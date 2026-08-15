"""
Generates the COMBINATION matrix: mapping features stacked on each other, not merely present.

The taxonomy reached "35 of 35 features present" while four classes carried every advanced
construct in the corpus and exactly two pairs ever co-occurred (chain+dyna on one class,
binding+embedded on another). Presence is not coverage. A feature that appears once has been
shown to work in one shape, and the defects this corpus has actually found -- concat over
NULL, count over an empty to-many, a chain filter that excluded nothing -- all live at the
point where one feature meets another.

So the axes are enumerated and crossed rather than illustrated:

  PROPERTY axes                      CLASS-MAPPING axes
    reach    col | chain1 | chain2     filter    none | direct | chain
    xform    none | up | cat | nest    extends   no | yes
    nulls    notnull | nullable
    host     top | embedded

The property cross is 3x4x2x2 = 48 cells and every one is generated -- this is a full cross,
not a sample, because 48 is small enough to afford. The class cross is 3x2 = 6, and the
property cells are dealt round-robin across the six so that each property shape also meets
each class shape. What that buys over 48 separate one-feature tests is the interaction: a
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
XFORM = ("none", "up", "cat", "nest")
NULLS = ("notnull", "nullable")
HOST = ("top", "embedded")

FILTERS = ("none", "direct", "chain")
EXTENDS = ("no", "yes")

# Columns the axes draw on. Two of each kind per table so `cat` has a second argument that
# is not the same column -- concat(x, x) would hide an argument-order defect.
_COLS = {
    ROOT: {"notnull": ("R_NAME", "R_ALT"), "nullable": ("R_NOTE", "R_MEMO")},
    HOP1: {"notnull": ("H1_NAME", "H1_ALT"), "nullable": ("H1_NOTE", "H1_MEMO")},
    HOP2: {"notnull": ("H2_NAME", "H2_ALT"), "nullable": ("H2_NOTE", "H2_MEMO")},
}
_TABLE_FOR = {"col": ROOT, "chain1": HOP1, "chain2": HOP2}
_CHAIN_FOR = {"col": [], "chain1": [J1], "chain2": [J1, J2]}


def cells() -> list[tuple[str, str, str, str]]:
    """The full property cross, in a fixed order so names are stable across runs."""
    return [(r, x, n, h) for r in REACH for x in XFORM for n in NULLS for h in HOST]


def class_cells() -> list[tuple[str, str]]:
    return [(f, e) for f in FILTERS for e in EXTENDS]


def prop_name(cell) -> str:
    r, x, n, h = cell
    return f"{r}_{x}_{n}_{h}"


def _ref(reach: str, col: str) -> str:
    """The value expression for one column at one reach."""
    table = _TABLE_FOR[reach]
    joins = _CHAIN_FOR[reach]
    if not joins:
        return f"[{DB}]{table}.{col}"
    chain = " > ".join(f"@{j}" for j in joins)
    return f"[{DB}]{chain} | [{DB}]{table}.{col}"


def expression(cell) -> str:
    """The right-hand side of the property mapping for one cell."""
    reach, xform, nulls, _host = cell
    a, b = _COLS[_TABLE_FOR[reach]][nulls]
    ra, rb = _ref(reach, a), _ref(reach, b)
    if xform == "none":
        return ra
    if xform == "up":
        return f"toUpper({ra})"
    if xform == "cat":
        return f"concat({ra}, {rb})"
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
    names = ("reach", "xform", "nulls", "host")
    cnames = ("filter", "extends")
    return {((a, v), (b, w))
            for a, v in zip(names, cell) for b, w in zip(cnames, ccell)}


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
        pidx = ("reach", "xform", "nulls", "host").index(paxis)
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
    return {((a, v), (b, w))
            for a, vs in (("reach", REACH), ("xform", XFORM),
                          ("nulls", NULLS), ("host", HOST)) for v in vs
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
    for table, bykind in _COLS.items():
        for col in bykind["nullable"]:
            vals = [r.get(col) for r in tables.get(table, [])]
            if vals and not any(v is None for v in vals):
                bad.append(f"{table}.{col} is a `nullable` source but carries no NULL, so "
                           f"every cell reading it tests the non-null case twice")
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
        "//     reach {col, chain1, chain2} x xform {none, up, cat, nest}",
        "//     x nulls {notnull, nullable} x host {top, embedded}",
        "// dealt across the class cross",
        "//     filter {none, direct, chain} x extends {no, yes}",
        "//",
        "// 48 property cells over 6 class mappings. The corpus previously had two feature",
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
        tops = [c for c in deal[i] if c[3] == "top"]
        embs = [c for c in deal[i] if c[3] == "embedded"]
        head = (f"Class {cls} extends combo::Base" if ext == "yes" else f"Class {cls}")
        L.append(f"// filter={filt}  extends={ext}")
        L.append(head)
        L.append("{")
        if ext == "no":
            L.append("   rootId: String[1];")
        for cell in tops:
            L.append(f"   {prop_name(cell)}: String[0..1];")
        if embs:
            L.append(f"   nested: combo::E{i}[0..1];")
        L.append("}")
        L.append("")
        if embs:
            L.append(f"Class combo::E{i}")
            L.append("{")
            for cell in embs:
                L.append(f"   {prop_name(cell)}: String[0..1];")
            L.append("}")
            L.append("")

    # ---- store ------------------------------------------------------------------
    def cols(table: str, last: bool = False) -> list[str]:
        """Column lines for `table`. `last=True` drops the trailing comma -- a Table's final
        columnDefinition must not carry one, and the parser reports it deep inside
        relationalIdentifier rather than at the comma."""
        nn = _COLS[table]["notnull"]
        nl = _COLS[table]["nullable"]
        out = ([f"      {c} VARCHAR(200) NOT NULL," for c in nn]
               + [f"      {c} VARCHAR(200)," for c in nl])
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
        "   // The chain filter's predicate, on the far end of two joins.",
        "   Filter ComboNamedHop2(COMBO_HOP2.H2_NAME is not null)",
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
        tops = [c for c in deal[i] if c[3] == "top"]
        embs = [c for c in deal[i] if c[3] == "embedded"]
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
            path = (["nested", prop_name(cell)] if cell[3] == "embedded"
                    else [prop_name(cell)])
            projs.append(Proj(prop_name(cell), path))
        spec.projections = projs
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
              f"({sum(1 for g in got if g[3] == 'embedded')} embedded)")
    print()
    seen = {ax: set() for ax in ("reach", "xform", "nulls", "host")}
    for r, x, n, h in cells():
        seen["reach"].add(r); seen["xform"].add(x)
        seen["nulls"].add(n); seen["host"].add(h)
    for k, v in seen.items():
        print(f"  {k:<7}{sorted(v)}")
