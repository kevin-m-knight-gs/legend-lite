# The stress corpus through legend-lite — program record (2026-09-16)

**Decision (user, 2026-09-16):** bring the `test-corpus` branch fully onto main and
build everything needed to run the tests it holds through legend-lite. Measure before
gating: how long the corpus actually takes on legend-lite is a fact to establish first.

This file is the running record. `docs/RUNNING_THE_CORPUS.md` (from the branch) says
how the corpus runs against legend-engine; `docs/DEFERRED_TEST_EXECUTION.md` is the
charter this program executes; `docs/TEST_CORPUS_MASTER_PLAN.md` §6 is the estimate it
was built against.

## 1. What the branch is

| body | what | how it was checked on the branch | where |
| --- | --- | --- | --- |
| the corpus | 4,742 services (4,729 with test suites) over a 20-domain financial model, seeded by one `###Data` element, every expectation computed by an independent Python oracle | executed against legend-engine 4.138 via `tools/engine-runner` (`perf.TestableMain`): 0 unexpected, 28 engine defects quarantined | `core/src/test/resources/stress/` (202 files, 13 MB) |
| the project graph | 56 Legend projects with declared dependencies; eleven of them are LINKED into the corpus and must load first | compiled only, via `scripts/projects/check.py` on legend-engine | `projects/` |
| grammar fixtures | 215 negatives + 51 positives generated from the engine grammars | already ADOPTED on main as `parser-equivalence/.../sibling-corpus` + `FixtureCorpusParityTest` (main's version kept at merge) | `scripts/parser/` |

On the branch the legend-lite side only LOWERED each service to SQL text and checked it
was non-blank (`StressDomainTest`). Nothing executed a suite or judged an answer through
legend-lite, and that side was stale: it did not load the linked projects.

## 2. The merge (LEG 0)

`origin/test-corpus` merged into main with `--no-ff`. One conflict
(`FixtureCorpusParityTest`, add/add): main's stricter ratchet kept. Everything else was
additive.

Making the whole corpus LOAD in legend-lite took four parser fixes and one flipped
assertion:

| blocker | fix |
| --- | --- |
| `ExtractSubQueriesAsCTEsPostProcessor` in a connection's `postProcessors` | parsed as the bare-keyword flavor; wire `{"_type":"ExtractSubQueriesAsCTEsPostProcessor"}` (the engine's record has no field beyond its span) |
| `coalesce([[db]T.A, [db]T.B])` — an array argument of column refs | `PRelLiteralList` now holds ANY function-operation argument (the engine's `functionOperationArgumentArray`); elements emit as `{"_type":"literal","value":<element, untyped>}` because `Literal.value` is an `Object` field. `[` decides between array and `[db]` pointer by content, as ANTLR does |
| compact suite test `id : PURE_TDSOBJECT => ...` | told apart from a base-data resolver `path: Kind #{` by the `=>` after the identifier |
| `LegendLiteGapTest.booleanColumnType` asserted BOOLEAN accepted | main rejects BOOLEAN like the engine (F3 CLOSED); the corpus already uses BIT; assertion flipped |
| linked projects absent | `StressCorpus.LINKED_PROJECTS`, dependencies before dependents, each in model / store / mapping order |

Then the model-build census (probe: exclude the failing file, retry, until the model
builds). Four real gaps, five files:

| file | gap |
| --- | --- |
| 29-money.pure, 55-canonical-store.pure | Measure/Unit: `stress::Money~USD` never registers as a resolvable type |
| 70-surface-store2.pure | precise primitives (`meta::pure::precisePrimitives::Varchar(200)`) |
| 71-mapping-surface2.pure | M2M explosion `part*:` refused by the normalizer |
| 75-surface-gaps.pure | M2M local property `+localTag` colliding with a declared property refused |

Each has an executable case in `LegendLiteGapTest`, and `StressCorpus.EXCLUDED`
carries the reason. Removing one is a deliberate act that the gap test forces.

**Timing, lowering only** (`StressDomainTest`, 228 files, 13.2 MB, 7,608 elements):
parse + build 4.9 s; lowering all 4,735 services to SQL 2.4 s (1.7 s in the lowerer).
2,753 lower cleanly, 1,979 fail — 1,798 of them one shape: navigating a join-mapped
property inherited by a subtype set (`dataquality::UniquenessRule.book`, where `book`
is mapped on the base set and the subtype set `extends` it). The bare projection over
the same subtype passes; only navigation through the inherited edge fails. One
normalizer fix, many rows. Remaining lowering buckets: `orElse` unported (73),
associations declared across linked projects not mapped (80), `combo::ComboRT` binding
(15), typing of derived properties (9), grouped-subselect aggregates (3).

## 3. The pieces (LEG 1 + LEG 2)

**Typed suites in the model.** `ServiceDefinition.testSuites` is the typed
`Protocol.PServiceTestSuite` list and `test` the typed legacy block; the `"<suites>"`
placeholder is gone. `###Data` is a `DataDefinition` (the protocol body, indexed by
name, `ModelContext.findData`); the opaque-carrier exemption for `ElementParser` in
`ArchitectureTest` is retired. `RuntimeDefinition.connectionIds` keeps the
`store: [ id: conn ]` id, which is how a suite addresses its provisioning.

**`com.legend.test.ServiceTestRunner`** (product): the engine's testable rules from
its sources —

- provisioning: `data: [ connections: [ id: ... ] ]` → the runtime's store for that id;
  compact resolvers → the store; `Reference` → the `###Data` body; `Relational` CSV
  → `CsvSeed` DDL + INSERT typed from the parsed store. Other kinds SKIP loudly;
- sessions: one seeded session per distinct provisioning, shared by every test whose
  program has no statement effects (the corpus's 4,729 suites all reference
  `stress::TestData`, so the 5,159-row seed loads ONCE); an effectful body gets a
  private freshly seeded session;
- execution: parameters as `let`-bound variables ahead of the body, then the ONE
  production entry (`Compiler.executeResolved`) against the service's runtime;
- serialization: `PURE_TDSOBJECT`/`RAW` = one object per row keyed by column; a graph
  result is the `{"builder":{"_type":"json"},"values":[...]}` envelope; DEFAULT on a
  tabular result SKIPS (not in the corpus);
- judgment: `EqualToJson` through `com.legend.test.TestAssertions` — the engine's
  `JsonNodeComparator.NULL_MISSING_EQUIVALENT_AND_UNORDERED_ARRAYS` with exact
  decimals: null ≡ missing, arrays unordered at every level, `1 == 1.0`.

`CsvSeed` learned two things the corpus needed: RFC 4180 quoted cells (a value with a
comma), and reserved-word identifiers quoted the way the query renderers quote them
(a column named `LIMIT` was created unquoted and the DDL failed to parse).

**`StressServiceSuitesTest`** (core, integration): runs every `stress::` suite through
the runner on DuckDB, writes `target/stress-suites-{pass,fail,skipped}.txt`, prints the
per-phase times, the failure buckets and the slowest tests. Measurement first: no
count is pinned until the numbers are known.

## 4. Measurements

First smoke, one service (`F30_TradeEverything`, 16 columns, 3-hop chains, qualified
properties): model parse + build 6.4 s; seed 5,159 rows once ≈ 1.4 s; PASS against the
oracle.

**Full run, 2026-09-16 (first):**

| | |
| --- | --- |
| tests | 4,736 (4,729 suites; a few suites carry two tests) |
| PASS (equal to the oracle) | **2,702** |
| FAIL | 2,028 |
| SKIPPED | 6 |
| model parse + build | 6.3 s |
| execution of every test | **8.5 s** |
| wall, whole test | **20.7 s** |
| sessions opened (distinct provisioning) | 19 |
| slowest test | 1.36 s (the one that seeds the shared session) |

The same corpus takes about an hour against legend-engine (`docs/RUNNING_THE_CORPUS.md`:
0.6 s per service plus 9 s per JVM). Legend-lite runs it in the time the engine spends
starting two JVMs. The cost that matters is the 6 s model build, paid once.

Both counts are pinned as ratchets in core's suite: `StressServiceSuitesTest.MIN_PASS =
2702` and `StressDomainTest.MIN_LOWERED = 2753`. Raise, never lower.

## 4b. Decisions taken with the user (2026-09-16, afternoon)

**The runner executes no SQL.** The first cut seeded through JDBC in the runner; that
was wrong. The engine's design is: a suite runs on a TEST RUNTIME whose connection
carries the suite's data, loaded when the connection is established. Legend-lite had
that seam (`StatementExecutor.establishContexts`) but only for Pure-INSTANCE runtimes;
a connection declared as an element with `testDataSetupCSV` was parsed and never
seeded — a platform gap, now closed. The runner builds the test runtime through the
existing execution overlay (`PureModelContext.withExecutionOverlay`, what the corpus
harness uses) and hands sessions to the platform, like `PureTestRunner`. Registered in
the JDBC census with that argument.

**Seeding: baseline the engine's way FIRST, optimize second.** `ServiceTestRunner.Sessions`:
`FRESH_PER_TEST` (a fresh session per test, seeded on establishment — the engine's
fresh database per run) is the apples-to-apples baseline; `SHARED` (one seeded session
per distinct provisioning for read-only tests; an effectful body gets its own) is the
optimization, measured after. The platform-side "established once per session, re-seed
after a write" memo is in place and is a no-op under FRESH_PER_TEST.

**Nothing "smarter than the engine" is hidden.** The judge implements the engine's own
JSON rules (null ≡ missing, unordered arrays, exact decimals); rendering is ours in the
engine's shapes; graph JSON is built by the database, not Java (the standing tenet);
DEFAULT tabular serialization, EqualTo/Relation assertions, multi-execution services,
ExternalFormat/ModelStore data are SKIPPED loudly, never faked; parameters bind as
`let` variables; ratchets list every failure by name; where the engine is wrong and the
oracle right (F6/F50/F51/F54) lite is expected to PASS.

**The parser ledger, re-measured on real divergence.** `FixtureAdjudicationTest` fed
Java text blocks with their SOURCE indentation, so every `###` header sat 16 columns in
and the engine refused 270 ordinary fixtures ("Unexpected token"); it also never asked
legend-lite. Fixed: runtime text, both parsers, a row is a DISAGREEMENT. Real ledger:
749 fixtures, 722 agree, 23 leniencies in 6 kinds — the clean-sheet mapping language
(18), two legend-pure forms the engine subsets away (3), the SQLite backend (2) — and 4
over-strict rows, two of them lite INTERNAL errors on one-/three-end associations (a
defect). `InMemory` is gone since 2026-08-10. Left as-is for now by the user's decision,
every kind named.

## 5. Ledger — the fail rows, by bucket (first run)

| rows | bucket | where it fails | decision |
| --- | --- | --- | --- |
| 1,798 | `property 'book' of class 'X' is not mapped in mapping 'stress::AllMapping'` — navigation through a join-mapped property INHERITED by a subtype set (`X[sub] extends [base]`; `book[positions_Book]: [store::DB]@Join` mapped on the base) | resolver (ClassSources / Substitution) | FIX, first: one normalizer/resolver rule, 1,798 rows |
| 80 | `association 'A' is not mapped in mapping` — associations declared across linked projects (`reporting::BookHasRollup`, `middleoffice::TradeLifecycle`, `brokerage::TradeBrokerage`, …) | resolver | FIX, second |
| 73 | `unknown function 'orElse'` — unported platform function (F7) | typing | FIX: port `orElse` from the spec |
| 15 | `runtime 'combo::ComboRT' has 0 mappings binding class` — the combination matrix's mapping fails to normalize | normalizer | census the normalize failure |
| 9 | typing of derived properties (`expected hier::Profile, got String`; `expected Float, got Number` under `trustOne`) | typing | census |
| 3 | `aggregate over navigation requires equi-join parent keys` | lowering | census |
| 2 | stores bound to DIFFERENT connections under one runtime (`external::EntityDB` + `store::DB`) — XStore | lowering | XStore leg |
| ~45 | `matchesOracle: … has no equal element in the actual array` — a genuine ANSWER difference (numbers, dates, nulls, ordering of nested arrays) | judgment | read each: engine-quarantined rows where lite is RIGHT vs real lite defects |
| 6 SKIPPED | multi-execution services (2), `ExternalFormat` data (1), runtimes in excluded files (3) | runner | multi-execution binding + ExternalFormat provisioning later |

Ledger order follows the rows: the 1,798 first.

## 5b. Fix ledger — findings of the afternoon (2026-09-16), each with its judge

| # | finding | fix | judge | state |
| --- | --- | --- | --- | --- |
| F-A | Multi-store suites (10: the `hier` family, the external-entity pair) SKIP because the test runtime binds one connection | one test connection per provisioned store in the overlay (the engine: one test connection per store) | those 10 rows PASS/FAIL, no SKIP | open |
| F-B | Routed set names resolved in the DEFINING mapping's closure (1,798 + 80 rows) | LANDED in two parts. (1) PINS: a pin is a NAME; `UnionSynthesis.resolvePin` resolves it in the defining closure first, else MODEL-WIDE by effective set id (the same set any including mapping binds; the engine's compiler accepted these pins — the corpus compiled there); the route names the set's function under ITS defining mapping and the queried mapping binds it at query time. An ambiguous id (several mappings define it) POISONS that one property with the owners named; NO re-synthesis. Declared divergence: a query under a mapping that cannot see the set still navigates here, where the engine fails. (2) ASSOCIATION ENDS: `AssociationSynthesis.endAnchors` — an end class the closure cannot see anchors on the table the single-hop join names for that end (`reporting::BookHasRollup`: the association in the rollup mapping, `positions::Book` in the positions mapping). | DuckDB 108 / H2 440 EXACT; stress H2 1,693 → 2,710 pass (ratchet 2,702 GREEN on H2); the 1,798 rows moved to F-M | fixed |
| F-M | The former 1,798 sat on ONE resolver seam (1,436 + 360 rows, all `book.desk.businessUnit.legalEntity.jurisdiction` over the 359 `book`-pinned classes): a ROUTED navigation head (`book`, a class-typed Join PM slot) followed by ASSOCIATION hops (`desk`, `businessUnit`, `legalEntity` are all association ends, not slots). The materializer's assoc-sub rule took ONE extra hop past a slot head and the association join materialized only SLOT tails of its target, so the read walk died at the second association. | LANDED as recursion in the existing owners, not a new pass-through: (1) `NavMaterializer.demandUnboundTail`/`foldAssocSubs` — a deeper tail past an association end rides that association join as its nav tails, and the SubNav carries the join's own sub-tree (`composeSubNavPrefixes`); (2) `AssociationJoins.associationJoin` — a tail whose head is an ASSOCIATION end of the target (no binding) joins the nested-association widening (`widenNestedAssocs`, the navigate() rule) with its deeper tails, the widened SubNavs ride the join's target sub-tree, and the nested join carries the DOTTED chain key (an explicit hop date keys by it — corpus testDerivedPropertyOnNonTemporalClassWithMilestonedChain) under the same temporal gate the one-hop rule applies. A chain-side slot pass-through was tried first and reverted: it duplicated the head's own assoc-sub columns. | stress DuckDB shared 2,765 → 4,203 (MIN_PASS), H2 fresh 2,710 → 4,148; corpus lanes 108/440 EXACT | fixed |
| F-O | 360 rows: the same family on the graph-fetch side (DSTree*: `book { desk { businessUnit { legalEntity { leId }}}}`). The graph node builder fixed its row type from the node's relation BEFORE emitting children; a ROUTED node (a union source) projects only what its own leaves demanded, so the `Book_Desk` condition's parent-side read (`DESK_ID`) found no column on the `book` child's row and the lowering walled ("unresolvable even after isolation") | `GraphEmission.buildGraphNode0` widens the node's relation for every ASSOCIATION child's parent-side key reads BEFORE the row type is fixed — `StackBuilder.demandForCondition`, the same demand seam an association join applies to its target (`AssociationJoins.associationParentSide` exposes the column-space condition and which param is the parent; property-space conditions substitute through bindings and need nothing). Rows that already carry the keys pass through untouched. `ServiceTestRunner` prints a failure's stack under `LEGEND_LITE_STACKS` (the resolver walls' switch). | stress DuckDB shared 4,203 → 4,564 (MIN_PASS), H2 fresh 4,148 → 4,509; corpus lanes 108/440 EXACT, no churn | fixed |
| F-Q | The seed-once memo keyed by the RENDERED seed text: before every statement the executor regenerated the whole loading script (CSV parsed, rows typed, DDL + INSERTs rendered through the dialect) only to find the session already seeded — the memo skipped the execution, not the generation. Per test 1.8 → 7 ms at the DDL leg; DuckDB shared lane 20.7 s → 50 s wall | The key is a RECORD of the seed's sources, compared by value (`StatementExecutor.SeedSources`): each from()'s inline setup strings and CSV records, the named runtime's definition and its bound connections' definitions (a LocalH2 spec carries the CSV/SQL as written); the text is generated on a miss only; the write-dirty rule is unchanged. Two wrong shapes on the way, both caught by measurement: the model's object identity (the runner overlays a fresh model per test — every test missed), and a joined string key (value equality of records, not string assembly). | DuckDB shared 51 s → 26 s wall (execution 39 → 13 s), identical fail set; H2 fresh identical fail set; corpus lanes 108/440 EXACT; G1 alone 107 s → 93 s | fixed |
| F-N | Lane shapes (user ruling): H2 = the engine-faithful reference, a fresh freshly-seeded session per test (1.9 min for 4,736 tests; the engine ~60 min: ~32x); DuckDB = the fast gate, one seeded session per distinct provisioning SHARED across its tests, isolation by write detection (a session that ran an effectful statement is re-seeded before the next test). Apples-to-apples DuckDB fresh-per-test: 2,765 pass in 27 min; shared gives the same count in ~30 s (read-only corpus). `-Dstress.sessions=fresh|shared` overrides either lane | `StressServiceSuitesTest` defaults by backend; MIN_PASS 2,702 → 2,765 | stress run both lanes | done |

| F-C | H2 reserved words `VALUE`, `YEAR` missing from the H2 lexicon (DDL and queries over such columns fail on H2) | added to `Lexicon.H2` (witnessed) | H2 lane EXACT; stress on H2 | landed in tree |
| F-D | Seed DDL typed from the PURE property type (`DECIMAL(18,4)` → `DECIMAL(38, 9)`): 940 H2 rows answered decimal division at the wrong scale; DuckDB hid it | the seed creates the store's DECLARED types through the one DDL producer | the 940 rows on H2; DuckDB unchanged | in progress (F-E's shape) |
| F-E | DDL spelled by a `Flavor` enum + a `constraints` boolean + `rawH2IsNative()` ternaries at call sites — a target decided outside its dialect, three times over (USER stopped three patches) | DDL as IR: `CreateTable`/`DropTable` nodes rendered by the dialect with its own identifier and type rules; `Ddl` = model→node; keys/nullability ride the node as DECLARED; delete Flavor, the boolean, the duplicate identifier/type tables | DuckDB 108 / H2 444 EXACT; engine-text goldens byte-equal; stress both backends | in progress (task #6) |
| F-F | Nothing stops the next flavor: no test pins target-name decisions outside the dialect package | `DialectBoundaryTest`: `rawH2IsNative()` callers and `DatabaseType` checks outside `com.legend.sql.dialect` pinned to the two seams (dialect resolution, the raw-SQL boundary), shrink-only | the test itself; a new ternary fails the build | open — DO NOT FORGET (this row) |
| F-G | Two over-strict parser rows are lite INTERNAL errors (one-/three-end Association → IllegalStateException, not a parse refusal) | refuse at parse with a positioned message | `FixtureAdjudicationTest` lite-internal list empty | open |
| F-H | Service query-by-reference leniency and the clean-sheet/SQLite spellings | user decision: left as-is for now, every kind named | — | decided |
| F-I | Rendering DDL from the declared store (keys included) exposed a metamodel DATA defect: `DatabaseDefinition.views()`/`tables()` are flat mirrors of EVERY schema, and the metamodel walks paired them with `default` — each named-schema view was registered twice, two same-named views in two schemas (scanRelations DB2 `E.AltID_View` / `ViewSchema.AltID_View`) collapsed onto ONE id; the tables/columns walks hid the same shape behind a put-then-remove-by-name compensation | the model derives `defaultSchemaTables()`/`defaultSchemaViews()` ONCE (flat minus named, by identity); every walk (MetamodelSeeds tables/columns/views/schemas, OpSeeds types/view columns) reads it; the compensations died | corpus lanes 108/444 EXACT with keys declared | fixed |
| F-J | The stress H2 session refused `VALUE`/`YEAR` column names because the test opener used a bare `jdbc:h2:mem:` — the engine's H2 test connection carries `NON_KEYWORDS` (H2Settings) that make them legal; my first patch added them to the H2 lexicon, which quotes a lowercase `value` and breaks against the uppercase table (23 corpus rows) | reverted the lexicon; the stress opener carries `H2Settings.SETTINGS` like the corpus lane. OPEN: the product's own H2 URLs (`ConnectionResolver`, EmbeddedH2/default arms) also open without the engine's settings — a LocalH2 user connection is not the engine's session | `ConnectionResolver` H2 arms append H2Settings; a test on a `value` column through a user connection | open (opener fixed; product arms open) |
| F-K | Declared keys exposed a second physical-order reliance: `Mapping.enumerationMappings` is an ORDERED Pure collection, its metamodel table carried no declaration ordinal, and `first()` below a navigation hop lowered to a bare `LIMIT 1` — insertion order on both engines until H2 scanned the keyed table in key order (corpus testEnumTheSame: Foo by declaration, Active by key) | `enumeration_mappings.ordinal` (declaration order, seeded); the limit family (first/take/drop/slice) over any metamodel row carrying the ordinal sorts by it first (`FlattenOps.byDeclarationOrder`, both fold sites), read through the hop join's column prefix. CENSUS still owed: `enum_value_mappings`, `enum_value_sources`, `class_mappings`, `group_by_mappings`, `view_column_mappings` are ordered in Pure and carry no ordinal — a positional read over them still follows scan order | corpus lanes 108/444 EXACT (DuckDB 54 s, H2 21 s) | fixed for the witness; census open |
| F-L | The H2-only stress gap was ONE bucket (936 rows): Pure `divide(Number, Number)` IS a Float, but the renderer only promoted with `1.0 *`, so H2 divided two DECIMAL(18,4) columns into an exact 36-digit NUMERIC (DuckDB divides decimals into a DOUBLE natively); the engine's expected values are doubles | divide renders as a DOUBLE division — both operands cast BEFORE the division (a cast after keeps the operands' arithmetic: integers truncate, decimals round from an exact quotient); the `1.0 *` trick is retired | corpus lanes DuckDB 108 EXACT / H2 440 (four wavg/round rows retired from the roster); stress H2 1,693 → 2,642 pass (DuckDB 2,702) | fixed |

**Measured today (engine's shape, fresh session per test, all 4,736):** DuckDB 23.2 min at 290 ms/test; H2 95 s at 17 ms/test. Same fixture replay: DuckDB 247 ms, H2 32 ms. Loading levers measured (200k rows): Arrow 8 ms ingest + 52 ms vector build, Appender 102 ms, read_csv 82 ms + file, text 2,654 ms, prepared batch 20 s; 393 tiny tables: Appender 90, text 137, Arrow 256, DDL floor 23 ms; ADBC from Java: the JNI driver (0.21.0) has no executeUpdate — ingest impossible today. Seeding policy decisions (clone vs share) deferred until the load path is right.
