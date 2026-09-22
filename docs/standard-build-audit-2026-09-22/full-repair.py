#!/usr/bin/env python3
"""Simulate the complete cycle repair for core, as a list of CLASS relocations.
No code restructuring: only package declarations and imports change."""
import re,os,collections,statistics,sys
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
def report(label,MOV):
    A={c:MOV.get(c,c.rsplit(".",1)[0]) for c in ALL}
    pe=collections.defaultdict(set); size=collections.Counter()
    for c in ALL: size[A[c]]+=1
    for a,bs in E.items():
        for b in bs:
            if A[a]!=A[b]: pe[A[a]].add(A[b])
    comps=sccs(set(A.values()),pe)
    unit={p:i for i,c in enumerate(comps) for p in c}
    us=collections.Counter()
    for p,n in size.items(): us[unit[p]]+=n
    ue=collections.defaultdict(set)
    for a,bs in pe.items():
        for b in bs:
            if unit[a]!=unit[b]: ue[unit[a]].add(unit[b])
    rev=collections.defaultdict(set)
    for a,bs in ue.items():
        for b in bs: rev[b].add(a)
    def clo(u):
        s,st={u},[u]
        while st:
            n=st.pop()
            for m in rev.get(n,()):
                if m not in s: s.add(m);st.append(m)
        return s
    rb=sorted(sum(us[x] for x in clo(u)) for u in us)
    cyc=[c for c in comps if len(c)>1]
    big=max((sum(size[p] for p in c) for c in cyc),default=0)
    print(f"{label:<46} units={len(comps):3d}  largest cycle={big:4d} files  "
          f"median rebuild={int(statistics.median(rb)):3d}  <100: {sum(1 for x in rb if x<100)}/{len(rb)}")
    return cyc,size
M={}
report("0  today",M)
M["com.legend.Nullable"]="com.legend.annot"; M["com.legend.NonNull"]="com.legend.annot"
report("1  + Nullable/NonNull -> annot (2)",M)
for c in ["ConstraintDefinition","DerivedPropertyDefinition","ParameterDefinition",
          "Protocol","Realization","TypeExpression"]:
    M[f"com.legend.protocol.{c}"]="com.legend.protocol.spec"
report("2  + 6 protocol classes -> protocol.spec",M)
for c in ["ElementParser","MappingProtocolParser","PmcdParser","QuotedSpecParser","RelationIslands",
          "SectionGrammarRegistry","ServiceLegacyMappingParser","ServiceStubDataParser","SpecParser"]:
    M[f"com.legend.parser.{c}"]="com.legend.parser.section"
report("3  + 9 parser classes -> parser.section",M)
for c in ["ResolvedNames","ModelBuilder","NameResolver","SynthFqn"]:
    M[f"com.legend.compiler.{c}"]="com.legend.compiler.names"
report("4  + 4 compiler names -> compiler.names",M)
M["com.legend.compiler.spec.InferenceKernel"]="com.legend.compiler.element"
M["com.legend.compiler.spec.typed.Feature"]="com.legend.compiler.element.type"
M["com.legend.compiler.spec.typed.TypedGetAll"]="com.legend.compiler.element"
M["com.legend.compiler.spec.typed.TypedSpec"]="com.legend.compiler.element"
cyc,size=report("5  + 4 singleton back-edge targets",M)
print(f"\nTOTAL CLASS RELOCATIONS: {len(M)}")
print(f"remaining cycles: {len(cyc)}")
for c in sorted(cyc,key=lambda c:-sum(size[p] for p in c)):
    print(f"   {len(c)} pkgs / {sum(size[p] for p in c)} files: "
          f"{sorted(x.replace('com.legend','') or '(root)' for x in c)}")
