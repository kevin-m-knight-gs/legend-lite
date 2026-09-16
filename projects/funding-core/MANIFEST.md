# funding-core

Layer 1. Depends on **core-account** and **core-tenor**, and on nothing else. Package root
`funding_core::`, prefix `FND` / `Fnd` / `fnd`.

Internal funds transfer pricing. Treasury publishes a funding curve -- a transfer rate per
tenor bucket -- and a business unit is charged off it for term money and credited for term
deposits. Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no
functions, no associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**MILESTONING.** `funding_core::FndCurveVersion` is `<<temporal.businesstemporal>>`, so
`all()` on it takes a date: `funding_core::FndCurveVersion.all(%2024-03-31)`,
`funding_core::FndCurveVersion.all(%latest)`. Any navigation that reaches it from your project
must carry a date too. Treasury re-cuts curves and last month's charge has to be reproducible
next year, which is the whole reason this class is versioned rather than overwritten.

`funding_core::FndCurveDefinition` is the *unversioned* half: the stable identity a curve id
names. It is an ordinary class and needs no date. If you only want to know which desk owns
`USD_TERM_1`, use the definition; if you want to know what it charged in March, use the
version.

**RANGE JOIN.** A cashflow carries `daysToMaturity` and no bucket id and no point id, because
which band it falls in is a property of the number. Two half-open range joins resolve it:
`Fnd_CashflowPoint` to this project's priced `FndCurvePoint`, and `Fnd_CashflowTenorBucket`
across the boundary to `core_tenor::CtnTenorBucket`. Both are `[0..1]` because the intervals
do not overlap.

## Elements

| element | kind | note |
| --- | --- | --- |
| funding_core::FndFundingDesk | class | treasury unit that owns a curve; key `deskId` |
| funding_core::FndBenchmarkIndex | class | index a curve is a spread to (SOFR, ESTR); key `indexCode` |
| funding_core::FndCurveDefinition | class | the curve's stable identity; key `curveId`; `desk`, `benchmark`, `points[*]` |
| funding_core::FndCurveVersion | class | **business-temporal**: `curveId`, `curveName`, `currencyCode`, `methodology`, `benchmarkCode`, `baseRateBps`, `status`, `approvedBy`. No from/thru properties -- `all(<date>)` required |
| funding_core::FndCurvePoint | class | one bucket's transfer price; key `(curveId, pointSeq)`; RANGE-join target; derived `spanDays()`, `transferRate(): Float[1]` |
| funding_core::FndLiquidityPremium | class | term liquidity add-on; key `(curveId, bucketId, fundingClass)` |
| funding_core::FndCashflow | class | the thing being priced; key `cashflowId`; carries `daysToMaturity`; `point` and `bucket` both by RANGE join |
| funding_core::FndTransferPrice | class | the applied rate and the `pricedAsOf` date; derived `allInRate(): Float[1]` |
| funding_core::FndFundingRequest | class | a unit asking treasury for term money; key `requestId` |
| funding_core::FndAccountFundingProfile | class | which curve an account prices off; key `(institutionId, accountNo)` |
| funding_core::FndCurveApproval | class | the sign-off explaining a milestone boundary; key `approvalId` |
| funding_core::FndBucketRollup | class | per-bucket funding gap rollup; reaches `bucket` by KEY, not by range |
| funding_core::Store | store | includes `core_account::Store` and `core_tenor::Store`; tables below |
| funding_core::Mapping | mapping | includes `core_account::Mapping` and `core_tenor::Mapping`; set ids below |

**Set ids**, all named explicitly, so a downstream `extends [...]` or `AssociationMapping`
must use these and not a default id: `fndDesk`, `fndBenchmark`, `fndCurve`,
`fndCurveVersion`, `fndCurvePoint`, `fndPremium`, `fndCashflow`, `fndTransferPrice`,
`fndRequest`, `fndAccountProfile`, `fndApproval`, `fndRollup`.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| FND_DESK | DESK_ID | |
| FND_BENCHMARK_INDEX | INDEX_CODE | |
| FND_CURVE | CURVE_ID | the unversioned header |
| FND_CURVE_MS | CURVE_ID, FROM_Z | **milestoned**, see below |
| FND_CURVE_POINT | CURVE_ID, POINT_SEQ | MIN_DAYS / MAX_DAYS are the range bounds |
| FND_LIQUIDITY_PREMIUM | CURVE_ID, BUCKET_ID, FUNDING_CLASS | |
| FND_CASHFLOW | CASHFLOW_ID | DAYS_TO_MATURITY and no bucket column |
| FND_TRANSFER_PRICE | PRICE_ID | PRICED_AS_OF is the date to pass to `all(...)` |
| FND_FUNDING_REQUEST | REQUEST_ID | |
| FND_ACCOUNT_PROFILE | INSTITUTION_ID, ACCOUNT_NO | one profile per account |
| FND_CURVE_APPROVAL | APPROVAL_ID | |
| FND_BUCKET_ROLLUP | ROLLUP_ID | |

### The milestoned table

    Table FND_CURVE_MS
    (
      milestoning ( business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-31) )
      CURVE_ID VARCHAR(20) PRIMARY KEY,
      FROM_Z DATE PRIMARY KEY,
      THRU_Z DATE,
      ...
    )

The milestoning block comes first, ahead of the columns. The primary key is composite
(CURVE_ID + FROM_Z) because a curve has many versions. `INFINITY_DATE` is present so `%latest`
works; without it only `%latest` fails, and it fails late, at plan generation. The mapping's
`~primaryKey` names `CURVE_ID` and `FROM_Z`. FROM_Z / THRU_Z appear in the store and the
mapping key and nowhere on the class. No join in this project targets FND_CURVE_MS -- reach
`FndCurveVersion` through `all(<date>)`, not by navigation.

### Joins

| join | condition | note |
| --- | --- | --- |
| Fnd_CashflowPoint | `FND_CASHFLOW.CURVE_ID = FND_CURVE_POINT.CURVE_ID and FND_CASHFLOW.DAYS_TO_MATURITY >= FND_CURVE_POINT.MIN_DAYS and FND_CASHFLOW.DAYS_TO_MATURITY < FND_CURVE_POINT.MAX_DAYS` | **RANGE**, both directions: `FndCashflow.point`, `FndCurvePoint.cashflows` |
| Fnd_CashflowTenorBucket | `FND_CASHFLOW.DAYS_TO_MATURITY >= CTN_BUCKET.MIN_DAYS and FND_CASHFLOW.DAYS_TO_MATURITY < CTN_BUCKET.MAX_DAYS` | **RANGE**, across the project boundary into core-tenor |
| Fnd_CurveDesk | FND_CURVE.DESK_ID = FND_DESK.DESK_ID | |
| Fnd_CurveBenchmark | FND_CURVE.INDEX_CODE = FND_BENCHMARK_INDEX.INDEX_CODE | |
| Fnd_CurvePoints | FND_CURVE.CURVE_ID = FND_CURVE_POINT.CURVE_ID | |
| Fnd_CashflowCurve | FND_CASHFLOW.CURVE_ID = FND_CURVE.CURVE_ID | |
| Fnd_PriceCashflow | FND_TRANSFER_PRICE.CASHFLOW_ID = FND_CASHFLOW.CASHFLOW_ID | |
| Fnd_RequestDesk | FND_FUNDING_REQUEST.DESK_ID = FND_DESK.DESK_ID | |
| Fnd_ProfileCurve | FND_ACCOUNT_PROFILE.CURVE_ID = FND_CURVE.CURVE_ID | |
| Fnd_ApprovalCurve | FND_CURVE_APPROVAL.CURVE_ID = FND_CURVE.CURVE_ID | |
| Fnd_ApprovalDesk | FND_CURVE_APPROVAL.DESK_ID = FND_DESK.DESK_ID | |
| Fnd_RollupCurve | FND_BUCKET_ROLLUP.CURVE_ID = FND_CURVE.CURVE_ID | |
| Fnd_PointTenorBucket | FND_CURVE_POINT.BUCKET_ID = CTN_BUCKET.BUCKET_ID | keyed route to core-tenor |
| Fnd_PremiumTenorBucket | FND_LIQUIDITY_PREMIUM.BUCKET_ID = CTN_BUCKET.BUCKET_ID | keyed route to core-tenor |
| Fnd_RollupTenorBucket | FND_BUCKET_ROLLUP.BUCKET_ID = CTN_BUCKET.BUCKET_ID | keyed route to core-tenor |
| Fnd_CashflowAccount | INSTITUTION_ID **and** ACCOUNT_NO against CA_ACCOUNT | two columns, per core-account |
| Fnd_RequestAccount | INSTITUTION_ID **and** ACCOUNT_NO against CA_ACCOUNT | two columns, per core-account |
| Fnd_ProfileAccount | INSTITUTION_ID **and** ACCOUNT_NO against CA_ACCOUNT | two columns, per core-account |

## For downstream projects

`include funding_core::Store` and `include funding_core::Mapping` -- both transitively bring
`core_account::` and `core_tenor::` with them, so do not include those a second time. To band
your own amounts, declare your own range join against `FND_CURVE_POINT.MIN_DAYS` /
`MAX_DAYS`; there is no key on a point you can join to instead.

## Verified

    python3 scripts/projects/check.py funding-core
    compiles  funding-core (+2 deps)
