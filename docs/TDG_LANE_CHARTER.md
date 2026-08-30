# The ###Data / TDG lane charter (2026-08-30)

The LARGEST remaining decline bucket: `assert-test-data-csv` = **117
asserts, exactly pinned** (RelationalCorpusRunner lane guard), all in ONE
engine family — `meta::relational::testDataGeneration::tests` (~35
tests). Census attributed row-by-row 2026-08-30 (probe on the decline
site, full sweep, reverted); raw rows preserved in the session register.

## Census — the 117 by assert form (measured, no sampling)

| form | count | contract |
|---|---|---|
| assertSqlEquals/2 | 45 | the SQL text the generation pipeline emits (`.sqls`) — engine H2 spellings, golden-SQL doctrine territory |
| assertTestData/3 | 35 | the generated ROWS (the row contract — the actual test data) |
| assertSize/2 | 26 | generated row counts |
| assertEquals/2 | 10 | `necessaryColumns` lists + CSV strings (incl. quoted-columns, no-data cases) |
| assertEqualsH2Compatible/3 | 1 | dual-golden SQL compare |

Families inside the tests: joins/unions (incl. union-to-union multiple
levels + string hashing), inheritance (multi-level, multi-table), views
(root, view-on-view, embedded-in-chained-join, nested), milestoning
(business/processing/bitemporal, with and without dates), OLAP groupBy,
qualifiers, constants, quoted columns, multiple start rows.

## Current architecture (the debt, named)

Generation is ALREADY production Java — `testdatagen/TestDataGenerator`
(eval-ledger registered): `generate(...)` → `Result(sqls, dataCsvString,
...)`, plus `necessaryColumns`, `seedDataString`, `compareCsv`,
`planText`. But ORCHESTRATION and ADJUDICATION are harness-side
(EngineTestExecutor #46): `tdgLetArm` intercepts four binding shapes
(planGenerate / seedDataString / csvCensus / generate), runs the
generator, stores Results keyed by let name; `checkTdgAssert`
adjudicates asserts against those Results in the HARNESS. The
dual-channel names every such assert a `assert-test-data-csv` decline —
correctly, because the production verdict path never sees them. This is
the harness-as-third-implementation pattern (harness-platformization
program) applied to a whole feature: the platform can GENERATE but
cannot EVALUATE the generation surface.

## Target architecture (tenets applied)

The statement list (lets + asserts) rides `evalSpliced` →
`StatementExecutor` → `AssertVerdicts` UNCHANGED — no TDG special-casing
in the harness at all:

1. **K-natives for the TDG surface**: StatementExecutor evaluates the
   engine's testDataGeneration functions by calling the EXISTING
   production generator (Java orchestrates; the generator's row
   materialization already executes through the database). Signatures
   validated against the real engine .pure
   (validate-against-registered-signature).
2. **Result values are platform values**: `Result` surfaces as typed
   platform data (the CSV string, the sqls list, the row relation) so
   downstream asserts are ordinary value asserts.
3. **assertTestData/3 needs NO new verdict form** (homework 2026-08-30,
   engine source read): `tests::assertTestData(s1,s2,db)` is a USER
   test function whose body is
   `assertSameElements(setUpDataSQLs(s1,db), setUpDataSQLs(s2,db))` —
   and BOTH dependencies are already platform-owned
   (SET_UP_DATA_SQLS native + the assert family). Routing it =
   compiling the user function and evaluating its body; the row
   contract is the engine's own CSV→setup-SQL→same-elements compare.
   The one S2 question: our setUpDataSQLs must accept the GENERATED
   csv envelope (both args ride the same native, so format agreement
   is by construction if the envelope matches the shared CSV grammar).
4. **assertSqlEquals is per-string, not a list problem** (homework):
   `tests::assertSqlEquals(s1,s2)` =
   `assertEquals(s1->sqlRemoveFormatting(), s2->sqlRemoveFormatting())`
   — plain string asserts, one per generated sql. The REAL S3
   challenge is golden-SQL doctrine on CONTENT: our generator's .sqls
   text vs the engine's H2 spellings (the harness today count-verifies
   and treats text as advisory — byte convergence or named
   sql-text-outcome buckets is S3's actual work).
5. **Harness #46 arms DELETE** (tdgLetArm TDG branches, checkTdgAssert,
   TestDataGenForm routing); the 117 decline pin burns toward 0 with a
   named residue for anything adjudicated otherwise.

## HONESTY LABELS — what is receipts vs design intent

ALL FORMER UNKNOWNS RESEARCHED (2026-08-30). Receipts now in the list
above and here:
- `TestDataGenerator.generate` DOES execute through the database
  (temp-table fetches via the connection, csvEnvelope read-back, temps
  dropped in finally) — Result(sqls = the fetch SQL it ran, csv).
- S2 engine signatures (testDataGeneration.pure:72-118):
  `generateTestData(func, mapping, runtime[, parameters],
  rowIdentifiers[, hashStrings], extensions): TestDataGenResult[1]`
  (3 overloads), `createTableRowIdentifiers` (2 overloads),
  `createRowIdentifier(columnNames, columnValues): RowIdentifier[1]`.
  S2 must register the prelude classes `TestDataGenResult`,
  `TableRowIdentifiers`, `RowIdentifier` the same way S1 registers the
  CSV-census classes (and per the ###Data rule: single representation).
- `tests::assertTestData` / `tests::assertSqlEquals` are USER
  functions in the corpus test source — routing them is user-call
  compilation over already-owned natives, not new AssertVerdicts arms.

## Slices (each gated, ratchets moved with attribution)

- **S1 (smallest, proves the seam)**: the CSV-census form
  (getRelationalCSVDataFromQuery) → checker fold + routed assertEquals.
  EXACTLY 6 asserts (3 tests × 2 — see witnesses); the other 4
  assertEquals rows in the census are generate/seedDataString-family
  and belong to S2.
- **S2 (the row contract)**: generate/seedDataString natives +
  assertTestData/3 + assertSize/2 routed verdicts. ~62 asserts.
- **S3 (text lane)**: assertSqlEquals/2 + H2Compatible → sql-text
  referee lanes (exec-verify the generated SQL where executable). ~46
  asserts.
- **S4 (deletion + pins)**: harness arms deleted; decline pin 117 → 0
  (or exact named residue); JavaEvalLedger unchanged (the generator was
  always production).

## Open questions for sign-off

1. assertSqlEquals rows land in the sql-text lane's OUTCOME buckets —
   acceptable to grow exec-passing/unable-to-exec pins there, with the
   same attribution discipline?
2. planTestDataGeneration (plan-text flavored bindings) — S3 or defer
   with the plan-text lane?
3. Milestoning-dates variants ride S2 or split if the temporal seam
   resists?

## S1 CORRECTED ARCHITECTURE (2026-08-30 — after a reverted first attempt)

A first S1 implementation was built, PROVEN END-TO-END (all 3
necessaryColumns tests routed with dual-channel agree=6, zero declines),
then REVERTED — because two of its mechanisms were wrong even though
the numbers were green. The corrected design, with the receipts:

**The fold belongs in the CHECKER, not the executor.** The census is a
COMPILE-TIME REFLECTION fact (model-space: query AST + mapping →
table/column demand; no database — TENET_CHARTER C1.6, the
deactivate/.genericType precedent). The checker, on typing the native's
call, holds the PROTOCOL query argument natively — validate the
registered signature, run TestDataGenerator.necessaryColumns, emit the
instance-literal tree (^RelationalCSVData(tables=[^RelationalCSVTable
(schema, table, values)…])) right there. This deletes, relative to the
reverted attempt: the executor prepass, the protoStmts side-channel
through executeStatements, and the protocol tree-search
(findCall/letValue archaeology) — all three seams existed only because
the fold ran in the wrong phase.

**What stays from the proven attempt** (re-land verbatim):
- Pure.java: EMBEDDED_DATA + RELATIONAL_CSV_TABLE (property types
  spelled with FULL FQNs — bare `String` does not resolve in
  nativeClass) + RELATIONAL_CSV_DATA classes; the
  getRelationalCSVDataFromQuery signature (executionPlan-precedent
  param relaxation).
- PlatformTypes: GET_RELATIONAL_CSV_DATA + isPlatformOwnedFunction
  membership.
- Pipelines.literalOrAutoMapRead: property-access-over-instance-literal
  folds to the property value — compiler CONSTANT FOLDING (the
  structural-shapes idiom), hooked by the zero-net-line swap in
  StoreResolver's auto-map arm (StoreResolver is at 3498/3500 — the
  logic must live in Pipelines).
- Harness: delete tdgLetArm's hasCsvCensus branch (cut over hard).
- Runner pin: assert-test-data-csv 117 → 111 with attribution.

**The one REAL platform gap**: TypedSortBy has no scalar-lowering arm.
Two of the three tests sort the literal collection
(`->sortBy(t | $t.schema + $t.table)`). The fix is ORDER BY executed
by the database — NEVER a Java sort. DESIGN CAUTION (audit
2026-08-30): order does NOT survive a subquery into an aggregation in
SQL — sortBy feeding joinStrings must thread the key INTO the
aggregation (string_agg(x ORDER BY key) or the platform's equivalent).
AUDIT UPDATE (verified in code): sortBy IS fully implemented —
Sorts.sortBy (ORDER BY over the lowered key, fold/isolate handling,
Lowerer.relation dispatch :589) and CollectionLanes.valueLane already
whitelists TypedSortBy. The scalar switch (Lowerer ~:3095-3120) has an
existing scalar↔relation BRIDGE arm admitting relation-op heads
(TypedDistinct, TypedSort, ...) via relation(rel) + ScalarSubquery —
TypedSortBy is simply MISSING from its admit-list(s). The fix is a few
guard lines adding TypedSortBy beside TypedSort in that arm, reusing
Sorts.sortBy verbatim. Verify sort-order survives into joinStrings the
same way it does for bridged TypedSort sources (it should — same arm);
only if it does not, thread the key into the aggregation
(string_agg(x ORDER BY key)).

**Checker-fold receipts (researched, no longer a guess)**: the hook is
`Typer.applyCore(CoreFn fn, AppliedFunction af, Env env)` — the sealed
special-form dispatch, which receives the PROTOCOL call directly; the
`case DEACTIVATE ->` arm (~Typer:1239) is the compile-time-reflection
precedent to copy, and the 34 `*Checker` classes
(compiler/spec/*Checker.java) are the per-form checker idiom
(Checkers.java validates against the registered signature parameter by
parameter). For `$query` as a VARIABLE: the compiler already has
source-level protocol let-inlining — `SourceSubst.inlineLets`
(~Typer:2142, "pure lets are value bindings — β-substitution is
exact") — reuse that mechanism; never a new tree search. (Today the
harness also pre-inlines, so S1 may only ever see the inline shape —
but the variable arm rides existing machinery either way.)

**Chain status of the proven attempt** (audit): the first attempt was
PROBE-green only (scoped corpus runs + JavaEvalLedgerTest) — the full
chain never ran on it. Expect governance pins to want conscious
registration on re-land: PlatformTypesDriftTest (FQN constants vs
prelude declarations move together), the natives-partition census, and
any prelude-class count pins. Treat their failures as registration
work, not regressions.

## ANTI-PATTERNS CAUGHT AND REVERTED (do not re-derive)

1. **Shadow interpreter**: a foldLiteral/inlineVar/constString cluster
   in production Java that applied lambda params, evaluated string
   `+`, and sorted — Pure semantics in host Java. The eval ledger did
   NOT catch it (new file, name-pinned ledger — a known census-grain
   hole). If a wall says "lowering not implemented for X", the fix is
   the lowering arm, never a Java evaluator.
2. **Wrong-phase fold**: executor-seam interception + typed→protocol
   side-channel, built from harness instinct. Compile-time facts fold
   in the compiler phase that holds the inputs naturally.
3. **Probe hygiene**: two false claims in one slice from grep patterns
   ("FAIL " missed ERROR rows; "error:" missed maven's second format).
   Verdict greps must match the runner's full outcome vocabulary.

## S1 witnesses (exact)

testGenerateNecessaryTableColumnsForSingleTable (2 asserts, no sort),
ForMultiTables (2, sortBy), ForMilestoningTable (2, sortBy) — family
probe: -Drcorpus.only=testDataGeneration -Drcorpus.test=
testGenerateNecessaryTableColumns. Success = dual-channel agree=6, csv
declines 117→111, family otherwise byte-stable, ledger + chain green.

## ONE METAMODEL — the ###Data unification (do NOT build two things)

The engine's `RelationalCSVData extends EmbeddedData`
(relationalTest.pure:17) is not TDG-private — it IS the ###Data
metamodel's relational shape. One class family, three consumers:

1. **TDG census results** (S1) — `getRelationalCSVDataFromQuery`
   returns it.
2. **TDG generated data** (S2) — the row contract materializes through
   it.
3. **###Data elements and mapping testSuites data blocks** — TODAY:
   `###Data` parses fully (byte-parity proven; `PDataElement` +
   `PDataBody` on the wire) but enters the compile model as
   `OpaqueElementDefinition` ("legend-lite's compile model has no
   data-element concept" — ElementParser.dataElement); mapping
   `testSuitesSource` is D-3 raw text.

THE RULE: the prelude classes S1 registers (`EmbeddedData`,
`RelationalCSVData`, `RelationalCSVTable`) are THE compile-model
representation of relational embedded data — for all three consumers.
When ###Data becomes a compile-model citizen (its own future leg),
`dataElement()` stops producing Opaque and its relational body opens
into instances of THESE classes — never a parallel data model. The
protocol side is already shared (`PDataElement`/`PDataBody`); the TDG
lane must not invent a second wire shape either.

S2 DESIGN CONSTRAINT from this: the row-materialization path
(RelationalCSVData → CREATE/INSERT seeds for the verdict-in-DB compare)
must be built as a SHARED mechanism with two callers in mind — TDG
results now, ###Data-seeded test setups later. One materializer, not
two.

## Appendix — the raw census (117 asserts, attributed 2026-08-30)

By (test, form), count-prefixed; the alloy sub-family (6 plan-flavored
asserts) is SEPARATE — already plan-let-partitioned and deferred with
the plan-text lane per sign-off:

```
   6 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnion :: assertSqlEquals/2
   6 [tdc] meta::relational::testDataGeneration::tests::testUnion :: assertSqlEquals/2
   4 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion :: assertSqlEquals/2
   4 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSMultipleJoins :: assertSqlEquals/2
   4 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleLevel :: assertSqlEquals/2
   3 [tdc] meta::relational::testDataGeneration::tests::testSelfJoin :: assertSqlEquals/2
   3 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleTableJoin :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinToSameTable :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndOLAPGroupBy :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithConcatenate :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTableMultipleStartRows :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTable :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testQualifier :: assertSqlEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testGenerateNecessaryTableColumnsForSingleTable :: assertEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testGenerateNecessaryTableColumnsForMultiTables :: assertEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testGenerateNecessaryTableColumnsForMilestoningTable :: assertEquals/2
   2 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithBusinessDateMilestoning_WithMilestoningDates :: assertTestData/3
   2 [tdc] meta::relational::testDataGeneration::tests::TestDatGenForNestedViews :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testViewEmbeddedInChainedJoin :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testViewEmbeddedInChainedJoin :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionViewOnView :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionViewOnView :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnionMultipleLevelsWithStringHashing :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnionMultipleLevelsWithStringHashing :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnionMultipleLevels :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnionMultipleLevels :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnion :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testUnionToUnion :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testUnion :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testUnion :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinToSameTable :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinToSameTable :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndOLAPGroupBy :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndOLAPGroupBy :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithGroupBy :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithGroupBy :: assertSqlEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithGroupBy :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithConcatenate :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithConcatenate :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithAppliedFunctions :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithAppliedFunctions :: assertSqlEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTdsWithAppliedFunctions :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSSimple :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSSimple :: assertSqlEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSSimple :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSMultipleJoins :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testTableToTDSMultipleJoins :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleViewRootToJoin :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleViewRootToJoin :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleViewRoot :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleViewRoot :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTableMultipleStartRows :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTableMultipleStartRows :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTable :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTwoTable :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTableToViewJoin :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleTableToViewJoin :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTableWithNoDataToInsert :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTableWithNoDataToInsert :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTableWithNoDataToInsert :: assertEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTable :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTable :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSimpleSingleTable :: assertEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testSelfJoin :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testSelfJoin :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testQualifier :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testQualifier :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testQualifier :: assertEqualsH2Compatible/3
   1 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleTableJoin :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleTableJoin :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleLevel :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testInheritanceMultipleLevel :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testGenerateSeedDataWithQuotedColumns :: assertEquals/2
   1 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithSnapshotMilestoning_WithMilestoningDates :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithSnapshotMilestoning :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithBusinessDateMilestoning :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithBiTemporalMilestoning_WithMilestoningDates :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testDataGenerationWithBiTemporalMilestoning :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testConstant :: assertTestData/3
   1 [tdc] meta::relational::testDataGeneration::tests::testConstant :: assertSize/2
   1 [tdc] meta::relational::testDataGeneration::tests::testConstant :: assertEquals/2
```
