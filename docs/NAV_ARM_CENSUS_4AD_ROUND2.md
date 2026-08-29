# §4AD ARM-ATTRIBUTION CENSUS — ROUND 2 (batch 2 of the routing program, 2026-08-29)

Parent: NAV_ROUTING_DESIGN_4AD_SLICE1.md §5 (measurement before
implementation) + batch-0 homework (NAV_ROUTING_BATCH0_4AD.md).
Instrument: seven new `NavArmCensus` arms at the aggScan registration
points, the two LOGICAL fnlr arms, and the shared-mid-slot claim
point. Committed dump: [nav-arm-census-4AD-round2.txt]
(nav-arm-census-4AD-round2.txt). Full DuckDB sweep green, 152s;
baseline pins byte-reproduced (exec-passing 1,387; h2-exec matched
457 / rescued 880; rescued-passes 816; round-1 arms 946/234/109/2
unchanged).

## Measured arms (distinct tests)

| arm | tests | reading |
|---|---|---|
| `fnlr-value-dispatch` | **30** | the correlated scalar arm's LIVE value-position blast radius — THE batch-5 must-cover list (named in the dump) |
| `fnlr-filter-equality-fold` | 25 | fnlr as a MATCHER inside the filter-position equality→EXISTS fold — batch-7 scope |
| `agg-size2-leaf-arm` | 42 | plain 2-hop leaf reductions (sum($f.employees.age) spellings) |
| `agg-sortby-arm` | 13 | ordered sub-aggregation |
| `agg-computed-mapper-arm` | 9 | qualifier-inlined mapper aggregation (#69) |
| `agg-bare-count-arm` | 7 | bare-head row count |
| `agg-filter-position` | 7 | aggregations demanded from FILTER predicates (parent-copy grouped subselect) |
| `agg-deep-tail-arm` | 2 | deep-leaf tail-mapper spellings |
| `agg-chain-count-arm` | 1 | chained bare count behind a to-one hop |
| `shared-mid-slot` | 11 | a physical mid slot claimed by TWO chains via the nav-CONDITION channel — all milestoning/isolation shapes (list below) |

Call-site correction to the design doc's §4 audit note: fnlr's three
SOURCE call sites (Substitution ~1035, ~1042, ~1758) are TWO logical
arms — 1035/1042 are one matcher+use pair (the filter-position
equality fold); 1758 is the value-position dispatch.

## The three attribution questions, answered

1. **The canary rides EXISTS-material, not aggregation.** 
   `testChainedInnerJoinsWithQualifierInGroupBy` fires ONLY
   `exists-material`. The design doc's PLAUSIBLE claim ("passes via
   the aggregation route") is **REFUTED by measurement**: no agg arm,
   no fnlr. Consequence: batch 5's aggScan implicit-plus deletion and
   fnlr deletion CANNOT strand the canary — its route is the
   filter-position EXISTS channel, batch-7 scope. (Its engine target
   shape is the fanned filtered-subselect join + top-level count —
   batch-0 golden.)
2. **fnlr's live value-position population is 30 named tests**,
   including both batch-0 placement witnesses:
   `testTwoQualifiersWithOperation` (value position, multi-occurrence
   → per-occurrence copies + shared top WHERE) and
   `testDerivedWithFilteringTwoProperties` (projection position,
   multi-occurrence → per-occurrence copies + ON preds,
   row-preserving), plus tree/mid-hop chains (`testProjectMerge`,
   `testProjection*`), milestoned subtyping, and the
   query::qualifier::advanced family. These are exactly the shapes
   the batch-1 pred-count gate parked.
3. **Batch 3 MERGES INTO batch 5** (the design's own rule): no
   `[*]`-lift shape exercises shared-FILTER-mid material through
   existing routing — the 11 `shared-mid-slot` firings are all
   milestoning-context/isolation NAV-CONDITION sharing
   (contextpropagation, graphFetch isolation, multiJoins), not
   filtered-head material. The cross-fan disease witness
   (`testProjectMerge`) only becomes reachable when the flip widens
   the lift, so per-occurrence mid-hop bundling lands WITH the flip,
   tested by the tests that start lifting in that batch.

## Size-2 emission bug (SUM(FIRSTNAME,'Test'))

MEASURED UNREACHABLE at a618c5d2: `Aggregates.isDemandReducer(callee,
argc)` excludes the plus family at argc>1 BEFORE aggScan's size-2 arm
can see it (the batch-1 arity gate). No code change in this batch;
batch 5 moves the rule's ownership into the one-owner classifier
(the rule itself is the engine's own dispatch condition —
batch-0 0a, processPlus L3232-3239).

## Batch-2 verdict

Measurement-only (census fires; zero behavior change — sweep
byte-reproduced the a618c5d2 pins). Sequencing after this round:
batch 4 (dated-head alias fix, baseline-failing witness) → batch 5
(per-occurrence mid-hop bundling + router flip + deletions, the
30-list as acceptance) → batch 6 → batch 7 (exists-material split:
fnlr-filter-equality-fold 25 + agg-filter-position 7 are its named
sub-populations) → batch 8.
