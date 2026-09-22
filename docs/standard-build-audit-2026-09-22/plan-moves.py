#!/usr/bin/env python3
"""THE UNTANGLE PLAN.

Algorithm:
  1. Build the class-level graph (imports + fully-qualified refs, comments and
     string literals stripped with line numbers preserved).
  2. Collapse genuine class-level SCCs — these MUST co-locate, they are not
     filing decisions.
  3. Split every package by dependency depth. This is provably acyclic: every
     class edge points strictly downward in depth, so no cycle can survive.
  4. Greedily MERGE sub-packages back together while acyclicity holds,
     preferring merges within the same original package. This recovers
     semantic names and keeps the package count sane.
  5. A class whose final package differs from its original is a MOVE.
     Its cost is the number of files that reference it (import edits).
"""
import re,os,collections,sys,statistics,json
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
dag=collections.defaultdict(set)
for a,bs in E.items():
    for b in bs:
        if comp[a]!=comp[b]: dag[comp[a]].add(comp[b])
sys.setrecursionlimit(100000); dep={}
def d(n):
    if n in dep: return dep[n]
    dep[n]=0; dep[n]=1+max((d(m) for m in dag.get(n,())),default=-1); return dep[n]
for i in range(len(csc)): d(i)
DEP={c:dep[comp[c]] for c in ALL}; ORIG={c:c.rsplit(".",1)[0] for c in ALL}
# ---- step 3: split by (original package, depth); SCC members share a key ----
def key(c):
    g=csc[comp[c]]
    if len(g)>1:
        # genuine SCC: name it after the package holding the most members
        home=collections.Counter(ORIG[x] for x in g).most_common(1)[0][0]
        return (home, DEP[c], "scc%d"%comp[c])
    return (ORIG[c], DEP[c], "")
A={c:key(c) for c in ALL}
def cycles(A):
    pe=collections.defaultdict(set)
    for a,bs in E.items():
        for b in bs:
            if A[a]!=A[b]: pe[A[a]].add(A[b])
    return [g for g in sccs(set(A.values()),pe) if len(g)>1], pe
cy,_=cycles(A)
print(f"step 3  split by (package, depth): {len(set(A.values()))} packages, {len(cy)} cycles")
# ---- step 4: greedy merge, same original package first, then adjacent depth ----
def try_merge(A,x,y):
    B={c:(y if A[c]==x else A[c]) for c in ALL}
    return B if not cycles(B)[0] else None
improved=True; rounds=0
while improved and rounds<200:
    improved=False; rounds+=1
    groups=collections.defaultdict(list)
    for c in ALL: groups[A[c]].append(c)
    keys=sorted(groups, key=lambda k:(k[0],k[1]))
    for i,x in enumerate(keys):
        if x not in groups: continue
        for y in keys:
            if y==x or y not in groups: continue
            if x[0]!=y[0]: continue           # same original package only
            B=try_merge(A,x,y)
            if B is not None:
                A=B; improved=True
                groups=collections.defaultdict(list)
                for c in ALL: groups[A[c]].append(c)
                break
        if improved: break
final=collections.defaultdict(list)
for c in ALL: final[A[c]].append(c)
cy,_=cycles(A)
print(f"step 4  after merging within packages: {len(final)} packages, {len(cy)} cycles")
# name the resulting packages
name={}
for k,cs in final.items():
    base,dpt,tag=k
    same=[j for j in final if j[0]==base]
    if len(same)==1: name[k]=base
    else:
        rank=sorted(j[1] for j in same).index(dpt)
        name[k]=base if rank==len(same)-1 else f"{base}.l{rank}"
moves=[(c,ORIG[c],name[A[c]]) for c in ALL if name[A[c]]!=ORIG[c]]
print(f"\nMOVES REQUIRED: {len(moves)} classes of {len(ALL)}")
cost=sum(REF[c]+1 for c,_,_ in moves)
print(f"edit surface: ~{cost} files touched (the class + every referrer's import)\n")
bypkg=collections.defaultdict(list)
for c,o,n in moves: bypkg[o].append((REF[c],c,n))
print(f"{'from':<28}{'#':>4}  where they go")
for o,lst in sorted(bypkg.items(),key=lambda kv:-len(kv[1])):
    tgt=collections.Counter(n for _,_,n in lst)
    print(f"{o.replace('com.legend.','').replace('com.legend','(root)'):<28}{len(lst):>4}  "
          + ", ".join(f"{t.replace('com.legend.','')}({n})" for t,n in tgt.most_common(3)))
json.dump([{"class":c,"from":o,"to":n,"refs":REF[c],"loc":LOC[c],"depth":DEP[c]}
           for c,o,n in sorted(moves,key=lambda m:-REF[m[0]])],
          open("docs/standard-build-audit-2026-09-22/move-manifest.json","w"),indent=1)
print("\nmanifest -> docs/standard-build-audit-2026-09-22/move-manifest.json")
