# Code as data: the extension-registry homework (2026-09-05)

Status: **PARKED by user decision 2026-09-05.** The five connection-equality
tests fail in both channels from batch 72c on; the walk's Java version of the
comparison (`ConnEquality.java`) is deleted; the compile-time mechanism built
for them is preserved on branch `wip/72c-extension-registry-read` and is NOT
on main. This document is the research, written so it never has to be redone.
It ends with the design leg that would run these five tests the right way and
would also give the engine's other tree-walking programs a chance to run.

Companion: docs/WALK_ONLY_PLAN_2026_09_05.md §5 (the by-need design as first
proposed, with the same trace) and §6 (the parking note).

---

## 1. What "extensions" are, in ordinary terms

The engine talks to many kinds of stores (relational databases, in-memory
models, aggregation-aware stores, …). Its core knows none of them. Each store
type hands the core a **bundle of functions** — "here is how to do X for my
kind of store" — and the core glues the bundles into a list and consults them.
The engine calls one bundle an `Extension` and a store's slice of it a
`StoreContract`.

```
relationalBundle = { type: 'relational',
                     availableStores: [ relationalContract ],   // ~25 functions each
                     tdsToRelation: …, moduleExtensions: …, … } // ~14 fields
modelBundle      = { availableStores: [ modelContract, aggregationAwareContract ] }
h2Bundle         = { moduleExtensions: [ the H2 SQL dialect ] }
```

"Are these two connections equal?" is answered through the bundles: every
contract may contribute a `connectionEquality` function; the core collects
them and tries each. The five tests exercise exactly that path:

```
// testRelationalExtension.pure:28-36
let extensions = meta::relational::extension::relationalExtensions().routerExtensions();
$c1->match($extensions.connectionEquality->map(e | $e->eval($c2))
           ->concatenate([a:Connection[1] | true])->toOneMany());
```

Why this is hard for us: our compiler turns Pure into SQL. Numbers, strings
and rows can be database values; **a function cannot**. A record whose fields
are functions never exists at run time in our world. The only way to honour
the test is to read the program text at compile time, see the record written
out literally, and pull the one field we need. That works while every step
is spelled structure (literals, concatenate, cast, field reads, map over a
literal list) and breaks the moment a step needs a type we lack or code we
have not admitted.

---

## 2. The chain, traced by hand (every program the compile-time reader meets)

| # | program | what it spells | read by this test |
|---|---|---|---|
| 1 | `relationalExtensions()` — core_relational `relational/extensions/extension.pure:62-65` | `relationalExtension()->concatenate(h2SqlDialectExtension())`. In Pure.java it is a BODILESS native (`RELATIONAL_EXTENSIONS__ANY_MANY`, a typing surface for the extension argument that every engine entry point takes and almost no test reads); the program itself is in the model because the corpus loads extension.pure as a setup file | whole |
| 2 | `relationalExtension()` — extension.pure:72-252 | `^Extension` with 14 fields: `type` (string); `availableStores = defaultExtensions().availableStores->concatenate(relationalStoreContract())`; `serializerExtension` (a function reference); `grammarSerializerExtensions = relationGrammarExtension()` (call); three `executionPlan_execution_*` lambdas; `tdsToRelation = tdsToRelationExtension()` (call → a record with one closure field; its class `TdsToRelationExtension_V_X_X`, core/pure/tds/relation/tdsToRelation.pure:28, was EXCLUDED from the prelude because its `transfers` property names the template protocol package `meta::protocols::pure::vX_X_X::metamodel::m3::…AppliedFunction`); `tdsSchema_resolveSchemaImpl`, `testExtension_testedBy` (lambdas); `validTestPackages` (string); `availableFeatures = ExecutionPlanFeatureFlagExtension().availableFeatures`; `moduleExtensions = [^RelationElementAccessorExtension(module = relationElementAccessorModuleExtensionName(), instancePrimaryKeyResolver = lambda)]` | `availableStores`, `availableFeatures` only |
| 3 | `h2SqlDialectExtension()` — `core_external_store_relational_sql_dialect_translation_h2/h2SqlDialect.pure:59-72` | `^Extension(type, moduleExtensions = [^SqlDialectTranslationModuleExtension(module = sqlDialectTranslationModuleExtensionName(), extraSqlDialects = h2SqlDialect())])`; `h2SqlDialect()` (42-57) assembles the whole H2 dialect from nine private helpers (`h2DialectNodeProcessors` :143, function-processor map, keywords, quote configs, precedence comparator, expected test errors, …) | nothing (`availableStores`/`availableFeatures` unspelled → declared `[*]` → empty) |
| 4 | `defaultExtensions()` — core `pure/extensions/functions.pure:84-93` | `^Extension(type = 'defaultExtensions', availableStores = [modelStoreContract(), aggregationAwareStoreContract()])` | `availableStores` |
| 5 | `modelStoreContract()` — core `store/m2m/storeContract.pure:20-43` | `^StoreContract`, no lets; strings, function references (`execution_StoreQuery_…`, `supports_…`, `planExecution_…`, `getterOverrideMapped_…`), lambdas, booleans; NO `connectionEquality` | `connectionEquality` → absent → RouterExtension declares it `[0..1]` (router_extension.pure:28) → empty |
| 6 | `aggregationAwareStoreContract()` — core `store/aggregationAware/storeContract.pure:26-39` | same shape; NO `connectionEquality` | same |
| 7 | `relationalStoreContract()` — core_relational `relational/contract/storeContract.pure:37-184` | `let defaultState = meta::relational::functions::pureToSqlQuery::defaultState([], newMap([]->cast(@Pair<String, List<Any>>)))` — the SQL query PLANNER's state (its body reaches `buildClassMappingsById`, core/pure/mapping/mappingExtension.pure:77); then `^StoreContract` with ~25 fields; `$defaultState` is used ONLY inside the `supports` closure (line 62); `connectionEquality` (48-60) = `{b:Connection[1] \| [d:RelationalDatabaseConnection[1] \| let bAsRDB = $b->cast(@RelationalDatabaseConnection); let comparison = $d.type == $bAsRDB.type && $d.timeZone == $bAsRDB.timeZone && $d.quoteIdentifiers == $bAsRDB.quoteIdentifiers && $d.datasourceSpecification == $bAsRDB.datasourceSpecification && compareObjectsWithPossiblyNoProperties($d.authenticationStrategy, $bAsRDB.authenticationStrategy) && postProcessorsMatch($d.postProcessors, $bAsRDB.postProcessors);]}` | `connectionEquality` |
| 8 | `ExecutionPlanFeatureFlagExtension()` — core executionPlan featureFlag.pure:64-89 | ONE trailing statement `let shared = ^Extension(type = 'featureFlag', availableFeatures = ^FeatureExtension(id = 'featureFlag', routeFunctionExpressions = [pair(lambda, lambda)]))` — a trailing let is the value | `availableFeatures` → the FeatureExtension record → its `connectionEquality` absent → empty |
| 9 | `routerExtensions` — core `pure/extensions/extension.pure:46-49`, a QUALIFIED PROPERTY of Extension | `$this.availableStores->concatenate($this.availableFeatures)->cast(@meta::pure::router::extension::RouterExtension)`. `StoreContract extends RouterExtension` (core/pure/store/storeContract.pure:9); `FeatureExtension extends RouterExtension, ExecutionPlanExtension` (extension.pure:140) | whole |
| 10 | `.connectionEquality` over `[modelSC, aggSC, relationalSC, featureExt]` | `[relational closure]` — three unspelled keys fold to their declared empty default (pure's dot rule concatenates) | — |
| 11 | `->map(e \| $e->eval($c2))`, concatenate the catch-all arm, `$c1->match(…)` | applying the closure yields ONE typed arm (`d:RelationalDatabaseConnection[1] \| …`); the arm list is then spelled → static dispatch on `$c1`'s class; the comparison folds over the two spelled `^RelationalDatabaseConnection` literals; `compareObjectsWithPossiblyNoProperties` (storeContract.pure:290) uses `type()->cast(@Class<Any>)->hierarchicalProperties()->size()` (metaExtension.pure:189, recursive over generalizations) and `postProcessorsMatch` (:278) uses `remove` (collectionExtension.pure:72). **This tail ran end to end** under the (deleted) named-fold version: 5/5 flipped, corpus 163/2410 | — |

Engine facts used along the way: json-simple accepts a stray `]"`; H2
2.1.214 has no base64 functions; DuckDB `decode(from_base64())` yields text.

---

## 3. What the honest attempt hit, in order (three probes, one pattern)

The morning version answered the test with Java that NAMED the relational
contract (`RELATIONAL_STORE_CONTRACT_FQN`) and read its arm — a hack shape
(Java knowing a program's name, WORLD_MAP rule 5). Replacing it with the
general mechanism (read the engine's own program) walled three times:

1. `Unknown type 'TdsToRelationExtension_V_X_X'` — field 2.`tdsToRelation`.
   Cause: the prelude generator excluded the class because it names the
   versioned protocol packages. Fix (on the branch): admit the ONE template
   package `meta::protocols::pure::vX_X_X::metamodel::m3::` out of the
   exclusion; the generator's closure pulled 39 lines of shapes cleanly.
2. `unknown function 'executeInMemory'` via `modelStoreContract → execution/7`.
   Cause: the inliner compiled a CLOSURE FIELD (`executeStoreQuery`) that
   nobody applies. Fix (on the branch): a lambda stored in a record field
   stands until applied (generalizes the postprocessor-config rule).
3. `unknown function 'buildClassMappingsById'` via `relationalStoreContract →
   defaultState/2`. Cause: an EAGER `let` whose only use is inside a standing
   closure.

Next in line if evaluation stayed eager (read off the table, not probed):
`relationGrammarExtension()`, `tdsToRelationExtension()`, `moduleExtensions`,
and through the H2 record `h2SqlDialect()` with its nine helpers. None of it
is read by the test.

**Diagnosis.** The inliner evaluates every field of a record and every let of
a body eagerly. The extension record is ~40 fields wide; this test reads two.
Eager evaluation makes the test depend on compiling the engine's whole plug-in
surface at compile time. Every wall was that one pattern, not five bugs.

---

## 4. The by-need design (proposed, reviewed, NOT implemented)

### 4.1 The rule

- **R1 — record fields stand.** Rewriting `^Record(…)` substitutes variables
  into every field but inlines no call (the inliner's existing "config" mode,
  `configMode`, today used only for postprocessor-config properties). Closure
  fields stand even when the record is forced: a closure's body compiles when
  applied. Strings, numbers, enum values and nested literals are kept as they
  are — the only thing deferred is a CALL to a user function.
- **R2 — a field read forces the field it names.** `$rec.f`, and `$recs.f`
  over a spelled collection (absent keys → declared defaults), yields the
  field's standing value rewritten in normal mode. Only that field.
- **R3 — lets are classified by their uses in the rest of the body.** No use →
  not evaluated. Every use inside a record field → substituted standing. Any
  other use → eager, exactly as today.
- **R4 — the query boundary forces.** After the top-level rewrite a record
  that survives is data for the lowering: its non-closure fields are forced
  whole (nested records included); queryLets likewise. A tree with no
  standing call skips the pass.

Where it would live: UserCallInliner (record arm ~line 957, property-access
arm ~912, `reduceStatements` ~496, `inlineBody` ~120) plus a shared
`read(pa, ctx)` helper in LiteralUnroll (its `fold` pa arm, ~337). About 80
lines. No Java names any program; no new native.

### 4.2 Precedents

Haskell evaluates record fields and lets by need. C++ instantiates a
template's member functions only when used, so an unused member with a type
error never errors. Online partial evaluators (which is what the inliner is)
residualize what they cannot evaluate instead of failing.

### 4.3 The one semantic deviation — say it plainly

Pure is strict: `^Foo(a = fail())` fails even if nobody reads `a`. Under
by-need it would not. For any expression that succeeds the result is
identical; only the moment of evaluation moves. The deviation is observable
only when an unread field or let would have ERRORED — which in our compiler
means "we cannot compile the engine's query planner", the case we want to
skip — but it would equally hide a genuine assertion in an unread field.

### 4.4 Interactions checked

- Renaming/capture: standing fields are rewritten under the frame's env at
  standing time (variables substituted, lambdas renamed); forcing runs with an
  empty env.
- Recursion detection (the callee `stack`): forcing happens at the READ site
  or the boundary, where the stack is the reader's. A standing call whose
  forced inlining re-enters a function already on the stack would be flagged
  where eager evaluation was not. Judged rare; unproven on paper.
- A dynamic-arm `TypedMatchRuntime` inside a standing closure: the inliner
  leaves it; the "did not fold to []" throw is MatchFold's at lowering.
- `reduceStatements`' non-let-intermediate wall is not reached by standing
  lambda bodies (`lambda()` rewrites statements one by one).
- The boundary pass is one more rewrite over trees still carrying a user
  call; rewrite is idempotent on inlined trees.
- Closures that reach the lowering keep their user calls uninlined — which
  corpus tests build such records is unknown → full corpus before any commit.
- Guards: CodeShapeGuardrail 3500 (UserCallInliner 1371, LiteralUnroll 677),
  string-dispatch pin 87, catch-returns-value pin 15 (no new catch), native
  catalog, NativeCatalogGovernance INTERNAL_DESUGAR, ArchitectureTest 6c'.

### 4.5 The alternative considered

Keep eager evaluation and turn each field that fails to compile into a
marker that errors only if read (`walledKey`, on the branch). Same
observable behaviour as by-need, but it compiles the entire H2 dialect on
every one of these tests and relies on catching failures — the pattern the
guardrails pin. By-need is cheaper and needs no catch.

### 4.6 Why it was not implemented

With the code-as-data leg (§6) built, by-need becomes an optimization rather
than the thing that makes the tests possible. Building it now, for five
tests, in the middle of the burn, is the wrong order (user, 2026-09-05).

---

## 5. Why the unread programs do not compile — three kinds, with receipts

| what failed | where it lives | kind |
|---|---|---|
| `executeInMemory` | core/store/m2m/inMemory.pure:28 (416 lines) | A — the engine's in-memory interpreter for model-to-model mappings |
| `buildClassMappingsById` via `defaultState` | core/pure/mapping/mappingExtension.pure:77 | A at the surface (planner state); its body is metamodel reflection over a Mapping (kind C material) |
| `h2DialectNodeProcessors` + eight siblings | h2SqlDialect.pure:143 | A — the engine's SQL renderer for H2 |
| `relationGrammarExtension` | core_relational grammarSerializerExtension.pure:287 | A — the engine's Pure grammar pretty-printer |
| `TdsToRelationExtension_V_X_X` | core/pure/tds/relation/tdsToRelation.pure:28 | B — vocabulary our prelude generator excluded (fixed on the branch) |
| `.func` on a SimpleFunctionExpression (the morning's walled keys) | m3 | C — our `FunctionExpression` shape carries `functionName`/`parametersValues` only (§6.1) |

- **Kind A — the engine's implementation of itself.** Interpreter, planner,
  renderer, printer: the programs our compiler exists to REPLACE. Never
  ported (WORLD_MAP: engine = semantic spec; compiler internals = decisions).
  Skipping them is not masking a gap; it is declining to compile the
  competitor.
- **Kind B — vocabulary not loaded.** Real gaps, fixed as found; a loaded and
  READ thing that fails still fails loudly.
- **Kind C — genuine typer/model gaps.** Named so nothing hides. A deferred
  or walled field hides a kind-C gap only while unread; a read fails loudly
  with the field name and reason. Proposal (not built): a walled/deferred-key
  CENSUS in the corpus ledger so kind C is a countable bucket.

Correction recorded: the claim "a bare function reference does not type" was
WRONG. Typer ~2551: an unambiguous reference `foo_String_1__String_1_`
ETA-EXPANDS into the lambda calling it; an ambiguous one with an existing
base becomes an opaque `Function<Any>` value. The walled keys were `.func`
reads (kind C above), not references.

---

## 6. Could a compiler written in Pure run on our platform? The interpreter, line by line

`executeInMemory` (inMemory.pure:28-42), trimmed:

```
let res = $v->match([
   f:FunctionExpression[1] |
      if($f.func.name == 'getAll_Class_1__T_MANY_' || …,
         | let impls = $e.sets->resolveOperation($mapping)->cast(@PureInstanceSetImplementation);
           $impls->map(impl | inMemoryGetAll($impl, …))->setOperation($e);,
         | $f.func->evaluate($f.parametersValues->map(p | list($p->executeInMemory(…).values)))),
   r:FunctionRoutedValueSpecification[1] | $r.value->cast(@InstanceValue).values->at(0)
                                           ->cast(@LambdaFunction<Any>)->routeGettersInCode($e),
   e:StoreMappingRoutedValueSpecification[1] | $e.value->executeInMemory(…).values,
   i:InstanceValue[1] | $i.values->map(v | $v->match([vs:ValueSpecification[1] | $vs->executeInMemory(…), a:Any[1] | $a]))
]);
^Result<Any|*>(values = $res);
```

Features it needs, and what we have:

1. **The program as data** (`$v->match([f:FunctionExpression[1] | …])`,
   `$f.func.name`, `$f.parametersValues`). We hold the typed tree inside the
   compiler; classes/properties/mappings are ROWS in the system database; but
   the expression tree is never handed to a Pure program as m3 INSTANCES.
   What exists: the m3 classes are in Pure.java as native classes but THIN
   (`FunctionExpression { functionName: String[0..1]; parametersValues:
   ValueSpecification[*] }`, `InstanceValue { values: Any[*] }`,
   `LambdaFunction<F> {}` — no `func`, `genericType`, `multiplicity`,
   `expressionSequence`); `TypedDeactivate` already carries a QUOTED tree as
   a value (`quotedFrames` in the inliner). Missing: a printer from our ~80
   typed node kinds to about six m3 classes (every call/property read/relation
   op → `^SimpleFunctionExpression(func = …, parametersValues = […])`;
   literals → `^InstanceValue`; variables → `^VariableExpression`; lambdas →
   `^LambdaFunction(expressionSequence = […])`; `func` spelled with the
   mangled id — SignatureMangle exists), and the reverse reader so `eval` of
   code assembled from literal pieces works. Each ~300 lines, mechanical.
2. **Function values from data** (`$f.func->evaluate(args)`; `setOperation`'s
   `newMap(pair(merge_…, mergeResult_…))->get($operation)`). Mostly there:
   references eta-expand (above); a reference read off a literal tree is the
   same reference. Missing: map lookup keyed by function identity (one fold:
   compare references by name). The general run-time case is
   DEFUNCTIONALIZATION (every function that flows as a value becomes a tag;
   a call through a value becomes a dispatch over the tags) — known, not built.
3. **Recursion over data** (`executeInMemory` on every sub-expression). The
   inliner unrolls recursion only when it descends into a LITERAL it can see
   (Tier 1, LiteralUnroll). Over rows it needs recursive SQL — task #4
   (JoinTreeNode/SelectSQLQuery) is the first instance; not built.
4. **Building code at run time and running it** (`routeGettersInCode` copies
   a lambda with a rewritten body and later evaluates it). Literal pieces →
   foldable. Code that exists only at run time → NO compiler can help; the
   only way is an interpreter inside the target — the thing we decline to
   build. This is the one genuine impossibility ("dynamic recursion").
5. **Mixed-type collections** (`Result<Any|*>`). The variant carrier we have:
   an `Any` scalar riding as JSON (`VariantShapes`, TO_VARIANT) and mixed
   Number/Date lists split into identity + comparable channels
   (`MixedEncoding`). Missing: a list mixing INSTANCES of different classes
   with `match`/`cast` over the mix — a DISCRIMINATED ROW (tagged union of
   structs), the item the metamodel-as-relations homework already named. For
   the static version it is not needed: the mixed lists are compile-time
   literals; only rows of the mapped class reach SQL.

**The partial-evaluation point.** In the corpus the query is not run-time
data — it is a literal lambda the test wrote. So `$v` is a literal, the match
is on a literal's class, `$f.func.name` is a readable string, the recursion
descends into literal structure (the one kind we handle), and the only
run-time input is the source objects, which for us are rows. Run the
interpreter through a partial evaluator with the query fixed and what comes
out is a SQL query over those rows (Futamura's first projection) — what our
compiler does BY HAND for model-to-model mappings today.

**Sizing.** Not crazy: two to four legs — widen the m3 shapes (hours); the
printer (~300 lines); the reader (~300 lines); then grind fold rules against
the interpreter body (each unfolded construct — `resolveOperation`,
`functionReturnType().rawType`, `newMap` lookups — an hour to a session,
historically). The schedule risk is the grind, not the design.

**Payoff.** Running the interpreter on a literal query reproduces the SQL our
M2M path already emits — a second derivation to check ours against, not new
capability. The real payoff: several of the both-channel failures are engine
features written as Pure programs OVER THE QUERY TREE (schema resolution
`tdsSchema_resolveSchemaImpl`/`resolveSchema`, routing `routeFunction`, plan
printing, protocol transforms) — the `decision:*` buckets exist precisely
because feature 1 does not. Feature 1 is the design leg to size (not probe)
after the burn.

---

## 7. What the parked branch holds (`wip/72c-extension-registry-read`)

File by file, so the next session knows what is there and why:

- `builtin/Pure.java`: raw `Class` declaration with `name`/`properties`
  (system-store end typed as a raw row class); `ROUTER_EXTENSIONS` native
  REMOVED (the engine's program is compiler input); `RELATIONAL_STORE_CONTRACT_FQN`
  removed; `Lite.WALLED_KEY` + `WALLED_KEY__STRING_1` (residual error marker).
- `builtin/SystemMetamodel.java`: `class_ancestry` table (class_fqn,
  ancestor_fqn, depth) + joins ClassToProperties/ClassToAncestry/
  ClassAncestryToAncestor/ClassToSelf; classes `ClassAncestry`,
  `ClassDescendant`, association `ClassAncestries`; mapping arms; platform
  functions `routerExtensions(_this)` (extension.pure:46-49 as a function)
  and `hierarchicalProperties(class)` as a navigation over the rows.
- `MetamodelSeeds.java`: `classAncestry` BFS over superClassFqns with depth.
- `compiler/spec/LiteralUnroll.java`: `type(<Instance>)` → class ref;
  cast of a class ref to `Class`; cast over a collection of calls by declared
  type; `collectionFieldWithDefaults` (dot rule with declared defaults for
  absent keys).
- `compiler/spec/UserCallInliner.java`: `spelledArms` (declared-type lambdas
  as match arms → static dispatch, folded dyn prefix); `spelledProgramOr`
  (a bodiless native consumed structurally hands off to the model's same-FQN
  program); closures in record fields stand until applied; inlineCall's
  compile catch tries `SpelledInstanceBody.partial`.
- `compiler/spec/SpelledInstanceBody.java` (new): key-by-key typing of a
  `[lets…, ^Instance]` body; failing keys become `walledKey(...)`.
- `lowering/AsorReaders.java`: `walledKey` rule throws NotImplementedException.
- `resolver/StoreResolver.java`: structural `TypedIf` when no branch holds a
  getAll; `EXTENT_COUNT_COL` count terminal (INNER hops for size-over-chain).
- `rcorpus/Corpus.java`: LIBRARY_FILES + functions.pure, m2m storeContract,
  aggregationAware storeContract, h2SqlDialect.pure.
- `rcorpus/RelationalCorpusRunner.java`: verbatim `remove` (collectionExtension.pure:72).
- `tools/PreludeGeneratorTest.java`: template protocol package admitted;
  `builtin/Prelude.java` regenerated (+6 classes earlier, +39 lines protocol).
- `builtin/NativeCatalogGovernanceTest.java`: INTERNAL_DESUGAR pin 17.
- `harness/ConnEquality.java` DELETED and `harness/EngineTestExecutor.java`
  call sites removed — the only part re-applied on main.

The morning's named-fold version (Java naming `relationalStoreContract`) is
NOT on the branch; it was deleted before the branch was cut.

---

## 8. Decision record

- 2026-09-05, user: "are we hacking? did we look at what engine code does?" →
  the named fold was hack-shaped; replaced with the general mechanism.
- 2026-09-05, user: pause before changing evaluation order in the inliner;
  design on paper first (→ §4, plan doc §5).
- 2026-09-05, user: delete the walk's Java version, let the five fail in both
  channels with a note, park the mechanism, burn the rest, come back with the
  code-as-data leg (§6) that also gives the engine's other tree-walking
  programs a shot.

The ratchet's fallback count does not move (the five were already
fallbacks); the walk lane stops passing them, so they stop looking like
something the platform is close to. Their ledger bucket is the loud wall
`wall:lowering — scalar match: the arm collection has a non-literal prefix
(extension-contributed arms) that did not fold to []`.
