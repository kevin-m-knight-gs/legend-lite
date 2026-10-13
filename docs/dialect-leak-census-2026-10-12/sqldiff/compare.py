"""Compare two whole-lane SQL dumps (extract.py's records, gzipped), target by target.
A pure move leaves every target identical. Prints, per target, identical or the first differing records with the
expression that produced them.
usage: python3 -I compare.py <before dir> <after dir> [max records shown per target]"""
import difflib
import gzip
import os
import sys

before, after = sys.argv[1], sys.argv[2]
show = int(sys.argv[3]) if len(sys.argv) > 3 else 3
names = sorted(set(os.listdir(before)) | set(os.listdir(after)))
same = differ = 0
for n in names:
    a, b = os.path.join(before, n), os.path.join(after, n)
    if not (os.path.exists(a) and os.path.exists(b)):
        print(f"{n}: only in {'before' if os.path.exists(a) else 'after'}")
        differ += 1
        continue
    ra = gzip.open(a, "rt", encoding="utf-8").read().split("\n\x1e\n")
    rb = gzip.open(b, "rt", encoding="utf-8").read().split("\n\x1e\n")
    if ra == rb:
        same += 1
        continue
    differ += 1
    sm = difflib.SequenceMatcher(None, ra, rb, autojunk=False)
    changed = [op for op in sm.get_opcodes() if op[0] != "equal"]
    print(f"== {n}: {sum(max(i2 - i1, j2 - j1) for _, i1, i2, j1, j2 in changed)} records differ ({len(ra)} -> {len(rb)})")
    for tag, i1, i2, j1, j2 in changed[:show]:
        ctx = next((r for r in reversed(ra[:i1]) if r.startswith("[LegendLite PCT]")), "(start)")
        print(f"   at {ctx[:160]}")
        for r in ra[i1:i2][:2]:
            print("   - " + r.replace("\n", "\n     ")[:600])
        for r in rb[j1:j2][:2]:
            print("   + " + r.replace("\n", "\n     ")[:600])
print(f"{same} identical, {differ} differ")
sys.exit(1 if differ else 0)
