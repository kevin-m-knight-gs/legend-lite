# Bucket 4 — SQL-text golden is the whole contract

35 tests from the ledger; **35 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: GOLDEN TEXT ONLY 14, REAL DEFECT 13, MISSING FEATURE 5, HARNESS GAP 2, TESTS ENGINE INTERNALS 1

---

## `testBuildFilterWithValueThatCanBeNullPlanSql`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | S |
| confidence | high |

**Root cause**

The assert is `assertEquals(expectedSqlForValueThatCanBeNull('is null'), $result->sqlRemoveFormatting())` — the GOLDEN side is a call to a corpus-private String function, not a literal. `EngineTestExecutor.sqlTextVerify` extracts the golden with `TestDataGenForm.foldString`, which folds ONLY `CString` and `plus`-of-foldables and returns null for anything else (TestDataGenForm.java:477-494). So golden==null; the `args.size()==2` no-golden branch calls `sideSqlText(args.get(0),…)` which finds no execute/toSQLString terminal in the helper call and returns null; control falls to `return h2Upgrade(...)`, which early-outs with `h2Decline("no foldable golden string")` and returns ADVISORY_MARKER (EngineTestExecutor.java:1062-1068). The assert is therefore counted advisory, verified stays 0, and `Runner.score` emits Status.SHAPE `sql-only: 1 advisory golden-SQL assert(s), no row verification` (Runner.java:1456-1465). Nothing in the platform is wrong — the harness simply never evaluates the golden expression.

**Fix**

In `EngineTestExecutor.sqlTextVerify` (core/src/main/java/com/legend/harness/EngineTestExecutor.java, the golden/actual split loop at ~line 958-966) and in the identical loop inside `h2Upgrade` (~line 1053-1061), replace the bare `TestDataGenForm.foldString(subst(a, lets))` with a small helper `goldenStringOf(a, …)` that (1) tries `foldString`, and (2) if that is null AND `!containsSqlText(a)`, falls back to the platform evaluator already available here: `evalScalar(a, lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn) instanceof String s ? s : null`. The `!containsSqlText(a)` guard is load-bearing: it keeps the `$result->sqlRemoveFormatting()` side out of the golden slot. Factor the helper once and call it from both loops so the two stay in step. After this, the test resolves honestly: identical text -> M1 H2 row check; divergent text -> `h2Upgrade` row verification, and only if that is unverifiable does it report `sql-text: expected …, got …`.

**How legend-engine does it** — Not a semantics question — the engine's Pure interpreter simply evaluates the argument expression. The relevant engine fact is only that `expectedSqlForValueThatCanBeNull` is an ordinary `access.private` String function (core_relational/relational/functions/tests/testFilters.pure:222-225).

**Risk** — Routing an arbitrary golden-side argument through `evalScalar` can now throw where it previously fell back to advisory. Keep the call inside the existing try/catch that already declines with `h2Decline("replay/verify failed: …")` so a non-evaluable golden degrades to today's behaviour rather than turning into a spurious ERROR. Tenet-2 note: this is NOT harness compensation — the harness is the Pure test-body evaluator and it is delegating to the platform's own evaluator, not hand-rolling the value or papering over a platform shape. Do NOT instead teach `foldString` to inline user function bodies; that would be re-implementing the platform's inliner inside the harness.

**Also unblocks** — None confirmed. The other four `advisory golden-SQL` tests in the sweep have different causes — e.g. testViewChainsWithBusinessDate's golden IS a literal; it declines because its `actual` is a plain String let with no root exec variable (`no root exec variable in the actual arg`, EngineTestExecutor.java:1064).

**Falsifier** — Print the value of `golden` at EngineTestExecutor.java:961 for this test. If it is already non-null (i.e. `foldString` somehow folds the helper call), this diagnosis is wrong and the decline is coming from `rootExecVar` instead.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/TestDataGenForm.java:477 — `foldString` handles only `CString` and `plus`; every other node returns null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:961 — `String s = TestDataGenForm.foldString(subst(a, lets)); if (s != null && golden == null) golden = s; else actual = a;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1064 — `if (golden == null || var == null) { h2Decline(golden == null ? "no foldable golden string" : …); return ADVISORY_MARKER; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1461 — `if (r.verified() == 0 && r.advisory() > 0) yield new Outcome(fqn, Status.SHAPE, "sql-only: " + r.advisory() + …)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1843 — the assertEquals arm routes to `sqlTextVerify` whenever either side `containsSqlText`
- corpus testFilters.pure:217-226 — the golden is `expectedSqlForValueThatCanBeNull('is null')`, whose body is a single string-plus over the parameter

</details>

---

## `testGroupByWithJoinDB2`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | FAIL |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The ONLY difference between expected and got is the table-alias spelling: expected `personTable_d#4_d_m1`, got `persontable_0`. Every other token — projections, join, `group by "root".LEGALNAME,"…".FIRSTNAME` (DB2 groups by column expression, not by output alias) — matches exactly. `personTable_d#4_d_m1` is pureToSqlQuery's RAW internal alias, built from the JoinTreeNode id (`d#4`) and merge counters (`_d_m1`). It survives into the golden only because `toSQLString(f, mapping, databaseType, extensions)` passes an EMPTY post-processor list (toSQLString.pure:65), whereas the `execute`/runtime path runs `meta::relational::mapping::sqlQueryDefaultPostProcessors()` which includes `meta::relational::postProcessor::reAlias::replaceAliasName` — the pass that rewrites every alias to `<lowercased table>_<index>` (reAliasQuery.pure:26-40). legend-lite has no pre-reAlias alias layer at all: `Lowerer.nextAlias()` mints `t0/t1/…` and `EngineStyleH2`'s alias planner emits the POST-reAlias names directly. Matching this golden would require reproducing pureToSqlQuery's join-tree node numbering, which the codebase already declares an engine internal and strips on both sides in the lineage comparator (ScanRelations.java:937-944).

**Fix**

Do not fix — ledger it. The only way to pass is to introduce a second, pre-reAlias alias namespace that replicates pureToSqlQuery's JoinTreeNode ids and merge suffixes (`_d#N`, `_d_mN`, `_md`), then run legend-lite's existing alias planner as a post-pass gated on which toSQLString overload was called. That reproduces a data structure legend-lite does not have and does not need, purely to match a text the engine itself normalises away before execution. If a wall is wanted instead of a diff, the honest place is `StatementExecutor.toSqlString` (StatementExecutor.java:359-396): it could record that the requested overload is the post-processor-free one and note in the diff that aliases are post-reAlias. Recommended action: mark this test permanently expected-diff, alias-only.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:26-40 (replaceAliasName) together with transform/fromPure/toSQLString.pure:49 vs :65 — the exact reason the two goldens for the same query differ only in alias spelling.

**Risk** — If someone 'fixes' this by teaching EngineStyleDB2 to emit `_d#N` names, every other DB2/H2 golden in the corpus (which all expect post-reAlias `<table>_<n>`) breaks at once. The alias scheme is global; it must not be changed per-test.

**Falsifier** — Diff the expected and got strings token-by-token with all `"[A-Za-z]+_[^"]*"` alias tokens masked. If anything other than alias tokens differs, there is a second, real defect hiding behind the alias noise and this verdict is wrong.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:26 — `replaceAliasName(query, dbConnection, exeCtx, extensions)`; line 35 builds `$x.first+'_'+$x.second->toString()` from the LOWERCASED table name and a per-group index
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:64 — `replaceAliasName_…` is a member of `sqlQueryDefaultPostProcessors()`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/toSQLString.pure:65 — the DatabaseType overload calls `toSQLString($f,$mapping,$databaseType,[],[],^Format(...),$extensions,noDebug())` — the 5th arg (post-processors) is `[]`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/toSQLString.pure:49 — the runtime overload builds `$postProcessors` from `sqlQueryDefaultPostProcessors()` and passes them in
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/testAssert.pure:23-25 — `assertSameSQL(sqlString, result:String[1])` is a bare `assertEquals`; it performs NO alias normalisation, so the sibling H2 test's `persontable_0` really is the engine's post-reAlias output
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:283 — `private String nextAlias() { return "t" + aliasCounter++; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:281-297 and :394-397 — the alias planner IS `replaceAliasName` parity: `nextInGroup` returns `group + "_" + i` over lowercased table names
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:937-944 — "its alias BREADCRUMBS (`_d#2_m1`…) are pureToSqlQuery's internal counters and are stripped on BOTH sides at compare time"

</details>

---

## `testMostRecentDayOfWeek`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

This test calls `toSQLString($query, simpleRelationalMapping, DatabaseType.H2, …)` — the contract is literal H2 text. legend-lite hard-codes ONE day-of-week formula in the IR and never dialect-adapts it. `DateShifts.dayOfWeekShift` builds `EXTRACT('isodow', anchor)` with ISO day numbers (Monday=1) from `isoDayNumber` (DateShifts.java:22-60), and `EngineStyleH2` only special-cases `EXTRACT` for the part name `"doy"` (EngineStyleH2.java:1491-1495); everything else falls through to the generic spelling `date_part` (Spellings.java:70). Result under the H2 renderer: `dateadd(day, case when 1 - date_part('isodow', cast(now() as date)) > 0 then … end, cast(now() as date))`. The engine's H2 model is `date_part('dow', …)` with `mapToDBDayOfWeekNumber` (Monday=2 … Sunday=1), printed as `extract(dow from …)` with the arithmetic operand parenthesised inside the WHEN and the THEN. Both formulas are semantically correct for THEIR target (DuckDB `isodow` Monday=1; H2 `dow` Sunday=1), which is why the sibling `execute` test testPreviousDayOfWeek — same formula, different comparator — is NOT in the failing set: it row-verifies through h2Upgrade and the text diff stays advisory. Only the pure-text `toSQLString` surface exposes it. The emitted text is not valid H2 (`date_part` is not an H2 function), so this is a renderer defect, not cosmetics — but rows on DuckDB are unaffected.

**Fix**

Keep the shift SEMANTIC in the IR and expand per dialect, exactly the pattern already used for `DATE_TRUNC` (EngineStyleH2.java:1498-1509 expands it while DuckDb renders the plain call). Concretely: (1) add `SqlFn.DOW_ANCHORED_SHIFT` taking (dayName StringLit, strict BoolLit, anchor); (2) `DateShifts.dayOfWeekShift` (core/src/main/java/com/legend/lowering/DateShifts.java:40-60) returns that single call instead of the expanded CASE, defaulting `anchor` to `TODAY` as today; (3) `DuckDb`/`AnsiSqlRenderer` expand it to the CURRENT isodow form (Monday=1, `date_part('isodow', …)`) so DuckDB rows are unchanged; (4) `EngineStyleH2` expands it to the engine's form: day number from a Monday=2…Sunday=1 table mirroring `mapToDBDayOfWeekNumber`, part name `dow`, printed as `extract(dow from <anchor>)`, wrapped as `dateadd(day, case when (<N> - extract(dow from <a>)) <op> 0 then (<N> - extract(dow from <a>)) - 7 else <N> - extract(dow from <a>) end, <a>)` — note the parentheses appear around the subtraction in the WHEN operand and in the THEN left operand but NOT in the ELSE; match that literally. `<op>` is `>` for mostRecent and `>=` for previous (toPostgresModel.pure:432-433).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:583

**Risk** — testPreviousDayOfWeek, testMostRecentDayOfWeekWithDate and testPreviousDayOfWeekWithDate currently PASS by row verification with an advisory text diff; if the DuckDB expansion is accidentally changed to the `dow` numbering their ROWS go wrong (DuckDB `dow` is Sunday=0, not Sunday=1 — the engine's table is H2's convention, not Postgres'). Keep the DuckDB arm byte-identical to today's output and change only the EngineStyleH2 arm.

**Also unblocks** — Should also close the advisory text diffs on testPreviousDayOfWeek and the 2-arg `…WithDate` variants in functions/tests/projection/testDateFilters.pure (they pass today, but with `1 advisory sql diff`).

**Falsifier** — Render the same query through `toSQLString(..., DatabaseType.H2, ...)` after changing ONLY `isoDayNumber` to the engine table and `"isodow"` to `"dow"`. If the text still differs anywhere other than `date_part(...)` vs `extract(... from ...)` and the parenthesisation, the diagnosis is incomplete.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/DateShifts.java:47-59 — `int t = isoDayNumber(dowName); … new SqlExpr.Call(SqlFn.EXTRACT, List.of(new SqlExpr.StringLit("isodow"), anchor))` then `ADD_INTERVAL_TEMPORAL("to_days", shifted, anchor)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/DateShifts.java:23-34 — `isoDayNumber`: Monday->1 … Sunday->7
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:483-491 — the only registration site: `mostRecentDayOfWeek`->false, `previousDayOfWeek`->true, both delegating to `DateShifts.dayOfWeekShift`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1491-1495 — `case EXTRACT -> a.size()==2 && a.get(0) instanceof StringLit part && "doy".equals(part.value()) ? "extract(doy from "+… : super.call(...)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/Spellings.java:70 — `m.put(SqlFn.EXTRACT, "date_part")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:369-378 — `case "H2" -> new EngineStyleH2(); case "DB2" -> new EngineStyleDB2();` — the DatabaseType really does select the engine-style renderer
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:568-578 — `mapToDBDayOfWeekNumber`: Monday=2, Tuesday=3 … Saturday=7, Sunday=1
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:583-585 — `recentDayOfWeek` builds `functionCall('dateadd',[$dateVal, ^SearchedCaseExpression(… ArithmeticExpression(SUBTRACT, $recentDayInt, functionCall('date_part',[literal('dow'), $dateVal])) …), ^StringLiteral('DAY')])`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlDialectTranslation/toPostgresModel.pure:432-433 — `mostRecentDayOfWeek` -> `recentDayOfWeek(castExpression(functionCall('now',[]),'date'), $p->at(0), GREATER_THAN)`; `previousDayOfWeek` -> the same with GREATER_THAN_OR_EQUAL

</details>

---

## `testVariableReferenceWithNestedFilterMultiple`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Cardinality bug in the correlated-filter isolation subselect: it is keyed and joined back on the ASSOCIATION's parent-side equi columns instead of the parent extent's PRIMARY KEY. `CorrelatedSubselects.explodingSubselect` computes `keyCols = parentEquiKeys(aj.condition(), aj.prefix())` (CorrelatedSubselects.java:285) — for `Person.firm` the association condition is `firmTable.ID = personTable.FIRMID`, so the parent-side key is FIRMID. The subselect then projects those keys as `_pk0…` and the join back is `pkEqualityCond(keyCols, subKeys, …)` (line 362) — i.e. `outer.FIRMID = sub._pk0`. FIRMID is not unique on Person, so every outer Person row matches EVERY parent-copy row of the same firm that satisfies the predicate. Arithmetic check against the observed counts: Firm X has 4 employees, of whom 3 (Johnson, Hill, Allen) satisfy `$f.address.name == $p.address.name` (Firm X's address is New York and those three live in New York). 4 x 3 = 12, plus Roberts/O.Hill/Harris contributing 1 null row each = 15 — exactly the 15 rows observed, with Allen appearing 3 times. The engine instead copies the ROOT tree and re-joins on the root's own key: its golden reads `… ) as "persontable_3" on ("persontable_2".ID = "persontable_3".ID)` and `… ) as "persontable_1" on ("root".ID = "persontable_1".ID)` — personTable.ID, the PK, at both isolation levels, giving 7 rows.

**Fix**

In `CorrelatedSubselects.explodingSubselect` (core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:273-364), stop deriving the isolation key from the association condition. Take the PARENT EXTENT's identity columns instead: resolve the parent `ClassSource cs`'s primary-key columns (the class mapping's `~primaryKey`, falling back to the main table's declared PRIMARY KEY — the same source `ViewFrames`/`ClassSources` already read the mapping from) and use those as `keyCols`; project them as `_pk<i>` out of the parent copy exactly as today, and build the join-back with `pkEqualityCond` over those PK columns. The association condition stays INSIDE the subselect (it already does, at line 304-306) so the target still explodes per matching target instance — only the correlation back to the outer row becomes 1:1. Apply the same change to the filter-position parent-copy branch of `corrAggSubSource` (line ~2043-2055, `if (corrAgg == null) return new CorrAggSub(joinedSub, keyCols, pcRow, corrTp, corrRowVar, corrJoinedRow, pc);`) which reuses `parentEquiKeys` the same way. If the parent class has no resolvable PK, throw a loud NotImplementedException naming the class rather than silently keeping the association key — a loud wall beats 15 rows where 7 are right.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7584-7590 — `buildCorrelatedSubQuery` is the strategy that "copies the root tree" into the isolation subquery; the corresponding golden text (testFunctionVariables.pure:143) shows the copy re-joined on the root table's PK, not on the association key.

**Risk** — Any currently-passing test whose parent-side association key HAPPENS to be the parent PK (e.g. `Firm.employees`, cond `firmTable.ID = personTable.FIRMID`, parent key = ID = Firm's PK) is unaffected — the columns are literally the same. The exposure is classes whose PK is composite or whose mapping declares `~primaryKey` over non-main-table columns; make the PK lookup go through the same mapping-closure walk `ViewFrames.frameNameOf` uses (ViewFrames.java:34-48) so included mappings are honoured. Tenet-2 trap: do NOT 'fix' this by adding a DISTINCT to the outer projection — that would hide the explosion and change semantics for legitimately to-many shapes.

**Also unblocks** — Plausibly testTwoQualifiersUsingSameJoinWithNoUserParams (functions/tests/projection, `assertSize: expected 1, got 4` — the same over-explosion signature) and testIsolatioWhereNoConstaintsAndInnerJoin (duplicate-row list mismatch). Not verified — check them after the fix.

**Falsifier** — Dump the rendered SQL for this query and look at the join-back predicate of the isolation frame. If it already reads `root.ID = <sub>._pk0` (person PK) rather than `root.FIRMID = <sub>._pk0`, this diagnosis is wrong and the duplication comes from somewhere else. The 4x3+3=15 arithmetic is the corroborating check: it should collapse to 7 the moment the key becomes the person PK.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:285-286 — `List<String> keyCols = parentEquiKeys(aj.condition(), aj.prefix());`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:330-349 — each keyCol is projected out of the parent copy as `_pk<i>`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:361-363 — `return new ExplodingSub(subPipe, subRowX, assocMaterial.pkEqualityCond(keyCols, subKeys, leftRowT, subRowX));` — the join-back is on those same association keys
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:395-405 — `parentEquiKeys` collects equi keys from the ASSOCIATION condition (`cond.parameters().get(1)` vs `get(0)`), never from the class's primary key
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1946-1953 — `if (aj.corrSubPred() != null) { … corrSubs.explodingSubselect(cs, aj, …) }` — this is the path a correlated nav filter takes
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:275-284 — the doc comment states the intent: "One row per matching TARGET instance"; keying by FIRMID makes it one row per matching PARENT-COPY row instead
- corpus functions/tests/projection/testFunctionVariables.pure:138-146 — the golden's two isolation frames both join back on `ID` (`"persontable_2".ID = "persontable_3".ID` and `"root".ID = "persontable_1".ID`), never on FIRMID

</details>

---

## `testDateFunctionInMilestonedProperty`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XS |
| confidence | high |

**Root cause**

The two SQLs are row-equivalent; the whole diff is spelling.
(i) ALIASES. The golden comes from `toSQLString($query, milestoningmap, DatabaseType.H2, extensions)` — the one overload that passes `[]` for sqlQueryPostProcessors, so `replaceAliasName` (the reAlias default post-processor) never runs and the raw `<table><routerNodeId>` aliases survive: `StockProductTable_d#7_d_m2`, `ProductTable_d#7_l_d_m2_r`. legend-lite's EngineStyleH2 unconditionally plans the POST-PROCESSED spelling `stockproducttable_0` / `producttable_0`. Those node ids are legend-engine router internals.
(ii) JOIN SHAPE. The golden wraps the ProductTable hop in a derived table carrying the milestoning predicate — `left outer join (select id, name from ProductTable as "…" where from_z <= DATE'2015-01-01' and thru_z > DATE'2015-01-01') as R on (R.id = SP.id)`. legend-lite emits the same predicate in the ON clause of a LEFT OUTER JOIN against the bare table. For a LEFT OUTER JOIN, restricting the right relation before the join and restricting it in the ON clause are identical: the same right rows qualify, and unmatched left rows still produce NULLs. Same rows, same column values.
The SEMANTICS legend-lite gets right and that the test was written to check: the explicit `constantDate()` argument on `$o.stockProduct(constantDate())` correctly reaches BOTH milestoned tables as DATE'2015-01-01' (not the ambient date). That is the assertion's actual subject and it passes.
The test has no row assertions at all (it is `toSQLString` + assertEqualsH2Compatible only), and the harness's h2 row-replay upgrade declined, so nothing but text is being compared.

**Fix**

Do not fix; ledger it as an engine-internal alias-spelling golden. Concretely: add `testDateFunctionInMilestonedProperty` to the known 'toSQLString raw-alias golden' ledger alongside the transform/fromPure cluster (testEqualityInFilterOnOptionalProperties, testIsDistinctSQLGeneration, testToSQLStringJoinStrings, …) which fail for exactly the same reason. Do NOT add alias normalization to the harness's SQL-text compare — that would be tenet-2 harness compensation and would also mask genuine alias-numbering regressions in the ~200 execute-path goldens that legitimately assert `<table>_<n>`. If the project ever decides these must go green, the only honest route is a platform-side 'raw alias' render mode that reproduces legend-engine's router nodeId path (`d#N`/`_l`/`_r`/`_f`/`_m<N>`/`_md`), which is a large white-box modelling exercise, not a fix to this test.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/toSQLString.pure:63 vs :46 — the same query rendered through the two surfaces differs ONLY by the default post-processors, reAlias among them

**Also unblocks** — Same classification applies to the other raw-alias `toSQLString` goldens currently failing: transform/fromPure/tests testEqualityInFilterOnOptionalProperties, testEqualityInFilterOnOptionalPropertiesLegacy, testIsDistinctSQLGeneration, testNotEqualityForOptionalProperties, testNotEqualityInFilterOnOptionalProperties, testNotEqualityInFilterOnOptionalPropertiesLegacy, testNullSafeEqualityForOptionalProperties, testSqlGenerationDivide_AllDBs, testToSQLStringJoinStrings; functions/tests testGroupByWithJoinDB2; tds/tests testRestrictDistinct_NoOptimization_WindowColumns. I identified these by their `_d#` goldens, I did not read each failure detail.

**Falsifier** — Take legend-lite's produced SQL and the golden, execute both on H2 against the corpus seeds, and diff the rows. If the rows differ, my equivalence claim about the derived-table-vs-ON-clause milestoning predicate is wrong and this is a REAL_DEFECT.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/toSQLString.pure:63 — `toSQLString(f, mapping, databaseType, extensions)` delegates with `[]` in the sqlQueryPostProcessors slot
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/transform/fromPure/toSQLString.pure:49 — by contrast `toSQL(f, mapping, runtime, extensions)` builds `postProcessors` from `meta::relational::mapping::sqlQueryDefaultPostProcessors()`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:62 — `meta::relational::postProcessor::reAlias::replaceAliasName_…` is one of the defaults
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:29 — lowercases every table name, then `$zipped->map(x| $x.first+ '_'+ $x.second->toString())` producing `<lowercase table>_<n>`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:9000 — `let aliasName = …cast(@Table).name…+$nodeId` — the un-post-processed alias is tableName + router nodeId
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:394 — `nextInGroup` returns `group + "_" + i`, with `group` the lowercased bare table name (:334); there is no alternate alias mode
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/LineageRelationsForm.java:142 — the project's own stated position: the `_d#N`/`_mN` runs are "pureToSqlQuery's internal processing-step counters, not reproducible from our IR"
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/testBusinessDateMilestoning.pure:927 — the test body: `toSQLString($query, milestoningmap, DatabaseType.H2, …)` with no row asserts
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1013 — `return "sql-text: expected " + golden + ", got " + sql;` is reached only after the h2 row-replay returned the advisory marker

</details>

---

## `testDateFunctionInMilestonedPropertyWithMilestonedEntity`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

A REAL milestoning-date defect, sitting behind the same alias-spelling wall as test 2.
The query is `Product.all(%2015-10-16)->filter(p|$p.classification(constantDate()).system.name=='SYS1')` against `milestoningMapWithEmbeddedSimple`, where `classification` is an EMBEDDED set whose `system` property is a CHAINED PM `[db]@StockProduct_ClassificationSystem > [db]@ClassificationSystem_System`. The MID table of that chain, ProductClassificationSystemTable, is business-milestoned. The golden filters it with the classification's explicit date DATE'2015-01-01'; legend-lite filters it with the root fetch date DATE'2015-10-16'. Different rows, not different text.
Mechanism — `TemporalFrame.applyJoinTemporalFilters`'s mid-table arm computes `midCtx = midSpec != null && midSpec.dates().size() == 1 && !midSpec.sweep() && specDim != null ? TemporalContext.single(specDim, midSpec.dates().get(0)) : root`. TWO gates both fail here and each independently forces the `root` fallback:
  (1) `specDim` comes from `midPrefixToDim`, which StoreResolver fills with `temporal.temporalStrategy(tg2.classFqn())` — the nav step's TARGET class. The target class is `System`, which is not temporal, so `specDim` is null. (The guard exists because `TemporalContext.single(null, …)` throws.) This is directly contradicted by the comment three lines above it in the same file, which says "the TARGET class's temporality never governs the mid table".
  (2) `midChain` is the nav head key, which for a drilled EMBEDDED head is the DOTTED chain (`classification.system`), while `collectTemporalNodes` registers the explicit date under the bare milestoned-access chain `classification`. `specs.get("classification.system")` therefore misses.
Engine semantics: the mid table takes the milestoningContext in force where the property mapping is processed — i.e. the embedded `classification` set's context, which the explicit qualified-property argument replaced (`resolveMilestoningDateParams` builds a NEW context for the hop). It does not take the root context, and the target class's temporality is irrelevant — `createJoinTableAlias`/`processRelation` stamp every milestoned join-tree relation against the ambient context.
Even after this fix the test still cannot go green: its golden is a raw-alias `toSQLString` golden (`ProductClassificationSystemTable_d#5_d#2_m1`) — see the shared root cause.

**Fix**

Replace the mid-table context computation in `TemporalFrame.applyJoinTemporalFilters` (TemporalFrame.java:1622-1648) with a proper chain-walk, and fix the dimension source:
  1. Carry the dimension ON the spec, not on the slot. In `TemporalFrame.TemporalSpec` (record at TemporalFrame.java:2119) add a `MilestoningStrategy dim` field, and set it in `collectTemporalNodes` (TemporalFrame.java:2024-2026) from `temporalStrategy(<class FQN of ma.info().type()>)` — i.e. the class the milestoned qualified property RETURNS (ProductClassification -> BUSINESS). This is the dimension the engine's TemporalMilestoningContext carries.
  2. Add a helper `TemporalContext midContextFor(String midChain)` that walks the dotted chain longest-prefix-first (`classification.system`, then `classification`) and, for the first prefix with a spec: sweep spec -> `TemporalContext.NONE` (allVersions filters nothing); 2-date spec -> the existing range/pair construction; single-date spec -> `TemporalContext.single(spec.dim(), spec.dates().get(0))`. Only when NO prefix has a spec does it return `root`.
  3. Call it at TemporalFrame.java:1643 in place of the current ternary, and delete `midPrefixToDim` together with its producer at StoreResolver.java:1829 and its parameter threading (TemporalFrame.java:1599, 1688, 1700, 1730-1750; StoreResolver.java:1792, 1838).
  4. Keep the loud `NotImplementedException` at TemporalFrame.java:1631 only for shapes the walk genuinely cannot express; the sweep and range arms are now handled, so narrow its trigger accordingly rather than deleting it.
`stampByOwnBlocks` already selects the correct milestoning block from the TABLE, so no further change is needed downstream.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8998 (ambient milestoningContext stamps every joined relation) with /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/milestoning.pure:640 (an explicit qualified-property date builds the new context for that hop)

**Risk** — The chain-prefix walk makes explicit dates propagate to mid tables that previously took the root date. Any corpus test whose golden shows a mid table stamped with the ROOT date while an ancestor hop carried an explicit date will flip — those goldens must be read before assuming a regression; the passing sibling at testBusinessDateMilestoning.pure:911 (`$p.classification.system.name`, no explicit date) is unaffected because with no spec anywhere on the chain the walk still returns `root`. Deleting `midPrefixToDim` also removes the shared-slot conflict throw's dimension input at StoreResolver.java:1829 — keep the conflict throw at :1820, it keys on `temporal.spec(chain)` and is independent. Tenet-2 trap: do NOT make the harness tolerate the date difference; wrong dates are wrong rows.

**Also unblocks** — Candidates in the same family that turn on mid-table / intermediate-join milestoning dates: testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR, testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter, testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation. Unverified — I did not read their failure details. Note this test itself will still fail on the raw-alias golden after the fix.

**Falsifier** — Log `(j.prefix(), midChain, specs.keySet(), specDim)` at TemporalFrame.java:1625 while resolving this query. If `specDim` is non-null (i.e. BUSINESS) there, gate (1) is not the cause and the diagnosis is wrong; if `midChain` is already `classification` and `specDim` is BUSINESS yet the root date still lands on the join, the mid-table arm is not the code stamping this join at all.

<details><summary>Evidence read (15 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:1643 — `TemporalContext midCtx = midSpec != null && midSpec.dates().size() == 1 && !midSpec.sweep() && specDim != null ? TemporalContext.single(specDim, midSpec.dates().get(0)) : root;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:1625 — `MilestoningStrategy specDim = midPrefixToDim.get(j.prefix().orElseThrow());`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1829 — `midPrefixToDim.putIfAbsent(slot + "_", temporal.temporalStrategy(tg2.classFqn()));` — the dimension is taken from the nav step's TARGET class
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1806 — the comment immediately above says "the TARGET class's temporality never governs the mid table", contradicting :1829
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalContext.java:66 — `case null, default -> throw new NotImplementedException("single-date temporal context for strategy '" + strategy + "' is not defined")` — why the null-specDim guard exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:2025 — `String chainKey = prefix + String.join(".", maPath);` — the explicit date registers under `classification`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1393 — `String headKey = String.join(".", path.subList(0, mid));` — nav heads key by the DOTTED chain
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:1663 — "the spec registry keys by the DOTTED chain (drilled embedded heads)"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:225 — `stampByOwnBlocks` already picks the dimension from the TABLE's own milestoning blocks (`tableHasBlock(out, dim)` then `c.dateFor(dim)`), so the context only needs the date in the right slot
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:893 — `milestoningMapWithEmbeddedSimple` embedded block: `classification( type : [db]ProductTable.type, system : [db]@StockProduct_ClassificationSystem > [db]@ClassificationSystem_System )`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:2213 — `Table ProductClassificationSystemTable( milestoning( business(BUS_FROM=from_z, BUS_THRU=thru_z) ) …)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/milestoningModel.pure:329 — `Class meta::relational::tests::milestoning::System{` — no temporal stereotype, so temporalStrategy(System) is null
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/milestoningModel.pure:293 — `Class <<temporal.businesstemporal>> …::ProductClassification` — the class whose strategy SHOULD supply the dimension
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/milestoning.pure:640 — `resolveMilestoningDateParams(temporalStrategy, milestoneDateParams, currentMilestoningContext, …)`: an explicit InstanceValue Date param yields `^DateWrapper(date=^Literal(value=$d))`, replacing the inherited context for that hop
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8998 — `createJoinTableAlias(targetAliasInJoin, joinType, nodeId, state, milestoningContext, …)` then `$targetAliasInJoin.relationalElement->processRelation(…, $milestoningContext, …)` — every join-tree relation is stamped against the AMBIENT context, regardless of the target class

</details>

---

## `testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XL |
| confidence | medium |

**Root cause**

Three stacked divergences; the milestoning ISOLATION the test is named for is actually CORRECT in legend-lite. Query: `Product.all(%2015-10-16)->filter(p| $p.cancelProductActivity.createdBy == 'David' || $p.newActivity.createdBy == 'Peter')` on propagationMapping, where `cancelProductActivity`/`cancelProductActivityCreatedBy` are CHAINED joins with an INNER second hop (`[db]@Product_CancelActivities > (INNER) [db]@CancelActivities_NewActivityInfo`). (1) The engine wraps each chained branch into a derived sub-select — `select cancelactivitiestable.productId, from_z, thru_z, newactivityinfotable.created_by from CancelActivitiesTable inner join NewActivityInfoTable on (...) where ...` — and LEFT-OUTER-joins that sub-select; this is the nested-filter isolation path (`shouldIsolateNestedFilter` → `manageIsolation`, pureToSQLQuery.pure:5170-5176). legend-lite has no such wrapping: `Pipelines.walkJoinSlot` builds a flat `TypedJoin` per hop and hardcodes the kind to `"LEFT"` (Pipelines.java:428-431), so the INNER hop is emitted as LEFT OUTER — a shape `NavMaterializer`'s own comment calls row-count-wrong ('emits LEFT where the mapping says INNER … JoinIsolationDeeper expected 4, got 11'). Here CancelActivitiesTable has `productId PRIMARY KEY` plus milestoning, so the hop is to-one and the rows happen to coincide; the emission is nonetheless wrong in general and silently so. (2) legend-lite SHARES one `cancelactivitiestable_0` between the filter's `cancelProductActivity` chain and the mapped `cancelProductActivityCreatedBy` column chain; the engine mints a separate CancelActivitiesTable instance inside each derived table. (3) Join ORDER: got is newactivityinfotable_0(newActivity), cancelactivitiestable_0, newactivityinfotable_1(filtered), newactivityinfotable_3 — exactly propagationMapping's Product declaration order (newActivity, cancelActivity, cancelProductActivity, cancelProductActivityCreatedBy); the engine puts the DataType column chain (cancelProductActivityCreatedBy) first at getAll and the two filter branches after, in predicate order. The per-branch milestoning isolation itself matches: legend-lite milestones NewActivityInfoTable on the column chain (newactivityinfotable_3) and NOT on the filter chain (newactivityinfotable_1), and leaves the plain `newActivity` join unmilestoned — identical to the golden.

**Fix**

Two separate pieces of work; neither is small.
1. INNER-hop threading (the correctness half): the mapping's per-hop JoinType must survive into the pipeline and out of `Pipelines.walkJoinSlot`. `TypedJoinSlot` (com.legend.compiler.spec.typed.TypedJoinSlot) carries no kind, so the kind is lost at JoinChecker.java:376 where the slot is built; add a JoinType field there (populated from `MappingFromProtocol`'s hop parsing, which already distinguishes `> (INNER) @Next` — see MappingFromProtocol.java:518-521) and emit it at Pipelines.java:428-431 instead of the hardcoded `"LEFT"`. This is the change NavMaterializer.java:181-188 is already waiting on, and it is what makes the golden's `inner join NewActivityInfoTable` reachable at all.
2. Nested-filter isolation (the shape half): implement the engine's `manageIsolation` — when a chained-join branch is materialized under a filter at chain depth <= 1, wrap the chain in a derived sub-select that projects the branch's read columns plus the milestoning columns (`productId, from_z, thru_z, created_by` in the golden), and LEFT-OUTER-join the sub-select while re-applying the outer milestoning predicate on the exposed from_z/thru_z. This belongs in the resolver/lowering seam next to `Pipelines.materialize` (StoreResolver.materializeRoot, StoreResolver.java:1755-1873), not in the renderer. It is the same missing surface as the other sql-text failure `tests/advanced testForcedIsolationFilterOnTop`.
3. Join order: the same change as tests 4/5/6 (DataType-column join slots before class-typed navigate steps).
Honest recommendation: LEDGER this test. Piece 2 is a large new emission surface, and this test asserts nothing but SQL text, so it cannot be closed by any of the cheaper fixes. Piece 1 should still be done on its own merits — it is a real correctness gap the codebase already documents.

**How legend-engine does it** — meta::relational::functions::pureToSqlQuery::processFilter / manageIsolation — legend-engine/.../core_relational/relational/pureToSqlQuery/pureToSQLQuery.pure:5170

**Risk** — Piece 1 flips LEFT to INNER on every chained mapping hop declared `(INNER)` across the corpus — that legitimately REMOVES rows wherever the mapping says so, and will move tests that currently pass with the over-permissive LEFT. Land it with a full sweep and read the row-count deltas, do not assume they are all improvements. Tenet-2 trap: the temptation here is to teach the harness to compare 'join sets modulo nesting' so the derived-table difference stops mattering — that would hide a real emission gap the platform owns.

**Also unblocks** — tests/advanced testForcedIsolationFilterOnTop (same manageIsolation surface, per the sweep's sql-text failure list)

**Falsifier** — Run the query and compare rows against the golden's SQL on the same seed data. If the row sets are identical (which I expect here, because CancelActivitiesTable.productId is a PRIMARY KEY so the INNER hop is to-one), then piece 1 changes nothing observable for THIS test and the whole remaining gap is text-shape — reclassify as GOLDEN_TEXT_ONLY and ledger it. A cheaper structural falsifier: if `TypedJoinSlot` turns out to already carry a join kind that Pipelines.java:428 is overriding, then the INNER loss is a one-line bug rather than missing plumbing.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/testMilestoningContextPropagation.pure:175-181 — the test body and its H2 golden showing the two derived sub-selects and `left outer join NewActivityInfoTable as "newactivityinfotable_2"`
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:820-827 — propagationMapping Product: `newActivity`, `cancelActivity`, `cancelProductActivity : [db]@Product_CancelActivities > (INNER) [db]@CancelActivities_NewActivityInfo`, `cancelProductActivityCreatedBy : ... > (INNER) ... | NewActivityInfoTable.created_by` — declaration order matches the got join order exactly
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:2194-2212 — both CancelActivitiesTable and NewActivityInfoTable declare `milestoning(business(BUS_FROM=from_z, BUS_THRU=thru_z))` with `productId Integer PRIMARY KEY`
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:2552 — `Filter NewActivityWithValidProduct(NewActivityInfoTable.productId > 0)`, the `~filter` on NewProductActivityInfo that both sides render (engine in the derived WHERE, legend-lite in a `select * from ... where productId > 0` sub-select)
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:5170-5176 — `let shouldIsolateNestedFilter = $state.inFilter && ($state.filterChainDepth <= 1) && ...; let res = if($shouldIsolateNestedFilter, |^$preIsolation(element = manageIsolation(...)), |$preIsolation);` — the sub-select wrapping the golden shows
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:4766-4790 and :5062 — class DataType columns at getAll, filter predicate joins after
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:428-431 — `return new TypedJoin(left, tgt, new TypedEnumValue(JOIN_KIND_FQN, "LEFT", ...), cond, ...)` — every materialized slot is LEFT, unconditionally
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/NavMaterializer.java:181-188 — 'the optimization chains declare (INNER) hops (orgs: @a > (INNER) @b) that our slot emission does not thread yet; demanding them emits LEFT where the mapping says INNER (row-count wrong: JoinIsolationDeeper expected 4, got 11)'

</details>

---

## `testLatestIgnoredForNonMilestonedMappedBiTemporalClassesAllQuery`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | XS (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Identical mechanism to testLatestIgnoredForNonMilestonedMappedClassesAllQuery, applied on both temporal axes. `BiTemporalProduct.all(%latest, %latest)` against noMilestoningMap maps to `ProductTableNoMilestoning` (no milestoning block). `GraphEmission.synthesizeScalarTree` (GraphEmission.java:150-158) adds BOTH a `businessDate` and a `processingDate` node because `strat` is BITEMPORAL (neither `strat != PROCESSING` nor `strat != BUSINESS` excludes it), and both leaves resolve to the `%latest` context date, which lowers to `TimestampLit("9999-12-31 00:00:00.0000")` (Lowerer.java:2214). Observed got carries `TIMESTAMP'9999-12-31 00:00:00.0000' as "k_businessDate", TIMESTAMP'9999-12-31 00:00:00.0000' as "k_processingDate"`. The engine's `TemporalMilestoningContext.columns()` (milestoning.pure:86-89) maps `getTemporalDateAlias` over `expandToSingleTemporalStrategies()`, and each single strategy returns `[]` because ProductTableNoMilestoning supports neither, so neither column exists.

**Fix**

Same change as testLatestIgnoredForNonMilestonedMappedClassesAllQuery, applied per dimension in `GraphEmission.synthesizeScalarTree` (GraphEmission.java:150-158): for the businessDate node consult `temporal.rootContextDate(true)` + `latestAliasLiteral(cs.pipeline(), BUSINESS)`; for the processingDate node consult `temporal.rootContextDate(false)` + `latestAliasLiteral(cs.pipeline(), PROCESSING)`. Each axis is decided independently, exactly as `expandToSingleTemporalStrategies()->map(...)` does — a bitemporal class over a table with only a business block must keep k_businessDate and drop k_processingDate.

**How legend-engine does it** — meta::relational::milestoning::TemporalMilestoningContext.columns + getTemporalDateAlias — .../core_relational/relational/milestoning/milestoning.pure:86 and :92

**⚠ Correction from adversarial review** — Not a separate work item: diagnosis 3's fix text already applies the gate to both axes, so this test falls out of that same edit. Track it as a second falsifier of one change, not as an extra S.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism verified. GraphEmission.java:149-159 is verbatim as cited and for BITEMPORAL both guards pass (strat != PROCESSING and strat != BUSINESS are both true), so both nodes are added with no reference to the mapped relation. synthesizeScalarTree is reached exactly for this shape: StoreResolver.java:2792-2793 calls it when tree==null && implicitSerialize (a bare .all() with no project). generatedDateLeaf (:167-182) returns the root context date verbatim, and Lowerer.java:2214-2215 lowers TypedCLatestDate to TimestampLit("9999-12-31 00:00:00.0000"); RelationalRootForm.java:107-110 renames to k_<prop>. The recorded got (docs/RELATIONAL_CORPUS_ALL.md:1317) is exactly expected + those two columns, in tree order, so removing the two nodes yields the golden character-for-character. Engine side confirmed: TemporalMilestoningContext.columns() maps getTemporalDateAlias over expandToSingleTemporalStrategies(), and getTemporalDateAlias (milestoning.pure:93-104) returns [] for a %latest date when relationalElementCanSupportStrategy is false — and relationalElementCanSupportStrategy (:736-746) is genuinely per-single-strategy against the table's milestoning, so per-axis independence is real. noMilestoningMap maps BiTemporalProduct to ProductTableNoMilestoning (setUp :778-783), which has no milestoning block (:1823). Effort: this is NOT separate work — the fix in diagnosis 3 already says 'symmetrically for processingDate', i.e. one edit fixes both tests. Booking this as an independent S double-counts the plan.

</details>

**Citation issues found in review** — milestoning.pure:86-89 is cited for columns(); columns() is actually 88-90 (line 86 is currentProcessingStateIsMilestonedClassProperty). testLatestDateMilestoning.pure:33-36 is cited for the execute+golden; the execute is at :35 and the assertSameSQL at :36. Both are off-by-small, content matches.

**Risk** — Same as the sibling. Additionally: dropping k_processingDate changes the object envelope's processingDate for bitemporal instances — confirm `getKeyValueBuildClasses` parity (milestoning.pure:626-630 only builds a KeyValue when the row actually carries the alias column, so the engine tolerates absence by design).

**Also unblocks** — testLatestIgnoredForNonMilestonedMappedClassesAllQuery

**Falsifier** — If legend-lite's rendered SQL for `BiTemporalProduct.all(%latest,%latest)` against `singleTemporalMappingForBiTemporalTypes` (ProductTable: business-only block) emits BOTH k_ columns, the per-axis independence is already broken elsewhere and the gate must live one level deeper than synthesizeScalarTree.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:86-89 — `columns(r:Relation[1]) { $this.currentMilestoningStrategy->expandToSingleTemporalStrategies()->map(temporalStrategy | $temporalStrategy->getTemporalDateAlias($this, $r))->reverse(); }`
- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:92-104 — the %latest arm returns `[]` when `relationalElementCanSupportStrategy` is false or `getInfinityDate` is empty
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:150-158 — both `businessDate` and `processingDate` nodes added whenever `strat != null`, with per-dimension guards that only exclude the single-axis strategies
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:778-782 — noMilestoningMap maps `BiTemporalProduct` to `[db]ProductTableNoMilestoning.id/.name/.type`
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/testLatestDateMilestoning.pure:33-36 — `execute(|BiTemporalProduct.all(%latest, %latest), noMilestoningMap, ...)` with golden `select "root".id as "pk_0", ... from ProductTableNoMilestoning as "root"` (no k_ columns)

</details>

---

## `testLatestIgnoredForNonMilestonedMappedClassesAllQuery`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`Product` is businesstemporal but `noMilestoningMap` maps it to `ProductTableNoMilestoning`, which declares no milestoning block. `GraphEmission.synthesizeScalarTree` (GraphEmission.java:149-159) adds a `businessDate` envelope node for ANY class whose temporal strategy is non-null, with no reference to the mapped relation. `GraphEmission.generatedDateLeaf` (GraphEmission.java:178-182) then returns the root context date verbatim — and its comment says so deliberately: 'the point-fetch CONSTANT needs no milestone columns — a temporal class on a capability-tolerance (non-milestoned) table still has a well-defined context date (audit 14 B-F8: the column check walled it needlessly)'. That over-corrected. The context date here is `%latest` (TypedCLatestDate), which `Lowerer.scalar` turns into `SqlExpr.TimestampLit("9999-12-31 00:00:00.0000")` (Lowerer.java:2214-2215). `RelationalRootForm.flatten` renames the leaf to `k_businessDate` (RelationalRootForm.java:107-110) and it lands in the SELECT. The engine drops the alias entirely: `getTemporalDateAlias` (milestoning.pure:92-104) evaluates `if($temporalDate->toOne()->isLatestDate() && $temporalStrategy->relationalElementCanSupportStrategy($r) && $temporalStrategy->getInfinityDate($r)->isNotEmpty(), | ^Literal(...) , | if($temporalDate->toOne()->isLatestDate(), | [] , | ...))` — a %latest date on a relation that cannot support the strategy yields `[]`, so no Alias is built and `TemporalMilestoningContext.columns()` (milestoning.pure:86-89) contributes nothing. Note the suppression is specific to %latest: a concrete date on a non-milestoned table still projects the alias (the final match arm), so the audit-14 removal was right for that case and wrong only for %latest.

**Fix**

In `GraphEmission.synthesizeScalarTree` (core/src/main/java/com/legend/resolver/GraphEmission.java:149-159), gate each generated-date tree node on the engine's getTemporalDateAlias rule: add the node only when NOT (the root context date for that dimension is a `TypedCLatestDate` AND the class's mapped root relation cannot supply an INFINITY_DATE for that dimension). Add one package-private helper on TemporalFrame next to `stampWithBlock` (TemporalFrame.java:1359-1398, which already resolves exactly this block):

    /** engine getTemporalDateAlias (milestoning.pure:92-104): the %latest
     *  alias literal — the TABLE's INFINITY_DATE in Pure toString() form;
     *  null when the relation cannot support the strategy or declares no
     *  INFINITY_DATE, in which case the engine projects NO alias at all. */
    @Nullable String latestAliasLiteral(TypedSpec pipe, MilestoningStrategy dim) {
        TypedTableReference root = rootTable(pipe);
        var ms = root == null ? null
                : ctx.findTableMilestoning(root.store(), root.table()).orElse(null);
        var blk = ms == null ? null
                : (dim == MilestoningStrategy.PROCESSING ? ms.processing() : ms.business());
        String inf = blk == null ? null : blk.infinityDate();
        if (inf == null) { return null; }
        var d = PureDateLiteral.parse(inf.startsWith("%") ? inf.substring(1) : inf);
        return d.toEngineString()
                + (d instanceof PureDateLiteral.StrictDate
                   || d instanceof PureDateLiteral.YearMonth
                   || d instanceof PureDateLiteral.Year ? "" : "+0000");
    }

Then in synthesizeScalarTree, for the businessDate node: skip when `temporal.rootContextDate(true) instanceof TypedCLatestDate && temporal.latestAliasLiteral(cs.pipeline(), MilestoningStrategy.BUSINESS) == null`; symmetrically for processingDate with `rootContextDate(false)` and PROCESSING. Do NOT gate on the relation for non-latest dates — the engine's final match arm emits the alias regardless of milestoning, which is what audit 14 B-F8 was about. `TemporalFrame.rootContextDate(boolean)` already exists (TemporalFrame.java:71-74) and `ctx.findTableMilestoning` is already used at TemporalFrame.java:1352.

**How legend-engine does it** — meta::relational::milestoning::getTemporalDateAlias — legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/milestoning/milestoning.pure:92

**⚠ Correction from adversarial review** — The gate is right; the helper as specified is over-built and has one latent hole. (1) latestAliasLiteral's return VALUE is never used — the fix only tests it for null — so building the Pure-toString literal (PureDateLiteral.parse + '+0000' suffix) is dead weight here, and it collides with the separate bucket-4 diagnosis for testQueryOfMilestonedTypeUsingLatestWithFilterInMapping, which puts the same literal in RelationalRootForm.apply. Decide one owner for the KEPT-case spelling before writing this helper; note legend-lite currently emits TIMESTAMP'9999-12-31 00:00:00.0000' where every engine golden has '9999-12-31T00:00:00.0000+0000'. (2) rootTable(pipe) returns null for a concatenate (union-mapped) pipe, so as written the gate would also drop k_businessDate for a union-mapped MILESTONED class under %latest, where the engine keeps it (relationalElementCanSupportStrategy and getInfinityDate both have Union arms, milestoning.pure:739 and :610-613). No corpus test exercises bare .all(%latest) over a union mapping, so this will not show up in the sweep — but reuse the existing Pipelines.containsConcatenate + tableHasBlock shape at TemporalFrame.java:1344-1348 rather than rootTable alone. (3) A cheaper equivalent for the gate already exists: temporal.milestoneColumnsOf(cs.pipeline(), cs.classFqn()) is empty for a non-milestoned table (used at GraphEmission:183).

<details><summary>Adversarial review notes (CONFIRMED)</summary>

All six legend-lite citations resolve verbatim (GraphEmission:149-159 with no relation check; the audit-14 comment at :174-177 followed by 'if (ctxDate != null) return ctxDate;' at :180-182; Lowerer:2214-2215; RelationalRootForm:107-110). The engine rule is exactly as quoted: getTemporalDateAlias (milestoning.pure:93-104) yields [] for %latest when the relation cannot support the strategy, and the non-latest final match arm does emit the alias regardless of milestoning — so the claim that the suppression is %latest-specific (and that audit 14 B-F8 was right for concrete dates) is correct. The recorded got (RELATIONAL_CORPUS_ALL.md:1318) is expected + exactly one k_businessDate column, so the gate produces the golden. Plumbing exists: TemporalFrame.rootContextDate(boolean) at :71-74, ctx.findTableMilestoning at :1351, stampWithBlock at :1358+, ClassSource.pipeline() already used at GraphEmission:183; TemporalFrame and GraphEmission are the same package, so package-private access works. I checked the competing hypothesis in docs/BURNDOWN_EXPLANATIONS.md:195 ('LATEST_DATE at the lowering boundary, PureSql.java:65-66') — PureSql:55-71 only maps LATEST_DATE to SqlType TIMESTAMP; it decides the column's TYPE, never whether the column exists, so it cannot explain the extra projection. Two caveats below, neither blocking this test.

</details>

**Citation issues found in review** — milestoning.pure:86-89 cited for columns() — it is 88-90. getTemporalDateAlias cited as 92-104 — the function header is at 93. setUp cited as 773-776 for the Product mapping — it is ~773-776 in a block whose surrounding lines I read (id/type only, no name), so the substance holds. All legend-lite citations are exact.

**Risk** — Gating on the mapped ROOT relation is wrong for a class mapped over a union/partial-milestoning source where only some members declare INFINITY_DATE; the engine's getInfinityDate has a Union arm (milestoning.pure:608-613) that asserts all unioned tables agree. Keep that path out of the gate (fall through to emitting) rather than silently dropping the column. Tenet-2 trap: do NOT strip the column in H2Verify/EngineTestExecutor or in RelationalRootForm's k_ rename — the platform owns which columns the SELECT has.

**Also unblocks** — testLatestIgnoredForNonMilestonedMappedBiTemporalClassesAllQuery (same fix, both dimensions)

**Falsifier** — Read legend-lite's rendered SQL for `Product.all(%2015-10-16)` against noMilestoningMap. If it also lacks `k_businessDate`, then the suppression is not %latest-specific and my gate is over-narrow (the engine's final match arm says it should still be present, so that would instead mean legend-lite has a second, unrelated suppression).

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/milestoning.pure:92-104 — getTemporalDateAlias: `if($temporalDate->toOne()->isLatestDate() && $temporalStrategy->relationalElementCanSupportStrategy($r) && $temporalStrategy->getInfinityDate($r)->isNotEmpty(), |^Literal(value=$temporalStrategy->getInfinityDate($r)->toString()), | if($temporalDate->toOne()->isLatestDate(), | [] , | ...))` then `if($temporalDateRelationalElement->isNotEmpty(), |^Alias(name='\"'+temporalColumnAliasProperties(...).second+'\"', ...), |[])`
- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:86-89 — `columns(r:Relation[1]) { $this.currentMilestoningStrategy->expandToSingleTemporalStrategies()->map(temporalStrategy | $temporalStrategy->getTemporalDateAlias($this, $r))->reverse(); }: Alias[*]` — an empty alias list means no projected column
- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:209 — `getBusinessDateAliasLiteral():String[1] { 'k_businessDate'; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:149-159 — `MilestoningStrategy strat = temporal.temporalStrategy(cs.classFqn()); if (strat != null) { if (strat != PROCESSING && !cs.bindings().containsKey("businessDate")) tree.add(new TypedGraphTree("businessDate", List.of())); ... }` — no relation/milestoning check at all
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:174-182 — comment 'the point-fetch CONSTANT needs no milestone columns … (audit 14 B-F8: the column check walled it needlessly)' followed by `if (ctxDate != null) return ctxDate;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2214-2215 — `case TypedCLatestDate ignored -> new SqlExpr.TimestampLit("9999-12-31 00:00:00.0000");`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/RelationalRootForm.java:107-110 — `if (strat != null && Temporal.isGeneratedDateProperty(c.name(), strat)) { c = new TypedFuncCol("k_" + c.name(), c.fn()); }`
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:1823 — `Table ProductTableNoMilestoning(` with no `milestoning(...)` block; contrast line 1833 `business(BUS_FROM=from_z, BUS_THRU=thru_z, INFINITY_DATE=%9999-12-31T00:00:00.0000)` on ProductTable
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:773-776 — noMilestoningMap maps `meta::relational::tests::milestoning::Product` to `[db]ProductTableNoMilestoning.id/.type`

</details>

---

## `testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | L |
| confidence | high |

**Root cause**

Same two causes as its sibling, and nothing else. `Product.all(%latest)->filter(p|$p.classification.type=='STOCK')` — the filter's `classification` carries no explicit date, so the %latest context propagates and BOTH ProductClassificationTable joins get `thru_z = TIMESTAMP'9999-12-31 00:00:00.0000'`; got and golden agree on every predicate. The divergences are (a) `TIMESTAMP'9999-12-31 00:00:00.0000' as "k_businessDate"` where the engine spells the table's INFINITY_DATE as the string `'9999-12-31T00:00:00.0000+0000'` (Lowerer.java:2214 hardcodes the timestamp sentinel; EngineStyleH2.java:909 only de-types DateLit in projection position), and (b) the join permutation — got is `class_0(filter), stock_0, desc_0, class_1(column)`, golden is `stock_0, desc_0, class_0(column), class_1(filter)` — because legend-lite renders the ClassSource pipeline in mapping-declaration order (milestoningmap Product declares the `classification` navigate at slot 4, before the `stockProductName`/`classificationType` join slots at 9/10) while the engine emits the class's DataType-property joins at getAll and the filter's joins afterwards. I diffed the two texts token by token: apart from the k_ literal they are an exact permutation with consistent alias renaming, so no row can differ.

**Fix**

No separate fix. Apply exactly the two changes described for testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter: (a) source the %latest k_ column value from the mapped table's INFINITY_DATE in `GraphEmission.generatedDateLeaf` plus a `TimestampLit` arm in `EngineStyleH2.projection`; (b) order join slots before navigate steps in the ClassSource pipeline. Change (a) alone will NOT flip this test — the join permutation remains — so do not expect a green until (b) lands.

**How legend-engine does it** — meta::relational::milestoning::getTemporalDateAlias — .../core_relational/relational/milestoning/milestoning.pure:97; meta::relational::functions::pureToSqlQuery::processRelationalMappingSpecification — .../pureToSqlQuery/pureToSQLQuery.pure:4766

**Risk** — Same as the sibling: (b) permutes alias numbering corpus-wide. Do not 'fix' this by making the harness compare join sets instead of join text.

**Also unblocks** — testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter, testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty

**Falsifier** — Apply change (a) only and re-render. If the resulting text then matches the golden, the join order was never divergent here and cause (b) is fabricated — but the recorded got literally places `productclassificationtable_0` (the filter's join, referenced by the WHERE) before `stockproducttable_0`, which the golden places last, so this is directly checkable from the sweep record alone.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/testMilestoningContextPropagation.pure:306-311 — the test body `Product.all(%latest)->filter(p|$p.classification.type=='STOCK')` and its assertEqualsH2Compatible golden with `'9999-12-31T00:00:00.0000+0000' as "k_businessDate"`
- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:97 — the alias value is `getInfinityDate($r)->toString()`, a String literal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2214-2215 — the hardcoded `TimestampLit("9999-12-31 00:00:00.0000")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:326-346 — `planSource` assigns `<table>_<n>` strictly by left-to-right traversal of the join tree
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:4788 — `columnNamesWithRelationalElement($viewSpecification, $c, $state)` is what seeds the getAll join tree (DataType property mappings only)
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:550,555,556 — milestoningmap Product: `classification : [db]@Product_Classification` declared before `stockProductName`/`classificationType`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1455-1458 — `if (r.verified() == 0 && !r.sqlDiffs().isEmpty()) yield new Outcome(fqn, Status.FAIL, ...)` — this test has no row assert, so the text diff is the whole contract

</details>

---

## `testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | L |
| confidence | high |

**Root cause**

Two independent text divergences, no wrong rows (the test asserts nothing but SQL). (a) k_businessDate LITERAL: `Product.all(%latest)` on milestoningmap — ProductTable declares `INFINITY_DATE=%9999-12-31T00:00:00.0000`, so the engine projects `^Literal(value = getInfinityDate(r)->toString())`, a STRING, rendering `'9999-12-31T00:00:00.0000+0000'` (Pure DateTime toString appends the zone). legend-lite's leaf is the raw `TypedCLatestDate`, which `Lowerer.scalar` maps to a hardcoded `SqlExpr.TimestampLit("9999-12-31 00:00:00.0000")` — the comment there ('the FIXED engine sentinel, not the table's INFINITY_DATE') is factually wrong for this column; the table's INFINITY_DATE is exactly what the engine uses. `EngineStyleH2.projection` only de-types a projected `DateLit` into plain quotes (EngineStyleH2.java:909-911); a TimestampLit falls through to `timestampLit()` and prints `TIMESTAMP'...'`. (b) JOIN ORDER: got emits `productclassificationtable_0` = the FILTER's `classification(%2015-10-17)` join first, then stockproducttable_0/productdescriptiontable_0/productclassificationtable_1 (the class's own mapped columns). The golden is the exact inverse permutation. Cause: legend-lite renders the ClassSource pipeline in mapping-declaration order — milestoningmap's Product declares `classification` (a class-typed navigate step) at position 4, before `stockProductName`/`classificationType` (the join-slot columns) at 9/10 — and `Pipelines.materialize`/`walk` preserves that structural order, while `EngineStyleH2.planSource` numbers aliases by left-to-right traversal. The engine instead materialises the class's DATA-TYPE property columns at getAll time (processRelationalMappingSpecification: `columnNamesWithRelationalElement($viewSpecification, $c, $state)->map(...)`) and only then processes the filter, whose LEFT side (the getAll tree) is processed first and whose predicate joins are appended after.

**Fix**

Two changes, independent.
(a) k_ literal (small, do this one): in `GraphEmission.generatedDateLeaf` (GraphEmission.java:178-182), when the context date is a `TypedCLatestDate` and `TemporalFrame.latestAliasLiteral(cs.pipeline(), dim)` (the helper proposed for tests 2/3) is non-null, return that literal instead of the raw ctxDate. Keeping the leaf DATE-typed is safest: return `new TypedCDate(PureDateLiteral.parse(<infinity, %-stripped>), ExprType.one(Type.Primitive.DATE_TIME))`, and add one arm to `EngineStyleH2.projection` (EngineStyleH2.java:909) so a projected `SqlExpr.TimestampLit` also spells plainly in the engine's Pure toString() form: `p.expr() instanceof SqlExpr.TimestampLit tl ? "'" + tl.iso() + "+0000'" : ...` — `TimestampLit.iso()` already carries the 'T' form (MatchFold.java:95-96 stores `su.toEngineString()`), so this yields exactly `'9999-12-31T00:00:00.0000+0000'`. Sourcing the value from the table's INFINITY_DATE (rather than the hardcoded sentinel) also fixes the latent wrong-value case: businessDateMilestoningSetUp.pure:1997 declares a table with `INFINITY_DATE=%9999-12-31` (a StrictDate), for which the engine projects `'9999-12-31'`.
(b) join order (large, ledger it unless the sql-only bucket is being burned down): the ClassSource pipeline must place join slots that feed DATA-TYPE property bindings before class-typed navigate steps, so that `Pipelines.materialize`'s structural walk emits them in the engine's order. The construction site is `MappingNormalizer` (its documented pipeline shape at MappingNormalizer.java:96-101 already says `join(~alias: ...)*` then `legacyNavigate(~slot: ...)*` — the observed SQL proves the emission does not honour that ordering for property mappings declared out of that sequence). Either enforce the documented order there, or add a stable partition in `Pipelines.sinkNavSteps`-style form (Pipelines.java:67-113 already has the exact top-first/deepest-last machinery to reorder a slot/nav chain) applied at `StoreResolver.materializeRoot` (StoreResolver.java:1761-1764) so that all `TypedJoinSlot` steps precede all `TypedNavigate` steps. Filter/projection-demanded navs then naturally follow the column joins.

**How legend-engine does it** — meta::relational::milestoning::getTemporalDateAlias — .../core_relational/relational/milestoning/milestoning.pure:97; join ordering: meta::relational::functions::pureToSqlQuery::processRelationalMappingSpecification .../pureToSqlQuery/pureToSQLQuery.pure:4766 and processFilter :5062

**Risk** — Change (b) permutes alias numbering across the WHOLE corpus. Any currently-passing golden-text assert that matches by luck under the present order will flip, and the M1 byte-match census (H2Verify.M1_VERIFIED) will move in both directions. Do it as its own change with a full sweep, never bundled with (a). Tenet-2 trap for both: do not normalise alias numbers or re-order join text inside EngineTestExecutor/H2Verify to make the compare pass — the renderer owns join order and the k_ literal spelling.

**Also unblocks** — testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation and testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty (both); testQueryOfMilestonedTypeUsingLatestWithFilterInMapping (change (a) only — its detail shows the same TIMESTAMP'9999-12-31 00:00:00.0000' vs '9999-12-31T00:00:00.0000+0000' k_businessDate divergence)

**Falsifier** — Render `Product.all(%2015-10-16)->filter(p|$p.classification(%2015-10-17).type=='STOCK')` (the non-latest sibling testMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter, which currently PASSES) and inspect its SQL text. If its join order matches the golden (stockproducttable_0, productdescriptiontable_0, productclassificationtable_0, productclassificationtable_1), then the ordering is NOT systemic and something %latest-specific reorders — my cause (b) is wrong. My prediction is that it too emits the filter join first and passes only because it has row asserts that verify (EngineTestExecutor.java:1007-1013 upgrades a text-divergent golden to a pass on row equality).

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../milestoning/milestoning.pure:97 — `^Literal(value=$temporalStrategy->getInfinityDate($r)->toString())` (a String, not a Date literal)
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:1833 — `business(BUS_FROM=from_z, BUS_THRU=thru_z, INFINITY_DATE=%9999-12-31T00:00:00.0000)` on ProductTable
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/test/java/.../TestDateTimeParsing.java:58 — `assertParsesTo("2014-02-07T07:03:01.0003742635+0000", "%2014-02-07T07:03:01.0003742635")` — Pure DateTime toString carries `+0000`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2209-2215 — `// %latest … the FIXED engine sentinel, not the table's INFINITY_DATE` / `new SqlExpr.TimestampLit("9999-12-31 00:00:00.0000")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:909-911 — `String e = p.expr() instanceof SqlExpr.DateLit dl ? "'" + dl.iso() + "'" : expr(p.expr(), 0);` (TimestampLit not covered)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:637-642 — `timestampLit` rewrites the ISO 'T' to a space and emits `TIMESTAMP'...'`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:329-346,394-397 — `planSource` renames each table alias via `nextInGroup(group, groups)` in traversal order; `nextInGroup` = `group + "_" + i++`
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:4766-4790 — processRelationalMappingSpecification builds the root node then `columnNamesWithRelationalElement($viewSpecification, $c, $state)->map(c | ...processColumnsInRelationalOperationElements...)` — only DataType property mappings contribute columns/joins here
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:5062 — processFilter: `let leftSideOp = processValueSpecificationReturnPropertyMapping($leftSidePure, ...)` (the getAll and its join tree) BEFORE the predicate is processed at :5100
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:546-558 — milestoningmap Product: `classification` at declaration slot 4, `stockProductName`/`classificationType` at 9/10
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:341-434 — `materialize`/`walkJoinSlot` rebuild the pipeline in its existing structural order; no reordering by column vs navigation

</details>

---

## `testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | L |
| confidence | high |

**Root cause**

Pure join-ordering divergence — the k_ literal is correct here (`'2015-10-15'` on both sides, because the root date is a StrictDate and `EngineStyleH2.projection` already de-types a projected DateLit into plain quotes, EngineStyleH2.java:909-911). Query: `Product.all(%2015-10-15)->filter(p|$p.classification(%2015-10-16).type=='STOCK' && $p.exchange(%latest).name=='LNSE')`. Golden join order: stockproducttable_0(10-15), productdescriptiontable_0, productclassificationtable_0(10-15, the mapped `classificationType` column), productclassificationtable_1(10-16, the filter), productexchangetable_0(thru_z=INF, the filter). Got: productclassificationtable_0(10-16), productexchangetable_0(INF), stockproducttable_0(10-15), productdescriptiontable_0, productclassificationtable_1(10-15). Same five joins, same five ON predicates, same WHERE semantics — an exact permutation with consistent renaming. Cause: legend-lite renders the ClassSource pipeline in mapping-declaration order, and milestoningmap's Product declares the class-typed navigations `classification` and `exchange` (slots 4 and 6) BEFORE the join-slot columns `stockProductName`/`classificationType` (slots 9/10). `Pipelines.materialize`/`walkJoinSlot` preserve that structural order and `EngineStyleH2.planSource` numbers by traversal. The engine seeds the join tree from the class's DataType property columns at getAll (pureToSQLQuery.pure:4788) and appends every predicate join afterwards (processFilter processes its LEFT side first, :5062). Its non-%latest sibling `testMilestoningQueryWithMilestoneFilterANdDifferentDatesOnTypeAndProperty` (testBusinessDateMilestoning.pure:359) has the same divergence but PASSES, because it carries row asserts and a text-divergent golden is upgraded to a pass by the H2 row replay.

**Fix**

Only change (b) from the sibling entries is needed: make the ClassSource pipeline place DataType-column join slots ahead of class-typed navigate steps, so `Pipelines.materialize`'s structural walk emits them in the engine's order. Enforce it at construction in `MappingNormalizer` (the shape it already documents at MappingNormalizer.java:96-101: `join(~alias: ...)*` then `legacyNavigate(~slot: ...)*`), or as a stable partition applied to `cs.pipeline()` at `StoreResolver.materializeRoot` (StoreResolver.java:1761-1764) reusing the chain-rebuild loop in `Pipelines.sinkNavSteps` (Pipelines.java:82-112) with the predicate `step instanceof TypedJoinSlot` instead of the nav-date alias test. Nothing else in this test needs to change.

**How legend-engine does it** — meta::relational::functions::pureToSqlQuery::processRelationalMappingSpecification — legend-engine/.../core_relational/relational/pureToSqlQuery/pureToSQLQuery.pure:4766 (class columns seed the join tree) and processFilter :5062 (left side first)

**Risk** — Reordering slots vs navigate steps changes alias numbering everywhere and will move a large number of currently-advisory sql diffs in both directions; it can also change which alias a correlated/EXISTS subquery numbering group consumes (EngineStyleH2.planQuery walks WHERE subqueries into the same group counters, EngineStyleH2.java:304-309). Land it alone, with a full sweep before/after. Tenet-2 trap: never normalise alias indices in the harness comparison.

**Also unblocks** — testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter, testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation, and the ordering half of testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR

**Falsifier** — Render the PASSING sibling `testMilestoningQueryWithMilestoneFilterANdDifferentDatesOnTypeAndProperty` and print its SQL. If its join order already matches the golden, the divergence is %latest-triggered rather than declaration-order-driven and this diagnosis is wrong. (Cheapest form: the sweep already records that the sibling passes with row asserts — dump its `sqlDiffs` count from the Ran outcome; a non-zero advisory count confirms the systemic divergence without any code change.)

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/testBusinessDateMilestoning.pure:373-380 — the test body and its H2 golden (join order stockproducttable_0, productdescriptiontable_0, productclassificationtable_0, productclassificationtable_1, productexchangetable_0)
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/testBusinessDateMilestoning.pure:359-368 — the sibling with `$p.exchange(%2015-10-17)` instead of `%latest`, same golden join order, and with `assertEquals(1, $products->size())` row asserts
- /Users/neemsandv/legend/legend-engine/.../milestoning/tests/businessDateMilestoningSetUp.pure:546-558 — milestoningmap Product declaration order: id, name, type, classification, referenceSystem, exchange, synonyms, orders, stockProductName, classificationType, biTemporalClassification
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:4766-4790 — processRelationalMappingSpecification: root node, then the DataType property columns, then pk columns; class-typed property mappings contribute no columns here
- /Users/neemsandv/legend/legend-engine/.../pureToSqlQuery/pureToSQLQuery.pure:5054-5100 — processFilter: left side (the getAll tree) at :5062, predicate at :5100
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Pipelines.java:67-113 — sinkNavSteps collects the slot/nav chain top-first and rebuilds it; the DEEPEST step renders leftmost, so pipeline order IS join order
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:378-381,394-397 — `planSource(Join)` recurses left then right and `nextInGroup` hands out `_0,_1,...` in that order
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1007-1013 — a divergent golden text still passes when `h2Upgrade` verifies rows; only an ADVISORY_MARKER (unverifiable) surfaces the `sql-text: expected …, got …` string
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1451-1458 — an sql-only Ran outcome with sqlDiffs and zero verified asserts becomes FAIL

</details>

---

## `testQueryOfMilestonedTypeUsingLatestWithFilterInMapping`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

TWO independent defects, both real (they change the object shape / literal, not just text).

(A) EXTRA PROPERTIES. `StockProduct` and `Product` both have their own class mapping in `milestoningmap`, neither declares `extends [setId]`. legend-lite's `ImplicitInheritance.apply` (called unconditionally from MappingNormalizer.normalizeMapping) rewrites any Relational class mapping whose CLASS extends an ancestor that has exactly one Relational mapping over the SAME main table, merging the ancestor's unqualified property mappings into the child's `propertyMappings` list. StockProduct's inferred main table is ProductTable and Product's is ProductTable, so StockProduct's class mapping silently gains Product's `stockProductName`, `classificationType`, `referenceSystem`, `biTemporalClassification`. The two datatype ones become bindings, and `GraphEmission.synthesizeScalarTree` emits ONE LEAF PER SCALAR BINDING for a bare class root — hence the extra `stockProductName`/`classificationType` select columns and their three left-outer joins. legend-engine has no such merge: the root object's column list is `$r->dataTypePropertyMappings()` = `allPropertyMappings()`, and `allPropertyMappings` inherits ONLY through `superSetImplementationId`, i.e. an explicit `extends [setId]`.

(B) %latest VALUE LITERAL. The generated `businessDate` leaf carries the `%latest` sentinel; `Lowerer.scalar` renders `TypedCLatestDate` as `SqlExpr.TimestampLit("9999-12-31 00:00:00.0000")`, producing `TIMESTAMP'9999-12-31 00:00:00.0000' as "k_businessDate"`. Every engine golden spells this column as the dialect-INVARIANT string `'9999-12-31T00:00:00.0000+0000'` (identical in both the legacy-H2 and the H2-2.1.214 arm of assertEqualsH2Compatible), i.e. Pure's `toString` of the LatestDate sentinel routed through the default `'%s'` literal processor — NOT a date literal. The Lowerer's own comment states this contract and then emits the other spelling. Note the milestoning PREDICATE (`thru_z = TIMESTAMP'…'`) is a separate path that reads the table's declared INFINITY_DATE and is already correct.

**Fix**

(A) Do NOT delete ImplicitInheritance — it is load-bearing: `ProductWithConstraint2`'s constraint reads `$this.classification(%2019-1-1)` and that class mapping declares only `name`/`referenceSystem` (businessDateMilestoningSetUp.pure ProductWithConstraint2 block), so the inherited PM is needed for property RESOLUTION. legend-engine gets this the same way: routing walks generalizations (`meta::pure::router::routing::getClassMappings`, routing.pure:1021) while the SELECT column list does not. So make the merge PROVENANCED instead of silent:
  1. `ImplicitInheritance.apply` (ImplicitInheritance.java:78-99): collect the names of the PMs it appends into a `Set<String> inherited` and record it on the rebuilt `ClassMapping.Relational` (new field, or a parallel map keyed by set id carried into `MappingDefinition.ClassBinding`).
  2. Thread that set through `MappingDefinition.ClassBinding` -> `ClassSource` as `Set<String> inheritedBindings()`.
  3. `GraphEmission.synthesizeScalarTree` (GraphEmission.java:123-144): skip `e.getKey()` when it is in `cs.inheritedBindings()`, exactly as it already skips `ClassMapping.isSubTypeColumn` pseudo-bindings at :128. Explicit navigation/projection of the property still resolves through the binding — only the IMPLICIT bare-root envelope loses it, which is engine parity.
(B) In `RelationalRootForm.apply` (RelationalRootForm.java:100-112), when the k_-renamed generated-date leaf's body is a `TypedCLatestDate`, substitute a `TypedCString("9999-12-31T00:00:00.0000+0000")` (STRING-typed) leaf before it is added to `cols`. Do this in RelationalRootForm, NOT in `Lowerer.scalar`: RelationalRootForm is the flat golden-text surface only (Compiler.java:589 / StatementExecutor.java:447), so the JSON-envelope execution path keeps a real Date for the object's `businessDate` property and `testPopulationOfLatestMilestonedDateInQuery`'s `assertEquals([%9999-12-31T00:00:00.0000+0000,…], $products.businessDate)` keeps working.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4882 (root columns = dataTypePropertyMappings) and /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_PropertyMappingsImplementation.pure:21,37 (inheritance only via superSetImplementationId)

**Risk** — (A) is a real behaviour change for every subclass class mapping sharing a main table with its parent (milestoningmap's StockProduct, isolationFocusedMapping, the ProductWithConstraint family). Bare-root `.all()` envelopes for those classes will lose the inherited leaves — that is the intended engine-parity change, but any corpus test asserting an inherited property value off a bare `.all()` object of such a subclass will start failing and must be re-read, not patched around. Tenet-2 trap to avoid: do NOT special-case the property names in the harness's golden compare, and do NOT filter `stockProductName`/`classificationType` in RelationalRootForm — the wrong thing is the mapping model, not the renderer. (B) touching `Lowerer.scalar` instead of RelationalRootForm would change the executed envelope's businessDate value type and is the trap here.

**Also unblocks** — The (B) latest-literal fix is a candidate for the other %latest goldens in the same family — testLatestIgnoredForNonMilestonedMappedClassesAllQuery, testLatestIgnoredForNonMilestonedMappedBiTemporalClassesAllQuery, testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty, testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation — unverified, I did not read their failure details.

**Falsifier** — For (A): dump `ClassSource.bindings().keySet()` for (`milestoningmap`, `...::StockProduct`). If it does NOT contain `stockProductName` and `classificationType`, ImplicitInheritance is not the source and the extra columns come from somewhere else. For (B): grep the corpus for any golden that spells `TIMESTAMP'9999-12-31 00:00:00.0000' as "k_businessDate"` — if one exists, the string spelling is not universal.

<details><summary>Evidence read (17 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ImplicitInheritance.java:81 — `for (PropertyMapping pm : ancestor.propertyMappings()) { if (!own.contains(pm.propertyName()) && (!(pm instanceof PropertyMapping.Join j) || j.targetSetId() == null)) { merged.add(pm); ... } }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ImplicitInheritance.java:94 — the child ClassMapping.Relational is rebuilt with `merged` as its propertyMappings
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:249 — `md = ImplicitInheritance.apply(md, model);` runs for every mapping, right after resolveExtends
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/GraphEmission.java:123 — `synthesizeScalarTree(ClassSource cs)` iterates `cs.bindings()` and adds `new TypedGraphTree(e.getKey(), List.of())` for every non-class-typed binding
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/RelationalRootForm.java:100 — `for (TypedFuncCol leaf : g.leaves())` turns each leaf into a select column; :109 renames the generated date leaf to `k_businessDate`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2214 — `case TypedCLatestDate ignored -> new SqlExpr.TimestampLit("9999-12-31 00:00:00.0000")`, under a comment that says the engine golden projects `'9999-12-31T00:00:00.0000+0000'`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/TemporalFrame.java:1513 — the `%latest` PREDICATE arm builds `thruCol = <table INFINITY_DATE>`, a path independent of Lowerer's value-position rendering
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4882 — `r:RootRelationalInstanceSetImplementation[1] | $r->dataTypePropertyMappings()` is the root select's column source
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/helperFunctions/helperFunctions.pure:418 — `dataTypePropertyMappings` = `$impl->allPropertyMappings()` filtered to DataType-typed properties
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_PropertyMappingsImplementation.pure:21 — `superMapping` = `if($_this.superSetImplementationId->isEmpty(), |[], |...)`; there is NO class-hierarchy walk
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_PropertyMappingsImplementation.pure:37 — `allPropertyMappings` concatenates only `$superMapping->map(x|$x->allPropertyMappings())`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:586 — `meta::relational::tests::milestoning::StockProduct : Relational{ ~filter [db] IsStockType id, name, type, classification, exchange, synonyms, orders }` — no stockProductName, no classificationType, no `extends`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:555 — `stockProductName : [db]@Product_StockProduct > [db]@StockProduct_Description | ProductDescriptionTable.description` is declared only on Product's class mapping
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/milestoningModel.pure:290 — `Class <<temporal.businesstemporal>> ...::StockProduct extends ...::Product`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/testBusinessDateMilestoning.pure:353 — the golden for this test: only `id, name, type` plus `'9999-12-31T00:00:00.0000+0000' as "k_businessDate"`, versus :265 where the same query on Product DOES carry stockProductName/classificationType
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:155 — H2 2.1.214 renders a `Date` literal as `TIMESTAMP'%s'` via convertDateToSqlString (yyyy-MM-dd HH:mm:ss…), which can never produce the `T…+0000` spelling in the golden
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/Compiler.java:589 — RelationalRootForm is applied only behind the `relationalRootForm` flag (the toSQLString surface); execution keeps the envelope

</details>

---

## `testViewChainsWithBusinessDate`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | medium |

**Root cause**

The wall is honest and the diagnosis is a missing PLATFORM surface, not a harness gap.
The test builds its SQL as `toSQL(|OrderPnl.all($businessDate)->project(…), ViewChainMapping, testRuntime(), extensions).toSQLString($connection.type, $connection.timeZone, $connection.quoteIdentifiers, ^Format(newLine='', indent=''))`. legend-lite registers exactly one `toSQLString` native — the 4-arg `(f:Function, mapping, databaseType, extensions)` form — and no `meta::relational::functions::sqlstring::toSQL` at all, so there is no overload whose first parameter is a SQLResult. `ExecCallFinder.findTerminal` correctly stops at the outer `toSQLString` AppliedFunction and hands it to `evalScalar`, which throws on the unresolvable signature; `sideSqlText` catches and returns null, so `sqlTextVerify` falls through to the h2 row-replay, which declines and returns the advisory marker. `Runner.score` then sees verified==0, no sqlDiffs, advisory>0 and emits the SHAPE wall verbatim.
Importantly, this test's golden is NOT an engine-internals golden: `toSQL(f, mapping, runtime, extensions)` DOES apply `sqlQueryDefaultPostProcessors()` including `replaceAliasName`, which is why its golden spells `producttableview_0`, `intermediate_0`/`intermediate_1`, `intertwoview_0` — exactly legend-lite's own alias convention. So unlike tests 2 and 3, this one is reachable once the surface exists.

**Fix**

Implement the surface in PLATFORM code; no harness change is needed once it exists (ExecCallFinder already reaches it).
  1. `PlatformTypes` (add next to TO_SQL_STRING at PlatformTypes.java:155): `TO_SQL = "meta::relational::functions::sqlstring::toSQL"`.
  2. `Pure.java` (next to :1639): register `native function meta::relational::functions::sqlstring::toSQL(f:Function<{->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):Any[1]` returning an opaque SQLResult handle (same pattern the plan surface already uses per PlatformTypes.java:173's "PLATFORM-OWNED opaque handle + K-native"), plus the rendering overload `toSQLString(sqlResult:Any[1], databaseType:Any[1], dbTimeZone:Any[0..1], quoteIdentifiers:Any[0..1], format:Any[1]):String[1]`.
  3. `StatementExecutor` (extend the arm at :205-213): recognise `toSQLString(toSQL(f, mapping, runtime, ext), type, tz, quote, format)` and route it into the SAME `toSqlString(...)` / `engineSql(...)` pipeline used today, taking the DatabaseType from the runtime's connection instead of an explicit enum argument. This is exact parity: legend-lite's EngineStyleH2 already applies the reAlias convention unconditionally, which is precisely what `toSQL`'s default post-processors add over the bare 4-arg toSQLString. Honour `^Format(newLine='', indent='')` as the unformatted spelling (the same normalization sqlRemoveFormatting yields).
Do NOT special-case `toSQL` inside ExecCallFinder.sideSqlText — that would be tenet-2 harness compensation for an unimplemented platform native.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/toSQLString.pure:46-53 (toSQL + default post-processors) and :41-44 (the identical toSQL(...).toSQLString(conn…, Format) composition)

**Risk** — Implementing the surface only removes the wall; the test may then FAIL on content rather than pass, because the golden also exercises view-chain milestoning (a milestoned businessDate constant projected inside chained view sub-selects: `'2018-07-31' as "k_businessDate"` inside both `intertwoview_0` and `producttableview_0`). Budget a second pass for that. Also, registering a second `toSQLString` arity must not perturb overload resolution for the existing 4-arg call sites — the new one takes an opaque SQLResult in position 0, not a Function, so keep the dispatch keyed on the first argument's kind rather than on arity alone.

**Also unblocks** — Any other corpus test whose SQL is produced via `toSQL(...).toSQLString(...)` or `toSQLStringPretty(f, mapping, runtime, extensions)` (the runtime-taking overload, toSQLString.pure:41) would be unblocked by the same native. I did not enumerate them.

**Falsifier** — Set LL_SQLTEXT_DEBUG and run the test: ExecCallFinder.java:150 prints the caught exception from the assert side. If that message is anything other than an unresolved `toSQLString`/`toSQL` signature (e.g. a mapping or view-chain resolution failure), the missing native is not what walls this test.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/testBusinessDateMilestoning.pure:241 — the test body: `toSQL(|…, ViewChainMapping, testRuntime(), …).toSQLString($connection.type, $connection.timeZone, $connection.quoteIdentifiers, ^Format(newLine='', indent=''))`, golden aliases `producttableview_0` / `intermediate_0` / `intertwoview_0`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1639 — the ONLY toSQLString native: `(f:Function<{->Any[*]}>[1], mapping:Any[1], databaseType:Any[1], extensions:Any[*]):String[1]`; no `toSQL` native anywhere in builtin/
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ExecCallFinder.java:113 — `findTerminal(side, …, Set.of("execute", "toSQLString", "toSQLStringPretty"), …)` stops at the outer toSQLString
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ExecCallFinder.java:147 — `catch (RuntimeException | SQLException e) { … return null; }` — the unresolvable signature becomes a silent null side
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1986 — `case "assertSameSQL" -> … sqlTextVerify(af.parameters(), …)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1461 — `if (r.verified() == 0 && r.advisory() > 0) yield new Outcome(fqn, Status.SHAPE, "sql-only: " + r.advisory() + " advisory golden-SQL assert(s), no row verification");` — the exact wall text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:205 — the existing K-dispatch arm: `preRoot instanceof TypedNativeCall tsc && (PlatformTypes.TO_SQL_STRING.equals(…) || PlatformTypes.TO_SQL_STRING_PRETTY.equals(…)) -> result = toSqlString(tsc, specs, env);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:155 — `TO_SQL_STRING` / :162 `TO_SQL_STRING_PRETTY` are the only registered sqlstring surfaces
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/transform/fromPure/toSQLString.pure:46 — `toSQL(f, mapping, runtime, extensions):SQLResult[1]`, whose body builds `postProcessors` from `sqlQueryDefaultPostProcessors()`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/transform/fromPure/toSQLString.pure:43 — the engine's own `toSQLStringPretty(f, mapping, runtime, extensions)` is literally `toSQL($f,$mapping,$runtime,$extensions).toSQLString($databaseConnection.type, $databaseConnection.timeZone, $databaseConnection.quoteIdentifiers, ^Format(...))` — the same composition this test writes by hand

</details>

---

## `testReplaceTablePostProcessorWithExists`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | S |
| confidence | high |

**Root cause**

Alias-group derivation happens at render time from the POST-rename table name. `SqlPostProcessors.source()` (SqlPostProcessors.java:223-227) rebuilds `new SqlSource.Table(nn, t.alias(), t.outputs())` — the physical name changes, the IR alias (e.g. `t7`) is preserved, but `SqlSource.Table` (SqlSource.java:44) carries no alias-group identity. `EngineStyleH2.render` (EngineStyleH2.java:219-231) then runs `planQuery`/`planSource` over the ALREADY-renamed tree, and `planSource`'s Table arm (:326-346) computes `String group = t.name()...toLowerCase()`, so the group becomes `differentpersontable` instead of `persontable`; the exists-subselect alias goes the same way through `firstInnerTable` (:415-428), which also keys on `t.name()`. Real legend-engine computes the alias map in `reAlias::replaceAliasName` (reAliasQuery.pure:26-42, from `$query->traverse()` pairs of table-name→alias) as part of `sqlQueryDefaultPostProcessors()`, which `defaultProcessors` folds BEFORE the connection hooks (defaultPostProcessor.pure:47-56) and long before `connectionAwareProcessors` (defaultPostProcessor.pure:36-44, connectionAwareProcessors.pure:25-31). Hence the golden keeps `persontable_0/_1` over a `differentPersonTable` source. Everything else in our text is byte-identical to the golden.

**Fix**

Make the engine-style alias plan run over the PRE-post-processor IR. Smallest correct change: give `EngineStyleH2` a two-tree entry point — `public String render(SqlQuery toRender, SqlQuery aliasPlanSource)` (EngineStyleH2.java:219-231) that does `toRender = wrapTdsJoinTop(toRender); aliasPlanSource = wrapTdsJoinTop(aliasPlanSource); … planQuery(aliasPlanSource, new LinkedHashMap<>()); query(sb, toRender, 0);`, with the existing one-arg `render(q)` delegating as `render(q, q)`. This is sound because `SqlPostProcessors.source()` preserves `t.alias()`/`sub.alias()` verbatim (SqlPostProcessors.java:220-247), so a plan keyed by alias built on the pre-rename tree applies unchanged to the renamed tree; `subselects` (populated at :376) is only read for `frameName()`/`outputs()` (:601-612), neither of which the rename touches. Then change the two callers that rename before rendering to pass both trees: StatementExecutor.java:388-395 (`renderer.render(post, es.plan())`) and StatementExecutor.java:470-478 in `engineSql` (capture the pre-`SqlPostProcessors.apply` plan and pass it as the alias source, so the relationalMapper rename channel gets the same treatment). Alternative if you prefer the property to live on the data: add a nullable `aliasGroup` to `SqlSource.Table` (SqlSource.java:44) defaulted from `name`, have `SqlPostProcessors.source()` carry the OLD group through the rename, and have `planSource`/`firstInnerTable` read `t.aliasGroup()` — more construction sites to touch, but it survives any future renderer. Do NOT 'fix' this by normalizing aliases in the harness comparison.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:47-56 (replaceAliasName folds before the connection's post-processors) and .../postprocessor/defaultPostProcessor/reAliasQuery.pure:26-42 (the alias map itself)

**Risk** — The two-tree render must keep `wrapTdsJoinTop` applied identically to both trees or the alias plan and the rendered tree disagree about the synthetic `tdswrap__` node. Any renderer state derived from names other than the alias map (e.g. `quotedFrameRead` via `subselects`) must keep reading the RENDERED tree, not the plan source — only `planQuery` moves. Tenet-2 trap: the tempting shortcut is to make the harness compare aliases case/number-insensitively, or to add `differentpersontable`→`persontable` normalization; that would hide a genuine engine-parity divergence.

**Also unblocks** — testReplaceTablesPostProcessor (its SQL-text half), testToSqlStringReplaceTablesPostProcessor (once its harness gate is opened), and the alias half of testReplaceTablePostProcessorWithView

**Falsifier** — If the observed 'got' for this test differed from the golden in ANY way other than the `persontable_N`→`differentpersontable_N` alias spelling, the alias-timing story is not the whole cause. Cheapest check: diff docs/RELATIONAL_CORPUS_ALL.md:1345's expected vs got token-by-token after substituting `differentpersontable`→`persontable` — they must become identical (they do, by inspection).

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:326-346 — planSource's Table arm: `String group = t.name().substring(t.name().lastIndexOf('.')+1).toLowerCase(Locale.ROOT)` then `renames.put(t.alias(), nextInGroup(group, groups))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:415-428 — firstInnerTable(): a subselect's alias group is its leftmost inner Table's lowercased `t.name()`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:219-231 — render(SqlQuery): clears state, `planQuery(query, …)` then `query(sb, query, 0)` — the plan is computed on whatever tree it is handed
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:220-227 — source(): the Table arm swaps only the NAME, keeping `t.alias()`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/SqlSource.java:44 — `record Table(String name, String alias, List<OutputCol> outputs)` — no alias-group / original-name field
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:388-395 — toSqlString: `SqlPostProcessors.apply(es.plan(), PostProcessBoundary.tableReplace())` and only then `renderer.render(post)` — rename strictly precedes the alias plan
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:47-56 — defaultProcessors: `sqlQueryDefaultPostProcessors()->fold(...)` first, `$connectionProcessors->fold(...)` second
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:58-68 — sqlQueryDefaultPostProcessors() list contains `meta::relational::postProcessor::reAlias::replaceAliasName`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/connectionAwareProcessors.pure:25-31 — connectionAwareProcessors folds `sqlQueryPostProcessorsConnectionAware` over the query it RECEIVES (already re-aliased)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:26-42 — replaceAliasName builds table→alias groups from the query's CURRENT table names and rewrites aliases
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1345 — the untruncated observed 'got': identical to the golden except every `persontable_N` is spelled `differentpersontable_N`

</details>

---

## `testReplaceTablePostProcessorWithView`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

NOT a post-processor defect — the rename is applied correctly (our text shows `differentPersonTable`, and `firmTable`, which is not in the pair, is untouched). The divergence is legend-lite's handling of a class whose main source is a VIEW with a join. `relationalMappingWithViewAndInnerJoin` maps `Person` with `scope([db] PersonFirmView)` (testView.pure:100-116) over `View PersonFirmView (PERSON_ID: personTable.ID, lastName: personTable.LASTNAME, firm_name: @Firm_Person | firmTable.LEGALNAME)` (relationalSetUp.pure:31-36). The engine materializes that view as a NAMED derived table `(select "root".ID as PERSON_ID, "root".LASTNAME as lastName, "firmtable_0".LEGALNAME as firm_name from <personTable|differentPersonTable> as "root" left outer join firmTable as "firmtable_0" on …) as "personfirmview_0"`, INNER-joins salesPersonTable to it (the mapping's `> (INNER) [db] @SalesPerson_PersonView`), and wraps that in the distinct exists-keys select. legend-lite instead FLATTENS the view: our exists subselect reads `from differentPersonTable as "differentpersontable_1" left outer join firmTable … left outer join salesPersonTable as "salespersontable_0" on (…)` — no `personfirmview_0` frame, no inner join, one nesting level fewer. `resolver/ViewFrames.frameNameOf` exists and is wired only for ASSOCIATION-JOIN targets (StoreResolver.java:888, :1967, :2006); the scope()-mapped view as a class's MAIN source does not take the frame path here. This is the identical shape (modulo the rename) produced by the sibling test testViewSimpleExists (testView.pure:78-83), which is failing in another unit with the same 'got' — that test, not this one, owns the fix.

**Fix**

Do not fix this test directly — ledger it behind testViewSimpleExists (tests/query), which is the same defect without the post-processor confound and is where the fix must be verified. The fix site is the resolver's main-source handling: a `ClassSource` whose main table resolves to a View (`ViewFrames.frameNameOf(ctx, cs)` non-null) must lower to a `SqlSource.Subselect(viewSelect, alias, frameName=<viewName>)` — the same frame construction StoreResolver.java:888/:1967 already performs for association-join targets — instead of splicing the view's own columns and joins into the enclosing select; and the mapping's `> (INNER)` join qualifier on `pnlContact` must lower as an INNER join to that frame rather than a LEFT OUTER join to the flattened tables. Once that lands, this test additionally needs the alias fix from testReplaceTablePostProcessorWithExists (our frame would otherwise be grouped `differentpersontable_*` where the golden says `salespersontable_*`/`personfirmview_0`). Nothing in SqlPostProcessors needs to change for this test.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:47-52 — the engine's traverse() has an explicit `v:ViewSelectSQLQuery[1] | pair($relElement->cast(@Table).name, $alias.name)` arm, i.e. a view IS a distinct aliased relational element in the engine's SQL metamodel, never inlined away

**Risk** — Turning a flattened view into a materialized frame changes join cardinality and column visibility for every view-mapped class — it will move many other view-family goldens, in both directions. The INNER-vs-LEFT-OUTER difference is a genuine semantic change (our form keeps view rows with no salesPerson and relies on the outer `is not null` to discard them; that happens to be row-equivalent here but is not in general). Tenet-2 trap: do not special-case `scope(View)` in the golden comparison or 'fix' it by teaching the harness to ignore nesting levels.

**Also unblocks** — testViewSimpleExists (tests/query); likely other members of the view family that assert a materialized view frame (testViewPropertyFilterWithPrimaryKey shape)

**Falsifier** — If testViewSimpleExists (tests/query) turns out to have a DIFFERENT 'got' shape than this test after substituting `personTable`→`differentPersonTable`, then this test has a cause of its own and cannot be ledgered behind it. Compare docs/RELATIONAL_CORPUS_ALL.md:1346 against :1536 — they must be identical modulo that substitution and the induced alias spellings.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1346 — untruncated 'got' for this test: `… (select distinct "salespersontable_0".ACCOUNT_ID from differentPersonTable as "differentpersontable_1" left outer join firmTable as "firmtable_0" on (…) left outer join salesPersonTable as "salespersontable_0" on (…) where "firmtable_0".LEGALNAME = 'Johnson') as "differentpersontable_0"` — the rename landed, the view frame did not
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1536 — testViewSimpleExists's 'got' is the SAME flattened shape with `personTable as "persontable_1"`, proving the view flattening is independent of the post-processor
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ViewFrames.java:17-95 — frameNameOf() resolves a class's main-source view name (handles both `~mainTable` and scope()-distributed column PMs)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:888 and :1967 and :2006 — the only call sites: `ViewFrames.frameNameOf(ctx, aj.target())`, i.e. association-JOIN targets only
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/query/testView.pure:100-116 — Mapping relationalMappingWithViewAndInnerJoin: `Person : Relational { scope([db] PersonFirmView) ( lastName : lastName, firm ( legalName:firm_name ) ) }` and `pnlContact : [db] @Order_SalesPerson > (INNER) [db] @SalesPerson_PersonView`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/query/testView.pure:78-83 — testViewSimpleExists: the identical query and mapping WITHOUT the post-processor, same golden shape
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:31-36 — View PersonFirmView with the @Firm_Person join that becomes the golden's inner derived table
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:349-376 — planSource's Subselect arm shows the renderer already knows how to group a NAMED frame (`sub.frameName()` → `personfirmview_0`); the frame is simply never built for this mapping shape

</details>

---

## `testReplaceTablesPostProcessor`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

TWO independent causes stacked; the REPORTED one is a wrong-rows defect. (1) ROWS — the connection rename map is scoped to the `execute()` call and is LOST on the `$result.values` re-read. `buildFrame` computes the map and rebinds a LOCAL `env` (StatementExecutor.java:2214-2229); the eager run uses that local env (`run = executeTyped(body, env)` at :2278) so the first execution IS renamed; but `ExecFrame` (record at :2054, constructed at :2280) carries only `(chain, relationRooted, result)` — no env, no map. The harness's row verification evaluates `$result.values` (EngineTestExecutor.java:1070-1073), which reaches `spliceValuesRead` (:2700-2703) and returns the BARE `f.chain()`; that chain is then lowered and executed under the AMBIENT `ExecEnv`, whose `tableReplace` is the `Map.of()` default from the historical-arity constructor (:52-69, entry construction at :40-44), and StatementExecutor.java:3205-3209 applies `SqlPostProcessors.apply(plan, env.tableReplace())` = no rename. So the re-read queries `firmTable`/`personTable` and returns 7 real rows, while the golden (correctly renamed) hits `otherFirmTable`/`differentPersonTable`, which relationalSetUp.pure:1394/1421 create and never seed → 0 rows. (2) TEXT — this test is relation-rooted (`->project(...)`), so unlike its class-rooted siblings it is Tabular and the H2 oracle actually verifies; its SQL text also diverges from the golden by the shared alias defect (`differentpersontable_0` vs `persontable_0`), which is why sqlTextVerify took the divergent-text branch (EngineTestExecutor.java:1004-1013) and surfaced the row divergence rather than a text diff.

**Fix**

Carry the rename map on the frame and apply it wherever a frame is spliced. (a) Hoist `tr` out of the `if (ec.args().size() >= 3)` block in buildFrame (StatementExecutor.java:2214-2229) and widen the record to `record ExecFrame(TypedSpec chain, boolean relationRooted, @Nullable ExecutionResult result, Map<String,String> tableReplace)`, returning it at :2280. (b) In `executeStatements`, in the generic-statement path (the block starting at :185 that builds `single`/`stmtInliner` and ends at the `executeTyped` tail), and in the effectful-let path at :153-166, compute the effective map before executing: walk `execFrames` and, for every frame variable the ORIGINAL statement references (`referencesVar(stmt, name)` already exists at :2757), union its `tableReplace()`; if two frames bind the same key to different values, THROW (never silently pick one). Build `ExecEnv senv = tr.isEmpty() ? env : new ExecEnv(env.ctx(), env.runtimeFqn(), env.dialect(), env.connection(), env.rawSqlFailureSink(), env.addDriverTablePk(), env.queryLets(), tr)` and pass `senv` down so the `env.tableReplace()` read at :3208 sees it. (c) `spliceValuesRead`'s inline-execute arm (:2711-2720) builds a non-eager frame whose map is currently discarded — feed it into the same union. Do NOT fix this by having the harness reuse `ExecFrame.result()` instead of re-planning: the re-plan is the architecture, and the map is the platform's to thread. Separately, note that `PostProcessBoundary` (exec/PostProcessBoundary.java:24-33) is a thread-local that survives a test with no execute() — once the frame carries the map, prefer narrowing or retiring that boundary rather than adding a second source of truth.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/connectionAwareProcessors.pure:25-31 — in the engine the hook is applied once, to THE query of THE execution, and `$result.values` is a materialized collection that is never re-planned, so the question of a second unrenamed execution cannot arise

**Risk** — Rebuilding `ExecEnv` per statement must not drop `queryLets` (it is a shared mutable accumulator — pass the SAME map instance, not a copy). Widening `ExecFrame` touches every construction/read site (:131, :332, :2280, :2417, :2702, :2717). If two frames in one statement carry conflicting renames the union is ambiguous — wall loudly rather than merge. Tenet-2 trap: the cheap-looking alternative (make `$result.values` return the cached eager `ExecutionResult`) hides the real hole and would silently change semantics for every read with operations stacked on top.

**Also unblocks** — Very likely testReplaceTablesPostProcessorJoinIsolation (tests/mapping/tree, tree.pure:229-241 — same replaceTables runtime, project-shaped so also row-verified, and its reported divergence is our rows carrying pre-rename data); more broadly every corpus test whose runtime carries a connection post-processor AND whose asserts read $result.values

**Falsifier** — Run this one test with the SQL dump enabled (`LL_TMP_SQL=1`, or add a temporary print at StatementExecutor.java:3207) and count the emitted statements. If only ONE SELECT is emitted, or every emitted SELECT names `otherFirmTable`/`differentPersonTable`, then the rename is not being lost on a re-read and the 7 rows have a different cause (next suspect: `tableReplaceMap` returning empty for the `[$pair1,$pair2]` collection shape at SqlPostProcessors.java:101-111).

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2214-2229 — buildFrame builds `tr` from the runtime arg, calls `PostProcessBoundary.record(tr)`, and rebinds a LOCAL `env` with `tr`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2054 — `record ExecFrame(TypedSpec chain, boolean relationRooted, @Nullable ExecutionResult result)` — no env and no rename map
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2261-2281 — the eager run uses the local (renamed) env, then `return new ExecFrame(chain, relationRooted, run)` drops it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2700-2703 — spliceValuesRead: `ExecFrame f = valuesFrame(n, execFrames); if (f != null) return f.chain();` — the bare chain, no map
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:3205-3209 — the generic execution tail: `SqlPostProcessors.apply(plan, env.tableReplace())` reads the AMBIENT env
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:52-69 and :40-44 — ExecEnv's historical-arity constructor defaults `tableReplace` to `Map.of()`; the session entry point uses that arity, and nothing else ever sets it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1069-1079 — h2Upgrade evaluates `new AppliedProperty(new Variable(var), "values")` and hands the rows to H2Verify.verifyAuto
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/H2Verify.java:363-366 — verifyOnSession throws Unverifiable on a non-Tabular frame, which is exactly why the class-rooted siblings (testReplaceTablePostProcessor, …WithExists) never expose this and only this project-shaped test does
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:1394 and :1421 — `dropAndCreateTableInDb(db,'differentPersonTable',…)` / `(db,'otherFirmTable',…)` with no inserts anywhere in the corpus → both tables are legitimately EMPTY, so 0 rows is the correct answer
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/CORPUS_STUDY_2026_08.md:283-289 — a prior LL_TMP_SQL=1 trace of THIS test showing executions 1–2 renamed and execution 3 (the .values re-read) not renamed; I re-derived the mechanism independently from the sources above

</details>

---

## `testToSqlStringReplaceTablesPostProcessor`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | M |
| confidence | medium |

**Root cause**

The assert is never attempted, so the test scores SHAPE rather than being verified. The body is `let sql = toSQLStringPretty(...); assertSameSQL(<golden>, $sql->replace('\n','')->replace('\t',''))`. `assertSameSQL` routes to `sqlTextVerify` (EngineTestExecutor.java:1986-1997), which calls `ExecCallFinder.sideSqlText` on the non-literal side. `sideSqlText` walks down with `through = Set.of("sqlRemoveFormatting","sql","toOne","at")` (ExecCallFinder.java:114-118) and bails to null the moment it meets an intermediate call outside that set (:81-84) — `replace` is not in it, deliberately (the comment at :44-49: a chain carrying a TRANSFORM must not resolve to its generator as if the transform weren't there). So `sql == null`, sqlTextVerify falls through to `h2Upgrade` (:1014), which needs a root EXEC variable; `let sql = toSQLStringPretty(...)` contains no execute(), so the harness filed it in `lets` (EngineTestExecutor.java:468) not `execVars`, `rootExecVar` returns null, and h2Upgrade declines with ADVISORY_MARKER (:1062-1067). That counts as advisory (:896-899), and Runner scores verified=0 & advisory=1 as SHAPE 'sql-only: 1 advisory golden-SQL assert(s), no row verification' (Runner.java:1461-1465), which the no-execute arm reports as the 'wall' (Runner.java:1311-1315). Behind that gate sits a REAL platform gap: `toSqlString` (StatementExecutor.java:359-395) uses its arg-2 runtime ONLY for `databaseTypeOf` (:364-368; which itself defaults to "H2" when the runtime is an un-inlined TypedUserCall, :1149-1152) and takes its rename map from `PostProcessBoundary.tableReplace()` (:390-392) — a thread-local written only by `execute()` (:2222). This test never calls execute(), so toSQLStringPretty would render the ORIGINAL tables (and, worse, whatever map the previous test on this thread left behind: exec/PostProcessBoundary.java:24-29 is never cleared per test).

**Fix**

Two changes, platform first. (1) PLATFORM (required, and correct independent of this test): `toSqlString` (StatementExecutor.java:359-395) must derive its post-processor renames from ITS OWN runtime argument, exactly as buildFrame does at :2214-2222 — i.e. `TypedSpec rtArg = call.args().get(2); if (rtArg instanceof TypedUserCall) rtArg = new UserCallInliner(specs).inlineBody(List.of(rtArg)).get(0); Map<String,String> tr = SqlPostProcessors.tableReplaceMap(rtArg);` — and use `tr` at :390 instead of `PostProcessBoundary.tableReplace()`, falling back to the boundary only when arg 2 is a DatabaseType enum rather than a runtime (the enum overload has no hooks to read). Inlining the runtime arg also fixes `databaseTypeOf` silently defaulting to "H2" for a helper-call runtime (:364-368/:1149-1152). Factor the extract-hooks-from-a-runtime-argument step out of buildFrame so both callers share one implementation. (2) HARNESS: `ExecCallFinder.sideSqlText` (:105-157) must APPLY a literal text-normalizing tail instead of refusing it — while walking down, collect `replace(x, <CString from>, <CString to>)` frames (both args literal, else keep refusing), add `"replace"` to `through`, and apply the collected pairs in order to the generated string before returning it. This is not harness compensation: the transform is applied, not ignored, and the shape it unblocks (`toSQLStringPretty(...)->replace('\n','')`) is pure golden-text normalization the corpus uses in several families. With both in place the compare runs and this test becomes gated on the alias fix from testReplaceTablePostProcessorWithExists (its golden is the same string as testReplaceTablesPostProcessor's).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/connectionAwareProcessors.pure:25-31 — the hooks are read from the RUNTIME's connection for the query being generated; the engine's toSQLString runs the same defaultPlanPostProcessors pipeline (defaultPostProcessor.pure:27-33) against the runtime it was handed, with no execute()-scoped side channel

**Risk** — Adding `replace` to `through` without applying the collected pairs would silently compare untransformed text against a transformed contract — exactly the failure the current guard exists to prevent; the pair collection and the refusal-on-non-literal must land in the same change. On the platform side, reading hooks from toSQLString's own runtime argument will start applying renames in every toSQLString test whose runtime carries them — check that no currently-passing golden depends on toSQLString ignoring them (the boundary-fed path means some may currently pass by accident, and some may currently pass by inheriting a stale thread-local, which is itself a latent cross-test leak worth closing in the same change).

**Also unblocks** — Any corpus assert of the form assertSameSQL(golden, <generated text>->replace(...)) — the same ExecCallFinder guard silently advisories all of them; and every toSQLString/toSQLStringPretty test whose runtime argument carries connection post-processors

**Falsifier** — Set LL_SQLTEXT_DEBUG=1 and run this one test. If `[sql-text] side unverifiable: …` prints an EXCEPTION, then sideSqlText reached evaluation and the `through` guard is not the gate (the cause would be a platform wall inside toSQLStringPretty instead). If nothing prints at all, findTerminal returned null before the try block — confirming the `through`-set diagnosis.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ExecCallFinder.java:114-121 — sideSqlText's `through` set is {sqlRemoveFormatting, sql, toOne, at}; `replace` is absent
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ExecCallFinder.java:78-87 — `if (through != null && !through.contains(simpleName(af.function()))) return null;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1986-1997 — assertSameSQL → sqlTextVerify
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1004-1014 — when `sql == null` sqlTextVerify falls straight through to h2Upgrade
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1062-1067 — `rootExecVar` null → h2Decline("no root exec variable in the actual arg") → ADVISORY_MARKER
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:896-899 — ADVISORY_MARKER increments counters[1] and returns null (no failure recorded)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:463-469 — a let whose rhs neither contains execute() nor references an exec var goes to `lets.put(...)`, never to execStmts/execVars
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1461-1465 — verified==0 && advisory>0 → SHAPE "sql-only: N advisory golden-SQL assert(s), no row verification"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1301-1315 — the no-execute arm appends the attempted run's outcome as " — wall: …", which is exactly the reported string
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:359-395 — toSqlString: arg 2 feeds only databaseTypeOf; the rename map comes from PostProcessBoundary.tableReplace()
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1149-1152 — databaseTypeOf returns "H2" when no connection NewInstance is found (an un-inlined runtime helper call)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/PostProcessBoundary.java:24-33 — a ThreadLocal written only by execute(); a test with no execute() inherits the previous test's map

</details>

---

## `testParseDate`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Two independent defects stack; the OBSERVED wall is defect A.

A (the wall). legend-lite dispatches the SQL-text K-natives only when they sit at the ROOT of a statement. StatementExecutor.java:196-212 unwraps TypedLet / TypedFrom / foldPairProjection to get `preRoot`, then tests `preRoot instanceof TypedNativeCall` with callee == PlatformTypes.TO_SQL_STRING or TO_SQL_STRING_PRETTY. testParseDate's body is `let sql = toSQLStringPretty(...)` then `assert($sql->contains('parsedatetime'))`. The harness binds lets SYNTACTICALLY, never as evaluated values (EngineTestExecutor.java:2556 `ValueSpecification spliced = subst(expr, lets)`; HarnessSubstitution.java:66-68 chases Variables into their RHS recursively), so the expression that actually reaches the platform is `contains(toSQLStringPretty(|Person.all()->project(..)->extend(..), simpleRelationalMapping, testRuntime(), ext), 'parsedatetime')`. `preRoot` is therefore TypedNativeCall(contains) — not a SQL-text native — so the dispatch at StatementExecutor.java:205-211 misses, and control falls through to `new StoreResolver(...).resolve(body, env.runtimeFqn())` at line 334. The resolver never descends into a toSQLStringPretty query lambda (that is what the pre-H arm exists for), so the TypedGetAll inside survives, and the post-condition check throws at StoreResolver.java:223-229. The ancestry path in the failure detail, `root > TypedNativeCall > TypedNativeCall > TypedLambda > TypedExte[nd]`, is exactly contains → toSQLStringPretty → the query lambda → extend, which confirms this reading. The precedent that this dispatch is root-only-by-construction is StatementExecutor.java:223-245: a one-off hand-written arm for `replace(planToString(...), a, b)` — the same nesting problem, solved for exactly one function.

Why it landed as SHAPE rather than ERROR: Runner.executeMappingRefs (Runner.java:839; executeShape name list at 879-880) recognizes `execute`/`toSQLString`/validate/scanColumns/... but NOT `toSQLStringPretty`, so mappingRefs is empty and the test takes the tryRunNoExecute branch (Runner.java:1097-1172), whose exception handler stamps the platform message as the `— wall:` suffix of the SHAPE row (Runner.java:1307-1315). That is a labelling detail, not the cause: the same statement would throw identically on the mappingRefs path.

B (the assert would still fail after A). The test asserts the rendered SQL contains the literal 'parsedatetime'. legend-lite lowers the 1-arg `meta::pure::functions::string::parseDate(String[1])` to a bare SQL cast: Scalars.java:2061-2084 — for a StringLit it pads a partial time and returns `new SqlExpr.Cast(in, PureSql.type(DATE_TIME))`, i.e. `cast('2023-01-01 ...' as timestamp)`. No `parsedatetime` token is ever produced. The engine renames the 1-arg pure parseDate to the `toTimestamp` dynafunction with a fixed format (processParseDate, pureToSQLQuery.pure:3241-3246), and H2 spells `toTimestamp` as `cast(parsedatetime(x, <fmt>) as timestamp)` (h2Extension2_1_214.pure:268 + transformToTimestampH2 549-555). The corpus's own H2 goldens for this pin it: testSqlFunctionsInMapping.pure:55 expects `cast(parsedatetime("root".string2date, 'yyyy-MM-dd HH:mm:ss') as timestamp)`.

**Fix**

Two changes, both platform-side.

FIX A — generalize the SQL-text dispatch from root-only to a subtree fold. In StatementExecutor.java, after `preRoot = foldPairProjection(preRoot);` (line 204) and BEFORE the existing root guard at 205, insert a recursive fold, e.g. a new private static helper `foldSqlTextNatives(TypedSpec n, SpecCompiler specs, ExecEnv env)` that does `n = n.mapChildren(c -> foldSqlTextNatives(c, specs, env));` and then, if `n` is a TypedNativeCall whose callee qualifiedName is PlatformTypes.TO_SQL_STRING or TO_SQL_STRING_PRETTY, returns `new TypedCString(((ExecutionResult.Scalar) toSqlString((TypedNativeCall) n, specs, env)).value().toString(), ExprType.one(Type.Primitive.STRING))`; same for PLAN_TO_STRING / PLAN_TO_STRING_WITHOUT_FORMATTING via planToString(). Then:
  TypedSpec foldedRoot = foldSqlTextNatives(preRoot, specs, env);
  if (foldedRoot != preRoot) { preRoot = foldedRoot; /* fall through */ }
and let the existing hostChannel/HostEval path evaluate the now-literal expression (`contains('<sql text>', 'parsedatetime')` is a pure host string op). Concretely: hoist the hostLets construction out of hostChannel (StatementExecutor.java:490-512) into a small helper and, when foldedRoot != preRoot, call `HostEval.evalToResult(foldedRoot, env.ctx(), specs, hostLets)` and `continue`. This makes the hand-written `replace(planToString(...))` arm at 223-245 and the planToStringWithoutFormatting arm at 248-260 redundant — delete both in the same change so there is one rule, not three.

FIX B — spell the 1-arg parseDate the way the engine does, in the ENGINE-STYLE dialect only. Add `SqlFn.PARSE_DATE_DEFAULT` (or reuse STRPTIME with the engine's default FormatLit: YEAR4,'-',MONTH2,'-',DAY2,' ',HOUR2,':',MIN2,':',SEC2 — h2Pattern (EngineStyleH2.java:1534-1560) already maps exactly that to "yyyy-MM-dd HH:mm:ss"). In Scalars.java:2063-2084 change the 1-arg arm to return `new SqlExpr.Cast(SqlExpr.Call.of(<that fn>, in), PureSql.type(Type.Primitive.DATE_TIME))` instead of a bare Cast on `in`. Spell it in exactly two places: EngineStyleH2.call() → `cast(parsedatetime(<arg>, 'yyyy-MM-dd HH:mm:ss') as timestamp)` (matching testSqlFunctionsInMapping.pure:55), and DuckDb/H2 (the EXECUTION dialects) → the current `cast(<arg> as timestamp)` so executed rows are byte-identical to today. Keep the existing literal T→space padding so the exec spelling is unchanged.

OPTIONAL (labelling only, not required to pass): add `simple.equals("toSQLStringPretty")` to Runner.java:879-880's executeShape list so these tests take the ordinary path and are scored ERROR/FAIL rather than SHAPE. Do this only after FIX A, otherwise it just relabels a wall.

**How legend-engine does it** — processParseDate at /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:3241 (1-arg pure parseDate → dyna 'toTimestamp' + format 'YYYY-MM-DD HH24:MI:SS'), spelled by transformToTimestampH2 at .../sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:549 as `cast(parsedatetime(x, <fmt>) as timestamp)`; the dispatch table entry is pureToSQLQuery.pure:10254.

**Risk** — FIX A: folding a SQL-text native to a literal STRING inside an arbitrary expression means the text-generation side effect now happens for sub-expressions that a lazy consumer might never have read. That is the engine's own semantics (toSQLString is a pure function), so it is safe, but a fold that throws must propagate the SAME NotImplementedException it does today — do not swallow it into a null and silently fall through to StoreResolver, or the wall becomes a wrong answer. FIX B: any goldens that currently expect legend-lite's bare `cast(x as timestamp)` for a 1-arg parseDate in the ENGINE-STYLE channel would flip; the exec dialects must keep the bare cast or DuckDB execution of parseDate over partial literals ('2015-04-15T17') regresses — that is precisely what the padding at Scalars.java:2069-2079 protects. Tenet-2 trap: do NOT widen EngineTestExecutor.isSqlText (line 2420) to treat toSQLStringPretty-derived text as advisory. That would make this test hollow-PASS without the platform ever computing anything — harness compensation for a platform gap.

**Also unblocks** — Every corpus test that consumes toSQLString/toSQLStringPretty/planToString text through a host string function other than the two hand-enumerated ones (contains/startsWith/toLower/indexOf/length chains). FIX A also lets the two hand-written arms at StatementExecutor.java:223-260 be deleted. FIX B additionally targets meta::relational::tests::mapping::sqlFunction::parseDate::testToSQLStringWithParseDateInQueryForH2 and ::testToSQLStringParseDateForH2 (testSqlFunctionsInMapping.pure:52-65), whose goldens are the same spelling.

**Falsifier** — For A: run this one test with LL_TMP_DEBUG=1 and print `preRoot.getClass().getSimpleName()` at StatementExecutor.java:204. If it is TypedNativeCall(toSQLStringPretty) rather than TypedNativeCall(contains), the let was evaluated eagerly and my substitution story is wrong. For B: after A alone, render the query — if the emitted H2 text already contains 'parsedatetime', defect B does not exist and A alone fixes the test.

<details><summary>Evidence read (13 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:196 — comment 'toSQLString dispatches PRE-H'; the guard at 205-211 requires preRoot ITSELF to be the TO_SQL_STRING / TO_SQL_STRING_PRETTY native
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:334 — the fall-through: `body = new StoreResolver(env.ctx(), specs)...resolve(body, env.runtimeFqn()); // Phase H`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:224 — `if (n instanceof TypedGetAll ga) throw new NotImplementedException("store resolution left getAll(" + ga.classFqn() + ") unresolved ... [at " + path + "]")` — the exact message and the `root > <ClassSimpleName> > ...` path builder at 236
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:223 — the hand-written one-off arm for `replace(planToString(...), from, to)`: proof the nested-consumer case is enumerated per-function, not general
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2556 — `ValueSpecification spliced = subst(expr, lets);` inside eval(): the let is inlined as an EXPRESSION before compilation
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2420 — isSqlText() only recognizes `sqlRemoveFormatting`/`sql`, so `assert($sql->contains(..))` is NOT routed to the advisory channel at 1791-1796 and is evaluated for real
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:879 — executeShape lists execute/toSQLString/validate/... but never toSQLStringPretty, so mappingRefs is empty for this test
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1310 — builds the `no execute(|...) call [calls ...] — wall: <platform message>` SHAPE detail seen in the brief
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2063 — `for (String f : Pure.nativeKeysAt("parseDate"))` … returns `new SqlExpr.Cast(in, PureSql.type(Type.Primitive.DATE_TIME))` (line 2083): no parsedatetime call is ever built
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1514 — the STRPTIME arm is the ONLY producer of the literal "parsedatetime", and it is reached only from the FORMAT-carrying dynafunctions (Scalars.java:2093-2101), never from 1-arg parseDate
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:3241 — processParseDate: appends `'YYYY-MM-DD HH24:MI:SS'` and calls `processDynafuncWithRename('toTimestamp', ...)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:549 — transformToTimestampH2 returns `'cast(parsedatetime('+$params->at(0)+','+ $timestampFormat+') as timestamp)'`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:55 — corpus golden `select cast(parsedatetime("root".string2date, 'yyyy-MM-dd HH:mm:ss') as timestamp) as "timestamp" from dataTable as "root"`

</details>

---

## `testRestrictDistinct_NoOptimization_WindowColumns`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

The test is 100% golden text (three toSQLString calls, three assertEquals on the string; no row assert anywhere). The expected string and the observed string share the prefix `select distinct "root".LASTNAME as ` and the truncated capture stops there, so the divergence is downstream of that point. Everything about the SHAPE is reproduced correctly by legend-lite — I traced it:

- The engine deliberately does NOT apply the restrict/distinct join-cut-down here: shouldOptimizeRestrictDistinct (pureToSQLQuery.pure:6575-6594) requires `$columns->forAll(c | $c->instanceOf(BasicColumnSpecification))`, and `col(window(..), func(..), 'maxAge')` is a WindowColumnSpecification, so it routes to processTdsRestrictNormal — which processes the full 6-column project (materializing the firmTable join for firmName) and then narrows only the SELECT list. Hence the join survives with no surviving column referencing it.
- legend-lite reproduces exactly that. Typer.java:401-414 desugars `restrictDistinct(cols)` to `distinct(select(<the ORIGINAL 6-col project>, colSpecs))`, so all six columns type and resolve and the firm join materializes. Lowerer.distinct (Lowerer.java:1500-1527) → narrowTo (1422-1426) → projectColumns (1443-1455), which calls `base.withProjections(ps, ...)`: it REPLACES the projection list on the same SqlSelect and leaves `from()` — including the join — untouched (the comment at Lowerer.java:1463-1468 names 'the restrict-over-window-cols corpus pin' explicitly). Fold.projectionFolds (Fold.java:265-267) and Fold.distinctFolds (371-374) are both true here (no groupBy/orderBy/limit), so no subselect is interposed: the output is one flat `select distinct <4 projections> from personTable as "root" left outer join firmTable as <alias> on (...)`.

What legend-lite cannot reproduce is the ALIAS. See sharedRootCause: this call site is `toSQLString(f, mapping, DatabaseType.H2, extensions)`, which in the engine runs with an EMPTY post-processor list, so the raw `firmTable_d#6_d#3_m3` alias survives; legend-lite's EngineStyleH2.render() unconditionally applies the reAlias renaming and will emit `firmtable_0`. The `d#6_d#3_m3` suffix is a run-length-compressed transcript of pureToSqlQuery's own recursive descent counters (buildNodeId, pureToSQLQuery.pure:3871-3891: `_d` per processValueSpecification descent, `_mN` per merge index), i.e. a fingerprint of the engine's Pure-level recursion, not of the query's meaning.

**Fix**

Do NOT fix; ledger it, with the raw-alias cluster as the ledger entry (not this test).

The only fix that makes this test pass is to give legend-lite a second alias channel that reproduces pureToSqlQuery's raw `<TableName>+<nodeId>` names, and to select it when the SQL-text surface was called with a DatabaseType rather than a Runtime. Mechanically: (1) StatementExecutor.toSqlString (StatementExecutor.java:359-373) would pass a `boolean reAlias` flag = (dbArg is a runtime) into the renderer; (2) EngineStyleH2.render (219-228) would skip planQuery/renames when the flag is false; (3) the Lowerer would have to STAMP each join's SqlSource alias with an engine-compatible nodeId, i.e. reproduce buildNodeId's `_d` accumulation, `#N` run-length compression, and `_mN` merge indices (pureToSQLQuery.pure:3871-3891) across legend-lite's own resolver — a resolver whose recursion shape is deliberately different from the Pure processValueSpecification cascade. Step 3 is the whole cost and it is not a semantic property: it is a transcript of the engine's Pure-level call tree. Since the assert is purely textual and the rows are not asserted at all in this test, the honest disposition is a ledger row: 'raw (un-post-processed) toSQLString aliases not reproduced — 113 golden literals across 14 corpus files'.

If someone does want to close the cluster, do it as ONE change against the whole 113-literal set, not per test, and start by verifying step (1)+(2) alone against a single-table golden (which is alias-free) so the flag plumbing is proven before attempting step (3).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/toSQLString.pure:63 (DatabaseType overload → no post-processors) vs .../postprocessor/defaultPostProcessor/defaultPostProcessor.pure:64 (reAlias in the execute-path defaults); raw alias minted at .../pureToSQLQuery/pureToSQLQuery.pure:9001, nodeId scheme at :3871.

**Risk** — The tempting shortcut — normalizing `_d#N…` alias runs out of the goldens before comparing (the machinery already exists for lineage at LineageRelationsForm.java:143 and ScanRelations.java:940) — is exactly tenet-2 harness compensation if applied to the SQL-text channel: it would mask a real alias-collision bug in legend-lite's own renamer. Keep the wall.

**Also unblocks** — testSortQuotes (this unit, test 3) and the other 111 `_d#` golden literals across testSort.pure, testTDSRestrictDistinct.pure, testPureToSql.pure, testMergeRules.pure, testMilestoningContextPropagation.pure, testBusinessDateMilestoning.pure, testWithFunction.pure, testModelGroupBy.pure, testConcatenate.pure, testExists.pure, testFunctionVariables.pure, testQualifier.pure, testToSQLString.pure, scanRelationsTests*.pure.

**Falsifier** — Print the FULL actual string for this test (the sweep truncated it at 250 chars) — e.g. set LL_SQLTEXT_DEBUG and dump both sides. If the actual reads `... from personTable as "root" left outer join firmTable as "firmtable_0" on ("firmtable_0".ID = "root".FIRMID)` and is otherwise character-identical to the expected, this diagnosis is confirmed and it is alias-only. If the actual has NO `left outer join` clause, or wraps the projection in a subselect, my Lowerer trace is wrong and the verdict must become REAL_DEFECT (join cut-down applied where the engine suppresses it).

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:219 — `public String render(SqlQuery query) { ... planQuery(query, new LinkedHashMap<>()); ... }` — the reAlias planner runs on every render, with no opt-out
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:336 — planSource keys the alias group by the BARE lowercased table name; nextInGroup (394-396) returns `group + "_" + i`, i.e. `firmtable_0`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:365 — toSqlString() picks the renderer purely from the DatabaseType arg and constructs `new EngineStyleH2()`; nothing distinguishes the post-processed (runtime) from the un-post-processed (DatabaseType) engine overload
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:283 — `private String nextAlias() { return "t" + aliasCounter++; }` — legend-lite's pre-rename aliases are t0/t1, nothing like the engine's raw scheme
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1443 — projectColumns() returns `base.withProjections(ps, outputsOf(info))`: FROM/join preserved, confirming legend-lite keeps the unused firmTable join like the engine
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:401 — restrict/restrictDistinct desugar; line 412 wraps in `distinct`, over the UNMODIFIED source project (so all 6 columns still resolve)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Fold.java:371 — distinctFolds is true when groupBy/orderBy/limit/offset are all empty, so the DISTINCT folds flat with no subselect
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/transform/fromPure/toSQLString.pure:63 — the DatabaseType overload: `toSQLString($f, $mapping, $databaseType, [], [], ^Format(newLine='', indent=''), $extensions, noDebug())` — empty sqlQueryPostProcessors
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/defaultPostProcessor.pure:64 — `reAlias::replaceAliasName_...` is in sqlQueryDefaultPostProcessors(), applied only on the execute/runtime path
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:35 — `let newTableNames = $zipped->map(x| $x.first+ '_'+ $x.second->toString());` with root/unionBase/subselect passthrough at 38 — the scheme EngineStyleH2 reimplements
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:9001 — createJoinTableAlias: `$targetAliasInJoin.relation->cast(@Table).name->replace(' ','_')->replace('"','')+$nodeId` — the RAW alias the golden asserts
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:6575 — shouldOptimizeRestrictDistinct: optimization requires `$c->instanceOf(BasicColumnSpecification)` for all project columns, which window cols fail — the engine reason the join is retained

</details>

---

## `testSortQuotes`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The observed wall is honest and precise: `meta::pure::functions::meta::enumValues` does not exist anywhere in legend-lite. `grep -rn enumValues core/src` returns zero hits — not in com/legend/builtin/Pure.java, not in the test resource core/src/test/resources/native-catalog.txt. The test's first statement is `DatabaseType->enumValues()->filter(e|$e->in([DatabaseType.DB2, DatabaseType.Composite]))->forAll(type | ...)`. That statement is not a shape the harness intercepts (the only multi-driver loop form EngineTestExecutor recognizes is `$pairs->map(p|...)->distinct()` over `pair(...)` literals — driverPairLoop, EngineTestExecutor.java:2249-2281 — reached only from the `equal(...)` statement guard at 478-481), so it falls to the generic expression-statement channel and is compiled. Typer.checkGeneric finds zero candidates for the name and throws the exact message in the brief at Typer.java:1447-1451.

That is the FIRST wall but not the only one. Behind it, in order:
(1) `enumValues` itself — a metamodel reflection native returning an Enumeration's values in declaration order.
(2) the loop form — `->forAll(type | let query = toSQLString(...); assertEquals(...);)`: a multi-statement lambda whose body carries harness assert vocabulary. EngineTestExecutor has a loop runner for this (runPerDriverLoop, 2110-2174, which already whitelists exactly H2/DB2/Composite at 2118-2126) but no recognizer for the enumValues/filter/forAll spelling.
(3) the goldens — both asserted strings use `addressTable_d#3_1_d#3_m2`, the RAW un-post-processed alias. See sharedRootCause: legend-lite always re-aliases, so even with (1) and (2) built, both assertEquals would fail on the alias. This test is therefore gated on the same cluster as test 2 above.

**Fix**

Three pieces; (1) is small and independently valuable, (2) is moderate, (3) is the XL cluster from test 2.

(1) Port enumValues. Add to com/legend/builtin/Pure.java, next to the other meta natives: `public static final NativeFunctionDefinition ENUM_VALUES__ENUMERATION_1 = signature("native function meta::pure::functions::meta::enumValues<T>(enum:meta::pure::metamodel::type::Enumeration<T>[1]):T[*];");`. Then fold it at compile time in Typer.java, mirroring extractEnumValueFold: add an arm next to the extractEnumValue dispatch at Typer.java:418, and a private `enumValuesFold(AppliedFunction af, Env env)` modelled line-for-line on extractEnumValueFold (1056-1077) — synth arg 0, require `Type.GenericType gt` with `gt.rawFqn().equals(Pure.ENUMERATION.qualifiedName())` and one `Type.EnumType et` argument, then `ctx.findEnum(et.fqn()).orElseThrow(...)` and emit a TypedCollection of `new TypedEnumValue(et.fqn(), v, ExprType.one(et))` for each `v` in `en.values()` (declaration order), typed `ExprType.many(et)`. Return null (fall through to the loud generic path) when the argument is not Enumeration-shaped, exactly as extractEnumValueFold does. With this, `->filter(e|$e->in([...]))` folds over a literal collection of TypedEnumValue and needs nothing new.

(2) Recognize the enum-driven driver loop. In EngineTestExecutor, add a recognizer beside driverPairLoop (2249) for a STATEMENT-position `forAll(<collection of enum values>, lambda)` (and the `filter(enumValues(E), pred)` source), returning the lambda plus the resolved driver list; then reuse runPerDriverLoop (2110) by generalizing its per-iteration substitution from substPairReads(pVar, first, second) to a plain single-value bind of the lambda parameter. Its existing {H2, DB2, Composite} gate at 2118-2126 is already the right policy for this test. Note this is harness ORCHESTRATION vocabulary (asserts inside a loop lambda), which the harness legitimately owns — it is not compensating for a platform gap.

(3) The goldens still will not match until the raw-alias channel exists. See the fix on testRestrictDistinct_NoOptimization_WindowColumns. Land (1) and (2) for their own sake, but expect this test to move from ERROR to a golden-text FAIL, not to PASS, until (3).

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/meta/type/enum/enumValues.pure:18 — the native signature; the semantics are 'the Enumeration's values in declaration order', which legend-lite already has via ModelContext.findEnum(...).values() as used at Typer.java:1071.

**Risk** — Piece (1) is near-zero risk — it is additive and the fold returns null on any non-Enumeration argument, keeping the existing loud path. Piece (2) touches the assert-scoring loop: if the generalized runPerDriverLoop stops rejecting unknown statements (its `return new Outcome.Unsupported("unrecognized statement in a per-driver golden loop")` at 2172), tests could silently skip real asserts — keep that arm strict. Tenet-2 trap: do NOT implement enumValues by hardcoding the DatabaseType value list in the harness, and do NOT normalize the `_d#` alias runs out of these two goldens before comparing (the normalizer already exists for lineage at LineageRelationsForm.java:143 — reusing it here would be harness compensation for the alias gap).

**Also unblocks** — Piece (1) unblocks any corpus test that reflects over an Enumeration's values (enumValues is a common spelling in per-driver and enum-mapping tests). Piece (3) is the 113-literal raw-alias cluster shared with test 2.

**Falsifier** — After landing (1) alone, re-run: if the failure moves from `unknown function 'enumValues'` to something else (an unrecognized-statement or forAll wall), the first-wall diagnosis is confirmed. If it still reports `unknown function 'enumValues'`, the name is being resolved somewhere other than Typer.checkGeneric and my location is wrong. For (3): if the new failure text shows `addresstable_0` where the golden has `addressTable_d#3_1_d#3_m2`, the raw-alias gating is confirmed.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1447 — `throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog (unported platform function, or a misspelling)")` — verbatim the brief's message, reached when functionCandidates(af) is empty (1443)
- grep -rn "enumValues" over /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src — zero matches, including core/src/test/resources/native-catalog.txt: the surface is genuinely absent
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/meta/type/enum/enumValues.pure:18 — `native function <<PCT.function, PCT.platformOnly>> meta::pure::functions::meta::enumValues<T>(enum:Enumeration<T>[1]):T[*];` — the signature to port
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1056 — extractEnumValueFold: the existing template for an Enumeration-typed argument (guard on Type.GenericType with rawFqn == Pure.ENUMERATION and one Type.EnumType argument, then ctx.findEnum(et.fqn()) and emit TypedEnumValue at 1076)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2249 — driverPairLoop requires `distinct(map(<collection of pair(...)>, lambda))`; the enumValues/filter/forAll spelling matches none of it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2118 — runPerDriverLoop already gates on exactly {H2, DB2, Composite}, matching this test's DB2/Composite/H2 set
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:369 — the DB2 and Composite engine-style renderers already exist and are dispatched (EngineStyleDB2.java, EngineStyleComposite.java are both present in com/legend/sql/dialect/)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testSort.pure:177 and :181 — both goldens spell the join alias `addressTable_d#3_1_d#3_m2` (raw, un-post-processed), unlike the execute-based goldens at :161/:167 which spell `addresstable_0`

</details>

---

## `testForcedIsolationFilterOnTop`

| | |
|---|---|
| family | `tests/advanced` |
| sweep status | FAIL |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Two independent defects, the first dominant. (1) `execute(..., ^RelationalDebugContext(debug=false, space='', forcedIsolation = IsolationStrategy.MoveFilterOnTop))` — legend-lite types the 5th execute argument as plain `Any` (Pure.java:1537-1543 registers RelationalDebugContext/IsolationStrategy as corpus classes and `EXECUTE__…__ANY_1` takes `debug: Any[1]`), and the harness's SQL-text path drops it entirely when rewriting execute into toSQLString (ExecCallFinder.java:137-139 keeps only the trailing extensions argument). Nothing in the resolver reads `forcedIsolation` — grep finds it only in comments and in the Runner's structural test-shape detector (Runner.java:748-760). So legend-lite silently applies its own default isolation: it wraps the `employees.locations` navigation in a derived table that carries `locations_PLACE` out, and filters on the carried column. The engine, told MoveFilterOnTop, keeps the join tree FLAT and puts the saved filtering operation in the outer WHERE (pureToSQLQuery.pure:7584-7588). (2) A separate, genuine renderer bug is visible in the same output: the derived table projects `select t1.*` — a dangling reference to `t1`, an alias that does not exist in the rendered SQL. `Lowerer.joined` emits `new SqlExpr.Star(source.left().alias())` with the raw internal alias (Lowerer.java:1827, aliases minted as `t0/t1/…` at Lowerer.java:283-285), and `AnsiSqlRenderer` renders `SqlExpr.Star` as `ident(s.table()) + ".*"` (AnsiSqlRenderer.java:298) WITHOUT passing through EngineStyleH2's alias-rename map (`rename`, EngineStyleH2.java:588-590), which every Column/source read does use. The DuckDB execution path is unaffected because it never renames; only the engine-style text is corrupt.

**Fix**

Two separable changes. (A) The Star rename — do this one regardless, it is small and unambiguously a bug: give `AnsiSqlRenderer` a `protected String sourceAlias(String a) { return a; }` hook used by the `SqlExpr.Star`/`SqlExpr.StarExcept` arms (AnsiSqlRenderer.java:298-301) and override it in `EngineStyleH2` to `return rename(a);`, exactly as the Column arm already does. (B) forcedIsolation — implement or wall, do not keep ignoring. Minimum honest behaviour: give the execute native a typed 5th parameter, read `forcedIsolation` in `StatementExecutor` where the frame is built, thread it into `StoreResolver` as an isolation-strategy override, and have `StoreResolver` throw `NotImplementedException("forcedIsolation=<X> is not implemented")` for any strategy whose emission does not exist. Full parity means implementing `moveFiltersOnTop` (keep the navigation joins flat in the main tree; hoist the saved filtering operation into the outer WHERE) alongside the current default; `MoveFilterInOnClause` and `BuildCorrelatedSubQuery` are the sibling cases (testLiteralConditionsForcedIsolation / testQualifierWithIsolation*). Note the engine's own guard at pureToSQLQuery.pure:7581 — MoveFilterOnTop degrades to BuildCorrelatedSubQuery when the root contains an inner join; reproduce that or the strategies will not match on other tests.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7565 and :7584

**Risk** — (A) is low risk but will change the engine-style text of every query that currently renders a `<alias>.*` projection — those goldens are presently wrong, so expect several diffs to MOVE rather than vanish; re-sweep. (B) is high risk: the isolation strategy governs row shape, and blindly wiring an override into a resolver that has only one strategy will produce wrong rows. Tenet-2 trap: do NOT make the harness special-case debug-arity execute calls to skip the SQL compare — that would bury a platform gap in the test runner.

**Also unblocks** — The Star-rename fix (A) plausibly touches testLiteralConditionsForcedIsolation and any other engine-text golden whose isolation frame projects a star. The forcedIsolation work (B) would cover the testQualifierWithIsolation* family in tests/advanced/testQueryStructure.pure.

**Falsifier** — For (A): grep the sweep's full got-strings for `\bt[0-9]+\.\*` — if `t1.*` appears in no other test, the Star bug is narrower than claimed but still real here. For (B): if a build with the debug argument dropped entirely produces byte-identical SQL to today's, that confirms forcedIsolation is inert; if the SQL changes, something already reads it and this root cause is wrong.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1537-1543 — "RelationalDebugContext/IsolationStrategy stay CORPUS classes"; `EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY__ANY_1` takes `debug: Any[1]`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ExecCallFinder.java:133-144 — rewriting execute->toSQLString keeps args 0,1, a synthetic H2 DatabaseType, and only the LAST argument; the debug context is discarded
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:748-760 — `isDebugContextNew` exists only to RECOGNISE the shape for helper expansion, not to honour it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1827 — `ps.add(new SqlSelect.Projection(new SqlExpr.Star(source.left().alias()), null));`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:283-285 — `nextAlias()` returns `"t" + aliasCounter++`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:298 — `case SqlExpr.Star s -> s.table() == null ? "*" : ident(s.table()) + ".*";` — no rename hook; EngineStyleH2 does not override it (no `Star` case anywhere in that file)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:588-590 — `private String rename(String alias) { return renames.getOrDefault(alias, alias); }`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7565-7567 — `let possibleStrategy = if($context->instanceOf(RelationalDebugContext) && !$context->cast(@RelationalDebugContext).forcedIsolation->isEmpty(), | $context->cast(@RelationalDebugContext).forcedIsolation->toOne(); , | …)` — the forced value overrides the computed default
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7584-7588 — `if ($strategy == IsolationStrategy.MoveFilterOnTop, | $select->moveFiltersOnTop($select.savedFilteringOperation, …)`

</details>

---

## `testViewSimpleExists`

| | |
|---|---|
| family | `tests/query` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

The `PersonFirmView` frame is DISSOLVED during lowering because its body matches `isRenameOnlySelect`, and the resulting flat join tree also reverses the drive side of the mapping's INNER hop. Person is mapped `scope([db] PersonFirmView) (lastName: lastName, firm(legalName: firm_name))`, so `MappingNormalizer.synthRelational` infers the main table, finds the view, and `ViewRelation.frameable` returns true (view has no ~filter/~groupBy/~distinct; every PM leaf reads a declared view column) — so the class extent IS built as a view frame (MappingNormalizer.java:1638-1641, :1866-1878; ViewRelation.java:363-390). But the frame's SQL body is `select personTable.ID as PERSON_ID, personTable.LASTNAME as lastName, firmTable.LEGALNAME as firm_name from personTable left outer join firmTable on (...)` — projections that are all plain `SqlExpr.Column`, source a `SqlSource.Join`, no clauses. That is exactly `isRenameOnlySelect`'s predicate (Lowerer.java:1842-1858), so when the exists-correlation join arrives with a prefix, `Lowerer.join` takes the hosting branch at Lowerer.java:1718: the frame's join tree becomes the LEFT side and its renames are carried, which is why the emitted subquery reads `from personTable as "persontable_1" left outer join firmTable as "firmtable_0" …`, the predicate reads `firmtable_0.LEGALNAME` instead of `personfirmview_0.firm_name`, and the SalesPerson_PersonView condition's `PersonFirmView.PERSON_ID` degrades to `persontable_1.ID` (the documented rename substitution, Lowerer.java:1713-1714). The frame identity is only ever attached to the RIGHT side of a join (`j.frameName()` at Lowerer.java:1741); the LEFT side has a special case for union frames (`unionFramed`, Lowerer.java:1734, :1763-1766) but NONE for view frames, and `asLeftJoinSide` wraps with `frameName` hard-coded null (Lowerer.java:1868). This also explains why the neighbouring view tests pass: testViewAll's view is `~distinct` (so `isRenameOnlySelect` returns false on the distinct check), testAllWithJoinToView has the view on the RIGHT, and testViewPropertyFilterWithPrimaryKey's OrgViewOnView body has a Subselect (not a Join/Table) as its `from`, failing the source check at Lowerer.java:1849.

**Fix**

Preserve view-frame identity on the LEFT side of a join, mirroring the existing union-frame treatment. (1) Carry the frame name onto the extent itself, not just onto the joining TypedJoin: in `StoreResolver`, when a ClassSource is view-backed (`ViewFrames.frameNameOf(ctx, cs) != null`), tag the class-extent pipeline so the lowerer can see it — either a `frameName` field on the project node the extent lowers through, or (cheaper) have `Lowerer.relation` return the extent already wrapped as `SqlSelect.starOf(new SqlSource.Subselect(body, nextAlias(), <viewName>))`. (2) In `Lowerer.join` (Lowerer.java:1708-1741), add a `viewFramed(leftSel)` guard alongside `unionFramed` so the `isRenameOnlySelect` hosting branch is SKIPPED for a view-framed left, and pass the frame name into the wrap: `new SqlSource.Subselect(leftSel, nextAlias(), viewFrameNameOf(leftSel))` instead of the null at Lowerer.java:1868. Keeping the frame will also stop the SalesPerson_PersonView condition from degrading to `personTable.ID`, which should restore the engine's drive order (salesPersonTable inner-joined to the view) rather than the reversed personTable-first tree we emit today.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:49 (ViewSelectSQLQuery is traversed as a Table with its own alias and its own inner select — views are relations, never inlined).

**Risk** — The rename-only hosting branch is what keeps the resolver's prefix chains in ONE flat SELECT ("the real engine's shape", Lowerer.java:1710-1715). Narrow the new guard to view frames specifically — if it accidentally catches ordinary prefixed join selects, a large number of currently-passing flat-join goldens will grow spurious subselects. Verify against testViewAll / testViewSimpleFilter / testAllWithJoinToView / testViewPropertyFilterWithPrimaryKey, which pass today for the incidental reasons listed in the root cause and must keep passing.

**Also unblocks** — Possibly testReplaceTablePostProcessorWithView (postprocessor/tests, also a sql-text divergence on a view-bearing query). Not verified.

**Falsifier** — Set a breakpoint / trace print at Lowerer.java:1718 for this query and record whether the hosting branch fires with `leftSel` being the PersonFirmView body (three Column projections over a personTable⋈firmTable Join). If it does not fire, the flattening happens earlier — in that case re-check `ViewRelation.frameable` by asserting it returns true for this Person mapping, since the alternative cause is the normalizer taking the column-substitution fallback at MappingNormalizer.java:1880-1893.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1718 — `if (j.prefix().isPresent() && isRenameOnlySelect(leftSel)) { leftCarry = leftSel.projections(); left = leftSel.from(); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1842-1858 — `isRenameOnlySelect`: false only on distinct/where/groupBy/having/qualify/orderBy/limit/offset, requires from ∈ {Join, Table} and all projections Star or Column
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1710-1715 — the comment states the consequence: "refs to renamed columns in the ON condition substitute to their underlying columns" — precisely the `PersonFirmView.PERSON_ID` -> `personTable.ID` we observe
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1739-1741 — `SqlSource right = asRightSide(rightSel, unionFramed(rightSel) ? "unionAlias" : j.frameName());` — frame identity is applied to the RIGHT only
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:1867-1868 — `asLeftJoinSide` -> `new SqlSource.Subselect(side, nextAlias(), null)` — a wrapped LEFT side gets frameName null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:1863-1878 — "a view reached as a relation is an IDENTITY-CARRYING FRAME … a view NEVER flattens"; the frame path is taken when `frameable`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/ViewRelation.java:363-390 and :393-406 — `frameable` passes for this mapping: no view filter/groupBy/distinct, Column PM `lastName` and Embedded `firm(legalName: firm_name)` both read declared view columns
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:761-784 — `plainClassViewCond` explicitly REFUSES to substitute a view whose column mappings contain a join navigation, and PersonFirmView's `firm_name : @Firm_Person | firmTable.LEGALNAME` is exactly that — so the flattening is NOT coming from the normalizer
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ViewFrames.java:26-97 — `frameNameOf` resolves the view name for a class whose main source is a view (used only at TypedJoin construction, StoreResolver.java:888/1967/2006)
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:49 — `v:ViewSelectSQLQuery[1] | pair($relElement->cast(@Table).name, $alias.name)->concatenate($v.selectSQLQuery->traverse())` — in the engine a view IS an aliased Table-like relation with its own subselect; it is never inlined into the parent FROM
- corpus tests/query/testView.pure:79-82 — the golden keeps `(select "root".ID as PERSON_ID, … from personTable as "root" left outer join firmTable …) as "personfirmview_0"` and joins it with `inner join`

</details>

---

## `testEqualityInFilterOnOptionalProperties`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

Filter position, so `FILTER_POS` IS entered (Lowerer.java:1208) and both operands are optional columns — `equalNullArms` should take the NULL_SAFE_EQUAL arm (NullSemantics.java:117-123) and `EngineStyleH2.nullSafeSpelling` (EngineStyleH2.java:947-950) should render ` is not distinct from `, matching the golden. The only divergence I can see in the (truncated) `got` is the alias: golden `personTable_d#6_d#3_m1_d#2_m1`, produced `persontable_1`. Cause per sharedRootCause. The `got` string is cut at `left outer join personTable as "persontable_1" on ("ro`, i.e. exactly at the first alias occurrence — consistent with alias-only, but the WHERE clause is not visible.

**Fix**

DO NOT FIX — ledger with testNotEqualityForOptionalProperties; identical alias mechanism and identical (XL) fix shape. No operator work is needed for this test.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8998-9003 and .../transform/fromPure/toSQLString.pure:63-66.

**Risk** — Same harness-compensation trap: do not normalise aliases in the comparator.

**Falsifier** — Print the FULL untruncated `got` string. If the WHERE clause reads `"root".AGE = "persontable_1".AGE` rather than `is not distinct from`, then the filter arm is ALSO not firing here (most likely because the lowered operands are not `SqlExpr.Column` after Pipelines' right-side prefixing, Pipelines.java:415-433, failing the `instanceof SqlExpr.Column` conjunct at NullSemantics.java:119-120) — which would reclassify this as REAL_DEFECT sharing test 6's fix plus a relaxation of the Column shape check to the engine's pure-multiplicity-only rule.

<details><summary>Evidence read (6 citations)</summary>

- brief failure detail: got `... left outer join personTable as "persontable_1" on ("ro` (truncated) vs expected `... as "personTable_d#6_d#3_m1_d#2_m1" on (...)`
- corpus transform/fromPure/tests/testToSQLString.pure:785-792 — body `Person.all()->filter(p | $p.age == $p.manager.age)->project([col(p | $p.firstName, 'name')])`, DatabaseType.H2, golden WHERE `"root".AGE is not distinct from "personTable_d#6_d#3_m1_d#2_m1".AGE`
- legend-lite core/src/main/java/com/legend/lowering/Lowerer.java:1208 — `try (var ignored = NullSemantics.enterFilter())` wraps the predicate lowering, so filter position does take the null-safe arm
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:117-123 — the arm returns `new SqlExpr.Call(SqlFn.NULL_SAFE_EQUAL, ops)` under exactly these conditions
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:947-950 — NULL_SAFE_EQUAL renders as ` is not distinct from `, no parens
- legend-engine transform/fromPure/toSQLString.pure:63-66 and postprocessor/defaultPostProcessor/defaultPostProcessor.pure:58-66 — no post-processors on this surface, so the raw nodeId alias survives

</details>

---

## `testEqualityInFilterOnOptionalPropertiesLegacy`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

Same query as testEqualityInFilterOnOptionalProperties but DatabaseType.DB2, which legend-lite DOES support (`EngineStyleDB2`, dispatched at StatementExecutor.java:371). Its NULL_SAFE_EQUAL override emits `(%s = %s or (%s is null and %s is null))` (EngineStyleDB2.java:44-49) — character-for-character the engine's default-extension format and the golden's shape. So the OR-expansion is right; only the alias diverges (golden `personTable_d#6_d#3_m1_d#2_m1`, produced `persontable_1`). Cause per sharedRootCause. The DB2 renderer subclasses EngineStyleH2 (EngineStyleDB2.java:20) and therefore inherits the same unconditional re-alias plan.

**Fix**

DO NOT FIX — ledger with testNotEqualityForOptionalProperties. Alias mechanism only; the DB2 spelling is already exact.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:254 (the DB2-inherited nullSafeEqual format) and .../pureToSQLQuery/pureToSQLQuery.pure:8998-9003 (the alias).

**Risk** — Same harness-compensation trap.

**Falsifier** — Print the full untruncated `got`. If the WHERE is a bare `=` rather than the OR-expansion, the null-safe node was never chosen and this joins test 6 as a REAL_DEFECT instead.

<details><summary>Evidence read (6 citations)</summary>

- brief failure detail: got `select "root".FIRSTNAME as "name" from personTable as "root"` (truncated exactly before the join alias)
- corpus transform/fromPure/tests/testToSQLString.pure:794-801 — DatabaseType.DB2, golden WHERE `("root".AGE = "personTable_d#6_d#3_m1_d#2_m1".AGE or ("root".AGE is null and "personTable_d#6_d#3_m1_d#2_m1".AGE is null))`
- legend-lite core/src/main/java/com/legend/StatementExecutor.java:371 — `case "DB2" -> new com.legend.sql.dialect.EngineStyleDB2();` so this test is not walled
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:44-49 — `return "(" + a + " = " + b + " or (" + a + " is null and " + b + " is null))";`
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:20 — `public class EngineStyleDB2 extends EngineStyleH2` — inherits render()/planQuery() and thus the re-alias
- legend-engine sqlQueryToString/extensionDefaults.pure:254 — `dynaFnToSql('nullSafeEqual', $allStates, ^ToSql(format='(%s = %s or (%s is null and %s is null))', ...))` — identical format string

</details>

---

## `testIsDistinctSQLGeneration`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | S |
| confidence | high |

**Root cause**

Two independent text divergences, no row assert in the test at all. (1) DISTINCT spelling: legend-lite lowers isDistinct to EQUAL(COUNT(distinct=true, v), COUNT(v)) (Lowerer.java:1070-1080). AnsiSqlRenderer.reducer renders that as `COUNT(DISTINCT v)` (AnsiSqlRenderer.java:705-706) and EngineStyleH2.reducer only lowercases the name up to the first '(' (EngineStyleH2.java:1205-1214: `s.substring(0,p).toLowerCase() + s.substring(p)`), producing exactly the observed `count(DISTINCT "persontable_0".F…`. The engine spells DISTINCT as a nested CALL: `distinct(%s)` (extensionDefaults.pure:211) so isDistinct is `count(distinct(%s)) = count(%s)` (extensionDefaults.pure:228). (2) The alias scheme — see sharedRootCause; the golden wants `personTable_d#4_d_m1`, we emit `persontable_0`. The assert is `assertSameSQL` (arity 2) so it routes to sqlTextVerify (EngineTestExecutor.java:953,1986-1994); h2Upgrade declines because the test never executes anything, so RawSqlBoundary.recording() is null (EngineTestExecutor.java:1024-1035), the diff is recorded as a sqlDiff and — with verified()==0 — Runner.score turns it into a hard FAIL (Runner.java:1455-1459).

**Fix**

Fix the DISTINCT spelling; LEDGER the alias. In core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1205 stop post-processing `super.reducer(r)` and build the string locally so DISTINCT can be spelled as the engine's nested call: `String args = r.args().isEmpty() ? "*" : list(r.args()); String body = r.distinct() ? "distinct(" + args + ")" : args; return r.fn().name().toLowerCase(Locale.ROOT) + "(" + body + order + ")";` reusing the same ORDER BY suffix AnsiSqlRenderer.reducer builds (extract that suffix into a protected helper so both renderers share it, rather than duplicating). EngineStyleDB2/EngineStyleComposite inherit it, which is correct — DB2 and Composite both take the `distinct(%s)` default from extensionDefaults.pure:211. Do NOT try to make this test pass: the `personTable_d#4_d_m1` half needs pureToSqlQuery join-tree breadcrumb naming that legend-lite deliberately does not model (harness/LineageRelationsForm.java:142-151), so ledger the test as advisory-only and keep it out of the pass target.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:228 (isDistinct format='count(distinct(%s)) = count(%s)') and :211 (distinct format='distinct(%s)')

**Risk** — EngineStyleH2's text is also executed on the real H2 second target in the M1 byte-match path (EngineTestExecutor.java:995-1008). `count(distinct(x))` must still parse on H2 2.1.214 — it does in the engine's own goldens, but confirm before landing. Tenet-2 trap: do NOT fix this by normalising DISTINCT spellings inside the harness comparator; the spelling is owned by the dialect renderer.

**Also unblocks** — Any golden spelling `count(distinct(` — e.g. tds/tests/testGroupBy.pure:87,99,422,438,497 and functions/tests/testModelGroupBy.pure:1078 currently pass only because their row asserts carry them; this removes their silent advisory sql diffs.

**Falsifier** — Render `Firm.all()->groupBy([t|$t.legalName],[agg(x|$x.employees.firstName,y|$y->isDistinct())],['LegalName','IsDistinctFirstName'])` through toSQLString/H2 and inspect the text. If the aggregate already reads `count(distinct(...))` then EngineStyleH2.reducer is not the source of the `DISTINCT` keyword and this diagnosis is wrong.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:705 — `return r.fn() + "(" + (r.distinct() ? "DISTINCT " : "") + args + order + ")";` i.e. uppercase ANSI DISTINCT keyword
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1205-1214 — reducer() calls super then lowercases only the substring before '(' , so `COUNT(DISTINCT x)` becomes `count(DISTINCT x)` (exactly the observed 'got')
- core/src/main/java/com/legend/lowering/Lowerer.java:1072-1080 — `if (fn == SqlAgg.Fn.IS_DISTINCT_MARK) { … return SqlExpr.Call.of(SqlFn.EQUAL, Reducer(COUNT, value, distinct=true), Reducer(COUNT, value, false)); }`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:228 — `dynaFnToSql('isDistinct', $allStates, ^ToSql(format='count(distinct(%s)) = count(%s)' …))`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:211 — `dynaFnToSql('distinct', $allStates, ^ToSql(format='distinct(%s)' …))`
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:343-345,394 — planSource renames leftmost source to "root" and every other to `nextInGroup(group)` = `group + "_" + i` (the persontable_0 form)
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1013 — `return "sql-text: expected " + golden + ", got " + sql;` the exact message shape in the brief
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:684-697 — the test body: two assertSameSQL golden-SQL asserts (H2 then DB2) and no row/value assertion of any kind

</details>

---

## `testNonExecutableSQLString`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

`meta::relational::functions::sqlstring::toNonExecutableSQLString` and its `meta::relational::postProcessor::nonExecutable` post-processor are genuinely absent from legend-lite. Grepping the whole of core/src for `toNonExecutableSQLString` returns nothing; `nonExecutable` exists only as an unimplemented native SIGNATURE in builtin/Pure.java:1505 (NON_EXECUTABLE_PP), with no lowering/rewrite behind it. The observed message is the compound of two honest-but-badly-attributed walls: (a) Runner.executeMappingRefs' executeShape list (Runner.java:877-901) recognises execute/toSQLString/validate/scanColumns/scanRelations/generateTestData/… but NOT toNonExecutableSQLString, so mappingRefs is empty and the test takes the no-execute branch (Runner.java:1288,1311-1315); (b) the try-run then reaches assertSameSQL → sqlTextVerify → ExecCallFinder.sideSqlText, whose terminal `stops` set is {execute, toSQLString, toSQLStringPretty} (ExecCallFinder.java:114-118) — toNonExecutableSQLString is not there, findTerminal returns null, sql==null, and sqlTextVerify falls through to h2Upgrade which returns the advisory marker (EngineTestExecutor.java:1015-1017,1024-1035). Nothing in the platform was ever asked to render this query; the wall is honest about there being no verification, but it names the wrong owner.

**Fix**

Implement the surface, in the platform, not the harness. (1) Add a `nonExecutable` IR pass beside SqlPostProcessors: a recursive SqlQuery rewrite that, for every SqlSelect reachable through SqlUnion branches, Subselect sources, join sides and projection sub-selects, conjoins `EQUAL(IntLit(1), IntLit(2))` into `where()` (prepended, matching the engine's `andFilters($nonExecutableOperation->concatenate($s.filteringOperation))` order at nonExecutablePostProcessor.pure:45 — the golden reads `where … = 'CUSIP1' and 1 = 2`, and inner selects read `where … TYPE = 'CUSIP' and 1 = 2`). (2) In StatementExecutor, add the `toNonExecutableSQLString(f, mapping, dbType, extensions)` K-native next to toSqlString (StatementExecutor.java:359-397): same engineSql pipeline, then apply the pass before `renderer.render(...)`. (3) Add the FQN to Runner.executeMappingRefs' executeShape list (Runner.java:879) and to ExecCallFinder's findTerminal stops (ExecCallFinder.java:114) so discovery and the golden channel see it. Independently, make sideSqlText's null return attributable: today an unknown terminal and a thrown generation are indistinguishable (ExecCallFinder.java:151-157 swallows into null) — thread a reason so the wall reads 'toNonExecutableSQLString is not implemented' instead of 'no execute(|...) call'.

**How legend-engine does it** — legend-engine .../core_relational/relational/transform/fromPure/toSQLString.pure:83-86 and .../postprocessor/nonExecutablePostProcessor.pure:24-58

**Risk** — Steps (3) alone, without (1)+(2), would be harness compensation: discovery would find the mapping and the golden channel would call a platform surface that does not exist. Land the platform pass first. Note the golden also carries `_d#…` aliases, so even a correct nonExecutable pass leaves this test on the shared alias wall — it will move from SHAPE to an honest sql-text diff, not to PASS.

**Falsifier** — Grep core/src for `toNonExecutableSQLString`. A single hit anywhere in main/ falsifies 'genuinely absent'; if it exists and the wall is elsewhere, this diagnosis is wrong.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:1505 — NON_EXECUTABLE_PP is only a `signature("native function meta::relational::postProcessor::nonExecutable(...)")` declaration; grep for `toNonExecutableSQLString` across core/src returns zero hits
- core/src/main/java/com/legend/harness/ExecCallFinder.java:114-118 — findTerminal stops = Set.of("execute","toSQLString","toSQLStringPretty"); toNonExecutableSQLString is absent, so the side is unverifiable
- core/src/test/java/com/legend/rcorpus/Runner.java:879-900 — the executeShape name list (execute/toSQLString/validate/scanColumns/scanRelations/generateTestData/planTestDataGeneration/getRelationalCSVDataFromQuery/generateSeedDataString/executionPlan) has no toNonExecutableSQLString entry
- core/src/test/java/com/legend/rcorpus/Runner.java:1311-1315 — emits `"no execute(|...) call" + " [calls " + ns + "]" + " — wall: " + attempted.wall()`, the exact message in the brief
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/toSQLString.pure:83-86 — toNonExecutableSQLString = toSQLString with a post-processor lambda `nonExecutable_SelectSQLQuery_1__Extension_MANY__Result_1_->eval($select, $extensions)`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/postprocessor/nonExecutablePostProcessor.pure:24-58 — nonExecutable walks every SelectSQLQuery (including nested Alias/Union/CTE selects and the join tree via joinNonExecutable) and ANDs `^DynaFunction(name='equal', parameters=[^Literal(1),^Literal(2)])` into filteringOperation — i.e. `and 1 = 2` on every select
- core/src/main/java/com/legend/lowering/SqlPostProcessors.java:23-31 — the existing post-processor channel: it recognises only the replaceTables shape over legend-lite's own SQL IR and is loud on anything else

</details>

---

## `testNotEqualityForOptionalProperties`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | high |

**Root cause**

Operator is already CORRECT — the observed `got` shows `"root".AGE is distinct from "persontable_1".AGE as "notmatch"`, matching the golden token for token. The only divergence is the join-target alias: golden `personTable_d#6_d#3_m1_d_m2`, produced `persontable_1`. Cause per sharedRootCause: the engine's `toSQLString` runs no post-processors so the alias is the raw `tableName + nodeId`, whereas `EngineStyleH2.render` unconditionally applies its `replaceAliasName`-parity plan. Note `!=` is unaffected by the FILTER_POS defect because it routes through `NullSemantics.negate` -> `notEqualNullArms` (NullSemantics.java:68-85, 179-197), which has no position gate at all — which is precisely the asymmetry that exposes the `==` bug in test 6.

**Fix**

DO NOT FIX — ledger it. Reproducing `personTable_d#6_d#3_m1_d_m2` requires legend-lite's H/I phases to carry a nodeId breadcrumb whose value is a function of legend-engine's own Pure recursion structure (one `_d` per `processFunctionExpression` descent, `_m` per map, `_fd` per function-definition descent, `_i<N>` per indexed accessor, run-length collapsed by `buildNodeId`). legend-lite's resolver has a fundamentally different traversal shape, so any breadcrumb it emits would be a per-golden reverse-engineering exercise, not a mechanism. The alias is a correlation name; rows are byte-identical. If parity is ever wanted the correct (still XL) shape is: mint the alias at the resolver's navigation-materialisation site (NavMaterializer.java:582/646/738, AssociationJoins.java:1061) from a nodeId threaded through the H-phase, and then make `EngineStyleH2.render` apply its reAlias plan ONLY on the execute/plan channel (a flag on the renderer set by StatementExecutor.java:369-378 for `toSQLString` vs the plan/execute paths), because the engine only re-aliases on the post-processed channel. Both halves are needed — suppressing the re-alias alone just exposes `t0`/`t1`.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8998-9003 (createJoinTableAlias) with .../pureToSQLQuery/pureToSQLQuery.pure:3871-3896 (buildNodeId); and .../transform/fromPure/toSQLString.pure:63-66 showing the empty post-processor list that preserves the raw alias.

**Risk** — If someone 'fixes' this by stripping/normalising alias suffixes in the harness comparator, that is textbook harness compensation: it would mask genuine alias-identity bugs (wrong table correlated, self-join sides swapped) across the whole corpus. The comparator must stay literal.

**Also unblocks** — The raw `_d#` alias form appears on 113 assertion lines across 15 corpus files (tests/query/testWithFunction.pure, milestoning/tests/testMilestoningContextPropagation.pure, milestoning/tests/testBusinessDateMilestoning.pure, tds/tests/testTDSRestrictDistinct.pure, tds/tests/testSort.pure, lineage/scanRelations/*.pure, pureToSQLQuery/tests/testPureToSql.pure, pureToSQLQuery/tests/testMergeRules.pure, functions/tests/testConcatenate.pure, functions/tests/testModelGroupBy.pure, functions/tests/testExists.pure, functions/tests/projection/testFunctionVariables.pure, functions/tests/projection/testQualifier.pure, transform/fromPure/tests/testToSQLString.pure). Every toSQLString golden among them is blocked by the same wall.

**Falsifier** — Diff the produced string against the golden after substituting `personTable_d#6_d#3_m1_d_m2` -> `persontable_1` throughout. If anything else differs, the alias is not the whole story for this test.

<details><summary>Evidence read (12 citations)</summary>

- brief failure detail: got `... "root".AGE is distinct from "persontable_1".AGE as "notmatch" from personTable as ...` — operator matches, alias does not
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:84 — `return new SqlExpr.Call(SqlFn.NULL_SAFE_NOT_EQUAL, ops);` with no position gate
- legend-lite core/src/main/java/com/legend/lowering/Lowerer.java:283-285 — `private String nextAlias() { return "t" + aliasCounter++; }` — no nodeId concept exists
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:220-226 — `render` calls `planQuery(query, new LinkedHashMap<>())` unconditionally, i.e. always re-aliases
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:334-346 — the group key is the bare lowercased table name and non-leftmost sources get `nextInGroup(group, groups)`, giving `persontable_1`
- legend-engine transform/fromPure/toSQLString.pure:63-66 — the 4-arg `toSQLString` forwards `sqlQueryPostProcessors = []`
- legend-engine transform/fromPure/toSQLString.pure:93-99 — that overload routes and renders with only the caller-supplied post-processors; nothing injects the defaults
- legend-engine postprocessor/defaultPostProcessor/defaultPostProcessor.pure:58-66 — `sqlQueryDefaultPostProcessors()` contains `replaceAliasName`, and is applied only via the connection/runtime path
- legend-engine postprocessor/defaultPostProcessor/reAliasQuery.pure:26-42 — `replaceAliasName` lowercases table names, groups, and renames to `<table>_<n>` with `root`/`unionBase`/`subselect` pinned — exactly what EngineStyleH2 reimplements
- legend-engine pureToSQLQuery/pureToSQLQuery.pure:8998-9003 — `createJoinTableAlias`: `$targetAliasInJoin.relation->cast(@Table).name->replace(' ','_')->replace('"','') + $nodeId`
- legend-engine pureToSQLQuery/pureToSQLQuery.pure:3871-3896 — `buildNodeId`: run-length encodes repeated descent markers into `_d#N`
- legend-lite core/src/main/java/com/legend/lineage/ScanRelations.java:937-944 — the project already states `_d#2_m1` breadcrumbs are pureToSqlQuery internals it does not reproduce

</details>

---

## `testNotEqualityInFilterOnOptionalProperties`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

Same as testEqualityInFilterOnOptionalProperties but for `!=`, which is even safer: `!=` lowers via `NullSemantics.negate` -> `notEqualNullArms` (NullSemantics.java:179-197, 68-85) with no position gate at all, yielding NULL_SAFE_NOT_EQUAL, which EngineStyleH2 spells ` is distinct from ` — exactly the golden's operator (and the operator is independently confirmed correct by the sibling projection test 5, whose untruncated output shows `is distinct from`). Divergence is the alias only: golden `personTable_d#7_d#4_m1_d#2_m1`, produced `persontable_1`. Cause per sharedRootCause.

**Fix**

DO NOT FIX — ledger with testNotEqualityForOptionalProperties. Alias mechanism only.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8998-9003 (alias) and .../sqlQueryToString/dbExtension.pure:970-976 (the `!=` operator choice legend-lite already matches).

**Risk** — Same harness-compensation trap.

**Falsifier** — Print the full untruncated `got`. Anything differing beyond the alias substitution `personTable_d#7_d#4_m1_d#2_m1` -> `persontable_1` refutes this.

<details><summary>Evidence read (6 citations)</summary>

- brief failure detail: got `... left outer join personTable as "persontable_1" on ("root".` (truncated) vs expected `... as "personTable_d#7_d#4_m1_d#2_m1"`
- corpus transform/fromPure/tests/testToSQLString.pure:815-822 — body and golden WHERE `"root".AGE is distinct from "personTable_d#7_d#4_m1_d#2_m1".AGE`
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:184-187 — `case EQUAL -> enumInvolved ? notEqualExpandedArms(c.args()) : notEqualNullArms(c.args());`
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:84 — `return new SqlExpr.Call(SqlFn.NULL_SAFE_NOT_EQUAL, ops);`
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:948-949 — the non-NULL_SAFE_EQUAL branch spells ` is distinct from `
- legend-engine sqlQueryToString/dbExtension.pure:975-976 — `processNotEqual` redirects every non-two-literal `not(equal)` to `^$func(name = 'nullSafeNotEqual')->processOperation($sgc)`, matching legend-lite's arm

</details>

---

## `testNotEqualityInFilterOnOptionalPropertiesLegacy`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

DB2 counterpart of testNotEqualityInFilterOnOptionalProperties. `!=` -> NULL_SAFE_NOT_EQUAL (no position gate, NullSemantics.java:184-187 -> :84), and `EngineStyleDB2.expr` spells it `(not (%s = %s) or (%s is null and %s is not null) or (%s is not null and %s is null))` (EngineStyleDB2.java:50-56) — character-for-character the engine's default format and the golden. Divergence is the alias only: golden `personTable_d#7_d#4_m1_d#2_m1`, produced `persontable_1`. Cause per sharedRootCause.

**Fix**

DO NOT FIX — ledger with testNotEqualityForOptionalProperties. Alias mechanism only; the DB2 spelling is already exact.

**How legend-engine does it** — legend-engine .../core_relational/relational/sqlQueryToString/extensionDefaults.pure:255 (the nullSafeNotEqual format) and .../pureToSQLQuery/pureToSQLQuery.pure:8998-9003 (the alias).

**Risk** — Same harness-compensation trap.

**Falsifier** — Print the full untruncated `got`. Anything differing beyond the alias substitution refutes this.

<details><summary>Evidence read (5 citations)</summary>

- brief failure detail: got is truncated at `select "root".FIRSTNAME as "name" from personTable as "root"`
- corpus transform/fromPure/tests/testToSQLString.pure:824-831 — DatabaseType.DB2, golden WHERE `(not ("root".AGE = "personTable_d#7_d#4_m1_d#2_m1".AGE) or ("root".AGE is null and ....AGE is not null) or ("root".AGE is not null and ....AGE is null))`
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:50-56 — `return "(not (" + a + " = " + b + ") or (" + a + " is null and " + b + " is not null) or (" + a + " is not null and " + b + " is null))";`
- legend-engine sqlQueryToString/extensionDefaults.pure:255 — `dynaFnToSql('nullSafeNotEqual', $allStates, ^ToSql(format='(not (%s = %s) or (%s is null and %s is not null) or (%s is not null and %s is null))', ...))` — identical
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:220-226 — the inherited `render` always re-aliases

</details>

---

## `testNullSafeEqualityForOptionalProperties`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

TWO causes stacked. (a) REAL DEFECT — operator: `NullSemantics.equalNullArms` (NullSemantics.java:114-126) gates the null-safe-equal arm on a `FILTER_POS` ThreadLocal (NullSemantics.java:100-112) that is only entered from `Lowerer.filter` (Lowerer.java:1208). This projection column (`col(p | $p.age == $p.manager.age, 'match')`) never enters that scope, so the gate falls through to `new SqlExpr.Call(SqlFn.EQUAL, ops)` (NullSemantics.java:125) and the renderer spells a bare `=`. Observed output confirms it exactly: `"root".AGE = "persontable_1".AGE as "match"`. The engine has NO position condition here: `processEqual` calls `nullSafeEqualsOperation` unconditionally (pureToSQLQuery.pure:7896-7898), and that function's decisive arm is purely multiplicity-based — `$leftParam.multiplicity->getLowerBound() == 0 && $rightParam.multiplicity->getLowerBound() == 0` -> `nullSafeEqual` (pureToSQLQuery.pure:7940-7941). The `callingFromFilter` flag legend-lite modelled is a DIFFERENT, secondary mechanism (`isEqualsFromFilter`, dbExtension.pure:926-930) that upgrades STORE-AUTHORED (non-router) filter equalities in the string layer; it is an addition to the semantic rule, not the semantic rule. legend-lite implemented the secondary mechanism and omitted the primary one. This is not cosmetic: `NULL = NULL` yields SQL NULL, `is not distinct from` yields TRUE — the 'match' column value differs. (b) alias text, per sharedRootCause.

**Fix**

In core/src/main/java/com/legend/lowering/NullSemantics.java:
(1) Delete the `FILTER_POS` ThreadLocal, the `Scope` interface and `enterFilter()` (lines 100-112), and drop the `FILTER_POS.get() &&` conjunct from `equalNullArms` (line 117). The remaining predicate — two operands, both `[0..1]` — IS the engine's rule (pureToSQLQuery.pure:7940-7941). Keep the `instanceof SqlExpr.Column` shape check for now as a conservative narrowing; every affected golden has bare columns on both sides.
(2) While in there, add the engine's two missing degenerate arms that legend-lite has never had (pureToSQLQuery.pure:7930-7938): if a param's multiplicity upper bound is 0 (a literal `[]`), emit `IS_NULL(otherSide)`; if BOTH are, emit a constant true.
(3) Replace the deleted filter marker with its INVERSE — a `NullSemantics.enterJoinCondition()` suppression scope — and open it in `Lowerer.sideCondition` (Lowerer.java:1904, the single funnel for every join ON: called from :1742, :1777, :1779). Inside that scope `equalNullArms` returns the bare EQUAL. This is the honest structural analog: in the engine a Database `@join` operation arrives at the SQL layer as an already-built `DynaFunction('equal')` from the store DSL and never passes through `processEqual`, so `nullSafeEqualsOperation` structurally cannot see it. Then delete the `try (var ignored = NullSemantics.enterFilter())` wrapper at Lowerer.java:1208 (filter position stops being special) and update the stale comments at Lowerer.java:1204-1207, NullSemantics.java:87-99 and GraphEmission.java:66-79 which all describe the filter-gate model.
(4) COMPANION, required to avoid a new failure: honour `Feature.LEGACY_SQL_NULL_UNSAFE_EQUALS`. `StoreResolver.resolveNode` currently discards `withFeatureFlags` outright (StoreResolver.java:290-296: `return resolveNode(wf.args().get(0), context);`) and the enum is only declared, never consumed (Pure.java:660-666). Thread the flag from that call site into the Lowerer and, when set, make `equalNullArms` return the bare EQUAL — the engine's `$state.legacyNullUnsafeEquals` branch (pureToSQLQuery.pure:7897).
Note: (1)-(4) fix the OPERATOR. This test still will not pass, because the alias text (`personTable_d#5_d#2_m1_d_m2`) is a separate, ledgered problem — see the other five entries.

**How legend-engine does it** — legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7928-7944 (`nullSafeEqualsOperation`), reached unconditionally from `processEqual` at pureToSQLQuery.pure:7896-7898; the decisive arm is pureToSQLQuery.pure:7940-7941. Rendering: h2 spells it via `nullSafeEqual`; the OR-expansion default is extensionDefaults.pure:254.

**Risk** — Two real risks. (i) Join ON conditions: without step (3) the arm would fire on `TypedJoin` conditions, whose operands are nullable-column reads with `[0..1]` multiplicity (join conditions are TypedLambdas over row reads — Pipelines.java:415-433 -> Lowerer.java:1742 `sideCondition(j.condition(), ...)`), turning join keys null-matching and silently changing rows. Note the existing `GraphEmission.toOneJoinEquals` (GraphEmission.java:80-94) already neutralises CORRELATION-lambda mints by wrapping both operands in `toOne()` (making them `[1]`, so `isOptional` fails) — but that covers only correlation mints, not store joins, so step (3) is still required. (ii) Without step (4), `testLegacyFlagProjectionEmitsPlainEquals` (testLegacyNullUnsafeEquals.pure:28-35, expects plain `=`) flips from accidentally-passing to failing. TENET-2 trap to avoid: do NOT satisfy this by post-processing the rendered string in EngineStyleH2 or by normalising the golden in the harness — the wrong node is chosen in the I-phase and that is where it must be fixed.

**Also unblocks** — tests/query/testLegacyNullUnsafeEquals.pure::testDefaultProjectionIsNullSafe and ::testDefaultOptionalParamIsNullSafe (plan channel, reAliased aliases — not blocked by the alias problem). Step (4) additionally unblocks ::testLegacyFlagProjectionEmitsPlainEquals and ::testLegacyFlagRestoresOptionalParamFreeMarkerSelector.

**Falsifier** — Lower `Person.all()->project([col(p|$p.age == $p.manager.age,'match')])` and dump the SqlExpr tree. If the node is already `NULL_SAFE_EQUAL` and only the H2 renderer spelled `=`, the diagnosis is wrong and the defect is in EngineStyleH2 instead. (The observed `got` string already shows `=`, and NullSemantics.java:125 is the only producer of a bare EQUAL on this path, so this is close to already-falsified-in-my-favour.)

<details><summary>Evidence read (11 citations)</summary>

- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:117 — `if (FILTER_POS.get() && ops.size() == 2 && ops.get(0) instanceof SqlExpr.Column ...)`: the null-safe arm is gated on filter position
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:125 — the fall-through is `return new SqlExpr.Call(SqlFn.EQUAL, ops);`
- legend-lite core/src/main/java/com/legend/lowering/NullSemantics.java:100-101 — `private static final ThreadLocal<Boolean> FILTER_POS = ThreadLocal.withInitial(() -> Boolean.FALSE);`
- legend-lite core/src/main/java/com/legend/lowering/Lowerer.java:1208 — `try (var ignored = NullSemantics.enterFilter()) {` is the ONLY entry point, inside `private SqlSelect filter(TypedFilter f)`
- legend-lite core/src/main/java/com/legend/lowering/Scalars.java:114 — the `equal` rule delegates: `return NullSemantics.equalNullArms(n, args);`
- legend-lite core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:947-950 — `nullSafeSpelling` renders NULL_SAFE_EQUAL as ` is not distinct from `, so the renderer side is already correct; only the node choice is wrong
- legend-engine .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:7896-7898 — `|if($state.legacyNullUnsafeEquals, |legacyNullUnsafeEqualsOperation(...), |nullSafeEqualsOperation($leftParam, $rightParam, $leftVal, $rightVal))` — called from processEqual with no filter/projection distinction
- legend-engine pureToSQLQuery.pure:7940-7941 — `pair(|$leftParam.multiplicity->getLowerBound() == 0 && $rightParam.multiplicity->getLowerBound() == 0, |^DynaFunction(name = 'nullSafeEqual', parameters = [$leftVal, $rightVal]))`
- legend-engine .../sqlQueryToString/dbExtension.pure:926-930 — `isEqualsFromFilter` = `$func.name == 'equal' && $config.callingFromFilter == true && $func.parameters->forAll(p | $p->instanceOf(TableAliasColumn) && ...nullable != false)` — the SECONDARY string-layer upgrade, distinct from the semantic rule
- corpus tests/query/testLegacyNullUnsafeEquals.pure:38-44 `testDefaultProjectionIsNullSafe` — same query, PLAN channel, asserts `"root".AGE is not distinct from "persontable_1".AGE as "match"` in a PROJECTION; its aliases are the reAliased `persontable_1`, so the alias problem does not block it
- corpus transform/fromPure/tests/testToSQLString.pure:771-782 — the test body and golden I read directly

</details>

---

## `testSqlGenerationDivide_AllDBs`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | XL |
| confidence | medium |

**Root cause**

The golden is a DB2/Composite toSQLString text with two features legend-lite does not reproduce. (a) The alias breadcrumb `tradeEventViewMaxTradeEventDate_d#4_d#4_m5` — the shared root cause above. (b) The join itself: the engine's root form for a scalar `->map(...)` over a class keeps the class's whole root join tree, so `latestEventDate : [db]@Trade_TradeEventViewMaxTradeEventDate | tradeEventViewMaxTradeEventDate.maxTradeEventDate` (relationalSetUp.pure:820, simpleRelationalMapping's Trade) drags the view sub-select into the FROM even though only `quantity` is projected. legend-lite prunes it: RelationalRootForm only rebuilds a root for a BARE class root (a TypedSerializeGraph, RelationalRootForm.java:64-68), and `Trade.all()->filter(...)->map(t|$t.quantity->divide(1000000))` is a scalar map, so the plan is just tradeTable. The divide spelling itself matches — the observed 'got' already begins `select ((1.0 * ` , which is the engine's `divide` format `((1.0 * %s) / %s)` (extensionDefaults.pure:213). Rows are identical either way: the dropped join is a LEFT OUTER JOIN to a per-trade_id grouped view, at most one row per trade, and the test asserts no rows at all.

**Fix**

Do not fix; ledger it. The only way to make the text match is to reproduce (i) pureToSqlQuery's join-tree breadcrumb alias naming and (ii) the engine's unpruned root join tree for scalar `map` roots — the second would ADD a join to legend-lite's plan purely to match text, which is a regression in generated SQL quality for zero row-correctness gain. Record it as advisory-only in the golden-SQL census alongside the other join-carrying toSQLString goldens. If someone insists on closing it, the ordered work is: (1) give the H-phase join-tree nodes engine-shaped breadcrumb names, (2) make RelationalRootForm (or a sibling) rebuild the full mapped root tree for non-serialize scalar roots under EngineTextBoundary only.

**How legend-engine does it** — legend-engine .../core_relational/relational/postprocessor/defaultPostProcessor/reAliasQuery.pure:26-41 (the alias rewrite toSQLString does not run) and .../transform/fromPure/toSQLString.pure:63 (toSQLString passes an empty sqlQueryPostProcessors list)

**Falsifier** — Print the untruncated 'got' for the DB2 assert (LL_SQLTEXT_DEBUG=1, or widen the sweep's diff truncation). If the got text contains the tradeEventView join and differs only in the alias, my (b) claim is wrong and only the alias is at fault; if it fails before rendering (a DialectCapability/NotImplemented from EngineStyleDB2), this is a renderer gap, not a golden-text issue.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testSqlGenerationDivide_AllDBs — testToSQLString.pure:673-682: two assertSameSQL calls (DB2 then Composite) against one $expectedSQL, no row assertion
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/relationalSetUp.pure:812-822 — simpleRelationalMapping's Trade: `latestEventDate : [db]@Trade_TradeEventViewMaxTradeEventDate | tradeEventViewMaxTradeEventDate.maxTradeEventDate`
- core/src/main/java/com/legend/resolver/RelationalRootForm.java:64-68 — `if (!(root instanceof TypedSerializeGraph g) || !g.nested().isEmpty() || g.bareValue() || !(…RelationType)) return body;` — a scalar map root is returned untouched, so no class-wide join tree is rebuilt
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:213 — `dynaFnToSql('divide', $allStates, ^ToSql(format='((1.0 * %s) / %s)'))`, matching the 'got' prefix in the brief
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:343-345,394 — the alias plan that cannot produce `tradeEventViewMaxTradeEventDate_d#4_d#4_m5`
- core/src/main/java/com/legend/StatementExecutor.java:369-376 — toSqlString picks EngineStyleDB2 for DatabaseType.DB2 and EngineStyleComposite for Composite, so both asserts do reach a real renderer

</details>

---

## `testToSQLStringJoinStrings`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | FAIL |
| **verdict** | **GOLDEN TEXT ONLY** |
| effort | S |
| confidence | high |

**Root cause**

Three stacked text divergences on an assert with no row verification. (1) STRING_AGG spelling: EngineStyleH2 has no override for the aggregate name, so AnsiSqlRenderer.reducer emits the enum name and EngineStyleH2.reducer lowercases it to `string_agg(...)`; the corpus contract for H2 is `listagg(col, sep)`. (2) A DuckDB-only determinism key leaks into the engine-text channel: Lowerer.java:1111-1116 injects `SqlSelect.SortKey(SqlExpr.RowOrder(alias))` into any unordered STRING_AGG whose value reads a base-table alias, and EngineStyleH2.rowOrder (EngineStyleH2.java:939-942) renders it as `"persontable_0".rowid` — producing the observed `string_agg("persontable_0".FIRSTNAME, '*' ORDER BY "persontable_0".rowid ASC)`. `rowid` is a DuckDB pseudo-column; the engine's text has no ORDER BY at all, and this text is also what the M1 path would execute on real H2. The injection is unguarded even though Lowerer already consults EngineTextBoundary.active() for exactly this kind of channel split (Lowerer.java:3158). (3) The alias scheme — shared root cause. Secondary: because the actual side is a bare Variable `$h2Sql` rather than a `…->sqlRemoveFormatting()` chain, EngineTestExecutor.isSqlText (2419-2428) does not recognise it as golden SQL, so the assert never routes through sqlTextVerify and is scored as a plain assertEquals failure — hence 'assertEquals: expected …' instead of 'sql-text: …'.

**Fix**

Two real fixes, then ledger the alias. (A) Gate the determinism key: in core/src/main/java/com/legend/lowering/Lowerer.java:1111 add `&& !EngineTextBoundary.active()` to the STRING_AGG rowid-injection condition, mirroring Lowerer.java:3158. Execution (boundary inactive) keeps the deterministic ordering; the engine-text/plan channel stops emitting a DuckDB pseudo-column. (B) Give EngineStyleH2 a STRING_AGG spelling: override reducer (or add a name-map hook) so `SqlAgg.Fn.STRING_AGG` with two args renders `listagg(<value>, <sep>)` rather than `string_agg(<value>, <sep>)`. Put it in EngineStyleH2 so EngineStyleDB2 inherits it — DB2's own extension spells `listagg(%s,%s)` (db2Extension.pure:99, note: no space after the comma; if a DB2 golden pins that, override the separator spacing in EngineStyleDB2 rather than forking the whole reducer). Then ledger the `personTable_d#4_d_m1` half: unreachable, same as the rest of the family.

**How legend-engine does it** — legend-engine .../core_relational/relational/tds/tests/testTDSFilter.pure:164 (H2 listagg golden, passing execute test) and .../sqlQueryToString/dbSpecific/db2/db2Extension.pure:99 (`dynaFnToSql('joinStrings', ^ToSql(format='listagg(%s,%s)'))`)

**Risk** — Fix (A) makes the ENGINE-TEXT string_agg unordered. That text is executed on the H2 second target in the M1 byte-match path (EngineTestExecutor.java:995-1008); on H2 an unordered listagg follows scan order, which is exactly what the engine's own goldens assume, so this should be neutral — but re-run the joinStrings row-verified tests (the Johnson*Hill / S1*S2 goldens named in the Lowerer comment) before landing. Tenet-2 trap: do NOT make the harness strip ' ORDER BY … rowid …' before comparing; the rowid leak is a lowering-channel defect the platform owns.

**Also unblocks** — Every corpus golden spelling `listagg(` (41 occurrences across the relationalStore generation module) — most currently pass on rows while carrying a silent advisory sql diff.

**Falsifier** — Render `Firm.all()->groupBy([f|$f.legalName], agg(x|$x.employees.firstName,y|$y->joinStrings('*')), ['legalName','employeesFirstName'])` through toSQLString/H2. If the ORDER BY rowid is absent, the Lowerer:1111 injection is not the source and fix (A) is unnecessary.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/lowering/Lowerer.java:1106-1116 — the ORDER-DETERMINISM block: `if (fn == SqlAgg.Fn.STRING_AGG && aggOrder.isEmpty() && value instanceof SqlExpr.Column vc && aliasIsBaseTable(...)) aggOrder = List.of(new SqlSelect.SortKey(new SqlExpr.RowOrder(vc.table()), true, null, null));` — no EngineTextBoundary guard
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:939-942 — `rowOrder` renders `'"' + rename(ro.table()) + "\".rowid"` , the `"persontable_0".rowid` in the observed got
- core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1205-1214 — reducer lowercases the SqlAgg.Fn enum name; there is no STRING_AGG spelling override anywhere in the file (grep of the class shows STRING_AGG only inside the joinStringsFlat literal-list recogniser at :111-125)
- core/src/main/java/com/legend/lowering/Lowerer.java:3158 — `if (c.wire() && EngineTextBoundary.active())` — the existing precedent for splitting lowering between execution and engine-text
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:2419-2428 — isSqlText only matches AppliedFunctions named sqlRemoveFormatting/sql, so `$h2Sql` falls through to the plain assertEquals eval at 1850-1897
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:137-146 — the test body; the golden is `listagg("personTable_d#4_d_m1".FIRSTNAME, '*')`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tds/tests/testTDSFilter.pure:164 — a PASSING execute() golden in the same corpus: `listagg("addresstable_0".NAME, ',')` — confirms `listagg(col, sep)` is the corpus's H2 spelling on both surfaces
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:460-464 — the checked-out H2 DbExtension actually spells `group_concat(<col> separator <sep> )`; the corpus goldens and the extension disagree, and the goldens that spell group_concat (testTDSFilter.pure:173,183) are tagged <<test.ToFix>>

</details>

---

## `testToSQLStringWithAbs`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Typer.classReference's escape hatch for engine signature-mangled function references is gated on the wrong condition, so a valid metadata-only reference throws. The test's second statement is `runTestCaseById('testToSQLStringWithAbs_2')`, whose body evaluates `testCasesForDocGeneration()` — a collection of two ^TestCase instances, the FIRST of which carries `generateUsageFor = [meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_]`. Typing that reference: ctx.findFunction(full mangled name) misses; functionCandidates then demangles to base `meta::pure::tds::groupBy`, arity 3, return-type name 'TabularDataSet', and filters candidates with `f.returnType().typeName().endsWith(ret)` (Typer.java:2148-2160). legend-lite's two `meta::pure::tds::groupBy` natives (Pure.java:1350-1351) are the modern Relation-typed ones returning `Relation<Z+R>`, so the return-name filter empties the list. Back in classReference, the 'a mangled id naming an overload we don't carry standalone' branch that produces an opaque `Function<Any>[1]` ref is inside `if (fns.size() > 1)` (Typer.java:2249-2270) — unreachable once the filter returned zero — so control falls to the ResolutionException at Typer.java:2294, which is the exact message in the brief.

**Fix**

In core/src/main/java/com/legend/compiler/spec/Typer.java:2248, before the `fns.size() > 1` branch, add the zero-candidate arm for mangled ids: if `fns.isEmpty()` AND `SignatureMangle.tailStart(ref.fullPath()) >= 0` AND `!ctx.findFunction(SignatureMangle.stripTail(ref.fullPath())).isEmpty()` (the BASE name is known, we simply carry no overload with that exact signature), return the same opaque value the existing branch returns: `new TypedPackageableRef(ref.fullPath(), ExprType.one(new Type.GenericType("meta::pure::metamodel::function::Function", List.of(InferenceKernel.anyType()))))`. Keep functionCandidates strict — eta-expansion must stay exact — so the only behaviour change is 'a metadata reference to a known base name with an unknown signature is an opaque Function value' instead of a hard resolution failure; invoking it still walls loudly at its own call site, which is the comment's stated contract. Refactor the two branches to share one helper so the opaque-ref construction lives in one place. This alone will NOT make the test green: the next statement calls toSQLString with `$testCase.query`, which StatementExecutor.toSqlString rejects at line 380-383 because it is not a TypedLambda literal. Closing that needs host-side folding of `^TestCase(...)` collection → filter → toOne → property read down to the literal lambda before the K-native sees it; treat it as a separate, larger item.

**How legend-engine does it** — legend-engine .../core_relational/relational/transform/fromPure/tests/testToSQLString.pure:41-62 — the ^TestCase records whose `generateUsageFor: Function<Any>[*]` slot holds mangled function ids purely as documentation metadata; they are never evaluated

**⚠ Correction from adversarial review** — The typing arm as written is fine (add, right after Typer.java:2248, an `fns.isEmpty() && SignatureMangle.tailStart(ref.fullPath()) >= 0 && !ctx.findFunction(SignatureMangle.stripTail(...)).isEmpty()` arm returning the same opaque TypedPackageableRef, shared with the existing branch via one helper). But it must not be scheduled as the fix for this test. The item that closes testToSQLStringWithAbs additionally needs host-side folding of `^TestCase(...)` collection -> filter -> toOne -> property read down to the literal lambda/mapping/enum/string before the toSQLString K-native inspects its arguments; no such fold exists today (I checked every fold in StatementExecutor). Split into two items: (a) XS/S typer arm, (b) L instance-graph folding.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is fully confirmed — every citation resolves and says what is claimed, and the brief's observed error text ('meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_' is not a known class, mapping, runtime, connection, or database) is emitted from exactly one place in the tree (Typer.java:2294-2297), reachable only after functionCandidates returned an EMPTY list. I verified each link: the mangled tail parses to arity 3 with return-name 'TabularDataSet' (SignatureMangle.tailArity/tailReturnTypeName), legend-lite's only two meta::pure::tds::groupBy natives are 3-param Relation<Z+R> returners, and Type.GenericType.typeName() renders 'Relation<Z+R>' which cannot endsWith('TabularDataSet'), so the filter at Typer.java:2156-2160 empties the candidate list; with fns.size()==0 the opaque-ref escape hatch (inside `if (fns.size() > 1)`) is unreachable and control falls to the throw. The proposed guard would also fire: Pure.Index.FN_BY_FQN is FQN-keyed, so ctx.findFunction('meta::pure::tds::groupBy') is non-empty (the falsifier's second branch is unlikely). What does NOT hold up is the FIX AS A WORK ITEM. The diagnosis itself concedes the change will not make the test green — the next statement hits StatementExecutor.java:379-383 ('toSQLString whose query argument is not a lambda literal') because runTestCaseById passes $testCase.query, a property read off a ^TestCase reached through filter/toOne. I grepped StatementExecutor for any host-side folding that could reduce ^TestCase(...)->filter(...)->toOne().query to a TypedLambda and found none (the existing folds are foldPairProjection, the connection-builder fold, the activity-envelope reads, and the let/lambda folds — none touch instance construction). So scheduling this as 'effort S closes testToSQLStringWithAbs' is wrong: the S-sized edit only relocates the wall. The typing arm is genuinely XS/S; the whole test is L (needs host-side constant-folding of an instance-graph literal through filter/toOne/property-read, plus the mapping/dbType/expectedSql reads on the same instance).

</details>

**Risk** — Widening the opaque-ref path can mask genuine typos in mangled references. The `base name must exist` guard keeps it tight, but a mangled id whose base name coincidentally exists with a different arity would now type as Function<Any> instead of erroring — accept that only because such a reference cannot be invoked without walling at the call site. Tenet-2 trap: do NOT make the harness skip generateUsageFor or pre-strip the mangled tail; the reference is ordinary Pure the typer owns.

**Also unblocks** — testToSQLStringWithAggregation (bucket 9, same file testToSQLString.pure:92-95) fails with the identical message from the identical reference — it is the same ^TestCase list. Any other corpus element carrying generateUsageFor-style mangled metadata refs.

**Falsifier** — Type the bare expression `[meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_]`. If it types today (no ResolutionException), the failure is somewhere else in testCasesForDocGeneration and this diagnosis is wrong. Conversely, if `ctx.findFunction("meta::pure::tds::groupBy")` returns nothing at all, the base-name-known guard I propose will not fire and the fix must be widened.

<details><summary>Evidence read (8 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/Typer.java:2148-2160 — functionCandidates demangle: `return ctx.findFunction(base).stream().filter(f -> f.parameters().size() == arity && f.returnType().typeName().endsWith(String.valueOf(ret))).toList();`
- core/src/main/java/com/legend/compiler/spec/Typer.java:2248-2270 — `List<TypedFunction> fns = functionCandidates(ref.fullPath()); if (fns.size() > 1) { … byArity … else { return new TypedPackageableRef(ref.fullPath(), ExprType.one(Function<Any>)); } }` — the opaque-ref escape hatch, whose own comment names 'the legacy TDS groupBy the checker desugars at call sites'
- core/src/main/java/com/legend/compiler/spec/Typer.java:2293-2296 — the throw: `'" + ref.fullPath() + "' is not a known class, mapping, runtime, connection, or database`
- core/src/main/java/com/legend/builtin/Pure.java:1350-1351 — the only two meta::pure::tds::groupBy natives, both `…):meta::pure::metamodel::relation::Relation<Z+R>[1];` with 3 parameters — arity matches, return name 'TabularDataSet' does not
- core/src/main/java/com/legend/compiler/spec/SignatureMangle.java:44-58 — tailArity counts one `_Type_mult` segment per parameter plus the return, so the 4-segment tail yields arity 3
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:41-72 — testCasesForDocGeneration()'s first ^TestCase carries the mangled groupBy ref in generateUsageFor; runTestCaseById filters the list by id
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:129-134 — testToSQLStringWithAbs: an abs golden assert then `runTestCaseById('testToSQLStringWithAbs_2')`
- core/src/main/java/com/legend/StatementExecutor.java:380-383 — `throw new NotImplementedException("toSQLString whose query argument is not a lambda literal")` — the wall this test will hit next, since runTestCaseById passes `$testCase.query` (a property read off a ^TestCase) as toSQLString's first argument

</details>

---

## `testToSQLStringWithCodeBlock`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | medium |

**Root cause**

The golden side renders as null because generating the SQL throws, and ExecCallFinder.sideSqlText swallows it. The query is a multi-statement code block whose first statement is `let endDate = %2015-01-01->add(^Duration(number=1, unit=DurationUnit.MONTHS));`. legend-lite carries the `meta::pure::functions::date::DurationUnit` ENUM (Pure.java:667-672) but no `meta::pure::functions::date::Duration` CLASS and no `meta::pure::functions::date::add(Date[1], Duration[1])` overload — grepping core/src/main/java/com/legend/builtin for `Duration` excluding `DurationUnit` returns nothing. So `^Duration(...)` cannot be typed. sideSqlText catches the RuntimeException and returns null (ExecCallFinder.java:151-157); sqlTextVerify then has golden != null but sql == null, falls to h2Upgrade, which declines (no recorded seeds — the test never executes) and returns ADVISORY_MARKER; scoreAssert counts it as advisory only (EngineTestExecutor.java:900-905), and Runner.score reports SHAPE 'sql-only: 1 advisory golden-SQL assert(s), no row verification' (Runner.java:1460-1464). The test's other exotic constructs — the path literal `#/…Trade/date#` passed as a `Function<{T[1]->Date[0..1]}>[1]` parameter and applied via `$x->map($path)` in the generic helper filterReportDates — are the next candidates if the Duration one is fixed; path literals themselves are parsed (SpecParser.java:730) and desugared at typing (Typer.java:127-129).

**Fix**

Add the missing date surface to builtin/Pure.java: (1) a `meta::pure::functions::date::Duration` class with `number:Integer[1]` and `unit:DurationUnit[1]` (mirroring legend-pure _structures.pure:66), and (2) the three `meta::pure::functions::date::add(Date|StrictDate|DateTime[1], Duration[1])` overloads, folded HOST-SIDE the way the existing adjust native is (Pure.java:1114 ADJUST__DATE_1__INTEGER_1__DURATION_UNIT_1 already carries the Date+n+DurationUnit shift), so `%2015-01-01->add(^Duration(number=1,unit=MONTHS))` folds to the literal 2015-02-01 the golden's `<= '2015-02-01'` needs. Separately — and this is worth doing regardless — stop ExecCallFinder.sideSqlText (ExecCallFinder.java:151-157) from erasing the cause: return the exception text through a side channel so a generation failure reports as a named platform wall rather than as a silent 'no row verification' advisory. Under the tenets a loud wall is the required outcome here; the current SHAPE line hides which feature is missing.

**How legend-engine does it** — legend-pure .../platform/pure/essential/date/_structures.pure:66 (Class Duration) and legend-engine .../core/pure/corefunctions/dateExtension.pure:507-517 (the three add(date, duration) overloads)

**Risk** — Adding a Duration class touches the shared builtin surface; make sure the new `add` overloads do not shadow the collection `add(T[*],T[1])` used everywhere in the corpus (they differ in the second parameter's type, but the overload scorer must actually discriminate on it). Tenet-2 trap: do NOT special-case this test's date arithmetic in the harness's foldString/eval path.

**Also unblocks** — Any corpus test constructing ^Duration or calling date::add with a Duration.

**Falsifier** — Re-run this one test with LL_SQLTEXT_DEBUG=1 and read the `[sql-text] side unverifiable: …` line (ExecCallFinder.java:154). If the exception is not about Duration/add — e.g. it names the path-literal parameter, `map` over a Function-typed variable, or the multi-statement lambda body — then Duration is not the blocking cause and the fix targets the wrong surface.

<details><summary>Evidence read (9 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:667-672 — `DURATION_UNIT = nativeEnum("Enum meta::pure::functions::date::DurationUnit { YEARS, MONTHS, … }")`; a grep of core/src/main/java/com/legend/builtin for `Duration` minus `DurationUnit` returns zero hits, so there is no Duration class and no date::add(Date,Duration)
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/date/_structures.pure:66 — `Class meta::pure::functions::date::Duration` exists in real pure
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/corefunctions/dateExtension.pure:507,512,517 — the three `meta::pure::functions::date::add(Date|StrictDate|DateTime [1], duration:Duration[1])` overloads legend-lite lacks
- core/src/main/java/com/legend/harness/ExecCallFinder.java:151-157 — `catch (RuntimeException | java.sql.SQLException e) { if (System.getenv("LL_SQLTEXT_DEBUG") != null) …; return null; }` — the swallow that turns any generation failure into an advisory
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:1015-1017 — sqlTextVerify's tail `return h2Upgrade(...)` when golden or sql is null
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:900-905 — scoreAssert: ADVISORY_MARKER increments counters[1] and returns null (no failure recorded)
- core/src/test/java/com/legend/rcorpus/Runner.java:1460-1464 — `yield new Outcome(fqn, Status.SHAPE, "sql-only: " + r.advisory() + " advisory golden-SQL assert(s), no row verification")` — the exact brief message
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/transform/fromPure/tests/testToSQLStringWithCodeBlock — testToSQLString.pure:160-175 plus the helper filterReportDates at :177-180
- core/src/main/java/com/legend/parser/SpecParser.java:730 — `case PATH_LITERAL -> parsePathLiteral();` and core/src/main/java/com/legend/compiler/spec/Typer.java:127-129 — `case PathLiteral pl -> synth(pl.desugared(), env);` — path literals are supported, so they are not the first suspect

</details>

---
