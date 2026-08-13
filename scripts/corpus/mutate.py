"""
Mutation testing — proves the corpus can detect a wrong answer.

A green suite is not evidence. The previous corpus was 10,800 cases, all green, and could
not have detected any defect because nothing it asserted depended on the data. The only
way to know an assertion is load-bearing is to break something and watch it fail.

Each mutation below perturbs exactly one thing — a data value, a foreign key, a row, an
expectation — and the suite MUST go red. A mutation that survives is reported as SURVIVED
and is a hole in the corpus, not a curiosity.

The mutations are chosen to target the specific semantics the corpus claims to pin:

  null_to_value      an expected null becomes a value  -> the NULL assertions are real
  drop_row           one expected row is deleted       -> row COUNT is asserted
  perturb_float      190500.0 -> 190500.01             -> numeric equality is exact
  heal_orphan        CP-NONE -> CP-0001 in the seed    -> the LEFT OUTER JOIN semantic is
                                                          pinned; an inner join would have
                                                          produced this shape all along
  break_fk           a valid FK is dangled             -> navigation is really traversed
  widen_filter       'EXECUTE' -> 'EXECUTED' in seed   -> Q10's filter boundary is pinned
  swap_alias         two column aliases exchanged      -> columns are bound to the right
                                                          values, not merely present

Usage:  python3 scripts/corpus/mutate.py [--only <name>]
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from model import STRESS
from quarantine import QUARANTINE

REPO = STRESS.parents[4]
RUNNER = REPO / "tools" / "engine-runner"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))


def _sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    new, n = re.subn(pattern, repl, text, count=1)
    if n != 1:
        raise SystemExit(f"mutation {label!r} did not apply: {pattern!r} not found")
    return new


# Each mutation takes {filename: text} and returns the mutated mapping.
def null_to_value(f):
    f["92"] = _sub_once(f["92"], r'"cptyName":null', '"cptyName":"Meridian Asset Management"',
                        "null_to_value")
    return f


def drop_row(f):
    t = f["92"]
    i = t.index("data: '[")
    j = t.index("},{", i)
    return {**f, "92": t[:i + len("data: '[")] + t[j + 3:]}


def perturb_float(f):
    f["92"] = _sub_once(f["92"], r'"notional":190500\.0', '"notional":190500.01',
                        "perturb_float")
    return f


def heal_orphan(f):
    f["93"] = _sub_once(f["93"], r"CP-NONE", "CP-0001", "heal_orphan")
    return f


def break_fk(f):
    # The FK columns sit at the end of the TRADE row in declared order:
    # ...,BOOK_ID,COUNTERPARTY_ID,INSTRUMENT_ID,TRADER_ID
    f["93"] = _sub_once(f["93"], r",INST-MSFT,TRD-002", ",INST-GONE,TRD-002", "break_fk")
    return f


def widen_filter(f):
    f["93"] = _sub_once(f["93"], r",EXECUTE,", ",EXECUTED,", "widen_filter")
    return f


def break_enum_mapping(f):
    """Send the legacy 'BOT' code to the wrong enum value. The DATA is untouched, so only
    a working EnumerationMapping can notice."""
    f["36"] = _sub_once(f["36"], r"BUY: \['B', 'BOT'\],\n        SELL: \['S'\]",
                        "BUY: ['B'],\n        SELL: ['S', 'BOT']", "break_enum_mapping")
    return f


def unmapped_enum_code(f):
    """A source code with no entry in the EnumerationMapping. Legend's behaviour here is
    not established -- this mutation exists to show the corpus notices, not to assert what
    the engine should do."""
    f["93"] = _sub_once(f["93"], r",BOT,", ",XX,", "unmapped_enum_code")
    return f


def swap_alias(f):
    f["92"] = _sub_once(f["92"], r'"cptyName":"Meridian Asset Management","cptyLei":"5493001KJTIIGC8Y1R12"',
                        '"cptyName":"5493001KJTIIGC8Y1R12","cptyLei":"Meridian Asset Management"',
                        "swap_alias")
    return f


MUTATIONS = {
    "null_to_value": null_to_value,
    "drop_row": drop_row,
    "perturb_float": perturb_float,
    "heal_orphan": heal_orphan,
    "break_fk": break_fk,
    "widen_filter": widen_filter,
    "swap_alias": swap_alias,
    "break_enum_mapping": break_enum_mapping,
    "unmapped_enum_code": unmapped_enum_code,
}


def run(work: Path) -> tuple[int, int, str]:
    """(passed, failed, tail) for the corpus in `work`."""
    files = sorted(str(p) for p in work.glob("*.pure"))
    # Both service files: a mutation to the seed can just as easily be caught by the
    # fan-out battery, and excluding it would understate the corpus's sensitivity.
    testables = [f"--testable={m.group(1)}"
                 for f in sorted(work.glob("9[24]-*.pure"))
                 for m in re.finditer(r"^Service (\S+)", f.read_text(), re.M)]
    cp = (RUNNER / "cp.txt").read_text().strip()
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, PATH=f"{JAVA_HOME}/bin:" + os.environ["PATH"])
    r = subprocess.run([f"{JAVA_HOME}/bin/java", "-cp", f"{RUNNER}/target/classes:{cp}",
                        "perf.TestableMain", *files, *testables],
                       capture_output=True, text=True, env=env, cwd=RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    m = re.search(r"(\d+) passed, (\d+) failed, (\d+) errored", out)
    if not m:
        return 0, -1, out[-400:]
    return int(m.group(1)), int(m.group(2)) + int(m.group(3)), ""


def main() -> None:
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    names = [only] if only else list(MUTATIONS)

    print("baseline (unmutated):", end=" ", flush=True)
    with tempfile.TemporaryDirectory() as d:
        work = Path(d)
        for p in STRESS.glob("*.pure"):
            shutil.copy(p, work)
        p0, f0, tail = run(work)
        print(f"{p0} passed, {f0} failed (of which {len(QUARANTINE)} quarantined) {tail}")
        if f0 != len(QUARANTINE):
            raise SystemExit(
                f"baseline has {f0} failures but {len(QUARANTINE)} are quarantined; "
                f"run scripts/corpus/run.py and reconcile before mutating")

    caught = []
    for name in names:
        with tempfile.TemporaryDirectory() as d:
            work = Path(d)
            for p in STRESS.glob("*.pure"):
                shutil.copy(p, work)
            paths = {"92": work / "92-services.pure",
                     "93": work / "93-testdata.pure",
                     "36": work / "36-trading-store.pure"}
            src = {k: v.read_text() for k, v in paths.items()}
            out = MUTATIONS[name](dict(src))
            for k, path in paths.items():
                if out[k] != src[k]:
                    path.write_text(out[k])
            p1, f1, tail = run(work)
            # A mutation must cause failures BEYOND the quarantined ones. Comparing
            # against zero would let every mutation "pass" on the back of the six
            # known-failing fan-out services.
            ok = f1 > f0
            caught.append(ok)
            print(f"  {name:<16} {p1:>2} passed {f1:>2} failed   "
                  f"{'CAUGHT' if ok else 'SURVIVED -- assertion is not load-bearing'}{tail}")

    print(f"\n{sum(caught)}/{len(caught)} mutations caught")
    if not all(caught):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
