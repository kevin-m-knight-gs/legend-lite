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
