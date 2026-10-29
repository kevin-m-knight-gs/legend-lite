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
| (none) | -- | -- | -- | every expectation held: 2,232 of 2,232 pass on the LEAN reader with the three rules above; the mechanical rewrites changed the FORM of 11 files' reads (`asCollection().values()` -> `values()`, `returnType().typeName()` -> `columns().get(0).type()`, `asGraph().json()` -> `json()`, `Row` -> `List<Object>`, the service's materialized `execute` -> `Lean.run`), never a value |

`ExecutionResultIntegrationTest` (9 tests) asserted the old door's result VARIANTS (`Scalar`, `Tabular`, the typed
accessors' errors, `returnType` non-null): rewritten to the LEAN answer's SHAPES (`VALUE`, `RELATION`), the plan's
column types (`Integer`, `String`) and the reader's refusal of the wrong shape -- the same queries, the facts the
product's output carries.
