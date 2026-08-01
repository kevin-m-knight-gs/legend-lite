#!/usr/bin/env python3
R=[]
def p(i,c,s):
    assert '\t' not in s and '\n' not in s, i
    R.append((i,c,s))

ALPH="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

def b64enc(srcexpr):
    """SQLite base64 encode of srcexpr (text) as a scalar subquery."""
    h  = "(SELECT h FROM src)"
    cp = "(substr(%s, 6*(i-1)+1, 6) || '0000')" % h
    dig = lambda k: "(instr('0123456789ABCDEF', substr(%s,%d,1))-1)" % (cp, k)
    v = "(" + "+".join("%s*%d" % (dig(k), 16**(6-k)) for k in range(1,7)) + ")"
    A = lambda sh: "substr('%s', ((%s>>%d)&63)+1, 1)" % (ALPH, v, sh)
    n = "length(substr(%s, 6*(i-1)+1, 6))" % h
    piece = ("%s || %s || CASE WHEN %s>=4 THEN %s ELSE '=' END || CASE WHEN %s=6 THEN %s ELSE '=' END"
             % (A(18), A(12), n, A(6), n, A(0)))
    return ("(WITH RECURSIVE src(h) AS (SELECT hex(CAST(%s AS BLOB))), "
            "g(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM g WHERE 6*i < (SELECT length(h) FROM src)) "
            "SELECT group_concat(%s, '' ORDER BY i) FROM g)" % (srcexpr, piece))

def b64dec(srcexpr):
    """SQLite base64 decode of srcexpr (text) back to TEXT."""
    s = "(SELECT s FROM src)"
    q  = "substr(%s, 4*(i-1)+1, 4)" % s
    dg = lambda k: "(instr('%s', substr(%s,%d,1))-1)" % (ALPH, q, k)
    v  = "(" + "+".join("max(%s,0)*%d" % (dg(k), 64**(4-k)) for k in range(1,5)) + ")"
    H  = lambda sh: "substr('0123456789ABCDEF', ((%s>>%d)&15)+1, 1)" % (v, sh)
    pad= "(length(%s) - length(replace(%s,'=','')))" % (q,q)
    piece = ("%s||%s || CASE WHEN %s<2 THEN %s||%s ELSE '' END || CASE WHEN %s<1 THEN %s||%s ELSE '' END"
             % (H(20),H(16), pad, H(12),H(8), pad, H(4),H(0)))
    return ("(WITH RECURSIVE src(s) AS (SELECT %s), "
            "g(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM g WHERE 4*i < (SELECT length(s) FROM src)) "
            "SELECT CAST(unhex(group_concat(%s, '' ORDER BY i)) AS TEXT) FROM g)" % (srcexpr, piece))

p('R4.enc1','ENCODE_BASE64',"SELECT "+b64enc("'hello'"))
p('R4.enc2','ENCODE_BASE64',"SELECT "+b64enc("'hi'"))
p('R4.enc3','ENCODE_BASE64',"SELECT "+b64enc("'a'"))
p('R4.enc4','ENCODE_BASE64',"SELECT "+b64enc("'héllo'"))
p('R4.enc5','ENCODE_BASE64',"SELECT "+b64enc("'abcdefghijklmnopqrstuvwxyz0123456789'"))
p('R4.enc6','ENCODE_BASE64',"SELECT "+b64enc("e.name")+" FROM emp e WHERE e.id=1")
p('R4.enc7','ENCODE_BASE64',"SELECT "+b64enc("e.name")+" FROM emp e ORDER BY e.id")
p('R4.dec1','DECODE_BASE64',"SELECT "+b64dec("'aGVsbG8='"))
p('R4.dec2','DECODE_BASE64',"SELECT "+b64dec("'aGk='"))
p('R4.dec3','DECODE_BASE64',"SELECT "+b64dec("'YQ=='"))
p('R4.dec4','DECODE_BASE64',"SELECT "+b64dec("'aMOpbGxv'"))

# reference values from duckdb
p('R4.ref1','ENCODE_BASE64',"SELECT to_base64(CAST('hello' AS BLOB))")
p('R4.ref2','ENCODE_BASE64',"SELECT to_base64(CAST('hi' AS BLOB))")
p('R4.ref3','ENCODE_BASE64',"SELECT to_base64(CAST('a' AS BLOB))")
p('R4.ref5','ENCODE_BASE64',"SELECT to_base64(CAST('abcdefghijklmnopqrstuvwxyz0123456789' AS BLOB))")
p('R4.ref6','ENCODE_BASE64',"SELECT to_base64(CAST(e.name AS BLOB)) FROM emp e WHERE e.id=1")

# correlated recursive CTE — decisive capability test
p('R4.corr1','CAP',"SELECT (WITH RECURSIVE r(i,o) AS (SELECT 1,'' UNION ALL SELECT i+1, substr(e.name,i,1)||o FROM r WHERE i<=length(e.name)) SELECT o FROM r ORDER BY i DESC LIMIT 1) FROM emp e WHERE e.id=2")
p('R4.corr2','CAP',"SELECT e.id, (WITH RECURSIVE r(i,n) AS (SELECT 1,0 UNION ALL SELECT i+1,n+1 FROM r WHERE i<=length(e.name)) SELECT max(n) FROM r) FROM emp e ORDER BY e.id")

# levenshtein via recursive DP, correlated
def lev(a,b):
    return ("(WITH RECURSIVE p(a,b) AS (SELECT %s,%s), "
            "d(i,j,v) AS ("
            "  SELECT 0,0,0"
            "  UNION ALL SELECT 0,j+1,j+1 FROM d WHERE i=0 AND j<(SELECT length(b) FROM p)"
            "  UNION ALL SELECT i+1,0,i+1 FROM d WHERE j=0 AND i<(SELECT length(a) FROM p)"
            ") SELECT count(*) FROM d)" % (a,b))
p('R4.lev1','LEVENSHTEIN',"SELECT "+lev("'kitten'","'sitting'"))
p('R4.lev2','LEVENSHTEIN',"SELECT (WITH RECURSIVE d(i,j) AS (SELECT 0,0 UNION ALL SELECT CASE WHEN j<7 THEN i ELSE i+1 END, CASE WHEN j<7 THEN j+1 ELSE 0 END FROM d WHERE i<=6) SELECT count(*) FROM d)")

# md5 groundwork: does sqlite have bitwise ops + integer overflow behaviour?
p('R4.bit1','MD5',"SELECT 5 & 3")
p('R4.bit2','MD5',"SELECT 1 << 31")
p('R4.bit3','MD5',"SELECT (1 << 63)")
p('R4.bit4','MD5',"SELECT (0xFFFFFFFF + 1) & 0xFFFFFFFF")

dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-r4.tsv'
open(dest,'w',encoding='utf-8').write(''.join('%s\t%s\t%s\n'%r for r in R))
print('wrote',len(R))
