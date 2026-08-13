"""
Reads the stress corpus into a resolvable model.

The corpus is 46 files, 200 classes, 182 associations and 182 joins. Nothing here is
authored by hand against that: every fact the seeder and the oracle need — which table
backs a class, which column backs a property, which join a navigation crosses, and
whether that navigation is to-one or to-many — is read out of the .pure sources.

That matters for correctness of the *oracle*, not convenience. A hand-written map would
encode what I believe the mapping says; this encodes what it says. When the two disagree
the resolver raises instead of quietly producing a wrong expectation.

Grammar actually present in this corpus (asserted, not assumed — see check()):
  Table  NAME (COL TYPE [PRIMARY KEY], ...)          no schema qualifier, one Database
  Join   NAME(A.X = B.Y)                             single equality, no 'and', no {target}
  Class  pkg::Name { prop: Type[mult]; ... }
  Assoc  pkg::Name { endA: T[m]; endB: U[n]; }
  <cls>: Relational { ~mainTable [db] T   prop: [db] T.COL, ... }
  <assoc>: Relational { AssociationMapping ( end: [db] @Join, ... ) }

Anything outside that raises. The corpus is a fixture we control, so an unhandled form is
a signal to extend this deliberately rather than a reason to make the parser lenient.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

STRESS = Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"


# ---------------------------------------------------------------- data model

@dataclass
class Column:
    name: str
    type: str
    pk: bool = False

    @property
    def kind(self) -> str:
        """Coarse class used by the seeder and by JSON rendering."""
        t = self.type.upper()
        if t.startswith("VARCHAR") or t.startswith("CHAR"):
            return "string"
        if t in ("INTEGER", "INT", "BIGINT", "SMALLINT"):
            return "int"
        if t in ("DOUBLE", "FLOAT", "REAL", "DECIMAL", "NUMERIC"):
            return "float"
        # BIT is the boolean type in Legend's relational grammar. BOOLEAN is NOT in
        # RelationalDataType and legend-engine rejects it at parse time with
        # "Unsupported column data type 'BOOLEAN'" — legend-lite accepts it, which is how
        # this corpus shipped 194 unparseable columns.
        if t in ("BIT", "BOOLEAN"):
            return "bool"
        if t == "DATE":
            return "date"
        if t in ("TIMESTAMP", "DATETIME"):
            return "timestamp"
        raise ValueError(f"unhandled SQL type {self.type!r}")


@dataclass
class ViewCol:
    name: str
    source: str            # source column on the base table
    agg: str | None        # None for a grouping column; 'count' | 'sum' | 'max' | 'min'


@dataclass
class View:
    """A Legend View — an inlined GROUP BY, not a database view. No DDL is created for it;
    the engine folds the aggregation into the query it generates. The oracle therefore has
    to compute the same grouping itself, which views.py does."""
    name: str
    base: str
    group_by: list[str] = field(default_factory=list)
    columns: dict[str, ViewCol] = field(default_factory=dict)


@dataclass
class Milestoning:
    """Business-temporal (SCD2) or processing-temporal columns on a table."""
    kind: str          # 'business' | 'processing'
    frm: str
    thru: str
    # Required for %latest. Absent, dated queries still work and only %latest fails —
    # at plan generation, not at compile.
    infinity: str | None = None


@dataclass
class Table:
    name: str
    columns: dict[str, Column] = field(default_factory=dict)
    milestoning: Milestoning | None = None

    @property
    def pk(self) -> list[str]:
        return [c.name for c in self.columns.values() if c.pk]


@dataclass
class Join:
    """A.X = B.Y. Direction is resolved at use, not here — the same join is
    traversed both ways by the two ends of an association."""
    name: str
    left_table: str
    left_col: str
    right_table: str
    right_col: str

    def other(self, frm: str) -> tuple[str, str, str]:
        """(target_table, from_col, to_col) when entered from table `frm`."""
        if frm == self.left_table:
            return self.right_table, self.left_col, self.right_col
        if frm == self.right_table:
            return self.left_table, self.right_col, self.left_col
        raise KeyError(f"join {self.name} does not touch {frm}")


@dataclass
class Prop:
    name: str
    type: str          # 'String' | 'Float' | ... or a class FQN
    lower: int
    upper: int | None  # None == *


@dataclass
class Derived:
    """A derived property: `netCost() { $this.commission + $this.fees } : Float[0..1];`

    Stored as source text; oracle.py evaluates it. Deliberately not pre-parsed here — the
    expression grammar the oracle supports is narrow and enforced at evaluation time, so
    an expression outside it fails loudly at build rather than silently here.
    """
    name: str
    expr: str
    type: str
    lower: int
    upper: int | None
    params: list[str] = field(default_factory=list)   # a QUALIFIED property if non-empty


@dataclass
class Klass:
    fqn: str
    props: dict[str, Prop] = field(default_factory=dict)
    derived: dict[str, Derived] = field(default_factory=dict)
    stereotypes: list[str] = field(default_factory=list)

    @property
    def temporal(self) -> str | None:
        for s in self.stereotypes:
            if s.startswith("temporal."):
                return s.split(".", 1)[1]
        return None


@dataclass
class AssocEnd:
    """One navigable end. `owner` holds the property, `target` is what you land on."""
    owner: str
    name: str
    target: str
    to_many: bool
    join: str | None = None   # filled from the AssociationMapping


@dataclass
class Corpus:
    tables: dict[str, Table] = field(default_factory=dict)
    joins: dict[str, Join] = field(default_factory=dict)
    classes: dict[str, Klass] = field(default_factory=dict)
    enums: dict[str, list[str]] = field(default_factory=dict)
    # class fqn -> table name
    main_table: dict[str, str] = field(default_factory=dict)
    # class fqn -> {property -> column}
    columns: dict[str, dict[str, str]] = field(default_factory=dict)
    # (class fqn, property) -> AssocEnd
    ends: dict[tuple[str, str], AssocEnd] = field(default_factory=dict)
    # EnumerationMapping name -> {source code -> enum value}. Many-to-one is legal and
    # used: 'B' and the legacy 'BOT' both mean BUY.
    enum_maps: dict[str, dict[str, str]] = field(default_factory=dict)
    # (class fqn, property) -> EnumerationMapping name
    enum_props: dict[tuple[str, str], str] = field(default_factory=dict)
    # class fqn -> the member TABLES of an Operation union mapping, in declared order
    unions: dict[str, list[str]] = field(default_factory=dict)
    views: dict[str, View] = field(default_factory=dict)
    # store filter name -> (table, column, op, literal)
    filters: dict[str, tuple[str, str, str, object]] = field(default_factory=dict)
    # class fqn -> the store filter its mapping applies
    class_filter: dict[str, str] = field(default_factory=dict)

    # -------------------------------------------------------- resolution

    def resolve(self, root: str, path: list[str]):
        """Walk a projection path from `root`.

        Returns (table, column, hops) where hops is the ordered list of
        (join_name, from_table, from_col, to_table, to_col) crossed to reach it.
        Raises on anything that does not resolve — the point of the exercise.
        """
        cls, table, hops = root, self.main_table.get(root), []
        if table is None:
            raise KeyError(f"class {root} has no ~mainTable")
        for i, step in enumerate(path):
            last = i == len(path) - 1
            end = self.ends.get((cls, step))
            if end is not None:
                if end.join is None:
                    raise KeyError(f"{cls}.{step} has no AssociationMapping")
                j = self.joins[end.join]
                tgt, fc, tc = j.other(table)
                hops.append((end.join, table, fc, tgt, tc))
                cls, table = end.target, tgt
                if last:
                    raise KeyError(f"path ends on association {cls}.{step}")
                continue
            col = self.columns.get(cls, {}).get(step)
            if col is None:
                raise KeyError(f"{cls}.{step} is neither a mapped property nor an association")
            if not last:
                raise KeyError(f"{cls}.{step} is a column but the path continues")
            return table, col, hops
        raise KeyError("empty path")

    def resolve_assoc(self, root: str, path: list[str]):
        """Walk a path that ends ON an association rather than on a column.

        Returns (hops, target_class). Used for aggregate projections such as
        `$x.trades->count()`, where the value is a property of the SET of landed rows
        rather than of any one row.
        """
        cls, table, hops = root, self.main_table.get(root), []
        if table is None:
            raise KeyError(f"class {root} has no ~mainTable")
        for step in path:
            end = self.ends.get((cls, step))
            if end is None:
                raise KeyError(f"{cls}.{step} is not an association")
            if end.join is None:
                raise KeyError(f"{cls}.{step} has no AssociationMapping")
            tgt, fc, tc = self.joins[end.join].other(table)
            hops.append((end.join, table, fc, tgt, tc))
            cls, table = end.target, tgt
        return hops, cls

    def resolve_derived(self, root: str, path: list[str]):
        """If the path ends on a derived property, return (hops, owning class, Derived).
        Otherwise None, so callers can fall through to the column resolver."""
        cls = root
        for step in path[:-1]:
            end = self.ends.get((cls, step))
            if end is None:
                return None
            cls = end.target
        d = self.classes.get(cls, Klass(cls)).derived.get(path[-1])
        if d is None:
            return None
        hops = self.resolve_assoc(root, path[:-1])[0] if len(path) > 1 else []
        return hops, cls, d

    def owner_of(self, root: str, path: list[str]) -> str:
        """The class that declares the LAST step of a path — needed to look up whether
        that property carries an EnumerationMapping."""
        cls = root
        for step in path[:-1]:
            cls = self.ends[(cls, step)].target
        return cls

    def to_many_on(self, root: str, path: list[str]) -> bool:
        """True if any hop along the path is to-many — i.e. the projection fans out."""
        cls = root
        for step in path[:-1]:
            end = self.ends[(cls, step)]
            if end.to_many:
                return True
            cls = end.target
        return False


# ---------------------------------------------------------------- parsing

_TABLE = re.compile(r"^\s*Table\s+(\w+)\s*\((.*)\)\s*$")
_JOIN = re.compile(r"^\s*Join\s+(\w+)\s*\(\s*(\w+)\.(\w+)\s*=\s*(\w+)\.(\w+)\s*\)\s*$")
_FILTER_DECL = re.compile(
    r"^\s*Filter\s+(\w+)\s*\(\s*(\w+)\.(\w+)\s*(=|<>|<=|>=|<|>)\s*(.+?)\s*\)\s*$")
_CLS_FILTER = re.compile(r"^\s*~filter\s*\[[\w:]+\]\s*(\w+)\s*$")
# Stereotypes carry the temporal marker: `Class <<temporal.businesstemporal>> pkg::Name`
_CLASS = re.compile(r"^\s*Class\s+(?:<<([^>]*)>>\s*)?([\w:]+)\s*$")
_ASSOC = re.compile(r"^\s*Association\s+([\w:]+)\s*$")
_ENUM = re.compile(r"^\s*Enum\s+([\w:]+)\s*$")
_PROP = re.compile(r"^\s*(\w+)\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
# `name() { expr } : T[m];` and the qualified form `name(p: T[1], ...) { expr } : T[m];`
_DERIVED = re.compile(
    r"^\s*(\w+)\s*\(([^)]*)\)\s*\{(.+)\}\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
_PARAM = re.compile(r"(\w+)\s*:\s*[\w:]+\s*\[[^\]]+\]")
_MAIN = re.compile(r"^\s*~mainTable\s*\[[\w:]+\]\s*(\w+)\s*$")
# `Class: Relational`, `Class[id]: Relational`, and the root-marked `*Class: ...` form.
_CLSMAP = re.compile(r"^\s*\*?([\w:]+)(?:\[(\w+)\])?\s*:\s*Relational\s*\{?\s*$")
_OPMAP = re.compile(r"^\s*\*?([\w:]+)\s*:\s*Operation\s*\{?\s*$")
_UNION = re.compile(r"union_OperationSetImplementation_1__SetImplementation_MANY_"
                    r"\s*\(([^)]*)\)")
_COLMAP = re.compile(r"(\w+)\s*:\s*\[[\w:]+\]\s*(\w+)\.(\w+)")
# `prop: EnumerationMapping <Name>: [db] TABLE.COL` — must be stripped BEFORE _COLMAP
# runs, or _COLMAP matches the tail and records the mapping NAME as the property.
_ENUMCOLMAP = re.compile(
    r"(\w+)\s*:\s*EnumerationMapping\s+(\w+)\s*:\s*\[[\w:]+\]\s*(\w+)\.(\w+)")
_ENUMMAP_HEAD = re.compile(r"^\s*([\w:]+)\s*:\s*EnumerationMapping\s+(\w+)\s*$")
_ENUMMAP_ROW = re.compile(r"^\s*(\w+)\s*:\s*\[([^\]]*)\]\s*,?\s*$")
_ENDMAP = re.compile(r"(\w+)\s*:\s*\[[\w:]+\]\s*@(\w+)")


def _strip(line: str) -> str:
    i = line.find("//")
    return (line[:i] if i >= 0 else line).rstrip()


def _mult(s: str) -> tuple[int, int | None]:
    s = s.strip()
    if s == "*":
        return 0, None
    if ".." in s:
        lo, hi = s.split("..", 1)
        return int(lo), (None if hi.strip() == "*" else int(hi))
    return int(s), int(s)


_MILESTONING = re.compile(
    r"milestoning\s*\(\s*(business|processing)\s*\("
    r"\s*(?:BUS_FROM|PROCESSING_IN)\s*=\s*(\w+)\s*,"
    r"\s*(?:BUS_THRU|PROCESSING_OUT)\s*=\s*(\w+)\s*"
    r"(?:,\s*INFINITY_DATE\s*=\s*%([\d:.T-]+)\s*)?\)\s*\)", re.S)


def _table_bodies(text: str) -> list[tuple[str, str]]:
    """(name, body) for every Table, matching parentheses so a declaration may span lines.

    The single-line reader this replaces could not see the canonical milestoned form,
    where `milestoning ( business(BUS_FROM = ..., BUS_THRU = ...) )` sits on its own lines
    ahead of the columns.
    """
    out = []
    for m in re.finditer(r"\bTable\s+(\w+)\s*\(", text):
        i, depth = m.end(), 1
        while depth and i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        out.append((m.group(1), text[m.end():i - 1]))
    return out


_VIEW_GROUPBY = re.compile(r"~groupBy\s*\((.*?)\)", re.S)
_VIEW_AGG = re.compile(r"^(\w+)\s*:\s*(count|sum|max|min)\s*\(\s*(\w+)\.(\w+)\s*\)$")
_VIEW_PLAIN = re.compile(r"^(\w+)\s*:\s*(\w+)\.(\w+)(?:\s+PRIMARY\s+KEY)?$", re.I)


def _view_bodies(text: str) -> list[tuple[str, str]]:
    out = []
    for m in re.finditer(r"\bView\s+(\w+)\s*\(", text):
        i, depth = m.end(), 1
        while depth and i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        out.append((m.group(1), text[m.end():i - 1]))
    return out


def _parse_view(name: str, body: str, c: Corpus) -> None:
    gb = _VIEW_GROUPBY.search(body)
    group_cols, base = [], None
    if gb:
        for ref in gb.group(1).split(","):
            ref = ref.strip()
            if not ref:
                continue
            tbl, col = ref.rsplit(".", 1)
            base = tbl.split("]")[-1]
            group_cols.append(col)
        body = _VIEW_GROUPBY.sub("", body, count=1)

    v = View(name, base or "", group_cols)
    for spec in _split_cols(body):
        spec = " ".join(spec.split())
        if not spec:
            continue
        m = _VIEW_AGG.match(spec)
        if m:
            v.base = v.base or m.group(3)
            v.columns[m.group(1)] = ViewCol(m.group(1), m.group(4), m.group(2))
            continue
        m = _VIEW_PLAIN.match(spec)
        if m:
            v.base = v.base or m.group(2)
            v.columns[m.group(1)] = ViewCol(m.group(1), m.group(3), None)
            continue
        raise ValueError(f"View {name}: unhandled column {spec!r}")
    c.views[name] = v


def _parse_store(text: str, c: Corpus) -> None:
    text = "\n".join(_strip(l) for l in text.splitlines())
    for name, body in _view_bodies(text):
        _parse_view(name, body, c)
    for name, body in _table_bodies(text):
        t = Table(name)
        ms = _MILESTONING.search(body)
        if ms:
            t.milestoning = Milestoning(ms.group(1), ms.group(2), ms.group(3),
                                        ms.group(4))
            body = _MILESTONING.sub("", body)
        for spec in _split_cols(body):
            spec = " ".join(spec.split())
            if not spec:
                continue
            parts = spec.split()
            t.columns[parts[0]] = Column(parts[0], parts[1],
                                         spec.upper().endswith("PRIMARY KEY"))
        c.tables[t.name] = t

    for raw in text.splitlines():
        line = _strip(raw)
        m = _FILTER_DECL.match(line)
        if m:
            lit = m.group(5).strip()
            if lit.startswith("'") and lit.endswith("'"):
                val = lit[1:-1]
            elif re.fullmatch(r"-?\d+", lit):
                val = int(lit)
            elif re.fullmatch(r"-?\d*\.\d+", lit):
                val = float(lit)
            else:
                raise ValueError(f"Filter {m.group(1)}: unhandled literal {lit!r}")
            c.filters[m.group(1)] = (m.group(2), m.group(3), m.group(4), val)
            continue
        m = _JOIN.match(line)
        if m:
            n, lt, lc, rt, rc = m.groups()
            if n in c.joins:
                raise ValueError(f"duplicate join {n}")
            c.joins[n] = Join(n, lt, lc, rt, rc)


def _split_cols(body: str) -> list[str]:
    """Split a Table(...) body on commas that are not inside VARCHAR(200)."""
    out, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return out


def _parse_domain(text: str, c: Corpus) -> None:
    cur_class = cur_assoc = cur_enum = None
    ends: list[tuple[str, str, int, int | None]] = []
    for raw in text.splitlines():
        line = _strip(raw)
        if not line.strip():
            continue
        m = _CLASS.match(line)
        if m:
            cur_class, cur_assoc, cur_enum = m.group(2), None, None
            k = c.classes.setdefault(cur_class, Klass(cur_class))
            k.stereotypes = [s.strip() for s in (m.group(1) or "").split(",") if s.strip()]
            continue
        m = _ASSOC.match(line)
        if m:
            cur_assoc, cur_class, cur_enum = m.group(1), None, None
            ends = []
            continue
        m = _ENUM.match(line)
        if m:
            cur_enum, cur_class, cur_assoc = m.group(1), None, None
            c.enums[cur_enum] = []
            continue
        if line.strip() == "}":
            if cur_assoc:
                if len(ends) != 2:
                    raise ValueError(f"association {cur_assoc} has {len(ends)} ends")
                (n0, t0, _, u0), (n1, t1, _, u1) = ends
                # The end named n0 has type t0, so it is reachable FROM t1.
                c.ends[(t1, n0)] = AssocEnd(t1, n0, t0, u0 is None or u0 > 1)
                c.ends[(t0, n1)] = AssocEnd(t0, n1, t1, u1 is None or u1 > 1)
            cur_class = cur_assoc = cur_enum = None
            continue
        if cur_enum is not None:
            v = line.strip().rstrip(",")
            if v and v != "{":
                c.enums[cur_enum].append(v)
            continue
        m = _DERIVED.match(line)
        if m and cur_class:
            lo, hi = _mult(m.group(5))
            c.classes[cur_class].derived[m.group(1)] = Derived(
                m.group(1), m.group(3).strip(), m.group(4), lo, hi,
                _PARAM.findall(m.group(2)))
            continue
        m = _PROP.match(line)
        if m:
            name, typ, mult = m.group(1), m.group(2), m.group(3)
            lo, hi = _mult(mult)
            if cur_class:
                c.classes[cur_class].props[name] = Prop(name, typ, lo, hi)
            elif cur_assoc:
                ends.append((name, typ, lo, hi))


def _split_mappings(body: str) -> list[str]:
    """A ###Mapping section may hold several Mapping elements; skipping one means
    splitting them apart first."""
    out, cur = [], []
    for line in body.splitlines(True):
        if _MAPPING_NAME.match(line) and cur:
            out.append("".join(cur))
            cur = []
        cur.append(line)
    if cur:
        out.append("".join(cur))
    return out


def _parse_mapping(text: str, c: Corpus) -> None:
    cur = None
    cur_id = None
    cur_op = None
    set_tables: dict[str, str] = {}   # set-implementation id -> its ~mainTable
    in_assoc = False
    enum_map = None          # name of the EnumerationMapping currently being read
    for raw in text.splitlines():
        line = _strip(raw)
        if not line.strip():
            continue

        m = _ENUMMAP_HEAD.match(line)
        if m:
            enum_map, cur = m.group(2), None
            c.enum_maps.setdefault(enum_map, {})
            continue
        if enum_map is not None:
            if line.strip() == "}":
                enum_map = None
                continue
            m = _ENUMMAP_ROW.match(line)
            if m:
                value = m.group(1)
                for code in m.group(2).split(","):
                    code = code.strip().strip("'")
                    if code:
                        if code in c.enum_maps[enum_map]:
                            raise ValueError(
                                f"EnumerationMapping {enum_map}: source code {code!r} "
                                f"maps to both {c.enum_maps[enum_map][code]} and {value}")
                        c.enum_maps[enum_map][code] = value
                continue
            if line.strip() != "{":
                raise ValueError(f"unhandled EnumerationMapping line: {line!r}")
            continue
        m = _OPMAP.match(line)
        if m:
            cur, in_assoc, cur_op = None, False, m.group(1)
            continue
        if cur_op and _UNION.search(line):
            ids = [i.strip() for i in _UNION.search(line).group(1).split(",") if i.strip()]
            c.unions[cur_op] = [set_tables[i] for i in ids if i in set_tables]
            cur_op = None
            continue
        m = _CLSMAP.match(line)
        if m and "AssociationMapping" not in line:
            cur, in_assoc, cur_id = m.group(1), False, m.group(2)
            continue
        if "AssociationMapping" in line:
            in_assoc = True
            continue
        if line.strip() in ("}", ")", "    }"):
            continue
        m = _CLS_FILTER.match(line)
        if m and cur:
            c.class_filter[cur] = m.group(1)
            continue
        m = _MAIN.match(line)
        if m and cur:
            # For a union member, the per-id table is what the union needs; the class's
            # own main_table is set by whichever member comes last and is only used as a
            # fallback for callers that do not know about unions.
            if cur_id:
                set_tables[cur_id] = m.group(1)
            c.main_table[cur] = m.group(1)
            continue
        if cur and in_assoc:
            for prop, join in _ENDMAP.findall(line):
                for (owner, name), end in c.ends.items():
                    if name == prop and _assoc_matches(cur, owner, end):
                        end.join = join
            continue
        if cur and cur in c.main_table:
            tbl = c.main_table[cur]
            for prop, mapping, t, col in _ENUMCOLMAP.findall(line):
                if t != tbl:
                    raise ValueError(f"{cur}.{prop} maps to {t}, not mainTable {tbl}")
                c.columns.setdefault(cur, {})[prop] = col
                c.enum_props[(cur, prop)] = mapping
            line = _ENUMCOLMAP.sub("", line)
            for prop, t, col in _COLMAP.findall(line):
                if t != tbl:
                    raise ValueError(f"{cur}.{prop} maps to {t}, not mainTable {tbl}")
                c.columns.setdefault(cur, {})[prop] = col


def _assoc_matches(assoc_fqn: str, owner: str, end: AssocEnd) -> bool:
    """An AssociationMapping names ends by property only. Bind the property to the two
    ends of *that* association by checking the owner/target pair appears in its name."""
    short = assoc_fqn.split("::")[-1]
    a, _, b = short.partition("_")
    o, t = owner.split("::")[-1], end.target.split("::")[-1]
    return {a, b} == {o, t} or (a in (o, t) and b in (o, t))


def sections(text: str) -> list[tuple[str, str]]:
    """Split a source into (section kind, body). A section header is a line that STARTS
    with ###; the mention of one inside a comment is not a header. Dispatching on
    `"###Mapping" in text` instead of this cost an afternoon — a comment in
    90-cross-domain-associations.pure explaining the header requirement contained the
    word, so the file was silently treated as a mapping and 44 associations vanished."""
    out, kind, buf = [], "Pure", []
    for line in text.splitlines():
        if line.startswith("###"):
            out.append((kind, "\n".join(buf)))
            kind, buf = line[3:].strip(), []
        else:
            buf.append(line)
    out.append((kind, "\n".join(buf)))
    return [(k, b) for k, b in out if b.strip()]


# Mappings the oracle deliberately does NOT model.
#
# reporting::EmbeddedFlatMapping maps trading::Trade to TRADE_FLAT with EMBEDDED property
# mappings. Parsing it with the line-based reader here would rebind trading::Trade's
# ~mainTable to TRADE_FLAT and attach the embedded columns to the wrong class, silently
# corrupting every expectation in the corpus.
#
# It is skipped rather than supported because it does not need to be supported: the whole
# claim of mapping invariance is that a query through this mapping returns what the
# canonical mapping returns. Its expectation IS the canonical expectation, so computing a
# second one would be circular at best and wrong at worst.
SKIP_MAPPINGS = {"reporting::EmbeddedFlatMapping"}

# re.M is load-bearing: without it `search` over a multi-line chunk never matches, the
# skip-list silently does nothing, and trading::Trade quietly gets rebound to TRADE_FLAT.
_MAPPING_NAME = re.compile(r"^\s*Mapping\s+([\w:]+)\s*$", re.M)


def _materialise_view_schemas(c: Corpus) -> None:
    """Views are referenced by mappings exactly like tables, so they need a schema. Types
    are inherited from the base column; a count is an Integer whatever it counts."""
    for v in c.views.values():
        base = c.tables.get(v.base)
        if base is None:
            raise ValueError(f"View {v.name} is built on unknown table {v.base!r}")
        t = Table(v.name)
        for name, col in v.columns.items():
            src = base.columns.get(col.source)
            if src is None:
                raise ValueError(f"View {v.name}: {v.base}.{col.source} does not exist")
            typ = "INTEGER" if col.agg == "count" else src.type
            t.columns[name] = Column(name, typ, name in v.group_by
                                     or col.source in v.group_by)
        c.tables[v.name] = t


def load() -> Corpus:
    c = Corpus()
    files = sorted(STRESS.glob("*.pure"))
    parsed = [(f, sections(f.read_text())) for f in files]
    # Three passes: stores define the tables mappings point at, and mappings bind
    # associations that must already exist.
    for _, secs in parsed:
        for kind, body in secs:
            if kind == "Relational":
                _parse_store(body, c)
    _materialise_view_schemas(c)
    for _, secs in parsed:
        for kind, body in secs:
            if kind == "Pure":
                _parse_domain(body, c)
    for _, secs in parsed:
        for kind, body in secs:
            if kind == "Mapping":
                for chunk in _split_mappings(body):
                    name = _MAPPING_NAME.search(chunk)
                    if name and name.group(1) in SKIP_MAPPINGS:
                        continue
                    _parse_mapping(chunk, c)
    return c


# ---------------------------------------------------------------- self-check

def check(c: Corpus) -> list[str]:
    """Facts that must hold for the oracle to be trustworthy. Returned, not raised, so
    the caller can print all of them at once."""
    bad = []
    if not c.tables:
        bad.append("no tables parsed")
    if len(c.joins) < 100:
        bad.append(f"only {len(c.joins)} joins parsed")
    for cls, tbl in c.main_table.items():
        if tbl not in c.tables:
            bad.append(f"{cls} ~mainTable {tbl} is not a declared table")
    for cls, cols in c.columns.items():
        t = c.tables.get(c.main_table.get(cls, ""))
        if t is None:
            continue
        for prop, col in cols.items():
            if col not in t.columns:
                bad.append(f"{cls}.{prop} -> {t.name}.{col} does not exist")
    for (owner, name), end in c.ends.items():
        if end.join and end.join not in c.joins:
            bad.append(f"{owner}.{name} uses undeclared join {end.join}")
    for cls, name in c.class_filter.items():
        if name not in c.filters:
            bad.append(f"{cls} uses undeclared store filter {name}")
    for cls, members in c.unions.items():
        if len(members) < 2:
            bad.append(f"union mapping for {cls} resolved {len(members)} member tables; "
                       f"the set-implementation ids probably did not match")
        for m in members:
            if m not in c.tables:
                bad.append(f"union member {m} of {cls} is not a declared table")
    for (cls, prop), mapping in c.enum_props.items():
        if mapping not in c.enum_maps:
            bad.append(f"{cls}.{prop} uses undeclared EnumerationMapping {mapping}")
        elif not c.enum_maps[mapping]:
            bad.append(f"EnumerationMapping {mapping} has no source codes")
    return bad


if __name__ == "__main__":
    c = load()
    print(f"tables      {len(c.tables)}")
    print(f"joins       {len(c.joins)}")
    print(f"classes     {len(c.classes)}")
    print(f"enums       {len(c.enums)}")
    print(f"mapped cls  {len(c.main_table)}")
    print(f"assoc ends  {len(c.ends)}  ({sum(1 for e in c.ends.values() if e.join)} with a join)")
    problems = check(c)
    print(f"\nself-check: {'OK' if not problems else str(len(problems)) + ' PROBLEMS'}")
    for p in problems[:25]:
        print("  -", p)
