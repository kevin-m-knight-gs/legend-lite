import re,os,collections,itertools,sys
root=sys.argv[1] if len(sys.argv)>1 else "core/src/main/java"
def blank(m):  # replace a comment/string with spaces, PRESERVING newlines (line numbers stay valid)
    return re.sub(r'[^\n]', ' ', m.group(0))
def strip_keep_lines(s):
    s=re.sub(r'/\*.*?\*/', blank, s, flags=re.S)
    s=re.sub(r'//[^\n]*', blank, s)
    s=re.sub(r'"(?:\\.|[^"\\\n])*"', blank, s)
    return s
pkg_of={};files=[]
for dp,_,fns in os.walk(root):
    for fn in fns:
        if fn.endswith(".java"):
            p=os.path.join(dp,fn);files.append(p);pkg_of[p]=os.path.relpath(dp,root).replace(os.sep,".")
BASE=set(pkg_of.values())
imp=re.compile(r'^\s*import\s+(?:static\s+)?(com\.legend[\w.]+)\s*;')
fqc=re.compile(r'\b(com\.legend(?:\.[a-z_]\w*)*)\.([A-Z]\w*)')
MOVES={("com.legend","Nullable"):"com.legend.annot",("com.legend","NonNull"):"com.legend.annot"}
node_of={p:MOVES.get((pkg_of[p],os.path.basename(p)[:-5]),pkg_of[p]) for p in files}
sizes=collections.Counter(node_of.values())
sites=collections.defaultdict(list)
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read(); a=node_of[p]
    clean=strip_keep_lines(raw)
    for i,ln in enumerate(clean.split("\n"),1):
        m=imp.match(ln)
        if m:
            parts=m.group(1).split('.')
            for k in range(len(parts)-1,1,-1):
                c='.'.join(parts[:k])
                if c in BASE:
                    b=MOVES.get((c,parts[-1]),c)
                    if a!=b: sites[(a,b)].append((p,i,parts[-1],"import"))
                    break
            continue
        for mm in fqc.finditer(ln):
            tp,tc=mm.group(1),mm.group(2)
            if tp in BASE:
                b=MOVES.get((tp,tc),tp)
                if a!=b: sites[(a,b)].append((p,i,tc,"fqn"))
edges=collections.defaultdict(set)
for (a,b) in sites: edges[a].add(b)
def sccs(nodes,E):
    idx,low,on,st,cnt,out={},{},{},[],[0],[]
    for v in sorted(nodes):
        if v in idx: continue
        stack=[(v,iter(sorted(E.get(v,()))))]
        idx[v]=low[v]=cnt[0];cnt[0]+=1;st.append(v);on[v]=True
        while stack:
            n,it=stack[-1];adv=False
            for w in it:
                if w not in idx:
                    idx[w]=low[w]=cnt[0];cnt[0]+=1;st.append(w);on[w]=True
                    stack.append((w,iter(sorted(E.get(w,())))));adv=True;break
                elif on.get(w): low[n]=min(low[n],idx[w])
            if adv: continue
            stack.pop()
            if stack: low[stack[-1][0]]=min(low[stack[-1][0]],low[n])
            if low[n]==idx[n]:
                comp=[]
                while True:
                    w=st.pop();on[w]=False;comp.append(w)
                    if w==n:break
                out.append(sorted(comp))
    return out
sh=lambda p:p.replace("com.legend.","").replace("com.legend","(root)")
comps=[c for c in sccs(set(sizes),edges) if len(c)>1]
print("CYCLE HOMEWORK — core/src/main/java")
print("Precondition applied: Nullable.java + NonNull.java moved to com.legend.annot")
print("(that move alone takes the graph from ONE 25-package / 667-file cycle to what follows)\n")
tot=sum(sizes.values())
print(f"files={tot}  packages={len(sizes)}  remaining cycles={len(comps)}\n"+"="*76)
for comp in sorted(comps,key=lambda c:-sum(sizes[p] for p in c)):
    nf=sum(sizes[p] for p in comp)
    print(f"\nCYCLE: {len(comp)} packages / {nf} files")
    for p in sorted(comp,key=lambda p:-sizes[p]): print(f"    {sizes[p]:4d}  {p}")
    intra={(a,b):len(sites[(a,b)]) for a in comp for b in edges[a] if b in comp}
    print(f"\n  {len(intra)} intra-cycle edges. Candidate cuts (fewest references first):")
    for (a,b),n in sorted(intra.items(),key=lambda kv:kv[1])[:8]:
        cls=collections.Counter(c for _,_,c,_ in sites[(a,b)])
        print(f"    {n:4d} refs  {sh(a)} -> {sh(b)}   [{', '.join(f'{c}x{k}' for c,k in cls.most_common(3))}]")
    best=None
    keys=list(intra)
    for r in range(1,min(len(keys),3)+1):
        for sub in itertools.combinations(keys,r):
            E2=collections.defaultdict(set)
            for a in comp:
                for b in edges[a]:
                    if b in comp and (a,b) not in sub: E2[a].add(b)
            if all(len(c)==1 for c in sccs(set(comp),E2)):
                w=sum(intra[e] for e in sub)
                if best is None or w<best[0]: best=(w,sub)
        if best: break
    if best:
        w,sub=best
        print(f"\n  >>> MINIMUM CUT: {len(sub)} edge(s), {w} reference site(s) to relocate")
        for a,b in sub:
            print(f"      CUT  {sh(a)} -> {sh(b)}  ({intra[(a,b)]} sites)")
            cls=collections.Counter(c for _,_,c,_ in sites[(a,b)])
            print(f"           types crossing: {', '.join(f'{c}({k})' for c,k in cls.most_common(10))}")
            for p,i,c,k in sites[(a,b)][:5]: print(f"           {p}:{i} [{k}] {c}")
            if len(sites[(a,b)])>5: print(f"           ... +{len(sites[(a,b)])-5} more")
    else:
        print("\n  >>> no cut of <=3 edges breaks this cycle")
    print("\n"+"="*76)
