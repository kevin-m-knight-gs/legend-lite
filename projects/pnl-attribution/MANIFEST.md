# pnl-attribution

Layer 2. Depends on **position-keeping** and **valuation-core**, and on nothing else.
Why the book is worth a different number today than it was yesterday, split into causes —
and by how much the causes fail to add up, which is the number risk management reads.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

Prefixes: classes `Pnl*`, tables `PNL_*`, joins `Pnl_*`, filters `Pnl*`, set ids `pnl*`.

## THE DIAMOND, which is what this project is for

Both dependencies know about the same security and reached it by different routes:

| dependency | route to the instrument |
| --- | --- |
| position-keeping | **resolved** — `Position.instrument` is a real `core_instrument::Instrument`, on a one-column join to `CI_INSTRUMENT` |
| valuation-core | **by identifier only** — via core-price and core-fx, both of which key on `INSTRUMENT_ID` and never join to the instrument master |

The two upstream stores have no join between them and share only the string in
`INSTRUMENT_ID`. `PNL_LINE` is where that string becomes one security again: one table
carrying **both** upstream keys, joined to each of them **twice** — once for today and once
for the prior day, because a P&L is a difference and both ends must be reachable.

| join | into | columns |
| --- | --- | --- |
| `Pnl_LinePosition` | `PK_POSITION` | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE |
| `Pnl_LinePriorPosition` | `PK_POSITION` | the same four, but `PRIOR_COB_DATE` = `COB_DATE` |
| `Pnl_LineValuation` | `VC_VALUATION` | VALUATION_RUN_ID, INSTRUMENT_ID, COB_DATE |
| `Pnl_LineOpeningValuation` | `VC_VALUATION` | PRIOR_VALUATION_RUN_ID, INSTRUMENT_ID, PRIOR_COB_DATE |

The diamond is restated twice more: at **batch** level (`PnlAttributionRun` names both the
valuation run and the position run) and at **group** level (`PnlAccountDayTotal` and
`PnlInstrumentDayTotal` join view-to-view into position-keeping's own `~groupBy` views, so a
P&L group and a position group meet without either side reading a row).

Nothing in this project refers to core-instrument, core-account, core-price or core-fx. They
are in the compile closure transitively and every fact needed from them is reached **through**
one of the two declared dependencies.

## The explain, in one paragraph

A day's P&L on a position is one number — the change in market value — and nobody can act on
one number. Six causes split it: **PRICE** (the instrument moved, at yesterday's quantity and
rate), **FX** (the rate moved, at yesterday's price and quantity), **CARRY** (time passed:
coupon, dividend, financing), **FEE** (money that left whatever the market did), **TRADE**
(positions opened today, marked from execution to close — deliberately not a price effect),
**AMEND** (restatements of a prior day, because a closed day cannot be reopened). Those six
added to the opening value should reproduce the closing value; the gap is the **residual**,
and it is derived rather than stored on the line, because a stored total is one more thing
that can disagree with its parts and that disagreement is the whole subject. Two further
splits cut across all six: **realised vs unrealised** and **clean vs dirty**.

## Elements

| element | kind | note |
| --- | --- | --- |
| `pnl_attribution::PnlAttributionRun` | class | one attribution batch; key `attributionId`; names BOTH upstream runs; 2 constraints; derived `residualPct()`, `brokenPct()`, `isComplete()`, `runLabel()` |
| `pnl_attribution::PnlLine` | class | **THE ROOT, THE GRAIN AND THE DIAMOND**; key `(attributionId, institutionId, accountNo, instrumentId, cobDate)`; six effects, both splits, both market values; reaches `Position`/`VcValuation` for today AND the prior day; derived `marketValueChange()`, `explainedPnl()`, `unexplainedPnl()`, `marketEffect()`, `activityEffect()`, `totalPnl()`, `dirtyPnl()`, `returnPct()`, `quantityChange()`, `pnlInReporting()`, `lineKey()` |
| `pnl_attribution::PnlValueBridge` | class | the reconciliation: both values, both rates, both quantities, in both currencies; derived `movePct()`/`ratePct()` call `valuation_core::vcDeviationPct` |
| `pnl_attribution::PnlResidual` | class | **THE UNEXPLAINED RESIDUAL**, stored, held to a tolerance, owned and closed with a reason; derived `isExplained()`, `breachRatio()` |
| `pnl_attribution::PnlExplainStep` | class | one rung of the opening→closing waterfall; key + `stepSeq`; derived `balanceCheck()`, `stepPct()` |
| `pnl_attribution::PnlRealisedSplit` | class | realised vs unrealised, with the lot-matching method; derived `totalPnl()`, `unrealisedChange()`, `realisedShare()`, `realisedFromEvents()` |
| `pnl_attribution::PnlCleanDirtySplit` | class | clean vs dirty, with accrued at both ends and any coupon paid; derived `dirtyCheck()`, `accruedChange()`, `accruedChangeExCoupon()`, `accruedShare()` |
| `pnl_attribution::PnlComponent` | class | BASE of a filter-subtype family; one attributed amount of one cause; key + `componentCode`; derived `reportingCheck()`, `absoluteAmount()` |
| `pnl_attribution::PnlPriceEffect` | class | subtype, `~filter PnlPriceRows`; adds `openPrice`, `closePrice`; `effectCheck()` calls `valuation_core::vcMarketValue`, `movePct()` calls `vcDeviationPct` |
| `pnl_attribution::PnlFxEffect` | class | subtype, `~filter PnlFxRows`; adds `openFxRate`, `closeFxRate`; `rateMovePct()` calls `vcDeviationPct` |
| `pnl_attribution::PnlCarryEffect` | class | subtype, `~filter PnlCarryRows`; adds `carryType`, `dayCountFraction` |
| `pnl_attribution::PnlFeeEffect` | class | subtype, `~filter PnlFeeRows`; adds `feeType`, `feeRateBps` |
| `pnl_attribution::PnlNewTradeEffect` | class | subtype, `~filter PnlNewTradeRows`; adds `tradeCount`, `tradedQuantity` |
| `pnl_attribution::PnlAmendmentEffect` | class | subtype, `~filter PnlAmendmentRows`; adds `amendmentCount`, `restatedFrom` |
| `pnl_attribution::PnlCarryAccrual` | class | one strand of carry; key + `carryType`; reaches `valuation_core::VcAccrual` |
| `pnl_attribution::PnlFeeCharge` | class | one fee actually charged; key + `feeSeq` |
| `pnl_attribution::PnlTradePnl` | class | one trade's day-one P&L; key + `tradeReference`; reaches `position_keeping::PositionMovement`; `notional()` calls `valuation_core::vcMarketValue` |
| `pnl_attribution::PnlAmendment` | class | a restatement of a prior day, before AND after; key + `amendmentSeq` |
| `pnl_attribution::PnlAttributionBreak` | class | a breached residual with an owner; key + `breakCode`; derived `isOpen()`, `isBlocking()` |
| `pnl_attribution::PnlTolerancePolicy` | class | how big a residual may be, as a pct AND an absolute floor; key `policyCode` |
| `pnl_attribution::PnlAttributionMethod` | class | which algorithm split the day, and what it does with the cross term; key `methodCode` |
| `pnl_attribution::PnlSignOff` | class | one role's acceptance of a run; key `(attributionId, signOffSeq)` |
| `pnl_attribution::PnlAccountDayTotal` | class | **AGGREGATE on a view**: one book's day; reaches `position_keeping::AccountDayTotal` |
| `pnl_attribution::PnlInstrumentDayTotal` | class | **AGGREGATE on a view**: one security across every account; reaches `position_keeping::InstrumentDayTotal` |
| `pnl_attribution::PnlCurrencyTotal` | class | **AGGREGATE on a view**: one currency per run; reaches `valuation_core::VcReportingCurrency` |
| `pnl_attribution::PnlRunTotal` | class | **AGGREGATE on a view**: the whole run as one row — the firm-level explain |
| `pnl_attribution::PnlComponentTotal` | class | **AGGREGATE on a view**: one cause totalled over a run — the totals table of the explain itself |
| `pnl_attribution::Store` | store | `include position_keeping::Store`, `include valuation_core::Store`; 16 tables, 5 views, 39 joins, 8 filters |
| `pnl_attribution::Mapping` | mapping | `include position_keeping::Mapping`, `include valuation_core::Mapping`; 27 sets |

27 classes. Every aggregate class carries `totalUnexplained()`, so the residual is readable at
book level, security level, currency level and for the whole run without the reader summing
anything.

## Set ids (a GLOBAL namespace — reference these, do not guess)

`pnlRun`, `pnlLine`, `pnlValueBridge`, `pnlResidual`, `pnlExplainStep`, `pnlRealisedSplit`,
`pnlCleanDirtySplit`, `pnlComponent`, `pnlPriceEffect`, `pnlFxEffect`, `pnlCarryEffect`,
`pnlFeeEffect`, `pnlNewTradeEffect`, `pnlAmendmentEffect`, `pnlCarryAccrual`, `pnlFeeCharge`,
`pnlTradePnl`, `pnlAmendment`, `pnlBreak`, `pnlTolerancePolicy`, `pnlMethod`, `pnlSignOff`,
`pnlAccountDayTotal`, `pnlInstrumentDayTotal`, `pnlCurrencyTotal`, `pnlRunTotal`,
`pnlComponentTotal`.

Every id is named EXPLICITLY, so the default ids (`pnl_attribution_PnlLine` and the rest) **do
not exist**. None is marked root. A downstream `extends [...]` or cross-project
`AssociationMapping` must name the id from this table.

## Store detail

| table | primary key |
| --- | --- |
| PNL_RUN | ATTRIBUTION_ID |
| PNL_LINE | ATTRIBUTION_ID, INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE |
| PNL_VALUE_BRIDGE | the same five |
| PNL_RESIDUAL | the same five |
| PNL_REALISED_SPLIT | the same five |
| PNL_CLEAN_DIRTY | the same five |
| PNL_COMPONENT | ... + COMPONENT_CODE |
| PNL_EXPLAIN_STEP | ... + STEP_SEQ |
| PNL_CARRY_ACCRUAL | ... + CARRY_TYPE |
| PNL_FEE_CHARGE | ... + FEE_SEQ |
| PNL_TRADE_PNL | ... + TRADE_REFERENCE |
| PNL_AMENDMENT | ... + AMENDMENT_SEQ |
| PNL_BREAK | ... + BREAK_CODE |
| PNL_TOLERANCE_POLICY | POLICY_CODE |
| PNL_METHOD | METHOD_CODE |
| PNL_SIGN_OFF | ATTRIBUTION_ID, SIGN_OFF_SEQ |

`INSTRUMENT_ID` is `VARCHAR(60)` throughout — position-keeping's width, not valuation-core's
`VARCHAR(20)` — because the wider of the two is the only one that cannot truncate.

### Views (the AGGREGATION shape — `~groupBy` IS the primary key)

| view | grouping | one row is |
| --- | --- | --- |
| PNL_ACCOUNT_DAY_TOTAL | attribution, institution, account, cob date | one book's whole day of P&L |
| PNL_INSTRUMENT_DAY_TOTAL | attribution, instrument, cob date | one security across every account |
| PNL_CURRENCY_TOTAL | attribution, pnl currency | the treasury cut — drops account, instrument AND date |
| PNL_RUN_TOTAL | attribution | the firm's whole day, one row |
| PNL_COMPONENT_TOTAL | attribution, component code | one CAUSE totalled — grouped from PNL_COMPONENT, not PNL_LINE |

Four group the line table at four widths; the fifth groups the component table by cause and
carries `max`/`min` beside the `sum`, because a total of zero built from two enormous opposite
numbers is not a total of zero in any useful sense. These are Legend views, not database
views: no DDL, nothing seeds them, the engine folds the `GROUP BY` into its SQL. An account
with no P&L lines on a date forms **no group** and is absent rather than showing zero.

### Cross-project joins

| join | shape |
| --- | --- |
| Pnl_LinePosition / Pnl_LinePriorPosition | 4 columns into PK_POSITION, today and yesterday |
| Pnl_LineValuation / Pnl_LineOpeningValuation | 3 columns into VC_VALUATION, today and yesterday |
| Pnl_LineMovementTotal | 4 columns into position-keeping's `PK_MOVEMENT_TOTAL` **view** |
| Pnl_LineLifetime | 3 columns into `PK_POSITION_LIFETIME` |
| Pnl_LineAccrual / Pnl_CarryAccrualSource | instrument + date into `VC_ACCRUAL` |
| Pnl_TradePnlMovement | 5 columns into `PK_POSITION_MOVEMENT` |
| Pnl_RunValuationRun | `PNL_RUN.VALUATION_RUN_ID` = `VC_VALUATION_RUN.RUN_ID` |
| Pnl_RunPositionRun | COB_DATE + POSITION_RUN_SEQ into `PK_RUN` |
| Pnl_AccountDayTotalPositionTotal | **view to view** — 3 columns into `PK_ACCOUNT_DAY_TOTAL` |
| Pnl_InstrumentDayTotalPositionTotal | **view to view** — 2 columns into `PK_INSTRUMENT_DAY_TOTAL` |
| Pnl_CurrencyTotalReportingCurrency | from a view — PNL_CURRENCY into `VC_REPORTING_CURRENCY` |

Upstream set ids named: `pkPosition`, `pkPositionMovement`, `pkPositionRun`,
`pkMovementTotal`, `pkAccountInstrumentLifetime`, `pkAccountDayTotal`, `pkInstrumentDayTotal`,
`vcValuation`, `vcAccrual`, `vcRun`, `vcReportingCurrency`.

### Filters

Six back the filter-subtype family — `PnlPriceRows`, `PnlFxRows`, `PnlCarryRows`,
`PnlFeeRows`, `PnlNewTradeRows`, `PnlAmendmentRows`, each `PNL_COMPONENT.COMPONENT_CODE = '…'`
— plus `PnlUnexplainedResiduals` (`EXPLAINED_ON is null`) and `PnlOpenBreaks`
(`RESOLVED_ON is null`). String comparisons and null tests, because a `Filter` will not take a
boolean literal.

## Functions called across the boundary

| call site | function |
| --- | --- |
| `PnlValueBridge.movePct()`, `.ratePct()` | `valuation_core::vcDeviationPct` |
| `PnlPriceEffect.movePct()`, `PnlFxEffect.rateMovePct()` | `valuation_core::vcDeviationPct` |
| `PnlPriceEffect.effectCheck()`, `PnlTradePnl.notional()` | `valuation_core::vcMarketValue` |

Deviation and market value are never re-derived here: they are valuation-core's definitions,
called, so this project measures a move exactly as every valuation break in the firm does.

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(20,6) | every money column — market values, the six effects, realised/unrealised, clean/accrued/dirty, residual and tolerance amounts, fees, trade cash, day-one P&L, amendment before/after, break amounts, and every view total |
| FLOAT | FX_RATE, OPEN_FX_RATE, CLOSE_FX_RATE, OPENING_FX_RATE, CLOSING_FX_RATE, ATTRIBUTION_QUALITY, RESIDUAL_PCT, TOLERANCE_PCT, CONTRIBUTION_PCT, BREAK_PCT, FEE_RATE_BPS, CARRY_RATE, DAY_COUNT_FRACTION, RESIDUAL_TOLERANCE_PCT, EXPECTED_RESIDUAL_PCT |
| SMALLINT | LINE_COUNT, BROKEN_LINE_COUNT, COMPUTED_SEQ, STEP_SEQ, FEE_SEQ, AMENDMENT_SEQ, SIGN_OFF_SEQ, SEVERITY, TRADE_COUNT, AMENDMENT_COUNT, ESCALATION_LEVEL |
| CHAR(n) | PNL_CURRENCY / REPORTING_CCY / COMPONENT_CCY / FEE_CCY / TRADE_CCY / ACCRUAL_CCY / VALUE_CURRENCY CHAR(3), DAY_COUNT_BASIS CHAR(7) |

Money is NUMERIC because a P&L explain must reconcile to the last unit; rates, ratios and
tolerances are FLOAT because they are compared against a threshold rather than for equality.
`REAL` is not used: it parses and cannot be read back at execution (F53).

## Notes for downstream

- `PnlLine` has one class-level constraint (`closingIsAfterOpening`) and `PnlAttributionRun`
  has two (`priorDateIsEarlier`, `lineCountIsPositive`).
- The residual exists in two forms and they must agree: `PnlLine.unexplainedPnl()` is what the
  arithmetic says; `PnlResidual.residualAmount` is what the run wrote. A downstream control
  report should compare them rather than pick one.
- Every derived property that divides is declared `Float[1]` — `/` widens to Float regardless
  of its operands.
- Only `position-keeping` and `valuation-core` are referred to. Their transitive dependencies
  are reachable through them and are never named here.

## Verified

    python3 scripts/projects/check.py pnl-attribution
    compiles  pnl-attribution (+6 deps)
