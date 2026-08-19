"""
The STACKING scoreboard: how many features a single executing test puts on top of each other.

executed.py answers "is this feature exercised anywhere". That is the right first question and
it saturates -- once every feature has one passing service the number stops moving, and a
corpus can sit at 100% while every test exercises exactly one thing. The defects this corpus
has actually found did not live in a feature. They lived where features met:

    concat over a NULL          two features: a transform and an absent value
    count over an empty to-many an association shape and an aggregate
    `!=` over a NULL            a predicate and an absent value
    a chain filter that         a class filter and a join chain, where the filter followed
      excluded nothing            the chain into rows that did not exist

So this measures DEPTH rather than breadth. For each service that passes, it computes the set
of distinct constructs its evaluation touches and calls the size of that set the service's
STACK DEPTH. The scoreboard is the distribution of that depth, plus the frontier: which
combinations exist at each depth and which do not exist at all.

Two things it deliberately does NOT do.

It does not count a feature twice for appearing twice. A service projecting forty
dynafunctions stacks one construct, not forty -- otherwise the number rewards volume, which
is the easiest thing to add and the least informative.

It does not credit a quarantined service. A known-failing test pins a defect, which is
valuable, but a stack that has never run green is a claim rather than a demonstration.

The pair and triple counts are the actionable part: a pair at zero is a combination nothing
has ever executed, and that is the next thing to build.
"""
from __future__ import annotations

import itertools
import sys
from collections import Counter

import executed
import model

# The ratchet, updated deliberately when coverage improves. Raised from the numbers this
# corpus reached the day spread.py was written: 175 pairs and 422 triples before it, these
# after.
SURFACE_BASELINE = {"pairs": 260, "triples": 820}


def spec_features(c: model.Corpus, spec, byclass, bymapping, closure) -> set[str]:
    """Every distinct construct this one service's evaluation touches."""
    mp = getattr(spec, "mapping", None) or executed.DEFAULT_MAPPING
    mappings = closure.get(mp, {mp})
    classes, props = executed._spec_touches(c, spec)
    feats: set[str] = set()

    for m in mappings:
        feats |= bymapping.get(m, set())
        for k in classes:
            feats |= byclass.get((m, k), set())

    # Property-level constructs, credited only where the property is actually projected --
    # the same rule executed.py had to learn, for the same reason.
    if any(k in c.chains for k in props):
        feats.add("A2  join chain")
    if any(k in c.dyna for k in props):
        feats.add("A3  dynafunction")
    if any(k in c.bindings for k in props):
        feats.add("A9  Binding transformer")
    if any(k in c.embedded and c.embedded[k] not in c.json_backed for k in props):
        feats.add("A6  embedded")
    if any(k in c.enum_props for k in props):
        feats.add("A5  enum transformer")

    # Store constructs reached by the joins this service follows.
    joins = executed._spec_joins(c, spec, classes)
    for name in joins:
        j = c.joins.get(name)
        if j is None:
            continue
        if j.self_join:
            feats.add("D5  self-join {target}")
        for kind, fid in (("and", "D3  multi-column join"),
                          ("ineq", "--  join non-equality"),
                          ("or", "--  join with or"),
                          ("call", "D4  join w/ dynafunction")):
            if executed._cond_kind(j, kind):
                feats.add(fid)

    # Table-level constructs.
    for k in classes:
        table = c.main_table.get(k)
        if table is None:
            continue
        if c.tables[table].milestoning:
            feats.add("--  milestoning")
        if len(c.tables[table].pk) > 1:
            feats.add("--  composite PK")
        if c.tables[table].schema != "default":
            feats.add("D8  Schema")
        if table in c.views:
            feats.add("D7  View")

    # QUERY SHAPE is a feature too, and the one executed.py never modelled: two services over
    # the same mapping are not the same test if one is a flat projection and the other a
    # tree, or if one aggregates and the other does not.
    if getattr(spec, "graph", None):
        feats.add("Q   graph fetch")
    else:
        feats.add("Q   projection")
    if getattr(spec, "filters", None):
        feats.add("Q   query filter")
    if getattr(spec, "group_by", None) or getattr(spec, "aggs", None):
        feats.add("Q   groupBy")
    for p in getattr(spec, "projections", []):
        if not p.path:
            continue                    # an aggregate over the root itself has no path
        if p.agg == "count":
            feats.add("Q   count over to-many")
        elif p.agg in ("isEmpty", "isNotEmpty"):
            feats.add("Q   emptiness over to-many")
        if p.func:
            feats.add("Q   standalone function")
        if getattr(p, "args", None):
            feats.add("Q   qualified property")
        if c.resolve_derived(spec.root, p.path) is not None:
            feats.add("Q   derived property")
        if len(p.path) > 2:
            feats.add("Q   navigation depth 3+")
    if getattr(spec, "as_of", None):
        feats.add("Q   milestoned as-of")
    return feats


def feasible_pairs(c: model.Corpus, universe) -> tuple[set, list]:
    """Which pairs COULD co-occur, and which need two mappings that never meet.

    The denominator was every pair of constructs, which quietly assumes any two can appear in
    one service. Many cannot. A mapping-level construct is only reachable through the mapping
    that declares it, so two of them can share a service only if some mapping's include
    closure spans both -- and 148 of the 820 pairs are two constructs whose mappings never
    meet anywhere in this corpus.

    Reporting those as uncovered is not wrong, but it is unactionable in the way that matters:
    they cannot be closed by writing a query, only by changing the MODEL so the constructs
    live where one query reaches both. Splitting them out says which of the two jobs each
    remaining pair is.
    """
    byclass = executed.class_features()
    bymapping = executed.mapping_features()
    closure = executed.include_closure()
    host: dict[str, set] = {}
    for m, feats in bymapping.items():
        for f in feats:
            host.setdefault(f, set()).add(m)
    for (m, _k), feats in byclass.items():
        for f in feats:
            host.setdefault(f, set()).add(m)
    for m, included in closure.items():
        for m2 in included:
            for f, ms in host.items():
                if m2 in ms:
                    ms.add(m)
    ok, blocked = set(), []
    for a, b in itertools.combinations(sorted(universe), 2):
        if a in host and b in host and not (host[a] & host[b]):
            blocked.append((a, b))
        else:
            ok.add((a, b))
    return ok, blocked


def profile(c: model.Corpus, specs, quarantined: set[str]):
    byclass = executed.class_features()
    bymapping = executed.mapping_features()
    closure = executed.include_closure()
    out = {}
    for spec in specs:
        if spec.name in quarantined:
            continue
        out[spec.name] = spec_features(c, spec, byclass, bymapping, closure)
    return out


def main() -> None:
    import quarantine

    c = model.load()
    q = set(quarantine.ENGINE_QUARANTINE) | set(quarantine.HANGS)
    prof = profile(c, executed.all_specs(c), q)

    depths = Counter(len(v) for v in prof.values())
    print(f"passing services: {len(prof)}")
    print("\nSTACK DEPTH -- distinct constructs one service puts together")
    for d in sorted(depths):
        bar = "#" * min(60, depths[d])
        print(f"  {d:>2} features  {depths[d]:>4}  {bar}")
    print(f"  max depth: {max(depths) if depths else 0}")

    deepest = sorted(prof.items(), key=lambda kv: -len(kv[1]))[:5]
    print("\nDEEPEST STACKS")
    for name, feats in deepest:
        print(f"  {len(feats):>2}  {name.split('::')[-1]}")
        print(f"      {', '.join(sorted(f.split('  ')[-1] for f in feats))}")

    pairs = Counter()
    for feats in prof.values():
        for a, b in itertools.combinations(sorted(feats), 2):
            pairs[(a, b)] += 1
    universe = sorted({f for v in prof.values() for f in v})
    possible = list(itertools.combinations(universe, 2))
    covered = [p for p in possible if pairs[p]]
    feasible, blocked = feasible_pairs(c, universe)
    cov_f = [p for p in covered if p in feasible]
    print(f"\nPAIR COVERAGE  {len(covered)} of {len(possible)} "
          f"({len(covered) / len(possible):.0%}) feature pairs co-occur in a passing service")
    print(f"  of the {len(feasible)} pairs a query COULD reach: {len(cov_f)} "
          f"({len(cov_f) / len(feasible):.0%})")
    print(f"  the other {len(blocked)} need two mapping constructs that never share a "
          f"mapping --\n  a MODEL change, not a query")

    # TRIPLES. The docstring has promised these since the file was written and the code only
    # ever counted pairs, which is the sort of gap a scoreboard is supposed to expose rather
    # than contain. They matter for the same reason pairs do, one level up: `concat` over a
    # NULL through a join chain is not the union of three pairs, it is a third thing, and the
    # combination matrix exists because that turned out to be where defects live.
    triples = Counter()
    for feats in prof.values():
        for combo in itertools.combinations(sorted(feats), 3):
            triples[combo] += 1
    possible3 = len(universe) * (len(universe) - 1) * (len(universe) - 2) // 6
    print(f"TRIPLE COVERAGE {len(triples)} of {possible3} "
          f"({len(triples) / possible3:.0%}) feature triples co-occur in a passing service")
    print(f"  constructs in play: {len(universe)}")

    if "--gate" in sys.argv:
        # A RATCHET on the two combination counts. Not a target: the numbers are low in
        # absolute terms and will stay low for a while, and the point of pinning them is that
        # they only ever move one way. A generator refactor that quietly stops emitting half
        # its services leaves the suite green and every other scoreboard unchanged -- this is
        # the only place that would notice.
        base = SURFACE_BASELINE
        if len(covered) < base["pairs"] or len(triples) < base["triples"]:
            raise SystemExit(
                f"\ncombination coverage went DOWN:\n"
                f"  pairs   {len(covered)} (was {base['pairs']})\n"
                f"  triples {len(triples)} (was {base['triples']})\n\n"
                f"If this is deliberate -- a construct removed, a generator narrowed -- "
                f"update SURFACE_BASELINE\nin scripts/corpus/stacking.py and say why in the "
                f"commit. If it is not, something stopped\nemitting services and the suite "
                f"is still green, which is exactly what this catches.")
        print(f"\ngate: pairs {len(covered)} >= {base['pairs']}, "
              f"triples {len(triples)} >= {base['triples']}")

    if "--gaps" in sys.argv:
        missing = [p for p in possible if not pairs[p]]
        print(f"\nUNCOVERED PAIRS ({len(missing)}) -- each is a combination nothing executes:")
        for a, b in missing[:60]:
            print(f"    {a.split('  ')[-1]:<28} + {b.split('  ')[-1]}")
        if len(missing) > 60:
            print(f"    ... and {len(missing) - 60} more")


if __name__ == "__main__":
    main()
