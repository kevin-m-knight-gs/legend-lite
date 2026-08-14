import os,re,json,collections
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
REL="/Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational"
rows=json.load(open(OUT+'/failing.json'))
blk=re.compile(r'<<([^<>]*)>>')
idx={}
for dp,_,fns in os.walk(REL):
    for fn in fns:
        if not fn.endswith('.pure'):continue
        p=os.path.join(dp,fn);src=open(p,encoding='utf-8',errors='replace').read()
        for m in blk.finditer(src):
            tags=set(t.strip().split('.')[-1] for t in m.group(1).split(',') if 'test.' in t)
            if 'Test' not in tags: continue
            nm=re.match(r'\s*(\{[^}]*\})?\s*([\w:$]+)\s*\(', src[m.end():m.end()+400])
            if not nm: continue
            fs=src.rfind('function',0,m.start()); b=src.find('{',m.end()+len(nm.group(0)))
            if b<0: continue
            d=0;i=b
            while i<len(src):
                if src[i]=='{':d+=1
                elif src[i]=='}':
                    d-=1
                    if d==0:break
                i+=1
            idx[nm.group(2).split('::')[-1]]=(os.path.relpath(p,REL),src.count('\n',0,fs)+1,src[fs:i+1])
ASSERT=re.compile(r'\b(assert[A-Za-z]*|meta::pure::tds::schema::tests::assertSchemaRoundTripEquality)\s*\(')
CALL=re.compile(r'->\s*([a-zA-Z][\w]*)\s*\(')
out=[]
for r in rows:
    fl,ln,body=idx[r['test']]
    asserts=collections.Counter(ASSERT.findall(body))
    calls=collections.Counter(CALL.findall(body))
    out.append({**r,'file':fl,'line':ln,'nlines':body.count('\n')+1,
        'asserts':dict(asserts),'topcalls':[k for k,_ in calls.most_common(14)],
        'has_execute': bool(re.search(r'\bexecute\s*\(', body)),
        'has_toSQLString': 'toSQLString' in body or 'sqlQueryToString' in body,
        'has_plan': 'executionPlan' in body or 'ExecutionPlan' in body or 'planToString' in body or 'generatePlan' in body,
        'has_meta_internals': bool(re.search(r'\b(routeFunction|toSQLQuery|deactivate|evaluateAndDeactivate|byPassValueSpecificationWrapper|RelationalExecutionContext|JoinTreeNode|OldAliasToNewAlias|SQLQuery|ValueSpecification|FunctionExpression)\b', body)),
        })
json.dump(out,open(OUT+'/features.json','w'),indent=1)
print("features for",len(out))
print("\n=== body-shape flags across the 276 ===")
for k in ['has_execute','has_toSQLString','has_plan','has_meta_internals']:
    print(f"  {k:22s} {sum(1 for r in out if r[k]):4d}")
print("\n=== assert vocabulary (distinct asserts used) ===")
av=collections.Counter()
for r in out:
    for a in r['asserts']: av[a]+=1
for k,v in av.most_common(): print(f"  {v:4d}  {k}")
