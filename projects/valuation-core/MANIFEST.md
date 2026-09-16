# valuation-core

Layer 1. Depends on **core-price** (the price that goes into a valuation) and **core-fx**
(the rate that carries it into the reporting currency). Refers to nothing else.

The point of this project in the graph is the **cross-project function call**:
`VcValuation.valueInReporting()` is a derived property whose entire body is
`core_fx::convert($this.marketValue, $this.fxRate)`. Conversion arithmetic is never written
in this package — it is called from the project that owns it, so every project above this
layer inherits one rule.

Prefixes: classes `Vc*`, tables `VC_*`, joins `Vc_*`, filters `Vc*`, set ids `vc*`,
functions `vc*`.

## Exports

| element | kind | note |
| --- | --- | --- |
| `valuation_core::VcValuation` | class | one instrument's value on one cob date from one run; key `(runId, instrumentId, cobDate)`; 3 constraints; carries clean/accrued/dirty and both currencies; 6 derived properties, 4 of them calling `core_fx` |
| `valuation_core::VcUnpricedValuation` | class | subtype of `VcValuation`, `~filter VcUnpricedRows` over the same table and key; adds `unpricedReason`, `carriedForward` |
| `valuation_core::VcValuationRun` | class | one batch execution; keyed by `runId`; derived `pricedPct(): Float[1]`, `runLabel(): String[1]` |
| `valuation_core::VcAccrual` | class | the interest accrual behind `accruedInterest`; key `(instrumentId, cobDate)`; derived `accruedPerUnit(): Float[1]` |
| `valuation_core::VcValuationMethod` | class | how a value was produced (MARK/MODEL/BROKER) and the tolerance it is held to; keyed by `methodCode` |
| `valuation_core::VcFxExposure` | class | net exposure per run and currency; derived `exposureInReporting()` calls `core_fx::convertQuoted` |
| `valuation_core::VcMarkAdjustment` | class | manual adjustment stacked on a valuation; key `(runId, instrumentId, adjustmentSeq)`; derived `adjustmentInReporting()` calls `core_fx::convert` |
| `valuation_core::VcValuationBreak` | class | tolerance break; key `(runId, instrumentId, breakSeq)` |
| `valuation_core::VcPricingPolicy` | class | which vendor and method to price an asset class with; keyed by `policyCode` |
| `valuation_core::VcReportingCurrency` | class | a currency the firm reports in, with its rounding rule; keyed by the CHAR(3) code |
| `valuation_core::VcValuationSummary` | class | per-run, per-currency subtotal; key `(runId, currencyCode)`; derived `totalInReporting()` calls `core_fx::convert` |
| `valuation_core::VcPriceInput` | class | the price the run actually chose; key `(runId, instrumentId)`; derived `midPrice()` calls `core_fx::midOf` |
| `valuation_core::vcDirtyPrice` | function | `(cleanPrice: Float[1], accruedInterest: Float[1]): Float[1]` |
| `valuation_core::vcMarketValue` | function | `(quantity: Float[1], dirtyPrice: Float[1]): Float[1]` |
| `valuation_core::vcMarketValueInReporting` | function | `(quantity: Float[1], dirtyPrice: Float[1], fxRate: Float[1]): Float[1]` — wraps `core_fx::convert` |
| `valuation_core::vcValueQuoted` | function | `(quantity: Float[1], cleanPrice: Float[1], accruedInterest: Float[1], fxRate: Float[1], quotedInverted: Boolean[1]): Float[1]` — wraps `core_fx::convertQuoted` |
| `valuation_core::vcDeviationPct` | function | `(valueOne: Float[1], valueTwo: Float[1]): Float[1]` |
| `valuation_core::Store` | store | `include core_price::Store`, `include core_fx::Store`; tables VC_VALUATION, VC_VALUATION_RUN, VC_ACCRUAL, VC_VALUATION_METHOD, VC_FX_EXPOSURE, VC_MARK_ADJUSTMENT, VC_VALUATION_BREAK, VC_PRICING_POLICY, VC_REPORTING_CURRENCY, VC_VALUATION_SUMMARY, VC_PRICE_INPUT |
| `valuation_core::Mapping` | mapping | `include core_price::Mapping`, `include core_fx::Mapping`; sets `vcValuation`, `vcUnpriced`, `vcRun`, `vcAccrual`, `vcMethod`, `vcFxExposure`, `vcAdjustment`, `vcBreak`, `vcPolicy`, `vcReportingCurrency`, `vcSummary`, `vcPriceInput` |

## Set ids, for a downstream `extends` or `AssociationMapping`

Every set id is named EXPLICITLY, so the default ids (`valuation_core_VcValuation` and the
rest) **do not exist**. A downstream `extends [...]` or cross-project `AssociationMapping`
must name the id from the table above.

| class | set id |
| --- | --- |
| `VcValuation` | `vcValuation` |
| `VcUnpricedValuation` | `vcUnpriced` |
| `VcValuationRun` | `vcRun` |
| `VcAccrual` | `vcAccrual` |
| `VcValuationMethod` | `vcMethod` |
| `VcFxExposure` | `vcFxExposure` |
| `VcMarkAdjustment` | `vcAdjustment` |
| `VcValuationBreak` | `vcBreak` |
| `VcPricingPolicy` | `vcPolicy` |
| `VcReportingCurrency` | `vcReportingCurrency` |
| `VcValuationSummary` | `vcSummary` |
| `VcPriceInput` | `vcPriceInput` |

## The price stack

Three numbers, not one. A valuation that stores only "price" cannot say which it means.

| property | what it is |
| --- | --- |
| `cleanPrice` | the quoted price, EXCLUDING interest earned since the last coupon |
| `accruedInterest` | that interest, per unit — zero for equities |
| `dirtyPrice` | `clean + accrued` — what actually settles |
| `marketValue` | `quantity * dirtyPrice`, in the INSTRUMENT's currency |
| `valueInReporting()` | the same value in the REPORTING currency, via `core_fx::convert` |

`accrualCheck()` re-derives `dirtyPrice - cleanPrice`; when it does not equal
`accruedInterest`, one of the three came from a different snapshot than the other two.

## What this project calls in core-fx

| call site | core-fx function |
| --- | --- |
| `VcValuation.valueInReporting()` | `core_fx::convert` |
| `VcValuation.cleanValueInReporting()` | `core_fx::convert` |
| `VcValuation.accruedValueInReporting()` | `core_fx::convert` |
| `VcValuation.inverseFxRate()` | `core_fx::invert` |
| `VcFxExposure.exposureInReporting()` | `core_fx::convertQuoted` |
| `VcFxExposure.inverseRate()` | `core_fx::invert` |
| `VcMarkAdjustment.adjustmentInReporting()` | `core_fx::convert` |
| `VcValuationSummary.totalInReporting()` | `core_fx::convert` |
| `VcPriceInput.midPrice()` | `core_fx::midOf` |
| `valuation_core::vcMarketValueInReporting` | `core_fx::convert` |
| `valuation_core::vcValueQuoted` | `core_fx::convertQuoted` |

`VcValuation.fxRate` is stored ALREADY pointing instrument-currency → reporting-currency, so
`convert` is a plain multiply. `VcFxExposure.fxRate` is stored as the market quotes it, which
is why that class carries `quotedInverted` and uses `convertQuoted` instead.

## Joins, for a downstream store that includes this one

| join | shape |
| --- | --- |
| Vc_ValuationObservation | three columns into core-price: instrument, date and source |
| Vc_ValuationFxRate | three columns into core-fx: pair, cob date and rate type |
| Vc_ExposurePair | VC_FX_EXPOSURE.FX_PAIR_CODE = CFX_CURRENCY_PAIR.PAIR_CODE |
| Vc_ReportingCurrencyPair | VC_REPORTING_CURRENCY.BASE_PAIR_CODE = CFX_CURRENCY_PAIR.PAIR_CODE |
| Vc_PriceInputSource | VC_PRICE_INPUT.SOURCE_ID = CPR_PRICE_SOURCE.SOURCE_ID |
| Vc_PolicySource | VC_PRICING_POLICY.PREFERRED_SOURCE_ID = CPR_PRICE_SOURCE.SOURCE_ID |
| Vc_ValuationRun | VC_VALUATION.RUN_ID = VC_VALUATION_RUN.RUN_ID |
| Vc_ValuationAccrual | two columns: instrument and cob date |
| Vc_ValuationPriceInput | two columns: run and instrument |
| Vc_ValuationAdjustment | two columns: run and instrument, to-many over the sequence |
| Vc_ValuationBreak | two columns: run and instrument, to-many over the sequence |
| Vc_RunSummary | VC_VALUATION_RUN.RUN_ID = VC_VALUATION_SUMMARY.RUN_ID |
| Vc_RunReportingCurrency | VC_VALUATION_RUN.REPORTING_CCY = VC_REPORTING_CURRENCY.CURRENCY_CODE |
| Vc_PolicyMethod | VC_PRICING_POLICY.METHOD_CODE = VC_VALUATION_METHOD.METHOD_CODE |

## Filters

| filter | selects |
| --- | --- |
| VcUnpricedRows | VC_VALUATION.IS_PRICED = 0 — backs `VcUnpricedValuation` |
| VcApprovedAdjustments | VC_MARK_ADJUSTMENT.IS_APPROVED = 1 |

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(20,6) | QUANTITY, CLEAN_PRICE, ACCRUED_INTEREST, DIRTY_PRICE, MARKET_VALUE, NOTIONAL, ACCRUED_AMOUNT, EXPOSURE_AMOUNT, ADJUSTMENT_AMOUNT, DIFFERENCE_AMOUNT, TOTAL_MARKET_VALUE, PRICE_USED, BID_PRICE, ASK_PRICE |
| FLOAT | FX_RATE (on VC_VALUATION, VC_FX_EXPOSURE, VC_MARK_ADJUSTMENT, VC_VALUATION_SUMMARY), COUPON_RATE, DAY_COUNT_FRACTION, TOLERANCE_PCT, DIFFERENCE_PCT |
| SMALLINT | VALUATION_COUNT, UNPRICED_COUNT, FAIR_VALUE_LEVEL, ADJUSTMENT_SEQ, BREAK_SEQ, SEVERITY, MAX_PRICE_AGE_DAYS, ROUNDING_DECIMALS, POSITION_COUNT, PRICE_AGE_DAYS |
| CHAR(n) | INSTRUMENT_CCY / REPORTING_CCY / CURRENCY_CODE / PRICE_CURRENCY CHAR(3), DAY_COUNT_BASIS CHAR(7) |

Money is NUMERIC because it must reconcile exactly; rates and ratios are FLOAT because they
are compared against a tolerance rather than for equality. `REAL` is not used anywhere: it
parses and then cannot be read back at execution (docs/UPSTREAM_FINDINGS.md F53).

## Notes for downstream

- `VcValuation` carries three class-level constraints (`dirtyCoversClean`,
  `accruedIsNotNegative`, `fxRateIsPositive`), so `VcUnpricedValuation` inherits them.
- `VcValuation.priceObservation` reaches `core_price::CprPriceObservation` and
  `VcValuation.fxRateRow` reaches `core_fx::CfxRate`, both on the upstream's full composite
  key. Their property mappings name the upstream set ids `cprObservation` and `cfxRate`.
- No enums, no profiles, no associations, no `###Data` element and no Runtime are exported.
