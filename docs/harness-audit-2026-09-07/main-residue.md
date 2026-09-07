# Oracle machinery in the production jar

**Verdict: the oracle mass in `core/src/main` has barely moved.** Measured on the same 29-file
inventory at the audit commit that produced the ~9.3k figure (`22e2d9e3`) and at HEAD:

| point | raw lines | files |
|---|---:|---:|
| `22e2d9e3` (the 2026-09-06 audit) | **9,826** | 29 |
| `ff359bae` (batch 115, harness deletion) | **9,853** | 29 |
| **HEAD `f073b394`** | **9,421** | 22 |

**Net −405 lines, −4.1%.** Stripped of comments the set is ~6,400 lines of actual code.

The −405 came entirely from deleting seven small peripherals:

| deleted | lines |
|---|---:|
| `exec/SqlTextEmission` | 89 |
| `exec/PostProcessBoundary` | 78 |
| `exec/TimingLedger` | 77 |
| `exec/CanonDeclines` | 60 |
| `lowering/NavArmCensus` | 58 |
| `exec/PctRenderOption` | 45 |
| `validation/DriverPkOption` | 31 |

Against that, the survivors grew: `AssertVerdicts` +8, `VerdictQueries` +25, `RawSqlBoundary`
+7, `SqlTextVerdicts` −7. **Net +33 on the ten largest files.** `AssertVerdicts`,
`SqlTypeCensus`, `CanonicalDivergence`, `PureAsserts`, `TdsCompare`, `CanonicalRenderSql`,
`JsonCompare` and `CanonicalForm` are **byte-identical to their pre-rewrite state**.

What actually shrank by 12,838 lines was `core/src/test` (16,653 → 3,815) — which never shipped
in the jar. **Not one line of §6f item 2's deletion list has been touched**, and **zero of the
12 thread-locals in the plan's §1 have been deleted** (the three that died —
`DriverPkOption`, `PostProcessBoundary`, `PctRenderOption` — died *before* that document was
written).

---

## 1. Per-file: product or oracle

"Reachable" = a call chain exists from `Compiler.execute*`/`QueryService`. **"Fires in prod"** is
the honest test — whether anything other than a test corpus ever makes it run.

| file | LOC raw/stripped | reachable? | fires in prod? | verdict | evidence |
|---|---|---|---|---|---|
| `AssertVerdicts.java` | 2266 / 1649 | **yes** — `StatementExecutor:317,366` ← `Compiler:771,885` ← `QueryService:69,103,124,180` | only if the posted program's statement root is an assert | **ORACLE** | package-private `final class`; 14 assert families; **32 decline sites**; its whole job is adjudicating corpus asserts |
| `StatementExecutor.java` | 2792 / 2699 | **yes** — the statement channel | yes | **MIXED** | genuine product loop, but hosts the assert hook (`:317,366`), the engine-text funnels (`:619-620,640`), and threads `CanonRider` (6), `replayOracle` (14), `assertListener` (15) through its signatures |
| `SqlTextVerdicts.java` | 1432 / 1044 | yes via `AssertVerdicts:170,179,188,260` | **no — walls** | **ORACLE** | `:145-152`: `if (oracle == null) throw NotImplementedException("…correct outside tests: there are no goldens")` |
| `exec/SqlTypeCensus.java` | 1031 / 684 | yes — `Executor:83,246,252,587,591` | writes counters nothing in main reads | **ORACLE** | 26 `LongAdder` + 6 maps + 2 thread-locals; all readers are `pct/PctCensusGate` and `ChannelB*Test` |
| `lowering/CanonicalRenderSql.java` | 826 / 550 | yes | yes | **PRODUCT** | also used by `LiteralSpelling`, `ConstructionCanon`, `InstanceEquality`, `ScanOrder` — the DB-computes-the-render direction, i.e. **the cure** |
| `exec/CanonicalDivergence.java` | 728 / 456 | yes — 34 sites | records only; **every `probe*` returns void** | **ORACLE** | no main file reads a counter; **~280 LOC (`:401-680`) has zero production and zero PCT callers** |
| `exec/TdsCompare.java` | 577 / 444 | yes — 12 sites | yes, on every TDS assert | **ORACLE** | Java comparing grid values; §6f names it for deletion |
| `exec/PureAsserts.java` | 512 / 313 | yes — 11 sites in `AssertVerdicts`, 3 in `TdsCompare`, 1 in `JsonCompare` | yes, on **every** assert | **ORACLE** | §6f names it. `TestDataGenerator:1442` uses only `repr` (rendering, not comparison) |
| `compiler/spec/VerdictQueries.java` | 438 / 323 | yes | with the assert arm | **ORACLE (right direction)** | mints in-database verdict queries — pure AST synthesis, no value touched |
| `lowering/StampCensus.java` | 203 / 115 | yes — `Lowerer:2387` | **yes, and it throws** | **PRODUCT** | `fire():114-122` throws unless `LL_STAMP_COUNT`; a build-breaking lowering invariant. Only the `public ThreadLocal CONTEXT` is oracle |
| `exec/SqlReplayOracle.java` | 161 / 59 | yes — `Compiler:873,883` | **no** — "Production registers nothing" | **ORACLE (correct shape)** | an SPI; comparison policy explicitly outside core (`:83-86`). The 59 stripped lines are the right way to do this |
| `AssertErrorNative.java` | 156 / 91 | yes — `StatementExecutor:393` | yes | **PRODUCT** | `assertError` is a real platform native; catching a DB error's message + source position has no SQL formulation |
| `LineageTreeVerdicts.java` | 153 / 116 | yes — `AssertVerdicts:110` | with the assert arm | **ORACLE (exemplary)** | judges via one DuckDB query (`TREE_ROWS:47-63`); the Java residue is a single `goldenRows.equals(ourRows)` at `:99` |
| `exec/CanonRider.java` | 137 / 60 | yes — 18 sites | yes | **MIXED** | compares nothing; an immutable carrier of DB-computed canon columns. §6f names it, but it is the *result* of the good refactor |
| `exec/CanonicalForm.java` | 120 / 62 | **no** — one caller, `CanonicalDivergence:206-208` | **no** | **ORACLE (dead-ish)** | its own doc: "It is NOT a verdict path" |
| `exec/JsonCompare.java` | 118 / 64 | yes — `AssertVerdicts:509`, `PureAsserts:351` | yes | **ORACLE** | §6f names it |
| `PlanAllocations.java` | 474 | yes | yes | **PRODUCT** | the Allocation plan-node factory |
| `AggAwareActivities.java` | 251 / 227 | yes | yes | **PRODUCT** | builds the engine's `AggregationAwareActivity` envelope |
| `SqlTextInputs.java` | 65 / 29 | yes | yes | **PRODUCT** | one structured reading of `toSQLString`'s overloads |
| `compiler/spec/VerdictRoutes.java` | 55 / 35 | yes — `StatementExecutor:363` | assert routing | **ORACLE** | pure predicates naming which helper-wrapped asserts take the verdict route |
| `exec/TestResources.java` | 41 / 24 | yes — `CsvLoad:43` | **no — walls** | **ORACLE (wrong mechanism)** | a `ThreadLocal` SPI whose only registrant is `MinimalCorpus:443` |
| `sql/dialect/TextGoldens.java` | 34 / 18 | yes — `StatementExecutor:620,640` | only in the text funnels | **ORACLE** | exactly **one** reader: `EngineStyleH2:1031` |
| `lowering/EngineTextBoundary.java` | 38 / 18 | yes — `StatementExecutor:619` | only in the text funnels | **ORACLE** | exactly **one** reader: `CastPolicy:50` |
| `exec/AssertListener.java` | 18 / 5 | yes | observation only | **PRODUCT (correct SPI)** | *"The platform owns the JUDGMENT — this only reports it."* The model every other seam should copy |

---

## 2. Java comparing values in main

Every `AssertFailed` in `core/src/main` is raised at exactly three places —
`AssertVerdicts:2264`, `SqlTextVerdicts:1430`, `AssertErrorNative:84,96,112,119,125` — so the
boundary is closed.

**The structural finding first.** `AssertVerdicts.finish` (`:932-957`) makes the DB byte verdict
the verdict of record — `boolean held = byteHeld != null ? byteHeld : hostHeld` (`:941`) — but
the **host lattice is computed unconditionally anyway**, because the failure *message* comes only
from it (`:948`), and a host/byte message divergence throws (`:949-953`). **So Java compares
values on every single assert, byte channel or not**, and nobody counts how many verdicts Java
actually decides. §6b says so at `END_TO_END_PLAN:249`.

### `exec/PureAsserts.java` — the host lattice

- **TDSNull sentinel, asymmetric** — `:265-266`: expected `'TDSNull'` == actual SQL NULL; the
  reverse grant is deliberately absent.
- **No cross-kind numeric equality** — `:276-290` (integral×float FALSE), `:291-298` (Decimal
  cross FALSE), `:299-304`. Integral widens to `BigInteger` (`:286-288`) to avoid HUGEINT overflow.
- **Decimal scale-SENSITIVE** — `:296-297` `be.equals(ba)`, not `compareTo`.
- **Float: 2-ULP leniency** — `:325-326`, Double×Double only, censused under `LL_TOL_COUNT`.
- **`assertEqWithinTolerance`** — exact-kind branch uses `BigDecimal` (`:115-118`), else double.
- **`assertSameElements`** — sort-then-ordered-equal (`:86-88`) with a total order over `Any`
  (`:481-510`); an unmatched kind **throws**.
- Strings compare exactly; no case or whitespace normalization anywhere.

*SQL form:* `SELECT canon_e IS NOT DISTINCT FROM canon_a`; `assertSameElements` is two-way
`EXCEPT ALL`; `assertSize` is `count(*) = :n`; the tolerance arm is `abs(e-a) <= abs(:delta)`,
exact in DECIMAL for free.

### `exec/TdsCompare.java` — grids, tuples, rendered text

- **Headers pin exactly, ordered** (`:46-47`).
- **Row order caller-driven**; unordered ⇒ row-tuple multiset with cohesion (`:61-74`),
  instrumented at `:77`, never rescued.
- **`tdsEquivalent` delta policy** — `:357-359` numeric `abs(nx-ny) <= |delta|`, `:366` temporal
  `abs(ex-ey) <= |timeDeltaSeconds|` via `toInstantFloor()` at UTC; rows zip **in order**.
- **CSV cell tolerance** — `:541-576`, gated on both tokens containing `'.'` (`:545`), then
  `:563-565` `tol = sig >= 10 ? max(0.5*10^-dp, |ev|*1e-11) : |ev|*1e-11`. **The loosest policy
  in the tree, unconditioned by any declared type.**
- **`ulpOnlyCellDrift`** (`:268-288`) lets the byte verdict pass by policy at
  `AssertVerdicts:1024-1030`.

*SQL form:* `EXCEPT ALL` both ways **is** the row-cohesion multiset rule exactly; rendered text
is `string_split`/`unnest`/`EXCEPT ALL`; the tolerance is
`abs(try_cast(a AS DOUBLE) - try_cast(e AS DOUBLE)) <= …` with `try_cast … IS NULL` reproducing
the decimal-point gate.

### `exec/JsonCompare.java`

- Objects: key-set equality, order-insensitive (`:66`); arrays ordered and size-checked (`:80`).
- **Leaf rule is scale-INSENSITIVE** — `:108-111` `be.compareTo(ba) == 0` — **the exact opposite
  of `PureAsserts.equalScalar:296-297`.** Two comparators in the same package disagree about
  `5.0` vs `5.000000000`. Documented at `:99-105`, but still two policies.

### `AssertVerdicts.java` holds policy, not just dispatch

`:467-471` (`assertSize`), `:609` (`assert`/`assertFalse`), `:633-636` (`assertInstanceOf` over
the wire `__type`), `:686-702` (`assertIs`), **`:1013` `byteHeld = es.equals(as2)`** (the grid
byte verdict, with host-side `sort()` at `:1010-1012`), **`:1697`
`Objects.equals(fe2.text(), fa2.text())`** (the scalar byte verdict), plus the two named
leniencies that **override** the byte verdict: 2-ULP (`:1024-1030`, `:1663-1667`) and the
TDSNull sentinel (`:1676-1679`, expected-side only).

### The two that are already right

- **`LineageTreeVerdicts:99`** — `goldenRows.equals(ourRows)` where **both sides are strings the
  database produced through one query** (`TREE_ROWS:47-63`). **The exemplar.**
- **`AssertErrorNative:93`** — `if (!actual.equals(expected))`, exact, no prefix stripping
  (`:88-91` records deleting the old regex). **Legitimately host-bound** — DuckDB has no
  `BEGIN…EXCEPTION` to project its own error as a row. One nit: the javadoc at `:153-156`
  describes a prefix-stripping method that no longer exists.

### `SqlTextVerdicts` — `golden.equals(ours)` at `:144, 273, 393, 517, 773, 924`

Exact string equality, no normalization. Text is a **census** number; rows decide (`:1284-1299`).
On oracle decline text becomes the contract; foreign dialects short-circuit at `:153-159`.

### Confirmed clean

`GridProbe`, `PctProbe` (schema reads), `SqlTypeCensus:288-320,753-765` (type-vs-type, not
value-vs-value), `VerdictQueries` (AST synthesis), `TestResources`, `InstanceIds`,
`AssertListener`, and every `PureAsserts` mention in `Pure.java`/`Scalars.java`/`Repr.java`/
`sql/Json.java`/`Executor.java` — **comments only**. The sole non-verdict call is
`TestDataGenerator:1442` (`repr`).

---

## 3. The `equal()` disagreement — five-way

**STILL LIVE.** There is no shared equality kernel — no `EqualityKernel`, `ValueEquality`,
`PureEq` or `Comparisons` exists anywhere in `core/src/main`.

| # | implementation | file:line | `1 == 1.0` | scope |
|---|---|---|---|---|
| a | `resolver/LiteralFolds.literalEquals` | `:80-86` — `new BigDecimal(ln.toString()).compareTo(...) == 0` | **TRUE** | live product: `StoreResolver:296` picks the taken `if()` branch |
| b | SQL lowering (`equal`/`eq`) | `Scalars:84-157` → `NullSemantics:149` | **TRUE** | every in-query `==`; the declared World 2 |
| c | `exec/PureAsserts.equalScalar` | `:271-281` — *"there is NO cross-kind numeric equality in the engine"* | **FALSE** | the assert adjudicator; **the judge** |
| d | `compiler/spec/LiteralUnroll.equalityFold` | `:378-410`, dispatched `:545-549` | declines cross-kind, but folds same-kind Decimal with `BigDecimal.equals` → `3.0d == 3.00d` is **FALSE** where SQL says TRUE | inlined user-function bodies |
| e | `compiler/spec/StaticFold.staticEquals` | `:660-667` — `return a.equals(b)` | **FALSE** (`Long.equals(Double)`) | `Typer:650,1596`, `<<NormalizeRequiredFunction>>` bodies, `.columns` metadata folds |

**The tests codify the disagreement rather than resolving it.**
`EqualityWorldsConformanceTest:57-66` defines a `diverge(...)` helper — *"A DECLARED divergence:
each world pinned at its OWN verdict"* — and `:94-95` pins exactly this case:
`diverge(false, true, "1", "1.0", 1L, 1.0d, "SQL numeric coercion — engine-relational parity")`.
`:116-121` pins two more. `VerdictWorld2ConsistencyTest:61-65` explicitly disclaims the numeric
lattice.

**The new part, and the live defect:** (a) and (e) are *both* compile-time World-2 folders and
they give **opposite** answers. Neither is covered by `EqualityWorldsConformanceTest`, neither is
cross-checked against the SQL it defers to, and **no corpus test can catch it because in the
assert path the disagreeing implementation *is* the judge.** The (b)-vs-(c) split is at least
ratified and mechanically fenced (`VerdictChannelRegisterTest:33-43` pins the five files
permitted to call `PureAsserts.equal*`). **The (a)-vs-(d)-vs-(e) split is fenced by nothing.**

`builtin/Pure.java:1063-1068` registers the `equal`/`eq` *signatures* with no body; the only
routing registered is the SQL lowering at `Scalars:84-86`.

---

## 4. Assert-family suppression

`PlatformTypes:558-571` declares `ASSERT_FAMILY_OWNED` — **13 FQNs**: `assert`, `assertFalse`,
`assertEquals`, `assertNotEquals`, `assertSameElements`, `assertSize`, `assertEq`, `assertEmpty`,
`assertNotEmpty`, `assertIs`, `assertContains`, `assertEqWithinTolerance`,
`assertJsonStringsEqual`. `isPlatformOwnedFunction:574-600` adds `ASSERT_ERROR` and
`ASSERT_INSTANCE_OF` → **15 suppressed**. Separately `isVerdictFunction:523-530` prefix-matches
the *entire* `meta::pure::functions::asserts::` package plus four FQNs outside it
(`assertSameSQL`, `assertSqlEquals`, `assertEqualsH2Compatible`, `assertTdsEquivalent`).

The drop is at `FunctionCompiler:69-77`: if `isPlatformOwnedFunction(fqn)` the corpus's own
`findFunction(fqn)` results never join the overload set, with a one-shot stderr line. Applied
also to the bare-name path (`:47-50`) so a suppressed definition cannot be smuggled back.

**What it would take to let `assertEquals` compile as the Pure program it is.** The real body
reduces to `equal($expected, $actual)` → `assert(cond, msg)` → `if(cond, |true, |fail(msg))`.
Four things are needed and **three already exist**:

1. **A SQL raise** — `SqlFn.ERROR` (`sql/SqlFn:87`), used by `CastPolicy.crossKindRaise:190`,
   with provenance stripping in `exec/RaisedErrors`. ✅
2. **A canonical render of each side for the message** — `lowering/CanonicalRenderSql` (826
   lines) computes it *in the database*, and `CanonRider` carries it back on the same query. ✅
3. **A one-boolean-row statement shape** — the plan's "single-shot" (`:237-241`). Partly
   designed, not built.
4. **`equal(Any[*], Any[*])` lowered to SQL for collections, structs and TDS** — **the actual
   blocker.** `Scalars:84` registers `equal`/`eq` only for scalars; collection, keyed-instance
   and grid equality live in `AssertVerdicts`' `restrictToKeys`/`sideRowCanons`/
   `tdsRowValuesVerdict` arms as Java. **The 32 declines in `AssertVerdicts` are the enumeration
   of what item 4 still cannot express.**

So unsuppressing the family is not a compiler-flag change; it is the single-shot leg. But the
honest statement is that it is **one missing lowering rule plus a statement shape — not
fifteen.**

---

## 5. PCT coupling — real or removable?

**Removable.** The dependency is real but thin and purely observational.

**What PCT reads.** `PctCensusGate` runs inside a JUnit3 `TestSetup.tearDown()` (`:168`),
wrapped by all five `Test_LegendLite_*_PCT` suites. It touches **11 `SqlTypeCensus` members**:
`summary()` `:170,211`, `classes(100)` `:176`, `allSamples()` `:178` (print-only), and seven
assertions — `mismatchCount()==0` `:183`, `wireAdoptPendingCount()<=0` `:186`,
`wireDivergeCount()<=0` `:188`, `untypedCount()<=0` `:191`, `bottomMultCount()<=0` `:194`,
`wireUnknownCount()<=0` `:196`, `wireIntOrNullEmptyCount()<=226` `:199`, `nullBreachCount()<=0`
`:201`.

`ChannelB*Test` (5 files, identical block) asserts `CanonicalDivergence.sqlDisagreeCount()==0`,
`sqlDeclinedCount()<=0`, `SqlTypeCensus.wireDivergeCount()<=75`, `wireAdoptPendingCount()<=103`,
`mismatchCount()==0`. `ChannelB:191-192` writes the two attribution thread-locals.

**Counts, not behaviour.** All 34 `CanonicalDivergence` call sites in main are void `probe*`
sinks — **no main file reads any counter**. The two sites that look like branches
(`AssertVerdicts:1024-1030`, `:1664-1666`) branch on `TdsCompare.ulpOnlyCellDrift`/
`withinDeclaredUlp`, not on census state. `SqlTypeCensus` has 7 main call sites, of which only
`probeSuspended()` reads a return value — and it reads the census's *own* flag.

**`StampCensus` is misnamed and must stay.** `Lowerer:2387` calls `check()`, which **throws**
unless `LL_STAMP_COUNT` is set. PCT uses exactly one symbol — `CONTEXT.set(fqn)` at
`ChannelB:191` — read only to decorate the thrown message. A 2-line fix, not a deletion.

**~500 of the 1,759 lines are already dead in this tree:**
- The whole V7 dual-channel block, `CanonicalDivergence:401-680` (~280 LOC): `v7Verdict`,
  `v7Declined`, `noteWall`, `v7Report`, `v7Summary`, `v7DisagreeCount`, `v7DeclinedCount`,
  `v7QuarantinedCount`, `v7QuarantinedWallCount`, `v7DeclinedByReason`,
  `v7DeclinedByReasonPrefix`, `METAMODEL_QUARANTINE`, `QUARANTINED_WALL_TESTS`. **Zero
  production and zero PCT callers.** Its only exerciser is `V7DualChannelCensusTest:23-34`,
  which **feeds the API its own input and asserts on that input.**
- `muteAll:248` / `r1Suspend:253` — no callers, so `MUTED`/`R1_SUSPENDED` are permanently false
  and all 13 `if (MUTED.get()) return;` guards are dead.
- `CONTEXT_SOURCE:434` — a `public static volatile Supplier<String>` with **no setter anywhere**.
  Every census witness is stamped `<unattributed>`.
- **21 dead getters** over four accumulator families: `disagreeSamples:309`,
  `sqlDisagreeSamples:297`, `sqlUlpPolicyCount:378`, `sqlTdsNullPolicyCount:397`,
  `disagreeCount:694`, `residueCount:698`; `SqlTypeCensus.classifyExternal:813`, and the entire
  §E3 nullability differential (`:96-194`) and slack census (`:118-136, 502-544`).

### The concrete refactor

No file needed — the PCT pins run in the **same JVM** as the run they pin, and the counters are
documented as cumulative-per-JVM, so a test-side accumulator singleton reproduces today's
semantics exactly.

**Stays in main — two observer interfaces, ~25 LOC:**

```java
interface WireProbe {                        // replaces SqlTypeCensus in main
    void plan(SqlQuery p);                                        // Executor:83
    void wire(SqlQuery p, ResultSetMetaData md, boolean pivot, String dialect); // Executor:246
    void valueSeen(int col);  void nullSeen(int col);             // Executor:587,591
    void settle();                                                // Executor:252
    boolean suspended();  void suspend(boolean on);               // SqlTextVerdicts:1253-1267
}
interface VerdictProbe {                     // replaces CanonicalDivergence in main
    void equalVerdict(String family, List<Object> e, List<Object> a, boolean held, boolean unordered);
    void sameElements(List<Object> e, List<Object> a, boolean held);
    void gridText(String expected, String actual, boolean held, boolean sorted, String form);
    void sqlVerdict(String family, boolean hostHeld, boolean sqlHeld, String detail);
    void declined(String reason);
    void policy(String kind, String detail);      // folds sqlUlpPolicy + sqlTdsNullPolicy
    void lineageRows(boolean held);
}
```

**Moves to test:** ~950 of `SqlTypeCensus`'s 1031 (`walk:683`, `nulDifferential:138`,
`delivers:550`, `metaToType:590`, `wireSpelling:607`, `shapeOf:794`, `sketch:833`,
`memberAnatomy:902`, `WireWatch`/`WatchCol`/`LooseCol`, every counter) and ~690 of
`CanonicalDivergence`'s 728 (`byteEqual:173`, `keyOf:205`, `kindClass:215`, `record:257`, the
grid-text classifier `:99-151`). **Cost in main: 41 call-site rewrites** (7 + 34).

**The injection seam exists and is proven:** `exec/AssertListener:14` is carried on `ExecEnv`
(`StatementExecutor:98,113,137`) and threaded through `Compiler:860,872,882`. Add a second
nullable field beside it. But `AssertListener`'s signature is too narrow for the probe payloads,
so it must be **paralleled, not reused** — a real main-side change.

**Genuine blockers, ranked:**
1. **Module visibility.** `pct/pom.xml:36-39` depends on `legend-lite-core` at **compile scope
   with no test-jar**, and core publishes none. A census in `core/src/test` is invisible to PCT
   today. Fix: add `<goal>test-jar</goal>` plus a test-scoped dep, or create a
   `legend-lite-instrumentation` module — needed anyway, since core's own tests
   (`AssertVerdictsTest:129,131,136,141`; `InstanceIdentityTest:156,160,245,256`;
   `LiteralChannelTest:63,67,95,101`; `CanonicalFormTest:119-138`) also read the counters.
2. **No seam at the fetch funnel.** `Executor:579-592` (`private static fetch`) is the only place
   per-cell null/non-null evidence exists. `WireProbe` is genuinely new main surface — but that
   is **7 call sites and one interface, not 1,031 lines**. This is the only part of the
   "irreducible" claim with substance.
3. `Executor.hasPivot` is package-private (`:887`) and called from `SqlTypeCensus:233` — pass
   `pivot` as a boolean instead.
4. Guardrail edits: `ArchitectureTest:817,825` name `CanonicalDivergence`; `:865-921` holds
   **13 registered static-collection exceptions** for these two classes — **all 13 disappear**,
   a real architectural win. Plus `HarnessDisciplineTest:133,149` and `ErrorShapeGuardrailTest:105`.

**Verdict on batch 123's justification: not sustained.** PCT reads counters, not behaviour, in
the same JVM. Option (C) in the plan (`:100-107`) is correct and the two interfaces above are
its concrete form.

---

## 6. Deletion path

Ordered by (raw lines out of the jar) ÷ effort.

| # | action | lines out | effort | blocker |
|---|---|---:|---|---|
| 1 | **Delete the dead inside `CanonicalDivergence` + `SqlTypeCensus`** — V7 block `:401-680`, `muteAll`/`r1Suspend`/`CONTEXT_SOURCE`/`noteWall`, the 21 zero-caller getters, the nullability-differential `:96-194` and slack `:118-136,502-544`, `classifyExternal:813`. Delete `V7DualChannelCensusTest` | **~500** | hours | none — zero callers, verified |
| 2 | **Orphan statics sweep**: `RawSqlBoundary.XLATE_NANOS:179`, `SqlTyping.PAD_READ_FLIPPED:41`, `RawSqlBoundary.META_RECORDER:115` (accessor has **zero** call sites), `NullSemantics.FILTER_POS:100`, the `PROBE_SUSPENDED` reader | ~60 | hours | none |
| 3 | **`TestResources` → `ExecuteOptions` field.** One registrant, one reader | 41 + a TL | ~1h | none; `ExecuteOptions` already exists |
| 4 | **`TextGoldens` + `EngineTextBoundary` → renderer construction.** Two TLs with **one reader each**, three setters | 72 + 2 TLs | ~half day | none; plan step 1a and the easiest of the twelve |
| 5 | **`StampCensus.CONTEXT` → parameter** | 0 (2 lines) | ~1h | none |
| 6 | **`CanonicalDivergence` → `VerdictProbe`** (after #1, so ~450 lines move). 34 sites | **~450** | ~1 day | PCT compile-scope dep; `ArchitectureTest:817,825` + 13 registrations |
| 7 | **`SqlTypeCensus` → `WireProbe`** (after #1). 7 sites | **~600** | ~1 day | same module-visibility blocker; `hasPivot` visibility (trivial) |
| 8 | **`SqlReplayOracle` off the public API.** Fold into `ExecuteOptions`; delete the `Compiler.executeResolved(…, SqlReplayOracle)` overload whose own javadoc says *"Production never calls this arity"* (`:865-877`). The interface stays | ~100 + 14 params | ~half day | `StatementExecutor` threads it through 5 overloads |
| 9 | **`PureAsserts` + `TdsCompare` + `JsonCompare` + `CanonicalForm` deleted** (§6f item 2) | **1,327** | ~1 week | **Real.** `AssertVerdicts.finish:948` needs the host lattice for the failure *message*, and `byteHeld` is null wherever a decline fires (32 sites). Requires (i) message composition from the DB-computed canons; (ii) the 2-ULP and TDSNull leniencies expressed in SQL, which moves where the census counts fire; (iii) `tdsEquivalent`/`renderedText`/`cellEquals` migrated — `LineageTreeVerdicts.TREE_ROWS:47-63` proves the text→rows→compare pattern works |
| 10 | **`SqlTextVerdicts` + `LineageTreeVerdicts` → the referee** (plan step 3) | 1,585 | ~1 week | the TEXT decision (§6d) is unmade |
| 11 | **`AssertVerdicts` + `VerdictRoutes` + `VerdictQueries` → single-shot** | **2,759** | multi-week | needs `equal(Any[*],Any[*])` lowered to SQL — the 32 declines are its work list; needs item 4 first |
| 12 | **`AssertErrorNative` — do not delete** | 0 | — | no SQL formulation exists; correctly host-bound |

**Steps 1–5 remove ~670 lines and 4 of the 12 thread-locals in about two days, with zero design
risk. That is more jar reduction than the last ten batches produced.**

---

## 7. What is genuinely good

- **The harness deletion is real and large** — 16,653 → 3,815 test lines; `MinimalCorpus` is 581.
- **The seven deletions are clean, not renames.** All absent from the root tree with **zero live
  references in main** (two dangling javadoc mentions only: `ExecuteOptions:8`,
  `SqlTextVerdicts:31`). No stubs left behind.
- **`AssertListener.java` (18 lines) is the model.** *"The platform owns the JUDGMENT — this only
  reports it."* Every other seam should be shaped like this.
- **`SqlReplayOracle` is the right architecture in the wrong place.** The platform defines the
  seam, production registers nothing, the wall at `SqlTextVerdicts:146-152` is loud and correct.
  Only its position in the public `Compiler` signature is wrong.
- **`ArchitectureTest.hostVerdictIsReachableOnlyFromTheVerdictSeam` (`:806-829`) is enforced and
  holds.** Every apparent violator was checked: `sql/Json:181`, `Scalars:1605`, `Repr:15`,
  `Pure:1980,2006`, `Executor:322` are **comments only**. The single real call is
  `TestDataGenerator:1442`, which is registered. **The fence works.**
- **`LineageTreeVerdicts` (153 lines) is the correct answer already built** — two tree prints
  become rows through one DuckDB query, then rows compare. The smallest verdict file and the only
  one whose Java residue is a single `equals` on two DB-produced values.
- **`CanonicalRenderSql` + `CanonRider` are the cure, not the disease.** The canon rides the side
  query as appended VARCHAR projections — one execution producing values *and* canon text. §6f
  lists them for deletion, which reads as over-inclusive: **what should die is the *host* lattice
  they were built to displace.**
- **The `ProgramFacts` / `RawSqlBoundary.Raw` direction (batch 118) is right** — the harness reads
  facts the platform states instead of scanning bodies.
- **`PctFunctionSuppressionTest` pins the suppression rule against silent inversion with a
  deliberately-wrong reference body.** A test that would actually fail.

---

## 8. What DONE means for main-side residue

1. `grep -rn "ThreadLocal<" core/src/main/java` → **0**. Today: **12 fields in 9 files**.
2. `grep -rn "SqlTypeCensus\|CanonicalDivergence" core/src/main/java` → **0**. The PCT pins read
   a `WireProbe`/`VerdictProbe` in a test or instrumentation module.
3. `ls core/src/main/java/com/legend/exec/{PureAsserts,TdsCompare,JsonCompare,CanonicalForm}.java`
   → **no such file**. Not bypassed — absent.
4. `grep -rn "\.equals(\|compareTo\|Objects.equals\|Math.abs(" …/AssertVerdicts.java` returns
   **zero value-comparison sites**; `finish`'s `hostHeld` parameter no longer exists and the
   failure message derives from the same DB-computed canon that produced the verdict.
5. **Exactly one implementation of Pure `equal` in `core/src/main`.** Today: **five**. Interim
   milestone if full unification is deferred: `EqualityWorldsConformanceTest` gains rows for
   `LiteralFolds` and `StaticFold` and **they agree with the SQL lowering** — `diverge(...)` is
   used only for the ratified World-1/World-2 split, never between two World-2 folders.
6. `grep -rn "SqlReplayOracle" …/Compiler.java` → **0**.
7. `ArchitectureTest`'s static-collection register contains **zero** `CanonicalDivergence.*` /
   `SqlTypeCensus.*` rows. Today: **13**.
8. `grep -rn "TextGoldens\|EngineTextBoundary\|StampCensus.CONTEXT\|TestResources" core/src/main/java`
   → the flags are constructor arguments or `ExecuteOptions` fields; no global mode remains.
9. **No accumulator in main is written and never read.** Today's violations:
   `RawSqlBoundary.XLATE_NANOS`, `SqlTyping.PAD_READ_FLIPPED`, `RawSqlBoundary.META_RECORDER`,
   `NullSemantics.FILTER_POS`, `CanonicalDivergence.V7_*`, `QUARANTINED_WALL_TESTS`,
   `SqlTypeCensus.NUL_*`/`SLACK_*`/`TOLERATED_*`.
10. **`JavaEvalLedgerTest`'s `EVICT_SIZE` map lists no verdict class.** Today it pins
    `AssertVerdicts` 1652, `SqlTextVerdicts` 1071, `LineageTreeVerdicts` 116, `PureAsserts` 313,
    `TdsCompare` 444, `JsonCompare` 70 — **and does not track `SqlTypeCensus`,
    `CanonicalDivergence`, `CanonicalRenderSql`, `VerdictQueries`, `CanonRider`, `CanonicalForm`
    at all.** Adding those six is itself a checkable first step: it makes the unaccounted ~2,400
    lines visible to the ratchet.
11. **The §6b count exists** — a per-lane tally of verdicts by channel (byte / host-fallback /
    text). Today `finish:941` prefers the byte verdict but **computes the host lattice
    unconditionally**, and nobody counts how often each decides. Until that number is printed,
    *"host judged ZERO"* is unmeasurable — and §6b says so.
12. **The oracle-set total is stated in the plan and shrinks monotonically.** Today's honest
    baseline: **9,421 raw / ~6,400 stripped**, down 4.1% from 9,826.
