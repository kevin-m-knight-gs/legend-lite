#!/usr/bin/env python3
# Micro-probes behind the V12/V13 spike's adjudications
# (docs/V12_FUSION_SPIKE_2026_08_28.md) — the two that were run inline
# during the design discussion and belong in the record:
#
#  P-A: JSON rides the byte channel via canonical EMISSION (F4 as
#       amended): the serializer's key set is STATIC, so the canon
#       channel emits keys pre-sorted at compile time; the golden
#       LITERAL canonicalizes host-side once at inlining. No SQL-side
#       recursive canonicalization needed.
#  P-B: `WITH x AS MATERIALIZED` is the evaluate-once guarantee (the
#       keyword is LOAD-BEARING — a plain CTE may legally inline per
#       reference), and ONE result set can carry verdict rows AND
#       side-tagged evidence rows together (the V12 evidence layout).
import duckdb, json

c = duckdb.connect()

print("=== P-A: canonical JSON emission ===")
c.execute("create table p(firstName varchar, lastName varchar, age int, quantity double)")
c.execute("insert into p values ('Peter','Smith',23,25.0),('John','Johnson',22,320.5)")
tree = c.execute("""select json_group_array(json_object(
  'lastName', lastName, 'firstName', firstName, 'age', age, 'quantity', quantity)) from p""").fetchone()[0]
canon = c.execute("""select json_group_array(json_object(
  'age', age, 'firstName', firstName, 'lastName', lastName, 'quantity', quantity)) from p""").fetchone()[0]
golden = ('[{"lastName": "Smith", "firstName": "Peter", "age": 23, "quantity": 25.0},'
          ' {"lastName": "Johnson", "firstName": "John", "age": 22, "quantity": 320.5}]')
golden_canon = json.dumps(json.loads(golden), sort_keys=True, separators=(',', ':'))
print("serializer tree-order (product output):", tree)
print("canon-channel sorted-key emission:     ", canon)
print("golden canonicalized at inlining:      ", golden_canon)
print("BYTE-EQUAL:", canon == golden_canon)
assert canon == golden_canon

print("\n=== P-B: MATERIALIZED evaluate-once + one result set ===")
plain = c.execute("WITH x AS (SELECT random() r) SELECT (SELECT r FROM x) = (SELECT r FROM x)").fetchone()[0]
mat = c.execute("WITH x AS MATERIALIZED (SELECT random() r) SELECT (SELECT r FROM x) = (SELECT r FROM x)").fetchone()[0]
print("plain CTE two references same value:", plain,
      " (optimizer-dependent — NOT a guarantee)")
print("MATERIALIZED two references same value:", mat, " (the documented guarantee)")
assert mat
rows = c.execute("""
WITH res AS MATERIALIZED (SELECT * FROM (VALUES ('Firm A'),('Firm X')) t(n))
SELECT 'verdict' kind, NULL side, CAST((SELECT count(*) FROM res)=2 AS VARCHAR) v
UNION ALL
SELECT 'evidence', 'a', n FROM res
ORDER BY kind DESC, side, v""").fetchall()
print("one result set, verdicts + side-tagged evidence:", rows)
assert rows[0] == ('verdict', None, 'true')
print("\nall probes hold")
