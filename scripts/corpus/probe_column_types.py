"""
Which SQL column types can actually be read back?

Every table in this corpus used VARCHAR, DOUBLE, INTEGER, DATE, TIMESTAMP or BIT. The large
exposures return declared four more -- SMALLINT for a line number, CHAR(2) for an ISO country,
NUMERIC(20,2) for money and REAL for a percentage -- all of them ordinary, all of them
accepted by the grammar, and one of them fatal:

    Execution error at (resource:/core_relational_duckdb/relational/typeConversion.pure
    line:59 column:12), "Match failure: RealObject instanceOf Real"

The error names neither the column nor the type. This declares one column of each type over
one row and reads them back one at a time, so the answer is per type rather than per table.
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

# (name, SQL type, Pure type, seeded literal, expected value back)
TYPES = [
    ("varchar", "VARCHAR(20)", "String", "abc", "abc"),
    ("char", "CHAR(2)", "String", "GB", "GB"),
    ("integer", "INTEGER", "Integer", "42", 42),
    ("smallint", "SMALLINT", "Integer", "7", 7),
    ("bigint", "BIGINT", "Integer", "90000000000", 90000000000),
    ("tinyint", "TINYINT", "Integer", "3", 3),
    ("double", "DOUBLE", "Float", "1.5", 1.5),
    ("real", "REAL", "Float", "2.5", 2.5),
    ("float", "FLOAT", "Float", "3.5", 3.5),
    ("decimal", "DECIMAL(18,4)", "Float", "4.25", 4.25),
    ("numeric", "NUMERIC(20,2)", "Float", "5.25", 5.25),
    ("date", "DATE", "StrictDate", "2024-06-28", "2024-06-28"),
    ("timestamp", "TIMESTAMP", "TIMESTAMP_SENTINEL", "2024-06-28 18:30:00", None),
    ("bit", "BIT", "Boolean", "true", True),
]


def cases():
    return [(n, sql, pure, lit, exp) for n, sql, pure, lit, exp in TYPES]


def build(cs) -> str:
    props = "\n".join(
        f"   {n}: {'DateTime' if p == 'TIMESTAMP_SENTINEL' else p}[0..1];"
        for n, _s, p, _l, _e in cs)
    cols = ",\n     ".join(f"C_{n.upper()} {sql}" for n, sql, _p, _l, _e in cs)
    maps = ",\n      ".join(f"{n}: [ct::DB]T.C_{n.upper()}" for n, _s, _p, _l, _e in cs)
    header = ",".join(f"C_{n.upper()}" for n, _s, _p, _l, _e in cs)
    row = ",".join(lit for _n, _s, _p, lit, _e in cs)
    services = "".join(SERVICE.format(
        name=n, data=json.dumps([{"k": "R1", n: exp}]).replace("'", "\\'"), prop=n)
        for n, _s, _p, _l, exp in cs if exp is not None)
    return MODEL.format(props=props, cols=cols, maps=maps,
                        header=header, row=row) + services


MODEL = """###Pure
Class ct::Row
{{
   k: String[1];
{props}
}}

###Relational
Database ct::DB
(
   Table T
   (
     K VARCHAR(8) PRIMARY KEY,
     {cols}
   )
)

###Mapping
Mapping ct::M
(
   ct::Row: Relational
   {{
      ~primaryKey ( [ct::DB]T.K )
      ~mainTable [ct::DB]T
      k: [ct::DB]T.K,
      {maps}
   }}
)

###Connection
RelationalDatabaseConnection ct::Conn
{{ store: ct::DB; type: DuckDB; specification: DuckDB {{ }}; auth: Test; }}

###Runtime
Runtime ct::RT
{{ mappings: [ ct::M ]; connections: [ ct::DB: [ env: ct::Conn ] ]; }}

###Data
Data ct::Seed
{{
  Relational
  #{{
    default.T:
      'K,{header}\\n' +
      'R1,{row}\\n';
  }}#
}}

###Service
"""

SERVICE = """Service ct::S_{name}
{{
   pattern: '/ct/{name}';
   documentation: 'Read back one column of one SQL type.';
   execution: Single
   {{
      query: |ct::Row.all()->project(~[k: x|$x.k, {prop}: x|$x.{prop}]);
      mapping: ct::M;
      runtime: ct::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ ct::Seed }}# ] ]
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
    # ONE MODEL PER TYPE. The first version declared all fourteen columns in one table and
    # the whole file died at table creation -- "Match failure: RealObject instanceOf Real"
    # -- before a single service ran. A type that cannot be CREATED takes every other type
    # in the file with it, so each has to stand alone to be attributable.
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    for n, sql, p_, lit, exp in cases():
        if exp is None:
            continue
        src = work / f"ct_{n}.pure"
        src.write_text(build([(n, sql, p_, lit, exp)]))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src),
             f"--testable=ct::S_{n}"],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=1800)
        out = r.stdout + r.stderr
        m = re.search(r"(PASS|FAIL|ERROR)\s+S_\w+_suite", out)
        v = m.group(1) if m else "FATAL"
        d = re.search(r"(Match failure[^\n\"]{0,50}|Execution error at[^\n\"]{0,50}"
                      r"|actual  : [^\n]{0,50})", out)
        print(f"  {v:<8}{sql:<16}{d.group(1).strip() if v != 'PASS' and d else ''}")
    print(f"\n  sources kept at {work}")


if __name__ == "__main__":
    main()
