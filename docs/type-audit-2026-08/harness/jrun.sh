#!/bin/bash
# Compile+run any single-file Java probe against the full legend-lite
# classpath. Honours a `package` declaration, so you can reach
# package-private API. DuckDB, SQLite and H2 drivers are all present.
# Usage: jrun.sh <File.java> [args...]
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/.cp" ] || { echo "run $HERE/setup.sh first" >&2; exit 1; }
CP=$(cat "$HERE/.cp")
F="$1"; shift
D=$(mktemp -d)
javac -nowarn -cp "$CP" -d "$D" "$F" 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
PKG=$(grep -m1 '^package ' "$F" | sed 's/package *//;s/;.*//')
CLS=$(basename "$F" .java)
[ -n "$PKG" ] && CLS="$PKG.$CLS"
java -cp "$D:$CP" "$CLS" "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS\|^SLF4J"
