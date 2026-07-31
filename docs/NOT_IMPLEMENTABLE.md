# Documented not-implementable corpus tests

Tests whose expectations cannot be satisfied by a correct DuckDB-backed
implementation. Each entry carries the evidence that the gap is in the
test's assumptions, not our emission. Never "fixed" by overfitting the
harness or the SQL; revisit an entry only if its evidence is refuted.

Classification rule (burn-to-max arc): every non-passing corpus test ends
implemented, leg-owned, or listed here with evidence.

## Row-order artifacts: positional at(N) asserts over unsorted chains

The engine's relational corpus runs only on H2; positional row
expectations encode H2's incidental nested-loop scan order. The harness
ORDER POLICY (TestBody, whole-result multiset for unsorted chains — the
single deliberate leniency) cannot apply to `->at(N)` single-row
extractions: each assert sees one row, so the order dependence is baked
into the test text itself.

| Test | Evidence |
|---|---|
| `meta::relational::tests::advanced::forced::filter::testFilterMappingWithProjectionOverlappForcedCorrelated` | Engine's own byte-exact golden SQL (`testForced.pure` assertSameSQL text), executed on DuckDB over the corpus seeds, returns the identical 6-row multiset in a different order (`Federation` first, engine expects `ROOT` first). Query has no sort. Verified 2026-07-31, duckdb python replay of both the golden flat-join shape and our nested shape. |
| `meta::relational::tests::advanced::forced::filter::testFilterMappingWithProjectionOverlappForcedOnClause` | Same helper, same at(0..5) asserts, same replay evidence (MoveFilterInOnClause golden reorders identically on DuckDB). |

Note the semantics themselves are NOT the gap: our row multiset matches
(the size-6 assert and multiset agree); the target ~filter already rides
inside the joined pipeline (NavMaterializer), which is row-equivalent to
both forced strategies. Only the H2 scan order is unreproducible.

## Retired entries

- `testQualifierContainingAJoinWithIsolationAndExistsDeep` (2026-07-31,
  same session it was added): the divergence was RETIRED by implementing
  the engine's join-distinct exists form (ExistsJoinForm,
  buildExistsAsJoinWithNullCheck parity) — the test now byte-matches.
  Implemented beats documented.
