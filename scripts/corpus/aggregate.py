"""
Derives TRADE_BY_BOOK — the physical pre-aggregated table behind the AggregationAware
mapping.

Same argument as flat.py and partition.py: computed, not authored. An AggregationAware
mapping is a promise that reading the small table gives the same answer as reading the
detail, and a hand-typed aggregate would let a transcription slip masquerade as a rewrite
bug.

Note what this does NOT prove. Because detail and aggregate agree by construction, a query
returns the right answer whether or not the rewrite fired — the result cannot tell you
which table was read. That has to come from the generated SQL, which is why the emitted
statement is inspected rather than inferred.
"""
from __future__ import annotations

import model
import seed


def build(c: model.Corpus, tables: dict[str, list[dict]]) -> list[dict]:
    groups: dict[str, list[dict]] = {}
    for t in tables["TRADE"]:
        groups.setdefault(t["BOOK_ID"], []).append(t)
    return [dict(BOOK_ID=b,
                 TOTAL_NOTIONAL=round(sum(r["NOTIONAL"] for r in rows), 6),
                 TRADE_COUNT=len(rows))
            for b, rows in groups.items()]


def check(c: model.Corpus, rows: list[dict], tables: dict[str, list[dict]]) -> list[str]:
    """Aggregate decomposition again: the pre-aggregate must sum to the detail. If this
    ever fails, the AggregationAware mapping is claiming an equivalence that does not
    hold and every query through it is suspect."""
    bad = []
    total = round(sum(r["TOTAL_NOTIONAL"] for r in rows), 6)
    detail = round(sum(t["NOTIONAL"] for t in tables["TRADE"]), 6)
    if total != detail:
        bad.append(f"TRADE_BY_BOOK sums to {total}, TRADE sums to {detail}")
    if sum(r["TRADE_COUNT"] for r in rows) != len(tables["TRADE"]):
        bad.append("TRADE_BY_BOOK row counts do not sum to the detail row count")
    # A book with no trades forms no group -- the same absence the rollup View shows.
    booked = {r["BOOK_ID"] for r in rows}
    if not ({b["BOOK_ID"] for b in tables["BOOK"]} - booked):
        bad.append("every book has a group; the empty-book case is not represented")
    return bad
