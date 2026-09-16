# position-keeping

Layer 1. Depends on **core-instrument** and **core-account**, and on nothing else.
What a firm owns, per account, per instrument, per day.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**Composite primary key, four columns wide.** A position is
`(institutionId, accountNo, instrumentId, cobDate)` and is not identified by anything
shorter — the same instrument in the same account on two dates is **two rows**, because
yesterday's close is what today's open is reconciled against and it has to still be there.
The account half of that key is **two** columns, not one, because core-account keys an
account by `(institutionId, accountNo)`: a downstream store joining to `PK_POSITION` must
match all four, and a join to `CA_ACCOUNT` through it must match **both** account columns
or it will pick up accounts at other institutions. Every child table repeats all four and
adds its own discriminator; `PK_LOT_CLOSURE` runs to **six** key columns.

**Aggregation.** Four of the twelve classes are not events, they are per-group **totals**,
each mapped onto a store `View` whose `~groupBy` is its primary key:

| view | grouping | one row is |
| --- | --- | --- |
| PK_ACCOUNT_DAY_TOTAL | institution, account, cob date | one account's whole book on one day |
| PK_INSTRUMENT_DAY_TOTAL | instrument, cob date | the firm's net inventory in one security |
| PK_MOVEMENT_TOTAL | the position's four columns | one position's day of movements, totalled |
| PK_POSITION_LIFETIME | institution, account, instrument | a holding history — the grouping DROPS the date |

These are Legend views, not database views: no DDL, nothing seeds them, and the engine
folds the `GROUP BY` into the SQL it generates. Navigating `Position.accountDayTotal`
crosses into a group and never into another position row. Note what a grouping does not
produce: an account with no positions on a date forms **no group** and is simply absent
rather than appearing with a total of zero.

## Elements

Class names are unprefixed because `position_keeping::` already separates them; everything
in a GLOBAL namespace — tables, joins, filters, set ids — carries `PK_` / `Pk_` / `Pk` /
`pk`.

| element | kind | note |
| --- | --- | --- |
| position_keeping::Position | class | THE ROOT and the grain; key `(institutionId, accountNo, instrumentId, cobDate)`; opening/closing/settled quantity, average cost, realised and unrealised P&L; derived `positionKey()`, `quantityChange()`, `totalPnl()`, `unsettledQuantity()` |
| position_keeping::PositionMovement | class | one thing that changed a position that day; key + `movementSeq` |
| position_keeping::PositionLot | class | one parcel bought at one price; key + `lotId`; derived `isOpen()`, `remainingCost()` |
| position_keeping::LotClosure | class | one sale matched against one lot — where realised P&L comes from; key + `lotId` + `closureSeq` (six columns) |
| position_keeping::PositionAdjustment | class | a human override of a computed position; key + `adjustmentSeq`; derived `isApproved()` |
| position_keeping::SettlementLadderEntry | class | what is still due to settle out of the position, per future date; key + `settlementDate` |
| position_keeping::PositionBreak | class | a disagreement someone must resolve; key + `breakCode`; derived `isOpen()` |
| position_keeping::PositionRun | class | one execution of the batch; key `(cobDate, runSeq)`; derived `isComplete()` |
| position_keeping::AccountDayTotal | class | AGGREGATE on a view: one account, one day; derived `totalPnl()`, `averageLineValue()` (Float, because `/` widens) |
| position_keeping::InstrumentDayTotal | class | AGGREGATE on a view: one instrument across every account, one day |
| position_keeping::MovementTotal | class | AGGREGATE on a view: a position's movements totalled, same four-column grain |
| position_keeping::AccountInstrumentLifetime | class | AGGREGATE on a view: one account's whole history in one instrument, date dropped |
| position_keeping::Store | store | includes core_account::Store and core_instrument::Store; 8 tables, 4 views, 16 joins, 2 filters |
| position_keeping::Mapping | mapping | includes core_account::Mapping and core_instrument::Mapping; 12 sets, all with multi-column `~primaryKey` |

## Set ids (a GLOBAL namespace — extend or reference these, do not guess)

`pkPosition`, `pkPositionMovement`, `pkPositionLot`, `pkLotClosure`,
`pkPositionAdjustment`, `pkSettlementLadderEntry`, `pkPositionBreak`, `pkPositionRun`,
`pkAccountDayTotal`, `pkInstrumentDayTotal`, `pkMovementTotal`,
`pkAccountInstrumentLifetime`.

None is marked root, and every cross-set property mapping names its target id explicitly,
so a downstream `extends [...]` or `AssociationMapping` must name `[pkPosition]` (etc.)
rather than a default id.

## Store detail

| table | primary key |
| --- | --- |
| PK_POSITION | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE |
| PK_POSITION_MOVEMENT | ... + MOVEMENT_SEQ |
| PK_POSITION_LOT | ... + LOT_ID |
| PK_LOT_CLOSURE | ... + LOT_ID + CLOSURE_SEQ |
| PK_POSITION_ADJUSTMENT | ... + ADJUSTMENT_SEQ |
| PK_SETTLEMENT_LADDER | ... + SETTLEMENT_DATE |
| PK_POSITION_BREAK | ... + BREAK_CODE |
| PK_RUN | COB_DATE, RUN_SEQ |

| view | key = grouping | measures |
| --- | --- | --- |
| PK_ACCOUNT_DAY_TOTAL | INSTITUTION_ID, ACCOUNT_NO, COB_DATE | POSITION_COUNT, TOTAL_MARKET_VALUE, TOTAL_COST_BASIS, TOTAL_REALISED_PNL, TOTAL_UNREALISED_PNL |
| PK_INSTRUMENT_DAY_TOTAL | INSTRUMENT_ID, COB_DATE | ACCOUNT_COUNT, NET_QUANTITY, LONGEST_QUANTITY, TOTAL_MARKET_VALUE |
| PK_MOVEMENT_TOTAL | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE | MOVEMENT_COUNT, NET_QUANTITY_DELTA, NET_CASH |
| PK_POSITION_LIFETIME | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID | DAY_COUNT, FIRST_COB_DATE, LAST_COB_DATE, LIFETIME_REALISED_PNL |

| join | columns |
| --- | --- |
| Pk_PositionAccount | **cross-project** — INSTITUTION_ID **and** ACCOUNT_NO to CA_ACCOUNT |
| Pk_PositionInstrument | **cross-project** — INSTRUMENT_ID to CI_INSTRUMENT |
| Pk_PositionRun | COB_DATE **and** RUN_SEQ |
| Pk_PositionMovements | all four position columns |
| Pk_PositionLots | all four |
| Pk_PositionAdjustments | all four |
| Pk_PositionLadder | all four |
| Pk_PositionBreaks | all four |
| Pk_LotClosures | all five lot columns |
| Pk_PositionMovementTotal | all four, into PK_MOVEMENT_TOTAL |
| Pk_PositionAccountDayTotal | INSTITUTION_ID, ACCOUNT_NO, COB_DATE, into PK_ACCOUNT_DAY_TOTAL |
| Pk_PositionInstrumentDayTotal | INSTRUMENT_ID, COB_DATE, into PK_INSTRUMENT_DAY_TOTAL |
| Pk_PositionLifetime | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, into PK_POSITION_LIFETIME |
| Pk_AccountDayTotalAccount | **cross-project**, from a view — both account columns to CA_ACCOUNT |
| Pk_InstrumentDayTotalInstrument | **cross-project**, from a view — INSTRUMENT_ID to CI_INSTRUMENT |
| Pk_LifetimeAccount / Pk_LifetimeInstrument | **cross-project**, from a view |

Filters `PkOpenLots` (`CLOSED_ON is null`) and `PkUnresolvedBreaks`
(`RESOLVED_ON is null`) — null tests, because a `Filter` will not take a boolean literal.

## Cross-project references made

* `Database position_keeping::Store ( include core_account::Store include core_instrument::Store ... )`
* `Mapping position_keeping::Mapping ( include core_account::Mapping include core_instrument::Mapping ... )`
* `Position.account: core_account::Account[1]`, mapped `account[caAccount]: [...]@Pk_PositionAccount`
* `Position.instrument: core_instrument::Instrument[1]`, mapped `instrument[ciBase]: [...]@Pk_PositionInstrument`
* the same two properties again on `AccountDayTotal`, `InstrumentDayTotal` and
  `AccountInstrumentLifetime` — a group still knows whose and what it is

## Verified

    python3 scripts/projects/check.py position-keeping
    compiles  position-keeping (+2 deps)
