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
