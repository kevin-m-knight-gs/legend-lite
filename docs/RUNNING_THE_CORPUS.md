# Running the corpus

Two bodies of work live here and they run differently.

| | what it is | how it is checked | where |
| --- | --- | --- | --- |
| the **corpus** | ~2260 executable services with seeded data | run against legend-engine, every answer compared to an independently computed expectation | `core/src/test/resources/stress/` |
| the **project graph** | 56 Legend projects that depend on each other | compiled only — no data, no runtimes, no services | `projects/` |

Five of the 56 projects are also pulled into the corpus and executed; see
[the boundary](#the-boundary) below.

## Prerequisites

**A JDK 21.** The scripts default to `~/jdk/jdk-21.0.11+10/Contents/Home`; set `JAVA_HOME`
if yours is elsewhere. Everything shells out to `$JAVA_HOME/bin/java`, so a `java` on `PATH`
alone is not enough.

**Maven, and the runner built once.** `tools/engine-runner` is outside the reactor, so `-pl`
cannot reach it — use `-f`. Drop `-o` on a cold cache.

```
mvn -B -pl core install -DskipTests
mvn -B -f tools/engine-runner/pom.xml compile
mvn -B -f tools/engine-runner/pom.xml dependency:build-classpath -Dmdep.outputFile=cp.txt
```

That last line writes `tools/engine-runner/cp.txt`, an 80 KB list of absolute jar paths. It
is **gitignored and machine-specific** — regenerate it on any new machine rather than copying
it, and regenerate it whenever a dependency changes. Every script reads it, and a stale one
fails as a missing class rather than as a stale classpath.

**Python 3.12+.** No third-party packages: the scripts use only the standard library.

## The two commands

Everything is driven from the repository root.

```
python3 scripts/corpus/build.py     # regenerate the corpus from the generators
python3 scripts/corpus/run.py       # execute it and report
```

`build.py` is the one that matters. Four files under `stress/` are **generated wholesale**
and must never be hand-edited, because the next build overwrites them:

- `94-fanout-services.pure` — every generated service and its expectation (134k lines)
- `93-testdata.pure` — the `###Data` element, i.e. all seeded rows
- `98-combination-execution.pure` — the combination matrix's services
- `92-services.pure` — the hand-written battery, rendered

Everything else under `stress/` is hand-written and safe to edit. If you want to change what
is generated, change the generator in `scripts/corpus/`, not its output.

`build.py` also runs every consistency check before it writes anything — a class whose
mapping the runtime cannot resolve, a duplicate set id, a seeded value too wide for its
column, a service that sorts on an unprojected alias. It prints them all and writes nothing
if any fires. A clean build ends with `wrote` or `no changes`.

`run.py` takes about **50 minutes**. It batches the services across several JVMs, because a
single process stops working somewhere past ~170 testables. Its last line is the summary:

```
2227 passed, 30 known-fail (quarantined), 1 not run (hangs), 0 unexpected, 2258 total
```

**`0 unexpected` is the only acceptable result.** The other numbers are allowed to move.

## Reading a failure

`run.py` classifies every service, and the classes mean different things:

| verdict | meaning |
| --- | --- |
| `PASS` | the engine's answer matched the oracle's |
| `KNOWN-FAIL <Fnn>` | quarantined against a recorded finding in `docs/UPSTREAM_FINDINGS.md` |
| `REGRESSION` | it failed and is not quarantined — **this is the one to care about** |
| `ERROR` | it threw rather than returning a wrong answer |
| `FIXED <Fnn>` | a quarantined service now passes; remove it from `quarantine.py` |
| `MISSING` | it reported nothing at all |

A wall of `MISSING` almost always means **one** service killed the JVM its batch ran in.
`run.py` prints `FATAL AT INIT <name>` above them when it can detect this. A service that
throws during test-suite *initialisation* cannot be quarantined — quarantine excuses a
failure, and this one never reports one — so it has to be removed from the corpus and its
defect pinned with a probe under `repro/` instead.

## Running one service

The full suite is too slow for a debugging loop. To run a handful:

```python
# scripts/corpus/one.py — or inline; see the pattern in scripts/corpus/run.py
import os, subprocess, sys
sys.path.insert(0, "scripts/corpus")
import model, run as R
cp = (R.RUNNER / "cp.txt").read_text().strip()
files = ([str(f) for f in model.linked_files()]
         + sorted(str(p) for p in R.STRESS.glob("*.pure")))
subprocess.run([f"{R.JAVA_HOME}/bin/java", "-cp", f"{R.RUNNER}/target/classes:{cp}",
                "perf.TestableMain", *files, "--testable=stress::PL2_PillarTenorBand"],
               env=dict(os.environ, JAVA_HOME=R.JAVA_HOME), cwd=R.RUNNER)
```

Two things that are easy to get wrong and fail anonymously:

- **the linked project files must come first.** A `.pure` file with no `###Section` header
  inherits whatever section the previous file left open, so order is load-bearing. Omit them
  entirely and the corpus fails with a bare `Unexpected token` naming no file.
- **`perf.TestableMain` runs only what is NAMED.** Passing the files with no `--testable=`
  compiles everything and runs nothing, reporting `0 total` rather than an error.

`--dump=<dir>` writes the expected and actual JSON per suite, which is the only way to see a
diff the console truncates.

## The project graph

```
python3 scripts/projects/check.py              # each project alone, then all 56 together
python3 scripts/projects/check.py core-fx      # just one
python3 scripts/projects/check.py --graph      # just the whole-graph compile
python3 scripts/projects/loadtime.py           # parse and compile time by layer and closure
```

`check.py` asks two different questions. **Alone**: does a project compile with exactly its
declared dependency closure — a project needing an undeclared one fails here, which is the
defect the graph exists to find. **Together**: does the whole graph compile at once, which is
where set-id and filter-name collisions appear, both being global namespaces in Legend.

It also checks that a project is *substantively* what it was meant to be — the size band from
`scripts/projects/spec.py` and the presence of a `MANIFEST.md`. A three-file placeholder
compiles perfectly, and without that check the graph looks complete when it is not.

`projects/CONTRACT.md` is binding on anything added to `projects/`. Its "learned the hard
way" list is eleven items long and every one of them cost a project a failed compile.

## The boundary

`projects/` is compile-only by design, which proves a cross-project reference *compiles* and
nothing more — and several recorded findings compile perfectly and fail at execution. So a
slice of the graph is pulled into the executable corpus:

```python
# scripts/corpus/model.py
LINKED_PROJECTS = ["core-tenor", "core-fx", "core-ratings"]
```

Adding a project to that list is not enough on its own. It also needs:

1. **rows** in `scripts/corpus/seed.py` for each of its tables. Leave one unseeded and the
   corpus's expansion ring will invent placeholder rows for it, which surface as
   width-violation errors rather than as "nobody seeded this".
2. `include <project>::Store` in `store::DB` — **first in the body**, which is a grammar
   requirement, not tidiness.
3. `include <project>::Mapping` in `projectlink::ProjectLinkMapping` — likewise first.
4. an association in `8111-projectlink.pure` if the corpus is to navigate into it, with the
   set ids named on **both** ends. The project side names its ids explicitly; the corpus side
   mostly does not, so the two halves of one association follow opposite conventions and
   neither is guessable from the class name.

## The rule the whole thing rests on

**Every expectation is computed from the seed by `scripts/corpus/oracle.py`, never read back
from the engine.** An expectation copied from the engine's output asserts that the engine
agrees with itself, which is always true and worth nothing.

This is why the oracle refuses an unknown function rather than guessing at it, why it raises
on an aggregate it does not implement rather than returning null, and why a cross-project
function had to be reimplemented here from the project's own source. If you add a construct
the oracle cannot evaluate, teach the oracle — do not relax the assertion.
