"""
What the stress corpus actually exercises, measured against the protocol roster.

This answers a different question from `scripts/census_gate.py`, and the difference
matters. The census probes scan the **upstream** corpus — legend-engine and legend-pure
checkouts plus their inline snippets — and mark a protocol `_type` tag COVERED when some
upstream fixture uses it. That is the right denominator for "how much of the language
exists and has legend-lite's parser met it", but it is blind to our own corpus: adding a
thousand tests here can never move it.

So `census_gate.py check --strict`, which fails a change that "moved no census counter",
cannot be satisfied by corpus work at all. That was an error in how the gate was framed.
This script supplies the missing axis: it compiles our corpus with legend-engine, dumps
the protocol document, and reports which roster tags it exercises.

A caveat that matters more than the number: the roster contains only POLYMORPHIC protocol
types, the ones carrying a `_type` discriminator. A feature modelled as a plain field on its
parent — EnumerationMapping is the worked example — never appears, so this measures a real
thing but not "how much of Legend we cover". See docs/TEST_CORPUS_MASTER_PLAN.md §2b.

Usage:
  python3 scripts/corpus/coverage.py             summary + what we exercise
  python3 scripts/corpus/coverage.py --gaps      roster tags NOT exercised, by package
  python3 scripts/corpus/coverage.py --snapshot  write docs/corpus-coverage.json
  python3 scripts/corpus/coverage.py --check     fail if coverage went DOWN

The baseline is committed for the same reason docs/census-baseline.json is: a number
nobody can regress against is a number nobody notices losing. These are different
baselines and both are needed — census-baseline.json measures the UPSTREAM corpus and
cannot move when we add tests here; this one measures ours and cannot move when upstream
changes.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

from model import STRESS

REPO = STRESS.parents[4]
RUNNER = REPO / "tools" / "engine-runner"
ROSTER = REPO / "parser-equivalence" / "target" / "protocol-roster.txt"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))
OUT = RUNNER / "target" / "stress-pmcd.json"


def compile_pmcd() -> dict:
    files = sorted(str(p) for p in STRESS.glob("*.pure"))
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:" + os.environ["PATH"])
    cp = (RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run([f"{JAVA_HOME}/bin/java", "-cp", f"{RUNNER}/target/classes:{cp}",
                        "perf.TestableMain", *files, f"--pmcd-json={OUT}"],
                       capture_output=True, text=True, env=env, cwd=RUNNER, timeout=1800)
    if not OUT.exists():
        sys.stderr.write(r.stdout[-2000:] + r.stderr[-2000:])
        raise SystemExit("failed to produce the protocol document")
    return json.loads(OUT.read_text())


def tags(node, out: Counter) -> None:
    """Every `_type` discriminator anywhere in the document."""
    if isinstance(node, dict):
        t = node.get("_type")
        if isinstance(t, str):
            out[t] += 1
        for v in node.values():
            tags(v, out)
    elif isinstance(node, list):
        for v in node:
            tags(v, out)


def roster() -> dict[str, str]:
    if not ROSTER.exists():
        raise SystemExit(f"no roster at {ROSTER}; run scripts/census_gate.py snapshot")
    out = {}
    for line in ROSTER.read_text().splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            out[parts[0]] = parts[1]
    return out


BASELINE = REPO / "docs" / "corpus-coverage.json"


def snapshot(in_roster: dict, unknown: dict, known: dict) -> dict:
    return {"roster_total": len(known),
            "covered_count": len(in_roster),
            "covered": sorted(in_roster),
            "emitted_not_in_roster": sorted(unknown)}


def main() -> None:
    doc = compile_pmcd()
    used = Counter()
    tags(doc, used)
    known = roster()

    in_roster = {t: n for t, n in used.items() if t in known}
    unknown = {t: n for t, n in used.items() if t not in known}

    print(f"protocol tags exercised by the stress corpus: {len(in_roster)} "
          f"of {len(known)} in the roster")
    if unknown:
        print(f"  ({len(unknown)} tags emitted that are not in the roster: "
              f"{', '.join(sorted(unknown))})")
    print()
    for t, n in sorted(in_roster.items(), key=lambda kv: -kv[1]):
        print(f"  {n:>6}  {t}")

    if "--snapshot" in sys.argv:
        BASELINE.write_text(json.dumps(snapshot(in_roster, unknown, known), indent=1) + "\n")
        print(f"\nbaseline written: {BASELINE.relative_to(REPO)}")

    if "--check" in sys.argv:
        if not BASELINE.exists():
            raise SystemExit("no baseline; run coverage.py --snapshot")
        base = json.loads(BASELINE.read_text())
        lost = sorted(set(base["covered"]) - set(in_roster))
        gained = sorted(set(in_roster) - set(base["covered"]))
        print(f"\nbaseline {base['covered_count']} -> current {len(in_roster)}")
        for g in gained:
            print("   +", g)
        if lost:
            print(f"\nREGRESSION -- {len(lost)} tags no longer exercised:")
            for l in lost:
                print("   -", l)
            raise SystemExit(1)
        print("no coverage lost")

    if "--gaps" in sys.argv:
        missing = sorted(set(known) - set(used))
        by_pkg = {}
        for t in missing:
            pkg = known[t].rsplit(".", 1)[0] if "." in known[t] else "?"
            by_pkg.setdefault(pkg, []).append(t)
        print(f"\nroster tags NOT exercised here: {len(missing)}")
        for pkg, ts in sorted(by_pkg.items(), key=lambda kv: -len(kv[1]))[:12]:
            print(f"  [{len(ts):3d}] {pkg.split('.')[-2:] and '.'.join(pkg.split('.')[-2:])}")
            print(f"        {', '.join(ts[:14])}{' ...' if len(ts) > 14 else ''}")


if __name__ == "__main__":
    main()
