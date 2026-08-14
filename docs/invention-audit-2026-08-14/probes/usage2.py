import json,os,re
A=os.environ['CLAUDE_JOB_DIR']+'/tmp/audit'
CORPUS="/Users/neemsandv/legend/legend-engine"
lite=[f.split('::')[-1] for f in json.load(open(A+'/ll-natives.json')) if f.startswith('meta::legend::lite::')]
up=set(f.split('::')[-1] for f in json.load(open(A+'/upstream-fns.json')))
# strip single-quoted string literals and // comments, then look for bare calls
def clean(s):
    s=re.sub(r"'(?:\\.|[^'\\])*'", "''", s)          # pure string literals
    s=re.sub(r'/\*.*?\*/','',s,flags=re.S)
    return '\n'.join(re.sub(r'//.*','',l) for l in s.split('\n'))
hits={n:set() for n in lite}
for dp,dns,fns in os.walk(CORPUS):
    if '/.git' in dp or '/target/' in dp: continue
    for f in fns:
        if not f.endswith('.pure'): continue
        p=os.path.join(dp,f)
        try: s=clean(open(p,encoding='utf-8',errors='replace').read())
        except Exception: continue
        for n in lite:
            if re.search(r'(?<![A-Za-z0-9_:])'+re.escape(n)+r'\s*\(', s) or re.search(r'->\s*'+re.escape(n)+r'\s*[(<]', s):
                hits[n].add(os.path.relpath(p,CORPUS))
print(f"{'name':22s} {'upstream':9s} {'corpus .pure REALLY writes it'}")
for n in sorted(lite):
    u='yes' if n in up else 'NO'
    k=len(hits[n])
    ex = ('  e.g. '+sorted(hits[n])[0].split('/')[-1]) if k else ''
    print(f"  {n:20s} {u:9s} {k:3d}{ex}")
inv=[n for n in sorted(lite) if n not in up and not hits[n]]
print(f"\nNOT upstream AND never written by any corpus .pure ({len(inv)}):")
print('  ', ', '.join(inv))
