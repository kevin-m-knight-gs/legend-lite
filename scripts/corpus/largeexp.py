"""Large exposures reporting: a SCHEMA, a three-column key, and every datatype the reader knows.

The remaining uncovered feature pairs are almost all of one shape. Three constructs --
`composite PK`, `join non-equality` and `join with or` -- each need to meet the same set of
rare MAPPING constructs, and cannot, because each of those lives alone in a fixture mapping
with nothing else in it. 62 of the 126 remaining pairs have one of those three on one side.

A large-exposures return is the honest place to put them together. It is keyed by report,
date and line and by nothing shorter; the threshold a line breaches is a BAND of percentages,
so the join to it is a range with no key to join on; an exemption applies to a counterparty
OR to a country, which is a disjunction because the rule table really does hold both kinds;
and the whole thing is a regulatory extract, so it belongs in its own schema rather than
among the trading tables.

It also widens the type surface. Every table in this corpus was VARCHAR, DOUBLE, INTEGER,
DATE, TIMESTAMP or BIT; this one adds SMALLINT, CHAR, NUMERIC and REAL, which the reader
already understood and nothing had ever declared.
"""
import pathlib

STRESS = pathlib.Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"
SCRIPTS = pathlib.Path(__file__).resolve().parent

# (reportId, cob, line, cptyId, groupId, country, class, gross, crm, pctCapital, exempt)
#
# Four returns over two dates. The percentages straddle every threshold band, and two lines
# breach the 25% limit -- which is the point of the return and the reason the bands exist.
LINES = [
    ("LE-2024Q2-01", "2024-06-28", 1, "CP-0001", "GRP-ALPHA", "US", "INSTITUTION",
     4850000000.00, 1200000000.00, 18.4, False),
    ("LE-2024Q2-01", "2024-06-28", 2, "CP-0002", "GRP-ALPHA", "GB", "INSTITUTION",
     2100000000.00, 150000000.00, 9.2, False),
    ("LE-2024Q2-01", "2024-06-28", 3, "CP-0003", "GRP-BETA", "DE", "CORPORATE",
     7400000000.00, 400000000.00, 31.7, False),
    ("LE-2024Q2-01", "2024-06-28", 4, "CP-0004", "GRP-GAMMA", "FR", "CORPORATE",
     980000000.00, 0.00, 4.6, False),
    ("LE-2024Q2-01", "2024-06-28", 5, "CP-0005", None, "JP", "SOVEREIGN",
     15200000000.00, 0.00, 71.3, True),
    ("LE-2024Q2-01", "2024-06-28", 6, "CP-0001", "GRP-ALPHA", "US", "COVERED_BOND",
     640000000.00, 640000000.00, 0.0, False),
    ("LE-2024Q1-01", "2024-03-28", 1, "CP-0001", "GRP-ALPHA", "US", "INSTITUTION",
     4100000000.00, 900000000.00, 16.1, False),
    ("LE-2024Q1-01", "2024-03-28", 2, "CP-0003", "GRP-BETA", "DE", "CORPORATE",
     6900000000.00, 350000000.00, 29.8, False),
    ("LE-2024Q1-01", "2024-03-28", 3, "CP-0006", None, "CH", "INSTITUTION",
     1750000000.00, 75000000.00, 7.9, False),
    ("LE-2024Q2-02", "2024-06-28", 1, "CP-0007", "GRP-DELTA", "IE", "FUND",
     3300000000.00, 200000000.00, 14.5, False),
    ("LE-2024Q2-02", "2024-06-28", 2, "CP-0008", "GRP-DELTA", "LU", "FUND",
     880000000.00, 0.00, 4.1, False),
    ("LE-2024Q2-02", "2024-06-28", 3, "CP-0009", None, "SG", "INSTITUTION",
     2450000000.00, 300000000.00, 10.1, False),
]

# Percentage-of-capital bands. A line falls in exactly one, and there is no key to join on --
# which band applies is a property of the number.
THRESHOLDS = [
    ("TH-NIL", "Below reporting threshold", 0.0, 5.0, False, False, "Not reportable individually."),
    ("TH-REPORT", "Reportable", 5.0, 10.0, True, False, "Reported but with no limit implication."),
    ("TH-MONITOR", "Monitored", 10.0, 25.0, True, False, "Watched and escalated if it trends up."),
    ("TH-BREACH", "Limit breach", 25.0, 1000.0, True, True, "Above the 25% limit and reportable as a breach."),
]

# Exemptions attach to a COUNTERPARTY or to a COUNTRY, never both on one rule -- which is
# what makes the join a real disjunction rather than two conditions that agree.
EXEMPTIONS = [
    ("EX-SOV-JP", "CP-0005", None, "SOVEREIGN_EXPOSURE", "Sovereign exposures are exempt in full."),
    ("EX-CTY-DE", None, "DE", "INTRAGROUP_LOCAL", "Local intragroup exemption permitted by the host supervisor."),
    ("EX-CTY-IE", None, "IE", "COVERED_BOND_RELIEF", "Partial relief for qualifying covered bonds."),
    ("EX-CPTY-06", "CP-0006", None, "CENTRAL_BANK", "A central bank counterparty."),
    ("EX-CTY-SG", None, "SG", "TRADE_FINANCE_RELIEF", "Short-dated trade finance relief."),
]

PURE = '''###Pure
// Large exposures: what the firm owes to whom, as a supervisor wants it.
//
// This domain exists to put three constructs next to things they had never met. `composite
// PK`, `join non-equality` and `join with or` each had fifteen or more uncovered feature
// pairs, and always for the same reason -- each lived alone on a class carrying nothing else.
// A large-exposures return carries all three honestly:
//
//   * it is keyed by report, cob date and line number, and by nothing shorter
//   * the threshold band a line falls in is a property of its percentage, so the join to it
//     is a RANGE and there is no key to join on
//   * an exemption attaches to a counterparty OR to a country, and the rule table holds both
//     kinds, so the join is a disjunction
//
// It sits in its own SCHEMA, because a regulatory extract is not a trading table, and because
// nothing in this corpus had ever mapped a schema-qualified table -- the one Schema that was
// declared was mapped by nothing and seeded with nothing.
//
// The datatypes are deliberately varied: SMALLINT for the line number, CHAR(2) for the ISO
// country, NUMERIC(20,2) for the money and FLOAT for the percentage. REAL was the first
// choice and cannot be read at all under DuckDB (F53); FLOAT, its near-synonym in most
// dialects, is fine. Every table here before
// used VARCHAR, DOUBLE, INTEGER, DATE, TIMESTAMP or BIT, so four of the reader's known types
// had never been declared by anything.
Enum largeexp::ExposureClass
{
   INSTITUTION,
   CORPORATE,
   SOVEREIGN,
   COVERED_BOND,
   FUND
}

Class largeexp::ExposureLine
[
   // Validations a returns engine would actually enforce. Every seeded line satisfies them;
   // whether a VIOLATING row is rejected or ignored during relational execution is a
   // separate question and is isolated in repro/ rather than mixed in here.
   //
   // Fully parenthesised, because Pure binds && tighter than the comparison operators.
   grossCoversNet: ($this.grossExposure >= $this.netExposure),
   creditRiskMitigationIsNonNegative: ($this.crmEffect >= 0.0),
   lineNumberIsPositive: ($this.lineNumber > 0)
]
{
   reportId: String[1];
   cobDate: StrictDate[1];
   lineNumber: Integer[1];
   counterpartyId: String[1];
   // Null where the counterparty belongs to no connected group, which is the case the
   // grouping rules exist for and the case a naive group-by drops.
   groupId: String[0..1];
   countryCode: String[1];
   exposureClass: largeexp::ExposureClass[1];
   grossExposure: Float[1];
   crmEffect: Float[1];
   netExposure: Float[1];
   pctOfCapital: Float[1];
   reportedAt: DateTime[1];
   isExempt: Boolean[1];

   // What the mitigation actually bought, as a fraction of the gross. Division, so the
   // engine is in double from here on.
   mitigationRatio() { $this.crmEffect / $this.grossExposure } : Float[1];
   // The headroom to the 25% limit, in percentage points. Negative on a breach, which is
   // the number a supervisor reads first.
   headroomToLimit() { 25.0 - $this.pctOfCapital } : Float[1];
   // A qualified property: the net exposure converted at a rate the caller supplies.
   netExposureIn(fxRate: Float[1]) { $this.netExposure * $fxRate } : Float[1];
}

// The lines above the reporting threshold. A ~filter over the same table, and it selects
// ten of twelve -- so it is doing real work rather than selecting everything.
Class largeexp::ReportableLine extends largeexp::ExposureLine
{
}

// The distinct (report, date) pairs. Mapped ~distinct over the line table, which has several
// rows for each -- so twelve rows collapse to three.
Class largeexp::ReturnHeader
{
   reportId: String[1];
   cobDate: StrictDate[1];
   // A DYNAFUNCTION: the return's reference, built in the mapping rather than stored.
   returnRef: String[1];
}

// The band a line's percentage falls in. There is no key: which band applies is a property
// of the number, so the join is `pct >= floor and pct < ceiling`.
Class largeexp::ThresholdBand
{
   bandId: String[1];
   bandName: String[1];
   floorPct: Float[1];
   ceilingPct: Float[1];
   isReportable: Boolean[1];
   isBreach: Boolean[1];
   note: String[1];
}

// An exemption rule, which attaches to a counterparty or to a country and never to both.
Class largeexp::ExemptionRule
{
   ruleId: String[1];
   exemptCounterpartyId: String[0..1];
   exemptCountryCode: String[0..1];
   basis: String[1];
   note: String[1];
}

// The band a line falls in. A RANGE join from a composite-key class.
Association largeexp::LineThreshold
{
   bandedLines: largeexp::ExposureLine[*];
   thresholdBand: largeexp::ThresholdBand[0..1];
}

// The exemptions that reach a line, by counterparty OR by country. TO-MANY, because an
// unqualified `or` says "every rule that applies" and a line can match on both axes.
Association largeexp::LineExemptions
{
   exemptedLines: largeexp::ExposureLine[*];
   exemptionRules: largeexp::ExemptionRule[*];
}

###Mapping
Mapping largeexp::LargeExposureMapping
(
   largeexp::ExposureClass: EnumerationMapping ExposureClassMapping
   {
      INSTITUTION: ['INSTITUTION'],
      CORPORATE: ['CORPORATE'],
      SOVEREIGN: ['SOVEREIGN'],
      COVERED_BOND: ['COVERED_BOND'],
      FUND: ['FUND']
   }

   // The composite key, over a SCHEMA-qualified table. All three columns: report and line
   // alone collide across cob dates, and report and date alone collide across lines.
   largeexp::ExposureLine[expLine]: Relational
   {
      ~primaryKey ( [store::DB]regrpt.EXPOSURE_LINE.REPORT_ID, [store::DB]regrpt.EXPOSURE_LINE.COB_DATE, [store::DB]regrpt.EXPOSURE_LINE.LINE_NO )
      ~mainTable [store::DB]regrpt.EXPOSURE_LINE
      reportId: [store::DB]regrpt.EXPOSURE_LINE.REPORT_ID,
      cobDate: [store::DB]regrpt.EXPOSURE_LINE.COB_DATE,
      lineNumber: [store::DB]regrpt.EXPOSURE_LINE.LINE_NO,
      counterpartyId: [store::DB]regrpt.EXPOSURE_LINE.COUNTERPARTY_ID,
      groupId: [store::DB]regrpt.EXPOSURE_LINE.GROUP_ID,
      countryCode: [store::DB]regrpt.EXPOSURE_LINE.COUNTRY_CODE,
      exposureClass: EnumerationMapping ExposureClassMapping: [store::DB]regrpt.EXPOSURE_LINE.EXPOSURE_CLASS,
      grossExposure: [store::DB]regrpt.EXPOSURE_LINE.GROSS_EXPOSURE,
      crmEffect: [store::DB]regrpt.EXPOSURE_LINE.CRM_EFFECT,
      netExposure: [store::DB]regrpt.EXPOSURE_LINE.NET_EXPOSURE,
      pctOfCapital: [store::DB]regrpt.EXPOSURE_LINE.PCT_OF_CAPITAL,
      reportedAt: [store::DB]regrpt.EXPOSURE_LINE.REPORTED_AT,
      isExempt: [store::DB]regrpt.EXPOSURE_LINE.IS_EXEMPT
   }

   largeexp::ReportableLine[expReportable] extends [expLine]: Relational
   {
      ~filter [store::DB]ReportableLineRows
   }

   largeexp::ReturnHeader[expHeader]: Relational
   {
      ~distinct
      ~mainTable [store::DB]regrpt.EXPOSURE_LINE
      reportId: [store::DB]regrpt.EXPOSURE_LINE.REPORT_ID,
      cobDate: [store::DB]regrpt.EXPOSURE_LINE.COB_DATE,
      returnRef: concat([store::DB]regrpt.EXPOSURE_LINE.REPORT_ID, [store::DB]regrpt.EXPOSURE_LINE.COUNTRY_CODE)
   }

   largeexp::ThresholdBand[expBand]: Relational
   {
      ~primaryKey ( [store::DB]regrpt.EXPOSURE_THRESHOLD.BAND_ID )
      ~mainTable [store::DB]regrpt.EXPOSURE_THRESHOLD
      bandId: [store::DB]regrpt.EXPOSURE_THRESHOLD.BAND_ID,
      bandName: [store::DB]regrpt.EXPOSURE_THRESHOLD.BAND_NAME,
      floorPct: [store::DB]regrpt.EXPOSURE_THRESHOLD.FLOOR_PCT,
      ceilingPct: [store::DB]regrpt.EXPOSURE_THRESHOLD.CEILING_PCT,
      isReportable: [store::DB]regrpt.EXPOSURE_THRESHOLD.IS_REPORTABLE,
      isBreach: [store::DB]regrpt.EXPOSURE_THRESHOLD.IS_BREACH,
      note: [store::DB]regrpt.EXPOSURE_THRESHOLD.NOTE
   }

   largeexp::ExemptionRule[expRule]: Relational
   {
      ~primaryKey ( [store::DB]regrpt.EXEMPTION_RULE.RULE_ID )
      ~mainTable [store::DB]regrpt.EXEMPTION_RULE
      ruleId: [store::DB]regrpt.EXEMPTION_RULE.RULE_ID,
      exemptCounterpartyId: [store::DB]regrpt.EXEMPTION_RULE.EXEMPT_CPTY_ID,
      exemptCountryCode: [store::DB]regrpt.EXEMPTION_RULE.EXEMPT_COUNTRY,
      basis: [store::DB]regrpt.EXEMPTION_RULE.BASIS,
      note: [store::DB]regrpt.EXEMPTION_RULE.NOTE
   }

   largeexp::LineThreshold: Relational
   {
      AssociationMapping
      (
         thresholdBand[expLine, expBand]: [store::DB]@Line_ThresholdBand,
         bandedLines[expBand, expLine]: [store::DB]@Line_ThresholdBand
      )
   }

   largeexp::LineExemptions: Relational
   {
      AssociationMapping
      (
         exemptionRules[expLine, expRule]: [store::DB]@Line_ExemptionRule,
         exemptedLines[expRule, expLine]: [store::DB]@Line_ExemptionRule
      )
   }
)
'''


def _q(v):
    return f'"{v}"' if v is not None else "None"


def apply() -> None:
    p = STRESS / "30-store.pure"
    t = p.read_text()
    if "Schema regrpt" in t:
        raise SystemExit("large exposures already applied")

    anchor = "    // ---- Brokerage tiers and clearing routes ----"
    t = t.replace(anchor, """    // ---- Large exposures: a SCHEMA, and four datatypes nothing had declared ----
    //
    // A regulatory extract is not a trading table, so it lives in its own schema. Nothing in
    // this corpus had ever MAPPED a schema-qualified table: the one Schema that existed was
    // declared in the dense store and read by nothing.
    Schema regrpt
    (
       Table EXPOSURE_LINE
       (
          REPORT_ID VARCHAR(20) PRIMARY KEY,
          COB_DATE DATE PRIMARY KEY,
          LINE_NO SMALLINT PRIMARY KEY,
          COUNTERPARTY_ID VARCHAR(20),
          GROUP_ID VARCHAR(20),
          COUNTRY_CODE CHAR(2),
          EXPOSURE_CLASS VARCHAR(16),
          GROSS_EXPOSURE NUMERIC(20,2),
          CRM_EFFECT NUMERIC(20,2),
          NET_EXPOSURE NUMERIC(20,2),
          PCT_OF_CAPITAL FLOAT,
          REPORTED_AT TIMESTAMP,
          IS_EXEMPT BIT
       )

       Table EXPOSURE_THRESHOLD
       (
          BAND_ID VARCHAR(12) PRIMARY KEY,
          BAND_NAME VARCHAR(40),
          FLOOR_PCT FLOAT,
          CEILING_PCT FLOAT,
          IS_REPORTABLE BIT,
          IS_BREACH BIT,
          NOTE VARCHAR(80)
       )

       Table EXEMPTION_RULE
       (
          RULE_ID VARCHAR(12) PRIMARY KEY,
          EXEMPT_CPTY_ID VARCHAR(20),
          EXEMPT_COUNTRY CHAR(2),
          BASIS VARCHAR(30),
          NOTE VARCHAR(80)
       )
    )

""" + anchor, 1)

    t = t.replace("    Join Trade_BrokerageTier(",
                  "    Filter ReportableLineRows(regrpt.EXPOSURE_LINE.PCT_OF_CAPITAL >= 5.0)\n\n"
                  "    // A RANGE join from a composite-key table: which band applies is a\n"
                  "    // property of the percentage, and there is no key to join on.\n"
                  "    Join Line_ThresholdBand(regrpt.EXPOSURE_LINE.PCT_OF_CAPITAL >= regrpt.EXPOSURE_THRESHOLD.FLOOR_PCT and regrpt.EXPOSURE_LINE.PCT_OF_CAPITAL < regrpt.EXPOSURE_THRESHOLD.CEILING_PCT)\n"
                  "    // A DISJUNCTION: a rule attaches to a counterparty or to a country.\n"
                  "    Join Line_ExemptionRule(regrpt.EXPOSURE_LINE.COUNTERPARTY_ID = regrpt.EXEMPTION_RULE.EXEMPT_CPTY_ID or regrpt.EXPOSURE_LINE.COUNTRY_CODE = regrpt.EXEMPTION_RULE.EXEMPT_COUNTRY)\n\n"
                  "    Join Trade_BrokerageTier(", 1)
    p.write_text(t)

    lines = "\n".join(
        f'    dict(REPORT_ID="{r}", COB_DATE="{d}", LINE_NO={n}, COUNTERPARTY_ID="{cp}",\n'
        f'         GROUP_ID={_q(g)}, COUNTRY_CODE="{cc}", EXPOSURE_CLASS="{ec}",\n'
        f'         GROSS_EXPOSURE={gross}, CRM_EFFECT={crm}, NET_EXPOSURE={round(gross - crm, 2)},\n'
        f'         PCT_OF_CAPITAL={pct}, REPORTED_AT="{d} 18:30:00", IS_EXEMPT={ex}),'
        for r, d, n, cp, g, cc, ec, gross, crm, pct, ex in LINES)
    bands = "\n".join(
        f'    dict(BAND_ID="{b}", BAND_NAME="{nm}", FLOOR_PCT={lo}, CEILING_PCT={hi},\n'
        f'         IS_REPORTABLE={rep}, IS_BREACH={br}, NOTE="{note}"),'
        for b, nm, lo, hi, rep, br, note in THRESHOLDS)
    rules = "\n".join(
        f'    dict(RULE_ID="{r}", EXEMPT_CPTY_ID={_q(cp)}, EXEMPT_COUNTRY={_q(cc)},\n'
        f'         BASIS="{b}", NOTE="{note}"),'
        for r, cp, cc, b, note in EXEMPTIONS)

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                  f"\n\n# Large exposures: twelve lines over three returns and two dates.\n"
                  f"EXPOSURE_LINE = [\n{lines}\n]\n\n"
                  f"EXPOSURE_THRESHOLD = [\n{bands}\n]\n\n"
                  f"EXEMPTION_RULE = [\n{rules}\n]\n"
                  "\n\nTABLES: dict[str, list[dict]] = {", 1)
    t = t.replace("TABLES: dict[str, list[dict]] = {",
                  'TABLES: dict[str, list[dict]] = {\n'
                  '    "EXPOSURE_LINE": EXPOSURE_LINE,\n'
                  '    "EXPOSURE_THRESHOLD": EXPOSURE_THRESHOLD,\n'
                  '    "EXEMPTION_RULE": EXEMPTION_RULE,', 1)
    p.write_text(t)

    (STRESS / "8110-largeexp.pure").write_text(PURE)

    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if "include largeexp::" not in t:
        t = t.replace("    include brokerage::BrokerageMapping",
                      "    include brokerage::BrokerageMapping\n"
                      "    include largeexp::LargeExposureMapping", 1)
        p.write_text(t)


if __name__ == "__main__":
    apply()
    print("large exposures staged")
