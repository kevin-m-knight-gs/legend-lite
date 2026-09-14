# Leg 2 (the stack) — adversarial audit — 2026-09-14

Audited: commit `b0e01458b` ("Legacy routes as composition, leg 2: the stack"), against the tenets
the program runs on — every rule carries an engine receipt (golden rows, or the engine's own code);
loud, never quiet; no string matching where a typed fact exists; no fallbacks; one implementation
per question; measures reported honestly. Rows at the audited commit: DuckDB 108 / H2 444 EXACT,
chain green, CI green. The audit verifies receipts, not the design (the design was decided).

Grades: **HIGH** = a rule without an engine receipt, or a quiet arm that can change rows; **MED** = a
fact that lies or a shape that works by coincidence; **LOW** = tidiness with no row at stake today.

## Findings

| # | grade | finding | evidence | fix |
|---|---|---|---|---|
| F1 | HIGH | Two classification POISONS survive that the design (§11 A15) said die with the route lists: "NON-root mapping set … MULTI-route dispatch outside union members is a roadmap feature" and "MIXED root-set and union-member routes". Both DROP the property from the set's function (a quiet loss at synthesis; loud only if a query demands the property). With route lists both shapes are expressible: several pinned routes into a non-union class = a list of several; a root route beside member routes = a route whose target is the class extent. | `UnionSynthesis.classifyUnionRoutes` lines 288–299 | Emit route lists for both; a witness per shape (no corpus row exercises either — `-Drcorpus.test` census to confirm). |
| F2 | HIGH | The query-time pin rule (an arm's pinned navigation is dead unless its set is a leaf of the target class under the QUERIED mapping — `StackBuilder.leafSetIds`) is receipted by ROWS (the inclusive-union goldens, the same-store nested graph fetch) but not by ENGINE CODE: the archaeology stopped at `findMappingsFromProperty` / `processRelationalPropertyMapping` without finding the arm that nulls `oInThru`'s route. A rule fitted to two goldens may over- or under-apply (a pin to a NON-root set of a plain class from a union arm: leaf set = the root only → dead; the engine's `doJoinToClass` honours a pin by id for a single target). | `StackBuilder.liftOf` (the `leaves` filter); session notes | Homework before Leg 3 builds on it: the engine receipt (candidates: `State.idToClassMapping` construction, the routing `classMappingsForClass` cache, `getPropertyTargetSetImplementation`'s `rootClassMappingByClass`) and witness W4 (subclass-pinned route) plus a new witness: a union arm pinned to a non-root set of a plain class. |
| F3 | MED | `ClassBinding.Relational.propertyPins` records ONE pin per property (`putIfAbsent`): a set with two PMs on one property (`classification[stock]` and `classification[option]` on `BiTemporalProduct`, UnionOnView) loses the second. Harmless today (both are leaves) — a fact that lies. | `MappingNormalizer.propertyPinsOf` | `Map<String, List<String>>`; the dead rule = none of the pins is a leaf. |
| F4 | MED | Re-rooting types by column NAME: `StackBuilder.rechild0` (a node's own columns = its row minus the old child's row, by name) and `ClassSources.sameColumns` (a view projection IS the leaf's row when the column names agree in order). Structural in intent, name-based in mechanism; a node adding a column that shadows a child's column would mistype silently. | `StackBuilder.rechild0`, `ClassSources.rebaseRows` | Retype by the node kind (a project knows its output; a join slot adds its slot column) — the typed nodes already carry this; no name comparison. |
| F5 | HIGH | The MERGED lift (§9 shape 1, "uniform arms → one join, one key") is built on the target's PROPERTY columns: `sameTargetProperties` fires only when the route's target read is spelled like a property of the target class (`id` ↔ `id`). The engine merges by target COLUMN name across arms (`findFkListForEachSet` / the `Y0pk_Y1pk` naming) regardless of properties. A union target joined on a column that is not a mapped scalar property (or is mapped under another name) silently takes the STRICT routed form → rows may differ from the engine (the merged form matches by value across all arms; the strict form honours pins). No corpus row has the shape. | `StackBuilder.liftOf` merged branch, `sameTargetProperties` | Build shape 1 as designed: every arm projects its own target column under ONE merged key name; the merged predicate reads that key (never a property). Witness: a union joined on a non-property key column. |
| F6 | LOW | The four routed-union sites in `GraphEmission` pass different scopes: three pass `context.constructedScope()`, the child site (line 1133) passes `cs.scope()`. | `GraphEmission` 689 / 1133 / 1768 / 2572 | One rule (the context's scope); a witness under a constructed scope with a routed child. |
| F7 | MED | Two sources of truth for set pins: the new binding fact `propertyPins` (read by the stack's lifts) and the old stamped `MappingFacts.routedSets` behind `ctx.routedTargetSetOf` (read by `ClassSources.getForNav` for EVERY un-routed navigation, by the mixed builder, and by the graph-fetch set hint). A17 is half dead. | `ClassSources.getForNav`, `mixedMemberRoutes`, `GraphEmission` 1130 | Leg 3: `getForNav` reads the owner binding's pin; `routedSets` dies with the mixed builder. |
| F8 | LOW | `AssociationSynthesis.synthesizeAssociationMapping` still emits a class-level `legacyAssocPredicate` for a pair whose end class is union/inheritance-mapped, anchored on `anchorTableOf` = ONE member's table (first PM wins). Nothing should read it now that every such pair injects onto its member sets; a reader (`AssociationJoins.predicateMaterial`, taken when a navigation has no step) would join one member's table silently. | `AssociationSynthesis` 341–470 | Return null (no predicate) when either end class is operation-mapped; loud at demand. |
| F9 | LOW | Measures: M1 1,198 (target under 700). The residue beyond the allowed list (classification, the same-table inheritance collapse, the key-thread fact, the chain-walk helpers): `collectRoutedJoins` ×2, `subTypeDispatchProps`-era helpers (`embeddedOwner`, `addSubTypeDispatchCols` deleted; check the rest), `inheritanceMembers` / `collectInheritanceMembers` (needed), `recordKeyThreads` + `memberPrimaryKey` + `ownSharedKeys` + `tableKey` (the fact), `stackBody` / `memberFunction` (the emitter). M2 3, M3 1, M4 5 as recorded. | `wc -l`, the record | Leg 3 re-measures after the two builders fold in. |
| F10 | LOW | The four re-pinned witnesses assert SQL TEXT (`__route0_0`, `PA3` absent) beside their row asserts. Rows are the verdict; the text pins are shape witnesses for the lean-SQL ruling and must not become the reason a change is refused. | `UnionTargetLeanJoinTest`, `RoutedChainKeyTest`, `ResolveUnionTest` ×2 | Keep; label them shape pins in their names. |
| F11 | MED | Performance was not measured this leg. The design (§9) measured the three lowering shapes at 0.8 / 1.5 / 8.9 ms and promised the numbers per batch. The routed union of shape 4 duplicates a leaf per route (V3: two `Y0` arms) — correct rows, more work. The corpus's slow list shows no union row above the threshold. | the record; `leg2n/corpus-duckdb.log` slow list | A timing witness for the three shapes (plain, merged, routed) in Leg 3's record. |
| F12 | LOW | `StackBuilder.threadType` falls back to the recorded Pure kind when NO arm's row carries the thread's column → every arm projects a typed NULL under the thread name. A thread its owner arm cannot carry is a normalizer/store disagreement — should be loud. | `StackBuilder.threadType` | Throw when the owning arm (`t.ordinal()`, or the shared table's arm) lacks the column. |
| F13 | LOW | `addStc` / the lift's per-arm source columns use `putIfAbsent(arm, …)`: two entries for one arm in one group keep the first silently. | `StackBuilder.addStc`, `liftOf` source columns | Assert one value per (arm, column). |
| F14 | MED | `Pipelines.widenConcatenateForKeys` stays: made loud it lost 97 rows, all in unions the OTHER builders emit (`UnionHeads`, `mixedUnionSource`, class concatenates). A stack projects every column its lifts read; the widening is the other builders' debt. | the record | Dies with B6. |
| F15 | HIGH | The merged/strict split (SQL path merged, graph-fetch path strict) is receipted by two goldens (`snapshot::testUnionQueryOnNonTemporalRootWithTemporalProperty`, `rootLevel::testNestedUnion_SameStore`). The engine's SQL rule is broader than the condition that triggers MERGED here (`one entry per arm ∧ groups > 1 ∧ coversLeaves ∧ sameTargetProperties`): the engine unions the target sets whenever the arms' pms name MORE THAN ONE target set (`targets->size() > 1 → buildSQLQueryOutManySetImplementations`), one target set → a plain join. Shapes outside our condition (three arms, two pinned to one set; an arm without a route) take the strict form here and the merged form there. No corpus row; a row divergence is plausible. | `pureToSQLQuery.pure` 2552–2560; `StackBuilder.liftOf` | Homework: read `buildSQLQueryOutManySetImplementations` to the end (how the union's keys are named and matched — `findFkListForEachSet`, `buildColumnToNameMapForMappedFks`) and restate the merged condition from it; witnesses for the two out-of-condition shapes. |

## What the audit did not find

- No string matching on names that a typed fact already answers, except F4 (typing by column names)
  and F5 (property names standing in for column names).
- No optional parameter threaded through call sites (the leg's signatures changed: `navTarget`,
  `findBinding` by set id, `withInjectedPMs` with the lineage).
- No new quiet arm on the union path in the normalizer beyond the five named in the record (M4);
  the resolver's new code has the `continue`s of a filter (properties that are not scalar, keys an
  arm does not carry — each with the loud counterpart `keyReadMissing` / the lift's read check).
- Every corpus row that the leg turned green is named in the record; nothing was reclassified.

## The Leg 3 order this audit sets

1. **Receipts first (F2, F15, F5).** Read the engine to the end of the union path
   (`buildSQLQueryOutManySetImplementations`, `findFkListForEachSet`, the routing cache) and write
   the three rules down with their line receipts before touching the builder. Ratchet before the
   leg: name the rows.
2. **Witnesses (W1–W5 from the design, plus this audit's):** mixed Pure member, route into `~func`,
   importDataFlow, subclass-pinned route, route keys inside a merged scan; a union joined on a
   non-property key (F5); three arms two of which pin one set, and an arm without a route (F15);
   a union arm pinned to a non-root set of a plain class (F2); the two poison shapes (F1); a routed
   child under a constructed scope (F6); the timing witness (F11).
3. **The small fixes as one neutral batch (F3, F6, F8, F12, F13)** — facts that lie and quiet arms;
   rows unchanged by construction; chain.
4. **Shape 1 as designed (F5) and the two poisons as route lists (F1).**
5. **B6: `UnionHeads` and the mixed builder onto the stack**, then the widening (F14), the set-pin
   facts (F7), and `getForNav`'s dispatch die; the CastReRoot typed key; F4's retyping by node
   kind; re-measure M1–M4.

## Receipts (Leg 3 step 1, 2026-09-14)

Read to the end of the engine's union join path. Line numbers are the pinned engine
(`legend-engine-4.145.0`) and pure (`legend-pure-5.99.0`) checkouts.

**R-key — key naming is per COLUMN, not per lift** (`pureToSQLQuery_union.pure:373–420
findFkListForEachSet`, `:316 modifyColumnNameInOperation`). For each set on a side (source sets,
target sets), the join's columns on the set's main table are split: a column some
RelationalPropertyMapping of THAT set maps directly (`TableAliasColumn == column`) is MODELED and
named by the property (`pair(property.name, column)`; merged across the sets under one name); any
other column is NON-MODELED and named `<col>_<setIndex>` (one per set; NULL in the other threads).
With more than one set on a side the join's operation is rewritten through these maps
(`srcMap` / `targetMap`; a missing entry → `col_<index>`; one set → the bare column). The join is
the OR over the property mappings' rewritten conditions (`buildUnionJoin`, `canJoinTreeNodes…`).
Consequence: a pinned target is honoured iff the target key column is NON-MODELED (per-set name);
a MODELED target key matches every arm by value — the "merged" shape is not a lift-level choice
but the modeled-column case. `avoidModeledProperties` (`:433`, any null-join pm — an arm with no
route) forces every column NON-MODELED: with an arm without a route, every pin is honoured.
This restates F5 and F15: build the key per (target read column): modeled by the target set →
the property name shared by all arms; else `__route<g>_<k>`. The merged/strict predicate split
dissolves into one OR whose key names carry the rule. Graph fetch keeps per-pair keys
(`relationalGraphFetch.pure` builds children per set pair).

**R-target — the target union is the PINNED sets, resolved by the router**
(`core/pure/mapping/mappingExtension.pure findMappingsFromProperty`; `functions_Mapping.pure:66
_classMappingByIdRecursive`). The router collects the arms' `targetSetImplementationId`s and looks
them up with `_classMappingByIdRecursive(ids)`, whose filter is `$cm.id == $id` with `$id` the
WHOLE list — true only when the list has exactly one distinct id. So: one distinct pinned id →
that set (root or not, own or included); several distinct ids → EMPTY → the fallback
`rootClassMappingByClass(target)->potentiallyResolveOperation` = the class's ROOT under the queried
mapping (`_classMappingByClass … filter(root)->last()`: own mapping last = R1) resolved through
operations to its members. Property mappings whose target is not among the resolved sets are the
null-joined arms (the inclusive-union goldens: `pInThru` beside the includer's own `pInThru2`;
`multipleChainedJoins`: `y0`,`y1` = the Y operation's members; the same-store nested graph fetch).
This is F2's engine receipt and corrects `StackBuilder.leafSetIds`: the rule is "the root's
leaves" EXCEPT when every arm pins the same single set — then that set, whatever its root-ness.

**R-chain — chained routes merge by OR only as ordered-subset chains**
(`pureToSQLQuery_union.pure:870 canJoinTreeNodesBeSimplyMergedUsingOrOperation`): the routes'
mid-table chains sorted longest first; OR-merge iff every chain is an ordered subset of the
longest (a shared prefix); otherwise the chains are pushed into the source or target union
(`pushChainedJoinsIntoSourceUnion` / `…TargetUnion`: each arm roots at its mid). This is the
receipt for `JoinChainEmission.routeList`'s `uniformChainedRoutes` split (shared prefix → the last
hop's condition; per-arm → the mids inside the route's rows).

**Witnesses these receipts name** (step 2): W-a a union target joined on a NON-modeled column
(pins honoured, no cross-match); W-b three arms two pinned to one set (targets = 2 sets, modeled
key → cross-match); W-c an arm without a route (every column suffixed: pins honoured everywhere);
W-d union arms pinning two sets of a PLAIN class (several ids → the root only: the non-root pin
dies); W-e every arm pinning the same NON-root set of a plain class (one id → alive; today's leaf
rule would kill it — a known defect until step 4); W-f a modeled source key beside a non-modeled
target key (per-column naming on each side).
