# A standard build for legend-lite

**The goal.** A developer who has never seen this repository clones it, runs one
command, and gets a verdict — on Windows, macOS, or Linux, on any supported LTS JDK,
using only the tools a Java developer already has and nothing specific to this project.
A team of developers can then work on it at the same time without stepping on each other.

**The means.** Maven does all of it. Not Maven plus a shell script that reads Maven's
console output; not Maven plus two source checkouts the developer has to clone by hand;
not Maven plus a Python toolchain. `mvn` decides, and its exit code is the project's
verdict.

This document is the plan to get there from `main` as it stands — `c062b9bc9`,
2026-09-18, the commit every count in section 2 describes. It is written in phases, each
with an exit condition you can observe. Section 7 lists every bespoke script the plan
deletes and what replaces it.

---

## 1. What "done" looks like

Three commands, each with one job, all runnable on every supported platform:

| command | who runs it | what it does |
| --- | --- | --- |
| `./mvnw test` | every developer, all day | Unit tests only. The inner loop. Minutes, not tens of minutes. |
| `./mvnw verify` | every developer before pushing; CI on every PR | **Everything.** Compiles, runs every suite including the conformance suites against the pinned upstream release and both database lanes, and fails if anything is wrong. The same command CI runs; the same verdict. |
| `./mvnw verify -Dledger.update` | a maintainer, after a deliberate change of behavior or upstream release | Accepts new baselines: rewrites the committed ledgers so the diff can be reviewed as a pull request. Everything a build can derive, it derives — see §4.8. |

`mvn` and `./mvnw` are interchangeable throughout: the wrapper exists so a fresh machine
needs no Maven install, and Enforcer rejects an unsupported one either way.

Acceptance criteria for the program as a whole:

1. A clean clone plus `./mvnw verify` is green. No checkouts, no environment variables,
   no scripts, no manual setup steps.
2. That holds on Windows, macOS (arm64 and x86_64), and Linux, on JDK 21 and JDK 25.
3. Every input the build reads is resolved by Maven and version-locked in the POM.
4. A missing input fails the build; it is never skipped past. Skips that remain are
   registered, named, and shrink-only.
5. No test writes into the working tree. CI can assert `git diff --exit-code` after a run.
6. Pass/fail policy lives in test code and committed data files, not in a script that
   greps console output.
7. `main` moves only through pull requests whose required check is `./mvnw verify`.

---

## 2. Where the build stands today

### 2.1 The shape

Five Maven modules in one reactor — `core`, `spec`, `nlq`, `pct`, `parser-equivalence` —
plus a sixth Maven project outside it (`tools/engine-runner`), plus 1,285 lines of shell
in `tools/`, plus 435 lines of workflow YAML including a composite action, plus a Python
toolchain under `scripts/` that generates a large part of the test corpus.

The thing a developer is told to run is not `mvn`. It is `tools/allgates.sh`, which
invokes Maven once per gate, parses the console output of each run, applies its own
ceilings and rosters, and prints `ALLGATES_DONE — GREEN`. Maven's own verdict is not the
project's verdict.

### 2.2 What a newcomer meets, concretely

| | |
| --- | --- |
| **The toolchain is not enforced** | Nothing in the build checks the JDK or Maven version, so the wrong one fails obscurely instead of clearly. The only statement of the supported versions is `.sdkmanrc` (`java=25.0.1-tem`, `maven=3.9.12`), which Windows cannot read. |
| **The README describes a deleted module** | Its Quick Start runs `mvn -pl engine test`; the `engine` module no longer exists. Its test counts (1,713 for `core`) are years of work out of date. |
| **The instructions are agent documents** | `README.md` sends you to `AGENTS.md` ("read by AI coding assistants"), `core/README.md` and `docs/GATES.md`, which is 3,882 lines of dated work records and grows with most commits. There are 244 Markdown files at the top level of `docs/` and no index. |
| **Two checkouts, pinned by SHA** | `spec`, `pct`, and `parser-equivalence` read full source trees of `finos/legend-engine` and `finos/legend-pure` through `-Dlegend.engine.root` / `-Dlegend.pure.root`, which must sit on the exact commits in `tools/oracle-pins.env`. Maven cannot fetch them. Without them some tests fail and others skip silently — which is why `allgates.sh` has a skip detector. |
| **Line endings are unguarded** | There is no `.gitattributes`, so every checkout is at the mercy of the developer's `core.autocrlf`, and CI has to set `core.autocrlf false` and `core.longpaths true` before every checkout. The index itself is in good shape — 2,920 files LF, 10 binary, 4 exceptions — but one exception is `prelude.pure`, whose stored bytes are *mixed* because upstream's own sources carry CRLF and the generator copies their text. Whether a CRLF working tree actually breaks a byte-exact test today is unmeasured; what is certain is that nothing declares the intent. |
| **One LTS is untested** | The POMs set `maven.compiler.source`/`target` to 21 rather than `release`, so builds on a newer JDK can link against newer APIs and still claim 21. CI builds only on JDK 25 (`.github/actions/gate-env` defaults `java-version: "25"`). Nothing runs on 21. |
| **Basic project files are missing** | No `LICENSE` (the README claims Apache-2.0 and many sources carry the SPDX header), no `NOTICE`, no `CONTRIBUTING.md`, no `CODEOWNERS`, no `.editorconfig`. |
| **A known red** | `nlq`'s test model declares `provision` twice in `PriceTypeEnum` (`nlq/src/test/resources/nlq/cdm-model.pure:2574-2575`), so that model does not compile and the module's tests fail on a fresh clone. |
| **A project outside the reactor drifts** | `tools/engine-runner/pom.xml` is parentless, repeats the upstream version as its own `4.145.0`, and depends on `org.finos.legend:legend-lite-core` — a groupId this reactor does not publish (the root POM's is `com.legend`). Nothing in the build compiles it, so the mismatch is invisible. |
| **Whole directories are outside the build** | `projects/` (56 interdependent Legend projects) is checked only by `scripts/projects/check.py`, by hand. `scripts/` itself — the generators for most of the stress corpus — has no build coverage at all. |

### 2.3 The four causes

Everything above traces to four decisions, not to Maven being misconfigured.

**1. Test inputs live outside Maven's model.** The conformance suites read upstream
source trees. Maven cannot fetch a git checkout, so the checkout became a precondition
documented in prose and enforced by shell.

**2. Pass/fail policy lives in bash.** `tools/allgates.sh` (471 lines) holds ceilings
(gate 7 passes with up to 1 failure and 26 errors), a roster of 23 test class names that
must each appear in the output, a fully-skipped-class detector, a mid-run tree-mutation
tripwire, and a three-stream parallel scheduler. None of this is expressible to a
developer as "run the build". And policy the script does not run is not enforced at all:
the H2 stress lane has its floor in code (`MIN_PASS_H2`), but no gate and no workflow runs
that lane, and the stress ledger records a floor that was wrong when committed and was
caught only when someone re-ran the lane by hand (F-AA in
`docs/STRESS_CORPUS_THROUGH_LITE_2026_09_16.md`).

**3. Generated files are committed, and their generators are tests.** `core` ships
`prelude.pure`, `native-claims.tsv`, `native-membership.tsv` and signature text inside
`Pure.java`, all derived from the pinned upstream release. The generators are tests in
`spec` switched on by flags, and they rewrite files in the working tree. Moving to a new
upstream release is a 313-line script (`tools/bump.sh`), not a build step.

**4. Module boundaries are not real.** Nine `core` test classes read other modules'
sources by relative path (`ParserBoundaryArchTest`, `HarnessDisciplineTest`,
`JavaEvalLedgerTest`, `JdbcSurfaceCensusTest`, `LegacyReachbackCensusTest`,
`SkipCensusTest`, `VerdictChannelRegisterTest`, `DiagramServiceTest`, and
`DanglingStateGuardTest`, which walks all five modules). The stress corpus loads eleven of
its projects from `../projects`, and `ErrorShapeGuardrailTest` still walks `../engine`, a
deleted module, skipping silently because it is gone. An edit in `pct` can turn `core`
red, and a new test in `core` can turn `parser-equivalence` red.

### 2.4 What is already right, and must survive

- The product builds, and `core`'s suite is large, fast, and green.
- Real enforcement, already in Maven: ArchUnit layer rules, NullAway on every clean
  compile, an Enforcer ban on upstream artifacts inside `core`.
- Differential testing against the real Legend — the PCT suites, the relational corpus,
  byte-exact parser parity, and the stress corpus — is this project's best asset. The
  plan moves it into the build; it does not weaken it.
- CI already runs on Linux, macOS, and Windows. Only the JDK axis is missing.
- The test clock is already pinned (`-Duser.timezone=GMT`) — the right instinct,
  generalised in phase 1.
- Three runtime dependencies in `core`: DuckDB, SQLite, H2 drivers.

---

## 3. Rules the plan follows

1. **Maven decides.** If a condition can fail the project, it is expressed as a test
   assertion or an Enforcer rule, never as a grep over console output.
2. **Maven owns every input.** Anything a test reads is a Maven artifact resolved from a
   version property, or a file committed in this repository.
3. **A missing input is a failure, and every remaining skip is registered.** A suite that
   cannot reach its input throws rather than assuming past it. Legitimate skips exist —
   a gap waiting on a feature, a test needing a credential — and the project already
   answers them well: `SkipCensusTest` requires every one to carry a named reason and pins
   the per-file count shrink-only. That rule stays; the blanket ban it looks like is not
   the rule.
4. **Generate everything derivable; commit only what must be compared against.** Anything
   the build can compute from inputs it already has is generated on every build and
   committed nowhere (§4.8). Tests write to `target/` only; accepting a new baseline is an
   explicit, separate act.
5. **Nothing in the required path needs a shell.** No bash, no Python, no `make`.
   `mvnw.cmd` works from `cmd.exe` and PowerShell.
6. **Conventions over configuration.** `*Test` in surefire is the fast loop, `*IT` in
   failsafe is the full run, profiles are for modes and not for gates. A newcomer should
   be able to predict what a command does without reading a guide.
7. **One number, one place.** Versions and expectations live once, in the POM or in a
   committed data file.

---

## 4. The target build

### 4.0 Maven, and nothing CI-only

**Why Maven.** The project is already a five-module Maven reactor whose upstream
dependencies are Maven artifacts; the problems in section 2 are things bolted on around
Maven, not things Maven cannot do. Gradle would buy faster incremental builds and a real
worker API, at the cost of rewriting every POM and teaching everyone a second tool — it
does not address a single one of the four causes. Bazel would buy hermeticity, which is
genuinely tempting for a project whose inputs are a pinned upstream release, but it would
mean maintaining rules for the Pure PAR generation plugin, the PCT framework, and the
shaded server jar, and it would put a `WORKSPACE` between a newcomer and their first
build. Maven is also what the upstream projects this one tracks are built with. Staying
is the cheapest way to get the properties we want.

**Nothing is CI-only.** Every check in this plan runs from a laptop with one command.
Three things are deliberately not in `./mvnw verify`:

- **Accepting a baseline** (`-Dledger.update`) rewrites committed expectations. It is not
  a test, and it is not generation — the build generates everything it can derive, every
  time (§4.8). This is the human saying "yes, that change of behavior is intended".
- **The matrix itself.** Running six OS/JDK combinations at once is a property of having
  six machines, not of the build. Each cell is exactly `./mvnw verify`; a developer runs
  the cell they are sitting in.
- **Checks that need Python or the real engine.** The corpus generators are Python
  (§4.8); a scheduled workflow re-runs them and fails if the committed output moved, and a
  developer can run the same script. Checking the corpus's expectations against real
  legend-engine takes about an hour (§4.8) and is a maintainer's run. Nothing in `verify`
  needs either.

Everything else — the conformance suites, both database lanes, the stress corpus, the
parser differential, the generator verification — is in the default build on every
platform.

### 4.1 One reactor

```
legend-lite (root pom: versions, plugin management, enforcer rules)
├── generator             core's upstream-derived sources          (new, built before core)
├── core                  the compiler and server      (no upstream dependencies)
├── nlq                   natural language → Pure
├── spec                  the relational corpus, its harness, the claims ledger
├── pct                   legend-pure's PCT suites
├── parser-equivalence    differential parser tests
├── corpus                the stress corpus and its runner        (moved out of core)
├── projects              the 56-project graph, compiled by a test (new)
├── guards                repo-wide guard and census tests         (new, built last)
└── upstream-runner       today's tools/engine-runner              (in the reactor)
```

Three new modules earn their place:

- **`generator`** is the one module that reads upstream to produce what `core` compiles:
  the prelude, the signature text, the dynafunction registry, and the import sequence. It
  depends on the upstream artifacts and on nothing of ours, and it is built before `core`
  (§4.8, phase 4). The generators leave `spec` for it.
- **`guards`** is where every test that reads another module's sources goes. It is built
  last and depends on everything. An edit in `pct` can then fail `guards`, which is true
  and reviewable, instead of failing `core`, which is neither. It reads the other modules'
  source trees as files, which rule 2 allows. It cannot take their test classes through a
  test-jar: under `mvn test` the reactor substitutes the producer's whole
  `target/test-classes`, service registrations included, and `core`'s once made `###Toy` a
  known section inside `parser-equivalence` (tried and reverted in `b5ad0b82b`).
- **`projects`** gives the 56-project Legend graph a test that compiles it, so it stops
  being unbuilt content in a Java repository.

`corpus` moving out of `core` keeps the inner loop short: `core`'s own suite stays the
thing a developer runs every few minutes, and 4,700 service suites become a module that
runs in `verify`. `corpus` depends on `projects`: the stress model loads eleven of the 56
projects first (`StressCorpus.LINKED_PROJECTS`, read today from `../projects`), so
`projects` publishes them as an artifact that `corpus` resolves, not as a relative path.

### 4.2 Inputs Maven owns

The two source checkouts disappear, in two steps, because the two of them are not the
same problem.

**legend-pure, and most of legend-engine, is already published.** The `.pure` files the
tests read from `src/main/resources` are shipped inside the release jars — measured
byte-identical to the source tree at 5.92.0, the pin at the time; the pin is now 5.99.0,
and phase 2 re-measures before relying on it. `maven-dependency-plugin`'s `copy` goal
hands a module the jar *files* without putting them on a classpath (which matters:
`core` must resolve zero `org.finos.legend` artifacts, and Enforcer asserts it), and the
tests open each jar as a zip `FileSystem`. Paths inside a zip walk, resolve, relativize,
and read exactly like a checkout's, so the calling code barely changes. The version lock
is the existing `<legend.pure.version>` property.

**What is genuinely unpublished is upstream's *test* sources** — `parser-equivalence`
mines Pure snippets embedded in legend-engine's and legend-pure's Java test files, and
neither project ships a `-test-sources` jar. The snippets are committed here, harvested
from the tagged source archives at bump time by a tool the build owns, which reads
**inside** the archive rather than unpacking it (reading in place is not a nicety: it
keeps Windows clear of legend-engine's very deep paths, which is why CI has to set
`core.longpaths` today).

Committing the harvest rather than downloading it on every build is the deliberate
choice, and it is the one that finishes the job: it leaves **no build input that is not a
Maven artifact or a file in this repository**, deletes the download plugin and its
checksum file along with the last reason to reach the network, and turns "what upstream's
test corpus says" into a diff a human reviews when the release moves. The cost is one
refresh step inside a procedure that already exists.

Net effect: `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the `legend.engine.root` and
`legend.pure.root` properties, the env-var profiles in the root POM, the composite
action's two checkouts, and the skip detector all go away. One property,
`<legend.engine.version>`, names the release; `<legend.pure.version>` is derived from that
release's own POM and asserted by a test.

### 4.3 The verdict is Maven's exit code

| today | tomorrow |
| --- | --- |
| Gate ceilings in bash (`run>=469, fail<=1, err<=26`) | A committed expected-failure ledger; the suite asserts the observed failure set **equals** it |
| A 23-name roster so a renamed test cannot shrink a gate | The module runs whole; there is nothing to shrink |
| `skipped()` awk detector | A missing upstream input throws instead of assuming; `SkipCensusTest` keeps every remaining skip named and pinned |
| Tree-mutation tripwire | Tests write only to `target/`; CI runs `git diff --exit-code` |
| `classpath-convergence.sh` | Enforcer `dependencyConvergence` + `bannedDependencies` |
| `version-report.sh --check` | One version property, plus a test that checks it against the release's own POM |
| Three-stream scheduler | `mvn -T1C`, and surefire/failsafe `forkCount` — a bet on memory, not a translation, and budgeted in phase 1 |
| Heap set by CI env vars | `.mvn/jvm.config` and an explicit surefire `argLine` |
| `-Dh2.version=2.4.240` on gate 7's command line | The version in the POM, on that lane's execution (§4.4) |
| `-Dpct.reuseForks=false`, passed by CI only | One fork per PCT suite everywhere, as the POM's default (§4.7) |

### 4.4 Lanes are executions, not invocations

The DuckDB and H2 lanes are the same tests under a different switch
(`-Drcorpus.backend=h2`, `-Dstress.backend=h2`, and `LEGENDLITE_PCT_BACKEND=h2`, which is
an environment variable). Today that means running Maven twice from a script and comparing
logs. In the target build each lane is a failsafe **execution** with its own
`systemPropertyVariables` (or `environmentVariables`, until the PCT switch becomes a
property), its own `reportsDirectory`, and its own `summaryFile`. `mvn verify` runs both,
reports both, and fails if either fails. Gates 4/5, 6/7, and the two stress lanes collapse
into executions. The H2 stress lane is in no gate today — gate 10 runs the DuckDB lane
only — so as an execution it is new coverage, at about two minutes.

One lane differs by more than a switch. Gate 7 runs the PCT relation suite on H2 2.4.240
by passing `-Dh2.version=2.4.240` on its command line, while `pct/pom.xml` pins 2.1.214,
and a module resolves one version of a dependency for all of its executions. That
execution swaps the jar on its own test classpath (failsafe's `classpathDependencyExcludes`
plus `additionalClasspathDependencies`), or the lane becomes a module of its own. Either
way the version moves into the POM.

**A second axis is on its way.** The judging program makes the judge a run-level switch,
`-Dlegend.judge.mode`, read once per JVM (only `host` exists today; leg 3.1 of
`docs/DATABASE_MODE_HOMEWORK_2026_09_18.md` adds `database`), and commits to a permanent
**differential gate**: the corpus and stress lanes run in both modes, and every assertion
must get the same verdict from each (`docs/JUDGING_TWO_MODES_2026_09_17.md` §4). The
homework specifies it as per-assertion verdict files, one per mode per lane, keyed by test
and statement, compared by a gate that pins zero disagreements (D7). That fits this
section's model with one addition: a lane becomes backend × mode executions, each writing
its verdict file to `target/`, and one last execution in the same module compares them. A
module's executions run in the order its POM declares them, so the comparison needs no
script. Built this way when leg 3.3 lands, the gate is already in the target shape and
phase 3 only wires it; built as a new stream in `tools/allgates.sh`, it is one more thing
phase 3 has to port.

### 4.5 Ratchets become data

This is the single most important change for working concurrently. Today most
expectations are scalars in code, of three kinds:

- **pass floors** — `MIN_PASS` and `MIN_PASS_H2` in `StressServiceSuitesTest`, `G7_MIN_RUN`
  in `tools/allgates.sh`;
- **census ceilings** — the oracle-declined and spelling ceilings in `MinimalCorpusTest`,
  `MAX_INT_NULL_EMPTY` in the PCT census;
- **source-size pins** — `JavaEvalLedgerTest`'s line count for each file that evaluates in
  Java.

Whoever moves a number edits it, and the numbers move constantly. Between 2026-09-11 and
2026-09-18 the stress floors changed in nine commits (they now read 4,700 and 4,622), and
fifteen edited `JavaEvalLedgerTest` — seven of the ten from `adbc284ec` to `c062b9bc9`
alone, its `AssertVerdicts` pin going 1831 → 1840 → 1822 → 1823 → 1827 → 1842. Two
developers improving different things both edit the same line, and the merge is silent
about which improvements survived. A count cannot say which rows it counts:
`oracle-declined` went 28 → 39 for "eleven helper-shaped plan asserts", described by family
in a comment, and a different eleven would satisfy the same number. Even the provenance
drifts: `MIN_PASS`'s history comment still ends at 4,689 while the constant reads 4,700.

The corpus lanes already have the answer. `h2-fail-roster.txt` and the unordered-chain
registers are one row per test, compared as exact sets. When host-only judging changed how
one H2 test passed, on 2026-09-18, gate 5 went red naming that test, and the fix was one
register row with its reason. Extend that shape to every scalar above: a committed file of
rows — one row per expected-failing test or suite, or per counted case, with its reason —
and assert set equality:

```
corpus/src/test/resources/expected/stress-duckdb.tsv
pct/src/test/resources/expected/relation-h2.tsv
```

A new failure names itself. A fixed test fails with "remove this row". Two developers who
fix different tests touch different lines and git merges them correctly. Every run writes
its candidate to `target/`; `-Dledger.update` accepts it into the committed file, which is
the one deliberate act a human performs (§4.8). New expectations should be born in this
shape; the judging program's per-mode unjudged lists (homework D3) are the next due.
Whether the source-size pins should exist at all is a separate question (§9).

### 4.6 Portability, spelled out

| hazard | the fix |
| --- | --- |
| CRLF rewriting the `.pure` corpus | `.gitattributes`: `* text=auto eol=lf` for ordinary sources, and **`*.pure -text`** so git never converts a corpus file in either direction. The exception is load-bearing: `prelude.pure` is stored with mixed endings because upstream's text carries CRLF, and a blanket `eol=lf` would renormalize it and break the byte-exact comparison it exists for. Phase 4 stops committing `prelude.pure`, which retires that reason; phase 4 then decides whether the exception stays for the byte-exact `.pure` fixtures or becomes `text eol=lf`. CI then stops configuring git. |
| Deep upstream paths on Windows | Read inside the zip archives; never unpack them. |
| Locale-dependent case and formatting | Pin `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8` beside the existing `-Duser.timezone=GMT`, in one place. |
| Shell-only tooling | Deleted, not ported. `mvnw.cmd` is the Windows entry point. |
| `/tmp`, `mktemp`, `id -un`, fixed report paths | Gone with the scripts; per-execution `reportsDirectory` under `target/`. |
| arm64 vs x86_64 floating point | Already one declared, counted policy with one home: a 2-ULP leniency in `com.legend.exec.Equality`, and `VerdictChannelRegisterTest` fails if `Math.ulp` appears anywhere else. Database mode will add its SQL twin (homework D4), and the differential gate (§4.4) holds the two to the same answers. Never a platform `if`. |
| Case-insensitive filesystems | A guard test asserting no two committed paths differ only by case. |
| JDK differences | `maven.compiler.release=21`, Enforcer `requireJavaVersion [21,)`, and a CI matrix that actually runs 21 and 25. |

Beyond the three platforms CI can run, the build is pure Java and Maven, so it runs
anywhere a JDK 21+ does. The real limit is the native JDBC drivers: DuckDB and SQLite
ship binaries for a fixed set of platform/architecture pairs, and a platform outside that
set can run everything except the tests that execute SQL. That is worth stating in
`CONTRIBUTING.md` rather than discovering — and it is an argument for keeping the H2 lane
(pure Java) healthy, since it is the one that runs everywhere.

### 4.7 CI

Three workflows replace three plus a composite action.

**`build.yml`** — on every push and pull request:

```
strategy:
  matrix:
    os:  [ubuntu-latest, macos-latest, windows-latest]
    jdk: [21, 25]
run: ./mvnw -B verify
```

Six jobs, every one of them the same command a developer runs. Plus the `actionlint` job
the project already has; it lives inside `gate.yml` today and moves into `build.yml` before
phase 3 deletes that file.

**`release.yml`** — on a tag: `./mvnw -B deploy -Prelease`.

**`corpus-generators.yml`** — on a schedule: re-run the Python corpus generators and fail
if the committed output moved (§4.8). It is the one workflow that needs Python, which is
why it is not part of `verify`.

The `verify` job on `ubuntu-latest / 21` is the required check for merging. The matrix is
the only thing CI adds that a laptop cannot do.

**Every cell is exactly `./mvnw verify`, the small one included.** The macOS runner has
3 vCPU and 7 GB, and a PCT suite's 2–3 GB live set means one fork per suite there. Today
CI buys that with a flag of its own, `-Dpct.reuseForks=false`; in the target build it is
the POM's default everywhere, at a measured cost of 11–15 s, so no cell passes anything a
developer does not.

**On `-latest` rather than pinned images.** The reason to build on macOS and Windows is
that people develop there, and they keep their machines roughly current. A pinned image
tests a configuration nobody has, drifts further from the developers every month, and gets
removed by GitHub in the end anyway — so pinning buys a delay, not an escape. `-latest` is
not a cliff either: the label rolls over gradually and the old image warns in the logs
first.

What is worth holding still is the **architecture** axis, which is where this project has
already been bitten: DuckDB's `percentile_cont` returns a different double on x86_64 than
on arm64. `macos-latest` is arm64 and the other two legs are x86_64, so the matrix covers
both by construction — that should be stated in a comment, because it is the reason the
macOS leg exists, and an Intel macOS leg can be added if that specific combination ever
matters. If an image rollover does redden the required check one day, pin that one job and
keep an unpinned nightly as the early warning; do not pin the matrix by default.

### 4.8 Generated by the build, or committed — and why

**The rule: anything the build can derive from inputs it already has is generated on every
build and committed nowhere. Only what exists to be compared against is committed.** A
baseline regenerated from the thing it measures asserts nothing — it compares a file to
itself.

| artifact | today | target | why |
| --- | --- | --- | --- |
| `prelude.pure` (7,009 lines, 293 KB) | committed under `core/src/main/resources`; a `spec` test writes it into `../core` | **generated** into `target/generated-resources` before `core` compiles | A function of upstream's platform sources plus a committed exclusion list. `core` already reads it off the classpath at runtime — which is exactly what a generated resource is. |
| The signature text inside `Pure.java` | committed; a `spec` test rewrites the block between markers in a hand-written file | **generated** source; the membership list stays committed | The text is upstream's. A half-generated Java file cannot be a build output, so the file splits: our list of what we claim, their text for each claim. |
| The DynaFn registry members | a region of a hand-written Java source, patched by a test | **generated** source | A mirror of upstream's registries. |
| `CORE_IMPORTS` | a Java constant patched by a test | **generated** constant | A mirror of upstream's `CompileContext.META_IMPORTS`. |
| `native-claims.tsv` (829 rows) | committed; its own header says "the diff is the review" | **committed** | Measured from our own code: one row per `Pure.java` overload, with the `core` classes that claim it. Generating it each build would compare it with itself; its entire job is to make a change in the implemented surface visible in a pull request — as it did when a typer change (`928451d67`) added `NumberKinds` to twenty rows. |
| `native-membership.tsv` (787 rows) | committed, mixed | **splits** | Membership is a decision we make; the signature text beside it is upstream's. |
| Expected-failure ledgers, censuses, rosters, corpus scoreboards | committed; several rewritten in the working tree by a test run | **committed**, with the candidate written to `target/` and compared | Same as the claims ledger: they exist to be compared against. |
| The stress corpus's expected answers | committed | **committed** | Computed independently by the same Python generators as the sources (next row), then checked against real legend-engine by `scripts/corpus/run.py` through `tools/engine-runner` — about an hour, with `0 unexpected` the only acceptable result. A build cannot contain its own oracle. |
| The stress corpus's generated sources (~487k lines) | committed, produced by Python | **committed** | Producing them needs Python 3.12 and about ten minutes. Requiring a second toolchain on every developer's machine costs more than it buys; a scheduled job re-runs the generators and fails if the output moved. |
| Snippets harvested from upstream's test sources | read from a checkout | **committed**, refreshed at bump time (§4.2) | Deriving them each build is the only thing that would make the build reach past Maven; committing them removes the last non-artifact input and makes an upstream change reviewable. |
| Parser fixture adjudication | committed | **committed** | Which construct is a legal positive and which a deliberate negative is a judgment, not a derivation. |

Three things make build-time generation impossible today. None is a fact about the
problem; each is a consequence of where code was put.

1. **The inputs are outside Maven.** A generator cannot run in a build whose build tool
   cannot fetch what it reads. Phase 2 fixes this, and is a prerequisite for phase 4.
2. **The output is patched into hand-written files** — and into *another module's source
   tree*: `spec` writes `../core/src/main/...` through a `CoreTree` helper whose own
   javadoc describes the arrangement. A file that is half hand-written can never be a
   build output.
3. **The generators live in `spec`, which depends on `core`,** while their output is an
   input to `core`. The cycle is where the generators sit, not something inherent; a
   generator module that depends only on the upstream artifacts breaks it.

One thing is genuinely lost, and it should be said plainly: today an upstream bump shows
roughly 690 KB of generated diff in the pull request, and someone can read it. Generated
at build time, it shows nothing. The review signal moves to the committed ledgers — which
claims moved, which expected-failure rows appeared — which is a smaller diff carrying more
meaning. That is an improvement, but it is a real change in what a reviewer sees, and the
bump procedure should say so.

---

## 5. The phases

No phase carries a duration, because effort is not what sets this project's calendar. It
is built by one developer directing AI agents: every one of the 139 commits on `main`
between 2026-09-11 and 2026-09-18 is co-authored by Claude. Each phase instead ends with
what gates it: the **decisions** it needs from the people who own the project, the
**verification** its exit needs (CI runs and their re-runs, which cost machine time rather
than effort), anything **outside** the project's control, and the **coordination** it needs
with work landing on `main` at the same time. Every phase assumes phase 0's decisions have
been made.

One calibration point, and it is only one. The upstream boundary program
(`docs/UPSTREAM_BOUNDARY_PROGRAM.md`) made the same kinds of change this plan makes — a new
module, a groupId rename, Enforcer and ArchUnit bans, generators for `core`'s
upstream-derived facts, a scripted bump. It went from its plan (`c5121c020`, 2026-09-10
12:06) to a release bump through `tools/bump.sh` (`0a4a928c6`, 2026-09-11 23:20) in about
35 hours and 42 commits, 33 of them its own. If that pace holds, the implementation this
plan describes is days to a couple of weeks of calendar time. That is an extrapolation from
one program, not a measurement, and none of the gates below shrinks with it.

### Phase 0 — Decide how `main` is protected *(decision, not engineering)*

Nothing below survives if `main` keeps taking direct pushes: a build fixed on Monday is
stale by Friday, and a green `verify` means nothing if most commits never ran it.

- Every change lands through a pull request. Required check: `./mvnw verify`. Branch
  protection on. This applies to agent-authored work identically.
- A declared public surface (`com.legend.Compiler` and the HTTP API) and versioned
  releases, so other work can depend on something that holds still.
- `CODEOWNERS`, and a stated review expectation.
- Run records move into the pull request. `docs/GATES.md` stops taking a paragraph per
  change: it is the file most commits touch, so any two pull requests in flight conflict
  at its end (§6). Its gate definitions stay until phase 3 replaces the gates.

**Gated by:** decisions only, and all of them the owners': the protection itself, the
public surface, the review expectation, and the change of habit the last bullet asks for.
There is nothing to build.

**Exit:** branch protection enabled and a required check configured, even if that check
is initially only "compiles".

### Phase 1 — A fresh clone is green

- Replace `maven.compiler.source`/`target` with `maven.compiler.release=21`. Add Enforcer
  `requireJavaVersion [21,)` and `requireMavenVersion` at the root, so an unsupported
  toolchain fails on the first line with a sentence rather than a stack trace three minutes
  in. These rules, not the wrapper, are what make the requirement real. Verify NullAway and
  Error Prone on both 21 and 25 (the JSpecify workaround for 21 is already in
  `core/pom.xml` and needs a run to confirm).
- Add the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) as a convenience for anyone
  who would rather not install Maven, and `.mvn/jvm.config` for the Maven JVM heap. An
  installed `mvn` of a supported version stays a first-class way to build.
- Add `.gitattributes` — `* text=auto eol=lf` with `*.pure -text` (§4.6: the exception is
  what keeps `prelude.pure`'s mixed endings intact) — and `.editorconfig`. Confirm with
  `git ls-files --eol` before and after that exactly the four known exceptions remain.
- **Measure the full suite, in wall clock and in resident memory**, before anything
  depends on the answer: every lane the gates run today plus the H2 stress lane, serially
  and under `-T1C`, on a laptop-sized machine and on the CI runners. Measure each quiet,
  with no stray JVMs or IDE builds running: the same chain has measured 7m24s beside a
  day's leftover JVMs and 4m09s without them (`docs/GATES.md`, 2026-09-17). §6 weighs the
  project's four-minute budget against the result, and phase 3's parallelism is a bet on
  memory that this measurement settles.
- Fix the duplicate `provision` enum value in the `nlq` test model.
- Tag every test that needs an upstream source checkout, exclude that tag by default, and
  convert its skips into failures. This is temporary scaffolding that phase 2 removes —
  its purpose is to make "green on a clean clone" true immediately.
- Centralize every plugin and library version in the root POM's `pluginManagement` and
  `dependencyManagement` (H2, Enforcer, Shade, Error Prone, NullAway are repeated across
  child POMs today).
- Rewrite `README.md` for what exists: what the project is, the three commands, the module
  map. Add `CONTRIBUTING.md`, `LICENSE`, and `NOTICE` for upstream-derived files.
- CI: add `build.yml`, running `./mvnw -B verify` across
  `{linux, macos, windows} × {21, 25}` with whatever is green so far, **beside** the gate
  workflows. They keep running until phase 3's exit shows `verify` reproduces them;
  replacing them now would take the corpus lanes, the H2 PCT lane, Channel B, and parser
  parity out of CI for two phases. Make one fork per PCT suite the POM's default (§4.7),
  so no cell needs a flag.

**Gated by:** *decisions* — the toolchain range Enforcer will hold (JDK 21 and 25, a Maven
floor), and the `LICENSE` and `NOTICE` text for upstream-derived files, which is the
owners' to confirm. *Verification* — the six-cell matrix green, JDK 21's NullAway run
included, and the measurement above, taken quiet on a laptop-sized machine and on the
runners. *Coordination* — light: every POM changes once.

**Exit:** a newcomer on any of the three platforms clones, runs `./mvnw verify`, and sees
green — and CI proves it on six configurations.

### Phase 2 — Maven owns the inputs

- Re-measure that the release jars' `.pure` text is byte-identical to the source tree at
  the current pin; §4.2's measurement was at 5.92.0.
- Read legend-pure's (and legend-engine's published) `.pure` spec text inside the release
  jars via `dependency:copy` plus a zip `FileSystem` helper. Keep the `core` boundary
  intact: jars as files, never on a classpath.
- For `parser-equivalence`, commit the snippets harvested from upstream's test sources,
  and write the harvester that refreshes them from the tagged source archives at bump
  time. After this the build reaches the network for Maven artifacts and nothing else.
- Delete `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the root POM's
  `legend.engine.root` / `legend.pure.root` properties and their env-activated profiles,
  and the composite action's checkout steps.
- Add the derived-version test: `<legend.pure.version>` must equal what the pinned engine
  release's own POM declares.
- Remove the phase-1 exclusion tag. Those suites now run by default, everywhere.

**Gated by:** *verification* — the byte identity re-measured at the current pin, then a
green run from an empty `~/.m2` with no checkouts, on every cell. *Outside* — upstream's
releases: a new pin mid-phase means a new harvest, and a release is usable only once it is
on Maven Central (`tools/bump.sh` records a tagged release that was not). *Coordination* —
light: three modules change where their inputs come from, not what they test.

**Exit:** a machine with an empty `~/.m2` and no checkouts runs `./mvnw verify` green, and
the word "checkout" appears nowhere in the build.

### Phase 3 — Maven owns the verdict

- Rename the long suites to `*IT` and bind them to failsafe; surefire keeps the unit
  tests. `mvn test` becomes the inner loop by convention, not by flag.
- Turn each backend lane into a failsafe execution with its own properties and reports,
  adding the H2 stress lane that no gate runs today and giving gate 7 its own H2 jar
  (§4.4).
- Convert every floor and ceiling into a row ledger (§4.5), and whatever source-size pins
  survive §9's triage.
- Make a missing upstream input throw rather than assume past it, and keep
  `SkipCensusTest` as the rule for the skips that legitimately remain. Delete the walks
  over sibling directories that no longer exist (`ErrorShapeGuardrailTest` still walks
  `../engine`).
- Move every test write into `target/`; compare against committed baselines instead of
  rewriting them. Add the CI `git diff --exit-code` step.
- Replace `classpath-convergence.sh` with Enforcer rules, and `version-report.sh` with the
  derived-version test.
- Last, once the ledgers are data: move the nine cross-module guard tests into the new
  `guards` module, and the stress corpus and its runner into `corpus`, so `core`'s suite
  stays the inner loop (§8, risk 5).
- Delete `tools/allgates.sh`, `tools/diagnostics.sh`, `tools/corpus-both.sh`,
  `tools/ci-watch.sh`, `tools/classpath-convergence.sh`, `tools/version-report.sh`, and
  all three workflows: `gate.yml` and `gates-run.yml` once `build.yml` carries the
  `actionlint` job, and `diagnostics.yml` with `tools/diagnostics.sh`.

**Gated by:** *decisions* — four from §9, all needed before the lanes become executions:
the four-minute budget, `*IT` names or tags, which lanes gate pull requests, and the
triage of the ledger and census tests. *Verification* — the heaviest of any phase: each
converted lane must give its gate's verdict on the same commit, so every conversion costs a
chain run and a `verify` run side by side, on each platform. *Coordination* — the real
risk. The files holding the floors, ceilings, pins, and rosters this phase touches were
edited by 32 of the 139 commits between 2026-09-11 and 2026-09-18, and the phase moves the
stress corpus and nine guard tests between modules while that work continues. It lands as
small pull requests, one lane or one ledger at a time, or in a window agreed with whoever
runs the judging program; a long-lived branch will not survive the rebases.

**Exit:** `./mvnw verify` reproduces every gate the chain runs at the time — ten today,
plus the differential gate if leg 3.3 has landed (§4.4) — locally and in CI, with no shell
involved; a deliberately broken test fails it for the same reason the gate chain would
have.

### Phase 4 — Generation moves into the build

Execute §4.8: everything derivable becomes a build output, and what remains committed is
committed for a stated reason.

- **A `generator` module that depends on the upstream artifacts and on nothing of ours**,
  built before `core` in the reactor. It runs in `core`'s `generate-sources` phase —
  `exec-maven-plugin` with the generator and the upstream jars as *plugin* dependencies,
  which keeps them off `core`'s classpath, where Enforcer's `bannedDependencies` looks —
  and writes the prelude, the signature text, the dynafunction registry, and the import
  sequence into `core`'s own `target/generated-resources` and `target/generated-sources`,
  which `build-helper-maven-plugin` registers. No module reads another's `target/`. This
  breaks the `core` ↔ `spec` cycle by construction: the generator knows upstream, `core`
  knows the generator's output, and the ledgers that measure `core` sit downstream of both.
- **Split the three hand-written files that carry generated regions.** `Pure.java` keeps
  the membership it declares and loses the signature text; the dynafunction registry and
  the import constant become generated sources referenced by hand-written code. Delete
  `CoreTree` and every `../core` write with it.
- **Delete the committed copies** of everything in §4.8's generated rows, and the
  byte-parity tests that existed only to prove a committed copy was current. Those tests
  are made redundant by generation, not weakened by it. With `prelude.pure` gone, decide
  whether `*.pure -text` stays (§4.6).
- **One flow for baselines, not five.** Today each ledger has its own flag
  (`-Dprelude.generate`, `-Dnatives.generate`, `-Ddynafn.generate`, `-Dimports.generate`,
  `-Dclaims.generate`). Every remaining baseline writes its candidate to `target/` on
  every run and compares; `-Dledger.update` accepts them all.
- Bumping the upstream release becomes: change one version property, refresh the
  harvested test snippets (§4.2), run `./mvnw verify`, and where a baseline moved, accept
  it and review the rows. Delete `tools/bump.sh`.
- Bring `tools/engine-runner` into the reactor as `upstream-runner` so its upstream version
  and its dependency on `legend-lite-core` cannot drift.

**Gated by:** *decisions* — the owners accepting that a bump's review moves from ~690 KB of
generated diff to the ledger rows (§4.8), and whether `*.pure -text` stays.
*Verification* — one real upstream bump carried through the new flow end to end.
*Outside* — that bump needs a new upstream release on Maven Central. *Coordination* —
moderate: `Pure.java`, the dynafunction registry, and the import constant change shape
while feature work edits them (`Pure.java` alone was edited by 12 of the 139 commits).

**Exit:** no generated artifact is committed except the baselines §4.8 names, each with its
reason; `git grep` finds no test writing outside `target/`; moving to a new Legend release
is one property change and the snippet refresh §4.2 keeps at bump time.

### Phase 5 — The projects and the Python

- Make `projects` a module, and switch `corpus`'s eleven linked projects from
  `../projects` to a dependency on it (§4.1).
- Give `projects/` a test that compiles all 56 projects, individually and together, so the
  contract in `projects/CONTRACT.md` is enforced by the build rather than by a script.
- Draw the Python boundary explicitly, and justify it in §4.8's terms: the corpus sources
  are the one derivable artifact the plan still commits, because deriving them needs a
  second toolchain and ten minutes on every machine. **No developer needs Python to build
  or test.** A scheduled CI job re-runs the generators and fails if their output moved, so
  the committed copy cannot silently stop matching its generator.
- Port the generators to Java when someone has the appetite: that, and only that, would
  move the corpus sources from "committed with a reason" to "generated like everything
  else". It is not on this critical path, and the §4.8 row should be revisited if it lands.

**Gated by:** *decisions* — where the Python boundary sits, and whether the corpus stays in
this repository at all (§9). *Verification* — the 56-project compile test, and the first
runs of the scheduled generator workflow. *Coordination* — light.

**Exit:** every directory in the repository is either built by Maven or explicitly
declared maintainer tooling, with no third option.

### Phase 6 — Releases, and documentation a newcomer can use

- **Settle the namespace first, because the current one cannot be published.** Maven
  Central requires a `groupId` whose ownership the project can prove, and `com.legend` is
  not one it controls; the choice is a domain it does own, an `io.github.<owner>`
  namespace, or a FINOS one. In the same decision: `pct`'s `org.finos.legend.lite.pct`
  packages carry somebody else's organization in their name, so the rename is a release
  concern with a deprecation path, not a hygiene bullet.
- Semantic versions instead of a permanent `1.0.0-SNAPSHOT`; a tag-driven `release.yml`
  publishing `legend-lite-core` and the server jar, with a changelog built from PR titles.
- Mark everything outside the declared public surface internal.
- Archive `docs/`. Keep a handful of living documents — getting started, architecture,
  invariants, conformance testing, upstream bumps — and an `adr/` directory for decisions.
  Retire `GATES.md`; it stopped being a running log in phase 0, and history belongs in
  commits, PRs, and release notes.
- Replace the ad-hoc debug environment variables and `System.out` calls with a logging API.
- Remove `progress*.txt`, `progress/`, `experiments/`, and editor-specific directories from
  the repository root.

**Gated by:** *decisions* — the namespace, the `pct` package rename and its deprecation
path, the version policy, the public surface, and which six documents survive the archive.
*Outside* — more than any other phase: Maven Central verifies namespace ownership before
the first publish, a FINOS namespace runs on FINOS's process, and publishing needs signing
keys and repository secrets in place.

**Exit:** a new developer finds what they need in six documents, and another project can
depend on a released version without building legend-lite. The documents stay a standing
duty after it.

---

## 6. Working concurrently

A standard build is necessary but not sufficient. Six things decide whether five people
can work at once.

**Shared scalar ratchets are the main hazard.** They are the one construct guaranteed to
conflict, and to conflict silently in the direction of a false green. §4.5 is the fix, and
it should land early in phase 3.

**Shared append-only records.** `docs/GATES.md` takes a paragraph from most commits — 100
of the 139 (merges aside) between 2026-09-11 and 2026-09-18, and eight of the ten from
`adbc284ec` to `c062b9bc9`. Every paragraph lands at the end of the same file, so any two
pull requests in flight conflict there. The conflict is loud rather than silent, but it is
certain, and it grows with the number of people. Phase 0 moves the record into the pull
request; phase 6 retires the file.

**Cross-module coupling.** Today an edit in `pct` or `parser-equivalence` can fail `core`,
so two people working in different modules are not actually independent. The `guards`
module makes the dependency explicit and one-directional.

**Generated files in pull requests.** After phase 4 most of them are gone from the
repository entirely, which is the best answer to this problem. What §4.8 keeps — the
corpus sources above all — is marked in `.gitattributes` so diffs stay reviewable, is
never hand-edited, and moves in its own commit.

**Determinism.** Pinned clock, locale, encoding, and heap; per-execution report
directories; no fixed temp paths. Any test that can fail intermittently — native database
drivers under forked JVMs are the known source — gets a quarantine tag and a ticket, never
a retry loop that hides it.

**Cadence, and what it actually costs.** With a required check on every pull request,
`verify` has to stay fast enough to run on every one — and the plan's promise that
everything lives in the default build is the claim most likely to break here, so it should
be held to evidence.

What is measured today: a `verify` that omits the corpus lanes, the ChannelB suites, the
H2 relation lane, the H2 stress lane, and the parser sweep against a full corpus takes
8 min 37 s serially (Appendix A). The gate chain — core's clean compile and all ten gates —
measured 408 s serially and 249 s as three parallel streams on a quiet developer machine
(`docs/GATES.md`, 2026-09-17), and between 4m09s and 4m34s in parallel on every recorded
chain since. Adding up the per-gate times of a parallel run gives ten minutes and more, but
those times are inflated by the other streams; alone, every gate ran faster. So on a
developer's machine the promise does not depend on parallelism: serial is under seven
minutes.

Two things can still break it. The CI runners are smaller: the macOS one has 3 vCPU and
7 GB (`pct/pom.xml`), where one PCT suite's 2–3 GB live set is already a third of the
machine and `-T1C` multiplies the heavy JVMs by the number of cores — so on the runners the
budget is a **memory** budget before it is a time budget, and phase 1 measures both. And
the set is growing: the H2 stress lane, which no gate runs today, is about two minutes, and
the differential gate (§4.4) adds database-mode runs of the corpus and stress lanes, of
which the homework prices the DuckDB corpus run alone at about 100 s.

The project also already has a time budget: four minutes for the parallel chain, set on
2026-09-16 (`docs/STRESS_CORPUS_THROUGH_LITE_2026_09_16.md`, F-R). It already decides what
runs — the H2 stress lane stays out of the chain because the budget cannot carry it
(F-AA) — and the differential gate is to be priced against it. It and "everything in
`verify`" cannot both hold as lanes join; §9 asks which gives.

If the full set will not fit a pull-request check, the order of retreat is: raise
parallelism within a measured memory ceiling; then run the full set on merge with a
sampled corpus on pull requests; then split the matrix (everything on Linux/21, a smoke
subset elsewhere). Weakening the suite is not on the list. A merge queue keeps two
independently-green pull requests from landing a broken combination.

---

## 7. What gets deleted, and what replaces it

| deleted | lines | replaced by |
| --- | --- | --- |
| `tools/allgates.sh` | 471 | `mvn verify`: surefire/failsafe executions, tags, `-T1C` |
| `tools/bump.sh` | 313 | One version property; the build generates, `-Dledger.update` accepts |
| `tools/version-report.sh` | 270 | One property + a derived-version test |
| `tools/classpath-convergence.sh` | 86 | Enforcer `dependencyConvergence`, `bannedDependencies` |
| `tools/oracle-roots.sh` + `tools/oracle-pins.env` | 119 | Maven-resolved jars and checksummed source archives |
| `tools/diagnostics.sh` | 47 | A tagged failsafe execution (or deletion — they are measurements) |
| `tools/corpus-both.sh` | 11 | Two failsafe executions |
| `tools/ci-watch.sh` | 18 | `gh run watch` |
| `.github/workflows/gate.yml`, `gates-run.yml`, `diagnostics.yml` | 377 | One `build.yml` matrix job running `./mvnw verify`, which also takes over the `actionlint` job |
| `.github/actions/gate-env` | 58 | `actions/setup-java` with `cache: maven` |

About 1,770 lines of shell, pins, and YAML, replaced by POM configuration and test code
that runs identically on a laptop.

---

## 8. Risks

1. **Will `main` be protected?** Phase 0 is a decision, not a task, and every later phase
   depends on it. This is the largest risk in the plan.
2. **Does the whole suite fit in a pull-request check?** On a developer's machine, very
   likely, even serially: the gate chain runs in under seven minutes there. On the CI
   runners nobody has measured it, memory binds before time, and the set is growing (§6).
   §6 says what to measure and in what order to retreat; phase 1 does the measuring.
3. **Does the NullAway and Error Prone configuration hold on JDK 21 and 25?** It carries a
   JDK-21-specific workaround already; phase 1 must run both.
4. **Renormalizing line endings.** The index is already clean (§2.2), so `.gitattributes`
   is nearly a no-op — but `prelude.pure`'s mixed endings are exactly the case a careless
   rule would break, and phase 1 verifies the before-and-after with `git ls-files --eol`.
5. **Two modules moving (`corpus` out of `core`, `guards` out of everywhere)** touch many
   files at once. They are cheapest once the ledgers are data and the verdict is Maven's,
   which is why both close phase 3.

---

## 9. Open decisions

These are not build problems with a right answer. Each one is a question about what the
project is, and each should be settled by the people who own it before the phase that
depends on it.

**Should the ~52 ledger, census, guardrail, and roster tests exist?** Phase 3 relocates the
nine that read other modules into `guards`, which answers *where* they live without ever
asking *whether* they should. A test that reads another module's Java source to enforce a
convention is usually a linter rule wearing a test's clothes, and Checkstyle, Spotless, or
ArchUnit would give a better message for less code — though ArchUnit sees only classes on
its classpath, and other modules' test classes cannot get there through a test-jar (§4.1),
so rules over test trees stay source scans. The triage — real behavioral ledger,
linter rule, or delete — belongs in phase 3, and it may be the difference between a module
worth having and a module that preserves a cost nobody examined. The first candidate is
`JavaEvalLedgerTest`: it pins a line count for each file that evaluates in Java, it was
edited by seven of the ten commits from `adbc284ec` to `c062b9bc9`, and two people changing
the same file always conflict on its pin. Not every guard is a candidate, though:
`VerdictChannelRegisterTest` now enforces a design rule — one Java class decides every
equality (`docs/JUDGING_TWO_MODES_2026_09_17.md` §5) — so for it the question is form, not
existence.

**Is the module shape right?** The plan adds `generator`, `corpus`, `projects`, `guards`,
and `upstream-runner` to the existing five without questioning the five. After phase 4,
`spec` is a leftover: its generators have moved to `generator`, and what remains is a
corpus harness and the claims ledger, which may belong with the other conformance modules.
Separately, `nlq` calls an external LLM and key-gates ten of its tests; whether an
LLM-backed module belongs in the same reactor as a clean-room compiler is a product
question, not a build one. Ten modules by accretion is the default outcome if nobody
decides.

**Should the corpus live in this repository at all?** §4.8 asks only whether the build can
derive it, and answers no. The unasked question is whether ~487k lines belong in the tree
that every clone, every IDE index, and every `git log` pays for. Publishing it as a
versioned artifact the build resolves would cost nothing per developer and version
cleanly; porting its generators to Java would move it out of §4.8's committed rows
entirely. Either beats the status quo, and neither is free.

**Does the four-minute budget survive?** The project's parallel chain has a four-minute
budget (2026-09-16), and it already decides what runs: the H2 stress lane stays out because
the budget cannot carry it, and the differential gate will be priced against it. This plan
puts everything in `verify`. They cannot both hold as lanes join. Raise the budget, set it
per machine class, or let it decide what runs on pull requests with the rest on merge
(§6's retreat) — the project's call, to make before phase 3 turns the lanes into
executions.

**Four smaller ones, each a genuine trade:** renaming the long suites to `*IT` buys the
standard surefire/failsafe convention at the price of rewriting every command habit and
doc reference that names them — tags plus failsafe includes get the same behavior with no
churn. The shade plugin builds a 97 MB server jar on every `verify`; it probably belongs
in `package` or a release profile rather than the inner gate. Running both database lanes
on every pull request — and, once database mode exists, both judge modes on each — is
inherited, not reasoned: one gating and the other on merge is a legitimate option nobody
has priced. And upstream bumps are manual by design, but a Renovate or Dependabot pull
request — red on the ledgers, as it should be — costs nothing and turns "someone remembers
to look" into a notification.

---

## Appendix A — measurements behind section 2

Taken on `main` at `f659d747a`, ten commits before the state section 2 describes, on
Windows 11, JDK 25 (Temurin 25.0.4.1), Maven 3.9.14, offline against a warm local
repository, with no `legend-engine` / `legend-pure` checkouts present — the state a
newcomer's machine is in. Every number below is at that commit unless it says otherwise.

Command: `mvn -o clean verify -Dmaven.test.failure.ignore=true`. The flag keeps every
module running past its failures so each one reports. Total wall clock **8 min 37 s**.

| module | tests | fail | error | skip | wall | why it is not green |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `core` | 4,461 | 0 | 0 | 16 | 4:52 | green |
| `spec` | 21 | 2 | 3 | 5 | 0:08 | five tests fail or error on the missing checkouts, and five more skip silently for the same reason |
| `nlq` | 208 | 0 | 1 | 10 | 0:14 | the duplicate enum value; the ten skips need an API key |
| `pct` | 1,255 | 0 | 5 | 0 | 2:51 | only the five ChannelB suites, which need the checkouts; the 1,250 jar-based PCT tests pass |
| `parser-equivalence` | 49 | 10 | 2 | 0 | 0:32 | corpus floors and rosters measured against a starved corpus; the other 37 pass |

Every one of the 23 non-passing results traces to one of two causes: the absent
checkouts, or the `nlq` enum.

One detail worth pulling out, because it is the whole problem in miniature. `mvn verify`
runs the full stress corpus: inside `core` it reports `pass=4689 fail=31 skipped=16 of
4736` (4,700 and 20 by `c062b9bc9`) and takes 158 s of that module's 4:52.
`StressServiceSuitesTest` is tagged `stress`, but `core/pom.xml` excludes only the `heavy`
group, so the tag is honoured by `tools/allgates.sh` and by nothing else. The build and the
gate disagree about what the default suite is, and the script is the one that is right.

| | |
| --- | --- |
| Shell in `tools/` | 1,285 lines across 8 scripts |
| Workflow YAML | 377 lines across 3 workflows, plus a composite action |
| Gates | 10, with pass/fail policy in `tools/allgates.sh` |
| Markdown at the top of `docs/` | 241 files |
| Java sources | 1,081 (672 in `core/src/main`) |
| Test classes | 311 (`core` 249, `parser-equivalence` 29, `spec` 14, `nlq` 13, `pct` 6) |
