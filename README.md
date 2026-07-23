# compprov-analytics

A command-line tool that audits [compprov-core](https://github.com/compprov/compprov-core)
Calculation Provenance Graph (CPG) snapshots for signs of tampering — both with deterministic
structural checks and, optionally, with LLM-driven fraud-pattern analysis.

---

## Contents

- [What it does](#what-it-does)
- [Building](#building)
- [Usage](#usage)
- [Deterministic checks](#deterministic-checks)
- [LLM-based fraud analysis](#llm-based-fraud-analysis)
- [Plugins](#plugins)
- [Output layout](#output-layout)
- [License](#license)

---

## What it does

Given one or more CPG snapshot files (the JSON produced by `ComputationEnvironment.toJson(...)`
in compprov-core), `compprov-analytics`:

1. **Recomputes** every snapshot via `ComputationEnvironment.compute(...)` and diffs the replayed
   output against the recorded values, flagging any mismatch.
2. Runs a set of **structural integrity checks** over the graph (see
   [Deterministic checks](#deterministic-checks)) that catch common ways a CPG can be
   manipulated without breaking any single operation's local math.
3. Optionally sends the snapshot to an LLM under five **fraud-pattern prompts** (see
   [LLM-based fraud analysis](#llm-based-fraud-analysis)) that reason about the graph's topology
   and lineage rather than just its arithmetic.
4. Writes per-file findings plus an aggregated `out/summary.md` across all processed files.

## Building

Requires Java 17+ and Maven. `compprov-core` must be resolvable from your local Maven
repository (build it from [compprov-core](https://github.com/compprov/compprov-core) first, or
depend on a published version):

```bash
mvn package
```

The `maven-shade-plugin` produces a single runnable fat jar at
`target/compprov-analytics-0.1.0.jar`, with `io.compprov.analytics.cli.Main` as its main class
and `META-INF/services` entries merged (`ServicesResourceTransformer`) so that both this jar's
own defaults and any bundled dependencies' SPI providers are preserved.

## Usage

```bash
java -jar compprov-analytics.jar \
  --cpgpath=<path-to-cpg-file> [--cpgpath=<path-to-cpg-file> ...] \
  [--plugin=<path-to-plugin-jar> ...] \
  [--executePrompts=<true/false> ...] \
  [--intercallTimeoutMs=<ms>]
```

| Argument | Required           | Description                                                                                                                        |
|---|--------------------|------------------------------------------------------------------------------------------------------------------------------------|
| `--cpgpath=<path>` | Yes, at least one  | Path to a CPG JSON snapshot to analyze. Repeatable to process multiple files in one run.                                           |
| `--plugin=<path>` | No                 | Path to a plugin jar providing `EnvironmentCustomizer` and/or `ChatModel` implementations (see [Plugins](#plugins)). Repeatable.   |
| `--intercallTimeoutMs=<ms>` | No, default `0`    | Pause inserted before each LLM call, to stay under a provider's rate limit when a snapshot triggers all five prompts back to back. |
| `--executePrompts=<ms>` | No, default `true` | When false - even if ChatModel is provided throigh the plugin, prompt execution is skipped.                                        |

Running with no arguments prints this usage summary and exits with status `1`.

## Deterministic checks

For each snapshot, `Main.processFile` runs the following checks and, on a hit, appends a
human-readable line to that file's `highlights.json` and writes the offending
variables/operations to a dedicated JSON file under the file's output directory:

| Check | Flags | Output file |
|---|---|---|
| **Replay mismatch** | A recorded variable's value differs from what replaying the graph's operations actually produces | `violated-computations.json` |
| **Multiple leaves** | More than one terminal (unconsumed) output — a healthy single-outcome computation should converge on one | `leaves.json` |
| **Unused roots** | Input variables that are never consumed by any operation | `unused.json` |
| **Multiple math contexts** | More than one distinct `MathContext` used in the graph — a possible sign of selective rounding | `math-contexts.json` |
| **Multi-used variables** | A non-`MathContext` variable consumed by more than one operation — a possible sign of double-counting | `multi-used.json` |
| **Scaling operations present** | Any `setScale` operation in the graph — worth a manual look for rounding manipulation | `scaling-operations.json` |
| **Broken chronology** | An operation or its result/arguments carry timestamps out of causal order (started/finished/created not monotonically increasing) | `chrono-violations.json` |

`roots.json` and `leaves.json` are always written (not only on a hit) so the full input/output
surface of the graph is available for inspection alongside the flagged subsets.

These checks are graph-shape checks, not domain checks — they don't know what a "tax" or
"revenue" variable means. That's what the LLM prompts below are for.

## LLM-based fraud analysis

For every snapshot, `compprov-analytics` runs it through five prompt templates in
`io.compprov.analytics.ai.Prompt`, each describing a distinct provenance-fraud pattern for the
model to look for:

| Prompt | Attack pattern |
|---|---|
| Calculation omission | A cost/liability or revenue/credit is computed correctly in an isolated subgraph but never wired into the final aggregation |
| Lineage disconnection | Context substitution / a value's causal chain is silently rerouted or severed |
| Precision tampering | Rounding or precision is manipulated to shift the result in a favorable direction |
| Semantic violation | A value is cast or reinterpreted across an incompatible semantic type/context |
| Double counting | A value flows into the final result through more than one path, inflating the total |

Each prompt has a **markdown** template (`src/main/resources/prompts/markdown/`) and a
**JSON** template (`src/main/resources/prompts/json/`), differing only in the requested output
format; both embed the snapshot verbatim in place of `$CPG$`.

**Without a chat model** (no plugin supplies a `ChatModel`), the tool still renders the markdown
prompt template for each of the five patterns into that snapshot's output directory — ready to
paste into the LLM of your choice by hand — and the corresponding column in `out/summary.md`
reads `—`.

**With a chat model** (see [Plugins](#plugins)), the tool renders the JSON prompt template
instead, sends it to the model, and expects a JSON response matching
`PromptProcessingResult(verdict, confidence_score, markdown_report)`. The raw JSON is saved as
`<prompt>_result.json`, and `markdown_report` is rendered into `<prompt>_result.md` via
`templates/markdown_result.md`. Every prompt's `verdict`/`confidence_score` also feeds into that
file's row and `out/summary.md`'s overview table.

## Plugins

`compprov-analytics` ships with only the built-in compprov-core wrappers and no chat model
configured — it can validate the graph shape of any snapshot, but replaying a snapshot that uses
custom domain types (rather than the built-in `BigDecimal`/`BigInteger`/etc.) and running the LLM
prompts both require a plugin.

A plugin is any jar on disk passed via `--plugin=<path>`. For each one, `Main.processPlugin`
opens a `URLClassLoader` over the jar (parented to `Main`'s own class loader, so the jar doesn't
need to bundle `compprov-core` or `langchain4j-open-ai` itself) and looks up providers via
`java.util.ServiceLoader`:

| Service interface | Effect |
|---|---|
| `io.compprov.core.EnvironmentCustomizer` | Applied immediately to the shared `ComputationEnvironment` — typically registers wrappers/deserializers for domain-specific types used in the snapshot |
| `dev.langchain4j.model.chat.ChatModel` | Becomes the model used for every LLM prompt for the rest of the run |

See [compprov-plugin-example](https://github.com/compprov/compprov-plugin-example) for a
worked example of both: a `Amount`/`Rate` domain-type `EnvironmentCustomizer` and a
Gemini-backed `ChatModel`.

## Output layout

```
out/
├── summary.md                              Aggregated overview + highlights across all --cpgpath files
└── <cpg-filename>/
    ├── summary.md                          Detailed per-file report: validity, highlights, and fraud-pattern verdicts
    │                                        (or, with no chat model, a pointer to each prompt file and how to run it manually)
    ├── highlights.json                     This file's flagged issues, as a JSON array
    ├── roots.json / leaves.json            Full input/output variable surface
    ├── unused.json                         Unused root variables
    ├── violated-computations.json          Variables/operations where replay disagreed with the recording
    ├── multi-used.json                     Variables consumed by more than one operation
    ├── math-contexts.json                  All MathContext variables found
    ├── scaling-operations.json             All setScale operations and their variables
    ├── chrono-violations.json              Operations/variables with out-of-order timestamps
    └── <prompt>_prompt.md                  Rendered prompt sent (or ready to send) to an LLM, per fraud pattern
    └── <prompt>_result.json                Raw LLM response (only when a ChatModel plugin is active)
    └── <prompt>_result.md                  Rendered verdict + confidence + report (only when a ChatModel plugin is active)
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
