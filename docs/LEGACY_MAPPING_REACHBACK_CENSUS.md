# Legacy-mapping reach-back census (2026-08-30)

`LegacyMappingDefinition` is documented B→E-only ("MappingNormalizer is the
sole consumer" — ModelBuilder.findLegacyMapping javadoc). That contract is
violated by every post-compile consumer that re-derives, at query time and
from the raw parse artifact, a fact construction already knew. This census
lists EVERY `findLegacyMapping` call site with a verdict. Doctrine:
instrument state matches fact lifetime — compile-time facts ride the
compiled artifact.

Fixed this leg (4e6da393 + this commit): the include-flattened
enumeration-mapping table is now BAKED onto the normalized
`MappingDefinition` at the Phase-E seam (`MappingNormalizer.normalizeMapping`);
`PlanText.enumMappingOf`/`enumMappingIdFor` and the harness read the
compiled artifact — no walk, no lookup lambda, no legacy reach for the
enum list.

## LEGITIMATE — construction-time (Phase B→E, the artifact's design window)

| site | use |
|---|---|
| MappingNormalizer (4) | Phase-E translation consumes legacy BY DESIGN |
| UnionSynthesis (3) | Phase-E union synthesis walks includes of the raw surface |
| AssociationSynthesis (1) | Phase-E association synthesis, same |

(Hygiene note, not a placement defect: the normalizer package hand-rolls
the classMappings include-walk in 3+ places — same drift class the enum
walk had. Consolidation candidate inside Phase E.)

## LEGITIMATE — surface contract

| site | use |
|---|---|
| MetamodelWalk (3) | the Pure metamodel API PRESENTS the authored mapping (engine metamodel = includes + own lists; .pure navigation does its own traversal). Legacy IS the spec here. |
| PureModelContext (4) | registry plumbing (interface impl) + name-existence checks across both registries (eager runtime-ref validation, isExecutionContextElement) |
| ModelContext (1) | interface declaration |
| Tests: H2Verify mappingFqnOf (2), rcorpus Runner (1), integration ×3, ModelBuilderTest, NameResolutionContractTest, ElementParserTest | name-existence checks and parse/resolve-surface spec pins on the authored artifact |

## THE RAZOR (ratified 2026-08-30, supersedes the "side table" idea)

Two tenets hold at once: (1) everything on the AST; (2) mappings
normalize to functions — ONE compiler path. The reconciliation:

- The lifted FUNCTION is the only carrier of ROW SEMANTICS. Any consumer
  needing what rows/values mean walks the function or the lowered
  artifact. A struct field that would make a consumer replicate compiler
  logic is a shadow structure and must not exist.
- CLOSED FACTS known at construction are CACHED ANSWERS stamped on the
  binding node by the SAME code that resolves them for the body — one
  derivation site (`resolvedMainTable`/`relationalSourceOf`; Door 1
  stamps at the lift via `inlineRootSource` on the structural
  `tableReference` root). Consumers read them VERBATIM. Same species as
  `root`, `setId`, `declaredPrimaryKeyColumns`, `rowDropping`.
- SEALED, NEVER NULLABLE (user ruling 2026-08-30): `ClassBinding` is
  sealed `Relational | Pure` (the Kind enum died — the variant IS the
  kind), `Relational.source` is NON-NULL of sealed type
  `RelationalSource = Table | Json | Undeclared(why)`. A door that
  forgets to stamp does not compile; a door that cannot derive SPELLS
  it (`Undeclared` carries the reason, poison idiom). Absence by null
  was retrofit expedience and traded a compile-time guarantee for a
  null-guard convention — reversed same day.
- EVERY DOOR STAMPS or the artifact FORKS BY ORIGIN: the convergence
  suite (LegacyCleanSheetConvergenceTest) is the referee that makes
  forgetting a door impossible — it caught the legacy-only stamp
  within one gate cycle.
- OPEN-ENDED questions (analyze the computation) are walks, never
  stamps — caching them would summarize the body into metadata and grow
  a shadow model.
- Keyed side tables OFF the node are banned; verified invariant
  (registry receipts 2026-08-30): lifted functions are IMMUTABLE post
  Phase E (append-only registration ModelBuilder:304, no
  overwrite/remove API, every constructor + the sole copy-helper
  caller is construction-time), so stamps cannot drift from bodies.

## KILL ROWS — status

| row | site | disposition |
|---|---|---|
| PHYS-1 | PlanText.enumMappingIdFor | KILLED: reads `ClassBinding.source().enumColumns()` (id spellings, verbatim) |
| AGG-1 | AggAwareActivities.aggAwareSetId | KILLED: `classBindingsWithIncludes` + `source().aggregationAwareMain()` dispatch flag |
| VF-1 | ViewFrames.frameNameOf | KILLED: stamp carries the Phase-E RESOLVED main source (scope-inference included — the same `resolvedMainTable` call the synthesis makes); the view-name check stays a DATABASE registry lookup |
| SCAN-1 | ScanRelations lineage (2 sites) | OPEN — the one legitimate WALK conversion: lineage asks open-ended questions and must walk the lifted typed functions (one compiler path), not read stamps. Own leg. |

Bonus fix riding the ctor change: `liftInlineBindings` rebuilt bindings
through a convenience ctor that silently dropped `primaryKeyColumns`
(the AssocJoin disease, latent); convenience ctors are DELETED — every
site spells every component.

## Residual design note

Canonical (clean-sheet) mappings authored WITH includes and enumeration
mappings do not flatten today (no corpus witness; the legacy→canonical seam
covers the entire engine corpus). If clean-sheet includes gain a witness,
the same flatten belongs in `resolveCanonicalMapping`/Phase-E for the
canonical path.

## UNDECLARED ERADICATION (2026-08-30, user-ratified — phase types over in-band markers)

`RelationalSource.Undeclared` is DELETED. It conflated three things,
each now handled honestly:

1. **The pre-lift placeholder** → `CleanSheetMappingDefinition`, Door
   1/3's own B→E surface type (the `LegacyMappingDefinition` precedent
   applied to the clean-sheet door — the asymmetry was the bug). The
   compiled `MappingDefinition.ClassBinding` now carries a plain
   `functionFqn` STRING and a stamped source: no `Realization` union in
   the artifact, no throw-guarded `functionFqn()` accessor — the §7.4
   "no Inline survives Phase E" guard became a TYPE guarantee.
2. **Source shared through user functions** → the stamper FOLLOWS the
   chain: a body root that calls a registered user function recurses
   into that function's body (cycle-guarded) and stamps the Table the
   chain bottoms out at. "Mappings that call user functions" derive
   like everything else — construction-time, at Phase E. (A transient
   `Derived` variant existed for one commit-cycle as a stop-at-one-hop
   compromise; the user killed it: if one hop is legitimate
   derivation, so is the fixpoint.)
3. **Actual errors** → THROW: a ref binding naming an unknown/body-less
   function, a cyclic source chain, a root that never reaches a store
   access ("declare the binding Pure or root it at #>{db.TABLE}#"),
   and the legacy stamp arm whose synthesis must already have walled
   (`IllegalStateException`, invariant). All ride the per-element wall
   sink in tolerant builds. The two fixtures that motivated the
   compromise (`Relational { model::Person.all() }`) were semantically
   nonsense and are now honest table-rooted bodies.

End state: `RelationalSource = Table | Json`, sealed, total, no
unknown variant; both mapping doors have phase-separated types
(`CleanSheetMappingDefinition` ↔ `LegacyMappingDefinition`); the
compiled binding is `functionFqn` + stamp (no Realization union, no
throw-guarded accessor — §7.4 became a type guarantee).
