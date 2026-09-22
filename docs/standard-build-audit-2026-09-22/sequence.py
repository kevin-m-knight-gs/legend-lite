#!/usr/bin/env python3
"""Apply the 11 named move groups one at a time and measure what each buys."""
import re,os,collections,sys,json,statistics
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
E=collections.defaultdict(set); REF=collections.Counter()
for p in files:
    raw=open(p,encoding='utf-8',errors='replace').read(); me=fq[p]; mp=pk[p]; b=strip(raw); bd={}
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
B="com.legend."
GROUPS=[
 ("A  base vocabulary",               B+"base",           [B+x for x in ["Nullable","NonNull","ProgramFacts","ExecuteOptions"]]),
 ("B  protocol primitives",           B+"protocol.base",  [B+"protocol."+x for x in ["SourceInfo","Multiplicity","Escapes","SpanOrigin"]]),
 ("C  merge protocol into its SCC",   B+"protocol.spec",  [B+"protocol."+x for x in ["TypeExpression","Protocol","Realization","DerivedPropertyDefinition","ParameterDefinition","ConstraintDefinition"]]),
 ("D  name resolution",               B+"compiler.names", [B+"compiler."+x for x in ["ResolvedNames","SynthFqn","NameResolver","RelationalKinds","LiteralMapUnroll","DerivedProps","SymbolTable"]]),
 ("E  compile environment",           B+"compiler.env",   [B+"compiler.spec."+x for x in ["TypeInferenceException","Env","SourceSubst","CoreFn","Bindings","SchemaInvariantException","Expected","SignatureMangle","WalledBodies","AlphaRename","TdsNullForms"]]),
 ("F  typed metamodel",               B+"compiler.model", [B+"compiler.element."+x for x in ["ModelContext","TypedFunction","Property","TypedParameter","TypedClass","MilestoningStrategy","TypedEnum","TypedNominal","StoreCompiler","TypedElement","TypedConstraint"]]),
 ("G  relational layout facts",       B+"compiler.layout",[B+"compiler.element."+x for x in ["ClassLayouts","EqualityKeys","Temporal","RelationalOpRows","RelationalTypeInference"]]),
 ("H  typed leaf types",              B+"base",            [B+"compiler.spec.typed."+x for x in ["Feature","WindowFrame"]]),
 ("I  resolver vocabulary",           B+"resolver.model", [B+"resolver."+x for x in ["ClassSource","TemporalContext","RelationalRootForm","Callees","AsorRef","PipelineWalks","ChainNormalizer","RawGridSchema","ObjectReferenceArms","AggregationAwareRouting","ViewFrames","Space","DriverPkAppend","FunctionBodyRows","ClassConcatenates","ScalarValueReads","PkInference","StoreEscapees","NavReducer","LiteralFolds","ImportDataFlowAppend"]]),
 ("J  merge parser into its SCC",     B+"parser.section", [B+"parser."+x for x in ["ElementParser","SpecParser","MappingProtocolParser","SectionGrammarRegistry","PmcdParser","RelationIslands","ServiceStubDataParser","QuotedSpecParser","ServiceLegacyMappingParser"]]),
 ("K  sql leaf types",                B+"sql.base",       [B+"sql.dialect."+x for x in ["DialectCapability","RawSqlBoundary"]]),
]
M={}
def measure():
    A={c:M.get(c,c.rsplit(".",1)[0]) for c in ALL}
    size=collections.Counter()
    for c in ALL: size[A[c]]+=1
    pe=collections.defaultdict(set)
    for a,bs in E.items():
        for b in bs:
            if A[a]!=A[b]: pe[A[a]].add(A[b])
    comps=sccs(set(A.values()),pe)
    unit={p:i for i,g in enumerate(comps) for p in g}
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
            for m2 in rev.get(n,()):
                if m2 not in s: s.add(m2);st.append(m2)
        return s
    rb=sorted(sum(us[x] for x in clo(u)) for u in us)
    cyc=[g for g in comps if len(g)>1]
    big=max((sum(size[p] for p in g) for g in cyc),default=0)
    return len(comps),big,len(cyc),int(statistics.median(rb)),sum(1 for x in rb if x<100),len(rb)
u,b,nc,md,lt,tot=measure()
print(f"{'step':<34}{'units':>6}{'cycles':>7}{'biggest':>9}{'<100':>8}{'files edited':>14}")
print(f"{'0  today':<34}{u:>6}{nc:>7}{b:>9}{str(lt)+'/'+str(tot):>8}{'-':>14}")
for label,dest,cls in GROUPS:
    edits=0
    for c in cls:
        if c in ALL: M[c]=dest; edits+=REF[c]+1
    u,b,nc,md,lt,tot=measure()
    print(f"{label:<34}{u:>6}{nc:>7}{b:>9}{str(lt)+'/'+str(tot):>8}{edits:>14}")
print(f"\nclasses moved: {len(M)}   packages: {u}   cycles: {nc}")
