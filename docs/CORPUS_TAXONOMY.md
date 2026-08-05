# Corpus burndown — root-cause taxonomy

> # ⚠ SUPERSEDED (2026-08-05)
>
> **As a cause taxonomy this document is superseded by
> [`CORPUS_STUDY_2026_08.md`](CORPUS_STUDY_2026_08.md).**
>
> That study examined **every one of the 356 non-passing rows individually** — source located, body
> read in full, mapping/database/model dependencies followed, assert quoted, root cause traced to a
> legend-lite `file:line`, and classified. No sampling. This document's causes were derived from six
> slices over a dataset that is now three sweeps old (it was written at 2132 pass; the sweep it was
> re-stamped against read 2182; `main` now reads **2253 pass / 314 non-passing**).
>
> **Do not take unlock counts from this document.** They are stale in both directions, and the newer
> study shows why the arithmetic is unreliable in principle: fixing a shallow cause frequently moves
> a test to a *different* failure rather than to PASS (masking chains — `CORPUS_STUDY_2026_08.md`
> § 8.1). The 08-05 re-sweep confirmed this at corpus scale: ERROR and SHAPE fell by 70 while FAIL
> **rose** by 28.
>
> **What is still worth reading here:**
> - **§ 4 (diagnostic defects X2–X5)** — the newer study confirms these are still live and widens
>   them; see its § 1 on verdict-label integrity.
> - **§ 7 "Do NOT do these"** — each entry was specifically considered and rejected with reasons.
>   The 08-05 study did **not** re-litigate this list; it remains the authority on those decisions.
> - **§ 2 and § 9** — the method (cause taxonomy over symptom census; normalising data out of error
>   messages; `WALL_DEPTH.txt` as an unlock-estimation instrument) still holds.
>
> Everything else — the ranked causes in § 5, the counts in § 1 and § 3, the sequencing in § 8 —
> should be read as historical.

> **Staleness note (2026-07-31, main @ 2177 pass).** Imported from docs/audits; the dataset is
> a2ee66f0 (2132 pass). Already burned since: **R1** (c28-29, validation 12->19), the
> **k_businessDate carrier** tail item (c33), **enum decode in filters** (c31, + trio documented
> scan-order), plus the temporal WHERE-zone ordering (c34). Re-verify every unlock count against
> the current docs/RELATIONAL_CORPUS.md before spending.

> **What this replaces.** `docs/ENGINEERING_LOG.md`'s "Active queue (weight = current error census)"
> buckets failures by **error message**. That is a *symptom* taxonomy: it cannot tell you whether ten
> rows are one fix or ten, and its numbers are now stale by roughly 5×. This document is a **cause**
> taxonomy — for each root cause: the one thing in our code, and how many tests fixing it unlocks.
>
> **Companions:** `AUDIT_PROGRAM.md` (method), `CORRECTNESS_REMEDIATION.md` (the scoreboard's own
> honesty), `TENET_REMEDIATION.md`, `H2_BACKEND.md`, `NULL_GATE_VERIFICATION.md`.

**Dataset:** `docs/RELATIONAL_CORPUS.md` at `a2ee66f0` — **2538 tests, 2132 pass, 86 FAIL, 168 ERROR,
152 SHAPE**; 406 non-passing rows carrying **208 distinct symptom signatures**.

**Method.** Six independent slices. Every claim is grounded in `file:line` or a computed count.
Several slices ran the corpus per-family (`-Drcorpus.only=<family>` reproduces the committed
per-family counts exactly), dumped emitted SQL, and executed the engine's golden SQL side-by-side in
DuckDB. Where two slices overlapped they agreed; small count differences are noted inline rather than
averaged away.

---

## 1. Headline

**The denominator is honest. The queue is not. About a quarter of the failures are not defects.**

| | |
|---|---|
| Non-passing rows | **406** |
| …that are **not product defects** (§3) | **~95** |
| …real remaining work | **~310** |
| Distinct causes behind the 86 FAILs | **~35**, top two account for 16 |
| Long-tail causes (signatures ≤2 occurrences) | **22 causes cover 99 rows**; 47 irreducible one-offs |
| Catalog-miss bucket (typing) | **31 tests / 12 names**; 7 ports cover the 16 ERROR rows |

**208 signatures is an artifact of message formatting, not cause diversity.** `Runner.java:1039`
interpolates `dominantNamespace(body)` into its text and produces **17 distinct signatures from one
code site** — a site that accounts for **81 of all 406 failures**. `Substitution.java:1184`
interpolates the navigation path: 8 signatures from one `throw`. Normalise the data out of the
messages and 180 tail signatures collapse to ~69 before anyone writes a line of code.

---

## 2. The denominator is honest — and the queue is stale by 5×

### 2.1 Nothing is hiding

Re-derived from the corpus itself: **540 `.pure` files, 2538 runnable `<<test.Test>>` functions,
per-family delta of zero across all 66 families.**

- The 8,007-line **"mapping walls (dropped at assembly)" section loses zero tests.** Discovery never
  goes through the module — `RelationalCorpusRunner.java:418` calls `Runner.discoverTests` on the
  *file text*, and `Runner.java:1024` executes the body from that same parse.
- **Parse walls darken zero tests.** Zero `=> <parse error>` lines; zero of the 406 rows carry a
  parser-shaped message.

*One caveat:* the sweep's reference checkout is pinned at 2026-07-11 content. A newer checkout has
541 files / **2543** tests. Refreshing **widens** the denominator by ~7; it is not an unlock.

### 2.2 The active queue is a snapshot of a world with 830 ERRORs

Every one of the eight numbers reproduces **exactly** from the scoreboard at commit `fa61f0a8`
(2026-07-18), which read `2497 total | 1031 pass | 830 ERROR`. Nine sweeps and ~1,100 passes ago.

| Queue category | Claim | @`fa61f0a8` | **Now** | Where the difference went |
|---|---|---|---|---|
| `unknown function` | 56 | 56 ✓ | **19** | 42 now PASS |
| `property … is not mapped` | 50 | 50 ✓ | **5–6** | 44–46 now PASS |
| `TypedFilter not substitutable` — *"biggest single bucket"* | 33 | 33 ✓ | **3–4** | 27–33 now PASS |
| `no overload … matches` | 31 | 31 ✓ | **13** | 27 now PASS |
| bare-lambda typing | 26 | 26 ✓ | **0** | see the trap below |
| graph child walls (H4b/H5c) | 21 | 21 ✓ | **0–2** | 19–21 now PASS |
| `@TabularDataSet` casts | 19 | 19 ✓ | **0** | 17 PASS, 1 FAIL, 1 ERROR |
| union nav key demands | 15 | 15 ✓ | **0** | 14 PASS |
| **sum** | **251** | **251** | **≈40** | **209 of 251 now pass** |

**The trap: bare-lambda was fixed as a *message*, not as tests.** Zero rows carry it, but **14 of the
original 26 are still non-passing behind later walls.** Reading "26×" as 26 available wins is wrong
twice over. Chase those 14 by name.

**Its diagnosis is obsolete too.** The queue says *"SyntheticHeads handles only uncorrelated preds"* —
but `SyntheticHeads.parkFiltered:207-220` routes correlated predicates into a `corrPreds` pool today,
applied at the join condition by `AssociationJoins.andCorrelatedIntoCondition`. That is the audit-21b
F1/F2 work, already landed.

**Also dead:** *"task #50 parse walls (`<T|m>` — unlocks 37+)"*. `<T|m>` was already supported at
`ElementParser.java:999` before the entry was written; the 7 real parse walls at that commit were
different constructs and fell three hours later. Measured effect: **+41 tests into the denominator, of
which +2 passed** — the rest arrived as +5 FAIL, +20 ERROR, +14 SHAPE. Dark tests were hidden
*non-passing* tests, so un-darkening them made the honest ratio slightly worse, which is correct.
`docs/CONSTRUCT_COVERAGE.md:102` is a second stale doc pointing at the same dead task.

> **X0 — rewrite the active queue from §5 of this document, and stamp it with the sweep commit it was
> taken from.** The header says "current"; it has never been regenerated while
> `RELATIONAL_CORPUS.md` has been regenerated ~30 times.

---

## 3. What is NOT a defect — ~95 rows

Stop quoting one pass rate until these are separated out. `CORRECTNESS_REMEDIATION.md` §1 found every
scoreboard bucket mislabeled in the flattering direction; this is the same failure with the sign
reversed — counting non-defects as debt.

| Category | Rows | Why |
|---|---|---|
| **Tests of the engine's own Pure internals** | **~52** | `routeFunction`, router-metamodel walks, `mergeOldAliasToNewAlias`, `toSQLQuery($fe,…)` over a `deactivate()`d expression, `processIdentifierWithBackTicks`. legend-lite is a Java rewrite — **there is no Pure-level function to call.** Corroborated by the element drops: 748× `FunctionExpression`, 177× `ValueSpecification`, 90× `SQLQuery` |
| **Advisory `sql-only`** | **27** | Provably *not* in the 373 byte-matching set — reachable only via `ADVISORY_MARKER`, i.e. the comparison never ran. Composition: 9 lineage-tree asserts containing no SQL at all, 8 class-frame replay refusals (→ **F2**), 7 `toSQLString`-only tests with no rows in existence, 2 misrouted plan asserts, and 1 comparing **our own two SQL strings to each other** |
| **Ordering / oracle artifacts** | **8–9** | See **F1** — proven, not inferred |
| **Stale engine goldens** | **3 + a class** | See §6.2 |
| **The engine's golden is wrong** | **2** | See **F11** |
| **`routeFunction` asserts** | 2 | Pin the engine's router-printer text. Clean-room-unsatisfiable |

> **X1 — split the scoreboard into *wrong answers* / *unbuilt features* / *oracle mismatches* /
> *out-of-scope*.** Of 34 golden-SQL text disagreements, only **3 rows corpus-wide** carry any
> evidence our rows are actually wrong.

---

## 4. Diagnostic defects — the dataset lies about itself

All four are XS-to-1h and none unlocks a test. They are first because **every estimate below is
computed from data these defects distort.**

| # | Defect | Where | Effect |
|---|---|---|---|
| **X2** | **The runner computes the real wall for every no-execute body and discards it.** A SHAPE verdict from `tryRunNoExecute` is dropped; the exception is swallowed unless `LL_TMP_DEBUG`; a generic string is emitted instead | `Runner.java:929-935`, `:937-948`, `:1038-1040` | **81 rows — 20% of all failures — have no diagnosis attached.** ~1 h |
| **X3** | `String.valueOf(e.getMessage())` renders a message-less exception as the literal `"null"` | `Runner.java:1106` | The 4 `null`-reason rows are **not a bucket** — they are HotSpot's `OmitStackTraceInFastThrow` firing on the *same* `ArrayIndexOutOfBoundsException`. Re-run with `-XX:-OmitStackTraceInFastThrow` and all four print `Index 0 out of bounds for length 0`, revealing a **fifth** row |
| **X4** | `assertEqualsH2Compatible` failures render `args.get(0)` — the **legacy** golden — while comparing against the H2 arm | `PlanAsserts.java:166-173` | 6 of 14 PLANTEXT rows show an "expected" that is not the comparison target. **Two published hypotheses are refuted by this** — legend-lite already matches the real target |
| **X5** | `checkGeneric` and `checkWithDeferred` report the **identical** empty-candidate condition with two different messages | `Typer.java:1343-1351` vs `:1410-1417` | Splits one bucket in two. The only difference is whether the call carries a lambda/ColSpec arg |

**These four are why `unknown function` and `no candidates at all` looked like separate causes (X5),
why `null` looked like a bucket (X3), and why the largest bucket in the corpus is undiagnosed (X2).**

---

## 5. Ranked causes

Unlock counts are *rows that leave their current bucket*. Where a slice distinguished that from
*tests likely to turn green*, the smaller number is given.

### 5.1 Highest leverage

| # | Cause | Unlock | Where | Size |
|---|---|---|---|---|
| **F2** | **`H2Verify` declines every non-`Tabular` result frame**, so golden-SQL row replay never runs and the text silently becomes binding | **8** | `H2Verify.java:94-95` | M |
| **F1** | **DuckDB's hash join does not preserve driving-table order; the engine emits no `ORDER BY` and its goldens are pinned to H2's scan order** | **8–9** | decision site `Runner.java:1079` | L (a *decision*) |
| **T2** | **Five platform metamodel classes are entirely absent** from the 191 `nativeClass(…)` stubs — `EngineRuntime`, `ExecutionOptionContext`, `RelationalActivity`, `StoreMappingGlobalGraphFetchExecutionNode`, `TDS` — plus 3 declared with too few properties | **14** | `builtin/Pure.java:483` (block 168–480) | **S** — declarations only, 0 grep hits each |
| **N1** | **`meta::legend::executeLegendQuery` is absent from the native catalog** — and it is *not* new machinery: `execute` plus parameter binding plus the JSON envelope, all of which exist | **9** (Med that all 9 flip) | `Pure.java` (no entry; add beside `:1343`) | M |
| **T5** | **`isolate()` is not demand-aware** — it wraps a select as `SELECT * FROM (inner) tN` carrying only what the inner projected, so an outer read of a non-projected column walls or emits invalid SQL | **11** | `Lowerer.java:3464`, `:266`; walls `:1146`, `:1223` | M |
| **T4/E13** | **`EngineStyleH2` is constructed with DuckDB's function table** — `super(Lexicon.ENGINE_STYLE, TypeNames.ANSI, Spellings.DUCKDB)` | **10 (+3)** | `EngineStyleH2.java:49` | S–M |
| **R1** | **`collectOpDemand` routes `sortBy` keys through the aggregate-demand scan but routes `filter` predicates through `memberScan`**, which has no `aggOut` register and throws — so a to-many aggregate in a constraint body is banned outright | **6** | asymmetry at `StoreResolver.java:1300` vs `:1309`; throw `:3179-3188` | **S** — `AggDemand` + `CorrelatedSubselects.aggScan` already emit the right shape |
| **F3** | **`parseDecimal`/`parseInteger` render the ANSI default cast**; the engine spells casts per dyna-function (`decimal(5,2)`, which *rounds*; `integer` not `bigint`) | **5** | `Scalars.java:2029-2032`, `:1992-1996`; hook `EngineStyleH2.java:1052-1071` | M |

**T4 is triple-confirmed** — found independently by the H2 design work (probing a real H2: `[90022]
Function not found`), by long-tail clustering, and by the lowering slice. Same line.

### 5.2 Small, high-confidence

| # | Cause | Unlock | Where | Size |
|---|---|---|---|---|
| **N2** | **`ValidateDesugar.replaceVar` is not a total substitution** — it special-cases four node kinds and `return v`s everything else, with **no `AppliedProperty` arm and no default recursion**, so `$t` inside `$t.account.name` reaches the Typer genuinely free | **3** | `ValidateDesugar.java:328-368` | **XS** — delete it, call `SourceSubst.substitute` |
| **F4** | **`TIMESTAMP '…'` in DuckDB is microsecond-precision**; a >6-digit Pure subsecond literal is silently truncated | **4** | `Lowerer.java:2175` | S — emit `TIMESTAMP_NS` |
| **R2** | **`synthesizeAssociationMapping` refuses to emit a binding when either end class lacks a `~mainTable`** — always true for an `Operation{union()/inheritance()}` end | **4** | gate `AssociationSynthesis.java:396-398` | S–M |
| **H1** | `executeLegendQuery` splice accepts only a **zero-parameter** query lambda | **11** (2 here + 9 = N1) | guard `TestBody.java:762` | 0.5–1 d |
| **H2** | Two discovery lists disagree about what an execute-shape is — `planToString` is in one, not the other | **7** | `Runner.java:608-613` vs `:656-680` | ~2 h |
| **F10** | `__jk_` synthetic join-key rename leaks into emitted SQL; the engine's outer wrapper select is missing | **2** | `JoinChecker.java:247-288` | M |
| **F9** | Test-data generation **replaces** rather than **prepends** the VIEW node's own extraction SQL | **2** | `TestDataGenerator.java:574-602` | S |
| **H3** | An assert-free body that runs clean is credited only when `executed > 0`, which counts three statement kinds | **5** | `Runner.java:1129-1137` | 2–4 h |
| **R4** | Pipeline slots keyed by **bare property name** in a flat `Map`, so two same-named class-typed sub-PMs collide. **A naming bug, not a missing feature** | 2 (**bank on 1**) | `JoinChainEmission.java:126-142`; flat map `Pipeline.java:19` | S |
| **E7** | Target-side join-key widening has arms for `~distinct` and `union`, none for `~groupBy` | **2** | `Pipelines.java:447-457` | S |
| **E5** | `orderedDedup` inlines its list argument twice; when the list is a correlated subquery DuckDB rejects the copy | **2** | `Scalars.java:2401-2408` | M |
| **E8** | A to-one relation-typed value lowers to a correlated **scalar** subquery where the engine folds the qualifier into a LEFT JOIN `ON` clause | **2** + latent | `Lowerer.java:2762-2763` | M |

### 5.3 The long tail

**22 causes cover 99 of 218 tail rows. 47 are irreducible one-offs. 65 more are the big buckets
wearing different data.**

Beyond T2/T4/T5 above: `FreeMarkerConditionalExecutionNode` and `CreateAndPopulateTempTable` **do not
exist anywhere** (4 rows, 0 grep hits); milestoning context columns are aliased `businessDate` where
the engine names them `k_businessDate` (4 rows, XS — but **two sites cite it independently**,
`GraphEmission.java:152-158` and `RelationalRootForm.java:96-98`; reconcile before fixing); enum
decode baked into the property read so filters and GROUP BY see the decoded name (3 rows).

**Sequence by cause, not by family.** The top 5 families are 43% of the tail, but inside them causes
do not cluster — `functions/tests` has **18 tail rows and 18 distinct signatures**. Picking a family
buys 1–2 rows per fix; picking a cause buys 9–14 across 5–10 families.

---

## 6. The FAILs — wrong answers shipped

86 rows → **~35 distinct defects**. An ERROR is an honest refusal; a FAIL is a wrong answer, which
tenet #3 ranks worst.

### 6.1 F1 is proven, not inferred

Taking the engine's **verbatim golden SQL** and running it in DuckDB (`SET threads=1`) returns
`Federation, …, ROOT` — **character-for-character the reported `got`**. The SQL shape is irrelevant.
And legend-lite **already** injects mapping-PK order keys on the graph path (`… ORDER BY t0.ID ASC
NULLS LAST` in every `to_json(list(…))` envelope), so extending that to tabular is **existing policy,
not a new pin**. Alternatively, execute on H2 — `H2_BACKEND.md` scopes it.

### 6.2 Where the expectation itself is wrong

- **F11 (2 rows).** `filter(e|$e.age<35)` over `{Fabrice(45), Oliver(26)}` yields `{Oliver}`; the
  engine's 5 rows are reproducible only if `$e.age` binds to the **outer** variable. Confirmed by the
  *passing* sibling `testSimpleMappingQueryWithPreFilter`. **legend-lite is correct.** Same family as
  `H2_BACKEND.md` §6.5's precedent.
- **Three goldens are stale against the engine's own H2 renderer.** `testMostRecentDayOfWeek`'s
  `extract(dow from cast(now() as date))` appears in exactly one engine source — **`oracleExtension.pure:203`**;
  H2's is `DAY_OF_WEEK(%s)`/`current_date()`. `listagg(…)` is the DB2/Oracle spelling of `joinStrings`
  (H2: `group_concat`). `locate(',', x)` — H2 spells `position`.
  > **⚠ legend-lite has already calibrated to the first of these.** `EngineStyleH2.java:938`
  > (`case TODAY -> "cast(now() as date)"`) reproduces the Oracle spelling. **That line should be
  > reverted, not extended.** A committed corpus-pin is exactly what tenet #5 forbids.
- **The `toSQLString(f, mapping, DatabaseType, extensions)` goldens compare against a
  *pre-post-processor* surface** — that overload passes `[]` for post-processors while only the
  runtime-carrying overload adds `replaceAliasName`. The raw aliases (`personTable_d#4_d_m1`) encode
  the engine's join-tree path identity, which our IR does not carry. **Document, do not fix.**

### 6.3 The comparator defects cause zero FAILs — but one masks

Neither recorded defect (`H2Verify.norm`'s `MathContext(10)`, `csvRowEquals`' `Double.parseDouble`
fallback) causes any of the 86. The CSV fallback reaches exactly one family — `calendarAggregation`,
which is **92/92 green**.

**But `norm` does mask, with a named witness.** `testDivideFunctionPrecision` passes on values of 12
and 13 significant digits that `MathContext(10)` truncates on **both** sides; it survives only because
a sibling `assertEq` pins the value strictly through `wireEquals`. A golden-SQL assert without that
backstop has no such protection.

> **Correction to `TENET_REMEDIATION.md` §8:** in the CSV path the kind erasure is **upstream** of the
> numeric fallback — `csvCell` is `String.valueOf(v)` (`TestBody.java:3198-3207`), so `"13"` and `13L`
> are already equal before `Double.parseDouble` is reached. **Removing the fallback alone would not
> restore strictness.**

---

## 7. Do NOT do these

Each was specifically considered and specifically rejected. Several would make things worse.

- **Do not "fix" the `struct_extract` lowering typing.** Five ERRORs in
  `aggregationAware/test/rewrite/NOP` trace to one cause: `$r.activities` is hardcoded to `[]`
  (`StatementExecutor.java:2194-2209`), so an `at(0)` guard builds a `CASE` whose DuckDB type is
  VARCHAR and the field read becomes `struct_extract(VARCHAR, 'rewrittenQuery')`. The assert compares
  against a `rewrittenQuery` string we have no model for — **a typing fix converts 5 ERRORs into 5
  FAILs**, worse under tenet #3. Wall it loudly; model `AggregationAwareActivity` later.
- **Do not count `TypedFilter` as one cheap fix.** One gap *in kind* (`Substitution.java:1460` admits
  `TypedFilter` only over a `RelationType` source), but ≥3 shapes *in the fixing*. Retrofitting a
  fourth lift arm per consumer is the corpus-pin failure mode tenet #5 warns about; the right fix is a
  real object-space filter arm, which subsumes the lift.
- **Do not add four more `_P` arms** to make `FunctionDefinition<Any>[1]` type. XS and green, but real
  pure spells `FunctionDefinition<Any>[1]` in all seven places — model an any-arity function kind (S).
- **Do not re-attempt C1.1 inside `Fold.filterSlot`.** The `63a68804` revert was correct: `Fold.java:243-255`
  says the isolation is the resolver's job, and fixing it in `filterSlot` regresses PCT
  `testExtendFilterOutNull`. It belongs at `resolver/ClassSources.java` `anchoredInFlow`.
- **Do not chase the `joinStrings` router quirk.** Three of the four rows are sql-only and would become
  FAILs unless the concat spelling matches — and the goldens encode `joinStrings(coll,'|')` →
  `concat(f,' ',l,'|')`, separator *appended* not interleaved, which is arguably an engine bug.
- **Do not treat the `duplicate … kept sibling-N.pure` dedup as a burndown item.** It is our artifact —
  the same file text is registered twice (`RelationalCorpusRunner.java:316` + `Runner.java:1320-1324`)
  — and first-wins keeps a byte-identical element. **Unlock: 0 tests.** Verified arithmetically:
  `simpleTestModel.pure` 70 elements × 20 families = 1400; `relationalSetUp.pure` 57 × 5 = 285;
  `inheritanceTestModel.pure` 20 × 3 = 60; sum 1745, exact. Worth doing as **hygiene** — it is 5,561 of
  8,007 wall lines (69% noise; the generated doc drops from 8,530 to ~2,970) and redundant parse work
  in every module build.
- **Do not add a third string label to the SQL walk** for `lineage/scanColumns::testView`. The context
  must come from the mapping metamodel walk — the engine reads the class name one level above the
  `TableAliasColumn`. Adding it to the lowered plan is audit-20a overfit by construction.
- **`asin`/`acos`: the guard is right for PCT and wrong for the relational push-down.** The observed
  `Invalid Input Error: Unable to compute asin of 1.1` is **legend-lite's own `error()`**, not
  DuckDB's; the engine emits a bare `asin(%s)` and H2 returns NaN, which filters the row out.
  `Scalars.java:1550-1557`'s `sqrt` guard is the same shape, latent.

---

## 8. Sequencing

**First — make the data honest (X2–X5 + X0).** All are XS-to-1h, none unlocks a test, and every
estimate in §5 is computed from data they distort. X2 alone re-diagnoses 20% of the corpus.

**Then, by unlock-per-effort:**

1. **T2** (5 absent metamodel classes) — 14 rows, declarations only.
2. **F2** (`H2Verify` non-tabular decline) — 8 rows, one method, and it converts text divergences into
   honest row verification rather than into new pins.
3. **R1** (filter/sortBy demand asymmetry) — 6 rows, and the machinery already exists.
4. **N2** (`ValidateDesugar`) — 3 rows, XS.
5. **F4** (`TIMESTAMP_NS`), **F9**, **F10**, **E7**, **R2**, **R4** — 2–4 rows each, single sites,
   high confidence.
6. **N1 + H1** (`executeLegendQuery` port + the parameterised-lambda splice) — 11 rows together.
7. **T4** (author `Spellings.H2`) — 13 rows, and it is a prerequisite for `H2_BACKEND.md`'s Milestone 1.
8. **T5** (demand-aware `isolate()`), **F3** (cast origin tag) — 11 and 5 rows, real design work.
9. **F1** — decide the backend question explicitly rather than fixing code.

**Then it is a grind.** After the ~22 tail causes, 47 one-offs remain across 23 families with no
shared structure — plan roughly one fix per test, and say so rather than implying a rate.

---

## 9. Two standing recommendations

**Normalise the data out of error messages** (X2's site, `Substitution.java:1184`, and any other
`throw` that interpolates a path or namespace). 180 tail signatures collapse to ~69 for free, and every
future taxonomy gets cheaper.

**`docs/WALL_DEPTH.txt` is the right instrument for estimating unlocks** and is under-used. It records
how many *distinct* error messages each test has shown across sampled history; corpus median is 2. A
fresh wall at depth 1–2 is probably near-final; a test at depth 71 or 158 will not turn green from one
fix. Two of the estimates in §5 were reduced on that basis.
