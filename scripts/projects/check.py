"""Compile the project graph: each project with its dependencies, then all of them together.

Two questions, and they are different:

  ALONE   does a project compile with exactly its declared dependency closure? A project that
          only compiles when some unrelated project happens to be present has an undeclared
          dependency, which is the defect this whole exercise exists to find.

  TOGETHER does the whole graph compile at once? Name collisions between projects only appear
          here -- two projects may each be fine and still declare the same table.

Both report PARSE and COMPILE milliseconds, because the second reason for a project graph is
to watch those numbers grow with depth rather than only with size.

    python3 scripts/projects/check.py            # every project alone, then the graph
    python3 scripts/projects/check.py NAME ...   # just these, alone
    python3 scripts/projects/check.py --graph    # just the whole graph
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "corpus"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as runner
import spec

ROOT = Path(__file__).resolve().parents[2] / "projects"


def files_for(names) -> list[str]:
    """The .pure files of `names`, dependencies first.

    Order matters: a file with no ###Section header inherits the previous file's section, so
    a dependency emitted after its dependent would be parsed under the wrong grammar. Sorted
    by layer, then by name, which is a topological order because a project may only depend on
    a strictly lower layer.
    """
    out = []
    for n in sorted(names, key=lambda n: (spec.lookup(n)[1], n)):
        d = ROOT / n
        if not d.is_dir():
            raise SystemExit(f"project {n} has no directory at {d}")
        fs = sorted(d.glob("*.pure"))
        if not fs:
            raise SystemExit(f"project {n} has no .pure files")
        out += [str(f) for f in fs]
    return out


def substance(names) -> list[str]:
    """Projects that compile but are not the project they were meant to be.

    An agent that dies mid-task can leave its PROBE scaffold behind -- three files, two
    classes, no MANIFEST -- and that scaffold compiles perfectly. `check.py` said `compiles`
    and the graph looked complete; only counting the classes showed a 64-line placeholder
    where a 30-class project was specified.

    So the size band in the matrix is checked, not merely intended. The bands are wide and
    the check is a floor rather than a target: it exists to catch an empty project, not to
    police how many classes a domain needs.
    """
    out = []
    for n in sorted(names):
        d = ROOT / n
        if not d.is_dir():
            continue
        src = "".join(f.read_text() for f in d.glob("*.pure"))
        classes = len(re.findall(r"^Class\s", src, re.M))
        low, _high = spec.SIZES[spec.lookup(n)[5]]
        if classes < low:
            out.append(f"{n}: {classes} classes, band '{spec.lookup(n)[5]}' wants >= {low}")
        if not (d / "MANIFEST.md").is_file():
            out.append(f"{n}: no MANIFEST.md, so nothing downstream can be written against it")
    return out


def compile_set(names, label: str) -> tuple[bool, str]:
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", *files_for(names)],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    err = re.search(r"(?:EngineException|Exception)[:\s]+([^\n]{0,140})", out)
    timing = re.search(r"parse\s+([\d.]+)\s*ms\s+compile\s+([\d.]+)\s*ms", out)
    ms = (f"parse {timing.group(1):>8}ms  compile {timing.group(2):>9}ms"
          if timing else " " * 34)
    if err:
        return False, f"  FAILS     {label:<26}{err.group(1).strip()}"
    return True, f"  compiles  {label:<26}{ms}"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    graph_only = "--graph" in sys.argv
    names = args or [p[0] for p in spec.PROJECTS]
    bad = 0

    if not graph_only:
        for n in sorted(names, key=lambda n: (spec.lookup(n)[1], n)):
            closure = spec.transitive(n) | {n}
            ok, line = compile_set(closure, f"{n} (+{len(closure) - 1} deps)")
            bad += not ok
            print(line)

    if not args:
        print()
        ok, line = compile_set([p[0] for p in spec.PROJECTS],
                               f"ALL {len(spec.PROJECTS)} together")
        bad += not ok
        print(line)

    thin = substance(names)
    if thin:
        print("\nCOMPILES BUT IS NOT FINISHED:")
        for line in thin:
            print(f"  {line}")
        bad += len(thin)

    if bad:
        raise SystemExit(f"\n{bad} project(s) do not compile or are unfinished")


if __name__ == "__main__":
    main()
