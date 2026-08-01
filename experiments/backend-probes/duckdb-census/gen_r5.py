#!/usr/bin/env python3
R=[]
def p(i,c,s):
    assert '\t' not in s and '\n' not in s, i
    R.append((i,c,s))

W=4
def pad(e):  # right-align e (an int expr) into W chars
    return "substr('0000', 1, %d-length(CAST(%s AS TEXT))) || CAST(%s AS TEXT)" % (W,e,e)

def lev(aexpr, bexpr):
    n  = "(SELECT length(b) FROM p)"
    m  = "(SELECT length(a) FROM p)"
    i  = "(k/(%s+1)+1)" % n
    j  = "(k %% (%s+1))" % n
    val= lambda x,y: "CAST(substr(vec, ((%s)*(%s+1)+(%s))*%d+1, %d) AS INTEGER)" % (x,n,y,W,W)
    cost = ("CASE WHEN substr((SELECT a FROM p),%s,1)=substr((SELECT b FROM p),%s,1) THEN 0 ELSE 1 END"
            % (i,j))
    cell = ("CASE WHEN %s=0 THEN %s ELSE min(%s+1, %s+1, %s+%s) END"
            % (j, i, val("%s-1"%i, j), val(i, "%s-1"%j), val("%s-1"%i, "%s-1"%j), cost))
    return ("(WITH RECURSIVE p(a,b) AS (SELECT %s,%s), "
            "r0(j,vec) AS (SELECT 0, %s UNION ALL SELECT j+1, vec || (%s) FROM r0 WHERE j < %s), "
            "d(k,vec) AS (SELECT 0, (SELECT vec FROM r0 ORDER BY j DESC LIMIT 1) "
            " UNION ALL SELECT k+1, vec || (%s) FROM d WHERE k < %s*(%s+1)) "
            "SELECT CAST(substr(vec,-%d) AS INTEGER) FROM d ORDER BY k DESC LIMIT 1)"
            % (aexpr, bexpr, pad('0'), pad('j+1'), n, pad('('+cell+')'), m, n, W))

p('R5.lev1','LEVENSHTEIN',"SELECT "+lev("'kitten'","'sitting'"))
p('R5.lev2','LEVENSHTEIN',"SELECT "+lev("'abc'","'abc'"))
p('R5.lev3','LEVENSHTEIN',"SELECT "+lev("'abc'","''"))
p('R5.lev4','LEVENSHTEIN',"SELECT "+lev("''","'xyz'"))
p('R5.lev5','LEVENSHTEIN',"SELECT "+lev("'flaw'","'lawn'"))
p('R5.lev6','LEVENSHTEIN',"SELECT "+lev("e.name","'alice'")+" FROM emp e WHERE e.id=2")
p('R5.ref1','LEVENSHTEIN',"SELECT levenshtein('kitten','sitting')")
p('R5.ref2','LEVENSHTEIN',"SELECT levenshtein('abc','abc')")
p('R5.ref3','LEVENSHTEIN',"SELECT levenshtein('abc','')")
p('R5.ref4','LEVENSHTEIN',"SELECT levenshtein('','xyz')")
p('R5.ref5','LEVENSHTEIN',"SELECT levenshtein('flaw','lawn')")
p('R5.ref6','LEVENSHTEIN',"SELECT levenshtein(e.name,'alice') FROM emp e WHERE e.id=2")

# base64 per-row correctness sweep (compare to duckdb per row)
ALPH="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
def b64enc(src):
    h  = "(SELECT h FROM src)"
    cp = "(substr(%s, 6*(i-1)+1, 6) || '0000')" % h
    dig= lambda k: "(instr('0123456789ABCDEF', substr(%s,%d,1))-1)" % (cp,k)
    v  = "(" + "+".join("%s*%d"%(dig(k),16**(6-k)) for k in range(1,7)) + ")"
    A  = lambda sh: "substr('%s', ((%s>>%d)&63)+1, 1)" % (ALPH,v,sh)
    n  = "length(substr(%s, 6*(i-1)+1, 6))" % h
    pc = ("%s || %s || CASE WHEN %s>=4 THEN %s ELSE '=' END || CASE WHEN %s=6 THEN %s ELSE '=' END"
          % (A(18),A(12),n,A(6),n,A(0)))
    return ("(WITH RECURSIVE src(h) AS (SELECT hex(CAST(%s AS BLOB))), "
            "g(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM g WHERE 6*i < (SELECT length(h) FROM src)) "
            "SELECT group_concat(%s, '' ORDER BY i) FROM g)" % (src,pc))
p('R5.b64all','ENCODE_BASE64', "SELECT group_concat(x,'|') FROM (SELECT %s AS x FROM emp e WHERE e.name IS NOT NULL ORDER BY e.id)" % b64enc("e.name"))
p('R5.b64ref','ENCODE_BASE64', "SELECT group_concat(x,'|') FROM (SELECT to_base64(CAST(e.name AS BLOB)) AS x FROM emp e WHERE e.name IS NOT NULL ORDER BY e.id)")
p('R5.b64nul','ENCODE_BASE64', "SELECT %s FROM emp e WHERE e.id=5" % b64enc("e.dept_id"))
p('R5.b64empty','ENCODE_BASE64', "SELECT %s || '|'" % b64enc("''"))
p('R5.b64eref','ENCODE_BASE64', "SELECT to_base64(CAST('' AS BLOB)) || '|'")

dest='/private/tmp/claude-502/-Users-neemsandv/9d0bca0a-c404-43ee-9bc6-4ed2e759ec31/scratchpad/probe/probes-strings-r5.tsv'
open(dest,'w',encoding='utf-8').write(''.join('%s\t%s\t%s\n'%r for r in R))
print('wrote',len(R))
