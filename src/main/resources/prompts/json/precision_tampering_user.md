## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect potential **Precision and Scale Tampering** attacks or subtle arithmetic logic flaws.

---

## ATTACK VECTOR DEFINITION: Precision and Scale Tampering
Precision and Scale Tampering occurs when an adversary or faulty business logic exploits the way numbers are scaled, rounded, or converted between data types/units — forcibly reducing an intermediate value's scale before completing an aggregation, causing residual value leakage; using a non-standard rounding mode (`ROUND_DOWN`/`FLOOR` instead of `HALF_EVEN`) to consistently drain sub-cent fractions into an attacker-controlled pool (Salami Slicing); mixing scales or token units (e.g., 6-decimal USDC with 18-decimal WEI/wstETH) without a proper scaling transformation; or downcasting a precise decimal type to `float`/`double`/integer mid-pipeline, degrading precision.

Recompute every operation with deep enough precision rational arithmetic and compare against the reported value at each step ($\Delta = |Exact\_Result - Reported\_Result|$). A boundary-sized discrepancy alone is not sufficient for an anomaly verdict — see EXPECTED_INVARIANTS for what actually distinguishes tampering from ordinary rounding.

<EXPECTED_INVARIANTS>
- Asset conservation must hold across all intermediate steps, at a materiality threshold appropriate to the values involved — not at the level of single-ULP rounding noise on isolated operations. A single rounding-convention difference (truncate vs. round-to-nearest) can never differ from the alternative by more than 1 unit at the target scale; that is the mathematical ceiling of "wrong rounding mode on one operation," not evidence of tampering by itself.
- Rounding mode must default to HALF_EVEN / HALF_UP unless explicitly bounded. When an operation carries an explicit `mc` (MathContext) argument, check it directly — a result consistent with that declared context is not a violation regardless of what a different default would have produced. When no MathContext argument exists at all (common for some domain value-wrapper types), the graph provides no ground truth for "the correct" convention, so an isolated 1-unit discrepancy is not a confirmed violation on its own.
- Genuine Salami Slicing requires consistency across a *scalable* operation population — one whose count can grow with users, transactions, or volume, so that many negligible individual skims accumulate into something material — and demonstrated accumulation toward an identifiable beneficiary or sink, not mere directional repetition. A bias repeated only across a small, structurally bounded population (e.g., one conversion per asset held in a portfolio/NAV calculation, capped by composition rather than volume) does not qualify, however consistent the direction, because there is no way to "run it more times" to extract more value. Likewise, if deltas from multiple flagged operations cancel out in the final reported result rather than accumulating, that is evidence against a beneficiary-driven exploit, not a more sophisticated version of one.
- Scale conversions between units must strictly preserve the source asset's native precision (e.g., 18 decimals for WEI/wstETH, 6 for USDC) and maintain exact arbitrary-precision representations without unhandled intermediate truncations.
</EXPECTED_INVARIANTS>

---

## RESPONSE FORMAT SPECIFICS FOR THIS TASK

Follow the JSON envelope and markdown_report structure described in the system message.

<VERDICT>["CLEAN", "ANOMALY DETECTED", "SUSPICIOUS LOGIC"]</VERDICT>
