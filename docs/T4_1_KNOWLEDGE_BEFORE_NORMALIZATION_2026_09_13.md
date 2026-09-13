# T4.1 — Knowledge before normalization: research, receipts, design (2026-09-13, full-read audit)

USER: "We need to do a lot of real research and homework to get this right with receipts —
this is a big refactoring that we need to get right as experts." Then: "Did you read all the
normalizer code fully? Or sampled it?" — the first draft was sampled. This version follows a
FULL READ of the normalizer package (25 files, 12,971 lines) plus every receipt re-verified on
main at ce7136e8f. Corrections to the sampled draft are marked **[full-read]**. Nothing here is
built. Parent: docs/ARCHITECTURE_REMEDIATION.md §T4.1 (pending since 2026-08-06); trigger:
docs/WALKS_AND_REACHBACKS_CENSUS_2026_09_13.md.

## 0. The one-paragraph version

The compiler runs `parse → name-resolve → E (normalize) → F (compile elements, type kernel,
validate) → G (type-check)`. E cannot ask a compiled model anything: the model is indexed
INSIDE E from parsed elements and indexed AGAIN at the F gate from E's output — two indexes,
and the knowledge layer (type kernel, compiled classes) exists only on the second. So E carries
its own shadow of the type system (fourteen hierarchy/lookup walkers), re-walks includes (nine
entry points), and writes five kinds of mutable state into the first index. Knowledge (classes,
properties, types, supertypes, stores) is not changed by E, so it can be compiled FIRST, once,
and E can ask. The reorder loses no semantics (§5); the cost is a family-by-family migration,
each corpus-measured (§8).

## 1. The pipeline today (receipts)

| step | where | what |
|---|---|---|
| parse | `Compiler.compileModel` | parsed elements |
| name-resolve | `NameResolver.resolveAlongside` (`Compiler.java:234`; boot layer `:271`) | FQNs; class derived properties resolved (`NameResolver.java:658`); association end targets and qualified-property return types are FQNs by E.0's own comment (`ModelNormalizer.java:158`) |
| **E** | `ModelNormalizer.normalize` (`ModelNormalizer.java:105-144`) | E.0 adoption (`:114`); **index #1** `ModelBuilder.from(parsed)` (`:123`); E.1 `MappingNormalizer.normalize` (`:126`); E.2/E.3/E.4 lifts (`:133-135`) |
| gate | `PureModelContext.from` (`PureModelContext.java:92-114`) | **index #2** `ModelBuilder.from(new ParsedModel(normalized.elements(), …))` (`:98`); poisons / mixed unions / key threads / nullable census copied across (`:102-108`); legacy surfaces retained as an analysis archive (`:112`) |
| **F** | `PureModelContext` ctor | `TypeClassifier` (`:66`), `FunctionCompiler`, `ModelIntegrity.check` (`:78`) |
| G | `Typer` and the rest | — |

The double index IS the "laundering" the audit named (`PureModelContext.java:95-97`).
Dependency direction today: normalizer → compiler (`ModelNormalizer.java:4` imports
`ModelBuilder`); nothing in `compiler` imports `normalizer`.

`ModelBuilder` already IS a knowledge index (`ModelBuilder.java:685-1194`), E asks it 80
times, and `TypeClassifier` (`TypeClassifier.java:25-27, :78`) is built from a `ModelBuilder`
alone. The knowledge layer's pieces exist; they are assembled only on index #2, after E.

## 2. Full normalizer inventory **[full-read]** — every file, what it does, what kind of work

| file (lines) | does | kind | verdict |
|---|---|---|---|
| `ModelNormalizer` (408) | E.0 association qualified-property adoption (`:146-209`, the package's ONE `new ClassDefinition`, `:197`; two `ModelException`s `:166, :178`, thrown OUTSIDE any tolerant guard); builds index #1 (`:123`); E.2 derived lift (`:231`), E.3 constraint + message lift (`:280-343`), E.4 service query lift (`:354-407`) | E.0 = knowledge + validation; lifts = E | E.0 MOVES to F1; lifts stay |
| `MappingNormalizer` (3507) | per-mapping: `detectM2MCycles` (`:935`, validation), declared-key capture (`:250-255`), `resolveExtends` (`:787`) + `flattenExtends` (`:855`, circular-extends validation `:860`), store-ref qualification (`:261`), implicit ops (`:266`), multi-hop injection (`:269`), per-class synthesis with poison isolation (`:283-376`), include-direction RE-SYNTHESIS block (`:388-440`), association synthesis (`:443-473`), the compiled `MappingDefinition` (`:478-485`). Synthesis: M2M (`:1427`), relational dispatch (`:1723`), JSON source (`:1963`), view-backed (`:2019`), table-backed parts (`:2239`), PM→field (`:2575`), embedded/inline/otherwise (`:2670-2774`), filters (`:2780-2865`), enum decode (`:2965`), `buildNewInstanceToOne` (`:3341`). Shadow walkers: `classDef` (`:3429`, native-first — the SAME rule as `TypeClassifier.java:78`), `findPropertyDefDeep` (`:3441`), `findPropertyType` (`:3458`), `findPropertyTypeDeep` ×2 (`:3472, :3485` — includes ASSOCIATION ends via `model.findAssociationProperty`), `isBitemporalClass`/`isTemporalClass` (`:1619, :1646` — superclass walks for stereotypes), `findPhysicalColumn` (`:2492, :2508` — db-include walk), `validatePmNames` (`:2543` — PM names vs class properties: a KNOWLEDGE check inside E) | mapping translation + shadow knowledge + validation | translation stays; walkers → F1 API; validation → F1/F2 |
| `UnionSynthesis` (3041) | union/inheritance extents, route classification, nav lifts, inbound keys, subtype dispatch columns, single-scan merge. Include-recursive: `unionForClass` (`:82`), `inheritanceForClass` (`:715`), `collectRootClassMappings` (`:838`), `memberOrdinalOf` (`:158`, extends lineage). Hierarchy walkers: `isSubclassOf` (`:863`), `collectInheritanceMembers` (`:737` — `model.directSubclasses` + `Pure.directNativeSubclasses`, the ONE place a subclass index is consulted), `selfAndAncestorsBelow` (`:979`). Writes the index: `mappingPoisons` (`:350, :2548`), `mixedUnions` (`:399`), `unionKeyThreads` (`:2937`) | E synthesis + shadow knowledge | synthesis stays; walkers → F1; index writes → stamped facts |
| `JoinChainEmission` (1202) | hop emission, slot minting, routed navigation (incl. the 2026-09-13 lean coalesce arm `:607-630`), inner-filtered source, view/table lookups. Shadow: `classTypedTargetIfMapped` (`:801`), `hasMappedSubclass` (`:820` — scans EVERY class × `isSubclassOf`), `tableHasColumn` (`:740`, db-include walk), `findPhysicalTable` (`:1141`, db-include walk), `columnPureKind` (`:1184` → ViewRelation) | E emission + shadow store/knowledge | emission stays; lookups → F1 store API |
| `RelOpTranslator` (731) | legacy relational ops → Pure; dynafunction arms (`DynaFnArms`) | E translation (pure function of its inputs; no model walks) | stays |
| `AssociationSynthesis` (664) | multi-hop injection over the include closure (`:91`), pair entries (include-recursive `:297`), predicate synthesis, `anchorTableOf` (`:614`, closure walk + `findPropertyTypeDeep`) | E synthesis | stays; closure facts → stamped |
| `ViewRelation` (577) | views as relation expressions; `inferViewMainTable` (`:495` — a STORE fact recomputed per use; the seeder has its own `viewBaseTable`), `columnPureKind` (`:463`, view-on-view recursion), `frameable` | E emission + store facts | expansion stays; view root/kind facts → stamped on the compiled view (F1 store) |
| `StoreSubstitutionRewrite` (392) | substitution rewrite (exhaustive), `resolveAllStores` (include-order, name-level, loud cycle — landed 2026-09-13), `qualifyStoreRefs` (`:349`) | E rewrite; qualification = name resolution | rewrite stays; `qualifyStoreRefs` → name-resolve |
| `GroupBySynthesis` (315) | ~groupBy decomposition; `isGroupReducer` reads the DynaFn + native registries | E | stays |
| `XStorePureEnds` (304) | XStore ends over the closure (`:71`), property-space predicate | E | stays |
| `ImplicitInheritance` (248) | implicit same-extent inheritance (`apply`, `:38` — `nearestMappedAncestor` `:209` is a BFS over superclasses = shadow), implicit ops for routed targets (`:122`) which **`model.registerMappedClass(cls)`** (`:202`) — E WRITES a mapped-class fact into index #1 | E mapping rewrite + shadow + index write | rewrite stays; BFS → F1 `isSubtype`; the mapped-class fact becomes a product of the compiled mapping |
| `RelationReads` (245) | `$this.p` → column reads; `findDerivedInline` (`:186`, superclass walk), `findPropertyDeclared` (`:224`, superclass walk for multiplicity) | E translation + shadow | translation stays; walks → F1 `findProperty` |
| `DeclaredCoercions` (222) | declared-kind coercions; `declaredPlatformKind` (`:72`) over `findPropertyTypeDeep`; the RequiredNullableCensus hooks | E emission over knowledge lookups | stays; lookups → F1 |
| `ModelJoinNesting` (202) | nested ModelJoin hop composition | E | stays |
| `M2mRouteGuards` (160) | M2M route validation (`requireBenignRoute` `:64`, closure walk `:85`); `localField` collision (`:143`) | validation inside E | → F2 `checkMapping` (needs compiled bindings) |
| `RequiredNullableCensus` (136) | [1]-property × nullable-column census; writes `model.requiredNullableRows()` (`:133`) | a PURE KNOWLEDGE JOIN (class property multiplicity × store column nullability) computed during E | → F1 (both inputs are knowledge); stamped, not written |
| `Pipeline` (104) | per-synthesis state record | E | stays |
| `SetDispatch` (100) | routed target sets over the closure (`:37`) | E mapping fact | stays; rides the closure fact |
| `PhysicalTables` (70) | table definition, db-include walk (`:25`) — "the table half of findPhysicalColumn" | store lookup | → ONE store lookup in F1 |
| `SetKeyFacts` (68) | declared key text capture | E | stays |
| `AggregateViewLift` (66) | aggregation-aware views as sets | E | stays |
| `DynaFnArms` (56) | translator arm declarations (test-held) | E | stays |
| `RelationalKinds` (52) | SQL type → pure kind; its own doc: "T3.1 will merge this with StoreCompiler.columnType into the one reader" (`:14`) | a SECOND declared duplicate of a knowledge function | → the one reader (F1 store) |
| `MissProbe` (35) | F7.8 funnel: 23 `orElseThrow` sites ("this default NEVER fired on the corpus census") and the legitimate null sites — its doc says 10, **today 12** (`grep MissProbe.knownMiss`: DeclaredCoercions 1, RequiredNullableCensus 1, JoinChainEmission 2, MappingNormalizer 4, UnionSynthesis 4); its doc also records that NINE of them key on BARE superclass simple names "never import-resolved — a REAL name-resolution gap (FOUNDATIONS_PLAN §9)" | nullability policy | receipt for §4; the bare-name gap is a name-resolve item |
| `package-info` (66) | phase placement doc; claims idempotence (`:60-64`) that `ModelNormalizer:95-96` contradicts ("re-normalization is impossible at the type level") | doc | fix the doc in step 6 |

## 3. What E writes INTO the model index (the mutation channels) **[full-read]**

The sampled draft counted only poisons. Measured (`grep model\.(…)` over `normalizer/`):

| channel | writers | how it reaches F today |
|---|---|---|
| `mappingPoisons` | `MappingNormalizer.java:303, :331, :354, :424, :459`; `UnionSynthesis.java:350, :2548` | copied at the gate (`PureModelContext.java:102`) |
| `mixedUnions` | `UnionSynthesis.java:399` | copied (`:103`) |
| `unionKeyThreads` | `UnionSynthesis.java:2937` | copied (`:104`) |
| `requiredNullableRows()` | `RequiredNullableCensus.java:133` | copied (`:105-108`) |
| `registerMappedClass` | `ImplicitInheritance.java:202` | NOT copied — re-derived by index #2's ingest from the compiled bindings (`ModelBuilder.java:303, :535`: "feed the mapped-class set so isMappedClass() holds"); readers inside E: `JoinChainEmission.java:230, :817, :821`, `MappingNormalizer.java:1520`, `UnionSynthesis.java:2496` |

With ONE index (step 2) every one of these becomes a fact stamped on an artifact (the
`resolvedStores` shape) or a validation product — never a write into a shared mutable index.

## 4. The shadow type system, re-measured **[full-read: 14 walkers, not 6]**

| walker | where | what it re-derives |
|---|---|---|
| `findPropertyTypeDeep` ×2 | `MappingNormalizer.java:3472, :3485` | property type over the superclass chain + association ends (45 call sites) |
| `findPropertyDefDeep` | `:3441` | property definition over the chain (3) |
| `findPropertyType` | `:3458` | own property (private helper) |
| `classDef` | `:3429` | native-first class lookup — duplicates `TypeClassifier.java:78` |
| `isBitemporalClass` / `isTemporalClass` | `:1619, :1646` | stereotype over the chain |
| `isSubclassOf` | `UnionSynthesis.java:863` | subtype (10) |
| `collectInheritanceMembers` | `:737` | subtree via `model.directSubclasses` + native subclasses |
| `selfAndAncestorsBelow` | `:979` | ancestor set |
| `nearestMappedAncestor` | `ImplicitInheritance.java:209` | BFS over superclasses |
| `findDerivedInline` / `findPropertyDeclared` | `RelationReads.java:186, :224` | derived property / multiplicity over the chain |
| `hasMappedSubclass` | `JoinChainEmission.java:820` | every class × `isSubclassOf` |
| `columnPureKind` / `pureKindOf` / `declaredPlatformKind` | `ViewRelation.java:463`, `RelationalKinds.java:22`, `DeclaredCoercions.java:72` | kinds (9 + 3 + 3) |
| store lookups (db-include walks) | `MappingNormalizer.findPhysicalColumn :2492`, `PhysicalTables.find :25`, `JoinChainEmission.findPhysicalTable :1141`, `.tableHasColumn :740`, `ViewRelation.inferViewMainTable :495` | FIVE separate include-walking store lookups |

**Correction to the parent audit, with receipt:** it says these "default to `"String"` when a
lookup fails". Measured: ZERO such sites; the only `"String"` literal is a kind list
(`MappingNormalizer.java:3338`). Misses are nulls, and the F7.8 census (`MissProbe.java:7-24`)
already converted 23 sites to `orElseThrow` and funnels the 12 legitimate ones. The defect is
duplication, not silent defaults — smaller than the audit implies.

**The compiled API these shadow** (verified): `ModelContext.findProperty` (`:194`) — its
implementation recurses supertypes and consults the association index
(`PureModelContext.java:249-259`), so it COVERS association ends as `findPropertyTypeDeep`
does; `findType` (`:184`); `isSubtype`/`isDeclaredSubtype` (`:300, :308`, memoized
`PureModelContext.java:120-135`); `findTable` (`:206`). **[full-read caveat for step 3]:** the
shadow returns the RAW parsed `TypeExpression` and callers pattern-match
`instanceof TypeExpression.NameRef nr` then `classDef(model, nr.name())`; the compiled API
returns `Type`. The migration is not a rename — each site's downstream shape changes.

## 5. Does the reorder lose anything? (USER question) — NO, by construction

- E is append-only for knowledge (`ModelNormalizer.java:117-121`, and the full read confirms:
  the ONLY `new ClassDefinition` in the package is E.0). What E creates is mapping-side:
  `new FunctionDefinition` at MappingNormalizer 5, ModelNormalizer 3, AssociationSynthesis 1,
  XStorePureEnds 1 (+ `DerivedProps.lift` in `compiler`); compiled `MappingDefinition`s
  (`:478, :562`); synthesized operation mappings (`ImplicitInheritance.java:201`).
- `ModelIntegrity` already splits on this line (`ModelIntegrity.java:38-60`): only
  `checkMapping` (`:176`) reads compiled mappings; `checkClass` reads
  `cd.derivedProperties()` (`:116`), so E.0 must precede it — it will.
- Blocker (a): index #1's runtime cross-bake mutates mappings after parse
  (`ModelBuilder.java:292-296`), hence the re-fetch (`MappingNormalizer.java:167`). It becomes an
  E pre-pass over the mapping.
- Blocker (b): index #2 re-indexes from scratch (`PureModelContext.java:98`). With one index,
  the gate becomes "add E's products to the existing index" (`ModelBuilder.add` for compiled
  mappings + lifted functions). Package direction stays normalizer → compiler.
- **[full-read] Blocker (c):** the five index-write channels (§3) — each becomes a stamped
  fact or a validation product; `registerMappedClass` in particular must become a product of
  the compiled mapping (an implicit Inheritance op IS a class binding), read by E through the
  context.

## 6. The poison map (what "validate before E" must preserve)

Writers: `MappingNormalizer.java:303` (multi-set class without a union root — "not built yet"),
`:331` (per-set fault isolation), `:354` (DELIBERATE TRADE, audit 6: a user-model error the
engine rejects at compile time, deferred to query time), `:424` (per-class synthesis failure),
`:459` (association synthesis — TOLERANT builds only, `:455-458`); `UnionSynthesis.java:350`
(dropped route), `:2548` (skipped lift). Readers: `ClassSources.java:749, :1513`,
`AssociationJoins.java:1349-1350`. Two kinds ride one map; `tolerant` already distinguishes
strict from module. Validation before E keeps exactly that line. E.0's two errors are NOT
deferred today (§2) — moving them to validation makes them tolerant-aware for the first time.
**[full-read]** Other validations currently inside E that move with them: `detectM2MCycles`
(`:935`), the circular-extends check (`:860`), `validatePmNames` (`:2543` — a knowledge check:
PM names against class properties), `M2mRouteGuards` (`:64, :143`).

## 7. Target pipeline

```
parse → name-resolve (+ store-ref qualification; + the bare-superclass-name gap, MissProbe doc)
      → F1 KNOWLEDGE: E.0 adoption → ONE index → TypeClassifier → the nullable census
                      → ModelIntegrity minus checkMapping (+ validatePmNames, M2M cycles)
      → E  NORMALIZE mappings, asking ModelContext; products = compiled mappings + lifted functions
                      + stamped facts (poisons as walls, mixed unions, key threads, mapped classes)
      → F2 WORK: products added to the index → FunctionCompiler → checkMapping + M2M route guards
      → G
```

Invariants as tests: (1) F1's answers identical before and after E; (2) `compiler` never
imports `normalizer`; (3) the 14 walkers' call-site count is a shrink-only pin; (4) the
reach-back census (17) shrinks to zero; (5) **[full-read]** the normalizer performs no
`model.<mutable>` write (an ArchitectureTest rule once §3's channels are stamped).

## 8. Steps — each corpus-measured, each landable alone

**Step 1 — E.0 moves out of the normalizer.** New `compiler/KnowledgeLayer.adoptAssociationQualifiedProperties(ParsedModel)`
(the body of `ModelNormalizer.java:146-209` verbatim, `rawName` included; the two errors
become Phase.MODEL and tolerant-aware), called by BOTH normalize callers between name-resolve
and normalize: `Compiler.java:271` (boot) and `:331` (user). `ModelNormalizer.normalize` drops
`:114` and asserts loudly that no association still carries qualified properties. Witness: a
two-class association with a qualified property (each end), the self-association, the
no-unique-end error. Expected: 0 rows move. ~60 lines moved, one session.

**Step 2 — one index, built before E; the five channels stamped.** `Compiler` builds the
`ModelBuilder` after step 1 and passes it into `ModelNormalizer.normalize(parsed, model,
walls)` (delete `:123`); `PureModelContext.from(normalized, model)` ADDS E's products instead
of re-indexing (`:98`). The cross-bake becomes an E pre-pass (§5a). Each of §3's writes becomes
a field on the compiled mapping or a `NormalizedModel` product (`registerMappedClass` →
derived from bindings, the way index #2 already does it). Invariants (1) and (5) land here.
Expected: 0 rows move. One to two sessions.

**Step 3 — migrate the walkers**, one family per batch, each shadow delegating to the context
until its last caller moves: subtype family (`isSubclassOf`, `selfAndAncestorsBelow`,
`nearestMappedAncestor`, `hasMappedSubclass`, `collectInheritanceMembers`' subtree) →
`isSubtype` + a `directSubtypes` view; property family (`findPropertyTypeDeep`/`DefDeep`/
`findDerivedInline`/`findPropertyDeclared`, 50+ sites) → `findProperty` (each site's
`NameRef` pattern-match becomes a `Type` case); stereotype family (`isTemporalClass`,
`isBitemporalClass`) → the compiled class's strategy; store family (the five include-walking
lookups + `RelationalKinds` + `inferViewMainTable`) → ONE store API with the view root and
column kind stamped on compiled stores. Every guarded null becomes loud; the corpus names the
answers that were silently wrong (each a FIX/ACCEPT decision, never a re-silenced guard). Pin
(3) shrinks each batch. Three to four sessions.

**Step 4 — include-order facts on the compiled mapping** (WALKS census family 1): the
visible-set map, include closure, root-set-per-class, union/inheritance-op-per-class, set
ancestry, pair-association entries — the `resolvedStores` shape (`resolveAllStores` is the
template). **[full-read]** Retires all NINE include-recursive entry points:
`collectIncludedSetIds` (7 call sites), `collectMappingClosure` (10), `findSetById` (13),
`memberOrdinalOf` (6), `unionForClass`, `inheritanceForClass`, `collectRootClassMappings`,
`collectPairAssociationEntries`, `enumerationMappingsWithIncludes` (`:483, :2970`) — and with
them every pinned `findLegacyMapping` reach. One to two sessions.

**Step 5 — validation before E** (§6's line held; `checkMapping` + route guards stay in F2).

**Step 6 — delete**: the walkers, the E→F re-index, the strict-build poison sites, the
package-info idempotence claim.

## 9. Risks named

- Step 3's loudness: the F7.8 census shows 23 sites are ALREADY `orElseThrow`; the remaining
  12 `knownMiss` sites and every `findPropertyTypeDeep == null` guard are where wrong-all-along
  answers will surface. Land only at 0 LOST or with each loss adjudicated.
- **[full-read]** Nine `knownMiss` sites key on bare superclass simple names the normalizer
  never import-resolved (`MissProbe.java:16-23`). A compiled `isSubtype` over FQNs will answer
  FALSE where the shadow answered false-by-miss — same rows, but the gap must be closed in
  name-resolve (step 0 of step 3) or it becomes loud at the wrong layer.
- Module (tolerant) builds depend on deferral (§6's sites are the checklist).
- The boot layer runs the same pipeline once per process (`Compiler.bootLayer :251-271`);
  steps 1–2 apply to both callers; the layer union (`Compiler.java:339-344`) unions the stamped
  facts as it unions the others.
- `registerMappedClass` semantics under one index: today E's write is visible to later
  mappings normalized in the same pass (`ImplicitInheritance.java:202` then
  `JoinChainEmission.java:817` for a later class). Step 2 must preserve that ordering effect or
  compute mapped-ness up front from every mapping's class mappings (the honest form).

## 10. Fresh-session start (everything needed, nothing assumed)

- Read: this doc; `docs/ARCHITECTURE_REMEDIATION.md` §T4.1; the walks census; the
  store-substitution GATES record (2026-09-13) as the worked example of "compute once, in
  order, stamp, project". Then read `ModelNormalizer.java` whole (408 lines) and
  `MappingNormalizer.java:127-486` (the entry and per-mapping driver) before touching anything.
- Entry points: `Compiler.java:234` (`buildModel`), `:251-271` (boot layer), `:329-344`
  (`normalizeWithSystem`), `:402-404` (module compile with walls); `ModelNormalizer.java:105-144`;
  `PureModelContext.java:92-114`.
- Guards that will speak: `CodeShapeGuardrailTest` (`MappingNormalizer` sits at 3507 of a 3510
  allowance — step 1 removes nothing from it, so any addition there must be paid for),
  `LegacyReachbackCensusTest` (17, shrink-only), `ArchitectureTest` (typed nodes minted only by
  compiler layers; add the package-direction and no-index-write rules), `JavaEvalLedgerTest`,
  `OwnCorpusParityTest` (re-pin with a reason when a witness model joins), `MinimalCorpusTest`
  strength pins.
- Measure: `bash /Users/neema/.claude/jobs/664ac178/tmp/corpus-both.sh` (both lanes,
  LOST/GAINED), then `GATES_PARALLEL=1 tools/allgates.sh` once in the background; rosters at
  DuckDB 108 / H2 444 on main ce7136e8f. Corpus engine root = `$HOME/legend/legend-engine` at
  the pin in `tools/oracle-pins.env`.
- Land: GATES record with per-gate times; ledger row; commit named files only; push; watch CI
  with the full sha.
