"""
Filter on a DERIVED property.

The corpus projects derived properties everywhere and, until MD3_UnrevisedPrints, filtered on
one nowhere. That is a gap with a shape: `->filter({x|$x.someDerived == false})` is how anyone
would write "the rows where this computed flag is not set", it is the first thing a user does
after adding a derived property, and nothing in a thousand services had asked for it.

MD3 asked, and got:

    java.sql.SQLException: Parser Error: syntax error at or near "="

which is the database rejecting SQL the engine wrote. This narrows that to the smallest model
that shows it -- five rows, three derived properties -- and separates the variants, because
"filtering on a derived property is broken" and "comparing a derived BOOLEAN to a literal is
broken" are different defects with different workarounds.

Expectations come from the five seeded rows, which are written out here and evaluated by hand.
Nothing reads the engine's answer.
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

# id, status, value. Two FINAL, three not; two values above zero, three not.
ROWS = [("R1", "FINAL", 4.0), ("R2", "PRELIM", -1.0), ("R3", "FINAL", -2.0),
        ("R4", "REVISED", 7.0), ("R5", "PRELIM", 0.0)]


def cases() -> list[tuple[str, str, list]]:
    """(name, filter clause, expected rows) -- computed from ROWS, never read back."""
    def rows(keep):
        return [{"obsId": i, "obsValue": v} for i, s, v in ROWS if keep(s, v)]

    return [
        # The exact shape MD3 uses.
        ("BoolEqFalse", "->filter({x|($x.isFinal == false)})",
         rows(lambda s, v: s != "FINAL")),
        # The same comparison the other way, in case the literal `false` is what breaks.
        ("BoolEqTrue", "->filter({x|($x.isFinal == true)})",
         rows(lambda s, v: s == "FINAL")),
        # The boolean used bare, with no comparison at all.
        ("BoolBare", "->filter({x|$x.isFinal})",
         rows(lambda s, v: s == "FINAL")),
        # Negated bare, which is what BoolEqFalse means.
        ("BoolNot", "->filter({x|!$x.isFinal})",
         rows(lambda s, v: s != "FINAL")),
        # A derived property that is NOT a boolean, to separate "derived in a filter" from
        # "derived boolean in a filter".
        ("FloatCompare", "->filter({x|($x.doubled > 0.0)})",
         rows(lambda s, v: v * 2 > 0)),
        # A derived STRING, for the same reason.
        ("StringCompare", "->filter({x|($x.tag == 'FINAL!')})",
         rows(lambda s, v: s == "FINAL")),
        # The control: the derived property's own expression, written out at the call site.
        # If this fails too, the problem is not derivation.
        ("Underlying", "->filter({x|($x.status == 'FINAL')})",
         rows(lambda s, v: s == "FINAL")),
    ]


MODEL = """###Pure
Class dp::Obs
{
   obsId: String[1];
   status: String[1];
   obsValue: Float[1];

   isFinal() { $this.status == 'FINAL' } : Boolean[1];
   doubled() { $this.obsValue * 2.0 } : Float[1];
   tag() { $this.status + '!' } : String[1];
}

###Relational
Database dp::DB
(
   Table OBS (OBS_ID VARCHAR(8) PRIMARY KEY, STATUS VARCHAR(12), OBS_VALUE DECIMAL(18,4))
)

###Mapping
Mapping dp::M
(
   dp::Obs: Relational
   {
      ~primaryKey ( [dp::DB]OBS.OBS_ID )
      ~mainTable [dp::DB]OBS
      obsId: [dp::DB]OBS.OBS_ID,
      status: [dp::DB]OBS.STATUS,
      obsValue: [dp::DB]OBS.OBS_VALUE
   }
)

###Connection
RelationalDatabaseConnection dp::Conn
{ store: dp::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime dp::RT
{ mappings: [ dp::M ]; connections: [ dp::DB: [ env: dp::Conn ] ]; }

###Data
Data dp::Seed
{
  Relational
  #{
    default.OBS:
        'OBS_ID,STATUS,OBS_VALUE\\n' +
        'R1,FINAL,4.0\\n' +
        'R2,PRELIM,-1.0\\n' +
        'R3,FINAL,-2.0\\n' +
        'R4,REVISED,7.0\\n' +
        'R5,PRELIM,0.0\\n';
  }#
}

###Service
"""

SERVICE = """Service dp::S_{name}
{{
   pattern: '/dp/{name}';
   documentation: 'Filtering on a derived property: {name}.';
   execution: Single
   {{
      query: |dp::Obs.all(){clause}
         ->project(~[obsId: x|$x.obsId, obsValue: x|$x.obsValue])
         ->sort(~obsId->ascending());
      mapping: dp::M;
      runtime: dp::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ dp::Seed }}# ] ]
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
        SERVICE.format(name=name, clause=clause,
                       data=json.dumps(expected).replace("'", "\\'"))
        for name, clause, expected in cs)


def main() -> None:
    cs = cases()
    work = Path(tempfile.mkdtemp())
    src = work / "dp.pure"
    src.write_text(build(cs))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        + [f"--testable=dp::S_{name}" for name, _c, _e in cs],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr

    fatal = re.search(r"EngineException: ([^\n]{0,300})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        print(f"\n  source kept at {src}")
        return

    for name, clause, expected in cs:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        verdict = m.group(1) if m else "MISSING"
        detail = ""
        if verdict != "PASS":
            e = re.search(rf"S_{name}_suite.*?((?:Parser|Binder|Catalog) Error[^\n\"]{{0,120}}"
                          rf"|Assert failure[^\n\"]{{0,120}}|actual  : [^\n]{{0,120}})",
                          out, re.S)
            detail = f"   {e.group(1).strip()}" if e else ""
        print(f"  {verdict:<8}{name:<16}{len(expected)} row(s) expected{detail}")
    print(f"\n  source kept at {src}")


if __name__ == "__main__":
    main()
