#!/usr/bin/env python3
# V12/V13 fusion spike ROUND 2 (2026-08-28) — harder real corpus tests,
# confirming the RATIFIED design: ONE statement per test body
# (MATERIALIZED CTE lets, plain verdict columns, no CASE nesting),
# JSON via canonical sorted-key EMISSION, grid canon rows, pure-total-
# order sort in SQL, split rung as error-diagnosis fallback.
import duckdb, time, statistics, json

con = duckdb.connect()
R = {}

def section(n): print(f"\n=== {n} ===")

# ---------------------------------------------------------------- fixtures
con.execute("Create Table tradeTable(id INT, prodid INT, accountId INT, quantity FLOAT, tradeDate DATE, settlementDateTime TIMESTAMP(9))")
con.executemany("insert into tradeTable values (?,?,?,?,?,?)", [
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
 # dates.pure's OWN setUp adds these (cumulative fixture, 3rd witness)
 (12,3,2,45,'2016-02-04','2016-02-05 21:00:00.123456789'),
 (13,3,2,45,'2016-03-28','2016-03-29 21:00:00.123456789'),
 (14,3,2,45,'2016-03-28','2016-03-29 21:00:00.123456789'),
 (15,3,2,45,'2016-02-14','2016-02-15 21:00:00.123456789')])
con.execute("Create Table PersonTable(id INT, firstName VARCHAR, lastName VARCHAR, age INT, addressId INT, firmId INT, managerId INT)")
con.executemany("insert into PersonTable values (?,?,?,?,?,?,?)", [
 (1,'Peter','Smith',23,1,1,2),(2,'John','Johnson',22,2,1,4),
 (3,'John','Hill',12,3,1,2),(4,'Anthony','Allen',22,4,1,None),
 (5,'Fabrice','Roberts',34,5,2,None),(6,'Oliver','Hill',32,6,3,None),
 (7,'David','Harris',35,7,4,None),
 (8,'No address','Smith',35,None,4,None),(9,'No firm','no Firm',35,7,None,None),
 (10,'New','York',35,7,1,None),(11,'Elena','Firm B',35,7,3,None),
 (12,'Don','New York',35,7,1,None)])
con.execute("Create Table FirmTable(id INT, legalName VARCHAR, addressId INT, ceoId INT)")
con.executemany("insert into FirmTable values (?,?,?,?)", [
 (1,'Firm X',8,1),(2,'Firm A',9,5),(3,'Firm B',10,3),(4,'Firm C',11,7)])
con.execute("Create Table LocationTable(id INT, personId INT, place VARCHAR, date DATE)")
con.executemany("insert into LocationTable values (?,?,?,?)", [
 (1,1,'New York','2014-12-01'),(2,1,'Hoboken','2014-12-01'),
 (3,2,'New York','2014-12-01'),(4,2,'Hampton','2014-12-01'),
 (5,3,'New York','2014-12-01'),(6,3,'Jersey City','2014-12-01'),
 (7,4,'New York','2014-12-01'),(8,4,'Jersey City','2014-12-01'),
 (9,5,'San Fransisco','2014-12-01'),(10,5,'Paris','2014-12-01'),
 (11,6,'Hong Kong','2014-12-01'),(12,6,'London','2014-12-01'),
 (13,7,'New York','2014-12-01')])

NINE = "strftime({c}, '%Y-%m-%dT%H:%M:%S.%n') || '+0000'"

# ================================================================ E1
# dates::datetime::testQuery — TWO lets + THREE asserts, ONE statement.
# (result: ns-window filter, size 1 + sorted nine-digit equals;
#  result2: <= now(), size 13 — the cumulative rows make 13 true)
section("E1: two MATERIALIZED lets + three verdict columns, one statement")
E1 = f"""
WITH result AS MATERIALIZED (
  select "root".settlementDateTime as sdt from tradeTable as "root"
  where TIMESTAMP_NS '2014-12-04 15:22:23.123456789' < "root".settlementDateTime
    and "root".settlementDateTime < TIMESTAMP_NS '2014-12-04 23:59:59.999999999'),
result2 AS MATERIALIZED (
  select "root".settlementDateTime as sdt from tradeTable as "root"
  where "root".settlementDateTime <= now()::TIMESTAMP::TIMESTAMP_NS)
SELECT (SELECT count(*) FROM result) = 1 AS a1_size,
       (SELECT list({NINE.format(c='sdt')} ORDER BY sdt) FROM result)
         = ['2014-12-04T21:00:00.000000000+0000'] AS a2_equals_sorted,
       (SELECT count(*) FROM result2) = 13 AS a3_size2
"""
R['E1'] = con.execute(E1).fetchone()
print("verdicts (size, sorted-ninedigit-equals, size2):", R['E1'])

# ================================================================ E2
# toMany::testTwoAssociationsToManyDeepWithOr — the ENGINE'S OWN SQL
# verbatim (double-nested left joins), sameElements + size.
section("E2: deep join chain, engine SQL verbatim")
INNER_E2 = """select "root".ID as "pk_0", "root".LEGALNAME as "legalName" from FirmTable as "root" left outer join (select distinct "persontable_1".FIRMID from PersonTable as "persontable_1" left outer join (select distinct "locationtable_1".PERSONID from LocationTable as "locationtable_1" where "locationtable_1".PLACE = 'Hoboken' or "locationtable_1".PLACE = 'Hong Kong') as "locationtable_0" on ("persontable_1".ID = "locationtable_0".PERSONID) where "locationtable_0".PERSONID is not null) as "persontable_0" on ("root".ID = "persontable_0".FIRMID) where "persontable_0".FIRMID is not null"""
E2 = f"""
WITH result AS MATERIALIZED ({INNER_E2})
SELECT (SELECT count(*) FROM result) = 2 AS a1_size,
       (SELECT list("legalName" ORDER BY "legalName") FROM result)
         = (SELECT list(g ORDER BY g) FROM (VALUES ('Firm X'),('Firm B')) t(g)) AS a2_same_elements
"""
R['E2'] = con.execute(E2).fetchone()
print("verdicts:", R['E2'])

# ================================================================ E3
# dates::strictdate::testProject — flat cells ->sort() over MIXED KINDS
# (15 Integers + 15 StrictDates): pure's TOTAL ORDER in SQL = kind rank
# then typed value (canon TEXT is NOT the sort key — '10' < '2' would
# lie). Golden verbatim from the test.
section("E3: mixed-kind flat cells under pure sort() semantics")
GOLD_E3 = (['%d' % i for i in range(1,16)]
           + ['2014-12-01','2014-12-01','2014-12-01','2014-12-02','2014-12-02',
              '2014-12-03','2014-12-03','2014-12-04','2014-12-04','2014-12-04',
              '2014-12-05','2016-02-04','2016-02-14','2016-03-28','2016-03-28'])
gold_e3 = "[" + ",".join(f"'{g}'" for g in GOLD_E3) + "]"
E3 = f"""
WITH result AS MATERIALIZED (
  select "root".id as "id", "root".tradeDate as "date" from tradeTable as "root"),
cells AS (
  SELECT 1 AS rk, CAST("id" AS DOUBLE) AS nk, NULL::DATE AS dk,
         CAST("id" AS VARCHAR) AS canon FROM result
  UNION ALL
  SELECT 4, NULL, "date", CAST("date" AS VARCHAR) FROM result)
SELECT (SELECT count(*) FROM result) = 15 AS a1_size,
       (SELECT list(canon ORDER BY rk, nk, dk) FROM cells) = {gold_e3} AS a2_sorted_cells
"""
R['E3'] = con.execute(E3).fetchone()
print("verdicts:", R['E3'])
# polarity: sorted semantics are ORDERED — a swapped golden must fail
bad = gold_e3.replace("'1','2'", "'2','1'", 1)
R['E3_polarity'] = con.execute(E3.replace(gold_e3, bad)).fetchone()
print("swapped golden (must fail):", R['E3_polarity'])

# ================================================================ E4
# graphFetch testOneComplexProperty — NESTED object, canonical
# sorted-key emission at EVERY level; golden canonicalized at compile
# time. ('firm' < 'firstName' < 'lastName')
section("E4: nested-object JSON, sorted-key canon emission both levels")
GOLD_E4 = ('[{"firstName":"Peter","lastName":"Smith","firm":{"legalName":"Firm X"}},'
 '{"firstName":"John","lastName":"Johnson","firm":{"legalName":"Firm X"}},'
 '{"firstName":"John","lastName":"Hill","firm":{"legalName":"Firm X"}},'
 '{"firstName":"Anthony","lastName":"Allen","firm":{"legalName":"Firm X"}},'
 '{"firstName":"Fabrice","lastName":"Roberts","firm":{"legalName":"Firm A"}},'
 '{"firstName":"Oliver","lastName":"Hill","firm":{"legalName":"Firm B"}},'
 '{"firstName":"David","lastName":"Harris","firm":{"legalName":"Firm C"}}]')
gold_canon = json.dumps(json.loads(GOLD_E4), sort_keys=True, separators=(',',':'))
E4 = f"""
WITH result AS MATERIALIZED (
  SELECT json_group_array(json_object(
    'firm', (SELECT json_object('legalName', f.legalName) FROM FirmTable f WHERE f.id = p.firmId),
    'firstName', p.firstName,
    'lastName', p.lastName)) AS doc
  FROM (SELECT * FROM PersonTable WHERE id <= 7) p)
SELECT (SELECT CAST(doc AS VARCHAR) FROM result) = $$GOLD$$ AS a1_json
"""
R['E4'] = con.execute(E4.replace("$$GOLD$$", "'" + gold_canon.replace("'","''") + "'")).fetchone()
print("nested JSON canonical byte verdict:", R['E4'])
# polarity: perturb one nested value
bad_canon = gold_canon.replace('"Firm B"', '"Firm Q"', 1)
R['E4_polarity'] = con.execute(E4.replace("$$GOLD$$", "'" + bad_canon.replace("'","''") + "'")).fetchone()
print("perturbed nested golden (must fail):", R['E4_polarity'])

# ================================================================ E5
# NULL cells in the fused grid canon (real query + fixture: Trade
# id+settlementDateTime over all 15 rows, ids 10/11 have NULL).
# Golden written in the harness's OWN convention ('TDSNull' sentinel,
# mapped at compile time) — labeled: real query, convention golden.
section("E5: grid canon with NULL cells (TDSNull sentinel at compile time)")
# golden hardcoded in the ENGINE'S nine-digit spelling (a python
# datetime round-trip TRUNCATES to microseconds — the probe itself got
# bitten by the wire-fidelity class the nine-digit canon fixes)
gold_rows = [
 (1,'2014-12-02T21:00:00.000000000+0000'),(2,'2014-12-02T21:00:00.000000000+0000'),
 (3,'2014-12-02T21:00:00.000000000+0000'),(4,'2014-12-03T21:00:00.000000000+0000'),
 (5,'2014-12-03T21:00:00.000000000+0000'),(6,'2014-12-04T21:00:00.000000000+0000'),
 (7,'2014-12-04T15:22:23.123456789+0000'),(8,'2014-12-05T21:00:00.000000000+0000'),
 (9,'2014-12-05T21:00:00.000000000+0000'),(10,'TDSNull'),(11,'TDSNull'),
 (12,'2016-02-05T21:00:00.123456789+0000'),(13,'2016-03-29T21:00:00.123456789+0000'),
 (14,'2016-03-29T21:00:00.123456789+0000'),(15,'2016-02-15T21:00:00.123456789+0000')]
gold_list = "[" + ",".join("'" + f"{i}\x1f{t}" + "'" for i, t in gold_rows) + "]"
E5 = f"""
WITH result AS MATERIALIZED (
  select "root".id as "id", "root".settlementDateTime as sdt from tradeTable as "root"),
a AS (SELECT CAST("id" AS VARCHAR) || chr(31) ||
             COALESCE({NINE.format(c='sdt')}, 'TDSNull') AS rowcanon FROM result)
SELECT (SELECT list(rowcanon ORDER BY rowcanon) FROM a) = (SELECT list(g ORDER BY g) FROM unnest({gold_list}) t(g)) AS a1_rowtuples
"""
R['E5'] = con.execute(E5).fetchone()
print("NULL-cell row-tuple verdict:", R['E5'])

# ================================================================ E6
# The DIAGNOSTIC FALLBACK, end to end: fused statement with an
# erroring later side -> statement error -> split rung localizes.
section("E6: fused error -> split-rung fallback localizes the assert")
E6_FUSED = """
WITH result AS MATERIALIZED (select id from tradeTable where id <= 3)
SELECT (SELECT count(*) FROM result) = 99 AS a1_fails,
       (SELECT CAST('x' AS INT)) = 1 AS a2_errors
"""
try:
    con.execute(E6_FUSED).fetchone()
    R['E6_fused'] = 'unexpected success'
except Exception as e:
    R['E6_fused'] = f'statement error as designed: {type(e).__name__}'
print("fused:", R['E6_fused'])
splits = ["WITH result AS MATERIALIZED (select id from tradeTable where id <= 3) SELECT (SELECT count(*) FROM result) = 99",
          "SELECT (SELECT CAST('x' AS INT)) = 1"]
out = []
for i, s in enumerate(splits, 1):
    try:
        out.append(f"assert{i}={con.execute(s).fetchone()[0]}")
    except Exception as e:
        out.append(f"assert{i}=ERROR({type(e).__name__})")
R['E6_split'] = "; ".join(out)
print("split fallback:", R['E6_split'], " <- assert1's failure recovered, error localized to assert2")

# ================================================================ timing
section("timing: E1 (2 CTEs, 3 verdicts) fused vs split")
def bench(sqls, n=200):
    ts = []
    for _ in range(n):
        t0 = time.perf_counter()
        for s in sqls: con.execute(s).fetchall()
        ts.append(time.perf_counter()-t0)
    return statistics.median(ts)*1e3
inner1 = "select settlementDateTime sdt from tradeTable where TIMESTAMP_NS '2014-12-04 15:22:23.123456789' < settlementDateTime and settlementDateTime < TIMESTAMP_NS '2014-12-04 23:59:59.999999999'"
inner2 = "select settlementDateTime sdt from tradeTable where settlementDateTime <= now()::TIMESTAMP::TIMESTAMP_NS"
split = [inner1, f"select count(*) from ({inner1})", f"select list({NINE.format(c='sdt')} ORDER BY sdt) from ({inner1}) t", inner2, f"select count(*) from ({inner2})"]
R['timing'] = {'fused_ms': round(bench([E1]),3), 'split_ms': round(bench(split),3)}
print(R['timing'])

section("summary")
print(json.dumps({k: str(v) for k, v in R.items()}, indent=1))
