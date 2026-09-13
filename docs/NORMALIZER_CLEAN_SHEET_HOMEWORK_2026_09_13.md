# Normalizer clean sheet — homework, receipts, plan (2026-09-13)

Written after T4.1 closed (docs/T4_1_KNOWLEDGE_BEFORE_NORMALIZATION_2026_09_13.md §14). USER: "the whole
idea is that the compile step has all the facts so the normalizer is one simple AST-to-AST transform
for mappings only." This doc checks that idea against the engine's own source at the pin and the
corpus, names where our code diverges, and sets a batch plan. Nothing here is built.

## NOT YET VERIFIED — questions, not facts

1. **Rows under the engine's last-root rule (B2).** 43 corpus mappings have a class mapped in more
   than one included mapping. Our resolver walls that as ambiguous at query time; the engine takes
   the last root. Which corpus tests reach the wall today, and what they do under the engine rule,
   is unmeasured until B2 runs.
2. **Compile-time cost of per-viewpoint translation (B3).** At most 779 extra set translations
   (+38 % over 2,058); the corpus normalizes in ~75 s today. Whether the ceiling is reached and
   what it costs is unmeasured.
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
mapping semantics); per mapping viewpoint; no policy, no fact-finding inside. 7. Emit functions +
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
mapping: a navigation's target set is resolved in the QUERIED mapping's closure. That is exactly
"translate per mapping viewpoint" (§1 step 6), and it is why our include-direction re-synthesis
block exists as a special case.

## 3. Receipts — the corpus (probe run 2026-09-13, DuckDB lane, one graph)

| count | value |
|---|---|
| mappings in the graph (user + boot) | 679 |
| own class-mapping sets | 2,058 |
| lifted functions today | 1,703 |
| mappings with includes (closure > 1) | 134 |
| visible INCLUDED sets summed over mappings (viewpoint pairs beyond own) | 779 |
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

**B2 — the engine's rules (semantics change, receipted).** R1 for roots and operation sets (last
root; delete `findBinding`'s ambiguity wall), R2 + R5 as validation (duplicate ids across the
closure are a MODEL error: strict throws, module walls the mapping), duplicate includes an error.
Expected: rows may GAIN (tests that hit the wall today); any LOST row adjudicated with R1/R5 as
the receipt.

**B3 — per-viewpoint translation (R6).** Every mapping translates every set visible to it, named
(mapping, class, set); the re-synthesis block, `routedTargetGainsOperation`, and `MappedClasses`'
global set die; mapped-ness is per closure. Ceiling +779 translations; measure the corpus time.
Expected: 0 LOST; the 2 re-synthesis hits reproduce by the uniform rule.

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

Order: B1 → B2 → B3 → B4 → B5 → B6. B2 and B3 are the only ones that can move rows by design; both
carry engine receipts above.

## 7. Stop rules and done criteria

The T4.1 doc's §12 stop rules apply unchanged. Done per batch: the census pins moved down with the
batch's name; 0 LOST or every LOST row adjudicated against a receipt in §2; chain green; GATES
record; ledger row here; CI green on the full sha.

## 8. Ledger

| batch | landed | rows | pins | record |
|---|---|---|---|---|
| — | — | — | — | — |
