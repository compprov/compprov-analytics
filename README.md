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
- [Pruning and minification](#pruning-and-minification)
- [Evaluation](#evaluation)
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
3. Optionally sends the snapshot to an LLM under a set of **fraud-pattern prompts** (three run by
   default, covering six named attack patterns — see
   [LLM-based fraud analysis](#llm-based-fraud-analysis)) that reason about the graph's topology
   and lineage rather than just its arithmetic.
4. Writes per-file findings plus an aggregated summary under a fresh, timestamped run directory
   (see [Output layout](#output-layout)) — each run gets its own folder, so nothing from a
   previous run is ever overwritten.

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

Alternatively, skip building it yourself: every published [GitHub release](https://github.com/compprov/compprov-analytics/releases)
has the same fat jar attached as `compprov-analytics-<version>.jar`.

## Usage

```bash
java -jar compprov-analytics.jar \
  [--cpgpath=<path-to-cpg-file> ...] \
  [--cpgfolder=<path-to-folder> ...] \
  [--plugin=<path-to-plugin-jar> ...] \
  [--promptProcessingMode=<mode>] \
  [--workDir=<dir>] \
  [--thresholds=<clean>,<suspicious>] \
  [--threads=<count>] \
  [--minify=<true|false>] \
  [--llmTemplates=<name>[,<name>...]] \
  [--<templateKey>=<path> ...]
```

| Argument | Required | Description |
|---|---|---|
| `--cpgpath=<path>` | Yes, at least one `--cpgpath`/`--cpgfolder` (except with `CONTINUE_ASYNC`) | Path to a CPG JSON snapshot to analyze. Repeatable to process multiple files in one run. |
| `--cpgfolder=<path>` | Yes, at least one `--cpgpath`/`--cpgfolder` (except with `CONTINUE_ASYNC`) | Directory to scan recursively for `*.json` CPG files (extension matched case-insensitively); every match is processed as a separate file. Repeatable. |
| `--plugin=<path>` | No | Path to a plugin jar providing `EnvironmentCustomizer`, `ChatModel` and/or `BatchChatModel` implementations (see [Plugins](#plugins)). Repeatable. |
| `--promptProcessingMode=<mode>` | No, default `GENERATE_MARKDOWN` | How prompts are handled, see [Prompt processing modes](#prompt-processing-modes). |
| `--workDir=<dir>` | No, default `<timestamp>/out`; required with `CONTINUE_ASYNC` | Existing output folder. With `CONTINUE_ASYNC` it must hold the `batch.json` written by `EXECUTE_ASYNC`. |
| `--thresholds=<clean>,<suspicious>` | No, default `25,70` | Overrides the `risk_score` cutoffs used to derive the displayed verdict label: below `<clean>` is `CLEAN`, below `<suspicious>` is `SUSPICIOUS LOGIC`, otherwise `ANOMALY DETECTED`. `<clean>` must fall within `(0, 100)` and `<suspicious>` within `(<clean>, 100]`; an invalid pair is logged and the run stops. |
| `--threads=<count>` | No, default `1` | Number of CPG files whose prompts are sent in parallel in `PROCESS_SYNC` mode. Prompts of a single file still run one after another. |
| `--minify=<true\|false>` | No, default `false` | Embed the pruned CPG into prompts as compact JSON, see [Pruning and minification](#pruning-and-minification). |
| `--llmTemplates=<names>` | No, default: `topological_fraud`, `precision_tampering`, `semantic_type_and_context_cast_attack` | Comma-separated list of prompt names to generate/execute, e.g. `calculation_omission,precision_tampering` (see [LLM-based fraud analysis](#llm-based-fraud-analysis) for the full list of names). |
| `--<templateKey>=<path>` | No, repeatable | Overrides a bundled prompt template with a file from disk, e.g. `--calculation_omission_user=/path/to/my_template.md` replaces the bundled `calculation_omission_user.md`. Run with no arguments to print the full list of valid template keys. |

Running with no arguments prints this usage summary and exits with status `1`. An unknown or
misspelled argument stops the run instead of being ignored.

### Prompt processing modes

| Mode | Needs | What happens |
|---|---|---|
| `GENERATE_MARKDOWN` | nothing | Runs the deterministic checks and writes standalone markdown prompts for manual use. No LLM is called. |
| `PROCESS_SYNC` | `ChatModel` | Sends the JSON-mode prompts through the plugin's `ChatModel` one call at a time (files in parallel with `--threads`). |
| `EXECUTE_ASYNC` | `BatchChatModel` | Submits all prompts as one batch, saves `<workDir>/batch.json` and exits. |
| `PROCESS_ASYNC` | `BatchChatModel` | Submits a batch and waits for its results. |
| `CONTINUE_ASYNC` | `BatchChatModel`, `--workDir` | Waits for and processes the batch saved in `<workDir>/batch.json`. |

A batch that ends cancelled, expired or failed is still processed if the provider returns results:
failed requests are logged and reported with no verdict, and the rest are processed normally.

## Deterministic checks

For each snapshot, `Generator` runs the following checks and, on a hit, appends a
human-readable line to that file's `highlights.json` and writes the offending
variables/operations to a dedicated JSON file under the file's output directory:

| Check | Flags | Output file |
|---|---|---|
| **Replay mismatch** | A recorded variable's value differs from what replaying the graph's operations actually produces | `violated-computations.json` |
| **Multiple leaves** | More than one terminal (unconsumed) output — a healthy single-outcome computation should converge on one | `leaves.json` |
| **Duplicate-named leaf** | A leaf shares its exact `descriptor.name` with another variable elsewhere in the graph — a possible sign the leaf is an orphaned computation that a differently-sourced stand-in was substituted for | *(highlight only — also forwarded to the LLM prompts as `$DUPLICATE_NAME_LEAF_IDS$`)* |
| **Unused roots** | Input variables that are never consumed by any operation | `unused.json` |
| **Multiple math contexts** | More than one `MathContext` variable in the graph — a possible sign of selective rounding | `math-contexts.json` |
| **Multi-used variables** | A non-`MathContext` variable consumed by more than one operation — a possible sign of double-counting | `multi-used.json` |
| **Repeated arguments** | An operation consuming the same variable more than once, e.g. `add(x, x)` — invisible to the multi-used check, also a possible sign of double-counting | `repeated-arguments.json` (also forwarded to the LLM prompts as `$REPEATED_ARGUMENT_OPERATION_IDS$`) |
| **Scaling operations present** | Any `setScale` operation, or `plus(MathContext)` on a `BigDecimal`, in the graph — worth a manual look for rounding manipulation | `scaling-operations.json` |
| **Broken chronology** | An operation or its result/arguments carry timestamps out of causal order (started/finished/created not monotonically increasing) | `chrono-violations.json` |

`roots.json` and `leaves.json` are always written (not only on a hit) so the full input/output
surface of the graph is available for inspection alongside the flagged subsets.

These checks are graph-shape checks, not domain checks — they don't know what a "tax" or
"revenue" variable means. That's what the LLM prompts below are for.

## LLM-based fraud analysis

`compprov-analytics` defines seven named prompts in `io.compprov.analytics.ai.Prompt`, but
only **three run by default** — `topological_fraud` is a single merged prompt covering the three
path-multiplicity patterns (calculation omission, lineage disconnection, double counting) that
used to be three separate calls, so the three legacy single-pattern prompts are still bundled as
templates but excluded from the default `activePrompts` list:

| Prompt                | `--llmTemplates=` name | Default | Attack pattern                                                                                                                                               |
|-----------------------|---|:---:|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Topological fraud     | `topological_fraud` | ✓ | Merged prompt for the three path-multiplicity patterns below: a value's causal path count into the terminal result is 0 (omission), >1 (double counting), or 1 but from the wrong source (lineage disconnection) |
| Precision tampering   | `precision_tampering` | ✓ | Rounding or precision is manipulated to shift the result in a favorable direction                                                                            |
| Semantic violation    | `semantic_type_and_context_cast_attack` | ✓ | A value is cast or reinterpreted across an incompatible semantic type/context                                                                                |
| Calculation omission  | `calculation_omission` |  | A mandatory adjustment (cost, credit, or cross-check — financial or not) is computed correctly in an isolated subgraph but never wired into the final result |
| Lineage disconnection | `lineage_disconnection_and_context_substitution` |  | Context substitution / a value's causal chain is silently rerouted or severed                                                                                |
| Double counting       | `topological_accumulation_fraud_via_double_counting` |  | A value flows into the final result through more than one path, inflating or deflating the total                                                             |
| Single prompt         | `single_prompt` |  | Topological fraud, precision tampering and semantic violation in one call; the score is the most severe of the three, and the report starts with a per-vector verdict. Run it on its own with `--llmTemplates=single_prompt` |

Pass `--llmTemplates=` with a comma-separated subset of the names above to change which run — e.g.
`--llmTemplates=calculation_omission,lineage_disconnection_and_context_substitution,topological_accumulation_fraud_via_double_counting,precision_tampering,semantic_type_and_context_cast_attack`
reverts to the original five-prompt, one-pattern-per-call behavior instead of the merged
`topological_fraud` prompt, at the cost of an extra LLM call per snapshot. Because the three
path-multiplicity patterns share a single prompt by default, a positive `topological_fraud`
verdict doesn't by itself say *which* of the three patterns was found — see that prompt's own
report for the specific mechanism (omission/duplication/substitution) it identified.

Each prompt has a **standalone markdown template** (`src/main/resources/prompts/markdown/`) for
the no-chat-model path, and a **JSON-mode pair** (`src/main/resources/prompts/json/`) for the
chat-model path:

- `json/shared_system.md` — one file shared by every active prompt except `single_prompt`: role,
  CPG format spec, the actual `<CPG>` data, audit discipline, and the response format. It's
  identical across every call for a given snapshot, so a `ChatModel` that supports system-message
  caching (e.g. Anthropic's `cacheSystemMessages`) only pays to process it once per file.
- `json/<prompt>_user.md` — the attack-specific half: objective, attack definition, invariants,
  and the structural reference data (root/leaf/multi-used/repeated-argument/duplicate-named-leaf
  ID lists) that prompt needs.
- `json/single_prompt_system.md` / `json/single_prompt_user.md` — `single_prompt` doesn't use
  `shared_system.md`: its system prompt holds all three attack vectors and no graph data, and its
  user message holds the `<CPG>` and the structural reference data. Override them with
  `--single_prompt_system=<path>` and `--single_prompt_user=<path>`.

Both the markdown and JSON-mode templates substitute the same placeholders (`$CPG$`,
`$ROOT_VARIABLE_IDS$`, `$LEAF_VARIABLE_IDS$`, `$MULTIUSED_VARIABLE_IDS$`,
`$REPEATED_ARGUMENT_OPERATION_IDS$`, `$DUPLICATE_NAME_LEAF_IDS$`).

Any of these bundled templates can be swapped out at runtime with a file from disk via
`--<templateKey>=<path>` (e.g. `--calculation_omission_user=/path/to/my_template.md`,
`--shared_system=/path/to/my_shared_system.md`) — see the `--<templateKey>=<path>` row in
[Usage](#usage).

**In `GENERATE_MARKDOWN` mode**, the tool renders each prompt's standalone markdown template into
that snapshot's output directory — ready to paste into the LLM of your choice by hand — and the
corresponding column in the aggregate summary reads `—`.

**In the other modes** (see [Prompt processing modes](#prompt-processing-modes)), the tool sends
each prompt's system prompt and `<prompt>_user.md` as a `SystemMessage`/`UserMessage` pair, and
expects a JSON response
matching `PromptProcessingResult(risk_score, markdown_report)` — a single continuous `risk_score`
(0–100) rather than a discrete verdict enum, so the model isn't forced to pick a coarse label
before it's actually sure which side of a fuzzy line a finding falls on. The raw JSON is saved as
`<prompt>_result.json`, and `markdown_report` is rendered into `<prompt>_result.md` via
`templates/markdown_result.md`. `compprov-analytics` itself derives a `CLEAN` / `SUSPICIOUS LOGIC`
/ `ANOMALY DETECTED` label from `risk_score` using the `--thresholds=` cutoffs (see
[Usage](#usage)); both the numeric score and the derived label feed into that file's row and the
aggregate overview table.

If a model's response can't be parsed as JSON at all, a last-resort fallback slices out just the
`risk_score` and `markdown_report` fields textually so a result is still recovered (with a
placeholder report) rather than the whole file failing. `risk_score` is contractually 0-100: a
missing/non-numeric value, an empty response or a failed call marks that one prompt as `FAILED`
in the tables (and is logged) rather than failing the whole file, and a value outside 0-100 is
clamped rather than propagated as-is. A missing `markdown_report` is rendered as an empty report.

## Pruning and minification

Before a snapshot is embedded into a prompt, the fields that carry no audit signal are removed:
the `createdAt`/`startedAt`/`finishedAt` timestamps (chronology is checked deterministically),
`kind` and `numericId`. The pruned graph is pretty-printed by default; with `--minify=true` the
same fields are removed from the JSON tree and the graph is written without indentation or line
breaks. Values and field order are unchanged.

Measured on the 116-snapshot test corpus (see [Evaluation](#evaluation)), counted with the
`o200k_base` tokenizer:

| CPG text in the prompt | Tokens | Change |
|---|---:|---:|
| Full snapshot, pretty-printed | 963,339 | — |
| Pruned, pretty-printed (default) | 552,004 | −42.7% |
| Pruned, minified (`--minify=true`) | 363,786 | −34.1% vs pruned, −62.2% vs full |

Effect of `--minify=true` on whole runs (single prompt, 116 calls; input tokens as reported by the
provider, F1 as defined in [Evaluation](#evaluation)):

| Model | Runs (pretty / minified) | Input tokens, pretty → minified | F1, pretty → minified |
|---|---|---|---|
| Claude Haiku 5.5 | 3 / 3 | 865,138 → 663,519 (−23%) | 0.940 → 0.939 |
| GPT-6 Luna (medium) | 3 / 3 | 1,066,988 → 878,770 (−18%) | 0.912 → 0.929 |
| Gemini 3.5 Flash-Lite (medium) | 1 / 1 | 1,353,599 → 945,890 (−30%) | 0.898 → 0.889 |

Minified runs missed 12 of 273 synthetic-attack checks against 7 of 273 for the same models
pretty-printed (Fisher's exact test p = 0.35), and the F1 differences are within run-to-run
variation. Minifying gave no measurable loss in detection, so use it when input volume or cost
matters. Input tokens are usually cheaper than output tokens, so the saving on the total bill is
smaller than the input reduction.

## Evaluation

The prompts were evaluated on 116 CPG snapshots, available with all run outputs in
[compprov-plugin-example/samples](https://github.com/compprov/compprov-plugin-example/tree/master/samples/):

- 33 benign computations from several domains;
- 39 synthetic attacks: calculation omission 7, double counting 7, lineage disconnection 9,
  precision tampering 7, semantic tampering 7, and 2 legacy cases (replay and chronology);
- 17 real bugs from Defects4J (16 Commons Math and Chart-24) and their 17 fixed versions;
- 5 DeFi logic flaws modelled on GPTScan categories and 5 numerical defects modelled on NumScout.

A snapshot counts as flagged when any prompt returns `SUSPICIOUS LOGIC` or `ANOMALY DETECTED`
(default thresholds, never tuned) or the deterministic replay or chronology check fails.
Positives are the attacks, the buggy Defects4J versions, GPTScan and NumScout (66); negatives are
the benign graphs and the fixed Defects4J versions (50).

### What it finds

Flag rates pooled over 15 single-prompt runs of 9 models:

| Group | Flagged | Notes |
|---|---:|---|
| Double counting | 100% | |
| Lineage disconnection | 99.3% | |
| Calculation omission | 99.0% | |
| Semantic tampering | 97.1% | |
| GPTScan-style DeFi logic flaws | 97.3% | |
| Precision tampering | 92.4% | Hardest synthetic category; one insurance case is caught by about half the runs |
| Defects4J buggy (real bugs) | 82.7% | Two bugs (Math-102, Math-105) are almost never caught |
| NumScout-style numerical defects | 61.3% | Precision-loss trends and minor amount retention are the weak spots |
| Legacy replay/chronology attacks | 100% | Caught by the deterministic checks; the LLM alone flags 47% |
| Benign graphs (false positives) | 5.3% | Mostly one metrology graph that uses a domain-specific formula convention |
| Defects4J fixed (false positives) | 12.5% | |

Topological manipulations (omission, double counting, lineage substitution) and semantic misuse
are found reliably. Subtle rounding and precision-loss defects, and real bugs that only show as a
wrong formula, are found less often. The deterministic checks and the LLM complement each other:
replay and chronology tampering is caught deterministically even when the model misses it.

### Models

Single prompt, default thresholds, mean over runs where a model was run more than once:

| Model (effort) | Runs | Precision | Recall | F1 | Output tokens per run |
|---|---:|---:|---:|---:|---:|
| Claude Sonnet 5 | 1 | 0.941 | 0.970 | 0.955 | 969k |
| Claude Opus 5.5 | 1 | 0.969 | 0.939 | 0.954 | 235k |
| Gemini 4 Argon (medium) | 1 | 0.984 | 0.924 | 0.953 | 141k |
| Gemini 3.8 Flash (medium) | 1 | 1.000 | 0.894 | 0.944 | 463k |
| Claude Haiku 5.5 | 3 | 0.973 | 0.909 | 0.940 | 424k |
| GPT-6.1 Sol (medium) | 1 | 0.910 | 0.924 | 0.917 | 187k |
| GPT-6 Luna (medium) | 3 | 0.910 | 0.914 | 0.912 | 214k |
| Gemini 3.5 Flash-Lite (medium) | 1 | 0.934 | 0.864 | 0.898 | 291k |
| GPT-6.1 Terra (medium) | 1 | 0.894 | 0.894 | 0.894 | 156k |

The larger Claude models and Gemini 4 Argon lead, with Gemini 4 Argon using the fewest output
tokens. Claude Haiku 5.5 is the best of the low-cost models. Run-to-run variation for the same model
is up to about ±0.02 F1, so differences smaller than that are not meaningful.

### Recommendations confirmed by the tests

- **Use `single_prompt`.** On models run in both configurations, three separate prompts gave no
  consistent gain (mean F1 +0.005) and cost 2–3× the output tokens.
- **Keep medium reasoning effort.** High effort did not improve detection (GPT-6 Luna 0.904 vs
  0.912, Gemini 3.5 Flash-Lite 0.905 vs 0.898) and up to doubled the output tokens.
- **Keep the structural reference data.** Removing the root/leaf/multi-used ID lists from the
  prompt did not change F1 and increased output tokens by 16–30%.
- **Prune always, minify when input volume matters** (see above).
- **Batch execution is safe.** Batched runs scored within the run-to-run variation of synchronous
  runs, at the providers' batch discount.
- **Treat findings as leads for a reviewer.** Most false positives come from domain conventions
  the model disagrees with, so findings in specialized domains need expert review.

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
| `dev.langchain4j.model.chat.ChatModel` | Becomes the model used for every LLM prompt in `PROCESS_SYNC` mode |
| `dev.langchain4j.model.chat.BatchChatModel` | Becomes the model used in `EXECUTE_ASYNC`, `PROCESS_ASYNC` and `CONTINUE_ASYNC` modes; results must be returned in submission order |

See [compprov-plugin-example](https://github.com/compprov/compprov-plugin-example) for a worked
example of both: `Amount`/`Rate`/`OptionPosition` domain-type wrappers, and a `ChatModel` backed
by langchain4j's native `AnthropicChatModel` (with an `OpenAiChatModel` fallback), configured
entirely from environment variables.

## Output layout

Unless `--workDir` is given, each run gets its own timestamped directory
(`yyyy-MM-dd_HH-mm-ss.SSS/out/`), so nothing from a previous run is overwritten:

```
<yyyy-MM-dd_HH-mm-ss.SSS>/out/
├── summary.md                              Aggregated overview + highlights across all --cpgpath/--cpgfolder files
├── batch.json                              Batch id and file list (batch modes only), read back by CONTINUE_ASYNC
└── <n>_<cpg-filename>/                     n is the file's 1-based position in the processing order
    ├── summary.md                          Detailed per-file report: validity, highlights, and fraud-pattern verdicts
    │                                        (or, with no chat model, a pointer to each prompt file and how to run it manually)
    ├── highlights.json                     This file's flagged issues, as a JSON array
    ├── roots.json / leaves.json            Full input/output variable surface
    ├── unused.json                         Unused root variables
    ├── violated-computations.json          Variables/operations where replay disagreed with the recording
    ├── multi-used.json                     Variables consumed by more than one operation
    ├── repeated-arguments.json             Operations consuming the same variable more than once
    ├── math-contexts.json                  All MathContext variables found
    ├── scaling-operations.json             All setScale operations and their variables
    ├── chrono-violations.json              Operations/variables with out-of-order timestamps
    │
    │   # GENERATE_MARKDOWN mode:
    ├── <prompt>_prompt.md                  Standalone prompt, ready to paste into an LLM by hand
    │
    │   # Other modes:
    ├── shared_system_prompt.md             Shared system prompt — written once per file, not per prompt
    ├── single_prompt_system_prompt.md      single_prompt's own system prompt (only when it is active)
    ├── <prompt>_user_prompt.md             This prompt's user message
    ├── <prompt>_call_details.json          Call metadata: request time, duration, model name, response id,
    │                                        finish reason, and token usage — written even if the response
    │                                        couldn't be parsed (the messages themselves are in
    │                                        <system>_prompt.md / <prompt>_user_prompt.md above)
    ├── <prompt>_result.json                Raw LLM response (risk_score + markdown_report)
    └── <prompt>_result.md                  Rendered risk score + derived verdict label + report
```

Input and Output samples are available here: https://github.com/compprov/compprov-plugin-example/tree/master/samples/

## License

Apache License 2.0 — see [LICENSE](LICENSE).
