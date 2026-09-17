# A standard build for legend-lite

**The goal.** A developer who has never seen this repository clones it, runs one
command, and gets a verdict — on Windows, macOS or Linux, on any supported LTS JDK,
with nothing installed but a JDK and git. A team of developers can then work on it at
the same time without stepping on each other.

**The means.** Maven does all of it. Not Maven plus a shell script that reads Maven's
console output; not Maven plus two source checkouts the developer has to clone by hand;
not Maven plus a Python toolchain. `mvn` decides, and its exit code is the project's
verdict.

This document is the plan to get there from `main` as it stands. It is written in
phases, each with an exit condition you can observe. Section 7 lists every bespoke
script the plan deletes and what replaces it.

---

## 1. What "done" looks like

Three commands, each with one job, all runnable on every supported platform:

| command | who runs it | what it does |
| --- | --- | --- |
| `./mvnw test` | every developer, all day | Unit tests only. The inner loop. Minutes, not tens of minutes. |
| `./mvnw verify` | every developer before pushing; CI on every PR | **Everything.** Compiles, runs every suite including the conformance suites against the pinned upstream release and both database lanes, and fails if anything is wrong. The same command CI runs; the same verdict. |
| `./mvnw verify -Pregenerate` | a maintainer, moving to a new upstream release | Rewrites the committed generated files and ledgers so the diff can be reviewed as a pull request. |

Acceptance criteria for the programme as a whole:

1. A clean clone plus `./mvnw verify` is green. No checkouts, no environment variables,
   no scripts, no manual setup steps.
2. That holds on Windows, macOS (arm64 and x86_64) and Linux, on JDK 21 and JDK 25.
3. Every input the build reads is resolved by Maven and version-locked in the POM.
4. No test is ever skipped for a missing input. A missing input fails the build.
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
| **No wrapper** | There is no `mvnw`/`mvnw.cmd` and no `.mvn/`. `.sdkmanrc` pins `java=25.0.1-tem`, `maven=3.9.12`; SDKMAN does not run on Windows. The developer guesses a Maven version. |
| **The README describes a deleted module** | Its Quick Start runs `mvn -pl engine test`; the `engine` module no longer exists. Its test counts (1,713 for `core`) are years of work out of date. |
| **The instructions are agent documents** | `README.md` sends you to `AGENTS.md` ("read by AI coding assistants"), `core/README.md` and `docs/GATES.md`, which is 3,782 lines of dated work records. There are 241 Markdown files at the top level of `docs/` and no index. |
| **Two checkouts, pinned by SHA** | `spec`, `pct` and `parser-equivalence` read full source trees of `finos/legend-engine` and `finos/legend-pure` through `-Dlegend.engine.root` / `-Dlegend.pure.root`, which must sit on the exact commits in `tools/oracle-pins.env`. Maven cannot fetch them. Without them some tests fail and others skip silently — which is why `allgates.sh` has a skip detector. |
| **Line endings are unguarded** | There is no `.gitattributes`. Git for Windows defaults to `autocrlf=true`, which rewrites the `.pure` corpus and breaks byte-exact parser parity. CI works around this by setting `core.autocrlf false` and `core.longpaths true` before every checkout. |
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
developer as "run the build".

**3. Generated files are committed, and their generators are tests.** `core` ships
`prelude.pure`, `native-claims.tsv`, `native-membership.tsv` and signature text inside
`Pure.java`, all derived from the pinned upstream release. The generators are tests in
`spec` switched on by flags, and they rewrite files in the working tree. Moving to a new
upstream release is a 313-line script (`tools/bump.sh`), not a build step.

**4. Module boundaries are not real.** Eight `core` test classes read other modules'
sources by relative path (`ParserBoundaryArchTest`, `HarnessDisciplineTest`,
`JavaEvalLedgerTest`, `JdbcSurfaceCensusTest`, `LegacyReachbackCensusTest`,
`SkipCensusTest`, `VerdictChannelRegisterTest`, `DiagramServiceTest`), and 43 `core`
test files reference paths under `docs/` or `scripts/`. An edit in `pct` can turn `core`
red, and a new test in `core` can turn `parser-equivalence` red.

### 2.4 What is already right, and must survive

- The product builds, and `core`'s suite is large, fast and green.
- Real enforcement, already in Maven: ArchUnit layer rules, NullAway on every clean
  compile, an Enforcer ban on upstream artifacts inside `core`.
- Differential testing against the real Legend — the PCT suites, the relational corpus,
  byte-exact parser parity, and the stress corpus — is this project's best asset. The
  plan moves it into the build; it does not weaken it.
- CI already runs on Linux, macOS and Windows. Only the JDK axis is missing.
- The test clock is already pinned (`-Duser.timezone=GMT`) — the right instinct,
  generalised in phase 1.
- Three runtime dependencies in `core`: DuckDB, SQLite, H2 drivers.

---

## 3. Rules the plan follows

1. **Maven decides.** If a condition can fail the project, it is expressed as a test
   assertion or an Enforcer rule, never as a grep over console output.
2. **Maven owns every input.** Anything a test reads is a Maven artifact resolved from a
   version property, or a file committed in this repository.
3. **A missing input is a failure.** No `Assumptions.assume*`. A suite that cannot run
   says so loudly.
4. **Tests write to `target/` only.** Regeneration is an explicit, separate mode.
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
mean maintaining rules for the Pure PAR generation plugin, the PCT framework and the
shaded server jar, and it would put a `WORKSPACE` between a newcomer and their first
build. Maven is also what the upstream projects this one tracks are built with. Staying
is the cheapest way to get the properties we want.

**Nothing is CI-only.** Every check in this plan runs from a laptop with one command.
Two things are deliberately not in `./mvnw verify`:

- **Regeneration** (`-Pregenerate`) is a mode that rewrites files, not a test. It runs
  locally on demand, and in CI on a pull request that bumps the upstream release.
- **The matrix itself.** Running six OS/JDK combinations at once is a property of having
  six machines, not of the build. Each cell is exactly `./mvnw verify`; a developer runs
  the cell they are sitting in.

Everything else — the conformance suites, both database lanes, the stress corpus, the
parser differential, the generator verification — is in the default build on every
platform.

### 4.1 One reactor

```
legend-lite (root pom: versions, plugin management, enforcer rules)
├── core                  the compiler and server      (no upstream dependencies)
├── nlq                   natural language → Pure
├── spec                  generators + their verification
├── pct                   legend-pure's PCT suites
├── parser-equivalence    differential parser tests
├── corpus                the stress corpus and its runner        (moved out of core)
├── projects              the 56-project graph, compiled by a test (new)
├── guards                repo-wide guard and census tests         (new, built last)
└── upstream-runner       today's tools/engine-runner              (in the reactor)
```

Two new modules earn their place:

- **`guards`** is where every test that reads another module's sources goes. It is built
  last and depends on everything. An edit in `pct` can then fail `guards`, which is true
  and reviewable, instead of failing `core`, which is neither.
- **`projects`** gives the 56-project Legend graph a test that compiles it, so it stops
  being unbuilt content in a Java repository.

`corpus` moving out of `core` keeps the inner loop short: `core`'s own suite stays the
thing a developer runs every few minutes, and 4,700 service suites become a module that
runs in `verify`.

### 4.2 Inputs Maven owns

The two source checkouts disappear, in two steps, because the two of them are not the
same problem.

**legend-pure, and most of legend-engine, is already published.** The `.pure` files the
tests read from `src/main/resources` are shipped inside the release jars — measured
byte-identical to the source tree at 5.92.0. `maven-dependency-plugin`'s `copy` goal
hands a module the jar *files* without putting them on a classpath (which matters:
`core` must resolve zero `org.finos.legend` artifacts, and Enforcer asserts it), and the
tests open each jar as a zip `FileSystem`. Paths inside a zip walk, resolve, relativize
and read exactly like a checkout's, so the calling code barely changes. The version lock
is the existing `<legend.pure.version>` property.

**What is genuinely unpublished is upstream's *test* sources** — `parser-equivalence`
mines Pure snippets embedded in legend-engine's and legend-pure's Java test files, and
neither project ships a `-test-sources` jar. For that one module, the build downloads the
two tagged source archives, verified against checksums committed here, and reads
**inside** the archive rather than unpacking it. Reading in place is not a nicety: it
keeps Windows clear of legend-engine's very deep paths, which is why CI has to set
`core.longpaths` today.

Net effect: `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the `legend.engine.root` /
`legend.pure.root` properties, the env-var profiles in the root POM, the composite
action's two checkouts and the skip detector all go away. One property,
`<legend.engine.version>`, names the release; `<legend.pure.version>` is derived from that
release's own POM and asserted by a test.

### 4.3 The verdict is Maven's exit code

| today | tomorrow |
| --- | --- |
| Gate ceilings in bash (`run>=469, fail<=1, err<=26`) | A committed expected-failure ledger; the suite asserts the observed failure set **equals** it |
| A 23-name roster so a renamed test cannot shrink a gate | The module runs whole; there is nothing to shrink |
| `skipped()` awk detector | `Assumptions` banned by an ArchUnit rule; missing input throws |
| Tree-mutation tripwire | Tests write only to `target/`; CI runs `git diff --exit-code` |
| `classpath-convergence.sh` | Enforcer `dependencyConvergence` + `bannedDependencies` |
| `version-report.sh --check` | One version property, plus a test that checks it against the release's own POM |
| Three-stream scheduler | `mvn -T1C`, and surefire/failsafe `forkCount` |
| Heap set by CI env vars | `.mvn/jvm.config` and an explicit surefire `argLine` |

### 4.4 Lanes are executions, not invocations

The DuckDB and H2 lanes are the same tests under a different property
(`-Drcorpus.backend=h2`, `LEGENDLITE_PCT_BACKEND=h2`, `-Dstress.backend=h2`). Today that
means running Maven twice from a script and comparing logs. In the target build each lane
is a failsafe **execution** with its own `systemPropertyVariables`, its own
`reportsDirectory` and its own `summaryFile`. `mvn verify` runs both, reports both, and
fails if either fails. Gates 4/5, 6/7 and the two stress lanes collapse into executions.

### 4.5 Ratchets become data

This is the single most important change for working concurrently. Today a scalar floor
(`MIN_PASS = 4679`, `MIN_PASS_H2 = 4607`, `G7_MIN_RUN=469`) is edited by whoever improves
the number. Two developers improving different things both edit the same line, and the
merge is silent about which improvements survived.

Replace each scalar with a committed file of rows — one row per expected-failing test or
suite, with its reason — and assert set equality:

```
corpus/src/test/resources/expected/stress-duckdb.tsv
pct/src/test/resources/expected/relation-h2.tsv
```

A new failure names itself. A fixed test fails with "remove this row". Two developers who
fix different tests touch different lines and git merges them correctly. `-Dledger.update`
rewrites the file for review, in the same explicit way regeneration works elsewhere.

### 4.6 Portability, spelled out

| hazard | the fix |
| --- | --- |
| CRLF rewriting the `.pure` corpus | `.gitattributes`: `* text=auto eol=lf`, plus `-text` on fixtures and binary payloads. CI then stops configuring git. |
| Deep upstream paths on Windows | Read inside the zip archives; never unpack them. |
| Locale-dependent case and formatting | Pin `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8` beside the existing `-Duser.timezone=GMT`, in one place. |
| Shell-only tooling | Deleted, not ported. `mvnw.cmd` is the Windows entry point. |
| `/tmp`, `mktemp`, `id -un`, fixed report paths | Gone with the scripts; per-execution `reportsDirectory` under `target/`. |
| arm64 vs x86_64 floating point | Already handled as a declared tolerance policy in the judges; keep it there, where both platforms can see it, and never in a platform `if`. |
| Case-insensitive filesystems | A guard test asserting no two committed paths differ only by case. |
| JDK differences | `maven.compiler.release=21`, Enforcer `requireJavaVersion [21,)`, and a CI matrix that actually runs 21 and 25. |

Beyond the three platforms CI can run, the build is pure Java and Maven, so it runs
anywhere a JDK 21+ does. The real limit is the native JDBC drivers: DuckDB and SQLite
ship binaries for a fixed set of platform/architecture pairs, and a platform outside that
set can run everything except the tests that execute SQL. That is worth stating in
`CONTRIBUTING.md` rather than discovering — and it is an argument for keeping the H2 lane
(pure Java) healthy, since it is the one that runs everywhere.

### 4.7 CI

Two workflows replace three plus a composite action.

**`build.yml`** — on every push and pull request:

```
strategy:
  matrix:
    os:  [ubuntu-latest, macos-14, windows-2022]
    jdk: [21, 25]
run: ./mvnw -B verify
```

Six jobs, every one of them the same command a developer runs. Plus one `actionlint` job,
which the project already has and should keep.

**`release.yml`** — on a tag: `./mvnw -B deploy -Prelease`.

The `verify` job on `ubuntu-latest / 21` is the required check for merging. The matrix is
the only thing CI adds that a laptop cannot do.

---

## 5. The phases

Sizes are rough engineer-weeks for one person fluent in Maven, and assume the phase 0
decision has been made.

### Phase 0 — Decide how `main` is protected *(decision, not engineering)*

Nothing below survives if `main` keeps taking direct pushes: a build fixed on Monday is
stale by Friday, and a green `verify` means nothing if most commits never ran it.

- Every change lands through a pull request. Required check: `./mvnw verify`. Branch
  protection on. This applies to agent-authored work identically.
- A declared public surface (`com.legend.Compiler` and the HTTP API) and versioned
  releases, so other work can depend on something that holds still.
- `CODEOWNERS`, and a stated review expectation.

**Exit:** branch protection enabled and a required check configured, even if that check
is initially only "compiles".

### Phase 1 — A fresh clone is green *(1–2 weeks)*

- Add the Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) pinned to the Maven version
  the project supports; add `.mvn/jvm.config` for the Maven JVM heap.
- Replace `maven.compiler.source`/`target` with `maven.compiler.release=21`. Add Enforcer
  `requireJavaVersion [21,)` and `requireMavenVersion` at the root. Verify NullAway and
  Error Prone on both 21 and 25 (the JSpecify workaround for 21 is already in
  `core/pom.xml` and needs a run to confirm).
- Add `.gitattributes` and `.editorconfig`.
- Fix the duplicate `provision` enum value in the `nlq` test model.
- Tag every test that needs an upstream source checkout, exclude that tag by default, and
  convert its skips into failures. This is temporary scaffolding that phase 2 removes —
  its purpose is to make "green on a clean clone" true immediately.
- Centralise every plugin and library version in the root POM's `pluginManagement` and
  `dependencyManagement` (H2, Enforcer, Shade, Error Prone, NullAway are repeated across
  child POMs today).
- Rewrite `README.md` for what exists: what the project is, the three commands, the module
  map. Add `CONTRIBUTING.md`, `LICENSE`, and `NOTICE` for upstream-derived files.
- CI: replace the gate workflows' entry point with `./mvnw -B verify` across
  `{linux, macos, windows} × {21, 25}`, running whatever is green so far.

**Exit:** a newcomer on any of the three platforms clones, runs `./mvnw verify`, and sees
green — and CI proves it on six configurations.

### Phase 2 — Maven owns the inputs *(2–3 weeks)*

- Read legend-pure's (and legend-engine's published) `.pure` spec text inside the release
  jars via `dependency:copy` plus a zip `FileSystem` helper. Keep the `core` boundary
  intact: jars as files, never on a classpath.
- For `parser-equivalence`, download the two tagged source archives with checksum
  verification and read inside them.
- Delete `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the root POM's
  `legend.engine.root` / `legend.pure.root` properties and their env-activated profiles,
  and the composite action's checkout steps.
- Add the derived-version test: `<legend.pure.version>` must equal what the pinned engine
  release's own POM declares.
- Remove the phase-1 exclusion tag. Those suites now run by default, everywhere.

**Exit:** a machine with an empty `~/.m2` and no checkouts runs `./mvnw verify` green, and
the word "checkout" appears nowhere in the build.

### Phase 3 — Maven owns the verdict *(3–4 weeks)*

- Rename the long suites to `*IT` and bind them to failsafe; surefire keeps the unit
  tests. `mvn test` becomes the inner loop by convention, not by flag.
- Turn each backend lane into a failsafe execution with its own properties and reports.
- Convert every ceiling and floor into an expected-failure ledger (§4.5).
- Ban `Assumptions`; make missing inputs throw.
- Move every test write into `target/`; compare against committed baselines instead of
  rewriting them. Add the CI `git diff --exit-code` step.
- Replace `classpath-convergence.sh` with Enforcer rules, and `version-report.sh` with the
  derived-version test.
- Move the eight cross-module guard tests into the new `guards` module.
- Delete `tools/allgates.sh`, `tools/diagnostics.sh`, `tools/corpus-both.sh`,
  `tools/ci-watch.sh`, `tools/classpath-convergence.sh`, `tools/version-report.sh`, and
  both gate workflows.

**Exit:** `./mvnw verify` reproduces all ten of today's gates, locally and in CI, with no
shell involved; a deliberately broken test fails it for the same reason the gate chain
would have.

### Phase 4 — Regeneration is a build mode *(1–2 weeks)*

- Break the `core` ↔ `spec` cycle: generators that only read upstream inputs move into a
  module `core` does not need; the claims ledger, which measures `core`, stays downstream
  of it.
- `-Pregenerate` rewrites every generated file and every ledger in one pass. Bumping the
  upstream release becomes: change one property, run it, review the diff as a pull request.
- Delete `tools/bump.sh`.
- Bring `tools/engine-runner` into the reactor as `upstream-runner` so its upstream version
  and its dependency on `legend-lite-core` cannot drift.

**Exit:** moving to a new Legend release is one property change plus one command, and the
resulting pull request is reviewable.

### Phase 5 — The corpus, the projects and the Python *(2–3 weeks)*

- Move the stress corpus into its own module with its runner; `core`'s suite stays the
  inner loop.
- Give `projects/` a test that compiles all 56 projects, individually and together, so the
  contract in `projects/CONTRACT.md` is enforced by the build rather than by a script.
- Draw the Python boundary explicitly: generated outputs are committed and verified by
  Java tests, so **no developer needs Python to build or test**. The generators stay as
  maintainer tooling, wired to `-Pregenerate` through `exec-maven-plugin` for those who
  have Python, with a CI job that runs them when `scripts/` changes. Porting the
  generators to Java is the eventual answer; it is not on this critical path.
- Decide what the repository should carry: the generated corpus is large, and a
  committed-artifact or generated-at-build choice should be made deliberately.

**Exit:** every directory in the repository is either built by Maven or explicitly
declared maintainer tooling, with no third option.

### Phase 6 — Releases, and documentation a newcomer can use *(2 weeks, then ongoing)*

- Semantic versions instead of a permanent `1.0.0-SNAPSHOT`; a tag-driven `release.yml`
  publishing `legend-lite-core` and the server jar, with a changelog built from PR titles.
- Mark everything outside the declared public surface internal.
- Archive `docs/`. Keep a handful of living documents — getting started, architecture,
  invariants, conformance testing, upstream bumps — and an `adr/` directory for decisions.
  Retire `GATES.md` as a running log; history belongs in commits, PRs and release notes.
- Replace the ad-hoc debug environment variables and `System.out` calls with a logging API.
- Remove `progress*.txt`, `progress/`, `experiments/` and editor-specific directories from
  the repository root.

**Exit:** a new developer finds what they need in six documents, and another project can
depend on a released version without building legend-lite.

**Total: roughly 11–15 engineer-weeks**, phases 1–3 being the load-bearing two-thirds.

---

## 6. Working concurrently

A standard build is necessary but not sufficient. Five things decide whether five people
can work at once.

**Shared scalar ratchets are the main hazard.** They are the one construct guaranteed to
conflict, and to conflict silently in the direction of a false green. §4.5 is the fix, and
it should land early in phase 3.

**Cross-module coupling.** Today an edit in `pct` or `parser-equivalence` can fail `core`,
so two people working in different modules are not actually independent. The `guards`
module makes the dependency explicit and one-directional.

**Generated files in pull requests.** The committed generated corpus is enormous. Mark
generated paths in `.gitattributes` so diffs stay reviewable, keep regeneration in its own
commit, and never hand-edit a generated file — a policy the build can enforce by
regenerating and comparing.

**Determinism.** Pinned clock, locale, encoding and heap; per-execution report
directories; no fixed temp paths. Any test that can fail intermittently — native database
drivers under forked JVMs are the known source — gets a quarantine tag and a ticket, never
a retry loop that hides it.

**Cadence.** With a required check on every pull request, `verify` has to stay fast enough
to run on every one: budget ten to fifteen minutes wall-clock on a CI runner, and measure
it each phase. If it grows past that, split the matrix (full on Linux/21, a smoke subset
on the rest) before weakening the suite. A merge queue keeps two independently-green pull
requests from landing a broken combination.

---

## 7. What gets deleted, and what replaces it

| deleted | lines | replaced by |
| --- | --- | --- |
| `tools/allgates.sh` | 471 | `mvn verify`: surefire/failsafe executions, tags, `-T1C` |
| `tools/bump.sh` | 313 | `-Pregenerate` and one version property |
| `tools/version-report.sh` | 270 | One property + a derived-version test |
| `tools/classpath-convergence.sh` | 86 | Enforcer `dependencyConvergence`, `bannedDependencies` |
| `tools/oracle-roots.sh` + `tools/oracle-pins.env` | 119 | Maven-resolved jars and checksummed source archives |
| `tools/diagnostics.sh` | 47 | A tagged failsafe execution (or deletion — they are measurements) |
| `tools/corpus-both.sh` | 11 | Two failsafe executions |
| `tools/ci-watch.sh` | 18 | `gh run watch` |
| `.github/workflows/gate.yml`, `gates-run.yml`, `diagnostics.yml` | 377 | One `build.yml` matrix job running `./mvnw verify` |
| `.github/actions/gate-env` | 58 | `actions/setup-java` with `cache: maven` |

About 1,770 lines of shell, pins and YAML, replaced by POM configuration and test code
that runs identically on a laptop.

---

## 8. Risks and open decisions

1. **Will `main` be protected?** Phase 0 is a decision, not a task, and every later phase
   depends on it. This is the largest risk in the plan.
2. **Are upstream's test sources reachable as a versioned artifact?** The plan assumes the
   tagged source archives are downloadable and checksum-stable. If a build must work behind
   a firewall with only an artifact mirror, the alternative is committing a snapshot of the
   snippets that `parser-equivalence` mines — smaller, but it must then be refreshed on
   every upstream bump.
3. **Does the whole suite fit in a pull-request check?** Unmeasured until phase 3. The
   fallback is a reduced matrix, never a reduced suite.
4. **Does the NullAway/Error Prone configuration hold on JDK 21 and 25?** It carries a
   JDK-21-specific workaround already; phase 1 must run both.
5. **Should the repository carry a half-million lines of generated corpus?** A deliberate
   decision, deferred to phase 5, not settled by this plan.
6. **Two modules moving (`corpus` out of `core`, `guards` out of everywhere)** touch many
   files at once. They are cheapest immediately after phase 3, when the ledgers are already
   data and the verdict is already Maven's.

---

## Appendix A — measurements behind section 2

Taken on `main`, Windows 11, JDK 25 (Temurin 25.0.4.1), Maven 3.9.14, offline against a
warm local repository, with no `legend-engine` / `legend-pure` checkouts present — the
state a newcomer's machine is in.

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
4736` and takes 158 s of that module's 4:52. `StressServiceSuitesTest` is tagged
`stress`, but `core/pom.xml` excludes only the `heavy` group, so the tag is honoured by
`tools/allgates.sh` and by nothing else. The build and the gate disagree about what the
default suite is, and the script is the one that is right.

| | |
| --- | --- |
| Shell in `tools/` | 1,285 lines across 8 scripts |
| Workflow YAML | 377 lines across 3 workflows, plus a composite action |
| Gates | 10, with pass/fail policy in `tools/allgates.sh` |
| Markdown at the top of `docs/` | 241 files |
| Java sources | 1,081 (672 in `core/src/main`) |
| Test classes | 311 (`core` 249, `parser-equivalence` 29, `spec` 14, `nlq` 13, `pct` 6) |
