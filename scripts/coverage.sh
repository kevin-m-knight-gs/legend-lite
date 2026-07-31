#!/usr/bin/env bash
#
# THE ONLY SUPPORTED WAY TO MEASURE COVERAGE.
#
# core/ production code is exercised by THREE suites in three modules:
#   core's own unit tests, engine's corpus sweep, and pct.
# Reading any one of them alone understates coverage badly. The JaCoco exec
# file is APPEND-mode so the three can be merged — which also means a stale
# file silently inflates the number, and coverage can only ever appear to
# rise. `mvn -pl core clean` does NOT delete it (it lives at the reactor
# root). So this script owns the whole lifecycle:
#
#   1. delete the stale exec                  -- else the number is a lie
#   2. run all three suites, in order         -- partial runs understate
#   3. restore docs/RELATIONAL_CORPUS.md      -- the sweep REGENERATES the
#                                                baseline it asserts against,
#                                                so a failed sweep leaves the
#                                                next one passing trivially
#   4. report once, from the merged exec
#
# Usage:  scripts/coverage.sh [--skip-corpus] [--skip-pct]
# Env:    LEGEND_ENGINE_ROOT  (default /Users/neemsandv/legend/legend-engine)
#
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
EXEC="$ROOT/target/jacoco.exec"
BASELINE="docs/RELATIONAL_CORPUS.md"
ENGINE_ROOT="${LEGEND_ENGINE_ROOT:-/Users/neemsandv/legend/legend-engine}"

SKIP_CORPUS=0; SKIP_PCT=0
for a in "$@"; do
  case "$a" in
    --skip-corpus) SKIP_CORPUS=1 ;;
    --skip-pct)    SKIP_PCT=1 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

MVN=(mvn -B -P coverage)
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
partial=0

# --- 1. no stale data, ever -------------------------------------------------
step "clearing stale exec"
rm -f "$EXEC"
mkdir -p "$ROOT/target"

# baseline is restored on ANY exit path, including failure or Ctrl-C.
# The handler must PRESERVE the script's exit status — a bash EXIT trap
# otherwise replaces it with the status of its own last command.
restore_baseline() {
  local rc=$?
  if ! git diff --quiet -- "$BASELINE" 2>/dev/null; then
    git checkout -- "$BASELINE" && echo "restored $BASELINE (the sweep rewrote it)"
  fi
  exit "$rc"
}
trap restore_baseline EXIT

# --- 2. all three suites, in order ------------------------------------------
step "core: unit suite + null gate (clean is load-bearing)"
"${MVN[@]}" -pl core clean test || { echo "core suite FAILED — coverage number would be partial"; exit 1; }

step "installing core for downstream modules"
"${MVN[@]}" -pl core install -DskipTests -q || exit 1

if [ "$SKIP_CORPUS" -eq 0 ]; then
  step "engine: corpus sweep"
  "${MVN[@]}" -pl engine test -Dtest=RelationalCorpusRunner \
      -Dlegend.engine.root="$ENGINE_ROOT" \
    || { echo "NOTE: corpus sweep did not pass — coverage still collected, but the run is PARTIAL"; partial=1; }
else
  echo "skipping corpus sweep (--skip-corpus): the number will be PARTIAL"; partial=1
fi

if [ "$SKIP_PCT" -eq 0 ] && [ -d pct ]; then
  step "pct suite"
  "${MVN[@]}" -pl pct test \
    || { echo "NOTE: pct did not pass — coverage still collected, but the run is PARTIAL"; partial=1; }
else
  echo "skipping pct (--skip-pct): the number will be PARTIAL"; partial=1
fi

# --- 3. one report, from the merged exec ------------------------------------
step "report (union of every suite above)"
[ -f "$EXEC" ] || { echo "no exec file produced — did the agent attach?"; exit 1; }
"${MVN[@]}" -pl core org.jacoco:jacoco-maven-plugin:report -Djacoco.dataFile="$EXEC" -q || exit 1

CSV="core/target/site/jacoco/jacoco.csv"
python3 - "$CSV" "$partial" <<'PY'
import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1])))
partial = sys.argv[2] == "1"
def pct(m, c): return 0.0 if (m + c) == 0 else 100.0 * c / (m + c)
def tot(k): return (sum(int(r[k + '_MISSED']) for r in rows),
                    sum(int(r[k + '_COVERED']) for r in rows))
print()
for k in ("INSTRUCTION", "BRANCH", "LINE", "METHOD"):
    m, c = tot(k)
    print(f"  {k.lower():12s} {pct(m,c):5.1f}%   {c:,} / {c+m:,}   (missed {m:,})")
lm, lc = tot('LINE'); bm, bc = tot('BRANCH')
print(f"\n  shrink-only ratchet for this run:  LINE MISSEDCOUNT <= {lm}   BRANCH MISSEDCOUNT <= {bm}")
pk = collections.defaultdict(lambda: [0, 0])
for r in rows:
    pk[r['PACKAGE']][0] += int(r['LINE_MISSED']); pk[r['PACKAGE']][1] += int(r['LINE_COVERED'])
print("\n  weakest packages (>=300 lines):")
for p, (m, c) in sorted(pk.items(), key=lambda kv: pct(*kv[1]))[:6]:
    if m + c >= 300: print(f"    {pct(m,c):5.1f}%  {p}  ({c:,}/{m+c:,})")
if partial:
    print("\n  *** PARTIAL RUN — a suite was skipped or failed. This number is a")
    print("      LOWER BOUND and must NOT be used to set or check a ratchet. ***")
PY

echo
echo "html: core/target/site/jacoco/index.html"
[ "$partial" -eq 1 ] && exit 3 || exit 0
