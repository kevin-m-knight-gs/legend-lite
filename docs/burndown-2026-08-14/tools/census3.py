import os, re, collections, json, csv
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
ROOT="/Users/neemsandv/legend/legend-engine"
blk=re.compile(r'<<([^<>]*)>>')
rows=[]
for dp,dns,fns in os.walk(ROOT):
    if '/.git' in dp or '/target/' in dp: continue
    for fn in fns:
        if not fn.endswith('.pure'): continue
        p=os.path.join(dp,fn)
        try: src=open(p,encoding='utf-8',errors='replace').read()
        except Exception: continue
        if 'test.Test' not in src: continue
        for m in blk.finditer(src):
            tags=[t.strip() for t in m.group(1).split(',')]
            norm=[t.split('.')[-1] for t in tags if t.startswith('test.') or t.startswith('meta::pure::profiles::test.')]
            if 'Test' not in norm: continue
            tail=src[m.end():m.end()+400]
            n=re.match(r'\s*(\{[^}]*\})?\s*([\w:$]+)\s*\(', tail)
            if not n: continue
            rel=os.path.relpath(p,ROOT)
            cr=re.search(r'src/(main|test)/resources/([\w]+)/', rel)
            rows.append({'file':rel,'line':src.count('\n',0,m.start())+1,'fqn':n.group(2),
                         'tags':';'.join(sorted(set(norm))),
                         'pureRoot': cr.group(2) if cr else 'NON-RESOURCE',
                         'module': rel.split('/')[0]})
print("TOTAL:",len(rows))
byfqn={}
for r in rows: byfqn.setdefault(r['fqn'],r)
print("DISTINCT FQN:",len(byfqn))
w=csv.DictWriter(open(OUT+'/engine-tests.csv','w',newline=''),
                 fieldnames=['module','pureRoot','file','line','fqn','tags'])
w.writeheader()
for r in sorted(rows,key=lambda x:(x['module'],x['pureRoot'],x['file'],x['line'])): w.writerow(r)
c=collections.Counter(r['pureRoot'] for r in rows)
print("\n=== by pure resource root (top 30) ===")
for k,v in c.most_common(30): print(f"{v:6d}  {k}")
# excluded breakdown per root
print("\n=== runnable-vs-excluded per root (Test only vs Test+ToFix/Ignore/ExcludeAlloy) ===")
agg=collections.defaultdict(lambda:[0,0,0])
for r in rows:
    t=set(r['tags'].split(';'))
    excl = bool(t & {'ToFix','Ignore','ExcludeAlloy'})
    a=agg[r['pureRoot']]; a[0]+=1
    if excl: a[2]+=1
    else: a[1]+=1
for k,v in sorted(agg.items(), key=lambda x:-x[1][0])[:30]:
    print(f"{v[0]:6d} total  {v[1]:6d} runnable  {v[2]:5d} excluded   {k}")
