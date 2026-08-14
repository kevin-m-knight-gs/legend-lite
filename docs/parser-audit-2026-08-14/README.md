# Parser-audit probes — 2026-08-14

Reproduces `docs/PARSER_AUDIT_2026_08_14.md`. Standalone; nothing is wired into
the build. Promoting `Mut3` is finding #7 of that audit.

## Build

```bash
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$HOME/jdk/apache-maven-3.9.9/bin:$PATH"
cd ~/legend/legend-lite
mvn -o -q -pl core install -DskipTests
mvn -o -q -pl parser-equivalence test-compile
mvn -o -q -pl parser-equivalence dependency:build-classpath \
    -Dmdep.outputFile=/tmp/pecp.txt -Dmdep.includeScope=test
CP="core/target/classes:parser-equivalence/target/test-classes:$(cat /tmp/pecp.txt)"
javac -cp "$CP" -d /tmp/pa docs/parser-audit-2026-08-14/probes/*.java
caffeinate -dims java -Xss16m -cp "/tmp/pa:$CP" <Class>
```

Each walks the legend-engine checkout at `~/legend/legend-engine`. Runs take
1-5 minutes; `Mut3` longer.

| class | dimension | flags |
|---|---|---|
| `Dim` | D1 accept-drift, D2 reject-drift, D4 messages, over every `.pure` | `-Dlimit=N` |
| `Msg` | D4 **corrected** — strips legend-lite's `[line:col]` prefix before comparing | |
| `Npe` | the 301 files that crash the real engine, and what legend-lite says instead | |
| `Tier` | D5 — the PLATFORM / LITE / ENGINE / real-engine ladder | |
| `Mut3` | corpus-seeded mutation fuzz, **both directions** | `-Dseeds=N -Dmaxlen=N` |

## The invariant worth landing as a gate

```
LEGEND_ENGINE accepts  ⟺  real engine accepts
```
over seeds × mutations. Every existing parity gate sweeps text that already
exists in a corpus; none generates input, which is why the mutation rows in the
audit had never been seen.

## A method note

`Dim` reports "878 of 878 message divergences" — wrong. It compares
legend-lite's `[line:col]`-prefixed message against the engine's unprefixed one,
so nothing ever matches. `Msg` strips the prefix first; the real split is 368
identical / 510 different. Both are kept so the error stays legible rather than
being quietly corrected.

**And the oracle is not clean.** 301 of 3,180 corpus files crash
`PureGrammarParser` with an uncaught NPE. Treat "the engine rejects X" as
"the engine did not accept X" — sometimes it crashed. `Npe` quantifies it.
