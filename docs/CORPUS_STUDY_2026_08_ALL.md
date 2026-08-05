# Corpus study — ALL 484 non-passing rows (2026-08-05)

> **New here? Start with [`CORPUS_BURNDOWN_HANDOFF.md`](CORPUS_BURNDOWN_HANDOFF.md).**
>
> **Supersedes** [`CORPUS_STUDY_2026_08.md`](CORPUS_STUDY_2026_08.md) (356 rows, partial denominator)
> and, as a cause taxonomy, [`CORPUS_TAXONOMY.md`](CORPUS_TAXONOMY.md).
>
> Baseline: `RELATIONAL_CORPUS_ALL.md` — **2793 tests, 2309 pass, 484 non-passing.**
> Every row below was studied individually. No sampling.

---

## §0 — Method, and how to reproduce anything here

23 parallel agents, one per family group, each instructed to study **every** row in its
batch, anchor every claim to `file:line`, prefer running the code over reading docs, and
report explicitly where it could not determine a cause.

**Reproduction.** Most batches are **not** reproducible with the obvious command:

```
mvn -o -q test -pl engine -am -Dtest=RelationalCorpusRunner \
    -Drcorpus.only=<family> -Drcorpus.includeExcluded \
    -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

`-Drcorpus.includeExcluded` is **required** — the default sweep skips
`<<test.ToFix>>` / `<<test.ExcludeAlloy>>` / `<<test.AlloyOnly>>`, and 130+ of the 484
rows carry one of those stereotypes. Several agents initially failed to reproduce their
own batch for this reason.

Useful environment variables (all read-only):

| var | what it reveals |
|---|---|
| `LEGEND_LITE_STACKS=1` | full stack at each wall |
| `LEGEND_LITE_DUMP_SQL=1` | emitted SQL per statement |
| `LL_TMP_DEBUG=1` | `[run] <fqn>` markers — needed to attribute SQL dumps to tests |
| `LL_SQLTEXT_DEBUG=1` | **the swallowed golden-re-render exceptions** (see §7) |
| `LEGEND_LITE_CMP_DEBUG=1` | expected/actual value *kinds*, not just renderings |
| `-Drcorpus.backend=h2` | separates DuckDB artifacts from real defects |
| `-Drcorpus.perTestSessions` | separates cross-test seed pollution from real defects |

---

## §1 — The denominator: 484 is not 484 units of work

| category | rows | evidence |
|---|---:|---|
| **Goldens disproven on evidence** | **31** | §4.1 |
| Goldens upstream-admitted aspirational | ~12 | ToFix comments naming the engine's own failure |
| Harness defects (ours) | ~35 | §5 |
| Backend artifacts (DuckDB vs H2) | ~12 | §4.3 — proven by re-run |
| By design (Pure-level metamodel, router, plan-shape emulation) | ~25 | §4.4 |
| **Genuine legend-lite defects** | **remainder** | §2, §3 |

Roughly **a quarter of the 484 is not engine work.** Any plan that treats the number as
a backlog will mis-size by that much before it starts.

**Two hard cautions on counting:**

1. **Message templates are not cause groups.** Rows sharing `no scalar lowering
   registered for …` or `TDS = one carrier` were checked and found to have *unrelated*
   causes. Grouping by error string will merge independent work and produce fix
   estimates that do not hold.
2. **Architectural severity and corpus yield are different axes.** Three separate
   batches found that the architecture audits' headline findings account for far fewer
   rows than expected — in `union`, the two flagged threads explained **exactly one row
   each out of 25**. Rank by tests-unblocked (§2), not by architectural weight.

---

## §2 — Ranked fix list (tests unblocked per fix)

Ordered by leverage. Every entry has a `file:line` and a confidence.

### Tier 1 — one-line or near-one-line, verified

| # | fix | rows | confidence |
|---|---|---:|---|
| 1 | `builtin/Pure.java:653-658` — add `LEGACY_SQL_NULL_UNSAFE_EQUALS` to the `Feature` enum. **Verified**: legend-lite already emits both golden strings verbatim. | 2 | certain |
| 2 | `builtin/Pure.java:1474-1475` — add `EXECUTION_PLAN__2_P2` (2-arg form, 2-param lambda). Five passing siblings prove the surrounding logic is correct. | 1 | certain |
| 3 | `harness/ExecCallFinder.java:124-138` — truncate the rebuilt `toSQLString` to 4 args instead of copying args 3..n. A 5-arg `execute(…, noDebug())` currently produces an unresolvable 5-arg `toSQLString`, swallowed to advisory. | 2+ | certain |
| 4 | `harness/TestBody.java:1586` — `return unsupported(e.getMessage())`. Its **three siblings** at `:1388`, `:1398`, `:1408` already do. Pure signal recovery, zero verdict change. | 0 (diagnostics) | certain |
| 5 | `harness/TestBody.java:1944` — give `assertSameSQL` the `PlanAsserts.containsPlanToString` pre-check that `assertEquals` has at `:1808-1812`. | 2 | certain (routing); outcome unknown |
| 6 | `builtin/Pure.java:1751-1752` — add `assert(Boolean[1], Function<{->String[1]}>[1])`. **135 call sites** across the corpus. | 5+ | certain |
| 7 | `lowering/SqlPostProcessors.java:51-59` — also read `sqlQueryPostProcessors`, not only `…ConnectionAware`. | 1 + latent | certain |
| 8 | `compiler/element/StoreCompiler.java:34-70` — `findTableDef` never scans `db.views()`. Views are modelled and are expanded on the mapping side; only `#>{db.View}#` is missing. | 1 | certain |

### Tier 2 — localized, high yield

| # | fix | rows | confidence |
|---|---|---:|---|
| 9 | **`toSQLString/5` overload** — a masked-wall census (§7) found this blocks **6 tests** in `milestoning` alone. | 6+ | certain |
| 10 | **`LATEST_DATE` fold on the `toSQLString` path** — `lowering/PureSql.java:65-66` is where it *surfaces*; the execute path folds it correctly (renders `TIMESTAMP '9999-12-31…'`), the golden-re-render path does not. 6 tests in `milestoning`. | 6 | certain (cause); fix site not pinned |
| 11 | **Null-safe equality → MIR node, dialect-spelled.** `SqlFn.IS_DISTINCT` already exists and renders (`AnsiSqlRenderer.java:456`); it is wired only to `isDistinct` and unreachable from `==`/`!=`. Corpus census: **113 goldens** expect `is not distinct from`, **30** expect `is distinct from`. Delete the hardcoded expansion in `lowering/NullSemantics.java:66-142` and the `FILTER_POS` ThreadLocal with it. | 30+ | certain |
| 12 | `resolver/CorrelatedSubselects.java:2078-2107` — the agg-demand handler **and its own loud guard** both test `path.get(0)`. A to-many hop at index > 0 escapes both. Causes a **silently eaten `sum()`** (§3.1). | 2+ | certain |
| 13 | `resolver/CorrelatedSubselects.java:1976-2001` — the computed-mapper arm must recurse into the mapper body with `userVar` rebound. | 1+ | certain |
| 14 | `compiler/spec/Typer.java:1440` + `:1451-1509` — **retry the next-best overload when `typeLambda` raises.** There is no such catch today. Selection commits on non-lambda args only. | 5+ | certain |
| 15 | `resolver/Substitution.java:1871-1878` — add a `TypedNewInstance` constant-fold arm. A host-side `^Firm(legalName='Firm A').legalName` cannot fold to a literal. | 6+ | certain |
| 16 | `lowering/Lowerer.java:2211` — emit `TIMESTAMP_NS` when subsecond length > 6. DuckDB's bare `TIMESTAMP` is microsecond; any ns literal silently truncates. **Proven with a standalone DuckDB repro.** | 5 | certain |
| 17 | `normalizer/RelationReads.java:90-116` — accept `c.expr() != null` (the machinery, `Col.bindSrc`, already exists). | 10 | certain |
| 18 | `StatementExecutor.java:3133-3145` — re-point `setUpDataSQLs` from `CsvSeed` to a `Ddl.*StatementText` composition. **Those generators already emit the golden text character for character.** | 3 | certain |

### Tier 3 — structural, sequenced

| # | fix | rows | note |
|---|---|---:|---|
| 19 | Give a join a **kind**. `TypedJoinSlot`/`TypedNavigate` carry no `joinType`; every mapping-implied join is a literal `"LEFT"`. Deletes the demand-withholding pin at `NavMaterializer.java:181-188`. | ~6 | land after #20 |
| 20 | Replace construct-whitelist join elision with a **cardinality rule** (`Pipelines.java:404-408`). Today an undemanded to-many join is cancelled unconditionally, dropping rows. | ~4 | changes row counts corpus-wide |
| 21 | `MappingNormalizer.java:2766-2776` — consume `oe.fallbackSetId()`. It is parsed, copied through four files, and has **zero consumers repo-wide**. Its neighbour `materializeInlineEmbedded` honours `ie.setId()`. | 1+ | certain |
| 22 | `GraphEmission.java:2107` — recurse with `childClass` (computed at `:2008` and discarded), not `cs`. | 2 | certain |
| 23 | `GraphEmission.java:450` — skip `pkOrderKeys` when the pipeline already carries an explicit sort. Emitted SQL contains **both** order specs; the PK one wins. | 1+ | certain |
| 24 | Views as relations — `MappingNormalizer.java:3245-3253`, `:3273-3276`. | 3 | certain |
| 25 | Test-data-gen: keep the **view as a node**; retire `perWebChildren` in the same commit (§8). | 3 | certain |

---

## §3 — Silent wrong answers (highest severity)

Eight confirmed. These return plausible numbers with no error, and most were caught only
because an advisory oracle happened to replay a golden.

1. **A `sum()` silently eaten.** `$p.age * 2 * $p.firm->toOne().sumEmployeesAge()` emits
   no `sum()` and no `GROUP BY`; the join explodes to 46 rows against 12, with
   per-employee ages instead of the firm aggregate. Both the handler and the guard that
   exists to catch exactly this test `path.get(0)`; the to-many hop is at index 1.
   The guard's own comment says it must "never be silent."
   → `resolver/CorrelatedSubselects.java:2078-2107`

2. **Mapping `~filter` with `!=` on a nullable column** emits `<> 'x' OR col IS NULL`,
   admitting rows the engine drops. Run-verified; both SQL texts captured.
   → `lowering/NullSemantics.java:66-96` reached via `normalizer/RelOpTranslator.java:485-487`

3. **Aggregate rooted at an inner lambda parameter** — `exists(e|$e.kids.kage->sum() > 2)`
   loses the `sum()` entirely. Returns 2 firms where 3 are correct.

4. **`average()` over a to-one receiver** elides to identity, losing the mandated
   `Float[1]` promotion. `Pure.java:984` declares `average(Number[*]):Float[1]`; the
   engine enforces it in SQL as `avg(1.0 * %s)`.
   → `lowering/Scalars.java:1176-1181`

5. **Nanosecond timestamp truncation.** Proven outside the pipeline:
   `TIMESTAMP '…123456789'` binds as microsecond `TIMESTAMP` → `.123456`. Comparisons
   against a `TIMESTAMP_NS` column silently invert.
   → `lowering/Lowerer.java:2211`

6. **An undemanded to-many join is cancelled**, dropping 6 rows to 3. Safe only for
   at-most-one joins; there is no cardinality guard.
   → `resolver/Pipelines.java:404-408`

7. **A 2-hop `> (INNER)` chain prefix inside an embedded expression PM is dropped**
   and collapses to a root column read. The 1-hop prefix in the same expression survives.
   → `normalizer/JoinChainEmission.java:70-140`

8. **`at(0)` folded as `LIMIT 1` *after* a to-many join**, truncating joined rows rather
   than selecting one parent. The renderer is innocent — `Lowerer.isBareSelect` correctly
   refuses to flatten, proving the `LIMIT` sat above the join in the typed tree.
   → `resolver/StoreResolver.java:2919-2922`

**Near-miss worth naming:** `graphFetch` appends PK order keys unconditionally, so a user
`sortBy` is silently discarded. The emitted SQL contains both order specs.

---

## §4 — Not our work

### §4.1 Goldens disproven on evidence (31)

The technique that found nearly all of them: **compare against a passing sibling that
differs in exactly one dimension.** Adopt it as standard practice before touching any
ToFix row.

Representative specimens:

- `testFilterAfterGroupByWithSameColForGroupByAggAndFilterOnRootClass` — expects
  `7,Anthony,Fabrice,Oliver`. **Oliver has six letters** and cannot be in a `length == 7`
  group. Corroborating tell: the golden's own SQL literal is syntactically mangled
  (`group_concat(… separator ',')` terminates the Pure string early), making it an
  accidental `assertEquals/3`. That test was never runnable upstream.
- `testToManyJoinTreeNodesForInvalidUsageOfFilterAndProjectDoMergeGivingWrongResults` —
  the name says it; the source comment says the intended fix is to **reject** the
  expression, so a 4-row assert cannot be engine output; and its golden uses a different
  alias scheme from its own passing sibling.
- `testConcatenateClassJoinMerge` — expects 7 rows, we return 21. The passing sibling is
  the *same* query differing only in the first projected column and asserts **21**.
  Changing a projected column cannot turn 21 rows into 7.
- `testSimpleMappingQueryWithFilterInProject` (+1 sibling) — the golden is reproduced
  exactly by applying the filter to the **outer** person: the classic self-association
  alias collision. **Carries no ToFix marker** — needs a scoreboard exception, not a
  classification.
- `testUnionWithPartialForeignKeyUsage1/2` — `assertSameSQL('')` and three
  `assertEquals([])`. Literal stubs; no engine could satisfy them.
- `testConstraintUsageOfVarReferenceWithThisMilestoningContext` — hand-derived from seed
  data: exactly one order violates. We return one. The golden's SQL joins with **no**
  milestoning predicate and contains a `not not not exists` triple negation.
- `testAllVersionsQueryWithMilestonedProperty` — the corpus comment states the engine
  emits predicates it calls **"incorrect as we have allVersions in query."** We emit the
  corrected form and fail only on alias text. *We are ahead of the engine here.*
- `testFilterInQualifierAndMapping` — our pipeline returns 0 rows, which satisfies the
  test's **own** `assertSize(…, 0)`. The FAIL comes from replaying the *golden* text,
  which omits both filters.

### §4.2 Upstream-admitted aspirational (~12)

Ten `union/relation` rows carry ToFix comments naming the reference engine's own failure
(`RelationFunction cannot be cast to NamedRelation`, `Cannot cast a collection of size 0
to multiplicity [1]`). Two more end in a literal `assert(false)` or `false`.

### §4.3 Backend artifacts — **family-specific, not a blanket bucket**

Proven by re-run, not argued:

| family | evidence |
|---|---|
| `mapping/filter` | `-Drcorpus.backend=h2` → **9/9 pass** |
| `mapping/selfJoin` | → 2/3 (the non-pass is an unrelated struct-literal wall) |
| `mapping/enumeration` | 18/30 → **22/30**, four rows vanish |
| `advanced` (2 rows) | the **engine's own golden SQL**, run byte-for-byte on DuckDB, produces the identical scramble |

**Counter-evidence that matters:** in `functions/tests/projection`, **30 of 32 rows
reproduce byte-identically on H2**. Row order is a real but narrow explanation. Do not
generalize it.

All affected rows share one shape: **positional `rows->at(N)` asserts on `ORDER BY`-less
queries.** The harness's multiset relaxation cannot help — the row was already selected
in SQL.

### §4.4 By design (~25)

Proven from our **own** code comments, not assumed:

- `PlatformTypes.java:151-155` — *"the corpus's own `toSQLString` body is engine
  plan-generation internals, suppressed like `toDDL`"*
- `RelationalDataType.java:5-10` — *"mirroring engine's
  `meta::relational::metamodel::datatype::CoreDataType` hierarchy … The Java-side name
  drops engine's `Core` prefix"*

Closing these means porting legend-pure's relational metamodel and M3 valuespecification
as Pure classes — rewriting the design. Named missing types: `SQLQuery`,
`ValueSpecification`, `CoreDataType`, `metamodel::join::Join`.

The **Pure router** is proven absent four ways: `grep routeFunction` → zero;
`grep StoreMappingRouted|ClusteredValueSpecification` → zero; the only `*Rout*.java` is
an M2M guard; and `core/pure/router/**` is outside both registered corpus roots.

Also here: **plan-shape emulation.** `testMilestonedProperty`'s *data* assert passes; only
its `planToString` golden — encoding the engine's multi-node
`StoreMappingGlobalGraphFetch` with temp-table chaining — cannot match a design that
emits one JSON-assembling SQL.

---

## §5 — Harness defects (ours, ~35 rows)

Six distinct, two of which manufacture failures **across files**:

1. **Test ordering.** We discover tests in *source declaration order*; legend-pure sorts
   **alphabetically** (`PureTestBuilder.java:65`). A test doing a raw un-rolled-back
   `INSERT` is declared first in its file and pollutes 12 downstream tests. Proven three
   ways: exact arithmetic (every delta is one extra qty-45 row), `-Drcorpus.perTestSessions`
   (240→252, exactly the 12), and a name-filter excluding the polluter (18/19, zero inserts).
   → `RelationalCorpusRunner.java:502` / `Runner.java:508-534`

2. **One DuckDB connection for all `Database` elements.** Two corpus files both create
   `FirmSet1`, on different databases, with different shapes. In the engine these are
   separate physical H2 databases. Second creation clobbers the first; the resulting
   binder error was reproduced in a standalone program that flips solely on that shape.
   → `Runner.openSession()` / `Runner.ddlConflictsWithSession:1727-1737`

3. **An effectful-let guard kills a whole `BeforePackage` seed.**
   `let x = <effectful fn>()` followed by a read of `$x` throws before any statement runs;
   `Runner.callSetup` swallows it into a `failed seeds: 1` counter. 4 rows.
   Behind it, latent: `Runner.moduleDdl` dedups tables by bare lowercased name across all
   databases, so the shared corpus's `productTable` shadows milestoning's `ProductTable`.
   → `StatementExecutor.java:134-149`

4. **Model assembly, three gaps.** (a) only two corpus roots are registered
   (`Corpus.java:46-60`); (b) the `extends` closure pulls only *qualified* tokens
   (`RelationalCorpusRunner.java:653-665`), so a supertype resolved via `import …::*` is
   never pulled; (c) platform functions outside those roots have no native. ~12 rows.

5. **Verdict-determining assert shapes** — §6.

6. **`assertSameSQL` / `assertRoundTrip` / `assertTdsEquivalent` / `assertIs` /
   `fail()` / `assertInstanceOf`-inside-a-lambda** are not harness vocabulary. Several of
   these rows **execute correctly** and are one harness addition from verifying.

---

## §6 — Five ways the verdict column misleads

Recorded because every one of them cost an agent time.

1. **SHAPE hides a real wall.** Endemic. Best summary, from the sqlQuery batch:
   *"SHAPE is a bucket name, not the diagnosis."* Nine of eleven SHAPE rows there were
   really "ERROR: a platform type we don't model."
2. **The verdict letter is an exception-hierarchy artifact.** `DialectCapability extends
   IllegalStateException`, which is outside `PlanAsserts.java:177-179`'s catch set — so it
   scores ERROR where an identical wall scores SHAPE.
3. **A missing class silently becomes a *different* class.** `NameResolver.java:505`'s
   prelude fallback is keyed on **simple name** across packages, so an absent
   `meta::relational::metamodel::join::Join` bound to the Postgres SQL metamodel's `Join`
   and reported `has no property 'name'` on a class the test never mentions. This
   *manufactures* misinformation rather than losing it.
4. **The poison channel misattributes blame.** `NotImplementedException`s downgraded at
   `MappingNormalizer.java:325-339` / `:419-435` re-emit as **`class 'X' is not mapped`** —
   an assertion about the user's mapping that is false.
5. **Visibility depends on assert shape, not severity.** The same divergence is fatal or
   invisible depending on whether the test carries a row assert:
   - `execute(…)->sqlRemoveFormatting()` → advisory → counted as `sqldiff-pass`
   - `executionPlan(…).sqlQuery` → plain `assertEquals` → hard FAIL

   One renderer divergence (`!=` spelling) is **silent in ~111 places and fatal in 2**.
   Sorting by FAIL count sizes it at 2 and is wrong by two orders of magnitude.

---

## §7 — The masked-wall census (do this first)

The single highest-value diagnostic technique found. One env var, one grep:

```
LL_SQLTEXT_DEBUG=1 mvn … -Drcorpus.only=<family> -Drcorpus.includeExcluded 2>&1 \
  | grep 'side unverifiable' | sort | uniq -c | sort -rn
```

`harness/ExecCallFinder.java:142-147` swallows every golden-re-render exception unless that
variable is set. In `milestoning` alone it hid **17 walls**:

```
6x  no SQL type for Pure primitive LATEST_DATE at the lowering boundary
6x  no overload of 'toSQLString' matches 5 argument(s) — candidates: [toSQLString/4]
4x  toSQLString whose query argument is not a lambda literal
1x  no overload of 'toSQL' matches the argument types
```

That converts directly into a fix ranking. **Recommendation:** make the decline *counted*
the way `H2Verify.decline` already is, so the blind spot closes permanently.

---

## §8 — Sequencing constraints

Violating these makes the scoreboard move the wrong way or manufactures false greens.

1. **Removing a wall converts ERROR/SHAPE → FAIL before anything turns green.** Four
   independent agents flagged this. Budget for FAIL to rise first.
2. **Test-data-gen: the view fix and the `perWebChildren` retirement are one commit.**
   `expandView(perWebChildren=true)` forks a child chain per join web; the fork's `+1`
   exactly cancels the missing view SQL's `−1` in three tests. Doing either alone breaks
   them. Ledger:

   | test | fetches | view deficit | fork surplus | net | golden |
   |---|---:|---:|---:|---:|---:|
   | testSimpleViewRoot | 5 | −1 | +1 | **5** ✓ | 5 |
   | testViewEmbeddedInChainedJoin | 4 | −1 | **+0** | **4** ✗ | 5 |
   | testUnionViewOnView (per arm) | 6 | **−2** | +1 | **6** ✗ | 7 |

   Even where counts match, the SQL text is wrong — three duplicate table fetches where
   the engine issues one merged fetch plus a view fetch.
3. **Lineage: delete the advisory arms *first*.** `scanRelations` reports 40/49; ~19 are
   verified. Building the plan-derived tree before deleting
   `LineageRelationsForm.java:109-119` and `:131-140` manufactures the same false green.
4. **The include-walk fix for inheritance is necessary but not sufficient** — it yields
   `stc_Car___person` and still not `stc_Bicycle___person`. The structural fix is one
   layer up in `UnionSynthesis`.
5. **Fix the resolver, not just the harness, for the multi-mapping ambiguity.**
   `StoreResolver.collectOpChain` keeps a stale `chainContext`; the harness's
   all-mappings-in-one-runtime overlay is what makes it visible. Fix only the harness and
   the resolver still picks a mapping by luck.

---

## §9 — Corrections to existing docs and code comments

**Wrong diagnoses already committed:**

- `LEG1_INNERJOIN_FAMILY.md:448-467` records the join-order divergence as
  `StoreResolver.java:2808-2809`. **Wrong** — those paths drive *association* joins; the
  witness's five joins are all class-mapping steps. Real cause: join emission follows the
  mapping's **property-mapping declaration order**, while the engine's is phase-based
  (projection block, then filter block).

**Load-bearing comments that are factually false:**

- `sql/dialect/EngineStyleH2.java:1277-1283` — asserts uppercase `dateadd` spellings "are
  firstDayOf\* FORMAT literals, never adjust units." The engine spells `adjust` units
  through `mapToDBUnitType`, which maps `DAYS → 'DAY'` (uppercase).
- `lowering/Scalars.java:79-81` — *"IS NOT DISTINCT FROM appears in no golden."* It
  appears **113 times**, including in a test file named for it.
- `normalizer/MappingNormalizer.java:2358-2362` — *"the corpus only ever feeds
  'true'/'false'"*. Falsified by the very test that fails on it, which feeds `'something'`.

**Comments that are exemplary** (keep this standard):

- `compiler/spec/Typer.java:2349-2352` names the exact test that caused a fix to be reverted.
- `normalizer/JoinChainEmission.java:801-842` — a documented approximation with a
  *structural* soundness precondition and a loud wall. This is what debt should look like.

---

## §10 — Architecture audits (12 feature areas)

Full detail in [`ARCHITECTURE_AUDIT_2026_08.md`](ARCHITECTURE_AUDIT_2026_08.md).

**11 of 12: `sound core, accreted edges`. 1 (`lineage` + test-data-gen):
`point-solutions accreted`.**

The unifying diagnosis, converged on independently by six agents:

> **Where legend-lite lacks a first-class concept, it encodes the concept into a NAME and
> re-parses that name at every consumer.**

| missing noun | encoded as | decoders |
|---|---|---:|
| subtype narrowing | `stc_<Fqn>___<prop>` | 4 |
| embedded set identity | `emb__path__sub` | 4 |
| join tree node | prefix concat | 5 key spaces |
| aggregate demand scope | head-name string | shared with `#fN`/`#cN`/`#dN` |

A second form: **one engine concept split into two implementations behind boolean flags** —
one `RelationTree` became two trees behind `tdgMode`, `perWebChildren`, and a
`tdsRoots`/`buildRoots` split, each flag calibrated against a different golden.

**The most reliable predictor of a cheap fix, found ~16 times:** the correct mechanism
already exists elsewhere in the tree. `SqlFn.IS_DISTINCT` renders correctly but is
unreachable from `!=`. `Aggregates.java:65-70` arity-scopes correctly *with a comment
explaining why*, fourteen lines below a registration that doesn't. `Ddl.createTableStatementText`
already emits the golden text character for character. `Lowerer.stripQuotes` is declared
and never called.

**Evidence *against* corpus-pinning, worth stating:** 348/348 on legend-pure's externally
authored PCT relation suite; **zero** control-flow branches keyed on test names across
four independently swept areas; `CalendarAgg`'s 92/92 is earned through structural
detection. legend-lite does not pin on test *identity*. It pins on **shape enumeration** —
subtler, harder to grep, and exactly what a corpus-driven build produces.

---

## §11 — Where the per-test detail lives

Each of the 23 batch reports contains, for every row: the Pure source `file:line`, the
full test body, the mapping/store/seed context, the emitted SQL, the real wall with stack,
the passing sibling that isolates the variable, and a classification.

Those reports are the per-test index. This document is the synthesis over them; §2 is the
actionable projection.

---

## §12 — Honest gaps

- Whether the engine's `_d#N_d#N_mN` alias grammar is **reproducible** from our IR at all.
  Two agents independently flagged this and both declined to guess. If it is not, the
  remedy is alias-insensitive comparison on the `toSQLString` surface, which is very
  different work.
- The engine's discriminating predicate for **when to keep the enum-decode `CASE`** — it
  keeps it on the routed `->from()` path and strips it on the 4-arg path. Both behaviours
  are pinned by tests. The fix site is certain; the guard condition is not.
- Whether **`getAllForEachDate`** (11 tests, one name) is a catalog entry or a feature. The
  golden shows a cross-join of the class extent against a date subselect with the
  milestoning window correlated to the date column — a new temporal query form.
- **`aggregationAware` 13/13 is hollow.** The parser silently skips the `Views:` block, so
  no rewrite can occur; `AggAwareActivities.rewrittenQuery` fabricates the routed print
  host-side; and a divergent SQL text that row-replays equal records as fully verified
  with zero divergence signal. Not counted in §1 because the rows currently *pass*.
- The `alloy::` test-data-gen family reports PASS while exercising a walled feature. If
  those are neutralized rather than running, §3's break list is understated.
