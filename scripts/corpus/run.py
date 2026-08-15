"""
Runs the whole asserted corpus through legend-engine and adjudicates the result.

  python3 scripts/corpus/run.py [-v]

Exit status is 0 only when every case lands where it is supposed to:

  PASS        not quarantined, passed          — as intended
  KNOWN-FAIL  quarantined, failed              — as intended; the defect is still there
  REGRESSION  not quarantined, failed          — exit 1
  FIXED       quarantined, PASSED              — exit 1; delete it from quarantine.py
                                                 and from docs/UPSTREAM_FINDINGS.md

The FIXED case is an error on purpose. A quarantine that silently keeps passing is how a
suite stops meaning anything: the entry rots, nobody notices the upstream fix, and the
next real failure in that area is assumed to be the old known one.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

from model import STRESS
import functest
from quarantine import HANGS, QUARANTINE

REPO = STRESS.parents[4]
RUNNER = REPO / "tools" / "engine-runner"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))


def testables() -> list[str]:
    """Services, plus the FUNCTION testables — which are addressed by a mangled id
    encoding the signature, not by the function's path."""
    out = []
    # 92 hand-written, 94 generated fan-out, 97 the hier:: feature domain. A GLOB rather
    # than a list, and it has already been wrong once: it read "9[24]-*" while the hier
    # services lived in 97, so two services passed when run by hand and were simply absent
    # from the suite -- the total stayed at 182 and nothing said why.
    for f in sorted(STRESS.glob("9[2478]-*.pure")):
        out += re.findall(r"^Service (\S+)", f.read_text(), re.M)
    # Hanging cases are excluded from execution but still reported, so they cannot be
    # forgotten -- and so one non-returning test cannot block the whole suite.
    return [n for n in out if n not in HANGS] + functest.testables()


# Testables per JVM, and the ceiling is a CONNECTION POOL, not a guess.
#
# At 182 testables in one process the run reported twelve ERRORs -- every service after a
# point -- and the services were fine: each passed alone, and all nineteen of the new ones
# passed together as a group. The engine's own message says what actually happened:
#
#   DBPool_DuckDB_... - Connection is not available, request timed out after 30001ms
#   (total=100...)
#
# The pool holds 100 connections and a full suite drains it, after which every remaining
# test blocks for the 30-second timeout and fails. That is also where the runtime went:
# twelve tests waiting out 30s each is six minutes of doing nothing.
#
# So BATCH must stay comfortably under the pool size -- 40 leaves room for the connections
# a single batch holds concurrently, and puts a 182-test suite at five processes. Raising it
# past ~90 would reintroduce the same failure with the same misleading shape.
BATCH = 40


def main() -> None:
    names = testables()
    cp = (RUNNER / "cp.txt").read_text().strip()
    if not (RUNNER / "target" / "classes").is_dir():
        raise SystemExit(f"runner not built; see {RUNNER}/README.md")
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:" + os.environ["PATH"])
    # BATCHED, one JVM per chunk. Running every testable in a single process stopped
    # working somewhere past ~170: the corpus grew to 182 and twelve services reported
    # ERROR that pass individually AND pass as a group of nineteen. The failures were
    # everything after a point, which is the same shape as the ClassCastException that
    # once killed a run mid-way -- state accumulating in one JVM, not a defect in the
    # services.
    #
    # Chunking also bounds the blast radius: whatever exhausts a process now takes one
    # batch with it instead of the tail of the suite, and the batch that broke is named.
    files = sorted(str(p) for p in STRESS.glob("*.pure"))
    chunks = [names[i:i + BATCH] for i in range(0, len(names), BATCH)] or [[]]
    parts = []
    for chunk in chunks:
        r = subprocess.run(
            [f"{JAVA_HOME}/bin/java", "-cp", f"{RUNNER}/target/classes:{cp}",
             "perf.TestableMain", *files, *(f"--testable={n}" for n in chunk)],
            capture_output=True, text=True, env=env, cwd=RUNNER, timeout=3600)
        parts.append(r.stdout + r.stderr)
    out = "\n".join(parts)
    if "-v" in sys.argv:
        print(out)

    # ERROR is a third outcome, not an absence. A service that throws produces no
    # PASS/FAIL line, and treating that as MISSING reported a correctly-quarantined
    # failure as an unexpected result.
    results = {m.group(2): m.group(1) for m in
               re.finditer(r"^(PASS|FAIL|ERROR)\s+(\S+?)_suite / ", out, re.M)}
    # Function tests report as "<suiteId> / <testId>" with no _suite marker, so each
    # atomic test is its own line. A function testable counts as passing only if EVERY
    # one of its tests passed.
    fn_results = {}
    for m in re.finditer(r"^(PASS|FAIL|ERROR)\s+(\S+) / (\S+)\s*$", out, re.M):
        if m.group(2).endswith("_suite"):
            continue
        fn_results.setdefault(m.group(2), []).append(m.group(1))
    if not results:
        print(out[-3000:])
        raise SystemExit("no results parsed; the model probably failed to compile")

    verdicts, bad = [], 0
    for name, (fid, why) in sorted(HANGS.items()):
        verdicts.append((name.split("::")[-1], f"NOT RUN {fid}", why))
    for name in sorted(names, key=lambda n: (n not in QUARANTINE, n)):
        short = name.split("::")[-1]
        passed = results.get(short)
        if passed is None and fn_results:
            # match a function testable by its suite id
            for suite, states in fn_results.items():
                if suite in ("testSuite_1",) and name in functest.testables():
                    passed = "PASS" if all(s == "PASS" for s in states) else "FAIL"
                    break
        q = QUARANTINE.get(name)
        if passed is None:
            verdict, wrong = "MISSING", True
        elif q and passed in ("FAIL", "ERROR"):
            verdict, wrong = f"KNOWN-FAIL {q[0]}", False
        elif q:
            verdict, wrong = f"FIXED {q[0]} -- remove from quarantine.py", True
        elif passed == "PASS":
            verdict, wrong = "PASS", False
        else:
            verdict, wrong = ("REGRESSION" if passed == "FAIL" else "ERROR"), True
        bad += wrong
        verdicts.append((short, verdict, q[1] if q else ""))

    width = max(len(v[0]) for v in verdicts)
    for short, verdict, why in verdicts:
        print(f"  {short:<{width}}  {verdict:<34}{why}")

    n_hang = sum(1 for _, v, _ in verdicts if v.startswith("NOT RUN"))
    n_known = sum(1 for _, v, _ in verdicts if v.startswith("KNOWN-FAIL"))
    n_pass = sum(1 for _, v, _ in verdicts if v == "PASS")
    print(f"\n{n_pass} passed, {n_known} known-fail (quarantined), "
          f"{n_hang} not run (hangs), {bad} unexpected, {len(verdicts)} total")
    if bad:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
