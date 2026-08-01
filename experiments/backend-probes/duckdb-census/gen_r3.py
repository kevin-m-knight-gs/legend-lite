#!/usr/bin/env python3
R=[]
def p(i,c,s):
    assert '\t' not in s and '\n' not in s, i
    R.append((i,c,s))

# ---- GREATEST/LEAST null-skipping construction (must match duckdb/pg)
p('R3.gr1','GREATEST',"SELECT CASE WHEN 1 IS NULL THEN 3 WHEN 3 IS NULL THEN 1 ELSE max(1,3) END")
p('R3.gr2','GREATEST',"SELECT CASE WHEN (CASE WHEN 1 IS NULL THEN NULL WHEN NULL IS NULL THEN 1 ELSE max(1,NULL) END) IS NULL THEN 3 WHEN 3 IS NULL THEN (CASE WHEN 1 IS NULL THEN NULL WHEN NULL IS NULL THEN 1 ELSE max(1,NULL) END) ELSE max((CASE WHEN 1 IS NULL THEN NULL WHEN NULL IS NULL THEN 1 ELSE max(1,NULL) END),3) END")
p('R3.gr3','GREATEST',"SELECT coalesce(max(coalesce(1,3),coalesce(3,1)),1,3)")
p('R3.gr4','GREATEST',"SELECT coalesce(max(coalesce(e.sal,100.0),coalesce(100.0,e.sal)),e.sal,100.0) FROM emp e WHERE e.id=5")
p('R3.le1','LEAST',   "SELECT coalesce(min(coalesce(e.sal,100.0),coalesce(100.0,e.sal)),e.sal,100.0) FROM emp e WHERE e.id=5")
p('R3.le2','LEAST',   "SELECT coalesce(min(coalesce(1,NULL),coalesce(NULL,1)),1,NULL)")
p('R3.gr5','GREATEST',"SELECT coalesce(max(coalesce(NULL,NULL),coalesce(NULL,NULL)),NULL,NULL)")

# ---- SQLite LIKE case sensitivity + ICU
p('R3.ci1','MISC',"PRAGMA case_sensitive_like=ON")
p('R3.ci2','STARTS_WITH',"SELECT 'ABCdef' LIKE 'abc%'")
p('R3.ci3','MISC',"PRAGMA case_sensitive_like=OFF")
p('R3.icu','MISC',"SELECT sqlite_compileoption_used('ENABLE_ICU')")
p('R3.opts','MISC',"SELECT group_concat(sqlite_compileoption_get(0)||';'||sqlite_compileoption_get(1)||';'||sqlite_compileoption_get(2)||';'||sqlite_compileoption_get(3)||';'||sqlite_compileoption_get(4))")

# ---- STARTS_WITH / ENDS_WITH null-correct, case-correct constructions
p('R3.sw1','STARTS_WITH',"SELECT substr('ABCdef',1,length('abc'))='abc'")
p('R3.sw2','STARTS_WITH',"SELECT instr('abcdef','abc')=1")
p('R3.sw3','STARTS_WITH',"SELECT instr('abcdef','')=1")
p('R3.sw4','STARTS_WITH',"SELECT 'abcdef' GLOB 'abc*'")
p('R3.sw5','STARTS_WITH',"SELECT 'ABCdef' GLOB 'abc*'")
p('R3.ew1','ENDS_WITH',"SELECT right('abcdef',length('def'))='def'")
p('R3.ew2','ENDS_WITH',"SELECT right('abc',length(''))='' ")
p('R3.ew3','ENDS_WITH',"SELECT rightstr('abcdef',length('def'))='def'")
p('R3.ew4','ENDS_WITH',"SELECT rightstr('abc',length(''))=''")
p('R3.ew5','ENDS_WITH',"SELECT 'abcdef' LIKE '%'||'def'")
p('R3.ew6','ENDS_WITH',"SELECT 'abcdef' LIKE '%'||'d_f'")
p('R3.ew7','ENDS_WITH',"SELECT right('abcdef',length('D_F'))='D_F'")

# ---- PG: substr with negative/absolute start (duckdb semantics)
p('R3.sub1','SUBSTRING',"SELECT CASE WHEN -3 < 0 THEN right('abcdef', -(-3)) ELSE substr('abcdef',-3) END")
p('R3.sub2','SUBSTRING',"SELECT substr('abcdef', 2, greatest(-1,0)) || '|'")

# ---- PG FORMAT: %d / %f workarounds
p('R3.fmt1','FORMAT',"SELECT format('%s-%s','a',1)")
p('R3.fmt2','FORMAT',"SELECT format('%s', round(3.14159::numeric,2))")
p('R3.fmt3','FORMAT',"SELECT to_char(3.14159,'FM990.99')")
p('R3.fmt4','FORMAT',"SELECT format('%s%%', 50)")
p('R3.fmt5','FORMAT',"SELECT printf('%s-%s','a',1)")

# ---- PG SPLIT_PART / STRPOS edge
p('R3.sp1','SPLIT_PART',"SELECT split_part('a,b,c',',',-1)")
p('R3.sp2','SPLIT_PART',"SELECT split_part('a,b,c','',2) || '|'")

# ---- PG REGEXP_EXTRACT null->'' normalisation and group-0
p('R3.re1','REGEXP_EXTRACT',"SELECT coalesce(regexp_substr('abcdef','[0-9]+'),'') || '|'")
p('R3.re2','REGEXP_EXTRACT',"SELECT coalesce(regexp_substr('abc123','([a-z]+)([0-9]+)'),'')")
p('R3.re3','REGEXP_EXTRACT',"SELECT coalesce(regexp_substr('abc123','([a-z]+)([0-9]+)',1,1,'',2),'')")
p('R3.re4','REGEXP_EXTRACT',"SELECT coalesce(regexp_substr('abcdef','([a-z]+)([0-9]+)',1,1,'',2),'') || '|'")
p('R3.rea1','REGEXP_EXTRACT_ALL',"SELECT ARRAY(SELECT regexp_substr('a1b22c333','[0-9]+',1,g) FROM generate_series(1,regexp_count('a1b22c333','[0-9]+')) g)")

# ---- SQLite GUID: RFC-4122 v4 shaped
guid = ("lower(substr(hex(randomblob(4)),1,8))||'-'||lower(substr(hex(randomblob(2)),1,4))||'-4'||"
        "lower(substr(hex(randomblob(2)),2,3))||'-'||substr('89ab',1+(abs(random())%4),1)||"
        "lower(substr(hex(randomblob(2)),2,3))||'-'||lower(hex(randomblob(6)))")
p('R3.guid1','GUID',"SELECT "+guid)
p('R3.guid2','GUID',"SELECT length("+guid+")")

# ---- SQLite base64 attempt (recursive CTE over a literal)
b64 = ("WITH RECURSIVE src(b) AS (SELECT hex(CAST('hello' AS BLOB))), "
       "n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < (SELECT length(b)/2 FROM src)) "
       "SELECT group_concat(substr(b,2*i-1,2),'') FROM n, src")
p('R3.b64a','ENCODE_BASE64',"SELECT ("+b64+")")
p('R3.b64b','ENCODE_BASE64',"SELECT hex(CAST('hello' AS BLOB))")
p('R3.b64c','ENCODE_BASE64',"SELECT unhex('68656C6C6F')")

# ---- SQLite LEVENSHTEIN attempt via recursive CTE (uncorrelated literal)
lev = ("WITH RECURSIVE s(i,c) AS (SELECT 1, substr('kitten',1,1) UNION ALL SELECT i+1, substr('kitten',i+1,1) FROM s WHERE i<length('kitten')) SELECT count(*) FROM s")
p('R3.lev1','LEVENSHTEIN',"SELECT ("+lev+")")
p('R3.lev2','LEVENSHTEIN',"SELECT ("+lev.replace("SELECT count(*) FROM s","SELECT count(*) FROM s, s AS s2")+")")

# ---- SQLite SPLIT as JSON array (list carrier probe)
p('R3.split1','SPLIT',"SELECT json_array('a','b','c')")
p('R3.split2','SPLIT',"SELECT typeof(json_array('a','b','c'))")
p('R3.split3','SPLIT',"WITH RECURSIVE t(rest,part) AS (SELECT 'a,b,c'||',', NULL UNION ALL SELECT substr(rest,instr(rest,',')+1), substr(rest,1,instr(rest,',')-1) FROM t WHERE rest<>'') SELECT json_group_array(part) FROM t WHERE part IS NOT NULL")

# ---- HASH: is there a stable 64-bit hash anywhere?
p('R3.h1','HASH',"SELECT hashtextextended('abc', 0)")
p('R3.h2','HASH',"SELECT hash_bigint('abc')")
p('R3.h3','HASH',"SELECT ('x'||substr(md5('abc'),1,16))::bit(64)::bigint")
p('R3.h4','HASH',"SELECT CAST(substr(md5('abc'),1,15) AS TEXT)")

# ---- returned type checks
p('R3.t1','TYPE',"SELECT length('abc')")
p('R3.t2','TYPE',"SELECT starts_with('abc','a')")
p('R3.t3','TYPE',"SELECT substr('abcdef',1,3)='abc'")
p('R3.t4','TYPE',"SELECT strpos('abcdef','cd')")
p('R3.t5','TYPE',"SELECT levenshtein('kitten','sitting')")
p('R3.t6','TYPE',"SELECT md5('abc')")

dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-r3.tsv'
open(dest,'w',encoding='utf-8').write(''.join('%s\t%s\t%s\n'%r for r in R))
print('wrote',len(R))
