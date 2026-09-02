import re, json, collections, os
t='tools/metamodel-census/'
R='/Users/neemsandv/legend/legend-engine'
bodies=json.load(open(t+'family_bodies.json')); classes=json.load(open(t+'metamodel_classes.json'))
fam=json.load(open(t+'family_tests.json'))
MM_NS=('meta::pure::metamodel::','meta::relational::metamodel::','meta::pure::mapping::','meta::pure::executionPlan::','meta::pure::router::','meta::core::runtime::','meta::pure::runtime::','meta::relational::mapping::','meta::pure::lineage::','meta::relational::functions::pureToSqlQuery::metamodel::','meta::relational::runtime::','meta::protocols::pure::vX_X_X::','meta::external::store::','meta::pure::alloy::connections::','meta::relational::functions::toPostgresModel::','meta::pure::graphFetch::','meta::pure::extension::','meta::json::','meta::pure::tds::','meta::relational::functions::sqlQueryToString::','meta::relational::postProcessor::','meta::relational::testDataGeneration::','meta::alloy::')
PRIM={'Boolean','Integer','String','Float','Date','DateTime','StrictDate','Number','Decimal','Any','Nil','Byte','LatestDate','Map','Pair','List'}
cache={}
def src(p):
    if p not in cache: cache[p]=open(os.path.join(R,p),encoding='utf-8',errors='replace').read()
    return cache[p]
def imports_for(p, line):
    s=src(p); lines=s.split('\n')
    # imports in the enclosing ###Pure section
    start=0
    for i in range(line-1,-1,-1):
        if lines[i].startswith('###'): start=i; break
    imps=[]
    for i in range(start, min(line, len(lines))):
        m=re.match(r'\s*import\s+([\w:$]+)::\*;', lines[i])
        if m: imps.append(m.group(1))
    return imps
def resolve(name, imps, pkg):
    if name in PRIM: return []
    if '::' in name:
        return [name] if name in classes and name.startswith(MM_NS) else []
    cands=[i+'::'+name for i in imps]+[pkg+'::'+name]
    return [c for c in cands if c in classes and c.startswith(MM_NS)]
# metamodel-only property names: appear on MM classes, never on corpus test-model classes
mm_props=collections.defaultdict(set); test_props=set()
for fqn,v in classes.items():
    for p,_ in v['props']:
        if fqn.startswith(MM_NS) and '::tests::' not in fqn: mm_props[p].add(fqn)
        elif '::tests::' in fqn or '::test::' in fqn: test_props.add(p)
mm_only={p for p in mm_props if p not in test_props}
def tier(f):
    if f in fam: return 'T'
    if '::tests::' in f or '::test::' in f: return 'H'
    return 'E'
per_test={}; type_refs=collections.Counter(); type_ex=collections.defaultdict(set)
chains=collections.Counter(); chain_ex=collections.defaultdict(set); chain_prop=collections.Counter(); prop_ex=collections.defaultdict(set)
for f,b in bodies.items():
    tr=tier(f)
    if tr=='E': continue
    txt=b['text']; imps=imports_for(b['file'], b['line']); pkg='::'.join(f.split('::')[:-1])
    refs=set()
    for m in re.finditer(r'(?:cast\(@|instanceOf\(|@|\^|:\s*)([\w:$]+)', txt):
        for fq in resolve(m.group(1), imps, pkg): refs.add(fq)
    for fq in refs: type_refs[fq]+=1; type_ex[fq].add(f.split('::')[-1])
    mych=set()
    for m in re.finditer(r'\$[\w$]+((?:\.[\w$]+|->cast\(@[\w:$]+\)|->at\(\d+\)|->toOne\(\)|->first\(\)|->evaluateAndDeactivate\(\))+)', txt):
        ch=m.group(1); props=re.findall(r'\.([\w$]+)', ch)
        if any(p in mm_only for p in props) or '->cast(@' in ch:
            mych.add(ch)
            for p in props:
                if p in mm_only: chain_prop[p]+=1; prop_ex[p].add(f.split('::')[-1])
    for ch in mych: chains[ch]+=1; chain_ex[ch].add(f.split('::')[-1])
    per_test[f]={'tier':tr,'file':b['file'],'line':b['line'],'types':sorted(refs),'chains':sorted(mych)}
json.dump({'per_test':per_test,'type_refs':type_refs.most_common(),'type_ex':{k:sorted(v)[:4] for k,v in type_ex.items()},'chains':chains.most_common(),'chain_ex':{k:sorted(v)[:3] for k,v in chain_ex.items()},'chain_prop':chain_prop.most_common(),'prop_ex':{k:sorted(v)[:3] for k,v in prop_ex.items()},'mm_only_props_count':len(mm_only)}, open(t+'scan3.json','w'), indent=0)
print('scanned', len(per_test), 'mm-only props', len(mm_only))
print('\nMETAMODEL TYPES (import-resolved):', len(type_refs))
for k,v in type_refs.most_common(80): print(f'{v:4} {k}   e.g. {sorted(type_ex[k])[:2]}')
print('\nMETAMODEL PROPERTIES NAVIGATED:', len(chain_prop))
for k,v in chain_prop.most_common(80): print(f'{v:4} {k} <- {sorted(mm_props[k])[:2]}   e.g. {sorted(prop_ex[k])[:2]}')
