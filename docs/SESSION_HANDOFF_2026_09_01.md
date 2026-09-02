# Harness-Deletion Burn — Session Handoff (2026-09-01)

## UPDATE (later the same day) — STEP 1+2 DONE, foundation sound
The diagnose-first probe ran (record: `docs/PLATFORM_FAIL_ADJUDICATION_2026_09_01.md`).
- **Null-vs-value (3): ENGINE DEFECT, not ours.** Hand-computed from the seed rows + mapping, our `Fabrice,Oliver` is the Pure answer; the golden binds `$e.age` to the ROOT person (the engine corpus itself carries a `test.ToFix` sibling admitting the column "fails to resolve against inner relation"). Stays a counted platform-fail row.
- The other 6 of the "9": conventions / narrow bindings, all loud, all named in the burn map (see the record). Blast-radius census for the join-chain binding: ONE mapping corpus-wide.
- **Two REAL burns the earlier audit missed, both landed:** (1) mapping-seam window rule (`testMappingWithWindowColumn`: class filter had folded INTO the ~func's RANK() select) — `extentBoundary` stamp + lowerer isolate + unit test; (2) ONE TEST CLOCK — the ratchet was time-of-day dependent (H2 oracle local zone vs DuckDB UTC on `dateDiff(..., now())` goldens); root pom pins `-Duser.timezone=GMT` like the engine, and the comparison seam declines BY NAME a projected `datediff`-to-now (two instants can never be a reproducible row verdict; text is the contract there, §3.7).
- **Ratchet now 871 fallbacks / 1702 flipped**, corpus 2350. The numbers below are the pre-probe state.
- **STEP 3 re-sized by census (record, "STEP 3 census" section):** "scalar lowerings 76" = 42 asserts reaching the lowerer in EXPRESSION position (a verdict-placement leg) + 43 mapping-metamodel query functions (the quarantined family) + 1 native; "unported natives ~70" = 25 legacy TDS `join` predicate rows not typing as TDS rows (one typed-form leg; the getters exist) + 42 harness vocabulary + ~20 small named legs. Neither bucket is mechanical volume. Honest next legs: TDS join-predicate row typing (25) and assert-in-expression placement (42).
- **TDS join let-bound JoinType LANDED** (`JoinChecker.resolveLetBoundArgs`): +23 flips, **ratchet 848 fallbacks / 1725 flipped**. Residue 2 = let-bound `{a:TDSRow[1],b:TDSRow[1]|…}` condition (deferred-kind candidate, bind-once charter). Next: TDG carrier frame (29+3, STEP 4 below), dialect-loop unroll (13).

## Where things stand — RIGHT NOW
- **Repo:** `/Users/neema/legend/legend-lite`, branch `main`, **tree clean, all pushed.**
- **HEAD:** `a8f5cb59` (flip-fail-debug observability keeper).
- **Ratchet:** 876 fallbacks / 1697 flipped (the migration counter — flipped = tests moved to the platform verdict; fallbacks = tests still scored by the legacy "walk", each with a named reason). Endgame: fallbacks → 0-or-named-by-design, then delete EngineTestExecutor's ~1,800-line statement walk + satellites.
- **Standing quality gates all GREEN** through HEAD: canon disagree EXACT 21, sql-verdict disagree 0, corpus byte-stable, full 8-gate chain (`tools/allgates.sh`) passes in ~5.3 min.

## Commits landed this session (oldest→newest)
1. `7978c668` — **TDG catalog-spelling burn.** The "Any-property checker" leg dissolved by census: 46 walls were 3 widened native class spellings in OUR catalog (`Pure.java`) shadowing the corpus's real declarations (classDef is native-first). Fixed to real pure spellings: `RowIdentifier.columnValuePairs → Pair<String,Any>[*]`, `Table.schema → Schema[1]`, `Schema.name → String[1]`. Ratchet 943/1630 → 928/1645, corpus 2348→2349.
2. `97dfd892` — **Effectful cutover.** Deleted the static verb-classification gate (~90 lines that scanned SQL text to decide walk-re-run safety). Replaced by DB transaction: effect-bearing bodies run in a txn on the session connection — commit after verdicts pass, rollback + ledger-truncate + mirror-repair on failure so the walk's fallback re-run starts pristine. Ratchet 928/1645 → 878/1695. **NOTE the process lesson:** my first conservation receipt used the WRONG counters (emission census = text channel; canon-line "sql-verdict" = the dual-verdict alarm — neither proves row verification). Correct receipt: text-verdict decline census byte-identical (102=102) + the rule that a declined rows-leg can't flip. **Audit conservation on the RIGHT instrument before moving any verification floor.**
3. `59215baa` — **Placement fix** (from user probe "right place if it survives?"). The rollback protocol (txn + ledger mark + mirror repair) is ONE invariant, now owned by `ReplayOracle.beginAttempt/commitAttempt/rollbackAttempt`; `WholeTestFlip` only drives it. Platform stays transaction-policy-free.
4. `03aa3b4f` — **TDG let-adoption.** The 31 "unclassified argument Variable" walls (surfaced when the effectful gate burned) → 0. `SourceSubst.resolveStructuralArgs` now adopts TDG data-constructor shapes DEEP (`tdgCtorShape`). Ratchet 878/1695 → 876/1697.
5. `b2ab5b22` — **FQN hardening.** `tdgCtorShape` matches exact FQNs (`TDG_CTOR_FQNS`) instead of simple names (doctrine: exact-FQN identification, never suffix/name matching). Behavior-identical.
6. `a8f5cb59` — **flip-fail-debug keeper.** The platform-fail/wall-exec catch arms only recorded the SCRUBBED `bucketOf()` form; the full failure message was never printed. Added env-gated (`LL_TMP_DEBUG`) stderr prints, parity with the existing `[flip-wall-debug]`. This is what made the audit below possible.

## THE AUDIT (last real work — user asked "how many real failures, really?")
Ran a full sweep with `LL_TMP_DEBUG=1` and read every failure message. **The earlier "~81 real wrong-answers" figure was badly overstated.**

Of 23 assert expected/actual mismatches, most are cosmetic:
- 3 = dialect spelling (`TOP 20` vs `LIMIT 20`) — the row-comparison arm resolves these; not bugs.
- 7 = printed SQL/plan text formatting differences — not bugs.
- ~4 = auto-generated lambda variable names (`x` vs `_i0`) — cosmetic.

**The real burn list is 9 content mismatches, in 4 patterns:**
1. **Duplicate rows dropped (2):** `testIsolatioWhereNoConstaintsAndInnerJoin`, `testMultipleJoinsInPropertyMappingWithDatesInClass`. Expected repeats each row (join fan-out), we dedup.
2. **Null-vs-value (3) — MOST BUG-LIKE:** `testMixedMappingWithFilterInProject`, `testSimpleMappingQueryWithFilterInProject`, + sibling. Expected `Fabrice,null`, we return `Fabrice,Oliver` — a filter that should null a joined value isn't applied. **This is the recommended next target** — smells like a genuine correctness bug regardless of harness deletion.
3. **Empty-vs-null encoding (2):** `testSQLQueryMergingForInnerJoins`(+2). Expected `[]`, we return `TDSNull`.
4. **Union column padding (2):** `testUnionTwoRelationMappings_ManyColumnProject`(+Single). Expected `null` in unfilled union columns, we return empty string.

## DIAGNOSED BUT NOT BUILT (deliberately stopped to report)
The 3 dialect-spelling TDG tests (`testConstant`, `testSimpleSingleTableWithNoDataToInsert`, one more) do `assertEquals('...H2 golden SQL...', $testData.sqls->at(0))`. This is a fetch-text compare that should judge on ROWS (arm `tryArmTdgSql` exists). It doesn't fire because:
- `let testData = generateTestData(...)` is **effectful** → the executor runs it once and does NOT add it to `letPrefix` (`StatementExecutor.java:232` routes effectful lets to `executeCallStatement` + `continue`, skipping `letPrefix.add`).
- The arm chases `$testData` through `letPrefix` to find the TDG carrier — can't find it — falls through to the dumb string compare and fails.
- **The real fix:** register the TDG carrier in `execFrames` the way execute-natives are (`StatementExecutor.java:169-177`, the `isExecuteFqn` branch), so `$testData.sqls` splices from the executed frame. This is frame-path work, same CLASS as the plan-execute values-binding leg — NOT a small patch.

## THE COMPLETE BLOCKER INVENTORY — FOUR LAYERS
These are DIFFERENT KINDS of blocker, not one flat list. All four must
resolve before the walk can delete. Read the framing, not just the numbers.

### LAYER 1 — the 876 DuckDB-lane fallbacks (the walk's remaining scoring load)
The main list. Each is a test still scored by the walk, with a named reason.
- **Metamodel-as-relations family, ~270** — THE big architectural leg (see GAPS below — designed only to leg-1 depth). Class queries over TypedMap (65) + mapping::sql (45), FunctionDefinition metaprogramming bodies (69), lineage scanRelations (21), misc metamodel-property walls. Quarantined by [[metamodel-in-database-ruling]]; fix = metamodel AS RELATIONS in the DB (Class.all() route, resolver side-output rows, recursive CTEs), NOT per-bucket patches. Design lives in `docs/PROGRAM_MAP.md` (metamodel-as-data leg 1 = plan-nodes-as-rows) + `docs/METAMODEL_MACHINERY_CENSUS.md`.
- **Scalar lowerings 76** — "no scalar lowering registered" (mechanical, family-batchable).
- **Unported natives ~70** — unknown-function rows (mechanical, census-batched).
- **Join-condition-whole-variable 43** — one design leg (only column reads can correlate).
- **Filter-predicate isolation 25; multiplicity-stamp/compat 22; execution-activities-not-recorded 14; plan-execute values-binding 10** (named, referee-bindings design sketched in charter §5); **array-literal dialect capability 9;** smaller named families ~40; **long tail 279 across 172 buckets** (mostly singletons that burn incidentally as the big legs land).
- **text-policy 65** — ~25 plan-program replayer cohort (the §5 full replayer: run allocations, force the temp-table conditional BOTH WAYS — inline + >50 temp-table) + TDG-excluded-by-design + mixed/multi-statement residue.
- **platform-fail ~81 → AUDITED DOWN TO 9 REAL** (see THE AUDIT above; the rest are cosmetic — dialect spelling / plan-text formatting / auto-generated names). The 9 are the genuine correctness burn list.

### LAYER 2 — verdict-side residue (NOT in the 876 fallback count)
Separate accounting; these are quality-gate numbers, not fallbacks.
- **Canon disagree EXACT 21** — the calendarAggregations float-print class; burns on the chartered ULP-carrier leg (charter §6 row 3), decided not started.
- **Arm decline census 102** — 44 plan-related (die with the replayer); **18 foreign-dialect DB2/Composite = PERMANENT NAMED CENSUS, never burns by design**; singletons.
- **The walk-accounting numbers** (exec-passing 345, text-only 40, unable-to-exec 20, rescued 165, sqldiff 12, advisory 14, 0-asserts 30) are BOOKKEEPING of the 876, not separate work — they dissolve as their tests flip. Do not treat them as extra items.

### LAYER 3 — standing commitments (do NOT block deletion, but owed)
- **§6 normalization-inventory upgrades** (charter §6 table): ULP-based float compare (#3, retires the canon-21 class), TIMESTAMP_NS leg (#4, PROVEN reducible — duckdb_jdbc 1.5 round-trips nanos), our-column projection instead of arity-decline (#11), null non-printable sentinel (#1). Small individual legs, each its own commit.
- **The emission census** (396 text-matched / 812 text-diverged / 102 text-verdict) = slice-6's SHRINK-ONLY spelling ratchet. Dialect spelling work retires rows class-by-class. **NEVER a verdict, NEVER blocks deletion** — it's a cosmetic-convergence tracker only.

### LAYER 4 — the UNDECIDED open question (a real deletion blocker)
The **h2-backend lane (gate G5)** has its OWN flip profile: **1,618 fallbacks, dominated by 863 UNNEST-placement dialect-capability walls.** The walk scores THIS lane too, so the "876" is only the DuckDB lane — full walk deletion also needs the h2 lane at zero. Decision nobody has made: grow the H2 execution dialect's capabilities (unnest placement, list encodings) vs. redefine the advisory-H2 lane so it doesn't need walk scoring. Measure-first; NO plan exists yet (see GAPS).

### WHAT ACTUALLY DELETES at the end
EngineTestExecutor's statement walk + satellites (~1,800 test-side lines), the v7 dual-channel classifier, the TestDataGenForm twin, FlipProbe, the text-taint prescan (`WholeTestFlip` §D prescan) — and the atomic-attempt call site (begin/commit/rollbackAttempt) moves from WholeTestFlip into the runner as permanent family-session failure hygiene.

## GAPS — NOT YET PLANNED (do not mistake pointers for plans)
The near-term work above is build-ready. These are NOT, and gate true "to zero":
1. **Metamodel-as-relations (~270, Layer 1)** — designed only to LEG-1 depth (plan-nodes-as-rows, one acceptance test in PROGRAM_MAP.md). The full arc (200+ metamodel-query classes → DB relations) needs a real design session, not just execution.
2. **The h2-lane (1,618, Layer 4)** — NO plan at all, only a flagged decision. Might be as much work as everything else combined.
3. **The deletion procedure itself** — WHAT deletes is listed above; the ORDER, preconditions, and post-deletion runner shape are NOT written.
4. **The precise end-state** — "zero" means "zero UNNAMED"; permanent named residue stays (foreign-dialect 18, by-design-unrunnable). The full permanent-allowed set is not enumerated, so "done" is not crisply defined.

## SUGGESTED NEXT SEQUENCING — DIAGNOSE-FIRST, then let the finding pick the path

**Why NOT start with the big mechanical buckets (scalar 76 / natives 70).** They're 16x the volume and tempting for ratchet throughput, BUT a missing-feature wall is self-announcing — it THROWS, is counted, falls back correctly, and can never silently return a wrong number. The 9 real divergences are the opposite: the only place we produced a plausible-looking WRONG ANSWER. The whole program rests on the claim that the 1697 flipped tests are TRULY row-verified, not just passing. If our filter lowering can drop a null-out (the null-vs-value case), that defect could be silently corrupting some of those 1697 "verified" flips with no wall firing — which would undermine trust in the ratchet itself. You can't safely rack up 146 mechanical flips on a foundation you haven't confirmed is semantically sound. So: probe the foundation first (it's cheap), THEN do volume on solid ground.

**STEP 1 — TIME-BOXED diagnosis of the null-vs-value group (pattern #2, 3 tests).** Reproduce `testMixedMappingWithFilterInProject` with `-Dlegend.corpus.containing=`, read the lowered SQL (`LL_TMP_DEBUG` / `LEGEND_LITE_DUMP_SQL`), find where the filter's null-out is lost (expected `Fabrice,null`, we return `Fabrice,Oliver`). This is a PROBE of foundation soundness, not just 3 tests.
  - **If it IS a real lowering bug** → it's the priority, full stop. Fix it (it may be silently affecting passing flips too), verify no other tests move unexpectedly, then proceed to mechanical with confidence.
  - **If it turns out benign** (a representation/convention difference, the way the dedup ×2 and empty-vs-null ×2 groups probably are) → NOTE it in the audit record and PIVOT to the mechanical buckets (STEP 3). You've spent ~an hour to buy confidence.

**STEP 2 — the rest of the 9, only if quick:** dup-rows-dropped ×2, empty-vs-null ×2, union-padding ×2 (all in THE AUDIT section). These look more like convention decisions than bugs — adjudicate, don't grind.

**STEP 3 — the mechanical volume (post-confidence):** scalar lowerings 76 + unported natives ~70. NOTE: "unported natives" is not thoughtless — each signature must be verified against real legend-pure ([[verify-signatures-against-real-legend-pure]]; the Any-property catalog widening this session was exactly this doctrine violated). Predictable, not free.

**STEP 4 — TDG-carrier frame registration** (flips the 3 dialect TOP/LIMIT tests + likely more): register the carrier in `execFrames` like execute-natives (StatementExecutor.java:169-177) so `$testData.sqls` splices from the executed frame and `tryArmTdgSql` reaches it. Fully diagnosed in "DIAGNOSED BUT NOT BUILT" above. OR fold into the plan-execute values-binding leg (both = "executed producer whose result an assert reads").

**STEP 5+ — the longer arc:** plan-program replayer (~80 rows) → join-cond 43 → metamodel-as-relations ~270 (the game-changer, needs a DESIGN pass first — see GAPS) → ULP (canon 21→0) → h2-lane decision (see GAPS) → DELETE the walk.

## OPERATIONAL NOTES (learned the hard way)
- **Gate chain:** `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure caffeinate -dims tools/allgates.sh`. Upstream checkouts are at **/Users/neemsandv** (NOT $HOME/legend, which is a stale July tag).
- **Single measuring sweep:** `LL_TMP_DEBUG=1 mvn -o -pl core test -Dtest=RelationalCorpusRunner -Dlegend.engine.root=... -Dlegend.pure.root=...` (~90s; longer = a hang, jstack it). Add `-Dlegend.corpus.containing=<substr>` to focus.
- **`LL_TMP_DEBUG=1` unmasks:** flip wall reasons (`[flip-wall-debug]`), platform-fail/wall-exec messages (`[flip-fail-debug]`, new this session), effect-classification.
- **grep silently fails on sweep output files** — they contain canon keys with NUL bytes. **Use python or awk on sweep logs, never grep.**
- **Fallback census file:** `core/target/wholetest-flip-fallbacks.txt` (THE burn map, with witnesses). Flip roster: `core/target/wholetest-flipped.txt`. Verdict roster: `core/target/h2-verdicts.txt`.
- **Pin discipline:** ratchet + M1 floors + lane counts live in `core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java` (~lines 940–1580). Pins move ONLY in the same commit as their burn, WITH written justification. Paired same-tree sweeps must be byte-identical before moving any pin (wobbles get fixed, never enveloped).
- **Charter (authoritative design):** `docs/SQLTEXT_ROW_VERDICT_CHARTER.md`. Rule §0: a SQL-text assert's verdict is ALWAYS rows, never text (golden is H2-flavored, we execute DuckDB — identical text proves spelling not answer).
- **Tree FROZEN during any gate/sweep run** — zero repo writes (PX.1 tripwire voids the run; it diffs the tree mid-chain).

## MEMORY FILES (already updated this session)
`sqltext-row-verdict-charter`, `harness-deletion-program`, `MEMORY.md` index — all reflect ratchet 876/1697, effectful gate deleted, Any-property leg dissolved, the process lesson about auditing conservation on the right counter, AND the platform-fail audit (9 real in 4 patterns, not 81) with the TDG-carrier fix diagnosis.
