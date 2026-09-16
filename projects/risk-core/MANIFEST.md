# risk-core

Layer 1. Depends on **core-instrument** and **core-price**, and on nothing else. Risk factors,
the sensitivities measured against them, VaR and expected shortfall, stress scenarios and
limits — 28 classes over 19 tables and one store VIEW.

The two constructs this project carries for the graph:

* **a store `View`**, `RSK_FACTOR_TOTAL`, which `~groupBy`s `RSK_SENSITIVITY` by
  `FACTOR_ID, COB_DATE` and exposes `sum(DELTA_VALUE)` and `count(SENSITIVITY_ID)`. It is a
  Legend view, not a database one: no DDL, nothing seeds it, the engine folds the `GROUP BY`
  into the SQL. `~groupBy` takes COLUMN REFERENCES, not expressions.
* **an `AggregationAware` class mapping**, `[rskFactorExposure]`, whose `~mainMapping` is the
  leg table and whose one aggregate view is that same store view.

The difference between the two is the point. `RskFactorTotal` is the view used **as a table** —
a report names the class and reads the groups. `RskFactorExposure` is the view used **as an
index** — nothing names it, and a query over the legs that groups by factor and date and sums
the delta is rewritten onto the pre-aggregate by the engine.

**The three names a downstream project needs:** `risk_core::RskRiskFactor` with the root set id
**`[rskFactor]`** (extend both to add a factor type), `risk_core::RskSensitivity` with
**`[rskSensitivity]`**, and the view class `risk_core::RskFactorTotal`.

| element | kind | note |
| --- | --- | --- |
| risk_core::RskRiskFactor | class | BASE class, ROOT set; id, type, name, currency, curve, quotation and shock unit |
| risk_core::RskRatesFactor | class | extends RskRiskFactor; curve convention, compounding, index tenor |
| risk_core::RskCreditSpreadFactor | class | extends RskRiskFactor; reference entity, seniority, restructuring, recovery |
| risk_core::RskEquityFactor | class | extends RskRiskFactor; ticker, GICS sector, index, basket flag |
| risk_core::RskFxFactor | class | extends RskRiskFactor; base and quote currency, peg flag |
| risk_core::RskCommodityFactor | class | extends RskRiskFactor; group, delivery location, contract month, grade |
| risk_core::RskVolatilityFactor | class | extends RskRiskFactor; surface, moneyness, expiry tenor |
| risk_core::RskFactorCurvePoint | class | one pillar of a factor's curve on one date; key (factor, date, tenor) |
| risk_core::RskFactorCorrelation | class | pairwise correlation; key (factorA, factorB, date, windowDays) |
| risk_core::RskGreekMeasure | class | the five greeks as a code table: DELTA, GAMMA, VEGA, THETA, RHO |
| risk_core::RskSensitivity | class | THE leg row: one greek vector per leg per factor per date; 2 constraints, 4 derived properties |
| risk_core::RskMaterialSensitivity | class | subtype of RskSensitivity, `~filter RskMaterialRows` over the same table and key |
| risk_core::RskCrossGamma | class | second-order cross term; key (date, portfolio, factorA, factorB) |
| risk_core::RskFactorExposure | class | THE AggregationAware class; exactly factorId, cobDate, deltaValue |
| risk_core::RskFactorTotal | class | one row of the VIEW: total delta and leg count per factor per date |
| risk_core::RskSensitivityLadder | class | one greek for one factor and portfolio, cut into buckets by TENOR or STRIKE |
| risk_core::RskLadderBucket | class | one bucket of a ladder; key (ladder, bucketSeq) — the order is part of the ladder |
| risk_core::RskRiskPortfolio | class | the reporting unit every measure below is keyed by |
| risk_core::RskValueAtRisk | class | VaR; 5-column key (portfolio, date, confidencePct, horizonDays, methodCode) |
| risk_core::RskExpectedShortfall | class | tail mean; key (portfolio, date, confidencePct, horizonDays) |
| risk_core::RskVarBacktest | class | VaR against realised P&L; key (portfolio, date); traffic-light zone |
| risk_core::RskStressScenario | class | a named set of shocks: HISTORICAL, HYPOTHETICAL, REGULATORY |
| risk_core::RskScenarioShock | class | one factor's move in one scenario; key (scenario, factor) |
| risk_core::RskStressResult | class | scenario applied to a portfolio on a date; key of all three |
| risk_core::RskRiskLimit | class | cap on one measure for a portfolio, optionally for one factor |
| risk_core::RskLimitUtilisation | class | daily usage against the cap that was standing; key (limit, date) |
| risk_core::RskLimitBreach | class | a breach and its approval; key (limit, date, breachSeq) |
| risk_core::RskRiskInstrument | class | extends core_instrument::Instrument; risk model, pricing library, default bump |
| risk_core::Store | store | 19 RSK_ tables + View RSK_FACTOR_TOTAL; includes core_instrument::Store and core_price::Store |
| risk_core::Mapping | mapping | 28 sets, one of them AggregationAware; includes core_instrument::Mapping and core_price::Mapping |

## Set ids (extend these; they are a GLOBAL namespace)

Every id is explicit, so the DEFAULT ids (`risk_core_RskRiskFactor` and friends) **do not
exist** — a downstream `extends [...]` or cross-project `AssociationMapping` must name the id
below.

`rskFactor` (root, `*risk_core::RskRiskFactor`), then `rskRatesFactor`,
`rskCreditSpreadFactor`, `rskEquityFactor`, `rskFxFactor`, `rskCommodityFactor`,
`rskVolatilityFactor` — each `extends [rskFactor]` with a `~filter`.

`rskSensitivity`, and `rskMaterialSensitivity` which `extends [rskSensitivity]`.

Then, one table each: `rskCurvePoint`, `rskCorrelation`, `rskGreekMeasure`, `rskCrossGamma`,
`rskLadder`, `rskLadderBucket`, `rskPortfolio`, `rskVar`, `rskExpectedShortfall`,
`rskVarBacktest`, `rskScenario`, `rskScenarioShock`, `rskStressResult`, `rskLimit`,
`rskLimitUtilisation`, `rskLimitBreach`.

Over the VIEW: `rskFactorTotal` (rooted at it) and `rskFactorExposure` (AggregationAware; the
engine derives `rskFactorExposure_Main` and `rskFactorExposure_Aggregate_0` from it).

Across the boundary: `rskRiskInstrument` `extends [ciBase]` — core-instrument's root set.

## The VIEW

    View RSK_FACTOR_TOTAL
    (
      ~groupBy ( RSK_SENSITIVITY.FACTOR_ID, RSK_SENSITIVITY.COB_DATE )
      FACTOR_ID: RSK_SENSITIVITY.FACTOR_ID PRIMARY KEY,
      COB_DATE: RSK_SENSITIVITY.COB_DATE PRIMARY KEY,
      TOTAL_DELTA: sum(RSK_SENSITIVITY.DELTA_VALUE),
      LEG_COUNT: count(RSK_SENSITIVITY.SENSITIVITY_ID)
    )

The grouping columns are the view's PRIMARY KEY, which is what makes it joinable —
`Rsk_FactorTotal` hangs it off `RSK_RISK_FACTOR`, and `RskRiskFactor.dailyTotals` navigates
into the `GROUP BY` without ever touching a leg row.

A factor with no legs on a date forms **no group** and is simply absent, rather than appearing
with a total of zero. Anything downstream that needs the zero has to outer-join from
`RSK_RISK_FACTOR` instead.

## Store names

Tables `RSK_RISK_FACTOR`, `RSK_FACTOR_CURVE_POINT`, `RSK_FACTOR_CORRELATION`,
`RSK_GREEK_MEASURE`, `RSK_SENSITIVITY`, `RSK_CROSS_GAMMA`, `RSK_LADDER`, `RSK_LADDER_BUCKET`,
`RSK_PORTFOLIO`, `RSK_VAR_MEASURE`, `RSK_EXPECTED_SHORTFALL`, `RSK_VAR_BACKTEST`,
`RSK_STRESS_SCENARIO`, `RSK_SCENARIO_SHOCK`, `RSK_STRESS_RESULT`, `RSK_RISK_LIMIT`,
`RSK_LIMIT_UTILISATION`, `RSK_LIMIT_BREACH`, `RSK_INSTRUMENT_RISK`. View `RSK_FACTOR_TOTAL`.

### Joins, for a downstream store that includes this one

| join | shape |
| --- | --- |
| Rsk_SensitivityFactor | RSK_SENSITIVITY.FACTOR_ID = RSK_RISK_FACTOR.FACTOR_ID |
| Rsk_SensitivityPortfolio | RSK_SENSITIVITY.PORTFOLIO_ID = RSK_PORTFOLIO.PORTFOLIO_ID |
| Rsk_FactorCurvePoint | RSK_FACTOR_CURVE_POINT.FACTOR_ID = RSK_RISK_FACTOR.FACTOR_ID |
| Rsk_FactorTotal | RSK_FACTOR_TOTAL.FACTOR_ID = RSK_RISK_FACTOR.FACTOR_ID — the VIEW as a join target |
| Rsk_LadderBucket | RSK_LADDER_BUCKET.LADDER_ID = RSK_LADDER.LADDER_ID |
| Rsk_LadderFactor | RSK_LADDER.FACTOR_ID = RSK_RISK_FACTOR.FACTOR_ID |
| Rsk_LadderGreek | RSK_LADDER.MEASURE_CODE = RSK_GREEK_MEASURE.MEASURE_CODE |
| Rsk_PortfolioVar | RSK_VAR_MEASURE.PORTFOLIO_ID = RSK_PORTFOLIO.PORTFOLIO_ID |
| Rsk_VarShortfall | four columns: portfolio, date, confidence, horizon |
| Rsk_ScenarioShock | RSK_SCENARIO_SHOCK.SCENARIO_ID = RSK_STRESS_SCENARIO.SCENARIO_ID |
| Rsk_ShockFactor | RSK_SCENARIO_SHOCK.FACTOR_ID = RSK_RISK_FACTOR.FACTOR_ID |
| Rsk_ScenarioResult | RSK_STRESS_RESULT.SCENARIO_ID = RSK_STRESS_SCENARIO.SCENARIO_ID |
| Rsk_ResultPortfolio | RSK_STRESS_RESULT.PORTFOLIO_ID = RSK_PORTFOLIO.PORTFOLIO_ID |
| Rsk_LimitPortfolio | RSK_RISK_LIMIT.PORTFOLIO_ID = RSK_PORTFOLIO.PORTFOLIO_ID |
| Rsk_LimitUtilisation | RSK_LIMIT_UTILISATION.LIMIT_ID = RSK_RISK_LIMIT.LIMIT_ID |
| Rsk_LimitBreach | RSK_LIMIT_BREACH.LIMIT_ID = RSK_RISK_LIMIT.LIMIT_ID |
| Rsk_SensitivityInstrument | RSK_SENSITIVITY.INSTRUMENT_ID = CI_INSTRUMENT.INSTRUMENT_ID |
| Rsk_FactorInstrument | RSK_RISK_FACTOR.UNDERLYING_INSTRUMENT_ID = CI_INSTRUMENT.INSTRUMENT_ID |
| Rsk_SensitivityPrice | two columns: instrument and date, onto CPR_CONSENSUS_PRICE |
| Rsk_FactorPrice | RSK_RISK_FACTOR.UNDERLYING_INSTRUMENT_ID = CPR_CONSENSUS_PRICE.INSTRUMENT_ID |
| Rsk_InstrumentRiskAttr | CI_INSTRUMENT.INSTRUMENT_ID = RSK_INSTRUMENT_RISK.INSTRUMENT_ID |

### Filters

| filter | selects |
| --- | --- |
| RskRatesFactorRows | RSK_RISK_FACTOR.FACTOR_TYPE = 'RATES' |
| RskCreditSpreadFactorRows | ... = 'CREDIT_SPREAD' |
| RskEquityFactorRows | ... = 'EQUITY' |
| RskFxFactorRows | ... = 'FX' |
| RskCommodityFactorRows | ... = 'COMMODITY' |
| RskVolatilityFactorRows | ... = 'VOL' |
| RskMaterialRows | RSK_SENSITIVITY.IS_MATERIAL = 1 |
| RskOpenBreachRows | RSK_LIMIT_BREACH.CLOSED_ON is null |

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(24,8) | DELTA/GAMMA/VEGA/THETA/RHO_VALUE, SHOCK_SIZE, LEVEL_VALUE, CROSS_VALUE, SHOCK_A/B, STRIKE_LEVEL, BUCKET_VALUE, TOTAL_VALUE, VAR_AMOUNT, PRIOR_VAR_AMOUNT, ES_AMOUNT, ACTUAL_PNL, SHOCK_VALUE, PNL_IMPACT, BASE_VALUE, STRESSED_VALUE, LIMIT_AMOUNT, USED_AMOUNT, HEADROOM, BREACH_AMOUNT |
| FLOAT | RECOVERY_RATE, MONEYNESS, CORRELATION, WEIGHT_PCT, CONFIDENCE_PCT, UTILISATION_PCT, WARNING_PCT, EXCESS_PCT |
| SMALLINT | DECIMALS, TENOR_DAYS, WINDOW_DAYS, DERIVATIVE_ORDER, BUCKET_SEQ, BUCKET_COUNT, HIERARCHY_LEVEL, HORIZON_DAYS, SCENARIO_COUNT, TAIL_SCENARIO_COUNT, LIQUIDITY_HORIZON_DAYS, EXCEPTION_COUNT_250D, SEVERITY, BREACH_SEQ, DEFAULT_SHOCK_BP, FACTOR_COUNT |
| CHAR(3) | CURRENCY, BASE_CURRENCY, QUOTE_CURRENCY |

`REAL` is not used anywhere (docs/UPSTREAM_FINDINGS.md F53).

## Notes for downstream

- `RskSensitivity` carries two class-level constraints (`shockIsPositive`,
  `tenorIsNotNegative`), so `RskMaterialSensitivity` inherits them.
- The greeks are `Float[1]`, not `[0..1]`: a linear product's gamma is stored as zero rather
  than as null, so a sum never has to decide what an absent number means.
- Properties that cross a boundary: `RskSensitivity.instrument` and
  `RskRiskFactor.underlying` resolve to core-instrument's `[ciBase]`;
  `RskSensitivity.consensusPrice` and `RskRiskFactor.driverPrices` to core-price's
  `[cprConsensus]`. Both dependency mappings are `include`d, so nothing upstream is re-mapped.
- No enums, no profiles, no functions, no associations and no `###Data` element are exported.
