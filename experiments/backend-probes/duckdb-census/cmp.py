#!/usr/bin/env python3
import sys, os
D='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/'
def load(b):
    m={}
    for ln in open(D+'out-%s-strings.tsv'%b, encoding='utf-8'):
        parts=ln.rstrip('\n').split('\t',3)
        if len(parts)<4: continue
        m[parts[0]]=(parts[1],parts[2],parts[3])
    return m
duck=load('duckdb'); lite=load('sqlite'); pg=load('postgres')
order=[l.split('\t')[0] for l in open(D+'probes-strings.tsv',encoding='utf-8') if l.strip()]
sqlm={l.split('\t')[0]: l.rstrip('\n').split('\t',2)[2] for l in open(D+'probes-strings.tsv',encoding='utf-8') if l.strip()}
filt = sys.argv[1] if len(sys.argv)>1 else None
mode = sys.argv[2] if len(sys.argv)>2 else 'all'
for pid in order:
    if filt and not pid.startswith(filt): continue
    d=duck.get(pid,('','?','')); s=lite.get(pid,('','?','')); g=pg.get(pid,('','?',''))
    if mode=='div':
        divs = (s[1]=='OK' and d[1]=='OK' and s[2]!=d[2]) or (g[1]=='OK' and d[1]=='OK' and g[2]!=d[2])
        if not divs: continue
    if mode=='ok':
        if not (s[1]=='OK' or g[1]=='OK'): continue
    print('%-26s | D %s %-38s | S %s %-38s | P %s %s' % (pid, d[1], d[2][:38], s[1], s[2][:38], g[1], g[2][:60]))
