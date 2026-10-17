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

### W1. Schema-optional reads (S58 overturned)

A property declared by no candidate class of a pattern variable reads the known null (the island already reads a
property an arm's class lacks as the known null, §12.40.2: the same mechanism, one step wider). `MATCH (n:Nope)`
over a model with no such class reads no rows (the first-clause form already exists for an OPTIONAL MATCH over no
class, §12.20); a relationship type naming no association end or edge class reads no rows (the pattern's "no way"
form, §12.36). `n.missing` inside a comparison folds as the known null does today. Neo4j's static errors stay
static errors (a property on a non-map). One leg; the compile-error form kept only behind a query option if the
user wants the typo guard back (not proposed).

### W2. Identity through hidden keys (C5 as the user meant it)

- **The model.** A node class's identity is its `<<equality.Key>>` properties. The harness declares `id` on every
  node class as a key property marked hidden (a `graph.hidden` stereotype on the existing `graph::graph` profile,
  beside `Edge`, `from`, `to`, `type`), so `properties(n)`, `keys(n)`, a node as a value and `RETURN n` never show
  it. A user model keeps whatever keys it declares; a class without keys keeps today's refusals (identity needs
  a key, by name).
- **Relationships.** An edge class's identity is its own hidden `id`. A plain association has no row of its
  own: its identity is the ordered pair of node identities, which S74 already states; the harness switches a
  relationship type to an edge class when its setup holds parallel links between one pair (as it does for
  properties and several pairs), so parallel links get their own identities. S74's "one link per pair" then
  holds only for user models that choose a plain association.
- **The island.** A node value carries `"@id"` (its key values) in its JSON, which the judge ignores; a carried
  node keeps its key columns; comparisons of nodes, relationships and paths compare keys; `DISTINCT` and grouping
  over nodes use the keys; an OPTIONAL MATCH or MATCH after rows from a carried node re-finds the instance by key
  (`Class.all()->filter(n | $n.id == $r.id)`, a lateral) — the continuation the rows could not express; both ends
  bound is the hop's far key compared with the bound node's; an undirected hop over a self-association dedupes
  by key; relationship uniqueness along a variable-length chain is the chain's relationship identities
  pairwise distinct (S75 overturned); `collect(DISTINCT n)` and `count(DISTINCT n)` count keys. The judge reads
  `@id` and never compares it (Neo4j's ids are its own).
- Three to four legs: keys and node values; comparisons, DISTINCT and grouping; continuation and both-ends-bound
  hops; uniqueness and the undirected self-association.

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

## 4. The order, and the count it would reach

| phase | workstreams | legs | cases (cumulative agreement of 3880) |
|---|---|---|---|
| A — rules in the island | W1, W3, W12 | 5 | 3031 → ~3180 |
| B — identity | W2 | 4 | → ~3290 |
| C — the harness's models | W5, W6, W7, W8 | 7 | → ~3440 |
| D — the platform's limits | W9, W10, W11 | 9 | → ~3600 |
| E — writes | W4 (after F1) | 6–10 | → 3880 |

Each leg lands as the forty-three before it: Neo4j-judged lite cases, both lanes in update mode with FAIL, BUG and
ERROR at zero, the oracle check, the guards, the study's section, GATES. Phase A can start now on PR #50's
branch; B changes the harness's models and the register, so it opens PR #50's successor; C to E follow in order,
E in its own PR per decision 6.
