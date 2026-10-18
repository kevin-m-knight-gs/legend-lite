#!/bin/bash
# Runs the SQL stress corpus: each query in its own JVM (a query that never ends is killed with it), one TSV line each.
#
#   bazel build //core:server_deploy.jar //core:test_cases //core:test_backends
#   core/src/test/resources/sql-stress/run.sh <legend-engine checkout> <out.tsv> [id regex]
#
# A Postgres 16 must answer at STRESS_PG_URL (default jdbc:postgresql://localhost:55432/postgres?user=postgres), passed
# as -Dstress.pg.url; STRESS_JAVA_OPTS passes anything else. The queries are queries.tsv here (python3 gen.py queries.tsv makes them).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../../.." && pwd)
schema="$1/legend-engine-xts-sql/legend-engine-xt-sql-e2e-tests/src/test/resources/parity-tests"
out="$2"
cp="$repo/bazel-bin/core/server_deploy.jar:$repo/bazel-bin/core/libtest_cases.jar:$repo/bazel-bin/core/libtest_backends.jar"
echo -e "id\tchars\tpostgres\tpg_ms\tduck_plan_ms\tduck_sql\tduckdb\tduck_ms\tpg_plan_ms\tpg_sql\tpostgres_lane\tpgl_ms\tdetail" > "$out"
for id in $(cut -f1 "$here/queries.tsv" | grep -E "${3:-.}"); do
  line=$(timeout -s KILL 200 java -Xmx4g -Duser.timezone=GMT \
      "-Dstress.pg.url=${STRESS_PG_URL:-jdbc:postgresql://localhost:55432/postgres?user=postgres}" \
      ${STRESS_JAVA_OPTS:-} -cp "$cp" \
      com.legend.testcases.SqlStressOnPostgres "$schema" "$here/queries.tsv" "^${id}\$" 2>/dev/null | tail -n +2 || true)
  echo "${line:-$id	KILLED}" >> "$out"
done
