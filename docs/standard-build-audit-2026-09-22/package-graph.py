#!/usr/bin/env python3
"""
Package dependency graph of a Java source tree — the measurement behind
STANDARD_BUILD_PROGRAM.md §4.1a ("how finely can core actually be cut?").

WHY THIS EXISTS. Bazel's compile incrementality is bounded by the coarsest
java_library you can legally declare, and a java_library cannot contain half
of a dependency cycle. So "a BUILD file per package" is only possible where
the package graph is acyclic. This script measures that.

WHY IT COUNTS MORE THAN IMPORTS. Java needs no import for a fully-qualified
reference, and this codebase writes thousands of them (`@com.legend.Nullable`,
`com.legend.protocol.spec.ValueSpecification`). An import-only analysis — which
is what most IDE and lint tooling does — reports a far healthier graph than the
compiler actually sees. Comments and string literals are stripped first, so a
javadoc {@link} does not manufacture an edge.

    python3 package-graph.py <source-root> [--prefix com.legend] [--move pkg.Class=new.pkg ...]

--move simulates relocating a class to a different package, to price a
refactor before doing it.
"""
import argparse, collections, os, re, statistics, sys


def strip_noncode(s):
    s = re.sub(r'/\*.*?\*/', ' ', s, flags=re.S)
    s = re.sub(r'//[^\n]*', ' ', s)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', s)


def scan(root, prefix, moves):
    pkg_of, files = {}, []
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if fn.endswith(".java"):
                p = os.path.join(dp, fn)
                files.append(p)
                pkg_of[p] = os.path.relpath(dp, root).replace(os.sep, ".")
    known = set(pkg_of.values())
    imp = re.compile(r'^\s*import\s+(?:static\s+)?(' + re.escape(prefix) + r'[\w.]+)\s*;', re.M)
    fqc = re.compile(r'\b(' + re.escape(prefix) + r'(?:\.[a-z_]\w*)*)\.([A-Z]\w*)')

    node_of = {p: moves.get((pkg_of[p], os.path.basename(p)[:-5]), pkg_of[p]) for p in files}
    sizes = collections.Counter(node_of.values())
    edges = collections.defaultdict(set)
    fqn_only = collections.Counter()

    for p in files:
        raw = open(p, encoding="utf-8", errors="replace").read()
        a = node_of[p]
        imported = set()
        for m in imp.finditer(raw):
            parts = m.group(1).split(".")
            for k in range(len(parts) - 1, 1, -1):
                c = ".".join(parts[:k])
                if c in known:
                    imported.add(c)
                    b = moves.get((c, parts[k]), c)
                    if a != b:
                        edges[a].add(b)
                    break
        body = strip_noncode(re.sub(r'^\s*import\s+[^;]+;', '', raw, flags=re.M))
        for m in fqc.finditer(body):
            tp, tc = m.group(1), m.group(2)
            if tp not in known:
                continue
            b = moves.get((tp, tc), tp)
            if a != b:
                edges[a].add(b)
                if tp not in imported:
                    fqn_only[(a, b)] += 1
    return sizes, edges, fqn_only


def sccs(nodes, edges):
    idx, low, on, st, cnt, out = {}, {}, {}, [], [0], []
    for v in sorted(nodes):
        if v in idx:
            continue
        stack = [(v, iter(sorted(edges.get(v, ()))))]
        idx[v] = low[v] = cnt[0]; cnt[0] += 1; st.append(v); on[v] = True
        while stack:
            n, it = stack[-1]; adv = False
            for w in it:
                if w not in idx:
                    idx[w] = low[w] = cnt[0]; cnt[0] += 1; st.append(w); on[w] = True
                    stack.append((w, iter(sorted(edges.get(w, ()))))); adv = True; break
                elif on.get(w):
                    low[n] = min(low[n], idx[w])
            if adv:
                continue
            stack.pop()
            if stack:
                low[stack[-1][0]] = min(low[stack[-1][0]], low[n])
            if low[n] == idx[n]:
                comp = []
                while True:
                    w = st.pop(); on[w] = False; comp.append(w)
                    if w == n:
                        break
                out.append(sorted(comp))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--prefix", default="com.legend")
    ap.add_argument("--move", action="append", default=[])
    a = ap.parse_args()
    moves = {}
    for spec in a.move:
        src, dst = spec.split("=", 1)
        pkg, cls = src.rsplit(".", 1)
        moves[(pkg, cls)] = dst

    sizes, edges, fqn_only = scan(a.root, a.prefix, moves)
    total = sum(sizes.values())
    comps = sccs(set(sizes), edges)
    unit = {p: i for i, c in enumerate(comps) for p in c}
    usize = collections.Counter()
    for p, n in sizes.items():
        usize[unit[p]] += n
    uedges = collections.defaultdict(set)
    for x, ys in edges.items():
        for y in ys:
            if unit[x] != unit[y]:
                uedges[unit[x]].add(unit[y])
    rev = collections.defaultdict(set)
    for x, ys in uedges.items():
        for y in ys:
            rev[y].add(x)

    def closure(u):
        seen, stk = {u}, [u]
        while stk:
            n = stk.pop()
            for m in rev.get(n, ()):
                if m not in seen:
                    seen.add(m); stk.append(m)
        return seen

    print(f"root={a.root}")
    print(f"files={total}  packages={len(sizes)}  package-edges={sum(len(v) for v in edges.values())}")
    print(f"edges carried ONLY by fully-qualified inline refs (invisible to import analysis): "
          f"{len(fqn_only)} across {sum(fqn_only.values())} sites")
    print(f"build units after collapsing cycles: {len(comps)}")
    if moves:
        print(f"simulated moves: {', '.join(a.move)}")
    for c in sorted((c for c in comps if len(c) > 1), key=lambda c: -sum(sizes[p] for p in c)):
        n = sum(sizes[p] for p in c)
        print(f"\nCYCLE: {len(c)} packages / {n} files ({100 * n / total:.0f}% of the tree)")
        for p in sorted(c, key=lambda p: -sizes[p]):
            print(f"   {sizes[p]:5d}  {p}")
    rows = []
    for u in sorted(usize):
        members = [p for p in sizes if unit[p] == u]
        nm = sorted(members, key=len)[0] + (f" (+{len(members)-1})" if len(members) > 1 else "")
        rows.append((sum(usize[x] for x in closure(u)), usize[u], nm))
    rows.sort()
    print(f"\n{'REBUILD':>8} {'OWN':>5}  unit   (REBUILD = files recompiled when this unit changes)")
    for nf, own, nm in rows:
        print(f"{nf:8d} {own:5d}  {nm}")
    r = [x[0] for x in rows]
    print(f"\nunits={len(rows)}  median rebuild={int(statistics.median(r))}  "
          f"units under 100 files: {sum(1 for x in r if x < 100)}/{len(r)}")
    print(f"today, with one Maven module, every change rebuilds all {total}")


if __name__ == "__main__":
    main()
