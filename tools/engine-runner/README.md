# engine-runner

Runs .pure sources through **legend-engine** — parse, compile, then the Testable framework
— and reports each testSuite as PASS/FAIL.

It exists because the asserted corpus in `core/src/test/resources/stress/` is portable
Legend grammar, and "portable" is only a claim until a second engine executes it. legend-lite
parses and plans those files; this runs them for real and checks the answers.

## Build

This harness is OUTSIDE the reactor, so `-pl` cannot reach it -- use `-f`. It depends on
legend-lite's own `core` (LiteParseMain drives that parser beside legend-engine), so core
must be installed first. Drop `-o` on a cold Maven cache.

```
mvn -B -pl core install -DskipTests
mvn -B -f tools/engine-runner/pom.xml compile
mvn -B -f tools/engine-runner/pom.xml dependency:build-classpath -Dmdep.outputFile=cp.txt
```

`target/` and `cp.txt` are generated and gitignored; regenerate them after any pom change.
`JAVA_HOME` is taken from the environment.

## Run

```
java -cp target/classes:$(cat cp.txt) perf.TestableMain <file.pure>... \
     [--testable=<fqn>]... [--dump=<dir>]
```

- `--testable=` selects which testable elements to run; omit to parse and compile only.
- `--dump=` writes the full expected/actual JSON per failing assertion. Without it the
  report truncates at 300 characters, which for a 60-column TDS is identical on both sides
  and tells you nothing.

The whole stress corpus:

```
S=../../core/src/test/resources/stress
java -cp target/classes:$(cat cp.txt) perf.TestableMain $S/*.pure \
  $(grep -h '^Service ' $S/92-services.pure | awk '{print "--testable="$2}')
```

Note zsh does not word-split unquoted parameter expansions; use an array or `${=VAR}`.

## Dependencies worth knowing about

`duckdb-execution` + `duckdb_jdbc` are present because the stress runtime declares
`type: DuckDB`. They are needed for the model to *compile*; the testSuites themselves always
execute against H2 regardless, because `TestRuntimeBuilder` swaps every connection for a
seeded local H2 (see docs/UPSTREAM_FINDINGS.md).
