# A26 — MODEL <-> PROTOCOL boundary: does a Type survive the round trip?

Scope: `com/legend/protocol/*` (14 files) + `com/legend/protocol/spec/*` (32 files) +
`com/legend/model/*` (incl. `FromProtocol`, `MappingFromProtocol`, `RelOpFromProtocol`),
plus the two adapters that actually *cross* the boundary
(`compiler/NameResolver.resolveType`, `compiler/element/TypeClassifier.classify`).

All probes under `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a26/`,
run via `/home/user/probe/jrun.sh`. Every "Actual output" block below is pasted verbatim.

---

## 0. WHAT THE PROTOCOL LAYER IS (answer to task item 1)

Read in full: `ProtocolEmitter.java` (type/literal/multiplicity paths), `Protocol.java` (relevant
records), `TypeExpression.java`, `Multiplicity.java`, `Escapes.java`, `spec/ValueSpecification.java`
plus all 9 literal records, `FromProtocol.java`, `MappingFromProtocol.java`, `RelOpFromProtocol.java`,
`TypeClassifier.java`, `NameResolver.resolveType` / `resolveTypeAnnotation` / `resolveColSpec`.

**It is an EMISSION format only. There is no ingestion path.** Three directions exist, all one-way:

```
Pure text --ElementParser/SpecParser/MappingProtocolParser--> Protocol.* records (in-memory, span-carrying)
                                                       |
                        +------------------------------+------------------------------+
                        |                                                             |
        ProtocolEmitter/TailEmitter/MappingEmitter/                    FromProtocol / MappingFromProtocol /
        ConnectionEmitters/GqlEmitter/AuthSpecEmitter                  RelOpFromProtocol
                        |                                                             |
                        v                                                             v
                engine-parity JSON bytes                                  com.legend.model.* records
                (terminal — nothing reads them back)                                  |
                                                                       NameResolver.resolve (rewrites TypeExpression)
                                                                                      |
                                                                       TypeClassifier.classify -> compiler Type
```

Verification that no reader exists: `grep -rn "readValue|parseJson|fromJson|JsonParser"` over
`protocol/` and `model/` returns **zero** hits. `PmcdParser` is *source text -> JSON*, not the inverse.
The only `PureModelContextData` consumers are the six emitters and `ProtocolEmitterTest`.

Consequence for the central question: a `Type` cannot be round-tripped, only **compared against its
own emission**. That is what the findings below do. Two independent losses were found and both are
load-bearing.

Two further structural facts that make "round trip" strictly impossible today:

* **The model-side `TypeExpression` is not emittable at all.** `NameResolver` rebuilds every node it
  rewrites through position-free convenience constructors, so `pos == null` afterwards, and
  `ProtocolEmitter.genericTypeOf` (`ProtocolEmitter.java:1798-1801`) *throws* on a null position.
  Proved below.
* `com.legend.model` shares `protocol.TypeExpression` / `protocol.Multiplicity` by reference — the
  "MODEL <-> PROTOCOL" boundary is therefore not a copy but a *mutation in place by NameResolver*,
  which is exactly where the type data is destroyed.

---

## FINDINGS

### [UNSOUND] `Decimal(p,s)` / `String(n)` type-variable values are silently destroyed between model and compiler — a well-formed model becomes uncompilable, and two different Decimal types compare equal

**Evidence.**

`compiler/NameResolver.java:452-457` rebuilds a `Generic` through the **2-argument** convenience
constructor, which hard-codes `multiplicityArguments = List.of()`, `typeVariableValues = List.of()`,
`pos = null`:

```java
case TypeExpression.Generic g -> {
    String r = resolveName(g.name(), scope);
    List<TypeExpression> args = resolveTypeList(g.arguments(), scope);
    yield (r.equals(g.name()) && args == g.arguments()) ? g
            : new TypeExpression.Generic(r, args);          // <-- drops tvv + multArgs + pos
}
```

`protocol/TypeExpression.java:123-135` compounds it — `Generic.equals`/`hashCode` **exclude**
`typeVariableValues` entirely, so even before resolution `Decimal(10,2).equals(Decimal(38,18))` is `true`.

`compiler/element/TypeClassifier.java:101-107` then classifies `Generic` into
`Type.GenericType(name, args)`, which has **no slot** for type-variable values. `Type.PrecisionDecimal`
— the variant that *does* carry `(precision, scale)` — is never produced from a declared type:
`grep` shows its only constructors are `TdsChecker:169,224`, `Typer:1818,3184`,
`StoreCompiler:192,193` (from the *relational* column type), and `Type.java:175,237,241`.

**Repro:** `a26/Matrix.java`, `a26/P2.java`, `a26/Rich.java`, `a26/m.pure` + `a26/q.pure`.

**Actual output** (`Matrix.java`):
```
======== Decimal(10, 2)[1]
  PARSE  : Generic[name=Decimal, arguments=[], multiplicityArguments=[], typeVariableValues=[CInteger[value=10, pos=@], CInteger[value=2, pos=@]], pos=@]
  RESOLV : Generic[name=meta::pure::metamodel::type::Decimal, arguments=[], multiplicityArguments=[], typeVariableValues=[], pos=@]
  ***LOSS at NameResolver
  TYPE   : GenericType[rawFqn=meta::pure::metamodel::type::Decimal, arguments=[]]   mult=Bounded[lower=1, upper=1]
======== Decimal(38, 18)[1]
  ...
  TYPE   : GenericType[rawFqn=meta::pure::metamodel::type::Decimal, arguments=[]]   mult=Bounded[lower=1, upper=1]
======== String(200)[1]
  ...
  TYPE   : GenericType[rawFqn=meta::pure::metamodel::type::String, arguments=[]]    mult=Bounded[lower=1, upper=1]
```

`P2.java` (equality):
```
EQUALITY: Decimal(10,2)-type equals Decimal(38,18)-type ? true
TE equals(10,2 vs 38,18) = true
TE hash equal = true
```

**The WIRE keeps what the model loses** — so this is a genuine model/protocol divergence, not a
parser gap (`Wire.java`):
```
======== Decimal(10, 2)[1]
  WIRE(parsed)   : {"genericType":{...,"fullPath":"Decimal",},"typeArguments":[],
                    "typeVariableValues":[{"_type":"integer","value":10},{"_type":"integer","value":2}]},
                   "multiplicity":{"lowerBound":1,"upperBound":1}
```

**End-to-end consequence** — `probe.sh a26/m.pure a26/q.pure test::R a26/ddl.sql` on a model whose
property is `amount: Decimal(18, 4)[1]` mapped to a `DECIMAL(18,4)` column:
```
[G] type=Relation<(a:Decimal<>[1])> mult=[1]
[PLAN-ERROR] com.legend.compiler.spec.TypeInferenceException: in function 'model::M$class$model::P':
  property 'amount' of 'model::P': expected Decimal<>, got Decimal(18,4)
```
Contrast, the same model with `(18, 4)` removed (`a26/m2.pure`):
```
[G] type=Relation<(a:Decimal[1])> mult=[1]
[PLAN] SELECT t0.AMT AS a
[EXEC] shape=Tabular returnType=Relation<(a:Decimal[1])>
```

**Why it matters.** (a) The declared precision/scale of a property is silently dropped — an
information loss at exactly the boundary under audit. (b) The residue is a *nonsense* static type
spelled `Decimal<>` that no other phase can match, so a legal Pure model fails to compile with a
self-contradictory message. (c) `Generic.equals` ignoring `typeVariableValues` means any type-identity
check (dedup, cache key, subsumption) treats `Decimal(10,2)` and `Decimal(38,18)` as the same type.
The same holds for qualified-property parameters (`Rich.java`: `scaled(g: Decimal(10,2)[0..1])` becomes
`TypedParameter[name=g, type=GenericType[...Decimal, arguments=[]]]`).

---

### [UNSOUND] `FromProtocol.dataType` narrows `Long` wire precision/scale/size to `int` — a fabricated static Pure type reaches the compiler, or an internal exception escapes

**Evidence.** `model/FromProtocol.java:310` and `:343-344` / `:350-351`. `Protocol.PDbType`
(`Protocol.java:862-865`) declares `Long size, Long precision, Long scale`; the model records take `int`:

```java
int size = t.size() == null ? 0 : t.size().intValue();            // :310
...
case "Decimal" -> new RelationalDataType.Decimal(
        t.precision() == null ? 0 : t.precision().intValue(),      // :343
        t.scale() == null ? 0 : t.scale().intValue());             // :344
case "Numeric" -> new RelationalDataType.Numeric(
        t.precision() == null ? 0 : t.precision().intValue(),      // :350
        t.scale() == null ? 0 : t.scale().intValue());             // :351
```

`compiler/element/StoreCompiler.java:192-193` then turns that model record straight into the static
Pure type: `case RelationalDataType.Decimal d -> new Type.PrecisionDecimal(d.precision(), d.scale())`.

**Repro:** `a26/DbType.java`, `a26/DbType2.java`, `a26/DecTable.java`.

**Actual output** — wire vs model (`DbType2.java` / `DbType.java`):
```
VARCHAR(2147483648)               ==>  WIRE "type":{"_type":"Varchar","size":2147483648}
                                       MODEL dataType=Varchar[size=-2147483648]
DECIMAL(4294967298, 4294967297)   ==>  WIRE "type":{"_type":"Decimal","precision":4294967298,"scale":4294967297}
                                       MODEL dataType=Decimal[precision=2, scale=1]
VARCHAR(4294967296)               ==>  MODEL dataType=Varchar[size=0]
CHAR(4294967296)                  ==>  MODEL dataType=Char_[size=0]
BINARY(4294967296)                ==>  MODEL dataType=Binary[size=0]
```

**Actual output** — the fabricated value reaching the compiler's *static Pure type* (`DecTable.java`):
```
DECIMAL(38,18)                     -> findTable = ... Column[name=C, type=PrecisionDecimal[precision=38, scale=18], ...]
DECIMAL(4294967298, 4294967297)    -> findTable = ... Column[name=C, type=PrecisionDecimal[precision=2, scale=1], ...]
DECIMAL(4294967296, 2)             -> !! IllegalArgumentException: scale must be in [0, precision], got scale=2, precision=0
VARCHAR(2147483648)                -> findTable = ... Column[name=C, type=STRING, ...]
```

**Why it matters.** `PrecisionDecimal(2,1)` is what `PureSql.type` turns into `SqlType.Decimal(2,1)`
(`lowering/PureSql.java:106`) — a claimed static precision the data can never satisfy. The
`DECIMAL(4294967296, 2)` case escapes as a raw `IllegalArgumentException` (CRASH/ICE), not a
user-facing compile error.

---

### [UNSOUND] `MappingFromProtocol` narrows `long` wire multiplicity bounds to `int` — CONFIRMS the orchestrator's report at `:605-610`, with three distinct failure modes

**Evidence — CONFIRMED, verbatim, `model/MappingFromProtocol.java:605-612`:**

```java
return new PropertyMapping.LocalProperty(rel.property(),
        new com.legend.protocol.TypeExpression.NameRef(lp.type(), null),
        new com.legend.protocol.Multiplicity.Concrete(
                (int) lp.lowerBound(),                                   // :608  raw (int) cast
                lp.upperBound() == null ? null
                        : Integer.valueOf(lp.upperBound().intValue())),  // :610  intValue()
        bodyOf(...));
```

The wire type is genuinely `long`: `Protocol.PLocalProp(String type, long lowerBound, Long upperBound, ...)`
(`Protocol.java:703-706`), fed by `MappingProtocolParser.parseLongMultBounds` (`:3431-3451`) which uses
`consumeLong()` with **no** bound check — unlike the ordinary property path, which uses
`consumeBoundedInt` (`TokenStreamCursor.java:318-326`) and refuses `> Integer.MAX_VALUE` cleanly.
`MappingEmitter.java:789-794` emits the untruncated `long` to the wire.

**Repro:** `a26/LocalProp.java`, `a26/LocalPropWire.java` — a `+localProp: String[m]` line in a
Relational class mapping.

**Actual output:**
```
======== +localProp: String[1]              multiplicity=[1]
======== +localProp: String[0..1]           multiplicity=[0..1]
======== +localProp: String[4294967296]     multiplicity=[0]                <-- SILENT TRUNCATION
======== +localProp: String[2147483648]     !! java.lang.IllegalArgumentException: lowerBound must be >= 0, got -2147483648
======== +localProp: String[0..4294967296]  multiplicity=[0]
======== +localProp: String[0..2147483648]  multiplicity=[0..-2147483648]   <-- NEGATIVE upper bound accepted
======== +localProp: String[3000000000]     !! java.lang.IllegalArgumentException: lowerBound must be >= 0, got -1294967296
```
And the wire for the same source (`LocalPropWire.java`):
```
"localMappingProperty":{"multiplicity":{"lowerBound":4294967296,"upperBound":4294967296},...,"type":"String"}
```

**Three distinct defects, all from this one site:**
1. **Silent truncation** — wire `[4294967296]` becomes model `[0]` (*exactly zero values*) with no diagnostic.
2. **Unrepresentable multiplicity constructed** — `[0..-2147483648]`. `Multiplicity.Concrete`'s compact
   constructor (`protocol/Multiplicity.java:66-74`) validates only `lowerBound >= 0`; **`upperBound` is
   never validated at all**, so a negative upper bound sails through.
3. **CRASH/ICE** — `[2147483648]` / `[3000000000]` escape as a raw `IllegalArgumentException`.

**Sibling sites found (the exhaustive sweep).** `grep -n "(int)|(long)|(short)|intValue()|longValue()|
doubleValue()|floatValue()|Integer.parseInt|Double.parseDouble|Long.parseLong|Float.parseFloat"` over
`protocol/*.java protocol/spec/*.java model/*.java` returned **17** hits; each evaluated:

| Site | Verdict |
|---|---|
| `MappingFromProtocol.java:608,610` | **DEFECT** (above) — the reported site, confirmed |
| `FromProtocol.java:310,343,344,350,351` | **DEFECT** (previous finding) — size/precision/scale |
| `ProtocolEmitter.java:3174` `-c.value().longValue()` | **DEFECT** (next finding) — negated literal |
| `ProtocolEmitter.java:3044,3047` `Integer.parseInt` | minor — misclassifying diagnostic (see LOW below) |
| `FromProtocol.java:726,752,755` `(int) s.port()` | narrows `long` port to `int`; a TCP port is bounded by its own protocol, so no realistic input reaches it — noted, not filed |
| `MappingFromProtocol.java:450` `n.longValue()` | SOUND — `PEnumSourceValue.value` is always a `Long` from `consumeLong()`; nothing wider exists to lose |
| `Multiplicity.java:101`, `Protocol.java:2684`, `ProtocolEmitter.java:3263` `upperBound().intValue()` | SOUND — the field is already `Integer`; `intValue()` is a no-op unbox |
| `Gql.java:238,244` (javadoc only) | SOUND — `IntValue(long)`, `FloatValue(double)`; GraphQL Int is 32-bit by spec, so the field is *wider* than needed |

---

### [UNSOUND] `ProtocolEmitter.foldNegation` truncates a negated `BigInteger` literal through `longValue()` — the emitted wire value has the wrong sign and the wrong magnitude

**Evidence.** `protocol/ProtocolEmitter.java:3172-3174`:

```java
case com.legend.protocol.spec.CInteger c ->
        new com.legend.protocol.spec.CInteger(
                -c.value().longValue(), span);          // <-- CInteger.value is Number (Long OR BigInteger)
```

`spec/CInteger.java` declares `Number value` precisely so `BigInteger` overflow "is preserved exactly"
(its own javadoc), and `SpecParser.parseInteger` (`:785-802`) produces a `BigInteger` for any literal
that does not fit 64 signed bits. The plain (non-negated) emission arm is correct —
`ProtocolEmitter.java:1994-1995` uses `c.value().toString()`. Only the unary-minus fold narrows.

**Repro:** `a26/BigNeg.java` — a `###Data / ModelStore` element (parsed at `Dialect.LEGEND_LITE`, which
`ElementParser.dataElement()` (`ElementParser.java:497-507`) passes straight through and emits with
`ProtocolEmitter.emitElement`).

**Actual output:**
```
-1                             -> WIRE "value":-1
-9223372036854775808           -> WIRE "value":-9223372036854775808
-9223372036854775809           -> WIRE "value":9223372036854775807      <-- SIGN FLIPPED
-18446744073709551616          -> WIRE "value":0
9223372036854775809            -> WIRE "value":9223372036854775809      (positive path: correct)
-99999999999999999999999999    -> WIRE "value":2537764290115403777      <-- arbitrary positive garbage
```

**Why it matters.** The record's whole reason for being `Number` rather than `long` is defeated at the
one place the emitter computes with it. A negative literal beyond 64 bits is emitted as a *positive*
number of a different magnitude, silently. The asymmetry with the positive path proves it is a bug and
not a deliberate width contract.

---

### [UNSOUND / FORWARD-BACKWARD ASYMMETRY] The same float literal is a `Float` on the wire and a `Decimal` in the model, with different values

**Evidence.** `SpecParser.parseFloat` (`:815-837`) branches on dialect: at `LEGEND_ENGINE` (the dialect
`PmcdParser` — i.e. the protocol wire — uses) it always builds `CFloat(double)`; at `LEGEND_LITE` (the
dialect `Compiler.compileModel` / `Compiler.parseQuery` use) it builds `CDecimal(BigDecimal)` whenever
the `double` is not an exact representation.

**Repro:** `a26/Floats2.java`, `a26/Floats.java`.

**Actual output:**
```
3.141592653589793238462643          | LEGEND_ENGINE = CFloat[value=3.141592653589793]         | LEGEND_LITE = CDecimal[value=3.141592653589793238462643]
0.1000000000000000055511151231257827| LEGEND_ENGINE = CFloat[value=0.1]                        | LEGEND_LITE = CDecimal[value=0.1000000000000000055511151231257827]
123456789012345678901234567890.5    | LEGEND_ENGINE = CFloat[value=1.2345678901234568E29]      | LEGEND_LITE = CDecimal[value=123456789012345678901234567890.5]
1e-400                              | LEGEND_ENGINE = CFloat[value=0.0]                        | LEGEND_LITE = CDecimal[value=1E-400]
1e400                               | LEGEND_ENGINE = CFloat[value=Infinity]                   | LEGEND_LITE !! NumberFormatException: Character I is neither a decimal digit number, decimal point, nor "e" notation exponential mark.
```

**Why it matters.** One source token, two different **Pure types** (`Float` vs `Decimal`) and two
different **values** across the boundary. A consumer of the protocol JSON is told
`3.141592653589793238462643` is a `Float` worth `3.141592653589793`; the compiler in the same process
holds a 25-digit `Decimal`. Two sub-findings ride along:

* `1e400` emits `{"_type":"float","value":Infinity}` — **not valid JSON** (RFC 8259 has no `Infinity`
  token), so the emitted document cannot be parsed back by any standard reader.
  Actual (`Floats.java`): `1e400      | WIRE {"_type":"float","value":Infinity}`
* `1e400` at `LEGEND_LITE` escapes as a raw `NumberFormatException` from
  `BigDecimal.valueOf(Double.POSITIVE_INFINITY)` at `SpecParser.java:832` — CRASH/ICE.

---

### [INFORMATION LOSS] An undeclared inline-relation-type column multiplicity is emitted as `[0..1]` while the compiler types it `[1]`

**Evidence.** `protocol/ProtocolEmitter.java:1774-1780`:

```java
if (col.multiplicityDeclared()) {
    multiplicity(b, col.multiplicity());
} else {
    b.append("{\"lowerBound\":0,\"upperBound\":1}");   // <-- ignores col.multiplicity() entirely
}
```

The `TypeExpression.Column` record's own javadoc says the parser stores `[1]` when the source does not
declare one, and `TypeClassifier.classify` (`:117-123`) passes `c.multiplicity()` through verbatim.

**Repro:** `a26/Wire.java` + `a26/Matrix.java`.

**Actual output** for `p: (a:Integer, b:String)[1]`:
```
WIRE : ... "multiplicity":{"lowerBound":0,"upperBound":1},"name":"a", ... "multiplicity":{"lowerBound":0,"upperBound":1},"name":"b"
TYPE : RelationType[columns=[Column[name=a, type=INTEGER, multiplicity=Bounded[lower=1, upper=1]],
                             Column[name=b, type=STRING,  multiplicity=Bounded[lower=1, upper=1]]], dynamicColumns=[]]
```

Aggravating: `NameResolver.resolveColumn` (`:1721-1726`) rebuilds through the 3-arg convenience
constructor, which sets `multiplicityDeclared = false`. So an **explicitly declared** `[1]` also becomes
"undeclared" the moment its type name is resolved:
```
======== (a:Integer[1], b:String[0..1])[1]
  PARSE  : ... Column[name=a, ..., multiplicity=[1], multiplicityDeclared=true, ...]
  RESOLV : ... Column[name=a, ..., multiplicity=[1], multiplicityDeclared=false, ...]
  ***LOSS at NameResolver
```

**Why it matters.** `[1]` (required) vs `[0..1]` (optional) is a nullability distinction. The wire
claims every undeclared relation column is optional; the compiler treats it as required. Anything
consuming the protocol (a downstream engine, a diff harness) gets the wrong nullability.

---

### [SILENT FALLBACK] A relational class mapping with no `~mainTable` / `[DB]` qualifier is silently DROPPED from the model while the wire keeps it

**Evidence.** `model/MappingFromProtocol.java:467-475`:

```java
private static @com.legend.Nullable ClassMapping relational(Protocol.PClassMappingRel rel) {
    try {
        return relationalInner(rel);
    } catch (MissingDatabase md) {
        // database inference (legend-pure) is unbuilt — skip, carried
        return null;
    }
}
```
The caller (`:186-191`) simply skips a `null`:
```java
ClassMapping built = classMapping(cm);
if (built != null) { classMappings.add(built); }
```

**Repro:** `a26/Drop.java` — a mapping with `firstName: T_PERSON.FIRST_NAME` (no `~mainTable`, no `[DB]`).

**Actual output:**
```
MODEL classMappings = 0  -> []
WIRE  classMappings = "classMappings":[{"_type":"relational","class":"model::Person",...,"propertyMappings":[{"_type":"rel...
```

**Why it matters.** AGENTS.md forbids silent fallbacks. Here an entire class mapping vanishes with no
diagnostic, no wall entry, and no error — the model simply believes the class is unmapped. The wire and
the model disagree about the *existence* of a mapping element, the largest possible unit of loss at
this boundary.

---

### [INFORMATION LOSS / INJECTION] Quoted FQN segments are unquoted into the wire path, so two distinct Pure elements produce byte-identical protocol and collide in the model

**Evidence.** `protocol/Protocol.java:2963-3000` (`unquoteSegments`, used by `splitPath` and the public
`unquotePath`) decodes each quoted segment with `Escapes.unescapeJavaLike` and re-joins with `::`. The
quoting is not recorded anywhere, so a quoted segment that itself contains `::` becomes real nesting.

**Repro:** `a26/Esc.java`, `a26/Esc2.java`, `a26/Esc3.java`.

**Actual output:**
```
Class test::x::y::A     -> WIRE "name":"A","package":"test::x::y"
Class test::'x::y'::A   -> WIRE "name":"A","package":"test::x::y"
span-stripped IDENTICAL? true

Class test::'A::B'      -> WIRE "name":"A::B","package":"test"
quoted-name fqn = test::A::B   plain fqn = test::A::B  COLLIDE? true

compile(Class test::A::B + Class test::'A::B')
  -> ModelException: [2:1] Duplicated element 'test::A::B'

compile(Class test::'A::B' + Class test::C { r: test::A::B[1]; })
  -> REFERENCE resolved to the quoted class:
     [Stored[name=r, type=ClassType[fqn=test::A::B], multiplicity=Bounded[lower=1, upper=1], ...]]
```

**Why it matters.** (a) The emission is not reversible — nothing in the JSON distinguishes a quoted
segment from real package nesting, so `test::'x::y'::A` and `test::x::y::A` are indistinguishable
downstream. (b) It is a live **injection into the type system**: a crafted class name `test::'A::B'`
becomes an alias for whatever `test::A::B` names, and a `test::A::B[1]` property type *resolves onto it*
(last line above). A model author can make a type reference land on a different element than the one
that appears to be named.

**The escape table itself is faithful** for every case tested (see VERIFIED SOUND) with one documented
irreversibility: `Escapes.java:131` `default -> sb.append(esc)` is DROP-BACKSLASH, so `'x\qy'` and
`'xqy'` both decode to `xqy` and emit identically
(`drop-backslash in name -> WIRE "name":"xqy"`). Commons-text parity, but lossy by construction.

---

### [INFORMATION LOSS] Generic multiplicity arguments (`Relation<T|m>`, `Result<X|1>`) are dropped at name resolution — inconsistently, depending on whether the head name resolves

**Evidence.** Same site as the `Decimal(p,s)` finding, `NameResolver.java:452-457`: the 2-arg `Generic`
constructor also zeroes `multiplicityArguments`. `Type.GenericType(String rawFqn, List<Type> arguments)`
has no multiplicity-argument slot either, so `TypeClassifier` could not carry them even if they survived.

**Actual output** (`Matrix.java`), showing the *inconsistency* — the loss fires only when the head name
is rewritten:
```
======== Relation<T|m>[1]
  PARSE  : Generic[name=Relation, arguments=[NameRef[name=T]], multiplicityArguments=[m], ...]
  RESOLV : Generic[name=meta::pure::metamodel::relation::Relation, arguments=[NameRef[name=T]], multiplicityArguments=[], ...]
  ***LOSS at NameResolver
======== TestClass<|1>[1]
  PARSE  : Generic[name=TestClass, arguments=[], multiplicityArguments=[1], ...]
  RESOLV : Generic[name=TestClass, arguments=[], multiplicityArguments=[1], ...]     <-- kept, because the name did NOT resolve
```

**Why it matters.** Whether a type's multiplicity parameter survives depends on whether its *name*
needed rewriting — two spellings of the same type give different model objects. Combined with
`Generic.equals` also excluding `typeVariableValues`, the only `Generic` fields that participate in
type identity are `name` and `arguments`.

---

### [INFORMATION LOSS] Post-resolution `TypeExpression` / spec AST cannot be emitted at all — every NameResolver rebuild drops the source position the emitter requires

**Evidence.** `ProtocolEmitter.genericTypeOf` (`:1798-1801`) and `requirePos` throw when `pos == null`.
`NameResolver` rebuilds through position-free constructors at `:447` (`NameRef`), `:456` (`Generic`),
`:1689-1690` (`ColSpec` — the 6-arg ctor drops `pos`, `colType`, `colTypeMult`, `stereotypes`,
`taggedValues`), `:1697` (`TypeAnnotation.Named`), `:1703` (`TypeAnnotation.RelationShape` — the 1-arg
ctor drops `spelledName`, `typeSpan`, `pos`), and `:1721-1726` (`TypeExpression.Column`).

**Actual output** (`a26/Wire.java`, for every non-trivial form):
```
======== Integer[1]
  WIRE(parsed)   : {"genericType":{...,"fullPath":"Integer",...}}
  WIRE(resolved) : !! UnsupportedOperationException: ProtocolEmitter needs a source position for type
                      meta::pure::metamodel::type::Integer and the parser did not thread one — fix the parse site, do not default it.
```
(`a26/ColSpecP.java`, ColSpec position):
```
pos before=true  after=false
```

**Why it matters.** This is the structural proof that the boundary is strictly one-way: the model's own
type representation is *not* a valid protocol input. Any future "compile then re-emit" path is
categorically blocked, and the drops are silent until emission time.

---

### [INFORMATION LOSS / DEAD TYPE LOGIC] `TypeExpression.FunctionType` and `TypeExpression.SchemaAlgebra` have NO protocol emission rule, and a multiplicity PARAMETER walls in three separate places

**Evidence + actual output** (`a26/Wire.java`) — the parser accepts all of these as property types and
`TypeClassifier` classifies all of them into a `Type`, but the emitter refuses:
```
======== {Integer[1]->String[1]}[1]
  WIRE(parsed)   : !! UnsupportedOperationException: ProtocolEmitter has no rule for type expression FunctionType — add the emit rule, do not drop it.
======== {->Boolean[1]}[1]
  WIRE(parsed)   : !! ... FunctionType ...
======== Relation<T+Z>[1]
  WIRE(parsed)   : !! UnsupportedOperationException: ProtocolEmitter has no rule for type expression SchemaAlgebra — add the emit rule, do not drop it.
======== Relation<T-Z>[1]
  WIRE(parsed)   : !! ... SchemaAlgebra ...
======== Relation<T|m>[1]
  WIRE(parsed)   : !! UnsupportedOperationException: ProtocolEmitter has no rule for a multiplicity PARAMETER 'm' in generic Relation — add the emit rule.
```
The corresponding classifications all succeed (`Matrix.java`):
```
{Integer[1]->String[1]} -> FunctionType[params=[Param[type=INTEGER, multiplicity=Bounded[lower=1, upper=1]]], result=Param[type=STRING, ...]]
Relation<T+Z>           -> GenericType[rawFqn=...Relation, arguments=[SchemaAlgebra[left=TypeVar[name=T], op=UNION, right=TypeVar[name=Z]]]]
```
The three multiplicity-parameter walls: `ProtocolEmitter.multiplicity` (`:3257-3260`),
`parseMultArg` (`:3051-3054`), `Protocol.PFunction.mangleMult` (`:2676-2679`).

**Why it matters.** These are *loud* walls, not silent drops (good), but they mean the protocol cannot
represent 2 of the 5 `TypeExpression` variants and 1 of the 2 `Multiplicity` variants. This is the
answer to task item 6, forward direction.

---

### [INCONSISTENCY] Bare `Result` — the wire synthesises `Result<Any|1>` with an unresolved `fullPath`; the compiler produces `ClassType[meta::pure::mapping::Result]` with no arguments

**Evidence.** `ProtocolEmitter.java:1806-1818` special-cases the *literal string* `"Result"`:
```java
if (path.equals("Result") && args.isEmpty() && multArgs.isEmpty()) { ... Result<Any|1> ... }
```

**Repro / actual output** (`a26/BareResult.java`) for `p: Result[1]`:
```
PARSE TE = NameRef[name=Result, pos=SourceInfo[...]]
WIRE = "genericType":{"multiplicityArguments":[{"lowerBound":1}],"rawType":{"_type":"packageableType","fullPath":"Result",},
        "typeArguments":[{...,"fullPath":"meta::pure::metamodel::type::Any"},...],"typeVariableValues":[]}
TYPE = [Stored[name=p, type=ClassType[fqn=meta::pure::mapping::Result], multiplicity=Bounded[lower=1, upper=1], ...]]
```
Two mismatches: the wire fabricates a `<Any|1>` application the model does not have, and it keeps the
*bare* `Result` in `fullPath` while the model resolved it to `meta::pure::mapping::Result`. Because the
guard matches on the bare string, the same type emits differently once the name is FQN.

---

### [DEAD TYPE LOGIC] `ColSpec.colType` / `ColSpec.colTypeMult` are emitted to the wire but have no consumer in the compiler, and are never name-resolved

**Evidence.** `grep -rn "colType"` across `com/legend`: producers are `SpecParser.java:2310,2317`;
consumers are only `ProtocolEmitter.java:2994-3001` (emission) and
`spec/ValueSpecification.java:195` (`withChildren` pass-through). Nothing in `compiler/` reads either.
`NameResolver.resolveColSpec` (`:1678-1690`) does not touch `colType` at all — and if its rebuild *does*
fire (a lambda/arg changed), the 6-arg `ColSpec` constructor drops both fields.

**Actual output** (`a26/ColSpecT.java`) — a declared column type is carried but left unresolved:
```
~a:Integer[1]
  PARSE : ColSpec[name=a, ..., colType=NameRef[name=Integer, pos=@], colTypeMult=[1], ...]
  RESOLV: ColSpec[name=a, ..., colType=NameRef[name=Integer, pos=@], colTypeMult=[1], ...]
```
(`Integer` is still the bare name after resolution — an import-relative colType would never be
rewritten to an FQN.)

**Why it matters.** This is the answer to task item 6, reverse direction: an emitted type that cannot
be re-classified because no classifier ever looks at it.

---

### [LOW / CRASH-ADJACENT] `ProtocolEmitter.parseMultArg` misreports an out-of-int-range concrete multiplicity bound as a "multiplicity PARAMETER"

**Evidence.** `protocol/ProtocolEmitter.java:3042-3055` — `Integer.parseInt` inside a `try` whose
`catch (NumberFormatException named)` rethrows with a message that names the text as a *parameter*.
The parser hands `multiplicityArguments` across as raw `String`
(`TypeExpression.Generic.multiplicityArguments` is `List<String>`, filled by
`TokenStreamCursor.parseMultiplicityArgumentText` `:1118-1147`), so this re-parse is the only place the
argument becomes structured.

**Actual output** (`a26/MultArg.java`):
```
TestClass<|1>                  PARSE=[1]              WIRE="multiplicityArguments":[{"lowerBound":1,"upperBound":1}]
TestClass<|m>                  PARSE=[m]              !! ... no rule for a multiplicity PARAMETER 'm' ...            (correct)
TestClass<|2147483648>         PARSE=[2147483648]     !! ... no rule for a multiplicity PARAMETER '2147483648' ...   (WRONG: it is a bound)
TestClass<|99999999999>        PARSE=[99999999999]    !! ... PARAMETER '99999999999' ...                             (WRONG)
TestClass<|0..99999999999>     PARSE=[0..99999999999] !! ... PARAMETER '0..99999999999' ...                          (WRONG)
```
Low severity — it walls rather than corrupting — but the diagnostic sends the reader to the wrong fix.

---

### [LOW / SILENT FALLBACK] `SpecParser.tryTypedColSpec` swallows a `ParseException` and silently degrades a malformed typed colspec

**Evidence.** `parser/SpecParser.java:2304-2323`:
```java
try {
    TypeExpression colType = parseType();
    ...
    return null;
} catch (ParseException e) {
    return null;                     // <-- a malformed TYPE becomes "not a typed colspec"
}
```
Adjacent to the assigned scope (it produces the `ColSpec.colType` the emitter reads); reported briefly
per the brief's ranking.

---

## VERIFIED SOUND

Coverage evidence. Everything below was probed and found correct.

**Type-form matrix — `a26/Matrix.java` + `a26/Wire.java` covered 32 declared type forms.**
Round-tripped correctly (source -> wire, and source -> `Type`) with no divergence:

* **All 12 primitives**, exhaustively (the complete `Type.Primitive` enum, `Type.java:74-85`):
  `Integer, Float, Decimal, String, Boolean, Byte, Date, StrictDate, DateTime, LatestDate, StrictTime,
  Number`. Each wire `fullPath` matched the source spelling; each `Type` matched the expected
  `Primitive` constant.
* **All 6 concrete multiplicity forms** `[1] [0..1] [*] [1..*] [3] [2..7]`. Wire encoding verified,
  including the `NON_NULL` omission of `upperBound` for `[*]` (`{"lowerBound":0}`) and `[1..*]`
  (`{"lowerBound":1}`), with the compiler's `Bounded[lower,upper]` matching every time.
* **Generics** `Relation<T>`, `List<Integer>`, `Pair<String,Integer>`, `ColSpec<T>` — nested
  `typeArguments` recursion correct on the wire, `GenericType[rawFqn, arguments]` correct in the
  compiler, `T` correctly classified as `TypeVar`.
* **Concrete generic multiplicity arguments** `TestClass<|1>`, `TestClass<|3..7>`, `TestClass<|*>` —
  emitted as `multiplicityArguments:[{lowerBound:..,upperBound:..}]` and preserved across resolution.
* **Function types** `{Integer[1]->String[1]}`, `{->Boolean[1]}` — classified correctly into
  `Type.FunctionType` with per-parameter multiplicities (emission is a loud wall, filed above).
* **Inline relation types** — column names, types and *declared* multiplicities round-trip; only the
  *undeclared* case diverges (filed above).
* **Schema algebra** `T+Z` (UNION), `T-Z` (DIFFERENCE) — classified into `Type.SchemaAlgebra` with the
  right `Op` (emission is a loud wall, filed above).
* **Enum / class references, inheritance, associations, derived properties, constraints, qualified
  properties with parameters** — `a26/Rich.java`: `superTypes:[{"path":"test::Base","type":"CLASS"}]`
  matched `supers = [test::Base]`; association ends `owner: test::Derived[1]` / `others: test::Other[*]`
  matched `multiplicity=[1]` / `[*]`; the constraint lambda emitted with the synthesised `$this`
  parameter at `{"lowerBound":1,"upperBound":1}` and no span, matching
  `TypedConstraint[...level=ERROR...]`; qualified property `scaled(f: Integer[1], g: ...[0..1])`
  matched parameter-for-parameter.

**`spec/ValueSpecification` sealed hierarchy — exhaustive, by reflection (`a26/Sealed.java`).**
```
ValueSpecification sealed? true
DIRECT permits count = 26
CONCRETE leaf variants = 29
intermediate sealed ifaces = [ValueSpecification, ColumnInstance, TypeAnnotation]
```
`permits` matches the file set exactly: the 26 direct names each have a file; `ColumnInstance` is
sealed over `ColSpec` + `ColSpecArray` (2 more files); `TypeAnnotation` is sealed over 3 records nested
in `TypeAnnotation.java`. The only other files in the 32-file directory are `ValueSpecification.java`
itself, `package-info.java`, and two non-`ValueSpecification` helpers (`KeyExpression.java`,
`Gql.java`). `children()` / `withChildren()` are exhaustive over all 29 leaves with **no `default`
arm** — a new variant fails compilation there, as documented.

**Literal-node backing field types (task item 5) — all 9 checked, all adequate:**

| Node | Java field | Verdict |
|---|---|---|
| `CInteger` | `Number` (Long, else BigInteger) | **adequate** — unbounded; only the negation fold narrows (filed) |
| `CFloat` | `double` | **adequate** — Pure `Float` *is* a 64-bit float; `String.valueOf(double)` is shortest-round-trip. Caveat: `Infinity` emits an invalid JSON token (filed) |
| `CDecimal` | `java.math.BigDecimal` | **SOUND — not a `double`.** Emitted via `dec.value().toString()` (`ProtocolEmitter.java:2085-2090`), so every digit survives. Probed: `3.141592653589793238462643` and `123456789012345678901234567890.5` preserved exactly |
| `CString` | `String` | adequate |
| `CBoolean` | `boolean` | adequate |
| `CDate` | `PureDateLiteral` (sealed, 7 variants; `int` components; **`String subsecond`**) | adequate — the fractional part is kept as text, so no float rounding; out-of-int years refuse cleanly at `PureDateLiteral.readDigits` |
| `CTime` | `PureTimeLiteral` (sealed, 3 variants; `String subsecond`) | adequate; `@Nullable value` with `requireValue()` guarding an out-of-range literal — an explicit throw, not a default |
| `CLatestDate` | no value | adequate |
| `CByteArray` | `String` (base64 exactly as the wire carries it) | adequate — passed through `Escapes.jsonEscape`, no re-encoding |

The top-severity hypothesis in the task ("a `double`-backed `CDecimal` or `CFloat`") is **REFUTED**:
`CDecimal` is `BigDecimal`, and `CFloat`'s `double` exactly matches Pure's `Float`.

**Escaping (`Escapes.java`) — every case in the task list probed (`a26/Esc.java`, `a26/Esc2.java`):**
```
octal escape      'x\101y'      -> WIRE "name":"xAy"           (decoded, then JSON-escaped)
unicode escape    'xAy'    -> WIRE "name":"xAy"
single quote      'x\'y'        -> WIRE "name":"x'y"
backslash         'x\\y'        -> WIRE "name":"x\\y"          (correctly re-escaped)
newline           'x\ny'        -> WIRE "name":"x\ny"
NUL               'x y'    -> WIRE "name":"x y"      (control -> \uXXXX, per RFC 8259)
non-BMP           'x<U+1F600>y' -> wire chars U+0078 U+D83D U+DE00 U+0079 (surrogate pair carried raw; valid JSON in UTF-8)
pivot separator   'v__|__agg' as a property name -> emitted verbatim, no structural effect on the protocol
string literal    'a\'b\\c\nd e' -> "value":"a'b\\c\nd e"
```
`Escapes.jsonEscape` is the single WRITE table (RFC 8259 + an uppercase-hex knob) and is used by
`ProtocolEmitter.str` (`:3343-3352`) for *every* string on the wire — no second table was found.
No injection is possible through a *string value* or a *property / column name*: all pass through
`str`. The only injection found is through **FQN segment quoting**, which never reaches `str` as a unit
(filed above).

**`catch` / `orElse` / default arms in the assigned files (task item 7) — exhaustive sweep.**
`grep -n "catch (|orElse|default ->|default:"` over the 14 assigned `protocol/*.java` files returned
**16** hits total. `ConnectionEmitters.java`, `GqlEmitter.java`, `TypeExpression.java`,
`Multiplicity.java`, `DerivedPropertyDefinition.java`, `ConstraintDefinition.java`, `Realization.java`,
`ParameterDefinition.java`, `SpanOrigin.java` have **zero**. Classification of the rest:

| Site | Class |
|---|---|
| `ProtocolEmitter.java:1077, 1094, 1648, 1789, 2190, 2310, 2915` | **loud walls** — `default -> throw`. Correct; these are the "no emit rule" refusals |
| `ProtocolEmitter.java:3051` | **misclassifying diagnostic** (filed LOW) |
| `ProtocolEmitter.java:3127` | dispatch into `foldNegation`; not a fallback |
| `ProtocolEmitter.java:3164` (`default -> null`) | benign — "operand is not a literal, do not fold" |
| `ProtocolEmitter.java:3182` (`default -> v`) | benign, unreachable (guarded by the `:3164` switch) |
| `ProtocolEmitter.java:3347` (`catch IOException`) | benign — `StringBuilder` never throws; rethrows unchecked |
| `Protocol.java:2669` | loud wall — `mangleType` refuses non-nominal heads |
| `TailEmitter.java:783, 1535, 2137`, `MappingEmitter.java:1082` | loud walls |
| `TailEmitter.java:1348` (`default -> wireKey(e)`) | benign — a key-rename table falling through to identity; not type data |
| `Escapes.java:48` | the `< 0x20 -> \uXXXX` arm; correct |
| `Escapes.java:131` | **DROP-BACKSLASH** — lossy by design, noted under the escaping finding |

On the `model/` side: `FromProtocol.java:352, 572, 656` are loud walls;
`FromProtocol.java:715` `catch (IllegalArgumentException)` rethrows as a named
`UnsupportedConnectionShape` (correct); `RelOpFromProtocol.java:167` falls through to a generic
`FunctionCall` (documented, not type data); `RelOpFromProtocol.java:184` `default -> null` is a
`@Nullable` comparison-op lookup whose caller checks. `MappingFromProtocol.java:471` is the one real
silent fallback (filed).

**Parser-side bound checking is CORRECT and was checked as a contrast.**
`TokenStreamCursor.consumeBoundedInt` (`:318-326`) refuses `> Integer.MAX_VALUE` with a positioned
error, and `parseMultiplicity` (`:1149-1181`) uses it for both bounds. This is why ordinary property
multiplicities are safe and only the `long`-typed local-mapping-property path (which uses the
unchecked `consumeLong`) corrupts.

**`TypeExpression` <-> `Type` correspondence (task item 6), complete table:**

| `TypeExpression` | `Type` produced by `TypeClassifier.classify` | Emittable to protocol? |
|---|---|---|
| `NameRef` (type param / `?`) | `TypeVar` | yes (`fullPath:"T"` / `"?"`) |
| `NameRef` (primitive FQN) | `Primitive` | yes |
| `NameRef` (class FQN) | `ClassType` | yes |
| `NameRef` (enum FQN) | `EnumType` | yes |
| `NameRef` (unknown) | throws `ModelException` (no fallback) | n/a |
| `Generic` | `GenericType(name, args)` — **`typeVariableValues` and `multiplicityArguments` dropped** | yes (the wire keeps both) |
| `FunctionType` | `Type.FunctionType` | **NO** — emitter throws |
| `RelationType` | `Type.RelationType` — **`dynamicColumns` always empty** | yes (with the `[0..1]` divergence) |
| `SchemaAlgebra` | `Type.SchemaAlgebra` | **NO** — emitter throws |
| *(none)* | **`Type.PrecisionDecimal`** — reachable only from a *relational column* type, never from a declared Pure type | **NO** — no `TypeExpression` form maps to it |

---

## NOT COVERED

* **`TailEmitter.java` (2286 lines) and `MappingEmitter.java` (1588 lines) were read only where they
  touch types** (the local-mapping-property multiplicity at `MappingEmitter:787-800, 1140-1150,
  1406-1415`; the `default` arms; the enum-mapping source values). Their service / persistence /
  dataspace emission surfaces were not audited — they carry no `Type` or `Multiplicity` data, and the
  narrowing grep over both files returned no further hits.
* **`ConnectionEmitters.java` / `AuthSpecEmitter.java`** — scanned for narrowing and fallbacks (zero
  hits for both). The `(int) s.port()` narrowing in `FromProtocol:726,752,755` was noted but not filed:
  a TCP port is bounded by its own protocol, so no realistic input reaches it.
* **`GqlEmitter.java`** — numeric handling checked (`Gql.IntValue(long)`, `FloatValue(double)`, emitted
  by direct append at `GqlEmitter.java:328-331`). A GraphQL `FloatValue` of `Infinity` would emit the
  same invalid JSON token as `CFloat`, but I did not find a Pure source path that produces one, so it
  is not filed.
* **Round trip through an actual engine JSON reader** — impossible here: no reader exists in the repo
  and no legend-engine dependency is available. All "would not survive re-reading" claims are argued
  from the emitted bytes plus the model object, never from a real deserialization.
* **`protocol/spec/AppliedFunction`, `PathLiteral`, `GraphFetchLiteral`, `NewInstance`, `Gql`**
  internals — only their `children()` / `withChildren()` participation was verified (exhaustiveness),
  not their own emission shapes; they carry expressions, not declared types.
* **The `__|__` pivot separator as a *compiler-layer* collision** (`Type.RelationType.PIVOT_SEPARATOR`,
  `Type.java:427`): a user column literally named `v__|__agg` would be matched by the pivot-template
  rule. I confirmed the *protocol* layer is unaffected (the name is emitted verbatim through `str`) but
  did not chase the compiler-side collision — it belongs to the relation / pivot auditor.
