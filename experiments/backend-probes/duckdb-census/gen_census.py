#!/usr/bin/env python3
rows=[]
for i in range(0,20):
    rows.append(('CEN2.%02d'%i,'CEN2',
      "SELECT group_concat(n) FROM (SELECT DISTINCT name AS n FROM pragma_function_list ORDER BY name LIMIT 10 OFFSET %d)"%(i*10)))
dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-cen2.tsv'
open(dest,'w').write(''.join('%s\t%s\t%s\n'%r for r in rows))
print('wrote',len(rows))
