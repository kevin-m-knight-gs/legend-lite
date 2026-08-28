#!/usr/bin/env python3
# V12/V13 fusion spike (2026-08-28) — real corpus tests, hand-fused SQL.
# Register design under test (OPEN_REGISTER V12/V13, user design 2026-08-22):
#   V13: let IS WITH (materialized CTE), assert = verdict overlay, one
#        statement runs query + verdicts, verdict row out.
#   V12: side-tagged UNION ALL evidence, per-side typed value columns,
#        per-side canon columns, ORDER BY side+canon, literals inline.
# Canon spellings mirror core LiteralSpelling: bool/int CAST VARCHAR,
# float fixed-point (zeros -> '0.0'), string raw/quoted, datetime
# 'T'-separated + '+0000'.
import duckdb, time, statistics, json

con = duckdb.connect()
R = {}  # findings

def section(name):
    print(f"\n=== {name} ===")

# ---------------------------------------------------------------- fixtures
section("fixtures (verbatim from relationalSetUp.pure / graphFetch setup)")
try:
    con.execute("Create Table tradeTable(id INT, prodid INT, accountId INT, quantity FLOAT, tradeDate DATE, settlementDateTime TIMESTAMP(9))")
    R['ddl_timestamp9'] = 'accepted'
except Exception as e:
    R['ddl_timestamp9'] = f'REJECTED: {e}'
    con.execute("Create Table tradeTable(id INT, prodid INT, accountId INT, quantity FLOAT, tradeDate DATE, settlementDateTime TIMESTAMP_NS)")
print("TIMESTAMP(9) DDL:", R['ddl_timestamp9'])

trade_rows = [
 (1,1,1,25,'2014-12-01','2014-12-02 21:00:00'),
 (2,1,2,320,'2014-12-01','2014-12-02 21:00:00'),
 (3,2,1,11,'2014-12-01','2014-12-02 21:00:00'),
 (4,2,2,23,'2014-12-02','2014-12-03 21:00:00'),
 (5,2,1,32,'2014-12-02','2014-12-03 21:00:00'),
 (6,3,1,27,'2014-12-03','2014-12-04 21:00:00'),
 (7,3,1,44,'2014-12-03','2014-12-04 15:22:23.123456789'),
 (8,3,2,22,'2014-12-04','2014-12-05 21:00:00'),
 (9,3,2,45,'2014-12-04','2014-12-05 21:00:00'),
 (10,3,2,38,'2014-12-04',None),
 (11,-3,-4,5,'2014-12-05',None),
]
con.executemany("insert into tradeTable values (?,?,?,?,?,?)", trade_rows)

con.execute("Create Table PersonTable(id INT, firstName VARCHAR(200), lastName VARCHAR(200), age INT, addressId INT, firmId INT, managerId INT)")
con.executemany("insert into PersonTable values (?,?,?,?,?,?,?)", [
 (1,'Peter','Smith',23,1,1,2),(2,'John','Johnson',22,2,1,4),
 (3,'John','Hill',12,3,1,2),(4,'Anthony','Allen',22,4,1,None),
 (5,'Fabrice','Roberts',34,5,2,None),(6,'Oliver','Hill',32,6,3,None),
 (7,'David','Harris',35,7,4,None),
 # CUMULATIVE cross-family setup (testSimple.pure adds these — the
 # corpus fixture universe is shared; finding: fused asserts bind the
 # harness's workspace state, never per-file fixtures)
 (8,'No address','Smith',35,None,4,None),(9,'No firm','no Firm',35,7,None,None),
 (10,'New','York',35,7,1,None),(11,'Elena','Firm B',35,7,3,None),
 (12,'Don','New York',35,7,1,None)])
con.execute("Create Table FirmTable(id INT, legalName VARCHAR(200), addressId INT, ceoId INT)")
con.executemany("insert into FirmTable values (?,?,?,?)", [
 (1,'Firm X',8,1),(2,'Firm A',9,5),(3,'Firm B',10,3),(4,'Firm C',11,7)])
con.execute("Create Table InteractionTable(id VARCHAR(200), sourceId INT, targetId INT, time INT, active VARCHAR(1))")
con.executemany("insert into InteractionTable values (?,?,?,?,?)", [
 ('1',1,2,4,'Y'),('2',1,2,6,'N'),('2',1,3,12,'N'),('2',1,4,14,'Y'),
 ('3',4,5,3,'N'),('3',4,6,23,'Y'),('4',3,6,11,'N'),('5',3,7,33,'Y'),
 ('6',4,1,44,'Y'),('6',4,3,55,'N'),('6',5,4,22,'Y'),('6',5,6,33,'Y'),
 ('7',4,1,14,'N'),('7',4,2,11,'Y')])

# ------------------------------------------------------------ micro-probes
section("micro-probes")
# P1: nine-digit strftime over the ns carrier
try:
    r = con.execute("select strftime(settlementDateTime, '%Y-%m-%dT%H:%M:%S.%n') from tradeTable where id=7").fetchone()[0]
    R['ns_strftime'] = r
except Exception as e:
    R['ns_strftime'] = f'ERROR: {e}'
print("nine-digit strftime (id=7):", R['ns_strftime'])
try:
    r = con.execute("select strftime(settlementDateTime, '%Y-%m-%dT%H:%M:%S.%n') from tradeTable where id=6").fetchone()[0]
    R['ns_strftime_zero'] = r
except Exception as e:
    R['ns_strftime_zero'] = f'ERROR: {e}'
print("nine-digit strftime (id=6, whole second):", R['ns_strftime_zero'])
# P2: JSON equality key-order semantics
try:
    r = con.execute("""select '{"a":1,"b":2}'::JSON = '{"b":2,"a":1}'::JSON""").fetchone()[0]
    R['json_eq_keyorder_insensitive'] = r
except Exception as e:
    R['json_eq_keyorder_insensitive'] = f'ERROR: {e}'
print("JSON = with reordered keys:", R['json_eq_keyorder_insensitive'])
# P3: eager evaluation — does an error in verdict-column 2 kill the
# whole statement even when verdict-column 1 already failed?
# (1/0 is inf in DuckDB — use a real conversion error)
try:
    con.execute("select 1=2 as a1, (select CAST('x' AS INT)) as a2").fetchone()
    R['eager_error'] = 'no error (lazy or folded)'
except Exception as e:
    R['eager_error'] = f'ERRORS WHOLE STATEMENT: {type(e).__name__}'
print("first-failure hazard probe:", R['eager_error'])
# P3b: CASE-guard mitigation — later verdicts nested under earlier
# success defer evaluation (the fusion-gradient inside ONE statement)
try:
    r = con.execute("select CASE WHEN 1=2 THEN (select CAST('x' AS INT)) END").fetchone()
    R['case_guard'] = f'defers evaluation, returns {r}'
except Exception as e:
    R['case_guard'] = f'STILL ERRORS: {type(e).__name__}'
print("CASE-guard mitigation:", R['case_guard'])

# canon helper (mirrors LiteralSpelling leaf forms, hand-inlined)
BOOLC = "CAST({c} AS VARCHAR)"
INTC  = "CAST({c} AS VARCHAR)"

# ================================================================ TEST A
# meta::relational::tests::mapping::dates::datetime::testQuery
#   assertSize($result.values, 1)
#   assertEquals([%2014-12-04T21:00:00.000000000],
#                $result.values.settlementDateTime->sort())
section("TEST A: dates::datetime::testQuery (sorted temporal + size)")
INNER_A = """select "root".settlementDateTime as "settlementDateTime"
from tradeTable as "root"
where TIMESTAMP_NS '2014-12-04 15:22:23.123456789' < "root".settlementDateTime
  and "root".settlementDateTime < TIMESTAMP_NS '2014-12-04 23:59:59.999999999'"""

# platform temporalCanon (today): CAST -> T-sep -> +0000 (minimal subseconds)
A_TODAY = f"""
WITH result AS MATERIALIZED ({INNER_A}),
 actual AS (
   SELECT row_number() OVER (ORDER BY "settlementDateTime") ord,
          replace(CAST("settlementDateTime" AS VARCHAR), ' ', 'T') || '+0000' AS canon
   FROM result),
 expected(ord, canon) AS (VALUES (1, '2014-12-04T21:00:00.000000000+0000'))
SELECT (SELECT count(*) FROM result) = 1 AS assert_size,
       NOT EXISTS (SELECT 1 FROM expected e FULL JOIN actual a USING (ord)
                   WHERE e.canon IS DISTINCT FROM a.canon) AS assert_equals
"""
row = con.execute(A_TODAY).fetchone()
R['A_today'] = row
print("with today's minimal-subsecond canon:", row, " <- equals FALSE = the named nine-digit wire-fidelity row, reproduced")

# candidate emission fix: nine-digit canon on BOTH sides
A_NINE = f"""
WITH result AS MATERIALIZED ({INNER_A}),
 actual AS (
   SELECT row_number() OVER (ORDER BY "settlementDateTime") ord,
          strftime("settlementDateTime", '%Y-%m-%dT%H:%M:%S.%n') || '+0000' AS canon
   FROM result),
 expected(ord, canon) AS (VALUES (1, '2014-12-04T21:00:00.000000000+0000'))
SELECT (SELECT count(*) FROM result) = 1 AS assert_size,
       NOT EXISTS (SELECT 1 FROM expected e FULL JOIN actual a USING (ord)
                   WHERE e.canon IS DISTINCT FROM a.canon) AS assert_equals
"""
try:
    row = con.execute(A_NINE).fetchone()
    R['A_ninedigit'] = row
    print("with nine-digit canon:", row)
except Exception as e:
    R['A_ninedigit'] = f'ERROR: {e}'
    print("nine-digit canon ERROR:", e)

# ================================================================ TEST B
# query::association::toMany::testAssociationToManyWithBoolean
#   assertSize 3; assertSameElements(['Firm A','Firm C','Firm X'], .legalName)
# inner SQL = the ENGINE'S OWN (assertSameSQL golden), verbatim
section("TEST B: toMany::testAssociationToManyWithBoolean (multiset, engine SQL verbatim)")
INNER_B = """select "root".ID as "pk_0", "root".LEGALNAME as "legalName" from FirmTable as "root" left outer join (select distinct "persontable_1".FIRMID from PersonTable as "persontable_1" where "persontable_1".LASTNAME = 'Roberts' or "persontable_1".LASTNAME = 'Smith') as "persontable_0" on ("root".ID = "persontable_0".FIRMID) where "persontable_0".FIRMID is not null"""
B = f"""
WITH result AS MATERIALIZED ({INNER_B}),
 sides(side, canon) AS (
   SELECT 'e', canon FROM (VALUES ('Firm A'),('Firm C'),('Firm X')) t(canon)
   UNION ALL
   SELECT 'a', "legalName" FROM result)
SELECT (SELECT count(*) FROM result) = 3 AS assert_size,
       (SELECT list(canon ORDER BY canon) FROM sides WHERE side='e')
     = (SELECT list(canon ORDER BY canon) FROM sides WHERE side='a') AS assert_same_elements
"""
row = con.execute(B).fetchone()
R['B'] = row
print("verdicts:", row)
# V12 evidence layout (side-tagged, ORDER BY side+canon) — the referee feed
ev = con.execute(f"""WITH result AS MATERIALIZED ({INNER_B}),
 sides(side, canon) AS (
   SELECT 'e', canon FROM (VALUES ('Firm A'),('Firm C'),('Firm X')) t(canon)
   UNION ALL SELECT 'a', "legalName" FROM result)
SELECT side, canon FROM sides ORDER BY side, canon""").fetchall()
print("evidence rows (side-tagged):", ev)
# polarity: broken golden must fail
rowp = con.execute(B.replace("'Firm C'", "'Firm ZZZ'")).fetchone()
R['B_polarity'] = rowp
print("broken golden:", rowp)

# ================================================================ TEST C
# mapping::boolean::testProject — flat cells, width 2, incidental order
#   assertSize($result.values.rows, 14)
#   assertEquals([4,true,6,false,...28 cells...], $result.values.rows.values)
section("TEST C: boolean::testProject (flat cells -> row-tuple canon)")
INNER_C = """select "root".time as "time", case when "root".active = 'Y' then true else false end as "active" from InteractionTable as "root" """
GOLD = [(4,'true'),(6,'false'),(12,'false'),(14,'true'),(3,'false'),(23,'true'),
        (11,'false'),(33,'true'),(44,'true'),(55,'false'),(22,'true'),(33,'true'),
        (14,'false'),(11,'true')]
vals = ",".join(f"({t},{b})" for t,b in GOLD)
# grid canon: per-cell leaf spelling joined by a separator no cell can
# contain (unit separator chr(31)) — the multi-column wrapWithCanon
# extension in miniature. Incidental order -> row-tuple multiset =
# list(rowcanon ORDER BY rowcanon) equality.
C = f"""
WITH result AS MATERIALIZED ({INNER_C}),
 a AS (SELECT CAST("time" AS VARCHAR) || chr(31) || CAST("active" AS VARCHAR) AS rowcanon FROM result),
 e AS (SELECT CAST(t AS VARCHAR) || chr(31) || CAST(b AS VARCHAR) AS rowcanon
       FROM (VALUES {vals}) g(t, b))
SELECT (SELECT count(*) FROM result) = 14 AS assert_size,
       (SELECT list(rowcanon ORDER BY rowcanon) FROM e)
     = (SELECT list(rowcanon ORDER BY rowcanon) FROM a) AS assert_equals_rowtuples
"""
row = con.execute(C).fetchone()
R['C'] = row
print("verdicts:", row)
# cross-row shuffle MUST fail: swap the booleans of rows 1+2 (4,true)(6,false)->(4,false)(6,true)
SHUF = vals.replace("(4,true)", "(4,false)", 1).replace("(6,false)", "(6,true)", 1)
rowp = con.execute(C.replace(vals, SHUF)).fetchone()
R['C_shuffle'] = rowp
print("cross-row shuffle (must be False):", rowp)

# ================================================================ TEST D
# graphFetch::tests::simple::testSimpleGraphFetchWithPrimitivesOnly
section("TEST D: simple graphFetch serialize (JSON verdict)")
GOLD_D = ('[{"firstName":"Peter","lastName":"Smith"},{"firstName":"John","lastName":"Johnson"},'
          '{"firstName":"John","lastName":"Hill"},{"firstName":"Anthony","lastName":"Allen"},'
          '{"firstName":"Fabrice","lastName":"Roberts"},{"firstName":"Oliver","lastName":"Hill"},'
          '{"firstName":"David","lastName":"Harris"}]')
# the graphFetch family's OWN setup re-creates personTable with 7 rows
# (fixture-state finding again, in reverse) — scope to that state
INNER_D = """SELECT json_group_array(json_object('firstName', firstName, 'lastName', lastName)) AS doc
FROM (SELECT firstName, lastName FROM PersonTable WHERE id <= 7) src"""
D = f"""
WITH result AS MATERIALIZED ({INNER_D})
SELECT (SELECT doc FROM result) = '{GOLD_D}'::JSON AS assert_json
"""
try:
    row = con.execute(D).fetchone()
    R['D'] = row
    print("byte JSON verdict (same key order):", row)
except Exception as e:
    R['D'] = f'ERROR: {e}'
    print("ERROR:", e)
# key-order variant of the golden (engine semantics: keys order-INSENSITIVE)
GOLD_D2 = GOLD_D.replace('{"firstName":"Peter","lastName":"Smith"}',
                         '{"lastName":"Smith","firstName":"Peter"}', 1)
try:
    row = con.execute(D.replace(GOLD_D, GOLD_D2)).fetchone()
    R['D_keyorder'] = row
    print("reordered-key golden (engine says equal):", row)
except Exception as e:
    R['D_keyorder'] = f'ERROR: {e}'

# ================================================================ timing
section("timing: fused single-shot vs split (query + 2 side fetches)")
def bench(sql_list, n=200):
    ts = []
    for _ in range(n):
        t0 = time.perf_counter()
        for s in sql_list:
            con.execute(s).fetchall()
        ts.append(time.perf_counter() - t0)
    return statistics.median(ts) * 1e3

fused_ms = bench([B])
split_ms = bench([INNER_B,
                  "SELECT list(canon ORDER BY canon) FROM (VALUES ('Firm A'),('Firm C'),('Firm X')) t(canon)",
                  f"SELECT list(\"legalName\" ORDER BY \"legalName\") FROM ({INNER_B}) r",
                  f"SELECT count(*) FROM ({INNER_B}) r"])
R['timing'] = {'fused_ms': round(fused_ms,3), 'split_ms': round(split_ms,3)}
print(f"median per test-case: fused one-shot={fused_ms:.3f} ms  vs  split (1 query + 3 verdict fetches, sides re-executed)={split_ms:.3f} ms")

section("summary")
print(json.dumps({k: str(v) for k,v in R.items()}, indent=1))
