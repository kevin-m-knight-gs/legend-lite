import re, json, os
t='tools/metamodel-census/'
classes=json.load(open(t+'metamodel_classes.json')); inv=json.load(open(t+'inventory.json'))
roots={'legend-pure':'/Users/neemsandv/legend/legend-pure','legend-engine':'/Users/neemsandv/legend/legend-engine'}
cache={}
def src(tagpath):
    tag,p=tagpath.split(':',1)
    full=os.path.join(roots[tag],p)
    if full not in cache: cache[full]=open(full,encoding='utf-8',errors='replace').read()
    return cache[full]
def class_body(fqn):
    c=classes[fqn]; s=src(c['file']); lines=s.split('\n')
    # find the class header line and its body
    pos=sum(len(l)+1 for l in lines[:c['line']-1])
    i=s.index('{', pos); d=1; j=i+1
    while d>0 and j<len(s): d+=(s[j]=='{')-(s[j]=='}'); j+=1
    return s[i+1:j-1]
def props(fqn):
    b=class_body(fqn); out=[]
    # strip nested braces (qualified property bodies, constraint bodies) but keep their heads
    depth=0; head=''; heads=[]
    for ch in b:
        if ch=='{':
            if depth==0: heads.append(('Q',head.strip())); head=''
            depth+=1
        elif ch=='}':
            depth-=1
            if depth==0: heads.append(('B',''))
        elif depth==0:
            if ch==';': heads.append(('P',head.strip())); head=''
            else: head+=ch
    for kind,h in heads:
        if not h: continue
        h=re.sub(r'<<[^>]*>>','',h); h=re.sub(r'\{[^}]*\}','',h).strip()
        m=re.match(r'^(?:\[[^\]]*\]\s*)?([\w$]+)\s*(\([^)]*\))?\s*:\s*(.+)$', h, re.S)
        if m:
            name,params,typ=m.group(1),m.group(2),m.group(3).strip()
            mm=re.search(r'\[([^\]]*)\]\s*$', typ)
            out.append({'name':name,'kind':'qualified' if params else 'property','type':typ[:mm.start()].strip() if mm else typ,'mult':mm.group(1) if mm else '','params':(params or '')[:80]})
    return out
def ancestors(fqn):
    return inv[fqn]['ancestors'] if fqn in inv else []
full={}
for fqn in inv:
    own=props(fqn); inh=[]
    for a in ancestors(fqn):
        if a in classes:
            for p in props(a): inh.append(dict(p, **{'from':a.split('::')[-1]}))
    full[fqn]={'file':inv[fqn]['file'],'line':inv[fqn]['line'],'extends':inv[fqn]['extends'],'own':own,'inherited':inh}
json.dump(full, open(t+'inventory_props.json','w'), indent=1)
for k in ['meta::relational::mapping::RootRelationalInstanceSetImplementation','meta::pure::metamodel::function::FunctionDefinition','meta::pure::metamodel::valuespecification::SimpleFunctionExpression','meta::pure::metamodel::valuespecification::InstanceValue','meta::relational::metamodel::relation::Table','meta::pure::executionPlan::ExecutionPlan','meta::pure::mapping::Mapping','meta::relational::mapping::RelationalPropertyMapping','meta::relational::metamodel::TableAliasColumn','meta::core::runtime::Runtime']:
    v=full.get(k)
    if not v: print('MISSING',k); continue
    print('\n##',k,'|',v['file'].split('/')[-1]+':'+str(v['line']),'| extends',v['extends'])
    print('   own:',[(p['name'],p['type'][:30],p['mult'],p['kind'][0]) for p in v['own']])
    print('   inherited:',[(p['name'],p['from']) for p in v['inherited']][:30])
