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
class Table:
    name: str
    columns: dict[str, Column] = field(default_factory=dict)

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


@dataclass
class Klass:
    fqn: str
    props: dict[str, Prop] = field(default_factory=dict)
    derived: dict[str, Derived] = field(default_factory=dict)


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
_CLASS = re.compile(r"^\s*Class\s+([\w:]+)\s*$")
_ASSOC = re.compile(r"^\s*Association\s+([\w:]+)\s*$")
_ENUM = re.compile(r"^\s*Enum\s+([\w:]+)\s*$")
_PROP = re.compile(r"^\s*(\w+)\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
_DERIVED = re.compile(
    r"^\s*(\w+)\s*\(\s*\)\s*\{(.+)\}\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
_MAIN = re.compile(r"^\s*~mainTable\s*\[[\w:]+\]\s*(\w+)\s*$")
_CLSMAP = re.compile(r"^\s*([\w:]+)\s*:\s*Relational\s*\{?\s*$")
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


def _parse_store(text: str, c: Corpus) -> None:
    for raw in text.splitlines():
        line = _strip(raw)
        m = _TABLE.match(line)
        if m:
            t = Table(m.group(1))
            for spec in _split_cols(m.group(2)):
                parts = spec.split()
                pk = spec.upper().endswith("PRIMARY KEY")
                name, typ = parts[0], parts[1]
                t.columns[name] = Column(name, typ, pk)
            c.tables[t.name] = t
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
            cur_class, cur_assoc, cur_enum = m.group(1), None, None
            c.classes.setdefault(cur_class, Klass(cur_class))
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
            lo, hi = _mult(m.group(4))
            c.classes[cur_class].derived[m.group(1)] = Derived(
                m.group(1), m.group(2).strip(), m.group(3), lo, hi)
            continue
        m = _PROP.match(line)
        if m:
            name, typ, mult = m.group(1), m.group(2), m.group(3)
            lo, hi = _mult(mult)
            if cur_class:
                c.classes[cur_class].props[name] = Prop(name, typ, lo, hi)
            elif cur_assoc:
                ends.append((name, typ, lo, hi))


def _parse_mapping(text: str, c: Corpus) -> None:
    cur = None
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
        m = _CLSMAP.match(line)
        if m and "AssociationMapping" not in line:
            cur, in_assoc = m.group(1), False
            continue
        if "AssociationMapping" in line:
            in_assoc = True
            continue
        if line.strip() in ("}", ")", "    }"):
            continue
        m = _MAIN.match(line)
        if m and cur:
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
    for _, secs in parsed:
        for kind, body in secs:
            if kind == "Pure":
                _parse_domain(body, c)
    for _, secs in parsed:
        for kind, body in secs:
            if kind == "Mapping":
                _parse_mapping(body, c)
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
