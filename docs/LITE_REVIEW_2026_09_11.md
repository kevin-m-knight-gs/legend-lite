# Lite functions in Pure.java — the one-by-one review (2026-09-11, batch 5 audit, item 1)

Every native under `meta::legend::lite::` — 43 signatures, 34 names at the review (37 signatures, 31 names after leg A). Each is either (ENGINE) a name from the engine's relational grammar / dynaFn registry with no Pure signature, (IR) an intermediate form our own normalizer or resolver emits and only it can reach, or (SURFACE) a user-reachable lite-dialect feature. The census columns are mechanical (governance set, claim, referencing files); the verdict column is mine and marked VERIFY where I am not sure.

| name | set | origin | verdict |
|---|---|---|---|
| `adjustTemporal` | INTERNAL_DESUGAR | IR: milestoning date shift the resolver mints (TemporalFrame) and DateShifts lowers — no pure counterpart. | keep |
| `asOfJoinWithPrefix` | LITE_SURFACE | SURFACE: the prefix as-of join (USER decision, leg 5d): every right-side column renamed prefix+name. | keep |
| `asorDecodePkMap` | INTERNAL_DESUGAR | IR: store-object-reference pk-map decode (base64 + framing) minted by the resolver where the engine's decodeObjectReferencesAndGetPkMap reads a frame. | keep |
| `asorPkValue` | INTERNAL_DESUGAR | IR: store-object-reference decoder (base64 + framing) minted by the resolver where the engine's objectReferenceIn is a runtime value. | keep |
| `avg` | DELETED (leg A) | NOT ENGINE VOCABULARY (registry check, item 0): no `dynaFnToSql('avg')` in any of the 21 engine registries; the engine spells `avg` only as SQL OUTPUT for pure's `average` (`pair(Average, simpleFunctionProcessor('avg'))` in the dialect models). Our producers: MappingNormalizer's aggregate-name list and RelationalTypeInference's `"sum","average","avg"` arm. | done — the only writer was our own test (now `average`); the engine would have failed on `avg` (no rendering) |
| `castAsDeclared` | INTERNAL_DESUGAR | IR: the mapping property's DECLARED-type coercion (DeclaredCoercions) — a typing shim. | keep; verify each site still needs a coercion rather than the kernel's own conformance |
| `convertDateFormat` | ENGINE_VOCAB_SHIMS | ENGINE: the `convertDate` dynaFn's format shape; the translator's arm lands here (`DynaFnArms.LANDINGS`). | moved (leg A) |
| `convertDateTimeFormat` | ENGINE_VOCAB_SHIMS | ENGINE: the `convertDateTime`/`toTimestamp` dynaFns' format shape; arm landing. | moved (leg A) |
| `convertTimeZoneFormat` | ENGINE_VOCAB_SHIMS | ENGINE: the `convertTimeZone` dynaFn's shape; arm landing. | moved (leg A) |
| `divideRound` | ENGINE_VOCAB_SHIMS | ENGINE: engine dynaFn. | keep |
| `greaterThan` | ENGINE_VOCAB_SHIMS | ENGINE: Any-typed ordering shim for the engine's untyped dynaFn literals (protocol `Literal` carries `Object value`, no type). | keep — receipt verified |
| `greaterThanEqual` | ENGINE_VOCAB_SHIMS | ENGINE: Any-typed ordering shim for the engine's untyped dynaFn literals (protocol `Literal` carries `Object value`, no type). | keep — receipt verified |
| `groupByComputedKeys` | INTERNAL_DESUGAR | IR: mapping/view ~groupBy over table rows by key expressions (leg 5d). | keep |
| `groupByOverInstances` | INTERNAL_DESUGAR | IR: the legacy tds::groupBy(K[*], …) desugar landing (leg 5d). | keep |
| `hash` | DELETED (leg A) | NOT ENGINE VOCABULARY (registry check, item 0): no `dynaFnToSql('hash')` anywhere. The engine's hashing dynaFns are `md5`/`sha1`/`sha256`, which the translator rewrites to UPSTREAM's `meta::pure::functions::hash::hash(String[1], HashType[1])` (carried byte-identical, `HASH__STRING_1__HASH_TYPE_1`). Our 1-arg `lite::hash(String[1])` has NO producer (translator, checkers, corpus: none) — only a lowering family registration (`Scalars`: `SqlFn.HASH`) and the shim set. | done — deleted with the Scalars family row |
| `isDistinct` | ENGINE_VOCAB_SHIMS | ENGINE: engine dynaFn isDistinct(a, b) — IS DISTINCT FROM (leg 5c). | keep |
| `isNumeric` | ENGINE_VOCAB_SHIMS | ENGINE: engine dynaFn. | keep |
| `join` → `joinSlot` | INTERNAL_DESUGAR | IR: the pipeline SLOT join the normalizer emits (JoinChecker: "lite-INTERNAL vocabulary, exists ONLY under its exact spelling"). It shared upstream's bare name, which the internal-desugar rule (bare internal names are refused) cannot hold for a name users write — renamed `lite::joinSlot` (`Pure.Lite.JOIN_SLOT`). | moved + renamed (leg A) |
| `joinWithPrefix` | LITE_SURFACE | SURFACE: the prefix join (USER decision, leg 5d). | keep |
| `legacyAssocPredicate` | INTERNAL_DESUGAR | IR: legacy mapping association predicate. | keep; verify whether the resolver could read the association directly |
| `legacyLocalProperty` | INTERNAL_DESUGAR | IR: legacy +local property spelling. | keep |
| `legacyNavigate` | INTERNAL_DESUGAR | IR: legacy mapping navigation (join chains) — the normalizer's main navigation IR. | keep |
| `lessThan` | ENGINE_VOCAB_SHIMS | ENGINE: Any-typed ordering shim: the receipt says engine DynaFunc ordering comparisons carry UNTYPED operands (RelationalParseTreeWalker Literal). | keep — receipt VERIFIED: the engine's relational protocol `Literal` carries `Object value` and no type |
| `lessThanEqual` | ENGINE_VOCAB_SHIMS | ENGINE: Any-typed ordering shim for the engine's untyped dynaFn literals (protocol `Literal` carries `Object value`, no type). | keep — receipt verified |
| `navigate` | LITE_SURFACE | SURFACE: the relation-navigation extension users write in query text; VERIFIED — the normalizer's three mentions are comments, zero internal emitters. | keep |
| `notEqualAnsi` | ENGINE_VOCAB_SHIMS | ENGINE: engine dynaFn. | keep |
| `otherwise` | INTERNAL_DESUGAR | IR: the mapping `otherwise` embedded-mapping form. | keep |
| `parseDateFormat` | ENGINE_VOCAB_SHIMS | ENGINE: the `parseDate` dynaFn's 2-arg format shape; arm landing (pure's 1-arg parseDate passes through). | moved (leg A) |
| `sourceUrl` | LITE_SURFACE | SURFACE: JSON source URL of a lite runtime (SourceUrlChecker). | keep |
| `sub` | DELETED (leg A) | The engine's `sub` renders `%s-%s` (2-ary, extensionDefaults.pure); the translator's arm rewrites every 2-arg `sub` into the minus run, so the four numeric-pair overloads had NO producer; any other arity is an error, as it is in the engine. Registry: TRANSLATED. | done |
| `tds` | INTERNAL_DESUGAR | IR: the #TDS literal carrier the parser produces. | keep |
| `trustOne` | INTERNAL_DESUGAR | IR: the SQL-lane to-one trust wrapper (typing-level; erased at lowering). | keep — and it is now also StoreLane's mark |
| `typeAsDeclared` | INTERNAL_DESUGAR | IR: the mapping property's DECLARED-type coercion at the type level (DeclaredCoercions). | keep; same question as castAsDeclared |
| `unionScan` | INTERNAL_DESUGAR | IR: union mapping scan. | keep |

## Findings to act on

Findings 1–6 LANDED as batch 5 audit leg A (2026-09-11); see the GATES.md record.

1. ~~`join` (the slot form) filed under ENGINE_VOCAB_SHIMS~~ — moved to INTERNAL_DESUGAR and renamed `joinSlot` (a bare internal name must not be a name users write).
2. ~~the four `*Format` date shims filed under INTERNAL_DESUGAR~~ — moved to ENGINE_VOCAB_SHIMS; they are the translator's declared landings (`DynaFnArms.LANDINGS`).
3. ~~`sub` four overloads~~ — deleted; the arm covers the engine's only shape.
4. ~~`hash` (1-arg)~~ — deleted with its lowering family row.
5. ~~`avg`~~ — deleted; the only writer was our own test, now `average`. The hand list of aggregate names (`AGGREGATE_FNS`, which also carried `stdDev`, no dynafunction either) is replaced by a derivation: a one-argument call of a PURE-resolved dynafunction whose catalog native consumes a collection.
6. ~~shim set described twice~~ — `Pure.ENGINE_VOCAB_SHIMS` is now held equal to the registry's SHIM rows plus the landings; the bare-name respelling table `wireEmissionName` is deleted (an unregistered name is a plain pure call under its own name).
7. OPEN — `castAsDeclared` / `typeAsDeclared` / `trustOne`: verify each producer site still needs a coercion the kernel's own conformance would not give (a measurement leg: count sites, remove one, watch the rosters).
8. OPEN — `legacyAssocPredicate`: could the resolver read the association directly?
9. OPEN (leg C) — `RelationalTypeInference` mirrors the engine's `getDynaFunctionTypeInferenceMap` by hand, with a lowercase string switch (the engine's lookup is case-sensitive); it should switch on `DynaFn` and its name set be held ⊆ the `Inference.MAPPED` members.
10. OPEN (leg B) — the 156 PURE-resolved dynafunctions pass through to pure's same-name function; the registry test checks that the name EXISTS in the catalog, not that the engine's SQL rendering and pure's semantics agree (`reverse` — SQL string reverse vs pure's collection reverse — was caught by hand and marked UNSUPPORTED; the other 155 want the same reading).

Verified during the review (no action): the four Any-typed ordering shims — the engine's relational protocol `Literal` class carries `Object value` with no type, so dynaFn comparison operands really are untyped; `navigate` — zero internal emitters, a genuine lite-surface feature.

## Census (mechanical)


## adjustTemporal  [INTERNAL_DESUGAR]
- constant: `ADJUST_TEMPORAL__DATE_1__INTEGER_1__DURATION_UNIT_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/DateShifts.java (2), resolver/TemporalFrame.java (2)
- `native function meta::legend::lite::adjustTemporal(d:meta::pure::metamodel::type::Date[1], amount:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Date[1];`

## asOfJoinWithPrefix  [LITE_SURFACE]
- constant: `AS_OF_JOIN_WITH_PREFIX__RELATION_1__RELATION_1__FUNCTION_1__FUNCTION_1__STRING_1`
- claim: CORE_FN by CoreFn.AS_OF_JOIN
- referenced by: compiler/spec/AsOfJoinChecker.java (2), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::asOfJoinWithPrefix<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], match:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], join:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], prefix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<T+V>[1];`

## asorDecodePkMap  [INTERNAL_DESUGAR]
- constant: `ASOR_DECODE_PK_MAP__STRING_1__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/AsorReaders.java (1), resolver/ObjectReferenceDecode.java (1)
- `native function meta::legend::lite::asorDecodePkMap(ref:meta::pure::metamodel::type::String[1], pkNames:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];`

## asorPkValue  [INTERNAL_DESUGAR]
- constant: `ASOR_PK_VALUE__STRING_1__INTEGER_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/AsorReaders.java (1), resolver/ObjectReferenceArms.java (1)
- `native function meta::legend::lite::asorPkValue(ref:meta::pure::metamodel::type::String[1], index:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Any[1];`

## avg  [ENGINE_VOCAB_SHIMS]
- constant: `AVG__NUMBER_MANY`
- claim: REDUCER by Aggregates.REDUCERS
- referenced by: lowering/Aggregates.java (1), normalizer/MappingNormalizer.java (1), compiler/element/RelationalTypeInference.java (1)
- `native function meta::legend::lite::avg(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Float[1];`

## castAsDeclared  [INTERNAL_DESUGAR]
- constant: `CAST_AS_DECLARED__ANY_01__T_1`
- claim: CORE_FN by CoreFn.CAST_AS_DECLARED
- referenced by: normalizer/DeclaredCoercions.java (3), compiler/spec/CoreFn.java (1), compiler/spec/Typer.java (1)
- `native function meta::legend::lite::castAsDeclared<T>(value:meta::pure::metamodel::type::Any[0..1], type:T[1]):T[0..1];`

## convertDateFormat  [INTERNAL_DESUGAR]
- constant: `CONVERT_DATE_FORMAT__STRING_0_1__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1)
- `native function meta::legend::lite::convertDateFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::StrictDate[0..1];`

## convertDateTimeFormat  [INTERNAL_DESUGAR]
- constant: `CONVERT_DATE_TIME_FORMAT__STRING_0_1__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1)
- `native function meta::legend::lite::convertDateTimeFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::DateTime[0..1];`

## convertTimeZoneFormat  [INTERNAL_DESUGAR]
- constant: `CONVERT_TIME_ZONE_FORMAT__DATE_0_1__STRING_1__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1)
- `native function meta::legend::lite::convertTimeZoneFormat(d:meta::pure::metamodel::type::DateTime[0..1], tz:meta::pure::metamodel::type::String[1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[0..1];`

## divideRound  [ENGINE_VOCAB_SHIMS]
- constant: `DIVIDE_ROUND__NUMBER_1__NUMBER_1__INTEGER_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1)
- `native function meta::legend::lite::divideRound(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Float[1];`

## greaterThan  [ENGINE_VOCAB_SHIMS]
- constant: `GREATER_THAN_ANY__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lineage/ScanRelations.java (3), resolver/TemporalFrame.java (3), compiler/spec/LiteralUnroll.java (2), lowering/Scalars.java (1), parser/OperatorParts.java (1), parser/DatabaseProtocolParser.java (1)
- `native function meta::legend::lite::greaterThan(left:meta::pure::metamodel::type::Any[0..1], right:meta::pure::metamodel::type::Any[0..1]):meta::pure::metamodel::type::Boolean[1];`

## greaterThanEqual  [ENGINE_VOCAB_SHIMS]
- constant: `GREATER_THAN_EQUAL_ANY__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lineage/ScanRelations.java (3), resolver/TemporalFrame.java (3), compiler/spec/LiteralUnroll.java (2), lowering/Scalars.java (1), parser/OperatorParts.java (1), parser/DatabaseProtocolParser.java (1)
- `native function meta::legend::lite::greaterThanEqual(left:meta::pure::metamodel::type::Any[0..1], right:meta::pure::metamodel::type::Any[0..1]):meta::pure::metamodel::type::Boolean[1];`

## groupByComputedKeys  [INTERNAL_DESUGAR]
- constant: `GROUP_BY_COMPUTED_KEYS__RELATION_1__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_1`
- claim: CORE_FN by CoreFn.GROUP_BY
- referenced by: normalizer/GroupBySynthesis.java (3), normalizer/ViewRelation.java (2), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::groupByComputedKeys<T,Z,K,V,R>(rel:meta::pure::metamodel::relation::Relation<T>[1], keys:meta::pure::metamodel::relation::FuncColSpecArray<{T[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];`

## groupByComputedKeys  [INTERNAL_DESUGAR]
- constant: `GROUP_BY_COMPUTED_KEYS__RELATION_1__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_ARRAY_1`
- claim: CORE_FN by CoreFn.GROUP_BY
- referenced by: normalizer/GroupBySynthesis.java (3), normalizer/ViewRelation.java (2), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::groupByComputedKeys<T,Z,K,V,R>(rel:meta::pure::metamodel::relation::Relation<T>[1], keys:meta::pure::metamodel::relation::FuncColSpecArray<{T[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];`

## groupByOverInstances  [INTERNAL_DESUGAR]
- constant: `GROUP_BY_OVER_INSTANCES__C_MANY__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_1`
- claim: CORE_FN by CoreFn.GROUP_BY
- referenced by: compiler/spec/CoreFn.java (1), compiler/spec/GroupByChecker.java (1)
- `native function meta::legend::lite::groupByOverInstances<C,Z,K,V,R>(cl:C[*], keys:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpec<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];`

## groupByOverInstances  [INTERNAL_DESUGAR]
- constant: `GROUP_BY_OVER_INSTANCES__C_MANY__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_ARRAY_1`
- claim: CORE_FN by CoreFn.GROUP_BY
- referenced by: compiler/spec/CoreFn.java (1), compiler/spec/GroupByChecker.java (1)
- `native function meta::legend::lite::groupByOverInstances<C,Z,K,V,R>(cl:C[*], keys:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpecArray<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];`

## hash  [ENGINE_VOCAB_SHIMS]
- constant: `HASH__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (2), normalizer/RelOpTranslator.java (1), sql/dialect/DuckDb.java (1)
- `native function meta::legend::lite::hash(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];`

## isDistinct  [ENGINE_VOCAB_SHIMS]
- constant: `IS_DISTINCT__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (2), lowering/Aggregates.java (1)
- `native function meta::legend::lite::isDistinct(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];`

## isNumeric  [ENGINE_VOCAB_SHIMS]
- constant: `IS_NUMERIC__STRING_0_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: normalizer/RelOpTranslator.java (2), lowering/Scalars.java (1)
- `native function meta::legend::lite::isNumeric(str:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[0..1];`

## join  [ENGINE_VOCAB_SHIMS]
- constant: `JOIN__RELATION_1__FUNC_COL_SPEC_1__FUNCTION_1`
- claim: CORE_FN by CoreFn.JOIN
- referenced by: lineage/ScanRelations.java (7), compiler/spec/JoinChecker.java (4), sql/dialect/Lexicon.java (3), lineage/PkInference.java (1), normalizer/JoinChainEmission.java (1), normalizer/ModelJoinNesting.java (1)
- `native function meta::legend::lite::join<S,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], slot:meta::pure::metamodel::relation::FuncColSpec<{->meta::pure::metamodel::relation::Relation<T>[1]},Z>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];`

## joinWithPrefix  [LITE_SURFACE]
- constant: `JOIN_WITH_PREFIX__RELATION_1__RELATION_1__JOIN_KIND_1__FUNCTION_1__STRING_1`
- claim: CORE_FN by CoreFn.JOIN
- referenced by: compiler/spec/JoinChecker.java (2), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::joinWithPrefix<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], joinKind:meta::pure::functions::relation::JoinKind[1], f:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], prefix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<T+V>[1];`

## legacyAssocPredicate  [INTERNAL_DESUGAR]
- constant: `LEGACY_ASSOC_PREDICATE__A_1__B_1__RELATION_1__RELATION_1__FUNCTION_1`
- claim: FAMILY by NativeFn.LiteDesugar
- referenced by: normalizer/MappingNormalizer.java (2), normalizer/XStorePureEnds.java (1), normalizer/AssociationSynthesis.java (1)
- `native function meta::legend::lite::legacyAssocPredicate<A,B,S,T>(a:A[1], b:B[1], src:meta::pure::metamodel::relation::Relation<S>[1], tgt:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];`

## legacyAssocPredicate  [INTERNAL_DESUGAR]
- constant: `LEGACY_ASSOC_PREDICATE__A_1__B_1__STRING_1__STRING_1__FUNCTION_1`
- claim: FAMILY by NativeFn.LiteDesugar
- referenced by: normalizer/MappingNormalizer.java (2), normalizer/XStorePureEnds.java (1), normalizer/AssociationSynthesis.java (1)
- `native function meta::legend::lite::legacyAssocPredicate<A,B>(a:A[1], b:B[1], srcSet:meta::pure::metamodel::type::String[1], tgtSet:meta::pure::metamodel::type::String[1], cond:meta::pure::metamodel::function::Function<{A[1],B[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];`

## legacyLocalProperty  [INTERNAL_DESUGAR]
- constant: `LEGACY_LOCAL_PROPERTY__ANY_1__STRING_1`
- claim: FAMILY by NativeFn.LiteDesugar
- referenced by: normalizer/XStorePureEnds.java (1), normalizer/MappingNormalizer.java (1)
- `native function meta::legend::lite::legacyLocalProperty(row:meta::pure::metamodel::type::Any[1], prop:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Any[1];`

## legacyNavigate  [INTERNAL_DESUGAR]
- constant: `LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1`
- claim: CORE_FN by CoreFn.LEGACY_NAVIGATE
- referenced by: normalizer/UnionSynthesis.java (2), normalizer/JoinChainEmission.java (1), normalizer/MappingNormalizer.java (1), normalizer/GroupBySynthesis.java (1), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::legacyNavigate<S,C,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->C[*]},Z>[1], tgtRows:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];`

## legacyNavigate  [INTERNAL_DESUGAR]
- constant: `LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1__FUNCTION_1`
- claim: CORE_FN by CoreFn.LEGACY_NAVIGATE
- referenced by: normalizer/UnionSynthesis.java (2), normalizer/JoinChainEmission.java (1), normalizer/MappingNormalizer.java (1), normalizer/GroupBySynthesis.java (1), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::legacyNavigate<S,C,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->C[*]},Z>[1], tgtRows:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1], pairedCond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];`

## lessThan  [ENGINE_VOCAB_SHIMS]
- constant: `LESS_THAN_ANY__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lineage/ScanRelations.java (3), compiler/spec/LiteralUnroll.java (2), lowering/Scalars.java (1), parser/OperatorParts.java (1), parser/DatabaseProtocolParser.java (1), parser/SpecParser.java (1)
- `native function meta::legend::lite::lessThan(left:meta::pure::metamodel::type::Any[0..1], right:meta::pure::metamodel::type::Any[0..1]):meta::pure::metamodel::type::Boolean[1];`

## lessThanEqual  [ENGINE_VOCAB_SHIMS]
- constant: `LESS_THAN_EQUAL_ANY__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lineage/ScanRelations.java (3), resolver/TemporalFrame.java (3), lowering/Scalars.java (1), parser/OperatorParts.java (1), parser/DatabaseProtocolParser.java (1), parser/SpecParser.java (1)
- `native function meta::legend::lite::lessThanEqual(left:meta::pure::metamodel::type::Any[0..1], right:meta::pure::metamodel::type::Any[0..1]):meta::pure::metamodel::type::Boolean[1];`

## navigate  [LITE_SURFACE]
- constant: `NAVIGATE__C_MANY__FUNC_COL_SPEC_1__FUNCTION_1`
- claim: CORE_FN by CoreFn.NAVIGATE
- referenced by: normalizer/MappingNormalizer.java (1), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::navigate<C,T,Z>(cl:C[*], target:meta::pure::metamodel::relation::FuncColSpec<{->T[*]},Z>[1], pred:meta::pure::metamodel::function::Function<{C[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):C[*];`

## navigate  [LITE_SURFACE]
- constant: `NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__FUNCTION_1`
- claim: CORE_FN by CoreFn.NAVIGATE
- referenced by: normalizer/MappingNormalizer.java (1), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::navigate<S,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->T[*]},Z>[1], pred:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];`

## navigate  [LITE_SURFACE]
- constant: `NAVIGATE__T_MANY__FUNCTION_1`
- claim: CORE_FN by CoreFn.NAVIGATE
- referenced by: normalizer/MappingNormalizer.java (1), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::navigate<T>(target:T[*], pred:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):T[*];`

## notEqualAnsi  [ENGINE_VOCAB_SHIMS]
- constant: `NOT_EQUAL_ANSI__ANY_1__ANY_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), parser/DatabaseProtocolParser.java (1), model/RelOpFromProtocol.java (1)
- `native function meta::legend::lite::notEqualAnsi(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];`

## otherwise  [INTERNAL_DESUGAR]
- constant: `OTHERWISE__T_1__T_0_1`
- claim: FAMILY by NativeFn.LiteDesugar
- referenced by: normalizer/MappingNormalizer.java (2), resolver/Substitution.java (1)
- `native function meta::legend::lite::otherwise<T>(partial:T[1], fallback:T[0..1]):T[1];`

## parseDateFormat  [INTERNAL_DESUGAR]
- constant: `PARSE_DATE_FORMAT__STRING_0_1__STRING_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1)
- `native function meta::legend::lite::parseDateFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::DateTime[0..1];`

## sourceUrl  [LITE_SURFACE]
- constant: `SOURCE_URL__STRING_1`
- claim: CORE_FN by CoreFn.SOURCE_URL
- referenced by: normalizer/MappingNormalizer.java (1), compiler/spec/CoreFn.java (1), compiler/spec/SourceUrlChecker.java (1)
- `native function meta::legend::lite::sourceUrl(url:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];`

## sub  [ENGINE_VOCAB_SHIMS]
- constant: `SUB__DECIMAL_1__DECIMAL_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1), compiler/element/RelationalTypeInference.java (1)
- `native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Decimal[1], right:meta::pure::metamodel::type::Decimal[1]):meta::pure::metamodel::type::Decimal[1];`

## sub  [ENGINE_VOCAB_SHIMS]
- constant: `SUB__FLOAT_1__FLOAT_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1), compiler/element/RelationalTypeInference.java (1)
- `native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];`

## sub  [ENGINE_VOCAB_SHIMS]
- constant: `SUB__INTEGER_1__INTEGER_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1), compiler/element/RelationalTypeInference.java (1)
- `native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];`

## sub  [ENGINE_VOCAB_SHIMS]
- constant: `SUB__NUMBER_1__NUMBER_1`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: lowering/Scalars.java (1), normalizer/RelOpTranslator.java (1), compiler/element/RelationalTypeInference.java (1)
- `native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];`

## tds  [INTERNAL_DESUGAR]
- constant: `TDS__STRING_1__STRING_1`
- claim: CORE_FN by CoreFn.TDS
- referenced by: lowering/Render.java (2), compiler/spec/CoreFn.java (1), compiler/spec/TdsChecker.java (1)
- `native function meta::legend::lite::tds(tag:meta::pure::metamodel::type::String[1], raw:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];`

## trustOne  [INTERNAL_DESUGAR]
- constant: `TRUST_ONE__T_MANY`
- claim: SCALAR_RULE by Scalars.RULES
- referenced by: compiler/spec/Typer.java (11), normalizer/UnionSynthesis.java (10), normalizer/RelOpTranslator.java (4), lowering/Coercions.java (2), lowering/Lowerer.java (1), normalizer/DeclaredCoercions.java (1)
- `native function meta::legend::lite::trustOne<T>(values:T[*]):T[1];`

## typeAsDeclared  [INTERNAL_DESUGAR]
- constant: `TYPE_AS_DECLARED__ANY_01__T_1`
- claim: CORE_FN|SCALAR_RULE by CoreFn.TYPE_AS_DECLARED|Scalars.RULES
- referenced by: normalizer/DeclaredCoercions.java (2), compiler/spec/CoreFn.java (1)
- `native function meta::legend::lite::typeAsDeclared<T>(value:meta::pure::metamodel::type::Any[0..1], type:T[1]):T[0..1];`

## unionScan  [INTERNAL_DESUGAR]
- constant: `UNION_SCAN__RELATION_1`
- claim: FAMILY by NativeFn.LiteDesugar
- referenced by: normalizer/UnionSynthesis.java (2), resolver/Pipelines.java (2), lowering/RelationPredicates.java (1)
- `native function meta::legend::lite::unionScan<T>(rel:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];`
