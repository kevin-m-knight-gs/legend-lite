#!/bin/bash
# Compile+run a scratch java file against the legend-lite classpath.
# Supports package-declared files (so you can reach package-private API).
# Usage: jrun.sh <File.java> [args...]
F="$1"; shift
D=$(mktemp -d)
CP=$(cat /tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/fullcp.txt)
javac -nowarn -cp "$CP" -d "$D" "$F" 2>&1 | grep -v "JAVA_TOOL_OPTIONS"
PKG=$(grep -m1 '^package ' "$F" | sed 's/package *//;s/;.*//')
CLS=$(basename "$F" .java)
[ -n "$PKG" ] && CLS="$PKG.$CLS"
java -cp "$D:$CP" "$CLS" "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS\|^SLF4J"
