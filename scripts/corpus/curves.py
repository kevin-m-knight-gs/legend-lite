"""Stage the yield-curve domain: a genuinely composite primary key, and things to stack on it.

`composite PK` is the corpus's most isolated construct -- it appears on two milestoned
classes with three properties each and nothing navigating out of them, so it co-occurs with
almost nothing. 33 of the uncovered feature pairs have it on one side.

A curve pillar is the honest place to fix that. It is keyed by (curve, date, tenor) and by
nothing shorter: the same curve and tenor appear on every cob date, so a key that drops the
date collides on the second day. Two cob dates are seeded for exactly that reason -- one
would let a two-column key pass and prove nothing.

Around that key this puts, deliberately, the constructs composite PK has never met:

  * an EMBEDDED curve, because a curve store denormalizes its parent onto the point row and
    reading it should emit no join at all
  * a THREE-column self-join with an inequality (`same curve, same date, longer tenor`),
    which is how you find the next pillar out and is the join a forward rate needs
  * a ~filter subtype for the pillars the market actually quotes, against the 15Y and 20Y
    which are interpolated on every curve here
  * an enum transformer on the curve type
  * a navigation three deep out to the market-data series a pillar was fitted to, and on to
    that series' observations -- to-many at the end, and empty for most pillars, because
    most pillars are not a published series
"""
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent / "exec"
STRESS = ROOT / "core/src/test/resources/stress"
SCRIPTS = ROOT / "scripts/corpus"

# The pillars a curve is quoted on. 15Y and 20Y are interpolated on all eight curves here --
# they are the tenors the market does not quote and a curve builder fills in.
TENORS = [("1M", 30), ("3M", 91), ("6M", 182), ("1Y", 365), ("2Y", 730), ("3Y", 1095),
          ("5Y", 1826), ("7Y", 2556), ("10Y", 3653), ("15Y", 5479), ("20Y", 7305),
          ("30Y", 10958)]
INTERPOLATED = {"15Y", "20Y"}

# Which pillar was fitted to a published series, where one exists. Most were not: a curve is
# built from swap and deposit quotes, and only a few of its pillars line up with something
# that has a series identifier of its own.
FITTED = {("USD.SOFR.OIS", "1M"): "SOFR.ON",
          ("EUR.EURIBOR.6M", "3M"): "EURIBOR.3M",
          ("USD.UST.GOVT", "10Y"): "UST.10Y",
          ("EUR.DBR.GOVT", "10Y"): "DBR.10Y"}

# (curveId, name, currency, sourceTypeCode, interpolation, dayCount, zero rates on the 19th,
#  the move to the 20th in basis points)
#
# Real June 2024 shapes: the dollar and sterling curves are inverted at the front and turn up
# past five years, the yen curve is upward-sloping from almost nothing, and the moves are
# twists rather than parallel shifts because that is what curves do.
CURVES = [
    ("USD.SOFR.OIS", "USD SOFR Overnight Index Swap", "USD", "OIS", "LINEAR_ZERO", "ACT/360",
     [5.34, 5.33, 5.27, 5.09, 4.63, 4.38, 4.16, 4.10, 4.09, 4.16, 4.15, 3.96],
     [0, -1, -2, -4, -6, -6, -5, -3, -2, 0, 1, 2]),
    ("USD.LIBOR.3M", "USD 3M LIBOR Swap (legacy)", "USD", "IBOR_SWAP", "CUBIC_SPLINE",
     "ACT/360",
     [5.59, 5.60, 5.53, 5.34, 4.88, 4.63, 4.41, 4.35, 4.34, 4.41, 4.40, 4.21],
     [0, -1, -2, -4, -6, -6, -5, -3, -2, 0, 1, 2]),
    ("EUR.ESTR.OIS", "EUR Euro Short Term Rate OIS", "EUR", "OIS", "LINEAR_ZERO", "ACT/360",
     [3.66, 3.62, 3.50, 3.24, 2.86, 2.68, 2.55, 2.55, 2.59, 2.70, 2.68, 2.50],
     [-1, -2, -3, -5, -8, -9, -8, -7, -6, -4, -3, -2]),
    ("EUR.EURIBOR.6M", "EUR 6M Euribor Swap", "EUR", "IBOR_SWAP", "CUBIC_SPLINE", "30/360",
     [3.79, 3.77, 3.71, 3.51, 3.11, 2.92, 2.78, 2.78, 2.82, 2.93, 2.91, 2.73],
     [-1, -2, -3, -5, -8, -9, -8, -7, -6, -4, -3, -2]),
    ("GBP.SONIA.OIS", "GBP SONIA Overnight Index Swap", "GBP", "OIS", "LINEAR_ZERO",
     "ACT/365",
     [5.19, 5.17, 5.05, 4.76, 4.25, 4.02, 3.87, 3.85, 3.87, 3.93, 3.83, 3.55],
     [0, 0, -1, -2, -3, -3, -2, -1, 0, 1, 2, 3]),
    ("JPY.TONA.OIS", "JPY TONA Overnight Index Swap", "JPY", "OIS", "LINEAR_ZERO", "ACT/365",
     [0.08, 0.11, 0.18, 0.31, 0.44, 0.52, 0.63, 0.75, 0.94, 1.30, 1.55, 1.87],
     [0, 1, 1, 2, 3, 4, 5, 6, 7, 8, 8, 9]),
    ("USD.UST.GOVT", "US Treasury Zero Curve", "USD", "GOVT", "BOOTSTRAP", "ACT/ACT",
     [5.42, 5.36, 5.25, 5.09, 4.71, 4.51, 4.31, 4.29, 4.29, 4.42, 4.47, 4.41],
     [1, 0, -1, -3, -5, -5, -4, -3, -2, 0, 1, 2]),
    ("EUR.DBR.GOVT", "German Bund Zero Curve", "EUR", "GOVT", "BOOTSTRAP", "ACT/ACT",
     [3.61, 3.55, 3.44, 3.24, 2.94, 2.75, 2.55, 2.53, 2.51, 2.63, 2.66, 2.68],
     [-1, -1, -2, -4, -7, -8, -7, -6, -5, -3, -2, -1]),
]

COB_DATES = [(2024, 6, 19), (2024, 6, 20)]


def _rows():
    curves, points = [], []
    for cid, name, ccy, ctype, interp, dcb, day1, shift in CURVES:
        curves.append(
            f'    dict(CURVE_ID="{cid}", CURVE_NAME="{name}", CURRENCY="{ccy}",\n'
            f'         CURVE_TYPE="{ctype}", INTERPOLATION="{interp}", DAY_COUNT_BASIS="{dcb}",\n'
            f'         IS_ACTIVE={ctype != "IBOR_SWAP"}),')
        for d, (y, m, dd) in enumerate(COB_DATES):
            for (label, days), base, bp in zip(TENORS, day1, shift):
                # Percent, shifted by the hand-written basis-point move on the second day.
                rate = round(base + (bp / 100.0 if d else 0.0), 2)
                # A discount factor is not an independent fact -- a curve store computes it
                # from the zero rate and stores it, and so does this.
                df = round(math.exp(-rate / 100.0 * days / 365.0), 8)
                series = FITTED.get((cid, label))
                points.append(
                    f'    dict(CURVE_ID="{cid}", COB_DATE=_iso({y}, {m}, {dd}),\n'
                    f'         TENOR_LABEL="{label}", TENOR_DAYS={days}, ZERO_RATE={rate},\n'
                    f'         DISCOUNT_FACTOR={df}, '
                    f'IS_INTERPOLATED={label in INTERPOLATED},\n'
                    f'         SOURCE_SERIES_ID={f'"{series}"' if series else None},\n'
                    f'         CURVE_NAME="{name}", CURVE_CURRENCY="{ccy}", '
                    f'CURVE_TYPE="{ctype}"),')
    return "\n".join(curves), "\n".join(points)


PURE = '''###Pure
// Yield curves: a curve, its pillars, and what each pillar was fitted to.
//
// The point of this domain is the KEY. A curve pillar is identified by curve, cob date and
// tenor together and by nothing shorter -- the 10Y point of the SOFR curve exists on every
// business day, so a key without the date collides on the second one. Two cob dates are
// seeded so that a key that has quietly lost a column fails rather than passing by accident.
//
// Everything else here exists to STACK on that key. Composite primary keys were the corpus's
// most isolated construct: two milestoned classes, three properties each, nothing navigating
// out of them, and so 33 feature pairs with `composite PK` on one side that nothing executed.
// A pillar can carry all of them honestly, because a curve store really does denormalize the
// curve onto the point, really does self-join to find the next pillar out, and really does
// leave most pillars without a published series behind them.
Enum curves::CurveType
{
   OIS,
   SWAP,
   GOVERNMENT
}

Class curves::YieldCurve
{
   curveId: String[1];
   curveName: String[1];
   currency: String[1];
   curveType: curves::CurveType[1];
   interpolation: String[1];
   dayCountBasis: String[1];
   isActive: Boolean[1];
}

// The curve's own attributes, carried on the pillar row. A curve store denormalizes these
// so a pillar can be read without touching the curve table, and this is that value object --
// a distinct class rather than the curve itself, because they are not the same thing: this
// one has no key, no interpolation and no lifecycle, and it exists only as a copy.
//
// It is also the only shape an embedded mapping can honestly take here. Mapping the curve
// ITSELF embedded would give one class two column sets -- CURRENCY on the curve table and
// CURVE_CURRENCY on the pillar -- and every reader keyed by class alone would take whichever
// was written last.
Class curves::PillarCurveRef
{
   curveName: String[1];
   currency: String[1];
   curveType: curves::CurveType[1];
}

Class curves::CurvePoint
{
   curveId: String[1];
   cobDate: StrictDate[1];
   tenorLabel: String[1];
   tenorDays: Integer[1];
   zeroRate: Float[1];
   discountFactor: Float[1];
   isInterpolated: Boolean[1];
   // Null for most pillars. A curve is built from swap and deposit quotes, and only a few of
   // its pillars line up with something that has a published series identifier.
   sourceSeriesId: String[0..1];
   // Read EMBEDDED: no join is emitted, because the columns are on this row.
   curveRef: curves::PillarCurveRef[1];

   // The tenor in years, as a curve is actually quoted. Division, so the engine is in
   // double from here on.
   tenorYears() { $this.tenorDays / 365.0 } : Float[1];
   // The rate in basis points, which is the unit a curve is actually discussed in. An exact
   // decimal multiply -- and the case that first showed the oracle was multiplying in binary
   // float where the engine was multiplying a DECIMAL column.
   zeroRateBps() { $this.zeroRate * 100.0 } : Float[1];
   // The long end, where a curve's shape is set by demand for duration rather than by the
   // policy rate. A comparison rather than a lookup.
   isLongEnd() { $this.tenorDays > 3650 } : Boolean[1];
   // A qualified property: the discount factor for a notional the caller supplies, which is
   // the number a PV actually uses.
   presentValueOf(notional: Float[1]) { $notional * $this.discountFactor } : Float[1];
}

// The pillars the market quotes. 15Y and 20Y are interpolated on every curve here, so this
// is 10 of 12 per curve -- a filter that is doing real work rather than selecting everything.
Class curves::QuotedPillar extends curves::CurvePoint
{
}

// The curve a point belongs to. Mapped EMBEDDED below: the columns are on the point's own
// row, so reading the curve through a pillar emits no join.
Association curves::CurveOfPoint
{
   curve: curves::YieldCurve[0..1];
   points: curves::CurvePoint[*];
}

// The next pillars out: same curve, same date, longer tenor. A three-column self-join with an
// inequality on the third -- and the join a forward rate is computed across.
//
// BOTH ends are to-many, and that is not a formality. The inequality means the 1M point has
// eleven pillars longer than it and the 30Y has eleven shorter, so declaring the reverse end
// [0..1] was simply false -- and the generators believed it, chained `shorterPillar` three
// deep as a to-one navigation, and produced a service whose expected value was null where the
// engine returned the first of twenty-four rows.
Association curves::LongerPillars
{
   shorterPillars: curves::CurvePoint[*];
   longerPillars: curves::CurvePoint[*];
}

// Out to the market-data series the pillar was fitted to, where there is one. Four of the 192
// pillars have one, so the empty case is the overwhelming majority.
Association curves::PillarSource
{
   fittedPillars: curves::CurvePoint[*];
   sourceSeries: timeseries::TimeSeries[0..1];
}

###Mapping
Mapping curves::CurvesMapping
(
   curves::CurveType: EnumerationMapping CurveTypeMapping
   {
      OIS: ['OIS'],
      SWAP: ['IBOR_SWAP'],
      GOVERNMENT: ['GOVT']
   }

   curves::YieldCurve: Relational
   {
      ~primaryKey ( [store::DB]RATE_CURVE.CURVE_ID )
      ~mainTable [store::DB]RATE_CURVE
      curveId: [store::DB]RATE_CURVE.CURVE_ID,
      curveName: [store::DB]RATE_CURVE.CURVE_NAME,
      currency: [store::DB]RATE_CURVE.CURRENCY,
      curveType: EnumerationMapping CurveTypeMapping: [store::DB]RATE_CURVE.CURVE_TYPE,
      interpolation: [store::DB]RATE_CURVE.INTERPOLATION,
      dayCountBasis: [store::DB]RATE_CURVE.DAY_COUNT_BASIS,
      isActive: [store::DB]RATE_CURVE.IS_ACTIVE
   }

   // The composite key, and the whole reason this domain exists. All three columns, because
   // any two of them collide.
   curves::CurvePoint[pillar]: Relational
   {
      ~primaryKey ( [store::DB]RATE_CURVE_POINT.CURVE_ID, [store::DB]RATE_CURVE_POINT.COB_DATE, [store::DB]RATE_CURVE_POINT.TENOR_LABEL )
      ~mainTable [store::DB]RATE_CURVE_POINT
      curveId: [store::DB]RATE_CURVE_POINT.CURVE_ID,
      cobDate: [store::DB]RATE_CURVE_POINT.COB_DATE,
      tenorLabel: [store::DB]RATE_CURVE_POINT.TENOR_LABEL,
      tenorDays: [store::DB]RATE_CURVE_POINT.TENOR_DAYS,
      zeroRate: [store::DB]RATE_CURVE_POINT.ZERO_RATE,
      discountFactor: [store::DB]RATE_CURVE_POINT.DISCOUNT_FACTOR,
      isInterpolated: [store::DB]RATE_CURVE_POINT.IS_INTERPOLATED,
      sourceSeriesId: [store::DB]RATE_CURVE_POINT.SOURCE_SERIES_ID,
      curveRef
      (
         curveName: [store::DB]RATE_CURVE_POINT.CURVE_NAME,
         currency: [store::DB]RATE_CURVE_POINT.CURVE_CURRENCY,
         curveType: EnumerationMapping CurveTypeMapping: [store::DB]RATE_CURVE_POINT.CURVE_TYPE
      )
   }

   curves::QuotedPillar[quoted] extends [pillar]: Relational
   {
      ~filter [store::DB]QuotedPillarRows
   }

   // The curve reached by JOIN, alongside the same three facts reached embedded. The two
   // readings must agree -- which is the assertion the denormalization is worth having.
   curves::CurveOfPoint: Relational
   {
      AssociationMapping
      (
         points[curves_YieldCurve, pillar]: [store::DB]@Curve_Point,
         curve[pillar, curves_YieldCurve]: [store::DB]@Curve_Point
      )
   }

   curves::LongerPillars: Relational
   {
      AssociationMapping
      (
         longerPillars[pillar, pillar]: [store::DB]@Pillar_LongerPillar,
         shorterPillars[pillar, pillar]: [store::DB]@Pillar_LongerPillar
      )
   }

   curves::PillarSource: Relational
   {
      AssociationMapping
      (
         sourceSeries[pillar, timeseries_TimeSeries]: [store::DB]@Pillar_Series,
         fittedPillars[timeseries_TimeSeries, pillar]: [store::DB]@Pillar_Series
      )
   }
)
'''


def apply() -> None:
    curves, points = _rows()

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    if '"RATE_CURVE"' not in t:
        t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                      f"\n\n# Yield curves and their pillars, on two cob dates.\n"
                      f"RATE_CURVE = [\n{curves}\n]\n\n"
                      f"RATE_CURVE_POINT = [\n{points}\n]\n"
                      "\n\nTABLES: dict[str, list[dict]] = {", 1)
        t = t.replace("TABLES: dict[str, list[dict]] = {",
                      'TABLES: dict[str, list[dict]] = {\n'
                      '    "RATE_CURVE": RATE_CURVE,\n'
                      '    "RATE_CURVE_POINT": RATE_CURVE_POINT,', 1)
        p.write_text(t)

    p = STRESS / "30-store.pure"
    t = p.read_text()
    for name in ("RATE_CURVE", "RATE_CURVE_POINT"):
        if f"Table {name} (" in t and "// ---- Yield curves" not in t:
            raise SystemExit(
                f"table {name} is already declared. Pick another name: skipping the write "
                f"is what the first version did, and the failure then arrived as "
                f"'~mainTable is not a declared table' pointing at the class rather than "
                f"at the collision.")
    if "Table RATE_CURVE (" not in t:
        anchor = "    // ---- Back office: where cash actually moves ----"
        t = t.replace(anchor, """    // ---- Yield curves: the composite-key domain ----
    Table RATE_CURVE (CURVE_ID VARCHAR(20) PRIMARY KEY, CURVE_NAME VARCHAR(40),
                       CURRENCY VARCHAR(4), CURVE_TYPE VARCHAR(12),
                       INTERPOLATION VARCHAR(16), DAY_COUNT_BASIS VARCHAR(10), IS_ACTIVE BIT)

    // Keyed by all three of CURVE_ID, COB_DATE and TENOR_LABEL. The last three columns are
    // the curve's own, denormalized onto the point so the embedded mapping has something to
    // bind to -- which is what a curve store does, for the same reason.
    Table RATE_CURVE_POINT (CURVE_ID VARCHAR(20) PRIMARY KEY, COB_DATE DATE PRIMARY KEY,
                       TENOR_LABEL VARCHAR(6) PRIMARY KEY, TENOR_DAYS INTEGER,
                       ZERO_RATE DECIMAL(18,4), DISCOUNT_FACTOR DECIMAL(18,8),
                       IS_INTERPOLATED BIT, SOURCE_SERIES_ID VARCHAR(20),
                       CURVE_NAME VARCHAR(40), CURVE_CURRENCY VARCHAR(4),
                       CURVE_TYPE VARCHAR(12))

""" + anchor, 1)
        t = t.replace("    Join Counterparty_Ssi(",
                      "    Filter QuotedPillarRows(RATE_CURVE_POINT.IS_INTERPOLATED = 0)\n\n"
                      "    Join Curve_Point(RATE_CURVE.CURVE_ID = RATE_CURVE_POINT.CURVE_ID)\n"
                      "    Join Pillar_Series(RATE_CURVE_POINT.SOURCE_SERIES_ID = TIME_SERIES.SERIES_ID)\n"
                      "    // Same curve, same date, longer tenor. Three columns and the third\n"
                      "    // an inequality -- the join a forward rate is computed across.\n"
                      # One line: the reader parses a join from a single line and refuses a
                      # wrapped one rather than dropping it silently.
                      "    Join Pillar_LongerPillar(RATE_CURVE_POINT.CURVE_ID = {target}.CURVE_ID"
                      " and RATE_CURVE_POINT.COB_DATE = {target}.COB_DATE"
                      " and RATE_CURVE_POINT.TENOR_DAYS < {target}.TENOR_DAYS)\n\n"
                      "    Join Counterparty_Ssi(", 1)
        p.write_text(t)

    (STRESS / "835-curves.pure").write_text(PURE)

    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if "include curves::" not in t:
        t = t.replace("    include backoffice::BackOfficeMapping",
                      "    include backoffice::BackOfficeMapping\n"
                      "    include curves::CurvesMapping", 1)
        p.write_text(t)


if __name__ == "__main__":
    apply()
    print("curves staged")
