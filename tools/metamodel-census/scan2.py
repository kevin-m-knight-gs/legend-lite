import re, json, collections
t='tools/metamodel-census/'
bodies=json.load(open(t+'family_bodies.json')); classes=json.load(open(t+'metamodel_classes.json'))
fam=json.load(open(t+'family_tests.json'))
MM_NS=('meta::pure::metamodel::','meta::relational::metamodel::','meta::pure::mapping::','meta::pure::executionPlan::','meta::pure::router::','meta::core::runtime::','meta::pure::runtime::','meta::relational::mapping::','meta::pure::lineage::','meta::relational::functions::pureToSqlQuery::metamodel::','meta::relational::runtime::','meta::protocols::','meta::external::store::','meta::pure::alloy::connections::','meta::relational::functions::toPostgresModel::','meta::pure::graphFetch::','meta::pure::extension::','meta::json::','meta::pure::tds::','meta::pure::executionPlan::')
def tier(f):
    if f in fam: return 'T'
    if '::tests::' in f or '::test::' in f: return 'H'
    return 'E'
simple=collections.defaultdict(list)
for fqn in classes:
    if fqn.startswith(MM_NS) and '::tests::' not in fqn: simple[fqn.split('::')[-1]].append(fqn)
def resolve(name):
    if name in classes: return [name] if name.startswith(MM_NS) else []
    return simple.get(name.split('::')[-1], []) if '::' not in name else []
# per test: type refs, navigation chains
per_test={}
type_refs=collections.Counter(); type_ex=collections.defaultdict(set)
chains=collections.Counter(); chain_ex=collections.defaultdict(set)
engine_calls=collections.Counter(); engine_ex=collections.defaultdict(set)
helper_calls=collections.Counter()
for f,b in bodies.items():
    tr=tier(f)
    if tr=='E': continue
    txt=b['text']
    refs=set()
    for m in re.finditer(r'(?:cast\(@|instanceOf\(|@|\^|:\s*)([\w:$]+)', txt):
        for fq in resolve(m.group(1)): refs.add(fq)
    for fq in refs:
        type_refs[fq]+=1; type_ex[fq].add(f.split('::')[-1])
    for m in re.finditer(r'\$[\w$]+((?:\.[\w$]+|->cast\(@[\w:$]+\)|->at\(\d+\)|->toOne\(\)|->first\(\))+)', txt):
        ch=m.group(1)
        if re.search(r'\.[a-z]', ch):
            chains[ch]+=1; chain_ex[ch].add(f.split('::')[-1])
    for m in re.finditer(r'([\w:$]+)\(', txt):
        n=m.group(1)
        if n.startswith('meta::') and n not in bodies and '::tests::' not in n: engine_calls[n]+=1; engine_ex[n].add(f.split('::')[-1])
        elif n in bodies and tier(n)=='E': engine_calls[n]+=1; engine_ex[n].add(f.split('::')[-1])
        elif n in bodies and tier(n)=='H': helper_calls[n]+=1
    per_test[f]={'tier':tr,'file':b['file'],'line':b['line'],'types':sorted(refs)}
json.dump({'per_test':per_test,'type_refs':type_refs.most_common(),'type_ex':{k:sorted(v)[:4] for k,v in type_ex.items()},'chains':chains.most_common(),'chain_ex':{k:sorted(v)[:3] for k,v in chain_ex.items()},'engine_calls':engine_calls.most_common(),'engine_ex':{k:sorted(v)[:3] for k,v in engine_ex.items()},'helper_calls':helper_calls.most_common()}, open(t+'scan2.json','w'), indent=0)
print('tests+helpers scanned', len(per_test))
print('\nMETAMODEL TYPES REFERENCED (tests+helpers):', len(type_refs))
for k,v in type_refs.most_common(70): print(f'{v:4} {k}   e.g. {sorted(type_ex[k])[:2]}')
print('\nNAV CHAINS:', len(chains))
for k,v in chains.most_common(70): print(f'{v:4} {k}   e.g. {sorted(chain_ex[k])[:2]}')
print('\nENGINE CALLS (tests+helpers -> engine library):', len(engine_calls))
for k,v in engine_calls.most_common(80): print(f'{v:4} {k}   e.g. {sorted(engine_ex[k])[:2]}')
