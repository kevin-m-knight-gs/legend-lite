"""The project matrix: what each generated Legend project is, and what makes it different.

The point of many projects is not many classes -- the taxonomy batches already proved that
volume alone buys little. It is DEPENDENCY: a project that extends another's classes,
includes another's store, includes another's mapping, associates across a boundary, calls
another's functions and applies another's profile stereotypes. Those references are the thing
that can break when a graph gets deep, and until now this corpus had one flat namespace where
none of them crossed anything.

So every project here differs deliberately along five axes:

  LAYER      how deep in the graph it sits (L0 has no dependencies; L3 depends on L2)
  DEPS       which projects it depends on, and in what SHAPE -- chain, diamond, star, fan-in
  EXPORTS    what it offers downstream: classes, a store, a mapping, functions, enums,
             profiles, associations, or a deliberately narrow subset of those
  CONSTRUCTS which Legend features it uses INTERNALLY, so the graph exercises milestoning,
             composite keys, embedded mappings, unions, views, schemas and the rest at
             different depths rather than all at the root
  SIZE       3 classes to 60, so parse and compile time can be attributed to shape as well
             as to volume

The verification contract is the same for all of them and is checked by
scripts/projects/check.py: every project must compile ALONE with its declared dependencies,
and the whole graph must compile together.
"""

# (name, layer, [deps], exports, constructs, size, theme)
#
# EXPORTS and CONSTRUCTS are vocabularies rather than free text, so the matrix can be
# queried -- "which projects export a store", "which use milestoning at layer 2".
PROJECTS = [
    # ---------------------------------------------------------------- L0: foundations
    # No dependencies. These are what everything else refers to, so their shapes matter
    # most: an enum here is used six layers down.
    ("core-types", 0, [], ["enums", "profiles", "functions"],
     ["enum", "profile", "function"], "small",
     "Currency, country and unit enums; a governance profile; rounding and date helpers."),
    ("core-party", 0, [], ["classes", "store", "mapping", "associations"],
     ["composite-pk", "association"], "medium",
     "Legal entities, their identifiers and their hierarchy. Keyed by entity and scheme."),
    ("core-calendar", 0, [], ["classes", "store", "mapping", "functions"],
     ["view", "function"], "small",
     "Business calendars, holidays and settlement cycles, with a holiday-count view."),
    ("core-instrument", 0, [], ["classes", "store", "mapping", "enums"],
     ["inheritance", "filter-subtype", "enum-transformer"], "large",
     "Instrument master: a base class with eight asset-class subtypes over one table."),
    ("core-account", 0, [], ["classes", "store", "mapping"],
     ["composite-pk", "embedded"], "medium",
     "Accounts and their embedded ownership block, keyed by institution and number."),
    ("core-book", 0, [], ["classes", "store", "mapping", "associations"],
     ["association", "self-join"], "medium",
     "Book hierarchy: a book reports to a book, so the join is a {target} self-join."),
    ("core-price", 0, [], ["classes", "store", "mapping"],
     ["composite-pk", "float-types"], "medium",
     "Price observations keyed by instrument and date, with SMALLINT and FLOAT columns."),
    ("core-fx", 0, [], ["classes", "store", "mapping", "functions"],
     ["composite-pk", "function"], "small",
     "FX rates keyed by pair and date, with a conversion function used downstream."),
    ("core-ratings", 0, [], ["classes", "store", "mapping"],
     ["milestoning"], "small",
     "Credit ratings as a business-temporal class, so `all(%date)` crosses a boundary."),
    ("core-tenor", 0, [], ["classes", "store", "mapping", "enums"],
     ["enum", "range-join"], "small",
     "Tenor buckets, joined by a range on days rather than by a key."),
    ("core-geo", 0, [], ["classes", "store", "mapping", "associations"],
     ["association", "join-chain"], "medium",
     "Country, region and economic bloc -- three hops for a downstream join chain."),
    ("core-units", 0, [], ["classes", "store", "mapping"],
     ["schema", "datatypes"], "small",
     "Units of measure in their own SCHEMA, with CHAR, NUMERIC and SMALLINT columns."),

    # ---------------------------------------------------------------- L1: core domains
    # Each depends on one or two L0 projects. The shapes here are CHAINS and the first
    # diamonds.
    ("trade-capture", 1, ["core-instrument", "core-party", "core-book"],
     ["classes", "store", "mapping", "associations"],
     ["association", "constraints", "enum-transformer"], "large",
     "Trades, referencing instruments, parties and books from three separate projects."),
    ("position-keeping", 1, ["core-instrument", "core-account"],
     ["classes", "store", "mapping"], ["composite-pk", "aggregation"], "medium",
     "Positions keyed by account, instrument and date."),
    ("settlement-core", 1, ["core-party", "core-calendar"],
     ["classes", "store", "mapping", "enums"], ["enum", "join-chain"], "medium",
     "Settlement instructions, reaching a calendar to compute a settlement date."),
    ("valuation-core", 1, ["core-price", "core-fx"],
     ["classes", "store", "mapping", "functions"], ["function", "float-types"], "medium",
     "Valuations, calling the FX project's conversion function in a derived property."),
    ("reference-data", 1, ["core-instrument", "core-geo"],
     ["classes", "store", "mapping"], ["filter-subtype", "distinct"], "large",
     "Reference data over the instrument master, with a ~distinct issuer set."),
    ("credit-core", 1, ["core-party", "core-ratings"],
     ["classes", "store", "mapping"], ["milestoning", "association"], "medium",
     "Credit exposures reaching a MILESTONED rating class in another project."),
    ("collateral-core", 1, ["core-party", "core-instrument"],
     ["classes", "store", "mapping", "associations"], ["association", "or-join"], "medium",
     "Collateral eligible by issuer OR by asset class -- a disjunction across projects."),
    ("cash-core", 1, ["core-account", "core-types"],
     ["classes", "store", "mapping"], ["embedded", "enum"], "medium",
     "Cash movements, using the currency enum from the types project."),
    ("order-core", 1, ["core-instrument", "core-book"],
     ["classes", "store", "mapping", "enums"], ["enum", "self-join"], "medium",
     "Orders, with a parent-order self-join and a state enum."),
    ("fee-core", 1, ["core-types", "core-tenor"],
     ["classes", "store", "mapping"], ["range-join", "composite-pk"], "small",
     "Fee schedules banded by tenor, joined by range across a project boundary."),
    ("risk-core", 1, ["core-instrument", "core-price"],
     ["classes", "store", "mapping"], ["aggregation", "view"], "large",
     "Risk factors and sensitivities, with a store VIEW aggregating them."),
    ("static-core", 1, ["core-types", "core-units"],
     ["classes", "store", "mapping"], ["schema", "datatypes"], "small",
     "Static data in a schema, depending on another project's schema-qualified tables."),
    ("legal-core", 1, ["core-party"], ["classes", "store", "mapping", "profiles"],
     ["profile", "association"], "medium",
     "Agreements between parties, tagged with a governance profile."),
    ("custody-core", 1, ["core-account", "core-instrument"],
     ["classes", "store", "mapping"], ["composite-pk", "embedded"], "medium",
     "Custody holdings keyed by account, instrument and depot."),
    ("index-core", 1, ["core-instrument", "core-geo"],
     ["classes", "store", "mapping", "associations"], ["association", "aggregation"],
     "medium", "Index constituents and weights, aggregated by region."),
    ("funding-core", 1, ["core-account", "core-tenor"],
     ["classes", "store", "mapping"], ["range-join", "milestoning"], "medium",
     "Funding curves bucketed by tenor, versioned business-temporally."),
    ("client-core", 1, ["core-party", "core-geo"],
     ["classes", "store", "mapping", "associations"], ["association", "join-chain"],
     "medium", "Clients and their domiciles, three hops out to an economic bloc."),
    ("product-core", 1, ["core-instrument", "core-types"],
     ["classes", "store", "mapping"], ["inheritance", "filter-subtype"], "large",
     "Product taxonomy extending the instrument base from another project."),
    ("event-core", 1, ["core-instrument", "core-calendar"],
     ["classes", "store", "mapping", "enums"], ["enum", "association"], "medium",
     "Corporate events dated against another project's business calendar."),
    ("limit-core", 1, ["core-party", "core-book"],
     ["classes", "store", "mapping"], ["composite-pk", "constraints"], "medium",
     "Limits keyed by book, party and measure, with class-level validations."),

    # ---------------------------------------------------------------- L2: cross-domain
    # DIAMONDS: two L1 projects that share an L0 dependency, joined back together here.
    ("pnl-attribution", 2, ["position-keeping", "valuation-core"],
     ["classes", "store", "mapping"], ["aggregation", "float-types"], "large",
     "P&L attribution over positions and valuations -- a diamond through core-instrument."),
    ("exposure-agg", 2, ["credit-core", "collateral-core"],
     ["classes", "store", "mapping"], ["aggregation", "or-join"], "large",
     "Net exposure after collateral -- a diamond through core-party."),
    ("settlement-flow", 2, ["settlement-core", "cash-core"],
     ["classes", "store", "mapping", "associations"], ["association", "join-chain"],
     "medium", "Settlement to cash movement, four hops from the calendar."),
    ("trade-lifecycle", 2, ["trade-capture", "event-core"],
     ["classes", "store", "mapping", "enums"], ["enum", "self-join"], "large",
     "Lifecycle events amending trades, with an amendment chain."),
    ("regulatory-extract", 2, ["trade-capture", "reference-data", "client-core"],
     ["classes", "store", "mapping"], ["schema", "filter-subtype"], "large",
     "A regulatory extract in its own schema, fanning in from three projects."),
    ("margin-calc", 2, ["position-keeping", "risk-core"],
     ["classes", "store", "mapping", "functions"], ["function", "aggregation"], "medium",
     "Margin from positions and sensitivities."),
    ("liquidity-view", 2, ["cash-core", "funding-core"],
     ["classes", "store", "mapping"], ["view", "range-join"], "medium",
     "Liquidity buckets, reading a view over two projects' tables."),
    ("client-reporting", 2, ["client-core", "position-keeping", "valuation-core"],
     ["classes", "store", "mapping"], ["aggregation", "association"], "large",
     "Client statements: a three-way fan-in."),
    ("collateral-opt", 2, ["collateral-core", "index-core"],
     ["classes", "store", "mapping"], ["or-join", "aggregation"], "medium",
     "Collateral optimisation against index eligibility."),
    ("order-execution", 2, ["order-core", "trade-capture"],
     ["classes", "store", "mapping", "associations"], ["association", "constraints"],
     "large", "Executions linking orders to trades."),
    ("fee-billing", 2, ["fee-core", "client-core"],
     ["classes", "store", "mapping"], ["range-join", "aggregation"], "medium",
     "Billing runs applying tenor-banded fees to clients."),
    ("custody-recon", 2, ["custody-core", "position-keeping"],
     ["classes", "store", "mapping"], ["composite-pk", "aggregation"], "medium",
     "Custody against internal positions -- the classic reconciliation."),
    ("credit-limits", 2, ["credit-core", "limit-core"],
     ["classes", "store", "mapping"], ["milestoning", "constraints"], "medium",
     "Limit utilisation against milestoned exposures."),
    ("product-pricing", 2, ["product-core", "valuation-core"],
     ["classes", "store", "mapping", "functions"], ["function", "inheritance"], "large",
     "Pricing by product type, extending the product taxonomy."),
    ("static-distribution", 2, ["static-core", "reference-data"],
     ["classes", "store", "mapping"], ["schema", "distinct"], "medium",
     "Static data distribution, deduplicating across two schemas."),
    ("legal-netting", 2, ["legal-core", "exposure-agg"],
     ["classes", "store", "mapping"], ["association", "aggregation"], "medium",
     "Netting sets defined by agreements, over aggregated exposure."),

    # ---------------------------------------------------------------- L3: applications
    # DEEP: four levels from a leaf. These are where a long reference chain either holds or
    # does not.
    ("firm-balance-sheet", 3, ["pnl-attribution", "exposure-agg", "liquidity-view"],
     ["classes", "store", "mapping"], ["aggregation", "schema"], "large",
     "The balance sheet, five levels above core-types."),
    ("regulatory-capital", 3, ["exposure-agg", "regulatory-extract", "margin-calc"],
     ["classes", "store", "mapping"], ["aggregation", "filter-subtype"], "large",
     "Capital calculation fanning in from three L2 projects."),
    ("client-portal", 3, ["client-reporting", "fee-billing"],
     ["classes", "store", "mapping"], ["association", "view"], "medium",
     "What a client actually sees, four levels from the instrument master."),
    ("trade-surveillance", 3, ["order-execution", "trade-lifecycle"],
     ["classes", "store", "mapping", "enums"], ["enum", "self-join"], "large",
     "Surveillance over orders, executions and amendments."),
    ("risk-dashboard", 3, ["margin-calc", "credit-limits", "pnl-attribution"],
     ["classes", "store", "mapping"], ["aggregation", "view"], "large",
     "The risk dashboard: the widest fan-in in the graph."),
    ("ops-control", 3, ["custody-recon", "settlement-flow"],
     ["classes", "store", "mapping"], ["association", "constraints"], "medium",
     "Operational controls over reconciliation and settlement."),
    ("collateral-mgmt", 3, ["collateral-opt", "legal-netting"],
     ["classes", "store", "mapping"], ["or-join", "association"], "medium",
     "Collateral management under netting agreements."),
    ("product-catalogue", 3, ["product-pricing", "static-distribution"],
     ["classes", "store", "mapping"], ["inheritance", "distinct"], "large",
     "The product catalogue as distributed to sales."),
]

SIZES = {"small": (3, 6), "medium": (8, 16), "large": (20, 34)}


def by_layer():
    out = {}
    for p in PROJECTS:
        out.setdefault(p[1], []).append(p)
    return out


def lookup(name):
    for p in PROJECTS:
        if p[0] == name:
            return p
    raise KeyError(name)


def transitive(name, seen=None):
    """Every project `name` depends on, directly or otherwise."""
    seen = seen if seen is not None else set()
    for d in lookup(name)[2]:
        if d not in seen:
            seen.add(d)
            transitive(d, seen)
    return seen


if __name__ == "__main__":
    import collections

    print(f"{len(PROJECTS)} projects")
    for layer, ps in sorted(by_layer().items()):
        print(f"  L{layer}: {len(ps)}")
    depth = {p[0]: len(transitive(p[0])) for p in PROJECTS}
    deepest = max(depth.items(), key=lambda kv: kv[1])
    print(f"deepest transitive closure: {deepest[0]} depends on {deepest[1]} projects")
    print("\nconstruct coverage across the graph:")
    for k, n in collections.Counter(
            c for p in PROJECTS for c in p[4]).most_common():
        print(f"  {k:<18}{n}")
