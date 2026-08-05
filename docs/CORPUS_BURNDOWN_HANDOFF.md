# Burn-down handoff — start here

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
