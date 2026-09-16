"""
Which AGGREGATE functions execute, one column per function over one known group.

probe_functions.py reaches everything callable from a property mapping. That leaves out an
entire kind of function: the aggregates only reachable from `groupBy`, which is roughly the
whole statistics half of the registry -- average, median, mode, the four variance spellings,
correlation, covariance, the percentiles.

Same discipline as the scalar probe. One column per function, the whole set run at once, and
whatever the engine names gets dropped and the rest retried, so a refusal identifies itself
instead of taking the batch down with it. Expected values come from the oracle over the same
four numbers, never from the engine.

The seed is four rows in ONE group, chosen so no two aggregates coincide:

    V = 1, 2, 3, 4        W = 2.0, 5.0, 6.0, 11.0

Values that make the answers distinguishable matter more here than anywhere else in the
corpus. Over 1,2,3,4 the mean and the median are both 2.5, so a `median` wired to `average`
would pass; over a symmetric pair of columns the two covariance spellings collide, and the
population and sample variances differ only by a factor of n/(n-1) that a single well-chosen
row makes visible. W is skewed for exactly that reason.
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

V = [1, 2, 3, 4]
W = [2.0, 5.0, 6.0, 11.0]

# How a case's aggregate argument is built. The default pairs nothing -- `x|$x.v` -- and the
# two-column aggregates need the columns paired into one value first.
_RM = "meta::pure::functions::math::mathUtility::rowMapper($x.v, $x.w)"
# The oracle's two-column aggregates take ONE argument that is a list of pairs, mirroring what
# rowMapper builds on the Pure side -- not two parallel lists.
PAIRS = list(zip(V, W))
_WM = "meta::pure::functions::math::mathUtility::wavgRowMapper($x.v, $x.w)"

# (name, Pure return type, the aggregate expression, oracle args[, post])
#
# `post` adjusts the ORACLE side when the Pure expression composes something onto the
# function being probed. `distinct()` returns a collection, which is not a legal aggregate
# column, so the expression has to end `->size()` -- and the oracle then has to take the size
# too or the comparison is between a count and a list. Without this the probe reported
# `distinct` as a disagreement, which would have been my expression's fault reported as the
# engine's.
CASES = [
    ("count", "Integer", "agg|$agg->count()", [V]),
    ("sum", "Integer", "agg|$agg->sum()", [V]),
    ("average", "Float", "agg|$agg->average()", [V]),
    ("max", "Integer", "agg|$agg->max()", [V]),
    ("min", "Integer", "agg|$agg->min()", [V]),
    ("median", "Float", "agg|$agg->median()", [V]),
    ("mode", "Integer", "agg|$agg->mode()", [V]),
    ("stdDevPopulation", "Float", "agg|$agg->stdDevPopulation()", [V]),
    ("stdDevSample", "Float", "agg|$agg->stdDevSample()", [V]),
    ("variancePopulation", "Float", "agg|$agg->variancePopulation()", [V]),
    ("varianceSample", "Float", "agg|$agg->varianceSample()", [V]),
    # 0.5, not 50.0. The argument is passed to SQL unchanged and DuckDB rejects anything
    # outside [0, 1] -- "PERCENTILEs can only take parameters in the range [0, 1]". So the
    # relational contract is a fraction, whatever the Pure signature suggests.
    ("percentile", "Float", "agg|$agg->percentile(0.5)", [V, 0.5]),
    ("isDistinct", "Boolean", "agg|$agg->isDistinct()", [V]),
    # isEmpty/isNotEmpty are NOT probed as aggregates: in that position they lower to the
    # per-row expression `"root".V is null`, which lands unaggregated in a grouped select and
    # the database rejects the statement (F40). They work in the to-many position, where the
    # corpus exercises them across 40 services.
    ("distinct", "Integer", "agg|$agg->distinct()->size()", [V], len),
    # Two-column aggregates. They do not take two arguments -- they take ONE argument built
    # by `rowMapper`, which pairs the columns. So probing corr also probes rowMapper, and
    # probing wavg also probes wavgRowMapper; neither of those two has any other call site.
    ("corr", "Float", "agg|$agg->corr()", [PAIRS], None, _RM),
    ("covarSample", "Float", "agg|$agg->covarSample()", [PAIRS], None, _RM),
    ("covarPopulation", "Float", "agg|$agg->covarPopulation()", [PAIRS], None, _RM),
    ("wavg", "Float", "agg|$agg->wavg()", [PAIRS], None, _WM),
    # `variance` is NOT probed as an aggregate. Its DuckDB lowering is
    #     if(last == 'true', 'list_var_samp', 'list_var_pop') + '([' + args + '])'
    # -- a LIST function over a per-row array, so in a grouped select the column arrives
    # unaggregated and the database rejects the statement, exactly as in F40.
    #
    # Unlike F40 this is not filed as a defect. The lowering handles a list deliberately, so
    # this spelling is for a collection VALUE, and the aggregate spellings are
    # varianceSample and variancePopulation -- both of which are probed above and both of
    # which pass. Where the corpus cannot tell a defect from its own misuse of a signature,
    # it says so rather than picking the flattering reading.

    # maxBy/minBy take two PARALLEL collections in the oracle -- values and keys -- while
    # corr and friends take one list of pairs. Both are fed by the same rowMapper on the Pure
    # side, so the shape difference lives entirely in the oracle's own signatures.
    ("maxBy", "Integer", "agg|$agg->maxBy()", [V, W], None, _RM),
    ("minBy", "Integer", "agg|$agg->minBy()", [V, W], None, _RM),
    ("joinStrings", "String", "agg|$agg->joinStrings('-')",
     [["p", "q", "r", "s"], "-"], None, "$x.s"),
    ("size", "Integer", "agg|$agg->size()", [V]),
    ("first", "Integer", "agg|$agg->first()", [V]),
    ("last", "Integer", "agg|$agg->last()", [V]),
    ("removeDuplicates", "Integer", "agg|$agg->removeDuplicates()->size()", [V], len),
    ("reverse", "Integer", "agg|$agg->reverse()->first()", [V], lambda r: r[0]),
    ("sort", "Integer", "agg|$agg->sort()->first()", [V], lambda r: r[0]),
]

MODEL = """Class agg::P
{{
   k: String[1];
   g: String[1];
   s: String[1];
   v: Integer[1];
   w: Float[1];
}}

###Relational
Database agg::DB
(
   Table T ( K VARCHAR(10) PRIMARY KEY, G VARCHAR(10), S VARCHAR(10), V INTEGER, W DOUBLE )
)

###Mapping
Mapping agg::M
(
   agg::P: Relational
   {{
      ~primaryKey ( [agg::DB]T.K )
      ~mainTable [agg::DB]T
      k: [agg::DB]T.K,
      g: [agg::DB]T.G,
      s: [agg::DB]T.S,
      v: [agg::DB]T.V,
      w: [agg::DB]T.W
   }}
)

###Connection
RelationalDatabaseConnection agg::Conn
{{ store: agg::DB; type: DuckDB; specification: DuckDB {{ }}; auth: Test; }}
###Runtime
Runtime agg::RT {{ mappings: [ agg::M ]; connections: [ agg::DB: [ env: agg::Conn ] ]; }}

###Data
Data agg::Seed
{{
  Relational
  #{{
    default.T:
      'K,G,S,V,W\\n' +
      'R1,g,p,1,2.0\\n' +
      'R2,g,q,2,5.0\\n' +
      'R3,g,r,3,6.0\\n' +
      'R4,g,s,4,11.0\\n';
  }}#
}}

###Service
Service agg::Svc
{{
   pattern: '/agg/svc';
   documentation: 'One aggregate column per registered aggregate, over a single known group.';
   execution: Single
   {{
      query: |agg::P.all()->project(~[g:x|$x.g, s:x|$x.s, v:x|$x.v, w:x|$x.w])
                 ->groupBy(~[g], ~[
{aggs}
                 ]);
      mapping: agg::M;
      runtime: agg::RT;
   }}
   testSuites:
   [
      Svc_suite:
      {{
         data: [ connections: [ env: Reference #{{ agg::Seed }}# ] ]
         tests:
         [
            expected_rows:
            {{
               serializationFormat: PURE_TDSOBJECT;
               asserts: [ a: EqualToJson #{{ expected: ExternalFormat #{{
                   contentType: 'application/json'; data: '[]'; }}#; }}# ]
            }}
         ]
      }}
   ]
}}
"""


def build(cases) -> str:
    aggs = ",\n".join(
        f"                    f{i}: x|{c[5] if len(c) > 5 else '$x.v'} : {c[2]}"
        for i, c in enumerate(cases))
    return MODEL.format(aggs=aggs)


def attempt(cases) -> tuple[str, str]:
    work = Path(tempfile.mkdtemp())
    tmp = work / "agg.pure"
    tmp.write_text(build(cases))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain",
         str(tmp), "--testable=agg::Svc", f"--dump={work}"],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
        cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr
    dumps = sorted(work.glob("*.actual.json"))
    if dumps:
        return "ok", dumps[0].read_text()
    for pat, why in (
            (r"The function '(\w+)'.{0,80}?is not supported yet", "not supported yet"),
            (r"No SQL translation exists for the PURE function '([\w:]+)'", "no SQL translation"),
            (r"dyna function \[(\w+)\] is not registered", "not in DynaFunctionRegistry"),
            (r"Can't find a match for function '([\w:]+)", "no matching signature"),
            (r"The system can't find the function '([\w:]+)", "unknown function")):
        m = re.search(pat, out, re.S)
        if m:
            return m.group(1).split("::")[-1], why
    m = re.search(r"(?:EngineException|Caused by)[^\n]*", out)
    return "ERROR", (m.group(0) if m else out[-300:])


def bisect_error(cases):
    """Halve a set that fails for a reason no message attributes to a function."""
    if len(cases) == 1:
        return [], cases
    mid = len(cases) // 2
    good, bad = [], []
    for half in (cases[:mid], cases[mid:]):
        outcome, _d = attempt(half)
        if outcome == "ok":
            good += half
        else:
            g, b = bisect_error(half)
            good += g
            bad += b
    return good, bad


def _same(a, b) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a) == bool(b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b or abs(a - b) <= 1e-9 * max(abs(a), abs(b), 1.0)
    return str(a) == str(b)


def compare(cases, actual: str) -> list:
    import oracle
    try:
        row = json.loads(actual[actual.index("["):])[0]
    except (ValueError, IndexError, json.JSONDecodeError) as exc:
        print(f"  could not parse the engine result: {exc}")
        return []
    agree, differ, nooracle = [], [], []
    for i, c in enumerate(cases):
        name, args = c[0], c[3]
        # `or` rather than a length check: the two-column cases carry an explicit None in
        # this slot so the argument-builder can follow it, and calling None raised TypeError
        # for every one of them -- which the probe then reported as "no oracle value",
        # blaming the oracle for the probe's own tuple.
        post = (c[4] if len(c) > 4 else None) or (lambda x: x)
        engine = row.get(f"f{i}")
        try:
            mine = post(oracle._dynafunction(name, args))
        except Exception as exc:
            nooracle.append((name, type(exc).__name__))
            continue
        if _same(engine, mine):
            agree.append(name)
        else:
            differ.append((name, engine, mine))
    print(f"\n  oracle agrees with the engine on {len(agree)} of {len(cases)}")
    if nooracle:
        print(f"  no oracle value for {len(nooracle)}: "
              + ", ".join(f"{n} ({w})" for n, w in nooracle))
    for name, engine, mine in differ:
        print(f"    DISAGREE  {name:<22} engine={engine!r:<24} oracle={mine!r}")
    # Returns BOTH the disagreements and the ones the oracle could not answer. A function the
    # engine ran while the oracle raised is not evidence of agreement -- counting it as
    # executed would let a broken oracle call improve the scoreboard.
    return differ, [n for n, _w in nooracle]


def main() -> None:
    cases, rejected, differ = list(CASES), [], []
    for _round in range(len(CASES) + 1):
        outcome, detail = attempt(cases)
        if outcome == "ok":
            print(f"{len(cases)} aggregates EXECUTE in a relation groupBy.")
            diffs, unanswered = compare(cases, detail)
            differ = [d[0] for d in diffs] + unanswered
            break
        if outcome == "ERROR":
            print(f"  unattributed failure, bisecting: {detail[:110]}", flush=True)
            cases, dropped = bisect_error(cases)
            for c in dropped:
                rejected.append((c[0], "fails with no message naming it"))
                print(f"  REJECTED  {c[0]:<22} fails with no message naming it", flush=True)
            if not cases:
                break
            continue
        rejected.append((outcome, detail))
        print(f"  REJECTED  {outcome:<22} {detail}", flush=True)
        cases = [c for c in cases if c[0] != outcome]

    ok = [c[0] for c in cases] if cases else []
    ok = [n for n in ok if n not in differ]
    # rowMapper and wavgRowMapper have no call site of their own -- they exist to pair the
    # columns that corr/covar/wavg consume. If those passed, these ran.
    paired = [n for n in ok if n in ("corr", "covarSample", "covarPopulation")]
    extra = (["rowMapper"] if paired else []) + (["wavgRowMapper"] if "wavg" in ok else [])
    _merge_evidence(ok + extra, [(n, w[:60]) for n, w in rejected])
    print(f"\n  {len(ok)} aggregates executed and agreeing; "
          f"{len(rejected)} rejected, {len(differ)} disagreeing")


def _merge_evidence(names, failing=()) -> None:
    import probe_functions

    probe_functions.merge_evidence("aggregate", sorted(set(names)), failing)


if __name__ == "__main__":
    main()
