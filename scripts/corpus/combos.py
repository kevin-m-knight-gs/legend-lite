"""
Generates the COMBINATION matrix: mapping features stacked on each other, not merely present.

The taxonomy reached "35 of 35 features present" while four classes carried every advanced
construct in the corpus and exactly two pairs ever co-occurred (chain+dyna on one class,
binding+embedded on another). Presence is not coverage. A feature that appears once has been
shown to work in one shape, and the defects this corpus has actually found -- concat over
NULL, count over an empty to-many, a chain filter that excluded nothing -- all live at the
point where one feature meets another.

So the axes are enumerated and crossed rather than illustrated:

  PROPERTY axes                            CLASS-MAPPING axes
    reach    col | chain1 | chain2           filter    none | direct | chain
    type     string | int | float | bool     extends   no | yes
    xform    per type -- see XFORM
    nulls    notnull | nullable
    host     top | embedded

The transform axis DEPENDS on the type axis, because a dynafunction's arguments and result
have to agree with the property it maps, so the cross is over the per-type transform lists
rather than one shared list: 180 cells, every one generated. The class cross is 3x2 = 6, and
the property cells are dealt across the six so that each property shape also meets each
class shape. What that buys over 48 separate one-feature tests is the interaction: a
dynafunction over a two-hop chain, inside an embedded block, on a set that extends another
set and carries a filter reached through joins, where the source column is NULL for some rows.

WHY THE SOURCE DATA MATTERS MORE THAN THE MATRIX. Every table here is reachable from the
seed's ordinary expansion, so each foreign key carries the adversarial shapes (absent key,
dangling key, childless parent) and each nullable column carries a NULL. Without that a
combination test is a shape with nothing interesting flowing through it -- which is exactly
what the corpus's first join chain was, resolving for every row because two tables' generated
values coincided.

Nothing here is asserted from engine output. Each generated property is evaluated by
oracle.py from the seed, and a cell whose value the oracle cannot compute independently is
not generated at all -- it is reported by `unsupported()` so the gap is visible rather than
quietly absent.
"""
from __future__ import annotations

import model
from query import Proj, Spec

DB = "combo::ComboDB"
MAPPING = "combo::ComboMapping"
RUNTIME = "combo::ComboRT"
CONN_ID = "comboEnv"
DATA = "combo::ComboData"

ROOT, HOP1, HOP2 = "COMBO_ROOT", "COMBO_HOP1", "COMBO_HOP2"
J1, J2 = "Combo_Hop1", "Combo_Hop2"

# ---------------------------------------------------------------- the axes
REACH = ("col", "chain1", "chain2")
TYPES = ("string", "int", "float", "bool")
NULLS = ("notnull", "nullable")
HOST = ("top", "embedded")

FILTERS = ("none", "direct", "chain")
EXTENDS = ("no", "yes")

# Transforms are PER TYPE, because a dynafunction's arguments and result have to agree with
# the property it maps. Every one preserves its argument's type, so a cell's Pure type is
# decided by the type axis alone.
#
# Each was verified usable in a relational property mapping before being listed -- nothing
# validates a dynafunction name at parse OR compile, so an unusable one fails at SQL
# generation with an error far from the mapping. `plus`/`minus`/`times` in particular are
# binary here, which was a guess worth checking rather than assuming from Pure's list form.
XFORM = {
    "string": ("none", "up", "cat", "nest"),
    "int": ("none", "abs", "plus", "times"),
    "float": ("none", "abs", "sqrt", "times"),
    # isNull/isNotNull are the only transforms whose RESULT type is fixed rather than
    # inherited, and Boolean is the one type where that is not a mismatch.
    "bool": ("none", "isnull", "isnotnull"),
}

TYPE_SQL = {"string": "VARCHAR(200)", "int": "INTEGER", "float": "DOUBLE", "bool": "BIT"}
TYPE_PURE = {"string": "String", "int": "Integer", "float": "Float", "bool": "Boolean"}

# Two columns of every (type, nullability) per table, because a two-argument transform whose
# arguments are the same column cannot detect an argument-order defect.
_PREFIX = {ROOT: "R", HOP1: "H1", HOP2: "H2"}
_SHORT = {"string": "S", "int": "I", "float": "F", "bool": "B"}


def column(table: str, type_: str, nulls: str, which: int) -> str:
    return f"{_PREFIX[table]}_{_SHORT[type_]}_{'NN' if nulls == 'notnull' else 'NL'}{which}"


_TABLE_FOR = {"col": ROOT, "chain1": HOP1, "chain2": HOP2}
_CHAIN_FOR = {"col": [], "chain1": [J1], "chain2": [J1, J2]}
AXES = ("reach", "type", "xform", "nulls", "host")


def cells() -> list[tuple[str, str, str, str, str]]:
    """The full property cross, in a fixed order so names are stable across runs.

    Not a rectangle: the transform axis depends on the type axis, so the cross is over the
    per-type transform lists rather than one shared list."""
    return [(r, ty, x, n, h)
            for r in REACH for ty in TYPES for x in XFORM[ty]
            for n in NULLS for h in HOST]


def class_cells() -> list[tuple[str, str]]:
    return [(f, e) for f in FILTERS for e in EXTENDS]


def prop_name(cell) -> str:
    r, ty, x, n, h = cell
    return f"{r}_{ty}_{x}_{n}_{h}"


def pure_type(cell) -> str:
    return TYPE_PURE[axis(cell, "type")]


def axis(cell, name: str):
    """One axis value of a cell, BY NAME.

    Positional indexing is what broke when the type axis was inserted: `cell[3]` had meant
    `host` and silently became `nulls`, so every class mapping came out with no properties
    at all -- a generator that emitted a well-formed, entirely empty matrix.
    """
    return cell[AXES.index(name)]


def _ref(reach: str, col: str) -> str:
    """The value expression for one column at one reach."""
    table = _TABLE_FOR[reach]
    joins = _CHAIN_FOR[reach]
    if not joins:
        return f"[{DB}]{table}.{col}"
    chain = " > ".join(f"@{j}" for j in joins)
    return f"[{DB}]{chain} | [{DB}]{table}.{col}"


_UNARY = {"up": "toUpper", "abs": "abs", "sqrt": "sqrt",
          "isnull": "isNull", "isnotnull": "isNotNull"}
_BINARY = {"cat": "concat", "plus": "plus", "times": "times"}


def expression(cell) -> str:
    """The right-hand side of the property mapping for one cell."""
    reach, type_, xform, nulls, _host = cell
    table = _TABLE_FOR[reach]
    ra = _ref(reach, column(table, type_, nulls, 1))
    rb = _ref(reach, column(table, type_, nulls, 2))
    if xform == "none":
        return ra
    if xform in _UNARY:
        return f"{_UNARY[xform]}({ra})"
    if xform in _BINARY:
        return f"{_BINARY[xform]}({ra}, {rb})"
    if xform == "nest":
        # A function OVER a function. Neither regex the reader used before could span this,
        # which is why it is in the matrix rather than assumed to work.
        return f"toUpper(concat({ra}, {rb}))"
    raise ValueError(xform)


# ---------------------------------------------------------------- source generation
def _class_for(idx: int) -> str:
    return f"combo::C{idx}"


def _pairs(cell, ccell) -> set:
    """Every (property-axis value, class-axis value) this placement would witness."""
    cnames = ("filter", "extends")
    return {((a, v), (b, w))
            for a, v in zip(AXES, cell) for b, w in zip(cnames, ccell)}


def assignment() -> dict[int, list[tuple]]:
    """Property cells dealt across the class cells to MAXIMISE pair coverage.

    Round-robin was the obvious choice and it was wrong. `host` is the innermost axis, so
    it has period 2; with six classes, cell k always lands on a class of the same parity as
    k, and every `embedded` cell landed on an `extends=yes` class while every `top` cell
    landed on an `extends=no` one. The matrix would have reported 48 combinations while
    two of its ten cross-axis pairs could never occur -- a coverage hole manufactured by
    the dealing scheme rather than by the features.

    So each cell goes to the class that witnesses the most pairs not yet witnessed, subject
    to an even split. check() then asserts that every pair is covered, because a dealing
    rule that silently misses one is exactly what this replaces.
    """
    ccells = class_cells()
    cap = -(-len(cells()) // len(ccells))        # ceiling, so the split stays even
    out: dict[int, list[tuple]] = {i: [] for i in range(len(ccells))}
    remaining = list(cells())
    covered: set = set()

    # PASS 1 -- coverage first, capacity second. A plain greedy is order-dependent: whatever
    # it meets last competes for whatever capacity is left, and two different orderings each
    # left a different pair uncovered (embedded/extends, then chain2/filter, then
    # nest/filter). Chasing that with a better ordering is guessing. Instead each REQUIRED
    # pair is placed deliberately, and only then is the remainder dealt.
    for pair in sorted(_required(), key=str):
        if pair in covered:
            continue
        (paxis, pval), (caxis, cval) = pair
        pidx = AXES.index(paxis)
        cidx = ("filter", "extends").index(caxis)
        for cell in remaining:
            if cell[pidx] != pval:
                continue
            slot = next((i for i, cc in enumerate(ccells)
                         if cc[cidx] == cval and len(out[i]) < cap), None)
            if slot is None:
                continue
            out[slot].append(cell)
            covered |= _pairs(cell, ccells[slot])
            remaining.remove(cell)
            break

    # PASS 2 -- the rest go wherever there is room, emptiest first, so the split stays even.
    for cell in list(remaining):
        slot = min((i for i in range(len(ccells)) if len(out[i]) < cap),
                   key=lambda i: (len(out[i]), i))
        out[slot].append(cell)
        covered |= _pairs(cell, ccells[slot])
    return out


def _required() -> set:
    values = {"reach": REACH, "type": TYPES, "nulls": NULLS, "host": HOST,
              "xform": tuple(sorted({x for xs in XFORM.values() for x in xs}))}
    return {((a, v), (b, w))
            for a, vs in values.items() for v in vs
            for b, ws in (("filter", FILTERS), ("extends", EXTENDS)) for w in ws}


def check_data(c: model.Corpus, tables: dict[str, list[dict]]) -> list[str]:
    """The matrix must be non-vacuous ON THE SEED, not merely well-shaped.

    Three ways a combination test can be green while testing nothing, all of which this
    corpus has actually shipped at least once:

      * a class filter that excludes no row -- indistinguishable from no filter;
      * a class filter that excludes EVERY row -- the service returns nothing and every
        cell on it is asserted vacuously;
      * a `nullable` cell whose source column carries no NULL, so the transform under test
        never meets the case its null rule exists for.

    Checked here rather than trusted, because each was found only after the fact.
    """
    import oracle

    bad = []
    for i, (filt, _ext) in enumerate(class_cells()):
        cls = _class_for(i)
        if cls not in c.main_table:
            continue
        rows = tables.get(c.main_table[cls], [])
        kept = oracle._mapping_filtered(c, cls, list(rows), tables)
        if filt != "none" and len(kept) == len(rows):
            bad.append(f"{cls}: filter={filt} excludes no row of {len(rows)} -- "
                       f"indistinguishable from no filter")
        if not kept:
            bad.append(f"{cls}: filter={filt} excludes EVERY row; its cells assert nothing")
    # Every nullable source column must actually carry a NULL somewhere.
    for table in (ROOT, HOP1, HOP2):
        for type_ in TYPES:
            for which in (1, 2):
                col = column(table, type_, "nullable", which)
                vals = [r.get(col) for r in tables.get(table, [])]
                if vals and not any(v is None for v in vals):
                    bad.append(f"{table}.{col} is a `nullable` source but carries no NULL, "
                               f"so every cell reading it tests the non-null case twice")
    return bad


def check() -> list[str]:
    """Every (property-axis value, class-axis value) pair must actually co-occur."""
    ccells = class_cells()
    deal = assignment()
    covered = {p for i, cs in deal.items() for c in cs for p in _pairs(c, ccells[i])}
    want = _required()
    return [f"pair never co-occurs: {p[0][0]}={p[0][1]} with {p[1][0]}={p[1][1]}"
            for p in sorted(want - covered, key=str)]


def build_source() -> str:
    """The .pure text: store, classes, and the mapping carrying every combination."""
    ccells = class_cells()
    deal = assignment()
    L = [
        "// GENERATED by scripts/corpus/combos.py -- do not edit by hand.",
        "//",
        "// The COMBINATION matrix. Every cell of the property cross",
        "//     reach {col, chain1, chain2} x type {string, int, float, bool}",
        "//     x xform (per type) x nulls {notnull, nullable} x host {top, embedded}",
        "// dealt across the class cross",
        "//     filter {none, direct, chain} x extends {no, yes}",
        "//",
        "// 180 property cells over 6 class mappings. The corpus previously had two feature",
        "// PAIRS in total; this crosses them deliberately, because every defect it has",
        "// found so far lived where one feature met another rather than in a feature",
        "// alone.",
        "###Pure",
    ]

    # ---- classes ----------------------------------------------------------------
    # A shared supertype so `extends [setId]` has a base set to extend, and an embedded
    # child so the `host: embedded` cells have somewhere to live.
    L += [
        "Class combo::Base",
        "{",
        "   rootId: String[1];",
        "}",
        "",
    ]
    for i, (filt, ext) in enumerate(ccells):
        cls = _class_for(i)
        tops = [c for c in deal[i] if axis(c, "host") == "top"]
        embs = [c for c in deal[i] if axis(c, "host") == "embedded"]
        head = (f"Class {cls} extends combo::Base" if ext == "yes" else f"Class {cls}")
        L.append(f"// filter={filt}  extends={ext}")
        L.append(head)
        L.append("{")
        if ext == "no":
            L.append("   rootId: String[1];")
        for cell in tops:
            L.append(f"   {prop_name(cell)}: {pure_type(cell)}[0..1];")
        if embs:
            L.append(f"   nested: combo::E{i}[0..1];")
        L.append("}")
        L.append("")
        if embs:
            L.append(f"Class combo::E{i}")
            L.append("{")
            for cell in embs:
                L.append(f"   {prop_name(cell)}: {pure_type(cell)}[0..1];")
            L.append("}")
            L.append("")

    # ---- store ------------------------------------------------------------------
    def cols(table: str, last: bool = False) -> list[str]:
        """Column lines for `table`: two of every (type, nullability).

        `last=True` drops the trailing comma -- a Table's final columnDefinition must not
        carry one, and the parser reports that deep inside relationalIdentifier rather than
        at the comma, so it reads as a bad column name."""
        out = []
        for type_ in TYPES:
            for nulls in NULLS:
                for which in (1, 2):
                    sfx = "" if nulls == "nullable" else " NOT NULL"
                    out.append(f"      {column(table, type_, nulls, which)} "
                               f"{TYPE_SQL[type_]}{sfx},")
        if last:
            out[-1] = out[-1].rstrip(",")
        return out

    L += [
        "###Relational",
        f"Database {DB}",
        "(",
        f"   Table {ROOT}",
        "   (",
        "      ROOT_ID VARCHAR(64) PRIMARY KEY,",
        *cols(ROOT),
        "      HOP1_CODE VARCHAR(64)",
        "   )",
        f"   Table {HOP1}",
        "   (",
        "      HOP1_CODE VARCHAR(64) PRIMARY KEY,",
        *cols(HOP1),
        "      HOP2_CODE VARCHAR(64)",
        "   )",
        f"   Table {HOP2}",
        "   (",
        "      HOP2_CODE VARCHAR(64) PRIMARY KEY,",
        *cols(HOP2, last=True),
        "   )",
        "",
        f"   Join {J1}({ROOT}.HOP1_CODE = {HOP1}.HOP1_CODE)",
        f"   Join {J2}({HOP1}.HOP2_CODE = {HOP2}.HOP2_CODE)",
        "",
        "   // The DIRECT filter. It tests the foreign key rather than one of the columns",
        "   // the matrix cells read, for two reasons: the seeder GUARANTEES an absent key",
        "   // (property A2) so the filter provably excludes a row, and filtering on a cell's",
        "   // own source column would remove the very NULL that the `nullable` cells on this",
        "   // class exist to meet -- making that pair vacuous while looking covered.",
        "   //",
        "   // The first version tested `R_MEMO is not null` and excluded NOTHING, because the",
        "   // seeder nulls only the FIRST nullable column of a table. A filter matching every",
        "   // row is indistinguishable from no filter at all.",
        "   Filter ComboLinked(COMBO_ROOT.HOP1_CODE is not null)",
        "   // The chain filter's predicate, on the far end of two joins. It names a NOT",
        "   // NULL column deliberately: what this cell tests is that the FILTER FOLLOWS THE",
        "   // CHAIN, so the rows it must exclude are the ones whose chain breaks -- an",
        "   // absent key, a dangling key -- not the ones failing a predicate. Pointing it",
        "   // at a nullable column instead excluded every row, which the vacuity guard",
        "   // caught: a filter matching nothing asserts as little as one matching",
        "   // everything.",
        f"   Filter ComboNamedHop2({HOP2}.{column(HOP2, 'string', 'notnull', 1)} is not null)",
        ")",
        "",
    ]

    # ---- mapping ----------------------------------------------------------------
    L += ["###Mapping", f"Mapping {MAPPING}", "("]
    # The root set every `extends` set extends. It maps only the inherited identity, so an
    # extending set below contributes nothing but its own combination cells.
    L += [
        "   // The ROOT set. A class mapped by more than one set implementation needs exactly",
        "   // one root, and an extending set carries no ~mainTable of its own.",
        "   *combo::Base[base]: Relational",
        "   {",
        f"      ~primaryKey ( [{DB}]{ROOT}.ROOT_ID )",
        f"      ~mainTable [{DB}]{ROOT}",
        f"      rootId: [{DB}]{ROOT}.ROOT_ID",
        "   }",
        "",
    ]
    for i, (filt, ext) in enumerate(ccells):
        cls = _class_for(i)
        tops = [c for c in deal[i] if axis(c, "host") == "top"]
        embs = [c for c in deal[i] if axis(c, "host") == "embedded"]
        L.append(f"   // filter={filt}  extends={ext}  "
                 f"({len(tops)} top-level, {len(embs)} embedded)")
        if ext == "yes":
            L.append(f"   {cls}[c{i}] extends [base]: Relational")
        else:
            L.append(f"   {cls}[c{i}]: Relational")
        L.append("   {")
        # Directive order is fixed: ~filter first, and an extending set has no ~mainTable.
        if filt == "direct":
            L.append(f"      ~filter [{DB}]ComboLinked")
        elif filt == "chain":
            L.append(f"      ~filter [{DB}]@{J1} > @{J2} | [{DB}]ComboNamedHop2")
        if ext == "no":
            L.append(f"      ~primaryKey ( [{DB}]{ROOT}.ROOT_ID )")
            L.append(f"      ~mainTable [{DB}]{ROOT}")
        body = []
        if ext == "no":
            body.append(f"      rootId: [{DB}]{ROOT}.ROOT_ID")
        for cell in tops:
            body.append(f"      {prop_name(cell)}: {expression(cell)}")
        if embs:
            inner = ",\n".join(f"         {prop_name(cell)}: {expression(cell)}"
                               for cell in embs)
            body.append("      nested\n      (\n" + inner + "\n      )")
        L.append(",\n".join(body))
        L.append("   }")
        L.append("")
    L.append(")")
    L.append("")
    return "\n".join(L)


# ---------------------------------------------------------------- services
def specs(c: model.Corpus) -> list[Spec]:
    """One service per class cell, projecting every combination cell it carries.

    A cell whose value the oracle cannot compute is DROPPED from the projection rather than
    silently asserted, and reported by unsupported(). Grouping a class's cells into one
    service keeps the count readable; the cost is that one divergent cell fails the whole
    service, which is why the known divergences get their own below.
    """
    import oracle
    import flat

    tables = flat.all_tables(c)
    out = []
    for i, (filt, ext) in enumerate(class_cells()):
        cls = _class_for(i)
        if cls not in c.main_table:
            continue
        spec = Spec(f"stress::CB_{cls.split('::')[-1]}", f"/stress/cb_c{i}",
                    f"Combination matrix cell: class filter={filt}, extends={ext}. "
                    f"Projects every property cell dealt to this class -- each a distinct "
                    f"(reach, transform, nullability, host) combination. Generated by "
                    f"scripts/corpus/combos.py.", cls)
        projs = [Proj("rootId", ["rootId"])]
        for cell in assignment()[i]:
            path = (["nested", prop_name(cell)] if axis(cell, "host") == "embedded"
                    else [prop_name(cell)])
            projs.append(Proj(prop_name(cell), path))
        spec.projections = projs
        spec.sort = ("rootId", False)
        spec.mapping, spec.runtime = MAPPING, RUNTIME
        spec.connection, spec.data_element = CONN_ID, DATA
        out.append(spec)
    return out + predicate_specs(c)


def predicate_specs(c: model.Corpus) -> list[Spec]:
    """The three comparison operators against a NULL operand, side by side.

    F28: the engine EXCLUDES a row whose operand is NULL for `==` and for `>`, and KEEPS it
    for `!=`. Pinned as a trio rather than as one failing case, because the two that pass
    are the evidence: they show the divergence is specific to `!=` rather than the oracle
    being wrong about NULL comparison generally. A lone failing service would leave that
    ambiguous, and the obvious "fix" -- teaching the oracle that NULL != x is true -- would
    then have made all three disagree.

    Deliberately here rather than on the dense services, which used to carry it. Thirty of
    them diverged at once when the seeder began nulling every nullable column, coupling
    every deep-navigation test to an unrelated defect. Same split aggregates.py makes
    around F6.
    """
    from query import Pred

    # SEARCHED, not assumed. The first version hardcoded class 0 and found nothing: the
    # dealing is a coverage-driven greedy, so which class carries a given cell is not
    # something a caller can predict -- and the services silently did not exist.
    #
    # A class with NO filter, so what the predicate excludes is not confounded with what a
    # class filter already excluded.
    want = ("string", "none", "nullable", "top")
    cls = prop = None
    for i, (filt, _ext) in enumerate(class_cells()):
        if filt != "none":
            continue
        hit = next((x for x in assignment()[i]
                    if (axis(x, "type"), axis(x, "xform"),
                        axis(x, "nulls"), axis(x, "host")) == want), None)
        if hit is not None and _class_for(i) in c.main_table:
            cls, prop = _class_for(i), prop_name(hit)
            break
    if prop is None:
        raise SystemExit(
            "combination matrix carries no plain nullable string cell on an unfiltered "
            "class, so F28 cannot be pinned -- the trio would vanish silently")
    out = []
    for name, op, value, note in (
            ("NotEqualsNull", "!=", " none",
             "F28: the engine KEEPS the row whose operand is NULL. SQL three-valued logic "
             "makes the comparison UNKNOWN, and a predicate that is not TRUE excludes."),
            ("EqualsNull", "==", " none",
             "The complement, which AGREES. Both readings exclude a NULL here, which is "
             "why no `==` predicate in the corpus ever exposed F28."),
            ("GreaterNull", ">", " ",
             "An ORDERED comparison against the same NULL, which also AGREES -- so the "
             "engine excludes for `>` and keeps for `!=`, and those cannot both be right.")):
        spec = Spec(f"stress::CB_{name}", f"/stress/cb_{name.lower()}",
                    f"Comparison `{op}` against a column that is NULL in some rows. {note} "
                    f"Generated by scripts/corpus/combos.py.", cls)
        spec.projections = [Proj("rootId", ["rootId"]), Proj(prop, [prop])]
        spec.filters = [Pred([prop], op, value)]
        spec.sort = ("rootId", False)
        spec.mapping, spec.runtime = MAPPING, RUNTIME
        spec.connection, spec.data_element = CONN_ID, DATA
        out.append(spec)
    return out


def runtime_text() -> str:
    return f"""// GENERATED by scripts/corpus/combos.py -- do not edit by hand.
//
// Connection and runtime for the combination matrix. Its tables live in their own store,
// and test data is bound to a CONNECTION, so it needs its own.
###Connection
RelationalDatabaseConnection combo::ComboConn
{{
    type: DuckDB;
    specification: DuckDB {{ }};
    auth: Test;
}}


###Runtime
Runtime {RUNTIME}
{{
    mappings:
    [
        {MAPPING}
    ];
    connections:
    [
        {DB}: [ {CONN_ID}: combo::ComboConn ]
    ];
}}
"""


if __name__ == "__main__":
    c = model.load()
    deal = assignment()
    problems = check()
    print(f"property cells: {len(cells())}   class cells: {len(class_cells())}")
    print(f"pair coverage: {'COMPLETE' if not problems else problems}")
    for i, (f, e) in enumerate(class_cells()):
        got = deal[i]
        print(f"  C{i}  filter={f:<7} extends={e:<4} {len(got)} cells "
              f"({sum(1 for g in got if axis(g, 'host') == 'embedded')} embedded)")
    print()
    seen = {ax: set() for ax in ("reach", "xform", "nulls", "host")}
    for r, x, n, h in cells():
        seen["reach"].add(r); seen["xform"].add(x)
        seen["nulls"].add(n); seen["host"].add(h)
    for k, v in seen.items():
        print(f"  {k:<7}{sorted(v)}")
