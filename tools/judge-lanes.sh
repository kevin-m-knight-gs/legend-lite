#!/bin/bash
# THE FOUR JUDGE LANES + THE DIFFERENTIAL — the pre-commit discipline of the
# judging program (docs/DATABASE_MODE_HOMEWORK_2026_09_18.md): every commit
# ships with the DuckDB and H2 corpus lanes EXACT under both judges (host,
# database) and the per-assert differential at zero unregistered
# disagreements (gate 11's own runs, on the developer's box). Sequential:
# concurrent heavy JVMs get killed on small machines.
#   tools/judge-lanes.sh            # all four lanes + the differential
#   JUDGE_LANES=duck tools/judge-lanes.sh   # the DuckDB pair only (the fast judge)
set -u
cd "$(dirname "$0")/.."
. tools/oracle-roots.sh
OUT=${JUDGE_LANES_OUT:-${TMPDIR:-/tmp}/judge-lanes-$(id -un)}
mkdir -p "$OUT"
lane() {   # lane <name> <mvn -D flags…>
  local name=$1; shift
  echo "=== $name $(date +%T)"
  mvn -q -o -pl spec test -Dtest=MinimalCorpusTest -Dsurefire.excludedGroups= "$R1" "$R2" "$@" > "$OUT/$name.out" 2>&1
  local rc=$?
  grep -aE "roster .* EXACT|fail diff|accepted diff|\[judge-differential\]|leniencies grew|NEW  |STALE |expected: <" "$OUT/$name.out" | grep -v "^\s*at " | head -8
  echo "$name rc=$rc"
  return $rc
}
status=0
rm -f "$OUT/duck-host.tsv" "$OUT/duck-database.tsv"
lane duck-host -Dlegend.judge.mode=host -Dlegend.judge.ledger="$OUT/duck-host.tsv" || status=1
lane duck-database -Dlegend.judge.mode=database -Dlegend.judge.ledger="$OUT/duck-database.tsv" -Dlegend.judge.ledger.host="$OUT/duck-host.tsv" || status=1
if [ "${JUDGE_LANES:-all}" != "duck" ]; then
  lane h2-host -Drcorpus.backend=h2 -Dlegend.judge.mode=host || status=1
  lane h2-database -Drcorpus.backend=h2 -Dlegend.judge.mode=database || status=1
fi
echo "=== judge lanes: $([ $status -eq 0 ] && echo GREEN || echo RED) (outputs in $OUT)"
exit $status
