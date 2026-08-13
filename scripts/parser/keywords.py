"""
Parser completeness — the keyword surface, measured against sources WE own.

There is already a keyword census in parser-equivalence, and it answers a different
question: does this keyword appear anywhere in legend-engine's own corpus. That is the
right denominator for "has the parity harness ever seen this grammar arm", and it is the
wrong one for "is legend-lite's parser complete", because a construct nobody upstream
happened to write is invisible to it. Ninety percent of THEIR fixtures says nothing about
the other ten percent of the grammar.

This measures the grammar directly:

  * harvest every literal keyword from all 156 .g4 files, grouped by grammar
  * check each against the .pure sources in this repository
  * report what is missing, BY GRAMMAR, so the gap is a work queue rather than a number

A keyword here is a lexer rule with a quoted literal — `DATABASE: 'Database'` — which is
exactly what a user can type. Parser rules, fragments and character classes are excluded:
they are structure, not surface.

Deliberately NOT measured here: whether the construct is handled correctly once parsed.
That is what the stress corpus is for. A keyword can be lexed, routed, and still mean
nothing — this only establishes that the surface is reachable.
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

import tiers

REPO = Path(__file__).resolve().parents[2]
ENGINE = Path.home() / "legend" / "legend-engine"

# `NAME: 'literal'` at the start of a line — a lexer token with a typeable spelling.
_TOKEN = re.compile(r"^([A-Z_][A-Z0-9_]*)\s*:\s*'([^']+)'", re.M)

# Keywords that are punctuation or operators rather than words a user "types" as a
# construct. Excluded from the target so the number means something: nobody writes a test
# to cover '{'.
_PUNCT = re.compile(r"^[^A-Za-z]+$")


def harvest() -> dict[str, set[str]]:
    """grammar file stem -> the literal keywords it defines."""
    out: dict[str, set[str]] = defaultdict(set)
    for g4 in sorted(ENGINE.rglob("*.g4")):
        text = g4.read_text(errors="replace")
        for _token, literal in _TOKEN.findall(text):
            if _PUNCT.match(literal):
                continue
            out[g4.stem].add(literal)
    return dict(out)


# Comments and string literals, stripped before counting. A keyword inside a comment or
# inside a quoted CSV payload is not a keyword the parser ever saw — and this corpus is
# full of both, since the generated ###Data blocks are megabytes of quoted rows and the
# hand-written files carry long explanatory comments that name constructs.
_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
_LINE_COMMENT = re.compile(r"//[^\n]*")
_STRING = re.compile(r"'(?:[^'\\]|\\.)*'")


def strip_noncode(text: str) -> str:
    text = _BLOCK_COMMENT.sub(" ", text)
    text = _LINE_COMMENT.sub(" ", text)
    return _STRING.sub(" '' ", text)


def our_sources() -> str:
    """Every .pure this repository owns, with comments and string literals removed.

    Coverage is measured against what WE wrote, not against what upstream happens to
    contain — and against CODE, not prose about code.
    """
    parts = []
    for root in ("core/src/test/resources", "scripts", "pct-corpus", "experiments"):
        base = REPO / root
        if not base.is_dir():
            continue
        for p in base.rglob("*.pure"):
            parts.append(strip_noncode(p.read_text(errors="replace")))
    return "\n".join(parts)


def covered(keywords: set[str], text: str) -> set[str]:
    """A keyword counts as covered when it appears as a WORD in our CODE.

    Word boundaries, because substring matching would be wildly generous — 'in' occurs
    inside a thousand identifiers. Comments and string literals are already stripped.

    What remains over-counted, stated plainly: a keyword is credited wherever it appears,
    even in a section whose grammar does not define it. `Schema` in a Service section
    would count toward the Relational grammar. Removing that needs per-section lexing with
    the grammar's own lexer, which is the exact fix; this is the honest approximation
    until fixtures replace the search entirely — a fixture that PARSES proves reachability
    in a way no text search can.
    """
    found = set()
    for k in keywords:
        if re.search(rf"(?<![A-Za-z0-9_]){re.escape(k)}(?![A-Za-z0-9_])", text):
            found.add(k)
    return found


def _keywords_in(grammars: dict[str, set[str]], stems) -> set[str]:
    return set().union(*[grammars[s] for s in stems if s in grammars]) if stems else set()


def main() -> None:
    grammars = harvest()
    text = our_sources()
    all_kw = {k for ks in grammars.values() for k in ks}
    have = covered(all_kw, text)

    # A grammar upstream adds must land in a tier deliberately. Without this it would
    # simply be absent from every total, and the coverage percentage would go UP because
    # new surface appeared -- the exact failure this harness exists to prevent.
    unclassified = sorted(s for s in grammars if tiers.tier_of(s) == "unclassified")
    if unclassified:
        print(f"UNCLASSIFIED GRAMMARS (add to scripts/parser/tiers.py): {unclassified}\n")

    scope = tiers.TIER1 | tiers.TIER1_EMBEDDED | tiers.TIER2
    in_scope = _keywords_in(grammars, scope)
    dropped = _keywords_in(grammars, tiers.OUT_OF_SCOPE) - in_scope

    print(f"{len(grammars)} grammars, {len(all_kw)} distinct typeable keywords")
    print(f"  out of scope: {len(dropped)} keywords in {len(tiers.OUT_OF_SCOPE)} grammars "
          f"unreachable from .pure text (see tiers.py)")
    print(f"  IN SCOPE:     {len(in_scope)}\n")

    for label, stems in (("TIER 1  core Legend surface", tiers.TIER1),
                         ("TIER 1  embedded (GraphQL)", tiers.TIER1_EMBEDDED),
                         ("TIER 2  vendor connectors", tiers.TIER2_VENDOR),
                         ("TIER 2  extension DSLs", tiers.TIER2_DSL)):
        kws = _keywords_in(grammars, stems)
        print(f"{label:<32}{len(kws & have):>5} of {len(kws):<5} missing {len(kws - have)}")
    print(f"{'ALL IN SCOPE':<32}{len(in_scope & have):>5} of {len(in_scope):<5} "
          f"missing {len(in_scope - have)}")

    missing_embedded = [m for m in tiers.EMBEDDED if m not in text]
    print(f"{'embedded Pure parsers':<32}"
          f"{len(tiers.EMBEDDED) - len(missing_embedded):>5} of {len(tiers.EMBEDDED)}"
          + (f"    missing {missing_embedded}" if missing_embedded else ""))

    rows = []
    for stem, kws in grammars.items():
        miss = kws - have
        if miss and tiers.tier_of(stem) != "out":
            rows.append((tiers.tier_of(stem), len(miss), len(kws), stem, sorted(miss)))

    if "--tier1" in sys.argv:
        rows = [r for r in rows if r[0].startswith("1")]
    rows.sort(key=lambda r: (r[0], -r[1]))

    if "--gaps" in sys.argv or "--tier1" in sys.argv:
        print("\nwork queue:\n")
        for tier, miss, total, stem, words in rows:
            print(f"[t{tier}] [{total - miss:>3} of {total:>3}] "
                  f"{stem.replace('LexerGrammar', '')}")
            print("       " + ", ".join(words))
    else:
        print(f"\n{'grammar':<50}{'tier':>5}{'missing':>9}{'of':>6}")
        for tier, miss, total, stem, _ in sorted(rows, key=lambda r: -r[1])[:20]:
            print(f"  {stem:<48}{tier:>5}{miss:>9}{total:>6}")
        print(f"\n({len(rows)} in-scope grammars have gaps; "
              f"--tier1 or --gaps lists the keywords)")


if __name__ == "__main__":
    main()
