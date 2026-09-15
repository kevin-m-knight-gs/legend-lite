# The property-mapping translator, audited against the engine (2026-09-15)

Scope: `core/src/main/java/com/legend/normalizer/MappingNormalizer.java` (3,034 lines),
`JoinChainEmission.java` (1,062), `RelOpTranslator.java` (731), with `ViewRelation`,
`GroupBySynthesis`, `DeclaredCoercions` where a rule lives there. Method: the same as
docs/LEG2_STACK_AUDIT_2026_09_14.md — every rule that shapes rows gets (1) the engine's own source
line, (2) a judge (corpus rows or a witness), (3) a finding where the two disagree or where the
rule is ours alone. Engine tree: `$HOME/legend/legend-engine` at the pin (4.145.0); `P` =
`…/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure`, `H` =
`…/compiler/toPureGraph/HelperRelationalBuilder.java`, `X` = `…/RelationalCompilerExtension.java`,
`HF` = `…/core_relational/relational/helperFunctions/helperFunctions.pure`.

## 1. Citation census (the item 3 method)

Every engine name the translator package cites, checked against the engine tree: 35 citations;
29 resolve (18 to an exact class or file, 11 to a function or word in the sources); 6 do not.
Three are English words the scan caught (`Several`, `ColumnRefs`, `DYNAs`). Three are REAL:

| cited | where | truth |
|---|---|---|
| `PureModelBuilder.inferViewMainTable` | MappingNormalizer (view section javadoc) | no `PureModelBuilder` in the engine; the view's main table is `H:521–565` (`resolveMainTable` → `findMainTable` → `identifyMainTable`: the explicit `~mainTable`, else the ROOT table of every column mapping's element, exactly one or "contains multiple main tables") |
| `PureModelBuilder.addRuntime` | MappingClosures:35 | no such class; the include closure is the engine's `Mapping.includes` walk (`mappingExtension.pure`) |
| `functions_Mapping.pure:66` | StoreSubstitutionRewrite:244 and the leg 2 audit's R-target receipt | the function `_classMappingByIdRecursive` lives in `core/pure/mapping/mappingExtension.pure` |
| `com.gs.legend.compiler.MappingNormalizer` | MappingNormalizer:75 (class javadoc) | no such class in legend-engine; the sentence describes our own design, not an engine artefact |
| `functions.pure:190` (`resolvePrimaryKey`) | MappingNormalizer primary-key javadoc; UnionSynthesis | `HF:439–454` |
| `pureToSQLQuery.pure:5061–5074` (`getRelationalElementWithInnerJoin`) | synthTableBackedParts | `P:5077` at the pin |

Rule for 6c: a citation is a file and a line at the pin, or it is not a citation.

## 2. Rule inventory

| # | rule (ours) | where | engine receipt | judge | finding |
|---|---|---|---|---|---|
| T1 | main table: explicit `~mainTable`, else the ONE table every DIRECT column PM names; several → loud | `inferMainTable` | `X:316–360`: explicit, else the set of table aliases collected while processing the class mapping — exactly one table and one database, else "Can't find the main table … Please specify" / "Inconsistent database definitions" | corpus (every mapping without `~mainTable`) | READ TO THE END: the engine's alias map fills at `H:1172` (every direct `TableAliasColumn`, including those inside a dyna function) and a join's terminal element is processed with a FRESH map (`H:1182`), so joined tables never count — our direct-only rule is the engine's. Two corners differ (F3) |
| T2 | `~distinct` = DISTINCT over the MAPPED columns (plus the slot pseudo-columns when joins are present) | synthTableBackedParts | `P:5116` (`requiresAllProperties` when distinct: every property column projected) + `P:5162` (`distinct = getDistinct()`) | corpus `distinct` family | matches |
| T3 | `~groupBy`: aggregate decomposition; class-typed Join PMs navigate the GROUPED relation on the group keys | `GroupBySynthesis` | `P:5331` (`applyGroupBy` on the set's select: the grouped select IS the set's relation, so every join off the set hangs off it — the stage-2 rule) + `P:5116` (a grouped set projects every property) | corpus `groupBy` family | matches; (`P:6273` `processGroupBy` is the QUERY-side `groupBy`, not this rule — the earlier draft cited it wrongly) |
| T4 | `~primaryKey`: object identity for graph fetch and union threads; never a row-level distinct | (not lowered) | `H:1564` `processRelationalPrimaryKey`; `HF:439` `resolvePrimaryKey` (declared, else the table's PK) | corpus `importDataFlow` row; union family | matches; the javadoc's `functions.pure:190` is stale (§1) |
| T5 | `~filter` with an explicit INNER join type: the main table becomes the joined-and-filtered subselect, one row per matching child | `innerFilteredSource` | `P:5077` `getRelationalElementWithInnerJoin`; `P:5101` (`innerJoinFilterExists`) | `testInnerJoinClassMappingFilterWithChainedJoins` | matches; LEFT+WHERE realization is ours (null-rejecting only; tolerant loud — `nullTolerant`) |
| T6 | filter anchored at the chain TERMINUS | applyFilter | `P:5101–5140` (`applyTypeFilter` on the filter's join tree node) | `ChainedJoins` golden | matches |
| T7 | views: a view is a subselect (identity-carrying frame) | `ViewRelation.viewRelationExpr` | `P:5187` `ViewSelectSQLQuery` (`v:View | processRelationalMappingSpecification(v …)`) | corpus view family | matches for the FRAME path |
| T8 | views, fallback: when not `frameable`, the PMs are rewritten through the view's column expressions onto the single physical root table, view-level filter/distinct/groupBy merged, view-on-view flattened | `synthViewBackedMapping` fallback | NONE — the engine never flattens a view | census below | F-T8: an emission of ours. Rows equal today (the lanes) because the flattening is row-preserving for the shapes that take it; the frame path should absorb them (`frameable` false = mapping-level `~filter`/`~groupBy`, or a PM that reads a non-declared column) |
| T9 | view filter sequences before the mapping filter; both in WHERE before aggregation | `layerMappingFilterPreMap`, `filterBelowAggregation` | `P:5138` (`applyTypeFilter` after `applyGroupBy` on the SELECT; the view's own filter inside the view's subselect) | corpus (`testViewWithFilter…`) | matches in rows; our "filter below the groupBy" is the SQL-level equivalent (audit 18 finding 6) |
| T10 | otherwise-embedded: the partial's sub-PMs hoist; the fallback join rides as a pin + route | `emitOtherwiseEmbeddedHop` | `P:927` (`OtherwiseEmbeddedRelationalInstanceSetImplementation` → `navigateToOtherwiseMapping`), `P:1912` | corpus `otherwise` family | matches |
| T11 | enum mappings on columns (`EnumeratedColumn`) | `translateEnumeratedSource` | `P` enum processing (line TBD) | corpus `enum` family | cite |
| T12 | local (`+prop`) mapping properties bind like class properties | `translatePmToField` | `H:1511` (`processRelationalClassMapping`, local properties) | corpus `localProperty` family | cite the exact lines |
| T13 | a binding read TYPES AS the declared property with no SQL cast; a cast only on a genuine kind mismatch | `DeclaredCoercions` | engine: the result rows are the raw column values (`P` column processing) | corpus | cite |
| T14 | JSON-source sets: `get($row.data, 'prop')` + `to(@Type)` | synthJsonSourceMapping | engine `JsonModelConnection` (in-memory; no SQL) | XStore JSON rows | ours by design (the engine evaluates in memory); receipted by rows |
| T15 | inline-embedded set ids resolve across the include closure | `materializeInlineEmbedded` | `testMappingEmbeddedTargetIdsWithIncludes` (corpus) | corpus | cite the engine's include-closure set lookup (`mappingExtension.pure`) |
| T16 | routes: member / root / pinned-single / dead | `classifyUnionRoutes` | R-target, R-chain (leg 2 audit; re-cite `mappingExtension.pure`) | union family + W7 | done (legs 3–5) |
| T17 | join-chain slot dedup: physical hops by structured path, class hops by property name | `emitJoinChain` | engine `JoinTreeNode` merging by property path (`P` `mergeJoinTreeNodes`, line TBD) | corpus | cite |
| T18 | a Join PM whose target class has no set in the closure is dropped with the reason (the engine compiles it; not navigable) | JCE:725 | engine compile warning (grammar compiler) | corpus | cite |
| T19 | dyna-function translation (`RelOpTranslator`: 30 functions) | `RelOpTranslator` | engine `DynaFunction` registry (`…/relational/functions/…`) per function | PCT + corpus | each function's receipt is the PCT row; no per-function citation needed |

## 3. Findings

- **F1 (T8): the view-flattening fallback is ours.** The engine keeps a view a subselect always.
  Census (instrumented DuckDB lane, 2026-09-15): five mappings, seven sets take it —
  `classMappingFilterWithInnerJoin::mapping::testViewToTableMapping` and `testViewToViewMapping`
  (Person over `myPersonView`; view-on-view), `…::TestClassMappingsWithInnerFilterJoinedWithMilestoningDepthTwoNested`
  (two sets), `milestoning::UnionOnViewWithMilestoningMapping` (ProductClassification[option],
  [stock] over `ProductClassificationView`), `simpleRelationalMappingIncWithStoreFilter` (Person
  over `PersonView`). Every one has a mapping-level `~filter` or `~groupBy`, or a PM reading the
  physical root through the view — exactly the `frameable` exclusions. Plan: make the frame path serve those
  shapes (mapping-level `~filter`/`~groupBy` under a frame; PMs reading the physical root through
  the view — the engine's `findTableForColumnInAlias`), then delete the fallback (≈160 lines:
  `synthViewBackedMapping`'s second half, `layerMappingFilterPreMap`, `filterBelowAggregation`,
  `chainHasGroupBy`, `ViewRelation.rewritePmThroughView`, `inferViewMainTable`).
- **F2 (§1): three citations name engine artefacts that do not exist**; two more are stale lines.
  Fix in 6c.
- **F3 (T1): two corners of main-table inference differ from `H:1172/1182`.** (a) An expression
  PM that navigates a join contributes NOTHING in ours (`collectMainTables`, the `navs.isEmpty()`
  guard); the engine counts its direct column references and excludes only the join's terminal.
  (b) `OtherwiseEmbedded` is not recursed in ours ("preserved as-is; needs its own probe"); the
  engine processes an embedded mapping's property mappings with the same alias map. No corpus
  mapping has either shape (the lanes are EXACT either way). Fix both to the receipt, each with a
  witness, in 6c.
- **F4 (T3): resolved** — the set-level `~groupBy` is `P:5331` `applyGroupBy` on the set's own
  select (`P:5136`), so a join off a grouped set hangs off the grouped relation; the earlier
  citation of `P:6273` (`processGroupBy`) was the query-side `groupBy`, a different function.

## 4. What this audit does not cover
`AssociationSynthesis` (676), `MappingClosures` (430), `StoreSubstitutionRewrite` (401),
`XStorePureEnds` (303), `ImplicitInheritance` (226): the include/extends/association machinery.
Same method, next pass.
