# Audit of `docs/STANDARD_BUILD_PROGRAM.md`

Audited at **`97a32a987`** — the commit the plan itself names as the state it
was last re-checked against, so plan and repository are compared like for like.
The copy audited was verified byte-identical to `kmk/plan`'s
`docs/STANDARD_BUILD_PROGRAM.md` before a line of this was written.

**The evidence rule this audit followed.** A claim is established by a
`file:line` citation or by the output of a command that was actually run.
No document is evidence for anything — not `README.md`, not `docs/GATES.md`,
not `AGENTS.md`, and not the plan under audit. Several of those are
demonstrably stale, which is one of the findings. Where a claim could only be
supported by prose it is marked UNVERIFIABLE rather than accepted.

**Headline: the plan is accurate and should be adopted.** Of 75 checkable
assertions, 61 CONFIRMED, 6 IMPRECISE (right in direction, off in a count),
5 UNVERIFIABLE, 2 STALE, 1 REFUTED. The single refutation is a citation to a
commit SHA that does not exist in this repository (§4.1's `b5ad0b82b`). The
plan's most load-bearing technical claim — that upstream's `.pure` sources ship
byte-identical inside the published jars, so the two source checkouts can be
replaced by pinned artifacts — was **re-measured at the current pin and holds**.

What follows is what the audit adds, not a restatement of what the plan already
says. Four things are new: a measured answer to a question the plan does not
ask (§1), a reconciliation that finds 76 items the plan never mentions (§2), a
defect class the project's own guard cannot see (§3), and a runbook for a
decision taken during the audit (§4).

| file | what it holds |
| --- | --- |
| `claims.tsv` | all 75 claims, one row each, with verdict and evidence |
| `completeness.tsv` | 135 inventory items reconciled against the plan |
| `simplicity.md` | both concept inventories, counted by one rule, and a draft onboarding contract |
| `package-graph.py` | the measurement tool behind §1 — re-runnable, takes `--move` to price a refactor |
| `package-graph-before.txt`, `package-graph-after.txt` | its output, before and after the §1 precondition |
| `cycles.py`, `cycle-homework.txt` | the per-cycle minimum-cut analysis behind §1.2 |

---

## 1. The question the plan does not ask: can `core` actually be cut?

The plan's phases assume a BUILD file per package (§4.1, rule 6). Nothing in it
measures whether `core`'s package graph permits that. It does not.

A `java_library` cannot contain half of a dependency cycle, so Bazel's compile
incrementality is bounded by the largest strongly-connected component of the
package graph. Measured with `package-graph.py`:

```
files=693  packages=31  package-edges=213
build units after collapsing cycles: 7
CYCLE: 25 packages / 667 files (96% of the tree)
```

**`bazel build //core` today would produce one `java_library` of 667 files.**
Compile incrementality would be identical to Maven's: change anything, rebuild
everything. The plan's central day-to-day promise does not arrive for `core`
without the work below.

**Why an import-only analysis misses this.** Java requires no `import` for a
fully-qualified reference, and this codebase writes **4,691** of them
(`@com.legend.Nullable`, `com.legend.protocol.spec.ValueSpecification`). 161
package edges are carried *only* that way, invisible to import-based tooling.
Comments and string literals are stripped before counting, so a javadoc
`{@link}` does not manufacture an edge — that correction matters, because
stripping line-by-line rather than whole-file leaves multi-line block comments
in place and inflates the result from 5 packages to 17.

### 1.1 The precondition: two files

`Nullable.java` and `NonNull.java` are zero-dependency marker annotations that
live in `com.legend` — the same package as `Compiler.java`, the top-level
orchestrator. Every package depends on `com.legend` for the annotation;
`com.legend` depends on every package to orchestrate. That single accident
welds the module shut.

Priced by simulation (`--move com.legend.Nullable=com.legend.annot --move
com.legend.NonNull=com.legend.annot`):

| | build units | largest cycle | units under 100 files |
| --- | ---: | --- | ---: |
| today | 7 | 25 pkgs / 667 files | 3 of 7 |
| after moving 2 files | 25 | 5 pkgs / 209 files | 11 of 25 |

A zero-behaviour-change refactor of two files is the precondition for every
incrementality claim the plan makes about `core`. It belongs in phase 1, before
the first BUILD file.

### 1.2 The homework for what remains

Four cycles survive the annotation move. `cycles.py` computes, for each, the
minimum-weight set of edges whose removal makes it acyclic, and cites every
crossing reference.

| cycle | files | cheapest cut | verdict |
| --- | ---: | --- | --- |
| `lowering` ↔ `resolver` | 130 | **1 edge, 2 sites, 1 file** — `AsorRef` at `lowering/SnapshotEnvelope.java:134,140` | **do it** |
| `protocol` ↔ `protocol.spec` | 49 | 1 edge, 56 sites (`SourceInfo`×43) | leave as one target |
| `parser` ↔ `parser.section` | 44 | 1 edge, 34 sites — `ElementParser` naming concrete `*SectionGrammar` types | leave, or invert later |
| `compiler.*` (5 packages) | 209 | **no cut of ≤3 edges exists** | leave as one target |

The `lowering`/`resolver` cut is the second cheap win and the most valuable one
after the annotations: two references to one type in one file, and it separates
the 72-file lowering target from the 58-file resolver target — the area this
project's own diagnosis names as its densest defect concentration.

Measured end state after both moves — two files relocated, two lines edited:

```
build units 26, largest 209 files, 12 units under 100 files
```

**A hypothesis this audit tested and refuted.** Three of four cycles looked
like the `Nullable` pattern — shared leaf types stranded in the upper package —
so the obvious next move was to extract `SourceInfo`, `TypeExpression` and
`Multiplicity` into a leaf package. Simulated, that does **not** break the
`protocol` cycle; it adds a third package inside it and leaves the file count
at 49. The leaf-extraction pattern generalises to `AsorRef` and stops there.
Recorded because the negative result is what stops someone spending a day on it.

The honest ceiling: `compiler` (209), `protocol` (49) and `parser` (44) stay
single targets — 302 files, 44% of `core`, in three coherent subsystems. That
is a reasonable place to stop. Foundational types still cascade widely
(`model` → 593 files, `protocol` → 642) and no build system changes that.

---

## 2. Completeness: 76 items the plan never mentions

`completeness.tsv` reconciles 135 inventory items against the plan. Every item
is TARGET, DATA, DELETED, SCOPED-OUT, or UNADDRESSED. The count:
**UNADDRESSED 76**, DELETED 27, TARGET 20, SCOPED-OUT 8, DATA 1.

Three contradict the plan's own acceptance criteria, which makes them blocking
rather than cosmetic:

1. **`GEMINI_API_KEY`-gated tests.** §1.1 promises "no environment variables";
   §3.3 bans unregistered skips. Three `nlq` classes skip on a missing secret,
   and `SkipCensusTest` never scans `nlq`
   (`core/src/test/java/com/legend/SkipCensusTest.java:164-167`). *Resolved by
   §4 below.*
2. **`.gitignore` is never updated for `bazel-bin`/`bazel-out`/`bazel-testlogs`.**
   §1.5 has CI assert `git status --porcelain` is empty after every run. That
   check fails on the first green build.
3. **An 8th `pom.xml`.** `experiments/backend-probes/harness/pom.xml` sits
   inside `experiments/`, which phase 1's `.bazelignore` hides permanently.
   §1.8 says "no `pom.xml` remains". It would never be seen to fail.

And one that is self-referential: §4.5 warns that "a sweep for `*.generate`
would miss `ladder.record`", then counts **five** `*.generate` flags. There are
**seven**, plus `corpus.manifest.regen`
(`parser-equivalence/.../CorpusManifestTest.java:28-64`), which rewrites the
working tree and does not match the pattern. The plan reproduces the failure
mode it names.

Corrected counts, where the plan's or this audit's earlier numbers were wrong:
**41** `System.getProperty` names, not 40 — `legend.judge.ledger` is read
through a string constant and is invisible to a literal grep, the same class of
blindness as §1's fully-qualified edges. Python is **56,094** lines across 121
files. `projects/` has **58** entries. `docs/` holds **350** files, but every
count the plan cites covers only the 247 at top level; the other 103 include
live shell scripts under `docs/type-audit-2026-08/harness/`.

§7's arithmetic is **correct** — the rows sum to 2,959, as claimed. The finding
is what §7 omits, not its sum.

---

## 3. A defect class the project's own guard cannot see

`allgates.sh`'s `skipped()` detector reads surefire's `Skipped:` count, so it
sees `Assumptions`-skips and nothing else. Six sites bail out of a missing
input with a bare `return` — no assumption, no assertion. They report
**PASSED**.

```
core/src/test/java/com/legend/server/DiagramServiceTest.java:118
spec/src/test/java/com/legend/generators/CensusWorlds.java:172
parser-equivalence/src/test/java/com/legend/equivalence/InlineSnippets.java:60
parser-equivalence/src/test/java/com/legend/equivalence/OwnCorpusParityTest.java:107
parser-equivalence/src/test/java/com/legend/equivalence/PctParseCensusTest.java:69
parser-equivalence/src/test/java/com/legend/equivalence/Corpus.java:61
```

The last is gate 8's corpus reader. `Corpus.filesWith` returns `List.of()` when
the root is absent, so the sweep runs over an empty corpus and passes. That is
the exact mechanism behind the incident `docs/GATES.md` records for 2026-08-11
— "runs on 774 rows instead of 6,033, reports 0 diff, and the build SUCCEEDS".
The project's response was to add `roots_present()` to `allgates.sh`: **an
external shell guard compensating for an internal defect that is still
present.** It is the plan's own thesis, found in the wild.

This matters to the migration specifically. Declaring the corpus as a Bazel
input makes an *absent* root impossible, but a `filegroup` that matches nothing
still hands these six helpers an empty list. The sites must be fixed, not
merely re-plumbed — they should throw. Phase 2 is where that belongs.

---

## 4. Deleting `nlq` — decision taken 2026-09-22, and its runbook

The owner's decision during this audit: **`nlq` is removed from the repository
entirely.** It resolves §2's blocking item 1 and answers the plan's own §9
question ("whether an LLM-backed package belongs in the same repository as a
clean-room compiler is a product question, not a build one") in the negative.

**What goes:** 50 files, 9.3 MB — 15 main Java (2,941 lines), 13 test Java
(1,908), 8 Python (2,997) — plus the `GEMINI_API_KEY` dependency and its cost,
a shade plugin, an exec plugin, a second HTTP server, and the duplicate
`provision` enum value at `nlq/src/test/resources/nlq/cdm-model.pure:2574-2575`
that fails a fresh clone today.

**This is not a directory removal.** Eleven references live outside `nlq/`, and
they must land in the same commit or the gates go red.

**1. Production code in `core` hardcodes an `nlq` name — decide this first.**
`core/src/main/java/com/legend/server/DiagramService.java:234-236`:

```java
&& ("NlqProfile".equals(tv.profileName())
        || "nlq::NlqProfile".equals(tv.profileName()))
```

`getTag` reads diagram metadata tags **only** from an nlq-namespaced profile,
so a `core` product feature is keyed to the module being deleted. Either
generalise the lookup to any profile, or delete the tag feature with the
module. This is a product decision, not a mechanical edit, and it is the one
item that cannot be done by find-and-replace.

**2. Module rosters that name `nlq` shrink, so their pins move.**

| file:line | what it is |
| --- | --- |
| `pom.xml:21` | `<module>nlq</module>` |
| `core/src/test/java/com/legend/DanglingStateGuardTest.java:55` | `ROOTS` list |
| `core/src/test/java/com/legend/LegacyReachbackCensusTest.java:52` | scanned roots |
| `core/src/test/java/com/legend/JdbcSurfaceCensusTest.java:71` | scanned roots |
| `parser-equivalence/.../OwnCorpusConformanceTest.java:29` | module array |
| `parser-equivalence/.../ProtocolRosterCensusTest.java:94` | module array |

Each of the last five changes a denominator. Re-pin in the same commit and say
so in the message; do not let a shrunk denominator pass as an improvement.

**3. A silent-pass test disappears with it.**
`core/src/test/java/com/legend/server/DiagramServiceTest.java:116-120` reads
`../nlq/src/test/resources/nlq/sales-trading-model.pure` and bare-`return`s if
absent — §3's pattern. Deleting `nlq` makes it permanently vacuous, so delete
the test method rather than leaving it green and empty. Its two sibling
fixtures at `:252` and `:84` embed `nlq::NlqProfile` in Pure text and must be
re-namespaced or removed depending on decision 1.

**4. Comments only, safe to sweep:** `core/src/main/java/com/legend/Compiler.java:46`,
`core/src/main/java/com/legend/server/LegendHttpServer.java:397`,
`parser-equivalence/.../OwnCorpusParityTest.java:23`,
`parser-equivalence/.../OwnCorpusConformanceTest.java:17`.

**5. Documentation:** 33 files under `docs/` mention `nlq`, as do `README.md`
(the Quick Start's second server invocation) and `FAQ.md`.

**Order of work.** Decide (1); delete `nlq/` and the `<module>` line; update the
five rosters and re-pin; delete the vacuous test method and re-namespace the
two fixtures; sweep comments and docs. One commit, because the rosters and the
deletion cannot be separated without a red gate in between.

**Effect on the plan.** `nlq` leaves §4.1's package list, reducing it from ten
packages to nine. §2.2's "known red" row is resolved. The `GEMINI_API_KEY` row
in §4.0's "checks that need credentials" disappears, and with it the only
acceptance-criteria contradiction that a clean clone would have hit first.

---

## 5. Feasibility: six prototypes, built and run

The plan carries six technical unknowns that decide whether phase 1 is weeks or
months, and leaves the largest of them to be settled *during* the phase that
depends on it. Each was settled by building it, on Bazel 9.2.0 / rules_java
9.9.0 / rules_jvm_external 7.1. The workspaces are under `prototypes/`.

| | question | verdict | effort |
| --- | --- | --- | --- |
| Q1 | Error Prone + NullAway on Bazel's toolchain (**the plan's Risk 4**) | **WORKS** | half a day |
| Q2 | PCT's runtime-generated JUnit suites | WORKS, with a real trap | 1–2 weeks (PAR) |
| Q3 | `rules_jvm_external` over 398 + 139 artifacts | WORKS | 1 day |
| Q4 | `.pure` sources inside published jars | WORKS for `spec`, **BLOCKED** for `parser-equivalence` | 1 day / ~1 week |
| Q5 | native DuckDB / SQLite / H2 under the sandbox | **WORKS** | hours |
| Q6 | test parallelism and memory | WORKS, but **CI fails twice over** without three lines | 2–4 hours |

### Q1 — Risk 4 is closed, and cheaper than feared

`bazel build //:violation` fails with `[NullAway] returning @Nullable
expression from method with @NonNull return type`, and a JSpecify-only
violation is caught too, so JSpecify mode is genuinely engaged. No version
conflict exists: JavaBuilder's bundled Error Prone is a strict superset of
`error_prone_core-2.50.0` (0 of 1,666 classes missing). **No custom
`java_toolchain` is needed** — three edits to what `core/pom.xml` does today:

- **delete `-Xplugin:ErrorProne`** — Bazel rejects it (`plug-in not found: ErrorProne`);
- **do not port the `-J--add-exports` block** — aquery shows rules_java's
  `BASE_JDK9_JVM_OPTS` already passes all of it;
- `-XDaddTypeAnnotationsToSymbol=true` is **inert**, because Bazel pins javac to
  `remotejdk_25` regardless of `--tool_java_runtime_version`. Tested on both
  JDK 21 and 25: identical results.

The plan should stop listing this as an open risk.

### Q2 — the silent-zero is real, worse than described, and now has a proven fix

The plan worries a statically-enumerating runner might report zero tests and
pass. It is worse: with zero generated cases the runner reports `OK (0 tests)`
exit 0; ConsoleLauncher's **`--fail-if-no-tests` does not fire** (vintage counts
the runner itself as one successful test); and contrib_rules_jvm writes
`tests="1"` into `test.xml`, defeating even an external XML guard. Three
independent safety nets all fail open.

The remedy is built and proven — a case-count floor asserted *inside* `suite()`
(`prototypes/q2/GuardedSuite.java`), red at zero and green at 7 on both
runners. Port `wrap()` into `PctCensusGate`. This is the same shape as §3's
finding and the plan's own §4.3 row about rosters: **a count floor is the only
thing that distinguishes "ran and passed" from "did not run".**

**The genuine schedule risk is elsewhere and the plan under-weights it.** The
`legend-pure-maven-generation-par` PAR build has no Bazel rule and none exists
upstream: 1–2 weeks. One thing de-risks it, established separately in this
audit: the Maven mojo is a thin wrapper — `PureJarMojo` delegates to a plain
static call, `org.finos.legend.pure.m3.generator.par.PureJarGenerator
.doGeneratePAR(...)`, and that class ships **inside `legend-pure-m3-core`**,
an artifact `pct` already depends on. So the rule is a small `java_binary` over
an existing dependency, not a reimplementation — but writing and validating it
is still the long pole of the whole migration, and phase 3 should say so.

### Q3 — resolution is a non-event; the version floor is the finding

`@pe` resolved **398 artifacts (316 `org.finos.legend`) in 27 s** → a 794 KiB
lock file; `@pct` 139 in 3.4 s → 249 KiB; full download of both, 104 s. **Zero
malformed or unresolvable poms.** The INV-5 pins converge to one version each
of HikariCP, commons-lang3, httpcore and junit, so `classpath-convergence.sh`
is genuinely replaced rather than merely deleted. The `test-jar` dependency
maps to `classifier = "tests"`. **Two H2 versions as two named repositories is
confirmed working** (distinct hashes, `@pct` unaffected) — §4.4's design holds.

One hard constraint the plan must record: **`rules_jvm_external` 6.10 is broken
on Bazel 9** (`rules_android`: "The CcInfo symbol has been removed"). Pin 7.x.
Seed lock files as zero-byte files, not `{}`.

### Q4 — the design holds for `spec` and does not reach `parser-equivalence`

Re-measured at the current pin by comparing jar bytes against the pinned git
tags (the local checkouts are *not* on the pin — engine `4.137.0+36`, pure
`5.92.0+3` — so they were not used). Seeded with the twelve exact paths
`spec/src/test/java/com/legend/rcorpus/Corpus.java` reads: **85 of 85 sampled
files byte-identical, 0 differ**, and all three of its roots are **exact set
equality** — RELATIONAL 552/552, CORE_PURE 574/574, M2M_TESTS 49/49. The
plan's §4.2 claim survives re-measurement at the current pin. `spec` can drop
its checkout: 1 day.

`parser-equivalence` cannot. Of 3,253 engine `.pure` files its whole-tree walk
reads, 2,392 are in jars and **0 of 437 `src/test/resources` files are in any
jar**; a further 421 `src/main` files sit in modules the poms do not pull and
are recoverable by adding coordinates. This **confirms** the plan's instinct
that upstream's test sources are genuinely unpublished, and extends it: the gap
is wider than "test sources", and `parser-equivalence` needs the
committed-snapshot pattern it already uses for tier C6. ~1 week, and it should
be its own phase-2 workstream rather than a paragraph.

### Q5 — native drivers are a non-issue on the two platforms that can be tested

DuckDB 1.4.4.0, SQLite 3.47.1.0 and H2 2.1.214 all opened in-memory and
executed a query under `darwin-sandbox`. Bazel sets `java.io.tmpdir ==
TEST_TMPDIR`, so native extraction works with no overrides. Two things to
budget: the DuckDB jar extracts a **106 MB** `libduckdb_java.so_osx_universal`
**per test JVM** (~1.0 s on first connect) — which interacts directly with Q6 —
and **the jar ships no Windows-arm64 DuckDB native at all**, only
`windows_amd64`. The plan's §4.6 already notes the driver-platform limit; this
names the specific missing pair.

### Q6 — CI fails twice over, and the plan's §4.7 does not yet prevent it

This is the finding that most affects the plan's Risk 2 and its claim that
"every cell is exactly `bazel test //...`".

Measured on a 10-core / 32 GiB machine: the default is **10 concurrent test
JVMs** (`--jobs=auto` → HOST_CPUS; CPU is the binding constraint). Test `size`
*does* reserve RAM, but `enormous` reserves only **~800 MB** (calibrated:
`memory=1700` → 2 concurrent, `memory=900` → 1) against a PCT suite's **2.0–2.8
GB** live set — a **3.5× over-schedule**. The default pool is `HOST_RAM * 0.67`,
derived exactly: 8 targets each declaring 2,800 MB ran 7 at once.

On a 3-vCPU / 7 GB macOS runner it fails **twice over**:

1. CPU binds at 3 → 6–8.4 GB of live set on a 7 GB box; and
2. the test JVM's default max heap is 25% of host RAM (measured `maxHeapMB=8192`
   on 32 GiB) → **~1.75 GB on a 7 GB runner, below a single suite's floor**, so
   one PCT test OOMs *at concurrency 1*.

`size` alone cannot express this. The knob is **`tags =
["resources:memory:N"]`** (`bazel help test --long`), exact in measurement:
2,800 MB against a 7,000 MB pool yields exactly 2. The fix is three lines per
heavy target — the memory tag, `-Xmx3g`, and `-Duser.timezone=GMT` moved out of
surefire's `argLine` into `jvm_flags` — plus `build:ci --local_test_jobs=1`.
**`exec_properties` is remote-execution only and does nothing locally**, so
§4.3's row proposing it for "the heavy suites' needs declared on their targets"
is wrong as written and should say `tags`.

`prototypes/q6/` contains the calibration harness. Run it **on the real CI
runners** to derive their budgets rather than inferring them — that is the
measurement phase 1 owes Risk 2.
