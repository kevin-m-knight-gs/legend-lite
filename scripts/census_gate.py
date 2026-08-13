#!/usr/bin/env python3
"""
Census gate — coverage is measured, not claimed.

Runs the three coverage probes, normalises their output into a single snapshot, and
diffs it against a committed baseline. The point is that a corpus change either moves a
counter or it does not; "we added 200 tests" is not evidence of anything on its own.

  axis A  protocol type roster     ZFullRosterCensusProbe   -> target/protocol-roster.txt
  axis B  grammar keyword roster   ZKeywordCoverageProbe
  axis C  PMCD reachability        ZPmcdReachabilityProbe

Usage
  scripts/census_gate.py snapshot          run the probes, write docs/census-baseline.json
  scripts/census_gate.py check             run the probes, diff against the baseline
  scripts/census_gate.py check --strict    as above, but exit 1 unless coverage improved
  scripts/census_gate.py worklist          print the in-scope uncovered tags, grouped

Notes
  - ZKeywordCoverageProbe has no default for -Dlegend.engine.root and NPEs without it;
    this script always passes it.
  - The baseline is committed so the delta is reviewable in a PR.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TARGET = REPO / "parser-equivalence" / "target"
CENSUS = TARGET / "census"
BASELINE = REPO / "docs" / "census-baseline.json"

ENGINE_ROOT = os.environ.get(
    "LEGEND_ENGINE_ROOT", str(Path.home() / "legend" / "legend-engine"))
JAVA_HOME = os.environ.get(
    "JAVA_HOME", str(Path.home() / "jdk" / "jdk-21.0.11+10" / "Contents" / "Home"))
MVN = os.environ.get("MVN", str(Path.home() / "jdk" / "apache-maven-3.9.9" / "bin" / "mvn"))

# These were Z*Probe scratch files until 3349061f deleted every Z* probe as dead scratch
# — a correct cleanup that happened to remove the three this gate depends on, leaving the
# committed baseline unreproducible. They are renamed rather than restored under the old
# names: a file that backs a committed baseline is infrastructure, and naming it as scratch
# is what made it deletable.
PROBES = {
    "roster": "ProtocolRosterCensusTest",
    "keywords": "GrammarKeywordCensusTest",
    "reach": "PmcdReachabilityCensusTest",
}


def run_probe(name, cls):
    CENSUS.mkdir(parents=True, exist_ok=True)
    out = CENSUS / f"{name}.raw"
    env = dict(os.environ, JAVA_HOME=JAVA_HOME,
               PATH=f"{JAVA_HOME}/bin:" + os.environ.get("PATH", ""))
    cmd = [MVN, "-q", "-o", "-pl", "parser-equivalence", "test",
           f"-Dtest={cls}", "-DfailIfNoSpecifiedTests=false",
           f"-Dlegend.engine.root={ENGINE_ROOT}"]
    r = subprocess.run(cmd, cwd=REPO, env=env, capture_output=True, text=True, timeout=3600)
    out.write_text(r.stdout + r.stderr)
    if r.returncode != 0:
        sys.stderr.write(f"probe {cls} failed (exit {r.returncode}); see {out}\n")
        sys.stderr.write("\n".join(
            l for l in (r.stdout + r.stderr).splitlines() if "ERROR" in l)[:1200] + "\n")
        raise SystemExit(2)
    return out


def parse_roster():
    """tag \t class \t COVERED|UNCOVERED"""
    path = TARGET / "protocol-roster.txt"
    covered, uncovered, owner = set(), set(), {}
    for line in path.read_text().splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        tag, cls, state = parts[0], parts[1], parts[2].strip()
        owner[tag] = cls
        (covered if state == "COVERED" else uncovered).add(tag)
    return covered, uncovered, owner


def parse_reach(raw):
    """@@ IN [package] tag, tag, ...  — the in-scope uncovered worklist."""
    in_scope, by_pkg = set(), defaultdict(list)
    classes = None
    for line in raw.read_text().splitlines():
        m = re.match(r"@@ reachable protocol classes: (\d+)", line)
        if m:
            classes = int(m.group(1))
        m = re.match(r"@@ IN \[([^\]]*)\] (.*)", line)
        if m:
            pkg = m.group(1)
            for tag in (t.strip() for t in m.group(2).split(",")):
                if tag:
                    in_scope.add(tag)
                    by_pkg[pkg].append(tag)
    return in_scope, dict(by_pkg), classes


def parse_keywords(raw):
    total = covered = None
    uncovered = []
    for line in raw.read_text().splitlines():
        m = re.search(r"keywords harvested: (\d+)", line)
        if m:
            total = int(m.group(1))
        m = re.search(r"keywords covered: (\d+)/(\d+)", line)
        if m:
            covered, total = int(m.group(1)), int(m.group(2))
        m = re.match(r"@@ +UNCOVERED +(\S+)", line)
        if m:
            uncovered.append(m.group(1))
    return total, covered, uncovered


def collect():
    for name, cls in PROBES.items():
        run_probe(name, cls)
    covered, uncovered, owner = parse_roster()
    in_scope, by_pkg, reach_classes = parse_reach(CENSUS / "reach.raw")
    kw_total, kw_covered, kw_uncovered = parse_keywords(CENSUS / "keywords.raw")
    return {
        "roster_total": len(covered) + len(uncovered),
        "roster_covered": sorted(covered),
        "roster_uncovered_in_scope": sorted(in_scope),
        "roster_uncovered_out_of_scope": len(uncovered) - len(in_scope),
        "reachable_classes": reach_classes,
        "keywords_total": kw_total,
        "keywords_covered_count": kw_covered,
        "keywords_uncovered": sorted(kw_uncovered),
        "worklist_by_package": {k: sorted(v) for k, v in sorted(by_pkg.items())},
    }


def summarise(s):
    return (f"protocol tags {len(s['roster_covered'])}/{s['roster_total']} covered · "
            f"in-scope worklist {len(s['roster_uncovered_in_scope'])} · "
            f"out of scope {s['roster_uncovered_out_of_scope']} · "
            f"keywords {s['keywords_covered_count']}/{s['keywords_total']} · "
            f"reachable classes {s['reachable_classes']}")


def cmd_snapshot(_):
    s = collect()
    BASELINE.write_text(json.dumps(s, indent=1) + "\n")
    print(summarise(s))
    print(f"baseline written: {BASELINE.relative_to(REPO)}")


def cmd_check(args):
    if not BASELINE.exists():
        raise SystemExit("no baseline; run: scripts/census_gate.py snapshot")
    base = json.loads(BASELINE.read_text())
    now = collect()
    print("baseline:", summarise(base))
    print("current :", summarise(now))

    gained = sorted(set(now["roster_covered"]) - set(base["roster_covered"]))
    lost = sorted(set(base["roster_covered"]) - set(now["roster_covered"]))
    kw_gained = sorted(set(base["keywords_uncovered"]) - set(now["keywords_uncovered"]))
    kw_lost = sorted(set(now["keywords_uncovered"]) - set(base["keywords_uncovered"]))

    if gained:
        print(f"\n+{len(gained)} protocol tags newly covered:")
        for t in gained:
            print("   +", t)
    if kw_gained:
        print(f"\n+{len(kw_gained)} keywords newly covered:")
        for t in kw_gained:
            print("   +", t)
    if lost or kw_lost:
        print(f"\nREGRESSION — coverage lost: {len(lost)} tags, {len(kw_lost)} keywords")
        for t in lost + kw_lost:
            print("   -", t)
        raise SystemExit(1)
    if not gained and not kw_gained:
        print("\nno coverage change")
        if args.strict:
            raise SystemExit("--strict: this change moved no census counter")
    print("\nok")


def cmd_worklist(_):
    s = json.loads(BASELINE.read_text()) if BASELINE.exists() else collect()
    total = len(s["roster_uncovered_in_scope"])
    print(f"in-scope uncovered protocol tags: {total}\n")
    for pkg, tags in s["worklist_by_package"].items():
        print(f"[{len(tags):3d}] {pkg}")
        print("      " + ", ".join(tags))
    if s["keywords_uncovered"]:
        print(f"\nuncovered keywords: {len(s['keywords_uncovered'])}")
        print("  " + ", ".join(s["keywords_uncovered"]))


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("snapshot").set_defaults(fn=cmd_snapshot)
    c = sub.add_parser("check")
    c.add_argument("--strict", action="store_true",
                   help="exit 1 unless coverage improved")
    c.set_defaults(fn=cmd_check)
    sub.add_parser("worklist").set_defaults(fn=cmd_worklist)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
