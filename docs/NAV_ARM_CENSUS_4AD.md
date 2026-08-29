# §4AD NAVIGATION-ARM CENSUS — execution step 1 (2026-08-29)

Charter: V7_ASSERT_VERDICT_CHARTER.md §4AD ("census of navigation-arm
firings — blast radius as a NAMED list, never an estimate"). The
redesign replaces every arm that compiles a MAPPED NAVIGATION to
something other than the engine's row algebra (left-outer-join
fan-out, conditions in join/WHERE, one row per surviving joined row):
EXISTS forms, correlated scalar subqueries, per-object reductions.

**Instrument**: `lowering/NavArmCensus` (runtime accumulation, the
StampCensus/H2Verify precedent; per-test attribution via
`StampCensus.CONTEXT`; always on, no env flag). The corpus runner
prints per-arm counts (`[rcorpus] nav-arm …`) and dumps the full
(arm, test) list to `target/nav-arm-census.txt`. The committed
snapshot of the baseline full sweep is
[nav-arm-census-4AD.txt](nav-arm-census-4AD.txt) — THE named list.

## Measured blast radius (full DuckDB sweep, 2,575 runnable, baselines held)

| arm | emission site | witnesses | reading |
|---|---|---|---|
| `exists-material` | `Substitution.ExistsSub` compact ctor (ALL construction sites: registerExistsSubs, registerDottedExistsSubs, ChainedExists.explodedTwoHop, assoc/nav heads) | **946 tests** | every navigation that compiled to EXISTS material — the semi-join family the redesign re-expresses as join fan-out + WHERE (charter decision 2: no dedup on the class lane) |
| `correlated-count-reducer` | `RelationPredicates` COUNT arm | **234 tests** | `count`/`size`/emptiness over a navigation relation as a correlated scalar COUNT subquery — banned shape under the rule |
| `exists-join-form-dedup` | `ExistsJoinForm.rewrite` (the DISTINCT-key LEFT JOIN dedup emission) | **109 tests** | the row-count-PRESERVING join form — exactly where the engine's algebra would fan out and keep duplicates (testQualifierQueryWithOr's 7-vs-1 lives here) |
| `correlated-agg-reducer` | `RelationPredicates` general-aggregate arm | **2 tests** | avg/sum-style sub-aggregation (`testSubAggregationInQualifier`, `testSubAggregationMultiLevel`) |

Distinct tests touched: **1,017 of 2,575** (251 fire ≥2 arms; 1,291
(arm, test) rows total). The blast radius is large but
CONCENTRATED: `exists-material` dominates, and the charter's slice
order (map/select navigations → filter/qualifier dedup → oracle lane
unpark) cuts it by shape, not by count.

## Honest scope notes

- Attribution is by TEST, deduplicated — an arm firing on a rebuild
  or on multiple navigations in one test counts once. The list is a
  work-item universe, not a defect count: many witnesses PASS today
  (both lanes execute the same non-engine shape consistently); the
  charter's ratified decisions 1–2 name where results will
  OBSERVABLY change (user accepted).
- NOT yet instrumented (candidates needing a provenance split before
  counting): `ValueCollections` list-collects and `SqlExpr.Exists`
  emissions outside the resolver channel — these fire for PURE-LAND
  collections too, which §4AD's boundary explicitly leaves untouched.
  Round 2 of the census splits them by the resolver's
  provenance knowledge if slice 1 needs it.
- The aggregate-over-to-many-navigation WALL
  (CorrelatedSubselects `NotImplementedException`) is not counted
  here — walls are already visible as failures; this census names the
  SILENT non-engine shapes.

## Next (charter order)

Slice 1: map/select navigations to join fan-out. Design with the
witness lists above; acceptance per slice: zero DuckDB-lane pass
regressions, oracle conversions grow-only, pins + charter same
commit. Regenerate the dump on any full sweep and diff against the
committed snapshot — shrink per slice is the ratchet.
