"""
Emit a reference-data taxonomy: seed rows, store DDL, classes, mapping, and registration.

Every taxonomy in this corpus needs the same five edits in four files, and doing them by hand
is how CORPORATE_ACTION got declared twice and PRODUCT_TYPE ended up two characters too
narrow. The CONTENT -- which types exist, what distinguishes each, which columns they need --
is hand-authored per taxonomy and is the part that matters. The five edits are not.

What this does NOT decide: the type list, the field list, the derived property, or which rows
are seeded. Those are arguments, written out per taxonomy by someone who knows what a
scheme of arrangement is. This only puts them in the five places the corpus needs them.
"""
from __future__ import annotations

import pathlib
import re

STRESS = pathlib.Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"
SCRIPTS = pathlib.Path(__file__).resolve().parent

SQL = {"String": "VARCHAR(60)", "StrictDate": "DATE", "DateTime": "TIMESTAMP",
       "Float": "DECIMAL(18,4)", "Integer": "INTEGER", "Boolean": "BIT"}


def camel(s: str) -> str:
    return "".join(w.capitalize() for w in s.split("_"))


def emit(*, table: str, pkg: str, base: str, discriminator: str, tag: str,
         types: list[tuple[str, str, str]], fields: list[tuple[str, str, str, int]],
         derived: str, seed_rows: str, doc: str, file_index: int) -> None:
    """One taxonomy, in the five places the corpus needs it.

    `types` is (CODE, ClassName, one-line doc); `fields` is (property, COLUMN, type,
    required). The first field is the primary key. `seed_rows` is the literal Python source
    of the row list, so the seed stays readable as data rather than as a generator.
    """
    ident = fields[0][0]

    # 0. A table name already in use is an ERROR, and is checked BEFORE anything is written.
    # This lived between the seed edit and the store edit, so a colliding taxonomy wrote its
    # SEED and then raised -- leaving rows under a table name it does not own, with the wrong
    # columns, which surfaced two steps later as `KeyError: 'FAIL_ID'` from a primary-key
    # check on an unrelated table. Refusing before the first write is the only version of
    # this guard that leaves nothing behind.
    if f"Table {table} (" in (STRESS / "30-store.pure").read_text() or any(
            f"Table {table} (" in o.read_text() or f"Table {table}\n" in o.read_text()
            for o in STRESS.glob("*.pure")):
        if f"// emitted by refdata for {pkg}" not in (STRESS / "30-store.pure").read_text():
            raise SystemExit(
                f"table {table} is already declared in the corpus. refdata will not "
                f"reuse a name: pick another, or the reader keeps one declaration and "
                f"silently drops the other.")

    # 1. the seed
    p = SCRIPTS / "seed.py"
    t = p.read_text()
    if f'"{table}": {table}' not in t:
        t = t.replace("\nTABLES: dict[str, list[dict]] = {",
                      f"\n\n# {doc}\n{table} = [\n{seed_rows}\n]\n"
                      "\n\nTABLES: dict[str, list[dict]] = {", 1)
        t = t.replace("TABLES: dict[str, list[dict]] = {",
                      f'TABLES: dict[str, list[dict]] = {{\n    "{table}": {table},', 1)
        p.write_text(t)

    # 2. the store: one table and one filter per type.
    #
    # `p` and `t` are REBOUND here. Moving the collision guard out of this block took these
    # two lines with it, and the store edit then ran against seed.py's text -- finding
    # neither anchor, changing nothing, and writing seed.py back unchanged. Ten taxonomies
    # emitted their classes and mappings against tables that were never declared.
    p = STRESS / "30-store.pure"
    t = p.read_text()
    if f"Table {table} (" not in t:
        cols = ", ".join(f"{c} {SQL[ty]}" + (" PRIMARY KEY" if i == 0 else "")
                         for i, (_p, c, ty, _m) in enumerate(fields))
        filters = "\n".join(
            f"    Filter {tag}{camel(code)}Rows({table}."
            f"{[c for p_, c, _t, _m in fields if p_ == discriminator][0]} = '{code}')"
            for code, _n, _d in types)
        anchor = "    // ---- Back office: where cash actually moves ----"
        t = t.replace(anchor, f"    // emitted by refdata for {pkg}\n"
                                 f"    Table {table} ({cols})\n\n{anchor}", 1)
        t = t.replace("    Join Counterparty_Ssi(", filters + "\n\n    Join Counterparty_Ssi(", 1)
        p.write_text(t)

    # 3. the classes and the mapping
    props = "\n".join(f"   {p_}: {ty}[{'1' if m else '0..1'}];" for p_, _c, ty, m in fields)
    subclasses = "\n".join(f"""
// {d}
Class {pkg}::{name} extends {pkg}::{base}
{{
}}""" for _code, name, d in types)
    maps = "\n\n".join(f"""   {pkg}::{name}[{tag.lower()}{camel(code)}] extends [{pkg}Base]: Relational
   {{
      ~filter [store::DB]{tag}{camel(code)}Rows
   }}""" for code, name, _d in types)
    colmaps = ",\n".join(f"      {p_}: [store::DB]{table}.{c}" for p_, c, _t, _m in fields)
    (STRESS / f"8{file_index}-{pkg}.pure").write_text(f'''###Pure
// {doc}
//
// {len(types)} types over one table. A reference-data taxonomy is where discriminated
// subtypes earn their keep: the types differ in what they MEAN rather than in what columns
// they carry, so one table with a discriminator is the honest shape and {len(types)} tables
// would be ceremony.
//
// Not every type carries rows. The rest are types the firm can process and does not have
// today, which is the normal state of reference data and the sharpest test of a ~filter --
// an empty result is only correct if the filter works, and the failure mode is returning the
// whole table.
Class {pkg}::{base}
{{
{props}

{derived}
}}
{subclasses}

###Mapping
Mapping {pkg}::{camel(pkg)}Mapping
(
   *{pkg}::{base}[{pkg}Base]: Relational
   {{
      ~primaryKey ( [store::DB]{table}.{fields[0][1]} )
      ~mainTable [store::DB]{table}
{colmaps}
   }}

{maps}
)
''')

    # 4. the default mapping includes it
    p = STRESS / "89-all-mapping.pure"
    t = p.read_text()
    if f"include {pkg}::" not in t:
        t = t.replace("    include backoffice::BackOfficeMapping",
                      f"    include backoffice::BackOfficeMapping\n"
                      f"    include {pkg}::{camel(pkg)}Mapping", 1)
        p.write_text(t)

    # 5. the taxonomy generator emits a service per subtype
    p = SCRIPTS / "taxonomy.py"
    t = p.read_text()
    if f'"{pkg}::{base}"' not in t:
        t = t.replace('    ("orders::OrderTicket", "orderType", "TXO"),',
                      f'    ("orders::OrderTicket", "orderType", "TXO"),\n'
                      f'    ("{pkg}::{base}", "{discriminator}", "{tag.upper()}X"),', 1)
        t = t.replace('    "orders::OrderTicket": "ticketId",',
                      f'    "orders::OrderTicket": "ticketId",\n'
                      f'    "{pkg}::{base}": "{ident}",', 1)
        p.write_text(t)
