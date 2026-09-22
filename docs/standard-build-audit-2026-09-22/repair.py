#!/usr/bin/env python3
"""Iterative repair: while a package cycle exists, eliminate the package edge
inside it that is carried by the FEWEST distinct classes, by relocating exactly
those classes into the package they point at. Reports the full move list."""
import re,os,collections
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
E=collections.defaultdict(set)
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read(); me=fq[p]; mp=pk[p]; b=strip(raw); bd={}
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
A={c:c.rsplit(".",1)[0] for c in ALL}
def pcycles(A):
    pe=collections.defaultdict(set)
    for a,bs in E.items():
        for b in bs:
            if A[a]!=A[b]: pe[A[a]].add(A[b])
    return [c for c in sccs(set(A.values()),pe) if len(c)>1],pe
moves={}
for step in range(40):
    cyc,pe=pcycles(A)
    if not cyc: break
    comp=set(max(cyc,key=len))
    cand={}
    for a,bs in E.items():
        if A[a] not in comp: continue
        for b in bs:
            if A[b] in comp and A[b]!=A[a]: cand.setdefault((A[a],A[b]),set()).add(a)
    # only edges whose removal actually helps
    best=None
    for (P,Q),srcs in sorted(cand.items(),key=lambda kv:len(kv[1])):
        t=dict(A)
        for s in srcs: t[s]=Q
        c2,_=pcycles(t)
        if sum(len(c) for c in c2) < sum(len(c) for c in cyc):
            best=((P,Q),srcs); break
    if best is None:
        print(f"step {step}: no single-edge move reduces the cycle set"); break
    (P,Q),srcs=best
    for s in srcs: A[s]=Q; moves[s]=Q
    print(f"step {step}: {P.split('com.legend')[-1] or '(root)'} -> {Q.split('com.legend')[-1] or '(root)'} "
          f": relocate {len(srcs)} class(es)")
    for s in sorted(srcs): print(f"        {s.split('com.legend.')[-1]}")
cyc,_=pcycles(A)
print(f"\nTOTAL CLASSES RELOCATED: {len(moves)}   remaining package cycles: {len(cyc)}")
for c in cyc: print("   ",sorted(x.split('com.legend')[-1] or '(root)' for x in c))
