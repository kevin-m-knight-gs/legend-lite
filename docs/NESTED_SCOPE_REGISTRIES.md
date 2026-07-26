# Nested-scope registries — one factory, every scope

## Problem

Multi-hop navigation consumed inside a NESTED substitution scope walls or
mis-resolves because each scope hand-threads its registries, and several
pass empty ones. The census (2026-07-25, at ledger 1599):

| Site | Registries today |
|---|---|
| `StoreResolver.resolveChain` root terminals (2869/2877/2919) | FULL (assocs, ends, exists, agg, inQuery) |
| `StoreResolver.flattenNavSlot` belowOps splice | **`belowScope(...)` (factory)** — slices 1–2; hop-colliding ops HOIST above the materialization with the hop's AssocSub |
| `StoreResolver.flattenSource` assoc-route splice | **`belowScope(...)` (factory)** — slice 3 |
| `StoreResolver.flattenMaterializedNav` splice | **`belowScope(...)` (factory)** — slice 3 |
| `registerExistsSubs` nested scopes (navigate + assoc + dotted) | `nestedScope(...)` — **R1/R2, the real factory** |
| `Substitution.filteredNavLeafRead` predSub (:2086) | ExistsSub.innerRegs (from nestedScope) |
| `Substitution` class-filter / correlated preds (:2233/:2262) | ExistsSub.innerRegs (factory-built via `withInnerRegs`) — the pre-threading "partial" tag is stale |
| `CorrelatedSubselects.predFilteredPipe` (:1435) | nav-assocs or NONE |
| `AssociationJoins` condition subs (964/981/1050/1074) | condition-only scopes (no chains legal there) |

## Status (2026-07-26): leg COMPLETE

All three flatten splices consume the ONE factory through
`StoreResolver.belowScope` (paths from the ops' own lambdas via
`FlattenOps.splitBelowOps`, scope from `scopeMaterials`, substitution
against the widened row). The `Substitution` sites consume
factory-built `ExistsSub.innerRegs`. Remaining `Map.of()` scopes are
the condition-only sites where chains are structurally illegal.
Landed: d4cf7dba (factory + slice 1), 9b7e9c30 (slice 2, hop-colliding
hoist), 28a4af88 (slice 2b, framed-view slot targets), slice 3 (this
commit). Converted: testIsolationOfInputToIsEmpty,
testJoinThroughView.

## The rule (engine parity)

The engine holds ONE cursor context and re-enters `findPropertyMapping`
per hop; registries are therefore a FUNCTION of
`(ClassSource, consumed paths, context, pipeline)` — never a per-site
hand-off. `nestedScope` already implements that function: exists
materials (`registerExistsSubs`), assoc/chain materials
(`CorrelatedSubselects.nestedAssocMaterials`), and — critically — the
PIPELINE WIDENED with the nested joins (R2 arm 2), which is exactly the
ordering lesson from the two reverted attempts (materialize-then-widen;
RowScope typed off the WIDENED row; never fold raw joins under a flatten
hop).

## Plan

1. **Extract** `scopeMaterials(t, innerPaths, innerFullPaths, innerOps,
   context, targetPipe, pathKey)` = the lower half of `nestedScope`
   (temporal nested-frame guard included). `nestedScope` keeps only the
   lambda-extraction upper half and delegates. No behavior change —
   suite + sweep must hold exactly.
2. **Adopt at `flattenNavSlot`** belowOps splice: consumed paths from the
   below-ops' own lambdas feed the factory; the splice base and the
   RowScope both use the widened pipeline; `substitution(...)` receives
   the factory's registries. Converts the `[assocs=[]` family
   (testIsolationOfInputToIsEmpty, testJoinThroughView).
3. **Adopt at the remaining `Map.of()` splices** (flattenSource assoc
   route, flattenMaterializedNav) one slice each, sweep-gated.
4. Later: fold `Substitution`'s partial scopes (:2233/:2262) onto the
   same factory where their shapes allow.

Each step is REVERT-ON-REGRESSION gated; the factory-first step is
behavior-neutral by construction.
