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

import re

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


def walk_many(c: Corpus, data: dict[str, list[dict]], row: dict,
              hops: list[tuple[str, str, str, str, str]]) -> list[dict]:
    """Follow hops that MAY fan out, returning every landed row.

    This is the counterpart to walk(): where a to-one navigation lands on at most one row,
    a to-many lands on a set, and the set may be EMPTY. The empty case is the interesting
    one — it is where `->count()` over a LEFT OUTER JOIN is prone to returning 1 (one
    all-NULL joined row) instead of 0.
    """
    cur = [row]
    for _join, _ftab, fcol, ttab, tcol in hops:
        nxt = []
        for r in cur:
            key = r.get(fcol)
            if key is None:
                continue
            nxt.extend(x for x in data[ttab] if x.get(tcol) == key)
        cur = nxt
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


def _value(c: Corpus, data, row, root: str, path: list[str], args=()):
    hit = c.resolve_derived(root, path)
    if hit is not None:
        return _derived(c, data, row, root, path, hit, args)
    table, col, hops = c.resolve(root, path)
    landed = walk(c, data, row, hops)
    raw = None if landed is None else landed.get(col)
    mapping = c.enum_props.get((c.owner_of(root, path), path[-1]))
    if mapping is None or raw is None:
        return raw
    # A source code with no EnumerationMapping entry yields NULL — silently, and
    # indistinguishably from a NULL source column. Established empirically in
    # repro/unmapped-enum/, not assumed: it is the kind of behaviour that could equally
    # have been an error or a pass-through of the raw code.
    #
    # Worth noting that the property is declared [1] and still comes back null, so the
    # multiplicity is not enforced on this path.
    return c.enum_maps[mapping].get(raw)


# ---------------------------------------------------- derived-property evaluation

_TOKEN = re.compile(
    r"\s*(->orElse\(\s*-?\d+(?:\.\d+)?\s*\)|->\w+\(\)|\$this\.\w+|\$\w+"
    r"|[-+*/()]|-?\d+\.\d+|-?\d+)")


def _tokenise(expr: str) -> list[str]:
    out, i = [], 0
    while i < len(expr):
        m = _TOKEN.match(expr, i)
        if not m:
            if expr[i].isspace():
                i += 1
                continue
            raise Unsupported(f"cannot tokenise derived expression at {expr[i:][:40]!r}")
        out.append(m.group(1))
        i = m.end()
    return out


class Unsupported(Exception):
    """The derived expression is outside the grammar the oracle models.

    Kept narrow on purpose. Widening it to accept an expression we cannot faithfully
    evaluate would mean shipping an expectation that encodes a guess, which is the exact
    failure this whole apparatus exists to prevent.
    """


class _Eval:
    """Recursive descent over:

        expr   := term (('+'|'-') term)*
        term   := factor (('*'|'/') factor)*
        factor := number | '(' expr ')' | '$this.'ident postfix*
        postfix:= '->isEmpty()' | '->isNotEmpty()' | '->orElse(number)'

    NULL propagates through arithmetic as it does in SQL: any NULL operand makes the
    result NULL. That path is reachable here but NOT via a Pure derived property, because
    Pure rejects `[0..1] + [0..1]` at compile time -- plus requires [1]. Optionality has to
    be discharged with ->orElse before the arithmetic, which is why the corpus's netCost
    is written that way. isEmpty/isNotEmpty/orElse are the only things that inspect NULL.
    """

    def __init__(self, tokens, lookup, params=None):
        self.t, self.i, self.lookup = tokens, 0, lookup
        self._params = params or {}

    def param(self, name):
        if name not in self._params:
            raise Unsupported(f"unbound parameter ${name} in derived expression")
        return self._params[name]

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self):
        v = self.peek()
        self.i += 1
        return v

    def expr(self):
        v = self.term()
        while self.peek() in ("+", "-"):
            op = self.take()
            r = self.term()
            v = None if v is None or r is None else (v + r if op == "+" else v - r)
        return v

    def term(self):
        v = self.factor()
        while self.peek() in ("*", "/"):
            op = self.take()
            r = self.factor()
            if v is None or r is None:
                v = None
            elif op == "*":
                v = v * r
            else:
                v = None if r == 0 else v / r
        return v

    def factor(self):
        tok = self.take()
        if tok == "(":
            v = self.expr()
            if self.take() != ")":
                raise Unsupported("unbalanced parentheses in derived expression")
        elif tok and tok.startswith("$this."):
            v = self.lookup(tok[len("$this."):])
        elif tok and tok.startswith("$"):
            v = self.param(tok[1:])
        elif tok and re.fullmatch(r"\d+\.\d+", tok):
            v = float(tok)
        elif tok and tok.isdigit():
            v = int(tok)
        else:
            raise Unsupported(f"unexpected token {tok!r} in derived expression")
        while True:
            nxt = self.peek()
            if nxt in ("->isEmpty()", "->isNotEmpty()"):
                v = (v is None) if self.take() == "->isEmpty()" else (v is not None)
            elif nxt is not None and nxt.startswith("->orElse("):
                default = self.take()[len("->orElse("):-1].strip()
                if v is None:
                    v = float(default) if "." in default else int(default)
            else:
                return v


def _derived(c: Corpus, data, row, root: str, path: list[str], hit, args=()):
    hops, cls, d = hit
    if len(args) != len(d.params):
        raise Unsupported(
            f"{cls}.{d.name} takes {len(d.params)} argument(s), given {len(args)}")
    bound = dict(zip(d.params, args))
    landed = walk(c, data, row, hops) if hops else row
    if landed is None:
        return None

    def lookup(prop: str):
        col = c.columns.get(cls, {}).get(prop)
        if col is None:
            raise Unsupported(f"{cls}.{prop} referenced by derived property "
                              f"{d.name!r} is not a mapped column")
        raw = landed.get(col)
        mapping = c.enum_props.get((cls, prop))
        return c.enum_maps[mapping].get(raw) if mapping and raw is not None else raw

    e = _Eval(_tokenise(d.expr), lookup, bound)
    v = e.expr()
    if e.peek() is not None:
        raise Unsupported(f"trailing tokens in derived expression {d.expr!r}")
    return v


def _agg(c: Corpus, data, row, root: str, proj):
    hops, _target = c.resolve_assoc(root, proj.path)
    landed = walk_many(c, data, row, hops)
    if proj.agg == "count":
        # An entity with no children counts 0. Stated plainly because this is the
        # assertion, not an implementation detail.
        return len(landed)
    raise Fanout(f"unhandled aggregate {proj.agg!r}")


# Legend's default infinity date. A business-temporal row carrying it in its THRU column
# is the CURRENT version, and that is what `%latest` selects.
INFINITY = "9999-12-31"


def _rows_for(c: Corpus, root: str, data: dict[str, list[dict]]) -> list[dict]:
    """Every row of the class, whether it lives in one table or several.

    A union-mapped class is the concatenation of its members in declared order. The
    ordering does not matter to any assertion here — EqualToJson compares unordered — but
    concatenating rather than merging is deliberate: a duplicate across partitions must
    show up as a duplicate row, not be silently collapsed.
    """
    members = c.unions.get(root)
    if members:
        return [r for m in members for r in data[m]]
    return data[c.main_table[root]]


def _mapping_filtered(c: Corpus, root: str, rows: list[dict]) -> list[dict]:
    """A store filter attached to the class mapping narrows what all() can even see.

    It is applied BEFORE any query predicate and before milestoning, which is the whole
    point: the class is defined as the filtered subset, so no query can widen it back.
    """
    name = c.class_filter.get(root)
    if name is None:
        return rows
    _table, col, op, val = c.filters[name]
    sql_to_py = {"=": "==", "<>": "!="}
    return [r for r in rows if _cmp(sql_to_py.get(op, op), r.get(col), val)]


def _milestoned(c: Corpus, spec: Spec, rows: list[dict]) -> list[dict]:
    """Apply the business-milestoning predicate for a temporal root.

    The interval is [FROM, THRU) — inclusive at the start, EXCLUSIVE at the end. That
    boundary is the whole reason CP-0003's version change is seeded exactly on
    2024-06-07: an implementation that made THRU inclusive returns the OLD rating on the
    day the new one took effect, which is a wrong answer that looks entirely reasonable.
    """
    klass = c.classes.get(spec.root)
    temporal = klass.temporal if klass else None
    table = c.tables[c.main_table[spec.root]]

    if temporal is None:
        if spec.as_of is not None:
            raise Fanout(f"{spec.short}: as-of date given for non-temporal {spec.root}")
        return rows
    if temporal != "businesstemporal":
        raise Fanout(f"{spec.short}: {temporal} milestoning is not modelled")
    if table.milestoning is None:
        raise Fanout(f"{spec.short}: {spec.root} is temporal but {table.name} declares "
                     f"no milestoning columns")
    if spec.as_of is None:
        raise Fanout(f"{spec.short}: {spec.root} is business-temporal, so all() needs a "
                     f"business date")

    frm, thru = table.milestoning.frm, table.milestoning.thru
    if spec.as_of == "latest":
        return [r for r in rows if r.get(thru) == INFINITY]
    return [r for r in rows
            if r.get(frm) is not None and r.get(frm) <= spec.as_of
            and (r.get(thru) is None or spec.as_of < r.get(thru))]


def evaluate(c: Corpus, spec: Spec, data: dict[str, list[dict]]) -> list[dict]:
    """Returns the rows the service must produce, as alias -> python value."""
    plain = [p for p in spec.projections if p.agg is None]
    if any(c.to_many_on(spec.root, p.path) for p in plain):
        raise Fanout(f"{spec.short}: a non-aggregate projection crosses a to-many "
                     f"association, which would fan the row set out")

    base = _mapping_filtered(c, spec.root, _rows_for(c, spec.root, data))
    base = _milestoned(c, spec, base)
    kept = [r for r in base
            if all(_cmp(f.op, _value(c, data, r, spec.root, f.path), f.value)
                   for f in spec.filters)]

    out = [{p.alias: (_agg(c, data, r, spec.root, p) if p.agg
                      else _value(c, data, r, spec.root, p.path, p.args))
            for p in spec.projections}
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
        if p.agg == "count":
            out[p.alias] = "int"
            continue
        hit = c.resolve_derived(spec.root, p.path)
        if hit is not None:
            out[p.alias] = {"Float": "float", "Integer": "int", "Boolean": "bool",
                            "String": "string"}[hit[2].type]
            continue
        if c.enum_props.get((c.owner_of(spec.root, p.path), p.path[-1])):
            out[p.alias] = "string"     # an enum renders as its VALUE NAME
            continue
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
