"""
Derives the partitioned trade history from the normalized seed.

Same argument as flat.py: the partitions are COMPUTED, not authored. If they were typed
out, a union that silently dropped or duplicated rows would be indistinguishable from a
transcription slip, and the invariance claim would be worthless.

The split is by the instrument's asset class, which is how a firm whose rates and
equities businesses run on different platforms actually stores trades. TRADE_FX is
declared, mapped exactly like the others, and left EMPTY — a union leg contributing no
rows is where naive implementations produce a cross product, drop the other legs, or fail
outright.
"""
from __future__ import annotations

import model
import oracle
import seed

COLUMNS = ["TRADE_ID", "TRADE_DATE", "QUANTITY", "PRICE", "NOTIONAL", "SIDE", "STATUS",
           "CURRENCY", "INSTRUMENT_ID"]

# asset class -> partition table. Anything else is a build failure rather than a silently
# dropped trade, which is the only way a partitioning bug stays invisible.
BY_ASSET_CLASS = {"EQUITY": "TRADE_EQ", "RATES": "TRADE_RATES"}
EMPTY = "TRADE_FX"


def build(c: model.Corpus, tables: dict[str, list[dict]]) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {t: [] for t in
                                  list(BY_ASSET_CLASS.values()) + [EMPTY]}
    _t, col, hops = c.resolve("trading::Trade", ["instrument", "assetClass"])
    for t in tables["TRADE"]:
        landed = oracle.walk(c, tables, t, hops)
        ac = None if landed is None else landed.get(col)
        if ac not in BY_ASSET_CLASS:
            raise SystemExit(
                f"trade {t['TRADE_ID']} has asset class {ac!r}, which no partition "
                f"accepts; every trade must land in exactly one partition or the union "
                f"cannot be equivalent to the whole")
        out[BY_ASSET_CLASS[ac]].append({k: t[k] for k in COLUMNS})
    return out


def check(c: model.Corpus, parts: dict[str, list[dict]],
          tables: dict[str, list[dict]]) -> list[str]:
    bad = []
    total = sum(len(v) for v in parts.values())
    if total != len(tables["TRADE"]):
        bad.append(f"partitions hold {total} rows, TRADE holds {len(tables['TRADE'])}; "
                   f"a union can only equal the whole if the split is exact")
    ids = [r["TRADE_ID"] for v in parts.values() for r in v]
    if len(set(ids)) != len(ids):
        bad.append("a trade appears in more than one partition")
    if parts[EMPTY]:
        bad.append(f"{EMPTY} must stay EMPTY — it is the empty-union-leg case")
    for name, rows in parts.items():
        if name != EMPTY and not rows:
            bad.append(f"{name} is empty; only {EMPTY} may be")
    return bad


if __name__ == "__main__":
    c = model.load()
    parts = build(c, seed.TABLES)
    for k, v in parts.items():
        print(f"  {k:<14} {len(v):>3} rows")
    problems = check(c, parts, seed.TABLES)
    print(f"\nself-check: {'OK' if not problems else str(len(problems)) + ' PROBLEMS'}")
    for p in problems:
        print("  -", p)
