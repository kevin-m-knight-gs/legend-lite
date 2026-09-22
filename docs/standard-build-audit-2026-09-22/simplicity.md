# Audit D — Does the Bazel migration actually simplify onboarding?

Repo: `ll-origin` @ `97a32a987`. Plan: `STANDARD_BUILD_PROGRAM.md` (1,178 lines).
All line numbers below are from those two files at the paths given in the task,
unless marked "plan §n" (a section of the plan document, since the plan's own
line numbers shift less usefully than its section numbers).

---

## TASK 1 — Two concept inventories, one counting rule

**Rule used for both sides:** a "concept" is a name — a script, flag, env var,
gate, workflow, filename, package, rule, or gotcha — that a developer must be
able to say out loud to build, test, or change the project, and would have to
look up if they hadn't seen it before. I count each name once. I do **not**
count the 121 individual Python source files, the 247 individual docs/ files,
or the 56 individual project directories as separate concepts — those are
counted as one bucket each on both sides, the same way the plan itself counts
"1,349 lines of shell" as one line-item rather than nine. Where the same rule
applied to both sides would be unfair to one side, I say so explicitly.

### 1(a) CURRENT STATE — from the repo, counted

| # | Category | Count | Items |
|---|---|---:|---|
| 1 | Maven modules in the reactor | 5 | `core`, `spec`, `nlq`, `pct`, `parser-equivalence` (`pom.xml:` `<modules>`) |
| 2 | Projects outside the reactor | 1 | `tools/engine-runner` (own `pom.xml`, groupId `perf`, depends on `org.finos.legend:legend-lite-core:1.0.0-SNAPSHOT` — a groupId/artifact this reactor's root POM does not publish as `org.finos.legend`; root POM's groupId is `com.legend`) |
| 3 | Entry-point scripts (invoked by name) | 15 | `tools/`: `allgates.sh`, `judge-lanes.sh`, `bump.sh`, `version-report.sh`, `classpath-convergence.sh`, `oracle-roots.sh`, `diagnostics.sh`, `corpus-both.sh`, `ci-watch.sh` (9, 1,349 lines total, `wc -l tools/*.sh`); `scripts/`: `census_gate.py`, `generate_pure_constants.py`, `outstanding.py`, `walldepth.py`, `corpus/run.py`, `projects/check.py` (6) |
| 4 | Environment variables that change behaviour | ~30 | Build/test-relevant: `LEGEND_ENGINE_ROOT`, `LEGEND_PURE_ROOT`, `LEGENDLITE_PCT_BACKEND`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `LLM_PROVIDER`, `GATES_PARALLEL`, `MVN_OFFLINE`, `ORACLE_PIN_CHECK`, `PINS_ONLY`, `CHECK_ONLY`, `QUIET`, `PORT` (13) + debug/trace switches read by `System.getenv`: `LEGEND_LITE_CARRY_TRACE`, `LEGEND_LITE_DUMP_SQL`, `LEGEND_LITE_NAVDATE_TRACE`, `LEGEND_LITE_PREP_TRACE`, `LEGEND_LITE_PROGRESS`, `LEGEND_LITE_RAW_EXPAND_TRACE`, `LEGEND_LITE_SPLIT_TRACE`, `LEGEND_LITE_STACKS`, `LEGEND_LITE_STAMP_TRACE`, `LL_DUMP_RESOLVED`, `LL_LINEAGE_DEBUG`, `LL_ORD_COUNT`, `LL_STAMP_COUNT`, `LL_TDG_DEBUG`, `LL_TMP_DEBUG`, `LL_TMP_SQL`, `LL_TOL_COUNT` (17). CI-internal `GITHUB_*` plumbing excluded — a developer changing local behaviour never needs those. |
| 5 | `-D` system properties | 50 | 40 distinct keys read via `System.getProperty("…")` in Java (`grep -rhoE 'System\.getProperty\("[a-zA-Z0-9._]+"'` → exactly 40, including `legend.engine.root`, `legend.pure.root`, the five `*.generate` flags (`claims.generate`, `dynafn.generate`, `imports.generate`, `natives.generate`, `prelude.generate`), `ladder.record`, `legend.judge.mode`, `legend.judge.ledger.host`, `rcorpus.backend`, `stress.backend`, plus ~10 more that neither the plan nor any doc names: `chb.only`, `eager.world2`, `fixture.dump`, `legend.corpus.containing`, `legend.exec.engineScanOrder`, `legend.lite.root`, `legend.mapping.trace`, `legend.spec.trace`, `natives.bootstrap/debug/dump`, `owncorpus.generate`, `prelude.census`, `prelude.m3`, `rcorpus.detachTrace/test/trace`, `roster.generate`, `stress.only/sessions`, `tier2.classes`) + ~10 Maven/Surefire mechanics flags used from the command line or POMs (`skipTests`, `test`, `groups`, `surefire.excludedGroups`, `surefire.failIfNoSpecifiedTests`, `mdep.includeScope`, `mdep.outputFile`, `includeScope`, `outputFile`, `h2.version`) |
| 6 | Gates | 11 | Gate 1 through Gate 11 in `tools/allgates.sh` (`grep -noE 'gate ?[0-9]+' tools/allgates.sh`), each with its own ceiling constants (e.g. `G7_MIN_RUN`, `G7_MAX_FAIL`, `G7_MAX_ERR`, `G9_LINE`, `MIN_PASS`, `MIN_PASS_H2`) |
| 7 | CI workflows + composite action | 4 | `gate.yml` (108 lines), `gates-run.yml` (214), `diagnostics.yml` (56), `.github/actions/gate-env/action.yml` (58) — 436 lines total |
| 8 | Magic filenames | ~10 | `.sdkmanrc` (Windows can't read it), `tools/oracle-pins.env`, `docs/GATES.md` (4,841 lines / 768 KB, grows almost every commit — 51 of the last 52 wrote to it), `native-claims.tsv` (829 rows), `native-membership.tsv` (787 rows), `prelude.pure` (mixed line endings, load-bearing), `census-baseline.json`, `h2-fail-roster.txt`, the ~13-file family of `rcorpus/*` register TSVs added in one three-week window, `projects/CONTRACT.md` + `projects/FINDINGS.md` |
| 9 | Guard/census/ledger/roster test classes | ~52 | Plan's own count (plan §9); independently corroborated — 31 found by name glob alone (`*Census*Test`, `*Ledger*Test`, `*Guard*Test`, `*Register*Test`, `*Roster*Test`), more exist under other names the plan itself names in §2.3 (`HarnessDisciplineTest`, `ParserBoundaryArchTest`) and AGENTS.md (`PctDisciplineTest`) |
| 10 | Backend/database concepts | 4 | DuckDB, SQLite, H2 default (`2.1.214`, `pct/pom.xml`), H2 gate-7 override (`2.4.240`, command-line `-D`) |
| 11 | Load-bearing gotchas a newcomer must be told or gets burned by | 8 | (1) `clean` is required before `-pl core test` or NullAway silently no-ops (`docs/GATES.md:864`); (2) `-pl <module>` resolves `core` from `~/.m2`, not the reactor (`README.md:266-268`, `AGENTS.md:360-362`); (3) `nlq`'s test model has a duplicate `provision` enum value that fails a fresh clone (`nlq/src/test/resources/nlq/cdm-model.pure:2574-2575`); (4) `ErrorShapeGuardrailTest` walks a deleted `../engine` directory and silently no-ops because it's gone; (5) `tools/engine-runner`'s POM is parentless with a mismatched groupId, invisible because nothing builds it; (6) gate 7's H2 version override exists only on a shell command line, not in any POM; (7) `-Dpct.reuseForks=false` is set by CI only, undocumented for local runs; (8) no `LICENSE`, `NOTICE`, `CONTRIBUTING.md`, `CODEOWNERS`, `.editorconfig`, or `.gitattributes` anywhere in the repo |

**Total, current state: ≈ 190 named concepts** (6 + 15 + 30 + 50 + 11 + 4 + 10 + 52 + 4 + 8, per the rows above). This excludes, as buckets rather than enumerations: the 121 Python files / 56,094 lines under `scripts/` and `tools/` (`find . -name '*.py' -not -path '*/target/*' | wc -l`), the 247 Markdown files at the top of `docs/` with no index, and the 56 individual project names under `projects/`.

### 1(b) TARGET STATE — from the plan, counted the same way

| # | Category | Count | Items |
|---|---|---:|---|
| 1 | Root workspace files | 6 | `MODULE.bazel`, `.bazelversion`, `.bazelrc`, `.bazelignore`, `.gitattributes`, `.editorconfig` (plan §4.1, §4.6, phase 1) |
| 2 | Named `.bazelrc` configs | 3 | `quick`, `jdk21`, `jdk25` (phase 1) |
| 3 | Packages/directories | 10–11 | The plan's own tally is "the existing five" (`core`, `spec`, `nlq`, `pct`, `parser-equivalence`) **+ 5 new** (`generator`, `corpus`, `projects`, `guards`, `upstream-runner`) = 10 (plan §9: "Ten packages by accretion..."); its own workspace tree (§4.1) lists 11 directories once `tools/` (rules/macros) is included |
| 4 | New build/rule concepts | 5 | `legend.bzl`, one shared test macro (clock/locale/encoding/heap), `legend_library` (two forms — coarse v1, per-element v2), `java_export`, the `genquery`-based closure test pattern |
| 5 | Bazel primitives a newcomer must learn | ~15 | `rules_java`, `rules_jvm_external`, `contrib_rules_jvm`, `http_file` (4 toolchains/rulesets); `java_binary`, `java_library`, deploy jars, the runfiles library, `TEST_TMPDIR` (5 primitives); test `size` as an axis (1); tags `manual`, `heavy`, `testonly`, quarantine (4 tags) |
| 6 | `//tools:accept` + the two-kinds-of-baseline distinction | 2 | A new bespoke tool, plus the "test outcome" vs. "derived file" taxonomy it has to straddle (plan §4.5, §4.8) |
| 7 | Ledger/register file family | 1 (bucket) | Expected-failure TSVs, differential-gate ledger (host-produced/database-consumed), the derived-version test, the `genquery` closure test — but note: the plan keeps **two** baseline *kinds*, not one (see Task 3) |
| 8 | Judge/lane concepts carried forward unchanged | 5 | Judge mode (`host`/`database`), the differential gate, and per-lane targets for DuckDB, H2, and H2-stress |
| 9 | CI | 5 | `build.yml`, `release.yml`, `corpus-generators.yml` (3 workflows); `bazel-contrib/setup-bazel` (1 action); the os × jdk-config matrix as a concept developers must reason about (1) |
| 10 | Portability/hazard concepts (§4.6) | 6 | `.gitattributes` eol rule + the `*.pure -text` exception; Windows short output root (`--output_user_root`); long-path support; runfiles-not-symlinks; the locale/timezone/encoding pin; the case-insensitive-filesystem guard test |
| 11 | Governance/process additions (phase 0, phase 6) | 7 | Branch protection + required check; `CODEOWNERS`; a declared public surface (`com.legend.Compiler` + the HTTP API); semantic versioning + tag-driven release; the namespace decision (`io.github.*` vs. a FINOS namespace); an `adr/` directory; "six living documents" that survive the doc archive |
| 12 | Open design questions baked into the plan (§9) | 5 | Whether the ~52 guard/census tests should exist at all; whether the package shape (10 packages) is right; whether the corpus belongs in this repo; whether the four-minute budget survives; whether there's a shared remote cache |

**Total, target state: ≈ 70–75 named concepts** (6+3+10+5+15+2+1+5+5+6+7+5 ≈ 70, using the low end of row 3 and the plan's own framing).

### Verdict on Task 1

The target state is **smaller — roughly 190 → 70, about a 60% cut — but not
dramatically smaller**, and three qualifications matter more than the
headline number:

1. **Most of the cut is a relabeling, not a deletion of decisions.** The 50
   `-D` properties and ~30 env vars mostly become Bazel `tags`, `size`, named
   `.bazelrc` configs, or per-lane target names. A developer still has to
   learn "there is a DuckDB-lane target and an H2-lane target and an
   H2-stress-lane target and both a host-judge and a database-judge" — that
   is the same number of *decisions* as `-Drcorpus.backend=h2` plus
   `-Dlegend.judge.mode=database`, just spelled as target names instead of
   flag values. Discoverable via `bazel query //...` instead of grep, which
   is a real win, but not a concept removed.
2. **The plan adds a whole new tool a Java shop doesn't have today**: Bazel
   itself, Bazelisk, `MODULE.bazel`, `BUILD` files, and the entire
   `rules_java`/`rules_jvm_external`/`contrib_rules_jvm` stack. Section 4.0
   concedes this ("Bazel is not a tool every Java developer already has")
   but the concept count above still has to carry it — row 5 alone is 15
   concepts that do not exist today in any form.
3. **Five to seven of the plan's own "simplifications" are unresolved
   questions, not delivered simplicity** (row 12, plan §9): the package
   shape, the guard-test population, the corpus's very presence in the repo,
   and the four-minute budget are all open. If they resolve toward
   "accretion" — the plan's own word — the real target count could land
   closer to 90–100, eroding a third of the claimed gain before an
   implementer touches a `BUILD` file.

So: real reduction, worth doing, but the plan's own prose ("any person can
onboard quickly... without many concepts") oversells it. The honest framing
is "fewer flags to memorize, replaced by a bigger, more uniform tool a
newcomer must still install and learn" — which is a good trade for a project
run by many developers over years, and a much smaller win for the stated
goal of someone understanding everything easily on day one.

---

## TASK 2 — Complexity the plan adds that it doesn't need

1. **`generator` as a new top-level package.** Its entire job (plan §4.1) is
   to run as a build action *inside* `core`'s `BUILD` file and feed `core`'s
   compile step. Rule 6 of the plan itself says "BUILD files beside the code
   they build." A `core/generator/` subpackage with its own `BUILD` file
   would satisfy the acyclic-dependency goal (generator depends on upstream
   only, `core` depends on generator's output) without adding a new
   top-level directory a newcomer has to place in the mental map of "what are
   the modules." Nothing in §4.1's justification requires a *sibling*
   package instead of a *nested* one — that choice is presented as settled,
   not argued for.

2. **`guards` as a new top-level package whose justification the plan itself
   has not settled.** Section 9 asks outright whether the ~52 tests it will
   hold should exist at all ("a test that reads another module's Java source
   to enforce a convention is usually a linter rule wearing a test's
   clothes... Checkstyle, Spotless, or ArchUnit would give a better message
   for less code"). Creating a dedicated package to house a population the
   plan admits might mostly be deleted is building the house before deciding
   who lives in it. The simpler order: triage first (phase 3's own §9
   question), *then* decide whether what survives needs its own package or
   just an ArchUnit rule set inside `core`'s existing test tree.

3. **The two-kinds-of-baseline distinction (§4.5 vs §4.8).** A "test
   outcome" baseline (candidate from a test run) and a "derived file"
   baseline (candidate from a build action) are given different provenance
   stories, but `//tools:accept` has to "copy either kind into the
   workspace" regardless — i.e., the tool already treats them uniformly at
   the point of use. The distinction buys nothing for the newcomer beyond an
   extra thing to keep straight; a single model — "every baseline is a
   committed file; some candidate producer writes a new one; `accept` copies
   it over; a test compares them" — covers both cases the plan describes and
   is what the corpus lanes already do today (`h2-fail-roster.txt`, plan
   §4.5).

4. **Splitting `corpus` out of `core` for a reason the plan calls dependency
   hygiene, not performance.** Plan §4.1 says explicitly: "for what they
   depend on rather than for speed." That is real (it removes `core`'s
   dependency on `../projects`), but it is a smaller, more mechanical fix
   than a new package name suggests — it could equally be phrased as "move
   the stress-corpus sources into `spec` (which is already the corpus/harness
   package) and declare its dependency on the eleven `projects` targets
   there," using an existing name instead of minting a new one. The plan
   itself notes in §9 that after phase 4 "`spec` is a leftover... which may
   belong with the other conformance packages" — i.e., it already suspects
   `corpus` and `spec` should be one thing, and creates two anyway.

5. **Carrying the JDK 21/25 axis into the developer's everyday vocabulary.**
   Acceptance criterion 2 requires both JDKs to pass, and phase 1 gives the
   axis two named `.bazelrc` configs (`jdk21`, `jdk25`) that sit next to
   `quick` in the same "three commands" table a newcomer reads first (§1).
   But the plan's own §4.0 argues the *platform* matrix (Windows/macOS/Linux)
   is "a property of having three machines, not of the build" and keeps it
   CI-only. The JDK axis is symmetric — Bazel downloads both toolchains and a
   developer only needs to run the default (21) locally; NullAway/JDK-21
   parity is exactly the kind of thing CI verifies on merge, not something
   `bazel test //...` must expose as a first-class config choice on day one.
   Folding it into CI-only (a fourth `build.yml` matrix cell) rather than the
   developer-facing config surface removes two names from the newcomer's
   day-one vocabulary at zero loss of coverage.

Honorable mention, not in the top five because the plan is candid about it:
`upstream-runner` is a pure rename of `tools/engine-runner` with no behavior
change — churn, but cheap churn, and the plan says so.

---

## TASK 3 — Simplifications the plan misses

1. **Fewer packages than even the plan's revised 10.** Per §9's own
   admission that `spec` becomes "a leftover" after phase 4 and that `nlq`'s
   LLM dependency raises a "product question, not a build one," a genuinely
   minimal layout is: `core` (compiler + server), `conformance` (today's
   `spec` + `pct` + `parser-equivalence` + the new `corpus`, all of which do
   one thing — differentially test `core` against upstream — merged into one
   package with subdirectories), `nlq` (kept separate specifically *because*
   it is a different kind of thing, per §9), `projects` (the 56 Legend
   projects), and `tools` (rules/macros). That is 5 packages, not 10, and it
   resolves §9's own open question about package shape instead of deferring
   it into phase 3's triage.

2. **One ledger format, not "two kinds of baseline."** Fold §4.5's
   test-outcome ledgers and §4.8's derived-file ledgers into one schema: every
   expectation is a row-shaped TSV with (subject, reason, date/task), full
   stop — a census, a roster, and a claims ledger are all instances of the
   same three-column shape. The plan's own text shows the corpus lanes
   already converged on this by pressure (§4.5, the paragraph on
   `h2-fail-roster.txt`) — the plan should generalize that convergence into
   the *rule*, not keep a second taxonomy alive for the claims ledger and the
   generated-constant tests.

3. **Kill the 40 `System.getProperty` switches instead of quietly porting a
   third of them.** The plan names and disposes of about 11 of the 40:
   `legend.engine.root` / `legend.pure.root` (retired with the checkouts),
   the five `*.generate` flags plus `ladder.record` (become `bazel run`
   targets), `legend.judge.mode` / `legend.judge.ledger(.host)` (become a
   build action + `data` input), `h2.version` (becomes a second pinned
   repository), `pct.reuseForks` (moot once every suite is its own target).
   The other ~29 — `chb.only`, `eager.world2`, `fixture.dump`,
   `legend.corpus.containing`, `legend.exec.engineScanOrder`,
   `legend.lite.root`, `legend.mapping.trace`, `legend.spec.trace`,
   `natives.bootstrap/debug/dump`, `owncorpus.generate`, `prelude.census`,
   `prelude.m3`, `rcorpus.detachTrace/test/trace`, `roster.generate`,
   `stress.only/sessions`, `tier2.classes`, and more — are never mentioned.
   Each one is either dead (delete it), a debug aid (make it a `-Dverbose`-
   style single switch or a logging level, not forty separate names), or a
   test-selection hack that Bazel's own `--test_filter`/target selection
   replaces outright. The plan should inventory and adjudicate every one,
   not just the ones its own narrative happened to touch.

4. **Delete, don't migrate, the ~121 ungated Python scripts (56,094 lines;
   `scripts/` alone is 76 files / 49,547 lines).** The plan's own position
   (§4.8, §9 phase 5) is to keep the corpus generators as committed,
   Python-only, checked by a *scheduled* (not gating) workflow — "no
   developer needs Python." That is a defensible middle position for the
   ~76 files that generate the ~487k-line stress corpus. It is not a
   position on the other ~45 Python files under `tools/` (`census_gate.py`,
   `outstanding.py`, `walldepth.py`, `native-axes.py`, `upstream-drift.py`,
   `golden_shape_survey.py`, `scoreboard.py`, `tools/metamodel-census/`,
   `tools/spikes/`) — phase 5 says only that these get "declared maintainer
   tooling or deleted," with no triage performed here. A simpler build
   should perform that triage as part of *this* plan, not defer it, and
   should default to deletion: a script with no test asserting its output is
   current is not "tooling," it's an undocumented liability with a
   `.py` extension.

5. **Answer, don't relocate, the ~52 ledger/census/guardrail tests.** Phase 3
   moves the nine cross-module ones into `guards`; §9 raises but explicitly
   declines to answer whether they should exist. A simpler plan settles this
   *before* choosing a package for the survivors: for each of the ~52,
   classify as (a) a real behavioral invariant worth a test (e.g.
   `VerdictChannelRegisterTest`, which §9 itself exempts as "a design rule,"
   or `SkipCensusTest`, which the plan's rule 3 relies on), (b) a linter rule
   better served by ArchUnit/Checkstyle with a real error message, or (c) a
   scalar ratchet with no behavioral content, only a comment about who moved
   it last — like `JavaEvalLedgerTest`, edited by 26 of 31 and then 17 of 21
   consecutive commits (§4.5) — which is a strong candidate for deletion, not
   relocation. The plan's own numbers (twenty-two moves of one pin in one
   window, a 6,708-character provenance comment that a single cleanup commit
   then discarded) are the evidence that this category is mostly (c).

6. **Stop counting the OS matrix and the JDK matrix as different kinds of
   thing while treating one as CI-only and the other as a developer-facing
   config** (see Task 2, item 5) — this is a missed simplification as much as
   an unneeded addition; naming it once covers both tasks.

---

## TASK 4 — The onboarding path, concretely

**Documents a newcomer is told to read, and in what order.** `README.md:5`
is the pointer chain: *"Start here: `AGENTS.md` for the invariants,
`core/README.md` for the per-package spec, `docs/GATES.md` for what must be
green."* `FAQ.md` is not linked from `README.md`, `AGENTS.md`, or
`core/README.md` at all — the only files that link to it are three buried
`docs/` audit documents (`docs/ARCHITECTURE_REMEDIATION.md`,
`docs/MODEL_TO_MODEL.md`, `docs/COMPILER_STAGE_AUDIT_2026_08.md`), so a
newcomer only finds it by browsing the top-level directory listing, not by
following the repo's own instructions.

**Document sizes, in the order given:** `README.md` 486 lines,
`AGENTS.md` 364 lines, `core/README.md` 440 lines, `docs/GATES.md` 4,841
lines (768 KB). That is **6,131 lines across the four documents README.md
itself points to**, before the newcomer has written one line of code — and
`docs/GATES.md` is explicitly a growing log, not a stable reference (51 of
the last 52 commits wrote to it; it grew from 4,412 lines at `a16a1ae16`
three days earlier). Add the orphan `FAQ.md` (386 lines) and the total a
diligent newcomer who also finds it reads before touching anything is 6,517
lines.

**The first command a newcomer runs, and whether it works.** Following
`README.md`'s own Quick Start (`README.md:259-263`):
```
mvn clean install -DskipTests   # works
mvn -pl core clean test         # works, but see test-count claim below
mvn -pl engine test             # FAILS — no such module: `engine` does not
                                 # exist in this repo (`pom.xml`'s <modules>
                                 # lists core, spec, nlq, pct,
                                 # parser-equivalence — no engine)
```
The third command in the README's own three-line Quick Start does not run.

**The first thing that misleads a newcomer.** It is this same line
(`README.md:262`), for two compounding reasons: (1) the module doesn't exist,
and (2) `AGENTS.md` — the very first document `README.md` sends the
newcomer to — says on its own first content heading, `AGENTS.md:72`, *"Read
this first: ONE tree — the engine module is deleted (2026-08-11)"*. The
newcomer is told to read `AGENTS.md` first, and if they do, `AGENTS.md`
immediately contradicts the document that sent them there — six weeks
before the commit under audit.

### Every wrong instruction in `README.md` at `97a32a987`

| Line(s) | Claim | Why it's wrong |
|---|---|---|
| `README.md:65-68` | `engine/src/main/java/com/gs/legend/` described as a live "frozen legacy implementation" with live HTTP server, LSP, diagram service | The `engine` module does not exist — `ls engine` → "No such file or directory"; deleted per `AGENTS.md:72-89` on 2026-08-11 |
| `README.md:212, 242-245` | Package Structure section lists `engine/src/main/java/com/gs/legend/` — "341 files, 47,949 LOC" — as a real, present directory | Same: does not exist |
| `README.md:216` | `core/src/main/java/com/legend/` is "418 files, 120,629 LOC" | Actual: 693 files, 210,409 LOC (`find core/src/main -name '*.java' | wc -l`; `... | xargs wc -l`) — core has grown by ~65% in files and ~75% in LOC since this line was written |
| `README.md:261` | `mvn -pl core clean test` is "the compiler: 1,713 tests" | Actual: 268 test source files and 4,358 `@Test`-annotated methods in `core` today (`grep -rn "@Test" core/src/test | wc -l`), consistent with the plan's own Appendix A measurement of 4,461 tests for `core`'s `mvn verify` |
| `README.md:262` | `mvn -pl engine test` — "integration + corpus: 2,730 tests (default suite)" | The module does not exist; the command fails immediately with "Could not find the selected project" |
| `README.md:274-275` | `mvn exec:java -pl engine -Dexec.mainClass="com.gs.legend.server.LegendHttpServer"` | **Wrong module and wrong class.** The module `engine` does not exist. The class is `com.legend.server.LegendHttpServer`, and it lives in `core` (`core/src/main/java/com/legend/server/LegendHttpServer.java`), not in any `engine` module, and not under `com.gs.legend`. This is the "wrong main class" the task asked to find. |
| `README.md:287-301` | HTTP API table: most endpoints marked "Served by: legacy" or "core (legacy front/back)", describing routing through the frozen `engine` module | There is no legacy module to route through; per `AGENTS.md:77-78` the server shell moved into `core com.legend.server` entirely |
| `README.md:451` | `mvn -pl engine test # integration + the relational corpus scoreboard` | Same wrong-module error, repeated in the Testing section |
| `README.md:464` | "Project Stats... Derived 2026-08-06" | Six weeks stale at the audited commit, and stale by the plan's own account of the module's 2026-08-11 deletion — i.e., these stats were already wrong on the day they were written into this file, since `engine` was deleted five days *before* 2026-08-06's stats claim to have measured it truthfully (the dates are inconsistent with each other, not just with today) |
| `README.md:469` | `core (the compiler) 418 files / 120,629 LOC main; 91 test files` | See LOC line above; also 91 test files vs. actual 268 |
| `README.md:470` | `engine (legacy + server) 341 files / 47,949 LOC main; 104 test files` | Module does not exist |
| `README.md:473` | `core tests 1,713` | See above; actual ballpark is ~4,358–4,461 |
| `README.md:474` | `engine tests 2,730 (default suite; heavy group excluded)` | Module does not exist |
| `README.md:80` | Comparison table: "Legend Lite... ~171K LOC, 5 modules (compiler: 120K)" | The "5 modules" count is coincidentally right (`core, spec, nlq, pct, parser-equivalence`), but the LOC figures are stale (`core` alone is now 210K+) and the document elsewhere (lines above) describes those five modules as if `engine` were a sixth, live one |
| — | Missing modules from the Package Structure and Project Stats sections | `spec`, `pct`, and `parser-equivalence` — three of the five real, currently-building Maven modules — are never mentioned in either section; the document's structural picture of the project is built entirely around one module that doesn't exist (`engine`) and omits three that do |

Two further documents in the same reading path are themselves stale, which
compounds the problem rather than correcting it:

- **`core/README.md`** — the document `AGENTS.md:63` calls the *authoritative*
  spec — still describes, in its own first section (`core/README.md:1-29`), a
  "Strangler Fig" migration in which `core/` "lives alongside `engine/`"
  and a wall ("Nothing under `com.legend.*` may import anything under
  `com.gs.legend.*`") enforced partly by "`core/pom.xml` does not declare
  `legend-lite-engine` as a dependency." `engine` was deleted over a month
  before the audited commit; there is no more wall to maintain because
  there is nothing on the other side of it.
- **`FAQ.md`** — even more stale than `README.md`: "~25K LOC, 3 modules" and
  "19 seconds (clean + 955 tests)" (`FAQ.md:8-14`), against a present-day
  reality of 5 modules, `core` alone over 210K LOC, and a full `mvn verify`
  measured at 8 minutes 37 seconds (plan Appendix A). It also still tells
  the newcomer to run `mvn clean test -pl engine` (`FAQ.md:313-328`) — the
  same nonexistent module.

**A second, separate onboarding hazard, not a factual error but a design
one:** `AGENTS.md:320-323` warns that `ArchitectureTest` uses its own
numbering ("6g", "7a-c") that does not map to `AGENTS.md`'s invariant list,
and that `core/README.md` has *its own, separate* 12-invariant list, also
cited by number from code — "Do not merge or renumber either." A newcomer
following the repo's own recommended reading order is handed two
independently-numbered invariant lists in the first two documents they read,
with an explicit warning not to conflate them. That is the "many concepts"
problem the plan is nominally trying to solve, present in the *documentation*
layer, independent of the build system entirely.

**Count, for the record:** the newcomer following `README.md`'s own pointer
chain is told to read 4 documents totaling 6,131 lines (6,517 including the
un-pointed-to `FAQ.md`) before the first command in the first of those
documents (`mvn -pl engine test`) has even been shown to fail.

---

## TASK 5 — The minimal onboarding contract

This is written for the repository **as it exists today** (Maven, no Bazel),
deliberately — one honest finding of this audit is that most of the
onboarding damage documented in Task 4 is a documentation-hygiene problem,
not a build-system problem, and a five-minute `CONTRIBUTING.md` could be
shipped this afternoon, before any Bazel work starts, and would fix most of
what a newcomer actually trips over. This file assumes gates 1, 2, 4/5, 6/7,
8, 9, 10 (11 is opt-in; see `tools/judge-lanes.sh`) and does not try to
enumerate every gate — that is `docs/GATES.md`'s job once it's disentangled
from being a running log.

```markdown
# Contributing to Legend Lite

## Install

- Java 21 (Temurin). Maven 3.9+.
- Optional: `GEMINI_API_KEY` in your environment — only needed for `nlq`'s
  LLM-backed tests; everything else runs without it.

## Run it

    mvn clean install -DskipTests

This builds every module. It does not run tests.

## Prove it works

    mvn -pl core clean test

`clean` is not optional here — a warm `target/` silently skips the null-safety
check. This is the compiler's own test suite (~4,400 tests) and is the one
command you should run after any change to `core/`. It's green on a clean
clone; if it isn't, something's wrong with your machine, not the code.

To run everything (all five modules, both database backends, the full
conformance suite against upstream Legend):

    mvn -pl core install -DskipTests   # always do this after touching core —
                                        # -pl <module> resolves core from
                                        # ~/.m2, not this checkout, so a
                                        # downstream module will otherwise
                                        # silently test the OLD core
    mvn -pl spec test
    mvn -pl nlq test
    mvn -pl pct -o test
    mvn -pl parser-equivalence -am test

The last two need local checkouts of `legend-engine` and `legend-pure` at the
commits named in `tools/oracle-pins.env`. Without them, those two suites
report skips, not failures — that's expected on a laptop that hasn't set the
checkouts up, and it's not something you need for `core`-only work.

## Change something and prove it still works

1. Make your change.
2. `mvn -pl core clean test` (or the module you touched — see the reactor
   note above).
3. If your change touches `core`, run `mvn -pl core install -DskipTests`
   before testing any module downstream of it.
4. If a test's expected output changed on purpose (not a regression), the
   test itself will tell you which committed file to update — these are
   rows in a `.tsv`, not numbers to hand-edit. Don't touch a ratchet
   constant without a comment naming the change and the reason.
5. Before pushing: `mvn clean verify` from the repo root, once. That's the
   full picture, and it's what CI runs.

## Where things live, in one paragraph

`core/` is the whole compiler and server — there is no other "engine"
module; if you see one mentioned in an older doc, it was deleted and the
doc is wrong. `spec/` holds the relational-corpus harness and the
"claims ledger" (what the compiler claims to support, checked against
upstream). `pct/` runs legend-pure's own conformance suite against us.
`parser-equivalence/` checks our hand-written parser produces byte-identical
output to legend-engine's ANTLR one. `nlq/` is natural language → Pure,
separate because it's the one module that calls an external LLM.

## If something feels wrong

Read `AGENTS.md` before touching `core/` — it's short, and it's the one
document in this repo that's kept current on purpose. Don't trust test
counts or module lists in any other document; run `find . -name pom.xml`
and `mvn -pl core test` to see the truth for yourself.
```

That is roughly a 500-word, five-minute read. It deliberately omits:
docs/GATES.md (a log, not a contract), the shell scripts under `tools/`
(maintainer tooling — a contributor changing `core/` never needs them), the
40 system properties (only a handful — `LEGEND_ENGINE_ROOT`/`LEGEND_PURE_ROOT`
for the two checkout-dependent suites — matter to a first change), and every
gate number (the developer's actual obligation is "the suite for the module
you touched is green," which is a true, checkable statement without needing
to know there are eleven gates with names).
