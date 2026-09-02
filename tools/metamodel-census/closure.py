import re, json, collections, os
t='tools/metamodel-census/'
R='/Users/neemsandv/legend/legend-engine'
idx=json.load(open(t+'corpus_fn_index.json'))
s2=json.load(open(t+'scan2.json'))
calls=[n for n,c in s2['engine_calls']]
cache={}
def src(p):
    if p not in cache: cache[p]=open(os.path.join(R,p),encoding='utf-8',errors='replace').read()
    return cache[p]
def body(fqn):
    if fqn not in idx: return None
    texts=[]; native=False; p0=None; l0=None
    for p,line in idx[fqn]:
        s=src(p)
        for m in re.finditer(r'^(?:native\s+)?function\s+(?:<<[^>]*>>\s*)?(?:\{[^}]*\}\s*)?'+re.escape(fqn)+r'\(', s, re.M):
            if p0 is None: p0,l0=p,line
            if s[m.start():m.start()+6]=='native': native=True; continue
            try: i=s.index('{', m.end())
            except ValueError: continue
            d=1; j=i+1
            while d>0 and j<len(s): d+=(s[j]=='{')-(s[j]=='}'); j+=1
            texts.append(s[m.start():j])
    if p0 is None: return None
    return {'native':native and not texts,'p':p0,'line':l0,'text':'\n'.join(texts),'imports_all':[imports_for(p,l) for p,l in idx[fqn]]}
def imports_for(p, line):
    s=src(p); lines=s.split('\n'); start=0
    for i in range(line-1,-1,-1):
        if lines[i].startswith('###'): start=i; break
    return [m.group(1) for i in range(start, min(line,len(lines))) for m in [re.match(r'\s*import\s+([\w:$]+)::\*;', lines[i])] if m]
def callees(fqn, b):
    imps=sorted({i for il in b.get('imports_all',[]) for i in il}); pkg='::'.join(fqn.split('::')[:-1]); out=set()
    for m in re.finditer(r'([\w:$]+)\(', b['text']):
        n=m.group(1)
        if n.startswith('meta::pure::functions::'): continue
        if '::' in n:
            if n in idx: out.add(n)
        else:
            for c in [pkg+'::'+n]+[i+'::'+n for i in imps]:
                if c in idx: out.add(c); break
    return out
results={}
for root in calls:
    if root not in idx: results[root]={'kind':'class-or-native-ctor','lines':0,'closure_fns':0,'closure_lines':0}; continue
    seen={}; todo=[root]
    while todo:
        f=todo.pop()
        if f in seen: continue
        b=body(f)
        if b is None: seen[f]=0; continue
        seen[f]=len(b['text'].split('\n')) if not b['native'] else 0
        if b['native']: continue
        for c in callees(f,b):
            if c not in seen: todo.append(c)
        if len(seen)>4000: break
    rb=body(root)
    results[root]={'kind':'native' if rb and rb['native'] else 'pure','lines':seen.get(root,0),'closure_fns':len(seen),'closure_lines':sum(seen.values()),'file':rb['p'] if rb else ''}
json.dump(results, open(t+'closure.json','w'), indent=1)
for k,v in sorted(results.items(), key=lambda kv:-kv[1]['closure_lines']):
    print(f"{v['closure_lines']:7} lines / {v['closure_fns']:5} fns   own={v['lines']:4}  {v['kind']:6} {k}")
