#!/usr/bin/env python3
R=[]
def p(i,c,s):
    assert '\t' not in s and '\n' not in s, i
    R.append((i,c,s))

# PG: regexp_matches as an SRF in the select list DROPS ROWS
p('R6.srf1','MATCHES',"SELECT count(*) FROM emp")
p('R6.srf2','MATCHES',"SELECT count(*) FROM (SELECT e.id, regexp_matches(e.name,'a') FROM emp e) t")
p('R6.srf3','MATCHES',"SELECT count(*) FROM (SELECT e.id, e.name ~ 'a' AS m FROM emp e) t")
p('R6.srf4','MATCHES',"SELECT count(*) FROM (SELECT e.id, regexp_matches(e.name,'[0-9]') FROM emp e) t")

# duckdb '~' is FULL match, pg '~' is PARTIAL
p('R6.tilde1','MATCHES',"SELECT 'abc123' ~ '^[a-z0-9]+$'")
p('R6.tilde2','MATCHES',"SELECT 'abc123' ~ 'bc1'")
p('R6.tilde3','MATCHES',"SELECT 'abc123' !~ 'bc1'")
p('R6.tilde4','MATCHES',"SELECT 'abc123' ~~ 'abc%'")

# PG ENDS_WITH final candidates
p('R6.ew1','ENDS_WITH',"SELECT right('abcdef', length('def')) = 'def'")
p('R6.ew2','ENDS_WITH',"SELECT right(NULL, length('def')) = 'def'")
p('R6.ew3','ENDS_WITH',"SELECT strpos(reverse('abcdef'), reverse('def')) = 1")
p('R6.ew4','ENDS_WITH',"SELECT ends_with('abcdef','def')")

# PG STARTS_WITH null + type
p('R6.sw1','STARTS_WITH',"SELECT starts_with(NULL,'a')")
p('R6.sw2','STARTS_WITH',"SELECT starts_with('abc',NULL)")

# PG levenshtein without the extension (fresh db) then with
p('R6.lev0','LEVENSHTEIN',"SELECT levenshtein('kitten','sitting')")
p('R6.ext','SETUP',"CREATE EXTENSION IF NOT EXISTS fuzzystrmatch")
p('R6.lev1','LEVENSHTEIN',"SELECT levenshtein('kitten','sitting')")
p('R6.lev2','LEVENSHTEIN',"SELECT levenshtein('héllo','hello')")
p('R6.pgc','SETUP',"CREATE EXTENSION IF NOT EXISTS pgcrypto")
p('R6.sha1','SHA1',"SELECT encode(digest('abc','sha1'),'hex')")
p('R6.md5b','MD5',"SELECT md5('abc')")
p('R6.sha256','SHA256',"SELECT encode(sha256('abc'::bytea),'hex')")
p('R6.b64','ENCODE_BASE64',"SELECT replace(encode('hello'::bytea,'base64'), E'\\n','')")
p('R6.b64d','DECODE_BASE64',"SELECT convert_from(decode('aGVsbG8=','base64'),'UTF8')")

# PG hash: any 64-bit equivalent to duckdb hash()?
p('R6.h1','HASH',"SELECT hashtextextended('abc',0)")
p('R6.h2','HASH',"SELECT hashtext('abc')")

# PG jaro
p('R6.jw1','JARO_WINKLER',"SELECT similarity('kitten','sitting')")
p('R6.jw2','JARO_WINKLER',"SELECT daitch_mokotoff('kitten')")

# PG GUID + FORMAT residuals
p('R6.g1','GUID',"SELECT gen_random_uuid()::text")
p('R6.f1','FORMAT',"SELECT format('%s',NULL) || '|'")
p('R6.f2','FORMAT',"SELECT format('%s-%s','a',NULL) || '|'")

# type census
p('R6.t1','TYPE',"SELECT length('abc')")
p('R6.t2','TYPE',"SELECT strpos('abc','b')")
p('R6.t3','TYPE',"SELECT ascii('a')")
p('R6.t4','TYPE',"SELECT levenshtein('ab','b')")
p('R6.t5','TYPE',"SELECT sha256('abc'::bytea)")
p('R6.t6','TYPE',"SELECT gen_random_uuid()")
p('R6.t7','TYPE',"SELECT string_to_array('a,b',',')")
p('R6.t8','TYPE',"SELECT greatest(1.5,2)")

dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-r6.tsv'
open(dest,'w',encoding='utf-8').write(''.join('%s\t%s\t%s\n'%r for r in R))
print('wrote',len(R))
