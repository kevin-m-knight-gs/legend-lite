"""
An aggregate over a to-many end reached by a NON-EQUALITY join.

`CV3_PillarNeighbours` asks, for each curve pillar, whether anything on the same curve and
date sits further out. The association behind it is a `{target}` self-join with three
conditions, the last an inequality:

    Join Pillar_LongerPillar(P.CURVE_ID = {target}.CURVE_ID
                             and P.COB_DATE = {target}.COB_DATE
                             and P.TENOR_DAYS < {target}.TENOR_DAYS)

`->isEmpty()` over that end returns the right BOOLEAN and the wrong number of ROWS: the
source row comes back once per joined row instead of once. A query over 192 pillars returns
far more than 192 rows, every one of them individually correct.

This separates the possible causes, because "aggregates over inequality joins fan out",
"aggregates over self-joins fan out" and "two to-many aggregates in one projection fan out"
are three different defects:

  * one isEmpty, over an inequality self-join
  * one count, over the same
  * one isEmpty, over an EQUALITY self-join on the same table
  * two isEmpty, over the two directions of the inequality join
  * one isEmpty, over an inequality join to a DIFFERENT table

Expectations are computed from the six seeded rows below and never read back from the engine.
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

# (id, group, rank). Group 'a' has ranks 1,2,3; group 'b' has 1,2; group 'c' has 1 alone --
# so 'c' is the row with nothing above it AND nothing below it.
ROWS = [("R1", "a", 1), ("R2", "a", 2), ("R3", "a", 3),
        ("R4", "b", 1), ("R5", "b", 2), ("R6", "c", 1)]


def _above(g, r):
    return [x for x in ROWS if x[1] == g and x[2] > r]


def _below(g, r):
    return [x for x in ROWS if x[1] == g and x[2] < r]


def _peers(i, g):
    return [x for x in ROWS if x[1] == g and x[0] != i]


# R1 has two children, R2 one, and the rest none.
KIDS = [("K1", "R1"), ("K2", "R1"), ("K3", "R2")]


def _kids(i):
    return [k for k in KIDS if k[1] == i]


def cases() -> list[tuple[str, str, list]]:
    ident = [{"id": i} for i, _g, _r in ROWS]

    def rows(extra):
        return [dict(id=i, **extra(i, g, r)) for i, g, r in ROWS]

    return [
        ("IneqIsEmpty",
         "->project(~[id: x|$x.id, noneAbove: x|$x.above->isEmpty()])",
         rows(lambda i, g, r: {"noneAbove": not _above(g, r)})),
        ("IneqCount",
         "->project(~[id: x|$x.id, countAbove: x|$x.above->count()])",
         rows(lambda i, g, r: {"countAbove": len(_above(g, r))})),
        ("EqIsEmpty",
         "->project(~[id: x|$x.id, noPeers: x|$x.peers->isEmpty()])",
         rows(lambda i, g, r: {"noPeers": not _peers(i, g)})),
        # The control that decides how wide the claim can be: a to-many end to a DIFFERENT
        # table, over a plain equality join. If this duplicates too, the defect is
        # `isEmpty` over any to-many; if it does not, it is the SELF-join.
        ("ChildIsEmpty",
         "->project(~[id: x|$x.id, noKids: x|$x.kids->isEmpty()])",
         rows(lambda i, g, r: {"noKids": not _kids(i)})),
        ("TwoIneqIsEmpty",
         "->project(~[id: x|$x.id, noneAbove: x|$x.above->isEmpty(), "
         "noneBelow: x|$x.below->isEmpty()])",
         rows(lambda i, g, r: {"noneAbove": not _above(g, r),
                               "noneBelow": not _below(g, r)})),
    ]


MODEL = """###Pure
Class ineq::P
{
   id: String[1];
   grp: String[1];
   rank: Integer[1];
}

Association ineq::Above
{
   below: ineq::P[*];
   above: ineq::P[*];
}

Association ineq::Peers
{
   peerOf: ineq::P[*];
   peers: ineq::P[*];
}

Class ineq::K
{
   kid: String[1];
   parentId: String[1];
}

Association ineq::Kids
{
   parent: ineq::P[0..1];
   kids: ineq::K[*];
}

###Relational
Database ineq::DB
(
   Table T ( ID VARCHAR(10) PRIMARY KEY, GRP VARCHAR(10), RNK INTEGER )
   Table K ( KID VARCHAR(10) PRIMARY KEY, PARENT_ID VARCHAR(10) )

   Join T_Kids(T.ID = K.PARENT_ID)

   // Same group, higher rank. An INEQUALITY over a {target} self-join.
   Join T_Above(T.GRP = {target}.GRP and T.RNK < {target}.RNK)
   // Same group, different row. An EQUALITY over the same self-join, as the control.
   Join T_Peers(T.GRP = {target}.GRP and T.ID <> {target}.ID)
)

###Mapping
Mapping ineq::M
(
   ineq::P[p]: Relational
   {
      ~primaryKey ( [ineq::DB]T.ID )
      ~mainTable [ineq::DB]T
      id: [ineq::DB]T.ID,
      grp: [ineq::DB]T.GRP,
      rank: [ineq::DB]T.RNK
   }

   ineq::Above: Relational
   {
      AssociationMapping
      (
         above[p, p]: [ineq::DB]@T_Above,
         below[p, p]: [ineq::DB]@T_Above
      )
   }

   ineq::K[k]: Relational
   {
      ~primaryKey ( [ineq::DB]K.KID )
      ~mainTable [ineq::DB]K
      kid: [ineq::DB]K.KID,
      parentId: [ineq::DB]K.PARENT_ID
   }

   ineq::Kids: Relational
   {
      AssociationMapping
      (
         kids[p, k]: [ineq::DB]@T_Kids,
         parent[k, p]: [ineq::DB]@T_Kids
      )
   }

   ineq::Peers: Relational
   {
      AssociationMapping
      (
         peers[p, p]: [ineq::DB]@T_Peers,
         peerOf[p, p]: [ineq::DB]@T_Peers
      )
   }
)

###Connection
RelationalDatabaseConnection ineq::Conn
{ store: ineq::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime ineq::RT
{ mappings: [ ineq::M ]; connections: [ ineq::DB: [ env: ineq::Conn ] ]; }

###Data
Data ineq::Seed
{
  Relational
  #{
    default.T:
      'ID,GRP,RNK\\n' +
      'R1,a,1\\n' +
      'R2,a,2\\n' +
      'R3,a,3\\n' +
      'R4,b,1\\n' +
      'R5,b,2\\n' +
      'R6,c,1\\n';
    default.K:
      'KID,PARENT_ID\\n' +
      'K1,R1\\n' +
      'K2,R1\\n' +
      'K3,R2\\n';
  }#
}

###Service
"""

SERVICE = """Service ineq::S_{name}
{{
   pattern: '/ineq/{name}';
   documentation: 'Aggregate over a to-many end reached by a non-equality join: {name}.';
   execution: Single
   {{
      query: |ineq::P.all(){body}->sort(~id->ascending());
      mapping: ineq::M;
      runtime: ineq::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ ineq::Seed }}# ] ]
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


def build(cs) -> str:
    return MODEL + "".join(
        SERVICE.format(name=name, body=body,
                       data=json.dumps(expected).replace("'", "\\'"))
        for name, body, expected in cs)


def main() -> None:
    cs = cases()
    work = Path(tempfile.mkdtemp())
    src = work / "ineq.pure"
    src.write_text(build(cs))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        + [f"--testable=ineq::S_{name}" for name, _b, _e in cs],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr

    fatal = re.search(r"EngineException: ([^\n]{0,300})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        print(f"\n  source kept at {src}")
        return

    for name, _body, expected in cs:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        verdict = m.group(1) if m else "MISSING"
        detail = ""
        if verdict == "FAIL":
            a = re.search(rf"S_{name}_suite.*?actual  : (\[.*?\])\n", out, re.S)
            if a:
                try:
                    got = json.loads(a.group(1))
                    detail = f"   {len(expected)} rows expected, {len(got)} returned"
                except (ValueError, TypeError):
                    detail = "   (actual not parseable)"
        print(f"  {verdict:<8}{name:<18}{detail}")
    print(f"\n  source kept at {src}")


if __name__ == "__main__":
    main()
