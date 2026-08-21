"""
A qualified property reached through a to-one navigation that lands on nothing.

A generated service projected `$x.curve.benchmarkSeries.tickerOn('BBG')` over eight curves,
three of which have no benchmark series. For those three the oracle expects NULL -- the chain
is broken, so there is no object to ask -- and the engine returns:

    "BBG/:"

which is the property's own body, `$vendor + '/' + $this.sourceCode + ':' + $this.seriesId`,
evaluated with every `$this.` component empty. A string that looks like a value, built out of
a row that is not there.

This narrows it. Four questions, and they have different answers:

  * a PLAIN property through the same broken chain -- does it give null?
  * a DERIVED property with no arguments -- does it give null?
  * a QUALIFIED property whose body concatenates -- this is the case above
  * a QUALIFIED property whose body does ARITHMETIC rather than concatenation -- because
    `0 + 0` and `'' + ''` fail differently, and only one of them is visible

Expectations are computed from the three seeded rows and never read back.
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

# (id, targetId) -- R2 points at a target that does not exist, R3 points at nothing at all.
ROWS = [("R1", "T1"), ("R2", "MISSING"), ("R3", None)]


def cases():
    def rows(key):
        # Only R1's chain lands. The other two have no object to ask, so every projection
        # through the chain is null -- that is the claim under test.
        return [{"k": i, key: ("T-one" if key == "plainName"
                               else 42 if key == "derivedTwice"
                               else "BBG/T-one:T1" if key == "qualifiedText"
                               else 2100.0) if t == "T1" else None}
                for i, t in ROWS]

    return [
        ("PlainThroughChain", "plainName: x|$x.target.name", rows("plainName")),
        ("DerivedThroughChain", "derivedTwice: x|$x.target.twiceSize", rows("derivedTwice")),
        ("QualifiedTextThroughChain",
         "qualifiedText: x|$x.target.label('BBG')", rows("qualifiedText")),
        ("QualifiedMathThroughChain",
         "qualifiedMath: x|$x.target.scaled(100.0)", rows("qualifiedMath")),
    ]


MODEL = """###Pure
Class qb::Target
{
   targetId: String[1];
   name: String[1];
   size: Integer[1];

   twiceSize() {{ $this.size * 2 }} : Integer[1];
   // Concatenation: every component is a String, so an absent one contributes ''.
   label(vendor: String[1]) {{ $vendor + '/' + $this.name + ':' + $this.targetId }} : String[1];
   // Arithmetic over the same absent object, for contrast.
   // ->toFloat(): `Integer * Float` types as `Number`, which is not a Float.
   scaled(factor: Float[1]) {{ $this.size->toFloat() * $factor }} : Float[1];
}}

Class qb::Source
{{
   k: String[1];
   target: qb::Target[0..1];
}}

Association qb::SourceTarget
{{
   sourceRows: qb::Source[*];
   linkedTarget: qb::Target[0..1];
}}

###Relational
Database qb::DB
(
   Table SRC ( K VARCHAR(8) PRIMARY KEY, TARGET_ID VARCHAR(8) )
   Table TGT ( TARGET_ID VARCHAR(8) PRIMARY KEY, NAME VARCHAR(16), SIZE_N INTEGER )

   Join Qb_SourceTarget(SRC.TARGET_ID = TGT.TARGET_ID)
)

###Mapping
Mapping qb::M
(
   qb::Target[qbTarget]: Relational
   {{
      ~primaryKey ( [qb::DB]TGT.TARGET_ID )
      ~mainTable [qb::DB]TGT
      targetId: [qb::DB]TGT.TARGET_ID,
      name: [qb::DB]TGT.NAME,
      size: [qb::DB]TGT.SIZE_N
   }}

   qb::Source[qbSource]: Relational
   {{
      ~primaryKey ( [qb::DB]SRC.K )
      ~mainTable [qb::DB]SRC
      k: [qb::DB]SRC.K,
      target[qbTarget]: [qb::DB]@Qb_SourceTarget
   }}
)

###Connection
RelationalDatabaseConnection qb::Conn
{{ store: qb::DB; type: DuckDB; specification: DuckDB {{ }}; auth: Test; }}

###Runtime
Runtime qb::RT
{{ mappings: [ qb::M ]; connections: [ qb::DB: [ env: qb::Conn ] ]; }}

###Data
Data qb::Seed
{{
  Relational
  #{{
    default.SRC:
      'K,TARGET_ID\\n' +
      'R1,T1\\n' +
      'R2,MISSING\\n' +
      'R3,\\n';
    default.TGT:
      'TARGET_ID,NAME,SIZE_N\\n' +
      'T1,T-one,21\\n';
  }}#
}}

###Service
"""

SERVICE = """Service qb::S_{name}
{{
   pattern: '/qb/{name}';
   documentation: 'A qualified or derived property through a to-one chain that lands on nothing.';
   execution: Single
   {{
      query: |qb::Source.all()->project(~[k: x|$x.k, {proj}])->sort(~k->ascending());
      mapping: qb::M;
      runtime: qb::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ qb::Seed }}# ] ]
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
    cs = cases()
    work = Path(tempfile.mkdtemp())
    src = work / "qb.pure"
    src.write_text(MODEL.replace("{{", "{").replace("}}", "}") + "".join(
        SERVICE.format(name=n, proj=p, data=json.dumps(e).replace("'", "\\'"))
        for n, p, e in cs))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        + [f"--testable=qb::S_{n}" for n, _p, _e in cs],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    fatal = re.search(r"EngineException: ([^\n]{0,200})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        print(f"\n  source kept at {src}")
        return
    for n, _p, _e in cs:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{n}_suite", out)
        v = m.group(1) if m else "MISSING"
        detail = ""
        if v != "PASS":
            d = re.search(rf"S_{n}_suite.*?actual  : (\[[^\n]{{0,150}})", out, re.S)
            detail = f"   actual {d.group(1).strip()}" if d else ""
        print(f"  {v:<8}{n:<28}{detail}")
    print(f"\n  source kept at {src}")


if __name__ == "__main__":
    main()
