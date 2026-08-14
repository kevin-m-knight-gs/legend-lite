"""
Mutates every positive fixture and records what legend-engine does with the result.

The hand-written negatives are precise and slow to produce: each one pins a rule somebody
had to know about first. This is the other half — breadth. Take the fixtures that are known
to parse, damage them mechanically in ways a rewritten parser might plausibly tolerate, and
record the verdict for each.

WHAT THIS IS NOT: a correctness oracle. Nothing here knows whether a mutant SHOULD be
rejected. The manifest records what legend-engine actually does, which makes it a
differential fixture — legend-lite has to produce the same verdict for the same input, and
any disagreement is a divergence worth explaining. That is a weaker claim than the
hand-written negatives make, and it is worth having anyway because it covers ground nobody
would think to cover by hand.

The genuinely interesting output is the ACCEPTED list. A mutant that deletes a field, or
duplicates one, or reorders two clauses, and is still accepted, means one of two things:
the construct was optional (fine), or legend-engine is more permissive there than its
grammar suggests (worth knowing, and occasionally worth reporting). Either way a rewrite
must match it, and neither is visible from reading a .g4.

Deterministic by construction: mutation sites are chosen by position, never at random, so
the manifest diffs cleanly and a reviewer can regenerate exactly what they are looking at.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from collections import Counter
from pathlib import Path

import keywords as K

HERE = Path(__file__).resolve().parent
POSITIVE = HERE / "fixtures"
MANIFEST = HERE / "mutants.tsv"
REPO = HERE.parents[1]
RUNNER = REPO / "tools" / "engine-runner"
JAVA_HOME = os.environ.get("JAVA_HOME", str(Path.home() / "jdk/jdk-21.0.11+10/Contents/Home"))
WORK = Path(os.environ.get("CLAUDE_JOB_DIR", "/tmp")) / "tmp" / "mutants"

# Per operator, per fixture. Bounded so the run stays a couple of minutes rather than an
# afternoon, and so one enormous fixture cannot dominate the manifest.
MAX_SITES = 3


def _field_lines(lines: list[str]) -> list[int]:
    """Indices of lines that look like a complete `name: value;` field."""
    return [i for i, ln in enumerate(lines)
            if re.match(r"^\s*[~A-Za-z_][^:]*:\s*.+;\s*$", ln)]


def _sibling_pairs(lines: list[str]) -> list[int]:
    """Indices i where i and i+1 are fields at the SAME indentation — reordering those is
    the mutation a rewrite would tolerate, because they look interchangeable."""
    fields = set(_field_lines(lines))
    out = []
    for i in range(len(lines) - 1):
        if i in fields and i + 1 in fields:
            if len(lines[i]) - len(lines[i].lstrip()) == len(lines[i + 1]) - len(lines[i + 1].lstrip()):
                out.append(i)
    return out


def mutate(text: str):
    """Yield (operator, description, mutated_text). Text-level and deterministic."""
    lines = text.split("\n")

    for n, i in enumerate(_field_lines(lines)[:MAX_SITES]):
        out = lines[:i] + lines[i + 1:]
        yield "delete-field", lines[i].strip()[:60], "\n".join(out)

    for n, i in enumerate(_field_lines(lines)[:MAX_SITES]):
        out = lines[:i] + [lines[i], lines[i]] + lines[i + 1:]
        yield "duplicate-field", lines[i].strip()[:60], "\n".join(out)

    for n, i in enumerate(_sibling_pairs(lines)[:MAX_SITES]):
        out = lines[:i] + [lines[i + 1], lines[i]] + lines[i + 2:]
        yield "swap-siblings", lines[i].strip()[:60], "\n".join(out)

    for n, i in enumerate(_field_lines(lines)[:MAX_SITES]):
        out = list(lines)
        out[i] = out[i].rstrip().rstrip(";")
        yield "drop-semicolon", lines[i].strip()[:60], "\n".join(out)

    # Island delimiters. `}#` closed as a plain `}` is the mutation a hand-written parser
    # makes naturally when it scans forward for the next brace.
    for n, m in enumerate(list(re.finditer(r"\}#", text))[:MAX_SITES]):
        yield "island-close-plain", f"offset {m.start()}", text[:m.start()] + "}" + text[m.end():]

    for n, m in enumerate(list(re.finditer(r"#\{", text))[:MAX_SITES]):
        yield "island-open-plain", f"offset {m.start()}", text[:m.start()] + "{" + text[m.end():]

    # Body delimiters. Mapping uses parentheses and every other element uses braces; a
    # rewrite that normalises them would accept both everywhere.
    for n, m in enumerate(list(re.finditer(r"^\(\s*$", text, re.M))[:MAX_SITES]):
        yield "paren-body-to-brace", f"line at {m.start()}", text[:m.start()] + "{" + text[m.end():]

    for n, m in enumerate(list(re.finditer(r"^\{\s*$", text, re.M))[:MAX_SITES]):
        yield "brace-body-to-paren", f"line at {m.start()}", text[:m.start()] + "(" + text[m.end():]

    # Section header. Every element in the file is then routed to the wrong grammar.
    for m in list(re.finditer(r"^###([A-Za-z]+)\s*$", text, re.M))[:1]:
        other = "Text" if m.group(1) != "Text" else "Pure"
        yield "wrong-section", f"{m.group(1)} -> {other}", text[:m.start()] + f"###{other}" + text[m.end():]

    # Keyword case. Legend is case-sensitive; a case-insensitive lexer is a classic rewrite
    # shortcut, and this is the cheapest way to prove the reference implementation is not.
    code = K.strip_noncode(text)
    for n, word in enumerate(sorted({w for w in re.findall(r"\b[A-Z][A-Za-z]{3,}\b", code)})[:MAX_SITES]):
        swapped = word[0].lower() + word[1:]
        yield "keyword-lowercase", f"{word} -> {swapped}", re.sub(rf"\b{re.escape(word)}\b", swapped, text, count=1)


def run(paths: list[Path]) -> dict[str, str]:
    """name -> "" if accepted, else the rejection message."""
    cp = (RUNNER / "cp.txt").read_text().strip()
    cmd = [f"{JAVA_HOME}/bin/java", "-cp", f"{RUNNER}/target/classes:{cp}", "perf.ParseMain",
           *[str(p) for p in paths]]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    out = {}
    for line in r.stdout.splitlines():
        if line.startswith("SLF4J") or not line.strip():
            continue
        if line.startswith("ok ") or line.startswith("WRONG"):
            body = line[5:].strip()
            name, _, msg = body.partition("  REJECTED: ")
            out[name.strip()] = msg.strip()
    return out


def main() -> None:
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)

    index = {}
    for f in sorted(POSITIVE.rglob("*.pure")):
        for n, (op, where, body) in enumerate(mutate(f.read_text())):
            name = f"{f.stem}__{op}__{n}.pure"
            (WORK / name).write_text(body)
            index[name] = (f.name, op, where)

    print(f"{len(index)} mutants from {len(list(POSITIVE.rglob('*.pure')))} fixtures; parsing...")
    verdicts = run([WORK])

    rows, missing = [], 0
    for name, (fixture, op, where) in sorted(index.items()):
        if name not in verdicts:
            missing += 1
            continue
        msg = verdicts[name]
        rows.append((fixture, op, where, "REJECTED" if msg else "ACCEPTED", msg[:120]))

    header = (
        "# Generated by mutants.py -- what legend-engine does with each damaged fixture.\n"
        "# NOT a correctness oracle: it records behaviour, not what behaviour should be.\n"
        "# legend-lite must produce the same verdict for the same input.\n"
        "# fixture\toperator\tsite\tverdict\tmessage\n")
    body = "".join("\t".join(r) + "\n" for r in rows)

    if "--check" in sys.argv:
        # Regression gate. The manifest is a record of legend-engine's behaviour; if
        # regenerating it produces something different, either a fixture changed or the
        # engine did, and both need a human to look rather than a silent overwrite.
        if not MANIFEST.is_file():
            print("no manifest to check against -- run without --check first")
            sys.exit(1)
        if MANIFEST.read_text() != header + body:
            print("MANIFEST DRIFT: regenerating produced different verdicts.")
            old = {tuple(l.split("\t")[:3]): l.split("\t")[3:] for l in
                   MANIFEST.read_text().splitlines() if not l.startswith("#")}
            new = {tuple(l.split("\t")[:3]): l.split("\t")[3:] for l in
                   body.splitlines()}
            for k in sorted(set(old) | set(new)):
                if old.get(k) != new.get(k):
                    print(f"  {k}\n    was {old.get(k)}\n    now {new.get(k)}")
            sys.exit(1)
        print("manifest matches: no drift")
        sys.exit(0)

    MANIFEST.write_text(header + body)

    by_op = Counter((op, verdict) for _, op, _, verdict, _ in rows)
    print(f"\n{'operator':<24}{'rejected':>10}{'accepted':>10}")
    for op in sorted({op for _, op, _, _, _ in rows}):
        print(f"  {op:<22}{by_op[(op, 'REJECTED')]:>10}{by_op[(op, 'ACCEPTED')]:>10}")
    total_rej = sum(v for (o, s), v in by_op.items() if s == "REJECTED")
    total_acc = sum(v for (o, s), v in by_op.items() if s == "ACCEPTED")
    print(f"  {'TOTAL':<22}{total_rej:>10}{total_acc:>10}")
    if missing:
        print(f"\n{missing} mutants produced no verdict -- investigate, do not ignore")

    if "--accepted" in sys.argv:
        print("\nACCEPTED mutants (review queue -- optional field, or genuine permissiveness):\n")
        for fixture, op, where, verdict, _ in rows:
            if verdict == "ACCEPTED":
                print(f"  {op:<22}{fixture:<40}{where}")


if __name__ == "__main__":
    main()
