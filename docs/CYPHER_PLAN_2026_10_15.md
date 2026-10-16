# The Cypher plan: `#Cypher{...}#` over the Legend model, and the graph algorithms the database runs

**The one-page plan of the eleventh line (the user, 2026-10-15: "put the whole plan together so we can look at it
holistically"; "let's start implementing"). The study behind every line here is `docs/CYPHER_STUDY_2026_10_15.md`;
this file is the plan, kept in step with it, and the record of landings goes in the study's §12.**

## 1. What we are building

A Cypher query, written exactly as Neo4j accepts it, becomes an ordinary Pure Relation expression over the Legend
class model, typed once by the compiler and run on any backend lite renders, returning Neo4j's answer. On top of it,
the graph algorithms whose answers are a function of the input run in the database too. The method is the one the
Postgres and IQ islands proved: the vendor is the spec, a real oracle judges every case, statuses ratchet in a ledger,
and whatever lite cannot answer exactly is refused by name.

```
#Cypher{ USE demo::MovieSpace
         MATCH (a:Actor)-[r:ACTED_IN]->(m:Movie) WHERE m.year > 2000
         RETURN a.name, collect(m.title) AS titles, count(r) AS n ORDER BY n DESC LIMIT 10 }#
```

## 2. Decided (the user, 2026-10-15)

| | Decision | Study |
|---|---|---|
| C1 | One island type, `#Cypher{...}#`. The text inside is what Neo4j accepts, including Neo4j's own `CYPHER 5` or `CYPHER 25` prefix; no sugar types, no tag syntax, nothing on the model for the version | §4 |
| C9 | Cypher 25 is the default; `CYPHER 5` is the frozen profile. The spec is the newest Neo4j Community release at each landing, named in the answers file (the user: "neo4j latest release") | §2.1, §4 |
| C2 | The graph is the class model: a label is a class (a node carries its superclasses' labels), a relationship type without properties is an association or a property, the graph a query sees is a data space, selected with Neo4j's `USE` | §4, §5.6 |
| C3 | A relationship with properties is an edge class, marked `<<graph.Edge>>`, its ends the properties carrying `<<graph.from>>` and `<<graph.to>>` (on the class, inherited, association-injected, or a derived property for an end the model does not own), its type a `<<graph.type>>` derived property spelling one value of the model's `RelationshipType` enumeration, else the class's name. The four stereotypes come from an ordinary `Profile graph::graph` in the library project `projects/graph` (the user: "profile name you choose"), so the model compiles in the engine unchanged; lite reads them by name as it reads `<<equality.Key>>`. No string anywhere in the declaration. The class form is the primary form; an end moves into an association only for navigation in Pure | §5.6, §5.7 |
| C4 | A property no class of the label declares is a compile error, not `null` (a register row) | §4 |
| C5 | Identity is Pure's instance equality: `<<equality.Key>>` where a class declares it (the model's identity), the row itself otherwise, realized by the mapping's primary key, never a model property. Its identity half in lite's lowering is a dependency on the database owner's line before PR3 | §4 |
| C6 | Recursion is required ("for these graph queries and algos for sure"): bounded paths unroll, unbounded forms and iterative algorithms use a native built in PR5 | §6 |
| C7 | Algorithms in three buckets: deterministic ones translated and judged, costly ones under a size bound, randomized ones refused by name | §7 |
| C8 | Lists, maps, nodes, relationships and paths are Variant values | §4 |
| C10 | A new line, the eleventh, in `IN_FLIGHT`; `protocol/cypher/` a sibling of `SqlToPure`, not an `InputDialect` of it | §4, §5 |

**Verified (2026-10-15):** the worked example of the study's §5.7, every feature in one model, compiles with zero errors
in legend-lite and in legend-engine 4.145.0, and both refuse its negative controls, except that lite does not check a
stereotype against its profile, which PR3's reader does for the graph profile and which is recorded for the compiler
line.

## 3. The design in one table

| Part | What | Where |
|---|---|---|
| The island | `#Cypher{...}#`; wire `classInstance` of type `Cypher`; the version from the text's own prefix; `USE` selects the data space, else the `from(mapping, runtime)` that follows the island | study §4, §5.3 |
| The reader | a hand-written lexer, parser, AST with spans and a printer, `protocol/cypher/`, JDK only; every clause parses, writes included, so a write is refused by name with Neo4j's wording | §5.1 |
| The translator | `CypherToPure`, Cypher AST to a Relation `ValueSpecification`, typed once by the Typer through `compiler/spec/CypherIslands`; the function table as data; three-valued logic as `SqlToPure.Tx` spells it | §5.2 |
| The model side | labels, relationship types, edge classes, identity, `USE` (C2 to C5); `GraphEdges`, a phase-F reader beside `EqualityKeys`; association ends keep their stereotypes (one model change) | §5.6, §5.7 |
| Recursion | `meta::legend::lite::recurse` and a keyed variant, one lowering rule per dialect, two shapes (a row per node per iteration; the state as one array row where the recursive term may not aggregate), a termination guard | §6 |
| Algorithms | bucket 1 translated (degree, triangles, LCC, WCC, SCC, BFS, shortest-path distances, PageRank, ArticleRank, eigenvector, HITS, k-core, topological sort, node similarity, link prediction, modularity, CDLP); bucket 2 bounded (betweenness, closeness, articulation points, spanning trees, Yen's paths); bucket 3 refused (Louvain, Leiden, LPA as GDS runs it, random walks, embeddings, k-means, ...) | §7 |
| The harness | the openCypher TCK as test input (Apache 2.0; 1,615 scenarios, 1,155 read-only expecting rows, 188 errors, 312 writes); lite's own Neo4j-judged cases; Neo4j Community run locally only through the Apache 2.0 Python driver, answers and dumped graphs snapped and committed with the STALE, UNSNAPPED, GONE and version-drift guards; LDBC Graphalytics for the algorithms; lanes on DuckDB and the embedded Postgres in `//gates:core`; a randomized check; an independent review per landing; zero FAIL, zero BUG, zero ERROR | §3, §8 |

## 4. The slices

| Slice | Content | Needs |
|---|---|---|
| PR1 | the island plumbing, the reader, `MATCH` over labels with fixed-length patterns (properties, associations), `WHERE`, `WITH`, `RETURN`, `ORDER BY`, `SKIP`, `LIMIT`, `DISTINCT`, literals, comparisons, boolean logic, `CASE`, `coalesce`, implicit grouping with `count`, `sum`, `avg`, `min`, `max`; the TCK reader and inferred models; the oracle tool; about 650 TCK scenarios reachable | nothing outside the protocol layer, the Typer arm and the seam |
| PR2 | lists, maps, `UNWIND`, `collect`, the quantifiers, the string and number table from lite's own Neo4j-judged cases, the conversions — **landed 2026-10-15 (the study's §12.5) but for `collect`, `UNWIND` after a `MATCH` or over lists of lists, lists of row values that may be null, the string conversions that answer null, and the temporal values (PR2b, §12.5.4)** | |
| PR3 | the model: `projects/graph`, `GraphEdges`, edge classes in every form, inheritance, identity, `USE`, `OPTIONAL MATCH`, `UNION`, subqueries, nodes and paths as values | the identity half on the product lane; association ends keeping their stereotypes |
| PR4 | bounded paths by unrolling, named paths, bounded `shortestPath` | nothing new |
| PR5 | the recursion native and the unbounded forms | the native's design agreed; the database owner's files |
| PR6, PR7 | the algorithms, buckets 1 then 2, the partition judge, both oracles | PR5 |
| later | Neo4j's HTTP Query API, then a Bolt server; writes after F1; SQL/PGQ as a second spelling | the server program; F1 |

Size: 20k to 25k lines over six to eight landings, each with its cases, ledger, independent review and GATES entry.

**Status, 2026-10-15:** PR1a landed on the branch (the study's §12.1): the island, the reader, the translator over
fixed-length patterns, the seam, 67 tests. PR1b landed on the branch (§12.2): the oracle (Neo4j Community 2026.09.0),
the TCK as test input, the inferred models, the two parity lanes in `//gates:core` with the first ledger (3,906 cases,
184 PASS, 0 FAIL, 0 BUG, 0 ERROR, 2,723 REFUSED by name, 669 BOTH_ERROR, 330 SKIP, identical on DuckDB and Postgres),
and the showcase. The independent review of PR1 (§12.4): 17 findings, every one met or recorded; after it 3,914 cases,
190 PASS, 0 FAIL, 0 BUG, 0 ERROR. PR2 landed on the branch (§12.5): lists and maps of known shape, the quantifiers,
comprehensions, indexing and slicing, `IN` over lists, `WITH` first, `UNWIND`, lists and maps as returned values, the
function table, 29 more lite cases (boolean ordering followed on 2026-10-16): 3,943 cases, 1,044 PASS, 0 FAIL,
0 BUG, 0 ERROR, 1,898 REFUSED by name, identical on both lanes. The temporal slice landed on the branch (§12.6, 2026-10-16): dates and local date-times over Pure's StrictDate and
DateTime, 3,959 cases, 1,168 PASS, 0 FAIL, 0 BUG, 0 ERROR; zoned date-times, times of day and durations refused by
name (§12.6.5). The time-of-day leg landed on the branch (§12.7, 2026-10-16): `localtime` over Pure's StrictTime, a
SQL TIME type end to end (the user's "do it all here": the first Cypher landing in the database owner's files); PASS
1,195, 0 FAIL, 0 BUG, 0 ERROR, 1,785 REFUSED, identical on both lanes. The zoned date-time leg landed next (§12.8): `datetime` as an instant with its zone carried beside, upstream's
model; PASS 1,401, 0 FAIL, 0 BUG, 0 ERROR, identical on both lanes. Next in the same direction: durations (the user's question, whether a duration can be a user class on the Variant
rather than a platform type, answered in the study's §12.9 when that leg lands). Then the full CI run on the branch,
the merge; the join form (316 fan-out cases) and `collect` (the database owner's aggregate) wait.

**The showcase (the user, 2026-10-15: "when we have a real oracle, an md doc showcase similar to the IQ showcase").**
`docs/CYPHER_SHOWCASE.md`, written as `docs/IQ_SQL_SHOWCASE.md` is, lands with the first slice that has the oracle
(PR1b) and grows with each later slice: one non-trivial Cypher query over the worked example's model (the study's
§5.7), Neo4j's answer snapped by `tools/neo4j-oracle`, lite's answer identical cell for cell on both parity lanes
(DuckDB and Postgres), the Pure the island desugars to, the SQL for DuckDB (run) and for Snowflake (rendered only),
both pinned byte for byte by a test, what lite refused on the way, and how it was checked and its limits. Each
slice adds a section for what it brings (PR3 the edge classes and `USE`, PR4 a bounded path, PR5 an unbounded one,
PR6 an algorithm against both oracles).

## 5. The rules this line keeps

- Zero FAIL, zero BUG, zero ERROR, always; a refusal by name beats a possibly wrong answer.
- Java never computes a value; a missing semantic is a Pure spelling, a native with one lowering rule per dialect, or a refusal.
- Nothing from Neo4j's manuals or code enters the repository; Neo4j Community (GPLv3) is never on a classpath or in CI.
- The generated Pure reads like hand-written Pure; the harness dumps it per case for review.
- Every cross-area edit is announced in `IN_FLIGHT` before it lands; each landing records itself in the study's §12.
