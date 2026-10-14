#!/bin/sh
# The #IQ{...}# island's oracle (README.md): snap.py on an x86_64 Python with SQL Anywhere 17's environment.
#   tools/iq-oracle/snap.sh --write [--only GLOB] [--verbose]    snap the answers into iq-answers.jsonl
#   tools/iq-oracle/snap.sh --check [--only GLOB] [--verbose]    report DRIFT, UNSNAPPED, STALE, GONE (exit 1 on any)
# or `bazel run //tools/iq-oracle:snap -- --check`. SA_CONFIG and IQ_ORACLE_PYTHON override the two paths below.
sa_config=${SA_CONFIG:-/Applications/SQLAnywhere17/System/bin64/sa_config.sh}
python=${IQ_ORACLE_PYTHON:-/Library/Developer/CommandLineTools/usr/bin/python3}
if [ ! -r "$sa_config" ]; then
  echo "snap.sh: no SQL Anywhere 17 at $sa_config (tools/iq-oracle/README.md says what to install)" >&2
  exit 2
fi
# shellcheck source=/dev/null
. "$sa_config" >/dev/null 2>&1
# under `bazel run`, the checkout's own files (this script's copy sits in the runfiles)
if [ -n "${BUILD_WORKSPACE_DIRECTORY:-}" ]; then
  here=$BUILD_WORKSPACE_DIRECTORY/tools/iq-oracle
else
  here=$(cd "$(dirname "$0")" && pwd)
fi
exec arch -x86_64 "$python" -I "$here/snap.py" "$@"
