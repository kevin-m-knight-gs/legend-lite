# Metamodel-as-relations — the deep homework (2026-09-02)

Every number here was counted, every class/property/function was read from
the real checkouts (`/Users/neemsandv/legend/legend-pure`,
`/Users/neemsandv/legend/legend-engine`), and every platform claim points at
a file. Where a fact could NOT be established without a prototype, it says
so. Nothing is sampled. The scripts and JSON that produced the tables live
in the session's job tmp (`mm/`); re-running them is the way to refresh.

Standing ruling this design sits under: [[metamodel-in-database-ruling]]
(commit 47206a73) — no Java-computed metamodel/lineage/plan fact enters the
verdict path; the end-state is metamodel AS RELATIONS in the database.

## 0. Method, and the one known gap in the census

- Test inventory = every fallback at HEAD (848 = 2573 runs − 1725 flipped)
  whose FIRST wall message names a metamodel class, function, or the HN
  vocabulary, read from an `LL_TMP_DEBUG=1` sweep of the HEAD-1 tree
  (probe15) — **261 tests**, grouped into 17 wall shapes (§1).
- **Known gap (not guessed around):** the census file counts the two
  "HN vocabulary" buckets at 65 (TypedMap) + 45 (mapping::sql) TESTS, but
  those walls do not print a per-test debug line; only 22 of them surface
  through `[v7] decline-witness` lines. So ~88 tests of the ~355-row
  metamodel family are attributed by bucket only. The fix is mechanical:
  make `WholeTestFlip`'s census dump `bucket → test names` (it already
  holds `BUCKETS`/`WITNESSES`, `WholeTestFlip.java:60-110`). Do that
  before chartering the plan-walk leg; nothing below depends on it.
- Object inventory = import-aware resolution of every `cast(@X)`,
  `instanceOf(X)`, `^X(…)`, type annotation and match arm in the 261 test
  bodies PLUS their transitive corpus helpers (316 bodies), against a
  class universe of 11,181 `Class` declarations parsed from both
  checkouts (`mm/metamodel_classes.json`). 71 metamodel classes resolve
  by reference; 7 more are named by the wall messages themselves.
- Engine-function inventory = every `meta::…` call from those bodies
  into engine library code (72 functions), with the TRANSITIVE pure
  closure sized by import-aware resolution over all overloads
  (`mm/closure.py`).

## 1. The tests — 261 named, by wall shape

| shape | tests | where (files) | what the wall says |
|---|---:|---|---|
| A expressionSequence / metaprogramming | 70 | pkInferenceTests.pure 43, scanRelationsTests.pure 24, scanRelationsTestWithViewsAndUnions 2, domainManagementTests 1 | `class FunctionDefinition has no property 'expressionSequence'` |
| D harness vocabulary | 43 | testSubTypeGraphFetch 15, testCrossStoreGraphFetchMilestoning 6, testSliceTakeLimitDrop 4, testPlatformOperationsOnRelational 4, … | `unknown function meta::legend::executeLegendQuery / compileLegendValueSpecification / compileLegendGrammar` |
| F mapping-metamodel query functions | 27 | testRelationalExtension.pure 20, testExtendsForMainTable 5, testExtendsForPrimaryKey 1, testSubtypeMapping 1 | `no scalar lowering: rootClassMappingByClass/2, classMappingById/2, view/2, inferRelationalType/1, _classMappingByClass/2` |
| E scanRelations | 21 | scanRelationsTests.pure | `in call to scanRelations …` |
| Z other metamodel-typed | 18 | testPureToSql 4, testBusinessDateMilestoning 4, testIn 2, testLoadCsv 1, … | unknown types / Pair typing / SelectSQLQuery reads |
| J misc unported | 17 | testObjectReferenceIn 7, testRouting 4, hybrid milestoning 2, testEnumerationMapping 2 | `unknown function generateObjectReferences / routeFunction / repeat / toDomainValue / resolveStore / enumValues` |
| Q Any-typed plan reads | 13 | executionPlanTest 9, testLegacyNullUnsafeEquals 2, m2m2rExecutionPlanTests 1 | `expected Integer/Date/String, got Any` (reads off plan nodes) |
| G toPostgresModel newState | 10 | sqlDialectTranslation tests.pure | `no scalar lowering: newState/0` |
| N unknown metamodel type | 9 | testDdlGeneration 2, testPostProcessor 2, relationalToPure 1, executionPlanTest 1 | `Unknown type 'SQLQuery' / 'CoreDataType' / PureModelContextData …` |
| B plan-walk TypedMap (named) | 6 | testConcatenate 4, testFilters 1, testValidationWithMilestoning 1 | `class query under TypedMap is not resolvable yet (HN vocabulary)` |
| I LambdaFunction reads | 6 | scanColumnsTests.pure | `class LambdaFunction has no property …` |
| C mapping::sql (named) | 5 | testFrom 4, testSort 1 | `class query under TypedUserCall[meta::relational::mapping::sql]` |
| P routerExtensions | 5 | testRelationalExtension (connection equality) | `in call to routerExtensions, argument 1: multiplicity` |
| H InstanceValue trees | 4 | testPureToSql.pure | `unknown type 'InstanceValue' in @InstanceValue` |
| O TDG Pair typing | 4 | testDataGeneration.pure | `expected Pair<String, List<…>>` |
| M graphFetch HN | 2 | m2m2rExecutionPlanTests | `class query under TypedGraphFetch` |
| K assert in scalar position | 1 | executionPlanTest | assertEquals/2 reached the lowerer |

(+ ~88 HN-vocabulary tests attributed by bucket only — §0.)

## 2. The objects — what the tests actually touch

### 2a. The m3 core (expression trees) — `legend-pure/…/platform/pure/grammar/m3.pure`
Read from the bootstrap grammar (class blocks at m3.pure:2154 FunctionDefinition, :1780 InstanceValue, …):

| class | extends | properties (name:type[mult]) |
|---|---|---|
| `type::Any` | — | classifierGenericType:GenericType[0..1] |
| `PackageableElement` | ModelElement, Referenceable | package:Package[0..1] (+ name from ModelElement) |
| `type::Type` | Any | name:String[0..1], generalizations:Generalization[*], specializations:Generalization[*] |
| `type::Class` | Type, PropertyOwner, ElementWithConstraints, PackageableElement, Testable | properties:Property[*], originalMilestonedProperties:Property[*], propertiesFromAssociations:Property[*], qualifiedProperties:QualifiedProperty[*], qualifiedPropertiesFromAssociations:QualifiedProperty[*], typeParameters, typeVariables, multiplicityParameters |
| `property::AbstractProperty` | Function, ModelElement | genericType:GenericType[1], owner:PropertyOwner[1] |
| `property::Property` | AbstractProperty | aggregation:AggregationKind[1] |
| `property::QualifiedProperty` | AbstractProperty, FunctionDefinition | id:String[1] |
| `generics::GenericType` | Referenceable | rawType:Type[0..1], typeVariableValues, multiplicityArguments:Multiplicity[*] (+typeArguments) |
| `multiplicity::Multiplicity` | Any | lowerBound, upperBound, multiplicityParameter |
| `relationship::Generalization` | Any | general:GenericType, specific:Type |
| `function::Function` | Referenceable | name:String[0..1], applications:FunctionExpression[*] |
| `function::FunctionDefinition` | Function | **expressionSequence:ValueSpecification[1..*]** |
| `function::ConcreteFunctionDefinition` | FunctionDefinition, PackageableFunction, Testable | — |
| `function::LambdaFunction` | FunctionDefinition | openVariables:String[*] |
| `valuespecification::ValueSpecification` | Any | genericType:GenericType[1], multiplicity, usageContext |
| `valuespecification::InstanceValue` | ValueSpecification | values:Any[*] |
| `valuespecification::FunctionExpression` | Expression | func:Function[1], parametersValues:ValueSpecification[*], propertyName:InstanceValue[0..1], qualifiedPropertyName, functionName, resolvedTypeParameters |
| `valuespecification::SimpleFunctionExpression` | FunctionExpression | — |
| `valuespecification::VariableExpression` | Expression | name:String[1] |

Hierarchy size: ValueSpecification has 16 transitive subclasses in the
metamodel namespaces (RoutedValueSpecification +9 under it: Extended-, TDS-,
NoSet-, FunctionRouted-, ClassSetImplementationHolder…, ClusteredValueSpecification).
Two natives sit on these trees:
`evaluateAndDeactivate` (`legend-pure …/essential/meta/reflect/evaluateAndDeactivate.pure:17`,
`native function … evaluateAndDeactivate<T|m>(var:T[m]):T[m]`) and
`openVariableValues` (`…/reflect/openVariableValues.pure:17`,
`native … (f:Function<Any>[1]):Map<String, List<Any>>[1]`).

**Who reads them (receipts):** group H — `$f.expressionSequence->at(0)->cast(@FunctionExpression).parametersValues->at(0)` ×5 chains (testPureToSql.pure); group J — `.expressionSequence->evaluateAndDeactivate()->cast(@ClusteredValueSpecification).val->toOne()`, `->cast(@StoreMappingRoutedValueSpecification).sets.class.name`; group A — `pkOfFunc` walks `$func.expressionSequence` (pkInferenceHelpers.pure:20 says so verbatim: "Bodies are never executed; pkOfFunc() only walks their expression tree") and hands it to `inferPrimaryKeyColumnNames(vs:ValueSpecification[1])` (helperFunctions.pure:582); group E — `scanRelations(f:FunctionDefinition<Any>[1], m, ext)` (scanRelations.pure:74-91) does `$f.expressionSequence->evaluateAndDeactivate()->last()`, `$f->openVariableValues()`, `inlineQualifiedProperties`, `scanProperties(…)->buildPropertyTree()`.

### 2b. The mapping metamodel — `legend-pure/…/platform_dsl_mapping/grammar/mapping.pure`
| class | line | properties |
|---|---:|---|
| `meta::pure::mapping::Mapping` (extends PackageableElement, Testable) | 26 | includes:MappingInclude[*], classMappings:SetImplementation[*], enumerationMappings, associationMappings |
| `SetImplementation` (PropertyOwnerImplementation) | 61 | root:Boolean[1], class:Class<Any>[1] (+ id, superSetImplementationId, parent from PropertyOwnerImplementation) |
| `PropertyMappingsImplementation` | 68 | stores:Store[*], propertyMappings:PropertyMapping[*] |
| `InstanceSetImplementation` (SetImplementation, PropertyMappingsImplementation) | 74 | mappingClass[0..1], aggregateSpecification[0..1] |
| `PropertyMapping` | — | owner, targetSetImplementationId, sourceSetImplementationId, property, localMappingProperty, localMappingPropertyType/Multiplicity, store |

Relational specialization (`legend-pure/…/platform_store_relational/grammar/relationalMapping.pure`):
`RelationalInstanceSetImplementation` (:26) primaryKey:RelationalOperationElement[*];
`RootRelationalInstanceSetImplementation` (:46) = RelationalInstanceSetImplementation + `RelationalMappingSpecification` (userDefinedPrimaryKey, filter, distinct, groupBy, **mainTableAlias:TableAlias**);
`RelationalPropertyMapping` (:66) relationalOperationElement:RelationalOperationElement[1], transformer[0..1].
Hierarchies: SetImplementation 17 transitive subclasses, PropertyMapping 19.

**Function-shaped navigation the tests use (bodies read):**
- `classMappingById(_this:Mapping, id)` — functions_Mapping.pure:74: recursive over `includes` (`_classMappingByIdRecursive` :66), then `addAssociationMappingsIfRequired`.
- `rootClassMappingByClass(_this:Mapping, class)` — :61: `_classMappingByClass` (:28, recursive over includes, filters `cm.class == $class`, also aggregation-aware sets) `->filter(root == true)->last()`.
- `propertyMappingsByPropertyName(_this:PropertyMappingsImplementation, s)` — functions_PropertyMappingsImplementation.pure:62: a `match` over OtherwiseEmbedded / Embedded / AggregationAware / InstanceSetImplementation, then `allPropertyMappings()->filter(pm|$pm.property.name == $s)` (:57).
- `mainTable(_this:RelationalMappingSpecification)` — platform_store_relational/functions.pure:277: `$_this.mainTableAlias.relationalElement->match([t:Table|$t, v:View|$v->mainTable()])`.
- `resolvePrimaryKey` — engine helperFunctions.pure:439-458: `match` over Embedded/Root/RelationFunction set implementations (16 calls in group F).
- `allSuperSetImplementations(set, m)` — engine mappingExtension.pure:163 (11 lines, reads superSetImplementationId, recurses via classMappingById).

Every one of these is a SMALL pure function over model facts plus a
`match` on the set-implementation subtype. That is the whole "derived"
layer for group F (27 tests) — nothing here is analysis.

### 2c. The relational store metamodel — `legend-pure/…/platform_store_relational/grammar/relational.pure`
| class | line | properties |
|---|---:|---|
| `Database` (SetBasedStore, AnnotatedElement) | 29 | schemas:Schema[*], joins:Join[*], filters:Filter[*] |
| `Schema` | 36 | name, database, tables:Table[*], views:View[*], tabularFunctions |
| `relation::Table` (NamedRelation, AnnotatedElement) | 92 | schema:Schema[1], primaryKey:Column[*], milestoning:Milestoning[*], temporaryTable (+ name, columns:Column[*] from Relation/NamedRelation) |
| `relation::View` (NamedRelation, RelationalMappingSpecification, AnnotatedElement) | 114 | schema, primaryKey:Column[*], columnMappings:ColumnMapping[*] |
| `Column` (RelationalOperationElement, SetColumn, AnnotatedElement) | 214 | name, type:DataType[1], nullable[0..1], owner:Relation[0..1] |
| `join::Join` | 173 | name, database[0..1], target:TableAlias[0..1], aliases:Pair<TableAlias,TableAlias>[*], operation:Operation[1] |
| `join::JoinTreeNode` (RelationalTreeNode) | 148 | setMappingOwner, database, joinName, join, joinType, lateral |
| `TableAlias` (Alias) | 205 | setMappingOwner, database, schema, relation() {relationalElement->cast(@Relation)} |

### 2d. The SQL AST — `RelationalOperationElement` family (relational.pure)
**80 transitive subclasses, 142 own properties** in the metamodel namespaces
(Relation 19 of them: TDS, NamedRelation, Table, View, SelectSQLQuery,
Union, RootJoinTreeNode, CommonTableExpressionReference, …; Function/
Operation: DynaFunction(name, parameters), Literal(value:Any), LiteralList,
SQLNull, TableAliasColumn(setMappingOwner, columnName, alias, column),
ColumnName, VarPlaceHolder/VarSetPlaceHolder/VarCrossSetPlaceHolder, …).
`SelectSQLQuery` (:240) alone has 15 properties (distinct, data, filteringOperation, groupBy, pivot, having, qualify, orderBy, fromRow, toRow, leftSideOfFilter, savedFilteringOperation, extraFilteringOperation, preIsolationCurrentTreeNode, commonTableExpressions).

**Who touches it:** group F CONSTRUCTS `^Literal`, `^DynaFunction`,
`^LiteralList` (12+10+1) and reads `.relationalOperationElement`,
`.columnName`, casts `@TableAliasColumn` ×16 — then calls
`inferRelationalType(rop)` (relationalExtension.pure:120, closure 385
lines/8 fns) and `dataTypeToSqlText` (per-dialect: DuckDB
`typeConversion.pure:57`, Postgres `typeConversion.pure:48`). Group G
constructs `^Literal` ×18, `^DynaFunction` ×8, `^TableAliasColumnName`,
`^TableAlias` and converts them with
`convertElement(r:RelationalOperationElement, state)` (toPostgresModel.pure:82,
36 lines, reads alias/column/columnName/dataType/name/schema/values/varName
and dispatches through `state.dynaFunctionConverterMap`) into PROTOCOL
`Node` trees, then `assertEquals($expected, $actual)` = **structural
equality of constructed protocol instances** (testGroupG `assertConversion`,
tests.pure). Group Z reads generated SelectSQLQuery objects
(`->cast(@SelectSQLQuery)`, `.relation`, `->toOne().second.relation`) and
builds JoinTreeNodes by hand (`buildJoinTreeNode`, `OldAliasToNewAlias` ×6,
`testReAliasMergedJoinOperations`: `.joinAliases/.jtnAliases/.missingJoinAliases`).

### 2e. Execution plans — `legend-engine/…/executionPlan.pure`
`ExecutionPlan` (:61): func:FunctionDefinition, mapping, runtime,
**rootExecutionNode:ExecutionNode[1]**, processingTemplateFunctions:String[*],
authDependent, kerberos, globalImplementationSupport.
`ExecutionNode` (:74): fromCluster:ClusteredValueSpecification[0..1],
resultType, resultSizeRange, **executionNodes:ExecutionNode[*]**,
authDependent, kerberos, supportFunctions, requiredVariableInputs,
implementation, qualified `childNodes()` (a fold over executionNodes).
**22 transitive subclasses, 40 own properties** (SQLExecutionNode :63 —
sqlQuery:String[1], resultColumns:SQLResultColumn[*], connection:
DatabaseConnection[1], metadata, isResultColumnsDynamic, isMutationSQL;
RelationalInstantiationExecutionNode :88 — no own props; Sequence,
MultiResultSequence, Allocation, Constant, FreeMarkerConditional,
VariableResolution, PureExpressionPlatform, PlatformUnion/Merge,
CreateAndPopulateTempTable, StoreStreamReading, RelationalSave, …).
**Who reads:** group Q — `.rootExecutionNode.executionNodes`, `.sqlQuery`,
`->cast(@SQLExecutionNode)` ×4, `.first.processingTemplateFunctions`,
`allNodes()`; groups B/M/O/K print with `planToString` (closure 916
lines / 33 fns). The plan node model is ALSO legend-lite's own artifact
(`PlanAllocations`, `PlanEnvelope`, `plan/`), which is why leg 1 chose it.

### 2f. Router / clustering — `legend-engine/…/router/…/metamodel.pure`, `clustering.pure`
`ClusteredValueSpecification` (clustering.pure:16, extends
ValueSpecification): executable, exeCtx, openVars, **val:ValueSpecification**.
`StoreMappingRoutedValueSpecification` (metamodel.pure:31): mapping,
processedChainSets, **sets:SetImplementation[*]**, propertyMapping.
`StoreMappingRoutingStrategy` (:51): mapping, sets:PermutationSet[*],
setsByDepth:Map, toChooseSet, classMappingsByClass:Map<Class,List<SetImplementation>>.
RoutedValueSpecification: 9 transitive subclasses. **Who reads:** group J
(testRouting: `routeFunction(…)`, `.expressionSequence->evaluateAndDeactivate()->cast(@ClusteredValueSpecification).val`,
`->cast(@StoreMappingRoutedValueSpecification).sets.class.name`), group N
(`RoutingState`, `routeFunctionExpressionFunctionDefinition` closure 5,598
lines / 123 fns; `preval` 3,854 / 82).

### 2g. Runtime and connections
`meta::core::runtime::Runtime` (runtime.pure:17): connectionStores:
ConnectionStore[*], preprocessFunction. `ConnectionStore` (:24):
connection:Connection[1], element:Any[1]. `ExecutionContext`
(runtime.pure:34): queryTimeOutInSeconds, enableConstraints (both
equality keys). `RelationalDatabaseConnection` (engine connection.pure:29):
datasourceSpecification, authenticationStrategy, postProcessors.
`TestDatabaseConnection` (relationalRuntime.pure:101): testDataSetupCsv,
testDataSetupSqls, testDataSchemas:Schema[*]. **Who:** constructed in
groups D/K/M/O/P/Q (`^ConnectionStore` ×16, `^ExecutionContext` ×15,
`^Runtime` ×8, `^RelationalDatabaseConnection` ×10 …); READ in D/Z
(`.connectionStores->at(0).connection->cast(@TestDatabaseConnection)`);
group P compares connections for equality through `routerExtensions`
(5 tests, `Connection` match arms ×5).

### 2h. Lineage — `legend-engine/…/lineage/scanRelations/scanRelations.pure`, `scanProperties.pure`
`RelationTree` (:47): relation:NamedRelation[0..1], root, join:Join[0..1],
children:RelationTree[*], nestedViewTree, columns:Column[*], qualified
isTable/table/isView/view. `PropertyPathTree` (scanProperties.pure:54):
display, value:Any, children, qualifierSubTree. `PropertyPathNode` (:27):
class, property, nestedQualifier, parameters, nestedQualifierReturn.
**Who:** group E (21) asserts `relationTreeAsString` TEXT
(scanRelations.pure:108/113/118); group I (6, scanColumnsTests) runs
`scanProperties->buildPropertyTree->scanColumns` and reads
`.column.owner->cast(@Table).name`.

### 2i. Protocol trees and JSON
`meta::protocols::pure::vX_X_X::…` (`transformMapping` closure 3,370 lines,
`transformClass` 3,002, `transformAssociation` 2,930 — group N
`testClassesAssociationsAndMappingFromDatabase`, `PureModelContextData`,
`alloyToJSON`); toPostgresModel `Node` trees (group G, §2d); `meta::json::
JSONObject/JSONArray/JSONString` (`parseJSON`, `getValue`, groups D/J/N —
object-reference decoding, `decodeObjectReferencesAndGetPkMap` closure
1,610 lines).

### 2j. Not metamodel at all, filed here by the walls
`TDSRow` match arms (27 in group E, 28 overall) are the TDS join lambdas
of the scanRelations tests' QUERIES; `TabularDataSet` casts are result
reads. They ride with the tests but are not objects to store.

## 3. Fact origin — where each object family's truth already lives

| family | origin | receipt |
|---|---|---|
| Class / Property / Association / Enumeration / generalizations | **COMPILE-TIME, ours** | `com.legend.model.ClassDefinition` (:48, `PropertyDefinition` :91); `SystemMetamodel` already seeds `metamodel.classes(fqn,name,package)` from `ModelContext.classifierInstances` (SystemMetamodel.java, one table, "grow by witness") |
| Mapping / includes / class mappings / property mappings / PK columns / main table | **COMPILE-TIME, ours** | `MappingDefinition.ClassBinding.Relational(classFqn, setId, extendsSetId, root, functionFqn, primaryKeyColumns, RelationalSource.Table)` (:89), `ClassBinding.Pure` (:107), `AssociationBinding` (:248); `ClassMapping.Relational/Pure/Union/Inheritance/RelationFunction` (:156/232/340/359/388); `PropertyMapping.Column/Join/JoinTerminalColumn/Expression/Embedded/InlineEmbedded/OtherwiseEmbedded/LocalProperty` (:72-315) |
| Database / Schema / Table / Column / View / Join / Filter / milestoning | **COMPILE-TIME, ours** | `DatabaseDefinition(SchemaDefinition, TableDefinition(columns, Milestoning), ColumnDefinition(name, dataType, primaryKey, notNull), ViewDefinition(ViewColumnMapping), JoinDefinition(name, RelationalOperation), FilterDefinition)` (:36-202) |
| mainTable through a view, PK resolution, class mapping by id through includes, super-set chain | **DERIVED by small pure functions over the above** | bodies in §2b (10-40 lines each; `match` on set-implementation subtype) — our resolver computes the same facts today (`ClassSources.findBinding`/`classBindingsWithIncludes`, `MappingNormalizer.mainTableDefOf`, PK: `DriverPkAppend`, `ViewRelation.mainSourceRef`) |
| expressionSequence trees (function bodies) | **COMPILE-TIME, ours, ALREADY A TREE**: the typed tree `TypedSpec` (`compiler/spec/typed/`, 60+ node records with `children()/withChildren()`) IS the resolved expression tree; `SpecCompiler.compile(fn).body()` gives a function's body | the engine's m3 tree (§2a) is the same information in a different shape; ours is post-inference (types/multiplicities resolved) |
| routed / clustered value specifications | **DERIVED by the resolver** (it IS our router): class sources, set choice, join demand | `resolver/` (StoreResolver, ClassSources, Pipelines) — but NO routed-VS object exists as a value today |
| lineage RelationTree | **DERIVED by the resolver's join demand** (the tree of relations reached, with join and columns) | `Pipelines.outerNavSteps`, `JoinChainEmission`; the Java `lineage.ScanRelations` is a parallel re-implementation, ledger-pinned as harness-only (ruling) |
| execution plan nodes | **RUNTIME-PRODUCED by our compiler** (plan text renders via `EngineStyleH2`; `PlanAllocations`, `PlanEnvelope`) | the engine's plan is a tree of typed nodes (§2e); ours is a rendered program with holes |
| SQL AST objects (`^Literal`, `^DynaFunction`, `SelectSQLQuery`) | **CONSTRUCTED BY TESTS** (group F/G/Z) or **PRODUCED by our lowering** (our `SqlExpr`/`SqlSelect` IR, `sql/`) | `SqlExpr` is a sealed IR with `StructLit`, `Call`, `Column`, `WindowCall`…; `SqlSelect`/`SqlSource` (Table, Subselect, Values, Join, Pivot, RawSql, Dual, VarSetPlaceholder) |
| connections / runtimes / execution contexts | **CONSTRUCTED BY TESTS** as instance literals; read back structurally | struct-values design landed ([[struct-values-design-landed]]): `TypedNewInstance` lowers to `SqlExpr.StructLit` (Lowerer.java:2686) |
| protocol Node trees (toPostgresModel) | **CONSTRUCTED by an engine transform** over constructed SQL AST | `convertElement` (36 lines + per-node converters via `dynaFunctionConverterMap`) |
| compile-from-text (`executeLegendQuery`, `compileLegendGrammar`, `compileLegendValueSpecification`) | **HARNESS VOCABULARY**: run pure TEXT inside a test | `meta::legend::executeLegendQuery` own body 20 lines (engine `legend.pure`), the others 6 lines — thin wrappers over the engine's compile+execute |

## 4. Query shapes — what the platform must be able to DO over those rows

Counted over the 261 tests + helpers (§ per-group operations census):

| operation | groups | count | platform meaning |
|---|---|---:|---|
| function-shaped navigation with subtype `match` inside (`classMappingById`, `rootClassMappingByClass`, `propertyMappingsByPropertyName`, `mainTable`, `resolvePrimaryKey`, `view`) | F | 25+11+13+8+16+6 | a qualified property / function over a metamodel row whose body dispatches on the row's SUBTYPE |
| `->cast(@Subtype)` on a metamodel value | F, H, J, Q, Z, I | RootRelationalInstanceSetImplementation 35, TableAliasColumn 16, FunctionExpression 14, RelationalPropertyMapping 13, InstanceValue 9, Table 5, SQLExecutionNode 4, … | downcast = row of the subtype's table (or narrowed node kind) |
| tree walk by position (`.expressionSequence->at(i)->cast(@FunctionExpression).parametersValues->at(j)`) | H, J | 5 chains ×4 tests | ordered children of a tree node |
| `evaluateAndDeactivate` / `openVariableValues` / `reactivate` | J, E (via engine) | 1 direct + inside engine closures | tree-as-value natives |
| construct metamodel instances (`^Literal`, `^DynaFunction`, `^TableAlias`, `^Column`, `^Runtime`, `^ConnectionStore`, `^ExecutionContext`, …) | F, G, Z, D, P, Q | ~150 | instance literals of metamodel classes (struct values) |
| structural equality of constructed instances (`assertEquals($expectedNode, $actualNode)`, connection equality) | G, P | 10 + 5 | deep struct compare incl. subtype |
| render to TEXT and compare (`dataTypeToSqlText`, `relationTreeAsString`, `planToString`, `asString`, `sqlQueryToString`) | F, E, B/M/O/Q/K, J, Z | 24, 21, ~15, 3, 6 | owned printers over rows (the ruling's "recursive query + egress formatter") |
| analysis over trees (`inferRelationalType`, `inferPrimaryKeyColumnNames`, `scanRelations`, `scanColumns`, `routeFunction`, `preval`, `convertElement`) | F, A, E, I, J, N, G | 24, 43, 21, 6, 1, 3, 10 | engine ANALYSES whose results become rows (resolver side-outputs) — §5 |
| compile-and-run pure text | D | 43 | one router entry from a string |

## 5. Engine functions the family calls — sized (transitive pure closure, all overloads)

| function | own lines | closure lines / fns | what it is for us |
|---|---:|---:|---|
| `relationalExtensions()` | 4 | 21,192 / 683 | the extension registry (boilerplate arg) — not a feature |
| `planTestDataGeneration` | 388 | 23,449 / 773 | TDG — we own it natively (`testdatagen/`) |
| `scanRelations` (lineage) | 1,600 | **22,290 / 735** | the engine's lineage program; our fact = the resolver's join demand |
| `toSQLQuery` / `sqlQueryToString` | 30 / 100 | 17,673 / 653; 8,324 / 264 | the SQL generator — our platform IS this |
| `routeFunctionExpressionFunctionDefinition` / `preval` | 242 / 276 | 5,598 / 123; 3,854 / 82 | the router — our resolver IS this; the ROUTED-VS objects do not exist as values here |
| `transformMapping` / `transformClass` / `transformAssociation` (protocol) | 18 / 48 / 18 | 3,370; 3,002; 2,930 | protocol emission — we own `ProtocolEmitter`/`Protocol` |
| `trimColumnName` (reAliasColumnName post-processor) | 42 | 2,064 / 32 | post-processor leg (named) |
| `executionPlan` / `planToString` | 1,034 / 16 | 1,973; 916 / 33 | plan build + print — ours renders via `EngineStyleH2` |
| `decodeObjectReferencesAndGetPkMap` | 10 | 1,610 / 36 | object references (group J) |
| `classesAssociationsAndMappingFromDatabase` | 6 | 700 / 35 | autogen (1 test) |
| `asString` (router printer) | 420 | 695 / 16 | pure-expression printer (group J/N) |
| `dropAndCreateTableInDb` | 396 | 397 / 2 | DDL — ours (`toDDL`) |
| `inferRelationalType` | 260 | **385 / 8** | SQL expression typing — our typed SQL IR already carries `TypeFact`s (`SqlTyping`, "Typed[type=VARCHAR, nullable=…]") |
| `inferPrimaryKeyColumnNames` | 6 | **286 / 10** | PK inference over an expression tree — our resolver computes PKs (`DriverPkAppend`; harness `PkInference` is the parallel copy) |
| `allSuperSetImplementations`, `from`, `execute`, `executeInDb`, `toSQLString`, … | 1-64 | < 70 | thin |
| `executeLegendQuery` / `compileLegendGrammar` / `compileLegendValueSpecification` | 20 / 6 / 6 | 20 / 6 / 6 | compile-from-text entry (group D) |

Reading: the BIG closures are programs we already own natively (SQL
generation, planning, TDG, routing, protocol). The metamodel program does
not re-own those; it exposes their FACTS as rows. The genuinely
metamodel-shaped engine code the tests depend on is small: the mapping
navigations (§2b, 10-40 lines each), `mainTable` (8 lines), PK resolution
(20 lines + the 286-line inference), type inference (385), the printers
(`relationTreeAsString` 4×3 overloads, `asString` 695), `convertElement`
(36 + converters).

## 6. What the platform already has — receipts

- **SystemMetamodel v1** (`core/src/main/java/com/legend/builtin/SystemMetamodel.java`):
  fixed Pure SOURCE (a `###Relational` store `meta::lite::metamodel::MetamodelStore`
  with ONE table `metamodel.classes(fqn PK, name, package)` + a `###Mapping`
  `meta::lite::metamodel::MetamodelMapping` mapping `meta::pure::metamodel::type::Class`
  with `~primaryKey(fqn)`, `~mainTable metamodel.classes`, `name:` column),
  injected into every model build (`Compiler.java:232/267`), seeded by
  `seedStatements` from `ModelContext.classifierInstances`, referenced by
  the resolver (`StoreResolver.java:1244`). `Class.all()` is a real SELECT.
  Identity rule D2: the FQN is the key; `package` is a column only.
- **Inheritance / union class mappings**: `ClassMapping.Union` (:340),
  `ClassMapping.Inheritance` (:359), synthesized by `UnionSynthesis`;
  subtype dispatch columns `ClassMapping.isSubTypeColumn` (:62) and
  `subType(@Sub)` reads (corpus family tests/mapping/extends 23/23,
  inheritance 46/47 pass).
- **`match`**: `MatchChecker.java:18-70` — **STATIC dispatch**: the first
  branch whose declared type accepts the input's STATIC type is selected
  at compile time; runtime dispatch exists only (a) on optional COUNT
  (`if(isNotEmpty(...))` emission, M4) and (b) as `TypedMatchRuntime` kept
  for the HOST channel when a branch is a strict subtype of the static
  type — "SQL lowering has no runtime type dispatch and walls loudly".
  `instanceOf` folds only when statically decided (`Scalars.instanceOfFold`,
  :2508). `CastNav` (resolver) handles the M2M `^Target($src.slot)` cast.
  **This is the one capability the metamodel program needs and does not
  have: `match`/`instanceOf`/`cast` dispatched on a ROW's discriminator.**
- **Struct values**: `TypedNewInstance` → `SqlExpr.StructLit` (Lowerer:2686);
  canonical layouts, covariance, variant carrier ([[struct-values-design-landed]]).
- **Recursion**: NONE in the SQL IR. `SqlSource` kinds are Pivot, SourceUrl,
  Table, VarSetPlaceholder, Dual, Subselect, Values, RawSql, Join
  (SqlSource.java:30-127); `SqlExpr.FoldCall` (:992) notes "a lambda-less
  backend: recursive CTE or a loud error" as a future. No `WITH RECURSIVE`
  anywhere in `sql/` or `lowering/`. DuckDB and H2 both support it.
- **Dialects**: two execution dialects (DuckDB, H2 advisory lane) + engine-text
  renderers; list encodings are a named capability gap (31 rows).

## 7. Your three questions, answered on the evidence

**Q1 — what objects do we need to store?** Not "the metamodel". The 261
tests touch **78 classes in 9 families** (§2), and the families split by
ORIGIN (§3) into three things that are stored differently:
1. **Model facts** (Class, Property, Mapping, SetImplementation,
   PropertyMapping, Database, Schema, Table, Column, View, Join, Filter,
   Runtime, Connection): compile-time, already in our records, a dump.
   Inventory for the seed: the properties the tests READ, which is a
   subset of §2 — for the 27 group-F tests: `classMappings`, `includes`,
   `id`, `root`, `class`, `superSetImplementationId`, `propertyMappings`,
   `property.name`, `relationalOperationElement`, `primaryKey`,
   `mainTableAlias`, `columns`, `name`, `type`, `schema`.
2. **Trees** (expressionSequence / ValueSpecification, execution plan
   nodes, lineage RelationTree/PropertyPathTree, routed/clustered VS, SQL
   AST, protocol Node trees): heterogeneous, recursive, ordered children.
   Ours already exist as typed trees (`TypedSpec`, `SqlSelect/SqlExpr`,
   plan program) — the question is exposure as rows, not construction.
3. **Constructed instances** (`^Literal`, `^ConnectionStore`, protocol
   Nodes): test-authored values — struct literals, not seeds.

**Q2 — typed tables or one node table?** The hierarchy sizes decide it,
and they decide it DIFFERENTLY per family. Model facts have shallow,
closed hierarchies with few subtypes actually read (SetImplementation 17
subclasses but tests cast to 2; PropertyMapping 19 but tests cast to 1):
**typed tables per class with an inheritance mapping** (our own
`ClassMapping.Inheritance`/subtype columns — dogfooding the feature). The
trees are the opposite: RelationalOperationElement has **80 subclasses
and 142 properties**, ExecutionNode 22/40, ValueSpecification 16+9; the
tests walk them by position and cast by kind. Per-class tables there
means 80 tables for the SQL AST alone, and every tree walk becomes a
join across kinds. For trees the honest shape is **one node table per
tree KIND** (expression, plan, relation-tree, sql-ast, protocol):
`node_id, parent_id, ordinal, kind (the class FQN), edge_label` + the
per-kind payload as a **struct/JSON column** (the variant carrier we
already have), with typed VIEWS per subclass where a test casts (a view
over `kind = '…'` exposing the struct fields as columns) — that gives
`->cast(@FunctionExpression)` a typed row and keeps the adjacency list
single. This is a decision made on counts, not taste; the prototype
(§9) must confirm the struct-column read path lowers.

**Q3 — do we need runtime facts, late binding, subtype polymorphism?**
Yes to all three, with receipts, and they are the crux:
- *Runtime facts*: groups Q/B/M/O/K read PLANS (`rootExecutionNode.executionNodes`,
  `sqlQuery`, `processingTemplateFunctions`), group E reads LINEAGE trees,
  group J reads ROUTED value specifications — all produced per query by
  our compiler/resolver. The ruling already says how: **resolver
  side-output rows** ("same category as emitting SQL"), materialized into
  the per-query temp-table lifecycle the TDG lane uses. The receipts show
  what to emit: plan nodes with `sqlQuery`/`resultColumns`/`connection`
  and their ordered children; relation-tree nodes with `relation`,
  `join`, `columns`; routed VS with `sets` and `val`.
- *Late binding*: `$x->mainTable()` is a function whose body dispatches on
  `relationalElement`'s subtype (Table vs View, functions.pure:277);
  `propertyMappingsByPropertyName` dispatches on four set-implementation
  subtypes; `resolvePrimaryKey` on three. Every navigation the F group
  uses is a `match` over subtypes of a ROW value.
- *Polymorphism in the platform*: MatchChecker is STATIC (§6). Over a
  row whose static type is `SetImplementation` and whose kind lives in a
  discriminator column, the first-accepting rule would silently take the
  wrong arm; the guard currently keeps all arms for the HOST channel and
  the SQL lowering walls. So the design REQUIRES a new lowering:
  **`match`/`instanceOf`/`cast` over a discriminated row = `CASE kind
  WHEN … THEN <arm>` (scalar arms) or a UNION of per-kind arms (relation
  arms)**, with the arm's parameter bound to the subtype VIEW. This is the
  single platform feature the program cannot proceed without, and it is
  a user-facing feature too (users with inheritance mappings hit the same
  wall) — the dogfooding pays here.

## 8. The rest of the questions (yours plus the ones the census raised)

Decidable NOW from the receipts:
1. Identity: FQN/path for model elements (D2, SystemMetamodel); for tree
   nodes a per-query `(query_id, node_id)`; for constructed instances no
   identity (they are values) — structural equality.
2. Seeding granularity: the model layer is seeded once per model build
   (compile-once corpus: one model + per-test overlays, ~50s sweep);
   trees per query at resolve time into temp tables (TDG lifecycle).
3. Include chains and super-set chains: seed the TRANSITIVE CLOSURE
   (`mapping_includes_closure`, `set_supers_closure`) at extent-render
   time — the PROGRAM_MAP homework already ruled out recursive CTEs for
   these; both are small and static.
4. `classMappingById`/`rootClassMappingByClass`/`propertyMappingsByPropertyName`/
   `mainTable`/`resolvePrimaryKey`/`allSuperSetImplementations`: register
   as natives whose implementations are QUERIES over the seed tables
   (the engine bodies in §2b are the spec; ours are the resolver's own
   facts). Not pure source carried by the platform (ruling).
5. Printers (`relationTreeAsString`, `planToString`, `asString`,
   `dataTypeToSqlText`): owned recursive queries over the node tables +
   an egress formatter; `dataTypeToSqlText` is a per-dialect scalar
   spelling table (the engine's own is one function per dialect).
6. `inferRelationalType`: our `SqlTyping` facts over a constructed SQL
   AST — the test constructs `^DynaFunction(name='and', parameters=[…])`
   and asks its type; we type SQL IR already. Needs: constructed SQL-AST
   struct → SQL IR node (a decoder), then the existing type facts.
7. Harness vocabulary (group D, 43): `executeLegendQuery(text, …)` is
   compile-from-string through the ONE router — `Compiler.execute` is
   exactly that entry today. This group is likely the cheapest of all;
   it was misfiled under "metamodel" by its FQN.

Decidable only by PROTOTYPE:
8. Does a struct/JSON payload column read lower through the ordinary
   pipeline when reached via a kind-filtered VIEW (`->cast(@FunctionExpression).parametersValues->at(0)`)?
9. Cost: seed size and time for the model layer at corpus scale (the
   PROGRAM_MAP flagged this as unmeasured; still unmeasured).
10. Tree exposure: does the resolver's join-demand walk reproduce the
    engine's `relationTreeAsString` ORDER and naming (`bTable1(equal_rootfk_bTable1_d#2_d_m2_m1fk)`
    — alias naming is engine-generated; the golden text pins it)? The 21
    tests compare TEXT with engine alias spellings — this may be a
    text-contract family (declines by name) rather than a rows family.
11. Match-over-discriminator lowering: CASE for scalar arms is
    straightforward; relation-valued arms (an arm that returns
    `mainTable()` rows) need UNION-of-arms with a shared schema — the
    prototype must show one.
12. Constructed protocol Node equality (group G): struct equality over
    nested structs with subtypes — the variant carrier's canonical
    layouts may already give byte-equal JSON; must be shown.
13. `evaluateAndDeactivate`/`openVariableValues`/`reactivate`: over our
    typed tree these are identity / open-variable listing / re-typing —
    trivial or impossible depending on whether a tree row can be turned
    back into an executable (reactivate). Group J has one such chain.

## 9. Tractability verdict and the prototype order

| group | tests | blocking capability | verdict |
|---|---:|---|---|
| F mapping-metamodel navigations | 27 | seed tables + natives-as-queries + match-over-discriminator (scalar arms) | TRACTABLE — first target |
| D harness vocabulary | 43 | compile-from-string through the one router | TRACTABLE — cheapest, not really metamodel |
| Q/B/M/O/K plan reads and prints | ~26 | plan nodes as side-output rows + printer | TRACTABLE once plan-nodes-as-rows (leg 1) lands |
| A pkInference | 43 | expression tree rows + PK inference as a resolver side-output (286-line spec) | TRACTABLE, medium |
| H/J expression tree walks, routing reads | ~21 | expression tree rows + kind views + `evaluateAndDeactivate` semantics | TRACTABLE for reads; `reactivate` unknown |
| E scanRelations text | 21 | relation-tree side-output + engine alias spelling in the golden TEXT | UNCERTAIN — may stay a named text-contract family |
| I scanColumns | 6 | property-path trees + column lineage | as E |
| G toPostgresModel | 10 | SQL-AST structs → protocol Node structs, deep struct equality | UNCERTAIN — needs the struct-equality prototype (§8.12) |
| N/P/Z singletons | ~32 | assorted (protocol emit, DDL, connection equality) | per witness |

Prototype order (each an acceptance test, each a gated batch):
1. **testMainTableForB1** (group F): seed `class_mappings(mapping_fqn, id, class_fqn, root, super_set_id, main_table_db, main_table_schema, main_table)` + `mapping_includes_closure`; native `classMappingById` = query; native `mainTable` = query; `->cast(@RootRelationalInstanceSetImplementation)` = the subtype view; verdict lands in-DB. Proves seeds + natives + cast-as-view.
2. **testDynaAndOrInference** (group F): adds `property_mappings` rows and the constructed-SQL-AST → SQL IR decoder; proves `match`-over-discriminator with SCALAR arms (`propertyMappingsByPropertyName`) and `inferRelationalType` over our type facts.
3. **one group-D test**: `executeLegendQuery` as the router's string entry.
4. **plan-nodes-as-rows (leg 1)**: one group-Q test reading `.rootExecutionNode.executionNodes->cast(@SQLExecutionNode).sqlQuery`; proves the side-output temp-table lifecycle and the kind view over a node table.
5. **tesIsToOneDataTypeFunctionExpressionSequence** (group H): expression tree rows from `TypedSpec`, ordered children, `->at(i)->cast(@FunctionExpression).parametersValues`.
Then the verdict on E/G/I with the mechanisms in hand, not before.

## 10. What this document deliberately does not claim
- It does not claim the ~88 HN-vocabulary tests' shapes (§0 gap).
- It does not claim seed cost, struct-column lowering, relation-valued
  match arms, or Node structural equality — those are §8's prototype
  items, listed as such.
- It does not propose carrying any engine pure source; every engine body
  cited is the SPEC for a native whose implementation is a query over our
  own facts.

## 11. Answers to the review questions (2026-09-02) — not glossed

**355 vs 261.** Two instruments. 355 = a sum of fallback-census BUCKET rows
(tests by FIRST wall; exact at 848). 261 = tests my per-test parse could
name from `[flip-wall/fail-debug]` lines. The difference is the two HN
buckets (65 plan-walk + 45 `mapping::sql`) whose wall prints no per-test
line (22 of 110 surface via `[v7] decline-witness`); the rest were the
~11 stamp rows filed under the multiplicity leg. First-wall filing means
the family is neither a floor nor a ceiling for flips: tests filed
elsewhere may need metamodel facts later, and metamodel-filed tests may
carry later non-metamodel walls (E's TDS join lambdas, for one).

**Dynamic models.** SystemMetamodel already seeds from the ACTIVE model
context at execution setup (compile-once + per-test overlays), so V0 is
not a static file. NOT designed and owed: the seed LIFECYCLE for users —
when (session / query / model hash), where (per-connection temp schema vs
shared), overlays as row deltas vs full re-seed, invalidation, cost at
any scale (unmeasured). Identity stays FQN + model hash: one Pure graph
holds one element per FQN, so versions are contexts, never rows.

**Worries, explicitly.**
1. TREE SHAPE: our `TypedSpec` is post-inference with OUR node kinds; the
   H/J/A tests walk the ENGINE's m3 shape (`SimpleFunctionExpression`
   with `func`/`parametersValues`/`propertyName`). The row projection
   must present the m3 shape, or those tests diverge; pkInference's
   286-line spec is written against m3 shapes. Undesigned.
2. TEXT GOLDENS: group E (21) and the plan-print groups compare
   engine-generated alias/plan TEXT; rows can exist and the verdict still
   be text. Likely permanent named residue unless spelling is matched.
3. Relation-valued `match` arms (Table row vs View path in `mainTable`)
   need schema unification; only scalar arms were reasoned through.
4. `reactivate` (rows → executable): probably a permanent wall (one chain).
5. H2 LANE: node-table payloads are struct/JSON columns; the H2 execution
   dialect walls on list encodings 863 times today — the tree half may
   not run on H2 until Layer 4 is decided.
6. JAVA GROWTH: the constructed-SQL-AST decoder and side-output emitters
   are Java the evaluation ledger counts; the "emitting rows vs parallel
   evaluator" line will be tested every batch.
7. Group G (protocol Node structural equality) and the 45 `mapping::sql`
   HN tests are unverified beyond bucket names.

**Tractability verdict.** As a program: yes, split. The model-facts half
(F 27, D 43, plan reads ~26, pkInference 43 via a PK side-output) ≈ 140
tests rides three mechanisms — seed tables + natives-as-queries,
match/cast over a discriminator, resolver side-output rows — and is a
confident bet after prototypes 1-2. The tree-exposure half hinges on the
m3-shape projection; the text-golden and protocol-equality groups may
never flip through rows. Expectation: well over half the family lands
and the rest is named with receipts. NOT tractable as "all 355 flip".
