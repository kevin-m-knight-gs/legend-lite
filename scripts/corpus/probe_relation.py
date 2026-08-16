"""
Which RELATION operations execute, one service per operation.

The third and last family the probes reach. Scalars live in a property mapping and aggregates
live in a groupBy; relation operations are neither -- they are query SHAPES, and each one
changes the result differently, so they cannot share a row the way the other two probes do.
One service per operation it is.

None of these were exercised at all before this. The corpus used `project`, `groupBy`, `sort`
and `limit` and nothing else: no `extend`, no `rename`, no `select`, no `slice`, and not one
of the seven window functions. That is 47 of the registry's names, the single largest block
of never-executed surface, and it went unnoticed because the density scoreboard counted
"relation operations" as one construct that `project` alone satisfied.

Expected values come from the oracle's RELATION_IMPL over the same four seed rows. The seed
is built so the operations cannot be confused with one another:

    g   a  a  b  b       two groups, so groupBy and window partitions differ from the whole
    v   3  1  4  1       unsorted, with a DUPLICATE, so sort is visibly not identity and
                         distinct is visibly not a no-op
    w   x  y  x  z       a second dimension, so `select` dropping a column is observable

The duplicate 1 is the load-bearing part of the seed. It is what separates rank from
denseRank from rowNumber -- over distinct values all three agree, and a test over distinct
values would pass whichever of the three the engine actually computed.
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
    {"k": "R1", "g": "a", "v": 3, "w": "x"},
    {"k": "R2", "g": "a", "v": 1, "w": "y"},
    {"k": "R3", "g": "b", "v": 4, "w": "x"},
    {"k": "R4", "g": "b", "v": 1, "w": "z"},
]

BASE = "|rel::P.all()->project(~[g:x|$x.g, v:x|$x.v, w:x|$x.w])"


def _o(fn, *args):
    """Call the oracle's relation implementation, so the expectation is ITS answer."""
    import oracle
    return oracle.RELATION_IMPL[fn](*args)


def cases() -> list[tuple[str, str, list]]:
    """(operation, query, expected rows) -- expectations from the oracle, never the engine."""
    base = [{"g": r["g"], "v": r["v"], "w": r["w"]} for r in SEED]
    return [
        ("project", BASE, base),
        ("filter", BASE + "->filter(r|$r.v > 1)", _o("filter", base, lambda r: r["v"] > 1)),
        ("sort", BASE + "->sort([~v->ascending(), ~g->ascending()])",
         sorted(base, key=lambda r: (r["v"], r["g"]))),
        # limit/drop/slice are ordinal, so they need a TOTAL order or the expectation is not
        # a fact about the operation. Sorting by `w` alone leaves the two 'x' rows tied, and
        # SQL is free to return them either way round -- the first version of this probe did
        # exactly that and reported `slice` as a disagreement when the only disagreement was
        # about which of two tied rows came first. `v` then `w` is unique across the seed.
        ("limit", BASE + "->sort([~v->ascending(), ~w->ascending()])->limit(2)",
         _o("limit", _key(base), 2)),
        ("drop", BASE + "->sort([~v->ascending(), ~w->ascending()])->drop(1)",
         _o("drop", _key(base), 1)),
        ("slice", BASE + "->sort([~v->ascending(), ~w->ascending()])->slice(1, 3)",
         _o("slice", _key(base), 1, 3)),
        ("select", BASE + "->select(~[g, v])->sort([~g->ascending(), ~v->ascending()])",
         sorted(_o("select", base, ["g", "v"]), key=lambda r: (r["g"], r["v"]))),
        ("rename", BASE + "->rename(~v, ~vv)->sort([~g->ascending(), ~vv->ascending()])",
         sorted(_o("rename", base, "v", "vv"), key=lambda r: (r["g"], r["vv"]))),
        ("extend", BASE + "->extend(~[dbl: r|$r.v * 2])"
                          "->sort([~g->ascending(), ~v->ascending()])",
         sorted(_o("extend", base, "dbl", lambda r: r["v"] * 2),
                key=lambda r: (r["g"], r["v"]))),
        ("distinct", BASE + "->select(~[g])->distinct()->sort(~g->ascending())",
         sorted(_o("distinct", _o("select", base, ["g"])), key=lambda r: r["g"])),
        ("concatenate",
         BASE + "->filter(r|$r.v > 3)->concatenate("
                "rel::P.all()->project(~[g:x|$x.g, v:x|$x.v, w:x|$x.w])->filter(r|$r.v > 3))",
         _o("concatenate", _o("filter", base, lambda r: r["v"] > 3),
            _o("filter", base, lambda r: r["v"] > 3))),
        # The window functions. Partitioned by g and ordered by v, over a group that contains
        # the duplicate -- which is the only arrangement that tells the three apart.
        ("rank", BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->rank($w,$r)}])"
                        "->sort([~g->ascending(), ~v->ascending()])",
         _window("rank")),
        ("denseRank",
         BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->denseRank($w,$r)}])"
                "->sort([~g->ascending(), ~v->ascending()])",
         _window("denseRank")),
        # rowNumber takes (Relation, row) -- no window argument, unlike every other window
        # function here. `rowNumber(Relation<T>[1], T[1])` per the compiler's own suggestion.
        ("rowNumber",
         BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->rowNumber($r)}])"
                "->sort([~g->ascending(), ~v->ascending()])",
         _window("rowNumber")),
        ("percentRank",
         BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->percentRank($w,$r)}])"
                "->sort([~g->ascending(), ~v->ascending()])",
         _window("percentRank")),
        ("cumulativeDistribution",
         BASE + "->extend(over(~g, ~v->ascending()), "
                "~[r: {p,w,r|$p->cumulativeDistribution($w,$r)}])"
                "->sort([~g->ascending(), ~v->ascending()])",
         _window("cumulativeDistribution")),
        # -- second batch. Window functions that take an extra argument, the ordinal
        # operations, and the shapes that return a single row or a scalar rather than a
        # relation.
        ("nth", BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->nth($w,$r,1).v}])"
                       "->sort([~g->ascending(), ~v->ascending()])",
         _nth()),
        ("ntile", BASE + "->extend(over(~g, ~v->ascending()), ~[r: {p,w,r|$p->ntile($r,2)}])"
                         "->sort([~g->ascending(), ~v->ascending()])",
         _ntile()),
        ("take", BASE + "->sort([~v->ascending(), ~w->ascending()])->take(2)",
         _o("take", _key(base), 2)),
        # No `size`: a service whose query returns an Integer rather than a relation cannot
        # be asserted through this test framework at all -- the result builder is a
        # DataTypeBuilder and the assertion casts it to a TDSBuilder, throwing
        # ClassCastException before any comparison happens. Same shape as the EqualTo
        # limitation already recorded in SURFACE_BLOCKED.tsv.

        ("first", BASE + "->sort([~v->ascending(), ~w->ascending()])->first()",
         [_o("first", _key(base))]),
        ("last", BASE + "->sort([~v->ascending(), ~w->ascending()])->last()",
         [_o("last", _key(base))]),
        # No `restrict` here: it belongs to the TDS family and takes a TabularDataSet, so
        # over a Relation the compiler reports no matching signature. The relation spelling
        # of the same idea is `select`, which is probed above.
        ("groupBy", BASE + "->groupBy(~[g], ~[t: x|$x.v : a|$a->sum()])"
                           "->sort(~g->ascending())",
         [{"g": "a", "t": 4}, {"g": "b", "t": 5}]),
        ("join",
         BASE + "->join(rel::P.all()->project(~[g2:x|$x.g, v2:x|$x.v]), JoinKind.INNER, "
                "{a,b| $a.g == $b.g2 && $a.v == $b.v2})"
                "->sort([~v->ascending(), ~w->ascending()])",
         [{**r, "g2": r["g"], "v2": r["v"]} for r in _key(base)]),
    ]


def _nth() -> list:
    """The FIRST row of each partition, repeated across the partition (nth is 1-based here)."""
    out = []
    for g in sorted({r["g"] for r in SEED}):
        part = sorted([{"g": r["g"], "v": r["v"], "w": r["w"]} for r in SEED if r["g"] == g],
                      key=lambda r: r["v"])
        out += [{**row, "r": part[0]["v"]} for row in part]
    return out


def _ntile() -> list:
    """Two buckets over a two-row partition: one row each."""
    out = []
    for g in sorted({r["g"] for r in SEED}):
        part = sorted([{"g": r["g"], "v": r["v"], "w": r["w"]} for r in SEED if r["g"] == g],
                      key=lambda r: r["v"])
        out += [{**row, "r": i + 1} for i, row in enumerate(part)]
    return out


def _key(rows) -> list:
    """The seed in the one total order every ordinal case uses."""
    return sorted(rows, key=lambda r: (r["v"], r["w"]))


def _window(fn) -> list:
    """Expected rows for a window function, partitioned by g and ordered by v."""
    import oracle
    out = []
    for g in sorted({r["g"] for r in SEED}):
        part = sorted([{"g": r["g"], "v": r["v"], "w": r["w"]} for r in SEED if r["g"] == g],
                      key=lambda r: r["v"])
        ranks = oracle._window(part, "v", fn)
        out += [{**row, "r": rank} for row, rank in zip(part, ranks)]
    return out


MODEL = """###Pure
Class rel::P
{
   k: String[1];
   g: String[1];
   v: Integer[1];
   w: String[1];
}

###Relational
Database rel::DB
(
   Table T ( K VARCHAR(10) PRIMARY KEY, G VARCHAR(10), V INTEGER, W VARCHAR(10) )
)

###Mapping
Mapping rel::M
(
   rel::P: Relational
   {
      ~primaryKey ( [rel::DB]T.K )
      ~mainTable [rel::DB]T
      k: [rel::DB]T.K,
      g: [rel::DB]T.G,
      v: [rel::DB]T.V,
      w: [rel::DB]T.W
   }
)

###Connection
RelationalDatabaseConnection rel::Conn
{ store: rel::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime rel::RT
{ mappings: [ rel::M ]; connections: [ rel::DB: [ env: rel::Conn ] ]; }

###Data
Data rel::Seed
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

SERVICE = """Service rel::S_{name}
{{
   pattern: '/rel/{name}';
   documentation: 'The relation operation {name}, against an independently computed result.';
   execution: Single
   {{
      query: {query};
      mapping: rel::M;
      runtime: rel::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ rel::Seed }}# ] ]
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
        SERVICE.format(name=name, query=query,
                       data=json.dumps(expected).replace("'", "\\'"))
        for name, query, expected in cs)


# Operations known to fail, with the finding that explains each. Kept in the case list rather
# than deleted: a probe that drops what it cannot pass stops being a measurement. They are
# reported, and excluded from the evidence file so the scoreboard does not count them.
KNOWN_BAD = {
    "first": "F41 -- returns the whole relation instead of the first row",
    "last": "F42 -- generates SQL that builds a list of mixed types, which the database rejects",
}


def main() -> None:
    cs = cases()
    work = Path(tempfile.mkdtemp())
    src = work / "rel.pure"
    src.write_text(build(cs))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        # The runner has no auto-discovery: a service is only run if it is NAMED. Passing the
        # file alone compiles everything and runs nothing, and reports "0 total" rather than
        # an error, which is a quiet way to believe a probe passed.
        + [f"--testable=rel::S_{name}" for name, _q, _e in cs],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr

    fatal = re.search(r"EngineException: ([^\n]{0,300})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        print(f"\n  source kept at {src}")
        return

    good, bad = [], []
    for name, _q, _e in cs:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        verdict = m.group(1) if m else ("PASS" if f"S_{name}_suite" in out
                                        and "FAIL" not in out else "?")
        (good if verdict == "PASS" else bad).append((name, verdict))
        if verdict != "PASS":
            why = KNOWN_BAD.get(name, "")
            print(f"  {name:<24} {verdict:<6} {why}")
    if not bad:
        print(out[-400:] if not good else "")
    print(f"\n{len(good)} of {len(cs)} relation operations execute and agree with the oracle")
    if bad:
        unexplained = [n for n, _v in bad if n not in KNOWN_BAD]
        print(f"  {len(bad)} did not: " + ", ".join(n for n, _v in bad))
        if unexplained:
            print(f"  {len(unexplained)} of them with no recorded finding: "
                  + ", ".join(unexplained))
        print(f"\n  source kept at {src}")
    _merge_evidence([n for n, _v in good])


def _merge_evidence(names) -> None:
    import probe_functions

    f = probe_functions.EVIDENCE
    rows = {}
    if f.exists():
        rows = dict(line.split("\t", 1)
                    for line in f.read_text().splitlines()[1:] if "\t" in line)
    for n in names:
        rows[n] = "relation-probe"
    f.write_text("\n".join(["function\tevidence"]
                           + [f"{k}\t{v}" for k, v in sorted(rows.items())]) + "\n")


if __name__ == "__main__":
    main()
