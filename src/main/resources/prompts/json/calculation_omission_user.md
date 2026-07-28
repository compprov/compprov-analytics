## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect potential **Calculation Omission attacks**, where mandatory state changes—including both debit/cost entries (e.g., tax liabilities, operational costs) and credit/revenue entries (e.g., bonuses, profit windfalls, subsidies)—are calculated correctly in isolated subgraphs but intentionally severed or ignored during final net aggregation.

---

## ATTACK VECTOR DEFINITION: Calculation Omission
Calculation Omission occurs when a mandatory adjustment — a cost, credit, correction, or cross-check — is computed or measured correctly and transparently within the graph, but silently excluded from the final result the pipeline reports, biasing that result toward a more favorable or simpler outcome than a complete computation would produce. This is domain-agnostic, not limited to money: it applies equally to an excluded tax liability, a skipped correction factor in an engineering simulation, or an unused cross-validation measurement in a metrology pipeline. It typically shows up as a correctly-computed or correctly-measured variable left as an unconsumed dead-end, or a final aggregation/result operation whose arguments quietly omit a component that its own name, role, and metadata indicate it should include.

<EXPECTED_INVARIANTS>
- Every variable whose name, role, or metadata identifies it as a mandatory adjustment, deduction, credit, correction, or cross-check for the pipeline's final reported result — in any domain, not only financial — must have an active causal path into the operation that produces that result.
- Reconstruct the complete formula or computation the final result *should* reflect from every such qualifying variable, and compare it against what the final aggregation/result operation *actually* consumes; any qualifying variable present in the former but missing from the latter is the omission.
- No variable meeting that description may exist as an unconsumed dead-end (leaf) while the pipeline reports its result as though the computation were complete.
</EXPECTED_INVARIANTS>

---

## RESPONSE FORMAT SPECIFICS FOR THIS TASK

Follow the JSON envelope and markdown_report structure described in the system message.

<VERDICT>["CLEAN", "CALCULATION OMISSION DETECTED", "UNLINKED DEDUCTION"]</VERDICT>
