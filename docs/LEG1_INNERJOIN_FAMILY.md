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
   'PersonBicycle' not found in db X): DEEPER than a lookup bug —
   ModelBuilder.findJoin IS include-closure aware and the join IS
   declared in the included db (union::myDB, testUnion.pure:835). The
   included db is a CROSS-FAMILY element (the union family's source
   file); the likely failure is corpus module ASSEMBLY not carrying the
   dependency source into this family's model (the include resolves to
   an unknown db). Route: the harness module-dependency story (#43),
   verify with a findDatabase probe before any code.
3. MERGE CLASS MAPPINGS — 2 tests (employees2/3): the Merge operation
   feature (own track, like union/inheritance ops).
4. TYPE LUB Column-vs-bare — 'no common supertype for
   (address:String[0..1]) and String' (2 symptoms; one may be leg-4
   masked): a NAMED-COLUMN type meeting a bare primitive in a LUB
   position — small typing bug, candidate slice 1 sibling.
5. TypedVariable lowering — 1 test, needs its own diagnosis.

## Slice ladder

S1: Column-vs-bare LUB (diagnose first); the join-lookup pair moved
    to the #43 assembly track (see above).
S2: leg-4 views-as-join-targets (5) — belongs to the leg-4 design
    (frame-as-join-side), not built here.
S3: Merge ops (2) — feature track.
