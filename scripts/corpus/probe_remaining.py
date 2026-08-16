"""
The long tail: every registry name the other five probes cannot reach.

What is left after scalars, aggregates, relation ops, TDS ops and collection ops is a
miscellany that shares only the property of needing something the other probes' models do not
have -- an enum, a subclass, a milestoned class, a Map. So this file carries one model with
all of it and runs one service per remaining name.

Most of these are expected to REFUSE, and that is the point. The map functions (`newMap`,
`put`, `keys`, `values`) are in-memory structures with no SQL to lower to; `getAllVersions`
needs milestoning; `tableToTDS` reads a table directly rather than through a mapping. A
refusal recorded with its message is a complete answer -- what the burndown cannot accept is a
name nobody has ever put in front of the engine.

The bar stays where it was for the names that DO execute: the expected value is computed here
and compared, never read back from the engine.
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

MODEL = """###Pure
Enum rem::Colour
{
   RED, GREEN
}

Class rem::P
{
   k: String[1];
   g: String[1];
   v: Integer[1];
}

Class rem::Special extends rem::P
{
   extra: String[0..1];
}

// Business-temporal, for the three getAllVersions* spellings. Milestoning is the only reason
// those functions exist, so probing them without it would only ever record a signature error.
Class <<temporal.businesstemporal>> rem::Versioned
{
   k: String[1];
   v: Integer[1];
}

###Relational
Database rem::DB
(
   Table T ( K VARCHAR(10) PRIMARY KEY, G VARCHAR(10), V INTEGER, C VARCHAR(10) )
   Table VT ( K VARCHAR(10) PRIMARY KEY, V INTEGER, FROM_Z DATE, THRU_Z DATE )
   View TV ( ~filter ACTIVE k: T.K, v: T.V )
   Filter ACTIVE ( T.V > 0 )
)

###Mapping
Mapping rem::M
(
   rem::P: Relational
   {
      ~primaryKey ( [rem::DB]T.K )
      ~mainTable [rem::DB]T
      k: [rem::DB]T.K,
      g: [rem::DB]T.G,
      v: [rem::DB]T.V
   }

   rem::Versioned: Relational
   {
      ~primaryKey ( [rem::DB]VT.K )
      ~mainTable [rem::DB]VT
      k: [rem::DB]VT.K,
      v: [rem::DB]VT.V
   }

   rem::Colour: EnumerationMapping ColourMap
   {
      RED: 'red',
      GREEN: 'green'
   }
)

###Connection
RelationalDatabaseConnection rem::Conn
{ store: rem::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime rem::RT
{ mappings: [ rem::M ]; connections: [ rem::DB: [ env: rem::Conn ] ]; }

###Data
Data rem::Seed
{
  Relational
  #{
    default.T:
      'K,G,V,C\\n' +
      'R1,a,3,red\\n' +
      'R2,a,1,green\\n' +
      'R3,b,4,red\\n' +
      'R4,b,1,green\\n';
    default.VT:
      'K,V,FROM_Z,THRU_Z\\n' +
      'V1,10,2024-01-01,2024-06-30\\n' +
      'V2,20,2024-07-01,2999-12-31\\n';
  }#
}

###Service
"""

SERVICE = """Service rem::S_{name}
{{
   pattern: '/rem/{name}';
   documentation: 'The registry name {name}, put in front of the engine.';
   execution: Single
   {{
      query: {query};
      mapping: rem::M;
      runtime: rem::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ rem::Seed }}# ] ]
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

ROWS = [{"g": "a", "v": 3}, {"g": "a", "v": 1}, {"g": "b", "v": 4}, {"g": "b", "v": 1}]
SORTED = sorted(ROWS, key=lambda r: (r["v"], r["g"]))

# (name, query, expected rows). One service each; a name that refuses is recorded with the
# engine's message rather than dropped.
CASES = [
    # `.all()` desugars to getAll, so this one is exercised by every service in the corpus --
    # but never NAMED, which is why it read as untouched. Written explicitly here.
    ("getAll", "|rem::P.getAll()->project(~[g:x|$x.g, v:x|$x.v])"
               "->sort([~v->ascending(), ~g->ascending()])", SORTED),
    ("range", "|rem::P.all()->project(~[g:x|$x.g, n:x|range(1, 4, 1)->size()])"
              "->sort(~g->ascending())",
     [{"g": r["g"], "n": 3} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("list", "|rem::P.all()->project(~[g:x|$x.g, n:x|list([1, 2])->size()])"
             "->sort(~g->ascending())",
     [{"g": r["g"], "n": 2} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("newMap", "|rem::P.all()->project(~[g:x|$x.g, n:x|newMap([pair('a', 1)])->keys()->size()])"
               "->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("keys", "|rem::P.all()->project(~[g:x|$x.g, n:x|newMap([pair('a', 1)])->keys()->size()])"
             "->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("values", "|rem::P.all()->project(~[g:x|$x.g, "
               "n:x|newMap([pair('a', 1)])->values()->size()])->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("get", "|rem::P.all()->project(~[g:x|$x.g, "
            "n:x|newMap([pair('a', 1)])->get('a')->toOne()])->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("put", "|rem::P.all()->project(~[g:x|$x.g, "
            "n:x|newMap([pair('a', 1)])->put(pair('b', 2))->keys()->size()])"
            "->sort(~g->ascending())",
     [{"g": r["g"], "n": 2} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("putAll", "|rem::P.all()->project(~[g:x|$x.g, "
               "n:x|newMap([pair('a', 1)])->putAll([pair('b', 2)])->keys()->size()])"
               "->sort(~g->ascending())",
     [{"g": r["g"], "n": 2} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("flatten", "|rem::P.all()->project(~[g:x|$x.g, n:x|[[1, 2], [3]]->flatten()->size()])"
                "->sort(~g->ascending())",
     [{"g": r["g"], "n": 3} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("extractEnumValue", "|rem::P.all()->project(~[g:x|$x.g, "
                         "c:x|extractEnumValue(rem::Colour, 'RED')->toString()])"
                         "->sort(~g->ascending())",
     [{"g": r["g"], "c": "RED"} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("subType", "|rem::P.all()->project(~[g:x|$x.g, "
                "n:x|$x->subType(@rem::Special).extra])->sort(~g->ascending())",
     [{"g": r["g"], "n": None} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("tdsRows", "|rem::P.all()->project([x|$x.g, x|$x.v], ['g', 'v'])->tdsRows()->size()", []),
    ("tableToTDS", "|rem::DB->tableToTDS('T')->restrict(['G'])", []),
    ("viewToTDS", "|rem::DB->viewToTDS('TV')->restrict(['k'])", []),
    ("getAllVersions", "|rem::Versioned.allVersions()->project(~[v:x|$x.v])"
                       "->sort(~v->ascending())", [{"v": 10}, {"v": 20}]),
    ("getAllVersionsInRange",
     "|rem::Versioned.allVersionsInRange(%2024-01-01, %2024-12-31)->project(~[v:x|$x.v])", []),
    ("getAllForEachDate",
     "|rem::Versioned.all([%2024-06-03])->project(~[v:x|$x.v])", []),
    ("whenSubType", "|rem::P.all()->project(~[g:x|$x.g, "
                    "n:x|$x->whenSubType(@rem::Special).extra])->sort(~g->ascending())",
     [{"g": r["g"], "n": None} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("firstNotNull", "|rem::P.all()->project(~[g:x|$x.g, "
                     "n:x|firstNotNull([1, 2])])->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("tdsContains", "|rem::P.all()->project([x|$x.g], ['g'])"
                    "->tdsContains([x|$x.g], ['g'], "
                    "rem::P.all()->project([x|$x.g], ['g']))", []),
    ("groupByWithWindowSubset",
     "|rem::P.all()->project([x|$x.g, x|$x.v], ['g', 'v'])"
     "->groupByWithWindowSubset(['g'], [agg('t', x|$x.getInteger('v'), y|$y->sum())], ['g'])",
     []),
    ("to", "|rem::P.all()->project(~[g:x|$x.g, n:x|toVariant($x.v)->to(@Integer)])"
           "->sort(~g->ascending())",
     [{"g": r["g"], "n": r["v"]} for r in sorted(ROWS, key=lambda r: (r["g"], r["v"]))]),
    ("toMany", "|rem::P.all()->project(~[g:x|$x.g, "
               "n:x|toVariant($x.v)->toMany(@Integer)->size()])->sort(~g->ascending())",
     [{"g": r["g"], "n": 1} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("eval", "|rem::P.all()->project(~[g:x|$x.g, n:x|{a:Integer[1]|$a + 1}->eval(1)])"
             "->sort(~g->ascending())",
     [{"g": r["g"], "n": 2} for r in sorted(ROWS, key=lambda r: r["g"])]),
    ("save", "|rem::P.all()->project(~[g:x|$x.g])->save()", []),
    ("graphFetch", "|rem::P.all()->graphFetch(#{rem::P{g}}#)->serialize(#{rem::P{g}}#)", []),
    ("graphFetchChecked",
     "|rem::P.all()->graphFetchChecked(#{rem::P{g}}#)->serialize(#{rem::P{g}}#)", []),
    ("objectReferenceIn", "|rem::P.all()->project(~[g:x|$x.g, "
                          "n:x|$x->objectReferenceIn(['a'])])->sort(~g->ascending())", []),
]


def build(cs) -> str:
    return MODEL + "".join(
        SERVICE.format(name=n, query=q, data=json.dumps(e).replace("'", "\\'"))
        for n, q, e in cs)


def run_one(case) -> tuple[str, str]:
    work = Path(tempfile.mkdtemp())
    src = work / "rem.pure"
    src.write_text(build([case]))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src),
         f"--testable=rem::S_{case[0]}"],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    if re.search(rf"PASS\s+S_{case[0]}_suite", out):
        return "ok", "agrees with the oracle"
    for pat, why in (
            (r"Function does not exist '(\w+)", "the function does not exist"),
            (r"The function '(\w+)'.{0,80}?is not supported yet", "not supported yet"),
            (r"dyna function \[(\w+)\] is not registered", "not in the DynaFunctionRegistry"),
            (r"No SQL translation exists for the PURE function '([\w:]+)'", "no SQL translation"),
            (r"Can't find a match for function '(\w+)", "no matching signature"),
            (r"(?:Assert failure|Execution error|EngineException)[^\n\"]{0,60}\"([^\"]{0,110})",
             "engine error")):
        m = re.search(pat, out, re.S)
        if m:
            return "refused", f"{why}: {m.group(1)[:100]}"
    if re.search(rf"FAIL\s+S_{case[0]}_suite", out):
        m = re.search(r"actual\s*:\s*(.*)", out)
        return "differs", (m.group(1)[:120] if m else "differs from the oracle")
    # Last resort: the first line that looks like a diagnosis, from ANYWHERE in the output.
    # Slicing the tail was returning SLF4J's startup notice for every short failure, which
    # told me nothing eleven times in a row.
    # The MESSAGE, not the exception class. "Exception in thread main
    # org.finos...EngineException" is the same 90 characters every time and says nothing; what
    # matters is the text after the colon, and for a test ERROR the line that follows it.
    lines = out.splitlines()
    for i, line in enumerate(lines):
        s = line.strip()
        if "slf4j" in s.lower():
            continue
        if s.startswith("ERROR ") and i + 1 < len(lines):
            return "refused", " ".join(lines[i + 1].split())[:150]
        m = re.search(r"(?:Exception|Caused by)[^:]*:\s*(.+)", s)
        if m:
            return "refused", m.group(1)[:150]
    return "?", " ".join(out.split())[-160:]


def main() -> None:
    good, other = [], []
    for case in CASES:
        outcome, detail = run_one(case)
        if outcome == "ok":
            good.append(case[0])
        else:
            other.append((case[0], f"{outcome}: {detail}"))
        print(f"  {case[0]:<20} {outcome:<8} {detail[:96]}", flush=True)

    import probe_functions
    probe_functions.merge_evidence("remaining", sorted(good),
                                   [(n, w[:60]) for n, w in other])
    print(f"\n{len(good)} of {len(CASES)} execute and agree; "
          f"{len(other)} recorded with the engine's answer")


if __name__ == "__main__":
    main()
