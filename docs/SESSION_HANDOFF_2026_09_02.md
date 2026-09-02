# Harness-Deletion Burn — Session Handoff (for the session after 2026-09-01)

Supersedes `docs/SESSION_HANDOFF_2026_09_01.md` (kept for its audit trail).
Read this top to bottom before touching the tree; every number below is
a measured receipt from the sweeps that landed HEAD.

## 0. Where things stand — RIGHT NOW
- **Repo** `/Users/neema/legend/legend-lite`, branch `main`, **tree clean, all pushed.**
- **HEAD `5b63838c`** — three gated batches landed 2026-09-01 (evening):
  `ac99dcf5` foundation probe (mapping-seam window rule + one test clock),
  `302365b8` legacy TDS join let-bound JoinType (+23),
  `5b63838c` TDG arm reach (zero net flips, honest re-bucketing).
- **Ratchet 848 fallbacks / 1725 flipped** (EXACT pins,
  `RelationalCorpusRunner.java` ~line 1120). Corpus **2350** pass (2144 clean).
- **Standing pins, all green:** sql-verdict disagree EXACT 0; canon
  disagree EXACT 21 (calendarAggregations float class); M1 h2-exec
  verified 83 / rescued 204 / unverifiable ≤ 11; exec-passing 345;
  emission census 392 text-matched / 812 diverged / 110 text-verdict
  (cosmetic, shrink-only, never a verdict); fallback census header
  `rollbacks=48 mirror-detaches=1 rollback-failures=0` (the 1 is
  deterministic failure hygiene — see 5b63838c).
- **Full 8-gate chain** `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure caffeinate -dims tools/allgates.sh` ≈ 5.5 min, green at HEAD.
- **Foundation verdict:** sound. `docs/PLATFORM_FAIL_ADJUDICATION_2026_09_01.md`
  is the record: no silent filter/null-out corruption; the null-vs-value
  trio is the ENGINE's own defect (its corpus admits it in a
  `test.ToFix` sibling); the rest of the "9" are conventions or
  one-mapping bindings, all loud, all named.

## 1. THE DECISION THE NEXT SESSION OPENS WITH

The 848 fallbacks split (census of `target/wholetest-flip-fallbacks.txt`
at HEAD, families grouped by message class):

| family | rows | nature |
|---|---:|---|
| **METAMODEL family** (as-relations + metaprogramming bodies + harness vocabulary) | **~355** | ONE architectural program (§3) |
| platform-fail (9 adjudicated + cosmetic plan/sql-text + named singletons) | 77 | mostly named residue, not burnable by mechanism |
| text-policy (plan-program replayer cohort + by-design) | 65 | §5 replayer design (charter) |
| join-condition reads a whole variable | 43 | one resolver design leg |
| dialect capability (array literal 9, UNNEST 5, LIST_GET 5, banker's ROUND 4, …) | 31 | execution-dialect encodings |
| mapping resolution: class not mapped (cross-store graph-fetch, chains) | 27 | resolver legs |
| filter-predicate isolation (unresolvable after isolation) | 25 | one resolver/lowering leg |
| multiplicity stamp/compat | 23 | stamp program |
| plan-execute parametersValues binding | 17 | the chartered referee-binding cut (charter §5) |
| execution activities not recorded | 14 | harness/platform activities channel |
| post-processors (sqlQueryPostProcessorsConnectionAware 8, replaceTables 4, inline MapperPostProcessor 1) | 13 | recognizer legs |
| dialect-loop asserts (`$expected->map(p\|… assertEquals …)`) | 13 | verdict-layer map-over-literal unroll |
| TDG chained fetch (generator temp tables not replayable) | 12 | oracle sequence replay |
| executeInDb result reads | 7 | named wall by design |
| true singletons / small named tails | ~140 | burn incidentally |

**Option A — metamodel design first (RECOMMENDED).** It is the single
largest lever (~355 rows plus the dissolution of every per-construct
let-chase the bind-once leg had to add), it is the user-ratified
end-state ([[metamodel-in-database-ruling]]), and its precondition —
"the foundation is sound" — is now a receipt. The non-metamodel legs
are each bounded, gated, and independent of the metamodel design, so
they serve as gate-cycle fillers whenever a metamodel step is blocked
on a decision. Nothing in the non-metamodel list unblocks the
metamodel work; serializing ~490 rows of mechanism legs in front of it
only delays the hard design.

**Option B — burn to "only metamodel left".** ~490 rows across ~14
mechanism legs plus a ~140-row tail: several sessions. Every leg
below carries its diagnosis and entry points, so it can be executed in
the listed order without re-deriving. Choose B only if the user wants
the ratchet visibly moving while the metamodel design is being
thought about elsewhere.

Under either option the standing rules are unchanged: one gate chain
per batch, push after green, pins move only WITH their burn and a
written justification, paired same-tree sweeps byte-identical on all
three rosters before any pin move, no envelopes.

## 2. THE NON-METAMODEL BURN MAP — per leg, in execution order

Each entry: witness, diagnosis (receipted), design sketch, entry points,
expected movement. "Size" is an honest estimate of one batch or more.

1. **Dialect-loop asserts (13)** — `testToSQLString.pure` (testCbrt et
   al.): `$expected->map(p| let driver = $p.first; … assertEquals($expectedSql, $result, …))->distinct() == [true]`.
   The verdict layer sees statement-root asserts only; asserts inside
   the map lambda reach the scalar lowerer ("no scalar lowering for
   assertEquals/4"). Design: a map over a LITERAL collection (a
   `PureCollection` of `pair(...)` literals) whose lambda body ends in
   an assert β-unrolls into per-element statement sequences with the
   lambda parameter substituted (the bind-once machinery —
   `UserCallInliner`/`SourceSubst` — owns substitution; add the
   unroll where the whole-test body is compiled, `WholeTestFlip` →
   `Compiler`/`StatementExecutor` statement fold). NOT a bespoke arm:
   it is a general β-reduction of `map` over a literal. Size: one batch.
   Gate: the 13 flip; watch the `->distinct() == [true]` tail (fold to
   a trivially-true verdict after unroll).
2. **Plan-execute values-binding (17)** — charter §5 "referee bindings
   as MINTED LETS": `$plan->execute(parametersValues, ext)` with
   non-empty values walls counted (`StatementExecutor.buildFrame`,
   EXECUTION_PLAN_EXECUTE branch, ~line 1902). Bind each
   `pair(name, value)` as a let over the plan's query lambda (the
   normalization already peels the plan to its `executionPlan(...)`
   build). Size: one batch; the 7 TDG rows that joined this bucket in
   5b63838c come with it.
3. **TDG chained-fetch sequence replay (12)** — `ReplayOracle.tdgSqlReplay`
   line ~384 declines any fetch text touching `tdg_N_*` (the generator
   drops its temp tables in its finally). Design: replay the fetch
   SEQUENCE on both sides — for fetch k, first materialize fetches
   0..k-1 into their `tdg_*` temp tables (the generator's own naming),
   then execute k; multiset compare; drop. Oracle-side (testing) work;
   the platform side already produces the folded `TestDataGenResult`
   literal with the ordered `sqls`. Size: one batch. Note the dialect
   TOP/LIMIT text of these goldens is why they currently fail on the
   text contract — rows are the verdict, so a replay flips them.
4. **Join-condition whole-variable (43)** — witness
   testGroupByAndMilestoning: "join condition reads a whole variable —
   only column reads can correlate sides". A synthesized join whose
   condition references the row VARIABLE (e.g. passes `$r` to a
   function) rather than columns. Design: the resolver's correlation
   channel (`TypedFilter.Stamp.CORRELATION`, `NullSemantics.enterVerbatimEquality`)
   needs a row-struct carrier for the whole-row read, or the condition
   inlines the callee to column reads first (UserCallInliner before
   correlation). Read `docs/EMBEDDED_UNION_NAV_HANDOFF_2026_08_31.md`
   §7 first — it names this leg. Size: 1–2 batches.
5. **Filter-predicate isolation (25)** — "filter predicate references
   column '_', unresolvable even after isolation" (params `_rN`,
   `ms_row`): predicates over columns the isolated select no longer
   carries (milestoning `ms_row` 12, `_rN` 13). Design: the isolate
   must project the predicate's demanded columns (a demand pass before
   `Lowerer.isolate`), or the resolver keeps the columns on the
   milestoned frame. Size: one batch per witness class.
6. **Dialect capability (31)** — array literal 9 / UNNEST placement 5 /
   LIST_GET 5 / banker's ROUND 4 / others: the ENGINE-text dialects
   lack list encodings (`CarrierStrategies`, `EngineStyleH2`). These
   are the same walls the h2 lane multiplies ×863 (Layer 4 below);
   decide with the h2-lane decision, not before.
7. **Mapping resolution: class not mapped (27)** — cross-store
   graph-fetch (`meta::pure::graphFetch::tests::…` 8) and chain
   mappings (`graphFetch::tests::chai…` 4, nested union cross-store
   4): the class's set lives in an included/other-store mapping the
   resolver does not reach. Read `ClassSources.findBinding` +
   `classBindingsWithIncludes`. Size: one batch per witness class.
8. **Multiplicity stamp/compat (23)** — ONE-STAMP/LIST-SHAPE invariant
   (toPostgresModel::testConvertAlias 11, quarantine-adjacent) and
   `[*]` vs `[N]` argument compat (validateAllConstraints 11):
   `docs/STAMP_DISCIPLINE_PROGRAM.md`. The 11 stamp rows sit inside the
   toPostgresModel family (metamodel-adjacent — PROGRAM_MAP says
   "repromote only on a non-quarantine witness").
9. **Execution activities not recorded (14)** — `RelationalActivity`
   reads (`$result.activities`); the platform records one activity per
   execute (`spliceHook.relationalActivitySql`, activity 0 only).
   Design: an activities channel on the executed frame. One batch.
10. **Post-processors (13)** — connection-aware hook shapes
    (`extractSubqueriesAsCTEs` 8), `replaceTables` pair side not a
    schema()/table() navigation (4), inline `^MapperPostProcessor`
    on a connection (1, silent in production — named in the
    adjudication record). Recognizer legs on `RelationalMapperRenames`.
11. **executeInDb result reads (7)** — by design (opaque handle);
    stays named unless a witness reads real data.
12. **Text-policy (65)** — the §5 plan-program replayer cohort (~25:
    allocations + both-ways temp-table conditional) + TDG
    assertSqlEquals-by-design + mixed bodies. Charter §5 is the design.
13. **Platform-fail (77)** — composition (adjudication record): 9
    adjudicated (engine defect / conventions / one-mapping binding),
    ~12 plan-text formatting, ~4 auto-generated lambda names, 12 TDG
    text-contract fails (leg 3 above), 5 ROW-verdict diverged
    (testQualifierQueryWithOr-class isolation shape, hash-function
    spellings, TDS join-strings), 8 datediff-to-now named declines,
    dialect enum-decoded declines, and named singletons. Burnable rows
    here are the ones with a leg above; the rest are receipts.
14. **Also named, silent-in-production (adjudication record):**
    connection timeZone at execution (2 tests, testIn.pure — the
    execution dialect ignores the runtime's timeZone; only the
    engine-text renderer knows it), join-chain terminal binding (1
    mapping corpus-wide), instance-carrier fan-out cardinality,
    TDSNull cross-carrier encoding (instance literal JSON null vs row
    `'TDSNull'`), identity leak on multi-pk `map` (1).
15. **Let-bound TDSRow-typed join lambda (2)** — `let jc = {a:TDSRow[1], b:TDSRow[1]|…}`
    walls at its own let (a nominal TDSRow has no columns; it only
    types against the consuming join's rows). Bind-once charter
    family A: add "lambda with declared TDSRow parameters" to the
    deferred-kind closed list (`Env.withDeferred`, `Typer.deferredLetRhs`)
    and resolve at the consuming join. A design decision on a closed
    list — do NOT chase it through the alias channel (measured and
    removed in 302365b8).

Layer 4 stands: the **h2 lane (G5) has its own 1,618 fallbacks, 863 =
UNNEST placement walls**; walk deletion needs that decision (grow the
H2 execution dialect vs redefine the advisory lane). No plan yet.

## 3. THE METAMODEL PROGRAM — what the design session must produce

**Composition of the ~355 (LL_TMP_DEBUG sweep at HEAD, by shape):**

| shape | rows | witness |
|---|---:|---|
| `FunctionDefinition.expressionSequence` reads (metaprogramming callee bodies: pkOfFunc 43, scanRelations helpers, TDG helpers) | 70 | pkInferenceTests, testGraphFetch |
| class query under `TypedMap` (plan-walk over metamodel values) — "HN vocabulary" | 65 | testSQLCommentsInPlan |
| class query under `meta::relational::mapping::sql` (SQL-node metamodel) | 45 | testRewriteCanAggregateGroupByOnLiteralWithMultipleAgg |
| `meta::legend::executeLegendQuery` / `compileLegendValueSpecification` / `compileLegendGrammar` (harness vocabulary: compile-and-run pure TEXT) | 42 | testDropWithVariables |
| lineage `scanRelations` call typing | 21 | testSameRelationsAtSameLevel |
| mapping-metamodel query functions: `rootClassMappingByClass` 11, `classMappingById` 6, `view` 6, `inferRelationalType` 3, `_classMappingByClass` 1 | 27 | testDynaAndOrInference, testMainTableForB1 |
| `toPostgresModel::newState` (runtime-constructed protocol values compared structurally) | 10 | testConvertColumnName |
| `generateObjectReferences` 7, `routeFunction` 4, `InstanceValue` construction 4, `LambdaFunction` property reads 6, `repeat` 2, `toDomainValue` 2, `resolveStore` 1 | 26 | testObjectReferenceInEmbeddedMapping |
| stamp/compat rows inside toPostgresModel (see leg 8) | ~11 | testConvertAlias |

**The ruling that stands** ([[metamodel-in-database-ruling]], commit
47206a73): NO Java-computed metamodel/lineage/plan fact enters the
verdict path; the end-state is metamodel AS RELATIONS in the database.
Model layer = classes/properties/mappings/tables/joins/columns as seed
rows (INFORMATION_SCHEMA precedent; `SystemMetamodel` is the one-table
v1 where `Class.all()` is a real SELECT). Analysis layer = lineage/plan
trees as adjacency lists computed as a RESOLVER SIDE-OUTPUT (rows, not
text), traversed with recursive CTEs, printed by an owned recursive
query + tiny egress formatter. Metamodel classes get relational
MAPPINGS onto the metamodel tables so pure-over-metamodel lowers
through the one router.

**Homework already DONE (PROGRAM_MAP.md §"DEFERRED PROGRAM", do not
redo):** exact census + witnesses; engine .pure specs read from the
real checkouts (functions_Mapping.pure:28-79, platform_store_relational/
functions.pure:254, relationalExtension.pure:120-137,
toPostgresModel.pure:31-48, extension.pure:62, pkInferenceTests.pure:25-29);
decline mechanism verified; SystemMetamodel v1 scope verified (ONE
table, name only). Leg 1 chosen 2026-08-31: PLAN-NODES-AS-ROWS
(acceptance: the TypedMap-65 plan-walk filter lambdas lower to SQL over
plan-node rows; quarantine partition 172 witness rows + 20 wall tests,
pins in RelationalCorpusRunner, vocabulary
`CanonicalDivergence.METAMODEL_QUARANTINE`).

**Homework OPEN — the design session's deliverables, in order:**
1. **Compile-time fact vs derived-on-the-fly** (PROGRAM_MAP open item 1):
   read `MetamodelWalk.mainTable/resolvePrimaryKey/infer` +
   `MappingNormalizer`; if the compiled model already holds the facts
   (extends-chain main table, groupBy/distinct PK, view column types),
   seeding is a dump and the lowerings are plain SELECTs. Decide.
2. **The seed schema** (grow-by-witness): `metamodel.classes`,
   `properties`, `mappings`, `class_mappings` (fqn key, id, root,
   class, superSetImplementationId), `mapping_includes` (transitive
   closure seeded at extent-render time, not recursive CTEs),
   `schemas/tables/views/columns/view_column_mappings`, `joins`.
   Identity = FQN/path primary key (SystemMetamodel D2 rule). Seed
   cost at corpus scale must be MEASURED (compile-once sweep ~50s
   now; metamodel extent per test/connection unmeasured).
3. **Function-shaped navigation over mapped metamodel rows**
   (`$x->mainTable()` is a function, not a property): mapped
   association vs compiler-synthesized query per native — pick one,
   demonstrate `cast->map(fn)` chains and `assertEquals` over
   row-backed metamodel instances in the store lane.
4. **Trees as data** (expressionSequence 70 + inferRelationalType +
   pkOfFunc): trees-as-rows (adjacency list, resolver side-output)
   vs trees-as-structs. This is the hard end and the biggest row
   count — design it explicitly, with the recursive-CTE print path.
5. **newState (10) + constructed protocol values**: struct-values
   canonical layouts are the one lead (constructed instances already
   lower as structs when the class declares stored properties).
6. **Harness vocabulary (42: executeLegendQuery / compileLegend*)**:
   these compile-and-run pure TEXT inside a test — decide whether
   they are walk-by-design forever (the harness's own vocabulary) or
   platform (a compile-from-string entry through the one router).
   The user's one-router ruling suggests the latter only if it is
   the SAME entry point, never a second evaluator.
7. **Tractability prototype BEFORE chartering the rest**: ONE witness
   end-to-end — testMainTableForB1: seed a class_mappings+tables
   fragment, register one lowering, watch the verdict land in-DB.
   Then leg 1 (plan-nodes-as-rows) as the first shippable batch.
8. **Acceptance + pins plan**: the quarantine partition (172/20)
   shrinks as buckets migrate; each migration lands with its witness
   test, the ledger rows (`JavaEvalLedgerTest`: MetamodelWalk 1307 +
   MetamodelSteps 196 stripped lines) shrink to zero as store
   lowerings claim FQNs; the flip ratchet moves WITH the burn.

**Doctrine reminders for that session:** one router, one evaluator (no
bespoke per-FQN entry points — `TestDataGenerationNatives` is a named
instance of the wrong pattern owing a rename when it migrates); engine
source is oracle material, never runtime; verify every signature
against real legend-pure; measure before claiming; design with
conviction, not menus.

## 4. OPERATIONAL NOTES (all learned the hard way this session)
- **Clock:** the test JVM runs under `-Duser.timezone=GMT` (root pom
  surefire) — engine parity. Do not remove; do not "fix" a datetime
  test by editing the zone. A projected `datediff(..., now())` golden
  declines BY NAME (H2Verify.compareFrame `instantInSelectList`).
- **A frozen-tree ±1 can still be the clock** — check what the oracle
  and the execution each call `now()` before hunting nondeterminism.
- **G8 runs `-am clean` and wipes `core/target/`** — save
  `wholetest-flipped.txt`, `wholetest-flip-fallbacks.txt`,
  `h2-verdicts.txt` to job tmp BEFORE a chain; the chain's own G4
  rosters are gone by the time it reports.
- **There is NO `-Dlegend.corpus.containing` property** (the 09-01
  handoff was wrong); a full sweep is ~50s: `mvn -o -q -pl core test -Dtest=RelationalCorpusRunner -Dlegend.engine.root=… -Dlegend.pure.root=…`.
  `LL_TMP_DEBUG=1` unmasks `[flip-wall-debug]`/`[flip-fail-debug]`
  lines (the folded TDG literal was sitting in that log — read the
  block before designing a mechanism). Sweep logs contain NUL bytes:
  python/awk, never grep.
- **Paired sweeps:** two same-tree sweeps must be byte-identical on
  ALL THREE rosters before a pin moves. Two shells started in the
  wrong cwd this session — every mvn command starts with
  `cd /Users/neema/legend/legend-lite;`.
- **Governance you will trip:** `CodeShapeGuardrailTest` (Lowerer ≤ 3500
  lines — it is at 3499), `JdbcSurfaceCensusTest` (every test file that
  opens JDBC registers with a tenet argument), `JavaEvalLedgerTest`
  (per-file stripped-line pins; SqlTextVerdicts now 592 — a bump needs
  a written justification, routing/recognition only),
  `OwnCorpusConformanceTest` (our own test Pure must parse on the
  4.138.2 oracle: spell a Relation mapping's function as the
  DESCRIPTOR `~func f():meta::pure::metamodel::relation::Relation<Any>[1]`,
  never the mangled `f__Relation_1_`).
- **QueryService/Compiler.execute take a single EXPRESSION** — unit
  tests spell multi-statement bodies as `{| let …; expr; }`.
- **Own-corpus mapping tests:** `Relation { ~func … }` class mappings
  parse in our parser; the seam witness lives in
  `RelationMappingWindowSeamTest` (registered in the JDBC census).
- **Memory files** (`~/.claude/projects/-Users-neema-legend/memory/`)
  `harness-deletion-program`, `sqltext-row-verdict-charter`, `MEMORY.md`
  reflect HEAD; `metamodel-in-database-ruling` is the standing ruling.
