# Hand-declared shapes in Pure.java vs the spec — the sweep and the plan (2026-09-08)

Status: SWEEP, run after batch 150 with `tools/shape_sweep.py` (output: the table below). USER question:
"why would anything diverge from the real spec? … we need a sweep and a plan to fix all of these."

## 1. The numbers

| | count |
|---|---|
| hand-declared classes in `Pure.java` | 84 |
| exact against the spec (supertypes, parameters, stored properties) | 48 |
| diverging | 36 |
| of those, tool gaps (verified by hand this session: Package, Measure, Unit, `Class<T>`, `Property extends AbstractProperty<{U[1]->V[m]}>`, `RelationElementAccessor`) | 6 |
| REAL divergences | 30 |

Native function signatures are not in this sweep: `NativeFunctionTest.everyTypePositionFqnInNativeSignaturesResolvesToCatalog`
and the census already hold them to the spec's spellings.

## 2. The 30 real divergences, by kind

**A. Type-parameter NAMES (6, cosmetic but real):** `Function<F>`, `FunctionDefinition<F>`, `ConcreteFunctionDefinition<F>`,
`LambdaFunction<F>` are `<T>` in m3; `relation::Column<T,X>` is `<U,V>`; `Enumeration<T>` is `<E>`. Nothing in Java keys on the
names (batch 149 renamed the ColSpec family without incident).

**B. Raw vs parameterized spellings (7):** ours says `Function<Any>` where the spec says `Function` (`FunctionExpression.func`,
`ConstraintsOverride.constraintsManager`), `FunctionDefinition<Any>` vs `FunctionDefinition` (`Constraint` ×2), `QualifiedProperty<Any>`
vs `QualifiedProperty` (`Association`); and the reverse: `Class` vs `Class<Any>` (`SetImplementation.class`), `EnumerationMapping` vs
`EnumerationMapping<Any>` (`Mapping`), `Property` vs `Property<Nil,Any|*>` (`PropertyMapping.property`). Since batch 150 the kernel
reads a raw reference as the class over `Any`, so either spelling types the same; spec-exact costs nothing now.

**C. Missing supertypes (10):** `Referenceable` (PackageableElement, Function, FunctionType), `Testable` (Mapping, Class,
ConcreteFunctionDefinition), `ElementWithConstraints` and `PropertyOwner` (Class, PrimitiveType), `AnnotatedElement` (Column, Database),
`SetColumn` (Column), `Expression` (VariableExpression, FunctionExpression — the m3 class between them and ValueSpecification),
`ValueTransformer<T>` (EnumerationMapping), `SetBasedStore` for `Store` (Database), and `Type extends Any` where ours says `ModelElement`.
Each is a shape the generator would emit for free; by hand they were skipped as "not witnessed".

**D. Missing stored properties (reflection metadata, 12 classes, ~30 properties):** `Any.classifierGenericType/elementOverride`;
`Multiplicity.multiplicityParameter` (and its bounds are `[0..1]`, not `[1]`); `ValueSpecification.usageContext`;
`VariableExpression.functionTypeOwner`; `FunctionExpression`'s seven (importGroup, propertyName, qualifiedPropertyName, the two
originalMilestoned*, resolvedType/MultiplicityParameters); `Testable.tests`; `PrimitiveType.typeVariables`; `FunctionType.function/
typeParameters/multiplicityParameters`; `Property.aggregation/defaultValue`; `Function.name/applications`; `LambdaFunction.openVariables`;
`Class`'s five (originalMilestonedProperties, qualifiedPropertiesFromAssociations, typeParameters, typeVariables, multiplicityParameters);
`relation::Column.nameWildCard`; `TDSNull.key`; `PropertyMappingsImplementation.stores`; `InstanceSetImplementation.mappingClass/
aggregateSpecification`; `PropertyMapping.localMappingProperty*/store`.

**E. Hierarchy reshaped (2):** the `SetImplementation` chain (spec: `SetImplementation` and `PropertyMappingsImplementation` side by side
under `PropertyOwnerImplementation`, which carries `id/parent/superSetImplementationId`; `InstanceSetImplementation` inherits both —
ours flattens it and hoists the three properties); `Database extends Store` for `SetBasedStore, AnnotatedElement`.

**F. Property TYPES changed (3):** `EnumValueMapping.enum : String[1]` (spec `Enum[1]`) and `sourceValues : String[*]` (spec `Any[*]`) —
the system store's rows carry enum names as strings; `relational::Column.owner : Table[0..1]` (spec `Relation[0..1]`).

Also: `Mapping.name` and `Class.name` are declared directly where the spec inherits them from `ModelElement` (harmless duplicates that
go when the shapes migrate).

## 3. Why they diverged — the three causes

1. **Hand-written by witness.** Each class was typed in with only the properties and supertypes a corpus test read at the time
   (the pins in `NativeFunctionTest` say so: "stored props only", "grown by witness"). Kinds A–D are all this. Not decisions.
2. **A platform limitation of the day.** `Any` property-free protects the class LAYOUT (every instance would gain two reflection fields
   with no SQL type — 173 tests, batch 147); `Column<T,X>` dropped `|z` when the kernel could not carry multiplicity parameters.
   Both limitations are gone or nearly: batch 150 added "a function-typed property is code, no layout slot"; the same rule for
   reflection-typed properties (`GenericType`, `ElementOverride`) frees `Any`; the kernel binds `|m` since batch 149.
3. **The system store's row shape.** Kinds E and F: the metamodel-as-relations tables type their rows as these classes, and a flat
   chain / string-typed enum column made one row class serve. These are the only divergences with a PLATFORM reason; each needs a
   leg on the store side (a `PropertyOwnerImplementation` row class; enum values as `Enum` rows), not a permanent exception.

## 4. The plan — in the order that removes mechanisms

1. **LANDED 2026-09-08 (batch 151, PRELUDE_MODULE_HOMEWORK §9).** The prelude becomes a Pure MODULE compiled through the user pipeline (parse → resolve → normalize → build), the way
   `SystemMetamodel.source()` already is. The generator writes source with imports instead of `nativeClass(...)` calls; derived
   properties are emitted verbatim and the normalizer lifts them like a user class's. This closes the last 3 census rows
   (`TableAlias.relation`, `GraphFetchTree.propertyTrees`), makes the printer unnecessary, and retires the on-demand lift in
   `FunctionCompiler` once no catalog class has a derived property. Pair/List `toString` ride it; Scalars' Java arms go.
2. **Migrate the 84 out of `Pure.java` a family at a time** — IN PROGRESS. Census 2026-09-08 (batch 157): of 85 hand shapes,
   65 are m3.pure BOOTSTRAP declarations (the generator cannot read m3.pure — a generator leg: read the m3 graph as
   `tools/m3shape.py` does), 17 are declared in legend-pure `.pure` files, 2 in engine files. Family 1 LANDED (batch 157):
   the ColSpec six, `Variant`, `Rows`, `TDSNull`, `Result`, `RelationalActivity` — hand lines deleted, every Java site names
   them by `PlatformTypes` constants, definitions read from the module; kinds A/B for them dissolved by construction; hand
   count 85 → 74; no pass change. Left in the file-declared group: the six store-coupled shapes (step 4) and `Column`/`Database`.
   **The m3 reader LANDED (batch 158):** the generator reads m3.pure's instance graph and prints its 85 classes and 2
   enumerations as declarations (`PreludeGeneratorTest.M3Reader`; `tools/m3shape.py` retired — one owner); T1-whole then admits
   the 32 classes + 2 enums no hand line owned (`prelude.pure` 384 → 418); against the 53 hand m3 shapes the print reproduces
   exactly the kinds A–D divergences above (28 identical, 25 differing only by them). Remaining by hand: 74 = 53 m3 shapes
   (migrate by family now that the reader exists — the bootstrap floor is measured as each family goes) + 13 primitives
   (`PrimitiveType` instances, the Java `Type.Primitive` enum's keys) + the 6 store-coupled + `Column`/`Database`.
   The original plan: m3 valuespecification/function/type families,
   mapping, relational, plan/runtime, tds. Migration = delete the hand line, let the generator emit the spec declaration. Kinds
   A–D dissolve on migration by construction. `Pure.java` keeps native signatures, `Lite`, and the BOOTSTRAP handful.
3. **The bootstrap handful** (what Java constructs before a model exists: the primitives — a Java enum —, `Any`, `Class<T>`,
   `Relation<T>` and the ColSpec family the Typer builds directly): each stays with a receipt naming WHICH Java constructs it. `Any`
   gets the layout rule (reflection-typed properties have no slot) and then its two spec properties; the Typer's hand-served
   `classifierGenericType`/`elementOverride` arms go with it.
4. **The two store-shaped divergences** (kind E/F): a `PropertyOwnerImplementation` row class and `Enum`-typed enum-value rows in
   the system store. Each is a store leg with a witness; until it lands the hand shape carries the receipt.
5. **The pin**: `tools/shape_sweep.py` becomes a governance test — every hand shape exact, or on a shrink-only list with its receipt.

Estimated: step 1 is the design leg (generator output + model-build wiring, moderate); steps 2–3 are mechanical with lane risk per
family; step 4 is two store legs; step 5 is small. None of it changes what the corpus passes; all of it removes Java and hand text.
