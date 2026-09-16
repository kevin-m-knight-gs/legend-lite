# firm-balance-sheet

Layer 3 — the DEEPEST project in the graph. Depends on **pnl-attribution**, **exposure-agg**
and **liquidity-view**, and on nothing else. Package root `firm_balance_sheet::`, prefixes
`FBS_` (tables and views), `Fbs_` (joins), `Fbs` (filters), `Fbs` (class names), `fbs` (set
ids **and the schema**).

The balance sheet, five levels above core-types: assets and liabilities by measurement basis,
the trading book against the banking book, derivatives before netting, HQLA by level, goodwill
and the deductions that never make it into capital, provisions, and the equity that balances it.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## The two required constructs

**A SCHEMA of its own.** Every table and view this project owns lives in `fbs`. A published
balance sheet is not a trading table: it is produced once per period, signed, retained for as
long as the companies act says, and restored as a unit when an auditor asks about a number from
three years ago. Its own schema lets a DBA grant, back up and retain it separately from the
seventeen projects underneath it, and makes `fbs.FBS_LINE_ITEM` unambiguous in a database that
also holds `PNL_LINE`, `EXA_NET_EXPOSURE` and `LQV_FLOW`.

It costs something, and downstream projects must know the cost: **every reference to one of
these tables is four segments deep**, `[firm_balance_sheet::Store]fbs.FBS_LINE_ITEM.COLUMN`.
Dropping the `fbs.` produces an error naming the COLUMN and not the missing schema. The three
dependencies put their tables in the DEFAULT schema, so a reference to one of theirs has no
schema segment at all.

**AN AGGREGATION shape.** Four `~groupBy` views over `fbs.FBS_LINE_ITEM`, at four widths. See
the table below. `~groupBy` takes COLUMN references and nothing else, which is why
`FBS_LINE_ITEM` carries `SIDE`, `CATEGORY_CODE`, `BASIS_CODE` and `BOOK_CODE` as stamped
columns rather than anything derived.

## What the depth buys, and what it costs

Seventeen projects are in the compile closure and several arrive twice — core-account through
both position-keeping and cash-core, core-types through both valuation-core and cash-core,
core-party through credit-core and collateral-core. **Not one of them is named anywhere in this
project.** Every fact from below layer 2 is reached THROUGH a declared dependency:

| wanted | reached as | never written |
| --- | --- | --- |
| the instrument behind a trading line | `$position.pnlLine.position.instrument` | `core_instrument::` |
| the counterparty behind a derivative | `$derivativeAsset.netExposure.counterparty` | `core_party::` |
| the account behind an HQLA holding | `$assetLine.hqlaHolding.accountNo` | `core_account::` |
| a currency | a three-character `String` | `core_types::CtCurrency` |

`Fbs_TradingPositionPnlLine` is the deepest single reference in the graph: five columns into
`PNL_LINE`, which is itself the apex of pnl-attribution's diamond over position-keeping and
valuation-core, each of which sits on core-instrument / core-account / core-price / core-fx,
each of which sits on core-types. **Five hops from a leaf.**

## Elements

| element | kind | note |
| --- | --- | --- |
| `firm_balance_sheet::FbsBalanceSheet` | class | the statement header; key `(institutionId, asOfDate, reportingCurrency)`; 1 constraint; reaches `pnl_attribution::PnlRunTotal` and `liquidity_view::LqvNsfrResult`; derived `balanceDifference()`, `isBalanced()`, `equityRatio()`, `leverageMultiple()`, `isPublished()`, `statementKey()` |
| `firm_balance_sheet::FbsBalanceSheetRun` | class | the batch; names BOTH upstream runs (`pnl_attribution::PnlAttributionRun`, `exposure_agg::ExaExposureRun`); derived `isComplete()`, `rejectedPct()` |
| `firm_balance_sheet::FbsLineItem` | class | **THE ROOT AND THE GRAIN**, and the table all four views group; key `lineId`; derived `netAmount()`, `movement()`, `movementPct()`, `impairmentCheck()`, `coverageRatio()`, `isAsset()`, `isFairValued()`, `isTradingBook()`, `reportingFromLocal()`, `lineKey()` |
| `firm_balance_sheet::FbsAssetLine` | class | subtype, `~filter FbsAssetRows`; adds `isHqla`, `hqlaLevelCode`, `encumberedAmount`, `liquidityDays`; reaches `liquidity_view::LqvHqlaHolding`; derived `unencumberedAmount()`, `encumbranceRatio()`, `isLiquidWithinMonth()` |
| `firm_balance_sheet::FbsLiabilityLine` | class | subtype, `~filter FbsLiabilityRows`; adds `maturityDate`, `isSecured`, `fundingType`, `counterpartyId`, `isDemandDeposit`; derived `isPerpetual()`, `isCallableFunding()` |
| `firm_balance_sheet::FbsEquityLine` | class | subtype, `~filter FbsEquityRows`; adds `equityComponentCode`, `isCet1Eligible`, `nonControllingInterest`; derived `attributableToParent()`, `countsAsCet1()` |
| `firm_balance_sheet::FbsCategory` | class | the statement taxonomy; key `categoryCode`; `parentCode` is a plain String — there is deliberately no self-join |
| `firm_balance_sheet::FbsMeasurementBasis` | class | AC / FVOCI / FVTPL; `movementsThrough` is 'PL', 'OCI' or 'NONE'; `recyclesToPl` distinguishes an FVOCI debt instrument from an FVOCI equity one |
| `firm_balance_sheet::FbsBookClassification` | class | trading book vs banking book; `boundaryPolicyRef` names the policy a migration will be asked about |
| `firm_balance_sheet::FbsFairValueLevel` | class | Level 1/2/3; derived `isModelled()` |
| `firm_balance_sheet::FbsTradingBookPosition` | class | **THE DEEPEST JOIN**: five columns into `pnl_attribution::PnlLine`; derived `totalReserve()`, `prudentValue()`, `reservePct()`, `isShort()` |
| `firm_balance_sheet::FbsBankingBookAsset` | class | amortised-cost detail with the IFRS 9 stage; reaches `exposure_agg::ExaNetExposure`; derived `netCarryingAmount()`, `amortisedCostWithAccrual()`, `coverageRatio()`, `isCreditImpaired()`, `isLifetimeEcl()` |
| `firm_balance_sheet::FbsDerivativeAsset` | class | gross POSITIVE value before netting; reaches `ExaNettingSet`, `ExaNetExposure` and the `ExaCounterpartyDayNet` **view**; derived `carryingValue()`, `totalValuationAdjustment()`, `cvaPct()`, `isBilateral()` |
| `firm_balance_sheet::FbsDerivativeLiability` | class | gross NEGATIVE value before netting; derived `carryingValue()`, `dvaPct()`, `isBilateral()` |
| `firm_balance_sheet::FbsNettingAdjustment` | class | what actually nets; **composite key** `(nettingSetId, asOfDate)`; `offsetBasis` is 'IAS32' or 'ASC815'; derived `balanceSheetReduction()`, `assetAfterOffset()`, `liabilityAfterOffset()`, `offsetPct()`, `netAssetCheck()`, `isNettedOnBalanceSheet()` |
| `firm_balance_sheet::FbsCollateralOffset` | class | collateral taken and how much of it may be offset; reaches `exposure_agg::ExaCollateralPosition`; derived `totalCollateral()`, `ineligibleAmount()`, `offsetEfficiency()`, `isRehypothecated()` |
| `firm_balance_sheet::FbsHqlaStock` | class | the bridge to the liquidity buffer; **composite key** of four; reaches `liquidity_view::LqvHqlaLevel` and `LqvLcrResult`; derived `recognisedPct()`, `haircutPct()`, `cappedAway()`, `isLevelOne()`, `averageHoldingSize()` |
| `firm_balance_sheet::FbsGoodwill` | class | goodwill per cash-generating unit; derived `headroom()`, `isImpaired()`, `impairmentToDatePct()`, `isTested()` |
| `firm_balance_sheet::FbsIntangibleAsset` | class | software, licences, customer lists; derived `netBookValue()`, `amortisedPct()`, `annualCharge()`, `isFullyAmortised()` |
| `firm_balance_sheet::FbsCapitalDeduction` | class | **what never makes it into capital**; both `deductedAmount` and `riskWeightedRemainder`, because a threshold deduction takes some and risk-weights the rest; derived `retainedOnBalanceSheet()`, `deductionPct()`, `excessOverThreshold()`, `isThresholdDeduction()`, `hitsCommonEquity()` |
| `firm_balance_sheet::FbsProvision` | class | the five-column roll-forward; derived `rollForward()`, `rollForwardCheck()`, `netCharge()`, `utilisationPct()`, `isReleasing()` |
| `firm_balance_sheet::FbsEclAllowance` | class | ECL by stage, with the post-model overlay held apart from the modelled number; reaches `exposure_agg::ExaNetExposure`; derived `coverageRatio()`, `modelledAmount()`, `overlayPct()`, `isLifetime()`, `isCreditImpaired()`, `stageMigrationCost()` |
| `firm_balance_sheet::FbsEquityComponent` | class | the equity taxonomy; `isDistributable` and `capitalTier` are different questions with different answers |
| `firm_balance_sheet::FbsEquityBalance` | class | one component on one statement; **composite key** of four; derived `movementCheck()`, `ownershipSplitCheck()`, `minorityPct()`, `movementPct()` |
| `firm_balance_sheet::FbsOciReserve` | class | where FVOCI movements wait; derived `taxCheck()`, `effectiveTaxPct()`, `isLoss()`, `isTrapped()` |
| `firm_balance_sheet::FbsRetainedEarnings` | class | the one equity line the income statement writes into; reaches `pnl_attribution::PnlRunTotal`; derived `rollForward()`, `rollForwardCheck()`, `payoutRatio()`, `isLossMaking()`, `growthPct()` |
| `firm_balance_sheet::FbsAccountingAdjustment` | class | the manual journal; derived `isApproved()`, `isLive()`, `isUnapprovedAndLive()`, `absoluteAmount()` |
| `firm_balance_sheet::FbsBalanceCheck` | class | a stated equality that did not hold; generic `(checkType, leftAmount, rightAmount)`; derived `computedDifference()`, `differenceCheck()`, `breachRatio()`, `isWithinTolerance()`, `isOpen()`, `isBlocking()` |
| `firm_balance_sheet::FbsAttestation` | class | one role's acceptance; **composite key** `(institutionId, asOfDate, roleCode)`; derived `isCurrent()`, `isAccepted()` |
| `firm_balance_sheet::FbsCategoryTotal` | class | **AGGREGATION on a view**: the statement as printed; derived `movement()`, `movementPct()`, `averageLine()`, `coverageRatio()`, `spread()`, `concentrationPct()` |
| `firm_balance_sheet::FbsBasisTotal` | class | **AGGREGATION on a view**: the IFRS 9 disclosure, per basis per side; derived `averageLine()`, `netOfImpairment()`, `isFairValueBasis()`, `concentrationPct()` |
| `firm_balance_sheet::FbsBookTotal` | class | **AGGREGATION on a view**: the book boundary in money; derived `movement()`, `averageLine()`, `isTradingBook()`, `dayMovePct()` |
| `firm_balance_sheet::FbsSideTotal` | class | **AGGREGATION on a view**: **THE BALANCE CHECK ITSELF** — three rows per statement, straight off the line table; derived `movement()`, `averageLine()`, `netOfImpairment()`, `isAssetSide()` |
| `firm_balance_sheet::Store` | store | `include`s all three dependency stores; **26 tables and 4 views in the `fbs` schema**, 60 joins, 9 filters |
| `firm_balance_sheet::Mapping` | mapping | `include`s all three dependency mappings; 33 sets, 3 filter subtypes, 4 rooted on views |

33 classes.

## Set ids (a GLOBAL namespace — reference these, do not guess)

`fbsStatement`, `fbsRun`, `fbsLineItem`, `fbsAssetLine`, `fbsLiabilityLine`, `fbsEquityLine`,
`fbsCategory`, `fbsMeasurementBasis`, `fbsBookClassification`, `fbsFairValueLevel`,
`fbsTradingPosition`, `fbsBankingAsset`, `fbsDerivativeAsset`, `fbsDerivativeLiability`,
`fbsNettingAdjustment`, `fbsCollateralOffset`, `fbsHqlaStock`, `fbsGoodwill`, `fbsIntangible`,
`fbsCapitalDeduction`, `fbsProvision`, `fbsEclAllowance`, `fbsEquityComponent`,
`fbsEquityBalance`, `fbsOciReserve`, `fbsRetainedEarnings`, `fbsAdjustment`, `fbsBalanceCheck`,
`fbsAttestation`, `fbsCategoryTotal`, `fbsBasisTotal`, `fbsBookTotal`, `fbsSideTotal`.

Six do not follow the class name: `fbsStatement` (`FbsBalanceSheet`), `fbsRun`
(`FbsBalanceSheetRun`), `fbsTradingPosition` (`FbsTradingBookPosition`), `fbsBankingAsset`
(`FbsBankingBookAsset`), `fbsIntangible` (`FbsIntangibleAsset`), `fbsAdjustment`
(`FbsAccountingAdjustment`). Read them from here rather than guessing.

Every id is named EXPLICITLY, so the default ids (`firm_balance_sheet_FbsLineItem` and the
rest) **do not exist**. `fbsLineItem` is the only set marked root (`*`). A downstream
`extends [...]` or cross-project `AssociationMapping` must name an id from this list.

## Store detail

All tables and views are inside `Schema fbs`.

| table | primary key |
| --- | --- |
| FBS_STATEMENT | INSTITUTION_ID, AS_OF_DATE, REPORTING_CURRENCY |
| FBS_RUN | RUN_ID |
| **FBS_LINE_ITEM** | LINE_ID |
| FBS_CATEGORY | CATEGORY_CODE |
| FBS_MEASUREMENT_BASIS | BASIS_CODE |
| FBS_BOOK_CLASSIFICATION | BOOK_CODE |
| FBS_FAIR_VALUE_LEVEL | LEVEL_CODE |
| FBS_TRADING_POSITION | LINE_ID |
| FBS_BANKING_ASSET | LINE_ID |
| FBS_DERIVATIVE_ASSET | DERIVATIVE_ASSET_ID |
| FBS_DERIVATIVE_LIABILITY | DERIVATIVE_LIABILITY_ID |
| FBS_NETTING_ADJUSTMENT | NETTING_SET_ID, AS_OF_DATE |
| FBS_COLLATERAL_OFFSET | OFFSET_ID |
| FBS_HQLA_STOCK | INSTITUTION_ID, AS_OF_DATE, HQLA_LEVEL_CODE, REPORTING_CURRENCY |
| FBS_GOODWILL | GOODWILL_ID |
| FBS_INTANGIBLE | INTANGIBLE_ID |
| FBS_CAPITAL_DEDUCTION | DEDUCTION_ID |
| FBS_PROVISION | PROVISION_ID |
| FBS_ECL_ALLOWANCE | ALLOWANCE_ID |
| FBS_EQUITY_COMPONENT | COMPONENT_CODE |
| FBS_EQUITY_BALANCE | INSTITUTION_ID, AS_OF_DATE, COMPONENT_CODE, REPORTING_CURRENCY |
| FBS_OCI_RESERVE | RESERVE_ID |
| FBS_RETAINED_EARNINGS | INSTITUTION_ID, AS_OF_DATE, REPORTING_CURRENCY |
| FBS_ADJUSTMENT | ADJUSTMENT_ID |
| FBS_BALANCE_CHECK | CHECK_ID |
| FBS_ATTESTATION | INSTITUTION_ID, AS_OF_DATE, ROLE_CODE |

`INSTRUMENT_ID` on `FBS_TRADING_POSITION` is `VARCHAR(60)` — pnl-attribution's width, because
the wider of two candidate widths is the only one that cannot truncate.

### Views (the AGGREGATION shape — `~groupBy` IS the primary key)

| view | grouping | one row is |
| --- | --- | --- |
| FBS_CATEGORY_TOTAL | institution, date, currency, category | the statement as printed; carries `max`/`min` beside the sums |
| FBS_BASIS_TOTAL | + basis, side | the IFRS 9 disclosure — how much of each side is at AC / FVOCI / FVTPL |
| FBS_BOOK_TOTAL | institution, date, book, side | the trading/banking boundary; drops the currency on purpose |
| FBS_SIDE_TOTAL | institution, date, currency, side | **the balance check** — ASSET, LIABILITY, EQUITY, three rows |

All four group `fbs.FBS_LINE_ITEM`. These are Legend views, not database views: no DDL, nothing
seeds them, the engine folds the `GROUP BY` into its SQL. **A group with no rows forms NO GROUP
and is absent, not zero** — a category with nothing in it on a date does not appear, and a
downstream report that must print every category every day drives from `FbsCategory` and
outer-joins. The sums are `Float[0..1]`, because a `sum()` over a nullable column is nullable;
every derived property above `->orElse(0.0)` before doing arithmetic.

`FBS_STATEMENT.TOTAL_ASSETS` is what was **published**; `FbsSideTotal` is what the **lines
say**. A downstream control report should compare them rather than pick one.

### Cross-project joins (16 of the 60)

| join | shape |
| --- | --- |
| `Fbs_TradingPositionPnlLine` | **5 columns** into `PNL_LINE` — the deepest reference in the graph |
| `Fbs_StatementPnlRunTotal` / `Fbs_RetainedEarningsPnlRunTotal` | into pnl-attribution's `PNL_RUN_TOTAL` **view** |
| `Fbs_RunPnlRun` | `PNL_RUN.ATTRIBUTION_ID` |
| `Fbs_LineNetExposure`, `Fbs_BankingAssetNetExposure`, `Fbs_EclNetExposure`, `Fbs_DerivativeAssetNetExposure`, `Fbs_DerivativeLiabilityNetExposure` | five routes into `EXA_NET_EXPOSURE` |
| `Fbs_NettingAdjustmentNettingSet`, `Fbs_DerivativeAssetNettingSet`, `Fbs_DerivativeLiabilityNettingSet` | into `EXA_NETTING_SET` — enforceability is read, never re-derived |
| `Fbs_CollateralOffsetPosition` | into `EXA_COLLATERAL_POSITION` |
| `Fbs_DerivativeAssetCounterpartyDayNet` | 2 columns into exposure-agg's `EXA_COUNTERPARTY_DAY_NET` **view** |
| `Fbs_RunExposureRun` | `EXA_RUN.RUN_ID` |
| `Fbs_HqlaStockLevel` | into `LQV_HQLA_LEVEL` |
| `Fbs_HqlaStockLcrResult` | **4 columns** into `LQV_LCR_RESULT`; `REPORTING_CURRENCY` meets `CURRENCY_CODE` |
| `Fbs_AssetLineHqlaHolding` | into `LQV_HQLA_HOLDING` |
| `Fbs_StatementNsfrResult` | 3 columns into `LQV_NSFR_RESULT` |

Upstream set ids named: `pnlLine`, `pnlRunTotal`, `pnlRun`, `exaNetExposure`, `exaNettingSet`,
`exaCollateralPosition`, `exaCounterpartyDayNet`, `exaRun`, `lqvHqlaLevel`, `lqvHolding`,
`lqvLcrResult`, `lqvNsfrResult`.

### Filters

Three back the subtype family — `FbsAssetRows`, `FbsLiabilityRows`, `FbsEquityRows`, each
`fbs.FBS_LINE_ITEM.SIDE = '…'`. Unlike an overlapping regime/state split these three are
**disjoint and exhaustive**: a row is on exactly one side of a balance sheet.

Declared and unapplied, for downstream: `FbsOpenBalanceChecks` (`RESOLVED_ON is null`),
`FbsLiveCategories` (`RETIRED_ON is null`), `FbsLiveAdjustments` (`REVERSED_ON is null`),
`FbsCurrentAttestations` (`WITHDRAWN_ON is null`), `FbsPublishedStatements`
(`SIGNED_OFF_ON is null`), `FbsFairValuedLines` (`BASIS_CODE = 'FVTPL'`). String comparisons and
null tests only — a `Filter` will not take a boolean literal.

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(24,2) | every money column — carrying/gross/impairment/prior amounts, gross positive and negative derivative values, CVA/DVA/FVA, offsets, collateral, HQLA amounts, goodwill, intangibles, deductions, provisions, ECL, equity balances, reserves, retained earnings, adjustments and check amounts |
| NUMERIC(20,6) | QUANTITY — a share count needs more decimals and fewer digits than a balance |
| FLOAT | FX_RATE, EFFECTIVE_INTEREST_RATE, DISCOUNT_RATE |
| SMALLINT | STATEMENT_ORDER, LEVEL_RANK, ECL_STAGE, USEFUL_LIFE_YEARS, PRESENTATION_ORDER |
| INTEGER | LINE_COUNT, REJECTED_LINE_COUNT, LIQUIDITY_DAYS, HOLDING_COUNT |
| BIT | every flag |

Money is NUMERIC because a balance sheet has to reconcile to the last unit; rates are FLOAT
because they are compared against a threshold rather than for equality. `REAL` is not used: it
parses, compiles, and cannot be read back at execution (F53).

## Notes for downstream

- `include firm_balance_sheet::Store` and `include firm_balance_sheet::Mapping` bring **all
  seventeen** projects below transitively. Do not include any of them a second time.
- **Every reference to a table here needs the `fbs.` schema segment.** This is the one thing
  that differs from every layer-1 and layer-2 project in the graph.
- `FbsLineItem` is the only root set. `FbsLineItem.all()` returns every row of all three sides;
  `FbsAssetLine.all()` returns the filtered subset.
- A property declared on a subclass (`unencumberedAmount`, `maturityDate`,
  `equityComponentCode`) is mapped on the subclass's own set and cannot be mapped on
  `[fbsLineItem]`.
- **The aggregate classes are groups, not rows.** Navigating `line.sideTotal` crosses into a
  `GROUP BY` and never into another line.
- Two numbers are deliberately allowed to disagree, and both disagreements are the point:
  `FbsBalanceSheet.totalAssets` against `FbsSideTotal.totalCarrying` (published vs computed),
  and `FbsRetainedEarnings.profitForPeriod` against `pnlRunTotal.totalPnl()` (the accounts vs
  the explain).
- Every derived property that divides is declared `Float[1]` — `/` widens to Float regardless
  of its operands. Where an Integer multiplies a Float, `->toFloat()` is written explicitly,
  because `Integer * Float` types as `Number` and `Number` is not a subtype of `Float`.
- Only pnl-attribution, exposure-agg and liquidity-view are named. Their transitive
  dependencies are reachable through them and are never named here.

## Verified

    python3 scripts/projects/check.py firm-balance-sheet
    compiles  firm-balance-sheet (+17 deps)   parse 442ms  compile 2033ms
