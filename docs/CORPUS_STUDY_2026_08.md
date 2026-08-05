# The relational corpus, test by test — a root-cause study of all 356 non-passing rows

> **Status.** Complete. Every non-passing row in `docs/RELATIONAL_CORPUS.md` (sweep of 2026-08-03)
> was studied individually — source located, body read in full, mapping/database/model dependencies
> followed, assert quoted, root cause traced to a legend-lite `file:line`, and classified.
> No sampling.
>
> **Supersedes** `docs/CORPUS_TAXONOMY.md` as the cause taxonomy. That document was written against a
> dataset with 2,132 passes / 406 non-passing; the current sweep is 2,182 / 356, and several of its
> ranked causes have since burned down. It remains useful for its *method* (§ "Six independent
> slices") and for the "Do NOT do these" list, which this study did not re-litigate.
>
> **Read § 1 before quoting any number from the scoreboard.**

---

## 0a. Dataset drift — re-verified 2026-08-05 on `main` @ `1be0d0b3`

The study was conducted against the sweep committed on **2026-08-03**. `main` moved 125 commits
while it ran. A fresh sweep on current `main` gives:

| | study dataset (08-03) | `main` @ `1be0d0b3` (08-05) | Δ |
|---|---|---|---|
| tests | 2538 | **2567** | +29 |
| pass | 2182 | **2253** | +71 |
| FAIL | 76 | **104** | **+28** |
| ERROR | 144 | **97** | −47 |
| SHAPE | 136 | **113** | −23 |
| non-passing | 356 | **314** | −42 |

**The shape of that movement independently validates § 8.1.** ERROR and SHAPE fell by a combined 70
while FAIL *rose* by 28 — which is precisely what "removing a shallow wall reveals the wrong answer
behind it" predicts. Anyone reading the headline `−42 non-passing` as `42 tests fixed` would be
wrong: a large share of the ERROR/SHAPE reduction reappeared as FAIL.

**§ 9.1 is resolved.** Commit `0dc9ea53` ("Corpus unlock: upstream explicit-src relation mappings")
taught the parser the `$src.<col>` relation-mapping spellings the 08-04 upstream pull introduced. The
sweep runs again. The commit message confirms the diagnosis in § 9.1 independently — gate 4 had been
dead for both `main` and the parser branch since the checkout moved.

**§ 9.2 is not resolved, and is now load-bearing.** `Corpus.ENGINE_ROOT` still defaults to
`/Users/neema/legend/legend-engine` (`Corpus.java:31-37`), but the committed baseline is produced
against `/Users/neemsandv/legend/legend-engine`. The two are genuinely distinct checkouts (different
inodes; 2668 vs 2697 `<<test.Test>>` functions — a difference of exactly 29, matching the table
above). Running the sweep **without** the override trips the runner's own regression gate:

```
CORPUS REGRESSION vs committed docs/RELATIONAL_CORPUS.md:
  [tests/mapping/relation 90 < baseline 103, transform/fromPure/tests 43 < baseline 44]
  — fix or revert; do not commit the rewritten scoreboard
```

So the sweep must be invoked as:

```bash
mvn -o test -pl engine -am -Dtest=RelationalCorpusRunner \
    -Dlegend.engine.root=/Users/neemsandv/legend/legend-engine \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

With that override the sweep is **green** and reproduces the committed scoreboard exactly. The
default in `Corpus.java` should be corrected, or the override documented in the runner — as it
stands, the obvious invocation silently reads the wrong corpus and looks like a 42-test regression.

**What this does and does not invalidate.** Every root cause in this document is anchored in
legend-lite's own Java, which the drift does not touch; the cause groups, `file:line` citations and
classifications stand. What has moved is the *per-test verdict roster* — some studied rows now pass,
some changed bucket, and 29 newly-visible tests were never studied. Re-partitioning against the
current 314 would be a re-run of § 0's method, not a re-derivation of § 2–§ 7.

**One unexplained observation.** Across two runs at identical `HEAD` and identical corpus root, one
row (`testViewToTDS [executionPlan/tests]`) reported a *different wall message* — `class
'meta::relational::mapping::TableTDS' has no property 'store'` versus `property 'table' … expected
NamedRelation, got View`. Same test, same SHAPE verdict, no number affected. The two messages come
from different loops in `compiler/spec/NewChecker.java` (the copy path at `:40` and the construction
path at `:70`), so the query is reaching a different checker between runs. Not chased; recorded
because a scoreboard that is not byte-reproducible is a hazard for any ratchet built on it.

---

## 0. Method, and what makes this different from a symptom census

The dataset is the 356 rows of `docs/RELATIONAL_CORPUS.md` that carry a non-PASS verdict:
**76 FAIL, 144 ERROR, 136 SHAPE**, across 47 of the corpus's 66 families.

Rows were partitioned into 18 batches by family (keeping each family whole where possible) and each
batch given to an independent investigator with the same brief:

1. Locate the test's Pure source; give the exact `file:line`.
2. Read the **entire** function body — not a snippet — plus the mapping, database, class definitions
   and seed data it depends on.
3. Use every available signal: directory, filename, test name, stereotypes, comments, and **sibling
   tests in the same file that pass**.
4. Record what the test exercises and quote what it asserts.
5. Trace the root cause into legend-lite's Java, reading the code — never inferring the cause from
   the error string, and explicitly **not** trusting legend-lite's own docs.
6. Classify: `real-defect` / `unbuilt-feature` / `oracle-artifact` / `out-of-scope` / `harness-defect`.
7. Report cause-sharing, and state plainly what could not be determined.

Coverage was verified arithmetically before the work started: the 18 batch files contain exactly 356
rows, and the family table's `fail + error + shape` columns sum to exactly 356 with no family
carrying rows the table does not count.

**Why the sibling-passes control matters.** Because the 356 rows are the *complete* failure set, any
sibling test not appearing in a batch file necessarily passes. Investigators used this constantly to
isolate a defect to a single differing ingredient — "the same query without `subType` passes", "the
same assert form passes twenty lines earlier", "the same mapping with a two-set union passes". Many
of the strongest conclusions in this document rest on that control rather than on a run.

Ten of the eighteen batches were additionally **reproduced live** (`-Drcorpus.only=<family>`, with
`LEGEND_LITE_STACKS`, `LEGEND_LITE_DUMP_SQL`, `LL_TMP_DEBUG`, `LL_SQLTEXT_DEBUG`, `LL_H2_DEBUG`),
which supplied untruncated messages, full stack traces and the actual emitted SQL. Where a finding is
observed rather than read, this document says so.

---

## 1. Scoreboard integrity — read this first

### 1.1 The verdict labels do not mean what the legend says

`docs/RELATIONAL_CORPUS.md` defines SHAPE as *"test body/assert form the runner does not yet
recognize (accounted, not skipped silently)."* Five investigators independently found that it is also
produced by at least three other situations:

**(a) A hard exception, swallowed.** `harness/TestBody.java:984` catches
`RuntimeException | SQLException` from the golden-SQL re-render and falls through to the advisory
path. Five milestoning tests reported as SHAPE are in fact
`IllegalStateException: no SQL type for Pure primitive LATEST_DATE at the lowering boundary`
(`lowering/PureSql.java:65-66`).

**(b) A comparison never attempted.** `sqlTextVerify` can only re-render an `execute()`-rooted chain
(`harness/ExecCallFinder.java:48-62` hard-codes `simpleName(...).equals("execute")`). A
`toSQLString`-rooted golden is skipped — *even when both sides are plain strings and directly
comparable*. At least 15 tests across five families. In one case
(`tds::testSimpleSliceZeroSameAsTake`) the assert compares **our own SQL to our own SQL** with no
engine golden involved, and is still discarded.

**(c) A genuine `NotImplementedException` downgraded.** `harness/LineageRelationsForm.java:131-139`
converts a real wall from `ScanRelations` into `advisory` for runtime variants.

And the converse — a SHAPE that should be an ERROR, and an ERROR that should be a SHAPE:

**(d) `DialectCapability extends IllegalStateException`**, so a *declared* capability wall inside a
plan assert escapes the catch at `harness/PlanAsserts.java:177-179` (which catches only
`NotImplementedException | LegendCompileException | UnsupportedOperationException`) and reports
ERROR.

**(e) Plan walls lose their reason.** `harness/TestBody.java:1569-1577` returns `UNSUPPORTED_MARKER`
without calling `unsupported(reason)`, so `scoreAssert` (`:857-865`) renders the generic
`assert form 'assertEquals/2' is not supported yet`. This is *provably false* for at least one test
whose identical assert form passes twenty lines earlier in the same file. Already logged at
`docs/CORRECTNESS_REMEDIATION.md:33-34`; this study confirms it corpus-wide.

**Consequence:** the 136 SHAPE rows are a mixed bag of genuine form gaps, masked hard walls, and
never-attempted comparisons. Any burndown estimate computed from the SHAPE column is unreliable
until (a), (b), (c) and (e) are fixed. Those five changes unlock no tests by themselves and are the
correct first work item.

### 1.2 Two rows in the PASS column prove nothing

- **`functions::tests::concatenate::testConcatenateFlat`** passes by coincidence. It emits two rows
  with SQL `LIST` cells where the engine emits four rows from a `UNION ALL`; the harness flattens the
  two lists and arrives at the expected `[1,19,2,20]`. Its sibling
  `testConcatenateFlatWithOtherProperty` adds one plain column and the coincidence collapses (that
  sibling is a FAIL in this study). Fixing the real bug will change this test's SQL.
- **`testDataGeneration::testAlloyTestDatGenWithViewAsDriver`** passes via
  `Runner.java:1370-1375`'s `"0 asserts — N statement(s) executed"` arm. Its only real assertion
  lives inside a `mayExecuteAlloyTest` leg that `harness/TestBody.java:344-362` discards whenever the
  leg references its own `clientVersion`/`host`/`port` parameters. Its twin
  `testAlloyTestDatGenForNestedViews` scores SHAPE *purely* because it binds its setup with a `let`
  instead of a bare statement. One is green and one is not; neither verifies anything.

**So the pass rate has air in both directions**, and at least one no-op PASS arm exists that will
manufacture more hollow passes if the assert-free rule is relaxed without care (see § 8.3).

### 1.3 What is genuinely excluded

Separately from the 356, **144 test functions are excluded by stereotype**: 50 `<<test.ToFix>>` and
94 `<<test.ExcludeAlloy>>`. `<<test.Ignore>>` is **zero** — the runner's comment and the scoreboard
header both cite it, but nothing in the corpus uses it. Both exclusions are defensible (ToFix: the
engine does not run them either; ExcludeAlloy: not for the Alloy-shaped path we execute).

---

## 2. Architectural finding #1 — `execute()` is fused, not materialized

**This is the single largest finding of the study.** Four investigators, four different families, no
shared context, one cause.

In legend-engine, `execute()` is **eager**: `$result.values` is a materialized collection of Pure
objects, and any downstream read is an in-memory operation over those objects. In legend-lite the
result is **fused back into the plan** and re-planned as SQL
(`StatementExecutor.buildFrame:1886-1895`, `ExecFrame` at `:1872-1873`, splice at `:2414-2439`,
execution tail at `:2795-2821`).

That single divergence produces five distinct classes of wrong answer:

| # | Mechanism | Observed effect |
|---|---|---|
| 1 | A downstream `.prop` read re-plans as a fresh projection; the object extent's row-multiplying join is undemanded and stripped (`resolver/Pipelines.java:404-406`) | **6 rows → 3** (`testMultipleJoinsInPropertyMappingWithDatesInClass`) |
| 2 | The fused read is `TABULAR`-shaped (`exec/ResultShape.java:38-41`), so the empty-drop never runs | SQL NULLs survive as collection elements — **7 values where Pure gives 5** (two tests, two families) |
| 3 | A Pure relation `sort` is fused into SQL `ORDER BY` with no `NULLS` clause (`lowering/Fold.java:333-346`) | Null placement follows the dialect default instead of Pure's nulls-largest |
| 4 | `$result.values` splices the **bare** stored chain and executes it under the ambient `ExecEnv` | The connection post-processor's table-replace map is lost — **queries the original tables** |
| 5 | The same splice puts `TypedFrom` *in-chain* rather than at the statement root, bypassing `StoreResolver.java:298-306` | Stale `chainContext` → wrong mapping dispatch (three independent confirmations) |

Mechanism 4 is the sharpest demonstration. With `LL_TMP_SQL=1`, one test emits **three** executions:

```
[exec-sql] SELECT … FROM otherFirmTable AS t0 LEFT OUTER JOIN differentPersonTable AS t1 …   ← replaced
[exec-sql] SELECT … FROM otherFirmTable … differentPersonTable …                              ← replaced
[exec-sql] SELECT … FROM firmTable      AS t0 LEFT OUTER JOIN personTable          AS t1 …   ← NOT replaced
```

The rename map *is* derived correctly. `ExecFrame` simply does not carry the env, so the eager run
hits the renamed tables and the envelope re-read hits the originals. **The same query returns
different answers depending on how it is read**, with no error.

Mechanism 5 has the cleanest mechanism trace: `execute()`'s mapping is attached as a `TypedFrom`
*wrapping* the query lambda (`StatementExecutor.java:2072-2085`); frame reads splice that node
verbatim; so any read with an operation stacked on top (`$result.values->filter(...)->toOne()`) puts
the `TypedFrom` in-chain. `collectOpChain` snapshots the context *before* the walk
(`StoreResolver.java:2472`), the walk re-scopes only the parameter (`:2540-2543`), and root class
dispatch reads the **stale snapshot** (`:2607-2609`). `grep -n chainContext` returns exactly those
two lines; it is never reassigned.

Note this is **core, not harness**. The harness's synthetic two-mapping runtime
(`Runner.java:1421-1435`) merely removes the accidental cover that a single-binder runtime provides.

**The correct rule already exists in the codebase, twice** — `exec/Executor.java:117-130` and
`harness/TestBody.java:2483-2486` both encode "a SQL NULL is a Pure empty, and no Pure collection
holds empties". The fused path bypasses both.

---

## 3. Architectural finding #2 — `subType` is a column name, not a set narrowing

`subType` is canonicalized into a **synthetic column name** (`resolver/Substitution.java:718-736`,
with a parallel canonicalizer at `resolver/CorrelatedSubselects.java:1664-1669`, `:1709-1713`)
rather than narrowing the `ClassSource`. Four separately-diagnosed causes trace back to that one
decision:

1. **A bare `subType` in `exists` head position is not recognized.** `pathOf` has look-through arms
   for `toOne()` (`:684`), `map` (`:691`), milestoned access (`:701`) and `subType(...).prop`
   (`:718` — which requires a `TypedPropertyAccess` *wrapping* the cast), but none for a bare
   `subType(nav, @C)` feeding `exists`. `headPath` comes back null, the emptiness arm at `:778-783`
   is skipped — **even though `StoreResolver.registerExistsSubs:2014` already registered the
   handler** — and the walk falls to the whole-value throw at `:1278-1284`. Two tests.
2. **The synthetic column is never emitted for class-typed association ends.**
   `normalizer/UnionSynthesis.java:634-641` emits `stc_` dispatch columns only for scalars (and
   `:642-661` for embedded ctor leaves), so `subType(@Bicycle).person.name` has nothing to bind to.
3. **A no-op cast is still mangled.** Where the cast target equals the declared type, the golden SQL
   shows the cast should vanish entirely; we mangle it into `stc_…Party___name` and the
   embedded-ctor leaf lookup (`Substitution.java:1625`) — which has no `stc_` awareness at all,
   unlike the association-head arm at `:1235-1244` — misses.
4. **Two casts over one nav slot mint two join identities.** `SyntheticHeads.parkFiltered:207-220`
   keys an identity on `(real property name, alpha-canonical predicate body)` and consults **no set
   id, no join name, no target table, no nav-slot alias**. Two mutually-exclusive witness predicates
   therefore fail the reuse test and a second physical join is materialized
   (`StoreResolver.java:1390-1394` → `:2355-2399`), producing a **cross product: 5 rows where the
   engine gives 3** (arithmetic verified: 2×2 + 1×1).

Worth recording explicitly: cause 4 was initially mis-attributed to the mapping's two set ids. It is
not — nav-slot aliases key on the property name alone
(`normalizer/JoinChainEmission.java:525-547`) and the second property mapping is skipped outright at
`:289-294`. The mapping side produces exactly one join. **The split is entirely query-side.**

Roughly ten rows across three families trace to this one design point.

---

## 4. The catalogue of silent wrong answers

The worst category: we answer confidently and incorrectly, with no error and no wall. Sixteen
confirmed, beyond those in §2 and §3.

| Defect | Site | Effect |
|---|---|---|
| `in(x, coll)` over a non-literal collection appends the whole collection as **one element**, giving a 2-arg `IN` that is then collapsed to `=` | `lowering/Scalars.java:2254-2260` → `dialect/EngineStyleH2.java:838-843` | `LEGALNAME = string_split(…)` — cannot return the right rows. The correct branch (`SqlExpr.Membership`) exists twelve lines above at `:2248-2252` |
| `average()`/`mean()` over a to-one argument elided to the raw argument | `lowering/Scalars.java:1158-1163` | Drops both the mandated `Integer→Float` conversion and the `GROUP BY`. Output byte-identical to the raw column in PK order. `median` has the same shortcut but escapes because its return type is `Number` |
| A user `sortBy` under a graphFetch/serialize terminal is folded onto the pipeline, then discarded | `resolver/GraphEmission.java:418-439`, consumed `lowering/Lowerer.java:688-727` | `json_group_array` order keys come only from union witnesses + PK determinism keys; observed order is exactly PK order |
| `at(rel, n)` returns a **row**, not a cell — three distinct sites | `lowering/Lowerer.java:284-301`; `compiler/spec/Typer.java:2266`; `Typer.java:2232-2240` + harness carrier guard `TestBody.java:1898-1904` | "You asked for a cell, you got a row." Correct flattening already exists at `exec/HostEval.java:696-703` |
| `connection.postProcessors` (`MapperPostProcessor`) parsed, accepted, and dropped | `lowering/SqlPostProcessors.java:48-62` keys on `sqlQueryPostProcessorsConnectionAware`; `plan/RelationalMapperRenames.java:124-141` keys on `queryPostProcessorsWithParameter`; `readPostProcessor` `return`s silently otherwise | Queries the un-renamed table and returns wrong employees |
| `testDataSetupSqls` declared with **no consumer anywhere** | `builtin/Pure.java:252`; only `testDataSetupCsv` is seeded (`TestBody.java:2646-2666`, `Runner.java:1662-1673`) | Tables created empty by `Runner.moduleDdl`; graph fetch legitimately returns `[]` |
| Connection `timeZone` honoured only by the plan-text renderer | shift exists at `dialect/EngineStyleH2.java:337-359`; `Compiler.dialectOf:364-414` returns a bare `DuckDb` | GMT literals reach the DB unshifted — **0 rows instead of 5** |
| Correlated parent-copy subselect keyed on the association equi-key rather than the parent PK | `resolver/CorrelatedSubselects.java:200-201`, `_pk<i>` projection `:245-264` | **15 rows instead of 7** (4×3 + 3, verified) |
| Join-chain terminal resolves against the inherited **outer** table scope when the name collides | `normalizer/RelOpTranslator.java:529-537` | Chain silently pruned — **4 rows instead of 7**, and the wrong column source |
| `castAsDeclared` applies a wire-level coercion as a **SQL cast** | `normalizer/MappingNormalizer.java:2358-2371`, `:2483-2486` | Poisons every comparison context the property appears in (`'something'` → BOOL); also changes the TDS cell's runtime kind (`"11"` String vs `11` Integer) |
| `CsvSeed` splits rows with a naive `split(",", -1)` and bounds the value loop by **header width** | `exec/CsvSeed.java:89`, `:93` | A quoted comma tears one field into two and silently pushes the last real value off the end — data loss on any seed CSV with a quoted comma |
| `PlanEnumForm` strips the enum-decode `CASE` from **all** plan text unconditionally | `StatementExecutor.java:451-458`, `plan/PlanEnumForm.java:42-45` | The engine's rule is context-dependent (raw column for typed-TDS project, pushed-down CASE for `->from()`/externalize) |
| Parameterized-lambda `let` folding erases plan structure | `compiler/spec/Typer.java:1736-1747` (`SourceSubst.inlineLets`) | The `Allocation` node is never emitted — converts a loud gap into a wrong plan shape |
| `supportsStream` is a multiplicity heuristic, not the engine's use-site analysis | `StatementExecutor.java:1816-1818`, `plan/PlanNode.java:29-33` | Wrong `supportsStream` for a param used in both a supported and an unsupported position |
| Sub-microsecond DateTime literals render as plain `TIMESTAMP '…'` | `lowering/Lowerer.java:2199-2200` → `dialect/AnsiSqlRenderer.java:713-715` (no `DuckDb` override) | DuckDB truncates to µs against a `TIMESTAMP(9)`/`TIMESTAMP_NS` column — probe-verified, three tests |
| Query-side predicate folds into the same SELECT as a mapping `~func`'s window | `lowering/Fold.java:227-238`; the promised compensation is documented at `:242-252` and **unimplemented** | `rank()` sees filtered rows — rank 1 where the engine gives 2 |

Two further wrong-SQL (not wrong-row) defects worth listing here because they produce **unbindable
or invalid SQL**, not merely divergent text:

- **`t1.*` leaks a pre-rename internal alias into plan text.** `dialect/EngineStyleH2` renames only
  `SqlExpr.Column`/`RowOrder` (`:745-757`); there is no `SqlExpr.Star` case, so a qualified star
  falls through to `AnsiSqlRenderer.java:289` with the raw alias. The emitted plan text is not
  executable SQL.
- **Union branches project columns that do not exist.** `UnionSynthesis.java:865-880` projects the
  union of the members' scalars, and `SubselectPrune.java:280-283` explicitly declines to prune
  UNION branches (pruning requires dropping the same ordinal in every branch in lockstep). In one
  test the surplus column is absent from the seed DDL and DuckDB rejects the query.

---

## 5. Cross-batch cause groups (found independently by ≥2 investigators)

These carry the most weight because they were reached from different families without shared context.

**`EngineStyle*` renderers run zero MIR passes.** `dialect/EngineStyleH2.java:372-377` overrides
`passes()` to return `List.of()`, and `render()` (`:62-71`) never calls it — so `CarrierStrategies`
never runs on the engine-text surface and semantic collection nodes reach the ANSI base's declared
walls (`AnsiSqlRenderer.java:351-354`). `EngineStyleDB2 extends EngineStyleH2` inherits it. The
expansion that would satisfy the goldens **already exists** at `CarrierStrategies.java:729-737`.
Found via `joinStrings`/`STRING_AGG` in `transform/fromPure`, and again via a DB2 `concat` golden in
`tests/query`. Stack-confirmed.

**Overload selection commits on present args and never backtracks.** `Typer.java:1427` calls
`selectByPresentArgs`, which scores through `InferenceKernel.scoreNonLambda` — and that skips
deferred slots outright (`:860-863`). When the deferred lambda then fails its committed slot
(`:589-592`), there is no retry. The kernel *has* the correct rule for already-typed function values
(`:984-999`, whose comment describes exactly this case); it is simply unreachable for a deferred
lambda. Found via `map` in two different families.

**To-one filtered navigation lowers to an unguarded correlated scalar subquery.**
`resolver/Substitution.java:2166-2181` sets `firstRow = true` for `first()`/`head()` but not for
`toOne()`, so no `LIMIT 1` is emitted; `lowering/Lowerer.java:2746-2790` then yields a
`ScalarSubquery` where the engine emits a LEFT JOIN and lets the join fan out. Under a mapping whose
predicate is a tautology this becomes a DuckDB *"more than one row returned"* error on a legal query.
Three tests, two families; the design intent is stated at `resolver/package-info.java:45-56`.

**`CorrelatedSubselects.aggScan` registration gating.** Every registration arm (`:1818`, `:1832`,
`:1859`) and both loud-fallthrough guards (`:1876`, `:1883`) require a non-null path rooted at the
projection row variable. Two shapes escape silently: an aggregate rooted at an *inner* lambda's
parameter, and one whose first hop off the root is to-**one** (`:1818-1821`, `:1859-1860`). The
grouped-subselect builder that would serve them exists and is exactly the golden's shape
(`StoreResolver.java:1886`ff.); it never fires. Downstream, `sum` over a `[0..1]`-stamped leaf
degenerates to the identity (`Scalars.java:1071-1073`) over an exploding join — **46 rows instead of
12**, with smaller values.

**We apply `replaceAliasName` on the `toSQLString` surface where the engine does not.** The engine's
`toSQLString(f, mapping, DatabaseType, extensions)` passes `[]` post-processors
(`transform/fromPure/toSQLString.pure:63-66`), while the runtime-carrying `toSQL` passes
`sqlQueryDefaultPostProcessors()`. legend-lite's `EngineStyleH2.render()` runs `planQuery`
unconditionally (`:62-71`, plan at `:118-181`). Three independent finds. The engine's alias identity
(`personTable_d#4_d_m1`) encodes join-tree path depth and **has zero occurrences in legend-lite** —
it is a structural absence, not a spelling difference.

**Engine-style aliases are derived at render time from the current table name.** `:121-138` computes
the alias group from `t.name()`, and `SqlPostProcessors.source:191-195` rewrites `t.name()` *before*
rendering while preserving the internal alias — so a post-processor's table rename silently renames
the alias too (`persontable_0` → `differentpersontable_0`). The engine fixes alias names at
query-build time; its `fixTables` never touches `TableAlias.name`.

**`orderedDedup` inlines its source twice, once inside a DuckDB lambda.**
`lowering/Scalars.java:2409-2416`. When the source is a scalar subquery, the copy inside the lambda
is illegal (`subqueries in lambda expressions are not supported`). **The hazard is already known and
guarded** for `removeDuplicates` and `sort` at `lowering/ValueCollectionOps.java:70-77` — whose
comment names the exact failure mode — and `distinct` was simply never added to that list.

---

## 6. Oracle artifacts — five distinct kinds, ~30 rows

Not every failure is ours. These are cases where the corpus's expectation, not legend-lite's output,
is the problem.

**(a) Positional row asserts on `ORDER BY`-less queries.** The largest group. The corpus pins
`rows->at(i)` on queries with no ordering; the engine's goldens encode H2's incidental insertion or
nested-loop order, and DuckDB's hash join emits probe-side order.

> **Proven, not inferred.** One investigator re-ran an affected family with `-Drcorpus.backend=h2`:
> the family moved **18/26 → 21/26** and all three ordering tests disappeared from the failure list,
> leaving only the four non-ordering failures. Another verified three more tests by executing the
> goldens' shapes directly against `duckdb_jdbc-1.5.0.0` on the corpus's own seed rows and matching
> legend-lite's `got` **byte-for-byte**.

The harness's documented multiset leniency (`TestBody.java:2774-2788`) cannot rescue these, because
`->at(n)` materializes a single row and there is no multiset to compare.

**(b) Malformed goldens.** Two tests share an expected literal ending `]"` — a stray double-quote
*inside* the string, after the closing bracket. A third is missing a comma between two objects. Our
`sql/Json.java:29` correctly rejects trailing content; Jackson's `readTree` stops after the first
complete value, which is why the typos survived upstream. Refusing to score an unparseable expected
side is the right call.

**(c) A golden the corpus itself annotates as wrong.** `testCheckedWithCircularConstraints` carries
`// toFix: after fixing isDistinct related bug this test should expect:` followed by a commented
block — and that commented block is **exactly our output**. Corroborated by a sibling test in the
java-platform-binding suite asserting the constraint is supposed to be excluded.

**(d) `java.util.HashMap` bucket order as a root-ordering contract.** A union test's golden orders
firms `Firm B, Firm X, Firm A`. Computing default-capacity `HashMap` bucket assignment for those
string keys gives `Firm B → 0`, `Firm X → 6`, `Firm A → 15` — iteration order **Firm B, Firm X,
Firm A**, matching exactly. The same computation explains why the *two*-set siblings pass
(`{Firm X (6), Firm A (15)}` → declaration order). One explanation covering both the failing and the
passing cases, an exact 1-in-6 permutation hit.

**(e) The engine's own pre-post-processor alias scheme** (§5) and, in two milestoning goldens, a
*stale* alias convention (`_d#7_l_d_m2_r`) that differs from almost every other golden in the same
file.

**(f) An engine XStore filter-placement artifact.** In two relation-mapping tests, the corpus
expectation is reproducible only if `filter(e|$e.age < 35)` binds to the **outer** row. Hand-evaluation
of the Pure semantics against the seed reproduces legend-lite's rows exactly.

---

## 7. Harness defects — ~30 rows

Distinct from §1's verdict-labelling problems; these are cases where a valid body is mishandled.

- **`assertSameSQL` does not route plan-text asserts to `PlanAsserts`**, unlike `assertEquals`
  (`TestBody.java:1934-1938` vs the guard at `:1798-1802`). Two tests.
- **`collection->map(x | assert(...))` is not recognized as assertion vocabulary.** The interception
  at `TestBody.java:481-497` matches only when the *statement itself* is an `assert*` call, so the
  `map` is pushed into the pipeline and correctly refused by the resolver.
- **Assert-side splicing pushes the store pipeline into a host lambda.** `checkAssert` β-substitutes
  the lazy `execute` chain into the predicate, so the whole pipeline ends up inside a `forAll`
  lambda body; `StoreResolver.java:498` correctly declines to resolve inside lambda values
  (*"a BARE lambda VALUE is DATA"*), and the `TypedGetAll` escapes. The sibling using the same splice
  under `assertEquals` passes — only the extra lambda wrapper breaks it.
- **`assertSameElements` labels arg0 "expected" and arg1 "got" positionally**
  (`TestBody.java:1853-1863`), so tests written actual-first print **our output under "expected" and
  the corpus golden under "got"**. Materially misleading during triage; a neutral `arg0`/`arg1`
  rendering would be honest.
- **Zero-row CSV rendering is one newline short.** The engine's `toCSV` uses
  `joinStrings('', '\n', '\n')`, which still emits its suffix for zero rows (`header\n` + `\n`).
  `TestBody.java:3126-3127` emits `header\n`. The multiset fallback cannot rescue it because
  `"name,firm\n\n".split("\n",-1)` puts the empty token at a non-final index.
- **`mayExecuteAlloyTest`'s parameterized leg is discarded** whenever the leg references its own
  parameters (`TestBody.java:344-362`), so the test's only assertion never runs.
- **The per-driver golden loop rejects the whole loop** if any pair declares a driver other than
  H2/DB2 (`TestBody.java:2057-2065`) — and the guard's message is stale relative to
  `StatementExecutor.java:364`, which explicitly accepts `Composite`.
- **`Runner.executeMappingRefs` only reads a mapping pointer from argument index 1** of
  `execute`/`from` (`Runner.java:800-845`), so a `withMapping(M)` split form is invisible and the row
  is mislabelled *"no execute(|…) call"* — when there manifestly is one.

---

## 8. Fix-ordering traps found (not yet made)

**8.1 Masking chains.** Several tests fail on a shallow cause that hides a deeper one. Fixing the
shallow cause moves the test to a *different failure*, not to PASS. Confirmed chains:
`columnValueDifferenceTest` (missing date-format constant → hides a store-resolver gap);
`rowValueDifferenceTest` (same → hides the `.columns` defect); `testDecimal` (advisory policy → hides
a missing `toSQLStringPretty`); `testSubAggregationWithDeepAndOverlap_WithColVar` (colspec-as-value →
hides the aggScan gap). This is the same trap `CORPUS_TAXONOMY.md` §2.2 documented for bare-lambda
("fixed as a *message*, not as tests" — 14 of 26 still non-passing behind later walls), now confirmed
as a general property. **All per-cause unlock counts in this document should be read as "rows that
change bucket", not "tests that turn green."**

**8.2 A coupled revert.** Emitting per-view SQL in test-data generation (3 tests) requires
*simultaneously* reverting `ScanRelations.expandView(..., perWebChildren=true)` (`:816-864`), which
was added specifically to make `testSimpleViewRoot` pin 5 SQLs without view SQLs. Fixing one without
the other overshoots.

**8.3 A hollow-pass trap, caught before it was introduced.** `Runner.java:1371-1379` awards an
assert-free PASS only when `executed() > 0`, so a plan-generation smoke test scores SHAPE. The obvious
fix — relax the gate — would score it PASS **without ever generating the plan**, because the 2-arg
`executionPlan(query-with-from, extensions)` form is itself unbuilt
(`StatementExecutor.java:1775-1784`). Build the plan shape first, or manufacture a false green.

---

## 9. Two blockers

**9.1 Our parser cannot read current upstream — the sweep cannot run at all.**

The legend-engine checkout was updated 2026-08-04, pulling `76f83ea3bc3` ("Relation Function Class
Mapping improvements", 2026-07-15) and `8740a0775aa` ("relation mapping emit tests", 2026-07-23),
which introduced `$src.<col>` and `~src` forms into relation-mapping setups. Three files now fail to
parse:

```
tests/mapping/relation/relationMappingSetup.pure:460          expected identifier, got DOLLAR
tests/mapping/union/relation/relationUnionSetup.pure:122      expected identifier, got DOLLAR
tests/mapping/union/relation/relationUnionAdvancedSetup.pure:86  expected identifier, got DOLLAR
```

`Runner.globalModule()` (`Runner.java:1470-1475`) turns any parse wall into a whole-sweep
`IllegalStateException`, so **every test in every family reports ERROR**. Driving the compiled parser
directly isolates it:

```
bare-col   -> walls=[]
src-plain  -> walls=[[7:9]  expected identifier, got DOLLAR]     // age: $src.AGE
src-quoted -> walls=[[7:15] expected identifier, got DOLLAR]     // firstName: $src.'FIRST NAME'
src-arith  -> walls=[[7:9]  expected identifier, got DOLLAR]     // age: $src.AGE + 5
src-concat -> walls=[[6:4]  expected TILDE but found SRC_CMD]    // ~src in a Relation mapping
```

Cause: `parser/MappingGrammarParser.java:613` accepts only a bare identifier for a relation-mapping
column — `String col = p.parseIdentifier();`. This is **not** a `parser/drop-in` regression; the
method is byte-identical on `main`. Investigators worked around it by mirroring the corpus minus
those two directories.

**9.2 There are two legend-engine checkouts, and the harness reads the one you would not expect.**

`Corpus.ENGINE_ROOT` defaults to `/Users/neema/legend/legend-engine` (`Corpus.java:31-37`) — an older
tree — not `/Users/neemsandv/legend/legend-engine` (`d0b4c3a2f68`, 2026-08-04). Measured divergence:

| | |
|---|---|
| corpus `.pure` files | 540 |
| files differing between the trees | **61 (11%)** |
| …of which genuinely modified (not appends) | **59** |
| files present in only one tree | 5 |
| distinct non-passing test names declared in a differing file | **115 of 348 (33%)** |

This is why the sweep is pinned to the older tree — and it interacts with 9.1: updating the corpus
root will not work until the parser accepts the new forms.

---

## 10. Experiments and code written for this study

Everything below is reproducible. Scratchpad root abbreviated `$SP`.

### 10.1 Row extraction and batch partitioning (mine)

```bash
cd /Users/neemsandv/legend/legend-lite
awk '/^- (FAIL|ERROR|SHAPE) /' docs/RELATIONAL_CORPUS.md > $SP/rows.txt      # 356 rows

# verdict tally
awk '/^- (FAIL|ERROR|SHAPE) /{n[$2]++; t++} END {for (k in n) print k, n[k]; print "TOTAL", t}' \
    docs/RELATIONAL_CORPUS.md
#   SHAPE 136 / FAIL 76 / ERROR 144 / TOTAL 356

# family histogram (note: the naive sed is wrong when the error text contains [...] —
# this awk form takes the FIRST bracket group, which is the family)
awk '{ if (match($0, /\[[^]]*\]/)) print substr($0, RSTART+1, RLENGTH-2) }' $SP/rows.txt \
  | sort | uniq -c | sort -rn
```

Partitioning kept families whole where possible; a `pick()` helper emitted one file per batch, then
coverage was verified by set difference:

```bash
cat batches/* | sort > /tmp/cov ; sort rows.txt > /tmp/all
echo "batched: $(wc -l < /tmp/cov)  total: $(wc -l < /tmp/all)  missing: $(comm -23 /tmp/all /tmp/cov | wc -l)"
# batched: 356  total: 356  missing: 0
```

### 10.2 Scoreboard self-consistency check (mine)

```bash
awk -F'|' '/^\| [a-zA-Z]/ && NF>6 && $3+0>0 {t+=$3;p+=$4;f+=$5;e+=$6;s+=$7}
           END {printf "tests=%d pass=%d fail=%d error=%d shape=%d (non-pass=%d)\n",t,p,f,e,s,f+e+s}' \
    docs/RELATIONAL_CORPUS.md
# tests=2538 pass=2182 fail=76 error=144 shape=136 (non-pass=356)
```

Row count and family-table sum agree exactly, and no family has rows the table does not count.

### 10.3 Stereotype census (mine)

```bash
R=<corpus-root>
grep -rhE "^function .*test\.Test" "$R" --include='*.pure' > /tmp/tf
wc -l /tmp/tf                                   # 2697 total <<test.Test>> functions (neemsandv tree)
grep -cF 'test.ToFix'        /tmp/tf            #   50
grep -cF 'test.ExcludeAlloy' /tmp/tf            #   94
grep -cF 'test.AlloyOnly'    /tmp/tf            #  360   (NOT an exclusion)
grep -cE 'test\.(ToFix|ExcludeAlloy)' /tmp/tf   #  144   excluded
grep -rF 'test.Ignore' "$R" --include='*.pure' | wc -l   # 0 — cited by the runner, unused
```

### 10.4 Two-checkout divergence (mine)

```bash
A=/Users/neema/legend/.../core_relational/relational          # what the harness reads
B=/Users/neemsandv/legend/.../core_relational/relational      # what I briefed agents with

diff -rq "$A" "$B" | awk '{print $1}' | sort | uniq -c        # 61 Files, 5 Only

# Is the divergence purely additive (appends, leaving line numbers valid)?
while read -r f; do
  la=$(wc -l < "$A/$f")
  [ "$(head -n "$la" "$B/$f" | md5)" = "$(md5 -q "$A/$f")" ] && add=$((add+1)) || mod=$((mod+1))
done < diff_files.txt
# purely additive: 2   genuinely modified: 59      → line-number citations are NOT safe
```

Then: which non-passing tests are declared in a differing file →
**115 of 348** (`$SP/affected.txt`).

### 10.5 `subsumes` against the PELT sentinel (mine — compiled and run)

From the adjacent PELT investigation, retained because it settles whether legend-engine's graph can
be serialized by legend-pure's element serializer. `SourceInformationHelper.toM3SourceInformation`
never returns null — it fabricates `("X", 0, 0, 0, 0)` — and `ReferenceIdGenerator` decides sub-node
ownership via `subsumes`, which requires **sourceId equality**.

```java
// $SP/Subsume.java — compiled against legend-pure-m4-5.88.0.jar, JDK 21
import org.finos.legend.pure.m4.coreinstance.SourceInformation;
public class Subsume {
  static void t(String label, SourceInformation parent, SourceInformation child) {
    System.out.printf("%-42s -> subsumes=%s%n", label, parent.subsumes(child));
  }
  public static void main(String[] a) {
    SourceInformation real      = new SourceInformation("model.pure", 1, 1, 50, 1);
    SourceInformation sentinel  = new SourceInformation("X", 0, 0, 0, 0);
    SourceInformation realChild = new SourceInformation("model.pure", 5, 3, 5, 20);
    t("real element / real child",         real,     realChild);
    t("real element / SENTINEL child",     real,     sentinel);
    t("SENTINEL element / real child",     sentinel, realChild);
    t("SENTINEL element / SENTINEL child", sentinel, sentinel);
    t("real element / null child",         real,     null);
  }
}
```

```
real element / real child                  -> subsumes=true
real element / SENTINEL child              -> subsumes=false
SENTINEL element / real child              -> subsumes=false
SENTINEL element / SENTINEL child          -> subsumes=true
real element / null child                  -> subsumes=false
```

Combined with a `javap` check confirming the generated constructor signature is
`(String name, SourceInformation, CoreInstance classifier)` and a count of **505+**
`new Root_meta_*_Impl(…, null, …)` sites versus 174 uses of the helper, this establishes that
engine-built graphs would serialize **partially and silently** — the exact false-green shape.

### 10.6 Experiments run by investigators (reproducible)

| Experiment | Command / method | Result |
|---|---|---|
| **Backend-order proof** | `-Drcorpus.backend=h2` on the enumeration family | 18/26 → **21/26**; all three ordering tests vanish. Converts `oracle-artifact` from inference to measurement |
| **Direct DuckDB order probe** | Execute the goldens' SQL shapes against `duckdb_jdbc-1.5.0.0` on the corpus's own seed rows | Row 0 matches legend-lite's `got` byte-for-byte in all three self-join/filter tests |
| **DuckDB timestamp precision** | Probe a `TIMESTAMP(9)` column against a `TIMESTAMP '…'` literal | `coltype=TIMESTAMP_NS`, `col=…123456789`, `lit=…123456`, `eq=false` — explains three FAILs |
| **Three-execution trace** | `LL_TMP_SQL=1` on `testReplaceTablesPostProcessor` | Executions 1–2 replaced, execution 3 (the `.values` re-read) **not** replaced — §2 mechanism 4 |
| **Parser-wall isolation** | Drive the compiled `MappingGrammarParser` on five hand-built relation-mapping forms | Isolates the wall to a bare-identifier-only column RHS (`:613`) — §9.1 |
| **HashMap bucket arithmetic** | Compute default-capacity bucket indices for the golden's string keys | `Firm B→0, Firm X→6, Firm A→15`; explains both the failing 3-set and passing 2-set cases |
| **Cross-product arithmetic** | Hand-evaluate the two filtered join instances against the seed | 2×2 + 1×1 = 5 observed rows, exactly |

---

## 11. Errors made during this study (recorded so they are not repeated)

1. **I briefed 17 of 18 investigators with the wrong corpus root.** The harness reads
   `/Users/neema/legend/legend-engine`; I gave `/Users/neemsandv/…`. Two investigators caught it
   independently. Blast radius measured at §9.2: 115 of 348 tests are declared in a differing file
   and their corpus `file:line` citations need re-verification. **All legend-lite-side analysis is
   unaffected**, which is the bulk of the value. The project memory records a "path caveat" for
   exactly this; I did not check it.
2. **I reported a "~15 hidden tests" discrepancy that did not exist.** It was an artifact of
   measuring the wrong tree with a line-grep that undercounts multi-line declarations. Against the
   correct tree my count is *lower* than the scoreboard, i.e. the method is imprecise, not the
   denominator dishonest. **Retracted** — `CORPUS_TAXONOMY.md` §2.1's "nothing is hiding" claim
   stands.
3. **I twice quoted a stale document as evidence about our own code** — `NAME_RESOLUTION_BUG.md`
   carries a `STATUS: FIXED` banner and a stale body, and I read the body. This is the failure mode
   the project's own audit method explicitly bans. The investigators' brief was amended to forbid it,
   and several of them flagged doc/code disagreements as a result.
4. **A tallying error in §5's `toSQLString` group.** An early summary described the harness's
   synthetic multi-mapping runtime as a "contributor" to the `chainContext` failure. It is not — it
   only removes accidental cover. The defect is entirely in core.

---

## 12. Recommended sequencing

Ordered by evidence, not convenience.

1. **Make the verdicts honest (§1.1).** Five changes — stop swallowing the re-render exception
   (`TestBody.java:984`), let `sqlTextVerify` compare a `toSQLString`-rooted actual directly,
   stop downgrading `NotImplementedException` in the lineage form, make `DialectCapability` catchable
   by the plan-assert arm, and thread the reason through `unsupported(reason)`. None unlocks a test;
   every estimate below depends on them.
2. **The `execute()` fusion (§2).** One architectural cause, five wrong-answer classes. Carry the
   `ExecEnv` on `ExecFrame`, and make a fused property read preserve the object extent's semantics.
3. **The `subType` canonicalization (§3).** ~10 rows, one design point.
4. **The individually-cheap silent wrong answers (§4).** `in()`→`=` (one branch already exists twelve
   lines away); `distinct` added to `ValueCollectionOps`' rewrite list (the hazard is already
   documented there); the `FromChecker` subtype check (`ct.fqn().equals(...)` → a subtype test, for a
   class **we ourselves declare**); timezone threading in `Compiler.dialectOf`; the `CsvSeed` quote
   and truncation bugs.
5. **`EngineStyle*` renderers running their passes (§5).** One override; the expansion already
   exists.
6. **The parser gap (§9.1)**, before anyone updates the corpus checkout — otherwise the sweep stops
   entirely.

After that it is a grind, and it should be described as one: a large residue of genuinely unbuilt
features (router metamodel, `meta::json`, `meta::protocols`, plan-node vocabulary, external formats,
M3 reflection) that are legitimate scope decisions rather than defects, plus a long tail of one-offs.

---

## Appendix A — classification shape

Aggregated from the per-batch tables. Treat as **indicative**: a handful of tests carry two
classifications (e.g. `real-defect` + `oracle-artifact`) and were counted under the primary one.

| Classification | Approx. rows | Character |
|---|---|---|
| `unbuilt-feature` | ~150 | Honest refusals. Many are named walls with self-describing messages; a large share are unported platform surfaces that are legitimate scope decisions |
| `real-defect` | ~110 | We produce a wrong answer, invalid SQL, or refuse something we demonstrably support |
| `out-of-scope` | ~35 | Tests of the engine's own Pure internals — router metamodel, `meta::protocols`, M3 reflection, the forked `legend-h2` build |
| `harness-defect` | ~30 | A valid body the runner mishandles |
| `oracle-artifact` | ~30 | The corpus's expectation, not our output, is the problem |

The `real-defect` and `harness-defect` columns together — roughly 140 rows — are the actionable
correctness work. The `unbuilt-feature` column is roadmap, not debt.

## Appendix B — batch index

| Batch | Families | Rows |
|---|---|---|
| `ep_00`/`ep_01`/`ep_02` | `executionPlan/tests` | 51 |
| `fn_00`/`fn_01` | `functions/tests`, `functions/tests/loadCsvToDbTable` | 32 |
| `b03_projection` | `functions/tests/projection` | 22 |
| `b04_tds` | `tds/tests`, `tds/relation` | 22 |
| `b05_graphfetch` | `graphFetch/{tests,tests/union,domain}` | 22 |
| `b06_transform` | `transform/fromPure/tests` | 16 |
| `b07_milestoning` | `milestoning/tests`, `modelToModelToRelational/milestoned` | 22 |
| `b08_tests_query` | `tests`, `tests/query` | 24 |
| `b09_sqlquery` | `pureToSQLQuery/tests`, `sqlQueryToString*` | 16 |
| `b10_map_a` | `tests/mapping/{inheritance,enumeration,embedded}` | 25 |
| `b11_map_b` | `tests/mapping/{union,modelJoin,association,join}` | 23 |
| `b12_map_c` | `tests/mapping/{classMappingFilterWithInnerJoin,relation,tree,sqlFunction,selfJoin,multigrain,include,filter,…}` | 20 |
| `b13_lineage` | `tests/advanced`, `lineage/{scanRelations,scanColumns}` | 20 |
| `b14_gen_post` | `testDataGeneration/tests`, `postprocessor/tests`, `router/tests` | 22 |
| `b15_misc` | `aggregationAware/…/NOP`, `validation/tests`, `modelJoins`, `tests/{injection,datatype}`, `helperFunctions/tests`, `autogeneration/tests` | 19 |
| | **total** | **356** |

Batch files, extracted rows, the affected-test list and the divergence file lists are in the session
scratchpad (`rows.txt`, `batches/`, `affected.txt`, `diff_files.txt`, `modified_files.txt`).
