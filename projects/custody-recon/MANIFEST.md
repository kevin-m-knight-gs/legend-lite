# custody-recon

Layer 2. Depends on **custody-core** and **position-keeping**, and on nothing else. It does
not name anything from core-account or core-instrument: whatever it needs about an account
or an instrument it reaches *through* a holding or a position.

Custody against internal positions — the classic reconciliation. A run, its matched items,
its breaks by type, their ageing, their assignment and their resolution.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## The problem, and how it is solved

The two upstream keys share three columns and disagree on the fourth:

    CST_HOLDING   INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, DEPOT_CODE
    PK_POSITION   INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE

Neither key is a prefix of the other. Custody has no date — a holding is a standing
statement of *where stock sits* — and position keeping has no depot — a position is what an
account *owns*, wherever it sits. So:

1. **The date comes from the run.** `ReconRun` is keyed `(reconCode, cobDate, runSeq)`, and
   every row it produces is stamped with its `cobDate`. That is the column the custody side
   is missing, and it is spent twice: once joining to `PK_POSITION.COB_DATE`, and once
   joining to `CST_STATEMENT_LINE.STATEMENT_DATE`.
2. **The item carries both keys.** `ReconItem` is keyed by **six** columns,
   `(reconCode, cobDate, institutionId, accountNo, instrumentId, depotCode)`. Columns 3–6
   are custody-core's holding key exactly; columns 2–5 are position-keeping's position key
   exactly. `Rcn_ItemHolding` matches four with no date; `Rcn_ItemPosition` matches four
   with no depot. Both joins leave the same row.
3. **The depot is aggregated away.** `Rcn_ItemPosition` is many-to-one — every depot's item
   lands on the *same* position row — so comparing item to position directly would
   double-count. `DepotRollup` is a store `View` whose `~groupBy` is the position's five
   columns, i.e. the items grouped with `DEPOT_CODE` dropped. That GROUP BY is the
   reconciliation: it converts a set of holding-shaped rows into one position-shaped row,
   and `Rcn_RollupPosition` then joins group to position cleanly.
4. **`ReconLine` is the same shape, materialised.** Five key columns, one per position, with
   the summed custody quantity and `depotCount` on it — the row an ops screen reads.
5. **The split back down is a stated policy, not a fact.** `ReconItem.internalQuantity` is
   the position's quantity *allocated* to one depot, and `allocationBasis` says how:
   `SINGLE_DEPOT`, `PRIMARY_DEPOT`, `PRO_RATA`, or `UNALLOCATED` when the internal book
   simply does not know. An `UNALLOCATED` item still rolls up into a line that reconciles.

Note what a grouping does not produce: a position with no custody items at all forms **no
group**, and is absent rather than showing a total of zero — which is why `MISSING_THERE`
has to be detected on the items and cannot be read off `RCN_DEPOT_ROLLUP`.

## The two constructs a downstream project should know about

**Composite primary key.** `RCN_ITEM` is **six** columns and `RCN_BREAK` is **seven** (the
item's six plus `BREAK_TYPE`, because one item can be wrong in two ways at once and the two
are worked by different desks). `RCN_TOLERANCE_RULE` and `RCN_RUN` are three each. Only
`RCN_DEFINITION` is identified by one column.

**Aggregation.** Four classes are per-group totals on store `View`s whose `~groupBy` is
their primary key:

| view | grouping | one row is |
| --- | --- | --- |
| RCN_DEPOT_ROLLUP | recon, cob date, institution, account, instrument | custody's several depots collapsed into the position's shape — **the reconciling one** |
| RCN_ACCOUNT_TOTAL | recon, cob date, institution, account | one account's whole reconciliation on one day |
| RCN_BREAK_TYPE_TOTAL | recon, cob date, break type | the daily break report, four rows |
| RCN_ASSIGNEE_TOTAL | recon, assignee — the grouping **drops the date** | one person's break queue across every day |

## Elements

| element | kind | note |
| --- | --- | --- |
| custody_recon::ReconDefinition | class | the standing set-up: which two sources, how often, whose; key `reconCode`; derived `isLive()` |
| custody_recon::ToleranceRule | class | how small a difference counts as zero; THREE-column key `(reconCode, ruleScope, ruleCode)`; derived `ruleKey()`, `isCurrent()` |
| custody_recon::AgeingBand | class | a named bucket of break age and what it escalates to; key `(reconCode, bandCode)`; derived `isOpenEnded()` |
| custody_recon::ReconRun | class | one execution for one day; THREE-column key `(reconCode, cobDate, runSeq)`; **supplies the cobDate the custody key lacks**; derived `isComplete()`, `matchRate()` (Float — `/` widens) |
| custody_recon::ReconLine | class | the POSITION-SHAPED side; FIVE-column key `(reconCode, cobDate, institutionId, accountNo, instrumentId)`; derived `lineKey()`, `custodyLessInternal()`, `isMatched()`, `facesSeveralDepots()` |
| custody_recon::ReconItem | class | **the ROOT**; SIX-column key `(reconCode, cobDate, institutionId, accountNo, instrumentId, depotCode)` — both upstream key shapes in one row; derived `holdingKey()`, `positionKey()`, `signedDifference()`, `isMatched()`, `isCustodyOnly()`, `isInternalOnly()`, `isUnallocated()` |
| custody_recon::ReconBreak | class | one disagreement of one type; SEVEN-column key (the item's six + `breakType`); types QUANTITY / MISSING_HERE / MISSING_THERE / TIMING; ageing on `firstSeenOn`, not `detectedOn`; derived `isOpen()`, `isQuantityBreak()`, `isTimingBreak()`, `isOneSided()`, `isUnassigned()` |
| custody_recon::BreakAssignment | class | one handover of a break to one person; key `(reconCode, breakRef, assignmentSeq)`; derived `isCurrent()` |
| custody_recon::BreakResolution | class | how a break was closed and on whose authority; key `(reconCode, breakRef)`; derived `isApproved()`, `isWriteOff()` |
| custody_recon::DepotRollup | class | AGGREGATE on a view: the depot grouped away, the custody side in the position's shape; derived `differenceAgainstPosition()`, `averageDepotQuantity()` (Float), `facesSeveralDepots()` |
| custody_recon::AccountReconTotal | class | AGGREGATE on a view: one account, one day; derived `averageDifference()` (Float) |
| custody_recon::BreakTypeTotal | class | AGGREGATE on a view: breaks of one type on one day; derived `averageBreakQuantity()` (Float) |
| custody_recon::AssigneeLoad | class | AGGREGATE on a view: one person's queue, date dropped; derived `isOverloaded()` |
| custody_recon::Store | store | includes custody_core::Store and position_keeping::Store; 9 tables, 4 views, 22 joins, 2 filters |
| custody_recon::Mapping | mapping | includes custody_core::Mapping and position_keeping::Mapping; 13 sets, root `rcnItem` |

13 classes.

## Set ids (a GLOBAL namespace — extend or reference these, do not guess)

`rcnDefinition`, `rcnToleranceRule`, `rcnAgeingBand`, `rcnRun`, `rcnLine`, `rcnItem`,
`rcnBreak`, `rcnBreakAssignment`, `rcnBreakResolution`, `rcnDepotRollup`,
`rcnAccountReconTotal`, `rcnBreakTypeTotal`, `rcnAssigneeLoad`.

`rcnItem` is the **root** set (`*custody_recon::ReconItem`). Because every id here is
explicit, a downstream `extends [...]` or cross-project `AssociationMapping` must name them
— `custody_recon_ReconItem` does not exist. Upstream ids that stay relevant downstream:
`cstHolding`, `cstDepot`, `cstPendingMovement`, `cstStatementLine`, `pkPosition`,
`pkMovementTotal`, `pkSettlementLadderEntry`.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| RCN_DEFINITION | RECON_CODE | the only one-column key in the project |
| RCN_TOLERANCE_RULE | RECON_CODE, RULE_SCOPE, RULE_CODE | **three columns** |
| RCN_AGEING_BAND | RECON_CODE, BAND_CODE | MAX_AGE_DAYS null on the top band |
| RCN_RUN | RECON_CODE, COB_DATE, RUN_SEQ | **three columns**; a day is re-run when a late statement lands |
| RCN_LINE | RECON_CODE, COB_DATE, INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID | **five**; the position's shape |
| RCN_ITEM | + DEPOT_CODE | **six**; both key shapes in one row |
| RCN_BREAK | the item's six + BREAK_TYPE | **seven**; carries BREAK_REF for the workflow tables |
| RCN_BREAK_ASSIGNMENT | RECON_CODE, BREAK_REF, ASSIGNMENT_SEQ | keyed on the minted reference, not the seven-column natural key — a ticket system holds a reference |
| RCN_BREAK_RESOLUTION | RECON_CODE, BREAK_REF | one per break |

| view | key = grouping | measures |
| --- | --- | --- |
| RCN_DEPOT_ROLLUP | RECON_CODE, COB_DATE, INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID | DEPOT_COUNT, TOTAL_CUSTODY_QUANTITY, TOTAL_INTERNAL_QUANTITY, NET_DIFFERENCE, LARGEST_DEPOT_QUANTITY |
| RCN_ACCOUNT_TOTAL | RECON_CODE, COB_DATE, INSTITUTION_ID, ACCOUNT_NO | LINE_COUNT, TOTAL_CUSTODY_QUANTITY, TOTAL_INTERNAL_QUANTITY, TOTAL_DIFFERENCE_QUANTITY, TOTAL_DIFFERENCE_VALUE, LARGEST_DIFFERENCE |
| RCN_BREAK_TYPE_TOTAL | RECON_CODE, COB_DATE, BREAK_TYPE | BREAK_COUNT, TOTAL_BREAK_QUANTITY, TOTAL_BREAK_VALUE, OLDEST_AGE_DAYS, EARLIEST_DETECTED_ON |
| RCN_ASSIGNEE_TOTAL | RECON_CODE, ASSIGNED_TO | ASSIGNED_BREAK_COUNT, TOTAL_BREAK_VALUE, OLDEST_AGE_DAYS, LATEST_COB_DATE |

| join | columns |
| --- | --- |
| Rcn_DefinitionToleranceRules / Rcn_DefinitionAgeingBands / Rcn_DefinitionRuns | RECON_CODE |
| Rcn_RunLines / Rcn_RunItems | RECON_CODE, COB_DATE, RUN_SEQ |
| Rcn_LineItems | all five line columns — the one-to-many the depot creates |
| Rcn_ItemBreaks | all six item columns |
| Rcn_ItemToleranceRule | RECON_CODE, TOLERANCE_SCOPE, TOLERANCE_CODE |
| Rcn_BreakAssignments / Rcn_BreakResolution | RECON_CODE, BREAK_REF |
| Rcn_BreakAgeingBand | RECON_CODE, AGEING_BAND_CODE = BAND_CODE |
| Rcn_LineDepotRollup / Rcn_ItemDepotRollup | five columns, into RCN_DEPOT_ROLLUP |
| Rcn_LineAccountTotal | four, into RCN_ACCOUNT_TOTAL |
| Rcn_BreakTypeTotal | three, into RCN_BREAK_TYPE_TOTAL |
| Rcn_BreakAssigneeLoad | two, into RCN_ASSIGNEE_TOTAL |
| Rcn_ItemHolding | **cross-project** — INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, DEPOT_CODE to CST_HOLDING; **no date** |
| Rcn_ItemDepot | **cross-project** — three columns to CST_DEPOT |
| Rcn_ItemPendingMovements | **cross-project** — four columns to CST_PENDING_MOVEMENT |
| Rcn_ItemStatementLine | **cross-project** — four holding columns **plus** RCN_ITEM.COB_DATE = CST_STATEMENT_LINE.STATEMENT_DATE |
| Rcn_ItemPosition | **cross-project** — INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, COB_DATE to PK_POSITION; **no depot**, hence many-to-one |
| Rcn_LinePosition | **cross-project** — the same four, from the line |
| Rcn_RollupPosition | **cross-project**, group to row — RCN_DEPOT_ROLLUP's four onto PK_POSITION |
| Rcn_LineMovementTotal | **cross-project**, into position-keeping's own aggregation PK_MOVEMENT_TOTAL |
| Rcn_LineLadder | **cross-project** — four columns to PK_SETTLEMENT_LADDER |

Filters `RcnOpenBreaks` (`RCN_BREAK.RESOLVED_ON is null`) and `RcnUnmatchedItems`
(`RCN_ITEM.MATCHED_ON is null`) — null tests, because a `Filter` will not take a boolean
literal.

## Cross-project references made

* `Database custody_recon::Store ( include custody_core::Store include position_keeping::Store ... )`
  — both included stores themselves include core-account and core-instrument, so those
  arrive twice by two routes; the diamond compiles and nothing here names a table from them.
* `Mapping custody_recon::Mapping ( include custody_core::Mapping include position_keeping::Mapping )`
* `ReconItem.holding: custody_core::CustodyHolding[0..1]`, mapped `holding[cstHolding]`
* `ReconItem.depot: custody_core::Depot[0..1]`, mapped `depot[cstDepot]`
* `ReconItem.pendingMovements: custody_core::PendingMovement[*]`, mapped `[cstPendingMovement]`
* `ReconItem.custodianStatement: custody_core::CustodyStatementLine[0..1]`, mapped `[cstStatementLine]`
* `ReconItem.position` / `ReconLine.position` / `DepotRollup.position:
  position_keeping::Position[0..1]`, mapped `[pkPosition]`
* `ReconLine.movementTotal: position_keeping::MovementTotal[0..1]`, mapped `[pkMovementTotal]`
* `ReconLine.ladder: position_keeping::SettlementLadderEntry[*]`, mapped `[pkSettlementLadderEntry]`

## Verified

    python3 scripts/projects/check.py custody-recon
    compiles  custody-recon (+4 deps)
