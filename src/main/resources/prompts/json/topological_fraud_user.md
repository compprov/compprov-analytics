## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect **Topological & Provenance Fraud** along the graph's causal paths into its terminal result: Calculation Omission (a mandatory contributor never reaches the terminal output), Lineage Disconnection and Context Substitution (a substitute reaches it in place of the computed value), and Double Counting (the same entity reaches it more than once). Applies in any domain, not only financial.

---

## STRUCTURAL REFERENCE DATA
Computed by an exhaustive, deterministic traversal of the CPG. The root, leaf, multi-use and repeated-argument sets are exact and complete, and the multi-use and repeated-argument sets together cover every literal reuse of a variable ID; the duplicate-name set covers exact name matches only. Membership is a lead, not a verdict.

- **Root variable IDs** (values supplied to the computation rather than calculated within it; no operation produces them as its `resultId`): $ROOT_VARIABLE_IDS$
- **Leaf variable IDs** (no operation consumes them as an argument): $LEAF_VARIABLE_IDS$
- **Variable IDs consumed as an argument by more than one operation** (`MathContext` variables excluded): $MULTIUSED_VARIABLE_IDS$
- **Operations that consume the same variable more than once** (e.g., `add(x, x)` or an ID repeated in an `addBulk` list): $REPEATED_ARGUMENT_OPERATION_IDS$
- **Leaf variables that share their exact display name (`descriptor.name`) with another variable**: $DUPLICATE_NAME_LEAF_IDS$

---

## TOPOLOGICAL CHECKS

1. **Calculation Omission** (leaf variables): the true terminal output(s) are among them. For every other leaf, reconstruct the formula the terminal result should reflect and ask: should this leaf be part of it (a mandatory adjustment, deduction, credit, correction, cross-check)? If yes and it does not reach the terminal output, flag Calculation Omission. A standalone output with no claim on the terminal result is benign; "kept just for reference" is not a defense when its name or meta says it belongs in the formula.

2. **Lineage Disconnection and Context Substitution** (root variables, duplicate-name leaves): for every leaf that is a computed value, look for a root that plays the same role (same or reworded name, same units, overlapping meta, same position in the formula) and is consumed in its place. Local replay of each step still passes, so it proves nothing; what matters is whether the terminal result derives from the graph's own computation chain. The duplicate-name set is a floor, not exhaustive. Also check roots that are never consumed: one may have been superseded by a substitute. Not defenses:
   - the substitute is a root variable with full metadata (that is how a substitute looks in a CPG);
   - its value is close to, or far from, the computed one (the value distance decides nothing);
   - the computed value has no consumers (a bypassed value always looks like that).

   Exempt: genuine constants (mathematical constants, unit conversions, precision contexts) with no computed counterpart for the same quantity anywhere in the graph.

3. **Double Counting**: trace each case to the terminal output, not just the first subtotal. Duplication counts whether it inflates or deflates the total, including a cost netted into a subtotal and subtracted again from a later aggregate.
   - **Multi-used variables**: legitimate when the variable is a shared parameter feeding independent branches or a proportional split whose shares add up to the whole; duplication when the same value converges into the same total more than once.
   - **Repeated-argument operations**: legitimate only as a power (`multiply(x, x)`) or as repeated addition whose multiple is stated by the result's name or meta ("doubled", "6 months") and matches the rest of the graph (e.g., six monthly periods actually present). Otherwise `add(x, x)` is a disguised double count.
   - **Same entity under different IDs**: two roots holding the same entity, or a passthrough re-wrap that gives a value a new ID.
