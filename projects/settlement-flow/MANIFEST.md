# settlement-flow

Layer 2. Depends on **settlement-core** and **cash-core** and on nothing else. Package root
`settlement_flow::`, prefixes `SFL_` (tables), `Sfl_` (joins), `Sfl` (filters), `sfl` (set
ids), `Sfl` (class names).

A settlement instruction produces a cash movement on its value date; the two are reconciled;
a failed settlement produces a claim. Nothing here re-models an instruction or a movement —
both are reached by **association**, which is how a class in `settlement_core::` acquires a
`cashLinks` property and a class in `cash_core::` acquires `settlementLinks` without either
project knowing this one exists.

The shape to know before using it: **the calendar is FOUR hops from a settlement link and
SIX from a claim.** settlement-core reaches it in two
(`SC_SETTLEMENT_INSTRUCTION` → `CC_MARKET` → `CC_CALENDAR`); standing a layer further out,
this project extends past that and re-reaches the same calendar the long way, through the
SSI and the venue. No calendar id is a column on any `SFL_` table.

## Exports

| element | kind | note |
| --- | --- | --- |
| settlement_flow::SflCashSettlementLink | class | the bridge: one instruction's cash leg ↔ one `CshCashMovement`; carries the FOUR-hop chain properties; `isMatched()`, `isConfirmed()` |
| settlement_flow::SflPaymentRoute | class | the path the cash takes to the venue — direct or via a correspondent; `isIndirect()` |
| settlement_flow::SflNostroMandate | class | standing rule: this venue + this currency ⇒ that cash account; dated `effectiveFrom`/`effectiveTo`; `isOpenEnded()` |
| settlement_flow::SflReconciliation | class | expected cash vs booked cash for one link; `isClean()`, `withinTolerance()`; `bookedValueDate`/`bookedAmount` are chain properties into the cash book |
| settlement_flow::SflReconciliationBreak | class | one unexplained difference, with an owner; `isOpen()`; optionally points at a `CshStatementLine` |
| settlement_flow::SflSettlementClaim | class | what a failed settlement produces; carries the SIX-hop chain; `isOpen()`, `isPaid()`, `isDisputed()`, `dailyInterest()` |
| settlement_flow::SflClaimInterestAccrual | class | daily interest on an open claim; key `(claimId, accrualDate)` |
| settlement_flow::SflClaimEvent | class | dated audit trail of a claim; key `(claimId, eventSeq)` |
| settlement_flow::SflSettlementFailCost | class | what the fail cost US, as opposed to what we are claiming; `isAbsorbed()` |
| settlement_flow::SflCashForecast | class | expected cash per account, currency and value date; `netExpected()` |
| settlement_flow::SflForecastLine | class | one link's contribution to a forecast; key `(forecastId, lineSeq)`; `signedAmount()` |
| settlement_flow::SflFundingShortfall | class | forecast need vs available balance; `isCovered()`, `uncoveredAmount()`, `coverageRatio()` |
| settlement_flow::Store | store | 12 `SFL_*` tables, 18 `Sfl_*` joins, filters `SflOpenBreak`, `SflOpenClaim`, `SflLiveMandate`; includes `settlement_core::Store` and `cash_core::Store` |
| settlement_flow::Mapping | mapping | 12 class sets `sfl*`, 18 association mappings; includes `settlement_core::Mapping` and `cash_core::Mapping`. No enumeration mappings — this project declares no enums |

No enums, no profiles, no functions, no `###Data`, no Runtime.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| SFL_CASH_SETTLEMENT_LINK | LINK_ID | INSTRUCTION_ID→SC; (INSTITUTION_ID, ACCOUNT_NO, MOVEMENT_ID)→CSH, all three nullable until the cash is booked |
| SFL_PAYMENT_ROUTE | ROUTE_ID | LINK_ID, MANDATE_ID |
| SFL_NOSTRO_MANDATE | MANDATE_ID | PLACE_BIC→SC and (INSTITUTION_ID, ACCOUNT_NO)→CSH on one row |
| SFL_RECONCILIATION | RECON_ID | LINK_ID; AMOUNT_DIFFERENCE is signed |
| SFL_RECON_BREAK | BREAK_ID | RESOLVED_ON null means open; the five cash columns are the statement line it was raised against |
| SFL_SETTLEMENT_CLAIM | CLAIM_ID | LINK_ID, FAIL_REASON_CODE→SC_FAIL_REASON; SETTLED_ON null means open |
| SFL_CLAIM_INTEREST | CLAIM_ID, ACCRUAL_DATE | one row per day a claim is open |
| SFL_CLAIM_EVENT | CLAIM_ID, EVENT_SEQ | history; SFL_SETTLEMENT_CLAIM.CLAIM_STATUS is the current value |
| SFL_FAIL_COST | COST_ID | CLAIM_ID nullable — a cost with no recoverable claim is the normal case |
| SFL_CASH_FORECAST | FORECAST_ID | (INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, FORECAST_DATE) is the natural grain |
| SFL_FORECAST_LINE | FORECAST_ID, LINE_SEQ | LINK_ID is what the line came from |
| SFL_FUNDING_SHORTFALL | SHORTFALL_ID | FORECAST_ID; the four cash columns match CSH_CASH_BALANCE, VALUE_DATE against BALANCE_DATE |
| SflOpenBreak | filter | `SFL_RECON_BREAK.RESOLVED_ON is null` |
| SflOpenClaim | filter | `SFL_SETTLEMENT_CLAIM.SETTLED_ON is null` |
| SflLiveMandate | filter | `SFL_NOSTRO_MANDATE.EFFECTIVE_TO is null` |

## Joins

Internal: `Sfl_LinkRoute`, `Sfl_RouteMandate`, `Sfl_LinkReconciliation`, `Sfl_ReconBreak`,
`Sfl_LinkClaim`, `Sfl_ClaimAccrual`, `Sfl_ClaimEvent`, `Sfl_ClaimCost`, `Sfl_ForecastLine`,
`Sfl_ForecastShortfall`, `Sfl_LineLink`.

Into settlement-core: `Sfl_LinkInstruction` (→ `SC_SETTLEMENT_INSTRUCTION`),
`Sfl_ClaimFailReason` (→ `SC_FAIL_REASON`), `Sfl_MandatePlace`
(→ `SC_PLACE_OF_SETTLEMENT`).

Into cash-core, each matching the FULL key because cash-core has no synthetic ids:
`Sfl_LinkMovement` (3 columns → `CSH_CASH_MOVEMENT`), `Sfl_MandateCashProfile` (2 →
`CSH_CASH_ACCOUNT_PROFILE`), `Sfl_BreakStatementLine` (5 → `CSH_STATEMENT_LINE`),
`Sfl_ShortfallBalance` (4 → `CSH_CASH_BALANCE`).

## Set ids

`sflCashSettlementLink`, `sflPaymentRoute`, `sflNostroMandate`, `sflReconciliation`,
`sflReconciliationBreak`, `sflSettlementClaim`, `sflClaimInterestAccrual`, `sflClaimEvent`,
`sflSettlementFailCost`, `sflCashForecast`, `sflForecastLine`, `sflFundingShortfall`

All explicit, so the default ids (`settlement_flow_SflCashSettlementLink` and friends) **do
not exist**. A downstream `extends [...]` or cross-project `AssociationMapping` must name the
`sfl*` id.

## Associations

Across the boundary — these add a property to a class this project does not own:

| association | end on the dependency's class | end here |
| --- | --- | --- |
| SflLinkInstruction | `ScSettlementInstruction.cashLinks[*]` | `SflCashSettlementLink.instruction[1]` |
| SflLinkMovement | `CshCashMovement.settlementLinks[*]` | `SflCashSettlementLink.movement[0..1]` |
| SflClaimFailReason | `ScFailReason.claims[*]` | `SflSettlementClaim.failReason[0..1]` |
| SflMandatePlace | `ScPlaceOfSettlement.nostroMandates[*]` | `SflNostroMandate.place[1]` |
| SflMandateCashProfile | `CshCashAccountProfile.mandates[*]` | `SflNostroMandate.cashProfile[0..1]` |
| SflBreakStatementLine | `CshStatementLine.settlementBreaks[*]` | `SflReconciliationBreak.statementLine[0..1]` |
| SflShortfallBalance | `CshCashBalance.settlementShortfalls[*]` | `SflFundingShortfall.balance[0..1]` |

Inside this project: `SflLinkRouting` (`route`/`routedLink`), `SflRouteMandate`
(`mandate`/`mandatedRoutes`), `SflLinkReconciliation` (`reconciliation`/`reconciledLink`),
`SflReconciliationBreaks` (`breaks`/`brokenReconciliation`), `SflLinkClaims`
(`claims`/`claimedLink`), `SflClaimAccruals` (`accruals`/`accruedClaim`), `SflClaimEvents`
(`events`/`eventClaim`), `SflClaimCosts` (`costs`/`costClaim`), `SflForecastLines`
(`lines`/`forecast`), `SflForecastShortfalls` (`shortfalls`/`shortfallForecast`),
`SflLineLink` (`sourceLink`/`forecastLines`).

Every `AssociationMapping` end names `[sourceSetId, targetSetId]`, because this project and
both dependencies declare their set ids explicitly and the bare `prop: [db]@Join` form only
resolves against defaults.

## The join chains

**Four hops**, on `SflCashSettlementLink` — the theme of this project:

    SFL_CASH_SETTLEMENT_LINK -@Sfl_LinkInstruction-> SC_SETTLEMENT_INSTRUCTION
                             -@Sc_InstructionSsi---> SC_STANDING_INSTRUCTION
                             -@Sc_SsiPlace---------> SC_PLACE_OF_SETTLEMENT
                             -@Sc_PlaceCalendar----> CC_CALENDAR

    settlementCalendarId:  [settlement_flow::Store]@Sfl_LinkInstruction > @Sc_InstructionSsi > @Sc_SsiPlace > @Sc_PlaceCalendar | [core_calendar::Store]CC_CALENDAR.CALENDAR_ID
    settlementCentre:      ... | [core_calendar::Store]CC_CALENDAR.FINANCIAL_CENTRE
    settlementWeekendDays: ... | [core_calendar::Store]CC_CALENDAR.WEEKEND_DAYS

**Six hops** — the longest in this project — on `SflSettlementClaim`, the long way round
through the counterparty's custodian:

    @Sfl_LinkClaim > @Sfl_LinkInstruction > @Sc_InstructionSsi > @Sc_SsiCustodian > @Sc_CustodianPlace > @Sc_PlaceCalendar | [core_calendar::Store]CC_CALENDAR.*

giving `claimCalendarId`, `claimSettlementCentre`, `claimWeekendDays`.

**Four hops the other way**, into the cash book, also on `SflSettlementClaim`:

    fundingPoolName: [settlement_flow::Store]@Sfl_LinkClaim > @Sfl_LinkMovement > @Csh_MovementProfile > @Csh_PoolMembers | [cash_core::Store]CSH_CASH_POOL.POOL_NAME

and **two hops** on `SflReconciliation` for `bookedValueDate` and `bookedAmount`
(`@Sfl_LinkReconciliation > @Sfl_LinkMovement | CSH_CASH_MOVEMENT.*`).

Only the first hop of any of these is declared here. Every later hop is a dependency's own
join, reused because its store is included — `Sc_*` from settlement-core, `Csh_*` from
cash-core, and `Cc_*`/`CC_CALENDAR` in scope because settlement-core's store includes
core-calendar's. Every hop is many-to-**one**; a to-many hop anywhere in a chain makes the
whole property a list. The store qualifier is required on the first hop and optional
afterwards; the **pipe** before the final column is not optional at all.

## Notes for downstream

- Do not `include core_calendar::Store` or `core_account::Store` alongside
  `settlement_flow::Store`. Both arrive transitively (calendar and party via
  settlement-core, account via cash-core) and including one twice re-declares its tables.
- Currency is a `String` code on every class here, deliberately: `cash_core` exposes
  `core_types::CtCurrency` on its own classes, and this project does not re-encode it.
- `SflCashSettlementLink.expectedAmount` is frozen at link time; the movement's own amount is
  read across the chain. The difference between them is `SflReconciliation.amountDifference`.
- Derived properties that divide or multiply are declared `Float[1]`, and
  `SflForecastLine.signedAmount()` multiplies by `-1.0` rather than `-1` — an Integer factor
  would type the product as `Number`, which is not a subtype of `Float`.
- No `###Data` element and no Runtime: the tables are declared and unseeded.

## Verified

    python3 scripts/projects/check.py settlement-flow
    compiles  settlement-flow (+6 deps)
