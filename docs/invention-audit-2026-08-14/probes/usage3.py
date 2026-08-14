import json,os,re
A=os.environ['CLAUDE_JOB_DIR']+'/tmp/audit'
CORPUS="/Users/neemsandv/legend/legend-engine"
lite=[f.split('::')[-1] for f in json.load(open(A+'/ll-natives.json')) if f.startswith('meta::legend::lite::')]
up=set(f.split('::')[-1] for f in json.load(open(A+'/upstream-fns.json')))
def clean(s):
    s=re.sub(r"'(?:\\.|[^'\\])*'","''",s)
    s=re.sub(r'/\*.*?\*/','',s,flags=re.S)
    return '\n'.join(re.sub(r'//.*','',l) for l in s.split('\n'))
hits={n:[] for n in lite}
for dp,dns,fns in os.walk(CORPUS):
    if '/.git' in dp or '/target/' in dp: continue
    for f in fns:
        if not f.endswith('.pure'): continue
        p=os.path.join(dp,f)
        try: s=clean(open(p,encoding='utf-8',errors='replace').read())
        except Exception: continue
        for n in lite:
            # a CALL: not preceded by $ (variable), ^ , alnum, _ or :
            for m in re.finditer(r'(?<![A-Za-z0-9_:$^.])'+re.escape(n)+r'\s*\(', s):
                hits[n].append(os.path.relpath(p,CORPUS)); break
            else:
                for m in re.finditer(r'->\s*'+re.escape(n)+r'\s*[(<]', s):
                    hits[n].append(os.path.relpath(p,CORPUS)); break
inv=[n for n in sorted(lite) if n not in up and not hits[n]]
print("REVISED — not upstream AND never CALLED by any corpus .pure:",len(inv))
print(' ',', '.join(inv))
prev=set(['avg','castAsDeclared','convertDateTimeFormat','convertTimeZoneFormat','divideRound',
 'legacyAssocPredicate','legacyLocalProperty','legacyNavigate','maxDate','minDate','navigate',
 'notEqualAnsi','otherwise','parseDateFormat','percentileCont','percentileDisc','sourceUrl',
 'typeAsDeclared','variantTo'])
print("\nnewly included (were false-excluded):", sorted(set(inv)-prev) or "none")
print("dropped:", sorted(prev-set(inv)) or "none")
for n in sorted(set(inv)-prev): print(f"   {n}: corpus 'hits' were {hits[n][:1]} -> re-checked as non-calls")
