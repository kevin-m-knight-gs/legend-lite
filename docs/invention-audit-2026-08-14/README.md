# Invention-audit probes — 2026-08-14

Reproduces every claim in `docs/INVENTION_AUDIT_2026_08_14.md`. Nothing here is
wired into the build; these are standalone probes. Promoting `Mutate*.java` into
`parser-equivalence/src/test` is action #3 of the audit.

## Setup

```bash
export JAVA_HOME=~/jdk/jdk-21.0.11+10/Contents/Home
export PATH="$JAVA_HOME/bin:$HOME/jdk/apache-maven-3.9.9/bin:$PATH"
cd ~/legend/legend-lite
mvn -o -q -pl core install -DskipTests
mvn -o -q -pl parser-equivalence test-compile
mvn -o -q -pl parser-equivalence dependency:build-classpath \
    -Dmdep.outputFile=/tmp/pecp.txt -Dmdep.includeScope=test
CP="core/target/classes:parser-equivalence/target/test-classes:$(cat /tmp/pecp.txt)"
javac -cp "$CP" -d /tmp/probes docs/invention-audit-2026-08-14/probes/*.java
```

Then `java -Xss8m -cp "/tmp/probes:$CP" <Class>`. Run under `caffeinate -dims`;
the fuzz rounds take a few minutes.

## The Java probes

| class | what it answers |
|---|---|
| `Probe` | Are the `meta::legend::lite::*` natives reachable from user query text? Calls `Compiler.compileQuery`. Includes a positive control (`average()`) and a negative one (`bogusFnThatCannotExist`). |
| `Gate` | Is `refusesPlatformDialect()` applied to each construct `Dialect`'s javadoc names as platform-only? Compares all three tiers against the real `PureGrammarParser`. |
| `Adv` | Hand-picked constructs across all tiers + real engine. |
| `Fuzz` | 40-construct sweep: reports both drift and we-are-stricter rows. |
| `Mutate` | **Differential mutation fuzz round 1** — delete/duplicate each special char over 32 engine-valid seeds. Reports mutants the engine rejects and `LEGEND_ENGINE` accepts. |
| `Mutate2` | **Round 2** — trailing junk (15 forms), token deletion, keyword lower-casing. |
| `Term` | Island-terminator mutations (`#{…}#`, `#/…/#`, `#>{…}#`, `#TDS…#`, `#SQL{…}#`, `#GQL{…}#`). |
| `Deep` | Prints what a drifting construct actually *parses to*, plus the engine's exact error. |
| `One <file>` | Parses one real `.pure` file at all three tiers and at the real engine. Used to falsify the stray-`)` comment's premise. |

**The fuzz invariant, and the gate worth landing:**
`LEGEND_ENGINE accepts ⟺ real engine accepts`, over seeds × mutations.

## The Python census scripts

Run from the repo root; they read both upstream checkouts directly and write to
`$CLAUDE_JOB_DIR/tmp/audit` as shipped — repoint that if you relocate them.

| script | what it produces |
|---|---|
| `nat.py` | every native FQN legend-lite declares |
| `idx.py` | every function FQN defined upstream (20,805) and the unmatched diff |
| `cls.py` | the same for native classes |
| `usage2.py` | which invented names any corpus `.pure` really writes — **strips string literals and comments first**, without which SQL goldens produce false hits (this is how `avg` initially looked legitimate) |
| `usage3.py` | **the corrected version** — `usage2.py`'s call regex counted `^$tds(…)` as a call to `tds`. Use this one; it is what makes the count 20, not 19. |
| `bare.py` | bare-name collision between `meta::legend::lite::*` and upstream |
| `final.py` | buckets invented natives by whether anything inside legend-lite consumes them |

## Provenance

Findings established at `e0a907a9`, **every probe re-run at `8a206fc8`** after
nine upstream commits touching 19 parser files. That re-run is why the audit
records `#{…}->` as fixed rather than open, and why one line citation moved.
Repeat it before acting on any row here.
