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

## 5. FIRST STEPS FOR THE METAMODEL SESSION — exact entry points (added 2026-09-02, after the homework)

Read first: `docs/METAMODEL_AS_RELATIONS_HOMEWORK_2026_09_02.md` (§7 decisions,
§8 open questions, §9 prototype order, §11 worries). The census scripts and
their outputs are in `tools/metamodel-census/` (`build.py <sweep.log>` then
`scan2.py`, `scan3.py`, `closure.py`, `props.py`; inputs = an
`LL_TMP_DEBUG=1` sweep log + `target/wholetest-flipped.txt`).

**Step 1 — census dump (half a session).** `WholeTestFlip.java:60-110` holds
`BUCKETS` (bucket → count) and `WITNESSES` (bucket → one test). Add a
`bucket → all test names` map written to `target/wholetest-flip-buckets.txt`
at the same shutdown hook. That names the ~88 HN-vocabulary tests the
homework could only count by bucket. Harness-only; no pin moves.

**Step 2 — run-time branch choice on a row's type column (one session).**
Today: `MatchChecker.java:18-70` selects a `match` arm STATICALLY by the
input's compile-time type; when an arm is a strict subtype of the input
type it keeps all arms in a `TypedMatchRuntime` "for the host channel";
`lowering/MatchFold.java` folds that node statically and says verbatim
"a genuinely polymorphic input (class hierarchies) stays a loud wall";
`CollectionLanes.java:199-200` refuses both node kinds; `instanceOf` folds
only when statically decided (`Scalars.instanceOfFold`, :2508). Rows from
inheritance/union mappings already carry a subtype column
(`ClassMapping.isSubTypeColumn` :62, `UnionSynthesis`, `subType(@X)` reads
in ClassSources). Build: lower a `TypedMatchRuntime` whose input row has a
discriminator into `CASE <kind> WHEN … THEN <arm body> …` for VALUE arms,
with each arm's parameter bound to the narrowed row (the subtype's columns);
`instanceOf` → `kind = '…'`; `cast(@Sub)` → the narrowed row (a wall if the
kind does not match at run time is acceptable for v1, documented). Witness:
a NEW unit test on an ordinary user inheritance mapping (no corpus test in
tests/mapping/inheritance or extends uses `->match` — grep confirmed), plus
the metamodel navigations themselves (`mainTable` = match Table/View). Row-
returning arms (UNION of arms) are step 4's problem, not this one's.

**Step 2 LANDED (2026-09-02).** Placement differs from the sketch above,
on evidence: the dispatch happens in the RESOLVER's class-lambda
substitution (`Substitution.typeDispatchArms`), not the Lowerer — the
union/inheritance row already carries the discriminator as the
`$member` membership witness (`ClassMapping.memberWitness`, NULL in
non-conforming threads) and per-subtype `stc_` columns registered under
`SUBTYPE_KEY`; `MatchFold`/`instanceOfFold` never see a head-variable
dispatch any more. Forms: `$p->instanceOf(Sub)` → `isNotEmpty(witness)`;
`$p->match([s:Sub[1]|v,…])` → nested `if` over witnesses, catch-all arm
(`Any` / the input's class / a TOTAL-membership subtype) as the ELSE,
otherwise ELSE = `fail('Match failure …')`; `$p->cast(@Sub).prop` →
`if(witness, stc read, fail('Cast exception …'))`. `fail` gained its
scalar lowering (`ERROR(...)`, cast to the position's carrier — no
dialect inference). A subtype the row carries no columns for is LOUD.
Witness: `RuntimeTypeDispatchTest` (8 cases, rows are the verdict, an
ordinary user inheritance mapping). Corpus: byte-identical rosters,
848/1725 unchanged (no corpus test dispatches on a union row).
`collectSubTypeFqns` now also demands match-arm / instanceOf / cast
targets. Residue named: row-returning arms (step 4), navigation through
an arm parameter (`$c.mechanic.name`), a match `extra` argument,
supertype arms other than `Any`/the input's class.

**Step 3 — prototype 1, testMainTableForB1 (one session).** Witness:
`tests/mapping/extend/testExtendsForMainTable.pure` (`B1Mapping->classMappingById('b1')->cast(@RootRelationalInstanceSetImplementation)->map(x|$x->mainTable())` equals the super mapping's). Pieces:
- Seed tables, grown from `SystemMetamodel.java` (its `SOURCE` is the Pure
  text of the store + mapping; `seedStatements` renders DDL+INSERT from the
  active `ModelContext`; injected at `Compiler.java:232/267`; resolver hook
  `StoreResolver.java:1244`): add `metamodel.mappings(fqn PK)`,
  `metamodel.class_mappings(mapping_fqn, id, class_fqn, root, super_set_id,
  main_db, main_schema, main_table)`, `metamodel.mapping_includes_closure
  (mapping_fqn, included_fqn)`. Source of the rows: `MappingDefinition
  .ClassBinding` (:89, Relational carries `RelationalSource.Table`),
  `classBindingsWithIncludes`, `MappingNormalizer.mainTableDefOf`.
- Map `meta::pure::mapping::Mapping` and `RootRelationalInstanceSetImplementation`
  in the system mapping (inheritance mapping with a `kind` column for the
  set-implementation subtypes; `~primaryKey` = mapping_fqn + id).
- Natives implemented as queries (spec = the engine bodies cited in the
  homework §2b): `classMappingById` (closure over includes),
  `rootClassMappingByClass`, `mainTable` (Table vs View arm). Register in
  the native catalog (`Pure.java`; the catalog golden line diff is the
  conscious registration; verify signatures against
  `legend-pure/…/platform_dsl_mapping/functions_Mapping.pure:61/74` and
  `platform_store_relational/functions.pure:277`).
- Verdict: `assertEquals` of two Table rows → row equality in the DB.
- Pins that move: `CanonicalDivergence.METAMODEL_QUARANTINE` (:513) and
  the runner's quarantine counts (172 witness rows / 20 wall tests,
  `RelationalCorpusRunner.java` ~:1219/:1223) shrink; the flip ratchet
  moves +tests; Java-eval ledger rows for `MetamodelWalk` (1307) /
  `MetamodelSteps` (196) must NOT grow — the whole point.
- Acceptance = the verdict lands with zero test-specific Java; then
  `testMainTableForB2..` and the 5 extends tests follow for free.

**Step 3 LANDED as a PARTIAL prototype (2026-09-02) — mechanisms proven,
the witness's verdict blocked on ONE named resolver gap.** Landed (all
platform code, zero test-specific Java; witness
`MetamodelMappingStoreTest`, 9 cases, rows are the verdict):
- **Seeds** (`SystemMetamodel` schema + `MetamodelSeeds` rows, seeded per
  execution like `classes`): `mappings`, `class_mappings` (one row per
  RELATIONAL class mapping; the compiler's stamped, extends-resolved main
  table — P4 receipt: `B[b1] extends [a] {}` carries `ABC`),
  `mapping_includes_closure` (reflexive-transitive; a ROW ENTITY
  `meta::lite::metamodel::MappingVisibility` with associations
  `viewer`/`visibility` and `visible`/`visibleFrom` and `visibleSets`),
  `table_aliases` (the set's main-table alias as its own relation, keyed
  like the set — never a self-join), `tables`. Null seed cells render as
  NULL (`Ddl.metamodelSeed`).
- **Metaclasses mapped**: `Mapping[mapping]` (+ real m3 `classMappings`),
  `SetImplementation` as an INHERITANCE op whose member is
  `RootRelationalInstanceSetImplementation[rootRel]` (`id`,
  `superSetImplementationId`, `parent`, `mainTableAlias`),
  `TableAlias[alias]` (`name`, `relationalElement[tbl]`),
  `RelationalOperationElement` as an inheritance op, `Table[tbl]`. Native
  class growth, all real-m3 spellings: `PackageableElement` (new),
  `Mapping extends PackageableElement` + `classMappings`,
  `SetImplementation.parent: Mapping[1]`,
  `RelationalMappingSpecification.mainTableAlias: TableAlias[1]`
  (NativeFunctionTest surface pins moved; class count 211→212).
- **Natives as Pure bodies** (the one router inlines them; the engine
  bodies are the SPEC, ours read our rows): `meta::lite::metamodel::classMappingById` =
  `Root.all()->filter(cm | $cm.id == $id && $cm.visibilityOf.viewer->exists(v | $v->elementToPath() == $_this->elementToPath()))->first()`;
  `meta::lite::metamodel::mainTable` = `$_this.mainTableAlias.relationalElement->cast(@Table)`.
  They carry LITE names for now: taking the real FQNs away from the
  natives turned 6 extends tests that the LEGACY WALK scores through
  those natives into walls (scoreboard `tests/mapping/extends` 23→17 —
  the corpus-regression gate caught it, measured and reverted same
  session). The switch-over is one rename, owed to the navigation-depth
  leg that flips the witnesses. New native
  `elementToPath(PackageableElement[1])` (real elementToPath.pure:44):
  over a REFERENCE it is the path literal; over a metamodel ROW it is
  the row's key (the D2 identity) — the `$pk:<col>` pseudo-binding
  `ClassMapping.primaryKeyBinding`, registered beside the subtype
  pseudo-bindings and never serialized.
- **D3 — element reference = row** (`ElementReferences`, resolver): a
  reference to a tracked, system-mapped element (`ext::B1Mapping`) is
  its metaclass extent filtered on its key — an ordinary object-space
  filter, so it rides every position a class filter rides; a BARE
  reference (a `from()`/`execute()` argument) stays a value (`Anchors`:
  a reference anchors only as the SOURCE of a navigation). D1 widened:
  "intrinsic" = registry-tracked OR bound in the system mapping.
- **Chain-position `->cast(@Sub)`** = re-typing when the mapping PROVES
  totality: the hop is routed to one member set whose class conforms
  (`ModelContext.routedTargetClass`), or the extent's members all
  conform (`unionMemberClasses` / inheritance subclasses). Partial stays
  loud by name. A single-entry routed navigation into an Operation-mapped
  root with no set of its own now lands on the routed set's class
  (`JoinChainEmission`); multi-entry routes keep per-arm dispatch (the
  first cut of this re-targeted every entry and cost 30 inheritance
  tests — measured and reverted same session).
- **Normalizer**: every hierarchy-walk class lookup is native-first
  (`MappingNormalizer.classDef`, primitives excluded); inheritance
  members enumerate the native catalog too; a property-less union root
  with subtype-dispatch columns is allowed; `isSubclassOf` has a cycle
  guard (LeniencyD6/VarianceD4 caught the overflow the wider universe
  exposed). `SqlTextRatchetTest`'s string-literal regex is unrolled (a
  multi-KB text block overflowed the naive alternation).
- **Receipts**: ratchet **848/1725 UNCHANGED**; paired sweeps
  byte-identical on all four rosters; the only bucket movement vs batch
  2 is one test (`testBuildFilterWithValueThatCanBeNullWithIn`) reaching
  the new chain-cast wall by name instead of an earlier wall; quarantine
  pins 172/20 UNCHANGED (the real-name natives keep their refusal
  spelling); scoreboard unchanged (2350). Full core suite green. Java-eval
  ledger rows unchanged (MetamodelWalk 1307 / MetamodelSteps 196).

**Residue, named (the next resolver leg — "navigation depth"):**
1. The witness itself: `B1Mapping->classMappingById('b1')->cast(@Root)->map(x|$x->mainTable())`
   composes FOUR flatten hops; the nested-navigation machinery walls at
   the third hop after two association hops
   (`witnessResidueIsNamed` pins it: "navigation through class-typed
   slot property ... not supported yet" / "is not mapped"). Two-level
   navigation inside a nested predicate has the same limit. Fixing depth
   is the right move (the user's call: fix, don't reshape) — it is a
   resolver leg on `flattenSource`/`nestedAssocMaterials`, not a
   metamodel design question.
2. Under the lite names the corpus chain (probed) stops at the chain
   cast: `->cast(@Table) over RelationalOperationElement ... partial
   membership` — the flatten path reaches the cast without the route
   fact (the same cast is total by route from a `Root.all()` head).
3. Views as main tables: `table_aliases → tables` only (a `~mainTable`
   view yields no row; engine `mainTable` recurses into the view).
4. `SetImplementation.all().parent` from the UNION side (an end mapped
   on the member set, read off the inheritance row) walls; `parent` is
   read off `Root` rows fine.
5. Assert failure MESSAGE rendering over instance values
   (`toRepresentation for LinkedHashMap is not modeled`): the verdict is
   right, the failure text is not printable yet.
6. The Java-eval ledger did not grow (MetamodelWalk 1307 / MetamodelSteps
   196 unchanged); their `classMappingById`/`mainTable` arms still score
   the 6 extends tests until the platform flips them — the rename above
   deletes them.

**Navigation-depth leg — LANDED (batch 4, 2026-09-02).** Residue items 1
and 2 above are closed; 3, 4, 5, 6 remain (see the list below).
- **Mechanisms (resolver only, no dialect coupling, no test hooks)**:
  nav TAILS ride through `flattenSource`'s association branch (the whole
  remaining hop chain + each hop's consumed paths; provenance registered
  as `AssocSub`/`SubNav` trees relative to the materialization's ROOT
  target row — ONE prefix convention, `NavMaterializer.composeSubNavPrefixes`
  / `StoreResolver.rebaseSubNavs`); `Substitution.rewriteMultiHop` gained
  the chain-key + SubNav descent read (`chainKeySubNavRead`);
  `ClassSource.composedPrefix` re-points a chained condition after a
  filtered association hop; `AssociationJoins` materializes deeper tails
  recursively through `NavMaterializer`; DOTTED emptiness registers
  inside nested scopes exactly as at the root (`DottedExists`, extracted;
  the path collector `EmptinessPaths` takes the terminal's lambdas at
  the root and nothing else in a nested scope — no nullable mode flag);
  `Pipelines.walk` (join-slot materializer) gained arms for limit / drop
  / slice / sortBy / a resolver-synth join above the slots and its
  default arm is LOUD on a leftover navigate (it used to pass a `first()`
  wrapper through silently, leaving the slot unmaterialized);
  `FlattenOps.innerizeOrNull` descends projection / limit / distinct
  wrappers to find the nested navigate join; `AssociationSynthesis`
  injects routed PMs only when the binding path is impossible (an
  inheritance-mapped target end keeps its binding under a filter).
- **classMappingById is the NATURAL body now**:
  `$_this.visibility.visible.classMappings->filter(cm|$cm.id == $id)->first()`
  (the elementToPath reshape is gone; `elementToPath` stays as a native
  with its own pin). `MetamodelMappingStoreTest.witnessMainTableForB1`
  asserts the witness verdict TRUE and the main tables as rows.
- **Pinned**: `NavigationDepthTest` (4 cases / 22 shapes on an ordinary
  user model: 3–4 hops, ops between hops, nested-predicate depth 2,
  inheritance-mapped association ends) — registered in the JDBC census.
- **DuckDB driver re-pinned 1.5.0.0 → 1.4.4.0 (root pom)**: 1.5.0
  returns ZERO rows for `SELECT .. FROM (.. WHERE .. LIMIT 1) t WHERE
  t.c IS NOT NULL` over a join chain — reproduced in five lines, fails
  with the optimizer disabled too, correct on 1.4.4; upstream
  duckdb/duckdb#21160 ("duckdb 1.5: Issues around LIMIT", open). The SQL
  is the ordinary `first()` lowering and is legal everywhere; the corpus
  is indifferent to the driver (identical rosters). Revisit at 1.5.1.
- **Receipts**: ratchet 848/1725 → **847/1726** (+1:
  `testNestedExistsWithExistsInAbstractProperty`, wall-exec "predicate
  references column '_'" → platform pass); H2 verdict roster
  byte-identical to the c20859da baseline; paired same-tree sweeps
  byte-identical on all four rosters; quarantine 172/20 unchanged;
  extends 23/23 unchanged; dual-channel 613 agree / 0 disagree;
  exec-passing 345 unchanged; full core suite green (4378). Java-eval
  ledger rows unchanged (the walk still scores the extends witnesses
  under the real names — next batch).

**Batch 5 — the REAL-NAME switch — LANDED (579b1171, 2026-09-02).** Residue item 6
above is closed; 3, 4, 5 remain (plus the two named below).
- **Pure bodies under the real names** (SystemMetamodel):
  `meta::pure::mapping::classMappingById`, `meta::relational::metamodel::
  mainTable`, `meta::pure::mapping::superMapping`, `meta::pure::mapping::
  allSuperSetImplementations`, `meta::relational::mapping::resolvePrimaryKey`
  (the engine's this-vs-super precedence as ONE chain over the ancestry
  rows: filter by any key fact, sort by rank×1000+depth, first, then
  `.ancestor.primaryKey`). The five natives, the `MetamodelSteps` arms
  and `MetamodelWalk`'s classMappingById/superMapping/
  allSuperSetImplementations/resolvePrimaryKey/primaryKeyOf/mainTable/
  tableHandle(3)/classMappingByIdIn are DELETED (reach-back census 3→2).
- **New rows**: `set_ancestry` (reflexive-transitive extends closure with
  depth — `meta::lite::metamodel::SetAncestry` + associations
  `ancestry`/`ancestor`), `group_by_mappings` (m3 GroupByMapping, new
  native class), `primary_keys` (the compiler's population rule — user
  ~primaryKey, else ~groupBy columns, else ~distinct → own mapped
  columns, else the main table's PRIMARY KEY — one TableAliasColumn row
  per column), `columns` (Column rows); `class_mappings` gained
  `distinct_set`/`user_defined_pk`. `RelationalMappingSpecification`
  gained `userDefinedPrimaryKey`/`distinct`/`groupBy` (real
  relationalMapping.pure).
- **Compiled artifact**: `MappingDefinition.ClassBinding.Relational.declared`
  (`DeclaredKeys`: the set's OWN ~distinct/~groupBy/~primaryKey/column
  PMs, captured BEFORE the extends pre-pass merges the parent in —
  `SetKeyFacts`); a function-form binding declares NONE (named gap).
  `GroupBySynthesis`: a per-row PM outside the ~groupBy key list is
  WITHHELD (the Join-PM rule), no longer a poison — the engine compiles
  such mappings (the primaryKey fixtures map `id` beside ~groupBy(aName)).
- **Resolver (general, not metamodel-specific)**: D1 dispatch — an
  intrinsic metaclass (bound in the system mapping, or an abstract class
  whose subclass is) dispatches to the system mapping under an EXPLICIT
  user mapping too (the corpus runs every test under its mapping);
  `InferenceKernel.mostSpecific` — same-name module overloads resolve
  to the most specific class-typed parameters (the engine's
  `resolvePrimaryKey(RISI)` / `(ISI)` sit beside the root-set body);
  `SystemMetamodel.injectInto` shadows FUNCTIONS by signature (a same-
  name overload is not a shadow); a TO-MANY navigation after `first()`
  / limit / drop / slice stays in the chain and joins ABOVE the op
  (`A.all()->first().links.tag` returned ONE link — `rowCountOpBelow`,
  the tail/extra-head rules keep to-many tails out of a target beneath a
  row-count op); a sort below a flatten hop splices (`applyBelow`); a
  TO-ONE hop with ops below it joins FIRST and IS the below scope's
  material for its head (`preJoins` — the second join doubled every
  column name); a slot hop off a composed source whose step was
  stripped splices the class's own step (`NavProvenance.spliceOwnStep`).
  Extractions for the file guardrail: `NavProvenance`, `SetKeyFacts`,
  `FlattenOps.consumedPaths/rowCountOpBelow`, `Pipelines.
  widenPipeForJoinKeys`.
- **Pinned**: `MetamodelMappingStoreTest` (+extendsChainAsRows,
  +resolvePrimaryKeyPrecedence with the engine's own mapping shapes),
  `NavigationDepthTest.toManyAfterRowCountOps`.
- **Receipts**: extends 23/23 — ALL SIX formerly walk-scored tests now
  platform-scored; ratchet 847/1726 → **841/1732** (+6, exactly those
  six); quarantine witness rows 172 → **151** (the classMappingById
  refusal spelling retired), walls 20; H2 verdict roster byte-identical;
  paired sweeps byte-identical on all four rosters; dual-channel 613/0;
  exec-passing 345; core suite green; the global-compile failure of
  `pureToSqlQuery::getGroupBy` ("Unknown type GroupByMapping") is gone.

**Batch 6 — residues (2026-09-02).** `parent` from the union side reads
(closed by batch 5's dispatch/depth fixes — `SetImplementation.all()
.parent.name`, probed). Assert failure MESSAGE over instance rows renders
the spec's `<id instanceOf Type>` (toRepresentation.pure:28; id = the
synthetic site identity when carried, else the row's property values in
wire order — identity as data; T = the side's static class, `?` when the
side is not class-typed); pinned in `MetamodelMappingStoreTest.
instanceFailureMessageRenders`; corpus: `testSelfJoinPropertyMapping`
moved from the "toRepresentation … not modeled" wall to an honest
platform-fail with its message (ratchet unchanged 841/1732, paired
sweeps byte-identical). PureAsserts ledger pin 311 → 313 (message text,
no verdict).

**VIEWS AS MAIN TABLES — chartered, NOT built** (no corpus witness: the
only corpus caller of `mainTable()` is the extends file, 23/23). The real
body is `$_this.mainTableAlias.relationalElement->match([t:Table[1]|$t,
v:View[1]|$v->mainTable()])`; the honest relational model is View rows
(+ a view alias row keyed by the view, base table resolved transitively
at seed time — the extends-closure pattern) with `relationalElement`
routed to Table OR View, which makes the read a PARTIAL-membership
dispatch in CHAIN position. The mechanism gap (batch 2 built the
instance-variable form only), exact walls: "class query under
TypedMatchRuntime is not resolvable yet" (`chain->match([...])`),
"object-space expression node TypedMatchRuntime is not substitutable yet"
(`->map(x|$x.nav->match([...]))`), and for the cast form "->cast(@T)
over a chain of U whose mapped members do not all conform (partial
membership) is not supported in chain position yet" — that last one HAS a
corpus witness: `meta::relational::validation::tests::milestoning::
testValidateQueryWithUnion` (`->cast(@RelationalActivity)` over
Activity). Semantics to build: retype the chain to the member set, gate
each read on the witness with fail('Cast exception') for non-conforming
rows (never a silent filter), reads of member properties through the
SUBTYPE_KEY dispatch. Then the View rows land on top.

**Harness burn-down leg 1 — chain-position type dispatch — LANDED
(2026-09-02; user ratified the FULL burn-down: every Java-scored test
runs on the platform through the one compile path).** `chain->cast(@Sub)`
over a partial-membership row keeps the union row GATED: `ChainDispatch`
adds a filter whose predicate RAISES on a non-conforming row
(`if($v->instanceOf(Sub), |true, |fail('Cast exception …'))` — pure's
cast exception, never a silent filter) and stamps `ClassSource.castGate`
so reads of the target's own properties are the value-position witness-
gated subtype reads (`Substitution` RowScope.castGate → castLeafRead);
`chain->match([...])` IS `chain->map(v|$v->match([...]))`;
`->map(o|$o.nav->match([...]))` splices the source for the parameter (the
class-result-map rule) onto the chain form. Two general fixes:
`routedTargetClass` returns ONE class only when every route of a
`prop[set1]`/`prop[set2]` property lands on it (the first route used to
win and a cast over the union target was judged total); the peeled
scalar read's leaf carries the property's own multiplicity over an all-
to-one path (the chain's `[*]` tripped the carrier stamp of a gated
read). Pinned: `ChainTypeDispatchTest` (raises + rows, incl. a two-route
navigation). Corpus: ratchet unchanged 841/1732, paired sweeps byte-
identical; the one chain-cast wall (`->cast(@RelationalActivity)` inside
the inlined `validate` body) moved to its next wall (the plan/text
family). Named: a partial cast BELOW a flatten hop, or a second cast on
one chain, stays loud.

**Harness burn-down leg 2 — views as main tables + ROW-arm match —
LANDED (2026-09-02).** `View` native = the real class (NamedRelation +
RelationalMappingSpecification; schema/primaryKey/columnMappings); View
rows (`views`, top-level views in `default`); ONE alias table for every
main-table alias — owned by a set (mapping_fqn + id) or by a VIEW (its
database + `view:<schema>.<name>`, identity in view_* columns) — with the
BASE TABLE resolved transitively through views of views at seed time
(base_* columns; the extends-closure pattern) read through the lite
association `TableAlias.base` (`AliasBaseTables`); `relationalElement`
routed to Table OR View (`relationalElement[tbl]`/`[vw]` — the proven
two-route shape); `mainTable` body = `$_this.mainTableAlias.base` (the
engine body `match([t:Table|$t, v:View|$v->mainTable()])` recurses; ours
reads the seeded base — the classMappingById/include-closure precedent).
`chain->match([...])` with ROW arms = the UNION of one filtered, cast
branch per arm (`ChainDispatch.chainMatchAsUnion`; normalized before the
chain walk, also for a class-result `map(x|…)` whose spliced body is such
a match); a scalar map and the whole-instance terminal DISTRIBUTE over a
class concatenate like project does. Pinned: `viewAsMainTable`
(relationalElement->cast(@View).name = AV; mainTable().name = ABC),
`ChainTypeDispatchTest.chainMatchWithRowArms`. Corpus: ratchet unchanged
841/1732, paired sweeps byte-identical; `concatenate::testAll` moved from
a wrong-SQL platform-fail to the named wall "lowering not yet implemented
for TypedSerializeGraph" (whole-instance over a concatenate now reaches
the graph lowering).

**CHARTERED — cast then navigate through the member's own slot (re-root
at the member set).** The union-ROW form carries member properties as
thread-local columns but NOT a member's join slots, so
`chain->cast(@View).mainTableAlias…` (the real mainTable body's view
arm) cannot navigate on the row. Exact wall: "->cast(@View) over a chain
of RelationalOperationElement (partial membership) below a flatten hop,
or a second cast on one chain, is not supported yet". The mechanism:
re-root the gated chain on the member SET's own extent (inner-join the
union rows to `View[vw]` on the member's key thread), so the chain
continues natively with the member's slots. No corpus witness yet; the
metamodel witness is the real `mainTable` body verbatim.

**Harness burn-down — GROUP F LANDED (2026-09-02, batch 7): 841/1732 →
**820/1753** (+21 flipped: 16 typeInference row-navigated tests, the 4 constructed-instance tests incl. joinStrings, testSubTypeMappingValidWhenMappedExplicitly); paired sweeps 5/6 byte-identical rosters at the landed state; scoreboard `tests` family 33/39 unchanged, corpus total unchanged; exec-passing 345 → 344 and M1 verified 83 → 82 (lane move, charter §8.3); metamodel quarantine 151 rows / 20 walls → 125 / 9 (the four refusal spellings retired); dual-channel 613 agree / 0 disagree.** The 20 typeInference tests of testRelationalExtension.pure +
testSubTypeMappingValidWhenMappedExplicitly are platform-scored; every
function they compose is a Pure body under its REAL name over seeded rows
(the six natives DELETED; the walk's mapping / set / property-mapping /
view / type handles DELETED; MetamodelSteps' six cases DELETED; the four
quarantine spellings retired).
- **Rows (MetamodelSeeds + OpSeeds → `RelationalOpRows`)**: `databases`,
  `schemas` (+ the engine's `default` for top-level tables/views),
  `properties` (class-declared stored properties + association ends a
  mapping binds), `data_types` (one row per column declared type and per
  inferred node type, m3 subclass simple name + size/precision/scale),
  `relational_ops` (every mapping / view expression as a NODE TREE: kind,
  parent, ordinal, dyna name, literal, column reference, inferred type,
  and the compiled primary-key columns as TableAliasColumn nodes owned by
  their set — `primary_keys` and the second `TableAliasColumn` set are
  GONE), `view_column_mappings`, `property_mappings` (effective across the
  extends chain, `declared_depth` says own vs inherited);
  `class_mappings.root` (the `*` set, else the class's sole own set —
  MappingValidator.validateStar), `mapping_includes_closure.include_rank`
  (the engine's visit order: includes first, the viewer last).
  Physical columns renamed around the composed-row prefix collision
  (`mapped_class_fqn`, `prop_owner_fqn`/`prop_name`, `col_*`, `itype_id`,
  `dtype_id`).
- **Metaclasses**: `CoreDataType` + 21 datatype kinds (real
  relational.pure:392-520), `Property` (name), `SetImplementation.root:
  Boolean[1]` + `class: Class[1]` (declared RAW — the normalizer classifies
  class-typed properties by NameRef; the generic form is a normalizer leg),
  `PropertyMappingsImplementation.propertyMappings`,
  `PropertyMapping.property`. DataType is an inheritance op over 21
  FILTERED sets of one table (the engine's single-table-hierarchy idiom);
  the op-node kinds likewise (5 filtered sets of `relational_ops`).
- **Bodies**: `_classMappingByClass` =
  `$_this.visibility->sortBy(v|$v.includeRank).visible.classMappings->filter(cm|$cm.class == $class)`;
  `rootClassMappingByClass` = real (`->filter(root)->last()`); `view` =
  real; `allPropertyMappings` = `->cast(@Root).effectivePropertyMappings`;
  both `propertyMappingsByPropertyName` overloads = real filter over it;
  `inferRelationalType` = `$rop.inferredType` (the compiler's stamp — the
  include-closure precedent; the engine recurses the type lattice per
  query, ours reads the compile-time fact; the recursive form over the
  node rows is the honest end state and is NOT built); `dataTypeToSqlText`
  = the real match over the 21 subclass rows.
- **Mechanisms (all general, each forced by a named test)**: M3 primitive
  names win in name resolution (a class called Integer/Date is reachable
  only qualified — `NameResolver`); a property-less inheritance member
  still gets its membership witness (`UnionSynthesis`); `last()` over a
  sorted chain = `first()` over the reversed sort (`ChainNormalizer`);
  element identity equality `$x.cls == Element` = the navigation's
  FOREIGN-KEY IDENTITY pseudo-binding `$fk:<prop>` (registered beside
  `$pk:` when the slot's join is one equality on the target's key —
  `ForeignKeyIdentity`; a plain row read, resolves in every scope);
  three union-projection gaps (association keys through union member
  threads — `AssociationJoins.memberAssocKeyReads`; nested union targets
  widened for downstream hops — `NestedUnionKeys`; hoisted steps over a
  union source — `Pipelines.widenConcatenateBelow`); a stripped slot in a
  nested scope splices the class's own step (`CorrelatedSubselects` ←
  `NavProvenance.spliceOwnStep`); sibling key typing reads the member's
  SOURCE row (`Pipelines.widenUnionMember` bug); system function bodies
  REPLACE a same-signature model function (the corpus carries the
  engine's own `inferRelationalType` source; resolve → inject → resolve,
  types compared by spelling); the metamodel seeds skip a corpus class
  that does not compile (one dangling protocol type broke every
  store-reading test); `innerizeFlattenJoin` descends a spliced sort; H2 spells
  `format(<literal template>, …)` as `||` concatenation with `%d`/`%s`
  slots CAST to VARCHAR (H2 has no printf — `H2.formatAsConcat`); a
  FILTERED single-table hierarchy (one ~filter per subclass set — the
  engine's own idiom, the datatype metamodel's 21 kinds) synthesizes as
  ONE scan with filter-gated columns and witnesses, the thread restricted
  to rows some member claims (`UnionSynthesis.mergedScan`; H2's planner
  re-evaluated the 21-arm union derived table per outer row over the
  corpus-sized store and hung — DuckDB hash-joined it); a lone projected
  thread widens for join keys like a union member
  (`Pipelines.widenConcatenateForKeys`).
- **Constructed instances as ROWS (the ruling's side-output rows)**: a
  query's `^DynaFunction(...)` / `^Literal(...)` / `^LiteralList(...)` tree
  over constants becomes `relational_ops` rows (ONE builder,
  `RelationalOpRows`) keyed by a content id, the expression becomes the
  member class's extent filtered on that id (`ConstructedInstances`,
  anchored like an element reference), and the rows ride the resolver →
  `ExecEnv.constructedSeeds` → the execution setup seeds them after the
  model's own. A row-valued argument (a navigated element) is admitted
  only under an argument-free type rule (`joinStrings` = VARCHAR(4000)
  whatever it joins) and seeds no child row.
- **Pinned**: `MetamodelQueryFunctionsTest` (7 cases: schemas/views, view
  column inference incl. join and view-on-view, class mappings by class
  through includes and `*`, property mappings by name incl. inherited and
  association ends, property inference incl. concat/plus/case, constructed
  instances, Column.type + dataTypeToSqlText).
- **Pins moved**: ratchet EXACT 841/1732 → 820/1753; exec-passing lane
  345 → 344 (charter §8.3 record); metamodel quarantine 151 rows / 20
  walls → 125 / 9; required-over-nullable ceiling 520 → 529 (the
  single-table hierarchies' kind-specific columns, non-null on their own
  set's rows); H2 lane pass floor 1329 held; M1 h2-exec verified floor 83 → 82
  (testSubTypeMappingValidWhenMappedExplicitly's assertSameSQL row-verifies
  through the oracle SPI now — lane move); native class count 213 → 236;
  native catalog −6; Java-eval ledger StatementExecutor 2571 → 2594
  (ExecEnv carries the side-output rows, no evaluation), OpSeeds
  registered; reach-back census MetamodelWalk 2 → gone, MetamodelSeeds 1
  (property mappings live only on the parse artifact).
- **Named residue**: embedded / inline-embedded / otherwise-embedded and
  local-property mappings seed no rows; a join-slot mapping seeds its
  terminal column only (no JoinTreeNode rows); `Mapping.associationMappings`
  and the aggregation-aware half of `_classMappingByClass` are unseeded;
  `SetImplementation.class` is raw `Class[1]`; `dataTypeToSqlText` omits
  the Boolean / Json / DbSpecificDataType arms (no store type models them);
  `inferRelationalType` reads a stamp, not a recursive query;
  `testTranslateDbType` (extension-lambda `TranslationContext`) stays a wall.

**Batch 8 (2026-09-02) — chain speed: the system database + two normalizer
bugs (docs/GATES.md "Budget BREACH, 2026-09-02").** Ratchet UNCHANGED
820/1753; corpus report byte-identical; H2 verdicts identical. Landed:
- **THE SYSTEM DATABASE (user ruling: "insert once at first compile,
  separate from user connections").** `exec/SystemDatabase`: one in-memory
  database per GRAPH per engine (the engine follows the session: H2 lane
  stays on H2), created and written the first time a query of that graph
  reads the metamodel, alive as long as the graph
  (`ModelContext.derived` — graph-lifetime facts, overlays share them).
  The executor ROUTES a store-reading body to it (`routeSystemStore`);
  `seedMetamodelStore` (per-execution DROP+CREATE+INSERT) DELETED. A
  query's constructed instances insert content-addressed (once per id).
  A body reading the store AND a user store is LOUD (census: none).
- **Normalizer**: `mergedScan` compared branch expressions by PRINTING
  them (quadratic) — record equality; `collectInheritanceMembers` scanned
  the whole class universe with chain walks per inheritance op — a
  direct-subclass index (`ModelBuilder.directSubclasses`,
  `Pure.directNativeSubclasses`), same membership and order. Model
  compile 28.2 -> 8.0ms.
- **Harness**: `TimingLedger.addNamed` — the corpus ledger lists the 30
  slowest tests (`slowest` rows in target/timing-ledger.txt); allgates
  keeps g4/g5 logs on failure like g1. (The corpus filter ALREADY exists:
  `-Drcorpus.only=<family-substring>`, `-Drcorpus.test=<test>`.)
- **Vocabulary**: "model" here = the GRAPH (the whole compiled universe:
  every package, dependency and the platform; `Class.all()` = every class
  in it). One system database per graph; many graphs only in the test JVM.

**Batch 9 (2026-09-02) — ONE TABLE for the RelationalOperationElement
hierarchy.** tables/columns/views/table_aliases/relational_ops →
`relational_elements` (kind, id PK, 29 columns; the ONE row layout lives in
`RelationalOpRows`' factories); Table/View/Column/TableAlias and the five
node kinds are FILTERED sets over it; every cross-kind join is a self-join
(`{target}`). General fix: a self-join between two DIFFERENT classes over
one table oriented backwards in `AssociationSynthesis` (the same-class
convention was applied) — orientation now follows the property line's
source set. Rosters, corpus report and H2 verdicts byte-identical;
required-over-nullable ceiling 529 → 533 (Table/View/Column/TableAlias
`name` over the shared column — the idiom's cost, recorded on the pin).
The H2 lane is STILL ~139s: the extent is still a UNION ALL of six
branches because the single-scan collapse skips members WITH navigation
chains (Column's 21 type routes, View, TableAlias, TableAliasColumn), and
member keys are CASE-gated + OR-joined — item (2) below. Denormalization
check (user question): table-per-POLYMORPHIC-hierarchy, nothing repeated;
the one smell is TableAlias' nine name-triple reference columns — item (6).

**Batch 10 (2026-09-02) — UNION LOWERING for single-table hierarchies:
the H2 lane 137s → 41s (ten typeInference tests 9–18s → <1s).** Three
general mechanisms, rosters / H2 verdicts byte-identical, ratchet 820/1753:
- **Every filtered member of one table merges into the ONE scan**: a
  member's scan source is its table wrapped in its own navigation SLOTS
  (demand-driven, free when unused), so the group key is the innermost
  table and the merged scan carries the deduped union of the members'
  slots (`UnionSynthesis.ScanSource`; same-named slots must agree). The
  merged projection carries the UNION-SCAN MARKER `meta::legend::lite::
  unionScan` (identity native, INTERNAL_DESUGAR 13→14): the resolver's
  "is a union" facts (`Pipelines.containsConcatenate`, member-key
  widening, nested-slot demands, the slot walk, milestone pushdown) read
  the node kind where a concatenate no longer exists; lowering is erasure
  (`RelationPredicates.isRelationIdentity`).
- **THE SHARED TABLE KEY**: routes into members of ONE table keyed on its
  sole PRIMARY KEY emit `src = t.<key>__pk_<table> AND (t.<key>_<k> IS
  NOT NULL OR …)` — one indexable equality on the ungated shared key
  (projected once by every thread over that table; the merged scan
  collapses it to the plain column) AND the members' gated keys carrying
  membership exactly as the per-member OR did (`JoinChainEmission.
  sharedTableKey`; `UnionSynthesis.sharedKeyName`). PROBED AND REJECTED:
  the ungated key ALONE as the join key — two routes with different
  source columns (ResolveUnionTest FirmID/LegacyID) matched the wrong
  member's rows; a primary key names one row, not one member.
- **Same-source members coalesce**: a union's own lift whose members read
  the SAME source column against one target expression contributes ONE
  disjunct, `coalesce(s.col_k1, coalesce(…))` (at most one non-null per
  row; `UnionSynthesis.coalesceReads`) — the inferredType hop's 105
  entries (5 op kinds × 21 datatype kinds) are one probe. Identical
  disjuncts dedupe (`orDistinct`).
- Also: a self-join orientation fix rode in batch 9; `PhysicalTables`
  (schema-aware table lookup) split out of MappingNormalizer.

**Batch 11 (2026-09-02) — THE BOOT LAYER (option 1, user-ratified): the
system metamodel prepared once per process.** `Compiler.bootLayer()`: the
system elements are name-resolved and normalized ONCE, content-addressed
by the hash of their Pure source in a `ContentStore` (Invariant 3 — the
artifact persists across compiles), and entered into every graph's index
exactly like the graph's own elements (`normalizeWithSystem`: the graph's
own elements normalize, then the prepared system elements join the
normalized model; poisons / legacy surfaces / mixed unions / the
[1]-over-nullable census union). Protected as system: a graph element
redefining a system ELEMENT is an error (`SystemMetamodel.
withoutSystemShadows`); a same-signature system FUNCTION still replaces
the engine's own source riding in the corpus. The graph's name
resolution sees the system FQNs as known (`NameResolver.
resolveAlongside`) — a graph function calling `rootClassMappingByClass`
or `view` by imported bare name resolves as before (the corpus's
toPostgresModel / viewToTDS tests witnessed the miss). Model compile
8.0ms → **0.5ms** (2.3ms before group F); G1 57s → **29s** (33s before
group F). Rosters, corpus report, H2 verdicts byte-identical.
Why option 1, not a fall-through boot index: a context's compilers are
built over the graph's element INDEX directly, so a fall-through would
have to live in every lookup door of the index (dozens, id-numbered per
index, on the type-check hot path) plus the integrity pass — for ~0.3ms
per graph compile (indexing 78 prepared elements), which is once per
process outside the test JVM. Per QUERY both options cost nothing.

**Batch 12 (2026-09-02) — ELEMENT REFERENCES BY ID.** The alias rows'
nine name-triple reference columns (main/view/base as db, schema, name)
and the op nodes' four column-reference columns become three + one
element ids (`main_element_id`, `view_element_id`, `base_element_id`,
`col_element_id`; ids are the deterministic `tbl:` / `view:` / `column:`
spellings `RelationalOpRows.tableId/viewId/columnId` the rows already
carry). Every cross-kind join is now ONE primary-key equality
(AliasToTables/AliasToViews/AliasToBaseTable/ViewToAlias/OpToColumn);
relational_elements 29 → 21 columns. Rosters, report, H2 verdicts
byte-identical. `DynaFunction.parameters` via parent_id waits for a
witness.
**Regression fixed on the way (batch 11's):** the boot layer merged the
two layers' `requiredNullableRows` with a key-REPLACING map union; the
census is keyed by bucket ("direct"), so the system layer's 13 direct
witnesses replaced the corpus's 500 and the shrink-only ceiling passed
silently at 46. Now a per-key SET union; the count is 533 again. Lesson:
a shrink-only pin cannot catch a census that lost its input — print the
count in the lane log (it is) and READ it after a change to the merge.

**DESIGN — constructed instances as inline relations, scoped (user
ruling 2026-09-02: "I hate that we opened a write path to something that
should be read-only"; folding types onto the element row REJECTED as a
hack).** Facts: the resolver already computes every row of a constructed
tree (element rows + datatype rows + the type ids linking them); the
tree is CLOSED (its nodes reference only its own nodes and types — a
real column inside it is admitted only where no child row is needed);
`TypedTds` is an inline relation literal lowered to VALUES
(`Lowerer.tdsLiteral`, cells typed by the row schema, NULL cells). Plan:
(a) a class source resolved for a constructed root CARRIES ITS SCOPE
(`ClassSource.scope`, the tree's content id) and `ClassSources.get`
REQUIRES a scope argument (no scope-less overload — 61 lookup sites in
the resolver, each passes its source's scope; compile-enforced, not a
convention): navigation targets, subtype casts, correlated subselects
and graph emission inherit the scope of the source they serve. Dynamic
scoping (resolver state) was REJECTED: a real column's type read in the
same expression would wrongly read the inline rows. (b) Inside
`ClassSources`, under a constructed scope the table-scan leaves of the
element and datatype store tables become `TypedTds` literals of that
tree's rows typed with the leaf's own row type; the memo key includes
the scope. (c) DELETE the side-output seeds (`ConstructedInstances.
seeds`), `ExecEnv.constructedSeeds`, `SystemDatabase.insertConstructed`:
the system database is read-only after the graph's rows. Witnesses: the
four typeInference constructed tests + `MetamodelQueryFunctionsTest.
constructedInstances`; rosters byte-identical. ~3h + sweeps.
**FEASIBILITY HOMEWORK (2026-09-02, verified, not guessed):** (i) a
probe ran an inline VALUES relation with all-NULL columns, kind-gated
CASE reads and a join to a second inline relation on DuckDB AND H2 —
identical correct rows, an all-NULL column compared with a string
filters correctly; (ii) `TypedTds` copies rows null-rejecting — absent
cells must be `PlatformTypes.TDS_NULL_CELL` (`Scalars.tdsCell` maps it
to NULL); (iii) navigation targets have ONE owner, `ClassSources.
getForNav` (6 callers); the general `get` has 55 sites across ten
resolver files, every one with the source `cs` in hand — scope threading
is mechanical and compile-enforced once the scope-less overloads go;
(iv) the constructed root's class source is fetched in flattenSource with
`RoutingContext.contextKey(chainContext)` — the scope rides the Context
and the key; (v) `TypedTableReference.info()` IS the table's row type
(the literal's type); `ClassSource` is a record (copy with the rewritten
pipeline); the memo key already carries the context key; (vi) downstream:
`Pipelines.walk` passes a slot-free leaf (walkOpaque), milestoning never
touches the store, the lowering has the literal case, `routeSystemStore`
stays correct for pure-constructed (no store ref → user session, no
store needed) and mixed queries.

**Batch 13 (2026-09-03) — CONSTRUCTED INSTANCES AS INLINE RELATIONS; the
system database is READ-ONLY after the graph's rows.** Built exactly as
designed above: `ClassSource.scope` (the constructed tree's content id;
null for the graph's stores); `ClassSources.get` / `getForNav` REQUIRE
a scope (the scope-less overloads are gone — 71 sites now pass their
source's `cs.scope()`, a graph root's `context.constructedScope()`, or
`null` with a stated reason for the two binding-shape probes and
ClassSources' own building blocks, whose assembled pipeline is rewritten
ONCE by `ClassSources.scoped`); `NavMaterializer.navTargetMaterialized`
and `NestedUnionKeys.pipeline` carry the scope. Under a constructed scope
the store's table leaves become `TypedTds` literals of the tree's rows
(`inlineStoreLeaves`; absent cells = `TDS_NULL_CELL`), typed with the
leaf's own row type; the memo key includes the scope. The chain rooted at
a constructed instance sets `Context.constructedScope`
(`StoreResolver.collectOpChain`). DELETED: `ConstructedInstances.seeds`,
`ExecEnv.constructedSeeds` / `withConstructedSeeds`, `SystemDatabase.
insertConstructed` + the session's constructed ids. The generated SQL
carries `(VALUES (...)) AS _tdsN(...)` for BOTH the element rows and the
datatype rows of a constructed tree. Witnesses green (7/7 metamodel
query functions, the four corpus typeInference constructed tests);
rosters, report and H2 verdicts byte-identical; census 533; H2 lane 38s.

**DESIGN — THE NORMALIZER'S MAPPING-FACT OWNER (user condition 2026-09-02:
"I would do it if part of the work is to actually clean up the API so
these call sites have a clean owner").** Census (2026-09-03): nine
static helpers, 88 call sites — UnionSynthesis 26, MappingNormalizer 17,
AssociationSynthesis 9, XStorePureEnds 8, JoinChainEmission 5,
SetDispatch 4, ImplicitInheritance 4, M2mRouteGuards 1: `setIdOf` 28
(string-replace spelling per ask), `findSetById` 14 (linear walk over
own sets, then the include closure), `collectMappingClosure` 11,
`unionForClass` 10 (own sets first, then includes in order, first found
wins — recursive, never memoized), `collectIncludedSetIds` 8 (nearer
include wins; store SUBSTITUTION composes through the include chain into a
LOCAL map, then merges in include order), `memberOrdinalOf` 7 (set id OR
extends-lineage match), inheritanceMembers/collectInheritanceMembers 7,
`collectRootClassMappings` 3 (the `*` set, else a class's sole set).
OWNER: `normalizer/MappingFacts` — ONE object per `LegacyMappingDefinition`
INSTANCE (the mapping passes through five rewrites in normalizeMapping —
resolveExtends, ImplicitInheritance.apply, qualifyStoreRefs,
implicitOpsForAssociationEnds, injectMultiHopAssociationPMs — each a new
instance whose facts differ; key by IDENTITY, never by name), memoized on
the `ModelBuilder` for the model's lifetime (`ModelBuilder.mappingFact(md,
kind, derive)`, IdentityHashMap — the derived-fact idiom, no static
sinks). API: `idOf(set)`, `setById(id)` (own then included, own wins),
`includedSets()` (the substitution-composed map), `closure()`,
`unionOf(class)`, `rootSetOf(class)`, `extendsChain(set)`,
`memberOrdinal(memberIds, setId)`, `inheritanceMembers(op)`. Every
site becomes `MappingFacts.of(md, model).x(...)`; the nine statics are
DELETED (not kept as pass-throughs). Verification: byte-identical paired
sweeps (the include-order and first-wins rules are pinned by the
rosters); model compile stays 0.5ms (the system layer is already
prepared once) — the value is one owner for these questions.

**NEXT (user-ratified order 2026-09-02, enumerated):**
(1) DONE (batch 9). (2) DONE (batch 10). (3) DONE (batch 11, option 1). (6) DONE (batch 12). Inline relations DONE (batch 13). NEXT = MappingFacts (design above, ~3h fresh session), then group D. — was: UNION LOWERING for single-table hierarchies: merge
members WITH chains into the one scan (each chain a join on the shared
scan guarded by the member's kind predicate); emit the key UNGATED when it
is the scan table's PK and dedupe identical OR terms → `op_id = id`, an
indexed lookup on H2 (the ten 8–16s typeInference tests); re-arm the
5.5-minute chain budget. (3) BOOT LAYER. (4) NORMALIZER PER-MAPPING INDEX.
(5) CONSTRUCTED INSTANCES AS INLINE VALUES. (6) ELEMENT REFERENCES BY ID:
TableAlias main/base and Column's table as element ids, not name triples;
`DynaFunction.parameters` via the parent_id self-join when a witness
demands. (7) Group D → Q → A. Details of (1)–(5) as first written:
(1) ONE TABLE for the
`RelationalOperationElement` hierarchy — merge tables/columns/views/
table_aliases/relational_ops into `relational_elements` (kind, id PK,
superset columns; the datatype/op-kind idiom), with the plain-`id` key
read for merged members: the H2 lane's ten 9–18s typeInference tests
(UNION ALL extent, unindexable on H2) become indexed lookups. (2) The
BOOT LAYER: system elements normalized + compiled ONCE per process,
keyed by the hash of the system source in the content store (Invariant
3 — it persists across compiles, so it is content-addressed); every
graph's context falls through to it for names it does not define;
extents (`Class.all()`) union boot + graph; boot rows written once. A
user compile then normalizes ONLY its own elements (target: the
pre-group-F 2.3ms and below). (3) NORMALIZER PER-MAPPING INDEX (general,
speeds every corpus mapping): the remaining 5.7ms/compile is facts
re-derived per call — `setIdOf` re-spelled per lookup inside linear
`findSetById` scans, `collectRootClassMappings` rebuilt per
inheritance-member collection, `unionForClass` uncached across its ~10
call sites, `memberOrdinalOf` chain walks. One index object per mapping
normalization (set id -> set, root set per class, extends chain, union
per class) built at entry and read everywhere — a LOCAL with one call's
lifetime, not a cache (nothing to content-address). Verified by
byte-identical paired sweeps. (4) CONSTRUCTED INSTANCES AS INLINE VALUES
(user 2026-09-02): a query's `^DynaFunction(...)` trees today insert
content-addressed rows (once per id) into the graph's system database —
a per-query write into shared state; end state = the query carries its
own constants as an inline relation (VALUES), the system database holds
ONLY facts of the graph and is never written per query (the executor
loses write access to it). (5) Then group D below.

Batch 8 chain (gates9.log): G1 54s, G2 9s, G4 64s, G5 134s, G6 104s,
G7 28s, G9 20s, G8 72s — **8m05s** (was 12m54s).

**BURN TO ZERO — the next session's goal (user, 2026-09-03): compile and
run EVERYTHING from the platform and delete all harness badness.**
STATE at b103582a (all pushed): ratchet **820 fallbacks / 1753 flipped**
(WholeTestFlip, EXACT pins in RelationalCorpusRunner.scoreboard),
exec-passing lane 344, metamodel quarantine 125 rows / 9 walls,
required-over-nullable 533, chain ~5m50s (G1 39s, G4 62s, G5 ~40s, G6
~80s, G7 25s, G9 19s, G8 72s), model compile 0.5ms, the system database
read-only after the graph's rows (batches 8–13 above).
DEFINITION OF DONE: every test the harness still scores in Java runs
through the ONE compile/router path and the database's verdict — the
fallback lane, the quarantine channel and the census pins are DELETED,
the referees stay (H2Verify, TdsCompare, the replay oracle). RESIDUE IS
THE LAST RESORT (user, 2026-09-03): a test may be left behind ONLY when
(a) running it on the platform makes no sense (it asserts a fact about
the Java runtime itself) AND (b) no emulation or validation route exists
— and the search for that route is part of the batch, not an
afterthought. The precedent is the H2 SQL REPLAY ORACLE: an SQL-text
golden the platform will never spell byte-identically is VALIDATED by
executing the golden against the same data and comparing ROWS — the
verdict moved into the database instead of being declared out of scope.
Every other "can't" must first be tried as one of: seed the fact as rows
(metamodel-as-relations), carry it in the query (inline relations),
validate by replay/referee (rows, not text), or emulate the engine
behavior in the dialect. What remains is a NAMED list, one line of
reason each, never a bucket; "engine defect" (null-vs-value) counts
only with the foundation probe's adjudication cited.
THE BURN MAP (fallbacks by census group; homework §1 + the bucket dump —
cut legs BY GROUP, never by wall label; the 64-test "TypedMap (HN
vocabulary)" bucket is heterogeneous):
- D harness vocabulary 43 — `meta::legend::executeLegendQuery` /
  `compileLegendValueSpecification` as the ROUTER's string entry
  (compile-from-string through the one router). FIRST.
- Q plan reads 13 (+ printers ~26) — plan nodes as ROWS (homework §2e):
  name the tests from the bucket dump first.
- A expressionSequence / metaprogramming 70 + E scanRelations 21 + I
  LambdaFunction reads 6 + H InstanceValue trees 4 — EXPRESSION TREES AS
  ROWS (homework §2a: node table per tree kind, `expressionSequence`,
  `parametersValues`, `evaluateAndDeactivate`); the biggest group.
- J misc unported 17, Z other metamodel-typed 18, N unknown metamodel
  types 9, G toPostgresModel newState 10, P routerExtensions 5, O TDG
  Pair typing 4, B/C/M/K small named shapes.
- The non-metamodel buckets (from the dump): text-policy 65 (SQL-text
  verdicts — the charter's rows-are-the-verdict rule; each is a named
  decision), join-condition-reads-a-whole-variable 43, no-scalar-lowering
  36, filter-predicate-isolation 25, parametersValues binding 17,
  execution activities 14, unknown functions ~38, multiplicity 11, array/
  list/struct dialect capabilities ~30, misc.
HARNESS CODE THAT DIES WITH THE BURN (delete as each family flips, never
before): the rest of MetamodelWalk (905 lines), MetamodelSteps, PlanText,
AggAwareActivities, StatementExecutor's walk arms, the fallback lane in
RelationalCorpusRunner, WholeTestFlip's quarantine channel, the census
pins (JavaEvalLedger funnel registers shrink, never grow).
METHOD (unchanged, user-ratified): name the exact fallback tests a batch
targets and the expected flips BEFORE building; build the seeds/bodies or
resolver leg; delete the Java arms they retire; land only when the count
moved; a test that moves to a different wall is named and the family is
pursued until it flips; one gate chain per batch; push after green; pins
move only with their burn and a written justification.
SPEED LOOP (use it): `-Drcorpus.only=<family-substring>` /
`-Drcorpus.test=<test>` (scoped runs exit 1 on the full-run pins —
expected); `LEGEND_LITE_DUMP_SQL=1` prints every executed SQL;
`target/timing-ledger.txt` has bucket totals + the 30 slowest tests;
ALWAYS `cd /Users/neema/legend/legend-lite` in every command (the parent
directory holds a STALE core module); zsh does not word-split `"$R"` —
pass `-D` args as separate words; the H2 lane overwrites core/target
rosters — diff the DuckDB rosters BEFORE running H2; G8 cleans
core/target — save rosters before a chain.
DEFERRED WITH DESIGNS WRITTEN (ride a burn, do not stand alone):
MappingFacts (the normalizer's mapping-fact owner, design above);
the 5.5-minute chain ceiling re-arms when a chain measures ≤330s.

**Batch 14 — GROUP D leg 1, the ROUTER'S STRING ENTRY (2026-09-03):
ratchet 820/1753 → 791/1782 (+29, no losses; chain 5m49s, all green).**
Mechanisms (all platform-side, on the one compile/router path):
(1) `compileLegendValueSpecification($treeString)` folds AT PARSE TIME
when `$treeString` is a let-bound literal-string constant of the same
body (SpecParser keeps a scope stack of string constants; lambda
parameters shadow; QuotedSpecParser.fold resolves `$var` through it);
the `let tree = …->cast(@RootGraphFetchTree<T>)` binding parks as a
deferred let (Typer.deferredLetRhs) and resolves at its graphFetch/
serialize consumer (GraphFetchChecker.unwrapCompiledTree strips the
cast) — 13 testSubTypeGraphFetch flips. (2) `execute()` whose query is
`if(<literal>, |{|q1}, |{|q2})` selects the branch STRUCTURALLY
(ExecuteChainAssembly.peelSelections, the literal read through the let
prefix — the Impl(checked, expected) helper). (3)
`meta::legend::executeLegendQuery` (devUtils.pure:30/:35, both
signatures registered VERBATIM) is a RESULT FRAME beside
router::execute (PlatformTypes.EXECUTE_LEGEND_QUERY, HANDLE):
ExecuteChainAssembly.prepareLegendQuery binds the query lambda's
parameters from the vars pairs as LEADING LETS coerced by the declared
parameter type (enum name → enum value, date string → date literal —
the engine's JSON-borne variable coercion; lets keep the `$var`
spelling the serialize keys need), the chain rides `chain()` with a
null mapping ref (every branch carries its own from()), and
legendQueryEnvelope emits the engine's result JSON OVER THE CHAIN by
shape: serialize root → `{"builder":{"_type":"json"},"values":…}`
(joinStrings), primitive scalar root → toString (the platform-ops
witnesses assert 'false'); TDS/class/String roots are NAMED walls
(leg 2). A bare inline call splices to the envelope
(ResultEnvelopeSplice). Nil (the []-born value) now conforms to CLASS
formals in the kernel (`executeLegendQuery($f, [], ext)` against
`Pair<String, Any>[*]`). A from() whose runtime is a LET-BOUND
variable collects the let's setup SQL through the alias channel
(FromChecker + TypedFrom.sqlSetupsInRaw — the m2m2r
`getModelChainRuntime` shapes seeded only by neighbour tests before).
XStore milestoning 4, m2m2r milestoned 5, platformOperations 4 flips.
(4) `compileLegendGrammar(<const>)` over a FUNCTIONS-ONLY payload folds
at parse time to the two-faced QuotedGrammarCall (wire = the call,
pipeline = each function as its lambda); the typer types it as the
lambda collection and peelSelections reads `->at(i)->cast(
@FunctionDefinition<…>)` structurally — 3 testGraphFetchMilestoning
flips. Guards moved with receipts: native-catalog golden +2 lines;
string-dispatch count held (CoreFn.of for cast, a named FQN set in the
parser).
GROUP D REMAINDER (named, each with its route): JSON navigation over
the result string — `parseJSON()->cast(@JSONObject).keyValuePairs->
filter(kv|$kv.key.value=='result').value`, `.values`, `->size()`,
toCompactJSONString/toPrettyJSONString, `^JSONArray(values=…->sortBy)`
(runLegendTest 4: slice/take/limit/drop WithVariables; paginate 2;
enumPushDown testPushDownProjectWithParameter; subType
testInheritanceMappingWithoutSubType +
testSubTypeAtRootLevelWithInheritanceMapping) = LEG 2: the meta::json
classes (json.pure:32-70, verbatim) ride the VARIANT lane by emission
(parseJSON = JSON cast; member get = VARIANT_GET; `.values` =
VARIANT_ELEMENTS; `.value` = to(@String/@Number); casts within the
family identity; compact/pretty = the JSON text — DuckDB probe
2026-09-03: `-> '$.*'`, `CAST(… AS JSON[])`, list_transform/flatten,
json_group_array(json_object(…)), json_pretty all verified) plus the
tdsBuilder/classBuilder envelopes for TDS/class-rooted queries
(json_object('columns',…,'rows', json_group_array(json_object('values',
json_array(cols))))). testParametrizedEnumFilter — the from() runtime
is a `^$runtime(connectionStores=^$connectionStore(connection=
^$connection(testDataSetupCsv=…)))` COPY chain over a navigated
connection store: the CSV seed route (Ddl.setUpDataSqlsText over the
store's tables) through the alias channel, next. testSpecialUnion_m2m2r
— `class Person is not mapped in mapping FirmsAndEmployees_M2M`: an
M2M union-root mapping resolution gap (family: graphFetch union
rootLevel), not the string entry. XStore
testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedProperty
Constraint / …ZeroToOne — a MODEL in a string (classes, mappings,
connection, runtime + functions): the route is the compile-once
overlay admitting grammar payloads (the carrier refuses non-function
payloads loudly); a separate leg. Adjacent (use compileLegendGrammar,
other walls): testMilestonedProperty,
testMilestonedRootAndMilestonedProperty (graphFetch milestoning),
testFlatten_ViaNoArgMapping(_ViaAssociation) (from() mapping argument
is a let-bound helper CALL — `getNoArgMapping()` builds ^Mapping).
Harness arms still standing (they serve the named remainder): ElqSplice,
clgArm, the walk's QuotedSpecParser.fold site — each dies with its
last fallback.

**Batch 15 — GROUP D leg 2, the meta::json TREE on the variant lane
(2026-09-03): ratchet 791/1782 → 782/1791 (+9, no losses; chain 5m56s,
all green).** The `meta::json` classes (real json.pure:32-70, verbatim
— JSONBoolean/String/Number/Null/Array/Object + JSONKeyValue) are
registered natively and their VALUES ride the variant lane
(`PlatformTypes.isVariant` covers the family): a JSON element IS the
database's JSON value; the classes are its kinds. Reads type BY
EMISSION in `JsonChecker` onto two HIR nodes — `TypedJsonAccess`
(MEMBER = `keyValuePairs->filter(kv|$kv.key.value == key)` and
`getValue(key)`, MEMBERS = unfiltered `keyValuePairs` (`-> '$.*'` as a
list), ELEMENTS = `JSONArray.values`, TEXT/NUMBER/BOOLEAN = the scalar
kinds' `.value` through the `'$'` extraction, IDENTITY =
`JSONKeyValue.value`; a many receiver auto-maps and the list map
FLATTENS the MEMBERS/ELEMENTS mappers) and `TypedJsonResult` (the
string entry's tdsBuilder / classBuilder RESULT envelope over a
TDS/class-rooted chain — `{"builder":…,"activities":[{"_type":
"relational","sql":<engine-style render of the chain>}],"result":
{"columns":[…],"rows":[{"values":[…]}…]}}`, one scalar subquery; the
class kind wraps the graph emission's objects). `parseJSON` = the JSON
cast (with the engine parser's one tolerance the assert reader already
mirrors — `}{` reads as `},{`), `toCompactJSONString` = the JSON text,
`toPrettyJSONString` = `json_pretty` (Spellings row; typed VARCHAR),
casts within the family are identity, `^JSONArray(values=…)` emits
`toVariant(values)->cast(@JSONArray)`. New SQL node `SqlExpr.JsonArray`
(`json_array(…)` / H2 `JSON_ARRAY`), owner `lowering/JsonEmission`
(+ `JsonLane` for the rules, incl. fromJson — the variant lane's one
owner). Executor: a helper whose body is a STATEMENT SEQUENCE (non-let
intermediates, through thin forwarding overloads) runs as one
(`executeCallStatement`) — AFTER the assert root arms; an inlined
string-entry call re-offers to the frame splice after argument
substitution (its query argument is a helper parameter until then);
α-renamed query parameters (`_i<n>`) bind by POSITION; an inline frame
runs the runtime's setup SQL before its read; a helper that β-reduced to
an assert over a string-entry read is adjudicated post-inline (scoped —
adjudicating every inlined assert regressed ~200 text-golden flips).
List `sortBy` now zips element indices (`list_zip`) instead of indexing
the source inside lambdas (DuckDB refuses subqueries in lambdas; the
(k,i,v) struct sort keeps the stable tie-break; positional
`struct_extract(z, 1)` for list_zip's unnamed structs). Flips: slice/
take/limit/drop WithVariables 4, paginate 2 (the activity SQL text
matched the engine golden byte-for-byte), enumPushDown 1,
testSubTypeGraphFetch 2 (all 15 of that file now flip). Guards moved
with receipts: native-class count 236 → 243, JavaEvalLedger
StatementExecutor 2594 → 2680 (orchestration only), text-only 40 → 35;
Lowerer/Scalars/StoreResolver held under 3500 by owner extraction.
GROUP D REMAINDER (named): testParametrizedEnumFilter (CSV runtime COPY
chain over a navigated connection store — the CSV seed route through the
alias channel), testSpecialUnion_m2m2r (M2M union-root mapping
resolution), XStore …DatePropagationForMilestonedPropertyConstraint /
…ZeroToOne (a MODEL in a string — the compile-once overlay leg).
Adjacent: testFlatten_ViaNoArgMapping(_ViaAssociation) (from() mapping
argument is a let-bound helper CALL building ^Mapping),
testMilestonedProperty (plan-text golden), testMilestonedRootAndMilestonedProperty
("trailing JSON" — a JSON text shape).

**Batch 16 — GROUP D remainder, let-bound runtimes and CSV seeds
(2026-09-03): ratchet 782/1791 → 780/1793 (+2, no losses; chain 5m56s,
all green).** A from() whose runtime argument is a LET-BOUND variable
(the string-entry query shapes: `let runtime = ^EngineRuntime(…)` /
`getModelChainRuntime($m)` / a copy with inline test data) now TYPES
the let's rhs through the alias channel and the same collectors read it
— chain mappings (the ModelChainConnection an M2M union root resolves
through), JSON sources, setup SQL — plus the CSV half: `testDataSetupCsv`
on a LocalH2 specification / a TestDatabaseConnection is recorded on the
node as a FACT (`TypedFrom.csvSetups`: block text + the enclosing
connection store's `element`; a COPIED connection's store found by
structural navigation of the copy's source — `$runtime.connectionStores
->at(0).connection->cast(…)` through lets and zero-arg helpers) and the
EXECUTOR turns it into seed SQL (CsvSeed) when it establishes the
connection, exactly like the SQL setups (the compiler never reaches into
exec — invariant 6e caught the first cut). +2 = testSpecialUnion_m2m2r,
testParametrizedEnumFilter. Left in group D (named): XStore
…DatePropagationForMilestonedPropertyConstraint / …ZeroToOne — a MODEL in
a string (the compile-once overlay leg). Known limit: a copied
connection whose source navigates a one-ARGUMENT helper
(`testRuntime()` → `testRuntime(db)`) resolves no store — the CSV then
seeds without DDL (the family table already exists; loud otherwise).

**Batch 17 — GROUP Q opener (2026-09-03): ratchet 780/1793 → 778/1795
(+2; chain 5m56s, all green).** `meta::pure::executionPlan::executionPlan`
is registered VERBATIM (`f:FunctionDefinition<Any>[1]`,
executionPlan_generation.pure:25-50; the six per-arity
`Function<{Any[1]->Any[*]}>` overloads were an invention that rejected
`bd:Date[1]` / `Integer[0..1]` query parameters by contravariance —
deleted). +2 = testDefaultOptionalParamIsNullSafe,
testFilterInWithResultSorcedFromAnExpression. The other ten group Q
tests now type and reach ONE wall: "class query under TypedMap (HN
vocabulary)" on the plan-node navigation
`$result.rootExecutionNode.executionNodes->filter(n|$n->instanceOf(
RelationalInstantiationExecutionNode))->at(0).executionNodes->at(0)
->cast(@SQLExecutionNode).sqlQuery` read INSIDE an assert side
(assertEqualsH2Compatible): the statement executor's `planWalk` (a Java
plan-node evaluator — harness badness, dies with the burn) answers such
chains only at a statement ROOT; the honest route is PLAN NODES AS ROWS
(homework §2e): the plan handle's nodes seeded as rows of the system
database (kind, parent, ordinal, sql text, result columns), the reads
lowered as ordinary relation navigation, `sqlQuery` judged by the SQL-
text referee (replay). testLegacyFlagRestoresOptionalParamFreeMarkerSelector
is a plan-TEXT spelling (the legacy `optionalVarPlaceHolderOperationSelector`
freemarker form) — named. testMultiExpressionWithPlatformAndFromFunction:
"PureExp source printing for TypedMap pending" (plan-text of a map
expression) — named.

**Batch 18 — GROUP Q: PLAN NODES AS ROWS (2026-09-03): ratchet 778/1795
→ 729/1844 (+49, ZERO lost; chain 5m48s, all green).** The executor's
plan model (PlanNode — the lowering's product) rides the query as inline
rows of the system store's `plans` / `plan_nodes` / `plan_template_
functions` / `plan_function_parameters` / `plan_node_closure` tables
(`plan.PlanRows`, keyed by the handle's call-site id; `PlanAllocations.
registerPlanRows` registers them under the let binding; the graph-
lifetime store seeds NONE of them — MetamodelSeeds). The plan reads are
ordinary navigation over those rows, resolved by the ONE resolver:
- member-union hops COMPOSED: each hop's subtype witnesses register
  under the hop's own prefix (`registerSubTypeSubs(..., hopPrefix)`,
  belowScope registers per op without the sources registry), the
  top-level table under the composed prefix with a lenient anywhere
  fallback; a union-threaded key on a composed row reads as the coalesce
  over its member threads (`FlattenOps.coalesceThreadedReads`, applied by
  flattenSource / registerAssociationJoins / NavProvenance.spliceOwnStep);
  composed sources CARRY the constructed scope (three ClassSource sites +
  spliceOwnStep) so every hop's target reads the inline rows.
- `chain->cast(@Sub)` BELOW a flatten hop = a PSEUDO-HOP
  (`ChainDispatch.pseudoHop` → `CastReRoot.reRoot`): the gate filter
  (raise on a non-member) runs in the segment below, then the chain
  re-roots at the subtype's own extent joined on the shared primary key
  (`<prefix><pk>__pk_<table>` thread merge / plain column); the hop above
  (`.functionParameters`) is the subtype's own route (spliceOwnStep).
- `allNodes(node, ext)` is a Pure BODY over the closure rows
  (`$node.subtree.node`; associations PlanNodeSubtrees /
  PlanNodeClosureNodes on the lite class PlanNodeClosure) — the native
  signature and `MetamodelSteps`' Java arm DELETED.
- the plan-rows registration resolves under the runtime's chain mappings
  (planModel now passes them — the testModelConnection* M2M plans).
- `UserCallInliner` keeps binder NAMES unless an argument of the frame
  mentions the name (capture is the only hazard; the plan surface prints
  binders: `functionParameters = [optionalID:String[0..1]]`); `pair(a,b)
  .first/.second` folds to the component (the datetime helpers' pairs);
  `ExecuteChainAssembly.letBound` chases a let bound to another variable
  DOWN the prefix (a call frame's `let func = $func`).
- upgraded-H2 plan spellings (the assertEqualsH2Compatible pairs' UPGRADED
  golden is the oracle's): DATE placeholders `TIMESTAMP'${x}'`, optional-
  parameter equality null-safe for every kind (the DATE/DATETIME selector
  forms were the LEGACY halves), lowercase `dateadd(day, …)` on the
  milestoning adjust channel, `PureExp` `$names -> map([Routed Func:n:
  String[1] | $n -> toUpper();])`, FreeMarkerConditional block indent,
  RelationalBlockExecutionNode's `) ` closer, `tempTableColumns … )]`,
  renderCollection's `{"'" : "''" }`, dotted Integer placeholders bare
  (`${endDateCalendar.fiscalYear.value}` — `PlanParams.dottedPlanParam`).
- guardrail moves: `Callees`, `CastReRoot`, `ClassSorts` extracted from
  StoreResolver; plan-row registration in PlanAllocations; walk text-only
  asserts 35 → 27 (charter §8.0 row); metamodel quarantine rows 125 → 77
  (the plan-read refusal spellings are dead; walls 9); required-over-
  nullable ceiling 533 → 534 (sqlQuery over the single-table plan_nodes).
NAMED residue in executionPlan/tests after this batch (all walls or
text diffs, none silent): testTemporalDateVariableInFunctionExpression
WithPropagation (our milestoned derived-property join SHAPE nests the
exchange navigation — the engine flattens it; rows underivable: the
milestoning tables are never seeded in the executionPlan package, so
text is the contract — 4 tests count as our-rows-underivable in the
text-verdict census); testDatabaseConnectionSQLPopulation ×2 (the
SQLExecutionNode `connection` → datasource `testDataSetupSqls` rows —
next plan-row table); testGroupByWithOpenVariableInAgg ×2 (join ORDER +
`cast(0.0 as float)` literal), testMapWithOpenVariable /
testTwoMappingsOneRuntime ×2 (aggregation / union SQL shapes),
testLegacyFlag* ×2 (tests/query: the LEGACY_SQL_NULL_UNSAFE_EQUALS
feature flag must reach the plan dialect — withFeatureFlags is identity
today), the datetime `testPlanWithLocalH2ConnectionWithSQL`
(transformPlan protocol), testSupportStreamFlagWithGraphFetchAndFrom
(deferred graph-tree let), the model-connection agg/join/deep trio
(M2M shapes), withPlatform (STRING_AGG list encoding).

**Batch 19 — GROUP A: FUNCTION BODIES AS ROWS (2026-09-03): ratchet
729/1844 → 686/1887 (+43, ZERO lost; chain 5m49s, all green).** The 43
pkInferenceTests all read ONE helper: `$func.expressionSequence
->evaluateAndDeactivate()->at(0)` then `inferPrimaryKeyColumnNames($expr)`.
`FunctionDefinition.expressionSequence : ValueSpecification[1..*]` is
registered VERBATIM (real m3). A function reference eta-expands to a
lambda (existing Typer rule); the resolver meets `<lambda>.expressionSequence`
as a ROW ROOT (Anchors.functionBodyRead → ElementReferences.rowRoot): the
lambda's statements are `value_specifications` rows under the lambda's
content-id scope (`FunctionBodyRows`, registered on first meeting, riding
the query), each stamped with the compiler's inferred primary key
(`PkInference` — the engine's inferPrimaryKeyColumnNames RULES over the
typed tree: table accessor = declared pk; row-preserving ops keep; select
keeps iff it projects every key; rename maps; groupBy / distinct(cols)
key on their columns; INNER/LEFT join and asOf join union both sides;
aggregate / pivot / concatenate / other joins none). The read
`inferPrimaryKeyColumnNames(vs)` is a Pure body over the lite association
`InferredPrimaryKeys` (`$vs.inferredPrimaryKeyColumns->sortBy(ordinal).name`).
`evaluateAndDeactivate` is the IDENTITY over rows (resolveNode + the
object spine). The four row-root arms of collectOpChain (element ref, plan
handle, function value, constructed instance) now live in
`ElementReferences.rowRoot`. Quarantine rows 77 → 34. Named residue: none
in the family (43/43). Design debts named in the session report: the
analysis is Java-stamped (PkInference / PlanRows) and read by the
database; two parallel plan builders (planModel vs planToString);
content-id scopes; the lenient anywhere fallback in subtype registration.

**Batch 20 — GROUP E: LINEAGE TREES AS ROWS (2026-09-03): ratchet
686/1887 → 661/1912 (+25, ZERO lost; chain 5m42s, all green).** `scanRelations(f, m[, r], ext)`
is a PLATFORM HANDLE native (real scanRelations.pure:74/:341; the class
`RelationTree` registered, its engine properties are the lite node rows);
the engine's `scanRelations.pure` shipped beside its tests is SPEC and no
longer joins the family model (`RelationalCorpusRunner.ENGINE_
IMPLEMENTATION_FILES`). On the handle's let the executor registers the
tree's rows (`PlanAllocations.registerLineageRows` → `lineage.LineageRows`:
the lineage scan's printed lines as DATA — preorder, indent, kind
root/t/v, name, join label, sorted columns — the scan walks the raw query
lambda found by the let's name in the query's protocol body, now carried
on `ExecEnv.protocolBody`; `ScanRelations.lines` is the one walk both the
Java printer and the rows use). `relationTreeAsString(t[, withJoin])` is a
Pure body over the rows (`$t.nodes->sortBy(preorder)->map(...)->joinStrings`)
— the DATABASE prints the tree. Handles generalize: `PlatformTypes.
handleRowClass(fqn)` names the metaclass a handle's rows extend as
(ExecutionPlan / RelationTree); a `->toOne()` over a handle is the handle.
Residue, NAMED: 19 runtime-variant trees whose join labels carry the
engine's internal alias breadcrumbs (`Car_dy1c_PersonID`,
`AltID_View_d#5_d#2_m1entityID`, `Owner_f_d_rVEHICLE_ID`) — the Java arm
`LineageRelationsForm` stripped them from BOTH sides (a harness
compensation); the platform will not mint engine-internal alias names, so
those goldens stay Java-scored until the labels are adjudicated (engine-
internal spelling, not a lineage fact). 3 walls: `concatenate` of TDS
relations with differing columns (typer, ×2), scalar lowering of a
TypedPropertyAccess under a cross join (×1). Named resolver debt: an
aggregated to-many hop's join key resolves by COLUMN NAME across the
composed row (a node key spelled `id` collided with the tree's `id`) —
the store spells it `node_id`.

**Batch 21 — GROUP I: COLUMN LINEAGE AS ROWS (2026-09-03): ratchet
661/1912 → 656/1917 (+5, ZERO lost).** `scanColumns(tree, mapping)` is
a PLATFORM HANDLE native (real scanColumns.pure:30; `scanProperties`
:136 and `buildPropertyTree` :753 are the natives that feed it, their
classes `PropertyPathNode` / `Res` / `PropertyPathTree` registered); the
handle's rows are `column_contexts` (`lineage.ColumnLineageRows`: the
scan's (table, column, context) entries — `ScanColumns.entries`, the one
walk the Java arm and the rows share — each resolved to its owning
database/schema through the mapping's databases, includes-closed, all
databases as the fallback, loud on 0 or >1 owners). The read is pure
NAVIGATION: `ColumnWithContext.column` joins `relational_elements`
(`ColumnContextToColumn`), `Column.owner` is a self-join to the owning
table (`ColumnToOwnerTable`, typed `Table[0..1]` — real m3 says
`Relation[0..1]`; a nested union hop under map is not materialized yet,
a named debt). Two resolver legs, both real-pure semantics: `cast(@T)`
over a value whose static class already conforms to T is the IDENTITY
(`CastChecker`) so `$c.owner->cast(@Table).name` keeps its property-path
shape; instance `removeDuplicates` replayed over a materialized row
keeps the TO-ONE navigation slots' columns in its DISTINCT tuple (a
to-one slot is a function of the row — dedup-neutral; to-many exists
materials stay out — the two-exists witness `testAssociationToMany
WithTwoSeparateExists` guards it; `StoreResolver.instanceDistinct`).
The lowering's unresolvable-ref failure now names the columns it had.
Residue, NAMED: `testNonDataTypeProperty` — a CLASS-valued project
column (`p|$p.address`) walls in the inner lowering ("class query under
TypedMap"), the same 34-test bucket; the Java arm `LineageForm`'s
scanColumns branch serves that one test and dies with the bucket.

**Batch 22 — GROUP H: THE EXPRESSION TREE AS ROWS (2026-09-03): ratchet
656/1917 → 653/1920 (+3, ZERO lost).** Every node of a function body is
now a `value_specifications` row (`FunctionBodyRows.nodeRows`, preorder:
id, function id, ordinal, m3 kind, parent, depth, multiplicity bounds,
variable name); the kinds are the real m3 subclasses (`FunctionExpression`
/ `InstanceValue` / `VariableExpression` — Pure.java declares them with
their real properties, m3.pure bootstrap :1955; `func` is not modeled
yet: a function reference is not a row) as an Operation set over the one
table (`SystemMetamodel.VS_KINDS`, the plan-node idiom); `parametersValues`
are the children rows (`VsToChildren`); `expressionSequence` is the
depth-0 rows (the `FunctionToBody` join carries `depth = 0`). The node's
`multiplicity` is the REAL m3 object shape — `Multiplicity.lowerBound /
upperBound : MultiplicityValue.value` — mapped over the same row (`VsSelf`
self-joins; an unbounded upper bound is NULL); `getLowerBound` is the
real body verbatim (getLowerBound.pure:17) and the engine's
`expressionSequenceReturnsAtLeastToOneDataType` is a Pure body over it
(`$v.multiplicity->getLowerBound() >= 1` — the engine's
findFunctionSequenceMultiplicity fold and the typer's static multiplicity
agree on every witness). Two reflection folds at TYPING (real-pure
identities): `evaluateAndDeactivate` over a lambda literal is the literal
(`NormalizeFolds.foldReflection`, wired at `Typer.emitCall` — the generic
`<T|m>` signature used to strip the function carrier and lose
`.expressionSequence`), and `{..}->deactivate()->cast(@InstanceValue)
.values->at(0)->cast(@LambdaFunction<..>)` is the lambda
(`CastChecker.deactivatedLambda`). The Java arm `ReflectAsserts` (the
host multiplicity walk) is DELETED; the metamodel quarantine shrank 34 → 22
rows (the m3 classes type chains that walled as unknown types). Residue, NAMED — engine-GENERATOR
internal API with no platform counterpart (their bodies are the engine's
pureToSQLQuery.pure, never loaded): testFindFunctionSequenceMultiplicity
(`findFunctionSequenceMultiplicity` pairs + `.func`), testMergeOldAliasTo
NewAlias, testReAliasMergedJoinOperations, testFindAliasMappingBySchema
Name, addDriverTablePkForProject, testImportDataFlow (routeFunction /
toSQLQuery over RelationalExecutionContext); simpleFunctionExpression
TranslationNow/Adjust read `toSQLQuery(fe)->sqlQueryToString(H2)` — a
plan-text handle leg (the plan rows already hold the SQL text), not
built.

**Batch 23 — CONSOLIDATION (2026-09-03, after the user's design question
"is this all metamodel as data?"): ratchet unchanged 653/1920 (0 lost).**
Answer given: the READS are metamodel-as-data (rows, navigation, Pure
bodies, Operation sets, real m3 shapes); the FACTS are still Java-stamped
(PkInference rules, ScanRelations/ScanColumns walks over the lowered SQL,
the AggAwareActivities printer) — the harness smell moved into main, a
named debt with a port order (below). Three consolidations landed: (1) the
per-FQN `handleRowClass` table is GONE — a handle's row class is the
native's DECLARED return class when the registry labels it HANDLE
(`PlatformTypes.handleRowClass(fqn, returnType)`; executionPlan →
ExecutionPlan, scanRelations → RelationTree, scanColumns →
ColumnWithContext; execute's generic Result and preval's function value
yield none); (2) the let-time registration no longer sniffs shapes
(`->toOne()` / `->removeDuplicates()` unwrapping): every HANDLE call
anywhere in a let's binding registers (`PlanAllocations.registerHandlesIn`);
(3) the six identical StoreResolver constructions are one factory
(`StatementExecutor.resolver`). NOT consolidated, and why: registration
still happens at the let rather than on demand in the resolver, because
`ScanRelations.lines` walks the PROTOCOL lambda (found by the let's name
in the protocol body) — the lineage scans must first become Pure over
the expression rows + the mapping rows before the resolver can register
lineage on first meeting (function bodies already do). PkInference → Pure
over the expression rows needs bottom-up recursion over the tree (a
recursive CTE or a closure table, like plan_node_closure) — its own leg.

**NEXT SESSION OPENS HERE — burn fallbacks, by census group (user
ruling 2026-09-02: every batch must move the ratchet; no mechanism-only
legs).** State: 653 fallbacks / 1920 flipped (batches 14–22 = group D,
group Q plan nodes as rows, group A function bodies as rows, group E
lineage trees as rows, group I column lineage as rows, group H the
expression tree as rows), exec-passing 344, quarantine 34 rows / 9 walls (was 125 / 9;
group F LANDED — batch 7 above; batches 8–13 = speed + architecture,
ratchet unchanged), exec-passing 344, quarantine 125 rows / 9 walls.
NEXT = group Q (plan nodes as rows), then A/E/I/H (expression trees as
rows), then J/Z/N/G/P/O, then the non-metamodel buckets. Census after batch 15 (bucket dump): text-policy 65; "class
query under TypedMap (HN vocabulary)" 64 (heterogeneous); `mapping::sql`
45 (group C); FunctionDefinition.expressionSequence 43+26 (group A);
join-condition-reads-a-whole-variable 43; no-scalar-lowering 27+9;
scanRelations Join 21 (group E); plan parametersValues 17; activities
14; filter-predicate isolation 13+12; multiplicity 11; toPostgresModel
newState 11 (group G); group Q plan reads 12 (`expected Date/Integer/
String, got Any` — executionPlanTest's `$result.rootExecutionNode
.executionNodes->filter(instanceOf(RelationalInstantiationExecutionNode))
->at(0)…->cast(@SQLExecutionNode).sqlQuery` + assertEqualsH2Compatible;
the route is plan nodes as rows + the SQL-text referee).
1. **Group F — DONE (batch 7).** Was: mapping-metamodel query functions (27 tests; §1 of the
   homework: testRelationalExtension.pure 20, testExtendsForMainTable 5
   [DONE], testExtendsForPrimaryKey 1 [DONE], testSubtypeMapping 1)**:
   `_classMappingByClass` / `rootClassMappingByClass` / `view` as Pure
   bodies over rows. Real bodies (functions_Mapping.pure:28/:61,
   functions.pure:254): `_classMappingByClass` = includes' sets (recursive
   — the seeded include closure replaces it, as for classMappingById) ++
   own sets with `cm.class == $class` ++ AggregationAware members, then
   `addAssociationMappingsIfRequired`; `rootClassMappingByClass` =
   `_classMappingByClass->filter(s|$s.root == true)->last()`; `view` =
   `$_this.views->filter(t|$t.name == $name)->first()`. Seeds needed:
   `class_mappings.root` (m3 SetImplementation.root — declared `*`),
   `class_mappings.class_fqn` already there (map `SetImplementation.class`
   as an element reference — D3), `Schema` rows + `Schema.views`/`tables`
   associations, `Mapping.associationMappings` (association_mappings
   rows) for the addAssociationMappingsIfRequired half — grow by the
   20 tests' actual reads (census §2b/§2c). Retire the quarantine
   spellings `rootClassMappingByClass` / `_classMappingByClass` / `view`
   and MetamodelSteps' `rootClassMappingByClass` arm with the burn.
2. **Group D — harness vocabulary (43 tests)**: `meta::legend::
   executeLegendQuery` / `compileLegendValueSpecification` as the router's
   string entry (compile-from-string through the ONE router).
3. **Group Q — plan reads (13 + printers ~26)**: plan nodes as side-output
   rows (§2e) — name the tests from the bucket dump first.
The "class query under TypedMap (HN vocabulary)" bucket (64) is
HETEROGENEOUS (execute()+TDS-row reads on union tests, relationalMapper
SQL-text tests, tds unions, modelJoins) — never cut a leg by that label.

**Residue after leg 2**: (new) the
composed-row prefix scheme `<slot>_<column>` can COLLIDE with a physical
column of that spelling (two system-store columns were renamed around it:
`pk_column`, `super_mapping_fqn`/`super_id` — a user model with column
`ancestor_id` beside an association `ancestor` would hit
"duplicate column ... in relation type"); (new) a function-form mapping's
key facts are NONE (no text to read; an analysis of the lifted body would
derive them).

**Step 4 — prototype 2, testDynaAndOrInference** (homework §9 item 2), then
plan-nodes-as-rows, then the tree half — each decided on its own receipt.

Rules unchanged: one gate chain per batch, tree frozen during runs, pins
move only with their burn and a written justification, paired same-tree
sweeps byte-identical, save `core/target` rosters before a chain (G8
cleans), GMT test clock, no engine pure source in the platform, every
native signature verified against the real .pure.
