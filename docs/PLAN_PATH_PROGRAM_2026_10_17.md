# The plan path program, 2026-10-17: everything executes through a plan

**The user's word (2026-10-17): "i think we should take over the plangen work and fix it here and make it actually
work"; "is it achievable for everything to always run against plan (in engine mode at least)"; "lets write it all down
and then actually do it end to end and lets go!"**

This document is the plan and the record. It continues `docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md` (the boundary,
its §8 the program as agreed on 2026-10-05, its steps 1 to 3 landed) and takes that program's step 4 -- the callers
switch and the old paths are deleted -- to its end: every execution in legend-lite is a plan made by the planner and
run by the model-free runner. The tenet it serves is the execution tenet of `AGENTS.md`: Java orchestrates, the
DATABASE executes. A runner that holds no model cannot compute a value, so moving every caller onto it is the
mechanical form of that tenet, and every value Java still computes today has nowhere to hide on the plan path: it goes
to the database or is refused by name.

## 0. The rulings this program rests on (the user, 2026-10-17)

| # | ruling | where it bites |
|---|---|---|
| R1 | Everything runs against a plan, in engine mode at least: the server's execute, the query service, the test runners, the PCT lanes, the corpus. | §7, §8 |
| R2 | Both judges stay. The host judge (World 1 of the charter's Clause 2c, the verdict channel) and the database judge (both sides composed into one verdict statement) both read the plan's results; they must agree. | §6 |
| R3 | The plan carries every type: the Pure type and multiplicity, the SQL type the database answers with, and the store's declared type -- each present or explicitly absent, never defaulted. | §3 |
| R4 | The lossless text carrier for a non-primitive value is Pure's own literal grammar, spelled by the database and parsed by Pure. JSON is not required anywhere the engine's API does not demand it. | §4 |
| R5 | The lite plan's representation stays JSON (the decision and its reasons: §2). | §2 |
| R6 | Nothing in this program computes a value outside the database. The database executes, the runner carries, the judges judge. | everywhere |
| R7 | The order: types first, the text carrier second (the audit of 2026-10-17). | §7 |
| R8 | Deletion is mandatory: the legacy executor, the legacy PCT route, the shadow plan and the Java struct-to-instance rebuild all go. A referee overlap -- the old path kept beside the new until the new is proven -- is allowed, and MUST end in deletion. | §7 |
| R9 | The corpus rule and the PCT rule are the same: no roster row and no pin moves unless a verdict genuinely changed, and both judges must agree for a pass to count. | §6, §7 |
| R10 | The engine-compatible plan is as compatible as it can be made: where the engine carries a fact (a column's declared type), ours carries the same fact, read from the plan, never spelled from a table. | §3 |
| R11 | A `let` lives in the database or nowhere (§5): dissolved by the compiler, or a temporary table in the body's workspace; never a value Java holds between statements. | §5 |

## 1. Where we are (measured 2026-10-17)

**Landed.** The planner makes lite plans (`PlanMaker`, `ExecutionPlan`, `PlanJson`; step 2 of the boundary program,
2026-10-09). The runner runs a plan with no model (`exec.PlanRunner`; step 3, 2026-10-10): its values are typed and
checked in the plan's own words, parameters bind through the plan's slots, the one designed hole (a placeholder's type
written by the runner at a recorded place) is `TypeHole`. The server's `executionPlan` endpoint renders the
engine-compatible plan shape; its `execute` still binds parameters through `PureV1Api.boundParameters` and runs the
legacy path (step 7's work). The MongoDB lane runs PCT
channel A end to end through the plan path (`PctPlanRoute`, `TypedQuery.Output.TDS_TEXT`, 2026-10-17): 1,145 of
1,249 tests, every pin named. A differential answers the same query through today's path and through a plan on DuckDB,
H2 and Postgres.

**Still on the legacy executor** (`Execution.execute` / `executeResolved` / `StatementExecutor`), by file in
`core/src/main/java/com/legend`: `server/QueryService.java` (the wire and streaming paths), `test/PureTestRunner.java`
and `test/ServiceTestRunner.java` (Pure test bodies, service tests), `CsvLoad.java`, the verdict arms
(`AssertVerdicts.java`, `DatabaseJudge.java`, `HostJudge.java`, `SqlTextVerdicts.java`, `AssertErrorNative.java`),
`BodyCompiler.java`, `PlanAllocations.java`, `PlanEnvelope.java`, `SeedSqlForms.java`, `exec/CanonRider.java`,
`exec/InstanceIds.java`, and three lowering-side callers (`builtin/Pure.java`, `lowering/Scalars.java`,
`lowering/CollectionLanes.java`). Twenty files; three of them matter (the query service and the two test runners), the
rest follow.

**What the plan carries today** (`executionplan/ExecutionPlan.java`): a parameter's name, Pure type, multiplicity,
enum values, bind type and hole spellings; a tabular result's column name, Pure type and SQL type (`TdsColumn`); a text
result's column name and Pure type only (`Column`); the typed SQL tree in memory (`Sql.statement`) but NOT in the lite
JSON ("a plan read back has none", `PlanJson`'s header); no store-declared type anywhere. The engine-compatible
renderer spells a column's `relationalType` from the Pure type through a table (`plan/UpstreamRelationType.
relationalSpelling`: every String and every enumeration `VARCHAR(1024)`, a Decimal `FLOAT`), with the comment "lite
has no physical provenance". The engine's own TDS header carries the declared type (`VARCHAR(30)`; the corpus goldens
show it).

**How upstream runs PCT** (read in `finos/legend-engine` at `3593037`, a blobless read-only clone at
`/home/user/finos/legend-engine`; spec and test input only, per the reference-checkout tenet). legend-pure's own lanes
(`testAdapterForInMemoryExecution`) are `$f->eval()`: no plan. legend-engine's relational lanes
(`core_external_test_connection/pct_relational.pure`, `testAdapterForRelationalExecution`) go through plan generation
every time: the test function is rewritten so its literal data becomes CSV-seeded tables (constant pre-evaluation off on
purpose), `executionPlan(...)` builds the plan, the setup nodes are prepended, the plan is bound to the Java platform,
serialised to the protocol JSON and run by `executePlanAsJSON` (the Java `PlanExecutor`), and the result JSON is read
back by the function's declared return type (`resultToType`): a List, Map, Variant or to-many primitive arrives as JSON
text in one cell and is parsed with `fromJson`; a class result through `processRelationalClassResult`. Upstream's DuckDB
manifest (`core_relational_duckdb_pct/.../EssentialFunctions_manifest.json`, `GrammarFunctions_manifest.json`) excludes
`testFindInstance`, `testFilterInstance`, `testEqNonPrimitive`, `testAssignNewInstance`, `testConcatenateMixedType` and
`testLetChainedWithAnotherFunction`: the instance-identity tests cross no wire upstream either.

**The corpus.** 2,798 `<<test.Test>>` functions of legend-engine's `core_relational`, consumed as data
(`spec/.../rcorpus/Corpus.java`), run by `PureTestRunner` statement by statement through `Execution.executeResolved`
after the package's setups; the gate is a roster per database (`spec/src/test/resources/rcorpus/*-roster.txt`: 108
known DuckDB failures, 14 skipped) that has not moved in weeks.

## 2. The representation: JSON stays (decided 2026-10-17)

The question put: "decide if you want new plan in json too or some other representation that might be
better/faster/cheaper/more bulletproof -- if json is already good then no need to change."

**JSON stays.** The reasons, measured against the alternatives (a binary encoding such as CBOR or protobuf, Java
serialisation, a textual DSL of our own):

1. **Nothing numeric travels as a JSON number.** A plan's values are the statement's text, typed parameter slots, and
   type spellings; the one place JSON is weak (Long and Decimal fidelity) never arises, so a binary format's one real
   advantage buys nothing.
2. **Plans are small** (kilobytes), made once per query and run once; encoding cost is not on any measured path.
3. **The writer and the reader exist** (`PlanJson`, format `legend-lite-plan`, versioned since 2026-10-08), every
   `_type` an enum constant read by an exhaustive switch, an unknown tag refused by name; its consumers are the runner
   (`exec/PlanSessions`) and its tests. A second format would be a second reader to keep honest.
4. **Inspectable and diffable.** A plan is read in a terminal, pinned in a test, and compared in a differential; the
   planner also runs in the browser (TeaVM) where JSON is the native carrier.
5. **The engine's protocol is JSON**, and the compatibility mode (the boundary program's last phase) is a projection of
   the lite plan; one carrier for both keeps the projection a function, not a translation.

**What makes it bulletproof is content and a gate, not a format**: the typed SQL tree written into the lite JSON (§3,
format version 5; the lite JSON is at 4 today), and a round-trip gate -- every plan the tests make, written to JSON, read back, and rendered again,
must render the same SQL on every dialect and run the same in the runner. The gate is the bulletproofing; a binary
format would not add to it.

## 3. The types in the plan (R3)

**Per result column, three facts**, each present or explicitly `absent`:

| fact | what it answers | who reads it |
|---|---|---|
| `pure` -- the Pure type and multiplicity, as the typer names it | how a value is decoded and printed: the TDS header, Pure's print forms, the host judge's declared-kind equality | the runner, the judges, the text wires |
| `slot` -- the SQL type the database answers with (the lowering's type fact) | the bind kind, the hole spelling, Decimal scale, DATE against TIMESTAMP (the `datePart` fix of 2026-10-17 was this slot type deciding the text) | the runner, the dialects' holes |
| `store` -- the column's declared type in its `###Relational` store, when the value is a store column read through | the engine renderer's `relationalType` without a table; widths and scales the engine shows; the DDL of setup steps; the template a dynamic pivot's value columns inherit | the engine renderer, the corpus goldens, the pivot mode |

A computed column has no store type and says `absent`. The planner asserts the three agree (a store `VARCHAR` behind a
Pure Integer is a model defect the typer refuses; the plan is where the compiler's facts are asserted one last time)
and refuses by name when they do not: invariant 4, no defaulting, on the plan.

**The engine renderer reads `store` where the engine carries the physical type.** Measured on the recorded 4.145.0
answers (`core/src/test/resources/upstream-api/e8-groupby-sort.json`, `e9-groupby-sort.json`; the audit of
2026-10-17): the plan's `resultColumns[].dataType` is the store's declared type for a column read through
(`VARCHAR(32)`) and `""` for a computed one; the TDS builder's `relationalType` is the engine's spelling of the PURE
type even for a store column (`VARCHAR(1024)` for that same `VARCHAR(32)` column; `pureTypeToDataTypeMap`,
`pureToRelational.pure:48-56`, probed 2026-09-27). So `resultColumns.dataType` reads `store` (the parity test's
recorded difference 2 closes) and the builder keeps `UpstreamRelationType.relationalSpelling`, which is the engine's
own rule, not a lite guess; an unprobed type stays refused.

**Parameters** already carry theirs (Pure type, multiplicity, enum values, bind type, hole spellings).

**The typed SQL tree in the lite JSON** (format version 5): the MIR (`SqlQuery`, `SqlSource`, `SqlExpr`, `SqlAgg`,
`SqlType`, `DateFmt`, `TypeFact`) written as JSON, every variant its own `_type`, read back by exhaustive switches. A
plan read back is then fully typed: re-renderable for another dialect, diffable, inspectable, and the engine protocol
renderer becomes a projection of the lite plan (the boundary program's §8, "the lite JSON carries ... the typed SQL
tree"). The gate: §2's round trip.

**Dynamic pivots** need one more piece than the types, because on a text wire the database writes the header line
itself and a single static statement cannot name columns it does not know. The shape is a two-plan sequence: a PROBE
plan that asks the database for the distinct pivot values, then the main plan with an IDENTIFIER HOLE the runner fills
from the probe's rows -- the mechanism the type hole already uses (the dialect writes nothing at a place the writer
records; the runner writes there, before it prepares the statement, what the plan says). The pivot columns' types are
the template column the plan carries (`pure`, `slot`, `store` of the aggregate). The database computes every value;
the runner writes names where the plan recorded a hole. Today's refusal ("a pivot's columns are known only once the
database is asked") retires with the sequence. (Corrected in the audit of 2026-10-17: "names from the result" alone
is not enough.)

## 4. The text carrier for non-primitive values (R4)

**The problem.** On the text route (the TDS text output, the PCT lanes through the plan) a value root whose type is not
a primitive -- a class instance, a List, a Map, a Pair, `Any`, a `Type` -- has no written form, and upstream's TDS
reader (`TestTDS` in `legend-engine-pure-runtime-java-extension-shared-functions-relation`) refuses any header column
whose type is not a primitive ("Pure Type: 'X' is not supported yet", the text behind 7 MongoDB pins). The legacy route
passes such tests only through ~400 lines of Java rebuilding instances from JDBC structs (`structToInstance`, the PCT
audit's largest compensation): value work in Java, the opposite of R6.

**The carrier: Pure's literal grammar, spelled by the database, parsed by Pure.** The forms:

| value | the cell's text | how it is spelled | how it is read |
|---|---|---|---|
| a class instance | `^my::CO_Person(firstName='Fabrice', lastName='Smith')`, nested instances and collections inside | the TDS writer's concatenation over the instance's columns (the graph shape the JSON wire already lowers); on MongoDB the nested-document writer that writes the Variant JSON today, with Pure's brackets | `compileValueSpecification(text).result->reactivate()` (both natives on the classpath: `core_functions_unclassified/meta/compileValueSpecification.pure`, `platform/.../reactivate.pure`) |
| a List, a Pair, a Map | `list([1, 2])`, `pair(1, 'a')`, `newMap([pair('k', 1)])` | the same concatenation over the collection's elements | the same |
| an `Any`-typed value | each element in its own literal spelling by the type fact of its branch: `1`, `1.0`, `1.0D`, `'a'`, `%2015-01-01`, `true` | the TDS writer, by the lowering's type fact per branch (a union's branches are typed) | the same |
| a `Type` or another model element | its path, `my::TestClass` | a string literal | the same; it reactivates to the element |

The adapter already bypasses the TDS reader for value roots and reads lines by the function's return type; a class,
`Any`, `Type` or Pair return type takes the new branch. The input direction already uses this form: the adapter writes
captured instances into the query as `^Class(...)` (`objectToGrammar`, `CapturedInstance`).

**What stays named.** The identity tests (`find` and `filter` returning the instance passed in, `eq` on instances,
`deactivate`, a `new` instance's generic type) compare by reference; no wire carries that, upstream's manifests exclude
the same tests, and they stay pinned by name on every lane with that evidence. The `zip` of empty lists ("Type argument
mismatch for the class Pair") is a typer defect about an empty list's type arguments, fixed in the compiler, not a
carriage question.

## 5. A `let` lives in the database or nowhere (R11)

A Pure test body is not one expression: setups, `let`s, several asserts. On the plan path a body is a SEQUENCE of
plans (the `Sequence` node exists): the setups as DDL and DML plans (`SqlDdl`, `SqlDml`, the session tables and
`SetupStep`s already there), then the statements. A `let` is a name for a value, and the only question is where that
value lives between statements. The tenet allows two answers:

1. **Nowhere: the compiler dissolved it.** `let x = <expr>;` then `assert($x->size() == 3)` becomes
   `assert(<expr>->size() == 3)`: the expression substituted into every statement that uses the name, the database
   computing it where it is used (G½'s β-inlining, applied to statement lets). Inside one statement the uses share a
   CTE; across statements the database recomputes, which is correct for anything deterministic. The default.
2. **In the database: materialized.** The let's own plan runs `CREATE TEMPORARY TABLE __let_x AS <expr>` in the body's
   private workspace (bodies with inline CSV seeds already get one), and every later plan reads it. The value never
   leaves the database. Required when the value must be computed exactly once (a `now()`, a `generateGuid()`, a side
   effect); chosen by the planner when a relation is reused by many statements and recomputing would be wasteful --
   a plan-time choice, which is planning, not Java doing work.

**Never in Java.** No value is fetched after one statement and bound into the next (legend-engine's allocation node
does exactly that; it is a carriage site and we do not build one). The runner runs the sequence and carries nothing
but the final result. A let that can be neither dissolved nor materialized is refused by name; none is known.

**Plan-time lets** are a different thing: a body that writes `let plan = executionPlan(...)` and asserts on the plan's
SQL text binds a value the PLANNER produces (a plan, a SQL text, a type), read only by plan-time verdicts
(`SqlTextVerdicts`). Those live in the planner, as they do today; they are not runtime carriage.

The planner picks between 1 and 2 from two facts it has: whether the expression is deterministic, and how many
statements read the name. The judges (§6) do not care which was chosen; they care what they are handed. This is what
the channel B homework sized (`docs/MONGODB_PLAN_2026_10_16.md` §9: single- against multi-statement bodies) and what
`docs/DEFERRED_TEST_EXECUTION.md` charters.

## 6. Both judges on plans (R2)

The charter's Clause 2c (ratified 2026-08-19) names two worlds: World 1, the host adjudication layer (`PureAsserts`,
the verdict channel, pinned by `VerdictChannelRegisterTest`), and World 2, the compiler; verdicts are World 1's job,
in-query computation World 2's, neither reimplements the other. Both judges stay:

- **The host judge** receives each side as a plan's result and decodes it by the plan's declared result type (the Z2
  ruling's carrier-decode bridge), then compares. Nothing computed; read and compared.
- **The database judge** composes both sides into one verdict statement; on the plan path that statement is a plan whose
  one row is the verdict.
- **The agreement gate.** Every assert gets two verdicts from two routes; a disagreement is a finding on a named test
  (a verdict statement's composition bug, a host decode misreading a carrier), never a quiet wrong pass. This is the
  parallel-referee role the host judge's javadoc already describes, stronger with both arms on plans.

## 7. The callers (R1): the order, and what is deleted

1. **Types in the plan** (§3): the three facts per column, the engine renderer on `store`, the typed tree in the lite
   JSON (format 5), the round-trip gate. Lands first because every later step reads it.
2. **Printed values** (PARK-24, the boundary program's step 3b): Float arithmetic in decimal, a whole Float's `.0`, a
   Decimal literal's scale, a DateTime's text. The user's ruling (2026-10-10): an answer is Pure's value, its text the
   served API's. The value root's date precision (2026-10-17) was the first slice.
3. **The text carrier** (§4): the forms, the adapter's reader, a differential case per form on DuckDB against MongoDB.
4. **Dynamic pivots** (§3's last paragraph): names from the result, types from the template.
5. **A `let` in the database or nowhere** (§5): dissolve or materialize, never carry.
6. **Both judges on plans** (§6), the agreement gate.
7. **The query service**: the wire and streaming paths plan and run; `Execution.executeWire` / `executeStreaming`,
   `Execution`'s `wireOn` / `streamOn`, `PureV1Api.boundParameters` and `ConnectionResolver`'s reading of the compiled
   model are DELETED (the boundary program's step 4, rule 15). The differential between the two paths is the gate, and
   is itself deleted with the old path.
8. **The test runners**: `PureTestRunner` and `ServiceTestRunner` emit a sequence of plans per body; `CsvLoad` a DML
   plan; the small seams follow.
9. **The PCT lanes**: `PctBackend.throughThePlan` for every lane, the DuckDB lane first (its shadow run agreed on 81%
   before this program; the pins say the rest); the legacy route (`PctExecuteNative`'s query-service path,
   `ExecuteLegendLiteQuery`'s struct-to-instance bijection) deleted; `PctShadowPlan` deleted with it.
10. **The corpus**: the 2,798 bodies through plan sequences, both judges; the rosters are the gate and no row moves
    unless a verdict genuinely changed.
11. **The deletions**: `StatementExecutor` and what only it called; `JavaEvalLedgerTest`'s pins only go down; the
    `exec` guard reaches zero (the boundary program's §8: `exec` depends only on `//base`, `//json`, the plan records and
    `java.sql`).

**What never goes through a plan, and should not**: compiling a model, the language server, diagrams, tests that
assert a compile error, the corpus buckets that exercise the engine's own machinery (routing internals,
pre-evaluation, protocol transforms, dynamic compilation), and the PCT identity tests.

## 8. How each step lands

- Announced here (a dated line in §9) and in `docs/IN_FLIGHT.md`'s thirteenth line before it starts.
- The local gate (`bazel test --lockfile_mode=error //gates:local`), the step's own judges named in the step, one full
  CI on the branch, then pushed; the GATES entry names what judged it.
- A guard pin, roster row or allowlist entry moves only with a dated justification naming this program.
- Every deliberate difference from legend-pure or legend-engine is a `docs/SEMANTICS_REGISTER.md` row; "deliberate
  differences" that are defects are fixed, not registered.
- A plan never references a harness; the plan path serves the product, the PCT and the corpus are its judges.

## 9. Status

- **2026-10-17** -- the program written (this document), the rulings R1 to R6 recorded, the representation decided
  (JSON stays, §2). Step 1 announced: types in the plan.
- **2026-10-18, step 2, the matrix measured** (GATES "The plan path program, step 2 begins"): `OutputMatrixTest`, 192
  cells on DuckDB and H2; 116 write Pure's text, 76 pinned by name (the JSON wires' native number tokens, H2's
  exponent forms, every DateTime text but the TDS text's whole second and the TDS text's millisecond column, a
  binder error on DuckDB's CSV wire for a value root's fractional date-time). The landings that follow delete pins:
  the Float's text owner, the Decimal's, the DateTime's, then `pure/v1`'s text.
- **2026-10-18, step 2 announced: the printed values** (PARK-24; `docs/OUTPUT_LAYER_DESIGN_2026_10_10.md`, whose
  five steps this step is): first the matrix, measured -- a test recording, for each kind (a whole, a fractional and
  an extreme Float; a Decimal literal and after arithmetic; a DateTime with no, three and nine fraction digits; a
  StrictDate) and each output (CSV, JSON, a stream of JSON rows, Pure's TDS text, `pure/v1` execute) on DuckDB and H2,
  lite's text beside interpreted Pure's (`probes/engine-reference/pure-interpreted-results.txt`) and the engine's;
  today's deviations pinned by name and shrink-only, so each later landing (the Float's text owner, the Decimal's, the
  DateTime's, `pure/v1`'s) is judged by the pins it removes.
- **2026-10-18, step 1 landed** (the three facts per column, the typed tree in the lite JSON, the round-trip gate;
  GATES "The plan path program, step 1"). The tree: `MirJson`, every MIR variant its own tag, written into the
  plan's `tree` and read back; the gate: `testcases/PlanRoundTrip`, every plan the plan maker's and the MongoDB tests
  make reads back equal and renders the same statement. Measured on the way: a column no output is named after is
  one of three said things (a pivot's generated column, a nested column flattened into prefixed outputs, a value
  root's one output), found by the integration suite's first run.
- **2026-10-17, step 1a** (the three facts per column): `ExecutionPlan.Column` carries `type`, `multiplicity`,
  `slot` and `store` (null when computed); `ColumnFacts` reads the slot from the lowered query's outputs and the store
  type by provenance (a projection reading a base table's column through subselects, CTEs, joins and a pivot's keys;
  `SqlExpr.StoredRead` carries it outright); `PlanColumns` joins them with the typer's columns; `PlanJson` format 5;
  `QueryPlan` carries the columns and `PureV1Api`'s `resultColumns.dataType` is the store type, so the parity test's
  recorded difference 2 is closed and the engine's recorded plan is compared verbatim there. Judged by
  `PlanMakerTest.aColumnCarriesItsThreeTypeFacts`, `PlanJsonTest`, `PureV1ApiTest.e9`, the local gate's core lane and
  `//core:mongodb_plan_test`.
- **2026-10-17, the audit with the user** -- R7 to R11 recorded; four corrections (the server's `execute` is still on
  the legacy path; the lite JSON's next version is 5; a dynamic pivot is a probe plan and an identifier hole; a `let`
  is dissolved or materialized, never carried). Step 1a starts: the three facts per column, the engine renderer on the
  store's declared type.
