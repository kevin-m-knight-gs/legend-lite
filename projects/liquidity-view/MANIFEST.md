# liquidity-view

Layer 2. Depends on **cash-core** and **funding-core**, and on nothing else. Package root
`liquidity_view::`, prefix `LQV` / `Lqv` / `lqv`.

What a treasurer reads in the morning: cash flows laddered by how many days out they fall,
the liquidity coverage ratio over its thirty-day stress window, the stock of high-quality
liquid assets by level after haircut, and the net stable funding ratio's one-year view.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**The store VIEW.** `LQV_BUCKET_FLOW` is a `~groupBy` over `LQV_FLOW`: one row per ladder,
bucket, currency and as-of date, with `sum(GROSS_AMOUNT)`, `sum(OUTFLOW_AMOUNT)` and
`count(FLOW_ID)`. `liquidity_view::LqvBucketFlowTotal` is **rooted at it** — a query naming
that class reads the GROUP BY directly, one row per band instead of one per flow. There is no
`AggregationAware` set here; the view is used as a table, not as an index.

`~groupBy` takes **column references**, not expressions. That is why `LQV_FLOW` carries a
stamped `BUCKET_ID` *and* a raw `DAYS_OUT`: the view groups on the column, and the range join
re-derives the band from the number. The two are not redundant — `BUCKET_ID` is the band that
was filed, `DAYS_OUT` is what lets the same flow be re-banded on a different ladder.

A band with no flows on a date **forms no group and is absent**, not zero. Anything
downstream that must show every band on every day drives from `LQV_BUCKET` and outer-joins;
it does not drive from `LqvBucketFlowTotal`.

**Two RANGE JOINS**, both half-open on `[MIN_DAYS, MAX_DAYS)`, both `[0..1]` because the
intervals do not overlap:

* `Lqv_FlowBucket` — within this project, `LQV_FLOW.DAYS_OUT` into `LQV_BUCKET`. Carries
  `LADDER_ID =` alongside the range, so a flow lands in one band on one ladder rather than in
  one band on every ladder.
* `Lqv_FlowCurvePoint` — **across the boundary** into funding-core's `FND_CURVE_POINT`,
  carrying `CURVE_ID =` alongside the range. funding-core's MANIFEST says a point has no key
  to join to instead, so the range is the only route.

## What this project does NOT do

Nothing here navigates to `funding_core::FndCurveVersion`. It is
`<<temporal.businesstemporal>>` and no join in either project targets `FND_CURVE_MS`. Reach a
version with `funding_core::FndCurveVersion.all(%2024-03-31)`, passing the `asOfDate` these
classes carry; `LqvNsfrLine.fundingCurve` reaches the **unversioned** `FndCurveDefinition`
instead. Reproducing last quarter's ratio means calling `all()` with that quarter's date.

Currencies are ISO alpha-3 **Strings** here, not `core_types::CtCurrency`. cash-core maps
that enum and it is visible transitively, but core-types is not a declared dependency of this
project and naming it would be the undeclared-dependency defect the graph exists to detect.
A downstream project that wants the enum declares its own `EnumerationMapping` over these
columns.

## Elements

| element | kind | note |
| --- | --- | --- |
| liquidity_view::LqvLiquidityLadder | class | a named set of bands — regulatory, internal, agency; key `ladderId`; derived `isLive()` |
| liquidity_view::LqvLiquidityBucket | class | one band; **composite key** `(ladderId, bucketId)`; RANGE-join target on `minDays`/`maxDays`; derived `spanDays()`, `isWithinStressWindow()` |
| liquidity_view::LqvProjectedFlow | class | the central fact: one flow with `daysOut` and a stamped `bucketId`; key `flowId`; `band` and `curvePoint` both by RANGE; derived `isOutflow()`, `signedAmount(): Float[1]`, `isWithinStressWindow()`, `isBeyondOneYear()` |
| liquidity_view::LqvBucketFlowTotal | class | **rooted at the store VIEW**; key `(ladderId, bucketId, currencyCode, asOfDate)`; `netAmount`, `grossOutflow`, `flowCount`; derived `averageFlow(): Float[1]` |
| liquidity_view::LqvHqlaLevel | class | L1 / L2A / L2B with the standard haircut and the cap; key `levelCode`; derived `retainedPct(): Float[1]`, `isLevelOne()` |
| liquidity_view::LqvHqlaHolding | class | one holding in the stock; key `holdingId`; carries the haircut ACTUALLY applied; derived `unencumberedValue(): Float[1]`, `weightedValue(): Float[1]` |
| liquidity_view::LqvDepositType | class | what kind of deposit a balance is, which is what decides how fast it runs; key `depositType` |
| liquidity_view::LqvOutflowRate | class | run-off rate per deposit type per scenario; **composite key** `(scenarioId, depositType)`; derived `outflowFactor(): Float[1]` |
| liquidity_view::LqvStressScenario | class | the named stress and its horizon; key `scenarioId`; derived `isThirtyDay()`, `isLive()` |
| liquidity_view::LqvDepositBalance | class | a balance classified for run-off; key `balanceId`; same grain as cash-core's `CshCashBalance` and joined to it on four columns; derived `atRiskAmount(): Float[1]`, `stressedOutflow(): Float[1]` |
| liquidity_view::LqvLcrResult | class | the LCR; **composite key** `(institutionId, currencyCode, asOfDate, scenarioId)`; derived `netOutflows(): Float[1]`, `coverageRatioPct(): Float[1]`, `isCompliant()`, `inflowCapApplied()` |
| liquidity_view::LqvNsfrLine | class | one line of the one-year view, ASF or RSF side; key `lineId`; derived `weightedAmount(): Float[1]`, `isBeyondOneYear()`, `isAvailableSide()` |
| liquidity_view::LqvNsfrResult | class | the NSFR; **composite key** `(institutionId, currencyCode, asOfDate)`; derived `ratioPct(): Float[1]`, `isCompliant()`, `surplus(): Float[1]` |
| liquidity_view::LqvFundingConcentration | class | one counterparty's share of one band; **composite key** `(asOfDate, institutionId, counterpartyId, ladderId, bucketId)`; derived `isReportable()` |
| liquidity_view::Store | store | `include cash_core::Store` and `include funding_core::Store`; tables and the view below |
| liquidity_view::Mapping | mapping | `include cash_core::Mapping` and `include funding_core::Mapping`; set ids below |

**Set ids**, all named explicitly, so a downstream `extends [...]` or `AssociationMapping`
must use these and not a default id: `lqvLadder`, `lqvBucket`, `lqvFlow`, `lqvBucketFlow`,
`lqvHqlaLevel`, `lqvHolding`, `lqvDepositType`, `lqvOutflowRate`, `lqvScenario`,
`lqvDepositBalance`, `lqvLcrResult`, `lqvNsfrLine`, `lqvNsfrResult`, `lqvConcentration`.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| LQV_LADDER | LADDER_ID | RETIRED_ON null means still in use |
| LQV_BUCKET | LADDER_ID, BUCKET_ID | MIN_DAYS / MAX_DAYS are the range bounds; the same bucket id exists on more than one ladder |
| LQV_FLOW | FLOW_ID | DAYS_OUT for the ranges, BUCKET_ID for the view; INSTITUTION_ID + ACCOUNT_NO because core-account keys an account by both |
| **LQV_BUCKET_FLOW** | LADDER_ID, BUCKET_ID, CURRENCY_CODE, AS_OF_DATE | **VIEW**, `~groupBy` over LQV_FLOW |
| LQV_HQLA_LEVEL | LEVEL_CODE | STANDARD_HAIRCUT_PCT and CAP_PCT |
| LQV_HQLA_HOLDING | HOLDING_ID | APPLIED_HAIRCUT_PCT is stamped, not read off the level |
| LQV_STRESS_SCENARIO | SCENARIO_ID | HORIZON_DAYS is 30 for the LCR; WITHDRAWN_ON null means live |
| LQV_DEPOSIT_TYPE | DEPOSIT_TYPE | |
| LQV_OUTFLOW_RATE | SCENARIO_ID, DEPOSIT_TYPE | the rate is not a property of the type alone |
| LQV_DEPOSIT_BALANCE | BALANCE_ID | deliberately the grain of CSH_CASH_BALANCE |
| LQV_LCR_RESULT | INSTITUTION_ID, CURRENCY_CODE, AS_OF_DATE, SCENARIO_ID | GROSS_INFLOWS and CAPPED_INFLOWS both stored so the 75% cap is visible |
| LQV_NSFR_LINE | LINE_ID | SIDE is 'ASF' or 'RSF'; RESIDUAL_DAYS against the 365-day boundary |
| LQV_NSFR_RESULT | INSTITUTION_ID, CURRENCY_CODE, AS_OF_DATE | |
| LQV_FUNDING_CONCENTRATION | AS_OF_DATE, INSTITUTION_ID, COUNTERPARTY_ID, LADDER_ID, BUCKET_ID | |

### The view

    View LQV_BUCKET_FLOW
    (
      ~groupBy
      (
        LQV_FLOW.LADDER_ID,
        LQV_FLOW.BUCKET_ID,
        LQV_FLOW.CURRENCY_CODE,
        LQV_FLOW.AS_OF_DATE
      )
      LADDER_ID: LQV_FLOW.LADDER_ID PRIMARY KEY,
      ...
      NET_AMOUNT: sum(LQV_FLOW.GROSS_AMOUNT),
      FLOW_COUNT: count(LQV_FLOW.FLOW_ID)
    )

`~groupBy` comes first, before the columns, and names columns only. Every grouping column is
repeated as a `PRIMARY KEY` output column, because the mapping's `~primaryKey` has to name the
grain the view produces.

### Joins

| join | condition | note |
| --- | --- | --- |
| Lqv_FlowBucket | `LQV_FLOW.LADDER_ID = LQV_BUCKET.LADDER_ID and LQV_FLOW.DAYS_OUT >= LQV_BUCKET.MIN_DAYS and LQV_FLOW.DAYS_OUT < LQV_BUCKET.MAX_DAYS` | **RANGE**, both directions: `LqvProjectedFlow.band`, `LqvLiquidityBucket.flows` |
| Lqv_FlowCurvePoint | `LQV_FLOW.CURVE_ID = FND_CURVE_POINT.CURVE_ID and LQV_FLOW.DAYS_OUT >= FND_CURVE_POINT.MIN_DAYS and LQV_FLOW.DAYS_OUT < FND_CURVE_POINT.MAX_DAYS` | **RANGE**, across the boundary into funding-core |
| Lqv_BucketTotals | LQV_BUCKET_FLOW.LADDER_ID and BUCKET_ID to LQV_BUCKET | makes the VIEW reachable by navigation |
| Lqv_LadderBuckets | LQV_BUCKET.LADDER_ID = LQV_LADDER.LADDER_ID | |
| Lqv_HoldingLevel | LQV_HQLA_HOLDING.LEVEL_CODE = LQV_HQLA_LEVEL.LEVEL_CODE | |
| Lqv_RateScenario | LQV_OUTFLOW_RATE.SCENARIO_ID = LQV_STRESS_SCENARIO.SCENARIO_ID | |
| Lqv_RateDepositType | LQV_OUTFLOW_RATE.DEPOSIT_TYPE = LQV_DEPOSIT_TYPE.DEPOSIT_TYPE | |
| Lqv_BalanceDepositType | LQV_DEPOSIT_BALANCE.DEPOSIT_TYPE = LQV_DEPOSIT_TYPE.DEPOSIT_TYPE | |
| Lqv_LcrScenario | LQV_LCR_RESULT.SCENARIO_ID = LQV_STRESS_SCENARIO.SCENARIO_ID | |
| Lqv_ConcentrationBucket | LADDER_ID **and** BUCKET_ID to LQV_BUCKET | |
| Lqv_FlowMovement | INSTITUTION_ID, ACCOUNT_NO **and** MOVEMENT_ID to CSH_CASH_MOVEMENT | three columns, per cash-core |
| Lqv_BalanceBookedBalance | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE **and** AS_OF_DATE = BALANCE_DATE, to CSH_CASH_BALANCE | four columns, same grain |
| Lqv_FlowFundingCashflow | LQV_FLOW.FUNDING_CASHFLOW_ID = FND_CASHFLOW.CASHFLOW_ID | |
| Lqv_NsfrLineCurve | LQV_NSFR_LINE.CURVE_ID = FND_CURVE.CURVE_ID | the UNVERSIONED header only |

### Filters

| filter | condition |
| --- | --- |
| LqvLiveLadders | `LQV_LADDER.RETIRED_ON is null` |
| LqvLiveScenarios | `LQV_STRESS_SCENARIO.WITHDRAWN_ON is null` |
| LqvOutflowsOnly | `LQV_FLOW.DIRECTION = 'OUT'` |

None uses a boolean literal: `Filter X(T.IS_ACTIVE = true)` does not parse.

## For downstream projects

`include liquidity_view::Store` and `include liquidity_view::Mapping`. Both transitively bring
cash-core, funding-core, core-account and core-tenor — and core-types through cash-core's
mapping — so do not include any of those a second time.

This project is itself a **diamond**: cash-core and funding-core each include
`core_account::Store`, so `CA_ACCOUNT` arrives here by two paths. It is included once, not
twice; the two paths converge and nothing has to be done about it.

To band your own amounts, declare your own range join against `LQV_BUCKET.MIN_DAYS` /
`MAX_DAYS` with a `LADDER_ID` equality alongside it. To read a ladder without scanning flows,
query `LqvBucketFlowTotal`, remembering that empty bands are absent rather than zero.

## Verified

    python3 scripts/projects/check.py liquidity-view
    compiles  liquidity-view (+5 deps)
