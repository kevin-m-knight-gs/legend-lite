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

from query import Pred, Proj, Spec

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


def _inline_variant(canonical: Spec) -> Spec:
    """The same query again, resolved by a mapping that reuses a ROOT set implementation
    for the counterparty via `counterparty () Inline[cptyInline]` instead of repeating its
    column bindings inline. Same answer or the reuse is not equivalent."""
    s = Spec("stress::N3_TradeInlineFlat", "/stress/n3",
             "Mapping invariance, inline side: identical query to N0 and N2, resolved by "
             "a mapping whose embedded counterparty is `Inline[cptyInline]` -- an empty "
             "property block delegating to a root set implementation over the same table.",
             canonical.root)
    s.projections = list(canonical.projections)
    s.mapping, s.runtime = "reporting::InlineFlatMapping", "stress::InlineFlatRT"
    return s


INLINE = _inline_variant(CANONICAL)


def _multi_variant(canonical: Spec) -> Spec:
    """Mapping invariance expressed INSIDE one service, via MultiExecution.

    Stronger than a pair of services: there is literally one query text, so the two runs
    cannot drift apart by someone editing one and not the other. Both tests assert the
    SAME expectation and differ only by `keys:`.
    """
    s = Spec("stress::N4_TradeMultiExecution", "/stress/n4",
             "MultiExecution: ONE query, run against the canonical mapping and the "
             "embedded flat mapping under two keys. Both must return the same rows -- "
             "the L2 invariance, asserted without a second query to keep in sync.",
             canonical.root)
    s.projections = list(canonical.projections)
    s.multi = [("canonical", "stress::AllMapping", "stress::RT"),
               ("flat", "reporting::EmbeddedFlatMapping", "stress::EmbeddedFlatRT")]
    s.multi_key = "shape"
    return s


MULTI = _multi_variant(CANONICAL)


# ------------------------------------------------------------ L4 union invariance
#
# Orthogonal to the L2 group. L2 varies how a row is ASSEMBLED (join vs denormalized);
# this varies where rows COME FROM (one table vs a union of three, one of them empty).
# The projection is restricted to the columns the partitions carry.

_UNION_COLUMNS = ["tradeId", "tradeDate", "quantity", "price", "notional", "side",
                  "status", "currency"]


def _union_pair():
    whole = Spec("stress::U0_TradeWhole", "/stress/u0",
                 "Union invariance, whole side: every trade from the single TRADE table.",
                 "trading::Trade")
    whole.projections = [Proj(a, [a]) for a in _UNION_COLUMNS]

    parts = Spec("stress::U1_TradePartitioned", "/stress/u1",
                 "Union invariance, partitioned side: the same trades reached through an "
                 "Operation union over TRADE_EQ, TRADE_RATES and the EMPTY TRADE_FX. A "
                 "union leg contributing no rows must add nothing and remove nothing.",
                 "trading::HistTrade")
    parts.projections = [Proj(a, [a]) for a in _UNION_COLUMNS]
    return whole, parts


WHOLE, PARTITIONED = _union_pair()


# ------------------------------------------------------ L4 filter invariance
#
# The predicate applied in the MAPPING versus the same predicate applied in the QUERY.
# Independent of the other two groups, and the cheapest kind of invariance to get wrong:
# a store filter that silently failed to apply would make E1 a superset of E0 and nothing
# else in the corpus would notice.

def _filter_pair():
    in_query = Spec("stress::E0_ExecutedByQuery", "/stress/e0",
                    "Filter invariance, query side: every trade, narrowed by a predicate "
                    "in the query.", "trading::Trade")
    in_query.projections = [Proj(a, [a]) for a in _UNION_COLUMNS]
    in_query.filters = [Pred(["status"], "==", "EXECUTED")]

    in_mapping = Spec("stress::E1_ExecutedByMapping", "/stress/e1",
                      "Filter invariance, mapping side: the SAME rows reached through a "
                      "class whose mapping carries ~filter [store::DB] ExecutedTrades. "
                      "The query has no predicate at all.", "trading::ExecutedTrade")
    in_mapping.projections = [Proj(a, [a]) for a in _UNION_COLUMNS]
    in_mapping.mapping, in_mapping.runtime = "trading::ExecutedMapping", "stress::ExecutedRT"
    return in_query, in_mapping


BY_QUERY, BY_MAPPING = _filter_pair()

# Each group must agree internally; groups are independent of one another.
# ------------------------------------------------------------- L5 M2M invariance
#
# The fourth invariance, and the one that crosses an execution ENGINE rather than a
# mapping shape: M0 reads trading::Trade through SQL, M1 reads canonical::CanonicalTrade
# through the M2M engine fed by that same SQL. The properties are renamed and one is
# computed in the M2M layer, so a mapping that quietly passed the source object through
# would not satisfy the projection.

# `side` is deliberately NOT here. An EnumerationMapping is not applied when the
# relational mapping feeds a ModelChainConnection -- the raw storage code arrives instead
# of the enum value (F12) -- and including it would redden this pair for a reason that has
# nothing to do with the invariance being tested. It is asserted separately by M2.
_M2M_COLUMNS = [
    ("identifier", "tradeId", "identifier"),
    ("executedOn", "tradeDate", "executedOn"),
    ("unitPrice", "price", "unitPrice"),
    ("units", "quantity", "units"),
    ("state", "status", "state"),
    ("grossValue", "grossAmount", "grossValue"),
]


def _m2m_pair():
    src = Spec("stress::M0_TradeRelational", "/stress/m0",
               "M2M invariance, source side: trading::Trade straight from SQL. "
               "grossAmount is the derived property; the M2M side computes the same "
               "product in the M2M engine instead.", "trading::Trade")
    src.projections = [Proj(a, s.split(".")) for a, s, _ in _M2M_COLUMNS]

    tgt = Spec("stress::M1_TradeCanonical", "/stress/m1",
               "M2M invariance, canonical side: the same facts through "
               "relational -> source model -> M2M -> canonical model, with every property "
               "renamed on the way.", "canonical::CanonicalTrade")
    tgt.projections = [Proj(a, t.split(".")) for a, _, t in _M2M_COLUMNS]
    tgt.mapping, tgt.runtime = "canonical::M2MMapping", "stress::CanonicalRT"
    tgt.connection = "environment"
    # The oracle cannot evaluate an M2M target: it has no table to read. The claim IS
    # that it returns what the relational side returns, so it mirrors it.
    tgt.mirrors = src
    # A Relation projection is REJECTED over a ModelChainConnection, so the canonical side
    # must use the legacy TDS form. That is F11, and it is why this pair also happens to
    # be the corpus's only coverage of the legacy paradigm end-to-end.
    tgt.paradigm = "tds"
    return src, tgt


RELATIONAL_SIDE, CANONICAL_SIDE = _m2m_pair()


# ---------------------------------------------------- L5 Otherwise / fallback
#
# One class, one table, two mappings differing ONLY in whether the embedded counterparty
# falls back to a join. Five of twenty trades have the cache populated; the other fifteen
# have only the FK.
#
#   O0  embedded only  -> counterparty present for 5, NULL for 15
#   O1  Otherwise      -> counterparty present for 19, NULL only for the one whose
#                         counterparty genuinely does not exist
#
# O1 mirrors the canonical answer, so it is a fifth invariance. O0 is asserted separately
# and MUST differ -- if the two agreed, Otherwise would be doing nothing and the test
# would be passing for the wrong reason.

_OTHERWISE_COLUMNS = [("tradeId", "tradeId"), ("notional", "notional"),
                      ("status", "status"),
                      ("cptyId", "counterparty.counterpartyId"),
                      ("cptyName", "counterparty.legalName"),
                      ("cptyLei", "counterparty.lei")]


def _otherwise_specs():
    canonical = Spec("stress::_O1MirrorSource", "/stress/_o1mirror", "", "trading::Trade")
    canonical.projections = [Proj(a, p.split(".")) for a, p in _OTHERWISE_COLUMNS]

    fallback = Spec("stress::O1_CounterpartyOtherwise", "/stress/o1",
                    "Embedded counterparty with an Otherwise fallback. Only 5 of 20 rows "
                    "carry the inline cache; the rest resolve through the join, and the "
                    "result must equal what the canonical model returns.",
                    "trading::Trade")
    fallback.projections = [Proj(a, p.split(".")) for a, p in _OTHERWISE_COLUMNS]
    fallback.mapping, fallback.runtime = "reporting::OtherwiseMapping", "stress::OtherwiseRT"
    fallback.mirrors = canonical
    return fallback


OTHERWISE = _otherwise_specs()


def _confluence_spec():
    """Otherwise AND scope on one class mapping -- see 82-confluence.pure.

    The two constructs had never executed together, because each lived alone in its own
    mapping and 148 of the stacking scoreboard's pairs are exactly that situation: two
    mapping-level constructs whose mappings never meet. Closing one of them needs a model
    change, not a query, which is what the confluence file is.

    Mirrors the canonical Trade projection for the same reason O1 does: the claim is that a
    trade read through a scope block with an Otherwise fallback equals the trade read
    plainly, and deriving a second expectation would test the deriving rather than the claim.
    """
    canonical = Spec("stress::_ConfMirrorSource", "/stress/_confmirror", "", "trading::Trade")
    canonical.projections = [Proj(a, p.split(".")) for a, p in _OTHERWISE_COLUMNS]

    s = Spec("stress::CF_Confluence", "/stress/cf",
             "Otherwise and scope on one class mapping: the embedded counterparty resolves "
             "through its fallback join while notional and status come through a scope "
             "block. Neither construct is new; their meeting is.",
             "conf::ConfTrade")
    s.projections = [Proj(a, p.split(".")) for a, p in _OTHERWISE_COLUMNS]
    s.mapping, s.runtime = "conf::Confluence", "conf::ConfluenceRT"
    s.mirrors = canonical
    return s


CONFLUENCE = _confluence_spec()


def _fixed_income_specs():
    """Queries a fixed-income desk actually runs, over the coupon schedule.

    Written by hand rather than generated because the point is that they are the REAL
    questions: what is scheduled, what has been paid, what is still owed, and which
    securities have holes in their payment history. A generated projection of five columns
    off DebtSecurity would exercise the same joins and ask nothing.
    """
    out = []

    # The securities master, with the derived annual coupon a desk quotes rather than the
    # rate, and the same figure converted at a supplied rate. The perpetual's maturityDate
    # is null and has to survive the projection.
    terms = Spec("stress::FI0_SecurityTerms", "/stress/fi0",
                 "Debt securities with their terms, the derived annual coupon, and that "
                 "coupon converted at a caller-supplied FX rate. One security is perpetual, "
                 "so maturityDate is null for it.",
                 "fixedincome::DebtSecurity")
    terms.projections = [Proj("securityId", ["securityId"]),
                         Proj("description", ["description"]),
                         Proj("issuerName", ["issuerName"]),
                         Proj("currency", ["currency"]),
                         Proj("couponRate", ["couponRate"]),
                         Proj("maturityDate", ["maturityDate"]),
                         Proj("annualCoupon", ["annualCoupon"]),
                         Proj("annualCouponGbp", ["annualCouponIn"], args=[0.79])]
    terms.sort = ("securityId", False)
    out.append(terms)

    # The schedule itself, deepest-fanning join in the corpus: 130 periods over 6 securities.
    # Sorted by security then period so the order is the schedule's own.
    sched = Spec("stress::FI1_CouponSchedule", "/stress/fi1",
                 "The accrual schedule: every coupon period with its accrual window, day "
                 "count and amount, and the payment against it where one exists. 130 rows "
                 "over 6 securities -- the deepest fan-out in the corpus -- and 87 of them "
                 "have no payment because the coupon is not due yet.",
                 "fixedincome::CouponPeriod")
    sched.projections = [Proj("periodId", ["periodId"]),
                         Proj("securityId", ["securityId"]),
                         Proj("periodNumber", ["periodNumber"]),
                         Proj("accrualStart", ["accrualStartDate"]),
                         Proj("accrualEnd", ["accrualEndDate"]),
                         Proj("accrualDays", ["accrualDays"]),
                         Proj("couponAmount", ["couponAmount"]),
                         Proj("dailyAccrual", ["dailyAccrual"]),
                         Proj("netPaid", ["payment", "netAmount"])]
    sched.sort = ("periodId", False)
    out.append(sched)

    # What a coupon actually pays after withholding, by issuer domicile. The German and
    # British issuers withhold; the US ones do not, so gross and net differ on some rows and
    # not others -- which is the case a `gross - tax = net` assertion has to see both of.
    paid = Spec("stress::FI2_CouponPayments", "/stress/fi2",
                "Coupons actually paid, with withholding. Withholding is a function of the "
                "issuer's domicile, so gross and net differ for the German and British "
                "issuers and agree for the US ones.",
                "fixedincome::CouponPayment")
    paid.projections = [Proj("paymentId", ["paymentId"]),
                        Proj("paymentDate", ["paymentDate"]),
                        Proj("grossAmount", ["grossAmount"]),
                        Proj("withholdingTax", ["withholdingTax"]),
                        Proj("netAmount", ["netAmount"]),
                        Proj("currency", ["currency"]),
                        Proj("issuer", ["period", "security", "issuerName"])]
    paid.sort = ("paymentId", False)
    out.append(paid)

    # Per-security totals. groupBy over a 60-deep fan-out, which is where an aggregate that
    # double-counts a join shows up and a 3-row fan-out hides it.
    totals = Spec("stress::FI3_ScheduleTotals", "/stress/fi3",
                  "Scheduled coupon totals per security: how many periods, how much is "
                  "scheduled in total. Aggregated over a fan-out sixty deep at its widest, "
                  "which is where an aggregate that double-counts its join stops hiding.",
                  "fixedincome::CouponPeriod")
    totals.projections = [Proj("securityId", ["securityId"]),
                          Proj("couponAmount", ["couponAmount"])]
    totals.group_by = ["securityId"]
    totals.aggs = [("periodCount", "couponAmount", "count"),
                   ("totalScheduled", "couponAmount", "sum"),
                   ("largestCoupon", "couponAmount", "max")]
    totals.sort = ("securityId", False)
    out.append(totals)

    # Which periods have no payment. isEmpty over a zero-or-one end, where the empty half is
    # the majority rather than an edge case.
    unpaid = Spec("stress::FI4_UnpaidPeriods", "/stress/fi4",
                  "Coupon periods with no payment against them. The empty half of this "
                  "association is 87 of 130 rows, so emptiness is the common case here "
                  "rather than the exception it is everywhere else in the corpus.",
                  "fixedincome::CouponPeriod")
    unpaid.projections = [Proj("periodId", ["periodId"]),
                          Proj("paymentDate", ["paymentDate"]),
                          Proj("isPaid", ["isPaid"]),
                          Proj("awaitingPayment", ["payment"], agg="isEmpty")]
    unpaid.sort = ("periodId", False)
    out.append(unpaid)
    return out


FIXED_INCOME = _fixed_income_specs()


def _otc_specs():
    """The questions a derivatives middle office actually asks.

    Not projections chosen to exercise a join -- the trade book, the leg detail, the risk
    roll-up by asset class, and the compliance query that looks for uncollateralised trades
    with no master agreement. Each is a real report, and each happens to stack several
    constructs because real reports do.
    """
    out = []

    book = Spec("stress::OT0_TradeBook", "/stress/ot0",
                "The OTC book: every trade with its tenor and its notional converted at a "
                "supplied rate. clearingHouse and masterAgreement are null for the "
                "bilateral trades, which is most of them.",
                "derivatives::OtcTrade")
    book.projections = [Proj("otcId", ["otcId"]),
                        Proj("productType", ["productType"]),
                        Proj("assetClass", ["assetClass"]),
                        Proj("notional", ["notional"]),
                        Proj("currency", ["currency"]),
                        Proj("clearingHouse", ["clearingHouse"]),
                        Proj("masterAgreement", ["masterAgreement"]),
                        Proj("tenorYears", ["tenorYears"]),
                        Proj("notionalGbp", ["notionalIn"], args=[0.79])]
    book.sort = ("otcId", False)
    out.append(book)

    # Leg detail, navigating back up to the parent trade. A basis swap has a floating leg on
    # both sides, so fixedRate is null on both -- the row that breaks a "one leg is fixed"
    # assumption.
    legs = Spec("stress::OT1_SwapLegs", "/stress/ot1",
                "Swap legs with their parent's product type. Fixed legs carry a rate and "
                "no index; floating legs the reverse; a basis swap has floating on BOTH "
                "sides, so neither of its legs has a fixed rate.",
                "derivatives::SwapLeg")
    legs.projections = [Proj("legId", ["legId"]),
                        Proj("legNumber", ["legNumber"]),
                        Proj("legType", ["legType"]),
                        Proj("payReceive", ["payReceive"]),
                        Proj("fixedRate", ["fixedRate"]),
                        Proj("floatingIndex", ["floatingIndex"]),
                        Proj("spreadBp", ["spreadBp"]),
                        Proj("dayCount", ["dayCount"]),
                        Proj("productType", ["trade", "productType"]),
                        Proj("counterpartyId", ["trade", "counterpartyId"])]
    legs.sort = ("legId", False)
    out.append(legs)

    # Risk roll-up. Notional by asset class is the first slide of every derivatives risk
    # pack, and it aggregates over a discriminated taxonomy rather than a flat table.
    risk = Spec("stress::OT2_NotionalByAssetClass", "/stress/ot2",
                "Notional by asset class and clearing status: the roll-up a derivatives "
                "risk pack opens with.",
                "derivatives::OtcTrade")
    risk.projections = [Proj("assetClass", ["assetClass"]),
                        Proj("clearingStatus", ["clearingStatus"]),
                        Proj("notional", ["notional"])]
    risk.group_by = ["assetClass", "clearingStatus"]
    risk.aggs = [("tradeCount", "notional", "count"),
                 ("totalNotional", "notional", "sum"),
                 ("largestTrade", "notional", "max")]
    risk.sort = ("assetClass", False)
    out.append(risk)

    # The subtype query. This is what the thirteen discriminator filters are FOR: asking for
    # swaps and getting swaps. If a ~filter is wrong the set returns the whole table, and the
    # only way to notice is to ask a subclass for its own rows and count them.
    irs = Spec("stress::OT3_InterestRateSwaps", "/stress/ot3",
               "Interest rate swaps only, with the DV01 a rates desk sizes them by. The "
               "discriminator is the point: if the ~filter were wrong this would return all "
               "thirteen OTC trades rather than the one that is an IRS.",
               "derivatives::InterestRateSwap")
    irs.projections = [Proj("otcId", ["otcId"]),
                       Proj("productType", ["productType"]),
                       Proj("notional", ["notional"]),
                       Proj("tenorYears", ["tenorYears"]),
                       Proj("dv01", ["dv01"])]
    irs.sort = ("otcId", False)
    out.append(irs)

    # Credit, where the subtype's own risk measure is a different formula entirely.
    cds = Spec("stress::OT4_CreditProtection", "/stress/ot4",
               "Single-name CDS with loss-given-default at the standard 40% recovery. A "
               "different subtype, a different risk measure, the same base table.",
               "derivatives::CreditDefaultSwap")
    cds.projections = [Proj("otcId", ["otcId"]),
                       Proj("counterpartyId", ["counterpartyId"]),
                       Proj("notional", ["notional"]),
                       Proj("lossGivenDefault", ["lossGivenDefault"])]
    cds.sort = ("otcId", False)
    out.append(cds)

    # Compliance: uncollateralised trades, and which of them have no master agreement. A real
    # query, and it lands on the row seeded with three genuinely absent optional columns.
    compliance = Spec("stress::OT5_UncollateralisedTrades", "/stress/ot5",
                      "Uncollateralised OTC trades and whether they have option terms. The "
                      "commodity swap has no clearing house and no master agreement -- three "
                      "optional columns genuinely absent on one row.",
                      "derivatives::OtcTrade")
    compliance.projections = [Proj("otcId", ["otcId"]),
                              Proj("productType", ["productType"]),
                              Proj("collateralised", ["collateralised"]),
                              Proj("masterAgreement", ["masterAgreement"]),
                              Proj("noOptionTerms", ["optionTerms"], agg="isEmpty")]
    # `==`, not `=`. The corpus's predicate vocabulary is Pure's, and `=` is not in it.
    compliance.filters = [Pred(["collateralised"], "==", False)]
    compliance.sort = ("otcId", False)
    out.append(compliance)
    return out


OTC = _otc_specs()


def _m2m_enum_probe():
    """The same chain plus the enum-mapped property. Quarantined: F12."""
    s = Spec("stress::M2_CanonicalWithEnum", "/stress/m2",
             "The M2M chain projecting the enum-mapped `side`. The relational mapping "
             "translates 'B' to BUY; through a ModelChainConnection the raw code arrives "
             "instead. Isolated from M1 so one defect does not mask an unrelated "
             "invariance.", "canonical::CanonicalTrade")
    s.projections = [Proj("identifier", ["identifier"]), Proj("side", ["side"])]
    s.mapping, s.runtime = "canonical::M2MMapping", "stress::CanonicalRT"
    s.connection, s.paradigm = "environment", "tds"
    mirror = Spec("stress::_M2MirrorSource", "/stress/_m2mirror", "", "trading::Trade")
    mirror.projections = [Proj("identifier", ["tradeId"]), Proj("side", ["side"])]
    s.mirrors = mirror
    return s


CANONICAL_WITH_ENUM = _m2m_enum_probe()


INVARIANCE_GROUPS = [[CANONICAL, FLAT, EMBEDDED, INLINE, MULTI], [WHOLE, PARTITIONED],
                     [BY_QUERY, BY_MAPPING], [RELATIONAL_SIDE, CANONICAL_SIDE]]
INVARIANCE = [s for g in INVARIANCE_GROUPS for s in g]


# ---------------------------------------------------------------- L3 temporal
#
# The same question asked at four business dates. T1 and T2 are one day apart and
# straddle CP-0003's version boundary, which is the assertion that matters: the interval
# is [FROM, THRU), so 2024-06-06 must return the OLD rating and 2024-06-07 the NEW one.
# An implementation that treated THRU as inclusive returns the old rating on the day the
# new one took effect — a wrong answer that reads as entirely reasonable.

def _temporal(n: int, name: str, as_of: str, doc: str) -> Spec:
    s = Spec(f"{PREFIX}{n}_{name}", f"/stress/t{n}", doc, "counterparty::RatingVersion")
    s.as_of = as_of
    s.projections = [Proj(a, [a]) for a in
                     ("counterpartyId", "rating", "agency", "outlook",
                      "isInvestmentGrade")]
    return s


TEMPORAL = [
    _temporal(10, "RatingsAsOf2019", "2019-01-01",
              "Before CP-0003 and CP-0004 were onboarded: only the two oldest "
              "counterparties have a rating in force."),
    _temporal(11, "RatingsAsOf2021", "2021-01-01",
              "CP-0004 still rated (its rating is withdrawn in 2022) and CP-0001 is in "
              "its middle version — a date where the answer differs from BOTH the "
              "earliest and the current state."),
    _temporal(12, "RatingsDayBeforeChange", "2024-06-06",
              "The day BEFORE CP-0003 crosses out of investment grade. Must return BBB."),
    _temporal(13, "RatingsOnChangeDate", "2024-06-07",
              "The day the new version takes effect. [FROM, THRU) is start-inclusive and "
              "end-EXCLUSIVE, so this must return BB+, not BBB. One day apart from T12 "
              "and the only difference between them is the boundary rule."),
    _temporal(14, "RatingsLatest", "latest",
              "%latest — the rows whose THRU is the infinity date. CP-0004's rating was "
              "withdrawn without a successor, so it must NOT appear; CP-0005 has no "
              "history at all and never appears."),
]


# ------------------------------------------------------------- L4 self-join
SELF_JOIN = [
    _spec(21, "TraderManagerChain", "org::Trader",
          "A {target} self-join walked two levels. TRD-001 and TRD-003 report to nobody "
          "(absent key), TRD-004 reports to a trader who has left (dangling key), and the "
          "two-level hop reaches a manager's manager. All three must come back as rows "
          "with NULLs where the chain stops -- never a dropped row and never a loop.",
          [("traderId", "traderId"), ("lastName", "lastName"),
           ("mgrId", "manager.traderId"), ("mgrLast", "manager.lastName"),
           ("mgrDesk", "manager.desk.name"),
           ("grandMgrId", "manager.manager.traderId"),
           ("grandMgrLast", "manager.manager.lastName")],
          []),
]


# ---------------------------------------------------------- L3b bitemporal
#
# The same BUSINESS date asked at two PROCESSING dates. B0 and B1 differ only in when the
# question was asked, and they must give different answers -- that is the entire content
# of bitemporality, and a store that collapses the two dimensions cannot produce it.

def _bitemporal(n: int, name: str, processing: str, business: str, doc: str) -> Spec:
    """Argument order is all(PROCESSING, BUSINESS) — engine order, not the order the
    concepts are usually said in."""
    s = Spec(f"stress::B{n}_{name}", f"/stress/b{n}", doc, "products::InstrumentRating")
    s.as_of = [processing, business]
    s.projections = [Proj(a, [a]) for a in ("instrumentId", "creditRating", "source")]
    return s


BITEMPORAL = [
    _bitemporal(0, "RatingAsBelievedThen", "2024-02-01", "2024-02-01",
                "Business 2024-02-01 as believed on 2024-02-01: INST-HSBA is 'A'. This is "
                "what a report run that month would have said."),
    _bitemporal(1, "RatingAsBelievedNow", "2024-04-01", "2024-02-01",
                "The SAME business date, asked after the correction landed: 'A-'. Neither "
                "answer is wrong; a single-temporal store cannot hold both."),
    _bitemporal(2, "RatingBeforeAnyBelief", "2024-01-05", "2024-02-01",
                "The same business date asked BEFORE anything was recorded about it. The "
                "processing dimension excludes every row, so INST-HSBA is absent "
                "entirely -- not null, absent."),
]


# ------------------------------------------------------------- graph fetch
#
# A completely different execution path: the result is a TREE, not a TDS. The earlier
# audit of legend-engine found two defects here, and the corpus now contains exactly the
# constructs they involve -- so these three are aimed rather than exploratory.
#
#   G0  to-one nesting with an orphan: the whole sub-object must be null, not an object
#       full of nulls
#   G1  graph fetch over the UNION-mapped class (audit finding: cast exception)
#   G2  graph fetch over a milestoned class at %latest (audit finding: parser NPE)

def _graph_spec(n: int, name: str, root: str, doc: str, tree: dict,
                as_of: str | None = None) -> Spec:
    s = Spec(f"stress::G{n}_{name}", f"/stress/g{n}", doc, root)
    s.graph, s.as_of = tree, as_of
    return s


GRAPH = [
    _graph_spec(0, "TradeTree", "trading::Trade",
                "Nested to-one navigation. TRD-0007's counterparty does not exist, so the "
                "whole counterparty sub-object must be null -- the tree-shaped version of "
                "a distinction a flat projection cannot make.",
                {"tradeId": None, "status": None, "notional": None,
                 "instrument": {"instrumentId": None, "name": None, "ticker": None},
                 "counterparty": {"counterpartyId": None, "legalName": None,
                                  "lei": None}}),

    _graph_spec(1, "UnionTree", "trading::HistTrade",
                "Graph fetch over a class mapped by an Operation UNION, including an "
                "empty leg. Deliberately projects NO enum -- see G3, which does, and "
                "fails for an unrelated reason that would otherwise mask this one.",
                {"tradeId": None, "notional": None, "status": None}),

    _graph_spec(3, "UnionTreeWithEnum", "trading::HistTrade",
                "The same tree plus the enum-mapped `side`. TRD-0012 carries source code "
                "'ZZ', which has no EnumerationMapping entry. A TDS projection returns "
                "NULL for it (F-A14); graph fetch RAISES. Same data, same mapping, two "
                "execution paths, two semantics -- see F10.",
                {"tradeId": None, "side": None, "notional": None, "status": None}),

    _graph_spec(2, "MilestonedTree", "counterparty::RatingVersion",
                "Graph fetch over a business-temporal class at %latest. An earlier audit "
                "reported a parser NPE on %latest in a graph-fetch tree.",
                {"counterpartyId": None, "rating": None, "outlook": None}, "latest"),
]


# ================================================================ STACK LAYER
#
# The corpus is otherwise a set of narrow probes: median 2 features per service, exactly
# one service above 4, none above 6. That is deliberate for ATTRIBUTION -- F10, F12 and
# F13 are each reportable precisely because they were isolated -- but a defect that only
# appears when eight features interact is invisible to a probe by construction.
#
# So the corpus needs both layers, and this is the second one. These services stack as
# many CURRENTLY-PASSING features as one query can legitimately carry. They deliberately
# exclude anything already quarantined: a stack containing a known defect is red for a
# reason nobody has to look for, and would mask every interaction it was built to find.

STACK = [
    _spec(30, "TradeEverything", "trading::Trade",
          "The deep stack: enum-mapped side, a derived property, a QUALIFIED property "
          "with two different arguments, three independent 3-hop navigation chains, a "
          "two-condition filter, a sort and a limit -- one query. Each of these passes "
          "alone elsewhere in the corpus; this asserts they still hold together.",
          [("tradeId", "tradeId"),
           ("side", "side"),                                  # enum
           ("status", "status"),
           ("quantity", "quantity"), ("price", "price"),
           # three separate 3-hop chains through different domains
           ("sectorName", "instrument.sector.name"),
           ("sectorGics", "instrument.sector.gicsCode"),
           ("deskName", "book.desk.name"),
           ("deskRegion", "book.desk.region"),
           ("cptyCountry", "counterparty.country.name"),
           ("cptyRegion", "counterparty.country.region"),
           ("traderDesk", "trader.desk.name"),
           # derived
           ("grossAmount", "grossAmount")],
          [],
          # qualified, twice with different arguments
          [("grossGbp", "grossAmountIn", [0.79]),
           ("grossEur", "grossAmountIn", [0.92])]),

    _spec(32, "TradeRollupEverything", "trading::Trade",
          "The widest stack in the corpus: a two-condition filter, a 3-hop navigation, an "
          "enum-mapped column and a FUNCTION result used as GROUP KEYS, a derived "
          "property used as an aggregate SOURCE, count and sum, a sort and a limit. Ten "
          "features in one query, and the aggregates must decompose to the ungrouped "
          "totals.",
          [("bookName", "book.name"), ("deskRegion", "book.desk.region"),
           ("side", "side"), ("notional", "notional"), ("gross", "grossAmount")],
          []),

    _spec(31, "TradeRules", "trading::Trade",
          "Standalone FUNCTIONS called extension-style, alongside the columns they are "
          "computed from. isBuy reads an ENUM inside a function body -- a different path "
          "from projecting one, which matters given F10/F12/F13 -- and "
          "counterpartyRegion NAVIGATES inside the body, so TRD-0007's orphan must yield "
          "null rather than an error.",
          [("tradeId", "tradeId"), ("side", "side"), ("quantity", "quantity"),
           ("price", "price"), ("cptyRegionDirect", "counterparty.country.region")],
          []),
]

# F32: a function result as a GROUP KEY -- a boolean partition of the trades computed by
# a shared rule rather than read from a column.
_F32 = next(s for s in STACK if s.short.startswith("F32_"))
_F32.projections.append(Proj("isLarge", [], None, [], "stress::rules::isLargeTrade"))
_F32.filters = [Pred(["status"], "==", "EXECUTED"), Pred(["quantity"], ">", 500.0)]
_F32.group_by = ["bookName", "deskRegion", "side", "isLarge"]
_F32.aggs = [("tradeCount", "notional", "count"), ("totalGross", "gross", "sum"),
             ("maxNotional", "notional", "max")]
_F32.sort = ("bookName", False)
_F32.limit = 20

# Functions are a distinct projection kind, so they are attached after construction.
# Referenced BY NAME, not by index: inserting F32 into the list silently moved STACK[1]
# from F31 to F32 and the function projections landed on the wrong service.
_by_name = {s.short.split("_")[0]: s for s in STACK}
_by_name["F31"].projections += [
    Proj("isLarge", [], None, [], "stress::rules::isLargeTrade"),
    Proj("isBuy", [], None, [], "stress::rules::isBuy"),
    Proj("over1m", [], None, [1000000.0], "stress::rules::exceeds"),
    Proj("over100k", [], None, [100000.0], "stress::rules::exceeds"),
    Proj("cptyRegionViaFn", [], None, [], "stress::rules::counterpartyRegion"),
]
# One function call on the deep stack too, so it is exercised in combination rather than
# only in isolation.
_by_name["F30"].projections.append(
    Proj("isLarge", [], None, [], "stress::rules::isLargeTrade"))
STACK[0].filters = [Pred(["status"], "==", "EXECUTED"),
                    Pred(["quantity"], ">", 500.0)]
STACK[0].sort = ("tradeId", False)
STACK[0].limit = 12


# ------------------------------------------------------------ L5 XStore
#
# A navigation that crosses a STORE boundary. No Join can express it -- a Join lives
# inside one Database -- so the link is a predicate over a local property:
#   legalEntity: $this.entityRef == $that.entityId
#
# The seed makes all three outcomes reachable (A18): trades whose entity exists, a trade
# whose counterparty is absent from the external master, and an entity no trade matches.
# The registered names differ from the local legal names, so a query that accidentally
# read the LOCAL counterparty would return a different string rather than the same one.

def _xstore():
    """XStore navigation works under GRAPH FETCH and fails under a projection (F15), so
    the corpus covers it on the path that supports it and quarantines the other."""
    graph = Spec("stress::X0_TradeExternalEntity", "/stress/x0",
                 "Cross-store navigation by graph fetch: trading::Trade in store::DB "
                 "reaching external::LegalEntity in external::EntityDB through an XStore "
                 "association. Two stores, two connections, two ###Data elements.",
                 "trading::Trade")
    graph.graph = {"tradeId": None, "notional": None, "status": None,
                   "legalEntity": {"registeredName": None, "jurisdiction": None,
                                   "isSanctioned": None}}
    graph.mapping, graph.runtime = "external::EntityMapping", "stress::XStoreRT"
    graph.extra_data = [("entities", "external::EntityData")]

    proj = Spec("stress::X1_ExternalEntityProjection", "/stress/x1",
                "The SAME cross-store navigation as a projection. Fails with an internal "
                "Pure match failure -- the relational SQL generator has no branch for "
                "XStorePropertyMapping. Quarantined: F15.", "trading::Trade")
    proj.projections = [Proj("tradeId", ["tradeId"]),
                        Proj("entityName", ["legalEntity", "registeredName"])]
    proj.mapping, proj.runtime = "external::EntityMapping", "stress::XStoreRT"
    proj.extra_data = [("entities", "external::EntityData")]
    return graph, proj


XSTORE, XSTORE_PROJECTION = _xstore()


# --------------------------------------------------------------- L5 ModelJoin
#
# The same navigation as the canonical model -- trade to its book -- but the relationship
# is a PREDICATE between two Relation-mapped classes rather than a Join in the store.
# Unlike XStore it names both sides as explicit lambda parameters. Same answer, or the
# ModelJoin does not mean what the association means.

def _modeljoin():
    s = Spec("stress::J0_TradeBookModelJoin", "/stress/j0",
             "trading::Trade to positions::Book through a ModelJoin over two Relation "
             "class mappings, instead of the store Join the canonical model uses. The "
             "answer must not change.", "trading::Trade")
    s.projections = [Proj("tradeId", ["tradeId"]), Proj("notional", ["notional"]),
                     Proj("status", ["status"]),
                     Proj("bookName", ["book", "name"]),
                     Proj("bookCurrency", ["book", "currency"])]
    s.mapping, s.runtime = "modeljoin::JoinMapping", "stress::ModelJoinRT"
    src = Spec("stress::_J0Mirror", "/stress/_j0m", "", "trading::Trade")
    src.projections = list(s.projections)
    s.mirrors = src
    return s


MODELJOIN = _modeljoin()


# ---------------------------------------------------------------- L6 Measure
#
# A unit-typed property. It serialises as an OBJECT carrying its unit, never as a bare
# number, which is the entire point of having Measures rather than a Float and a
# convention. The expectation is still DERIVED from the seed — the notional comes from the
# oracle and the unit envelope is wrapped around it — rather than captured from output.

def _unit_envelope(unit_id: str):
    def wrap(row):
        return {"identifier": row["identifier"],
                "amount": {"unit": [{"unitId": unit_id, "exponentValue": 1}],
                           "value": row["amount"]}}
    return wrap


def _measure():
    s = Spec("stress::MU0_MonetaryTrade", "/stress/mu0",
             "A Measure-typed amount through the M2M layer. Units cannot be mapped "
             "relationally at all -- legend-engine has no Unit handling in the relational "
             "compiler -- so a monetary amount that carries its unit has to be composed "
             "in an M2M mapping with newUnit(...)->cast(...).",
             "canonical::MonetaryTrade")
    s.graph = {"identifier": None, "amount": None}
    s.mapping, s.runtime = "canonical::MoneyMapping", "stress::MoneyRT"
    s.connection = "environment"
    src = Spec("stress::_MU0Mirror", "/stress/_mu0m", "", "trading::Trade")
    src.projections = [Proj("identifier", ["tradeId"]), Proj("amount", ["notional"])]
    s.mirrors = src
    s.transform = _unit_envelope("stress::Money~USD")
    return s


MEASURE = _measure()


# ------------------------------------------------- L5 AggregationAware
#
# ONE class, TWO query shapes, and the engine picks the table. A0 is a row-level
# projection, which must read the detail; A1 is a groupBy whose keys and aggregates match
# the declared view, which should read the pre-aggregated table.
#
# Both must return correct answers -- and because the aggregate is DERIVED from the
# detail, both do so whether or not the rewrite fires. The result cannot distinguish them.
# That is stated rather than papered over: what the pair asserts is that an
# AggregationAware mapping is CORRECT under both shapes, not that the rewrite happened.
# Which table was read is a question for the generated SQL.

def _aggregation_aware():
    detail = Spec("stress::A0_FactDetail", "/stress/a0",
                  "Row-level projection over an AggregationAware class: must read the "
                  "DETAIL table, one row per trade.", "reporting::TradeFact")
    detail.projections = [Proj("bookId", ["bookId"]), Proj("notional", ["notional"])]
    detail.mapping, detail.runtime = ("reporting::AggregationAwareMapping",
                                      "stress::AggregationRT")

    rolled = Spec("stress::A1_FactRollup", "/stress/a1",
                  "A groupBy matching the declared view exactly. Legacy TDS form on "
                  "purpose: the AggregationAware rewrite dispatches only on that "
                  "signature, so the same query written with ~[...] would silently read "
                  "the detail table instead.", "reporting::TradeFact")
    rolled.projections = [Proj("bookId", ["bookId"]), Proj("notional", ["notional"])]
    rolled.mapping, rolled.runtime = ("reporting::AggregationAwareMapping",
                                      "stress::AggregationRT")
    rolled.paradigm = "tds"
    rolled.group_by = ["bookId"]
    rolled.aggs = [("totalNotional", "notional", "sum")]
    # The oracle does not model AggregationAwareMapping, so both mirror the equivalent
    # query on trading::Trade -- which IS the equivalence the mapping asserts.
    d_src = Spec("stress::_A0Mirror", "/stress/_a0m", "", "trading::Trade")
    d_src.projections = [Proj("bookId", ["book", "bookId"]), Proj("notional", ["notional"])]
    detail.mirrors = d_src

    r_src = Spec("stress::_A1Mirror", "/stress/_a1m", "", "trading::Trade")
    r_src.projections = [Proj("bookId", ["book", "bookId"]), Proj("notional", ["notional"])]
    r_src.group_by = ["bookId"]
    r_src.aggs = [("totalNotional", "notional", "sum")]
    rolled.mirrors = r_src
    return detail, rolled


FACT_DETAIL, FACT_ROLLUP = _aggregation_aware()
AGGREGATION = [FACT_DETAIL, FACT_ROLLUP]


# ---------------------------------------------------------------- L4 rollup
ROLLUP = [
    _spec(20, "NotionalByBook", "reporting::BookRollup",
          "The rollup View: notional by book, grouped by the engine. Its rows are GROUPS, "
          "so BK-LEGACY -- which has no trades -- produces no group and is ABSENT. "
          "Contrast F2_BookChildCounts, which outer-joins from BOOK and returns a row for "
          "it. Two defensible answers to one question, differing on exactly the empty "
          "case, both asserted here.",
          [("bookId", "bookId"), ("tradeCount", "tradeCount"),
           ("totalNotional", "totalNotional"), ("maxNotional", "maxNotional")],
          []),
]


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

SPECS = (STACK + INVARIANCE + AGGREGATION
         + [XSTORE, XSTORE_PROJECTION, MODELJOIN, MEASURE,
            CANONICAL_WITH_ENUM, OTHERWISE, CONFLUENCE]) + FIXED_INCOME + OTC + TEMPORAL + BITEMPORAL + GRAPH + ROLLUP + SELF_JOIN + DERIVED + [
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
