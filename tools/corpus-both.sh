#!/bin/bash
# Full corpus on BOTH lanes (DuckDB, H2) — the measure before any chain.
# Logs: ${CORPUS_LOG_DIR:-/tmp}/corpus-<lane>.log. Prints LOST/GAINED and the pins.
cd "$(dirname "$0")/.."
. tools/oracle-roots.sh
OUT=${CORPUS_LOG_DIR:-/tmp}
for b in duckdb h2; do
  mvn -q -o -pl spec test -Dtest=MinimalCorpusTest -Dsurefire.excludedGroups= -Drcorpus.backend=$b "$R1" "$R2" > "$OUT/corpus-$b.log" 2>&1
  echo "=== $b exit $?"
  grep -a "GAINED\|LOST\|strength\|Tests run:.*Fail\|pass=" "$OUT/corpus-$b.log" | cut -c1-300 | head -40
done
