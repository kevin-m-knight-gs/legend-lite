"""
Derives TRADE_FLAT — the denormalized reporting table — from the normalized seed.

This file is short and the reason for it is the whole point of L2.

Mapping invariance says: the same query, asked through two semantically equivalent
mappings, must return the same rows. It is the strongest assertion in the corpus because
it needs no oracle — a violation is unambiguously an engine defect, not a disagreement
about what the answer should be.

That only holds if the two shapes really do carry the same information. Hand-authoring
TRADE_FLAT would break exactly that: one transcription slip and the "invariance failure"
is a fixture bug wearing an engine bug's clothes, which is worse than no test. So the flat
rows are COMPUTED from the normalized rows by walking the same joins the normalized
mapping walks, using the same resolver.

The NULLs matter most. TRD-0007 points at a counterparty that does not exist and two
trades carry no trader, so the flat row must hold NULLs in those columns — precisely what
the outer join produces on the normalized side. Encoding the join RESULT, not the join
INPUT, is what makes the two paths comparable.
"""
from __future__ import annotations

import model
import oracle
import seed

# flat column -> path from trading::Trade in the normalized model.
# Every one of these is resolved through model.py, so a typo is a build failure rather
# than a silently NULL column that would make invariance look satisfied.
COLUMNS: dict[str, list[str]] = {
    "TRADE_ID": ["tradeId"],
    "TRADE_DATE": ["tradeDate"],
    "SETTLEMENT_DATE": ["settlementDate"],
    "QUANTITY": ["quantity"],
    "PRICE": ["price"],
    "NOTIONAL": ["notional"],
    "STATUS": ["status"],
    "TRADE_TYPE": ["tradeType"],
    "CURRENCY": ["currency"],
    "COMMISSION": ["commission"],
    "FEES": ["fees"],
    "EXECUTION_VENUE": ["executionVenue"],
    "IS_BLOCK": ["isBlock"],
    "CREATED_TIME": ["createdTime"],
    "LAST_MODIFIED_TIME": ["lastModifiedTime"],
    "INSTR_ID": ["instrument", "instrumentId"],
    "INSTR_NAME": ["instrument", "name"],
    "INSTR_TICKER": ["instrument", "ticker"],
    "INSTR_ASSET_CLASS": ["instrument", "assetClass"],
    "INSTR_CURRENCY": ["instrument", "currency"],
    "INSTR_ISIN": ["instrument", "isin"],
    "BOOK_ID": ["book", "bookId"],
    "BOOK_NAME": ["book", "name"],
    "BOOK_CURRENCY": ["book", "currency"],
    "CPTY_ID": ["counterparty", "counterpartyId"],
    "CPTY_NAME": ["counterparty", "legalName"],
    "CPTY_LEI": ["counterparty", "lei"],
    "CPTY_TIER": ["counterparty", "tier"],
    "TRADER_ID": ["trader", "traderId"],
    "TRADER_FIRST": ["trader", "firstName"],
    "TRADER_LAST": ["trader", "lastName"],
}

ROOT = "trading::Trade"


def build(c: model.Corpus, tables: dict[str, list[dict]]) -> list[dict]:
    rows = []
    for t in tables["TRADE"]:
        row = {}
        for col, path in COLUMNS.items():
            _table, src, hops = c.resolve(ROOT, path)
            landed = oracle.walk(c, tables, t, hops)
            row[col] = None if landed is None else landed.get(src)
        # SIDE is deliberately carried as the SOURCE CODE, not the label: the flat mapping
        # reuses the same EnumerationMapping, so the enum translation is exercised on both
        # paths rather than being quietly pre-resolved on one of them.
        row["SIDE"] = t["SIDE"]
        rows.append(row)
    return rows


def check(c: model.Corpus, flat: list[dict]) -> list[str]:
    """The flat table is only useful if it is genuinely equivalent AND still carries the
    awkward shapes. A denormalization that quietly dropped the orphan row would make
    invariance trivially true."""
    bad = []
    declared = set(c.tables["TRADE_FLAT"].columns)
    for i, r in enumerate(flat):
        unknown = set(r) - declared
        if unknown:
            bad.append(f"TRADE_FLAT[{i}]: columns not in schema: {sorted(unknown)}")
    if len(flat) != len(seed.TABLES["TRADE"]):
        bad.append(f"TRADE_FLAT has {len(flat)} rows, TRADE has "
                   f"{len(seed.TABLES['TRADE'])} — denormalization must not change "
                   f"cardinality for a to-one join")
    if not any(r["CPTY_ID"] is None for r in flat):
        bad.append("no NULL CPTY_ID — the orphan counterparty (A1) did not survive "
                   "denormalization, so invariance would not test outer-join semantics")
    if not any(r["TRADER_ID"] is None for r in flat):
        bad.append("no NULL TRADER_ID — the NULL foreign key (A2) did not survive")
    if not any(r["INSTR_NAME"] is not None for r in flat):
        bad.append("no instrument names resolved — the join is not working")
    return bad


# Which trades have their counterparty cache POPULATED. Everything else keeps only the
# fallback FK, so the Otherwise branch is what produces its counterparty.
#
# Chosen deliberately rather than by a modulus: TRD-0007's counterparty does not exist at
# all, so it must come back NULL through BOTH branches -- Otherwise must not invent one.
_CACHED = {"TRD-0001", "TRD-0002", "TRD-0003", "TRD-0010", "TRD-0013"}


def partial(c: model.Corpus, tables: dict[str, list[dict]]) -> list[dict]:
    """TRADE_FLAT_PARTIAL: the FK always present, the inline cache present for some."""
    rows = []
    for t in tables["TRADE"]:
        cached = t["TRADE_ID"] in _CACHED
        cpty = next((x for x in tables["COUNTERPARTY"]
                     if x["COUNTERPARTY_ID"] == t["COUNTERPARTY_ID"]), None)
        rows.append(dict(
            TRADE_ID=t["TRADE_ID"], NOTIONAL=t["NOTIONAL"], STATUS=t["STATUS"],
            # The FK is carried verbatim, INCLUDING the dangling one — the fallback join
            # then finds nothing, which is the correct answer, not a reason to skip it.
            CPTY_FK=t["COUNTERPARTY_ID"],
            CPTY_ID_INLINE=(cpty or {}).get("COUNTERPARTY_ID") if cached else None,
            CPTY_NAME_INLINE=(cpty or {}).get("LEGAL_NAME") if cached else None,
            CPTY_LEI_INLINE=(cpty or {}).get("LEI") if cached else None,
        ))
    return rows


def check_partial(rows: list[dict]) -> list[str]:
    bad = []
    if not any(r["CPTY_ID_INLINE"] for r in rows):
        bad.append("no row has the counterparty cache populated; the embedded branch "
                   "would never be taken")
    if not any(r["CPTY_ID_INLINE"] is None and r["CPTY_FK"] for r in rows):
        bad.append("no row has an empty cache with a usable FK; the Otherwise branch "
                   "would never be taken, and the two mappings would agree trivially")
    return bad


def all_tables(c: model.Corpus) -> dict[str, list[dict]]:
    """The seed plus every DERIVED table. Each consumer of the corpus data goes through
    here, so a derived table can never be stale relative to its source — it does not
    exist until it is recomputed.

    Both derivations exist to make an invariance claim honest: TRADE_FLAT (L2) varies how
    a row is assembled, the partitions (L4) vary where rows come from.
    """
    import aggregate
    import partition
    import views

    tables = dict(seed.TABLES)
    tables["TRADE_FLAT"] = build(c, seed.TABLES)
    tables.update(partition.build(c, seed.TABLES))
    tables["TRADE_FLAT_PARTIAL"] = partial(c, seed.TABLES)
    tables["TRADE_BY_BOOK"] = aggregate.build(c, seed.TABLES)
    # Views are computed for the ORACLE only. They are not physical tables: nothing seeds
    # them, no DDL creates them, and the engine inlines the GROUP BY. Emitting one as
    # ###Data would create a real table that shadows the view and silently stop testing
    # the aggregation at all — so emit.py and differential.py both skip anything in
    # c.views.
    for name, v in c.views.items():
        tables[name] = views.build(c, v, tables)
    return tables


if __name__ == "__main__":
    c = model.load()
    rows = build(c, seed.TABLES)
    problems = check(c, rows)
    nulls = sum(1 for r in rows for v in r.values() if v is None)
    print(f"TRADE_FLAT: {len(rows)} rows x {len(COLUMNS) + 1} columns, "
          f"{nulls} NULL cells")
    for r in rows:
        if r["CPTY_ID"] is None or r["TRADER_ID"] is None:
            print(f"  {r['TRADE_ID']}  cpty={r['CPTY_NAME']}  trader={r['TRADER_LAST']}"
                  f"  instr={r['INSTR_NAME']}")
    print(f"\nself-check: {'OK' if not problems else str(len(problems)) + ' PROBLEMS'}")
    for p in problems:
        print("  -", p)
