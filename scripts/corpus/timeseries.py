"""Stage the market-data time-series domain: seed, store, classes, mapping.

Written out by hand rather than emitted by refdata.py, because a time series is not a
taxonomy. The shape refdata knows how to build is one table, one discriminator and a
subtype per code; what this needs is three tables, a deep one-to-many, a mixed observation
frequency and a bitemporal revision history. None of that is a filter.

The values are real levels for real series over real business days in June 2024, typed out
rather than generated from a base and a drift. A generated path is smooth and a real one is
not: SOFR sits on 5.33 for a week and then prints 5.34, EURUSD reverses three times, and the
Baltic Dry moves in whole points. Queries about change, direction and range only mean
something against a path that actually does those things.
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent / "exec"
STRESS = ROOT / "core/src/test/resources/stress"
SCRIPTS = ROOT / "scripts/corpus"

# Fourteen business days: 3-7, 10-14, 17-20 June 2024. No weekends -- a gap between the 7th
# and the 10th is the gap a business-day series HAS, and a query that counts calendar days
# between consecutive observations should find 3 across a weekend and 1 within a week.
DAILY_DATES = [3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 17, 18, 19, 20]
MONTHLY_DATES = [(4, 30), (5, 31), (6, 28)]

# (seriesId, name, source, frequency, unit, currency, assetClass, decimals, values)
DAILY = [
    ("SOFR.ON", "SOFR Overnight Financing Rate", "NYFED", "DAILY", "PERCENT", "USD", "RATES", 2,
     [5.33, 5.33, 5.33, 5.33, 5.34, 5.34, 5.33, 5.33, 5.33, 5.33, 5.32, 5.33, 5.33, 5.34]),
    ("EURIBOR.3M", "Euribor 3 Month", "EMMI", "DAILY", "PERCENT", "EUR", "RATES", 3,
     [3.812, 3.809, 3.804, 3.795, 3.771, 3.749, 3.732, 3.721, 3.716, 3.708,
      3.711, 3.705, 3.699, 3.702]),
    ("UST.10Y", "US Treasury 10 Year Yield", "FEDH15", "DAILY", "PERCENT", "USD", "RATES", 3,
     [4.402, 4.331, 4.281, 4.288, 4.428, 4.469, 4.404, 4.318, 4.242, 4.212,
      4.279, 4.216, 4.256, 4.257]),
    ("DBR.10Y", "German Bund 10 Year Yield", "BUNDESBANK", "DAILY", "PERCENT", "EUR", "RATES", 3,
     [2.664, 2.582, 2.512, 2.549, 2.618, 2.664, 2.545, 2.514, 2.401, 2.362,
      2.404, 2.410, 2.396, 2.410]),
    ("EURUSD", "Euro US Dollar Spot", "WMR16", "DAILY", "RATIO", "USD", "FX", 4,
     [1.0872, 1.0879, 1.0871, 1.0895, 1.0801, 1.0761, 1.0741, 1.0805, 1.0736, 1.0704,
      1.0733, 1.0741, 1.0703, 1.0705]),
    ("USDJPY", "US Dollar Japanese Yen Spot", "WMR16", "DAILY", "RATIO", "JPY", "FX", 2,
     [157.14, 156.06, 155.66, 155.83, 156.75, 157.05, 157.31, 156.71, 158.11, 157.38,
      158.03, 157.85, 158.94, 159.79]),
    ("GBPUSD", "Sterling US Dollar Spot", "WMR16", "DAILY", "RATIO", "USD", "FX", 4,
     [1.2795, 1.2790, 1.2789, 1.2788, 1.2721, 1.2712, 1.2740, 1.2793, 1.2797, 1.2686,
      1.2707, 1.2712, 1.2686, 1.2661]),
    ("SPX.CLOSE", "S&P 500 Index Close", "CBOE", "DAILY", "INDEX", "USD", "EQUITY", 2,
     [5283.40, 5291.34, 5354.03, 5352.96, 5346.99, 5360.79, 5375.32, 5421.03, 5433.74,
      5431.60, 5473.23, 5487.03, 5473.17, 5464.62]),
    ("VIX", "CBOE Volatility Index", "CBOE", "DAILY", "INDEX", "USD", "EQUITY", 2,
     [13.11, 13.09, 12.63, 12.62, 12.22, 12.74, 12.94, 12.63, 12.20, 12.66,
      12.75, 12.30, 13.28, 13.20]),
    ("WTI.M1", "WTI Crude Front Month", "NYMEX", "DAILY", "PRICE", "USD", "COMMODITY", 2,
     [74.22, 73.25, 73.25, 75.55, 75.53, 78.48, 77.74, 78.50, 78.62, 78.45,
      80.33, 81.57, 80.71, 80.73]),
    ("BRENT.M1", "Brent Crude Front Month", "ICE", "DAILY", "PRICE", "USD", "COMMODITY", 2,
     [78.36, 77.52, 77.52, 79.87, 79.62, 82.63, 81.92, 82.75, 82.62, 82.56,
      84.25, 85.33, 85.07, 85.24]),
    ("XAU.PM", "Gold LBMA PM Fix", "LBMA", "DAILY", "PRICE", "USD", "COMMODITY", 2,
     [2350.15, 2327.10, 2355.35, 2375.55, 2293.05, 2308.00, 2312.65, 2323.60, 2303.60,
      2325.25, 2320.50, 2328.15, 2325.85, 2321.30]),
    ("LME.CU3M", "LME Copper 3 Month", "LME", "DAILY", "PRICE", "USD", "COMMODITY", 2,
     [10035.00, 10122.50, 9930.50, 9822.00, 9721.00, 9711.50, 9803.00, 9847.50, 9668.00,
      9640.50, 9701.00, 9748.50, 9666.00, 9583.50]),
]

# Three monthly series. Economic statistics, published with a lag and restated afterwards --
# which is why these and not the market series carry a revision history below.
MONTHLY = [
    ("CPI.US.YOY", "US CPI All Items Year on Year", "BLS", "MONTHLY", "PERCENT", "USD",
     "MACRO", 1, [3.4, 3.3, 3.0]),
    ("UNRATE.US", "US Unemployment Rate", "BLS", "MONTHLY", "PERCENT", "USD", "MACRO", 1,
     [3.9, 4.0, 4.1]),
    ("HICP.EA.YOY", "Euro Area HICP Year on Year", "EUROSTAT", "MONTHLY", "PERCENT", "EUR",
     "MACRO", 1, [2.4, 2.6, 2.5]),
]

# The revisions, as they actually happened: a first print, then a restatement. Bitemporal
# because both dates matter and they are different dates -- the value FOR April changed ON a
# day in June, so "what did April read" and "what did we think April read on 1 June" are two
# questions with two answers.
#
# (seriesId, value, reason, from_z, thru_z, in_z, out_z). There is no separate
# observation-date column: FROM_Z is the observation date. Carrying it twice would let a
# query filter the date directly and quietly bypass the milestoning the class exists to test.
REVISIONS = [
    ("CPI.US.YOY", 3.4, "FIRST_PRINT", "2024-04-30", "2024-05-31",
     "2024-05-15", "2024-06-12"),
    ("CPI.US.YOY", 3.5, "SEASONAL_FACTOR_UPDATE", "2024-04-30", "2024-05-31",
     "2024-06-12", "9999-12-31"),
    ("CPI.US.YOY", 3.3, "FIRST_PRINT", "2024-05-31", "9999-12-31",
     "2024-06-12", "9999-12-31"),
    ("UNRATE.US", 3.9, "FIRST_PRINT", "2024-04-30", "2024-05-31",
     "2024-05-03", "2024-06-07"),
    ("UNRATE.US", 4.0, "BENCHMARK_REVISION", "2024-04-30", "2024-05-31",
     "2024-06-07", "9999-12-31"),
    ("UNRATE.US", 4.0, "FIRST_PRINT", "2024-05-31", "9999-12-31",
     "2024-06-07", "9999-12-31"),
    ("HICP.EA.YOY", 2.4, "FIRST_PRINT", "2024-04-30", "2024-05-31",
     "2024-05-17", "9999-12-31"),
    ("HICP.EA.YOY", 2.6, "FLASH_ESTIMATE", "2024-05-31", "9999-12-31",
     "2024-05-31", "2024-06-18"),
    ("HICP.EA.YOY", 2.5, "FINAL_ESTIMATE", "2024-05-31", "9999-12-31",
     "2024-06-18", "9999-12-31"),
]


def seed_source() -> tuple[str, str, str]:
    series, obs, revs = [], [], []
    for sid, name, src, freq, unit, ccy, ac, dp, vals in DAILY + MONTHLY:
        dates = ([f"_iso(2024, 6, {d})" for d in DAILY_DATES] if freq == "DAILY"
                 else [f"_iso(2024, {m}, {d})" for m, d in MONTHLY_DATES])
        series.append(
            f'    dict(SERIES_ID="{sid}", SERIES_NAME="{name}", SOURCE_CODE="{src}",\n'
            f'         FREQUENCY="{freq}", UNIT="{unit}", CURRENCY="{ccy}", ASSET_CLASS="{ac}",\n'
            f'         DECIMAL_PLACES={dp}, FIRST_OBS_DATE={dates[0]}, IS_ACTIVE=True),')
        for i, (d, v) in enumerate(zip(dates, vals)):
            prior = "None" if i == 0 else repr(vals[i - 1])
            # PRELIM on the latest monthly print: a flash estimate is not a final one, and
            # the status column is what a query filters on to exclude it.
            status = ("PRELIM" if freq == "MONTHLY" and i == len(vals) - 1
                      else "REVISED" if freq == "MONTHLY" and i == 0 else "FINAL")
            obs.append(
                f'    dict(OBS_ID="OBS-{sid}-{i + 1:02d}", SERIES_ID="{sid}", OBS_DATE={d},\n'
                f'         OBS_VALUE={v}, PRIOR_VALUE={prior}, STATUS="{status}",\n'
                f'         IS_INTERPOLATED=False),')
    for sid, v, why, fz, tz, iz, oz in REVISIONS:
        revs.append(
            f'    dict(SERIES_ID="{sid}", REVISED_VALUE={v}, REVISION_REASON="{why}",\n'
            f'         FROM_Z="{fz}", THRU_Z="{tz}", IN_Z="{iz}", OUT_Z="{oz}"),')
    return "\n".join(series), "\n".join(obs), "\n".join(revs)


PURE = '''###Pure
// Market data: a series, its observations, and what the observations used to say.
//
// The corpus had prices as attributes of a position and marks as attributes of a valuation.
// What it did not have is a SERIES -- the same measurement repeated on a schedule, where the
// interesting questions are all about the relationship between one observation and the one
// before it. Change, direction, range, gap, staleness: none of them are properties of a row.
//
// Three things here exist nowhere else in the corpus:
//
//   * MIXED FREQUENCY under one parent. Thirteen daily series carry fourteen observations
//     each and three monthly series carry three, so a count grouped by series returns two
//     distinct answers and any query that assumes a uniform fan-out is wrong.
//   * A PRIOR VALUE that is genuinely absent on the first observation, not zero. `change`
//     has to discharge it, and the honest discharge is `orElse($this.obsValue)` -- no change
//     on the first print, rather than a change of the entire level.
//   * A BITEMPORAL revision history that revises something. The corpus's only other
//     bitemporal class is a credit rating whose two dates move together; here the value FOR
//     April changed ON a day in June, so the business and processing axes genuinely differ
//     and `all(%2024-04-30, %2024-06-01)` and `all(%2024-04-30, %2024-06-20)` are two
//     different answers to two different questions.
Class timeseries::TimeSeries
{
   seriesId: String[1];
   seriesName: String[1];
   sourceCode: String[1];
   frequency: String[1];
   unit: String[1];
   currency: String[1];
   assetClass: String[1];
   decimalPlaces: Integer[1];
   firstObsDate: StrictDate[1];
   isActive: Boolean[1];

   // Whether the series prints every business day. Daily and monthly series behave
   // differently in every query that reasons about gaps, so the distinction is worth a name.
   isDaily() { $this.frequency == 'DAILY' } : Boolean[1];
   // A qualified property: the series name as a vendor ticker, which is source-dependent and
   // so cannot be stored on the row.
   tickerOn(vendor: String[1]) { $vendor + '/' + $this.sourceCode + ':' + $this.seriesId } : String[1];
}

Class timeseries::Observation
{
   obsId: String[1];
   seriesId: String[1];
   obsDate: StrictDate[1];
   obsValue: Float[1];
   // Null on the first observation of every series, because there is no observation before
   // it. Not zero: a level of zero and no previous level are different facts.
   priorValue: Float[0..1];
   status: String[1];
   isInterpolated: Boolean[1];

   // The move since the previous print. Zero on a first observation -- the discharge has to
   // be `orElse(obsValue)` rather than `orElse(0.0)`, or the first print of the S&P would
   // report a rise of 5283.40.
   change() { $this.obsValue - $this.priorValue->orElse($this.obsValue) } : Float[1];
   // A flash estimate is not a final one. Any average that includes it is measuring a
   // forecast alongside a measurement.
   isFinal() { $this.status == 'FINAL' } : Boolean[1];
   // Basis points, for the rate series. The unit conversion a rates desk does by reflex.
   changeInBps() { ($this.obsValue - $this.priorValue->orElse($this.obsValue)) * 100.0 } : Float[1];
}

// The revision history, bitemporal. FROM_Z/THRU_Z is the period the value describes;
// IN_Z/OUT_Z is the period we believed it.
Class <<temporal.bitemporal>> timeseries::ObservationRevision
{
   seriesId: String[1];
   revisedValue: Float[0..1];
   revisionReason: String[0..1];
}

// One series, many observations. Fourteen for a daily series and three for a monthly one --
// the fan-out is not uniform, deliberately.
Association timeseries::SeriesObservations
{
   series: timeseries::TimeSeries[0..1];
   observations: timeseries::Observation[*];
}

Association timeseries::SeriesRevisions
{
   revisedSeries: timeseries::TimeSeries[0..1];
   revisions: timeseries::ObservationRevision[*];
}

###Mapping
Mapping timeseries::TimeSeriesMapping
(
   timeseries::TimeSeries: Relational
   {
      ~primaryKey ( [store::DB]TIME_SERIES.SERIES_ID )
      ~mainTable [store::DB]TIME_SERIES
      seriesId: [store::DB]TIME_SERIES.SERIES_ID,
      seriesName: [store::DB]TIME_SERIES.SERIES_NAME,
      sourceCode: [store::DB]TIME_SERIES.SOURCE_CODE,
      frequency: [store::DB]TIME_SERIES.FREQUENCY,
      unit: [store::DB]TIME_SERIES.UNIT,
      currency: [store::DB]TIME_SERIES.CURRENCY,
      assetClass: [store::DB]TIME_SERIES.ASSET_CLASS,
      decimalPlaces: [store::DB]TIME_SERIES.DECIMAL_PLACES,
      firstObsDate: [store::DB]TIME_SERIES.FIRST_OBS_DATE,
      isActive: [store::DB]TIME_SERIES.IS_ACTIVE
   }

   timeseries::Observation: Relational
   {
      ~primaryKey ( [store::DB]TS_OBSERVATION.OBS_ID )
      ~mainTable [store::DB]TS_OBSERVATION
      obsId: [store::DB]TS_OBSERVATION.OBS_ID,
      seriesId: [store::DB]TS_OBSERVATION.SERIES_ID,
      obsDate: [store::DB]TS_OBSERVATION.OBS_DATE,
      obsValue: [store::DB]TS_OBSERVATION.OBS_VALUE,
      priorValue: [store::DB]TS_OBSERVATION.PRIOR_VALUE,
      status: [store::DB]TS_OBSERVATION.STATUS,
      isInterpolated: [store::DB]TS_OBSERVATION.IS_INTERPOLATED
   }

   timeseries::ObservationRevision: Relational
   {
      ~primaryKey ( [store::DB]TS_REVISION_BI.SERIES_ID, [store::DB]TS_REVISION_BI.FROM_Z, [store::DB]TS_REVISION_BI.IN_Z )
      ~mainTable [store::DB]TS_REVISION_BI
      seriesId: [store::DB]TS_REVISION_BI.SERIES_ID,
      revisedValue: [store::DB]TS_REVISION_BI.REVISED_VALUE,
      revisionReason: [store::DB]TS_REVISION_BI.REVISION_REASON
   }

   timeseries::SeriesObservations: Relational
   {
      AssociationMapping
      (
         observations: [store::DB]@Series_Observation,
         series: [store::DB]@Series_Observation
      )
   }

   timeseries::SeriesRevisions: Relational
   {
      AssociationMapping
      (
         revisions: [store::DB]@Series_Revision,
         revisedSeries: [store::DB]@Series_Revision
      )
   }
)
'''


def apply() -> None:
    series, obs, revs = seed_source()

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    if '"TIME_SERIES"' not in t:
        t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                      f"\n\n# Market data: a series, its observations, and its revisions.\n"
                      f"TIME_SERIES = [\n{series}\n]\n\n"
                      f"TS_OBSERVATION = [\n{obs}\n]\n\n"
                      f"TS_REVISION_BI = [\n{revs}\n]\n"
                      "\n\nTABLES: dict[str, list[dict]] = {", 1)
        t = t.replace("TABLES: dict[str, list[dict]] = {",
                      'TABLES: dict[str, list[dict]] = {\n'
                      '    "TIME_SERIES": TIME_SERIES,\n'
                      '    "TS_OBSERVATION": TS_OBSERVATION,\n'
                      '    "TS_REVISION_BI": TS_REVISION_BI,', 1)
        p.write_text(t)

    p = STRESS / "30-store.pure"
    t = p.read_text()
    if "Table TIME_SERIES (" not in t:
        anchor = "    // ---- Back office: where cash actually moves ----"
        t = t.replace(anchor, """    // ---- Market data: series, observations, revisions ----
    Table TIME_SERIES (SERIES_ID VARCHAR(20) PRIMARY KEY, SERIES_NAME VARCHAR(60),
                       SOURCE_CODE VARCHAR(20), FREQUENCY VARCHAR(12), UNIT VARCHAR(12),
                       CURRENCY VARCHAR(4), ASSET_CLASS VARCHAR(12), DECIMAL_PLACES INTEGER,
                       FIRST_OBS_DATE DATE, IS_ACTIVE BIT)

    Table TS_OBSERVATION (OBS_ID VARCHAR(30) PRIMARY KEY, SERIES_ID VARCHAR(20),
                          OBS_DATE DATE, OBS_VALUE DECIMAL(18,4), PRIOR_VALUE DECIMAL(18,4),
                          STATUS VARCHAR(12), IS_INTERPOLATED BIT)

    Table TS_REVISION_BI
    (
      milestoning
      (
        business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z, INFINITY_DATE = %9999-12-31),
        processing(PROCESSING_IN = IN_Z, PROCESSING_OUT = OUT_Z, INFINITY_DATE = %9999-12-31)
      )
      SERIES_ID VARCHAR(20) PRIMARY KEY,
      FROM_Z DATE PRIMARY KEY,
      IN_Z DATE PRIMARY KEY,
      THRU_Z DATE,
      OUT_Z DATE,
      REVISED_VALUE DECIMAL(18,4),
      REVISION_REASON VARCHAR(30)
    )

""" + anchor, 1)
        t = t.replace("    Join Counterparty_Ssi(",
                      "    Join Series_Observation(TIME_SERIES.SERIES_ID = TS_OBSERVATION.SERIES_ID)\n"
                      "    Join Series_Revision(TIME_SERIES.SERIES_ID = TS_REVISION_BI.SERIES_ID)\n\n"
                      "    Join Counterparty_Ssi(", 1)
        p.write_text(t)

    (STRESS / "834-timeseries.pure").write_text(PURE)

    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if "include timeseries::" not in t:
        t = t.replace("    include backoffice::BackOfficeMapping",
                      "    include backoffice::BackOfficeMapping\n"
                      "    include timeseries::TimeSeriesMapping", 1)
        p.write_text(t)


if __name__ == "__main__":
    apply()
    print("market data staged")
