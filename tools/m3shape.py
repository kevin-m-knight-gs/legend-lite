import re,sys
src=open('/Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/grammar/m3.pure').read()
def fqn(path):  # Root.children[meta].children[pure]... -> meta::pure::...
    parts=re.findall(r'children\[([A-Za-z0-9_]+)\]', path)
    return '::'.join(parts) if parts else re.sub(r'[^A-Za-z0-9_:]','',path)
MULT={'PureOne':'[1]','ZeroOne':'[0..1]','ZeroMany':'[*]','OneMany':'[1..*]','PureZero':'[0]'}
def block(name):
    m=re.search(r'\^Root\.children\[meta\]\.children\[pure\]\.children\[metamodel\]\.children\[type\]\.children\[Class\] '+name+r' @(\S+)\n\{', src)
    if not m: return None
    start=m.end(); depth=1; i=start
    while depth:
        c=src[i]; depth+= (c=='{') - (c=='}'); i+=1
    return fqn(m.group(1))+'::'+name, src[start:i-1]
def gtype(text):
    # rawType : Root....children[X] ; typeParameter : ^…TypeParameter{name:'T'}
    r=re.search(r'properties\[rawType\]\s*:\s*(\S+?)[,}\n]', text)
    if r:
        base=fqn(r.group(1))
        ta=re.findall(r"properties\[typeArguments\].*?TypeParameter\][^}]*?name\]\s*:\s*'([A-Za-z]+)'", text, re.S)
        return base+('<'+','.join(ta)+'>' if ta else '')
    t=re.search(r"properties\[typeParameter\][^']*'([A-Za-z]+)'", text)
    return t.group(1) if t else '??'
for name in sys.argv[1:]:
    b=block(name)
    if not b: print('# NOT FOUND', name); continue
    f,body=b
    gens=[]
    for g in re.finditer(r'properties\[general\]\s*:\s*\^\S+\s*\{([^\n]*)', body):
        gens.append(gtype(g.group(1)))
    for g in re.finditer(r'properties\[general\]\s*:\s*\^[^\n]*\n\s*\{(.*?)properties\[specific\]', body, re.S):
        v=gtype(g.group(1))
        if v not in gens: gens.append(v)
    tps=re.findall(r"properties\[typeParameters\][^\]]*", body)
    tp=[]
    head=body.split('properties[properties]')[0].split('properties[generalizations]')[0]
    for x in re.findall(r"TypeParameter\]\s*\{[^}]*?name\]\s*:\s*'([A-Za-z]+)'", head):
        if x not in tp: tp.append(x)
    props=[]
    for p in re.finditer(r'children\[property\]\.children\[Property\] ([A-Za-z0-9_]+)\s*\{(.*?)\n\s*\}', body, re.S):
        pn,pb=p.group(1),p.group(2)
        gt=re.search(r'properties\[genericType\]\s*:\s*\^.*?(?=properties\[multiplicity\]|properties\[classifierGenericType\]|properties\[aggregation\]|$)', pb, re.S)
        typ=gtype(gt.group(0)) if gt else '??'
        mu=re.search(r'properties\[multiplicity\]\s*:\s*\S*children\[([A-Za-z]+)\]', pb)
        if mu and mu.group(1)=='Multiplicity':
            lo=re.search(r'lowerBound\][^\n]*?value\]\s*:\s*(\d+)', pb); hi=re.search(r'upperBound\][^\n]*?value\]\s*:\s*(\d+)', pb)
            MULT['Multiplicity']='['+(lo.group(1) if lo else '?')+'..'+(hi.group(1) if hi else '*')+']'
        props.append(f"{pn}: {typ}{MULT.get(mu.group(1),'[?'+(mu.group(1) if mu else '')+']')};")
    print(f"Class {f}{'<'+','.join(tp)+'>' if tp else ''}{' extends '+', '.join(gens) if gens else ''} {{ " + ' '.join(props) + ' }')
