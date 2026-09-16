# fee-core

Layer 1. Depends on `core-types` and `core-tenor`, and on nothing else. Package root
`fee_core::`, prefix `FEE` / `Fee` / `fee`.

Fee schedules banded by tenor. A fee rate is not stored against a band — it is stored against
a number of days, and which band that is falls out of a RANGE JOIN whose far side,
`CTN_BUCKET`, belongs to core-tenor. `fee_core::Store` `include`s `core_tenor::Store` and
declares the join over one local table and one foreign one; `fee_core::Mapping` `include`s
`core_tenor::Mapping` so the property can land on core-tenor's explicit set id `ctnBucket`.

| element | kind | note |
| --- | --- | --- |
| fee_core::FeeProduct | class | A billable product: `productCode`, `name`, `assetClass`, `billingCurrency: core_types::CtCurrency[1]`, `isActive`, `schedules[*]` |
| fee_core::FeeTier | class | A client tier: `tierCode`, `name`, `rank`, `minNotional`, `schedules[*]` |
| fee_core::FeeSchedule | class | One banded rate. COMPOSITE KEY `(productCode, tierCode, effectiveDate)`; `daysToMaturity`, `rateBasisPoints`, `minimumFee`, `maximumFee[0..1]`, `feeCurrency`; `product[0..1]`, `tier[0..1]`, `waivers[*]`, `bucket: core_tenor::CtnTenorBucket[0..1]` via the range join; derived `rate(): Float[1]` and `grossFee(Float[1]): Float[1]` |
| fee_core::FeeWaiver | class | A fee taken off: `waiverId`, `reasonCode`, `waivedBasisPoints`, `isFullWaiver`, `grantedOn`, `expiresOn[0..1]`, `schedule[0..1]` |
| fee_core::Store | store | Tables FEE_PRODUCT, FEE_TIER, FEE_SCHEDULE, FEE_WAIVER; includes `core_tenor::Store` |
| fee_core::Mapping | mapping | Set ids feeProduct, feeTier, feeSchedule, feeWaiver; enumeration mapping FeeCurrencyMapping; includes `core_tenor::Mapping` |

## Set ids

All four are explicit, so a downstream `extends [...]` or cross-project `AssociationMapping`
must name them — the default ids (`fee_core_FeeSchedule` and friends) do not exist.

    feeProduct   feeTier   feeSchedule   feeWaiver

`FeeCurrencyMapping` maps `core_types::CtCurrency` from ISO alpha-3 strings, and covers eight
billing currencies only: USD, EUR, GBP, CHF, JPY, SGD, HKD, AUD.

## Joins in `fee_core::Store`

| join | condition | note |
| --- | --- | --- |
| Fee_ScheduleBucket | `FEE_SCHEDULE.DAYS_TO_MATURITY >= CTN_BUCKET.MIN_DAYS and FEE_SCHEDULE.DAYS_TO_MATURITY < CTN_BUCKET.MAX_DAYS` | CROSS-PROJECT RANGE join. `CTN_BUCKET` is core-tenor's table, visible only because of `include core_tenor::Store`. Half-open, so a schedule matches exactly one bucket |
| Fee_ScheduleProduct | `FEE_SCHEDULE.PRODUCT_CODE = FEE_PRODUCT.PRODUCT_CODE` | ordinary key join, traversed both ways |
| Fee_ScheduleTier | `FEE_SCHEDULE.TIER_CODE = FEE_TIER.TIER_CODE` | ordinary key join, traversed both ways |
| Fee_WaiverSchedule | `FEE_WAIVER.PRODUCT_CODE = FEE_SCHEDULE.PRODUCT_CODE and FEE_WAIVER.TIER_CODE = FEE_SCHEDULE.TIER_CODE and FEE_WAIVER.EFFECTIVE_DATE = FEE_SCHEDULE.EFFECTIVE_DATE` | three-column join, because the schedule's key is composite |

## For downstream projects

- `include fee_core::Store` and `include fee_core::Mapping`. Including this store transitively
  brings in `core_tenor::Store`; do not include core-tenor's store a second time alongside it.
- `FeeSchedule.bucket` is `[0..1]` and reaches core-tenor's `CtnTenorBucket`, so a fee row can
  be grouped by `bucket.band` without fee-core ever storing a band.
- Anything reaching `FeeSchedule` by key must supply all three key columns; one or two of them
  does not identify a row.
- `rate()` and `grossFee()` are derived and are not in the mapping — they call
  `core_types::ctBasisPointsToRate`, so a downstream project gets them for free but inherits
  core-types as a transitive dependency of the expression.

No `###Data` element and no Runtime: the tables are declared and unseeded.
