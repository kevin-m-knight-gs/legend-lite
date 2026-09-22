#!/usr/bin/env python3
"""CLASS-level dependency graph. A package cycle is often an artifact of package
assignment; the irreducible unit is the class-level SCC. This finds them."""
import re,os,collections,sys,statistics
root=sys.argv[1] if len(sys.argv)>1 else "core/src/main/java"
def blank(m): return re.sub(r'[^\n]',' ',m.group(0))
def strip(s):
    s=re.sub(r'/\*.*?\*/',blank,s,flags=re.S); s=re.sub(r'//[^\n]*',blank,s)
    return re.sub(r'"(?:\\.|[^"\\\n])*"',blank,s)
# map: simple class name -> fqcn (top-level types only, one per file)
files=[];fq_of={};pkg_of={}
for dp,_,fns in os.walk(root):
    for fn in fns:
        if fn.endswith(".java") and fn!="package-info.java":
            p=os.path.join(dp,fn);files.append(p)
            pkg=os.path.relpath(dp,root).replace(os.sep,".")
            pkg_of[p]=pkg; fq_of[p]=pkg+"."+fn[:-5]
byname=collections.defaultdict(list)
for p in files: byname[os.path.basename(p)[:-5]].append(fq_of[p])
ALL=set(fq_of.values())
imp=re.compile(r'^\s*import\s+(?:static\s+)?(com\.legend\.[\w.]+?)(?:\.\*)?\s*;',re.M)
word=re.compile(r'\b([A-Z]\w*)\b')
fqref=re.compile(r'\b(com\.legend(?:\.[a-z_]\w*)*\.[A-Z]\w*)')
edges=collections.defaultdict(set)
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read()
    me=fq_of[p]; mypkg=pkg_of[p]
    body=strip(raw)
    # explicit single-type imports bind a simple name to an fqcn
    bound={}
    for m in imp.finditer(raw):
        t=m.group(1)
        if t in ALL: bound[t.rsplit(".",1)[1]]=t
    # fully-qualified references
    for m in fqref.finditer(body):
        t=m.group(1)
        if t in ALL and t!=me: edges[me].add(t)
    # simple names: resolve via import, else same package, else unique global match
    for m in word.finditer(body):
        n=m.group(1)
        if n in bound:
            if bound[n]!=me: edges[me].add(bound[n]); continue
        cand=mypkg+"."+n
        if cand in ALL:
            if cand!=me: edges[me].add(cand)
            continue
        if len(byname.get(n,()))==1 and byname[n][0]!=me:
            # only bind if that package was imported wholesale or is same pkg
            pass
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
comps=sccs(ALL,edges)
big=sorted((c for c in comps if len(c)>1),key=len,reverse=True)
print(f"CLASS-LEVEL GRAPH  files={len(ALL)}  class-edges={sum(len(v) for v in edges.values())}")
print(f"class SCCs with >1 member: {len(big)}   largest: {len(big[0]) if big else 0} classes")
print(f"classes in ANY cycle: {sum(len(c) for c in big)} of {len(ALL)}")
print(f"classes in NO cycle:  {len(ALL)-sum(len(c) for c in big)}\n")
for c in big[:6]:
    pk=collections.Counter(x.rsplit(".",1)[0] for x in c)
    print(f"SCC of {len(c)} classes, spanning {len(pk)} packages:")
    for k,v in pk.most_common(): print(f"     {v:4d}  {k}")
    if len(c)<=14:
        for x in sorted(c): print(f"        {x}")
    print()
