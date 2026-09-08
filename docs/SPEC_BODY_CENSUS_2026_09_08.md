# The typing census over legend-pure's platform packages (2026-09-08)

Status: MEASURED, first run. Produced by `core/src/test/java/com/legend/tools/SpecBodyCensusTest.java`
(report only, no pin yet): load the nine platform packages as a model (the spec's `native function`
declarations drop — the registry is their definition), type every Pure body once through the ordinary
compile entry (`SpecCompiler.compile`), one row per failure with its reason. Full rows in
`target/spec-body-census.txt` after a run. This is the typing WORK LIST of
`docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md` §6; it is meant to trend to zero.

## 1. The numbers

| | count |
|---|---|
| files loaded | 261 |
| load walls (8 parse, 1 model) | 9 |
| bodies typed OK | 481 |
| bodies FAILED | 643 |
| native declarations skipped (the registry defines them) | 148 |

Failures by reason class: kernel 470, unknown-function 74, overload 35, unknown-property 30, other 30,
unknown-type 6. By what failed: **605 are PCT TEST functions, 38 are definitions** (platform functions
and derived properties).

## 2. One cause for 605 of the 643 — the PCT harness shape

Every PCT test is declared `test<Z|y>(f:Function<{Function<{->Z[y]}>[1]->Z[y]}>[1])` and its body
calls `$f->eval(|<expr>)` several times with different result types:

```
type variable T bound to { -> Z[y]} cannot also bind { -> Integer[1]}   (76)
type variable T bound to { -> Z[y]} cannot also bind { -> String[1]}    (63)
... Boolean 55, Float 39, Date 20, Integer[*] 19, Number 14, String[*] 12, Decimal 8, DateTime 8, String[N..N] 8
```

Our kernel treats the ENCLOSING function's type and multiplicity parameters (`Z`, `y`) as rigid inside
the body, so the first `eval` binds `T` to `{->Z[y]}` and every concrete lambda after it conflicts.
Real Pure compiles these bodies: a type parameter of the enclosing function is OPEN inside the body and
binds per expression. Channel B never met this because it types each test with `$f` already bound to
the concrete harness adapter. ONE kernel rule, 605 witnesses, guarded on both sides by channel B (the
"eval-wrong-arg" family proves the rigid rule still protects a CALLEE's declared parameter types).
This is the first item of the work list.

## 3. The 38 definition failures, grouped

| group | rows | examples | what it is |
|---|---|---|---|
| m3 / mapping metamodel PROPERTIES our shapes lack | 17 | `Mapping.associationMappings`, `PropertyMapping.sourceSetImplementationId` / `targetSetImplementationId`, `TableAlias.relation`, `Class.properties`, `InlineEmbeddedSetImplementation.owner`; the relation ColSpec family (`ColSpec.name`, `FuncColSpec.name`, `AggColSpec.name`, `*Array.names/funcSpecs/aggSpecs`) | vocabulary: the generator's shapes for these classes omit properties the spec declares (some are on system-store-coupled hand shapes in `Pure.java`) |
| natives the spec has and we lack | 5 | `genericTypeClass`, `enumName` ×2, `propertyTrees` (a derived property referenced as a call), `PrimitiveType` (a bare metaclass reference) | vocabulary / a resolver form |
| overload ARITY of natives we register differently | 5 | `elementToPath/3`, `pathToElement/2`, `lenientPathToElement/2`, `lastIndexOf/3`, `elementToPath` structural ×4 (the `PostProcessor*Id` derived properties) | our signatures do not match the spec's overload set — row-19 material |
| special-form name collisions | 2 | `meta::relational::metamodel::join/2`, `meta::relational::metamodel::filter/2` bodies call `join`/`filter` on the RELATIONAL METAMODEL (SQL AST), which our `join`/`filter` special forms claim by bare name | the special form must not claim a call whose receiver is not a TDS/relation — the same routing rule as the receiver's own property |
| kernel / typer | 4 | `newTDSRelationAccessor` (`@T` cast to a type variable), `addAssociationMappingsIfRequired` (variant `get` overload), `enumerationMappingByName` (ambiguous tie: the spec's body vs our platform Pure of the same name) | typer gaps; the tie is a platform-Pure twin of a spec function — rule 7/8 material |

## 4. The 9 load walls — grammar the parser lacks

`Primitive P(x:Integer[1]) extends Integer` (precise primitives with parameters — two files),
`->toMultiplicity(@[1])` (a multiplicity literal), `5 RomanLength~Pes` / `newUnit(RomanLength~Pes, 5)`
(units), `@(x:String)` (a relation-type literal in cast position), `Class X(x:Integer[1])` (class type
variables), `^Instance …` at top level (the m3 graph bootstrap file — never parsed by design,
`tools/m3shape.py` is its receipt), and one MODEL wall: `functionType.pure` names `FunctionType` as a
type (an m3 metaclass we do not declare). Each is a parser or vocabulary row; none blocks the rest.

## 5. What the census says about the design

- The platform's own definitions type almost entirely: 481 OK plus 38 rows, of which 17 are missing
  properties and 5 are arity spellings — vocabulary, not architecture. The 24 derived properties typed
  except the four `PostProcessor*Id` (an `elementToPath` overload) and two graph-fetch ones.
- The only large item is a kernel rule, and it has 605 witnesses and a conformance suite on both sides.
- Nothing in the list is reflection: `evaluateAndDeactivate`, `eval`, `newMap` all TYPE. The permanent
  list lives at lowering (§6 of the design), exactly as predicted.

## 6. Work list, in order

1. Kernel: the enclosing function's type/multiplicity parameters are open inside its body (605 rows).
2. Generator/shapes: the 17 missing metamodel properties (ColSpec family, Mapping, PropertyMapping,
   TableAlias, Class.properties, InlineEmbeddedSetImplementation.owner).
3. Natives: spec-exact overload sets for `elementToPath`, `pathToElement`, `lenientPathToElement`,
   `lastIndexOf`; register `genericTypeClass`, `enumName` (rule 7: each with a lowering or a named row).
4. Routing: a special form claims a bare call only when the receiver is a TDS/relation.
5. Parser: multiplicity literals, relation-type literals, class type variables, precise primitives with
   parameters, units.
6. Then re-run; the list should be near zero, and the census becomes a pin.

## 7. After the kernel rule (same day)

Two kernel changes, both measured against channel B (unchanged: 314/13, 355, 137, 95, 204):

1. **A variable already bound to a function type, meeting another function type, UNIFIES structurally**
   instead of demanding equality (`InferenceKernel.bindOrCheckTypeVar`). The existing binding may
   carry the enclosing function's own type and multiplicity parameters (`{->Z[y]}`), and real Pure
   binds those per expression — `Z := Integer`, `y := 1` for THIS call. A genuinely different function
   type still fails inside `unify` (the eval-wrong-arg spec holds).
2. **Resolution follows variable chains** (`V := Z`, `Z := Integer`) with a cycle guard across the whole
   resolution (`T := G<W>`, `W := G<T>` stays as-is) — `InferenceKernel.resolve` / `resolveMult`. Two
   earlier attempts recursed on such cycles (StackOverflow in the census); the guard is a set of
   variables being resolved.

| | before | after |
|---|---|---|
| bodies typed OK | 481 | 950 |
| bodies FAILED | 643 | 174 |
| kernel-class failures | 470 | 1 |

Remaining 174 by class: unknown-function 74 (`subTypeOf` 12, `generalizations` 11, `evaluate` 11,
`genericTypeClass` 7, `stringToTDS` 5, `sourceInformation` 4, `canReactivateDynamically` 4,
`openVariableValues` 3, `enumName` 3, `elementPath` 3, …), overload 35, unknown-property 30 (§3),
unknown-type 6, other 30 (an `IndexOutOfBounds` in the typer ×7 — the `testCreateTempTable*` bodies,
a bug; `eval` on a Property value ×3; `unknown enumeration` ×7; bare `PrimitiveType` ×4).

## 8. Batch 149 — the work list burned from 174 to 36 (2026-09-08)

Run: 261 files, 8 load walls (the `FunctionType` metaclass now declared: 9 → 8), **1090 typed / 36 failed** (1126 bodies: the by-name PCT rule below drops 21 spec bodies whose native is the definition).
Every change was measured against channel B (unchanged) and both corpus lanes (no pass change);
two regressions were caught by the DuckDB lane mid-batch and fixed before landing (§8.3).

### 8.1 What landed, by the design's placement table

**Hand shapes made spec-exact** (`Pure.java`, the shapes the Java is coupled to — receipts are the m3/mapping lines):
`ColSpec.name`, `ColSpecArray.names`, `FuncColSpec.name/function`, `FuncColSpecArray.funcSpecs`, `AggColSpec.name/map/reduce`,
`AggColSpecArray.aggSpecs` (relation.pure:17–50, type parameters renamed to the spec's); `Mapping.associationMappings`;
`PropertyMapping.owner/targetSetImplementationId/sourceSetImplementationId`; `Package.children`; `Function.functionName`;
`Class.properties/propertiesFromAssociations/qualifiedProperties` and `Class extends Type, PackageableElement`;
`ConcreteFunctionDefinition extends FunctionDefinition, PackageableFunction`; `ModelElement extends AnnotatedElement`
(stereotypes/taggedValues on every element); `Property<U,V|m> extends AbstractProperty<{U[1]->V[m]}>` (a property VALUE is a
function value); `Enumeration<E> extends DataType, PackageableElement { values }`; new m3 shapes `DataType`, `PrimitiveType`,
`FunctionType`, `NativeFunction`.

**Spec natives registered** (signature spec-exact; reflection/effects have no SQL meaning and wall at the lowering — the §6
permanent list, to be pinned in §9.4): `evaluate`, `subTypeOf`, `generalizations`, `genericTypeClass`, `sourceInformation`,
`canReactivateDynamically`, `openVariableValues`, `elementPath`, `enumName`, `elementToPath/3`, `pathToElement/2`,
`lenientPathToElement/2`, `lastIndexOf/3`, `stringToTDS`, `dynamicNew` ×4 (getter-override overloads), `assertError(f, matcher)`,
`dropTempTable`, `loadValuesToDbTable` ×2. Collection arithmetic is the spec's overload set (`minus/plus/times` over
`Decimal[*]`/`Float[*]`/`Number[*]`, plus the runtime's `Integer[*]` — its engine id is referenced by the spec's own tests)
replacing the platform's `<T>(values:T[*])` spelling; `isEmpty(p:Any[*])` spec-exact.

**Typer / kernel rules** (each a rule real pure has, none a per-name arm):
1. The enclosing function's type parameters are a FRAME (`Typer.inFunctionScope`): `cast(@T)` inside a generic body is the type
   variable; in the kernel they are RIGID — a call whose bindings never bind them resolves them to themselves (`contains<Z>`).
2. A class named as a value is `Class<ThatClass>`, so `Class<T>`'s properties bind `T` (`LA_Person.properties : Property<LA_Person,Any|*>[*]`).
3. Supertype INSTANTIATION (`InferenceKernel.asSuper`): `TypedClass` carries its structured supertypes over its own parameters;
   an m3 `Function` subclass value (a `Property`) unwraps to its function type when a formal is structural — eval / map of a property value.
4. `Nil` is the bottom of the join (`if(…, |[], |…)`); a parameterized actual scores against a class formal by its raw class;
   ties between UNRELATED class formals resolve by the argument's linearized supertype order (real pure's generalization order —
   `elementToPath(Type)` over `(PackageableElement)` for a `Class` value), same-shape module twins of a native standing aside.
5. [WITHDRAWN before landing — §8.3] a `match` no branch accepts statically as the runtime form.
6. A `<<PCT.function>>` with a same-NAME native is suppressed — the native is the definition (chB-std's 204 depend on it: a signature-exact
   rule let the spec's `average`/`median`/`max` bodies join and lost 17). PCT marks a PLATFORM function, so the spellings that rule drops
   are registered natives: `pathToElement/1`, `elementToPath(Function<Any>)`, `extractEnumValue(enum, String[0..1])`.
7. A property read on a lambda value reads its m3 classifier (`$f.expressionSequence`); `Any.classifierGenericType` is served on
   parameterized receivers too; a relation-type literal in argument position (`@TDS<(a:String[1])>`) resolves column-wise;
   `extractEnumValue` with a non-literal name types against its signature; a row pick over an unknown schema is not a cell index.
8. Profiles are values of the `Profile` metaclass (`ModelContext.findProfile`).

### 8.2 The 36 that remain, by owner

| rows | what | owner |
|---|---|---|
| 7 | `match` bodies no branch accepts statically (`testMatch*Fail`) | real pure's runtime failure; the inliner's `liveArms` must emit a RAISE for a no-live-arm match before the typer can route these to the runtime form (§8.3) |
| 1 | `elementToPath(Class<X>)` ties with the `Function<Any>` overload | the linearization tie-break needs the m3 `Function`/`Type` relation for a Class value — row |
| 4 | units: `RomanLength` as a value ×3, `@Unit` | parser wall (§4: `newUnit`, `~Pes`) — units are unparsed |
| 3 | a PACKAGE as a value (`meta`, `meta::pure::functions::meta`, `Root`) | vocabulary: a package index on the model (no startsWith) |
| 3 | deep-copy key paths `^$p(address.name='x')` | parser/typer feature (keyed copy paths) |
| 3 | `TableAlias.relation` ×2, `GraphFetchTree.propertyTrees` | DERIVED properties — the generator's derived-body leg (§9.3) |
| 3 | `executeTest`, `executePCTTest`, `loadPCTManifest` | PCT harness natives over spec-only classes (`TestResult`, `PCTManifest`) — walls |
| 3 | `replaceAll`, `getMapStats`, `getIfAbsentPutWithKey` | spec functions declared outside the nine platform roots |
| 2 | a lambda body with a discarded expression statement (`|[]->toOneMany(); 1;`, `inlineEmbeddedProperty`) | typing should accept, lowering walls — `LambdaBodies` rule (design row) |
| 2 | `meta::relational::metamodel::join/filter` bodies | special-form routing: `join`/`filter` claim bare names on any receiver (design row) |
| 1 | `doSomething_Integer_1__Boolean_1_->eval(getInts())` — `[*]` into `[1]` | spec leniency on eval multiplicity — decision row |
| 1 | `getProperty(...):Property<Nil,Any|*>` vs `Property<Any,Any>` | Nil as a universal type ARGUMENT — decision row |
| 1 | `enumerationMappingByName` tie: the spec's body vs `SystemMetamodel`'s same-name view (return `EnumerationMapping` vs `<Any>`) | census-only (production never loads the spec file); `concatenate` of the two spellings in the spec body is the same fact |
| 1 | `variant::navigation::get` structural | a variant overload spelling — later |

### 8.3 Caught by the lanes, not the census

- `Class.properties : Property<T,Any|*>[*]` entered the class LAYOUT (a `Class` value's SQL struct) → 9 tests lost (`no SQL type for
  generic Property<…>`). Rule: a nominal whose class is a `Function` subclass is CODE like the bare function type — no slot
  (`ClassLayouts.isFunctionCarrier`).
- Making `SystemMetamodel.enumerationMappingByName` return `EnumerationMapping<Any>` (to dissolve the census tie) changed the
  typed carrier the system views produce → 2 tests lost. Reverted; the tie stays a census-only row.
- The first cut of the property-value unwrap applied to EVERY formal (a plain `T` bound the function type, not the Property):
  2 system-mapping bodies broke inside the census — scoped to structural formals before any lane ran.
- The runtime form for a `match` no branch accepts (rule 5) HUNG the DuckDB lane: `UserCallInliner.liveArms` returns EVERY arm of a
  match with no live arm, and nested matches make the rewrite exponential (9 minutes at 100% CPU, one test). Withdrawn; the 7 rows stay.
- The signature-exact PCT suppression (rule 6, first cut) lost 17 chB-std tests: the spec's `average`/`median`/`max`/`min`/`minBy`
  bodies joined the overload set beside our natives and were inlined. Back to by-name; the three dropped spellings became natives.
- The engine's SQL post-processing machinery is WALLED whole (USER 2026-09-08, until a design session): `UserCallInliner.ENGINE_MACHINERY_WALLS`
  names the SQL printer (`sqlQueryToString`) and the four `PostProcessor`/`PostProcessors` registry properties by exact FQN; a program that
  reaches one fails at once naming the wall. Post-processing on this platform is a compiler pass (post-processors-are-compiler-passes);
  the 3 post-processor tests in the fail roster now fail in 2 s instead of unrolling the engine's SQL printer for minutes. The inliner also
  gained an UNROLL BUDGET (20,000 expansions per compile — a loud wall, sibling of the recursion-cycle guard; 2,000 was too tight for two
  passing toPostgresModel tests) and a one-time declared-ancestor index for its arm scan.
- Guards: `Typer.java` split — annotation resolution moved to `TypeAnnotations` (the type-parameter frame lives there); the native-catalog
  golden regenerated (−4 platform `<T>` arithmetic spellings, +36 spec natives); hand-declared class count 78 → 82; the identity-argument
  pin became "arguments over the class's own parameters" now that the kernel instantiates supertypes (`asSuper`, used by the unify arm too).
