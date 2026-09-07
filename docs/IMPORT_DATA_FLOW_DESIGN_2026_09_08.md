# importDataFlow — clean-sheet design (2026-09-08)

Status: DESIGN ONLY. The first attempt (six fix cycles, 2026-09-08) was reverted
uncommitted by the user's decision ("revert and clean sheet the right design").
This document is the design the next batch implements as decided up front.
Witness: `meta::relational::tests::mapping::union::testPksWithImportDataFlow`
(DuckDB and H2 fail rosters).

## 1. What the engine does (spec)

`RelationalExecutionContext(importDataFlow = true, importDataFlowAddFks = true)`
on the `execute` exeCtx overload. In `pureToSQLQuery_union.pure:140–150`
(`buildUnion` key columns): every member set of a UNION projects its primary
key — always, as `pk_<p>_<memberOrdinal>` — and under `importDataFlow` the
column is instead NAMED `<column>_<memberOrdinal>` and a NON-member thread
carries the type's DEFAULT literal (`getDefaultLiteralValue`: Integer 0,
Float/Number 0.0, String '', Boolean false, Date/DateTime %9999-01-01T00:00:00,
StrictDate %9999-01-01) rather than NULL when `importDataFlowImplementationCount`
is empty. The set's primary key is `resolvePrimaryKey` (functions.pure:190):
the set's declared `~primaryKey`, compiler-filled from the main table's PRIMARY
KEY when undeclared. The witness expects rows `'Anand, 2, 0'`: `lastName`,
`ID_0` (set1's key), `ID_1` (set2's key, default 0 for a set1 row).

## 2. The facts, and where each is produced

| fact | producer | carrier |
|---|---|---|
| a union's key threads: `(name, pureKind)` per member key, member order | `UnionSynthesis` at the point it projects the threads (it already computes `<col>_<ord>` for routed keys) | `ModelBuilder.unionKeyThreads` (`mapping::class` → list), the same plumbing as `mixedUnions`; surfaced by `ModelContext.unionKeyThreads(mapping, class)` (default null; `PureModelContext` reads the builder) |
| the union binding's primary key | `MappingNormalizer` when it builds the union's `ClassBinding.Pure` | `ClassBinding.primaryKeyColumns` = the thread names (the engine's own notion: the union's key IS its members' keys) |
| the option on an execute call | `ContextReading` (literal-only, the driver-PK reader generalised to `contextFlag(name)`) | `ExecutionContext.importDataFlowColumns` (typed `Type.Column` list, empty = off) |
| the widened result type | the Typer's deferred-call typing (`bindDeferredAndBuild`) for the execute exeCtx overload | `Result<Relation<row + threads>>` |
| the appended projection columns | a resolver pass, `DriverPkAppend`'s sibling | the resolved `TypedProject` over the UNION ROW gains `coalesce(row.<thread>, default)` per thread |

Rules the attempt confirmed:

- The union synthesis MUST project every member's key thread unconditionally
  (engine parity). Batch 146's attempt did this and both lanes showed ZERO
  movement — the extra columns are harmless. A key already shared across
  members (`<col>__pk`, single-table hierarchies) is not doubled.
- The key thread's TYPE cannot be read off the synthesized union function: it
  returns class instances (`->map(row | ^Person(...))`), not a relation. The
  kind must ride the fact (`ViewRelation.columnPureKind` at production).
- The typer helper must NOT read the legacy `ClassMapping.Union` nor call the
  normalizer (`ArchitectureTest` forbids a compiler→normalizer cycle): it reads
  the normalized binding's `primaryKeyColumns` and the kinds through
  `ModelContext.unionKeyThreads`.
- Undeclared `~mainTable` on members is inferred by the normalizer
  (`inferMainTableQuiet`) — the synthesis site already has it; the typer never
  needs it once the kinds ride the fact.
- The append pass runs where the frame is executed (`StatementExecutor`, beside
  `DriverPkAppend`) and must DESCEND to the frame's projection: an assert side
  re-plans the spliced chain under `$result.rows->map(...)`, so the union-row
  projection is not the statement root. It appends only to the projection whose
  source row carries the threads; a projection over the frame's own TDS is
  left alone; a row carrying SOME threads is a half-built union — loud.
- Default literals: Integer 0, Float 0.0, String '', Boolean false as typed
  literals; the date defaults need `TypedCDate` literals — build them, do not
  throw (the engine's map has them).

## 3. The batch, in order

1. `ModelBuilder.unionKeyThreads` + record `KeyThread(name, pureKind)`;
   `ModelContext.unionKeyThreads` default + `PureModelContext` override.
2. `UnionSynthesis`: after `collectInboundRouteKeys`, add every member's
   primary-key threads (declared `~primaryKey` columns else the table's PK,
   via `PhysicalTables.find`), record the fact. Guard: `MappingNormalizer` is
   at its 3510-line cap — new code lives in `UnionSynthesis`.
3. `MappingNormalizer`: the union / inheritance binding's `primaryKeyColumns`
   = the recorded thread names (a 3-arg `declaredPrimaryKeyColumns(md, cm,
   model)` beside the Relational-only 1-arg one; `AggregateViewLift` keeps the
   1-arg).
4. `ContextReading.contextFlag(name)`; `ExecutionContext.importDataFlowColumns`
   (+ `withImportDataFlowColumns`, `importDataFlowRequested`).
5. `ImportDataFlow` (compiler.spec): `columns(mapping, class, ctx)` from the
   binding + `ctx.unionKeyThreads`; `widen(ExprType, cols)`; `rootClass(chain)`.
   Typer: `refineImportDataFlow` in `bindDeferredAndBuild` for the execute
   exeCtx overload (protocol alias of the 4th argument → `importDataFlow`
   literal). `ExecuteChainAssembly.chain`: the bound context carries the
   columns; the `TypedFrom` info widens.
6. `ImportDataFlowAppend` (resolver) + the `StatementExecutor` hook.
7. Lanes both; expect exactly the witness to flip on both. Pins that move:
   `JavaEvalLedgerTest` StatementExecutor (+4, orchestration), Typer (the arm),
   each with its reason.

## 4. Sizing and the rule

One batch, decided up front. The attempt's six cycles were discovery, not
implementation; with §2 known, the implementation is ~250 lines across the
seven files above. Three fix cycles then a wall, as always.
