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

import rhs

STRESS = Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"


# ---------------------------------------------------------------- data model

@dataclass
class Column:
    name: str
    type: str
    pk: bool = False
    # Whether the DDL forbids a NULL here. Previously untracked, which is why the corpus's
    # adversarial NULL property (A2) only ever covered FOREIGN KEYS: nothing else could be
    # nulled without risking a NOT NULL violation in the generated DDL. That left every
    # transform over a plain column -- a dynafunction, most obviously -- with no null case
    # to meet.
    not_null: bool = False

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
    # Which Database declared it. Table names are scoped PER DATABASE in Legend; this
    # reader keys them globally, so two stores declaring the same name would silently
    # collide and one mapping would bind to the other's table. check() rejects that
    # rather than letting it happen quietly.
    database: str = ""
    # The Schema block the table is declared inside, or "default" when there is none.
    # Untracked until now, so a schema-qualified table could not be MAPPED (the mapping
    # names `[db]schema.TABLE`) nor SEEDED (a ###Data element keys rows by `schema.TABLE`),
    # and a Schema could only ever be declared. Both forms were verified against the engine
    # before this was added -- see scripts/corpus/repro/ -- rather than assumed to work.
    schema: str = "default"
    columns: dict[str, Column] = field(default_factory=dict)
    # A list, not one: a BITEMPORAL table declares both business and processing
    # milestoning in the same block, and the two are combined at query time.
    milestoning: list[Milestoning] = field(default_factory=list)

    def milestone(self, kind: str) -> Milestoning | None:
        for m in self.milestoning:
            if m.kind == kind:
                return m
        return None

    @property
    def pk(self) -> list[str]:
        return [c.name for c in self.columns.values() if c.pk]


@dataclass
class Join:
    """A.X = B.Y. Direction is resolved at use, not here — the same join is
    traversed both ways by the two ends of an association.

    `condition` carries the FULL parsed condition when the join is more than one equality --
    multi-column, non-equality, `or`, a dynafunction on either side. The four simple fields
    stay populated for the single-equality case, which is 192 of this corpus's 198 joins and
    which the indexed walk depends on; a general condition cannot be indexed, so it is
    evaluated per candidate row instead.

    Before this existed the reader dropped every join it could not fit into the four fields,
    without a word. Five of the six generated dense joins were in that state: unusable by any
    mapping, unexecutable by any service, and still counted as present by the feature meter.
    """
    name: str
    left_table: str
    left_col: str
    right_table: str
    right_col: str
    condition: object | None = None
    tables: tuple = ()

    @property
    def self_join(self) -> bool:
        return self.left_table == self.right_table

    def other(self, frm: str) -> tuple[str, str, str]:
        """(target_table, from_col, to_col) when entered from table `frm`.

        For a SELF-join both ends are the same table, so `frm` cannot disambiguate
        direction. The left-hand side is taken as the owning end — TRADER.MANAGER_ID
        points AT TRADER.TRADER_ID — which makes `manager` the navigable direction here.
        Reaching `reports` needs the reverse and is not modelled; the oracle refuses it
        rather than silently returning managers.
        """
        if self.self_join:
            return self.left_table, self.left_col, self.right_col
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
class Func:
    """A standalone `function pkg::name(p: T[m], ...): R[m] { body }`.

    Stored as source text like a derived property; oracle.py evaluates it with the same
    expression evaluator. The FIRST parameter is the receiver — these are called
    extension-style, `$x->pkg::name()` — and the rest are supplied at the call site.
    """
    fqn: str
    params: list[tuple[str, str]]      # (name, type)
    ret: str
    body: str


@dataclass
class Klass:
    fqn: str
    props: dict[str, Prop] = field(default_factory=dict)
    derived: dict[str, Derived] = field(default_factory=dict)
    stereotypes: list[str] = field(default_factory=list)
    supertype: str | None = None

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
    # WHICH association declared this end. Recorded rather than inferred from the
    # association's NAME, which is what binding used to rely on: it split the short
    # name on "_" and required both class names to appear. An association not named
    # `A_B` therefore bound to nothing -- its ends kept `join=None` and every
    # navigation through it silently failed to resolve. Two of this corpus's
    # associations were in that state.
    assoc: str = ""


@dataclass
class Corpus:
    tables: dict[str, Table] = field(default_factory=dict)
    joins: dict[str, Join] = field(default_factory=dict)
    classes: dict[str, Klass] = field(default_factory=dict)
    enums: dict[str, list[str]] = field(default_factory=dict)
    functions: dict[str, Func] = field(default_factory=dict)
    # class fqn -> table name
    main_table: dict[str, str] = field(default_factory=dict)
    # (class fqn, property) -> (dynafunction name, [column, ...]) for property mappings
    # that TRANSFORM rather than copy. Kept beside `columns` rather than inside it because
    # six modules read `columns` expecting a bare column name; a transform there would
    # change meaning silently everywhere.
    dyna: dict[tuple[str, str], tuple[str, list[str]]] = field(default_factory=dict)

    # class -> (table, column) whose JSON payload backs it, via a Binding transformer. Its
    # properties are keys in that JSON, not columns.
    json_backed: dict[str, tuple[str, str]] = field(default_factory=dict)

    # mapping set id -> the class it maps. Needed to resolve `extends [setId]`, which names
    # a SET rather than a class.
    setid_class: dict[str, str] = field(default_factory=dict)

    # (parent class, property) -> embedded class. The hop stays on the SAME row: an
    # embedded property has no join and no association, so navigation must not look for one.
    embedded: dict[tuple[str, str], str] = field(default_factory=dict)

    # class fqn -> the Mapping that declared its class mapping (first one wins).
    declared_in: dict[str, str] = field(default_factory=dict)

    # (class, property) -> (binding path, table, column) for a Binding transformer. Recorded
    # so the property is not silently fabricated from the binding's NAME, and so a generator
    # can see it exists without the oracle having to deserialize the payload.
    bindings: dict[tuple[str, str], tuple[str, str, str]] = field(default_factory=dict)

    # (class, property) -> ([join names in order], target table, target column) for a
    # property mapped through a JOIN CHAIN rather than a column of the main table.
    chains: dict[tuple[str, str], tuple[list[str], str, str]] = field(default_factory=dict)

    # (class, property) the reader saw but cannot model -- join chains, Binding transformers.
    # Visible so a service using one fails with a reason rather than a mystery.
    unparsed: list[tuple[str, str]] = field(default_factory=list)

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
    # Filters declared as MultiGrainFilter. Referenced from a mapping exactly like a Filter
    # -- the distinction is consumed by the planner, for join elision -- so the reader kept
    # no record of which was which and the feature could not be told apart from an ordinary
    # Filter even in principle.
    multigrain: set = field(default_factory=set)

    # cls -> ([join, ...], filter name). The predicate applies to the row the chain LANDS
    # on, so a row whose chain breaks is excluded -- unlike a to-one projection, where a
    # broken chain yields NULL and the row survives.
    class_filter_chain: dict[str, tuple[list[str], str]] = field(default_factory=dict)

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
            child = self.embedded.get((cls, step))
            if child is not None:
                # An EMBEDDED hop stays on the same row: no join, no association, the
                # sub-object's columns live on the parent's table. So the class advances and
                # `table` and `hops` deliberately do not.
                if last:
                    raise KeyError(f"path ends on embedded property {cls}.{step}")
                cls = child
                continue
            src = self.json_backed.get(cls)
            if src is not None and step in self.classes.get(cls, Klass(cls)).props:
                # A Binding-backed class's properties are JSON KEYS. There is no column per
                # key -- they all come out of the one serialized column -- so that column is
                # what resolves, and the VALUE is deserialized by the oracle. Reported this
                # way so callers that only need the table and kind (rendering, for one) work
                # without knowing about bindings at all.
                return src[0], src[1], hops
            col = self.columns.get(cls, {}).get(step)
            if col is None:
                raise KeyError(f"{cls}.{step} is neither a mapped property nor an association")
            if not last:
                raise KeyError(f"{cls}.{step} is a column but the path continues")
            return table, col, hops
        raise KeyError("empty path")

    def owner_hops(self, root: str, path: list[str]):
        """(hops, table) reaching the row that CARRIES `path` -- every step consumed as a
        navigation, none required to end on a column.

        resolve() refuses a path ending on an embedded property, because such a property has
        no column of its own. That is right for a projection and wrong for asking "which row
        do this property's columns live on", which is what a dynafunction inside an embedded
        block needs: the block advances the class but contributes no hop, since the
        sub-object's columns are on the parent's row.
        """
        cls, table, hops = root, self.main_table.get(root), []
        if table is None:
            raise KeyError(f"class {root} has no ~mainTable")
        for step in path:
            end = self.ends.get((cls, step))
            if end is not None:
                if end.join is None:
                    raise KeyError(f"{cls}.{step} has no AssociationMapping")
                j = self.joins[end.join]
                tgt, fc, tc = j.other(table)
                hops.append((end.join, table, fc, tgt, tc))
                cls, table = end.target, tgt
                continue
            child = self.embedded.get((cls, step))
            if child is not None:
                cls = child                      # same row: deliberately no hop
                continue
            raise KeyError(f"{cls}.{step} is not a navigation")
        return hops, table

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
            end = self.ends.get((cls, step))
            # An embedded hop advances the class without an association, so it has to be
            # consulted here too -- otherwise this raises on the very paths resolve() can
            # walk, and the two disagree about what the model contains.
            cls = end.target if end is not None else self.embedded[(cls, step)]
        return cls

    def to_many_on(self, root: str, path: list[str]) -> bool:
        """True if any hop along the path is to-many — i.e. the projection fans out."""
        cls = root
        for step in path[:-1]:
            end = self.ends.get((cls, step))
            if end is None:
                # An EMBEDDED hop cannot fan out: the sub-object lives on the same row, so
                # it contributes exactly one. Third navigation helper to need this -- resolve,
                # owner_of and now to_many_on -- which is the cost of adding a hop KIND
                # rather than a new association.
                cls = self.embedded[(cls, step)]
                continue
            if end.to_many:
                return True
            cls = end.target
        return False


# ---------------------------------------------------------------- parsing

_TABLE = re.compile(r"^\s*Table\s+(\w+)\s*\((.*)\)\s*$")
# `{target}` names the far side of a SELF-join, where both ends are the same table.
# Writing the table name twice would be a tautology matching every row against itself.
# `[db]` qualifiers on either side, for a CROSS-DATABASE join. Without admitting them the
# join was not captured at all -- and a chain referencing it failed with "join is not
# declared", which reads as a typo in the chain rather than as a join the reader could not
# see. Optional, so single-database joins are unaffected.
_JOIN = re.compile(
    r"^\s*Join\s+(\w+)\s*\(\s*(?:\[[\w:]+\]\s*)?(\w+)\.(\w+)\s*=\s*"
    r"(?:\[[\w:]+\]\s*)?(\{target\}|\w+)\.(\w+)\s*\)\s*$")
# A Filter's column may carry a store qualifier -- `Filter F([db]T.COL is not null)` -- and
# its predicate may be a NULL TEST rather than a comparison. Neither was accepted before,
# so three of this corpus's seven filters matched nothing and were silently absent from
# c.filters. That is invisible rather than wrong only because all three happen to exclude
# no rows; a filter that discriminated would have made every expectation for its class
# wrong, with nothing pointing at the filter.
# A Join whose condition is anything more than a single equality. Matched loosely and then
# PARSED, because the condition can nest and a regex that tried to span it would match the
# wrong extent rather than fail.
_JOIN_HEAD = re.compile(r"^\s*Join\s+(\w+)\s*\((.*)\)\s*$")
_FILTER_DECL = re.compile(
    r"^\s*(?:MultiGrain)?Filter\s+(\w+)\s*\(\s*(?:\[[\w:]+\]\s*)?(\w+)\.(\w+)\s*"
    r"(?:(=|<>|<=|>=|<|>)\s*(.+?)|(is\s+not\s+null|is\s+null))\s*\)\s*$")
_CLS_FILTER = re.compile(r"^\s*~filter\s*\[[\w:]+\]\s*(\w+)\s*$")
# `~filter [db]@J1 > @J2 | [db]NAME` -- a filter reached through a JOIN CHAIN. The predicate
# applies to the row the chain LANDS on, so a row whose chain breaks is excluded.
_CLS_FILTER_CHAIN = re.compile(
    r"^\s*~filter\s*(?:\[[\w:]+\]\s*)?((?:@\w+\s*>?\s*)+)\|\s*(?:\[[\w:]+\]\s*)?(\w+)\s*$")
# Stereotypes carry the temporal marker: `Class <<temporal.businesstemporal>> pkg::Name`
# The trailing `\s*$` used to be unconditional, which meant `Class X extends Y` did not
# match at all -- the class was SILENTLY SKIPPED and simply did not exist in the model. The
# corpus had no inheritance, so nothing ever noticed. Anything relying on a subclass would
# have failed with "unknown class" pointing at the USE rather than at the declaration the
# reader had quietly dropped.
_CLASS = re.compile(
    r"^\s*Class\s+(?:<<([^>]*)>>\s*)?([\w:]+)(?:\s+extends\s+([\w:]+))?\s*$")
_ASSOC = re.compile(r"^\s*Association\s+([\w:]+)\s*$")
_ENUM = re.compile(r"^\s*Enum\s+([\w:]+)\s*$")
_FUNC = re.compile(r"^\s*function\s+([\w:]+)\s*\(([^)]*)\)\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*$")
_FUNC_PARAM = re.compile(r"(\w+)\s*:\s*([\w:]+)\s*\[[^\]]+\]")
_PROP = re.compile(r"^\s*(\w+)\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
# `name() { expr } : T[m];` and the qualified form `name(p: T[1], ...) { expr } : T[m];`
_DERIVED = re.compile(
    r"^\s*(\w+)\s*\(([^)]*)\)\s*\{(.+)\}\s*:\s*([\w:]+)\s*\[([^\]]+)\]\s*;\s*$")
_PARAM = re.compile(r"(\w+)\s*:\s*[\w:]+\s*\[[^\]]+\]")
# A reference may be SCHEMA-QUALIFIED: `[db]schema.TABLE.COL`. Tables are keyed globally
# by name here, so the schema qualifier is matched and discarded -- but it has to be
# MATCHED, or the pattern reads `analytics` as the table and `COMBO_SUMMARY` as the
# column and the mapping silently records a property against a table that does not exist.
_MAIN = re.compile(r"^\s*~mainTable\s*\[[\w:]+\]\s*(?:\w+\.)?(\w+)\s*$")
# `Class: Relational`, `Class[id]: Relational`, and the root-marked `*Class: ...` form.
# `extends [parentId]` sits between the set id and the colon. Without admitting it, a
# subclass's class mapping did not match -- so `cur` stayed on the PREVIOUS class and every
# property of the subclass was recorded against its superclass. The symptom was
# "Can't find property 'coupon' in class 'hier::Instrument'" from the ENGINE, pointing at a
# generated service, three files away from the regex that caused it.
#
# Third construct to hit this exact blind spot: _CLASS could not see `Class X extends Y`,
# density's _BLOCK could not see a mapping using `extends [id]`, and now this. Each pattern
# was written before inheritance existed anywhere in the corpus.
_CLSMAP = re.compile(
    r"^\s*\*?([\w:]+)(?:\[(\w+)\])?(?:\s+extends\s*\[(\w+)\])?\s*:\s*Relational\s*\{?\s*$")
_OPMAP = re.compile(r"^\s*\*?([\w:]+)\s*:\s*Operation\s*\{?\s*$")
_UNION = re.compile(r"union_OperationSetImplementation_1__SetImplementation_MANY_"
                    r"\s*\(([^)]*)\)")
_COLMAP = re.compile(r"(\w+)\s*:\s*\[[\w:]+\]\s*(?:\w+\.)?(\w+)\.(\w+)")
# `prop: concat([db]T.A, [db]T.B)` -- a DYNAFUNCTION property mapping. Like _ENUMCOLMAP this
# must be stripped BEFORE _COLMAP runs, or _COLMAP matches the first column inside the
# parentheses and records the property as a plain column mapping -- silently turning a
# transform into a copy, which the oracle would then agree with for the wrong reason.
# `prop: fn(` -- the ANCHOR for a dynafunction property mapping. Only the head is matched
# by pattern; the extent of the call is found by balancing parentheses and the contents are
# parsed by rhs.py, because the argument list can nest and can mix chains with columns.
_CALL_START = re.compile(r"(\w+)\s*:\s*(\w+)\s*\(")
# A property mapping whose right-hand side this reader has no rule for: a join chain
# (`prop: @A > @B | T.col`), a Binding transformer, an embedded block opener.
# `prop: [db]@J1 > @J2 | [db]T.COL` -- a JOIN CHAIN property mapping. The value comes from a
# table reached by following joins, with no association involved, so `resolve` (which walks
# associations) cannot find it. Must run BEFORE _COLMAP, which would otherwise match the
# trailing `T.COL` and record the property as a plain column of the MAIN table -- turning a
# navigation into a copy, silently and wrongly.
_CHAINMAP = re.compile(
    r"(\w+)\s*:\s*((?:\[[\w:]+\]\s*)?@[\w@\s>\[\]:.]*?)\|\s*(?:\[[\w:]+\]\s*)?(\w+)\.(\w+)")
_CHAINJOIN = re.compile(r"@(\w+)")

# `prop: Binding path::B : [db]T.COL` -- a BINDING TRANSFORMER, reading a complex-typed
# property out of one column through an external-format binding.
#
# Must run BEFORE _COLMAP, and the consequence of not doing so was not a missed property but
# a FABRICATED one: _COLMAP matched the tail `ProfileBinding : [db]T.COL` and recorded a
# property called `ProfileBinding` on the class. A generated service then projected it and
# the engine answered "Can't find property 'ProfileBinding' in class 'hier::Issuer'" -- a
# property that never existed anywhere except in the reader's head.
# An EMBEDDED block opener: a bare property name followed by `(`. Its contents belong to the
# embedded CLASS, not to the parent, and this reader does not model that -- so they are
# routed to `unparsed` rather than flattened onto the parent, which is what it did before:
# `contact ( email: ..., phone: ... )` put `email` and `phone` on hier::Issuer, and a
# generated service projecting them was answered "Can't find property 'email' in class
# 'hier::Issuer'".
#
# `scope(` and `AssociationMapping(` share the shape and are excluded by name.
# The name and the `(` are usually on SEPARATE lines, so both forms are matched: the
# one-liner and a bare identifier whose next non-blank line is `(`.
# `scope([db]TABLE)` and the db-pointer-only `scope([db])`. Inside the first, property
# mappings are written with BARE column names; inside the second they carry `TABLE.COL`.
# Neither form matches _COLMAP, which requires a `[db]` prefix, so 60 generated class
# mappings were parsed as having no properties at all -- invisible rather than wrong,
# because each of those classes is also mapped flatly elsewhere.
_SCOPE_TABLE = re.compile(r"^\s*scope\s*\(\s*\[[\w:]+\]\s*(?:\w+\.)?(\w+)\s*\)\s*$")
_SCOPE_DB = re.compile(r"^\s*scope\s*\(\s*\[[\w:]+\]\s*\)\s*$")
_SCOPE_BARE = re.compile(r"(?:^|,)\s*(\w+)\s*:\s*(\w+)\s*(?:,|$)")
_SCOPE_QUAL = re.compile(r"(?:^|,)\s*(\w+)\s*:\s*(\w+)\.(\w+)\s*(?:,|$)")

_EMBED_OPEN = re.compile(r"^\s*(?!scope\b|AssociationMapping\b)([a-z]\w*)\s*\($")
_EMBED_NAME = re.compile(r"^\s*(?!scope\b|AssociationMapping\b)([a-z]\w*)\s*,?\s*$")

_BINDINGMAP = re.compile(
    r"(\w+)\s*:\s*Binding\s+([\w:]+)\s*:\s*(?:\[[\w:]+\]\s*)?(\w+)\.(\w+)")

_LEFTOVER_PROP = re.compile(r"(?:^|,)\s*(\w+)\s*:\s*\S", re.M)
# `prop: EnumerationMapping <Name>: [db] TABLE.COL` — must be stripped BEFORE _COLMAP
# runs, or _COLMAP matches the tail and records the mapping NAME as the property.
_ENUMCOLMAP = re.compile(
    r"(\w+)\s*:\s*EnumerationMapping\s+(\w+)\s*:\s*\[[\w:]+\]\s*(\w+)\.(\w+)")
_ENUMMAP_HEAD = re.compile(r"^\s*([\w:]+)\s*:\s*EnumerationMapping\s+(\w+)\s*$")
_ENUMMAP_ROW = re.compile(r"^\s*(\w+)\s*:\s*\[([^\]]*)\]\s*,?\s*$")
# An association end may name its SOURCE and TARGET set ids -- `end[srcId, tgtId]:` --
# which the pattern did not admit, so such an end bound to no join at all. The end
# still existed (it comes from the Association declaration), so the symptom was not a
# missing property but a navigation that could never resolve: `join=None`. The one
# association in the corpus written that way was unreachable for exactly this reason.
_ENDMAP = re.compile(r"(\w+)(?:\[\s*\w+\s*,\s*\w+\s*\])?\s*:\s*\[[\w:]+\]\s*@(\w+)")


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


_MILESTONING_BLOCK = re.compile(r"milestoning\s*\((.*?)\)\s*\)\s*$", re.S)
_MILESTONING_ONE = re.compile(
    r"(business|processing)\s*\("
    r"\s*(?:BUS_FROM|PROCESSING_IN)\s*=\s*(\w+)\s*,"
    r"\s*(?:BUS_THRU|PROCESSING_OUT)\s*=\s*(\w+)\s*"
    r"(?:,\s*INFINITY_DATE\s*=\s*%([\d:.T-]+)\s*)?\)", re.S)


def _parse_milestoning(body: str):
    """Returns (specs, remaining body). A block may hold one spec or two — business AND
    processing on the same table is BITEMPORAL, and the two predicates AND together."""
    i = body.find("milestoning")
    if i < 0:
        return [], body
    j, depth = body.index("(", i) + 1, 1
    while depth and j < len(body):
        if body[j] == "(":
            depth += 1
        elif body[j] == ")":
            depth -= 1
        j += 1
    inner = body[body.index("(", i) + 1:j - 1]
    specs = [Milestoning(m.group(1), m.group(2), m.group(3), m.group(4))
             for m in _MILESTONING_ONE.finditer(inner)]
    if not specs:
        raise ValueError(f"unparseable milestoning block: {inner!r}")
    return specs, body[:i] + body[j:]


def _table_bodies(text: str) -> list[tuple[str, str, int]]:
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
        out.append((m.group(1), text[m.end():i - 1], m.start()))
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


# A View may carry ~filter and ~distinct as well as ~groupBy, in that fixed order. The
# reader ignores the first two rather than modelling them: ~distinct changes which rows a
# view yields and ~filter changes which rows it sees, so a view using either is NOT
# oracle-computable from the base table alone. Stripping them keeps the reader honest about
# what it does understand -- the alternative was a parse failure that made the whole corpus
# unreadable the moment a view used a directive the base model never had.
_VIEW_DIRECTIVE = re.compile(r"~(filter\s+[\w:\[\]@|.]+|distinct)\s*", re.M)


def _parse_view(name: str, body: str, c: Corpus) -> None:
    body = _VIEW_DIRECTIVE.sub("", body)
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


_DATABASE = re.compile(r"^\s*Database\s+([\w:]+)\s*$", re.M)



def _owning_schema(text: str, at: int) -> str:
    """The Schema block enclosing the declaration at `at`, or "default".

    Found by scanning backwards for the nearest `Schema x (` and checking the parentheses
    between it and here still leave us inside it -- a Schema's tables are nested, so a
    plain "nearest preceding Schema" would also claim every table declared AFTER the block
    closed.
    """
    best = "default"
    for m in re.finditer(r"^\s*Schema\s+(\w+)\s*$", text[:at], re.M):
        depth = text.count("(", m.end(), at) - text.count(")", m.end(), at)
        if depth > 0:
            best = m.group(1)
    return best

def _owning_database(text: str, at: int) -> str:
    """The Database whose declaration most recently precedes this offset."""
    last = ""
    for m in _DATABASE.finditer(text):
        if m.start() > at:
            break
        last = m.group(1)
    return last


def _parse_store(text: str, c: Corpus) -> None:
    text = "\n".join(_strip(l) for l in text.splitlines())
    for name, body in _view_bodies(text):
        _parse_view(name, body, c)
    for name, body, at in _table_bodies(text):
        if name in c.tables and c.tables[name].database != _owning_database(text, at):
            # STORE SUBSTITUTION requires two databases declaring the SAME table -- that is
            # the whole mechanism: `include M [dbA -> dbB]` rewrites the store pointer and
            # every column reference has to still resolve. So a duplicate name is only a
            # hazard when the two tables are DIFFERENT; when the column sets are identical,
            # binding to "the other one" cannot change any answer.
            #
            # The check stays otherwise, because the hazard it guards is real (F7: names are
            # scoped per Database in Legend and global here, so two genuinely different
            # tables sharing a name would silently cross-bind).
            existing = {col.name for col in c.tables[name].columns.values()}
            incoming = set(re.findall(r"^\s*(\w+)\s+[A-Z]", body, re.M))
            if not existing or existing != incoming:
                raise ValueError(
                    f"table {name} is declared by both {c.tables[name].database} and "
                    f"{_owning_database(text, at)} with DIFFERENT columns. Names are scoped "
                    f"per Database in Legend but global here, so one mapping would bind to "
                    f"the other's table. (Identical shapes are allowed -- that is store "
                    f"substitution.)")
            continue
        t = Table(name, database=_owning_database(text, at),
                  schema=_owning_schema(text, at))
        t.milestoning, body = _parse_milestoning(body)
        for spec in _split_cols(body):
            spec = " ".join(spec.split())
            if not spec:
                continue
            parts = spec.split()
            u = spec.upper()
            t.columns[parts[0]] = Column(parts[0], parts[1],
                                         u.endswith("PRIMARY KEY"),
                                         "NOT NULL" in u)
        c.tables[t.name] = t

    for raw in text.splitlines():
        line = _strip(raw)
        m = _FILTER_DECL.match(line)
        if m:
            if m.group(6):                      # `is null` / `is not null`
                op = "isnull" if m.group(6).split()[1] == "null" else "isnotnull"
                val = None
            else:
                op = m.group(4)
                lit = m.group(5).strip()
                if lit.startswith("'") and lit.endswith("'"):
                    val = lit[1:-1]
                elif re.fullmatch(r"-?\d+", lit):
                    val = int(lit)
                elif re.fullmatch(r"-?\d*\.\d+", lit):
                    val = float(lit)
                else:
                    raise ValueError(f"Filter {m.group(1)}: unhandled literal {lit!r}")
            c.filters[m.group(1)] = (m.group(2), m.group(3), op, val)
            if line.lstrip().startswith("MultiGrainFilter"):
                c.multigrain.add(m.group(1))
            continue
        m = _JOIN.match(line)
        if m:
            n, lt, lc, rt, rc = m.groups()
            if n in c.joins:
                raise ValueError(f"duplicate join {n}")
            c.joins[n] = Join(n, lt, lc, lt if rt == "{target}" else rt, rc,
                              tables=(lt, lt if rt == "{target}" else rt))
            continue
        m = _JOIN_HEAD.match(line)
        if m:
            # A general condition. The two sides are whichever tables it names; `{target}`
            # resolves to the other one, since a self-join names its own table once and
            # {target} once.
            name, body = m.group(1), m.group(2)
            if name in c.joins:
                raise ValueError(f"duplicate join {name}")
            cond = rhs.parse_condition(body)
            named = sorted(rhs.condition_tables(cond))
            real = [x for x in named if x != "{target}"]
            if "{target}" in named:
                real = real * 2
            if len(real) != 2:
                raise ValueError(
                    f"Join {name} names {len(real)} table(s) {real} -- a join must connect "
                    f"exactly two. A condition mentioning ONE is rejected by the engine too, "
                    f"with \"can only find one table in the join\"; use the {{target}} form.")
            c.joins[name] = Join(name, real[0], "", real[1], "", condition=cond,
                                 tables=tuple(real))
            continue
        # A Join this reader cannot parse is DROPPED, and a dropped join is invisible in
        # exactly the way a dropped filter was: nothing references it, so nothing complains,
        # and the store object counts as "present" in the density meter while no mapping can
        # use it and no service can execute it. Five of the six generated dense joins --
        # multi-column, non-equality, `or`, and both dynafunction forms -- were in that state.
        if line.strip().startswith("Join "):
            raise ValueError(
                f"Join form not modelled by this reader -- {line.strip()!r}. Extend it "
                f"deliberately; dropping it silently makes the join uncountable and "
                f"unexecutable while leaving it visible to a text-matching feature meter.")


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
    cur_func = None
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
            k.supertype = m.group(3)
            continue
        m = _ASSOC.match(line)
        if m:
            cur_assoc, cur_class, cur_enum = m.group(1), None, None
            ends = []
            continue
        m = _FUNC.match(line)
        if m:
            cur_func = Func(m.group(1), _FUNC_PARAM.findall(m.group(2)), m.group(3), "")
            cur_class = cur_assoc = cur_enum = None
            continue
        if cur_func is not None:
            s = line.strip()
            if s == "{":
                continue
            if s == "}":
                c.functions[cur_func.fqn] = cur_func
                cur_func = None
                continue
            cur_func.body += (" " if cur_func.body else "") + s
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
                c.ends[(t1, n0)] = AssocEnd(t1, n0, t0, u0 is None or u0 > 1,
                                            assoc=cur_assoc)
                c.ends[(t0, n1)] = AssocEnd(t0, n1, t1, u1 is None or u1 > 1,
                                            assoc=cur_assoc)
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


def _parse_mapping(text: str, c: Corpus, mapping_name: str | None = None) -> None:
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
            embedded, pending_embed, scope_tbl = None, None, None
            if cur_id:
                c.setid_class[cur_id] = cur
            # `extends [parentSetId]` INHERITS the parent set's main table and property
            # mappings -- an extending set declares only what it adds, and carries no
            # ~mainTable of its own. Without this the subclass had no table and no columns,
            # so inheritance was covered at compile time and by nothing else.
            parent_set = m.group(3)
            if parent_set:
                parent_cls = c.setid_class.get(parent_set)
                if parent_cls:
                    c.main_table.setdefault(cur, c.main_table.get(parent_cls, ""))
                    for pp, pcol in c.columns.get(parent_cls, {}).items():
                        c.columns.setdefault(cur, {}).setdefault(pp, pcol)
            # WHICH mapping declared this class mapping. The reader is otherwise
            # mapping-agnostic, and deriving a service's mapping from the class's TABLE
            # instead got it wrong the moment a mapping spanned two stores: hier::
            # InstrumentReach is declared in IssuerMapping with a main table in HierDB, and
            # routing by database produced "Error mapping not found for class
            # InstrumentReach". Only the FIRST declaration is kept -- a class mapped twice
            # still collapses, which is the wider limitation this does not fix.
            if mapping_name and cur not in c.declared_in:
                c.declared_in[cur] = mapping_name
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
        m = _CLS_FILTER_CHAIN.match(line)
        if m and cur:
            c.class_filter_chain[cur] = (_CHAINJOIN.findall(m.group(1)), m.group(2))
            continue
        # A ~filter this reader cannot model changes what the class MEANS -- all() returns a
        # different row set -- so every expectation for that class would be silently wrong.
        # Dropping it is the worst available option and is exactly what happened: the
        # chain-filter form matched neither pattern and vanished, and the corpus only stayed
        # green because the filter excluded no rows. Raise instead.
        if line.strip().startswith("~filter") and cur:
            raise ValueError(
                f"{cur}: ~filter form not modelled by this reader -- {line.strip()!r}. "
                f"Extend the reader deliberately; ignoring it would change what all() "
                f"returns for {cur} with nothing pointing at the cause.")
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
            m_sc = _SCOPE_TABLE.match(line)
            if m_sc:
                scope_tbl, pending_embed = m_sc.group(1), None
                continue
            if _SCOPE_DB.match(line):
                scope_tbl, pending_embed = "", None      # qualified inside
                continue
            if scope_tbl is not None:
                if line.strip() in ("(", ""):
                    continue
                if line.strip().startswith(")"):
                    scope_tbl = None
                    continue
                if scope_tbl:
                    # Value forms FIRST, with the scope's table supplied for bare columns.
                    # A dynafunction inside a scope -- `up: toUpper(A)` -- is accepted by
                    # the engine and matched none of the scope patterns, which recognise
                    # `prop: COL` only; it was therefore dropped, and a scope block could
                    # hold nothing but plain columns as far as this reader was concerned.
                    rest = _value_forms(line, c, cur, scope_tbl, bare=scope_tbl)
                    for sp, sc in _SCOPE_BARE.findall(rest):
                        c.columns.setdefault(cur, {}).setdefault(sp, sc)
                else:
                    rest = _value_forms(line, c, cur, tbl)
                    for sp, _st, sc in _SCOPE_QUAL.findall(rest):
                        c.columns.setdefault(cur, {}).setdefault(sp, sc)
                continue
            m_emb = _EMBED_OPEN.match(line)
            if m_emb:
                embedded = m_emb.group(1)
                continue
            if pending_embed and line.strip() == "(":
                embedded, pending_embed = pending_embed, None
                continue
            m_name = _EMBED_NAME.match(line)
            pending_embed = m_name.group(1) if m_name else None
            if m_name:
                continue
            if embedded is not None:
                if line.strip().startswith(")"):
                    embedded, pending_embed = None, None
                    continue
                # An embedded property's sub-mappings read columns of the SAME row -- no
                # join, no association. So the embedded CLASS is given the parent's table
                # and its own columns, and the hop from parent to child is recorded as
                # embedded so navigation knows to stay on the row rather than look for an
                # association that does not exist.
                child = None
                kl = c.classes.get(cur)
                if kl is not None and embedded in kl.props:
                    child = kl.props[embedded].type
                if child is None:
                    for sub in _LEFTOVER_PROP.findall(line):
                        c.unparsed.append((cur, f"{embedded}.{sub}"))
                    continue
                # The hop is recorded from the BLOCK, not from the first column mapping
                # inside it. Recording it per-column meant a block whose every entry was a
                # dynafunction registered no embedded hop at all, so the property looked
                # absent rather than unmodelled.
                c.embedded[(cur, embedded)] = child
                c.main_table.setdefault(child, tbl)
                rest = _value_forms(line, c, child, tbl)
                for sub, _sc_tbl, sc_col in _COLMAP.findall(rest):
                    c.columns.setdefault(child, {})[sub] = sc_col
                for sub in _LEFTOVER_PROP.findall(_COLMAP.sub("", rest)):
                    if (sub not in c.columns.get(child, {})
                            and (child, sub) not in c.chains
                            and (child, sub) not in c.dyna):
                        c.unparsed.append((cur, f"{embedded}.{sub}"))
                continue
            for prop, mapping, t, col in _ENUMCOLMAP.findall(line):
                if t != tbl:
                    raise ValueError(f"{cur}.{prop} maps to {t}, not mainTable {tbl}")
                c.columns.setdefault(cur, {})[prop] = col
                c.enum_props[(cur, prop)] = mapping
            line = _ENUMCOLMAP.sub("", line)
            # Binding transformers first: their tail is a plain column mapping preceded by
            # a binding path, so _COLMAP would fabricate a property from the binding's name.
            for prop, binding, btbl, bcol in _BINDINGMAP.findall(line):
                c.bindings[(cur, prop)] = (binding, btbl, bcol)
                # A Binding transformer reads a COMPLEX property out of ONE column holding
                # serialized JSON. So the hop behaves like an embedded one -- it stays on
                # the same row -- but the sub-properties are JSON KEYS rather than columns,
                # which is recorded separately so the oracle knows to deserialize instead of
                # looking for columns that do not exist.
                kl = c.classes.get(cur)
                child = kl.props[prop].type if kl and prop in kl.props else None
                if child:
                    c.embedded[(cur, prop)] = child
                    c.json_backed[child] = (btbl, bcol)
                    c.main_table.setdefault(child, btbl)
            line = _BINDINGMAP.sub("", line)
            line = _value_forms(line, c, cur, tbl)
            # Anything that still LOOKS like a property mapping after the recognised forms
            # have been stripped is a form this reader does not model -- a join chain, a
            # Binding transformer, an embedded block. Recorded rather than dropped.
            #
            # Silent dropping is a pattern that has now bitten three times: `Class X extends
            # Y` did not match _CLASS and the class simply did not exist; a mapping using
            # `extends [id]` matched no block in the density meter; and join-chain properties
            # vanished here. In every case the symptom appeared far from the cause. A
            # property the oracle cannot resolve should be visible in the reader, not
            # discovered when a service using it fails.
            for prop, t, col in _COLMAP.findall(line):
                if t != tbl:
                    raise ValueError(f"{cur}.{prop} maps to {t}, not mainTable {tbl}")
                c.columns.setdefault(cur, {})[prop] = col

            # CATCH-ALL, not a pattern list. Anything still shaped like `prop:` after every
            # recognised form has been stripped is a property mapping this reader does not
            # model, and it is recorded rather than dropped.
            #
            # The previous version matched only `@` and `Binding` right-hand sides, so a
            # dynafunction OVER a join chain -- `toUpper([db]@J1 > @J2 | [db]T.COL)` -- fell
            # through both the recognisers and the leftover check and vanished entirely.
            # That was the sixth silent drop in this reader. A catch-all cannot have a
            # seventh: a construct it does not recognise is, by construction, still shaped
            # like `prop:`.
            for leftover in _LEFTOVER_PROP.findall(_COLMAP.sub('', line)):
                if leftover in ("mainTable", "filter", "groupBy", "distinct",
                                "primaryKey", "src", "func"):
                    continue
                # Only if NOTHING has resolved this property. A scope() block writes bare
                # `prop: COL` with no [db]TABLE. prefix and this reader does not model
                # scope -- but the same property is usually also mapped flatly elsewhere,
                # and a property the oracle CAN resolve by another route is not a gap.
                # Without this the list was 653 entries, almost all of them scope-block
                # restatements of columns already known.
                if (leftover not in c.columns.get(cur, {})
                        and (cur, leftover) not in c.chains
                        and (cur, leftover) not in c.dyna):
                    c.unparsed.append((cur, leftover))


def _value_forms(line: str, c: "Corpus", owner: str, tbl: str,
                 bare: str | None = None) -> str:
    """Record every VALUE-expression property mapping on `line` against `owner`.

    Returns the line with the recognised forms stripped, so the caller can decide what to do
    with whatever is left.

    Extracted because the top-level path and the embedded-block path had drifted: the
    embedded path understood plain columns and nothing else, so a dynafunction or a join
    chain inside an embedded block went to `unparsed` -- 22 of the combination matrix's 48
    cells, all of which the reader understood perfectly well one nesting level up. Two
    copies of "how a property mapping is written" is one copy too many.
    """
    while True:
        m = _CALL_START.search(line)
        if m is None:
            break
        prop, _fn = m.group(1), m.group(2)
        close = rhs.find_call(line, line.index("(", m.end() - 1))
        node = rhs.parse(line[m.start(2):close + 1], default_table=bare)
        _tag, (fname, args) = node
        c.dyna[(owner, prop)] = (fname, args)
        # A plain-column argument on the main table keeps its entry in c.columns, so
        # everything that types or resolves a property by column continues to work for the
        # shapes that already existed. An expression whose every argument is a chain has no
        # such column and is typed from the function's return kind instead.
        #
        # A PLAIN argument must sit on the main table, because that is the row the oracle
        # holds. A value from another table is legal in the grammar but has to be written as
        # a chain, so the reader records how to REACH it rather than assuming the column is
        # somehow already in hand.
        local = []
        for atbl, acol in rhs.columns(node):
            if atbl != tbl:
                raise ValueError(
                    f"{owner}.{prop} uses {fname}() over {atbl}.{acol}, not mainTable "
                    f"{tbl} -- write it as a chain if it needs a join, since the oracle "
                    f"has no row of {atbl} to read it from")
            local.append(acol)
        if local:
            c.columns.setdefault(owner, {})[prop] = local[0]
        line = line[:m.start(1)] + line[close + 1:]
    # Join chains BEFORE plain columns: a chain's tail looks exactly like one.
    for prop, chaintext, ctbl, ccol in _CHAINMAP.findall(line):
        joins = _CHAINJOIN.findall(chaintext)
        if joins:
            c.chains[(owner, prop)] = (joins, ctbl, ccol)
    return _CHAINMAP.sub("", line)


def _assoc_matches(assoc_fqn: str, owner: str, end: AssocEnd) -> bool:
    """An AssociationMapping names its ends by property only, so binding a property to the
    right association relies on the association's NAME carrying both class names.

    A SELF-association breaks that: org::Trader_Manager has owner == target == Trader, so
    the pair {Trader, Manager} never matches {Trader}. Handled explicitly — for a
    self-association it is enough that one half of the name is the class.
    """
    if end.assoc:
        return end.assoc == assoc_fqn
    short = assoc_fqn.split("::")[-1]
    a, _, b = short.partition("_")
    o, t = owner.split("::")[-1], end.target.split("::")[-1]
    if o == t:
        return o in (a, b)
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
SKIP_MAPPINGS = {
    "reporting::EmbeddedFlatMapping",
    # An M2M mapping has no tables at all — it maps class to class. The line-based reader
    # here understands `prop: [db] TABLE.COL` and nothing else, so parsing it would find
    # no mainTable and attach nothing. Skipped for the same reason as the embedded
    # mapping: the canonical class's expectation is DERIVED from the source class it is
    # mapped from, which is what the invariance asserts.
    "canonical::M2MMapping",
    "canonical::MoneyMapping",
    # Both map trading::Trade onto TRADE_FLAT_PARTIAL with embedded blocks; parsing them
    # would rebind Trade's ~mainTable exactly as EmbeddedFlatMapping would. Their
    # expectations are mirrored from the canonical model, which is the claim under test.
    "reporting::PartialEmbeddedMapping",
    "reporting::OtherwiseMapping",
    "reporting::InlineFlatMapping",
    # AggregationAware has its own grammar -- Views / ~modelOperation / ~mainMapping with
    # NESTED Relational blocks. The line-based reader here would bind those inner blocks
    # to the wrong class. Its expectations are mirrored from equivalent queries on
    # trading::Trade, which is exactly the equivalence the mapping claims.
    "reporting::AggregationAwareMapping",
    # XStore relates classes by a PREDICATE, not a join, and declares local (+) properties
    # the reader does not model. It also re-maps trading::Trade onto the same table, which
    # would fight the canonical binding.
    "external::EntityMapping",
    # Relation class mappings bind to relation COLUMN names, not [db]Table.COLUMN, and
    # ModelJoin is a predicate rather than a join. Neither shape is modelled here.
    "modeljoin::JoinMapping",
}

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
                    _parse_mapping(chunk, c, name.group(1) if name else None)
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
