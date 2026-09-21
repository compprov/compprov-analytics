## OBJECTIVE
Analyze the computation graph (`<CPG>`) provided in the system message to detect potential **Semantic Type and Context Cast** attacks, where technical type safety, signatures, and mathematical replay pass validation perfectly, but the underlying business meaning, domain metadata, or regulatory context of data is covertly altered.

Focus on metadata/business context shifts. 

---

## ATTACK VECTOR DEFINITION: Semantic Type and Context Cast Attack
A Semantic Type and Context Cast Attack occurs when an adversary exploits the gap between technical type checking (e.g., confirming a field is a `java.math.BigDecimal`) and semantic domain validation (e.g., confirming whether that `BigDecimal` represents "Gross Revenue" or "Net Profit"). The system maintains 100% technical type continuity and mathematical convergence while silently re-mapping business context — preserving types while quietly altering or stripping domain metadata (`domainType`, `units`, `taxStatus`); passing a variable through an identity or wrapper operation so downstream steps treat it as a completely different domain entity (e.g., casting a "Standard Risk Multiplier" into a "Corporate Discount Factor"); feeding an unadjusted metric into a step that silently consumes it as though already post-adjustment or tax-deducted, without altering the raw numeric value; or otherwise exploiting validation tooling that only checks node connectivity and schema compliance, not business meaning.

Map both technical type attributes (`valueClass`) and business metadata (`descriptor`, units, domain tags) for every variable, and trace the declared business context (C) from root variables through every downstream consumer. A node where C_source != C_target despite identical technical types (`BigDecimal` -> `BigDecimal`) — i.e., where technical type validity and mathematical replay both pass, but semantic continuity does not — is the signature of this attack, regardless of whether the operation performing the cast looks legitimate in isolation.

<EXPECTED_INVARIANTS>
- A variable's business meaning (`descriptor`) must remain consistent across operations unless explicitly transformed by a domain-justified business operation; downstream operations must consume variables whose domain attributes match the operational assumptions of the consuming node.
- Changing the semantic interpretation of a value requires an explicit domain transformation node — implicit re-labeling or metadata suppression is strictly prohibited, and no operation may consume a variable under a business definition that conflicts with its originating metadata.
- Domain transitions (e.g., Gross -> Net, Local Currency -> Base Currency) must be backed by explicit, auditable transformation logic, not merely by an operation that happens to be type-safe.
</EXPECTED_INVARIANTS>

**Note:**
- **Standard Domain Conventions & Omitting Redundant Metadata:** The absence of explicit key-value tags in `descriptor.meta` for intermediate or self-explanatory variables (e.g., aggregating standard line-item subtotals into a taxable base) is NOT inherently an attack or an anomaly. Standard business arithmetic (e.g., Subtotal + Taxable Shipping = Tax Base) follows implicit domain conventions and must be presumed benign unless an explicit contradiction or malicious context-swap is present.
- **Explicit Conflict Required for Violation:** A context-cast attack requires an *explicit contradiction* between a variable's originating domain definition and its downstream consumption (e.g., using a "Risk Multiplier" as a "Discount Percentage", or consuming a "Net Amount" as if it were "Gross"). A natural flow of financial aggregation where labels and math align logically is not a context cast violation, even if intermediate outputs carry empty metadata arrays.

