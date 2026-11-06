## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect **Calculation Omission** along the graph's causal paths into its terminal result: a mandatory contributor never reaches the terminal output. Applies in any domain, not only financial.

---

## STRUCTURAL REFERENCE DATA
Computed by an exhaustive, deterministic traversal of the CPG. The leaf set is exact and complete. Membership is a lead, not a verdict.

- **Leaf variable IDs** (no operation consumes them as an argument): $LEAF_VARIABLE_IDS$

---

## CHECK
**Calculation Omission** (leaf variables): the true terminal output(s) are among them. For every other leaf, reconstruct the formula the terminal result should reflect and ask: should this leaf be part of it (a mandatory adjustment, deduction, credit, correction, cross-check)? If yes and it does not reach the terminal output, flag Calculation Omission. A standalone output with no claim on the terminal result is benign; "kept just for reference" is not a defense when its name or meta says it belongs in the formula.
