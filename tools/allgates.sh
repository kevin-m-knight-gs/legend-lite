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
FAILED=()
g() { echo "=== $1" >> "$L"; }

# rec <n> <exit> — record a gate's verdict so the script can fail at the end.
# A gate script that cannot fail is not a gate.
rec() {
  echo "G$1_EXIT=$2" >> "$L"
  [ "$2" -ne 0 ] && FAILED+=("G$1")
  return 0
}

# skipped <file> — surefire reports "Skipped: N" for Assumptions-skipped tests.
# Gates 4, 5 and 8 skip silently without the upstream checkouts; that is NOT a
# pass. Returns 0 (true) when the run was entirely skipped.
skipped() {
  # awk, not a grep backreference — ERE backrefs are not portable to BSD grep.
  awk '/Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+/ {
         run=0; skip=0
         for (i = 1; i <= NF; i++) {
           if ($i == "run:")     { run  = $(i+1) + 0 }
           if ($i == "Skipped:") { skip = $(i+1) + 0 }
         }
         if (run > 0 && run == skip) { found = 1 }
       }
       END { exit(found ? 0 : 1) }' "$1" 2>/dev/null
}

g "GATE1 core suite (CLEAN is load-bearing: NullAway binds to default-compile,"
g "       so a warm target/ silently no-ops the null gate)"
mvn -o -pl core clean test > /tmp/g1.out 2>&1
rec 1 $?; grep -E "Tests run: [0-9]+, Fail" /tmp/g1.out | tail -1 >> "$L"

g "GATE2 core install"
mvn -o -pl core install -DskipTests > /tmp/g2.out 2>&1
rec 2 $?

g "GATE3 engine suite minus corpus"
mvn ${SFLAG[@]+"${SFLAG[@]}"} -o -pl engine test "-Dtest=!RelationalCorpusRunner" "$R1" "$R2" > /tmp/g3.out 2>&1
rec 3 $?; grep -E "Tests run: [0-9]+, Fail" /tmp/g3.out | tail -1 >> "$L"

g "GATE4 DuckDB corpus"
mvn -pl engine test -Dtest=RelationalCorpusRunner "$R1" "$R2" > /tmp/g4.out 2>&1
G4=$?; if skipped /tmp/g4.out; then
  echo "G4 SKIPPED — no legend-engine checkout at $ROOT_ENGINE. NOT a pass." >> "$L"; G4=1
fi
rec 4 $G4; grep -E "h2-exec|Tests run: [0-9]+, Fail" /tmp/g4.out | tail -3 >> "$L"

g "GATE5 h2 corpus"
mvn -pl engine test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2 "$R1" "$R2" > /tmp/g5.out 2>&1
G5=$?; if skipped /tmp/g5.out; then
  echo "G5 SKIPPED — no legend-engine checkout at $ROOT_ENGINE. NOT a pass." >> "$L"; G5=1
fi
rec 5 $G5; grep -E "EXACT|h2|Tests run: [0-9]+, Fail" /tmp/g5.out | tail -3 >> "$L"

g "GATE6 PCT full DuckDB"
( cd pct && mvn -o test ) > /tmp/g6.out 2>&1
rec 6 $?; grep -E "Tests run: [0-9]+, Fail" /tmp/g6.out | tail -1 >> "$L"

# Ledger: 348 run, <=1 failure, <=22 errors. CEILINGS, not equality — the old
# `grep -q "Tests run: 348, Failures: 1, Errors: 22"` went RED the moment you
# fixed one of the 22. Lower these numbers when you earn it.
G7_MIN_RUN=348; G7_MAX_FAIL=1; G7_MAX_ERR=22
g "GATE7 PCT h2modern Relation (run>=$G7_MIN_RUN, fail<=$G7_MAX_FAIL, err<=$G7_MAX_ERR)"
( cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn -o test -Dtest=Test_LegendLite_RelationFunctions_PCT -Dh2.version=2.4.240 ) > /tmp/g7.out 2>&1
G7_LINE=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" /tmp/g7.out | tail -1)
G7=1
if [[ "$G7_LINE" =~ Tests\ run:\ ([0-9]+),\ Failures:\ ([0-9]+),\ Errors:\ ([0-9]+) ]]; then
  R=${BASH_REMATCH[1]}; F=${BASH_REMATCH[2]}; E=${BASH_REMATCH[3]}
  if [ "$R" -ge "$G7_MIN_RUN" ] && [ "$F" -le "$G7_MAX_FAIL" ] && [ "$E" -le "$G7_MAX_ERR" ]; then
    G7=0
    if [ "$F" -lt "$G7_MAX_FAIL" ] || [ "$E" -lt "$G7_MAX_ERR" ]; then
      echo "G7 IMPROVED — fail $F/$G7_MAX_FAIL, err $E/$G7_MAX_ERR. Ratchet tools/allgates.sh." >> "$L"
    fi
  fi
else
  echo "G7 no surefire summary found — treating as failure" >> "$L"
fi
rec 7 $G7; echo "${G7_LINE:-<no summary>}" >> "$L"

g "GATE8 parser-equivalence: byte parity + rejection parity + SPI seam + pull sentinel"
mvn ${SFLAG[@]+"${SFLAG[@]}"} -pl parser-equivalence test \
    -Dtest='CorpusEquivalenceTest,RejectionParityTest,SpiSeamProofTest,SectionParseSentinelTest' \
    -Dsurefire.failIfNoSpecifiedTests=false "$R1" "$R2" > /tmp/g8.out 2>&1
rec 8 $?
sed -n '4,10p' parser-equivalence/target/equivalence-report.txt >> "$L" 2>/dev/null
sed -n '3,6p' parser-equivalence/target/rejection-report.txt >> "$L" 2>/dev/null
sed -n '3,9p' parser-equivalence/target/spi-seam-report.txt >> "$L" 2>/dev/null
sed -n '3,5p' parser-equivalence/target/section-sentinel-report.txt >> "$L" 2>/dev/null

if [ ${#FAILED[@]} -eq 0 ]; then
  echo "ALLGATES_DONE — ALL 8 GREEN" >> "$L"
  echo "ALLGATES_DONE — ALL 8 GREEN"
  exit 0
fi
echo "ALLGATES_DONE — FAILED: ${FAILED[*]}" >> "$L"
echo "ALLGATES_DONE — FAILED: ${FAILED[*]}  (detail: $L)" >&2
exit 1
