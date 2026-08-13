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


def our_sources() -> str:
    """Every .pure this repository owns, concatenated. Coverage is measured against what
    WE wrote, not against what upstream happens to contain."""
    parts = []
    for root in ("core/src/test/resources", "scripts", "pct-corpus", "experiments"):
        base = REPO / root
        if not base.is_dir():
            continue
        for p in base.rglob("*.pure"):
            parts.append(p.read_text(errors="replace"))
    return "\n".join(parts)


def covered(keywords: set[str], text: str) -> set[str]:
    """A keyword counts as covered when it appears as a WORD in our sources.

    Substring matching would be wildly generous — 'in' occurs inside a thousand
    identifiers — so each is matched on word boundaries. Still an over-estimate: a keyword
    inside a comment counts. Tightening that needs the parser's own token stream, which is
    the natural next step and is noted rather than pretended away.
    """
    found = set()
    for k in keywords:
        if re.search(rf"(?<![A-Za-z0-9_]){re.escape(k)}(?![A-Za-z0-9_])", text):
            found.add(k)
    return found


def main() -> None:
    grammars = harvest()
    text = our_sources()
    all_kw = {k for ks in grammars.values() for k in ks}
    have = covered(all_kw, text)

    print(f"{len(grammars)} grammars, {len(all_kw)} distinct typeable keywords")
    print(f"covered by sources in THIS repo: {len(have)} ({len(have) / len(all_kw):.0%})")
    print(f"missing: {len(all_kw - have)}\n")

    rows = []
    for stem, kws in grammars.items():
        miss = kws - have
        if miss:
            rows.append((len(miss), len(kws), stem, sorted(miss)))
    rows.sort(reverse=True)

    if "--gaps" in sys.argv:
        print("gaps by grammar, largest first:\n")
        for miss, total, stem, words in rows:
            print(f"[{miss:>3} of {total:>3}] {stem}")
            print("      " + ", ".join(words[:16]) + (" ..." if len(words) > 16 else ""))
    else:
        print(f"{'grammar':<52}{'missing':>9}{'of':>6}")
        for miss, total, stem, _ in rows[:25]:
            print(f"  {stem:<50}{miss:>9}{total:>6}")
        print(f"\n({len(rows)} grammars have gaps; --gaps lists the keywords)")


if __name__ == "__main__":
    main()
