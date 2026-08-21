# collateral-opt

Layer 2. Depends on **collateral-core** and **index-core**, and on nothing else. Package root
`collateral_opt::`, prefixes `COP_` (tables + views), `Cop_` (joins), `Cop` (filters + class
names), `cop` (set ids).

Which piece of collateral goes to which agreement, and what that choice costs.

collateral-core says whether a piece is *allowed*; this project says which piece is
*cheapest to deliver*. Three costs decide it — the haircut, the funding of the asset, and the
**opportunity cost** of pledging something that could have been lent instead — and the plan is
only feasible once it has been rolled up per issuer against the concentration caps.

**Two things to know before using it.**

1. **This project adds a SECOND, DIFFERENT or-join.** collateral-core's disjunction is
   *issuer OR asset class* and decides eligibility. This one is *instrument OR issuer* and
   decides opportunity cost:

       Join Cop_LotLendingDemand(COP_INVENTORY_LOT.INSTRUMENT_ID = COP_LENDING_DEMAND.INSTRUMENT_ID or COP_INVENTORY_LOT.ISSUER_ID = COP_LENDING_DEMAND.ISSUER_ID)

   A securities-lending quote is written against a named line or against an issuer as general
   collateral, never both, and a lot earns the fee on either axis. So
   `collateral_opt::CopLendingOpportunity` is to-**many on both ends**, exactly like
   collateral-core's. Take `$lot.lendingDemands.borrowFeeBps->max()`; do not assume one quote.

2. **The AGGREGATIONS are two `~groupBy` views over `COP_PLAN_LINE`,** and the concentration
   one is what makes the optimiser correct rather than merely cheap: every line in a plan can
   be individually eligible and the plan can still put 40% of its value with one issuer.

## Exports

| element | kind | note |
| --- | --- | --- |
| collateral_opt::CopOptimisationRun | class | one dated execution of the optimiser; objective, solver status, `isCommitted` |
| collateral_opt::CopCostBasis | class | the price list — funding spread, substitution cost, haircut weight, index relief |
| collateral_opt::CopInventoryLot | class | **the supply side**: a lot in the box that *could* be posted. NOT a `ColCollateral` — that one is already pledged. Carries `instrumentId` / `issuerId` as OWN columns: they are the left side of the or-join |
| collateral_opt::CopLendingDemand | class | **the disjunctive class**: exactly one of `instrumentId` / `issuerId` is set; `demandBasis` records which. `borrowFeeBps` is the opportunity cost |
| collateral_opt::CopIndexBasketRule | class | a Schedule A clause written as index membership — "any constituent above 0.05% weight". Hangs off `ColEligibilitySchedule`, points at `IdxIndex` |
| collateral_opt::CopEligibilityAssessment | class | the RESOLVED verdict per (run, lot, agreement) — composite key. `matchedRuleCount` keeps the evidence of collateral-core's to-many match; `bindingRule` names the line that bound |
| collateral_opt::CopDeliveryScore | class | the cost stack per (run, lot, agreement) — composite key. Haircut + funding + opportunity − index relief, components stored not just the total |
| collateral_opt::CopPostingPlan | class | what the run decided to post to one agreement: required, planned, shortfall, feasible |
| collateral_opt::CopPostingPlanLine | class | **the grain**: one lot against one plan. Carries `runId` / `agreementId` / `issuerId` as own columns because a `~groupBy` groups on columns of its own table |
| collateral_opt::CopPlanAgreementSummary | class | **AGGREGATION** — plan totals per (run, agreement), over view `COP_PLAN_AGREEMENT_SUMMARY` |
| collateral_opt::CopIssuerConcentration | class | **AGGREGATION** — posted value per (run, agreement, issuer), over view `COP_ISSUER_CONCENTRATION`; joins to `ColConcentrationLimit` on the issuer axis |
| collateral_opt::CopSubstitutionProposal | class | swap a pledged `ColCollateral` out for a `CopInventoryLot` in — the asymmetry is the class |
| collateral_opt::CopLendingOpportunity | association | **the or-join**: CopInventoryLot[*] `lendingDemands` ↔ CopLendingDemand[*] `demandedLots` — to-many BOTH ways |
| collateral_opt::CopRunCostBasis | association | CopOptimisationRun `costBasis`[0..1] ↔ CopCostBasis `runs`[*] |
| collateral_opt::CopRunPlans | association | CopPostingPlan `run`[1] ↔ CopOptimisationRun `plans`[*] |
| collateral_opt::CopPlanLines | association | CopPostingPlanLine `plan`[1] ↔ CopPostingPlan `lines`[*] |
| collateral_opt::CopLotPlanLines | association | CopPostingPlanLine `lot`[1] ↔ CopInventoryLot `plannedLines`[*] |
| collateral_opt::CopRunAssessments | association | CopEligibilityAssessment `assessmentRun`[1] ↔ CopOptimisationRun `assessments`[*] |
| collateral_opt::CopLotAssessments | association | CopEligibilityAssessment `assessedLot`[1] ↔ CopInventoryLot `lotAssessments`[*] |
| collateral_opt::CopRunScores | association | CopDeliveryScore `scoreRun`[1] ↔ CopOptimisationRun `deliveryScores`[*] |
| collateral_opt::CopLotScores | association | CopDeliveryScore `scoredLot`[1] ↔ CopInventoryLot `lotScores`[*] |
| collateral_opt::CopPlanSummaryLink | association | CopPostingPlan `planSummary`[0..1] ↔ CopPlanAgreementSummary `summarisedPlan`[0..1] |
| collateral_opt::CopPlanConcentrations | association | CopPostingPlan `issuerConcentrations`[*] ↔ CopIssuerConcentration `concentrationPlan`[0..1] |
| collateral_opt::CopRunSubstitutions | association | CopSubstitutionProposal `substitutionRun`[1] ↔ CopOptimisationRun `substitutionProposals`[*] |
| collateral_opt::CopProposalIncomingLot | association | CopSubstitutionProposal `incomingLot`[0..1] ↔ CopInventoryLot `incomingProposals`[*] |
| collateral_opt::CopPlanAgreement | association | **cross-project**: collateral_core::ColCollateralAgreement[0..1] `agreement` ↔ CopPostingPlan[*] `postingPlans` |
| collateral_opt::CopAssessmentBindingRule | association | **cross-project**: collateral_core::ColEligibilityRule[0..1] `bindingRule` ↔ CopEligibilityAssessment[*] `boundAssessments` |
| collateral_opt::CopBasketRuleSchedule | association | **cross-project**: collateral_core::ColEligibilitySchedule[0..1] `basketSchedule` ↔ CopIndexBasketRule[*] `indexBasketRules` |
| collateral_opt::CopConcentrationAgainstLimit | association | **cross-project**: collateral_core::ColConcentrationLimit[*] `bindingLimits` ↔ CopIssuerConcentration[*] `issuerRollups` — to-many both ways |
| collateral_opt::CopProposalOutgoing | association | **cross-project**: collateral_core::ColCollateral[0..1] `outgoingCollateral` ↔ CopSubstitutionProposal[*] `optimiserProposals` |
| collateral_opt::CopLotIndexMembership | association | **cross-project**: index_core::IdxConstituent[*] `indexMemberships` ↔ CopInventoryLot[*] `inventoryLots` — to-many both ways |
| collateral_opt::CopBasketRuleIndex | association | **cross-project**: index_core::IdxIndex[0..1] `basketIndex` ↔ CopIndexBasketRule[*] `collateralBasketRules` |
| collateral_opt::Store | store | 10 tables `COP_*`, 2 aggregation views, 20 joins `Cop_*` (1 disjunctive), 6 filters `Cop*`; includes `collateral_core::Store` and `index_core::Store` |
| collateral_opt::Mapping | mapping | 12 class sets `cop*`, 20 association mappings; includes `collateral_core::Mapping` and `index_core::Mapping` |

12 classes, 20 associations.

## Tables and views

| table | primary key | note |
| --- | --- | --- |
| `COP_RUN` | `RUN_ID` | FK `COST_BASIS_ID` |
| `COP_COST_BASIS` | `COST_BASIS_ID` | |
| `COP_INVENTORY_LOT` | `LOT_ID` | `INSTRUMENT_ID` + `ISSUER_ID` are the or-join's left side |
| `COP_LENDING_DEMAND` | `DEMAND_ID` | **the disjunctive table**: exactly one of `INSTRUMENT_ID` / `ISSUER_ID` per row |
| `COP_INDEX_BASKET_RULE` | `BASKET_RULE_ID` | FK `SCHEDULE_ID` → `COL_ELIGIBILITY_SCHEDULE`, `INDEX_ID` → `IDX_INDEX` |
| `COP_ELIGIBILITY_ASSESSMENT` | `RUN_ID` + `LOT_ID` + `AGREEMENT_ID` | composite; FK `BINDING_RULE_ID` → `COL_ELIGIBILITY_RULE` |
| `COP_DELIVERY_SCORE` | `RUN_ID` + `LOT_ID` + `AGREEMENT_ID` | composite |
| `COP_POSTING_PLAN` | `PLAN_ID` | FK `RUN_ID`, `AGREEMENT_ID` → `COL_AGREEMENT` |
| `COP_PLAN_LINE` | `LINE_ID` | the grain both views read; `RUN_ID` / `AGREEMENT_ID` / `ISSUER_ID` denormalised for grouping |
| `COP_SUBSTITUTION_PROPOSAL` | `PROPOSAL_ID` | `OUTGOING_COLLATERAL_ID` → `COL_COLLATERAL`, `INCOMING_LOT_ID` → `COP_INVENTORY_LOT` |
| `COP_PLAN_AGREEMENT_SUMMARY` | `RUN_ID` + `AGREEMENT_ID` | **VIEW**, `~groupBy` over `COP_PLAN_LINE`: `sum(POST_VALUE)`, `sum(OPPORTUNITY_COST)`, `count(LINE_ID)` |
| `COP_ISSUER_CONCENTRATION` | `RUN_ID` + `AGREEMENT_ID` + `ISSUER_ID` | **VIEW**, the same rollup grouped on the issuer as well |

## Joins

Disjunctive: **`Cop_LotLendingDemand`**.

Internal: `Cop_RunCostBasis`, `Cop_RunPlan`, `Cop_PlanLine`, `Cop_LotPlanLine`,
`Cop_RunAssessment`, `Cop_LotAssessment`, `Cop_RunScore`, `Cop_LotScore`,
`Cop_RunSubstitution`, `Cop_ProposalIncomingLot`, and the two **two-column** joins onto the
rollups, `Cop_PlanSummary` and `Cop_PlanIssuerConcentration` (both columns must match or a plan
picks up every other run's totals).

Cross-project: `Cop_PlanAgreement` (→ `COL_AGREEMENT`), `Cop_AssessmentBindingRule`
(→ `COL_ELIGIBILITY_RULE`), `Cop_BasketRuleSchedule` (→ `COL_ELIGIBILITY_SCHEDULE`),
`Cop_ConcentrationLimit` (→ `COL_CONCENTRATION_LIMIT.LIMIT_ISSUER_ID`), `Cop_ProposalOutgoing`
(→ `COL_COLLATERAL`), `Cop_LotIndexMembership` (→ `IDX_CONSTITUENT.INSTRUMENT_ID`),
`Cop_BasketRuleIndex` (→ `IDX_INDEX`).

## Set ids (extend or target these; they are a GLOBAL namespace)

`copRun`, `copCostBasis`, `copInventoryLot`, `copLendingDemand`, `copIndexBasketRule`,
`copEligibilityAssessment`, `copDeliveryScore`, `copPostingPlan`, `copPlanLine`,
`copPlanSummary`, `copIssuerConcentration`, `copSubstitutionProposal`. All root sets, one per
class.

`collateral_opt::Mapping` includes both dependency mappings, so a downstream mapping that
includes this one also inherits every `col*` and `idx*` set id and, through them, `cpLegalEntity`,
`ciBase` with its subtype sets, and the `cg*` sets. A downstream `AssociationMapping` end
pointing into this project must name one of the `cop*` ids above; the default ids do not exist.

## Filters

`CopAvailableLots` (`WITHDRAWN_DATE is null`), `CopUnencumberedLots` (`IS_ENCUMBERED = 0`),
`CopLiveLendingDemand` (`EXPIRY_DATE is null`), `CopCommittedRuns` (`IS_COMMITTED = 1`),
`CopOpenProposals` (`WITHDRAWN_DATE is null`), `CopCurrentBasketRules` (`WITHDRAWN_DATE is
null`). Declared and unapplied. No boolean literal anywhere — `= true` does not parse.

## Derived properties

| on class | property | note |
| --- | --- | --- |
| `CopOptimisationRun.netBenefit()` | `Float[1]` | posted value less the lending revenue given up |
| `CopCostBasis.fundingCostFraction()` | `Float[1]` | `bps / 10000.0`; `Float` because `/` widens |
| `CopInventoryLot.isAvailable()` / `.unitValue()` | `Boolean[1]` / `Float[1]` | a lot leaves the box by a date, never by deletion |
| `CopLendingDemand.isInstrumentSpecific()` / `.isGeneralCollateral()` | `Boolean[1]` | the two halves of the disjunction on a single quote |
| `CopIndexBasketRule.weightWindowPct()` | `Float[1]` | how wide the admitted band is |
| `CopEligibilityAssessment.hasCompetingRules()` / `.isPostable()` | `Boolean[1]` | `matchedRuleCount > 1` is normal, not an error |
| `CopDeliveryScore.recomputedTotalBps()` | `Float[1]` | the stored total restated from its parts |
| `CopPostingPlan.isCovered()` / `.coveragePct()` | `Boolean[1]` / `Float[1]` | |
| `CopPostingPlanLine.haircutAmount()` / `.netPostValue()` | `Float[1]` | |
| `CopPlanAgreementSummary.netOfOpportunity()` | `Float[1]` | |
| `CopIssuerConcentration.averageLotValue()` | `Float[1]` | `postedValue / lotCount->toFloat()` — `Integer` against `Float` types as `Number`, and `Number` is not a `Float` |

## Notes for downstream

- **Two or-joins are now in play and they are different disjunctions.** collateral-core's is
  issuer-or-asset-class and answers *may I post this*; this one is instrument-or-issuer and
  answers *what does posting it cost me*. Neither collapses into the other.
- `CopInventoryLot` and `collateral_core::ColCollateral` are not the same thing and must not be
  unioned. A lot is unpledged supply; a `ColCollateral` is already posted under an agreement. A
  lot becomes a `ColCollateral` only when a plan line is executed, and `CopSubstitutionProposal`
  is the one class that touches both — outgoing is a `ColCollateral`, incoming is a lot.
- The view-backed measures (`totalPostValue`, `postedValue`, `lineCount`, `lotCount`) are
  declared `[1]` and not `[0..1]`: a `sum` or `count` over a group that exists is never null.
  But **a grouping produces no row for an empty group** — an agreement the run posted nothing
  to is absent from `CopPlanAgreementSummary`, not present at zero. A feasibility report must
  read that case off `CopPostingPlan`, which does have a row.
- `CopEligibilityAssessment` is the place collateral-core's to-many rule match has already been
  resolved. Read `bindingRule` and `tightestHaircutPct` rather than re-walking
  `$lot.matchingRules` — and note that an `isExclusion` rule is applied here, after the join,
  because the or-join returns it like any other match.
- Index membership is reached with `$lot.indexMemberships`, which is to-many: an instrument is a
  constituent of several indices and of the same index at every rebalance. From there
  index-core's own chain does the rest —
  `$lot.indexMemberships.rebalance.regionWeights`,
  `$lot.indexMemberships.issuerCountry.subRegion.macroRegion.code` — and this project declares
  no join for either.
- The store is a **diamond**: `core_instrument::Store` arrives by both dependency paths and is
  one database either way. The mapping is the same diamond and `ciBase` resolves once.
- No `###Data`, no Runtime, no seeded rows.

## Verify

    python3 scripts/projects/check.py collateral-opt   # compiles
