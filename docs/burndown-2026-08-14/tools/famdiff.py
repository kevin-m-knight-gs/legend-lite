import os,re,collections
REL="/Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational"
SB="/Users/neemsandv/legend/legend-lite/docs/RELATIONAL_CORPUS.md"
blk=re.compile(r'<<([^<>]*)>>')
mine=collections.Counter()
for dp,_,fns in os.walk(REL):
    fam=os.path.relpath(dp,REL)
    for fn in fns:
        if not fn.endswith('.pure'):continue
        src=open(os.path.join(dp,fn),encoding='utf-8',errors='replace').read()
        for m in blk.finditer(src):
            tags=set(t.strip().split('.')[-1] for t in m.group(1).split(',') if 'test.' in t)
            if 'Test' in tags and not (tags&{'ToFix','Ignore','ExcludeAlloy'}): mine[fam]+=1
sb={}
for line in open(SB):
    if not line.startswith('| '): continue
    c=[x.strip() for x in line.split('|')]
    if len(c)<4 or c[1] in ('family','---','**total**') or '**total**' in line: continue
    try: sb[c[1]]=int(c[2])
    except: pass
allk=sorted(set(mine)|set(sb))
print(f"{'family':60s} {'grep':>5s} {'sweep':>5s} {'diff':>5s}")
tg=ts=0
for k in allk:
    a=mine.get(k,0); b=sb.get(k,0); tg+=a; ts+=b
    if a!=b: print(f"{k:60s} {a:5d} {b:5d} {a-b:5d}")
print(f"{'TOTAL':60s} {tg:5d} {ts:5d} {tg-ts:5d}")
