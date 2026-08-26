#!/bin/bash
# Full-pipeline probe: Phase-G root type + typed-HIR tree, rendered SQL,
# and the execution result with each column's Pure type beside each cell's
# Java runtime class. Errors are captured per phase, never thrown.
# Usage: probe.sh <modelFile> <queryFile|-> [runtimeFqn] [ddlFile]
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$HERE/.cp" ] || { echo "run $HERE/setup.sh first" >&2; exit 1; }
exec java -cp "$HERE:$(cat "$HERE/.cp")" Probe "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS\|^SLF4J"
