"""
The fan-out battery — the half of L0 the original 12 services could not reach.

All 530 projection paths in the 12 stress services are to-one navigations. That was
measured, not assumed (`python3 query.py` reports "0 cross a to-many hop"). It means the
corpus exercised outer joins that land on at most one row and never one that fans out —
and fan-out is where the interesting defects live.

Specifically, `count()` over a to-many association compiles to an aggregate over a LEFT
OUTER JOIN. An entity with no children still contributes one all-NULL joined row, so an
implementation that emits `COUNT(*)` rather than `COUNT(child_key)` returns **1** where
the answer is **0**. It is a silent wrong answer: no error, no empty result, just a count
that is too high by one for exactly the rows a naive fixture would not contain.

The seed was built with that in mind. Every root below has at least one childless member:

  INST-NESN    listed, never traded, no positions, no greeks
  CP-0005      onboarded, never traded with, no settlements, no CSA
  BK-LEGACY    closed book, no trades, no PnL
  DSK-CREDIT   no books, no traders, no PnL
  SEC-55       no instruments
  POS-0003/4/6 no greeks

Each service pairs identifying columns with several `->count()` projections, so one row
carries several independent assertions and a defect in any one of them reddens the suite.
"""
from __future__ import annotations

from query import Proj, Spec

PREFIX = "stress::F"


def _spec(n: int, name: str, root: str, doc: str, ids: list[tuple[str, str]],
          counts: list[tuple[str, str]], calls: list[tuple[str, str, list]] = ()) -> Spec:
    s = Spec(f"{PREFIX}{n}_{name}", f"/stress/f{n}", doc, root)
    s.projections = ([Proj(a, p.split(".")) for a, p in ids]
                     + [Proj(a, p.split("."), "count") for a, p in counts]
                     + [Proj(a, p.split("."), None, list(args)) for a, p, args in calls])
    return s


# ---------------------------------------------------------------- L2 invariance
#
# The SAME question, asked of the canonical model (which joins four ways) and of the
# reporting model (which reads one denormalized table). The projection aliases are
# identical by construction, so the two expectations must be equal cell for cell —
# build.py asserts that before either is emitted, and a mismatch fails the build rather
# than shipping two tests that quietly disagree.
#
# This is the strongest assertion in the corpus because it needs no oracle to be
# meaningful: two mappings of the same facts must agree, so a disagreement is the
# engine's. The oracle is still used, which makes it stronger again — it pins the VALUE
# as well as the agreement.

_INVARIANT_COLUMNS = [
    ("tradeId", "tradeId", "tradeId"),
    ("tradeDate", "tradeDate", "tradeDate"),
    ("quantity", "quantity", "quantity"),
    ("price", "price", "price"),
    ("notional", "notional", "notional"),
    ("side", "side", "side"),
    ("status", "status", "status"),
    ("currency", "currency", "currency"),
    ("grossAmount", "grossAmount", "grossAmount"),
    # The four navigations that the flat shape has already resolved. TRD-0007's
    # counterparty is an orphan and two trades have no trader, so these carry the NULLs
    # that make the comparison worth doing.
    ("instrName", "instrument.name", "instrName"),
    ("instrTicker", "instrument.ticker", "instrTicker"),
    ("instrIsin", "instrument.isin", "instrIsin"),
    ("bookName", "book.name", "bookName"),
    ("cptyName", "counterparty.legalName", "cptyName"),
    ("cptyLei", "counterparty.lei", "cptyLei"),
    ("traderLast", "trader.lastName", "traderLast"),
]


def _invariance_pair():
    canonical = Spec("stress::N0_TradeCanonical", "/stress/n0",
                     "Mapping invariance, canonical side: reached by joining "
                     "trading::Trade to instrument, book, counterparty and trader.",
                     "trading::Trade")
    canonical.projections = [Proj(a, p.split(".")) for a, p, _ in _INVARIANT_COLUMNS]

    flat = Spec("stress::N1_TradeFlat", "/stress/n1",
                "Mapping invariance, reporting side: the identical question answered from "
                "one denormalized table through reporting::FlatMapping. Must return "
                "exactly the rows N0 returns.",
                "reporting::FlatTrade")
    flat.projections = [Proj(a, f.split(".")) for a, _, f in _INVARIANT_COLUMNS]
    flat.mapping, flat.runtime = "reporting::FlatMapping", "stress::FlatRT"
    return canonical, flat


def _embedded_variant(canonical: Spec) -> Spec:
    """The strongest form: the IDENTICAL query — same root class, same property paths,
    same generated text — resolved by a mapping that reads one denormalized table through
    embedded property mappings instead of joining four ways.

    Its expectation needs no separate derivation. oracle.py deliberately does not model
    reporting::EmbeddedFlatMapping, so evaluating this spec walks the canonical mapping
    and produces the canonical answer — which is exactly the claim under test.
    """
    s = Spec("stress::N2_TradeEmbeddedFlat", "/stress/n2",
             "Mapping invariance, embedded side: byte-identical query to N0, resolved "
             "against TRADE_FLAT through embedded property mappings. No join is emitted "
             "at all, and the answer must not change.",
             canonical.root)
    s.projections = list(canonical.projections)
    s.mapping, s.runtime = "reporting::EmbeddedFlatMapping", "stress::EmbeddedFlatRT"
    return s


CANONICAL, FLAT = _invariance_pair()
EMBEDDED = _embedded_variant(CANONICAL)
INVARIANCE = [CANONICAL, FLAT, EMBEDDED]


DERIVED = [
    _spec(7, "TradeDerivedProperties", "trading::Trade",
          "Derived properties alongside the columns they are computed from, so a wrong "
          "value cannot hide: grossAmount is total arithmetic over two [1] measures.",
          [("tradeId", "tradeId"), ("quantity", "quantity"), ("price", "price"),
           ("commission", "commission"), ("fees", "fees"),
           ("settlementDate", "settlementDate"), ("side", "side"),
           ("grossAmount", "grossAmount")],
          [],
          # The same quantity*price, scaled by an argument. Two different rates on one
          # row, so a qualified property that ignored its parameter — returning the
          # unscaled gross twice — is caught rather than merely looking plausible.
          [("grossInGbp", "grossAmountIn", [0.79]),
           ("grossInEur", "grossAmountIn", [0.92])]),

    _spec(8, "SettlementTradeDerived", "settlement::Settlement",
          "A derived property reached ACROSS an association, so it is evaluated on a "
          "joined row rather than the root. STL-9999 points at a trade that does not "
          "exist: grossAmount must be NULL there, which it is in both engines because "
          "NULL arithmetic propagates. The case that does NOT survive this shape -- a "
          "derived property that INSPECTS nullness -- is F8, in repro/derived-on-absent/.",
          [("settlementId", "settlementId"), ("status", "status"),
           ("tradeId", "trade.tradeId"), ("tradeGross", "trade.grossAmount"),
          ],
          []),
]

SPECS = INVARIANCE + DERIVED + [
    _spec(0, "InstrumentChildCounts", "products::Instrument",
          "Fan-out: per-instrument child counts. INST-NESN is childless on every end, "
          "which is the count-over-outer-join case.",
          [("instrumentId", "instrumentId"), ("name", "name"), ("ticker", "ticker"),
           ("assetClass", "assetClass"), ("isActive", "isActive")],
          [("tradeCount", "trades"), ("positionCount", "positions"),
           ("greeksCount", "instrumentGreeks")]),

    _spec(1, "CounterpartyChildCounts", "counterparty::Counterparty",
          "Fan-out: per-counterparty exposure. CP-0005 has no trades, settlements or "
          "collateral agreements; CP-0004 has trades but no CSA.",
          [("counterpartyId", "counterpartyId"), ("legalName", "legalName"),
           ("tier", "tier"), ("isActive", "isActive"),
           ("countryName", "country.name")],
          [("tradeCount", "trades"), ("settlementCount", "settlementsWithCpty"),
           ("csaCount", "collateralAgreements")]),

    _spec(2, "BookChildCounts", "positions::Book",
          "Fan-out: per-book activity. BK-LEGACY is closed and has neither trades nor "
          "PnL rows, while still holding one position.",
          [("bookId", "bookId"), ("name", "name"), ("currency", "currency"),
           ("isActive", "isActive"), ("deskName", "desk.name"),
           ("deskRegion", "desk.region")],
          [("tradeCount", "trades"), ("positionCount", "positions"),
           ("pnlCount", "dailyPnLs")]),

    _spec(3, "DeskChildCounts", "org::Desk",
          "Fan-out: desk rollup. DSK-CREDIT has no books, no traders and no PnL — three "
          "independent zeroes on one row.",
          [("deskId", "deskId"), ("name", "name"), ("region", "region"),
           ("assetClass", "assetClass"), ("isActive", "isActive")],
          [("bookCount", "books"), ("traderCount", "traders"),
           ("pnlCount", "dailyPnLs")]),

    _spec(4, "SectorInstrumentCounts", "refdata::Sector",
          "Fan-out across a link that is itself broken elsewhere: SEC-55 has no "
          "instruments, and INST-GILT30 points at a sector id that does not exist, so it "
          "is counted under no sector at all.",
          [("sectorId", "sectorId"), ("name", "name"), ("gicsCode", "gicsCode"),
           ("isActive", "isActive")],
          [("instrumentCount", "instruments")]),

    _spec(5, "TraderChildCounts", "org::Trader",
          "Fan-out with a NULL foreign key on the child side: two trades carry no trader, "
          "so they must be counted under no trader rather than under all of them.",
          [("traderId", "traderId"), ("firstName", "firstName"),
           ("lastName", "lastName"), ("seniority", "seniority"),
           ("isActive", "isActive"), ("deskName", "desk.name")],
          [("tradeCount", "trades"), ("pnlCount", "dailyPnLs")]),

    _spec(6, "PositionGreeksCounts", "positions::Position",
          "Fan-out on the shallowest possible association, so a failure here cannot be "
          "blamed on join depth. Three of six positions have no greeks row.",
          [("positionId", "positionId"), ("direction", "direction"),
           ("currency", "currency"), ("isOpen", "isOpen"),
           ("instrumentName", "instrument.name")],
          [("greeksCount", "greeks")]),
]
