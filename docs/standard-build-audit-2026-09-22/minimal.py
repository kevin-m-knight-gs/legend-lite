#!/usr/bin/env python3
"""MINIMAL-MOVE UNTANGLE.

The depth-split construction proves an acyclic assignment exists but over-moves:
it relocates every depth-0 leaf, including ones no low-level code reaches
(TypedConstraint). This searches for the FEWEST relocations instead.

While a package cycle exists, for every package edge inside it consider two
repairs — push the referring classes up into the target, or pull the referenced
classes down into a new leaf package — and take whichever costs fewest classes
(ties broken by fewest import edits). Repeat until acyclic.
"""
import re,os,collections,sys,json,statistics
root="core/src/main/java"
def blank(m): return re.sub(r'[^\n]',' ',m.group(0))
def strip(s):
    s=re.sub(r'/\*.*?\*/',blank,s,flags=re.S); s=re.sub(r'//[^\n]*',blank,s)
    return re.sub(r'"(?:\\.|[^"\\\n])*"',blank,s)
files=[];fq={};pk={};LOC={}
for dp,_,fns in os.walk(root):
    for fn in fns:
        if fn.endswith(".java") and fn!="package-info.java":
            p=os.path.join(dp,fn);files.append(p)
            pk[p]=os.path.relpath(dp,root).replace(os.sep,".");fq[p]=pk[p]+"."+fn[:-5]
ALL=set(fq.values())
imp=re.compile(r'^\s*import\s+(?:static\s+)?(com\.legend\.[\w.]+?)(?:\.\*)?\s*;',re.M)
word=re.compile(r'\b([A-Z]\w*)\b'); fqref=re.compile(r'\b(com\.legend(?:\.[a-z_]\w*)*\.[A-Z]\w*)')
E=collections.defaultdict(set); REF=collections.Counter()
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read(); me=fq[p]; mp=pk[p]
    LOC[me]=raw.count("\n")+1; b=strip(raw); bd={}
    for m in imp.finditer(raw):
        t=m.group(1)
        if t in ALL: bd[t.rsplit(".",1)[1]]=t
    seen=set()
    for m in fqref.finditer(b):
        if m.group(1) in ALL and m.group(1)!=me: seen.add(m.group(1))
    for m in word.finditer(b):
        n=m.group(1)
        if n in bd and bd[n]!=me: seen.add(bd[n])
        else:
            c=mp+"."+n
            if c in ALL and c!=me: seen.add(c)
    E[me]|=seen
    for t in seen: REF[t]+=1
RDEP=collections.defaultdict(set)
for a,bs in E.items():
    for b in bs: RDEP[b].add(a)
def sccs(nodes,ed):
    idx,low,on,st,cnt,out={},{},{},[],[0],[]
    for v in sorted(nodes):
        if v in idx: continue
        stk=[(v,iter(sorted(ed.get(v,()))))]
        idx[v]=low[v]=cnt[0];cnt[0]+=1;st.append(v);on[v]=True
        while stk:
            n,it=stk[-1];adv=False
            for w in it:
                if w not in idx:
                    idx[w]=low[w]=cnt[0];cnt[0]+=1;st.append(w);on[w]=True
                    stk.append((w,iter(sorted(ed.get(w,())))));adv=True;break
                elif on.get(w): low[n]=min(low[n],idx[w])
            if adv: continue
            stk.pop()
            if stk: low[stk[-1][0]]=min(low[stk[-1][0]],low[n])
            if low[n]==idx[n]:
                c=[]
                while True:
                    w=st.pop();on[w]=False;c.append(w)
                    if w==n:break
                out.append(sorted(c))
    return out
csc=sccs(ALL,E); comp={c:i for i,g in enumerate(csc) for c in g}
dagc=collections.defaultdict(set)
for a,bs in E.items():
    for b in bs:
        if comp[a]!=comp[b]: dagc[comp[a]].add(comp[b])
sys.setrecursionlimit(100000); dep={}
def dd(n):
    if n in dep: return dep[n]
    dep[n]=0; dep[n]=1+max((dd(m) for m in dagc.get(n,())),default=-1); return dep[n]
for i in range(len(csc)): dd(i)
DEP={c:dep[comp[c]] for c in ALL}
# genuine cross-package SCCs must co-locate: pre-merge them
A={c:c.rsplit(".",1)[0] for c in ALL}
premerge={}
for g in csc:
    if len(g)>1 and len({A[x] for x in g})>1:
        home=collections.Counter(A[x] for x in g).most_common(1)[0][0]
        for x in g:
            if A[x]!=home: premerge[x]=home; A[x]=home
def pcyc(A):
    pe=collections.defaultdict(lambda: collections.defaultdict(set))
    for a,bs in E.items():
        for b in bs:
            if A[a]!=A[b]: pe[A[a]][A[b]].add(a)
    flat={k:set(v) for k,v in pe.items()}
    return [g for g in sccs(set(A.values()),flat) if len(g)>1], pe
moves=dict(premerge)
for it in range(80):
    cyc,pe=pcyc(A)
    if not cyc: break
    comp_set=set(max(cyc,key=lambda g:sum(1 for c in ALL if A[c] in g)))
    best=None
    for P in comp_set:
        for Q,srcs in pe.get(P,{}).items():
            if Q not in comp_set: continue
            tgts={t for s in srcs for t in E[s] if A[t]==Q}
            for kind,cls,dest in (("push",srcs,Q),("pull",tgts,None)):
                cls=set(cls)
                if not cls: continue
                d=dest or f"{Q}.base"
                B={c:(d if c in cls else A[c]) for c in ALL}
                c2,_=pcyc(B)
                m2=sum(sum(1 for c in ALL if B[c] in g) for g in c2)
                m1=sum(sum(1 for c in ALL if A[c] in g) for g in cyc)
                if m2<m1:
                    score=(len(cls),sum(REF[c] for c in cls))
                    if best is None or score<best[0]: best=(score,cls,d,kind,P,Q)
    if best is None: print("stuck"); break
    _,cls,d,kind,P,Q=best
    for c in cls: A[c]=d; moves[c]=d
sh=lambda x:x.replace("com.legend.","").replace("com.legend","(root)")
cyc,_=pcyc(A)
groups=collections.defaultdict(list)
for c in ALL: groups[A[c]].append(c)
print(f"MINIMAL-MOVE RESULT: {len(groups)} packages, {len(cyc)} cycles, "
      f"{len(moves)} classes relocated (of {len(ALL)})")
print(f"edit surface ~{sum(REF[c]+1 for c in moves)} files\n")
json.dump({"moves":{k:v for k,v in moves.items()},"packages":{k:sorted(v) for k,v in groups.items()}},
          open("docs/standard-build-audit-2026-09-22/minimal-plan.json","w"),indent=1)
for k in sorted(groups,key=lambda k:(-len(groups[k]),k)):
    cs=sorted(groups[k],key=lambda c:-REF[c])
    ds=[DEP[c] for c in cs]
    mv=sum(1 for c in cs if c in moves)
    print(f"{sh(k):<30} {len(cs):>4} classes  depth {min(ds):>2}-{max(ds):<2}"
          + (f"  ({mv} moved in)" if mv else ""))
