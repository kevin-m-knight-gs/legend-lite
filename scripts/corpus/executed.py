"""
Which features are EXECUTED, as distinct from present.

density.py counts feature occurrences in the sources. That is the right measure of whether
the corpus USES a construct and the wrong measure of whether anything TESTS it: every object
in 60-dense-store.pure -- a Schema, a TabularFunction, a MultiGrainFilter, a View, and five
joins -- was declared exactly once, referenced by no mapping, and reached by no service,
while scoring as present. Nine taxonomy entries were decoration.

So this asks a different question, per feature: is there a service that RUNS, whose expected
value would CHANGE if the feature behaved differently?

That question cannot be answered by pattern-matching, so each feature has a predicate over
the resolved model and the generated specs instead. A feature is executed when some
non-quarantined spec touches a site that uses it:

    class-mapping features   a spec whose root, or a class on one of its projection paths,
                             is the class the mapping maps
    store features           a spec that reads the table, or follows the join, involved

A quarantined spec does not count. A known-failing service pins a defect, which is valuable,
but it does not demonstrate that the feature works -- and counting it would let a feature be
"executed" by a test that never passes.

Where a predicate cannot be written, the feature is reported UNKNOWN and counted as NOT
executed. Conservative on purpose: the failure this file exists to prevent is a coverage
number that flatters, and an optimistic default would rebuild exactly that.
"""
from __future__ import annotations

import sys

import model


def _spec_touches(c: model.Corpus, spec) -> tuple[set[str], set[tuple[str, str]]]:
    """(classes, properties) the spec's evaluation passes through.

    Both are needed, and conflating them flatters the report. A property-level feature has
    to be PROJECTED to be executed, not merely to live on a class some service reads:
    hier::Issuer carries a Binding transformer and is read by H_Issuer, which does not
    project it -- so counting the class alone reported the Binding as executed while the
    only service that projects one is quarantined under F27.
    """
    classes, props = {spec.root}, set()
    for p in getattr(spec, "projections", []):
        cls = spec.root
        for step in p.path:
            props.add((cls, step))
            end = c.ends.get((cls, step))
            if end is not None:
                cls = end.target
            elif (cls, step) in c.embedded:
                cls = c.embedded[(cls, step)]
            else:
                break
            classes.add(cls)
    for pred in getattr(spec, "filters", []):
        props.add((spec.root, pred.path[-1]))
    for key in getattr(spec, "group_by", []):
        props.add((spec.root, key))
    return classes, props


def _spec_tables(c: model.Corpus, classes: set[str]) -> set[str]:
    return {c.main_table[k] for k in classes if k in c.main_table}


def _spec_joins(c: model.Corpus, spec, classes: set[str]) -> set[str]:
    """Joins the spec follows: association ends it navigates, and chains it projects."""
    out = set()
    for p in getattr(spec, "projections", []):
        cls = spec.root
        for step in p.path:
            end = c.ends.get((cls, step))
            if end is not None:
                if end.join:
                    out.add(end.join)
                cls = end.target
                continue
            chain = c.chains.get((cls, step))
            if chain is not None:
                out.update(chain[0])
            dyn = c.dyna.get((cls, step))
            if dyn is not None:
                import rhs
                for joins, _t, _col in rhs.chains(("call", dyn)):
                    out.update(joins)
            if (cls, step) in c.embedded:
                cls = c.embedded[(cls, step)]
    for cls in classes:
        chained = c.class_filter_chain.get(cls)
        if chained:
            out.update(chained[0])
    return out


def reached(c: model.Corpus, specs, quarantined: set[str]):
    """(classes, properties, tables, joins) reached by a spec that is expected to PASS."""
    classes, props, tables, joins = set(), set(), set(), set()
    for spec in specs:
        if spec.name in quarantined:
            continue
        ks, ps = _spec_touches(c, spec)
        classes |= ks
        props |= ps
        tables |= _spec_tables(c, ks)
        joins |= _spec_joins(c, spec, ks)
    return classes, props, tables, joins


# ---------------------------------------------------------------- the predicates
#
# Keyed to density.py's taxonomy ids so the two reports line up entry for entry: `present`
# there and `executed` here are meant to be read side by side, and the gap between them is
# the thing worth looking at.
def report(c: model.Corpus, specs, quarantined: set[str]) -> list[tuple[str, bool, str]]:
    ks, ps, ts, js = reached(c, specs, quarantined)

    def any_prop(d, pred=lambda k: True):
        return sorted(k for k in d if k in ps and pred(k))

    def joins_where(pred):
        return sorted(n for n in js if pred(c.joins[n]))

    out = []

    def add(name, hits, note=""):
        out.append((name, bool(hits), note or (f"{len(hits)} site(s): "
                                               f"{', '.join(map(str, list(hits)[:2]))}"
                                               if hits else "no executing service")))

    add("A2  join chain", [k for k in c.chains if k in ps])
    add("A3  dynafunction", any_prop(c.dyna))
    add("A4  dyna over join",
        [k for k, v in c.dyna.items() if k in ps and _has_chain(v)])
    add("A5  enum transformer", any_prop(c.enum_props))
    add("A6  embedded", [k for k, v in c.embedded.items()
                         if k in ps and v not in c.json_backed])
    add("A9  Binding transformer", [k for k in c.bindings if k in ps])
    add("B2  ~filter", [k for k in c.class_filter if k in ks])
    add("B3  ~filter via join", [k for k in c.class_filter_chain if k in ks])
    add("B9  extends [id]", [k for k in ks if c.classes.get(k) and c.classes[k].supertype])
    add("D6  Filter", ([c.class_filter[k] for k in c.class_filter if k in ks]
                       + [c.class_filter_chain[k][1] for k in c.class_filter_chain
                          if k in ks]))
    add("D7  View", [v for v in c.views if v in ts])
    add("D3  multi-column join", joins_where(lambda j: _cond_kind(j, "and")))
    add("--  join non-equality", joins_where(lambda j: _cond_kind(j, "ineq")))
    add("--  join with or", joins_where(lambda j: _cond_kind(j, "or")))
    add("D4  join w/ dynafunction", joins_where(lambda j: _cond_kind(j, "call")))
    add("D5  self-join {target}", joins_where(lambda j: j.self_join))
    add("--  milestoning", [t for t in ts if c.tables[t].milestoning])
    add("--  composite PK", [t for t in ts if len(c.tables[t].pk) > 1])
    add("D8  Schema", [n for n in ts if c.tables[n].schema != "default"])
    # NOT a gap in the corpus. `###Data` materializes Tables only, so a mapping over a
    # TabularFunction fails at test-session setup with the function reported as a missing
    # table -- see repro/tabularfunction-untestable/, where a real Table in the same Schema
    # seeded the same way is the control. The construct is unreachable by any service test,
    # so it is reported separately rather than counted against the total: closing it is not
    # something the corpus can do.
    out.append(("D10 TabularFunction", None,
                "UNTESTABLE by construction -- ###Data materializes Tables only; see "
                "repro/tabularfunction-untestable/"))
    add("D11 MultiGrainFilter",
        [n for k in c.class_filter if k in ks
         for n in [c.class_filter[k]] if n in c.multigrain])
    return out


def _has_chain(dyn) -> bool:
    import rhs
    return bool(rhs.chains(("call", dyn)))


def _cond_kind(j, kind: str) -> bool:
    """Whether a join's condition contains a given shape."""
    if j.condition is None:
        return False

    def walk(node):
        tag, body = node
        if tag in ("and", "or"):
            return (tag == kind) or walk(body[0]) or walk(body[1])
        if tag == "cmp":
            left, op, right = body
            if kind == "ineq" and op != "=":
                return True
            if kind == "call" and (left[0] == "call" or right[0] == "call"):
                return True
            return False
        return False

    return walk(j.condition)


def all_specs(c: model.Corpus):
    """Every spec the corpus emits, from every generator."""
    import flat
    import aggregates
    import battery
    import combos
    import graphs
    import hier
    import query
    import stacks

    tables = flat.all_tables(c)
    seeded = {k for k, v in tables.items() if v}
    return (query.load() + list(battery.SPECS) + stacks.build(c, seeded)
            + graphs.build(c, seeded, tables) + aggregates.build(c, seeded, tables)
            + hier.specs(c) + combos.specs(c))


if __name__ == "__main__":
    import quarantine

    c = model.load()
    rows = report(c, all_specs(c), set(quarantine.ENGINE_QUARANTINE) | set(quarantine.HANGS))
    done = sum(1 for _n, ok, _w in rows if ok)
    blocked = sum(1 for _n, ok, _w in rows if ok is None)
    measurable = len(rows) - blocked
    for name, ok, why in rows:
        mark = "n/a " if ok is None else ("EXEC" if ok else "  --")
        print(f"  {mark}  {name:<26} {why}")
    print(f"\n{done} of {measurable} testable features are EXECUTED by a passing service"
          + (f"; {blocked} untestable by construction" if blocked else ""))
    if "--gate" in sys.argv:
        gaps = [n for n, ok, _w in rows if ok is False]
        if gaps:
            raise SystemExit(f"\nnot executed: {', '.join(gaps)}")
