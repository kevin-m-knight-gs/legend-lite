import json,re,os,collections,csv
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
import os
f=json.load(open(OUT+'/features.json'))
META=re.compile(r"(Unknown type|unknown class|has no property)")
ENGINE_INTERNAL=re.compile(r"\b(SQLQuery|ValueSpecification|FunctionExpression|CoreDataType|Operation|routeFunction|toSQLQuery|JoinTreeNode|OldAliasToNewAlias|TdsSelectSqlQuery|PureModelContextData|metamodel::datatype)\b")
DB=re.compile(r"(Binder Error|Catalog Error|Conversion Error|Invalid Input Error)")
def bucket(r):
    d=r['detail']
    if DB.search(d): return '5. INVALID/UNSUPPORTED SQL WE EMITTED (DuckDB rejects)'
    if r['has_meta_internals'] and not r['has_execute']: return '1. ENGINE SELF-METAMODEL (pure-implemented compiler internals)'
    if ENGINE_INTERNAL.search(d) and not r['has_execute']: return '1. ENGINE SELF-METAMODEL (pure-implemented compiler internals)'
    if r['has_plan']: return '2. EXECUTION-PLAN subsystem'
    if META.search(d): return '3. ENGINE METAMODEL SURFACE (missing class/property)'
    if r['has_toSQLString'] and not r['has_execute']: return '4. SQL-TEXT GOLDEN ONLY (advisory)'
    if d.startswith('sql-text:') or 'advisory golden-SQL' in d or 'h2-advisory' in d: return '4. SQL-TEXT GOLDEN ONLY (advisory)'
    if re.match(r'assert',d) or 'assertJsonStringsEqual' in d or 'assertTdsEquivalent' in d: return '6. WRONG ROWS / WRONG VALUE (real semantic defect)'
    if re.search(r'(not substitutable|multi-hop navigation|nested navigation|graph output|not mapped in mapping|getAll\(.*\) unresolved|unresolvable even after isolation|derived property|milestoned property|emptiness check|filtered-navigation|undemanded|resolver bug)',d): return '7. RESOLVER GAP (H-phase: substitution/navigation/mapping dispatch)'
    if re.search(r'(no scalar lowering|lowering not yet implemented|UNNEST|collection reduction|zip over|project expects)',d): return '8. LOWERING GAP (I-phase)'
    if re.search(r"(unknown function|no overload of|cannot access|expected at most one value|unbound variable|not a known class, mapping|unresolved type variable|is not a known)",d): return '9. TYPER / VOCABULARY GAP (G-phase)'
    if r['status']=='SHAPE': return '10. HARNESS SHAPE (assert/body form unrecognised)'
    return '11. UNCLASSIFIED'
for r in f: r['bucket']=bucket(r)
c=collections.Counter(r['bucket'] for r in f)
tot=len(f)
print(f'=== MASTER CLASSIFICATION of all {tot} non-passing core_relational tests ===\n')
for k in sorted(c): 
    v=c[k]; print(f'{v:5d}  ({100*v/tot:4.1f}%)  {k}')
print()
x=collections.Counter((r['bucket'],r['status']) for r in f)
print('=== bucket x status ===')
for k in sorted(c):
    print(f'{k[:56]:58s} ' + '  '.join(f'{s}={x[(k,s)]}' for s in ('FAIL','ERROR','SHAPE') if x[(k,s)]))
w=csv.DictWriter(open(OUT+'/master.csv','w',newline=''),
   fieldnames=['bucket','status','family','test','file','line','detail'],extrasaction='ignore')
w.writeheader()
for r in sorted(f,key=lambda x:(x['bucket'],x['family'],x['test'])):
    r=dict(r); r['detail']=re.sub(r'\s+',' ',r['detail'])[:400]; w.writerow(r)
print('\nmaster.csv written')
