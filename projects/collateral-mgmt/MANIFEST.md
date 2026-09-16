# collateral-mgmt

Layer 3. Depends on **collateral-opt** and **legal-netting**, and on nothing else. Package root
`collateral_mgmt::`, prefixes `CMG_` (tables + view), `Cmg_` (joins), `Cmg` (filters + class
names), `cmg` (set ids).

Collateral management under netting agreements — the daily margin cycle, run.

collateral-opt says which piece is cheapest to deliver. legal-netting says what the enforceable
population is and what it is worth net. Neither runs the day. The day is: measure, gate, call,
dispute, agree, deliver, settle — plus substitutions and the interest on cash collateral, which
run alongside the cycle rather than inside it.

**Three things to know before using it.**

1. **The THRESHOLD and the MINIMUM TRANSFER AMOUNT are two different tests and both live on
   `CmgMarginCall` as applied values.** The threshold is the unsecured exposure the CSA
   tolerates; clearing it means something is *owed*. The MTA is the floor below which nothing is
   *transferred* even though it is owed. `exceedsThreshold()` and `clearsMinimumTransfer()` are
   separate derived properties for exactly that reason, and a call is only made when both are
   true. **A row exists for the call that was NOT made** — `isBelowMta` is the reason, and the
   desk has to be able to evidence it. A missing row is indistinguishable from a failed batch.

   The two values are **copied onto the call**, not read live off `CmgMarginTerms`. They are what
   was applied on the day; tomorrow's amendment must not rewrite today's call.

2. **A dispute splits the call, it does not reject it.** `disputedAmount` is the number the desk
   chases; `undisputedAmount` still moves today. So `CmgAgreedTransfer.agreedAmount` is
   deliberately not the call amount, and `shortfallAmount()` is the desk's live exposure to the
   argument. `CmgDisputeItem` is what makes the disputed total attributable — one dispute is
   almost never one cause.

3. **This project adds a THIRD, DIFFERENT or-join.** collateral-core's disjunction is *issuer OR
   asset class* and decides eligibility. collateral-opt's is *instrument OR issuer* and decides
   opportunity cost. This one is *agreement OR currency* and decides what cash collateral earns:

       Join Cmg_BalanceCashRate(CMG_CASH_BALANCE.AGREEMENT_ID = CMG_CASH_RATE_SCHEDULE.AGREEMENT_ID or CMG_CASH_BALANCE.BALANCE_CURRENCY = CMG_CASH_RATE_SCHEDULE.RATE_CURRENCY)

   An interest election is negotiated in a particular CSA *or* it is the house rate for a
   currency, never both on one row. A balance matches on either axis, so
   `collateral_mgmt::CmgApplicableCashRates` is to-**many on both ends**, exactly like the two
   above it. Read `$balance.applicableRates` and pick the agreement-specific one where there is
   one; do not assume a single rate.

## Which netting set is authoritative here

legal-netting's MANIFEST flags the overlap: `legal_netting::NetSet` and
`exposure_agg::ExaNettingSet` are different classes about the same `nettingSetId`, meeting
through `NetSetNetExposure`.

**This project treats `legal_netting::NetSet` as authoritative and never reaches for the other.**
Three reasons, in order of weight:

* **A margin call is a contractual act.** The threshold, the MTA, the rounding increment, the
  independent amount and the substitution consent right are all clauses of one master agreement
  and its Credit Support Annex. `NetSet` is the closure of that agreement — it is *what the
  contract covers*. `ExaNettingSet` is the measurement side's grouping key: the label under which
  exposure was aggregated. You cannot call for margin against a grouping key, and you cannot
  enforce a threshold that is not in a document.
* **The dispute mechanics need the contract, not the number.** Escalation deadlines, the
  dealer-poll fallback, and whether the taker may refuse a substitution are all readable from the
  agreement side and from nowhere else.
* **Mechanically, exposure-agg is not a declared dependency of this project.** It is in the
  transitive closure through legal-netting, so a reference to `ExaNettingSet` would compile —
  which is exactly the undeclared-dependency defect `check.py` exists to catch. The contract
  forbids it and the modelling reason above means nothing is lost by the ban.

The measurement side is still reachable, and should be reached *through* the set:
`$call.nettingSet.nettedExposures` is legal-netting's own cross-project property, and
`$call.capitalTreatment.netExposureAmount` is the **recognised** number, which is the one a call
should actually be struck against. `CmgCallCapitalTreatment` is a **two-column** join, (set,
date), because the treatment a call was struck against is the one for that day.

Note that `NetCapitalTreatment.recognisedAsNet` may be false — a set with no current clean
opinion is measured **gross**, and the call against it is correspondingly larger. That is not an
error to be smoothed over in a downstream report.

## Exports

| element | kind | note |
| --- | --- | --- |
| `collateral_mgmt::CmgMarginTerms` | class | **the gate**: threshold, minimum transfer amount, rounding, independent amount, consent right. Keyed `termsId`, not `agreementId` — one master carries VM and IM terms and an amendment is a new row. Derived `isCurrent()`, `effectiveThreshold()` |
| `collateral_mgmt::CmgMarginCall` | class | **the day's call**, keyed `callId`. A row exists even when no call was made (`isBelowMta`). `thresholdApplied` / `mtaApplied` are the values used on the day. Derived `uncoveredExposure()`, `exceedsThreshold()`, `clearsMinimumTransfer()`, `roundingDifference()`, `isOpen()` |
| `collateral_mgmt::CmgCallEvent` | class | keyed (call, **seq**) — the cycle audit trail. The same status recurs, so a status column on the call cannot carry it |
| `collateral_mgmt::CmgDispute` | class | keyed `disputeId`; **`disputedAmount` is the number the desk chases**, `undisputedAmount` still moves today. `cobDate` / `disputeStatus` denormalised for the rollup. Derived `isOpen()`, `isEscalated()`, `disputedProportion()` |
| `collateral_mgmt::CmgDisputeItem` | class | keyed (dispute, item) — our number against theirs, per break. Derived `difference()`, `isResolved()` |
| `collateral_mgmt::CmgAgreedTransfer` | class | keyed `callId`; what was actually agreed, which is the call amount only when nothing was disputed. Derived `shortfallAmount()` |
| `collateral_mgmt::CmgCollateralMovement` | class | **the grain of delivery**: one piece or one cash amount, out or back. A substitution is two of these in opposite directions. Derived `valueNetOfHaircut()`, `isCash()` |
| `collateral_mgmt::CmgSettlement` | class | keyed `movementId`; what the custodian did, and the fail. Derived `isFailed()`, `unsettledValue()` |
| `collateral_mgmt::CmgSubstitution` | class | swap a pledged piece out for a lot in — and the taker's **consent**, which is what makes it a request. `proposalId` is optional: a desk substitutes for reasons the optimiser knows nothing about. Derived `isConsented()`, `valueGap()` |
| `collateral_mgmt::CmgCashBalance` | class | keyed (agreement, currency, **date**); the cash interest accrues on. `agreementId` / `balanceCurrency` are the or-join's left side. Derived `netMovement()` |
| `collateral_mgmt::CmgCashRateSchedule` | class | **the disjunctive class**: exactly one of `agreementId` / `rateCurrency` is set; `rateBasis` records which. `hasZeroFloor` false means the accrual goes negative and the poster pays. Derived `isAgreementSpecific()`, `isCurrencyDefault()`, `isLive()`, `spreadFraction()` |
| `collateral_mgmt::CmgInterestAccrual` | class | keyed (agreement, currency, **date**), the same grain as the balance. Rate and day-count fraction stored beside the amount so the number is reproducible. Derived `recomputedAccrual()`, `isPaid()` |
| `collateral_mgmt::CmgInterestPayment` | class | the periodic settlement; `isNettedIntoCall` is why this is a class — netted interest never appears in a cash statement. Derived `unpaidAmount()` |
| `collateral_mgmt::CmgDisputeDaySummary` | class | **AGGREGATION** — the desk's morning worklist, over view `CMG_DISPUTE_DAY_SUMMARY`, grouped (date, status). Derived `averageDisputed()` |
| `collateral_mgmt::CmgApplicableCashRates` | association | **the or-join**: CmgCashBalance[*] `applicableRates` ↔ CmgCashRateSchedule[*] `ratedBalances` — to-many BOTH ways |
| `collateral_mgmt::CmgTermsCalls` | association | CmgMarginTerms[0..1] `governingTerms` ↔ CmgMarginCall[*] `governedCalls` |
| `collateral_mgmt::CmgCallEvents` | association | CmgMarginCall[1] `eventCall` ↔ CmgCallEvent[*] `callEvents` |
| `collateral_mgmt::CmgCallDisputes` | association | CmgMarginCall[1] `disputedCall` ↔ CmgDispute[*] `disputes` |
| `collateral_mgmt::CmgDisputeItems` | association | CmgDispute[1] `itemDispute` ↔ CmgDisputeItem[*] `disputeItems` |
| `collateral_mgmt::CmgCallAgreed` | association | CmgMarginCall[0..1] `agreedCall` ↔ CmgAgreedTransfer[0..1] `agreedTransfer` |
| `collateral_mgmt::CmgCallMovements` | association | CmgMarginCall[0..1] `movementCall` ↔ CmgCollateralMovement[*] `movements` |
| `collateral_mgmt::CmgMovementSettlement` | association | CmgCollateralMovement[0..1] `settledMovement` ↔ CmgSettlement[0..1] `settlement` |
| `collateral_mgmt::CmgSubstitutionMovements` | association | CmgSubstitution[0..1] `substitution` ↔ CmgCollateralMovement[*] `substitutionMovements` |
| `collateral_mgmt::CmgBalanceAccrual` | association | CmgCashBalance[0..1] `accruedBalance` ↔ CmgInterestAccrual[0..1] `accrual`; **three-column** join |
| `collateral_mgmt::CmgPaymentAccruals` | association | CmgInterestPayment[0..1] `interestPayment` ↔ CmgInterestAccrual[*] `paidAccruals` |
| `collateral_mgmt::CmgCallNettedInterest` | association | CmgMarginCall[0..1] `nettedIntoCall` ↔ CmgInterestPayment[*] `nettedInterestPayments` |
| `collateral_mgmt::CmgDisputeSummaryLink` | association | CmgDispute[*] `disputesInDay` ↔ CmgDisputeDaySummary[0..1] `daySummary`; two-column join |
| `collateral_mgmt::CmgCallFundingPlan` | association | **cross-project**: collateral_opt::CopPostingPlan[0..1] `fundingPlan` ↔ CmgMarginCall[*] `answeredCalls` |
| `collateral_mgmt::CmgMovementLot` | association | **cross-project**: collateral_opt::CopInventoryLot[0..1] `deliveredLot` ↔ CmgCollateralMovement[*] `deliveringMovements` |
| `collateral_mgmt::CmgSubstitutionProposalLink` | association | **cross-project**: collateral_opt::CopSubstitutionProposal[0..1] `optimiserProposal` ↔ CmgSubstitution[*] `substitutionRequests` |
| `collateral_mgmt::CmgCallNettingSet` | association | **cross-project**: legal_netting::NetSet[0..1] `nettingSet` ↔ CmgMarginCall[*] `marginCalls` — the defining reach |
| `collateral_mgmt::CmgTermsNettingSet` | association | **cross-project**: legal_netting::NetSet[0..1] `securedSet` ↔ CmgMarginTerms[*] `marginTerms` |
| `collateral_mgmt::CmgBalanceNettingSet` | association | **cross-project**: legal_netting::NetSet[0..1] `collateralisedSet` ↔ CmgCashBalance[*] `cashBalances` |
| `collateral_mgmt::CmgCallCapitalTreatment` | association | **cross-project**: legal_netting::NetCapitalTreatment[0..1] `capitalTreatment` ↔ CmgMarginCall[*] `marginCallsRaised`; **two-column** join |
| `collateral_mgmt::Store` | store | 13 tables `CMG_*`, 1 aggregation view, 18 joins `Cmg_*` (1 disjunctive), 8 filters `Cmg*`; includes `collateral_opt::Store` and `legal_netting::Store` |
| `collateral_mgmt::Mapping` | mapping | 14 class sets `cmg*`, 21 association mappings; includes `collateral_opt::Mapping` and `legal_netting::Mapping` |

14 classes, 21 associations.

## Properties added to upstream classes

Downstream projects see these on the dependency classes once collateral-mgmt is in the graph:

| class | property | type |
| --- | --- | --- |
| `collateral_opt::CopPostingPlan` | `answeredCalls` | `collateral_mgmt::CmgMarginCall[*]` |
| `collateral_opt::CopInventoryLot` | `deliveringMovements` | `collateral_mgmt::CmgCollateralMovement[*]` |
| `collateral_opt::CopSubstitutionProposal` | `substitutionRequests` | `collateral_mgmt::CmgSubstitution[*]` |
| `legal_netting::NetSet` | `marginCalls` | `collateral_mgmt::CmgMarginCall[*]` |
| `legal_netting::NetSet` | `marginTerms` | `collateral_mgmt::CmgMarginTerms[*]` |
| `legal_netting::NetSet` | `cashBalances` | `collateral_mgmt::CmgCashBalance[*]` |
| `legal_netting::NetCapitalTreatment` | `marginCallsRaised` | `collateral_mgmt::CmgMarginCall[*]` |

## Tables and the view

| table | primary key | note |
| --- | --- | --- |
| `CMG_MARGIN_TERMS` | `TERMS_ID` | `THRESHOLD_AMOUNT` + `MINIMUM_TRANSFER_AMOUNT` are the gate; FK `NETTING_SET_ID` → `NET_SET` |
| `CMG_MARGIN_CALL` | `CALL_ID` | FK `TERMS_ID`, `PLAN_ID` → `COP_POSTING_PLAN`, `NETTING_SET_ID` → `NET_SET` |
| `CMG_CALL_EVENT` | `CALL_ID` + `EVENT_SEQ` | composite |
| `CMG_DISPUTE` | `DISPUTE_ID` | the table the view reads; `COB_DATE` / `DISPUTE_STATUS` denormalised for grouping |
| `CMG_DISPUTE_ITEM` | `DISPUTE_ID` + `ITEM_CODE` | composite |
| `CMG_AGREED_TRANSFER` | `CALL_ID` | one per call at most |
| `CMG_MOVEMENT` | `MOVEMENT_ID` | FK `LOT_ID` → `COP_INVENTORY_LOT`, `SUBSTITUTION_ID` |
| `CMG_SETTLEMENT` | `MOVEMENT_ID` | |
| `CMG_SUBSTITUTION` | `SUBSTITUTION_ID` | FK `PROPOSAL_ID` → `COP_SUBSTITUTION_PROPOSAL`, nullable |
| `CMG_CASH_BALANCE` | `AGREEMENT_ID` + `BALANCE_CURRENCY` + `COB_DATE` | composite; the or-join's left side is the first two |
| `CMG_CASH_RATE_SCHEDULE` | `RATE_SET_ID` | **the disjunctive table**: exactly one of `AGREEMENT_ID` / `RATE_CURRENCY` per row |
| `CMG_INTEREST_ACCRUAL` | `AGREEMENT_ID` + `ACCRUAL_CURRENCY` + `ACCRUAL_DATE` | composite; FK `PAYMENT_ID` |
| `CMG_INTEREST_PAYMENT` | `PAYMENT_ID` | FK `NETTED_INTO_CALL_ID` → `CMG_MARGIN_CALL` |
| `CMG_DISPUTE_DAY_SUMMARY` | `COB_DATE` + `DISPUTE_STATUS` | **VIEW**, `~groupBy` over `CMG_DISPUTE`: `count`, `sum(DISPUTED_AMOUNT)`, `sum(UNDISPUTED_AMOUNT)`, `max(DISPUTED_AMOUNT)` |

## Joins

Disjunctive: **`Cmg_BalanceCashRate`**.

Internal: `Cmg_TermsCall`, `Cmg_CallEvent`, `Cmg_CallDispute`, `Cmg_DisputeItem`,
`Cmg_CallAgreedTransfer`, `Cmg_CallMovement`, `Cmg_MovementSettlement`,
`Cmg_SubstitutionMovement`, `Cmg_AccrualPayment`, `Cmg_CallNettedInterest`, the **three-column**
`Cmg_BalanceAccrual`, and the **two-column** `Cmg_DisputeDaySummary`.

Into collateral-opt: `Cmg_CallFundingPlan`, `Cmg_MovementLot`, `Cmg_SubstitutionProposal`.

Into legal-netting: `Cmg_CallNettingSet`, `Cmg_TermsNettingSet`, `Cmg_BalanceNettingSet`, and the
**two-column** `Cmg_CallCapitalTreatment`.

## Set ids (extend or target these; they are a GLOBAL namespace)

`cmgMarginTerms`, `cmgMarginCall`, `cmgCallEvent`, `cmgDispute`, `cmgDisputeItem`,
`cmgAgreedTransfer`, `cmgMovement`, `cmgSettlement`, `cmgSubstitution`, `cmgCashBalance`,
`cmgCashRateSchedule`, `cmgInterestAccrual`, `cmgInterestPayment`, `cmgDisputeDaySummary`. All
root sets, one per class.

Every id is explicit, so the default ids (`collateral_mgmt_CmgMarginCall`) do **not** exist. A
downstream `extends [...]` or cross-project `AssociationMapping` must name one of the `cmg*` ids.

`collateral_mgmt::Mapping` includes both dependency mappings, so a downstream mapping that
includes this one also inherits every `cop*` and `net*` set id and, through them, the `col*`,
`idx*`, `lgl*`, `exa*`, `cp*`, `ci*` and `cg*` sets.

## Filters

`CmgOpenCalls` (`CLOSED_ON is null`), `CmgCallsMade` (`IS_BELOW_MTA = 0`), `CmgLiveDisputes`
(`RESOLVED_ON is null`), `CmgEscalatedDisputes` (`ESCALATED_ON is not null`),
`CmgCurrentMarginTerms` (`TERMINATED_DATE is null`), `CmgLiveCashRates` (`EXPIRY_DATE is null`),
`CmgFailedSettlements` (`FAIL_REASON is not null`), `CmgPendingSubstitutions`
(`CONSENT_RECEIVED_ON is null`). Declared and unapplied. No boolean literal anywhere — `= true`
does not parse.

## Notes for downstream

- **Do not read `roundedCallAmount` as "the exposure".** It is what was called after the
  threshold was deducted, the MTA gate applied and rounding done. The exposure is
  `exposureAmount`; the requirement before rounding is `creditSupportAmount`. Three different
  numbers, and reports that confuse them are how a desk explains a 0 call on a 40m move.
- **Three or-joins are now in play and they are three different disjunctions.** issuer-or-asset-
  class (eligibility), instrument-or-issuer (opportunity cost), agreement-or-currency (cash
  interest). None collapses into any other.
- `CmgCollateralMovement` and `collateral_opt::CopPostingPlanLine` are **not** the same thing and
  must not be unioned. A plan line is what the optimiser intended; a movement is what was
  instructed after the counterparty agreed, and the two differ by exactly the dispute.
- Interest can be **negative** (`CmgCashRateSchedule.hasZeroFloor` false,
  `CmgInterestAccrual.isNegativeAccrual`) and can be **netted rather than paid**
  (`CmgInterestPayment.isNettedIntoCall`). A cash-flow report that assumes positive-and-paid is
  wrong on both axes.
- **A grouping produces no row for an empty group.** A date on which nothing is `ESCALATED` is
  absent from `CmgDisputeDaySummary`, not present at zero. A worklist that must show every status
  has to drive off the status list, not off this view.
- The store is a **diamond twice over**: `collateral_core::Store` arrives through
  `collateral_opt::Store` and again through `legal_netting::Store`'s `exposure_agg` include, and
  `core_party::Store` by more paths than that. Each resolves once. The mapping is the same
  diamond.
- No `###Data`, no Runtime, no seeded rows.

## Verify

    python3 scripts/projects/check.py collateral-mgmt   # compiles
