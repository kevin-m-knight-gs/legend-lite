#!/bin/bash
# The standing gate chain (docs/GATES.md). Sequential BY DESIGN — concurrent
# heavy JVMs get killed on small machines. The engine module is DELETED:
# its suite migrated into core, so GATE 3 folded into GATE 1 (gate numbers
# kept stable so logs and habits stay comparable).
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
# Offline by default (local hygiene: skips remote metadata checks). CI has a
# cold ~/.m2 and MUST resolve, so it sets MVN_OFFLINE=0.
OFF=()
[ "${MVN_OFFLINE:-1}" = "1" ] && OFF=(-o)
# Gate subset: GATES=1,2,3 runs only those. Default is all eight.
WANT=${GATES:-1,2,4,5,6,7,8}
want() { case ",$WANT," in *",$1,"*) return 0;; *) return 1;; esac; }
# Default the log to a PER-USER path. A fixed /tmp/gates.log is shared across
# accounts on this box (it was found owned by another user), so writes fail
# silently and you end up reading someone else's run. TMPDIR is per-user on
# macOS; the id -un suffix covers Linux, where it is not.
L=${GATES_LOG:-${TMPDIR:-/tmp}/gates-$(id -un).log}
# Per-run scratch dir. The old fixed /tmp/g<n>.out paths are shared across
# users and concurrent runs on this box: "$OUT/g1.out" was found owned by a
# DIFFERENT account, so the redirect failed and the log's grep silently read
# that other user's stale output and reported it as this run's result.
OUT=$(mktemp -d "${TMPDIR:-/tmp}/allgates.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
: > "$L"
FAILED=()
G_T0=$(date +%s)
g() { echo "=== $1" >> "$L"; G_T0=$(date +%s); }

# rec <n> <exit> — record a gate's verdict so the script can fail at the end.
# A gate script that cannot fail is not a gate.
rec() {
  echo "G$1_EXIT=$2 (took $(( $(date +%s) - G_T0 ))s)" >> "$L"
  [ "$2" -ne 0 ] && FAILED+=("G$1")
  return 0
}

# roots_present — gates 4, 5 and 8 are meaningless without the upstream
# checkouts. The `skipped()` detector below CANNOT catch this for gate 8: the
# harness ships a committed in-repo fixture tier, so Corpus is never empty and
# the tests genuinely RUN on a starved corpus rather than Assumptions-skipping.
# Measured 2026-08-11: with both roots absent, PmcdEquivalenceTest runs on 774
# rows instead of 6,033, reports "0 diff", and the build SUCCEEDS. Three gated
# tests have no floor at all and pass green on a 99%-shrunk corpus. So check the
# roots up front rather than trying to detect the symptom afterwards.
roots_present() {
  local ok=0
  [ -d "$ROOT_ENGINE" ] || { echo "MISSING legend-engine checkout: $ROOT_ENGINE" >> "$L"; ok=1; }
  [ -d "$ROOT_PURE" ]   || { echo "MISSING legend-pure checkout: $ROOT_PURE"   >> "$L"; ok=1; }
  return $ok
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

if want 1; then
  g "GATE1 core suite (CLEAN is load-bearing: NullAway binds to default-compile,"
  g "       so a warm target/ silently no-ops the null gate)"
  mvn "${OFF[@]}" -pl core clean test > "$OUT/g1.out" 2>&1
  rec 1 $?; grep -E "Tests run: [0-9]+, Fail" "$OUT/g1.out" | tail -1 >> "$L"
fi

if want 2; then
  g "GATE2 core install"
  mvn "${OFF[@]}" -pl core install -DskipTests > "$OUT/g2.out" 2>&1
  rec 2 $?
fi

# GATE3 (engine suite) folded into GATE1 — the engine module is deleted and
# its behavioral tests live in core's suite (com.legend.integration).

if want 4; then
  if ! roots_present; then
    echo "G4 NOT RUN — legend-engine checkout absent. NOT a pass." >> "$L"
    rec 4 1
  else
  g "GATE4 DuckDB corpus"
  mvn -pl core test -Dtest=RelationalCorpusRunner "$R1" "$R2" > "$OUT/g4.out" 2>&1
  G4=$?; if skipped "$OUT/g4.out"; then
    echo "G4 SKIPPED — no legend-engine checkout at $ROOT_ENGINE. NOT a pass." >> "$L"; G4=1
  fi
  rec 4 $G4; grep -E "h2-exec|Tests run: [0-9]+, Fail" "$OUT/g4.out" | tail -3 >> "$L"
  fi
fi

if want 5; then
  if ! roots_present; then
    echo "G5 NOT RUN — legend-engine checkout absent. NOT a pass." >> "$L"
    rec 5 1
  else
  g "GATE5 h2 corpus"
  mvn -pl core test -Dtest=RelationalCorpusRunner -Drcorpus.backend=h2 "$R1" "$R2" > "$OUT/g5.out" 2>&1
  G5=$?; if skipped "$OUT/g5.out"; then
    echo "G5 SKIPPED — no legend-engine checkout at $ROOT_ENGINE. NOT a pass." >> "$L"; G5=1
  fi
  rec 5 $G5; grep -E "EXACT|h2|Tests run: [0-9]+, Fail" "$OUT/g5.out" | tail -3 >> "$L"
  fi
fi

if want 6; then
  g "GATE6 PCT full DuckDB"
  ( cd pct && mvn "${OFF[@]}" test ) > "$OUT/g6.out" 2>&1
  rec 6 $?; grep -E "Tests run: [0-9]+, Fail" "$OUT/g6.out" | tail -1 >> "$L"
fi

# Ledger: 348 run, <=1 failure, <=22 errors. CEILINGS, not equality — the old
# `grep -q "Tests run: 348, Failures: 1, Errors: 22"` went RED the moment you
# fixed one of the 22. Lower these numbers when you earn it.
G7_MIN_RUN=348; G7_MAX_FAIL=1; G7_MAX_ERR=22
if want 7; then
  g "GATE7 PCT h2modern Relation (run>=$G7_MIN_RUN, fail<=$G7_MAX_FAIL, err<=$G7_MAX_ERR)"
  ( cd pct && LEGENDLITE_PCT_BACKEND=h2 mvn "${OFF[@]}" test -Dtest=Test_LegendLite_RelationFunctions_PCT -Dh2.version=2.4.240 ) > "$OUT/g7.out" 2>&1
  # Anchor on the SUITE line, not `tail -1`. Surefire prints a trailing
  # "Tests run: 1, Failures: 0, Errors: 1" summarising failing CLASSES, and
  # taking the last match picks that instead of the 348-test result — which
  # reported a false RED on a genuinely ledgered run.
  G7_LINE=$(grep -E "Tests run: .*Test_LegendLite_RelationFunctions_PCT" "$OUT/g7.out" | tail -1)
  [ -z "$G7_LINE" ] && G7_LINE=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$OUT/g7.out" | tail -1)
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
fi

if want 8; then
  if ! roots_present; then
    echo "G8 NOT RUN — upstream checkout absent. NOT a pass." >> "$L"
    rec 8 1
  else
  g "GATE8 parser-equivalence: byte parity + rejection parity + SPI seam + pull sentinel"
  # -am is REQUIRED: without it Maven resolves legend-lite-core from ~/.m2,
  # not the reactor, so GATES=8 alone silently A/Bs the previously installed
  # jar. Relying on GATE2 having run first institutionalises hazard #1.
  mvn ${SFLAG[@]+"${SFLAG[@]}"} -pl parser-equivalence -am test \
      -Dtest='CorpusEquivalenceTest,RejectionParityTest,SpiSeamProofTest,SectionParseSentinelTest,FixtureAdjudicationTest,EngineSectionRosterTest,EngineElementRosterTest,PmcdEquivalenceTest,ViewFilterParityTest,LeniencyCatalogTest,StrictDialectParityTest' \
      -Dsurefire.failIfNoSpecifiedTests=false "$R1" "$R2" > "$OUT/g8.out" 2>&1
  G8=$?; if skipped "$OUT/g8.out"; then
    echo "G8 SKIPPED — no upstream checkouts ($ROOT_ENGINE / $ROOT_PURE). NOT a pass." >> "$L"; G8=1
  fi
  rec 8 $G8
  sed -n '4,10p' parser-equivalence/target/equivalence-report.txt >> "$L" 2>/dev/null
  sed -n '3,6p' parser-equivalence/target/rejection-report.txt >> "$L" 2>/dev/null
  sed -n '3,9p' parser-equivalence/target/spi-seam-report.txt >> "$L" 2>/dev/null
  sed -n '3,5p' parser-equivalence/target/section-sentinel-report.txt >> "$L" 2>/dev/null
  fi
fi

if [ ${#FAILED[@]} -eq 0 ]; then
  echo "ALLGATES_DONE — GREEN (gates: $WANT)" >> "$L"
  echo "ALLGATES_DONE — GREEN (gates: $WANT)"
  exit 0
fi
echo "ALLGATES_DONE — FAILED: ${FAILED[*]}" >> "$L"
echo "ALLGATES_DONE — FAILED: ${FAILED[*]}  (detail: $L)" >&2
for k in "${FAILED[@]}"; do n=${k#G}; cp "$OUT/g$n.out" "${L%.log}.g$n.out" 2>/dev/null; done
echo "failing gate output copied beside $L" >&2
exit 1
