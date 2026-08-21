# product-pricing

Layer 2. Depends on **product-core** and **valuation-core**, and nothing else.

How each kind of product is turned into a number. The thesis is that **the product type
picks the model**, and the model picks everything after it — the inputs it needs, the
calibration behind those inputs, the greeks it can produce, and the reserve held because it
is only a model:

| product | model | what makes it different |
| --- | --- | --- |
| vanilla, linear payoff | closed form | one evaluation, exact greeks |
| path dependent (autocall, barrier) | Monte Carlo | a path count, a seed, a standard error |
| early exercise (American, Bermudan) | lattice | a step count, and backward induction |
| deposits and financing | accrual and discounting | no market model at all |
| funds | the administrator's NAV | not a model, a report |
| everything nobody trusts | a broker mark | a reserve big enough to admit it |

30 classes, 19 tables, 30 mapping sets, 14 exported functions. No enums, no profiles, no
associations, no `###Data` element and no Runtime.

Prefixes: classes `Ppr*`, tables `PPR_*`, joins `Ppr_*`, filters `Ppr*`, set ids `ppr*`,
functions `ppr*`.

## Exports

| element | kind | note |
| --- | --- | --- |
| `product_pricing::PprPricedProduct` | class | ROOT of the pricing overlay; **extends `product_core::Product`**. Model, desk, pricing currency, tier, validation flag, standing reserve. 3 derived properties |
| `product_pricing::PprAnalyticPricedProduct` | class | extends PprPricedProduct; closed form, smile flag, quoted vol, analytic greeks |
| `product_pricing::PprMonteCarloPricedProduct` | class | extends PprPricedProduct; paths, time steps, seed, variance reduction; derived `standardError()`, `totalTimeSteps()` |
| `product_pricing::PprLatticePricedProduct` | class | extends PprPricedProduct; lattice type, steps, exercise style; derived `stepSizeYears()` |
| `product_pricing::PprAccrualPricedProduct` | class | extends PprPricedProduct; accrual basis, contract rate, fair-value option, funding spread |
| `product_pricing::PprNavPricedProduct` | class | extends PprPricedProduct; NAV source, lag days, swing factor, estimated-NAV flag |
| `product_pricing::PprBrokerMarkedProduct` | class | extends PprPricedProduct; broker count, dispersion, uncertainty reserve; derived `totalReserveRate()` |
| `product_pricing::PprPricedAutocall` | class | **extends `product_core::YieldEnhancementProduct`**; observation schedule, memory coupon, autocall probability, expected life |
| `product_pricing::PprPricedProtectedNote` | class | **extends `product_core::CapitalProtectedNote`**; zero-leg and option-leg PVs, issuer funding spread; derived `decompositionCheckPercent()` |
| `product_pricing::PprPricingModel` | class | ROOT of the model inventory; engine, version, quant team, approval; reaches `valuation_core::VcValuationMethod` |
| `product_pricing::PprAnalyticModel` | class | extends PprPricingModel; formula name, closed-form greeks, smile treatment |
| `product_pricing::PprMonteCarloModel` | class | extends PprPricingModel; default paths, antithetic, quasi-random, Brownian bridge |
| `product_pricing::PprLatticeModel` | class | extends PprPricingModel; lattice type, default steps, American/Bermudan, dividend treatment |
| `product_pricing::PprModelAssignment` | class | which model prices which product subtype; key (productSubtype, modelCode) |
| `product_pricing::PprModelInput` | class | one named input a model will not run without; key (modelCode, inputCode) |
| `product_pricing::PprVolatilitySurface` | class | an implied vol surface; keyed by `surfaceCode`; arbitrage-free flag, ATM vol, 25d skew |
| `product_pricing::PprVolatilityPoint` | class | one quoted point on it; key (surfaceCode, expiryTenor, strikeBucket); derived `volatilitySpread()` |
| `product_pricing::PprDiscountCurve` | class | the curve everything discounts on; keyed by `curveCode`; collateralised flag |
| `product_pricing::PprCurvePoint` | class | one pillar; key (curveCode, tenorCode); derived `tenorYears()` |
| `product_pricing::PprCorrelationEntry` | class | one correlation; key (matrixCode, underlyingOne, underlyingTwo); stressed flag |
| `product_pricing::PprDividendForecast` | class | a forecast dividend; key (underlyingCode, exDate) |
| `product_pricing::PprCalibration` | class | one fit of one model on one date; key (modelCode, calibrationDate); derived `errorPerTarget()` |
| `product_pricing::PprCalibrationTarget` | class | one instrument in the basket; key (modelCode, calibrationDate, targetSeq); derived `quoteError()`, `quoteErrorPct()` (calls `valuation_core::vcDeviationPct`) |
| `product_pricing::PprPricingRun` | class | one pricing batch; keyed by `runId`; reaches `valuation_core::VcValuationRun`; derived `pricedPct()` |
| `product_pricing::PprProductPrice` | class | THE OUTPUT: one model price beside its market price; key (runId, productCode); 2 constraints; 5 derived properties, 4 of them calling valuation-core; reaches `valuation_core::VcValuation` |
| `product_pricing::PprGreekProfile` | class | the standard first-order block; key (runId, productCode); derived `deltaCash()`, `gammaCash()`, `vegaCash()`, `thetaPerDay()` |
| `product_pricing::PprGreekObservation` | class | one greek outside that block (vanna, volga, cross-gamma); key (runId, productCode, greekCode) |
| `product_pricing::PprModelReserve` | class | a reserve held against a price; key (runId, productCode, reserveType); reaches `valuation_core::VcMarkAdjustment` |
| `product_pricing::PprModelValidation` | class | one validation review; key (modelCode, reviewDate) |
| `product_pricing::PprModelLimitation` | class | a known limitation of a model; key (modelCode, limitationCode) |
| `product_pricing::pprDirtyModelPrice` | function | `(cleanModelPrice: Float[1], accruedInterest: Float[1]): Float[1]` — wraps `valuation_core::vcDirtyPrice` |
| `product_pricing::pprModelValue` | function | `(quantity: Float[1], dirtyModelPrice: Float[1]): Float[1]` — wraps `valuation_core::vcMarketValue` |
| `product_pricing::pprModelValueInReporting` | function | `(quantity: Float[1], dirtyModelPrice: Float[1], fxRate: Float[1]): Float[1]` — wraps `valuation_core::vcMarketValueInReporting` |
| `product_pricing::pprModelVsMarketPct` | function | `(marketPrice: Float[1], modelPrice: Float[1]): Float[1]` — wraps `valuation_core::vcDeviationPct` |
| `product_pricing::pprIsWithinTolerance` | function | `(deviationPct: Float[1], tolerancePct: Float[1]): Boolean[1]` — unsigned test on a signed deviation |
| `product_pricing::pprReserveAmount` | function | `(baseValue: Float[1], reserveBasisPoints: Float[1]): Float[1]` |
| `product_pricing::pprNetOfReserve` | function | `(baseValue: Float[1], reserveAmount: Float[1]): Float[1]` |
| `product_pricing::pprDeltaCash` | function | `(delta: Float[1], spotLevel: Float[1], quantity: Float[1]): Float[1]` |
| `product_pricing::pprGammaCash` | function | `(gamma: Float[1], spotLevel: Float[1], quantity: Float[1]): Float[1]` — includes the Taylor half |
| `product_pricing::pprVegaForShift` | function | `(vega: Float[1], volShiftPoints: Float[1]): Float[1]` |
| `product_pricing::pprThetaPerDay` | function | `(thetaPerYear: Float[1]): Float[1]` — 365, because carry accrues on weekends |
| `product_pricing::pprLatticeStepSize` | function | `(yearsToExpiry: Float[1], stepCount: Integer[1]): Float[1]` |
| `product_pricing::pprMonteCarloStandardError` | function | `(pathStandardDeviation: Float[1], pathCount: Integer[1]): Float[1]` — uses `sqrt` |
| `product_pricing::pprHasConverged` | function | `(standardError: Float[1], targetBasisPoints: Float[1]): Boolean[1]` |
| `product_pricing::Store` | store | `include product_core::Store`, `include valuation_core::Store`; 19 tables, 23 joins, 12 filters |
| `product_pricing::Mapping` | mapping | `include product_core::Mapping`, `include valuation_core::Mapping`; 30 sets, 9 of them `extends` |

## Inheritance, and the set ids it extends

The class hierarchy is rooted in the DEPENDENCY's taxonomy rather than in a new one, and the
mapping hierarchy is the same shape. Three classes extend a `product_core` class:

| class | extends class | set id | extends set id |
| --- | --- | --- | --- |
| `PprPricedProduct` | `product_core::Product` | `pprPricedProduct` | **`[prdProduct]`** |
| `PprPricedAutocall` | `product_core::YieldEnhancementProduct` | `pprAutocall` | **`[prdYieldEnhancement]`** |
| `PprPricedProtectedNote` | `product_core::CapitalProtectedNote` | `pprProtectedNote` | **`[prdCapitalProtected]`** |

So the extension chain for a model-family set is four links long and crosses two project
boundaries:

    pprAnalytic -> pprPricedProduct -> prdProduct -> ciBase
                   (product-pricing)  (product-core) (core-instrument)

## Set ids (this is a GLOBAL namespace; extend these)

Every set id is named EXPLICITLY, so the default ids (`product_pricing_PprPricedProduct` and
the rest) **do not exist**. A downstream `extends [...]` or cross-project `AssociationMapping`
must name an id from this table.

| class | set id | extends |
| --- | --- | --- |
| `PprPricedProduct` | `pprPricedProduct` | `[prdProduct]` |
| `PprAnalyticPricedProduct` | `pprAnalytic` | `[pprPricedProduct]` |
| `PprMonteCarloPricedProduct` | `pprMonteCarlo` | `[pprPricedProduct]` |
| `PprLatticePricedProduct` | `pprLattice` | `[pprPricedProduct]` |
| `PprAccrualPricedProduct` | `pprAccrual` | `[pprPricedProduct]` |
| `PprNavPricedProduct` | `pprNav` | `[pprPricedProduct]` |
| `PprBrokerMarkedProduct` | `pprBrokerMarked` | `[pprPricedProduct]` |
| `PprPricedAutocall` | `pprAutocall` | `[prdYieldEnhancement]` |
| `PprPricedProtectedNote` | `pprProtectedNote` | `[prdCapitalProtected]` |
| `PprPricingModel` | `pprModel` | — |
| `PprAnalyticModel` | `pprAnalyticModel` | `[pprModel]` |
| `PprMonteCarloModel` | `pprMonteCarloModel` | `[pprModel]` |
| `PprLatticeModel` | `pprLatticeModel` | `[pprModel]` |
| `PprModelAssignment` | `pprAssignment` | — |
| `PprModelInput` | `pprModelInput` | — |
| `PprVolatilitySurface` | `pprVolSurface` | — |
| `PprVolatilityPoint` | `pprVolPoint` | — |
| `PprDiscountCurve` | `pprCurve` | — |
| `PprCurvePoint` | `pprCurvePoint` | — |
| `PprCorrelationEntry` | `pprCorrelation` | — |
| `PprDividendForecast` | `pprDividend` | — |
| `PprCalibration` | `pprCalibration` | — |
| `PprCalibrationTarget` | `pprCalibrationTarget` | — |
| `PprPricingRun` | `pprPricingRun` | — |
| `PprProductPrice` | `pprProductPrice` | — |
| `PprGreekProfile` | `pprGreekProfile` | — |
| `PprGreekObservation` | `pprGreek` | — |
| `PprModelReserve` | `pprReserve` | — |
| `PprModelValidation` | `pprValidation` | — |
| `PprModelLimitation` | `pprLimitation` | — |

## Store names

Tables `PPR_PRODUCT_PRICING` (the pricing overlay, keyed by `INSTRUMENT_ID`), `PPR_MODEL`,
`PPR_MODEL_ASSIGNMENT`, `PPR_MODEL_INPUT`, `PPR_VOL_SURFACE`, `PPR_VOL_POINT`,
`PPR_DISCOUNT_CURVE`, `PPR_CURVE_POINT`, `PPR_CORRELATION`, `PPR_DIVIDEND`,
`PPR_CALIBRATION`, `PPR_CALIBRATION_TARGET`, `PPR_PRICING_RUN`, `PPR_PRODUCT_PRICE`,
`PPR_GREEK_PROFILE`, `PPR_GREEK`, `PPR_MODEL_RESERVE`, `PPR_MODEL_VALIDATION`,
`PPR_MODEL_LIMITATION`.

### Joins, for a downstream store that includes this one

| join | shape |
| --- | --- |
| Ppr_PricingInstrument | `CI_INSTRUMENT.INSTRUMENT_ID = PPR_PRODUCT_PRICING.INSTRUMENT_ID` — the overlay join |
| Ppr_PricingModel | PPR_PRODUCT_PRICING.MODEL_CODE = PPR_MODEL.MODEL_CODE |
| Ppr_PricingCurve | PPR_PRODUCT_PRICING.DISCOUNT_CURVE_CODE = PPR_DISCOUNT_CURVE.CURVE_CODE |
| Ppr_PricingProductPrice | PPR_PRODUCT_PRICING.PRODUCT_CODE = PPR_PRODUCT_PRICE.PRODUCT_CODE |
| Ppr_ModelValuationMethod | into valuation-core: PPR_MODEL.VALUATION_METHOD_CODE = VC_VALUATION_METHOD.METHOD_CODE |
| Ppr_RunValuationRun | into valuation-core: PPR_PRICING_RUN.VALUATION_RUN_ID = VC_VALUATION_RUN.RUN_ID |
| Ppr_PriceValuation | into valuation-core, three columns: valuation run, instrument, cob date |
| Ppr_ReserveAdjustment | into valuation-core, three columns: valuation run, instrument, adjustment seq |
| Ppr_ModelInput, Ppr_ModelAssignment, Ppr_ModelCalibration, Ppr_ModelValidation, Ppr_ModelLimitation | PPR_MODEL.MODEL_CODE to each satellite |
| Ppr_CalibrationTarget | two columns: model and calibration date |
| Ppr_CalibrationSurface, Ppr_CalibrationCurve | PPR_CALIBRATION to PPR_VOL_SURFACE / PPR_DISCOUNT_CURVE |
| Ppr_SurfacePoint, Ppr_CurveNode | a surface / curve to its points |
| Ppr_RunPrice | PPR_PRICING_RUN.RUN_ID = PPR_PRODUCT_PRICE.RUN_ID |
| Ppr_PriceModel | PPR_PRODUCT_PRICE.MODEL_CODE = PPR_MODEL.MODEL_CODE |
| Ppr_PriceGreekProfile, Ppr_PriceGreek, Ppr_PriceReserve | two columns: run and product code |

### Filters

The discriminator is this project's OWN columns, not core-instrument's `INSTRUMENT_SUBTYPE`.
How a product is priced is a decision the pricing desk made, and it can change without
touching a row reference data owns — which also means **this project takes no new
`INSTRUMENT_TYPE` or `INSTRUMENT_SUBTYPE` values and cannot collide with product-core's**.

| filter | selects |
| --- | --- |
| PprPricedRows | `PPR_PRODUCT_PRICING.PRICING_STATUS = 'IN_MODEL'` — backs `[pprPricedProduct]` |
| PprAnalyticRows / PprMonteCarloRows / PprLatticeRows / PprAccrualRows / PprNavRows / PprBrokerMarkedRows | `MODEL_FAMILY` = `ANALYTIC` / `MONTE_CARLO` / `LATTICE` / `ACCRUAL` / `NAV` / `BROKER` |
| PprAutocallPricedRows / PprProtectedNotePricedRows | `PAYOFF_ENGINE` = `AUTOCALL` / `ZERO_PLUS_OPTION` — a separate column, because those two sets are on a different branch of the taxonomy and must not be cut by `MODEL_FAMILY` as well |
| PprAnalyticModelRows / PprMonteCarloModelRows / PprLatticeModelRows | `PPR_MODEL.MODEL_KIND` = `ANALYTIC` / `MONTE_CARLO` / `LATTICE` |

## What this project calls in valuation-core

The model price and the market price must travel through the same arithmetic or the
deviation between them measures the arithmetic and not the model. So none of it is written
here; it is called from the project that owns it, which in turn calls core-fx.

| call site | valuation-core function |
| --- | --- |
| `PprProductPrice.dirtyModelPrice()` | `vcDirtyPrice` |
| `PprProductPrice.modelValue()` | `vcMarketValue`, `vcDirtyPrice` |
| `PprProductPrice.modelValueInReporting()` | `vcMarketValueInReporting`, `vcDirtyPrice` (→ `core_fx::convert`) |
| `PprProductPrice.modelVsMarketPct()` | `vcDeviationPct` |
| `PprProductPrice.isWithinTolerance()` | `vcDeviationPct` |
| `PprCalibrationTarget.quoteErrorPct()` | `vcDeviationPct` |
| `product_pricing::pprDirtyModelPrice` | `vcDirtyPrice` |
| `product_pricing::pprModelValue` | `vcMarketValue` |
| `product_pricing::pprModelValueInReporting` | `vcMarketValueInReporting` |
| `product_pricing::pprModelVsMarketPct` | `vcDeviationPct` |

## Datatypes, by column

| type | columns |
| --- | --- |
| NUMERIC(20,6) | CLEAN_MODEL_PRICE, ACCRUED_INTEREST, QUANTITY, MARKET_PRICE, RESERVE_AMOUNT, BASE_VALUE — money and prices, which must reconcile against valuation-core's exactly |
| FLOAT | FX_RATE, TOLERANCE_PCT — the two that land on a `Float[1]` argument of a valuation-core or core-fx function |
| DOUBLE | volatilities, greeks, correlations, discount factors, basis-point rates, probabilities — compared against a tolerance, never for equality |
| SMALLINT | tiers, severities, sequences, counts bounded by construction |
| INTEGER | PATH_COUNT, TIME_STEPS_PER_YEAR, RANDOM_SEED, TREE_STEPS, TENOR_DAYS — counts that are not bounded by construction |
| BIT | every flag; a `Filter` never tests one against a boolean literal |

`REAL` is not used anywhere: it parses and then cannot be read back at execution
(docs/UPSTREAM_FINDINGS.md F53).

## Notes for downstream projects

- **The main table of every priced-product set is core-instrument's `CI_INSTRUMENT`**, two
  projects up — not `PRD_PRODUCT` and not `PPR_PRODUCT_PRICING`. This project's columns are
  an overlay reached through `Ppr_PricingInstrument`, so a property mapping added downstream
  must be written `[product_pricing::Store]@Ppr_PricingInstrument | PPR_PRODUCT_PRICING.COL`,
  and an association from one of these sets is a join CHAIN:
  `[db]@Ppr_PricingInstrument > @YourJoin`.
- Every `~filter` on a priced-product set uses the join form
  `~filter [db]@Ppr_PricingInstrument | [db]FilterName`. Both database pointers are required;
  `~filter [db]@Join | FilterName` is a parse error.
- A property declared on a SUBCLASS is mapped on its OWN set, never on the parent's — which
  is why `PprAnalyticPricedProduct`'s five columns are on `[pprAnalytic]` and not on
  `[pprPricedProduct]`. Follow the same rule when extending these.
- To add a pricing technique: a class extending `product_pricing::PprPricedProduct`, a
  `Filter` in your own store on `PPR_PRODUCT_PRICING.MODEL_FAMILY` with a value not listed
  above, and a set `extends [pprPricedProduct]` in your own mapping.
- `PprProductPrice` carries two class-level constraints (`toleranceIsPositive`,
  `fxRateIsPositive`).
- `PprMonteCarloPricedProduct.standardError()` and several other derived properties call
  `->toOne()` on an optional column, so they are compile-time conveniences and not
  null-safe accessors — the same caveat product-core recorded for
  `Product.ongoingChargeRate()`.
- No enums, no profiles, no associations, no `###Data` element and no Runtime are exported.
  core-types is NOT a dependency of this project, so its enums and its `CtGovernance`
  profile are deliberately absent even though product-core uses them.
