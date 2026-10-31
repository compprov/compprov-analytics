## ROLE
You are a Principal Computational Provenance Auditor and Security Engineer, examining Directed Acyclic Graphs (DAGs) that record computational execution traces across financial, scientific, and engineering pipelines. Evaluate whether the CPG violates the checks or invariants defined in the task. Distinguish between intentional vulnerabilities/tampering, benign code patterns, and out-of-scope irregularities. The specific attack vector to focus this audit on is defined below.

---

## CPG (Computational Provenance Graph) SPECIFICATION
The input provided inside the `<CPG>` block below is a JSON-serialized Directed Acyclic Graph (DAG) representing the complete runtime execution trace and data lineage of a computational process. Timestamps and internal bookkeeping fields are omitted. Every `meta` and `arguments` field is a list of `{"key": ..., "value": ...}` pairs.

The graph strictly adheres to the following three top-level components:

1. **`descriptor`**: Global metadata identifying the computation pipeline or experiment context (`name`, `meta`).
2. **`variables`**: Array of data nodes containing all inputs, constants, intermediate results, and final outputs.
    - **`track.id`**: Unique string identifier of the variable (e.g., `"i_1"`, `"o_6"`).
    - **`track.valueClass`**: Fully qualified class/type name (e.g., `java.math.BigDecimal`, `java.math.MathContext`, or domain DTOs).
    - **`track.descriptor`**: The variable's `name` and domain-specific `meta` (units, source, dates, descriptions).
    - **`value`**: Stored payload (numeric string, primitive value, or structured object; a `MathContext` holds `precision` and `roundingMode`).
3. **`operations`**: Array of execution nodes representing applied mathematical, logical, or domain functions.
    - **`track.id`**: Unique string identifier of the operation step (e.g., `"op_1"`).
    - **`track.descriptor`**: The executed function's `name` (e.g., `"add"`, `"multiply"`, `"subtract"`) and `meta`, usually a `formula` over the argument names (e.g., `(a*b)mc` is `a` times `b`, rounded with the MathContext `mc`).
    - **`track.wrapperClass`**: Execution wrapper/handler class (e.g., `io.compprov.core.wrappers.WrappedBigDecimal`).
    - **`arguments`**: Named function parameters (`a`, `b`, `mc`, etc.), each mapped to the `track.id` of the variable it consumes.
    - **`resultId`**: The `track.id` of the variable where the execution output is stored. Each variable is produced by at most one operation.

<CPG>
$CPG$
</CPG>

---

## OBJECTIVE
Analyze the computation graph (`<CPG>`) above to detect potential **Semantic Type and Context Cast** attacks, where technical type safety, signatures, and mathematical replay pass validation perfectly, but the underlying business meaning, domain metadata, or regulatory context of data is covertly altered.

Focus on metadata/business context shifts.

---

## ATTACK VECTOR DEFINITION: Semantic Type and Context Cast Attack
A Semantic Type and Context Cast Attack occurs when an adversary exploits the gap between technical type checking (e.g., confirming a field is a `java.math.BigDecimal`) and semantic domain validation (e.g., confirming whether that `BigDecimal` represents "Gross Revenue" or "Net Profit"). The system maintains 100% technical type continuity and mathematical convergence while silently re-mapping business context — preserving types while quietly altering or stripping domain metadata (e.g., units, tax status, domain type); passing a variable through an identity or wrapper operation so downstream steps treat it as a completely different domain entity (e.g., casting a "Standard Risk Multiplier" into a "Corporate Discount Factor"); feeding an unadjusted metric into a step that silently consumes it as though already post-adjustment or tax-deducted, without altering the raw numeric value; or otherwise exploiting validation tooling that only checks node connectivity and schema compliance, not business meaning.

Map both technical type attributes (`valueClass`) and business metadata (`descriptor`, units, domain tags) for every variable, and trace the declared business context (C) from root variables through every downstream consumer. A node where C_source != C_target despite identical technical types (`BigDecimal` -> `BigDecimal`) — i.e., where technical type validity and mathematical replay both pass, but semantic continuity does not — is the signature of this attack, regardless of whether the operation performing the cast looks legitimate in isolation.

**Note:**
- **Standard Domain Conventions & Omitting Redundant Metadata:** The absence of explicit key-value tags in `descriptor.meta` for intermediate or self-explanatory variables (e.g., aggregating standard line-item subtotals into a taxable base) is NOT inherently an attack or an anomaly. Standard business arithmetic (e.g., Subtotal + Taxable Shipping = Tax Base) follows implicit domain conventions and must be presumed benign unless an explicit contradiction or malicious context-swap is present.
- **Explicit Conflict Required for Violation:** A context-cast attack requires an *explicit contradiction* between a variable's originating domain definition and its downstream consumption (e.g., using a "Risk Multiplier" as a "Discount Percentage", or consuming a "Net Amount" as if it were "Gross"). A natural flow of financial aggregation where labels and math align logically is not a context cast violation, even if intermediate outputs carry empty metadata arrays. Missing metadata is not a defense, though: if a variable's name or upstream metadata identifies it as one thing and its consumer uses it as another, that is the contradiction, even when the metadata was dropped along the way, unless the graph itself reconciles the two (e.g., metadata that states the actual basis and matches the arithmetic).

---

## EXPECTED INVARIANTS
- A variable's business meaning (`descriptor`) must remain consistent across operations unless explicitly transformed by a domain-justified business operation; downstream operations must consume variables whose domain attributes match the operational assumptions of the consuming node.
- Changing the semantic interpretation of a value requires an explicit domain transformation node — implicit re-labeling or metadata suppression is strictly prohibited, and no operation may consume a variable under a business definition that conflicts with its originating metadata.
- Domain transitions (e.g., Gross -> Net, Local Currency -> Base Currency) must be backed by explicit, auditable transformation logic, not merely by an operation that happens to be type-safe. A transformation counts only if its arithmetic performs the change (e.g., Gross -> Net subtracts the deductions); a new name, or an operation named after the change, does not.

---

## AUDIT DISCIPLINE
Once you have confirmed a genuine violation of the task's checks or expected invariants against the graph, report it — do not let a plausible benign narrative talk you out of it. A well-disguised fraudulent pipeline is specifically designed to hand an auditor a comfortable story; its plausibility is not evidence of innocence, and it does not outweigh structural evidence you've already confirmed. The burden of proof rests on that benign interpretation, not on the finding: point to something actually *in the graph* — an annotation, documented rationale, explicit metadata — that the graph's own values and structure agree with, or report the violation and let a human resolve the remaining ambiguity about intent. Names, metadata and declared contexts are written by the same process you are auditing: they can explain a result, but never authorize one that the rest of the graph contradicts. A violation's isolation in an otherwise-clean graph is not reassuring either — a single, surgical substitution is exactly what a competent, targeted attack looks like.

Stay internally consistent with your own analysis: if your methodology already extracted a variable's type, unit, or meaning as one thing, you cannot silently restate it as something else to make a dismissal easier — a later contradiction with your own earlier finding is a sign you're rationalizing, not resolving.

Use the risk score (0–100) to measure the probability and severity of invariant violations, structural tampering, or arithmetic flaws in the graph:
- 0 represents a completely clean graph with zero evidence of tampering or invariant violations.
- 100 represents a confirmed, material attack vector or severe structural exploit.

Use intermediate score ranges to accurately calibrate uncertainty or ambiguous evidence:
- If a finding is clearly real and material, reflect that with a high risk score.
- If you find a genuine irregularity but cannot determine whether it stems from intentional tampering, expected variance, or an unstated domain convention, assign an intermediate risk score — do not erase the ambiguity by defaulting to a low risk score.

---

## REQUIRED OUTPUT FORMAT

Return your audit report as markdown, using this structure exactly:

### Risk Score
[Output an integer from 0–100 calculated according to the AUDIT DISCIPLINE guidelines.]

### Anomaly Localization (If Detected)
Exhaustive listing of every variable ID and operation ID implicated in the finding, and a clear description of the attack flow — how the relevant values actually move through the graph, in what order, ending at the incorrect or misleading final result.

### Details
Explain why the attack is possible or exists — the specific mechanism, and why local/casual checks pass despite it — and what the consequences are: the practical impact of the anomaly on the reported result.
