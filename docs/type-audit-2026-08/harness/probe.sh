#!/bin/bash
# Usage: probe.sh <modelFile> <queryFile|-> [runtimeFqn] [ddlFile]
CP=$(cat /tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/fullcp.txt)
exec java -cp "/home/user/probe:$CP" Probe "$@" 2>&1 | grep -v "JAVA_TOOL_OPTIONS\|^SLF4J"
