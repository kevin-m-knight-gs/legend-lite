"""
Generates DENSE services — many features in one query — instead of one probe per feature.

The corpus grew as a set of narrow probes: median 2 features per service. That is right
for ATTRIBUTION (F10, F12, F13 and F15 are each reportable because they were isolated) and
wrong as the whole corpus, because a defect that only appears when several features
interact is invisible to a probe by construction. F14 is the proof: groupBy over a
many-to-one enum mapping, findable only by stacking.

So this generates the other layer. For each root class it takes what the MODEL actually
offers — the deepest navigation chains, any enum-mapped column, any derived property —
and stacks them into one query with a filter, a sort and a limit on top. Generated from
the model rather than hand-written, so it grows as the model does instead of needing a new
hand-authored service per domain.

Two rules keep the output meaningful:

  * Nothing quarantined goes in. A stack containing a known defect is red for a reason
    nobody has to look for, and masks every interaction it exists to find. Aggregates over
    a to-many are therefore excluded while F6 stands, and groupBy over an enum while F14
    does.
  * A service must reach MIN_FEATURES or it is not emitted at all. A "stack" of three
    features is a probe wearing a bigger name.
"""
from __future__ import annotations

import model
from query import Pred, Proj, Spec

MIN_FEATURES = 5

# Roots worth stacking on: rooted on a seeded table, with real navigation depth. Ordered
# by how much the model offers from each (see the census in the commit message).
ROOTS = [
    ("settlement::Settlement", "settlementId"),
    ("ops::Confirmation", "confirmId"),
    ("regulatory::TradeReport", "reportId"),
    ("sales::SalesCredit", "creditId"),
    ("risk::Greeks", "greeksId"),
    ("pnl::DailyPnL", "pnlId"),
    ("positions::Position", "positionId"),
]

_LABELLISH = ("name", "legalname", "region", "status", "currency", "jurisdiction")


def _chains(c: model.Corpus, root: str, seeded: set[str], depth: int = 3):
    """Every to-one navigation path from `root`, deepest first, whose every hop lands on a
    seeded table. Deepest-first so the densest service takes the longest chains."""
    out, frontier = [], [([], root)]
    for _ in range(depth):
        nxt = []
        for path, cls in frontier:
            for (owner, name), end in c.ends.items():
                if owner != cls or end.to_many or not end.join:
                    continue
                if c.main_table.get(end.target) not in seeded:
                    continue
                out.append((path + [name], end.target))
                nxt.append((path + [name], end.target))
        frontier = nxt
    return sorted(out, key=lambda p: -len(p[0]))


def _leaf(c: model.Corpus, cls: str, kind: str = "string"):
    """A scalar property on `cls`, preferring one that reads like a label so the generated
    query stays legible to a human reviewing the diff."""
    cols = c.columns.get(cls, {})
    table = c.tables.get(c.main_table.get(cls, ""))
    if table is None:
        return None
    fallback = None
    for prop, col in sorted(cols.items()):
        if table.columns[col].kind != kind:
            continue
        if prop.lower() in _LABELLISH:
            return prop
        fallback = fallback or prop
    return fallback


def _alias(path: list[str], leaf: str) -> str:
    parts = path + [leaf]
    head, *rest = parts
    return head + "".join(p[:1].upper() + p[1:] for p in rest)


def build(c: model.Corpus, seeded: set[str]) -> list[Spec]:
    """Generated services must DIFFER from one another, not merely exist.

    The first version of this emitted four services whose navigation was identical --
    every root reaches `trade`, and the deepest chains from trade are the same, so
    Settlement, Confirmation, TradeReport and SalesCredit all became
    trade.instrument.sector.name and friends. That is precisely the failure mode of the
    v1 corpus (10,800 cases, ~75% interchangeable), reproduced by a generator instead of
    by hand.

    Two guards: chains already used by an earlier service are DEPRIORITISED so each root
    reaches somewhere new, and a service whose complete path-set duplicates an earlier one
    is dropped rather than emitted.
    """
    specs = []
    seen_signatures: set[tuple] = set()
    used_chains: set[tuple] = set()
    for n, (root, ident) in enumerate(ROOTS):
        if c.main_table.get(root) not in seeded:
            continue
        chains = _chains(c, root, seeded)
        if not chains:
            continue
        # Prefer chains no earlier service has taken; keep depth as the tiebreak.
        chains.sort(key=lambda pc: (tuple(pc[0]) in used_chains, -len(pc[0])))

        projections = [Proj(ident, [ident])]
        used_targets: set[str] = set()
        features: set[str] = set()

        # Up to three DISTINCT chains — distinct targets, so the service exercises several
        # join trees rather than the same tree three times.
        for path, target in chains:
            if len(projections) > 3 or target in used_targets:
                continue
            leaf = _leaf(c, target)
            if leaf is None:
                continue
            projections.append(Proj(_alias(path, leaf), path + [leaf]))
            used_targets.add(target)
            used_chains.add(tuple(path))
            features.add("navigation")
            if len(path) >= 3:
                features.add("deepNavigation")

        # An enum-mapped column anywhere along the way, if one is reachable.
        for path, target in chains:
            enum_prop = next((p for (cls_, p) in c.enum_props if cls_ == target), None)
            if enum_prop and len(projections) < 8:
                projections.append(Proj(_alias(path, enum_prop), path + [enum_prop]))
                features.add("enum")
                break

        # A derived property, on the root or on anything it reaches.
        for path, target in [([], root)] + chains:
            derived = c.classes.get(target)
            name = next(iter(derived.derived), None) if derived else None
            if name and len(projections) < 9:
                projections.append(Proj(_alias(path, name), path + [name]))
                features.add("derivedProperty")
                break

        spec = Spec(f"stress::D{n}_{root.split('::')[-1]}Dense", f"/stress/d{n}",
                    f"Dense stack on {root}: several distinct navigation chains plus "
                    f"whatever the model offers along them, with a filter, a sort and a "
                    f"limit. Generated from the model by scripts/corpus/stacks.py, so it "
                    f"deepens as the model does rather than needing a new hand-written "
                    f"service per domain.", root)
        spec.projections = projections

        scalar = _leaf(c, root)
        if scalar:
            # A predicate that excludes nothing is not a filter. `!= ' none'` keeps every
            # row — no seeded value has a leading space — while still exercising the
            # predicate path and the three-valued comparison on any NULL in that column.
            spec.filters = [Pred([scalar], "!=", " none")]
            features.add("queryFilter")
        spec.sort = (ident, False)
        spec.limit = 25
        features.update({"sort", "limit"})

        signature = tuple(sorted(".".join(p.path) for p in projections[1:]))
        if signature in seen_signatures:
            continue        # an interchangeable service is not a second test
        if len(features) >= MIN_FEATURES:
            seen_signatures.add(signature)
            specs.append(spec)
    return specs
