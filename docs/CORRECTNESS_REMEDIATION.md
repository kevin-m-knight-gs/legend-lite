# Correctness Remediation Plan

> **Companion to `ARCHITECTURE_REMEDIATION.md`.** That doc fixes *shape*; this one fixes
> *answers*.
>
> **Sweep basis:** 6 agents, run at `89f3c6a7` (corpus 2005/2538). Ground-truthed against
> `/Users/neemsandv/legend/legend-pure` and `/Users/neemsandv/legend/legend-engine`.
> **Re-baselined at `16ee3358`** (corpus 2074/2538) after T2/T3 landed — findings the
> intervening 46 commits killed are listed in §5 so nobody re-works them.
>
> **Confidence column is load-bearing.** `VERIFIED@16ee3358` = I re-checked the code at the
> current head. `LIKELY` = found at `89f3c6a7` in a file the T2/T3 work did not restructure.
> Re-confirm a `LIKELY` before starting it.

---

## 1. The meta-finding: every bucket is mislabeled, always flatteringly

This is the most important output of the sweep, because it means **the scoreboard cannot be
read literally** — in either direction.

- **FAIL** contains ~22 tests that are not legend-lite defects. In several, *our answer is
  correct and the harness corrupted the expected value*.
- **ERROR** is ~66% honest walls. **30 messages are misattributed**: `unknown function 'X'`
  and `no overload of 'X' matches N arguments` fire from the *same* `candidates.isEmpty()`
  condition (`Typer.java:1261`, `:1322`). The function isn't loaded; the message blames the
  model.
- **11 errors say `property 'X' is not mapped in mapping 'Y'`** — verified false in ≥6.
  `Person.locations` *is* mapped (`relationalSetUp.pure:436`); we looked up `locations#f1`,
  **our own synthetic filtered-head name**. Two more name `stc_…`, our own union-dispatch
  column identifier, which `ClassMapping.java:52-58` documents as "never a real property
  name." **We are blaming users for our own internal identifiers.**
- **SHAPE** reported `assert form 'assertEquals/2' is not supported` for 44 tests —
  `assertEquals` *is* implemented (`TestBody.java:1829`); 43 were plan-text walls.
  23 tests labelled `sql-only: advisory golden-SQL` **contained no SQL at all**.
- **PASS** hides ~1,224 tests with unreported SQL divergences and an unknown number whose
  row order was never checked.

---

## 2. TIER C0 — Diagnostics (do first; these are measurement, not fixes)

Every one is small, and each unblocks work that is currently un-startable.

### C0.1 — Raise the 300-char detail truncation · **VERIFIED@16ee3358**
`engine/src/test/java/com/gs/legend/rcorpus/Runner.java:1843`
```java
.append(d, 0, Math.min(300, d.length()))
```
Destroys the `got` side of every long SQL/plan diff. **This alone blocked all 10 FAILs the
sweep could not root-cause.**
**Accept:** a full diff is recoverable from the ledger or a debug sink for any FAIL.

### C0.2 — Report `sqlDiffs` on PASS rows · **VERIFIED@16ee3358**
`Runner.java:1025` fails a golden-SQL divergence *only* when `r.verified() == 0`:
```java
if (r.verified() == 0 && !r.sqlDiffs().isEmpty()) { … Status.FAIL … }
```
Any test that verifies one row **swallows its SQL diff as advisory**, and `writeScoreboard`
emits only non-PASS rows. Two agents reached this independently. Consequence: **the ~7
`sql-text:` FAILs are the subset with nowhere to hide, not the SQL divergence rate**, and
the pass count is not evidence of SQL parity.
Witness: `testMilestoningFiltersPropogatedToDataTypePropertiesFromAllInProject` passes with
a near-identical golden to a failing sibling — because it also asserts rows.
**Accept:** PASS rows carry a `sqlDiffs` count; the scoreboard gains a column. Zero
semantic change.

### C0.3 — Make `scoreAssert` carry the inner failure reason · **LIKELY**
`TestBody.java:779-784` prints `assert form 'X' is not supported yet` for *any*
`UNSUPPORTED_MARKER`, including the plan-surface wall inside `planTextAssert`
(`TestBody.java:1500-1511`). That is why 43 plan-renderer walls were indistinguishable from
vocabulary gaps, and why the SHAPE bucket went unexamined for so long.
**Accept:** the reason string names the actual wall.

### C0.4 — Measure the order exposure · **LIKELY**
`endsInSort` (`TestBody.java:3446`) bails on **any** `AppliedProperty`, and the corpus's two
dominant result-read spellings (`$result.values.rows->…`, `$result.values.<prop>`) are
`AppliedProperty` nodes — so a **sorted** query read through a property is compared as a
multiset. Sharpest instance: **`functions/tests/testSort.pure`'s dedicated ORDER BY tests do
not verify order.**

Capped by the fact that `compare()` tries the exact positional path *first* and only falls
through on failure — so exposure is an upper bound, not a count.

Instrument rather than guess:
```java
private static boolean compare(Eval e, Eval a, boolean ordered) {
    boolean r = compare0(e, a, ordered);
    if (r && System.getenv("LL_ORD_COUNT") != null && !strictEquals(e, a)) {
        System.err.println("[ord] leniency-dependent pass");
    }
    return r;
}
```
Mirror inside `gridEquals` / `csvEquals` / `csvJoinedEquals` / `tdsStringEquals`, then
attribute each `[ord]` line to the preceding `[run] <fqn>`.
**Accept:** an exact count of order-unverified passes, in one sweep.

### C0.5 — Fix the two misattributions above · **LIKELY**
(a) Split the `candidates.isEmpty()` message from the arity-mismatch message, and file the
former as `NotImplementedException` per this project's own convention.
(b) Route every "not mapped" throw through `SyntheticHeads.realHead` before naming the
property. `AssociationJoins.java:711` already does; the sibling sites at
`Substitution.java:1512, :1609` do not.
**Accept:** no error message names a `#fN` / `stc_` internal identifier.

---

## 3. TIER C1 — Engine defects, ranked

### C1.1 — `Fold.filterSlot` has no window guard · **VERIFIED@16ee3358** · silent wrong value
`core/src/main/java/com/legend/lowering/Fold.java:236-247`

The guard is `referencesWindowColumn` — whether the *predicate* mentions a window column.
It is not "does this select already carry a `WindowCall`". A filter over a window-carrying
select folds to `WHERE`, and **SQL evaluates `WHERE` before window functions in the same
SELECT**, so `rank()` is computed over the filtered rows.

Witness: `tests/mapping/relation/testMappingWithWindowColumn` — expected `John, Group A, 2`,
got `John, Group A, 1`.

The correct guard is **one method away**: `Fold.java:301-305` (`groupByFolds`) already
excludes any select whose projections contain a `SqlExpr.WindowCall`. The doc comment at
`:317-321` states the mistaken premise verbatim — *"WHERE is fine (windows evaluate after
it)"* — true only when the window is added *after* the filter in the chain.

### C1.2 — Top-level sort emits no NULL ordering · **REVERTED 2026-08-02**

The global ASC→NULLS FIRST stamp was wrong and is reverted: the real
engine emits NO NULLS clause on ANY target (extensionDefaults.pure
processOrderBy — only the WINDOW convention is dialect-pinned), the two
DIFF goldens it targeted were row-count mismatches (the pin bought zero
corpus tests), and it broke five engine-suite sort/groupBy pins by
forcing H2 placement onto DuckDB. Placement now lives where it belongs:
`Fold.sortNulls` returns null (dialect default), and H2's dialect pins
NULLS LAST both directions for unspecified keys (`H2.sortKey` — the
reference target's default; the DESC side originally copied the window
convention's NULLS FIRST and flipped 13 h2 tds/groupBy tests). Original
finding kept below for the record.

### C1.2 (original finding) — Top-level sort emits no NULL ordering · **LIKELY** · passing-by-luck
`Lowerer.java` sort sites pass `nullOrder = null`; the renderer emits a clause only when
non-null (`AnsiSqlRenderer.java:160-167`), so we inherit DuckDB's NULLS-LAST default.
Two independent goldens want **NULLS FIRST** (one DESC, one ASC).

**The data-dependence is the finding: 73 corpus tests use a DESC sort and 68 pass solely
because their sort columns contain no NULLs.** Exactly one sorts a nullable column, and it
fails. The codebase already pins a null-order convention for the **window** path
(`Lowerer.java:1841-1851`); it was never applied to the top-level sort.

### C1.3 — `~groupBy` silently lost across `extends` · **LIKELY** · dead null-check
`normalizer/MappingNormalizer.java:755`
```java
child.groupBy() != null ? child.groupBy() : flatParent.groupBy(),
```
`model/ClassMapping.java:181` normalizes `groupBy == null → List.of()`, so `child.groupBy()`
is **never** null. An absent `~groupBy` is `List.of()`, which wins and discards the parent's
grouping. **The correct `!isEmpty()` idiom is on the next two lines**, for `primaryKey`.

### C1.4 — Enum decode materialized on the execution path · **LIKELY** · wrong grouping
The engine keeps the **raw** store column and decodes host-side. `plan/PlanEnumForm.java`
implements exactly that rule — and is reachable only from the **plan** surface
(`StatementExecutor.java:421`). On the execution path, `MappingNormalizer.java:2685-2688` →
`:3121-3158` bakes a `CASE` into the class relation.

Consequences: `groupBy([e|$e.type])` groups on the *decoded name*, collapsing two source
values into one group; `getEnum('Type') == 'FTE'` compares the CASE output against a source
value and matches nothing. A latent divergence with **no corpus witness** also exists:
`== 'CONTRACT'` yields `type in ('FTC','FTO')` where the engine yields `type = 'CONTRACT'`.

### C1.5 — Scalars: four rules that hand-inline a Pure body and drop a clause · **LIKELY**
`core/src/main/java/com/legend/lowering/Scalars.java`. Each has a *correct* counter-example
in the same file, which is the fastest way to review them.

| # | Bug | Site | The same file already does it right |
|---|---|---|---|
| a | **`between` drops the `[0..1]` null guards** → `[]` where Pure says `false`. Catalog signature is `Number[0..1]` on all three params, so **every** `between` takes this path | `:677-681` | `:120-134` applies `NullSemantics.optionalOperandGuards` to the standalone `>=`/`<=` — which `between` *is* |
| b | **`contains(coll, val, cmp)` binds comparator params backwards.** Pure's body is `exists(x \| cmp(value, x))` — first param is the *needle*. `[1,2,3]->contains(5, {v,e\|$v>$e})` returns `false`, should be `true` | `:1765-1773` | `:1296-1298` — `removeDuplicates`' comparator binding is correct, verified against an asymmetric PCT case |
| c | **`indexOf` is 0-based, `substring` is 1-based-with-length.** The canonical idiom *from Pure's own test* — `$t->substring($t->indexOf('<'), $t->indexOf('>')+1)` — returns `'s <awesome>'` not `'<awesome>'`. The reference engine keeps **both** 1-based; we match neither convention | `:1202` vs `:1159-1170` | — |
| d | **`size` is the only member of its family with no to-one guard.** `'abc'->size()` emits `coalesce(len('abc'),0)` = **3** | `:742-746` | 13 siblings guard `isToOne`: `first`, `last`, `sum`, `min`, `max`, `mean`, `mode`, `reverse`, `tail`, `joinStrings`, `removeDuplicates`, `greatest`, `least` |

Also, lower severity: `mostRecentDayOfWeek`/`previousDayOfWeek` skip the `datePart()`
truncation their Pure body begins with (`:479-488`); `mode` tie-breaking is DuckDB-arbitrary
where Pure's is smallest-value (`:1084`).

Four **CRASH-ON-VALID** in the same file — `IllegalStateException` on comparator/unit forms
Pure's signature explicitly allows: `comparatorSelect` (`:2815`), `comparatorDirection`
(`:863`, `:874`), `enumName` on a *variable* duration unit (`:3425`).

### C1.6 — Prelude type-imports shadow the user's own imports · **LIKELY** · 4–5 tests
`NameResolver.withPrelude` (`:229-241`) folds every `Pure.nativeClassFqns()` into one
builder; `ImportScope.java:74` keys them by **simple name**; `resolveNameMulti` (`:432-433`)
returns `typeImports` **before** consulting the user's wildcards. `Pure.java:684`'s
`CLASS_BY_FQN` is a `HashMap`, so the winner among colliding names is *unspecified*.
Six colliding names: `Table`, `Union`, `Window`, `Relation`, `PostProcessor`, `Literal`.

### C1.7 — Grouped join target never renames its join key · **LIKELY** · bad SQL
`resolver/Pipelines.java:398-421` has widening arms for *distinct* and *union* targets, none
for `~groupBy`; `:416` rewrites only `parameters().get(0)`. The derived table projects
`k1__PRODUCT_ID` while the ON clause says `PRODUCT_ID`. Source-side rename exists
(`MappingNormalizer.java:2267-2282`); target-side does not.

### C1.8 — Union over-projection into every arm · **LIKELY** · bad SQL
`normalizer/UnionSynthesis.java:804-815` projects the full common property vocabulary into
every union arm rather than the query's demand, referencing columns the fixture DDL never
creates. `SubselectPrune` exists for exactly this and **structurally refuses union branches**
(`SubselectPrune.java:252-263`).

---

## 4. TIER C2 — Not corpus-visible at all

Neither of these can be caught by the sweep, which is why they are called out separately.

### C2.1 — Injection through Pure identifiers · **LIKELY**
`stringLit` is **correct** — standard `''` doubling; a Pure *string literal* cannot escape.
But `DuckDb.structLit`/`structGet` (`:367-376`) interpolate a **Pure property name** raw
into a single-quoted SQL string, and the parser accepts quoted property names carrying `\'`
escapes (`ElementParser.java:1481`, `TokenStreamCursor.unquoteAndUnescape`):

```
Class m::C { 'x'', 1) or (select 1)=(select 1) --': String[1]; }
```
→ `struct_extract(v, 'x'', 1) or (select 1)=(select 1) --')`

**Fix is one call: `stringLit(g.field())`.** Two related paths: `ident()`'s "starts and ends
with a quote → return verbatim" bypass (`AnsiSqlRenderer.java:780`), reachable via
`~'quoted column'`; and `EngineStyleH2.enumSelector`'s missing `'` escape, where the
structurally identical arm 320 lines later *does* escape.

### C2.2 — Silent cross-store wrong answer · **LIKELY**
`Lowerer.java:327-328` discards `TypedTableReference.store()`. A genuine two-database query
therefore **executes against one connection and returns rows**. The corpus surfaces this only
as a plan NPE under `executionPlan`, never as the wrong-rows bug it is.

---

## 5. SUPERSEDED — killed by the T2/T3 work; do not re-open

The sweep ran at `89f3c6a7`. These findings were real then and are dead now:

| Sweep finding | Killed by |
|---|---|
| `EngineStyleH2` drops parens on `AND`/`OR`/`NOT` (F1, F2, F11) | `a093bff9` — *"composite parens are the WALK's decision — the T1.6 class dies structurally"* |
| The 60+ invalid-SQL `Sqlite` inventory; `Sqlite` never overrides `normalize()` | `58f1af2e` — *"the Sqlite class is gone"* |
| graphFetch JSON array aggregated with no ORDER BY (FAIL cluster B1, 5 tests) | `3ef155cb` — GraphFetch PK row-order determinism keys |
| SHAPE runner gap #1: let-position β-expansion, `router/tests` 16 | `5d7e7cf7` + `c7d182d3` — Router leg, 4→20 of 26 |
| Dialects re-parse `strftime` format strings | `a3c83388` — typed date formats |
| Window frame reconstructed from raw args / literal sign | `b788e755` — `TypedOver` carries `WindowFrame` |
| Five `readsVar` implementations, one shadow-aware | `24149e1a` — one `VarUse.reads` |
| Four date-precision ladders | `288cd07f` — one ladder on `PureDateLiteral` |
| `RelationalDataType` non-exhaustive switches | `3cbb2a9b` |
| SHAPE lineage cluster (`scanRelations`, ~30) | `1d6d2fbf`…`b711642c` — four batches |

**Also superseded from the architecture plan:** T2.1 (`withChildren`), T2.2 (lossy
constructors), T2.3 (strategy enum), all of T3.1 and T3.2 landed. The `%g` sub-second
truncation finding may be affected by `a3c83388` — **re-verify before starting it.**

---

## 6. Do NOT spend effort here

- **`assertError` / `assertTdsEquivalent` from `pct-native` unlock zero relational tests.**
  Neither string appears anywhere in `RELATIONAL_CORPUS.md`. That is PCT vocabulary.
  `assertNotEmpty` already exists on `main`.
- **The PCT expected-failure pins are genuine reference parity.** Verified against the
  reference DuckDB manifests: all 33 present by name, and for `substring::testStart` /
  `testStartEnd` the reference's recorded actuals are **byte-identical to ours**. One real
  defect in the ledger: two section-C pins pin the *expected* side — a constant of the test
  — so a regression would keep the build green. Use the reference's recorded actuals, as the
  two sort pins already do.
- **~22 of the FAILs need a harness rule, not engine work** — in-body `rows->at(N)` defeating
  the order policy (7), `Eval.size()` counting TDS rows (2), null surviving the
  Collection/Tabular arms (2), backend-incidental order with no ORDER BY on either side (3),
  stale ledger entries (2).
- **The ~122 honest walls are a roadmap, not a defect list.** Largest: unported platform
  types (10), `executeLegendQuery` (9), `generateObjectReferences` (7), multi-hop through an
  embedded head (7), Phase H4 class-typed-property-as-value (6), aggregate over to-many in
  FILTER position (6).
- **`docs/OUTSTANDING.md` and `docs/CONSTRUCT_COVERAGE.md` are stale** — 641 harness-shape
  rows vs the real count, path constants pointing at `/Users/neema/…`, and a 2489
  denominator. Neither was usable for triage; regenerate or delete.

---

## 7. Drive-by

`resolver/StoreResolver.java:416-419` rebuilds a `TypedJoin` with the 6-arg convenience
constructor, dropping `frameName` (the record has 7 components). Currently inert — TDS joins
carry `frameName == null` — but it is the **T2.2 lossy-constructor pattern still live** after
that sweep. Worth folding into the T2.2 acceptance check.
