## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect potential **Precision and Scale Tampering** attacks or subtle arithmetic logic flaws.

Focus on numerical accuracy, rounding modes, and precision degradation.

---

## ATTACK VECTOR DEFINITION: Precision and Scale Tampering
Precision and Scale Tampering occurs when an adversary or faulty business logic exploits how numbers are scaled, rounded, or converted between data types or units. This includes forcibly reducing an intermediate value's scale before completing an aggregation (causing residual value leakage), using a non-standard rounding mode to consistently drain sub-cent fractions into an attacker-controlled pool (Salami Slicing), reducing precision through arbitrary scaling operations, or downcasting a precise decimal type to `float`, `double`, or integer mid-pipeline.

**Note:**
- Operations may involve primitives, high-precision numeric types, or domain-specific wrapper classes (e.g., custom currency, unit, amount objects or any other datatypes from various domains) where rounding, truncation, and scale constraints are embedded directly in the wrapper's internal context. Verify that premature division or domain-specific wrapper constraints are not causing unintended precision degradation.
- Calculations may represent an overall operation or a single step within a cyclic process; analyze the CPG context, operation lineage, and descriptors to evaluate potential recurrence.
- The `risk_score` for potential precision and truncation flaws depends on both materiality and execution context:
    - **Materiality Override:** A large or high-impact arithmetic error represents a high `risk_score` regardless of cyclicity — a critical precision failure in a single, non-repeating execution is still a material flaw.
    - **Cyclic Scaling:** For subtle or small-unit rounding/truncation discrepancies, scale your `risk_score` based on your confidence in the operation's cyclic or high-frequency nature: assign a lower risk score if the flaw is isolated and non-cyclic, and increase the score as your confidence grows that the flaw compounds across repeated executions.

Recompute every operation with deep enough precision rational arithmetic and compare against the reported value at each step ($\Delta = |Exact\_Result - Reported\_Result|$). A boundary-sized discrepancy alone is not sufficient for an anomaly verdict — see EXPECTED_INVARIANTS for what actually distinguishes tampering from ordinary rounding.

<EXPECTED_INVARIANTS>
- Asset conservation must hold across all intermediate steps, at a materiality threshold appropriate to the values involved — not at the level of single-ULP rounding noise on isolated operations. A single rounding-convention difference (truncate vs. round-to-nearest) can never differ from the alternative by more than 1 unit at the target scale; that is the mathematical ceiling of "wrong rounding mode on one operation," not evidence of tampering by itself.
- Rounding mode must default to HALF_EVEN / HALF_UP unless explicitly bounded. When an operation carries an explicit `mc` (MathContext) argument, check it directly — a result consistent with that declared context is not a violation regardless of what a different default would have produced. When no MathContext argument exists at all (common for some domain value-wrapper types), the graph provides no ground truth for "the correct" convention, so an isolated 1-unit discrepancy is not a confirmed violation on its own.
- Genuine Salami Slicing requires consistency across a *scalable* operation population — one whose count can grow with users, transactions, or volume, so that many negligible individual skims accumulate into something material — and demonstrated accumulation toward an identifiable beneficiary or sink, not mere directional repetition. A bias repeated only across a small, structurally bounded population does not qualify, however consistent the direction, because there is no way to "run it more times" to extract more value. Likewise, if deltas from multiple flagged operations cancel out in the final reported result rather than accumulating, that is evidence against a beneficiary-driven exploit, not a more sophisticated version of one.
- Scale conversions between units must strictly preserve the source asset's native precision and maintain exact arbitrary-precision representations without unhandled intermediate truncations.
</EXPECTED_INVARIANTS>

