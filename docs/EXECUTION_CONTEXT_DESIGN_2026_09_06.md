# Execution context as a VALUE — step A of the harness endgame (2026-09-06)

User ruling (2026-09-06): the executor had accumulated nineteen "search the typed tree
for a shape" walks and 76 loose `equals("meta::…")` name checks across 32 files; two
corpus tests (m2m2r::executeProjectWithNestedDerivedProperty, paginate::testPaginated)
regressed under the statement splice because ONE route each forgot to re-derive the
execution context that other routes re-derived by walking. "Are we building a general
purpose pure runner or a super hard-coded test runner?" — the answer is this leg.

## The nuance, stated once

Two kinds of name knowledge exist and must not be confused:

1. **Special forms.** `from`, `execute`, `executionPlan`, `executeLegendQuery`,
   `executeInDb` and the DDL natives are natives whose arguments are programs or
   contexts, not values. Their implementation is a COMPILATION RULE (the resolver, the
   lowering, the effect order), exactly as `if` and `let` are special forms in any
   language. The platform's catalog (`PlatformTypes`) is the one place that names
   them and classifies their kind (`IMPLEMENTATION_KIND`). Every other file asks the
   catalog; no other file spells a Pure function name. Ratchet: literal
   `equals("meta::…")` checks outside PlatformTypes, 76 → shrink-only → 0.

2. **The execution context.** Mapping, runtime, the runtime's connections' inline
   setup SQL / CSV, the model-chain mappings, connection flags (quoteIdentifiers,
   timezone, database type, connection name), JSON sources. Today each route
   re-derives these from the typed tree with its own subset of walkers
   (`TypedFrom.chainMappingsIn / jsonSourcesIn / setupsIn / connectionNameIn`,
   `ConnectionFlags.*Of`, `firstFromChainMappings`, the `runRuntimeSetups` walk,
   `ExecuteChainAssembly`'s let chasing, `RoutingContext.routedContext`'s own inline
   + chain read). A general-purpose Pure runner evaluates `runtime()` as a VALUE once
   and binds it; every consumer reads a field.

## The design

`com.legend.compiler.spec.typed.ExecutionContext` — an immutable record:

```
mapping        Optional<String>          the mapping FQN the query compiles against
runtimeFqn     Optional<String>          a NAMED runtime (packageable ref), when one
chainMappings  List<String>              ModelChainConnection mappings (M2M2R layers)
jsonSources    Map<String,String>        class FQN → JsonModelConnection url
sqlSetups      List<String>              LocalH2 testDataSetupSqls blobs, in order
csvSetups      List<CsvSetup>            testDataSetupCsv blobs with their store
connectionName String?                   the connection's element/name for plan text
quoteIdentifiers boolean                 TestDatabaseConnection(quoteIdentifiers=…)
timeZone       String?                   the connection's timeZone
databaseType   String?                   DatabaseType.X of the connection
```

**One reader.** `ExecutionContext.read(Optional<TypedPackageableRef> mapping,
@Nullable TypedSpec runtimeArg, RuntimeValue value)` where the runtime argument is
first brought to a VALUE by the caller's means: at typing (FromChecker) the helper
bodies are read raw through the module (the existing raw collectors, moved inside the
reader); after inlining (the executor, the resolver's routing, the chain assembly)
the argument IS the value. The reader is the ONLY code that knows the field names
`connectionStores`, `connection`, `mappings`, `testDataSetupSqls`, `testDataSetupCsv`,
`quoteIdentifiers`, `timeZone`, `type`, `element`, `url`.

**One holder.** `TypedFrom(source, ExecutionContext context, boolean executedExtent,
info)`. The seven loose fields are gone; accessors delegate to the context so the
resolver's reads keep their spelling. Every rebuild site passes `fr.context()`.

**Binders (the special forms' rule, four call sites, one reader):**
- `from(mapping, runtime)` — FromChecker, at typing.
- `execute(f, m, rt, ext)` / `executeLegendQuery(...)` / `executionPlan(...)->execute`
  in STATEMENT position — ExecuteChainAssembly.chain builds the envelope from with the
  bound context (the frame route).
- the same calls in VALUE position — RoutingContext.routedContext (the resolver's
  arm); plan-execute peels its `executionPlan(...)` build to reach the mapping and
  runtime (the m2m2r fix: a plan-execute in value position had NO mapping argument
  and fell to the outer context).
- `executionPlan(...)` for plan TEXT — the plan route reads flags and connection from
  the bound context instead of re-inlining the runtime argument twice.

**Consumers read fields.** Connection flags, chain mappings, setups, connection name,
JSON sources: all `context.x()`. The resolver's `Context` is built from the from's
`ExecutionContext` (JsonSourceFrame.fromContext) and a from that declares no chain
INHERITS the enclosing chain (a value re-evaluating a plan execution).

**Setups run once, at the statement.** Before a statement executes, the statement
channel establishes every execution context in it (the one walk, in one place:
`ExecutionContext.froms(statement)`): each from's sqlSetups/csvSetups run in tree
order, deduplicated per statement. The per-route `runRuntimeSetups` calls
(executeTyped's from loop, the legend-query inline branch, the eager frame) are
deleted. The paginate fix: its helper runtime seeds a seven-row PersonTable that
the graph serialize route never established.

## What is deleted by this step

- `TypedFrom.chainMappingsIn / jsonSourcesIn / sqlSetupsIn / setupsIn /
  connectionNameIn` as public walks (internals move into the reader, private).
- `ConnectionFlags` (its four readers become reader internals).
- `StatementExecutor.firstFromChainMappings`, the two `rtArg` re-inlining blocks,
  the `chainMaps` derivations, `runRuntimeSetups`' walk and its three callers.
- `RoutingContext.routedContext`'s own inline-and-walk.
- `ExecuteChainAssembly.chain`'s chainMappingsIn/jsonSourcesIn calls.

## Ratchets (shrink-only, guardrail)

- `equals("meta::` literal checks outside PlatformTypes: 76 → the count at landing → 0.
- Public tree-walk readers of runtime shapes outside ExecutionContext: 0 at landing.

## STATUS: LANDED as batch 114 (2026-09-06) — 122/2451, minimal 2454; the deletion list below is
empty except that `firstFromChainMappings` / `firstFromConnectionName` remain as "the first from's
context" queries over the one walk (ExecutionContext.froms); literal name checks 76 → 73 at landing.

## Acceptance

Both regressed tests pass with no per-route patch; old runner 122/2451, minimal
harness 2454 (= 2451 + the three walk-only trivial passes); chain green; the
deletion list above empty.

## What this step is NOT

Not the single-shot compile (step C): the statement channel still runs statements in
order; asserts are still per-statement verdicts. Step A makes the context a value so
that step B (delete the old harness, converge the call-frame route into the splice)
and step C (one verdict query per test) build on one binding instead of nineteen.
