#!/usr/bin/env python3
"""Show, per package, each class's dependency DEPTH (0 = depends on nothing in
core). A package organised by layer has a narrow band; one organised by topic
is smeared across the whole range."""
import re,os,collections,sys
root="core/src/main/java"
def blank(m): return re.sub(r'[^\n]',' ',m.group(0))
def strip(s):
    s=re.sub(r'/\*.*?\*/',blank,s,flags=re.S); s=re.sub(r'//[^\n]*',blank,s)
    return re.sub(r'"(?:\\.|[^"\\\n])*"',blank,s)
files=[];fq={};pk={}
for dp,_,fns in os.walk(root):
    for fn in fns:
        if fn.endswith(".java") and fn!="package-info.java":
            p=os.path.join(dp,fn);files.append(p)
            pk[p]=os.path.relpath(dp,root).replace(os.sep,".");fq[p]=pk[p]+"."+fn[:-5]
ALL=set(fq.values())
imp=re.compile(r'^\s*import\s+(?:static\s+)?(com\.legend\.[\w.]+?)(?:\.\*)?\s*;',re.M)
word=re.compile(r'\b([A-Z]\w*)\b'); fqref=re.compile(r'\b(com\.legend(?:\.[a-z_]\w*)*\.[A-Z]\w*)')
E=collections.defaultdict(set); LOC={}
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read(); me=fq[p]; mp=pk[p]
    LOC[me]=raw.count("\n")+1
    b=strip(raw); bd={}
    for m in imp.finditer(raw):
        t=m.group(1)
        if t in ALL: bd[t.rsplit(".",1)[1]]=t
    for m in fqref.finditer(b):
        if m.group(1) in ALL and m.group(1)!=me: E[me].add(m.group(1))
    for m in word.finditer(b):
        n=m.group(1)
        if n in bd and bd[n]!=me: E[me].add(bd[n])
        else:
            c=mp+"."+n
            if c in ALL and c!=me: E[me].add(c)
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
sys.setrecursionlimit(100000); depth={}
def d(n):
    if n in depth: return depth[n]
    depth[n]=0; depth[n]=1+max((d(m) for m in dag.get(n,())),default=-1); return depth[n]
for i in range(len(csc)): d(i)
DEP={c:depth[comp[c]] for c in ALL}
P={c:c.rsplit(".",1)[0] for c in ALL}
sh=lambda x:x.replace("com.legend.","").replace("com.legend","(root)")
for target in sys.argv[1:]:
    cs=sorted([c for c in ALL if P[c]==target],key=lambda c:DEP[c])
    if not cs: continue
    print(f"\n=== {sh(target)} — {len(cs)} classes, depth {DEP[cs[0]]}..{DEP[cs[-1]]} ===")
    for c in cs:
        nm=c.rsplit(".",1)[1]
        print(f"   depth {DEP[c]:3d}   {nm:<34} {LOC[c]:5d} lines")
