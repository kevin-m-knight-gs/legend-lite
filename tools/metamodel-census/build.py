"""Rebuild the metamodel-census inputs (run from the repo root).

Inputs:  a corpus sweep log written with LL_TMP_DEBUG=1 (per-test
         [flip-wall-debug]/[flip-fail-debug] lines), target/wholetest-flipped.txt
         from the same tree, and the real checkouts under /Users/neemsandv/legend.
Outputs (tools/metamodel-census/): metamodel_classes.json (every `Class` in
         both checkouts), corpus_fn_index.json (function fqn -> [(file,line)]),
         family_tests.json (metamodel-family fallbacks with their wall message),
         family_bodies.json (test bodies + transitive corpus helpers),
         all_fallback_msgs.json, fallback_partition.json.
Then run scan2.py, scan3.py, closure.py, props.py for the derived tables.

Usage: python3 tools/metamodel-census/build.py <sweep.log> [flipped-roster.txt]
"""
import re, os, sys, json, collections
OUT='tools/metamodel-census/'
ROOTS={'legend-pure':'/Users/neemsandv/legend/legend-pure','legend-engine':'/Users/neemsandv/legend/legend-engine'}
log_path=sys.argv[1]; roster=sys.argv[2] if len(sys.argv)>2 else 'core/target/wholetest-flipped.txt'
def rd(p): return open(p,'rb').read().decode('utf-8','replace')

# 1. class universe
cls_re=re.compile(r'^Class\s+(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?([\w:$]+)(?:<[^>]*>)?\s*(?:extends\s+([^\n{]+?))?\s*(?:\[[^\]]*\])?\s*\{', re.M)
classes={}
for tag,R in ROOTS.items():
    for dp,dn,fn in os.walk(R):
        if '/target/' in dp or '/.git' in dp: continue
        for f in fn:
            if not f.endswith('.pure'): continue
            p=os.path.join(dp,f)
            try: s=rd(p)
            except Exception: continue
            for m in cls_re.finditer(s):
                fqn=m.group(1)
                if '::' not in fqn: continue
                i=m.end(); d=1
                while d>0 and i<len(s): d+=(s[i]=='{')-(s[i]=='}'); i+=1
                body=s[m.end():i-1]
                props=[(pm.group(1), pm.group(2).strip()[:60]) for pm in re.finditer(r'^\s*(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?([\w$]+)\s*(?:\([^)]*\))?\s*:\s*([^;{]+?)\s*(?:;|\{)', body, re.M)]
                classes.setdefault(fqn, {'file':tag+':'+p.replace(R+'/',''),'line':s[:m.start()].count('\n')+1,'extends':(m.group(2) or '').strip()[:120],'props':props})
json.dump(classes, open(OUT+'metamodel_classes.json','w'), indent=0)
print('classes', len(classes))

# 2. corpus function index (legend-engine only — the corpus + engine library)
idx={}
R=ROOTS['legend-engine']
for dp,dn,fn in os.walk(R):
    if '/target/' in dp or '/.git' in dp: continue
    for f in fn:
        if not f.endswith('.pure'): continue
        p=os.path.join(dp,f); s=rd(p)
        for m in re.finditer(r'^(?:native\s+)?function\s+(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?([\w:$]+)\(', s, re.M):
            idx.setdefault(m.group(1), []).append((p.replace(R+'/',''), s[:m.start()].count('\n')+1))
json.dump(idx, open(OUT+'corpus_fn_index.json','w'))
print('functions', len(idx))

# 3. family tests from the sweep log
ls=rd(log_path).splitlines(); flipped=set(rd(roster).splitlines())
msgs={}; runs=[]
for l in ls:
    if l.startswith('[run] '): runs.append(l[6:])
    if l.startswith('[flip-wall-debug]') or l.startswith('[flip-fail-debug]'):
        name=l.split(' :: ')[0].split('] ',1)[1]; msg=l.split(' :: ',1)[1] if ' :: ' in l else ''
        msgs.setdefault(name, msg)
fb={n:m for n,m in msgs.items() if n not in flipped}
MM=re.compile(r"HN vocabulary|FunctionDefinition has no property|scanRelations|meta::legend::executeLegendQuery|meta::legend::compileLegend|generateObjectReferences|routeFunction|classMappingById|rootClassMappingByClass|_classMappingByClass|metamodel::view|inferRelationalType|toPostgresModel::newState|InstanceValue|LambdaFunction has no property|enumValues|repeat'|toDomainValue|resolveStore|host channel|unknown type|Unknown type|metamodel|relationalMapper|routerExtensions|expressionSequence|SQLQuery|CoreDataType|SelectSQLQuery")
fam={n:m for n,m in fb.items() if MM.search(m) and 'filter predicate references column' not in m and 'exists/forAll predicate' not in m and 'MULTIPLICITY-STAMP' not in m}
json.dump(fam, open(OUT+'family_tests.json','w'), indent=1)
json.dump(fb, open(OUT+'all_fallback_msgs.json','w'), indent=1)
fallbacks=[r for r in runs if r not in flipped]
json.dump({'fallbacks':fallbacks,'noMsg':[f for f in fallbacks if f not in msgs]}, open(OUT+'fallback_partition.json','w'))
print('fallbacks', len(fallbacks), 'family', len(fam))

# 4. bodies: tests + transitive corpus helpers (bare names resolve within the same package)
cache={}
def src(p):
    if p not in cache: cache[p]=rd(os.path.join(R,p))
    return cache[p]
def body(fqn):
    if fqn not in idx: return None
    p,line=idx[fqn][0]; s=src(p)
    m=re.search(r'^function\s+(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?'+re.escape(fqn)+r'\(', s, re.M)
    if not m: return None
    i=s.index('{', m.end()); d=1; j=i+1
    while d>0 and j<len(s): d+=(s[j]=='{')-(s[j]=='}'); j+=1
    return {'file':p,'line':line,'text':s[m.start():j]}
bodies={}; todo=list(fam); seen=set()
while todo:
    f=todo.pop()
    if f in seen: continue
    seen.add(f); b=body(f)
    if not b: continue
    bodies[f]=b; pkg='::'.join(f.split('::')[:-1])
    for m in re.finditer(r'([\w:$]+)\(', b['text']):
        n=m.group(1); c=n if '::' in n else pkg+'::'+n
        if c in idx and c not in seen and not c.startswith('meta::pure::functions::'): todo.append(c)
json.dump(bodies, open(OUT+'family_bodies.json','w'))
print('bodies', len(bodies))
