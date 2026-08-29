#!/usr/bin/env python3
"""0b golden shape survey for NAV_ROUTING_DESIGN_4AD_SLICE1 Batch 0.

Universe: distinct tests in nav-arm-census-4AD.txt (the measured nav-arm
blast radius) plus the design doc's named witnesses. For each test, find
its engine source, extract golden SQL strings (sqlRemoveFormatting
asserts), and classify each golden by shape and the pure query by
consumption position. FORCED split: family path contains ::forced:: or
body mentions forcedIsolation.
"""
import os, re, sys, collections

_HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGINE = os.environ.get("LEGEND_ENGINE_ROOT", "/Users/neemsandv/legend/legend-engine")
CENSUS = os.path.join(_HERE, "docs", "nav-arm-census-4AD.txt")
OUT = os.environ.get("GOLDEN_SURVEY_OUT", os.path.join(_HERE, "docs", "golden-shape-survey-4AD.tsv"))

EXTRA_WITNESSES = [
    "meta::relational::tests::advanced::structure::testQualifierWithOperation",
    "meta::relational::tests::advanced::structure::testTwoQualifiersWithOperation",
    "meta::relational::tests::advanced::forced::structure::testQualifierQueryWithOr",
    "meta::relational::tests::mapping::join::testChainedInnerJoinsWithQualifierInGroupBy",
    "meta::relational::tests::mapping::tree::testProjectMerge",
    "meta::relational::tests::projection::qualifier::testQualifierWithVariableArg",
]

# ---- load universe -------------------------------------------------------
tests = set(EXTRA_WITNESSES)
arms = collections.defaultdict(set)
with open(CENSUS) as f:
    for line in f:
        parts = line.split()
        if len(parts) == 2:
            arm, fqn = parts
            tests.add(fqn)
            arms[fqn].add(arm)

# ---- index engine test functions ----------------------------------------
# map FQN -> (file, start_line). Scan .pure files under the relational pure
# modules' tests dirs plus graphFetch tests. Broad scan of the whole
# checkout is too slow; restrict to files containing '::tests::'.
func_re = re.compile(r"^function\s+(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?(meta::[\w:]+)\s*\(")
index = {}
roots = [os.path.join(ENGINE, "legend-engine-xts-relationalStore"),
         os.path.join(ENGINE, "legend-engine-core")]
files_scanned = 0
for root in roots:
    for dirpath, dirnames, filenames in os.walk(root):
        if "/target/" in dirpath or "/node_modules/" in dirpath:
            continue
        for fn in filenames:
            if not fn.endswith(".pure"):
                continue
            path = os.path.join(dirpath, fn)
            try:
                with open(path, encoding="utf-8", errors="replace") as fh:
                    lines = fh.readlines()
            except OSError:
                continue
            files_scanned += 1
            for i, line in enumerate(lines):
                if not line.startswith("function"):
                    continue
                m = func_re.match(line)
                if m and m.group(1) in tests and m.group(1) not in index:
                    index[m.group(1)] = (path, i, lines)

# also handle multi-line function headers (stereotypes on prior line)
missing = [t for t in tests if t not in index]
if missing:
    name_res = {t: re.compile(re.escape(t) + r"\s*\(") for t in missing}
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            if "/target/" in dirpath:
                continue
            for fn in filenames:
                if not fn.endswith(".pure"):
                    continue
                path = os.path.join(dirpath, fn)
                try:
                    with open(path, encoding="utf-8", errors="replace") as fh:
                        content = fh.read()
                except OSError:
                    continue
                for t in list(name_res):
                    if name_res[t].search(content):
                        lines = content.splitlines(keepends=True)
                        for i, line in enumerate(lines):
                            if name_res[t].search(line) and ("function" in line or (i > 0 and "function" in lines[i-1])):
                                index.setdefault(t, (path, i, lines))
                                name_res.pop(t, None)
                                break
                if not name_res:
                    break

# ---- extract body + goldens ---------------------------------------------
def body_of(lines, start):
    out = []
    for j in range(start, len(lines)):
        if j > start and lines[j].startswith("function"):
            break
        out.append(lines[j])
    return "".join(out)

# golden = pure single-quoted string literal starting with select
golden_re = re.compile(r"'((?:select|Select|SELECT)\s(?:[^'\\]|\\.)*)'")

def unescape(s):
    return s.replace("\\'", "'").replace("\\n", " ")

def classify_sql(sql):
    s = " ".join(sql.lower().split())
    shapes = []
    if re.search(r"exists\s*\(\s*select", s):
        shapes.append("exists-predicate")
    if "_ecq" in s:
        shapes.append("exists-distinct-join")
    # joined subselect w/ group by inside parens
    for m in re.finditer(r"join\s+\((select[^()]*(?:\([^()]*\)[^()]*)*)\)", s):
        inner = m.group(1)
        if "group by" in inner:
            shapes.append("grouped-subselect-join")
        else:
            shapes.append("filtered-subselect-join")
    # correlated scalar subquery: a (select ...) NOT preceded by
    # join/exists/in/from/union (those are inline views / set ops)
    for m in re.finditer(r"\(\s*select\b", s):
        pre = s[max(0, m.start()-16):m.start()]
        if re.search(r"(join|exists|in|from|union|union all|,)\s*$", pre):
            continue
        shapes.append("scalar-subquery")
    # on-clause non-key predicate: literal comparison inside on(...)
    for m in re.finditer(r"\bon\s*\((.*?)\)\s*(?:left|right|inner|join|where|group|order|$)", s):
        if re.search(r"(=|<|>|like)\s*'", m.group(1)):
            shapes.append("on-clause-literal-pred")
    if re.search(r"\bwhere\b", s) and re.search(r"where .*?(=|<|>|like|in\s*\()\s*'?", s):
        if "join" in s:
            shapes.append("top-where-pred")
    if not shapes:
        if "join" in s:
            shapes.append("plain-join")
        else:
            shapes.append("single-table")
    return sorted(set(shapes))

def classify_position(body):
    pos = []
    ex = body
    if re.search(r"->filter\(\s*\w+\s*\|[^)]*\.\w+\([^)]*\)", ex) or re.search(r"->filter\(", ex):
        pos.append("filter")
    if re.search(r"->map\(", ex):
        pos.append("map")
    if re.search(r"->project", ex) or re.search(r"->projectWithColumnSubset", ex):
        pos.append("project")
    if re.search(r"->groupBy", ex):
        pos.append("groupBy")
    if not pos:
        pos.append("other")
    return "+".join(pos)

rows = []
nf = []
for t in sorted(tests):
    if t not in index:
        nf.append(t)
        continue
    path, start, lines = index[t]
    body = body_of(lines, start)
    forced = ("::forced::" in t) or ("forcedIsolation" in body) or ("Forced" in t)
    goldens = [unescape(g) for g in golden_re.findall(body)]
    position = classify_position(body)
    if not goldens:
        rows.append((t, "forced" if forced else "default", position, "no-sql-golden", "", "|".join(sorted(arms.get(t, set())))))
        continue
    for g in goldens:
        shapes = classify_sql(g)
        rows.append((t, "forced" if forced else "default", position, "+".join(shapes), g[:160], "|".join(sorted(arms.get(t, set())))))

with open(OUT, "w") as f:
    f.write("test\tmode\tposition\tshapes\tsql_prefix\tcensus_arms\n")
    for r in rows:
        f.write("\t".join(r) + "\n")

# ---- summary -------------------------------------------------------------
print(f"universe {len(tests)} tests; located {len(index)}; missing {len(nf)}; files scanned {files_scanned}")
print(f"rows (test,golden) = {len(rows)} -> {OUT}")
summary = collections.Counter()
for t, mode, pos, shapes, _, _ in rows:
    summary[(mode, shapes)] += 1
print("\nmode\tshapes\tcount")
for (mode, shapes), c in sorted(summary.items(), key=lambda kv: -kv[1]):
    print(f"{mode}\t{shapes}\t{c}")

# shape x position cross-tab (default-mode only, per atomic shape tag)
cross = collections.Counter()
witness = {}
for t, mode, pos, shapes, _, _ in rows:
    if mode != "default" or shapes == "no-sql-golden":
        continue
    for shape in shapes.split("+"):
        cross[(shape, pos)] += 1
        witness.setdefault((shape, pos), t)
print("\nshape\tposition\tcount\texample")
for (shape, pos), c in sorted(cross.items(), key=lambda kv: -kv[1]):
    print(f"{shape}\t{pos}\t{c}\t{witness[(shape,pos)]}")
if nf:
    print("\nMISSING (first 20):")
    for t in nf[:20]:
        print("  " + t)
