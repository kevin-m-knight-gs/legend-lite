"""
The FUNCTION burndown: what the oracle can evaluate, against what the engine can execute.

docs/ENGINE_FUNCTIONS.tsv is derived from `getSupportedFunctions()` in pureToSQLQuery.pure --
the map the engine consults before it emits "No SQL translation exists for the PURE function".
292 distinct names, 428 entries once overloads are counted. It is generated, not transcribed:
the list is too long to copy without error and too important to get wrong.

A function is IMPLEMENTED here when the oracle computes its result independently of
legend-engine. That is the whole bar, and it is deliberately high. Reading the engine's
lowering and reproducing it would make every assertion circular -- the corpus would agree
with the engine by construction and could never disagree with it, which is the only thing it
exists to do.

So each entry lands in one of three states, and the middle one is not a failure:

    IMPLEMENTED   the oracle has an independent implementation and states its NULL rule
    REFUSED       the oracle raises Unsupported, with a reason
    ABSENT        neither -- nobody has looked at it yet

REFUSED is a real answer. `concat` taught the lesson: its behaviour over NULL is decided by
the DIALECT, not by the function, so an implementation written from what concat "means" was
wrong and the corpus asserted it confidently. Some of these 292 cannot be implemented
honestly without picking a dialect, and saying so beats guessing.

Not every function is reachable from a mapping either. The registry mixes scalar functions
usable in a property expression with TDS and Relation operations that are query SHAPES, with
collection operations that are navigation, with 30 calendar aggregations that need a business
calendar to mean anything. The scoreboard reports by family so the shape of the remaining
work is visible rather than a single discouraging number.
"""
from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

INVENTORY = Path(__file__).resolve().parents[2] / "docs/ENGINE_FUNCTIONS.tsv"

# Families, in the order the burndown works them. The split is by what a function IS, not by
# package: a scalar usable in a mapping expression is a different kind of work from a TDS
# operation that changes the shape of a result.
FAMILY = {
    "meta::pure::functions::math": "scalar",
    "meta::pure::functions::string": "scalar",
    "meta::pure::functions::boolean": "scalar",
    "meta::pure::functions::date": "scalar-date",
    "meta::pure::functions::date::calendar": "calendar",
    "meta::pure::functions::collection": "collection",
    "meta::pure::functions::lang": "lang",
    "meta::pure::functions::multiplicity": "lang",
    "meta::pure::functions::meta": "lang",
    "meta::pure::functions::relation": "relation",
    "meta::pure::tds": "tds",
    "meta::pure::tds::extensions": "tds",
    "meta::pure::functions::variant::convert": "variant",
    "meta::pure::functions::hash": "scalar",
    "meta::pure::functions::runtime": "scalar",
    "meta::core::runtime": "scalar",
    "meta::pure::graphFetch::execution": "graphfetch",
    "meta::pure::mutation": "mutation",
    "meta::relational::functions": "relational-native",
}


def inventory() -> list[tuple[str, str, int]]:
    rows = []
    for line in INVENTORY.read_text().splitlines()[1:]:
        pkg, name, n = line.split("\t")
        rows.append((pkg, name, int(n)))
    return rows


def family(pkg: str) -> str:
    return FAMILY.get(pkg, "other")


# The two registries are consulted PER FAMILY, never merged. `filter`, `sort`, `distinct`
# and `size` exist in both the collection and relation families with different signatures,
# so a merged lookup would let one implementation mark the other done -- the same flattering
# every scoreboard here has had to be rescued from at least once.
_RELATION_FAMILIES = {"relation", "tds"}


def implemented(fam: str | None = None) -> set[str]:
    """Names the oracle evaluates, read FROM the oracle rather than listed here, so the two
    cannot drift: a function deleted from a registry disappears from the count."""
    import oracle
    if fam in _RELATION_FAMILIES:
        return set(oracle.RELATION_IMPLEMENTED)
    return set(oracle.IMPLEMENTED)


def refused(fam: str | None = None) -> dict[str, str]:
    import oracle
    if fam in _RELATION_FAMILIES:
        return dict(oracle.RELATION_REFUSED)
    return dict(oracle.REFUSED)


def report():
    rows = inventory()
    by_family: dict[str, list] = {}
    for pkg, name, n in rows:
        by_family.setdefault(family(pkg), []).append(name)

    print(f"ENGINE FUNCTION REGISTRY: {len(rows)} distinct names "
          f"({sum(n for _p, _n, n in rows)} entries with overloads)\n")
    print(f"  {'family':<18} {'impl':>5} {'refused':>8} {'absent':>7}   of")
    tot_i = tot_r = 0
    for fam, names in sorted(by_family.items()):
        impl, refu = implemented(fam), refused(fam)
        i = sum(1 for x in names if x in impl)
        r = sum(1 for x in names if x in refu)
        tot_i += i
        tot_r += r
        print(f"  {fam:<18} {i:>5} {r:>8} {len(names) - i - r:>7}   {len(names)}")
    print(f"\n  {'TOTAL':<18} {tot_i:>5} {tot_r:>8} "
          f"{len(rows) - tot_i - tot_r:>7}   {len(rows)}")

    if "--absent" in sys.argv:
        fam_want = next((a.split("=")[1] for a in sys.argv if a.startswith("--family=")), None)
        print("\nABSENT -- no independent implementation and no stated refusal:")
        for fam, names in sorted(by_family.items()):
            if fam_want and fam != fam_want:
                continue
            impl, refu = implemented(fam), refused(fam)
            missing = sorted(x for x in names if x not in impl and x not in refu)
            if missing:
                print(f"\n  [{fam}] {len(missing)}")
                for i in range(0, len(missing), 6):
                    print("    " + "  ".join(f"{m:<20}" for m in missing[i:i + 6]))

    if "--refused" in sys.argv:
        print("\nREFUSED -- deliberately not implemented, with the reason:")
        allrefu = {**refused(), **refused("relation")}
        for name, why in sorted(allrefu.items()):
            print(f"  {name:<24} {why}")


if __name__ == "__main__":
    report()
