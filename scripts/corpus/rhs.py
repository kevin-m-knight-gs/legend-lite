"""
Recursive-descent parser for the VALUE EXPRESSION of a relational property mapping.

Replaces the two regexes that between them handled `fn(col, col)` and `fn(chain)`. Regexes
cannot nest, and the combination matrix needs exactly the nesting they cannot express:

    concat([db]@J | [db]T.A, [db]T.B)          a chain and a column as sibling arguments
    toUpper(concat([db]T.A, [db]T.B))          a function over a function
    substring([db]T.A, 1, 3)                   literal arguments

The argument for a parser rather than a longer pattern is not elegance. The last time a
regex in this reader met a form it was not written for it did not fail -- `_COLMAP` matched
the TAIL of a Binding mapping and recorded a property named `ProfileBinding` that existed
nowhere but in the reader. A wrong span is worse than no match, and nesting is where wrong
spans come from.

The grammar:

    expr    := call | chain | column | literal
    call    := IDENT '(' [expr (',' expr)*] ')'
    chain   := [dbref] '@' IDENT ('>' '@' IDENT)* '|' [dbref] IDENT '.' IDENT
    column  := [dbref] IDENT '.' IDENT
    literal := STRING | NUMBER
    dbref   := '[' path ']'

Nodes are tuples, tagged so the oracle can evaluate them without re-inspecting text:

    ("col",   (table, column))
    ("chain", ([join, ...], table, column))
    ("call",  (fn, [node, ...]))
    ("lit",   value)
"""
from __future__ import annotations

import re


class ParseError(ValueError):
    """The reader has no rule for this expression. Raised rather than returning a partial
    parse -- a half-understood mapping is the thing that produces a green test asserting
    the wrong value."""


# `{target}` is a TABLE NAME, not punctuation: in a self-join it stands for the far side of
# the same table. Lexed as an identifier-shaped token so the operand grammar needs no special
# case for it.
_TOKEN = re.compile(r"""
      \s*(?:
        (?P<db>\[[\w:]+\])
      | (?P<target>\{target\})
      | (?P<str>'(?:[^']|'')*')
      | (?P<num>-?\d+\.\d+|-?\d+)
      | (?P<ident>[A-Za-z_]\w*)
      | (?P<punct>[@|,().><=])
      )""", re.X)


def _tokenise(text: str) -> list[tuple[str, str]]:
    out, i = [], 0
    while i < len(text):
        if text[i].isspace():
            i += 1
            continue
        m = _TOKEN.match(text, i)
        if not m or m.end() == i:
            raise ParseError(f"cannot tokenise at {text[i:][:30]!r}")
        kind = m.lastgroup
        out.append((kind, m.group(kind)))
        i = m.end()
    return out


class _Parser:
    def __init__(self, tokens: list[tuple[str, str]]):
        self.t, self.i = tokens, 0

    def peek(self, n: int = 0):
        return self.t[self.i + n] if self.i + n < len(self.t) else (None, None)

    def take(self):
        tok = self.peek()
        self.i += 1
        return tok

    def expect(self, value: str):
        kind, v = self.take()
        if v != value:
            raise ParseError(f"expected {value!r}, found {v!r}")
        return v

    # ---- expr := call | chain | column | literal -------------------------------------
    def expr(self):
        kind, v = self.peek()
        if kind == "str":
            self.take()
            return ("lit", v[1:-1].replace("''", "'"))
        if kind == "num":
            self.take()
            return ("lit", float(v) if "." in v else int(v))
        # A call is the only form where an identifier is followed by '('. A column is an
        # identifier followed by '.', so one token of lookahead separates them.
        if kind == "ident" and self.peek(1)[1] == "(":
            return self.call()
        if kind in ("db", "ident", "target") or v == "@":
            return self.chain_or_column()
        raise ParseError(f"unexpected {v!r}")

    def call(self):
        _k, fn = self.take()
        self.expect("(")
        args = []
        if self.peek()[1] != ")":
            args.append(self.expr())
            while self.peek()[1] == ",":
                self.take()
                args.append(self.expr())
        self.expect(")")
        return ("call", (fn, args))

    def chain_or_column(self):
        # An optional store qualifier precedes either form. It is REQUIRED on a chain's
        # first hop and optional afterwards, but that is the grammar's rule to enforce, not
        # this reader's -- anything the engine accepts should parse here.
        if self.peek()[0] == "db":
            self.take()
        if self.peek()[1] == "@":
            return self.chain()
        return self.column()

    def chain(self):
        joins = []
        while self.peek()[1] == "@":
            self.take()
            kind, name = self.take()
            if kind != "ident":
                raise ParseError(f"expected a join name after '@', found {name!r}")
            joins.append(name)
            if self.peek()[1] == ">":
                self.take()
                if self.peek()[0] == "db":      # a qualifier may precede a later hop
                    self.take()
                continue
            break
        self.expect("|")
        table, col = self.column()[1]
        return ("chain", (joins, table, col))

    def column(self):
        if self.peek()[0] == "db":
            self.take()
        parts = []
        kind, name = self.take()
        if kind not in ("ident", "target"):
            raise ParseError(f"expected a table name, found {name!r}")
        parts.append(name)
        while self.peek()[1] == ".":
            self.take()
            kind, name = self.take()
            if kind != "ident":
                raise ParseError(f"expected a name after '.', found {name!r}")
            parts.append(name)
        # Two parts is TABLE.COL; three is SCHEMA.TABLE.COL. Tables are keyed globally by
        # name in this reader, so the schema is dropped -- but it must be CONSUMED, or the
        # trailing `.COL` is left unparsed and the caller reports trailing input.
        if len(parts) == 3:
            parts = parts[1:]
        if len(parts) != 2:
            raise ParseError(f"expected TABLE.COL or SCHEMA.TABLE.COL, found {'.'.join(parts)}")
        return ("col", tuple(parts))


def parse(text: str):
    """Parse one property-mapping value expression. Raises ParseError on anything the
    grammar above does not cover, so an unmodelled form is visible rather than guessed."""
    p = _Parser(_tokenise(text))
    node = p.expr()
    if p.i != len(p.t):
        raise ParseError(f"trailing input at {p.peek()[1]!r}")
    return node


# ---------------------------------------------------------------- helpers for callers
def columns(node) -> list[tuple[str, str]]:
    """PLAIN (table, column) references -- deliberately NOT descending into chains.

    A chain's target column is read from the row the chain LANDS on; a plain reference is
    read from the row the oracle already holds. Returning both in one list invites the
    caller to treat them alike, and the caller that does will read the landed table's
    column off the base row and get None -- or, worse, a value from a same-named column."""
    tag, body = node
    if tag == "col":
        return [body]
    if tag == "call":
        return [c for a in body[1] for c in columns(a)]
    return []


def functions(node) -> list[str]:
    tag, body = node
    if tag != "call":
        return []
    return [body[0]] + [f for a in body[1] for f in functions(a)]


def chains(node) -> list[tuple]:
    tag, body = node
    if tag == "chain":
        return [body]
    if tag == "call":
        return [ch for a in body[1] for ch in chains(a)]
    return []


def find_call(text: str, start: int) -> int:
    """Index of the ')' closing the '(' at or after `start`, skipping string literals.
    Needed because a call's extent cannot be found by a regex once it nests."""
    depth, i, n = 0, start, len(text)
    while i < n:
        ch = text[i]
        if ch == "'":
            j = text.find("'", i + 1)
            if j < 0:
                raise ParseError("unterminated string literal")
            i = j
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ParseError("unbalanced parentheses")


# ------------------------------------------------------------------ join conditions
#
# A Join's condition is a boolean expression over the same operand grammar as a property
# mapping's value: columns, dynafunction calls, literals. The corpus's reader modelled only
# `A.X = B.Y`, so five of its six generated dense joins -- multi-column, non-equality, `or`,
# and both dynafunction forms -- were dropped without a word, which made them unusable by any
# mapping and unexecutable by any service while still counting as present in the feature meter.
#
# `and` and `or` have EQUAL precedence and are RIGHT-associative in this grammar, so
# `A and B or C` parses as `A and (B or C)`. That is not SQL's rule and it is easy to get
# wrong in both directions; it is verified in scripts/corpus/verified/store.md.
#
# Nodes:
#     ("cmp",  (operand, op, operand))       op in = <> < <= > >=
#     ("null", (operand, negated))           `is null` / `is not null`
#     ("and",  (left, right))
#     ("or",   (left, right))
_COMPARISONS = ("<=", ">=", "<>", "=", "<", ">")


class _CondParser(_Parser):
    def condition(self):
        left = self.primary()
        kind, v = self.peek()
        if v in ("and", "or"):
            self.take()
            return (v, (left, self.condition()))      # right-associative, equal precedence
        return left

    def primary(self):
        if self.peek()[1] == "(":
            self.take()
            inner = self.condition()
            self.expect(")")
            return inner
        left = self.expr()
        kind, v = self.peek()
        if v == "is":
            self.take()
            negated = self.peek()[1] == "not"
            if negated:
                self.take()
            self.expect("null")
            return ("null", (left, negated))
        # `<=` and `>=` arrive as two tokens, so the longer forms are reassembled here
        # rather than lexed: a lexer that produced `<` then `=` for `<=` would otherwise
        # parse it as a comparison against an empty right-hand side.
        op = self.take()[1]
        if op not in ("<", ">", "=", "<>") and op is not None:
            raise ParseError(f"expected a comparison operator, found {op!r}")
        if self.peek()[1] == "=" and op in ("<", ">"):
            self.take()
            op += "="
        elif op == "<" and self.peek()[1] == ">":
            self.take()
            op = "<>"
        return ("cmp", (left, op, self.expr()))


def parse_condition(text: str):
    p = _CondParser(_tokenise(text))
    node = p.condition()
    if p.i != len(p.t):
        raise ParseError(f"trailing input in join condition at {p.peek()[1]!r}")
    return node


def condition_tables(node) -> set:
    """Every table the condition names, so a caller can tell which two sides it joins."""
    tag, body = node
    if tag == "cmp":
        return set(t for t, _c in columns(body[0]) + columns(body[2]))
    if tag == "null":
        return set(t for t, _c in columns(body[0]))
    return condition_tables(body[0]) | condition_tables(body[1])
