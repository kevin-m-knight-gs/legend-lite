# Track T — the expectations that moved, one by one (started 2026-10-29)

The user's ruling (2026-10-28): the overlapping tests are not deleted; the same queries and logic are judged on lite's
own LEAN output (lite's JSON rows, what the query service serves), the platform twin of PCT and the corpus. The rule
of this file (the start page's track T, build item 2): every expectation that moves is decided one by one and listed
here -- the file, the test, the old expectation, the new one, the reason (the old decoder's Java carrier; a Decimal's
scale; a date's precision; a bug in the new path FIXED -- never a loosened assertion).

## The reader (`core/src/test/java/com/legend/testing/Lean.java`; the decisions log D42)

`Lean.run(model, query, runtime, connection)` runs the product's own route (`Execution.run`: plan, the plan's runner,
`JsonRowsWriter`) and reads the JSON back as a typed client does. Three rules decided while the first batch ran:

1. **The columns are the ones the runner handed the writer**, captured by the sink -- a keyed plan's (a dynamic pivot's
   per-key columns) exist only once its keys have run; `ExecutionPlan.answerColumns()` is null for it by design.
   (Seen as 26 pivot tests refusing "the plan answers no rows".)
2. **A row's key is the column's PRESENTED name** (`CellText.presentedName`: a generated pivot column in its quotes, as
   the engine labels it), the writer's own spelling, looked up the same way. (Seen as null cells in 5 pivot and
   quoted-name tests.)
3. **Each cell is decoded by the product's own row decoder** (`result/RowDecode.cell`, by the column's three type
   facts: an Integer a long, a Float a double, a Decimal an exact BigDecimal, a date its `PureDateLiteral`, a String its
   text); a TAGGED column (a mixed selection), which reaches the writer as the one cell the session resolved, is read
   by its declared Pure type as any slotless cell is (a Number: a whole number a long, any other an exact decimal; a
   Date: its text parsed). (Seen as 10 refusals "a tagged cell is read with its row" and, before rule 3 chose the
   decoder, 11 date carriers arriving as text.)

## Batch T-a (2026-10-29): `AbstractDatabaseTest`, `CheckerResults`, the 28 subclasses -- 2,232 tests

| File | Test | Old expectation | New expectation | Reason |
|---|---|---|---|---|
| (none) | -- | -- | -- | every expectation held: 2,232 of 2,232 pass on the LEAN reader with the three rules above; the mechanical rewrites changed the FORM of the reads, never a value (the T-a audit compared the 23 rewritten files statement by statement: zero residue): `asCollection().values()` -> `values()`; `returnType().typeName()` -> `columns().get(0).type()` (six sites, every one a scalar answer whose column 0 IS the value's type); `asGraph().json()` -> `json()`; `assertInstanceOf(ExecutionResult.Graph.class, r)` -> `assertEquals(Shape.OBJECTS, r.shape())`; `col.pureType().typeName()` -> `col.type()`; `Row` -> `List<Object>` and a row's `.values()` dropped; `ExecutionResult` (simple and qualified) -> `Lean.Result`; the service's materialized `execute` and `Execution.execute` -> `Lean.run`; `((Scalar) r).value()` -> `r.one()` (which also enforces one row of one column); two unused `QueryService` fields deleted |
| DuckDBIntegrationTest, TypeInferenceIntegrationTest | the tagged-column tests (`testMode_MixedNumericList_returnsDouble`, `testMinNumbers_preservesOriginalType`, `testGreatest/Least_MixedDateTypes_*`, `testMinNumbers`) | a mixed selection's cell carried the SLOT's carrier (a Float slot a `Double`; a DateTime slot at its presented precision) | the JSON token read by the column's declared type (a Number: a whole number a `Long`, any other an exact `BigDecimal`; a Date: its text parsed) | a CARRIER fact that changed with no assertion depending on it (the T-a audit's finding 4): the tests assert `instanceof Number`, `.doubleValue()` within a tolerance, `instanceof Integer || Long` and a date's `toString()`, all of which hold; the `returnsDouble` name was already vacuous (`instanceof Number`), before this batch. Named here, not folded into rule 3 |

`ExecutionResultIntegrationTest` (8 tests) asserted the old door's result VARIANTS (`Scalar`, `Tabular`, the typed
accessors' errors, `returnType` non-null): rewritten to the LEAN answer's SHAPES (`VALUE`, `RELATION`), the plan's
column names and types (`firstName`, `Integer`, `String`) and the one column a VALUE answer is written with
(`[{"value": ...}]`) -- the same queries, the facts the product's output carries. Dropped: `Type.relationSchema(returnType)`
non-null (the relation's shape is asserted as `RELATION`); the typed accessors' error (an old-door fact).

**Counting rule for "left on the old door":** files under `core/src/test/java` whose text matches
`Execution\.execute\(|Execution\.executeResolved\(|ExecutionResult` (`grep -rlE`), and the lines matching
`Execution\.execute\(|Execution\.executeResolved\(|queryService\.execute\(` (`grep -rnE ... | wc -l`). At `39a2a657`:
114 files, 142 + 89 = 231 lines by the audit's two greps (the T-a records said "174 call sites in 115 files" from a
grep whose rule they did not state: corrected here, D43).

## Batch T-b (2026-10-29): the integration package's remaining 31 files; `testing/Program.java` -- 2,232 tests

Rule 4 (the reader): **a VARIANT column's cell is the variant's JSON text.** Lite's JSON rows write a Variant as a
STRING holding its JSON (legend-engine's measured form, `CellText`), so the token in the row is a JSON string; the
reader hands its value (`Json.Str.value()`), the text the old door's cell held. The writer is unchanged.

Rule 5 (the view, `Lean.of`): **a body only the program entry runs is read through the same reader.** Six files run
bodies the plan path (`Execution.run`) does not run today -- a read of the system metamodel store (`MetamodelStoreTest`,
`MetamodelMappingStoreTest`, `MetamodelStoreSubstitutionTest`, `MetamodelQueryFunctionsTest`: measured on `Lean.run`,
`DataError: Catalog Error: Table with name classes does not exist!` -- the plan's metamodel setup is not applied on the
caller's session where the body driver's route establishes the graph's system database, `SystemDatabase.Route`; an
open finding for track D, when the body driver's route goes, D44), the DDL natives (`DeclaredQuotedTableH2Test`), a
staged let (`StagedRowsTest`). They enter through `testing/Program.java` (`Execution.runProgram`, the one entry for a
program: the body typed once, the verdicts kept in order, the last statement's side) and read the side through
`Lean.of`: a scalar or a collection a VALUE answer of one column `value`, a relation a RELATION answer, a graph fetch
an OBJECTS answer. The body driver's `Scalar(null)` is Pure's `[]`: ZERO rows, as the plan path writes an empty value
(a statement answering no row writes `[]`; the judges' value lane makes `Scalar(null)` of no row, `JudgePlans`).

| File | Test | Old expectation | New expectation | Reason |
|---|---|---|---|---|
| AssociationIntegrationTest | `CombinedTraverse.testExtendTraverseThenFilterOnBoth` (the one query whose root keeps a navigate's slot column `n1`; the others select it away) | the old door's judge flattened the row-struct column to the prefixed columns the join emitted (`n1_ID`, `n1_NAME`, ...; `JudgePlans`' private `flattenStructColumns`) and answered 7 columns | the plan path declared `n1` as ONE column with no slot (`ColumnFacts`' tolerance for a name prefixing some outputs) and the runner refused: "the statement answers 7 columns, and the plan declares 5 [name, firm, deptId, n1, deptName]" | **a bug in the new path, fixed in main** (nothing in the test moved): `TypedRows.schema` flattens a row-struct column (`TypedRows.flat`, the lowering's own `outputsOf` rule, stated once), the judge's private copy deleted, `PlanColumns`' second derivation of the columns (`of(ExprType, SqlQuery)` over `UpstreamRelationType.columns`, which `TypedQuery.plan` took) deleted for the one (`TypedRows.schema`), `ColumnFacts`' tolerance deleted (AGENTS.md invariant 4: it hid the defect; a column none of the outputs covers is refused by name). Both routes now answer the 7 columns |
| CypherNativeEndsTest, CypherIslandIntegrationTest | `theNodesAtAWrittenRelationshipsEndsAreItsStartAndItsEnd`; one test of the island | a Variant cell was the variant's JSON text | read as a JSON string token the decoder handed WITH its quotes | rule 4 (the reader): the string token's value; no value moved |
| MetamodelMappingStoreTest, MetamodelQueryFunctionsTest | `classMappingByIdIsAQuery`, `extendsChainAsRows`, `classMappingsByClass` | the helper mapped the old door's `Scalar(null)` to `[]` (`v == null ? List.of() : List.of(v)`) | `Lean.of` first gave one row of a null cell, `[null]` | rule 5 (the view): `Scalar(null)` is zero rows, as the plan path writes it; the helpers now read `values()`; no value moved |

The mechanical rewrite (`t-b-code.py`, the forms of T-a plus: a 3-argument `Execution.execute(model, query, c)` ->
`Lean.run(model, query, null, c)`; `((Scalar) r).value()` -> `r.one()`; `.rows().get(i).values()` -> `.rows().get(i)`;
`col.pureType().typeName()` -> `col.type()`) touched 31 files; the six files above were switched by hand to
`Program.last` (the same model, body, runtime and connection; the reads unchanged). `ExecutionResultIntegrationTest`'s
`typedAccessorErrorMessage` (which judged the reader's refusal, not the product: the T-a audit) is replaced by
`valueAnswerHasOneColumnNamedValue` (the one column `value` a VALUE answer is written with, `[{"value": ...}]`).

**Left on the old door after T-b** (the counting rule above): 85 files, 149 lines. By package: lowering 16, exec 16,
resolver 12, integration 6 (the parity-lane files `CypherParityTest`, `CypherParityNativeTest`, `SqlParityTest`,
`StreamingIntegrationTest`, `CypherWritePlansTest`: T-c with the helpers; and `ExecutionResultIntegrationTest`, a false
hit: its own class name matches `ExecutionResult`), compiler/spec 5, testcases 4, sql/dialect 3, server 3, normalizer
3, compiler 2, the root package 13, and one each in testing (`Program`, which names `ExecutionResult` in a javadoc
until D) and compiler/element.
