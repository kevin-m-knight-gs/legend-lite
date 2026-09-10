#!/bin/bash
# ORACLE ROOTS — sourced by tools/allgates.sh and tools/diagnostics.sh (and
# usable from a hand shell: `. tools/oracle-roots.sh && mvn ... "$R1" "$R2"`).
#
# Resolves the legend-engine / legend-pure checkouts the tests read as the
# spec, in ONE place, with ONE precedence:
#     LEGEND_ENGINE_ROOT / LEGEND_PURE_ROOT   (environment)
#   > $HOME/legend/legend-engine, $HOME/legend/legend-pure
# and converts them into the -Dlegend.engine.root / -Dlegend.pure.root
# SYSTEM PROPERTIES the tests actually read (the root pom now also forwards
# the env vars, so a hand `mvn` with the vars exported reads the same
# checkouts — the "unexported root" trap of docs/GATES.md is closed).
#
# oracle_roots_check  — call before running anything:
#   * a MISSING checkout is fatal (a starved corpus passes green — GATES.md);
#   * a checkout on a commit OTHER than tools/oracle-pins.env is fatal
#     (a divergent checkout fakes regressions and re-pins scoreboards
#     against the wrong spec); ORACLE_PIN_CHECK=0 downgrades that to a
#     warning for a deliberate pin-bump session.
# Returns 0 when both checkouts are present and on the pinned commits.
#
# This is the LAPTOP guard: in CI the checkouts are cloned BY the pinned SHA
# and the comparison passes by construction, so CI does not call it
# separately (tools/allgates.sh calls it on entry, everywhere, once). Whether
# the pinned SHA is the release TAG's commit is a property of the pins file,
# not of any clone: tools/version-report.sh INV-2c asks the remote
# (`git ls-remote`), so no clone needs tags and no git runs against the
# oracle clones in CI (USER 2026-09-10).

# BASH_SOURCE is bash-only; from a zsh hand shell fall back to the repo-root
# relative path (both gate scripts cd to the repo root before sourcing)
_OR_HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-tools/oracle-roots.sh}")" && pwd)
# shellcheck source=oracle-pins.env
. "$_OR_HERE/oracle-pins.env"

ROOT_ENGINE=${LEGEND_ENGINE_ROOT:-$HOME/legend/legend-engine}
ROOT_PURE=${LEGEND_PURE_ROOT:-$HOME/legend/legend-pure}
R1="-Dlegend.engine.root=$ROOT_ENGINE"
R2="-Dlegend.pure.root=$ROOT_PURE"

# _oracle_head <dir> — the checkout's HEAD sha, or "" when it is not a git
# checkout (an exported tarball): then only presence can be checked.
_oracle_head() { git -C "$1" rev-parse HEAD 2>/dev/null; }

oracle_roots_check() {
  local ok=0 h
  echo "roots: engine=$ROOT_ENGINE pure=$ROOT_PURE"
  echo "pins:  engine=$LEGEND_ENGINE_SHA ($LEGEND_ENGINE_DESCRIBE)"
  echo "       pure=$LEGEND_PURE_SHA ($LEGEND_PURE_DESCRIBE)"
  [ -d "$ROOT_ENGINE" ] || { echo "MISSING legend-engine checkout: $ROOT_ENGINE"; ok=1; }
  [ -d "$ROOT_PURE" ]   || { echo "MISSING legend-pure checkout: $ROOT_PURE"; ok=1; }
  [ $ok -eq 0 ] || return $ok
  for pair in "engine|$ROOT_ENGINE|$LEGEND_ENGINE_SHA" "pure|$ROOT_PURE|$LEGEND_PURE_SHA"; do
    IFS='|' read -r name dir want <<< "$pair"
    h=$(_oracle_head "$dir")
    if [ -z "$h" ]; then
      echo "WARNING: $dir is not a git checkout — pin drift for legend-$name cannot be checked"
    elif [ "$h" != "$want" ]; then
      if [ "${ORACLE_PIN_CHECK:-1}" = "0" ]; then
        echo "WARNING: legend-$name checkout at $dir is $h, pin is $want (ORACLE_PIN_CHECK=0: continuing)"
      else
        echo "PIN DRIFT: legend-$name checkout at $dir is $h, tools/oracle-pins.env pins $want."
        echo "           Move the checkout to the pin, or bump the pin (docs/GATES.md), or ORACLE_PIN_CHECK=0 for a deliberate bump session."
        ok=1
      fi
    fi
  done
  return $ok
}
