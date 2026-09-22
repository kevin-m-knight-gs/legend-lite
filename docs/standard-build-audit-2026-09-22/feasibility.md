# C — Bazel feasibility: six unknowns, answered by building

Date: 2026-09-22. Host: macOS 24.5.0, darwin arm64, **10 cores / 32 GiB**.
Toolchain used everywhere: **Bazel 9.2.0** (via bazelisk), `rules_java 9.9.0`,
`rules_jvm_external 7.1`, bzlmod. Reference repo read at commit `97a32a987`.

Every prototype is a standalone Bazel workspace under
`/private/tmp/claude-502/-Users-neemsandv/75e142bb-86fc-4fc7-9991-eda774c19853/scratchpad/audit/C-proto/`
(`q1`…`q6`). They are self-contained and re-runnable with
`USE_BAZEL_VERSION=9.2.0 bazel test //...`.

---

## Q1 — Error Prone + NullAway on Bazel's Java toolchain

### VERDICT: **WORKS** (Risk 4 is resolved; the pom's flag list needs three edits, not a custom toolchain)

**Prototype:** `C-proto/q1/` — `BUILD.bazel`, `src/main/java/com/legend/{Violation,Clean,Generics,NonNull,Nullable}.java`

### Evidence

The deliberate violation **fails the build**, with NullAway's own diagnostics:

```
$ bazel build //:violation
ERROR: .../BUILD.bazel:34:13: Building libviolation.jar (1 source file) failed: (Exit 1)
src/main/java/com/legend/Violation.java:10: error: [NullAway] returning @Nullable expression from method with @NonNull return type
        return null;
        ^
src/main/java/com/legend/Violation.java:14: error: [NullAway] dereferenced expression 's' is @Nullable
        return s.length();
```

`//:clean` (same code, return declared `@Nullable`) builds green. A **JSpecify-mode-only**
violation — `List<@Nullable String>` passed where `List<String>` is expected — is also caught,
proving `JSpecifyMode=true` is genuinely engaged and not silently ignored:

```
src/main/java/com/legend/Generics.java:9: error: [NullAway] incompatible types:
    List<@Nullable String> cannot be converted to List<String>
```

#### Wiring that works

```python
java_plugin(
    name = "nullaway_plugin",
    deps = ["@ep//:com_uber_nullaway_nullaway"],   # NO processor_class
)
java_library(
    name = "core", ...,
    plugins = [":nullaway_plugin"],
    javacopts = [
        "-XDcompilePolicy=simple",
        "--should-stop=ifError=FLOW",
        "-Xmaxerrs", "10000",
        "-XepDisableAllChecks",
        "-Xep:NullAway:ERROR",
        "-XepOpt:NullAway:AnnotatedPackages=com.legend",
        "-XepOpt:NullAway:CustomNullableAnnotations=com.legend.Nullable",
        "-XepOpt:NullAway:CustomNonnullAnnotations=com.legend.NonNull",
        "-XepOpt:NullAway:JSpecifyMode=true",
        "-XepOpt:NullAway:CheckOptionalEmptiness=true",
    ],
)
```

A `java_plugin` with **no `processor_class`** lands on javac's `--processorpath`, where Error
Prone's `ServiceLoader` finds NullAway's `@AutoService(BugChecker.class)` registration. That is
the whole mechanism — no custom `java_toolchain` is needed.

#### The three edits to `core/pom.xml`'s flag list

1. **`-Xplugin:ErrorProne` must be DELETED.** Bazel's `JavaBuilder` instantiates Error Prone
   itself (`BlazeJavaCompiler`); the Maven `-Xplugin` form is rejected outright:
   ```
   error: plug-in not found: ErrorProne
   ```
   This was the first failure mode hit, and it is the one that would burn a day if unanticipated.
   The `-Xep*` flags go through untouched.

2. **The whole `-J--add-exports` / `-J--add-opens` block must NOT be reproduced.** It is already
   there. `bazel aquery 'mnemonic("Javac", //:generics)'` shows the Javac action's command line:
   ```
   external/rules_java++toolchains+remotejdk25_macos_aarch64/bin/java
   '--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED'
   '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED'
   '--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED'
   '--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED'
   '--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED'
   '--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED'
   ```
   This is `rules_java`'s `BASE_JDK9_JVM_OPTS`
   (`toolchains/default_java_toolchain.bzl:21`). It covers every module the pom lists; the pom's
   `file` and `parser` **exports** appear here as **opens**, which subsumes them. They are
   **toolchain JVM options, not javacopts** — there is no correct place to put them in
   `javacopts`, and none is needed.

3. `-XDaddTypeAnnotationsToSymbol=true` is **accepted but not load-bearing under Bazel.**
   A target built with the flag removed still produced the JSpecify generics error identically.
   Reason: the pom's note says the flag prevents a NullAway 0.13.x crash *on JDK 21*, and Bazel
   does not run javac on JDK 21 — see below. Keep it (it is free) but do not treat it as a
   dependency.

#### JDK 21 vs JDK 25

There is **no separate JDK-25 experiment to run** — Bazel 9.2 already runs Error Prone on JDK 25
out of the box. `rules_java`'s `_BASE_TOOLCHAIN_CONFIGURATION` hard-pins
`java_runtime = Label("//toolchains:remotejdk_25")` (line 102), so the JavaBuilder JVM is
`remotejdk25_macos_aarch64` **regardless of `--tool_java_runtime_version`**; only the
`--bootclasspath`/target comes from `remotejdk_21`. Both configurations were run:

| config | `//:violation` | `//:clean` |
|---|---|---|
| `--java_language_version=21 --java_runtime_version=remotejdk_21` (javac on JDK 25) | FAILS with the 2 NullAway errors | passes |
| `--java_language_version=25 --java_runtime_version=remotejdk_25 --tool_java_runtime_version=remotejdk_25` | FAILS with the same 2 errors | passes |

#### Error Prone version conflict: none

Bazel 9.2's `java_tools v21.0` `JavaBuilder_deploy.jar` bundles Error Prone. Class-set comparison
against the pom's pinned `error_prone_core-2.50.0`:

```
JavaBuilder EP classes: 2120    error_prone_core-2.50.0 classes: 1666
in 2.50.0 but NOT in JavaBuilder: 0
in JavaBuilder but NOT in 2.50.0: 454      (check_api + annotation + other EP modules)
```

The bundled Error Prone is a strict **superset** of 2.50.0 — i.e. **≥ 2.50.0**. NullAway 0.13.8
loads from the processorpath and links against it without an `AbstractMethodError` /
`NoSuchMethodError`, which the green/red runs above prove empirically.

### Remedy / residual risk

- Delete `-Xplugin:ErrorProne` and the ten `-J--add-*` args when porting. Keep the `-Xep*` block
  verbatim.
- **Standing risk (new, not in the plan):** the Error Prone version is now owned by
  `java_tools`, not by the pom. A `rules_java` / Bazel upgrade silently moves it. If NullAway
  0.13.8 ever stops linking against a newer `error_prone_check_api`, the fix is a custom
  `java_toolchain` with your own `javabuilder` — budget 1 day *if* it happens. Add a CI job that
  pins Bazel by version and a `.bazelversion` file.

### Effort: **half a day** to port `core`'s null gate, including the per-package
`AnnotatedPackages` ratchet as a shared `javacopts` list in a `.bzl` macro.

---

## Q2 — JUnit 5 + vintage + runtime-generated suites

### VERDICT: **WORKS WITH CAVEAT** — the suites run correctly, *and* the silent-zero-tests failure mode is REAL on all three runners

**Prototype:** `C-proto/q2/` — `src/test/java/com/legend/GeneratedSuite.java` (faithful
miniature of `Test_LegendLite_RelationFunctions_PCT` + `PctCensusGate.wrap`),
`src/test/java/com/legend/GuardedSuite.java` (the remedy), `BUILD.bazel` (9 lanes).

### What was modelled

`Test_LegendLite_RelationFunctions_PCT.suite()` is a **JUnit 3** `public static Test suite()`
that returns `PctCensusGate.wrap("Relation", wrapSuite(..., PureTestBuilderInterpreted
.buildPCTTestSuite(reportScope, expectedFailures, adapter), ...))` — i.e. cases constructed at
runtime from the loaded Pure graph, wrapped in a `junit.extensions.TestSetup` whose `tearDown()`
**asserts** the SqlTypeCensus ceilings. `GeneratedSuite` reproduces exactly that shape with a
runtime case count controlled by `-Dq2.count`.

### Evidence — the good path works on every runner

| lane | runner | 7 generated cases | one case fails | teardown assertion fires |
|---|---|---|---|---|
| A | Bazel's own `java_test` / `BazelTestRunner` | `OK (7 tests)`, all 7 ran, teardown ran | **FAILED** | **FAILED** |
| B | `contrib_rules_jvm 0.34.0` `java_junit5_test` + `junit-vintage-engine 5.11.3` | all 7 ran, teardown ran, `Failures: 0` | **FAILED** | n/a |
| C | plain `java_test`, `use_testrunner = False`, `main_class = ConsoleLauncher` | all 7 ran | — | — |

Lane A's `test.xml` is honest: `<testsuite name='GeneratedSuite(runtime)' tests='7' .../>` with
seven `<testcase name='generated_N' .../>` rows. **Bazel does not enumerate test classes
statically** — `BazelTestRunner` hands the class to JUnit4's `JUnit38ClassRunner`, which honours
`suite()`. So the framework's runtime generation is fine.

One wiring gotcha on lane B: without `org.junit.platform:junit-platform-launcher` **and**
`junit-platform-reporting` in `runtime_deps`, the runner dies with
`IllegalStateException: JUnit 5 test runner is missing a dependency on artifact("org.junit.platform:junit-platform-launcher")`.

### Evidence — the silent pass is REAL, and worse than the plan's phrasing

With `-Dq2.count=0` (the framework generated nothing — a missing PAR, an unregistered
`CodeRepositoryProvider`, an adapter that failed to load):

| lane | result | `test.xml` |
|---|---|---|
| A `BazelTestRunner` | **PASSED** — `OK (0 tests)`, `exiting with a return value of 0` | `<testsuites />` (empty) |
| B `java_junit5_test` + vintage | **PASSED** — `Failures: 0` | **`tests="1"`** with a fake `<testcase name="com.legend.GeneratedSuite">` |
| C ConsoleLauncher **with `--fail-if-no-tests`** | **PASSED** | `[1 tests successful]` — the vintage *runner* is counted as a leaf, so the flag never fires |

So:
- **Bazel's default runner passes silently** (detectable only by reading `test.xml` for an empty
  `<testsuites/>`).
- **`--fail-if-no-tests` does NOT protect you.** The JUnit Platform's vintage engine reports the
  empty runner as one successful test.
- **contrib_rules_jvm is the worst of the three**: its `test.xml` claims `tests="1"`, so even an
  external XML-count guard is fooled.

The census gate's `tearDown()` still runs in the zero case and still passes — because every
counter is zero. `[q2] TEARDOWN ran` appears in all three zero-case logs.

### Remedy (built and proven)

The only runner-independent detector is a **case-count floor asserted inside `suite()`**, before
the suite is handed over — the same ratchet shape as `MAX_UNTYPED`:

```java
static Test wrap(String suiteName, TestSuite t) {
    int n = t.countTestCases();
    if (n < MIN_CASES) {               // MEASURED; only ever goes UP
        throw new AssertionError("[pct-floor] " + suiteName + ": the framework generated "
            + n + " test cases, floor is " + MIN_CASES
            + " — the suite did not build, it did not pass");
    }
    return new TestSetup(t) { ... };
}
```

Measured (`//:D_guard_bazel_zero`, `//:E_guard_junit5_zero`, `//:D_guard_bazel_ok`):

```
//:D_guard_bazel_ok        PASSED in 0.3s
//:D_guard_bazel_zero      FAILED in 0.7s      (Bazel runner)
//:E_guard_junit5_zero     FAILED in 5.7s      (contrib_rules_jvm + vintage)
```

Bolt the floor into `PctCensusGate.wrap(...)` beside the ceilings — one floor per suite, measured
at the current pin (Relation ≈ 469 functions, etc.). This is worth doing **under Maven too**;
the hole is in the suite, not in Bazel.

### What is NOT resolved here

I proved the **shape** runs. I did **not** run the real PCT suite under Bazel. The real suite
additionally needs `legend-pure-maven-generation-par:build-pure-jar` (pct/pom.xml's
`generate-sources` phase) to produce the `core_legend_lite_pct` PAR, and **there is no Bazel rule
for that plugin**. It has to become a `genrule`/`java_binary` action invoking the plugin's mojo
class directly, with `core_legend_lite_pct.definition.json` and the three `.pure` sources as
inputs. That is a genuine remaining unknown, separate from this question.

### Effort
- Floor guard in `PctCensusGate` + 5 suite floors: **4 hours.**
- Porting the 5 PCT suites + 5 ChannelB suites to `java_test` targets: **1 day.**
- **PAR generation as a Bazel action: 1–2 weeks, unproven.** This is the real schedule risk in
  the `pct` module, not the test runner.

---

## Q3 — rules_jvm_external over the real dependency set

### VERDICT: **WORKS WITH CAVEAT** — resolves clean and fast, but `rules_jvm_external` **6.x is broken on Bazel 9**

**Prototype:** `C-proto/q3/` — `MODULE.bazel` with the real coordinates, plus the four generated
lock files (`pe_install.json`, `pct_install.json`, `h2_modern_install.json`,
`h2_legacy_install.json`).

### The caveat, found first

`rules_jvm_external 6.10` + Bazel 9.2.0 fails at **loading** time, before any resolution:

```
ERROR: external/rules_android+/rules/android_library/impl.bzl:367:17:
       The CcInfo symbol has been removed, add the following to your BUILD/bzl file:
ERROR: Skipping '@unpinned_pe//:pin': error loading package
       '@@rules_jvm_external++maven+unpinned_pe//'
```

`rules_jvm_external` 6.x pulls `rules_android`, which is not Bazel-9 compatible. **Use
`rules_jvm_external 7.1`** (or newer). Everything below is on 7.1.

Second gotcha: a lock file seeded with `{}` fails with `Unable to determine lock file version`.
Seed it as a **zero-byte file** — `maven.bzl:760` treats empty content as a fresh v3 lock.

### Evidence — resolution

```
$ bazel run @unpinned_pe//:pin            # parser-equivalence
Successfully pinned resolved artifacts for @pe
real 27.478s          (cold: BCR + repo1 pom fetches included)

$ bazel run @unpinned_pct//:pin
Successfully pinned resolved artifacts for @pct
real 3.672s

$ bazel run @unpinned_h2_modern//:pin  /  @unpinned_h2_legacy//:pin
real 1.602s / 1.591s
```

| repo | artifacts resolved | `org.finos.legend` | lock file |
|---|---|---|---|
| `@pe` (parser-equivalence) | **398** | **316** | 812,085 B (794 KiB) |
| `@pct` | **139** | 73 | 254,737 B (249 KiB) |
| `@h2_modern` | 1 | — | 2,335 B |
| `@h2_legacy` | 1 | — | 2,337 B |

Against the plan's 402 / 320 and ~140: **398 / 316 / 139** — within 1 %. The small delta is the
`com.legend` reactor modules and Maven's optional/provided-scope handling, which Coursier
resolves slightly differently.

Versions on the `@pe` graph: `['0.37.0', '4.145.0', '5.99.0']` — one engine release, one pure
release, plus `org.finos.legend.shared:legend-shared-* 0.37.0`.

**Zero malformed or unresolvable poms.** `grep -iE "warn|error|could not|unresolv|malformed|fail"`
over `pe-pin.log` and `fetch.log` returns nothing. `legend-engine-extensions-collection-generation`
fans out to every section grammar without incident.

### Evidence — actual download

```
$ bazel build @pe//... @pct//...
INFO: Elapsed time: 104.183s, Critical Path: 20.90s
FETCH exit=0
```
All 537 artifacts materialised. No failures.

### INV-5 convergence survives

`maven.artifact(...)` pins on top of `maven.install(version_conflict_policy = "pinned")`
reproduce the root pom's `dependencyManagement` exactly:

```
HikariCP     -> 7.0.2      (one entry)
commons-lang3 -> 3.18.0    (one entry)
httpcore     -> 4.4.13     (one entry)
junit:junit  -> 4.13.1     (one entry)
```
One version each — the classpath-convergence invariant holds without extra machinery.

### The Maven `test-jar` works

`legend-pure-m3-core:5.99.0` `<type>test-jar</type>` (the `PureTestBuilderInterpreted` carrier)
maps to `maven.artifact(classifier = "tests")`. The lock records **one** artifact entry with two
shasums:
```
org.finos.legend.pure:legend-pure-m3-core 5.99.0 ['jar', 'tests']
```

### The plan's two-H2 claim: **CONFIRMED**

Two `maven.install` calls with different `name`s produce two independent repositories; the jars
materialise side by side with different content hashes:
```
29b70e427cc1c40c  .../unpinned_h2_modern/.../h2/2.4.240/h2-2.4.240.jar
d623cdc0f61d218c  .../unpinned_h2_legacy/.../h2/2.1.214/h2-2.1.214.jar
d623cdc0f61d218c  .../unpinned_pct/.../h2/2.1.214/h2-2.1.214.jar
```
`@pct`'s own H2 stayed at 2.1.214, unaffected by `@h2_modern`. A gate-7 lane declaring
`@h2_modern//:com_h2database_h2` gets 2.4.240 and nothing else changes. This is strictly better
than the Maven `-Dh2.version=2.4.240` profile, which mutates the whole module.

### Remedy
- Pin `rules_jvm_external >= 7.1` in `MODULE.bazel` and `9.2.0` in `.bazelversion`.
- Commit the four lock files; set `fail_if_repin_required = True` in CI so a coordinate change
  without a repin is a build failure.
- ~1 MB of lock files in the repo. That is the honest cost.

### Effort: **1 day** (the `MODULE.bazel` above is ~120 lines and already works).

---

## Q4 — are the `.pure` sources really inside the published jars, at the current pin?

### VERDICT: **WORKS for the `spec` corpus consumer (byte-exact, complete). BLOCKED for `parser-equivalence`'s whole-tree walk.**

**Prototype / data:** `C-proto/q4/` — `cmp.py`, `engine-tree.json`, `engine-pure-paths.json`,
`pure-pure-paths.json`, `jar-pure-index.json`, `engine-coverage.json`.

### Method

The plan's "measured byte-identical at 5.92.0" could not be re-measured against the local
checkouts: they are **not on the pin**.
```
~/legend/legend-engine  HEAD 943d38b3  = legend-engine-4.137.0-36-g943d38b3dc2   (pin is 4.145.0)
~/legend/legend-pure    HEAD d00cfd5b  = legend-pure-5.92.0-3-gd00cfd5ba         (pin is 5.99.0)
```
So the comparison is **jar bytes vs the pinned git tag's blob bytes**, fetched from
`raw.githubusercontent.com` at the tag commits:
- `legend-engine-4.145.0` → `230c159196d6512486fd382556c5e9e4fb128ebb`
- `legend-pure-5.99.0` → `7fbc7d6e8d52e2488bdae67280e5f5dfed448d68`

`.pure` files were indexed out of **all 322 jars** of those two releases present in
`~/.m2/repository` (290 at 4.145.0, 32 at 5.99.0), keyed by the jar-internal resource path
(the source path minus `…/src/{main,test}/resources/`).

### Result 1 — byte identity: **HOLDS at the current pin**

```
ENGINE 4.145.0 jar-vs-tag: identical=40  DIFFER=0  fetch-fail=0  of 40
PURE   5.99.0  jar-vs-tag: identical=45  DIFFER=0  fetch-fail=0  of 45
```
85 files, SHA-256 compared, **zero differences**. The 40-file engine sample was *seeded with the
twelve exact paths* `spec/src/test/java/com/legend/rcorpus/Corpus.java` names
(`pureToSQLQuery.pure`, `testTdsToRelation.pure`, `testModel.pure`, `preeval/tests.pure`,
`testToJson.pure`, `metamodel.pure`, `tds.pure`, `json.pure`, `core_service/service/metamodel.pure`,
the DuckDB connection metamodel, the sqlDialectTranslation `utils.pure`, the relation mapping
`tests.pure`) plus 28 random files. No jar rewrites resource bytes; there is no filtering,
no `${…}` expansion, no line-ending change.

Also: **0 resource paths appear with different bytes in more than one jar** across all 322 jars —
so "open the jars as a zip FileSystem and take the first hit" is unambiguous.

### Result 2 — the `spec` corpus reader's roots are **complete**

`Corpus.java`'s three roots, source tree at the tag vs jar contents, as exact sets:

| root | jar prefix | source files | jar entries | missing from jar | extra in jar |
|---|---|---|---|---|---|
| `RELATIONAL` | `core_relational/relational/` | 552 | 552 | **0** | **0** |
| `CORE_PURE` | `core/` | 574 | 574 | **0** | **0** |
| `M2M_TESTS` | `core/store/m2m/tests/` | 49 | 49 | **0** | **0** |

Every one of the 5 `LIBRARY_FILES` and 60 `SHAPE_FILES` literals resolves to a jar entry.
**For the `spec` module the design holds completely: `legend-engine-xt-relationalStore-core-pure`
and `legend-engine-pure-code-compiled-core` as `data`, opened as a zip FileSystem, is a
byte-exact substitute for the checkout.** `Corpus.ENGINE_ROOT` / `RELATIONAL` / `CORE_PURE` /
`M2M_TESTS` become jar-relative prefixes; the `available()` skip becomes unnecessary.

### Result 3 — what is genuinely absent: `parser-equivalence`'s walk

`parser-equivalence/src/test/java/com/legend/equivalence/Corpus.all()` does
`add(out, engineRoot(), "C3/C10 engine", t -> true)` — **every `.pure` under the whole checkout**,
plus `pureRoot()`. Measured at the tag:

```
legend-engine 4.145.0:  3,253 .pure files
    in src/main/resources : 2,813
    in src/test/resources :   437
    elsewhere (docs/)     :     3

  PRESENT in some pinned jar : 2,392   (all from src/main/resources)
  ABSENT from every jar      :   849
      · 428 of 437  src/test/resources  -> 0 of 437 present. NONE.
      · 421 of 2,813 src/main/resources -> published as jars, but not in
        THIS dependency closure (legend-engine-xt-sql-reversePCT 136,
        relationalai-pure 50, changetoken-test-pure 47,
        elasticsearch-executionPlan-test 30, changetoken-pure 26,
        protocol-generation-pure 14, python-reversePCT-shared 14, …)

legend-pure 5.99.0:     281 .pure files
  PRESENT: 268 (every src/main/resources file)   ABSENT: 12 (all src/test/resources)
```

So:
- **Recoverable** (add the coordinate): the 421 `src/main/resources` files in modules the current
  poms do not pull. Each is a published `-pure` artifact; add it to the `parser-equivalence`
  `maven.install` and it appears.
- **NOT recoverable from jars**: the **437 engine + 12 pure `src/test/resources` `.pure`
  files**. Maven does not package test resources into the main jar, and the plan already records
  (§4.2) that neither project ships a `-test-sources` jar. These are corpus tiers C3/C10 rows.
  `parser-equivalence`'s C4/C5/C12 **inline** snippets (extracted from upstream *Java test
  sources*) are equally unreachable — the plan already handles those with the committed
  `engine-grammar-fixtures-4.145.0.jsonl` snapshot, which is the same pattern.

### Remedy
- `spec`: migrate now, jars as `data`. Zero risk. **1 day.**
- `parser-equivalence`: three options, in order of cost —
  1. Add the ~15 missing `-pure` coordinates to recover the 421 `src/main` files, and extend the
     committed-snapshot pattern (already proven for C6) to cover the 449 test-resource files:
     harvest once per pin, commit as a `.jsonl`, gate on the pin header exactly as
     `Corpus.engineFixtures()` already does. **~1 week.**
  2. Take the tag **tarball** as a `http_archive` in `MODULE.bazel` (`legend-engine-4.145.0` is a
     single pinned URL + sha256) and keep the walk. Hermetic, no Git, but a large fetch.
  3. Keep the checkout for that one module. Defeats the purpose.
- **Do not let the plan's §4.2 sentence stand unqualified.** Rewrite it as: *byte-identity holds
  and was re-measured at 4.145.0 / 5.99.0; completeness holds for `src/main/resources` within the
  declared dependency closure; `src/test/resources` is absent entirely.*

### Effort: **1 day** for `spec`; **1 week** for `parser-equivalence` (option 1).

---

## Q5 — native JDBC drivers through Bazel

### VERDICT: **WORKS** on macOS arm64. Windows untested — specific risk stated below.

**Prototype:** `C-proto/q5/` — `src/test/java/com/legend/NativeDriverTest.java`, `BUILD.bazel`.

### Evidence

```
$ bazel test //:NativeDriverTest
==================== Test output for //:NativeDriverTest:
TEST_TMPDIR      = /private/tmp/q5ob/sandbox/darwin-sandbox/20/execroot/_main/_tmp/b1f9da9d…
java.io.tmpdir   = /private/tmp/q5ob/sandbox/darwin-sandbox/20/execroot/_main/_tmp/b1f9da9d…
user.home        = /Users/neemsandv
os.arch          = aarch64
PWD              = …/NativeDriverTest.runfiles/_main
DuckDB  answer=42 version=v1.4.4  (1015 ms)
SQLite  answer=42 version=3.47.1  (267 ms)
H2      answer=42 version=2.1.214  (74 ms)
ALL THREE OK
//:NativeDriverTest     PASSED in 1.9s
```

Run under `darwin-sandbox` (Bazel's default local strategy on macOS). All three in-memory
connections opened and executed a query.

**Why it works:** Bazel sets `java.io.tmpdir` **equal to `TEST_TMPDIR`**, which is inside the
sandbox and writable. Both drivers extract via `File.createTempFile` into `java.io.tmpdir`:
- `org.duckdb.DuckDBNative` — strings `duckdb_java`, `libduckdb_java`, `createTempFile`.
- `org.xerial.sqlite-jdbc` — `org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib`.

No `-Dorg.sqlite.tmpdir` or `-Djava.io.tmpdir` override is needed. `user.home` is **not**
sandboxed on macOS (it is the real `/Users/neemsandv`), which is worth knowing but no driver here
writes there.

### Cost worth budgeting

`duckdb_jdbc-1.4.4.0.jar` carries four fat native blobs and picks one at runtime:
```
 57,363,400  libduckdb_java.so_linux_amd64
106,597,944  libduckdb_java.so_osx_universal
 33,496,576  libduckdb_java.so_windows_amd64
 51,504,424  libduckdb_java.so_linux_arm64
```
**106 MB is extracted into `TEST_TMPDIR` per test JVM on macOS**, costing ~1.0 s on the first
`getConnection` (measured above; SQLite ~0.27 s). With `bazel test //...` fanning out to N
concurrent test JVMs that is N × 106 MB of sandbox disk and N × 1 s of wall time. Combine with
Q6: cap concurrency, or put every DuckDB-touching test in one `java_test` per lane rather than
one per class.

### Windows — cannot be tested here (macOS arm64 only). The specific risk:

1. **Sandboxing.** Bazel has no sandbox on Windows (`--spawn_strategy` falls back to `local`), so
   `TEST_TMPDIR` is a real directory and extraction will work — but tests are **not** isolated
   from each other's temp state.
2. **`sqlitejdbc.dll` / `libduckdb_java.so_windows_amd64` loading + antivirus.** `LoadLibrary` on
   a just-written DLL in a temp dir is the classic Windows-Defender false-positive/locking path.
   `sqlite-jdbc` ships `Windows/{x86,x86_64,armv7,aarch64}/sqlitejdbc.dll`; DuckDB ships
   **only `windows_amd64`** — **there is no Windows arm64 DuckDB native in this jar**, so a
   Windows-on-ARM runner would fail outright.
3. **Path length.** Bazel's Windows runfiles paths plus a 32-hex `TEST_TMPDIR` segment plus the
   driver's own temp name pushes toward `MAX_PATH`; `--output_user_root=C:/b` is the standard
   mitigation and would need to be in `.bazelrc`.
4. **File locking.** Windows cannot delete a mapped/loaded native; Bazel's post-test tmpdir
   cleanup can fail and surface as a flaky test teardown. The repo already has Windows CI
   experience (`Corpus.slashed()`, 2026-09-09) — expect one more round of the same.

### Effort: **1 hour** on macOS/Linux. **2–3 days** to prove the Windows lane, and it may end with
"DuckDB tests do not run on Windows arm64".

---

## Q6 — test parallelism and memory

### VERDICT: **WORKS WITH CAVEAT** — the knob exists and is exact, but Bazel's *defaults* will over-schedule PCT lanes by ~3.5×, and on a 3-vCPU/7 GB runner the plan's `bazel test //...` OOMs for **two independent reasons**.

**Prototype:** `C-proto/q6/` — `src/Sleeper.java` (prints `MARK START/END <epoch_ms>`),
`BUILD.bazel` (16 `small` + 16 `enormous` + 8 `resources:memory:2800` + 8 `exclusive` sleepers).
Max concurrency is computed by interval-overlap on the `MARK` timestamps in `bazel-testlogs`.

### Measured defaults on this 10-core / 32 GiB machine

```
$ bazel canonicalize-flags --for_command=test -- --jobs=auto --local_test_jobs=auto
--jobs=auto
--local_test_jobs=auto
```
`bazel help test --long`:
> `--local_test_jobs` … default `"auto"` … *0 means local resources will limit the number of
> local test jobs to run concurrently instead. Setting this greater than the value for `--jobs`
> is ineffectual.*
>
> `--local_resources` … *Bazel will limit concurrently running actions based on the available
> resources and the resources required. **Tests can declare the amount of resources they need by
> using a tag of the `"resources:<resource name>:<amount>"` format.***

| experiment | max concurrent test JVMs |
|---|---|
| 16 × `size = "small"`, all defaults | **10** |
| 16 × `size = "enormous"`, all defaults | **10** |
| 16 × `small`, `--local_resources=memory=2000` | **10** |
| 16 × `enormous`, `--local_resources=memory=2000` | **2** |
| 16 × `enormous`, `--local_resources=memory=1700` | **2** |
| 16 × `enormous`, `--local_resources=memory=900` | **1** |
| 16 × `enormous`, `--local_resources=cpu=64 --jobs=64` | **16** (not memory-bound) |
| 16 × `enormous`, `--local_test_jobs=2` | **2** |
| 16 × `small`, `--local_resources=cpu=3` (3-vCPU sim) | **3** |
| 16 × `enormous`, `--local_resources=cpu=3 --local_resources=memory=7000` | **3** |
| 8 × `tags=["resources:memory:2800"]`, `--local_resources=memory=7000` | **2** |
| 8 × `tags=["resources:memory:2800"]`, default pool (32 GiB host) | **7** |
| 8 × `tags=["exclusive"]` | **1** |

### The exact mechanism

1. **Default on this box = 10 concurrent test JVMs** — `--jobs=auto` and `--local_test_jobs=auto`
   both resolve through `HOST_CPUS` = 10, and CPU is the binding resource.
2. **`size` *does* reserve RAM, and the numbers are far too small.** The `memory=1700 → 2` and
   `memory=900 → 1` calibration pins `size = "enormous"` at **≈ 800 MB** per test (Bazel's
   `TestTargetProperties` estimates: small 20 MB, medium 100 MB, large 300 MB, enormous 800 MB).
   **A PCT lane's live set is 2.0–2.8 GB.** Even tagged `enormous`, Bazel believes it is 3.5×
   smaller than it is and schedules accordingly.
3. **Default memory pool = `HOST_RAM * 0.67`.** Derived, not assumed: 8 tests each declaring
   2,800 MB ran **7** concurrently on the default pool, so pool ∈ [19.6 GB, 22.4 GB);
   32,768 × 0.67 = 21,955 MB → ⌊21955/2800⌋ = 7. Exact match.
4. **`tags = ["resources:memory:<MB>"]` is the knob, and it is exact.** 2,800 MB declared against
   a 7,000 MB pool → exactly 2 concurrent. This is a *per-target* declaration that travels with
   the target — unlike `--local_test_jobs`, it does not need every CI invocation to remember it,
   and unlike `size` it is not quantised.

### What happens on a 3-vCPU / 7 GB macOS CI runner

Two independent failures, either one fatal:

**(a) Scheduler over-subscription.** `--local_resources=cpu=3` binds first (measured: **3**
concurrent, for both `small` and `enormous`). Three PCT JVMs × 2.0–2.8 GB = **6.0–8.4 GB of live
set on a 7 GB machine.** The memory pool would be 7,000 × 0.67 = 4,690 MB, which at the
`enormous` estimate of 800 MB permits 5 — so memory never binds before CPU does, and Bazel never
learns the truth. This is exactly the failure the pom already documents (`pct.reuseForks=false`,
"the shared fork died with OutOfMemoryError in the Pure graph loader at both 3 GB and 4 GB") —
Bazel reintroduces it by *scheduling*, where Maven reintroduced it by *fork reuse*.

**(b) The test JVM's default heap is below the floor.** Measured in a Bazel test JVM on this
host:
```
MARK START 1790088234946 94188 maxHeapMB=8192
```
8,192 MB = **exactly 25 % of 32 GiB** — the JVM's `MaxRAMPercentage` default. On a 7 GB runner
that is **~1,750 MB**, which is *below* the 2.0 GB low end of the PCT live set. **A single PCT
test would OOM at concurrency 1**, before any scheduling question arises. Bazel does not pass
`-Xmx`; Maven surefire's `argLine` did not either, but Maven's fork inherited the developer
machine's larger default.

### Remedy — three lines, per PCT target

```python
java_test(
    name = "Test_LegendLite_RelationFunctions_PCT",
    size = "enormous",
    timeout = "eternal",
    # (1) tell the scheduler the TRUE live set — Bazel's size estimate is 800 MB
    tags = ["resources:memory:3000"],
    # (2) give the JVM a heap that fits it, independent of host RAM
    jvm_flags = ["-Xmx3g", "-Duser.timezone=GMT"],
    ...
)
```
plus, in `.bazelrc`:
```
# CI lane (3 vCPU / 7 GB): one PCT graph at a time, whatever else is queued
build:ci --local_test_jobs=1
```
- `tags = ["resources:memory:N"]` is the **right** primary knob: it is per-target, it composes
  with everything else in `bazel test //...`, and it lets a big developer machine still run
  7 lanes in parallel while a 7 GB runner is forced to 2 (or 1 with the pool at 4.7 GB).
- `--local_test_jobs=N` is the **blunt** knob: global, applies to every test in the invocation
  including the 3,000 fast ones. Use it only in the CI config.
- `tags = ["exclusive"]` (measured: **1**) is the sledgehammer — it also serialises the target
  against *all* other tests, which would make `bazel test //...` on the dev machine much slower.
  Use it only if `resources:memory` proves insufficient.
- `exec_properties` is for **remote** execution (it maps to RBE platform properties); it does
  **not** affect the local scheduler. Do not reach for it here.
- `-Duser.timezone=GMT` must move from surefire's `argLine` into `jvm_flags` on every test target
  (see the root pom's "THE ENGINE'S TEST CLOCK" note) — easiest as a shared `PCT_JVM_FLAGS` list
  in a `.bzl` macro.

### Effort: **2–4 hours** (a `pct_test` macro carrying the tag, the `-Xmx`, and the timezone), plus
one CI run to calibrate the `resources:memory` number per lane against a real `-verbose:gc`
measurement. **The plan's "every lane in `bazel test //...`" is fine — but only once these
tags exist.** Without them it is strictly worse than the current Maven arrangement, because
Maven at least runs the suites in one fork at a time.

---

## Reusable prototypes

| path | what it is |
|---|---|
| `C-proto/q1/BUILD.bazel` + `src/main/java/com/legend/` | working Error Prone + NullAway 0.13.8 JSpecify setup; copy `NULLAWAY_OPTS` verbatim into a `.bzl` |
| `C-proto/q2/src/test/java/com/legend/GuardedSuite.java` | the case-count floor — port `wrap()` into `PctCensusGate` |
| `C-proto/q2/BUILD.bazel` | 12 lanes proving the silent-zero on 3 runners and the fix on 2 |
| `C-proto/q3/MODULE.bazel` | the real `rules_jvm_external` config: 398 + 139 artifacts, INV-5 pins, the `tests` classifier, two H2 repos |
| `C-proto/q3/*_install.json` | the generated lock files (1.07 MB total) |
| `C-proto/q4/cmp.py` + `*.json` | the jar-vs-tag byte-identity + coverage measurement; re-run at every pin bump |
| `C-proto/q5/src/test/java/com/legend/NativeDriverTest.java` | the native-driver smoke test; keep it as a real target |
| `C-proto/q6/BUILD.bazel` + `src/Sleeper.java` | the concurrency calibrator; re-run on any CI runner to derive its real `resources:memory` budget |

## Roll-up

| Q | verdict | effort |
|---|---|---|
| Q1 Error Prone + NullAway | **WORKS** | half a day |
| Q2 PCT suites | **WORKS WITH CAVEAT** (silent-zero is real) | 1 day + **1–2 weeks unproven for PAR generation** |
| Q3 rules_jvm_external | **WORKS WITH CAVEAT** (needs 7.x on Bazel 9) | 1 day |
| Q4 `.pure` in jars | **WORKS** for `spec` / **BLOCKED** for `parser-equivalence`'s test-resource tiers | 1 day + 1 week |
| Q5 native JDBC | **WORKS** (macOS/Linux); Windows unproven | 1 hour + 2–3 days for Windows |
| Q6 parallelism/memory | **WORKS WITH CAVEAT** (defaults over-schedule 3.5×; CI runner OOMs twice over) | 2–4 hours |

Nothing found here makes the migration impossible. The two things that move the schedule are
**PAR generation as a Bazel action** (Q2) and **`parser-equivalence`'s dependence on upstream
test resources that no jar carries** (Q4) — neither is a Bazel problem, and both are invisible
in the plan as written.
