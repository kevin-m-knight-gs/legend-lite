"""
The SURFACE scoreboard: the corpus scored against what legend-engine can actually express.

Every coverage number before this one was scored against a taxonomy I wrote. That measures
how well the corpus covers what I thought of, which is not the question -- the constructs
most likely to be untested are exactly the ones nobody remembered to list. density.py's
taxonomy had 35 entries. The engine's declarative surface, enumerated from its ANTLR
grammars and parse-tree walkers, has 263, and its executable function registry has 440.

docs/ENGINE_SURFACE.tsv is that inventory. Each row carries a detection pattern, so
"present" is decided by matching the corpus against a construct derived from the GRAMMAR
rather than from memory. Where a construct cannot be detected by text the row carries no
pattern and is reported UNKNOWN, never assumed absent and never assumed present.

Three columns, and the gap between them is the point:

    PRESENT     the corpus writes it somewhere
    EXECUTED    a service that PASSES depends on it        (executed.py)
    STACKED     it co-occurs with another construct        (stacking.py)

A construct can be present and unexecuted -- 60 scope-using class mappings sat in a Mapping
no runtime bound. It can be executed and unstacked -- half this corpus's services exercise
exactly one thing. Only the third column measures what the corpus was built to find.

Deliberately NOT scored as a percentage of 263. Some rows are alternative spellings of one
idea (nine authentication strategies, twenty-one column types), and averaging them produces
a number that improves by adding the cheapest possible cases. The report is per DSL, with
the absent rows named, because the list is the actionable artefact and the ratio is not.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

SURFACE = Path(__file__).resolve().parents[2] / "docs/ENGINE_SURFACE.tsv"


def load_surface() -> list[dict]:
    rows = []
    for line in SURFACE.read_text().splitlines()[1:]:
        parts = line.split("\t")
        if len(parts) < 5:
            parts += [""] * (5 - len(parts))
        rows.append(dict(zip(("id", "dsl", "name", "syntax", "detect"), parts)))
    return rows


def corpus_text() -> str:
    """Every .pure source, comments STRIPPED.

    Comments must not count. This corpus is heavily commented and several constructs are
    named in prose explaining why they are absent -- scoring those as present would make the
    documentation of a gap close the gap.
    """
    import model

    src = "\n".join(p.read_text() for p in sorted(model.STRESS.glob("*.pure")))
    return re.sub(r"//[^\n]*", " ", src)


def score():
    import executed
    import model
    import quarantine
    import stacking

    rows = load_surface()
    text = corpus_text()
    c = model.load()
    q = set(quarantine.ENGINE_QUARANTINE) | set(quarantine.HANGS)
    prof = stacking.profile(c, executed.all_specs(c), q)
    # Constructs that appear in a passing service, and those that appear in one ALONGSIDE
    # another -- stacking.py keys by its own vocabulary, so this maps by name fragment.
    executed_names = {f for feats in prof.values() for f in feats}
    stacked_names = {f for feats in prof.values() if len(feats) > 1 for f in feats}

    def status(row):
        if not row["detect"]:
            return "UNKNOWN"
        if not re.search(row["detect"], text, re.M):
            return "absent"
        key = row["name"].lower()
        hit = next((f for f in executed_names if f.split("  ")[-1].lower() in key
                    or key in f.split("  ")[-1].lower()), None)
        if hit is None:
            return "present"
        return "stacked" if hit in stacked_names else "executed"

    return [(r, status(r)) for r in rows]


def main() -> None:
    scored = score()
    by_dsl: dict[str, list] = {}
    for row, st in scored:
        by_dsl.setdefault(row["dsl"], []).append((row, st))

    print(f"ENGINE SURFACE: {len(scored)} constructs enumerated from the grammars\n")
    print(f"  {'dsl':<9} {'present':>8} {'absent':>8} {'unknown':>8}   of")
    for dsl, items in sorted(by_dsl.items()):
        pres = sum(1 for _r, s in items if s in ("present", "executed", "stacked"))
        absent = sum(1 for _r, s in items if s == "absent")
        unk = sum(1 for _r, s in items if s == "UNKNOWN")
        print(f"  {dsl:<9} {pres:>8} {absent:>8} {unk:>8}   {len(items)}")

    total_pres = sum(1 for _r, s in scored if s in ("present", "executed", "stacked"))
    total_abs = sum(1 for _r, s in scored if s == "absent")
    print(f"\n  {'TOTAL':<9} {total_pres:>8} {total_abs:>8} "
          f"{sum(1 for _r, s in scored if s == 'UNKNOWN'):>8}   {len(scored)}")

    if "--absent" in sys.argv:
        print("\nABSENT -- constructs the engine can express and this corpus never writes:")
        for dsl, items in sorted(by_dsl.items()):
            miss = [r for r, s in items if s == "absent"]
            if not miss:
                continue
            print(f"\n  [{dsl}] {len(miss)}")
            for r in miss:
                print(f"    {r['id']:<7} {r['name']:<38} {r['syntax'][:44]}")


if __name__ == "__main__":
    main()
