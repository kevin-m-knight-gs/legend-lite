"""
Reference evaluator — computes what a service query must return, independently of Legend.

This is the thing that makes the corpus worth having. Without it a test can only assert
that *something* came back, which is what the 12 stress services did before this and why
they could not have detected a wrong answer.

It is deliberately NOT a Legend implementation. It models exactly the algebra the service
queries use, and refuses anything else:

  all()            every row of the class's ~mainTable
  to-one nav       LEFT OUTER JOIN — this is the semantic that matters most. Legend
                   compiles to-one property navigation to an outer join, so a row whose
                   foreign key is NULL or dangling SURVIVES with NULLs in the navigated
                   columns. An engine that emitted an inner join would silently drop
                   rows, and the row counts here are what catch it.
  projection       column value, or NULL if any hop on the path failed
  filter           three-valued: NULL compared to anything is UNKNOWN, and UNKNOWN rows
                   are excluded. `status == 'EXECUTED'` must not match a NULL status.
  sort / limit     ordering is computed but see `ordered` below

Where it refuses:
  - A to-many hop would fan the row set out. The service queries contain none (verified
    by query.py), and rather than guess at Legend's fan-out semantics the resolver
    raises. When L1 adds to-many queries this is the place to extend, deliberately.
  - A join whose target key is not unique would also fan out. Asserted, not assumed.

On ordering: EqualToJson compares arrays UNORDERED (the comparator is
NULL_MISSING_EQUIVALENT_AND_UNORDERED_ARRAYS), so an expectation cannot pin row order.
Every case therefore reports `ordered=False` and the row multiset is what is asserted.
A sort is still worth executing — `->sort()->limit()` changes WHICH rows come back, and
that IS asserted — but the corpus must never claim to test ordering through this harness.
"""
from __future__ import annotations

from model import Corpus
from query import Pred, Spec

def _timestamp(v: str) -> str:
    """Legend renders a DateTime column as ISO-8601 with nanoseconds and a numeric UTC
    offset — "2024-06-03T19:00:00.000000000+0000" — not as the "YYYY-MM-DD HH:MM:SS" the
    seed is written in. A DATE column, by contrast, renders as plain "2024-06-03".

    Determined empirically: the first run of this corpus passed every service that
    projected no timestamp and failed every service that projected one, with Q7 showing
    the two forms side by side. It is not inferred from the serializer source.
    """
    date, _, clock = v.partition(" ")
    return f"{date}T{clock}.000000000+0000"


class Fanout(Exception):
    """Raised when evaluating would require fan-out semantics we refuse to guess at."""


def _index(rows: list[dict], key: str, table: str, join: str) -> dict:
    idx = {}
    for r in rows:
        v = r.get(key)
        if v is None:
            continue
        if v in idx:
            raise Fanout(
                f"join {join} targets {table}.{key} which is not unique (value {v!r}); "
                f"the navigation would fan out")
        idx[v] = r
    return idx


def walk(c: Corpus, data: dict[str, list[dict]], row: dict,
         hops: list[tuple[str, str, str, str, str]]) -> dict | None:
    """Follow the hops from `row`. Returns the landed row, or None if the chain broke —
    either because a key was NULL (A2) or because it matched nothing (A1, A11)."""
    cur = row
    for join, ftab, fcol, ttab, tcol in hops:
        if cur is None:
            return None
        key = cur.get(fcol)
        if key is None:
            return None
        idx = _index(data[ttab], tcol, ttab, join)
        cur = idx.get(key)
    return cur


def _cmp(op: str, left, right) -> bool:
    """Three-valued: a NULL operand yields UNKNOWN, which is not true."""
    if left is None or right is None:
        return False
    if op == "==":
        return left == right
    if op == "!=":
        return left != right
    if op == "<":
        return left < right
    if op == "<=":
        return left <= right
    if op == ">":
        return left > right
    if op == ">=":
        return left >= right
    raise ValueError(f"unhandled operator {op}")


def _value(c: Corpus, data, row, root: str, path: list[str]):
    table, col, hops = c.resolve(root, path)
    landed = walk(c, data, row, hops)
    return None if landed is None else landed.get(col)


def evaluate(c: Corpus, spec: Spec, data: dict[str, list[dict]]) -> list[dict]:
    """Returns the rows the service must produce, as alias -> python value."""
    if any(c.to_many_on(spec.root, p.path) for p in spec.projections):
        raise Fanout(f"{spec.short}: a projection crosses a to-many association")

    base = data[c.main_table[spec.root]]
    kept = [r for r in base
            if all(_cmp(f.op, _value(c, data, r, spec.root, f.path), f.value)
                   for f in spec.filters)]

    out = [{p.alias: _value(c, data, r, spec.root, p.path) for p in spec.projections}
           for r in kept]

    if spec.sort:
        alias, desc = spec.sort
        # NULLs sort last ascending / first descending is dialect-dependent, so a case
        # whose sort column contains NULLs cannot have a stable limit. Refuse it.
        if any(r[alias] is None for r in out):
            raise Fanout(f"{spec.short}: sort column {alias!r} contains NULL, so the "
                         f"rows surviving ->limit() are dialect-dependent")
        out.sort(key=lambda r: r[alias], reverse=desc)
    if spec.limit is not None:
        # A tie spanning the limit boundary makes the surviving set ambiguous.
        if spec.sort and len(out) > spec.limit:
            alias = spec.sort[0]
            if out[spec.limit - 1][alias] == out[spec.limit][alias]:
                raise Fanout(f"{spec.short}: a tie on {alias!r} straddles ->limit("
                             f"{spec.limit}), so the surviving rows are ambiguous")
        out = out[:spec.limit]
    return out


# ------------------------------------------------------------------ rendering

def render(value, kind: str):
    """Python value -> the JSON value Legend is expected to emit."""
    if value is None:
        return None
    if kind == "bool":
        return bool(value)
    if kind == "int":
        return int(value)
    if kind == "float":
        return float(value)
    if kind == "timestamp":
        return _timestamp(value)
    if kind in ("string", "date"):
        return value
    raise ValueError(f"unhandled kind {kind}")


def kinds(c: Corpus, spec: Spec) -> dict[str, str]:
    out = {}
    for p in spec.projections:
        table, col, _ = c.resolve(spec.root, p.path)
        out[p.alias] = c.tables[table].columns[col].kind
    return out


def as_json_rows(c: Corpus, spec: Spec, rows: list[dict]) -> list[dict]:
    k = kinds(c, spec)
    return [{a: render(v, k[a]) for a, v in r.items()} for r in rows]


if __name__ == "__main__":
    import json
    import model
    import query
    import seed

    c = model.load()
    specs = query.load()
    print(f"{'service':<36} {'rows':>5}  note")
    ok = 0
    for s in specs:
        try:
            rows = evaluate(c, s, seed.TABLES)
            js = as_json_rows(c, s, rows)
            nulls = sum(1 for r in js for v in r.values() if v is None)
            cells = sum(len(r) for r in js)
            print(f"  {s.short:<34} {len(rows):>5}  "
                  f"{nulls}/{cells} cells NULL ({nulls / cells:.0%})" if cells else
                  f"  {s.short:<34} {len(rows):>5}  EMPTY")
            ok += 1
        except Fanout as e:
            print(f"  {s.short:<34} {'--':>5}  REFUSED: {e}")
    print(f"\n{ok}/{len(specs)} evaluated")
    sample = evaluate(c, specs[-1], seed.TABLES)
    print("\nsample row (Q11, the 9-join monster):")
    print(json.dumps(as_json_rows(c, specs[-1], sample)[6], indent=1)[:900])
