## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect **Topological Accumulation Fraud via Double Counting** along the graph's causal paths into its terminal result: the same entity reaches the terminal output more than once. Applies in any domain, not only financial.

---

## STRUCTURAL REFERENCE DATA
Computed by an exhaustive, deterministic traversal of the CPG. The root, leaf, multi-use and repeated-argument sets are exact and complete, and the multi-use and repeated-argument sets together cover every literal reuse of a variable ID. Membership is a lead, not a verdict.

- **Root variable IDs** (values supplied to the computation rather than calculated within it; no operation produces them as its `resultId`): $ROOT_VARIABLE_IDS$
- **Leaf variable IDs** (no operation consumes them as an argument; the true terminal output(s) are among them): $LEAF_VARIABLE_IDS$
- **Variable IDs consumed as an argument by more than one operation** (`MathContext` variables excluded): $MULTIUSED_VARIABLE_IDS$
- **Operations that consume the same variable more than once** (e.g., `add(x, x)` or an ID repeated in an `addBulk` list): $REPEATED_ARGUMENT_OPERATION_IDS$

---

## CHECK
**Double Counting**: trace each case to the terminal output, not just the first subtotal. Duplication counts whether it inflates or deflates the total, including a cost netted into a subtotal and subtracted again from a later aggregate.
- **Multi-used variables**: legitimate when the variable is a shared parameter feeding independent branches or a proportional split whose shares add up to the whole; duplication when the same value converges into the same total more than once.
- **Repeated-argument operations**: legitimate only as a power (`multiply(x, x)`) or as repeated addition whose multiple is stated by the result's name or meta ("doubled", "6 months") and matches the rest of the graph (e.g., six monthly periods actually present). Otherwise `add(x, x)` is a disguised double count.
- **Same entity under different IDs**: two roots holding the same entity, or a passthrough re-wrap that gives a value a new ID.
