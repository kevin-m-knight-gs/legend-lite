#!/usr/bin/env python3
# HAND-SHAPE DIVERGENCE SWEEP (2026-09-08): every `nativeClass("…")` in Pure.java diffed against its
# spec declaration — supertypes, type parameters, stored properties (type + multiplicity). Spec = the
# legend-pure / legend-engine checkouts (class declarations) or tools/m3shape.py (the m3 bootstrap).
# A report, not a pin: docs/HAND_SHAPE_DIVERGENCE_2026_09_08.md carries the reading and the plan.
# Known tool gaps: m3 Package/Measure/Unit (m3shape.py cannot read them), Class<T>'s type parameter and
# a FunctionType generalization argument (printed `<>` / `??`), the primitives (m3 instances, no body).
import re, os, subprocess, glob, json
LITE='/Users/neema/legend/legend-lite'; PURE='/Users/neemsandv/legend/legend-pure'; ENG='/Users/neemsandv/legend/legend-engine'
src=open(LITE+'/core/src/main/java/com/legend/builtin/Pure.java').read()
decls=re.findall(r'nativeClass\("(native Class [^"]*)"', src)
def strip_pkg(t):
    return re.sub(r'(?:[A-Za-z_][A-Za-z0-9_]*::)+','',t)
def parse_decl(text):
    m=re.match(r'native Class ([A-Za-z0-9_:]+)(<[^>]*>)?\s*(?:extends\s+(.*?))?\s*\{(.*)\}\s*$', text.strip(), re.S)
    if not m: return None
    fqn,params,sups,body=m.group(1),m.group(2) or '',m.group(3) or '',m.group(4)
    sups=[strip_pkg(s.strip()) for s in re.split(r',(?![^<]*>)', sups) if s.strip()]
    props={}
    for p in re.finditer(r'(?:<<[^>]*>>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^;=]+?)(?:\s*=\s*[^;]*)?;', body):
        typ=p.group(2).strip()
        mm=re.match(r'(.*)(\[[^\]]*\])$', typ)
        t,mult=(mm.group(1),mm.group(2)) if mm else (typ,'')
        props[p.group(1)]=(strip_pkg(re.sub(r'\s+','',t)), mult)
    return fqn, params.replace(' ',''), sups, props
def spec_text_for(fqn):
    short=fqn.split('::')[-1]
    pat=re.compile(r'^Class\s+(?:<<[^>]*>>\s*)*(?:\{[^}]*\}\s*)?'+re.escape(fqn)+r'(<[^>]*>)?\s*(?:extends\s+([^{/\n]*?))?\s*(?://[^\n]*)?\s*\{', re.M)
    for root in (PURE, ENG):
        for f in glob.glob(root+'/**/*.pure', recursive=True):
            if '/tests/' in f or '/test/' in f and 'platform' not in f: pass
            try: s=open(f,encoding='utf-8',errors='ignore').read()
            except: continue
            m=pat.search(s)
            if m:
                i=m.end(); depth=1; j=i
                while depth and j<len(s):
                    depth+= (s[j]=='{') - (s[j]=='}'); j+=1
                return f, (m.group(1) or '').replace(' ',''), m.group(2) or '', s[i:j-1]
    return None
def parse_spec_body(body):
    props={}
    # strip derived (with parentheses before '{' ) and constraints; keep stored: name : Type[m];
    for line in body.split('\n'):
        l=line.strip()
        if not l or l.startswith('//') or '(' in l.split(':')[0]: continue
        m=re.match(r'(?:<<[^>]*>>\s*)*(?:\{[^}]*\}\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^;=]+?)(?:\s*=\s*[^;]*)?;', l)
        if m:
            typ=m.group(2).strip(); mm=re.match(r'(.*)(\[[^\]]*\])$', typ)
            t,mult=(mm.group(1),mm.group(2)) if mm else (typ,'')
            props[m.group(1)]=(strip_pkg(re.sub(r'\s+','',t)), mult)
    return props
def m3shape(short):
    r=subprocess.run(['python3',LITE+'/tools/m3shape.py',short],capture_output=True,text=True,env={**os.environ,'LEGEND_PURE_ROOT':PURE})
    out=r.stdout.strip()
    if not out or out.startswith('#') or 'Traceback' in r.stderr: return None
    m=re.match(r'Class ([A-Za-z0-9_:]+)(<[^>]*>)?\s*(?:extends\s+(.*?))?\s*\{(.*)\}\s*$', out, re.S)
    if not m: return None
    sups=[strip_pkg(x.strip()) for x in m.group(3).split(',')] if m.group(3) else []
    props={}
    for p in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*):\s*([^;]+);', m.group(4)):
        typ=p.group(2).strip(); mm=re.match(r'(.*)(\[[^\]]*\])$', typ)
        t,mult=(mm.group(1),mm.group(2)) if mm else (typ,'')
        props[p.group(1)]=(strip_pkg(re.sub(r'\s+','',t)), mult)
    return (m.group(2) or '').replace(' ',''), sups, props, 'm3.pure'
rows=[]
for d in decls:
    pd=parse_decl(d)
    if not pd: rows.append(('?', d[:80], 'unparsed')); continue
    fqn,params,sups,props=pd
    spec=spec_text_for(fqn)
    if spec:
        f,sparams,ssups,sbody=spec
        ssups=[strip_pkg(x.strip()) for x in re.split(r',(?![^<]*>)', ssups) if x.strip()]
        sprops=parse_spec_body(sbody); where=f.replace(PURE+'/','pure:').replace(ENG+'/','engine:')
    else:
        m3=m3shape(fqn.split('::')[-1])
        if not m3:
            short=fqn.split('::')[-1]
            if fqn.startswith('meta::pure::metamodel::type::') and short in ('Number','Integer','Float','Decimal','String','Boolean','Byte','Date','StrictDate','DateTime','LatestDate','StrictTime'):
                rows.append((fqn,'m3 PrimitiveType instance', 'EXACT' if not props else 'EXTRA '+','.join(props))); continue
            rows.append((fqn,'m3.pure (tool cannot read: hand-verified this session)','TOOL-GAP')); continue
        sparams,ssups,sprops,where=m3
    diffs=[]
    if params.replace('|',',')!=sparams.replace('|',','): diffs.append(f'params ours{params or "<>"} spec{sparams or "<>"}')
    if not ssups and sups==['Any']: ssups=['Any']
    if set(sups)!=set(ssups): diffs.append(f'extends ours={sups} spec={ssups}')
    missing=[k for k in sprops if k not in props]; extra=[k for k in props if k not in sprops]
    typed=[f'{k}:{props[k][0]}{props[k][1]}≠{sprops[k][0]}{sprops[k][1]}' for k in props if k in sprops and props[k]!=sprops[k]]
    if missing: diffs.append('MISSING '+','.join(missing))
    if extra: diffs.append('EXTRA '+','.join(extra))
    if typed: diffs.append('TYPE '+'; '.join(typed))
    rows.append((fqn, where.split('/')[-1] if where else '', ' | '.join(diffs) if diffs else 'EXACT'))
exact=sum(1 for r in rows if r[2]=='EXACT')
print(f'# hand shapes: {len(rows)}  exact: {exact}  diverging: {len(rows)-exact}')
for fqn,where,d in rows:
    if d!='EXACT': print(f'{fqn}  [{where}]\n    {d}')
