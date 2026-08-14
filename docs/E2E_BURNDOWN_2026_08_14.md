# End-to-end execution burn-down — full evidence base, 2026-08-14

> Generated from a **fresh full gate chain** at HEAD `2c0632d6` (all 8 gates
> green: G1 4048 core tests, G4 DuckDB corpus, G5 H2 corpus, G6 PCT 1109,
> G7 PCT-h2 348, G8 parser-equivalence) against the live legend-engine checkout
> `943d38b3dc2`.
>
> **Re-verified at `e0a907a9`** after six commits landed upstream (~2000 lines
> across `parser/section`, `protocol`, and `rcorpus/Runner`): G1/G2/G4 green,
> `docs/RELATIONAL_CORPUS.md` regenerated **byte-identical**, and both
> load-bearing line citations (§1, §2.1) confirmed still exact. Every number
> below holds at `e0a907a9`.
>
> Two questions are answered here:
> **A.** every non-passing `core_relational` test, root-caused and clustered;
> **B.** the whole legend-engine `<<test.Test>>` estate, not just `core_relational`.
>
> **Evidence base: `docs/burndown-2026-08-14/`**
> — `master-classification.csv` (all 276 rows), `h2-backend-sweep.txt` (the §3.1
> evidence), and `tools/` (8 scripts that regenerate every number here, including
> the per-failing-test source dossiers).
> **Start at `docs/burndown-2026-08-14/README.md`** for provenance, toolchain,
> exact repro commands per finding, and the two `-Dlegend.engine.root` / `~/.m2`
> traps that have produced phantom regressions before.
> If you are picking this up cold and want to *do* the work rather than read
> about it, skip to **§9**.

---

## 0. Headline numbers

| | |
|---|---:|
| `core_relational` runnable | 2575 |
| passing | **2299 (89.3%)** |
| non-passing | **276** (89 FAIL, 92 ERROR, 95 SHAPE) |
| **Whole legend-engine `<<test.Test>>` estate** | **5390** |
| …of which `core_relational` | 2727 (50.6%) |
| …everything else | 2663 |

**legend-lite today executes 43% of legend-engine's entire test estate and
passes 2299 of them.** The other 57% is censused in §6.

---

## 1. A census defect worth fixing first

`docs/RELATIONAL_CORPUS.md` states the denominator as **2798 total, 223 excluded**.
Both numbers are wrong.

Counting stereotype blocks directly over the corpus tree, **with comments
stripped**, reconciles the runnable figure exactly and the excluded figure not
at all:

| | scoreboard | ground truth |
|---|---:|---:|
| runnable | 2575 | **2575** ✅ |
| `<<test.ExcludeAlloy>>` | 96 | **96** ✅ |
| `<<test.ToFix>>` | 127 | **50** ❌ |
| total | 2798 | **2721** ❌ |

**Root cause** — `core/src/test/java/com/legend/rcorpus/Runner.java:443-460`
(the defect is the `return` at `:459`):

```java
switch (st.stereotypeName()) {
    case "Test" -> isTest = true;
    case "ToFix", "Ignore", "ExcludeAlloy" -> excluded = true;
    default -> { }
}
return excluded ? TestKind.EXCLUDED : isTest ? TestKind.TEST : TestKind.NONE;
```

The `excluded ? … ` arm wins **before** `isTest` is consulted, so a function
stereotyped `<<test.ToFix>>` with **no** `test.Test` is classified `EXCLUDED`
and lands in `CENSUS_EXCLUDED`. There are **77 such functions** in the corpus
(56 bare `ToFix`, 21 `AlloyOnly, ToFix`). legend-engine's own `PureTestBuilder`
only ever collects `test.Test`-stereotyped functions, so upstream does not
consider these tests at all.

**Fix:** return `NONE` when `!isTest`. Denominator becomes 2721; the pass rate
against *all* tests rises from 82.2% to **84.5%**, and against runnable stays
2299/2575.

Two smaller notes from the same reconciliation:
- 4 apparent tests are **commented out** in the corpus source (e.g.
  `graphFetch/domain/domainManagementTests.pure:10` `testGraphQLQuery`). The real
  parser correctly skips them — a text-grep census would over-count by 4.
- **1008 model elements are dropped at assembly** (scoreboard §"mapping walls").
  See §4.

---

## 2. Master classification of all 276

Every non-passing test was resolved to its source (`276/276`, zero unresolved),
its full body extracted, and its untruncated failure detail classified.

The result is committed as **`docs/burndown-2026-08-14/master-classification.csv`**
(`bucket, status, family, test, file, line, detail`) — filter it by `bucket` to
get the working list for any row of the table below. Regenerating it with
`tools/` writes the same content to `$BURNDOWN_OUT/master.csv`, alongside
`features.json` (body-shape flags) and `dossiers/<family>.md` (every failing
test's complete `.pure` body next to its untruncated failure detail).

| # | bucket | n | % | FAIL/ERROR/SHAPE |
|---|---|---:|---:|---|
| 2 | **Execution-plan subsystem** | 71 | 25.7% | 26 / 9 / 36 |
| 6 | **Wrong rows / wrong value** (real semantic defect) | 37 | 13.4% | 36 / – / 1 |
| 7 | **Resolver gap** (H-phase: substitution, navigation, mapping dispatch) | 36 | 13.0% | – / 36 / – |
| 4 | **SQL-text golden is the whole contract** | 35 | 12.7% | 27 / 2 / 6 |
| 9 | **Typer / vocabulary gap** (G-phase) | 26 | 9.4% | – / 14 / 12 |
| 1 | **Engine's own Pure-implemented compiler internals** | 23 | 8.3% | – / 2 / 21 |
| 10 | Harness SHAPE (assert/body form unrecognised) | 15 | 5.4% | – / – / 15 |
| 5 | **Invalid/unsupported SQL we emitted** (DuckDB rejects) | 12 | 4.3% | – / 12 / – |
| 11 | Unclassified one-offs | 11 | 4.0% | – / 11 / – |
| 8 | Lowering gap (I-phase) | 7 | 2.5% | – / 5 / 2 |
| 3 | Engine metamodel surface (missing class/property) | 3 | 1.1% | – / 1 / 2 |

**The structural read.** Only **125 of 276** are real end-to-end execution tests
(they call `execute(|…)` and assert rows). 71 are execution-plan tests, 35 are
SQL-text goldens, 23 are white-box tests of legend-engine's compiler-written-in-Pure.

### 2.1 The SHAPE column is lying — 82 of 95 are platform gaps

`harness/EngineTestExecutor.java:878-886`:

```java
if (failure == UNSUPPORTED_MARKER) {
    String why = takeUnsupportedReason();
    return new Outcome.Unsupported("assert form '" + af.function()
            + "/" + af.parameters().size() + "' is not supported yet"
            + (why == null ? "" : " — " + why));
}
```

Whenever the marker is set, the row is stamped **"assert form 'X/N' is not
supported yet"** — a harness-gap message — and the *actual* cause is appended
after an em-dash. Counting across all 95 SHAPE rows:

| | n |
|---|---:|
| carry a real platform cause after the em-dash | **82** |
| genuinely bare | 13 |
| …of those 13, still naming a platform wall (`scanRelations: …`, `class query under TypedMap …`) | 10 |
| **actually harness-shaped** | **~3** |

The three real ones: `router/tests/testPrerouting42` (`assertRoundTrip/3`
genuinely unimplemented) and two `no verifying assertions` rows
(`modelJoins/testPersonToFirmUsingProject`,
`testDataGeneration/tests/testAlloyTestDatGenForNestedViews`).

So `assertEquals/2` **is** supported; what failed was plan generation underneath.
Mislabelled SHAPE by family: `executionPlan/tests` 22, `pureToSQLQuery` 8,
`tds/tests` 7, `tests` 6, `functions/tests` 4, `postprocessor` 4, and a tail.

**Consequence:** the harness is not the bottleneck anywhere. Fixing the message
prefix re-buckets 82 rows from "harness SHAPE" to "platform gap" without changing
a single verdict, and makes every downstream prioritisation honest. Do this
before quoting SHAPE counts to anyone.

---

## 3. Verified findings (each reproduced, not inferred)

### 3.1 The `[ROOT, TDSNull, TDSNull]` cluster is a **DuckDB row-order artifact**, not a defect

Six tests fail with the same signature — expected row 0, got a *different but
valid* row of the same result set:

```
tests/mapping/selfJoin/testSelfJoinPropertyMappingOverlap
   expected [ROOT, TDSNull, TDSNull]   got [Federation, Firm X, ROOT]     ← expected row 5
tests/mapping/selfJoin/testSelfJoinPropertyMappingWithDynaFunction
   expected [ROOT, TDSNull, TDSNull, true]  got [Banking_c1_c1, Firm X, ROOT, false]  ← expected row 8
tests/mapping/filter/testFilterMappingWithProjectionOverlapp
tests/advanced/testFilterMappingWithProjectionOverlappForcedCorrelated
tests/advanced/testFilterMappingWithProjectionOverlappForcedOnClause
functions/tests/testSequenceMapWithConfusingSetImplementation
```

These tests assert **positionally** (`$result.values.rows->at(0).values`) on a
query whose golden SQL contains **no `ORDER BY`** (`selfJoin.pure:57`). The
engine's goldens encode H2's scan order.

**Decisive experiment** — same pipeline, same SQL, H2 execution target:

```
mvn -o -f pom.xml -pl core test -Dtest=RelationalCorpusRunner \
    -Drcorpus.backend=h2 -Drcorpus.only=tests/mapping/selfJoin,tests/mapping/filter,tests/mapping/tree

[rcorpus] h2-backend tests/mapping/filter: 9/9 pass      ← DuckDB: 8/9
[rcorpus] h2-backend tests/mapping/selfJoin: 2/3 pass    ← both ROOT/TDSNull tests PASS
```

The sibling `testSelfJoinPropertyMapping`, which uses order-insensitive
`assertSameElements` over the identical query, passes on DuckDB already. That is
the control.

**Corpus-wide quantification** (full H2 sweep vs the DuckDB ledger):

| | |
|---|---:|
| DuckDB corpus passes | 2299 |
| H2 corpus passes | 2258 |
| tests passing on H2 but **not** DuckDB | **7** |
| tests passing on DuckDB but not H2 | 48 |

Concentrated in `tests/mapping/enumeration` (+3), `tests/mapping` (+2),
`tests/mapping/filter` (+1), `tests/mapping/selfJoin` (+1).

**Implication:** ~7–9 of the 276 are execution-target artifacts. They are not
fixable by changing the pipeline, and "fixing" them by injecting an ORDER BY
would be harness compensation (tenet 2). The honest options are (a) ledger them
as DuckDB-order-dependent with the H2 sweep as the standing proof, or (b) make
the H2 backend authoritative for this subset. **Recommend (a)** — DuckDB passes
41 more tests overall.

### 3.2 A comparator defect that renders its own failure message useless

```
FAIL tests/mapping/tree/testJoinIsolationDeeper_LeftOuterLeftOuterThenInner:
     assertEquals: expected [11, OrgName3], got [11, OrgName3]
```

Expected and got render **identically**, yet the assert fails. Reproduced
deterministically on both backends (`-Drcorpus.only=tests/mapping/tree`), so it
is not a flapper and not dialect-dependent. The comparator is discriminating on
something the renderer does not show — cell type identity, collection arity, or
`TDSNull` vs `null`.

This matters beyond one test: **a comparator that can fail on invisible grounds
can also pass on them.** The message must render type and arity. Fix this before
trusting any row-equality result in this family.

Its sibling `testJoinIsolationDeeperTwoIsolations_…` fails `expected OrgName2,
got null` on **both** DuckDB and H2 — a genuine defect in the second isolated
join (`orgByName('BUSINESS UNIT')`), unrelated to order.

### 3.3 1008 model elements are dropped at assembly

The scoreboard's mapping-walls section lists 1008 dropped elements. They cascade
from a small set of unknown root types:

| drops | root unknown type |
|---:|---|
| 207 | `FunctionExpression` |
| 60 | `PropertyMapping` |
| 58 | `ValueSpecification` |
| 42 | `Lambda` |
| 37 | `TemporalStrategy` |
| 31 | `SQLQuery` |
| 30 | `Res` |
| 15 each | `Join`, `Filter`, `AggregationAwareSetImplementation`, … |

The large majority are legend-engine's **self-reflective Pure metamodel** —
the engine's compiler is written in Pure and reflects over its own AST.
legend-lite's HIR is Java-typed (`TypedSpec`) and deliberately exposes no Pure
metamodel face. That is a design boundary (tenet 1: no host interpreter), not a
backlog item.

**But not all of them.** 31 drops are `Unknown type: 'Firm'` on
`…::mft::distinctTestMapping$class$Firm` — a **real name-resolution bug**: the
class-mapping head is declared unqualified under an `import` wildcard in `mft::`
mapping files and legend-lite fails to resolve it. Worth fixing; unrelated to
the metamodel question.

### 3.4 245 tests pass on rows while emitting different SQL

The scoreboard's `sqldiff-pass` column totals **245**, and the runner's advisory
ceiling is 297 diffs (`RelationalCorpusRunner.java:514`). Separately, the
`sql-text:` FAILs in bucket 4 are tests where `verified()==0` — the SQL golden is
the *only* assertion, so text divergence fails honestly
(`Runner.java:1422-1426`). Do not conflate the two: 245 is silent SQL drift on
passing tests; the 27 bucket-4 FAILs are contract failures.

---

## 4. The 125 real end-to-end execution failures — where the work is

| n | signature | owning code |
|---:|---|---|
| 31 | wrong rows / wrong value | — |
| 11 | SQL-text golden diff | — |
| 8 | **DuckDB rejects our SQL** | dialect + lowering |
| 8 | no overload matches | `compiler/spec/` |
| 7 | property not mapped in mapping | `resolver/ClassSources.java:623` |
| 5 | `getAll(...)` unresolved | `resolver/StoreResolver.java` |
| 4 | unknown function | `builtin/Pure.java` |
| 4 | columns unresolvable after isolation | `lowering/Lowerer.java:1173` |
| 4 | node not substitutable | `resolver/Substitution.java:1897` |
| 4 | multi-hop navigation | `resolver/Substitution.java:1341` |
| 3 | DuckDB `Invalid Input Error` | dialect |
| 2 each | graph output (H4), derived property, nested nav, `cannot access`, lowering unimplemented | `resolver/`, `lowering/` |

**ERROR walls by owning file** (all 92 ERRORs, attributed by grepping the message
literal back to source):

| n | file |
|---:|---|
| 10 | `resolver/ClassSources.java` |
| 7 | `resolver/Substitution.java` |
| 7 | `AggAwareActivities.java` |
| 5 | `lowering/Lowerer.java` |
| 4 | `lowering/Scalars.java` |
| 3 each | `resolver/CorrelatedSubselects.java`, `ConnectionLets.java`, `parser/SpecParser.java` |
| 2 each | `sql/dialect/AnsiSqlRenderer.java`, `compiler/spec/EvalChecker.java`, `normalizer/AssociationSynthesis.java`, `protocol/ParameterDefinition.java` |
| 31 | (DB-engine errors + plan layer — see below) |

**`resolver/` owns 22 of the 92 ERROR walls — the single largest concentration.**

### 4.1 Bucket 5 in full — 12 tests where DuckDB rejects the SQL we emitted

These are the most concretely actionable failures in the whole corpus: the
pipeline ran to completion and produced SQL the database refused.

| test | error |
|---|---|
| `tests/mapping/classMappingFilterWithInnerJoin/testChainedJoinsWithUnionsAndIsolation…` | `Referenced table "t5" not found! Candidate tables: "t4"` |
| `tests/mapping/union/testProjectAndFilterSamePropertySameJoinInUnion` | `Table "t0" does not have a column named "firstName"` |
| `tests/mapping/testGet`, `tests/mapping/testQuery` | `Cannot compare TIMESTAMP_NS and TIMESTAMP WITH TIME ZONE` |
| `functions/tests/testAssociationWithProjectionHandlingDups`, `tests/query/testCollectionDistinctFunction` | `subqueries in lambda expressions are not supported` |
| `tests/query/testFilterUsingArcCosFunction`, `…ArcSinFunction` | `Unable to compute acos/asin of 1.1` |
| `tests/mapping/sqlFunction/testProject` | `No function matches 'len(DOUBLE)'` |
| `functions/tests/testAll` | `No function matches 'list_concat(JSON, JSON)'` |
| `functions/tests/projection/testInWithDynaFunction` | `Could not convert string 'something' to BOOL` |
| `functions/tests/projection/testIsolationOfFiltersWithoutAlias` | `More than one row returned by a scalar subquery` |

The first two are **wrong-SQL bugs** — alias scope defects in join isolation and
union projection. Highest severity in this list: a query that binds the wrong
table is one dialect quirk away from silently returning wrong rows instead of
erroring.

A **third wrong-SQL bug** surfaced in the plan family:
`executionPlan/tests/testFilterInWithResultSorcedFromAnExpression` emits
`LEGALNAME = string_split(...)` — **equality against a collection where the query
means membership**. That is a tenet-3 violation: it does not error, it just
means something else. It resolves with cluster #15 below.

The `acos/asin of 1.1` pair is a semantic divergence: the engine returns NaN,
DuckDB throws. That is a dialect-level decision, not a bug in the query.

### 4.2 Bucket 6 — the 37 wrong-rows failures, sub-clustered

- **6 × DuckDB row order** — §3.1, not defects.
- **3 × relation-mapping filter-in-project** (`tests/mapping/relation`):
  `testSimpleMappingQueryWithFilterInProject` and `testMixedMappingWithFilterInProject`
  expect 5 rows, produce 4 — we lose the `Fabrice, null` row and mis-pair the
  rest. `testMappingWithWindowColumn` computes John's window value as 1, engine
  says 2. Same file, same machinery, likely one root cause.
- **3 × enumeration mapping in `if`/project** (`tests/mapping/enumeration`) —
  `testProjectionWithEnumThroughAssociation` expects `GS_NUMBER` gets `CUSIP`,
  i.e. the enum mapping is applied from the wrong side. **All 3 pass on H2**, so
  check §3.1 before treating as semantic.
- **2 × null renders as empty string** (`tests/mapping/union/relation`) —
  expected `null`, got `` (empty). Clean, isolated, cheap.
- **3 × date/timezone** — `testDateTimeRetrieveWithTimeZone` (expected
  `2016-02-05`, got `2016-02-05 21:00:00.123456789`),
  `testDateTimeInclusiveRangeQuery`, `testInExecutionWithTempTableForDateTimesWithTz`.
  This is the known date-carrier precision problem.
- **5 × duplicate-row preservation** — `testConcatenateFlatWithOtherProperty`
  (expected `[1,1,2,2]` got `[1,2]`), `testMultipleJoinsInPropertyMappingWithDatesInClass`,
  `testSameTableNameDifferentSchema1`, `testIsolatioWhereNoConstaintsAndInnerJoin`,
  `testTwoQualifiersUsingSameJoinWithNoUserParams`. Theme: **the engine does not
  dedupe; we do** (or isolate joins differently).
- Remainder: one-offs.

### 4.3 Bucket 1 — 23 tests that are architecturally out of reach

`pureToSQLQuery/tests` (8), `postprocessor/tests` (3), `router/tests` (3),
`helperFunctions/tests` (2), `sqlQueryToString` (2), and 5 others are **white-box
unit tests of legend-engine's compiler, which is written in Pure**. They call
`routeFunction`, `toSQLQuery`, `mergeOldAliasToNewAlias`, `buildJoinTreeNode`,
and construct `^RelationalExecutionContext`, `^OldAliasToNewAlias`,
`^TableAlias(relationalElement=^Table(…))`.

Example, `pureToSQLQuery/tests/testPureToSql.pure:149`:
```pure
let res = mergeOldAliasToNewAlias(false, ^OldAliasToNewAlias(first='1', second=buildTableAlias('1a')), $aliasList);
assertEquals(['1','3'], $res.first);
```

Passing these requires interpreting the engine's own compiler as data. That
violates tenet 1 and serves no product purpose. **Recommend: ledger as
PERMANENTLY-OUT-OF-SCOPE with this rationale**, and subtract them from the
denominator quoted in goals. That alone moves the honest target from 276 to 253.

### 4.4 Bucket 2 — the 71 execution-plan tests

legend-lite has a real plan subsystem (`core/src/main/java/com/legend/plan/`:
`PlanText`, `PlanNode`, `PlanEnumForm`, `PlanSupportFunctions`) and already
passes 61 of 108 `executionPlan/tests`. The remaining 47 (+24 elsewhere) are
blocked behind a **long diffuse tail** — 30 tests sit behind 20 distinct plan
walls, none appearing more than 3 times:

```
3  planToString: no getAll root (multi-node plans pending)
3  model-to-model binding navigates an unmapped non-association property
2  class query under TypedGraphFetch is not resolvable yet
2  SQLExecutionNode has no property 'connection'
2  unknown class 'Service'
2  StoreMappingGlobalGraphFetchExecutionNode has no property 'localGraphFetchExecutionNode'
… 14 more, 1 each
```

**This is grind, not leverage** — roughly 1–3 tests per fix, ~25 distinct fixes.
About 8 of the 30 are "class X has no property Y" on the engine's plan metamodel,
which overlaps §3.3.

A dedicated line-by-line pass over all 47 `executionPlan/tests` rows resolved them
into **23 distinct clusters**. Of the 18 FAILs: **12 semantically real**, 5
rows-equivalent/engine-artifact, 1 purely cosmetic (`dateadd(DAY` vs
`dateadd(day`). Of the 22 SHAPE rows: **0 are genuine harness gaps** — all are
platform walls wearing the §2.1 label.

**The cheap end — 8 clusters, ~14 tests, one S/M workstream:**

| # | root cause | tests | size |
|---|---|---|---|
| 1 | `StatementExecutor.java:603` routes to `sequencePlan` only when the lambda has params or >1 statement, so a lone `let` prints as bare `Relational` instead of bare `Allocation` | 1 | XS |
| 2 | `Typer.isFunctionTyped:1670-1674` rejects `FunctionDefinition<Any>` although `InferenceKernel.FUNCTION_CARRIER_FQNS:1181-1185` already names the carrier family | 1 | XS |
| 3 | `SQLExecutionNode` declared without `sqlComment` (`Pure.java:516`) | 1 | S |
| 4 | `crossDbTdsPlan` resolves `resultColumns` over the pre-splice tree; the engine types every spliced-placeholder column `Integer` (`pureToSQLQuery.pure:581-585`) | 3 | S |
| 5 | `EngineStyleH2.java:1035-1045` handles only one level of `StructGet(PlanParam)` so `$var.a.b` walls | 1 | S |
| 6 | Date **literals** render H2-2.1.214-style but date **plan parameters** render 1.4.200-style, so 3-arg `assertEqualsH2Compatible` matches neither golden | 1–3 | S |
| 8 | `planModel:1937-1945` demands a `TypedPackageableRef` at `args[1]`, duplicating logic the plan-text path already generalised at `:546-582` | 2 | S+M |
| 13 | `GraphFetchChecker.java:82-87` requires a syntactic `ColSpecArray`, rejecting a graph tree bound to a variable | 1 | M |

**The expensive end.** Cluster 14 (IN-collection temp table / FreeMarker
conditional — zero hits for `tempTableForIn`, `CreateAndPopulateTempTable`,
`RelationalBlockExecutionNode`) and cluster 15 (no non-store `PureExp` plan node)
are each L and each gate 3–5 tests. Cluster 16/17 (graphFetch plan nodes) likewise.

**Two clusters to defer deliberately:**
- **#20 correlated aggregates.** `StoreResolver.java:2020-2100` roots on the
  *child* table keyed by FK with a `case when … else 0 end` guard; the engine
  roots on the *parent*. The two shapes **disagree on rows for empty groups**
  (engine `count = 1`, lite `0`). This is a rows-first study, not a golden chase,
  and it underpins much of the corpus.
- **#21 milestoned propagation** (`TemporalFrame.java:503/645/712/880`) —
  pre-joins a propagated hop as an `_nav_` column instead of a sibling
  left-outer-join and emits `select t1.*` with an undefined alias. Broadly used.

**Four tenet-2 traps** — each must be solved in the platform, never the harness:
do not case-fold `DAY`/`day` or strip `DATE`/`TIMESTAMP` prefixes in
`PlanAsserts`; do not special-case `resultColumns` type tokens in the harness
diff; do not filter synthetic `__jk_*` aliases out of golden comparisons; do not
let the harness synthesise the connection object or count seed statements.

---

## 5. Sequenced plan for `core_relational`

Ordered by *tests-unblocked per unit of risk-adjusted effort*.

| # | Work | Unblocks | Size | Notes |
|---|---|---:|---|---|
| 1 | Fix the `testKindOf` census bug (§1) | 0 tests, correct denominator | XS | One line. Do it first so every later number is honest. |
| 1b | Split the `scoreAssert` message so a platform wall stops reporting as an assert-form gap (§2.1) | 0 tests, **82 rows re-bucketed** | XS | Same reason: nobody can prioritise against a column that says "harness" 95 times and means it 3 times. |
| 2 | Fix the TDS comparator to render type + arity (§3.2) | 1–2, plus **audit value** | S | A comparator that fails invisibly can pass invisibly. Prerequisite for trusting §4.2. |
| 3 | Ledger the DuckDB-order tests against the H2 sweep (§3.1) | 6–9 reclassified | S | Not a code fix. Removes false burn-down targets. |
| 4 | Ledger bucket 1 as out-of-scope (§4.3) | 23 reclassified | S | Denominator honesty; frees attention. |
| 5 | Fix the two wrong-SQL alias-scope bugs (§4.1) | 2, high severity | M | `t5 not found`, `t0 has no column firstName`. Correctness risk, not just coverage. |
| 6 | DuckDB dialect gaps: `len(DOUBLE)`, `acos/asin` domain, TIMESTAMP_NS vs TZ, string→BOOL | 5–6 | M | Mechanical; each is one renderer arm. |
| 7 | Replace `list_transform`-with-subquery lowering | 2 | M | `subqueries in lambda expressions are not supported` — needs a different shape, not a dialect flag. |
| 8 | Duplicate-row preservation / join isolation (§4.2) | ~5 | L | Theme: engine does not dedupe. One investigation, several tests. |
| 9 | `resolver/ClassSources` + `Substitution` walls | up to 22 | L | Largest ERROR concentration. Sub-split by wall text; expect 2–5 tests per fix. |
| 10 | `tests/mapping/relation` filter-in-project + window frame | 3 | M | Same file, likely one cause. |
| 11 | Date/timezone carrier | 3 | L | Known hard design problem; do not start it opportunistically. |
| 12 | Engine metamodel surface (§3.3 non-self-reflective part) | ~8 plan tests + the `mft::` `Firm` name-resolution bug | M | |
| 13 | **Execution-plan cheap 8** (§4.4 table: clusters 1,2,3,4,5,6,8,13) | ~14 | M | Better than its family average. Two are XS one-liners. Do this slice; do not commit to the family. |
| 14 | Execution-plan expensive tail (clusters 14,15,16,17,18,19,22) | ~20 | XL | Each L, each gating 3–5 tests. Cluster 15 also fixes the third wrong-SQL bug. Schedule as sustained grind, not a sprint. |
| — | **Defer:** clusters 20 (correlated aggregates) and 21 (milestoned propagation) | 4 | XL | #20 is a rows-correctness study — the two shapes disagree on empty groups. Do not chase its goldens. |

**Realistic ceiling.** After steps 3 and 4 the honest non-passing count is ~244.
Steps 5–12 plus the execution-plan cheap 8 plausibly reach **~2360–2390 / 2575
(92–93%)**. Past that, every remaining test costs an L-sized subsystem, and two
of them (clusters 20/21) should be entered as correctness investigations rather
than burn-down items.

---

## 6. The engine-wide estate — 5390 tests

Four parallel censuses covered all 5390 with no gaps or double-counts.

| group | tests | verdict |
|---|---:|---|
| `core_relational` (today's corpus) | 2727 | 2299 passing |
| **per-dialect relational roots** | 624 | **558 are pure SQL-text goldens needing no database** |
| `core` platform + platform functions | 1016 (1001 live) | ~302 small-bridge, ~543 need M3-as-data, ~165 N/A |
| Legend SQL / GraphQL / analytics / dataquality / changetoken | 687 | 265 should never be on the roadmap |
| external formats, bindings, persistence, long tail | 392 | 384 assert generated text; 1 root matters |

### 6.1 The single biggest finding: 558 dialect goldens need no database

Of 624 tests across 25 per-dialect roots, **613 touch no database at all**, and
558 are `assertEquals('select …', toSQLString(query, mapping, DatabaseType.X, …))`.

Critically, **the query model is the shared `core_relational` model** —
`simpleRelationalMapping`, personTable/firmTable/tradeTable — which legend-lite
already compiles and lowers. What is missing is only the **renderer**. The gate
is one switch, `core/src/main/java/com/legend/StatementExecutor.java:369-378`:

```java
com.legend.sql.dialect.EngineStyleH2 renderer = switch (db) {
    case "H2" -> new EngineStyleH2();
    case "DB2" -> new EngineStyleDB2();
    case "Composite" -> new EngineStyleComposite();
    default -> throw new NotImplementedException(
            "toSQLString for DatabaseType." + db + " — only the H2/DB2 engine-style renderers are built");
};
```

`EngineStyleDB2` is the template: **9 overrides, 270 lines**. Yield per renderer:

| renderer | golden tests |
|---|---:|
| SybaseIQ | 120 |
| Presto | 69 |
| Databricks | 54 |
| Snowflake | 54 |
| MemSQL | 45 |
| Postgres | 40 |
| Sybase | 16 |
| SqlServer | 14 |
| DuckDB (engine-text, distinct from the execution renderer) | 8 |
| SparkSQL 2, Hive 1, Oracle 1, ClickHouse 1 | 5 |

**425 tests reachable through machinery legend-lite already owns.** Presto is the
best first target: 69 tests, double-quoted identifiers like H2, mostly function
respelling. Oracle is the hardest (drops `AS` in aliases, `||` concatenation —
structural, not respelling).

Prerequisite: `Corpus.RELATIONAL` (`Corpus.java:48-51`) is a single hard-wired
path and `allFamilies()` walks only it. Every root needs adding, plus each
module's `relational/connection/metamodel.pure` as a library source.

**Full per-root breakdown.** All roots live under
`legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/`
unless noted. Harness pattern is uniform: a JUnit3 `static TestSuite suite()` in
`…-pure/src/test/java/org/finos/legend/pure/code/core/Test_Pure_Relational_<Db>.java`
calling `PureTestBuilderCompiled.buildSuite(TestCollection.collectTests(…))` —
no testcontainer, no connection acquisition.

| root | n | composition | verdict |
|---|---:|---|---|
| sybaseiq | 135 | 120 SQL-golden, 8 plan-golden, 7 exec-on-H2 | renderer-only |
| snowflake | 75 | 54 SQL-golden, **17 plan-golden**, 2 struct, 2 exec | mixed — the 17 need `RelationalBlockExecutionNode`, `CreateAndPopulateTempTable` |
| presto | 72 | 69 SQL-golden, 2 plan, 1 exec | renderer-only — **best first target** |
| databricks | 55 | 54 SQL-golden, 1 struct | renderer-only — backtick quoting is an `AnsiSqlRenderer`-level change |
| memsql | 50 | 45 SQL-golden, 5 plan | renderer-only — backticks, `INTERVAL` arithmetic |
| postgres | 41 | 40 SQL-golden, 1 plan | renderer-only — `Text'…'` literals, `string_agg`, `group by 1` ordinals |
| sybase | 20 | 16 SQL-golden, 3 plan, 1 exec | renderer-only + 1 DDL native |
| sqlserver | 15 | 14 SQL-golden, 1 plan | renderer-only — `+` concat, full-expression `group by` |
| duckdb (engine-text) | 9 | 8 SQL-golden, 1 struct | renderer-only — distinct from the *execution* renderer |
| sparksql 4, hive 2, oracle 2, clickhouse 1, redshift 1 | 10 | mixed | renderer-only; **Oracle is structurally deepest** (drops `AS`, uses `\|\|`) |
| aurora 2, bigquery 1, athena 1 | 4 | connection-equality only | **RUNNABLE-NOW** once the harness walks the root — `harness/ConnEquality.java` already implements the rule |
| `core_snowflake_test` + `core_snowflake` | 49 | SnowflakeApp activator goldens | needs `readFile` + full `compileLegendGrammar` + activator |
| `…_sql_dialect_translation` (+h2/duckdb/snowflake) | 63 | dialect-as-pure-data over the SQL-frontend AST | **different layer** — a renderer buys nothing here |
| `…_sql_planning` | 23 | SQL-text in, rule-transformed SQL-text out | needs `parseSqlStatement` — a SQL *parser* |
| `core_relational_test` | 3 | mutation plans + `printDatabase` round-trip | needs the mutation plan family |

Two upstream anomalies: `core_relational_clickhouse` has **no `src/test`
directory at all** (its one test is executed by nothing), and
`meta::relational::tests::tds::postgres` is in no `collectTests` call.

**Secondary blockers, counted:** `toSQLString/5` (the `quoteIdentifiers`
overload) 3 tests; `toSQLString/8` 1 test; the lower-level
`sqlQueryToString`/`toSQL` IR→text surface ~14 tests; DDL natives
(`createTempTableStatement`, `getTempTableSqlStatements`) ~8 tests.

Also free once the harness walks those roots: **9 connection-equality tests**
(aurora/bigquery/athena/oracle/snowflake) — `harness/ConnEquality.java` already
implements the rule.

### 6.2 The highest-leverage single bridge: `jsonEquivalent`

`core/store/m2m/tests/legend` (145 tests) is **already architecturally supported**:
- legend-lite ingests that directory as library source today
  (`RelationalCorpusRunner.java:132-143`);
- it turns `JsonModelConnection(url='data:application/json,…')` into a typed
  `VALUES` relation (`resolver/JsonSourceFrame.java:21-31`);
- the structurally identical relational `graphFetch/tests` family already scores
  136/144.

The gap is **one assert alias**. `jsonEquivalent` appears 179× and is not a
recognised assert form, so `assert(jsonEquivalent(a,b))` falls into the generic
arm and tries to lower `jsonEquivalent` to SQL. Aliasing it onto the existing
`assertJsonStringsEqual` arm (`harness/EngineTestExecutor.java:1964`, whose
`stripJsonCanon` already discards the `parseJSON()` wrappers) plausibly unlocks
the bulk of 145 tests.

**This is the best ratio in the entire report.**

### 6.3 The highest-leverage strategic item: `reprocess` / `classesToDatabase`

`core_external_test_connection` is only 8 tests, but they cover the machinery by
which legend-engine turns **arbitrary in-memory Pure queries into a real database
+ generated mapping + CSV seed data** (`pct_relational.pure:986-1057`). That is
how ~5000 platform PCT function tests become *relational execution* tests.

legend-lite already has the raw material — `testdatagen/TestDataGenerator.java`,
`harness/TestDataGenForm.java`, the `pct/` and `pct-corpus/` trees. The missing
piece is `printFunctionDefinition`, a Pure grammar printer, which 7 of the 8
asserts compare against.

That same printer is the shared gate for **156 Legend-SQL + 71 dataquality +
most of GraphQL** — 300+ tests hang off one absent component. Every one of those
asserts is *text*, not rows, so it buys breadth, not correctness evidence. But it
is the single most-depended-upon missing piece in the estate.

### 6.4 Other cheap wins

| item | tests | blocker |
|---|---:|---|
| `core_analytics_lineage` | 47 of 58 | Lineage **graph** node-id builder. `ScanColumns`/`ScanRelations`/`PkInference` already written and unit-tested; the runner already routes `scanColumns`/`scanRelations` (`Runner.java:872-873`); the test models are ones legend-lite already compiles. Node ids are mechanical (`db_`+name, `tb_`+db+schema+table, `pack_`+package). The other 11 need M3 function-pointer reflection — a separate, harder gap. |
| `core/pure/corefunctions` value semantics | ~114 | Register the `*Extension.pure` libraries — verified to declare **zero natives**, so they inline through `UserCallInliner` exactly as `RelationalCorpusRunner.java:64-127` already does for three hand-picked snippets from the same files. Residual work is an enumerable list of leaf natives (`substringBefore`, `isDigit`, `chunk`, `newMultiValueMap`, `Duration` add/subtract). |
| `core_functions_unclassified` string/collection | 21 | `parseCSV`, `encodeUrl`/`decodeUrl`, `chunk`, `repeat`, `containsAny`, `get` need DuckDB lowerings. |
| `dataquality_relation_helper_test.pure` | 21 | Library-source registration only. `#TDS` literals already compile (`Typer.java:141`). |
| `core_functions_relation` | 11 | `assertTdsEquivalent` is **already implemented** (`EngineTestExecutor.java:1946`); needs standalone `#TDS` ingestion outside the relational corpus + `wrapPrimitiveInTDS`/`columns`/`toCSVString`. |
| `core_mongodb_execution_test` | 4 | Widen `EngineTestExecutor.clgArm` (`:791-840`) from "FunctionDefinitions only" (line 830) to "compiled elements". `MongoDBSectionGrammar` already parses the grammar. Also unlocks 2 JSON binding-validation tests. |
| `core_functions_standard/date/extract` | 6 | Probably runnable now — `dayOfWeekNumber` is already a native. Residual risk is the enum-receiver and 2-arg week-start overloads. |
| `core_diagram` | 2 | `readFile` + a compile-to-elements route. `Protocol.PDiagram` (`Protocol.java:1508-1551`) is already field-for-field what the tests assert. |
| `core_elasticsearch_seven_metamodel` | 3 | A small host evaluator for `^`-instances + property reads + `match`. |

**The dominant blocker across the `core` root** — ~543 of 1016 rows — is one
thing: those tests assert facts about the **M3 metamodel as manipulable data**
(`Class.properties`, `FunctionDefinition.expressionSequence`, `GraphFetchTree`
nodes, `ExecutionNode` children, `ValueSpecification`→JSON). legend-lite has a
compiled `ModelContext` but no reflective M3 face:
`exec/MetamodelWalk.java:15-24` is scoped to the *store* metamodel only,
`resolver/GenericTypeReflection.java` handles exactly one query-shaped idiom, and
`harness/ReflectAsserts.java` exactly one predicate. **That is a design boundary
(tenet 1), not a backlog.** It is the same boundary as §3.3 and §4.3, reached
from three independent directions.

### 6.5 What to strike from the roadmap — 413+ tests

| root | n | why |
|---|---:|---|
| `core_pure_changetoken_test` | 192 | Generates Java source, compiles it with javac, runs it, diffs JSON. No query, no mapping, no store, no SQL. |
| Java platform binding / Java generation (6 roots) | 148 | Assertion target is generated Java text and its compiled behaviour. |
| `core_external_query_relationalai` | 38 | Datalog backend — **and dead upstream** (only an `@Ignore`d integrity test). |
| `core_analytics_quality` | 34 | Pure source linter — **dead upstream**. |
| `core_analytics_test_coverage` | 1 | Meta-tooling about test suites — **dead upstream**. |
| `core_external_language_haskell` + `_daml` | 5 | **No `PureTestBuilder` suite anywhere in legend-engine** — they compile but never run. |
| external formats (Avro/protobuf/Rosetta/Morphir/OpenAPI/XML/flatdata/JSON-schema) | ~380 | No external-format subsystem; `###ExternalFormat` parses to an opaque shell (`ExternalFormatSectionGrammar.java:40-46`); zero relational content. |

**Upstream defects worth reporting to FINOS:** the haskell/daml tests, the
relationalai/quality/test-coverage roots, and `core_relational_clickhouse` (which
has no `src/test` directory at all) are compiled but never executed by legend-engine's
own CI.

---

## 7. Recommended order of attack

1. **Correct the ledger** (§5 steps 1–4). One-line census fix, comparator fix,
   two reclassifications. The 276 becomes an honest ~244.
2. **`jsonEquivalent` alias** (§6.2). Best ratio in the report — one alias,
   up to 145 tests.
3. **Wrong-SQL alias-scope bugs** (§5 step 5). Correctness, not coverage.
4. **`EngineStylePresto`** (§6.1). 69 tests, proves the renderer pattern; then
   SybaseIQ (120) and the rest — 425 total on a template that already exists.
5. **`core_analytics_lineage` graph builder** (§6.4). 47 tests over machinery
   already written.
6. Then the resolver walls (§5 step 9) as the sustained core_relational grind,
   and decide separately whether `printFunctionDefinition` (§6.3) is worth its
   300-test breadth given that none of it asserts rows.

**One judgement to make explicitly.** Steps 2, 4 and 6.3 buy *breadth* — more
tests, mostly asserting text. Steps 3, 5 and §5 steps 8–11 buy *correctness
evidence* — fewer tests, all asserting rows. The stated goal is end-to-end
execution. If that is still the goal, the correctness work should not be
displaced by the larger numbers available elsewhere.

---

## 8. Confidence — what is verified, what is relayed

This section exists so a session reading cold knows which claims it may act on
directly and which it must re-check first.

**Verified by execution on this machine** — act on these:

| claim | how |
|---|---|
| 2299/2575, and the committed scoreboard is current | full 8-gate chain, `git diff` on `RELATIONAL_CORPUS.md` empty |
| census is 2721/2575/146, not 2798/2575/223 | `tools/recon3.py`, reconciles runnable and `ExcludeAlloy` exactly |
| 82 of 95 SHAPE rows are platform gaps | counted over `features.json`; mechanism read at `EngineTestExecutor.java:878-886` |
| the `ROOT/TDSNull` cluster is DuckDB row order | H2-backend scoped run — `tests/mapping/filter` 8/9 → 9/9; control test passes on DuckDB already |
| 7 tests pass on H2 but not DuckDB, 48 the reverse | full H2 sweep diffed against the DuckDB ledger (`h2-backend-sweep.txt`) |
| the invisible comparator failure | reproduced on both backends, `tests/mapping/tree` |
| 1008 dropped elements and their root types | parsed from the sweep's own walls section |
| the 276-row classification | every test resolved to source, 276/276, zero unresolved |
| `executionPlan/tests` is 61/108 | scoped family run — the ledger is not stale |

**Relayed from subagent research, not re-verified** — check before acting:

- §6.1's per-root dialect counts and the 558/425 figures.
- §6.2's `jsonEquivalent` claim (179 occurrences, the `stripJsonCanon` overlap).
  This is the report's best-ratio recommendation *and* one of its least-verified;
  confirm the occurrence count and that `assertJsonStringsEqual`'s canonicaliser
  really accepts these shapes before committing to it.
- §6.3's `printFunctionDefinition` dependency graph.
- §6.4's per-item blockers and §6.5's strike list.
- §4.4's 23-cluster table and its file:line attributions.

Every one of those carries file:line citations that can be checked in minutes.
None of them was executed.

**Known gaps in this analysis:**

- No timings anywhere. Nothing was benchmarked and nothing should be, on a
  machine running gates.
- The subagent transcripts are not preserved; §6 and §4.4 are the only record.
- Bucket 11 (11 unclassified one-offs) was left unclassified rather than forced
  into a bucket. They are listed in `master-classification.csv`.
- The 245 `sqldiff-pass` tests (§3.4) were counted but not individually examined.
  That is silent SQL drift on *passing* tests and deserves its own pass.

---

## 9. Execution guide — start here if you are picking this up cold

### 9.0 The loop, every time

```bash
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$HOME/jdk/apache-maven-3.9.9/bin:$PATH"
cd ~/legend/legend-lite && git pull --ff-only origin main   # a parallel session pushes often
```

1. **Pick a target** from `docs/burndown-2026-08-14/master-classification.csv`
   (filter by `bucket`). Read its full body:
   `BURNDOWN_OUT=/tmp/burndown python3 docs/burndown-2026-08-14/tools/dossier.py`
   then open `/tmp/burndown/dossiers/<family>.md`.
2. **Ground the semantics in the real engine**, never in the corpus pin —
   `grep -rn "function .*::<name>" ~/legend/legend-engine ~/legend/legend-pure`.
   Tenet 5; audit 20a's not()-COALESCE finding is the cautionary tale.
3. **Fast probe** while iterating (scoped runs never rewrite the scoreboard):
   ```bash
   mvn -o -f pom.xml -pl core test -Dtest=RelationalCorpusRunner \
       -Drcorpus.only=<family> -Drcorpus.test=<testName> \
       -Dlegend.engine.root=$HOME/legend/legend-engine
   ```
4. **Prove it** with the full chain — never a family probe alone, which has lied
   via seed-context artifacts:
   ```bash
   LEGEND_ENGINE_ROOT=~/legend/legend-engine LEGEND_PURE_ROOT=~/legend/legend-pure \
     caffeinate -dims tools/allgates.sh && cat ${TMPDIR:-/tmp}/gates-$(id -un).log
   ```
   `allgates.sh` always exits 0 — read the log, not the exit code. Gate 4 will
   rewrite `docs/RELATIONAL_CORPUS.md` **only if no family regressed**; a
   regression leaves the committed file intact and prints the deltas.
5. **Classify every delta** in the scoreboard diff before committing. Then commit
   with the sweep evidence in the message (this repo's convention: commit
   messages carry the narrative).

### 9.1 The two ledger-honesty fixes, concretely

Neither changes a test verdict, so a green chain with an unchanged
`RELATIONAL_CORPUS.md` total row is the whole proof. Do them first — everything
downstream is prioritised off numbers they correct.

**(a) `core/src/test/java/com/legend/rcorpus/Runner.java:459`**

```java
// before — `excluded` wins before `isTest` is consulted, so a function
// stereotyped <<test.ToFix>> with NO test.Test counts as an excluded TEST
return excluded ? TestKind.EXCLUDED : isTest ? TestKind.TEST : TestKind.NONE;

// after — not a test at all unless it carries test.Test
return !isTest ? TestKind.NONE : excluded ? TestKind.EXCLUDED : TestKind.TEST;
```

Expected: census line goes `2798 total … 223 excluded` → `2721 total … 146
excluded` (`ToFix` 127 → 50; `ExcludeAlloy` stays 96). Runnable stays **2575** —
if it moves, stop, you have changed discovery.

**(b) `core/src/main/java/com/legend/harness/EngineTestExecutor.java:881-886`**

Lead with the real cause when there is one; keep the assert form as trailing
context so nothing diagnostic is lost; claim an assert-form gap only when
`why == null`:

```java
if (failure == UNSUPPORTED_MARKER) {
    String why = takeUnsupportedReason();
    String form = "assert form '" + af.function()
            + "/" + af.parameters().size() + "'";
    return new Outcome.Unsupported(why != null
            ? why + " [" + form + "]"
            : form + " is not supported yet");
}
```

Expected: 82 of 95 SHAPE details stop *opening* with "assert form … is not
supported yet". Counts do not move. Verify with the snippet in
`docs/burndown-2026-08-14/README.md` §"Key reproductions".

> **Scope note.** This is a message change only, which is why it is safe. The
> larger question it exposes — that a row with a real platform cause arguably
> should score `ERROR`, not `SHAPE` — **does** move the 89/92/95 split and the
> per-family baselines the regression gate asserts against. Treat that as a
> separate, deliberate decision; do not fold it into this fix.

> Both are in `core`, so gate 2 (`mvn -pl core install`) must run before gates
> 4/5 or you A/B the previously installed jar.

### 9.2 Where each bucket's work starts

| bucket | filter `master-classification.csv` on | first file to open |
|---|---|---|
| 5 — invalid SQL | `bucket` starts `5.` | `sql/dialect/AnsiSqlRenderer.java`, `sql/dialect/DuckDb.java`; for the two alias bugs, `lowering/Lowerer.java:1173,1268` |
| 6 — wrong rows | `6.` | depends on sub-cluster (§4.2); start from the dossier, not the code |
| 7 — resolver | `7.` | `resolver/ClassSources.java:623`, `resolver/Substitution.java:1341,1372,1897` |
| 9 — typer | `9.` | `compiler/spec/Typer.java`, `builtin/Pure.java` |
| 8 — lowering | `8.` | `lowering/Lowerer.java`, `lowering/Scalars.java:2383` |
| 2 — plan | `2.` | §4.4 cluster table names the file per cluster |

### 9.3 Traps that have already cost time

- `-Dlegend.engine.root` is a **system property**; `LEGEND_ENGINE_ROOT` is only
  read by `allgates.sh`. A hand-run `mvn` with the env var set silently reads the
  default checkout. Tells: `census: 2759` instead of `2798`, `h2-exec 0
  verified`, ~320s instead of ~90s.
- `mvn -pl core test` resolves core from `~/.m2`, not the reactor.
- Do not build while a gate chain runs — swapping the jar underneath produces
  fake failures.
- `caffeinate -dims` or your timings are fiction.
- **Do not fix any of this in the harness.** §4.4 lists four specific tenet-2
  traps; the general rule is that a shape is owned by the platform or the
  harness, never both, and harness compensation is the cardinal sin.
- A wrong-rows PASS is worse than an honest ERROR. If you cannot support a shape,
  wall it loudly and name it.

### 9.4 If the numbers here no longer match

This document is a **snapshot**. Re-derive rather than trust:

```bash
export BURNDOWN_OUT=/tmp/burndown
cd docs/burndown-2026-08-14/tools
python3 ledger.py && python3 features.py && python3 master.py
```

That regenerates the 276-row classification from whatever
`docs/RELATIONAL_CORPUS.md` currently says. If the corpus has moved, the buckets
move with it and this document's §5 ordering should be re-checked against the new
`master.csv` before it is followed.
