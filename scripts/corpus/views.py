"""
Computes the rows of a Legend View — the GROUP BY the engine will inline.

A View is not a database view. No DDL is created for it and nothing seeds it; the engine
folds the aggregation into the SQL it generates. So the oracle cannot read the answer from
anywhere — it has to do the grouping itself, which is what this does.

The interesting property is what a grouping does NOT produce. BK-LEGACY has no trades, so
it forms no group and is simply absent from the rollup. F2_BookChildCounts asks what looks
like the same question by outer-joining from BOOK and gets a row for BK-LEGACY. Both
answers are defensible; they differ on exactly the empty case, and the corpus asserts both.
"""
from __future__ import annotations

import model


def build(c: model.Corpus, view: model.View,
          tables: dict[str, list[dict]]) -> list[dict]:
    # A view over an UNSEEDED base is empty, not an error. Surface-coverage tables carry
    # composite keys precisely so the seeder skips them, and a view declared over one is a
    # declaration being exercised for its grammar rather than its rows.
    groups: dict[tuple, list[dict]] = {}
    for r in tables.get(view.base) or []:
        key = tuple(r.get(g) for g in view.group_by)
        groups.setdefault(key, []).append(r)

    rows = []
    for key, members in groups.items():
        row = {}
        for name, col in view.columns.items():
            vals = [m.get(col.source) for m in members]
            present = [v for v in vals if v is not None]
            if col.agg is None:
                row[name] = members[0].get(col.source)
            elif col.agg == "count":
                # COUNT of a column counts NON-NULL values, which is not the same as the
                # size of the group. The distinction only shows up when the counted column
                # is nullable; TRADE_ID is not, so this is stated rather than exercised.
                row[name] = len(present)
            elif col.agg == "sum":
                row[name] = sum(present) if present else None
            elif col.agg == "max":
                row[name] = max(present) if present else None
            elif col.agg == "min":
                row[name] = min(present) if present else None
            else:
                raise SystemExit(f"unhandled view aggregate {col.agg!r}")
        rows.append(row)
    return rows


def check(c: model.Corpus, name: str, rows: list[dict],
          tables: dict[str, list[dict]]) -> list[str]:
    """Aggregate decomposition: the parts must sum to the whole. This is a metamorphic
    relation, not an oracle — it holds regardless of what the individual group totals are,
    so it catches a grouping error without anyone computing the right answer by hand."""
    bad = []
    v = c.views[name]
    base = tables[v.base]
    for col_name, col in v.columns.items():
        if col.agg == "count":
            total = sum(r[col_name] for r in rows)
            expected = sum(1 for r in base if r.get(col.source) is not None)
            if total != expected:
                bad.append(f"{name}.{col_name}: groups sum to {total}, base table has "
                           f"{expected} non-null {col.source}")
        elif col.agg == "sum":
            total = round(sum(r[col_name] for r in rows if r[col_name] is not None), 6)
            expected = round(sum(r[col.source] for r in base
                                 if r.get(col.source) is not None), 6)
            if total != expected:
                bad.append(f"{name}.{col_name}: groups sum to {total}, base sums to "
                           f"{expected}")
    if not rows:
        bad.append(f"{name} produced no groups at all")
    return bad


if __name__ == "__main__":
    import flat
    import seed

    c = model.load()
    tables = flat.all_tables(c)
    for name, v in c.views.items():
        rows = build(c, v, tables)
        print(f"{name}: {len(rows)} groups from {len(tables[v.base])} base rows")
        for r in sorted(rows, key=lambda x: str(x[v.group_by[0]])):
            print("   ", r)
        problems = check(c, name, rows, tables)
        print(f"  self-check: {'OK' if not problems else problems}")
        books = {b["BOOK_ID"] for b in tables["BOOK"]}
        grouped = {r["BOOK_ID"] for r in rows}
        print(f"  books with NO group (absent, not zero): {sorted(books - grouped)}")
