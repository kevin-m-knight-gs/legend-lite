import json,os,subprocess
A=os.environ['CLAUDE_JOB_DIR']+'/tmp/audit'
LL="/Users/neemsandv/legend/legend-lite/core/src/main/java"
inv=['avg','castAsDeclared','convertDateTimeFormat','convertTimeZoneFormat','divideRound',
 'legacyAssocPredicate','legacyLocalProperty','legacyNavigate','maxDate','minDate','navigate',
 'notEqualAnsi','otherwise','parseDateFormat','percentileCont','percentileDisc','sourceUrl',
 'typeAsDeclared','variantTo']
def refs(n):
    o=subprocess.run(f'grep -rn \'"{n}"\' {LL} --include="*.java" | grep -v builtin/Pure.java',
        shell=True,capture_output=True,text=True).stdout.strip()
    return [l.split(':')[0].replace(LL+'/com/legend/','') for l in o.split('\n') if l]
print(f"{'invented name':22s} {'internal consumers (files)'}")
keep=[];dele=[]
for n in inv:
    r=sorted(set(refs(n)))
    print(f"  {n:20s} {len(r)}  {', '.join(r[:3]) if r else '— NONE —'}")
    (keep if r else dele).append(n)
print(f"\nHAS internal consumer -> real desugar vocabulary ({len(keep)}): {', '.join(keep)}")
print(f"NO internal consumer  -> dead surface, delete ({len(dele)}): {', '.join(dele)}")
