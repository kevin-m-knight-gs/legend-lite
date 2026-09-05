# The remaining walk-only fallbacks: homework and plan (2026-09-05)

Scope: the fallbacks that the harness walk still passes and the platform
does not (state accb2819, batch 71: 179 fallbacks / 2394 flipped). Every
row below was researched one by one: the engine test body, the engine's
own definition of the feature, the walk's pass mechanism, and the exact
platform wall (re-probed with `-Drcorpus.test=<name>` and
`LL_TMP_DEBUG=1` where the ledger text was not enough).

## 0. Correction to the batch-71 list

The batch-71 note called 33 tests "walk-only". Re-checking each against the
scoreboard's non-passing section shows only **16** pass in the walk today.
The other 17 fail in BOTH channels (they sit in the non-passing section
with ERROR/SHAPE rows) and were mislabeled by the tmp census file. They are
still real work and are listed in §2, but they are not "fallbacks the
harness passes".

| Group | Tests | Walk passes via | Platform wall |
|---|---|---|---|
| A. objectReferenceIn | 8 | ObjectRefs.java (Java builds the references) | unknown function `generateObjectReferences`; non-literal refs |
| B. connection equality | 5 | ConnEquality.java (Java compares two instance literals) | match arm prefix from `relationalExtensions()` does not fold |
| C. asserts inside a map lambda | 1 | host evaluation | LambdaBodies: only `assert(...)` guards are accepted |
| D. two-round test-data generation | 1 | TestDataGenForm | alias-scoping bug after an inlined helper (see D) |
| E. lineage over a name-mismatched concatenate | 1 | LineageRelationsForm → platform ScanRelations | typer: relation concatenate demands equal column names |
| F. malformed JSON golden | 2 (same simple name) | walk's JSON compare accepts it | strict JSON parse of the GOLDEN (`]"`) |

## 1. The 16 walk-only tests, one by one

### A. objectReferenceIn (8 tests) — batch 72 candidate (task #20)

Tests: testObjectReferenceInSimple, ...EmbeddedMapping,
...WithBiTemporalMilestoning, ...WithEmptyLists, ...WithMilestonedProperty,
...WithObjReferenceOutput, testObjectReferneceInWithMilestonedRootClass,
and testObjectReferenceInUsingResultReferences.

What the tests do. `generateObjectReferences(version, mappingPath, setId,
runtimeFn, pkMaps, ext)` builds a list of opaque reference strings for
given primary-key values; a query then filters `Person.all()->filter(p |
$p->objectReferenceIn($refs))` (also on an embedded navigation
`$p.firm->toOne()`, on a milestoned property `$o.product(%date)->exists(...)`
and on a milestoned root) and serializes with graphFetch;
assertJsonStringsEqual on the rows. WithObjReferenceOutput additionally
decodes a reference back (`decodeObjectReferencesAndGetPkMap`) and asserts
pathToMapping / pkMap / setId. UsingResultReferences takes the
`objectReference` values OUT of a first serialize result, `->take(3)`, and
feeds them to the second query.

Engine mechanism (objectReference.pure:20-42): one AlloyObjectReference
per pkMap, serialized to JSON, base64, prefixed `ASOR:`; objectReferenceIn
decodes the trailing pk segment and becomes a primary-key membership
predicate on the set.

Platform today: objectReferenceIn exists (Pure.java native +
Substitution.objectReferenceInRewrite, literal refs only, single pk);
serialize already EMITS the reference IN SQL (SnapshotEnvelope.asorWrap:
base64 computed by the database from the pk columns). The walk's
ObjectRefs.java builds the reference strings in Java and hands them to the
platform as literals — the Java form to delete.

Plan (no Java string building, constants ride the query):
1. `generateObjectReferences[ForGivenSetId]` lowers to the SAME
   asorWrap SQL expression the serialize envelope uses, over the literal
   pk values (a VALUES relation of one row per pkMap; `pair('name','C')
   ->newMap()` keys map onto the set's ~primaryKey columns in order,
   case-insensitive — engine resolvePrimaryKeysNames parity). Result type
   String[*].
2. `objectReferenceIn($p, $refs)` = `asorWrap(p's pk columns) IN (refs)`:
   the encoded strings compare directly, so a LITERAL collection and a
   RELATION-VALUED collection (UsingResultReferences: a column of the
   previous statement's rows, `->take(3)` = LIMIT) take the same path; the
   current decode-at-resolution arm and its "needs a literal reference
   collection" wall go away.
3. `decodeObjectReferencesAndGetPkMap` (one test): base64-decode + split
   in SQL. DuckDB: from_base64 → string. H2 has NO base64 functions at all
   (probed on 2.1.214: BASE64, FROM_BASE64, BASE64DECODE all "not found"),
   so both the existing encode (AnsiSqlRenderer:687 `to_base64`) and the
   decode are DuckDB-only; these graphFetch JSON tests never verdict on
   the H2 lane.
Audit facts that bind the design (decoded from the engine golden):
the reference is `version:type:Relational:mapping:rootSetId:setId:
<connection JSON>:<pk JSON>`. Our resolver prefix (GraphEmission
.asorPrefix → AsorRef.prefix) fills the two set-id slots with the class's
own set id TWICE and embeds a CONSTANT canonical test-H2 connection JSON,
not the runtime's. Equality of our own strings still holds, but (a) the
generator's `runtimeFnPath` argument is ignored by that prefix — a
pre-existing fidelity gap, stated here, not widened; (b) the
ForGivenSetId (embedded) variant must fill the slots the engine's way:
root set id, then the embedded set id — the prefix builder needs the
second slot exposed.
Verdict: the existing JSON row verdict. Size: medium. Deletes
ObjectRefs.java.

### B. connection equality (5 tests)

Tests (testRelationalExtension.pure): two `^RelationalDatabaseConnection`
literals (type, datasourceSpecification, authenticationStrategy) and
`assert(runRelationalRouterExtensionConnectionEquality($c1,$c2))` or its
negation.

Engine mechanism: runtimeExtension.pure:54-64 —
`$a->match($extensions.routerExtensions().connectionEquality->map(e |
$e->eval($b))->concatenate([a:Connection[1] | true]))`; the relational arm
(storeContract.pure:48-63) compares type, timeZone, quoteIdentifiers,
`datasourceSpecification == datasourceSpecification` (instance equality),
`compareObjectsWithPossiblyNoProperties(auth, auth)` (storeContract.pure:290:
both classes have zero properties → true, else `==`) and
`postProcessorsMatch` (storeContract.pure:278). The store element is not
compared.

Platform wall (MatchFold.java:29): the arm collection has a dynamic prefix
(`$extensions.routerExtensions().connectionEquality->map(...)`) that folds
to `[]` only when the extensions are empty; here they are
`relationalExtensions()`. These 5 tests are the WHOLE "did not fold to []"
family in the scoreboard.

Plan (three mechanisms, all compile-time structure, no Java comparison):
1. Extension instances are a Pure program, evaluate it as one:
   `relationalExtensions()` → `^Extension(availableStores =
   [relationalStoreContract()], ...)`; `routerExtensions()` gets its real
   body (extension.pure:46: availableStores ++ availableFeatures cast to
   RouterExtension) instead of a body-less native signature
   (Pure.java:1356); `.connectionEquality` on the literal is the lambda;
   `->map(e|$e->eval($b))` beta-reduces (LiteralUnroll) to a literal arm
   list. The dynamic prefix is then spelled arms.
2. MatchFold static dispatch for CLASS inputs: the input's static type is
   `RelationalDatabaseConnection` (a `^` literal); the first arm whose type
   the input's class conforms to wins (today staticConforms answers false
   for every class input).
3. The arm body is instance equality — and that arm EXISTS: `==` on
   instances is InstanceEquality (F13c/D91), keyed by `<<equality.Key>>`
   and executed in SQL over canonical renders; every datasource and auth
   class here carries equality keys (StaticDatasourceSpecification
   host/port/databaseName, LocalH2DatasourceSpecification, ApiToken
   apiToken), and `[0..1] == [0..1]` already lowers null-safe
   (NullSemantics:136). What is MISSING is reflection:
   `compareObjectsWithPossiblyNoProperties` exists in the engine exactly
   because DefaultH2AuthenticationStrategy has NO properties (keyless
   instances are never `==`), and it reads
   `type()->hierarchicalProperties()->size()`. The system store today has
   ONE relation (relational_elements: expression nodes + per-set property
   lines) — no class-properties relation — so hierarchicalProperties is a
   NEW store fact (class property rows), per the metamodel-as-relations
   ruling. `postProcessorsMatch([],[])` folds by the literal unroll.
Ruling check: "no value-object folding" — the comparison executes in the
database, the compile-time work is only turning the program into literal
arms. Verdict: assert over a boolean row. Size: medium-large (arm
folding + class dispatch + the property-rows fact). Deletes
ConnEquality.java.

### C. testBusinessDateInjectionFromVarReference (1 test)

Body: two `execute(...)` results, then
`[$result, $result2]->map(r | let orders = $r.values; assertEquals(1,
$orders->size()); assertEquals([2], $orders.id););` and two
assertSameSQL goldens.

Wall (LambdaBodies.java:60): a multi-statement lambda body is accepted only
as lets + `assert(...)`/`fail` guards + a value; `assertEquals` is not an
accepted guard and the body has no trailing value.

Plan: (1) LambdaBodies treats every asserts-family call as a guard spelled
by its boolean (`assertEquals(a,b)` ≡ `assert(a == b)`), and a trailing
assert IS the lambda's value; (2) `->map` over a LITERAL collection of
execute-result lets unrolls per element, so each element's asserts become
the existing row verdicts (count = 1, ids = [2]) over that frame. The
assertSameSQL asserts already have their arms.
Audit: LiteralUnroll has NO map arm today (grep), and how a collection of
two execute frames `[$result, $result2]` types is unverified — both
mechanisms are new. Size: small-medium, not small.

### D. testDataGenerationWithBusinessDateMilestoning_WithMilestoningDates (1 test)

Body: the same generation the passing sibling
testDataGenerationWithBusinessDateMilestoning does (let-bound query and
mapping, `generateTestData(... $milestoningDates, ...)`, assertTestData),
then `loadAndTestExecution($query, [], $mapping, ...)`, `initDatabase()`,
and a SECOND generateTestData round with `milestoningDates2`.

Wall, re-probed with a temporary print (reverted): the second call sees
`$query` and `$mapping` as bare Variables (n=8 args, a0=Variable,
a1=Variable) and the alias map carries the inlined helper's own lets
(parametersValues, data, testConnection, setUpSQLs, plan, result). The
inlined `loadAndTestExecution` helper's parameter aliases clobber the
caller's `query`/`mapping` aliases and are never popped. The first round
(identical to the sibling) types fine.

Plan: scope an inlined helper's aliases to its statements (push/pop) so
the caller's bindings survive the call. Verdict: the existing
assertTestData row compare (both rounds). Size: small; it is a typer bug,
not a TDG feature.

### E. testTableToTdsWithConcatenate (lineage, 1 test)

Note: two engine tests share this simple name; the walled one is
meta::pure::lineage::scanRelations::test (scanRelationsTests.pure:870),
not the testDataGeneration one.

Body: `tableToTDS(personTable)->project(col(FIRSTNAME as 'firstName'))
->concatenate(Person.all()->project(col(lastName as 'lastName')))`,
then `scanRelations($query, $mapping, $runtime, ext)` and assertEquals on
the tree text. The query is never executed.

Engine: the in-memory TDS concatenate (tds.pure:483-487) asserts equal
column names at RUNTIME; the relational lowering (pureToSQLQuery.pure:2709
processConcatenate) unions the arms positionally with column alignment.
The lineage scan analyzes the tree only.

Wall: our typer's relation concatenate requires equal column sets ("column
mismatch: type variable T bound to relation [firstName]"); the lineage
analyzer (ScanRelations, already on the platform; LineageRelationsForm
routes to it) never gets the typed query.

Plan (rule choice, surfaced): type relation concatenate POSITIONALLY like
the relational lowering (same arity, compatible column types, the first
operand's names). This follows the lane-seam precedent (the relational
lane carries the behavior we compile to); the in-memory name assert is
that implementation's own check. Verdict: LineageTreeVerdicts. Size:
small.
Audit: the wall is the GENERIC type-variable binding (InferenceKernel:596,
`concatenate<T>(T[*], T[*])` binds T to relation [firstName] and refuses
[lastName]); the change must be a concatenate/union-specific relation
rule, never a loosening of T-binding.

### F. testMilestonedRootAndMilestonedProperty ×2 (malformed goldens)

Two tests with this simple name (graphFetch::tests::embedded::otherwise
and graphFetch::tests::milestoning). Both goldens end in `]"` — a stray
quote after the JSON array. The engine passes because
`assertJsonStringsEqual` → `equalJsonStrings` → json-simple's JSONParser,
which returns after the first complete value (probed on the 1.1.1 jar:
`[{"id":2}]"` parses; `[...] junk` throws). Our Json.parse is strict and
fails at the GOLDEN, after the query executed (probe stack:
AssertVerdicts.adjudicate → Json.parse, "trailing JSON at 191").

Plan: ENGINE_GOLDEN_DEFECTS entry `malformed-json-golden` keyed by both
FQNs, applied only when the golden literal itself fails to parse (ours
parsed). Same standing as joinStrings / h2-week-start. Size: tiny. The
walk's tolerance goes with the walk.

## 2. The 17 that fail in both channels (not walk-only; listed for the record)

Researched to the same depth; each is a named leg or a decision.

- addDriverTablePkForProject — engine SQL-IR introspection (routeFunction →
  toSQLQuery → TdsSelectSqlQuery.paths/columns). decision:routeFunction,
  with the other 4 routeFunction tests; the engine's IR objects are not
  ours.
- relationalResultSourcingOfListExecutionPlan — executionPlan over a
  TWO-statement lambda with `^Runtime(connectionStores=[getConnection()])`
  where getConnection() runs createTablesAndFillDb() (an effect). Our
  executor classes the `result` let as an executeInDb result binding
  (containsEffect reaches the effect through the runtime argument;
  helperValueLet handles only a user-call rhs). Leg: run the effectful
  argument's effects, bind the value; verdict = batch 67's chained-plan
  structure row (Sequence(Allocation, Relational)).
- inheritance — `RoadVehicle : Operation { inheritance(...) }`:
  router_operations.pure:19-45 = union over the class's mapped leaf
  subtypes (Car→map1, Bicycle→map2, u_type discriminator). Audit: the
  parser AND the model already carry it (MappingProtocolParser:1570 →
  ClassMapping.Inheritance, MappingFromProtocol:330 "members are
  IMPLICIT"); only the resolver's leaf-type expansion
  (getMappedLeafTypes, router_operations.pure:39-51) is missing. Leg: that
  expansion onto the existing union ladder; verdict = union golden SQL
  replay.
- tdsTwoJoinThreeDB — one TDS join across THREE databases; engine plan
  moves temp tables (tdsvar_0_0, tdsvar_0) between connections. Only test
  of its kind (the two-database siblings pass). Leg: cross-connection
  relation transfer as a multi-statement plan. Large for one test.
- withPlatform — `.lastName->makeString(', ')`: engine plan = PureExp
  (in-memory reduction) over Relational; we push STRING_AGG into SQL and
  the ENGINE_TEXT flavor has no list encoding. Leg: string_agg spelling
  for that flavor; verdict = golden inner SQL → rows → makeString vs our
  value (PlanText.PureExp arm exists).
- planGraphFetchWithDerivedProperty ×2 — graphFetch through a
  model-to-model chain: the engine runs the M2M layer IN MEMORY
  (StoreMappingGlobalGraphFetch + InMemoryRootGraphFetch) and the golden is
  that plan text; the project siblings pass because the engine folds M2M
  project into SQL. The assert is on the engine's execution strategy, not
  data — decision candidate, SURFACED.
- executeProjectWithNestedDerivedProperty — same family but a ROW test;
  the helper uses `tdsToJSONKeyValueObjectString` (toJSON.pure:231, a
  Pure program in core/external/format/json that the corpus model does not
  load). Leg: load and compile it (a TDS→JSON string). Small.
- simpleFunctionExpressionTranslationAdjust / Now — call the engine's
  internal translator (toSQLQuery on a bare expression, sqlQueryToString
  H2 → 'select dateadd(month, 1, now())'); the typer inlines the engine's
  own implementation and hits its feature-flag helper. Engine-internal
  program tests like routeFunction — decision candidate, SURFACED.
- resolveSchemaTest — `Address` is legend-pure's platform test model class
  (core/pure/corefunctions/tests/testModel.pure), not loaded; the test
  compares `$query->eval().columns` (in-memory run with NO mapping) to
  resolveSchema (a static-analysis Pure program). Two blocks; the
  in-memory extent side is a decision candidate, SURFACED.
- rowValueDifferenceTest — (i) `$tds.columns` types as String[*]; the
  engine's TabularDataSet.columns is TDSColumn[*] (name, type): make the
  result's columns a relation (metamodel-as-relations); (ii)
  rowValueDifference (tdsExtension.pure:22-95) is a Pure program over
  join/restrict/renameColumns/extendMatchColumns — compiles as a user
  function once (i) lands. Medium.
- iqrClassifyTest / zScoreTest — `range()->map()->zip()` then
  `->project([col(p|$p.first,'name'), ...])`: project over an IN-MEMORY
  collection (no `col` overload for a non-store receiver), then
  iqrClassify/zScore (tdsExtension.pure:150-200: groupBy percentile /
  average / stdDevPopulation, joinWithOptionalColumns, extend) as Pure
  programs. Leg: instance collection → VALUES relation; percentile →
  quantile_cont. Medium.
- dropAndCreateTempTable — createTempTable/dropTempTable natives (DDL text
  from a Pure lambda `createTempTableStatement()` per DatabaseType) plus
  reading `executeInDb(...).columnNames` — the same "reading an executeInDb
  result binding" wall. Leg: executeInDb result as a relation value
  (columnNames/rows) + the two DDL effects. Small-medium.
- isolationTest — the resolver leg named at batch 69 (a correlated filter
  predicate on hop 3 of employees.group.children.name).

## 3. Order and what each burns

1. F (register, tiny) → 2 rows leave the walk.
2. D (alias scoping, small) → 1; also hardens every inlined-helper test.
3. C (assert guards + literal map unroll, small) → 1.
4. E (positional concatenate typing — needs the user's yes on the rule) → 1.
5. A (objectReferenceIn, medium; task #20) → 8; deletes ObjectRefs.java.
6. B (extension arms + class dispatch + struct equality, medium-large) → 5;
   deletes ConnEquality.java and closes the "did not fold" family.

After 1-6 the walk-only set is empty. Then §2 in this order: dropAndCreate
+ resultSourcing (the executeInDb-result leg, 2), inheritance (union
ladder, 1), executeProjectWithNestedDerivedProperty (1), withPlatform (1),
rowValueDifference + iqr/zScore (in-memory relations, 3), isolationTest
(1), tdsTwoJoinThreeDB (1); decisions to surface: M2M graphFetch plan text
(2), engine-internal translator (2), resolveSchemaTest (1),
addDriverTablePkForProject (1, already decision:routeFunction).

## 4. Audit of this plan (2026-09-05, same session)

Method: every claim re-checked against a receipt — engine source line,
a probe run, or a jshell/H2 probe. Temporary prints were added to two
checkers for the probes and reverted (tree clean, only this doc untracked).

Confirmed as written:
- F: our serialized JSON is byte-identical to the golden up to the stray
  `]"` and one whitespace (probe printed both sides); json-simple accepts
  the stray quote (jar 1.1.1 probe). Register entry is the right fix.
- D: the second generateTestData sees `query`→Variable(query @1020:25) and
  `mapping`→Variable(mapping @1020:37) — line 1020 is the
  `loadAndTestExecution($query, [], $mapping, ...)` call: the inlined
  helper's parameter aliases (bound to the caller's argument variables)
  overwrote the caller's lets and were never popped. Cause verified.
- B: routerExtensions has a real Pure body (extension.pure:46-49);
  MatchFold.staticConforms answers false for every class input (verified);
  the "did not fold to []" family is exactly these 5 tests.
- resolveSchemaTest / executeProjectWithNestedDerivedProperty: the corpus
  loads core_relational, core/store/m2m/tests, pureToSQLQuery.pure and the
  dialect utils only (Corpus.java:48-76); `Address` is testModel.pure:192
  and tdsToJSONKeyValueObjectString is toJSON.pure:231 — neither loaded.

Corrected by the audit (already folded into §1/§2 above):
- B step 3 said "struct equality"; instance `==` already exists as the
  keyed InstanceEquality arm, and optional `==` is already null-safe. The
  real gap is reflection (`hierarchicalProperties`), which needs class
  property rows in the system store (today: one table,
  relational_elements).
- A: H2 has no base64 at all; the reference's connection segment is a
  constant in our prefix builder (pre-existing); the embedded-set variant
  needs the second set-id slot.
- C: LiteralUnroll has no map arm; two new mechanisms, size raised.
- E: the wall is generic T-binding; the rule must be concatenate-specific.
- inheritance: the model already has ClassMapping.Inheritance; only the
  resolver's leaf expansion is missing.

Left unverified (stated, not guessed):
- How the walk's JSON compare tolerates the stray quote (irrelevant once
  the walk goes).
- dropAndCreateTempTable's second wall (reading the executeInDb result) is
  inferred from the resultSourcing wall text; the typer wall
  (`createTempTable` unknown) is what the probe shows first.
- The typing of a literal collection of two execute frames (leg C).

## 5. Batch 72c design: connection equality by READING the engine's extension record (2026-09-05, for review before implementation)

### 5.1 The expression (testRelationalExtension.pure:28-36)

```
let extensions = meta::relational::extension::relationalExtensions().routerExtensions();
$c1->match($extensions.connectionEquality->map(e | $e->eval($c2))
           ->concatenate([a:Connection[1] | true])->toOneMany());
```

### 5.2 The chain, traced by hand (every program the compile-time reader meets)

| # | program | what it spells | read by this test |
|---|---|---|---|
| 1 | `relationalExtensions()` — core_relational extensions/extension.pure:62-65 | `relationalExtension()->concatenate(h2SqlDialectExtension())`. Registered in Pure.java as a BODILESS native (typing surface for the extension argument); the corpus loads the program as a setup file | whole |
| 2 | `relationalExtension()` — extension.pure:72-252 | `^Extension` with 14 fields: `type` (string); `availableStores = defaultExtensions().availableStores->concatenate(relationalStoreContract())`; `serializerExtension` (function reference); `grammarSerializerExtensions = relationGrammarExtension()` (call); three `executionPlan_execution_*` lambdas; `tdsToRelation = tdsToRelationExtension()` (call → a record with one closure field; its class `TdsToRelationExtension_V_X_X` was EXCLUDED from the prelude because it names the template protocol package — admitted today); `tdsSchema_resolveSchemaImpl`, `testExtension_testedBy` (lambdas); `validTestPackages` (string); `availableFeatures = ExecutionPlanFeatureFlagExtension().availableFeatures`; `moduleExtensions = [^RelationElementAccessorExtension(module = …Name(), instancePrimaryKeyResolver = lambda)]` | `availableStores`, `availableFeatures` only |
| 3 | `h2SqlDialectExtension()` — h2SqlDialect.pure:59-72 | `^Extension(type, moduleExtensions = [^SqlDialectTranslationModuleExtension(module = …Name(), extraSqlDialects = h2SqlDialect())])`; `h2SqlDialect()` (42-57) assembles the whole H2 dialect from nine helpers (node processors, function-processor map, keywords, …) | nothing: `availableStores`/`availableFeatures` are unspelled → declared `[*]` → empty |
| 4 | `defaultExtensions()` — core/pure/extensions/functions.pure:84-93 | `^Extension(type, availableStores = [modelStoreContract(), aggregationAwareStoreContract()])` | `availableStores` |
| 5 | `modelStoreContract()` — core/store/m2m/storeContract.pure:20-43 | `^StoreContract`, no lets; strings, function references, lambdas, booleans; NO `connectionEquality` | `connectionEquality` (absent → `[0..1]` default, router_extension.pure:28) |
| 6 | `aggregationAwareStoreContract()` — core/store/aggregationAware/storeContract.pure:26-39 | same shape; NO `connectionEquality` | same |
| 7 | `relationalStoreContract()` — core_relational contract/storeContract.pure:37-184 | `let defaultState = defaultState([], newMap(...))` (the QUERY PLANNER's state — its body calls `buildClassMappingsById`; never portable, never needed) then `^StoreContract` with ~25 fields; `$defaultState` is used ONLY inside the `supports` closure (line 62); `connectionEquality` (48-60) = `{b:Connection[1] \| [d:RelationalDatabaseConnection[1] \| let bAsRDB = $b->cast(…); let comparison = $d.type == $bAsRDB.type && … && compareObjectsWithPossiblyNoProperties(…) && postProcessorsMatch(…)]}` | `connectionEquality` |
| 8 | `ExecutionPlanFeatureFlagExtension()` — featureFlag.pure:64-89 | one trailing `let shared = ^Extension(type, availableFeatures = ^FeatureExtension(id, routeFunctionExpressions = [pair(lambda, lambda)]))` (a trailing let is the value) | `availableFeatures` → the FeatureExtension record → its `connectionEquality` absent → empty |
| 9 | `routerExtensions` — core extension.pure:46-49 (qualified property; ported this batch as a platform function in SystemMetamodel) | `$this.availableStores->concatenate($this.availableFeatures)->cast(@RouterExtension)`; StoreContract extends RouterExtension (storeContract.pure:9), FeatureExtension extends RouterExtension (extension.pure:140) | whole |
| 10 | `.connectionEquality` over `[modelSC, aggSC, relationalSC, featureExt]` | `[relational closure]` — three unspelled keys fold to their empty default (LiteralUnroll.collectionFieldWithDefaults, landed this batch) | — |
| 11 | `->map(e \| $e->eval($c2))`, concatenate, `$c1->match(…)` | applying the closure yields ONE typed arm; the arm list is now spelled → static dispatch on `$c1`'s class (UserCallInliner.spelledArms, landed); the comparison folds over the two spelled `^RelationalDatabaseConnection` literals; `compareObjectsWithPossiblyNoProperties` → `hierarchicalProperties` over the metamodel rows (SystemMetamodel `class_ancestry`, landed). This tail ran end to end under the (deleted) named-fold version: 5/5 flipped, corpus 163/2410 | — |

### 5.3 What today's three probes hit, in order

1. `Unknown type 'TdsToRelationExtension_V_X_X'` — field 2.`tdsToRelation`. Fixed: the prelude generator's protocol exclusion now admits the one TEMPLATE package (`meta::protocols::pure::vX_X_X::metamodel::m3`), +39 lines of generated shapes.
2. `unknown function 'executeInMemory'` via `modelStoreContract → execution` — the inliner compiled a CLOSURE field (`executeStoreQuery`) that nobody applies. Fixed: a lambda stored in a record field stands until applied (generalizes the existing postprocessor-config rule).
3. `unknown function 'buildClassMappingsById'` via `relationalStoreContract → defaultState` — an EAGER let whose only use is inside a standing closure.

Next in line if evaluation stays eager (read off the table, not probed): `grammarSerializerExtensions = relationGrammarExtension()`, `tdsToRelationExtension()`, `moduleExtensions`, and through the H2 record `h2SqlDialect()` with its nine helpers — none of it read by the test.

### 5.4 Diagnosis

The inliner evaluates every field of a record and every let of a body eagerly. The engine's extension record is forty fields wide; this test reads one. Eager evaluation makes the test depend on compiling the engine's whole plug-in surface at compile time. Every wall today is that one pattern, not five bugs.

### 5.5 The rule: records and lets evaluate BY NEED

- **R1 — record fields stand.** Rewriting `^Record(…)` substitutes variables into every field but inlines no call (the inliner's existing "config" mode, today used only for postprocessor config). Closure fields stand even when the record is forced: a closure's body compiles when applied.
- **R2 — a field read forces the field it names.** `$rec.f`, and `$recs.f` over a spelled collection (absent keys → declared defaults), yields the field's standing value rewritten in normal mode. Only that field.
- **R3 — lets are classified by their uses in the rest of the body.** No use → not evaluated (dead let). Every use inside a record field → substituted standing (R1 governs it from there). Any other use → eager, exactly as today.
- **R4 — the query boundary forces.** After the top-level rewrite, a record that survives is data for the lowering: its non-closure fields are forced whole, nested records included; query-level lets the resolver reads (queryLets) likewise. A tree with no standing call skips the pass (containment check), so tests without records never pay it.

Semantics: Pure is strict, but these programs are effect-free, so skipping fields no read reaches is dead-code elimination — result-preserving. What changes is WHEN evaluation happens and whether unread code is compiled at all.

Where it lives: UserCallInliner (record arm, property-access arm, reduceStatements, inlineBody) plus one shared `read(pa, ctx)` helper in LiteralUnroll. No Java names any program (WORLD_MAP rule 5). No new native. About 80 lines.

### 5.6 Interactions checked (the ramifications list)

- **Renaming and capture.** Standing fields are rewritten under the frame's env at standing time — variables already substituted, lambdas already renamed. Forcing runs with an empty env.
- **Recursion detection (callee stack).** Forcing happens at the READ site or the boundary, where the stack is the reader's. A standing call whose forced inlining re-enters a function already on the stack is flagged as recursion where eager evaluation would not have been. Judged rare (a field holding a call to the function that reads it); the corpus measures it.
- **Dynamic-arm match inside a standing closure.** The inliner leaves it in place; the "did not fold to []" throw is MatchFold's at lowering, which a standing closure reaches only if its record does — unchanged from today's config rule.
- **`reduceStatements`' non-let-intermediate wall** is not reached by standing lambda bodies (`lambda()` rewrites statements one by one).
- **The boundary pass** is one more rewrite over trees that still carry a user call; rewrite is idempotent on inlined trees (folds included).
- **Closures that reach the lowering** (already true since fix 2 above): a record with a lambda field now carries the lambda's user calls to the lowering instead of their inlined bodies. Which corpus tests build such records is unknown → the full corpus before any commit.
- **Guards**: CodeShapeGuardrail file limit 3500 (UserCallInliner 1371, LiteralUnroll 677 — room), string-dispatch pin 87, catch-returns-value pin 15 (no new catch), native catalog (walledKey +, routerExtensions −), NativeCatalogGovernance INTERNAL_DESUGAR 17, ArchitectureTest 6c'.

### 5.7 Not covered, by decision

Records built by code rather than spelled (`newMap`-built dictionaries of closures such as H2's processor maps — not read here); dispatch on a runtime value that is not a row; the planner state (`defaultState`) — never evaluated under R3.

### 5.8 Measurement, in order

1. `-Drcorpus.test=testConnectionEquality` → 5 flips expected.
2. Full corpus with the engine root → 163/2410 expected, ZERO regressions; a regression is analyzed, never pinned around.
3. Guards (5.6), native catalog regenerated, chain.
4. Pins move with the burn; GATES/charter/handoff/memory; commit named files (incl. `git rm` ConnEquality.java); push.

## 6. Parking note (2026-09-05, user decision)

Group B (the five connection-equality tests) is PARKED: the walk's Java
comparison (`harness/ConnEquality.java`) is deleted, the tests fail in both
channels with the loud wall "scalar match: the arm collection has a
non-literal prefix (extension-contributed arms) that did not fold to []", and
the compile-time mechanism of §5 lives on branch
`wip/72c-extension-registry-read`, not on main. The full research — the
chain trace, the by-need design, why the unread programs do not compile, and
the code-as-data leg that would run these five the right way — is
docs/CODE_AS_DATA_HOMEWORK_2026_09_05.md. Next: the both-channel set (§2,
now 22 with these five), burned to zero; then the code-as-data leg, sized
before it is started.
