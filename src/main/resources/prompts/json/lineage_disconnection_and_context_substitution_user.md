## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect **Lineage Disconnection and Context Substitution** along the graph's causal paths into its terminal result: a substitute reaches the terminal output in place of the computed value. Applies in any domain, not only financial.

---

## STRUCTURAL REFERENCE DATA
Computed by an exhaustive, deterministic traversal of the CPG. The root and leaf sets are exact and complete; the duplicate-name set covers exact name matches only. Membership is a lead, not a verdict.

- **Root variable IDs** (values supplied to the computation rather than calculated within it; no operation produces them as its `resultId`): $ROOT_VARIABLE_IDS$
- **Leaf variable IDs** (no operation consumes them as an argument): $LEAF_VARIABLE_IDS$
- **Leaf variables that share their exact display name (`descriptor.name`) with another variable**: $DUPLICATE_NAME_LEAF_IDS$

---

## CHECK
**Lineage Disconnection and Context Substitution** (root variables, duplicate-name leaves): for every leaf that is a computed value, look for a root that plays the same role (same or reworded name, same units, overlapping meta, same position in the formula) and is consumed in its place. Local replay of each step still passes, so it proves nothing; what matters is whether the terminal result derives from the graph's own computation chain. The duplicate-name set is a floor, not exhaustive. Also check roots that are never consumed: one may have been superseded by a substitute. Not defenses:
- the substitute is a root variable with full metadata (that is how a substitute looks in a CPG);
- its value is close to, or far from, the computed one (the value distance decides nothing);
- the computed value has no consumers (a bypassed value always looks like that).

Exempt: genuine constants (mathematical constants, unit conversions, precision contexts) with no computed counterpart for the same quantity anywhere in the graph.
