# SQL-TEXT → ROW-VERDICT CHARTER (2026-09-01, user-ratified design)

**READ WHOLE BEFORE CODE. This charter was ratified line-by-line in
conversation (2026-09-01); do not re-derive the design — verify your
homework against it.** Parent program: harness deletion item 1
(WHOLETEST_COMPILATION_CHARTER.md). Bound by
[[metamodel-in-database-ruling]] (memory): nothing here consumes
Java-computed metamodel facts — the producers below are the emitters
and the executor, both already platform.

## 0. The one-sentence rule

**A SQL-text assert's verdict is ALWAYS rows, never text — including
when the text matches byte-for-byte.** Golden text is H2-flavored;
we execute a DuckDB translation. Identical text proves the emitter's
spelling, not the answer. Text match/diff is a CENSUS number
(shrink-only ratchet for emission work), never a verdict.

## 1. The lane (measured 2026-09-01)

1,545 tests fall back on text-policy; plus ~15 plan/SQL-text
platform-fail rows. Walk-side machinery already row-verifies most of
it: per sweep ~463 text-matched + ~1,001 text-divergent all
row-verified, 0 row divergences, ~21 counted replay declines, 44
"text-only". The clean-sheet work is RE-HOMING triggers and ownership
— replay itself exists and is proven.

The 44 "text-only" decompose (measured): 23 plan-text (17
plan-literal + 6 plan-let) + ~21 sqlstring renders. Both are
runnable — §4 and §5.

## 2. Platform vs testing — the ruled split

- **Platform** (product-legitimate, mostly exists): SQL/plan text
  emission (toSQLString/planToString JAVA_ROUTINEs), executing SQL on
  a connection, the H2 dialect as a real backend, and the
  assert-verdict layer.
- **Testing**: the goldens, the seed ledger + H2 mirror, the
  comparison policy, the divergence census, the plan replayer.
- **The bridge — an SPI, the AssertListener precedent**: the platform
  defines a tiny interface (`SqlReplayOracle`: rows for a SQL text;
  plan replay entry) carried on ExecEnv; the harness registers the H2
  mirror implementation per run. Production registers nothing and a
  SQL assert WALLS loudly (correct: no goldens outside tests). The
  platform never learns what a golden is.

## 3. The pipeline, step by step (the ratified walkthrough)

1. Whole test body compiles as one unit (existing).
2. Query-level lets substitute forward (existing) — after this the
   assert's typed argument tree CONTAINS the producer chain inline.
3. Statement-root asserts reach the verdict layer before evaluation
   (existing).
4. **Detection = navigation of the typed tree by node kind + exact
   callee FQN** (never text sniffing): a TypedNativeCall to
   toSQLString/planToString/executionPlan in an argument tree, or the
   assertSameSQL-family form itself. The producer node's CHILDREN are
   the structured inputs: query lambda, mapping ref, dialect,
   runtime.
5. Derive four artifacts:
   a. OUR TEXT — the platform emitter (the same routine staging
      calls).
   b. GOLDEN TEXT — ordinary evaluation of the literal side.
   c. OUR ROWS — the query lambda from the producer's own children,
      run through the ordinary pipeline on DuckDB (the referee
      executes it even when the test never did — this dissolves most
      of "text-only": the pure query is IN the assert's arguments).
   d. GOLDEN ROWS — the oracle SPI replays the golden text on the H2
      mirror.
6. VERDICT = row compare under §6's ratified policy (order-sensitive
   per §7). Text result → census.
7. Outcomes: rows= & text= → clean pass. rows= & text≠ → pass +
   emission-census row. rows≠ → FAIL (real bug, whatever the text
   said). leg underivable → counted decline, advisory, visible.

## 4. sqlstring "text-only" — dual-derivation

`assertSameSQL(golden, toSQLString({|query}, mapping, dialect))`: the
pure query is arg 0 of the producer. Referee executes it (5c) and
replays the golden (5d). Irreducible residue, named:
- FOREIGN-DIALECT renders (Sybase/Snowflake/…): no oracle database —
  text stays the contract, census-listed; the H2 member of each
  family still row-verifies.
- testNonExecutableSQLString (by design), both-ours compares (both
  sides OUR renders → execute both queries, compare rows to each
  other — self-consistency beats string compare).

## 5. Plan-text — the plan replayer (test-side)

A plan is a PROGRAM, not a statement: Sequence / Allocation (bind
name = evaluate expr) / FreeMarkerConditional (>50 elements → temp
table, else inline literals) / CreateAndPopulateTempTable /
Relational(sql-with-${holes}). Tests generate plans for PARAMETERIZED
queries and supply no values — nothing executes as-is.

The replayer (harness-side, a few hundred lines over that closed node
vocabulary): obtain parameter values (harvest from sibling executions
where they exist, referee-chosen otherwise — coverage is a
measure-first item), run allocations, take the conditional **BOTH
WAYS** (small collection AND >50 to force the temp-table branch),
fill the template, run on the oracle, compare rows per branch against
our pipeline with the same values. Branch-forcing is the point:
character-matching can never prove the temp-table path works. The
plan PRINT joins the text census like any emission.

## 6. THE RATIFIED NORMALIZATION INVENTORY (2026-09-01, per-row
## verdicts — the compare policy IS this table; receipts H2Verify.java)

| # | rule | verdict |
|---|---|---|
| 1 | null → `<null>` token | REAL; harden to non-printable sentinel (string-collision edge) |
| 2 | integers exact | REAL |
| 3 | floats @10 significant digits | HACK-ish → REVERIFY: upgrade to ULP-based compare (unify with canon ulp-policy lane); probe the float witnesses under ULP-1 first |
| 4 | timestamps floored to micros | REDUCIBLE — PROVEN 2026-09-01: duckdb_jdbc 1.5 TIMESTAMP_NS round-trips .123456789 (plain TIMESTAMP truncates). A real leg: DDL/seed translation, casts, wire-census types |
| 5 | trailing-zero trim on ts strings | REAL (canonicalization) |
| 6 | temporal carriers → instant | REAL (engine 9-digit parity, receipt in-file) |
| 7 | JSON-carrier ISO→temporal, type-driven only | REAL |
| 8 | per-column enum code→name decode | REAL (underivable = decline) |
| 9 | array/JSON carrier unwrap | REAL (JDBC mechanics) |
| 10 | unconditional row sort | **HACK → FIX**: order-sensitivity decided STATICALLY from our typed query (pipeline ends in sort?) — ordered compares in order, unordered as multisets. Measure blast radius first via the existing LL_ORD_COUNT counter |
| 11 | column-arity mismatch → decline | DECLINE-OK but REDUCIBLE: the extra columns are OURS (driver PKs/order keys) with provenance — project them away instead of declining |
| 12 | golden-side all-NULL single-col row drop (value frames) | REAL, USER-RATIFIED as the observable-boundary policy (engine's own asserts are the receipts) |
| 13 | duplication skew → decline | **ADJUDICATE — PRIORITY, user: "scary/masking bug"**: see §6.1 |
| 14 | graph compare by label, sorted-key tupling | REAL |
| 15 | bookkeeping-column exclusion (pk_N, u_type, from_z/thru_z/in_z/out_z, k_businessDate/k_processingDate) | REAL but pattern-based: engine's own generated spellings; keep list PINNED shrink-only |
| 16 | graph nesting/key-skew → decline | REAL |
| 17 | assertEqualsH2Compatible dual goldens | REAL (engine's own H2-version variance) |
| 18 | sqlRemoveFormatting folds | REAL (engine's own helper) |
| 19 | (breadcrumb strip) | GONE — reverted with the metamodel ruling |
| 20 | session settings + extension fns + seed ledger | REAL — this IS the oracle |

### 6.1 The dedup adjudication (slice 0 — before any lane work)

The engine's execute path builds ONE OBJECT PER ROW — join fan-out
duplicates instances, no pk dedup anywhere (RelationalResult.java,
verified 2026-08-28). OUR filter lowering dedups. Every "identical
distinct sets, different duplication" replay result is currently a
counted decline — which may be PARKING A REAL LOWERING BUG: on join
fan-out our answers may have the wrong multiplicities versus pure
semantics. Homework: census the skew declines' witnesses; for each,
determine what real pure returns (the engine test's own asserts are
receipts); locate where our lowering introduces DISTINCT/dedup and
whether any is semantics-changing rather than an equivalent rewrite.
If the bug is real it outranks everything else in this charter —
wrong answers beat missing migrations.

## 7. Order-sensitivity rule (ratified)

Compare IN ORDER exactly when the compared query is ordered — decided
statically from OUR TYPED QUERY (the pipeline ends in
sort/sortBy/asOfJoin-ordered shape), never by grepping SQL text.
Unordered queries compare as multisets. Migration: run LL_ORD_COUNT
over a full sweep first; every pass that depended on order-leniency
under an ordered query is a pre-existing defect to burn before the
flip, pinned like any census.

## 8. Slices (each: sweep between mechanisms, ratchet WITH the burn,
## one gate chain per batch, push green; regressions → 2-bisection
## stop rule)

0. **Dedup adjudication** (§6.1) — answers first.
1. Oracle service extraction: one named harness service owning
   mirror+ledger+verify (today statics across Runner/H2Verify/
   RawSqlBoundary); platform SPI on ExecEnv; no behavior change,
   census byte-stable.
2. Order-sensitivity fix (§7) with the measured blast radius.
3. Verdict arms for the sqlstring family incl. dual-derivation (§4);
   text census wired; flip the lane's simple shapes.
4. Plan replayer (§5) + plan-text flips; branch-forcing.
5. Inventory upgrades as their own commits: #3 ULP probe/upgrade,
   #4 TIMESTAMP_NS leg, #11 projection, #1 sentinel.
6. Emission byte-parity: the text-diff census becomes the shrink-only
   ratchet; dialect spelling work retires rows class-by-class —
   NEVER a verdict, never a blocker.

## 9. Standing constraints

No metamodel-fact consumption ([[metamodel-in-database-ruling]]);
declines counted never silent; no statement-shape recognizers on the
platform side (detection is typed-node + exact FQN only); the walk's
sql-text recognizer dies only as flips make it unreachable; foreign-
dialect/by-design-unrunnable residue stays a NAMED census forever.

## 10. Open items ratified as measure-first (no guessing)

toSQLString overload coverage per test; runtime pairing for render
tests whose module runtime may not bind the producer's mapping;
parameter-value harvesting coverage for plan replay; LL_ORD_COUNT
blast radius; ULP-1 float survivor set.
