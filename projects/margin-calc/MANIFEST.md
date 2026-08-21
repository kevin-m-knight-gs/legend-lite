# margin-calc

Layer 2. Depends on **position-keeping** and **risk-core**, and on nothing else. A DIAMOND:
both dependencies include core-instrument, so that project's store and mapping arrive down
two paths and resolve to one.

How much collateral a counterparty must have posted by tonight, and whether we are allowed
to ask for it — 15 classes over 13 tables, two store views and 12 standalone functions.

Exports **classes**, a **store**, a **mapping** and **functions**. No enums, no profiles, no
associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**Functions.** Twelve `function` elements in `margin_calc::`, called from the derived
properties in this project and intended to be called from downstream ones instead of being
re-derived. Three of them encode rules that are routinely re-implemented wrong: a haircut
multiplies by `(1 - h)` and does not subtract; a close-out scaling is `sqrt(days)` and not
`days`; and two correlated charges combine under `sqrt(a² + b² + 2ρab)`, which is `a + b`
only at ρ = 1.

**Aggregation.** Two store `View`s, each mapped onto a class whose `~primaryKey` is its
`~groupBy`:

| view | grouping | one row is |
| --- | --- | --- |
| MGN_PORTFOLIO_MARGIN_TOTAL | margin portfolio, cob date | one netting set's whole margin on one day |
| MGN_RISK_CLASS_SENS_TOTAL | margin portfolio, cob date, **risk class** | one risk class's weighted sensitivities, totalled |

The second grouping carries a third column deliberately: a sensitivity-based calculation
aggregates *inside* a risk class before it aggregates across them, so grouping by set and
date alone would add an interest-rate delta to an equity delta.

A third aggregation is **read, not declared**: `MgnConcentrationAddOn.factorTotal` navigates
into risk-core's own store view `RSK_FACTOR_TOTAL` on `(FACTOR_ID, COB_DATE)`, so a
concentration charge is measured against the firm-wide delta in a factor without this
project summing a single leg.

These are Legend views, not database ones: no DDL, nothing seeds them, and the engine folds
the `GROUP BY` into the SQL. A netting set with no positions on a date forms **no group** and
is absent, rather than appearing with a total of zero.

## The domain, in the order the numbers are produced

1. `MgnSimmSensitivity` — a risk-core leg with a SIMM risk class, bucket and **risk weight**
   attached; `weighted = raw × weight`
2. legs aggregate inside a class under a correlation → `MgnRiskClassMargin`; classes
   aggregate again → `MgnInitialMargin`
3. that one-day number is scaled to a **close-out period** at a **confidence level** —
   ten days, 99% — because margin has to survive the time it takes to replace a defaulted
   book, not one day of it
4. **add-ons** for concentration (`MgnConcentrationAddOn`) and liquidity
   (`MgnLiquidityAddOn`) stack on top
5. **variation margin** settles the day's mark-to-market move in cash, daily
   (`MgnVariationMargin`)
6. collateral already held is valued **after haircut** (`MgnCollateralHaircut`)
7. the **minimum transfer amount** decides whether the call is made at all — which is why
   `MgnMarginCall` carries both `grossCallAmount` (the shortfall that exists) and
   `callAmount` (what may be demanded), and why `MgnMarginRun.suppressedCallCount` exists

## Elements

| element | kind | note |
| --- | --- | --- |
| margin_calc::MgnMarginAgreement | class | THE CSA. threshold, MTA, rounding, independent amount, close-out days, confidence; 3 constraints; derived `isLive()`, `closeOutScalar()`, `effectiveThreshold()`, `callTriggerLevel()` |
| margin_calc::MgnMarginPortfolio | class | the netting set; carries risk-core's PORTFOLIO_ID **and** position-keeping's two account columns; derived `isSimm()`, `accountKey()` |
| margin_calc::MgnMarginRun | class | one execution of the batch; key `(cobDate, runSeq)`; derived `isComplete()`, `suppressedPct()`, `averageCallAmount()` |
| margin_calc::MgnMarginCall | class | the call; key `(agreementId, cobDate)`; 2 constraints; derived `exposureAboveThreshold()`, `meetsMinimumTransfer()`, `transferableAmount()`, `roundedCallAmount()`, `uncollateralisedByMta()`, `coverageRatio()`, `isSettled()`, `isDisputed()` |
| margin_calc::MgnVariationMargin | class | the daily settled leg; key `(agreementId, cobDate)`; derived `dailyMove()`, `markConsistency()`, `isCollectingSide()`, `netPosted()`, `lagExposure()` |
| margin_calc::MgnInitialMargin | class | IM per set per day per method; key `(marginPortfolioId, cobDate, methodCode)`; 2 constraints; derived `horizonScalar()`, `scaledFromOneDay()`, `addOnTotal()`, `addOnPct()`, `marginFromModel()` |
| margin_calc::MgnSimmBucket | class | the SIMM calibration; key `(riskClass, bucketCode)`; qualified `weightFor(Float)`, `vegaWeightFor(Float)`, `aggregateWithin(Float, Float)`; derived `liquidityScalar()` |
| margin_calc::MgnSimmSensitivity | class | THE leg the calculation sums; key `(sensitivityId, cobDate)`; 2 constraints; derived `weighted()`, `concentrationScaled()`, `absWeighted()`; qualified `aggregateWith(Float, Float)`, `overCloseOut(Integer)` |
| margin_calc::MgnRiskClassMargin | class | one risk class of one set, after correlation; key `(marginPortfolioId, cobDate, riskClass)`; derived `beforeCorrelation()`, `nettingBenefit()`, `nettingBenefitPct()`; qualified `aggregateWith(Float)`, `overCloseOut(Integer)` |
| margin_calc::MgnConcentrationAddOn | class | per-factor concentration charge; 4-column key `(marginPortfolioId, cobDate, riskClass, factorId)`; derived `ratioOverThreshold()`, `concentrationFactor()`, `shareOfFirmFactorDelta()`; qualified `addOnFrom(Float)` |
| margin_calc::MgnLiquidityAddOn | class | per-bucket liquidity-horizon charge; key `(marginPortfolioId, cobDate, bucketCode)`; derived `horizonScalar()`, `impliedDaysToLiquidate()`, `exceedsBaseline()`; qualified `addOnFrom(Float)`, `overRealHorizon(Float)` |
| margin_calc::MgnCollateralHaircut | class | the haircut schedule; key `(agreementId, collateralAssetId)`; 2 constraints; derived `valueAfterHaircut()`, `valueAfterAllHaircuts()`, `effectiveHaircutPct()`; qualified `cappedContribution(Float)` |
| margin_calc::MgnPositionMargin | class | margin attributed to ONE position — position-keeping's four-column grain exactly; derived `totalMargin()`, `marginRate()`, `weightedExposure()`, `shareOfPortfolioMargin()` |
| margin_calc::MgnPortfolioMarginTotal | class | AGGREGATE on a view: one set, one day; derived `totalMargin()`, `averageMarginPerPosition()` (Float, `/` widens), `marginRate()`, `isSinglePositionDriven()` |
| margin_calc::MgnRiskClassSensitivityTotal | class | AGGREGATE on a view: one set, one day, one risk class; derived `averageWeightedSensitivity()`, `impliedRiskWeight()`, `largestShare()`, `isSingleFactorDriven()` |
| margin_calc::Store | store | includes position_keeping::Store and risk_core::Store; 13 tables, 2 views, 21 joins, 2 filters |
| margin_calc::Mapping | mapping | includes position_keeping::Mapping and risk_core::Mapping; 15 sets, every `~primaryKey` explicit |

## Functions (call these; do not re-derive them)

| function | signature |
| --- | --- |
| `margin_calc::mgnHaircut` | `(marketValue: Float[1], haircutPct: Float[1]): Float[1]` — `v * (1 - h)` |
| `margin_calc::mgnWeightedSensitivity` | `(sensitivity: Float[1], riskWeight: Float[1]): Float[1]` |
| `margin_calc::mgnCorrelatedAggregate` | `(chargeA: Float[1], chargeB: Float[1], correlation: Float[1]): Float[1]` — `sqrt(a² + b² + 2ρab)` |
| `margin_calc::mgnCloseOutScalar` | `(closeOutPeriodDays: Integer[1]): Float[1]` — `sqrt(days)` |
| `margin_calc::mgnScaleToCloseOut` | `(oneDayValue: Float[1], closeOutPeriodDays: Integer[1]): Float[1]` |
| `margin_calc::mgnAddOn` | `(baseCharge: Float[1], addOnPct: Float[1]): Float[1]` |
| `margin_calc::mgnConcentrationFactor` | `(netSensitivity: Float[1], concentrationThreshold: Float[1]): Float[1]` — `max(1, sqrt(\|S\|/T))` |
| `margin_calc::mgnTotalInitialMargin` | `(oneDayValue: Float[1], closeOutPeriodDays: Integer[1], concentrationAddOn: Float[1], liquidityAddOn: Float[1]): Float[1]` |
| `margin_calc::mgnCallAmount` | `(requiredMargin: Float[1], collateralValue: Float[1], threshold: Float[1]): Float[1]` |
| `margin_calc::mgnMeetsMta` | `(callAmount: Float[1], minimumTransferAmount: Float[1]): Boolean[1]` |
| `margin_calc::mgnAfterMinimumTransfer` | `(callAmount: Float[1], minimumTransferAmount: Float[1]): Float[1]` — the whole amount, or zero |
| `margin_calc::mgnRoundUp` | `(callAmount: Float[1], roundingAmount: Float[1]): Float[1]` — `ceiling` returns Integer, so it casts back |

Add-ons are **not** scaled to the close-out period a second time inside
`mgnTotalInitialMargin`: they are already sized to the horizon they correct for, and scaling
them again is a double count.

## Set ids (a GLOBAL namespace — reference these, do not guess)

`mgnAgreement`, `mgnMarginPortfolio`, `mgnMarginRun`, `mgnMarginCall`, `mgnVariationMargin`,
`mgnInitialMargin`, `mgnSimmBucket`, `mgnSimmSensitivity`, `mgnRiskClassMargin`,
`mgnConcentrationAddOn`, `mgnLiquidityAddOn`, `mgnCollateralHaircut`, `mgnPositionMargin`,
`mgnPortfolioMarginTotal`, `mgnRiskClassSensTotal`.

Note the last one: the class is `MgnRiskClassSensitivityTotal` but the set id is
`mgnRiskClassSensTotal`. None is marked root, and every cross-set property mapping names its
target id explicitly, so a downstream `extends [...]` or `AssociationMapping` must name
`[mgnMarginCall]` (etc.) rather than a default id.

## Store detail

| table | primary key |
| --- | --- |
| MGN_AGREEMENT | AGREEMENT_ID |
| MGN_MARGIN_PORTFOLIO | MARGIN_PORTFOLIO_ID |
| MGN_RUN | COB_DATE, RUN_SEQ |
| MGN_MARGIN_CALL | AGREEMENT_ID, COB_DATE |
| MGN_VARIATION_MARGIN | AGREEMENT_ID, COB_DATE |
| MGN_INITIAL_MARGIN | MARGIN_PORTFOLIO_ID, COB_DATE, METHOD_CODE |
| MGN_SIMM_BUCKET | RISK_CLASS, BUCKET_CODE |
| MGN_SIMM_SENSITIVITY | SENSITIVITY_ID, COB_DATE |
| MGN_RISK_CLASS_MARGIN | MARGIN_PORTFOLIO_ID, COB_DATE, RISK_CLASS |
| MGN_CONCENTRATION_ADDON | MARGIN_PORTFOLIO_ID, COB_DATE, RISK_CLASS, FACTOR_ID |
| MGN_LIQUIDITY_ADDON | MARGIN_PORTFOLIO_ID, COB_DATE, BUCKET_CODE |
| MGN_COLLATERAL_HAIRCUT | AGREEMENT_ID, COLLATERAL_ASSET_ID |
| MGN_POSITION_MARGIN | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE |

| view | key = grouping | measures |
| --- | --- | --- |
| MGN_PORTFOLIO_MARGIN_TOTAL | MARGIN_PORTFOLIO_ID, COB_DATE | POSITION_COUNT, TOTAL_INITIAL_MARGIN, TOTAL_VARIATION_MARGIN, TOTAL_ADDON, TOTAL_EXPOSURE, LARGEST_POSITION_MARGIN |
| MGN_RISK_CLASS_SENS_TOTAL | MARGIN_PORTFOLIO_ID, COB_DATE, RISK_CLASS | SENSITIVITY_COUNT, TOTAL_WEIGHTED_SENSITIVITY, TOTAL_RAW_SENSITIVITY, LARGEST_WEIGHTED_SENSITIVITY |

### Joins, for a downstream store that includes this one

| join | shape |
| --- | --- |
| Mgn_AgreementPortfolios / Mgn_AgreementCalls / Mgn_AgreementVariationMargins / Mgn_AgreementHaircuts | AGREEMENT_ID |
| Mgn_CallVariationMargin | AGREEMENT_ID **and** COB_DATE |
| Mgn_CallRun | COB_DATE **and** RUN_SEQ |
| Mgn_PortfolioInitialMargins / Mgn_PortfolioRiskClassMargins / Mgn_PortfolioSimmSensitivities / Mgn_PortfolioConcentrationAddOns / Mgn_PortfolioLiquidityAddOns / Mgn_PortfolioPositionMargins | MARGIN_PORTFOLIO_ID |
| Mgn_BucketSensitivities | RISK_CLASS **and** BUCKET_CODE |
| Mgn_RiskClassMarginInitialMargins | MARGIN_PORTFOLIO_ID **and** COB_DATE |
| Mgn_PortfolioMarginTotals | MARGIN_PORTFOLIO_ID, into the view |
| Mgn_PositionMarginPortfolioTotal | MARGIN_PORTFOLIO_ID, COB_DATE, into the view |
| Mgn_RiskClassMarginSensTotal / Mgn_SensitivityRiskClassTotal | three columns, into the view |
| Mgn_PositionMarginPosition | **cross-project** — all FOUR position columns to PK_POSITION |
| Mgn_PortfolioRiskPortfolio | **cross-project** — RISK_PORTFOLIO_ID to RSK_PORTFOLIO |
| Mgn_SimmSensitivityRiskLeg | **cross-project** — SENSITIVITY_ID to RSK_SENSITIVITY |
| Mgn_SimmSensitivityFactor | **cross-project** — FACTOR_ID to RSK_RISK_FACTOR |
| Mgn_ConcentrationFactorTotal | **cross-project, into somebody else's VIEW** — FACTOR_ID and COB_DATE to RSK_FACTOR_TOTAL |

### Filters

`MgnLiveAgreements` (`MGN_AGREEMENT.TERMINATED_ON is null`) and `MgnUnsettledCalls`
(`MGN_MARGIN_CALL.SETTLED_ON is null`) — null tests, because a `Filter` will not take a
boolean literal.

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(24,8) | THRESHOLD, MINIMUM_TRANSFER_AMOUNT, ROUNDING_AMOUNT, INDEPENDENT_AMOUNT, all *_MARGIN, all *_AMOUNT, COLLATERAL_VALUE, GROSS_CALL_AMOUNT, CALL_AMOUNT, *_MARK_TO_MARKET, MTM_CHANGE, CUMULATIVE_VM_POSTED, ONE_DAY_MARGIN, SCALED_MARGIN, CONCENTRATION_ADDON, LIQUIDITY_ADDON, CONCENTRATION_THRESHOLD, RAW_SENSITIVITY, WEIGHTED_SENSITIVITY, GROSS_SENSITIVITY, NET_SENSITIVITY, POSITION_NOTIONAL, AVERAGE_DAILY_VOLUME, MARKET_VALUE, HAIRCUT_VALUE, EXPOSURE_AMOUNT |
| FLOAT | CONFIDENCE_PCT, RISK_WEIGHT, VEGA_RISK_WEIGHT, INTRA/INTER_BUCKET_CORRELATION, INTRA_CLASS_CORRELATION, CONCENTRATION_RISK_FACTOR, CONCENTRATION_RATIO, ADDON_PCT, DAYS_TO_LIQUIDATE, HAIRCUT_PCT, FX_HAIRCUT_PCT, CONCENTRATION_CAP_PCT, CURVATURE_SCALE |
| SMALLINT | CLOSE_OUT_PERIOD_DAYS, SETTLEMENT_LAG_DAYS, LIQUIDITY_HORIZON_DAYS, BASELINE_HORIZON_DAYS |
| CHAR(3) | BASE_CURRENCY, CALL_CURRENCY, VM_CURRENCY, IM_CURRENCY, MARGIN_CURRENCY, ADDON_CURRENCY, SENSITIVITY_CURRENCY, CURRENCY_CODE |

`REAL` is not used anywhere (docs/UPSTREAM_FINDINGS.md F53).

## Cross-project references made

* `Database margin_calc::Store ( include position_keeping::Store include risk_core::Store ... )`
* `Mapping margin_calc::Mapping ( include position_keeping::Mapping include risk_core::Mapping )`
* `MgnPositionMargin.position: position_keeping::Position[0..1]`, mapped
  `position[pkPosition]: [...]@Mgn_PositionMarginPosition` — four columns
* `MgnMarginPortfolio.riskPortfolio: risk_core::RskRiskPortfolio[0..1]`, mapped
  `riskPortfolio[rskPortfolio]`
* `MgnSimmSensitivity.riskLeg: risk_core::RskSensitivity[0..1]` → `[rskSensitivity]`, and
  `MgnSimmSensitivity.factor: risk_core::RskRiskFactor[0..1]` → `[rskFactor]`
* `MgnConcentrationAddOn.factorTotal: risk_core::RskFactorTotal[0..1]` → `[rskFactorTotal]`,
  which is a set rooted on **another project's store view**

## Notes for downstream

- `MgnMarginCall.grossCallAmount` and `MgnMarginCall.callAmount` are different columns on
  purpose. A shortfall below the MTA is a real, uncollateralised shortfall that nobody is
  allowed to ask for; `uncollateralisedByMta()` states it, and
  `MgnMarginRun.suppressedCallCount` counts how often it happened.
- Navigating a `[0..1]` into an aggregate and then dividing needs `->toOne()`:
  `shareOfFirmFactorDelta()` and `shareOfPortfolioMargin()` both do this, because `/`
  requires a `[1]` operand.
- Money is `Float[1]` throughout, never `[0..1]`: a margin component that does not apply is
  stored as zero, so a sum never has to decide what an absent number means.
- `MgnSimmSensitivity.riskWeight` is FROZEN onto the leg rather than only joined from
  `MgnSimmBucket`, so a recalibration does not silently restate yesterday's margin.
  `weighted()` re-derives the product as the data-quality screen on that.

## Verified

    python3 scripts/projects/check.py margin-calc
    compiles  margin-calc (+5 deps)
