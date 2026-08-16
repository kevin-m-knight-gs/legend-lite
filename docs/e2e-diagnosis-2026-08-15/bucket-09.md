# Bucket 9 — Typer / vocabulary gap (G-phase)

26 tests from the ledger; **26 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: REAL DEFECT 13, MISSING FEATURE 10, TESTS ENGINE INTERNALS 3

---

## `testFromWithMapping`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

`meta::pure::mapping::withMapping(t, mapping)` is not registered anywhere in legend-lite. `core/src/main/java/com/legend/builtin/Pure.java:1293` registers its sibling `WITH_CHAINED_MAPPINGS` (`meta::pure::mapping::withChainedMappings<T>(source:T[*], mappings:Mapping[*]):T[*]`) but there is no `withMapping` constant, and `core/src/test/resources/native-catalog.txt:180` (the golden catalog) likewise lists only withChainedMappings. It is not a CoreFn either — `core/src/main/java/com/legend/compiler/spec/CoreFn.java:114` has `FROM("from")` but no withMapping entry. So `Typer.checkGeneric` finds zero candidates and throws the exact observed message at `core/src/main/java/com/legend/compiler/spec/Typer.java:1446-1452`. Two consequences chain from that. (a) The harness's discovery gate never sees a mapping: `Runner.executeMappingRefs` (`core/src/test/java/com/legend/rcorpus/Runner.java:839-925`) accepts `execute(q, MAPPING, …)` when arg1 is a `PackageableElementPtr` and `from(src, MAPPING, …)` (the `fromShape` branch, line ~901), but here execute's arg1 is the `^Mapping(name='')` sentinel and `from(testRuntime())` carries only a runtime — the mapping lives inside `withMapping(...)`, which is not a recognized shape. `mappingRefs` comes back empty, so `run0` takes the no-execute branch at Runner.java:1287-1315, calls `tryRunNoExecute`, and the typer wall becomes the ` — wall: …` suffix on the SHAPE outcome. That is exactly the observed string. (b) Even with the native registered, `FromChecker` would not know what to do with it: `core/src/main/java/com/legend/compiler/spec/FromChecker.java:92-104` peeks at the from() SOURCE for a `withChainedMappings` TypedNativeCall and absorbs its refs into `chainMappings`, but has no arm for withMapping, so the node would survive into lowering and no mapping would reach `TypedFrom.mapping()` (which is what `JsonSourceFrame.fromContext` at `core/src/main/java/com/legend/resolver/JsonSourceFrame.java:158-163` turns into the resolver Context, and what `StatementExecutor.firstFromMapping` at `core/src/main/java/com/legend/StatementExecutor.java:803-816` uses to name the plan).

**Fix**

Three coordinated edits, all mirroring the existing withChainedMappings channel.

1. `core/src/main/java/com/legend/builtin/Pure.java` — beside WITH_CHAINED_MAPPINGS (line 1293) add, with the same citation-style comment (engine mappingExtension.pure:386, routing.pure:724):
   `public static final NativeFunctionDefinition WITH_MAPPING = signature("native function meta::pure::mapping::withMapping<T|m>(source:T[m], mapping:meta::pure::mapping::Mapping[1]):T[m];");`
   Keep `T[m]` (multiplicity-preserving) — verbatim to the engine signature, and the same reason already documented for FROM__T_MANY__ANY_1__ANY_1 at Pure.java:1287-1289.
   Add the matching line to the golden `core/src/test/resources/native-catalog.txt` (next to line 180).

2. `core/src/main/java/com/legend/compiler/spec/FromChecker.java` — extend the query-side channel at lines 92-104. Before that block, walk the source SPINE unwrapping identity nodes and absorbing withMapping:
   ```java
   TypedSpec src = a.args().get(0);
   String queryMapping = null;
   // spine walk: cast(@TabularDataSet) is emission-identity (CastChecker)
   // but a non-TDS TypedCast can still sit between withMapping and from
   for (;;) {
       if (src instanceof TypedCast tc) { src = tc.source(); continue; }
       if (src instanceof TypedNativeCall wm
               && "meta::pure::mapping::withMapping".equals(wm.callee().qualifiedName())
               && wm.args().size() == 2
               && wm.args().get(1) instanceof TypedPackageableRef mr) {
           queryMapping = mr.fullPath();   // engine routing.pure:730
           src = wm.args().get(0);
           continue;
       }
       break;
   }
   ```
   (unwrapping TypedCast must REBUILD the cast around the result if you strip a non-identity cast — simplest correct form: only strip the cast when its target is the TDS nominal, i.e. exactly the CastChecker identity condition; otherwise leave it and look one level down for withMapping, keeping the cast in place around the stripped source.)
   Then, when slotting: if `queryMapping != null` and the `mapping` Optional computed from `refs` is empty, set `mapping = Optional.of(new TypedPackageableRef(queryMapping, <mapping-ref ExprType>))`. The runtime slot stays as-is (here it is empty because `from(testRuntime())` is a helper-CONSTRUCTED runtime, handled by the existing arm at FromChecker.java:48-72). Keep the existing withChainedMappings arm untouched.
   With TypedFrom.mapping() populated, `JsonSourceFrame.fromContext` (JsonSourceFrame.java:158) sets the resolver Context and `StatementExecutor.firstFromMapping` (StatementExecutor.java:803) names the plan — no further downstream change.

3. `core/src/test/java/com/legend/rcorpus/Runner.java:900-902` — widen the discovery shape so the test takes the SEEDED execute path (tryRunNoExecute does not replay seeds):
   `boolean fromShape = (simple.equals("from") || simple.equals("withMapping")) && af.parameters().size() >= 2;`
   This is module/seed discovery (which corpus files constitute the module), the same role the `from` entry already plays — it does not compute any semantics.

**How legend-engine does it** — legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/mapping/mappingExtension.pure:386 — `function <<functionType.NotImplementedFunction>> meta::pure::mapping::withMapping<T|m>(t:T[m], m:meta::pure::mapping::Mapping[1]):T[m] { $t; }` — identity on the value. Its meaning is entirely in routing: legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/store/routing.pure:724-737 pairs withMapping with withChainedMappings in ONE routing rule; for withMapping it takes `pair($params->cast(@Mapping)->toOne(), $runtime)` — i.e. the mapping comes from withMapping, the runtime from the enclosing ->from() (routing.pure:726 asserts `'%s() called without a runtime context! Add a ->from(Runtime) call'` if the state is not already a StoreRoutingStrategy).

**Risk** — FromChecker's spine walk must not swallow a mapping from a SIBLING branch: `concatenate(A->withMapping(M1)->from(rt), B->withMapping(M2)->from(rt))` is the engine's own known-broken case — testMultipleFromWithMapping is marked `<<test.ToFix>>` (testFrom.pure:104) and Runner.java:470 excludes ToFix, so a strict SPINE walk (never a subtree scan) keeps parity and stays out of that trap. Do not implement withMapping by pre-order scanning the whole from() subtree. Tenet-2 trap: do NOT resolve this by teaching EngineTestExecutor to strip withMapping from the AST before typing — the mapping-binding is platform semantics; the only harness-side edit that is legitimate is the discovery predicate in step 3.

**Also unblocks** — testFromWithMappingAndIntermediateFuncCall (same unit). testMultipleFromWithMapping would also compile, but it is <<test.ToFix>> and excluded.

**Falsifier** — If `meta::pure::mapping::withMapping` already existed in the catalog, the typer would have resolved it — grep `withMapping` in core/src/main/java/com/legend/builtin/Pure.java and core/src/test/resources/native-catalog.txt: zero hits in both (done). To falsify the FromChecker half: after registering the native alone, if the test PASSES via tryRunNoExecute, the discovery edit is unnecessary — but seeds are not replayed on that path, so it would fail on rows instead; run with LL_TMP_DEBUG=1 and read the `[try-run]` line.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1452 — `if (candidates.isEmpty()) throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog (unported platform function, or a misspelling)")` — verbatim the observed wall.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1293 — WITH_CHAINED_MAPPINGS is registered; a grep of Pure.java for `withMapping` returns nothing.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/resources/native-catalog.txt:180 — golden catalog contains withChainedMappings only.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/FromChecker.java:92-104 — the existing query-side chain arm: `if (src instanceof TypedNativeCall wc && "meta::pure::mapping::withChainedMappings".equals(wc.callee().qualifiedName()) && wc.args().size() == 2) { collectMappingRefs(wc.args().get(1), chainMappings); src = wc.args().get(0); }`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:900-902 — `boolean fromShape = simple.equals("from") && af.parameters().size() >= 2;` — the only non-execute mapping-ref shape; no withMapping.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1287-1315 — empty mappingRefs ⇒ tryRunNoExecute ⇒ `SHAPE, "no execute(|...) call [calls …] — wall: " + attempted.wall()`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/JsonSourceFrame.java:158-163 — `if (fr.mapping().isPresent()) return new Context(fr.mapping().get().fullPath(), …)` — TypedFrom.mapping IS the resolver's mapping context.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1383-1394 vs 1132-1146 — the normal run path calls `replaySeeds(...)` before EngineTestExecutor.run; tryRunNoExecute does NOT, so a try-run-only pass would query unseeded tables.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testFrom.pure:74-83 — the test body: `project(...)->withMapping(MappingWithAssociation)->from(testRuntime())`, mapping arg of execute is the `^Mapping(name='')` sentinel.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testFrom.pure:169 — `Mapping meta::relational::tests::fromMapping::MappingWithAssociation` is defined in the test's OWN file, so the module already contains it once the execute path is taken.

</details>

---

## `testFromWithMappingAndIntermediateFuncCall`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XS |
| confidence | medium |

**Root cause**

Identical to testFromWithMapping: `meta::pure::mapping::withMapping` is not in the native catalog, so Typer.checkGeneric throws at Typer.java:1446-1452, and Runner.executeMappingRefs finds no mapping ref (execute's arg1 is `^Mapping(name='')`, from's arg1 is `testRuntime()`), producing the SHAPE + wall outcome via Runner.java:1287-1315. The ONE additional wrinkle is the intermediate `->cast(@TabularDataSet)` between withMapping and from: `CastChecker.check` (core/src/main/java/com/legend/compiler/spec/CastChecker.java:33-41) returns `a.args().get(0)` UNCHANGED when the target is the TabularDataSet nominal and the source is a RelationType, so the cast normally vanishes and FromChecker's direct-source peek would see the withMapping call. That identity arm requires the target to be a `Type.GenericType` with rawFqn TabularDataSet; Typer.java:2817-2827 produces exactly that for the bare name `TabularDataSet` only as a FALLBACK after `ctx.findType(name)` misses, and findType is FQN-keyed (TypeClassifier.java:32-54: `Pure.findNativeClass(fqn)`), so a bare `@TabularDataSet` under testFrom.pure's imports (which do not include meta::pure::tds::*) misses and takes the fallback. Hence the cast is expected to be transparent — but the fix should not depend on it.

**Fix**

Same three edits as testFromWithMapping. The only test-2-specific requirement is that FromChecker's source walk tolerate an intervening cast: strip a `TypedCast` whose target is the TabularDataSet nominal (the same condition CastChecker.java:33-41 uses) while hunting for the withMapping node, and re-wrap any cast you strip that is NOT that identity form. Concretely, in FromChecker.check replace the single `src instanceof TypedNativeCall wc` peek with the loop sketched in the testFromWithMapping fix, and slot the collected mapping FQN into the `mapping` Optional of the constructed TypedFrom.

**How legend-engine does it** — legend-engine .../core/pure/mapping/mappingExtension.pure:386 (withMapping = identity, T[m]) and .../core/pure/router/store/routing.pure:724-737 (the routing rule that takes the mapping from withMapping and the runtime from the enclosing from). Routing descends through the intervening cast because the rule fires on the FunctionExpression wherever it sits under the from wrapper.

**Risk** — If the cast is stripped unconditionally, a genuine narrowing cast (`->cast(@SomeClass)`) between a withMapping and a from would be silently dropped and the query would lose a type assertion. Guard the strip on the TDS-nominal condition only. Same tenet-2 note as test 1: do not pre-strip withMapping in EngineTestExecutor.

**Also unblocks** — testFromWithMapping

**Falsifier** — If `@TabularDataSet` in this file resolves to a `Type.ClassType` rather than the GenericType, CastChecker's identity arm does not fire and a TypedCast wraps the withMapping call — in which case a direct-source-only peek (without the cast-walk) fixes test 1 but leaves test 2 walled. Cheapest observation: after implementing, run only testFromWithMappingAndIntermediateFuncCall; if test 1 passes and test 2 still walls, the cast is surviving and the spine walk is required.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/CastChecker.java:33-41 — `if (ref.target() instanceof Type.GenericType g && PlatformTypes.TABULAR_DATA_SET.equals(g.rawFqn()) && a.args().get(0).info().type() instanceof Type.RelationType) { return a.args().get(0); }` — cast(@TabularDataSet) over a relation is emission-identity.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2813-2827 — exact-FQN TabularDataSet ⇒ GenericType; the bare name reaches it only via the `.or(() -> "TabularDataSet".equals(name) ? …GenericType… )` fallback after ctx.findType misses.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:32-54 — findType is FQN-keyed (`Pure.findNativeClass(fqn)`), so a bare simple name does not resolve to the native TDS class.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:96-98 and 966-968 — TabularDataSet is the schema-erasing nominal over any relation-shaped actual, so the withMapping `T[m]` signature binds T to the project's RelationType and the result stays relation-shaped.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testFrom.pure:86-99 — the test body, identical to testFromWithMapping plus `->cast(@TabularDataSet)` between withMapping and from; the asserted SQL is character-identical to testFromWithMapping's.

</details>

---

## `testObjectReferneceInWithMilestonedRootClass`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | medium |

**Root cause**

`generateObjectReferences` IS supported — as a host-side let-RHS fold in `core/src/main/java/com/legend/harness/ObjectRefs.java` (reached via `ConnEquality.letFold` at ConnEquality.java:77, called from EngineTestExecutor.java:418-421). But `ObjectRefs.build` opens with `if (!(rhs instanceof AppliedFunction af)) return null;` (ObjectRefs.java:37-39). This test binds a COLLECTION of two calls — `let productObjectRef = [generateObjectReferences(...), generateObjectReferences(...)]` (testObjectReferenceIn.pure:131-132) — which parses to a `PureCollection`, not an `AppliedFunction`. The recognizer therefore declines, `letFold` returns null, and the let falls through EngineTestExecutor's remaining arms to `lets.put(name, purifiedSetup(rhs, ctx))` (EngineTestExecutor.java:461), binding the RAW calls. When `$productObjectRef` is substituted into the execute'd query, the un-folded `generateObjectReferences` call reaches the platform typer, which has no such native (it is defined in legend-engine's core pure, outside the corpus root, and is not in Pure.java) — Typer.java:1446-1452 throws the observed 'unknown function' error. Every other objectReferenceIn test in this file passes, including testObjectReferenceInWithMilestonedProperty, which uses the SAME function against the SAME milestoningmap/Product set with the SAME two-key pkMap (name,id) and the same graphFetch/serialize shape — its only difference is a single (unbracketed) generateObjectReferences call. That isolates the collection wrapper as the discriminator.

**Fix**

`core/src/main/java/com/legend/harness/ObjectRefs.java` — make `build` accept a collection of calls, emitting ONE merged JSON array (which is exactly what the consumer at Substitution.java:1477-1501 expects). Split the current method:
  - Extract the existing body (lines 37-76) into `private static @Nullable List<String> refsOf(ValueSpecification rhs, ModelContext ctx)` that returns the list of `"ASOR:<base64>"` strings for a single AppliedFunction (or null if not the shape).
  - New `build`:
    ```java
    static @Nullable ValueSpecification build(ValueSpecification rhs, ModelContext ctx) {
        List<ValueSpecification> calls = rhs instanceof PureCollection pc ? pc.values() : List.of(rhs);
        if (calls.isEmpty()) { return null; }
        List<String> all = new ArrayList<>();
        for (ValueSpecification c : calls) {
            List<String> r = refsOf(c, ctx);
            if (r == null) { return null; }   // any non-matching element ⇒ not this shape, wall stays loud
            all.addAll(r);
        }
        return new CString("[" + all.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]");
    }
    ```
  Keep the all-or-nothing rule: if ANY element is not a generateObjectReferences call, return null so the unknown-function wall stays (the class javadoc's stated contract, ObjectRefs.java:28-29).
  Optionally (defence in depth, not required by this test) make `Substitution.objectReferenceInRewrite` flatten TypedCollection elements that are themselves JSON-array TypedCStrings — one line in the loop at Substitution.java:1512-1531 — so a String[2]-of-arrays shape also decodes.

**How legend-engine does it** — legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/legend/objectReference/objectReference.pure:20-22 and 30-41 — `generateObjectReferences(...):String[1]` delegates to generateVersionedObjectReferences, which ends `$pkMaps->map(pkMap | $versionedFunc->eval(...))->toJSON()`, i.e. ONE String that is a JSON array of references. A `[gOR(a), gOR(b)]` literal is therefore a String[2] of two JSON arrays, and engine's objectReferenceIn takes `Any[*]` and decodes all of them.

**Risk** — Merging two String[1] arrays into one String[1] array is a small deviation from the engine's String[2]; it is safe here because the producer and the consumer are BOTH ours (ObjectRefs.build ↔ Substitution.objectReferenceInRewrite, and the comment at ObjectRefs.java:19-29 already claims that ownership). If a test ever asserts on the bound variable's multiplicity or prints it, the merged form would read differently — no such test exists in this corpus that I found. Tenet-2 note: this is NOT harness compensation for a platform gap — the platform side (Substitution's objectReferenceIn → pk predicate rewrite) is fully implemented and passing on the sibling tests; only the host-fold recognizer's input shape is too narrow.

**Falsifier** — The single cheapest disproof: if the wall is NOT from the collection wrapper, then unbracketing the two calls (binding just one) would still wall — but the sibling testObjectReferenceInWithMilestonedProperty, which does exactly that against the same mapping and pkMap shape, passes. Second falsifier for the 'does the fix make it PASS' claim (not for the root cause): after the fix the test may reach a NEW wall in the milestoned-ROOT graphFetch path (`Product.all(%2015-10-16)->filter(objectReferenceIn)->graphFetch->serialize`), which the passing sibling exercises only on a milestoned PROPERTY. Run just this test after the change and read whether the outcome moves from ERROR to PASS/FAIL-on-rows; a FAIL-on-rows would be a separate, downstream defect.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ObjectRefs.java:36-44 — `static ValueSpecification build(ValueSpecification rhs, ModelContext ctx) { if (!(rhs instanceof AppliedFunction af)) return null; … if (!forSet && !fn.equals("generateObjectReferences")) return null; }` — a PureCollection RHS is rejected at the first line.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ConnEquality.java:66-78 — letFold's last resort is `return ObjectRefs.build(rhs, ctx);`; null means 'not this shape'.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:416-461 — letFold is tried, then extractStrings, then decodePkMaps, then containsExecute; a plain non-execute RHS ends at `lets.put(name.value(), purifiedSetup(rhs, ctx))`, i.e. the raw call survives into the query.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1452 — the 'unknown function' throw, verbatim the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ObjectRefs.java:63-76 — build's output is a single CString holding a JSON array: `"[" + "\"ASOR:" + base64(ref) + "\"" … + "]"`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:1477-1501 — the consumer: a single TypedCString whose value starts with '[' is JSON-parsed into a TypedCollection of ASOR strings; a collection whose ELEMENTS are themselves JSON arrays would instead hit the per-element base64 decode at Substitution.java:1518-1527 and blow up.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/ObjectRefs.java:279-311 — `pkMaps(...)` already handles `[pair(k,v), pair(k,v)]->newMap()`, so the pk payload of this test parses fine once the outer collection is accepted.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testObjectReferenceIn.pure:131-132 — `let productObjectRef = [generateObjectReferences(...), generateObjectReferences(...)];` (bracketed collection of two).
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testObjectReferenceIn.pure:150-152 — the passing sibling testObjectReferenceInWithMilestonedProperty binds a SINGLE generateObjectReferences with the identical mapping/setId/pkMap shape.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/milestoning/tests/businessDateMilestoningSetUp.pure:1831-1843 — `Table ProductTable( milestoning(business(BUS_FROM=from_z, …)) id Integer PRIMARY KEY, name VARCHAR(200) PRIMARY KEY, …)` — composite pk (id,name) matching the two-key pkMap, so Substitution's OR-of-ANDs path (Substitution.java:1560-1580) with pkColRead('name')/pkColRead('id') is the right shape.

</details>

---

## `testLoadCsv`

| | |
|---|---|
| family | `functions/tests/loadCsvToDbTable` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | medium |

**Root cause**

The corpus itself defines the 3-arg convenience overload `meta::relational::metamodel::execute::loadCsvToDbTable(filePath, table, databaseConnection)` in relationalExtension.pure, whose body is `loadCsvToDbTable($filePath, $table, $databaseConnection, [])` — a call to the 4-arg legend-pure PLATFORM native `loadCsvToDbTable(String[1], Table[1], DatabaseConnection[1], Integer[0..1]):Nil[0]`. legend-lite loads and type-checks the corpus's 3-arg overload (SpecCompiler wraps errors as `in function '<fqn>': …`, SpecCompiler.java:70) but has no registration of the 4-arg native anywhere in Pure.java, so InferenceKernel.resolveOverload sees only the two 3-arg corpus overloads and throws `no overload of '…loadCsvToDbTable' accepts 4 argument(s)` at InferenceKernel.java:787-790. The wall is honest: there is no CSV bulk-load surface in legend-lite at all, and no code-storage/resource-root seam through which a Pure repository path like '/core_relational/relational/functions/tests/loadCsvToDbTable/employees.csv' could be resolved.

**Fix**

Build the surface, in three parts. (1) core/src/main/java/com/legend/builtin/Pure.java, beside EXECUTE_IN_DB (line 1517): `LOAD_CSV_TO_DB_TABLE = signature("native function meta::relational::metamodel::execute::loadCsvToDbTable(filePath:meta::pure::metamodel::type::String[1], table:meta::relational::metamodel::relation::Table[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], numberOfRows:meta::pure::metamodel::type::Integer[0..1]):meta::pure::metamodel::type::Nil[0];")` and register it in the same catalog list as EXECUTE_IN_DB. (2) A resource-root seam: the Pure path is a RepositoryCodeStorage path, so it must be resolved against a configured root, NOT hardcoded. Add a root to the execution configuration (an `ExecEnv`/Compiler option, e.g. `pureResourceRoots:List<Path>`) that the corpus runner sets to `Corpus.RELATIONAL.getParent().getParent()` (= `…/src/main/resources`, so `root + '/core_relational/relational/functions/tests/loadCsvToDbTable/employees.csv'` is the file); fall back to `getResourceAsStream(filePath)` when unset, and wall loudly with the attempted path when the file is not found. Passing a ROOT is configuration (real pure injects RepositoryCodeStorage into the native, LoadCsvToDbTable.java:52-60), not harness compensation. (3) core/src/main/java/com/legend/StatementExecutor.java, beside `executeInDb` (line 3273): a `loadCsvToDbTable` K-native arm that reads the table handle (MetamodelWalk table -> columns + declared SQL types), skips the header row, coerces each field per LoadCsvToDbTable.java:96-118 (Integer/SmallInt/TinyInt -> int, BigInt -> long, Double/Float/Numeric -> double, empty -> NULL, everything else including dates -> string), honours `numberOfRows` as a row LIMIT when present, and issues a batched parameterised INSERT on `env.connection()` — routing through the same RawSqlBoundary recording channel executeInDb uses so the H2 second-target replay stays faithful.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-runtime-java-extension-interpreted-store-relational/src/main/java/org/finos/legend/pure/runtime/java/extension/store/relational/interpreted/natives/LoadCsvToDbTable.java:64 (execute) and :96-118 (per-column-type coercion); signature at legend-pure .../platform_store_relational/functions.pure:28.

**Risk** — Tenet-2 trap, and it is a sharp one here: the tempting shortcut is to have the corpus Runner pre-seed personCsvTable from employees.csv (it already reads the corpus tree and already knows `loadCsvToDbTable` is an effectful-setup marker at Runner.java:2169). That would be harness compensation for a platform native and must not be done — the harness may supply the resource ROOT as configuration, but the CSV read, the type coercion and the INSERT belong in the platform. Secondary risk: DuckDB/H2 date handling — the engine deliberately keeps dates as strings and lets the mapping convert (LoadCsvToDbTable.java:118 comment), so do not 'helpfully' parse date columns during load.

**Falsifier** — Cheapest disproof of the root cause: grep the whole legend-lite tree for `loadCsvToDbTable` — if any native registration exists, the diagnosis is wrong. I ran it: the ONLY hit in the entire main+test source is Runner.java:2169 (an effectful-setup name check). Second falsifier, for the PASS claim: even with the native, the test then needs `execute(|Person.all(), csvMapping, testRuntime(), …)` over a 3-column table plus four assertEquals on `$res.values->at(n).firstName/lastName/age` — probe by hand-seeding personCsvTable with the two employees.csv rows via an executeInDb-shaped test and checking that part passes independently.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/functions/tests/loadCsvToDbTable/testLoadCsv.pure:26 — the test calls `loadCsvToDbTable('/core_relational/relational/functions/tests/loadCsvToDbTable/employees.csv', db->schema('default')->toOne()->table('personCsvTable')->toOne(), $connection)` — 3 args.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/relationalExtension.pure:57-59 — the corpus's own 3-arg overload; its body is `loadCsvToDbTable($filePath, $table, $databaseConnection, [])`, i.e. the 4-arity call that fails.
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/functions.pure:28 — `native function meta::relational::metamodel::execute::loadCsvToDbTable(filePath:String[1], table:Table[1], databaseConnection:DatabaseConnection[1], numberOfRows:Integer[0..1]):Nil[0];` — the missing platform surface.
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-runtime-java-extension-interpreted-store-relational/src/main/java/org/finos/legend/pure/runtime/java/extension/store/relational/interpreted/natives/LoadCsvToDbTable.java:64-76 — the real implementation: read the table's columns and column TYPES, `CsvReader.readCsv(codeStorage, …)` with the header row dropped (`LazyIterate.drop(…, 1)`), coerce per column type (Integer/SmallInt/TinyInt -> Integer, BigInt -> Long, Double/Float/Numeric -> Double, dates and strings left as strings), then `new ExecuteInDb(...).bulkInsertInDb(connection, table, values, …)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:787-790 — `throw new TypeInferenceException("no overload of '" + name + "' accepts " + args.size() + " argument(s)")`, the exact observed text.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:70 — `"in function '" + fn.qualifiedName() + "': " + e.getMessage()`, the observed prefix.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1517 — `EXECUTE_IN_DB__STRING_1__CONN_1__INTEGER_1__INTEGER_1`, the sibling K-phase JDBC native, with the comment 'executeInDb is the 4-arg leaf every corpus wrapper bottoms out at'; there is no loadCsvToDbTable constant anywhere in the file.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:3273-3300 — `static ExecutionResult executeInDb(...)`, the K-phase JDBC arm the new native would sit beside (splitStatements + adaptRaw + Executor.executeRaw).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1506 — `TABLE__SCHEMA_1__STRING_1` and Pure.java:1497 `SCHEMA__DB_1__STRING_1` already exist, so `db->schema('default')->toOne()->table('personCsvTable')->toOne()` already walks to a table handle.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/ConnectionLets.java:15-22 — the `$runtime.connectionStores…->cast(…)` connection-let idiom this test opens with is already supported (connection args are never evaluated).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:2169 — the harness already treats `loadCsvToDbTable` as an effectful-setup marker, so the discovery layer expects this surface to exist.

</details>

---

## `testGroupByWithWindowSubset`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | medium |

**Root cause**

`meta::pure::tds::groupByWithWindowSubset(set, functions, aggValues, ids, subSelectIds, subAggIds)` is defined in legend-engine-core's `core/pure/tds/tds.pure` — OUTSIDE the relational corpus root — and the relational compiler additionally intercepts it with a dedicated processor. legend-lite has neither: `groupByWithWindowSubset` is not in CoreFn (which owns `groupBy` at CoreFn.java:73 and dispatches to GroupByChecker at Typer.java:1182) and has no NativeFunctionDefinition in Pure.java. The call carries lambdas, so it takes Typer.checkWithDeferred, where `functionCandidates` returns empty and the throw at Typer.java:1511-1518 emits exactly `no overload of 'groupByWithWindowSubset' matches 6 argument(s) of these shapes (no candidates at all)` — the '(no candidates at all)' branch confirms zero registration, not a shape mismatch. This is purely a missing desugar: legend-lite ALREADY has the 4-arg legacy TDS groupBy desugar (`GroupByChecker.legacyToModern`, which turns `groupBy(src,[keyFns],[agg(map,agg)…],['aliases'])` into the modern colspec form), and the sibling contractmoneyscenario tests (test1/test2) that use the identical `usdRate`/`usdValueNoMap` qualifier lambdas and mapping already pass.

**Fix**

A pure G-phase desugar mirroring processObjectGroupByWithWindowSubSet — no new native, no lowering change. (1) core/src/main/java/com/legend/compiler/spec/CoreFn.java:73: `GROUP_BY("groupBy", "groupByWithWindowSubset")`. (2) core/src/main/java/com/legend/compiler/spec/GroupByChecker.java, at the top of `check` (before the arity-4 branch at line 45): `if (af.parameters().size() == 6) { return check(t, windowSubsetToLegacy(af), env); }`. (3) New private `windowSubsetToLegacy(AppliedFunction af)`: read `functions = asCollection(ps.get(1))`, `aggs = asCollection(ps.get(2))`, `allIds`, `subSelectIds = asCollection(ps.get(4))`, `subAggIds = asCollection(ps.get(5))` as string literals; enforce the three engine preconditions with loud TypeInferenceExceptions carrying the engine's own messages ('SubAggIds and Ids should not have an intersection', 'SubAggIds must be a subset of ids'); build `newFunctions = subSelectIds.map(i -> functions.get(allIds.indexOf(i)))` and `newAggs = subAggIds.map(i -> aggs.get(allIds.indexOf(i) - functions.size()))` — ORDER FOLLOWS subSelectIds/subAggIds, not the original order; return `new AppliedFunction("groupBy", List.of(ps.get(0), new PureCollection(newFunctions), new PureCollection(newAggs), new PureCollection(concat(subSelectIds, subAggIds))))`, which the existing arity-4 `legacyToModern` then handles unchanged. IMPORTANT: follow pureToSQLQuery.pure:879, NOT tds.pure:867 — the tds.pure body sorts the subset functions back into ORIGINAL order while labelling them with subSelectIds order, which would mislabel results3's Rate/Amount columns; the relational processor's index-mapped order is what the corpus goldens assert.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:879 — processObjectGroupByWithWindowSubSet, the authoritative relational desugar (index-mapped, subSelectIds/subAggIds order preserved).

**Risk** — Aliasing `groupByWithWindowSubset` onto CoreFn.GROUP_BY means GroupByChecker now owns the name for ALL arities; make the 6-arity branch explicit and let anything else fall through to `t.checkGeneric` (which will wall honestly) rather than silently mis-desugaring. Second risk: the two later asserts use `assertEqualsH2Compatible` on golden SQL with H2-specific date rendering (`"fx_0".date = DATE'2003-10-10'`); a passing desugar can still leave a GOLDEN_TEXT_ONLY residue there — if that happens, reclassify the residue, do not paper over it in the H2/DuckDB text comparator.

**Also unblocks** — Nothing else in this corpus — testGroupWithWindowSubset.pure is the only relational-corpus user of groupByWithWindowSubset (the other users are in legend-engine-core's testGroupBy.pure, outside the corpus root).

**Falsifier** — Root cause is falsified only if `groupByWithWindowSubset` turns out to be registered somewhere — grep Pure.java and CoreFn.java; I read both and it is absent, and the '(no candidates at all)' suffix in the observed message independently proves `functionCandidates` returned empty. For the PASS claim the cheapest falsifier is the first assert alone: after the desugar, run only `results` (subSelectIds ['Amount'], subAggIds ['Amount-Sum']) and compare `sqlRemoveFormatting()` to `select "root".price as "Amount", sum("root".price) as "Amount-Sum" from Contract as "root" group by "Amount"` — if the group-by clause or alias quoting differs, the remaining gap is golden-text, not the desugar.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/functions/tests/projection/testGroupWithWindowSubset.pure:25-40 — the 6-arg call: functions, aggs, allIds ['Contract ID','Amount','Rate','Value','Amount-Sum','Rate-Average'], subSelectIds ['Amount'], subAggIds ['Amount-Sum'].
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:867 — `function meta::pure::tds::groupByWithWindowSubset<K,V,U>(set, functions, aggValues, ids, subSelectIds, subAggIds):TabularDataSet[1]`, reducing to `meta::pure::tds::groupBy($set, $subSetfunctions, $newAggValues, $newIds)`. NOT under the corpus root.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:879-915 — `processObjectGroupByWithWindowSubSet`, the RELATIONAL desugar that is actually used for execute: `indexOfFunctions = $ids->map(i|$allIds->indexOf($i)); newFunctions = $indexOfFunctions->map(i|$functions->at($i))`, `indexOfSubAggs = $subAggIds->map(i|$allIds->indexOf($i)); newAggs = $indexOfSubAggs->map(i|$aggs->at($i - $functions->size()))`, new ids = `concatenate($ids,$subAggIds)`, then `processObjectGroupBy($newF, …)`. Note the ORDER follows subSelectIds/subAggIds, which is what results3 asserts ('Rate | Amount | Rate-Average | Amount-Sum').
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:892-894 — the three preconditions: subAggIds must not intersect subSelectIds; every subAggId must be in `allIds->slice(functions->size(), allIds->size())`; every subSelectId must be in allIds.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:10261 — `^PureFunctionToRelationalFunctionPair(first=groupByWithWindowSubset_K_MANY__…, second=processObjectGroupByWithWindowSubSet_…)`, proving the relational path never runs the tds.pure body.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1511-1518 — the emitting site, including the `(no candidates at all)` branch when `candidates.isEmpty()`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GroupByChecker.java:44-51 — `check` already routes arity-4 through `legacyToModern` and arity-3 TDS-legacy through `tdsLegacyToModern`; the desugar target already exists.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GroupByChecker.java:63-104 — `legacyToModern(src, [keyFns], [agg(map,agg)…], [aliases])`, including the alias-count check and the `agg(mapFn, aggFn)` shape check the new desugar should feed.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/CoreFn.java:73 — `GROUP_BY("groupBy")`; the enum doc at CoreFn.java:26-29 states a CoreFn owns every overload of its name and aliases are listed in the constructor (e.g. SELECT("select","newTDSRelationAccessor") at CoreFn.java:57).
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/tests/advanced/testContractMoneyScenario.pure:29-52 — test1 projects the SAME four lambdas over the SAME ContractMoney mapping; test1/test2 do not appear in docs/RELATIONAL_CORPUS_ALL.md's failure list (the tests/advanced failures at lines 1422-1433 do not include them), so the model, qualifiers and joins already work in legend-lite.

</details>

---

## `testReplaceTablePostProcessorWithSubQueries`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

Two stacked causes. (a) The wall you see is a TYPER defect: legend-lite registers `meta::relational::extension::relationalExtensions()` as a native returning `meta::pure::metamodel::type::Any[*]` (Pure.java:1582), while the corpus function `meta::relational::postProcessor::nonExecutable(selectSQLQuery:SelectSQLQuery[1], extensions:Extension[*])` — and legend-lite's own native mirror of it (Pure.java:1505) — declare the 2nd parameter as `meta::pure::extension::Extension[*]`. InferenceKernel.paramTypeScore's ClassType arm scores formal=Extension / actual=Any as -1 (Any is not a subtype of Extension; the score-0 shortcut fires only when the FORMAL is Any, InferenceKernel.java:960-966,975-978). Both arity-2 candidates therefore score -1, `winners` is empty, and resolveOverload throws the exact 'structurally matches the argument types' message. There ARE two candidates because the corpus's own postprocessor/nonExecutablePostProcessor.pure is a function-only parent file and RelationalCorpusRunner.registerFamily pulls it into the `postprocessor/tests` family model (RelationalCorpusRunner.java:704-717). (b) Behind that, the feature itself is absent: SqlPostProcessors.readHook accepts exactly ONE hook body shape — a terminal `meta::relational::postProcessor::replaceTables` call — and throws NotImplementedException for anything else, including the `sqlQueryPostProcessors` plain slot this test uses (SqlPostProcessors.java:60-98). So fixing (a) only advances the wall to (b).

**Fix**

Two changes, in this order. (1) TYPING (XS): in core/src/main/java/com/legend/builtin/Pure.java:1582 change RELATIONAL_EXTENSIONS__ANY_MANY's return type from `meta::pure::metamodel::type::Any[*]` to `meta::pure::extension::Extension[*]` — that class already exists in the native catalog (Pure.java:247, `native Class meta::pure::extension::Extension {}`) and is exactly what the real engine declares (extensions/extension.pure:62). Rename the constant to RELATIONAL_EXTENSIONS__EXTENSION_MANY and update core/src/test/resources/native-catalog.txt. Call sites that pass it into `Any[*]` parameters are unaffected (formal=Any short-circuits to score 0). (2) FEATURE (M): teach SqlPostProcessors a second recognized hook shape. Add to readHook a branch for a terminal TypedNativeCall/TypedUserCall on `meta::relational::postProcessor::nonExecutable` with 2 args whose first is the hook's own lambda parameter; record it as a boolean `nonExecutable` flag on the same channel that today carries tableReplaceMap (extend the record to a small Cfg carrying {renames, nonExecutable}), thread it through StatementExecutor.java:2211-2222 the way tableReplaceMap already is, and in SqlPostProcessors.apply add an IR pass that walks the SqlQuery tree and ANDs the constant predicate `1 = 2` into the WHERE of EVERY SqlSelect node (root select and every nested sub-select/derived-table), leaving plain table sources untouched. That is precisely what the engine's Pure implementation does (nonExecutablePostProcessor.pure:36-46: build ^DynaFunction(name='equal', parameters=[^Literal(1),^Literal(2)]) and set filteringOperation = that, or andFilters(that, existing), recursing into Union / CommonTableExpression / Alias-wrapped SelectSQLQuery / RootJoinTreeNode), and it matches the golden SQL in the test, which shows `and 1 = 2` on the sub-select and on the outer select but not on plain-table joins.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/nonExecutablePostProcessor.pure:36-46 (the `1 = 2` DynaFunction injected into every SelectSQLQuery's filteringOperation) and .../relational/extensions/extension.pure:62 (`function meta::relational::extension::relationalExtensions() : meta::pure::extension::Extension[*]`)

**Risk** — Fix (1) changes overload SCORING wherever both an `Extension[*]` and an `Any[*]` overload exist for the same name — the Extension one will now win. That is the correct engine behaviour but could shift dispatch for execute/toSQLString/from families; re-sweep the whole corpus, not just this family. Fix (2) is the tenet-2 trap: do NOT special-case this in the harness or in the corpus source. `1 = 2` injection is a SQL-IR concern and belongs in com.legend.lowering.SqlPostProcessors next to tableReplaceMap, with the recognizer staying LOUD for any third hook shape (the existing comment in SqlPostProcessors.java:63-75 records a user ruling that silently skipping hooks produced false greens).

**Also unblocks** — Fix (1) alone may unblock other tests that pass relationalExtensions() into corpus functions declaring Extension[*] parameters (e.g. the pureToSqlQuery::andFilters family). Fix (2) is specific to the two nonExecutable post-processor tests (this one and its Snowflake/SybaseIQ mirrors, which are outside this corpus).

**Falsifier** — Set only fix (1) and re-run the test. If the new failure message is `sqlQueryPostProcessorsConnectionAware hook shape is not a replaceTables lambda — post-processor recognizer pending for: …`, the two-layer diagnosis is confirmed. If instead it fails somewhere else entirely (e.g. still in the typer), layer (a) is misdiagnosed.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1582 — `native function meta::relational::extension::relationalExtensions():meta::pure::metamodel::type::Any[*];` with a comment saying the corpus's own definition is signature-broken so this shim exists 'for TYPING the context argument'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1505 — NON_EXECUTABLE_PP declares `extensions:meta::pure::extension::Extension[*]`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:809-822 — `if (winners.isEmpty()) { … throw new TypeInferenceException("no overload of '" + name + "' structurally matches the argument types (" …)`, reachable only when arityMatches.size() > 1
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:960-966 — `case Type.ClassType c when c.fqn().equals(ANY_FQN) -> 0;` fires only for the FORMAL; actual=Any against formal=Extension falls to the ClassType arm and yields -1
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:85-98 — readHook: 'the ONLY recognized body is a terminal replaceTables($query, <pairs>) call'; anything else throws NotImplementedException('…post-processor recognizer pending for: ' + hook)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:63-75 — the plain `sqlQueryPostProcessors` slot is deliberately routed through the same LOUD readHook (a documented user ruling against catch-and-skip)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:704-717 — funcOnly parent files (no Class/Database/Enum/Association/Mapping lines) are prepended to the family sources; nonExecutablePostProcessor.pure qualifies
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/nonExecutablePostProcessor.pure:24 — the corpus function signature, `extensions:Extension[*]`
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/postprocessor/tests/testPostProcessor.pure:141 — the hook is `sqlQueryPostProcessors = [{query:SelectSQLQuery[1] | nonExecutable($query, relationalExtensions()) }]`

</details>

---

## `testCompositionInMultiStatementPureExpressions`

| | |
|---|---|
| family | `router/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | medium |

**Root cause**

The reported wall is STALE. It came from the 2026-08-14 burndown sweep (docs/burndown-2026-08-14/master-classification.csv:258, docs/RELATIONAL_CORPUS_ALL.md:1365, last regenerated 2026-08-09). The cause it recorded — Typer.isFunctionTyped rejecting a `FunctionDefinition<Any>` parameter, so deferredShapesMatch filtered out the only routeInternal candidate and checkWithDeferred threw 'matches 1 argument(s) of these shapes' — was diagnosed in docs/CORPUS_BURNDOWN_INDEX.md:329 and FIXED on 2026-08-15 by commit 787c391b, which added the FUNCTION_CARRIER_FQNS clause to isFunctionTyped. In the current worktree the call type-checks. What the test actually asserts is legend-engine's ROUTER printer output: `routeInternal` is a corpus wrapper over `meta::pure::router::routeFunction`, and the assertion compares `$routingResult->map(f|$f->meta::pure::router::printer::asString())` against the literal `'x:Integer[1] | {Platform> [strategy_wrapper /let y = [$x, 1] -> plus()]};…'`. Neither `routeFunction` nor `asString` exists in legend-lite's catalog (grep of core/src/main/java/com/legend/builtin/Pure.java returns nothing for either), and the sibling routing tests confirm the shape of the real wall: 'no overload of routeFunction matches 4 argument(s) of these shapes (no candidates at all)'. The `{Platform> …}` / `strategy_wrapper` notation is legend-engine's own router IR pretty-printer — a Pure-implemented compiler internal with no legend-lite counterpart.

**Fix**

Do not fix; ledger it. First, re-sweep this test to refresh the stale wall — the recorded typer error no longer exists in HEAD. Then adjudicate it BLOCKED-ON-ENGINE-INTERNALS alongside the other router/tests entries (testPlatformExpressionDependencyOnAFromExpression, testPlatformExpressionDependencyOnAFromExpression2, testRoutingOfSimpleQualifiedProperty), which already fail on the absent `meta::pure::router::routeFunction`. Passing it would require legend-lite to reproduce legend-engine's router IR node-for-node AND its printer's exact text ('{Platform> [strategy_wrapper /…]}'), which is a white-box assertion on a Pure-implemented compiler stage legend-lite does not have and should not grow to satisfy a golden string. If the router surface is ever wanted, `routeFunction` and `meta::pure::router::printer::asString` would go in com/legend/builtin/Pure.java plus a new router package — but that is a platform decision, not a bug fix.

**Risk** — The one trap here is 'fixing' this by teaching the harness to skip or vacuously pass router-printer asserts. That is harness compensation for a platform gap — a loud wall is the correct outcome.

**Also unblocks** — Nothing. Its three router/tests siblings are blocked on the same absent routeFunction surface but are already failing for that reason directly.

**Falsifier** — Re-run this single test against HEAD. If the wall is still 'no overload of routeInternal matches 1 argument(s) of these shapes', then commit 787c391b did not in fact cover FunctionDefinition<Any> in this position and the staleness claim is wrong. Expected new wall: a routeFunction 'no candidates at all' error (or a NotImplementedException from tryRunNoExecute).

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1727-1738 — isFunctionTyped now returns true for any GenericType whose rawFqn is in InferenceKernel.FUNCTION_CARRIER_FQNS
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:1180-1185 — FUNCTION_CARRIER_FQNS contains 'meta::pure::metamodel::function::FunctionDefinition'
- git show 787c391b (2026-08-15 18:58, 'Cluster 2 (XS): the FunctionDefinition carrier family counts as function-typed in Typer.isFunctionTyped') — the diff adds exactly that clause
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/CORPUS_BURNDOWN_INDEX.md:329 — 'isFunctionTyped rejects FunctionDefinition<Any> … testCompositionInMultiStatementPureExpressions … very high'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1363-1367 — the sibling router tests fail with 'no overload of routeFunction … (no candidates at all)'
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/router/tests/testRouting.pure:341-344 — routeInternal's body is `routeFunction($f, ^ExecutionContext(), relationalExtensions(), noDebug())`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/router/tests/testRouting.pure:484-485 — the assertion is on `$routingResult->map(f|$f->asString())`, the router printer's `{Platform> [strategy_wrapper /…]}` text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1505-1519 — the 'matches N argument(s) of these shapes' throw site in checkWithDeferred, gated on deferredShapesMatch

</details>

---

## `testJoinFunc`

| | |
|---|---|
| family | `tds/relation` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

Identical mechanism to testJoinUsing, one file down. The body is `test({|TDS project->join(tds, JoinType.INNER, {x,y|$x.getInteger('int1') == $y.getInteger('int2')})}, {|Relation project->join(rel, JoinKind.INNER, {x,y|$x.int1 == $y.int2})}, relationalExtensions())` — a protocol-JSON equality assert over legend-engine's Pure-implemented TDS->Relation AST transformer (third pair in `tdsToRelationExtension`, the Function-valued join-condition arm). No execute call, so Runner falls to tryRunNoExecute, and the bare `TestClass` (declared only in legend-engine-core, outside the corpus root) throws at Typer.packageableRef.

**Fix**

Do NOT fix; ledger it with testJoinUsing for the same reason. Same exclusion note.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/relation/tdsToRelation.pure:54 — `meta::pure::tds::toRelation::transform(a:AppliedFunction[1], extensions)`, the protocol-AST rewriter under test.

**Risk** — Same tenet-2 trap as testJoinUsing.

**Also unblocks** — testJoinUsing

**Falsifier** — Same as testJoinUsing: find a `meta::pure::tds::toRelation::transform` equivalent in legend-lite. There is none.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/relation/testTdsToRelation.pure:42 — `testJoinFunc` body; the third argument is `meta::relational::extension::relationalExtensions()`, matching the harness's `[calls meta::relational::extension]` namespace tag.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/relation/tdsToRelation.pure:47 — the `pair(join_TabularDataSet_1__TabularDataSet_1__JoinType_1__Function_1__TabularDataSet_1_, |… appliedFunction(join_Relation_…, [$left,$right,$joinKind,$func]))` arm this test targets.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2294 — the emitting site of the observed wall.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1312 — `return new Outcome(t.fqn(), Status.SHAPE, "no execute(|...)" + " call" + … + " — wall: " + attempted.wall())`.

</details>

---

## `testJoinUsing`

| | |
|---|---|
| family | `tds/relation` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

This test does not execute a query at all. `meta::pure::tds::toRelation::test(inputs, expected, extensions)` serializes BOTH lambdas to legend-engine's vX_X_X *protocol JSON* and compares the strings: the expected side via `transformLambda(...)->meta::json::toJSON(100)` and the actual side via `meta::pure::tds::toRelation::transform($extensions)`, which rewrites the protocol AppliedFunction tree through the `TdsToRelationExtension_V_X_X` registry. That registry, the transformer, the `test` helper AND the `TestClass` model element all live in legend-engine's own Pure code, not in the corpus tree the harness loads. So legend-lite's harness finds no `execute(|...)` call, falls into `Runner.tryRunNoExecute` (Runner.java:1307), and the very first thing the typer touches — the bare name `TestClass` — is not in the assembled module, so `Typer.packageableRef` throws the ResolutionException at Typer.java:2294-2297. The `TestClass` wall is a corpus-scope symptom; the assert underneath is over legend-engine compiler internals that legend-lite has no counterpart for.

**Fix**

Do NOT fix; ledger it as out-of-scope. The only way to make it green is to port legend-engine's vX_X_X protocol value-specification metamodel, `transformLambda` (Pure-graph -> protocol AST), `meta::json::toJSON`, and the `TdsToRelationExtension_V_X_X` transfer registry — a compiler-internals surface legend-lite deliberately does not have. If the project ever wants the SEMANTIC content of this test (that TDS `join(tds, tds, JoinType, ['leftCols'], ['rightCols'])` means the same as Relation `join(rel, rel, JoinKind, {x,y|…})`), express it as a rows-level test on real data, not as protocol-JSON string equality. Record the exclusion in docs/RELATIONAL_CORPUS_ALL.md next to the existing tds/relation rows (docs/RELATIONAL_CORPUS_ALL.md:1374) with the reason 'asserts engine protocol-AST JSON'.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/tds/relation/tdsToRelation.pure:21 — `tdsToRelationExtension():TdsToRelationExtension_V_X_X[1]` returns the pair-table mapping `join_TabularDataSet_…` protocol functions onto `join_Relation_1__Relation_1__JoinKind_1__Function_1__Relation_1_`; this IS the compiler transformation under test.

**Risk** — Tenet-2 trap: it is tempting to 'fix' this by teaching the harness to load engine-core's `core/pure/tds/relation/*.pure` (the M2M_TESTS precedent at Corpus.java:57 makes it look sanctioned). That would only move the wall from `TestClass` to `transform`/`transformLambda` and add a foreign family to every module assembly. Do not.

**Also unblocks** — testJoinFunc (same file, same mechanism). The other tds/relation tests in the engine-core file are not in this corpus.

**Falsifier** — If legend-lite in fact carries a `meta::pure::tds::toRelation::transform` / protocol-AST `transformLambda` over Pure value specifications, the verdict is wrong. Falsifier: `grep -rn 'toRelation::transform\|assertLambdaJSONEquals\|functionJSON' core/src/main/java` — I ran it and the only hits are `MappingEmitter.transformLambda` (a mapping-transform JSON emitter, unrelated) at MappingEmitter.java:572.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/relation/testTdsToRelation.pure:18 — testJoinUsing's whole body is `test({|TDS join…}, {|Relation join…}, relationalExtensions())`; no execute, no mapping, no runtime.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/relation/testTdsToRelation.pure:433 — `function meta::pure::tds::toRelation::test(inputs, expected, extensions)` body is `$inputs->forAll(input | let transformed = $input->transform($extensions); assertLambdaJSONEquals($expected, $transformed))`.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/relation/testTdsToRelation.pure:441 — `assertLambdaJSONEquals` is `assertEquals($expected->functionJSON(), $actual->meta::json::toJSON(100))`: a JSON *string* comparison of protocol ASTs.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/relation/testTdsToRelation.pure:20 — `Class meta::pure::tds::toRelation::TestClass` is declared HERE, in legend-engine-core, not under the corpus root the runner reads.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/relation/tdsToRelation.pure:33 — `meta::pure::tds::toRelation::transform(l:LambdaFunction<Any>[1], extensions)` calls `meta::protocols::pure::vX_X_X::transformation::fromPureGraph::transformLambda` then rewrites the protocol body.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2294 — the exact literal in the failure detail: `throw new ResolutionException("'" + ref.fullPath() + "' is not a known class, mapping, runtime, connection, or database" ...)`, reached only after class/mapping/runtime/database/function lookups all miss.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1307 — `TryRun attempted = tryRunNoExecute(t, body);` then Runner.java:1312 emits the `no execute(|...) call [calls …] — wall: …` string verbatim.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:49 — the corpus root is exactly `core_relational/relational`; the only foreign tree loaded is `M2M_TESTS` (Corpus.java:57), which does not include `core/pure/tds/relation`.

</details>

---

## `iqrClassifyTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`iqrClassify` is `<<functionType.NormalizeRequiredFunction>>` so legend-lite inlines it (Typer.inlineNormalized), and its private helper `joinWithOptionalColumns(q1,q2,joinType,cols)` (params are TabularDataSet → schema-erased → also inlined) has the body `if($cols->isEmpty(), | $q1->join($q2,$joinType,{x,y|true}), | $q1->join($q2,$joinType,$cols->toOneMany()))`. The test calls `$tds->iqrClassify([], 'score', 'irq_classification')`, so `$cols` (the window) β-substitutes to the literal `[]` and the condition is statically TRUE. But `StaticFold.evalCall` has no `isEmpty` arm (StaticFold.java:241-437 switch: plus, minus, pair, equal, not, in, concatenate, removeDuplicates, removeAll, indexOf, map, filter, sortBy, if, and/or, makeString/joinStrings, elementToPath, toOne/at — nothing else), so `eval(ps.get(0))` returns null, `foldCall`'s `if` arm (StaticFold.java:144-149) does not fire, and BOTH branches survive into the Typer. Pure type-checks both `if` branches, so the DEAD else-branch `join($q1, $q2, JoinKind.LEFT, []->toOneMany())` is checked. Its 4th argument is not a lambda, so `Typer.deferredArg` (Typer.java:1652-1660) is false and it takes the plain `checkGeneric` → `InferenceKernel.resolveOverload` path. Under the bare name `join` there are THREE arity-4 candidates — the native `meta::pure::functions::relation::join<T,V>(Relation,Relation,JoinKind,Function)` (Pure.java:1409) plus the two 4-arg `meta::pure::tds::join` module overloads from the corpus's tds/tds.pure:23 and :88, which RelationalCorpusRunner pulls into the tds/tests model as a function-only parent source (RelationalCorpusRunner.java:705-721). Because `arityMatches.size() > 1`, resolveOverload goes to the scoring loop; every candidate scores -1 (JoinChecker.tdsLegacyToModern already rewrote arg3 from JoinType to JoinKind, which the tds:: overloads reject, and arg4 is a string collection, which the native's Function param rejects), so InferenceKernel.java:810-821 throws exactly the observed 'no overload of meta::pure::functions::relation::join structurally matches the argument types (ExprType[type=RelationType[columns=[Column[name=name, type=STRING…], Column[name=score, type=INTEGER…]…' — arg0 is `$tds` = project(name,score), matching the observed text verbatim.

**Fix**

In `core/src/main/java/com/legend/compiler/spec/StaticFold.java`, add to the `evalCall` switch (alongside the existing `removeDuplicates` / `indexOf` arms):

  case "isEmpty" -> { List<Object> c = ps.size() == 1 ? evalList(ps.get(0), scope) : null; return c == null ? null : c.isEmpty(); }
  case "isNotEmpty" -> { List<Object> c = ps.size() == 1 ? evalList(ps.get(0), scope) : null; return c == null ? null : !c.isEmpty(); }
  case "size" -> { List<Object> c = ps.size() == 1 ? evalList(ps.get(0), scope) : null; return c == null ? null : (long) c.size(); }

Caveat on `evalList`: it wraps a non-list static value into a 1-element list, so `isEmpty` over an evaluable scalar correctly yields false; a NON-static argument still yields null and the `if` stays un-folded (loud, unchanged). Note that `[]` (an empty `PureCollection`) already evals to an empty `List` via StaticFold.java:164-174, so the literal `[]` window folds immediately.

With that, `foldCall`'s existing `if` arm (StaticFold.java:144-149) collapses `joinWithOptionalColumns` to the then-branch `$q1->join($q2, JoinType.LEFT_OUTER, {x,y|true})` and the dead `[]->toOneMany()` join never reaches the Typer. Do NOT 'fix' this by suppressing type-checking of if-branches — Pure checks both; the engine's answer is to fold the condition (see engineReference).

Expect a follow-on requirement (same test, next wall): the surviving branch is a constant-TRUE LEFT join (cross join) against a `groupBy([], [agg…])` with an EMPTY grouping key. Verify both are supported before claiming the test green; the aggregate vocabulary itself is present (Pure.java:1869 `percentile`, Pure.java:2002 `stdDevPopulation`).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/preeval.pure:169-183 — the router's pre-evaluation registers a dedicated handler for `if_Boolean_1__Function_1__Function_1__T_m_`: it pre-evaluates parameter 0 and, when the result `isInstanceValue`, replaces the whole `if` expression with ONLY the taken branch. The comment above it states the exact reason legend-lite needs it: "Avoid handling known 'false' case (which may not be evalulatable / have runtime issues, e.g. a toOne() after an isNotEmpty condition)". The test file itself imports `meta::pure::router::preeval::*` (testTdsExtension.pure:16).

<details><summary>Adversarial review notes (CONFIRMED)</summary>

I tried to refute this and could not. Citations all resolve correctly. StaticFold.evalCall's switch (StaticFold.java:243-436) has exactly the arms listed — I enumerated every `case` in the file: plus, minus, pair, equal, not, in, concatenate, removeDuplicates, removeAll, indexOf, map, filter, sortBy, if, and/or, makeString/joinStrings, elementToPath, toOne/at. No isEmpty/isNotEmpty/size, so eval returns null. foldCall's if-arm at :144-149 requires `eval(ps.get(0), scope) instanceof Boolean`, so with null both LambdaFunction branches survive into the rebuilt AST. Typer.java:1228-1237 requiresNormalization is verbatim as cited and isSchemaErased (:1253) matches TabularDataSet, so joinWithOptionalColumns is inlined; inlineNormalized (:1274-1305) does a REAL AST β-substitution (SourceSubst.substitute) and then StaticFold.fold, so `$cols` is physically replaced by the caller's `[]`. I checked the parser: `[]` yields `new PureCollection(values)` with an empty list (SpecParser.java:1015-1019), and StaticFold.eval's PureCollection arm (:164-174) evaluates it to an empty java List — so the proposed isEmpty arm would fold to TRUE, exactly as claimed, and evalList (:474-485) behaves as the caveat describes.

The strongest attack — 'maybe the THEN branch is the one that throws' — fails on message shape. deferredArg (Typer.java:1652-1660) is verbatim as cited: lambdas and colspecs only. The then-branch's 4th arg `{x,y|true}` is a LambdaFunction, so it routes to checkWithDeferred, whose failure text is 'no overload of X matches N argument(s) of these shapes' (Typer.java:1511-1518) — a DIFFERENT string. The observed text is the resolveOverload structural throw (InferenceKernel.java:810-821, verified verbatim), which is only reachable when arityMatches.size() > 1 (:791-793 short-circuits a single match to resolveChosen). I grepped Pure.java for arity-4 `join` natives: exactly ONE (relation::join at :1409; meta::legend::lite::join is arity 3, the prefix form is arity 5). So >1 requires module overloads, and tds/tds.pure really does declare two arity-4 meta::pure::tds::join (:23 String[1..*], :88 Function<{TDSRow,TDSRow->Boolean}>) plus an arity-5 at :30. RelationalCorpusRunner.java:698-721 is verbatim as cited, and I confirmed tds.pure qualifies as funcOnly (0 lines starting Class/Database/Enum/Association/Mapping, 3 starting `function `). JoinChecker.tdsLegacyToModern (:165-204) does rewrite arg2 JoinType->JoinKind and, because columnNames() returns null for the AppliedFunction `[]->toOneMany()`, falls to the `out.set(2, joinKind)` tail — so the tds:: overloads can no longer match. Everything lines up.

CAVEAT I could not close: the diagnosis itself flags a follow-on wall (constant-TRUE cross LEFT join against an empty-key groupBy). I found supporting infrastructure (Typer.java:2874 explicitly supports empty-key grouping; percentile at Pure.java:1869-1870 and stdDevPopulation at :2002 exist, with lowering in Scalars.java:859-868), but I could not execute anything, so 'this greens the test' is unproven. The named StaticFold edit itself is XS and contained; getting the test green is not.

</details>

**Risk** — Adding `isEmpty`/`isNotEmpty`/`size` makes MORE `if` conditions foldable across every NormalizeRequired body, so previously-typed branches will start disappearing. That is the intended semantics (it matches preeval), but it changes which wall other tds tests hit, so re-sweep the whole tds/ and functions/ families, not just these two. Tenet-2 trap to avoid: do NOT special-case `joinWithOptionalColumns` in the harness or add a bespoke desugar for it in JoinChecker — the platform owns condition folding, and a name-keyed hack would hide the general gap. Equally, do not 'fix' this by widening `resolveOverload` to tolerate the tds:: join overloads: the dead branch is genuinely untypeable and must never resolve.

**Also unblocks** — zScoreTest (identical mechanism). Any other corpus test routed through `meta::pure::tds::extensions::joinWithOptionalColumns`, and more broadly any NormalizeRequired body whose plan branches on `isEmpty`/`isNotEmpty`/`size` of a substituted literal collection.

**Falsifier** — Set a breakpoint / println in `StaticFold.foldCall`'s `if` arm and in `InferenceKernel.resolveOverload`'s `winners.isEmpty()` throw while compiling iqrClassifyTest. If the throwing call's 4th argument type is a Function (not a string/Nil collection), the failing join is the THEN branch and this diagnosis is wrong. Cheaper still: temporarily add only the `isEmpty` arm and recompile the one test — if the message changes (to a cross-join or empty-groupBy wall) the diagnosis holds; if the identical join message survives, it does not.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 — the `evalCall` switch; I read every arm: there is no `isEmpty`, `isNotEmpty` or `size` case, so `default -> return null`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:144-149 — `foldCall`'s if-fold requires `eval(ps.get(0), scope) instanceof Boolean cond`; a null condition leaves BOTH branch lambdas in the rebuilt AST.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1228-1237 — `requiresNormalization`: the stereotype OR any schema-erased param; `joinWithOptionalColumns(q1:TabularDataSet[1],…)` qualifies via `isSchemaErased`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:791-821 — `if (arityMatches.size() == 1) return resolveChosen(...)`; only with >1 same-arity candidate does the scoring loop run and throw "no overload of '" + name + "' structurally matches the argument types (" + detail + ")".
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1652-1660 — `deferredArg` is true only for LambdaFunction / lambda collections / ColSpec; `[]->toOneMany()` is an AppliedFunction, so the else-branch join takes the plain resolveOverload path (the then-branch, whose 4th arg IS `{x,y|true}`, takes checkWithDeferred and would throw a different message).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1409 — the sole 4-arg native `meta::pure::functions::relation::join<T,V>(rel1,rel2,joinKind:JoinKind[1],f:Function<{T[1],V[1]->Boolean[1]}>[1])`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:705-721 — "FUNCTION-ONLY parent files (tds/tdsExtension.pure, tds/tds.pure) … familySources.add(0, src2)": tds/tds.pure's three `meta::pure::tds::join` overloads are in the tds/tests model.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tds.pure:23 and :88 — two arity-4 `meta::pure::tds::join` overloads (String[1..*] keys, and Function<{TDSRow[1],TDSRow[1]->Boolean[1]}>).
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:172-207 — `iqrClassify` body and the `joinWithOptionalColumns` if/isEmpty helper, verbatim.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/JoinChecker.java:165-204 — `tdsLegacyToModern` rewrites arg3 JoinType→JoinKind even when the 4th arg is not a column-name list (`columnNames` returns null for an AppliedFunction), so the tds:: overloads can no longer match.

</details>

---

## `rowValueDifferenceTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Two-part, but one fix point. (1) `meta::pure::tds::extensions::rowValueDifference` (NormalizeRequired) computes `extendMatchColumns($tds1.columns->filter(c|$c.name->in($columnsToCheck))->sortBy(c|$columnsToCheck->indexOf($c.name)))` (tdsExtension.pure:39, :56). `StaticFold` CAN evaluate that whole chain to a `List<Col>` — but `reify` has no arm for `Col` (StaticFold.java:519-544 handles String/Long/Double/Boolean/Pair/List-of-reifiable only), so `fold` cannot replace it with a literal and the raw AST `<tds1>.columns->filter(c|$c.name->in(['quantity','count']))->sortBy(...)` survives as an argument to `extendMatchColumns`. (2) `Typer.applyGeneric` (Typer.java:1208-1213) calls `checkGeneric(af, env)` — which synths EVERY argument (Typer.java:1437-1440) — and only THEN asks `requiresNormalization(a.chosen())`. So that argument is type-checked standalone even though `inlineNormalized` (Typer.java:1274-1305) will discard the typed args and β-substitute `af.parameters()` raw. Typing it standalone hits the third defect: bare `.columns` on a relation is typed by `Typer.accessProperty` (Typer.java:2465-2467) as `columnsMeta(rt2, false)`, which returns a `TypedCollection` of `TypedCString` with element type `Type.Primitive.STRING` (Typer.java:2334-2345) — i.e. column NAMES, not `TDSColumn[*]`. `filter`'s lambda therefore binds `c : String`, and `$c.name` falls to the default arm of the member switch at Typer.java:2597, throwing verbatim "cannot access 'name' on String". Proof this is the differentiator rather than something in the shared test preamble: the sibling `columnValueDifferenceTest` has the identical `println($rawTradeDate.columns->map(c|$c.name + ':' + …))` preamble and the identical `$tds1.columns->filter(c|$c.name->in(...))->sortBy(...)` chain, but there the chain is immediately consumed by `->map(col|if($col.type == Integer, …))`, which StaticFold's map-UNROLL (StaticFold.java:126-142) expands to literal colspecs so `.columns` never reaches the Typer — and that test gets all the way past typing to a store-resolution wall (docs/RELATIONAL_CORPUS.md:1298). rowValueDifference is the only one that passes the Col collection ACROSS a function boundary.

**Fix**

Primary (small, doctrinally aligned): in `core/src/main/java/com/legend/compiler/spec/Typer.java`, decide normalization BEFORE typing arguments. Change `applyGeneric` (Typer.java:1208-1214) to:

  TypedFunction nr = soleNormalizeRequiredCandidate(af);   // new helper
  if (nr != null) { return inlineNormalized(af, nr, env); }
  Application a = checkGeneric(af, env);
  if (requiresNormalization(a.chosen())) { return inlineNormalized(af, a.chosen(), env); }   // keep as backstop
  return emitCall(a.chosen(), a.args(), a.out());

where `soleNormalizeRequiredCandidate(af)` mirrors the gate already proven in `rawSchemaErasedExpansion` (Typer.java:1352-1373): take `functionCandidates(af)` filtered to `c.parameters().size() == af.parameters().size()`; return null if ANY of them `isNative()`; return the single element of the sublist filtered by `requiresNormalization`, or null if that sublist is not exactly 1. `inlineNormalized` already takes `(af, chosen, env)` and uses only raw parameters, so nothing else changes. This makes `extendMatchColumns`'s schema argument never type standalone; inside the inlined body `$diffCols->map(col|if($col.type == Integer, …))` and `$diffCols.name->map(name|…)` (tdsExtension.pure:71-93) both re-derive the Cols by static eval and unroll to literals.

Secondary (the deeper correctness bug, do it separately and behind its own sweep): bare `.columns` on a relation should not be column-name Strings. Real pure declares `TabularDataSet.columns : TDSColumn[*]` (engine tds.pure:18-23). Either type `columnsMeta(rt2,false)` elements as `meta::pure::tds::TDSColumn` with `.name`/`.type` served by the existing static folds, or — cheaper and safer — leave Typer.java:2465-2467 alone and instead teach `StaticFold.reify` to emit a lite-internal column-fact marker for `Col` (e.g. `AppliedFunction("meta::legend::lite::tdsColumnFact", [CString(name), CString(typeSimpleName)])`) with a matching `evalCall` arm that reads it back, so a Col in fold scope survives β-substitution into any helper. Do NOT change columnsMeta's String typing without auditing every `assertEquals([...names...], $x.columns)` / `assertSize($x.columns, n)` consumer in the corpus.

Honest expectation: this removes the G-phase wall only. rowValueDifferenceTest is a relational execute test whose sibling columnValueDifferenceTest — same query family, same groupBy/adjust/extend pipeline — already clears typing and dies at 'store resolution left getAll(Trade) unresolved'. Expect rowValueDifferenceTest to land on that same resolver wall next, not to go green.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:18-23 — `Class meta::pure::tds::TabularDataSet { columns : TDSColumn[*]; columnByName(s:String[1]){$this.columns->filter(c|$c.name == $s)->first()}:TDSColumn[0..1]; rows : TDSRow[*]; }`. The engine's own `columnByName` is the same `columns->filter(c|$c.name…)` shape legend-lite rejects, which is why `.columns` must not be a String collection. TDSColumn's `name : String[1]` and `type : Type[0..1]` are at tds.pure:25-34.

**Risk** — The `soleNormalizeRequiredCandidate` gate deliberately declines when several same-arity NormalizeRequired overloads exist — e.g. `meta::pure::tds::join` has TWO arity-4 overloads (tds.pure:23 and :88), both schema-erased. Those keep the current (arg-typing) path, so the change cannot silently pick the wrong overload; verify by asserting the gate returns null for `join`. Excluding candidates when any native of that arity exists is essential (already the rule in rawSchemaErasedExpansion) or the native `join`/`project` paths would be hijacked. Tenet-2 trap: do NOT make `Typer.accessProperty` special-case the literal shape `.columns->filter(c|$c.name->in(...))`, and do NOT teach the harness to pre-rewrite tdsExtension.pure — the platform owns both the normalization order and the TDSColumn type.

**Also unblocks** — Any corpus test whose NormalizeRequired body passes a `.columns`-derived collection to another schema-erased helper. The pre-typing gate also removes wasted work (and spurious walls) for every NormalizeRequired call site, so re-sweep the tds/ and functions/ families for message changes.

**Falsifier** — Instrument `Typer.accessProperty` to print the receiver type and enclosing call when it throws "cannot access 'name' on String". If the receiver is NOT the `.columns` read (i.e. `columnsMeta` is not in the stack), or if the enclosing call is not `extendMatchColumns`, this diagnosis is wrong. Cheapest single check: in `applyGeneric`, log the callee name whenever `requiresNormalization(a.chosen())` is true after a successful checkGeneric — `extendMatchColumns` should never appear, because it throws during checkGeneric first.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2465-2467 — `if (ap.property().equals("columns")) { return columnsMeta(rt2, false); }` on a RelationType receiver.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2334-2345 — `columnsMeta` builds `TypedCString` items and types the collection `new ExprType(Type.Primitive.STRING, …)`. This is the String the error names.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2591-2599 — the member switch: `Type.RelationType` handles column reads, and `default -> throw new TypeInferenceException("cannot access '" + ap.property() + "' on " + source.info().type().typeName())` — the exact observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1208-1214 — `applyGeneric`: `Application a = checkGeneric(af, env); if (requiresNormalization(a.chosen())) return inlineNormalized(af, a.chosen(), env);` — args typed first, decision second.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1437-1440 — `checkGeneric` loops `args.add(synth(p, env))` over every parameter before resolving the overload.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1289-1301 — `inlineNormalized` builds its substitution purely from `af.parameters()` (the RAW AST) and folds; it never reads the typed args checkGeneric produced.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:519-544 — `reify` has no `Col` (or `TypeToken`) arm, so a statically-evaluated `List<Col>` cannot become a literal AST and the raw chain survives folding.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:126-142 — `foldCall`'s map UNROLL: this is why columnValueDifference's `.columns->filter(...)->map(col|…)` disappears entirely while rowValueDifference's `.columns->filter(...)->sortBy(...)` (no trailing map at that level) does not.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:39 and :56 — `->extendMatchColumns($tds1.columns->filter(c|$c.name->in($columnsToCheck))->sortBy(c|$columnsToCheck->indexOf($c.name)))`, the Col collection crossing a call boundary.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:112 — columnValueDifference's identical `.columns->filter(c|$c.name->in(...))->sortBy(...)` but consumed by `->map(col|if($col.type == Integer, …))` on the SAME expression, which unrolls.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1298 — columnValueDifferenceTest fails at 'store resolution left getAll(...Trade) unresolved', i.e. it clears G-phase typing entirely; the shape difference above is the only thing separating the two.

</details>

---

## `testExtendDigest_InMemory`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

Identical mechanism to testExtendDigest_Relational, differing only in the source relation. `$tds` is the in-memory `$data->project([col(p|$p.first,'name'), col(p|$p.second,'score')])`; `$tds->extendWithDigestOnColumns('_digest')` inlines the 2-arg overload (tdsExtension.pure:209-212), whose `$input.columns.name->toOneMany()` folds only as far as `toOneMany(['name','score'])` because `StaticFold.evalCall` has no `toOneMany` arm. That un-evaluable `$digestColumns` makes `in` → null → `filter` → null → the `map` UNROLL gate fails, so `$input.columns->filter(c|$c.name->in(...))` reaches the Typer, where bare `.columns` on a relation is a String collection (Typer.java:2465-2467 → columnsMeta, Typer.java:2334-2345) and `$c.name` throws at Typer.java:2597. The harness reports it under the 'no execute(|...) call' prefix simply because this test has no `execute(...)` call — the wall text is the same G-phase failure.

**Fix**

No separate change: the StaticFold vocabulary completion described for testExtendDigest_Relational (add `toOneMany`; widen `toOne` to the 2-arg message spelling; make LambdaFunction a static value that reifies to itself; add `toString`; and make a fold-scope `Col` survive into a schema-erased helper, preferably by calling `typer.rawSchemaErasedExpansion` from `StaticFold.foldCall` and folding the expansion in the same scope) covers both. After that this test's remaining requirement is that MD5 `hash()` over the concatenated per-column strings evaluates on the in-memory/DuckDB path and produces the corpus's expected digests; if `hash` is unimplemented, wall loudly there rather than approximating.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:227-249 — `toStringForColAccessor` is the engine's per-column-type accessor selector: a `[pair(Type, {r|…}), …]->filter(p|$p.first == $col.type).second->toOne('Unsupported column type: …')`. Its shape is what the fold must be able to evaluate; the engine resolves it during preeval (core/pure/router/preeval/preeval.pure:199-203 uses the identical idiom).

**Risk** — Same as testExtendDigest_Relational. Additionally: this test asserts exact MD5 digests of the concatenated string form, so any difference in how legend-lite stringifies an Integer cell ('1' vs '1.0') changes the digest silently. That is a correctness trap — after the fold lands, compare the produced pre-hash string against the engine's `joinStrings('|')` output before trusting a passing/failing digest.

**Also unblocks** — testExtendDigest_Relational — one shared fix.

**Falsifier** — Same probe as testExtendDigest_Relational: add only the `toOneMany` arm to `StaticFold.evalCall` and recompile. The message must move off "cannot access 'name' on String"; if it does not, the fold is failing before the `in` call and the diagnosis is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tests/testTdsExtension.pure:496-509 — the test: an in-memory project of (name, score) then `->extendWithDigestOnColumns('_digest')`; no execute() call, matching the harness prefix.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:209-225 — the 2-arg → 4-arg chain that introduces `toOneMany` and the `.columns->filter(c|$c.name->in($digestColumns))` read.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 — no `toOneMany` arm; :278-285 the `in` arm nulls out; :334-354 `filter` nulls out; :129-141 the UNROLL gate fails.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2465-2467, :2334-2345, :2597-2598 — `.columns` → String collection → `$c.name` → the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1302-1303 — testExtendDigest_InMemory and testExtendDigest_Relational carry the same wall text, consistent with one shared root cause independent of the relational path.

</details>

---

## `testExtendDigest_Relational`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

`extendWithDigestOnColumns(input, digestColumnName)` (tdsExtension.pure:209-212) is NormalizeRequired; its body is `$input->extendWithDigestOnColumns($digestColumnName, HashType.MD5, $input.columns.name->toOneMany())`. StaticFold folds `$input.columns.name` correctly to `['Trade ID','Quantity']` (evalProperty's `columns` arm at StaticFold.java:183-189 calls `relationColumns`, then the List<Col> auto-map at :203-217 yields the names, then `reify` at :531-541 makes a PureCollection of CStrings) — but the enclosing `->toOneMany()` has NO arm in `evalCall` (StaticFold.java:243-436), so the argument reifies only to the AST `AppliedFunction("toOneMany", [PureCollection['Trade ID','Quantity']])`. That AST is then β-substituted as `$digestColumns` into the 4-arg body (tdsExtension.pure:214-225): `$input->extend(col({row:TDSRow[1]| $input.columns->filter(c|$c.name->in($digestColumns))->map(col|toStringForColAccessor($col)->eval($row))->joinStrings('|')->hash($digestHashType)}, $digestValueColumnName))`. Now `evalCall`'s `in` arm (StaticFold.java:278-285) calls `evalList(ps.get(1))`, which evals `toOneMany(...)` → null → `in` returns null → the `filter` arm (:334-354) sees a non-Boolean predicate result and returns null → `foldCall`'s map UNROLL (:129-141) finds `evalList(ps.get(0)) == null` and does not unroll. The raw chain `$input.columns->filter(c|$c.name->in(...))` therefore reaches the Typer inside the col() lambda, where bare `.columns` on the projected relation is typed as a String collection (Typer.java:2465-2467 → columnsMeta at :2334-2345, element type `Type.Primitive.STRING`), `filter` binds `c : String`, and `$c.name` throws at Typer.java:2597: "cannot access 'name' on String".

**Fix**

In `core/src/main/java/com/legend/compiler/spec/StaticFold.java`, complete the static vocabulary. Four changes, all in that one file:

1. `evalCall`: add `case "toOneMany" -> { return ps.size() >= 1 ? evalList(ps.get(0), scope) : null; }` — identity on the evaluated list (the [1..*] assertion is a multiplicity claim, not a value transform). Also widen the existing `toOne` arm (StaticFold.java:421-432) to accept the 2-arg `toOne(v, message)` spelling: if `ps.size()==2` and `eval(ps.get(1))` is a String, treat it as the 1-arg case. `toStringForColAccessor` ends in `->toOne('Unsupported column type: '…)` (tdsExtension.pure:248) and will not fold otherwise.
2. `eval`: add a `case LambdaFunction lf -> lf;` arm so a lambda is an opaque static VALUE, and a matching `reify` arm `case LambdaFunction lf -> lf;`. This is what lets `[pair(Integer,{r|…}), pair(Float,{r|…}), …]->filter(p|$p.first == $col.type).second` (tdsExtension.pure:238-246) evaluate: without it `evalCall("pair")` returns null for every pair and the whole selector stays dynamic.
3. `evalCall`: add `case "toString" ->` returning `stringify(eval(ps.get(0), scope))` (StaticFold.java:487-496 already has `stringify`, including a TypeToken arm) — needed by the `toOne` message expression above.
4. Make a `Col` bound by a map UNROLL survive β-substitution into a schema-erased helper. Either (4a) give `reify` a `Col` arm emitting a lite-internal marker `AppliedFunction("meta::legend::lite::tdsColumnFact", [CString(name), CString(typeSimpleName)])` plus a matching `evalCall` arm that reconstructs the `Col`; or (4b) in `foldCall`, before the default rebuild, try `typer.rawSchemaErasedExpansion(af)` (already public at Typer.java:1348) and, if non-null, `fold(expansion, scope)` — folding the expanded helper body in the SAME scope so `$col.name`/`$col.type` resolve. Prefer (4b): it needs no new AST vocabulary and reuses an existing, tested primitive. Without step 4, `toStringForColAccessor($col)` inlines with `$col` FREE and the next wall is "unbound variable '$col'" (Typer.java:165).

With 1-4, the col() body folds to `[ {r:TDSRow[1]|$r.getInteger('Trade ID')->toString()}->eval($row), {r:TDSRow[1]|$r.getInteger('Quantity')->toString()}->eval($row) ]->joinStrings('|')->hash(HashType.MD5)` — ordinary typed vocabulary. Then verify MD5 hashing exists on the DuckDB render path; if it does not, the honest outcome is a MISSING_FEATURE wall on `hash`, not a fold hack.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/preeval.pure:199-203 — the engine's own preeval uses precisely the pair/filter/`.second`/`toOne`/`eval` idiom (`let handler = $handlers->filter(p|$p.first == $sfe.func).second; … $handler->toOne()->eval()`) and pre-evaluates it; the engine's static evaluator handles lambda VALUES inside pairs, which is what step 2 adds to StaticFold. The TDSColumn shape the fold must model is engine core/pure/tds/tds.pure:25-34 (`name : String[1]`, `type : Type[0..1]`).

**Risk** — Step 2 (lambdas become static values) is the widening risk: `evalList` will now succeed on collections containing lambdas, which changes `foldCall`'s map arm from UNROLL to eval for expressions like `[λ1,λ2]->map(...)`. If that proves disruptive, scope the lambda-as-value arm to lambdas appearing as a `pair(...)` operand only. Step 1's `toOneMany` identity is safe (it drops only a multiplicity assertion the folder never checked anyway) but does mean a genuinely-empty collection no longer walls at fold time — the downstream checker must still wall, so verify a `toOneMany([])` case still fails loudly. Tenet-2 trap: do NOT teach the harness or ExecCallFinder to skip `extendWithDigestOnColumns`, and do NOT hard-code the digest column list — the platform owns the fold.

**Also unblocks** — testExtendDigest_InMemory (identical mechanism, in-memory source). testTdsSchema.pure:129 and testTDSJoin.pure:1125-1128 also call extendWithDigestOnColumns and are likely blocked on the same fold.

**Falsifier** — Add ONLY the `toOneMany` arm and recompile testExtendDigest_Relational. If the message changes to an unbound-`$col` / `toStringForColAccessor` error, the chain of reasoning is confirmed and steps 2-4 are the remainder. If the message stays "cannot access 'name' on String", then the fold is failing earlier than `in`/`toOneMany` — most likely `relationColumns` (StaticFold.java:225-239) returning null for `Trade.all()->project([...])` — and the diagnosis is wrong in its first step.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:209-212 — the 2-arg overload passes `$input.columns.name->toOneMany()` as `digestColumns`; the `toOneMany` wrapper is the blocker.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:214-225 — the 4-arg body with `$input.columns->filter(c|$c.name->in($digestColumns))->map(col|toStringForColAccessor($col)->eval($row))`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 — `evalCall` has arms for `toOne` and `at` (:421-432) but none for `toOneMany`; the `default` returns null.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:278-285 — the `in` arm returns null the moment `evalList(ps.get(1))` is null.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:334-354 and :129-141 — `filter` returns null on a non-Boolean predicate result, and the map UNROLL is gated on `evalList(ps.get(0)) != null`, so the raw chain survives into the Typer.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:182-217 — `evalProperty`: the `columns` arm (:183-189) and the List<Col> auto-map (:203-217) that make `$input.columns.name` fold; this is why the 2-arg body gets that far.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2465-2467 and :2334-2345 — bare `.columns` on a relation types as a String collection.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2597-2598 — the throw site for "cannot access 'name' on String".
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:157-180 — `eval` has no `LambdaFunction` arm (falls to `default -> null`), which is the SECOND wall behind this one (see fix).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1314-1339 and :1348-1386 — `expandFunctionValuedHelperArgs` / `rawSchemaErasedExpansion`: `toStringForColAccessor($col)` WILL be raw-expanded in the `eval(...)` argument slot, so after the toOneMany fix the free `$col` variable becomes the next failure, not a silent success.

</details>

---

## `testFirstNotNull`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

Unrelated to the other five. `meta::pure::tds::extensions::firstNotNull<T>(set:T[*]):T[0..1]` with body `$set->filter(v | $v != TDSNull)->first()` is compiled into legend-lite's model verbatim by the corpus runner. It is an ordinary generic USER function — not native, not NormalizeRequired, no schema-erased params — so the call `[TDSNull, 1, 2]->firstNotNull()` emits a `TypedUserCall`. `SpecCompiler.check` (SpecCompiler.java:129-139) types a function body in an Env where each parameter is bound to its DECLARED type: `scope.with(p.name(), new ExprType(p.type(), p.multiplicity()))`. For firstNotNull that is `set : Type.TypeVar("T")[*]`. `SpecCompiler.compile` memoizes that ONE compilation keyed on the TypedFunction (SpecCompiler.java:57-74) — there is no per-call-site variant. `UserCallInliner.inlineCall` then splices exactly that generic body: `List<TypedSpec> body = specs.compile(call.callee()).body();` (UserCallInliner.java:184) followed by `reduceStatements(body, callEnv)`, which substitutes the ARGUMENT nodes for the parameter names but never re-types the body's nodes. So the spliced `filter(...)` node still carries `ExprType(T, [*])` and the `first()` node still carries `ExprType(T, [0..1])`, even though the call node's own `info()` was correctly resolved at the call site. When that spliced expression reaches SQL lowering, `PureSql.type` hits `case Type.TypeVar v -> throw new IllegalStateException("unresolved type variable " + v.typeName() + " reached the lowering boundary")` (PureSql.java:98-99) — with `v.typeName()` = "T", exactly the observed message. This is a general monomorphization gap: ANY generic user function whose body types flow into SQL will wall the same way; firstNotNull is simply the one this unit exercises.

**Fix**

Monomorphize the callee body at the call site. In `core/src/main/java/com/legend/compiler/spec/SpecCompiler.java`, add a call-site-typed variant alongside `compile`:

  public CompiledFunction compileAt(TypedFunction fn, List<ExprType> argTypes)

It runs the same `check(fn)` logic but seeds `scope` from `argTypes` (position-wise) instead of the declared `p.type()/p.multiplicity()`, and memoizes on the pair (fn, argTypes) rather than on fn alone. Keep the existing generic `compile(fn)` for whole-graph validation — the declared-return conformance check at SpecCompiler.java:151-158 must still run generically.

In `core/src/main/java/com/legend/compiler/spec/UserCallInliner.inlineCall` (UserCallInliner.java:184), replace

  List<TypedSpec> body = specs.compile(call.callee()).body();

with a gated form:

  List<TypedSpec> body = call.callee().typeParameters().isEmpty()
          ? specs.compile(call.callee()).body()
          : specs.compileAt(call.callee(),
                  args.stream().map(TypedSpec::info).toList()).body();

`args` is already computed at UserCallInliner.java:162-165. Gate on `typeParameters().isEmpty()` so non-generic callees keep the existing memoized path and there is no compile-count regression. If `compileAt` throws for a given argument shape, let it throw — the existing `catch (NotImplementedException)` at :211-218 already lets the call stand, and a TypeInferenceException here is a genuine call-site type error that should be loud.

Alternative if reworking SpecCompiler is too invasive: build a `Bindings` by unifying the callee's declared parameter types/multiplicities against the actual argument ExprTypes, then map every spliced node's ExprType through `kernel.resolve(type, b)`. This needs a generic 'withInfo' rewrite over TypedSpec, which mapChildren does not currently provide — hence compileAt is the cleaner route.

Honest expectation: this removes the TypeVar wall. The test then asserts `assertEquals(1, [TDSNull,1,2]->firstNotNull())` over a heterogeneous collection whose element type is Any (carried as JSON at the SQL boundary per PureSql.java's isAny arm) and `assertEquals([], [TDSNull,TDSNull]->firstNotNull())`. Verify the Any/JSON carrier compares equal to the integer literal 1 before claiming the test green; if it does not, that is a separate, honest issue about heterogeneous-collection carriers, not something to paper over in the assert.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tdsExtensions.pure:15-18 — `function meta::pure::tds::extensions::firstNotNull<T>(set:T[*]):T[0..1] { $set->filter(v | $v != TDSNull)->first(); }`. The engine additionally registers it with a call-site-resolved return: /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-base/legend-engine-core-language-pure/legend-engine-language-pure-compiler/src/main/java/org/finos/legend/engine/language/pure/compiler/toPureGraph/handlers/Handlers.java:1771 — `register("meta::pure::tds::extensions::firstNotNull_T_MANY__T_$0_1$_", "firstNotNull", false, ps -> res(ps.get(0)._genericType(), "zeroOne"));` — i.e. T is resolved FROM THE ARGUMENT at the call site, never left abstract.

**Risk** — Memoizing on (fn, argTypes) multiplies compilations of hot generic helpers; bound it by keying on the resolved ExprType list and reusing the generic entry when the callee has no type parameters (the gate above already does this). A second-order risk: a generic body that previously compiled once and passed its declared-return conformance may now fail conformance for a specific argument shape — that is a true error surfacing, but it can turn a currently-passing test loud, so re-sweep broadly. Tenet-2 trap: do not make PureSql.type silently map an unresolved TypeVar to VARCHAR or JSON — that converts a compiler gap into wrong rows, which is precisely the failure mode the tenets forbid.

**Also unblocks** — Every corpus test that calls a GENERIC user-defined function whose result reaches SQL lowering — the same 'unresolved type variable … reached the lowering boundary' text should be grepped across docs/RELATIONAL_CORPUS.md before sizing the change.

**Falsifier** — Print `call.callee().qualifiedName()` and each spliced node's `info().type()` in `UserCallInliner.inlineCall` while compiling testFirstNotNull. If no node carries a `Type.TypeVar` named T, the TypeVar is coming from somewhere other than the generic body splice (e.g. the call node's own unresolved output because unify failed to bind T against the heterogeneous `[TDSNull,1,2]` argument) and the fix belongs in overload resolution instead. That single observation separates the two candidate causes.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:136-139 — `Env scope = Env.empty(); for (TypedParameter p : fn.parameters()) { scope = scope.with(p.name(), new ExprType(p.type(), p.multiplicity())); }` — the DECLARED type, i.e. `Type.TypeVar("T")` for a generic function.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:57-74 — `compile` memoizes on the TypedFunction alone (`memo.get(fn)` / `memo.put(fn, cf)`), so there is exactly one, generic, body compilation per function.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:184-210 — `specs.compile(call.callee()).body()` then `reduceStatements(body, callEnv)`; callEnv maps parameter NAMES to argument nodes (:191-200). No re-typing pass, and the only post-fix is the relation-widening `TypedSelect` at :202-209.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/PureSql.java:98-99 — `case Type.TypeVar v -> throw new IllegalStateException("unresolved type variable " + v.typeName() + " reached the lowering boundary");` — the exact observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:69-74 — the corpus runner injects `function meta::pure::tds::extensions::firstNotNull<T>(set:T[*]):T[0..1] { $set->filter(v | $v != TDSNull)->first(); }` verbatim into the shared model, so this is a user function with a body, not a native.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypedFunction.java:51-99 — the record carries `List<String> typeParameters`, so a 'is this generic?' gate is already available.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:169-175 — a bare `TDSNull` reference synths to `sqlNull()`, so the argument `[TDSNull, 1, 2]` is a well-typed heterogeneous collection; the call node's own output resolves fine and only the spliced BODY carries T.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tdsExtensions.pure:15-18 — the engine's real definition of firstNotNull, identical to the one the runner injects.

</details>

---

## `testJoinWithExtendWithDigestOnColumnsOnBothQueries`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

StaticFold's map-over-a-static-collection UNROLL drops the lambda binder from the rebuilt AST while binding the loop variable ONLY in its private static-value scope (a Java Map<String,Object>). Every occurrence of that variable in the unrolled body must therefore be reifiable back into an AST literal, and reify only knows String/Long/Double/Boolean/Pair/List. A Col (the TDSColumn metadata record) and a TypeToken reify to null, so an occurrence that passes the loop variable WHOLE survives as a free Variable with no binder anywhere. That is exactly the shape of the corpus body being inlined here. CHAIN: extendWithDigestOnColumns(input,name,hashType,cols) carries <<functionType.NormalizeRequiredFunction>>, so Typer.applyGeneric (Typer.java:1208-1213) routes it to inlineNormalized (Typer.java:1274-1305), which (a) alpha-renames every binder in the corpus body to a fresh _nr<N> (Typer.alphaRename, Typer.java:1388-1425), (b) beta-substitutes the call arguments, (c) runs new StaticFold(this,env).fold(body) (Typer.java:1301). The body is $input->extend(col({row|$input.columns->filter(c|$c.name->in($digestColumns))->map(col|toStringForColAccessor($col)->eval($row))->joinStrings('|')->hash($digestHashType)}, $digestValueColumnName)). alphaRename walks depth-first left-to-right, so binders number row=_nr0, then (descending hash -> joinStrings -> map -> filter) c=_nr1, then map's col=_nr2. $_nr2 is therefore precisely the map(col|...) binder, which is the exact identifier in the failure text, corroborating both the traversal model and that this is the first inline in the query. In the fold, $input.columns is intercepted by StaticFold.evalProperty/relationColumns (StaticFold.java:182-239): it speculatively types the substituted project(...) receiver, gets a RelationType, and yields [Col(personID,Integer),Col(firstName,String),Col(lastName,String),Col(eID,Integer)]. evalCall 'filter' + evalCall 'in' (StaticFold.java:278-285, 334-354) keep [Col(firstName),Col(lastName)]. eval of the map returns null (its lambda body reads the runtime row), so foldCall's unroll arm (StaticFold.java:129-142) fires: it binds _nr2 -> Col in `inner` and emits one folded copy of the body per column with NO lambda wrapper. Folding that body reaches fold($_nr2) inside toStringForColAccessor($_nr2); eval returns the Col, reify(Col) returns null (StaticFold.java:519-544 has no Col arm), so the bare Variable _nr2 is emitted. The Typer then synths the folded tree; EvalChecker.check (EvalChecker.java:50-57) beta-expands toStringForColAccessor via rawSchemaErasedExpansion (its col:TDSColumn[1] param makes requiresNormalization true, Typer.java:1228-1270), producing a body full of $_nr2.name / $_nr2.type, and Typer.synth's Variable arm throws at Typer.java:164-165: unbound variable '$_nr2'. The hash2 side does NOT trip this: its digestColumns ['legalName'] match none of the firm TDS columns [firmName,fID], so the static filter yields the EMPTY list, eval of map returns [] (loop never runs), joinStrings folds to '' and the column becomes hash('',MD5) - which is why the corpus expects d41d8cd98f00b204e9800998ecf8427e (md5 of the empty string) for every hash2 cell. So the defect is entirely on the hash1 side.

**Fix**

Make the unroll's binding survive into the AST, in StaticFold.java. Four coordinated changes:
(1) EXPAND schema-erased helper calls DURING the fold, so the whole-Col occurrence is consumed while the Col is still in the static scope. In foldCall, before the generic rebuild at StaticFold.java:150, add: if some free Variable of `af` is bound in `scope` to a value reify() cannot represent (a Col), try `ValueSpecification ex = typer.rawSchemaErasedExpansion(af); if (ex != null) return fold(ex, scope);`. Gate it on that non-reifiable-capture condition so existing NormalizeRequired inlines are untouched. (rawSchemaErasedExpansion is already package-visible: Typer.java:1348.)
(2) Give eval() a quoted-lambda value so the expansion's pair(Type, {r|...}) dispatch table can be evaluated: add `record Quote(LambdaFunction lambda)` and, in eval, `case LambdaFunction lf -> new Quote((LambdaFunction) foldLambdaInScope(lf, shadow(scope, lf.parameters())))` - the body must be FOLDED BEFORE quoting, otherwise the inner {r|$r.getString($_nr2.name)} keeps its own dangling $_nr2. Add `case Quote q -> q.lambda()` to reify. CRITICAL GUARD: fold(v,scope) at StaticFold.java:94-99 currently calls eval/reify first for every node; it must skip that shortcut for LambdaFunction (`if (!(v instanceof LambdaFunction)) { ... }`), or every col({r|...},name) lambda in existing NormalizeRequired bodies would be reified back unfolded and today's .name/.type folding inside col lambdas would silently regress.
(3) reify(TypeToken t) -> new PackageableElementPtr(t.simpleName()), and teach evalProperty's List arm (StaticFold.java:203-217) to auto-map 'first'/'second' over a List of Pair (today it only handles Col), so [pair(Integer,{..}),...]->filter(p|$p.first == $col.type).second evaluates.
(4) Support toOne with a message argument in evalCall's 'toOne','at' arm (StaticFold.java:421-432): today `toOne(coll,'msg')` falls into the `at` branch, needs a Long index, and returns null - so toStringForColAccessor's final ->toOne('Unsupported column type: '...) never folds. Also add toOneMany as a passthrough there; the 2-arg extendWithDigestOnColumns overload (tdsExtension.pure:209-212) needs it for $input.columns.name->toOneMany().
After this the hash1 extend column folds to col({_nr0|hash(joinStrings([eval({r|$r.getString('firstName')->toString()},$_nr0), eval({r|$r.getString('lastName')->toString()},$_nr0)],'|'), HashType.MD5)}, 'hash1') with no free variables; eval-of-lambda-literal is already handled by EvalChecker.lambdaEval (EvalChecker.java:69,75-125) and hash(text,HashType) already lowers to SqlFn.MD5 (Scalars.java:1971-1985).
CHEAPER ALTERNATIVE, and the one this codebase has precedent for: register extendWithDigestOnColumns (both arities) as a NATIVE in builtin/Pure.java and desugar it in a checker. requiresNormalization returns false for natives (Typer.java:1229) and InferenceKernel.java:836-850 already implements the 'a registered native carries the PLATFORM's semantics for a name whose corpus body is reflection this platform cannot run' tie-break (Pure.java:1564-1572, concatenateTemporalTdsQueries). Add a CoreFn arm next to Typer.java:1180-1198 that reads the receiver's RelationType, picks the columns whose names are in the digest list IN RECEIVER ORDER, and builds extend(col({row|joinStrings([<typed getX(row,'c')->toString()> ...], sep)->hash(type)}, name)) from Java. That covers both overloads and skips all of StaticFold - but it does not generalise to any other NormalizeRequired body that passes a TDSColumn whole to a helper.
SECOND, INDEPENDENT OBSTACLE - do not expect green from the above alone. I reconstructed the corpus's golden digests: they are md5 of firstName+lastName+'|' (verified for all 7 rows: md5('PeterSmith|')=ee0af362d8c1e4fa8c805dfeadd1aa37, md5('FabriceRoberts|')=78e0713429c21373986cd56e497ece2f, ...), i.e. the engine emitted concat(A, B, '|') - the separator APPENDED once, not interleaved. legend-lite's joinStrings lowering (Scalars.java:813-857) interleaves (CONCAT_JOIN, or STRING_AGG with the separator), which would give md5('Peter|Smith')=acb3d9c79f4a025d8ff258eda164fffa and a rows-differ failure. I could not pin the engine line that produces the append form: pureToSQLQuery.pure:8260-8276 (processJoinStrings) routes the 2-arg separator to JoinStrings.separator, and h2Extension1_4_200.pure:338-342 interleaves it (concat(A,'|',B)); the append form only arises in extensionDefaults.pure:701-716 when prefix and separator are empty and suffix is set. Treat this as a separate ticket and probe it before claiming the test green.

**How legend-engine does it** — legend-engine does NOT source-inline these bodies - it PREVALS them. router_routing.pure:206-241 (meta::pure::router::routing::prevalFunctionExpressionIfRequired) tests `$f->hasStereotype('NormalizeRequiredFunction', functionType)` and calls `$tmpFe->meta::pure::router::preeval::preval($inScopeVars->putAll($f->openVariableValues()), ...)`, i.e. the body is really EVALUATED by the Pure interpreter against real TDSColumn instances, and closures carry their captured environment (note the `openVariableValues()` merge at router_routing.pure:250-292, which is precisely how the captured $col stays bound inside the closure returned by toStringForColAccessor). The function bodies being inlined are core_relational/relational/tds/tdsExtension.pure:214-227 (extendWithDigestOnColumns/4) and :228-249 (toStringForColAccessor, the pair(Type, closure) dispatch table).

**Risk** — The eval-returns-Quote change is the dangerous one: fold() calls eval/reify on every node first (StaticFold.java:94-99), so without the LambdaFunction guard in (2) every lambda would be reified back UNFOLDED, silently regressing the existing col({r|$col.name+'_x'},...) folding that other NormalizeRequired bodies (extendMatchColumns, the columnValueDifference family) depend on. The (1) expansion must stay gated on a non-reifiable capture, otherwise it changes which helper calls expand raw vs. at check time. Tenet-2 trap to avoid: do NOT special-case extendWithDigestOnColumns in RelationalCorpusRunner or in any harness assert path, and do NOT hardcode the golden hash strings - the digest column is platform output. Also note that the honest minimum, if the full fix is deferred, is to make the unroll REFUSE and throw a named wall (e.g. 'static fold: TDSColumn loop variable used outside .name/.type') instead of emitting an ill-formed AST with a dangling variable; a loud wall beats the misleading 'unbound variable $_nr2'.

**Also unblocks** — Other extendWithDigestOnColumns callers in the corpus: tds/tests/testTdsExtension.pure:504 and :523 and tds/tests/testTdsSchema.pure:129 (all use the 2-arg overload, which additionally needs the toOneMany passthrough from fix step 4). Fix step (3) (reify TypeToken, .first/.second over a Pair list) plus the underlying 'legend-lite has no TDSColumn object' gap (Typer.java:2465-2467 types .columns as name strings) is also what tds/tests/testTdsExtension.pure rowValueDifferenceTest trips on ('cannot access name on String').

**Falsifier** — Make StaticFold.foldCall's map arm (StaticFold.java:129-142) bail out (fall through to the generic rebuild at :150) whenever evalList returns a list containing a Col, and re-run the test. If the unroll is the source, the message must change away from "unbound variable '$_nr2'" - the fold's LambdaFunction arm (:104-108) preserves lf.parameters(), so the binder would still exist and the failure would move to the TDSColumn/.columns typing wall (the sibling shape 'cannot access name on String' seen in rowValueDifferenceTest). If the message stays "unbound variable '$_nr2'", the binder is being lost somewhere other than the unroll and this diagnosis is wrong.

<details><summary>Evidence read (12 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/StaticFold.java:129-142 - foldCall's map arm: `List<Object> coll = evalList(ps.get(0), scope); ... inner.put(lam.parameters().get(0).name(), e); parts.add(fold(single(lam), inner));` - emits the bodies, never re-wraps them in a LambdaFunction, so the binder is gone
- core/src/main/java/com/legend/compiler/spec/StaticFold.java:519-544 - reify(): arms for String/Long/Double/Boolean/Pair/List only; `case null, default -> null` swallows Col and TypeToken, so a whole-Col (or .type) occurrence cannot be turned back into AST
- core/src/main/java/com/legend/compiler/spec/StaticFold.java:126-128 - the design comment itself states the assumption: 'the parameter bound as a scope fact (a Col element's .name/.type reads fold to literals inside)' - i.e. only .name/.type occurrences were anticipated
- core/src/main/java/com/legend/compiler/spec/StaticFold.java:182-239 - evalProperty('columns') + relationColumns(): types the receiver speculatively and returns Col(name, simple type) records; this is what makes the unroll fire at all
- core/src/main/java/com/legend/compiler/spec/Typer.java:164-165 - `case Variable v -> new TypedVariable(v.name(), env.lookup(v.name()).orElseThrow(() -> new TypeInferenceException("unbound variable '$" + v.name() + "'")))` - the exact message literal from the sweep
- core/src/main/java/com/legend/compiler/spec/Typer.java:1388-1425 - alphaRename: `String fresh = "_nr" + nrFresh++;` with depth-first left-to-right traversal of AppliedFunction params, which makes row=_nr0, c=_nr1, col=_nr2
- core/src/main/java/com/legend/compiler/spec/Typer.java:1274-1305 - inlineNormalized: inlineLets, param substitution, alphaRename, then `synth(new StaticFold(this, env).fold(body), env)`
- core/src/main/java/com/legend/compiler/spec/Typer.java:1228-1237 - requiresNormalization: stereotype 'NormalizeRequiredFunction' OR any schema-erased param; toStringForColAccessor(col:TDSColumn[1]) qualifies via the TDSColumn arm at :1255
- core/src/main/java/com/legend/compiler/spec/EvalChecker.java:50-57 - eval() expands a helper CALL in position 0 via rawSchemaErasedExpansion, which is how $_nr2 reaches Typer:165 as $_nr2.name/$_nr2.type
- core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:705-720 - FUNCTION-ONLY parent sources are loaded, naming tds/tdsExtension.pure explicitly: the corpus definitions of extendWithDigestOnColumns and toStringForColAccessor really are in the module
- docs/BURNDOWN_EXPLANATIONS.md:282 - a prior pass already localised this to 'StaticFold map-unroll leaks the binder - StaticFold.java:124-141' plus '.columns returns String names, not TDSColumn objects'
- core/src/main/java/com/legend/compiler/spec/Typer.java:2333-2345 + :2465-2467 - columnsMeta(): a bare `.columns` on a relation types as the column NAME strings, so legend-lite has no TDSColumn object at the Typer level; only StaticFold's Col record carries name+type

</details>

---

## `zScoreTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M (revised up from XS by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

Identical to iqrClassifyTest, same code path, same helper. `zScore` (tdsExtension.pure:150-170) is `<<functionType.NormalizeRequiredFunction>>` and calls the same private `joinWithOptionalColumns($input, <groupBy pipeline>, JoinType.LEFT_OUTER, $window)`; the test calls `$tds->zScore([], 'score', 'zscore')` so `$window` β-substitutes to `[]`. `StaticFold.evalCall` has no `isEmpty` arm, the `if($cols->isEmpty(), …)` does not fold, and the dead else-branch `join($q1, $q2, JoinKind.LEFT, []->toOneMany())` is type-checked, hitting three same-arity `join` candidates (native relation::join plus the two tds/tds.pure module overloads) that all score -1. The reported argument detail is byte-identical to iqrClassifyTest's because in both tests `$input` is the same `$data->project([col(p|$p.first,'name'), col(p|$p.second,'score')])` relation.

**Fix**

Exactly the fix for iqrClassifyTest — add `isEmpty`/`isNotEmpty`/`size` arms to `StaticFold.evalCall` in core/src/main/java/com/legend/compiler/spec/StaticFold.java. No zScore-specific change. After the fold, zScore's surviving pipeline is a cross LEFT join against an empty-key `groupBy` with `average`/`stdDevPopulation` aggregates, then `->extend(col(r|if($r.getNumber('score_zScoreStdDev') > 0, …, |[]->cast(@Float)), 'zscore'))` and `->restrict($input.columns.name->concatenate($outputCols))`; the `$input.columns.name` read already folds to literals via StaticFold.evalProperty/Typer.accessProperty's columns.name fast path, so the remaining risk is the empty-key groupBy and the cross join, not the schema computation.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/preeval/preeval.pure:169-183 — the preeval `if` handler that folds a statically-known condition to its taken branch, which is exactly what legend-lite's StaticFold is standing in for.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Same mechanism as iqrClassifyTest, and I verified the zScore-specific citations independently. tdsExtension.pure:150-170 is zScore verbatim: `$input->joinWithOptionalColumns($input->restrict($window->concatenate($cols))->groupBy($window, ...), JoinType.LEFT_OUTER, $window)` then `->extend(...)->restrict($input.columns.name->concatenate($outputCols))`, and :201-207 is the shared joinWithOptionalColumns if/isEmpty helper — both exactly as quoted, so `$cols` in the helper binds to `$window` which the test passes as `[]`. docs/RELATIONAL_CORPUS.md:1300 and :1305 do carry byte-identical failure text for the two tests. StaticFold has no isEmpty arm (grepped every case), InferenceKernel.java:810-821 is the only site producing that string, and the else-branch's non-lambda 4th arg is what routes it there rather than to checkWithDeferred's differently-worded throw. β-substitution is real AST substitution (Typer.inlineNormalized :1274-1305), and `[]` parses to an empty PureCollection which StaticFold.eval already reduces to an empty List — so the proposed isEmpty arm folds the condition and the dead branch disappears.

Same caveat as iqrClassifyTest: I could not run anything, so 'the message must change' is untested, and the diagnosis's own follow-on (cross LEFT join + empty-key groupBy with average/stdDevPopulation) is unverified. The named StaticFold edit is genuinely XS and is shared with iqrClassifyTest (one edit, two tests), but XS as an estimate for getting zScoreTest green is optimistic — hence the revised effort.

</details>

**Risk** — Same as iqrClassifyTest: broader condition folding changes walls elsewhere; re-sweep the tds/ families. Do not add a zScore- or joinWithOptionalColumns-specific desugar (tenet 2).

**Also unblocks** — iqrClassifyTest — same single change.

**Falsifier** — Same as iqrClassifyTest: if the join that throws has a Function-typed 4th argument, the then-branch is the failing one and this is wrong. One-line probe: add only the `isEmpty` arm and recompile zScoreTest; the message must change.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:150-170 — `zScore` body: `$input->joinWithOptionalColumns(<groupBy>, JoinType.LEFT_OUTER, $window)` then `->extend(...)->restrict($input.columns.name->concatenate($outputCols))`.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tdsExtension.pure:201-207 — the shared `joinWithOptionalColumns` if/isEmpty body.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tds/tests/testTdsExtension.pure:47-49 — `$data->project([col(p|$p.first,'name'), col(p|$p.second,'score')])` then `$tds->zScore([], 'score', 'zscore')`: the empty window, and the exact (name:String, score:Integer) relation named in the failure text.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 — no `isEmpty` arm in `evalCall`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:810-821 — the throw site for the observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1300 and :1305 — iqrClassifyTest and zScoreTest carry byte-identical failure text, consistent with one shared helper.

</details>

---

## `testSimpleTypeMappingProjectNulls`

| | |
|---|---|
| family | `tests/datatype` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

`meta::json::toJSON` is genuinely absent from legend-lite. The test file imports `meta::json::*` (testDataTypeMapping.pure:36) and its final assert calls `$tds->toJSON()` on a TabularDataSet. Typer.checkGeneric collects candidates via functionCandidates(af); the catalog has no function named toJSON at all (a grep for 'toJSON' across core/src/main/java/com/legend/ returns zero hits), so `candidates.isEmpty()` and the C0.5a arm throws the exact 'unported platform function' message. This is not a misdiagnosis of some other defect: the whole meta::json serializer family — toJSON/toJSONElement/toJSONStringStream, Config, JSONState, extraSerializers — is unported. The test's other assertions (TDSNull rows from a projection over nullable columns) are the semantic content and are unrelated to the wall.

**Fix**

Port a SCOPED `meta::json::toJSON`. In core/src/main/java/com/legend/builtin/Pure.java add the native signatures the corpus actually spells — at minimum `native function meta::json::toJSON(obj:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];` (the corpus also uses the 2-arg `(Any[*], Integer[0..1])` and `(Any[*], Function[*], Boolean[1])` forms; add only what the corpus spells). Implement it host-side in the harness's native evaluator alongside the other value-producing natives (com/legend/harness — the same seam that folds toCSV/sqlRemoveFormatting), with ONE arm: when the argument is a TabularDataSet value, emit exactly the engine's optimizedTdsJSONStringStream shape — `{"columns":[{"name":<col>,"type":<elementToPath of the column type or "">,"metaType":"PrimitiveType"|"Enumeration"|"InvalidType"|""}],"rows":[{"values":[…]}]}`, with a TDSNull cell rendered as bare `null` (toJSON.pure:195,263). Every other argument shape must throw NotImplementedException naming toJSON — do not attempt a generic object serializer.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/external/format/json/toJSON.pure:54 (entry) and :193-206 (the TDS serializer whose exact text the test's golden pins)

**Risk** — Do NOT implement this by teaching the harness to skip the toJSON assert or to compare a normalized form — the golden string pins column type names and metaType classification, and weakening the compare would turn a real serializer gap into a silent pass. Scope creep is the other risk: meta::json in the engine is a full extensible serializer (Config, extraSerializers, graph-fetch trees, cipher/decipher). Port the TDS arm only and stay loud elsewhere.

**Also unblocks** — Nothing else in this corpus — testSimpleTypeMappingProjectNulls is the only failing test whose wall is toJSON (docs/RELATIONAL_CORPUS_ALL.md:1435 is the sole hit).

**Falsifier** — Register only the native signature (no implementation) and re-run. If the failure changes from 'unknown function toJSON' to a NotImplementedException at the evaluator, the vocabulary-gap diagnosis is confirmed. If it changes to something else — e.g. an overload/resolution error — then `meta::json` is partially present somewhere I did not find and the diagnosis is wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1451 — `if (candidates.isEmpty()) { … throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog (unported platform function, or a misspelling)"); }`
- grep of /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/ for 'toJSON' — no matches; the name exists nowhere in legend-lite
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/datatype/testDataTypeMapping.pure:36 — `import meta::json::*;`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/datatype/testDataTypeMapping.pure:149-159 — the test body; line 157 is the `$tds->toJSON()` assert
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/external/format/json/toJSON.pure:54-56 — `function meta::json::toJSON(obj:Any[*]):String[1] { toJSON($obj, [], 5); }`
- /Users/neemsandv/legend/legend-engine/.../core/external/format/json/toJSON.pure:193-206 — meta::json::optimizedTdsJSONStringStream: the TDS arm producing exactly `{"columns":[{"name":…,"type":…,"metaType":…}],"rows":[{"values":[…]}]}`, with metaType via match([PrimitiveType|'PrimitiveType', Enumeration<Any>|'Enumeration', Any|'InvalidType'])
- /Users/neemsandv/legend/legend-engine/.../core/external/format/json/toJSON.pure:263 — `n:TDSNull[1] | 'null'` (the null cell rendering the test's golden expects)

</details>

---

## `testEnumMappings`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | medium |

**Root cause**

`meta::pure::mapping::enumerationMappingByName(Mapping[1], String[1]):EnumerationMapping<Any>[0..1]` and `meta::pure::mapping::toDomainValue<T>(EnumerationMapping<T>[1], Any[1]):Any[1]` are legend-pure platform functions. legend-lite carries the mapping-metamodel navigation family (`rootClassMappingByClass`, `_classMappingByClass`, `classMappingById`, `superMapping`, `allSuperSetImplementations`, `propertyMappingsByPropertyName`) as typed natives in Pure.java plus a K-phase walk in MetamodelWalk/StatementExecutor — but the ENUMERATION half of that family was never ported. So `Typer.checkGeneric` finds zero candidates for `enumerationMappingByName` and throws at Typer.java:1448-1451. The data is all present: `LegacyMappingDefinition.enumerationMappings()` and `com.legend.model.EnumerationMapping` (with `EnumValueMapping` / `SourceValue`) already model exactly what the pure body reads; the walk simply has no arm for them.

**Fix**

Three coordinated edits, all following the classMappingById precedent. (1) core/src/main/java/com/legend/builtin/Pure.java, next to CLASS_MAPPING_BY_ID (line 1489): add `ENUMERATION_MAPPING_BY_NAME = signature("native function meta::pure::mapping::enumerationMappingByName(_this:meta::pure::mapping::Mapping[1], name:meta::pure::metamodel::type::String[1]):meta::pure::mapping::EnumerationMapping<meta::pure::metamodel::type::Any>[0..1];")` and `TO_DOMAIN_VALUE = signature("native function meta::pure::mapping::toDomainValue<T>(_this:meta::pure::mapping::EnumerationMapping<T>[1], sourceValue:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Any[1];")`, and register both wherever the neighbouring constants are collected into the native catalog. (2) core/src/main/java/com/legend/exec/MetamodelWalk.java: add `public record Em(ModelContext ctx, com.legend.model.EnumerationMapping em)` alongside `Cm`/`Pm` (line 771-782), then `public static Object enumerationMappingByName(Object recv, String name)` that mirrors classMappingByIdIn EXACTLY — recurse into `md.includes()` via `ctx.findLegacyMapping(inc.mappingPath())` FIRST, then match `md.enumerationMappings()` on `mappingId().equals(name)` — and `public static Object toDomainValue(Object recv, Object sourceValue)` that, for an `Em`, scans `em.valueMappings()` for the entry whose `sourceValues()` contains a SourceValue equal to the argument (StringValue.value / IntegerValue.value / EnumRef by enumValueName), asserts exactly one match and throws the engine's message text when not, and returns the domain enum value in the SAME host representation TypedEnumValue uses — i.e. the bare value NAME string, per StatementExecutor.java:1668 `case TypedEnumValue ev -> ev.value()`. (3) core/src/main/java/com/legend/StatementExecutor.java: add `case "enumerationMappingByName" ->` (guarded on `c.args().get(1) instanceof TypedCString`) and `case "toDomainValue" ->` arms to the planWalk switch beside the existing `"propertyMappingsByPropertyName"` arm (line ~1400). Note the include-closure ORDER: real pure puts the includes' hits BEFORE the own-mapping hits and then removeDuplicates+toOne, so a name defined in both an include and the includer resolves to the INCLUDE's — mirror that, do not short-circuit on the local mapping first.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:19 (enumerationMappingByName) and .../functions_EnumerationMapping.pure:18 (toDomainValue).

**Risk** — Tenet-2 trap: do NOT special-case these in Runner/EngineTestExecutor as harness asserts — they are platform metamodel navigation and belong in the native catalog + MetamodelWalk, exactly where their classMapping siblings already live. Real risk of the change: `toDomainValue` returning a bare String makes enum identity indistinguishable from a string of the same text; that is already legend-lite's convention (StatementExecutor.java:1668) so it introduces no NEW ambiguity, but do not 'improve' it here or the existing TypedEnumValue consumers (Lowerer.java:2535 renders an enum as a StringLit) go out of sync.

**Also unblocks** — testEnumMappingsWithInclude (same two natives, plus the includes recursion). PARTIALLY testEnumTheSame (docs/RELATIONAL_CORPUS_ALL.md:1464), which needs `toDomainValue` too but walls earlier on `employeeTestMapping.enumerationMappings` — a PROPERTY read on a Mapping ref, which additionally needs an `enumerationMappings` arm in MetamodelWalk.prop (reached via StatementExecutor.walkProp) and currently mis-parses as an enum-value access (Typer.java:2664 `unknown enumeration '…employeeTestMapping'`).

**Falsifier** — Cheapest check: after adding only `enumerationMappingByName` (leaving toDomainValue absent), rerun the test and confirm the wall moves to `unknown function 'toDomainValue'`. That proves the catalog-gap chain is the whole story. Second falsifier for the PASS claim: verify how `assertEquals(TradeType.BUY, <walk value>)` compares — if the harness compares a TypedEnumValue node against a String, `toDomainValue` must return the bare name ("BUY"), not an enum handle; get this wrong and the test FAILs instead of ERRORing.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMapping.pure:166-178 — testEnumMappings: `tradeMapping->enumerationMappingByName('TradeSource1')->toOne()` then `assertEquals(TradeType.BUY, $map1->toDomainValue('BUY'))`. No execute call, so the SHAPE bucket is correct.
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:19 — `function meta::pure::mapping::enumerationMappingByName(_this:Mapping[1], name:String[1]):EnumerationMapping<Any>[0..1]`; body = includes-closure map, concatenated with `$_this.enumerationMappings->filter(em|$em.name == $name)`, removeDuplicates, then toOne-or-empty.
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_EnumerationMapping.pure:18 — `toDomainValue`: filter enumValueMappings where `sourceValues->contains($sourceValue)`, assert exactly one, return `.enum`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1444-1451 — `if (candidates.isEmpty()) throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog …")`, the exact observed message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1478-1491 — the ported mapping-metamodel natives (`ROOT_CLASS_MAPPING_BY_CLASS`, `CLASS_MAPPING_BY_CLASS`, `CLASS_MAPPING_BY_ID`, `SUPER_MAPPING`, `ALL_SUPER_SET_IMPLEMENTATIONS`); no `enumerationMappingByName`, no `toDomainValue`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:845-866 — `classMappingById` + `classMappingByIdIn`, the includes-recursive walk this fix should mirror exactly.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/LegacyMappingDefinition.java:58 — the record already carries `List<EnumerationMapping> enumerationMappings`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/EnumerationMapping.java:35-65 — `record EnumerationMapping(enumName, mappingId, List<EnumValueMapping> valueMappings)` with `EnumValueMapping(enumValue, List<SourceValue>)` and StringValue/IntegerValue/EnumRef.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1190 — `MetamodelWalk.mapping(env.ctx(), pr9.fullPath())` already turns a Mapping element ref in value position into an `Mm` walk handle, so `tradeMapping` on the left of `->` is already a live receiver.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1298-1415 — the `planWalk` native switch where `schema`/`table`/`classMappingById`/`propertyMappingsByPropertyName` arms live; the new arms go here.

</details>

---

## `testEnumMappingsWithInclude`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XS |
| confidence | medium |

**Root cause**

Same missing natives as testEnumMappings, with one extra requirement: the receiver `tradeMapping3` declares no enumeration mappings of its own — `TradeSource11` comes from `tradeMapping1` and `TradeSource22` from `tradeMapping2`, reached through `include tradeMapping2` -> `include tradeMapping1`. So the ported `enumerationMappingByName` must be include-closure-recursive, not own-mapping-only. legend-lite already has the exact recursion pattern in MetamodelWalk.classMappingByIdIn (walk `md.includes()` via `ctx.findLegacyMapping(inc.mappingPath())` first, then own). The observed wall is again Typer.java:1448-1451 zero-candidates.

**Fix**

Identical to testEnumMappings — the same two natives, with the include recursion written per functions_Mapping.pure:21. No additional change is needed: all three mappings live in one corpus file (testEnumerationMappingDomain.pure), so the harness's single-defining-file pull already brings tradeMapping1/2/3 into the module together, and `ctx.findLegacyMapping` will resolve the include targets.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:19-25

**Risk** — Do not implement the include walk as a flattened set: real pure orders includes-first and takes toOne after removeDuplicates, so a name shadowed in both places resolves to the include. Getting the order backwards would silently pass these two tests and be wrong for any shadowing mapping.

**Also unblocks** — testEnumMappings

**Falsifier** — If, after the fix, `enumerationMappingByName('TradeSource11')` on tradeMapping3 returns empty while the same call on tradeMapping1 works, the recursion is missing/short-circuited. Cheapest pre-check without building: confirm `LegacyMappingDefinition.includes()` is populated for tradeMapping3 by the mapping parser — I verified the field exists (LegacyMappingDefinition.java) and that MetamodelWalk already relies on it for class mappings, but I did not confirm the parser fills it for a `include tradeMapping2` line specifically.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMapping.pure:180-192 — `tradeMapping3->enumerationMappingByName('TradeSource11')` / `'TradeSource22'`, then toDomainValue asserts.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMappingDomain.pure:388-419 — `tradeMapping1` declares `TradeType: EnumerationMapping TradeSource11`; `tradeMapping2` is `include tradeMapping1` + `TradeSource22`; `tradeMapping3` is `include tradeMapping2` + only an EquityTrade Relational class mapping. Two include hops.
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:21 — `$_this.includes->map(i | $i.included)->map(m | $m->enumerationMappingByName($name))->concatenate(own)`: includes first, recursive, then removeDuplicates.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:852-866 — `classMappingByIdIn` already does exactly this recursion over `mapping.includes()` / `ctx.findLegacyMapping(inc.mappingPath())`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1444-1451 — the zero-candidate throw that produced the observed message.

</details>

---

## `testMultipleJoinsInPropertyMappingWithDateInJoin`

| | |
|---|---|
| family | `tests/mapping/join` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

legend-lite type-checks RELATIONAL-ALGEBRA operations against Pure's function overload table, and relational algebra legally compares a date column to a string literal. The mapping advancedRelationalMapping2 maps propertyTableB through `@TypeTableTableBThen`, whose condition is `TypeTable.ID = TypeTableB.ID and TypeTableB.IN_Z <= '2013-07-15 13:52:22.370' and '2013-07-15 13:52:22.370' < TypeTableB.OUT_Z`. IN_Z/OUT_Z are declared TIMESTAMP. RelOpTranslator.translate's Comparison arm builds a Pure AppliedFunction from comparisonFn(op) over both translated sides, and literalToValueSpec maps a String literal to a CString unconditionally, with no knowledge of the opposing column's SQL type. So the synthetic class-mapping function `advancedRelationalMapping2$class$…TypeBuiltOutOfMultipleJoins` type-checks `lessThanEqual(DateTime[0..1], String[1])`. legend-lite's lessThanEqual overload set is correctly Pure-faithful — Date×Date, Number×Number, String×String, Boolean×Boolean only, no mixed arm — so every arity-2 candidate scores -1, winners is empty, and resolveOverload throws. Real legend-engine never reaches a Pure overload table here: HelperRelationalBuilder turns the join operation into a DynaFunction whose parameters are untyped Literal nodes (`public Object value`), and the dialect renders the literal verbatim.

**Fix**

Make the relational Comparison translation type-directed on the column side. (1) Add a column-type lookup to RelOpTranslator.PipelineView (core/src/main/java/com/legend/normalizer/RelOpTranslator.java:58-84), e.g. `@Nullable String columnSqlType(String table, String column)`, implemented by MappingNormalizer from the Database it already holds and returning null in PipelineView.NONE. (2) In the Comparison arm (RelOpTranslator.java:493-514), before translating: if exactly one side is a `RelationalOperation.ColumnRef` whose columnSqlType is DATE / TIMESTAMP / DATETIME and the other side is a `RelationalOperation.Literal` carrying a String, translate that literal to a `CDate(PureDateLiteral.parse(...))` instead of a CString — normalising the SQL 'YYYY-MM-DD HH:MM:SS.mmm' space separator to Pure's 'T' before parsing, and mapping SQL DATE→StrictDate, TIMESTAMP→DateTime (the same SQL-type→Pure-primitive table already spelled in RelOpTranslator.pureTypeFor at lines 159-172). If the string does not parse as a date, leave it a CString so the existing loud overload error still fires. (3) Do the same for the mirrored operand order, since this join has the literal on the LEFT of `<`. Keep literalToValueSpec itself untyped — the coercion belongs at the comparison site, which is the only place the type context exists.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/main/java/org/finos/legend/engine/language/pure/compiler/toPureGraph/HelperRelationalBuilder.java:851-863 — a DynaFunc becomes Root_meta_relational_metamodel_DynaFunction_Impl with `_name(dynaFunc.funcName)` and processed parameters; a Literal becomes Root_meta_relational_metamodel_Literal_Impl with `_value(convertLiteral(...))`. No Pure function-overload resolution and no type check anywhere on that path. Corroborated by .../relationalStore-protocol/.../store/relational/model/operation/Literal.java:17-20 — `public class Literal extends RelationalOperationElement { public Object value; }`.

**Risk** — The rendered SQL will change: legend-lite will emit a typed timestamp literal where the engine golden shows a quoted string ('2013-07-15 13:52:22.370'). That is acceptable — EngineTestExecutor treats golden-SQL spellings as advisory ('golden-SQL spellings are advisory: our SQL is DuckDB's', EngineTestExecutor.java:1834-1835) and this test's real content is the three row assertions. The tenet-2 trap: do NOT widen Pure's lessThanEqual/greaterThanEqual with a Date×String overload to make this type-check. That would corrupt the Pure vocabulary for USER query code, where comparing a date to a string must stay an error.

**Also unblocks** — testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting, testBiTemporalUnionJoin_milestoningColumnInOnClause, testBiTemporalUnionWithSelfJoin_duplicateColumnRegression (all tests/mapping/union) — docs/RELATIONAL_CORPUS_ALL.md:1140,1508-1510 show them failing on greaterThanEqual(STRICT_DATE[0..1], STRING[1]) from `lake_thru >= '9999-01-01'` in mapping filters, which is the identical mechanism through the same Comparison arm.

**Falsifier** — Print the AppliedFunction RelOpTranslator produces for TypeTableTableBThen (or set a breakpoint at RelOpTranslator.java:508). If the second argument of lessThanEqual is not a CString holding '2013-07-15 13:52:22.370', the diagnosis is wrong. Equivalently: change the corpus join literal to a Pure date literal spelling and confirm the error disappears.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/tests/mapping/join/advancedRelationalSetUp.pure:27 — `Table TypeTableB (ID INT PRIMARY KEY, TypePropertyB VARCHAR(200), IN_Z TIMESTAMP, OUT_Z TIMESTAMP)`
- /Users/neemsandv/legend/legend-engine/.../join/advancedRelationalSetUp.pure:52 — `Join TypeTableTableBThen (TypeTable.ID = TypeTableB.ID and TypeTableB.IN_Z <= '2013-07-15 13:52:22.370' and '2013-07-15 13:52:22.370' < TypeTableB.OUT_Z)`
- /Users/neemsandv/legend/legend-engine/.../join/advancedRelationalSetUp.pure:196 — advancedRelationalMapping2's `propertyTableB : [db]@TypeTableTableBThen | default.TypeTableB.TypePropertyB` (the only user of that join)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:570-578 — `static ValueSpecification literalToValueSpec(Object value) { if (value instanceof String s) return new CString(s); … }` — no type context
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:493-514 — the Comparison arm builds `new AppliedFunction(comparisonFn(cmp.op()), List.of(side.apply(cmp.left()), side.apply(cmp.right())))`; both operands are in hand at this point
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1695-1706 — lessThanEqual overloads: Date×Date, Number×Number, String×String only (no mixed arm), which is correct Pure
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:885-899 — score() returns -1 as soon as any paramTypeScore is -1
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS_ALL.md:1140 — the same defect on greaterThanEqual with the untruncated types: `(ExprType[type=STRICT_DATE, multiplicity=[0..1]], ExprType[type=STRING, multiplicity=[1,1]])` against Date×Date / Number×Number / String×String / Boolean×Boolean candidates
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnionBiTemporalSelfJoinDuplicateColumn.pure:186 — `and OLAP.CUSTOMER.lake_thru >= '9999-01-01'` — the same string-vs-date-column shape in a mapping filter

</details>

---

## `testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Same vocabulary gap, but the failing function is `biTemporalUnionMapping$class$...PartyAccount` (a plain `ClassMapping.Relational`, not a union). `PartyAccount` is the OWNER of the 2-hop association end `owner[selectAccount, selectPerson]: @Account_To_AccountRole > @AccountRole_To_Party`; since PartyAccount is not union-mapped, AssociationSynthesis.java:186-193 puts the stamped Join PM into `bySet`/`byClass` and `withInjectedPMs` appends it to the `selectAccount` set. `MappingNormalizer.synthTableBackedParts` then emits both hops' conditions into `$class$PartyAccount`; `Account_To_AccountRole` carries `OLAP.PARTY_ACCOUNT_ROLE.lake_thru >= '9999-01-01'` and `AccountRole_To_Party` carries `OLAP.CUSTOMER.lake_thru >= '9999-01-01'` — both DATE-vs-String, both rejected by Pure overload resolution.

**Fix**

Same single change as test 1. No test-specific work.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/main/java/org/finos/legend/engine/language/pure/grammar/from/RelationalParseTreeWalker.java:823 (DynaFunc, untyped operands).

**Risk** — Same as test 1. This test additionally asserts a two-level correlated-subquery wrapper (`as "unionalias_1"` nested inside `as "unionalias_0"`, with `"unionalias_1"."lake_thru_0" as "lake_thru_0"` in the wrapper SELECT). legend-lite does emit `unionalias_N` frames (Lowerer.java:1753 comment `frame: select * from (lhs join rhs on ...) as "unionalias_N"`), but whether it produces the nested wrapper shape when a union is a JOIN TARGET is unverified — expect this one to need a second, independent diagnosis after the typer wall falls.

**Also unblocks** — testBiTemporalUnionWithSelfJoin_duplicateColumnRegression, testBiTemporalUnionJoin_milestoningColumnInOnClause

**Falsifier** — Dump the untruncated argument types of the failing resolution. If the second operand is not STRING, this diagnosis is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/AssociationSynthesis.java:186 — `bySet.computeIfAbsent(owner, ...).computeIfAbsent(apm.sourceSetId(), ...).add(stamped);` — the 2-hop `owner` end is appended to the selectAccount set
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3479 — javadoc: "This lets injected per-end association property mappings (Option A ...) resolve their terminus class through validatePmNames / classTypedTargetIfMapped / emitJoinChain" — confirms injection into the class body
- /Users/neemsandv/legend/legend-engine/.../testUnionBiTemporalSelfJoinDuplicateColumn.pure:201 — `and OLAP.PARTY_ACCOUNT_ROLE.lake_thru >= '9999-01-01'` (Join Account_To_AccountRole); :207 same shape in AccountRole_To_Party
- /Users/neemsandv/legend/legend-engine/.../testUnionBiTemporalSelfJoinDuplicateColumn.pure:147 — `lake_thru DATE` on PARTY_ACCOUNT / :167 on PARTY_ACCOUNT_ROLE — the columns really are DATE
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:509 — the emission site

</details>

---

## `testBiTemporalUnionJoin_milestoningColumnInOnClause`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Identical mechanism and identical failing function to test 1 (`biTemporalUnionMapping$class$...BiTemporalPerson`): the union member sets carry the injected 2-hop `account` Join PM whose first hop `AccountRole_To_Party` contains `OLAP.CUSTOMER.lake_thru >= '9999-01-01'`. `RelOpTranslator` emits Pure `greaterThanEqual(StrictDate[1], String[1])`; no homogeneous overload matches; InferenceKernel.java:819 throws. The class-mapping realizing function is synthesized eagerly over ALL property mappings of the set, so the failure fires even though this query only navigates `relationshipInfo` — the un-navigated `account` end is enough to sink the whole class mapping.

**Fix**

Same single change as test 1 (Pure.Lite ordering shims + `RelOpTranslator.comparisonFn` returning them). No test-specific work.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/main/java/org/finos/legend/engine/language/pure/grammar/from/RelationalParseTreeWalker.java:823 — the join operator becomes an untyped `DynaFunc` funcName; no operand typing exists in the engine's join pipeline.

**Risk** — Same as test 1. Additionally, this test's own assert (`$sql->contains('"lake_thru_0"')`) requires legend-lite to emit union milestoning columns with the engine's `_<setIndex>` suffix AND to quote them in the ON clause. That is a separate capability from the typer fix; if lite does not suffix/quote milestoning columns in union arms this test will re-fail on the assert after the wall is removed. Do not paper over that by relaxing the assert in the harness.

**Also unblocks** — testBiTemporalUnionWithSelfJoin_duplicateColumnRegression, testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting

**Falsifier** — Same as test 1: dump the untruncated argument types. If the second operand is not STRING, the vocabulary-gap diagnosis is wrong.

<details><summary>Evidence read (4 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2124 — `for (PropertyMapping pm : rcm.propertyMappings()) { ... JoinChainEmission.emitHopsForStructuralPm(...) }` — every PM of the set is emitted into the one realizing body, so one bad join condition fails the whole class
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:931 — `case ClassMapping.Union u -> UnionSynthesis.synthUnion(md, u, model);` inside `synthesizeClassMapping`, which names the function `SynthFqn.mappingClass(md.qualifiedName(), cm.className())` (line 940) = the `$class$` fqn in the error
- /Users/neemsandv/legend/legend-engine/.../testUnionBiTemporalSelfJoinDuplicateColumn.pure:222 — `and OLAP.PARTY_RELATIONSHIP.lake_thru >= '9999-11-30'` and :224 `and OLAP.CUSTOMER.lake_thru >= '9999-11-30'` in Join Party_To_Relationship — the same DATE-vs-String shape on the path this test actually navigates
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:819 — the throw site

</details>

---

## `testBiTemporalUnionWithSelfJoin_duplicateColumnRegression`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The mapping's `Account_Owner` association has multi-hop (2-join) ends, so `AssociationSynthesis.injectMultiHopAssociationPMs` turns `account[selectPerson, selectAccount]: @AccountRole_To_Party > @Account_To_AccountRole` into a class-typed Join PM on the BiTemporalPerson member sets (AssociationSynthesis.java:151-160 `join.joins().size() < 2 -> continue`, i.e. 2-hop injects; union-owner entries route via UnionSynthesis.java:371-405 into the member sets). The synth function `biTemporalUnionMapping$class$...BiTemporalPerson` therefore contains those join conditions. `RelOpTranslator.translate` case `RelationalOperation.Comparison` (RelOpTranslator.java:493-514) emits `new AppliedFunction(comparisonFn(cmp.op()), ...)` = the *Pure* name `greaterThanEqual` (RelOpTranslator.java:580-589), and the String literal `'9999-01-01'` becomes a `CString` (RelOpTranslator.java:570-578). The column read types as `STRICT_DATE` (StoreCompiler.java:191 `case RelationalDataType.Date_ d -> Type.Primitive.STRICT_DATE`). Pure's `greaterThanEqual` has only same-family overloads (Pure.java:1318-1329), `primitiveTypeScore` returns -1 for String-vs-Date (InferenceKernel.java:1046), every candidate scores -1, `winners` is empty and InferenceKernel.java:819-821 throws; SpecCompiler.java:69-70 prefixes it with `in function '<synth fqn>'`. The defect is that legend-lite subjects an engine `DynaFunc` (which has NO type discipline) to Pure overload resolution.

**Fix**

Give relational (DynaFunc-sourced) ordering comparisons their own loosely-typed vocabulary, exactly as `notEqualAnsi` already is (Pure.java:1819 `meta::legend::lite::notEqualAnsi(left:Any[1], right:Any[1]):Boolean[1]`).
1. `core/src/main/java/com/legend/builtin/Pure.java`, in `Pure.Lite` (line 788-794 block): add `LESS_THAN = PKG + "lessThan"`, `LESS_THAN_EQUAL = PKG + "lessThanEqual"`, `GREATER_THAN = PKG + "greaterThan"`, `GREATER_THAN_EQUAL = PKG + "greaterThanEqual"`, and add all four to `ENGINE_VOCAB_SHIMS` (line 831-836) so `Pure.wireEmissionName` (line 853) rewrites them.
2. Register four natives next to NOT_EQUAL_ANSI__ANY_1__ANY_1 (Pure.java:1819): `native function meta::legend::lite::greaterThanEqual(left:Any[1], right:Any[1]):Boolean[1];` and the three siblings.
3. `core/src/main/java/com/legend/normalizer/RelOpTranslator.java:580-589` — `comparisonFn` returns the LITE fqn for LT/LTE/GT/GTE (`Pure.Lite.GREATER_THAN_EQUAL` etc.). EQ/NEQ stay on `equal` (already Any-typed).
Nothing downstream needs to change, and that is verifiable: `Scalars.java:121-123` builds its ordering rules from `Pure.nativeKeysAt("greaterThanEqual")`, which is a BARE-NAME index (`Pure.nativeFunctionsAt`, Pure.java:1022-1030) and so picks the lite overload up automatically; `Substitution.java:1089-1096` uses `Pure.nativeNamed("greaterThanEqual", key)` which reads the same bare-name index (Pure.java:1016); `ScanRelations.java:1852` / `:2467` match on `simple(bf.function())`, the bare name. `Scalars.dateArg` (Scalars.java:2992-3003) passes a non-CDate operand through untouched, so the String literal lowers to a bare `'9999-01-01'` — byte-identical to the engine golden.
DO NOT instead (a) add Date/String overloads under `meta::pure::functions::boolean::*` — that invents upstream vocabulary and loosens user-Pure typing the engine rejects; or (b) coerce the String literal to a `CStrictDate` at translation time — it would render `DATE'9999-01-01'` and break the exact-SQL golden at line 34.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-grammar/src/main/java/org/finos/legend/engine/language/pure/grammar/from/RelationalParseTreeWalker.java:823 — `else if (ctx.GREATER_OR_EQUAL() != null) { return "greaterThanEqual"; }`, the funcName of a `DynaFunc` whose `parameters` are untyped `RelationalOperationElement`s (visitAtomicOperation, lines 780-794). No Pure overload resolution is ever applied to a join condition.

**Risk** — Every relational join/filter/view ordering comparison in the corpus re-routes through the new shim, so a lowering or lineage path that keys on the *fqn* rather than the bare name would silently stop matching. Grep for `meta::pure::functions::boolean::lessThan`-style fqn literals before landing. Tenet-2 trap: do not make this go away in the harness (skipping the test, or pre-coercing the corpus literal) — the shape is owned by the normalizer/typer boundary. Also note the fix is NECESSARY BUT LIKELY NOT SUFFICIENT for this test: once the typer is unblocked the test still has to match a long exact golden SQL for a bi-temporal union with a self-join (quoted `"lake_from_0"/"lake_thru_0"` suffixed milestoning columns, `unionBase` aliasing, per-arm null padding). Expect it to re-fail one layer down as a golden-text/structure mismatch.

**Also unblocks** — testBiTemporalUnionJoin_milestoningColumnInOnClause and testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting (same unit). Outside this unit, any test that touches `meta::relational::tests::mapping::join`'s `TypeBuiltOutOfMultipleJoins` set: advancedRelationalSetUp.pure:52 `Join TypeTableTableBThen (... TypeTableB.IN_Z <= '2013-07-15 13:52:22.370' and '2013-07-15 13:52:22.370' < TypeTableB.OUT_Z)` compares a TIMESTAMP column with a String in BOTH operand orders, and it is wired as a Join PM at advancedRelationalSetUp.pure:202 — it must hit the identical wall today.

**Falsifier** — Print the full (untruncated) argument list of the failing overload-resolution error. If the second `ExprType` is not `STRING` — e.g. if it is `STRICT_DATE` with multiplicity `[*]` — the cause is a many-valued milestoning column read, not a Date-vs-String vocabulary gap, and this whole diagnosis is wrong. (The message is truncated at `ExprType[type=STRICT_DATE, mul` in the sweep, which is the one thing I could not read directly.)

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:819 — `throw new TypeInferenceException("no overload of '" + name + "' structurally matches the argument types (" + detail + ")" + cands);` — the exact message in the sweep
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:69 — wraps a TypeInferenceException as `"in function '" + fn.qualifiedName() + "': "`, which is why the message names the `$class$` synth fqn
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:509 — the Comparison arm builds `new AppliedFunction(comparisonFn(cmp.op()), List.of(side.apply(cmp.left()), side.apply(cmp.right())))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:587 — `case GTE -> "greaterThanEqual";` (the real-Pure name, not a shim)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:571 — `literalToValueSpec`: `if (value instanceof String s) return new CString(s);` — the join's date-shaped literal stays a Pure String
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/StoreCompiler.java:191 — `case RelationalDataType.Date_ d -> Type.Primitive.STRICT_DATE;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1318 — greaterThanEqual overloads are Date/Date, Number/Number, String/String, Boolean/Boolean only (lines 1318-1329, 1711); no mixed pair exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:1046 — `return a.equals(f) ? 2 : (ctx.isSubtype(a, f) ? 1 : -1);` — String actual vs Date formal scores -1
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/AssociationSynthesis.java:156 — `if (join.joins().size() < 2 && !routedUnion) { continue; // single-hop -> predicate path }` — the 2-hop Account_Owner ends inject as class Join PMs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:371 — `AssociationSynthesis.collectPairAssociationEntries(...)` merges the pair Join PMs into each union member set, so the join lands in `$class$BiTemporalPerson`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnionBiTemporalSelfJoinDuplicateColumn.pure:207 — `and OLAP.CUSTOMER.lake_thru >= '9999-01-01'` inside Join AccountRole_To_Party (lake_thru is `DATE`, line 103)
- /Users/neemsandv/legend/legend-engine/.../testUnionBiTemporalSelfJoinDuplicateColumn.pure:34 — the golden SQL contains `"customer_1".lake_thru > '9999-01-01'`: the engine renders the literal as a bare SQL string, never a DATE literal

</details>

---

## `testPksWithImportDataFlow`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Two layers. (a) The immediate wall: legend-lite registers exactly two 5-arg `execute` shapes — `(f[1], mapping:Any[1], runtime:Any[1], extensions:Any[*], debug:Any[1])` under both `meta::pure::mapping::execute` and `meta::pure::router::execute` (Pure.java:1545, 1552). Real Pure has a SECOND 5-arg shape with the ExecutionContext in slot 4: `execute<T|y>(f, m, runtime, exeCtx:ExecutionContext[1], extensions:Extension[*])` (router_entry.pure:25). The corpus call `execute(|..., unionMapping, testRuntime(), $execCtx, relationalExtensions())` therefore binds `relationalExtensions()` (declared `Extension[*]`, extension.pure:62) to `debug:Any[1]`. Because the call has a deferred lambda argument, resolution runs through `Typer.bindDeferredAndBuild`, whose `kernel.unifyMult` at Typer.java:1562 throws `expected at most one value, got many ([*])` (InferenceKernel.java:601), and the retry loop rethrows it RAW at Typer.java:1549 — which is exactly why the sweep shows the bare message with neither the `in call to '…'` prefix (InferenceKernel.java:919) nor the `in function '…'` prefix (SpecCompiler.java:69; test bodies are typed via the unwrapped `typeQueryBody`, SpecCompiler.java:185). (b) The real gap behind it: `importDataFlow` is an unimplemented execution-context feature. legend-lite has no typed exeCtx argument at all — the one option it supports (`addDriverTablePkForProject`) rides a ThreadLocal, and DriverPkOption.java:9-14 says so explicitly ("the engine carries this on the RelationalExecutionContext argument of execute(); … the option travels run-scoped here … Replace with the typed exeCtx argument once the metamodel family loads").

**Fix**

Two steps, in this order.
(1) Vocabulary (XS): in `core/src/main/java/com/legend/builtin/Pure.java` next to the existing execute natives (lines 1544-1552) register the exeCtx overloads for BOTH fqns — `native function meta::pure::mapping::execute<T>(f:...Function<{->T[*]}>[1], mapping:Any[1], runtime:Any[1], exeCtx:meta::pure::runtime::ExecutionContext[1], extensions:Any[*]):Result<T>[1];` and the identical `meta::pure::router::execute` spelling (and, for completeness, the 6-arg `(…, exeCtx, extensions, debug)` form of router_execution.pure:29). Typing slot 4 as `ExecutionContext[1]` rather than `Any[1]` keeps it unambiguous against the existing `(Any[*], Any[1])` debug form in both directions. `StatementExecutor.buildFrame` only indexes args 0-2 (query/mapping/runtime), so it needs no arity change.
(2) Behaviour (L): implement `importDataFlow` — thread the typed exeCtx into `ExecEnv` (replacing the DriverPkOption ThreadLocal per its own TODO), and in the union lowering rename the per-arm PK aliases from `pk_<pkIndex>_<setIndex>` to `<ColumnName>_<setIndex>` and project them (plus FKs when `importDataFlowAddFks`) into the result TDS, mirroring pureToSQLQuery_union.pure:662-680.
If only (1) is landed, (2) MUST be an explicit wall: throw a NotImplementedException naming `importDataFlow` when the exeCtx carries it, rather than silently ignoring the flag. Silently dropping it yields a syntactically valid query whose SQL differs from the engine's — wrong-shape rows behind a green-looking pipeline.

**How legend-engine does it** — router_entry.pure:25 (the exeCtx execute overload) and pureToSQLQuery_union.pure:674 + :187 (`let propertyMappingsInScope = if ($state.importDataFlow == true, ...`) — importDataFlow makes the union project every set's PK under `<ColumnName>_<setIndex>` and adds FK columns, which is precisely the `ID_0`/`ID_1` the assert reads.

**Risk** — Adding the overload without the feature converts a clear typer wall into a confusing `getInteger('ID_0')`/column-not-found failure, and — worse — silently ignores `importDataFlow` for any OTHER query that sets it, where the row values could look right while the SQL diverges. That is the tenet-1 violation to avoid; pair the overload with the explicit wall. Tenet-2 trap: do not make the harness special-case `^RelationalExecutionContext` or synthesize `ID_0`/`ID_1` columns in the test runner — the union PK naming is owned by the lowering.

**Also unblocks** — Step (1) alone unblocks the typer for any corpus test passing an ExecutionContext to execute — notably tests/mapping/union/relation/testRelationUnion.pure:220 (`let execCtx = ^RelationalExecutionContext(importDataFlow = true, importDataFlowAddFks = true);`), which the corpus itself marks 'ToFix' at line 213. Step (2) is what those tests actually need.

**Falsifier** — Add ONLY the exeCtx `execute` overload and re-run. If the error is still `expected at most one value, got many ([*])`, the failing call is not `execute` and the diagnosis is wrong; if it becomes a missing-column/`ID_0` failure, the diagnosis is confirmed and only layer (b) remains.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1545 — `EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY__ANY_1 = ... execute<T>(f, mapping:Any[1], runtime:Any[1], extensions:Any[*], debug:Any[1])` — slot 4 is `Any[*]`, slot 5 is `Any[1]`; there is no exeCtx form
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1552 — the router spelling has the same two shapes only
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:601 — `throw new TypeInferenceException("expected at most one value, got many (" + actual.text() + ")");` — the exact sweep message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1562 — `kernel.unifyMult(chosen.parameters().get(i).multiplicity(), typed[i].info().multiplicity(), ...)` inside `bindDeferredAndBuild`, and Typer.java:1549 `throw java.util.Objects.requireNonNull(firstFailure);` — rethrown unwrapped, matching the prefix-less message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:185 — `typeQueryBody` types test-function statements with no `in function` wrapper (contrast SpecCompiler.java:69)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/validation/DriverPkOption.java:9 — "the engine carries this on the RelationalExecutionContext argument of execute(); … the option travels run-scoped here … Replace with the typed exeCtx argument"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:238 — `meta::pure::runtime::ExecutionContext` IS registered as a native class, so the new parameter can be typed faithfully
- /Users/neemsandv/legend/legend-engine/.../core/pure/router/router_entry.pure:25 — `function meta::pure::router::execute<T|y>(f:FunctionDefinition<{->T[y]}>[1], m:Mapping[1], runtime:Runtime[1], exeCtx:meta::pure::runtime::ExecutionContext[1], extensions:meta::pure::extension::Extension[*]):Result<T|y>[1]`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery_union.pure:674 — `let propName = if($state.importDataFlow == true, | '"'+$pkCol.column.name+'_'+$setIndex->toString()+'"', | '"pk_'+$pkIndex->toString()+'_'+$setIndex->toString()+'"');` — this is what produces the `ID_0`/`ID_1` columns the test reads
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/runtime/executionContext/executionContext.pure:31 — `importDataFlow : Boolean[0..1];` / `:32 importDataFlowAddFks : Boolean[0..1];` on RelationalExecutionContext

</details>

---

## `testDayOfWeekNumberFunction`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

legend-lite's native catalog carries only the 1-arg `meta::pure::functions::date::dayOfWeekNumber(Date[1]):Integer[1]` (Pure.java:1193). The test calls the 2-arg form `$t.date->dayOfWeekNumber(DayOfWeek.Monday)` / `(DayOfWeek.Sunday)`, which in the engine is a separate, Pure-defined overload with a constraint. With no arity-2 candidate, InferenceKernel.resolveOverload's arity filter empties and it throws the exact 'accepts 2 argument(s)' message. Notably the LOWERING half already exists and is correct: RelOpTranslator.java:231-261 handles the 2-arg dyna spelling and desugars it to the engine's semantics (Monday → the isodow-based 1-arg form; Sunday → mod(isodow,7)+1), and Scalars.java:400-408 lowers the 1-arg native to EXTRACT('isodow', …). Only the PURE-surface overload and its lowering rule are missing — the relational-mapping surface is covered.

**Fix**

Two edits. (1) core/src/main/java/com/legend/builtin/Pure.java, next to line 1193, add `DAY_OF_WEEK_NUMBER__DATE_1__DAY_1 = signature("native function meta::pure::functions::date::dayOfWeekNumber(day:meta::pure::metamodel::type::Date[1], firstDay:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Integer[1];")` — the DayOfWeek enum is already registered (Pure.java:692). (2) core/src/main/java/com/legend/lowering/Scalars.java:400-408: change the existing EXTRACT-isodow registration to iterate `Pure.nativeKeysAt("dayOfWeekNumber", 1)` so it no longer captures the new overload, and add a second loop over `Pure.nativeKeysAt("dayOfWeekNumber", 2)` whose rule reads `enumName(n.args().get(1))` and emits: for 'Monday', `EXTRACT('isodow', dateArg(args[0]))`; for 'Sunday', `plus(mod(EXTRACT('isodow', dateArg(args[0])), 7), 1)`; anything else, throw NotImplementedException naming the engine's own constraint (firstDayMondayOrSundayOnly). That is a byte-for-byte match of the logic already written in RelOpTranslator.java:240-261, so factor it into one shared helper rather than duplicating.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/date/extract/dayOfWeekNumber.pure:17-24

**Risk** — Two follow-on hazards, neither a reason to withhold the fix. (a) Golden SQL will differ — the corpus expects H2's `extract(dow from …)` / `extract(isodow from …)`; legend-lite emits `mod(extract('isodow', …), 7) + 1` for the Sunday form. That is advisory (EngineTestExecutor.java:1834-1835) and the ROW values are what matter: mod(isodow,7)+1 gives Monday→2, Wednesday→4, Thursday→5, Friday→6, exactly the golden CSV. (b) The test's filter is `dayOfWeekNumber($t.date, DayOfWeek.Monday) != '2'` — an Integer compared to a STRING. Verify that legend-lite's equal lowering coerces this the way DuckDB does, or the filter will drop the wrong rows; if it does not, that is a SEPARATE defect to file, not something to paper over here.

**Also unblocks** — Nothing else in this corpus — docs/RELATIONAL_CORPUS_ALL.md:1530 is the only failing test walled on dayOfWeekNumber arity.

**Falsifier** — Add only the signature from step (1) and re-run. If the typer error disappears and the next failure is a lowering error (the arity-blind EXTRACT rule reaching for a single arg), the two-part diagnosis is confirmed. If the typer still says 'accepts 2 argument(s)', the enum parameter type is not resolving and the diagnosis is wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1193 — DAY_OF_WEEK_NUMBER__DATE_1 is the ONLY dayOfWeekNumber signature registered
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:786-790 — `if (arityMatches.isEmpty()) { throw new TypeInferenceException("no overload of '" + name + "' accepts " + args.size() + " argument(s)"); }`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/date/extract/dayOfWeekNumber.pure:15-24 — `native function …dayOfWeekNumber(d:Date[1]):Integer[1];` plus `function …dayOfWeekNumber(day:Date[1], firstDay:DayOfWeek[1]):Integer[1] [firstDayMondayOrSundayOnly : $firstDay->in([DayOfWeek.Monday, DayOfWeek.Sunday])] { if($firstDay==DayOfWeek.Sunday, | dayOfWeekNumber($day)->mod(7)+1, | dayOfWeekNumber($day)); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/RelOpTranslator.java:231-261 — the 2-arg dyna arm already implements exactly that desugaring, with the comment 'the pure native lowers to isodow (Monday=1), so Sunday-based forms conform by emission: mod(isodow, 7) + 1'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:400-408 — dayOfWeekNumber → `EXTRACT('isodow', dateArg(args[0]))`, registered over `Pure.nativeKeysAt("dayOfWeekNumber")` (arity-blind)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:483-491 — the mostRecentDayOfWeek/previousDayOfWeek precedent: one RULES registration serving both the 1-arg and 2-arg overloads, arity-dispatched inside the lambda via enumName(n.args().get(…))
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:965-973 — `nativeKeysAt(String name, int arity)` exists precisely for arity-selective lowering registration

</details>

---

## `testToSQLStringWithAggregation`

| | |
|---|---|
| family | `transform/fromPure/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | L (revised up from S by adversarial review) |
| confidence | high |
| **adversarial review** | **PARTIALLY_WRONG** |

**Root cause**

Typer.classReference gates its whole mangled-signature-id handling behind `if (fns.size() > 1)`, so the deliberate zero-candidate fallback inside that block is unreachable. Walking it for this name: the corpus's ^TestCase carries `generateUsageFor = [meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_]`. functionCandidates(fullPath) misses on the literal name, then demangles: stripTail gives `meta::pure::tds::groupBy`, tailArity gives 3, tailReturnTypeName gives 'TabularDataSet', and the filter requires BOTH `parameters().size()==3` AND `returnType().typeName().endsWith("TabularDataSet")`. legend-lite's two `meta::pure::tds::groupBy` overloads are both arity 3 but return `meta::pure::metamodel::relation::Relation<Z+R>`, whose typeName renders as 'Relation<…>' — so the return-name guard rejects both and fns comes back EMPTY. Empty is not > 1, so the block that would have produced the opaque `Function<Any>[1]` reference is skipped, `fns.size()==1` is false, and the method falls through to the ResolutionException. The code's own comment at Typer.java:2260-2266 describes exactly this case ('a mangled id naming an overload we don't carry standalone (the legacy TDS groupBy the checker desugars at call sites): the REFERENCE is an opaque Function<Any> value') — the intent is right, the guard is wrong. legend-lite has no standalone TDS-era groupBy because GroupByChecker desugars that spelling at call sites into the modern colspec form (GroupByChecker.java:44-54,138-172), which is a legitimate design choice; the reference is pure metadata and is never invoked.

**Fix**

In core/src/main/java/com/legend/compiler/spec/Typer.java:2246-2270, hoist the mangled handling out of the `fns.size() > 1` guard so the zero-candidate case reaches the fallback the comment already describes. Concretely: compute `int arity = SignatureMangle.tailArity(ref.fullPath());` unconditionally; enter the block when `arity >= 0 && fns.size() != 1`; inside, keep the existing `byArity.size() == 1 -> fns = byArity` narrowing; and in the else branch return the opaque `TypedPackageableRef(ref.fullPath(), ExprType.one(new Type.GenericType("meta::pure::metamodel::function::Function", List.of(InferenceKernel.anyType()))))` ONLY when `!ctx.findFunction(SignatureMangle.stripTail(ref.fullPath())).isEmpty()` — i.e. the demangled BASE name really exists in the catalog. That preserves loudness for a genuinely misspelled or absent function id (the base lookup fails and the ResolutionException still fires) while accepting a mangled id that names a real function under a signature this platform spells differently. That base-exists guard is the whole safety property; do not drop it.

**⚠ Correction from adversarial review** — The Typer edit as written is correct and will advance the wall, but it is not the fix for this test. Re-scope: label this 'wall-advance only, effort XS for the Typer change' and open the real item separately — constant-folding ^TestCase construction + filter + toOne so `$testCase.query` yields a TypedLambda and `$testCase.mapping` a TypedPackageableRef at StatementExecutor.java:379-388 (per the bucket doc's own tenet-2 note, that folding belongs in the spec compiler, not the harness). Also, when hoisting, keep the entry condition as `arity >= 0 && fns.size() != 1` (never `fns.isEmpty()` alone) and keep the base-exists guard, and be aware it relaxes the §1.1 #4 loudness for any mangled id whose base exists but whose tail does not round-trip.

<details><summary>Adversarial review notes (PARTIALLY_WRONG)</summary>

The MECHANISM is fully confirmed — every citation is accurate and the wall is exactly where claimed. Typer.java:2248-2249 is `List<TypedFunction> fns = functionCandidates(ref.fullPath()); if (fns.size() > 1) {`, and the opaque-Function return at 2266-2269 is genuinely inside that guard, so a zero-candidate result cannot reach it. functionCandidates (2143-2163) does demangle-then-filter on BOTH `parameters().size() == arity` AND `returnType().typeName().endsWith(ret)`. SignatureMangle parses the id to base `meta::pure::tds::groupBy`, tailArity 3, tailReturnTypeName 'TabularDataSet'. Pure.java:1350-1351 are the only two `meta::pure::tds::groupBy` registrations in the whole tree (grep confirms) and both return `Relation<Z+R>[1]`; Type.GenericType.typeName() (Type.java:296-315) renders `Relation<...>`, so endsWith("TabularDataSet") is false and fns is empty. Typer.java:2294 is the ONLY site emitting that message (grep), and docs/RELATIONAL_CORPUS_ALL.md:1548 records the observed wall verbatim with that exact mangled id. So the root cause is right.

What is WRONG is the packaging: V09 presents this as REAL_DEFECT / effort S with no caveat, but the fix does not make the test green and cannot. I confirmed the second wall exists (StatementExecutor.java:379-388) and that `databaseTypeOf` returns "H2" for a non-connection arg (StatementExecutor.java:1149-1152), so the H2 renderer is selected and the run does reach line 379 — i.e. the falsifier's prediction is sound, but it lands on ANOTHER NotImplementedException. Behind that wall the test needs: evaluating `^TestCase(...)` literals, `filter`+`toOne` over them, and recovering a TypedLambda literal and a TypedPackageableRef out of `$testCase.query` / `$testCase.mapping` property reads. The harness path (Runner.java:1307-1315 tryRunNoExecute) will simply re-report SHAPE with a new wall string. Notably, the source bucket doc (docs/e2e-diagnosis-2026-08-15/bucket-09.md, 'Risk' paragraph) says this explicitly — 'This fix ADVANCES the wall; it does not by itself make the test pass' — and V09.md DROPPED that paragraph. A work plan built on V09 as written would budget S for a green that costs L+.

Secondary: the proposed base-exists guard is safe from NPE (arity>=0 implies stripTail non-null) but it does weaken the audit §1.1 #4 loudness property the code was built around: after the change, any mangled id whose demangled base exists but whose arity/return do not round-trip (e.g. `compute_Step_2_` where `compute` exists) types as an opaque Function<Any> instead of raising ResolutionException. That is a deliberate trade the diagnosis names, but it is a behaviour change on a shared path (classReference is the reference-as-value path for every packageable element), not a contained one. I could not run the suite to see whether any green test depends on that loudness.

</details>

**Risk** — This fix ADVANCES the wall; it does not by itself make the test pass. runTestCaseById builds a list of ^TestCase instances, filters it by id, calls toOne(), and passes `$testCase.query` / `$testCase.mapping` — property reads off a class instance — into toSQLString. StatementExecutor.toSqlString requires arg0 to be a TypedLambda literal and arg1 to be a TypedPackageableRef (StatementExecutor.java:379-388), so a second, larger piece of work (constant-folding the instance construction + filter + toOne so the literal lambda and mapping reference are recovered) is needed for a green. The tenet-2 trap: do not make the harness reach into the corpus source to pluck the lambda out of the ^TestCase literal — the folding, if built, belongs in the spec compiler, not the test runner. Broader risk of the resolution change itself is low but real: any mangled id whose base name exists will now type as an opaque Function<Any> instead of erroring, so a mangled id with a WRONG signature tail becomes silent at the reference site (it stays loud at any invocation site).

**Also unblocks** — testToSQLStringWithAbs (transform/fromPure/tests) — docs/RELATIONAL_CORPUS_ALL.md:1547 shows it failing with the identical message, because testCasesForDocGeneration() builds both ^TestCase literals and the groupBy generateUsageFor id is compiled for either entry point.

**Falsifier** — Apply the change and re-run. If the wall moves to 'toSQLString whose query argument is not a lambda literal' (StatementExecutor.java:382), the resolution diagnosis is confirmed and the remaining blocker is the second layer. If it still reports 'is not a known class, mapping, runtime, connection, or database', then fns was not empty and the return-name filter is not the rejecting predicate.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2246-2296 — classReference: `List<TypedFunction> fns = functionCandidates(ref.fullPath()); if (fns.size() > 1) { … mangled handling, including the opaque-Function return … } if (fns.size() == 1) { … } throw new ResolutionException("'" + ref.fullPath() + "' is not a known class, mapping, runtime, connection, or database")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2143-2162 — functionCandidates(String): on a miss it demangles and filters `f.parameters().size() == arity && f.returnType().typeName().endsWith(String.valueOf(ret))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SignatureMangle.java:28-59 — the tail grammar and tailArity (segments minus the return); the id parses to base `meta::pure::tds::groupBy`, arity 3, return 'TabularDataSet'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1350-1351 — both `meta::pure::tds::groupBy` overloads return `meta::pure::metamodel::relation::Relation<Z+R>[1]`, never anything named TabularDataSet
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/Type.java:299-315 — GenericType.typeName() renders 'Relation<…>', so endsWith("TabularDataSet") is false
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/GroupByChecker.java:44-54 and 138-172 — the TDS-era 3-arg groupBy is desugared at the call site into the modern ColSpecArray form, which is why no standalone overload exists
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/transform/fromPure/tests/testToSQLString.pure:44-52 — the ^TestCase whose generateUsageFor holds the mangled id; :77-84 runTestCaseById; :92-95 the test
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:379-388 — the SECOND wall this test will hit: `throw new NotImplementedException("toSQLString whose query argument is not a lambda literal")` and `"toSQLString mapping argument must be a mapping reference"`

</details>

---
