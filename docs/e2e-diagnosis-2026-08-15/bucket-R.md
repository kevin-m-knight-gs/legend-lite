# Bucket R — Newly-honest walls (false greens removed after the 2026-08-14 sweep)

7 tests from the ledger; **7 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: MISSING FEATURE 7

---

## `testComplexSubQueries`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same wall, same missing pass. Deepest case: three levels, four CTEs, exercising threaded counters and deepest-first ordering simultaneously.

**Fix**

Covered by the recognizer arm plus Parts 2+3. No case-specific work; this test is the acceptance test for the pass — if 3_1/2_1/2_2/1_1 come out in that order with those names, the ordering and counter rules are right.

**How legend-engine does it** — cteExtractionPostProcessor.pure:47-99 in full; the executed channel that applies it is postProcessor.pure:71-72.

**Risk** — Same alias-numbering risk as testMultipleSubQueries (its golden shows `"tradetable_4"` at cteExtractionPostProcessor.pure:231, another pre-extraction number).

**Also unblocks** — —

**Falsifier** — If the produced WITH list is ordered 1_1, 2_1, 2_2, 3_1 (parent-first), the append order in Part 3 is inverted.

<details><summary>Evidence read (3 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- cteExtractionPostProcessor.pure:228-232 — golden order is 3_1, 2_1, 2_2, 1_1, exactly the recurse-then-append fold order
- core/src/main/java/com/legend/sql/SqlQuery.java:11 — no CTE variant to express any of it

</details>

---

## `testCorrelatedSubQueryIsolationStrategy`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

Same wall, same missing pass. The subselect here comes from the correlated-subquery isolation strategy behind a qualified property (`employeeByLastName('Smith')`), not from limit() or a view — a third producer of the same Subselect node.

**Fix**

Covered by the recognizer arm plus Parts 2+3. Explicitly: CteExtraction must key ONLY on the node type SqlSource.Subselect (any frameName, including null and including SqlSource.Subselect.EXISTS_KEYS_FRAME, SqlSource.java:87) — never on frameName — so view frames, isolation frames and correlated-isolation frames all hoist identically.

**How legend-engine does it** — postProcessor.pure:66-83 (postProcessQuery) — whatever produced the SelectSQLQuery, the post-processor sees one uniform metamodel; the extraction is producer-agnostic. lite's pass must be equally producer-agnostic — it keys on SqlSource.Subselect, never on how the subselect arose.

**Risk** — The correlated case is the one where lite's join-isolation lowering is most likely to differ structurally from the engine's (the golden's subselect projects FIRMID and FIRSTNAME and is joined on FIRMID); if lite emits a lateral/exists form instead of a plain Subselect, the CTE names will be right but the body text will not.

**Also unblocks** — —

**Falsifier** — Dump lite's pre-post-processing IR for this query. If the isolated correlated branch is a SqlSource.Join with a LEFT_LATERAL kind or an SqlExpr.Exists rather than a SqlSource.Subselect, this test needs an isolation-lowering fix first and is not covered by Parts 2+3 alone.

<details><summary>Evidence read (3 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- cteExtractionPostProcessor.pure:242 — `$x.firm.employeeByLastName('Smith').firstName`, the qualified property that forces the isolated subselect
- cteExtractionPostProcessor.pure:246-247 — golden: `with subquery_cte_1_1 as (select "persontable_2".FIRMID ... where "persontable_2".LASTNAME = 'Smith') select ... left outer join subquery_cte_1_1 as "persontable_1" on (...)`

</details>

---

## `testDeepSubQueries`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same wall, same missing pass. Two nested levels: the pass must number by DEPTH and emit deepest-first.

**Fix**

Covered by Part 3 as specified: recurse-then-name, and append the child's list before the node's own CTE. No extra work beyond the shared parts.

**How legend-engine does it** — cteExtractionPostProcessor.pure:86-94 — name is `'subquery_cte_' + toString($level) + '_' + toString($agg.levelIndexMap->get($level)->toOne() + 1)`, and the recursion `->extractSubqueriesAsCTEsRecursively($level + 1)` runs BEFORE the parent's own CTE is built.

**Risk** — Depth accounting: level 1 must mean the ROOT select's direct subselects (not the root itself) — off-by-one here renames every CTE and fails all six text asserts at once.

**Also unblocks** — —

**Falsifier** — If a correct implementation emits `subquery_cte_1_1` for the innermost (top-10) select rather than the outermost, the level base is off by one.

<details><summary>Evidence read (3 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- cteExtractionPostProcessor.pure:186-188 — golden emits `subquery_cte_2_1` BEFORE `subquery_cte_1_1`, proving child CTEs precede the parent's own in the WITH list
- cteExtractionPostProcessor.pure:93 — `extractedCTEs = $processedCteResult.extractedCTEs->concatenate($cte)` — the concatenation order that produces it

</details>

---

## `testMultipleSubQueries`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same wall, same missing pass. Two SIBLING level-2 subselects under a join — this is the case that pins the per-level counter as THREADED state, not a per-subtree local.

**Fix**

Covered by Part 3: carry the per-level counter map in the accumulator threaded left-to-right across siblings (Join left before right), never reset per subtree.

**How legend-engine does it** — cteExtractionPostProcessor.pure:83-95 — the whole rewrite is a fold over the sibling nodes whose accumulator carries currentSelect, extractedCTEs AND levelIndexMap together.

**Risk** — This golden also shows the alias-numbering GAP (`"tradetable_1"` then `"tradetable_3"`, cteExtractionPostProcessor.pure:209): the engine's group-alias planner numbered the tree as if the hoisted bodies were still in place. EngineStyleH2's planQuery (EngineStyleH2.java:226) must therefore plan aliases over the PRE-extraction tree (or number CTE bodies in their original positions) — plan after extraction and the numbering closes the gap and the text diverges.

**Also unblocks** — —

**Falsifier** — Implement the pass, then check whether the rendered aliases are tradetable_0/1/3 (pre-extraction numbering, matches) or tradetable_0/1/2 (post-extraction numbering, diverges). The latter proves alias planning must move ahead of the post-processor.

<details><summary>Evidence read (3 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- cteExtractionPostProcessor.pure:207-210 — golden emits `subquery_cte_2_1` and `subquery_cte_2_2` for the two join branches, then `subquery_cte_1_1`
- cteExtractionPostProcessor.pure:94 — `levelIndexMap = $processedCteResult.levelIndexMap->put($level, ... + 1)` threaded through the fold accumulator, which is why the second branch gets _2

</details>

---

## `testNoSubQueries`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The recognizer has no arm for the cteExtraction hook shape, so the query refuses before lowering. For THIS test the extraction is provably a no-op — the golden has no `with` clause at all, and the engine's own pass short-circuits when `subQueryTotalCount == 0` (cteExtractionPostProcessor.pure:52-53). So the only thing standing between this test and a pass is recognizing the hook.

**Fix**

Add arm B to SqlPostProcessors.readHook (SqlPostProcessors.java:89): body is a single TypedNewInstance of `meta::pure::mapping::Result` whose only property `values` is a TypedNativeCall to `meta::relational::postProcessor::cteExtraction::extractSubqueriesAsCTEs` whose single arg is the lambda's own parameter -> yield the rewrite `CteExtraction::extract`. To carry a rewrite that is not a rename, generalize the channel: `tableReplaceMap(TypedSpec)` (SqlPostProcessors.java:43) becomes `List<UnaryOperator<SqlQuery>> hooks(TypedSpec)`; readHook returns a rewrite instead of mutating a Map (arm A = the existing replaceTables pattern, yielding `q -> apply(q, map)`); ExecEnv's `Map<String,String> tableReplace` (StatementExecutor.java:58) and PostProcessBoundary.record (PostProcessBoundary.java:27) carry the list; the two application sites (StatementExecutor.java:3206-3208 exec, :390-392 toSQLString) fold the list over the plan in declaration order. KEEP the throw for unrecognized shapes — but fix its text: pass the slot name in from the two call sites (SqlPostProcessors.java:55 and :74) so it names `sqlQueryPostProcessors` when that is the slot that carried the hook; today it always says `sqlQueryPostProcessorsConnectionAware`, which is wrong for all 7 of these (they come from the PLAIN slot) and sent this investigation to the wrong property name. With arm B in place, `CteExtraction.extract` returns its argument unchanged when the FROM tree holds no SqlSource.Subselect, and this test passes byte-exact with no IR change at all.

**How legend-engine does it** — core_relational/relational/postprocessor/postProcessor.pure:71-72 — the execution channel folds the slot generically: `$postProcessors1->fold({pp,q|$pp->eval($q).values->toOne()}, $postProcesedQuery->cast(@SelectSQLQuery))`. The engine evals an arbitrary Pure lambda over its own SelectSQLQuery metamodel; it never pattern-matches hook shapes. legend-lite's shape recognizer is a deliberate substitute for that eval, so every corpus hook shape must earn an explicit arm.

**Risk** — Low. The identity path touches no IR. Only risk is that lite's CTE-free rendering of this query was never actually byte-exact and was passing on the row upgrade even for the non-CTE portion — see the falsifier.

**Also unblocks** — The channel generalization (rename-map -> rewrite list) and the slot-name message fix are shared by all 7 and by every future hook shape.

**Falsifier** — Set the recognizer to yield an identity rewrite for this hook and run only testNoSubQueries with LL_TMP_SQL. If the rendered SQL byte-matches cteExtractionPostProcessor.pure:150, the diagnosis holds and this test is S-effort. If it does not match, the wall is masking a SECOND, unrelated text divergence and this test is not S-effort.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — readHook throws NotImplementedException for any lambda whose terminal body is not a 2-arg `meta::relational::postProcessor::replaceTables` call
- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:61-76 — the plain `sqlQueryPostProcessors` slot now calls readHook unguarded (the catch-and-skip removed by 6ddae338)
- core/src/main/java/com/legend/StatementExecutor.java:2214-2221 — tableReplaceMap(rtArg) is invoked for every execute() with >=3 args, so the throw happens at setup
- cteExtractionPostProcessor.pure:139 — the hook literal that the message echoes
- cteExtractionPostProcessor.pure:150 — the golden, CTE-free: `select "root".ID as "TradeID", "root".quantity as "Quantity" from tradeTable as "root" where "root".ID = 100`
- cteExtractionPostProcessor.pure:52-53 — `if ($subQueryTotalCount == 0, | $select, ...)`

</details>

---

## `testSingleSubQueryFromOperations`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same wall, same missing pass. One level-1 CTE, this time from a limit()-induced isolation subselect rather than a view.

**Fix**

No additional change beyond the recognizer arm and Parts 2+3 above. The limit lives inside the hoisted SqlSelect, so `SqlWith` must render its CTE bodies through the same dialect path as any query — i.e. the new arm in EngineStyleH2.render (EngineStyleH2.java:219-230) renders each Cte body with the existing `query(sb, ...)` recursion so `top 10` keeps its H2 spelling.

**How legend-engine does it** — postProcessor.pure:71-72 (generic fold of the plain slot) plus cteExtractionPostProcessor.pure:82-92 (the node-replacement that preserves the alias).

**Risk** — SubselectPrune (core/src/main/java/com/legend/lowering/SubselectPrune.java) may already collapse a limit-isolation subselect that the engine keeps; if it does, lite has no node to hoist and the golden is unreachable without also making the prune post-processor-aware.

**Also unblocks** — —

**Falsifier** — Dump the pre-post-processing IR: if the FROM is a bare SqlSource.Table rather than a Subselect wrapping the top-10 select, SubselectPrune has already flattened it and the fix needs a prune exclusion as well.

<details><summary>Evidence read (3 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- cteExtractionPostProcessor.pure:170 — `->limit(10)->filter(...)` is what forces the isolation subselect
- cteExtractionPostProcessor.pure:173-174 — golden: `with subquery_cte_1_1 as (select top 10 ...) select ... from subquery_cte_1_1 as "subselect"` — H2 `top n` spelling inside the CTE body, and the original `"subselect"` alias preserved at the reference

</details>

---

## `testSingleSubQueryFromView`

| | |
|---|---|
| family | `postprocessor` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same wall. Beyond recognition, this one needs the real pass: the view-backed derived table must be hoisted into `subquery_cte_1_1` while its outer reference keeps the original alias. legend-lite has no CTE node in its SQL IR to hoist into.

**Fix**

Parts 2+3 on top of the recognizer arm. Part 2 (IR): add `record SqlWith(List<Cte> ctes, SqlQuery body) implements SqlQuery` with `record Cte(String name, SqlQuery query)`, extend the permits clause at SqlQuery.java:11, and add `record CteRef(String name, String alias, List<OutputCol> outputs) implements SqlSource` to SqlSource so a table rename can never hit a CTE reference — this mirrors the engine's explicit `c:CommonTableExpressionReference[1] | $c` arm in fixTables (postProcessor.pure:351). Add arms in the seven files that switch over SqlQuery/SqlSource: SqlPostProcessors.apply/source (SqlPostProcessors.java:192-251 — note `source()` is deliberately default-less/total, so the compiler will name every site), SubselectPrune, UnionSerialOrder, ScanColumns, SqlRewriter, AnsiSqlRenderer (render at :82, union arm at :115), EngineStyleH2 (render at :219-230, union arm at :311). Part 3 (pass): new `com.legend.lowering.CteExtraction.extract(SqlQuery)`, a straight transcription of cteExtractionPostProcessor.pure:47-99 — non-SqlSelect or no Subselect in the FROM tree returns the argument unchanged; otherwise walk the FROM tree (Join left then right = the childrenData walk) with level starting at 1 for the root select's direct subselects; at each Subselect recurse into its inner query FIRST, then emit `subquery_cte_<level>_<n>` where n comes from a per-level counter THREADED across sibling subtrees (.pure:94), append the child's CTEs before the node's own (.pure:93), and replace the node with `SqlSource.CteRef(name, node.alias(), node.outputs())`. Render as `with <name> as (<query>)[, <name> as (<query>)] <body>` — lowercase, comma-space separated, one space before the body.

**How legend-engine does it** — cteExtractionPostProcessor.pure:78-99 (extractSubqueriesAsCTEsRecursively) — for each join-tree node whose `alias.relationalElement` is a SelectSQLQuery, recurse first, name `'subquery_cte_' + level + '_' + (index+1)`, then `replaceTreeNode` the node with `^$node(alias = ^$nodeAlias(relationalElement = $cteReference))`, i.e. the ALIAS survives and only the relationalElement is swapped for a CommonTableExpressionReference.

**Risk** — Byte-exactness, not correctness. EngineStyleH2.render plans group aliases up front (planQuery at EngineStyleH2.java:226 over the `renames`/`subselects` maps at :215-217); if that planning runs on the POST-extraction tree the alias numbering will drift from the goldens. If it drifts, sqlTextVerify falls through to the H2 row-replay (EngineTestExecutor.java:1007-1013 -> :1076) and the test goes green on rows that are invariant by construction — re-creating the exact false green 6ddae338 was written to kill. The fix must be accepted only on byte-exact text.

**Also unblocks** — Parts 2+3 are shared by the remaining 6.

**Falsifier** — Dump lite's IR for this query before any post-processing. If the view is NOT lowered as a SqlSource.Subselect in the FROM/join tree (e.g. it is inlined or pruned by SubselectPrune), there is nothing for the pass to hoist and the fix as written cannot produce the golden.

<details><summary>Evidence read (4 citations)</summary>

- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 — the refusing recognizer
- core/src/main/java/com/legend/sql/SqlQuery.java:11 — `sealed interface SqlQuery permits SqlSelect, SqlUnion` — no CTE variant exists
- core/src/main/java/com/legend/sql/SqlSource.java:77-97 — Subselect(inner, alias, frameName); a view frame carries its model name here, and this is the node the pass must hoist
- cteExtractionPostProcessor.pure:160-161 — golden: `with subquery_cte_1_1 as (select ... from tradeEventTable ...) select ... left outer join subquery_cte_1_1 as "tradeeventviewmaxtradeeventdate_0" on (...)` — the hoisted body keeps the view's original alias at the reference site

</details>

---
