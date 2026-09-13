# Normalizer clean sheet — homework, receipts, plan (2026-09-13)

Written after T4.1 closed (docs/T4_1_KNOWLEDGE_BEFORE_NORMALIZATION_2026_09_13.md §14). USER: "the whole
idea is that the compile step has all the facts so the normalizer is one simple AST-to-AST transform
for mappings only." This doc checks that idea against the engine's own source at the pin and the
corpus, names where our code diverges, and sets a batch plan. Nothing here is built.

## The plan in one paragraph (plain words)

Keep one function per set, never per queried mapping. A class function already reaches other
classes only through `Class.all()`, which the resolver answers under the mapping being queried, so
plain navigations already work the engine's way. The ONE leak is union/inheritance targets: the
generator bakes the union's member layout (member-numbered key columns, an OR condition) into the
navigating class's function, so that function depends on which mapping declares the union. Close
the leak by giving the union one key plus a member tag, keeping the navigating function ignorant of
the union, expanding the navigation in the resolver (where `Class.all()` is already resolved and
association predicates already become join conditions), and choosing the execution shape in the
lowering (one hash join when the predicate ignores the member; the join pushed into each member
arm when it branches per member). Around that: one resolved-mapping record before generation, the
engine's include rules adopted with receipts, error policy in one place, every quiet miss made
loud, one implementation per knowledge question.

## NOT YET VERIFIED — questions, not facts

1. **Rows under the engine's last-root rule (B2).** 43 corpus mappings have a class mapped in more
   than one included mapping. Our resolver walls that as ambiguous at query time; the engine takes
   the last root. Which corpus tests reach the wall today, and what they do under the engine rule,
   is unmeasured until B2 runs.
2. **The union navigation rewrite (B3).** SETTLED by B3.1 (2026-09-13): the non-uniform plan is
   measured (§6 B3: OR 8.9 ms nested loop, coalesce 1.5 ms hash, merged column 0.8 ms hash) and
   the resolver change is built and sized (GATES "Clean-sheet B3.1"). What remains unverified is
   B3.2's chained-route form against the `multipleChainedJoins` rows — SETTLED 2026-09-13:
   the engine pushes a per-arm chain's mids INTO the arm (§6 B3.2), and so do we.
3. **The 60 null-tolerant sites (B5).** How many are "the miss is the answer" versus a hidden
   wrong-all-along answer is unknown until each is made loud and the corpus adjudicates.

## 1. The shape (first principles, one paragraph per step)

1. Parse: records that mirror the text. 2. Resolve names: full names everywhere, never again.
3. Knowledge: one immutable index of classes, properties (declared, inherited, association-added,
adopted qualified), supertypes, enums, types, stores with includes resolved; a fixed set of
questions, one implementation, asked by normalizer and compiler alike; knowledge validated here.
4. Resolve the mapping graph: per mapping, the ordered visible mappings; cycles loud; store
substitutions resolved. 5. Resolve each mapping into a CLOSED record: every set has an identity,
every reference to another set (extends, union member, `prop[setId]` route, association end) is a
direct reference resolved by the engine's rules, extends flattened, identity sets from runtimes
added, implied sets made explicit, per-set store facts attached, structure validated. 6. Translate
each resolved set into one Pure function (the large, inherent part: the engine's relational
mapping semantics); ONE per set — a navigation names `Target.all()` and the queried mapping decides
the target set at query time (R6, R7); no policy, no fact-finding inside. 7. Emit functions +
binding table + facts stamped on it; the mapping DSL is dead from here. 8. Compile everything
through the one pipeline. 9. Later phases read the binding table and knowledge only.

Steps 3, 7, 8 exist after T4.1. Steps 4–5 exist as three objects plus a pre-pass with string set
ids surviving into step 6. Step 6 carries policy and re-resolves. That is the gap.

## 2. Receipts — the engine's include semantics at the pin (legend-pure 5.99.0 / engine 4.145.0)

`legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure`:

```
function meta::pure::mapping::_classMappingByClass(_this:Mapping[1], class:Class<Any>[1]):SetImplementation[*]
    $_this.includes->map(i | $i.included)->map(m | $m->_classMappingByClass($class))
                  ->concatenate($_this.classMappings->filter(cm|$cm.class == $class)) ...
function meta::pure::mapping::rootClassMappingByClass(_this:Mapping[1], class:Class<Any>[1]):SetImplementation[0..1]
    $_this->_classMappingByClass($class)->filter(s|$s.root == true)->last();
function meta::pure::mapping::_classMappingByIdRecursive(_this:Mapping[1], id:String[*]):SetImplementation[*]
    $_this.includes->map(i | $i.included)->map(m | $m->_classMappingByIdRecursive($id))
                   ->concatenate($_this.classMappings->filter(cm|$cm.id == $id))->removeDuplicates();
function meta::pure::mapping::classMappingById(_this:Mapping[1], id:String[1]):SetImplementation[0..1]
    ... $allClassMappings->toOne()->addAssociationMappingsIfRequired($assocPropertyMappings)
function meta::pure::mapping::_allClassMappingsRecursive(_this:Mapping[1]):SetImplementation[*]
    $_this.includes.included->map(m | $m->_allClassMappingsRecursive())->concatenate($_this.classMappings);
function meta::pure::mapping::allSuperSetImplementationIds(_this:Mapping[1], id : String[1]):String[*]
    $id->concatenate(... $_this->allSuperSetImplementationIds($setImplementation.superSetImplementationId->toOne()))
function meta::pure::mapping::_associationPropertyMappingsByIdRecursive(_this:Mapping[1], id:String[1]):PropertyMapping[*]
    includes first, then own association property mappings filtered by sourceSetImplementationId == id
```

**Rules these state.** (R1) Root set of class C in mapping M: collect includes' answers first
(recursively, include order), then M's own; the LAST root wins. So M's own root beats every
include; among includes, the later include beats the earlier; an include's own beats its includes'.
No error for a class mapped in several mappings. (R2) Set by id: the same walk; duplicates removed
by identity; `toOne()` — two DIFFERENT sets with one id in the closure is an error. (R3) A set's
association property mappings are the closure's association PMs whose source set id is that set's.
(R4) Extends chains resolve by id through `classMappingById` (the same closure walk).

`legend-engine .../compiler/toPureGraph/validator/MappingValidator.java` (compile time):

```
collectAndValidateClassMappingIds(mapping, mappingByClassMappingId, visitedMappings):
  includes first (recursively); then for each own non-embedded class mapping:
    if the id is already associated with ANOTHER mapping -> throw (id)
    if the id repeats within this mapping -> throw (id)
  -> "Duplicated class mappings found with ID '<id>' in mapping '<path>'"
MappingCompilerExtension: "Duplicated mapping include '<name>' in mapping '<path>'"
```

**Rule (R5).** The engine rejects duplicate class-mapping IDS across the include closure and
duplicate includes. It does NOT reject a class mapped in two included mappings under different ids
— that is legal, and R1 picks the last root.

Where the router applies them (engine at the pin):

```
core/pure/router/operations/router_operations.pure:48   getMappedLeafTypes_recursive: let foundClassMapping = $mapping->rootClassMappingByClass($type);
core/pure/router/store/routing.pure:390                  $mapping->rootClassMappingByClass($c)->potentiallyConvertRouterUnionToStoreUnionAndResolveSets(...)
core_relational/relational/relationalMappingExecution.pure:865-876  getPropertyTargetSetImplementation(sourceSet, property, mapping):
    OtherwiseEmbedded -> $mapping->classMappingById($o.otherwisePropertyMapping.targetSetImplementationId)
    RelationalPropertyMapping -> $mapping->rootClassMappingByClass($propertyReturnTypeClass)->potentiallyResolveOperation($sourceSetImplementation.parent); assertSize(1)
```

**Rule (R6).** `$mapping` is the mapping being executed, not the mapping that declared the property
mapping — receipt for the chain: `getPropertyTargetSetImplementation(…, mapping)` is called at
`relationalMappingExecution.pure:838` from `generatePropertySql(…, mapping, runtime, …)`, itself
called at `:755` from `processProperty` with the execution's `$mapping` (the mapping the runtime
executes against), and the router's own uses at `router_operations.pure:48` / `routing.pure:390`
pass the routing mapping. A navigation's target set is resolved in the QUERIED mapping's closure. Our generated
functions already honour this for plain navigations (`getAll(Target)`, §2b); the include-direction
re-synthesis block exists only because union targets do not (R7).

## 2b. Receipts — how a generated function reaches another class today

- docs/MAPPING_CLEAN_SHEET.md §2–§4: the hand-written form is
  `-> navigate(~firm: acme::Firm.all(), {p, f | acme::funcs::personFirmMatch($p, $f)})`; a mapping
  function "MAY NOT reference another class's specific mapping function by name (only
  active-mapping dispatch via `Class.all()`)". The "which set" question is answered at query time.
- `MappingNormalizerTest:1048`: the legacy-derived class-typed join "emits a pipeline
  `legacyNavigate(~firm: getAll(Firm), {s,t | <cond>})`"; `Anchors.java:34`: "SLOT's target is
  getAll-shaped BY CONVENTION". So a plain navigation does NOT bake in the target set.
- `AssociationJoins.java:1338-1420`: an association binding's predicate function is looked up in
  the include closure and turned into a column-space join condition (`propertyCondToColumns`) —
  the extension point for expanding a navigation into a union.
- `UnionSynthesis.recordKeyThreads` / `collectNavLifts` (the NAV LIFT block): "member i's thread
  carries its join keys member-suffixed (`<col>_<i>`, NULL in the other threads) and the navigate
  condition ORs the per-entry conditions" — the union's row layout leaking into the navigating
  class's function. The re-synthesis block (`routedTargetGainsOperation`, 2 corpus hits) exists
  only because of this.
- `router_operations.pure:57-70` `potentiallyResolveOperation(s, mapping)`: when the resolved
  target is an Operation set (union/inheritance), the ENGINE expands it into member sets at
  navigation time (`reprocessOperationForAssociationMapping`), under the routing mapping — the
  engine's own version of B3's resolver-side expansion.
- `MappingNormalizer.routedTargetGainsOperation`: the re-synthesis detector fires only for a
  ROUTED join (`targetSetId != null`) whose target class has a union/inheritance set under the
  including mapping and none under the declaring one — exactly the union leak, nothing else.
- docs/GATES.md "Non-uniform union witness + step 1b parked — 2026-09-13": uniform members
  coalesce to ONE key (landed, HASH_JOIN ≈ 1.5 ms vs the OR's BLOCKWISE_NL_JOIN ≈ 9.1 ms); the
  non-uniform case (members keyed on DIFFERENT columns, `UnionTargetLeanJoinTest`) keeps the
  per-member OR; the merged-column form was parked because "the union body's key registry holds
  ONE projected name per physical column per member" — the very leak above.

**Rule (R7), the one this doc adds.** A union's rows carry their key once plus which member they
came from. A navigation into a union is ONE predicate over (source, target); when the routes differ
per member it branches on the member tag. The generator writes no member numbering into any other
class's function. The lowering chooses: predicate independent of the member → one join on the
key; predicate branching on the member → the join distributes into the union's arms (join each
member with its own condition, then stack), so each arm is an equality join. Measured per the
witness before it replaces the OR.

## 2c. The union piece in plain words

A union set says "all Firms are the rows of FIRM stacked with the rows of FIRM_ARCHIVE". Today the
union's function copies each member's key column out under a member-numbered name (`FIRM_ID_0`
for rows from FIRM, `FIRM_ID_1` for rows from FIRM_ARCHIVE, NULL in the other member's rows), and a
class navigating to Firm has to write its join condition as an OR over those numbered columns.
Writing that OR requires knowing, while generating Person, that Firm is a union in the queried
mapping, how many members it has and in which order. That knowledge lives in the mapping that
declares the union, so Person's function depends on it; today a special case regenerates Person
under the including mapping (2 corpus hits), and the rejected plan would have generated every class
under every mapping. The fix: (1) the union's function carries its key once plus which member
the row came from; (2) Person's function says "navigate to `Firm.all()` where person.FIRM_ID equals
firm.id", the same words whether Firm is one table or a union; (3) at query time the resolver, which
already looks up what Firm is under the active mapping, joins Person to the union's rows on that
one key; when the two members are reached by DIFFERENT columns (the non-uniform case) the join
predicate branches on the member tag; (4) the lowering picks the execution: one hash join when the
predicate ignores the member, and when it branches, the join is pushed into each member's arm
(join Person to FIRM on FIRM_ID, join Person to FIRM_ARCHIVE on OWNER_ID, stack the results), so
the OR never appears. Person's function is then identical under every mapping.

## 3. Receipts — the corpus (probe run 2026-09-13, DuckDB lane, one graph)

| count | value |
|---|---|
| mappings in the graph (user + boot) | 679 |
| own class-mapping sets | 2,058 |
| lifted functions today | 1,703 |
| mappings with includes (closure > 1) | 134 |
| extra (mapping, included set) pairs summed over mappings — the ceiling the rejected per-mapping-functions plan would have paid | 779 |
| include-direction re-synthesis hits today | 2 |
| mappings with a class mapped in > 1 included mapping ("ambiguous" today) | 43 |
| largest closures | Calendarmap, milestoningmapwithconstraints: 25 visible sets; the graphFetch resultSourcing three: 24 |

## 4. Where our code diverges from the receipts

| ours | engine | consequence |
|---|---|---|
| `ClassSources.findBinding`: a class bound by two included mappings → `MappingResolutionException("ambiguously mapped … via includes")`; its javadoc says "real Legend errors on duplicate class mappings" | R1 + R5: legal; last root wins; the engine errors on duplicate IDS, not duplicate classes | 43 corpus mappings carry the shape; any query navigating to such a class walls where the engine answers |
| `MappingClosures.sets` / `findSetById`: own first, else includes with the LATER include overriding | R2: includes first then own, duplicates by identity removed, then `toOne()` — two different sets under one id is a compile error | we resolve silently what the engine rejects; `MappingValidation` should carry R5 |
| `unionOf` / `inheritanceOf` (formerly `unionForClass`): own first, else includes depth-first FIRST-wins | R1: operation sets are class mappings; last root wins | diverges only when two includes each declare an op for the same class — measure |
| roots (`collectRootClassMappings`): includes first, own overrides, later include overrides earlier | R1 verbatim | agrees |
| the include-direction re-synthesis block (`routedTargetGainsOperation`) | R6: every navigation resolves in the queried mapping; no special case | 2 hits today; the uniform rule subsumes it |
| `MappedClasses`: GLOBAL over every mapping in the graph | R1: per queried mapping's closure | an approximation kept in T4.1 step 2 |

## 5. Static inventories (HEAD after T4.1)

Set-id string plumbing (`setIdOf`, `findSetById`, `.setId()`, effective ids) per file:
MappingNormalizer 27, UnionSynthesis 17, XStorePureEnds 9, AssociationSynthesis 6, JoinChainEmission 5,
M2mRouteGuards 3, ImplicitInheritance 3, SetDispatch 2, MappingPrePass 2, MappingFacts 2,
AggregateViewLift 2, StoreSubstitutionRewrite 1, SetKeyFacts 1, MappingClosures 1 (81).

Null-tolerant sites (`orElse(null)`, `knownMiss`) per file: UnionSynthesis 14, MappingNormalizer 9,
ViewRelation 7, JoinChainEmission 7, RequiredNullableCensus 3, MissProbe 3, MappingClosures 3,
DeclaredCoercions 3, MappingValidation 2, ImplicitInheritance 2, AssociationSynthesis 2, one each in
StoreSubstitutionRewrite, RelOpTranslator, ModelNormalizer, ModelJoinNesting, M2mRouteGuards (60).

Policy or facts written INSIDE the translator (outside the driver): `UnionSynthesis:339`
(`p.droppedRoutedProps.add`), `:389` (`ledger.mixedUnions.put`), `:2408` (`ledger.poisons.merge`).
The first two are facts the translation produces; the third is policy.

Compiler-side twins of kernel rules: `PureModelContext.isSubtype` (typed lattice, primitives
INCLUDED — `Integer` under `Number`) versus `KnowledgeLayer.isSubtype` (mapping calculus, primitives
excluded): two questions, both real, to be named; `PureModelContext.findProperty` versus
`KnowledgeLayer.propertyType`; `StoreCompiler.findTableDef` (exact case, `default` schema rule)
versus `KnowledgeLayer.table` (case-insensitive, any schema); `StoreCompiler.columnType` versus
`RelationalKinds`; `MetamodelSeeds.viewBaseTable` versus `ViewRelation.inferViewMainTable`.

## 6. The plan — six batches, each landable alone, each corpus-measured

**B1 — one resolved mapping record (no semantics change).** `ResolvedMapping` replaces
`MappedClasses` + `MappingClosures` + `MappingPrePass.PrePassed`: sets with identities; every
extends/member/route/association reference a direct reference (resolved by today's rules, so 0 rows
move); identity sets; implied sets; declared keys; per-set store facts from the kernel. The 81
string sites read the record; `findSetById`, `setIdOf` and `memberOrdinalOf` become record
accessors or die. Expected: 0 LOST / 0 GAINED. Pins: the reach-back and walker censuses.

**B2 homework note (probe, 2026-09-13).** The 43 mappings with a class mapped in more than one
included mapping sit almost entirely in two corpus families that declare their OWN union set over
the included ones (`union::multipleChainedJoins` 20, `union::extend` 16): there the resolver finds
the local binding first and the ambiguity wall never fires. The wall's real exposure is the seven
outside those families (milestoning 2, embedded::advanced 2, classMappingByClass 2, lineage 1).

**B2 — the engine's rules (semantics change, receipted).** R1 for roots and operation sets (last
root; delete `findBinding`'s ambiguity wall), R2 + R5 as validation (duplicate ids across the
closure are a MODEL error: strict throws, module walls the mapping), duplicate includes an error.
Expected: rows may GAIN (tests that hit the wall today); any LOST row adjudicated with R1/R5 as
the receipt.

**B3 — union navigation by the route's member column (R7, corrected by homework).** (The
earlier draft of this step generated every visible set's function under every mapping, ceiling
+779 functions. Rejected: plain navigations already dispatch through `Class.all()`; only union
targets leak, and the fix for that is a resolver change, not more functions.)

*The first draft of this paragraph was wrong in one detail, found by reading the code with the
question in mind (NOT YET VERIFIED item 2):* "the union projects its key once" assumes a union has
ONE key. It does not. A route into a union member joins on whatever column that member's table
has for THIS navigation (`P1.FIRM_ID` for a firm's employees, `P1.ID` for an address's person);
the union can only know those columns by scanning every other class's routes into it — which is
exactly today's `collectInboundRouteKeys` (245 union bodies in the corpus scan their whole
closure), the dependence B3 exists to remove. So the union projects NOTHING for other classes'
benefit. Instead:

- **The navigating class names the column per set.** Where a route into set `p1` reads the
  target column `FIRM_ID`, the function says `memberColumn($t, @Integer, 'p1', 'FIRM_ID')` — "the
  FIRM_ID of rows that came from set p1, null for every other row". The set id is declared in the
  navigating class's OWN property mapping (`employees[p1]`), the column and its kind come from
  the Join and the store. Nothing here depends on whether `Person` is one set or a union in the
  queried mapping, how many members it has, or in which order — so one function serves every
  mapping (R6), and the member ORDINAL, the only union fact the navigator reads today, is gone.
  Routes of one property whose conditions differ only in the target column merge into ONE read
  (`memberColumn($t, @Integer, 'c1', 'FIRM_ID', 'c2', 'OWNER_ID')`) and ONE equality; routes whose
  source side differs stay an OR (branching predicate; the corpus has none outside the chained
  family below).
- **Every union arm says which sets it holds.** The union body wraps each thread as
  `unionArm(rows, ~[p1: {r | gate}])` — one set per plain thread, several with their filter
  gates for a merged single-table scan. The union's own outbound navigations, its primary-key
  threads for `importDataFlow` and its identity keep their member-numbered names: those are the
  union's own business, spelled by the function that declares the members.
- **The resolver widens on demand.** When a navigate's predicate is resolved against the target
  under the active mapping, each `memberColumn` becomes a plain read of a freshly minted column,
  and the target's arms are widened: an arm holding a named set reads that set's column (gated by
  the set's filter inside a merged scan; ungated when the demand covers every set of the arm —
  which reproduces today's `__pk` shared-key form for the metamodel hierarchy); an arm holding
  none reads a typed NULL. This is `Pipelines.widenUnionMember`'s existing plain-name path
  (511 widenings in the corpus, all plain — the `<col>_<i>` branch fired 0 times) with the
  per-set decision added. A lone set target (no union) widens as one arm: its set id must be
  named or the route is loud.
- **Nothing in the lowering.** A predicate that ignores the member is one hash join.
  Measured on the witness shape (1,000 firms × 10,000 people, members keyed on DIFFERENT
  columns): today's OR 8.9 ms BLOCKWISE_NL_JOIN; `coalesce` over per-set columns 1.5 ms HASH_JOIN;
  one merged column 0.8 ms HASH_JOIN. The merged column is what the resolver's widening
  produces, so the earlier parked "step 1b" arrives without its key registry (the normalizer no
  longer mints any inbound key name). The join-over-union rewrite rule stays a product option
  for the branching case only.

**Census (DuckDB corpus, temporary printlns, removed, 2026-09-13; 108 fails unchanged):**
412 routed-navigation groups over 84 mappings — 226 single-route, 145 multi-route with one
shared condition (today's coalesce), 23 shared-primary-key groups (all in our own
`MetamodelMapping`, up to 21 members), 44 properties whose routes differ per member (the
`inheritance`/`association::inheritence` `vehicles`/`roadVehicles` families, `unionMappingWithSelfJoin`,
`biTemporalUnionMapping`), 18 chained per-arm groups (15 properties: `multipleChainedJoins` V2–V5
across 2/3/4 sets, `unionMappingWithJoinSequenceInProperty` ×2, `unionOfViews2`); 245 union bodies
scan inbound routes; 14 mixed-union sources and 6 suffix strips (one test,
`XStoreUnion::inMemoryAndRelational`); the union-to-union member-paired arms
(`memberPairedCondition`, `chainedUnionHop`, `pairChainedUnionHop`) fired 0 times — their only
witness is `ResolveUnionChainTest`, whose rows judge the new form.

**B3.1b — the member owns its link key (USER review of B3.1, 2026-09-13).** B3.1 put the
per-member fact on the navigating class as `memberColumn($t, @Kind, 'p1', 'FIRM_ID', 'p2',
'FIRM_ID')` — mapping-DSL set ids as string literals inside generated Pure, a `unionArm` marker
tagging every thread by set id, and a registry to carry that identity through the resolver. USER:
"did you just carry mapping DSL syntax into our clean function design?" Yes. The version that a
person would write by hand (docs/MAPPING_CLEAN_SHEET.md §2, §3, §4.2 `+local`, E6): each member
set's function binds a mapping-local property to the column of ITS OWN table that links it —
`^Person(lastName = ..., +firmKey = $r.FIRM_ID)` in p1 and p2, `+firmKey = $r.OWNER_ID` in c2 — the
union is the members concatenated. Each set publishes ONLY its own key; a link is defined in
one of three places, always by its owner (USER, 2026-09-13): (1) the member's own function
navigates OUT on its own key — `-> navigate(~firm: Firm.all(), {p, f | $p.firmId == $f.id})`,
the DSL's per-member `firm: @f_p1` — needing only Firm's published `id`; (2) an Association is
bound ONCE, `Firm_Person: AssociationMapping { {p, f | $p.firmId == $f.id} }`, both directions
derived, neither class function mentioning the other; (3) a plain class-typed property with no
association keeps its predicate in the owning class's function, reading the other side's one
published key — `navigate(~employees: Person.all(), {r, p | $r.ID == $p.firmId})`. Union or
single table, uniform columns or not, the words are the same in all three. Visibility rule (the
one semantic addition): a local is readable by navigate predicates of functions and association
bindings in the same Mapping or in a Mapping that includes it, and invisible to queries. Two
groups owning Person and Firm: the DSL already makes Firm's author read Person's tables and set
ids through the Join; here each group publishes one name of its own. When an owner did not
publish a key, the including mapping re-binds that member with the extra local — today's
re-synthesis block, kept for exactly that case. The translator today folds association mappings
into both class functions as navigates; keeping the predicate in the association binding is the
reverse of that fold (the `legacyAssocPredicate` bridge exists) — done in this batch if the
corpus rows stay put under it, else split off as B3.1c.

What the translator generates from the DSL to match: for route `employees[p1]: @f_p1` with
`f_p1(FIRM.ID = P1.FIRM_ID)`, member p1's thread projects `P1.FIRM_ID` under the key name and
Firm's navigate compares `FIRM.ID` with `$p.<key>`. The key NAME is derived from the navigating
SET and property (`Firm_employees`; a union member navigating carries its own set id, so
`a1_b` and `a2_b` keep the engine's member pairing — the `ResolveUnionChainTest` trap row), plus
a position suffix for composite conditions; a hand author names by meaning. Members not routed
project a typed NULL under the name (the engine's un-routed thread), a merged single-table scan
gates by the member filter as every other column does. The typing bridge for `legacyNavigate`
declares the key's kind from the store (a typing shim, no semantics). Deleted: `memberColumn`,
`unionArm`, `MemberColumns`, both natives and their pins, the per-set widening and the raw-scan
wrap; the inbound route scan over the member's mapping closure returns (it is the translator
finding the member's own link column from the Join, not the union learning about navigators).
Kept from B3.1: the shape grouping in the navigator, `Pipelines.widenForCondition` and the
consumers that widen (harmless: the columns are now projected by the body), the flat-equi-key
guard, the deleted union-to-union arms.

**B3 in three measured slices** (each 0 LOST on both lanes, each its own GATES record):
- **B3.1 — single-hop routes.** `memberColumn` in the IR (registered signature, generic typing
  through the annotation argument, never reaches the lowering); `routedNavigation` and the
  union's own lifted routed navigations emit it; the inbound scan stops feeding single-hop keys
  and the shared-key form; `unionArm` markers; the resolver's demand type and per-set widening;
  the mixed-union child route reads the column per arm structurally (`stripMemberSuffix` dies);
  the `<col>_<i>` widening branch and the three dead arms die. `CastReRoot` reads the union's own
  shared primary-key thread, which the union keeps for members sharing one table.
- **B3.2 — chained routes (LANDED 2026-09-13).** Two receipts, read AFTER the first attempt
  failed. (1) `testUnionWithChainedJoinsAcross2SetsV2/V4` goldens join the chain's mid OUTSIDE
  the union on the navigator's side (`X0 left outer join A as "a_0"` … `on ("a_0".fk1 =
  "unionalias_0".fk_1 or "root".fk = "unionalias_0".fk_0)`). (2) `testUnionWithChainedJoinsAcross
  3SetsV4` and `unionOfViews2` (`testUnionOfViewsWithFilterInQualifiedPropertyAndNonOverlapping
  JoinSequnece`) goldens root each arm at the chain's FIRST mid and project that mid's column as
  the arm's key (`select "root".fk0 as fk0_1 … from A as "root" left outer join Y1 … left outer
  join G`; `from midTable as "root" inner join PersonExtensionT1 …`). Form (1) built and measured
  first: every `multipleChainedJoins` V4/V5 row and both `JoinSequenceInProperty` rows LOST —
  two mids joined as navigator siblings multiply the rows (each union row matches through one
  disjunct while the other mid's rows fan out: 5 → 13, 5 → 9 after shape-indexing by prefix), and
  a three-hop prefix hits the resolver's deep-composite wall. Form (2) is the engine's general
  rule and row-correct; (1) is its special case for one mid. REVERTED (1), kept push-into-arm.
  What B3.2 changed: the arm's chain key is spelled by the LINK-KEY rule — `linkKeyName(navigating
  identity, property, shape, position)` over the route's FIRST hop (`routeKeyCondition`: last hop
  for single-hop and shared-prefix routes, first hop for a per-arm chain; both sides call it with
  the group's `uniform` verdict) — and is a published FACT like any key (`linkKeys`: name → the
  mid's column), so an includer whose closure adds such a route (`extend::` mapping, child set
  inheriting the parent's chained PMs) re-binds the union by the same publication comparison;
  the union's inbound chain scan reads the PRE-PASSED closure records (`MappingLedger.closure
  Records`) like the publication does; every thread projects the union-wide key names in ONE
  order (the concatenation aligns by position — a thread that skipped a name got its columns
  renamed by position by `ConcatenateChecker`, the "TypedRename above join slot" wall): the
  owning thread reads a chain key off its mid slot, a sibling types the NULL by the mid's
  column, a member's own published column beats a sibling chain's NULL. The navigator's in-arm
  branch and `RouteEntry.inArm` are gone: every route, chained or not, reads one link-key name.
  The `col__prop_ord` spelling is gone from every navigator; `fk1__z_1`-style names remain ONLY
  as a union's own lifted-chain source keys (thread-internal, never read by another class).
  OWED: a member's own outbound lift and an inbound chain can join the same mid twice (2SetsV4:
  `A` as t5 and t7 in y1's thread — pre-existing, dedup is by alias); `routedTargetGainsOperation`
  stays (include-direction reclassification). Judge: `multipleChainedJoins` ×20, `JoinSequence
  InProperty` ×2, `unionOfViews2`; 108 / 444 EXACT.
- **B3.2 AUDIT (2026-09-13, while CI ran; receipts verified, not designs).** VERIFIED: no
  ordinal or set-scoped key spelling remains on the navigator side (the only `"__"` left in
  `JoinChainEmission` is the slot-alias uniqueness mint); `UnionSynthesis` keeps `__prop_ord` only
  for a union's own lifted-chain SOURCE keys (2760–2762, thread-internal); first chain G2–G9 exit 0,
  G1 red on the census pin alone, G7 at its floor; the witness reads `a0_b` off the chained arm.
  FINDINGS, ranked, none fixed here (an audit verifies; fixes are batches):
  1. **Concatenate aligns by POSITION and renames silently** (`ConcatenateChecker.positional`):
     that is Pure's spec for user-written `concatenate`, but for the SYNTHESIZED union threads a
     column-order drift produces renamed columns and, at best, the resolver's "TypedRename above
     join slot" wall — the B3.2 failure mode. The union driver must assert every thread's column
     names IN ORDER equal the first thread's and fail as a normalizer bug otherwise. → B5.
  2. **A second chain into the same member under the same key name is dropped silently**
     (`registerInboundEntry`'s `dup` check, pre-existing): two chained routes from one navigating
     set and property into one member with different first hops but one shape now share a name
     and the second never registers. Loud, or a second shape. → B5.
  3. **Chain facts yield silently** (`facts.putIfAbsent`) where single-hop facts throw on a
     conflicting column: a member routed to both directly and through a chain from the same
     navigating set, property and shape gets ONE name for two different columns; the thread reads
     the chain, the fact says the own column. Same loud guard as the single-hop path. → B5.
  4. **The link-key fact is overloaded**: `linkKeys` says set → name → column, and for a chain
     key the column belongs to the MID, not the set's table. Threads read it correctly (chain read
     first); the resolver's `linkKeyOnArm` would fail with the wrong words ("does not carry its
     link key column"). The fact should carry where the column lives: `LinkKey(name, db, table,
     column)`. → B6 (one typed fact, one reader).
  5. **The route group is derived twice**: the navigator's group is the property's routes into
     union members (`unionRoutes`, mixed root/member routes poisoned); the publisher's group is the
     routes of (navigating set, property) whose targets are in `memberIds` — union members in the
     driver, EVERY set in the publication. The comment at `UnionSynthesis` ~258 says one predicate;
     the inputs differ. One group function, both callers. → B6.
  6. **`closureRecords.isEmpty()` falls back to the raw closure silently** (driver ~996): a scratch
     ledger never synthesizes, so the fallback covers nothing but a future misuse. Require it. → B5.
  7. **Duplicate mid join in an arm** (2SetsV4: `A` twice in y1's thread — a member's own outbound
     lift and an inbound chain each wrap the mid; dedup is by slot alias). Pre-existing; dedup by
     (table, condition). → B6.
  8. Record text: the B3.2 GATES record says "Diff: 7 files"; the commit touched 8 (the census
     registration). Corrected in the next docs commit.
- **B3.3 homework (2026-09-13, measured on the DuckDB lane).** Three pre-pass rewrites make
  "implicit" content, all per mapping over its own closure already: `extends` flattening (explicit
  text), same-extent inheritance (`ImplicitInheritance.apply`: 10 sets on the corpus inherit an
  ancestor's PMs over the same table), implicit `Inheritance` ops for routed targets
  (`implicitOpsForRoutedTargets`: 7 mappings, e.g. the metamodel's `Store`/`Relation`, the
  inheritance families' `Vehicle`/`RoadVehicle`, `FunctionScope`). None of them is a GLOBAL
  phase; they are steps of one mapping's construction that today run before `ResolvedMapping`
  exists, which is why five sites build a bare `MappingView` (the pin). The one graph-wide fact
  is `MappedClasses` ("is class X mapped anywhere in the graph"), read at seven sites through
  `classTypedTargetIfMapped` and three direct reads. PROBE at that chokepoint: the global answer
  differs from the closure answer (engine R1) at 28 (mapping, class) pairs, always global=true /
  closure=false — e.g. `unionMappingWithFunction` maps Person with `firm: @PersonSet1Firm` and
  no set for Firm in its closure; the engine compiles such a PM (a missing target set is a
  compilation WARNING for association ends, `TestRelationalCompilationFromGrammar:2867`) and the
  property is simply not navigable under that mapping. Ours today navigates it through whichever
  mapping happens to map Firm. Design: (1) `MappingView` merges into `ResolvedMapping`, built in
  ONE construction per mapping — surface → extends → same-extent inheritance → store refs →
  implicit ops → validation — each step a rewrite of the record under construction
  (`withMapping`), so every closure question is asked of the record; the includer's re-bind
  question (`routedTargetGainsOperation`) asks the DEFINING mapping's resolved record from the
  ledger; (2) `MappedClasses` dies: the ledger answers `isMapped(class)` over the mapping's
  PRE-PASSED closure (own record + included records, implicit ops included), R1; a class-typed
  Join PM whose target has no set in the closure is DROPPED from the synthesized function with a
  ledger line (the engine's "not navigable here"), never a structural join; (3)
  `TransitionalShapesTest` and `MappedClassesTest` go, a closure-local witness replaces the
  latter. Expected: the 28 flips change no row (no corpus test navigates such a property under
  such a mapping — the engine's own tests could not); any LOST row is adjudicated against the
  engine's rule above.
  BUILT 2026-09-13: 2 rows LOST on the first run, both `projection::qualifier::testFilterIn
  QualifierWithFilterInMapping*`: the query runs under `productMappingWithFilter`, which INCLUDES
  `productSubMappingWithFilter` (Product with `synonyms: @Product_Synonym`, no Synonym set in
  the sub-mapping's closure — dropped there, correctly) and maps Synonym itself. Engine R6:
  every navigation resolves in the QUERIED mapping — the includer must re-bind the included
  Product set with the navigation. Third re-bind criterion in `resynthesizeIncluded`
  (`unmappedTargetGainsSet`: a class-typed Join PM dropped under the defining mapping's closure
  whose target the includer's closure maps), spelled with the same two predicates the emitter
  uses. Then 108 / 444 EXACT. `MappingView`, `MappedClasses`, `TransitionalShapesTest`,
  `MappedClassesTest` deleted; `MappedInClosureTest` is the witness (per-closure answers, an
  include brings its sets, order independence kept).
- **B3.3 — implicit sets at resolution (LANDED 2026-09-13, see the homework above).** The
  implicit sets are steps of one mapping's construction; `ResolvedMapping` is built in ONE
  construction from a mapping's text and its closure; `MappingView`, `MappedClasses` and
  `TransitionalShapesTest` deleted together. USER 2026-09-13: "let's make sure it does not stay
  this way" — it did not.

**B3 ARC AUDIT (2026-09-13, after B3.3; what the plan said would die, checked in the code).**
DELETED as promised: `memberColumn`, `unionArm`, `MemberColumns`, both natives and their pins
(B3.1b); `stripMemberSuffix`, `routesMerge`, the union-to-union arms, the coalesce form, the
`__pk` ROUTED form (B3.1; the union's own shared table-key thread `<col>__pk_<table>` stays, as
written); the navigator's in-arm branch, `RouteEntry.inArm`, the `col__prop_ord` navigator
spelling (B3.2); `MappingView` and every `MappingView.of` site, `MappedClasses`,
`TransitionalShapesTest`, `MappedClassesTest` (B3.3). The remaining `memberColumns` /
`unionArmsPrunable` names in the resolver and lowering are unrelated (a union head's own columns;
SQL union arms).
KEPT AGAINST THE ORIGINAL TEXT, by receipts, each written where it happened: the push-into-arm
threads, `chainsSink`, `inboundArmSteps`, `LiftChain` (B3.2: the engine's 3-set / unionOfViews2
goldens; the navigator-side form multiplies rows).
DEFERRED — owed, not done:
1. **The re-synthesis block did not die; it grew.** The original B3.2 text: "with no ordinal left in
   any navigator, the re-synthesis block and `routedTargetGainsOperation` die"; the plan's R6 row:
   "every navigation resolves in the queried mapping; no special case; the uniform rule subsumes
   it". Today `resynthesizeIncluded` carries THREE criteria (`routedTargetGainsOperation`,
   `includedOperationGainsLinkKeys`, `unmappedTargetGainsSet`), each a special case of R6 found by
   a row. The uniform rule — an included set is synthesized under the QUERYING mapping's closure,
   never reused from the defining mapping when its navigations differ — is the design; the block
   is its approximation. → its own slice before B6 (measure: how many included bindings differ
   between defining-closure and includer-closure synthesis; the answer is the cost of the uniform
   rule).
2. **"Implicit sets become resolve-time answers" was reinterpreted.** B3.3 kept the appended
   `Inheritance` set (`implicitOpsForRoutedTargets` still appends to the record) as a step of
   the one construction; it is not an answer computed at the point of resolution. Consistent with
   the pins (the implied set IS a class binding), and per mapping over its own closure; but the
   construction still materializes a set the author never wrote. → B6 candidate; no row depends
   on which of the two it is.
3. **"One construction" has one rewrite outside it**: the multi-hop association injection
   (`injectMultiHopAssociationPMs`) runs in the driver (`normalizeMapping`, `withMapping`) after
   `MappingPrePass` returned. It needs only the record and the model → move into the construction.
4. **A union's own OUTBOUND lifted chains still spell `col__prop_ord`** (`UnionSynthesis` ~2762,
   thread-internal: the source side of the union's own navigation through its mids, never read by
   another class). The original B3.2 text said the property-scoped chain keys die; the inbound
   ones did, the outbound spelling stays. → the link-key rule applies on the source side too:
   one key name per (property, shape), the member's own prefix — B6.
5. **The resolver's mixed-union child arms mint per-ordinal key names** (`ClassSources` ~406:
   `k__<prop>__<ord>_<k>`), the resolver-side sibling of the deleted normalizer spelling. Not in
   generated Pure; still an ordinal identity. → B6.
6. **`CastReRoot` finds the shared key by `startsWith(prefix + col + "__pk")`** — a string-prefix
   identity read (the string-hacking audit's rule: no new `startsWith` identity arms; this one is
   old). → B6 with the typed link-key fact (audit finding 4 of B3.2).
CLOSED OWED ITEMS: T4.1's "mapped-class fact is GLOBAL (engine asks per closure)" — closed by B3.3.
STILL OPEN from earlier records: `inferViewMainTable` + `MetamodelSeeds.viewBaseTable` (store fact),
owner-absent adoption silent, the parser flattening schema tables, the duplicate mid join (B3.2),
the B3.2 audit's eight findings (B5: 1–3, 6; B6: 4, 5, 7).

**B4 — policy out of the translator.** The translator reports (throws) and produces facts; the
driver alone applies strict/module; `UnionSynthesis:2408` moves to the driver's ledger; the
dropped-route and mixed-union facts stay facts. Expected: 0 rows.

**B5 — every guard loud.** The 60 sites, one println census over the corpus first (the F7.8
method), then each site: loud, or documented as "the miss is the answer" with the receipt.
Expected: the corpus names the wrong-all-along answers; each a FIX or an ACCEPT row in the ledger.

**B6 — one implementation across E and F.** `PureModelContext.isSubtype`/`findProperty` over the
kernel with the two subtype questions named (typing lattice vs mapping calculus); the store twins
reconciled by receipt (the engine's `schema('default')->table()` rule, case sensitivity) into the
kernel; `inferViewMainTable` and `viewBaseTable` become one compiled-store fact. Expected: 0 rows.

Order: B1 → B2 → B3 → B4 → B5 → B6. B2 is the only one expected to move rows by design (engine
receipts R1/R5); B3 changes SQL shape and plans, judged by the union witness and its measurements.

## 7. Stop rules and done criteria

The T4.1 doc's §12 stop rules apply unchanged. Done per batch: the census pins moved down with the
batch's name; 0 LOST or every LOST row adjudicated against a receipt in §2; chain green; GATES
record; ledger row here; CI green on the full sha.

## 8. Ledger

| batch | landed | rows | pins | record |
|---|---|---|---|---|
| B1 — `MappingView` (transitional, pinned) + `ResolvedMapping`; nine walkers deleted; synthesis takes the record | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED | none | docs/GATES.md "Clean-sheet B1" |
| B2 — R1 last-wins in the resolver and for operation sets; R5 duplicate ids and duplicate includes rejected; the ambiguity wall deleted | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED (probe: 0 wall hits, 0 duplicate ids, 0 first-vs-last differences) | own-corpus 2405 → 2425 | docs/GATES.md "Clean-sheet B2" |
| B3.1 — `memberColumn` (a routed navigation's target read per SET, minted by the Typer) + `unionArm` markers + the resolver's per-set widening; the inbound key scan cut to chains; the union-to-union arms, the suffix stripper, the `__pk` routed form, the coalesce form and `routesMerge` deleted | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED (from 144 / 143 LOST on the first run — twelve consumers found by rows) | ArchitectureTest register +1; INTERNAL_DESUGAR 16 → 18; ResolveUnionTest asserts the member column | docs/GATES.md "Clean-sheet B3.1" |
| B3.3 — one construction per mapping (`MappingView` merged into `ResolvedMapping`; the pre-pass steps rewrite the record under construction); the mapped fact closure-local on the ledger (engine R1; 28 global-only answers on the corpus flipped, none navigable in the engine either); a class-typed Join PM whose target has no set in the closure is dropped on record; includer re-binds a set whose dropped join it can serve (R6); `MappedClasses`, `TransitionalShapesTest` deleted | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED (2 → 0: the include-direction R6 case) | `MappedInClosureTest` replaces `MappedClassesTest`; the bare-view pin dies with the shape it pinned | docs/GATES.md "Clean-sheet B3.3" |
| B3.2 — chained routes: push-into-arm KEPT (engine 3-set / unionOfViews2 goldens; the navigator-side form multiplies rows with two mids and walls at three hops — built, measured, reverted); the arm's chain key spelled by the link-key rule over the route's FIRST hop and published as a fact; chain scan over pre-passed records; one key order per union; `RouteEntry.inArm` and the `col__prop_ord` navigator spelling deleted | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED (navigator-side attempt: 9 → 7 LOST; push-into-arm respelling: 6 → 1 → 0) | RoutedChainKeyTest pins the spelling | docs/GATES.md "Clean-sheet B3.2" |
| B3.1b — SUPERSEDES B3.1's spelling (USER review: set ids inside generated Pure): each set publishes its link keys as a mapping fact (`linkKeys`), union threads project them, the navigating class reads one name (identity = set, or the operation's class when its members route alike; shape index when routes differ on the source side); included operations re-bound by the includer when they gain its keys; `memberColumn`, `unionArm`, the registry and both natives deleted | 2026-09-13 | 108 / 444, 0 LOST, 0 GAINED (43 → 27 → 17 → 3 → 0 on the way) | INTERNAL_DESUGAR 18 → 16; register row removed; ResolveUnionTest asserts the link key | docs/GATES.md "Clean-sheet B3.1b" |
