"""
Seed data for the 18 tables the stress services reach.

Two rules govern everything here.

**Realistic.** Real tickers, real ISINs, real MICs, LEI-shaped identifiers, desk and book
names that would pass review at a bank. Cardinality is skewed the way real books are
skewed — one instrument carries nearly half the trades, several carry none. Uniform
fan-out is the single most common way a fixture stops resembling data.

**Adversarial on purpose.** Every awkward shape below is placed deliberately, is named,
and is asserted at build time by `check()`. If a future edit removes one, the build fails
rather than quietly weakening the corpus:

  A1  ORPHAN_FK        TRADE.COUNTERPARTY_ID points at a counterparty that does not exist.
                       Legend compiles to-one navigation as LEFT OUTER JOIN, so the row
                       must survive with NULL counterparty columns. An INNER JOIN
                       regression drops the row and the row-count assertion catches it.
  A2  NULL_FK          TRADE.TRADER_ID is NULL. Distinct from A1: the join key is absent
                       rather than unmatched, and `NULL = x` is UNKNOWN, not false.
  A3  ZERO_CHILD       Instruments, counterparties, sectors, countries and desks with no
                       children at all. This is where COUNT over an outer join returns 1
                       instead of 0 — the shape of the B-4 defect.
  A4  NULL_MEASURE     NULL COMMISSION / FEES / greeks, so three-valued logic shows up in
                       filters and aggregates rather than only in strings.
  A5  TIES             Several trades share a NOTIONAL, so any assertion that depends on
                       sort order is ambiguous by construction and must not be written.
  A6  SKEW             INST-AAPL carries far more trades than any other instrument.
  A7  DUP_NATURAL_KEY  Two counterparties share LEGAL_NAME with different ids — the
                       natural-vs-surrogate key split that breaks naive dedup.
  A8  BOUNDARY         Statuses one letter away from the filtered value ('EXECUTE' vs
                       'EXECUTED'), and measures sitting exactly on zero.
  A9  (WITHDRAWN)      Was EMPTY_VS_NULL: one DESCRIPTION empty string, another NULL.
                       It cannot be expressed. `###Data` Relational CSV maps BOTH a bare
                       empty field and a quoted "" to NULL, so the empty string does not
                       survive the round trip and an expectation of "" is unsatisfiable.
                       Minimized and recorded in docs/UPSTREAM_FINDINGS.md (F2); do not
                       re-add empty-string values to this seed until it is fixed
                       upstream, because they silently become NULL.
  A10 APOSTROPHE       A legal name and a surname containing an apostrophe, which must
                       survive Pure string quoting, CSV, and SQL literal quoting.
  A11 CHAIN_NULL       INSTRUMENT.SECTOR_ID is orphaned for one instrument, so a two-hop
                       navigation breaks at the second hop rather than the first.
  A12 CASE_SENSITIVE   Two venue codes differing only in case.
  A16 SELF_JOIN_CHAIN  TRADER.MANAGER_ID is a recursive link: two traders report to
                       TRD-001 (one of them cross-desk), TRD-001 and TRD-003 report to
                       nobody, and TRD-004 reports to TRD-999 who does not exist. So the
                       {target} self-join has to produce a real row, a NULL from an absent
                       key, and a NULL from a dangling one -- on the same table.
  A18 XSTORE_LINK      The external party master is NOT a copy of COUNTERPARTY: one
                       counterparty is missing from it, one entity matches no trade, and
                       every registered name differs from the local legal name -- so a
                       query that accidentally read the local side would return a
                       different string rather than the same one.
  A17 BITEMPORAL_FIX   INSTR_RATING_BI holds a RETROACTIVE CORRECTION: the same business
                       period recorded twice, the second superseding the first in
                       PROCESSING time only. Asking about the same business date at two
                       processing dates must give two different answers, and both are
                       correct. This is the one shape no single-temporal store can hold.
  A15 TEMPORAL_EDGES   The SCD2 history in CPTY_RATING_MS is built around the awkward
                       dates, not around convenience: a version boundary landing exactly
                       on the queried date (CP-0003 on 2024-06-07), a rating WITHDRAWN so
                       the entity has no current version at all (CP-0004), an entity with
                       no history whatsoever (CP-0005), one that never changed (CP-0002),
                       and one that changed twice and came back (CP-0001).
  A14 UNMAPPED_ENUM    One trade carries a source code with NO EnumerationMapping entry.
                       It must come back NULL — the same as a NULL source — which is a
                       silent data-quality hole worth pinning: a bad feed code does not
                       raise, it just disappears. The property is declared [1] and the
                       multiplicity is not enforced.
  A13 MANY_TO_ONE_ENUM TRADE.SIDE holds source CODES, not labels, and two of them ('B'
                       and the legacy 'BOT') collapse onto BUY. The projected value is
                       unchanged — still "BUY" — so the expectation is identical while the
                       mechanism is not: a broken EnumerationMapping shows up immediately
                       and cannot be mistaken for a data change.

Values are literal, not random. A seeded PRNG would still make the data unreadable in a
diff, and the whole point of a fixture is that a human can see why a case failed.

Column names are NOT guessed — `check()` validates every one against the parsed schema in
model.py, and the first draft of this file failed that check 103 times.
"""
from __future__ import annotations

from model import Corpus

_VENUES = ["XNYS", "XNAS", "BATS", "IEXG", "XLON"]


def _iso(y: int, m: int, d: int) -> str:
    return f"{y:04d}-{m:02d}-{d:02d}"


# ------------------------------------------------------------------ reference

COUNTRY = [
    dict(COUNTRY_CODE="US", NAME="United States", ISO3_CODE="USA", REGION="Americas",
         SUB_REGION="Northern America", CONTINENT="North America",
         CAPITAL_CITY="Washington", TIME_ZONE="America/New_York", GDP=25462700.0,
         POPULATION=331000000, IS_EU=False, IS_OECD=True),
    dict(COUNTRY_CODE="GB", NAME="United Kingdom", ISO3_CODE="GBR", REGION="EMEA",
         SUB_REGION="Northern Europe", CONTINENT="Europe", CAPITAL_CITY="London",
         TIME_ZONE="Europe/London", GDP=3070600.0, POPULATION=67000000,
         IS_EU=False, IS_OECD=True),
    dict(COUNTRY_CODE="DE", NAME="Germany", ISO3_CODE="DEU", REGION="EMEA",
         SUB_REGION="Western Europe", CONTINENT="Europe", CAPITAL_CITY="Berlin",
         TIME_ZONE="Europe/Berlin", GDP=4072200.0, POPULATION=83000000,
         IS_EU=True, IS_OECD=True),
    dict(COUNTRY_CODE="JP", NAME="Japan", ISO3_CODE="JPN", REGION="APAC",
         SUB_REGION="Eastern Asia", CONTINENT="Asia", CAPITAL_CITY="Tokyo",
         TIME_ZONE="Asia/Tokyo", GDP=4231100.0, POPULATION=125000000,
         IS_EU=False, IS_OECD=True),
    # A3 ZERO_CHILD: no counterparty is domiciled here. A4: GDP not loaded.
    dict(COUNTRY_CODE="SG", NAME="Singapore", ISO3_CODE="SGP", REGION="APAC",
         SUB_REGION="South-eastern Asia", CONTINENT="Asia", CAPITAL_CITY="Singapore",
         TIME_ZONE="Asia/Singapore", GDP=None, POPULATION=5900000,
         IS_EU=False, IS_OECD=False),
]

CURRENCY = [
    dict(CODE="USD", NAME="US Dollar", NUMERIC_CODE=840, MINOR_UNIT=2, SYMBOL="$",
         IS_ACTIVE=True, REGION="Americas", IS_MAJOR=True,
         CENTRAL_BANK="Federal Reserve", FIXING_SOURCE="WMR", COUNTRY_ID="US"),
    dict(CODE="GBP", NAME="Pound Sterling", NUMERIC_CODE=826, MINOR_UNIT=2, SYMBOL="GBP",
         IS_ACTIVE=True, REGION="EMEA", IS_MAJOR=True,
         CENTRAL_BANK="Bank of England", FIXING_SOURCE="WMR", COUNTRY_ID="GB"),
    dict(CODE="EUR", NAME="Euro", NUMERIC_CODE=978, MINOR_UNIT=2, SYMBOL="EUR",
         IS_ACTIVE=True, REGION="EMEA", IS_MAJOR=True,
         CENTRAL_BANK="European Central Bank", FIXING_SOURCE="ECB", COUNTRY_ID="DE"),
    # A4/A9: zero minor units is real for JPY, and no fixing source is loaded.
    dict(CODE="JPY", NAME="Japanese Yen", NUMERIC_CODE=392, MINOR_UNIT=0, SYMBOL="JPY",
         IS_ACTIVE=True, REGION="APAC", IS_MAJOR=True,
         CENTRAL_BANK="Bank of Japan", FIXING_SOURCE=None, COUNTRY_ID="JP"),
]

EXCHANGE = [
    dict(MIC="XNYS", NAME="New York Stock Exchange", CITY="New York",
         TIMEZONE="America/New_York", OPEN_TIME="09:30", CLOSE_TIME="16:00",
         IS_ELECTRONIC=True, OPERATING_MIC="XNYS", WEBSITE="https://www.nyse.com",
         REGULATORY_BODY="SEC", COUNTRY_ID="US"),
    dict(MIC="XNAS", NAME="Nasdaq Stock Market", CITY="New York",
         TIMEZONE="America/New_York", OPEN_TIME="09:30", CLOSE_TIME="16:00",
         IS_ELECTRONIC=True, OPERATING_MIC="XNAS", WEBSITE="https://www.nasdaq.com",
         REGULATORY_BODY="SEC", COUNTRY_ID="US"),
    dict(MIC="XLON", NAME="London Stock Exchange", CITY="London",
         TIMEZONE="Europe/London", OPEN_TIME="08:00", CLOSE_TIME="16:30",
         IS_ELECTRONIC=True, OPERATING_MIC="XLON", WEBSITE="https://www.londonstockexchange.com",
         REGULATORY_BODY="FCA", COUNTRY_ID="GB"),
    dict(MIC="XETR", NAME="Deutsche Boerse Xetra", CITY="Frankfurt",
         TIMEZONE="Europe/Berlin", OPEN_TIME="09:00", CLOSE_TIME="17:30",
         IS_ELECTRONIC=True, OPERATING_MIC="XFRA", WEBSITE=None,
         REGULATORY_BODY="BaFin", COUNTRY_ID="DE"),
]

SECTOR = [
    dict(SECTOR_ID="SEC-45", NAME="Information Technology", GICS_CODE="45", LEVEL=1,
         DESCRIPTION="Technology hardware and software", IS_ACTIVE=True,
         PARENT_SECTOR_ID=None, MARKET_CAP_WEIGHT=0.2840, NUMBER_OF_COMPANIES=68,
         LAST_UPDATED=_iso(2024, 3, 1)),
    dict(SECTOR_ID="SEC-40", NAME="Financials", GICS_CODE="40", LEVEL=1,
         DESCRIPTION="Banks and capital markets", IS_ACTIVE=True,
         PARENT_SECTOR_ID=None, MARKET_CAP_WEIGHT=0.1310, NUMBER_OF_COMPANIES=72,
         LAST_UPDATED=_iso(2024, 3, 1)),
    dict(SECTOR_ID="SEC-35", NAME="Health Care", GICS_CODE="35", LEVEL=1,
         DESCRIPTION="Pharmaceuticals and providers", IS_ACTIVE=True,
         PARENT_SECTOR_ID=None, MARKET_CAP_WEIGHT=0.1225, NUMBER_OF_COMPANIES=63,
         LAST_UPDATED=_iso(2024, 3, 1)),
    # A3 ZERO_CHILD: no instrument is classified here. A9 NULL description.
    dict(SECTOR_ID="SEC-55", NAME="Utilities", GICS_CODE="55", LEVEL=1,
         DESCRIPTION=None, IS_ACTIVE=False, PARENT_SECTOR_ID=None,
         MARKET_CAP_WEIGHT=0.0230, NUMBER_OF_COMPANIES=31,
         LAST_UPDATED=_iso(2023, 9, 15)),
]

DESK = [
    dict(DESK_ID="DSK-CASH", NAME="Cash Equities", DESK_TYPE="FLOW",
         ASSET_CLASS="EQUITY", REGION="Americas", IS_ACTIVE=True,
         HEAD_TRADER="TRD-001", COST_CENTER="CC-4410", FLOOR="3", BUILDING="200 West",
         PHONE="+1-212-555-0110", RISK_LIMIT=25000000.0, PNL_TARGET=4000000.0,
         BUSINESS_UNIT_ID="BU-EQ"),
    dict(DESK_ID="DSK-DERIV", NAME="Equity Derivatives", DESK_TYPE="STRUCTURED",
         ASSET_CLASS="EQUITY", REGION="EMEA", IS_ACTIVE=True, HEAD_TRADER="TRD-003",
         COST_CENTER="CC-4415", FLOOR="4", BUILDING="200 West",
         PHONE="+44-20-7555-0111", RISK_LIMIT=40000000.0, PNL_TARGET=7500000.0,
         BUSINESS_UNIT_ID="BU-EQ"),
    # A3 ZERO_CHILD: a desk with no books, therefore no trades and no PnL.
    dict(DESK_ID="DSK-CREDIT", NAME="Credit Trading", DESK_TYPE="FLOW",
         ASSET_CLASS="CREDIT", REGION="Americas", IS_ACTIVE=False, HEAD_TRADER=None,
         COST_CENTER="CC-4420", FLOOR="5", BUILDING="200 West", PHONE=None,
         RISK_LIMIT=15000000.0, PNL_TARGET=None, BUSINESS_UNIT_ID="BU-CR"),
]

TRADER = [
    dict(TRADER_ID="TRD-001", FIRST_NAME="Maria", LAST_NAME="Alvarez", BADGE="B10041",
         EMAIL="maria.alvarez@example.com", HIRE_DATE=_iso(2011, 3, 14), SENIORITY="MD",
         IS_ACTIVE=True, SPECIALIZATION="Cash Equities", LICENSE="Series 57",
         MAX_NOTIONAL=50000000.0, PNL_YTD=2840100.50, PHONE="+1-212-555-0141",
         DESK_ID="DSK-CASH", MANAGER_ID=None),          # chain root: reports to nobody
    dict(TRADER_ID="TRD-002", FIRST_NAME="Kenji", LAST_NAME="Watanabe", BADGE="B10077",
         EMAIL="kenji.watanabe@example.com", HIRE_DATE=_iso(2016, 7, 1), SENIORITY="VP",
         IS_ACTIVE=True, SPECIALIZATION="Program Trading", LICENSE="Series 57",
         MAX_NOTIONAL=20000000.0, PNL_YTD=-412300.75, PHONE="+1-212-555-0177",
         DESK_ID="DSK-CASH", MANAGER_ID="TRD-001"),     # reports to Alvarez
    # A10 APOSTROPHE in a surname that Q0/Q1/Q11 project.
    dict(TRADER_ID="TRD-003", FIRST_NAME="Aoife", LAST_NAME="O'Brien", BADGE="B10102",
         EMAIL="aoife.obrien@example.com", HIRE_DATE=_iso(2013, 11, 4), SENIORITY="ED",
         IS_ACTIVE=True, SPECIALIZATION="Volatility", LICENSE="FCA CF30",
         MAX_NOTIONAL=35000000.0, PNL_YTD=4102900.25, PHONE="+44-20-7555-0102",
         DESK_ID="DSK-DERIV", MANAGER_ID="TRD-001"),    # cross-desk reporting line
    # A4 NULL_MEASURE: a leaver with no licence, no phone and no PnL attributed.
    dict(TRADER_ID="TRD-004", FIRST_NAME="Peter", LAST_NAME="Nowak", BADGE="B10188",
         EMAIL=None, HIRE_DATE=_iso(2021, 1, 18), SENIORITY="Analyst", IS_ACTIVE=False,
         SPECIALIZATION=None, LICENSE=None, MAX_NOTIONAL=None, PNL_YTD=None,
         PHONE=None, DESK_ID="DSK-DERIV",
         # A16: points at a trader who has LEFT. The self-join must produce NULLs, not
         # drop the row and not loop.
         MANAGER_ID="TRD-999"),
]

BOOK = [
    dict(BOOK_ID="BK-CASH-US", NAME="US Cash Equity", BOOK_TYPE="TRADING",
         CURRENCY="USD", IS_ACTIVE=True, OPEN_DATE=_iso(2012, 1, 3), CLOSE_DATE=None,
         LEGAL_ENTITY="LE-US-BD", STRATEGY="Market Making", PNL_YTD=3120450.75,
         POSITION_COUNT=2, RISK_LIMIT=15000000.0,
         DESCRIPTION="Primary US cash equity book", DESK_ID="DSK-CASH"),
    dict(BOOK_ID="BK-CASH-EU", NAME="EMEA Cash Equity", BOOK_TYPE="TRADING",
         CURRENCY="EUR", IS_ACTIVE=True, OPEN_DATE=_iso(2014, 4, 1), CLOSE_DATE=None,
         LEGAL_ENTITY="LE-UK-BD", STRATEGY="Agency", PNL_YTD=-845200.25,
         POSITION_COUNT=2, RISK_LIMIT=9000000.0,
         DESCRIPTION="EMEA agency flow", DESK_ID="DSK-CASH"),
    dict(BOOK_ID="BK-VOL-01", NAME="Volatility Arb", BOOK_TYPE="TRADING",
         CURRENCY="USD", IS_ACTIVE=True, OPEN_DATE=_iso(2018, 9, 17), CLOSE_DATE=None,
         LEGAL_ENTITY="LE-US-BD", STRATEGY="Relative Value",
         PNL_YTD=0.0,                                              # A8 exactly zero
         POSITION_COUNT=1, RISK_LIMIT=20000000.0,
         DESCRIPTION=None, DESK_ID="DSK-DERIV"),                   # A9 NULL
    # A3 ZERO_CHILD: a closed book with no trades and no PnL rows.
    dict(BOOK_ID="BK-LEGACY", NAME="Legacy Wind-down", BOOK_TYPE="BANKING",
         CURRENCY="GBP", IS_ACTIVE=False, OPEN_DATE=_iso(2007, 6, 30),
         CLOSE_DATE=_iso(2023, 12, 29), LEGAL_ENTITY="LE-UK-BD", STRATEGY="Runoff",
         PNL_YTD=None, POSITION_COUNT=1, RISK_LIMIT=None,
         DESCRIPTION="Closed to new business", DESK_ID="DSK-DERIV"),
]

COUNTERPARTY = [
    dict(COUNTERPARTY_ID="CP-0001", LEGAL_NAME="Meridian Asset Management",
         SHORT_NAME="MERIDIAN", LEI="5493001KJTIIGC8Y1R12",
         COUNTERPARTY_TYPE="ASSET_MANAGER", DOMICILE="US", INCORPORATION_COUNTRY="US",
         IS_ACTIVE=True, ONBOARD_DATE=_iso(2015, 2, 9), CREDIT_RATING="AA-",
         RATING_AGENCY="S&P", TIER="1", PARENT_ENTITY_ID=None,
         INDUSTRY="Investment Management", WEBSITE="https://example.com/meridian",
         CLASSIFICATION_ID="CLS-AM", COUNTRY_CODE="US"),
    # A7 DUP_NATURAL_KEY: same LEGAL_NAME as CP-0004, different entity and domicile.
    dict(COUNTERPARTY_ID="CP-0002", LEGAL_NAME="Halberd Securities",
         SHORT_NAME="HALBERD-UK", LEI="213800WAVVOPS85N2205",
         COUNTERPARTY_TYPE="BROKER_DEALER", DOMICILE="GB", INCORPORATION_COUNTRY="GB",
         IS_ACTIVE=True, ONBOARD_DATE=_iso(2013, 8, 22), CREDIT_RATING="A+",
         RATING_AGENCY="Moody's", TIER="1", PARENT_ENTITY_ID=None,
         INDUSTRY="Broker Dealer", WEBSITE=None, CLASSIFICATION_ID="CLS-BD",
         COUNTRY_CODE="GB"),
    # A10 APOSTROPHE in a name projected by Q0/Q3/Q7/Q8/Q10/Q11.
    dict(COUNTERPARTY_ID="CP-0003", LEGAL_NAME="O'Neill Capital Partners",
         SHORT_NAME="ONEILL", LEI="529900T8BM49AURSDO55",
         COUNTERPARTY_TYPE="HEDGE_FUND", DOMICILE="DE", INCORPORATION_COUNTRY="DE",
         IS_ACTIVE=True, ONBOARD_DATE=_iso(2019, 5, 30), CREDIT_RATING="BBB",
         RATING_AGENCY="Fitch", TIER="2", PARENT_ENTITY_ID=None,
         INDUSTRY="Hedge Fund", WEBSITE="https://example.com/oneill",
         CLASSIFICATION_ID="CLS-HF", COUNTRY_CODE="DE"),
    dict(COUNTERPARTY_ID="CP-0004", LEGAL_NAME="Halberd Securities",
         SHORT_NAME="HALBERD-JP", LEI="984500B8DA6F1C9C8E11",
         COUNTERPARTY_TYPE="BROKER_DEALER", DOMICILE="JP", INCORPORATION_COUNTRY="JP",
         IS_ACTIVE=False, ONBOARD_DATE=_iso(2020, 10, 5), CREDIT_RATING=None,
         RATING_AGENCY=None, TIER="3", PARENT_ENTITY_ID="CP-0002",
         INDUSTRY="Broker Dealer", WEBSITE=None, CLASSIFICATION_ID="CLS-BD",
         COUNTRY_CODE="JP"),
    # A3 ZERO_CHILD: onboarded, never traded with, no settlements, no CSA.
    dict(COUNTERPARTY_ID="CP-0005", LEGAL_NAME="Kestrel Pension Trust",
         SHORT_NAME="KESTREL", LEI="254900OPPU84GM83MG36",
         COUNTERPARTY_TYPE="PENSION_FUND", DOMICILE="GB", INCORPORATION_COUNTRY="GB",
         IS_ACTIVE=True, ONBOARD_DATE=_iso(2022, 3, 1), CREDIT_RATING="AAA",
         RATING_AGENCY="S&P", TIER="2", PARENT_ENTITY_ID=None,
         INDUSTRY="Pension", WEBSITE=None, CLASSIFICATION_ID="CLS-PF",
         COUNTRY_CODE="GB"),
]

INSTRUMENT = [
    dict(INSTRUMENT_ID="INST-AAPL", TICKER="AAPL", NAME="Apple Inc", ISIN="US0378331005",
         CUSIP="037833100", SEDOL="2046251", ASSET_CLASS="EQUITY",
         PRODUCT_TYPE="COMMON_STOCK", IS_ACTIVE=True, LISTING_DATE=_iso(1980, 12, 12),
         MATURITY_DATE=None, NOTIONAL_AMOUNT=None, CURRENCY="USD",
         DESCRIPTION="Apple Inc common stock", ISSUE_DATE=_iso(1980, 12, 12),
         CURRENCY_CODE="USD", EXCHANGE_MIC="XNAS", SECTOR_ID="SEC-45"),
    dict(INSTRUMENT_ID="INST-MSFT", TICKER="MSFT", NAME="Microsoft Corp",
         ISIN="US5949181045", CUSIP="594918104", SEDOL="2588173", ASSET_CLASS="EQUITY",
         PRODUCT_TYPE="COMMON_STOCK", IS_ACTIVE=True, LISTING_DATE=_iso(1986, 3, 13),
         MATURITY_DATE=None, NOTIONAL_AMOUNT=None, CURRENCY="USD",
         DESCRIPTION="Microsoft Corp common stock", ISSUE_DATE=_iso(1986, 3, 13),
         CURRENCY_CODE="USD", EXCHANGE_MIC="XNAS", SECTOR_ID="SEC-45"),
    dict(INSTRUMENT_ID="INST-HSBA", TICKER="HSBA", NAME="HSBC Holdings plc",
         ISIN="GB0005405286", CUSIP=None, SEDOL="0540528", ASSET_CLASS="EQUITY",
         PRODUCT_TYPE="COMMON_STOCK", IS_ACTIVE=True, LISTING_DATE=_iso(1991, 7, 1),
         MATURITY_DATE=None, NOTIONAL_AMOUNT=None, CURRENCY="GBP",
         DESCRIPTION=None, ISSUE_DATE=_iso(1991, 7, 1),            # A9 NULL
         CURRENCY_CODE="GBP", EXCHANGE_MIC="XLON", SECTOR_ID="SEC-40"),
    dict(INSTRUMENT_ID="INST-SAP", TICKER="SAP", NAME="SAP SE", ISIN="DE0007164600",
         CUSIP=None, SEDOL="4846288", ASSET_CLASS="EQUITY",
         PRODUCT_TYPE="COMMON_STOCK", IS_ACTIVE=True, LISTING_DATE=_iso(1988, 11, 4),
         MATURITY_DATE=None, NOTIONAL_AMOUNT=None, CURRENCY="EUR",
         DESCRIPTION="SAP SE ordinary shares", ISSUE_DATE=_iso(1988, 11, 4),
         CURRENCY_CODE="EUR", EXCHANGE_MIC="XETR", SECTOR_ID="SEC-35"),
    # A11 CHAIN_NULL: SECTOR_ID points at a sector that does not exist, so
    # instrument.sector.name is NULL while instrument.name is not.
    dict(INSTRUMENT_ID="INST-GILT30", TICKER="UKT30", NAME="UK Gilt 4.25% 2055",
         ISIN="GB00BLBDX619", CUSIP=None, SEDOL="BLBDX61", ASSET_CLASS="RATES",
         PRODUCT_TYPE="GOVERNMENT_BOND", IS_ACTIVE=True, LISTING_DATE=_iso(2021, 1, 26),
         MATURITY_DATE=_iso(2055, 12, 7), NOTIONAL_AMOUNT=1000000000.0, CURRENCY="GBP",
         DESCRIPTION="UK Treasury gilt", ISSUE_DATE=_iso(2021, 1, 26),
         CURRENCY_CODE="GBP", EXCHANGE_MIC="XLON", SECTOR_ID="SEC-NONE"),
    # A3 ZERO_CHILD: listed, never traded.
    dict(INSTRUMENT_ID="INST-NESN", TICKER="NESN", NAME="Nestle SA", ISIN="CH0038863350",
         CUSIP=None, SEDOL="7123870", ASSET_CLASS="EQUITY",
         PRODUCT_TYPE="COMMON_STOCK", IS_ACTIVE=False, LISTING_DATE=_iso(1988, 1, 4),
         MATURITY_DATE=None, NOTIONAL_AMOUNT=None, CURRENCY="EUR",
         DESCRIPTION="Nestle SA registered shares", ISSUE_DATE=_iso(1988, 1, 4),
         CURRENCY_CODE="EUR", EXCHANGE_MIC="XETR", SECTOR_ID="SEC-35"),
]


# ---------------------------------------------------------------- transactions

def _trades() -> list[dict]:
    rows, n = [], 0

    def t(instr, book, trader, cpty, qty, px, status, side="B", day=3,
          commission=None, fees=None, venue=None, block=False, settle_day=None):
        nonlocal n
        n += 1
        rows.append(dict(
            TRADE_ID=f"TRD-{n:04d}",
            TRADE_DATE=_iso(2024, 6, day),
            SETTLEMENT_DATE=None if settle_day is None else _iso(2024, 6, settle_day),
            QUANTITY=float(qty), PRICE=px, NOTIONAL=round(qty * px, 2), SIDE=side,
            STATUS=status,
            TRADE_TYPE="PRINCIPAL" if side == "S" else "AGENCY",
            CURRENCY="USD", COMMISSION=commission, FEES=fees,
            EXECUTION_VENUE=venue or _VENUES[n % len(_VENUES)], IS_BLOCK=block,
            CREATED_TIME=f"2024-06-{day:02d} 09:{(n * 7) % 60:02d}:00",
            LAST_MODIFIED_TIME=f"2024-06-{day:02d} 17:{(n * 3) % 60:02d}:00",
            ORDER_ID=None, BLOCK_ID=None, AVG_PRICE_TRADE_ID=None,
            BOOK_ID=book, COUNTERPARTY_ID=cpty, INSTRUMENT_ID=instr, TRADER_ID=trader,
        ))

    # AAPL — the concentrated name (A6 SKEW: 9 of 20).
    t("INST-AAPL", "BK-CASH-US", "TRD-001", "CP-0001", 1000, 190.50, "EXECUTED",
      commission=95.25, fees=12.10, settle_day=5)
    t("INST-AAPL", "BK-CASH-US", "TRD-001", "CP-0001", 2500, 191.00, "EXECUTED",
      commission=238.75, fees=30.25, settle_day=5)
    t("INST-AAPL", "BK-CASH-US", "TRD-002", "CP-0002", 500, 500.00, "EXECUTED",
      commission=None, fees=None, settle_day=5)                     # A4, A5 tie 250000
    t("INST-AAPL", "BK-CASH-US", "TRD-002", "CP-0002", 1250, 200.00, "EXECUTED",
      side="S", commission=125.00, fees=15.00, settle_day=6)     # A5 tie 250000
    t("INST-AAPL", "BK-VOL-01", "TRD-003", "CP-0003", 2000, 125.00, "EXECUTED",
      commission=100.00, fees=11.50, settle_day=6)                  # A5 tie 250000
    t("INST-AAPL", "BK-CASH-US", None, "CP-0001", 800, 312.50, "EXECUTED",
      commission=80.00, fees=9.00, settle_day=6)                    # A2 NULL_FK, A5 tie
    t("INST-AAPL", "BK-CASH-US", "TRD-001", "CP-NONE", 1500, 188.00, "EXECUTED",
      side="BOT",                                                   # A13 legacy code
      commission=142.50, fees=18.00, settle_day=7)                  # A1 ORPHAN_FK
    t("INST-AAPL", "BK-CASH-US", "TRD-002", "CP-0001", 100, 189.75, "CANCELLED",
      day=4, commission=None, fees=1.00)
    t("INST-AAPL", "BK-CASH-US", "TRD-001", "CP-0002", 3000, 192.25, "EXECUTE",
      day=4, commission=285.00, fees=36.00, settle_day=7)           # A8 near-miss status

    # MSFT.
    t("INST-MSFT", "BK-CASH-US", "TRD-002", "CP-0001", 1200, 415.00, "EXECUTED",
      day=4, commission=249.00, fees=31.50, settle_day=7)
    t("INST-MSFT", "BK-CASH-US", None, "CP-0003", 400, 418.25, "EXECUTED",
      day=4, commission=41.80, fees=5.25, settle_day=7)             # A2 NULL_FK
    t("INST-MSFT", "BK-VOL-01", "TRD-003", "CP-0002", 900, 410.00, "PENDING",
      side="ZZ",                                                    # A14 UNMAPPED_ENUM
      day=5, commission=90.00, fees=11.00)

    # HSBA.
    t("INST-HSBA", "BK-CASH-EU", "TRD-003", "CP-0002", 25000, 6.85, "EXECUTED",
      day=5, side="S", commission=171.25, fees=22.00, settle_day=7)
    t("INST-HSBA", "BK-CASH-EU", "TRD-004", "CP-0004", 10000, 6.90, "EXECUTED",
      day=5, commission=69.00, fees=8.75, settle_day=7, venue="xnas")  # A12 case
    t("INST-HSBA", "BK-CASH-EU", "TRD-003", "CP-0003", 5000, 7.00, "SETTLED",
      day=6, commission=35.00, fees=4.50, settle_day=10)

    # SAP.
    t("INST-SAP", "BK-CASH-EU", "TRD-004", "CP-0003", 1500, 176.40, "EXECUTED",
      day=6, commission=132.30, fees=16.80, settle_day=10, block=True)
    t("INST-SAP", "BK-CASH-EU", "TRD-003", "CP-0004", 2200, 175.00, "EXECUTED",
      day=6, side="S", commission=192.50, fees=24.50, settle_day=10)

    # GILT30 — the instrument whose sector link is broken (A11).
    t("INST-GILT30", "BK-VOL-01", "TRD-003", "CP-0002", 5000000, 0.9825, "EXECUTED",
      day=7, commission=None, fees=250.00, settle_day=10)           # A4
    t("INST-GILT30", "BK-VOL-01", "TRD-004", "CP-0001", 2000000, 0.9800, "EXECUTED",
      day=7, commission=1960.00, fees=98.00, settle_day=11)
    t("INST-GILT30", "BK-VOL-01", "TRD-003", "CP-0002", 1000000, 0.9750, "REJECTED",
      day=7, commission=None, fees=None)

    return rows


TRADE = _trades()


def _positions() -> list[dict]:
    specs = [
        ("POS-0001", "INST-AAPL", "BK-CASH-US", 9150.0, 190.42, 1742343.0, 15420.50),
        ("POS-0002", "INST-MSFT", "BK-CASH-US", 1600.0, 415.81, 665296.0, -8210.75),
        ("POS-0003", "INST-HSBA", "BK-CASH-EU", -10000.0, 6.88, -68800.0, 2140.00),
        ("POS-0004", "INST-SAP", "BK-CASH-EU", 3700.0, 175.83, 650571.0, 0.0),  # A8
        ("POS-0005", "INST-GILT30", "BK-VOL-01", 7000000.0, 0.9818, 6872600.0, None),
        # A3: a flat position on the instrument nobody trades.
        ("POS-0006", "INST-NESN", "BK-LEGACY", 0.0, 0.0, 0.0, None),
    ]
    rows = []
    for i, (pid, instr, book, qty, avg, mkt, upnl) in enumerate(specs):
        rows.append(dict(
            POSITION_ID=pid, QUANTITY=qty, AVERAGE_COST=avg, MARKET_VALUE=mkt,
            UNREALIZED_PN_L=upnl,
            REALIZED_PN_L=None if upnl is None else round(upnl / 3, 2),
            CURRENCY="USD" if instr in ("INST-AAPL", "INST-MSFT") else "GBP",
            POSITION_DATE=_iso(2024, 6, 7),
            DIRECTION="LONG" if qty > 0 else ("SHORT" if qty < 0 else "FLAT"),
            COST_BASIS=round(abs(qty) * avg, 2),
            ACCRUED_INTEREST=None if instr != "INST-GILT30" else 18402.75,
            LAST_UPDATED=f"2024-06-07 18:0{i}:00", IS_OPEN=qty != 0,
            SETTLEMENT_DATE=_iso(2024, 6, 11), BOOK_ID=book,
            PORTFOLIO_ID="PF-MAIN", INSTRUMENT_ID=instr,
        ))
    return rows


POSITION = _positions()

GREEKS = [
    dict(GREEKS_ID="GRK-0001", CALC_DATE=_iso(2024, 6, 7), DELTA=0.62, GAMMA=0.014,
         VEGA=112.5, THETA=-8.25, RHO=4.10, CHARM=-0.002, VANNA=0.008, VOLGA=0.11,
         CURRENCY="USD", MODEL="BLACK_SCHOLES", IS_EOD=True, UNDERLYING_PRICE=190.42,
         INSTRUMENT_ID="INST-AAPL", POSITION_ID="POS-0001"),
    dict(GREEKS_ID="GRK-0002", CALC_DATE=_iso(2024, 6, 7), DELTA=0.48, GAMMA=0.009,
         VEGA=88.0, THETA=-5.40, RHO=2.75, CHARM=-0.001, VANNA=0.005, VOLGA=0.07,
         CURRENCY="USD", MODEL="BLACK_SCHOLES", IS_EOD=True, UNDERLYING_PRICE=415.81,
         INSTRUMENT_ID="INST-MSFT", POSITION_ID="POS-0002"),
    # A4 NULL_MEASURE: a position whose risk was not calculated.
    dict(GREEKS_ID="GRK-0003", CALC_DATE=_iso(2024, 6, 7), DELTA=None, GAMMA=None,
         VEGA=None, THETA=None, RHO=None, CHARM=None, VANNA=None, VOLGA=None,
         CURRENCY="GBP", MODEL="NOT_RUN", IS_EOD=False, UNDERLYING_PRICE=None,
         INSTRUMENT_ID="INST-GILT30", POSITION_ID="POS-0005"),
    # A1 ORPHAN_FK on both ends at once.
    dict(GREEKS_ID="GRK-0004", CALC_DATE=_iso(2024, 6, 7), DELTA=1.0, GAMMA=0.0,
         VEGA=0.0, THETA=0.0, RHO=0.0, CHARM=0.0, VANNA=0.0, VOLGA=0.0,
         CURRENCY="USD", MODEL="DELTA_ONE", IS_EOD=True, UNDERLYING_PRICE=100.0,
         INSTRUMENT_ID="INST-NONE", POSITION_ID="POS-NONE"),
]


def _settlements() -> list[dict]:
    rows = []
    for i, t in enumerate([r for r in TRADE if r["SETTLEMENT_DATE"]], start=1):
        failed = i % 4 == 0
        rows.append(dict(
            SETTLEMENT_ID=f"STL-{i:04d}", SETTLEMENT_DATE=t["SETTLEMENT_DATE"],
            AMOUNT=t["NOTIONAL"], CURRENCY=t["CURRENCY"],
            STATUS="FAILED" if failed else "SETTLED",
            SETTLED_DATE=None if failed else t["SETTLEMENT_DATE"],
            SETTLEMENT_METHOD="DVP", CUSTODIAN="Northern Trust", DEPOSITORY="DTC",
            SETTLED_AMOUNT=None if failed else t["NOTIONAL"],
            FAIL_REASON="INSUFFICIENT_SECURITIES" if failed else None,
            IS_PARTIAL=False, PRIORITY=1 if i % 5 == 0 else 5,
            LAST_UPDATED=f"{t['SETTLEMENT_DATE']} 14:30:00",
            NETTING_ID=None if i % 3 else f"NET-{i:03d}",
            COUNTERPARTY_ID=t["COUNTERPARTY_ID"], TRADE_ID=t["TRADE_ID"],
        ))
    # A1 ORPHAN_FK on both ends: a settlement for a trade that was purged.
    rows.append(dict(
        SETTLEMENT_ID="STL-9999", SETTLEMENT_DATE=_iso(2024, 6, 12), AMOUNT=1.0,
        CURRENCY="USD", STATUS="SETTLED", SETTLED_DATE=_iso(2024, 6, 12),
        SETTLEMENT_METHOD="FOP", CUSTODIAN="Northern Trust", DEPOSITORY="DTC",
        SETTLED_AMOUNT=1.0, FAIL_REASON=None, IS_PARTIAL=False, PRIORITY=9,
        LAST_UPDATED="2024-06-12 08:00:00", NETTING_ID=None,
        COUNTERPARTY_ID="CP-NONE", TRADE_ID="TRD-9999"))
    return rows


SETTLEMENT = _settlements()

CONFIRMATION = [
    dict(CONFIRM_ID=f"CNF-{i:04d}", STATUS="CONFIRMED" if i % 3 else "DISPUTED",
         SENT_DATE=t["TRADE_DATE"],
         AFFIRMED_DATE=None if i % 3 == 0 else t["TRADE_DATE"],
         CONFIRM_METHOD="ELECTRONIC", AFFIRMED_BY=None if i % 3 == 0 else "ops.batch",
         DAYS_OUTSTANDING=0 if i % 3 else 4, IS_OVERDUE=i % 3 == 0,
         TEMPLATE_TYPE="EQ_CASH", VERSION=1,
         LAST_SENT_TIME=f"{t['TRADE_DATE']} 10:00:00",
         COUNTERPARTY_REF=f"REF{i:06d}", IS_ELECTRONIC=True, TRADE_ID=t["TRADE_ID"])
    for i, t in enumerate([r for r in TRADE if r["STATUS"] == "EXECUTED"], start=1)
]

SALES_CREDIT = [
    dict(CREDIT_ID=f"SC-{i:04d}", CREDIT_AMOUNT=round(t["NOTIONAL"] * 0.0004, 2),
         CREDIT_DATE=t["TRADE_DATE"], CREDIT_TYPE="PRIMARY" if i % 2 else "SPLIT",
         CURRENCY=t["CURRENCY"], SPLIT_PCT=100.0 if i % 2 else 50.0,
         STATUS="BOOKED", IS_OVERRIDE=i % 5 == 0,
         OVERRIDE_BY=None if i % 5 else "sales.head",
         ORIGINAL_AMOUNT=None if i % 5 else round(t["NOTIONAL"] * 0.0005, 2),
         REGION="Americas" if t["CURRENCY"] == "USD" else "EMEA",
         PRODUCT_AREA="Cash Equities", SALES_PERSON_ID=f"SLS-{(i % 3) + 1:03d}",
         TRADE_ID=t["TRADE_ID"])
    for i, t in enumerate(TRADE[:12], start=1)
]

TRADE_REPORT = [
    dict(REPORT_ID=f"RPT-{i:04d}", REGIME="MIFID_II",
         STATUS="ACCEPTED" if i % 4 else "REJECTED",
         SUBMISSION_DATE=f"{t['TRADE_DATE']} 19:00:00", REPORT_TYPE="NEW",
         UTI=f"UTI{i:012d}", USI=None if i % 2 else f"USI{i:012d}",
         REPORTING_ENTITY="LE-US-BD", REPORTING_COUNTERPARTY=t["COUNTERPARTY_ID"],
         REJECT_REASON=None if i % 4 else "MISSING_LEI", RETRY_COUNT=0 if i % 4 else 2,
         IS_LATE_REPORT=i % 6 == 0, DUE_DATE=t["TRADE_DATE"], TRADE_ID=t["TRADE_ID"])
    for i, t in enumerate(TRADE[:14], start=1)
]


def _pnl() -> list[dict]:
    specs = [
        ("BK-CASH-US", "DSK-CASH", "TRD-001", 128400.25, 96200.00, 32200.25),
        ("BK-CASH-US", "DSK-CASH", "TRD-002", -41200.50, -30100.00, -11100.50),
        ("BK-CASH-EU", "DSK-CASH", "TRD-003", 18750.00, 12000.00, 6750.00),
        ("BK-CASH-EU", "DSK-CASH", "TRD-004", 0.0, 0.0, 0.0),          # A8 zero
        ("BK-VOL-01", "DSK-DERIV", "TRD-003", 402100.75, 350000.00, 52100.75),
        # A2 NULL_FK: desk-level residual with no trader attribution.
        ("BK-VOL-01", "DSK-DERIV", None, 15900.00, 15900.00, None),
        # A1 ORPHAN_FK: PnL carried against a book that no longer exists.
        ("BK-GONE", "DSK-DERIV", "TRD-003", -2500.00, -2500.00, 0.0),
    ]
    rows = []
    for i, (book, desk, trader, total, realized, unrealized) in enumerate(specs, start=1):
        rows.append(dict(
            PNL_ID=f"PNL-{i:04d}", PNL_DATE=_iso(2024, 6, 7), TOTAL_PN_L=total,
            REALIZED_PN_L=realized, UNREALIZED_PN_L=unrealized,
            NEW_TRADE_PN_L=round(total * 0.30, 2), CARRY_PN_L=round(total * 0.05, 2),
            FX_PN_L=None if trader is None else round(total * 0.02, 2),
            COMMISSIONS=round(abs(total) * 0.01, 2), FEES=round(abs(total) * 0.002, 2),
            CURRENCY="USD", IS_OFFICIAL=i % 2 == 1,
            PUBLISHED_TIME="2024-06-07 20:00:00", VERSION=1,
            MTD_PN_L_ID=f"MTD-{i:04d}", BOOK_ID=book, DESK_ID=desk, TRADER_ID=trader,
        ))
    return rows


DAILY_PN_L = _pnl()

COLLATERAL_AGREEMENT = [
    dict(AGREEMENT_ID="CSA-0001", AGREEMENT_TYPE="CSA", EFFECTIVE_DATE=_iso(2015, 3, 1),
         TERMINATION_DATE=None, MINIMUM_TRANSFER_AMOUNT=250000.0, THRESHOLD=1000000.0,
         INDEPENDENT_AMOUNT=0.0, ROUNDING_AMOUNT=10000.0, CURRENCY="USD",
         ELIGIBLE_COLLATERAL="CASH;UST", VALUATION_FREQUENCY="DAILY",
         DISPUTE_RESOLUTION_DAYS=2, IS_ACTIVE=True, GOVERNING_LAW="NY",
         COUNTERPARTY_ID="CP-0001"),
    dict(AGREEMENT_ID="CSA-0002", AGREEMENT_TYPE="CSA", EFFECTIVE_DATE=_iso(2013, 9, 15),
         TERMINATION_DATE=None, MINIMUM_TRANSFER_AMOUNT=100000.0,
         THRESHOLD=0.0,                                            # A8 exactly zero
         INDEPENDENT_AMOUNT=5000000.0, ROUNDING_AMOUNT=25000.0, CURRENCY="GBP",
         ELIGIBLE_COLLATERAL="CASH;GILT", VALUATION_FREQUENCY="DAILY",
         DISPUTE_RESOLUTION_DAYS=1, IS_ACTIVE=True, GOVERNING_LAW="ENGLISH",
         COUNTERPARTY_ID="CP-0002"),
    dict(AGREEMENT_ID="CSA-0003", AGREEMENT_TYPE="GMRA", EFFECTIVE_DATE=_iso(2019, 6, 3),
         TERMINATION_DATE=_iso(2024, 12, 31), MINIMUM_TRANSFER_AMOUNT=None,
         THRESHOLD=None, INDEPENDENT_AMOUNT=None, ROUNDING_AMOUNT=None,  # A4
         CURRENCY="EUR", ELIGIBLE_COLLATERAL=None, VALUATION_FREQUENCY="WEEKLY",
         DISPUTE_RESOLUTION_DAYS=5, IS_ACTIVE=False, GOVERNING_LAW="GERMAN",
         COUNTERPARTY_ID="CP-0003"),
    # A1 ORPHAN_FK.
    dict(AGREEMENT_ID="CSA-0004", AGREEMENT_TYPE="ISDA", EFFECTIVE_DATE=_iso(2020, 1, 10),
         TERMINATION_DATE=None, MINIMUM_TRANSFER_AMOUNT=50000.0, THRESHOLD=500000.0,
         INDEPENDENT_AMOUNT=0.0, ROUNDING_AMOUNT=1000000.0, CURRENCY="JPY",
         ELIGIBLE_COLLATERAL="CASH;JGB", VALUATION_FREQUENCY="MONTHLY",
         DISPUTE_RESOLUTION_DAYS=10, IS_ACTIVE=True, GOVERNING_LAW="JAPANESE",
         COUNTERPARTY_ID="CP-GONE"),
]


# L3 — the SCD2 history. INFINITY is the open-ended THRU_Z that marks a current version;
# Legend's default infinity date is 9999-12-31 and `%latest` selects rows carrying it.
INFINITY = _iso(9999, 12, 31)

CPTY_RATING_MS = [
    # CP-0001: downgraded once, then upgraded back. Three versions, currently AA-.
    dict(COUNTERPARTY_ID="CP-0001", FROM_Z=_iso(2015, 2, 9), THRU_Z=_iso(2020, 6, 1),
         RATING="AA", AGENCY="S&P", OUTLOOK="STABLE", IS_INVESTMENT_GRADE=True),
    dict(COUNTERPARTY_ID="CP-0001", FROM_Z=_iso(2020, 6, 1), THRU_Z=_iso(2023, 3, 15),
         RATING="A+", AGENCY="S&P", OUTLOOK="NEGATIVE", IS_INVESTMENT_GRADE=True),
    dict(COUNTERPARTY_ID="CP-0001", FROM_Z=_iso(2023, 3, 15), THRU_Z=INFINITY,
         RATING="AA-", AGENCY="S&P", OUTLOOK="STABLE", IS_INVESTMENT_GRADE=True),

    # CP-0002: a single version that has never changed.
    dict(COUNTERPARTY_ID="CP-0002", FROM_Z=_iso(2013, 8, 22), THRU_Z=INFINITY,
         RATING="A+", AGENCY="Moody's", OUTLOOK="STABLE", IS_INVESTMENT_GRADE=True),

    # CP-0003: crossed OUT of investment grade — the version boundary lands exactly on
    # 2024-06-07, the date several services ask about, so an inclusive/exclusive
    # off-by-one in the milestoning predicate changes the answer.
    dict(COUNTERPARTY_ID="CP-0003", FROM_Z=_iso(2019, 5, 30), THRU_Z=_iso(2024, 6, 7),
         RATING="BBB", AGENCY="Fitch", OUTLOOK="NEGATIVE", IS_INVESTMENT_GRADE=True),
    dict(COUNTERPARTY_ID="CP-0003", FROM_Z=_iso(2024, 6, 7), THRU_Z=INFINITY,
         RATING="BB+", AGENCY="Fitch", OUTLOOK="NEGATIVE", IS_INVESTMENT_GRADE=False),

    # CP-0004: rating WITHDRAWN. The last version was closed and no successor opened, so
    # there is no current version at all -- %latest must not return this counterparty,
    # while a query as of 2021 must.
    dict(COUNTERPARTY_ID="CP-0004", FROM_Z=_iso(2020, 10, 5), THRU_Z=_iso(2022, 9, 30),
         RATING="BBB-", AGENCY="Fitch", OUTLOOK="NEGATIVE", IS_INVESTMENT_GRADE=True),

    # CP-0005 has NO rating history at all -- a counterparty that exists in reference data
    # and is absent from the temporal table on every date.
]

# L3b — the bitemporal history, built around ONE retroactive correction.
#
# On 2024-01-10 we recorded that INST-HSBA was A from 2024-01-01. On 2024-03-01 we
# discovered that was wrong and it had been A- all along. Bitemporality keeps BOTH facts:
#
#   business 2024-02-01, processing 2024-02-01  ->  'A'    (what we believed then)
#   business 2024-02-01, processing 2024-04-01  ->  'A-'   (what we believe now)
#
# Same business date, different answers, and neither is a lie. A single-temporal store
# cannot represent this at all: it either loses the correction or loses the fact that we
# once believed otherwise.
INSTR_RATING_BI = [
    # The original belief, later closed in PROCESSING time by the correction.
    dict(INSTRUMENT_ID="INST-HSBA", FROM_Z=_iso(2024, 1, 1), IN_Z=_iso(2024, 1, 10),
         THRU_Z=INFINITY, OUT_Z=_iso(2024, 3, 1),
         CREDIT_RATING="A", SOURCE="FEED-A"),
    # The correction: same business period, opened in processing time on discovery.
    dict(INSTRUMENT_ID="INST-HSBA", FROM_Z=_iso(2024, 1, 1), IN_Z=_iso(2024, 3, 1),
         THRU_Z=INFINITY, OUT_Z=INFINITY,
         CREDIT_RATING="A-", SOURCE="FEED-A-CORRECTED"),
    # A second instrument with no correction: one row, current in both dimensions. The
    # contrast matters -- if the bitemporal predicate were wrong in a way that dropped
    # rows, this would vanish too and the correction case alone would not show it.
    dict(INSTRUMENT_ID="INST-GILT30", FROM_Z=_iso(2021, 1, 26), IN_Z=_iso(2021, 1, 26),
         THRU_Z=INFINITY, OUT_Z=INFINITY,
         CREDIT_RATING="AA", SOURCE="FEED-B"),
]

# L5 — the external party master. Deliberately NOT a copy of COUNTERPARTY:
#   * CP-0004 is absent, so a cross-store navigation finds nothing for it;
#   * the names differ from LEGAL_NAME, so a query that accidentally read the local
#     counterparty instead of the external entity would return the wrong string rather
#     than the same one;
#   * one entity (LE-9000) matches no trade at all.
EXT_LEGAL_ENTITY = [
    dict(ENTITY_ID="CP-0001", REGISTERED_NAME="Meridian Asset Management LLC",
         JURISDICTION="Delaware", IS_SANCTIONED=False),
    dict(ENTITY_ID="CP-0002", REGISTERED_NAME="Halberd Securities (UK) Limited",
         JURISDICTION="England", IS_SANCTIONED=False),
    dict(ENTITY_ID="CP-0003", REGISTERED_NAME="O'Neill Capital Partners GmbH",
         JURISDICTION="Germany", IS_SANCTIONED=True),
    dict(ENTITY_ID="CP-0005", REGISTERED_NAME="Kestrel Pension Trust",
         JURISDICTION="England", IS_SANCTIONED=False),
    dict(ENTITY_ID="LE-9000", REGISTERED_NAME="Unused Entity SA",
         JURISDICTION="Luxembourg", IS_SANCTIONED=False),
]


# ------------------------------------------------------- fixed income: coupons
#
# Six real debt securities, with their real ISINs, coupons and maturities. The corpus had
# `products::Bond` already, but its rows come from the generic expansion -- a 325% coupon
# paid 52 times a year, issued the day it matures -- which is fine for exercising a join and
# useless for asking whether an accrual is right.
#
# The TERMS are written by hand. The SCHEDULE is computed from them, because that is what a
# schedule is: nobody types out forty coupon dates, they fall out of an issue date, a
# frequency and a maturity. Computing them here keeps the oracle honest -- the expectations
# still come from this file rather than from the engine -- while giving the corpus its first
# genuinely deep one-to-many, where a parent has sixteen children rather than three.
DEBT_SECURITY = [
    dict(SECURITY_ID="US912828YY08", DESCRIPTION="US Treasury Note 1.75% 2029",
         ISSUER_NAME="United States Treasury", ISSUER_COUNTRY="US", CURRENCY="USD",
         COUPON_RATE=1.750, COUPON_FREQUENCY=2, DAY_COUNT_BASIS="ACT/ACT",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2019, 11, 30), MATURITY_DATE=_iso(2029, 11, 30),
         SENIORITY="SOVEREIGN", IS_CALLABLE=False, WITHHOLDING_RATE=0.000),
    dict(SECURITY_ID="US912810TM08", DESCRIPTION="US Treasury Bond 2.375% 2049",
         ISSUER_NAME="United States Treasury", ISSUER_COUNTRY="US", CURRENCY="USD",
         COUPON_RATE=2.375, COUPON_FREQUENCY=2, DAY_COUNT_BASIS="ACT/ACT",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2019, 5, 31), MATURITY_DATE=_iso(2049, 5, 31),
         SENIORITY="SOVEREIGN", IS_CALLABLE=False, WITHHOLDING_RATE=0.000),
    dict(SECURITY_ID="US037833DX90", DESCRIPTION="Apple Inc 2.05% 2026",
         ISSUER_NAME="Apple Inc", ISSUER_COUNTRY="US", CURRENCY="USD",
         COUPON_RATE=2.050, COUPON_FREQUENCY=2, DAY_COUNT_BASIS="30/360",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2020, 8, 20), MATURITY_DATE=_iso(2026, 9, 11),
         SENIORITY="SENIOR_UNSECURED", IS_CALLABLE=True, WITHHOLDING_RATE=0.000),
    dict(SECURITY_ID="XS2056430215", DESCRIPTION="HSBC Holdings 3.0% 2030",
         ISSUER_NAME="HSBC Holdings plc", ISSUER_COUNTRY="GB", CURRENCY="GBP",
         COUPON_RATE=3.000, COUPON_FREQUENCY=1, DAY_COUNT_BASIS="ACT/365",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2019, 9, 20), MATURITY_DATE=_iso(2030, 9, 20),
         SENIORITY="SENIOR_UNSECURED", IS_CALLABLE=False, WITHHOLDING_RATE=0.200),
    dict(SECURITY_ID="DE000A289N78", DESCRIPTION="Siemens 0.375% 2027",
         ISSUER_NAME="Siemens AG", ISSUER_COUNTRY="DE", CURRENCY="EUR",
         COUPON_RATE=0.375, COUPON_FREQUENCY=1, DAY_COUNT_BASIS="ACT/ACT",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2020, 6, 5), MATURITY_DATE=_iso(2027, 6, 5),
         SENIORITY="SENIOR_UNSECURED", IS_CALLABLE=False, WITHHOLDING_RATE=0.265),
    # A perpetual: no maturity date at all. Every schedule query has to cope with a security
    # whose MATURITY_DATE is NULL, which is a real shape and not an adversarial invention.
    dict(SECURITY_ID="XS1002801758", DESCRIPTION="Barclays 8.0% Perpetual AT1",
         ISSUER_NAME="Barclays plc", ISSUER_COUNTRY="GB", CURRENCY="USD",
         COUPON_RATE=8.000, COUPON_FREQUENCY=2, DAY_COUNT_BASIS="30/360",
         FACE_VALUE=1000.00, ISSUE_DATE=_iso(2019, 12, 15), MATURITY_DATE=None,
         SENIORITY="SUBORDINATED", IS_CALLABLE=True, WITHHOLDING_RATE=0.000),
]

# The corpus's "today". Coupons on or before it have been paid; after it they have not, which
# is what gives COUPON_PAYMENT its realistic holes rather than a seeded NULL.
_VALUATION_DATE = _iso(2024, 6, 28)


def _add_months(iso: str, n: int) -> str:
    y, m, d = (int(x) for x in iso.split("-"))
    m += n
    y += (m - 1) // 12
    m = (m - 1) % 12 + 1
    # Coupon dates roll to the last day of a shorter month -- a 31 May bond pays 30 November.
    while True:
        try:
            import datetime
            datetime.date(y, m, d)
            return _iso(y, m, d)
        except ValueError:
            d -= 1


def _day_count(basis: str, start: str, end: str) -> int:
    import datetime
    s = datetime.date(*(int(x) for x in start.split("-")))
    e = datetime.date(*(int(x) for x in end.split("-")))
    if basis == "30/360":
        # The bond-basis convention: every month is 30 days, every year 360.
        d1 = min(s.day, 30)
        d2 = min(e.day, 30) if d1 == 30 else e.day
        return (e.year - s.year) * 360 + (e.month - s.month) * 30 + (d2 - d1)
    return (e - s).days


def _coupon_schedule():
    """Accrual periods and the payments actually made against them.

    Periods run from the issue date to maturity, or to ten years out for the perpetual --
    a perpetual has no last coupon, so the schedule has to be bounded by something other
    than the terms.
    """
    periods, payments = [], []
    for sec in DEBT_SECURITY:
        months = 12 // sec["COUPON_FREQUENCY"]
        last = sec["MATURITY_DATE"] or _iso(2029, 12, 15)
        start, n = sec["ISSUE_DATE"], 0
        while True:
            end = _add_months(start, months)
            if end > last:
                break
            n += 1
            days = _day_count(sec["DAY_COUNT_BASIS"], start, end)
            amount = round(sec["FACE_VALUE"] * sec["COUPON_RATE"] / 100
                           / sec["COUPON_FREQUENCY"], 2)
            pid = f"{sec['SECURITY_ID']}-{n:03d}"
            periods.append(dict(
                PERIOD_ID=pid, SECURITY_ID=sec["SECURITY_ID"], PERIOD_NUMBER=n,
                ACCRUAL_START_DATE=start, ACCRUAL_END_DATE=end, PAYMENT_DATE=end,
                ACCRUAL_DAYS=days, NOTIONAL=sec["FACE_VALUE"],
                COUPON_RATE=sec["COUPON_RATE"], COUPON_AMOUNT=amount,
                IS_PAID=end <= _VALUATION_DATE))
            if end <= _VALUATION_DATE:
                tax = round(amount * sec["WITHHOLDING_RATE"], 2)
                payments.append(dict(
                    PAYMENT_ID=f"PAY-{pid}", PERIOD_ID=pid, SECURITY_ID=sec["SECURITY_ID"],
                    PAYMENT_DATE=end, GROSS_AMOUNT=amount, WITHHOLDING_TAX=tax,
                    NET_AMOUNT=round(amount - tax, 2), CURRENCY=sec["CURRENCY"],
                    STATUS="SETTLED"))
            start = end
    return periods, payments


COUPON_PERIOD, COUPON_PAYMENT = _coupon_schedule()



# ------------------------------------------------------------- OTC derivatives
#
# The product taxonomy, modelled the way a firm actually models it: ONE table of shared
# economics with a product-type discriminator, and the parts that differ per family in their
# own tables. A swap has legs; an option has a strike and an expiry; neither has the other's
# columns, and putting them all in one wide table is how you get forty nulls a row.
#
# Real trades, real conventions: SOFR replaced LIBOR for USD, EURIBOR is still 6M in Europe,
# a payer swaption pays fixed, a CDS spread is quoted in basis points, and an NDF settles in
# USD against a non-deliverable currency.
OTC_TRADE = [
    dict(OTC_ID="OTC-000001", PRODUCT_TYPE="IRS", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 3, 15),
         EFFECTIVE_DATE=_iso(2024, 3, 19), TERMINATION_DATE=_iso(2029, 3, 19),
         NOTIONAL=50000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000002", PRODUCT_TYPE="OIS", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 4, 2),
         EFFECTIVE_DATE=_iso(2024, 4, 4), TERMINATION_DATE=_iso(2026, 4, 4),
         NOTIONAL=25000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000003", PRODUCT_TYPE="BASIS_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 2, 8),
         EFFECTIVE_DATE=_iso(2024, 2, 12), TERMINATION_DATE=_iso(2027, 2, 12),
         NOTIONAL=75000000.00, CURRENCY="EUR", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="EUREX", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000004", PRODUCT_TYPE="FRA", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 5, 20),
         EFFECTIVE_DATE=_iso(2024, 8, 20), TERMINATION_DATE=_iso(2024, 11, 20),
         NOTIONAL=100000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000005", PRODUCT_TYPE="SWAPTION", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 1, 30),
         EFFECTIVE_DATE=_iso(2025, 1, 30), TERMINATION_DATE=_iso(2030, 1, 30),
         NOTIONAL=40000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000006", PRODUCT_TYPE="CAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 4, 18),
         EFFECTIVE_DATE=_iso(2024, 4, 22), TERMINATION_DATE=_iso(2027, 4, 22),
         NOTIONAL=30000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000007", PRODUCT_TYPE="CDS", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 3, 20),
         EFFECTIVE_DATE=_iso(2024, 3, 21), TERMINATION_DATE=_iso(2029, 6, 20),
         NOTIONAL=10000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="ICE", MASTER_AGREEMENT="ISDA_2014", COLLATERALISED=True),
    dict(OTC_ID="OTC-000008", PRODUCT_TYPE="CDS_INDEX", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 3, 20),
         EFFECTIVE_DATE=_iso(2024, 3, 21), TERMINATION_DATE=_iso(2029, 6, 20),
         NOTIONAL=250000000.00, CURRENCY="EUR", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="ICE", MASTER_AGREEMENT="ISDA_2014", COLLATERALISED=True),
    dict(OTC_ID="OTC-000009", PRODUCT_TYPE="NDF", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 6, 3),
         EFFECTIVE_DATE=_iso(2024, 6, 5), TERMINATION_DATE=_iso(2024, 12, 5),
         NOTIONAL=20000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000010", PRODUCT_TYPE="FX_OPTION", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 5, 7),
         EFFECTIVE_DATE=_iso(2024, 5, 9), TERMINATION_DATE=_iso(2025, 5, 9),
         NOTIONAL=15000000.00, CURRENCY="EUR", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000011", PRODUCT_TYPE="EQUITY_SWAP", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 2, 26),
         EFFECTIVE_DATE=_iso(2024, 2, 28), TERMINATION_DATE=_iso(2025, 2, 28),
         NOTIONAL=35000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000012", PRODUCT_TYPE="VARIANCE_SWAP", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 4, 11),
         EFFECTIVE_DATE=_iso(2024, 4, 15), TERMINATION_DATE=_iso(2024, 10, 15),
         NOTIONAL=5000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    # An uncollateralised commodity swap with no clearing house: the row where three of the
    # optional columns are genuinely absent rather than absent to be difficult.
    dict(OTC_ID="OTC-000014", PRODUCT_TYPE="CONSTANT_MATURITY_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 1, 1),
         EFFECTIVE_DATE=_iso(2024, 1, 2), TERMINATION_DATE=_iso(2025, 1, 15),
         NOTIONAL=5000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000015", PRODUCT_TYPE="CROSS_CURRENCY_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 3, 3),
         EFFECTIVE_DATE=_iso(2024, 3, 4), TERMINATION_DATE=_iso(2027, 3, 15),
         NOTIONAL=7000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000016", PRODUCT_TYPE="ACCRETING_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 5, 5),
         EFFECTIVE_DATE=_iso(2024, 5, 6), TERMINATION_DATE=_iso(2029, 5, 15),
         NOTIONAL=9000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000017", PRODUCT_TYPE="ZERO_COUPON_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 1, 7),
         EFFECTIVE_DATE=_iso(2024, 1, 8), TERMINATION_DATE=_iso(2026, 7, 15),
         NOTIONAL=11000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000018", PRODUCT_TYPE="CALLABLE_SWAP", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 3, 9),
         EFFECTIVE_DATE=_iso(2024, 3, 10), TERMINATION_DATE=_iso(2028, 9, 15),
         NOTIONAL=13000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000019", PRODUCT_TYPE="CMS_SPREAD_OPTION", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 5, 11),
         EFFECTIVE_DATE=_iso(2024, 5, 12), TERMINATION_DATE=_iso(2025, 11, 15),
         NOTIONAL=15000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000020", PRODUCT_TYPE="MIDCURVE_SWAPTION", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 1, 13),
         EFFECTIVE_DATE=_iso(2024, 1, 14), TERMINATION_DATE=_iso(2027, 1, 15),
         NOTIONAL=17000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000021", PRODUCT_TYPE="COLLAR", ASSET_CLASS="RATES",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 3, 15),
         EFFECTIVE_DATE=_iso(2024, 3, 16), TERMINATION_DATE=_iso(2029, 3, 15),
         NOTIONAL=19000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000022", PRODUCT_TYPE="CDS_TRANCHE", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 5, 17),
         EFFECTIVE_DATE=_iso(2024, 5, 18), TERMINATION_DATE=_iso(2026, 5, 15),
         NOTIONAL=21000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000023", PRODUCT_TYPE="NTH_TO_DEFAULT", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 1, 19),
         EFFECTIVE_DATE=_iso(2024, 1, 20), TERMINATION_DATE=_iso(2028, 7, 15),
         NOTIONAL=23000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000024", PRODUCT_TYPE="CREDIT_LINKED_NOTE", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 3, 21),
         EFFECTIVE_DATE=_iso(2024, 3, 22), TERMINATION_DATE=_iso(2025, 9, 15),
         NOTIONAL=25000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000025", PRODUCT_TYPE="LOAN_CDS", ASSET_CLASS="CREDIT",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 5, 23),
         EFFECTIVE_DATE=_iso(2024, 5, 24), TERMINATION_DATE=_iso(2027, 11, 15),
         NOTIONAL=27000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000026", PRODUCT_TYPE="FX_SWAP", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 1, 25),
         EFFECTIVE_DATE=_iso(2024, 1, 26), TERMINATION_DATE=_iso(2029, 1, 15),
         NOTIONAL=29000000.00, CURRENCY="EUR", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000027", PRODUCT_TYPE="FX_DIGITAL", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 3, 27),
         EFFECTIVE_DATE=_iso(2024, 3, 28), TERMINATION_DATE=_iso(2026, 3, 15),
         NOTIONAL=31000000.00, CURRENCY="EUR", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000028", PRODUCT_TYPE="FX_ACCUMULATOR", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 5, 2),
         EFFECTIVE_DATE=_iso(2024, 5, 3), TERMINATION_DATE=_iso(2028, 5, 15),
         NOTIONAL=33000000.00, CURRENCY="EUR", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000029", PRODUCT_TYPE="QUANTO_FORWARD", ASSET_CLASS="FX",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 1, 4),
         EFFECTIVE_DATE=_iso(2024, 1, 5), TERMINATION_DATE=_iso(2025, 7, 15),
         NOTIONAL=35000000.00, CURRENCY="EUR", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000030", PRODUCT_TYPE="EQUITY_OPTION_OTC", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 3, 6),
         EFFECTIVE_DATE=_iso(2024, 3, 7), TERMINATION_DATE=_iso(2027, 9, 15),
         NOTIONAL=37000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000031", PRODUCT_TYPE="TOTAL_RETURN_SWAP", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 5, 8),
         EFFECTIVE_DATE=_iso(2024, 5, 9), TERMINATION_DATE=_iso(2029, 11, 15),
         NOTIONAL=39000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000032", PRODUCT_TYPE="REVERSE_CONVERTIBLE", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 1, 10),
         EFFECTIVE_DATE=_iso(2024, 1, 11), TERMINATION_DATE=_iso(2026, 1, 15),
         NOTIONAL=41000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000033", PRODUCT_TYPE="LOOKBACK_OPTION", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 3, 12),
         EFFECTIVE_DATE=_iso(2024, 3, 13), TERMINATION_DATE=_iso(2028, 3, 15),
         NOTIONAL=43000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000034", PRODUCT_TYPE="RAINBOW_OPTION", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 5, 14),
         EFFECTIVE_DATE=_iso(2024, 5, 15), TERMINATION_DATE=_iso(2025, 5, 15),
         NOTIONAL=45000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000035", PRODUCT_TYPE="CORRIDOR_VARIANCE_SWAP", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 1, 16),
         EFFECTIVE_DATE=_iso(2024, 1, 17), TERMINATION_DATE=_iso(2027, 7, 15),
         NOTIONAL=47000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000036", PRODUCT_TYPE="EQUITY_REPO", ASSET_CLASS="EQUITY",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 3, 18),
         EFFECTIVE_DATE=_iso(2024, 3, 19), TERMINATION_DATE=_iso(2029, 9, 15),
         NOTIONAL=49000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000037", PRODUCT_TYPE="COMMODITY_SPREAD", ASSET_CLASS="COMMODITY",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 5, 20),
         EFFECTIVE_DATE=_iso(2024, 5, 21), TERMINATION_DATE=_iso(2026, 11, 15),
         NOTIONAL=51000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000038", PRODUCT_TYPE="WEATHER_DERIVATIVE", ASSET_CLASS="COMMODITY",
         COUNTERPARTY_ID="CP-0004", TRADE_DATE=_iso(2024, 1, 22),
         EFFECTIVE_DATE=_iso(2024, 1, 23), TERMINATION_DATE=_iso(2028, 1, 15),
         NOTIONAL=53000000.00, CURRENCY="USD", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000039", PRODUCT_TYPE="EMISSIONS_FORWARD", ASSET_CLASS="COMMODITY",
         COUNTERPARTY_ID="CP-0001", TRADE_DATE=_iso(2024, 3, 24),
         EFFECTIVE_DATE=_iso(2024, 3, 25), TERMINATION_DATE=_iso(2025, 3, 15),
         NOTIONAL=55000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000040", PRODUCT_TYPE="CALLABLE_RANGE_ACCRUAL", ASSET_CLASS="HYBRID",
         COUNTERPARTY_ID="CP-0003", TRADE_DATE=_iso(2024, 5, 26),
         EFFECTIVE_DATE=_iso(2024, 5, 27), TERMINATION_DATE=_iso(2027, 5, 15),
         NOTIONAL=57000000.00, CURRENCY="JPY", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000041", PRODUCT_TYPE="EQUITY_LINKED_NOTE", ASSET_CLASS="HYBRID",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 1, 1),
         EFFECTIVE_DATE=_iso(2024, 1, 2), TERMINATION_DATE=_iso(2029, 7, 15),
         NOTIONAL=59000000.00, CURRENCY="JPY", CLEARING_STATUS="CLEARED",
         CLEARING_HOUSE="LCH", MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=True),
    dict(OTC_ID="OTC-000042", PRODUCT_TYPE="LONGEVITY_SWAP", ASSET_CLASS="HYBRID",
         COUNTERPARTY_ID="CP-0002", TRADE_DATE=_iso(2024, 3, 3),
         EFFECTIVE_DATE=_iso(2024, 3, 4), TERMINATION_DATE=_iso(2026, 9, 15),
         NOTIONAL=61000000.00, CURRENCY="JPY", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT="ISDA_2002", COLLATERALISED=False),
    dict(OTC_ID="OTC-000013", PRODUCT_TYPE="COMMODITY_SWAP", ASSET_CLASS="COMMODITY",
         COUNTERPARTY_ID="CP-0005", TRADE_DATE=_iso(2024, 1, 15),
         EFFECTIVE_DATE=_iso(2024, 2, 1), TERMINATION_DATE=_iso(2025, 1, 31),
         NOTIONAL=8000000.00, CURRENCY="USD", CLEARING_STATUS="BILATERAL",
         CLEARING_HOUSE=None, MASTER_AGREEMENT=None, COLLATERALISED=False),
]

# Legs. A swap has two; an option has none, which is why they are a separate table and not
# columns. Pay/receive is from the firm's side.
OTC_SWAP_LEG = [
    # IRS: pay fixed 3.85%, receive SOFR flat.
    dict(LEG_ID="OTC-000001-1", OTC_ID="OTC-000001", LEG_NUMBER=1, LEG_TYPE="FIXED",
         PAY_RECEIVE="PAY", FIXED_RATE=3.8500, FLOATING_INDEX=None, SPREAD_BP=None,
         RESET_FREQUENCY=None, PAYMENT_FREQUENCY="SEMI_ANNUAL", DAY_COUNT="30/360",
         LEG_NOTIONAL=50000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000001-2", OTC_ID="OTC-000001", LEG_NUMBER=2, LEG_TYPE="FLOATING",
         PAY_RECEIVE="RECEIVE", FIXED_RATE=None, FLOATING_INDEX="USD-SOFR", SPREAD_BP=0.0,
         RESET_FREQUENCY="DAILY", PAYMENT_FREQUENCY="QUARTERLY", DAY_COUNT="ACT/360",
         LEG_NOTIONAL=50000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000002-1", OTC_ID="OTC-000002", LEG_NUMBER=1, LEG_TYPE="FIXED",
         PAY_RECEIVE="RECEIVE", FIXED_RATE=4.1000, FLOATING_INDEX=None, SPREAD_BP=None,
         RESET_FREQUENCY=None, PAYMENT_FREQUENCY="ANNUAL", DAY_COUNT="ACT/360",
         LEG_NOTIONAL=25000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000002-2", OTC_ID="OTC-000002", LEG_NUMBER=2, LEG_TYPE="FLOATING",
         PAY_RECEIVE="PAY", FIXED_RATE=None, FLOATING_INDEX="USD-SOFR-OIS", SPREAD_BP=0.0,
         RESET_FREQUENCY="DAILY", PAYMENT_FREQUENCY="ANNUAL", DAY_COUNT="ACT/360",
         LEG_NOTIONAL=25000000.00, LEG_CURRENCY="USD"),
    # Basis swap: floating against floating, which is the case a "one leg is fixed"
    # assumption gets wrong.
    dict(LEG_ID="OTC-000003-1", OTC_ID="OTC-000003", LEG_NUMBER=1, LEG_TYPE="FLOATING",
         PAY_RECEIVE="PAY", FIXED_RATE=None, FLOATING_INDEX="EUR-EURIBOR-3M",
         SPREAD_BP=0.0, RESET_FREQUENCY="QUARTERLY", PAYMENT_FREQUENCY="QUARTERLY",
         DAY_COUNT="ACT/360", LEG_NOTIONAL=75000000.00, LEG_CURRENCY="EUR"),
    dict(LEG_ID="OTC-000003-2", OTC_ID="OTC-000003", LEG_NUMBER=2, LEG_TYPE="FLOATING",
         PAY_RECEIVE="RECEIVE", FIXED_RATE=None, FLOATING_INDEX="EUR-EURIBOR-6M",
         SPREAD_BP=-4.5, RESET_FREQUENCY="SEMI_ANNUAL", PAYMENT_FREQUENCY="SEMI_ANNUAL",
         DAY_COUNT="ACT/360", LEG_NOTIONAL=75000000.00, LEG_CURRENCY="EUR"),
    dict(LEG_ID="OTC-000011-1", OTC_ID="OTC-000011", LEG_NUMBER=1, LEG_TYPE="EQUITY",
         PAY_RECEIVE="RECEIVE", FIXED_RATE=None, FLOATING_INDEX="SPX-TOTAL-RETURN",
         SPREAD_BP=None, RESET_FREQUENCY="QUARTERLY", PAYMENT_FREQUENCY="QUARTERLY",
         DAY_COUNT="ACT/365", LEG_NOTIONAL=35000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000011-2", OTC_ID="OTC-000011", LEG_NUMBER=2, LEG_TYPE="FLOATING",
         PAY_RECEIVE="PAY", FIXED_RATE=None, FLOATING_INDEX="USD-SOFR", SPREAD_BP=35.0,
         RESET_FREQUENCY="QUARTERLY", PAYMENT_FREQUENCY="QUARTERLY", DAY_COUNT="ACT/360",
         LEG_NOTIONAL=35000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000013-1", OTC_ID="OTC-000013", LEG_NUMBER=1, LEG_TYPE="FIXED",
         PAY_RECEIVE="PAY", FIXED_RATE=78.5000, FLOATING_INDEX=None, SPREAD_BP=None,
         RESET_FREQUENCY=None, PAYMENT_FREQUENCY="MONTHLY", DAY_COUNT="ACT/365",
         LEG_NOTIONAL=8000000.00, LEG_CURRENCY="USD"),
    dict(LEG_ID="OTC-000013-2", OTC_ID="OTC-000013", LEG_NUMBER=2, LEG_TYPE="COMMODITY",
         PAY_RECEIVE="RECEIVE", FIXED_RATE=None, FLOATING_INDEX="WTI-NYMEX-FRONT",
         SPREAD_BP=None, RESET_FREQUENCY="MONTHLY", PAYMENT_FREQUENCY="MONTHLY",
         DAY_COUNT="ACT/365", LEG_NOTIONAL=8000000.00, LEG_CURRENCY="USD"),
]

# Option terms, for the trades that have them. Six of thirteen trades do, so the join to
# this table is empty for the other seven.
OTC_OPTION_TERMS = [
    dict(OTC_ID="OTC-000005", OPTION_STYLE="EUROPEAN", CALL_PUT="PAYER", STRIKE=4.0000,
         PREMIUM=385000.00, PREMIUM_CURRENCY="USD", EXPIRY_DATE=_iso(2025, 1, 28),
         UNDERLYING_TENOR="5Y", BARRIER_LEVEL=None, BARRIER_TYPE=None),
    dict(OTC_ID="OTC-000006", OPTION_STYLE="EUROPEAN", CALL_PUT="CALL", STRIKE=5.2500,
         PREMIUM=142000.00, PREMIUM_CURRENCY="USD", EXPIRY_DATE=_iso(2027, 4, 22),
         UNDERLYING_TENOR="3M", BARRIER_LEVEL=None, BARRIER_TYPE=None),
    dict(OTC_ID="OTC-000010", OPTION_STYLE="EUROPEAN", CALL_PUT="CALL", STRIKE=1.0850,
         PREMIUM=218000.00, PREMIUM_CURRENCY="EUR", EXPIRY_DATE=_iso(2025, 5, 7),
         UNDERLYING_TENOR=None, BARRIER_LEVEL=1.1500, BARRIER_TYPE="UP_AND_OUT"),
]



# ------------------------------------------------------------------ market risk
#
# A risk run, the factors it shocked, and the numbers it produced. This is the shape every
# market-risk stack has: one batch per day per book, a factor hierarchy underneath it, and a
# measure table that fans out to trades x factors x tenor buckets.
#
# The measures are the real vocabulary -- DV01 and CS01 and PV01 are different things, a
# historical VaR and a parametric VaR are different models of the same question, and CVA is
# not a sensitivity at all. Modelling them as one table with a MEASURE_TYPE discriminator is
# what a risk warehouse actually does.
RISK_RUN = [
    dict(RUN_ID="RUN-20240628-EOD", COB_DATE=_iso(2024, 6, 28), RUN_TYPE="EOD",
         SCOPE="FIRM", MODEL_VERSION="MR-2024.2", STATUS="COMPLETE",
         STARTED_AT="2024-06-28 22:15:00", COMPLETED_AT="2024-06-28 23:48:00",
         TRADE_COUNT=13, APPROVED_BY="risk.controller"),
    dict(RUN_ID="RUN-20240628-INTRA", COB_DATE=_iso(2024, 6, 28), RUN_TYPE="INTRADAY",
         SCOPE="RATES_DESK", MODEL_VERSION="MR-2024.2", STATUS="COMPLETE",
         STARTED_AT="2024-06-28 12:00:00", COMPLETED_AT="2024-06-28 12:09:00",
         TRADE_COUNT=6, APPROVED_BY=None),
    dict(RUN_ID="RUN-20240627-EOD", COB_DATE=_iso(2024, 6, 27), RUN_TYPE="EOD",
         SCOPE="FIRM", MODEL_VERSION="MR-2024.1", STATUS="COMPLETE",
         STARTED_AT="2024-06-27 22:12:00", COMPLETED_AT="2024-06-27 23:39:00",
         TRADE_COUNT=13, APPROVED_BY="risk.controller"),
    # A run that failed part way. Its measures are absent, which is the case a report that
    # assumes every run produced numbers gets wrong.
    dict(RUN_ID="RUN-20240626-EOD", COB_DATE=_iso(2024, 6, 26), RUN_TYPE="EOD",
         SCOPE="FIRM", MODEL_VERSION="MR-2024.1", STATUS="FAILED",
         STARTED_AT="2024-06-26 22:14:00", COMPLETED_AT=None,
         TRADE_COUNT=0, APPROVED_BY=None),
]

# The factor hierarchy. A curve has tenor points; an FX spot does not, so TENOR is null for
# it -- absent rather than unknown.
RISK_FACTOR = [
    dict(FACTOR_ID="IR.USD.SOFR.2Y", FACTOR_TYPE="IR_CURVE", CURVE_NAME="USD-SOFR",
         CURRENCY="USD", TENOR="2Y", TENOR_MONTHS=24, ASSET_CLASS="RATES"),
    dict(FACTOR_ID="IR.USD.SOFR.5Y", FACTOR_TYPE="IR_CURVE", CURVE_NAME="USD-SOFR",
         CURRENCY="USD", TENOR="5Y", TENOR_MONTHS=60, ASSET_CLASS="RATES"),
    dict(FACTOR_ID="IR.USD.SOFR.10Y", FACTOR_TYPE="IR_CURVE", CURVE_NAME="USD-SOFR",
         CURRENCY="USD", TENOR="10Y", TENOR_MONTHS=120, ASSET_CLASS="RATES"),
    dict(FACTOR_ID="IR.EUR.ESTR.3Y", FACTOR_TYPE="IR_CURVE", CURVE_NAME="EUR-ESTR",
         CURRENCY="EUR", TENOR="3Y", TENOR_MONTHS=36, ASSET_CLASS="RATES"),
    dict(FACTOR_ID="CR.IG.5Y", FACTOR_TYPE="CREDIT_SPREAD", CURVE_NAME="CDX-IG",
         CURRENCY="USD", TENOR="5Y", TENOR_MONTHS=60, ASSET_CLASS="CREDIT"),
    dict(FACTOR_ID="FX.EURUSD", FACTOR_TYPE="FX_SPOT", CURVE_NAME=None,
         CURRENCY="EUR", TENOR=None, TENOR_MONTHS=None, ASSET_CLASS="FX"),
    dict(FACTOR_ID="EQ.SPX", FACTOR_TYPE="EQUITY_SPOT", CURVE_NAME=None,
         CURRENCY="USD", TENOR=None, TENOR_MONTHS=None, ASSET_CLASS="EQUITY"),
    dict(FACTOR_ID="VOL.SPX.1Y.ATM", FACTOR_TYPE="VOL_SURFACE", CURVE_NAME="SPX-VOL",
         CURRENCY="USD", TENOR="1Y", TENOR_MONTHS=12, ASSET_CLASS="EQUITY"),
    dict(FACTOR_ID="CM.WTI.FRONT", FACTOR_TYPE="COMMODITY_SPOT", CURVE_NAME="WTI",
         CURRENCY="USD", TENOR=None, TENOR_MONTHS=None, ASSET_CLASS="COMMODITY"),
]

# Which factors each trade is sensitive to. A rates swap loads on its curve buckets, a CDS on
# the credit curve, an FX option on spot AND vol. Written out because it is a modelling fact
# about the product, not something to be generated.
_EXPOSURE = {
    "OTC-000001": ["IR.USD.SOFR.2Y", "IR.USD.SOFR.5Y", "IR.USD.SOFR.10Y"],
    "OTC-000002": ["IR.USD.SOFR.2Y"],
    "OTC-000003": ["IR.EUR.ESTR.3Y"],
    "OTC-000004": ["IR.USD.SOFR.2Y"],
    "OTC-000005": ["IR.USD.SOFR.5Y", "VOL.SPX.1Y.ATM"],
    "OTC-000006": ["IR.USD.SOFR.2Y", "IR.USD.SOFR.5Y"],
    "OTC-000007": ["CR.IG.5Y"],
    "OTC-000008": ["CR.IG.5Y"],
    "OTC-000009": ["FX.EURUSD"],
    "OTC-000010": ["FX.EURUSD", "VOL.SPX.1Y.ATM"],
    "OTC-000011": ["EQ.SPX", "IR.USD.SOFR.2Y"],
    "OTC-000012": ["EQ.SPX", "VOL.SPX.1Y.ATM"],
    "OTC-000013": ["CM.WTI.FRONT"],
}

# Which measure a factor type produces. A curve gives DV01, a credit spread CS01, a vol
# surface vega -- the measure follows the factor, which is why this is a lookup and not a
# column someone types.
_MEASURE_FOR = {
    "IR_CURVE": ("DV01", "USD"), "CREDIT_SPREAD": ("CS01", "USD"),
    "FX_SPOT": ("FX_DELTA", "USD"), "EQUITY_SPOT": ("EQUITY_DELTA", "USD"),
    "VOL_SURFACE": ("VEGA", "USD"), "COMMODITY_SPOT": ("COMMODITY_DELTA", "USD"),
}


def _sensitivities():
    """One row per completed run, trade and factor it loads on.

    Values are derived from the trade's notional and the factor's tenor, which is how a
    sensitivity actually behaves -- a ten-year bucket carries more DV01 than a two-year one
    on the same notional -- so the numbers are ordered the way a risk report expects rather
    than random.
    """
    factors = {f["FACTOR_ID"]: f for f in RISK_FACTOR}
    notional = {t["OTC_ID"]: t["NOTIONAL"] for t in OTC_TRADE}
    out, n = [], 0
    for run in RISK_RUN:
        if run["STATUS"] != "COMPLETE":
            continue
        scope_rates = run["SCOPE"] == "RATES_DESK"
        for otc_id in sorted(_EXPOSURE):
            for fid in _EXPOSURE[otc_id]:
                f = factors[fid]
                if scope_rates and f["ASSET_CLASS"] != "RATES":
                    continue
                measure, ccy = _MEASURE_FOR[f["FACTOR_TYPE"]]
                months = f["TENOR_MONTHS"] or 12
                value = round(notional[otc_id] * (months / 120.0) * 0.0001, 2)
                n += 1
                out.append(dict(
                    SENSITIVITY_ID=f"SENS-{n:05d}", RUN_ID=run["RUN_ID"], OTC_ID=otc_id,
                    FACTOR_ID=fid, MEASURE_TYPE=measure, MEASURE_VALUE=value,
                    MEASURE_CURRENCY=ccy, SHOCK_SIZE=1.0, SHOCK_UNIT="BP",
                    COB_DATE=run["COB_DATE"]))
    return out


SENSITIVITY = _sensitivities()

# Firm-level risk numbers: several models of the same question, which is why MEASURE_TYPE is
# a discriminator and not a column name.
RISK_MEASURE = [
    dict(MEASURE_ID="MEAS-00001", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="HISTORICAL_VAR",
         SCOPE="FIRM", CONFIDENCE=0.99, HORIZON_DAYS=1, MEASURE_VALUE=4185000.00,
         CURRENCY="USD", LOOKBACK_DAYS=520, MODEL_NAME="HS-520D"),
    dict(MEASURE_ID="MEAS-00002", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="PARAMETRIC_VAR",
         SCOPE="FIRM", CONFIDENCE=0.99, HORIZON_DAYS=1, MEASURE_VALUE=3920000.00,
         CURRENCY="USD", LOOKBACK_DAYS=260, MODEL_NAME="VCV-260D"),
    dict(MEASURE_ID="MEAS-00003", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="MONTE_CARLO_VAR",
         SCOPE="FIRM", CONFIDENCE=0.99, HORIZON_DAYS=1, MEASURE_VALUE=4310000.00,
         CURRENCY="USD", LOOKBACK_DAYS=None, MODEL_NAME="MC-10K"),
    dict(MEASURE_ID="MEAS-00004", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="EXPECTED_SHORTFALL",
         SCOPE="FIRM", CONFIDENCE=0.975, HORIZON_DAYS=1, MEASURE_VALUE=5240000.00,
         CURRENCY="USD", LOOKBACK_DAYS=520, MODEL_NAME="ES-520D"),
    dict(MEASURE_ID="MEAS-00005", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="STRESSED_VAR",
         SCOPE="FIRM", CONFIDENCE=0.99, HORIZON_DAYS=10, MEASURE_VALUE=11800000.00,
         CURRENCY="USD", LOOKBACK_DAYS=260, MODEL_NAME="SVAR-2008"),
    dict(MEASURE_ID="MEAS-00006", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="CVA",
         SCOPE="FIRM", CONFIDENCE=None, HORIZON_DAYS=None, MEASURE_VALUE=862000.00,
         CURRENCY="USD", LOOKBACK_DAYS=None, MODEL_NAME="XVA-2024.1"),
    dict(MEASURE_ID="MEAS-00007", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="DVA",
         SCOPE="FIRM", CONFIDENCE=None, HORIZON_DAYS=None, MEASURE_VALUE=415000.00,
         CURRENCY="USD", LOOKBACK_DAYS=None, MODEL_NAME="XVA-2024.1"),
    dict(MEASURE_ID="MEAS-00008", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="FVA",
         SCOPE="FIRM", CONFIDENCE=None, HORIZON_DAYS=None, MEASURE_VALUE=228000.00,
         CURRENCY="USD", LOOKBACK_DAYS=None, MODEL_NAME="XVA-2024.1"),
    dict(MEASURE_ID="MEAS-00009", RUN_ID="RUN-20240628-EOD", MEASURE_TYPE="PFE",
         SCOPE="FIRM", CONFIDENCE=0.95, HORIZON_DAYS=1825, MEASURE_VALUE=18400000.00,
         CURRENCY="USD", LOOKBACK_DAYS=None, MODEL_NAME="CCR-2024.1"),
    dict(MEASURE_ID="MEAS-00010", RUN_ID="RUN-20240628-INTRA", MEASURE_TYPE="HISTORICAL_VAR",
         SCOPE="RATES_DESK", CONFIDENCE=0.99, HORIZON_DAYS=1, MEASURE_VALUE=1320000.00,
         CURRENCY="USD", LOOKBACK_DAYS=520, MODEL_NAME="HS-520D"),
    dict(MEASURE_ID="MEAS-00011", RUN_ID="RUN-20240627-EOD", MEASURE_TYPE="HISTORICAL_VAR",
         SCOPE="FIRM", CONFIDENCE=0.99, HORIZON_DAYS=1, MEASURE_VALUE=4092000.00,
         CURRENCY="USD", LOOKBACK_DAYS=520, MODEL_NAME="HS-520D"),
]

# Stress scenarios, and what each did to the book. The historical ones are the standard set
# every regulator asks for.
STRESS_SCENARIO = [
    dict(SCENARIO_ID="SCN-LEHMAN", SCENARIO_NAME="Lehman default Sep 2008",
         SCENARIO_TYPE="HISTORICAL", SEVERITY="SEVERE", HORIZON_DAYS=10,
         AS_OF_EVENT=_iso(2008, 9, 15), IS_REGULATORY=True),
    dict(SCENARIO_ID="SCN-COVID", SCENARIO_NAME="Covid crash Mar 2020",
         SCENARIO_TYPE="HISTORICAL", SEVERITY="SEVERE", HORIZON_DAYS=10,
         AS_OF_EVENT=_iso(2020, 3, 16), IS_REGULATORY=True),
    dict(SCENARIO_ID="SCN-GILT", SCENARIO_NAME="UK gilt crisis Sep 2022",
         SCENARIO_TYPE="HISTORICAL", SEVERITY="MODERATE", HORIZON_DAYS=5,
         AS_OF_EVENT=_iso(2022, 9, 23), IS_REGULATORY=False),
    dict(SCENARIO_ID="SCN-PAR-200BP", SCENARIO_NAME="Parallel +200bp",
         SCENARIO_TYPE="HYPOTHETICAL", SEVERITY="MODERATE", HORIZON_DAYS=1,
         AS_OF_EVENT=None, IS_REGULATORY=True),
    dict(SCENARIO_ID="SCN-STEEP", SCENARIO_NAME="Curve steepener 100bp",
         SCENARIO_TYPE="HYPOTHETICAL", SEVERITY="MILD", HORIZON_DAYS=1,
         AS_OF_EVENT=None, IS_REGULATORY=False),
]


def _scenario_results():
    """Each completed FIRM run against each scenario. The intraday rates-desk run is not
    stressed, so it has no results -- a parent with no children, on purpose."""
    out, n = [], 0
    sev = {"SEVERE": 3.5, "MODERATE": 1.8, "MILD": 0.7}
    for run in RISK_RUN:
        if run["STATUS"] != "COMPLETE" or run["SCOPE"] != "FIRM":
            continue
        for scn in STRESS_SCENARIO:
            n += 1
            pnl = round(-4185000.00 * sev[scn["SEVERITY"]] * (scn["HORIZON_DAYS"] / 10.0), 2)
            out.append(dict(RESULT_ID=f"SCNR-{n:05d}", RUN_ID=run["RUN_ID"],
                            SCENARIO_ID=scn["SCENARIO_ID"], SCOPE=run["SCOPE"],
                            STRESSED_PNL=pnl, BASE_PNL=0.00, CURRENCY="USD",
                            COB_DATE=run["COB_DATE"],
                            BREACHED_LIMIT=abs(pnl) > 10000000.00))
    return out


SCENARIO_RESULT = _scenario_results()



# ---------------------------------------------------------------- middle office
#
# What happens to a trade after it is booked. The lifecycle event table is the spine: every
# amendment, termination, novation, reset and credit event lands here with its own type, and
# the type decides which columns mean anything. A rate reset carries a fixing and no notional
# change; a partial termination carries a notional change and no fixing.
#
# Then the two things a middle office spends its day on: confirmations, which have to be
# matched against the counterparty's version, and breaks, which are what happens when they
# do not match.
LIFECYCLE_EVENT = [
    dict(EVENT_ID="EVT-000001", OTC_ID="OTC-000001", EVENT_TYPE="NEW_TRADE",
         EVENT_DATE=_iso(2024, 3, 15), EFFECTIVE_DATE=_iso(2024, 3, 19), STATUS="COMPLETE",
         INITIATED_BY="FIRM", NOTIONAL_DELTA=None, FIXING_RATE=None,
         CASH_FLOW=None, COUNTERPARTY_REF="HSBC-24-0315-A", NOTES=None),
    dict(EVENT_ID="EVT-000002", OTC_ID="OTC-000001", EVENT_TYPE="RATE_RESET",
         EVENT_DATE=_iso(2024, 6, 19), EFFECTIVE_DATE=_iso(2024, 6, 19), STATUS="COMPLETE",
         INITIATED_BY="SYSTEM", NOTIONAL_DELTA=None, FIXING_RATE=5.3312,
         CASH_FLOW=666400.00, COUNTERPARTY_REF=None, NOTES=None),
    dict(EVENT_ID="EVT-000003", OTC_ID="OTC-000001", EVENT_TYPE="PARTIAL_TERMINATION",
         EVENT_DATE=_iso(2024, 5, 10), EFFECTIVE_DATE=_iso(2024, 5, 14), STATUS="COMPLETE",
         INITIATED_BY="COUNTERPARTY", NOTIONAL_DELTA=-10000000.00, FIXING_RATE=None,
         CASH_FLOW=-84500.00, COUNTERPARTY_REF="HSBC-24-0510-T", NOTES="Client unwind"),
    dict(EVENT_ID="EVT-000004", OTC_ID="OTC-000003", EVENT_TYPE="AMENDMENT",
         EVENT_DATE=_iso(2024, 4, 2), EFFECTIVE_DATE=_iso(2024, 4, 2), STATUS="COMPLETE",
         INITIATED_BY="FIRM", NOTIONAL_DELTA=None, FIXING_RATE=None, CASH_FLOW=None,
         COUNTERPARTY_REF="DB-24-0402-AM", NOTES="Day count corrected to ACT/360"),
    dict(EVENT_ID="EVT-000005", OTC_ID="OTC-000007", EVENT_TYPE="CREDIT_EVENT",
         EVENT_DATE=_iso(2024, 6, 11), EFFECTIVE_DATE=_iso(2024, 6, 11), STATUS="PENDING",
         INITIATED_BY="DETERMINATIONS_COMMITTEE", NOTIONAL_DELTA=None, FIXING_RATE=None,
         CASH_FLOW=None, COUNTERPARTY_REF=None, NOTES="Failure to pay determination"),
    dict(EVENT_ID="EVT-000006", OTC_ID="OTC-000011", EVENT_TYPE="NOVATION",
         EVENT_DATE=_iso(2024, 5, 22), EFFECTIVE_DATE=_iso(2024, 5, 24), STATUS="COMPLETE",
         INITIATED_BY="COUNTERPARTY", NOTIONAL_DELTA=None, FIXING_RATE=None, CASH_FLOW=None,
         COUNTERPARTY_REF="NOV-24-0522", NOTES="Stepped out to CP-0003"),
    dict(EVENT_ID="EVT-000007", OTC_ID="OTC-000002", EVENT_TYPE="COMPRESSION",
         EVENT_DATE=_iso(2024, 6, 14), EFFECTIVE_DATE=_iso(2024, 6, 17), STATUS="COMPLETE",
         INITIATED_BY="TRIOPTIMA", NOTIONAL_DELTA=-5000000.00, FIXING_RATE=None,
         CASH_FLOW=None, COUNTERPARTY_REF="TRIOPT-24-06", NOTES="Cycle 2024-06"),
    dict(EVENT_ID="EVT-000008", OTC_ID="OTC-000005", EVENT_TYPE="EXERCISE",
         EVENT_DATE=_iso(2024, 6, 25), EFFECTIVE_DATE=_iso(2024, 6, 27), STATUS="PENDING",
         INITIATED_BY="FIRM", NOTIONAL_DELTA=None, FIXING_RATE=None, CASH_FLOW=None,
         COUNTERPARTY_REF=None, NOTES="Notice served - awaiting acknowledgement"),
    dict(EVENT_ID="EVT-000009", OTC_ID="OTC-000013", EVENT_TYPE="FULL_TERMINATION",
         EVENT_DATE=_iso(2024, 6, 20), EFFECTIVE_DATE=_iso(2024, 6, 24), STATUS="CANCELLED",
         INITIATED_BY="FIRM", NOTIONAL_DELTA=-8000000.00, FIXING_RATE=None,
         CASH_FLOW=112000.00, COUNTERPARTY_REF=None, NOTES="Cancelled: pricing disputed"),
    dict(EVENT_ID="EVT-000010", OTC_ID="OTC-000008", EVENT_TYPE="INCREASE",
         EVENT_DATE=_iso(2024, 4, 30), EFFECTIVE_DATE=_iso(2024, 5, 2), STATUS="COMPLETE",
         INITIATED_BY="FIRM", NOTIONAL_DELTA=50000000.00, FIXING_RATE=None, CASH_FLOW=None,
         COUNTERPARTY_REF="INC-24-0430", NOTES=None),
    dict(EVENT_ID="EVT-000011", OTC_ID="OTC-000003", EVENT_TYPE="RATE_RESET",
         EVENT_DATE=_iso(2024, 5, 12), EFFECTIVE_DATE=_iso(2024, 5, 12), STATUS="COMPLETE",
         INITIATED_BY="SYSTEM", NOTIONAL_DELTA=None, FIXING_RATE=3.8710,
         CASH_FLOW=725812.50, COUNTERPARTY_REF=None, NOTES=None),
    dict(EVENT_ID="EVT-000012", OTC_ID="OTC-000009", EVENT_TYPE="FIXING",
         EVENT_DATE=_iso(2024, 6, 3), EFFECTIVE_DATE=_iso(2024, 6, 5), STATUS="COMPLETE",
         INITIATED_BY="SYSTEM", NOTIONAL_DELTA=None, FIXING_RATE=1372.4500,
         CASH_FLOW=None, COUNTERPARTY_REF=None, NOTES="KRW fixing WM/R 4pm"),
]

CONFIRMATION_MO = [
    dict(CONFIRMATION_ID="CNF-00001", OTC_ID="OTC-000001", METHOD="ELECTRONIC",
         PLATFORM="MarkitWire", SENT_AT="2024-03-15 16:42:00",
         MATCHED_AT="2024-03-15 16:44:00", STATUS="MATCHED", CHASE_COUNT=0,
         AFFIRMED_BY="mo.ops.london"),
    dict(CONFIRMATION_ID="CNF-00002", OTC_ID="OTC-000003", METHOD="ELECTRONIC",
         PLATFORM="MarkitWire", SENT_AT="2024-02-08 11:20:00",
         MATCHED_AT="2024-02-08 11:21:00", STATUS="MATCHED", CHASE_COUNT=0,
         AFFIRMED_BY="mo.ops.london"),
    dict(CONFIRMATION_ID="CNF-00003", OTC_ID="OTC-000005", METHOD="PAPER",
         PLATFORM=None, SENT_AT="2024-01-30 18:05:00", MATCHED_AT=None,
         STATUS="OUTSTANDING", CHASE_COUNT=3, AFFIRMED_BY=None),
    dict(CONFIRMATION_ID="CNF-00004", OTC_ID="OTC-000010", METHOD="ELECTRONIC",
         PLATFORM="DTCC", SENT_AT="2024-05-07 09:15:00", MATCHED_AT=None,
         STATUS="DISPUTED", CHASE_COUNT=2, AFFIRMED_BY=None),
    dict(CONFIRMATION_ID="CNF-00005", OTC_ID="OTC-000013", METHOD="EMAIL",
         PLATFORM=None, SENT_AT="2024-01-15 14:30:00", MATCHED_AT="2024-01-22 10:02:00",
         STATUS="MATCHED", CHASE_COUNT=5, AFFIRMED_BY="mo.ops.singapore"),
]

TRADE_BREAK = [
    dict(BREAK_ID="BRK-00001", OTC_ID="OTC-000010", BREAK_TYPE="ECONOMIC_BREAK",
         DETECTED_DATE=_iso(2024, 5, 8), SEVERITY="HIGH", STATUS="OPEN",
         ASSIGNED_TO="mo.ops.london", RESOLVED_DATE=None, AGE_DAYS=51,
         FIELD_NAME="STRIKE", OUR_VALUE="1.0850", THEIR_VALUE="1.0855"),
    dict(BREAK_ID="BRK-00002", OTC_ID="OTC-000005", BREAK_TYPE="CONFIRMATION_BREAK",
         DETECTED_DATE=_iso(2024, 2, 5), SEVERITY="MEDIUM", STATUS="OPEN",
         ASSIGNED_TO="mo.ops.london", RESOLVED_DATE=None, AGE_DAYS=144,
         FIELD_NAME=None, OUR_VALUE=None, THEIR_VALUE=None),
    dict(BREAK_ID="BRK-00003", OTC_ID="OTC-000013", BREAK_TYPE="SETTLEMENT_BREAK",
         DETECTED_DATE=_iso(2024, 2, 2), SEVERITY="LOW", STATUS="RESOLVED",
         ASSIGNED_TO="bo.settlements", RESOLVED_DATE=_iso(2024, 2, 6), AGE_DAYS=4,
         FIELD_NAME="SETTLEMENT_DATE", OUR_VALUE="2024-02-01", THEIR_VALUE="2024-02-02"),
    dict(BREAK_ID="BRK-00004", OTC_ID="OTC-000007", BREAK_TYPE="REFERENCE_DATA_BREAK",
         DETECTED_DATE=_iso(2024, 3, 22), SEVERITY="LOW", STATUS="RESOLVED",
         ASSIGNED_TO="refdata.team", RESOLVED_DATE=_iso(2024, 3, 25), AGE_DAYS=3,
         FIELD_NAME="REFERENCE_ENTITY", OUR_VALUE="RED-8G836G", THEIR_VALUE="RED-8G836H"),
    dict(BREAK_ID="BRK-00005", OTC_ID="OTC-000003", BREAK_TYPE="VALUATION_BREAK",
         DETECTED_DATE=_iso(2024, 6, 26), SEVERITY="HIGH", STATUS="ESCALATED",
         ASSIGNED_TO="product.control", RESOLVED_DATE=None, AGE_DAYS=2,
         FIELD_NAME="MTM", OUR_VALUE="-482150.00", THEIR_VALUE="-511900.00"),
    dict(BREAK_ID="BRK-00006", OTC_ID="OTC-000001", BREAK_TYPE="COLLATERAL_BREAK",
         DETECTED_DATE=_iso(2024, 6, 27), SEVERITY="MEDIUM", STATUS="OPEN",
         ASSIGNED_TO="collateral.ops", RESOLVED_DATE=None, AGE_DAYS=1,
         FIELD_NAME="MARGIN_CALL", OUR_VALUE="1250000.00", THEIR_VALUE="1180000.00"),
]



# ----------------------------------------------------------------- back office
#
# Where cash and securities actually move. Standing settlement instructions say where to
# send things; payments are the instructions to move them; nostro movements are what the
# correspondent bank says happened; and reconciliation items are the differences between the
# last two, which is the entire job.
#
# The seed is built so the reconciliation has real outcomes: two matched, one unmatched on
# our side, one unmatched on theirs, and one matched on amount but not on value date.
SETTLEMENT_INSTRUCTION = [
    dict(SSI_ID="SSI-USD-CP0001", COUNTERPARTY_ID="CP-0001", CURRENCY="USD",
         INSTRUMENT_TYPE="CASH", CORRESPONDENT_BIC="CHASUS33", ACCOUNT_NUMBER="8-901-2345",
         PLACE_OF_SETTLEMENT="US", IS_DEFAULT=True, EFFECTIVE_FROM=_iso(2022, 1, 1),
         EFFECTIVE_TO=None, STATUS="ACTIVE"),
    dict(SSI_ID="SSI-EUR-CP0001", COUNTERPARTY_ID="CP-0001", CURRENCY="EUR",
         INSTRUMENT_TYPE="CASH", CORRESPONDENT_BIC="DEUTDEFF", ACCOUNT_NUMBER="400-88231",
         PLACE_OF_SETTLEMENT="DE", IS_DEFAULT=True, EFFECTIVE_FROM=_iso(2022, 1, 1),
         EFFECTIVE_TO=None, STATUS="ACTIVE"),
    dict(SSI_ID="SSI-USD-CP0002", COUNTERPARTY_ID="CP-0002", CURRENCY="USD",
         INSTRUMENT_TYPE="CASH", CORRESPONDENT_BIC="BOFAUS3N", ACCOUNT_NUMBER="7-556-0012",
         PLACE_OF_SETTLEMENT="US", IS_DEFAULT=True, EFFECTIVE_FROM=_iso(2021, 6, 1),
         EFFECTIVE_TO=None, STATUS="ACTIVE"),
    # Superseded: an instruction with an end date, which a query keyed on IS_DEFAULT alone
    # would still pick up.
    dict(SSI_ID="SSI-USD-CP0002-OLD", COUNTERPARTY_ID="CP-0002", CURRENCY="USD",
         INSTRUMENT_TYPE="CASH", CORRESPONDENT_BIC="CITIUS33", ACCOUNT_NUMBER="3-221-9087",
         PLACE_OF_SETTLEMENT="US", IS_DEFAULT=False, EFFECTIVE_FROM=_iso(2019, 3, 1),
         EFFECTIVE_TO=_iso(2021, 5, 31), STATUS="SUPERSEDED"),
    dict(SSI_ID="SSI-SEC-CP0003", COUNTERPARTY_ID="CP-0003", CURRENCY=None,
         INSTRUMENT_TYPE="SECURITIES", CORRESPONDENT_BIC="ECLRLULL",
         ACCOUNT_NUMBER="EOC-11923", PLACE_OF_SETTLEMENT="LU", IS_DEFAULT=True,
         EFFECTIVE_FROM=_iso(2020, 9, 1), EFFECTIVE_TO=None, STATUS="ACTIVE"),
]

PAYMENT = [
    dict(PAYMENT_ID="PMT-000001", OTC_ID="OTC-000001", SSI_ID="SSI-USD-CP0001",
         PAYMENT_TYPE="SWIFT_MT202", DIRECTION="OUT", AMOUNT=666400.00, CURRENCY="USD",
         VALUE_DATE=_iso(2024, 6, 19), STATUS="SETTLED", SETTLED_AT="2024-06-19 09:12:00",
         REFERENCE="PMT24061900123", FAIL_REASON=None),
    dict(PAYMENT_ID="PMT-000002", OTC_ID="OTC-000003", SSI_ID="SSI-EUR-CP0001",
         PAYMENT_TYPE="TARGET2", DIRECTION="IN", AMOUNT=725812.50, CURRENCY="EUR",
         VALUE_DATE=_iso(2024, 5, 12), STATUS="SETTLED", SETTLED_AT="2024-05-13 08:40:00",
         REFERENCE="T2-24051200077", FAIL_REASON=None),
    dict(PAYMENT_ID="PMT-000003", OTC_ID="OTC-000001", SSI_ID="SSI-USD-CP0001",
         PAYMENT_TYPE="SWIFT_MT202", DIRECTION="OUT", AMOUNT=84500.00, CURRENCY="USD",
         VALUE_DATE=_iso(2024, 5, 14), STATUS="FAILED", SETTLED_AT=None,
         REFERENCE="PMT24051400045", FAIL_REASON="Beneficiary account closed"),
    dict(PAYMENT_ID="PMT-000004", OTC_ID="OTC-000013", SSI_ID="SSI-USD-CP0002",
         PAYMENT_TYPE="BOOK_TRANSFER", DIRECTION="IN", AMOUNT=112000.00, CURRENCY="USD",
         VALUE_DATE=_iso(2024, 6, 24), STATUS="CANCELLED", SETTLED_AT=None,
         REFERENCE=None, FAIL_REASON="Underlying termination cancelled"),
    dict(PAYMENT_ID="PMT-000005", OTC_ID="OTC-000002", SSI_ID="SSI-USD-CP0002",
         PAYMENT_TYPE="FEDWIRE", DIRECTION="OUT", AMOUNT=41250.00, CURRENCY="USD",
         VALUE_DATE=_iso(2024, 6, 28), STATUS="PENDING", SETTLED_AT=None,
         REFERENCE="FW24062800311", FAIL_REASON=None),
    dict(PAYMENT_ID="PMT-000006", OTC_ID="OTC-000008", SSI_ID="SSI-EUR-CP0001",
         PAYMENT_TYPE="TARGET2", DIRECTION="OUT", AMOUNT=312500.00, CURRENCY="EUR",
         VALUE_DATE=_iso(2024, 6, 20), STATUS="SETTLED", SETTLED_AT="2024-06-20 14:05:00",
         REFERENCE="T2-24062000199", FAIL_REASON=None),
]

NOSTRO_ACCOUNT = [
    dict(NOSTRO_ID="NOS-USD-CHAS", CORRESPONDENT_BIC="CHASUS33", CURRENCY="USD",
         ACCOUNT_NUMBER="8-901-2345", ACCOUNT_NAME="USD clearing - JPM",
         OPENING_BALANCE=125000000.00, IS_ACTIVE=True, LAST_RECONCILED=_iso(2024, 6, 27)),
    dict(NOSTRO_ID="NOS-EUR-DEUT", CORRESPONDENT_BIC="DEUTDEFF", CURRENCY="EUR",
         ACCOUNT_NAME="EUR clearing - DB", ACCOUNT_NUMBER="400-88231",
         OPENING_BALANCE=64000000.00, IS_ACTIVE=True, LAST_RECONCILED=_iso(2024, 6, 27)),
    dict(NOSTRO_ID="NOS-GBP-BARC", CORRESPONDENT_BIC="BARCGB22", CURRENCY="GBP",
         ACCOUNT_NAME="GBP clearing - Barclays", ACCOUNT_NUMBER="20-32-11-4471",
         OPENING_BALANCE=18500000.00, IS_ACTIVE=False, LAST_RECONCILED=_iso(2024, 3, 29)),
]

# What the correspondent says happened. Deliberately not a mirror of PAYMENT.
NOSTRO_MOVEMENT = [
    dict(MOVEMENT_ID="MOV-000001", NOSTRO_ID="NOS-USD-CHAS", VALUE_DATE=_iso(2024, 6, 19),
         AMOUNT=-666400.00, CURRENCY="USD", STATEMENT_REF="ST240619-0012",
         NARRATIVE="OUR REF PMT24061900123", POSTED_AT="2024-06-19 09:14:00"),
    dict(MOVEMENT_ID="MOV-000002", NOSTRO_ID="NOS-EUR-DEUT", VALUE_DATE=_iso(2024, 5, 13),
         AMOUNT=725812.50, CURRENCY="EUR", STATEMENT_REF="ST240513-0004",
         NARRATIVE="OUR REF T2-24051200077", POSTED_AT="2024-05-13 08:41:00"),
    # On the statement and not in our ledger: a receipt we were not expecting.
    dict(MOVEMENT_ID="MOV-000003", NOSTRO_ID="NOS-USD-CHAS", VALUE_DATE=_iso(2024, 6, 21),
         AMOUNT=9350.00, CURRENCY="USD", STATEMENT_REF="ST240621-0031",
         NARRATIVE="UNIDENTIFIED CREDIT", POSTED_AT="2024-06-21 11:02:00"),
    # Same amount as PMT-000006, a day late. Matched on amount, broken on value date.
    dict(MOVEMENT_ID="MOV-000004", NOSTRO_ID="NOS-EUR-DEUT", VALUE_DATE=_iso(2024, 6, 21),
         AMOUNT=-312500.00, CURRENCY="EUR", STATEMENT_REF="ST240621-0008",
         NARRATIVE="OUR REF T2-24062000199", POSTED_AT="2024-06-21 15:20:00"),
]

RECON_ITEM = [
    dict(RECON_ID="REC-00001", NOSTRO_ID="NOS-USD-CHAS", PAYMENT_ID="PMT-000001",
         MOVEMENT_ID="MOV-000001", ITEM_TYPE="MATCHED", AMOUNT=666400.00, CURRENCY="USD",
         RECON_DATE=_iso(2024, 6, 20), STATUS="CLOSED", ASSIGNED_TO=None, AGE_DAYS=0),
    dict(RECON_ID="REC-00002", NOSTRO_ID="NOS-EUR-DEUT", PAYMENT_ID="PMT-000002",
         MOVEMENT_ID="MOV-000002", ITEM_TYPE="MATCHED", AMOUNT=725812.50, CURRENCY="EUR",
         RECON_DATE=_iso(2024, 5, 14), STATUS="CLOSED", ASSIGNED_TO=None, AGE_DAYS=0),
    dict(RECON_ID="REC-00003", NOSTRO_ID="NOS-USD-CHAS", PAYMENT_ID=None,
         MOVEMENT_ID="MOV-000003", ITEM_TYPE="UNMATCHED_STATEMENT", AMOUNT=9350.00,
         CURRENCY="USD", RECON_DATE=_iso(2024, 6, 22), STATUS="OPEN",
         ASSIGNED_TO="bo.recon.ny", AGE_DAYS=6),
    dict(RECON_ID="REC-00004", NOSTRO_ID="NOS-USD-CHAS", PAYMENT_ID="PMT-000003",
         MOVEMENT_ID=None, ITEM_TYPE="UNMATCHED_LEDGER", AMOUNT=84500.00, CURRENCY="USD",
         RECON_DATE=_iso(2024, 5, 15), STATUS="OPEN", ASSIGNED_TO="bo.recon.ny",
         AGE_DAYS=44),
    dict(RECON_ID="REC-00005", NOSTRO_ID="NOS-EUR-DEUT", PAYMENT_ID="PMT-000006",
         MOVEMENT_ID="MOV-000004", ITEM_TYPE="VALUE_DATE_MISMATCH", AMOUNT=312500.00,
         CURRENCY="EUR", RECON_DATE=_iso(2024, 6, 22), STATUS="INVESTIGATING",
         ASSIGNED_TO="bo.recon.frankfurt", AGE_DAYS=6),
]



# ------------------------------------------------- corporate actions, securities, orders
#
# Three taxonomies a firm cannot avoid. Corporate actions are what happens to a security
# without anyone trading it; the securities master is every instrument type the firm can
# hold; and the order ticket is every way of asking a venue for a fill.
#
# Two of every three types carry live rows. The rest are types the firm can process and does
# not have today, which is the normal state of a reference-data taxonomy.
CORPORATE_ACTION = [
    dict(ACTION_ID="CA-00001", ACTION_TYPE="CASH_DIVIDEND", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 1), EX_DATE=_iso(2024, 1, 3),
         RECORD_DATE=_iso(2024, 1, 4), PAY_DATE=_iso(2024, 2, 5),
         STATUS="ANNOUNCED", IS_MANDATORY=True, CASH_RATE=0.25,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00002", ACTION_TYPE="SPECIAL_DIVIDEND", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 2), EX_DATE=_iso(2024, 2, 4),
         RECORD_DATE=_iso(2024, 2, 5), PAY_DATE=_iso(2024, 3, 6),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00004", ACTION_TYPE="SCRIP_DIVIDEND", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 4, 4), EX_DATE=_iso(2024, 4, 6),
         RECORD_DATE=_iso(2024, 4, 7), PAY_DATE=_iso(2024, 5, 8),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00005", ACTION_TYPE="DRIP", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 5, 5), EX_DATE=_iso(2024, 5, 7),
         RECORD_DATE=_iso(2024, 5, 8), PAY_DATE=_iso(2024, 6, 9),
         STATUS="ANNOUNCED", IS_MANDATORY=False, CASH_RATE=0.45,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00007", ACTION_TYPE="REVERSE_SPLIT", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 7), EX_DATE=_iso(2024, 1, 9),
         RECORD_DATE=_iso(2024, 1, 10), PAY_DATE=_iso(2024, 2, 11),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=0.55,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00008", ACTION_TYPE="BONUS_ISSUE", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 8), EX_DATE=_iso(2024, 2, 10),
         RECORD_DATE=_iso(2024, 2, 11), PAY_DATE=_iso(2024, 3, 12),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00010", ACTION_TYPE="OPEN_OFFER", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 4, 10), EX_DATE=_iso(2024, 4, 12),
         RECORD_DATE=_iso(2024, 4, 13), PAY_DATE=_iso(2024, 5, 14),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00011", ACTION_TYPE="MERGER", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 5, 11), EX_DATE=_iso(2024, 5, 13),
         RECORD_DATE=_iso(2024, 5, 14), PAY_DATE=_iso(2024, 6, 15),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=0.75,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00013", ACTION_TYPE="SPINOFF", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 13), EX_DATE=_iso(2024, 1, 15),
         RECORD_DATE=_iso(2024, 1, 16), PAY_DATE=_iso(2024, 2, 17),
         STATUS="ANNOUNCED", IS_MANDATORY=True, CASH_RATE=0.85,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00014", ACTION_TYPE="DEMERGER", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 14), EX_DATE=_iso(2024, 2, 16),
         RECORD_DATE=_iso(2024, 2, 17), PAY_DATE=_iso(2024, 3, 18),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00016", ACTION_TYPE="EXCHANGE_OFFER", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 4, 16), EX_DATE=_iso(2024, 4, 18),
         RECORD_DATE=_iso(2024, 4, 19), PAY_DATE=_iso(2024, 5, 20),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00017", ACTION_TYPE="SCHEME_OF_ARRANGEMENT", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 5, 17), EX_DATE=_iso(2024, 5, 19),
         RECORD_DATE=_iso(2024, 5, 20), PAY_DATE=_iso(2024, 6, 21),
         STATUS="ANNOUNCED", IS_MANDATORY=False, CASH_RATE=1.05,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00019", ACTION_TYPE="RETURN_OF_CAPITAL", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 19), EX_DATE=_iso(2024, 1, 21),
         RECORD_DATE=_iso(2024, 1, 22), PAY_DATE=_iso(2024, 2, 23),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=1.15,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00020", ACTION_TYPE="REDEMPTION", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 20), EX_DATE=_iso(2024, 2, 22),
         RECORD_DATE=_iso(2024, 2, 23), PAY_DATE=_iso(2024, 3, 24),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00022", ACTION_TYPE="CALL", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 4, 22), EX_DATE=_iso(2024, 4, 24),
         RECORD_DATE=_iso(2024, 4, 25), PAY_DATE=_iso(2024, 5, 26),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00023", ACTION_TYPE="PUT", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 5, 23), EX_DATE=_iso(2024, 5, 25),
         RECORD_DATE=_iso(2024, 5, 26), PAY_DATE=_iso(2024, 6, 27),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=1.35,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00025", ACTION_TYPE="COUPON_PAYMENT", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 25), EX_DATE=_iso(2024, 1, 27),
         RECORD_DATE=_iso(2024, 1, 4), PAY_DATE=_iso(2024, 2, 6),
         STATUS="ANNOUNCED", IS_MANDATORY=True, CASH_RATE=1.45,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00026", ACTION_TYPE="NAME_CHANGE", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 26), EX_DATE=_iso(2024, 2, 3),
         RECORD_DATE=_iso(2024, 2, 5), PAY_DATE=_iso(2024, 3, 7),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00028", ACTION_TYPE="DELISTING", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 4, 1), EX_DATE=_iso(2024, 4, 5),
         RECORD_DATE=_iso(2024, 4, 7), PAY_DATE=_iso(2024, 5, 9),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
    dict(ACTION_ID="CA-00029", ACTION_TYPE="BANKRUPTCY", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 5, 2), EX_DATE=_iso(2024, 5, 6),
         RECORD_DATE=_iso(2024, 5, 8), PAY_DATE=_iso(2024, 6, 10),
         STATUS="ANNOUNCED", IS_MANDATORY=False, CASH_RATE=1.65,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00031", ACTION_TYPE="CONSENT_SOLICITATION", SECURITY_ID="INST-AAPL",
         ANNOUNCED_DATE=_iso(2024, 1, 4), EX_DATE=_iso(2024, 1, 8),
         RECORD_DATE=_iso(2024, 1, 10), PAY_DATE=_iso(2024, 2, 12),
         STATUS="CONFIRMED", IS_MANDATORY=True, CASH_RATE=1.75,
         RATIO_OLD=None, RATIO_NEW=None, CURRENCY="USD"),
    dict(ACTION_ID="CA-00032", ACTION_TYPE="WARRANT_EXERCISE", SECURITY_ID="INST-MSFT",
         ANNOUNCED_DATE=_iso(2024, 2, 5), EX_DATE=_iso(2024, 2, 9),
         RECORD_DATE=_iso(2024, 2, 11), PAY_DATE=_iso(2024, 3, 13),
         STATUS="CONFIRMED", IS_MANDATORY=False, CASH_RATE=None,
         RATIO_OLD=1, RATIO_NEW=2, CURRENCY="USD"),
]

SECURITY_MASTER = [
    dict(SECURITY_KEY="SM-00001", SECURITY_TYPE="COMMON_STOCK", DESCRIPTION="Common Stock sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2015, 1, 1), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00002", SECURITY_TYPE="PREFERRED_STOCK", DESCRIPTION="Preferred Stock sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2016, 2, 2), MATURITY_DATE=_iso(2028, 2, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00004", SECURITY_TYPE="GDR", DESCRIPTION="Gdr sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2018, 4, 4), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=False, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00005", SECURITY_TYPE="REIT", DESCRIPTION="Reit sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2019, 5, 5), MATURITY_DATE=_iso(2031, 5, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00007", SECURITY_TYPE="CLOSED_END_FUND", DESCRIPTION="Closed End Fund sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2021, 7, 7), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00008", SECURITY_TYPE="ETF_SHARE", DESCRIPTION="Etf Share sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2022, 8, 8), MATURITY_DATE=_iso(2028, 8, 15),
         FACE_VALUE=1000.0, IS_LISTED=False, EXCHANGE_MIC="XLON",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00010", SECURITY_TYPE="WARRANT", DESCRIPTION="Warrant sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2015, 10, 10), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00011", SECURITY_TYPE="SUBSCRIPTION_RIGHT", DESCRIPTION="Subscription Right sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2016, 11, 11), MATURITY_DATE=_iso(2031, 11, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="EQUITY"),
    dict(SECURITY_KEY="SM-00013", SECURITY_TYPE="TREASURY_BILL", DESCRIPTION="Treasury Bill sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2018, 1, 13), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00014", SECURITY_TYPE="INFLATION_LINKED_BOND", DESCRIPTION="Inflation Linked Bond sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2019, 2, 14), MATURITY_DATE=_iso(2028, 2, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00016", SECURITY_TYPE="MUNICIPAL_BOND", DESCRIPTION="Municipal Bond sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2021, 4, 16), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=False, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00017", SECURITY_TYPE="CORPORATE_BOND", DESCRIPTION="Corporate Bond sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2022, 5, 17), MATURITY_DATE=_iso(2031, 5, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00019", SECURITY_TYPE="CONVERTIBLE_BOND", DESCRIPTION="Convertible Bond sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2015, 7, 19), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00020", SECURITY_TYPE="FLOATING_RATE_NOTE", DESCRIPTION="Floating Rate Note sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2016, 8, 20), MATURITY_DATE=_iso(2028, 8, 15),
         FACE_VALUE=1000.0, IS_LISTED=False, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00022", SECURITY_TYPE="COVERED_BOND", DESCRIPTION="Covered Bond sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2018, 10, 22), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00023", SECURITY_TYPE="PERPETUAL_BOND", DESCRIPTION="Perpetual Bond sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2019, 11, 23), MATURITY_DATE=_iso(2031, 11, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00025", SECURITY_TYPE="ASSET_BACKED_SECURITY", DESCRIPTION="Asset Backed Security sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2021, 1, 25), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00026", SECURITY_TYPE="MORTGAGE_BACKED_SECURITY", DESCRIPTION="Mortgage Backed Security sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2022, 2, 26), MATURITY_DATE=_iso(2028, 2, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00028", SECURITY_TYPE="COMMERCIAL_PAPER", DESCRIPTION="Commercial Paper sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2015, 4, 1), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=False, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00029", SECURITY_TYPE="CERTIFICATE_OF_DEPOSIT", DESCRIPTION="Certificate Of Deposit sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2016, 5, 2), MATURITY_DATE=_iso(2031, 5, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FIXED_INCOME"),
    dict(SECURITY_KEY="SM-00031", SECURITY_TYPE="MUTUAL_FUND_UNIT", DESCRIPTION="Mutual Fund Unit sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2018, 7, 4), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FUND"),
    dict(SECURITY_KEY="SM-00032", SECURITY_TYPE="MONEY_MARKET_FUND", DESCRIPTION="Money Market Fund sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2019, 8, 5), MATURITY_DATE=_iso(2028, 8, 15),
         FACE_VALUE=1000.0, IS_LISTED=False, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FUND"),
    dict(SECURITY_KEY="SM-00034", SECURITY_TYPE="LISTED_OPTION", DESCRIPTION="Listed Option sample",
         ISSUER_NAME="Northbridge Capital", CURRENCY="USD",
         ISSUE_DATE=_iso(2021, 10, 7), MATURITY_DATE=None,
         FACE_VALUE=None, IS_LISTED=True, EXCHANGE_MIC="XNYS",
         ASSET_CLASS="FUND"),
    dict(SECURITY_KEY="SM-00035", SECURITY_TYPE="STRUCTURED_NOTE", DESCRIPTION="Structured Note sample",
         ISSUER_NAME="Kestrel Industries", CURRENCY="EUR",
         ISSUE_DATE=_iso(2022, 11, 8), MATURITY_DATE=_iso(2031, 11, 15),
         FACE_VALUE=1000.0, IS_LISTED=True, EXCHANGE_MIC="XLON",
         ASSET_CLASS="FUND"),
]

ORDER_TICKET = [
    dict(TICKET_ID="OT-00001", ORDER_TYPE="MARKET", SECURITY_ID="INST-AAPL",
         SIDE="SELL", QUANTITY=500, LIMIT_PRICE=None,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-10 10:30:00", STATUS="FILLED",
         FILLED_QUANTITY=500, TRADER_ID="TRD-001"),
    dict(TICKET_ID="OT-00002", ORDER_TYPE="LIMIT", SECURITY_ID="INST-MSFT",
         SIDE="BUY", QUANTITY=1000, LIMIT_PRICE=152.5,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-11 11:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=400, TRADER_ID="TRD-002"),
    dict(TICKET_ID="OT-00004", ORDER_TYPE="STOP_LIMIT", SECURITY_ID="INST-AAPL",
         SIDE="BUY", QUANTITY=2000, LIMIT_PRICE=157.5,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-13 13:30:00", STATUS="FILLED",
         FILLED_QUANTITY=2000, TRADER_ID="TRD-004"),
    dict(TICKET_ID="OT-00005", ORDER_TYPE="MARKET_ON_CLOSE", SECURITY_ID="INST-MSFT",
         SIDE="SELL", QUANTITY=2500, LIMIT_PRICE=None,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-14 14:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=1000, TRADER_ID="TRD-005"),
    dict(TICKET_ID="OT-00007", ORDER_TYPE="MARKET_ON_OPEN", SECURITY_ID="INST-AAPL",
         SIDE="SELL", QUANTITY=3500, LIMIT_PRICE=None,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-16 16:30:00", STATUS="FILLED",
         FILLED_QUANTITY=3500, TRADER_ID="TRD-002"),
    dict(TICKET_ID="OT-00008", ORDER_TYPE="ICEBERG", SECURITY_ID="INST-MSFT",
         SIDE="BUY", QUANTITY=4000, LIMIT_PRICE=167.5,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-17 17:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=1600, TRADER_ID="TRD-003"),
    dict(TICKET_ID="OT-00010", ORDER_TYPE="PEGGED", SECURITY_ID="INST-AAPL",
         SIDE="BUY", QUANTITY=5000, LIMIT_PRICE=172.5,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-19 10:30:00", STATUS="FILLED",
         FILLED_QUANTITY=5000, TRADER_ID="TRD-005"),
    dict(TICKET_ID="OT-00011", ORDER_TYPE="MIDPOINT_PEG", SECURITY_ID="INST-MSFT",
         SIDE="SELL", QUANTITY=5500, LIMIT_PRICE=None,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-20 11:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=2200, TRADER_ID="TRD-001"),
    dict(TICKET_ID="OT-00013", ORDER_TYPE="VWAP", SECURITY_ID="INST-AAPL",
         SIDE="SELL", QUANTITY=6500, LIMIT_PRICE=None,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-22 13:30:00", STATUS="FILLED",
         FILLED_QUANTITY=6500, TRADER_ID="TRD-003"),
    dict(TICKET_ID="OT-00014", ORDER_TYPE="POV", SECURITY_ID="INST-MSFT",
         SIDE="BUY", QUANTITY=7000, LIMIT_PRICE=182.5,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-23 14:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=2800, TRADER_ID="TRD-004"),
    dict(TICKET_ID="OT-00016", ORDER_TYPE="LIQUIDITY_SEEKING", SECURITY_ID="INST-AAPL",
         SIDE="BUY", QUANTITY=8000, LIMIT_PRICE=187.5,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-25 16:30:00", STATUS="FILLED",
         FILLED_QUANTITY=8000, TRADER_ID="TRD-001"),
    dict(TICKET_ID="OT-00017", ORDER_TYPE="DARK_AGGREGATOR", SECURITY_ID="INST-MSFT",
         SIDE="SELL", QUANTITY=8500, LIMIT_PRICE=None,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-26 17:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=3400, TRADER_ID="TRD-002"),
    dict(TICKET_ID="OT-00019", ORDER_TYPE="FILL_OR_KILL", SECURITY_ID="INST-AAPL",
         SIDE="SELL", QUANTITY=9500, LIMIT_PRICE=None,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-10 10:30:00", STATUS="FILLED",
         FILLED_QUANTITY=9500, TRADER_ID="TRD-004"),
    dict(TICKET_ID="OT-00020", ORDER_TYPE="IMMEDIATE_OR_CANCEL", SECURITY_ID="INST-MSFT",
         SIDE="BUY", QUANTITY=10000, LIMIT_PRICE=197.5,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-11 11:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=4000, TRADER_ID="TRD-005"),
    dict(TICKET_ID="OT-00022", ORDER_TYPE="GOOD_TILL_CANCELLED", SECURITY_ID="INST-AAPL",
         SIDE="BUY", QUANTITY=11000, LIMIT_PRICE=202.5,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-13 13:30:00", STATUS="FILLED",
         FILLED_QUANTITY=11000, TRADER_ID="TRD-002"),
    dict(TICKET_ID="OT-00023", ORDER_TYPE="GOOD_TILL_DATE", SECURITY_ID="INST-MSFT",
         SIDE="SELL", QUANTITY=11500, LIMIT_PRICE=None,
         TIME_IN_FORCE="GTC", VENUE="BATS",
         PLACED_AT="2024-06-14 14:30:00", STATUS="PARTIAL",
         FILLED_QUANTITY=4600, TRADER_ID="TRD-003"),
    dict(TICKET_ID="OT-00025", ORDER_TYPE="AT_THE_OPEN", SECURITY_ID="INST-AAPL",
         SIDE="SELL", QUANTITY=12500, LIMIT_PRICE=None,
         TIME_IN_FORCE="DAY", VENUE="XNYS",
         PLACED_AT="2024-06-16 16:30:00", STATUS="FILLED",
         FILLED_QUANTITY=12500, TRADER_ID="TRD-005"),
]


TABLES: dict[str, list[dict]] = {
    "EXT_LEGAL_ENTITY": EXT_LEGAL_ENTITY,
    "CPTY_RATING_MS": CPTY_RATING_MS,
    "INSTR_RATING_BI": INSTR_RATING_BI,
    "COUNTRY": COUNTRY, "CURRENCY": CURRENCY, "EXCHANGE": EXCHANGE, "SECTOR": SECTOR,
    "DESK": DESK, "TRADER": TRADER, "BOOK": BOOK, "COUNTERPARTY": COUNTERPARTY,
    "INSTRUMENT": INSTRUMENT, "TRADE": TRADE, "POSITION": POSITION, "GREEKS": GREEKS,
    "SETTLEMENT": SETTLEMENT, "CONFIRMATION": CONFIRMATION,
    "SALES_CREDIT": SALES_CREDIT, "TRADE_REPORT": TRADE_REPORT,
    "DAILY_PN_L": DAILY_PN_L, "COLLATERAL_AGREEMENT": COLLATERAL_AGREEMENT,
    "DEBT_SECURITY": DEBT_SECURITY, "COUPON_PERIOD": COUPON_PERIOD,
    "COUPON_PAYMENT": COUPON_PAYMENT,
    "OTC_TRADE": OTC_TRADE, "OTC_SWAP_LEG": OTC_SWAP_LEG,
    "OTC_OPTION_TERMS": OTC_OPTION_TERMS,
    "RISK_RUN": RISK_RUN, "MR_RISK_FACTOR": RISK_FACTOR, "SENSITIVITY": SENSITIVITY,
    "RISK_MEASURE": RISK_MEASURE, "MR_STRESS_SCENARIO": STRESS_SCENARIO,
    "SCENARIO_RESULT": SCENARIO_RESULT,
    "LIFECYCLE_EVENT": LIFECYCLE_EVENT, "CONFIRMATION_MO": CONFIRMATION_MO,
    "TRADE_BREAK": TRADE_BREAK,
    "BO_SSI": SETTLEMENT_INSTRUCTION, "PAYMENT": PAYMENT,
    "BO_NOSTRO": NOSTRO_ACCOUNT, "NOSTRO_MOVEMENT": NOSTRO_MOVEMENT,
    "RECON_ITEM": RECON_ITEM, "CA_EVENT": CORPORATE_ACTION,
    "SECURITY_MASTER": SECURITY_MASTER, "ORDER_TICKET": ORDER_TICKET,
}


# ------------------------------------------------------------------- checks

def check(c: Corpus) -> list[str]:
    """Two families: the data is legal against the declared schema, and every adversarial
    property A1..A12 is actually present. The second is the one that matters — a fixture
    silently losing its orphan row is how a corpus rots into a smoke test."""
    bad = []

    for name, rows in TABLES.items():
        t = c.tables.get(name)
        if t is None:
            bad.append(f"{name}: not a declared table")
            continue
        for i, r in enumerate(rows):
            unknown = set(r) - set(t.columns)
            if unknown:
                bad.append(f"{name}[{i}]: columns not in schema: {sorted(unknown)}")
            for pk in t.pk:
                if r.get(pk) is None:
                    bad.append(f"{name}[{i}]: NULL primary key {pk}")
        # Uniqueness is on the COMPOSITE key, not per column. A business-temporal table
        # is keyed by (id, from) precisely so the id repeats — checking columns
        # independently would reject exactly the shape SCD2 requires.
        if t.pk:
            keys = [tuple(r.get(k) for k in t.pk) for r in rows]
            if len(set(keys)) != len(keys):
                dupes = {k for k in keys if keys.count(k) > 1}
                bad.append(f"{name}: duplicate primary key {t.pk}: {sorted(dupes)[:3]}")
        # A value containing a comma or a newline would need CSV quoting, whose support
        # in ###Data we have not proven. Refuse it rather than emit something unverified.
        for i, r in enumerate(rows):
            for k, v in r.items():
                if isinstance(v, str) and ("," in v or "\n" in v or '"' in v):
                    bad.append(f"{name}[{i}].{k}: value needs CSV quoting: {v!r}")

    def has(msg, cond):
        if not cond:
            bad.append("adversarial property missing: " + msg)

    ids = {k: {r[c.tables[k].pk[0]] for r in v} for k, v in TABLES.items()
           if c.tables.get(k) and c.tables[k].pk}
    traded_instr = {t["INSTRUMENT_ID"] for t in TRADE}
    traded_cpty = {t["COUNTERPARTY_ID"] for t in TRADE}

    has("A1 ORPHAN_FK on TRADE.COUNTERPARTY_ID",
        any(r["COUNTERPARTY_ID"] not in ids["COUNTERPARTY"] for r in TRADE))
    has("A1 ORPHAN_FK on DAILY_PN_L.BOOK_ID",
        any(r["BOOK_ID"] not in ids["BOOK"] for r in DAILY_PN_L))
    has("A1 ORPHAN_FK on GREEKS.POSITION_ID",
        any(r["POSITION_ID"] not in ids["POSITION"] for r in GREEKS))
    has("A1 ORPHAN_FK on COLLATERAL_AGREEMENT.COUNTERPARTY_ID",
        any(r["COUNTERPARTY_ID"] not in ids["COUNTERPARTY"] for r in COLLATERAL_AGREEMENT))
    has("A2 NULL_FK on TRADE.TRADER_ID", any(r["TRADER_ID"] is None for r in TRADE))
    has("A2 NULL_FK on DAILY_PN_L.TRADER_ID",
        any(r["TRADER_ID"] is None for r in DAILY_PN_L))
    has("A3 ZERO_CHILD instrument",
        any(i["INSTRUMENT_ID"] not in traded_instr for i in INSTRUMENT))
    has("A3 ZERO_CHILD counterparty",
        any(x["COUNTERPARTY_ID"] not in traded_cpty for x in COUNTERPARTY))
    has("A3 ZERO_CHILD sector",
        any(s["SECTOR_ID"] not in {i["SECTOR_ID"] for i in INSTRUMENT} for s in SECTOR))
    has("A3 ZERO_CHILD desk",
        any(d["DESK_ID"] not in {b["DESK_ID"] for b in BOOK} for d in DESK))
    has("A3 ZERO_CHILD book (no trades)",
        any(b["BOOK_ID"] not in {t["BOOK_ID"] for t in TRADE} for b in BOOK))
    has("A4 NULL_MEASURE on TRADE.COMMISSION",
        any(r["COMMISSION"] is None for r in TRADE))
    has("A4 NULL_MEASURE on GREEKS.DELTA", any(r["DELTA"] is None for r in GREEKS))
    notionals = [r["NOTIONAL"] for r in TRADE]
    has("A5 TIES on TRADE.NOTIONAL", len(set(notionals)) < len(notionals))
    top = max(traded_instr, key=lambda i: sum(1 for t in TRADE if t["INSTRUMENT_ID"] == i))
    share = sum(1 for t in TRADE if t["INSTRUMENT_ID"] == top) / len(TRADE)
    has(f"A6 SKEW (top instrument holds {share:.0%}, want >=40%)", share >= 0.40)
    names = [r["LEGAL_NAME"] for r in COUNTERPARTY]
    has("A7 DUP_NATURAL_KEY on COUNTERPARTY.LEGAL_NAME", len(set(names)) < len(names))
    has("A8 BOUNDARY near-miss status",
        {"EXECUTE", "EXECUTED"} <= {r["STATUS"] for r in TRADE})
    has("A8 BOUNDARY exact zero measure", any(r["PNL_YTD"] == 0.0 for r in BOOK))
    # A9 is withdrawn (see the header). Guard the withdrawal instead: an empty string
    # anywhere in the seed would silently be read back as NULL and quietly weaken a case.
    for _name, _rows in TABLES.items():
        for _i, _r in enumerate(_rows):
            if any(v == "" for v in _r.values()):
                bad.append(f"{_name}[{_i}]: empty string cannot survive ###Data CSV "
                           f"(becomes NULL) -- see docs/UPSTREAM_FINDINGS.md F2")
    has("A10 APOSTROPHE in a projected string",
        any("'" in (r["LEGAL_NAME"] or "") for r in COUNTERPARTY)
        and any("'" in (r["LAST_NAME"] or "") for r in TRADER))
    has("A11 CHAIN_NULL on INSTRUMENT.SECTOR_ID",
        any(r["SECTOR_ID"] not in ids["SECTOR"] for r in INSTRUMENT))
    codes = {r["SIDE"] for r in TRADE}
    has("A13 MANY_TO_ONE_ENUM: two source codes for BUY", {"B", "BOT"} <= codes)
    has("A13 all SIDE values are codes, not labels",
        not ({"BUY", "SELL"} & codes))
    has("A14 UNMAPPED_ENUM: a SIDE code with no EnumerationMapping entry",
        bool(codes - {"B", "BOT", "S"}))
    # A15 — each edge asserted separately, so losing one is a named failure.
    ms = CPTY_RATING_MS
    cur = {r["COUNTERPARTY_ID"] for r in ms if r["THRU_Z"] == INFINITY}
    allc = {r["COUNTERPARTY_ID"] for r in ms}
    has("A15 a version boundary landing exactly on 2024-06-07",
        any(r["FROM_Z"] == _iso(2024, 6, 7) for r in ms))
    has("A15 an entity whose rating was withdrawn (no current version)", bool(allc - cur))
    has("A15 an entity with no rating history at all",
        bool({r["COUNTERPARTY_ID"] for r in COUNTERPARTY} - allc))
    has("A15 an entity with exactly one version",
        any(sum(1 for r in ms if r["COUNTERPARTY_ID"] == x) == 1 for x in allc))
    has("A15 an entity with three versions",
        any(sum(1 for r in ms if r["COUNTERPARTY_ID"] == x) == 3 for x in allc))
    for x in sorted(allc):
        vs = sorted((r["FROM_Z"], r["THRU_Z"]) for r in ms if r["COUNTERPARTY_ID"] == x)
        for (_f1, t1), (f2, _t2) in zip(vs, vs[1:]):
            if t1 != f2:
                bad.append(f"CPTY_RATING_MS {x}: gap or overlap between {t1} and {f2}; "
                           f"SCD2 versions must abut exactly")
    # A17 — the correction must be a genuine supersession, not two unrelated rows.
    bi = INSTR_RATING_BI
    corrected = [r for r in bi if r["OUT_Z"] != INFINITY]
    has("A17 a row closed in PROCESSING time (a correction)", bool(corrected))
    for r in corrected:
        same = [x for x in bi if x["INSTRUMENT_ID"] == r["INSTRUMENT_ID"]
                and x["FROM_Z"] == r["FROM_Z"] and x["IN_Z"] == r["OUT_Z"]]
        if not same:
            bad.append(f"INSTR_RATING_BI: {r['INSTRUMENT_ID']} is closed in processing "
                       f"time at {r['OUT_Z']} with no successor opening then; a "
                       f"correction that supersedes nothing is just a deletion")
        elif same[0]["CREDIT_RATING"] == r["CREDIT_RATING"]:
            bad.append(f"INSTR_RATING_BI: the correction for {r['INSTRUMENT_ID']} carries "
                       f"the SAME rating, so no query could tell the two apart")
    has("A17 an instrument with no correction, for contrast",
        any(r["OUT_Z"] == INFINITY for r in bi))
    # A18 — the cross-store link must have all three outcomes reachable.
    ents = {r["ENTITY_ID"] for r in EXT_LEGAL_ENTITY}
    refs = {r["COUNTERPARTY_ID"] for r in TRADE}
    has("A18 XSTORE a trade whose entity exists", bool(refs & ents))
    has("A18 XSTORE a trade whose entity is ABSENT from the other store",
        bool(refs - ents))
    has("A18 XSTORE an entity matched by no trade", bool(ents - refs))
    has("A18 XSTORE names differ from the local counterparty names",
        not (ents & {r["LEGAL_NAME"] for r in COUNTERPARTY}))
    tids = {r["TRADER_ID"] for r in TRADER}
    mgrs = [r["MANAGER_ID"] for r in TRADER]
    has("A16 a trader who reports to nobody", any(m is None for m in mgrs))
    has("A16 a trader whose manager exists", any(m in tids for m in mgrs if m))
    has("A16 a trader whose manager id dangles",
        any(m not in tids for m in mgrs if m))
    venues = {r["EXECUTION_VENUE"] for r in TRADE}
    has("A12 CASE_SENSITIVE venue codes",
        any(v.lower() in {o.lower() for o in venues - {v}} for v in venues))
    return bad


if __name__ == "__main__":
    import model

    c = model.load()
    print("seed tables:")
    for name, rows in sorted(TABLES.items()):
        print(f"  {name:<22} {len(rows):>3} rows")
    print(f"\ntotal rows: {sum(len(v) for v in TABLES.values())}")
    problems = check(c)
    print(f"\nself-check: {'OK' if not problems else str(len(problems)) + ' PROBLEMS'}")
    for p in problems:
        print("  -", p)
