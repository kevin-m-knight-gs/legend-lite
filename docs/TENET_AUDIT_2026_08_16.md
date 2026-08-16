# Tenet Audit, round 2 — "Java orchestrates, the DATABASE executes"

> **Companion to `docs/TENET_REMEDIATION.md`** (round 1, run at `16ee3358` / `b9746692`).
> That document remains the rubric authority — **§6 is used verbatim here** — but its
> *findings* are 691 commits and +196,700 / −82,716 lines stale, and it never covered
> `pct/` at all. Every site below was re-read at HEAD (`f6a50a7d`).
>
> **Method:** eleven parallel auditors, one per subsystem, each applying §6's rubric and
> required to cite line numbers read at HEAD rather than copied forward. Two auditors
> executed the suites they audited (PCT: 1,109/1,109 green, 2,473 native executions mined).

---

## 1. Verdict

**The query compiler is clean, and that verdict is now provable rather than merely readable.
Every violation of consequence lives at the two edges the compiler does not own: the
result boundary (egress) and the test harnesses.**

Round 1 said "the tenet holds in the query compiler." Round 2 re-tested that claim against
+10,451 lines of churn in `lowering/`, `resolver/`, `normalizer/`, `compiler/`, `builtin/`,
`sql/` and **found zero VIOLATION rows**. Three structural facts now carry it:

- `lowering/Scalars.java:52-54` — `interface Rule extends BiFunction<TypedNativeCall, List<SqlExpr>, SqlExpr>`.
  **Returning a Java value from a lowering rule is a compile error.** Dispatch is one map
  lookup on resolved-overload identity (`:2382`); an unregistered overload throws (`:2384-2386`).
  34 throw sites, zero silent defaults.
- **All 21 compile-time fold sites** are guarded on the *already-lowered* expression being a
  literal. Zero folds run on a non-literal.
- `resolver/` has **zero `java.sql` imports** across 20,000+ lines. One grep proves
  "nested resolution is typed→typed".
- `compiler/spec/StaticFold.java` is **frozen** — byte-identical across 691 commits except a
  package rename and one `@Nullable`. It has not grown an arm. Two call sites, both gated.

The same is true of two large surfaces round 1 never examined:

- **`protocol/` + `parser/` + `parser/section/` (~15,000 lines, new): clean.** Not one
  `java.sql`, `ResultSet`, or `Connection` reference. Every rubric path terminates at Q1-no.
  The ~7,800 lines of emitters are **write-only** — nothing parses that JSON back — so they
  are off the execution path entirely.
- **`lineage/` (3,046 lines): clean on the tenet.** No shadow evaluator in `ScanRelations`'
  2,571 lines; no execution, no `ResultSet`, no host-side Pure evaluation.

### 1.1 What round 1 got wrong, corrected here

Round 1's leads were re-tested, not trusted. Five of its findings are **fixed or refuted**:

| Round-1 claim | Status at HEAD |
|---|---|
| V1.5 — relation `->toString()` **silently mis-lowers** to a JSON-cell flatten cast to VARCHAR | **FIXED.** `Scalars.java:2790-2791` now throws `NotImplementedException("toString over " + t + " is not modeled")`, citing the tenet. The latent wrong answer is a loud wall. Called by one auditor "the single best fix since the prior audit." |
| `H2Verify.norm` collapses 14-digit integers (`12345678901234` == `…299`) | **FIXED**, and fixed correctly — `H2Verify.java:546-563` splits integral values out to compare exactly, and the comment names the false PASS the old code produced. |
| V2.2a — no constant arm; `createTablesAndFillDb` costs 431 statements, 214 touching no table | **REMEDIATED.** `LiteralFold` short-circuits all **143/143** bare-literal `executeInDb` calls. Its admission rule is the best discipline document in the tree and explicitly refuses to widen for performance. |
| `core/pom.xml` declares **no H2 dependency** | **STALE.** It declares H2 2.1.214 at compile scope. |
| `HostEval` is "growing one arm per wall" (~25 arms, +1,036 lines mid-audit) | **REFUTED.** +24/−7 over 691 commits, and every substantive line is a *new loud wall*. Zero new arms. (But the arm count was **undercounted**: the real number is **47**.) |
| `DuckDBDialect.java:110` regexes already-rendered SQL | **GONE.** That dialect no longer exists. The two survivors in `EngineStyleH2` pattern-match the **MIR**, not text, and are Q0-fenced with the required header declaration. |

**Do not cite round 1's line numbers.** Every one has moved.

### 1.2 The meta-finding repeats, and is now at nine

Round 1 found five self-claims falsified, every one flattering. Round 2 found **four more**,
same direction:

6. `exec/Executor.java:319-321` — the midnight heuristic's docstring now calls `00:00` a
   *"self-describing wire encoding."* It is a magnitude. Renaming it does not make it a kind.
7. `harness/LineageForm.java:44-46` — claims it *"requires the VERBATIM canonical form."*
   `walkProps:129-150` is an unordered existence check on three property names.
8. `testdatagen/TestDataGenerator.java:43-45` — lists `hashStrings`, view-backed relations, and
   temporal milestoning among its **LOUD walls**. All three are *implemented*. `grep hashString`
   finds zero `NotImplementedException`.
9. `pct/.../ExecuteLegendLiteQuery.java:73` — *"All type information flows from Type on
   ExecutionResult — no SQL type inspection."* `:635` and `:645` are SQL type inspection.
   This also falsifies `exec/Column.java:5-9`, which says consumers *"(PCT, serializers, the
   QueryService bridge) convert values by this type and **never sniff SQL types**."*

Plus two stale-but-harmless: `rcorpus/Corpus.java`'s javadoc still advertises corpus-source
rewriting that has genuinely been **retired** (good news, badly labelled), and
`exec/Ddl.java:321` points at a `DuckDb.quoteCreateColumns` that now lives in `RawSqlBoundary`.

**These headers are load-bearing for reviewers.** An auditor who trusts them stops looking.
Correcting all nine is one commit and no code change.

---

## 2. The root cause: there is no `RENDER` phase

**This is the single highest-leverage finding in the audit, because one missing phase
generates an entire class of violations across four unrelated subsystems.**

`AGENTS.md:44-59` defines the pipeline as A→K, ending at
`Executor.execute → ExecutionResult`. Verified independently at HEAD:

```java
// core/src/main/java/com/legend/error/LegendCompileException.java:27
public enum Phase { PARSE, RESOLVE, NORMALIZE, MODEL, TYPE, MAPPING, LOWER, EXECUTE }
```

Eight phases. **No `RENDER`.** `ExecutionResult` has no renderer of any kind.

So "the Pure print form of a value" is implemented **five times, by parties that do not know
about each other**:

| Implementation | Owns | Consequence of divergence |
|---|---|---|
| `Scalars.floatRepr` + `DateFmt.ISO_PURE_UTC` (**in SQL**) | only when the *query* itself calls `toString()` | the correct one; almost never exercised |
| `harness/EngineTestExecutor.java:3136-3302` | the corpus's `toCSV` / `#TDS` text | `toCSV` has **no platform owner at all** |
| `pct/.../ExecuteLegendLiteQuery.formatValue:678-707` | PCT's TDS cells | fixed 3-digit subseconds; **PCT never exercises `floatRepr`** |
| `server/serial/CsvSerializer.java:78-83` | HTTP CSV | naive `value.toString()` |
| `exec/ResultJson.java:87` | HTTP/LSP JSON | `Double.toString(n.doubleValue())` — **destroys `BigDecimal`** |

Two measurable consequences today:

- **The same result serializes under different value policies depending on the format
  requested.** A `DECIMAL(38,10)` survives CSV and is destroyed by JSON.
- **The platform's own float printer is untested by all 1,109 PCT tests.** `formatValue` uses
  Java's `BigDecimal.valueOf(double)`, which is *more correct* than `floatRepr` in exactly the
  bands `floatRepr` was patched for. A regression in `floatRepr` is invisible to PCT.

`toCSV` is the purest instance: a function with **no owner anywhere in `com.legend`**, over
which **165 of 2,070 corpus test functions (8.0%) currently pass**.

> **R1 (the keystone fix). Add a result-rendering phase the platform owns.** Give
> `ExecutionResult` a renderer per format (Pure print form, CSV, TDS, JSON), and register
> `toCSV` and a `RelationType`-receiver `toString` as platform lowerings. Then delete the
> format knowledge from the harness, from PCT, and from `ResultJson`, keeping only comparison
> *policy* where policy is legitimate.
>
> Round 1 already scoped the mechanism: `Lowerer.java:2674-2688` flattens the same shape,
> `Scalars.java:2645-2660` builds a nested-`CONCAT` render, and a CSV render is `string_agg`
> over row-wise `concat_ws`. **Keeping a structural comparison does not require keeping a
> renderer** — that is the conflation to avoid.

---

## 3. The four systemic shapes

### S1 — Rewrite-then-reimplement (the dominant harness shape)

When the platform cannot evaluate a construct, the harness (a) name-matches it in the AST,
(b) **deletes or rewrites it before compilation**, (c) reimplements its semantics in Java,
(d) compares against the corpus golden.

**Step (b) is what makes step (c) invisible.** The platform never errors, so no census counts
the gap. `EngineTestExecutor` has **14 distinct rewrite sites**; `StatementExecutor` fires
**21 name-matched dispatch arms before anything reaches the Lowerer**.

The tell is dispatch style. `StatementExecutor.java:1293` does
`String simple = fn.substring(fn.lastIndexOf(':') + 1); switch (simple)` — in a file that
states the opposite rule 1,000 lines away at `:2057-2058`: *"generic natives identified by
**EXACT FQN (never suffix matching)**."*

### S2 — The type is re-derived from the value, because the type didn't survive the bridge

Four PCT sites, four harness sites, one cause. In every case `com.legend.exec` **had the
fact** — `Column.pureType()`, `Type.RelationType`'s column multiplicity,
`Type.GenericType.arguments()` — and it either wasn't plumbed to the boundary or was erased
on the way. Each re-derivation is **data-dependent**, so each turns a class of wrong platform
answers into a self-consistent green test.

`CsvSeed.java:93-107` is the same shape in reverse at ingress: it binds the resolved column
types at `:61-79`, then types each literal from **a regex on the token's text** at `:101`.
The answer is in scope; the code asks the string.

### S3 — Silent degradation to a non-failure, instrumented in exactly one channel

Three paths swallow an exception into a non-failure. **Only one is counted.**

| Path | Counted? |
|---|---|
| `H2Verify.decline` → bucketed registry → **build fails if a bucket grows** | **yes — this machinery is excellent** |
| `harness/ExecCallFinder.java:151-156` — `catch (RuntimeException \| SQLException) → null` → advisory | **no.** Gates the entire golden-SQL channel (**1,220 sites**). A renderer crash and "this test has no SQL side" are the same outcome. |
| `harness/LineageRelationsForm.java:99-102` — unrecognized arity → advisory | no |

Measured on the instrumented channel: **296 verified / 128 unverifiable — 30% advisory.**

### S4 — Java writes SQL, then regex-rewrites its own output — **deliberately**

`RawSqlBoundary`'s contract says *"text-level translation of corpus-authored statements only …
never against platform-GENERATED SQL."* Round 1 found 3 of 4 call sites violating it.
**At HEAD it is 4 of 5, three exclusively Java-generated, and one of the new ones is in the
harness.**

The loop is self-inflicted and documented as such: `Ddl.spell:323` emits H2 `FLOAT`
**on purpose**, with a comment saying so, *so that* `RawSqlBoundary.mapColumnTypes:246` can
rewrite it to `DOUBLE`.

The root sentence is `Ddl.java:15-18`: *"ONE adaptation path for hand-written and
model-derived DDL alike."* **That sentence is the bug.** Hand-written text needs adaptation
because its origin is another dialect. Model-derived text needs *nothing* — it should be
spelled correctly the first time, because the type was in hand.

---

## 4. PCT — the priority area

**Headline: the PCT adapter overwrites the database's answer with the test's declared type.**

### 4.1 The overlay

`pct/src/main/resources/core_legend_lite_pct/pct_adapter.pure:88-128` (`buildTypedHeader`)
builds a TDS header — column **names, types, and multiplicities** — from the *test's declared
return type*, and `:285-294` **replaces the first line of the TDS the database produced**.

The discarded header crossed a `ResultSet`. The replacement is the **expectation**. This makes
the schema half of every relation test a tautology.

**Demonstrated, not inferred.** From the green run's log (line 936): declared type
`Relation<(id:Float[0..1], grp:Integer[0..1], name:String[0..1])>`; the wire returned
`id:Decimal[1], grp:Integer[1], name:String[1], newCol:Float[1]`. **Core typed a Float column
as Decimal, and the test is green because the overlay pasted the declared type over it.**

| measure | value |
|---|---|
| TDS results overlaid | **405** (341 of 348 Relation + 64 Standard) |
| results with multiplicity rewritten (`[0..1]` declared vs `[1]` delivered) | **322 of 405** |
| results carrying a Decimal column | 33 |
| results carrying a date-named column typed `String` | 13 |

PCT therefore **cannot detect** (a) a wrong column type, (b) a wrong multiplicity, or (c) a
wrong column **name** — `buildTypedHeader:93-108` emits the *declared* names, so a mis-named or
mis-ordered column is silently relabeled. `getSimpleTypeName:79` falls back to `'String'` for
anything unrecognized, so no declared column type can make the overlay fail loudly.

### 4.2 The Java half

| # | Site | Verdict | Finding |
|---|---|---|---|
| P1 | `ExecuteLegendLiteQuery.java:628-636` | **VIOLATION** | Scans **every row** for a null cell to decide the column's multiplicity — an `ANY(col IS NULL)` aggregate computed in Java over `ResultSet` cells. **100 of 405 renders (154 of 1,443 columns).** A lowering that wrongly emits NULLs **widens the declared type to match itself** and still passes. |
| P2 | `:635`, `:657-676` | **COMPENSATION** | `pureTypeName(col.sqlType())` sniffs the JDBC type **name** with `default -> "String"`. **Reintroduces the exact defect core removed** — `Executor.pureOfSqlType:684-707` *throws*, its javadoc citing *"audit 15: the silent String default corrupted result typing invisibly."* `Column.pureType()` already carries the answer. |
| P3 | `:400-408` (`genericTypeOf`) | **COMPENSATION** | Pads missing type arguments with `Any` so *"the harness's cast is a legal downcast"* — a downcast that **always succeeds**. A test asserting `Pair<String,Integer>` never verifies the type argument. **The clearest "passes without the platform computing the answer" in the file.** |
| P4 | `:678-707` (`formatValue`) | **COMPENSATION** | Java reimplements Pure's datetime and float print forms. Fixed 3-digit subseconds where `DateFmt.SUBSEC_MIN` is *minimal*; and **PCT never exercises `Scalars.floatRepr`**. |
| P5 | `:541-550` | **VIOLATION** | Picks `Date` vs `StrictDate` vs `DateTime` from the cell's **text precision** (`pd.hasHour() ? … : pd.hasDay() ? …`). Fails §6-Q5(b) and (c). Names a real gap: `ExecutionResult` cannot represent a precision-bearing date. |
| P6 | `:560-573` | **VIOLATION** | Multi-element list in scalar context → `return coreInstances.get(0)`, comment: *"this shouldn't happen … but return first as fallback."* Empty case fabricates the literal string `"[]"`. Neither branch throws. |
| P7 | `:258-261`, `:244-246`, `:312-314`, `:326-329`, `:766` | **VIOLATION** | Drops null cells from collections **unconditionally, in five places**. The corpus harness already litigated this and had to **scope it by channel** (commit `d0f3a356`, after the unscoped version cost `tds/tests 248→235`). PCT ships the version already proven wrong next door. |
| P8 | `:77-113` | **COMPENSATION** | Five verbatim copies of legend-engine PCT support-function bodies, keyed by exact FQN. An **unversioned fork of third-party test source** — if upstream changes `filterValues`, lite silently keeps testing the old definition and stays green. One of the five is dead. |
| P9 | `:1110-1123` | **DEAD** | `inlineFunctionLiterals` fires **0 of 2,473 executions**. A prior commit fixed the Pure side and left the Java half behind. A live `DOTALL` regex over every query, with no test coverage. |
| P10 | `:1035-1056` | **COMPENSATION (declared)** | `remapErrorMessage` strips dialect prefixes so text matches `assertError`. **Self-declared honestly**: *"the strip erases the error CLASS, so class-confusions can compare equal."* |

### 4.3 What PCT gets right — and it is more than the rest of the tree

**Do not regress any of this while fixing the above.**

1. **There is no comparison logic in Java anywhere in the PCT module.** No normalization, no
   tolerance, no ordering-insensitivity, no sorting, no dedupe. `assertEquals` stays in
   interpreted Pure with both sides in Pure's own value domain. Row order is the database's
   (`formatAsTds:638` iterates `result.rows()` as given). **A "fix" that moved comparison into
   Java to dodge the rendering problem would be far worse than what is there.**
2. **The exclusion ledger is real, tight, and upstream-enforced.** 36 pins; a pinned test that
   starts *passing* **fails the build**; a stale pin throws; matching is `contains` on our own
   actual message. **36 of 36 are verified to also be expected failures of the official
   legend-engine DuckDB adapter** (the reference excludes 241 where we exclude 36; `comm -23`
   on the pin sets is empty).
3. **No skipping, no `pack()`, no `testFailureIgnore`, no swallowed failures.** Verified by
   running: 1,109 run, 0 failures, 0 errors, **0 skipped**.
4. **The report provider reshapes nothing** — an obvious place to cheat, and it is clean.
5. **Native-class/enum injection guards** (`:826`, `:883`) prevent shadow types.
6. **`Graph` results pass through untouched** — the JSON is built by the database.

Two honesty notes: one ledger entry blames legend-pure for what is actually our adapter's
missing `KeyExpression` arm (verified — upstream *does* print it correctly); and
`pct/pom.xml` carried `testFailureIgnore=true` until **2026-07-28**, which is when the
"PCT COMPLETE, 0 errors" claim was written. That was self-corrected and the current claim is
honest. Separately, we wire **5 of 7** upstream PCT scopes — "1109/1109" is 1,109 of **1,203**.

### 4.4 PCT fix plan

Ordered by (value of the deletion) ÷ (risk). **Steps 1–2 are free; step 3 is the real one.**

**P-Step 1 — delete the dead code (hours, zero risk, proven on 2,473 executions).**
Remove `inlineFunctionLiterals` (`:147`, `:1110-1123`) and the dead `removeDuplicates::cmp`
body (`:109-112`). Also delete or prove-reachable `createClassInstance` (`:726-793`, 68 lines,
whose `default -> "Pair"` fabricates a type for any unknown struct).

**P-Step 2 — stop sniffing SQL types (hours, low risk).**
Replace `pureTypeName(col.sqlType())` at `:635` with `col.pureType()`. This deletes a 20-line
duplicate table, restores the loud-on-unknown contract, and un-falsifies two javadocs.

**P-Step 3 — delete the null-scan and the header overlay. Expect red. The red is the point.**
Remove `:628-634` and take multiplicity from the column's Pure type; then change
`pct_adapter.pure:286-294` to **compare** the declared header against the wire header and fail
on mismatch instead of overwriting.

Run it as a *probe first*: make it compare-and-log rather than compare-and-fail, and the
mismatch list is the exact concealment inventory — every wrong type, multiplicity, and column
name PCT is currently unable to see. Adjudicate each as a real platform gap, exactly as
`SqlPostProcessors.java:64-73` did for the 7 cteExtraction tests. **Do not fix them by
re-widening the overlay.**

**P-Step 4 — make the permissive fallbacks throw.**
`:572-573` (`get(0)`), `:576`, `:674` (`default -> "String"`). Then scope the null-drop
(P7) by channel, following `d0f3a356`'s precedent, or delete it.

**P-Step 5 — route rendering through the platform (blocked on R1).**
`formatValue`'s datetime and float arms should use the platform's print forms so PCT exercises
the code the corpus ships. This is the PCT-side payoff of the `RENDER` phase.

**P-Step 6 — settle the two open questions.**
(a) Does interpreted `TestTDS` really reject `Date` columns? Its constant pool contains `Date`,
`PureDate`, `fromDate`, `DATE_TIME_AS_LONG_SENTINEL`. Probe: `stringToTDS('d:Date[1]\n2014-01-01')`.
If it builds, delete `:669-673` and the date half of `formatValue`, and dates stop being
stringly-typed through the PCT boundary.
(b) Replace the five hardcoded support functions with a general
`extractFunctionDefinitions`, mirroring the `extractClassMetadata` / `extractEnumDefinitions`
mechanism that already exists in the same file.

---

## 5. Violation ledger (outside PCT), ranked

Ranked by *(can it silently produce a wrong answer)* × *(how many results does it touch)*.

| # | Site | Verdict | Finding |
|---|---|---|---|
| **A1** | `StatementExecutor.java:2649-2651`, `:2592-2600` | **COMPENSATION** | **Java fabricates data to satisfy assertions.** `$r.activities…comment` returns a manufactured string containing `java.util.UUID.randomUUID()`; `$r.activities` folds to `[]` and a `filter` over it folds to empty **without evaluating the predicate**. The code names the asymmetry: absence-asserts **pass for the wrong reason**. **71 occurrences / 24 corpus files.** |
| **A2** | `harness/EngineTestExecutor.java:2507-2521` | **VIOLATION** | **Deletes NULL rows from a `ResultSet`-crossed collection** when the column name starts with `"u_map__"` (the platform's own synthetic map-binder column, hardcoded as a literal). `assertEquals([1,2], …)` passes when the DB returned `[1, NULL, 2]` — **arity repaired before comparison**. Its own comment records that re-scoping cost `tds/tests -13, tree -3`, so ≥16 tests depend on the exact scoping. |
| **A3** | `harness/EngineTestExecutor.java:2501-2506` → `H2Verify.java:301-323` | **VIOLATION** | `coerceTemporal` **defeats the harness's own best discipline.** `wireEquals:3368-3376` refuses to bridge "an ACTUAL that comes back as a string where the engine returns a Date … a TYPING BUG this compare must catch, never bridge." `coerceTemporal` is **side-agnostic** and runs one layer above, converting that String to a `Timestamp` before the refusal can fire. |
| **A4** | `exec/HostEval.java:244-247` | **VIOLATION** | `hostEquals` compares two `Number`s via `longValue()` when neither is a `Double`, so **`hostEquals(1.5, 1)` is `true`**. On the live assert path (`EngineTestExecutor:3316`). A decimal-vs-integer mismatch goes silently green. |
| **A5** | `testdatagen/TestDataGenerator.java:1080-1090` | **VIOLATION** | Round 1's "purest breach", confirmed: `rs.getString(i)` → **Java SHA-256** → character scrub. `sha256`, `substr`, `repeat`, `length`, `replace` are all in the DuckDB spelling table. **Blast radius: 1 verifying test.** |
| **A6** | `testdatagen/TestDataGenerator.java:1376-1393` | **VIOLATION** (new) | `seedDataString` renders cells as **Pure source** via `"'" + str + "'"` with **no `'`-doubling** — an apostrophe in the data emits broken Pure source. The correct version, `lit():1304-1330`, is in the same file and throws on unknown classes. Non-String cells fall to `String.valueOf`, so date/decimal spelling is the **JDBC driver's**. |
| **A7** | `harness/JsonAssertCanon.java:76-87` | **VIOLATION** | Sorts DB-produced rows in Java, **lexically** — `Id` 10 sorts before 2, which is not Pure `sortBy` order — and maps a missing key to `""` so those elements sort first. Both sides canonicalized **independently**. 192 corpus occurrences. |
| **A8** | `harness/HarnessSubstitution.java:69-70` | **COMPENSATION** | Implements **dynamic scoping** where the platform's `compiler/spec/SourceSubst.java:50` implements lexical. `let a = $x; let x = 5; …$a…` yields `5`. `SourceSubst`'s javadoc calls itself *"the compiler-side sibling of the harness's inliner"* — **the fork is documented and nothing binds the halves.** Also lacks the self-referential cycle guard its sibling `ExecCallFinder:60-66` explicitly added. |
| **A9** | `exec/HostEval.java:376-382` → `DbMetaData.java:89-99` | **VIOLATION** | The `executeInDb` READ path answers from a **fresh throwaway H2** reconstructed by replaying recorded statements. CSV seeds and generator inserts are **absent** from that shadow DB, and rejected statements are **skipped** (`:114-123`). It **replaced a loud refusal**. Blast radius: **1 test.** |
| **A10** | `exec/Executor.java:339-350` | **VIOLATION** | The midnight heuristic: `StrictDate` vs `DateTime` decided by `toLocalTime().equals(MIDNIGHT)`. Reads **magnitude** → fails §6-Q5(b). **Root cause confirmed: `lowering/PureSql.java:70` maps `DATE_TIME, DATE, LATEST_DATE → TIMESTAMP`.** A genuine `DateTime` at exactly `00:00:00` is silently returned as a `StrictDate`. |
| **A11** | `harness/ObjectRefs.java:88-198` | **VIOLATION** | Decodes ASOR references in Java (base64, length-segments, positional `pk$_i` → column-name remapping **by index**) while `resolver/Substitution.java:1517` already decodes them **inside SQL**. `:330-344` hard-codes an H2 connection literal where `GraphEmission.asorPrefix:3324` derives it from the model. |
| **A12** | `resolver/JsonSourceFrame.java:75`, `:109-119` | **VIOLATION** | Parses the `data:application/json` payload in Java, then `String.valueOf(v)` **erases every JSON type back to text**, which `Scalars.tdsCell` re-parses. **The DB path exists and is fully wired**: `DuckDb.java:171-185` emits `unnest(CAST(… AS JSON[]))` via `SqlSource.SourceUrl`. 27 occurrences / 5 files. |
| **A13** | `exec/Executor.java:572-619` | **VIOLATION** | Host-side UNNEST: iterates a many-valued cell and emits one `Row` per element. Loud on a *second* many-valued column, so the discipline exists — just not for the shape it does handle. |
| **A14** | `exec/CsvSeed.java:93-107` | **VIOLATION** | Types each CSV literal from **a regex on the token's text** while holding the resolved column type. The DDL side's silent-VARCHAR was fixed (`ddlType:135-136` now throws); **the value side was not**. Worse: `EngineTestExecutor.java:2732` calls `CsvSeed.sqls(csv, null, ctx)`, so `ddlType` is **never called** and the T3.1 loudness is bypassed entirely. The correct policy is 1,000 lines away in `TestDataGenerator.loadSide:1194-1226`. |
| **A15** | `exec/ResultJson.java:87`, `server/Json.java:874` | **COMPENSATION** | `Double.toString(n.doubleValue())` on every non-integral `Number`. **`server/Json` still has the exact bug `sql/Json` was hardened against** — `sql/Json.java:167-170` documents *"audit 18: two distinct Decimals beyond 17 significant digits round to the SAME double."* The fix was applied to **one of two parsers with the same name**, and `server/Json` reads every HTTP body and LSP message. |
| **A16** | `exec/Ddl.java:79-104` | **BOUNDARY-with-defect** | `createTableStatementText` quotes only H2 **reserved words**, but corpus columns contain **spaces** (`datePeriods.pure:699`: `"Previous Fiscal Week Year"`). Traced line-by-line at HEAD: it emits bare, then `quoteCreateColumns:210-234` mangles it to `"Previous" Fiscal Week Year`. **Three different identifier-quoting rules coexist; whichever runs last wins.** |
| **A17** | `exec/DynamicPivot.java:86-93` | **VIOLATION** (one arm) | `default -> new SqlExpr.StringLit(String.valueOf(v))`: a `DATE`/`DECIMAL`/`TIMESTAMP` pivot key silently becomes a **string literal** in the regenerated `IN` list. The class throws correctly for the one case it anticipated — the discipline is present, just not extended. |
| **A18** | `normalizer/` — 31 sites | **BOUNDARY-with-defect** | 31 silent `orElse(null)` model lookups against `Scalars`' 34 loud throws. Worst: `MappingNormalizer.isBitemporalClass:1507-1510` — an unresolvable class silently answers "not bitemporal", **omitting milestoning predicates from the plan**. Wrong rows, no error. Contradicts AGENTS.md common-mistake #10. |
| **A19** | `model/FromProtocol.java:727-729` | **BOUNDARY-with-defect** | `EmbeddedH2` → `LocalH2(null,null,null)`, **discarding databaseName, directory, autoServerMode**; downstream default is a *fixed* `jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1`, so every such connection shares one instance. Zero tests hit it today. The comment says *"executes like LocalH2 with a directory-backed db"* — the code does the opposite. |
| **A20** | `server/LegendHttpServer.java:162,166` / `:238,243` | **product bug** | `/engine/execute` and `/engine/sql` **double-encode** the payload: `data` is a JSON *string containing JSON*. The integration test asserts `response.body().contains("John")` — a substring check that passes **identically** for both forms. Relatedly, real streaming exists (`Executor.stream:170-235`), is correct, and **no HTTP route uses it**. |
| **A21** | `GroupByCheckerTest:1428`, `JoinCheckerTest:1008`, `AsOfJoinCheckerTest:799` (+ 48 of 80 `rows().stream()` sites) | **VIOLATION** | Three copy-pasted `collectResults` build a `HashMap` keyed by a DB cell (`map.put` silently overwrites) — **68 call sites**. Plus Java `sorted`/`filter`/`count`/`distinct` over `ResultSet` cells. **Row order erased by default, so no `ORDER BY` bug is catchable here.** See §7.3 for the gated version 200m away. |
| **A22** | `nlq/NlqService.java:273-291` | **BOUNDARY-with-defect** | An LLM-generated query is accepted on `Compiler.parseQuery` — **syntax only**. `Compiler.compileQuery` exists at `Compiler.java:669` and needs no runtime. A query naming a nonexistent property, or `sum()` on a String, is reported `success: true`; the retry loop can never learn about type errors. Every "acceptable routing ≥ 70%" figure is over never-type-checked queries. Compounding: `NlqEvalMetrics:265-272`'s sort-coverage is a **tautology** (always 1.0 for any query containing any `sort()`), and judge failures score all-zero and are then **filtered out** of the average, so it can be computed over 1 of 25 cases and still pass. |

### 5.1 Measurement findings — the scoreboard cannot see its own softness

Not violations, but they determine whether any of the above is *visible*.

- **The corpus scoreboard prints only non-passing rows** (`Runner.java:2365-2382`), so **every
  soft pass is structurally unmeasurable.** `score()` has a **four-rung softening gradient**:
  real failure → FAIL, SQL-only divergence → FAIL, advisory-only → SHAPE, **assert-free-but-executed
  → PASS**. Each rung is individually argued in a comment; `writeScoreboard` renders none of them.
- Against 2,575 runnable / 2,301 PASS: **244 passes (10.6%) carry an unverified golden-SQL
  divergence**; **≤33** can be hollow "0 asserts" passes; **≥296** asserts are verified through
  an H2 oracle that **discards row order unconditionally** and rounds floats to 10 significant
  digits. The oracle also **rescues** text-divergent tests into row-verified PASS, and that
  rescue is **never recorded** — so the true SQL-divergence rate is `244 + an uncounted set`.
- The 244 are produced by matching the **prefix of a failure message**
  (`failure.startsWith("sql-text: ")`) — a protocol carried in human-readable error text.
- `LL_ORD_COUNT`, the harness's own order-leniency instrument, is wired into **2 of 6**
  leniency paths. Any "we measured our order tolerance" claim is a floor, not a count.
- The regression gate compares **per-family pass counts**, so one test going PASS→FAIL while a
  sibling goes FAIL→PASS is invisible; and `readBaseline` failing open still permits the write.

### 5.2 Absent primitives

- **Zero transaction control, exactly.** `grep -riE "setautocommit|\.commit\(\)|\.rollback\(\)|savepoint"`
  over the repo returns **one hit**, and it is a reserved-word string list in `Lexicon.java:57`.
  That absence *forced* the invention of `rawSqlFailureSink`, per-statement tolerate-and-continue,
  `RawSqlBoundary.unrecordLast()`, `Runner.failedSeeds`, and `emptinessUnverifiable` — machinery
  whose function is to **downgrade a red assertion to advisory**.
- **No driver bounds anywhere.** Zero `setFetchSize` / `setMaxRows` / `setQueryTimeout` in
  `core/src/main`. (Round 1's "always drains" half is now **false** — `Executor.stream` is a
  genuine lazy path — but nothing bounds the driver.)
- **N+1 seeding in four places**, plus the effectful `map` arm which materializes a collection,
  calls `executeTyped` per element, and **keeps only the last result**.

---

## 6. What is genuinely good — do not regress it

A naive remediation would destroy several of the best things in this codebase.

**Architecture**
- `Scalars.Rule`'s `SqlExpr` return type — the tenet enforced by javac in the hottest file.
- `StaticFold` frozen for 691 commits, two gated call sites.
- `resolver/` with zero `java.sql` imports.
- `exec/SqlPostProcessors.java:43-273` — **the convergence target.** Structural IR rewrite,
  four loud walls, a `SqlSource` switch **total by construction**. It got *better*: `:58-76`
  now throws where it used to catch-and-skip, and the comment names the false green it fixed
  (*"the cteExtraction corpus tests were 'passing' with the very feature they test skipped"*).
- `LiteralFold`'s two-condition admission rule, differentially pinned, explicitly refusing to
  widen for performance — the right answer to "when may Java answer without the DB."
- `ConnectionSectionGrammar` — 2,155 lines, **36 `default ->` arms, all 36 throw.**
- `TestDataGenerator.fetchRoot`/`fetchChild` — the whole tree walk is DB-resident; children
  join against the **parent's temp table**; dedup is `UNION`; `compareCsv` diffs with `EXCEPT`
  both ways. Any A5/A6 fix must preserve this.
- `DynamicPivot`'s placement: two-phase execution at the **execution seam**, never inside
  rendering, rewriting IR via `SqlRewriter`.
- `ide/package-info.java` carries the best Q0 declaration in the tree — and it **holds**
  (five hits outside `ide/`, all javadoc, zero imports).

**Discipline**
- `Scalars.pureToString:2787-2791` choosing a loud `NotImplementedException` over a
  working-but-wrong Cast, citing the tenet. **Do not "fix" this by adding a cast.**
- The one-directional wire bridges that **refuse the symmetric grant**, with the reason stated
  (`EngineTestExecutor:3318-3324`, `:3368-3376`). *(But see A3 — one is bypassed upstream.)*
- `H2Verify.decline` + `bucketOf` + the registry that **fails the build when a blind spot
  grows.** Generalize this to `ExecCallFinder`; do not remove it.
- `LL_TOL_COUNT` / `LL_ORD_COUNT` — a harness that counts its own leniency.
- `scoreAssert`'s `UNSUPPORTED_REASON` attribution, which fixed **82 of 95 SHAPE rows** that
  read as harness gaps when they were platform walls.
- `LineageRelationsForm:29-32` — ~half a family's greens **deleted** because they were
  manufactured, deliberately *before* the real implementation landed, "so its build starts honest."
- `ProtocolEmitter.require:3345-3352`: *"Silent omission is how a byte-identity claim becomes a
  lie that every structural comparison still passes."*
- **The parser byte lane** — `Comparators.sameBytes` on raw protocol JSON with **no**
  normalization of any compared byte; `ComparatorSelfTest` (a test the testers can fail);
  `CorpusManifestTest`'s SHA pin of all 8,891 sources; and `docDiffs==0 && weRefuse==0` chosen
  as an **identity** rather than a floor. The best-enforced claim in the tree.
- `CorpusSweepTest:123-127` choosing a corpus-size **assert** over an `Assumptions` skip, with
  "an absent corpus skips GREEN" written down as the reason.
- `FixtureCorpusParityTest`'s pardon list — 13 rows with a **stale-row assert** and **total
  accounting**. Every other allowlist in the tree should be rebuilt to this shape.
- `NoEagerUserClassLoadsTest`'s **anti-vacuity check** (`:108-112`) — a guard that verifies it
  is actually guarding something.
- `tools/allgates.sh` — the most hazard-aware file in the repo. Every defensive construct cites
  the incident that motivated it, including a rename-goes-red loop that verifies each of 18
  named classes actually ran.
- `scripts/corpus/` **refuses rather than guesses** (fanout on to-many, NULL sort keys, ties
  across `limit`), and it caught a real *upstream* defect — 6 legend-engine services where
  `count()` over an empty to-many returns 1, with a minimised repro filed.
- `experiments/backend-probes/` — asks each database what it does and records the answer with
  no expectation; prefixes values with their Java class "so type surprises are visible, not
  hidden"; treats SQLite's missing DATE type as "itself a finding, not a workaround."
- `nlq/`'s **total absence of a compensating fallback** — every failure path throws or returns
  an error; no code path hand-constructs a Pure query when the LLM fails.
- `Ddl.spell`'s exhaustive switch — *"EXPLICIT so a new variant is a compile error, not a
  runtime surprise (T3.1)."* Only the deliberately-wrong `Float_` arm is the problem.
- `ExecCallFinder`'s `through` restriction — a refusal to resolve past a transform, invented
  locally, required by nothing.
- PCT's ledger, and PCT's total absence of Java-side comparison (§4.3).

---

## 7. The enforcement gap — enforced vs. merely claimed

**Nothing in this codebase enforces this tenet. Not one test, anywhere, constrains where a
`ResultSet` may be consumed or what Java may compute from one.** That is why every finding in
this document was reachable by reading and none by running.

The mechanism is precise: `ArchitectureTest`'s 24 rules are **all** dependency-direction, and
every allowlist in them ends in `"java.."` (`:225, :294, :322, :345, :375, :469, :488`) —
which **includes `java.sql`**. `com.legend.lowering` could import `java.sql.ResultSet`
tomorrow and the build stays green.

**The proof that this matters: round 1's own worked calibration example
(`TestDataGenerator`'s `hashString` over `rs.getString`) is still live and unfixed at HEAD,
691 commits after it was named** — precisely because no rule forbids it.

### 7.1 The table

| # | Claim | Enforced? | Evidence at HEAD |
|---|---|---|---|
| **T1** | "Java orchestrates; the DATABASE executes. No host interpreter." | **NO — nothing, anywhere** | Only 5 tests scan `src/main`; none mentions `java.sql`, `ResultSet`, or value computation. JDBC lives in `harness`(5), `exec`(3), `server`(2), `testdatagen`(1) + root — **a funnel rule would cost ~5 exemptions and does not exist.** |
| **T2** | "Harness compensation is the cardinal sin" | **NO** | `com.legend.harness` = **7,019 lines in `src/main`**, appearing in `ArchitectureTest` at `:133, :141, :158, :504` — **all four exemptions**. The only rule touching it anywhere is `ErrorShapeGuardrailTest:70`, an error-*shape* ratchet, not computation. |
| **T3** | `HostEval` "orchestration values only; the DB-executes tenet governs QUERY values" | **NO** | `HostEvalTest` (118 lines) tests `evalToResult` on *synthetic* `TypedSpec` and **never exercises the routing predicate** that gates 894 lines of interpreter. `grep wantsHostEval core/src/test` → zero hits. That predicate has **collapsed the sweep twice** (2096→408, 2091→2013, two different mechanisms). |
| **T4** | `TestDataGenerator`: "Walls are LOUD … hashStrings …" | **FALSE, in the same file** | `:1084-1086`. See A5. |
| **T5** | `Column`: consumers "convert values by this type and **never sniff SQL types**" | **FALSE** | Its primary consumer, `ExecuteLegendLiteQuery.java:635`. See P2. |
| **T6** | Invariant 5 lazy loading: *"Core has **no** lazy-loading enforcement at all"* | **FALSE — it IS enforced** | `NoEagerUserClassLoadsTest.java:63-113` is live — a fail-fast `ModelContext` proxy over 4 cold FQNs × 3 query shapes, **with an anti-vacuity check at `:108-112`**. `NoEagerTypeReferencesTest` likewise. **AGENTS.md understates its own rigour.** |
| **T7/T8/T9** | Invariants 8, 7, 3a marked `[ENFORCED]` | **YES**, all three line-exact | `ArchitectureTest:50`, `:66`; `StoreResolver:177,220` + `StoreResolverTest:105`; `ArchitectureTest:218`. |
| **T10** | Invariants 1, 2, 3, 4, 6 marked `[CONVENTION]` | **honest, but understated** | Invariant 4 is *partly* enforced by `ErrorShapeGuardrailTest`'s ratchets. Invariant 2's "must not import a dialect" holds in fact, but `loweringDependencySurfaceIsPinned:486` explicitly **allows** `com.legend.sql..`, which includes `sql.dialect` — which is how F1's channel leak got in. |
| **T13** | `CodeShapeGuardrailTest` "build-failing, not aspirational" | **YES, and it covers the harness** | 250-line method cap with an **empty** allowlist. Latent gap: `SIG` anchors on exactly 4 spaces, so nested-class methods are unscanned — the 8-space variant finds **0 offenders today**. |
| **T14** | Scoreboard: "row equality is the contract, golden SQL is advisory" | **YES, honestly** | sqldiff gets its own column (244), SHAPE its own bucket (93), `verified==0 && sqlDiffs` → FAIL. |
| **T15** | `CorpusDifferentialTest` is "the third assertion mechanism… cannot be wrong about Legend semantics" | **NO — it never runs** | `Assumptions.assumeTrue(...target/diff/expected)` at `:38`; that directory does not exist, and `differential.py` is in **neither** `allgates.sh` **nor** `GATES.md`. **The most tenet-honest verifier in the tree is dark in every build.** |
| **T16** | Parser byte-parity "100% / 6489 docs" | **YES — the best-enforced claim in the tree** | `docDiffs==0 && weRefuse==0` forces `docsMatched == oracleAccepts` — a real identity, not a floor. Backed by `ComparatorSelfTest` (a test the testers can fail) and an 8,891-source SHA pin. |
| **T18** | Grammar-coverage ratchets | **NO — outside the gate** | `allgates.sh:176` names 18 classes; **11 parser-equivalence classes with real assertions never run**, including all four census tests. |
| **T19** | NLQ reports `success: true` for a validated query | **NO** | Validation is `Compiler.parseQuery` — **syntax only**. `Compiler.compileQuery` exists, needs no runtime, and is never called. A query naming a nonexistent property is reported successful. |
| **T20** | `EngineSectionRosterTest`: "A pull that ADDS one should widen the census, **not pass in silence**" | **FALSE** | `:74` is `assertTrue(size >= MIN_SECTIONS)` — growth passes in exactly that silence. |
| **T21** | 20 `@Disabled("GAP: …")` platform gaps | **invisible to every scoreboard** | None appears in `docs/OUTSTANDING.md`. **At least two are stale** — `"GAP: XStore not in grammar"` and `"GAP: AggregationAware not in grammar"` are both implemented at HEAD, and `aggregationAware/test/rewrite` scores **13/13 pass**. |

### 7.2 A cultural correction worth recording

In the `parser-equivalence` / `nlq` / tooling scope, **where docs and code disagree the docs are
now mostly *pessimistic*, not flattering** — the reverse of §1.2's pattern. `AGENTS.md`
disclaims lazy-loading enforcement it actually has (T6); `GATES.md` cites parser ceilings of
22/181/742 that do not exist (the real ones are **0 and 2**, i.e. far stricter); the
grammar-coverage doc narrates its own headline falling 49.3% → 37.0% as the denominator grew.
That auditor found **zero flattering claims** in its scope. The nine in §1.2 are concentrated
in `exec/`, `harness/`, `testdatagen/`, and `pct/` — which is exactly where the violations are.

### 7.3 The discipline exists; it is simply not required of anyone

`EngineTestExecutor.compare:2775-2806` gates every unordered comparison on
`ordered && actual.sortedChain()` — and **`sortedChain()` is a compile-time fact about the
query, not about the data**, so the typed-fact test passes cleanly.

Two hundred metres away, the hand-written integration tests do the identical Java sort with
**no gate at all**:

- `collectResults` — three copy-pasted implementations building a `HashMap` keyed by a DB cell,
  where `map.put` silently overwrites — **68 call sites** across `GroupByCheckerTest`,
  `JoinCheckerTest`, `AsOfJoinCheckerTest`. Row order is erased entirely, so no `ORDER BY` bug
  can ever be caught there.
- **48 of 80** `rows().stream()` sites end in `sorted` / `filter` / `count` / `distinct` —
  Java aggregates over `ResultSet` cells, where the Pure query could have expressed each one.
- Three tests are **green and cannot fail**: `DuckDBVariantLoadTest` (1 `@Test`, **0
  assertions**, 20 `catch (Exception) { println }`), `DuckDBUnnestSyntaxTest` (2 `@Test`, 0
  assertions), and `ProbeWireShapes` (an **893-line `@Test` with zero assertions**). These are
  probes filed as tests, inflating the green count. `experiments/backend-probes/` shows how to
  do this correctly and is exemplary.

**This is the whole finding in miniature.** Every good property in this codebase —
`wireEquals`' kind strictness, `sortedChain()` gating, `emptinessUnverifiable`, `sameBytes`,
`NoEagerUserClassLoadsTest`'s anti-vacuity check, `allgates.sh`'s ran-verification — is a
hand-authored decision in one file, and **not one of them is required of the next file.**

### 7.4 Other gate holes

- `docs/GATES.md` is candid that **CI runs gates 1/2/4 only, gate 4 skips**, `allgates.sh` has
  no `set -e` and always exits 0, and missing checkouts skip-not-fail.
- Nothing catches unused private methods — ~89 dead lines in `EngineTestExecutor` alone.
- `scripts/outstanding.py:14` and `scripts/walldepth.py:7` hardcode
  `REPO = "/Users/neema/legend/legend-lite"` — **another account's checkout**, which exists on
  this machine. `docs/RUNNABILITY_PLAN.md:129-133,184` derives its entire re-forecast from
  their artifacts, so **those numbers cannot be reproduced from this checkout.**
  `census_gate.py:33` already uses the correct relative pattern.
- `tools/scoreboard.py` still points at the deleted `engine/` module; invoked with `--record`
  it appends **a row of all zeros** to `docs/SCOREBOARD.md` as a legitimate run.

> **R2 — the guards that would have caught this audit.** Each is cheap and each pins a finding
> above. **#1 is the one that turns T1 from unenforced into structurally enforced.**
>
> 1. **Funnel `java.sql.*` to `{exec, server, com.legend (root), harness, testdatagen}`.**
>    Costs ~5 exemptions today. It is the only rule that makes the tenet mechanical rather than
>    cultural, and it would have caught A5 — round 1's own worked example — 691 commits ago.
> 2. **`com.legend.harness` may not be depended on by production code**, and **move it to
>    `src/test/java`.** 7,019 lines currently ship in the production jar with zero production
>    consumers. This deletes four ArchUnit carve-outs and makes the cardinal sin structurally
>    unreachable.
> 3. **An invariant on the `hostChannel` predicate** — no `ResultSet`-derived value may reach
>    `HostEval.eval()`. A charter listing forbidden *arms* would not have caught A9; only this
>    would. (See §8 for why.)
> 4. **A positive rule on the harness**, not just exemptions. Natural first pin: no
>    `Collections.sort` / `stream().sorted()` over an `ExecutionResult` outside a declared,
>    `sortedChain()`-gated site — the discipline `EngineTestExecutor.compare` already applies
>    to itself (§7.3).
> 5. **`com.legend.lowering` may not import `com.legend.sql.dialect`** — pins F1's channel
>    leak. Note `loweringDependencySurfaceIsPinned:486` currently *allows* it.
> 6. **The R0 rule made real**: `RawSqlBoundary` may only be called with corpus-authored text.
> 7. **Count the uncounted decline** (`ExecCallFinder:151-156`) into `H2Verify`'s bucket
>    registry — the one place this project already does this correctly.
> 8. **Render the soft-pass columns** in the scoreboard (`advisory`, `0-asserts`, `rescued`).
>    One render change, no semantic change — round 1's C0.2 finished properly.
> 9. **Wire the orphans into the gate**: the 11 parser-equivalence classes (their ratchets
>    already exist) and `CorpusDifferentialTest` — or delete the latter's claim to be "the third
>    assertion mechanism."

### 7.5 Pardon channels keyed on a string

A recurring shape worth naming on its own, because each instance looks locally reasonable:
**where a claim cannot be measured, it gets classified by matching text.**

| Channel | Keyed on | Capacity |
|---|---|---|
| `EngineTestExecutor:900-904` | `failure.startsWith("sql-text: ")` | the 244 sqldiff-passes |
| `CorpusSweepTest:278-288` (`msgRicher`) | a 6-string list of oracle messages | **1,277 rows** — ~53% of both-reject rows never have their message compared, and `classify()` in the *same file* reads two of those strings the opposite way |
| `OwnCorpusConformanceTest:93-134` (`liteDesign`) | `contains("native function")`, `contains("⊆")`, … **in our own source text** | re-introduces exactly the circularity `CorpusSweepTest:548-555` de-circularized ("OUR parser accepting is NOT evidence") |
| `OwnDialectCensusTest:37-67` | **file names** (11 + 12 hosts; `ElementParserTest.java`, 3,483 lines, is in both) | unbounded per host |
| `FixtureAdjudicationTest:100-108` | normalized message *kind*, ceiling 21 | **unbounded fixtures** — 268 lenient fixtures across 11 families under 21 kinds; a new fixture in an existing kind costs 0 |
| `ExecuteLegendLiteQuery:1035-1056` | a dialect error prefix | ≤94 `assertError` sites |
| `NlqEvalMetrics:189-259` | `String.contains` on query text | every NLQ coverage metric |

**196 named pardon rows are live** across the parser-equivalence ledgers. The model to rebuild
them all on is in the same module: `FixtureCorpusParityTest`'s pardon list — 13 rows, with a
**stale-row assert** and **total accounting** (`:128-133`). `model-refuse-allowlist.tsv` (71
rows) has no stale-row check and at least one provably dead row.

---

## 8. Sequencing

**Do V0 first: correct the nine falsified self-claims (§1.2) and write the two missing
charter clauses.** One commit, no code change. Round 1's V0.2 (model-space vs data-space) and
V0.6 (the host channel's charter) are **still unwritten**, and they are the reason A9 was
adjudicable only by hand.

But write the charter differently than round 1 proposed. Round 1 worried the channel would
*grow arms*; it did not. **The real mechanism is that one dispatch edge silently reclassified
all 47 existing arms.** 18 of them (`fold`, `map`, `concatenate`, `at`, `size`, `eq`, `in`,
`slice`, `filter`…) are *dual-use* — they are whichever the value flowing through them happens
to be. A ~6-line commit wired a `ResultSet` into the bottom of the chain and moved an entire
interpreter from compilation into execution. **Nothing had to be added.** The charter must
forbid a `ResultSet`-derived value from reaching `eval()`, not enumerate arms.

Then, by falling severity:

1. **A1** — stop fabricating UUIDs and empty activity collections. Let the reads fail with a
   named `NotImplementedException("execution activities are not recorded")` and adjudicate the
   71 reads as blocked-on-feature, exactly as `SqlPostProcessors:64-73` did for cteExtraction.
   **This is the one step that turns currently-green tests red. That is its purpose.**
2. **PCT P-Steps 1–4** (§4.4) — two are free deletions; P-Step 3's probe produces the
   concealment inventory.
3. **A2, A3, A4** — three live wrong-answer mechanisms on assert paths. A4 is a one-line fix.
4. **A5, A6** — give `TestDataGenerator`'s fetch SELECT an **expression channel** (per-column
   `SqlExpr` instead of `String`). One capability; both violations dissolve; the temp-table
   machinery is untouched.
5. **S4's flagship instance** — make `Ddl.spell` emit `DOUBLE`/`BOOLEAN` directly, delete
   `RawSqlBoundary.mapColumnTypes` and its three `Pattern` fields, route Java-generated DDL to
   `Executor.executeRaw` directly (as `dropAndCreateSchemaInDb:3316` already does). **Then
   `RawSqlBoundary`'s stated contract becomes true for the first time.** Unify the three
   identifier-quoting rules (A16).
6. **R2's guard rules** — cheap, and they stop everything above from regressing.
7. **A15** — apply the audit-18 `BigDecimal` fix to `server/Json`; collapse the five JSON
   readers and four writers toward one owner.
8. **Transactions (§5.2)** — wrap each seed unit in `setAutoCommit(false)`…`commit()`/`rollback()`,
   then attempt to **delete** `emptinessUnverifiable`, `rawSqlFailureSink`'s tolerate-and-continue
   arms, `unrecordLast()`, and `failedSeeds`. Whatever refuses to delete is a real gap that must
   then be **named**. The highest-leverage deletion available anywhere in the tree.
9. **R1, the `RENDER` phase** — the keystone. Retires `toCSV`'s homelessness, the harness CSV
   renderer, PCT's `formatValue`, and the three-way serializer divergence together.
10. **A7–A14, A17–A20, A18's 31 defaults** — the long tail.

**Two sequencing warnings.**

*Do not batch the PCT overlay removal with the `RENDER` phase.* P-Step 3 is a probe that
produces a defect inventory; R1 is a design change. Coupling them stalls the inventory behind
the design.

*Do not measure progress by the size of `EngineTestExecutor.java`.* It changed by +507/−490
while the `harness` package grew **+2,834/−645 across 16 files, 9 of them new**. The
compensation did not shrink; it **moved outward**, and this file became its dispatcher.
Shrinking it would measure progress that did not happen.

---

## 9. Hypotheses this round refuted — do not re-open

- **`HostEval` growing one arm per wall.** +24/−7 over 691 commits; every substantive line is
  a new loud wall. The growth alarm is dead; the *reclassification* risk is not (§8).
- **`MetamodelWalk` crossing into data-space.** No `java.sql`, no `Connection`, no `ResultSet`
  in 1,575 lines. Its decimal-lattice arithmetic operates on *declared* column precision —
  model-space, the engine's own `inferRelationalType` lattice. (Its "every unrecognized shape
  returns null" header is accurate about itself but relocates loudness to callers, and
  `StatementExecutor.walkProp:1822-1833` **drops** null-walking elements from a collection.)
- **`GraphEmission` touching a value.** +1,036 lines of churn and still typed→typed; zero
  `BigDecimal`/`Double.`/`compareTo`/`Collections.sort` in 3,461 lines.
- **The protocol emitters duplicating the Lowerer or the dialect.** They are write-only; nothing
  parses that JSON back; they contain no SQL and no MIR reference.
- **`TailEmitter` emitting a query tail.** "Tail" means *tail sections* (Service, Persistence,
  DataSpace…). It re-implements nothing.
- **`JsonAssertCanon` being a fifth JSON parser.** It contains zero parsing code.
- **`H2ExtensionFunctions` being Java UDFs replacing lowerings.** All four functions *do* lower
  to SQL in the platform; these exist only to make the **engine's** golden text executable on
  the oracle. (But `aliases()` is called from the fresh-replay branch only, so on the default
  DuckDB-sweep mirror path they are not registered.)
- **`server/QueryService` post-processing results.** §6-Q4 does not fire anywhere in `server/`.
  No reshape, no aggregate, no filter, no sort, no paginate-by-slicing.
- **The `TestDataGenForm` CSV round trip being a violation.** The CSV genuinely **is** the
  product — `assertTestData(<golden csv>, $testData.dataCsvString, $db)` appears 61 times and
  the comparison runs in SQL. *(The real finding on that leg is that
  `loadAndTestExecution`'s only terminal assertion is `rows->isNotEmpty()`, so the lossy
  cell scrub could corrupt every reseeded value undetected.)*
- **`scripts/corpus/oracle.py` computing expectations in Python.** It is a *test oracle*, not
  compensation: it does not repair the platform, it judges it — and `LITE_QUARANTINE` is
  **empty**, i.e. legend-lite agrees with it on all 21 services. Under the deletion test,
  removing it removes the assertion, not a platform answer. **Do not "fix" this.**
- **`nlq/` executing anything.** Zero `ResultSet`/`Connection`/`jdbc` in `nlq/src`. It
  structurally *cannot* execute — all eight fixture models are class-and-association-only,
  with no `Database`, `Mapping`, `Runtime`, or rows anywhere. `SemanticIndex`'s TF-IDF and
  Jaccard scoring run entirely over class names, property names, and `doc.doc` — model-space.
- **`parser-equivalence/` normalizing the bytes it compares.** `sameBytes` is `a.equals(b)` on
  raw protocol JSON — no sorting, no whitespace collapse, no position stripping, no case
  folding. `sourceInformation` is *inside* the compared payload. (Its five verdict-gating
  normalizers all sit in the *message* lane, not the byte lane — see §7.5.)
- **`H2ExtensionFunctions`, `JsonAssertCanon`-as-a-parser, `server/QueryService`
  post-processing, `MetamodelWalk` in data-space, `GraphEmission` touching a value, the
  emitters duplicating the Lowerer** — each specifically suspected and specifically disproved
  above.
