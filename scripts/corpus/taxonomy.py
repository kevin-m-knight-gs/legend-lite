"""
One service per SUBTYPE of a discriminated taxonomy.

A taxonomy modelled as one table plus a discriminator has exactly one thing that can go
wrong, and it is invisible: if a subtype's `~filter` is wrong, the set returns the WHOLE
table rather than its own rows. Nothing errors, the columns are right, the types are right,
and a report that asked for swaptions gets every trade in the book.

The only way to notice is to ask each subtype for its own rows and check the count. So this
emits one service per subtype -- seventy-odd of them across the derivatives, risk,
middle-office and back-office taxonomies -- each projecting the discriminator column itself,
which makes a wrong filter obvious in the expectation rather than merely wrong.

Empty subtypes are emitted too, and deliberately. A firm's product taxonomy covers what it
CAN book, not what it happens to hold this morning, so half of these return no rows -- and an
empty result is the strongest possible evidence that a filter is doing its job, because the
failure mode is returning everything.
"""
from __future__ import annotations

import model
import query
from query import Proj, Spec

# (base class, the property carrying the discriminator, a short tag for the service name)
TAXONOMIES = [
    ("derivatives::OtcTrade", "productType", "TX"),
    ("risk::RiskMeasure", "measureType", "TXR"),
    ("risk::Sensitivity", "measureType", "TXS"),
    ("middleoffice::LifecycleEvent", "eventType", "TXE"),
    ("middleoffice::TradeBreak", "breakType", "TXB"),
    ("backoffice::Payment", "paymentType", "TXP"),
    ("backoffice::ReconciliationItem", "itemType", "TXI"),
    ("corpactions::CorporateActionEvent", "actionType", "TXC"),
    ("securities::MasterSecurity", "securityType", "TXM"),
    ("orders::OrderTicket", "orderType", "TXO"),
]

# What each subtype service STACKS on top of the discriminator.
#
# The first version projected the identifier and the discriminator and nothing else, which is
# the right test for a ~filter and contributes nothing to the stacking scoreboard: a hundred
# and ten services, two constructs each, no combinations. These are the same services asking
# the same question with the reach the model already offers -- a derived property, a
# qualified one, a navigation, an emptiness -- so a wrong filter is still obvious AND the
# service is a stack rather than a probe.
#
# (alias, path, aggregate, args)
EXTRAS = {
    "derivatives::OtcTrade": [
        ("tenorYears", ["tenorYears"], None, []),
        ("notionalGbp", ["notionalIn"], None, [0.79]),
        ("counterpartyId", ["counterpartyId"], None, []),
        ("noLegs", ["legs"], "isEmpty", []),
        ("noOptionTerms", ["optionTerms"], "isEmpty", []),
    ],
    "risk::RiskMeasure": [
        ("valueEur", ["valueIn"], None, [0.92]),
        ("cobDate", ["measureRun", "cobDate"], None, []),
        ("runStatus", ["measureRun", "status"], None, []),
    ],
    "risk::Sensitivity": [
        ("scaled", ["scaledTo"], None, [2.0]),
        ("curveName", ["factor", "curveName"], None, []),
        ("assetClass", ["factor", "assetClass"], None, []),
        ("productType", ["sensitivityTrade", "productType"], None, []),
    ],
    "middleoffice::LifecycleEvent": [
        ("settlementLagDays", ["settlementLagDays"], None, []),
        ("tradeProduct", ["eventTrade", "productType"], None, []),
        ("tradeCurrency", ["eventTrade", "currency"], None, []),
    ],
    "middleoffice::TradeBreak": [
        ("ageDays", ["ageDays"], None, []),
        ("tradeProduct", ["brokenTrade", "productType"], None, []),
        ("counterpartyId", ["brokenTrade", "counterpartyId"], None, []),
    ],
    "backoffice::Payment": [
        ("signedAmount", ["signedAmount"], None, []),
        ("correspondentBic", ["instruction", "correspondentBic"], None, []),
        ("placeOfSettlement", ["instruction", "placeOfSettlement"], None, []),
        ("tradeProduct", ["paidTrade", "productType"], None, []),
    ],
    "backoffice::ReconciliationItem": [
        ("ageDays", ["ageDays"], None, []),
        ("accountName", ["reconNostro", "accountName"], None, []),
        ("correspondentBic", ["reconNostro", "correspondentBic"], None, []),
    ],
    "corpactions::CorporateActionEvent": [
        ("noticeDays", ["noticeDays"], None, []),
        ("isMandatory", ["isMandatory"], None, []),
        ("cashRate", ["cashRate"], None, []),
        ("payDate", ["payDate"], None, []),
    ],
    "securities::MasterSecurity": [
        ("daysToMaturity", ["daysToMaturity"], None, []),
        ("issuerName", ["issuerName"], None, []),
        ("faceValue", ["faceValue"], None, []),
        ("isListed", ["isListed"], None, []),
    ],
    "orders::OrderTicket": [
        ("fillRatio", ["fillRatio"], None, []),
        ("side", ["side"], None, []),
        ("limitPrice", ["limitPrice"], None, []),
        ("timeInForce", ["timeInForce"], None, []),
    ],
}

# An identifier per base, so the service has something stable to sort on.
IDENT = {
    "derivatives::OtcTrade": "otcId",
    "risk::RiskMeasure": "measureId",
    "risk::Sensitivity": "sensitivityId",
    "middleoffice::LifecycleEvent": "eventId",
    "middleoffice::TradeBreak": "breakId",
    "backoffice::Payment": "paymentId",
    "backoffice::ReconciliationItem": "reconId",
    "corpactions::CorporateActionEvent": "actionId",
    "securities::MasterSecurity": "securityKey",
    "orders::OrderTicket": "ticketId",
}


def build(c: model.Corpus, seeded: set[str], tables=None) -> list[Spec]:
    specs = []
    for base, discriminator, tag in TAXONOMIES:
        ident = IDENT[base]
        subtypes = sorted(k for k, v in c.classes.items() if v.supertype == base)
        for cls in subtypes:
            short = query.short_name(c, cls)
            spec = Spec(f"stress::{tag}_{short}", f"/stress/{tag.lower()}_{short.lower()}",
                        f"{cls} only. The subtype's ~filter is the whole test: if it were "
                        f"wrong this would return every row of the base table instead of "
                        f"its own, with no error and no type mismatch. Generated by "
                        f"scripts/corpus/taxonomy.py.", cls)
            # The discriminator itself is projected, so a wrong filter shows up as a wrong
            # VALUE in the expectation rather than only as a wrong row count.
            spec.projections = [Proj(ident, [ident]),
                                Proj(discriminator, [discriminator])]
            for alias, path, agg, args in EXTRAS.get(base, []):
                spec.projections.append(Proj(alias, list(path), agg, list(args)))
            spec.sort = (ident, False)
            query.apply_temporal(c, spec)
            specs.append(spec)
    return specs
