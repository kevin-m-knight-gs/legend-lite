"""A class-typed property mapped over a join with NO target set id.

    market: [core_calendar::Store] @Cc_MarketCycle          -- core-calendar writes this
    bucket[ctnBucket]: [fee_core::Store]@Fee_ScheduleBucket -- fee-core writes this

Both compile. Navigating the first one fails at test-suite initialisation with

    meta::pure::router::store::routing::Void not supported!

which names no class, no property, no mapping and no file. Adding `[ccMarket]` makes it pass.

This is the failure that was recorded as a hop-count or schema problem, reverted, and left
unexplained. `probe_boundary_navigation.py` could not reproduce it across fourteen cases for
one reason: every property in it was written WITH a set id, because that is what the corpus
does. The absent thing was never the variable.

Two readings remain, and they are very different:

  REQUIRED    the target set id is mandatory for a property over a join. core-calendar is
              simply wrong, `projects/check.py` compiles it happily, and the defect is that
              nothing says so until execution.
  INFERENCE   it resolves fine on its own and only breaks once the mapping is INCLUDED in
              another, where the engine stops being able to infer the target set.

The second would be an engine defect and would mean every project in the graph is a hazard
the moment it is consumed. The first is an authoring rule with a missing diagnostic. This
tells them apart, and also asks whether the `*` root marker is what actually matters.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import probe_boundary_navigation as base
import run as runner

WITH_ID = "leafByProperty[upLeaf]: [up::DB]@Up_MidLeaf"
NO_ID = "leafByProperty: [up::DB]@Up_MidLeaf"
ROOT_MARKED = "*up::Leaf[upLeaf]: Relational"
PLAIN_ROOT = "up::Leaf[upLeaf]: Relational"

# (name, set id present, target set marked `*`, mapping the query resolves against)
CASES = [
    ("WithSetId_OwnMapping", True, False, "up::M"),
    ("WithSetId_IncludingMapping", True, False, "down::M"),
    ("NoSetId_OwnMapping", False, False, "up::M"),
    ("NoSetId_IncludingMapping", False, False, "down::M"),
    # Does the ROOT MARKER stand in for the set id? If these pass, the rule is "the target
    # class needs a root set", not "the property needs an id".
    ("NoSetId_RootMarked_OwnMapping", False, True, "up::M"),
    ("NoSetId_RootMarked_IncludingMapping", False, True, "down::M"),
]

SERVICE = """Service ms::S_{name}
{{
   pattern: '/ms/{name}';
   documentation: 'A property over a join with no target set id.';
   execution: Single
   {{
      query: |up::Mid.all()->project(~[id: x|$x.midId, leaf: x|$x.leafByProperty.leafName])
                           ->sort(~id->ascending());
      mapping: {mapping};
      runtime: {runtime};
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ down::Seed }}# ] ]
         tests:
         [
            expected_rows:
            {{
               serializationFormat: PURE_TDSOBJECT;
               asserts: [ a: EqualToJson #{{ expected: ExternalFormat #{{
                   contentType: 'application/json';
                   data: '[{{"id":"M1","leaf":"mid-one-leaf"}}]'; }}#; }}# ]
            }}
         ]
      }}
   ]
}}
"""

EXTRA_RUNTIME = ("###Runtime\nRuntime up::RT\n"
                 "{ mappings: [ up::M ]; connections: [ down::DB: [ env: down::Conn ] ]; }\n\n"
                 "###Service\n")


def main() -> None:
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    for name, has_id, root_marked, mapping in CASES:
        up = base.UPSTREAM
        if not has_id:
            up = up.replace(WITH_ID, NO_ID)
        if root_marked:
            up = up.replace(PLAIN_ROOT, ROOT_MARKED)
        runtime = "up::RT" if mapping == "up::M" else "down::RT"
        src = work / f"{name}.pure"
        src.write_text(up + base.DOWNSTREAM.replace("###Service\n", "") + EXTRA_RUNTIME
                       + SERVICE.format(name=name, mapping=mapping, runtime=runtime))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src),
             f"--testable=ms::S_{name}"],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=1800)
        out = r.stdout + r.stderr
        m = re.search(rf"(PASS|FAIL)\s+S_{name}_suite", out)
        if m:
            # FAIL means it ROUTED and merely disagreed with a hand-written expectation --
            # which is not what this probe is asking about.
            verdict, detail = "ROUTES", "" if m.group(1) == "PASS" else "(content differs)"
        elif "Void not supported" in out:
            verdict, detail = "VOID", "Void not supported!"
        else:
            err = re.search(r"(?:EngineException|Caused by)[:\s]+([^\n]{0,110})", out)
            verdict, detail = "OTHER", err.group(1).strip() if err else "no verdict"
        print(f"  {verdict:<8}{name:<38}{detail}")

    print(f"\n  sources kept at {work}")


if __name__ == "__main__":
    main()
