"""Brokerage tiers and clearing routes: an inequality join and an `or` join on trading::Trade.

`join non-equality` and `join with or` were the two most isolated constructs left -- 20 and 21
uncovered pairs each -- because both existed only on curve pillars, a class that carries a
composite key and little else. Hanging them off `trading::Trade` pairs them with everything
that class touches, which is most of the corpus: an enum transformer, an Otherwise mapping,
an Inline mapping, an embedded mapping, an XStore link, a Pure/M2M source, and the deepest
join chains in the model.

Both are real.

A BROKERAGE SCHEDULE is tiered: the rate depends on which notional band the trade falls in,
and the join to it is a RANGE -- `notional >= min and notional < max`. There is no key to
join on and there could not be, because the tier is a property of the amount rather than of
the trade.

A CLEARING ROUTE is matched on either axis: some products clear the same way wherever they
trade, some venues clear everything the same way, and a routing table holds both kinds of
rule. Matching one row on `venue OR product` is what the table is for.
"""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent / "exec"
STRESS = ROOT / "core/src/test/resources/stress"
SCRIPTS = ROOT / "scripts/corpus"

# Bands in USD. The corpus's twenty trades run from 18,975 to 4,912,500, so every band is
# occupied and the top one has a single trade in it -- which is the band a range join gets
# wrong first, because it is the one with an open upper edge.
TIERS = [
    ("TIER-RETAIL", "Retail", 0.0, 100000.0, 12.5, 25.00),
    ("TIER-SMALL", "Small Institutional", 100000.0, 500000.0, 8.0, 40.00),
    ("TIER-MID", "Mid Institutional", 500000.0, 2000000.0, 5.5, 150.00),
    ("TIER-LARGE", "Large Block", 2000000.0, 100000000.0, 3.25, 500.00),
]

# A route matches on a venue OR on a product, never on both: the venue rules carry no
# product and the product rules carry no venue. That is what makes the `or` a real
# disjunction rather than a pair of conditions that happen to agree.
ROUTES = [
    ("RTE-XNYS", "XNYS", None, "DTCC", "T+1", True),
    ("RTE-XNAS", "XNAS", None, "DTCC", "T+1", True),
    ("RTE-XLON", "XLON", None, "CREST", "T+2", True),
    ("RTE-BATS", "BATS", None, "DTCC", "T+1", False),
    ("RTE-IEXG", "IEXG", None, "DTCC", "T+1", True),
    # The product rules. AGENCY trades clear the same way wherever they execute, so the
    # lower-case 'xnas' trade -- which matches no venue rule, the codes being case
    # sensitive -- reaches its route through the product instead.
    ("RTE-AGENCY", None, "AGENCY", "DTCC", "T+1", True),
    ("RTE-PRINCIPAL", None, "PRINCIPAL", "OCC", "T+2", False),
]

PURE = '''###Pure
// Brokerage tiers and clearing routes: two joins that are not key lookups.
//
// A brokerage schedule is TIERED. Which rate a trade pays depends on which notional band it
// falls in, and there is no key to join on -- the band is a property of the amount. The join
// is `notional >= min and notional < max`, and a range join is the shape every fee, margin,
// haircut and capital schedule in a bank actually has.
//
// A clearing route is matched on EITHER axis. Some products clear the same way wherever they
// trade and some venues clear everything the same way, so a routing table holds both kinds of
// rule and a trade matches on venue OR on product. The disjunction is the table's purpose.
//
// Both hang off trading::Trade deliberately. The two constructs previously existed only on
// curve pillars, which carry a composite key and little else; from a trade they combine with
// the enum transformer, the Otherwise and Inline mappings, the embedded mapping, the
// cross-store link and the deepest join chains in the corpus.
Class brokerage::BrokerageTier
{
   tierId: String[1];
   tierName: String[1];
   minNotional: Float[1];
   maxNotional: Float[1];
   bpsRate: Float[1];
   minimumFee: Float[1];

   // The rate as a decimal fraction rather than in basis points, which is the form a fee
   // calculation multiplies by.
   rateFraction() { $this.bpsRate / 10000.0 } : Float[1];
   // A qualified property: what this tier charges on a notional the caller supplies, before
   // the minimum is applied.
   feeOn(notional: Float[1]) { $notional * $this.bpsRate / 10000.0 } : Float[1];
}

Class brokerage::ClearingRoute
{
   routeId: String[1];
   // Exactly one of these two is set. A venue rule has no product and a product rule has no
   // venue, which is what makes the join a real disjunction.
   venueCode: String[0..1];
   productCode: String[0..1];
   clearingHouse: String[1];
   settlementCycle: String[1];
   isNetted: Boolean[1];
}

// The tier a trade's notional falls in. A RANGE join: no key, and the association is to-one
// because the bands do not overlap.
Association brokerage::TradeBrokerage
{
   tieredTrades: trading::Trade[*];
   brokerageTier: brokerage::BrokerageTier[0..1];
}

// The routes a trade clears through, matched on its venue OR on its product.
//
// TO-MANY, and that is the honest reading rather than a concession. An unqualified `or`
// cannot say "the venue rule if there is one, otherwise the product rule" -- it says "every
// rule that applies", so a trade on a venue with a rule AND a product with a rule matches
// both. Real routing tables behave exactly this way and resolve the ambiguity with a
// precedence rule the join cannot express. The trade executed on 'xnas' matches no venue
// rule at all, the codes being case sensitive, and reaches a route through its product.
Association brokerage::TradeClearing
{
   routedTrades: trading::Trade[*];
   clearingRoutes: brokerage::ClearingRoute[*];
}

###Mapping
Mapping brokerage::BrokerageMapping
(
   brokerage::BrokerageTier: Relational
   {
      ~primaryKey ( [store::DB]BROKERAGE_TIER.TIER_ID )
      ~mainTable [store::DB]BROKERAGE_TIER
      tierId: [store::DB]BROKERAGE_TIER.TIER_ID,
      tierName: [store::DB]BROKERAGE_TIER.TIER_NAME,
      minNotional: [store::DB]BROKERAGE_TIER.MIN_NOTIONAL,
      maxNotional: [store::DB]BROKERAGE_TIER.MAX_NOTIONAL,
      bpsRate: [store::DB]BROKERAGE_TIER.BPS_RATE,
      minimumFee: [store::DB]BROKERAGE_TIER.MINIMUM_FEE
   }

   brokerage::ClearingRoute: Relational
   {
      ~primaryKey ( [store::DB]CLEARING_ROUTE.ROUTE_ID )
      ~mainTable [store::DB]CLEARING_ROUTE
      routeId: [store::DB]CLEARING_ROUTE.ROUTE_ID,
      venueCode: [store::DB]CLEARING_ROUTE.VENUE_CODE,
      productCode: [store::DB]CLEARING_ROUTE.PRODUCT_CODE,
      clearingHouse: [store::DB]CLEARING_ROUTE.CLEARING_HOUSE,
      settlementCycle: [store::DB]CLEARING_ROUTE.SETTLEMENT_CYCLE,
      isNetted: [store::DB]CLEARING_ROUTE.IS_NETTED
   }

   brokerage::TradeBrokerage: Relational
   {
      AssociationMapping
      (
         brokerageTier: [store::DB]@Trade_BrokerageTier,
         tieredTrades: [store::DB]@Trade_BrokerageTier
      )
   }

   brokerage::TradeClearing: Relational
   {
      AssociationMapping
      (
         clearingRoutes: [store::DB]@Trade_ClearingRoute,
         routedTrades: [store::DB]@Trade_ClearingRoute
      )
   }
)
'''


def apply() -> None:
    p = STRESS / "30-store.pure"
    t = p.read_text()
    if "Table BROKERAGE_TIER" in t:
        raise SystemExit("brokerage already applied")

    anchor = "    // ---- Market data: series, observations, revisions ----"
    t = t.replace(anchor, """    // ---- Brokerage tiers and clearing routes ----
    Table BROKERAGE_TIER (TIER_ID VARCHAR(20) PRIMARY KEY, TIER_NAME VARCHAR(30),
                          MIN_NOTIONAL DECIMAL(18,2), MAX_NOTIONAL DECIMAL(18,2),
                          BPS_RATE DECIMAL(18,4), MINIMUM_FEE DECIMAL(18,2))

    Table CLEARING_ROUTE (ROUTE_ID VARCHAR(20) PRIMARY KEY, VENUE_CODE VARCHAR(10),
                          PRODUCT_CODE VARCHAR(20), CLEARING_HOUSE VARCHAR(20),
                          SETTLEMENT_CYCLE VARCHAR(6), IS_NETTED BIT)

""" + anchor, 1)
    t = t.replace("    Join Curve_Benchmark(",
                  "    // A RANGE join: which band the notional falls in. No key, and none possible.\n"
                  "    Join Trade_BrokerageTier(TRADE.NOTIONAL >= BROKERAGE_TIER.MIN_NOTIONAL and TRADE.NOTIONAL < BROKERAGE_TIER.MAX_NOTIONAL)\n"
                  "    // Matched on venue OR on product: a route rule carries one or the other.\n"
                  "    Join Trade_ClearingRoute(TRADE.EXECUTION_VENUE = CLEARING_ROUTE.VENUE_CODE or TRADE.TRADE_TYPE = CLEARING_ROUTE.PRODUCT_CODE)\n"
                  "    Join Curve_Benchmark(", 1)
    p.write_text(t)

    p = SCRIPTS / "seed.py"
    t = p.read_text()
    tiers = "\n".join(
        f'    dict(TIER_ID="{i}", TIER_NAME="{n}", MIN_NOTIONAL={lo}, MAX_NOTIONAL={hi},\n'
        f'         BPS_RATE={bps}, MINIMUM_FEE={fee}),'
        for i, n, lo, hi, bps, fee in TIERS)
    routes = "\n".join(
        f'    dict(ROUTE_ID="{r}", VENUE_CODE={_q(v)}, PRODUCT_CODE={_q(p_)},\n'
        f'         CLEARING_HOUSE="{ch}", SETTLEMENT_CYCLE="{sc}", IS_NETTED={net}),'
        for r, v, p_, ch, sc, net in ROUTES)
    t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                  f"\n\n# Tiered brokerage, and clearing routes matched on venue or product.\n"
                  f"BROKERAGE_TIER = [\n{tiers}\n]\n\n"
                  f"CLEARING_ROUTE = [\n{routes}\n]\n"
                  "\n\nTABLES: dict[str, list[dict]] = {", 1)
    t = t.replace("TABLES: dict[str, list[dict]] = {",
                  'TABLES: dict[str, list[dict]] = {\n'
                  '    "BROKERAGE_TIER": BROKERAGE_TIER,\n'
                  '    "CLEARING_ROUTE": CLEARING_ROUTE,', 1)
    p.write_text(t)

    (STRESS / "836-brokerage.pure").write_text(PURE)

    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if "include brokerage::BrokerageMapping" not in t:
        t = t.replace("    include backoffice::BackOfficeMapping",
                      "    include backoffice::BackOfficeMapping\n"
                      "    include brokerage::BrokerageMapping", 1)
        p.write_text(t)


def _q(v):
    return f'"{v}"' if v else "None"


if __name__ == "__main__":
    apply()
    print("brokerage staged")
