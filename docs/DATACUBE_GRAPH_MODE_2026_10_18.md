# DataCube's graph mode: a design note, and the G0 spike

Phase 4 of the DataCube roadmap run (`docs/DATACUBE_ROADMAP_RUN_2026_10_18.md`), on branch `claude/datacube-graph-mode`,
stacked on phase 3 with main merged in (main carries the `#Cypher{...}#` island, neema2/legend-lite#58).

**A proposal to the Cypher line, not yet agreed.** The compiler half of a graph mode is the Cypher session's
(`docs/CYPHER_PLAN_2026_10_15.md`); this line does not message other sessions, so the note is written for them to
read and answer, and the run builds only what stands on what main already has: the G0 spike.

## What exists

- **The island** (main, `compiler/spec/CypherIslands.java`): `#Cypher{...}#` inside any Pure query is translated to an
  ordinary Relation expression over the class model, which is the graph (decision C2: a label is a class, a
  relationship type a property or association end holding a class), then typed once and lowered to SQL like any query.
  Judged against Neo4j's snapped answers on DuckDB and Postgres (`//core:cypher_parity_test`).
- **The tab's planner** is the same compiler, in WebAssembly (`//wasm:planner`): it plans whatever core plans.
- **DataCube** runs a Pure relation as a grid's source; pivots, charts, Python and the page all stand on that.
- **Identities** (main, `docs/CYPHER_TCK_TO_ZERO_PLAN_2026_10_17.md` W2): a mapped instance's identity is its class's
  key, `'<class>:<key>'`, one native (`meta::legend::lite::elementId`); Cypher compares nodes by it. What a node-link
  drawing needs of a row is there.
- **A graph by name** (main, the study's §12.45): `USE <data space>` names a graph -- a DataSpace stereotyped
  `<<graph::graph.Open>>`, its elements the labels, read open; without one, the whole class model is the graph, closed.
- **Missing:** a Cypher source in DataCube; a network chart (ECharts has one, unused); paths and graph algorithms
  (still to come in the Cypher plan).

## The phases

| | What | Whose | Stands on |
|---|---|---|---|
| G0 | A Cypher query planned in the tab and run on its DuckDB, answering as Neo4j does | this line | main |
| G1 | A Cypher query as a grid's source: New > Data Source > Cypher over a model, its result a relation, so pivots, charts, the page and Python work on it unchanged | this line | G0 |
| G2 | A network view: nodes and relationships drawn (ECharts' graph series), each node's label and properties | this line | identities (main has them: `elementId`) |
| G3 | Click a node to expand its neighbours; a selection filters the grid, as a chart's click does | this line | G2 |
| G4 | Paths and algorithms (shortest path, PageRank) as the compiler gains them | the Cypher line | its plan |

## G0, built here

`datacube/test/cypher-spike.test.ts` (`//datacube:cypher_spike_test`): the island's own integration model (people,
firms, cities; a person's firm an association, a firm's city a class-typed property), its rows seeded into the tab's
DuckDB, Cypher queries planned by the tab's planner (`LitePlanner.planText`, the WebAssembly build of main's compiler)
and run on DuckDB, their rows the ones the island's integration test holds: a match, a hop along an association, a hop
back counted by group, nodes compared by identity -- and a Cypher result grouped and sorted in Pure, as a grid's
query composes with its source. It proves the tab runs Cypher today, with no server, so G1 is DataCube's work alone:
a source picker entry, a source editor, and the grid's query over `#Cypher{...}#` as over any relation.

## Questions for the Cypher line

1. **Identities:** for G2 a row needs each node's identity and each relationship's ends. Is `elementId(n)` in a RETURN
   (a string column `'<class>:<key>'`) the shape to draw by, and a relationship's ends its two nodes' identities?
2. **A graph to pick:** G1's source picker would list a model's graphs -- its data spaces stereotyped
   `<<graph::graph.Open>>`, opened with `USE` -- and offer the whole model, closed, as one more. Is that the reading?
3. **Refusals:** the island's refusals (not yet supported, syntax) arrive as compile errors placed in the source; G1
   would show them in the source editor as Pure's are. Anything else a source editor should know?
