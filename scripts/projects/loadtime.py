"""How parse and compile time grow with the project graph.

The second reason for a dependency graph, after finding references that break across a
boundary, is to watch what depth costs. A model twice the size is expected to take longer;
what is worth knowing is whether a model at twice the DEPTH does, holding size roughly
constant, and whether the cost of compiling N projects together is the sum of compiling them
apart or something worse.

Three series, each measured by re-running the real compiler rather than by estimating:

  BY LAYER      L0 alone, then L0+L1, then L0+L1+L2, ... -- depth grows, size grows with it
  BY CLOSURE    each project with exactly its dependency closure, plotted against closure
                size -- this is the number a developer actually waits for
  INCREMENTAL   one project at a time added to a fixed base, to separate per-project cost
                from whole-graph cost

Every measurement is a fresh JVM, because a warm one reports a different number and the
question is what a cold build costs.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "corpus"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check
import run as runner
import spec

ROOT = Path(__file__).resolve().parents[2] / "projects"


def measure(names) -> tuple[float, float, int, int]:
    """(parse ms, compile ms, files, bytes) for one set of projects."""
    files = check.files_for(names)
    size = sum(Path(f).stat().st_size for f in files)
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", *files],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    m = re.search(r"parse\s+([\d.]+)\s*ms\s+compile\s+([\d.]+)\s*ms", out)
    if not m:
        err = re.search(r"(?:EngineException|Exception)[:\s]+([^\n]{0,90})", out)
        raise SystemExit(f"no timing for {len(names)} projects: "
                         f"{err.group(1).strip() if err else out[-300:]}")
    return float(m.group(1)), float(m.group(2)), len(files), size


def built() -> list[str]:
    """Projects with at least one .pure file, and whose whole closure has one too.

    A directory that exists but is empty is a project an agent is still writing. Including it
    fails the measurement with "has no .pure files" halfway through a series, which reads as
    a graph problem rather than a timing run that started too early.
    """
    have = {p.name for p in ROOT.iterdir() if p.is_dir() and any(p.glob("*.pure"))}
    return [p[0] for p in spec.PROJECTS
            if p[0] in have and spec.transitive(p[0]) <= have]


def main() -> None:
    names = built()
    by_layer = {}
    for n in names:
        by_layer.setdefault(spec.lookup(n)[1], []).append(n)

    print(f"{len(names)} projects built\n")
    print("BY LAYER -- cumulative, so each row is everything up to that depth")
    print(f"  {'through':<10}{'projects':>9}{'files':>7}{'KB':>8}"
          f"{'parse ms':>10}{'compile ms':>12}")
    acc = []
    for layer in sorted(by_layer):
        acc += by_layer[layer]
        p, c, f, b = measure(acc)
        print(f"  L{layer:<9}{len(acc):>9}{f:>7}{b // 1024:>8}{p:>10.0f}{c:>12.0f}")

    print("\nBY CLOSURE -- one project with exactly its dependencies, deepest last")
    print(f"  {'project':<24}{'closure':>8}{'files':>7}{'KB':>8}"
          f"{'parse ms':>10}{'compile ms':>12}")
    ranked = sorted(names, key=lambda n: (len(spec.transitive(n)), n))
    for n in ranked[::max(1, len(ranked) // 8)] + ranked[-1:]:
        closure = spec.transitive(n) | {n}
        p, c, f, b = measure(closure)
        print(f"  {n:<24}{len(closure):>8}{f:>7}{b // 1024:>8}{p:>10.0f}{c:>12.0f}")

    print("\nINCREMENTAL -- what the Nth project costs on top of the previous N-1")
    print(f"  {'projects':>9}{'KB':>8}{'parse ms':>10}{'compile ms':>12}{'d compile':>11}")
    prev = None
    step = max(1, len(names) // 6)
    for i in range(step, len(names) + 1, step):
        p, c, f, b = measure(names[:i])
        delta = "" if prev is None else f"{c - prev:>+11.0f}"
        print(f"  {i:>9}{b // 1024:>8}{p:>10.0f}{c:>12.0f}{delta}")
        prev = c


if __name__ == "__main__":
    main()
