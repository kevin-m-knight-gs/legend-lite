# Collection-carrier redesign (H2_BACKEND.md §4.1) — the single-compiler leg

Status: DESIGN (c48). Measured motivation: the H2 portability sweep's
capability budget (c45, exact): **1,250 of 2,538 corpus tests wall on
collection carriers** — UNNEST 792, array literal 226, LIST_AGG 133,
LIST_CONTAINS 30, LIST_GET 20, TYPEOF 17, LIST_BOOL_AND 5, LIST_CONCAT 3,
tail 9. legend-engine passes these same tests on H2 — the walls are OUR
lowering's DuckDB-native carrier choices, not H2 limits. The engine's SQL
for each construct is the existence proof and the port source.

## 0. Tenets (ordered; #1 is HARD and user-set)

1. **ONE COMPILER — total migration, no dual paths.** The Lowerer and
   resolver emit SEMANTIC nodes only and lose the right to spell any
   backend idiom directly. DuckDB's native carriers (`list()`, `UNNEST`,
   array literals) survive ONLY as rewrite rules of the same semantic
   node in the dialect strategy pass — DuckDB migrates onto each node in
   the SAME commit that introduces it, its rule reproducing today's
   emission (golden-pinned; the 2180 baseline never moves). A construct
   never exists in both worlds across a commit boundary: each rung
   DELETES the old direct-emission site as it lands the node.
2. Rows are the contract (golden text advisory); the differential oracle
   (both strategies executed on DuckDB, row equality asserted) rides
   every rung, plus the engine-golden A/B and the h2 sweep scoreboard.
3. Probe-verified spellings only: every H2 rule's SQL shape executes on
   the real 2.1.214 jar before it is written down; every portable rule
   cites the engine emission it ports (pureToSQLQuery/…​.pure line).
4. Java orchestrates, the database executes — a portable rule may change
   the SQL SHAPE, never move value computation host-side.
5. Where NO portable shape exists (the engine itself goes host-side or
   uses its forked-H2 machinery), the typed `DialectCapability` wall
   REMAINS, budget-counted — an honest wall beats replicated temp-table
   orchestration in the first cut.

## 1. Architecture

The T3.2 rails already exist: SqlPlan → MIR rewrite passes → one
SqlWriter, Dialect = data. This leg adds ONE pass and a node family.

- **Semantic nodes** (`com.legend.sql`, sealed, grammar-first): the
  Lowerer's new output vocabulary for collection constructs. Draft
  inventory (final names at R0):
  - `CollectionSource(elements | subquery)` — a collection used as a
    row source (today: `UNNEST(...)` table function / array literal
    scans).
  - `SubAggregate(value, agg, orderKeys, filter)` — per-group reduction
    of a to-many path (today: `list(...)`, `LIST_AGG`, ordered forms).
  - `Membership(needle, collection)` — `in()` over a collection value
    (today: `LIST_CONTAINS`, array literals in `IN`).
  - `CollectionValue` carriers for list-typed intermediates (today:
    array literals, `LIST_GET`, `LIST_CONCAT`, `TYPEOF`-dispatched
    reads) — the tail; some become rules, some stay walls (R5 census
    decides per construct, budget-counted).
- **The strategy pass** (`com.legend.sql.dialect`, runs before the
  writer): per dialect, rewrites each semantic node to its emission.
  Dispatch is capability-driven: the dialect declares which strategies
  it supports (data, like Spellings/Lexicon rows); the pass selects;
  a node with no rule on this dialect throws the typed
  `DialectCapability` (same wall, now AFTER strategy selection).
  - DuckDB rules = today's emissions, verbatim (golden-pinned).
  - H2/portable rules = the engine's shapes: grouped subselect joined
    by group keys (SubAggregate — the engine's own form, already
    proven here by the parent-copy grouped subselect leg, task #77),
    literal `IN` lists / join-form membership (Membership), `VALUES`
    row constructors or UNION-of-literals (CollectionSource; probed:
    H2 supports both), STRING_AGG forms already landed (c44 RowOrder).
- **The purity guardrail** (core test, R0): walks the pre-strategy MIR
  and FAILS on any dialect-idiom node upstream of the pass. Starts as a
  ratchet pinned to the R0 census of direct-emission sites, burns to
  zero with the rungs, then freezes at zero. This is the mechanical
  form of tenet #1 — the same freeze discipline as string-dispatch/110
  and System.err/40.

## 2. Rungs (each: fix → core 1573 → DuckDB sweep 2180 byte-stable →
PCT 1109 → h2 sweep (scoreboard must rise) → differential oracle green →
commit+push)

- **R0 — census + rails (no behavior change).** Site-level census of
  every direct carrier emission in Lowerer/Scalars/Aggregates (the
  budget's 1,250 is test-level; R0 gives the code-level worklist). Land
  the empty strategy pass + the purity ratchet pinned at the census
  number. Engine-reference probe list drafted per rung head.
- **R1 — `SubAggregate`** (LIST_AGG 133 + the list() sub-agg share of
  UNNEST tests). Smallest semantic surface, biggest prior art: the
  engine's grouped-subselect form is already implemented for chained-nav
  aggregates (#77) — this rung generalizes it to a strategy rule.
- **R2 — `Membership`** (LIST_CONTAINS 30 + IN-over-collection share;
  interplay with the registered tempTableForIn gap: the engine's temp
  table route stays a wall; literal/join forms cover the corpus share).
- **R3 — `CollectionSource`** (UNNEST 792 — the giant; probed H2 forms:
  `VALUES`, UNION-of-literals; the engine's per-construct shapes read at
  rung head). Expect this rung to split into sub-rungs by source kind
  (literal collections, getAll-for-each-date, variant/JSON explodes —
  the last may remain walls).
- **R4 — array-literal value carriers** (226; many fall out of R1-R3
  since the literals feed those constructs; the residue is genuine
  list-VALUES — census decides rule vs wall per site).
- **R5 — tail classification** (LIST_GET/TYPEOF/LIST_BOOL_*/…​ — rule
  or budget-counted wall, decided per construct with the engine as the
  bar; zero silent drops).

## 3. Exit criteria

- Purity guardrail at ZERO and frozen (tenet #1 mechanically closed).
- h2-backend sweep ≥ 80% of the DuckDB baseline's passing set, with the
  remaining gap 100% budget-classified (walls + registry rows).
- DuckDB baseline 2180+ throughout (byte-stable per rung; improvements
  only by deliberate strategy flips with the differential oracle green).
- The strategy pass + node family documented as the standalone-SQL
  library's collection chapter (LEGEND_SQL_VISION.md cross-link).
