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

import math
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


# ------------------------------------------------------- general join conditions
#
# A join whose condition is more than one equality cannot be indexed, so it is evaluated per
# candidate row. Correctness first: these are the joins the reader used to drop entirely, so
# there is no existing behaviour to preserve and no reason to be clever. The tables are five
# rows each.
def _operand(c: Corpus, node, binding: dict):
    """One side of a join comparison, read from whichever row its table is bound to."""
    tag, body = node
    if tag == "lit":
        return body
    if tag == "col":
        table, col = body
        row = binding.get(table)
        return None if row is None else row.get(col)
    if tag == "call":
        fn, args = body
        return _dynafunction(fn, [_operand(c, a, binding) for a in args])
    raise Unsupported(f"join operand {tag!r} has no evaluation rule")


def _condition(c: Corpus, node, binding: dict) -> bool:
    tag, body = node
    if tag == "and":
        return _condition(c, body[0], binding) and _condition(c, body[1], binding)
    if tag == "or":
        return _condition(c, body[0], binding) or _condition(c, body[1], binding)
    if tag == "null":
        operand, negated = body
        v = _operand(c, operand, binding)
        return (v is not None) if negated else (v is None)
    if tag == "cmp":
        left, op, right = body
        # Three-valued: a NULL operand makes the comparison UNKNOWN, and a join keeps only
        # pairs for which the condition is TRUE. Same rule the query predicates use, and the
        # same one F28 shows `!=` breaking on the query side.
        return _cmp({"=": "==", "<>": "!="}.get(op, op),
                    _operand(c, left, binding), _operand(c, right, binding))
    raise Unsupported(f"join condition node {tag!r} has no evaluation rule")


def _general_targets(c: Corpus, data, join, from_table: str, src: dict) -> list[dict]:
    """Every row on the far side of `join` that the condition pairs with `src`."""
    import rhs

    named = rhs.condition_tables(join.condition)
    a, b = join.tables
    to_table = b if from_table == a else a
    out = []
    for tgt in data.get(to_table, []):
        if "{target}" in named:
            binding = {from_table: src, "{target}": tgt}
        else:
            binding = {from_table: src, to_table: tgt}
        if _condition(c, join.condition, binding):
            out.append(tgt)
    return out


def walk(c: Corpus, data: dict[str, list[dict]], row: dict,
         hops: list[tuple[str, str, str, str, str]]) -> dict | None:
    """Follow the hops from `row`. Returns the landed row, or None if the chain broke —
    either because a key was NULL (A2) or because it matched nothing (A1, A11)."""
    cur = row
    for join, ftab, fcol, ttab, tcol in hops:
        if cur is None:
            return None
        if fcol is None:                       # a general condition: no key to index on
            landed = _general_targets(c, data, c.joins[join], ftab, cur)
            if len(landed) > 1:
                raise Fanout(
                    f"join {join} pairs one row of {ftab} with {len(landed)} rows of "
                    f"{ttab}; the navigation would fan out")
            cur = landed[0] if landed else None
            continue
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


def _xstore_value(c: Corpus, data, row, root: str, path: list[str]):
    link = XSTORE_LINKS.get((root, path[0]))
    if link is None:
        return None, False
    from_col, table, to_col, target = link
    key = row.get(from_col)
    landed = next((r for r in data[table] if r.get(to_col) == key), None) if key else None
    if landed is None:
        return None, True
    col = c.columns.get(target, {}).get(path[1])
    if col is None:
        raise Unsupported(f"{target}.{path[1]} is not a mapped column")
    return landed.get(col), True


def _value(c: Corpus, data, row, root: str, path: list[str], args=(), func=None):
    if len(path) == 2 and (root, path[0]) in XSTORE_LINKS:
        v, handled = _xstore_value(c, data, row, root, path)
        if handled:
            return v
    if func:
        return _call(c, data, row, root, func, args)
    hit = c.resolve_derived(root, path)
    if hit is not None:
        return _derived(c, data, row, root, path, hit, args)
    owner = c.owner_of(root, path)
    src = c.json_backed.get(owner)
    if src is not None:
        return _json_property(c, data, row, root, path, src)
    # A DYNAFUNCTION is now checked before the plain-column path rather than after it,
    # because its arguments no longer have to be columns of one row: an argument may be a
    # chain, a literal, or another call. The expression tree is walked, each argument
    # evaluated in its own right, and the function applied to the results.
    dyn = c.dyna.get((owner, path[-1]))
    if dyn is not None:
        fn, fnargs = dyn
        # Plain-column arguments are read from the row the property's OWNER sits on, so a
        # dynafunction inside an embedded block reads the embedded parent's row.
        base = row
        if len(path) > 1:
            prefix, _t = c.owner_hops(root, path[:-1])
            base = walk(c, data, row, prefix)
        return _dynafunction(fn, [_arg_value(c, data, base, owner, path[-1], a)
                                  for a in fnargs])
    chain = c.chains.get((owner, path[-1]))
    if chain is not None:
        return _chain_value(c, data, row, root, path, chain)
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


# ------------------------------------------------------- Binding-payload evaluation
#
# A Binding transformer reads a complex property out of one column of serialized JSON, so a
# sub-property is a KEY in that payload rather than a column. Deserialized here independently
# -- the same discipline as the dynafunctions: the expected value comes from what the payload
# MEANS, not from what the engine returned for it.
def _json_property(c: Corpus, data, row, root: str, path: list[str], src):
    import json

    table, col = src
    # Everything before the binding hop is ordinary navigation; the payload hangs off
    # whatever row that lands on.
    if len(path) > 2:
        _t, _c, prefix = c.resolve(root, path[:-2])
        row = walk(c, data, row, prefix)
        if row is None:
            return None
    raw = row.get(col) if row else None
    if raw is None:
        return None
    try:
        return json.loads(raw).get(path[-1])
    except (ValueError, AttributeError):
        # A payload that is not valid JSON is a SEED defect, not a value. Refusing beats
        # returning None, which would look like an absent key.
        raise Unsupported(f"{table}.{col} does not hold valid JSON: {raw!r:.60}")


# --------------------------------------------------------- join-chain evaluation
#
# A join chain reaches a value through JOINS, with no association involved -- so `resolve`,
# which walks associations, cannot find it. The joins are followed here directly.
#
# Direction is decided per hop by which side the current table sits on, because a Join
# declares two tables and says nothing about which is the source: `Join J(A.x = B.y)` is
# followed A->B from A and B->A from B. Guessing wrong would silently land on the wrong row
# rather than failing.
def _chain_hops(c: Corpus, start: str, joins: list[str]):
    hops, cur = [], start
    for name in joins:
        j = c.joins.get(name)
        if j is None:
            raise Unsupported(f"join {name!r} in a chain is not declared")
        if j.condition is not None:
            # No from/to columns: the pairing is a whole condition, so the hop carries None
            # in their place and walk() evaluates instead of indexing.
            a, b = j.tables
            if cur not in (a, b):
                raise Unsupported(
                    f"join {name!r} connects {a}/{b}, neither of which is {cur} -- the "
                    f"chain does not compose")
            nxt = b if cur == a else a
            hops.append((name, cur, None, nxt, None))
            cur = nxt
            continue
        if j.left_table == cur:
            hops.append((name, j.left_table, j.left_col, j.right_table, j.right_col))
            cur = j.right_table
        elif j.right_table == cur:
            hops.append((name, j.right_table, j.right_col, j.left_table, j.left_col))
            cur = j.left_table
        else:
            raise Unsupported(
                f"join {name!r} connects {j.left_table}/{j.right_table}, neither of which "
                f"is {cur} -- the chain does not compose")
    return hops, cur


def _chain_value(c: Corpus, data, row, root: str, path: list[str], chain):
    joins, target_table, target_col = chain
    # Everything before the last step is ordinary association navigation; the chain hangs
    # off whatever row that lands on.
    if len(path) > 1:
        # owner_hops, not resolve: the prefix may end on an EMBEDDED property, which has no
        # column of its own and which resolve() therefore refuses. A chain inside an
        # embedded block departs from the parent's row, since the block adds no hop.
        prefix, _t = c.owner_hops(root, path[:-1])
        row = walk(c, data, row, prefix)
        if row is None:
            return None
    start = c.main_table.get(c.owner_of(root, path), "")
    hops, landed_table = _chain_hops(c, start, joins)
    if landed_table != target_table:
        raise Unsupported(
            f"chain ends at {landed_table} but the column is {target_table}.{target_col}")
    landed = walk(c, data, row, hops)
    return None if landed is None else landed.get(target_col)


# ------------------------------------------------------- dynafunction evaluation
#
# Implemented INDEPENDENTLY of legend-engine, which is the only thing that makes a
# dynafunction mapping testable at all. Reading the expected value out of the engine would
# make the assertion circular; these are written from what the function MEANS.
#
# Deliberately few. legend-engine's registry is 178 names, and each one added here is a
# separate opportunity to encode a subtly wrong belief about NULL handling or coercion --
# so the set grows only when something needs it, and every entry states its null rule.
#
# NULL semantics are the whole difficulty. SQL propagates NULL through most scalar
# functions, and the corpus guarantees a NULL in every column by construction (property
# A2), so every one of these will meet one.
def _arg_value(c: Corpus, data, base, owner: str, prop: str, node):
    """One argument of a dynafunction call, evaluated in its own right.

    `base` is the row the OWNER sits on. A plain column is read from it directly; a chain
    departs from the owner's main table; a nested call recurses. Splitting evaluation per
    argument is what makes `concat(@J | T.A, T.B)` expressible at all -- the previous shape
    read every argument off one row, so a chain could only ever be the whole expression.
    """
    tag, body = node
    if tag == "lit":
        return body
    if tag == "col":
        _table, col = body
        return None if base is None else base.get(col)
    if tag == "chain":
        # A one-element path, so _chain_value walks no prefix -- `base` IS the owner's row.
        return _chain_value(c, data, base, owner, [prop], body)
    if tag == "call":
        fn, args = body
        return _dynafunction(fn, [_arg_value(c, data, base, owner, prop, a) for a in args])
    raise Unsupported(f"dynafunction argument {tag!r} has no evaluation rule")


def _dynafunction(fn, vals):
    if fn == "concat":
        # concat over NULL yields THE OTHER ARGUMENT, not NULL.
        #
        # I asserted the opposite first, from SQL's `||` semantics, and the engine
        # disagreed -- correctly. Legend lowers `concat` to different SQL per dialect:
        #
        #   DuckDB / Snowflake / BigQuery   concat(a, b)   the FUNCTION, ignores NULL
        #   Postgres                        a || b         the OPERATOR, propagates NULL
        #
        # So this is dialect-dependent, and this corpus executes on the function dialects.
        # An oracle cannot be dialect-agnostic about concat, which is worth knowing before
        # the next dynafunction is added: the ones with an obvious mathematical meaning are
        # safe, and the string ones are not.
        # And when EVERY argument is NULL the result is the EMPTY STRING, not NULL.
        #
        # This rule had an unexamined edge: "ignores NULL" was written as "drop the nulls,
        # and if nothing is left return NULL". That last clause was a special case smuggled
        # in, not a consequence. Concatenating the empty sequence of strings is the empty
        # string -- that is the identity of concatenation, and deriving NULL from it would
        # be a second rule contradicting the first. The combination matrix found it because
        # only a BROKEN CHAIN makes both arguments null at once, and until the chain cells
        # existed no case in the corpus could reach it.
        return "".join(str(v) for v in vals if v is not None)
    if fn == "toUpper":
        return None if vals[0] is None else str(vals[0]).upper()
    if fn == "toLower":
        return None if vals[0] is None else str(vals[0]).lower()

    # ---- null-inspecting: total, never return NULL themselves ------------------------
    if fn == "isNull":
        return vals[0] is None
    if fn == "isNotNull":
        return vals[0] is not None
    if fn == "coalesce":
        return next((v for v in vals if v is not None), None)

    # ---- arithmetic: NULL propagates, as it does through every SQL scalar operator ----
    #
    # These are the SAFE ones, in the sense the concat comment above earns the right to
    # use: `a + b` means the same thing in every dialect this corpus can reach, so an
    # independent implementation is not a bet about lowering. The string functions are
    # where dialect divergence lives.
    if fn in ("plus", "times"):
        if any(v is None for v in vals):
            return None
        out = 0 if fn == "plus" else 1
        for v in vals:
            out = out + v if fn == "plus" else out * v
        return out
    if fn == "minus":
        return None if any(v is None for v in vals) else vals[0] - sum(vals[1:])
    if fn == "abs":
        return None if vals[0] is None else abs(vals[0])
    if fn == "sign":
        return None if vals[0] is None else (0 if vals[0] == 0 else
                                             (1 if vals[0] > 0 else -1))
    if fn == "sqrt":
        if vals[0] is None:
            return None
        if vals[0] < 0:
            # sqrt of a negative has no agreed answer -- an error in some dialects, NaN in
            # others. Refusing keeps the seed honest: if a negative ever reaches here the
            # corpus must change the data, not the expectation.
            raise Unsupported(f"sqrt({vals[0]}) is not defined; the seed must not produce "
                              f"a negative argument")
        return math.sqrt(vals[0])

    # ---- string, and therefore a bet about lowering rather than a fact ---------------
    if fn == "length":
        return None if vals[0] is None else len(str(vals[0]))
    if fn == "trim":
        # Whitespace at BOTH ends. ltrim/rtrim are deliberately not implemented here: they
        # are one-sided and it would cost nothing to add them wrongly.
        return None if vals[0] is None else str(vals[0]).strip()

    raise Unsupported(
        f"dynafunction {fn!r} has no independent implementation in the oracle. Add one "
        f"deliberately -- do NOT read the expected value from the engine, which would make "
        f"the assertion circular.")


# ---------------------------------------------------- derived-property evaluation

_TOKEN = re.compile(
    r"\s*(->orElse\(\s*-?\d+(?:\.\d+)?\s*\)|->\w+\(\)"
    r"|[\w:]+::\w+\.\w+"                      # enum literal, e.g. trading::Side.BUY
    r"|\$\w+(?:\.\w+)*"                        # $var, $var.path.to.prop
    r"|'[^']*'"                                  # string literal
    r"|<=|>=|==|!=|<|>"
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
        """Lowest precedence: comparison. `a * b > c` groups as `(a * b) > c`, which is
        what Pure means -- unlike `&&`, which binds TIGHTER than comparison and is the
        trap the constraints in 06-trading.pure are parenthesised against."""
        v = self.sum()
        if self.peek() in ("<", ">", "<=", ">=", "==", "!="):
            op = self.take()
            r = self.sum()
            return _cmp(op, v, r)
        return v

    def sum(self):
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
        elif tok and tok.startswith("$"):
            name, _, path = tok[1:].partition(".")
            v = self.lookup(path) if path else self.param(name)
            if not path and name in ("this",):
                raise Unsupported("bare $this is not a value")
        elif tok and tok.startswith("'") and tok.endswith("'"):
            v = tok[1:-1]
        elif tok and "::" in tok:
            # An enum literal renders as its VALUE NAME, which is exactly what the
            # projected column holds after the EnumerationMapping has been applied.
            v = tok.rsplit(".", 1)[1]
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


def _call(c: Corpus, data, row, root: str, fqn: str, args=()):
    """Evaluate a standalone function with the row bound to its FIRST parameter."""
    fn = c.functions.get(fqn)
    if fn is None:
        raise Unsupported(f"unknown function {fqn}")
    if len(args) != len(fn.params) - 1:
        raise Unsupported(f"{fqn} takes {len(fn.params) - 1} argument(s) besides the "
                          f"receiver, given {len(args)}")

    def lookup(path: str):
        steps = path.split(".")
        if len(steps) == 1:
            col = c.columns.get(root, {}).get(steps[0])
            if col is None:
                raise Unsupported(f"{root}.{steps[0]} is not a mapped column")
            raw = row.get(col)
            mapping = c.enum_props.get((root, steps[0]))
            return c.enum_maps[mapping].get(raw) if mapping and raw is not None else raw
        return _value(c, data, row, root, steps)

    bound = dict(zip((p[0] for p in fn.params[1:]), args))
    e = _Eval(_tokenise(fn.body), lookup, bound)
    v = e.expr()
    if e.peek() is not None:
        raise Unsupported(f"trailing tokens in function body {fn.body!r}")
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


# XStore links are predicates, not joins, so the model reader records no join for them.
# The oracle needs the correspondence to evaluate a cross-store navigation; it is declared
# here rather than inferred, and build.py fails if the columns do not exist.
XSTORE_LINKS = {
    ("trading::Trade", "legalEntity"):
        ("COUNTERPARTY_ID", "EXT_LEGAL_ENTITY", "ENTITY_ID", "external::LegalEntity"),
}


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


def _filter_holds(c: Corpus, name: str, row: dict | None) -> bool:
    """Whether the named store Filter's predicate holds of `row`.

    A None row means a broken chain -- the filter is reached through joins and the joins
    led nowhere. That is FALSE, not unknown: there is no landed row for the predicate to
    be true of, so the source row is excluded.
    """
    if row is None:
        return False
    _table, col, op, val = c.filters[name]
    if op == "isnull":
        return row.get(col) is None
    if op == "isnotnull":
        return row.get(col) is not None
    return _cmp({"=": "==", "<>": "!="}.get(op, op), row.get(col), val)


def _mapping_filtered(c: Corpus, root: str, rows: list[dict],
                      data: dict[str, list[dict]]) -> list[dict]:
    """A store filter attached to the class mapping narrows what all() can even see.

    It is applied BEFORE any query predicate and before milestoning, which is the whole
    point: the class is defined as the filtered subset, so no query can widen it back.

    Two forms. A DIRECT filter tests a column of the class's own main table. A CHAIN filter
    -- `~filter [db]@J1 > @J2 | [db]F` -- tests a column of a table reached by joins, and
    its exclusion semantics differ from a projection's: a projection over a broken chain
    yields NULL and KEEPS the row, whereas a filter over a broken chain DROPS it. Getting
    those two the same way round would be invisible on data where every chain resolves,
    which is exactly the data this corpus had.
    """
    name = c.class_filter.get(root)
    if name is not None:
        rows = [r for r in rows if _filter_holds(c, name, r)]
    chained = c.class_filter_chain.get(root)
    if chained is not None:
        joins, fname = chained
        start = c.main_table.get(root, "")
        hops, landed_table = _chain_hops(c, start, joins)
        ftable = c.filters[fname][0]
        if landed_table != ftable:
            raise Unsupported(
                f"~filter chain on {root} ends at {landed_table}, but filter {fname} tests "
                f"{ftable}")
        rows = [r for r in rows if _filter_holds(c, fname, walk(c, data, r, hops))]
    return rows


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
    # PROCESSING FIRST for a bitemporal class: all(processingDate, businessDate).
    # Verified against legend-engine's own bitemporal fixture, not assumed. Getting it
    # backwards is silent, not an error -- with the two dates swapped every query still
    # returns rows, just the wrong ones, and a fixture whose two dates happen to be equal
    # passes either way. The corpus deliberately uses DIFFERENT dates so the order is
    # observable.
    wanted = {"businesstemporal": ["business"],
              "processingtemporal": ["processing"],
              "bitemporal": ["processing", "business"]}.get(temporal)
    if wanted is None:
        raise Fanout(f"{spec.short}: {temporal} milestoning is not modelled")

    dates = spec.as_of if isinstance(spec.as_of, list) else [spec.as_of]
    if len(dates) != len(wanted) or any(d is None for d in dates):
        raise Fanout(f"{spec.short}: {spec.root} is {temporal}, so all() needs "
                     f"{len(wanted)} date(s) ({', '.join(wanted)}), got {dates!r}")

    for kind, at in zip(wanted, dates):
        ms = table.milestone(kind)
        if ms is None:
            raise Fanout(f"{spec.short}: {spec.root} is {temporal} but {table.name} "
                         f"declares no {kind} milestoning columns")
        # Each dimension narrows independently and the predicates AND together, which is
        # the whole content of bitemporality: "what did we BELIEVE on P about date B".
        if at == "latest":
            rows = [r for r in rows if r.get(ms.thru) == INFINITY]
        else:
            rows = [r for r in rows
                    if r.get(ms.frm) is not None and r.get(ms.frm) <= at
                    and (r.get(ms.thru) is None or at < r.get(ms.thru))]
    return rows


def _graph(c: Corpus, data, row, cls: str, tree: dict) -> dict:
    """Build one nested object. A to-one navigation that finds nothing yields null for the
    WHOLE sub-object, not an object full of nulls — which is the tree-shaped version of
    the distinction F8 showed a flat projection cannot make."""
    out = {}
    for name, sub in tree.items():
        if sub is None:
            col = c.columns.get(cls, {}).get(name)
            if col is None:
                raise Fanout(f"{cls}.{name} is not a mapped column")
            raw = row.get(col)
            mapping = c.enum_props.get((cls, name))
            out[name] = (c.enum_maps[mapping].get(raw)
                         if mapping and raw is not None else
                         render(raw, c.tables[c.main_table[cls]].columns[col].kind))
            continue
        link = XSTORE_LINKS.get((cls, name))
        if link is not None:
            from_col, table, to_col, target = link
            key = row.get(from_col)
            landed = (next((r for r in data[table] if r.get(to_col) == key), None)
                      if key else None)
            out[name] = None if landed is None else _graph(c, data, landed, target, sub)
            continue
        end = c.ends.get((cls, name))
        if end is None:
            raise Fanout(f"{cls}.{name} is not an association")
        if end.to_many:
            raise Fanout(f"{cls}.{name} is to-many; graph-fetch arrays are not modelled")
        hops, _ = c.resolve_assoc(cls, [name])
        landed = walk(c, data, row, hops)
        out[name] = None if landed is None else _graph(c, data, landed, end.target, sub)
    return out


def evaluate_graph(c: Corpus, spec: Spec, data: dict[str, list[dict]]) -> list[dict]:
    base = _mapping_filtered(c, spec.root, _rows_for(c, spec.root, data), data)
    base = _milestoned(c, spec, base)
    return [_graph(c, data, r, spec.root, spec.graph) for r in base]


def _group(spec: Spec, rows: list[dict]) -> list[dict]:
    """Group the PROJECTED rows. Keys and aggregates survive; everything else is dropped.

    count() here counts NON-NULL values of its source column, matching SQL's COUNT(col)
    rather than COUNT(*) — the distinction that F6 turns on.
    """
    groups: dict[tuple, list[dict]] = {}
    for r in rows:
        groups.setdefault(tuple(r[k] for k in spec.group_by), []).append(r)
    out = []
    for key, members in groups.items():
        row = dict(zip(spec.group_by, key))
        for name, src, fn in spec.aggs:
            vals = [m[src] for m in members if m[src] is not None]
            row[name] = (len(vals) if fn == "count"
                         else sum(vals) if fn == "sum"
                         else max(vals) if fn == "max"
                         else min(vals) if fn == "min" else None)
            if fn != "count" and not vals:
                row[name] = None
        out.append(row)
    return out


def evaluate(c: Corpus, spec: Spec, data: dict[str, list[dict]]) -> list[dict]:
    if spec.graph is not None:
        return evaluate_graph(c, spec, data)
    """Returns the rows the service must produce, as alias -> python value."""
    plain = [p for p in spec.projections if p.agg is None and not p.func
             and not (len(p.path) == 2 and (spec.root, p.path[0]) in XSTORE_LINKS)]
    if any(c.to_many_on(spec.root, p.path) for p in plain):
        raise Fanout(f"{spec.short}: a non-aggregate projection crosses a to-many "
                     f"association, which would fan the row set out")

    base = _mapping_filtered(c, spec.root, _rows_for(c, spec.root, data), data)
    base = _milestoned(c, spec, base)
    kept = [r for r in base
            if all(_cmp(f.op, _value(c, data, r, spec.root, f.path), f.value)
                   for f in spec.filters)]

    out = [{p.alias: (_agg(c, data, r, spec.root, p) if p.agg
                      else _value(c, data, r, spec.root, p.path, p.args, p.func))
            for p in spec.projections}
           for r in kept]

    if spec.group_by:
        out = _group(spec, out)
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


# A dynafunction whose result type DIFFERS from its first argument's. Everything absent
# here is type-preserving (toUpper, trim, abs, coalesce, plus...), which is why the map is
# short rather than 228 entries long: only the exceptions have to be stated.
DYNA_RETURN = {"length": "int", "sign": "int", "sqrt": "float",
               "isNull": "bool", "isNotNull": "bool"}


def _node_kind(c: Corpus, owner: str, node) -> str:
    tag, body = node
    if tag == "lit":
        return {int: "int", float: "float", str: "string"}[type(body)]
    if tag == "col":
        return c.tables[c.main_table[owner]].columns[body[1]].kind
    if tag == "chain":
        return c.tables[body[1]].columns[body[2]].kind
    if tag == "call":
        return _dyna_kind(c, owner, body)
    raise Unsupported(f"cannot type dynafunction argument {tag!r}")


def _dyna_kind(c: Corpus, owner: str, dyn) -> str:
    fn, args = dyn
    ret = DYNA_RETURN.get(fn)
    if ret is not None:
        return ret
    if not args:
        raise Unsupported(f"{fn}() takes no arguments, so its result cannot be typed")
    return _node_kind(c, owner, args[0])


def kinds(c: Corpus, spec: Spec) -> dict[str, str]:
    out = {}
    if spec.group_by:
        base = _kinds_of_projections(c, spec)
        k = {a: base[a] for a in spec.group_by}
        for name, src, fn in spec.aggs:
            k[name] = "int" if fn == "count" else base[src]
        return k
    return _kinds_of_projections(c, spec)


def _kinds_of_projections(c: Corpus, spec: Spec) -> dict[str, str]:
    out = {}
    for p in spec.projections:
        if p.agg == "count":
            out[p.alias] = "int"
            continue
        if p.func:
            out[p.alias] = {"Boolean": "bool", "Float": "float", "Integer": "int",
                            "String": "string"}[c.functions[p.func].ret]
            continue
        if len(p.path) == 2 and (spec.root, p.path[0]) in XSTORE_LINKS:
            target = XSTORE_LINKS[(spec.root, p.path[0])][3]
            col = c.columns[target][p.path[1]]
            out[p.alias] = c.tables[c.main_table[target]].columns[col].kind
            continue
        hit = c.resolve_derived(spec.root, p.path)
        if hit is not None:
            out[p.alias] = {"Float": "float", "Integer": "int", "Boolean": "bool",
                            "String": "string"}[hit[2].type]
            continue
        if c.enum_props.get((c.owner_of(spec.root, p.path), p.path[-1])):
            out[p.alias] = "string"     # an enum renders as its VALUE NAME
            continue
        dyn = c.dyna.get((c.owner_of(spec.root, p.path), p.path[-1]))
        if dyn is not None:
            out[p.alias] = _dyna_kind(c, c.owner_of(spec.root, p.path), dyn)
            continue
        chain = c.chains.get((c.owner_of(spec.root, p.path), p.path[-1]))
        if chain is not None:
            # A chained property's kind comes from the table the chain LANDS on, not from
            # the root's main table -- resolve() cannot reach it, since no association is
            # involved.
            _joins, ctbl, ccol = chain
            out[p.alias] = c.tables[ctbl].columns[ccol].kind
            continue
        table, col, _ = c.resolve(spec.root, p.path)
        out[p.alias] = c.tables[table].columns[col].kind
    return out


def as_json_rows(c: Corpus, spec: Spec, rows: list[dict]) -> list[dict]:
    if spec.graph is not None:
        return rows          # already rendered leaf by leaf
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
