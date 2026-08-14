import json,re,os,collections
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
import os
rows=json.load(open(OUT+'/failing.json'))
def sig(r):
    d=r['detail']
    pats=[
      (r"assert form '([^']+)' is not supported yet","SHAPE:assert-form:\\1"),
      (r"no execute\(\|\.\.\.\) call","SHAPE:no-execute-call"),
      (r"object-space expression node (\w+) is not substitutable","ERR:not-substitutable:\\1"),
      (r"store resolution left getAll\(([^)]*)\) unresolved","ERR:getAll-unresolved"),
      (r"unknown function '([^']+)'","ERR:unknown-function:\\1"),
      (r"is not mapped in mapping","ERR:property-not-mapped"),
      (r"no overload of '([^']+)'","ERR:no-overload:\\1"),
      (r"extend/project columns \[([^\]]*)\] reference names unresolvable","ERR:unresolvable-column"),
      (r"Unknown type: '([^']+)'","ERR:unknown-type:\\1"),
      (r"has no property '([^']+)'","ERR:no-property:\\1"),
      (r"Binder Error","ERR:duckdb-binder"),
      (r"Catalog Error","ERR:duckdb-catalog"),
      (r"Conversion Error","ERR:duckdb-conversion"),
      (r"nested navigation .* inside an exists/isEmpty","ERR:nested-nav-exists"),
      (r"is graph output \(Phase H4\)","ERR:graph-output-H4"),
      (r"no scalar lowering registered","ERR:no-scalar-lowering"),
      (r"cannot access '([^']+)' on ","ERR:cannot-access"),
      (r"^assertEquals: expected","FAIL:assertEquals"),
      (r"^assert(Equals|Same|Empty|Size|Eq)","FAIL:assert"),
      (r"rows? (differ|mismatch)","FAIL:rows-differ"),
      (r"assertSize","FAIL:assertSize"),
    ]
    for p,lab in pats:
        m=re.search(p,d)
        if m:
            out=lab
            for i,g in enumerate(m.groups() or []):
                out=out.replace('\\%d'%(i+1), g)
            return out
    return r['status']+":OTHER:"+d[:70]
c=collections.Counter()
bysig=collections.defaultdict(list)
for r in rows:
    s=sig(r); c[s]+=1; bysig[s].append((r['status'],r['family'],r['test']))
print(f"{len(c)} distinct signatures over {len(rows)} tests\n")
for k,v in c.most_common():
    print(f"{v:4d}  {k}")
json.dump({k:v for k,v in bysig.items()},open(OUT+'/clusters.json','w'),indent=1)
