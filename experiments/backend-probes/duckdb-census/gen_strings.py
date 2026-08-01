#!/usr/bin/env python3
# Generates probes-strings.tsv for the STRING/REGEX/HASH/ENCODING slice.
import io, os

P = []
def p(pid, cat, sql):
    assert '\t' not in sql and '\n' not in sql, pid
    P.append((pid, cat, sql))

# ---------------------------------------------------------------- SETUP / CENSUS
p('SETUP.pg_fuzzystrmatch', 'SETUP', "CREATE EXTENSION IF NOT EXISTS fuzzystrmatch")
p('SETUP.pg_pgcrypto',      'SETUP', "CREATE EXTENSION IF NOT EXISTS pgcrypto")
p('SETUP.pg_trgm',          'SETUP', "CREATE EXTENSION IF NOT EXISTS pg_trgm")
p('CENSUS.version',   'CENSUS', "SELECT version()")
p('CENSUS.sqlitever', 'CENSUS', "SELECT sqlite_version()")
p('CENSUS.fncount',   'CENSUS', "SELECT count(DISTINCT name) FROM pragma_function_list")
for i in range(0, 12):
    p('CENSUS.fnlist%02d' % i, 'CENSUS',
      "SELECT group_concat(n) FROM (SELECT DISTINCT name AS n FROM pragma_function_list ORDER BY name LIMIT 22 OFFSET %d)" % (i*22))
p('CENSUS.mathopt', 'CENSUS', "SELECT sqlite_compileoption_used('ENABLE_MATH_FUNCTIONS')")
p('CENSUS.jsonopt', 'CENSUS', "SELECT sqlite_compileoption_used('ENABLE_JSON1')")
p('CENSUS.loadext', 'CENSUS', "SELECT load_extension('nonexistent_xyz')")
p('CENSUS.pg_str_fns', 'CENSUS',
  "SELECT string_agg(DISTINCT proname, ',') FROM pg_proc WHERE proname IN ('ends_with','starts_with','strpos','levenshtein','jaro_winkler','sha1','sha256','md5','hash','hashtext','uuid','gen_random_uuid','uuidv4','printf','format','regexp_extract','regexp_substr','regexp_like','regexp_count','regexp_instr','regexp_replace','regexp_matches','reverse','repeat','split_part','string_to_array','to_base64','from_base64','encode','decode','initcap','btrim')")

# ---------------------------------------------------------------- CONCAT
p('CONCAT.c1',  'CONCAT', "SELECT concat('a','b','c')")
p('CONCAT.c2',  'CONCAT', "SELECT concat('a',NULL,'c')")
p('CONCAT.c3',  'CONCAT', "SELECT 'a' || NULL || 'c'")
p('CONCAT.c4',  'CONCAT', "SELECT 'a' || 'b' || 'c'")
p('CONCAT.c5',  'CONCAT', "SELECT concat('a', 1, 2.5)")
p('CONCAT.c6',  'CONCAT', "SELECT concat_ws('-','a',NULL,'c')")
p('CONCAT.c7',  'CONCAT', "SELECT concat(NULL,NULL)")
p('CONCAT.c8',  'CONCAT', "SELECT coalesce(e.name,'') || coalesce(CAST(e.dept_id AS VARCHAR),'') FROM emp e WHERE e.id=5")

# ---------------------------------------------------------------- LENGTH
p('LENGTH.c1', 'LENGTH', "SELECT length('hello')")
p('LENGTH.c2', 'LENGTH', "SELECT length('héllo')")
p('LENGTH.c3', 'LENGTH', "SELECT length('日本語')")
p('LENGTH.c4', 'LENGTH', "SELECT length('a\U0001F600b')")
p('LENGTH.c5', 'LENGTH', "SELECT length(NULL)")
p('LENGTH.c6', 'LENGTH', "SELECT length('')")
p('LENGTH.c7', 'LENGTH', "SELECT char_length('héllo')")
p('LENGTH.c8', 'LENGTH', "SELECT length('ab  ')")
p('LENGTH.c9', 'LENGTH', "SELECT length(1234)")
p('LENGTH.c10','LENGTH', "SELECT octet_length('héllo')")

# ---------------------------------------------------------------- UPPER / LOWER
p('UPPER.c1', 'UPPER', "SELECT upper('abc')")
p('UPPER.c2', 'UPPER', "SELECT upper('héllo')")
p('UPPER.c3', 'UPPER', "SELECT upper('ß')")
p('UPPER.c4', 'UPPER', "SELECT upper(NULL)")
p('LOWER.c1', 'LOWER', "SELECT lower('ABC')")
p('LOWER.c2', 'LOWER', "SELECT lower('HÉLLO')")
p('LOWER.c3', 'LOWER', "SELECT lower('İ')")

# ---------------------------------------------------------------- SUBSTRING
p('SUBSTRING.c1', 'SUBSTRING', "SELECT substr('abcdef', 2, 3)")
p('SUBSTRING.c2', 'SUBSTRING', "SELECT substr('abcdef', 2)")
p('SUBSTRING.c3', 'SUBSTRING', "SELECT substr('abcdef', 0, 3)")
p('SUBSTRING.c4', 'SUBSTRING', "SELECT substr('abcdef', -3, 2)")
p('SUBSTRING.c5', 'SUBSTRING', "SELECT substr('abc', 10, 3) || '|'")
p('SUBSTRING.c6', 'SUBSTRING', "SELECT substr('abc', 2, 100)")
p('SUBSTRING.c7', 'SUBSTRING', "SELECT substr('abcdef', 2, -1) || '|'")
p('SUBSTRING.c8', 'SUBSTRING', "SELECT substring('abcdef' from 2 for 3)")
p('SUBSTRING.c9', 'SUBSTRING', "SELECT substring('abcdef', 2, 3)")
p('SUBSTRING.c10','SUBSTRING', "SELECT substr(NULL, 1, 2)")
p('SUBSTRING.c11','SUBSTRING', "SELECT substr('abcdef', 1, 0) || '|'")
p('SUBSTRING.c12','SUBSTRING', "SELECT substr('héllo', 2, 2)")
p('SUBSTRING.c13','SUBSTRING', "SELECT substr('abcdef', -3)")

# ---------------------------------------------------------------- STRPOS
p('STRPOS.c1', 'STRPOS', "SELECT strpos('abcdef','cd')")
p('STRPOS.c2', 'STRPOS', "SELECT strpos('abcdef','zz')")
p('STRPOS.c3', 'STRPOS', "SELECT strpos('abcdef','')")
p('STRPOS.c4', 'STRPOS', "SELECT instr('abcdef','cd')")
p('STRPOS.c5', 'STRPOS', "SELECT instr('abcdef','zz')")
p('STRPOS.c6', 'STRPOS', "SELECT instr('abcdef','')")
p('STRPOS.c7', 'STRPOS', "SELECT position('cd' in 'abcdef')")
p('STRPOS.c8', 'STRPOS', "SELECT strpos(NULL,'a')")
p('STRPOS.c9', 'STRPOS', "SELECT strpos('héllo','llo')")
p('STRPOS.c10','STRPOS', "SELECT instr('héllo','llo')")
p('STRPOS.c11','STRPOS', "SELECT charindex('cd','abcdef')")

# ---------------------------------------------------------------- STARTS_WITH
p('STARTS_WITH.c1', 'STARTS_WITH', "SELECT starts_with('abcdef','abc')")
p('STARTS_WITH.c2', 'STARTS_WITH', "SELECT starts_with('abcdef','xyz')")
p('STARTS_WITH.c3', 'STARTS_WITH', "SELECT 'abcdef' LIKE 'abc%'")
p('STARTS_WITH.c4', 'STARTS_WITH', "SELECT 'ABCdef' LIKE 'abc%'")
p('STARTS_WITH.c5', 'STARTS_WITH', "SELECT substr('abcdef',1,length('abc'))='abc'")
p('STARTS_WITH.c6', 'STARTS_WITH', "SELECT instr('abcdef','abc')=1")
p('STARTS_WITH.c7', 'STARTS_WITH', "SELECT strpos('abcdef','abc')=1")
p('STARTS_WITH.c8', 'STARTS_WITH', "SELECT 'abcdef' GLOB 'abc*'")
p('STARTS_WITH.c9', 'STARTS_WITH', "SELECT starts_with('abc','')")
p('STARTS_WITH.c10','STARTS_WITH', "SELECT substr('abc',1,length(''))=''")
p('STARTS_WITH.c11','STARTS_WITH', "SELECT starts_with(NULL,'a')")
p('STARTS_WITH.c12','STARTS_WITH', "SELECT substr(NULL,1,length('a'))='a'")

# ---------------------------------------------------------------- ENDS_WITH
p('ENDS_WITH.c1', 'ENDS_WITH', "SELECT ends_with('abcdef','def')")
p('ENDS_WITH.c2', 'ENDS_WITH', "SELECT ends_with('abcdef','xyz')")
p('ENDS_WITH.c3', 'ENDS_WITH', "SELECT 'abcdef' LIKE '%def'")
p('ENDS_WITH.c4', 'ENDS_WITH', "SELECT substr('abcdef',-length('def'))='def'")
p('ENDS_WITH.c5', 'ENDS_WITH', "SELECT right('abcdef',length('def'))='def'")
p('ENDS_WITH.c6', 'ENDS_WITH', "SELECT substr('abcdef', length('abcdef')-length('def')+1)='def'")
p('ENDS_WITH.c7', 'ENDS_WITH', "SELECT ends_with('abc','')")
p('ENDS_WITH.c8', 'ENDS_WITH', "SELECT substr('abc',-length(''))=''")
p('ENDS_WITH.c9', 'ENDS_WITH', "SELECT substr('abc', length('abc')-length('')+1)=''")
p('ENDS_WITH.c10','ENDS_WITH', "SELECT 'abcdef' GLOB '*def'")

# ---------------------------------------------------------------- MATCHES (partial regexp -> boolean)
p('MATCHES.c1', 'MATCHES', "SELECT regexp_matches('abc123','[0-9]+')")
p('MATCHES.c2', 'MATCHES', "SELECT regexp_matches('abcdef','[0-9]+')")
p('MATCHES.c3', 'MATCHES', "SELECT 'abc123' ~ '[0-9]+'")
p('MATCHES.c4', 'MATCHES', "SELECT 'abcdef' ~ '[0-9]+'")
p('MATCHES.c5', 'MATCHES', "SELECT regexp_like('abc123','[0-9]+')")
p('MATCHES.c6', 'MATCHES', "SELECT 'abc123' REGEXP '[0-9]+'")
p('MATCHES.c7', 'MATCHES', "SELECT regexp('[0-9]+','abc123')")
p('MATCHES.c8', 'MATCHES', "SELECT regexp_instr('abc123','[0-9]+') > 0")
p('MATCHES.c9', 'MATCHES', "SELECT regexp_count('abc123','[0-9]+') > 0")
p('MATCHES.c10','MATCHES', "SELECT regexp_matches('abc123','[0-9]+') IS NOT NULL")
p('MATCHES.c11','MATCHES', "SELECT (SELECT count(*) FROM regexp_matches('abc123','[0-9]+')) > 0")

# ---------------------------------------------------------------- LEFT / RIGHT
p('LEFT.c1', 'LEFT', "SELECT left('abcdef',3)")
p('LEFT.c2', 'LEFT', "SELECT substr('abcdef',1,3)")
p('LEFT.c3', 'LEFT', "SELECT left('abcdef',-2)")
p('LEFT.c4', 'LEFT', "SELECT left('abc',10)")
p('LEFT.c5', 'LEFT', "SELECT substr('abc',1,10)")
p('LEFT.c6', 'LEFT', "SELECT left('abc',0) || '|'")
p('LEFT.c7', 'LEFT', "SELECT substr('abc',1,0) || '|'")
p('RIGHT.c1', 'RIGHT', "SELECT right('abcdef',3)")
p('RIGHT.c2', 'RIGHT', "SELECT substr('abcdef',-3)")
p('RIGHT.c3', 'RIGHT', "SELECT right('abc',10)")
p('RIGHT.c4', 'RIGHT', "SELECT substr('abc',-10)")
p('RIGHT.c5', 'RIGHT', "SELECT right('abc',0) || '|'")
p('RIGHT.c6', 'RIGHT', "SELECT substr('abc',-0) || '|'")
p('RIGHT.c7', 'RIGHT', "SELECT right('abcdef',-2)")
p('RIGHT.c8', 'RIGHT', "SELECT substr('abc', length('abc')-3+1) || '|'")
p('RIGHT.c9', 'RIGHT', "SELECT substr('abc', max(1, length('abc')-10+1)) || '|'")

# ---------------------------------------------------------------- LPAD / RPAD
p('LPAD.c1', 'LPAD', "SELECT lpad('ab',5,'*')")
p('LPAD.c2', 'LPAD', "SELECT lpad('abcdef',3,'*')")
p('LPAD.c3', 'LPAD', "SELECT lpad('ab',5,' ') || '|'")
p('LPAD.c4', 'LPAD', "SELECT lpad('ab',7,'xy')")
p('LPAD.c5', 'LPAD', "SELECT printf('%5s','ab') || '|'")
p('LPAD.c6', 'LPAD', "SELECT printf('%*s',5,'ab') || '|'")
p('LPAD.c7', 'LPAD', "SELECT substr(replace(hex(zeroblob(5)),'00','*') || 'ab', -5)")
p('LPAD.c8', 'LPAD', "SELECT substr(substr(replace(hex(zeroblob(5)),'00','*'),1,max(0,5-length('ab'))) || 'ab', 1, 5)")
p('LPAD.c9', 'LPAD', "SELECT substr(substr(replace(hex(zeroblob(3)),'00','*'),1,max(0,3-length('abcdef'))) || 'abcdef', 1, 3)")
p('LPAD.c10','LPAD', "SELECT substr(substr(replace(hex(zeroblob(7)),'00','xy'),1,max(0,7-length('ab'))) || 'ab', 1, 7)")
p('LPAD.c11','LPAD', "SELECT lpad('ab',0,'*') || '|'")
p('LPAD.c12','LPAD', "SELECT lpad(NULL,5,'*')")
p('RPAD.c1', 'RPAD', "SELECT rpad('ab',5,'*')")
p('RPAD.c2', 'RPAD', "SELECT rpad('abcdef',3,'*')")
p('RPAD.c3', 'RPAD', "SELECT rpad('ab',7,'xy')")
p('RPAD.c4', 'RPAD', "SELECT printf('%-5s','ab') || '|'")
p('RPAD.c5', 'RPAD', "SELECT substr('ab' || replace(hex(zeroblob(5)),'00','*'), 1, 5)")
p('RPAD.c6', 'RPAD', "SELECT substr('abcdef' || replace(hex(zeroblob(3)),'00','*'), 1, 3)")
p('RPAD.c7', 'RPAD', "SELECT substr('ab' || replace(hex(zeroblob(7)),'00','xy'), 1, 7)")

# ---------------------------------------------------------------- TRIM family
p('TRIM.c1', 'TRIM', "SELECT trim('  ab  ') || '|'")
p('TRIM.c2', 'TRIM', "SELECT trim('xxabxx','x')")
p('TRIM.c3', 'TRIM', "SELECT trim('xyabyx','xy')")
p('TRIM.c4', 'TRIM', "SELECT trim(both 'x' from 'xxabxx')")
p('TRIM.c5', 'TRIM', "SELECT btrim('xxabxx','x')")
p('TRIM.c6', 'TRIM', "SELECT btrim('  ab  ') || '|'")
p('TRIM.c7', 'TRIM', "SELECT trim('x','xxabxx')")
p('TRIM.c8', 'TRIM', "SELECT trim(NULL)")
p('TRIM.c9', 'TRIM', "SELECT trim('\tab\t') || '|'".replace('\t','\\t'))
p('LTRIM.c1','LTRIM', "SELECT ltrim('  ab') || '|'")
p('LTRIM.c2','LTRIM', "SELECT ltrim('xxab','x')")
p('LTRIM.c3','LTRIM', "SELECT ltrim('yxab','xy')")
p('LTRIM.c4','LTRIM', "SELECT trim(leading 'x' from 'xxabxx')")
p('LTRIM.c5','LTRIM', "SELECT ltrim('abxx','x')")
p('RTRIM.c1','RTRIM', "SELECT rtrim('ab  ') || '|'")
p('RTRIM.c2','RTRIM', "SELECT rtrim('abxx','x')")
p('RTRIM.c3','RTRIM', "SELECT rtrim('abxy','xy')")
p('RTRIM.c4','RTRIM', "SELECT trim(trailing 'x' from 'xxabxx')")

# ---------------------------------------------------------------- REPLACE
p('REPLACE.c1','REPLACE', "SELECT replace('abcabc','b','X')")
p('REPLACE.c2','REPLACE', "SELECT replace('abc','','X')")
p('REPLACE.c3','REPLACE', "SELECT replace('abcabc','b','') ")
p('REPLACE.c4','REPLACE', "SELECT replace('abc',NULL,'X')")
p('REPLACE.c5','REPLACE', "SELECT replace('abc','b',NULL)")
p('REPLACE.c6','REPLACE', "SELECT replace('aaa','aa','X')")

# ---------------------------------------------------------------- SPLIT (-> list)
p('SPLIT.c1','SPLIT', "SELECT string_split('a,b,c',',')")
p('SPLIT.c2','SPLIT', "SELECT string_to_array('a,b,c',',')")
p('SPLIT.c3','SPLIT', "SELECT regexp_split_to_array('a,b,c',',')")
p('SPLIT.c4','SPLIT', "SELECT split('a,b,c',',')")
p('SPLIT.c5','SPLIT', "SELECT str_split('a,b,c',',')")
p('SPLIT.c6','SPLIT', "SELECT json_array('a','b','c')")
p('SPLIT.c7','SPLIT', "SELECT json_group_array(value) FROM json_each('[\"a\",\"b\"]')")
p('SPLIT.c8','SPLIT', "SELECT string_split('a,,c',',')")
p('SPLIT.c9','SPLIT', "SELECT string_to_array('a,,c',',')")
p('SPLIT.c10','SPLIT', "SELECT ARRAY['a','b','c']")
p('SPLIT.c11','SPLIT', "SELECT list_value('a','b')")

# ---------------------------------------------------------------- SPLIT_PART
p('SPLIT_PART.c1','SPLIT_PART', "SELECT split_part('a,b,c',',',2)")
p('SPLIT_PART.c2','SPLIT_PART', "SELECT split_part('a,b,c',',',9) || '|'")
p('SPLIT_PART.c3','SPLIT_PART', "SELECT split_part('a,b,c',',',1)")
p('SPLIT_PART.c4','SPLIT_PART', "SELECT split_part('a,b,c',',',0)")
p('SPLIT_PART.c5','SPLIT_PART', "SELECT (string_to_array('a,b,c',','))[2]")
p('SPLIT_PART.c6','SPLIT_PART', "SELECT json_extract('[\"a\",\"b\",\"c\"]','$[1]')")
# SQLite peel construction, n=2 over literal 'a,b,c' delim ','
peel2 = "substr('a,b,c', instr('a,b,c',',')+1)"
p('SPLIT_PART.c7','SPLIT_PART',
  "SELECT CASE WHEN instr(%s,',')=0 THEN %s ELSE substr(%s,1,instr(%s,',')-1) END" % (peel2,peel2,peel2,peel2))
# SQLite peel with delimiter padding so out-of-range n yields ''
padded = "('a,b,c' || replace(hex(zeroblob(9)),'00',','))"
peeled = padded
for _ in range(8):
    peeled = "substr(%s, instr(%s,',')+1)" % (peeled, peeled)
p('SPLIT_PART.c8','SPLIT_PART',
  "SELECT substr(%s,1,instr(%s,',')-1) || '|'" % (peeled, peeled))
padded3 = "('a,b,c' || replace(hex(zeroblob(2)),'00',','))"
peel3 = padded3
for _ in range(1):
    peel3 = "substr(%s, instr(%s,',')+1)" % (peel3, peel3)
p('SPLIT_PART.c9','SPLIT_PART',
  "SELECT substr(%s,1,instr(%s,',')-1)" % (peel3, peel3))

# ---------------------------------------------------------------- REVERSE_STRING
p('REVERSE.c1','REVERSE_STRING', "SELECT reverse('abc')")
p('REVERSE.c2','REVERSE_STRING', "SELECT reverse('héllo')")
p('REVERSE.c3','REVERSE_STRING', "SELECT reverse_string('abc')")
p('REVERSE.c4','REVERSE_STRING',
  "WITH RECURSIVE r(i,o) AS (SELECT 1, '' UNION ALL SELECT i+1, substr('abc',i,1) || o FROM r WHERE i<=length('abc')) SELECT o FROM r ORDER BY i DESC LIMIT 1")
p('REVERSE.c5','REVERSE_STRING',
  "SELECT (WITH RECURSIVE r(i,o) AS (SELECT 1, '' UNION ALL SELECT i+1, substr('abc',i,1) || o FROM r WHERE i<=length('abc')) SELECT o FROM r ORDER BY i DESC LIMIT 1)")
p('REVERSE.c6','REVERSE_STRING',
  "SELECT (WITH RECURSIVE r(i,o) AS (SELECT 1, '' UNION ALL SELECT i+1, substr(e.name,i,1) || o FROM r WHERE i<=length(e.name)) SELECT o FROM r ORDER BY i DESC LIMIT 1) FROM emp e WHERE e.id=1")

# ---------------------------------------------------------------- ASCII_CODE / CHR
p('ASCII_CODE.c1','ASCII_CODE', "SELECT ascii('A')")
p('ASCII_CODE.c2','ASCII_CODE', "SELECT unicode('A')")
p('ASCII_CODE.c3','ASCII_CODE', "SELECT ascii('abc')")
p('ASCII_CODE.c4','ASCII_CODE', "SELECT unicode('abc')")
p('ASCII_CODE.c5','ASCII_CODE', "SELECT ascii('')")
p('ASCII_CODE.c6','ASCII_CODE', "SELECT unicode('')")
p('ASCII_CODE.c7','ASCII_CODE', "SELECT ascii('é')")
p('ASCII_CODE.c8','ASCII_CODE', "SELECT unicode('é')")
p('CHR.c1','CHR', "SELECT chr(65)")
p('CHR.c2','CHR', "SELECT char(65)")
p('CHR.c3','CHR', "SELECT chr(233)")
p('CHR.c4','CHR', "SELECT char(233)")
p('CHR.c5','CHR', "SELECT chr(0) || '|'")
p('CHR.c6','CHR', "SELECT char(0) || '|'")
p('CHR.c7','CHR', "SELECT chr(128512)")
p('CHR.c8','CHR', "SELECT char(128512)")

# ---------------------------------------------------------------- UC_FIRST / LC_FIRST
p('UC_FIRST.c1','UC_FIRST', "SELECT upper(substr('hello',1,1)) || substr('hello',2)")
p('UC_FIRST.c2','UC_FIRST', "SELECT initcap('hello world')")
p('UC_FIRST.c3','UC_FIRST', "SELECT upper(substr('hello world',1,1)) || substr('hello world',2)")
p('UC_FIRST.c4','UC_FIRST', "SELECT upper(substr('',1,1)) || substr('',2) || '|'")
p('UC_FIRST.c5','UC_FIRST', "SELECT upper(substr(NULL,1,1)) || substr(NULL,2)")
p('UC_FIRST.c6','UC_FIRST', "SELECT upper(substr('école',1,1)) || substr('école',2)")
p('LC_FIRST.c1','LC_FIRST', "SELECT lower(substr('HELLO',1,1)) || substr('HELLO',2)")
p('LC_FIRST.c2','LC_FIRST', "SELECT lower(substr('ÉCOLE',1,1)) || substr('ÉCOLE',2)")

# ---------------------------------------------------------------- BASE64
p('ENCODE_BASE64.c1','ENCODE_BASE64', "SELECT to_base64(CAST('hello' AS BLOB))")
p('ENCODE_BASE64.c2','ENCODE_BASE64', "SELECT base64(CAST('hello' AS BLOB))")
p('ENCODE_BASE64.c3','ENCODE_BASE64', "SELECT encode(CAST('hello' AS bytea),'base64')")
p('ENCODE_BASE64.c4','ENCODE_BASE64', "SELECT to_base64('hello')")
p('ENCODE_BASE64.c5','ENCODE_BASE64', "SELECT base64('hello')")
p('ENCODE_BASE64.c6','ENCODE_BASE64', "SELECT to_base64(CAST(repeat('a',100) AS BLOB))")
p('ENCODE_BASE64.c7','ENCODE_BASE64', "SELECT encode(CAST(repeat('a',100) AS bytea),'base64')")
p('ENCODE_BASE64.c8','ENCODE_BASE64', "SELECT replace(encode(CAST(repeat('a',100) AS bytea),'base64'), chr(10), '')")
p('ENCODE_BASE64.c9','ENCODE_BASE64', "SELECT encode(CAST('héllo' AS bytea),'base64')")
p('ENCODE_BASE64.c10','ENCODE_BASE64', "SELECT to_base64(CAST('héllo' AS BLOB))")
p('DECODE_BASE64.c1','DECODE_BASE64', "SELECT CAST(from_base64('aGVsbG8=') AS VARCHAR)")
p('DECODE_BASE64.c2','DECODE_BASE64', "SELECT convert_from(decode('aGVsbG8=','base64'),'UTF8')")
p('DECODE_BASE64.c3','DECODE_BASE64', "SELECT CAST(decode('aGVsbG8=','base64') AS text)")
p('DECODE_BASE64.c4','DECODE_BASE64', "SELECT from_base64('aGVsbG8=')")
p('DECODE_BASE64.c5','DECODE_BASE64', "SELECT unbase64('aGVsbG8=')")
p('DECODE_BASE64.c6','DECODE_BASE64', "SELECT base64_decode('aGVsbG8=')")
p('DECODE_BASE64.c7','DECODE_BASE64', "SELECT CAST(CAST(from_base64('aGVsbG8=') AS BLOB) AS VARCHAR)")

# ---------------------------------------------------------------- LEVENSHTEIN / JARO_WINKLER
p('LEVENSHTEIN.c1','LEVENSHTEIN', "SELECT levenshtein('kitten','sitting')")
p('LEVENSHTEIN.c2','LEVENSHTEIN', "SELECT editdist3('kitten','sitting')")
p('LEVENSHTEIN.c3','LEVENSHTEIN', "SELECT edit_distance('kitten','sitting')")
p('LEVENSHTEIN.c4','LEVENSHTEIN', "SELECT levenshtein_less_equal('kitten','sitting',10)")
p('LEVENSHTEIN.c5','LEVENSHTEIN', "SELECT difference('kitten','sitting')")
p('LEVENSHTEIN.c6','LEVENSHTEIN', "SELECT damerau_levenshtein('kitten','sitting')")
p('JARO_WINKLER.c1','JARO_WINKLER', "SELECT jaro_winkler_similarity('kitten','sitting')")
p('JARO_WINKLER.c2','JARO_WINKLER', "SELECT jaro_winkler('kitten','sitting')")
p('JARO_WINKLER.c3','JARO_WINKLER', "SELECT jarowinkler('kitten','sitting')")
p('JARO_WINKLER.c4','JARO_WINKLER', "SELECT similarity('kitten','sitting')")
p('JARO_WINKLER.c5','JARO_WINKLER', "SELECT jaro_similarity('kitten','sitting')")

# ---------------------------------------------------------------- GUID
p('GUID.c1','GUID', "SELECT uuid()")
p('GUID.c2','GUID', "SELECT gen_random_uuid()")
p('GUID.c3','GUID', "SELECT uuidv4()")
p('GUID.c4','GUID', "SELECT uuid_generate_v4()")
p('GUID.c5','GUID', "SELECT lower(hex(randomblob(16)))")
p('GUID.c6','GUID', "SELECT lower(substr(hex(randomblob(16)),1,8)) || '-' || lower(substr(hex(randomblob(16)),1,4))")
p('GUID.c7','GUID', "SELECT CAST(uuid() AS VARCHAR)")
p('GUID.c8','GUID', "SELECT CAST(gen_random_uuid() AS text)")
p('GUID.c9','GUID', "SELECT random()")

# ---------------------------------------------------------------- FORMAT (printf)
p('FORMAT.c1','FORMAT', "SELECT printf('%s-%d','a',1)")
p('FORMAT.c2','FORMAT', "SELECT format('%s-%s','a','1')")
p('FORMAT.c3','FORMAT', "SELECT format('%s-%d','a',1)")
p('FORMAT.c4','FORMAT', "SELECT printf('%.2f', 3.14159)")
p('FORMAT.c5','FORMAT', "SELECT format('%.2f', 3.14159)")
p('FORMAT.c6','FORMAT', "SELECT printf('%s and %s','a','b')")
p('FORMAT.c7','FORMAT', "SELECT format('%s and %s','a','b')")
p('FORMAT.c8','FORMAT', "SELECT printf('%d%%', 50)")
p('FORMAT.c9','FORMAT', "SELECT format('%1$s-%2$s','a','b')")
p('FORMAT.c10','FORMAT', "SELECT printf('%s', NULL) || '|'")
p('FORMAT.c11','FORMAT', "SELECT format('%s', NULL) || '|'")
p('FORMAT.c12','FORMAT', "SELECT printf('%s', 3.5)")
p('FORMAT.c13','FORMAT', "SELECT format('%s', 3.5)")

# ---------------------------------------------------------------- HASH
p('HASH.c1','HASH', "SELECT hash('abc')")
p('HASH.c2','HASH', "SELECT hashtext('abc')")
p('HASH.c3','HASH', "SELECT hash_record('abc')")
p('HASH.c4','HASH', "SELECT hash(123)")
p('HASH.c5','HASH', "SELECT hashtext(CAST(123 AS text))")
p('HASH.c6','HASH', "SELECT ('x' || substr(md5('abc'),1,16))::bit(64)::bigint")

# ---------------------------------------------------------------- MD5 / SHA1 / SHA256
p('MD5.c1','MD5', "SELECT md5('abc')")
p('MD5.c2','MD5', "SELECT md5('')")
p('MD5.c3','MD5', "SELECT md5(CAST('abc' AS bytea))")
p('MD5.c4','MD5', "SELECT encode(digest('abc','md5'),'hex')")
p('MD5.c5','MD5', "SELECT lower(hex(md5('abc')))")
p('MD5.c6','MD5', "SELECT md5(NULL)")
p('SHA1.c1','SHA1', "SELECT sha1('abc')")
p('SHA1.c2','SHA1', "SELECT encode(digest('abc','sha1'),'hex')")
p('SHA1.c3','SHA1', "SELECT encode(sha1(CAST('abc' AS bytea)),'hex')")
p('SHA1.c4','SHA1', "SELECT lower(hex(sha1('abc')))")
p('SHA1.c5','SHA1', "SELECT sha1(CAST('abc' AS bytea))")
p('SHA256.c1','SHA256', "SELECT sha256('abc')")
p('SHA256.c2','SHA256', "SELECT encode(sha256(CAST('abc' AS bytea)),'hex')")
p('SHA256.c3','SHA256', "SELECT sha256(CAST('abc' AS bytea))")
p('SHA256.c4','SHA256', "SELECT encode(digest('abc','sha256'),'hex')")
p('SHA256.c5','SHA256', "SELECT lower(hex(sha3('abc',256)))")
p('SHA256.c6','SHA256', "SELECT sha3('abc',256)")
p('SHA256.c7','SHA256', "SELECT sha3_query('abc')")

# ---------------------------------------------------------------- REPEAT_STR
p('REPEAT_STR.c1','REPEAT_STR', "SELECT repeat('ab',3)")
p('REPEAT_STR.c2','REPEAT_STR', "SELECT replace(hex(zeroblob(3)),'00','ab')")
p('REPEAT_STR.c3','REPEAT_STR', "SELECT repeat('ab',0) || '|'")
p('REPEAT_STR.c4','REPEAT_STR', "SELECT replace(hex(zeroblob(0)),'00','ab') || '|'")
p('REPEAT_STR.c5','REPEAT_STR', "SELECT repeat('ab',-1) || '|'")
p('REPEAT_STR.c6','REPEAT_STR', "SELECT replace(hex(zeroblob(-1)),'00','ab') || '|'")
p('REPEAT_STR.c7','REPEAT_STR', "SELECT repeat(NULL,3)")
p('REPEAT_STR.c8','REPEAT_STR', "SELECT rpad('', 3*length('ab'), 'ab')")

# ---------------------------------------------------------------- REGEXP_EXTRACT
p('REGEXP_EXTRACT.c1','REGEXP_EXTRACT', "SELECT regexp_extract('abc123','[0-9]+')")
p('REGEXP_EXTRACT.c2','REGEXP_EXTRACT', "SELECT regexp_extract('abc123','([a-z]+)([0-9]+)',2)")
p('REGEXP_EXTRACT.c3','REGEXP_EXTRACT', "SELECT substring('abc123' from '[0-9]+')")
p('REGEXP_EXTRACT.c4','REGEXP_EXTRACT', "SELECT (regexp_match('abc123','[0-9]+'))[1]")
p('REGEXP_EXTRACT.c5','REGEXP_EXTRACT', "SELECT regexp_substr('abc123','[0-9]+')")
p('REGEXP_EXTRACT.c6','REGEXP_EXTRACT', "SELECT (regexp_match('abc123','([a-z]+)([0-9]+)'))[2]")
p('REGEXP_EXTRACT.c7','REGEXP_EXTRACT', "SELECT regexp_extract('abcdef','[0-9]+') || '|'")
p('REGEXP_EXTRACT.c8','REGEXP_EXTRACT', "SELECT regexp_substr('abcdef','[0-9]+') || '|'")
p('REGEXP_EXTRACT.c9','REGEXP_EXTRACT', "SELECT (regexp_match('abcdef','[0-9]+'))[1] || '|'")
p('REGEXP_EXTRACT.c10','REGEXP_EXTRACT', "SELECT substring('abcdef' from '[0-9]+') || '|'")
p('REGEXP_EXTRACT.c11','REGEXP_EXTRACT', "SELECT coalesce(regexp_substr('abcdef','[0-9]+'),'') || '|'")
p('REGEXP_EXTRACT.c12','REGEXP_EXTRACT', "SELECT regexp_substr('abc123','([a-z]+)([0-9]+)',1,1,'',2)")
p('REGEXP_EXTRACT.c13','REGEXP_EXTRACT', "SELECT regexp_extract('abc123','([a-z]+)([0-9]+)')")

# ---------------------------------------------------------------- REGEXP_EXTRACT_ALL
p('REGEXP_EXTRACT_ALL.c1','REGEXP_EXTRACT_ALL', "SELECT regexp_extract_all('a1b22c333','[0-9]+')")
p('REGEXP_EXTRACT_ALL.c2','REGEXP_EXTRACT_ALL', "SELECT ARRAY(SELECT (regexp_matches('a1b22c333','[0-9]+','g'))[1])")
p('REGEXP_EXTRACT_ALL.c3','REGEXP_EXTRACT_ALL', "SELECT (SELECT array_agg(m[1]) FROM regexp_matches('a1b22c333','([0-9]+)','g') AS m)")
p('REGEXP_EXTRACT_ALL.c4','REGEXP_EXTRACT_ALL', "SELECT regexp_extract_all('abcdef','[0-9]+')")
p('REGEXP_EXTRACT_ALL.c5','REGEXP_EXTRACT_ALL', "SELECT ARRAY(SELECT (regexp_matches('abcdef','[0-9]+','g'))[1])")
p('REGEXP_EXTRACT_ALL.c6','REGEXP_EXTRACT_ALL', "SELECT regexp_matches('a1b22c333','[0-9]+','g')")

# ---------------------------------------------------------------- REGEXP_REPLACE
p('REGEXP_REPLACE.c1','REGEXP_REPLACE', "SELECT regexp_replace('a1b2','[0-9]','X')")
p('REGEXP_REPLACE.c2','REGEXP_REPLACE', "SELECT regexp_replace('a1b2','[0-9]','X','g')")
p('REGEXP_REPLACE.c3','REGEXP_REPLACE', "SELECT regexp_replace('abc','(a)(b)','\\2\\1')")
p('REGEXP_REPLACE.c4','REGEXP_REPLACE', "SELECT regexp_replace('abc','(a)(b)','$2$1')")
p('REGEXP_REPLACE.c5','REGEXP_REPLACE', "SELECT regexp_replace('aAbB','[a-z]','X','gi')")
p('REGEXP_REPLACE.c6','REGEXP_REPLACE', "SELECT regexp_replace(NULL,'a','X')")

# ---------------------------------------------------------------- REGEXP_FULL_MATCH
p('REGEXP_FULL_MATCH.c1','REGEXP_FULL_MATCH', "SELECT regexp_full_match('abc','a.c')")
p('REGEXP_FULL_MATCH.c2','REGEXP_FULL_MATCH', "SELECT regexp_full_match('abcd','a.c')")
p('REGEXP_FULL_MATCH.c3','REGEXP_FULL_MATCH', "SELECT 'abc' ~ '^(a.c)$'")
p('REGEXP_FULL_MATCH.c4','REGEXP_FULL_MATCH', "SELECT 'abcd' ~ '^(a.c)$'")
p('REGEXP_FULL_MATCH.c5','REGEXP_FULL_MATCH', "SELECT regexp_like('abc','^(a.c)$')")
p('REGEXP_FULL_MATCH.c6','REGEXP_FULL_MATCH', "SELECT regexp_like('abcd','^(a.c)$')")
p('REGEXP_FULL_MATCH.c7','REGEXP_FULL_MATCH', "SELECT 'ab\\nc' ~ '^(a.*c)$'")
p('REGEXP_FULL_MATCH.c8','REGEXP_FULL_MATCH', "SELECT regexp_full_match(NULL,'a')")
p('REGEXP_FULL_MATCH.c9','REGEXP_FULL_MATCH', "SELECT 'abc' REGEXP '^(a.c)$'")

# ---------------------------------------------------------------- regex dialect syntax
p('RXSYN.digit',   'RXSYN', "SELECT regexp_full_match('1','\\d')")
p('RXSYN.digit2',  'RXSYN', "SELECT '1' ~ '^(\\d)$'")
p('RXSYN.posix',   'RXSYN', "SELECT regexp_full_match('1','[[:digit:]]')")
p('RXSYN.posix2',  'RXSYN', "SELECT '1' ~ '^([[:digit:]])$'")
p('RXSYN.backref', 'RXSYN', "SELECT regexp_full_match('aa','(a)\\1')")
p('RXSYN.backref2','RXSYN', "SELECT 'aa' ~ '^((a)\\2)$'")
p('RXSYN.lookahd', 'RXSYN', "SELECT regexp_full_match('ab','(?=a)ab')")
p('RXSYN.lookahd2','RXSYN', "SELECT 'ab' ~ '(?=a)ab'")
p('RXSYN.lazy',    'RXSYN', "SELECT regexp_extract('aXbXc','a.*?X')")
p('RXSYN.lazy2',   'RXSYN', "SELECT substring('aXbXc' from 'a.*?X')")
p('RXSYN.brace',   'RXSYN', "SELECT regexp_full_match('aaa','a{3}')")
p('RXSYN.brace2',  'RXSYN', "SELECT 'aaa' ~ '^(a{3})$'")

# ---------------------------------------------------------------- GREATEST / LEAST
p('GREATEST.c1','GREATEST', "SELECT greatest(1,2,3)")
p('GREATEST.c2','GREATEST', "SELECT greatest(1,NULL,3)")
p('GREATEST.c3','GREATEST', "SELECT max(1,NULL,3)")
p('GREATEST.c4','GREATEST', "SELECT max(1,2,3)")
p('GREATEST.c5','GREATEST', "SELECT greatest('a','b')")
p('GREATEST.c6','GREATEST', "SELECT max('a','b')")
p('GREATEST.c7','GREATEST', "SELECT greatest(1.5, 2)")
p('GREATEST.c8','GREATEST', "SELECT greatest(NULL,NULL)")
p('GREATEST.c9','GREATEST', "SELECT max(NULL,NULL)")
p('GREATEST.c10','GREATEST', "SELECT CASE WHEN 1 IS NULL OR 3 IS NULL THEN NULL ELSE max(1,3) END")
p('GREATEST.c11','GREATEST', "SELECT greatest(e.sal, 100.0) FROM emp e WHERE e.id=5")
p('GREATEST.c12','GREATEST', "SELECT max(e.sal, 100.0) FROM emp e WHERE e.id=5")
p('LEAST.c1','LEAST', "SELECT least(1,2,3)")
p('LEAST.c2','LEAST', "SELECT least(1,NULL,3)")
p('LEAST.c3','LEAST', "SELECT min(1,NULL,3)")
p('LEAST.c4','LEAST', "SELECT min(1,2,3)")
p('LEAST.c5','LEAST', "SELECT least('a','b')")
p('LEAST.c6','LEAST', "SELECT least(NULL,NULL)")
p('LEAST.c7','LEAST', "SELECT least(e.sal, 100.0) FROM emp e WHERE e.id=5")
p('LEAST.c8','LEAST', "SELECT min(e.sal, 100.0) FROM emp e WHERE e.id=5")

# ---------------------------------------------------------------- misc column-level sanity
p('COL.upper','COL', "SELECT upper(e.name) FROM emp e WHERE e.id=1")
p('COL.concat','COL', "SELECT concat(e.name,'-',d.name) FROM emp e JOIN dept d ON d.id=e.dept_id WHERE e.id=1")
p('COL.length','COL', "SELECT length(e.name) FROM emp e WHERE e.id=1")

out = io.StringIO()
for pid, cat, sql in P:
    out.write('%s\t%s\t%s\n' % (pid, cat, sql))
dest = '/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings.tsv'
with open(dest, 'w', encoding='utf-8') as f:
    f.write(out.getvalue())
print('wrote %d probes to %s' % (len(P), dest))
