"""Give every taxonomy a to-one edge, so the corpus can navigate out of one.

The fan-out to ~2200 classes added BREADTH WITH NO EDGES. 1811 of 2009 seeded roots have no
association and no join property at all: ~100 taxonomies, each a base class and ~17 subtypes
told apart by a `~filter`, every one a table of local columns joined to nothing. That is why
88% of the corpus's services navigate a single hop -- not a defect, an edge nobody drew.

Every one of those tables already carries BOOK_ID. It simply pointed nowhere: the taxonomy
seeds used BK-EQ, BK-FX, BK-RATES, BK-CREDIT and BK-COMMOD while BOOK held four completely
different ids, so the two sets were disjoint. seed.py now supplies four of those five, and
deliberately not BK-COMMOD, so one taxonomy's rows navigate to a book that does not exist.

The edge is declared on the BASE class and mapped on the BASE set. Subtype sets extend that
set and inherit the property mapping, and a subtype CAN navigate an inherited property over a
join -- proved directly, because F49 (an end inherited from a supertype is unnavigable from
the subtype's set) is about ASSOCIATIONS with mapped ends and does not extend to this form.
That distinction is what makes one edge per taxonomy worth ~1800 newly navigable roots
instead of ~100.

The target set id is named explicitly. F57: a class-typed property mapped over a join with no
target set id compiles and then fails at execution with `Void not supported!`, naming nothing.
`positions::Book` had no set id of its own, so this gives it one.

Idempotent: a taxonomy that already has the edge is skipped.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

STRESS = Path(__file__).resolve().parents[2] / "core/src/test/resources/stress"
STORE = STRESS / "30-store.pure"
# The DEFAULT set id of a class mapping that declares none: the class path with `::`
# replaced by `_`. Referencing it leaves positions::Book's mapping untouched, which
# matters -- giving that class an explicit id DESTROYS the default, and the two
# associations still relying on it (cross::Trade_Book, cross::CashFlow_Book) stop
# resolving with "Expected 1 class mapping ... found 0". Naming a set is not a local
# change; it is a change to every end that referenced it by default.
BOOK_SET = "positions_Book"


def give_book_a_set_id() -> bool:
    """Nothing to do: the taxonomy edges reference the DEFAULT id, so the class mapping is
    left exactly as it was. Kept as a function so the caller reads the same either way."""
    return False


def taxonomies() -> list[tuple[Path, str, str, str]]:
    """(file, base class fqn, base set id, main table) for each taxonomy file."""
    out = []
    for f in sorted(STRESS.glob("8*.pure")):
        t = f.read_text()
        m = re.search(r"^\s*\*([\w:]+)\[(\w+)\]:\s*Relational\s*$", t, re.M)
        if not m:
            continue
        blk = t[m.end():]
        tbl = re.search(r"~mainTable\s*\[store::DB\]\s*(\w+)", blk)
        if not tbl:
            continue
        out.append((f, m.group(1), m.group(2), tbl.group(1)))
    return out


def _columns() -> dict:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import model
    c = model.load()
    return {n: set(t.columns) for n, t in c.tables.items()}


COLUMNS: dict = {}


def apply(dry: bool) -> None:
    global COLUMNS
    COLUMNS = _columns()
    store = STORE.read_text()
    added = skipped = 0
    joins = []
    for f, base, setid, tbl in taxonomies():
        t = f.read_text()
        if "book[posBook]" in t:
            skipped += 1
            continue
        # The table must actually HAVE the column. Asked of the parsed model rather than of
        # the store text: these tables are declared on one long line and a `[^)]*` column
        # list stops at the first `)`, which belongs to VARCHAR(200).
        if "BOOK_ID" not in COLUMNS.get(tbl, ()):
            skipped += 1
            continue
        join = f"{setid[0].upper()}{setid[1:]}Book"
        # 1. the property on the BASE class
        cls_at = t.index(f"Class {base}\n{{\n")
        ins = cls_at + len(f"Class {base}\n{{\n")
        t = (t[:ins]
             + "   // The book this record belongs to. Declared on the BASE class and mapped\n"
               "   // on the BASE set, so every subtype inherits both and can navigate it.\n"
               f"   book: positions::Book[0..1];\n"
             + t[ins:])
        # 2. the property mapping on the BASE set, before that block's closing brace
        m = re.search(rf"^\s*\*{re.escape(base)}\[{setid}\]:\s*Relational\s*$", t, re.M)
        depth, i, start = 0, t.index("{", m.end()), None
        for i in range(t.index("{", m.end()), len(t)):
            if t[i] == "{":
                depth += 1
                if start is None:
                    start = i
            elif t[i] == "}":
                depth -= 1
                if depth == 0:
                    break
        body_end = i
        before = t[:body_end].rstrip()
        t = (before + f",\n      book[{BOOK_SET}]: [store::DB]@{join}\n   "
             + t[body_end:])
        # 3. the join in the one store
        joins.append(f"    Join {join}({tbl}.BOOK_ID = BOOK.BOOK_ID)")
        if not dry:
            f.write_text(t)
        added += 1
        if dry and added == 1:
            print(f"--- {f.name}: base {base} set {setid} table {tbl} join {join}")
            k = t.index(f"Class {base}")
            print(t[k:k + 260])
            print("   ...")
            print(t[body_end - 200:body_end + 60])

    if joins and not dry:
        anchor = "\n    // ---- from 31-products-store.pure ----"
        block = ("\n    // ---- taxonomy edges, added by scripts/corpus/add_taxonomy_edges.py ----\n"
                 "    // Every taxonomy table carries BOOK_ID and joined to nothing; these are\n"
                 "    // the edges that make 1811 otherwise-flat roots navigable.\n"
                 + "\n".join(joins) + "\n")
        store = store.replace(anchor, block + anchor, 1)
        STORE.write_text(store)
    print(f"{'would add' if dry else 'added'} {added} taxonomy edges, skipped {skipped}")


if __name__ == "__main__":
    dry = "--apply" not in sys.argv
    if not dry:
        print("book set id added" if give_book_a_set_id() else "book set id already present")
    apply(dry)
