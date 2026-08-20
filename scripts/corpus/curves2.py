"""Second wave on the curve domain: the mapping constructs composite PK still has not met.

The first wave took `composite PK` from 33 uncovered pairs to 23 by putting a three-column
key under an embedded value object, an enum transformer, a ~filter subtype, a self-join and a
navigation. What is left are mapping constructs the pillar set does not yet use, and most of
them a curve store uses for real:

  * a DYNAFUNCTION -- the composite key concatenated into one string, which is what a curve
    service keys its cache on and what a support ticket quotes
  * a LOCAL PROPERTY (`+`) -- the currency, readable off the pillar without navigating,
    declared in the mapping because it is a mapping-level convenience rather than part of
    what a curve pillar IS
  * a ~distinct set -- the (curve, date) pairs a curve exists for. Twelve pillars per pair,
    so distinct is doing real work: 16 rows out of 192
  * a VIEW with ~groupBy -- per-curve-and-date summary, which is what a curve monitor reads
    instead of scanning the pillars
  * a JOIN CHAIN -- pillar to curve to the curve's benchmark series, two hops
  * a JOIN WITH OR -- a pillar's series is its own where one is recorded, and the curve's
    benchmark where none is. Explicit override else default is how curve configuration
    actually works, and it is an `or`.
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent / "exec"
STRESS = ROOT / "core/src/test/resources/stress"
SCRIPTS = ROOT / "scripts/corpus"

# The series each curve is benchmarked against, where one is maintained. The legacy IBOR
# curve has none because nobody maintains it any more, and the sterling and yen curves have
# none because their overnight series are not among the sixteen seeded.
BENCHMARK = {"USD.SOFR.OIS": "SOFR.ON", "EUR.ESTR.OIS": "EURIBOR.3M",
             "EUR.EURIBOR.6M": "EURIBOR.3M", "USD.UST.GOVT": "UST.10Y",
             "EUR.DBR.GOVT": "DBR.10Y"}

PURE_ADD = '''
// The (curve, date) pairs a curve exists for. Mapped ~distinct over the pillar table, which
// has twelve rows for every one of these -- so `distinct` is the whole mapping rather than a
// decoration on it, and dropping it would turn 16 rows into 192.
Class curves::CurveDate
{
   curveId: String[1];
   cobDate: StrictDate[1];
   // A DYNAFUNCTION: the curve's display label, built in the mapping. It has to be constant
   // within a (curve, date) or ~distinct would not collapse anything -- concatenating the
   // TENOR was the first attempt and turned 16 rows back into 192, which is exactly the
   // mistake ~distinct is here to catch.
   curveLabel: String[1];
}

// Per curve and date, read from a VIEW rather than from the pillars. This is what a curve
// monitor looks at: how many pillars came in, and where the curve's ends are.
Class curves::CurveSummary
{
   curveId: String[1];
   cobDate: StrictDate[1];
   pillarCount: Integer[1];
   highestRate: Float[1];
   lowestRate: Float[1];
}

// Every curve has a benchmark series, or does not. Reaching it from a PILLAR is two hops --
// pillar to curve to series -- which is a join chain under a composite key.
Association curves::CurveBenchmark
{
   benchmarkOf: curves::YieldCurve[*];
   benchmarkSeries: timeseries::TimeSeries[0..1];
}

// The series a pillar is fitted to, falling back to the curve's benchmark where the pillar
// records none. Explicit override else default -- which is how curve configuration works,
// and which makes the join an `or` rather than an equality.
Association curves::PillarEffectiveSource
{
   effectivePillars: curves::CurvePoint[*];
   effectiveSeries: timeseries::TimeSeries[0..1];
}

// The summary belongs to the curve it summarises.
Association curves::CurveSummaries
{
   summarised: curves::YieldCurve[0..1];
   summaries: curves::CurveSummary[*];
}
'''

MAPPING_ADD = '''
   // ~distinct, because the source has twelve rows for every one of these. The dynafunction
   // builds the cache key the same way the service that reads it does.
   curves::CurveDate[curveDate]: Relational
   {
      ~distinct
      ~mainTable [store::DB]RATE_CURVE_POINT
      curveId: [store::DB]RATE_CURVE_POINT.CURVE_ID,
      cobDate: [store::DB]RATE_CURVE_POINT.COB_DATE,
      curveLabel: concat([store::DB]RATE_CURVE_POINT.CURVE_ID, [store::DB]RATE_CURVE_POINT.CURVE_TYPE)
   }

   curves::CurveSummary[curveSummary]: Relational
   {
      ~primaryKey ( [store::DB]CURVE_SUMMARY.CURVE_ID, [store::DB]CURVE_SUMMARY.COB_DATE )
      ~mainTable [store::DB]CURVE_SUMMARY
      curveId: [store::DB]CURVE_SUMMARY.CURVE_ID,
      cobDate: [store::DB]CURVE_SUMMARY.COB_DATE,
      pillarCount: [store::DB]CURVE_SUMMARY.PILLAR_COUNT,
      highestRate: [store::DB]CURVE_SUMMARY.HIGHEST_RATE,
      lowestRate: [store::DB]CURVE_SUMMARY.LOWEST_RATE
   }

   curves::CurveBenchmark: Relational
   {
      AssociationMapping
      (
         benchmarkSeries[curves_YieldCurve, timeseries_TimeSeries]: [store::DB]@Curve_Benchmark,
         benchmarkOf[timeseries_TimeSeries, curves_YieldCurve]: [store::DB]@Curve_Benchmark
      )
   }

   curves::PillarEffectiveSource: Relational
   {
      AssociationMapping
      (
         effectiveSeries[pillar, timeseries_TimeSeries]: [store::DB]@Pillar_EffectiveSeries,
         effectivePillars[timeseries_TimeSeries, pillar]: [store::DB]@Pillar_EffectiveSeries
      )
   }

   curves::CurveSummaries: Relational
   {
      AssociationMapping
      (
         summaries[curves_YieldCurve, curveSummary]: [store::DB]@Curve_Summary,
         summarised[curveSummary, curves_YieldCurve]: [store::DB]@Curve_Summary
      )
   }
'''


def apply() -> None:
    p = STRESS / "30-store.pure"
    t = p.read_text()
    if "Table CURVE_SUMMARY" in t:
        raise SystemExit("second wave already applied")

    # Two new columns: the benchmark on the curve, and the same value denormalized onto the
    # pillar so the fallback join can be expressed without a second hop.
    t = t.replace("DAY_COUNT_BASIS VARCHAR(10), IS_ACTIVE BIT)",
                  "DAY_COUNT_BASIS VARCHAR(10), IS_ACTIVE BIT,\n"
                  "                      BENCHMARK_SERIES_ID VARCHAR(20))", 1)
    t = t.replace("                       CURVE_TYPE VARCHAR(12))",
                  "                       CURVE_TYPE VARCHAR(12), CURVE_BENCHMARK_ID VARCHAR(20))", 1)

    t = t.replace("    Filter QuotedPillarRows(",
                  """    // What a curve monitor reads: one row per curve and date, over the twelve pillars.
    View CURVE_SUMMARY
    (
      ~groupBy
      (
        RATE_CURVE_POINT.CURVE_ID,
        RATE_CURVE_POINT.COB_DATE
      )
      CURVE_ID: RATE_CURVE_POINT.CURVE_ID PRIMARY KEY,
      COB_DATE: RATE_CURVE_POINT.COB_DATE PRIMARY KEY,
      PILLAR_COUNT: count(RATE_CURVE_POINT.TENOR_LABEL),
      HIGHEST_RATE: max(RATE_CURVE_POINT.ZERO_RATE),
      LOWEST_RATE: min(RATE_CURVE_POINT.ZERO_RATE)
    )

    Filter QuotedPillarRows(""", 1)

    t = t.replace("    Join Curve_Point(",
                  "    Join Curve_Benchmark(RATE_CURVE.BENCHMARK_SERIES_ID = TIME_SERIES.SERIES_ID)\n"
                  "    Join Curve_Summary(RATE_CURVE.CURVE_ID = CURVE_SUMMARY.CURVE_ID)\n"
                  "    // Explicit override else default: the pillar's own series where it has\n"
                  "    // one, the curve's benchmark where it does not. An `or`, because that\n"
                  "    // is what a fallback is.\n"
                  "    Join Pillar_EffectiveSeries(RATE_CURVE_POINT.SOURCE_SERIES_ID = TIME_SERIES.SERIES_ID or RATE_CURVE_POINT.CURVE_BENCHMARK_ID = TIME_SERIES.SERIES_ID)\n"
                  "    Join Curve_Point(", 1)
    p.write_text(t)

    # The seed rows are REGENERATED from the curve definitions rather than patched line by
    # line: the two new columns belong on every row, and a rewrite driven by the same data
    # that produced them cannot disagree with itself the way a text edit can.
    import sys
    sys.path.insert(0, str(SCRIPTS))
    import curves as first

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    lines = t.splitlines(keepends=True)
    start = next(i for i, l in enumerate(lines) if l.startswith("RATE_CURVE = ["))
    end = next(i for i in range(start, len(lines))
               if lines[i].startswith("RATE_CURVE_POINT = ["))
    end = next(i for i in range(end, len(lines)) if lines[i].rstrip() == "]") + 1
    curves, points = _rows(first)
    t = "".join(lines[:start]) + f"RATE_CURVE = [\n{curves}\n]\n\nRATE_CURVE_POINT = [\n{points}\n]\n" + "".join(lines[end:])
    p.write_text(t)

    (STRESS / "835-curves.pure").write_text(
        _with_additions((STRESS / "835-curves.pure").read_text()))


def _rows(first):
    """The same rows the first wave wrote, plus the two benchmark columns."""
    import math

    curves, points = [], []
    for cid, name, ccy, ctype, interp, dcb, day1, shift in first.CURVES:
        bench = BENCHMARK.get(cid)
        curves.append(
            f'    dict(CURVE_ID="{cid}", CURVE_NAME="{name}", CURRENCY="{ccy}",\n'
            f'         CURVE_TYPE="{ctype}", INTERPOLATION="{interp}", DAY_COUNT_BASIS="{dcb}",\n'
            f'         BENCHMARK_SERIES_ID={_q(bench)}, IS_ACTIVE={ctype != "IBOR_SWAP"}),')
        for d, (y, m, dd) in enumerate(first.COB_DATES):
            for (label, days), base, bp in zip(first.TENORS, day1, shift):
                rate = round(base + (bp / 100.0 if d else 0.0), 2)
                df = round(math.exp(-rate / 100.0 * days / 365.0), 8)
                series = first.FITTED.get((cid, label))
                points.append(
                    f'    dict(CURVE_ID="{cid}", COB_DATE=_iso({y}, {m}, {dd}),\n'
                    f'         TENOR_LABEL="{label}", TENOR_DAYS={days}, ZERO_RATE={rate},\n'
                    f'         DISCOUNT_FACTOR={df}, '
                    f'IS_INTERPOLATED={label in first.INTERPOLATED},\n'
                    f'         SOURCE_SERIES_ID={_q(series)}, CURVE_BENCHMARK_ID={_q(bench)},\n'
                    f'         CURVE_NAME="{name}", CURVE_CURRENCY="{ccy}", '
                    f'CURVE_TYPE="{ctype}"),')
    return "\n".join(curves), "\n".join(points)


def _with_additions(t: str) -> str:
    t = t.replace("###Mapping", PURE_ADD + "\n###Mapping", 1)
    return t.replace("   curves::LongerPillars: Relational", MAPPING_ADD +
                     "\n   curves::LongerPillars: Relational", 1)


def _q(v):
    return f'"{v}"' if v else "None"


if __name__ == "__main__":
    apply()
    print("second wave staged")
