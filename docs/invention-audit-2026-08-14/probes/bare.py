import json,os
A=os.environ['CLAUDE_JOB_DIR']+'/tmp/audit'
lite=[f for f in json.load(open(A+'/ll-natives.json')) if f.startswith('meta::legend::lite::')]
up=set(json.load(open(A+'/upstream-fns.json')))
upbare=set(f.split('::')[-1] for f in up)
print(f"{'lite native':34s} bare-name also upstream?")
uniq=[]
for f in sorted(lite):
    b=f.split('::')[-1]
    hit = b in upbare
    print(f"  {b:32s} {'yes' if hit else 'NO — unique to legend-lite'}")
    if not hit: uniq.append(b)
print(f"\n{len(uniq)} of {len(lite)} lite natives have a bare name that exists NOWHERE upstream:")
print(' ', ', '.join(uniq))
