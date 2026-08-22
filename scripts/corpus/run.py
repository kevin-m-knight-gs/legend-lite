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
import model
from quarantine import HANGS, QUARANTINE

REPO = STRESS.parents[4]
RUNNER = REPO / "tools" / "engine-runner"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))


def testables() -> list[str]:
    """Services, plus the FUNCTION testables — which are addressed by a mangled id
    encoding the signature, not by the function's path."""
    out = []
    # EVERY source file, not a numbered subset. The glob has been wrong twice now for the
    # same reason: it read "9[24]-*" while the hier services lived in 97, and then
    # "9[2478]-*" while the surface-burndown services lived in 67 and 73. Both times the
    # services PARSED, COMPILED and were simply never run -- the total did not move and
    # nothing said why, which is the quietest way a test can fail to exist.
    #
    # Scanning everything costs a directory read and removes the whole class of mistake.
    for f in sorted(STRESS.glob("*.pure")):
        text = f.read_text()
        # A service with no testSuites is a DECLARATION, not a testable. Listing one makes
        # it report MISSING for ever -- the runner asks for a result the service was never
        # going to produce, which reads like a failure and is not one.
        for m in re.finditer(r"^Service (\S+)", text, re.M):
            body = text[m.end():]
            nxt = re.search(r"^(?:Service|ExecutionEnvironment|###)", body, re.M)
            if "testSuites" in (body[:nxt.start()] if nxt else body):
                out.append(m.group(1))
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
# a batch opens without exhausting it.
#
# A later hang was NOT this. A batch blocked at 0% CPU with its JVM's CPU time frozen, which
# looked exactly like pool exhaustion and was not: the batch contained a generated graph
# fetch over a combination-matrix class, which fails plan generation with "Only one return
# type should be selected during Serialization Class generation". Halving BATCH moved the
# hang later rather than removing it, which is what showed the batch size was innocent --
# a size limit that is genuinely a size limit does not survive being halved. The matrix
# domain is now excluded from the generators that rank over the trading model.
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
    # The LINKED PROJECTS first, then the corpus. A corpus store includes a project store
    # and a corpus mapping includes a project mapping, so the project's elements must be in
    # the source before the corpus's own files refer to them. Omitting them entirely fails
    # with a bare "Unexpected token" -- a parse error naming no file, because the corpus text
    # references a package the parser has never seen.
    files = ([str(f) for f in model.linked_files()]
             + sorted(str(p) for p in STRESS.glob("*.pure")))
    chunks = [names[i:i + BATCH] for i in range(0, len(names), BATCH)] or [[]]
    parts = []
    for chunk in chunks:
        # A batch that HANGS used to kill the whole run: subprocess.run raised
        # TimeoutExpired after an hour and 50 minutes of results went with it, naming
        # nothing. Now the batch is abandoned, its testables are reported, and the suite
        # carries on -- so a hang costs one batch instead of everything, and the batch that
        # hung is named rather than inferred.
        #
        # The timeout is per batch and generous: a batch of 40 takes about 35 seconds, so
        # 300 is two orders of magnitude of headroom and still fails fast.
        try:
            r = subprocess.run(
                [f"{JAVA_HOME}/bin/java", "-cp", f"{RUNNER}/target/classes:{cp}",
                 "perf.TestableMain", *files, *(f"--testable={n}" for n in chunk)],
                capture_output=True, text=True, env=env, cwd=RUNNER, timeout=300)
            parts.append(r.stdout + r.stderr)
        except subprocess.TimeoutExpired:
            print(f"BATCH HUNG after 300s, abandoned. Its {len(chunk)} testables:\n  "
                  + "\n  ".join(chunk), flush=True)
            parts.append("")
    out = "\n".join(parts)

    # A service that throws during test-suite INITIALISATION kills the JVM its batch runs in.
    # It does not report FAIL -- it reports nothing, and neither do the other services in the
    # batch, which surface as MISSING. Three times now a single such service has cost eighty
    # unrelated results, and each time the eighty looked like the problem.
    #
    # A quarantine entry cannot hold one of these: quarantine excuses a FAILURE, and there is
    # no failure to excuse. The service has to be absent from the corpus, so say that rather
    # than let the reader work backwards from a wall of MISSING.
    for m in re.finditer(r"Error initializing test suite session for '(\w+)_suite'", out):
        print(f"  FATAL AT INIT  {m.group(1)}: threw during test-suite initialisation, which "
              f"kills its whole batch.\n"
              f"                 Every MISSING below is collateral. Quarantine will not help "
              f"-- a quarantine excuses a failure and\n"
              f"                 this service never reports one. Remove it from the corpus "
              f"and pin the defect with a repro instead.")
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
