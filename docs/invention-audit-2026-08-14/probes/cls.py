import re,os,json,collections
A=os.environ['CLAUDE_JOB_DIR']+'/tmp/audit'
src=open("/Users/neemsandv/legend/legend-lite/core/src/main/java/com/legend/builtin/Pure.java").read()
decl=re.findall(r'nativeClass\("native Class ([\w:]+)', src)
print("native classes declared:",len(decl))
# upstream: every Class FQN defined in any .pure
up=set()
cre=re.compile(r'^\s*Class\s*(?:<<[^>]*>>)?\s*(?:\{[^}]*\})?\s*([\w:]+)', re.M)
are=re.compile(r'^\s*Association\s+([\w:]+)', re.M)
for R in ["/Users/neemsandv/legend/legend-engine","/Users/neemsandv/legend/legend-pure"]:
    for dp,dns,fns in os.walk(R):
        if '/.git' in dp or '/target/' in dp: continue
        for f in fns:
            if not f.endswith('.pure'): continue
            try: s=open(os.path.join(dp,f),encoding='utf-8',errors='replace').read()
            except Exception: continue
            for m in cre.finditer(s):
                if '::' in m.group(1): up.add(m.group(1))
            for m in are.finditer(s):
                if '::' in m.group(1): up.add(m.group(1))
print("upstream .pure Class/Association FQNs:",len(up))
missing=[c for c in sorted(set(decl)) if c not in up]
print("legend-lite native classes NOT defined upstream:",len(missing))
c=collections.Counter('::'.join(x.split('::')[:-1]) for x in missing)
for k,v in c.most_common(30): print(f"  {v:4d}  {k}")
json.dump(missing,open(A+'/missing-classes.json','w'),indent=0)
