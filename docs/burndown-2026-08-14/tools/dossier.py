import os,re,json,collections,sys
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
REL="/Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational"
rows=json.load(open(OUT+'/failing.json'))
# index: short test name -> (file, startline, body)
idx=collections.defaultdict(list)
blk=re.compile(r'<<([^<>]*)>>')
for dp,_,fns in os.walk(REL):
    for fn in fns:
        if not fn.endswith('.pure'):continue
        p=os.path.join(dp,fn); src=open(p,encoding='utf-8',errors='replace').read()
        for m in blk.finditer(src):
            tags=set(t.strip().split('.')[-1] for t in m.group(1).split(',') if 'test.' in t)
            if 'Test' not in tags: continue
            nm=re.match(r'\s*(\{[^}]*\})?\s*([\w:$]+)\s*\(', src[m.end():m.end()+400])
            if not nm: continue
            fqn=nm.group(2); short=fqn.split('::')[-1]
            # find the enclosing 'function' keyword start
            fs=src.rfind('function',0,m.start())
            # walk braces from the first '{' after the signature
            b=src.find('{', m.end()+len(nm.group(0)))
            if b<0: continue
            d=0;i=b
            while i<len(src):
                if src[i]=='{': d+=1
                elif src[i]=='}':
                    d-=1
                    if d==0: break
                i+=1
            body=src[fs:i+1]
            idx[short].append((os.path.relpath(p,REL), src.count('\n',0,fs)+1, fqn, body))
out=collections.defaultdict(list)
miss=[]
for r in rows:
    hits=idx.get(r['test'],[])
    if not hits: miss.append(r); continue
    out[r['family']].append((r,hits))
print("resolved:",sum(len(v) for v in out.values()),"unresolved:",len(miss))
for m in miss[:20]: print("  MISS",m['family'],m['test'])
D=OUT+'/dossiers'; os.makedirs(D,exist_ok=True)
for fam,items in out.items():
    fn=fam.replace('/','__')+'.md'
    with open(os.path.join(D,fn),'w') as f:
        f.write(f"# {fam} — {len(items)} non-passing tests\n\n")
        for r,hits in items:
            f.write(f"\n---\n## [{r['status']}] {r['test']}\n\n")
            f.write(f"**legend-lite detail:** {r['detail'][:2500]}\n\n")
            for (fl,ln,fqn,body) in hits:
                f.write(f"**source:** `{fl}:{ln}`  fqn `{fqn}`\n\n```pure\n{body}\n```\n\n")
print("dossiers:", sorted(os.listdir(D)))
