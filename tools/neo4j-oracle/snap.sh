#!/bin/sh
# The #Cypher{...}# island's oracle (README.md): snap.py on a Python 3 with the Apache 2.0 Neo4j driver installed,
# against a Neo4j Community server running locally (never one shared with anything else: the tool EMPTIES the graph
# before every case).
#   tools/neo4j-oracle/snap.sh --write [--only GLOB]    snap the answers into cypher-parity/neo4j-answers.jsonl
#   tools/neo4j-oracle/snap.sh --check [--only GLOB]    report DRIFT, UNSNAPPED, STALE, GONE, VERSION (exit 1 on any)
#   tools/neo4j-oracle/snap.sh --census                 count the cases by what they expect (no server)
# or `bazel run //tools/neo4j-oracle:snap -- --check`. NEO4J_URL (default bolt://127.0.0.1:7687) and
# NEO4J_ORACLE_PYTHON (default python3) override the two defaults.
python=${NEO4J_ORACLE_PYTHON:-python3}
url=${NEO4J_URL:-bolt://127.0.0.1:7687}
# under `bazel run`, the checkout's own files (this script's copy sits in the runfiles)
if [ -n "${BUILD_WORKSPACE_DIRECTORY:-}" ]; then
  here=$BUILD_WORKSPACE_DIRECTORY/tools/neo4j-oracle
else
  here=$(cd "$(dirname "$0")" && pwd)
fi
exec "$python" -I "$here/snap.py" --url "$url" "$@"
