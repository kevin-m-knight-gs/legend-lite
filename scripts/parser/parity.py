"""
Runs the whole fixture corpus through BOTH parsers and reports where they disagree.

This is what the corpus was built for. Positives, negatives and mutants were all authored
against legend-engine and are silent about legend-lite; pointing them at legend-lite turns
every one of them into a parity assertion.

Three populations, three different meanings when they disagree:

  POSITIVES   engine accepts. lite rejecting one is a MISSING CONSTRUCT -- surface the
              rewrite has not implemented. The most actionable result here.

  NEGATIVES   engine rejects. lite accepting one is OVER-PERMISSIVENESS -- it would load a
              model the reference refuses. Quieter and more dangerous: nothing fails until
              the model reaches an engine that cares.

  MUTANTS     engine's verdict was recorded, not judged. A disagreement is a divergence to
              explain in either direction; see PERMISSIVENESS.md for why "lite is stricter"
              is not automatically the safe side.

Message text is NOT compared. Two parsers can refuse the same input for the same reason and
word it differently, and demanding identical prose would drown the signal in noise --
message parity is a separate question with its own harness (parser-equivalence's
MessageParityTest). What is compared is the verdict.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
RUNNER = REPO / "tools" / "engine-runner"
CORE = REPO / "core" / "target" / "classes"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))
WORK = Path(os.environ.get("CLAUDE_JOB_DIR", "/tmp")) / "tmp" / "mutants"

QUARANTINE = HERE / "parity-quarantine.tsv"

# NOTE ON ORACLES. This drives tools/engine-runner, whose classpath carries every published
# extension. parser-equivalence's FixtureCorpusParityTest drives a deliberately
# PRODUCTION-SHAPED oracle and therefore reports slightly different numbers for the same
# corpus -- it rejects a few inputs this one accepts. Neither is wrong; a baseline just has
# to come from the environment that asserts it.


def _run(main: str, target: Path) -> dict[str, str]:
    """name -> "" when accepted, else the rejection message."""
    cp = f"{RUNNER}/target/classes:{CORE}:{(RUNNER / 'cp.txt').read_text().strip()}"
    r = subprocess.run([f"{JAVA_HOME}/bin/java", "-cp", cp, main, str(target)],
                       capture_output=True, text=True, timeout=3600)
    out = {}
    for line in r.stdout.splitlines():
        if not (line.startswith("ok ") or line.startswith("WRONG")):
            continue
        body = line[5:].strip()
        name, _, msg = body.partition("  REJECTED: ")
        out[name.strip()] = msg.strip()
    return out


def quarantined() -> dict[str, str]:
    """Known divergences, each with a reason. A quarantine entry is a promise that somebody
    looked, not a way to make the number go green."""
    if not QUARANTINE.is_file():
        return {}
    out = {}
    for line in QUARANTINE.read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        name, _, reason = line.partition("\t")
        out[name.strip()] = reason.strip()
    return out


def compare(label: str, target: Path, expect_engine_rejects: bool | None,
            skip_parents: set[str] | None = None):
    engine = _run("perf.ParseMain", target)
    lite = _run("perf.LiteParseMain", target)
    known = quarantined()

    # A mutant of a fixture legend-lite already cannot parse tells us nothing new -- the
    # rejection is the parent's, repeated. Counting them made the mutant divergence look
    # five times worse than it is, and every one of the top offenders was a mutant of the
    # same handful of unsupported datasource specifications.
    derivative = 0
    if skip_parents:
        for name in list(engine):
            parent = name.split("__")[0]
            if parent in skip_parents:
                engine.pop(name, None)
                lite.pop(name, None)
                derivative += 1

    both = sorted(set(engine) & set(lite))
    only_engine = sorted(set(engine) - set(lite))
    only_lite = sorted(set(lite) - set(engine))

    agree, lite_rejects, lite_accepts, quar = [], [], [], []
    for name in both:
        e_rej, l_rej = bool(engine[name]), bool(lite[name])
        if e_rej == l_rej:
            agree.append(name)
        elif name in known:
            quar.append(name)
        elif l_rej:
            lite_rejects.append((name, lite[name]))
        else:
            lite_accepts.append((name, engine[name]))

    total = len(both)
    print(f"\n=== {label}: {total} files"
          + (f"  ({derivative} derivative mutants excluded)" if skip_parents and derivative
             else ""))
    print(f"    agree                {len(agree):>5}"
          f"   ({len(agree) / total:.0%})" if total else "")
    if quar:
        print(f"    known divergence     {len(quar):>5}")
    if lite_rejects:
        print(f"    LITE REJECTS         {len(lite_rejects):>5}   "
              f"{'(missing construct)' if expect_engine_rejects is False else '(stricter)'}")
    if lite_accepts:
        print(f"    LITE ACCEPTS         {len(lite_accepts):>5}   "
              f"{'(over-permissive)' if expect_engine_rejects else '(more permissive)'}")
    if only_engine or only_lite:
        print(f"    NO VERDICT           engine-only {len(only_engine)}, lite-only {len(only_lite)}")

    return lite_rejects, lite_accepts


def main() -> None:
    if not CORE.is_dir():
        raise SystemExit(f"legend-lite not built: {CORE} missing")

    detail = "--detail" in sys.argv
    results = {}
    results["positive"] = compare("POSITIVES  (engine accepts all)",
                                  HERE / "fixtures", expect_engine_rejects=False)
    unsupported = {Path(n).stem for n, _ in results["positive"][0]}
    results["negative"] = compare("NEGATIVES  (engine rejects all)",
                                  HERE / "negative", expect_engine_rejects=True)
    if WORK.is_dir():
        results["mutant"] = compare("MUTANTS    (engine verdict recorded, not judged)",
                                    WORK, expect_engine_rejects=None,
                                    skip_parents=unsupported)
    else:
        print(f"\n(mutants not generated -- run mutants.py first; {WORK} missing)")

    if detail:
        for label, (rejects, accepts) in results.items():
            # No truncation. An earlier version capped at 40 per population, which quietly
            # hid 6 of the 46 over-permissive negatives and made a cross-environment diff
            # look like a real disagreement.
            for name, msg in rejects:
                print(f"LITE-REJECTS  {label:<9}  {name}  ->  {msg[:70]}")
            for name, msg in accepts:
                print(f"LITE-ACCEPTS  {label:<9}  {name}  ->  engine said: {msg[:56]}")


if __name__ == "__main__":
    main()
