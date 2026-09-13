# T4.1 — Knowledge before normalization: research, receipts, design (2026-09-13)

USER: "We need to do a lot of real research and homework to get this right with receipts —
this is a big refactoring that we need to get right as experts." This document is the
homework. Every claim carries a file:line receipt measured on main at 1b07b6336. Nothing
here is built. Parent: docs/ARCHITECTURE_REMEDIATION.md §T4.1 (pending since 2026-08-06);
trigger: docs/WALKS_AND_REACHBACKS_CENSUS_2026_09_13.md.

## 0. The one-paragraph version

The compiler runs `parse → name-resolve → E (normalize mappings) → F (compile elements,
build the type kernel, validate) → G (type-check)`. Because E runs before F, E cannot ask a
compiled model anything, so it carries its own shadow of the type system and re-walks the
model for facts F would hold. Knowledge (classes, properties, types, supertypes, stores) is
not changed by E, so it can be compiled FIRST and E can ask. The reorder loses no semantics
(§4); the cost is a migration of ~70 call sites, family by family, each corpus-measured (§7).

## 1. The pipeline today (receipts)

| step | where | what |
|---|---|---|
| parse | `Compiler.compileModel` | parsed elements |
| name-resolve | `NameResolver.resolveAlongside` (`Compiler.java:234`) | FQNs everywhere |
| index | `ModelBuilder.from` (`ModelBuilder.java:257`) | by-FQN lookups; phase 3b `ingestRuntime` CROSS-BAKES synthetic class mappings into bound mappings (`ModelBuilder.java:240-247, 292-296`) |
| **E** | `ModelNormalizer.normalize` (`ModelNormalizer.java:108-135`) | E.0 association qualified-property adoption; E.1 mapping normalization (`MappingNormalizer.normalize`); E.2 derived-property lift; E.3 constraint lift; E.4 service-query lift |
| gate | `PureModelContext.from` (`PureModelContext.java:92-114`) | re-indexes the NORMALIZED elements as a fresh `ParsedModel` (the "laundering" the audit named), copies poisons/mixed unions/key threads across |
| **F** | `PureModelContext` ctor (`:66, :78`) | `TypeClassifier` (the kind manifest), `FunctionCompiler`, `ModelIntegrity.check` |
| G | `Typer` and the rest | — |

`ModelBuilder` already IS a knowledge index (`ModelBuilder.java:685-1194`: findClass,
findAssociation, findAssociationProperty, findAssociationEnd, directSubclasses, findEnum,
findDatabase, findFilter, findJoin, findView, classes(), associations(), importsOf …) and
E already asks it 78 times (`grep model.find*` over normalizer/: findView 20, findJoin 9,
findAssociation 9, findDatabase 8, findLegacyMapping 7, findFilter 6, isMappedClass 5, …).
`TypeClassifier` (`TypeClassifier.java:25-78`) is built from a `ModelBuilder` alone and
"builds no structure". So the KNOWLEDGE layer exists; it is constructed AFTER E and E cannot
see it.

## 2. What E does that is not mapping translation (the misplaced work)

| pass | receipt | kind | belongs |
|---|---|---|---|
| E.0 association qualified properties adopt into the owning-end class; two ModelExceptions on a return type identifying no unique end | `ModelNormalizer.java:146-204` (the ONE `new ClassDefinition` in the normalizer, :197) | knowledge + validation | the knowledge phase (USER: "why is normalizer building classes at all") |
| `extends [set]` flattening | `MappingNormalizer.resolveExtends` (:244-258, `flattenExtends` :853) | mapping | E |
| import-scope store-ref qualification | `StoreSubstitutionRewrite.qualifyStoreRefs` (:259-262) | name resolution | name-resolve |
| store substitutions resolved | `StoreSubstitutionRewrite.resolveAllStores` (landed 2026-09-13) | mapping fact, include order | E (correct today) |
| enumeration mappings flattened with includes | `MappingNormalizer.java:475-479` (`enumerationMappingsWithIncludes(model::findLegacyMapping)`) | mapping fact | E, but via a parse-artifact reach |
| routed target sets | `SetDispatch.routedTargetSets` (:248) | mapping fact | E |

## 3. The shadow type system (the audit's central claim), re-measured

| function | defined | call sites | returns |
|---|---|---|---|
| `findPropertyTypeDeep` | `MappingNormalizer.java:3472, :3485` | 45 | `TypeExpression` or null |
| `findPropertyDefDeep` | `MappingNormalizer.java:3441` | 3 | `PropertyDefinition` or null |
| `isSubclassOf` | `UnionSynthesis.java:863, :870` | 10 | boolean, visited-set walk |
| `columnPureKind` | `ViewRelation.java:463`, `JoinChainEmission.java:1184` (+1) | 9 | String kind or null |
| `pureKindOf` | `RelationalKinds.java:22` | 3 | String kind |
| `declaredPlatformKind` | `DeclaredCoercions.java:72` | 3 | String kind or null |

**Correction to the audit, with receipt:** the audit says these "default to `"String"` when a
lookup fails". Measured today: ZERO `orElse("String")` sites in the normalizer; the only
`"String"` literal is a kind list (`MappingNormalizer.java:3338`). The functions return
NULL on a miss and callers guard (the audit's "~40 null-tolerance guards"). The defect is
nullability tolerated, not a silent default — a smaller risk than the audit implies, and one
the compiled API turns into a loud error at each migrated site.

The real API these shadow: `ModelContext.findProperty(classFqn, name)` (`ModelContext.java:194`,
derived properties included), `findType` (:184), `isSubtype`/`isDeclaredSubtype` (:300, :308 —
memoized, `PureModelContext.java:120-135`), `findTable` (:206). Same facts, typed, no walks.

## 4. Does the reorder lose anything? (USER question) — NO, by construction

- E is append-only for knowledge. The ONE knowledge rewrite E performs is E.0 (§2), which is
  model-side, reads only parsed classes and associations, runs before any mapping is touched,
  and MOVES with the knowledge phase. Everything else E creates is mapping-side: lifted
  functions (`new FunctionDefinition`: MappingNormalizer 14 sites, ModelNormalizer 7,
  AssociationSynthesis 2, XStorePureEnds 2), compiled mappings, synthesized operation
  mappings. Classes, enums, associations, stores are otherwise untouched → F1 computed before
  E equals F1 computed after E.
- `ModelIntegrity` already splits on this line (`ModelIntegrity.java:38-60`): duplicates,
  classes, inheritance acyclicity, functions, enums need no E output; only `checkMapping`
  (:176) reads compiled mappings.
- The two blockers the audit named are still there and both bounded:
  (a) the runtime cross-bake mutates mappings after indexing (`ModelBuilder.java:292-296`),
  which is why E "re-fetches the latest legacy surface" (`MappingNormalizer.java:158-163`) —
  it becomes an E pre-pass over the mapping (a mapping rewrite, where mapping rewrites live);
  (b) the E→F gate re-indexes normalized elements as a fresh `ParsedModel`
  (`PureModelContext.java:98`) to dodge the compiler↔normalizer package cycle — with
  knowledge compiled first the gate becomes "add E's mapping products to the existing index",
  and the cycle is broken by E depending on `ModelContext` (an interface in `compiler.element`)
  rather than the normalizer package being depended upon.

## 5. The poison map (what "validate before E" must preserve)

Writers, all in `MappingNormalizer`: :303 (a multi-set class with no union root — `.all()`
undefined, a genuine "not built yet"), :331 (per-set fault isolation on
NotImplementedException|ModelException), :354 (DELIBERATE TRADE, audit 6: a user-model error
the engine rejects at compile time, deferred to query time so the rest of the model stays
queryable), :424 (per-class synthesis failure, same), :459 (association synthesis, TOLERANT
builds only — a STRICT build throws). Readers: `ClassSources.java:749, :1513`,
`AssociationJoins.java:1349-1350` (the 0-binder error's reason). Two kinds ride one map:
"not built yet" walls (keep) and user-model errors (audit 6 chose deferral for module builds;
`tolerant` already distinguishes strict from module at :455-459). Validation before E must
keep exactly that line: strict builds reject what the engine rejects; module builds defer.

## 6. Target pipeline

```
parse → name-resolve (incl. store-ref qualification, §2 row 3)
      → F1 KNOWLEDGE: index + E.0 adoption + TypeClassifier + ModelIntegrity(minus checkMapping)
      → E  NORMALIZE mappings, asking ModelContext (F1's view), producing lifted functions + compiled mappings
      → F2 WORK: FunctionCompiler over E's products + checkMapping
      → G
```

Invariants stated as tests: (1) F1's answers are identical before and after E (a guard that
compiles F1 twice and diffs); (2) the normalizer package imports `compiler.element`'s
interfaces only, never `ModelBuilder` (an ArchitectureTest rule); (3) the shadow functions'
call-site count is a shrink-only pin; (4) the reach-back census shrinks to zero.

## 7. Steps, each corpus-measured, each landable alone

1. **E.0 moves to F1** (~40 lines moved; the two ModelExceptions become validation). Cheapest
   receipt that the knowledge phase has a home. Zero rows expected to move.
2. **F1 constructed before E; E receives a `ModelContext`** alongside `ModelBuilder` (both,
   transitional). The E→F gate stops re-indexing from scratch. The cross-bake becomes an E
   pre-pass. Invariant (1) test lands here.
3. **Migrate the shadow families**, one per batch, shadow function delegating to the
   context until its last caller moves: `isSubclassOf` (10) → `isSubtype`;
   `findPropertyTypeDeep`/`findPropertyDefDeep` (48) → `findProperty`; the three kind
   functions (15) → `findType`/`findTable`. Each site that guarded a null becomes loud;
   the corpus tells us which answers were silently wrong.
4. **Include-order facts on the compiled mapping** (WALKS census family 1): visible-set
   map, include closure, set ancestry — the `resolvedStores` shape. Retires
   `collectIncludedSetIds` (7), `findSetById` (13), `collectMappingClosure` (10),
   `memberOrdinalOf` (6) and all nine pinned reach-backs; the enumeration-mapping flatten
   (§2 row 5) rides it.
5. **Validation before E** with the poison line of §5 held; `checkMapping` stays in F2.
6. **Delete**: the shadow functions, the E→F laundering, `retainLegacySurface` for F+
   consumers (the analysis archive stays for lineage).

Size: steps 1–2 one session; 3 two to three sessions; 4 one to two; 5–6 one. Never
big-bang; the pins make every step shrink-only.

## 8. Risks named

- Latent wrong answers surfacing as loud errors during step 3 — the ratchet may move the
  wrong way inside a batch; each batch lands only at 0 LOST or with each loss explained as a
  wrong-all-along answer (an ACCEPT/FIX decision, never a silenced guard).
- Module (tolerant) builds depend on deferral; step 5 must not turn "not built yet" into
  compile errors (the census of the 26 poison sites in §5 is the checklist).
- Boot layer: the system metamodel is normalized once per process
  (`Compiler.bootLayer`, :251-271) through the same pipeline — F1 for the boot layer is
  computed once too; the union of the two layers (`Compiler.java:339-344`) must union
  F1 facts as it unions the others.
