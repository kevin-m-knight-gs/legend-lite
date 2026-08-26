# A17 — FRONTEND: type SYNTAX -> type DATA

Scope: `core/src/main/java/com/legend/lexer/**`, the type-expression paths of
`core/src/main/java/com/legend/parser/**` (the type grammar lives entirely on
`TokenStreamCursor`, an interface with default methods, plus `SpecParser`'s
literal/annotation/colspec entries), `protocol/TypeExpression.java`,
`protocol/Multiplicity.java`, `protocol/spec/TypeAnnotation.java`,
`protocol/spec/ValueSpecification.java`, `compiler/NameResolver.java`,
`compiler/SymbolTable.java`, `compiler/SynthFqn.java`, plus the first consumers
of those data (`compiler/element/TypeClassifier.java`,
`compiler/element/ModelIntegrity.java`, `compiler/spec/Typer.java`).

All probes are under
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a17/`
and were run with `/home/user/probe/jrun.sh` / `/home/user/probe/probe.sh`.
Every "Actual output" block below is pasted verbatim from a run.

---

## 0. THE TYPE GRAMMAR AS ACTUALLY IMPLEMENTED

Read off `TokenStreamCursor.parseType()` (:713-782), `parseTypeArgument()`
(:1072-1105), `parseMultiplicity()` (:1150-1183), `parseFunctionType()`
(:1189-1216), `parseRelationType()` (:1220-1230), `parseRelationColumn()`
(:1235-1253), `parseTypeVariableValues()` (:786-806), `parseQualifiedName()`
(:534-546), `fqnSegmentText()` (:573-608). Each row CONFIRMED by compiling a
model through `Compiler.compileModel` (probe `T1/T3/TL/TM/TO`).

| Form | Parses | Compiles (Phase F) | Note |
|---|---|---|---|
| `Integer String Float Decimal Boolean Date DateTime StrictDate StrictTime Number Byte LatestDate Any Nil` | yes | yes | 14/14 primitives accepted |
| `Binary` | yes | **NO** — `Unknown type: 'Binary'` (Phase=MODEL) | lexes/parses fine; not in the kind manifest |
| `meta::pure::metamodel::type::String` (FQN) | yes | yes | |
| `t::B` user FQN | yes | yes | |
| bare name + `import o::*;` | yes | yes | |
| bare undeclared name | yes | **NO** — clean Phase=MODEL error | good |
| `List<Integer>`, `Pair<String,Integer>`, `List<List<Integer>>` | yes | yes | head NOT validated — see F5 |
| `List<>` (zero args) | **NO** — `<>` lexes as `NOT_EQUAL` | — | error text mentions `NOT_EQUAL`, confusing but clean |
| `Pair<Integer,>` (trailing comma) | **NO** — clean | — | |
| `Res<\|1>` (mult args, no type args) | yes | yes | multArgs kept at parse, dropped at RESOLVE (F14) |
| `Res<String>(1,'a')` (type-variable values after `<>`) | yes | yes | tvv dropped at RESOLVE (F14) and at classify |
| `Varchar(200)`, `Numeric(10,2)`, `V('ok')` | yes | yes | precise primitives; tvv only integers/strings |
| `V(1.5)` | **NO** — clean `type variable values support integer and string literals, got FLOAT` | — | |
| `Varchar(99999999999999999999)` | **ICE** raw `NumberFormatException` | — | **F11** |
| `{String[1]->Integer[1]}` function type | yes | yes | LEGEND_ENGINE dialect refuses it by design (`refusesLiteExtensions`) |
| `{->Integer[1]}` zero-param fn type | yes | yes | |
| `{{String[1]->Integer[1]}[1]->Boolean[1]}` nested | yes | yes | |
| `{String[1]->Integer}` (no result mult) | **NO** — clean | — | result multiplicity is mandatory |
| `(a:Integer[1], b:String[*])` relation type | yes | yes | |
| `()` empty relation type | yes | yes | accepted everywhere; see F7 |
| `(a:Integer[1], a:String[1])` duplicate cols | yes | **ICE** raw `IllegalArgumentException` | **F9** |
| `(a:Integer)` (undeclared col mult) | yes | yes | defaults to `[1]` in-memory, `[0..1]` on the wire |
| `('my col':Integer[1])` quoted col name | yes | yes | |
| `(class:…)`, `(let:…)` keyword col names | yes | yes | keywords in `IDENTIFIER_TOKENS` |
| `(true:…)`, `(1:…)` | **NO** — clean | — | `TRUE`/`FALSE` deliberately excluded |
| `(?:Integer[1])` wildcard col NAME | yes | yes | stored as the literal `"?"` |
| `(a:?)` wildcard col TYPE | yes | yes | becomes `Type.TypeVar("?")` |
| `R<T+V>`, `R<T-Z>`, `R<Z⊆T>`, `R<Z=K>`, `R<T-Z+V>`, `R<Z=K⊆T>` | yes | only if `T`/`Z`/… are declared types or type params | left-leaning; EQUAL then +/- then SUBSET |
| `Mass~Kilogram` (measure~unit) | yes → one `NameRef("Mass~Kilogram")` | **NO** — clean `Unknown type` | parse-level coverage only |
| `[1] [0..1] [*] [1..*] [3] [2..7] [m]` | yes | yes | |
| `[0..0]` | yes | yes (**accepted**) | caught later only by the mapping value check |
| `[5..2]` | yes | property/derived-prop: clean error; **fn param / fn return / assoc end: silently accepted**; **relation col / fn-type slot: ICE** | **F12** |
| `[-1] [..] [1..] [] [*..*]` | **NO** — clean PARSE errors | — | |
| `[99999999999999]`, `[2147483648]` | **NO** — clean `multiplicity bound out of range` | — | `consumeBoundedInt` guards |
| `a::::b`, `::b`, `a::` (FQN edges) | **NO** — clean PARSE errors (decl and ref) | — | |
| `t::'p a c k'::A` quoted segment | yes | declaration yes, **reference never matches** | **F19** |
| `t::2_0_0::A` digit-leading segment | yes | yes | `intLeadsIdentifier()` glues INTEGER+ident |
| `t::A$B` `$` in identifier | yes | yes | `$` is an ident PART — **F4/F21** |
| unicode identifiers (`Ä`, `中`, `é`) | **NO** — `isIdentStart` is ASCII-only; non-ASCII → `INVALID` token, rejected by `rejectInvalid()` | — | clean, but a hard ASCII-only restriction |
| `@Integer`, `@my::pkg::Foo`, `@List<Integer>`, `@Relation<(a:Integer)>` | yes | yes | |
| `@?` (bare wildcard annotation) | **NO** — clean | — | matches the documented rule |
| `@Integer[1]` (annotation + mult) | **NO** — `Bracket operation is not supported` | — | |
| `~[a:Integer[1]]` typed colspec | yes | **type silently ignored** | **F16** |
| ~981 nested generics / ~919 nested relation types / ~727 nested parens | **StackOverflowError** | — | **F13** |

---

## FINDINGS

### [UNSOUND] F1. Over-precision `Decimal` literals get a `DECIMAL(38,s)` static type the runtime value violates — the value returns as a `Double`, worst case `Infinity`

`Typer.decimalType` (compiler/spec/Typer.java:3178-3185) checks only the SCALE
of a decimal literal, never its PRECISION (total significant digits):

```java
private static Type decimalType(BigDecimal value) {
    int scale = Math.max(0, value.scale());
    if (scale > Type.PrecisionDecimal.MAX_PRECISION) { throw ... }
    return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
}
```

So `1e400d` (401 significant digits, scale −400 → clamped to 0) and
`12345678901234567890123456789012345678901234d` (44 digits, scale 0) both get
`PrecisionDecimal(38, 0)`.

**Repro** (`probe.sh` over the DuckDB fixture):
```
model::Person.all()->project(~[a:p|12345678901234567890123456789012345678901234d])
model::Person.all()->project(~[a:p|1.2345678901234567890123456789012345678901d])
model::Person.all()->project(~[a:p|1e400d])
```

**Actual output**
```
[G] type=Relation<(a:Decimal(38,0)[1])> mult=[1]
[PLAN] SELECT 12345678901234567890123456789012345678901234 AS a
[EXEC-COL] a : Decimal(38,0) [PrecisionDecimal[precision=38, scale=0]] mult=[1]
[EXEC-ROW] Double(1.2345678901234568E43) |

[G] type=Relation<(a:Decimal(38,38)[1])> mult=[1]
[PLAN] SELECT 1.23456789012345678901234567890123456789 AS a
[EXEC-COL] a : Decimal(38,38) [PrecisionDecimal[precision=38, scale=38]] mult=[1]
[EXEC-ROW] Double(1.2345678901234567) |

[G] type=Relation<(a:Decimal(38,0)[1])> mult=[1]
[PLAN] SELECT 1000000...000 AS a          (401 digits)
[EXEC-COL] a : Decimal(38,0) [PrecisionDecimal[precision=38, scale=0]] mult=[1]
[EXEC-ROW] Double(Infinity) |
```

Control (in-range) — same query shape, correct behaviour:
```
model::Person.all()->project(~[a:p|1.5d])      -> [EXEC-ROW] BigDecimal(1.5)
model::Person.all()->project(~[a:p|123456789012345678901234567890d])
                                               -> [EXEC-ROW] BigInteger(1234...890)
```

**Why it matters** The compiler states `Decimal(38,0)` and the runtime hands back
a `java.lang.Double` — wrong Java kind, 17 digits instead of 38+, and in the
`1e400d` case a value (`Infinity`) that is not a decimal at all. The claim is
violated in kind, precision AND value. Top-severity: the static type is a lie the
consumer of the result will act on.
(Note: the parse-time carriers are correct — `CDecimal` holds a `BigDecimal`
(protocol/spec/CDecimal.java:16), `CInteger` holds a `Number` (Long|BigInteger),
so there is NO parse-time double truncation. The loss is at TYPE + EXECUTE.)

---

### [UNSOUND] F2. Explicit `d`-suffixed decimal literals are silently truncated — the intended "loud reject" is unreachable dead code

`Typer.java:156-175` rounds a promoted decimal down to scale 38 but claims to
keep a loud reject for an explicitly `D`-suffixed literal:

```java
// An EXPLICIT D-suffixed decimal keeps the loud reject —
// silent truncation of a declared decimal lies.
if (dv.scale() > Type.PrecisionDecimal.MAX_PRECISION
        && (lit.written() == null
                || !lit.written().toUpperCase(Locale.ROOT).endsWith("D"))) {
    dv = dv.setScale(Type.PrecisionDecimal.MAX_PRECISION, RoundingMode.HALF_EVEN);
}
```

The guard can never be false, because `SpecParser.parseDecimal`
(parser/SpecParser.java:845-851) STRIPS the suffix before storing `written`:

```java
char last = text.charAt(text.length() - 1);
if (last == 'd' || last == 'D') text = text.substring(0, text.length() - 1);
return new CDecimal(new BigDecimal(text), text, spanOf(litTok, litTok));
```

`written` therefore NEVER ends in `D`, so every over-scale decimal — declared or
promoted — takes the silent-rounding arm.

**Repro / Actual output** (probe `T9.java`)
```
1.2345678901234567890123456789012345678901d
  => TypedCDecimal[value=1.23456789012345678901234567890123456789, info=...PrecisionDecimal[precision=38, scale=38]...]
0.123456789012345678901234567890123456789012345d
  => TypedCDecimal[value=0.12345678901234567890123456789012345679, info=...scale=38...]
```
The declared 40- and 45-digit values lose their tail with no diagnostic.

**Why it matters** The repo forbids silent defaulting/coercion; the code even
names this exact rule and then implements the opposite. A user who wrote `d` to
demand exactness gets a rounded value.

---

### [UNSOUND] F3. An UNBOUND multiplicity variable escapes into the result schema and out to the caller

`TokenStreamCursor.parseMultiplicity` (:1176-1179) accepts any identifier as a
`Multiplicity.Parameter`; nothing ever checks that the name is bound. A class
declaration cannot even declare multiplicity parameters (`Class t::A<T|m>` is a
PARSE error: `expected GREATER_THAN but found PIPE`), so **every** `[m]` on a
property is necessarily free.

**Repro** — fixture model with `varProp: String[m];` on `model::Person`, mapped to
`T_PERSON.LAST_NAME`, query `model::Person.all()->project(~[v:p|$p.varProp])`.

**Actual output**
```
[G] type=Relation<(v:String[m])> mult=[1]
[G] typeRepr=...RelationType[columns=[Column[name=v, type=STRING, multiplicity=Var[name=m]]]...
[PLAN] SELECT t0.LAST_NAME AS v
[PLAN] rootType=Relation<(v:String[m])> mult=[1]
[EXEC-COL] v : String [STRING] mult=[m]
[EXEC-ROW] String(Smith) |
```

**Why it matters** `mult=[m]` is not a cardinality — no runtime value can satisfy
or violate it, so every downstream nullability/cardinality decision on that column
is undefined. It survives PARSE → RESOLVE → MODEL → G → LOWER → RENDER → EXECUTE
and is reported as the result column's Pure multiplicity. `Multiplicity` doctrine
(compiler/element/type/Multiplicity.java, "Post-G, requireBounded doctrine:
resolved expressions are always bounded") is violated.

---

### [UNSOUND / SILENT SHADOWING] F4. A user function named `<Class>$prop$<name>` silently hijacks a derived property; `<Class>$constraint$<name>` silently replaces a constraint predicate

`SynthFqn.java:17-19` asserts:
> "The `$` sigil is non-user-writable in a Pure identifier, so a synth FQN can
> never collide with a user-authored function name in the shared `findFunction`
> lookup slot."

`ModelBuilder.java:299-301` repeats it. **Both are false.** `Lexer.isIdentPart`
(lexer/Lexer.java:192-194) includes `'$'`:

```java
private static boolean isIdentPart(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9') || c == '_' || c == '$';
}
```

so `model::Person$prop$tag` lexes as one `VALID_STRING` and is a perfectly legal
function name.

**Repro** (`probe.sh`, fixture model + a derived property `tag()`):
```pure
Class model::Person { ... age: Integer[1]; tag() { 'DERIVED' }: String[1]; }
function model::Person$prop$tag(this: model::Person[1]): String[1] { 'USER-WINS' }
```
query `model::Person.all()->project(~[t:p|$p.tag()])`

**Actual output**
```
===== CONTROL (no colliding function) =====
[PLAN] SELECT 'DERIVED' AS t
[EXEC-ROW] String(DERIVED) |

===== SHADOWED =====
[PLAN] SELECT 'USER-WINS' AS t
[EXEC-ROW] String(USER-WINS) |
```

The same works for constraints (`t::A$constraint$c1`): the user's `{ false }`
body replaces the real predicate `$this.base->size() > 0`. Model-level proof
(probe `TE.java`) — `findFunction("t::A$constraint$c1")` returns the user's
`parameters=[]`/`body=[CBoolean[value=false]]` first, ahead of the synthesized one.

There is **no diagnostic at all**: compile is `OK`. `ModelIntegrity
.checkDuplicateSignatures` misses it for the reason in F18.

**Why it matters** A model author (or a malicious/careless contributor) can
override the body of any derived property or any constraint of any class in the
model, silently, from a different file. Constraint override is a correctness AND
integrity hole.

---

### [UNSOUND / SILENT FALLBACK] F5. A GENERIC head is never validated — `Zork<Integer>` becomes `GenericType[rawFqn=Zork]` and reaches the Phase-G result type

`TypeClassifier.classify` (compiler/element/TypeClassifier.java:101-107):

```java
case TypeExpression.Generic g -> {
    List<Type> args = new ArrayList<>(g.arguments().size());
    for (TypeExpression arg : g.arguments()) { args.add(classify(arg, typeParams)); }
    yield new Type.GenericType(g.name(), args);   // <- g.name() is NEVER looked up
}
```

The `NameRef` arm two lines above DOES look the name up and throws
`Unknown type: …`. So the check exists and is bypassed by writing `<…>` or `(…)`
after the name.

**Actual output** (probe `T6.java`)
```
---- phantom bare | Class t::A { p: Zork[1]; }
     ModelException Phase=MODEL: [1:1] Unknown type: 'Zork' is not a known primitive, class, or enum
---- phantom generic head | Class t::A { p: Zork<Integer>[1]; }
     OK
     t::A = TypedClass[... properties=[Stored[name=p, type=GenericType[rawFqn=Zork, arguments=[INTEGER]], ...]]]
---- phantom tvv head | Class t::A { p: Zork(200)[1]; }
     OK    ... type=GenericType[rawFqn=Zork, arguments=[]] ...
```

It travels: fixture model with `phantom: Ghost<Integer>[1];`, query
`model::Person.all()->project(~[a:p|$p.age, g:p|$p.phantom])`:
```
[G] type=Relation<(a:Integer[1], g:Ghost<Integer>[1])> mult=[1]
[G-TREE] TypedProject :: Relation<(a:Integer[1], g:Ghost<Integer>[1])>[1]
```
i.e. a fully-typed Phase-G relation column whose type does not exist. It stops
only at the mapping/store boundary (`property 'phantom' … has no binding` or
`expected Ghost<Integer>, got String`), not at type classification.

**INCONSISTENCY**: the QUERY path DOES validate the same thing —
`Typer.namedType` resolves `namedType(new NameRef(g.name()))` and throws
`unknown type 'zz::Nope' in @zz::Nope<Integer>` (probe `TB.java`). Two
implementations of one rule, disagreeing.

---

### [SILENT FALLBACK] F6. An undeclared SUPERTYPE is silently accepted and silently dropped

`ClassCompiler.compile` (compiler/element/ClassCompiler.java:38-41) takes
`TypeClassifier.headFqn(sup)` — a raw string, no lookup. `ModelIntegrity
.checkClass` (:101-137) classifies property/derived/parameter types but never
`cd.superClasses()`. `ModelIntegrity.walkSupers` (:265-273) does:

```java
try { supFqn = TypeClassifier.headFqn(sup); }
catch (ModelException e) { continue; }   // "malformed head — checkClass's classify reports it"
classifier.classDef(supFqn).ifPresent(sc -> walkSupers(sc, classifier, path, acyclic));
```
`ifPresent` = a missing supertype is a no-op, and the comment's claim that
`checkClass` reports it is false (checkClass never touches superClasses).

**Actual output** (probes `T6.java`, `TR.java`)
```
---- phantom supertype | Class t::A extends Zork { p: Integer[1]; }
     OK
     t::A = TypedClass[qualifiedName=t::A, ..., superClassFqns=[Zork], ...]
---- inherited prop from phantom | t::P.all()->project(~[n:p|$p.ghostProp])
     TypeInferenceException Phase=TYPE: class t::P has no property 'ghostProp'
```
The class carries `superClassFqns=[Zork]` forever; subsumption, layout and
equality-key walks (`ClassLayouts:116`, `EqualityKeys:169`, `ModelContext:252`,
`PureModelContext:196`) all iterate that list and silently find nothing.

**Why it matters** A typo'd or renamed supertype compiles clean and the class
just quietly loses its inheritance. Contrast: a typo'd property TYPE is a loud
`Unknown type`. Same class of mistake, opposite handling.

---

### [UNSOUND + CRASH] F7. `cast(@Relation<()>)` on a non-empty relation is accepted, lowers to `SELECT *`, and dies with an `IllegalStateException` at EXECUTE

`TypeAnnotation.RelationShape` documents "An empty column list is structurally
legal in the parser …; any rejection lives in the type-checker" — the type
checker never rejects it. `CastChecker.check` (compiler/spec/CastChecker.java:23-41)
performs NO source/target comparison at all. `Lowerer.relationCast` (:3208-3255)
computes `allKnown` by `tgtRow.columns().stream().allMatch(...)`, which is
vacuously TRUE for an empty target, so it proceeds to project zero columns.

**Repro**
```
model::Person.all()->project(~[a:p|$p.firstName])
  ->cast(@meta::pure::metamodel::relation::Relation<()>)
```
**Actual output**
```
[G] type=Relation<()> mult=[1]
[G] typeRepr=...RelationType[columns=[], dynamicColumns=[]]...
[PLAN] SELECT *
[EXEC-ERROR] java.lang.IllegalStateException: result has 5 columns but the typed schema has 0 — plan/schema mismatch
```
(`exec/Executor.java:768-771`.)

**Why it matters** Two defects in one: (a) an obviously impossible schema claim
(0 columns for a 1-column source) is accepted with zero verification; (b) the
projection is silently DISCARDED and replaced by `SELECT *`, which leaks all five
base-table columns into the plan. The execution failure is a raw
`IllegalStateException`, not a user-facing compile error.

---

### [UNSOUND] F8. A relation `cast` annotation is TRUSTED and compiled into a lossy SQL conversion — a type annotation changes the DATA

`CastPolicy.lower` (lowering/CastPolicy.java:70-95) states the divergence itself:
> `// DELIBERATE divergence: pure's cast never converts; the corpus contract`
> `// (engine-lite lineage) is SQL-style conversion, so a NARROWING cast converts here.`

**Repro / Actual output**
```
model::Person.all()->project(~[a:p|$p.age / 4])
  ->cast(@meta::pure::metamodel::relation::Relation<(a:Integer[1])>)

[G] type=Relation<(a:Integer[1])> mult=[1]
[PLAN] SELECT CAST(((1.0 * t0.AGE_VAL) / 4) AS BIGINT) AS a
[EXEC-ROW] Long(8) |     <- 30/4 = 7.5, returned as 8
[EXEC-ROW] Long(7) |
[EXEC-ROW] Long(11) |    <- 45/4 = 11.25, returned as 11
```
and, where the conversion cannot succeed, the failure surfaces as a raw JDBC
error rather than a compile error:
```
->cast(@…Relation<(a:Integer[1])>)  over a String column
[PLAN] SELECT CAST(t0.FIRST_NAME AS BIGINT) AS a
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Could not convert string 'John' to INT64 …
```

**Why it matters** This is the `TypeAnnotation` trust channel the brief asks
about, answered concretely: the compiler does not verify the annotation against
the inferred type — it obeys it and rewrites the data to match. `7.5` silently
becomes `8`. Declared-in-a-comment does not make it sound.

---

### [CRASH/ICE] F9. Duplicate column names in a relation type escape as a raw `IllegalArgumentException` — three reachable sites

`compiler/element/type/Type.java:520-534`:
```java
public RelationType {
    ...
    for (Column c : columns) {
        if (!seen.add(c.name())) {
            throw new IllegalArgumentException("duplicate column '" + c.name() + "' in relation type");
        }
    }
```
Nothing pre-checks on the paths below, so the record-constructor guard IS the
error path.

**Actual output** (probe `T2.java`, stacks truncated to the informative frames)
```
---- relation dup cols in property  |  Class t::A { p: (a:Integer[1], a:String[1])[1]; }
     java.lang.IllegalArgumentException <<ICE>>: duplicate column 'a' in relation type
        at com.legend.compiler.element.type.Type$RelationType.<init>(Type.java:533)
        at com.legend.compiler.element.TypeClassifier.classify(TypeClassifier.java:122)
        at com.legend.compiler.element.ModelIntegrity.checkClass(ModelIntegrity.java:112)
---- relation dup cols in fn return  |  function t::f(): (a:Integer[1], a:String[1])[1] { [] }
     java.lang.IllegalArgumentException <<ICE>>: duplicate column 'a' in relation type
        at com.legend.compiler.element.ModelIntegrity.checkFunction(ModelIntegrity.java:167)
---- Q cast to dup relation | …->cast(@…Relation<(a:Integer, a:String)>)
     java.lang.IllegalArgumentException <<ICE>>: duplicate column 'a' in relation type
        at com.legend.compiler.spec.Typer.relationShapeType(Typer.java:3106)
        at com.legend.compiler.spec.CastChecker.check(CastChecker.java:24)
```

**INCONSISTENCY** — the *same rule* on the colspec path is a clean user error:
```
t::P.all()->project(~[a:p|$p.name])->select(~[a,a])
  com.legend.compiler.spec.SchemaInvariantException Phase=TYPE: duplicate column 'a' in ~[…]
```
`SchemaInvariantException extends TypeInferenceException extends
LegendCompileException`. Two implementations of one rule; one clean, one an ICE.

---

### [CRASH/ICE] F10. Any float literal that overflows `double` (`1e309` and up) escapes as a raw `NumberFormatException` from the PARSER

`SpecParser.parseFloat` (parser/SpecParser.java:825-836):
```java
double d = Double.parseDouble(text);          // -> Infinity for 1e309+
if (!dialect().refusesLiteExtensions()) {
    BigDecimal exact = new BigDecimal(text);
    if (exact.compareTo(BigDecimal.valueOf(d)) != 0) {   // BigDecimal.valueOf(Infinity) THROWS
```

**Actual output** (probe `T8.java`) — reproduces from BOTH entry points:
```
---- 1e309
     <<ICE>> java.lang.NumberFormatException: Character I is neither a decimal digit number, decimal point, nor "e" notation exponential mark.
        at java.base/java.math.BigDecimal.valueOf(BigDecimal.java:1371)
        at com.legend.parser.SpecParser.parseFloat(SpecParser.java:832)
        at com.legend.parser.SpecParser.parsePrimary(SpecParser.java:701)
---- MODEL function t::f(): Float[1] { 1e400 }
     <<ICE>> java.lang.NumberFormatException: Character I is neither a decimal digit number, decimal point, nor "e" notation exponential mark.
        at com.legend.parser.SpecParser.parseFloat(SpecParser.java:832)
```
also `-1e400`, `[1e400]`, `^t::A(p=1e400)`.

**Why it matters** A plainly writable literal produces an internal exception with
a message about the character `I` in `Infinity` — no line, no column, no phase.
The neighbouring `1e-400` path is fine (promotes to `CDecimal`), so the whole
failure is the missing `Double.isFinite(d)` guard.

---

### [CRASH/ICE] F11. Type-variable value overflow (`Varchar(99999999999999999999)`) escapes as a raw `NumberFormatException`

`TokenStreamCursor.parseTypeVariableValues` (:794-797):
```java
if (peek() == TokenType.INTEGER) {
    tvv.add(new CInteger(Long.parseLong(text()), spanOf(pos(), pos())));
```
No guard. The SAME FILE guards the same conversion 450 lines above
(`consumeLong`, :331-341: `catch (NumberFormatException overflow) { throw
error("integer literal out of range: '" + t + "'"); }`) — an internal
inconsistency, and the class javadoc for that guard says exactly why it exists.

**Actual output** (probe `TM.java`)
```
---- tvv overflowing long | Class t::A { p: Varchar(99999999999999999999)[1]; }
     <<ICE>> java.lang.NumberFormatException: For input string: "99999999999999999999"
        at java.base/java.lang.Long.parseLong(Long.java:709)
        at com.legend.parser.TokenStreamCursor.parseTypeVariableValues(TokenStreamCursor.java:796)
        at com.legend.parser.TokenStreamCursor.parseType(TokenStreamCursor.java:739)
```

---

### [CRASH/ICE + SILENT ACCEPT] F12. `[5..2]` (lower > upper): checked in 3 positions, silently ACCEPTED in 3, an ICE in 4

`protocol/Multiplicity.Concrete` deliberately drops the `upper >= lower`
invariant (protocol/Multiplicity.java:66-74: *"bound sanity is the compiler's"*),
and `TokenStreamCursor.parseMultiplicity` (:1163-1166) repeats the decision. The
compiler-side invariant lives in `compiler/element/type/Multiplicity.Bounded`
(:145-155, throws `IllegalArgumentException`), but `ModelIntegrity
.requireValidBounds` (:216-226) — the only place that converts that IAE into a
clean `ModelException` — is called from exactly THREE sites (:113, :118, :122):
class property, derived property, derived-property parameter.

**Actual output** (probe `TJ.java`)
```
---- prop [5..2] (checked)      -> ModelException Phase=MODEL: property 'p' of t::A: invalid multiplicity — upper (2) must be >= lower (5)
---- derived prop [5..2]        -> ModelException Phase=MODEL: derived property 'p' of t::A: …
---- fn param [5..2]            -> OK                       <-- silently accepted
---- fn return [5..2]           -> OK                       <-- silently accepted
---- assoc end [5..2]           -> OK                       <-- silently accepted
---- relation col [5..2]        -> <<ICE>> java.lang.IllegalArgumentException: upper (2) must be >= lower (5)
        at com.legend.compiler.element.type.Multiplicity$Bounded.<init>(Multiplicity.java:151)
        at com.legend.compiler.element.TypeClassifier.classify(TypeClassifier.java:120)
---- fn-type param [5..2]       -> <<ICE>> ... TypeClassifier.classify(TypeClassifier.java:111)
---- fn-type result [5..2]      -> <<ICE>> ... TypeClassifier.classify(TypeClassifier.java:114)
---- Q cast relation col [5..2] -> <<ICE>> ... Typer.relationShapeType(Typer.java:3103)
---- Q lambda annot x:t::A[5..2]-> OK                       <-- silently accepted
```
`[0..0]` is accepted everywhere at model time (only the mapping value check
later notices: `declares multiplicity Bounded[lower=0, upper=0] but the value has
Bounded[lower=1, upper=1]`).

---

### [CRASH] F13. `StackOverflowError` on deeply nested type syntax (~981 generics, ~919 relation types, ~727 parens)

Every type sub-grammar recurses without a depth budget
(`parseType` → `parseTypeArgument` → `parseType`, :759/:1086).

**Actual output** (probe `TK.java`, bisected thresholds; the 200 levels the brief
asks about parse FINE)
```
generic nesting SOE threshold  ~ 981
relation nesting SOE threshold ~ 919
paren-expr SOE threshold       ~ 727
```
and the escaping form at depth 5000:
```
java.lang.StackOverflowError <<ICE>>: null
   at com.legend.parser.TokenStreamCursor.parseQualifiedName(TokenStreamCursor.java:535)
   at com.legend.parser.TokenStreamCursor.parseType(TokenStreamCursor.java:725)
   at com.legend.parser.TokenStreamCursor.parseTypeArgument(TokenStreamCursor.java:1086)
```
Low-likelihood by hand, reachable from machine-generated or hostile input; an
`Error`, so it also bypasses every `catch (Exception)` recovery in the pipeline.

---

### [INFORMATION LOSS] F14. `NameResolver` silently DROPS `Generic.multiplicityArguments`, `Generic.typeVariableValues` and every `pos`

`compiler/NameResolver.java:452-457`:
```java
case TypeExpression.Generic g -> {
    String r = resolveName(g.name(), scope);
    List<TypeExpression> args = resolveTypeList(g.arguments(), scope);
    yield (r.equals(g.name()) && args == g.arguments()) ? g
            : new TypeExpression.Generic(r, args);      // 2-ARG ctor: multArgs=[], tvv=[], pos=null
}
```
The 2-argument convenience constructor (protocol/TypeExpression.java:127-129)
sets `multiplicityArguments = List.of()`, `typeVariableValues = List.of()`,
`pos = null`. Whenever the head resolves OR any argument resolves, all three are lost.

**Actual output** (probes `T4.java`, `T5.java`)
```
PARSED2  : Generic[name=meta::…::Pair, arguments=[NameRef[String]], multiplicityArguments=[1], typeVariableValues=[], pos=…]
RESOLVED2: Generic[name=meta::…::Pair, arguments=[NameRef[meta::…::String]], multiplicityArguments=[], typeVariableValues=[], pos=null]

PARSED  (import o::*; Class o::Varchar {}; p: Varchar(200)[1])
         Generic[name=Varchar, …, typeVariableValues=[CInteger[value=200,…]], pos=…]
RESOLVED Generic[name=o::Varchar, …, typeVariableValues=[], pos=null]
```
`typeVariableValues` is the wire's precision/scale carrier
(`ProtocolEmitter.genericType`, protocol/ProtocolEmitter.java:1758-1759), so a
`Varchar(200)` that resolves loses its `200` entirely.

---

### [INFORMATION LOSS] F15. `NameResolver` flips `Column.multiplicityDeclared` to `false` — on the wire that turns a declared `[*]` into `[0..1]`

`compiler/NameResolver.java:486-491` rebuilds a relation column with the 3-arg
constructor, which sets `multiplicityDeclared=false, pos=null`
(protocol/TypeExpression.java:218-220).

**Actual output** (probe `T4.java`)
```
PARSED4  : RelationType[columns=[Column[name=c, type=NameRef[Zz], multiplicity=[*], multiplicityDeclared=true,  pos=SourceInfo[...]]]]
RESOLVED4: RelationType[columns=[Column[name=c, type=NameRef[o::Zz], multiplicity=[*], multiplicityDeclared=false, pos=null]]]
```
`ProtocolEmitter.genericType` (:1770-1785) branches on exactly that flag:
```java
if (col.multiplicityDeclared()) { multiplicity(b, col.multiplicity()); }
else { b.append("{\"lowerBound\":0,\"upperBound\":1}"); }
...
srcInfo(b, requirePos(col.pos(), "relation column " + col.name()));
```
so an emit of a resolved model would (a) publish `[0..1]` for a source-declared
`[*]` and (b) hit `requirePos`'s `UnsupportedOperationException` on the now-null
position. The emitter is today only driven from the PARSE side
(`ElementParser:506`, `PmcdParser:526/655`, the `parser/section/*` grammars), so
this is latent rather than live — but the two halves of the pipeline disagree
about the same record's meaning.

---

### [INFORMATION LOSS / SILENT DROP] F16. A colspec type annotation `~[a:Integer[1]]` is parsed, stored, and then never read by the type checker

`ColSpec.colType`/`colTypeMult` (protocol/spec/ColSpec.java:64-65) are populated by
`SpecParser.tryTypedColSpec` (:2303-2322). Their ONLY consumers repo-wide are
`ProtocolEmitter:2994` (wire) and `SourceSubst:136` (copy). `Typer.typedColSpec`
(compiler/spec/Typer.java:3118-3126) and `typedColSpecArray` (:3129-3162) both
discard it and substitute `unknownColumn(name)`:
```java
Type row = new Type.RelationType(List.of(unknownColumn(cs.name())));
```

**Repro / Actual output**
```
model::Person.all()->project(~[a:p|$p.firstName])->select(~[a:Integer[1]])

[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS a
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(John) |
```
The user wrote `Integer[1]`; the compiler neither honours it nor rejects it. A
type annotation that is silently ignored is the worst of both worlds — it reads
as a checked declaration and is neither.

---

### [SILENT FALLBACK] F17. `tryTypedColSpec` swallows `ParseException`, so a malformed type annotation is silently reinterpreted as an EXPRESSION

`parser/SpecParser.java:2321-2323`:
```java
} catch (ParseException e) {
    return null;
}
```
The caller then re-parses the same tokens as a mapped colspec.

**Actual output** (probe `TN.java`) — `~[a:List<Int]` (unbalanced `<`) becomes a
column whose value is the boolean comparison `List < Int`:
```
ColSpec[name=a, function1=LambdaFunction[parameters=[], body=[
  AppliedFunction[function=lessThan, parameters=[
    PackageableElementPtr[fullPath=List], PackageableElementPtr[fullPath=Int]],
    …, infix=true]]], …, colType=null, colTypeMult=null]
```
The real error ("expected GREATER_THAN…") is destroyed; the user gets a wrong
program instead of a diagnostic. `looksLikeColumnType()` (:2255-2301) tolerates
the unbalanced bracket (its depth loop simply runs to end-of-stream and returns
`true`), so the swallow is on the main path, not an edge.

---

### [INCONSISTENCY] F18. `Function.signatureKey()` embeds `SourceInfo` via `TypeExpression.toString()` — the duplicate-signature guard is defeated by source position

`model/Function.java:51-65`:
```java
StringBuilder key = new StringBuilder(qualifiedName()).append('(');
for (var p : parameters()) {
    key.append(p.type()).append(':').append(p.multiplicity()).append(',');
}
```
`p.type()` is a `TypeExpression`; `NameRef` overrides `equals`/`hashCode` to
exclude `pos` (protocol/TypeExpression.java:83-91) but keeps the RECORD
`toString`, which prints `pos`. Two identical declarations on different lines
therefore produce different keys and `ModelIntegrity.checkDuplicateSignatures`
(:145-160) never fires.

**Actual output** (probe `TG.java`)
```
---- DUP with 0 params (control)
   function t::f(): String[1] { 'ONE' } ; function t::f(): String[1] { 'TWO' }
   ModelException Phase=MODEL: function 't::f' is defined more than once with the same signature — calls would be ambiguous
---- DUP with 1 user-class param
   Class t::A {} ; function t::f(a: t::A[1]) {'ONE'} ; function t::f(a: t::A[1]) {'TWO'}
   OK (NO duplicate error)
   findFunction(t::f).size=2
      body=[CString[value=ONE, …]]
      body=[CString[value=TWO, …]]
---- DUP with 1 primitive param  -> caught (NameResolver rewrote String->FQN, dropping pos on BOTH)
---- DUP with 1 param, one spelled bare (import) one FQN -> OK (NO duplicate error)
```
Whether the guard works depends on whether `NameResolver` happened to rewrite the
parameter type (and so happened to null the position) — i.e. on F14/F15's
information loss. This is also the mechanism by which the F4 synth-vs-user
collision escapes detection.

---

### [INCONSISTENCY] F19. A quoted FQN segment makes an element DECLARABLE but permanently UNREFERENCEABLE

`TokenStreamCursor.fqnSegmentText` (:573-582) keeps the quotes on a REFERENCE
("a REFERENCE keeps the raw quoted spelling … DECLARATION names unquote in
Protocol.splitFqn"). The two spellings never meet.

**Actual output** (probes `TO.java`, `TP.java`)
```
DECLARED FQN: [t::p a c k::A]
   (from source `Class t::'p a c k'::A { z: String[1]; }`)
prop type in `Class t::B { p: t::'p a c k'::A[1]; }`:
   NameRef[name=t::'p a c k'::A, …]

Class t::'p a c k'::A {...}  +  Class t::B { p: t::'p a c k'::A[1]; }
  -> ModelException Phase=MODEL: Unknown type: 't::'p a c k'::A' is not a known primitive, class, or enum
Class t::'p a c k'::A {...}  +  Class t::B { p: t::p a c k::A[1]; }
  -> ParseException Phase=PARSE: expected BRACKET_OPEN but found VALID_STRING ('a')
```
Neither spelling reaches the declaration; the class can never be used.

---

### [INCONSISTENCY / UNSOUND-ADJACENT] F20. The static type of an unsuffixed decimal-point literal depends on its DIGIT COUNT — `1.0` is `Float`, `1.0000000000000001` is `Decimal`

`SpecParser.parseFloat` (:825-836) promotes any FLOAT-token literal to `CDecimal`
when the `double` round-trip is not exact (in LEGEND_LITE and LEGEND_PLATFORM;
only LEGEND_ENGINE keeps `CFloat` unconditionally).

**Actual output** (probes `T7.java`, `T8.java`)
```
`1.0`                 LEX FLOAT   SPEC CFloat[1.0]        GTYPE FLOAT
`1.0000000000000001`  LEX FLOAT   SPEC CDecimal[…]        GTYPE PrecisionDecimal[38,16]
`1.2345678901234567890123456789012345678901`
                      LEX FLOAT   SPEC CDecimal[…]        GTYPE PrecisionDecimal[38,38]
`1e-400`              LEX FLOAT   SPEC CDecimal[1E-400]   GTYPE PrecisionDecimal[38,38]
1.0 + 1.0                       -> FLOAT
1.0000000000000001 + 1.0        -> NUMBER
```
Two source texts of the same lexical shape get different Pure types, and the
propagated type of an arithmetic expression flips from `Float` to `Number`
purely because a literal has more digits. Real Pure / engine (per the code's own
comment) types the unsuffixed form `Float` unconditionally; a `d` suffix is how a
user asks for `Decimal`. Whatever the intent, "the type depends on the digit
count" is a rule no user can predict.

---

### [DOC-LIE] F21. "The `$` sigil is non-user-writable in a Pure identifier"

`compiler/SynthFqn.java:17-19` (and :61-62, :98), and `compiler/ModelBuilder.java
:299-301` ("Lifted FQNs use the reserved '$' sigil, so they cannot collide with a
user-writable name in findFunction"). Falsified by `lexer/Lexer.java:192-194`
(`isIdentPart` includes `'$'`) and by the working repro in F4:
`function t::A$prop$foo(): String[1] { 'USER' }` compiles `OK`.

---

### [DOC-LIE] F22. "An EXPLICIT D-suffixed decimal keeps the loud reject"

`compiler/spec/Typer.java:163-165`. Unreachable — see F2.

---

### [DOC-LIE] F23. "malformed head — checkClass's classify reports it"

`compiler/element/ModelIntegrity.java:269`. `checkClass` (:101-137) never
classifies `cd.superClasses()`; the swallowed `ModelException` is reported
nowhere. See F6.

---

## VERIFIED SOUND

Checked and found correct (each run, not read):

1. **Ambiguous simple type name across two wildcard imports is a clean,
   DETERMINISTIC error.** `NameResolver.resolveName` (:514-522) throws
   `ResolutionException` on `matches.size() > 1`; `ImportScope.wildcards` is a
   `List` (model/ImportScope.java:30, 51, 65-67) preserving declaration order and
   de-duplicated on `add`, so the candidate list is order-stable. **10 runs each,
   both import orders** (probe `TA.java`): identical message every time —
   `ambiguous reference 'Thing' — matches via imports: [p1::Thing, p2::Thing].
   Use a fully qualified name.` (and `[p2::Thing, p1::Thing]` with the imports
   reversed — the ORDER of the listed candidates follows source order, the
   VERDICT does not vary). No nondeterminism found; `SymbolTable` uses a
   `HashMap` only for FQN→id interning, which never feeds a resolution decision.
2. **A bare unresolved NAME does NOT become a phantom `ClassType`.**
   `TypeClassifier.classify`'s `NameRef` arm (:98-99) throws
   `Unknown type: 'Zork' is not a known primitive, class, or enum`
   (Phase=MODEL). Verified for: property type, nested generic ARGUMENT
   (`List<Zork>`), function-type parameter, relation-type column type.
   The query side agrees (`unknown type 'zz::Nope' in @zz::Nope`, Phase=TYPE) and
   `zz::Nope.all()` is a clean `ResolutionException`. (The GENERIC HEAD is the
   one hole — F5.)
3. **`SymbolTable`** (compiler/SymbolTable.java) is a correct monotonic interner:
   `intern` is idempotent, `resolveId` never allocates, `nameOf` bounds-checks and
   throws `IndexOutOfBoundsException` rather than returning null. No defect found.
4. **Multiplicity bound overflow** is a clean, positioned refusal:
   `[99999999999999]` and `[2147483648]` both →
   `ParseException Phase=PARSE: multiplicity bound out of range: …` via
   `consumeBoundedInt`/`consumeLong` (TokenStreamCursor:319-341).
5. **FQN edge cases are clean PARSE errors** in BOTH declaration and reference
   position, exhaustively: `a::::b`, `::b`, `a::` →
   `expected identifier after '::' in qualified name` / `expected type name, got
   PATH_SEPARATOR`.
6. **Unbalanced generic brackets** (`List<Integer[1]`, `List Integer>`,
   `List<Integer>>`, bare `<`) are all clean positioned PARSE errors.
7. **`CDecimal` carries `BigDecimal`, `CInteger` carries `Number`
   (Long|BigInteger), `CString` carries the escape-decoded String** — there is NO
   parse-time narrowing of a 40-digit decimal or a 30-digit integer into a
   `double`. Verified by reading the records and by
   `123456789012345678901234567890` → `CInteger[value=…890]` → executes back as
   `BigInteger(123456789012345678901234567890)`.
8. **String escapes** decode through one shared table
   (`TokenStreamCursor.unescapeBody` → `Escapes.unescapeJavaLike`), including
   `\uXXXX` and unknown-escape passthrough: `'a\tb'`→`a<TAB>b`,
   `'a\u0041b'`→`aAb`, `'a\qb'`→`aqb`. Identifier-quoting and literal-quoting
   use the same decoder.
9. **Date/time literal classification**: `%2020-01-01` → `CDate` → `STRICT_DATE`;
   `%12:34:56` → `CTime[TimeWithSecond]` → `STRICT_TIME`; `%latest` →
   `CLatestDate` → `LATEST_DATE`. A malformed timezone
   (`%2020-01-01T00:00:00.123456789+05:30`) is a clean positioned PARSE error
   (`timezone offset must be exactly 4 digits (HHMM)`), matching the grammar
   `TimeZone: 'Z' | (+|-)DDDD`.
10. **Non-Pure numeric spellings** are rejected, not silently mis-lexed:
    `1_000` lexes `INTEGER('1') VALID_STRING('_000')` and `0x10` lexes
    `INTEGER('0') VALID_STRING('x10')`; both die as
    `trailing tokens after expression`. `5.` lexes `INTEGER DOT` and fails as
    `expected property name after '.'`.
11. **Duplicate colspec names** (`~[a,a]`) are a clean `SchemaInvariantException`
    (Phase=TYPE) — the correct handling that F9's relation-type path lacks.
12. **`INVALID` tokens never flow into a parse**: `rejectInvalid()` is wired into
    `advance()`/`expect()`/`consume()` (TokenStreamCursor:216-224, 241-253); a
    non-ASCII identifier char surfaces as
    `expected identifier, got INVALID`.
13. **Schema-algebra precedence** matches the documented rule
    (`parseTypeArgument`, :1072-1105): `R<T-Z+V>` →
    `SchemaAlgebra(SchemaAlgebra(T,DIFFERENCE,Z),UNION,V)` (left-leaning) and
    `R<Z=K⊆T>` → `SchemaAlgebra(SchemaAlgebra(Z,EQUAL,K),SUBSET,T)` (EQUAL binds
    tightest, SUBSET last). Verified structurally on the parsed AST.
14. **The `[0..0]` and `[5..2]` values that DO reach the mapping value check are
    caught cleanly** (`declares multiplicity Bounded[lower=0, upper=0] but the
    value has Bounded[lower=1, upper=1]`, Phase=TYPE).
15. **Nested generics at the depth the brief names (200) parse and compile
    fine** — the crash threshold is ~981 (F13).

---

## NOT COVERED

- **`MappingProtocolParser` (3481 lines) and `DatabaseProtocolParser` (1128) type
  paths.** Their type syntax (relational column DDL types, `~mainTable`, join
  pointers) is a different, store-side type language; A17's scope is the Pure
  type-expression front. Not audited.
- **`GqlParser` / `PmcdParser` / `IslandScan` / `ServiceStubDataParser`.** Islands
  and the protocol-JSON front-door were out of the assigned reading list; the
  `INVALID`-token trap was verified but island content typing was not.
- **The `LEGEND_ENGINE` dialect variant of literal typing.** `parseFloat`'s
  dialect split means the ENGINE surface builds `CFloat` unconditionally; I only
  exercised the default (`Compiler.compileModel`/`compileQuery`) path, which is
  LEGEND_LITE/PLATFORM. F20's severity on the ENGINE wire is therefore untested.
- **Whether `ProtocolEmitter` is reachable with a POST-NameResolver model.** F15's
  wire consequence (`[*]` → `[0..1]`, `requirePos` throw) is proven at the record
  level but I found no live call path that emits resolved elements; every
  `ProtocolEmitter.emit*` caller I traced is parse-side. Reported as latent.
- **Decimal/Float DECODE symmetry in `exec/`** beyond what F1 needed. Whether the
  `Double`-instead-of-`BigDecimal` return in F1 originates in DuckDB's inferred
  column type or in the JDBC decoder is another auditor's boundary; I report only
  the compile-time claim vs the observed runtime object.
- **`Realization` / Door-4 constraint-reference bindings.** F4 was proven for the
  INLINE (sugar) form; the `Realization` ref form short-circuits lifting
  (`ModelNormalizer:288`) and was not separately attacked.
- **Multi-source module compiles (`Compiler.parseSources` + `wallSink` tolerant
  mode).** All probes used single-source `compileModel`; the POISON-NOT-DROP path
  in `NameResolver.resolve` (:212-224) was read but not exercised.
