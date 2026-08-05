#!/bin/bash
# The standing 8-gate chain (docs/GATES.md). Sequential BY DESIGN — concurrent
# heavy JVMs get killed on small machines, and core must be INSTALLED before any
# engine-module gate (they build against core's installed jar).
#
# Usage:
#   LEGEND_ENGINE_ROOT=~/legend/legend-engine \
#   LEGEND_PURE_ROOT=~/legend/legend-pure \
#   caffeinate -dims tools/allgates.sh
#
# Optional: MVN_SETTINGS=<settings.xml> adds -s to the OFFLINE-friendly gates
# (1-3, 8). Gates 4-5 always run plain mvn — the corpus h2-exec backend resolves
# artifacts at runtime. Run under caffeinate: a ~900s gate with near-zero CPU
# means the machine slept mid-run, not a real regression.
set -u
cd "$(dirname "$0")/.."
ROOT_ENGINE=${LEGEND_ENGINE_ROOT:-$HOME/legend/legend-engine}
ROOT_PURE=${LEGEND_PURE_ROOT:-$HOME/legend/legend-pure}
R1="-Dlegend.engine.root=$ROOT_ENGINE"
R2="-Dlegend.pure.root=$ROOT_PURE"
SFLAG=()
[ -n "${MVN_SETTINGS:-}" ] && SFLAG=(-s "$MVN_SETTINGS")
L=${GATES_LOG:-/tmp/gates.log}
: > "$L"
g() { echo "=== $1" >> "$L"; }

g "GATE1 core suite"
mvn -o -pl core test > /tmp/g1.out 2>&1
echo "G1_EXIT=$?" >> "$L"; grep -E "Tests run: [0-9]+, Fail" /tmp/g1.out | tail -1 >> "$L"

g "GATE2 core install"
mvn -o -pl core install -DskipTests > /tmp/g2.out 2>&1
echo "G2_EXIT=$?" >> "$L"

g "GATE3 engine suite minus corpus"
mvn "${SFLAG[@]}" -o -pl engine test "-Dtest=!RelationalCorpusRunner" "$R1" "$R2" > /tmp/g3.out 2>&1
echo "G3_EXIT=$?" >> "$L"; grep -E "Tests run: [0-9]+, Fail" /tmp/g3.out | tail -1 >> "$L"

g "GATE4 DuckDB corpus"
mvn -pl engine test -Dtest=RelationalCorpusRunner "$R1" "$R2" > /tmp/g4.out 2>&1
echo "G4_EXIT=$?" >> "$L"; grep -E "h2-exec|Tests run: [0-9]+, Fail" /tmp/g4.out | tail -3 >> "$L"

g "GATE5 h2 corpus"
mvn -pl engine test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2 "$R1" "$R2" > /tmp/g5.out 2>&1
echo "G5_EXIT=$?" >> "$L"; grep -E "EXACT|h2|Tests run: [0-9]+, Fail" /tmp/g5.out | tail -3 >> "$L"

g "GATE6 PCT full DuckDB"
( cd pct && mvn -o test ) > /tmp/g6.out 2>&1
echo "G6_EXIT=$?" >> "$L"; grep -E "Tests run: [0-9]+, Fail" /tmp/g6.out | tail -1 >> "$L"

g "GATE7 PCT h2modern Relation (ledgered 325/348 => exit judged by counts)"
( cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn -o test -Dtest=Test_LegendLite_RelationFunctions_PCT -Dh2.version=2.4.240 ) > /tmp/g7.out 2>&1
grep -qE "Tests run: 348, Failures: 1, Errors: 22" /tmp/g7.out
echo "G7_EXIT=$?" >> "$L"; grep -E "Tests run: [0-9]+, Fail" /tmp/g7.out | tail -1 >> "$L"

g "GATE8 parser-equivalence: byte parity + rejection parity + SPI seam"
mvn "${SFLAG[@]}" -pl parser-equivalence test \
    -Dtest='CorpusEquivalenceTest,RejectionParityTest,SpiSeamProofTest' \
    -Dsurefire.failIfNoSpecifiedTests=false "$R1" "$R2" > /tmp/g8.out 2>&1
echo "G8_EXIT=$?" >> "$L"
sed -n '4,10p' parser-equivalence/target/equivalence-report.txt >> "$L" 2>/dev/null
sed -n '3,6p' parser-equivalence/target/rejection-report.txt >> "$L" 2>/dev/null
sed -n '3,9p' parser-equivalence/target/spi-seam-report.txt >> "$L" 2>/dev/null

echo "ALLGATES_DONE" >> "$L"
echo "ALLGATES_DONE"
