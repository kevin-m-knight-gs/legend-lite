#!/bin/bash
# THE DIAGNOSTICS BATTERY — triggered, not scheduled (user ruling
# 2026-08-26, reviving the 2026-08-14 cadence ruling e87fffa8 that the
# DEEP_AUDIT roster-restoration silently clobbered).
#
# Every class here is a MEASUREMENT, not a gate: six are assertless
# printers by their own docs (worklist generators), and
# GrammarCoverageCensusTest's asserts ratchet against inputs that are
# PINNED CONSTANTS between pin changes (corpus manifest SHA + oracle
# jar version) — re-measuring a constant every chain is pure cost
# (~185s of the old gate-8's 254s).
#
# RUN ON EXACTLY THESE TRIGGERS (each changes an input):
#   1. corpus manifest change (docs corpus / fixture tier moved)
#   2. oracle-pin bump (the upstream-checkout / jar bump procedure)
#   3. parser, protocol, or census-code change in parser-equivalence
#
# Usage:
#   LEGEND_ENGINE_ROOT=... LEGEND_PURE_ROOT=... tools/diagnostics.sh
set -u
cd "$(dirname "$0")/.."
ROOT_ENGINE=${LEGEND_ENGINE_ROOT:-$HOME/legend/legend-engine}
ROOT_PURE=${LEGEND_PURE_ROOT:-$HOME/legend/legend-pure}
R1="-Dlegend.engine.root=$ROOT_ENGINE"
R2="-Dlegend.pure.root=$ROOT_PURE"
[ -d "$ROOT_ENGINE" ] || { echo "MISSING legend-engine checkout: $ROOT_ENGINE"; exit 1; }
[ -d "$ROOT_PURE" ]   || { echo "MISSING legend-pure checkout: $ROOT_PURE"; exit 1; }

CLASSES='ParseSpeedBenchmarkTest,CorpusCensusTest,GrammarKeywordCensusTest,ProtocolRosterCensusTest,PmcdReachabilityCensusTest,GrammarCoverageCensusTest,MigrationSizingTest'

OUT=$(mktemp "${TMPDIR:-/tmp}/diagnostics.XXXXXX.out")
trap 'rm -f "$OUT"' EXIT
mvn -pl parser-equivalence -am test \
    -Dtest="$CLASSES" \
    -Dsurefire.failIfNoSpecifiedTests=false "$R1" "$R2" | tee "$OUT"
RC=${PIPESTATUS[0]}

# rename-goes-red, same as the gate: every named class must actually
# RUN — a renamed/deleted diagnostic must not silently shrink the
# battery (the exact mechanism by which the 08-14 cadence ruling was
# lost).
for tc in ${CLASSES//,/ }; do
  if ! grep -q "in com.legend.equivalence.$tc" "$OUT"; then
    echo "DIAGNOSTICS MISSING CLASS: $tc did not run — rename/delete goes RED."
    RC=1
  fi
done
exit $RC
