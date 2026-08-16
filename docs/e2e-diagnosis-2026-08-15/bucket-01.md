# Bucket 1 — Engine self-metamodel (Pure-implemented compiler internals)

23 tests from the ledger; **23 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: TESTS ENGINE INTERNALS 16, MISSING FEATURE 6, REAL DEFECT 1

---

## `testClassesAssociationsAndMappingFromDatabase`

| | |
|---|---|
| family | `autogeneration/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

Same throw site as tests 1/2 — `NewChecker.check` at NewChecker.java:68 — but for `meta::protocols::pure::vX_X_X::metamodel::PureModelContextData`, which the test body constructs directly. legend-lite has a JAVA-side protocol model (`com.legend.protocol.Protocol.PureModelContextData`, Protocol.java:34, emitted by ProtocolEmitter.java:44) but carries NO Pure-level `meta::protocols::pure::vX_X_X::*` metamodel and no `meta::protocols::pure::vX_X_X::transformation::fromPureGraph::*` transformers. It cannot acquire them by demand-pull either: the class is defined OUTSIDE the relational corpus tree, in legend-engine-core, and the Runner only registers the `core_relational/relational` tree plus `core/store/m2m/tests` (Corpus.java:48-60), so the FQN never enters `elementSource` and `unknownTypePull` (Runner.java:1191-1193) returns null. The test's subject — `meta::relational::transform::autogen::classesAssociationsAndMappingFromDatabase` — is itself an engine Pure code generator that builds protocol elements and serialises them via `meta::alloy::metadataServer::alloyToJSON()`; legend-lite implements none of it, and the assert is a JSON string equality against engine's own protocol serialisation.

**Fix**

Do not fix; ledger it. Passing this test requires porting engine's entire vX_X_X protocol metamodel (hundreds of Pure classes across `core/pure/protocol/vX_X_X/models/*` plus `metamodel_relational.pure`), the `fromPureGraph` transformers for Class/Association/Mapping, the `alloyToJSON` serialiser, and the whole `meta::relational::transform::autogen::*` generator — and then matching engine's JSON byte-for-byte through `assertJsonStringsEqual`. That is a parallel implementation of engine's protocol layer, not a defect in legend-lite's query pipeline. If a relational-to-Pure autogeneration surface is ever wanted, it belongs as a Java generator over `com.legend.model.DatabaseDefinition` emitting `com.legend.protocol.Protocol.PureModelContextData` via the existing `ProtocolEmitter` — and this corpus test still would not exercise it, because the test's EXPECTED side is itself built from engine Pure transformers.

**How legend-engine does it** — The constructed class is engine Pure at /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/protocol/vX_X_X/models/executionPlan.pure:322 — `Class meta::protocols::pure::vX_X_X::metamodel::PureModelContextData extends meta::protocols::pure::vX_X_X::metamodel::PureModelContext`. The function under test is /Users/neemsandv/legend/legend-engine/…/core_relational/relational/autogeneration/relationalToPure.pure:339 `classesAssociationsAndMappingFromDatabase(database, targetPackage)` → `getAllDatabases` → `generateModelsFromDatabase` (:351, returning `meta::protocols::pure::vX_X_X::metamodel::m3::PackageableElement[*]`) → `buildPMCD` (:361-370, `^PureModelContextData(_type='data', serializer=^meta::protocols::Protocol(name='pure', version='vX_X_X'), elements=$elements)->meta::alloy::metadataServer::alloyToJSON()`).

**Falsifier** — Show that `meta::protocols::pure::vX_X_X::metamodel::PureModelContextData` is reachable from the Runner's registered source roots. If a probe finds it in `elementSource`, the demand-pull retry should already have fired and my "cannot be pulled" claim is wrong — in that case the verdict softens to MISSING_FEATURE on the transformer functions.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/NewChecker.java:68 — the `unknown class '…' in ^…(…)` throw; the failure detail names `meta::protocols::pure::vX_X_X::metamodel::PureModelContextData`
- core/src/main/java/com/legend/protocol/Protocol.java:34 — `public record PureModelContextData(List<Element> elements)` — legend-lite's protocol model is a Java record, not a Pure class
- core/src/main/java/com/legend/protocol/ProtocolEmitter.java:44 — `public static String emit(PureModelContextData pmcd)` — the Java emitter; no `alloyToJSON` Pure surface exists (grep for `alloyToJSON`, `transformMapping`, `transformAssociation`, `vX_X_X` across core/src/main returns only these Java protocol hits)
- core/src/test/java/com/legend/rcorpus/Corpus.java:48-60 — `RELATIONAL` resolves to the `core_relational/relational` tree and `M2M_TESTS` to `core/store/m2m/tests`; the vX_X_X protocol tree is under neither
- core/src/test/java/com/legend/rcorpus/Runner.java:1191-1193 — a qualified unknown name only demand-pulls when `elementSource.containsKey(name)`
- core/src/main/java/com/legend/builtin/Pure.java — a grep of the whole native catalogue finds no `meta::protocols::` class or function declaration at all

</details>

---

## `testRoutingContextBuilderFunctions`

| | |
|---|---|
| family | `executionPlan/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

Two-layer message, both parts explained by one cause. The inner `plan wall: class meta::pure::metamodel::type::Any has no property 'type'` is thrown by `Typer` at Typer.java:2566 (`throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")`) while typing `relationalExtensions()->filter(e|$e.type == 'relational')`. It says `Any` because legend-lite declares `relationalExtensions()` as returning `meta::pure::metamodel::type::Any[*]` — Pure.java:1582, with the comment at :1578-1581 stating outright that the native "exists for TYPING the context argument of toSQLString/execute calls; it is never evaluated". And even if it returned `Extension[*]`, `Extension` is declared PROPERTY-FREE (Pure.java:247, `native Class meta::pure::extension::Extension {}`), so `$e.type` would fail identically. The outer `assert form 'assertEquals/2' is not supported yet` is just the scoring wrapper: `PlanAsserts.planTextAssert` catches the LegendCompileException and returns `unsupported("plan wall: " + …)` (PlanAsserts.java:188-198), and `EngineTestExecutor.scoreAssert` (EngineTestExecutor.java:878-894) stamps the assert form around it. The test's real subject is engine's router: it copy-updates a `StoreContract` with a new `routeFunctionExpressions` pair, rebuilds the extension list, and asserts the golden `planToString` text produced by routing through that custom contract. legend-lite has no extension registry, no StoreContract, and no Pure router.

**Fix**

Do not fix; ledger it. Making this test pass means (a) declaring `Extension` with its real property surface including the Function-typed hook slots, (b) declaring `StoreContract` with `id` and `routeFunctionExpressions`, (c) making `relationalExtensions()` return a real, EVALUABLE registry value rather than a typing placeholder, and then (d) having the plan builder actually consult a user-supplied `routeFunctionExpressions` handler during routing and reproduce engine's exact `planToString` text. (d) is the load-bearing part and it means implementing engine's Pure router extension dispatch. The `Any[*]` return type at Pure.java:1582 is a deliberate, documented decision, not an oversight — do not widen it piecemeal, because every `execute(…, relationalExtensions())` call in the corpus currently relies on it typing loosely.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/extensions/extension.pure:29-31 — `Class meta::pure::extension::Extension { type : String[1]; availableStores : StoreContract[*]; … }`, plus `availableFeatures`, `routerExtensions()`, `executionPlanExtensions()`, and ~10 Function-typed hook slots (:56-80). That whole registry, and `meta::pure::router::routing::routeFunctionExpressionFunctionDefinition` which the test's new pair calls, is the plug-in mechanism of engine's Pure router.

**Risk** — Tenet-2 trap: it is tempting to make `EngineTestExecutor`/`PlanAsserts` recognise the `relationalExtensions()->filter(e|$e.type == …)` shape and substitute a canned extension list. That is harness compensation for a platform concept (the extension registry) and would produce a green row while legend-lite still ignores the custom route handler the test installs — i.e. a wrong pass. The loud SHAPE wall is correct here.

**Falsifier** — Change `RELATIONAL_EXTENSIONS__ANY_MANY` (Pure.java:1582) to return `meta::pure::extension::Extension[*]` and add `type: String[1]` to `EXTENSION` (Pure.java:247). If the test then progresses past the `type` property, my claim that the registry must be evaluable is confirmed only when the NEXT wall names `availableStores` / `routeFunctionExpressions` / `filter` over a non-evaluable value. If instead the plan text matches, I am wrong and this is a small MISSING_FEATURE — but that would also mean legend-lite silently ignored the custom routing contract, which would itself be a REAL_DEFECT.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/builtin/Pure.java:1578-1582 — `// relationalExtensions(): the corpus's own definition is signature-broken in this platform (the Extension metamodel class), so it never enters the module — this native exists for TYPING the context argument of toSQLString/execute calls; it is never evaluated.` followed by `RELATIONAL_EXTENSIONS__ANY_MANY = signature("native function meta::relational::extension::relationalExtensions():meta::pure::metamodel::type::Any[*];")`
- core/src/main/java/com/legend/builtin/Pure.java:247 — `EXTENSION = nativeClass("native Class meta::pure::extension::Extension {}")` — no properties at all; the comment at :244-246 says corpus SIGNATURES name it "even where the value only ever passes through"
- core/src/main/java/com/legend/compiler/spec/Typer.java:2566 — `throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")` — matches the observed `class meta::pure::metamodel::type::Any has no property 'type'`
- core/src/main/java/com/legend/harness/PlanAsserts.java:188-198 — `catch (NotImplementedException | LegendCompileException | UnsupportedOperationException pw) { … return EngineTestExecutor.unsupported("plan wall: " + pw.getMessage()); }` with the comment "the PLAN surface is a pending vocabulary — its typing/resolution walls are SHAPE, scoped to plan asserts only"
- core/src/main/java/com/legend/harness/EngineTestExecutor.java:878-894 — `scoreAssert`: when a platform reason exists it becomes the PRIMARY message, else `"assert form '" + af.function() + "/" + af.parameters().size() + "' is not supported yet"`
- core/src/main/java/com/legend/builtin/Pure.java — no `StoreContract`, `routeFunctionExpressions`, `availableStores`, or `RoutingState` declaration exists anywhere in the native catalogue

</details>

---

## `dropAndCreateTempTable`

| | |
|---|---|
| family | `helperFunctions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Same first wall as `testCreateTempTableStatement`, from the same line: the body constructs `^Column(name='col', type=^meta::relational::metamodel::datatype::Integer())` and `NewChecker.check` (NewChecker.java:68) throws `unknown class 'meta::relational::metamodel::datatype::Integer'`. Beyond that shared wall this test needs strictly more than test 2: the natives `meta::relational::metamodel::execute::createTempTable(tableName, cols, sqlFn, connection)` and `dropTempTable(tableName, connection)` do not exist in legend-lite at all (a grep for `createTempTable`/`dropTempTable` across `core/src/main` returns zero hits, while sibling DDL natives `createTableStatement`/`dropTableStatement` ARE declared at Pure.java:1652-1657 and implemented in `com.legend.exec.Ddl`). The rest of what the test needs already exists: `executeInDb` is a K-native (StatementExecutor.java:3009-3014, 3273), and `ResultSet.columnNames` is declared (Pure.java:500) and evaluated (HostEval.java:714).

**Fix**

Do steps 1 and 2 from `testCreateTempTableStatement` first (they are a hard prerequisite), then:
3. `core/src/main/java/com/legend/builtin/Pure.java` — declare, next to the existing toDDL natives at :1652, `native function meta::relational::metamodel::execute::createTempTable(tableName:…String[1], cols:meta::relational::metamodel::Column[*], sql:meta::pure::metamodel::function::Function<{…String[1], meta::relational::metamodel::Column[*], meta::relational::runtime::DatabaseType[1]->…String[1]}>[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1]):meta::pure::metamodel::type::Nil[0];`, its 5-arg `relyOnFinallyForCleanup` overload, and `dropTempTable(tableName:String[1], databaseConnection:DatabaseConnection[1]):Nil[0]` — copying legend-pure functions.pure:44-48 verbatim.
4. `core/src/main/java/com/legend/StatementExecutor.java` — add K-native arms beside `executeInDb` (line ~3009): `createTempTable` evaluates its `sql` function argument with (tableName, cols, connection dbType) exactly as CreateTempTable.java:73 does, then routes the produced statement through the SAME `executeInDb(...)` path (StatementExecutor.java:3273) so it lands in the RawSqlBoundary recording; `dropTempTable` routes `"drop table " + tableName` through the same path. Follow the ambient-connection doctrine already used by executeInDb — do not evaluate the connection argument.
Do NOT shortcut by rendering the CREATE TEMP TABLE text in Java and ignoring the `sql` lambda: the corpus function IS the thing under test and engine's own native evaluates it (CreateTempTable.java:73).

**How legend-engine does it** — Natives declared at /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/functions.pure:44-48 — `native function meta::relational::metamodel::execute::createTempTable(tableName:String[1], cols:Column[*], sql:Function<{String[1], Column[*], DatabaseType[1]->String[1]}>[1], databaseConnection:DatabaseConnection[1]):Nil[0];` (plus the 5-arg relyOnFinallyForCleanup overload and `dropTempTable`). The Java implementation is /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-runtime-java-extension-interpreted-store-relational/src/main/java/org/finos/legend/pure/runtime/java/extension/store/relational/interpreted/natives/CreateTempTable.java:56-79: it reads the connection's `type`, calls `executeLambdaFromNative(toSql, [tableName, columns, dbType])`, takes the resulting String and hands it to `ExecuteInDb.executeInDb`, then registers a drop-table cleanup listener.

**Risk** — `createTempTable` returns `Nil[0]` and is called in STATEMENT position; the effect-analysis in StatementExecutor (the `executeInDb-family effect` guards at :3061/:3086/:3131) classifies which expressions may carry effects. A new effectful native must be registered in that family or the surrounding `let`/`ignore` shapes will either double-execute it or refuse it. Also: DuckDB is the execution target — `Create LOCAL TEMPORARY TABLE tt(col INT);` is H2 syntax. The test's `DatabaseType` will be whatever legend-lite reports for its connection; if it reports H2 the statement must still run on DuckDB (DuckDB accepts `CREATE TEMPORARY TABLE` but not the `LOCAL` keyword), so this test may then land on a genuine EXECUTION_TARGET_ARTIFACT. That would be a second, separate finding — not a reason to bend the DDL text.

**Falsifier** — After steps 1-2 only, re-run. If the wall moves off `unknown class 'meta::relational::metamodel::datatype::Integer'` and onto an unknown-function message naming `createTempTable`, the chain is confirmed. If instead it lands on `Create LOCAL TEMPORARY TABLE` failing in DuckDB, the remaining gap is dialect, not vocabulary, and the verdict for the residue becomes EXECUTION_TARGET_ARTIFACT.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/NewChecker.java:68 — the throw that produces the observed `unknown class 'meta::relational::metamodel::datatype::Integer' in ^…(…)`
- core/src/main/java/com/legend/builtin/Pure.java:351 — only `datatype::DataType` is declared; no concrete datatype exists
- core/src/main/java/com/legend/builtin/Pure.java:1652-1657 — `DDL_CREATE_TABLE_STATEMENT__DB_1__STRING_1` / `DDL_DROP_TABLE_STATEMENT__DB_1__STRING_1` natives exist, establishing the pattern; there is no analogous `createTempTable`/`dropTempTable` declaration anywhere in the file
- core/src/main/java/com/legend/StatementExecutor.java:3009-3014 — `// K-NATIVE dispatch: executeInDb never lowers — it IS the phase-K` … `return executeInDb(body, nc, env);` — the executeInDb half of the test already works
- core/src/main/java/com/legend/builtin/Pure.java:500 — `RESULT_SET` native class declares `columnNames: meta::pure::metamodel::type::String[*]`
- core/src/main/java/com/legend/exec/HostEval.java:714 — `case "columnNames" -> new ArrayList<Object>(rs.columnNames());`
- core/src/test/java/com/legend/rcorpus/Runner.java:1307-1315 — the `no execute(|...) call [calls …] — wall: <attempted.wall()>` message shape that the failure detail matches, emitted after `tryRunNoExecute` returns a non-PASS/FAIL outcome

</details>

---

## `testCreateTempTableStatement`

| | |
|---|---|
| family | `helperFunctions/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

The test evaluates `^Column(name='col', type=^meta::relational::metamodel::datatype::Integer())`. `NewChecker.check` (core/src/main/java/com/legend/compiler/spec/NewChecker.java:67-70) does `t.model().findClass(ni.className())` and throws `unknown class '…' in ^…(…)` when empty. legend-lite's native prelude declares EXACTLY ONE class in that package — `meta::relational::metamodel::datatype::DataType` (Pure.java:351). None of the ~20 concrete CoreDataType subclasses (Integer, Varchar, Char, Date, Float, SemiStructured, …) exist, so the construction is unknown at G. The Runner's demand-pull retry cannot rescue it: `unknownTypePull` (Runner.java:1183-1194) only pulls FQNs present in `elementSource`, and the datatype classes live in legend-pure's platform tree, not in the corpus, so it returns null and the test scores SHAPE with the wall text. Behind that first wall two more gaps sit: (a) `StatementExecutor.constructNode` (StatementExecutor.java:1517-1610) has cases for QualifiedName/ColumnName/Alias/TableAlias/TableAliasColumn/TableAliasColumnName and a generic-node default, but NO `case "Column"` — a constructed `^Column(...)` has no host value; (b) `meta::relational::functions::toDDL::createTempTableStatement()` is not declared as a native and the corpus's own body returns a lambda that must then be `->eval`'d.

**Fix**

Three additive changes, all in the platform (none in the harness).
1. `core/src/main/java/com/legend/builtin/Pure.java` — beside `DATA_TYPE_METACLASS` (line 351), add `CORE_DATA_TYPE` (`native Class meta::relational::metamodel::datatype::CoreDataType extends meta::relational::metamodel::datatype::DataType {}`) and one `nativeClass(...)` per concrete type, mirroring legend-pure relational.pure:392-470 one-for-one: Boolean, BigInt, SmallInt, TinyInt, Integer, UnsignedBigInt, UnsignedInt, UnsignedTinyInt, Float, Double, Real, Bit, Timestamp, Date, Distinct, Other, SemiStructured (nullary); Varchar/Char/Varbinary/Binary with `size: Integer[1]`; Decimal/Numeric with `precision: Integer[1]; scale: Integer[1]`. Cite the relational.pure line for each, the file's existing convention.
2. `core/src/main/java/com/legend/StatementExecutor.java` — in `constructNode` (line 1517) add two arms: `case "Column"` building a value that carries name + datatype (reuse `MetamodelWalk.ColH` by giving it an optional `RelationalDataType`, or add a small `MkColH(String name, RelationalDataType type)` record next to `ColH` in MetamodelWalk.java:59), and a datatype arm that maps a constructed `meta::relational::metamodel::datatype::X` NewInstance to `new MetamodelWalk.Dt(new RelationalDataType.X_(...))` — reading the `size`/`precision`/`scale` ctor args as TypedCInteger. `MetamodelWalk.sqlText` (line 1546) then already answers `INT` for Integer_ with no change. Also make property navigation `$c.type` / `$c.name` on the new Column value return the Dt / String (extend the `recv instanceof ColH` arm at MetamodelWalk.java:1126).
3. Add `meta::relational::functions::toDDL::createTempTableStatement()` support. Cheapest honest route: let the module PULL `helperFunctions/toDDL.pure` (it already can — bare `createTempTableStatement` under `import meta::relational::functions::toDDL::*` qualifies through `Runner.qualify` and the file is in `elementSource`) and let the platform evaluate the returned lambda through the existing `eval` path; the body needs only `map`, `joinStrings`, `+`, `if`, `==` on `DatabaseType.H2` and `dataTypeToSqlText`, all of which exist. If lambda-returning corpus functions do not evaluate today, declare `createTempTableStatement()` as a native returning a function value and K-dispatch `eval` on it to `com.legend.exec.Ddl` — but only after confirming the corpus-body route is genuinely blocked, since the native route silently stops testing the corpus's own string builder.

**How legend-engine does it** — legend-pure declares the concrete datatypes as ordinary Pure classes: /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:408 — `Class meta::relational::metamodel::datatype::Integer extends meta::relational::metamodel::datatype::CoreDataType {}` (Varchar/Char/Varbinary carry `size: Integer[1]`; Decimal/Numeric carry precision+scale, same file :430-455). The lambda under test is engine Pure at /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/helperFunctions/toDDL.pure:232-240 — `{ttName, cols, dbType | let colsAsString = '('+$cols->map(c|$c.name+' '+dataTypeToSqlText($c.type))->joinStrings(',')+')'; if($dbType == DatabaseType.H2, |'Create LOCAL TEMPORARY TABLE '+$ttName+$colsAsString+';', …)}`

**Risk** — Adding ~22 classes to the native prelude widens `NameResolver.PRELUDE_TYPES` (NameResolver.java:243-254, `bySimple`), which indexes classes by SIMPLE name. `Integer`, `Float`, `Double`, `Date`, `Boolean`, `Binary`, `Timestamp` all collide with `meta::pure::metamodel::type::*` primitives and with existing prelude entries. The prelude is only a FALLBACK tier (consulted after explicit wildcards and own package), and there is an explicit `PRELUDE_COLLISIONS` tie-break set at NameResolver.java:256 — every colliding simple name MUST be added there or bare `Integer` in a corpus signature could start resolving to the relational datatype and silently mistype the whole corpus. That is the single largest risk in this fix and must be checked before anything else. Tenet-2 trap: do NOT special-case `^datatype::…` inside `harness/EngineTestExecutor` or `LineageForm` — the class prelude is platform Knowledge and belongs in Pure.java.

**Also unblocks** — The datatype-class family is referenced by `postprocessor/tests/testPostProcessor.pure` and `tests/testRelationalExtension.pure` in the corpus, so step 1 alone may move (not necessarily pass) tests in those families. It is a strict prerequisite for `dropAndCreateTempTable` in this unit and for `testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements` (which constructs `^datatype::SemiStructured()` and `^datatype::Integer()`).

**Falsifier** — Add ONLY the `datatype::Integer` + `CoreDataType` declarations to Pure.java and re-run this one test. If the wall moves from `unknown class 'meta::relational::metamodel::datatype::Integer'` to something naming `Column` or `createTempTableStatement`, the diagnosis chain is right. If it stays on an unknown-class message for a different datatype, or shifts to a name-resolution ambiguity on bare `Integer`, the PRELUDE_COLLISIONS risk is the dominant problem, not the missing declarations.

<details><summary>Evidence read (7 citations)</summary>

- core/src/main/java/com/legend/compiler/spec/NewChecker.java:68 — `if (t.model().findClass(ni.className()).isEmpty()) throw new TypeInferenceException("unknown class '" + ni.className() + "' in ^" + ni.className() + "(…)")` — the exact message in the failure detail
- core/src/main/java/com/legend/builtin/Pure.java:351 — `DATA_TYPE_METACLASS = nativeClass("native Class meta::relational::metamodel::datatype::DataType extends meta::pure::metamodel::type::Any {}")`; a grep of every `native Class meta::relational::metamodel::datatype::…` in the file returns this ONE line
- core/src/main/java/com/legend/builtin/Pure.java:347 — `COLUMN_METAMODEL` declares `type: meta::relational::metamodel::datatype::DataType[1]`, i.e. the Column ctor slot exists and demands a DataType value that cannot be spelled
- core/src/main/java/com/legend/StatementExecutor.java:1517-1610 — `constructNode` switch on the simple class name: QualifiedName, QualifiedNameReference, ColumnName, Alias/TableAlias, TableAliasColumnName, TableAliasColumn, then a `default` that only handles the sql-protocol / pureToSqlQuery / clustering packages and GENERIC_RELATIONAL_KINDS. No `Column` arm.
- core/src/main/java/com/legend/exec/MetamodelWalk.java:1546-1573 — `sqlText(Object recv)` already maps `RelationalDataType.Integer_ -> "INT"`, i.e. the OUTPUT half of dataTypeToSqlText is built; only the INPUT (a constructible Pure datatype value) is missing
- core/src/main/java/com/legend/StatementExecutor.java:1413-1415 — `case "dataTypeToSqlText" -> { return com.legend.exec.MetamodelWalk.sqlText(recv); }` — the native is already K-dispatched
- core/src/test/java/com/legend/rcorpus/Runner.java:1183-1194 — `unknownTypePull` returns `elementSource.containsKey(name) ? name : null` for a qualified name; `meta::relational::metamodel::datatype::Integer` is not a corpus element, so no retry happens

</details>

---

## `testNonDataTypeProperty`

| | |
|---|---|
| family | `lineage/scanColumns` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

This is the one test in the unit whose surface IS implemented — by a different mechanism than engine's, and that mechanism has a genuine hole. `LineageForm.scanColumns` (LineageForm.java:96-117) recognises the corpus's scanProperties→buildPropertyTree→scanColumns→assertEquals shape and answers it by LOWERING the query to SQL (`Compiler.lowerResolved`) and running `ScanColumns.strings(plan)` over the lowered plan (ScanColumns.java:19-52 documents this explicitly: "Column lineage over the LOWERED SQL plan … computed from the REAL pipeline's output instead of a parallel metamodel walk"). Any `NotImplementedException` from the lowering becomes `Unsupported("scanColumns query: " + msg)` at LineageForm.java:115-117 — the observed message. The lowering throws because the query is `Person.all()->project([p|$p.name(), p|$p.address], …)` and `$p.address` is a CLASS-typed property: `Substitution.rewriteHeadProp` (Substitution.java:1352-1372) finds a non-null binding for `address`, sees the binding's inner value is a `TypedNewInstance` / `Type.ClassType`, and throws `class-typed property '$p.address' used as a whole value is graph output (Phase H4)`. That wall is CORRECT for lowering — `associationMappingWithIds` binds `address : [dbInc]@Address_Person` (a join, no columns), so there is no scalar SQL expression for that projection column. The mismatch is architectural: engine's scanColumns never lowers; it enriches the property tree with mapping info and reads columns off the property mappings, so a join-only property contributes exactly its join's ON columns and no value column — which is precisely the four-element expected answer.

**Fix**

Make the lowering able to express "this projection column forces a join and produces no output", which is the only shape that yields the expected four-element answer. Concretely: in `core/src/main/java/com/legend/resolver/Substitution.java`, at the class-typed head read (line ~1365, currently the unconditional throw), when the head is the TERMINAL value of a projection column, rewrite it to a join-forcing marker rather than throwing — the join must enter the plan's FROM/ON so `ScanColumns.envOf`/join-ON scan tags `personTable.ADDRESSID` and `addressTable.ID` as `<JoinTreeNode>`, while the column contributes no select expression. That needs a matching no-output column slot in `com.legend.sql.SqlSelect` and a lowering arm in `Lowerer` that emits the join without a projection entry. Keep the existing throw for every OTHER context (a class-typed head genuinely IS graph output outside a lineage projection) — do not relax the wall globally. The alternative, and the one that matches engine's own design, is to give `ScanColumns` a second entry point that walks the class's property mappings from `ModelContext` directly (mirroring scanColumns.pure:30-53) and have `LineageForm` use it; that removes the dependency on lowerability entirely but duplicates mapping traversal that `ScanRelations` already partly does.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/lineage/scanColumns/scanColumns.pure:30-46 — `scanColumns(p:PropertyPathTree[1], m:Mapping[1])` resolves the mapping's class mappings, calls `enrichPropertyPathTreeWithDataSetMapping`, then `getColumnsWithContext` (:50-53) which simply concatenates each enriched node's `dataSetColumns` with its children's. No SQL is generated anywhere in that path — the walk is over the property tree and the property mappings.

**Risk** — Do NOT solve this by rewriting `$p.address` to `$p.address.<primaryKey>`: that projects `addressTable.ID`, which `ScanColumns` would then also emit as `addressTable.ID <TableAliasColumn>` — an extra element that the test's exact-list `assertEquals` (after `removeDuplicates`, whose equality keys are column+context) rejects. Do NOT solve it in `harness/LineageForm.java` by special-casing class-typed project columns — the harness owns test-shape recognition, the platform owns lowering; that is exactly the tenet-2 cardinal sin. Relaxing the Substitution.java:1365 throw too broadly would let genuine graph-output queries lower to silently wrong SQL — the guard exists for a reason and must stay context-scoped.

**Also unblocks** — Unknown from static analysis. The `class-typed property … is graph output (Phase H4)` message is a single throw site (Substitution.java:1372) reachable from any query that reads a class-valued property as a whole value, so other corpus tests may share it — but they would legitimately be graph-fetch tests where the wall is correct, and relaxing it for them would be wrong. Only the lineage/projection context should change.

**Falsifier** — Set `LL_LINEAGE_DEBUG` and confirm the other five scanColumns tests in the same file reach `[scanColumns-sql]` (i.e. they lower fine) while this one does not — that isolates the cause to lowerability rather than to the ScanColumns walk. Then, minimally, delete the `$p.address` column from the query and check the remaining two columns produce `personTable.FIRSTNAME/LASTNAME <TableAliasColumn>`; if they do, the only missing piece is the join-forcing no-output column and my fix design holds. If instead the reduced query also mis-tags, the ScanColumns join/value tagging is the real problem and the fix is elsewhere.

<details><summary>Evidence read (5 citations)</summary>

- core/src/main/java/com/legend/harness/LineageForm.java:96-117 — `Compiler.lowerResolved(resolved, ctx, runtimeFqn, false)` then `ScanColumns.strings(plan)`, with `catch (NotImplementedException e) { return new Outcome.Unsupported("scanColumns query: " + msg.split("\\n")[0]); }` — the exact observed message prefix
- core/src/main/java/com/legend/lineage/ScanColumns.java:19-40 — the class doc: "Column lineage over the LOWERED SQL plan (feature track #44 — the engine's scanColumns surface, computed from the REAL pipeline's output instead of a parallel metamodel walk)"; `<TableAliasColumn>` = value read, `<JoinTreeNode>` = join-key read at any depth
- core/src/main/java/com/legend/resolver/Substitution.java:1355-1372 — `TypedSpec binding = target.bindings().get(prop); … if (inner instanceof TypedNewInstance || inner.info().type() instanceof Type.ClassType) throw new NotImplementedException("class-typed property '$" + target.userVar() + "." + prop + "' used as a whole value is graph output (Phase H4)")` — the exact message, with the comment "graph output territory: the honest story, not a 'resolver bug'"
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/mapping/association/testAssociationMapping.pure:100 — inside `associationMappingWithIds`, `address : [dbInc]@Address_Person` — a join-only property mapping with no column, which is why the binding is class-typed
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/lineage/scanColumns/scanColumnsTests.pure:64-73 — the test body and its expected list `['addressTable.ID <JoinTreeNode>', 'personTable.ADDRESSID <JoinTreeNode>', 'personTable.FIRSTNAME <TableAliasColumn>', 'personTable.LASTNAME <TableAliasColumn>']` — note addressTable.ID appears ONLY as JoinTreeNode, never as a value read

</details>

---

## `testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements`

| | |
|---|---|
| family | `milestoning/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The observed message `Unknown type: 'Operation' is not a known primitive, class, or enum` is thrown by `TypeClassifier.classify` (TypeClassifier.java:85-92) on a `TypeExpression.NameRef` whose name is still the BARE `Operation`. It is bare because `NameResolver` passes an unresolvable simple name through unchanged (documented rule, NameResolver.java:106-108: "0 matches → pass through (likely a primitive)") — no corpus element and no native prelude class has the simple name `Operation`. The prelude declares exactly one class under `meta::relational::metamodel::operation::` (JoinStrings, Pure.java:444) and does not declare `Operation`, `operation::Function`, `BinaryOperation`, `UnaryOperation`, `VariableArityOperation`, `SemiStructuredObjectNavigation`, `SemiStructuredPropertyAccess` or `SemiStructuredArrayElementAccess`. The name is reached because the module pulls `milestoning/milestoning.pure`, whose `applyMilestoningFilters` SIGNATURE (line 106) is `tableToFilterOp: Function<{TableAlias[1]->Operation[0..1]}>[1]`, and `ModelIntegrity.checkFunction` (ModelIntegrity.java:130-134) classifies every function parameter type eagerly at module construction. So the wall is real and correctly located — but it is only the FIRST of many. The test's actual subject is `meta::relational::milestoning::applyMilestoningFilters`, a ~55-arm `match` over engine's SQL metamodel that rebuilds SelectSQLQuery/JoinTreeNode/DynaFunction trees with `^$x(...)` copies (milestoning.pure:106-160 and :162-217). legend-lite applies milestoning filters in Java inside its own resolver/lowering; it does not and will not interpret that Pure function. The test carries NO assert — it passes iff evaluating that engine function six times does not throw. There is nothing here for legend-lite to compute.

**Fix**

Do not fix; ledger it. Declaring `meta::relational::metamodel::operation::{Function, Operation, BinaryOperation, UnaryOperation, VariableArityOperation, ArithmeticOperation, VariableArithmeticOperation, SemiStructuredObjectNavigation, SemiStructuredPropertyAccess, SemiStructuredArrayElementAccess}` and `relation::{SemiStructuredArrayFlatten, SemiStructuredArrayFlattenOutput}` in Pure.java (mirroring legend-pure relational.pure:186-357) is a legitimate small change and WILL move the wall — but the next wall is that `applyMilestoningFilters` itself must be interpreted: 55 match arms over engine metamodel classes, `^$x(...)` copy-with-update on each, `buildUniqueName`, `reprocessAliases`, `getAllNodes`, `newAndOrDynaFunctionRelaxedBrackets`, and an extension-handler dispatch through `RelationalExtension.milestoning_applyFilterHandlers`. That is engine's Pure compiler, which legend-lite replaces with Java by design. If the prelude classes are added anyway (they are cheap and correct), record this test as still-walled at the `applyMilestoningFilters` body rather than counting the declaration as progress on it.

**How legend-engine does it** — The class exists as ordinary Pure in legend-pure: /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:190 — `Class meta::relational::metamodel::operation::Operation extends meta::relational::metamodel::operation::Function {}` (parent at :186; `Join.operation : Operation[1]` at :178; the SemiStructured* subclasses at :339-357). The function under test is /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/milestoning/milestoning.pure:106 (the RelationalOperationElement overload) and :162 (the RelationalTreeNode overload) — engine's own SQL-metamodel rewriter, dispatching through `$extensions->map(e|$e.moduleExtension('relational')->cast(@RelationalExtension).milestoning_applyFilterHandlers)`.

**Risk** — If the operation:: classes ARE added, note that `Pure.java:444` currently declares `JoinStrings extends RelationalOperationElement` — deliberately flattened. Reparenting it to `Operation` to match relational.pure:323 changes subsumption for every `cast(@…)`/`instanceOf` site that currently relies on the flat chain, and `GENERIC_RELATIONAL_KINDS` (StatementExecutor.java:1614-1626) lists `metamodel::operation::JoinStrings` as a generic node. Do not reparent as a drive-by. Also `SemiStructuredPropertyAccess` etc. would newly resolve as bare names in EVERY corpus file that imports `meta::relational::metamodel::operation::*` (postProcessor.pure, trimColumnNamePostProcessor.pure, pushFiltersDownToJoin.pure), pulling those files past MODEL integrity and into deeper, noisier walls — the SHAPE denominator would shift without any test passing.

**Also unblocks** — The missing `operation::Operation` name also poisons `pureToSQLQuery/pureToSQLQuery.pure:6786` and `pureToSQLQuery_deprecated.pure:621`, plus milestoning.pure:162/217/283/296/304/311/318/337/349/360/373/387/403/412/420/487 — every white-box test that pulls those files hits the same MODEL wall.

**Falsifier** — Grep the corpus for an assert in this test body. There is none — the function returns `Any[*]` and the six statements are bare calls to `applyMilestoningFilters`. If someone claims it can pass without interpreting `milestoning.pure`, ask which legend-lite code would evaluate `^$s(operand = $s.operand->applyMilestoningFilters(...))` (milestoning.pure:145). Conversely, if a probe shows legend-lite already interprets other corpus `match`-over-metamodel functions of comparable depth, my TEST_ASSERTS_ENGINE_INTERNALS verdict is too pessimistic and this becomes MISSING_FEATURE.

<details><summary>Evidence read (6 citations)</summary>

- core/src/main/java/com/legend/compiler/element/TypeClassifier.java:85-92 — `case TypeExpression.NameRef nr -> … yield findType(nr.name()).orElseThrow(() -> new ModelException(Phase.MODEL, "Unknown type: '" + nr.name() + "' is not a known primitive, class, or enum"))` — the exact message
- core/src/main/java/com/legend/compiler/element/TypeClassifier.java:52-54 — `isClassFqn` = `model.findClass(fqn).isPresent() || Pure.findNativeClass(fqn).isPresent()`; a bare `Operation` matches neither
- core/src/main/java/com/legend/compiler/NameResolver.java:106-108 — the documented wildcard rule: "0 matches → pass through (likely a primitive). 1 match → use it. >1 → IllegalStateException"
- core/src/main/java/com/legend/builtin/Pure.java:444 — `JOIN_STRINGS_OP = nativeClass("native Class meta::relational::metamodel::operation::JoinStrings extends meta::relational::metamodel::RelationalOperationElement …")` — the ONLY `metamodel::operation::` class in the prelude, and it is even reparented off `Operation`
- core/src/main/java/com/legend/compiler/element/ModelIntegrity.java:130-134 — `checkFunction`: `for (var p : f.parameters()) classifier.classify(p.type(), f.typeParameters()); classifier.classify(f.returnType(), …)` — eager, whole-model, at context construction
- core/src/test/java/com/legend/rcorpus/Runner.java:1191-1194 — `unknownTypePull` for a non-qualified name searches `elementSource` simple names; no corpus element is named `…::Operation`, so no demand-pull retry rescues it

</details>

---

## `testDb2ColumnRename`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | medium |

**Root cause**

Same first wall as test 1 (SQLQuery absent -> SQLResult.sqlQueries / sqlQueryToString cannot classify). Behind it this test needs THREE things legend-lite does not have: (a) the toSQL/sqlQueryToString surface (shared with test 1); (b) `meta::relational::postProcessor::reAliasColumnName::trimColumnName(query, runtime)` — the DB2 alias-limit post-processor: `grep -rn "aliasLimit|trimColumn|reAliasColumnName" core/src/main` returns NOTHING, the feature is entirely absent; (c) DB2 rendering of a UNION ALL over the long-concat union mapping. (c) is partly there — EngineStyleDB2 exists and respells infix `concat` — but whether legend-lite emits the exact pre-trim union text (`select "root".ID as "pk_0_0", null as "pk_0_1", (...) ... UNION ALL ... ) as "unionBase"`) is UNVERIFIED; no passing sibling test pins it. So unlike test 1, this is not surface-only.

**Fix**

Land the shared surface from test 1 FIRST, then add trimColumnName as a platform-owned native. (1) PlatformTypes.java: add `TRIM_COLUMN_NAME = "meta::relational::postProcessor::reAliasColumnName::trimColumnName"` and OR it into isPlatformOwnedFunction (:218-230) so the corpus's Pure body (which walks the engine metamodel) is suppressed. (2) Pure.java: `signature("native function meta::relational::postProcessor::reAliasColumnName::trimColumnName(query:meta::relational::metamodel::relation::SelectSQLQuery[1], runtime:Any[1]):meta::pure::mapping::Result<meta::relational::metamodel::relation::SelectSQLQuery|1>[1];")` plus the ConnectionStore overload. (3) A new IR pass, e.g. core/src/main/java/com/legend/lowering/TrimColumnNames.java, mirroring SqlPostProcessors' apply/applySelect/source/expr walk (SqlPostProcessors.java:181-273) but rewriting NAMES instead of tables: collect every projection outputName / column alias whose length >= the dialect alias limit (DB2 = 128; put the limit on SqlDialect/DialectCapability next to the other per-dialect facts, do NOT hardcode it in the pass); for each, strip quotes and truncate to limit-10 = 118; group originals by truncated prefix in first-seen order and assign `"<prefix>_<index>"`; rewrite both the projection alias and every reference to it. (4) Wire the native to run that pass over the deferred toSQL handle's SqlQuery and wrap the result in a Result value so `.values` navigates. Order matters: measure (c) before building (b) — see the falsifier.

**How legend-engine does it** — legend-engine core_relational/relational/postprocessor/defaultPostProcessor/trimColumnNamePostProcessor.pure:34-49 (trimColumnName: search + groupBy + indexed rename) and :52-62 (lengthConfig: `createDbConfig([]).dbExtension.aliasLimit`, i.e. the limit is a DIALECT property, which is why it belongs on SqlDialect in legend-lite).

**Risk** — Same shared risks as test 1 (native SQLQuery un-walls many corpus elements; SelectSQLQuery re-parenting). Additionally: the alias limit must be a per-dialect fact, not a DB2 special case wired into the lowerer, or every future dialect re-litigates it. And if legend-lite's union SQL for unionMappingWithLongPropertyMapping does not already match the engine's pre-trim text, adding trimColumnName produces a DIFFERENT wrong string and the test still fails — spending L effort for no scoreboard movement. Do not paper over that by loosening the compare.

**Also unblocks** — Nothing else in the 276 depends on trimColumnName; the SQLQuery half of the fix is shared with the 6 other tests listed under test 1.

**Falsifier** — Before building anything: probe `meta::relational::functions::sqlstring::toSQLString(|Person.all()->project([p|$p.lastName],['name']), meta::relational::tests::mapping::union::unionMappingWithLongPropertyMapping, DatabaseType.DB2, relationalExtensions())` and compare against the expected string on testPostProcessor.pure:436 with the two long aliases replaced by their untruncated originals. If the union/UNION ALL/"unionBase" skeleton or the DB2 concat spelling diverges, trimColumnName is NOT the blocker and this test should be ledgered instead of fixed.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91 — the throw that produces the reported wall
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:19 — `public class EngineStyleDB2 extends EngineStyleH2` with the javadoc 'respells only the dialect-owned function forms the DB2 goldens pin: infix concat, bare trim, varchar(16000)': the DB2 renderer exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:371 — `case "DB2" -> new com.legend.sql.dialect.EngineStyleDB2();`: dbType->renderer selection already exists in the toSQLString arm
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/trimColumnNamePostProcessor.pure:34 — `trimColumnName(query:SelectSQLQuery[1],runtime:Runtime[1]):Result<SelectSQLQuery|1>[1]`: search names >= aliasLimit, groupBy truncated prefix, rename to '"'+prefix+'_'+index+'"'
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/defaultPostProcessor/trimColumnNamePostProcessor.pure:110 — `getSanitizedAndOriginalNamePair(name, maxLength)`: `if($name->length() >= $maxLength, | ... if($sanitizedName->length() > ($maxLength - 10), | $name->replace('"','')->substring(0, $maxLength - 10), | $sanitizedName) ...)` — the exact truncation rule (DB2 aliasLimit 128 -> 118 chars, which matches the 118-char prefix in the expected golden)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnion.pure:1581 — `Mapping meta::relational::tests::mapping::union::unionMappingWithLongPropertyMapping` with the nested concat(...) lastName mapping the test's alias explosion comes from
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:156 — TO_SQL_STRING is the established 'platform owns this engine surface' idiom that trimColumnName would join

</details>

---

## `testPostProcessTransformJoinOp`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The reported wall (SQLQuery, same mechanism as the other two — the body's last statement calls toSQL(...).sqlQueries->at(0)->cast(@SelectSQLQuery)->sqlQueryToString(...)) is a decoy: it fires while typing the LAST statement. Note the earlier statements DO compile — TableAliasColumn (Pure.java:350) and Literal (Pure.java:283) are native, which is why the message names SQLQuery and not them. The real blocker is the runtime this test builds: `sqlQueryPostProcessors = [{query:SelectSQLQuery[1] | $query->meta::relational::postProcessor::postprocess({rel | $rel->match([t:TableAliasColumn[1] | if($t.column.type->instanceOf(Integer), |^Literal(value=2), |$t), r:RelationalOperationElement[1] | $r])})}]`. That is an ARBITRARY user lambda pattern-matching over legend-engine's internal SQL metamodel node graph, recursively applied by the engine's Pure `transform`/`transformNonCached`. legend-lite deliberately does not reify its SQL IR as Pure metamodel instances: SqlPostProcessors recognizes exactly ONE hook shape (replaceTables) and throws loudly otherwise. The asserted output (`on (2 = 2 and ...)`) depends on the engine's node-level structure — that a join condition is a BinaryOperation over two TableAliasColumns — not on any observable query behavior. legend-lite's IR is a different, equivalent structure, so the golden encodes legend-engine's internal representation.

**Fix**

DO NOT FIX — ledger it. Supporting this test means reifying legend-lite's SqlQuery IR as Pure `meta::relational::metamodel::*` instances, evaluating an arbitrary user `match` lambda against them, running the engine's recursive transform, and lowering the rewritten graph back to IR — an XL feature that would import the engine's internal representation as a compatibility contract and directly contradicts the boundary stated at SqlPostProcessors.java:23-31. The correct action is diagnostic honesty: after the shared SQLQuery/toSQL surface from test 1 lands, this test will stop walling on 'Unknown type SQLQuery' and start walling at SqlPostProcessors.readHook (SqlPostProcessors.java:96) with 'hook shape is not a replaceTables lambda' — the TRUE reason, and the same wall its 7 siblings already show (docs/RELATIONAL_CORPUS.md:1265-1271). Record it in the ledger next to those seven as one bucket: 'arbitrary Pure post-processor transform over the reified SQL metamodel — not modelled'. If it is ever built, it belongs in a new lowering/MetamodelTransform pass reached from SqlPostProcessors.readHook, never in the harness.

**How legend-engine does it** — legend-engine core_relational/relational/postprocessor/postProcessor.pure:85-88 (postprocess = transformNonCached over an arbitrary lambda) and :90-115 (transform: the recursive JoinTreeNode/alias/operation walk). These ARE the Pure-implemented compiler internals; the golden `on (2 = 2 and ...)` is a statement about their node structure.

**Risk** — The tempting shortcut is to special-case THIS lambda (integer TableAliasColumn -> literal 2) in the recognizer, the way replaceTables is recognized. That would be pattern-matching a single test body rather than implementing a capability, and it would make the recognizer's loudness (the property that caught the false-green cteExtraction cluster, SqlPostProcessors.java:64-73) meaningless. Equally forbidden: intercepting the chain in Runner/EngineTestExecutor.

**Also unblocks** — Nothing. The 7 postprocessor ERROR rows already at the readHook wall (testComplexSubQueries, testCorrelatedSubQueryIsolationStrategy, testDeepSubQueries, testMultipleSubQueries, testNoSubQueries, testSingleSubQueryFromOperations, testSingleSubQueryFromView) belong in the SAME ledger bucket but are a different hook shape (cteExtraction), not fixed by anything here.

**Falsifier** — Land only the shared surface (native SQLQuery + platform-owned toSQL/sqlQueryToString) and re-run this test. If it PASSES, my claim is wrong — legend-lite would have to be reifying the metamodel somewhere I did not find. The expected observation is a new, honest wall: 'sqlQueryPostProcessors hook shape is not a replaceTables lambda'.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:23 — class javadoc: 'the engine hands its SQL metamodel to opaque lambdas; we RECOGNIZE the shapes the corpus builds (replaceTables over literal table pairs) and run the equivalent IR rewrite ... Unknown hook shapes inside the recognized channel are loud, never silently dropped' — the architectural boundary, stated in code
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:96 — `throw new NotImplementedException("sqlQueryPostProcessorsConnectionAware hook shape is not a replaceTables lambda — post-processor recognizer pending for: " + hook)`, the wall this test SHOULD be hitting
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SqlPostProcessors.java:61 — the plain `sqlQueryPostProcessors` slot routes to the same readHook recognizer, so this test's hook is in the recognized channel and will throw there
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:1265 — `ERROR testComplexSubQueries [postprocessor]: sqlQueryPostProcessorsConnectionAware hook shape is not a replaceTables lambda ...` — 7 sibling tests already fail at exactly that wall, confirming it is the live behavior
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:350 — TableAliasColumn IS a native class (columnName/alias/column), which is why the wall names SQLQuery rather than TableAliasColumn
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/postProcessor.pure:85 — `postprocess(s: SelectSQLQuery[1], f:Function<{RelationalOperationElement[1]->RelationalOperationElement[1]}>[1]):Result<SelectSQLQuery|1>[1]` whose body is `$s->transformNonCached($f)->cast(@SelectSQLQuery)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/postprocessor/postProcessor.pure:90 — `transform(r:RelationalTreeNode[1], f:Function<Any>[1], transformed: Map<...>[1])`: a hand-written recursive match over JoinTreeNode/join aliases/operation/target — the engine-internal machinery a passing run would have to reproduce

</details>

---

## `testPushFiltersDownToJoinsPostProcessorToSQL`

| | |
|---|---|
| family | `postprocessor/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

The test never reaches SQL generation. Its body is `toSQL(...).sqlQueries->at(0)->sqlQueryToString(DatabaseType.H2, '', [], extensions)`. G-phase compiles that statement, needs either the corpus class `meta::relational::functions::sqlstring::SQLResult` (for the `.sqlQueries` property read) or the corpus function `sqlQueryToString` (for the terminal call); both are typed over `meta::relational::metamodel::SQLQuery`, which legend-lite's native prelude does not declare, so TypeClassifier.classify throws (see sharedRootCause). Behind that: legend-lite has NO `meta::relational::functions::sqlstring::toSQL` and NO `meta::relational::functions::sqlQueryToString::sqlQueryToString` surface — only `toSQLString`/`toSQLStringPretty` (PlatformTypes.java:156-163). Critically, the SQL CONTENT this test asserts is already produced correctly: the 909-char golden on testPostProcessor.pure:401 is BYTE-IDENTICAL (verified by string extraction and comparison) to the goldens on :407 (testSqlRealiasJoin) and :209 (testPushFiltersDownToJoinsPostProcessorMultipleChildren), both of which PASS, and the family row `| postprocessor/tests | 30 | 22 | 3 | 1 | 4 | 1 |` (docs/RELATIONAL_CORPUS.md:45) shows exactly ONE sqldiff-pass in the family — since those two tests share one golden they would both have to be sqldiff-passes if the text diverged, which would make the count 2. So legend-lite's engine-style renderer already emits this exact string. Only the API surface is missing.

**Fix**

Add the platform surface next to the existing toSQLString surface — NOT in the harness. (1) core/src/main/java/com/legend/builtin/Pure.java: add `nativeClass("native Class meta::relational::metamodel::SQLQuery extends meta::relational::metamodel::RelationalOperationElement { comment: meta::pure::metamodel::type::String[0..1]; }")` and re-parent SELECT_SQL_QUERY (Pure.java:426) from `...metamodel::RelationalOperationElement` to `...metamodel::SQLQuery` (real chain, relational.pure:240 `SelectSQLQuery extends Relation, SQLQuery`) so `cast(@SelectSQLQuery)` off a SQLQuery-typed value type-checks; also add `signature("native function meta::relational::functions::sqlstring::toSQL(f:Function<{->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):meta::relational::functions::sqlstring::SQLResult[1];")` and `signature("native function meta::relational::functions::sqlQueryToString::sqlQueryToString(sqlQuery:meta::relational::metamodel::SQLQuery[1], dbType:Any[1], dbTimeZone:String[0..1], quoteIdentifiers:Boolean[0..1], extensions:Any[*]):String[1];")` (matching dbExtension.pure:400). Leave SQLResult as the CORPUS class — with SQLQuery present it classifies, and shadowing it would drop its `toSQLString(...)` derived property. (2) PlatformTypes.java: add TO_SQL and SQL_QUERY_TO_STRING constants and OR them into isPlatformOwnedFunction (:218-230) so the corpus Pure bodies are suppressed exactly as TO_SQL_STRING's are (FunctionCompiler.java:64-72). (3) StatementExecutor.java: add two K-native arms beside toSqlString (:359-396). `toSQL` runs the SAME engineSql(...) pipeline (:405-460) that toSqlString runs — lambda arg0, mapping arg1, DatabaseType read off the runtime arg2 via databaseTypeOf — but DEFERS the render: return an opaque handle (e.g. a new record in com.legend.exec holding the lowered `com.legend.sql.SqlQuery` list). Make `.sqlQueries` on that handle yield the list, `->at(0)` index it, and `cast(@SelectSQLQuery)` an identity peel. `sqlQueryToString(handle, dbType, tz, quote, ext)` picks the renderer by dbType with the identical switch at StatementExecutor.java:369-378 and returns `renderer.render(handle.plan())` as an ExecutionResult.Scalar of type STRING. With that, this test's assertEquals compares two strings and the pipeline already produces the right one.

**How legend-engine does it** — legend-engine core_relational/relational/transform/fromPure/toSQLString.pure:46 defines `toSQL(f, mapping, runtime, extensions):SQLResult[1]` (generation + connection post-processors), and sqlQueryToString/dbExtension.pure:400 defines the 5-arg `sqlQueryToString(sqlQuery:SQLQuery[1], dbType, dbTimeZone, quoteIdentifiers, extensions)` that renders with `^Format(newLine='', indent='')` — i.e. the single-line form legend-lite's EngineStyleH2 already emits.

**Risk** — Adding native SQLQuery un-walls MANY corpus elements whose signatures mention it (postprocessor/postProcessor.pure:37 PostProcessorResult.query, :46 postProcessSQLQuery, relationalMappingExecution.pure:220 generateSQLExecutionNode) — those go from fail-fast to compilable, so tests elsewhere may fail DEEPER instead of earlier; the sweep delta must be measured, not assumed positive. Re-parenting SelectSQLQuery inserts a level in the native inheritance chain — anything comparing superClassFqns by equality rather than walking the chain would change behavior (StatementExecutor's GENERIC_RELATIONAL_KINDS at :1618-1632 is a flat FQN set, so the toPostgresModel conversion surface is unaffected). Marking sqlstring::toSQL platform-owned suppresses ALL three corpus overloads at that FQN (toSQLString.pure:46/100/107); its only in-corpus callers are toSQLStringPretty bodies that are ALREADY suppressed as platform-owned. TENET-2 TRAP: do not teach Runner/EngineTestExecutor to pattern-match the `toSQL(...).sqlQueries->at(0)->sqlQueryToString(...)` chain — that is a platform surface, and recognizing it in the harness would be exactly the harness compensation TENETS forbids.

**Also unblocks** — The identical 'SQLQuery' classification wall blocks 4 more corpus tests: simpleFunctionExpressionTranslationAdjust, simpleFunctionExpressionTranslationNow, testImportDataFlow (pureToSQLQuery/tests, brief U01) and testTempTableSqlStatementsForH2 (sqlQueryToString/testSuite, brief U06). The class addition removes that wall for all of them, but each still needs its own surface behind it — do not count them as passes.

**Falsifier** — Add a throwaway probe calling `meta::relational::functions::sqlstring::toSQLString(|Trade.all()->project([x|$x.id, x|$x.quantity, x|$x.latestEventDate, x|$x.initiator.firstName], ['TradeID','Quantity','LastEventDate','Initiator'])->filter(x|$x.getInteger('TradeID')->in([1,2,3,4,5])), simpleRelationalMapping, DatabaseType.H2, relationalExtensions())` and diff its output against the 909-char golden on testPostProcessor.pure:401. If it differs, the SQL content is NOT already right and this is not a surface-only fix.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91 — `findType(nr.name()).orElseThrow(... "Unknown type: '" + nr.name() + "' is not a known primitive, class, or enum")`, the exact wall text
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/ClassCompiler.java:44 — `for (PropertyDefinition pd : cd.properties()) properties.add(new Property.Stored(pd.name(), classifier.classify(pd.type(), typeParams), ...))`: materializing a class classifies EVERY stored property type
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/PureModelContext.java:120 — `classifier.classDef(fqn).map(def -> { TypedClass typed = classes.compile(def); ... })`: findClass materializes on demand, so the throw happens at the `.sqlQueries` navigation
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:119 — `if (typed.isEmpty() && first != null) throw first;`: all sqlQueryToString overloads take SQLQuery[1], so all are broken and the real reason surfaces
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1312 — `return new Outcome(t.fqn(), Status.SHAPE, "no execute(|...)" + " call" ... + " — wall: " + attempted.wall())`, the reported row shape
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:218 — isPlatformOwnedFunction lists TO_SQL_STRING/TO_SQL_STRING_PRETTY/EXECUTE/... and NOT sqlstring::toSQL or sqlQueryToString::sqlQueryToString
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:369 — the toSQLString arm already selects EngineStyleH2/EngineStyleDB2/EngineStyleComposite by DatabaseType and renders one SqlQuery; this is the machinery sqlQueryToString needs
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:45 — `| postprocessor/tests | 30 | 22 | 3 | 1 | 4 | 1 |` (tests/pass/fail/error/shape/sqldiff-pass): only ONE golden-text divergence in the whole family
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/transform/fromPure/toSQLString.pure:145 — `Class meta::relational::functions::sqlstring::SQLResult { shouldWarn: Boolean[1]; sqlQueries: SQLQuery[*]; extensions: Extension[*]; ... }`
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:200 — `Class meta::relational::metamodel::SQLQuery extends meta::relational::metamodel::RelationalOperationElement { comment : String[0..1]; }`, the class legend-lite lacks

</details>

---

## `addDriverTablePkForProject`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The test drives the engine's Pure router and Pure SQL compiler and then asserts on the SHAPE of the resulting metamodel object: `$tdsSelectSqlQuery.paths->size()`, `$tdsSelectSqlQuery.columns->cast(@Alias).name`. Three absences stack. (a) `$x->routeFunction($mapping, $runtime, $context, $extensions, $debugContext)` resolves to `meta::pure::router::routeFunction(f, mapping, runtime, exeCtx, extensions, debug)` at engine-core `core/pure/router/deprecated/deprecated.pure:24`; `RelationalCorpusRunner` (lines 129-177) loads only the m2m test library, `core/pure/graphFetch/domain`, and `pureToSQLQuery/pureToSQLQuery.pure` from outside the relational tree, so the whole Pure router is absent from the catalog — legend-lite routes in Java (`resolver/`, the H phase). (b) `toSQLQuery` IS in the module but its signature drops on `vs:ValueSpecification[1]` — legend-lite has no M3 ValueSpecification (this exact drop is recorded at docs/RELATIONAL_CORPUS.md:705), which is the reported wall. (c) Even past those, the assertion target does not exist: `paths` is declared on `meta::relational::metamodel::RelationalTds` (legend-pure relational.pure:363-366, `paths: Pair<String, PathInformation>[*]`), and legend-lite's `TdsSelectSqlQuery` native (Pure.java:451) is `extends SelectSQLQuery {}` with no `RelationalTds` supertype and no `paths`. CRUCIALLY, the SEMANTICS this test is about are already implemented and working in legend-lite — `validation/DriverPkAppend.java` implements `addDriverTablePkForProject` (its javadoc even cites the same `"root".ID as "ID"` golden). What is missing is only the Pure-metamodel API through which the test observes it.

**Fix**

DO NOT FIX — ledger it, and record the reason precisely so it is not re-triaged as a feature gap: `addDriverTablePkForProject` is IMPLEMENTED in legend-lite (`core/src/main/java/com/legend/validation/DriverPkAppend.java` + `DriverPkOption.java`, wired at `StatementExecutor.java:342`). This test cannot pass because it observes the option through legend-engine's Pure compiler API (`routeFunction` → `StoreMappingClusteredValueSpecification` clusters → `toSQLQuery` → `TdsSelectSqlQuery.paths`), none of which legend-lite reifies. Making it pass requires loading legend-engine's whole Pure router tree AND reifying M3 ValueSpecification AND adding `RelationalTds`/`paths` — i.e. running legend-engine's compiler inside legend-lite, which is the opposite of what this project is. The right coverage for this option is a `project(...)` corpus test under `-Drcorpus` with the driver-PK column asserted by NAME in the result columns; per DriverPkAppend's own javadoc such a 'validation showcase golden' already exists.

**How legend-engine does it** — legend-engine `core_relational/relational/runtime/executionContext/executionContext.pure:27` declares `addDriverTablePkForProject : Boolean[0..1]` on `RelationalExecutionContext`; `core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:107` carries it on the compiler `State` and :303 threads it in from the execution context. legend-lite's equivalent is `core/src/main/java/com/legend/validation/DriverPkAppend.java:45-69`.

**Risk** — Tenet-2 trap, and it is a sharp one here precisely BECAUSE the feature works: it would be tempting to intercept this test in `EngineTestExecutor` and answer `.paths`/`.columns` from legend-lite's own MIR projection list. That would make the row green while asserting nothing about routing or SQL generation. Also do not add a bare `paths` property to `TdsSelectSqlQuery` in Pure.java — with nothing populating it, `assertEquals(2, …->size())` would compare against an empty collection and FAIL misleadingly, or worse, pass by coincidence.

**Also unblocks** — simpleFunctionExpressionTranslationNow, simpleFunctionExpressionTranslationAdjust, testImportDataFlow — all four share the ValueSpecification/SQLQuery metamodel absence

**Falsifier** — Cheapest falsifier for the 'feature already works' half: `grep -rn 'DriverPkAppend' core/src/main/java/` and read `StatementExecutor.java:342` — if the option is not actually wired into the execute path, then this row is a feature gap, not an internals test, and my verdict is wrong. Cheapest falsifier for the wall half: `grep -n 'RelationalTds\|paths' core/src/main/java/com/legend/builtin/Pure.java` — a `paths` property on TdsSelectSqlQuery would falsify (c). Note I inferred which of the three absences surfaces first through `Runner`'s ≤3-hop demand-pull retry (Runner.java:1132-1174) without running it; `LL_TMP_DEBUG=1 -Drcorpus.only=pureToSQLQuery` prints the actual `[try-run]` first failure.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:271-294 — the test body: `^RelationalExecutionContext(enableConstraints=false, addDriverTablePkForProject=true)`, `$x->routeFunction(...)`, `cast(@StoreMappingClusteredValueSpecification)`, `byPassValueSpecificationWrapper()`, `$fe->toSQLQuery(...)->cast(@TdsSelectSqlQuery)`, then `assertEquals(2, $tdsSelectSqlQuery.paths->size())`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/.../core/pure/router/deprecated/deprecated.pure:24 — the 6-arg `routeFunction(f, mapping, runtime, exeCtx, extensions, debug)` the test calls
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:129-177 — the complete foreign-tree load list; `core/pure/router` is absent, so `routeFunction` is not in `elementSource` and `Runner.unknownTypePull` (Runner.java:1183-1207) cannot pull it either
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:705 — `global meta::relational::functions::pureToSqlQuery::toSQLQuery => Unknown type: 'ValueSpecification' is not a known primitive, class, or enum` — the recorded drop matching this test's wall verbatim
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:188 and :216 — both `toSQLQuery` overloads take `vs:ValueSpecification[1]`, so `FunctionCompiler.compileAll` (FunctionCompiler.java:119-121) has no healthy sibling and rethrows
- /Users/neemsandv/legend/legend-pure/.../platform_store_relational/grammar/relational.pure:363-366 — `Class meta::relational::metamodel::RelationalTds { paths: Pair<String, PathInformation>[*]; }` and :275 `TdsSelectSqlQuery extends SelectSQLQuery, RelationalTds`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:451 — `native Class meta::relational::metamodel::relation::TdsSelectSqlQuery extends meta::relational::metamodel::relation::SelectSQLQuery {}` — no RelationalTds, no `paths`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/validation/DriverPkAppend.java:23-31 and :37-69 — legend-lite DOES implement the option: `each projection in the RESOLVED query gains the DRIVER TABLE's primary-key columns — physical names, appended AFTER the user columns (validation showcase golden: "root".ID as "ID")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — the emitting line for the reported wall

</details>

---

## `simpleFunctionExpressionTranslationAdjust`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

Identical mechanism to `simpleFunctionExpressionTranslationNow` — same two-line shape, only the query differs (`adjust(now(), 1, DurationUnit.MONTHS)` instead of `now()`, expecting `'select dateadd(month, 1, now())'`). It calls `pureToSqlQuery::toSQLQuery` and then `sqlQueryToString`; every `sqlQueryToString` overload takes `sqlQuery:SQLQuery[1]` (dbExtension.pure:388,400,408,417,425), `meta::relational::metamodel::SQLQuery` is a legend-pure class (relational.pure:200) absent from legend-lite's 199-entry native catalog, so `FunctionCompiler.compileAll` drops all overloads and rethrows `TypeClassifier`'s `Unknown type: 'SQLQuery' …`. `Runner.unknownTypePull` finds no corpus element with that simple name and the retry loop surfaces it as the wall. `toSQLQuery` is independently dropped on `ValueSpecification[1]` (docs/RELATIONAL_CORPUS.md:705). Note the DurationUnit/adjust→dateadd translation itself is not what fails and is not in question — the test never gets that far.

**Fix**

DO NOT FIX — ledger it, same disposition as `simpleFunctionExpressionTranslationNow`. The `adjust`→`dateadd` dialect mapping this test is nominally about is legend-lite's own concern and lives in `sql/dialect/`; the correct place to assert it is an `execute(|…)` row-or-golden-SQL corpus test, not a call into the engine's Pure renderer. Greening this row requires the full SQLQuery+ValueSpecification metamodel reification described for the sibling test.

**How legend-engine does it** — legend-engine `core_relational/relational/sqlQueryToString/dbExtension.pure:388`; the `adjust`/DurationUnit dyna-function pairs legend-lite does carry are noted at `core/src/main/java/com/legend/builtin/Pure.java:1195` (`day-of-week anchored shifts (engine pureToSQLQuery dyna pairs …)`).

**Risk** — Same tenet-2 trap as the sibling: do not add an empty `native Class meta::relational::metamodel::SQLQuery` to make the signature compile. `sqlQueryToString` would then be dispatchable with no implementation behind it, converting a loud wall into a silent wrong answer — precisely the failure mode TENETS.md's 'a loud wall is better than wrong rows' forbids.

**Also unblocks** — simpleFunctionExpressionTranslationNow, testImportDataFlow

**Falsifier** — `grep -n 'metamodel::SQLQuery' core/src/main/java/com/legend/builtin/Pure.java` returning a hit falsifies the root cause. Separately, if `LL_TMP_DEBUG=1 -Drcorpus.only=pureToSQLQuery` shows this test's `[try-run]` first failure naming something other than a `SQLQuery`/`ValueSpecification` type miss, my emitter attribution (as opposed to the absence itself) is wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:257-269 — the test body: `toSQLQuery($fe, $mapping, ^Map<String,List<Any>>() , [], noDebug(), relationalExtensions())` then `sqlQueryToString(..., DatabaseType.H2, ...)`, asserting `'select dateadd(month, 1, now())'`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbExtension.pure:388,400,408,417,425 — all five `sqlQueryToString`/`sqlQueryToStringPretty` overloads take `sqlQuery:SQLQuery[1]`
- /Users/neemsandv/legend/legend-pure/.../platform_store_relational/grammar/relational.pure:200 — `Class meta::relational::metamodel::SQLQuery extends RelationalOperationElement`, i.e. platform-owned, outside the corpus
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:126-148 — `compile(Function)` classifies EVERY parameter (line 132) before the return type (line 143), so a broken first parameter is what surfaces
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — the emitting line
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:426,451 — SelectSQLQuery/TdsSelectSqlQuery present, `metamodel::SQLQuery` base absent
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1097-1174 — `tryRunNoExecute`: the ≤3-hop demand-pull retry, then `new TryRun(null, exceptionText(e))` becomes the wall text

</details>

---

## `simpleFunctionExpressionTranslationNow`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The test calls the engine's Pure-implemented compiler directly — `meta::relational::functions::pureToSqlQuery::toSQLQuery($fe, $mapping, ^Map<String,List<Any>>(), [], noDebug(), relationalExtensions())` — and then renders the returned metamodel object with `meta::relational::functions::sqlQueryToString::sqlQueryToString(..., DatabaseType.H2, ...)`. Both functions ARE in legend-lite's element universe (`pureToSQLQuery.pure` is registered as a library source at RelationalCorpusRunner.java:169-177, and the whole `sqlQueryToString/` directory is a family under `allFamilies()`), but both DROP at signature compile: every `sqlQueryToString` overload takes `sqlQuery:SQLQuery[1]` and `meta::relational::metamodel::SQLQuery` is a legend-pure platform class (relational.pure:200) that legend-lite's native catalog does not carry. `FunctionCompiler.compileAll` compiles each overload, catches, and — since ALL overloads share the broken `SQLQuery` parameter — rethrows `first` at FunctionCompiler.java:119-121, which is `TypeClassifier.classify`'s `Unknown type: 'SQLQuery' …` from TypeClassifier.java:91-92. `Runner.unknownTypePull` then cannot rescue it: it scans `elementSource` for an element whose simple name is `SQLQuery` and the entire core_relational corpus declares none (only subclasses, e.g. metamodel/metamodel.pure:18 `UpsertSQLQuery extends SQLQuery`), so the retry loop returns the message as the wall. Underneath that, `toSQLQuery` itself is already dropped on `ValueSpecification[1]` (docs/RELATIONAL_CORPUS.md:705), and `$x.expressionSequence->cast(@FunctionExpression)` has no reflection surface at all.

**Fix**

DO NOT FIX — ledger it. The assertion is `assertEquals('select now()', $sql_string)` where `$sql_string` is produced by the ENGINE's Pure SQL renderer over the ENGINE's Pure SQL metamodel. legend-lite renders SQL in Java (`sql/dialect/`) from its own MIR; there is no `SQLQuery` object graph to hand to `sqlQueryToString`, and creating one would mean building a second, Pure-shaped rendering path alongside the Java one — two sources of truth for SQL text, which is a worse outcome than a wall. The equivalent legend-lite-native coverage (that `now()` in a projection renders correctly on the H2/DuckDB dialects) belongs in a normal `execute(|…)` corpus test, and that surface exists. If someone insists on greening this row the full cost is: reify `meta::relational::metamodel::SQLQuery` + the M3 `ValueSpecification` hierarchy in Pure.java, make `evaluateAndDeactivate` return a reflective expression tree, and implement `toSQLQuery`/`sqlQueryToString` as Pure-callable natives that round-trip legend-lite's MIR through the Pure metamodel. That is a project, not a fix.

**How legend-engine does it** — legend-engine `core_relational/relational/sqlQueryToString/dbExtension.pure:388` (renderer entry, `SQLQuery[1]`) and `core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:188` (`toSQLQuery(vs:ValueSpecification[1], …):SQLQuery[1]`) — the two Pure-implemented compiler entry points this test drives.

**Risk** — The tenet-2 trap here is real and specific: it would be easy to make this row green by teaching the harness to recognise the `toSQLQuery(...)->sqlQueryToString(...)` idiom and answer it from legend-lite's Java renderer. That is harness compensation of the worst kind — the test would then assert nothing about the code path it names. Equally, do not add `SQLQuery` as an empty `native Class` just to unblock the signature: `sqlQueryToString` would then compile and be dispatched to with no body, producing wrong or empty SQL text instead of a loud wall.

**Also unblocks** — simpleFunctionExpressionTranslationAdjust, testImportDataFlow (identical mechanism); and the `sqlQueryToString` / `sqlQueryToString/testSuite` family rows in docs/RELATIONAL_CORPUS.md:49,52

**Falsifier** — If `meta::relational::metamodel::SQLQuery` is in fact resolvable, this is wrong. Cheapest check: `grep -n 'metamodel::SQLQuery' core/src/main/java/com/legend/builtin/Pure.java` — no hit confirms. Second, to confirm the emitter is `sqlQueryToString`'s signature and not something earlier: run `LL_TMP_DEBUG=1 mvn ... -Drcorpus.only=pureToSQLQuery` and read the `[try-run]` stack — I identified the emitter statically and did not observe the ≤3-hop demand-pull sequence in `Runner.java:1132-1174`, so the exact name that surfaces after the pulls is inferred, not observed. The ABSENCE of `SQLQuery` is observed and is what matters.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbExtension.pure:388 — `function meta::relational::functions::sqlQueryToString::sqlQueryToString(sqlQuery:SQLQuery[1], dbType:DatabaseType[1], extensions:Extension[*]):String[1]` (and :400, :408, :417, :425 — every overload takes `SQLQuery[1]`)
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:200 — `Class meta::relational::metamodel::SQLQuery extends meta::relational::metamodel::RelationalOperationElement { comment : String[0..1]; }` — declared in legend-pure, not in the corpus
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — the wall literal `"Unknown type: '" + nr.name() + "' is not a known primitive, class, or enum"`, thrown by `findType(...).orElseThrow()`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:32-49 — `findType` = primitive → primitive-extension → `isClassFqn` → `isEnumFqn` → empty; no fallback
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:98-123 — `compileAll` DROP-AT-OVERLOAD; `if (typed.isEmpty() && first != null) throw first;` — all overloads broken ⇒ the classify exception is what the caller sees
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:426 and :451 — legend-lite carries `relation::SelectSQLQuery` and `relation::TdsSelectSqlQuery` but re-parents them onto `RelationalOperationElement`; the abstract `metamodel::SQLQuery` base is absent from all 199 nativeClass declarations
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1183-1207 — `unknownTypePull` requires EXACTLY ONE `elementSource` entry whose simple name matches; the corpus declares no `…::SQLQuery`, so the pull returns null and the message becomes the wall
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1288-1316 — the no-execute path: `tryRunNoExecute` then `Status.SHAPE, "no execute(|...) call [calls …] — wall: " + attempted.wall()`, the exact brief string
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:169-177 — `pureToSQLQuery/pureToSQLQuery.pure` is registered via `registerLibrarySource`, confirming the file IS in the element universe (so 'not loaded' is NOT the cause; signature drop is)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:705 — the recorded global drop for `toSQLQuery` on `ValueSpecification`

</details>

---

## `testFindAliasMappingBySchemaName`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | M |
| confidence | high |

**Root cause**

`meta::relational::metamodel::TableAlias` has a QUALIFIED (derived) property `relation(){$this.relationalElement->cast(@Relation)}:Relation[1]` in legend-pure (relational.pure:205-212). legend-lite's native port of that class (Pure.java:346) declares only the data property `schema` and inherits `name`/`relationalElement` from `Alias` — no derived properties at all; in fact NO native class in Pure.java declares a qualified property (`grep -n '(){' Pure.java` returns nothing across all 199 `nativeClass` declarations). So `$tableAliasToTable1.relation()` is not recognised as a property call, falls through to function dispatch, `functionCandidates` returns empty, and `Typer.checkGeneric` throws the catalog-miss at Typer.java:1448-1451. As with the other tests, the wall surfaces at the first assert (`assertEquals('s1', $found1->toOne().second.relation()->cast(@Table).schema.name)`) because `let found1 = findAliasMappingBySchemaName($tableAliasToTable1.relation(), ...)` was bound unevaluated. This is the ONE test in the unit whose immediate wall is a legend-lite prelude-fidelity gap rather than an absent subsystem — the sibling white-box tests in the same file (`testMergeOldAliasToNewAlias`, `testReAliasMergedJoinOperations`) already pass, so legend-lite genuinely can host engine Pure helpers over this metamodel when the surface is complete.

**Fix**

FIXABLE, but it is a three-part metamodel-fidelity change in the prelude, and part 2 carries a real structural risk. In `core/src/main/java/com/legend/builtin/Pure.java`:

1. `TABLE_ALIAS_METACLASS` (line 346) — add the qualified property verbatim from legend-pure relational.pure:211, fully qualified because the prelude string has no imports:
   `native Class meta::relational::metamodel::TableAlias extends meta::relational::metamodel::Alias { schema: meta::pure::metamodel::type::String[0..1]; relation(){$this.relationalElement->cast(@meta::relational::metamodel::relation::Relation)}:meta::relational::metamodel::relation::Relation[1]; }`

2. `TABLE_METACLASS` (line 417) — retype `schema` from `Any[0..1]` to `meta::relational::metamodel::Schema[0..1]` so `->cast(@Table).schema.name` navigates. NOTE: `SCHEMA_METACLASS` (line 489) already holds `tables: Table[*]`, so this closes a Table↔Schema value-layout cycle. The prelude comments at Pure.java:440 (`CommonTableExpression.sqlQuery` typed `Any` to break the CTE↔SelectSQLQuery cycle) say this platform's instance-value layout does not tolerate cycles. Verify the layout builder before making this edit; if it does not tolerate it, this test cannot be unblocked without that work and should be ledgered instead.

3. `VIEW_METACLASS` (line 277) — widen to the real shape: `native Class meta::relational::metamodel::relation::View extends meta::relational::metamodel::relation::NamedRelation { schema: meta::relational::metamodel::Schema[0..1]; primaryKey: meta::relational::metamodel::Column[*]; columnMappings: meta::relational::mapping::ColumnMapping[*]; userDefinedPrimaryKey: meta::pure::metamodel::type::Boolean[1]; mainTableAlias: meta::relational::metamodel::TableAlias[1]; }`. Changing the supertype from `ModelElement` to `NamedRelation` is the load-bearing part (the `v:View[1]` match arm needs View <: RelationalOperationElement) and is also the highest-blast-radius part — the typeInference family consumes View today.

Do all three or none: each alone only moves the wall one step (relation() → Table.schema is Any → View has no schema).

**How legend-engine does it** — legend-pure `legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:211` — `relation(){$this.relationalElement->cast(@Relation)}:Relation[1];` on `TableAlias`; consumed by legend-engine `core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:9010-9012`.

**Risk** — Part 3 (View's supertype ModelElement → NamedRelation) is the dangerous one: the typeInference family is documented at Pure.java:274-277 as consuming this exact View shape host-side over legend-lite's own `DatabaseDefinition`. Re-parenting it changes subsumption for every `match`/`instanceOf` over View in that family. Part 2 may break the instance-value layout (cycle) — the prelude already works around exactly this for CTE↔SelectSQLQuery at Pure.java:440. Tenet-2 trap: do NOT special-case `relation` in the harness or in `ExecCallFinder`/`EngineTestExecutor` to rewrite `$alias.relation()` into `$alias.relationalElement`. The derived property belongs to the metamodel the platform owns; a harness rewrite would be textbook harness compensation and would leave the engine's own `pureToSQLQuery.pure:9021/9025/9049/9067` call sites still broken.

**Also unblocks** — Nothing else in this unit. It would however unblock the engine's own `reprocessJoin`/`mergeJoinTreeNodes` call sites (pureToSQLQuery.pure:9021, 9025, 9049, 9067), which any future test over those helpers needs.

**Falsifier** — Cheapest: `grep -n 'relation()' core/src/main/java/com/legend/builtin/Pure.java` — a hit means `TableAlias.relation()` already exists and the wall has another cause. Next cheapest, to test the 'only one step' claim before committing to the whole change: add only part 1, run `-Drcorpus.only=pureToSQLQuery LL_TMP_DEBUG=1`, and read the `[try-run]` line. My prediction is the wall moves to a `Table.schema`/`View` property miss, NOT to PASS. If it goes straight to PASS, parts 2 and 3 are unnecessary. If it does not move at all, `relation()` is not being resolved as a derived property on a NATIVE class and there is a second gap in derived-property lookup for `Pure.findNativeClass` results.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:205-212 — `Class meta::relational::metamodel::TableAlias extends Alias { setMappingOwner...; database...; schema : String[0..1]; relation(){$this.relationalElement->cast(@Relation)}:Relation[1]; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:346 — `native Class meta::relational::metamodel::TableAlias extends meta::relational::metamodel::Alias { schema: String[0..1]; }` — the `relation()` qualified property is simply not there
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1452 — `if (candidates.isEmpty()) throw new TypeInferenceException("unknown function '" + af.function() + "' — no function of this name in the native or user catalog (unported platform function, or a misspelling)")`, the exact wall literal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:146-167 — `nativeClass(String)` runs the REAL `ElementParser` in LEGEND_PLATFORM dialect, so a derived property is expressible in the prelude string without any parser work
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/ClassDefinition.java:48-65 — `ClassDefinition` already carries `List<DerivedPropertyDefinition> derivedProperties`, so the model side supports it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:417 — `Table … { columns: Column[*]; schema: Any[0..1]; }` — `schema` is typed `Any`, so `->cast(@Table).schema.name` cannot navigate
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:277 — `native Class meta::relational::metamodel::relation::View extends meta::pure::metamodel::ModelElement { columnMappings: ColumnMapping[*]; }` — no `name`, `schema`, `primaryKey`, `mainTableAlias`, `userDefinedPrimaryKey`, and the wrong supertype
- /Users/neemsandv/legend/legend-pure/.../platform_store_relational/grammar/relational.pure:92-98 and :105-119 — real `Table extends NamedRelation, AnnotatedElement { schema : Schema[1]; … }` and `View extends NamedRelation, RelationalMappingSpecification, AnnotatedElement { schema : Schema[1]; … }` with `userDefinedPrimaryKey`/`mainTableAlias` coming from `RelationalMappingSpecification`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:9008-9016 — the function under test, `findAliasMappingBySchemaName(relation:RelationalOperationElement[1], map:OldAliasToNewAlias[*])`, dispatches `$relation->match([t:Table[1]|…$t.schema.name…, v:View[1]|…$v.schema.name…, r:RelationalOperationElement[1]|$map])`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:46 — `| pureToSQLQuery/tests | 14 | 6 | 0 | 0 | 8 | 0 |` — 6 of the 14 white-box tests in this file already pass, so the family is not categorically out of reach

</details>

---

## `testFindFunctionSequenceMultiplicity`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

`ZeroMany` / `PureOne` / `ZeroOne` / `OneMany` are M3 bootstrap INSTANCES of `Multiplicity` living under `meta::pure::metamodel::multiplicity` in legend-pure's m3.pure graph — they are values in the Pure graph, not classes or enums. legend-lite models multiplicity as a compile-time Java record (`com.legend.compiler.element.type.Multiplicity`, used by `TypeClassifier.multiplicity`), so there is no packageable element named `ZeroMany` anywhere. The test's first assert is `assertEquals(pair('employees',ZeroMany), $assertMultiplicity->eval($firmLambda, 0))`; the EXPECTED arm is typed first, `ZeroMany` arrives at `Typer.classReference` as a bare `PackageableElementPtr`, misses findClass/findEnum/isDatabase/findMapping/isExecutionContextElement/functionCandidates in turn, and falls off the end into the ResolutionException at Typer.java:2294. That is why the wall names `ZeroMany` and not the textually-earlier `deactivate` / `InstanceValue` / `LambdaFunction<...>`: `EngineTestExecutor` binds `let firmLambda = ...` symbolically without typing it (EngineTestExecutor.java:468) and only asserts force typing. Behind `ZeroMany` sit three more absences of the same family: `meta::pure::functions::meta::deactivate` is not in the catalog (only `evaluateAndDeactivate`, Pure.java:1509), `InstanceValue`/`FunctionExpression`/`AbstractProperty` are not native classes, `LambdaFunction.expressionSequence` is not a property (Pure.java:528 declares `LambdaFunction<F>` with an empty body), and the function under test — `meta::relational::functions::pureToSqlQuery::findFunctionSequenceMultiplicity(v:ValueSpecification[1]):Pair<FunctionExpression,Multiplicity>[*]` (pureToSQLQuery.pure:4215) — drops at signature compile on `ValueSpecification`.

**Fix**

DO NOT FIX — ledger it under the engine-self-metamodel bucket. The test asserts the multiplicity-inference behaviour of an engine helper that walks a COMPILED Pure expression tree. Making it runnable is not a bug fix, it is a new subsystem: (a) reify M3 `meta::pure::metamodel::valuespecification::*` (ValueSpecification, FunctionExpression, SimpleFunctionExpression, InstanceValue, VariableExpression) and `meta::pure::metamodel::function::property::AbstractProperty` as native classes in Pure.java; (b) reify `Multiplicity` as a class plus the four package-level singleton instances `PureOne/ZeroOne/ZeroMany/OneMany` — which requires a new element kind (a packageable INSTANCE), since `Typer.classReference` today only knows class/enum/database/mapping/runtime/function; (c) give `LambdaFunction` an `expressionSequence` property and make `deactivate()` return the un-evaluated expression tree, i.e. build a reflective view of the Typer's own output. That is legend-engine's compiler-in-Pure design, which legend-lite explicitly rejects (Java `compiler/spec` + `lowering`). If the project ever wants this row green, the honest vehicle is a Pure-visible reflection facade over `TypedSpec`, scoped as its own project — not a patch. Keep the wall.

**How legend-engine does it** — legend-pure `legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/grammar/m3.pure:223-224` — `ZeroMany` is an m3 bootstrap instance under `meta::pure::metamodel::multiplicity`, referenced by path, and legend-engine's `pureToSQLQuery.pure:4215` returns `Pair<FunctionExpression,Multiplicity>` over it.

**Risk** — The tempting harness compensation is to teach `Typer.classReference` to return an `Any[1]` sentinel for unresolved bare names so `pair('employees',ZeroMany)` types. That would silently turn every genuine typo/unported-element error in the whole corpus into a passing-but-meaningless comparison. Do not weaken Typer.java:2294.

**Also unblocks** — tesIsToOneDataTypeFunctionExpressionSequence* siblings in the same file, and every corpus test that reaches the M3 reflection metamodel

**Falsifier** — If `meta::pure::metamodel::multiplicity::ZeroMany` (or a `Multiplicity` class) turns out to be resolvable in legend-lite, this diagnosis is wrong. Cheapest check: `grep -rn 'multiplicity::ZeroMany\|PureOne\|OneMany' core/src/main/java/com/legend/builtin/Pure.java core/src/main/java/com/legend/compiler/spec/Typer.java` — a hit falsifies. Second cheapest: run with `LL_TMP_DEBUG=1 -Drcorpus.only=pureToSQLQuery` and read the `[try-run]` line; if the reported first failure is something other than a `classReference` miss on `ZeroMany`, the ordering story is wrong (the absence still is not).

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2294 — the exact wall literal: `throw new ResolutionException("'" + ref.fullPath() + "' is not a known class, mapping, runtime, connection, or database" + (contains("::") ? "" : " — user elements in a query need a fully qualified name"))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2203-2291 — `classReference` tries class, enum, database, mapping, execution-context element, then function-reference eta-expansion; a multiplicity singleton matches none of these arms
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/grammar/m3.pure:223-224 — `ZeroMany` is referenced as `Root.children[meta].children[pure].children[metamodel].children[multiplicity].children[ZeroMany]`, i.e. a bootstrap graph INSTANCE, not a type declaration
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:468 — `lets.put(name.value(), purifiedSetup(rhs, ctx))`: a plain let is bound UNEVALUATED, so `let firmLambda = {...}->deactivate()->cast(@InstanceValue)...` never types until an assert reads it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:499-515 — the `assert*` arm is where `checkAssert` forces typing of the substituted arguments
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:4215 — `function meta::relational::functions::pureToSqlQuery::findFunctionSequenceMultiplicity(v:ValueSpecification[1]):Pair<FunctionExpression,Multiplicity>[*]`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:528 — `native Class meta::pure::metamodel::function::LambdaFunction<F> extends FunctionDefinition<F> {}` — empty body, so no `expressionSequence`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1565-1573 — the platform states it itself: `SimpleFunctionExpressions — reflection metamodel this platform lacks`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/RELATIONAL_CORPUS.md:705 — the recorded global drop: `meta::relational::functions::pureToSqlQuery::toSQLQuery => Unknown type: 'ValueSpecification' is not a known primitive, class, or enum`

</details>

---

## `testImportDataFlow`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

Two independent absences, either of which alone walls the test. (a) The reported wall: the test ends with `$sql->meta::relational::functions::sqlQueryToString::sqlQueryToString(DatabaseType.H2, relationalExtensions())`; all five `sqlQueryToString` overloads take `sqlQuery:SQLQuery[1]` (dbExtension.pure:388,400,408,417,425), `SQLQuery` is legend-pure's (relational.pure:200) and is not in legend-lite's native catalog, so `FunctionCompiler.compileAll` drops every overload and rethrows `TypeClassifier`'s `Unknown type: 'SQLQuery' …`. Upstream of that the test also needs `meta::pure::router::routeFunction(f,mapping,runtime,exeCtx,extensions,debug)`, which lives in engine-core `core/pure/router/deprecated/deprecated.pure:24` — a tree `RelationalCorpusRunner` never loads (it loads only the m2m test library, `core/pure/graphFetch/domain`, and `pureToSQLQuery/pureToSQLQuery.pure`) — plus `StoreMappingClusteredValueSpecification`, `byPassValueSpecificationWrapper`, and `TdsSelectSqlQuery` reflection. (b) SEPARATELY AND MORE IMPORTANTLY: the FEATURE under test is unimplemented. `importDataFlow` / `importDataFlowAddFks` / `importDataFlowFksByTable` (RelationalExecutionContext fields, executionContext.pure:29-33) drive the engine to append foreign-key columns (`fk_ADDRESSID`, `fk_j0_0_STREET`, …) to the projection. A full grep of `core/src/main/java/` for `importDataFlow` returns ZERO hits — legend-lite has no such execution option anywhere in `resolver/`, `lowering/`, `validation/`, or `plan/`. Contrast `addDriverTablePkForProject`, which IS implemented (`validation/DriverPkAppend.java`, `validation/DriverPkOption.java`). So this row is a genuine unimplemented-surface row wearing an engine-internals wall.

**Fix**

DO NOT FIX AS WRITTEN — ledger the row, but SPLIT it in the ledger, because the two halves have different dispositions. (1) The `toSQLQuery`/`routeFunction`/`sqlQueryToString` access path is engine-self-metamodel and should stay walled forever, same as the other three tests. (2) The `importDataFlow` FEATURE is a genuine unimplemented surface and deserves its own ledger entry: the engine's semantics are 'when `importDataFlowAddFks` is set, append, after the user's projected columns, one `fk_<COLUMN>` alias per foreign-key column named by `importDataFlowFksByTable`, for the driver table and for every joined table reached in the projection, with the join-path prefix `fk_j<n>_`'. If that is ever built, the natural home is right next to `validation/DriverPkAppend.java` — the same column-append shape over the same `TypedProject` node, keyed off a new `ImportDataFlowOption` mirroring `validation/DriverPkOption.java` — and it should be asserted by an ordinary `execute(|…)`/golden-SQL corpus test, never through `toSQLQuery`. Do not implement it speculatively off this test: the golden string here is H2 alias-naming (`"addressTable_d#4_d_m1"`) produced by the engine's own alias generator, which legend-lite does not and should not reproduce.

**How legend-engine does it** — legend-engine `core_relational/relational/runtime/executionContext/executionContext.pure:29-33` declares the option; `core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:99-103` carries it through the compiler `State` (`importDataFlow`, `importDataFlowAddFks`, `importDataFlowFksByTable : Map<Relation, List<Column>>[0..1]`, `importDataFlowCurrentSetOffsetInUnion`, `importDataFlowImplementationCount`), and :306 threads it from the execution context into the state.

**Risk** — Two traps. First, tenet-2: do not implement `importDataFlow` inside the harness or inside `EngineTestExecutor` to satisfy this one golden string — it is an execution-context option and belongs in the platform's typed-spec rewrite stage beside `DriverPkAppend`. Second, do not chase the golden SQL text: the expected string encodes the ENGINE's alias generator (`addressTable_d#4_f_d_d_m2_r`), and matching it would mean adopting legend-engine's internal alias-naming scheme purely to satisfy a text compare — a GOLDEN_TEXT_ONLY concern layered on top of a real feature gap. Assert FK columns by row/column-name, not by the engine's alias spelling.

**Also unblocks** — simpleFunctionExpressionTranslationNow, simpleFunctionExpressionTranslationAdjust, addDriverTablePkForProject (the shared metamodel half only — the importDataFlow feature half is unique to this test)

**Falsifier** — For the feature half: `grep -rn 'importDataFlow' core/src/main/java/` returning any hit falsifies 'unimplemented'. For the wall half: `grep -n 'metamodel::SQLQuery' core/src/main/java/com/legend/builtin/Pure.java` returning a hit falsifies the emitter attribution. I did not observe the demand-pull sequence in `Runner.java:1132-1174` execute, so which of the several absences surfaces FIRST is inferred; run `LL_TMP_DEBUG=1 -Drcorpus.only=pureToSQLQuery` and read the `[try-run]` line to confirm ordering.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:296-328 — the test body: builds `^RelationalExecutionContext(importDataFlow=true, importDataFlowAddFks=true, importDataFlowFksByTable=…)`, calls `routeFunction`, `toSQLQuery`, `sqlQueryToString`, and asserts a golden SQL string containing `fk_ADDRESSID`, `fk_j0_0_STREET`, `fk_j0_1_STREET`, `fk_j1_ADDRESSID`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/runtime/executionContext/executionContext.pure:25-35 — `Class meta::relational::runtime::RelationalExecutionContext … { addDriverTablePkForProject : Boolean[0..1]; … importDataFlow : Boolean[0..1]; importDataFlowAddFks : Boolean[0..1]; importDataFlowFksByTable : Map<Relation, List<Column>>[0..1]; importDataFlowImplementationCount : Integer[0..1]; }`
- `grep -rn 'addDriverTablePkForProject|importDataFlow' core/src/main/java/` — returns hits ONLY for addDriverTablePkForProject (StatementExecutor.java:50,342; EngineTestExecutor.java:575; ValidateDesugar.java:206; DriverPkAppend.java:24,75,81,120; DriverPkOption.java:7) and ZERO for importDataFlow
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/validation/DriverPkAppend.java:23-31 — the sibling option IS implemented in Java, which is what makes the importDataFlow absence a real gap rather than a category difference
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/deprecated/deprecated.pure:24 — `function <<doc.deprecated>> meta::pure::router::routeFunction(f:FunctionDefinition<Any>[1], mapping:Mapping[1], runtime:Runtime[1], exeCtx:ExecutionContext[1], extensions:Extension[*], debug:DebugContext[1]):FunctionDefinition<Any>[1]` — the exact 6-arg overload the test calls, in a tree legend-lite does not load
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:129-177 — the complete list of foreign trees loaded: `Corpus.M2M_TESTS`, `core/pure/graphFetch/domain`, `pureToSQLQuery/pureToSQLQuery.pure`. `core/pure/router` is not among them
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/sqlQueryToString/dbExtension.pure:388 — the `SQLQuery[1]` parameter that emits the reported wall
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — the emitting line

</details>

---

## `testMergeOldAliasToNewAlias`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

legend-lite's element compiler DISCARDS the type arguments of a parameterized superclass, so an inherited property's type is returned with the superclass's raw type VARIABLE instead of the subclass's instantiation.

Mechanism, end to end:
1. The corpus declares `Class meta::relational::functions::pureToSqlQuery::OldAliasToNewAlias extends Pair<String, TableAlias>{}` (pureToSQLQuery.pure:65). `Pair<U,V>` declares `first: U[1]; second: V[1]`.
2. `ClassCompiler.compile` flattens every superclass through `TypeClassifier.headFqn(sup)` into a bare FQN string (ClassCompiler.java:38-41). `<String, TableAlias>` is thrown away at that instant. `TypedClass` has no field that could hold it — `superClassFqns` is `List<String>` (TypedClass.java:30).
3. `PureModelContext.findProperty("...OldAliasToNewAlias", "second")` finds nothing locally, recurses into the super FQN, and returns Pair's own `Property.Stored("second", TypeVar("V"), [1])` verbatim — there is no substitution step (PureModelContext.java:196-201).
4. Typing `mergeOldAliasToNewAlias`'s body `...->filter(p|$p.first != $p.second.name)` (pureToSQLQuery.pure:8459): `$p` is `ClassType(OldAliasToNewAlias)`, so Typer takes the `Type.ClassType ct` arm (Typer.java:2543) and yields `ExprType(TypeVar("V"), [1])` for `.second`.
5. Typing `.name` on that value: the receiver type is now `Type.TypeVar`, which matches NO arm of the member switch and falls to `default -> throw new TypeInferenceException("cannot access '" + ap.property() + "' on " + source.info().type().typeName())` (Typer.java:2597-2598). `TypeVar.typeName()` returns the bare name (Type.java:288-291), producing exactly `on V`.
6. `SpecCompiler.compile` wraps it as `"in function '" + fn.qualifiedName() + "': " + e.getMessage()` (SpecCompiler.java:69-70) — byte-for-byte the observed wall.

Note the asymmetry that hid this: the Typer ALREADY does correct positional instantiation when the RECEIVER is generic (`Type.GenericType g` arm, Typer.java:2574-2589, binding `cls.typeParameters()[i] -> g.arguments()[i]` then `kernel.resolve`). The identical instantiation in the INHERITANCE direction is simply absent. And `^OldAliasToNewAlias(first='1', second=...)` does not wall, because `NewChecker` unifies `TypeVar(V)` against the value with a FRESH `Bindings` (NewChecker.java:97), which binds V to anything — so the wrong type stays silent until someone navigates through it.

**Fix**

Carry the generalization's type arguments through Phase F and substitute them at property lookup. Three edits:

1. `core/src/main/java/com/legend/compiler/element/TypedClass.java` — replace the `List<String> superClassFqns` component with `List<Type> superTypes` (a `Type.ClassType` for a bare super, a `Type.GenericType` for `Pair<String, TableAlias>`), and keep `superClassFqns()` as a DERIVED accessor mapping each `superTypes()` entry to its head FQN (`ClassType::fqn` / `GenericType::rawFqn`). That keeps all 8 existing `superClassFqns()` call sites (ClassLayouts, ModelContext, PureModelContext, Temporal, GraphEmission, ValidateDesugar) compiling untouched — the change is additive, not a rewrite.

2. `core/src/main/java/com/legend/compiler/element/ClassCompiler.java:38-41` — replace `superFqns.add(TypeClassifier.headFqn(sup))` with `superTypes.add(classifier.classify(sup, typeParams))`. `classify` already handles the Generic form (TypeClassifier.java:94-100) and, because `typeParams` is passed, `Class Foo<T> extends List<T>` correctly yields `GenericType(List, [TypeVar(T)])` rather than a resolution error.

3. `core/src/main/java/com/legend/compiler/element/PureModelContext.java:196-201` — in the super loop, after `findProperty(superFqn, name)` returns present, if the super entry is a `Type.GenericType g`, build `Map<String,Type> bind = zip(findClass(superFqn).get().typeParameters(), g.arguments())` and return the property with its type (and, for `Property.Derived`, its parameter types) run through a substituter. Wall loudly if the arities disagree — the same check the Typer already makes at Typer.java:2580-2584.

The substituter must be a NEW, LENIENT function — do not reuse `InferenceKernel.resolve` (InferenceKernel.java:626-632): it throws on an unbound variable and also unwraps `Relation<row>`, neither of which is wanted here. Put a plain structural `Type substitute(Type, Map<String,Type>)` next to `Type` (leaves ride through; `TypeVar` not in the map rides through unchanged) so `compiler.element` never has to depend on `compiler.spec`'s `Bindings`.

`Property` is a sealed pair (Stored/Derived) — add a `withType(...)` to both arms; the Derived arm must also substitute its `TypedParameter` types. Transitivity is free: the recursive call already returns the grandparent-substituted type, and the child's bindings then compose over it.

No change is needed in `NewChecker` or `Typer` — both go through `ModelContext.findProperty` and inherit the fix.

**How legend-engine does it** — legend-pure resolves exactly this by walking the inheritance tree and binding type parameters to type arguments at each generalization: `GenericType.resolveClassTypeParameterUsingInheritance(genericTypeSource, genericTypeToFind, processorSupport)` at /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/java/org/finos/legend/pure/m3/navigation/generictype/GenericType.java:65-80, which seeds the walk with `bindTypeParametersToTypeArguments(...)`. Property types specifically go through `GenericType.resolvePropertyReturnType` at the same file:303-313, which calls `resolveClassTypeParameterUsingInheritance` when the declared return type is not concrete (i.e. is a type parameter) and then `reprocessTypeParametersUsingGenericTypeOwnerContext` (same file:315-321). legend-lite has the leaf operation (Typer.java:2574-2589) but not the inheritance walk.

**Risk** — This TIGHTENS types that were previously the free variable `TypeVar`, which unified with anything via `new Bindings()` at NewChecker.java:97. Constructions and conformance checks that silently passed will start being checked for real, so new walls can surface anywhere a corpus class extends a parameterized class. The sharpest exposure is in the same file: `Class PureFunctionToRelationalFunctionPair extends Pair<Function<Any>, Function<{FunctionExpression[1], ... ->RelationalOperationElement[1]}>>` (pureToSQLQuery.pure:78) and `PureFunctionTDSToRelationalFunctionPair` (pureToSQLQuery_deprecated.pure:26) — after the fix, `.second` on those becomes a full `Type.FunctionType` and every `->eval(...)` over it must conform. Gate the change on a full sweep, not on this one test.

Tenet-2 trap to avoid: do NOT special-case `OldAliasToNewAlias`, and do NOT teach `Typer`'s `default ->` arm at line 2597 to tolerate a `TypeVar` receiver by falling back to Any. Both would be harness/compiler compensation for a Phase-F data loss and would convert a loud wall into silently wrong types everywhere. The information must be restored where it was discarded (ClassCompiler.java:38-41).

**Also unblocks** — testFindAliasMappingBySchemaName (testPureToSql.pure:204-222) reads `$found1->toOne().second.relation()` and `$found2->toOne().second.relation()` off `OldAliasToNewAlias`, and the function it tests, `findAliasMappingBySchemaName`, does `$p.second.relation()` at pureToSQLQuery.pure:9011-9012 — the identical inherited-generic navigation, so it hits the identical wall. Any corpus function that navigates through `.first`/`.second` of an `OldAliasToNewAlias` is in the same class: milestoning.pure:123,180 and testDataGeneration.pure:461,547,979,1076 all construct them. I did not verify which of those tests currently fail, so treat this as a candidate list, not a promise.

**Falsifier** — Add `Class T extends Pair<String, meta::relational::metamodel::TableAlias>{}` in a scratch module and type `{p:T[1]| $p.second.name}`. If it types clean today, the inheritance-substitution diagnosis is wrong. Conversely — and this is the honest limit of my confidence — the fix makes the WALL go away, not necessarily the test go green: after typing succeeds, the test still has to EVALUATE `^OldAliasToNewAlias(...)` object values through `concatenate`/`removeDuplicatesBy`/`filter`, and `OldAliasToNewAlias` is NOT in `HostEval.HOST_CONSTRUCTION_CLASSES` (HostEval.java:105-111), so it will take the lowering/SQL path instead. Probe that separately with `LL_TMP_DEBUG=1` on this single test after the typer fix; if a second wall appears there it is a distinct, downstream defect.

<details><summary>Evidence read (16 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:65 — `Class meta::relational::functions::pureToSqlQuery::OldAliasToNewAlias extends Pair<String, TableAlias>{}`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8458-8459 — the function under test; body ends `->filter(p|$p.first != $p.second.name)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:149-154 — the test body; asserts `['1','3'] == $res.first`
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/anonymousCollections.pure:17-20 — `Class meta::pure::functions::collection::Pair<U,V>` with `first : U[1]; second : V[1]`
- core/src/main/java/com/legend/builtin/Pure.java:548 — legend-lite's PAIR native mirrors it: `Pair<U, V> { first: U[1]; second: V[1]; }`
- core/src/main/java/com/legend/compiler/element/ClassCompiler.java:38-41 — `for (TypeExpression sup : cd.superClasses()) superFqns.add(TypeClassifier.headFqn(sup));` — the type arguments are dropped here
- core/src/main/java/com/legend/compiler/element/TypeClassifier.java:122-126 — `headFqn` returns `g.name()` for a `TypeExpression.Generic`, i.e. the raw head only
- core/src/main/java/com/legend/compiler/element/TypedClass.java:30 — the field is `List<String> superClassFqns`; there is nowhere to keep `<String, TableAlias>`
- core/src/main/java/com/legend/compiler/element/PureModelContext.java:196-201 — `for (String superFqn : ...superClassFqns()) { Optional<Property> inherited = findProperty(superFqn, name); if (inherited.isPresent()) return inherited; }` — returned unsubstituted
- core/src/main/java/com/legend/compiler/spec/Typer.java:2543-2568 — the ClassType arm yields `new ExprType(prop.type(), prop.multiplicity())` straight from findProperty, so `TypeVar("V")` escapes
- core/src/main/java/com/legend/compiler/spec/Typer.java:2597-2598 — `default -> throw new TypeInferenceException("cannot access '" + ap.property() + "' on " + source.info().type().typeName())` — the emitting line
- core/src/main/java/com/legend/compiler/element/type/Type.java:283-291 — `record TypeVar(String name)` whose `typeName()` returns `name`, i.e. `V`
- core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:69-70 — `"in function '" + fn.qualifiedName() + "': " + e.getMessage()` — the wall prefix
- core/src/main/java/com/legend/compiler/spec/Typer.java:2574-2589 — the GenericType RECEIVER arm already binds `cls.typeParameters()` to `g.arguments()` and calls `kernel.resolve` — the same operation, present in one direction only
- core/src/main/java/com/legend/compiler/spec/NewChecker.java:97 — `t.kernel().unify(prop.type(), value.info().type(), new Bindings())` — a fresh Bindings makes `TypeVar(V)` accept anything, which is why construction stayed silent
- core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:626-632 — `resolve(Type, Bindings)` THROWS `unbound type variable` on an unbound var, so it cannot be reused as-is for a lenient inheritance substitution

</details>

---

## `testReAliasMergedJoinOperations`

| | |
|---|---|
| family | `pureToSQLQuery/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

Two layers, and the reported message is only the shallow one.

SHALLOW (why THIS message): `meta::relational::metamodel::join::Join` — a legend-pure PLATFORM class (relational.pure:173-180: `name:String[1]; database:Database[0..1]; target:TableAlias[0..1]; aliases:Pair<TableAlias,TableAlias>[*]; operation:Operation[1]`) — is absent from legend-lite's builtin catalog. `com.legend.builtin.Pure` ports its siblings (`RelationalTreeNode`, `RootJoinTreeNode`, `JoinTreeNode`, `TableAlias`, `Column`, `TableAliasColumn`, `DynaFunction`) but never `join::Join`; the omission is visible in `JOIN_TREE_NODE`, whose `join` property is degraded to `Any[0..1]` where real pure writes `Join[1]`. The corpus root the harness scans is core_relational/relational only (Corpus.java:52), so the class cannot be demand-pulled either — it lives in the legend-pure repo.

That makes `NameResolver.resolveNameMulti` walk off the end for the bare `Join` in `buildJoinTreeNode` (testPureToSql.pure:231): tier 1 (the file's wildcards, which DO include `meta::relational::metamodel::join::*`, testPureToSql.pure:20) finds no candidate in `knownFqns` because the FQN is not declared and not native; tier 2 (own package) misses; tier 3 (prelude) hits `PRELUDE_TYPES.get("Join")`, whose ONLY entry is the SQL-metamodel `Join` (Pure.java:336) — and because there is exactly one, the `PRELUDE_COLLISIONS` wildcard tie-break arm never fires. Bare `Join` binds to `meta::external::query::sql::metamodel::Join`. `NewChecker.check` then finds the class (it is native), fails `findProperty(..., "name")` — SQLN_JOIN has only `type/left/right/criteria` — and throws `"class '" + ni.className() + "' has no property '" + name + "'"` (NewChecker.java:93-94), wrapped by SpecCompiler.java:69-70. That is the observed string exactly.

This silent cross-namespace capture is a real hazard in its own right, but fixing it only MOVES the wall.

DEEP (why the test cannot pass): the test is a white-box driver of legend-engine's Pure-implemented SQL compiler. It calls `buildAndTransformJoinMetaData` (pureToSQLQuery.pure:8449-8456) and `reAliasMergedJoinOperations` (:8462-8471) and asserts on `JoinTreeMetaData.joinAliases/jtnAliases/missingJoinAliases` — the internal bookkeeping of the engine's join-tree MERGE algorithm. legend-lite implements join-tree construction and re-aliasing in Java (resolver/lowering), not in Pure, so there is no counterpart for these asserts to bind to. Running them as generic Pure would require a stack legend-lite does not have: `meta::relational::metamodel::children` (legend-pure functions.pure:288-291, absent from legend-lite — grep for `children` in Pure.java returns nothing), `gatherAllOperations`, `reprocessJoin`, `findNode`, `reprocessAliases` (all absent), the `JoinTreeMetaData` derived property `missingJoinAliases()`, copy-with-update over native classes (`^$target(childrenData=..., join=...)`), and higher-order dispatch `$joinTransform->eval($target.join, $parentJtns)` — which additionally cannot type today because `JoinTreeNode.join` is `Any[0..1]` in legend-lite while the transform's parameter is `Join[1]`.

**Fix**

Do NOT try to make this test pass — ledger it as an engine-internals test. legend-lite implements join-tree merge and re-aliasing in Java (resolver/lowering); reimplementing legend-engine's Pure version so its intermediate `JoinTreeMetaData` bookkeeping can be asserted would be building a second, parallel compiler purely to satisfy a white-box test. There is no user-visible behavior behind it.

DO make the wall honest — it currently lies, and the lie is a latent correctness hazard for every other file. One edit:

`core/src/main/java/com/legend/builtin/Pure.java`, immediately after `JOIN_TREE_NODE` (line 436), add:
```java
/** Real relational.pure:173 (database is a reference — Any per this prelude's convention; Operation collapses under RelationalOperationElement, same as JOIN_STRINGS_OP at :444). */
public static final ClassDefinition JOIN_METACLASS = nativeClass(
  "native Class meta::relational::metamodel::join::Join extends meta::pure::metamodel::type::Any {"
  + " name: meta::pure::metamodel::type::String[1];"
  + " database: meta::pure::metamodel::type::Any[0..1];"
  + " target: meta::relational::metamodel::TableAlias[0..1];"
  + " aliases: meta::pure::functions::collection::Pair<meta::relational::metamodel::TableAlias, meta::relational::metamodel::TableAlias>[*];"
  + " operation: meta::relational::metamodel::RelationalOperationElement[1]; }");
```
This registers the FQN in `Index.CLASS_BY_FQN`, so `NameResolver`'s tier-1 wildcard (`meta::relational::metamodel::join::*`) now claims bare `Join` and shadows the prelude — and, as a bonus, `Join` becomes a two-entry `PRELUDE_COLLISIONS` key so unimported files get the wildcard tie-break instead of an arbitrary winner. Optionally retype `JOIN_TREE_NODE.join` from `Any[0..1]` to `meta::relational::metamodel::join::Join[1]` to match relational.pure:151 — but only if nothing downstream depends on the Any relaxation.

After that, this test will wall honestly on `meta::relational::metamodel::children` (or on the higher-order `$joinTransform->eval(...)`), which is the truthful diagnosis: an unimplemented engine-internal Pure surface, not a bogus property error on an unrelated SQL-AST class. Then ledger it.

**How legend-engine does it** — The relational join metamodel this test drives is legend-pure platform data: /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:173-180 declares `meta::relational::metamodel::join::Join` with exactly the `name/database/target/aliases/operation` properties `buildJoinTreeNode` sets, and :288-291 of the sibling functions.pure defines `children()` over `RelationalTreeNode`. The algorithm under test is legend-engine's own Pure compiler: buildAndTransformJoinMetaData / reAliasMergedJoinOperations / merge at .../core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:8449, :8462, :8473.

**Risk** — Adding `join::Join` shifts the arbitrary winner for bare `Join` in files that import NEITHER `meta::relational::metamodel::join::*` NOR `meta::external::query::sql::metamodel::*`: `PRELUDE_TYPES` is a HashMap built in declaration order (NameResolver.java:246-254), so the later-declared relational Join would win where the SQL Join wins today. Sweep for bare `Join` under a sql-metamodel import before landing; any such file should be resolving through its wildcard anyway (tier 1 beats prelude), so exposure should be nil — but verify rather than assume.

Tenet-2 trap to avoid: do NOT reach for the harness. Do not add `Join` to a runner-side alias map, do not special-case the test's imports, and do not teach `ExecCallFinder`/`Runner` to skip or fake this test. The missing platform class is a PLATFORM gap and belongs in `builtin/Pure`. Equally, do not chase the deep layer by stubbing `children()`/`gatherAllOperations` as Java natives just to move this one test — that is building the engine's Pure compiler a piece at a time with a test as the spec. A loud, accurate wall is the correct end state here.

**Also unblocks** — Any corpus file that names bare `Join` under `import meta::relational::metamodel::join::*` is currently mis-binding to the SQL-metamodel Join the same way — testDataGeneration.pure:461,547,979,1076 all construct `^Join(name='gen_join', operation=...)`, and pureToSQLQuery.pure/milestoning.pure reference `Join` throughout. I did not enumerate which of their tests currently fail, so this is a candidate set, not a verified list.

**Falsifier** — For the shallow layer: if `meta::relational::metamodel::join::Join` turns out to already be registered somewhere I did not grep (a resource-loaded prelude rather than Pure.java), the resolution diagnosis is wrong — but then the message could not name the SQL Join, so this is close to self-refuting. For the deep layer: after adding the Join builtin, re-run this single test with LL_TMP_DEBUG=1. If it walls on anything OTHER than `children`/`gatherAllOperations`/`eval`-over-`Any` — in particular if it PASSES — then the engine-internals verdict is wrong and this is a plain MISSING_FEATURE with a much shorter tail than I judged.

<details><summary>Evidence read (17 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:156-178 — the test body; asserts on `$metaData.joinAliases`, `.jtnAliases`, `.missingJoinAliases` before and after `reAliasMergedJoinOperations`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:224-233 — `buildJoinTreeNode`, the walling function; line 231 is `^Join(name=$joinName, target=$targetAlias, aliases=[...], operation=$operation)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/pureToSQLQuery/tests/testPureToSql.pure:20 — the file's import `meta::relational::metamodel::join::*`, which SHOULD have claimed the name
- /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:173-180 — `Class meta::relational::metamodel::join::Join { name : String[1]; database : Database[0..1]; target : TableAlias[0..1]; aliases : Pair<TableAlias,TableAlias>[*]; operation : Operation[1]; }`
- /Users/neemsandv/legend/legend-pure/.../platform_store_relational/grammar/relational.pure:148-156 — real `JoinTreeNode` declares `join : Join[1]` and `database : Database[1]`
- core/src/main/java/com/legend/builtin/Pure.java:336 — `SQLN_JOIN = ...meta::external::query::sql::metamodel::Join extends ...Relation { type; left; right; criteria; }` — no `name` property, hence the message
- core/src/main/java/com/legend/builtin/Pure.java:436 — `JOIN_TREE_NODE` declares `join: meta::pure::metamodel::type::Any[0..1]` — the visible scar of the missing relational Join
- core/src/main/java/com/legend/builtin/Pure.java:433-434 — RELATIONAL_TREE_NODE and ROOT_JOIN_TREE_NODE ARE ported, so the omission of Join is an oversight in a block that otherwise mirrors relational.pure
- core/src/main/java/com/legend/compiler/NameResolver.java:532-591 — `resolveNameMulti`: wildcards over `knownFqns` first, then own package, then `PRELUDE_TYPES`; the `PRELUDE_COLLISIONS` wildcard tie-break only runs when a simple name is claimed by 2+ prelude classes
- core/src/main/java/com/legend/compiler/NameResolver.java:268-281 — `preludeCollisions()` does `bySimple.values().removeIf(v -> v.size() < 2)`, so a name owned by exactly one prelude class gets NO tie-break
- core/src/main/java/com/legend/compiler/NameResolver.java:283-291 — `knownFqns` = declared element FQNs + `Pure.nativeClassFqns()` + `nativeEnumFqns()`; a class in neither is invisible to the wildcard tier
- core/src/main/java/com/legend/compiler/spec/NewChecker.java:83-94 — `findProperty(ni.className(), name).…orElseThrow(() -> new TypeInferenceException("class '" + ni.className() + "' has no property '" + name + "'"))` — the emitting line
- core/src/test/java/com/legend/rcorpus/Corpus.java:52 — the corpus root is `src/main/resources/core_relational/relational`; legend-pure's platform_store_relational is outside it, so demand-pull cannot supply the class
- /Users/neemsandv/legend/legend-engine/.../pureToSQLQuery/pureToSQLQuery.pure:8449-8456 — `buildAndTransformJoinMetaData`, whose FIRST statement is `$target->children()->map(...)`
- /Users/neemsandv/legend/legend-pure/.../platform_store_relational/functions.pure:288-291 — `function meta::relational::metamodel::children(_this:RelationalTreeNode[1]):JoinTreeNode[*] { $_this.childrenData->cast(@JoinTreeNode) }` — legend-lite has no `children` (grep of Pure.java for `children` is empty)
- /Users/neemsandv/legend/legend-engine/.../pureToSQLQuery/pureToSQLQuery.pure:8439-8447 — `Class JoinTreeMetaData { transformedJtn; jtnAliases; joinAliases; missingJoinAliases(){...}:String[*]; }` — the derived property the test asserts on
- /Users/neemsandv/legend/legend-engine/.../pureToSQLQuery/pureToSQLQuery.pure:8462-8471 — `reAliasMergedJoinOperations`, using `->children()`, `reprocessJoin`, `findNode`, `reprocessAliases`, and copy-with-update over `MergeResultContainer` (declared :8361-8370)

</details>

---

## `testPlatformExpressionDependencyOnAFromExpression`

| | |
|---|---|
| family | `router/tests` |
| sweep status | ERROR |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

The test body never executes a query. It builds `let f = {| let firstFirmLegalName = Firm.all()->meta::pure::mapping::from(simpleRelationalMapping, testRuntime())->at(0).legalName; let otherLegalName = $firstFirmLegalName + 'Test'; }` and then calls `routeFunction($f, ^ExecutionContext(), relationalExtensions(), noDebug())` — the 4-arg overload defined in legend-engine's own Pure at router_main.pure:35. It then asserts EXACT STRING EQUALITY against `meta::pure::router::printer::asString()` of the routed tree: `' | {Platform> [strategy_wrapper /let firstFirmLegalName = {Platform> {Platform> [1 meta_relational_tests_model_simple_Firm/{meta::relational::tests::db> ...'`. That string is a dump of legend-engine's router metamodel (PlatformClusteredValueSpecification, the `strategy_wrapper` wrap name, ClassSetImplementationHolder set ids). legend-lite has no `routeFunction` in its catalog, so `Typer.functionCandidates` returns empty and the typer walls at Typer.java:1512. The wall is correct: even a perfect legend-lite router could not satisfy this assert, because the assert is over legend-engine's internal node classes and their debug print, not over any observable query result.

**Fix**

Do not fix. Ledger it. Concretely: leave `meta::pure::router::routeFunction` absent from `core/src/main/java/com/legend/builtin/Pure.java` and record this test under the existing router entry in `/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/NOT_IMPLEMENTABLE.md` (line 81 already names `routeFunction` as a call surface legend-lite does not expose) with the reason 'asserts legend-engine router metamodel print format, no observable result'. The current ERROR wall at Typer.java:1512 is the correct, loud outcome and its text already names the missing function. If a reviewer insists the sweep detail be more self-explanatory, the only defensible change is a catalog-level note — NOT a shim: register nothing, and do not special-case the name in the harness.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_main.pure:35 (the routeFunction overload) and .../core/pure/router/printer/printer.pure:38 `function meta::pure::router::printer::asString(v:ValueSpecification[1]):String[1]` (the printer whose output the test compares).

**Risk** — The tenet-2 trap here is real and specific: it would be easy to 'pass' this by teaching the harness to recognize `routeFunction` and hand back a canned tree, or by registering a native `routeFunction` that returns its input unchanged so the assert merely fails differently. Both are harness compensation over a surface the platform owns. A second trap: registering a no-op native would silently convert this ERROR into a wrong-answer FAIL and could shadow the honest 'unknown function' signal for any other test that reaches for the router.

**Also unblocks** — testPlatformExpressionDependencyOnAFromExpression2 and testRoutingOfSimpleQualifiedProperty in this same unit share this root cause exactly. docs/NOT_IMPLEMENTABLE.md:81 groups `routeFunction` with `toSQLQuery`/`sqlQueryToString` callers, suggesting further tests outside this unit sit in the same class, but I did not open those tests and do not claim them.

**Falsifier** — Show a legend-lite data structure that already carries per-node store/set-id routing decisions AND can print them in legend-engine's exact `{Platform> [strategy_wrapper /...]}` / `[1 meta_relational_tests_model_simple_Firm/...]` grammar. Concretely: `grep -rn "ClusteredValueSpecification\|strategy_wrapper\|StoreMappingRouted" core/src/main/java` returning a non-doc hit would falsify 'no analogue exists'. It returns nothing.

<details><summary>Evidence read (10 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/router/tests/testRouting.pure:220 — test declaration; body calls `routeFunction($f, ^ExecutionContext(), relationalExtensions(), noDebug())` and asserts on `$routingResult->map(f|$f->meta::pure::router::printer::asString())->joinStrings('')`. No `execute(...)`, no row or SQL assertion.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1512 — `throw new TypeInferenceException("no overload of '" + af.function() + "' matches " + raw.size() + " argument(s) of these shapes" + (candidates.isEmpty() ? " (no candidates at all)" : ...))` — the exact observed message; the `(no candidates at all)` suffix fires only when `functionCandidates(af)` is EMPTY, i.e. the name is absent from both catalogs.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1508 — `List<TypedFunction> candidates = functionCandidates(af);` inside `checkWithDeferred`, the call whose empty result produces the suffix.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1433-1435 — `af = expandFunctionValuedHelperArgs(af); if (af.parameters().stream().anyMatch(Typer::deferredArg)) { return checkWithDeferred(af, env); }` — explains why the error comes from the deferred path (1512) rather than checkGeneric's own 'unknown function' branch at 1446.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1652-1654 — `deferredArg` returns true for `p instanceof LambdaFunction`; combined with the harness let-splice this makes argument 0 (`$f`) a deferred arg.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2556 — `ValueSpecification spliced = subst(expr, lets);` in `eval(...)`: the harness substitutes the `let f = {|...}` binding into the statement, so the typer sees a literal lambda in arg 0.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_main.pure:35 — `function meta::pure::router::routeFunction(f:FunctionDefinition<Any>[1], exeCtx: meta::pure::runtime::ExecutionContext[1], extensions:Extension[*], debug:DebugContext[1]):FunctionDefinition<Any>[1]` — the exact 4-arg overload the test calls, implemented in Pure inside legend-engine.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/metamodel/clustering.pure:149 — `|'{Platform> '+$p.val->asString($pref)->replace('\n','\n'+space('{Platform>'->length()))+'}'` — the code that literally emits the `{Platform> ...}` text the test string-compares against.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/routing/router_routing.pure:77 — `$fxn.routingStrategy.wrapValueSpec($vs, 'strategy_wrapper', ...)` — the source of the `strategy_wrapper` token in the expected string.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:52 — the corpus root is `.../src/main/resources/core_relational/relational`; `router_main.pure` is outside it, so `routeFunction` is never loaded as corpus data and can only exist as a legend-lite platform surface.

</details>

---

## `testPlatformExpressionDependencyOnAFromExpression2`

| | |
|---|---|
| family | `router/tests` |
| sweep status | ERROR |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

Same mechanism as test 1, same call site, different query. The test builds a PARAMETERIZED lambda `{names:String[*]| let upperNames = $names->map(n|$n->toUpper()); Person.all()->filter(e|$e.firm.legalName->in($upperNames))->meta::pure::mapping::from(simpleRelationalMapping, testRuntime()); }`, calls the 4-arg `routeFunction($f, ^ExecutionContext(), relationalExtensions(), noDebug())`, and asserts exact string equality against the router printer's dump. The expected string is even more engine-internal than test 1's: it contains `[Routed Func:n:String[1] | ...]`, `[2 @firm(meta_relational_tests_model_simple_Person->meta_relational_tests_model_simple_Firm)@ ...]`, and `v_automap` — i.e. FunctionRoutedValueSpecification wrappers, set-implementation ids, association-property encoding, and the router's own auto-map binder name. legend-lite walls earlier, at overload resolution, because `routeFunction` is not in its catalog (Typer.java:1512, `(no candidates at all)`). Note this test does exercise a capability legend-lite plausibly HAS (a platform-side `map/toUpper` let feeding a store-side filter via `in`), but the test does not measure that capability — it measures the printed shape of the intermediate router tree. So this is not a hidden MISSING_FEATURE report; it is unobservable through any legend-lite-visible result.

**Fix**

Do not fix. Ledger alongside test 1, same entry in docs/NOT_IMPLEMENTABLE.md, same reason. No code change in core/src/main/java/com/legend/. Specifically: do NOT register a `routeFunction` signature in Pure.java, and do NOT add a branch in EngineTestExecutor that recognises `routeFunction` statements. If someone later wants routing observability for genuine debugging, the correct home is a legend-lite-native plan/route dump on the resolver output (a new printer under core/src/main/java/com/legend/resolver/), explicitly NOT format-compatible with meta::pure::router::printer — and it still would not make this test pass, which is the point.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/router_main.pure:35 — the called overload; the routed tree it returns is consumed by printer.pure:94-95.

**Risk** — Identical tenet-2 trap to test 1. Additional risk specific to this test: because it is a 1-line-different sibling of test 1, a fixer may be tempted to hardcode both expected strings behind a 'router printer' emulator seeded from the corpus — that is writing the oracle from the answer key and would silently pass regardless of whether legend-lite routes correctly.

**Also unblocks** — testPlatformExpressionDependencyOnAFromExpression and testRoutingOfSimpleQualifiedProperty.

**Falsifier** — Rewrite the test's intent as an observable: execute the same lambda with a `names` argument and compare rows/SQL. If legend-lite produced correct rows there, that would confirm the underlying routing capability exists and that only the internals assert is unreachable — it would NOT rescue this test. The claim that would actually be falsified is 'the assert is unobservable': show any legend-lite output whose equality with the expected string is derivable without reimplementing printer.pure.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/router/tests/testRouting.pure:234 — test declaration; the sole assertions are `assertEquals(1, $routingResult->size())` and an exact-string assertEquals over `meta::pure::router::printer::asString()`. No execute, no SQL, no rows.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1512 — the throw that produced the observed `no overload of 'routeFunction' matches 4 argument(s) of these shapes (no candidates at all)`; the suffix proves `candidates.isEmpty()`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1505-1508 — `checkWithDeferred` begins `List<ValueSpecification> raw = af.parameters(); List<TypedFunction> candidates = functionCandidates(af);` — `raw.size()` is the 4 in the message.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/printer/printer.pure:94 — `f:FunctionRoutedValueSpecification[1]|'[Routed Func:'+$f.value->asString($pref)+']'` — emits the `[Routed Func:...]` token the expected string requires.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/printer/printer.pure:95 — `cs:ClassSetImplementationHolder[1]|'['+$cs.set.id+' '+$cs.value->asString($pref)+']'` — emits the `[1 meta_relational_tests_model_simple_Person/...]` set-id brackets.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1283-1288 — `meta::pure::mapping::from` IS registered as a native (3 overloads), confirming the wall is specifically on `routeFunction` and not on the query the test builds.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/ — the whole H-phase is 26 Java files (StoreResolver, Substitution, NavMaterializer, Pipelines, …); none names or produces a routed-ValueSpecification metamodel that could be printed in the engine's grammar.

</details>

---

## `testRoutingOfSimpleQualifiedProperty`

| | |
|---|---|
| family | `router/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XS |
| confidence | high |

**Root cause**

Two-layer symptom, one cause. The test calls the DEPRECATED 6-arg overload `$f->routeFunction(simpleRelationalMapping, testRuntime(), ^ExecutionContext(), relationalExtensions(), noDebug())` and then, instead of comparing a printed string, downcasts the result through legend-engine router classes: `->cast(@meta::pure::router::metamodel::clustering::ClusteredValueSpecification).val->toOne()->byPassValueSpecificationWrapper()->cast(@SimpleFunctionExpression)`, then `->byPassRouterInfo()->cast(@InstanceValue)`, and finally asserts `$lhsRouted->instanceOf(StoreMappingRoutedValueSpecification)` and `.sets.class.name == 'Firm'`. Every one of those is a legend-engine metamodel class with no legend-lite counterpart. Layer 1 of the symptom: because the body contains no `execute(...)` and no `from(...)` mapping reference, `Runner.run0` finds `mappingRefs.isEmpty()` and takes the TRY-RUN-THEN-SHAPE path (Runner.java:1288, 1307), which attempts the body via `tryRunNoExecute` and, on failure, returns `Status.SHAPE, "no execute(|...) call [calls ...] — wall: <wall>"` (Runner.java:1312-1315). Layer 2: the wall carried in that message is the same `functionCandidates` empty-set throw at Typer.java:1512, here with `raw.size() == 6`. So the SHAPE label is the harness correctly reporting 'nothing to execute', and the wall behind it is the honest 'routeFunction does not exist here'.

**Fix**

Do not fix. Ledger with tests 1 and 2. No code change. Explicitly reject three tempting non-fixes: (a) registering `meta::pure::router::routeFunction` in core/src/main/java/com/legend/builtin/Pure.java so the typer stops walling — it would only move the failure to the first `cast(@ClusteredValueSpecification)`; (b) adding ClusteredValueSpecification / StoreMappingRoutedValueSpecification / byPassRouterInfo / byPassValueSpecificationWrapper as legend-lite metamodel classes purely to satisfy casts — that is importing another implementation's internal node taxonomy as public API; (c) teaching Runner or EngineTestExecutor to recognise this test's shape. The correct record is docs/NOT_IMPLEMENTABLE.md:81's existing `routeFunction` entry, extended to name this test and the reason 'downcasts to legend-engine router metamodel classes'.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/deprecated/deprecated.pure:24 (the 6-arg overload) and /Users/neemsandv/legend/legend-engine/.../core/pure/router/store/metamodel.pure:31 (StoreMappingRoutedValueSpecification, the asserted class).

**Risk** — Beyond the shared tenet-2 trap: this one is labelled SHAPE rather than ERROR, which can read as 'a small vocabulary gap' and invite someone to 'just add the missing classes'. Adding router metamodel classes to legend-lite would give the corpus a false surface that other engine-internal tests would then partially bind to, converting honest walls into wrong-shape passes — the worst outcome under TENETS. Also note the SHAPE classification is itself correct behaviour and must not be 'fixed' by making the try-run path swallow the wall.

**Also unblocks** — testPlatformExpressionDependencyOnAFromExpression and testPlatformExpressionDependencyOnAFromExpression2.

**Falsifier** — If legend-lite did possess a routed-value-specification metamodel that these casts could bind to, `grep -rn "StoreMappingRoutedValueSpecification\|ClusteredValueSpecification\|byPassRouterInfo" --include="*.java"` over the worktree would hit main source. It returns zero hits outside docs. A second cheap falsifier for the harness layer: if `executeMappingRefs` were supposed to pick up `simpleRelationalMapping` from a `routeFunction` argument, this test would take the ERROR path like its two siblings — but that would change only the label, not the outcome.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/router/tests/testRouting.pure:187 — test declaration; body is `let f = {|Firm.all()->project([f | $f.nameAndAddress()],'nameAndAddress')}; let routed = $f->routeFunction(simpleRelationalMapping, testRuntime(), ^ExecutionContext(), relationalExtensions(), noDebug());` followed by casts to ClusteredValueSpecification / StoreMappingRoutedValueSpecification and three `assert(...)` calls on router node identity. Six arguments — matching the message's '6 argument(s)'.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/deprecated/deprecated.pure:24 — `function <<doc.deprecated>> meta::pure::router::routeFunction(f:FunctionDefinition<Any>[1], mapping:Mapping[1], runtime:Runtime[1], exeCtx: meta::pure::runtime::ExecutionContext[1], extensions:meta::pure::extension::Extension[*], debug:DebugContext[1]):FunctionDefinition<Any>[1]` whose body is `routeFunction($f, getRoutingStrategyFromMappingAndRuntime($mapping, $runtime, $extensions), $exeCtx, [], $extensions, $debug)` — the exact 6-arg overload, Pure-implemented in legend-engine.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/store/metamodel.pure:31 — `Class meta::pure::router::store::metamodel::StoreMappingRoutedValueSpecification extends StoreRoutedValueSpecification` — the class the test asserts `instanceOf` against.
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/router/metamodel/clustering.pure:16 — `Class meta::pure::router::metamodel::clustering::ClusteredValueSpecification extends ValueSpecification` — the class the test casts to and reads `.val` from.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1312-1315 — `return new Outcome(t.fqn(), Status.SHAPE, "no execute(|...)" + " call" + (ns == null ? "" : " [calls " + ns + "]") + (attempted.wall() == null ? "" : " — wall: " + attempted.wall()));` — the exact composition of the observed SHAPE detail string.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1288 — `List<String> mappingRefs = executeMappingRefs(body, t); if (mappingRefs.isEmpty()) {` — the branch taken because this body has no execute/from mapping reference (unlike tests 1 and 2, which carry `meta::pure::mapping::from(simpleRelationalMapping, ...)` and therefore land on the ERROR path instead).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1097-1160 — `tryRunNoExecute` runs the body through EngineTestExecutor and returns `new TryRun(null, exceptionText(e))` on throw, which is the `wall:` text.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1512 — the throw supplying that wall text.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:470 — the only exclusion vocabulary is legend-engine's OWN stereotypes: `case "ToFix", "Ignore", "ExcludeAlloy" -> excluded = true;`. This test carries plain `<<test.Test>>`, so it will always be run and always land here — there is no legend-lite-invented suppression list, and inventing one would itself be harness compensation.

</details>

---

## `testProcessIdentifierWithQuoteChar`

| | |
|---|---|
| family | `sqlQueryToString` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The test's first statement is `let dbConfig = ^DbConfig(..., dbExtension=meta::relational::functions::sqlQueryToString::h2::v2_1_214::createDbExtensionForH2())` (extensionDefaults.pure:566). Typing that constructor forces compilation of corpus class `meta::relational::functions::sqlQueryToString::DbExtension` (dbExtension.pure:265). Its property at dbExtension.pure:272 is `coreTypeToDbSpecificSqlTranslator: meta::pure::metamodel::function::Function<{CoreDataType[1] -> String[0..1]}>[0..1]`. `CoreDataType` is written BARE and the file does `import meta::relational::metamodel::datatype::*` (dbExtension.pure:7), so NameResolver would qualify it — except `meta::relational::metamodel::datatype::CoreDataType` is in neither the corpus nor legend-lite's native catalog (Pure.java registers only the empty `datatype::DataType`, Pure.java:351). resolveNameMulti therefore finds no candidate in any tier and returns the name unchanged (NameResolver.java:592), and TypeClassifier.classify's NameRef arm throws `Unknown type: 'CoreDataType' is not a known primitive, class, or enum` (TypeClassifier.java:91-92) — the wall verbatim. That is only the PROXIMATE cause. The test's actual subject is legend-engine's Pure identifier processor `processIdentifierWithQuoteChar` (extensionDefaults.pure:556-562), reached through the fully-evaluated `^DbExtension(...)` record that `createDbExtensionForH2` builds from 20 function-pointer fields plus `getDynaFunctionToSqlDefault($literalProcessor)->groupBy(...)->putAll(...)->getDynaFunctionDispatcher()` (h2Extension2_1_214.pure:18-44). legend-lite has no interpreter for that: `exec/HostEval.java` is explicitly scoped to 'ORCHESTRATION-VALUE evaluation … expressions over meta::relational::metamodel::execute values' (HostEval.java:36-44), and legend-lite's own identifier quoting is a plain boolean flag with no reserved-word / space / FreeMarker handling (`EngineStyleH2.phys`, EngineStyleH2.java:211-213).

**Fix**

Do NOT try to make this test pass — ledger it. It white-boxes the engine's Pure SQL generator, which legend-lite replaces with Java by design; passing it means interpreting `createDbExtensionForH2` end to end (20 function-pointer fields, a Map-backed dyna-function dispatcher, ~1300 lines of dbExtension.pure plus 860 of extensionDefaults.pure) — an interpreter legend-lite deliberately does not have (HostEval.java:36-44). Do make the wall HONEST, because 'Unknown type: CoreDataType' reads as a two-line manifest gap and is not: (1) in `core/src/main/java/com/legend/builtin/Pure.java`, next to DATA_TYPE_METACLASS (:351), register the legend-pure datatype hierarchy verbatim — `native Class meta::relational::metamodel::datatype::CoreDataType extends meta::relational::metamodel::datatype::DataType {}` and `…::DbSpecificDataType extends …DataType { coreDataType: …CoreDataType[1]; dbSpecificSql: meta::pure::metamodel::type::String[1]; }`, plus the concrete subclasses at relational.pure:392-499; (2) MANDATORY companion — in `NameResolver.resolveNameMulti` (NameResolver.java:532-593) add a FIRST tier mirroring legend-pure ImportStub.java:183-186: if `name` is one of the 12 `Type.Primitive` simple names, return the primitive FQN immediately, ahead of the wildcard tier (:551-558) and the own-package tier (:565-570). Without (2), registering `datatype::Integer`/`Float`/`Boolean`/`Date`/`Decimal` re-points every bare `Integer[0..1]`/`Boolean[1]` in dbExtension.pure at the wrong class. After (1)+(2) the wall will move to the next unmodelled surface (the DbExtension record's evaluation) — which is the honest message.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:226 defines CoreDataType; the identifier semantics under test live in legend-engine at legend-engine-xts-relationalStore/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:556-562.

**Risk** — Tenet-2 trap: do NOT special-case `processIdentifierWithBackTicks`/`WithDoubleQuotes` as harness-intercepted natives to make the asserts green — identifier quoting is platform-owned (sql/dialect), and a name-intercepting arm is exactly the harness compensation PlatformTypes.java:151-155 records as already having been removed once. Registering colliding datatype names WITHOUT the NameResolver primitive tier silently mistypes bare `Integer`/`Boolean` properties across the corpus.

**Also unblocks** — Every other sqlQueryToString/* test whose body constructs ^DbConfig or calls createDbExtensionForH2*; the NameResolver primitive tier independently fixes latent mis-resolution in protocols/pure/v1_*/models/metamodel_relational.pure.

**Falsifier** — Register `datatype::CoreDataType` (alone, no colliding names) in Pure.java and re-run only this test with LL_TMP_DEBUG=1. My claim predicts the wall MOVES — to a further unresolved class in dbExtension.pure (UpsertSQLQuery/CommitQuery via metamodel.pure's `extends SQLQuery`) or to an unevaluatable `createDbExtensionForH2`. If the test instead PASSES or reaches an assertEquals, my 'needs the whole Pure generator' claim is wrong and this is a plain manifest gap.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:566 — `^DbConfig(dbType=DatabaseType.H2, …, dbExtension=…::v2_1_214::createDbExtensionForH2())`
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/sqlQueryToString/extensionDefaults.pure:556-562 — `processIdentifierWithQuoteChar` quotes when quoteIdentifiers OR startsWith('"') OR isDbReservedIdentifier OR (contains(' ') && !isFreeMarkerIdentifier)
- /Users/neemsandv/legend/legend-engine/…/sqlQueryToString/dbExtension.pure:272 — `coreTypeToDbSpecificSqlTranslator: …Function<{CoreDataType[1] -> String[0..1]}>[0..1];` (bare CoreDataType)
- /Users/neemsandv/legend/legend-engine/…/sqlQueryToString/dbExtension.pure:7 — `import meta::relational::metamodel::datatype::*;`
- /Users/neemsandv/legend/legend-engine/…/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:18-44 — createDbExtensionForH2 builds the whole ^DbExtension record incl. dynaFuncDispatch
- /Users/neemsandv/legend/legend-pure/…/platform_store_relational/grammar/relational.pure:226 — `Class meta::relational::metamodel::datatype::CoreDataType extends …datatype::DataType`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:351 — DATA_TYPE_METACLASS is `native Class …datatype::DataType extends …Any {}` and nothing under it is registered
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — `findType(nr.name()).orElseThrow(… "Unknown type: '" + nr.name() + "' is not a known primitive, class, or enum")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/NameResolver.java:571-592 — prelude tier is `PRELUDE_TYPES.get(name)` over Pure.nativeClassFqns(); no match falls through to `List.of(name)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1161-1172 — the catch arm returns `new TryRun(null, exceptionText(e))`, which Runner.java:1311-1313 prints as `— wall: …`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/HostEval.java:36-44 — 'The ORCHESTRATION-VALUE evaluation channel … Small recursive evaluator; every unhandled shape is LOUD'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:211-213 — `phys(name) { return quoteIdentifiers ? '"'+name+'"' : name; }` — no reserved-word/space/FreeMarker rule

</details>

---

## `testTempTableSqlStatementsForH2`

| | |
|---|---|
| family | `sqlQueryToString/testSuite` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The body calls `getTempTableSqlStatements(DatabaseType.H2)` (testTempTableSqlStatements.pure:117), which constructs `^CreateTableSQL(table=…, isTempTable=true)`, `^LoadTableSQL(...)` and `^DropTableSQL(...)` (:96-101). Those bare element refs qualify through the file's `import meta::relational::metamodel::*` (:18) to corpus classes in `metamodel/metamodel.pure`, so the harness demand-pulls that file (Runner.java:1110-1131). Compiling it hits `Class meta::relational::metamodel::CreateTableSQL extends SQLQuery` (metamodel.pure:116) — bare `SQLQuery`, resolved against the file's own wildcards `metamodel::relation::*` / `metamodel::*` (metamodel.pure:15-16). `meta::relational::metamodel::SQLQuery` is defined ONLY in legend-pure (relational.pure:200) and is absent from legend-lite's catalog, where `SELECT_SQL_QUERY` hangs directly off `RelationalOperationElement` and skips the SQLQuery layer (Pure.java:426). No tier matches, the name survives bare, TypeClassifier.classify throws `Unknown type: 'SQLQuery' …` (TypeClassifier.java:91-92), and unknownTypePull finds no corpus element named SQLQuery so it cannot retry (Runner.java:1195-1206). Behind that wall sit two further genuine gaps: (a) `createDbConfig` is declared PLATFORM-OWNED (PlatformTypes.java:215-216,228), so FunctionCompiler suppresses the corpus's real body (FunctionCompiler.java:64-72), yet grepping all of core/src/main/java for `DbConfig` outside PlatformTypes/Pure/InferenceKernel returns nothing — there is no execution arm, so `$dbType->createDbConfig([])` produces no value at all; (b) `meta::relational::functions::sqlQueryToString::ddlSqlQueryToString` is NOT in isDdlStatementFn (PlatformTypes.java:144-149) and legend-lite's Java DDL renderer has no `CREATE LOCAL TEMPORARY TABLE` or `CSVREAD` form (Ddl.java:292-303 covers only drop/create parity).

**Fix**

Three ordered changes, all in the platform (none in the harness). (1) `core/src/main/java/com/legend/builtin/Pure.java`: insert `public static final ClassDefinition SQL_QUERY = nativeClass("native Class meta::relational::metamodel::SQLQuery extends meta::relational::metamodel::RelationalOperationElement { comment: meta::pure::metamodel::type::String[0..1]; }");` (verbatim from legend-pure relational.pure:200-203) and re-parent SELECT_SQL_QUERY (Pure.java:426) to extend it, so the real chain SelectSQLQuery -> SQLQuery -> RelationalOperationElement holds; also register `datatype::CoreDataType` + the concrete `datatype::*` subclasses so `^Column(type=^…datatype::Integer())` (testTempTableSqlStatements.pure:26-30) types. (2) MANDATORY companion, same change as in testProcessIdentifierWithQuoteChar: add the primitive-first tier at the head of `NameResolver.resolveNameMulti` (NameResolver.java:532-593) mirroring legend-pure ImportStub.java:183-186, before registering any datatype class whose simple name collides with a primitive. (3) Own the DDL text surface the way toDDL is already owned: add `meta::relational::functions::sqlQueryToString::ddlSqlQueryToString` to `PlatformTypes.isDdlStatementFn`/`isPlatformOwnedFunction` (PlatformTypes.java:144-149, 218-230), declare its native signature in Pure.java, and add a K-arm that reads the constructed ^CreateTableSQL/^LoadTableSQL/^DropTableSQL instance (the same instance->host bridge shape as `StatementExecutor.constructOp`, StatementExecutor.java:1427-1440) and renders through `exec/Ddl.java`, which needs two new forms: `CREATE LOCAL TEMPORARY TABLE <t>(<col TYPE,…>);` and `INSERT INTO <t> SELECT * FROM CSVREAD('${var}');`. `createDbConfig` must additionally return a real handle carrying dbType/quoteIdentifiers/timeZone, since it currently has no execution arm at all.

**How legend-engine does it** — legend-pure legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:200 (SQLQuery); the H2 temp-table text under test is legend-engine …/sqlQueryToString/dbSpecific/h2/h2Extension2_1_214.pure:62-74 (translateCreateTableStatementForH2 emits `CREATE LOCAL TEMPORARY TABLE `).

**Risk** — Re-parenting SELECT_SQL_QUERY inserts a new node in a subsumption chain the lowering and post-processor boundary already walk — anything matching on exact superclass FQN of SelectSQLQuery could shift. Tenet-2 trap: do NOT make the harness recognize `getTempTableSqlStatements` by name and hand back the expected strings; the DDL text surface is platform-owned (Ddl.java) and must produce them. Registering datatype::Integer/Float/Date without the NameResolver primitive tier mistypes bare primitive properties corpus-wide.

**Also unblocks** — Any test constructing ^CreateTableSQL/^DropTableSQL/^LoadTableSQL/^UpsertSQLQuery/^CommitQuery, i.e. the rest of sqlQueryToString/testSuite and DDL/testDDL.pure — all of them currently die on the same `Unknown type: 'SQLQuery'`.

**Falsifier** — Register SQLQuery in Pure.java (alone) and re-run this test with LL_TMP_DEBUG=1. Prediction: the wall MOVES to the `createDbConfig` call at testTempTableSqlStatements.pure:93 producing no value, or to another unregistered datatype class. If it instead reaches an assertEquals on real SQL text, then ddlSqlQueryToString is already reachable somewhere I did not find and my step (3) is unnecessary.

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/sqlQueryToString/testSuite/testTempTableSqlStatements.pure:115-124 — asserts the three exact H2 temp-table statements
- /Users/neemsandv/legend/legend-engine/…/sqlQueryToString/testSuite/testTempTableSqlStatements.pure:91-101 — getTempTableSqlStatements builds ^CreateTableSQL/^LoadTableSQL/^DropTableSQL and calls ddlSqlQueryToString
- /Users/neemsandv/legend/legend-engine/…/metamodel/metamodel.pure:116 — `Class meta::relational::metamodel::CreateTableSQL extends SQLQuery`; :15-16 the wildcards it resolves under
- /Users/neemsandv/legend/legend-pure/…/platform_store_relational/grammar/relational.pure:200 — `Class meta::relational::metamodel::SQLQuery extends meta::relational::metamodel::RelationalOperationElement`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:426 — SELECT_SQL_QUERY extends RelationalOperationElement directly; no SQLQuery class is registered anywhere in the catalog
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 — the throw that produced the wall
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1183-1206 — unknownTypePull only retries when the unknown simple name has exactly one CORPUS definition
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:215-216 and :228 — createDbConfig is platform-owned
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:64-72 — platform-owned FQNs drop the corpus definitions from the overload set
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:144-149 — isDdlStatementFn covers only toDDL::{drop,create}{Schema,Table}Statement; ddlSqlQueryToString is not owned
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/Ddl.java:292-303 — dropTableStatementText + engineSpell; no temp-table or CSVREAD form exists in the file

</details>

---

## `testTranslateDbType`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | M |
| confidence | medium |

**Root cause**

The body constructs `^meta::relational::metamodel::datatype::Varchar(size = 100)` (testRelationalExtension.pure:148). The class name is fully qualified, so name resolution is not involved: `NewChecker.check` calls `t.model().findClass(ni.className())`, which routes through PureModelContext.findClass -> TypeClassifier.classDef (PureModelContext.java:114-125, TypeClassifier.java:69-72) — native catalog first, then the corpus model. `meta::relational::metamodel::datatype::Varchar` is in neither (legend-pure defines it at relational.pure:432; Pure.java stops at the empty `datatype::DataType`, :351), so NewChecker throws `unknown class 'meta::relational::metamodel::datatype::Varchar' in ^meta::relational::metamodel::datatype::Varchar(…)` (NewChecker.java:68-69) — the wall verbatim. unknownTypePull sees a name containing '::' and finds no corpus elementSource entry, so no retry (Runner.java:1192-1193). Behind that wall the test needs two further things legend-lite does not have: (a) host evaluation of the corpus function `translateCoreTypeToDbSpecificType` (relationalTypeTranslations.pure:26-38) — a lambda stored in a `Function<{CoreDataType[1] -> String[0..1]}>[0..1]` property, `->eval`'d, plus `^DbSpecificDataType(...)` construction, none of which is in HostEval's scoped channel (HostEval.java:36-44); (b) `dataTypeToSqlText`, which legend-lite implements as a native over its OWN Java datatype (`MetamodelWalk.sqlText` returns null unless the receiver is a `Dt` record, MetamodelWalk.java:1546-1551, and the only producer of `Dt` is `MetamodelWalk.infer`, :1219-1227). A Pure-constructed `^DbSpecificDataType` instance would therefore yield null, not 'STRING(100)'.

**Fix**

Narrow, tractable, and worth doing — but land it in this order. (1) `core/src/main/java/com/legend/builtin/Pure.java`, beside DATA_TYPE_METACLASS (:351): register the datatype hierarchy verbatim from legend-pure relational.pure:222-499 — CoreDataType, DbSpecificDataType{coreDataType, dbSpecificSql}, and the concrete leaves the corpus constructs (Varchar{size}, Char{size}, Varbinary{size}, Decimal{precision,scale}, Numeric{precision,scale}, Integer, Float, Double, Real, BigInt, SmallInt, TinyInt, Boolean, Bit, Binary, Timestamp, Date, Distinct, Other, Array, Object, SemiStructured). (2) PREREQUISITE for (1): add a primitive-first tier at the head of `NameResolver.resolveNameMulti` (NameResolver.java:532-593), before the wildcard tier (:551-558) and the own-package tier (:565-570) — if `name` equals one of the 12 `Type.Primitive` simple names, return the primitive FQN. This is exactly legend-pure's `_Package.SPECIAL_TYPES` check (ImportStub.java:183-186, set defined at _Package.java:39 / ModelRepository.java:79). (3) Make `dataTypeToSqlText` total over Pure-constructed instances: `MetamodelWalk.sqlText` (:1546) currently bails on anything that is not a `Dt`; add an arm that accepts a TypedNewInstance/instance value whose class FQN is under `meta::relational::metamodel::datatype::` and spells it per legend-pure functions.pure:68-96 — in particular `DbSpecificDataType -> $d.dbSpecificSql`. (4) Only then does the remaining gap show: host-evaluating `translateCoreTypeToDbSpecificType` (lambda-valued property + ->eval + ^DbSpecificDataType) inside HostEval. If (4) is judged out of scope, stop after (1)-(3): the wall then names the real missing surface instead of a class name.

**How legend-engine does it** — legend-pure legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:432 (Varchar) and .../platform_store_relational/functions.pure:68-96 (dataTypeToSqlText, whose DbSpecificDataType arm returns $d.dbSpecificSql); the function under test is legend-engine .../core_relational/relational/relationalTypeTranslations.pure:26.

**Risk** — Registering `datatype::Integer|Float|Boolean|Date|Decimal` WITHOUT step (2) is an active regression: NameResolver's wildcard tier (NameResolver.java:551-558) sits above the prelude, so every bare `Integer[0..1]`/`Boolean[1]` in dbExtension.pure (which imports datatype::* at :7) would re-point at the datatype class. A related latent instance already exists and step (2) fixes it independently: `Class meta::protocols::pure::v1_33_0::metamodel::store::relational::Varchar { size: Integer[1]; }` (protocols/pure/v1_33_0/models/metamodel_relational.pure:223-226) sits in a package that also declares `…::store::relational::Integer` (:207), so legend-lite's own-package tier resolves that `Integer` to the protocol DataType class rather than the primitive. Tenet-2 trap: do NOT teach the harness to recognize this test and synthesize 'STRING(100)'.

**Also unblocks** — The other datatype-constructing tests (testTempTableSqlStatementsForH2's ^Column(type=^…datatype::Integer()) and the rest of sqlQueryToString/testSuite); step (2) additionally de-risks every future native-class registration whose simple name collides with a primitive.

**Falsifier** — Register `datatype::CoreDataType` + `datatype::Varchar` + `datatype::DbSpecificDataType` in Pure.java and re-run this test with LL_TMP_DEBUG=1. Prediction: the wall MOVES off `^Varchar` and onto either eval-of-a-function-valued-property inside translateCoreTypeToDbSpecificType or a null/assert failure from `dataTypeToSqlText`. If it instead still says `unknown class …Varchar`, then NewChecker is not reading the native catalog on this path and my PureModelContext.findClass -> TypeClassifier.classDef trace is wrong.

<details><summary>Evidence read (12 citations)</summary>

- /Users/neemsandv/legend/legend-engine/…/tests/testRelationalExtension.pure:143-151 — testTranslateDbType: inline translator lambda, `translateCoreTypeToDbSpecificType(^…datatype::Varchar(size = 100), ^TranslationContext(...))`, asserts 'STRING(100)' via dataTypeToSqlText
- /Users/neemsandv/legend/legend-engine/…/relationalTypeTranslations.pure:20-38 — TranslationContext (its `coreTypeToDbSpecificSqlTranslator` also names CoreDataType) and translateCoreTypeToDbSpecificType's ->eval / ^DbSpecificDataType body
- /Users/neemsandv/legend/legend-pure/…/platform_store_relational/grammar/relational.pure:432-436 — `Class meta::relational::metamodel::datatype::Varchar extends …CoreDataType { size: Integer[1]; }`
- /Users/neemsandv/legend/legend-pure/…/platform_store_relational/functions.pure:68-96 — dataTypeToSqlText's match, last arm `d : …DbSpecificDataType[1] | $d.dbSpecificSql`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/NewChecker.java:68-69 — `if (t.model().findClass(ni.className()).isEmpty()) throw … "unknown class '" + ni.className() + "' in ^" + ni.className() + "(…)"`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/PureModelContext.java:114-125 — findClass delegates to classifier.classDef
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/TypeClassifier.java:69-72 — classDef = native catalog first, then model.findClass
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:351 — only the empty datatype::DataType is registered
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1496 — `native function meta::relational::metamodel::datatype::dataTypeToSqlText(type:…DataType[1]):String[1];`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1413-1415 — `case "dataTypeToSqlText" -> MetamodelWalk.sqlText(recv);`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1546-1551 — `if (!(r instanceof Dt d)) return null;`; :1219-1227 — infer() is the only `new Dt(...)` site in the codebase
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1192-1193 — a '::'-bearing unknown name only retries if elementSource has it (the corpus does not define this class)

</details>

---
