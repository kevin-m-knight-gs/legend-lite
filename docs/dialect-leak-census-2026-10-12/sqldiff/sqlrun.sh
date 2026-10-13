#!/bin/bash
# One batch of PCT targets with every statement dumped (-Dlegend.diagnostics=dump-sql): run them, extract each
# test.log's whole statements into <out dir>/<target>.sql.gz (extract.py), keep a summary, then drop the test logs
# and the runfiles trees (a container's disk allowance cannot hold them all).
# usage: sqlrun.sh <out dir> <batch name> [--pg] <targets...>     (--pg: Postgres as an unprivileged user, docs/GATES.md)
# The plan, §0: docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md.
out=$(realpath -m "$1"); shift; name=$1; shift
here=$(cd "$(dirname "$0")" && pwd)
extra=()
if [ "$1" = "--pg" ]; then shift; extra=(--strategy=TestRunner=standalone "--run_under=$(git rev-parse --show-toplevel)/tools/postgres/as_unprivileged.sh ubuntu"); fi
logs="$out/../logs"; mkdir -p "$out" "$logs"
cd "$(git rev-parse --show-toplevel)" || exit 2
TL=$(bazel info bazel-testlogs 2>/dev/null)/pct
BIN=$(bazel info bazel-bin 2>/dev/null)/pct
bazel test "$@" --disk_cache= --local_test_jobs=2 --nocache_test_results --test_output=errors \
  --jvmopt=-Dlegend.diagnostics=dump-sql "${extra[@]}" > "$logs/$name.bazel.log" 2>&1
echo "exit=$?" >> "$logs/$name.bazel.log"
for t in "$@"; do
  n=${t#//pct:}
  f=$TL/$n/test.log
  [ -f "$f" ] || { echo "== $n: NO LOG" >> "$logs/$name.summary"; continue; }
  python3 -I "$here/extract.py" "$f" "$out/$n.sql" > /dev/null && gzip -f "$out/$n.sql"
  { echo "== $n"; grep -E "tests successful|tests failed|measured:" "$f" | cut -c1-200; } >> "$logs/$name.summary"
done
chmod -R u+w "$TL" 2>/dev/null; rm -rf "$TL"
grep -E "PASSED|FAILED|TIMEOUT|NO STATUS|exit=" "$logs/$name.bazel.log" | sed 's/  */ /g' > "$logs/$name.status"
find "$BIN" -maxdepth 1 -name "*.runfiles*" -exec chmod -R u+w {} + 2>/dev/null
find "$BIN" -maxdepth 1 -name "*.runfiles*" -exec rm -rf {} +
