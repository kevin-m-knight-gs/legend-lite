# risk-dashboard

Layer 3. Depends on **margin-calc**, **credit-limits** and **pnl-attribution**, and on nothing
else. **The widest fan-in in the graph**: three layer-2 projects, fifteen projects in the
compile closure, and four of those arrive down two separate paths.

What a CRO actually looks at before nine o'clock — limit utilisation and its breaches, margin
calls outstanding, P&L against its explain, the largest movers, the concentrations, and the
exceptions that need a decision today. 29 classes over 22 tables, four store views and 41
joins.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

Prefixes: classes `Rdb*`, tables `RDB_*`, joins `Rdb_*`, filters `Rdb*`, set ids `rdb*`.

## The modelling claim

**A dashboard tile is a query with a threshold and an owner.**

A number on a screen that nobody owns and nothing compares against is decoration, so all three
are structure rather than convention:

| the tile has | held by | why it is not a column on the tile |
| --- | --- | --- |
| a QUERY | `RdbSourceBinding` (`tileId`, `sourceSeq`) | a tile may read two or three upstream projects, and one that reads three is what this project is *for* |
| a THRESHOLD | `RdbThresholdPolicy` (`policyCode`) | policies are shared, and `direction` (ABOVE/BELOW) is interpreted in exactly one place |
| an OWNER | `RdbOwner` (`ownerId`) | an exception assigned to a team is assigned to nobody |

`RdbTileSnapshot` is what a tile said on one morning — its own row per day, so the board can be
replayed. A red snapshot raises an `RdbException`, which carries a `dueBy`, an escalation chain
and a decision: the exceptions that need a decision today are the only part of a risk dashboard
that is not a report.

## THE FAN-IN, which is what this project is for

**At batch level.** `RdbMorningRun` names all three upstream batches on one row, and nothing
else in this graph does:

| column | join | into |
| --- | --- | --- |
| `COB_DATE` + `MARGIN_RUN_SEQ` | `Rdb_RunMarginRun` | `MGN_RUN` — margin-calc |
| `CREDIT_REPORT_ID` | `Rdb_RunCreditReport` | `CLM_DAILY_REPORT` — credit-limits |
| `ATTRIBUTION_ID` | `Rdb_RunAttributionRun` | `PNL_RUN` — pnl-attribution |

`allThreeSourcesResolved()` is the test. A board built from a complete margin run, yesterday's
credit report and a failed attribution looks fine, and that is the failure this row exists to
catch.

**At row level.** `RdbCounterpartyRollUp` carries the credit block, the margin block and the
P&L block for one name on one morning, with `creditLimits: ClmCreditLimit[*]` and
`marginAgreements: MgnMarginAgreement[*]` on the same class. No dependency could declare it:
margin-calc has never heard of a credit limit, credit-limits has never heard of a margin call,
and pnl-attribution has never heard of a counterparty. `netRiskAmount()` — drawn credit plus
the margin owed and not yet posted, less the collateral held — has two of its three terms from
projects that cannot see each other.

## The overlapping closures

Fifteen projects compile beneath this one, and the overlap is real:

| arrives twice | by | by |
| --- | --- | --- |
| position-keeping | `margin_calc::Store` | `pnl_attribution::Store` |
| core-account | (same two) | |
| core-instrument | (same two) | |
| core-price | (same two) | |
| core-party | inside credit-limits' own diamond, twice | |

Including a store does not copy its tables: `PK_*`, `CI_*`, `CA_*`, `CPR_*` and `CP_*` stay
owned by the projects that declare them, and the second and third arrivals are the same
database. **None of them is included here directly and no join names one** — every
cross-project join lands on a `CLM_*`, `MGN_* `or `PNL_*` table or one of their views. Same for
the mapping: no upstream set id outside the three dependencies is named.

## The two required constructs

**AGGREGATION + VIEW.** Four store `View`s, each with `~groupBy`, each mapped onto a class whose
`~primaryKey` IS its `~groupBy`:

| view | grouping | one row is |
| --- | --- | --- |
| RDB_TILE_DAY_TOTAL | dashboard, cob date | one board's whole morning |
| RDB_OWNER_QUEUE_TOTAL | cob date, **owner** | one person's queue |
| RDB_SEVERITY_TOTAL | cob date, **domain**, severity | the shape of the morning |
| RDB_COUNTERPARTY_LIMIT_TOTAL | cob date, **counterparty** | one name's limits, the limit dropped |

`RDB_SEVERITY_TOTAL` groups on three columns deliberately: a severity 4 in MARGIN and a
severity 4 in EXPLAIN are not the same morning, and grouping on severity alone would add them.
`RDB_TILE_DAY_TOTAL` carries `min(SOURCE_ROW_COUNT)` beside its sums, because a tile that is
green because it read nothing cannot be seen from any single tile.

These are Legend views, not database ones: no DDL, nothing seeds them, the engine folds the
`GROUP BY` into the SQL. **An owner with no exceptions on a date forms no group and is absent**
rather than showing a queue of zero — which here is the right answer.

Three more aggregations are **read, not declared**: `MGN_PORTFOLIO_MARGIN_TOTAL`,
`PNL_ACCOUNT_DAY_TOTAL` and `PNL_INSTRUMENT_DAY_TOTAL`. And one is read *two projects deep*:
`RdbConcentrationRow.shareOfFirmFactorDelta()` goes through margin-calc's add-on into
risk-core's own factor view, so the board measures a concentration against the firm's whole
delta without naming risk-core or summing a sensitivity.

**FILTER-SUBTYPES**, as a third shape: `RdbException` is the base over `RDB_EXCEPTION`, and the
three subtypes `extends [rdbException]` with a `~filter` on `DOMAIN_CODE` plus the two columns
that mean something for that domain.

## Elements

| element | kind | note |
| --- | --- | --- |
| `risk_dashboard::RdbDashboard` | class | the board; key `dashboardId`; derived `isLive()` |
| `risk_dashboard::RdbTile` | class | **THE TILE** = query + threshold + owner; key `tileId`; 2 constraints; derived `isLive()`, `tileLabel()` |
| `risk_dashboard::RdbThresholdPolicy` | class | the amber/red bands AND the direction; key `policyCode`; qualified `isRedAt(Float)`, `isAmberAt(Float)`; derived `bandWidth()` |
| `risk_dashboard::RdbOwner` | class | the named human; key `ownerId`; derived `hasEscalation()`, `ownerLabel()` |
| `risk_dashboard::RdbSourceBinding` | class | the tile's provenance — project, element, expression; key `(tileId, sourceSeq)`; derived `provenance()`, `readsAnAggregate()` |
| `risk_dashboard::RdbTileSnapshot` | class | what a tile said on one morning; key `(tileId, cobDate)`; derived `dayMove()`, `dayMovePct()`, `derivedStatus()`, `statusIsConsistent()`, `isStale()`, `isEmptyGreen()`, `averageExceptionSize()` |
| `risk_dashboard::RdbMorningRun` | class | **THE FAN-IN**; key `(cobDate, runSeq)`; reaches all three upstream runs; derived `allThreeSourcesResolved()`, `pnlSignedOff()`, `marginRunComplete()`, `redPct()`, `notGreenPct()`, `sourceLabel()`, `isComplete()` |
| `risk_dashboard::RdbLimitPanelRow` | class | panel 1; key `(cobDate, creditLimitId)`; **both milestoned routes** — `approvedThresholdOn(date)`, `ratingSymbolOn(date)`; derived `utilisationRatio()`, `headroomPct()`, `isOverLimit()`, `utilisationDrift()`, `shareOfCounterparty()` |
| `risk_dashboard::RdbLimitBreachRow` | class | panel 1; key `(cobDate, excessId)`; derived `needsDecisionToday()`, `unapprovedAmount()`, `excessRatio()`, `severityAgrees()`, `escalationOutstanding()`, `isOpen()`, `isCritical()` |
| `risk_dashboard::RdbMarginCallRow` | class | panel 2; key `(cobDate, agreementId)`; calls four margin-calc functions; derived `recomputedShortfall()`, `meetsMinimumTransfer()`, `uncollateralisedByMta()`, `coverageRatio()`, `isOutstanding()`, `isOverdue()`, `callDrift()`, `callTriggerLevel()` |
| `risk_dashboard::RdbMarginShortfallRow` | class | panel 2; key `(cobDate, marginPortfolioId, methodCode)`; derived `marginFromModel()`, `modelDrift()`, `horizonScalar()`, `addOnTotal()`, `addOnPct()`, `coverageRatio()`, `shareOfPortfolioMargin()`, `isSinglePositionDriven()` |
| `risk_dashboard::RdbExplainRow` | class | panel 3; key `(attributionId, institutionId, accountNo, cobDate)`; derived `residualFromRow()`, `explainQuality()`, `brokenPct()`, `unexplainedFromSource()`, `explainDrift()`, `returnPct()`, `runIsComplete()` |
| `risk_dashboard::RdbResidualRow` | class | panel 3; key = pnl-attribution's whole five-column line grain; derived `breachRatio()`, `isOpen()`, `isBlocking()`, `isUnowned()`, `residualDrift()`, `explainedAtSource()` |
| `risk_dashboard::RdbMoverRow` | class | panel 4, at SECURITY grain; key `(attributionId, instrumentId, cobDate)`; derived `absoluteMove()`, `moveFromEnds()`, `moveFromSource()`, `marketShare()`, `unexplainedOnSecurity()`, `isTopTen()`, `isSingleBook()` |
| `risk_dashboard::RdbConcentrationRow` | class | panel 5; key `(cobDate, marginPortfolioId, riskClass, factorId)`; derived `ratioOverThreshold()`, `concentrationFactor()`, `shareOfFirmFactorDelta()`, `ratioDrift()`; qualified `chargeOn(Float)` |
| `risk_dashboard::RdbCounterpartyRollUp` | class | **ALL THREE SOURCES, ONE ROW**; key `(cobDate, counterpartyId)`; 3 constraints; derived `headroom()`, `utilisationPct()`, `collateralCoverage()`, `netRiskAmount()`, `spansAllThreeSources()`, `utilisationFromGroup()`, `utilisationDrift()` |
| `risk_dashboard::RdbDeskRollUp` | class | the same morning by desk; key `(cobDate, institutionId, accountNo)`; derived `explainedPnl()`, `unexplainedPct()`, `returnOnMargin()`, `isNearLimit()`, `pnlFromSource()` |
| `risk_dashboard::RdbException` | class | **BASE of a filter-subtype family**; key `(cobDate, exceptionId)`; derived `isOpen()`, `isCritical()`, `overThreshold()`, `breachRatio()`, `isCarriedOver()`, `needsDecisionToday()`, `isBlocking()`, `shareOfOwnerQueue()` |
| `risk_dashboard::RdbLimitException` | class | subtype, `~filter RdbLimitExceptionRows`; adds `creditLimitId`, `excessId` |
| `risk_dashboard::RdbMarginException` | class | subtype, `~filter RdbMarginExceptionRows`; adds `agreementId`, `marginPortfolioId` |
| `risk_dashboard::RdbExplainException` | class | subtype, `~filter RdbExplainExceptionRows`; adds `attributionId`, `instrumentId` |
| `risk_dashboard::RdbEscalation` | class | one rung; key `(exceptionId, escalationSeq)`; derived `isOutstanding()`, `isAtCroLevel()` |
| `risk_dashboard::RdbDecision` | class | **the board's only output**; key `(exceptionId, decisionSeq)`; derived `isApproval()`, `isDeferral()`, `isTemporary()`, `approvedShare()` |
| `risk_dashboard::RdbCommentary` | class | the sentence somebody wrote; key `(tileId, cobDate, commentSeq)`; derived `attribution()`, `isDraft()` |
| `risk_dashboard::RdbWatchItem` | class | survives the morning it was raised on; key `watchId`; derived `isOpen()`, `camefromException()`; qualified `isOverdueForReview(StrictDate)` |
| `risk_dashboard::RdbTileDayTotal` | class | **AGGREGATE on a view**; derived `exceptionsPerTile()`, `averageAmountAtRisk()`, `hasRedTile()`, `hasEmptyTile()` |
| `risk_dashboard::RdbOwnerQueueTotal` | class | **AGGREGATE on a view**; derived `averageAmount()`, `queueConcentration()`, `queueBreachRatio()`, `isOverloaded()`, `hasBlockingItem()` |
| `risk_dashboard::RdbSeverityTotal` | class | **AGGREGATE on a view**, grouped three ways; derived `averageAmount()`, `amountSpread()`, `largestShare()`, `isBlockingBand()` |
| `risk_dashboard::RdbCounterpartyLimitTotal` | class | **AGGREGATE on a view**; derived `utilisationRatio()`, `averageLimitSize()`, `isSingleLimitDriven()`, `hasLimitNearTheLine()` |
| `risk_dashboard::Store` | store | `include`s all three dependency stores; 22 tables, 4 views, 41 joins, 6 filters |
| `risk_dashboard::Mapping` | mapping | `include`s all three dependency mappings; 29 sets, every `~primaryKey` explicit |

## Set ids (a GLOBAL namespace — reference these, do not guess)

`rdbDashboard`, `rdbTile`, `rdbThresholdPolicy`, `rdbOwner`, `rdbSourceBinding`,
`rdbTileSnapshot`, `rdbMorningRun`, `rdbLimitPanel`, `rdbLimitBreach`, `rdbMarginCall`,
`rdbMarginShortfall`, `rdbExplain`, `rdbResidual`, `rdbMover`, `rdbConcentration`,
`rdbCounterpartyRollUp`, `rdbDeskRollUp`, `rdbException`, `rdbLimitException`,
`rdbMarginException`, `rdbExplainException`, `rdbEscalation`, `rdbDecision`, `rdbCommentary`,
`rdbWatchItem`, `rdbTileDayTotal`, `rdbOwnerQueueTotal`, `rdbSeverityTotal`,
`rdbCounterpartyLimitTotal`.

Several are SHORTER than their class names — `rdbLimitPanel` for `RdbLimitPanelRow`,
`rdbExplain` for `RdbExplainRow`, `rdbMover` for `RdbMoverRow`, `rdbResidual` for
`RdbResidualRow`, `rdbConcentration` for `RdbConcentrationRow`, `rdbMarginCall` for
`RdbMarginCallRow`, `rdbLimitBreach` for `RdbLimitBreachRow`. Every id is explicit, so the
default ids (`risk_dashboard_RdbTile`) **do not exist**; none is marked root. A downstream
`extends [...]` or cross-project `AssociationMapping` must name an id from this list.

## Tables

| table | primary key |
| --- | --- |
| RDB_DASHBOARD | DASHBOARD_ID |
| RDB_TILE | TILE_ID |
| RDB_THRESHOLD_POLICY | POLICY_CODE |
| RDB_OWNER | OWNER_ID |
| RDB_SOURCE_BINDING | TILE_ID, SOURCE_SEQ |
| RDB_TILE_SNAPSHOT | TILE_ID, COB_DATE |
| RDB_MORNING_RUN | COB_DATE, RUN_SEQ |
| RDB_LIMIT_PANEL | COB_DATE, CREDIT_LIMIT_ID |
| RDB_LIMIT_BREACH | COB_DATE, EXCESS_ID |
| RDB_MARGIN_CALL_PANEL | COB_DATE, AGREEMENT_ID |
| RDB_MARGIN_SHORTFALL | COB_DATE, MARGIN_PORTFOLIO_ID, METHOD_CODE |
| RDB_EXPLAIN_PANEL | ATTRIBUTION_ID, INSTITUTION_ID, ACCOUNT_NO, COB_DATE |
| RDB_RESIDUAL_PANEL | ... + INSTRUMENT_ID (five) |
| RDB_MOVER | ATTRIBUTION_ID, INSTRUMENT_ID, COB_DATE |
| RDB_CONCENTRATION | COB_DATE, MARGIN_PORTFOLIO_ID, RISK_CLASS, FACTOR_ID |
| RDB_COUNTERPARTY_ROLLUP | COB_DATE, COUNTERPARTY_ID |
| RDB_DESK_ROLLUP | COB_DATE, INSTITUTION_ID, ACCOUNT_NO |
| RDB_EXCEPTION | COB_DATE, EXCEPTION_ID |
| RDB_ESCALATION | EXCEPTION_ID, ESCALATION_SEQ |
| RDB_DECISION | EXCEPTION_ID, DECISION_SEQ |
| RDB_COMMENTARY | TILE_ID, COB_DATE, COMMENT_SEQ |
| RDB_WATCH_ITEM | WATCH_ID |

### Cross-project joins

| join | shape |
| --- | --- |
| Rdb_RunMarginRun / Rdb_RunCreditReport / Rdb_RunAttributionRun | **THE FAN-IN** — one table, three projects |
| Rdb_LimitPanelUtilisation | 2 columns into `CLM_UTILISATION_LINE` on (limit, date) |
| Rdb_LimitPanelCreditLimit | into `CLM_CREDIT_LIMIT` — the anchor both milestoned routes hang off |
| Rdb_BreachExcess / Rdb_BreachTicket | `CLM_EXCESS`, `CLM_ESCALATION_TICKET` |
| Rdb_RollUpCreditLimits | counterparty → every `CLM_CREDIT_LIMIT` of that name |
| Rdb_MarginCallPanelCall | 2 columns into `MGN_MARGIN_CALL` |
| Rdb_MarginCallPanelAgreement | `MGN_AGREEMENT` |
| Rdb_ShortfallInitialMargin | 3 columns into `MGN_INITIAL_MARGIN` |
| Rdb_ShortfallPortfolioTotal | **into somebody else's VIEW** — `MGN_PORTFOLIO_MARGIN_TOTAL` |
| Rdb_ConcentrationAddOn | 4 columns into `MGN_CONCENTRATION_ADDON` |
| Rdb_RollUpMarginAgreements | counterparty → every `MGN_AGREEMENT` of that name |
| Rdb_ExplainAccountTotal / Rdb_DeskAccountTotal | **into a VIEW** — 4 columns into `PNL_ACCOUNT_DAY_TOTAL` |
| Rdb_MoverInstrumentTotal | **into a VIEW** — 3 columns into `PNL_INSTRUMENT_DAY_TOTAL` |
| Rdb_ResidualSource | 5 columns into `PNL_RESIDUAL` |
| Rdb_ResidualBreak | 6 columns into `PNL_BREAK` |
| Rdb_ExplainAttributionRun | `PNL_RUN` |

Upstream set ids named: `mgnMarginRun`, `mgnMarginCall`, `mgnAgreement`, `mgnInitialMargin`,
`mgnConcentrationAddOn`, `mgnPortfolioMarginTotal`, `clmDailyReport`, `clmCreditLimit`,
`clmUtilisationLine`, `clmExcess`, `clmEscalationTicket`, `pnlRun`, `pnlResidual`, `pnlBreak`,
`pnlAccountDayTotal`, `pnlInstrumentDayTotal`. Note `clmDailyReport` (class
`ClmDailyLimitReport`) and `pnlBreak` (class `PnlAttributionBreak`) are shorter than their
classes — read, not guessed.

### Filters

`RdbLimitExceptionRows`, `RdbMarginExceptionRows`, `RdbExplainExceptionRows` (each
`RDB_EXCEPTION.DOMAIN_CODE = '…'`), plus `RdbOpenExceptions` (`DECIDED_ON is null`),
`RdbLiveTiles` (`RDB_TILE.RETIRED_ON is null`) and `RdbOpenWatchItems` (`CLOSED_ON is null`).
String comparisons and null tests, because a `Filter` will not take a boolean literal.

## Functions called across the boundary

| call site | function |
| --- | --- |
| `RdbMarginCallRow.recomputedShortfall()` | `margin_calc::mgnCallAmount` |
| `RdbMarginCallRow.meetsMinimumTransfer()`, `.uncollateralisedByMta()` | `margin_calc::mgnMeetsMta` |
| `RdbMarginShortfallRow.marginFromModel()`, `.modelDrift()` | `margin_calc::mgnTotalInitialMargin` |
| `RdbMarginShortfallRow.horizonScalar()` | `margin_calc::mgnCloseOutScalar` |
| `RdbConcentrationRow.concentrationFactor()` | `margin_calc::mgnConcentrationFactor` |
| `RdbConcentrationRow.chargeOn(base)` | `margin_calc::mgnAddOn` |

Six of margin-calc's twelve. The board never re-derives a shortfall, an MTA test, a
close-out scaling or a concentration multiplier: they are margin-calc's definitions, called,
so the dashboard and the margin system cannot disagree about what a call is.

## Both milestoned routes, used

`RdbLimitPanelRow` exposes both of credit-limits' dated routes, each wrapped once more:

    risk_dashboard::RdbLimitPanelRow.all()->map(r | $r.approvedThresholdOn(%2024-03-31))
    risk_dashboard::RdbLimitPanelRow.all()->map(r | $r.ratingSymbolOn(%2024-03-31))

The first goes to credit-limits' own `<<temporal.businesstemporal>>` `ClmLimitStanding`; the
second on through credit-core to core-ratings' milestoned rating version. Both return `[*]` —
`Float[*]` and `String[*]` — because a business date can select a span that is not there.
Neither temporal class is named in this project, and core-ratings could not be: it is
transitive, not declared.

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(24,8) | every money column — utilisation, thresholds, headroom, call and margin amounts, collateral, residuals and tolerances, P&L, movers, add-ons, exception amounts, and every view total |
| FLOAT | UTILISATION_PCT, BREACH_RATIO, AMBER_LEVEL, RED_LEVEL, MOVE_PCT, RESIDUAL_PCT, TOLERANCE_PCT, UNEXPLAINED_PCT, CONCENTRATION_RATIO, WORST_UTILISATION_PCT |
| SMALLINT | every count and severity bounded by construction; INTEGER for RUN_SEQ and SOURCE_ROW_COUNT |
| CHAR(n) | currencies CHAR(3), DIRECTION CHAR(5), STATUS_CODE CHAR(4)/CHAR(5), DECISION_CODE / SEVERITY_CODE CHAR(4) |

Money is NUMERIC(24,8) — the **widest** of the three dependencies' scales (credit-limits uses
20,4 and pnl-attribution 20,6), because a board that displays all three must not be where a
figure loses digits. `REAL` is not used: it parses and cannot be read back at execution (F53).

## Notes for downstream

- Every panel row FREEZES what the board published and also reaches the live source, and the
  `...Drift()` derived properties are the difference. Neither number is the right one to trust
  on its own: the frozen one is what was decided against, the live one is what is true now, and
  the gap is the fact.
- `RdbTileSnapshot.isEmptyGreen()` and `RdbTileDayTotal.hasEmptyTile()` exist because a tile
  computed from zero rows is green. That is the most dangerous state on a risk dashboard and it
  is invisible from the tile's own colour.
- `MgnMarginCall.grossCallAmount` and `callAmount` are kept apart here exactly as margin-calc
  keeps them apart; `uncollateralisedByMta()` is on the board on purpose.
- Every derived property that divides is declared `Float[1]` — `/` widens regardless of its
  operands. Navigating a `[0..1]` into an aggregate and then dividing uses `->toOne()`, and
  calling a qualified property through one uses `->toOne().foo()`.
- Constraints are declared, not enforced by the store. `RdbLimitPanelRow.headroomAmount` and
  `RdbCounterpartyRollUp`'s headroom carry no non-negative bound: negative headroom is exactly
  what a breach looks like.
- No `###Data`, no Runtime, no seeded rows.

## Verified

    python3 scripts/projects/check.py risk-dashboard
    compiles  risk-dashboard (+15 deps)
