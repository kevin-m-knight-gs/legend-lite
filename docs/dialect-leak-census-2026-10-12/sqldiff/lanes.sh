#!/bin/bash
# Every lane's whole SQL dump into <out dir>: the eight local databases, both channels, five suites each (H2: Relation
# only); with --clouds, Snowflake, Databricks and BigQuery too (LEGENDLITE_PCT_CLOUD_PROPERTIES must name the
# credentials file). Batches keep ClickHouse and Trino apart (two embedded servers exceed a 15 GB container).
# usage: lanes.sh <out dir> [--clouds]
# Then: python3 -I compare.py <before dir> <after dir>. The plan, §0: docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md.
here=$(cd "$(dirname "$0")" && pwd); out=$1
suites="essential grammar relation standard unclassified"
pair() { local T=""; for s in $suites; do T="$T //pct:pct_$1_$s //pct:pct_channel_b_$1_$s"; done; echo $T; }
T=""; for s in $suites; do T="$T //pct:pct_duckdb_$s //pct:pct_channel_b_$s"; done
"$here/sqlrun.sh" "$out" duckdb_h2 $T //pct:pct_h2 //pct:pct_channel_b_h2_relation
for db in sqlite mariadb mysql; do "$here/sqlrun.sh" "$out" $db $(pair $db); done
"$here/sqlrun.sh" "$out" postgres --pg $(pair postgres)
"$here/sqlrun.sh" "$out" clickhouse $(pair clickhouse)
"$here/sqlrun.sh" "$out" trino $(pair trino)
if [ "$2" = "--clouds" ]; then
  for db in snowflake databricks bigquery; do "$here/sqlrun.sh" "$out" $db $(pair $db); done
fi
cat "$out/../logs/"*.status | grep -v PASSED
