"""
Navigating INTO a dependency: how many hops, which edge style, and does a schema matter?

Linking two more projects into the executable corpus and reaching across produced:

    meta::pure::router::store::routing::Void not supported!

at test-suite initialisation -- the same anonymous assertion as F49, naming nothing. Two
candidate causes were tangled in that attempt and it was reverted rather than reported:

  * HOP COUNT. The navigation that failed was two hops (corpus -> project market -> project
    calendar). A ONE-hop navigation of the same shape works and passes in the corpus today.
  * A SCHEMA. The other failing case rooted at a class whose table lives inside the
    dependency's own `Schema` block.

Neither is a finding until it is separated from the other. This runs the cross product: one
and two hops, both edge styles, with and without a schema, plus a to-many for contrast.
Everything is EXECUTED against seeded rows rather than merely compiled, because the failure is
at initialisation and a compile check cannot see it.

Legend has two valid ways to model an edge and both appear across the project graph:

  ASSOCIATION  a declared `Association` with mapped ends
  PROPERTY     a class-typed property mapped over a join, `prop[setId]: [db]@Join`

A dependency's MANIFEST does not say which one it chose, so a consumer cannot know without
reading its source -- which is exactly why it matters whether they behave differently.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as runner

# The upstream "project": a three-level chain, once in the default schema and once inside a
# Schema block, with each level reachable by BOTH edge styles.
UPSTREAM = """###Pure
Class up::Leaf
{
   leafId: String[1];
   leafName: String[1];
}

Class up::Mid
{
   midId: String[1];
   midName: String[1];
   // ASSOCIATION style is declared below; this is the PROPERTY style, mapped over a join.
   leafByProperty: up::Leaf[0..1];
}

Association up::MidLeafAssoc
{
   leafByAssoc: up::Leaf[0..1];
   midsOfLeaf: up::Mid[*];
}

// The same three levels again, but the tables live inside a Schema block.
Class up::SchemaLeaf
{
   leafId: String[1];
   leafName: String[1];
}

Class up::SchemaMid
{
   midId: String[1];
   midName: String[1];
   leafByProperty: up::SchemaLeaf[0..1];
}

###Relational
Database up::DB
(
   Table UP_LEAF ( LEAF_ID VARCHAR(8) PRIMARY KEY, LEAF_NAME VARCHAR(16) )
   Table UP_MID ( MID_ID VARCHAR(8) PRIMARY KEY, MID_NAME VARCHAR(16), LEAF_ID VARCHAR(8) )

   Schema sch
   (
      Table UP_S_LEAF ( LEAF_ID VARCHAR(8) PRIMARY KEY, LEAF_NAME VARCHAR(16) )
      Table UP_S_MID ( MID_ID VARCHAR(8) PRIMARY KEY, MID_NAME VARCHAR(16), LEAF_ID VARCHAR(8) )
   )

   Join Up_MidLeaf(UP_MID.LEAF_ID = UP_LEAF.LEAF_ID)
   Join Up_SchemaMidLeaf(sch.UP_S_MID.LEAF_ID = sch.UP_S_LEAF.LEAF_ID)
)

###Mapping
Mapping up::M
(
   up::Leaf[upLeaf]: Relational
   {
      ~primaryKey ( [up::DB]UP_LEAF.LEAF_ID )
      ~mainTable [up::DB]UP_LEAF
      leafId: [up::DB]UP_LEAF.LEAF_ID,
      leafName: [up::DB]UP_LEAF.LEAF_NAME
   }

   up::Mid[upMid]: Relational
   {
      ~primaryKey ( [up::DB]UP_MID.MID_ID )
      ~mainTable [up::DB]UP_MID
      midId: [up::DB]UP_MID.MID_ID,
      midName: [up::DB]UP_MID.MID_NAME,
      leafByProperty[upLeaf]: [up::DB]@Up_MidLeaf
   }

   up::MidLeafAssoc: Relational
   {
      AssociationMapping
      (
         leafByAssoc[upMid, upLeaf]: [up::DB]@Up_MidLeaf,
         midsOfLeaf[upLeaf, upMid]: [up::DB]@Up_MidLeaf
      )
   }

   up::SchemaLeaf[upSchemaLeaf]: Relational
   {
      ~primaryKey ( [up::DB]sch.UP_S_LEAF.LEAF_ID )
      ~mainTable [up::DB]sch.UP_S_LEAF
      leafId: [up::DB]sch.UP_S_LEAF.LEAF_ID,
      leafName: [up::DB]sch.UP_S_LEAF.LEAF_NAME
   }

   up::SchemaMid[upSchemaMid]: Relational
   {
      ~primaryKey ( [up::DB]sch.UP_S_MID.MID_ID )
      ~mainTable [up::DB]sch.UP_S_MID
      midId: [up::DB]sch.UP_S_MID.MID_ID,
      midName: [up::DB]sch.UP_S_MID.MID_NAME,
      leafByProperty[upSchemaLeaf]: [up::DB]@Up_SchemaMidLeaf
   }
)
"""

# The downstream "project": a root of its own that reaches into up:: .
DOWNSTREAM = """###Pure
Class down::Root
{
   rootId: String[1];
   midByProperty: up::Mid[0..1];
   schemaMidByProperty: up::SchemaMid[0..1];
}

Class down::KeylessRoot
{
   rootId: String[1];
   midByProperty: up::Mid[0..1];
}

Association down::RootMidAssoc
{
   midByAssoc: up::Mid[0..1];
   rootsOfMid: down::Root[*];
}

###Relational
Database down::DB
(
   include up::DB

   Table DOWN_ROOT ( ROOT_ID VARCHAR(8) PRIMARY KEY, MID_ID VARCHAR(8) )

   Join Down_RootMid(DOWN_ROOT.MID_ID = UP_MID.MID_ID)
   Join Down_RootSchemaMid(DOWN_ROOT.MID_ID = sch.UP_S_MID.MID_ID)
)

###Mapping
Mapping down::M
(
   include up::M

   down::Root[downRoot]: Relational
   {
      ~primaryKey ( [down::DB]DOWN_ROOT.ROOT_ID )
      ~mainTable [down::DB]DOWN_ROOT
      rootId: [down::DB]DOWN_ROOT.ROOT_ID,
      midByProperty[upMid]: [down::DB]@Down_RootMid,
      schemaMidByProperty[upSchemaMid]: [down::DB]@Down_RootSchemaMid
   }

   // The SAME class shape with NO ~primaryKey, which is how a large part of the corpus maps
   // its classes -- `~mainTable` and column bindings, nothing else. Every set in the first
   // version of this probe declared a key, which is why the first version found nothing.
   down::KeylessRoot[downKeyless]: Relational
   {
      ~mainTable [down::DB]DOWN_ROOT
      rootId: [down::DB]DOWN_ROOT.ROOT_ID,
      midByProperty[upMid]: [down::DB]@Down_RootMid
   }

   down::RootMidAssoc: Relational
   {
      AssociationMapping
      (
         midByAssoc[downRoot, upMid]: [down::DB]@Down_RootMid,
         rootsOfMid[upMid, downRoot]: [down::DB]@Down_RootMid
      )
   }
)

###Connection
RelationalDatabaseConnection down::Conn
{ store: down::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime down::RT
{ mappings: [ down::M ]; connections: [ down::DB: [ env: down::Conn ] ]; }

###Data
Data down::Seed
{
  Relational
  #{
    default.UP_LEAF:
      'LEAF_ID,LEAF_NAME\\n' +
      'L1,leaf-one\\n';
    default.UP_MID:
      'MID_ID,MID_NAME,LEAF_ID\\n' +
      'M1,mid-one,L1\\n';
    sch.UP_S_LEAF:
      'LEAF_ID,LEAF_NAME\\n' +
      'L1,sleaf-one\\n';
    sch.UP_S_MID:
      'MID_ID,MID_NAME,LEAF_ID\\n' +
      'M1,smid-one,L1\\n';
    default.DOWN_ROOT:
      'ROOT_ID,MID_ID\\n' +
      'R1,M1\\n';
  }#
}

###Service
"""

# (name, root class, projection, expected single row). Every case reads the same one row, so
# a difference between them is the navigation and nothing else.
CASES = [
    # --- inside the dependency, for a baseline. These are the shapes that already work. ---
    ("UpOneHopProperty", "up::Mid",
     "leaf: x|$x.leafByProperty.leafName", {"k": "M1", "leaf": "leaf-one"}, "midId"),
    ("UpOneHopAssoc", "up::Mid",
     "leaf: x|$x.leafByAssoc.leafName", {"k": "M1", "leaf": "leaf-one"}, "midId"),
    ("UpSchemaOneHopProperty", "up::SchemaMid",
     "leaf: x|$x.leafByProperty.leafName", {"k": "M1", "leaf": "sleaf-one"}, "midId"),

    # --- ONE hop across the boundary ---
    ("CrossOneHopProperty", "down::Root",
     "mid: x|$x.midByProperty.midName", {"k": "R1", "mid": "mid-one"}, "rootId"),
    ("CrossOneHopAssoc", "down::Root",
     "mid: x|$x.midByAssoc.midName", {"k": "R1", "mid": "mid-one"}, "rootId"),
    ("CrossOneHopSchema", "down::Root",
     "mid: x|$x.schemaMidByProperty.midName", {"k": "R1", "mid": "smid-one"}, "rootId"),

    # --- TWO hops across the boundary: the case that failed ---
    ("CrossTwoHopPropertyProperty", "down::Root",
     "leaf: x|$x.midByProperty.leafByProperty.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),
    ("CrossTwoHopPropertyAssoc", "down::Root",
     "leaf: x|$x.midByProperty.leafByAssoc.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),
    ("CrossTwoHopAssocProperty", "down::Root",
     "leaf: x|$x.midByAssoc.leafByProperty.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),
    ("CrossTwoHopAssocAssoc", "down::Root",
     "leaf: x|$x.midByAssoc.leafByAssoc.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),
    # --- the same navigations from a root whose set declares NO ~primaryKey ---
    ("KeylessOneHopProperty", "down::KeylessRoot",
     "mid: x|$x.midByProperty.midName", {"k": "R1", "mid": "mid-one"}, "rootId"),
    ("KeylessTwoHopProperty", "down::KeylessRoot",
     "leaf: x|$x.midByProperty.leafByProperty.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),
    ("KeylessTwoHopAssoc", "down::KeylessRoot",
     "leaf: x|$x.midByProperty.leafByAssoc.leafName",
     {"k": "R1", "leaf": "leaf-one"}, "rootId"),

    ("CrossTwoHopSchema", "down::Root",
     "leaf: x|$x.schemaMidByProperty.leafByProperty.leafName",
     {"k": "R1", "leaf": "sleaf-one"}, "rootId"),
]

SERVICE = """Service bn::S_{name}
{{
   pattern: '/bn/{name}';
   documentation: 'Navigating into a dependency: {name}.';
   execution: Single
   {{
      query: |{root}.all()->project(~[k: x|$x.{key}, {proj}])->sort(~k->ascending());
      mapping: down::M;
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
               serializationFormat: PURE_TDSOBJECT;
               asserts: [ a: EqualToJson #{{ expected: ExternalFormat #{{
                   contentType: 'application/json'; data: '{data}'; }}#; }}# ]
            }}
         ]
      }}
   ]
}}

"""


def main() -> None:
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    # ONE MODEL PER CASE. The failure is an exception at test-suite initialisation, which
    # kills the JVM -- so a single bad case in a shared file takes every other case with it
    # and reports nothing at all. That is the same trap that cost eighty corpus services.
    for name, root, proj, expected, key in CASES:
        src = work / f"{name}.pure"
        src.write_text(UPSTREAM + DOWNSTREAM + SERVICE.format(
            name=name, root=root, proj=proj, key=key,
            data=json.dumps([expected]).replace("'", "\\'")))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src),
             f"--testable=bn::S_{name}"],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=1800)
        out = r.stdout + r.stderr
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        verdict = m.group(1) if m else "FATAL"
        detail = ""
        if verdict != "PASS":
            d = re.search(r"(Void not supported[^\n\"]{0,30}|Error initializing[^\n\"]{0,40}"
                          r"|EngineException: [^\n\"]{0,70}|actual  : [^\n]{0,60})", out)
            detail = f"  {d.group(1).strip()}" if d else ""
        print(f"  {verdict:<7}{name:<30}{detail}")
    print(f"\n  sources kept at {work}")


if __name__ == "__main__":
    main()
