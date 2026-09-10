#!/bin/bash
# THE VERSION REPORT — every upstream identity legend-lite pins, side by side
# with what upstream has released, plus the three INVARIANTS that say whether
# the spread between them is legal.
#
# There is no single "legend version" here. Four identities are live at once
# (docs/UPSTREAM_BOUNDARY_HOMEWORK_2026_09_10.md §1), each controlling a different
# gate's universe:
#
#   SOURCE   tools/oracle-pins.env      gates 1,4,5,8,9 + the prelude generator
#                                       read these CHECKOUTS as the spec
#   ORACLE   parser-equivalence/pom.xml the reference PARSER gate 8 differs against
#   PCT      pct/pom.xml                the PCT framework + ReportScopes, gates 6,7
#   RUNNER   tools/engine-runner/pom.xml the perf harness (not a gate)
#
# Usage:
#   tools/version-report.sh            report (exit 0 unless an invariant breaks)
#   tools/version-report.sh --offline  pins only, no network
#   tools/version-report.sh --check    invariants only, quiet on success
#
# Exit 1 on an invariant violation, 2 on a broken pin file.

set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-tools/version-report.sh}")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)

OFFLINE=0
CHECK_ONLY=0
for a in "$@"; do
  case "$a" in
    --offline) OFFLINE=1 ;;
    --check) CHECK_ONLY=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

CENTRAL=https://repo1.maven.org/maven2
ENGINE_GA=org/finos/legend/engine/legend-engine-language-pure-grammar
PURE_GA=org/finos/legend/pure/legend-pure-m3-core

say() { [ "$CHECK_ONLY" = 1 ] || echo "$@"; }

# ---------------------------------------------------------------- the pins ---
. "$HERE/oracle-pins.env" || { echo "cannot read tools/oracle-pins.env" >&2; exit 2; }

# <legend.engine.version> / <legend.pure.version> out of a pom's own properties
pom_prop() {  # pom_prop <file> <property>
  sed -n "s|.*<$2>\([^<]*\)</$2>.*|\1|p" "$1" | head -1
}
# a hardcoded <version> under a named artifactId (pure m3-core in the parser pom)
pom_dep_version() {  # pom_dep_version <file> <artifactId>
  grep -A2 "<artifactId>$2</artifactId>" "$1" \
    | sed -n 's|.*<version>\([^<$][^<]*\)</version>.*|\1|p' | head -1
}

ORACLE_ENGINE=$(pom_prop "$ROOT/parser-equivalence/pom.xml" legend.engine.version)
ORACLE_PURE=$(pom_dep_version "$ROOT/parser-equivalence/pom.xml" legend-pure-m3-core)
PCT_ENGINE=$(pom_prop "$ROOT/pct/pom.xml" legend.engine.version)
PCT_PURE=$(pom_prop "$ROOT/pct/pom.xml" legend.pure.version)
RUNNER_ENGINE=$(pom_prop "$ROOT/tools/engine-runner/pom.xml" legend.version)

# the SOURCE pins name commits; `git describe` gives them a release identity
SRC_ENGINE_REL=${LEGEND_ENGINE_DESCRIBE#legend-engine-}
SRC_PURE_REL=${LEGEND_PURE_DESCRIBE#legend-pure-}
SRC_ENGINE_BASE=${SRC_ENGINE_REL%%-*}
SRC_PURE_BASE=${SRC_PURE_REL%%-*}

# the version carried by a committed FILENAME (the harvested fixture snapshot)
FIXTURE=$(ls "$ROOT"/parser-equivalence/src/test/resources/engine-grammar-fixtures-*.jsonl 2>/dev/null | head -1)
FIXTURE_VER=$(basename "${FIXTURE:-engine-grammar-fixtures-none.jsonl}" .jsonl)
FIXTURE_VER=${FIXTURE_VER#engine-grammar-fixtures-}

# ------------------------------------------------------------- the upstream ---
# Maven Central and the git tags DISAGREE: 4.142.0 is tagged and was never
# published, 4.135.3/.4 likewise. Jars can only pin what Central has; source
# pins can name any commit. Both are reported.
LATEST_ENGINE=""; LATEST_PURE=""; TAG_ENGINE=""; TAG_PURE=""
ENGINE_VERSIONS=""; PURE_VERSIONS=""
if [ "$OFFLINE" = 0 ]; then
  ENGINE_META=$(curl -sf --max-time 40 "$CENTRAL/$ENGINE_GA/maven-metadata.xml" 2>/dev/null)
  PURE_META=$(curl -sf --max-time 40 "$CENTRAL/$PURE_GA/maven-metadata.xml" 2>/dev/null)
  LATEST_ENGINE=$(printf '%s' "$ENGINE_META" | sed -n 's|.*<release>\([^<]*\)</release>.*|\1|p')
  LATEST_PURE=$(printf '%s' "$PURE_META" | sed -n 's|.*<release>\([^<]*\)</release>.*|\1|p')
  ENGINE_VERSIONS=$(printf '%s' "$ENGINE_META" | grep -oE '<version>[^<]+' | sed 's|<version>||')
  PURE_VERSIONS=$(printf '%s' "$PURE_META" | grep -oE '<version>[^<]+' | sed 's|<version>||')
  # ls-remote, NOT the GitHub tags API: that endpoint PAGES (legend-engine has
  # 1,232 tags) and does not return them in version order — its first page opens
  # with the legacy `legend-engine-release-4.114.0` names, so a first-page read
  # sorted with `sort -V` is sampling, and silently reports the wrong "latest"
  # as soon as the newest tag falls off page one.
  TAG_ENGINE=$(git ls-remote --tags "https://github.com/$LEGEND_ENGINE_REPO" 2>/dev/null \
    | sed -n 's|.*refs/tags/legend-engine-\([0-9][0-9.]*\)$|\1|p' | sort -V | tail -1)
  TAG_PURE=$(git ls-remote --tags "https://github.com/$LEGEND_PURE_REPO" 2>/dev/null \
    | sed -n 's|.*refs/tags/legend-pure-\([0-9][0-9.]*\)$|\1|p' | sort -V | tail -1)
fi

# releases published AFTER <version>, from a metadata version list. Legacy
# non-numeric versions (release-4.114.0, legend-pure-4.5.8) are dropped first:
# `sort -V` sorts them to the END of the list and they inflate every count.
behind() {  # behind <version> <newline-separated list>
  local v=$1 list=$2
  [ -n "$list" ] || { echo "?"; return; }
  printf '%s\n' "$list" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | sort -V \
    | awk -v v="$v" 'seen{n++} $0==v{seen=1} END{print seen?n:"?"}'
}

# the legend-pure version an ENGINE release declares in its own pom — the
# upstream pairing, and the authority for invariant 1
pure_of_engine() {  # pure_of_engine <engine version>
  [ "$OFFLINE" = 0 ] || { echo ""; return; }
  curl -sf --max-time 40 \
    "$CENTRAL/org/finos/legend/engine/legend-engine/$1/legend-engine-$1.pom" 2>/dev/null \
    | sed -n 's|.*<legend.pure.version>\([^<]*\)</legend.pure.version>.*|\1|p' | head -1
}

# ----------------------------------------------------------------- report ---
say
say "==================== legend-lite upstream pins, $(date +%Y-%m-%d) ===================="
say
printf_row() { [ "$CHECK_ONLY" = 1 ] || printf '%-9s %-28s %-24s %-21s %s\n' "$1" "$2" "$3" "$4" "$5"; }
printf_row IDENTITY "DECLARED IN" ENGINE PURE "RELEASES BEHIND"
printf_row --------- ---------------------------- ------------------------ --------------------- ---------------
printf_row SOURCE "tools/oracle-pins.env" "$SRC_ENGINE_REL" "$SRC_PURE_REL" \
  "$(behind "$SRC_ENGINE_BASE" "$ENGINE_VERSIONS")/$(behind "$SRC_PURE_BASE" "$PURE_VERSIONS")"
printf_row ORACLE "parser-equivalence/pom.xml" "$ORACLE_ENGINE" "$ORACLE_PURE" \
  "$(behind "$ORACLE_ENGINE" "$ENGINE_VERSIONS")/$(behind "$ORACLE_PURE" "$PURE_VERSIONS")"
printf_row PCT "pct/pom.xml" "$PCT_ENGINE" "$PCT_PURE" \
  "$(behind "$PCT_ENGINE" "$ENGINE_VERSIONS")/$(behind "$PCT_PURE" "$PURE_VERSIONS")"
printf_row RUNNER "tools/engine-runner/pom.xml" "$RUNNER_ENGINE" "-" \
  "$(behind "$RUNNER_ENGINE" "$ENGINE_VERSIONS")/-"
printf_row FIXTURE "parser-equiv .../fixtures-*" "$FIXTURE_VER" "-" \
  "$(behind "$FIXTURE_VER" "$ENGINE_VERSIONS")/-"
say
printf_row LATEST "maven central (release)" "${LATEST_ENGINE:-?}" "${LATEST_PURE:-?}" ""
printf_row LATEST "git tags (newest)" "${TAG_ENGINE:-?}" "${TAG_PURE:-?}" ""
say

# local checkouts: present, and on the pins?
say "---- local checkouts ----"
for pair in "engine|${LEGEND_ENGINE_ROOT:-$HOME/legend/legend-engine}|$LEGEND_ENGINE_SHA" \
            "pure|${LEGEND_PURE_ROOT:-$HOME/legend/legend-pure}|$LEGEND_PURE_SHA"; do
  IFS='|' read -r name dir want <<< "$pair"
  if [ ! -d "$dir" ]; then
    say "  legend-$name: MISSING at $dir"
  else
    h=$(git -C "$dir" rev-parse HEAD 2>/dev/null)
    d=$(git -C "$dir" describe --tags 2>/dev/null)
    if [ -z "$h" ]; then say "  legend-$name: $dir (not a git checkout)"
    elif [ "$h" = "$want" ]; then say "  legend-$name: ON PIN    $d"
    else say "  legend-$name: PIN DRIFT $d  ($h != $want)"
    fi
  fi
done
say

# ------------------------------------------------------------- invariants ---
FAIL=0
inv() {  # inv <id> <ok:0|1> <text>
  if [ "$2" = 0 ]; then say "  OK    INV-$1  $3"
  else echo "  BROKEN INV-$1  $3"; FAIL=1
  fi
}
say "---- invariants (docs/UPSTREAM_BOUNDARY_HOMEWORK_2026_09_10.md §2) ----"

# INV-1: a jar identity pairs engine with the pure version THAT engine release
# declares. Mixing an engine with a pure it was never built against means the
# oracle's own platform sources disagree with its compiler.
if [ "$OFFLINE" = 1 ]; then
  say "  SKIP  INV-1  (offline: upstream pairing not checked)"
else
  want=$(pure_of_engine "$ORACLE_ENGINE")
  inv "1a" "$([ -n "$want" ] && [ "$want" = "$ORACLE_PURE" ] && echo 0 || echo 1)" \
    "ORACLE pure $ORACLE_PURE == engine $ORACLE_ENGINE's own legend.pure.version ${want:-<unresolved>}"
  want=$(pure_of_engine "$PCT_ENGINE")
  inv "1b" "$([ -n "$want" ] && [ "$want" = "$PCT_PURE" ] && echo 0 || echo 1)" \
    "PCT pure $PCT_PURE == engine $PCT_ENGINE's own legend.pure.version ${want:-<unresolved>}"
fi

# INV-2: the SOURCE pins name RELEASE TAGS, so the oracle jar can be the same
# release as the source. Gate 8 differs our parser against the oracle over the
# SOURCE checkout's files, and jars exist ONLY at release tags — so a source pin
# on an arbitrary commit makes an identical oracle IMPOSSIBLE, and the two trees
# end up DIVERGED rather than ordered (2026-09-10: the 4.137.0+36 pin is 20
# commits ahead of the 4.138.2 tag and 11 behind it). Every construct on either
# side of that divergence becomes a hand-adjudicated row in
# docs/version-skew-claims.tsv. "The oracle is deliberately ahead" needs an
# ORDERING that a non-tag pin cannot provide.
for pair in "engine|$SRC_ENGINE_REL" "pure|$SRC_PURE_REL"; do
  IFS='|' read -r name rel <<< "$pair"
  case "$rel" in
    *-*-g*) inv "2$([ "$name" = engine ] && echo a || echo b)" 1 \
              "SOURCE $name pin $rel is NOT a release tag — no jar exists at this commit, so the oracle cannot match it" ;;
    *)      inv "2$([ "$name" = engine ] && echo a || echo b)" 0 \
              "SOURCE $name pin $rel is a release tag" ;;
  esac
done
inv "2c" "$([ "$ORACLE_ENGINE" = "$SRC_ENGINE_BASE" ] && echo 0 || echo 1)" \
  "ORACLE engine $ORACLE_ENGINE == SOURCE engine $SRC_ENGINE_BASE  (skew hand-adjudicated in docs/version-skew-claims.tsv: $(grep -c . "$ROOT/docs/version-skew-claims.tsv" 2>/dev/null) rows — that ledger is the COST of this row being broken)"

# INV-3: PCT jars == the SOURCE pin. Channel A discovers its universe from
# these JARS (ReportScope); Channel B discovers the SAME universe by walking
# the SOURCE checkouts. They are a dual-verdict pair, so a version spread
# means the two channels referee different test sets and the comparison is
# not like-for-like.
inv "3a" "$([ "$PCT_ENGINE" = "$SRC_ENGINE_BASE" ] && echo 0 || echo 1)" \
  "PCT engine $PCT_ENGINE == SOURCE engine $SRC_ENGINE_BASE  (channel A jars vs channel B source walk)"
inv "3b" "$([ "$PCT_PURE" = "$SRC_PURE_BASE" ] && echo 0 || echo 1)" \
  "PCT pure $PCT_PURE == SOURCE pure $SRC_PURE_BASE"

# INV-4: the committed fixture snapshot was harvested from the ORACLE jars it
# is adjudicated against (tier C6 of the parser corpus).
inv 4 "$([ "$FIXTURE_VER" = "$ORACLE_ENGINE" ] && echo 0 || echo 1)" \
  "FIXTURE snapshot $FIXTURE_VER == ORACLE engine $ORACLE_ENGINE"

say
if [ "$FAIL" = 0 ]; then say "all invariants hold."; else echo; echo "INVARIANT VIOLATIONS above — see docs/UPSTREAM_BOUNDARY_HOMEWORK_2026_09_10.md §2."; fi
exit $FAIL
