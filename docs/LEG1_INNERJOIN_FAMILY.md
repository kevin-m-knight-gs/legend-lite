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

## Filter-position pierced-toOne reads: fold the comparison into EXISTS (2026-07-31)

`filterFunctionExpressionWithOrConditionOnRightTable` (ERROR, DuckDB
"more than one row returned by a subquery"): `Firm.all()->filter(f|
$f.employees->filter(e|$e.lastName=='Lopez' || $e.age>20)->toOne()
.lastName == 'Smith')`. Substitution.filteredNavLeafRead rewrites the
pierced read to a correlated single-column relation rendered as a scalar
subquery — strict pure toOne (raises on >1 match). The engine instead
LEFT JOINs with the inner filter in the ON clause and compares in the
outer WHERE (golden in testFilterWithQualifiedProperties.pure:225, note
the null-guarded OR: `AGE is not null and AGE > 20`); join row
multiplication collapses via PK dedup at the class reader, so the
OBSERVABLE engine semantics in filter position is
`EXISTS(target: assoc-cond AND inner-filter AND leaf-compare)`.

Fix (this leg's hop emission table): when the consumer of a
filteredNavLeafRead is a comparison INSIDE a filter predicate, fold the
comparison into the correlated relation and emit the exists family
instead of a scalar-subquery compare. Projection position keeps the
scalar subquery (engine multiplies rows there; our scalar read is the
row-stable equivalent). Needs predicate-context awareness at the
Substitution comparison site — do NOT bolt onto the lowerer.

## Qualifier -> class-hop -> qualifier chains (testQualifierWithIsolation pair, 2026-07-31)

`testQualifierWithIsolation`/`testQualifierWithIsolationXX` (ERROR
"extend/project columns [firm] reference names unresolvable even after
isolation [col='firm' ref='firm']", Lowerer.computedColumns:1144 via
scalarRelationalArms — a nested relation in scalar position): the
projection arithmetic crosses `$f.employeeByLastName('Smith2').firm
->toOne().employeeByLastName('Smith3').age->toOne()` — a FILTERED
qualifier, then a CLASS hop (firm), then ANOTHER filtered qualifier.
filteredNavLeafRead's hop peeling handles class hops between leaf and
filtered head, but a second qualifier AFTER the hop leaves a TypedProject
binding whose 'firm' column self-references (never materialized as a
join). Engine golden (testQueryStructure.pure:237): four chained LEFT
JOINs to filtered subselects — persontable_0(Smith), persontable_2
(Smith2), firmtable_1 (the hop), persontable_4 (Smith3) — with the CASE
arithmetic reading across them. Fix shape: the hop chain must register
each qualifier segment as its own filtered-subselect join descriptor and
re-key the continuation on the joined alias (this leg's hop emission
table), not re-enter filteredNavLeafRead per segment. Same machinery as
#70 chained/deep filtered navigation.

## Milestoning long-tail witnesses (2026-07-31, cycle-12 triage — Leg 2 owned)

- `testMilestoningContextPropagatedThruPropertyToViewWithNonMilestonedRoot`
  WRONG ANSWER (priority): expected `[1,Joe Martinez, 1,Joe Martinez,
  2,TDSNull]`, got `2,John Martinez` — the business-date context must
  propagate through the property INTO the view frame (engine filters the
  out-of-date version to NULL; we return the raw view row). Leg 2
  milestone: temporal context threading across ViewFrames (the ON-clause
  seam memory: temporal conds ride the resolver join-condition channel).
- `testMultiLevelIsolatedToSubSelectHasCorrectExtraColumns` ERROR: typing
  wall in mapping body — Boolean property
  `isBrexitClassificationTypeExchange` bound to an if-expression typed
  String (normalizer/typer if-branch unification in mapping bindings,
  milestoningmap2). Typer bucket, not temporal calculus.
