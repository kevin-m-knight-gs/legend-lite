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

## REACH-BACKS TO KILL — query-time consumers of facts that die at Phase E

All four are the same disease: the normalized binding table lambda-lifts
class mappings into function refs, so PHYSICAL class-source facts survive
only in the raw artifact, and each consumer re-derives them with its own
hand-rolled include walk. The kill is one design: a Phase-E side table on
the normalized artifact (per set: classFqn/setId → database, mainTable,
aggregationAwareMain, per-column enum-mapping ids), computed once at
construction; all four consumers become field reads.

| row | site | fact it re-derives | kill |
|---|---|---|---|
| PHYS-1 | PlanText.enumMappingIdFor (1 remaining reach, commented in code) | which enum mapping a COLUMN uses (EnumeratedColumn.table/column → enumMappingId) | per-column enum-mapping ids on the side table |
| VF-1 | ViewFrames.frameNameOf (2) | classFqn → mainTable(table, database) over the include closure (view frame naming) | mainTable on the side table |
| AGG-1 | AggAwareActivities.aggAwareSetId (1) | classFqn → aggregationAwareMain setId over the include closure | aggAware flag + setId on the side table |
| SCAN-1 | ScanRelations lineage (2) | the full relational class-mapping set over the include closure, at scan time (orElseThrow on legacy!) | lineage scans the SAME normalized artifact the compiler used; heaviest row — needs the side table to carry enough physical shape, or lineage moves to the lifted typed functions |

Order: design the side table once (it is the ONE new fact carrier), then
PHYS-1 → AGG-1 → VF-1 → SCAN-1 by increasing surface. After SCAN-1, the
`findLegacyMapping` javadoc's claim becomes true again for query-time code,
and the remaining callers are construction, metamodel surface, and
existence checks only.

## Residual design note

Canonical (clean-sheet) mappings authored WITH includes and enumeration
mappings do not flatten today (no corpus witness; the legacy→canonical seam
covers the entire engine corpus). If clean-sheet includes gain a witness,
the same flatten belongs in `resolveCanonicalMapping`/Phase-E for the
canonical path.
