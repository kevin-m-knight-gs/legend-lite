# Running the corpus

Two bodies of work live here and they run differently.

| | what it is | how it is checked | where |
| --- | --- | --- | --- |
| the **corpus** | ~4690 executable services with seeded data | run against legend-engine, every answer compared to an independently computed expectation | `core/src/test/resources/stress/` |
| the **project graph** | 56 Legend projects that depend on each other | compiled only — no data, no runtimes, no services | `projects/` |

Some of the 56 are also pulled into the corpus and executed; see
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

`run.py` takes about **an hour** at 4290 testables and `build.py` about **10 minutes**. Those
follow from the measured costs below -- roughly 0.6s per service plus 9s per JVM -- rather
than from a fresh stopwatch, so treat them as the right order rather than to the minute. It batches the services across several JVMs, because a single
process stops working somewhere past ~170 testables. Its last line is the summary:

```
4661 passed, 28 known-fail (quarantined), 1 not run (hangs), 0 unexpected, 4690 total
```

**`0 unexpected` is the only acceptable result.** The other numbers are allowed to move.

### Where the time actually goes

Measured on an idle machine, two runs of each, at ~2,290 testables and `BATCH = 40`
(58 JVMs):

| | | share |
| --- | --- | --- |
| per-service execution — 0.61s each | ~23 min at 2294 | 56% |
| per-JVM fixed — 5.9s parse + 2.8s compile + 3.2s warm-up + start | ~12 min at 58 JVMs | 30% |
| `build.py`, pure Python, even when it writes nothing | 6.4 min | 15% |

Measured at 2294 testables and `BATCH = 40`. Both terms are linear, so at 4290 testables and
108 JVMs the run roughly doubles.

Compile is the SMALLEST piece, at about 7% -- which is worth stating because it is the
obvious guess and it is wrong. Parse is twice compile here, the reverse of the project graph
(998ms parse, 2334ms compile in a third of the bytes): the corpus is mostly generated service
text, wide and shallow, and the graph is dense cross-referenced elements.

The 3.2s warm-up is per JVM, not per seed -- a throwaway one-table model takes 3.3s to run a
single service, which is the whole intercept and no marginal.

So raising `BATCH` only attacks the 12-minute slice, and walks back toward the ~170 point
where one JVM stopped working. The 0.61s per service is the block worth attacking, and the
open question is how much of it is re-creating and re-loading the seed for every suite.

## The two generated layers

The services fall into two layers, and the split is deliberate.

**Bare projections**, one per subtype set, ~1830 of them. A wrong `~filter` fails by returning
the WHOLE table rather than by erroring, and a bare projection over the subtype is the only
thing that makes that visible. These are the control.

**Stacked queries** from `scripts/corpus/deepstack.py`, ~2440 of them: four-hop chains
filtered on a NAVIGATED column, `groupBy` on a joined key, `graphFetch` three and four levels
deep, subtype roots navigating inherited edges, qualified properties taking arguments.

A stacked query is only interpretable if a simple one over the same rows passes, which is why
the bare ones were kept rather than upgraded. The same split already pays around F6
(`aggregates.py` against `tomany.py`) and F28 (`CB_NotEqualsNull`).

One pair is a true INVARIANCE. `DSDeep_X` filters on a model path before `project`, where the
predicate can be pushed into the WHERE beside the join; `DSPost_X` applies the identical
predicate to the projected ALIAS afterwards. All 400 pairs carry identical expectations,
each computed independently from the seed -- so if the engine ever places one of them wrongly,
exactly one side of the pair fails and names itself.

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
LINKED_PROJECTS = ["core-types", "core-tenor", "core-fx", "core-ratings",
                   "core-instrument", "core-calendar", "core-units", "fee-core"]
```

**Order is load-bearing: dependencies before dependents.** `fee-core` depends on the two
before it, and `core-types` is in the list only because fee-core needs it — it exports no
store and no mapping at all.

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

Two things that a project brings and the corpus does not have, which cost a build each:

- **a class declared over several lines**, with stereotypes and tagged values above its name,
  and **a qualified property whose body wraps**. The reader is line-based and folds both, but
  only because it RAISED rather than skipping them — an unparsed class has no properties, no
  mapping and no service, and nothing would have said so.
- **arithmetic follows the COLUMN type, not the property type.** A `DECIMAL` column adds and
  subtracts exactly -- `108.7500 - 107.9000` is `0.85` in the database and
  `0.8499999999999943` in binary -- while a `DOUBLE` column computes in binary and a 4-byte
  `FLOAT` computes narrowed. The oracle carries a tag per width (`flat.F32`, `flat.F64`);
  a value that loses its tag silently gets the wrong arithmetic.
- **a graphFetch ENFORCES multiplicity where a projection does not.** A `[1]` property whose
  column holds a NULL returns the null happily in a TDS projection and fails a tree with
  "Property of multiplicity [1] can not be null". The seed nulls every nullable column on
  purpose, so a generator building trees has to CHOOSE a null-free leaf rather than take the
  first one and reject the tree.
- **a decimal column.** Seed it with a FLOAT, not a quoted string. The emitter writes
  `repr()` and the oracle's exact-decimal path goes through `Decimal(str(v))`, so every digit
  survives either way -- but a string is COMPARED as a string, and
  `$this.factorToBase == 1.0` is false for `"1.00000000"`. The engine returns decimals padded
  to the column's scale; that is not a mismatch, because `EqualToJson` compares numbers
  numerically and only the value has to agree.
- **a value containing a comma.** Allowed: the data element is CSV and RFC4180 quoting works,
  including `""` for an embedded quote. A NEWLINE is still refused, because the element is a
  concatenation of one Pure string literal per row and quoting cannot save that.
- **an enum over a string column.** A generator that picks a filter column by the column's
  type will pick it and emit `> ' '`, which fails to compile and takes the whole file with
  it. Choose by the DECLARED property type.
- **a mapping set that `extends` another.** It inherits the parent's main table, its column
  mappings AND their transformers — copying the columns without the enum transformers read
  `'ZC'` off the row where the engine returns `ZERO`, and only a subtype whose enum property
  is declared on an ANCESTOR set shows it. It does NOT inherit the parent's `~filter`; that
  is F56, and it is the engine's behaviour rather than the reader's choice.

A project whose value is its SUBTYPES rather than its navigation will generate almost
nothing on its own: `stacks.py` wants two distinct navigation targets and `graphs.py` wants
to-one branches, and a wide flat master table has neither. Register it in
`taxonomy.py`'s `TAXONOMIES` and `IDENT` instead — one entry per LEVEL, since each level is
told apart by a different column — and one service per subtype falls out.

## The rule the whole thing rests on

**Every expectation is computed from the seed by `scripts/corpus/oracle.py`, never read back
from the engine.** An expectation copied from the engine's output asserts that the engine
agrees with itself, which is always true and worth nothing.

This is why the oracle refuses an unknown function rather than guessing at it, why it raises
on an aggregate it does not implement rather than returning null, and why a cross-project
function had to be reimplemented here from the project's own source. If you add a construct
the oracle cannot evaluate, teach the oracle — do not relax the assertion.
