import os,re,collections
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
REL="/Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational"
blk=re.compile(r'<<([^<>]*)>>')
def strip_comments(src):
    src=re.sub(r'/\*.*?\*/','',src,flags=re.S)
    return '\n'.join(re.sub(r'//.*','',l) for l in src.split('\n'))
run=0; excl=collections.Counter(); tofix_only=0; tofix_only_list=[]
for dp,_,fns in os.walk(REL):
    for fn in fns:
        if not fn.endswith('.pure'):continue
        p=os.path.join(dp,fn)
        src=strip_comments(open(p,encoding='utf-8',errors='replace').read())
        for m in blk.finditer(src):
            tags=set(t.strip().split('.')[-1] for t in m.group(1).split(',') if 'test.' in t)
            if not tags: continue
            e=tags&{'ToFix','Ignore','ExcludeAlloy'}
            if 'Test' in tags:
                if e:
                    for x in e: excl[x]+=1
                else: run+=1
            elif e:
                tofix_only+=1
                nm=re.match(r'\s*(\{[^}]*\})?\s*([\w:$]+)\s*\(', src[m.end():m.end()+400])
                tofix_only_list.append((os.path.relpath(p,REL),src.count('\n',0,m.start())+1,nm.group(2) if nm else '?',sorted(tags)))
print("COMMENT-STRIPPED:")
print("  runnable (test.Test, not excluded):", run)
print("  test.Test + exclusion stereotype:", dict(excl), "=", sum(excl.values()))
print("  REAL TOTAL <<test.Test>>:", run+sum(excl.values()))
print("  ToFix/Ignore/ExcludeAlloy WITHOUT test.Test:", tofix_only)
print("     tag mix:", collections.Counter(tuple(t[3]) for t in tofix_only_list))
