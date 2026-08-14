import re,collections,json,os
OUT = os.environ.get('BURNDOWN_OUT', '/tmp/burndown'); os.makedirs(OUT, exist_ok=True)
import os
lines=open('/Users/neemsandv/legend/legend-lite/docs/RELATIONAL_CORPUS.md').read().split('\n')
i=lines.index('### per-test outcomes (non-passing)')
rows=[]
for l in lines[i+1:]:
    m=re.match(r'- (SHAPE|FAIL|ERROR|UNSUPPORTED) (\S+) \[([^\]]+)\]: (.*)$', l)
    if m: rows.append({'status':m.group(1),'test':m.group(2),'family':m.group(3),'detail':m.group(4)})
print("rows parsed:",len(rows))
print(collections.Counter(r['status'] for r in rows))
c=collections.Counter(r['family'] for r in rows)
print("\n=== non-passing by family ===")
for k,v in c.most_common(): print(f"{v:4d}  {k}")
json.dump(rows,open(OUT+'/failing.json','w'),indent=1)
# write a compact tsv, truncating detail
with open(OUT+'/failing.tsv','w') as f:
    f.write("status\tfamily\ttest\tdetail\n")
    for r in rows: f.write(f"{r['status']}\t{r['family']}\t{r['test']}\t{r['detail'][:600]}\n")
