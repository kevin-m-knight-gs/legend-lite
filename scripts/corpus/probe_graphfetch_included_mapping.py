"""graphFetch of a sub-object whose set is mapped in an INCLUDED mapping.

Linking fee-core into the executable corpus produced four green services and one that never
ran at all:

    Error initializing test suite session for 'GG_FeeScheduleTree_suite'
    Caused by: Execution error at relationalGraphFetch.pure line:557 column:68,
      "Cast exception: RelationalPropertyMapping cannot be cast to XStorePropertyMapping"

The same property, over the same join, PROJECTS correctly -- D_FeeScheduleDense reads
`$x.bucket.ladder.name` two hops across the project boundary and passes. So this is not the
join, the range, or the boundary: it is graphFetch specifically.

`XStorePropertyMapping` is what Legend uses for a property whose two ends live in different
STORES. The property here is an ordinary `RelationalPropertyMapping` over a join, and
something on the graphFetch path has concluded it must be cross-store. The hypothesis worth
testing is that INCLUDING a mapping is what makes it look that way -- that the engine treats
"the target set was contributed by an included mapping" as "the target set is in another
store".

Each case is its own file and its own JVM, because the failure is at INITIALISATION: it
takes the whole file down before any service in it runs, so two cases in one file would
report one result between them.

What this probe asserts is whether the suite INITIALISES, which is the defect under test. A
case that initialises and then disagrees on content is reported separately as RAN-MISMATCH
and is not evidence either way -- the expectations here are hand-written for a five-row
model and are not the point.
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

# (name, root, graph tree). The tree is used for both graphFetch and serialize.
CASES = [
    # --- baselines: graphFetch that does NOT cross an included mapping ---
    # Root and sub-object are both mapped in up::M, and up::M includes nothing.
    ("SameMappingSubObject", "up::Mid", "up::Mid { midId, leafByProperty { leafName } }"),
    ("SameMappingSubObjectAssoc", "up::Mid", "up::Mid { midId, leafByAssoc { leafName } }"),
    # A root in the INCLUDING mapping with no sub-object at all: if this fails, the defect is
    # about the mapping and not about the edge.
    ("IncludingMappingNoSubObject", "down::Root", "down::Root { rootId }"),

    # --- the suspect: the sub-object's set was contributed by an INCLUDED mapping ---
    ("IncludedSubObjectProperty", "down::Root",
     "down::Root { rootId, midByProperty { midName } }"),
    ("IncludedSubObjectAssoc", "down::Root",
     "down::Root { rootId, midByAssoc { midName } }"),
    ("IncludedSubObjectTwoHops", "down::Root",
     "down::Root { rootId, midByProperty { midName, leafByProperty { leafName } } }"),

    # --- both ends inside the included mapping, reached THROUGH the including one ---
    # Same tree as SameMappingSubObject, but resolved against down::M. If this fails and the
    # baseline passes, it is the including mapping alone -- no cross-mapping edge needed.
    ("IncludedRootAndSubObject", "up::Mid", "up::Mid { midId, leafByProperty { leafName } }"),
]

# The mapping each case resolves against. IncludedRootAndSubObject is the one that differs.
MAPPING = {"SameMappingSubObject": "up::M", "SameMappingSubObjectAssoc": "up::M"}

SERVICE = """Service gf::S_{name}
{{
   pattern: '/gf/{name}';
   documentation: 'graphFetch across an included mapping.';
   execution: Single
   {{
      query: |{root}.all()->graphFetch(#{{ {tree} }}#)->serialize(#{{ {tree} }}#);
      mapping: {mapping};
      runtime: down::RT;
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
               asserts: [ a: EqualToJson #{{ expected: ExternalFormat #{{
                   contentType: 'application/json'; data: '{{}}'; }}#; }}# ]
            }}
         ]
      }}
   ]
}}
"""


def main() -> None:
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    # up::M is declared without a runtime of its own; down::RT names down::M, so the two
    # baseline cases need a runtime over up::M. Adding it here rather than in the shared
    # model keeps the navigation probe's sources unchanged.
    extra = ("###Runtime\nRuntime up::RT\n"
             "{ mappings: [ up::M ]; connections: [ down::DB: [ env: down::Conn ] ]; }\n\n"
             "###Service\n")
    results = []
    for name, root, tree in CASES:
        mapping = MAPPING.get(name, "down::M")
        runtime = "up::RT" if mapping == "up::M" else "down::RT"
        src = work / f"{name}.pure"
        body = SERVICE.format(name=name, root=root, tree=tree, mapping=mapping)
        src.write_text(base.UPSTREAM + base.DOWNSTREAM.replace("###Service\n", "")
                       + extra + body.replace("runtime: down::RT;", f"runtime: {runtime};"))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src),
             f"--testable=gf::S_{name}"],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=1800)
        out = r.stdout + r.stderr
        cast = re.search(r"Cast exception: (\w+) cannot be cast to (\w+)", out)
        if re.search(r"Error initializing test suite session", out):
            verdict, detail = "INIT-ERROR", (f"{cast.group(1)} -> {cast.group(2)}" if cast
                                             else "")
        elif re.search(rf"(PASS|FAIL)\s+S_{name}_suite", out):
            # It initialised, which is all this probe claims. The expectation is `{}` and is
            # meant to be wrong.
            verdict, detail = "RAN", ""
        else:
            err = re.search(r"(?:EngineException|Exception)[:\s]+([^\n]{0,120})", out)
            verdict, detail = "OTHER", err.group(1).strip() if err else "no verdict"
        results.append((verdict, name, detail))
        print(f"  {verdict:<12}{name:<30}{detail}")

    print(f"\n  sources kept at {work}")
    ran = {n for v, n, _d in results if v == "RAN"}
    bad = {n for v, n, _d in results if v == "INIT-ERROR"}
    if ran and bad:
        print(f"\n  initialises: {', '.join(sorted(ran))}")
        print(f"  does not:    {', '.join(sorted(bad))}")


if __name__ == "__main__":
    main()
