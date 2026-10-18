# Writes: one write layer, two kinds of graph (design sprint, 2026-10-18)

**Status:** design agreed in conversation with the user on 2026-10-18 (§7 records each call). Leg 1 and leg 2 start
now. Leg 3 (a spike) decides the cost of the native store before its legs are planned in detail. Nothing here is
built yet.

**Asked for:** writes for the `#Cypher{...}#` island, designed so that the Postgres `#SQL{...}#` island's DML (and
GraphQL mutations later) land on the same layer, instead of each front end getting its own.

**Inputs:** three read-only surveys of 2026-10-18:
- lite's write machinery today;
- the TCK's remaining write cases;
- legend-pure 5.99.0 and legend-engine 4.145.0, read as spec.

§1 summarizes them. This builds on two earlier proposals:
- `docs/CYPHER_TCK_TO_ZERO_PLAN_2026_10_17.md`: W4, decisions 2, 5 and 6, questions Q5 and Q6.
- `docs/BI_AND_ETL_PLAN_2026_09_29.md`: F1, a `write` lowered to DML.

## 1. Where things stand

### Lite has no write path above raw SQL

- **No lowering emits DML.** The MIR's `SqlDml` has four records, all built by Java seed code:
  - `InsertValues`;
  - `InsertFromTable`, whose source is a staging table's name;
  - `DeleteAll`;
  - `CreateTempTableAs`, the one that already renders a `SqlQuery` inside a statement.

  `SqlDdl` has `CreateTable` (columns with a type, NOT NULL and PRIMARY KEY), `DropTable`, `CreateSchema` and
  `DropSchema`. Missing: UPDATE, DELETE … WHERE, INSERT … SELECT of a query, MERGE / ON CONFLICT, RETURNING, identity
  columns, defaults and foreign keys.

  `render(SqlDml)` returns text, and a bound parameter in a DML statement is refused (`AnsiSqlRenderer`:1629).
- **Pure's `write(rel, accessor): Integer[1]` type-checks but only counts rows** (`Render.writeCount`). A write to a
  store table throws "TypedWrite to a store destination is not yet implemented". No other write native exists
  (`mutateAdd` is walled).
- **The only write channel is raw SQL,** through the effect natives (`executeInDb`, …) and seeding.
  - Nothing reads an update count.
  - No production code calls `commit`, `rollback` or `setAutoCommit`.
  - A statement marked as writing makes the next statement reseed its test data.
- **The plan side only reads.** `ExecutionPlan.Node` has no DML node, and `PlanRunner` refuses `Sequence`.
- **Every island refuses writes:**
  - `#SQL`: "only queries are supported" (`SqlParser`:101).
  - `#IQ`: "only a SELECT is read".
  - `#Cypher`: "CREATE is not supported yet", for the first write clause, before anything else is translated.
- **No mapping is inverted.** A class mapping is a function over rows.
  - Keys exist as facts: `primaryKeyColumns`, and the `$pk:` and `$fk:` pseudo-bindings.
  - An association is a predicate; whether it is a foreign-key column or a junction table is implicit in its join
    chain.
- **GraphQL parses** (`#GQL{...}#`, mutations included), but the Typer calls it "parse-only surface".
- **The seam allows writes.** JDBC is allowed in `com.legend.exec`, the root package `com.legend`, server and
  testdatagen. The charter (C1.1) lets Java manage transactions and submit statements; C2.4 forbids
  Java-manufactured identities.

### legend-engine's writes are narrow

- **`relation::write(rel, accessor): Integer[1]` appends:** `INSERT INTO table SELECT …`, columns by position,
  returning the rows written.
- **`mutation::save(values, tree, mapping, runtime)` is the only write through a mapping.** It is a single-object
  upsert over plain column mappings of one root mapping's main table, with every primary key in the graph-fetch
  tree. Joins, DynaFunctions and enumeration mappings are silently dropped.
  - Upsert SQL exists for H2 and MemSQL only.
  - It returns a message string, not a count.
  - There is no delete through a mapping.
- **GraphQL mutations** are rewritten to `internalize → graphFetch → save`, so they inherit all of that.
- **The SQL layer parses INSERT, UPDATE and DELETE and refuses them** ("Unsupported Operation").
- **Persistence** (`###Persistence`) is an ingest pipeline: a service's rows are merged into a target table. Its ingest
  modes are useful vocabulary for later: append-only, snapshot, delta with a delete indicator, uni- and bitemporal
  milestoning, deduplication.

Nothing upstream is a design to copy. Its signatures (`write`, `save`) are verified spec that lite keeps where it
defines the same thing.

### The TCK's write work: 245 refused cases and 33 skipped

- **By first write clause:** CREATE 69, SET 53, MERGE 51, REMOVE 33, DELETE 30, DETACH DELETE 9.
- **Shape:**
  - 185 RETURN something after the write, 66 of them node, relationship or path values.
  - 84 mix writes with UNWIND, WITH or aggregation.
  - 164 MATCH before writing; only 2 read again after a write.
  - None uses FOREACH or a CALL subquery that writes.
- **What the TCK asserts:** 217 assert side-effect counts (+/- nodes, relationships, properties, labels), and 28 assert
  "no side effects". **The harness checks neither today.** It compares rows only, and the snapped answers record the
  graph before the query, never after. So the 60 cases that return nothing would pass if lite did nothing.
- **The 33 skipped cases** each write, then run a control query that reads the graph back. 18 of them are Temporal4,
  which stores temporal values and lists of them.
- **The harness's model can't hold most writes:**
  - The 80 empty-graph cases get no tables.
  - Many cases write a label, type or property the pre-graph lacks.
  - Label changes would move a node between classes.
  - A relationship's storage was chosen from the pre-graph's degrees.
  - 207 of the 245 share a pre-graph with other cases.
- **40 write scenarios expect an error** (33 at compile time, 7 at runtime). Lite agrees with Neo4j on them today only
  because it refuses the clause.

## 2. The design in one picture

```
  #Cypher over a domain model   #Cypher over a native graph   GraphQL mutation (later)   #SQL INSERT/UPDATE/DELETE
             |                            |                          |                           |
             v                            |                          v                           |
   L2  class writes over a direct (1-1) mapping: property -> column, key, FK column or junction row  |
             |                            |                                                      |
             |                            v                                                      |
             |                L2' the native store: labels, properties, relationships as rows    |
             v                            v                                                      v
   L1  store writes: write / update / delete / merge over a table accessor (#>{db.schema.table}#)
       lowering emits SqlDml (InsertSelect, Update, DeleteWhere, Merge); each dialect renders it
                                          |
                                          v
   L0  execution: one transaction per statement, update counts as values, rollback on error
```

- **Each front end translates to Pure,** as every island does today, and the one compiler lowers it. No island
  writes SQL.
- **`#SQL` DML lands on L1 directly,** because the SQL island already reads physical tables.
- **Cypher has two kinds of graph,** and a query is always on exactly one (§3).

## 3. Two kinds of graph

### A. A graph over a domain model (what the island reads today)

A label is a class, a property is a property, a relationship type is an association or an edge class. Writes keep
the model's shape:
- `CREATE` of a declared class (the database mints the key);
- `SET` and `REMOVE` of declared properties;
- links and unlinks of declared associations;
- `DELETE` and `DETACH DELETE`;
- `MERGE`.

Everything outside the model is refused by name:
- a label that names no class;
- a property the class doesn't declare;
- **a label added to or removed from a node: on a domain model a node's label is its class**, and an instance does
  not change class;
- a write to a class whose mapping is not direct (§4, L2).

This is the Legend value: Cypher over your model, with `#SQL` and GraphQL writing the same model under the same rules.

### B. A native graph store (new): lite as a graph database on any SQL database

There is no domain model. The platform owns one fixed schema, created once, that holds any graph:

```
nodes       (id PK)
node_labels (node_id, label)          -- a node's labels are rows
node_props  (node_id, key, value)     -- or one JSON column per node: leg 3 decides
rels        (id PK, type, src_id, dst_id)
rel_props   (rel_id, key, value)
```

Every Cypher write is an insert or delete of rows in these tables:
- `CREATE (:Robot {name: 'x'})` is a `nodes` row, a `node_labels` row and a `node_props` row.
- `SET n:Manager` inserts a `node_labels` row; `REMOVE n:Manager` deletes it.
- `SET n.nickname = 'A'` inserts or updates a `node_props` row.
- A relationship is a `rels` row, with its properties in `rel_props`.

Nothing needs declaring and nothing changes class. **This is how Neo4j itself works:**
- label names, relationship types and property keys are tokens created on first use;
- a node's record carries its label set and a chain of key/value property records, each value carrying its own type;
- a relationship's record carries its type and its two ends;
- constraints (uniqueness, existence, property types) are optional, restrict later writes, and never declare a
  schema.

So B emulates Neo4j by doing what Neo4j does. It needs no schema and no inference.

### How a query picks its graph

By the graph it names. `USE` already names a DataSpace (W1):
- `USE my::People`, a DataSpace over a model, is **A**;
- `USE my::Social`, declared as a native graph (a new store element), is **B**.

### How this relates to open and closed graphs

They are different questions. The backing (A or B) decides what a node is made of. Open or closed decides what a name
outside the schema means, so it only applies to A:

| | Reading an unknown name | Writing an unknown name |
|---|---|---|
| A, closed | compile error | refused |
| A, open (`<<graph.Open>>`, W1) | no rows, or null | refused: nothing to hold it |
| B | no rows, or null | a new row |

In GQL's own terms, an open graph has no graph type and takes any insert: that is B. A domain-model graph is GQL's
closed graph, typed by the class model. `<<graph.Open>>` on A means "lenient reads".

## 4. The layers

### L1: store writes

**Four verbs over a store accessor,** each returning `Integer[1]`, the rows affected (the database's update count, a
value it produced; charter C1.2):

| Verb | Pure | SQL | Notes |
|---|---|---|---|
| append | `rel->write(#>{db.t}#)` | `INSERT INTO t (cols) SELECT …` | upstream's native, lowered for real; columns by name |
| update | `#>{db.t}#->update(r | pred, ~[c: r | expr, …])` | `UPDATE t SET c = expr … WHERE pred` | lite native |
| delete | `#>{db.t}#->delete(r | pred)` | `DELETE FROM t WHERE pred` | lite native; `true` is `DeleteAll` |
| merge | `rel->merge(#>{db.t}#, ~[keys])` | `MERGE`, or `INSERT … ON CONFLICT (keys) DO UPDATE`, per dialect | lite native; an upsert by key |

- **Natives.** The lite natives are `meta::legend::lite::` functions, each moving the native governance ceiling
  with a dated justification. `write` stays upstream's (`meta::pure::functions::relation`), signature verified.
- **The MIR: extend what is there, nothing beside it.** New records follow `SqlDml`'s table naming (schema, table,
  `NameQuoting`, quoted columns):
  - `InsertSelect(table…, columns, SqlQuery)`, the general form of `InsertFromTable`;
  - `Update(table…, alias, assignments, where)`;
  - `DeleteWhere(table…, alias, where)`, of which `DeleteAll` is the `true` case;
  - `Merge(table…, SqlQuery source, keys, updates, inserts)`.

  Each record is pure data with a render arm per dialect. An arm that can't express its statement throws; there is
  no `default ->`.
- **Rendering with parameters.** `renderStatement(SqlDml)` returns a `RenderedStatement` (text plus its `?` binds),
  as queries have. The seed callers keep the text form.
- **DDL.** `SqlDdl.Column` gains an identity or sequence column where a store declares one (keys, D4).
- **The pipeline.** A write lowers to a `LoweredStatement`: a query, or a DML step with the query it reads.
- **Dialects.** DuckDB, Postgres and H2 first: the lanes that judge. The others refuse by name until each is measured.

### L0: execution

- **`Execution.executeWrite`** runs a statement's DML steps and its final query on one connection, in one
  transaction. It commits on success, returning the result and the counts, and rolls back on any error. The
  transaction calls live in `com.legend.exec`.
- **A caller-owned transaction is joined, not committed.** When the connection has autocommit off, the executor works
  inside the caller's transaction. The harness uses this to roll every case back.
- **The "a writing statement reseeds the next" rule** applies to test-data sessions only. A product write is never
  wiped.
- **Execution plans keep reading only for now.** A write plan node is a later leg.

### L2: class writes over direct (1-1) mappings (graph A, GraphQL)

**The verbs take a class or an association as the target.**
- `rows->write(m::Person)`, the rows' columns named by `Person`'s properties.
- `m::Person.all()->filter(p | …)->update(~[name: p | 'B'])`.
- `m::Person.all()->filter(p | …)->delete()`.
- `rows->merge(m::Person, ~[keys])`.

An association is written as its extent of pairs: a `write` links, a `delete` unlinks.

**Only direct mappings are writable.** Inverting mappings in general is the view-update problem, so a write requires
a direct mapping. Phase F checks this (it is never assumed). A mapping is **direct** when all of these hold:
- one main table, with no `~filter`, `~distinct` or `~groupBy`;
- not a union or operation, and not model-to-model;
- every property is a plain column of that table, or a value or embedded class made of its columns;
- no other class mapping uses the table;
- the key (`~primaryKey`, else the table's PRIMARY KEY) is known;
- every NOT NULL column is a property, the key, or minted.

A direct class write is a store write with property names renamed to columns.

| A direct mapping has | A write becomes |
|---|---|
| a plain column property | that column |
| an embedded or value class | its columns, in the same row |
| a key | the row's identity; required for update, delete, merge and links |
| an association over a foreign-key column (a to-one end) | an `UPDATE` of that column, or setting it to null |
| an association over a junction table of the two keys | an insert or delete of a junction row |
| an edge class over a bridge table | an insert or delete of a bridge row |

- **Everything else is refused, with the reason** ("`C` is not writable: its mapping filters its table").
- **Writability is Knowledge.** Phase F records each class's write shape; the DML is Work, built on demand.
- **Two later extensions:**
  - a user-written inverse (a Pure function over L1) for a mapping that isn't direct;
  - upstream's `save`, defined over `merge` and verified against its signature.

**Keys are minted by the database** (C2.4): an identity or sequence column where the store declares one; otherwise
`COALESCE(MAX(key), 0) + ROW_NUMBER() OVER ()` inside the statement. The latter is registered as unsafe under
concurrent writers.

**Staging gives read-your-own-writes.** A write clause materializes its rows, minted keys included, into a session
temp table (`CreateTempTableAs` exists). It applies its DML from that table, and the clauses after it read the temp
table. So `CREATE (n {name: 'foo'}) RETURN n.name` returns the written row, and a later `MATCH` sees it.

### L2': the native store (graph B)

- **A platform-owned schema** (§3 B), created by the platform's DDL. Every write is a `write` or a `delete` of its
  rows, and every read filters them.
- **Property values carry their type, as in Neo4j.** They are stored as Variants, and comparison and ordering use the
  Variant machinery already built (W7, W11).
- **The open question (leg 3):** can B be a platform-owned class model that the existing translator already reads?
  For example `Node` with `labels: String[*]` and Variant properties, over those tables, with a platform-defined
  inverse for its writes.
  - If yes, B is a new resolution rule for labels and properties: contained work.
  - If no, B is a second Cypher backend: much bigger.

## 5. The front ends

### Cypher

Each clause runs once per row of the rows in hand, as Neo4j's do.

- **`CREATE`:** a `write` of the node (A: to its class, with a minted key; B: `nodes`, `node_labels`, `node_props`
  rows). A relationship is a link or a bridge row (A), or a `rels` row (B).
- **`SET` and `REMOVE` of properties:** an `update` of the property (A: its column; B: its `node_props` row).
  - `SET n = map` replaces the properties: those the map names are written, the others nulled.
  - `SET n += map` updates only the properties the map names.
  - Writing `null` removes the property.
- **`SET n:L` and `REMOVE n:L`:** B inserts or deletes the `node_labels` row. A refuses by name.
- **`DELETE`:** deletes the row. Neo4j's DeleteConnectedNode is raised by the database, through a guarded statement,
  while relationships remain. **`DETACH DELETE`** unlinks first, then deletes.
- **`MERGE`:** a read of the pattern, then a create of what the read lacks, in the same statement. `ON CREATE SET` and
  `ON MATCH SET` are updates scoped to the created or the matched rows. A MERGE over a null property raises
  MergeReadOwnWrites.
- **The 33 compile-time write errors** (VariableAlreadyBound, UndefinedVariable and others) come from the static
  checks the island already runs, extended to the write clauses' variables.

### `#SQL` (L1)

- `INSERT INTO t [(cols)] VALUES | SELECT` becomes `write`.
- `UPDATE … WHERE` becomes `update`; `DELETE … WHERE` becomes `delete`.
- `INSERT … ON CONFLICT … DO UPDATE` becomes `merge`, and `DO NOTHING` is `merge` with no updates.
- `RETURNING` reads the staged rows back.
- **The judge:** the table's state after the statement, compared with Postgres 16's (the SQL parity harness's oracle).
- **Refused by name:** data-modifying CTEs and multi-statement scripts.

### GraphQL (later, L2)

A mutation is a `merge` or a `delete` over a class by key, its input object the row. That covers legend-engine's
`save`, and more. It needs the `#GQL{...}#` read side compiled first.

## 6. The judge

**The judge lands first, so a write case can never pass by doing nothing.**

- **Neo4j is the verdict only, never the schema.**
  - `snap.py` runs each case's query in an explicit transaction and commits it: Neo4j checks some writes only at
    commit (a node deleted with its relationships left raises then), and the next case empties the graph. For a query
    Neo4j's summary marks as writing, it dumps the graph after (each element the graph before held keeping its
    ordinal), then runs the control query over it. (The first draft rolled back; the commit-time check made it
    commit, measured on the DELETE scenarios, 2026-10-18.)
  - The answers file gains Neo4j's post-graph (`after`) and the control query's answer (`control`). The side effects
    are the diff of the two graphs, computed the TCK's way, and `snap.sh --check` holds that diff to every TCK
    side-effect table (all 248 agree, and the 2935 "no side effects").
  - Nothing in lite's model is taken from them.
- **The schema on A is lite's own inference,** from two sources it owns:
  - the pre-graph's data, as today;
  - the query's write clauses, typed by lite's own typer: every label, relationship type and property key a CREATE,
    SET or MERGE names, each property typed by the expression written into it (`{num: 1}` is an Integer; `{num: x}`
    takes the UNWIND element's type).

  Two fallback rules cover what that leaves open:
  - a property whose type the writes don't fix (only ever null, or several types) becomes a Variant column, as W7's
    two-typed properties are;
  - a relationship type the pre-graph lacks becomes a junction table (any number of links), or an edge class when the
    query writes its properties.
- **B needs no schema.**
- **Each case runs in a transaction the harness owns:** the statement through the island, then the post-graph read
  back through the island (the oracle's own dump queries), then the side effects (lite's diff of its two reads),
  then the control query if any. Then it rolls back, so no case leaks into the next. The graph before is read back
  once per seeded graph and must be Neo4j's dump, or the judge is broken there (a JUDGE problem fails the lane).
- **The verdict has four parts:**
  - the rows, as today;
  - the side-effect counts, lite's diff against Neo4j's (which is the TCK's table);
  - the post-graph, against Neo4j's, compared structurally;
  - the control queries' rows.
- **Write errors** are judged as BOTH_ERROR is today, but against the error raised, not a refusal.
- **Every write case runs on both kinds of graph,** each over DuckDB and Postgres:
  - **On A,** the cases that change a node's labels are decided as refused (a register row: "on a domain model a
    node's label is its class").
  - **On B,** all 245 run, with the 33 control queries.

## 7. The decisions (2026-10-18)

1. **D1. Layering:** L1 store writes under L2 class writes and the native store, every front end translating to Pure.
   *Agreed.*
2. **D2. Vocabulary:** upstream's `write` lowered for real, plus lite's `update`, `delete` and `merge`. The same verbs
   take a table accessor (L1) or a class or association (L2). *Proposed, unchallenged.*
3. **D3. Writable mappings: direct (1-1) only,** checked in phase F and refused by name otherwise. *The user's call.*
4. **D4. Keys minted by the database only:** an identity or sequence, else max plus row number inside the statement
   (registered as unsafe under concurrent writers). *Proposed, unchallenged.*
5. **D5. Two kinds of graph:** A (domain model, schema-first) and B (native store, schemaless). A query names its
   graph. *Agreed.*
6. **D6. Labels:** on A a label is the class, and label changes are refused by name. On B labels are rows. The
   label-set row move of the first draft is dropped. *Agreed.*
7. **D7. One transaction per statement,** writes staged through session temp tables, rollback on error, a caller's
   transaction joined. *Proposed, unchallenged.*
8. **D8. The judge first.** Neo4j is the verdict only. A's schema is lite's own inference (§6), B needs none. Per-case
   rollback, the four checks. *Agreed (the user: "we can't use Neo4j, have to do our own inference").*
9. **D9. Order** as §8.
10. **D10. Ownership:** this line builds L1's MIR and render arms in the database owner's files, announcing each in
    `docs/IN_FLIGHT.md`. *Proposed, unchallenged.*

## 8. Legs

1. **The judge (harness only).**
   - `snap.py`: the post-graph and side effects in a rolled-back transaction, recorded in the answers file.
   - `CypherParity`: per-case transaction and rollback, the side-effect counts, the post-graph compare, the control
     queries.
   - `CypherGraph`: A's schema from the pre-graph plus the query's typed writes.
   - The measured size of each group: writes inside the pre-graph's schema, writes that need the inferred schema,
     label changes.
   - The 245 stay REFUSED, but none can pass vacuously.
2. **L0 and L1.**
   - The four `SqlDml` records, with DuckDB, Postgres and H2 arms and `renderStatement`.
   - `write` lowered for real; `update`, `delete` and `merge` declared and lowered.
   - `Execution.executeWrite`, with transactions and update counts.
   - Per-dialect tests, including that a failed write leaves the table unchanged.
3. **The spike: the native store as a platform class model** (§4, L2'). It decides B's cost and its legs.
4. **`#SQL` DML** on L1, judged by Postgres 16.
5. **L2 and Cypher on A:**
   - the direct-mapping check, the property-to-column rename, key minting, staging;
   - then CREATE, SET/REMOVE of properties, DELETE/DETACH DELETE, MERGE.
6. **B and Cypher on B:** the platform schema, then every write clause, label changes included; the 33 control queries
   run here.
7. **Later:**
   - write plans in `ExecutionPlan`;
   - GraphQL mutations, once `#GQL{...}#` compiles;
   - Persistence-style ingest modes over `merge`.

**Expected end state:**
- the TCK at 3878 of 3880 (all but the 2 signed years) on B;
- on A, every write case except the label changes;
- every write judged on rows, side effects, the post-graph and the control queries;
- `#SQL` DML running on Postgres, DuckDB and H2.
