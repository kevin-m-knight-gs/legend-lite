# A standard build for legend-lite

**The goal.** A developer who has never seen this repository installs Bazelisk, clones it,
runs one command, and gets a verdict — on Windows, macOS, or Linux, with nothing beyond
Bazel's own documented settings for their platform and nothing specific to this project.
The build brings its own JDKs, so whatever Java the machine has does not matter. A team of
developers can then work on it at the same time without stepping on each other.

**The means.** Bazel does all of it: it compiles, generates, tests, packages, and
publishes, and its exit code is the project's verdict. Not Bazel plus a shell script that
reads its console output; not Bazel plus source checkouts cloned by hand; not Bazel plus
Maven. There is one build system, and the POMs go. Bazel is also how Legend projects are
meant to be built (`docs/BAZEL_DEPENDENCY_PROPOSAL.md`), so this plan is the first step of
that design rather than a detour from it.

**Decided (2026-09-18).** Bazel is the one build system, and Maven goes entirely. Windows,
macOS, and Linux stay first-class; well-known Bazel settings on a platform are acceptable.
The scope is legend-lite itself, built so that Legend projects can later use the same
system without anything here being undone.

This document is the plan to get there from `main`. Its counts are taken at `c062b9bc9`
(2026-09-18); the judging work that has landed since does not touch the build. It is
written in phases, each with an exit condition you can observe. Section 7 lists every
bespoke script and build file the plan deletes and what replaces it.

---

## 1. What "done" looks like

Three commands, each with one job, all runnable on every supported platform. `bazel` here
means Bazelisk, which reads `.bazelversion` and runs exactly that Bazel:

| command | who runs it | what it does |
| --- | --- | --- |
| `bazel test --config=quick //...` | every developer, all day | The inner loop: the tests whose `size` is small or medium, and of those only the ones whose inputs changed since the last run. Minutes, not tens of minutes. |
| `bazel test //...` | every developer before pushing; CI on every PR | **Everything.** Builds every target and runs every test except the few measurements tagged `manual` (§4.0), including the conformance suites against the pinned upstream release and both database lanes, and fails if anything is wrong. Results are cached, so a second run re-executes only the tests whose inputs changed. The same command CI runs; the same verdict. |
| `bazel run //tools:accept` | a maintainer, after a deliberate change of behavior or upstream release | Accepts new baselines: copies the current candidates — from the build, or from the last test run (§4.5) — into the committed files, so the diff can be reviewed as a pull request. Everything a build can derive, it derives — see §4.8. |

Acceptance criteria for the program as a whole:

1. A clean clone plus `bazel test //...` is green. No checkouts, no environment variables,
   no scripts, and no setup beyond Bazelisk and the platform settings `CONTRIBUTING.md`
   lists (§4.6).
2. That holds on Windows, macOS (arm64 and x86_64), and Linux, with the tests run on both
   the JDK 21 and the JDK 25 toolchain.
3. Every input the build reads is declared and pinned: external artifacts and archives in
   `MODULE.bazel` and the lock files beside it, everything else a file in this repository.
4. A missing input fails the build; it is never skipped past. Skips that remain are
   registered, named, and shrink-only.
5. No test writes into the working tree; tests write only to Bazel's test output
   directories. CI asserts `git status --porcelain` is empty after a run — on Windows too,
   where tests run unsandboxed.
6. Pass/fail policy lives in test code and committed data files, not in a script that
   greps console output.
7. `main` moves only through pull requests whose required check is `bazel test //...`.
8. One build system: no `pom.xml`, no `mvn`, and no Maven wrapper remains in the
   repository.

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
- Real enforcement that must survive the move: ArchUnit layer rules, NullAway on every
  clean compile, and a ban on upstream artifacts inside `core` (an Enforcer rule today).
- Differential testing against the real Legend — the PCT suites, the relational corpus,
  byte-exact parser parity, and the stress corpus — is this project's best asset. The
  plan moves it into the build; it does not weaken it.
- CI already runs on Linux, macOS, and Windows. Only the JDK axis is missing.
- The test clock is already pinned (`-Duser.timezone=GMT`) — the right instinct,
  generalised in phase 1.
- Three runtime dependencies in `core`: DuckDB, SQLite, H2 drivers.
- Groundwork for Bazel already exists. `experiments/` holds the prototypes that proved
  per-element caching, `.pure` element discovery, and version checking in Bazel
  (`docs/BAZEL_DEPENDENCY_PROPOSAL.md` §9). `core` keeps its compiler ready for lazy,
  per-element loading across projects: `BazelSmokeTest` compiles two projects as separate
  model sources, and `NoEagerTypeReferencesTest` and `NoEagerUserClassLoadsTest` guard the
  lazy-loading convention `AGENTS.md` §5 describes.

---

## 3. Rules the plan follows

1. **Bazel decides.** If a condition can fail the project, it is a test assertion or a
   build failure, never a grep over console output.
2. **Every input is declared.** A build action or test reads only what its target
   declares: a pinned external — an artifact or archive whose checksum is in
   `MODULE.bazel` or a lock file beside it — or a file in this repository. On Linux and
   macOS, Bazel's sandbox and runfiles make an undeclared read fail rather than work by
   accident; on Windows, where actions run unsandboxed, the same declarations are the rule
   and the other platforms' CI cells catch a lapse.
3. **A missing input is a failure, and every remaining skip is registered.** A suite that
   cannot reach its input throws rather than assuming past it. Legitimate skips exist —
   a gap waiting on a feature, a test needing a credential — and the project already
   answers them well: `SkipCensusTest` requires every one to carry a named reason and pins
   the per-file count shrink-only. That rule stays; the blanket ban it looks like is not
   the rule.
4. **Generate everything derivable; commit only what must be compared against.** Anything
   the build can compute from inputs it already has is generated by a build action and
   committed nowhere (§4.8). Tests write only to Bazel's test output directories;
   accepting a new baseline is an explicit, separate `bazel run`.
5. **Nothing in the required path needs a shell.** No bash, no Python, no `make` — and so
   no `sh_test` and no shell `genrule`, which on Windows would need MSYS2 bash, the one
   special setting this plan refuses to require. Build actions run Java tools.
6. **Conventions over configuration.** Bazel's own: BUILD files beside the code they
   build, a `size` on every test (small and medium are the inner loop), `manual` only for
   targets `//...` must skip, and `.bazelrc` configs for modes, not for gates. A newcomer
   should be able to predict what a command does without reading a guide.
7. **One number, one place.** Versions live once, in `MODULE.bazel` and the lock files
   beside it; expectations live in committed data files.
8. **One build system, shaped for Legend projects.** Bazel builds the platform now, and
   Legend projects will build with it (`legend_library`, per
   `docs/BAZEL_DEPENDENCY_PROPOSAL.md`). Choices made here — package layout, rule names,
   how `.pure` sources are declared — must not have to be undone when the projects arrive.

---

## 4. The target build

### 4.0 Bazel, and nothing CI-only

**Why Bazel.** Two reasons, and the second decides it. First, the properties this plan
wants are Bazel's defaults: every input declared and pinned, the JDK included; one exit
code; builds and tests cached per target, so a change re-runs only what it can affect; and
dependencies between packages that exist only where a BUILD file says so — cause 4's hidden
coupling made visible and checked. Second, Legend projects are to be built with Bazel: a
`legend_library` rule per project, per-element outputs, and external Legend repositories
fetched and built by the same rules (`docs/BAZEL_DEPENDENCY_PROPOSAL.md`). A platform built
with Maven would put two build systems between the compiler and the projects it compiles.
One system for both is the long-term shape, and this plan is its first step.

The costs, stated plainly. Bazel is not a tool every Java developer already has; Bazelisk
is one install. Windows needs its documented settings (§4.6). Rules must do what Maven
plugins did: the Pure PAR generation `pct` depends on, the two shaded server jars (a
`java_binary`'s deploy jar covers those), and Error Prone with NullAway in the Java
toolchain. JUnit 5 needs a runner (`contrib_rules_jvm`). IDEs need their Bazel plugins.
And every test that finds a file by relative path must declare it and find it through
runfiles — 57 test sources today (phase 1).

**Nothing is CI-only.** Every check in this plan runs from a laptop with one command.
Four things are deliberately not in `bazel test //...`:

- **Accepting a baseline** (`bazel run //tools:accept`) rewrites committed expectations.
  It is not a test, and it is not generation — the build generates everything it can
  derive, every time (§4.8). This is the human saying "yes, that change of behavior is
  intended".
- **The matrix itself.** Running three platforms at once is a property of having three
  machines, not of the build. The JDK axis is not: Bazel downloads both JDKs, so a
  developer can run the tests on 21 and on 25 from one machine. Each CI cell is exactly
  `bazel test //...` under one of the two JDK configs.
- **Checks that need Python or the real engine.** The corpus generators are Python
  (§4.8); a scheduled workflow re-runs them and fails if the committed output moved, and a
  developer can run the same script. Checking the corpus's expectations against real
  legend-engine takes about an hour (§4.8) and is a maintainer's run. Nothing in
  `bazel test //...` needs either.
- **Scale tests and measurements.** `core`'s two scale tests, `ProfileBuildCost` and
  `StressTestChaotic` — tagged `heavy`, excluded from Maven's default run, and run by no
  gate today — and the diagnostics battery become `manual` targets, run by name as they
  are run by hand now. The `heavy` tag itself does not carry over: in `spec` it marks
  `MinimalCorpusTest`, which is gates 4 and 5 and becomes lane targets (§4.4).

Everything else — the conformance suites, both database lanes, the stress corpus, the
parser differential, the generator verification — is in the default build on every
platform.

### 4.1 One workspace

```
legend-lite/                MODULE.bazel, .bazelversion, .bazelrc, .bazelignore
├── generator/              core's upstream-derived sources   (new; runs before core)
├── core/                   the compiler and server           (no upstream dependencies)
├── nlq/                    natural language → Pure
├── spec/                   the relational corpus and harness, the claims ledger
├── pct/                    legend-pure's PCT suites
├── parser-equivalence/     differential parser tests
├── corpus/                 the stress corpus and its runner  (moved out of core)
├── projects/<name>/        one target per Legend project     (legend_library, first form)
├── guards/                 repo-wide guard and census tests  (new)
├── upstream-runner/        today's tools/engine-runner       (in the build)
└── tools/                  the project's rules and macros    (legend.bzl, test macros)
```

Each directory with a BUILD file is a package, and the heavy suites get targets of their
own so they can be cached, sized, and scheduled separately. The layout inside each module
stays as it is — `src/main/java`, `src/test/resources` — because Bazel does not care; the
plan moves files only where a dependency edge demands it (`corpus` and `guards`, below).
Dependencies between packages exist only as `deps` or `data` edges in BUILD files, so the
coupling cause 4 describes stops being a relative path and becomes a line a reviewer sees.

Five packages are new or newly built:

- **`generator`** is the one package that reads upstream to produce what `core` compiles:
  the prelude, the signature text, the dynafunction registry, and the import sequence. It
  is a `java_binary` that depends on the upstream artifacts and on nothing of ours;
  `core`'s BUILD file runs it as a build action and compiles what it writes. The upstream
  jars are that action's inputs, never on `core`'s classpath (§4.8, phase 4). The
  generators leave `spec` for it.
- **`corpus`** takes the stress corpus and its runner out of `core`, for what they depend
  on rather than for speed — under Bazel a test's `size` decides whether it is in the
  inner loop, wherever it lives. The stress corpus is a conformance suite like `pct` and
  `spec`'s corpus, and it depends on eleven of the projects, which the stress model loads
  first (`StressCorpus.LINKED_PROJECTS`, read today from `../projects`). Those edges
  belong to a package of their own, not to the compiler's, and in Bazel they are declared
  edges to the projects' targets rather than a relative path.
- **`projects`** gives each of the 56 Legend projects a target of its own. The first form
  of `legend_library` compiles a project's `.pure` sources against its dependencies' with
  legend-lite's compiler and fails on any error. That is coarser even than the proposal's
  fallback, which still emits one output per source file
  (`docs/BAZEL_DEPENDENCY_PROPOSAL.md` §4), but it makes the projects a checked build.
  Per-element outputs, lazy loading across projects, and `unused_inputs_list` arrive with
  the compiler work the proposal describes; the targets, their dependency edges, and the
  BUILD layout do not change when they do, because phase 5 fixes the rule's interface
  first.
- **`guards`** is where every test that reads another package's sources goes. Each
  package exports its sources as a `filegroup` for the purpose, so a guard's reach is a
  declared dependency. Declared in `core`, those edges would point from the compiler to
  every package that uses it; in `guards` the direction stays one-way — everything
  depends on `core`, and only `guards` depends on everything — so an edit in `pct` can
  fail `guards`, where a reviewer expects it, and never `core`. Bazel also removes the old
  obstacle to sharing test code: a test can depend on another package's test helpers as
  their own `testonly` library, without the service registration that leaked through a
  Maven test-jar and flipped a `parser-equivalence` verdict (`b5ad0b82b`).
- **`upstream-runner`** is today's `tools/engine-runner`, built in place from phase 1 and
  moved in phase 3, so its upstream version comes from `MODULE.bazel` and cannot drift.

### 4.2 Inputs Bazel owns

Every external input is declared in `MODULE.bazel` and pinned there or in a lock file
beside it: the rules themselves, the JDKs, the Java artifacts, and the upstream archives.
The two source checkouts disappear, in two steps, because the two of them are not the same
problem.

**The JDKs come from the build.** `rules_java` provides remote JDKs for 21 and 25; the
build compiles for 21 and runs the tests on whichever runtime the config names. Bazel
itself runs on a JDK it embeds, so the machine needs no Java at all.

**Java artifacts come from Maven Central, not from Maven.** `rules_jvm_external` resolves
every third-party and upstream artifact into a lock file — pinned, checksummed, one version
per artifact. The one artifact that needs a second version, gate 7's H2 (§4.4), gets a
second repository, named on purpose, with a lock file of its own. Maven Central stays the
place artifacts come from and go to; Maven the build tool is not involved.

**legend-pure, and most of legend-engine, is already published.** The `.pure` files the
tests read from `src/main/resources` are shipped inside the release jars — measured
byte-identical to the source tree at 5.92.0, the pin at the time; the pin is now 5.99.0,
and phase 2 re-measures before relying on it. A test takes the jars as `data` — files in
its runfiles, never on its classpath (which matters: `core` must see zero
`org.finos.legend` artifacts, and a test asserts it, §4.3) — and opens each as a zip
`FileSystem`. Paths inside a zip walk, resolve, relativize, and read exactly like a
checkout's, so the calling code barely changes.

**What is genuinely unpublished is upstream's *test* sources** — `parser-equivalence`
mines Pure snippets embedded in legend-engine's and legend-pure's Java test files, and
neither project ships a `-test-sources` jar. The snippets are committed here, harvested at
bump time by a `bazel run` target that reads the tagged source archives, pinned by
checksum with `http_file`, **inside** the archive rather than unpacking it (reading in
place is not a nicety: it keeps Windows clear of legend-engine's very deep paths, which is
why CI has to set `core.longpaths` today). The harvester is tagged `manual`:
`bazel test //...` builds every target its pattern matches, tests or not, so an untagged
harvester would fetch both archives on every developer's first build.

Bazel could make those archives an ordinary build input, so committing the harvest has to
stand on its remaining reasons, and they suffice: a developer's `bazel test //...` never
downloads two whole source trees, and "what upstream's test corpus says" arrives as a diff
a human reviews when the release moves. The cost is one refresh step inside a procedure
that already exists.

Net effect: `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the `legend.engine.root` and
`legend.pure.root` properties and their env-activated profiles, the composite action's two
checkouts, and the skip detector all go away. One version in `MODULE.bazel` names the
release; the legend-pure version is derived from that release's own POM — fetched as a
pinned file — and asserted by a test.

### 4.3 The verdict is Bazel's exit code

| today | tomorrow |
| --- | --- |
| Gate ceilings in bash (`run>=469, fail<=1, err<=26`) | A committed expected-failure ledger; the suite asserts the observed failure set **equals** it |
| A 23-name roster so a renamed test cannot shrink a gate | Every test target except the `manual` measurements (§4.0) runs under `//...`; targets take their classes by `glob`, so a renamed class stays inside, and a deleted target is a visible line in a BUILD diff |
| `skipped()` awk detector | A missing upstream input throws instead of assuming; `SkipCensusTest` keeps every remaining skip named and pinned |
| Tree-mutation tripwire | Tests write only to Bazel's output directories; the sandbox stops any other write on Linux and macOS, and CI checks `git status --porcelain` on every platform |
| `classpath-convergence.sh` | One version per artifact in the lock file, and a test over a `genquery` of `core`'s dependency closure proving no `org.finos.legend` artifact reaches it |
| `version-report.sh --check` | One version in `MODULE.bazel`, plus a test that checks it against the release's own POM |
| Three-stream scheduler | Bazel's own scheduler, with the heavy suites' needs declared on their targets and the number of tests at once set from phase 1's measurement — a bet on memory, not a translation |
| Heap set by CI env vars | `jvm_flags` on each test target, from one macro |
| `-Dh2.version=2.4.240` on gate 7's command line | That lane's target takes H2 2.4.240 from its own pinned repository (§4.4) |
| `-Dpct.reuseForks=false`, passed by CI only | Each PCT suite is its own test target and so its own JVM, which is how Bazel runs tests anyway (§4.7) |

### 4.4 Lanes are test targets, not invocations

The DuckDB and H2 lanes are the same tests under a different switch
(`-Drcorpus.backend=h2`, `-Dstress.backend=h2`, and `LEGENDLITE_PCT_BACKEND=h2`, which is
an environment variable). Today that means running Maven twice from a script and comparing
logs. In the target build each lane is a test target of its own — one macro call per
lane — with its own `jvm_flags` or `env`, its own logs, and its own cached result.
`bazel test //...` runs them all and fails if any fails. Gates 4/5, 6/7, and the two stress
lanes become targets. The H2 stress lane is in no gate today — gate 10 runs the DuckDB lane
only — so as a target it is new coverage, at about two minutes.

One lane differs by more than a switch, and Bazel makes it the easy case. Gate 7 runs the
PCT relation suite on H2 2.4.240 by passing `-Dh2.version=2.4.240` on its command line,
while `pct/pom.xml` pins 2.1.214. Maven resolves one version of a dependency for all of a
module's executions; in Bazel the classpath belongs to the target, so that lane's target
takes H2 from a second pinned artifact repository while the rest of `pct` keeps 2.1.214.
The version moves out of a command line into `MODULE.bazel`.

**A second axis is on its way.** The judging program makes the judge a run-level switch,
`-Dlegend.judge.mode=host|database`, read once per JVM (`database` became selectable with
leg 3.1 of `docs/DATABASE_MODE_HOMEWORK_2026_09_18.md`), and commits to a permanent
**differential gate**: the corpus and stress lanes run in both modes, and every assertion
must get the same verdict from each (`docs/JUDGING_TWO_MODES_2026_09_17.md` §4). The
homework specifies it as per-assertion verdict files, one per mode per lane, keyed by test
and statement, compared by a gate that pins zero disagreements (D7). Bazel does not let one
test read another test's output, so the two runs become build actions — the harness, run
once per backend and mode, writing its verdict file as a declared, cached output — and the
comparison is the test. Whether the lanes' own verdicts then come from those same outputs,
so that nothing runs twice, is leg 3.3's to settle. Leg 3.3 is likely to land before Bazel
does — legs 3.1a and 3.1b already have — so it will be wired into the gate chain first.
Keep the chain's part to invocation: each lane writes its verdict file, and the comparison,
the zero-disagreement pin, and the unjudged ledgers live in a Java test. Phase 3 then ports
only the invocations — the runs become build actions and the test stays as it is — instead
of translating policy out of `tools/allgates.sh`.

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
fix different tests touch different lines and git merges them correctly. New expectations
should be born in this shape; the judging program's per-mode unjudged lists (homework D3)
are the next due. Whether the source-size pins should exist at all is a separate question
(§9).

Baselines come in two kinds, and one command accepts both. A **test outcome** — an
expected-failure set, a roster, a census — gets its candidate from running the tests: each
run writes it to the test's undeclared outputs. A **derived file** — the claims ledger —
gets its candidate from the build: an action computes it, and a test compares it with the
committed copy (§4.8). `bazel run //tools:accept` copies either kind into the workspace,
which is the one deliberate act a human performs. It is a Java tool like the rest, so it
runs the same on Windows (rule 5).

### 4.6 Portability, spelled out

| hazard | the fix |
| --- | --- |
| CRLF rewriting the `.pure` corpus | `.gitattributes`: `* text=auto eol=lf` for ordinary sources, and **`*.pure -text`** so git never converts a corpus file in either direction. The exception is load-bearing: `prelude.pure` is stored with mixed endings because upstream's text carries CRLF, and a blanket `eol=lf` would renormalize it and break the byte-exact comparison it exists for. Phase 4 stops committing `prelude.pure`, which retires that reason; phase 4 then decides whether the exception stays for the byte-exact `.pure` fixtures or becomes `text eol=lf`. CI then stops configuring git, and identical bytes on every platform let a shared cache hit across them. |
| Deep paths on Windows | Read upstream archives in place; never unpack them. Bazel's own output tree is deep too, so Windows developers point it at a short root (`startup --output_user_root=C:/b` in their user `.bazelrc`) and turn on Windows long-path support — both from Bazel's Windows documentation. |
| Tests finding their files | Through the runfiles library, which works from Bazel's manifest whether or not Windows creates runfiles symlinks — so the build needs neither Developer Mode nor `--enable_runfiles`. |
| Locale-dependent case and formatting | Pin `-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8` beside the existing `-Duser.timezone=GMT`, once, in the test macro every target uses. |
| Shell-only tooling | Deleted, not ported; no rule needs bash, so Windows needs no MSYS2. |
| `/tmp`, `mktemp`, `id -un`, fixed report paths | Gone with the scripts; tests use `TEST_TMPDIR` and their undeclared-outputs directory. |
| arm64 vs x86_64 floating point | Already one declared, counted policy with one home: a 2-ULP leniency in `com.legend.exec.Equality`, and `VerdictChannelRegisterTest` fails if `Math.ulp` appears anywhere else. Database mode will add its SQL twin (homework D4), and the differential gate (§4.4) holds the two to the same answers. Never a platform `if`. |
| Case-insensitive filesystems | A guard test asserting no two committed paths differ only by case. |
| JDK differences | Remote JDK toolchains: compiled for 21 everywhere, tested on the 21 and 25 runtimes by config. Nothing depends on the JDK a machine happens to have. |
| Bazel's own version | `.bazelversion`, which Bazelisk reads; a developer never chooses a Bazel. |

**What a developer sets up, per platform.** Bazelisk, from the platform's usual installer
(Homebrew on macOS; winget, Chocolatey, or Scoop on Windows; a release binary on Linux).
On Windows, the short output root and long-path support above. Nothing else is expected on
any platform, and phase 1 proves that on a clean machine of each kind: whatever a clean
machine turns out to need goes into `CONTRIBUTING.md`, and nothing it does not need does.
Editors need their Bazel plugins; IntelliJ and VS Code both have one.

Beyond the three platforms CI can run, the build is Java and Bazel, so it runs wherever
Bazel and the remote JDKs do. The real limit is the native JDBC drivers: DuckDB and SQLite
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
    os:     [ubuntu-latest, macos-latest, windows-latest]
    config: [jdk21, jdk25]
steps:
  - uses: bazel-contrib/setup-bazel   # Bazelisk, plus the repository and disk caches
  - run: bazel test --config=${{ matrix.config }} //...
```

Six jobs, every one of them the same command a developer runs. Plus the `actionlint` job
the project already has; it lives inside `gate.yml` today and moves into `build.yml` before
phase 3 deletes that file.

**`release.yml`** — on a tag: Bazel builds the jars, sources, javadoc, and POM that Maven
Central requires (`java_export` in `rules_jvm_external`), signs them, and publishes. Other
projects keep consuming legend-lite from Maven Central; publishing there needs no Maven.

**`corpus-generators.yml`** — on a schedule: re-run the Python corpus generators and fail
if the committed output moved (§4.8). It is the one workflow that needs Python, which is
why it is not part of `bazel test //...`.

From phase 3's exit, the job on `ubuntu-latest` with `jdk21` is the required check for
merging; until then the gate workflows are (phase 0). The matrix is the only thing CI adds
that a laptop cannot do.

**Caching.** Each job restores Bazel's repository and disk caches, so a pull request
re-runs only the tests its change can affect. A remote cache shared by CI and developers is
an option (§9), not a requirement.

**Every cell is exactly `bazel test //...`, the small one included.** Settings that change
how a machine runs the build — how many tests run at once, where the output tree lives,
which on the Windows cells is the same short root a Windows developer uses (§4.6) — may
differ per machine; settings that change the verdict may not. Each PCT suite is its own
target and so its own JVM, which is what CI's `-Dpct.reuseForks=false` buys today; on the
macOS runner, 3 vCPU and 7 GB, how many of those 2–3 GB suites run at once is a machine
setting sized by phase 1's measurement.

**On `-latest` rather than pinned images.** The reason to build on macOS and Windows is
that people develop there, and they keep their machines roughly current. A pinned image
tests a configuration nobody has, drifts further from the developers every month, and gets
removed by GitHub in the end anyway — so pinning buys a delay, not an escape. `-latest` is
not a cliff either: the label rolls over gradually and the old image warns in the logs
first.

What is worth holding still is the **architecture** axis, which is where this project has
already been bitten: DuckDB's `percentile_cont` returns a different double on x86_64 than
on arm64. `macos-latest` is arm64 and the other two legs are x86_64, so the matrix covers
both by construction. `gate.yml` already says so in a comment, because it is the reason the
macOS leg exists, and `build.yml` keeps it; an Intel macOS leg can be added if that
specific combination ever matters. If an image rollover does redden the required check one
day, pin that one job and keep an unpinned nightly as the early warning; do not pin the
matrix by default.

### 4.8 Generated by the build, or committed — and why

**The rule: anything the build can derive from inputs it already has is generated on every
build and committed nowhere. Only what exists to be compared against is committed.** A
baseline regenerated from the thing it measures asserts nothing — it compares a file to
itself.

| artifact | today | target | why |
| --- | --- | --- | --- |
| `prelude.pure` (7,009 lines, 293 KB) | committed under `core/src/main/resources`; a `spec` test writes it into `../core` | **generated** by the `generator` action, as a resource `core` packages | A function of upstream's platform sources plus a committed exclusion list. `core` already reads it off the classpath at runtime — which is exactly what a generated resource is. |
| The signature text inside `Pure.java` | committed; a `spec` test rewrites the block between markers in a hand-written file | **generated** source; the membership list stays committed | The text is upstream's. A half-generated Java file cannot be a build output, so the file splits: our list of what we claim, their text for each claim. |
| The DynaFn registry members | a region of a hand-written Java source, patched by a test | **generated** source | A mirror of upstream's registries. |
| `CORE_IMPORTS` | a Java constant patched by a test | **generated** constant | A mirror of upstream's `CompileContext.META_IMPORTS`. |
| `native-claims.tsv` (829 rows) | committed; its own header says "the diff is the review" | **committed**, with a test that fails when the build's candidate differs, and `//tools:accept` to take a new one | Measured from our own code: one row per `Pure.java` overload, with the `core` classes that claim it. Generating it each build would compare it with itself; its entire job is to make a change in the implemented surface visible in a pull request — as it did when a typer change (`928451d67`) added `NumberKinds` to twenty rows. |
| `native-membership.tsv` (787 rows) | committed, mixed | **splits** | Membership is a decision we make; the signature text beside it is upstream's. |
| Expected-failure ledgers, censuses, rosters, corpus scoreboards | committed; several rewritten in the working tree by a test run | **committed**, with the candidate in the test's outputs and compared | Same as the claims ledger: they exist to be compared against. |
| The stress corpus's expected answers | committed | **committed** | Computed independently by the same Python generators as the sources (next row), then checked against real legend-engine by `scripts/corpus/run.py` through the upstream runner — about an hour, with `0 unexpected` the only acceptable result. `run.py` builds the runner's classpath with `mvn` today; it takes the runner from its Bazel target once Maven is gone. A build cannot contain its own oracle. |
| The stress corpus's generated sources (~487k lines) | committed, produced by Python | **committed** | Producing them needs Python 3.12 and about ten minutes. Requiring a second toolchain on every developer's machine costs more than it buys; a scheduled job re-runs the generators and fails if the output moved. |
| Snippets harvested from upstream's test sources | read from a checkout | **committed**, refreshed at bump time (§4.2) | Deriving them each build would put two whole source archives into every developer's first build; committing them keeps that out of `bazel test //...` and makes an upstream change reviewable. |
| Parser fixture adjudication | committed | **committed** | Which construct is a legal positive and which a deliberate negative is a judgment, not a derivation. |

Three things make build-time generation impossible today. None is a fact about the
problem; each is a consequence of where code was put.

1. **The inputs are outside the build.** A generator cannot run in a build that cannot
   fetch what it reads. Phase 2 fixes this, and is a prerequisite for phase 4.
2. **The output is patched into hand-written files** — and into *another module's source
   tree*: `spec` writes `../core/src/main/...` through a `CoreTree` helper whose own
   javadoc describes the arrangement. A file that is half hand-written can never be a
   build output.
3. **The generators live in `spec`, which depends on `core`,** while their output is an
   input to `core`. The cycle is where the generators sit, not something inherent; a
   generator package that depends only on the upstream artifacts breaks it.

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
35 hours and 42 commits, 33 of them its own. It changed a Maven build rather than replacing
one, so it calibrates phases 2 to 4 better than phase 1, whose work — a BUILD file per
package, a lock file, and a declared input for every file a test reads — has no precedent
here. If that pace holds, the implementation this plan describes is weeks of calendar time,
not months. That is an extrapolation from one program, not a measurement, and none of the
gates below shrinks with it.

### Phase 0 — Decide how `main` is protected *(decision, not engineering)*

Nothing below survives if `main` keeps taking direct pushes: a build fixed on Monday is
stale by Friday, and a green build means nothing if most commits never ran it.

- Every change lands through a pull request, with branch protection on. The required
  check is the gate workflows CI runs today — the project's verdict until phase 3 — and
  `build.yml` joins them once phase 1 has it green; from phase 3's exit,
  `bazel test //...` alone is required. This applies to agent-authored work identically.
  One wrinkle: `gate.yml` skips documentation-only changes, and GitHub blocks a pull
  request whose required check never reports, so either the skip goes or a job that does
  report stands in for it on those changes.
- A declared public surface (`com.legend.Compiler` and the HTTP API) and versioned
  releases, so other work can depend on something that holds still.
- `CODEOWNERS`, and a stated review expectation.
- Run records move into the pull request. `docs/GATES.md` stops taking a paragraph per
  change: it is the file most commits touch, so any two pull requests in flight conflict
  at its end (§6). Its gate definitions stay until phase 3 replaces the gates.

**Gated by:** decisions only, and all of them the owners': the protection itself and how
it treats documentation-only changes, the public surface, the review expectation, and the
change of habit the last bullet asks for. The only engineering is, at most, that one
workflow change.

**Exit:** branch protection enabled, with the gate workflows as the required check.

### Phase 1 — Bazel builds and tests the project, beside Maven

- **The workspace.** `MODULE.bazel`, `.bazelversion`, `.bazelrc`, and `.bazelignore` at
  the root. The `.bazelrc` carries the `quick`, `jdk21`, and `jdk25` configs. The
  `.bazelignore` lists `experiments/`, whose two prototypes are Bazel workspaces of their
  own — `legend_rules_test`'s BUILD files load `//:legend.bzl` and repositories only its
  own `MODULE.bazel` defines — and no part of this build. `rules_java` brings the JDK 21
  and 25 remote toolchains, 21 the default; `rules_jvm_external`, one lock file holding
  every artifact the POMs name today, one version each; and `contrib_rules_jvm` runs
  JUnit 5, with the vintage engine for the eight JUnit 3 and 4 classes — the five PCT
  suites among them.
- **BUILD files for every module, `tools/engine-runner` included.** A `java_library` per
  main tree; test targets per suite, taking their classes by `glob` so a new test is never
  invisible to Bazel; a `size` on every test; the two HTTP servers as `java_binary`
  targets, whose deploy jars replace the shaded jars and are built only when asked for;
  and a build action for the Pure PAR `pct` depends on, calling the generator the Maven
  plugin wraps.
- **One test macro** that every test target uses, carrying the pinned clock, locale, and
  encoding of §4.6 and the heap, so no target sets them on its own.
- **Error Prone and NullAway** in the Java toolchain, configured as `core/pom.xml`
  configures them today, and verified on both JDKs — the JSpecify workaround for 21
  included.
- **Every test finds its files as a declared input.** 57 test sources read files by
  relative path today (26 in `core`, 19 in `parser-equivalence`, 6 each in `spec` and
  `pct`). Each declares them and finds them through one small helper — Bazel's runfiles
  under Bazel, the repository root under Maven — whose Maven half goes with Maven in
  phase 3. The 16 that read or write `target/` and the 24 that write files go through the
  same helper: under Bazel they write to its test output directories, under Maven where
  they write today.
- **Generation modes get `bazel run` targets.** The five `-D*.generate` flags rewrite
  files in the working tree, which a Bazel test cannot do; each gets a `bazel run` target
  that writes into the workspace instead. The flags themselves stay for Maven until
  phase 3 (below), and phase 4 moves generation into the build.
- Add `.gitattributes` — `* text=auto eol=lf` with `*.pure -text` (§4.6: the exception is
  what keeps `prelude.pure`'s mixed endings intact) — and `.editorconfig`. Confirm with
  `git ls-files --eol` before and after that exactly the four known exceptions remain, and
  add the guard test that no two committed paths differ only by case (§4.6).
- **Measure the full suite, in wall clock and in resident memory**, before anything
  depends on the answer: a cold build that fetches the JDKs and artifacts, a warm one, and
  the full test set serially and in parallel, on a laptop-sized machine of each platform
  and on the CI runners. Measure each quiet, with no stray JVMs or IDE builds running: the
  same chain has measured 7m24s beside a day's leftover JVMs and 4m09s without them
  (`docs/GATES.md`, 2026-09-17). §6 weighs the project's four-minute budget against the
  result, and phase 3's scheduling is a bet on memory that this measurement settles.
- Fix the duplicate `provision` enum value in the `nlq` test model.
- Tag every test that needs an upstream source checkout `manual`, so `//...` leaves it
  out, and convert its skips into failures. This is temporary scaffolding that phase 2
  removes — its purpose is to make "green on a clean clone" true immediately. The scale
  tests and the diagnostics battery are `manual` for good (§4.0).
- Rewrite `README.md` for what exists: what the project is, the three commands, the
  package map. Add `CONTRIBUTING.md` — Bazelisk, the per-platform settings a clean machine
  turned out to need, the IDE plugins — and `LICENSE` and `NOTICE` for upstream-derived
  files.
- CI: add `build.yml` (§4.7) with whatever is green so far, **beside** the gate workflows.
  They stay the required check until phase 3's exit shows `bazel test //...` reproduces
  them (phase 0); replacing them now would take the corpus lanes, the H2 PCT lane,
  Channel B, and parser parity out of CI for two phases.
- The POMs stay untouched, and Maven stays the reference the Bazel build must match. The
  tests change only in ways both builds accept (the helper above), and the five
  `-D*.generate` flags keep working, because `tools/bump.sh` runs the generators through
  Maven until phase 3 replaces it.

**Gated by:** *decisions* — the per-platform settings (as few as a clean machine allows),
and the `LICENSE` and `NOTICE` text for upstream-derived files, which is the owners' to
confirm. *Verification* — the six-cell matrix green, JDK 21's NullAway run included; the
settings tried on a clean machine of each platform; and Bazel running exactly the tests
Maven's default `verify` runs — `stress` included and `heavy` left out, as today — minus
the checkout suites tagged `manual`, compared module by module on the same commit
(Appendix A has Maven's counts, taken without checkouts). *Coordination* — heavy: 57 test
sources change how they find files, and every test or dependency the judging program adds
must reach a BUILD file too. The globs and the count check keep the two builds equal.

**Exit:** a newcomer on any of the three platforms installs Bazelisk, applies the
documented settings, clones, runs `bazel test //...`, and sees green — and CI proves it on
six configurations, test for test with Maven.

### Phase 2 — Bazel owns the inputs

- Re-measure that the release jars' `.pure` text is byte-identical to the source tree at
  the current pin; §4.2's measurement was at 5.92.0.
- Tests take legend-pure's (and legend-engine's published) release jars as `data` and read
  their `.pure` text inside them through a zip `FileSystem` helper. Keep the `core`
  boundary intact: jars as files, never on a classpath.
- For `parser-equivalence`, commit the snippets harvested from upstream's test sources,
  and write the harvester: a `manual` `bazel run` target over the tagged source archives,
  pinned with `http_file` (§4.2). After this `bazel test //...` reaches the network for
  pinned artifacts and nothing else.
- Add the derived-version test: the legend-pure version in `MODULE.bazel` must equal what
  the pinned engine release's own POM declares.
- Remove the `manual` tags phase 1 put on the checkout suites. Those suites now run in
  `//...`, everywhere.
- The Maven build keeps its checkouts until phase 3 deletes it; nothing new goes into it.

**Gated by:** *verification* — the byte identity re-measured at the current pin, then a
green `bazel test //...` from empty caches with no checkouts, on every cell. *Outside* —
upstream's releases: a new pin mid-phase means a new harvest, and a release is usable only
once it is on Maven Central (`tools/bump.sh` records a tagged release that was not).
*Coordination* — light: three packages change where their inputs come from, not what they
test.

**Exit:** a machine with empty Bazel caches and no checkouts runs `bazel test //...` green,
and nothing in the Bazel build reads a checkout.

### Phase 3 — Bazel owns the verdict, and Maven goes

- Turn each backend lane into a test target with its own flags and logs, adding the H2
  stress lane that no gate runs today and giving gate 7 its own H2 (§4.4).
- Convert every floor and ceiling into a row ledger (§4.5), and whatever source-size pins
  survive §9's triage.
- Make a missing upstream input throw rather than assume past it, and keep
  `SkipCensusTest` as the rule for the skips that legitimately remain. Delete the walks
  over sibling directories that no longer exist (`ErrorShapeGuardrailTest` still walks
  `../engine`).
- Compare against committed baselines instead of rewriting them, with `//tools:accept` to
  take new ones. Add the CI `git status --porcelain` step.
- One version per artifact from the lock file, and the `genquery` test for `core`'s
  closure, in place of `classpath-convergence.sh`; the derived-version test in place of
  `version-report.sh`.
- Replace `tools/bump.sh` with a procedure that needs no Maven — change the release in
  `MODULE.bazel`, repin the lock file, run the generation and harvest targets, accept the
  baselines — and point `scripts/corpus/run.py` at the upstream runner's Bazel target,
  moving `tools/engine-runner` to `upstream-runner/` in the same change. Phase 4 shortens
  the procedure.
- Last, once the ledgers are data: move the nine cross-module guard tests into `guards`
  and the stress corpus and its runner into `corpus`, so `core`'s package depends on
  nothing downstream of it (§4.1; §8, risk 8).
- **Delete Maven, in one change**, once `bazel test //...` reproduces every gate: every
  `pom.xml`, `.sdkmanrc`, the Maven half of the input helper, the five `-D*.generate`
  flags, `tools/allgates.sh`, `tools/bump.sh`, `tools/diagnostics.sh`,
  `tools/corpus-both.sh`, `tools/ci-watch.sh`, `tools/classpath-convergence.sh`,
  `tools/version-report.sh`, `tools/oracle-pins.env`, `tools/oracle-roots.sh`, the
  composite action, and all three workflows: `gate.yml` and `gates-run.yml` once
  `build.yml` carries the `actionlint` job, and `diagnostics.yml` with
  `tools/diagnostics.sh`.

**Gated by:** *decisions* — three from §9: the four-minute budget and which lanes gate
pull requests, both needed before the lanes become targets, and the triage of the ledger
and census tests, needed before the ledgers convert. *Verification* — the heaviest of any
phase: each converted lane must give its gate's verdict on the same commit, so every
conversion costs a chain run and a `bazel test` run side by side, on each platform, and
deleting Maven waits for all of them.
*Coordination* — the real risk. The files holding the floors, ceilings, pins, and rosters
this phase touches were edited by 32 of the 139 commits between 2026-09-11 and 2026-09-18,
and the phase moves the stress corpus and nine guard tests between packages while that
work continues. It lands as small pull requests, one lane or one ledger at a time, or in a
window agreed with whoever runs the judging program; a long-lived branch will not survive
the rebases.

**Exit:** `bazel test //...` reproduces every gate the chain runs at the time — ten today,
plus the differential gate if leg 3.3 has landed (§4.4) — locally and in CI, with no shell
involved; a deliberately broken test fails it for the same reason the gate chain would
have; no `pom.xml` and no `mvn` remains in the repository; and `build.yml` is the one
required check.

### Phase 4 — Generation moves into the build

Execute §4.8: everything derivable becomes a build output, and what remains committed is
committed for a stated reason.

- **A `generator` package that depends on the upstream artifacts and on nothing of
  ours.** Its `java_binary` runs as a build action in `core`'s BUILD file and writes the
  prelude, the signature text, the dynafunction registry, and the import sequence as
  declared outputs, which `core`'s `java_library` compiles and packages. The upstream jars
  are the action's inputs, never on `core`'s classpath, and no package reads another's
  output directory by path. This breaks the `core` ↔ `spec` cycle by construction: the
  generator knows upstream, `core` knows the generator's output, and the ledgers that
  measure `core` sit downstream of both.
- **Split the three hand-written files that carry generated regions.** `Pure.java` keeps
  the membership it declares and loses the signature text; the dynafunction registry and
  the import constant become generated sources referenced by hand-written code. Delete
  `CoreTree`, every `../core` write, and phase 1's five generation targets with it.
- **Delete the committed copies** of everything in §4.8's generated rows, and the
  byte-parity tests that existed only to prove a committed copy was current. Those tests
  are made redundant by generation, not weakened by it. With `prelude.pure` gone, decide
  whether `*.pure -text` stays (§4.6).
- **One flow for baselines.** Every baseline that remains is one of §4.5's two kinds — a
  test outcome or a derived file — compared by a test and accepted by `//tools:accept`.
- Bumping the upstream release becomes: change the release in `MODULE.bazel`, repin,
  refresh the harvested test snippets (§4.2), run `bazel test //...`, and where a baseline
  moved, accept it and review the rows.

**Gated by:** *decisions* — the owners accepting that a bump's review moves from ~690 KB of
generated diff to the ledger rows (§4.8), and whether `*.pure -text` stays.
*Verification* — one real upstream bump carried through the new flow end to end.
*Outside* — that bump needs a new upstream release on Maven Central. *Coordination* —
moderate: `Pure.java`, the dynafunction registry, and the import constant change shape
while feature work edits them (`Pure.java` alone was edited by 12 of the 139 commits).

**Exit:** no generated artifact is committed except the baselines §4.8 names, each with its
reason; no test writes outside Bazel's output directories; moving to a new Legend release
is a version change, a repin, and the snippet refresh §4.2 keeps at bump time.

### Phase 5 — The projects and the Python

- Give each of the 56 projects its target, with the first form of `legend_library`
  (§4.1): compile the project's `.pure` sources against its dependencies' and fail on any
  error, so the contract in `projects/CONTRACT.md` is enforced by the build rather than by
  `scripts/projects/check.py`. Fix the rule's interface now, as
  `docs/BAZEL_DEPENDENCY_PROPOSAL.md` §4 has it: projects declare `srcs`, `model_deps`,
  and `impl_deps`, even while the first form treats the two kinds of dependency alike.
  What the per-element form adds — the element list the proposal's module extension
  produces — arrives through the rule's own `.bzl` file, not through each BUILD file, so
  that form changes what the rule produces, not how projects declare themselves.
- Make `corpus`'s eleven linked projects a declared dependency on their targets instead
  of `../projects` (§4.1).
- Draw the Python boundary explicitly, and justify it in §4.8's terms: the corpus sources
  are the one derivable artifact the plan still commits, because deriving them needs a
  second toolchain and ten minutes on every machine. **No developer needs Python to build
  or test.** A scheduled CI job re-runs the generators and fails if their output moved, so
  the committed copy cannot silently stop matching its generator.
- Port the generators to Java when someone has the appetite: that, and only that, would
  move the corpus sources from "committed with a reason" to "generated like everything
  else". It is not on this critical path, and the §4.8 row should be revisited if it lands.
- Settle what else lives in `tools/` — four Python scripts, `metamodel-census/`, and
  `spikes/`: each is declared maintainer tooling or deleted, since the exit leaves no
  third option.

**Gated by:** *decisions* — where the Python boundary sits, and whether the corpus stays in
this repository at all (§9). *Verification* — the 56 project targets, and the first runs
of the scheduled generator workflow. *Coordination* — light.

**Exit:** every directory in the repository is either built by Bazel or explicitly
declared maintainer tooling, with no third option.

### Phase 6 — Releases, and documentation a newcomer can use

- **Settle the namespace first, because the current one cannot be published.** Maven
  Central requires a `groupId` whose ownership the project can prove, and `com.legend` is
  not one it controls; the choice is a domain it does own, an `io.github.<owner>`
  namespace, or a FINOS one. In the same decision: `pct`'s `org.finos.legend.lite.pct`
  packages carry somebody else's organization in their name, so the rename is a release
  concern with a deprecation path, not a hygiene bullet.
- Semantic versions instead of a permanent `1.0.0-SNAPSHOT`; a tag-driven `release.yml`
  publishing `legend-lite-core` and the server jar from Bazel (§4.7), with a changelog
  built from PR titles.
- Mark everything outside the declared public surface internal.
- Archive `docs/`. Keep a handful of living documents — getting started, architecture,
  invariants, conformance testing, upstream bumps — and an `adr/` directory for decisions.
  Retire `GATES.md`; it stopped being a running log in phase 0, and history belongs in
  commits, PRs, and release notes.
- Replace the ad-hoc debug environment variables and `System.out` calls with a logging API.
- Remove `progress*.txt`, `progress/`, and editor-specific directories from the repository
  root. `experiments/` stays, outside the build by `.bazelignore`, until
  `legend_library`'s own tests cover what its Bazel prototypes proved — per-element
  caching above all — and then retires with a pointer from the proposal.

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
so two people working in different modules are not actually independent. In Bazel a
dependency has to be declared to exist at all: the `guards` package names what it reads,
and a reviewer sees the edge in the BUILD diff.

**Generated files in pull requests.** After phase 4 most of them are gone from the
repository entirely, which is the best answer to this problem. What §4.8 keeps — the
corpus sources above all — is marked in `.gitattributes` so diffs stay reviewable, is
never hand-edited, and moves in its own commit.

**Determinism.** Pinned clock, locale, encoding, and heap; per-test output directories;
no fixed temp paths. Bazel raises the stakes: it keeps a passing result until the test's
inputs change, so a lucky pass hides an intermittent failure for as long as nobody touches
what the test depends on. Any test that can fail intermittently — native database drivers
under forked JVMs are the known source — gets a quarantine tag and a ticket, never
`flaky = True` or a retry that hides it.

**Cadence, and what it actually costs.** With a required check on every pull request,
`bazel test //...` has to stay fast enough to run on every one — and the plan's promise
that everything lives in the default build is the claim most likely to break here, so it
should be held to evidence.

What is measured today is Maven: a `verify` that omits the corpus lanes, the ChannelB
suites, the H2 relation lane, the H2 stress lane, and the parser sweep against a full
corpus takes 8 min 37 s serially (Appendix A). The gate chain — core's clean compile and
all ten gates — measured 408 s serially and 249 s as three parallel streams on a quiet
developer machine (`docs/GATES.md`, 2026-09-17), and between 4m09s and 4m34s in parallel
on every recorded chain since. Adding up the per-gate times of a parallel run gives ten
minutes and more, but those times are inflated by the other streams; alone, every gate ran
faster. So on a developer's machine the promise does not depend on parallelism: serial is
under seven minutes. Bazel's own numbers do not exist yet; phase 1 takes them.

Caching changes the arithmetic, though less than it seems. A pull request that touches
only `pct` re-runs `pct`'s tests and what depends on them; one that touches `core` — most
do — re-runs nearly everything, because nearly everything depends on `core`. Caching cuts
the cost of the many small changes outside `core`, not of the typical one.

Two things can still break it. The CI runners are smaller: the macOS one has 3 vCPU and
7 GB (`pct/pom.xml`), where one PCT suite's 2–3 GB live set is already a third of the
machine, and Bazel left alone runs as many tests at once as there are cores — so on the
runners the budget is a **memory** budget before it is a time budget, and phase 1 measures
both. And the set is growing: the H2 stress lane, which no gate runs today, is about two
minutes, and the differential gate (§4.4) adds database-mode runs of the corpus and stress
lanes, of which the homework prices the DuckDB corpus run alone at about 100 s.

The project also already has a time budget: four minutes for the parallel chain, set on
2026-09-16 (`docs/STRESS_CORPUS_THROUGH_LITE_2026_09_16.md`, F-R). It already decides what
runs — the H2 stress lane stays out of the chain because the budget cannot carry it
(F-AA) — and the differential gate is to be priced against it. It and "everything in
`bazel test //...`" cannot both hold as lanes join; §9 asks which gives.

If the full set will not fit a pull-request check, the order of retreat is: raise
parallelism within a measured memory ceiling; then run the full set on merge with a
sampled corpus on pull requests; then split the matrix (everything on Linux/21, a smoke
subset elsewhere). Weakening the suite is not on the list. A merge queue keeps two
independently-green pull requests from landing a broken combination.

---

## 7. What gets deleted, and what replaces it

| deleted | lines | replaced by |
| --- | --- | --- |
| Every `pom.xml` — the root, five modules, `tools/engine-runner` | 1,118 | `MODULE.bazel`, its lock files, and a BUILD file per package |
| `.sdkmanrc` | 6 | `.bazelversion`; the JDKs come from the build |
| `tools/allgates.sh` | 471 | `bazel test //...`: a target per lane, Bazel's scheduler, ledgers |
| `tools/bump.sh` | 313 | One version in `MODULE.bazel`, a repin, and the snippet refresh (§4.2); the build generates, `//tools:accept` accepts |
| `tools/version-report.sh` | 270 | One version + a derived-version test |
| `tools/classpath-convergence.sh` | 86 | One version per artifact in the lock file, and the `genquery` test for `core` |
| `tools/oracle-roots.sh` + `tools/oracle-pins.env` | 119 | Pinned jars and archives in `MODULE.bazel` |
| `tools/diagnostics.sh` | 47 | `manual` targets, run by name (or deletion — they are measurements) |
| `tools/corpus-both.sh` | 11 | Two test targets |
| `tools/ci-watch.sh` | 18 | `gh run watch` |
| `.github/workflows/gate.yml`, `gates-run.yml`, `diagnostics.yml` | 377 | One `build.yml` matrix job running `bazel test //...`, which also takes over the `actionlint` job |
| `.github/actions/gate-env` | 58 | `bazel-contrib/setup-bazel` |

About 2,900 lines of POMs, shell, pins, and YAML, replaced by `MODULE.bazel`, BUILD files,
a few macros, and test code that runs identically on a laptop.

---

## 8. Risks

1. **Will `main` be protected?** Phase 0 is a decision, not a task, and every later phase
   depends on it. This is the largest risk in the plan.
2. **Does the whole suite fit in a pull-request check?** On a developer's machine, very
   likely, even serially: the gate chain runs in under seven minutes there under Maven,
   and Bazel must be measured to match it. On the CI runners nobody has measured it,
   memory binds before time, and the set is growing (§6). §6 says what to measure and in
   what order to retreat; phase 1 does the measuring.
3. **Windows.** Bazel supports Windows, and Windows is where Bazel users most often hit
   trouble: path length, symlinks, and runfiles. The plan's answers are the documented
   ones — a short output root, the runfiles library instead of symlink trees, no shell
   rules — and phase 1 proves them on a clean Windows machine before anything depends on
   them.
4. **Error Prone and NullAway under Bazel's Java toolchain.** Bazel runs the Error Prone
   its Java tools bundle, and NullAway's JSpecify mode needs a recent one, on both JDKs.
   If the bundled version is too old, a custom Java toolchain carries a newer one — known
   work, but work. Phase 1 settles it first.
5. **Two builds at once.** From phase 1 until Maven is deleted in phase 3, Maven and Bazel
   both build the same tree, and a test one runs and the other does not is a silent loss.
   Test targets that take their classes by `glob`, and the module-by-module count check,
   keep them equal; deleting Maven in one change gives the overlap an end.
6. **The IDE.** IntelliJ and VS Code need their Bazel plugins, and IntelliJ's Bazel
   support is less mature than its Maven import. `CONTRIBUTING.md` names the plugins, and
   phase 1 tries them on each platform.
7. **Renormalizing line endings.** The index is already clean (§2.2), so `.gitattributes`
   is nearly a no-op — but `prelude.pure`'s mixed endings are exactly the case a careless
   rule would break, and phase 1 verifies the before-and-after with `git ls-files --eol`.
8. **Two packages moving (`corpus` out of `core`, `guards` out of everywhere)** touch many
   files at once. They are cheapest once the ledgers are data and the verdict is Bazel's,
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
ArchUnit would give a better message for less code — and under Bazel ArchUnit can see
other packages' test classes too, through their `testonly` libraries (§4.1), which a Maven
test-jar could not offer. The triage — real behavioral ledger, linter rule, or delete —
belongs in phase 3, and it may be the difference between a package worth having and a
package that preserves a cost nobody examined. The first candidate is
`JavaEvalLedgerTest`: it pins a line count for each file that evaluates in Java, it was
edited by seven of the ten commits from `adbc284ec` to `c062b9bc9`, and two people changing
the same file always conflict on its pin. Not every guard is a candidate, though:
`VerdictChannelRegisterTest` now enforces a design rule — one Java class decides every
equality (`docs/JUDGING_TWO_MODES_2026_09_17.md` §5) — so for it the question is form, not
existence.

**Is the package shape right?** The plan adds `generator`, `corpus`, `projects`, `guards`,
and `upstream-runner` to the existing five without questioning the five. After phase 4,
`spec` is a leftover: its generators have moved to `generator`, and what remains is a
corpus harness and the claims ledger, which may belong with the other conformance
packages. Separately, `nlq` calls an external LLM and key-gates ten of its tests; whether
an LLM-backed package belongs in the same repository as a clean-room compiler is a product
question, not a build one. Ten packages by accretion is the default outcome if nobody
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
puts everything in `bazel test //...`. They cannot both hold as lanes join. Raise the
budget, set it per machine class, or let it decide what runs on pull requests with the rest
on merge (§6's retreat) — the project's call, to make before phase 3 turns the lanes into
targets.

**Is there a shared remote cache?** CI's own caches are enough to start. A remote cache
shared by CI and developers would let a developer's first build reuse CI's work, and the
proposal assumes one for Legend projects at scale — but it costs a service, only CI should
ever write to it (a cache developers can write to can be poisoned), and hits across
platforms need identical inputs, which `.gitattributes` helps provide. A decision for once
the build is Bazel's, not before.

**Three smaller ones, each a genuine trade:** how finely to cut test targets — one per
suite, or one per class, which caches more finely but multiplies JVM starts. Running both
database lanes on every pull request — and, now that database mode exists, both judge modes
on each — is inherited, not reasoned: one gating and the other on merge is a legitimate
option nobody has priced. And upstream bumps are manual by design, but the proposal's own
version-check extension (proven in `experiments/legend_rules_test/`) could open the bump
pull request — red on the ledgers, as it should be — and turn "someone remembers to look"
into a notification.

---

## Appendix A — measurements behind section 2

Taken on `main` at `f659d747a`, ten commits before the state section 2 describes, on
Windows 11, JDK 25 (Temurin 25.0.4.1), Maven 3.9.14, offline against a warm local
repository, with no `legend-engine` / `legend-pure` checkouts present — the state a
newcomer's machine is in. Every number below is at that commit unless it says otherwise.
These are Maven's numbers: the baseline Bazel must match test for test in phase 1, and the
one its warm cache should beat.

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
