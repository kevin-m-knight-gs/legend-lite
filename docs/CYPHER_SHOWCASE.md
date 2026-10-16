# `#Cypher{...}#` showcase: one Cypher query, Neo4j's answer, and legend-lite's DuckDB and Snowflake SQL

This page follows one non-trivial Cypher query through legend-lite's `#Cypher{...}#` island
(`docs/CYPHER_STUDY_2026_10_15.md`, `docs/CYPHER_PLAN_2026_10_15.md`). It was verified on 2026-10-15 against the
translator on branch `claude/keen-noether-v5mis0` (PR1b, the study's §12.2); the commit that added this page pinned its
model, its Pure and its SQL and changed no translator code.

- **Accepted.** lite translates the query; it does not refuse it.
- **Correct.** lite's answer matches Neo4j Community 2026.09.0's cell for cell, in the query's
  order, on both parity lanes (DuckDB and the embedded Postgres 16). The judge is the Cypher parity harness's own
  (`CypherParity.outcome`), and its guards are clean. Neo4j's rows were snapped by the repo's oracle,
  `tools/neo4j-oracle`, under Cypher 25.
- **Pinned.** The model the graph implies, the Pure the island desugars to, and the SQL the query plans for DuckDB and
  for Snowflake are pinned byte for byte, so a change to any is seen and reviewed.
- **Snowflake.** The same query, compiled against a Snowflake connection, plans to the SQL in §8. That SQL is rendered
  only: nothing ran on Snowflake.

**Where it lives.**

- `core/src/test/resources/cypher-parity/lite/basics.yaml`: the query (`showcase_main`) and the 25 cases beside it, run
  by both Cypher parity lanes, `//core:cypher_parity_test` and `//core:cypher_parity_postgres_test`.
- `core/src/test/resources/cypher-parity/graphs/legend.cypher`: the graph (§2), which the oracle creates on Neo4j and
  dumps; the lanes seed their tables from the dump.
- `core/src/test/resources/cypher-parity/neo4j-answers.jsonl`, key `lite/basics/showcase_main`: Neo4j's answer,
  snapped by `tools/neo4j-oracle/snap.sh --write`; `ledger.tsv` holds the case's status (PASS on both lanes).
- `core/src/test/resources/cypher-parity/showcase/model.pure`, `pure.txt`, `duckdb.sql` and `snowflake.sql`: the
  pinned texts, checked by `CypherParityTest.theShowcasePlansItsPinnedSql` in `//core:cypher_parity_test`. If one
  changes on purpose, rewrite them from the workspace root and update §4 to §8 here:

  ```sh
  bazel test //core:cypher_parity_test --strategy=TestRunner=local \
    --test_env=JDK_JAVA_OPTIONS=-Dcypher.showcase.update=$PWD/core/src/test/resources/cypher-parity/showcase
  ```

## 1. The query

```
#Cypher{
MATCH (p:Person)-[:WORKS_AT]->(f:Firm)-[:HQ]->(c:City)
WHERE p.age IS NOT NULL AND c.name <> 'Nowhere'
WITH f.legalName AS firm, c.name AS city, count(*) AS headcount, avg(p.age) AS avgAge,
     min(p.age) AS youngest, max(p.age) AS oldest
WHERE headcount >= 1
RETURN firm, city, headcount, avgAge, youngest, oldest,
       CASE WHEN avgAge >= 32 THEN 'seasoned' ELSE 'young' END AS band
ORDER BY headcount DESC, firm
}#
```

Five people, three firms, two cities; who works where, and where each firm is based. For each firm with a headquarters,
the people of known age who work there: how many, their average, youngest and oldest age, and a band by the average.

## 2. The graph

The graph is written as Neo4j takes it (`graphs/legend.cypher`); the oracle creates it, and dumps it for the lanes:

```
CREATE (ny:City {name: 'New York'}),
       (ld:City {name: 'London'}),
       (acme:Firm {legalName: 'Acme', founded: 1990}),
       (globex:Firm {legalName: 'Globex'}),
       (initech:Firm {legalName: 'Initech', founded: 2001}),
       (alice:Person {name: 'Alice', age: 30, active: true}),
       (bob:Person {name: 'Bob', age: 40}),
       (charlie:Person {name: 'Charlie', age: 25, active: false}),
       (diana:Person {name: 'Diana', active: true}),
       (eve:Person {name: 'Eve', age: 35, active: true}),
       (acme)-[:HQ]->(ny),
       (globex)-[:HQ]->(ld),
       (alice)-[:WORKS_AT]->(acme),
       (bob)-[:WORKS_AT]->(acme),
       (charlie)-[:WORKS_AT]->(globex),
       (eve)-[:WORKS_AT]->(globex)
```

## 3. What it exercises

- Two hops in one pattern, `(p:Person)-[:WORKS_AT]->(f:Firm)-[:HQ]->(c:City)`, read from the root node `p` (each hop
  to-one from the node before it: `WORKS_AT` and `HQ` are foreign keys on the inferred tables), each hop a navigation
  (`$p.WORKS_AT.HQ.name`) with the condition that it finds a node.
- A `WHERE` with `IS NOT NULL` and a `<>` against a string: Cypher's three-valued logic, kept by `SqlToPure.Tx`'s
  spelling (the condition holds only where every operand is known).
- `WITH` with the implicit grouping of four aggregates (`count(*)`, `avg`, `min`, `max`) by two keys, a `WHERE` after
  it over the aggregate (a `HAVING`), and a `RETURN` that reads the projected columns, with a `CASE` over a nullable
  average and an `ORDER BY` on two keys (nulls first descending, last ascending: Neo4j's rule).

## 4. Neo4j 2026.09.0's answer

| firm | city | headcount | avgAge | youngest | oldest | band |
|---|---|---|---|---|---|---|
| 'Acme' | 'New York' | 2 | 35.0 | 30 | 40 | 'seasoned' |
| 'Globex' | 'London' | 2 | 30.0 | 25 | 35 | 'young' |

## 5. legend-lite's answer: identical

Cell for cell, in order, on both lanes (`ledger.tsv`: `lite/basics/showcase_main` PASS, PASS). The harness compares
the values in the TCK's value grammar: strings, integers, and floats within a relative tolerance of 1e-9.

## 6. The model the graph implies

The harness infers it from the dump (`CypherGraph`), the lanes compile it, and nothing hand-written is in it: a class
per label, a property per key typed by its values (every property optional, as a graph's are), each relationship type
an association (`g::R_<type>`, named apart from the classes) whose ends are named by the type (`WORKS_AT` holds the
firm, `WORKS_AT_in` the people), realized as a foreign key on the to-one side.

```
###Pure
Class g::City
{
    name: String[0..1];
}
Class g::Firm
{
    founded: Integer[0..1];
    legalName: String[0..1];
}
Class g::Person
{
    active: Boolean[0..1];
    age: Integer[0..1];
    name: String[0..1];
}
Association g::R_HQ
{
    HQ: g::City[0..1];
    HQ_in: g::Firm[*];
}
Association g::R_WORKS_AT
{
    WORKS_AT: g::Firm[0..1];
    WORKS_AT_in: g::Person[*];
}
###Relational
Database g::DB
(
    Table n_city (id BIGINT PRIMARY KEY, p_name VARCHAR(4000))
    Table n_firm (id BIGINT PRIMARY KEY, p_founded BIGINT, p_legalname VARCHAR(4000), r_hq BIGINT)
    Table n_person (id BIGINT PRIMARY KEY, p_active BIT, p_age BIGINT, p_name VARCHAR(4000), r_works_at BIGINT)
    Join HQ(n_firm.r_hq = n_city.id)
    Join WORKS_AT(n_person.r_works_at = n_firm.id)
)
###Mapping
Mapping g::Map
(
    g::City: Relational
    {
        ~mainTable [g::DB] n_city
        name: [g::DB] n_city.p_name
    }
    g::Firm: Relational
    {
        ~mainTable [g::DB] n_firm
        founded: [g::DB] n_firm.p_founded,
        legalName: [g::DB] n_firm.p_legalname
    }
    g::Person: Relational
    {
        ~mainTable [g::DB] n_person
        active: [g::DB] n_person.p_active,
        age: [g::DB] n_person.p_age,
        name: [g::DB] n_person.p_name
    }
    g::R_HQ: Relational { AssociationMapping ( HQ: [g::DB]@HQ, HQ_in: [g::DB]@HQ ) }
    g::R_WORKS_AT: Relational { AssociationMapping ( WORKS_AT: [g::DB]@WORKS_AT, WORKS_AT_in: [g::DB]@WORKS_AT ) }
)
```

## 7. The Pure the island desugars to

`compiler/spec/CypherIslands.translate`, printed as a person writes it (`pure.txt`; the lane runs it with the
mapping and runtime appended to the model):

```
g::Person.all()->filter(p|($p.WORKS_AT->isNotEmpty() && $p.WORKS_AT.HQ->isNotEmpty()) && ($p.age->isNotEmpty() && ($p.WORKS_AT.HQ.name->isNotEmpty() && ($p.WORKS_AT.HQ.name->toOne() != 'Nowhere'))))
    ->project(~[firm:p|$p.WORKS_AT.legalName, city:p|$p.WORKS_AT.HQ.name, _cypher_1:p|1, _cypher_2:p|$p.age, _cypher_3:p|$p.age, _cypher_4:p|$p.age])
    ->groupBy(~[firm, city], ~[headcount:x|$x._cypher_1:y|$y->count(), avgAge:x|$x._cypher_2:y|$y->average(), youngest:x|$x._cypher_3:y|$y->min(), oldest:x|$x._cypher_4:y|$y->max()])
    ->select(~[firm, city, headcount, avgAge, youngest, oldest])
    ->filter(r|$r.headcount >= 1)
    ->extend(~[band:r|if($r.avgAge->isNotEmpty() && ($r.avgAge->toOne() >= 32), |'seasoned', |'young')])
    ->select(~[firm, city, headcount, avgAge, youngest, oldest, band])
    ->sort([~headcount->descending(meta::pure::functions::relation::NullOrder.FIRST), ~firm->ascending(meta::pure::functions::relation::NullOrder.LAST)])
```

How the Cypher lands in it: the pattern's root is `Person` (`all()`); each hop that may find nothing adds an
`isNotEmpty` to the filter; `WHERE`'s `IS NOT NULL` is `isNotEmpty`, its `<>` a strict test that holds only where the
name is known; `WITH`'s aggregates project their arguments first (`_cypher_n`) and `groupBy` by the two keys, a `select`
restoring the items' order; the `WHERE` after `WITH` is a `filter` over the grouped relation; `RETURN`'s `CASE` is an
`extend` whose `if` tests the average where it is known; the `ORDER BY` is a `sort` with Neo4j's null order.

## 8. The SQL for DuckDB (rendered and run)

```sql
SELECT t8.firm, t8.city, t8.headcount, t8.avgAge, t8.youngest, t8.oldest, CASE WHEN t8.avgAge IS NOT NULL AND t8.avgAge >= 32 THEN 'seasoned' ELSE 'young' END AS band
FROM (
  SELECT t3.p_legalname AS firm, t3.HQ_p_name AS city, COUNT(1) AS headcount, AVG(1.0 * t0.p_age) AS avgAge, MIN(t0.p_age) AS youngest, MAX(t0.p_age) AS oldest
  FROM n_person AS t0
  LEFT OUTER JOIN (
    SELECT t1.id AS id, t1.p_legalname AS p_legalname, t1.r_hq AS r_hq, t2.p_name AS HQ_p_name
    FROM n_firm AS t1
    LEFT OUTER JOIN n_city AS t2 ON t1.r_hq = t2.id
  ) AS t3 ON t0.r_works_at = t3.id
  LEFT OUTER JOIN (
    SELECT DISTINCT t4.id
    FROM n_firm AS t4
  ) AS t6 ON t0.r_works_at = t6.id
  LEFT OUTER JOIN (
    SELECT DISTINCT t5.id
    FROM n_city AS t5
  ) AS t7 ON t3.r_hq = t7.id
  WHERE t6.id IS NOT NULL AND t7.id IS NOT NULL AND t0.p_age IS NOT NULL AND t3.HQ_p_name IS NOT NULL AND (t3.HQ_p_name IS DISTINCT FROM 'Nowhere')
  GROUP BY t3.p_legalname, t3.HQ_p_name
  HAVING COUNT(1) >= 1
) AS t8
ORDER BY t8.headcount DESC NULLS FIRST, t8.firm NULLS LAST
```

## 9. The SQL for Snowflake (rendered, not run)

```sql
SELECT "t8"."firm" AS "firm", "t8"."city" AS "city", "t8"."headcount" AS "headcount", "t8"."avgAge" AS "avgAge", "t8"."youngest" AS "youngest", "t8"."oldest" AS "oldest", CASE WHEN "t8"."avgAge" IS NOT NULL AND "t8"."avgAge" >= 32 THEN 'seasoned' ELSE 'young' END AS "band"
FROM (
  SELECT "t3"."p_legalname" AS "firm", "t3"."HQ_p_name" AS "city", COUNT(1) AS "headcount", CAST(AVG(CAST(1.0 AS NUMBER(2,1)) * "t0"."p_age") AS DOUBLE) AS "avgAge", MIN("t0"."p_age") AS "youngest", MAX("t0"."p_age") AS "oldest"
  FROM "n_person" AS "t0"
  LEFT OUTER JOIN (
    SELECT "t1"."id" AS "id", "t1"."p_legalname" AS "p_legalname", "t1"."r_hq" AS "r_hq", "t2"."p_name" AS "HQ_p_name"
    FROM "n_firm" AS "t1"
    LEFT OUTER JOIN "n_city" AS "t2" ON "t1"."r_hq" = "t2"."id"
  ) AS "t3" ON "t0"."r_works_at" = "t3"."id"
  LEFT OUTER JOIN (
    SELECT DISTINCT "t4"."id" AS "id"
    FROM "n_firm" AS "t4"
  ) AS "t6" ON "t0"."r_works_at" = "t6"."id"
  LEFT OUTER JOIN (
    SELECT DISTINCT "t5"."id" AS "id"
    FROM "n_city" AS "t5"
  ) AS "t7" ON "t3"."r_hq" = "t7"."id"
  WHERE "t6"."id" IS NOT NULL AND "t7"."id" IS NOT NULL AND "t0"."p_age" IS NOT NULL AND "t3"."HQ_p_name" IS NOT NULL AND ("t3"."HQ_p_name" IS DISTINCT FROM 'Nowhere')
  GROUP BY "t3"."p_legalname", "t3"."HQ_p_name"
  HAVING COUNT(1) >= 1
) AS "t8"
ORDER BY "t8"."headcount" DESC NULLS FIRST, "t8"."firm" NULLS LAST
```

## 10. What lite refused on the way

Nothing in this query. The cases beside it in `lite/basics.yaml` show the refusals by name: `RETURN p` (a node as a
value), `UNWIND` (lists), a property no class declares (C4: lite's compile error where Neo4j answers `null`,
`docs/SEMANTICS_REGISTER.md` S58).

## 11. How it was checked, and the limits

- The oracle ran the graph and the query on Neo4j Community 2026.09.0 (`snap.sh --write`), and `--check` reports no
  drift; the lanes judged lite's rows against the snapped answer on DuckDB and on Postgres 16.
- The SQL is lite's own for its DuckDB and Snowflake dialects; Snowflake's is rendered only.
- The query reads to-one hops only: a pattern that fans out (a firm's many employees read from the firm), lists,
  `OPTIONAL MATCH`, nodes as values and the graph algorithms are the later slices of the plan (`docs/CYPHER_PLAN_2026_10_15.md` §4).

## 12. What PR2 brings: lists, maps, the quantifiers, `UNWIND` (2026-10-15)

The values slice (the study's §12.5) reads lists and maps of known shape, the four quantifiers, comprehensions,
indexing and slicing, `IN` over lists, `WITH` as a first clause, `UNWIND`, lists and maps as returned values, and a
function table. Three of its 28 Neo4j-judged cases (`lite/values.yaml`; `ledger.tsv`: PASS on both lanes):

**Lists built from the rows** (`returned_lists_over_rows`), the database writing each as a Variant (`toVariant` of a
collection; a map built from the rows is refused by name until lite's Postgres lowering writes `newMap` as an
object, the study's §12.5.5):

```
MATCH (p:Person)
RETURN p.name AS name, [p.age IS NULL, p.active IS NULL] AS unknowns,
       [coalesce(p.name, '?'), coalesce(p.name, '?') + '!'] AS names
ORDER BY name
```

| name | unknowns | names |
|---|---|---|
| 'Alice' | [false, false] | ['Alice', 'Alice!'] |
| 'Bob' | [false, true] | ['Bob', 'Bob!'] |
| 'Charlie' | [false, false] | ['Charlie', 'Charlie!'] |
| 'Diana' | [true, false] | ['Diana', 'Diana!'] |
| 'Eve' | [false, false] | ['Eve', 'Eve!'] |

**The quantifiers under Cypher's three-valued rules** (`quantifiers_three_valued`), each unrolled over the spelled
list, the predicate computed by the database:

```
RETURN all(x IN [1, null] WHERE x > 0) AS a, any(x IN [null, 2] WHERE x = 2) AS b,
       none(x IN [1, 2] WHERE x = 3) AS c, single(x IN [2, null] WHERE x = 2) AS s,
       single(x IN [34, 0, null, 5, 900] WHERE x < 10) AS t,
       single(x IN [34, 10, null, 15, 900] WHERE x < 10) AS u, all(x IN [] WHERE false) AS e
```

| a | b | c | s | t | u | e |
|---|---|---|---|---|---|---|
| null | true | true | null | false | null | true |

**`UNWIND` after a projection** (`unwind_after_with_over_rows`), the rows in hand paired with the list's elements
(Pure's `lateral` of a `flatten`):

```
MATCH (p:Person) WITH p.name AS name UNWIND [1, 2] AS k RETURN name, k ORDER BY name, k
```

Ten rows, `'Alice' 1`, `'Alice' 2`, ... `'Eve' 2`, identical to Neo4j's on both lanes. What PR2 refuses by name, and
what the census says comes next (the temporal values, the join form, `collect`), is the study's §12.5.3 and §12.5.4.

## 13. What the temporal slice brings: dates and local date-times (2026-10-16)

Cypher's `date` is Pure's StrictDate and `localdatetime` Pure's DateTime (the study's §12.6); a zoned
`datetime`, a `time` and a `duration` are refused by name until a Pure type carries them. One of its 16 Neo4j-judged
cases (`lite/temporal.yaml`, `stored_dates`; `ledger.tsv`: PASS on both lanes), over a graph whose nodes hold a date
and a local date-time:

```
CREATE (:Val {name: 'a', d: date('1984-10-11'), dt: localdatetime('1984-10-11T12:31:14.645')}),
       (:Val {name: 'b', d: date('2015-06-24'), dt: localdatetime('2016-07-21T21:45:22.142')})
```

```
MATCH (v:Val)
RETURN v.name AS name, v.d AS d, v.d.year AS y, v.dt AS dt, v.dt.month AS m,
       v.d < date('2000-01-01') AS early, date.truncate('month', v.dt) AS t
ORDER BY name
```

| name | d | y | dt | m | early | t |
|---|---|---|---|---|---|---|
| 'a' | 1984-10-11 | 1984 | 1984-10-11T12:31:14.645 | 10 | true | 1984-10-01 |
| 'b' | 2015-06-24 | 2015 | 2016-07-21T21:45:22.142 | 7 | false | 2016-07-01 |

The inferred model types `d` as StrictDate (a DATE column) and `dt` as DateTime (a TIMESTAMP); the island reads
`v.d.year` as Pure's `year`, the comparison as the database's, the truncation as `firstDayOfMonth`, and the judge
reads each date cell as Neo4j's driver spells one. What the slice refuses by name is the study's §12.6.5.
