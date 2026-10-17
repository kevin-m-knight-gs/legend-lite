# The Cypher TCK to zero — a holistic plan, 2026-10-17

**Status: a proposal for the user's decisions (§3), written at the forty-third landing of the `#Cypher{...}#`
line (`docs/GATES.md`; the ledger: `docs/CYPHER_SHOWCASE.md` §20).** The user's ask: "can we come up with a holistic
plan to pass *all* TCK tests?", after three corrections to the line's standing claims (§0). Nothing here is decided
until the user says so; the register rows it would overturn are named where they fall.

## 0. Three corrections, and what "zero" means

The openCypher TCK is a conformance kit: every scenario is written to be passable by a conforming implementation,
with a deterministic outcome. Three claims this line made about "unwinnable" or "decided" scenarios do not survive
the user's questions of 2026-10-17:

1. **`rand()` and the clock functions are passable.** The 31 `rand()` scenarios shuffle a list with `rand() > 0.5`
   and assert an invariant that holds whatever the draw (`single(x IN list WHERE p) = (size([...]) = 1)` is
   `true`); the 19 clock scenarios assert `duration.inSeconds(datetime(), datetime())` is `PT0S` or `f(null)` is
   null. The island refused them "as a value of the call, not of the data"; the database's own `random()` and
   clock answer them as Neo4j does. No oracle problem: the judge compares the deterministic final rows.
2. **A property no class declares can read as null.** The island made it a compile error (C4, register S58: "the
   graph is the model"). Neo4j's graph is schema-optional: a missing property is null, a label no node carries
   reads no rows, a relationship type nobody has reads no rows. The island can spell all three statically (the
   model tells it the property is declared by no candidate class), so the choice is a rule, not a limit.
3. **Identity is available.** C5 was read as "no physical key may leak into the model"; the user's rule is that
   keys can be hidden where needed, not that they must be absent. The parity harness already gives every node
   table an `id BIGINT PRIMARY KEY` and every relationship table `src_id`/`dst_id`; the platform resolves a
   class's `<<equality.Key>>` properties at compile time (`compiler/element/EqualityKeys`). Node identity is a
   hidden key property; nothing is approximated by comparing user properties.

**"Zero" means:** every one of the 3880 TCK scenarios is `PASS` (rows match Neo4j's cell for cell) or
`BOTH_ERROR` (Neo4j errors and lite errors too), on both parity lanes; `SKIP` is 0 (the harness models every
scenario); the island refuses nothing the TCK asks. Refusals by name may remain for what the TCK never asks.

## 1. The inventory: the 849 non-passing cases, by what they need

The census of `cases-rows44b` (the forty-third landing): PASS 2361, BOTH_ERROR 670, REFUSED 668, SKIP 181. The 849
non-passing cases fall into twelve workstreams. "Where" says who changes: the island (`protocol/cypher`), the
harness (`testcases/CypherGraph`, `CypherTck`, `CypherValues`), or the platform (lowering, resolver, dialects,
store grammar, executor).

| # | workstream | cases | today's reason | where | rule to change |
|---|---|---|---|---|---|
| W1 | Schema-optional reads | 56 | a label naming no class (42), a property no class declares (10+2), a type naming no association (2): C4 | island | S58 |
| W2 | Identity | ~100 | OPTIONAL MATCH after rows (36), both ends bound (11), undirected self-association (15), a node among grouping keys (8), nodes compared (7), a MATCH binding a projected variable again (8), untyped relationship in OPTIONAL MATCH (7), RETURN DISTINCT over a node (3), two relationships of one type (3), the rest ones and twos; plus S75's uniqueness and S74's parallel links | island, harness | C5 as read, S74, S75 |
| W3 | Non-deterministic functions | 50 | `rand()` (31), `datetime()`/`date()`/`time()`/`localtime()`/`localdatetime()` and their `.transaction`/`.statement`/`.realtime` forms (19) | island | the "value of the call" rule |
| W4 | Writes | 274 | CREATE 69, SET 53, MERGE 49, REMOVE 31, DELETE 30, DETACH DELETE 9; 33 write scenarios the harness skips for their control query | platform (F1), island, harness | — |
| W5 | Stored temporal values | 72 | properties holding a time of day (10 scenarios), a duration (8), a zoned date-time (5): 61 skipped; a date-time of sub-microsecond precision: 11 skipped | harness, store grammar, island | S64 |
| W6 | Nodes with several labels | 18 | the harness skips `(:A:B)` | harness, island | — |
| W7 | A property typed two ways | 5 | `var: 1` on one node, `var: 'a'` on another of the same label (4), two types differing only in case (1) | harness, island | — |
| W8 | Procedures | 50 | `CALL test.my.proc(...)` declared by the scenario with its signature and result rows | harness, island | — |
| W9 | Lowering and resolver limits | 49 | a fan-out to a class without a property (21: `project` of a navigation mapped to a constant stays one row per root), a hop past an edge class (15: the resolver's nested navigation), `%` inside a database lambda on Postgres (9), ORDER BY on a non-returned expression without SKIP or LIMIT (4) | platform | — |
| W10 | Variable-length without a bound or past two hops | 25 | the unroll stops at a bound of 4 and two hops in all; no bound needs a recursion | platform (a recursion native), island | — |
| W11 | Variants of no known shape | 53 | a list comprehension over a Variant (26, all also `rand()`), ORDER BY a Variant (18), two Variants or two computed lists compared (9) | island, judge | — |
| W12 | The tail | ~45 | pattern comprehensions over rows (11) and over two hops (3), `count(DISTINCT)` (6), `toString` of lists and nodes (5), min/max over mixed values (6), untyped relationships beside other patterns (4), SKIP/LIMIT expressions (2), text predicates on non-strings (4), temporal texts of the row (4), and singletons | island | — |
| — | Setup oddities | 3 | Neo4j refused the setup | harness | — |

The sum exceeds 849 because W11's comprehension cases are also W3's, and W2 unlocks cases counted under W12.

## 2. The workstreams

### W1. Schema-optional reads (S58 overturned) — LANDED as open graphs, 2026-10-17 (the study's §12.45: TCK agreement 3031 → 3093, 62 scenarios; the declaration site reopened by the user, §3.2 Q1 addendum)

A property declared by no candidate class of a pattern variable reads the known null (the island already reads a
property an arm's class lacks as the known null, §12.40.2: the same mechanism, one step wider). `MATCH (n:Nope)`
over a model with no such class reads no rows (the first-clause form already exists for an OPTIONAL MATCH over no
class, §12.20); a relationship type naming no association end or edge class reads no rows (the pattern's "no way"
form, §12.36). `n.missing` inside a comparison folds as the known null does today. Neo4j's static errors stay
static errors (a property on a non-map). One leg; the compile-error form kept only behind a query option if the
user wants the typo guard back (not proposed). **Reframed 2026-10-17 (§3.2 Q1): the open reading is the OPEN
graph's, GQL's own distinction; the closed graph keeps C4, so the typo guard stays the default of a user's model
and no query option is needed.**

### W2. Identity (C5 as the user meant it) — the design settled 2026-10-17 (§3.2 Q2 and its three addenda)

**Two questions, two rules, as Legend already keeps them.** Value equality (`==`, `equal`, `in`) is Pure's rule,
unchanged: by the `<<equality.Key>>` properties when the class declares them, else by identity. Identity (`eq`: "the
same instance") is what Cypher means by `a = b` on nodes and relationships, by DISTINCT, grouping, `IN` over nodes
and relationship uniqueness; two nodes named Ann are two nodes. `<<equality.Key>>` is never in the identity chain:
it states meaning ("same name, same person for comparison"), identity states the store ("rows are distinguishable
by this key"); using the first for the second would merge distinct nodes exactly on the models that declare keys.

**The identity of an instance, in order, with no fallback past the last step (the engine's own precedence, to be
verified against its compiler source in the leg, per the verification tenet):**

1. a constructed instance (`^Person(...)`, the PCT lane): the `__id` the lowering mints per construction site
   (exists);
2. a mapped instance: the class mapping's `~primaryKey(...)` when declared;
3. else the main table's `PRIMARY KEY` columns from the store's DDL (a relational class mapping has exactly one
   main table and its instances ARE that table's rows: joins inside property mappings fetch columns for the same
   instance or reach other classes' instances, and a projection's exploded rows still carry the main row's key,
   which is how the engine's graph fetch rebuilds objects; a relation-function mapping's key is the engine's
   inference over its expression, `PkInference`, already lite's);
4. else a compile error naming the class ("no identity"): never all mapped columns, never `<<equality.Key>>`.

**Who does what.** The PRODUCT: one native, `meta::legend::lite::elementId(x: Any[1]): String[1]` (Cypher's own
`elementId(n)` and `id(n)`), lowered to the instance's identity as a value (the key columns, a composite spelled as
text), the only expression Pure lacks for "the identity of this mapped instance"; the same `__id` carrier on the
value lane later unifies `eq` for mapped and constructed instances. The HARNESS: nothing (its tables declare
`id BIGINT PRIMARY KEY`; step 3). A USER: nothing beyond a `~primaryKey` or a table key. The hidden-key property
and the `graph.hidden` marker of the first draft are withdrawn: there is no key property to hide.

**The island.** Node comparisons are `elementId($a) == elementId($b)`; a carried node keeps its identity as a
column; DISTINCT, grouping and `IN` over nodes use it; a MATCH or OPTIONAL MATCH after rows from a carried node
re-anchors by a JOIN on identity (`Class.all()` joined on `elementId`), which also retires the resolver's depth
limit from the translator's side (chains past two steps, variable-length unrolls past two hops, hops from a node
reached through an edge class: every hop beyond what the resolver navigates becomes a join on the id over
materialized rows); both ends bound is the far identity compared with the bound node's; an undirected hop over a
self-association dedupes by identity; relationship uniqueness along a chain is the relationships' identities
pairwise distinct (an edge class's own; a plain association's the ordered pair of node identities, S74; S75
overturned); `collect(DISTINCT n)` and `count(DISTINCT n)` count identities. A node value carries `"@id"` in its
JSON, which the judge ignores (Neo4j's ids are its own).

Legs: the native and its lowering (platform, under its guards); node identity in the island (comparisons,
DISTINCT, grouping, IN, values); re-anchoring joins (continuations, cycles, deep chains); uniqueness and the
undirected self-association.

### W3. Non-deterministic functions

`rand()` is the database's random float in `[0, 1)` (every dialect has one); `datetime()`, `date()`, `time()`,
`localtime()`, `localdatetime()` with no argument are the database's clock at the kind the island already carries,
their `.transaction`/`.statement`/`.realtime` forms the same (one clock per statement is what the TCK asserts:
`duration.inSeconds(datetime(), datetime())` is `PT0S`; the island reads the clock once per statement and reuses
it, as Neo4j's `statement` clock does). The lite cases pin only predicates (`rand() >= 0`, `date() = date()`).
One leg.

### W4. Writes (the largest)

- **The platform (F1 in `docs/BI_AND_ETL_PLAN_2026_09_29.md`).** `write` lowers to DML; the MIR's `SqlDml` grows
  `Update(table, set, where)`, `DeleteWhere(table, where)` and `InsertSelect`; each dialect renders them; the
  executor runs a statement's DML in one transaction and returns the counts. The `write` native itself is the
  relation form (`rel->write(#>{db.t}#)`); the island needs the row forms beside it.
- **The island.** `CREATE (n:A {props})` inserts a row with a minted key (W2's hidden id: `max(id)+1` in the
  statement, or the database's sequence); `CREATE (a)-[:T]->(b)` inserts the junction or edge row from the bound
  nodes' keys; `SET n.p = v`, `SET n += {...}`, `SET n:Label` update the row (a label added is a row in the
  label's table sharing the id — W6's representation); `REMOVE n.p` sets null, `REMOVE n:Label` deletes that
  table's row; `DELETE n` deletes the row (an error while relationships remain, as Neo4j's); `DETACH DELETE`
  deletes the relationship rows first; `MERGE` is a read, then a `CREATE` of what the read lacks, in one
  statement (`ON CREATE SET`, `ON MATCH SET`). A write clause after reads runs per row of the rows in hand; a
  query mixing writes and a RETURN returns the rows after the writes.
- **The harness.** The TCK's write scenarios assert side effects (`+nodes 1, +relationships 2, +properties 3,
  +labels 1`, and the minuses) and sometimes a control query's rows. The harness counts nodes, relationships,
  labels and property values before and after (a query per table), and runs the control query through the
  island. Neo4j's side effects are snapped as today's answers are.
- Six to ten legs after F1, in their own PR.

### W5. Stored temporal values (S64 revisited)

- A time of day in the store: lite's relational grammar adds a `TIME` column type (a lite extension: legend-
  engine's grammar has none, which S64 records), rendered by every dialect; the harness declares one for a
  `localtime`/`time` property, the island reads it as the `StrictTime` it already carries (a zoned time keeps its
  offset in a second column, as a zoned date-time keeps its instant and offset). Or, without touching the grammar,
  a text column read as a time by the property's declared type: the plan prefers the column type, the honest
  representation.
- A duration property: the `g::Duration` class the harness already declares for computed durations, as a Variant
  column holding the four slots.
- A zoned date-time property: the instant in a `TIMESTAMP` column and the offset beside it, as the island carries
  zoned values.
- A date-time of sub-microsecond precision: a `TIMESTAMP_NS` column type where the database has it (DuckDB), the
  Postgres lane refusing by name as the nanosecond leg does.
- Two to three legs.

### W6. Nodes with several labels

`(:A:B)` is an instance of a class `A_B` extending `A` and `B` (Pure's multiple inheritance), stored in its own
table with the shared id; `MATCH (n:A)` reads `A` and its subclasses (the island unions the subclass tables as it
unions arms today; whether the relational mapping reads subclasses through `A.all()` is to be verified first);
`labels(n)` is the class's label set; `n:B` is a subclass test. Two legs.

### W7. A property typed two ways

A property whose values differ in kind across the nodes of one label is a Variant column (as a list property is,
§12.37), read by kind through W11's shapes; a comparison across kinds is Neo4j's (false or null). One leg, with W11.

### W8. Procedures

The scenario declares each procedure's signature and its result rows (`there exists a procedure test.my.proc(in
:: INTEGER?) :: (out :: INTEGER?): | in | out | ...`). The harness models a procedure as a table of its rows (a
relation the model declares) and its signature; the island reads `CALL proc(args) YIELD cols` as the rows whose
inputs equal the arguments, joined to the rows in hand, and raises Neo4j's static errors for the wrong arity, a
missing parameter or a yield of nothing. Two legs.

### W9. Lowering and resolver limits (outside the island)

- `project` of a to-many navigation mapped to a constant keeps one row per root: fix in the lowering so the join
  fans out whenever the navigation is to-many (21 cases).
- The resolver's nested navigation past a second association step (15 cases): extend the resolver's
  property-chain rewrite to any depth.
- `rem` inside a list lambda on Postgres types its divide-by-zero guard as text (9): the dialect's arm.
- A sort key that is not an output column without a SKIP or LIMIT (4): the render's subquery form.
- Four legs, each in the platform's own guards, with the database owner.

### W10. Variable-length without a bound

A recursion native in the MIR (`WITH RECURSIVE` on every dialect that has it; a bound of the recursion's depth as
the dialect's guard), the island spelling an unbounded or deep pattern as the recursion over the association's
join, uniqueness by W2's keys. Two to three legs, after W2 and W9's resolver work.

### W11. Variants of no known shape

- ORDER BY a Variant list: Cypher orders values across types by a type rank, then within the type; the island
  spells a sort key of (rank, value) over a Variant's elements, pairwise, as §12.34 orders known lists. Two legs.
- Two computed lists or two Variants compared: element-wise with Cypher's null rules over the Variant's elements
  (the JSON null distinguished from a missing element). One leg.
- List comprehensions over a Variant of no known element kind: the elements read as Variants, each operation
  over a Variant element spelled by W11's typed reads. One leg (with W3, these are the quantifier scenarios).

### W12. The tail

Pattern comprehensions over rows and over two hops (W2's continuation, then the hop machinery), `count(DISTINCT)`
over scalars (the distinct count: order-free, unlike `collect(DISTINCT)`), `toString` of lists and nodes (the
JSON text), min/max over mixed values (Cypher's type rank, with W11), untyped relationships beside other patterns,
SKIP/LIMIT expressions, `STARTS WITH` on non-strings (null), temporal texts of the row. Three legs.

## 3. Decisions asked of the user

1. **W1:** adopt Neo4j's schema-optional reads (null, no rows) for the island, overturning S58? (Proposed: yes.)
   **Reframed by the user, 2026-10-17 (§3.2 Q1): GQL's OPEN and CLOSED graphs.** An open graph reads an unknown
   label or relationship type as no rows and an undeclared property as null; a closed graph keeps C4's compile
   errors. The harness's graphs are open (a `CREATE` with no schema); an island embedded in Pure source is
   closed; S58 becomes the two readings. Agreed.
2. **W2:** node identity through a hidden `<<equality.Key>>` property the harness declares, relationships as
   edge classes wherever a setup holds parallel links; S74 and S75 rewritten? (Proposed: yes.)
3. **W3:** `rand()` and the clock functions as the database's? (Proposed: yes.)
4. **W5:** a `TIME` column type added to lite's relational grammar (a lite extension of legend-engine's), or
   times of day stored as texts? (Proposed: the column type.)
5. **W6:** a node with several labels as a subclass of each label's class? (Proposed: yes, after verifying
   subclass reads.)
6. **W4:** writes in their own PR after PR #50, F1 first, judged by side effects? (Proposed: yes.)
7. **W9, W10:** this line may change the lowering, the resolver and the dialects under their own guards, or
   hands the four findings to the database owner? (Proposed: this line, one leg each, each with its guard.)

### 3.1 The decisions weighed (2026-10-17, on the user's ask)

**1. Schema-optional reads (W1, 56 cases).** For: Neo4j's rule, which a Cypher user expects; the island already reads
a property an arm's class lacks as the known null, so the change is one step wider and one leg. Against: a typo in a
property name (`p.nmae`) answers nulls where today it fails at compile time; that guard was the reason for C4 and is
in the spirit of "no fallbacks". Cost: one leg; S58 rewritten. Alternatives: (a) keep the compile error by default
and take Neo4j's semantics under a per-query option the harness sets, which keeps the guard for users at the price of
two behaviours to test and a TCK that certifies the option, not the default; (b) a middle rule, no rows for an
unknown label (schema-optional) but an error for a property no class of the whole model declares (the true typo
signal), which keeps 10 of the 56 cases refused. Recommended: Neo4j's rule, one behaviour; (a) if the guard matters
more than one behaviour.

**2. Identity through hidden keys (W2, ~100 cases, S74 and S75).** For: identity is what Neo4j's node is, and the
pieces exist (the harness's `id` columns, the platform's `<<equality.Key>>` resolution); nothing is approximated by
comparing properties; writes need a minted identity anyway. Against: a user model must declare a key per class to
get identity-dependent queries (a keyless class keeps today's refusals); a hidden-key stereotype is one more lite-only
meaning on the graph profile; parallel relationships as edge classes make every hop over them two steps and meet the
resolver's depth limit (W9) on chains. Cost: three to four legs plus harness and register. Alternatives: (a) the
lowering's own row identity (a class instance's mapped primary key, exposed to the island as an expression): no model
change, works for any class with a mapped key, but it is the database owner's line and a class mapped without a key
has none; (b) identity by row address (`rowid`, `ctid`): unstable across joins and unions, no; (c) identity by all
properties: an approximation, two equal nodes merge, no. Recommended: the hidden key property now, with (a) as the
later migration if the owner provides it.

**3. `rand()` and the clock as the database's (W3, 50 cases).** For: the TCK's invariants hold for any draw or
instant; every dialect has a random and a clock; one leg. Against: a query's rows can differ run to run, so lite's
own cases may pin only invariants (`rand() >= 0`, `date() = date()`); the clock forms differ (Neo4j's
`.statement`/`.transaction` are one instant per statement, `.realtime` per call): Postgres's `now()` is per
transaction and `clock_timestamp()` per call, DuckDB's `now()` per transaction. Cost: one leg. Alternative: seed the
database's random per lite case (`setseed`, both databases have it) so a lite case can pin a drawn value; a
nicety, not needed for the TCK. Recommended: adopt.

**4. Stored times of day: a `TIME` column type, or an existing type under a marker (W5, 72 cases).** (a) Add `TIME`
(and a nanosecond timestamp) to lite's relational store grammar: honest typing, each dialect's DDL; but a model using
it no longer compiles in legend-engine or Studio, which the graph profile was careful to keep. (b) Keep the grammar:
a time of day as a `TIMESTAMP` on the epoch day under a `graph.timeOfDay` marker (the convention the nanosecond leg
and the judge already use for computed times), a duration as the `g::Duration` Variant the harness already declares,
a zoned date-time as its instant plus an offset column, a sub-microsecond date-time as `BIGINT` epoch nanoseconds
under a marker; the island reads each by its marker. (c) Texts in `VARCHAR` with a transform: orders only when every
value has one precision, and writes must format. Cost: (a) about two legs, (b) one to two, (c) two. Recommended: (b),
reversing the proposal above: the same markers-give-meaning approach as the graph profile, and no grammar divergence.

**5. Nodes with several labels (W6, 18 cases).** (a) A class per label set, `A_B extends A, B`, `MATCH (n:A)` reading
`A` and its subclasses: Pure-native, `labels(n)` the class's set, `n:B` a subtype test; but every label set must be a
class before the query runs (a `SET n:B` on a node of `A` alone moves its row to `A_B`, which the model must already
hold), the subclass tables multiply, and whether the relational mapping reads subclasses through `A.all()` is to be
verified first. (b) A class per label and a node's row in each of its labels' tables under one hidden id (decision
2): `MATCH (n:A)` reads `A`, `labels(n)` is the id's presence across the label tables, `SET n:B` inserts a row and
`REMOVE n:B` deletes one; but a property of a node with two labels must live in one table and be found from the other,
so the harness declares it on every class the node carries (duplicated columns) or the island reads across tables by
id. Cost: two legs either way. Recommended: (a) for reads now; (b) is the better shape once writes land, so decide
with decision 6 in view.

**6. Writes in their own PR after F1, judged by side effects (W4, 274 cases).** (a) F1 first: Pure's `write` and the
DML the MIR lacks (`Update`, `DeleteWhere`, `InsertSelect`), each dialect, one transaction per statement; the island's
clauses on top: one compiler, and the ETL sinks of the BI plan get it too. (b) The island emitting DML plans of its
own beside its Pure: faster to start, but a second output path from one island, against the one-compiler tenet.
Judging: the TCK asserts side-effect counts (`+nodes 1, +properties 2`) and sometimes a control query; a stronger
lite-only judge compares the whole graph after the write with Neo4j's (nodes by labels and properties, relationships
by endpoints, type and properties). Cost: F1 is the BI plan's "L"; the island six to ten legs. Recommended: (a),
side-effect counts first, the whole-graph judge for lite's cases.

**7. Who changes the lowering, resolver and dialects (W9, W10, 74 cases).** The four findings are platform work:
`project` of a to-many navigation mapped to a constant (lowering, medium), the resolver's nested navigation past a
second association step (resolver, medium), `rem`'s guard typed as text inside a Postgres lambda (dialect, small), a
sort key that is not an output column without SKIP or LIMIT (render, small); the recursion native for unbounded
paths is a feature (a MIR node, every dialect, a depth guard), large. (a) This line takes the four under the
platform's guards (ArchitectureTest, the PCT suites, the corpus lanes): no waiting, but it touches the one compiler
every island and PCT runs on, and the heavy lanes run only in CI. (b) The database owner's line takes them: ownership
kept, at the queue's pace. Recommended: this line takes the two small ones and the fan-out with the owner's review,
the resolver depth too; the recursion native stays a planned platform item with the owner.

**A cross-cutting alternative, not recommended:** a "Neo4j compatibility mode" switch that turns decisions 1 and 3
on for the harness and leaves lite's stricter defaults for users. It would keep every present guard, but it doubles
the behaviours to test and the TCK would then certify the mode rather than the island; the island is Cypher, and
Cypher's semantics are its contract.

### 3.2 The user's six questions, answered (2026-10-17)

**Q1. GQL's open and closed graphs, for decision 1.** GQL (ISO/IEC 39075:2024) calls a graph *closed* when a
graph type constrains it (it holds only the node and edge types the type declares, with their labels and property
types) and *open* when none does (any label, any property, created on the fly). Neo4j's graphs are open: a label or
a property exists the moment a node carries it. A lite graph is a model, and a model IS a graph type (classes with
typed properties, associations as edge types), so every lite graph is closed by construction and C4 is the closed
reading: a label no class has, a property no class of the label declares, a relationship type naming no association
end is a compile error. Adopting GQL's pair: a graph declared OPEN reads an unknown label or relationship type as no
rows and an undeclared property as null (Neo4j's answers; the island already reads a property an arm's class lacks
as null, so the rule is one step wider); a CLOSED graph keeps C4. The verdict asked for: yes, and it is a better
frame than the "compatibility mode" §3.1 rejected, because openness is a property of the GRAPH, in the standard's
own words, not a switch on the engine; the TCK's fixtures are open graphs (a `CREATE` with no schema), so the TCK
then certifies the open reading of an open graph, which is what it measures on Neo4j, and a user's model stays
closed unless it says otherwise, so the typo guard (`p.nmae`) is lost nowhere by default. Where it is declared: GQL
declares it on the graph; lite has no graph element yet (the model is the graph), so the translator's `Graph` takes
its openness from its caller: the parity harness builds its model from a scenario's `CREATE` and passes OPEN; an
island embedded in Pure source (`#Cypher{...}#`) is CLOSED. The one open point: a model-side spelling of openness
(a graph element, which the writes of W4 may want anyway as the thing `CREATE` writes into, or a tag of the graph
profile) when a user wants an open graph; not needed for the TCK. Cost: one leg, the closed pins kept and the open
reading pinned beside them; S58 rewritten as the two readings. **Addendum (the user, later the same day): the graph
is declared with Cypher's own `USE`, naming a DataSpace (GQL's graph: its elements the labels, its execution context
the store), open by `<<graph.Open>>` on the DataSpace; neither a class stereotype (static, one way per model) nor
an island prefix (invented syntax). Landed so (the study's §12.45).**

**Q2. The identity problem, and the right thing on the Legend side.** What Neo4j does: every node and relationship
has an identity of its own (an element id the store mints), independent of its labels and properties. Two nodes
created alike are two nodes: after `CREATE (:A {x: 1}), (:A {x: 1})`, `MATCH (a:A), (b:A) WHERE a <> b RETURN
count(*)` is 2, `MATCH (a:A) RETURN count(DISTINCT a)` is 2, and `collect(a)` holds two. Identity is what `a = b`
and `a <> b` compare on nodes and relationships, what `DISTINCT`, the grouping keys, `count(DISTINCT n)`, `n IN
list` and `MERGE`'s matching use, and what relationship uniqueness is (one match never uses the same relationship
twice: `(a)-[r1]->(b)-[r2]->(c)` has `r1 <> r2` by identity). What lite does today: a node value is its label and
its properties, so two nodes alike are one value; the ~100 cases of W2 are the queries where that is visible (S74,
S75: counts, distinctness, comparisons, uniqueness, the triadic selection use case), and the harness's node tables
already hold an `id BIGINT PRIMARY KEY` the model does not see. What Legend's own notion is: a Pure instance has
identity; `==` on two instances compares their `<<equality.Key>>` properties when the class declares them, else the
instances themselves (the platform's `InstanceEquality`: keyed classes by their key tree, keyless by identity, which
the verdict lane mints as `__id` for constructed instances); under a relational mapping an instance's identity is
the class mapping's `~primaryKey` (legend-engine builds its graph-fetch objects by it; a mapping without one keys
on every mapped column, which is exactly the "two alike nodes are one" collapse). So the right thing on the Legend
side is: node identity = the class mapping's primary key, Legend's own identity of a mapped instance, never a
comparison of properties. Two ways to reach it from the island's Pure: (a) the harness declares the `id` column as
a property of the class marked `<<equality.Key>>` and hidden (a `graph.hidden` tag: `properties(n)`, `keys(n)` and a
node value omit it), so `$a == $b`, `distinct`, grouping and `in` resolve to the key through the platform's existing
EqualityKeys and the identity travels through `WITH` as a column; works today, no platform change, three to four
legs; (b) the platform resolves `==` of a keyless mapped class to its mapping's primary key (reference identity
under the mapping, the faithful semantics, not a fallback) and the island spells identity through a native
(`identity($n)`, lowered to the key columns), so a user's model needs no extra property; the database owner's line,
and the island still needs the native to carry identity through projections. Recommended: (a) now (the user's
"a way to hide keys if needed"), (b) as the platform item that lets any mapped class, keyed or not, answer
identity queries without a declared property. Relationships: an edge class gets the same hidden key; a relationship
mapped as a bare association end (no edge class) has no row of its own and no identity, so uniqueness over it is
the pair of endpoint identities, which is Neo4j's answer wherever the graph holds no parallel relationships (S74).
**Addendum (the user, three turns later): superseded by W2's settled design: identity is the mapped instance's row
key by the engine's precedence (`~primaryKey`, else the main table's `PRIMARY KEY`, else refuse), carried as the
PCT lane's `__id` and exposed by one native (`elementId`); `<<equality.Key>>` stays value equality and is never
identity; no key property is declared or hidden by anyone.**

**Q3. How the TCK's `rand()` and clock scenarios pass.** The 50 cases of W3 assert nothing a draw or an instant
decides. The quantifier features (Quantifier9, 11, 12: 30 cases) build a list by random filtering and random
reversal three times (`[y IN inputList WHERE rand() > 0.5 | y]`, `CASE WHEN rand() < 0.5 THEN reverse(list) ELSE
list END + x`) and then assert an identity that holds for every list: `none(x IN list WHERE p) = (NOT any(x IN
list WHERE p))` is `true`, `none(x IN list WHERE false)` on a non-empty list is `true`, `single(x IN list WHERE p) =
(size([x IN list WHERE p | x]) = 1)` is `true`; the randomness is there to vary the list, the answer is the same
row every run. The clock features (Temporal4/[13]: 15 cases, Temporal10/[12] and [13]: 5 cases) assert
`date(null)`, `date.statement(null)`, `localtime.realtime(null)` ... = `null` (the argument decides, the clock is
never read) and `duration.inSeconds(datetime(), datetime())` = `'PT0S'`, which holds because Neo4j's clock is one
instant per statement (`.statement`, the default; `.transaction` per transaction; `.realtime` per call): both calls
read the same instant. In SQL the statement clock exists on every dialect (`now()` and `current_timestamp` are the
transaction's start on Postgres and DuckDB; `clock_timestamp()` is the per-call one), so `datetime()` lowered as
the statement clock gives `PT0S` too, and `.realtime` lowered as the per-call clock gives Neo4j's semantics. So the
judge needs nothing new: it compares final rows, and the final rows are deterministic. The two rand cases outside
W3 already pass: `RETURN count(rand())` is a compile error on both sides (Return6/[15]) and `SKIP
toInteger(rand()*9)` asserts only `count > 0` (ReturnSkipLimit1/[3]). Lite's own cases pin invariants only
(`rand() >= 0 AND rand() < 1`, `date() = date()`); the database's `setseed` is a nicety for pinning a draw, not
needed.

**Q4. Times of day and Pure's `StrictTime`.** The same thing. Pure's `StrictTime` is a time of day with no date and
no zone (`%12:34:56.789`); Cypher's `localtime` is exactly that, and the island already carries it as StrictTime (the
MIR's `TIME`, every dialect, the nanosecond leg's `TIMESTAMP_NS` on the epoch day for sub-microsecond ones).
Cypher's `time` is a time of day with a UTC offset (Neo4j's `OffsetTime`), which the island carries as the UTC
StrictTime with the offset beside (§12.11). The W5 gap is not the value but the STORE: lite's `###Relational`
grammar (legend-engine's, kept verbatim) has `TIMESTAMP` and `DATE` and no `TIME` column type (S64), so a StrictTime
property has no column to map to, and a scenario whose `CREATE` stores a time of day is skipped by the harness.
§3.1's decision 4 stands: a `TIMESTAMP` on the epoch day under a `graph.timeOfDay` marker, read back as StrictTime,
keeps the grammar legend-engine's.

**Q5. Labels, and what "several labels" asks.** A label is a tag a node carries: `(:Person)` carries one, `(n:Person:
Employee)` carries two, `()` none; a node's labels are a SET, not a type, so any node may carry any labels, nodes of
the same label may hold different properties, and `SET n:Employee` and `REMOVE n:Employee` change the set at run
time. A pattern `(n:A)` matches every node whose set contains `A`; `(n:A:B)` every node whose set contains both;
`labels(n)` returns the set; `n:B` tests membership. Lite today: a label is a class (the harness makes one class and
one table per label), so a node's label set is one class, and a node carrying two labels has no class: the harness
skips the scenario (18 cases, "harness: a node with several labels"). Match1/[3] shows the whole problem: the
setup creates `(:A:B:C), (:A:B), (:A:C), (:B:C), (:A), (:B), (:C)` and three unlabelled nodes, and `MATCH (a:A:B)
RETURN a` must answer `(:A:B)` and `(:A:B:C)`: seven distinct label sets, the answer two of them, and the returned
node value shows ALL its labels, including the `C` the pattern never named. Match3/[7] creates one node of
thirteen labels and one of six and matches both by their full sets in another order. Two shapes: (a) a class per
LABEL SET, tagged with its labels (`g::A_B_C` with `{graph.labels = 'A,B,C'}`), `MATCH (n:A:B)` reading the arms of
every class whose set contains both, exactly the label-less machinery (§12.13 unions every class's arm at the first
projection) restricted by label membership; `labels(n)` is the class's set, `n:B` a constant per arm, a node value
prints its class's set: reads need no new mechanism and one harness change, and seven classes for Match1/[3] is
what the setup holds; writes are the cost: `SET n:C` on a node of `A_B` MOVES its row to `A_B_C`, which the model
must already declare (the harness can, from every label set the scenario mentions; a user cannot foresee every
set). (b) A class per LABEL and a node's row in each of its labels' tables under one identity (decision 2):
`MATCH (n:A:B)` is a join of `A` and `B` on the id, `labels(n)` is the id's presence across the label tables,
`SET n:C` inserts one row and `REMOVE n:C` deletes one; but a node's properties must live in one place (a node
table keyed by the id, the label tables holding only ids, or every label table repeating the columns), so each
read becomes id-joins and the harness's model changes shape. Recommended: (a) for the reads of Phase C (18
cases, two legs, no identity needed), reconsidered when W4 reaches `SET n:Label` (Set3, Merge3, Merge4: 7 cases),
where (b) is the honest relational shape and (a) a row move. Inheritance (`A_B extends A, B`) is not needed for
either: the arms do what subclass reads would, and a relational mapping's `A.all()` reads A's table only.

**Q6. DML: the "poor man's" MIR, and the right way.** What exists: `SqlDml` (`InsertValues`, `InsertFromTable`,
`DeleteAll`, `CreateTempTableAs`) is a real MIR half, rendered by every dialect through the same writer as a query;
what is poor about it is everything around it: it has no Pure front (Java builds the records: the CSV seed, the
DDL seeding, the row loader, the test-data generator's temp table), no `UPDATE`, no `DELETE ... WHERE`, no `INSERT
... SELECT` of a query, no `MERGE`, and three ad-hoc execution sites. Pure's own write native already exists in
lite's registry: legend-engine's `write<T>(rel: Relation<T>[1], accessor: RelationElementAccessor<T>[1]): Integer[1]`
(`#TDS ...#->write(#/db/T#)`, rows in, a count out), declared and not lowered. The right way, by the tenets (Java
orchestrates, the database executes; one compiler): a write is a Pure relation expression like a read, planned by
the one compiler to one DML statement the dialect renders and the database executes in a transaction, the count
its result: `write` for insert (its relation any query: `INSERT ... SELECT`, or a `Values` source: `INSERT ...
VALUES`), and lite natives in `meta::legend::lite::` for what legend-engine never declared: `update(accessor,
filter-lambda, ~[col: x | expr])` → `UPDATE ... SET ... WHERE`, `delete(accessor, filter-lambda)` → `DELETE ...
WHERE` (`deleteAll` the filter-less form, today's `DeleteAll`), `merge(rel, accessor, ~[keys])` → `MERGE` /
`INSERT ... ON CONFLICT` per dialect; each one MIR variant (`SqlDml.InsertSelect`, `Update`, `DeleteWhere`,
`Merge`), one render arm per dialect, one execution seam (`Execution.executeWrite`, one transaction per
statement or per `[...]` of statements). Who uses it: the Cypher island's `CREATE`, `SET`, `REMOVE`, `DELETE`,
`MERGE` (W4, 274 cases), the BI plan's ETL sinks ("L"), the test-data generator, and any user writing Pure. The
Java seeding sites keep constructing the records where no Pure exists (a fixture's rows are data, and building the
MIR directly is orchestration, not evaluation) or move to `write` of a `Values` relation; the temp table stays
planning-internal. So: yes, real relation functions, and `SqlDml` grows into their MIR rather than being replaced;
F1 of W4 is exactly this, before the island's clauses.

## 4. The order, and the count it would reach (rewritten 2026-10-17 on the user's "attack expertly" ask)

The remaining 787 (DuckDB: REFUSED 606, SKIP 181; agreement 3093 of 3880) are three kinds of work, ordered by what
gates what:

| phase | legs | what, and why here |
|---|---|---|
| 1 identity, then joins on it | ~5 | W2: the keystone most buckets lean on (cycles, DISTINCT and grouping over nodes, uniqueness, MERGE and DELETE later, the triadic use cases); then re-anchoring by join on identity, which retires the resolver's depth limit from the translator's side: ~150 cases hang off the pair |
| 2 cheap translator fillers | ~6 | W3 (50: a lite `random()` native and the statement clock, one MIR function each, a render arm per dialect) and W12 (62: COUNT/COLLECT subqueries, untyped relationships in OPTIONAL and multi-pattern MATCH, string predicates on non-strings, SKIP/LIMIT expressions, named-zone leftovers), interleaved while bigger legs wait on lanes |
| 3 the harness's models | ~5 | W5 stored temporals (72: the marker convention, no grammar change), W6 label sets (18: a class per set, multiple inheritance, the arms union; reads only), W7 two-typed properties (6), W8 procedures (50: the TCK declares each procedure's signature and rows, so the harness declares it as a Pure function returning that relation and `CALL ... YIELD` calls it) |
| 4 the platform's limits | ~9 | W9's remainder (fan-out to a property-less class 21, `%` in a database lambda 9, literal edge cases 14, ORDER BY a non-returned item 4), W11 the Variant family (68: comprehension over a JSON array, element-wise list ordering and comparison by a canonical key the database computes, min/max across kinds), W10 (15: the one real feature, a recursive MIR node for unbounded paths, WITH RECURSIVE on DuckDB and Postgres with a depth guard) |
| 5 writes, on a real foundation | ~7 | F1 first as its own landing (Pure's `write`, lite's `update`/`delete`/`merge` natives, four MIR variants, a render arm per dialect, one execution seam in a transaction); then the stronger judge BEFORE any write clause (the whole graph dumped after the write and compared structurally with Neo4j's); then CREATE, SET and REMOVE, DELETE and DETACH DELETE, MERGE; the 33 control-query scenarios come free |

About 32 legs. Each lands as the forty-four before it: Neo4j-judged lite cases, both lanes in update mode with FAIL,
BUG and ERROR at zero, the oracle check, the guards, the study's section, GATES, a commit and a push on the branch.
Three risks held in view: the writes judge (built before the first write clause), nondeterminism (`rand`, the order
of collected and distinct values: pin invariants only), the two lanes diverging (every leg runs both).

**Standing order (the user, 2026-10-17):** keep going until the TCK is at zero or as close as makes sense; no
questions, no PRs; a blocker is logged in §5 and the line moves to the next item; no hacks or shortcuts.

## 5. Blockers log

What got stuck, where, and why, for later; each entry names the cases it holds back.

(none yet)
