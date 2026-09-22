#!/usr/bin/env python3
"""THE VALIDATED GRAPH = bytecode (jdeps) UNION source (regex).

Neither alone is correct for a BUILD dependency graph:
  * jdeps reads bytecode, so it misses everything the compiler erases. The
    decisive case here is @Nullable — RetentionPolicy.CLASS + TYPE_USE, stored
    in RuntimeInvisibleTypeAnnotations, which jdeps does not walk: it reports
    4 referrers where the source has 355. A java_library whose sources write
    @com.legend.Nullable must still have it on the compile classpath.
  * regex reads source, so it misses inherited members, erased generics and
    implicit references. Measured against jdeps: 90.3% recall, 93.3% precision.

The union is what javac actually needs. Usage:
    jdeps -v --multi-release 21 core/target/classes > jdeps.txt
    python3 union-graph.py jdeps.txt core/src/main/java
"""
import re,collections,os,sys,json,statistics
JD_FILE=sys.argv[1] if len(sys.argv)>1 else "/tmp/jdeps.txt"
ROOT=sys.argv[2] if len(sys.argv)>2 else "core/src/main/java"
fq={};pk={};LOC={}
for dp,_,fns in os.walk(ROOT):
    for fn in fns:
        if fn.endswith(".java") and fn!="package-info.java":
            p=os.path.join(dp,fn); pk[p]=os.path.relpath(dp,ROOT).replace(os.sep,".")
            fq[p]=pk[p]+"."+fn[:-5]
ALL=set(fq.values())
E=collections.defaultdict(set)
pat=re.compile(r'^\s+(com\.legend\.[\w.$]+)\s+->\s+(com\.legend\.[\w.$]+)\s')
nb=0
for ln in open(JD_FILE):
    m=pat.match(ln)
    if m:
        a,b=m.group(1).split('$')[0],m.group(2).split('$')[0]
        if a!=b and a in ALL and b in ALL: E[a].add(b); nb+=1
def blank(m): return re.sub(r'[^\n]',' ',m.group(0))
def strip(s):
    s=re.sub(r'/\*.*?\*/',blank,s,flags=re.S); s=re.sub(r'//[^\n]*',blank,s)
    return re.sub(r'"(?:\\.|[^"\\\n])*"',blank,s)
imp=re.compile(r'^\s*import\s+(?:static\s+)?(com\.legend\.[\w.]+?)(?:\.\*)?\s*;',re.M)
word=re.compile(r'\b([A-Z]\w*)\b'); fqref=re.compile(r'\b(com\.legend(?:\.[a-z_]\w*)*\.[A-Z]\w*)')
for p,me in fq.items():
    raw=open(p,encoding='utf-8',errors='replace').read(); LOC[me]=raw.count("\n")+1
    mp=pk[p]; b=strip(raw); bd={}
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
REF=collections.Counter()
for a,bs in E.items():
    for b in bs: REF[b]+=1
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
if __name__=="__main__":
    print(f"union graph: {len(ALL)} classes, {sum(len(v) for v in E.values())} class edges "
          f"({nb} from bytecode)")
    ORIG={c:c.rsplit(".",1)[0] for c in ALL}
    pe=collections.defaultdict(set)
    for a,bs in E.items():
        for b in bs:
            if ORIG[a]!=ORIG[b]: pe[ORIG[a]].add(ORIG[b])
    cy=[g for g in sccs(set(ORIG.values()),pe) if len(g)>1]
    print(f"packages: {len(set(ORIG.values()))}   package cycles: {len(cy)}")
    for g in sorted(cy,key=lambda g:-sum(1 for c in ALL if ORIG[c] in g)):
        print(f"  CYCLE {len(g)} packages / {sum(1 for c in ALL if ORIG[c] in g)} classes")
    cs=[g for g in sccs(ALL,E) if len(g)>1]
    span=[g for g in cs if len({ORIG[x] for x in g})>1]
    print(f"class SCCs >1: {len(cs)}   spanning packages: {len(span)}   "
          f"largest {max((len(g) for g in cs),default=0)} classes")
    for g in sorted(span,key=len,reverse=True)[:6]:
        pk2=collections.Counter(ORIG[x] for x in g)
        print(f"   {len(g)} classes across { {k.replace('com.legend.',''):v for k,v in pk2.items()} }")
