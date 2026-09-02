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

**Residue after batch 5**: views as main tables; `parent` from the union
side; assert failure MESSAGE rendering over instances; (new) the
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
