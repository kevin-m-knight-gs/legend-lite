"""
Which TDS operations execute, one service per operation.

The TDS family is the older, string-keyed half of the query surface: `project([...], ['a'])`
returns a TabularDataSet, where `project(~[a:...])` returns a Relation. They are two spellings
of the same ideas with different signatures, which is why the oracle keeps two registries and
this file is separate from probe_relation.py -- `restrict` matches a TDS and not a Relation,
and `select` the reverse, so probing one says nothing about the other.

Same discipline as the other three probes: one service per operation, expected rows computed
from the seed, and the operations kept in the case list with a reason when they fail.
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
import run as runner  # noqa: E402

SEED = [
    {"g": "a", "v": 3, "w": "x"},
    {"g": "a", "v": 1, "w": "y"},
    {"g": "b", "v": 4, "w": "x"},
    {"g": "b", "v": 1, "w": "z"},
]

BASE = ("|tds::P.all()->project([x|$x.g, x|$x.v, x|$x.w], ['g', 'v', 'w'])"
        "->sort([asc('v'), asc('w')])")

# The seed in the total order every ordinal case uses. (v, w) is unique across the four rows;
# sorting on either alone leaves a tie, and a tie makes an ordinal expectation unfalsifiable.
ORDERED = sorted(SEED, key=lambda r: (r["v"], r["w"]))


def _o(fn, *args):
    """The oracle's own answer, so the expectation is ITS implementation under test."""
    import oracle
    return oracle.RELATION_IMPL[fn](*args)


def cases() -> list[tuple[str, str, list]]:
    return [
        ("project", BASE, ORDERED),
        ("restrict", BASE + "->restrict(['g', 'v'])",
         [{"g": r["g"], "v": r["v"]} for r in ORDERED]),
        ("renameColumns", BASE + "->renameColumns([pair('v', 'vv')])",
         [{"g": r["g"], "vv": r["v"], "w": r["w"]} for r in ORDERED]),
        ("take", BASE + "->take(2)", ORDERED[:2]),
        ("limit", BASE + "->limit(2)", ORDERED[:2]),
        ("drop", BASE + "->drop(1)", ORDERED[1:]),
        ("slice", BASE + "->slice(1, 3)", ORDERED[1:3]),
        ("distinct", BASE + "->restrict(['g'])->distinct()->sort([asc('g')])",
         [{"g": "a"}, {"g": "b"}]),
        ("filter", BASE + "->filter(r|$r.getInteger('v') > 1)",
         [r for r in ORDERED if r["v"] > 1]),
        ("groupBy", BASE + "->groupBy(['g'], [agg('t', x|$x.getInteger('v'), y|$y->sum())])"
                           "->sort([asc('g')])",
         [{"g": "a", "t": 4}, {"g": "b", "t": 5}]),
        ("concatenate", BASE + "->filter(r|$r.getInteger('v') > 3)"
                               "->concatenate(tds::P.all()"
                               "->project([x|$x.g, x|$x.v, x|$x.w], ['g', 'v', 'w'])"
                               "->filter(r|$r.getInteger('v') > 3))",
         [r for r in ORDERED if r["v"] > 3] * 2),
        # The TDS window form. `func(...)` names the aggregate or the window function, and the
        # third argument names the column it writes -- a different shape from the relation
        # family's `over(...)` with a lambda.
        ("olapGroupBy", BASE + "->olapGroupBy(['g'], func(y|$y->rank()), 'r')"
                               "->sort([asc('v'), asc('w')])",
         [{**r, "r": 1 if r["v"] == 1 else 2} for r in ORDERED]),
        ("sort", BASE, ORDERED),
        ("extend", BASE + "->extend([col(r|$r.getInteger('v') * 2, 'dbl')])",
         [{**r, "dbl": r["v"] * 2} for r in ORDERED]),
        ("paginated", BASE + "->paginated(1, 2)", _o("paginated", ORDERED, 1, 2)),
        ("join", BASE + "->restrict(['g', 'v'])->join("
                        "tds::P.all()->project([x|$x.g, x|$x.v], ['g2', 'v2']), "
                        "JoinKind.INNER, {a, b| $a.getString('g') == $b.getString('g2') "
                        "&& $a.getInteger('v') == $b.getInteger('v2')})"
                        "->sort([asc('v'), asc('g')])",
         [{"g": r["g"], "v": r["v"], "g2": r["g"], "v2": r["v"]}
          for r in sorted(SEED, key=lambda r: (r["v"], r["g"]))]),
        ("projectWithColumnSubset",
         "|tds::P.all()->projectWithColumnSubset([col(x|$x.g, 'g'), col(x|$x.v, 'v')], ['g'])"
         "->sort([asc('g')])",
         [{"g": "a"}, {"g": "a"}, {"g": "b"}, {"g": "b"}]),
    ]


MODEL = """###Pure
Class tds::P
{
   k: String[1];
   g: String[1];
   v: Integer[1];
   w: String[1];
}

###Relational
Database tds::DB
(
   Table T ( K VARCHAR(10) PRIMARY KEY, G VARCHAR(10), V INTEGER, W VARCHAR(10) )
)

###Mapping
Mapping tds::M
(
   tds::P: Relational
   {
      ~primaryKey ( [tds::DB]T.K )
      ~mainTable [tds::DB]T
      k: [tds::DB]T.K,
      g: [tds::DB]T.G,
      v: [tds::DB]T.V,
      w: [tds::DB]T.W
   }
)

###Connection
RelationalDatabaseConnection tds::Conn
{ store: tds::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime tds::RT
{ mappings: [ tds::M ]; connections: [ tds::DB: [ env: tds::Conn ] ]; }

###Data
Data tds::Seed
{
  Relational
  #{
    default.T:
      'K,G,V,W\\n' +
      'R1,a,3,x\\n' +
      'R2,a,1,y\\n' +
      'R3,b,4,x\\n' +
      'R4,b,1,z\\n';
  }#
}

###Service
"""

SERVICE = """Service tds::S_{name}
{{
   pattern: '/tds/{name}';
   documentation: 'The TDS operation {name}, against an independently computed result.';
   execution: Single
   {{
      query: {query};
      mapping: tds::M;
      runtime: tds::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ tds::Seed }}# ] ]
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
        SERVICE.format(name=n, query=q, data=json.dumps(e).replace("'", "\\'"))
        for n, q, e in cs)


def run(cs) -> tuple[str, dict]:
    work = Path(tempfile.mkdtemp())
    src = work / "tds.pure"
    src.write_text(build(cs))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        + [f"--testable=tds::S_{n}" for n, _q, _e in cs],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr
    verdicts = {}
    for name, _q, _e in cs:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        verdicts[name] = m.group(1) if m else "?"
    return out, verdicts


def main() -> None:
    cs = cases()
    out, verdicts = run(cs)
    fatal = re.search(r"EngineException: ([^\n]{0,240})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        return
    good = [n for n, v in verdicts.items() if v == "PASS"]
    bad = [(n, v) for n, v in verdicts.items() if v != "PASS"]
    for n, v in bad:
        print(f"  {n:<26} {v}")
    print(f"\n{len(good)} of {len(cs)} TDS operations execute and agree with the oracle")

    import probe_functions
    probe_functions.merge_evidence("tds", sorted(good), [(n, v) for n, v in bad])


if __name__ == "__main__":
    main()
