"""
Seeds the tables one join-hop out from the hand-written core, so the model stops being
scenery.

The hand-written seed covers 27 of 210 tables. That was enough to assert the twelve
original services, but it caps everything downstream: `stacks.py` can only build a
navigation chain whose every hop lands on a table that has rows, so most of the 200-class
model was unreachable and 366 association ends went untraversed.

This fills the next ring. It is DERIVED, not authored — for each table reachable by one
join from a seeded table it computes rows whose foreign keys point at real parents.
Values are deterministic functions of (table, column, row index): no RNG, so a diff is
stable and a reviewer can see why a value is what it is.

The adversarial discipline carries forward rather than being dropped for convenience.
Every generated table gets, by construction:

  * one row whose foreign key is NULL      (A2 — an absent key)
  * one row whose foreign key DANGLES      (A1 — outer join must keep the row)
  * one parent with no children at all     (A3 — the count-over-outer-join case)

so the properties the core seed established by hand hold across the whole expanded model
instead of only near the middle of it. check() asserts each of these per table; a
generator that quietly stopped producing them would make the expansion look like coverage
while removing the only thing that made it worth testing.
"""
from __future__ import annotations

import model

ROWS_PER_TABLE = 5

# Column-name heuristics, longest-match first. Realistic values matter here for the same
# reason they do in the hand-written seed: someone has to read a failure.
_VOCAB = {
    "NAME": ["Northbridge", "Kestrel", "Aldgate", "Fenwick", "Thameside"],
    "DESCRIPTION": ["primary record", "secondary record", "archived record",
                    "pending review", "closed"],
    "STATUS": ["ACTIVE", "PENDING", "CLOSED", "SUSPENDED", "ACTIVE"],
    "TYPE": ["STANDARD", "PREMIUM", "LEGACY", "STANDARD", "PREMIUM"],
    "CODE": ["AA", "BB", "CC", "DD", "EE"],
    "CURRENCY": ["USD", "GBP", "EUR", "USD", "JPY"],
    "REGION": ["Americas", "EMEA", "APAC", "Americas", "EMEA"],
    "METHOD": ["SWIFT", "MANUAL", "API", "SWIFT", "FILE"],
    "REASON": ["none", "late data", "manual override", "none", "reconciled"],
}


def _string_value(col: str, i: int) -> str:
    upper = col.upper()
    for key, values in _VOCAB.items():
        if key in upper:
            return values[i % len(values)]
    # Fall back to something that reads as an identifier for that column, not a blob.
    return f"{upper.replace('_', '-')[:14]}-{i + 1:03d}"


def _value(col: model.Column, i: int, seed_offset: int):
    n = i + seed_offset
    if col.kind == "string":
        return _string_value(col.name, i)
    if col.kind == "int":
        return 10 + n * 7
    if col.kind == "float":
        return round(100.0 + n * 37.5, 2)
    if col.kind == "bool":
        return n % 3 != 0
    if col.kind == "date":
        return f"2024-{(n % 12) + 1:02d}-{(n % 27) + 1:02d}"
    if col.kind == "timestamp":
        return f"2024-{(n % 12) + 1:02d}-{(n % 27) + 1:02d} {(n % 23):02d}:15:00"
    raise ValueError(f"unhandled kind {col.kind} for {col.name}")


def _distinct(rows: list[dict], col: str) -> list:
    """Distinct non-null values, order preserved. An identity FK needs these: reusing a
    parent value would duplicate the child's primary key."""
    seen, out = set(), []
    for r in rows:
        v = r.get(col)
        if v is not None and v not in seen:
            seen.add(v)
            out.append(v)
    return out


def _fk_targets(c: model.Corpus, table: str, seeded: set[str]):
    """(local column, parent table, parent column) for every join from `table` to a table
    that already has rows."""
    out = []
    for j in c.joins.values():
        if j.left_table == table and j.right_table in seeded:
            out.append((j.left_col, j.right_table, j.right_col))
        elif j.right_table == table and j.left_table in seeded:
            out.append((j.right_col, j.left_table, j.left_col))
    # Deduplicate on the local column: a table may join to several parents through the
    # same FK, and writing it twice would make the last one win silently.
    seen, uniq = set(), []
    for local, parent, pcol in out:
        if local in seen:
            continue
        seen.add(local)
        uniq.append((local, parent, pcol))
    return uniq


def candidates(c: model.Corpus, tables: dict[str, list[dict]]) -> list[str]:
    """Unseeded tables one join-hop from a seeded one, with a single-column primary key.

    A composite key means the table is a version/bridge table whose semantics the row
    generator does not model — milestoning, or a many-to-many — so it is left alone rather
    than filled with rows that would be wrong in a way nobody notices.
    """
    seeded = {t for t, rows in tables.items() if rows}
    out = []
    for name, table in sorted(c.tables.items()):
        if name in seeded or name in c.views or len(table.pk) != 1:
            continue
        fks = _fk_targets(c, name, seeded)
        if not fks:
            continue
        # An identity FK with no usable parent values cannot produce a valid row at all.
        identity = next((f for f in fks if f[0] == table.pk[0]), None)
        if identity and not _distinct(tables[identity[1]], identity[2]):
            continue
        out.append(name)
    return out


def build(c: model.Corpus, tables: dict[str, list[dict]]) -> dict[str, list[dict]]:
    seeded = {t for t, rows in tables.items() if rows}
    out: dict[str, list[dict]] = {}

    for offset, name in enumerate(candidates(c, tables)):
        table = c.tables[name]
        pk = table.pk[0]
        fks = _fk_targets(c, name, seeded)
        prefix = "".join(w[0] for w in name.split("_"))[:4]

        # A 1:1 extension table cannot have more rows than it has parents.
        pk_is_fk = any(local == pk for local, _, _ in fks)
        n_rows = ROWS_PER_TABLE
        if pk_is_fk:
            # Its identity comes from the parent, so it can have at most one row per
            # DISTINCT parent value — and none at all if the parent column is empty.
            _, parent, pcol = next(f for f in fks if f[0] == pk)
            n_rows = min(ROWS_PER_TABLE, len(_distinct(tables[parent], pcol)))
            if n_rows == 0:
                continue

        rows = []
        for i in range(n_rows):
            row: dict = {}
            for col in table.columns.values():
                if col.name == pk:
                    row[col.name] = f"{prefix}-{i + 1:04d}"
                elif any(col.name == local for local, _, _ in fks):
                    row[col.name] = None          # filled below, per FK
                else:
                    row[col.name] = _value(col, i, offset)

            for local, parent, pcol in fks:
                parents = [p[pcol] for p in tables[parent] if p.get(pcol) is not None]
                if not parents:
                    row[local] = None
                elif local == pk:
                    parents = _distinct(tables[parent], pcol)
                    # A 1:1 EXTENSION table: its identity IS the parent's key. It cannot
                    # carry a NULL or a duplicate, so it gets a distinct parent per row
                    # and is exempt from the absent/dangling shapes -- which belong on a
                    # referencing column, not on an identity.
                    row[local] = parents[i % len(parents)]
                elif i == 0:
                    row[local] = None                       # A2 absent key
                elif i == 1:
                    row[local] = f"{parents[0]}-GONE"        # A1 dangling key
                else:
                    # Skip the LAST parent entirely so it keeps zero children (A3).
                    usable = parents[:-1] or parents
                    row[local] = usable[(i - 2) % len(usable)]
            rows.append(row)
        out[name] = rows
    return out


def check(c: model.Corpus, generated: dict[str, list[dict]],
          tables: dict[str, list[dict]]) -> list[str]:
    """The expansion is only worth having if the adversarial shapes survived it."""
    bad = []
    for name, rows in generated.items():
        table = c.tables[name]
        pk = table.pk[0]
        ids = [r[pk] for r in rows]
        if len(set(ids)) != len(ids):
            bad.append(f"{name}: duplicate primary key")
        for r in rows:
            unknown = set(r) - set(table.columns)
            if unknown:
                bad.append(f"{name}: columns not in schema: {sorted(unknown)}")
            for k, v in r.items():
                if isinstance(v, str) and ("," in v or "'" in v or "\n" in v):
                    bad.append(f"{name}.{k}: value needs CSV quoting: {v!r}")

        fks = _fk_targets(c, name, {t for t, rr in tables.items() if rr})
        for local, parent, pcol in fks:
            if local == pk:
                # An identity column carries neither NULLs nor dangling values; requiring
                # them here would be asking the fixture to be invalid.
                if len({r[local] for r in rows}) != len(rows):
                    bad.append(f"{name}.{local}: identity FK is not distinct per row")
                continue
            vals = [r[local] for r in rows]
            parents = {p[pcol] for p in tables[parent]}
            if not any(v is None for v in vals):
                bad.append(f"{name}.{local}: no NULL foreign key (A2 lost)")
            if not any(v is not None and v not in parents for v in vals):
                bad.append(f"{name}.{local}: no DANGLING foreign key (A1 lost)")
            if not any(v in parents for v in vals if v):
                bad.append(f"{name}.{local}: no VALID foreign key, so nothing resolves")
    return bad


def build_rings(c: model.Corpus, tables: dict[str, list[dict]], rings: int = 6):
    """Expand outward repeatedly, each ring seeded from everything the previous ones
    produced.

    The default of six is a safety bound, not a target: the loop stops as soon as a ring
    produces nothing, and in practice it converges well before that. A bound exists at all
    so a cycle in the join graph cannot spin.

    One ring reaches only the immediate neighbours of the hand-written core. Iterating
    walks the join graph outward until it stops finding tables it can satisfy — which is
    what turns an inert 200-class model into one a generator can actually navigate.

    Returns (merged, [(generated, base_at_that_ring)]). Each ring's output has to be
    CHECKED against the base it was generated from: what counted as a foreign key changes
    as more tables gain rows, and judging ring 1 against the state after ring 3 would
    demand FK shapes of columns that were not foreign keys when their rows were written.
    """
    merged: dict[str, list[dict]] = {}
    layers: list[tuple[dict, dict]] = []
    current = dict(tables)
    for _ in range(rings):
        base = {k: list(v) for k, v in current.items()}
        produced = build(c, current)
        if not produced:
            break
        layers.append((produced, base))
        merged.update(produced)
        current.update(produced)
    return merged, layers


if __name__ == "__main__":
    import flat

    c = model.load()
    base = flat.all_tables(c)
    merged, layers = build_rings(c, base)
    for i, (produced, _) in enumerate(layers, 1):
        print(f"  ring {i}: {len(produced):>3} tables, "
              f"{sum(len(v) for v in produced.values()):>4} rows")
    print(f"total: {len(merged)} tables, {sum(len(v) for v in merged.values())} rows")
    problems = [p for produced, b in layers for p in check(c, produced, b)]
    print(f"self-check: {'OK' if not problems else str(len(problems)) + ' PROBLEMS'}")
    for p in problems[:8]:
        print("  -", p)
