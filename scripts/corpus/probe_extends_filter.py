"""Does a subtype set that `extends` a filtered set inherit the parent's ~filter?

    core_instrument::Equity[ciEquity]: Relational
    { ~filter [Store]CiEquityRows          // INSTRUMENT_TYPE = 'EQUITY'
      ... }

    core_instrument::CommonStock[ciCommonStock] extends [ciEquity]: Relational
    { ~filter [Store]CiCommonStockRows     // INSTRUMENT_SUBTYPE = 'COMMON'
      ... }

`CommonStock.all()` is either

  AND    INSTRUMENT_TYPE = 'EQUITY' AND INSTRUMENT_SUBTYPE = 'COMMON'   -- filters compose
  CHILD  INSTRUMENT_SUBTYPE = 'COMMON'                                  -- child's filter replaces

and the two differ on exactly one kind of row: a BOND whose subtype column happens to say
COMMON. The corpus's reader currently models CHILD -- `extends` inherits the parent's main
table and property mappings and not its filter -- and that was never a decision, it is just
what the code does.

This has to be settled BEFORE core-instrument is seeded, not after. Its whole shape is a
three-level hierarchy over one wide table told apart by ~filter, so every expectation over
every subtype depends on the answer. Guessing it and encoding the guess in the oracle would
produce a corpus that asserts a guess -- which is the one thing this corpus is not allowed to
do. Seeding only rows where the two readings agree, and leaving the question open, would be
the honest alternative and a much weaker test.

Note what this probe does and does not do. It establishes what the construct MEANS, which is
a prerequisite for computing anything -- the same standing as knowing that `+` adds. It does
not read an expected VALUE back from the engine: once the meaning is known, every expectation
in the corpus is still computed from the seed.

Six rows, three of them traps, and each trap breaks a different way of getting it wrong.
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

# (id, type, subtype, putCall). The first three are ordinary. The last three are the traps.
ROWS = [
    ("I-EQ-COMMON", "EQUITY", "COMMON", None),
    ("I-EQ-PREF", "EQUITY", "PREFERRED", None),
    ("I-BOND-GOVT", "BOND", "GOVERNMENT", None),
    # A BOND whose subtype says COMMON. Under AND it is not a CommonStock; under CHILD it is.
    ("I-TRAP-BOND-COMMON", "BOND", "COMMON", None),
    # An EQUITY with no subtype at all: an Equity, but neither of its two subtypes.
    ("I-TRAP-EQ-NOSUB", "EQUITY", None, None),
    # A SWAP carrying PUT_CALL. The option subtype filters on PUT_CALL alone, so under CHILD
    # this is a CallOption -- a swap that is an option, which is the reading's worst case.
    ("I-TRAP-SWAP-CALL", "SWAP", None, "CALL"),
]

# (case, class, rows under AND, rows under CHILD)
CASES = [
    ("RootNoFilter", "xf::Instrument",
     [r[0] for r in ROWS], [r[0] for r in ROWS]),
    ("ParentFiltered", "xf::Equity",
     ["I-EQ-COMMON", "I-EQ-PREF", "I-TRAP-EQ-NOSUB"],
     ["I-EQ-COMMON", "I-EQ-PREF", "I-TRAP-EQ-NOSUB"]),
    ("ChildOfFiltered", "xf::CommonStock",
     ["I-EQ-COMMON"],
     ["I-EQ-COMMON", "I-TRAP-BOND-COMMON"]),
    ("ChildOnOtherColumn", "xf::CallOption",
     [],
     ["I-TRAP-SWAP-CALL"]),
]

MODEL = """###Pure
Class xf::Instrument
{
   instrumentId: String[1];
   instrumentType: String[1];
}

Class xf::Equity extends xf::Instrument
{
   shareClass: String[0..1];
}

Class xf::CommonStock extends xf::Equity
{
   votingRights: Float[0..1];
}

Class xf::Option extends xf::Instrument
{
   putCall: String[0..1];
}

Class xf::CallOption extends xf::Option
{
   strikePrice: Float[0..1];
}

###Relational
Database xf::DB
(
   Table XF_INSTRUMENT
   (
      INSTRUMENT_ID VARCHAR(40) PRIMARY KEY,
      INSTRUMENT_TYPE VARCHAR(20),
      INSTRUMENT_SUBTYPE VARCHAR(20),
      PUT_CALL VARCHAR(4),
      SHARE_CLASS VARCHAR(10),
      VOTING_RIGHTS DOUBLE,
      STRIKE_PRICE DOUBLE
   )

   Filter XfEquityRows(XF_INSTRUMENT.INSTRUMENT_TYPE = 'EQUITY')
   Filter XfCommonRows(XF_INSTRUMENT.INSTRUMENT_SUBTYPE = 'COMMON')
   Filter XfOptionRows(XF_INSTRUMENT.INSTRUMENT_TYPE = 'OPTION')
   Filter XfCallRows(XF_INSTRUMENT.PUT_CALL = 'CALL')
)

###Mapping
Mapping xf::M
(
   *xf::Instrument[xfBase]: Relational
   {
      ~primaryKey ( [xf::DB]XF_INSTRUMENT.INSTRUMENT_ID )
      ~mainTable [xf::DB]XF_INSTRUMENT
      instrumentId: [xf::DB]XF_INSTRUMENT.INSTRUMENT_ID,
      instrumentType: [xf::DB]XF_INSTRUMENT.INSTRUMENT_TYPE
   }

   xf::Equity[xfEquity] extends [xfBase]: Relational
   {
      ~filter [xf::DB]XfEquityRows
      shareClass: [xf::DB]XF_INSTRUMENT.SHARE_CLASS
   }

   xf::CommonStock[xfCommon] extends [xfEquity]: Relational
   {
      ~filter [xf::DB]XfCommonRows
      votingRights: [xf::DB]XF_INSTRUMENT.VOTING_RIGHTS
   }

   xf::Option[xfOption] extends [xfBase]: Relational
   {
      ~filter [xf::DB]XfOptionRows
      putCall: [xf::DB]XF_INSTRUMENT.PUT_CALL
   }

   xf::CallOption[xfCall] extends [xfOption]: Relational
   {
      ~filter [xf::DB]XfCallRows
      strikePrice: [xf::DB]XF_INSTRUMENT.STRIKE_PRICE
   }
)

###Connection
RelationalDatabaseConnection xf::Conn
{ store: xf::DB; type: DuckDB; specification: DuckDB { }; auth: Test; }

###Runtime
Runtime xf::RT
{ mappings: [ xf::M ]; connections: [ xf::DB: [ env: xf::Conn ] ]; }

###Data
Data xf::Seed
{
  Relational
  #{
    default.XF_INSTRUMENT:
      'INSTRUMENT_ID,INSTRUMENT_TYPE,INSTRUMENT_SUBTYPE,PUT_CALL,SHARE_CLASS,VOTING_RIGHTS,STRIKE_PRICE\\n' +
%ROWS%;
  }#
}

###Service
"""

SERVICE = """Service xf::S_{name}
{{
   pattern: '/xf/{name}';
   documentation: 'Does a subtype set inherit the filter of the set it extends?';
   execution: Single
   {{
      query: |{cls}.all()->project(~[id: x|$x.instrumentId])->sort(~id->ascending());
      mapping: xf::M;
      runtime: xf::RT;
   }}
   testSuites:
   [
      S_{name}_suite:
      {{
         data: [ connections: [ env: Reference #{{ xf::Seed }}# ] ]
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
    csv = " +\n".join(
        "      '{},{},{},{},{},{},{}\\n'".format(
            i, t, st or "", pc or "", "A" if t == "EQUITY" else "", "1.0", "100.0")
        for i, t, st, pc in ROWS)
    work = Path(tempfile.mkdtemp())
    src = work / "xf.pure"
    # Assert the AND reading. A case that FAILS is reported with the rows it actually
    # returned, and CHILD is confirmed only if those rows are exactly the CHILD column.
    src.write_text(MODEL.replace("%ROWS%", csv) + "".join(
        SERVICE.format(name=n, cls=cls,
                       data=json.dumps([{"id": i} for i in sorted(a)]).replace("'", "\\'"))
        for n, cls, a, _child in CASES))
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    r = subprocess.run(
        [f"{runner.JAVA_HOME}/bin/java", "-cp",
         f"{runner.RUNNER}/target/classes:{cp}", "perf.TestableMain", str(src)]
        + [f"--testable=xf::S_{n}" for n, _c, _a, _ch in CASES],
        capture_output=True, text=True,
        env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME), cwd=runner.RUNNER, timeout=1800)
    out = r.stdout + r.stderr
    fatal = re.search(r"EngineException: ([^\n]{0,200})", out)
    if fatal and "PASS" not in out:
        print(f"  the whole file failed before any service ran:\n    {fatal.group(1)}")
        print(f"\n  source kept at {src}")
        return

    verdicts = []
    for name, _cls, and_rows, child_rows in CASES:
        m = re.search(rf"(PASS|FAIL|ERROR)\s+S_{name}_suite", out)
        v = m.group(1) if m else "MISSING"
        actual = re.search(rf"S_{name}_suite.*?actual\s*:\s*(\[[^\n]{{0,200}})", out, re.S)
        got = actual.group(1).strip() if actual else ""
        if v == "PASS":
            reading = "AND" if and_rows != child_rows else "either (cases agree)"
        else:
            ids = sorted(set(re.findall(r"I-[A-Z-]+", got)))
            reading = "CHILD" if ids == sorted(child_rows) else f"NEITHER {ids}"
        verdicts.append(reading)
        print(f"  {v:<8}{name:<22}{reading}")
        if v != "PASS" and got:
            print(f"          returned {got[:120]}")

    decisive = [v for v in verdicts if v in ("AND", "CHILD")]
    print()
    if decisive and len(set(decisive)) == 1:
        print(f"  A subtype set that extends a filtered set: {decisive[0]}")
        if decisive[0] == "AND":
            print("  -- the parent's filter composes, so model.py must inherit it through")
            print("     `extends` and the oracle must AND them.")
    else:
        print(f"  NOT DECIDED: {verdicts}")
    print(f"\n  source kept at {src}")


if __name__ == "__main__":
    main()
