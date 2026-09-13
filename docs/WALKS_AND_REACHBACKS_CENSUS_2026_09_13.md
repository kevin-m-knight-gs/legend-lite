# Walks, recursion and reach-backs — census (2026-09-13)

USER question: "find all the other bad stuff — walks, recursion, reach back, any other bad
version of these patterns that we need to kill/burn down/fix". Measured on main at the
store-substitution landing. The pattern behind all of it: a fact derived where it is
CONSUMED instead of once where the model is COMPILED. The cure is
docs/ARCHITECTURE_REMEDIATION.md **T4.1** (still pending): compile the knowledge layer
(classes, properties, types, supertypes, stores) BEFORE normalization, so the normalizer
asks instead of guessing; facts computed once in dependency order and stamped on artifacts.
The store-substitution leg is that shape at small scale (compute once, include order,
stamp, project) and its proof.

## T4.1 symptoms, measured today

| shadow type-system function (normalizer, returns String, defaults on miss) | call sites |
|---|---|
| `findPropertyTypeDeep` | 45 |
| `isSubclassOf` | 10 |
| `columnPureKind` | 9 |
| `findPropertyDefDeep`, `pureKindOf`, `declaredPlatformKind` | 3 each |
| poison-map sites (deferred failure through a public mutable map) | 26 |
| parse-artifact reaches (`findLegacyMapping`) | 17 (9 pinned include walks) |
| normalizer package | 12,971 lines |

Nothing in this table has shrunk since the audit was written.

## The families

1. **Include walks repeated per call (normalizer).** `collectIncludedSetIds` rebuilds a
   mapping's visible-set map at 7 sites; `findSetById` rebuilds it underneath at 13;
   `collectMappingClosure` at 10; `memberOrdinalOf` walks extends lineage at 6. Fix: the
   visible-set map, the include closure and set ancestry become fields stamped on the
   compiled mapping in include order (the `resolvedStores` shape). Retires the 9 pinned
   reach-backs.
2. **Seed-time re-derivation (MetamodelSeeds).** `visitIncludes`/`includesClosure`,
   `resolveIncludePath` (compiled include records keep the RAW path), `viewBaseTable`/
   `baseTableOf`. Fix: qualify include paths once in the compiler (as store refs are), stamp
   view bases and the closure; the seeder becomes pure projection. Falls out of 1.
3. **Per-lookup database-include walks.** `ModelBuilder.findView`/table lookups walk
   `db.includes()` per call; `JoinChainEmission.findPhysicalTable` is a second recursive
   lookup beside them. Fix: a database-include closure computed once when stores compile.
4. **Name-pattern derivations (3 surviving sites).** `Pipelines:1311` (`^.*_\d+$`),
   `ClassSources:528` (`endsWith("_" + ord)`), `TemporalFrame:2486` (`endsWith("_")`).
   Earlier audits ruled these out and threaded provenance for the rest. Fix: provenance from
   the normalizer's registrations.
5. **The shadow type system** (the table above). Fix: T4.1 proper — the root; 1–3 are its
   symptoms.
6. **Resolver-time recomputation.** `ClassSources` finds a runtime's reachable mappings by
   BFS per resolution. Fix: a compiled fact of the runtime.

Not on the list, deliberately: the parser section grammars' visited sets (token-level);
lineage scanning and model integrity (analyses by nature); the verdict layer's typed-tree
navigation (reads a compile artifact — though `orderView` and `sortKeys` duplicate each
other and could be one walker).

## T4.1 — cons of compiling knowledge first (USER question)

No semantic loss by design: normalization is append-only for knowledge; the ONE class
rewrite it performs (derived-property adoption, ModelNormalizer:197) is model-side and moves
in front of the knowledge layer. Two things move with it: the runtime ingestion's mapping
cross-bake (into normalization) and PureModelContext's normalized→parsed laundering (a
package-cycle fix). The costs are transition: ~70 shadow call sites migrate one family at a
time, each corpus-measured; where the shadow defaulted to a String type the compiled API is
loud, so latent wrong answers surface (the ratchet may move the wrong way first); validation
before normalization must keep the poison map carrying genuine "not built yet" walls (7
consumer sites) while modeling errors become loud. Incremental, never big-bang: the
knowledge API in front of normalization, shadow functions delegating, families migrated,
validation last.

## Recommended order

T4.1 as a design leg, first step = the include-order visible-set fact (family 1): the most
walkers and all nine pinned reaches for the least code.
