# classMappingFilterWithInnerJoin family — routing census (2026-07-26)

The feature map's leg-1 (DeferredFilter) assumption for this family is
CORRECTED by census: the 11 errors split into six mechanisms, and the
dominant one is LEG 4 (views as identity-carrying frames), not filter
isolation. The INNER-mapping-filter machinery itself (~filter declared
INNER wrapping the class source) already serves the family's 21 passing
tests.

## Routing

1. VIEWS AS JOIN TARGETS — 5 tests, LEG 4:
   testSourceViewRootQueryWithInnerJoinClassMappingViewFilter,
   testSourceViewPropertyQueryWithInnerJoinClassMappingViewFilter
   (Join 'myFirmView_myPersonView' targets view 'myFirmView'),
   TestClassMappingsWithInnerFilterJoinedWithMilestoningDepthTwoNested
   (ProductViewTrade_Join targets a view),
   testSourceViewPropertyQueryWithInnerJoinClassMappingTableFilter
   (Binder: VALUES t3 lacks 'firmID' — a view frame losing join-key
   columns), and likely testTargetViewPropertyQuery... (supertype
   symptom over a view read). A join whose TARGET is a view must join
   the view's FRAME (subselect) — the leg-4 identity-carrying frame
   discipline; the join-emission currently only targets tables.
2. CROSS-DB JOIN LOOKUP — 2 tests (Join 'PersonSet1AddressSet1' /
   'PersonBicycle' not found in db X): the join lives in a DIFFERENT
   database than the one searched — the lookup must search the
   mapping's store closure, not just the set's own db. Mechanical,
   candidate slice 1.
3. MERGE CLASS MAPPINGS — 2 tests (employees2/3): the Merge operation
   feature (own track, like union/inheritance ops).
4. TYPE LUB Column-vs-bare — 'no common supertype for
   (address:String[0..1]) and String' (2 symptoms; one may be leg-4
   masked): a NAMED-COLUMN type meeting a bare primitive in a LUB
   position — small typing bug, candidate slice 1 sibling.
5. TypedVariable lowering — 1 test, needs its own diagnosis.

## Slice ladder

S1: cross-db join lookup (2) + Column-vs-bare LUB (diagnose first).
S2: leg-4 views-as-join-targets (5) — belongs to the leg-4 design
    (frame-as-join-side), not built here.
S3: Merge ops (2) — feature track.
