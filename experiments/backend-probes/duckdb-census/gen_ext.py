#!/usr/bin/env python3
R=[]
def p(i,c,s):
    assert '\t' not in s and '\n' not in s, i
    R.append((i,c,s))

# --- leftstr / rightstr
p('X.leftstr1','LEFT',"SELECT leftstr('abcdef',3)")
p('X.leftstr2','LEFT',"SELECT leftstr('abc',10)")
p('X.leftstr3','LEFT',"SELECT leftstr('abcdef',-2) || '|'")
p('X.leftstr4','LEFT',"SELECT leftstr('abc',0) || '|'")
p('X.leftstr5','LEFT',"SELECT leftstr('日本語abc',2)")
p('X.leftstr6','LEFT',"SELECT leftstr(NULL,2)")
p('X.rightstr1','RIGHT',"SELECT rightstr('abcdef',3)")
p('X.rightstr2','RIGHT',"SELECT rightstr('abc',10)")
p('X.rightstr3','RIGHT',"SELECT rightstr('abc',0) || '|'")
p('X.rightstr4','RIGHT',"SELECT rightstr('abcdef',-2) || '|'")
p('X.rightstr5','RIGHT',"SELECT rightstr('abc日本語',2)")
p('X.rightstr6','RIGHT',"SELECT rightstr(NULL,2)")

# --- padl / padr / padc
p('X.padl1','LPAD',"SELECT padl('ab',5) || '|'")
p('X.padl2','LPAD',"SELECT padl('abcdef',3) || '|'")
p('X.padl3','LPAD',"SELECT padl('ab',5,'*')")
p('X.padl4','LPAD',"SELECT padl('日本',5) || '|'")
p('X.padl5','LPAD',"SELECT padl(NULL,5)")
p('X.padr1','RPAD',"SELECT padr('ab',5) || '|'")
p('X.padr2','RPAD',"SELECT padr('abcdef',3) || '|'")
p('X.padr3','RPAD',"SELECT padr('ab',5,'*')")
p('X.padc1','RPAD',"SELECT padc('ab',6) || '|'")

# --- replicate
p('X.repl1','REPEAT_STR',"SELECT replicate('ab',3)")
p('X.repl2','REPEAT_STR',"SELECT replicate('ab',0) || '|'")
p('X.repl3','REPEAT_STR',"SELECT replicate('ab',-1) || '|'")
p('X.repl4','REPEAT_STR',"SELECT replicate(NULL,3)")
p('X.repl5','REPEAT_STR',"SELECT length(replicate('日',3))")

# --- reverse
p('X.rev1','REVERSE_STRING',"SELECT reverse('abc')")
p('X.rev2','REVERSE_STRING',"SELECT reverse('héllo')")
p('X.rev3','REVERSE_STRING',"SELECT reverse('日本語')")
p('X.rev4','REVERSE_STRING',"SELECT reverse(NULL)")
p('X.rev5','REVERSE_STRING',"SELECT reverse('') || '|'")
p('X.rev6','REVERSE_STRING',"SELECT reverse(e.name) FROM emp e WHERE e.id=1")

# --- charindex (arg order!)
p('X.ci1','STRPOS',"SELECT charindex('cd','abcdef')")
p('X.ci2','STRPOS',"SELECT charindex('abcdef','cd')")
p('X.ci3','STRPOS',"SELECT charindex('zz','abcdef')")
p('X.ci4','STRPOS',"SELECT charindex('','abcdef')")
p('X.ci5','STRPOS',"SELECT charindex('c','abcabc',4)")
p('X.ci6','STRPOS',"SELECT charindex('llo','héllo')")

# --- proper (UC_FIRST candidate)
p('X.prop1','UC_FIRST',"SELECT proper('hello')")
p('X.prop2','UC_FIRST',"SELECT proper('hello world')")
p('X.prop3','UC_FIRST',"SELECT proper('HELLO WORLD')")
p('X.prop4','UC_FIRST',"SELECT proper('éa')")

# --- strfilter / difference
p('X.sf1','MISC',"SELECT strfilter('abcdef','ace')")
p('X.diff1','LEVENSHTEIN',"SELECT difference('kitten','sitting')")

# --- unhex / hex round trip (base64/hash groundwork)
p('X.hex1','MISC',"SELECT hex('abc')")
p('X.hex2','MISC',"SELECT unhex('616263')")
p('X.hex3','MISC',"SELECT typeof(unhex('616263'))")
p('X.hex4','MISC',"SELECT CAST(unhex('616263') AS TEXT)")
p('X.hex5','MISC',"SELECT hex(CAST('héllo' AS BLOB))")

# --- does the driver expose a REGEXP hook at all?
p('X.rx1','MATCHES',"SELECT 1 WHERE 'abc123' REGEXP '[0-9]+'")
p('X.rx2','MATCHES',"SELECT regexp('[0-9]+','abc123')")
p('X.rx3','MATCHES',"SELECT count(*) FROM pragma_function_list WHERE name='regexp'")
p('X.rx4','MATCHES',"SELECT count(*) FROM pragma_function_list WHERE name IN ('md5','sha1','sha256','sha3','base64','uuid','levenshtein','editdist3','split_part','ascii','chr','lpad','rpad','left','right','repeat','starts_with','ends_with','initcap','greatest','least','regexp')")
p('X.rx5','MATCHES',"SELECT sqlite_compileoption_used('ENABLE_SOUNDEX')")
p('X.rx6','MATCHES',"SELECT group_concat(name) FROM pragma_function_list WHERE narg=3")

# --- scalar max/min NULL semantics on sqlite (GREATEST/LEAST)
p('X.gr1','GREATEST',"SELECT max(1,NULL,3)")
p('X.gr2','GREATEST',"SELECT typeof(max(1,NULL,3))")
p('X.gr3','GREATEST',"SELECT max('a',2)")
p('X.gr4','GREATEST',"SELECT min('a',2)")
p('X.gr5','GREATEST',"SELECT max(1,2.5)")

dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-ext.tsv'
open(dest,'w',encoding='utf-8').write(''.join('%s\t%s\t%s\n'%r for r in R))
print('wrote',len(R))
