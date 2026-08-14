import re,os,json,collections
ROOTS=["/Users/neemsandv/legend/legend-engine","/Users/neemsandv/legend/legend-pure"]
# every function FQN DEFINED upstream, in .pure sources (native or user), plus
# Java-side native registrations (name strings in *.java of pure runtime extensions)
defined=set(); where={}
fn=re.compile(r'^\s*(?:native\s+)?function\s*(?:<<[^>]*>>)?\s*(?:\{[^}]*\})?\s*([\w:]+)\s*[<(]', re.M)
for R in ROOTS:
    for dp,dns,fns in os.walk(R):
        if '/.git' in dp or '/target/' in dp: continue
        for f in fns:
            if not f.endswith('.pure'): continue
            p=os.path.join(dp,f)
            try: s=open(p,encoding='utf-8',errors='replace').read()
            except Exception: continue
            for m in fn.finditer(s):
                q=m.group(1)
                if '::' in q:
                    defined.add(q); where.setdefault(q,p)
print("upstream .pure function FQNs:",len(defined))
ll=json.load(open(os.environ['CLAUDE_JOB_DIR']+'/tmp/audit/ll-natives.json'))
missing=[f for f in ll if f not in defined]
print("legend-lite natives:",len(ll)," NOT DEFINED in any upstream .pure:",len(missing))
c=collections.Counter('::'.join(f.split('::')[:-1]) for f in missing)
print("\n=== unmatched natives by package ===")
for k,v in c.most_common(): print(f"{v:5d}  {k}")
json.dump(missing,open(os.environ['CLAUDE_JOB_DIR']+'/tmp/audit/unmatched.json','w'),indent=0)
json.dump(sorted(defined),open(os.environ['CLAUDE_JOB_DIR']+'/tmp/audit/upstream-fns.json','w'),indent=0)
