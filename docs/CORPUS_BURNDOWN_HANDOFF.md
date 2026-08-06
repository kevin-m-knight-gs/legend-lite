# Burn-down handoff — start here

> **LINEAGE LEG IN FLIGHT (2026-08-06, fourth stretch).** scanRelations
> 19 → 32 honest across two gated batches (`28e16e2e` + milestoning
> cols). Landed rungs: variant-split label grammars (STATIC = join
> names + no extent for constant-only; RUNTIME = condition mangle +
> extent root — testConstant pins both), `stripAliasBreadcrumbs`
> compare (both sides; §12 SETTLED — see banner below), milestoned
> tables print their window columns under the runtime scan. **13
> rows remain, diffs in scratchpad lineage5.log:** union trees
> (FirmSet1/FirmSet2 + PersonMaster per set — union roots print but
> children don't fork per set), per-occurrence join forks
> (testSameRelationsAtSameLevel: engine re-walks a shared join per
> DEMAND SITE; we dedup on chain identity), subtype branch selection
> (testSelectOnLeftSide: a ->select() prunes the non-matching subtype
> branch), tableToTDS join labels (quoted `"joinleft_"` grammar), OLAP
> groupBy, inheritance_2. Each is a walk-structure change in
> `buildRoots`/`walk`, not a label change.

> **THIRD-SESSION DELTA (2026-08-06).** Next-designs #1 and #4 are
> LANDED: views as join targets (`HopTarget` carries the view identity;
> PASS-2 view landings expand as relation frames) took the view-to-view
> (INNER) pair AND the milestoned depth-two nested row (the extra rungs:
> `resolveViewRefsInJoin` now sees through STACKED views —
> `viewChainReaches` + recursive substitution — and Expression PM bodies
> rewrite through view columns on the fallback route);
> `routedTargetSetOf` learned `Otherwise([setId])` fallback routing; and
> `materializeInlineEmbedded` resolves set ids across the include
> closure. Default scoreboard 2269 → 2273. **Trap that burned an hour:
> the corpus/gate baselines are generated against
> `/Users/neemsandv/legend/legend-engine` (+ `legend-pure`) — the
> `$HOME/legend/legend-engine` default is a STALE July tag; without
> `LEGEND_ENGINE_ROOT`/`LEGEND_PURE_ROOT` every sweep reports phantom
> regressions (families shrink, null-safe goldens flip legacy).**
> Stale index entries verified consumed: the math-extension port, the
> TIMESTAMP_NS literal, the TypedNewInstance fold arms, synthesis §2
> #10 (LATEST_DATE fold) and #12 (the silent-sum guard is now a loud
> fallthrough). Of the "object-space TypedFilter" 4-row bucket, THREE
> are upstream ToFix/aspirational — one real row remains
> (testVariableReferenceInMapWithNestedFilter, correlated-nav
> machinery).
>
> **Masked-wall census re-run (2026-08-06, §7 discipline).** Milestoning
> 17 → 5 (4× `toSQLString` non-lambda-literal query arg, 1 extension
> typing). The NEW dominant cluster is ONE dialect leg: **the
> EngineStyleH2 golden-re-render dialect has no list encoding** — 29
> walls across functions/tests (16× collection membership, 2×
> LIST_CONCAT, STRING_AGG, UNNEST, LIST_GET, 2× array literal) and
> tds/tests (4× LIST_GET, LIST_BOOL_OR/AND). One dialect
> list-encoding leg retires the whole cluster on the advisory channel.
> Remaining toSQLString SHAPE arms: non-lambda-literal query (8 across
> families) + mapping-argument-not-a-reference (3).
>
> **LANDED since:** #25 (the tdg VIEW FETCH + `perWebChildren`
> retirement in one commit, `9d40e8fb` — the §8.2 trio converted, +2;
> the extra rule found on the way: a VIEW child's seed with explicit
> row identifiers fetches BY IDS, the engine's `tablePk`
> short-circuit, but ONLY in the view arm — the blanket version broke
> self-join/union children). 100% ledger after the batch: **2349/2793**
> = 2368 − 21 (the lineage honesty drop below: 26 manufactured greens
> are now real FAIL targets) + 2. Also the lineage advisory-arm
> deletion (§8.3) — the
> scoreboard-drop procedure that works: pre-edit the committed
> baseline's ONE family cell to the honest count, then let gate 4
> rewrite the whole file (the runner has no override flag).
> scanRelations is now an honest 19/49 with 26 real FAILs for the
> plan-derived-tree leg.
>
> **#20 NEGATIVE RESULT (2026-08-06, measured then reverted).** The
> obvious implementation — `TypedJoinSlot.atMostOne` computed from PK
> coverage in `JoinChecker.slot`, `Pipelines.walkJoinSlot` cancelling
> only proven at-most-one joins — does NOT fix the witness
> (`testMultipleJoinsInPropertyMappingWithDatesInClass` still returns 3
> rows, not 6: its cancellation is NOT (only) `Pipelines.java:405`) and
> costs the h2-exec advisory floor 318 → 239 (kept joins collide
> duplicate column names on H2). The deeper truth: our normalizer
> HOISTS join chains eagerly, so for hundreds of passing tests the
> engine never emits the joins we elide — blanket keep is far wronger
> than blanket cancel. #19/#20 are ONE design leg over the demand
> model (which navigations are semantically demanded, incl. the
> NavMaterializer demand-withholding pin at :181-188), not two patches.
>
> **§12 alias-grammar question SETTLED with ground truth (2026-08-06,
> lineage diffs).** The engine's `_d#N_…_mN` label mangles decompose
> into SEMANTIC content (function prefix `equal_`/`and_`, column
> names, literal constants, table identities, tree-root-renders-as
> `root`) — all reproducible — plus alias BREADCRUMBS (`_d#2`, `_d_1`,
> `_m3`, `_l`, `_r`, `_md`, `_dy1`, `_f`) that are pureToSqlQuery's
> internal processing-step counters and are NOT worth emulating. The
> remedy (as §12 itself authorizes): EMIT the semantic mangle in
> treeString labels, and compare with the breadcrumbs stripped from
> the expected — everything semantic stays exactly verified. ~20 of
> the 26 honest lineage FAILs are label-only under this rule; the
> structural residue is testConstant (missing empty-column extent
> node), testSelectOnLeftSide (branch selection),
> testSameRelationsAtSameLevel (per-occurrence forks).
>
> **Still-open ranked work (synthesis §2, verified against code):**
> #19/20 as the JOINT demand-model leg (above), #25 test-data-gen
> view-node + `perWebChildren` retirement (one commit, §8.2 ledger),
> getAllForEachDate residuals (#2), datatype metamodel leaves (#3),
> the EngineStyleH2 list-encoding leg (census cluster, 29 walls).
>
> **#25 entry seams (scouted 2026-08-06):** the tdg view swap is
> `TestDataGenerator.expandIfView:575` (view Rel → seed expansion via
> `ScanRelations.viewExpansion:644`, which calls
> `expandView(..., perWebChildren=true)` at `:652`; the fork suffix is
> `ScanRelations.java:846-852`). The witness trio: `testSimpleViewRoot`
> (passes BY CANCELLATION — will break if either half lands alone),
> `testViewEmbeddedInChainedJoin` (assertSize 5, we emit 4 — the
> missing one is the VIEW's own fetch; its CSV names only 4 base
> tables, so the view fetch merges into a base table's rows),
> `testUnionViewOnView` (14 vs 12, two view fetches missing across
> arms). Ground the design in the engine's
> `generateTestDataForNestedViewTree` before writing code.
>
> **STATE AS OF `bc375a46` (2026-08-06, second session).** The ledger
> moved **484 → 430 non-passing (2,363/2,793 pass)** across 24 gated
> commits. §4's Tier 1 is DONE; so are the null-safe MIR node (§4's
> largest lever — note upstream #5028 split the doctrine: in-flow
> execute is LEGACY plain-equals, plan surfaces keep null-safe; the
> 113-golden census is stale, now 38+30), the engine test-ORDER fix
> (§5.1, +12), the ConnectionLets seed guard (§5.3, +4), the overload
> retry (§5.4 — SchemaInvariantException must NOT retry), setUpDataSQLs
> in all three faces (string/records/execution — the TEXT executes
> nowhere; the shared DuckDB catalog makes its schema cascades
> destructive), RelationReads expr-cols (+10, ledger-only rows),
> GraphEmission childClass (+2) and the pkOrderKeys/user-sortBy
> priority. **`BURNDOWN_EXPLANATIONS.md` is the per-test verdict
> ledger** (43 impossible/infeasible with evidence; regenerate with
> each 100%-ledger sweep).
>
> **The quick-win tier is exhausted. Next implementations, designs
> ready:**
> 1. **Views as join targets** (3 rows): reuse
>    `ViewRelation.viewRelationExpr` as the hop's target frame —
>    `hopTarget` (JoinChainEmission:710) has the seam; `HopTarget`
>    needs a frame-expr variant; both `requireNonViewTarget` callers
>    convert.
> 2. **getAllForEachDate residuals** (the extent machinery WORKS):
>    (a) ODC sentinel-defer — the generated-date outer reaches
>    `outerDatedWindowCond` via THREE paths (hoist :582, deferred :660,
>    direct :553); defer ALL context-date windows under for-each mode
>    and replay over the joined row (a working `replayGeneratedOuter
>    Windows` draft is described in the git history of this doc's
>    session); (b) frame-read host channel — HostEval Tabular model +
>    `evalWithValues`; the walls escape the EAGER let-frame (~:130) and
>    preRoot-execute (~:330) executor arms, instrument those first.
> 3. **Datatype metamodel leaves**: registration SHADOWS primitives via
>    the PRELUDE_TYPES simple-name index; blanket exclusion breaks 20+
>    tests — needs a prelude-exempt registration tier or curated
>    PRELUDE_COLLISIONS entries.
> 4. **fallbackSetId set-routing** (3 seams: Pass-1 hop → slot
>    metadata → resolver set dispatch).
> 5. §5's structural pair (join kinds → cardinality elision), the
>    object-space TypedFilter arm, and the lineage advisory-arm
>    deletion (baseline-negative: pair it with the plan-derived tree).
>
> **Traps re-earned this session**: temporal changes take 2-3 corpus
> iterations — never attempt them without headroom; verify yield
> claims against the INCLUDE-EXCLUDED sweep (default-sweep family
> diffs miss ledger-only rows); the average to-one elision feeds an
> UNNEST pattern-match (naive promotion breaks it); asin/acos guard
> removal needs a PCT-vs-relational channel split.

**Purpose.** You are picking up a burn-down of the relational corpus and/or the
architecture work behind it. This is the entry point. Read this first, then the three
documents it points at.

**Provenance.** Written 2026-08-05 from 23 parallel per-test studies covering **all 484
non-passing rows** of the 100% ledger, plus 12 feature-area architecture audits of the
code behind the *passing* tests. No sampling.

---

## 1. The three documents

Read in this order.

| # | file | what it gives you |
|---|---|---|
| 1 | [`CORPUS_STUDY_2026_08_ALL.md`](CORPUS_STUDY_2026_08_ALL.md) | **Synthesis.** The real denominator, the ranked fix list, the 8 silent wrong answers, what isn't our work, the five ways the verdict column lies, sequencing constraints, corrections to docs already in the tree. |
| 2 | [`CORPUS_BURNDOWN_INDEX.md`](CORPUS_BURNDOWN_INDEX.md) | **The working index.** ~180 cause groups organised by family — fix site, member tests, confidence. Scoped so you can run one `-Drcorpus.only=` at a time. This is the artifact the previous study lacked. |
| 3 | [`ARCHITECTURE_AUDIT_2026_08.md`](ARCHITECTURE_AUDIT_2026_08.md) | **Design.** Twelve feature-area verdicts and the unifying diagnosis. You need this only when you move from rows to structure. |

### Supporting, already on main

- **`RELATIONAL_CORPUS_ALL.md`** — the 100% ledger, 2793 tests / 2309 pass / 484 non-passing.
  **This is the denominator.** `RELATIONAL_CORPUS.md` is the stereotype-filtered view and
  hides 130+ rows.
- `CORPUS_STUDY_2026_08.md` — superseded; banner explains exactly what changed. Its
  per-test analysis remains valid for the 356 rows it covers.
- `CORPUS_TAXONOMY.md` — superseded as a taxonomy, but §4 (diagnostic defects X2–X5) and
  §7 ("Do NOT do these") are still authoritative and were not re-litigated.
- `GATES.md` — the gates you must keep green.

---

## 2. Four things to know before you run anything

**1. `-Drcorpus.includeExcluded` is required.**

The default sweep skips `<<test.ToFix>>` / `<<test.ExcludeAlloy>>` / `<<test.AlloyOnly>>`,
which is 130+ of the 484 rows. Several study agents could not reproduce their own batch
until they found this. Full form:

```
mvn -o -q test -pl engine -am -Dtest=RelationalCorpusRunner \
    -Drcorpus.only=<family> -Drcorpus.includeExcluded \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

**2. Run the masked-wall census first, for whatever family you are in.**

```
LL_SQLTEXT_DEBUG=1 mvn … -Drcorpus.only=<family> -Drcorpus.includeExcluded 2>&1 \
  | grep 'side unverifiable' | sort | uniq -c | sort -rn
```

`harness/ExecCallFinder.java:142-147` swallows every golden-re-render exception unless that
variable is set. In `milestoning` alone it hid **17 walls**, and the counts converted
directly into a fix ranking (a missing `toSQLString/5` overload turned out to block 6
tests). Costs one minute. Do it before reading anything else about your family.

**3. The 484 is not 484 units of work.**

Roughly a quarter is not engine work: 31 goldens disproven on evidence, ~12
upstream-admitted aspirational, ~35 harness defects, ~12 DuckDB artifacts, ~25 by design.
The index flags each inline — `✗G` golden is wrong, `H` harness, `B` backend, `D` by design.

**4. Other useful read-only switches.**

| var | reveals |
|---|---|
| `LEGEND_LITE_STACKS=1` | full stack at each wall |
| `LEGEND_LITE_DUMP_SQL=1` | emitted SQL per statement |
| `LL_TMP_DEBUG=1` | `[run] <fqn>` markers — needed to attribute SQL dumps to tests |
| `LEGEND_LITE_CMP_DEBUG=1` | expected/actual *kinds*, not just renderings |
| `-Drcorpus.backend=h2` | separates DuckDB artifacts from real defects |
| `-Drcorpus.perTestSessions` | separates cross-test seed pollution from real defects |

---

## 3. Two standing rules

Both earned expensively during the study.

**Before touching any ToFix row, find the passing sibling that differs in exactly one
dimension.** That technique found **31 wrong goldens**. Specimens: a golden expecting
`Oliver` in a `length == 7` group (six letters); a golden whose own SQL literal is
syntactically mangled, making the test un-runnable upstream; a golden reproduced exactly by
applying a filter to the wrong side of a self-association. **Two of the 31 carry no ToFix
marker** — those need a scoreboard exception, not a classification.

**Never group by error message.** Rows sharing `no scalar lowering registered for …` or
`TDS = one carrier` were checked and found to have unrelated causes. Grouping by message
template merges independent work and produces fix estimates that do not hold.

---

## 4. Where to start

### Tier 1 — near-free, two of them verified

| fix | rows |
|---|---:|
| `builtin/Pure.java:653-658` — add `LEGACY_SQL_NULL_UNSAFE_EQUALS` to the `Feature` enum. **Verified: both goldens are already emitted verbatim.** | 2 |
| `builtin/Pure.java:1474-1475` — add `EXECUTION_PLAN__2_P2` (2-arg form, 2-param lambda). Five passing siblings prove the surrounding logic is right. | 1 |
| `builtin/Pure.java:1751-1752` — add `assert(Boolean[1], Function<{->String[1]}>[1])`. **135 call sites** across the corpus. | 5+ |
| `harness/ExecCallFinder.java:124-138` — truncate the rebuilt `toSQLString` to 4 args instead of copying args 3..n. | 2+ |
| `harness/TestBody.java:1586` — `return unsupported(e.getMessage())`. Its three siblings already do. Pure signal recovery. | diagnostics |
| `harness/TestBody.java:1944` — give `assertSameSQL` the `PlanAsserts` pre-check `assertEquals` has. | 2 |
| `lowering/SqlPostProcessors.java:51-59` — also read the plain `sqlQueryPostProcessors` slot. | 1 |
| `compiler/element/StoreCompiler.java:34-70` — `findTableDef` never scans `db.views()`. | 1 |

### Then the largest single lever

**Null-safe equality → a semantic MIR node keyed on multiplicity, spelled by the dialect.**

`SqlFn.IS_DISTINCT` already exists and renders correctly at `AnsiSqlRenderer.java:456` — it
is wired only to `isDistinct` and is unreachable from `==` / `!=`. Corpus census: **113
goldens expect `is not distinct from`, 30 expect `is distinct from`, zero expect our
spelling.**

Delete the hardcoded expansion in `lowering/NullSemantics.java:66-142`; the `FILTER_POS`
ThreadLocal disappears with it rather than needing a new home. `FILTER_POS` is also simply
the wrong predicate — the engine keys on multiplicity with no position test at all, and the
code comment saying otherwise (`lowering/Scalars.java:79-81`, *"IS NOT DISTINCT FROM appears
in no golden"*) is false.

Confirmed independently by five per-test batches and one architecture audit.

**Adjacent and genuinely wrong SQL:** `EngineStyleH2.java:1021-1032`'s `OR` arm ignores
`parentPrec`, so `NOT (a=b OR both-null)` renders as `(NOT a=b) OR (both-null)` — **true when
both sides are null**, the opposite of intent. Worth fixing on its own merits.

### Then, in rough order of leverage

- `StatementExecutor.java:3133-3145` — re-point `setUpDataSQLs` from `CsvSeed` to a
  `Ddl.*StatementText` composition. **Those generators already emit the golden text
  character for character.** (3 rows)
- `resolver/CorrelatedSubselects.java:1976-2001` + `:2078-2107` — rebind `userVar` at every
  lambda boundary in the agg-demand scan. ~15 lines; closes the worst silent-wrong-rows
  class *and* makes the walls total (today the guard shares the failing gate).
- `normalizer/RelationReads.java:90-116` — accept `c.expr() != null`. The machinery
  (`Col.expr()`, `Col.bindSrc`) already exists. (10 rows)
- `compiler/spec/Typer.java:1440` + `:1451-1509` — retry the next-best overload when
  `typeLambda` raises. There is no such catch today; selection commits on non-lambda args
  only. (5+ rows, plus a two-line repro showing program meaning depends on source order)
- `resolver/Substitution.java:1871-1878` — add a `TypedNewInstance` constant-fold arm. (6+ rows)
- `lowering/Lowerer.java:2211` — emit `TIMESTAMP_NS` when subsecond length > 6. Proven with
  a standalone DuckDB repro. (5 rows, silent)

---

## 5. Three sequencing traps

**Removing a wall converts ERROR/SHAPE → FAIL before anything turns green.** Four agents
flagged this independently. Budget for the buckets to move the wrong way first; do not read
it as a regression.

**The test-data-gen view fix and the `perWebChildren` retirement are one commit.**
`expandView(perWebChildren=true)` forks a child chain per join web, and the fork's `+1`
exactly cancels the missing view SQL's `−1` in three tests. Either change alone breaks them.
Ledger in the synthesis §8. Note that even where counts match, the SQL text is wrong.

**Delete the lineage advisory arms first.** `scanRelations` reports 40/49; roughly 19 are
actually verified — the skip at `LineageRelationsForm.java:109-119` decides whether to check
the answer by *grepping the answer*, and a second arm launders
`NotImplementedException`s into advisory. Building the plan-derived tree before deleting
those manufactures the same false green.

Two smaller ones: the include-walk fix for inheritance is necessary but **not sufficient**
(it yields `stc_Car___person` and still not `stc_Bicycle___person`; the structural fix is one
layer up in `UnionSynthesis`); and for the multi-mapping ambiguity, fix the resolver's stale
`chainContext`, not just the harness overlay that exposes it — otherwise the resolver still
picks a mapping by luck.

---

## 6. Corrections to what is already in the tree

Do not inherit these.

**A wrong diagnosis, committed.** `LEG1_INNERJOIN_FAMILY.md:448-467` attributes the
join-order divergence to `StoreResolver.java:2808-2809`. Those paths drive *association*
joins; the witness's five joins are all class-mapping steps. The real cause is that join
emission follows the mapping's **property-mapping declaration order** while the engine's is
phase-based (projection block, then filter block).

**Three load-bearing comments that are factually false.**

- `sql/dialect/EngineStyleH2.java:1277-1283` — claims uppercase `dateadd` spellings are
  "firstDayOf\* FORMAT literals, never adjust units." The engine spells `adjust` units
  through `mapToDBUnitType`, which maps `DAYS → 'DAY'`.
- `lowering/Scalars.java:79-81` — *"IS NOT DISTINCT FROM appears in no golden."* It appears
  113 times, including in a test file named for it.
- `normalizer/MappingNormalizer.java:2358-2362` — *"the corpus only ever feeds
  'true'/'false'"*. Falsified by the very test that fails on it, which feeds `'something'`.

**For contrast, two comments worth imitating:** `Typer.java:2349-2352` names the exact test
that caused a fix to be reverted; `JoinChainEmission.java:801-842` is a documented
approximation with a *structural* soundness precondition and a loud wall.

---

## 7. Two open questions that are not the next session's to settle

**Is the engine's `_d#N_d#N_mN` alias grammar reproducible from our IR at all?** Two agents
flagged this independently and both declined to guess. It gates ~11 rows in
`transform/fromPure` plus scattered others. If it is not reproducible, the remedy on the
`toSQLString` surface is alias-insensitive comparison — very different work from alias parity.

**The `${tdsVar}` splice columns typed `Integer`.** `pureToSQLQuery.pure:581-585` hardcodes
`^Integer()` for every splice column; the affected goldens contradict their own adjacent
`type = TDS[…]` lines. Matching them means **deliberately reproducing an upstream bug** in
three tests. That is a product decision.

---

## 8. Known-hollow greens

Not counted in the 484 because these rows currently *pass*, but they are not evidence of
working features.

- **`aggregationAware` 13/13.** The parser silently skips the `Views:` block
  (`MappingGrammarParser.java:522-527`), so no rewrite can occur;
  `AggAwareActivities.rewrittenQuery` fabricates the routed print host-side; and a divergent
  SQL text that row-replays equal on H2 records as fully verified with **zero** divergence
  signal.
- **20 of 40 `scanRelations` passes are half-verified** — a static assert verifies while its
  runtime twin goes silently advisory at the same skip.
- **The `alloy::` test-data-gen family** reports PASS while exercising a walled feature. If
  those are neutralised rather than running, the silent-defect list is understated.

---

## 9. If you only do one thing

Run the masked-wall census (§2.2) for the three largest families, then land Tier 1 (§4).
That is under a day, it is nearly all verified, and it will tell you more about the true
shape of the remaining work than any amount of further reading.
