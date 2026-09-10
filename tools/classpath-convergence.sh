#!/bin/bash
# CLASSPATH CONVERGENCE — INV-5 (docs/UPSTREAM_BOUNDARY_PROGRAM.md §3 A, §5).
#
# The upstream-facing modules do not share a dependency SET — pct needs the PCT
# framework and the interpreted runtime, parser-equivalence the grammar/compiler/
# extension jars. What they MUST share is the VERSION of every artifact present in
# both: when upstream is one release, every org.finos.legend.* resolves at that
# release and every transitive third-party both modules pull resolves identically.
# A divergence means either the pins did not propagate (ours to fix) or upstream
# itself pins two versions of something across its own modules (theirs to record).
#
# Also checks the boundary: `core` (and `spec`, once it exists) must resolve ZERO
# org.finos.legend artifacts.
#
# Usage:  tools/classpath-convergence.sh            # report; exit 1 on any divergence
#         tools/classpath-convergence.sh --quiet    # counts only
#
# Cost: one `mvn dependency:list` per module (~20-40s each). A check for the end of
# batch 1 and for CI on a pin change — not for every push.

set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-tools/classpath-convergence.sh}")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
QUIET=0; [ "${1:-}" = "--quiet" ] && QUIET=1
OUT=$(mktemp -d "${TMPDIR:-/tmp}/cpconv.XXXXXX")

UPSTREAM_FACING="pct parser-equivalence"
[ -d "$ROOT/spec" ] && BOUNDARY="core spec" || BOUNDARY="core"

cd "$ROOT" || exit 2
for m in $UPSTREAM_FACING $BOUNDARY; do
  mvn -q -pl "$m" dependency:list -DincludeScope=test -DoutputFile="$OUT/$m.txt" >/dev/null 2>&1 \
    || { echo "dependency:list failed for $m" >&2; exit 2; }
done

python3 - "$OUT" "$QUIET" "$UPSTREAM_FACING" "$BOUNDARY" <<'PYEOF'
import re, sys, itertools
out, quiet, facing, boundary = sys.argv[1], sys.argv[2] == "1", sys.argv[3].split(), sys.argv[4].split()
PAT = re.compile(r'\s+([\w.\-]+):([\w.\-]+):(jar|test-jar|pom|war|bundle):(?:([\w\-]+):)?([\w.\-]+):(\w+)')
def load(m):
    d = {}
    for l in open(f"{out}/{m}.txt"):
        mm = PAT.match(l)
        if mm:
            d[(mm.group(1), mm.group(2), mm.group(4) or "")] = mm.group(5)
    return d
maps = {m: load(m) for m in facing + boundary}
fail = 0

print("==== INV-5 — shared artifacts resolve at ONE version across the upstream-facing modules ====")
for a, b in itertools.combinations(facing, 2):
    da, db = maps[a], maps[b]
    shared = sorted(k for k in da if k in db)
    div = [(k, da[k], db[k]) for k in shared if da[k] != db[k]]
    legend = [d for d in div if d[0][0].startswith("org.finos.legend")]
    other = [d for d in div if not d[0][0].startswith("org.finos.legend")]
    print(f"  {a} ({len(da)}) vs {b} ({len(db)}): shared {len(shared)}, DIVERGENT {len(div)} "
          f"(legend {len(legend)}, third-party {len(other)})")
    if div:
        fail = 1
        if not quiet:
            for (g, art, cls), va, vb in sorted(div):
                print(f"      {g}:{art}{(':' + cls) if cls else ''}   {a}={va}   {b}={vb}")
    # one legend version per module, too
    for m, d in ((a, da), (b, db)):
        eng = sorted({v for (g, _, _), v in d.items() if g == "org.finos.legend.engine"})
        pur = sorted({v for (g, _, _), v in d.items() if g == "org.finos.legend.pure"})
        if len(eng) > 1 or len(pur) > 1:
            fail = 1
            print(f"      MIXED within {m}: engine {eng} pure {pur}")

print("\n==== the boundary — no org.finos.legend artifact on these classpaths ====")
for m in boundary:
    leg = sorted(f"{g}:{a}:{v}" for (g, a, _), v in maps[m].items() if g.startswith("org.finos.legend"))
    print(f"  {m}: {len(leg)} legend artifact(s)" + ("" if not leg else "  <-- BOUNDARY BREACH"))
    if leg:
        fail = 1
        if not quiet:
            for l in leg: print(f"      {l}")

print("\n" + ("all converged; boundary clean." if not fail else "DIVERGENCE — see above."))
sys.exit(fail)
PYEOF
rc=$?
rm -rf "$OUT"
exit $rc
