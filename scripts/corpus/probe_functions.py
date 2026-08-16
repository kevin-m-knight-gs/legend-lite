"""
Which registered functions actually EXECUTE, one minimal service per function.

`getSupportedFunctions()` is the map the engine consults before reporting "No SQL translation
exists", so it looks like the definitive list of what a query may contain. It over-reports.
`previousDayOfWeek` is in it, and using one fails at execution with

    [unsupported-api] The function 'previousDayOfWeek' (state: [Select, false])
    is not supported yet

-- registered, routed, and then refused by the thing it routes to. A registry entry means a
handler was bound, not that the handler works in the position you used it.

So this probes each function the oracle implements, in a real mapping, against a real
connection, and reports three outcomes:

    RUNS        the engine produced a value
    UNSUPPORTED the engine refused it, with its message
    MISMATCH    both produced a value and they disagree

MISMATCH is the interesting one and is NOT reported as a defect here. The oracle is an
independent implementation, so a disagreement means one of the two is wrong and the probe
cannot say which -- that adjudication is a person's job, and the corpus has been wrong at
least as often as the engine (concat over NULL, `!=` over NULL, the all-NULL empty string).

Run it before widening the combination matrix. A function that cannot execute has no business
in the matrix, and finding that out from a 980-cell service failing is far more expensive
than finding it out from one probe.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as runner  # noqa: E402

# (function, Pure return type, expression over the probe table, oracle args)
# The probe table carries one row with values chosen so no function meets an argument it
# would refuse: positive numbers, a non-empty string, a real date.
CASES = [
    ("toUpper", "String", "toUpper(T.S)", ["alpha"]),
    ("toLower", "String", "toLower(T.S)", ["alpha"]),
    ("trim", "String", "trim(T.S)", ["alpha"]),
    ("ltrim", "String", "ltrim(T.S)", ["alpha"]),
    ("rtrim", "String", "rtrim(T.S)", ["alpha"]),
    ("reverseString", "String", "reverseString(T.S)", ["alpha"]),
    ("length", "Integer", "length(T.S)", ["alpha"]),
    ("substring", "String", "substring(T.S, 2, 4)", ["alpha", 2, 4]),
    ("indexOf", "Integer", "indexOf(T.S, 'ph')", ["alpha", "ph"]),
    ("lpad", "String", "lpad(T.S, 8, 'x')", ["alpha", 8, "x"]),
    ("rpad", "String", "rpad(T.S, 8, 'x')", ["alpha", 8, "x"]),
    ("replace", "String", "replace(T.S, 'a', 'z')", ["alpha", "a", "z"]),
    ("splitPart", "String", "splitPart(T.S, 'l', 1)", ["alpha", "l", 1]),
    ("left", "String", "left(T.S, 3)", ["alpha", 3]),
    ("right", "String", "right(T.S, 3)", ["alpha", 3]),
    ("concat", "String", "concat(T.S, T.S2)", ["alpha", "beta"]),
    ("coalesce", "String", "coalesce(T.S, T.S2)", ["alpha", "beta"]),
    ("startsWith", "Boolean", "startsWith(T.S, 'al')", ["alpha", "al"]),
    ("endsWith", "Boolean", "endsWith(T.S, 'ha')", ["alpha", "ha"]),
    ("contains", "Boolean", "contains(T.S, 'ph')", ["alpha", "ph"]),
    ("isAlphaNumeric", "Boolean", "isAlphaNumeric(T.S)", ["alpha"]),
    ("isNull", "Boolean", "isNull(T.S)", ["alpha"]),
    ("isNotNull", "Boolean", "isNotNull(T.S)", ["alpha"]),
    ("not", "Boolean", "not(T.B)", [True]),
    ("ascii", "Integer", "ascii(T.S)", ["alpha"]),
    ("encodeBase64", "String", "encodeBase64(T.S)", ["alpha"]),
    ("decodeBase64", "String", "decodeBase64(T.S64)", ["YWxwaGE="]),
    ("levenshteinDistance", "Integer", "levenshteinDistance(T.S, T.S2)", ["alpha", "beta"]),
    ("abs", "Integer", "abs(T.I)", [7]),
    ("sign", "Integer", "sign(T.I)", [7]),
    ("plus", "Integer", "plus(T.I, T.J)", [7, 3]),
    ("minus", "Integer", "minus(T.I, T.J)", [7, 3]),
    ("times", "Integer", "times(T.I, T.J)", [7, 3]),
    ("mod", "Integer", "mod(T.I, T.J)", [7, 3]),
    ("rem", "Float", "rem(T.I, T.J)", [7, 3]),
    ("divide", "Float", "divide(T.F, T.G)", [10.0, 4.0]),
    ("sqrt", "Float", "sqrt(T.F)", [10.0]),
    ("cbrt", "Float", "cbrt(T.F)", [10.0]),
    ("exp", "Float", "exp(T.G)", [4.0]),
    ("log", "Float", "log(T.F)", [10.0]),
    ("log10", "Float", "log10(T.F)", [10.0]),
    ("pow", "Float", "pow(T.G, T.G)", [4.0, 4.0]),
    ("round", "Integer", "round(T.F)", [10.0]),
    ("ceiling", "Integer", "ceiling(T.F)", [10.0]),
    ("floor", "Integer", "floor(T.F)", [10.0]),
    ("sin", "Float", "sin(T.F)", [10.0]),
    ("cos", "Float", "cos(T.F)", [10.0]),
    ("tan", "Float", "tan(T.F)", [10.0]),
    ("atan", "Float", "atan(T.F)", [10.0]),
    ("atan2", "Float", "atan2(T.F, T.G)", [10.0, 4.0]),
    ("toDecimal", "Float", "toDecimal(T.I)", [7]),
    ("toFloat", "Float", "toFloat(T.I)", [7]),
    ("toString", "String", "toString(T.I)", [7]),
    ("bitAnd", "Integer", "bitAnd(T.I, T.J)", [7, 3]),
    ("bitOr", "Integer", "bitOr(T.I, T.J)", [7, 3]),
    ("bitXor", "Integer", "bitXor(T.I, T.J)", [7, 3]),
    ("bitNot", "Integer", "bitNot(T.I)", [7]),
    ("bitShiftLeft", "Integer", "bitShiftLeft(T.I, T.J)", [7, 3]),
    ("bitShiftRight", "Integer", "bitShiftRight(T.I, T.J)", [7, 3]),
    ("year", "Integer", "year(T.D)", ["2024-06-03"]),
    ("monthNumber", "Integer", "monthNumber(T.D)", ["2024-06-03"]),
    ("dayOfMonth", "Integer", "dayOfMonth(T.D)", ["2024-06-03"]),
    ("dayOfYear", "Integer", "dayOfYear(T.D)", ["2024-06-03"]),
    ("dayOfWeekNumber", "Integer", "dayOfWeekNumber(T.D)", ["2024-06-03"]),
    ("weekOfYear", "Integer", "weekOfYear(T.D)", ["2024-06-03"]),
    ("quarterNumber", "Integer", "quarterNumber(T.D)", ["2024-06-03"]),
    ("hour", "Integer", "hour(T.TS)", ["2024-06-03 19:15:00"]),
    ("minute", "Integer", "minute(T.TS)", ["2024-06-03 19:15:00"]),
    ("second", "Integer", "second(T.TS)", ["2024-06-03 19:15:00"]),
    ("datePart", "StrictDate", "datePart(T.TS)", ["2024-06-03 19:15:00"]),
    ("firstDayOfMonth", "StrictDate", "firstDayOfMonth(T.D)", ["2024-06-03"]),
    ("firstDayOfYear", "StrictDate", "firstDayOfYear(T.D)", ["2024-06-03"]),
    ("firstDayOfQuarter", "StrictDate", "firstDayOfQuarter(T.D)", ["2024-06-03"]),
    ("firstDayOfWeek", "StrictDate", "firstDayOfWeek(T.D)", ["2024-06-03"]),
    ("firstHourOfDay", "DateTime", "firstHourOfDay(T.TS)", ["2024-06-03 19:15:00"]),
    ("firstMinuteOfHour", "DateTime", "firstMinuteOfHour(T.TS)", ["2024-06-03 19:15:00"]),
    ("firstSecondOfMinute", "DateTime", "firstSecondOfMinute(T.TS)", ["2024-06-03 19:15:00"]),
    ("previousDayOfWeek", "StrictDate", "previousDayOfWeek(T.D, 'Monday')",
     ["2024-06-03", "Monday"]),
    ("mostRecentDayOfWeek", "StrictDate", "mostRecentDayOfWeek(T.D, 'Monday')",
     ["2024-06-03", "Monday"]),
    ("greatest", "Integer", "greatest(T.I, T.J)", [[7, 3]]),
    # -- second batch: trigonometry over a bounded operand, comparisons, parsing, and the
    # date functions whose extra argument is an enum or a format string. Anything the engine
    # refuses is dropped by the bisect, so a case that turns out not to be reachable from a
    # property mapping costs one round rather than a broken probe.
    ("acos", "Float", "acos(T.H)", [0.5]),
    ("asin", "Float", "asin(T.H)", [0.5]),
    ("cosh", "Float", "cosh(T.H)", [0.5]),
    ("sinh", "Float", "sinh(T.H)", [0.5]),
    ("tanh", "Float", "tanh(T.H)", [0.5]),
    ("cot", "Float", "cot(T.F)", [10.0]),
    ("char", "String", "char(T.I)", [7]),
    ("repeatString", "String", "repeatString(T.S, 2)", ["alpha", 2]),
    ("between", "Boolean", "between(T.I, 1, 9)", [7, 1, 9]),
    ("eq", "Boolean", "eq(T.I, T.J)", [7, 3]),
    ("equal", "Boolean", "equal(T.I, T.J)", [7, 3]),
    ("greaterThan", "Boolean", "greaterThan(T.I, T.J)", [7, 3]),
    ("greaterThanEqual", "Boolean", "greaterThanEqual(T.I, T.J)", [7, 3]),
    ("lessThan", "Boolean", "lessThan(T.I, T.J)", [7, 3]),
    ("lessThanEqual", "Boolean", "lessThanEqual(T.I, T.J)", [7, 3]),
    ("and", "Boolean", "and(T.B, T.B)", [True, True]),
    ("or", "Boolean", "or(T.B, T.B)", [True, True]),
    ("parseInteger", "Integer", "parseInteger(T.N)", ["42"]),
    ("parseFloat", "Float", "parseFloat(T.NF)", ["42.5"]),
    ("parseDecimal", "Float", "parseDecimal(T.NF)", ["42.5"]),
    ("parseBoolean", "Boolean", "parseBoolean(T.NB)", ["true"]),
    ("month", "Integer", "month(T.D)", ["2024-06-03"]),
    ("dayOfWeek", "String", "dayOfWeek(T.D)", ["2024-06-03"]),
    ("firstMillisecondOfSecond", "DateTime", "firstMillisecondOfSecond(T.TS)",
     ["2024-06-03 19:15:00"]),
    # The enum arguments are written as STRING LITERALS, not as `DurationUnit.DAYS`. Inside a
    # relational property mapping `X.Y` is a table-and-column reference, so an enum literal
    # there is read as a table -- "Can't find table 'DurationUnit'" -- and the fully qualified
    # form is a parse error. The lowerings strip quotes off the argument, so a literal is
    # what they expect. Nothing says so; it is visible only in the transform's source.
    ("dateDiff", "Integer", "dateDiff(T.D, T.D2, 'DAYS')",
     ["2024-06-03", "2024-06-13", "DAYS"]),
    ("adjust", "StrictDate", "adjust(T.D, 5, 'DAYS')", ["2024-06-03", 5, "DAYS"]),
    # ISO8601, not a format PATTERN. Relational formatDate accepts exactly two named formats
    # -- ISO8601 and ISO8601_NanoSecondPrecision -- in every dialect that implements it, and
    # `yyyy-MM-dd` fails with "Unsupported DateFormat". Consistent across dialects, so it is
    # a limitation of the relational lowering rather than a defect in one of them.
    ("formatDate", "String", "formatDate(T.D, 'ISO8601')", ["2024-06-03", "ISO8601"]),
    ("least", "Integer", "least(T.I, T.J)", [[7, 3]]),
]

MODEL = """Class probe::P
{{
   k: String[1];
{props}
}}

###Relational
Database probe::DB
(
   Table T ( K VARCHAR(20) PRIMARY KEY, S VARCHAR(50), S2 VARCHAR(50), S64 VARCHAR(50),
             N VARCHAR(20), NF VARCHAR(20), NB VARCHAR(20),
             I INTEGER, J INTEGER, F DOUBLE, G DOUBLE, H DOUBLE, B BIT,
             D DATE, D2 DATE, TS TIMESTAMP )
)

###Mapping
Mapping probe::M
(
   probe::P: Relational
   {{
      ~primaryKey ( [probe::DB]T.K )
      ~mainTable [probe::DB]T
      k: [probe::DB]T.K,
{maps}
   }}
)

###Connection
RelationalDatabaseConnection probe::Conn
{{ store: probe::DB; type: DuckDB; specification: DuckDB {{ }}; auth: Test; }}
###Runtime
Runtime probe::RT {{ mappings: [ probe::M ]; connections: [ probe::DB: [ env: probe::Conn ] ]; }}

###Data
Data probe::Seed
{{
  Relational
  #{{
    default.T:
      'K,S,S2,S64,N,NF,NB,I,J,F,G,H,B,D,D2,TS\\n' +
      'R1,alpha,beta,YWxwaGE=,42,42.5,true,7,3,10.0,4.0,0.5,true,'
        + '2024-06-03,2024-06-13,2024-06-03 19:15:00\\n';
  }}#
}}

###Service
Service probe::Svc
{{
   pattern: '/probe/svc';
   documentation: 'One column per registered function, so a refusal names the function.';
   execution: Single
   {{
      query: |probe::P.all()->project(~[{cols}]);
      mapping: probe::M;
      runtime: probe::RT;
   }}
   testSuites:
   [
      Svc_suite:
      {{
         data: [ connections: [ env: Reference #{{ probe::Seed }}# ] ]
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


def build(cases):
    props = "\n".join(f"   f{i}: {ret}[0..1];" for i, (_n, ret, _e, _a) in enumerate(cases))
    maps = ",\n".join(f"      f{i}: [probe::DB]{expr.replace('T.', 'T.')}"
                      for i, (_n, _r, expr, _a) in enumerate(cases))
    cols = ", ".join(f"f{i}:x|$x.f{i}" for i in range(len(cases)))
    return MODEL.format(props=props, maps=maps, cols=cols)


def attempt(cases) -> tuple[str, str]:
    """Run one probe. Returns (outcome, detail) where outcome is 'ok' or a function name."""
    work = Path(tempfile.mkdtemp())
    tmp = work / "probe.pure"
    tmp.write_text(build(cases))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    env = dict(os.environ, JAVA_HOME=runner.JAVA_HOME)
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain",
         str(tmp), "--testable=probe::Svc", f"--dump={work}"],
        capture_output=True, text=True, env=env, cwd=runner.RUNNER, timeout=3600)
    out = r.stdout + r.stderr
    m = re.search(r"The function '(\w+)'.{0,80}?is not supported yet", out, re.S)
    if m:
        return m.group(1), "not supported yet"
    m = re.search(r"No SQL translation exists for the PURE function '([\w:]+)'", out)
    if m:
        return m.group(1).split("::")[-1], "no SQL translation"
    # The console prints `actual` truncated at 300 characters, which is right for a human
    # reading a diff and useless for parsing 79 columns. --dump writes the payload whole.
    dumps = sorted(work.glob("*.actual.json"))
    if dumps:
        return "ok", dumps[0].read_text()
    m = re.search(r"actual\s*:\s*(.*)", out)
    if m:
        return "ok", m.group(1)
    # Compilation errors name the function they could not match, which is as good as a
    # refusal for the purpose of dropping it.
    m = re.search(r"Can't find a match for function '([\w:]+)", out)
    if m:
        return m.group(1).split("::")[-1], "no matching signature"
    m = re.search(r"(?:EngineException|Caused by)[^\n]*", out)
    return "ERROR", (m.group(0) if m else out[-300:])


def bisect_error(cases) -> tuple[list, list]:
    """Split a set that fails for a reason no message attributes to a function.

    Some failures name nothing usable -- a type mismatch deep in plan generation, a NULL
    pointer. Rather than stopping the whole probe on one bad case, halve the set and keep
    whichever halves run. Log2(n) runs finds every offender, which is the difference between
    a probe that grows by adding a line and one that has to be nursed through every addition.
    """
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
    """Equal allowing for PRESENTATION differences the probe is not asking about.

    A float rendered 3.1622776601683795 by one side and 3.16227766016838 by the other is the
    same answer at different precision, and a date rendered with or without a time component
    is the same instant. Neither is what this probe is looking for -- it is looking for a
    different ANSWER -- so both are normalised away rather than reported as disagreements
    that a person then has to dismiss one at a time.
    """
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a) == bool(b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        if a == b:
            return True
        scale = max(abs(a), abs(b), 1.0)
        return abs(a - b) <= 1e-9 * scale
    sa, sb = str(a), str(b)
    # A timestamp rendered '2024-06-03T19:00:00.000000000+0000' and one rendered
    # '2024-06-03 19:00:00' are the same instant. Normalise the separator and the trailing
    # zero fraction/offset rather than reporting three false disagreements a person then has
    # to dismiss -- the probe is looking for a different ANSWER, not a different format.
    def norm(s: str) -> str:
        s = s.replace("T", " ")
        s = re.sub(r"\.0+(?=$|[+-Z])", "", s)
        s = re.sub(r"(?:[+-]\d{4}|Z)$", "", s)
        return s.strip()
    # Compared for EQUALITY after normalisation, not by prefix. An earlier version allowed
    # either value to be a prefix of the other, to absorb a timestamp printed at lower
    # precision -- and it quietly passed `substring` where the engine said 'lpha' and the
    # oracle said 'lp'. A comparison loose enough to forgive formatting was loose enough to
    # forgive a wrong answer, which is the one thing the probe exists to catch.
    return norm(sa) == norm(sb)


def compare(cases, actual: str) -> list:
    """Engine value against oracle value, per function.

    A disagreement is NOT called a defect. The oracle is an independent implementation, so a
    mismatch says one of the two is wrong without saying which, and this corpus has been the
    wrong one at least as often as the engine -- concat over all-NULL, `!=` over NULL. What
    the probe can honestly do is put the two numbers next to each other and stop.
    """
    import json

    import oracle
    try:
        rows = json.loads(actual[actual.index("["):])
    except (ValueError, json.JSONDecodeError) as exc:
        print(f"  could not parse the engine result: {exc}")
        return []
    got = rows[0]
    agree, differ, nooracle = 0, [], []
    for i, (name, _ret, _expr, args) in enumerate(cases):
        engine = got.get(f"f{i}")
        try:
            mine = oracle._dynafunction(name, args)
        except Exception as exc:                        # Unsupported, or a real bug in mine
            nooracle.append((name, type(exc).__name__))
            continue
        if _same(engine, mine):
            agree += 1
        else:
            differ.append((name, engine, mine))
    print(f"\n  oracle agrees with the engine on {agree} of {len(cases)}")
    if nooracle:
        print(f"  no oracle value for {len(nooracle)}: "
              + ", ".join(f"{n} ({w})" for n, w in nooracle))
    if differ:
        print(f"\n  {len(differ)} DISAGREEMENTS -- one side is wrong and the probe cannot say")
        print("  which; each needs a person to adjudicate against the dialect's own rules:")
        for name, engine, mine in differ:
            print(f"    {name:<22} engine={engine!r:<28} oracle={mine!r}")
    return differ


EVIDENCE = Path(__file__).resolve().parents[2] / "docs/FUNCTIONS_EXECUTED.tsv"


def record(ran, rejected, differ=()) -> None:
    """Write the evidence file the function scoreboard reads.

    Written from what actually ran rather than maintained by hand, for the same reason the
    implemented/refused counts are read out of the oracle's registries: a list of "functions
    we have tested" that is edited separately from the testing drifts, and drifts in the
    flattering direction. A function leaves this file the moment it stops executing.

    The matrix contributes too. Its cells are asserted per-cell against independently
    computed values, which is the same standard the probe holds, so a function exercised
    there is as executed as one exercised here.
    """
    import combos

    rows = {name: "probe" for name, _r, _e, _a in ran}
    for x in set(combos._UNARY.values()) | set(combos._BINARY.values()):
        rows.setdefault(x, "matrix")
    for fn, _lit in combos._LITERAL_ARG.values():
        rows.setdefault(fn, "matrix")
    for name, why in rejected:
        rows[name] = f"REFUSED-BY-ENGINE ({why})"
    # A function that ran and produced the WRONG answer is not evidence of anything working.
    # It is recorded, because "we looked and it disagreed" is worth more than silence, but it
    # is not counted as executed -- otherwise the scoreboard would improve by finding bugs.
    for name in differ:
        rows[name] = "DISAGREES-WITH-ORACLE"
    lines = ["function\tevidence"] + [f"{k}\t{v}" for k, v in sorted(rows.items())]
    EVIDENCE.write_text("\n".join(lines) + "\n")
    ok = sum(1 for v in rows.values() if v in ("probe", "matrix"))
    print(f"\n  wrote {EVIDENCE.name}: {ok} functions executed and agreeing, "
          f"{len(rows) - ok} recorded as refused or disagreeing")


def main() -> None:
    """Bisect by refusal: run the whole set, drop whatever the engine names, run again.

    One unsupported column fails the entire query, so the engine reports refusals one at a
    time. Removing the named function and retrying walks the whole list in as many rounds as
    there are refusals, which is far fewer than one round per function.
    """
    cases, rejected, differ = list(CASES), [], []
    for _round in range(len(CASES)):
        outcome, detail = attempt(cases)
        if outcome == "ok":
            print(f"{len(cases)} functions EXECUTE in a relational property mapping.")
            differ = [d[0] for d in compare(cases, detail)]
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
    else:
        print("every case was rejected")

    record(cases, rejected, differ)

    if rejected:
        print(f"\n{len(rejected)} of {len(CASES)} registered functions do NOT execute here.")
        print("They are in getSupportedFunctions(), so the engine routes to a handler and the")
        print("handler then refuses. A registry that over-reports matters: it is what a")
        print("planner consults to decide a query is expressible BEFORE running it.")


if __name__ == "__main__":
    main()
