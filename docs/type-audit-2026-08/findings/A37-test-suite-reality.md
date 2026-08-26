# A37 — TEST SUITE REALITY: what 4,278 green tests actually catch

Auditor slug: `A37-test-suite-reality`. Everything below is either a quoted `file:LINE` from
sources I read in full, or output of code I ran and pasted verbatim.

**Working tree**: `/home/user/testrun` (a `cp -a` of `/home/user/legend-lite`, per the task).
Maven was run ONLY there. `/home/user/legend-lite` was read-only; probes ran via
`/home/user/probe/jrun.sh`, which compiles to a `mktemp -d` and never touches `core/target`.

---

## 1. THE SUITE RESULT — it is GREEN

Command: `cd /home/user/testrun && mvn -pl core test -B`

```
[INFO] Results:
[WARNING] Tests run: 4278, Failures: 0, Errors: 0, Skipped: 16
[INFO] BUILD SUCCESS
[INFO] Total time:  04:51 min
```

| metric | value |
|---|---|
| tests run | **4278** |
| failures | **0** |
| errors | **0** |
| skipped | **16** |
| wall time | **4 min 51 s** total (≈2 min of that is `javac` on 543 main + 240 test sources; the sum of per-class surefire `Time elapsed` is **210.7 s**) |
| test classes executed | 484 (from 240 `.java` files — `@Nested` classes) |
| `@Test`-annotated methods in source | 4171 (+4 `@ParameterizedTest`) |

Slowest classes: `TypeInferenceIntegrationTest` 15.2 s, `RelationalMappingIntegrationTest` 12.7 s,
`ArchitectureTest` 10.3 s, `DuckDBIntegrationTest` 10.1 s.

The repo is **not** already red. The suite is green, fast, and large.

### 1a. What `mvn -pl core test` silently does NOT run

- **`<excludedGroups>heavy</excludedGroups>`** — `core/pom.xml:88-94`. Two files carry `@Tag("heavy")`:
  `integration/StressTestChaotic.java:36` and `integration/ProfileBuildCost.java:13`. Both are
  excluded by default. (`StressTestChaotic` is the ONLY place in the repo that pairs Pure types
  with SQL column types — `TYPE_MAP` at `StressTestChaotic.java:72-82` — and every pair it uses is
  a CORRECT pair, so even when run it cannot see V26.)
- **The whole `com.legend.rcorpus` package never runs.** `RelationalCorpusRunner`, `Runner`, and
  `Corpus` are not named `*Test`, so surefire's default includes skip them. Confirmed: the log has
  zero `Running com.legend.rcorpus.*` lines. They are reached only by `tools/allgates.sh` GATE4/5,
  which invoke `mvn -pl core test -Dtest=RelationalCorpusRunner` explicitly and which
  `roots_present()` refuses to run without a legend-engine checkout at `$HOME/legend/legend-engine`
  (absent on this box — the checkout here is at `/home/user/finos/legend-engine`).
- **The PCT conformance suites are a different module** (`pct/`), reached only by GATE6/7/9.
  `tools/allgates.sh:154` ledgers GATE7 at `G7_MIN_RUN=348; G7_MAX_FAIL=1; G7_MAX_ERR=22` — a
  standing allowance of **1 failure and 22 errors**.

---

## 2. WHAT IS NOT RUN — skips, ratchets, ledgers

### 2a. The 16 skips (exact, from `target/surefire-reports/*.xml`)

15 are `@Disabled("GAP: …")` rows, all in `integration/RelationalMappingIntegrationTest`:

| nested class | test | reason |
|---|---|---|
| GapFeatures | testMappingExtends | GAP: extends clause ignored by builder |
| GapFeatures | testScopeBlock | GAP: scope keyword in lexer but no grammar rule |
| GapFeatures | testStoreSubstitution | GAP: store substitution not visited by builder |
| GapFeatures | testLocalProperty | GAP: Local property + prefix semantics lost |
| GapFeatures | testDatabaseFilter | GAP: Database filters not extracted |
| GapFeatures | testRelationClassMapping | GAP: Relation class mapping not in grammar |
| GapComposition | testLocalPropertyWithJoin | GAP: Local property + join + filter |
| GapComposition | testViewJoinFilter | GAP: View + join + filter |
| GapComposition | testStoreSubstitutionQuery | GAP: Store substitution + query |
| GapComposition | testScopeEmbeddedFilter | GAP: Scope block + embedded + filter |
| GapComposition | testAggAwareWithJoin | GAP: AggregationAware + join |
| GapComposition | testSetIdFilter | GAP: Set IDs + filter disambiguation |
| GapComposition | testDbFilterPlusMappingFilter | GAP: Database filter + mapping filter stacking |
| GapComposition | testIncludeWithJoin | GAP: Mapping include + join navigation |
| GapComposition | testExtendsWithFilter | GAP: Extends + filter inheritance |

The 16th is `integration/CorpusDifferentialTest.differential`, an `Assumptions.assumeTrue` on the
`expected/` oracle directory existing (`CorpusDifferentialTest.java:43`).

`Assumptions.assume*` sites in `core/src/test`: **2 files only** —
`rcorpus/RelationalCorpusRunner.java:55` (`Corpus.available()`, and that class never runs anyway)
and `CorpusDifferentialTest.java:43`. The other 8 registered assumption files live in
`parser-equivalence/`.

**The skips are themselves ratcheted.** `SkipCensusTest` (`core/src/test/java/com/legend/SkipCensusTest.java`)
pins `RelationalMappingIntegrationTest.java → 15` max `@Disabled` rows, requires every reason to start
`"GAP: "`, and pins the assumption-skipping FILE SET exactly (10 names). This is the healthiest
instrument I found in the suite: a skip cannot be added silently.

### 2b. The ratchet / ledger / register inventory

Every one of these is a **code-shape guard**: it reads the repo's own `.java` source with
`Files.walk` + a regex and asserts counts or file sets. **None of them asserts anything about
typing, values, or SQL semantics.** 35 of the 4173 test methods (0.8%) are of this kind, across 21 files.

| file | what it pins | entries held |
|---|---|---|
| `SqlTextRatchetTest.java` | SQL built as string TEXT outside `sql/dialect/` — per-file exact counts | **7 files / 37 sites**: StatementExecutor 2, exec/CsvSeed 4, compiler/spec/CatalogGrids 9, exec/Ddl 3, plan/InProtocol 1, plan/PlanText 2, testdatagen/TestDataGenerator 16. Coverage floor: ≥250 production files scanned. |
| `JavaEvalLedgerTest.java` | the "Java orchestrates, the DATABASE executes" tenet, as stripped-line-count caps | **13 SIZE rows** (ExecuteLegendLiteQuery 850, MetamodelWalk 1307, MetamodelSteps 196, PlanText 750, AggAwareActivities 225, StoreNav 199, DynamicPivot 118, GridProbe 52, PureAsserts 311, AssertVerdicts 829, StatementExecutor 2423, JsonCompare 70, GridCompare 295) + **6 NAME rows** (regex site counts, 5 of them pinned at 0) + **3 closed package registers** (exec = 26 classes, server = 7, testdatagen = 1) |
| `TenetRatchetTest.java` | JDBC value-accessor (`.getXxx(`) call sites in `src/main` | **1 number: ≤ 13**. Coverage floor: 498 files scanned. |
| `CarrierPurityRatchetTest.java` | backend-data-model idioms in lowering/resolver/plan | **5 patterns**: `new SqlExpr.ArrayLit(` ≤41, `new SqlExpr.OrderedListAgg(` ≤1, `SqlFn.LIST_` ≤134, `SqlFn.UNNEST` ≤12, `new SqlAgg.Reducer("LIST"` ≤0. Floor: 74 files. |
| `RawSqlLedgerTest.java` | `RawSqlBoundary.h2ToDuckDb` callers, and `new SqlSource.RawSql(` sites | **2 caller entries** (StatementExecutor 1, RawSqlAdapt 1) + **3 ctor entries** (GridProbe 1, Lowerer 1, RawSqlAdapt 1) — both asserted with `assertEquals`, i.e. EXACT both directions |
| `JdbcSurfaceCensusTest.java` | which files may touch JDBC | **13 main + 129 test** file names; floor 778 files |
| `ErrorShapeGuardrailTest.java` | swallowed-failure shapes | **18 broad-catch rows** + `CATCH_RETURNS_VALUE ≤15` + `ENDS_WITH_FQN ≤18` + `DEFAULT_LITERAL_FALLBACKS ≤5` |
| `ObservabilityGuardrailTest.java` | env flags, `STDERR_PRINTS ≤34`, `STRING_DISPATCH_SITES ≤87` | 3 numbers + an env-flag set |
| `CodeShapeGuardrailTest.java` | method ≤250 lines, file ≤3500 lines, `DEAD_PRIVATE_METHODS = 0`, mutable-field allowlist | 4 registers |
| `HarnessDisciplineTest.java` | harness-side compensation | 14 `Map.entry` rows |
| `VerdictChannelRegisterTest.java` | verdict channel owners | 6 entries |
| `SkipCensusTest.java` | `@Disabled` counts + assumption file set | 1 pin (15) + 10 file names |
| `pct/…/PctDisciplineTest.java` (NOT in core's run) | zero Java-side comparison in the pct module; `pct_adapter.pure` ≤320 lines | 2 regex families + 1 size pin |
| `TdsInferencePinTest.java` | 3 TDS column-inference rules (one is a DELIBERATE wrong-answer pin: `colonOffsetTimestampStaysStringUntilTheLoweringPreservesOffsets`) | 3 |
| `TdsNullTypingPinTest.java` | `^TDSNull()` types as `TDSNull[1]`; the bare reference stays the `sqlNull()` funnel | 4 |
| PCT expected-failure lists (`pct/`, not in core's run) | named PCT tests allowed to fail | **36 total**: Essential 25, Grammar 10, Relation 1, Standard 0, Unclassified 0 |

**Observation on the whole class**: these ratchets patrol *where code lives* and *how much of it
there is*. Not one of them can observe a wrong type, a wrong value, or a wrong SQL semantic. A
compiler could hand back `java.lang.String` under an `Integer[1]` column on every single query and
every ratchet above would stay green.

---

## 3. THE KEY QUESTION — per-finding coverage

Legend: **COVERED** = a test asserts the correct behaviour. **PINNED** = a test asserts the CURRENT
WRONG behaviour, so fixing the bug turns the suite red. **UNCOVERED** = nothing touches it.

### TALLY: COVERED 0 · PINNED 3 (+1 shared) · UNCOVERED 15 · of 19 findings

---

### PINNED

#### V3 — `cast` converts instead of asserting → **PINNED, hard**

`core/src/test/java/com/legend/integration/TypeConversionCheckerTest.java` exists to enshrine
cast-as-conversion. Its own file header, lines 15-22:

```java
/**
 * Integration tests for cast() — validates that cast enables meaningful
 * downstream operations that require the target type.
 *
 * <p>
 * Philosophy: every test should DO SOMETHING with the casted value that
 * would fail without the cast, and assert exact output values ...
 */
```

Three of its 13 tests cast a **String** column to `@Integer` and assert an arithmetic result.
`TypeConversionCheckerTest.java:56-77`:

```java
@Test
@DisplayName("cast column to Integer, then sum — proves cast enables aggregation")
void testCastThenSum() throws SQLException {
    var result = executeRelation("""
            #TDS
                product, amount:String
                Widget, 100
                ...
            #->groupBy(~[product], ~[total:x|$x.amount->toOne()->cast(@Integer):y|$y->plus()])
            """);
    ...
    assertEquals(250, ((Number) result.rows().get(1).get(totalIdx)).intValue(),
            "Widget: 100 + 150 = 250");
}
```

plus `testCastThenFilterNumeric` (`:80-99`, `$x.price->toOne()->cast(@Integer) > 60` where
`price:String`) and `testCastThenArithmetic` (`:102-119`). In real Pure, `cast` is a **checked
downcast**; `String` is not a subtype of `Integer`, so all three of these must be compile errors.
Verified current behaviour:

```
---- V3b '100'->cast(@Integer)
  SQL: SELECT CAST('100' AS BIGINT) AS value
  ROW Long(100)
---- V3 2.7->cast(@Integer)
  SQL: SELECT CAST(CAST(2.7 AS DOUBLE) AS BIGINT) AS value
  ROW Long(3)
```

Making `cast` an assertion turns **three tests, in a file named for the wrong semantics, red**.

The same file contains the contradiction in its own source. `TypeConversionCheckerTest.java:361-375`:

```java
@DisplayName("cast on TDS generates pass-through SQL — no CAST keyword")
...
assertFalse(sql.toUpperCase().contains("CAST("),
        "Relational cast is a type assertion, should not emit SQL CAST(): " + sql);
```

The suite therefore asserts that `cast` **is** a type assertion for the Relation arm and **is not**
for the scalar arm, in one file, 300 lines apart.

#### V2 — `cast(@Any)->cast(@Integer)` erases the check → **PINNED by shared fix**

No test anywhere performs `cast(@Any)->cast(@T)`; the only two `cast(@Any` sites are
`FoldCheckerTest.java:267` and `TypeInferenceIntegrationTest.java:3162`, both `[]->cast(@Any)` as a
fold identity. So the *erasure itself* is untested. But the principled repair — make `cast` a
checked downcast so an `Any`-typed String cannot satisfy `@Integer` — is exactly V3's repair, and
turns the three `TypeConversionCheckerTest` tests red. A narrow "only fix the Any arm" patch would
leave the suite green.

Confirmed behaviour:
```
---- V2 'a'->cast(@Any)->cast(@Integer)
  SQL: SELECT 'a' AS value
  COL value : INTEGER mult=null
  ROW String(a)
```

#### V11 — cross-kind `==` → **PINNED at the adjacent rows**

`core/src/test/java/com/legend/exec/EqualityWorldsConformanceTest.java` is a two-world conformance
table. `1 == '1'` is not in it. But `declaredDivergences` (`:90-121`) pins the neighbouring
cross-kind rows at **true**, and calls it a design decision:

```java
diverge(false, true, "1", "1.0", 1L, 1.0d,
        "SQL numeric coercion — engine-relational parity");
...
diverge(false, true, "8", "8D", 8L, new java.math.BigDecimal("8"),
        "SQL numeric coercion vs engine same-kind-only eq");
diverge(false, true, "3.0d", "3.00d", ...,
        "SQL scale-blind '=' vs engine assert-seam equals");
```

The helper's own contract (`:57-59`): *"A DECLARED divergence: each world pinned at its OWN verdict,
the reason on the record."* World 1 (the host adjudicator) answers **false** for `8 == 8D`; World 2
(the compiler) answers **true**; the test asserts both and calls the disagreement declared. Any fix
that makes `==` reject or correctly answer cross-kind operands turns this red.

Confirmed:
```
---- V11 1 == '1'          SQL: SELECT 1 = '1' AS value            ROW Boolean(true)
---- V11b 1 == 1.0         SQL: SELECT 1 = CAST(1.0 AS DOUBLE)     ROW Boolean(true)
```

#### V13 — `PrecisionDecimal` arithmetic is dead → **PINNED, and the pin is vacuous**

I re-verified the zero-caller claim myself:

```
$ grep -rn "\.times(\|\.dividedBy(\|\.adjust(\|DEFAULT_DECIMAL" core/src/main --include=*.java \
    | grep -vc "type/Type.java"
0
$ grep -rln "\.times(\|\.dividedBy(" core/src/test --include=*.java
core/src/test/java/com/legend/compiler/element/type/PrecisionDecimalArithmeticTest.java
```

**The only caller of `PrecisionDecimal.{plus,minus,times,dividedBy}` in the entire repository is the
test file that tests them.** That file holds **9 tests**, including a property sweep
(`PrecisionDecimalArithmeticTest.java:71-91`) over `p1,s1,p2,s2` in a 20×20 grid × 4 operations —
roughly 176,000 assertions against code production never calls. The correct fix for dead code is
deletion, and deletion turns 9 tests red. This is the suite's clearest *vacuous green*: a whole
file of passing tests that certifies an algebra the compiler does not consult.

---

### UNCOVERED (15)

Each row: the construct, the nearest test that exists, and why it misses.

| # | finding | nearest existing test | why it misses |
|---|---|---|---|
| **V1** | value-position `==` on a `[0..1]` operand yields NULL under `Boolean[1]` | `lowering/NullSemanticsTest.valuePosition` (`:112-127`) asserts *exactly this property* — "a `[0..1]` comparison yields pure's false, never SQL NULL" — **for `endsWith`**; `colToColEqualNullSafe` (`:73-88`) asserts filter-position `==` is null-safe | the guarded spellings are the tested ones; the ONE unguarded spelling (`==` in value position) is the one nobody wrote a case for |
| **V4** | high-scale Decimal literal decodes as Double | `exec/VerdictWorld2ConsistencyTest.decodeAnyPrecision` (`:78-95`) round-trips `1234567890123456789012345.5D` and asserts `assertInstanceOf(BigDecimal.class, viaAny)` | that literal is **25 digits of precision, scale 1** — the carrier switch is on **scale**, so it decodes `BigDecimal` and passes. `SpecCompilerTest:120,293` pins only the STATIC `PrecisionDecimal` type |
| **V5** | `Multiplicity.product` int overflow | `compiler/element/type/MultiplicityAlgebraTest.productComposesNavigationPaths` (`:65-76`) | its largest bound is **6**; `product(b(1,2), b(2,3)) == b(2,6)`. Nothing near `int` range |
| **V6** | `%10:30:45` → `NotImplementedException` | `lexer/LexerTest.strictTimeLiteral:164` and `parser/SpecParserTest.strictTimeLiteral:220` | both are parse-only; the literal is never compiled, lowered, or executed. There is exactly **1** `StrictTime` mention in the whole test tree |
| **V10** | `extends …type::Nil` makes a class a subtype of everything | `compiler/element/PureModelContextTest.isSubtypeWalksNativeLattice` (`:127-133`) | asserts `Integer < Number` and `Integer < Any` only. Zero tests declare a class `extends …type::Nil`; the 9 `Nil` mentions are all `Nil[0]` fold accumulators |
| **V12** | collection element LUB is order-dependent | `compiler/spec/SpecCompilerTest:120,293` pin single decimal literals | no test builds a collection of two decimals with different scales. Confirmed: `[1.25d,1.5d]` → `Decimal(38,1)`, `[1.5d,1.25d]` → `Decimal(38,2)` |
| **V15** | `project(~[])` types 0 columns but emits `SELECT *` | none | zero occurrences of `project(~[])` in the test tree; zero `"SELECT *"` expectations |
| **V18** | a generic user function's declared return type is never checked | `integration/UserFunctionIntegrationTest.testReturnTypeMismatchThrows` (`:555-568`) and `testReturnTypeMismatchRejected` (`:1102-1118`) **do** catch concrete mismatches | **all 16** generic user-function declarations in the test tree are in `parser/ElementParserTest` — parse-only — and every body is the identity (`function my::id<T>(x: T[1]): T[1] { $x }`). No generic function is ever compiled with a body that violates `T` |
| **V19** | `sum()` returns null under `Integer[1]` on an empty group; 2^64-2 on overflow | `exec/ExecutorTest.aggregateEmptyInput` (`:95-104`) tests **exactly the empty-aggregate case** and asserts `assertNull(t.rows().get(0).get(0))` | it uses **`max()`**, which correctly declares `[0..1]`. I probed the identical query: `COL m : INTEGER MULT=Bounded[lower=0, upper=1]` — the null is sound there. `sum()` declares `[1]` for the same shape and delivers null. No test uses `sum()` on an empty group |
| **V20** | object-space `->toOne()` is deleted | `lowering/ToOneLaneTest` (12 tests) and `compiler/spec/MultiplicityStrictnessTest` (11 tests) — two whole files on `toOne` semantics | **zero occurrences of `.all()->toOne()`** in the test tree. Both files exercise only literal/value-lane and relation-lane collections. Confirmed: `m::A.all()->toOne()->project(...)` emits `SELECT t0.NAME AS nm FROM A AS t0` with no guard and returns 3 rows |
| **V21** | same Decimal column: exact on DuckDB, `Long` on SQLite | `integration/SQLiteIntegrationTest` declares one `TOTAL DECIMAL(10,2)` column (`:113`) | it only asserts the **parsed column count** (`assertEquals(3, orderTable.columns().size())`). No Decimal value ever executes on SQLite. `SQLiteIntegrationTest` is the only file using `jdbc:sqlite`; `sql/dialect/CarrierDifferentialTest` runs both strategies on **DuckDB only** |
| **V23** | a user model can redefine a platform native | `compiler/PctFunctionSuppressionTest.nativeOwnedSuppresses` pins the native winning **only for `<<PCT.function>>`**; `compiler/spec/TypeCheckerTest.platformPackageUserFunctionIsNotSilentlyCaptured` (`:1228-1241`) pins the **user** winning at a NON-native `meta::pure::custom::map` FQN | the two tests bracket the hole from both sides and neither covers it. Probed: `first` hijacked → `SELECT 'HIJACKED'`; but the same hijack on `assert` is inert (V23a == V23b), so the mechanism is per-function and untested |
| **V24** | let-inlining capture — renaming a binder changes the answer | `compiler/spec/UserCallInlinerTest.captureHygiene` (`:114-123`): *"the eval lambda's own binder must not capture the outer `$v`"* | it exercises the **UserCallInliner**'s argument-vs-callee-binder collision via `applyTo`. V24 is the **let** substitution path (`LetChecker`/`SeedableLets`). Confirmed both spellings: `list_transform([7], z -> x)` (correct) vs `list_transform([7], y -> y)` (captured) |
| **V25** | `first(set, count)` drops its count | `native-catalog.txt:178` pins the SIGNATURE `first<T>(set:T[*], count:Integer[1]):T[*]` | **zero** calls to `first(<int>)` in the test tree (`grep -cE "first\([0-9]"` → 0). The catalog test (`NativeFunctionTest.catalogMatchesTheGoldenFile`) compares the catalog to a file generated from itself, so the signature is pinned and the lowering is not |
| **V26** | relational mapping does no property-type/column-type check | `integration/StressTestChaotic` is the only place pairing Pure types with SQL column types (`TYPE_MAP`, `:72-82`) | every pair in `TYPE_MAP` is a **correct** pair, and the file is `@Tag("heavy")` → excluded from the default run. No test constructs a mapping where the property type and column type disagree |

---

### Pasted probe output for the UNCOVERED claims

(`/home/user/probe/jrun.sh`, DuckDB unless stated.)

```
---- V1 value-pos: n==5 and n>3
  SQL: SELECT t0.NAME AS nm, t0.N = 5 AS eq, (t0.N IS NOT NULL AND t0.N > 3) AS gt FROM A AS t0
  COL eq : BOOLEAN mult=Bounded[lower=1, upper=1]
  ROW String(b) | null | Boolean(false) |          <- null under a [1] column
---- V1 control (the TESTED spelling): street->endsWith('p')
  SQL: SELECT t0.NAME AS nm, (t0.STREET IS NOT NULL AND ends_with(t0.STREET, 'p')) AS e ...
---- V1 filter-pos col==col (the TESTED spelling)
  SQL: ... WHERE (t0.STREET IS NOT DISTINCT FROM t0.STREET)

---- V15 project(~[])
  SQL: SELECT * FROM A AS t0
  EXEC-ERR: java.lang.IllegalStateException: result has 3 columns but the typed schema has 0

---- V20 all()->toOne()
  SQL: SELECT t0.NAME AS nm FROM A AS t0
  ROW String(a) | ROW String(b) | ROW String(c)     <- 3 rows out of a ->toOne()

---- V19 sum over EMPTY group (TDS)
  SQL: SELECT SUM(_tds0.x) AS s FROM (VALUES (1), (2)) AS _tds0(x) WHERE _tds0.x > 100
  COL s : INTEGER mult=Bounded[lower=1, upper=1]
  ROW null
---- V19 the query ExecutorTest.aggregateEmptyInput ACTUALLY runs (max, not sum)
  COL m : INTEGER  MULT=Bounded[lower=0, upper=1]   <- sound; that is why it passes
  ROW [null]

---- V4 3.14159265358979323846264338327950288419D
  COL value : PrecisionDecimal[precision=38, scale=38]     ROW Double(3.141592653589793)
---- V4 the literal decodeAnyPrecision actually tests
  COL value : PrecisionDecimal[precision=38, scale=1]      ROW BigDecimal(1234567890123456789012345.5)

---- V12 [1.25d, 1.5d]  -> PrecisionDecimal[38,1]   ROW BigDecimal(1.25), BigDecimal(1.50)
---- V12 [1.5d, 1.25d]  -> PrecisionDecimal[38,2]

---- V6 %10:30:45
  PLAN-ERR: com.legend.error.NotImplementedException: scalar lowering not yet implemented for TypedCTime

---- V24 correct: SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], z -> x), 1)), 1)  -> Integer(10)
---- V24 buggy:   SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], y -> y), 1)), 1)  -> Integer(7)

---- V25 first(2): SELECT UNNEST(list_filter([list_extract([1, 2, 3], 1)], ...))   -> Integer(1)          [one row]
---- V25 take(2):  SELECT UNNEST(list_filter(array_slice([1, 2, 3], 1, 2), ...))   -> Integer(1), Integer(2)

---- V23c hijack first: SELECT 'HIJACKED' AS value -> String(HIJACKED)   (control: list_extract([1,2,3],1) -> Integer(1))
---- V23a hijack assert(Boolean,String): identical to the control — the native wins there

---- V26 mapping type mismatch
  SQL: SELECT t0.NUM AS s, t0.DEC AS i FROM T_ITEM AS t0
  COL s : STRING  mult=[1]     COL i : INTEGER mult=[1]
  ROW Integer(42) | BigDecimal(123.45) |

---- V21 jdbc:duckdb:          COL v : DECIMAL [1]   ROW BigDecimal(12345678901234567.123456789)
---- V21 jdbc:sqlite::memory:  COL v : DECIMAL [1]   ROW Long(12345678901234568)

---- V18 function my::bad<T>(x: T[1]): T[1] { 'hello' } ; |my::bad(1)
  SQL: SELECT 'hello' AS value                          ROW String(hello)
```

---

## 4. THE HONEST HEADLINE — where the suite's assertions are aimed

**Of 19 confirmed findings, 0 are COVERED, 15 (79%) are completely UNCOVERED by 4,278 green tests,
and 3–4 are PINNED — the suite would go RED if they were fixed.**

That is not an accident of coverage. It is a direct consequence of **what the suite asserts about**.
I measured it, per test method, by parsing every `@Test` block in `core/src/test/java`:

| measurement | count | share |
|---|---|---|
| test methods that execute a query end-to-end (`Compiler.execute` / `exec(` / `executeRelation(` / an `ExecutionResult`) | **1671** | — |
| …that assert on **rows / values** | **888** | **53.1%** of executing tests |
| …that assert on a result **column's `pureType()` or `multiplicity()`** | **4** | **0.24%** |
| …that relate a column's **declared `pureType()`** to the **delivered value's Java carrier** | **1** | **0.06%** |
| …that assert a **hardcoded** Java carrier class for a delivered value (not against a declared type) | **3** | 0.18% |

Whole-tree grep, for cross-check:

```
Column.pureType() assertion lines in all of core/src/test :  7   (5 in ExecutorTest, 2-3 in PivotCheckerTest)
.rows() call sites                                        : 1943
lines containing a "SELECT literal                        :   87
```

And the four exceptions mostly do not do what their count suggests. `ExecutorTest.tabularShape`
(`:81-93`) asserts `pureType() == STRING` **and separately** `assertEquals("Bob", t.rows().get(0).get(0))`
— it never relates the two. `ExecutorTest.pivotDynamicColumnsInheritTemplateType` (`:108`) is about
pivot column naming and template inheritance.

**The single exception in the entire suite** is `integration/PivotCheckerTest.java:400-421`, which
does close the loop — and only just:

```java
for (var col : result.columns()) {
    if (col.name().contains("__|__")) {
        assertEquals("Integer", col.pureType().typeName(), ...);
    }
}
// Verify actual values are numeric (not String)
for (Row row : result.rows()) { ... if (col.name().contains("__|__")) {
    assertInstanceOf(Number.class, val, "... should be numeric, not " + val.getClass()...); } }
```

One test, scoped to dynamically-named pivot columns (`contains("__|__")`), at `Number` granularity —
it could not distinguish `Long` from `BigInteger` from `BigDecimal`, and it hardcodes the expected
type rather than reading the declared one. Three further tests hardcode a scalar carrier class
(`ConstantPlanParityTest.admittedKindsAgreeWithTheSqlPath` — which checks the two *routes* agree with
each other, not with a declared type; `AuditRound5Test.computedIntegralFloatKeepsKind`
(`assertEquals(Double.class, v.getClass())`); `TypeInferenceIntegrationTest.testStrictDateLiteralScalar`
(`assertFalse(value instanceof String)`)). All are scalar-position; none reads `Column.pureType()`.

**Zero tests read a column's declared Pure type and check the delivered cell against it in general.**

**The one instrument in the repo that does check type conformance is not in this run.**
`com.legend.exec.SqlTypeCensus` ("THE LABEL-LIE CENSUS: for every EXECUTED plan, compare each output
column's DECLARED label against the type the SqlTyping judgment computes … MISMATCH … the lie census")
is asserted in exactly two places: `rcorpus/RelationalCorpusRunner.java:744,754,799` — a class
surefire never loads — and `pct/…/PctCensusGate` — a different module, reached only by gates 6/7.
Even then it compares **SQL type label vs SQL type judgment**, not **Pure type vs delivered Java
carrier**, which is the axis every one of V1/V2/V4/V18/V19/V21/V26 lives on.

So the shape of the suite is:

- **~53% of executing tests are row-equality oracles.** They ask *"is the value right?"* and read the
  cell through a `((Number) x).intValue()` or a `String.valueOf(...)`, which erases the carrier. A
  `java.lang.String` delivered under an `Integer[1]` column (V2, V18, V26) survives every one of them,
  because effectively nothing looks at `getClass()` (4 tests do, and only 1 against a declared type).
- **~3.5% assert SQL text.** Useful for lowering regressions, blind to typing.
- **0.24% assert a declared result type; ONE test (0.06%) relates a declared type to a delivered carrier.** The static type and the runtime
  value are asserted in different tests, by different helpers, and never against each other.
- **0.8% (35 methods) are source-scanning ratchets** that count lines and grep patterns. They are
  well-built, honestly documented, and structurally incapable of seeing a single finding in this audit.
- The compile-time typing IS well tested — `TypeCheckerTest`, `InferenceKernelTest`,
  `MultiplicityAlgebraTest`, `MultiplicityStrictnessTest`, `SpecCompilerTest` are dense and adversarial.
  That is why the findings cluster precisely where the compiler's claim meets the wire: the two halves
  are each guarded and the **seam between them is not**.

The pattern repeats with a precision that is itself the finding. In case after case the suite tests
the *sound sibling* of the broken thing and stops:

| the suite tests… | …and the bug lives in the sibling it skipped |
|---|---|
| `endsWith` and `>` are null-guarded in value position | `==` is not (V1) |
| `max()` over an empty group is `[0..1]` and null | `sum()` over an empty group is `[1]` and null (V19) |
| a 25-digit **precision** Decimal round-trips as BigDecimal | a 38-**scale** Decimal round-trips as Double (V4) |
| `toOne()` raises in the value and relation lanes (2 files, 23 tests) | object-space `toOne()` is deleted (V20) |
| the **UserCall** inliner is capture-safe | the **let** inliner is not (V24) |
| `take(2)` slices correctly | `first(2)` does not (V25) |
| a **concrete** declared return type is checked | a **generic** one is not (V18) |
| `cast` on a **Relation** is an assertion, "should not emit SQL CAST()" | `cast` on a **scalar** is a conversion (V3) |
| `<<PCT.function>>` bodies are suppressed at a native FQN | un-stereotyped bodies are not (V23) |
| `Multiplicity.product` on bounds ≤ 6 | on bounds ≥ 46341 (V5) |

A suite this large going green on all 4,278 while missing 15/19 real defects is not under-tested in
volume. It is aimed one axis away from where the type system can actually break.

---

## VERIFIED SOUND (coverage evidence for this audit)

- The suite genuinely is green: 4278/0/0/16 reproduced from a clean copy, `BUILD SUCCESS`.
- The 16 skips are fully accounted for from the surefire XML (15 named `@Disabled` GAP rows + 1
  environment assumption) and are themselves ratcheted by `SkipCensusTest`.
- The `heavy` exclusion covers only 2 stress/benchmark files; nothing type-related is hidden there.
- `SkipCensusTest`, `RawSqlLedgerTest` and `JdbcSurfaceCensusTest` use `assertEquals` on the *whole
  set* (exact both directions, stale rows fail), not a `<=` ceiling — the strongest form of these
  guards. `SqlTextRatchetTest`, `TenetRatchetTest`, `CarrierPurityRatchetTest`, `JavaEvalLedgerTest`
  use ceilings but also flag *shrinkage* so pins cannot rot upward silently.
- `GuardCoverage.assertFloor` is real and used: `SkipCensusTest` ≥270 files, `TenetRatchetTest` ≥498,
  `CarrierPurityRatchetTest` ≥74, `SqlTextRatchetTest` ≥250, `JdbcSurfaceCensusTest` ≥778. These
  guard the guards against a moved walk root. That is good practice and it works.
- Compile-time type/multiplicity checking is well covered. `MultiplicityStrictnessTest` in particular
  is a strong, adversarial file (it pins the `[0..1]`-into-`[1]` rejection that "ZERO tests pinned"
  before it) and `VarianceD4Test` pins contravariant parameter slots with a real unsoundness repro.
- `ExecutorTest.aggregateEmptyInput` is correct as written — I ran its exact query and the declared
  multiplicity really is `[0..1]`.

## NOT COVERED by this report

- I did not run the `pct` module (gates 6/7/9) or `parser-equivalence`. Their ledgers are reported
  from source, not from a run. GATE7's standing allowance (`fail<=1, err<=22`) is quoted from
  `tools/allgates.sh:154`, not observed.
- I did not run `RelationalCorpusRunner` — the legend-engine checkout it wants
  (`$HOME/legend/legend-engine`) is absent, and it needs `-Dtest=` to be selected at all. So I cannot
  say what the corpus differential would or would not catch; I can only say **it is not in
  `mvn -pl core test`**.
- I did not attempt real fixes and re-run the suite. The PINNED verdicts are argued from the quoted
  assertion plus the verified current behaviour, not from an observed red build. V3 and V13 are
  unambiguous on that basis; V2 and V11 depend on which repair is chosen, and I have said so
  explicitly in each entry rather than rounding up.
- V7, V8, V9, V14, V16, V17, V22, V27 were outside my assignment and were not analysed for coverage.
