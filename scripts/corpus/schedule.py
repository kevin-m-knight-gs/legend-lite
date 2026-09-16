"""A VERSIONED brokerage schedule: milestoning, a range join and a composite key together.

The three constructs with the most uncovered pairs left were `join with or`, `composite PK`
and `join non-equality`, with `milestoning` close behind -- and the reason is always the same
shape. Each lives on a class that carries little else, so it has nothing to co-occur WITH.

A fee schedule fixes that honestly, because a fee schedule really is versioned. Rates get cut
when a competitor cuts theirs, and the question a client asks afterwards -- "what would this
trade have cost in February?" -- is a business-temporal read. The bands themselves do not
move; only the rates do, which is both what happens in practice and what makes the two
versions comparable.

Put together, one navigation carries:

  * milestoning, and `all(%date)` as the way to ask for it
  * a composite primary key, which a milestoned table has by construction -- the key is the
    tier AND the date it was in force from
  * a qualified property and a derived one over an OPTIONAL Float, which a milestoned class
    makes every non-key column into

The range join to the trades in each band was the fourth, and is withdrawn: adding it made
the whole corpus fail to compile inside an unrelated cross-store mapping, and
probe_milestoned_join.py cannot reproduce that in any of eight small models. See the note in
837-schedule.pure. What is here passes; what was tried and pulled is written down rather than
quietly dropped.
"""
import pathlib

STRESS = pathlib.Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"
SCRIPTS = pathlib.Path(__file__).resolve().parent

# (tierId, name, minNotional, maxNotional, old rate, new rate, old minimum, new minimum).
#
# The cut on 1 April 2024 was to the RATES, not to the bands: a schedule that re-cut its
# bands at the same time would make the two versions incomparable, and a client asking what
# February would have cost wants the same band at the old price.
TIERS = [
    ("TIER-RETAIL", "Retail", 0.0, 100000.0, 15.0, 12.5, 30.00, 25.00),
    ("TIER-SMALL", "Small Institutional", 100000.0, 500000.0, 10.0, 8.0, 50.00, 40.00),
    ("TIER-MID", "Mid Institutional", 500000.0, 2000000.0, 7.0, 5.5, 175.00, 150.00),
    ("TIER-LARGE", "Large Block", 2000000.0, 100000000.0, 4.0, 3.25, 600.00, 500.00),
]

CUT_DATE = "2024-04-01"
INFINITY = "9999-12-31"

PURE = '''###Pure
// The brokerage schedule, versioned. Rates were cut on 1 April 2024.
//
// The stereotype is what makes this temporal: `all()` no longer means every row, it means
// the version in force at a date, and the date becomes part of the query. There are no
// from/thru PROPERTIES on the class -- the milestoning columns live on the table and the
// engine supplies the predicate. Exposing them would let a query filter on them directly and
// bypass the whole mechanism.
//
// This exists to put milestoning next to things it had never been asked with. A milestoned
// table carries a composite primary key by construction, and reaching a trade from a tier is
// a RANGE join -- so one navigation combines milestoning, `all(%date)`, a composite key, a
// non-equality join and a count over the to-many it lands on.
//
// The BANDS are the same in both versions and only the rates differ. A schedule that re-cut
// its bands at the same time would make the two versions incomparable, and the question this
// is here to answer -- what would this trade have cost in February -- wants the same band at
// the old price.
Class <<temporal.businesstemporal>> schedule::TierVersion
{
   tierId: String[1];
   tierName: String[0..1];
   minNotional: Float[0..1];
   maxNotional: Float[0..1];
   bpsRate: Float[0..1];
   minimumFee: Float[0..1];

   // The rate as a fraction, which is the form a fee calculation multiplies by.
   rateFraction() { $this.bpsRate->orElse(0.0) / 10000.0 } : Float[1];
   // A qualified property: what this version of the band charges on a notional the caller
   // supplies, before the minimum is applied.
   feeOn(notional: Float[1]) { $notional * $this.bpsRate->orElse(0.0) / 10000.0 } : Float[1];
}

###Mapping
Mapping schedule::ScheduleMapping
(
   schedule::TierVersion: Relational
   {
      ~primaryKey ( [store::DB]BROKERAGE_TIER_MS.TIER_ID, [store::DB]BROKERAGE_TIER_MS.FROM_Z )
      ~mainTable [store::DB]BROKERAGE_TIER_MS
      tierId: [store::DB]BROKERAGE_TIER_MS.TIER_ID,
      tierName: [store::DB]BROKERAGE_TIER_MS.TIER_NAME,
      minNotional: [store::DB]BROKERAGE_TIER_MS.MIN_NOTIONAL,
      maxNotional: [store::DB]BROKERAGE_TIER_MS.MAX_NOTIONAL,
      bpsRate: [store::DB]BROKERAGE_TIER_MS.BPS_RATE,
      minimumFee: [store::DB]BROKERAGE_TIER_MS.MINIMUM_FEE
   }

)
'''


def apply() -> None:
    p = STRESS / "30-store.pure"
    t = p.read_text()
    if "Table BROKERAGE_TIER_MS" in t:
        raise SystemExit("versioned schedule already applied")

    anchor = "    // ---- Brokerage tiers and clearing routes ----"
    t = t.replace(anchor, """    // ---- The brokerage schedule, versioned ----
    Table BROKERAGE_TIER_MS
    (
      milestoning
      (
        business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-31)
      )
      TIER_ID VARCHAR(20) PRIMARY KEY,
      FROM_Z DATE PRIMARY KEY,
      THRU_Z DATE,
      TIER_NAME VARCHAR(30),
      MIN_NOTIONAL DECIMAL(18,2),
      MAX_NOTIONAL DECIMAL(18,2),
      BPS_RATE DECIMAL(18,4),
      MINIMUM_FEE DECIMAL(18,2)
    )

""" + anchor, 1)
    t = t.replace("    Join Trade_BrokerageTier(",
                  "    // A RANGE join FROM a milestoned table. The engine adds the milestoning\n"
                  "    // predicate; the band membership is the join's own condition.\n"
                  "    Join TierVersion_Trade(TRADE.NOTIONAL >= BROKERAGE_TIER_MS.MIN_NOTIONAL and TRADE.NOTIONAL < BROKERAGE_TIER_MS.MAX_NOTIONAL)\n"
                  "    Join Trade_BrokerageTier(", 1)
    p.write_text(t)

    rows = []
    for tid, name, lo, hi, old_bps, new_bps, old_min, new_min in TIERS:
        rows.append(
            f'    # The old rate, closed on the day of the cut.\n'
            f'    dict(TIER_ID="{tid}", FROM_Z=_iso(2024, 1, 1), THRU_Z="{CUT_DATE}",\n'
            f'         TIER_NAME="{name}", MIN_NOTIONAL={lo}, MAX_NOTIONAL={hi},\n'
            f'         BPS_RATE={old_bps}, MINIMUM_FEE={old_min}),')
        rows.append(
            f'    dict(TIER_ID="{tid}", FROM_Z="{CUT_DATE}", THRU_Z=INFINITY,\n'
            f'         TIER_NAME="{name}", MIN_NOTIONAL={lo}, MAX_NOTIONAL={hi},\n'
            f'         BPS_RATE={new_bps}, MINIMUM_FEE={new_min}),')

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                  "\n\n# The brokerage schedule, versioned: rates were cut on 1 April 2024 and the\n"
                  "# bands were left alone.\n"
                  f"BROKERAGE_TIER_MS = [\n" + "\n".join(rows) + "\n]\n"
                  "\n\nTABLES: dict[str, list[dict]] = {", 1)
    t = t.replace("TABLES: dict[str, list[dict]] = {",
                  'TABLES: dict[str, list[dict]] = {\n'
                  '    "BROKERAGE_TIER_MS": BROKERAGE_TIER_MS,', 1)
    p.write_text(t)

    (STRESS / "837-schedule.pure").write_text(PURE)

    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if "include schedule::ScheduleMapping" not in t:
        t = t.replace("    include brokerage::BrokerageMapping",
                      "    include brokerage::BrokerageMapping\n"
                      "    include schedule::ScheduleMapping", 1)
        p.write_text(t)


if __name__ == "__main__":
    apply()
    print("versioned schedule staged")
