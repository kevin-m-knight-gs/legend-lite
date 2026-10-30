# The planner-driver seam program (2026-10-30): a test body as one plan, the driver an interpreter

> **Status: PLANNED, NOT RATIFIED. No code.** This document is the end-to-end plan the user asked for on 2026-10-30
> ("Let's definitely do full detailed end to end plan - with homework and research, not guesses"). Every claim about
> lite's code is a measurement in `docs/planner-driver-seam-homework/CODE_MEASUREMENTS_2026_10_30.md` (cited below
> as **CODE §n**) and every claim about upstream is a line read in the pinned legend-engine and legend-pure sources,
> in `docs/planner-driver-seam-homework/UPSTREAM_READING_2026_10_30.md` (cited as **UPSTREAM §n**). Nothing in
> either was recalled from memory. The user rules on §6 before any step is built; each step is then planned with
> the user in its own brief, built, chained, audited, folded and pushed as the seam-and-wall program's steps were
> (`docs/result-boundary-homework/DECISIONS_LOG.md` D70 to D73).
>
> **Prerequisite:** the seam-and-wall program (steps A, B1, B2, C; PR #82) lands first. This program starts on a
> fresh branch from `main` after it.

## 0. In plain words

A product query is a VALUE the planner makes once (`TypedQuery.plan` → `PlanMaker.plan` → one `ExecutionPlan`) and
the runtime executes (`Execution.run` → `PlanRunner`). The planner never sees a session; the runtime never sees the
compiler (D72, the wall). That split is complete and is not what this program is about.

A TEST BODY (a PCT test, a corpus test, a service test: lets, asserts, effects, then an answer) is NOT a value today.
The body driver (`com.legend.driver`) improvises a PROCEDURE: it takes the query's own compiler off `TypedQuery`
(`specs()`, `resolved()`: CODE §1a, §1b), and for every statement and every assert side it re-runs the inliner,
the store resolver, the lowerer and the renderer itself (14 inliner sites, 6 resolver uses, 2 lowerers, 20+ renders:
CODE §2b), runs the statement, holds the rows, and plans the next statement against them. Four probes read the
live session to finish planning (CODE §3). One loop of 247 lines drives a 2,321-line driver and ~4,350 lines of
judges (CODE §5e).

Upstream shows the right shape, read in its sources (UPSTREAM §3): a multi-statement lambda is planned in ONE pass
as a `SequenceExecutionNode`; each `let` is an `AllocationExecutionNode`; each later statement is planned against a
placeholder carrying only the SHAPE of the let-bound value (its type, or its TDS columns), never its value; the SQL of
a later statement carries `${var}` holes the executor fills at run time from one variable map. No statement's result
is needed to plan the next. Planning never opens a connection (UPSTREAM §6: zero `executeInDb` calls in every
plan-generation file).

**The program, in one sentence:** the body becomes a plan the planner makes once (upstream's one-pass sequence, in
lite's own vocabulary, which already holds `Sequence`, `Update`, `Effect`, `Check`, `TdsResult` and `Keyed`), and
the driver becomes an interpreter over it (the plan runner plus the judges at the verdict steps), so that after
planning no compiler runs and no probe is sent.

What upstream does with the test body itself is NOT available to lite: legend-pure runs a PCT test function in its
Pure interpreter and plans only the lambda handed to the adapter; legend-engine's mapping and service tests plan the
query once and evaluate assertions in Java as data (UPSTREAM §5). Lite has no host interpreter by its execution
tenet (`AGENTS.md`; D29, D30), and its judges are SQL verdicts or a typed lattice over rows the database returned.
So lite does the nearest thing upstream itself does for a multi-statement lambda: one plan for the whole body. That
divergence is already the ruled form of track B (D29); this program changes WHO makes the plan and WHEN, not what
is compared.

## 1. The homework, in ten facts

Lite (CODE):

1. The driver reaches two of `TypedQuery`'s seven widened internals, `specs()` and `resolved()`; the other five
   (`lower`, `Resolved`, `root`, `resolve`, `lowerer`) are `PlanMaker`'s own (CODE §1h). Of `PlanMaker`'s four
   public statics, `plan` is the door's and `keyed`, `sql`, `serversFor` are the driver's (`JudgePlans`, one
   `BodyDriver` site; CODE §1i). The "driving of compiler phases through the internals" is, measured, the driver
   taking the compiler and the typed body off the query and re-running G½, H, I, J itself.
2. The driver compiles mid-run for eight measured reasons (CODE §2d): lets bound to earlier results (spliced frames,
   staged tables), helper bodies typed lazily at inlining, deferred TDS text, the metamodel system route, the four
   dynamic shapes read from the session, provisioning before planning (seeded tables' wire types), effects sent
   between statements, and engine-text plan asserts.
3. The four probes reach the database only through `PlanRunner.columns`/`describe` (CODE §3d), and re-enter planning
   as: a callback into `lowering.Render.resolveAllDeferredTds` (ColumnProbe), a `SchemaReferee` into
   `resolver.RawGridSchema.stamp` (GridProbe), a plan edit after lowering (DynamicPivot), a re-typing of outputs
   after a DESCRIBE (WireProbe).
4. The product path has a plan node for ONE of the four probe shapes: the dynamic pivot, as `ExecutionPlan.Keyed`
   run by `PlanRunner.keyed` (CODE §3e). A deferred TDS is REFUSED by the renderer there. A late-bound raw grid is
   NEITHER stamped NOR refused there: the plan carries zero columns (finding F1 below). A wire-decided kind is carried
   as the compiler's slot, neither probed nor refused.
5. The plan vocabulary is six node kinds with a runner arm each; `Keyed` inside a `Sequence` is a named refusal
   (`exec/PlanRunner.java:294-295`, `:361-362`; CODE §4).
6. The oracles: the corpus (`//gates:corpus_duckdb`, `corpus_h2`: the committed rosters
   `spec/src/test/resources/rcorpus/*-roster.txt`), channel B (`//gates:pct_channel_b`), and five core lanes (root,
   driver, exec, integration with its two twins, judge); the PCT main and heavy lanes already run the PRODUCT path
   (`PctPlanRoute`) and are untouched by this program (CODE §7a). A deferred TDS has no core test at all (CODE §7b).

Upstream (UPSTREAM):

7. The plan is a typed tree of data-only nodes; SQL text (`SQLExecutionNode.sqlQuery`) and column typing (the
   wrapping instantiation node's `TDSResultType`) sit on two nodes; a `let` is a first-class node
   (`AllocationExecutionNode.varName`) (UPSTREAM §1).
8. The executor is one visitor with one mutable `Map<String, Result>`; a let is stored as a `Result` (constant,
   deferred, streaming); later SQL reads it by text substitution at execution; a let-bound TDS referenced as
   `(${var})` is loaded into a temp table at that moment (UPSTREAM §2, `RelationalExecutor.java:284-300`).
9. Exactly one plan-time-unknown shape exists upstream, the dynamic pivot: a flag on the SQL node
   (`isResultColumnsDynamic`) and a run-time `ResultSetMetaData` read that replaces the column list and infers Pure
   types from database type names (UPSTREAM §4). Lite's ruled form is the keys probe plan (`Keyed`; register rows
   S106 to S110, the user 2026-10-20) and stays.
10. Upstream never plans a test body (UPSTREAM §5) and never reaches a database while planning (UPSTREAM §6):
    every run-time dependency is carried as a hole, a flag, a conditional node or a keyed set of plans.

## 2. Findings posted to the user (defects and gaps the homework found)

- **F1 (invariant 4, the product path).** A raw SQL grid of unknown schema on the product path is neither stamped
  nor refused: the lowering emits `SqlSelect.starOf(RawSql(...))` with the outputs of a late-bound schema, which are
  EMPTY, and `PlanMaker.typed` builds a plan with zero columns (CODE §3e; `lowering/Lowerer.java:2386-2388`,
  `plan/PlanColumns.java:44-49`). No refusal names it anywhere in `planner/`, `TypedQuery` or `Compiler`. This is a
  defaulting branch by omission. Proposed: a refusal by name on the plan side (step S0), its own commit, before
  anything else. **The user's go is needed.**
- **F2.** A deferred TDS (`SqlExpr.DeferredTdsString`) has no test under `core/src/test`; its only oracle is the
  corpus and channel B (CODE §7b). Step H1 measures its origins before step S3a touches it.
- **F3.** `TypedQuery.planStreaming` has zero callers in main and test (CODE §6a). Dead public API; deleted in S5.
- **F4.** `GridProbe.probeNames` has no caller (CODE §3b). Deleted with GridProbe in S3c, or earlier as a nit.
- **F5.** `Keyed` inside a `Sequence` is refused by name, so a write program whose answer is an uncast dynamic pivot
  is refused on the product path today (CODE §4). Step H6 counts whether any body needs it; S1 lifts the refusal
  if so (a per-step arm, not a default).
- **F6.** The test-data generator's fold reads the session before planning the next statement (`foldCensus`, CODE
  §5a; the named residue D30 said to revisit as "a two-plan body"). Step S2j.

## 3. The target shape

### 3.1 Upstream's model, in lite's vocabulary

| upstream (read) | lite today | lite after this program |
|---|---|---|
| `SequenceExecutionNode`: children in order, the last one's result (UPSTREAM §1) | `ExecutionPlan.Sequence(steps)`: one target, one transaction if any step changes; the write program's form (CODE §4) | the same record, now the BODY's root: every statement one step |
| `AllocationExecutionNode(varName)`: run one child, store its result under the name (UPSTREAM §1, §2) | a staged let is `Update` + a temp table in a write program (`PlanMaker.program`, CODE §1d); in a body the driver's `letPrefix`, `execFrames`, `queryLets` (CODE §5b) | `Sequence.Allocation(name, step)`: a let held under its name, its rows a staged table or a CTE the readers splice (lite's compile-time splice is the ruled form of lets, the plan path program §5) |
| `PlanSetPlaceHolder(name, tdsColumns)` / `PlanVarPlaceHolder(name, type, mult)`: later statements planned against the SHAPE of a let (UPSTREAM §3) | `PlanMaker`'s `with(lets, stmt)`: each statement resolved with the lets before it (CODE §1d) | the same mechanism, one pass over the whole body, in the body planner |
| `${var}` holes filled at execution by FreeMarker; `(${var})` loads a temp table (UPSTREAM §2) | a staged table's name written at planning; a slot bound at run time (`Binding`) | unchanged: lite splices at planning and binds values through slots; no text templating is added (divergence already in force: the plan path program §0) |
| `isResultColumnsDynamic` + `ResultSetMetaData` (UPSTREAM §4) | `Keyed` for the pivot (ruled S106 to S110); GridProbe, ColumnProbe, WireProbe for the rest (CODE §3) | `Keyed` stays; a `TdsResult` may declare its columns WIRE-DECIDED (the runner reads the column list, or a column's kind, from the wire at decode time; S3b, S3c) |
| `FreeMarkerConditionalExecutionNode` (UPSTREAM §1) | none | none, unless H4 finds a body needing one (none expected: lite's conditionals are SQL `CASE`s by the world-map tenet) |
| the PCT body in the Pure interpreter; only the adapter's lambda planned (UPSTREAM §5) | the body driver plans and runs statement by statement with judges (CODE §5) | the body planned ONCE as one `Sequence`; the judges its `Verdict` steps; the driver the interpreter (no host interpreter: the execution tenet, D29) |
| mapping and service tests: one plan, assertions evaluated in Java as data (UPSTREAM §5) | `ServiceTestRunner` → `Execution.runProgram` (CODE §6a) | the same body planner |

### 3.2 The plan vocabulary after (step S1)

Added to `executionplan/ExecutionPlan.java`, each a record with a runner arm and a JSON round trip, none with a
`default`:

- `Allocation(String name, Node value)` inside a `Sequence`: upstream's allocation node. The runner runs `value` and
  holds its answer under `name` (rows as a staged table the plan itself created with an `Update`, or the typed rows
  held in the session's state for a host-judge side; H4 decides which forms exist).
- `Verdict`: an assert as a step. Two forms, the two judges (D7 keeps both): `Verdict.Database(Sql verdict)`, one
  cell the database computes (today's `VerdictBatch` statement); `Verdict.Host(TdsResult left, TdsResult right,
  Arm arm)`, both sides' typed rows streamed to the host judge's lattice (`com.legend.judge`). Both carry the
  message facts the listener reports. `ExecuteOptions.JudgeMode.BOTH` runs both and the agreement arm.
- `TdsResult.columns` may be WIRE-DECIDED: a column whose `Type.wireDecided` kind (String, Number, Any:
  `compiler/element/type/Type.java:693-696`) is decoded by the kind the wire reports, recorded on the column
  (`Column.slot` gains the fact; no DESCRIBE round trip). Upstream's "the plan is the authority; the wire decides
  only where the plan flagged it" (UPSTREAM §2, `RelationalResult.java:243-256`).
- `Keyed` inside a `Sequence` (F5) if H6 counts a body that needs it.
- A step's `Target` may differ inside one `Sequence` where H7 shows bodies that mix the platform (a verdict's
  canonical rows) and a declared database; otherwise the one-target rule stays and the mixing refusal stays.

Nothing is removed from the vocabulary. The legacy plan document (`plan/PlanNode`, the engine-shaped JSON) gains
`AllocationExecutionNode` / `SequenceExecutionNode` kinds for free when the read/write side is built (deferred by
the user; not this program).

### 3.3 The body planner (step S2): one pass, each statement a step

`planner/BodyPlanner.plan(TypedQuery, runtime, options) → ExecutionPlan` (the name is provisional), grown out of
`PlanMaker.program` (the write program's `Sequence`, CODE §1d), which already does upstream's fold: each statement
resolved with the lets before it, a let bound to rows staged. The kinds, each a step, in the order H4's census ranks
them (provisional order here, by the driver's branches at `driver/BodyCompiler.java:71-176`, CODE §5a):

| kind (the driver's branch today) | the step after | upstream's twin |
|---|---|---|
| a value statement (`prepareValue` + `runValue`) | `TdsResult`/`Check`, last one the answer | the last child of the Sequence |
| a value let, a handle let (`letPrefix.add`, `PlanAllocations.registerHandlesIn`) | `Allocation` over the value's step; readers spliced at planning | `Allocation` + `PlanVarPlaceHolder` |
| an alias let (a frame shared) | no step: a substitution at planning | a let over a variable, folded |
| a staged let (`stage()`, `StagedRows.boundTo`) | `Update` (create as) + `Allocation` naming the table + `Update` (drop) at the end, as `PlanMaker.program` does | `Allocation` over a TDS + `(${var})` temp table |
| an effect (`executeInDb`, DDL natives, scripts; `collectEffect`/`sendScript`) | `Effect` steps in place (the segment script's batching kept as one `Effect` with several statements where the dialect sends scripts) | `RelationalBlockExecutionNode`'s leading nodes (UPSTREAM §5, the PCT adapter's `setupDatabase`) |
| an assert (`AssertVerdicts.tryAdjudicate`) | `Verdict.Database` / `Verdict.Host` / both | none (upstream judges in Pure or Java); lite's ruled form D7, D29 |
| a frame let (`execute(...)`, `legendQuery`; `buildFrame`, the splice hook) | `Allocation` whose readers splice the frame's chain at planning (today's "re-plan of a spliced chain" done once, by the planner); the eager run dropped where nothing reads rows before the end (H4 counts; each dropped run posted) | `Allocation` over a `RelationalTdsInstantiationExecutionNode`, `DeferredRelationalResult` |
| the metamodel system route (`routeSystemDatabase`) | the step's `Target` is the system database with its setup (the product path's form: `PlanMakerTest.theMetamodelStoresTablesAreThePlansSetupInProcess...`) | none |
| an engine-text plan assert (`SqlTextVerdicts`, `engineSql`) | a `Verdict` over two constants, the text lowered at planning by the engine-text dialect (planning-side work, no session) | none |
| the generator's fold (`foldCensus`, D30) | S2j: a two-plan body — the generator's plan run first, its facts handed to the body planner as DECLARED inputs (the one place a plan feeds another plan; posted to the user as a divergence row if any shape must stay) | none |
| `print`, `$plan` navigation, `planToString`, `toSQLString`, `assertError` (D30's Java residue) | unchanged: plan-time values or carriage of an error; each stays pinned in `JavaEvalLedgerTest` | none |

The body planner is a function over immutable values: it takes the typed query and returns a plan. It holds no
session, no probe, no referee. `plannerIsExecutionFree` already forbids it to reach `exec`, `driver`, `judge`; a
new rule `theBodyPlannerHoldsNoSession` pins that no type of `exec` appears in its signatures.

`JudgePlans` (CODE §5c), which BUILDS the judges' plans, is planning work and moves into `planner/` (its `run`
helpers stay in the driver). `VerdictSql`/`VerdictQueries` already live in the compiler.

### 3.4 The driver after (step S4): an interpreter

`driver/BodyDriver.execute` becomes: `plan = BodyPlanner.plan(typed, runtime, options)`; `PlanRunner.sequence(plan,
session, listener, answer)`; the verdict steps call the judges (`com.legend.judge`) over the rows the runner
streams; the listener gets the verdicts; the last step's answer goes to the caller's sink (`Execution.Answer`,
unchanged). `BodyCompiler`, its `Segments`, `ExecEnv`'s `queryLets`/`planRows`/`frame`, the four probes, the
inliner/resolver/lowerer constructions and the 20+ renders leave the driver. What remains of `BodyDriver` is the
interpreter's glue and D30's residue, each pinned.

After planning NOTHING compiles: a new ArchUnit rule `theDriverCompilesNothing` (no class of `com.legend.driver`
depends on `compiler.spec..`, `resolver..`, `lowering..`, `sql.dialect..`), and `tools/deps/core-layers.txt`'s driver
line loses `compiler`, `compiler_element_type`, `lowering`, `resolver`, `normalizer`, `parser`, `lineage`, `plan`,
`sql_dialect`, `validation`, `builtin`, `protocol` (the exact list is the measurement at S5; the target is "the
runtime's libraries plus `:planner`'s doors, `:judge` and `:execution_plan`").

### 3.5 Where planning may still touch the database: one question, not four probes

By construction everything in §3.3 is planned without a session, as upstream's §6 shows is possible for every
shape but one. The remaining question is the RAW GRID whose columns a LATER statement names (`columnNames`, a typed
read: `exec/ExecuteInDbProbeCountTest.columnNamesDemandsProbe`, CODE §7b). Upstream has no planned form for it at
all (`executeInDb` is a Pure-runtime native there, UPSTREAM §5); its dynamic-pivot answer (read the columns from the
wire at run time) covers the UNDEMANDED grid (`undemandedGridSingleQuery`) but not a statement typed against them.
Two honest forms, decided by H2's count and the user:

- **(i) declared in the program** (upstream's shape for every other dynamic case: the program casts, the plan knows):
  a typed `executeInDb` overload or a cast names the columns; the probe goes; a body that names columns it did not
  declare is refused by name. Zero roster movement only if H2 finds every demanding body declares or can declare
  (each one posted).
- **(ii) a schema oracle door**: `planner.SchemaOracle { List<Type.Column> columnsOf(sql, writtenFor) }`, an
  interface the PLANNER owns and takes as an argument; the product path passes the refusing one, the test runner
  passes one backed by a `PlanRunner.columns` probe on the body's session. Planning still asks the database in test
  bodies, through one named door the planner declares, instead of a referee threaded through the resolver. This is
  the hack-free form of today's `RawGridSchema.SchemaReferee`, and the ONLY place a session would remain upstream of
  a plan.

Recommendation: (i) where H2's count is zero or every case can declare; (ii) otherwise, as a named, pinned residue
(`JavaEvalLedgerTest`), with each demanding body a row the user rules.

### 3.6 The boundary at the end (step S5)

The widenings step C paid for (D73's "cost") revert by construction: the driver no longer calls `specs()` or
`resolved()`; `keyed`, `sql`, `serversFor` are called by the body planner inside `planner/`; `TypedQuery`'s
internals move INTO `planner/` (`TypedQuery` becomes a thin door delegating to one public entry) so they are
package-private again. `PlanMaker` and `BodyDriver` keep one public entry each (`plan`, `execute`), which
`theDriverHasOneCaller` and `plannerIsExecutionFree` already pin. The JPMS question (a `module-info.java` exporting
`com.legend` only) is then about MECHANICAL guarantee, not about hiding anything: the user decides whether the one-day
spike (does rules_java 9.9 compile a module under Bazel without breaking the product jars?) is worth it (§6 Q4).

## 4. The steps, ordered and sized

Each step: its own brief agreed with the user, the full local chain, an independent read-only audit, the fold, the
records (GATES, IN_FLIGHT, this §8, the decisions log, register rows where a spelling or an outcome moves), one
commit, one push. **The oracle of every step is zero roster movement** on `corpus_duckdb`, `corpus_h2`, channel B
and the five core lanes, plus the statement-origin census (`StatementOrigin.PROBE` shrinking to zero by S3,
`WIRE_RETYPED`/`WIRE_SLOT_SKEW` equal before and after S3b). A step that moves a roster row stops and posts the row.

| step | what | size | oracle and guards that move |
|---|---|---|---|
| **H** | the homework completions, read-only, lanes measure only: **H1** deferred TDS registrations by origin (pivot vs other) on the corpus and channel B; **H2** raw grids demanded vs undemanded, and whether each demanding body declares its columns; **H3** `WIRE_RETYPED` / `WIRE_SLOT_SKEW` per lane; **H4** a statement-kind census per body (the table in §3.3) and the frame lets whose rows are read before the end; **H5** bodies on the system route, bodies with the generator's fold; **H6** bodies whose non-last statement is an uncast dynamic pivot; **H7** bodies mixing the platform and a declared target | 2 to 3 days | none: measurements, posted as a report |
| **S0** | F1 closed: the plan side refuses a late-bound raw grid by name | half a day | a new planner test; no roster moves (the product lanes never plan one: measured by the refusal firing nowhere in the PCT main lanes) |
| **S1** | the vocabulary: `Allocation`, `Verdict.Database`, `Verdict.Host`, the wire-decided column fact, (`Keyed` in a `Sequence`, per-step targets if H6/H7 say so); runner arms; JSON round trip (`testcases/PlanRoundTrip`); no caller yet | 3 days | `core_tests_planner`, `exec`, `testcases`; nothing in the driver changes |
| **S2a–S2j** | the body planner, kind by kind, as a strangler: the driver keeps its loop but, kind by kind, asks the body planner for the step and runs it through the runner; the driver's own branch for that kind is deleted in the same commit. Order from H4's frequencies; provisional: a (values, value lets), b (alias, handle lets), c (staged lets), d (effects), e (database-judge asserts), f (host-judge asserts, BOTH), g (frames), h (the system route), i (engine-text asserts), j (the generator's fold) | 1 to 3 days each, 2 to 3 weeks in all | each: zero roster movement; `BodyDriver`'s size pin shrinks (dated); the `JavaEvalLedgerTest` registers move by name; D30's residue list re-pinned |
| **S3a** | the dynamic pivot in bodies as `Keyed` (the product path's form, S110); `DynamicPivot.staticize` deleted; ColumnProbe deleted where H1 shows the deferred TDS is the pivot's alone (else the deferred TDS as a per-key region of the `Keyed` body, S110's form) | 3 to 5 days | zero roster movement; `PROBE` census drops by the pivots' count; `DynamicPivotKeyLiteralH2Test` becomes a planner test; `JavaEvalLedgerTest:217` (DynamicPivot's pin) deleted |
| **S3b** | wire-decided kinds decoded by the wire (§3.2); `WireProbe.reconcile`/`staticized` deleted; `describe` has no caller in the driver | 3 to 5 days | `WIRE_RETYPED` before equals the wire-decoded count after; zero roster movement; `WireProbeTest` rewritten as a decode test in `exec` |
| **S3c** | raw grids: undemanded as wire-decided columns; demanded per the user's ruling (§3.5); `GridProbe` deleted; `RawSqlLedgerTest:122`'s one `RawSql` → 0; `RawGridSchema.SchemaReferee` deleted (form i) or replaced by the planner's oracle (form ii) | 1 week | zero roster movement, or each demanding body posted; `PROBE` census 0 |
| **S4** | the interpreter: `BodyCompiler` and `Segments` deleted; `BodyDriver.execute` = plan once, run the sequence, judge at the verdicts; `specs()`/`resolved()` no longer called | 1 week | zero roster movement; `theDriverCompilesNothing` added; the driver's size pins shrink to the residue |
| **S5** | the boundary: `TypedQuery`'s internals into `planner/` (package-private again), `PlanMaker`'s three driver statics package-private, `JudgePlans` in `planner/`, F3 and F4 deleted, `core-layers.txt`'s driver line cut, the registers closed; the JPMS spike if the user says so | 3 days (+1 for the spike) | `//tools/deps:core_layering_test`; `ArchitectureTest`; `JavaEvalLedgerTest` |
| **Z** | the final audit and the landing handed to the user | 2 days | CI on every OS on the branch; the PR; the user's word |

Total: six to eight weeks of sessions, the lanes' turnaround included. Steps H, S0, S1 are safe to start the moment
the user rules; S2 onward each need their own brief.

## 5. What stays, by name, and why

- **The two judges and the verdict batch** (`com.legend.judge`, D7, D71): a verdict over typed rows is lite's form of
  upstream's `assertEquals` in Pure; the program moves the judges' PLANS to the planner and leaves the judging where
  the rows are.
- **`Keyed`** (S106 to S110): the user's ruled form of the dynamic pivot; this program extends its use to bodies.
- **The engine-text dialect** (`EngineStyleH2`, `SqlTextVerdicts`'s expectations): plan-time text, planning-side.
- **D30's residue** (`assertError`, `print`, `$plan` navigation, `planToString`, `toSQLString`): named, pinned; the
  generator's fold is the one D30 said to revisit (S2j).
- **Compile-time splicing of lets** (no `${var}` templating): the plan path program's ruled form; upstream's holes
  are the same information filled later.

## 6. Decisions for the user, before any code

- **Q1. The program as a whole:** §0 to §4 as the plan, or changes. (A yes starts H.)
- **Q2. F1 now:** the plan-side refusal of a late-bound raw grid (S0) as its own small commit, or held.
- **Q3. The demanded raw grid (§3.5):** form (i) declared-in-program, or form (ii) the planner's schema oracle door,
  or "decide after H2's count" (recommended).
- **Q4. JPMS:** spend one day on the `module-info.java` spike at S5, or rely on the ArchUnit rules and
  package-privacy (recommended: the rules; the spike only if the user wants the mechanical guarantee).
- **Q5. The branch:** this program starts after PR #82 lands, on a fresh branch from `main` (recommended), or on top
  of the seam-and-wall branch.
- **Q6. The strangler order (S2a to S2j):** by H4's frequencies (recommended), or the user's order.

## 7. Rules of the program (in force, verbatim from the user)

"Never push to main, never open a PR or merge without my explicit word in your session"; "never change how any
output is spelled without asking me first, one case at a time, with the expression and the before and after"; "An
answer of mine to a question is not a go to build something else; ask"; "A guard pin or allowlist moves only with a
dated justification"; "Never read, print or copy credentials; lane sessions measure only"; "Run the start page's §6
checks before each commit; commit and push to the branch; records (GATES, IN_FLIGHT, the program's §9, register
rows) land with the code"; "Plan each step with the user and get agreement before writing code". The
upstream-fidelity tenet: every divergence posted, then a row of `docs/SEMANTICS_REGISTER.md`; upstream read from the
pinned sources (the evidence files here), never recalled.

## 8. Status

- 2026-10-30: the homework done (two independent read-only agents: CODE, UPSTREAM); this plan written; posted to the
  user with §2's findings and §6's questions. **No code.** Next: the user's rulings on §6.
