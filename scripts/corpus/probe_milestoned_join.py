"""
An association from a MILESTONED class over a join that is not a key equality.

A versioned fee schedule reaches the trades in each band by a range join -- which band a
trade falls in is a property of its notional, so there is no key and none possible. Adding
that association made the whole corpus fail to compile:

    EngineException: Error in 'external::EntityMapping': Size must be 1 but was 0

`external::EntityMapping` is a cross-store mapping that has nothing to do with schedules. It
maps `trading::Trade`, which is the association's other end, and that is the entire
connection. Nothing in the message names the association, the milestoned class, the join, or
the file any of them are in.

The corpus already contains both halves separately and neither is a problem:

  * a milestoned class associated over a KEY join (a counterparty's rating versions)
  * a NON-milestoned class associated over the same range join (the unversioned schedule)

So this narrows the combination. Four models, one construct different in each.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run as runner

BASE = """###Pure
Class ms::Owner
{{
   ownerId: String[1];
   amount: Float[1];
}}

Class {stereotype}ms::Band
{{
   bandId: String[1];
   label: String[0..1];
   minAmount: Float[0..1];
   maxAmount: Float[0..1];
}}

Association ms::BandOwners
{{
   band: ms::Band[*];
   owners: ms::Owner[*];
}}

###Relational
Database ms::DB
(
   Table OWNER ( OWNER_ID VARCHAR(10) PRIMARY KEY, AMOUNT DECIMAL(18,2), BAND_ID VARCHAR(10) )

{table}

   Join Band_Owner({condition})
)

###Mapping
Mapping ms::M
(
   ms::Owner: Relational
   {{
      ~primaryKey ( [ms::DB]OWNER.OWNER_ID )
      ~mainTable [ms::DB]OWNER
      ownerId: [ms::DB]OWNER.OWNER_ID,
      amount: [ms::DB]OWNER.AMOUNT
   }}

   ms::Band: Relational
   {{
      ~primaryKey ( {pk} )
      ~mainTable [ms::DB]BAND
      bandId: [ms::DB]BAND.BAND_ID,
      label: [ms::DB]BAND.LABEL,
      minAmount: [ms::DB]BAND.MIN_AMOUNT,
      maxAmount: [ms::DB]BAND.MAX_AMOUNT
   }}

   ms::BandOwners: Relational
   {{
      AssociationMapping
      (
         owners: [ms::DB]@Band_Owner,
         band: [ms::DB]@Band_Owner
      )
   }}
)
{second}"""

# A SECOND mapping over the same class, not including the association. This is the shape
# `external::EntityMapping` has: it maps trading::Trade for its own purposes -- five
# properties and a cross-store link -- and knows nothing about schedules.
SECOND = """
###Mapping
Mapping ms::Partial
(
   ms::Owner: Relational
   {
      ~primaryKey ( [ms::DB]OWNER.OWNER_ID )
      ~mainTable [ms::DB]OWNER
      ownerId: [ms::DB]OWNER.OWNER_ID,
      amount: [ms::DB]OWNER.AMOUNT
   }
)
"""

PLAIN_TABLE = """   Table BAND ( BAND_ID VARCHAR(10) PRIMARY KEY, LABEL VARCHAR(20),
                MIN_AMOUNT DECIMAL(18,2), MAX_AMOUNT DECIMAL(18,2) )"""

MS_TABLE = """   Table BAND
   (
     milestoning
     (
       business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-31)
     )
     BAND_ID VARCHAR(10) PRIMARY KEY,
     FROM_Z DATE PRIMARY KEY,
     THRU_Z DATE,
     LABEL VARCHAR(20),
     MIN_AMOUNT DECIMAL(18,2),
     MAX_AMOUNT DECIMAL(18,2)
   )"""

KEY_JOIN = "OWNER.BAND_ID = BAND.BAND_ID"
RANGE_JOIN = "OWNER.AMOUNT >= BAND.MIN_AMOUNT and OWNER.AMOUNT < BAND.MAX_AMOUNT"

PLAIN_PK = "[ms::DB]BAND.BAND_ID"
MS_PK = "[ms::DB]BAND.BAND_ID, [ms::DB]BAND.FROM_Z"


MS = "<<temporal.businesstemporal>> "


def cases():
    """Each row differs from its neighbours in ONE construct."""
    return [
        # Alone, every combination compiles. The corpus proved that first.
        ("PlainKey", "", PLAIN_TABLE, KEY_JOIN, PLAIN_PK, ""),
        ("PlainRange", "", PLAIN_TABLE, RANGE_JOIN, PLAIN_PK, ""),
        ("MilestonedKey", MS, MS_TABLE, KEY_JOIN, MS_PK, ""),
        ("MilestonedRange", MS, MS_TABLE, RANGE_JOIN, MS_PK, ""),
        # With a SECOND mapping over the association's other end, which is the ingredient
        # the isolated models were missing.
        ("PlainKey+2nd", "", PLAIN_TABLE, KEY_JOIN, PLAIN_PK, SECOND),
        ("PlainRange+2nd", "", PLAIN_TABLE, RANGE_JOIN, PLAIN_PK, SECOND),
        ("MilestonedKey+2nd", MS, MS_TABLE, KEY_JOIN, MS_PK, SECOND),
        ("MilestonedRange+2nd", MS, MS_TABLE, RANGE_JOIN, MS_PK, SECOND),
    ]


def main() -> None:
    work = Path(tempfile.mkdtemp())
    cp = (runner.RUNNER / "cp.txt").read_text().strip()
    for name, stereotype, table, condition, pk, second in cases():
        src = work / f"{name.replace('+', '_')}.pure"
        src.write_text(BASE.format(stereotype=stereotype, table=table,
                                   condition=condition, pk=pk, second=second))
        r = subprocess.run(
            [f"{runner.JAVA_HOME}/bin/java", "-cp",
             f"{runner.RUNNER}/target/classes:{cp}", "perf.ParseMain", str(src)],
            capture_output=True, text=True,
            env=dict(os.environ, JAVA_HOME=runner.JAVA_HOME),
            cwd=runner.RUNNER, timeout=900)
        out = r.stdout + r.stderr
        m = re.search(r"(?:EngineException|IllegalStateException)[:\s]+([^\n]{0,120})", out)
        print(f"  {'COMPILE FAILS' if m else 'compiles    '}  {name:<22}"
              f"{m.group(1).strip() if m else ''}")
    print(f"\n  sources kept at {work}")


if __name__ == "__main__":
    main()
