# A03 — SUBTYPE RELATION & TYPE LATTICE

Scope: `ModelContext.isSubtype`/`findType`/`findClass`/`findEnum`, `TypeClassifier`,
`type/PlatformTypes`, `type/Type`, `builtin/Pure` native class table, `TypedClass`,
`ClassCompiler`, plus every other implementation of "is X a subtype of Y" the walk turned up
(`InferenceKernel`, `CastPolicy`, `MatchFold`, `Scalars.instanceOfFold`).

All probes are under `/tmp/a03/`. Everything below was RUN; pasted output is verbatim.

---

## FINDINGS

### [UNSOUND] A property override with an incompatible type is accepted; the declared static type is then violated by the runtime value

**Evidence.** Nothing in Phase F compares a locally declared property against the inherited
declaration it shadows.
`compiler/element/ClassCompiler.java:43-52` just copies the local declarations:

```java
List<Property> properties = new ArrayList<>();
for (ClassDefinition.PropertyDefinition pd : cd.properties()) {
    properties.add(new Property.Stored(
            pd.name(),
            classifier.classify(pd.type(), typeParams), ...
```

`compiler/element/ModelIntegrity.java:101-138` (`checkClass`) checks duplicate property names
*within* the class and multiplicity bounds — never the supertypes' declarations.
`compiler/element/PureModelContext.java:186-208` (`findProperty`) returns the most-derived
declaration and never checks conformance.

**Repro** (`/tmp/a03/ovr.pure`, `/tmp/a03/ovrq.pure`):

```pure
Class model::B { p: meta::pure::metamodel::type::String[1]; }
Class model::D extends model::B { p: meta::pure::metamodel::type::Integer[1]; }
function model::readAsString(b: model::B[1]): meta::pure::metamodel::type::String[1] { $b.p }
```
query: `model::readAsString(^model::D(p=1))`

**Actual output** (`/home/user/probe/probe.sh /tmp/a03/ovr.pure /tmp/a03/ovrq.pure`):

```
[G] type=String mult=[1]
[G] typeRepr=STRING
[PLAN] SELECT 1 AS value
[PLAN] rootType=String mult=[1]
[EXEC] shape=Scalar returnType=String returnTypeRepr=STRING
[EXEC-ROW] Integer(1) |
```
and (`/tmp/a03/Override.java`)
```
model compiled OK (D.p:Integer OVERRIDES B.p:String)
  isSubtype(D,B) = true
    STATIC TYPE = String[1]   (the runtime value is the Integer 1)
```

**Why it matters.** The compiler claims `String[1]`; the wire returns a `java.lang.Integer`.
Every consumer that trusts `returnType` (result decoding, serialization, downstream string
natives) is now holding a lie. Real legend-pure rejects an incompatible property redefinition
at compile time.

---

### [UNSOUND] `Class X extends meta::pure::metamodel::type::Nil` makes X a subtype of EVERY type — including primitives and FQNs that do not exist

**Evidence.** `compiler/element/ModelContext.java:233-258`:

```java
private boolean isSubtype(String childFqn, String parentFqn, java.util.Set<String> visited) {
    if (childFqn.equals(parentFqn)) return true;
    if (childFqn.equals(...PlatformTypes.NIL)) return true;     // <-- fires on RECURSION too
    if (!visited.add(childFqn)) return false;
    ...
    for (String superFqn : child.get().superClassFqns()) {
        if (isSubtype(superFqn, parentFqn, visited)) return true;   // superFqn may BE Nil
    }
```
The bottom-type arm is inside the recursive walk, so any class whose ancestor chain reaches
`Nil` inherits "subtype of everything". `Nil` is an ordinary entry in the native class table
(`builtin/Pure.java:178`) and is therefore nameable in an `extends` clause; nothing rejects it.

**Repro** `/tmp/a03/NilExploit.java`. **Actual output:**

```
compileModel OK: 'Class model::Evil extends meta::pure::metamodel::type::Nil'
  isSubtype(Evil, Person)  = true
  isSubtype(Evil, Integer) = true
  accepts(ClassType(Person), ClassType(Evil)) = true
  LUB(Evil, Person) = model::Person

  QUERY: model::takesPerson(^model::Evil(boom='x'))
    ACCEPTED -> String[1]
  QUERY: ^model::Evil(boom='x')->cast(@model::Person).name
    ACCEPTED -> String[1]
```
(and `/tmp/a03/BadSuper.java`: `isSubtype(G, no::such::X) = true` for `G extends Nil`.)

**Why it matters.** `model::takesPerson` declares `p: model::Person[1]` and its body reads
`$p.name`; the argument is an `Evil` with no `name` property, and the whole call is typed
`String[1]`. Every conformance gate in the compiler (overload scoring, unification, graph-fetch
`->subType`, mapping class-binding selection) is defeated by one `extends` clause.

---

### [UNSOUND] `commonSupertype` of two `PrecisionDecimal`s returns whichever operand came SECOND — a narrowing, not a join; the static scale is violated by the value

**Evidence.** `compiler/spec/InferenceKernel.java:1331-1341` collapses a `PrecisionDecimal` to
the bare `Decimal` FQN:

```java
private static @com.legend.Nullable String nominalFqn(Type t) {
    case Type.PrecisionDecimal pd -> pd.basePrimitive().qualifiedName();
```
then `InferenceKernel.java:1294-1298`:
```java
if (ctx.isSubtype(fa, fb)) { return b; }
if (ctx.isSubtype(fb, fa)) { return a; }
```
Both sides collapse to `…::Decimal`, `isSubtype` is reflexive, so the first branch always
fires and the join is **`b`** — the right-hand operand — whatever its precision/scale.

**Repro / actual output** (`/tmp/a03/runq.sh`):

```
### QUERY: [1.25d, 1.5d]
[G] type=Decimal(38,1) mult=[2]
[EXEC] shape=Collection returnType=Decimal(38,1) returnTypeRepr=PrecisionDecimal[precision=38, scale=1]
[EXEC-ROW] BigDecimal(1.25) |          <-- scale 2 value under a scale-1 static type
[EXEC-ROW] BigDecimal(1.50) |

### QUERY: [1.5d, 1.25d]
[G] type=Decimal(38,2) mult=[2]        <-- SAME set, other order, different answer

### QUERY: if(true, |1.25d, |1.5d)
[G] type=Decimal(38,1) mult=[1]
[EXEC-ROW] BigDecimal(1.25) |

### QUERY: [1.25d, 1.5d]->at(0)
[G] type=Decimal(38,1) mult=[1]
[EXEC-ROW] BigDecimal(1.25) |
```
Exhaustive cross-product run (`/tmp/a03/TypeLattice.java`) — 6 commutativity violations, all
`PrecisionDecimal`:
```
LUB(P:Decimal,PD(38,18))=Decimal(38,18)  but LUB(PD(38,18),P:Decimal)=Decimal
LUB(PD(38,18),PD(10,2))=Decimal(10,2)  but LUB(PD(10,2),PD(38,18))=Decimal(38,18)
LUB(PD(38,18),PD(5,0))=Decimal(5,0)    but LUB(PD(5,0),PD(38,18))=Decimal(38,18)
LUB(PD(10,2),PD(5,0))=Decimal(5,0)     but LUB(PD(5,0),PD(10,2))=Decimal(10,2)
```
`LUB(Decimal(10,2), Decimal(5,0)) = Decimal(5,0)` is not an upper bound of either input.

**Why it matters.** A "least upper bound" that narrows silently loses precision/scale in exactly
the place the type is used to size the SQL column and to decode the wire value. It is also
order-dependent, so a source-order edit changes the declared type of a query.

---

### [UNSOUND] `match` dispatch ignores generic type ARGUMENTS — the first positional branch always wins

**Evidence.** `compiler/spec/InferenceKernel.java:1094-1119` (`paramTypeScore`, the body of
`accepts`) compares only the raw FQN of a `GenericType`:

```java
case Type.GenericType g -> {
    if (!(actual instanceof Type.GenericType ag)
            || !ag.rawFqn().equals(g.rawFqn())) {
        yield -1;
    }
    ... // only an interior FunctionType arity/result-multiplicity check
    yield 1;
}
```
`compiler/spec/MatchChecker.java:89` takes the **first** branch for which
`t.kernel().accepts(branchType, input.info().type())` holds, and the runtime-dispatch escape at
`MatchChecker.java:130-131` only triggers when a branch *strictly narrows*
(`accepts(input, bt) && !accepts(bt, input)`) — which is never true here, because acceptance is
symmetric for two same-raw generics.

**Repro / actual output:**
```
### QUERY: list([1,2])->match([ l: List<String>[1] | 'PICKED-STRING-BRANCH',
                                l2: List<Integer>[1] | 'PICKED-INT-BRANCH' ])
[PLAN] SELECT 'PICKED-STRING-BRANCH' AS value
[EXEC-ROW] String(PICKED-STRING-BRANCH) |

### QUERY: list(['a'])->match([ l: List<Integer>[1] | 'PICKED-INT-BRANCH',
                                l2: List<String>[1] | 'PICKED-STRING-BRANCH' ])
[PLAN] SELECT 'PICKED-INT-BRANCH' AS value
[EXEC-ROW] String(PICKED-INT-BRANCH) |
```
(Fully-qualified spellings used in the actual probe; see `/tmp/a03/MatchProbe.java`.)

The same hole exists for bare relation types — `InferenceKernel.java:1122`:
`case Type.RelationType ignored -> Type.schemaView(actual) != null ? 1 : -1;` — ANY relation
conforms to ANY relation formal regardless of columns; and for `FunctionType`, whose parameter
and result TYPES are never compared (only arity + result multiplicity).

Exhaustive antisymmetry scan over 42 constructible types (`/tmp/a03/TypeLattice.java`) — 19
violations, all of this class:
```
R:(a:String) <-> R:(a:Integer)
R:(a:String) <-> R:(a:String,b:Integer)
G:Relation<(a:String)> <-> G:Relation<(a:Integer)>
G:List<String> <-> G:List<Integer>
F:{String[1]->Integer[1]} <-> F:{Integer[1]->String[1]}
... (+ the PrecisionDecimal and Any/TypeVar pairs)
```

---

### [SILENT FALLBACK] An unknown / typo'd superclass FQN is accepted with no error — reference safety never classifies the `extends` list

**Evidence.** `compiler/element/ClassCompiler.java:38-41`:

```java
List<String> superFqns = new ArrayList<>(cd.superClasses().size());
for (TypeExpression sup : cd.superClasses()) {
    superFqns.add(TypeClassifier.headFqn(sup));   // headFqn returns nr.name() RAW
}
```
`TypeClassifier.headFqn` (`TypeClassifier.java:130-143`) returns the bare name without ever
calling `findType`. `ModelIntegrity.checkClass` (`ModelIntegrity.java:101-138`) classifies
property types, derived-property types and parameter types — and never the supertypes.
`checkInheritanceAcyclic` explicitly skips unresolvable heads
(`ModelIntegrity.java:266-272`: `classifier.classDef(supFqn).ifPresent(...)`).

**Repro / actual output** (`/tmp/a03/BadSuper.java`):
```
compileModel OK (typo'd 'model::Bass' accepted)
  D superClassFqns  = [model::Bass]
  findClass(Bass)   = false
  findType(Bass)    = Optional.empty
  isSubtype(D,Base) = false
  isSubtype(D,Bass) = true          <-- a subtype claim about a type that does not exist
  findProperty(D,n) = Optional.empty
```
The same hole accepts nonsense supertypes:
```
Class extends PRIMITIVE String: compiled OK   isSubtype(E,String)= true
Class extends ENUM:             compiled OK   isSubtype(F,Col)  = true
Class extends Nil:              compiled OK   isSubtype(G,H)    = true   (see the Nil finding)
```
Contrast: the very same FQN in a property/param/return position throws
(`/tmp/a03/Unknown.java`): `ModelException: Unknown type: 'model::Nope' is not a known
primitive, class, or enum`.

**Why it matters.** A one-character typo in `extends` silently detaches a class from its
hierarchy: inherited properties vanish, `isSubtype` goes false, mapping class-binding selection
(`resolver/ClassSources.java:716`) and association-end routing (`resolver/AssociationJoins.java:1219`)
silently pick differently — with no diagnostic anywhere.

---

### [SILENT FALLBACK] `TypeClassifier.classify` never validates a GENERIC head or its arity

**Evidence.** `compiler/element/TypeClassifier.java:101-107`:

```java
case TypeExpression.Generic g -> {
    List<Type> args = new ArrayList<>(g.arguments().size());
    for (TypeExpression arg : g.arguments()) { args.add(classify(arg, typeParams)); }
    yield new Type.GenericType(g.name(), args);      // g.name() used RAW
}
```
No `findType(g.name())`, no comparison against the head class's declared `typeParameters`.
The class javadoc (`TypeClassifier.java:15-22`) claims "an unknown FQN throws (no fallback,
AGENTS.md invariant 4)" — true for `NameRef`, false for `Generic`.

**Repro / actual output** (`/tmp/a03/Generic.java`):
```
=== undefined GENERIC head
   compiled OK
     prop Stored[name=p, type=GenericType[rawFqn=model::Nope, arguments=[STRING]], ...]
=== known head, WRONG ARITY (List takes 1)
   compiled OK
     prop ... GenericType[rawFqn=...collection::List, arguments=[STRING, INTEGER]]
=== known head, ZERO args   (Pair takes <U,V>)
   compiled OK
     prop ... GenericType[rawFqn=...collection::Pair, arguments=[STRING]]
=== PRIMITIVE used as a generic head
   compiled OK
     prop ... GenericType[rawFqn=meta::pure::metamodel::type::Integer, arguments=[STRING]]
```
Downstream, every `PlatformTypes` carrier predicate gates on arity
(`isListCarrier` size==1, `isPairCarrier` size==2, `Type.isRelation` size==1), so a malformed
`List<String,Integer>` silently *stops being a list* instead of erroring.

---

### [UNSOUND] No user class and no enum is a subtype of `Any`; 20 native classes never reach `Any` either

**Evidence.** `ClassCompiler` populates `superClassFqns` from the declared `extends` only, so a
class with no `extends` has NO supertype. `ModelContext.isSubtype` walks `findClass` alone, and
`findClass` is empty for enums (`PureModelContext.java:160-171` serves enums from a separate
`findEnum`).

**Repro / actual output** (`/tmp/a03/Lattice.java`, exhaustive over 35 FQNs):
```
--- NOT a subtype of Any ---
  model::Top / model::Left / model::Right / model::Bottom / model::Lone
  model::Color / model::Size
  meta::pure::tds::TDSNull
  meta::pure::metamodel::type::generics::GenericType
  meta::core::runtime::Runtime
  meta::pure::precisePrimitives::Int
  meta::pure::precisePrimitives::BigInt
  no::such::Type
  count = 13 / 35
```
And exhaustively over the whole native table (`/tmp/a03/PureGraph.java`, 200 classes):
```
--- NOT REACHING Any ---  (20 entries)
  ElementOverride, TDSNull, generics::GenericType, core::runtime::Connection,
  ConnectionStore, Runtime, EngineRuntime, ExecutionContext, MultiExecutionContext,
  ExecutionOptionContext, extension::Extension, RelationalQueryGenerationConfig,
  AlloySerializationConfig, DatabaseConnection, TestDatabaseConnection,
  RelationalDatabaseConnection, PureModelConnection, JsonModelConnection,
  ModelChainConnection, GenerationFeaturesConfig
orphan count = 20
```
The `ModelContext.isSubtype` javadoc (`ModelContext.java:216-222`) asserts the lattice is topped
by `Any` ("the bootstrap primitive lattice (Integer < Number < Any)"). That holds for the 12
primitives only.

**Why it matters.** `isSubtype(_, Any)` is the relation; `InferenceKernel.unify`
(`InferenceKernel.java:76`) and `paramTypeScore` (`InferenceKernel.java:1069`) each carry a
SEPARATE hard-coded `Any` arm to compensate. Any consumer that reaches for the relation instead
of the special case gets `false` for the top type. I could NOT reach an observable wrong answer
through `commonSupertype` (its `return anyType()` default at `InferenceKernel.java:1305` masks
it) — reported here as a latent hole plus the proven root cause of the `extends`-validation and
`Nil` findings above.

---

### [CRASH/ICE] `instanceOf` ignores the subtype relation entirely and raises an internal `NotImplementedException` on every non-identical pair — including statically decidable ones

**Evidence.** `lowering/Scalars.java:2563-2585`:

```java
Type actual = n.args().get(0).info().type();
boolean sure = target != null
        && (actual instanceof Type.ClassType a && a.fqn().equals(target)
            || ...TABULAR_DATA_SET.equals(target) && Type.isRelation(actual));
if (!sure) {
    throw new NotImplementedException("instanceOf undecidable statically: " + actual + " vs '" + target + "'");
}
return new SqlExpr.BoolLit(true);
```
`ctx.isSubtype` is never consulted; `Type.Primitive` actuals can never be `sure`; the function
can only ever return the literal `true`.

**Repro / actual output:**
```
### QUERY: ^model::Person(...)->instanceOf(model::Address)
[PLAN-ERROR] com.legend.error.NotImplementedException: instanceOf undecidable statically: ClassType[fqn=model::Person] vs 'model::Address'
### QUERY: meta::pure::functions::meta::instanceOf(1, meta::pure::metamodel::type::Integer)
[PLAN-ERROR] com.legend.error.NotImplementedException: instanceOf undecidable statically: INTEGER vs 'meta::pure::metamodel::type::Integer'
### QUERY: meta::pure::functions::meta::instanceOf(1, meta::pure::metamodel::type::Number)
[PLAN-ERROR] com.legend.error.NotImplementedException: instanceOf undecidable statically: INTEGER vs 'meta::pure::metamodel::type::Number'
```
Separately, the `@Type` spelling of the same call is rejected at Phase G for every type
(`instanceOf(@model::Person)`):
```
[G-ERROR] TypeInferenceException: in call to 'meta::pure::functions::meta::instanceOf',
          argument 2: expected meta::pure::metamodel::type::Type, got model::Person
```
because `@X` types as `X` and `isSubtype("model::Person", "…::type::Type")` is false.

---

### [INCONSISTENCY] TWO different LUBs for the same pair of types, producing different runtime decodings

**Evidence.** `InferenceKernel.commonSupertype` (line 1238) walks the real lattice;
`InferenceKernel.valueLub` (line 554) is a second, coarser join used on the type-variable
rebinding path (`InferenceKernel.java:520-524`):

```java
private static Type valueLub(Type a, Type b2) {
    if (isNumeric(a) && isNumeric(b2)) { return Type.Primitive.NUMBER; }
    return new Type.ClassType(ANY_FQN);          // no temporal ladder, no class LCA
}
```

**Repro / actual output:**
```
### QUERY: [%2020-01-01, %2020-01-01T10:00:00]          (collection literal -> commonSupertype)
[G] type=Date mult=[2]
[EXEC] returnType=Date
[EXEC-ROW] DateWithSecond(2020-01-01T00:00:00+0000) |
[EXEC-ROW] DateWithSecond(2020-01-01T10:00:00+0000) |

### QUERY: [%2020-01-01]->concatenate([%2020-01-01T10:00:00])   (unify -> valueLub)
[G] type=meta::pure::metamodel::type::Any mult=[*]
[EXEC] returnType=meta::pure::metamodel::type::Any
[EXEC-ROW] String(2020-01-01) |
[EXEC-ROW] String(2020-01-01 10:00:00) |
```
Same two values, same question ("what is their common type?"), two answers — and the answer
changes how the wire values are DECODED (`DateWithSecond` objects vs raw `String`s). This is
also an INFORMATION-LOSS / forward-backward asymmetry.

---

### [INCONSISTENCY] Six independent hand-rolled implementations of the primitive subtype ladder; two of them omit `LatestDate <: Date`

| # | Site | Rule |
|---|------|------|
| 1 | `compiler/element/ModelContext.java:233` `isSubtype` | walks `Pure.java` `extends` chains — `LatestDate <: Date` TRUE |
| 2 | `compiler/spec/InferenceKernel.java:1386` `isPrimitiveSubtype` | delegates to #1 |
| 3 | `compiler/spec/InferenceKernel.java:565` `conformsUpValueLattice` | `declared==DATE -> actual==STRICT_DATE \|\| actual==DATE_TIME` — **omits LATEST_DATE** |
| 4 | `compiler/spec/InferenceKernel.java:361` `conformsForWildcard` | NUMERIC family only |
| 5 | `lowering/CastPolicy.java:216` `isWidening` | `tgt==DATE -> src==STRICT_DATE \|\| src==DATE_TIME` — **omits LATEST_DATE** |
| 6 | `lowering/MatchFold.java:41` `staticConforms` | `armFqn==Date -> p.isTemporal() && p != STRICT_TIME` (a 3rd temporal grouping) |

Quoted, `InferenceKernel.java:565-574`:
```java
private static boolean conformsUpValueLattice(Type actual, Type declared) {
    if (declared == Type.Primitive.NUMBER) { return isNumeric(actual); }
    if (declared == Type.Primitive.DATE) {
        return actual == Type.Primitive.STRICT_DATE || actual == Type.Primitive.DATE_TIME;
    }
    return false;
}
```
`CastPolicy.java:216-225` is the same table again. Neither calls `ctx.isSubtype`.
I could NOT drive an observable wrong answer through #3 or #5 for `LatestDate` — `unify`'s
`requirePrimitiveSubtype` reaches #1 first, and `CastPolicy.isSqlPrimitive`
(`CastPolicy.java:227-233`) excludes `LATEST_DATE` from cast emission — so this is reported as
drift, not as a live defect.

Related: `Type.Primitive.Family` groups `StrictTime` under `TEMPORAL`
(`Type.java:85`) while the lattice puts it directly under `Any` — `/tmp/a03/Fam.java`:
```
StrictTime   TEMPORAL  [Any]   <:Number=false  <:Date=false  <:Any=true
MISMATCHES family-vs-lattice:
  STRICT_TIME: isTemporal()=true but isSubtype(_,Date)=false
```
`MatchFold.java:52` already hard-codes the `p != STRICT_TIME` correction for its own consumer;
`CastPolicy.crossKindRaise` does not, so a `StrictTime -> StrictDate` cast is treated as
"within family" and never raises.

---

### [SILENT FALLBACK] Redeclaring a platform FQN: classes are silently DROPPED, enums silently WIN — opposite precedence, no diagnostic either way

**Evidence.** `compiler/element/TypeClassifier.java:69-78`:

```java
Optional<ClassDefinition> classDef(String fqn) {
    Optional<ClassDefinition> nat = Pure.findNativeClass(fqn);
    return nat.isPresent() ? nat : model.findClass(fqn);       // NATIVE first
}
Optional<EnumDefinition> enumDef(String fqn) {
    Optional<EnumDefinition> user = model.findEnum(fqn);
    return user.isPresent() ? user : Pure.findNativeEnum(fqn); // USER first
}
```

**Repro / actual output** (`/tmp/a03/Shadow.java`):
```
[2] user class at meta::pure::tds::TabularDataSet -- compiled OK
    findClass().properties = [Stored[name=columns,...], Stored[name=rows,...]]   (the NATIVE's)
    findProperty(TDS,mine) = Optional.empty                                      (user's prop GONE)

[3] user enum at meta::pure::functions::date::Month -- compiled OK
    findEnum(Month).values = [HOAX]        <-- the platform's 12 months are REPLACED

[4] user class at meta::pure::metamodel::type::Any -- compiled OK
    findClass(Any).properties = []                                               (user's prop GONE)

[5] user class at meta::pure::metamodel::type::Integer -- compiled OK
    findType(Integer)  = Optional[INTEGER]                                       (user's class GONE)
```
Both directions are silent. Case [3] is a live hazard: any model can replace `Month`,
`DurationUnit`, `DatabaseType`, `SortDirection`, … for the whole compile.

---

### [SILENT FALLBACK] A user class at `meta::pure::precisePrimitives::*` is DUALLY classified: `findType` says `Integer`, `findClass` says the user's class

**Evidence.** `compiler/element/type/Type.java:122-139` hard-codes ten precise-primitive FQNs
into `Primitive.BY_FQN`, but `builtin/Pure.java` declares NO native class at those FQNs — so
the package is unclaimed and a user can declare into it. `TypeClassifier.findType`
(`TypeClassifier.java:32-50`) consults the primitive table first; `findClass`
(`PureModelContext.java:115-125`) consults the model.

**Repro / actual output** (`/tmp/a03/Shadow.java`):
```
[1] user class at meta::pure::precisePrimitives::Int -- compiled OK
    findType(...Int)   = Optional[INTEGER]
    findClass(...Int)  = Optional[TypedClass[qualifiedName=meta::pure::precisePrimitives::Int,
                          ..., properties=[Stored[name=evil, type=STRING, ...]], isNative=false]]
    Holder.v type      = Stored[name=v, type=INTEGER, ...]      <-- typed as a PRIMITIVE
    findProperty(Int,evil) = Optional[Stored[name=evil, type=STRING, ...]]  <-- has members
```
A property declared `v: meta::pure::precisePrimitives::Int[1]` compiles to `Integer`, while the
same FQN simultaneously resolves to a user class with a `String` property.

The same table is invisible to `isSubtype` (which is FQN-string-based):
```
meta::pure::precisePrimitives::Int is NOT <: Any        (/tmp/a03/Lattice.java)
```
i.e. `findType` says `Int` IS `Integer` while `isSubtype` says `Int` is unrelated to `Integer`,
`Number` and `Any`.

---

### [UNSOUND] `cast(@T)` performs NO relatedness check; an enum casts to `String` and an `Integer` casts to `String` silently

**Evidence.** `compiler/spec/CastChecker.java:23-41` emits `TypedCast` with no check that source
and target are related; the native signature is
`cast<T|m>(Any[m], type:T[1]):T[m]` (`builtin/Pure.java`), whose `Any` formal accepts everything
and whose `T` simply binds to the annotation. The only guard is a *lowering-time*
family check (`lowering/CastPolicy.java:181-206` `crossKindRaise`), which explicitly whitelists
TEXT<->NUMERIC and TEXT<->TEMPORAL, and returns `null` (no guard) for enums and classes.

**Repro / actual output:**
```
### QUERY: meta::pure::functions::date::Month.January->cast(@String)
[G] type=String mult=[1]
[PLAN] SELECT 'January' AS value
[EXEC-ROW] String(January) |            <-- an Enum silently becomes a String

### QUERY: 1->cast(@String)
[G] type=String mult=[1]
[PLAN] SELECT CAST(1 AS VARCHAR) AS value
[EXEC-ROW] String(1) |                  <-- cast is a CONVERSION, not an assertion

### QUERY: 'x'->cast(@Integer)
[G] type=Integer mult=[1]
[PLAN] SELECT CAST('x' AS BIGINT) AS value
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Could not convert string 'x' to INT64
```
In real Pure `cast` is a type ASSERTION over the subtype relation, never a value conversion.
(`1->cast(@Boolean)` IS guarded — `SELECT error('Cast exception: Integer cannot be cast to
Boolean')` — so the guard exists but keys on `Family`, not on the subtype relation.)

---

### [UNSOUND] Cross-kind `equal` returns TRUE — an enum equals its own name as a String, and `1 == '1'` is true

**Repro / actual output:**
```
### QUERY: meta::pure::functions::date::Month.January == 'January'
[G] type=Boolean mult=[1]
[PLAN] SELECT 'January' = 'January' AS value
[EXEC-ROW] Boolean(true) |

### QUERY: 1 == '1'
[PLAN] SELECT 1 = '1' AS value
[EXEC-ROW] Boolean(true) |
```
(`Month.January == Quarter.Q1` IS folded to `FALSE`, so enum-vs-enum is handled; enum-vs-String
and Integer-vs-String are not.) In real Pure, values of different types are never equal.
Root: `equal` is declared `equal(Any[*],Any[*])`, `EnumType` has no supertype so no lattice
check can reject the pair, and lowering hands both sides to the database, which coerces.

---

### [SILENT DEFAULTING] A diamond that inherits the same property name at conflicting types resolves by `extends`-list ORDER

**Evidence.** `compiler/element/PureModelContext.java:193-197` iterates `superClassFqns()` in
declaration order and returns the first hit.

**Repro / actual output** (`/tmp/a03/Final.java`):
```
diamond conflicting property 'p' (B:String, C:Integer):
  findProperty(A ,p) = Stored[name=p, type=STRING,  ...]     (A  extends B, C)
  findProperty(A2,p) = Stored[name=p, type=INTEGER, ...]     (A2 extends C, B)
```
Also: `Class model::A extends model::B, model::B` (the same super twice) compiles, yielding
`supers=[model::B, model::B]` — no duplicate check.

---

### [DEAD] `Type.PrecisionDecimal.plus/minus/times/dividedBy` (and `MIN_ADJUSTED_SCALE`, `DEFAULT_DECIMAL`) have NO production call site

**Evidence.** `grep -rn "\.plus(\|\.dividedBy(\|\.times(\|\.minus(\|MIN_ADJUSTED_SCALE\|DEFAULT_DECIMAL"` over
`core/src/main/java` returns exactly ONE hit — the declaration of `DEFAULT_DECIMAL` at
`Type.java:175`. Every other hit is in
`core/src/test/java/com/legend/compiler/element/type/PrecisionDecimalArithmeticTest.java`.

**Consequence (INFORMATION LOSS), actual output:**
```
### QUERY: 1.25d           [G] type=Decimal(38,2)
### QUERY: 1.25d + 1.5d    [G] type=Decimal        <-- precision/scale DROPPED
### QUERY: 1.25d * 1.5d    [G] type=Decimal
```
The ~70 lines of Spark-lineage precision derivation documented at `Type.java:194-242` are never
reached: decimal arithmetic erases the operands' precision to the bare `Decimal` primitive.

---

### [DOC-LIE] `Type.PrecisionDecimal`'s javadoc claims subtyping is normalized "in the one subtype routine `ModelContext.isSubtype`, via `basePrimitive()`"

`Type.java:150-157`:
> "Any normalization that is unavoidable happens in the *one* subtype routine
> (`ModelContext.isSubtype`), via `basePrimitive()`."

`ModelContext.isSubtype` takes `(String childFqn, String parentFqn)` — it can never see a
`PrecisionDecimal` and never calls `basePrimitive()`. The actual call sites are
`InferenceKernel.java:1334` and `:1401` — i.e. two places, in a different class. (This is the
same collapse that produces the PrecisionDecimal LUB unsoundness above.)

### [DOC-LIE] `PlatformTypes`' javadoc claims `PlatformTypesDriftTest` pins its constants against `builtin/Pure` "so neither can move alone"

`PlatformTypes.java:9-13` makes the claim. `PlatformTypesDriftTest.java:17-24` asserts exactly
six: `ANY, NIL, VARIANT, LIST, PAIR, FUNCTION`. Unpinned: `CLASS_METACLASS`, `TDS_NULL_FQN`,
`TABULAR_DATA_SET`, `TDS_ROW`, all ~28 function FQNs, and the `Map` carrier FQN — which is not
even a constant but an inline literal inside `isMapCarrier` (`PlatformTypes.java:332`).

---

## VERIFIED SOUND

Enumerated exhaustively unless stated.

**`ModelContext.isSubtype` as an FQN relation** (`/tmp/a03/Lattice.java`, universe = all 12
`Type.Primitive` FQNs + 5 diamond classes + 2 enums + 14 platform FQNs + 2 precise primitives +
1 nonexistent FQN = 35; all 35, 35², 35³ combinations checked):
- reflexivity: **0 violations** (35/35).
- transitivity: **0 violations** over all 42 875 triples.
- antisymmetry: **0 violations** over all 1 225 pairs.
- `Nil <: X` for all 35 X: **0 failures** — bottom IS modelled (`ModelContext.java:242`), and
  `Multiplicity`/`InferenceKernel` agree (`isNil` arms at `InferenceKernel.java:477`, `:615`, `:1065`).
- No `StackOverflowError`: the `visited` set (`ModelContext.java:245`) is shared across the whole
  traversal with a single fixed `parentFqn`, so the memoized negative is correct on DAGs; I
  re-derived this by hand and confirmed the diamond gives correct answers.

**Inheritance cycles are rejected** (`/tmp/a03/Misc.java`):
```
=== cycle A extends B, B extends A -> ModelException: Inheritance cycle: model::A -> model::B -> model::A
=== self cycle A extends A         -> ModelException: Inheritance cycle: model::A -> model::A
```

**`builtin/Pure` native class table** (`/tmp/a03/PureGraph.java`, all 200 classes + all 21 enum
declarations / 19 distinct enums):
- 0 cycles.
- 0 dangling super references (every `extends` head is itself in the table).
- 0 duplicate class declarations.
- The primitive lattice matches real FINOS Legend Pure `m3.pure`
  (`/tmp/a03/Fam.java`): `Number extends Any`; `Integer|Float|Decimal extends Number`;
  `String|Boolean|Byte|Date|StrictTime extends Any`;
  `StrictDate|DateTime|LatestDate extends Date`. **`LatestDate` IS present and IS under `Date`.**
  No edge here surprised me.
- `Relation extends Any`, `TDS extends Relation`, `Class extends Type`, `Enumeration extends Type`,
  `Type extends ModelElement extends Any`, `LambdaFunction extends FunctionDefinition extends
  Function extends Any` — all faithful.

**`Type`-level `accepts` / `commonSupertype`** (`/tmp/a03/TypeLattice.java`, 42 constructible
types = 12 primitives + 3 `PrecisionDecimal` + 8 `ClassType` + 2 `EnumType` + 2 `TypeVar` +
3 bare `RelationType` + 7 `GenericType` + 2 `FunctionType` + 1 `SchemaAlgebra`; 42², 42³ scanned):
- `accepts(Any, X)` is TRUE for all 42 — the `Any` slot rejects nothing.
- `accepts(T,T)` reflexive for 41/42 (only `SchemaAlgebra` fails, and `paramTypeScore`'s
  `case Type.SchemaAlgebra ignored -> -1` is documented as unreachable; I could not construct a
  reaching call, so I am reporting it only here).
- `commonSupertype` restricted to the 21 CONCRETE types (`/tmp/a03/LubConcrete.java`): all 441
  pairs — **0 throws, 0 non-upper-bounds**. `LUB(X, Any) == LUB(Any, X) == Any` for all 21.
- Class-diamond LUB is correct: `LUB(model::Left, model::Right) = model::Top`;
  `LUB(model::Bottom, model::Lone) = Any`; empirically
  `if(true, |^Left(..), |^Right(..))` -> `model::Top[1]` and `[^Left(..), ^Right(..)]` -> `model::Top[2]`.
- Temporal LUB `LUB(StrictDate, DateTime) = Date`, `LUB(LatestDate, StrictDate) = Date`.
- Enum LUBs are `Any` (`LUB(Color,Size)`, `LUB(Color,String)`), and `accepts` between two
  distinct `EnumType`s is FALSE (`InferenceKernel.java:1084`) — enums are correct leaves.

**Numeric promotion (item 3), verified against every producer I could find.**
Static join sites: (a) overload selection over the `plus/minus/times` catalog
(`builtin/Pure.java:1996-2001`, `:1895-1899`, `:2204-2208` — Integer/Integer, Float/Float,
Decimal/Decimal, Number/Number, String/String); (b) `InferenceKernel.commonSupertype:1238`;
(c) `InferenceKernel.valueLub:554`; (d) `InferenceKernel.conformsForWildcard:361`;
(e) lowering-side `Scalars.decimalJoin:2809`. (a)–(d) all answer **`Number`** for
Integer∨Float, Integer∨Decimal, Float∨Decimal — they agree. (e) is a *SQL-emission* rule that
promotes float literals to decimal literals when any operand is decimal-kind; that is a
narrower, emission-only rule and does not contradict the static answer.

Empirical (`/tmp/a03/runq.sh`, static type vs actual Java class):
```
1 + 1      -> Integer[1]  SELECT 1 + 1                       -> Integer(2)
1 + 1.0    -> Number[1]   SELECT 1 + CAST(1.0 AS DOUBLE)     -> Double(2.0)
1 + 1.0d   -> Number[1]   SELECT 1 + 1.0                     -> BigDecimal(2.0)
1.0d+1.0d  -> Decimal[1]  SELECT 1.0 + 1.0                   -> BigDecimal(2.0)
1.0 + 1.0d -> Number[1]   SELECT 1.0 + 1.0                   -> BigDecimal(2.0)
2.5d * 2   -> Number[1]   SELECT 2.5 * 2                     -> BigDecimal(5.0)
3 - 1.0d   -> Number[1]   SELECT 3 - 1.0                     -> BigDecimal(2.0)
1 / 2      -> Float[1]    SELECT ((1.0 * 1) / 2)             -> Double(0.5)
5 / 2      -> Float[1]    SELECT ((1.0 * 5) / 2)             -> Double(2.5)
1 == 1.0   -> Boolean[1]  SELECT 1 = CAST(1.0 AS DOUBLE)     -> Boolean(true)
1.25d      -> Decimal(38,2)                                  -> BigDecimal(1.25)
toDecimal(1) -> Decimal(38,18)                               -> BigDecimal(1)
[1,1.0]    -> Number[2]                                      -> Long(1), Double(1.0)
```
Integer division is NOT integer division: `1/2` is `Float` = 0.5, matching real Pure's
`divide(Number,Number):Float`. Every runtime class above is a subtype of the declared static
type — no unsoundness in this table. (The decimal LUB defect above is separate.)

**Unknown-name handling in `TypeClassifier` (item 5)** — `NameRef` throws, no
`ClassType(name)` fallback (`TypeClassifier.java:98-99`). Verified across five positions
(`/tmp/a03/Unknown.java`):
```
property             -> ModelException: Unknown type: 'model::Nope' ...
function param       -> ModelException: Unknown type: 'model::Nope' ...
function return      -> ModelException: Unknown type: 'model::Nope' ...
derived property     -> ModelException: Unknown type: 'model::Nope' ...
cast @model::Nope    -> TypeInferenceException: unknown type 'model::Nope' in @model::Nope
typed colspec/relation literal -> TypeInferenceException: unknown type 'model::Nope' in @model::Nope
inside a relation type literal -> ModelException: Unknown type: 'model::Nope' ...
inside a function type         -> ModelException: Unknown type: 'model::Nope' ...
^model::Nope(..)     -> TypeInferenceException: unknown class 'model::Nope' in ^model::Nope(?)
model::NoEnum.RED    -> TypeInferenceException: unknown enumeration 'model::NoEnum'
model::Persson.all() -> ResolutionException: 'model::Persson' is not a known class, mapping, ...
```
All loud. Typos do NOT become silent class references. (The two exceptions are the `extends`
list and the generic head — reported as findings above.)

**`PlatformTypes` predicates (item 4).** All 17 public predicates read in full. Every one of
them uses `String.equals` on a full FQN (`isTdsType`, `isAny`, `isNil`, `isVariant`,
`isListCarrier`, `isPairCarrier`, `isMapCarrier`, `isFunctionCarrier`, `isFetchDbFn`,
`fetchDbKind`, `isStoreNavFn`, `isDdlStatementFn`, `isPlatformOwnedFunction`, `isExecuteFqn`,
`isEffectfulNative`, `isKNative`, `isPostProcessorConfigProperty`). **No suffix, prefix, or
simple-name matching anywhere in this file.** I built a class named `my::domain::List`, a class
named `TabularDataSet` in a user package, and a class whose FQN is a prefix of a platform FQN —
none is misclassified. `/tmp/a03/PlatformDrift.java` reflects over every constant: all 12 type
FQNs correspond to a real entry in the native class catalog (no stale/hijackable type FQN). The
only way to defeat these predicates is to declare AT the exact FQN, which is the shadowing
finding above.

FQN *prefix/suffix* matching does exist elsewhere in the codebase and I checked the three
reachable ones: `normalizer/MappingNormalizer.java:2345`
(`name.startsWith("meta::pure::metamodel::type::")` with NO primitive-name check — a user class
declared into that package is treated as a platform kind) is a genuine prefix hole, but its
consumer (`declaredPlatformKind` -> `declaredAssertion`) is a mapping-wire concern I am leaving
to the mapping auditor; `MappingNormalizer.java:3377` guards the same prefix with
`PRIMITIVE_TYPE_NAMES.contains(...)` and is safe; `StatementExecutor.java:1187/:1323`
(`endsWith("::DynaFunction")`, `endsWith("::SQLNull")`) are suffix matches over store-metamodel
FQNs, out of this slug's scope.

**Enum subtyping (item 6)**, `/tmp/a03/EnumProbe.java`: `EnumType` is a LEAF — not `<: Any`, not
`<: Enumeration` under `isSubtype`; `accepts` between distinct enums is false; `accepts(Any,
EnumType)` is true via the dedicated `Any` arm; LUBs go to `Any`. Enum-vs-enum equality across
different enumerations is statically folded to `FALSE`. Only the enum-vs-String case is broken
(reported above).

---

## NOT COVERED

- **Multiplicity conformance** (`Multiplicity.java`, `unifyMult`) — a different slug's surface;
  I only read enough of it to be sure it was not the source of the type-level defects above.
- **`SchemaAlgebra` conformance** (`unifyConstraint`, `⊆`/`=`/`+`/`-`) beyond noting that
  `accepts` is not reflexive on it. Reaching those arms requires a colspec/window signature
  path that belongs to the relation-algebra auditor.
- **`Variant`** subtyping carve-outs (`unifyMult`'s "a Variant MANY conforms to a to-one slot")
  — noted, not audited.
- **Whether the `Nil`-extends and bad-`extends` holes survive into Phase H/I/J execution.** I
  proved the Phase-G/plan-level unsoundness (static type assigned, value violates it) for the
  property-override and PrecisionDecimal cases end-to-end through `[EXEC]`; for the `Nil` case I
  stopped at the Phase-G acceptance because a store mapping for the exploit class would have
  changed what the repro demonstrates.
- **`normalizer/MappingNormalizer.declaredPlatformKind`'s prefix hole** — located and quoted,
  but its blast radius is the mapping wire-cast lane, not the subtype relation.
- **`ClassSources` / `AssociationJoins` `isSubtype` consumers** — read the call sites, did not
  build mapping-level repros for them.
- I did **not** run the JUnit suite (the brief forbids `mvn`); every claim here is from
  `jrun.sh`/`probe.sh` output pasted above.
