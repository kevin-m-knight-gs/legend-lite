import re,os,json,collections
LL="/Users/neemsandv/legend/legend-lite/core/src/main/java/com/legend/builtin/Pure.java"
src=open(LL).read()
# every declared native signature string
sigs=re.findall(r'signature\("native function ([^"]+)"\)', src)
fqns=[]
for s in sigs:
    m=re.match(r'\s*([\w:]+)\s*(<[^>]*>)?\s*\(', s)
    if m: fqns.append(m.group(1))
print("declared native signatures:",len(sigs)," distinct FQNs:",len(set(fqns)))
json.dump(sorted(set(fqns)),open(os.environ['CLAUDE_JOB_DIR']+'/tmp/audit/ll-natives.json','w'),indent=0)
c=collections.Counter('::'.join(f.split('::')[:-1]) for f in set(fqns))
print("\n=== declared natives by package ===")
for k,v in c.most_common(40): print(f"{v:5d}  {k}")
