# A07 — `builtin/Pure.java` native registry: data audit

Scope: `core/src/main/java/com/legend/builtin/Pure.java` (2266 lines) plus
`compiler/element/{PureModelContext,ModelIntegrity,FunctionCompiler,TypedFunction,Property,TypeClassifier,ClassCompiler}.java`
and `compiler/ModelBuilder.java` (there is no `compiler/element/ModelBuilder.java`; the real file is
`core/src/main/java/com/legend/compiler/ModelBuilder.java`).

Everything below was extracted programmatically by reflecting over the loaded `Pure` class, or produced by
running a query through the real pipeline. Probe sources live in
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/p/`.
The complete machine-readable registry dump with FULL FQNs is at
`/home/user/audit/findings/A07-pure-registry.dump.tsv`; an abbreviated copy is inlined in Appendix A.

## Registry census (exact, programmatic)

| quantity | count |
|---|---|
| native classes (`Pure.allNativeClasses()`) | **200** |
| native enumerations (`Pure.allNativeEnums()`) | **19** |
| native function OVERLOADS (`Pure.all()`) | **721** |
| distinct native function FQNs | **431** |
| native classes with ≥1 type parameter | 24 |
| distinct type FQNs referenced anywhere in the registry | 150 |
| distinct `signatureKey()`s over the 721 overloads | **721** (no duplicate) |
| resolvable names (bare + FQN) with >1 overload | 254 |
| bare names whose user-visible overload set spans >1 package | 22 |
| platform-OWNED function FQNs (user redefinition suppressed) | 18 |
| user-EXTENDABLE native function FQNs | **413** |

Probe: `p/Dump.java`, `p/Dump2.java`, `p/Validate2.java`, `p/CrossPkg.java`, `p/Hygiene.java`.

```
$ jrun.sh p/Dump.java | head -1
#COUNTS classes=200 enums=19 functions=721

$ jrun.sh p/Hygiene.java
dup enum values: 0
dup native class members: 0
dup param names: 0
native function FQNs: 431 ; platform-OWNED (user defs suppressed): 18 ; user-EXTENDABLE: 413
```

---

## FINDINGS

### [UNSOUND] 21 native signatures (16 distinct function names) declare result multiplicity `[1]` but return NOTHING on an empty input — verified by execution

**Evidence.** `Pure.java` declares these reductions with an exactly-one result over a possibly-empty `[*]` domain:

| Pure.java line | signature |
|---|---|
| 2187–2189 | `math::sum(numbers:Float[*]):Float[1]` / `(Integer[*]):Integer[1]` / `(Number[*]):Number[1]` |
| 1180 | `math::average(numbers:Number[*]):Float[1]` |
| 1894 | `math::median(numbers:Number[*]):Float[1]` |
| 1919–1921 | `math::mode(numbers:Integer[*]):Integer[1]` / `(Float[*])` / `(Number[*])` |
| 2237, 2238, 2239, 2244 | `math::variancePopulation` / `varianceSample` / `variance(numbers:Number[*])` / `variance(numbers:Number[*], isSample:Boolean[1])` → `Number[1]` |
| 2175–2178 | `math::stdDevPopulation/stdDevSample/stdDev(numbers:Number[*]):Number[1]` |
| 1987 | `math::percentile(numbers:Number[*], p:Number[1]):Number[1]` |
| 1178 | `collection::at<T>(set:T[*], index:Integer[1]):T[1]` |
| 1899 / 2001 / 2208 | `math::minus<T>(values:T[*]):T[1]`, `math::plus<T>(values:T[*]):T[1]`, `math::times<T>(values:T[*]):T[1]` |
| 1893 | `math::mean(numbers:Number[*]):Float[1]` |

The registry is INTERNALLY INCONSISTENT about this: the sibling reductions get it right —
`max(values:Integer[*]):Integer[0..1]` (line 1888), `min(...):Integer[0..1]` (1913),
`percentile(numbers,p,ascending,continuous):Number[0..1]` (1988) — and so does the WINDOW form
`stdDev<T>(w,f,r):T[0..1]` (2180) and `variance<T>(w,f,r):T[0..1]` (2243).
So within one function family (`percentile`, `stdDev`, `variance`) one overload is `[0..1]` and its sibling is `[1]`.

**Repro 1 — scalar, empty extent.**
```
$ echo "model::Person.all()->filter(p|\$p.age > 200)->map(p|\$p.age)->sum()" > /tmp/q.pure
$ probe.sh /home/user/probe/fx/model.pure /tmp/q.pure test::TestRuntime /home/user/probe/fx/ddl.sql
```
Actual output (one line each per function, same fixture, `->filter(p|$p.age>200)` makes the extent empty):
```
sum                  [G] type=Integer mult=[1]    [EXEC-ROW] null |
average              [G] type=Float mult=[1]      [EXEC-ROW] null |
median               [G] type=Float mult=[1]      [EXEC-ROW] null |
mode                 [G] type=Integer mult=[1]    [EXEC-ROW] null |
variance             [G] type=Number mult=[1]     [EXEC-ROW] null |
varianceSample       [G] type=Number mult=[1]     [EXEC-ROW] null |
variancePopulation   [G] type=Number mult=[1]     [EXEC-ROW] null |
stdDev               [G] type=Number mult=[1]     [EXEC-ROW] null |
stdDevSample         [G] type=Number mult=[1]     [EXEC-ROW] null |
stdDevPopulation     [G] type=Number mult=[1]     [EXEC-ROW] null |
```
and (model `/tmp/aud/model.pure`, empty collection built with `filter(e|$e.name=='zzzz')`):
```
mean                 | type=Float mult=[1]            | null
minus                | type=Integer mult=[1]          | null
plus                 | type=Integer mult=[1]          | null
times                | type=Integer mult=[1]          | null
percentile(0.5)      | type=Number mult=[1]           | null
at(0)                | type=Integer mult=[1]          | null
```
Correct siblings, same harness, same run: `count → Long(0)`, `size → Long(0)`, `isEmpty → Boolean(true)`,
`isNotEmpty → Boolean(false)`, `makeString → String()`, `max/min → mult=[0..1]`.

**Repro 2 — the same defect reaches a TABULAR result envelope as a NOT-NULL column with a null cell.**
Model `/tmp/aud/model.pure` (`Emp.salary : Integer[0..1]` over a nullable `SALARY` column; row `b` has `SALARY = NULL`).
```
$ echo "model::Emp.all()->project(~[n:e|\$e.name, s:e|\$e.salary])->groupBy(~[n],~[tot:x|\$x.s:y|\$y->sum()])" > qq.pure
$ probe.sh /tmp/aud/model.pure qq.pure test::R /tmp/aud/ddl.sql
[G] type=Relation<(n:String[1], tot:Integer[1])> mult=[1]
[PLAN] SELECT t0.NAME AS n, SUM(t0.SALARY) AS tot
[EXEC-COL] n : String [STRING] mult=[1]
[EXEC-COL] tot : Integer [INTEGER] mult=[1]
[EXEC-ROW] String(a) | BigInteger(100) |
[EXEC-ROW] String(b) | null |
[EXEC-ROW] String(c) | BigInteger(300) |
```
Contrast `max()` in the identical query, which declares the column `[0..1]` and is therefore sound:
```
[EXEC-COL] m : Integer [INTEGER] mult=[0..1]
[EXEC-ROW] String(b) | null |
```
All 10 aggregates were run through this groupBy harness; every one declared `mult=[1]` and delivered `null` for row `b`.

(20 of the 21 were executed; `variance(numbers, isSample)` at 2244 is the same shape and was not separately run.)

**Why it matters.** `[1]` is the compiler's promise that the value exists. A consumer that trusts it
(wire serializer, a `[1]`-typed downstream parameter slot, a non-null column contract, an `->toOne()`-free
chain) receives `null`. This is the registry's declaration, one string in `Pure.java`, being wrong —
the fix is a one-character edit per line (`[1]` → `[0..1]`) and the pipeline already handles `[0..1]` correctly
(demonstrated by `max`/`min`).

---

### [UNSOUND] `collection::at()`'s bounds guard is defeated by NULL propagation on an EMPTY collection — silent `null` where an error was intended

**Evidence.** `Pure.java:1178`:
```java
public static final NativeFunctionDefinition AT__T_MANY__INTEGER_1 =
    signature("collection::at<T>(set:T[*], index:Integer[1]):T[1];");
```
The lowering emits an explicit range guard. On a NON-empty collection it fires correctly:
```
$ model::Person.all()->map(p|$p.age)->at(99)
[PLAN] SELECT CASE WHEN 99 >= len((SELECT LIST(...))) OR 99 < 0 THEN error(concat('The system is trying to get an element at offset ', ...)) ELSE list_extract(..., 100) END AS value
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
```
On an EMPTY collection the identical guard silently fails, because DuckDB's `LIST()` over zero rows is `NULL`
and `len(NULL)` is `NULL`, so `0 >= NULL` is `NULL` (not TRUE) and control falls to the ELSE branch:
```
$ model::Person.all()->filter(p|$p.age>200)->map(p|$p.age)->at(0)
[G] type=Integer mult=[1]
[PLAN] SELECT CASE WHEN 0 >= len((SELECT LIST(t1.u_map__age) FROM ( ... WHERE t0.AGE_VAL > 200 ) AS t1)) OR 0 < 0 THEN error(...) ELSE list_extract((SELECT LIST(...)), 1) END AS value
[EXEC-ROW] null |
```
`toOne()`, whose guard is written `... IS NULL OR len(...) <> 1`, gets this right on the same input:
```
$ model::Person.all()->filter(p|$p.age>200)->map(p|$p.age)->toOne()
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: Cannot cast a collection of size 0 to multiplicity [1]
```
**Why it matters.** The one case the guard exists for (index out of range) is exactly the case it misses,
and the failure mode is a wrong ANSWER (`null` in a `[1]` slot), not an error.

---

### [UNSOUND] `[]` (the empty-collection literal, type `Nil`) types `plus`/`minus`/`times` as `Nil[1]` — an uninhabited type at exactly-one multiplicity, carried all the way into the result envelope

**Evidence.** `Pure.java:2001` `math::plus<T>(values:T[*]):T[1]`, `Pure.java:1899` `math::minus<T>`, `Pure.java:2208` `math::times<T>`.
`[]` has type `Nil` (bottom). `T` unifies with `Nil`, so the declared result is `Nil[1]`.
`Nil` is by construction a type with NO values (`Pure.java:178`: `native Class meta::pure::metamodel::type::Nil extends ...::Any {}`;
`ModelContext.isSubtype` treats it as bottom: "Nil is the BOTTOM type — a subtype of every type").

**Repro / actual output:**
```
$ echo '[]->times()' > qq.pure ; probe.sh /tmp/aud/model.pure qq.pure test::R /tmp/aud/ddl.sql
[G] type=meta::pure::metamodel::type::Nil mult=[1]
[PLAN] SELECT list_aggregate(NULL, 'product') AS value
[EXEC] shape=Scalar returnType=meta::pure::metamodel::type::Nil
[EXEC-COL] value : meta::pure::metamodel::type::Nil [ClassType[fqn=meta::pure::metamodel::type::Nil]] mult=null
[EXEC-ROW] null |
```
Same for `[]->plus()` (`SELECT list_sum(NULL)`) and `[]->minus()`.
It also survives into a TABULAR schema as a declared column type:
```
$ model::Emp.all()->project(~[n:e|$e.name, z:e|[]->times()])
[G] type=Relation<(n:String[1], z:meta::pure::metamodel::type::Nil[1])> mult=[1]
[PLAN] SELECT t0.NAME AS n, list_aggregate(NULL, 'product') AS z
[EXEC-COL] z : meta::pure::metamodel::type::Nil [ClassType[fqn=meta::pure::metamodel::type::Nil]] mult=[1]
```
**Why it matters.** A relation column whose declared type is the bottom type at `[1]` is a statement that the
column holds a value of a type that has no values. Any consumer that switches on the column's Pure type
(wire encoder, dialect mapper, a downstream `Nil`-typed parameter) has no arm for it.

---

### [UNSOUND] A subclass may override an inherited property with a DISJOINT type; ModelIntegrity accepts it and the query silently returns a different VALUE for the same row

**Evidence.** `ModelIntegrity.checkClass` (`ModelIntegrity.java:101-138`) builds `propertyNames` as a
per-class local `HashSet` and never consults `superClassFqns`; there is no compatibility check of any kind
between a declared property and an inherited one. `ModelContext.findProperty`
(`PureModelContext.java:186-202`) returns the MOST-DERIVED declaration and stops.

Model (`/tmp/aud2/model.pure`): `Class model::Sup { code: String[1]; }`,
`Class model::Sub extends model::Sup { code: Integer[1]; }`, both mapped to the same VARCHAR column `T_ITEM.CODE`.
`Compiler.compileModel` accepts it.

**Repro A — silent value divergence (data `('007'),('0042')`):**
```
$ model::Sup.all()->project(~[c:x|$x.code])
[G] type=Relation<(c:String[1])> mult=[1]
[PLAN] SELECT t0.CODE AS c
[EXEC-ROW] String(007) |
[EXEC-ROW] String(0042) |

$ model::Sub.all()->project(~[c:x|$x.code])
[G] type=Relation<(c:Integer[1])> mult=[1]
[PLAN] SELECT CAST(t0.CODE AS BIGINT) AS c
[EXEC-ROW] Long(7) |
[EXEC-ROW] Long(42) |
```
The SAME database row yields `String("007")` through the supertype and `Long(7)` through the subtype.

**Repro B — raw SQLException on non-numeric data (`('abc'),('zzz')`):**
```
$ model::Sub.all()->project(~[c:x|$x.code])
[PLAN] SELECT CAST(t0.CODE AS BIGINT) AS c
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Could not convert string 'abc' to INT64 when casting from source column CODE
```
**Why it matters.** In real Legend a property redeclaration must be type-compatible. Here the model compiles,
the plan silently inserts a `CAST`, and the observable result depends on which static type the query happened
to name. The failure surfaces as a driver exception, not a compile error.

---

### [UNSOUND] A subclass may NARROW an inherited property's multiplicity `[0..1]` → `[1]`; the `[1]` column then carries a null cell

**Evidence.** Same missing check as above. Model `/tmp/aud3/model.pure`:
`Class model::Sup { note: String[0..1]; }`, `Class model::Sub extends model::Sup { note: String[1]; }`,
both mapped to nullable `T_ITEM.NOTE`. `Compiler.compileModel` accepts it.
```
$ model::Sup.all()->project(~[c:x|$x.note])
[G] type=Relation<(c:String[0..1])> mult=[1]
[EXEC-COL] c : String [STRING] mult=[0..1]
[EXEC-ROW] String(hi) |
[EXEC-ROW] null |

$ model::Sub.all()->project(~[c:x|$x.note])
[G] type=Relation<(c:String[1])> mult=[1]
[PLAN] SELECT t0.NOTE AS c
[EXEC-COL] c : String [STRING] mult=[1]
[EXEC-ROW] String(hi) |
[EXEC-ROW] null |          <-- declared [1], value absent
```
The two directions and both multiplicity edits were all tested (`p/Integ.java`, cases G/H/I/J/K):
NARROWER type (Any→String), WIDER type (String→Any), DISJOINT type (String→Integer),
`[1]`→`[*]`, `[0..1]`→`[1]` — **all five accepted, none diagnosed.**

---

### [SILENT FALLBACK / CRASH-ADJACENT] `ModelIntegrity` never checks SUPERCLASS references — a dangling `extends` compiles, and the class then fails `isSubtype(X, Any)`

**Evidence.** `ModelIntegrity.checkClass` (`ModelIntegrity.java:101-138`) classifies property types, derived-property
types and parameters, and constraint realizers. It **never touches `cd.superClasses()`**. The only place supers are
walked is `walkSupers` (`ModelIntegrity.java:253-277`), which does
```java
classifier.classDef(supFqn)
        .ifPresent(sc -> walkSupers(sc, classifier, path, acyclic));
```
— an unresolvable super is silently skipped (the comment at 242-243 even says so: "unresolvable heads are the
classify checks' concern, not this walk's" — but no classify check covers supers).

**Repro / actual output (`p/Integ.java` case A, `p/Integ2.java`):**
```
===== A. class extends a NON-EXISTENT class
  F: model compiled OK (ModelIntegrity PASSED)
  G[model::A.all()->map(p|$p.x)] => String [*]

GHOST SUPER: findClass(model::A).superClassFqns = [model::Ghost]
GHOST SUPER: findClass(model::Ghost) = Optional.empty
GHOST SUPER: isSubtype(model::A, Any) = false
```
Contrast: an ASSOCIATION end naming the same ghost IS caught —
```
===== P. association end targets a NON-EXISTENT class
  F: REJECTED ModelException: [2:1] Unknown type: 'model::Ghost' is not a known primitive, class, or enum
```
so the pass is inconsistent about which reference kinds it validates.

**Why it matters.** `isSubtype(model::A, "…::Any") == false` is a broken lattice: every class is an `Any`.
Any check routed through `isSubtype` (match-arm dispatch, `cast`, subtype-aware extents, `commonSupertype`)
now silently answers "no" for a class the user believes is a normal subclass. A one-character typo in
`extends` produces no diagnostic at all.

**DOC-LIE (same site).** `ModelIntegrity.java:18-25` claims: *"THE eager reference-safety pass (F.a + F.b
unified): **every** reference a model element makes — **type names**, realizing functions, mapping bindings,
association ends — must exist … An invalid model never becomes a queryable context."*
A superclass reference is a type name and is not checked. `PureModelContext.java:71-74` repeats the claim
("every reference every element makes (types, realizers, mapping bindings, association ends) is checked once").

---

### [UNSOUND / INCONSISTENCY] A class may `extends` a PRIMITIVE or an ENUM; `isSubtype` then says the class IS a Number, while `paramTypeScore` says it is not

**Evidence.** No check anywhere rejects a non-class supertype. `p/Integ2.java` actual output:
```
isSubtype(model::A, Integer) = true
isSubtype(model::A, Number)  = true
isSubtype(model::A, Any)     = true
findType(model::A)           = Optional[ClassType[fqn=model::A]]
   (model = "Class model::A extends meta::pure::metamodel::type::Integer { x: String[1]; }")

isSubtype(model::A, model::E) = true
   (model = "Enum model::E { X, Y }  Class model::A extends model::E { x: String[1]; }")
```
and the two halves of the kernel then disagree about the SAME question:
```
G[model::A.all()->map(p|$p->abs())]   => model::A [*]      <-- accepted: abs<T>(number:T[1]):T[1] binds T:=model::A
G[model::A.all()->map(p|$p->sqrt())]  => TypeInferenceException: in call to 'sqrt', argument 1: expected Number, got model::A
G[model::A.all()->map(p|$p + 1)]      => TypeInferenceException: no overload of 'plus' structurally matches …
```
`ModelContext.isSubtype` (`ModelContext.java:232-256`) walks `superClassFqns` and reports `model::A <: Number`,
but `InferenceKernel.primitiveTypeScore` (`InferenceKernel.java:1148-1155`) requires the ACTUAL to be a
primitive and rejects it. Two implementations of "is this a Number", two answers.

---

### [SILENT FALLBACK] Any user model can silently REDEFINE a stdlib native at 413 of the 431 native FQNs, and the user's overload wins end-to-end

**Evidence.** `ModelIntegrity.checkDuplicateSignatures` (`ModelIntegrity.java:145-160`) iterates
`model.functions()` only — user definitions are compared against **user** definitions, never against the
native catalog. `FunctionCompiler.functionsAt` (`FunctionCompiler.java:34-79`) merges natives + user
definitions into one candidate list and suppresses the user's definition only for
`PlatformTypes.isPlatformOwnedFunction(fqn)` — **18** FQNs out of **431**
(`PlatformTypes.java:241-257`: dropAndCreateTableInDb, toRepresentation, assertError, assertInstanceOf,
toCSV, dropAndCreateSchemaInDb, DDL statement fns, toSQLString(+Pretty), setUpDataSqls, executionPlan,
planToString(+WithoutFormatting), createDbConfig, execute).

**Repro — hijacking `meta::pure::functions::collection::first` (model `/tmp/aud/m2.pure`):**
```pure
function meta::pure::functions::collection::first(set: Integer[*]): String[1] { 'HIJACKED' }
```
```
$ echo "model::Emp.all()->map(e|\$e.salary)->first()" > qq.pure
$ probe.sh /tmp/aud/m2.pure qq.pure test::R /tmp/aud/ddl2.sql
[G] type=String mult=[1]
[PLAN] SELECT 'HIJACKED' AS value
[EXEC] shape=Scalar returnType=String returnTypeRepr=STRING
[EXEC-ROW] String(HIJACKED) |
```
The native `first<T>(set:T[*]):T[0..1]` would have produced `Integer[0..1]`. The user's overload wins because
`paramTypeScore` gives `Integer` (exact, 2) over `T` (type-var, 0). No warning is emitted at F or G.

**Corollary asymmetry** (`p/Shadow.java` actual output) — the three element kinds resolve conflicts three
different ways, none of them an error:
```
===== user class shadows native Integer, adding a property (native silently wins)
  F: PASSED
  G[meta::pure::metamodel::type::Integer.all()->map(p|$p.bogus)] => TypeInferenceException: class meta::pure::metamodel::type::Integer has no property 'bogus'

===== user enum shadows native DurationUnit (user silently wins?)
  F: PASSED
  G[meta::pure::functions::date::DurationUnit.BANANAS] => meta::pure::functions::date::DurationUnit [1]
  G[meta::pure::functions::date::DurationUnit.DAYS] => TypeInferenceException: enumeration meta::pure::functions::date::DurationUnit has no value 'DAYS'

===== baseline: native DurationUnit values without a user redefinition
  G[meta::pure::functions::date::DurationUnit.DAYS] => meta::pure::functions::date::DurationUnit [1]
  G[meta::pure::functions::date::DurationUnit.BANANAS] => TypeInferenceException: … has no value 'BANANAS'

===== user redefines platform-owned sum() with a DIFFERENT return type (String)
  F: PASSED
  G[model::Q.all()->map(p|$p.n)->sum()] => Integer [1]        <-- native wins (stderr-only note)
```
CLASS: native wins (`TypeClassifier.java:69-72` `classDef` — "NATIVE catalog first"), user's members vanish.
ENUM: **user wins** (`TypeClassifier.classDef`'s sibling `enumDef` at `TypeClassifier.java:75-78` — "user model first"), and the
platform's own date natives lose `DurationUnit.DAYS`.
FUNCTION: native wins for 18 FQNs, user can win for the other 413.
All three are silent (the function case prints to `System.err`, which is not a diagnostic channel —
`FunctionCompiler.java:73-77`).

---

### [CRASH / usability] `[]` as an argument makes 27 native overload sets AMBIGUOUS — `[]->sum()`, `[]->max()`, `->sort([])` are hard compile errors

**Evidence.** `InferenceKernel.paramTypeScore` short-circuits `if (isNil(actual)) return 0;`
(`InferenceKernel.java:1061-1067`), so a `Nil` argument scores IDENTICALLY against every candidate; the
tie then reaches `resolveOverload`'s tie-breaker (`InferenceKernel.java:936-953`), and because all tied
candidates are natives, `nativeWinners.size() != 1` and it throws.

Exhaustive simulation over the real kernel (`p/Ambig.java`): 72-point argument grid per position
(12 primitives + Any + Nil + an enum + Relation + a relation row + a function type, × `[1] [0..1] [*] [1..*]`),
all name groups of arity ≤ 2:
```
# grid size per position: 72
# probed resolutions: 998498 ; (name,arity) groups skipped (arity>2): 187
# AMBIGUOUS cases found: 763
```
**Every one of the 763 involves a `Nil` argument** (filtering the results for cases with no `Nil` returns zero rows).
Distinct names affected (FQN and bare spellings collapsed) — **27**:
`_range, aggregate, assert, executionPlan, extend, flatten, formatDate, get, graphFetch, greaterThan,
greaterThanEqual, lessThan, lessThanEqual, max, min, minus, mode, over, plus, round, rows, select, sort, sub,
sum, times, toString`.

**Repro / actual output:**
```
[]->sum()                       :: [G-ERROR] TypeInferenceException: ambiguous overload of 'meta::pure::functions::math::sum': 3 candidates tie for the argument types
[]->max()                       :: [G-ERROR] TypeInferenceException: ambiguous overload of 'meta::pure::functions::date::max': 7 candidates tie for the argument types
[]->min()                       :: [G-ERROR] TypeInferenceException: ambiguous overload of 'meta::pure::functions::date::min': 7 candidates tie
[]->mode()                      :: [G-ERROR] TypeInferenceException: ambiguous overload of 'meta::pure::functions::math::mode': 3 candidates tie
...->project(~[n:e|$e.name])->sort([]) :: [G-ERROR] TypeInferenceException: ambiguous overload of 'meta::pure::functions::relation::sort': 2 candidates tie
```
**Also a diagnostic defect:** `resolveOverload` names the callee as `candidates.get(0).qualifiedName()`
(`InferenceKernel.java:888`). Because 22 bare names union overloads from several packages
(`Pure.Index.FN_BY_BARE`), the reported FQN is whichever package happened to be registered first:
`[]->max()` blames `meta::pure::functions::date::max` for a tie among `math::` overloads, and
`->toString()` on a scalar blames `meta::pure::functions::relation::toString`:
```
$ model::Emp.all()->project(~[n:e|$e.name, x:e|$e.salary->toString()])
[G-ERROR] TypeInferenceException: no overload of 'meta::pure::functions::relation::toString' structurally matches …
```

---

### [SILENT FALLBACK] Diamond inheritance with conflicting property types: the FIRST-declared super wins, silently

**Evidence.** `PureModelContext.findProperty` (`PureModelContext.java:191-197`) iterates
`superClassFqns` in declaration order and returns the first hit. No conflict detection anywhere.
```
===== L. DIAMOND: two supers declare p with different types
  (Class model::X { p: String[1]; }  Class model::Y { p: Integer[1]; }  Class model::Z extends model::X, model::Y {})
  F: model compiled OK (ModelIntegrity PASSED)
  G[model::Z.all()->map(z|$z.p)]      => String [*]        <-- X wins by source order
  G[model::Z.all()->map(z|$z.p + 1)]  => TypeInferenceException: no overload of 'plus' …
```
Swapping `extends model::Y, model::X` would silently change the query's type. Real Legend rejects the model.

---

### [SILENT FALLBACK] Association ends may target a PRIMITIVE or an ENUM, and an end name may silently shadow a declared property

**Evidence.** `ModelIntegrity.check` (`ModelIntegrity.java:62-65`) validates association ends with
`classifier.classify(...)` only — which accepts primitives and enums, because `TypeClassifier.findType`
classifies primitive → class → enum. No "must be a class" check, and no name-collision check against the
owner class's own members.
```
===== M. association end targets a PRIMITIVE
  (Association model::AS { a: model::A[1]; s: meta::pure::metamodel::type::String[*]; })
  F: PASSED     G[model::A.all()->map(p|$p.s)] => String [*]

===== N. association end targets an ENUM
  F: PASSED     G[model::A.all()->map(p|$p.e)] => model::E [*]

===== O. association end NAME collides with a declared property of the same class
  (Class model::A { x: String[1]; }   Association model::AS { x: model::B[*]; b: model::A[1]; })
  F: PASSED     G[model::A.all()->map(p|$p.x)] => String [*]      <-- the association end is silently unreachable
```
In case O the association end `x: model::B[*]` becomes permanently unreachable — `findProperty`
(`PureModelContext.java:186-202`) checks the class's own properties first and returns before ever consulting
the association index.

---

### [DEAD] 3 native signatures declare type parameters that appear nowhere in the signature

Programmatic scan (`p/TVars.java`) over all 721 overloads:
```
### counts: unboundResultTypeVar=0 unboundResultMultVar=0 phantomTypeVar=3
```
The three:
| Pure.java line | declared | actually used |
|---|---|---|
| 1317 `relation::extend<T,Z,W,R>(r:Relation<T>[1], window:_Window<T>[1], f:FuncColSpec<{...},R>[1])` | `T,Z,W,R` | `T,R` — **`Z`,`W` unused** |
| 1318 `relation::extend<T,Z,W,R>(… FuncColSpecArray<{...},R>[1])` | `T,Z,W,R` | `T,R` — **`Z`,`W` unused** |
| 1430 `collection::groupBy<K,V,U>(set:K[*], fns:…, aggs:Any[*], ids:String[*]):Relation<K>[1]` | `K,V,U` | `K` — **`V`,`U` unused** |
Harmless (an unused binder is never resolved), but they are noise in a file whose stated contract is
"every signature is VERBATIM to its real .pure source".

---

### [INFORMATION LOSS] 2 registry references use a generic class RAW, dropping its type argument

Programmatic arity check (`p/Arity.java`) over every type reference in all 200 classes and 721 signatures:
```
# native classes with >=1 type parameter: 24
# GENERIC-ARITY MISMATCH kinds: 2
ARITY meta::pure::dataQuality::Checked declared<1> used<0>  (1 sites)
      @ FN meta::pure::graphFetch::execution::graphFetchChecked RETURN
ARITY meta::pure::metamodel::relation::Relation declared<1> used<0>  (1 sites)
      @ CLASS meta::pure::metamodel::relation::TDS extends
```
- `Pure.java:211` — `native Class …relation::TDS<T> extends …relation::Relation` (raw). The inheritance edge
  `TDS<row> <: Relation<row>` degenerates to `TDS<row> <: Relation`, so the row schema is lost across the
  subtype step.
- `Pure.java:1394-1395` — `graphFetchChecked<T>(source:T[*], tree:RootGraphFetchTree<T>[1]): …dataQuality::Checked[*]`
  returns raw `Checked`, so `T` never reaches the result type; the element type of a graph-fetch-checked result
  is unrecoverable from the signature. (Not reachable through the relational pipeline, so no execution repro.)

Every other generic reference in the registry is arity-correct (22 of 24 generic classes are always
applied with the declared number of arguments).

---

### [INFORMATION LOSS, minor] One declared Pure type `Integer` decodes to three different Java classes in one pipeline

Same fixture, same declared column type `Integer [INTEGER]`, three runs:
```
groupBy ~[tot: … ->sum()]  [EXEC-ROW] String(a) | BigInteger(100) |
groupBy ~[m:   … ->max()]  [EXEC-ROW] String(a) | Integer(100)    |
groupBy ~[v:   … ->count()][EXEC-ROW] String(b) | Long(0)         |
project ~[c: CAST(...)]    [EXEC-ROW] Long(7)                     |
```
Reported here only because it is visible in the aggregate repros above; the decode path is another auditor's scope.

---

## VERIFIED SOUND

Everything here was checked EXHAUSTIVELY over the whole registry (no sampling) unless stated.

### 1. Type-reference integrity of the registry: ZERO dangling FQNs (task 2)
Probe `p/Validate.java` walks every type reference the registry makes — 200 class `extends` heads and
property types, and every parameter type, return type, nested generic argument, function-type parameter and
result, relation-type column type, and schema-algebra operand across all 721 overloads — and resolves each
through `ModelContext.findType` on a context built over an EMPTY model
(`new PureModelContext(ModelBuilder.from(new ParsedModel(List.of(), ImportScope.empty())))`).
```
### total distinct referenced type FQNs (excluding type vars): 150
### DANGLING (non-generic-head positions): 0
### DANGLING GENERIC HEADS: 0
```
Control (`p/Validate2.java`) proves the probe can detect a miss:
```
control bogus: Optional.empty
Any: Optional[ClassType[fqn=meta::pure::metamodel::type::Any]]
Relation: Optional[ClassType[fqn=meta::pure::metamodel::relation::Relation]]
declared native types: 219
unresolvable declared: 0
```
Note this is a stronger check than the compiler itself performs: `TypeClassifier.classify`'s `Generic` arm
(`TypeClassifier.java:101-107`) builds `new Type.GenericType(g.name(), args)` **without** classifying the
head, so a dangling generic head would NOT be caught by compilation. The registry happens to have none.

### 2. Type-variable hygiene: ZERO unbound result type variables, ZERO unbound result multiplicity variables (task 3)
`p/TVars.java` computes, per overload, the set of declared type/multiplicity variables reachable from the
RETURN and subtracts those reachable from the PARAMETERS (descending through generics, function types,
relation types and schema algebra):
```
### counts: unboundResultTypeVar=0 unboundResultMultVar=0 phantomTypeVar=3
```
And the typer's behaviour if one existed is a clean user-facing error, not an ICE, not a fresh-var leak, not
an `Any` fallback — proved by feeding synthetic signatures straight into the real
`InferenceKernel.resolveOverload` (`p/Unbound.java`):
```
UNBOUND RESULT TYPEVAR -> com.legend.compiler.spec.TypeInferenceException: unbound type variable U
        (synthetic native  test::f<T,U>(a:T[1]):U[1]  called with Integer[1])
UNBOUND RESULT MULTVAR -> com.legend.compiler.spec.TypeInferenceException: unbound multiplicity variable n
        (synthetic native  test::g<T|m,n>(a:T[m]):T[n]  called with Integer[1])
```
Source: `InferenceKernel.java:737-739` (`resolve`) and `:856-862` (`resolveMult`) both `orElseThrow` a
`TypeInferenceException`. The 3 phantom variables found are declared-but-unused, which never reaches
resolution (reported above as a DEAD finding).

### 3. No duplicate or conflicting declarations INSIDE the registry (task 6)
`p/Validate2.java` + `p/Hygiene.java`, exhaustive:
- Same FQN declared twice as a class: **0**. As an enum: **0**.
- A class and an enum sharing an FQN: **0** (`class∩enum FQNs: []`).
- A function FQN colliding with a class FQN: **0** (`class∩function FQNs: []`). With an enum FQN: **0**.
- Duplicate `signatureKey()` across the 721 overloads: **0** (`dup sigkeys: 0 (distinct keys 721 / 721 defs)`).
- Duplicate enum values inside one enumeration: **0**. Empty enumerations: **0**.
- Duplicate property names inside one native class (stored or derived): **0**.
- Duplicate parameter names inside one signature: **0**.
- Duplicate type-parameter names inside one class or signature: **0**.
- Type-variable name clashing with a multiplicity-variable name in the same signature: **0**.
- Multiplicity variable used in a parameter or return without being declared in `<…|…>`: **0**.
- Malformed concrete bounds (`[2..1]`, upper < lower) anywhere in the registry: **0**.
- All 219 declared native types resolve through `findType` on an empty model: **0 unresolvable**.

### 4. Overload sets: only 3 guaranteed-tie groups, all benign (task 5)
`p/Overloads.java` groups by every name the resolver can be handed (all 431 FQNs plus all bare names, using
the real `Pure.nativeFunctionsAt`, i.e. the same `FN_BY_FQN` / `FN_BY_BARE` indexes the Typer reads):
```
### resolvable names with >1 overload: 254
### bare names whose overload set spans >1 package: 22
### identical-parameter-signature groups (guaranteed score tie): 3
AMBIG-SOFT name='execute' params(Function<{->T[*]}>[1],Any[1],Any[1],Any[*])
      -> meta::pure::mapping::execute : Result<T>[1]
      -> meta::pure::router::execute  : Result<T>[1]
AMBIG-SOFT name='execute' params(Function<{->T[*]}>[1],Any[1],Any[1],Any[*],Any[1])
      -> meta::pure::mapping::execute : Result<T>[1]
      -> meta::pure::router::execute  : Result<T>[1]
AMBIG-SOFT name='filter' params(Relation<T>[1],Function<{T[1]->Boolean[1]}>[1])
      -> meta::pure::functions::relation::filter : Relation<T>[1]
      -> meta::pure::tds::filter                : Relation<T>[1]
```
All three are "SOFT": identical parameters AND identical return type/multiplicity, which is exactly the
`allSameShape` tolerance `InferenceKernel.java:938-943` implements, so the first deterministically wins and
the observable typing is identical either way. There is no pair in the registry with identical parameters and
a DIFFERENT result. The only ambiguity the empirical 998,498-resolution sweep found is the `Nil`/`[]` class,
reported as a finding above.

### 5. The multiplicity checker DOES reject `[0..1]` arguments in `[1]` slots
Not a registry defect, verified so the "empty produces null" findings above are not confused with it.
Model `/tmp/aud/model.pure`, `salary : Integer[0..1]`, `nick : String[0..1]`:
```
$ model::Emp.all()->project(~[n:e|$e.name, x:e|$e.nick->toUpper()])
[G-ERROR] TypeInferenceException: in call to 'meta::pure::functions::string::toUpper', argument 1: multiplicity [0..1] is not compatible with [1]

$ model::Emp.all()->project(~[n:e|$e.name, x:e|$e.salary + 1])
[G-ERROR] TypeInferenceException: no overload of 'meta::pure::functions::math::plus' structurally matches the argument types (ExprType[type=INTEGER, multiplicity=Bounded[lower=0, upper=1]], …)
```

### 6. The `[0..1]`-argument boolean natives ARE null-guarded in the lowering
The registry declares `greaterThan(left:Number[0..1], right:Number[0..1]):Boolean[1]`,
`between(value:Number[0..1], …):Boolean[1]`, `startsWith(source:String[0..1], …):Boolean[1]`,
`endsWith`, `contains`, `in(value:Any[0..1], …)`, `equal(left:Any[*], right:Any[*])` — 30+ signatures where a
naive SQL translation would produce `NULL` in a `Boolean[1]` slot. Every one I executed emits an explicit
guard and yields a real boolean (data: row `b` has all-NULL columns):
```
$e.salary > 150          -> SELECT (t0.SALARY IS NOT NULL AND t0.SALARY > 150)         -> a=false b=false c=true
$e.salary == $e.bonus    -> SELECT (t0.SALARY IS NOT DISTINCT FROM t0.BONUS)           -> a=false b=true  c=false
$e.nick->startsWith('n') -> SELECT (t0.NICK IS NOT NULL AND starts_with(t0.NICK,'n'))  -> a=true  b=false c=false
$e.nick->endsWith('n')   -> SELECT (t0.NICK IS NOT NULL AND ends_with(t0.NICK,'n'))    -> a=true  b=false c=false
$e.nick->contains('n')   -> SELECT (t0.NICK IS NOT NULL AND strpos(t0.NICK,'n') > 0)   -> a=true  b=false c=false
$e.salary->between(50,200)-> SELECT (t0.SALARY IS NOT NULL AND (…>=50 AND …<=200))     -> a=true  b=false c=false
$e.salary->in([100,300]) -> SELECT coalesce(coalesce(t0.SALARY IN (100,300),FALSE),FALSE)-> a=true b=false c=true
```
Every `[1]` cell was a real `Boolean`, never `null`. These 30+ `[*]/[0..1] -> Boolean[1]` signatures are SOUND.

### 7. `count` / `size` / `isEmpty` / `isNotEmpty` / `makeString` on an empty input are sound
Same empty-extent harness as the aggregate findings:
`count → Long(0)`, `size → Long(0)`, `isEmpty → Boolean(true)`, `isNotEmpty → Boolean(false)`,
`makeString → String()` — all at declared `[1]`, all with a real value.

### 8. `ModelIntegrity` checks that DO work (each falsified against a model designed to break it)
- **Inheritance cycles** — caught, including the self-cycle:
  ```
  D. A extends B, B extends A -> F: REJECTED ModelException: [1:1] Inheritance cycle: model::A -> model::B -> model::A
  E. A extends A              -> F: REJECTED ModelException: [1:1] Inheritance cycle: model::A -> model::A
  ```
  (Note: `ModelContext.java:226-232` still carries the stale comment *"an inheritance CYCLE — `A extends B` /
  `B extends A`, **which the frontend still accepts**"* — the frontend now rejects it. Minor DOC-LIE.)
- **Association end naming a non-existent class** — caught (case P above).
- Duplicate stored-property names inside one class; duplicate enum values; malformed `[2..1]` bounds;
  duplicate FQNs; duplicate user function signatures; derived-property and constraint realizer existence +
  `Boolean[1]` shape; mapping class/association binding existence + shape; database join/filter column refs —
  all present and code-read in full (`ModelIntegrity.java:38-405`).

### 9. `Property` / `TypedFunction` records carry no defect of their own
`Property.java` (88 lines) and `TypedFunction.java` (110 lines) were read in full. Both are pure data with
null-checks; `TypedFunction.signatureKey()` correctly throws `IllegalStateException` when `definition == null`
(the test-convenience ctor) rather than fabricating a key. No issue found in either.

---

## NOT COVERED

1. **Overload groups of arity ≥ 3** in the empirical ambiguity sweep (`p/Ambig.java`): 187 (name, arity)
   groups were skipped because a 72-point grid at arity 3 is 373k resolutions per group. The ANALYTIC
   identical-parameter check (`p/Overloads.java`) covers ALL arities exhaustively and found only the 3 benign
   groups, so the residual risk is a partial-score tie at arity ≥ 3, which I did not rule out.
2. **`meta::external::query::sql::*`, `meta::pure::executionPlan::*`, `meta::pure::graphFetch::*`,
   `meta::pure::mapping::*`, `meta::pure::router::*`, variant/JSON natives** — declared in the registry but
   not reachable through the relational query pipeline the probe harness drives, so their `[1]`-on-empty
   claims (e.g. `graphFetchChecked`) are analysed statically only, never executed.
3. **Window-form aggregates** (`average<T>(partition, window, row, colToAgg):Float[1]`,
   `stdDevPopulation<T>(…):Number[1]`, `variance<T>(w,f,r):T[0..1]`) — a window partition is never empty by
   construction, so I did not attempt to falsify their `[1]` claims.
4. **`corr` / `covarSample` / `covarPopulation` / `wavg` / `isDistinct` / `joinStrings`** — flagged
   analytically as `[*] -> [1]` reductions but not executable in this harness:
   `corr/covar*` reject the argument at G (`in call to 'meta::pure::functions::math::corr', argument 1: …`),
   `joinStrings` rejects at G, `isDistinct` fails at K with
   `java.sql.SQLException: Catalog Error: Scalar Function with name is_distinct_m… does not exist`
   (a separate, pre-existing dialect gap outside my scope).
5. **`compiler/ModelBuilder.java`** was read only for `duplicateElements`, `findAssociationEnd`,
   `findFunction`, `findPrimitiveExtension` — the surfaces `ModelIntegrity` and `TypeClassifier` consume.
   Its store/mapping indexing is another auditor's scope.
6. **The 60-member CLASS B and 111-member CLASS C multiplicity-coherence candidate lists**
   (Appendix B) were triaged by execution only for the reductions; the boolean/predicate members were
   spot-verified as sound (section 6 above, 7 of ~30 executed), not exhaustively executed.

---

## Appendix B — full multiplicity-coherence scan (`p/MultCoh.java`)

Three mechanical classes over all 721 overloads. CLASS A = the result requires `[>=1]` of a type variable
whose ONLY top-level binder is an optional/many parameter. CLASS B = non-generic result with `lower >= 1`
whose parameters are ALL optional/many. CLASS C = superset scan: result `lower >= 1` with an optional/many
leading (receiver) parameter.

```
=== CLASS A: result type-var required [>=1] but its ONLY top-level binder is optional/many
  A meta::legend::lite::trustOne<T>(values:T[*]):T[1]
  A collection::at<T>(set:T[*], index:Integer[1]):T[1]
  A collection::list<T>(values:T[*]):collection::List<T>[1]
  A math::minus<T>(values:T[*]):T[1]
  A math::plus<T>(values:T[*]):T[1]
  A math::mathUtility::rowMapper<T,U>(value:T[0..1], key:U[0..1]):math::mathUtility::RowMapper<T,U>[1]
  A math::times<T>(values:T[*]):T[1]
  A multiplicity::toOneMany<T>(values:T[*]):T[1..*]
  A multiplicity::toOneMany<T>(values:T[*], message:String[1]):T[1..*]
  A multiplicity::toOne<T>(values:T[*]):T[1]
  A multiplicity::toOne<T>(values:T[*], message:String[1]):T[1]
  count=11
=== CLASS B: NON-generic result with lower>=1 whose params are ALL optional/many (a reduction over a possibly-empty input)
  B collection::and(bools:Boolean[*]):Boolean[1]
  B math::average(numbers:Number[*]):Float[1]
  B meta::legend::lite::avg(numbers:Number[*]):Float[1]
  B boolean::between(value:Number[0..1], lower:Number[0..1], upper:Number[0..1]):Boolean[1]
  B boolean::between(value:String[0..1], lower:String[0..1], upper:String[0..1]):Boolean[1]
  B boolean::between(value:StrictDate[0..1], lower:StrictDate[0..1], upper:StrictDate[0..1]):Boolean[1]
  B boolean::between(value:DateTime[0..1], lower:DateTime[0..1], upper:DateTime[0..1]):Boolean[1]
  B math::corr(x:Number[*], y:Number[*]):Number[1]
  B collection::count<T>(values:T[*]):Integer[1]
  B math::covarPopulation(x:Number[*], y:Number[*]):Number[1]
  B math::covarSample(x:Number[*], y:Number[*]):Number[1]
  B boolean::equal(left:Any[*], right:Any[*]):Boolean[1]
  B meta::genericType(any:Any[*]):generics::GenericType[1]
  B boolean::greaterThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
  B boolean::greaterThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
  B boolean::greaterThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
  B boolean::greaterThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
  B boolean::greaterThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
  B boolean::greaterThan(left:String[0..1], right:String[0..1]):Boolean[1]
  B hash::hashCode(val:Any[*]):Integer[1]
  B collection::in(value:Any[0..1], collection:Any[*]):Boolean[1]
  B collection::isDistinct<T>(set:T[*]):Boolean[1]
  B collection::isEmpty<T>(value:T[*]):Boolean[1]
  B collection::isNotEmpty<T>(value:T[*]):Boolean[1]
  B string::makeString(any:Any[*]):String[1]
  B string::joinStrings(strings:String[*]):String[1]
  B boolean::lessThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
  B boolean::lessThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
  B boolean::lessThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
  B boolean::lessThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
  B boolean::lessThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  B boolean::lessThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  B boolean::greaterThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  B boolean::greaterThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  B boolean::lessThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
  B boolean::lessThan(left:String[0..1], right:String[0..1]):Boolean[1]
  B math::mean(numbers:Number[*]):Float[1]
  B math::median(numbers:Number[*]):Float[1]
  B math::mode(numbers:Integer[*]):Integer[1]
  B math::mode(numbers:Float[*]):Float[1]
  B math::mode(numbers:Number[*]):Number[1]
  B meta::legend::lite::lessThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
  B meta::legend::lite::lessThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
  B meta::legend::lite::greaterThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
  B meta::legend::lite::greaterThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
  B collection::or(values:Boolean[*]):Boolean[1]
  B collection::size<T>(col:T[*]):Integer[1]
  B math::stdDevPopulation(numbers:Number[*]):Number[1]
  B math::stdDevSample(numbers:Number[*]):Number[1]
  B math::stdDev(numbers:Number[*]):Number[1]
  B math::sum(numbers:Float[*]):Float[1]
  B math::sum(numbers:Integer[*]):Integer[1]
  B math::sum(numbers:Number[*]):Number[1]
  B variant::convert::toVariant(source:Any[*]):variant::Variant[1]
  B meta::type(any:Any[*]):Type[1]
  B math::variancePopulation(numbers:Number[*]):Number[1]
  B math::varianceSample(numbers:Number[*]):Number[1]
  B math::variance(numbers:Number[*]):Number[1]
  B math::wavg<T,U>(values:math::mathUtility::RowMapper<T,U>[*]):Float[1]
  B math::wavgUtility::wavgRowMapper(value:Number[0..1], weight:Number[0..1]):math::mathUtility::RowMapper<Number,Number>[1]
  count=60
=== CLASS C: result lower>=1 with a leading [*] receiver param (pipeline subject) ? superset scan
  C meta::legend::lite::trustOne<T>(values:T[*]):T[1]
  C collection::and(bools:Boolean[*]):Boolean[1]
  C collection::at<T>(set:T[*], index:Integer[1]):T[1]
  C math::average(numbers:Number[*]):Float[1]
  C meta::legend::lite::avg(numbers:Number[*]):Float[1]
  C boolean::between(value:Number[0..1], lower:Number[0..1], upper:Number[0..1]):Boolean[1]
  C boolean::between(value:String[0..1], lower:String[0..1], upper:String[0..1]):Boolean[1]
  C boolean::between(value:StrictDate[0..1], lower:StrictDate[0..1], upper:StrictDate[0..1]):Boolean[1]
  C boolean::between(value:DateTime[0..1], lower:DateTime[0..1], upper:DateTime[0..1]):Boolean[1]
  C flow::coalesce<T>(value:T[0..1], ifEmpty:T[1]):T[1]
  C flow::coalesce<T>(value1:T[0..1], value2:T[0..1], ifEmpty:T[1]):T[1]
  C flow::coalesce<T>(value1:T[0..1], value2:T[0..1], value3:T[0..1], ifEmpty:T[1]):T[1]
  C collection::contains(collection:Any[*], val:Any[1]):Boolean[1]
  C collection::contains<T>(collection:T[*], val:T[1], comparator:function::Function<{T[1],T[1]->Boolean[1]}>[1]):Boolean[1]
  C math::corr(x:Number[*], y:Number[*]):Number[1]
  C collection::count<T>(values:T[*]):Integer[1]
  C math::covarPopulation(x:Number[*], y:Number[*]):Number[1]
  C math::covarSample(x:Number[*], y:Number[*]):Number[1]
  C string::endsWith(source:String[0..1], val:String[1]):Boolean[1]
  C boolean::equal(left:Any[*], right:Any[*]):Boolean[1]
  C collection::exists<T>(value:T[*], func:function::Function<{T[1]->Boolean[1]}>[1]):Boolean[1]
  C relation::variant::flatten<T,Z>(valueToFlatten:T[*], columnWithFlattenedValue:relation::ColSpec<(Z EQUAL (?:T[1]))>[1]):relation::Relation<Z>[1]
  C collection::forAll<T>(value:T[*], func:function::Function<{T[1]->Boolean[1]}>[1]):Boolean[1]
  C meta::genericType(any:Any[*]):generics::GenericType[1]
  C boolean::greaterThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
  C boolean::greaterThanEqual(left:Date[0..1], right:Date[1]):Boolean[1]
  C boolean::greaterThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
  C boolean::greaterThanEqual(left:Number[0..1], right:Number[1]):Boolean[1]
  C boolean::greaterThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
  C boolean::greaterThanEqual(left:String[0..1], right:String[1]):Boolean[1]
  C boolean::greaterThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
  C boolean::greaterThan(left:Date[0..1], right:Date[1]):Boolean[1]
  C boolean::greaterThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
  C boolean::greaterThan(left:Number[0..1], right:Number[1]):Boolean[1]
  C boolean::greaterThan(left:String[0..1], right:String[0..1]):Boolean[1]
  C boolean::greaterThan(left:String[0..1], right:String[1]):Boolean[1]
  C tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:relation::FuncColSpecArray<{C[1]->Any[*]},Z>[1], aggs:relation::AggColSpec<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):relation::Relation<(Z UNION R)>[1]
  C tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:relation::FuncColSpecArray<{C[1]->Any[*]},Z>[1], aggs:relation::AggColSpecArray<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):relation::Relation<(Z UNION R)>[1]
  C collection::groupBy<K,V,U>(set:K[*], fns:function::Function<{K[1]->Any[*]}>[*], aggs:Any[*], ids:String[*]):relation::Relation<K>[1]
  C hash::hashCode(val:Any[*]):Integer[1]
  C collection::indexOf<T>(set:T[*], value:T[1]):Integer[1]
  C collection::in(value:Any[0..1], collection:Any[*]):Boolean[1]
  C collection::isDistinct<T>(set:T[*]):Boolean[1]
  C collection::isEmpty<T>(value:T[*]):Boolean[1]
  C collection::isNotEmpty<T>(value:T[*]):Boolean[1]
  C string::makeString(any:Any[*]):String[1]
  C string::makeString(any:Any[*], separator:String[1]):String[1]
  C string::makeString(any:Any[*], prefix:String[1], separator:String[1], suffix:String[1]):String[1]
  C string::joinStrings(strings:String[*]):String[1]
  C string::joinStrings(strings:String[*], separator:String[1]):String[1]
  C string::joinStrings(strings:String[*], prefix:String[1], separator:String[1], suffix:String[1]):String[1]
  C meta::relational::milestoning::concatenateTemporalTdsQueries<T>(lfs:function::Function<{->T[*]}>[*]):function::Function<{->T[*]}>[1]
  C boolean::lessThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
  C boolean::lessThanEqual(left:Date[0..1], right:Date[1]):Boolean[1]
  C boolean::lessThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
  C boolean::lessThanEqual(left:Number[0..1], right:Number[1]):Boolean[1]
  C boolean::lessThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
  C boolean::lessThanEqual(left:String[0..1], right:String[1]):Boolean[1]
  C boolean::lessThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
  C boolean::lessThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  C boolean::lessThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  C boolean::greaterThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  C boolean::greaterThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
  C boolean::lessThan(left:Date[0..1], right:Date[1]):Boolean[1]
  C boolean::lessThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
  C boolean::lessThan(left:Number[0..1], right:Number[1]):Boolean[1]
  C boolean::lessThan(left:String[0..1], right:String[0..1]):Boolean[1]
  C boolean::lessThan(left:String[0..1], right:String[1]):Boolean[1]
  C collection::list<T>(values:T[*]):collection::List<T>[1]
  C math::mean(numbers:Number[*]):Float[1]
  C math::median(numbers:Number[*]):Float[1]
  C math::minus<T>(values:T[*]):T[1]
  C math::mode(numbers:Integer[*]):Integer[1]
  C math::mode(numbers:Float[*]):Float[1]
  C math::mode(numbers:Number[*]):Number[1]
  C meta::legend::lite::lessThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
  C meta::legend::lite::lessThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
  C meta::legend::lite::greaterThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
  C meta::legend::lite::greaterThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
  C collection::or(values:Boolean[*]):Boolean[1]
  C relation::over<T>(sortInfo:relation::SortInfo<T>[*]):relation::_Window<T>[1]
  C collection::newMap<U,V>(pairs:collection::Pair<U,V>[*]):collection::Map<U,V>[1]
  C math::percentile(numbers:Number[*], p:Number[1]):Number[1]
  C math::plus<T>(values:T[*]):T[1]
  C relation::project<C,T>(cl:C[*], x:relation::FuncColSpecArray<{C[1]->Any[*]},T>[1]):relation::Relation<T>[1]
  C tds::project<K>(set:K[*], fns:function::Function<{K[1]->Any[*]}>[*], ids:String[*]):relation::Relation<K>[1]
  C relation::variant::flatten<T>(valueToFlatten:T[*], columnWithFlattenedValue:relation::ColSpec<Any>[1]):relation::Relation<Any>[1]
  C math::mathUtility::rowMapper<T,U>(value:T[0..1], key:U[0..1]):math::mathUtility::RowMapper<T,U>[1]
  C graphFetch::execution::serialize<T>(source:T[*], tree:graphFetch::RootGraphFetchTree<T>[1]):String[1]
  C graphFetch::execution::serialize<T>(source:T[*], tree:graphFetch::RootGraphFetchTree<T>[1], config:Any[1]):String[1]
  C collection::size<T>(col:T[*]):Integer[1]
  C string::startsWith(source:String[0..1], val:String[1]):Boolean[1]
  C math::stdDevPopulation(numbers:Number[*]):Number[1]
  C math::stdDevSample(numbers:Number[*]):Number[1]
  C math::stdDev(numbers:Number[*]):Number[1]
  C math::sum(numbers:Float[*]):Float[1]
  C math::sum(numbers:Integer[*]):Integer[1]
  C math::sum(numbers:Number[*]):Number[1]
  C math::times<T>(values:T[*]):T[1]
  C multiplicity::toOneMany<T>(values:T[*]):T[1..*]
  C multiplicity::toOneMany<T>(values:T[*], message:String[1]):T[1..*]
  C multiplicity::toOne<T>(values:T[*]):T[1]
  C multiplicity::toOne<T>(values:T[*], message:String[1]):T[1]
  C variant::convert::toVariant(source:Any[*]):variant::Variant[1]
  C meta::type(any:Any[*]):Type[1]
  C math::variancePopulation(numbers:Number[*]):Number[1]
  C math::varianceSample(numbers:Number[*]):Number[1]
  C math::variance(numbers:Number[*]):Number[1]
  C math::variance(numbers:Number[*], isSample:Boolean[1]):Number[1]
  C math::wavg<T,U>(values:math::mathUtility::RowMapper<T,U>[*]):Float[1]
  C math::wavgUtility::wavgRowMapper(value:Number[0..1], weight:Number[0..1]):math::mathUtility::RowMapper<Number,Number>[1]
  count=111
```

Triage of CLASS A (11): `toOne`/`toOneMany` (4) are SOUND — they are the explicit assertion natives and the
lowering emits an `error(...)` guard (proved above). `trustOne` is the same shape. `list`/`rowMapper` wrap the
argument in a container, so `[1]` is the container, not the element — SOUND. `at`, `minus`, `plus`, `times`
are the 4 UNSOUND ones, all reported above with executed repros.

---

## Appendix A — full registry dump (abbreviated FQNs)

Full-FQN machine-readable copy: `/home/user/audit/findings/A07-pure-registry.dump.tsv`
(944 lines, TSV, same content with FQNs unabbreviated).

Abbreviations used below: `` = `meta::pure::metamodel::type::`, `m3::` = `meta::pure::metamodel::`,
`rel::` = `meta::pure::metamodel::relation::`, `fn::` = `meta::pure::metamodel::function::`,
`f::` = `meta::pure::functions::`, `p::` = `meta::pure::`, `lite::` = `meta::legend::lite::`,
`rl::` = `meta::relational::`.

```
# COUNTS classes=200 enums=19 functionOverloads=721
# --- NATIVE CLASSES (200) : name<typeParams> extends [...] { props }
CLASS	Any	extends -	{}
CLASS	m3::extension::ElementOverride	extends -	{}
CLASS	p::tds::TDSNull	extends -	{}
CLASS	Nil	extends Any	{}
CLASS	Type	extends m3::ModelElement	{}
CLASS	generics::GenericType	extends -	{rawType:Type[0..1]}
CLASS	m3::ModelElement	extends Any	{}
CLASS	Number	extends Any	{}
CLASS	Integer	extends Number	{}
CLASS	Float	extends Number	{}
CLASS	Decimal	extends Number	{}
CLASS	String	extends Any	{}
CLASS	Boolean	extends Any	{}
CLASS	Byte	extends Any	{}
CLASS	Date	extends Any	{}
CLASS	StrictDate	extends Date	{}
CLASS	DateTime	extends Date	{}
CLASS	LatestDate	extends Date	{}
CLASS	StrictTime	extends Any	{}
CLASS	rel::Relation<T>	extends Any	{}
CLASS	rel::TDS<T>	extends rel::Relation	{}
CLASS	rel::ColSpec<T>	extends Any	{}
CLASS	rel::ColSpecArray<T>	extends Any	{}
CLASS	rel::FuncColSpec<F,R>	extends Any	{}
CLASS	rel::FuncColSpecArray<F,R>	extends Any	{}
CLASS	rel::AggColSpec<F,U,R>	extends Any	{}
CLASS	rel::AggColSpecArray<F,U,R>	extends Any	{}
CLASS	meta::core::runtime::Connection	extends -	{}
CLASS	meta::core::runtime::ConnectionStore	extends -	{connection:meta::core::runtime::Connection[1]; element:Any[1]}
CLASS	meta::core::runtime::Runtime	extends -	{connectionStores:meta::core::runtime::ConnectionStore[*]}
CLASS	meta::core::runtime::EngineRuntime	extends meta::core::runtime::Runtime	{mappings:p::mapping::Mapping[*]}
CLASS	p::runtime::ExecutionContext	extends -	{}
CLASS	p::executionPlan::MultiExecutionContext	extends p::runtime::ExecutionContext	{childExecutionContext:p::runtime::ExecutionContext[*]}
CLASS	p::executionPlan::ExecutionOption	extends Any	{}
CLASS	p::executionPlan::ExecutionOptionContext	extends p::executionPlan::MultiExecutionContext	{executionOptions:p::executionPlan::ExecutionOption[*]}
CLASS	p::extension::Extension	extends -	{}
CLASS	meta::external::store::relational::runtime::RelationalQueryGenerationConfig	extends -	{}
CLASS	p::graphFetch::execution::AlloySerializationConfig	extends -	{typeKeyName:String[1]; includeType:Boolean[0..1]; includeEnumType:Boolean[0..1]; dateTimeFormat:String[0..1]; removePropertiesWithNullValues:Boolean[0..1]; removePropertiesWithEmptySets:Boolean[0..1]; fullyQualifiedTypePath:Boolean[0..1]; includeObjectReference:Boolean[0..1]}
CLASS	meta::external::store::relational::runtime::DatabaseConnection	extends meta::core::runtime::Connection	{type:rl::runtime::DatabaseType[1]; debug:Boolean[0..1]; timeZone:String[0..1]; quoteIdentifiers:Boolean[0..1]; queryTimeOutInSeconds:Integer[0..1]; queryGenerationConfigs:meta::external::store::relational::runtime::RelationalQueryGenerationConfig[*]; queryPostProcessorsWithParameter:rl::runtime::PostProcessorWithParameter[*]; sqlQueryPostProcessors:fn::Function<{rl::metamodel::relation::SelectSQLQuery[1]->p::mapping::Result<rl::metamodel::relation::SelectSQLQuery,|1>[1]}>[*]; sqlQueryPostProcessorsConnectionAware:fn::Function<{rl::metamodel::relation::SelectSQLQuery[1],meta::external::store::relational::runtime::DatabaseConnection[1]->p::mapping::Result<rl::metamodel::relation::SelectSQLQuery,|1>[1]}>[*]}
CLASS	meta::external::store::relational::runtime::TestDatabaseConnection	extends meta::external::store::relational::runtime::DatabaseConnection	{testDataSetupCsv:String[0..1]; testDataSetupSqls:String[*]}
CLASS	p::alloy::connections::alloy::specification::DatasourceSpecification	extends Any	{}
CLASS	p::alloy::connections::alloy::specification::LocalH2DatasourceSpecification	extends p::alloy::connections::alloy::specification::DatasourceSpecification	{testDataSetupCsv:String[0..1]; testDataSetupSqls:String[*]; disableDatabaseToUpper:Boolean[0..1]}
CLASS	p::alloy::connections::alloy::authentication::AuthenticationStrategy	extends Any	{}
CLASS	p::alloy::connections::alloy::authentication::DefaultH2AuthenticationStrategy	extends p::alloy::connections::alloy::authentication::AuthenticationStrategy	{}
CLASS	p::alloy::connections::alloy::authentication::TestDatabaseAuthenticationStrategy	extends p::alloy::connections::alloy::authentication::DefaultH2AuthenticationStrategy	{}
CLASS	meta::external::store::relational::runtime::RelationalDatabaseConnection	extends meta::external::store::relational::runtime::DatabaseConnection	{datasourceSpecification:p::alloy::connections::alloy::specification::DatasourceSpecification[1]; authenticationStrategy:p::alloy::connections::alloy::authentication::AuthenticationStrategy[1]; postProcessors:p::alloy::connections::PostProcessor[*]}
CLASS	rl::metamodel::Database	extends p::store::Store	{schemas:rl::metamodel::Schema[*]}
CLASS	rl::metamodel::relation::View	extends m3::ModelElement	{columnMappings:rl::mapping::ColumnMapping[*]}
CLASS	rl::mapping::ColumnMapping	extends Any	{columnName:String[1]; relationalOperationElement:rl::metamodel::RelationalOperationElement[1]}
CLASS	rl::metamodel::RelationalOperationElement	extends Any	{}
CLASS	rl::metamodel::DynaFunction	extends rl::metamodel::RelationalOperationElement	{name:String[1]; parameters:rl::metamodel::RelationalOperationElement[*]}
CLASS	rl::metamodel::Literal	extends rl::metamodel::RelationalOperationElement	{value:Any[1]}
CLASS	rl::metamodel::LiteralList	extends rl::metamodel::RelationalOperationElement	{values:rl::metamodel::Literal[*]}
CLASS	meta::external::query::sql::metamodel::Node	extends Any	{}
CLASS	meta::external::query::sql::metamodel::Expression	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::QualifiedName	extends Any	{parts:String[*]}
CLASS	meta::external::query::sql::metamodel::QualifiedNameReference	extends meta::external::query::sql::metamodel::Expression	{name:meta::external::query::sql::metamodel::QualifiedName[1]}
CLASS	meta::external::query::sql::metamodel::Statement	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::Relation	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::QueryBody	extends meta::external::query::sql::metamodel::Relation	{}
CLASS	meta::external::query::sql::metamodel::SelectItem	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::Literal	extends meta::external::query::sql::metamodel::Expression	{}
CLASS	meta::external::query::sql::metamodel::DateLiteral	extends meta::external::query::sql::metamodel::Literal	{value:StrictDate[1]}
CLASS	meta::external::query::sql::metamodel::TimestampLiteral	extends meta::external::query::sql::metamodel::Literal	{value:DateTime[1]}
CLASS	rl::metamodel::SQLNull	extends rl::metamodel::RelationalOperationElement	{}
CLASS	meta::external::query::sql::metamodel::StringLiteral	extends meta::external::query::sql::metamodel::Literal	{value:String[1]; quoted:Boolean[0..1]}
CLASS	meta::external::query::sql::metamodel::IntegerLiteral	extends meta::external::query::sql::metamodel::Literal	{value:Integer[1]}
CLASS	meta::external::query::sql::metamodel::BooleanLiteral	extends meta::external::query::sql::metamodel::Literal	{value:Boolean[1]}
CLASS	meta::external::query::sql::metamodel::DoubleLiteral	extends meta::external::query::sql::metamodel::Literal	{value:Float[1]}
CLASS	meta::external::query::sql::metamodel::NullLiteral	extends meta::external::query::sql::metamodel::Literal	{}
CLASS	meta::external::query::sql::metamodel::FunctionCall	extends meta::external::query::sql::metamodel::Expression	{name:meta::external::query::sql::metamodel::QualifiedName[1]; distinct:Boolean[1]; arguments:meta::external::query::sql::metamodel::Expression[*]; filter:meta::external::query::sql::metamodel::Expression[0..1]; window:meta::external::query::sql::metamodel::Window[0..1]}
CLASS	meta::external::query::sql::metamodel::Window	extends meta::external::query::sql::metamodel::Statement	{windowRef:String[0..1]; partitions:meta::external::query::sql::metamodel::Expression[*]; orderBy:meta::external::query::sql::metamodel::SortItem[*]; windowFrame:meta::external::query::sql::metamodel::WindowFrame[0..1]}
CLASS	meta::external::query::sql::metamodel::SortItem	extends meta::external::query::sql::metamodel::Node	{sortKey:meta::external::query::sql::metamodel::Expression[1]; ordering:meta::external::query::sql::metamodel::SortItemOrdering[1]; nullOrdering:meta::external::query::sql::metamodel::SortItemNullOrdering[1]}
CLASS	meta::external::query::sql::metamodel::WindowFrame	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::Cast	extends meta::external::query::sql::metamodel::Expression	{expression:meta::external::query::sql::metamodel::Expression[1]; type:meta::external::query::sql::metamodel::ColumnType[1]}
CLASS	meta::external::query::sql::metamodel::ColumnType	extends meta::external::query::sql::metamodel::Expression	{name:String[1]; parameters:Integer[*]}
CLASS	meta::external::query::sql::metamodel::extension::TablePlaceholder	extends meta::external::query::sql::metamodel::Relation	{name:String[1]}
CLASS	meta::external::query::sql::metamodel::extension::InClauseVariablePlaceholder	extends meta::external::query::sql::metamodel::Expression	{name:String[1]}
CLASS	meta::external::query::sql::metamodel::QuerySpecification	extends meta::external::query::sql::metamodel::QueryBody	{select:meta::external::query::sql::metamodel::Select[1]; from:meta::external::query::sql::metamodel::Relation[*]; where:meta::external::query::sql::metamodel::Expression[0..1]; groupBy:meta::external::query::sql::metamodel::Expression[*]; having:meta::external::query::sql::metamodel::Expression[0..1]; orderBy:meta::external::query::sql::metamodel::SortItem[*]; limit:meta::external::query::sql::metamodel::Expression[0..1]; offset:meta::external::query::sql::metamodel::Expression[0..1]}
CLASS	meta::external::query::sql::metamodel::extension::ExtendedQuerySpecification	extends meta::external::query::sql::metamodel::QuerySpecification	{qualify:meta::external::query::sql::metamodel::Expression[0..1]}
CLASS	meta::external::query::sql::metamodel::WithQuery	extends meta::external::query::sql::metamodel::Node	{name:String[1]; columns:String[*]; query:meta::external::query::sql::metamodel::Query[1]}
CLASS	meta::external::query::sql::metamodel::With	extends meta::external::query::sql::metamodel::Statement	{withQueries:meta::external::query::sql::metamodel::WithQuery[*]}
CLASS	meta::external::query::sql::metamodel::QueryWithScope	extends meta::external::query::sql::metamodel::QueryBody	{with:meta::external::query::sql::metamodel::With[0..1]; queryBody:meta::external::query::sql::metamodel::QueryBody[1]}
CLASS	meta::external::query::sql::metamodel::Union	extends meta::external::query::sql::metamodel::QueryBody	{left:meta::external::query::sql::metamodel::Relation[1]; right:meta::external::query::sql::metamodel::Relation[1]; distinct:Boolean[1]}
CLASS	meta::external::query::sql::metamodel::LogicalBinaryExpression	extends meta::external::query::sql::metamodel::Expression	{type:meta::external::query::sql::metamodel::LogicalBinaryType[1]; left:meta::external::query::sql::metamodel::Expression[1]; right:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::IsNullPredicate	extends meta::external::query::sql::metamodel::Expression	{value:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::IsNotNullPredicate	extends meta::external::query::sql::metamodel::Expression	{value:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::InListExpression	extends meta::external::query::sql::metamodel::Expression	{values:meta::external::query::sql::metamodel::Expression[*]}
CLASS	meta::external::query::sql::metamodel::InPredicate	extends meta::external::query::sql::metamodel::Expression	{value:meta::external::query::sql::metamodel::Expression[1]; valueList:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::ComparisonExpression	extends meta::external::query::sql::metamodel::Expression	{left:meta::external::query::sql::metamodel::Expression[1]; right:meta::external::query::sql::metamodel::Expression[1]; operator:meta::external::query::sql::metamodel::ComparisonOperator[1]}
CLASS	meta::external::query::sql::metamodel::AliasedRelation	extends meta::external::query::sql::metamodel::Relation	{relation:meta::external::query::sql::metamodel::Relation[1]; alias:String[1]; columnNames:String[*]}
CLASS	meta::external::query::sql::metamodel::Table	extends meta::external::query::sql::metamodel::QueryBody	{name:meta::external::query::sql::metamodel::QualifiedName[1]}
CLASS	meta::external::query::sql::metamodel::TableFunction	extends meta::external::query::sql::metamodel::QueryBody	{functionCall:meta::external::query::sql::metamodel::FunctionCall[1]}
CLASS	meta::external::query::sql::metamodel::TableSubquery	extends meta::external::query::sql::metamodel::QueryBody	{query:meta::external::query::sql::metamodel::Query[1]}
CLASS	meta::external::query::sql::metamodel::Join	extends meta::external::query::sql::metamodel::Relation	{type:meta::external::query::sql::metamodel::JoinType[1]; left:meta::external::query::sql::metamodel::Relation[1]; right:meta::external::query::sql::metamodel::Relation[1]; criteria:meta::external::query::sql::metamodel::JoinCriteria[0..1]}
CLASS	meta::external::query::sql::metamodel::JoinCriteria	extends meta::external::query::sql::metamodel::Node	{}
CLASS	meta::external::query::sql::metamodel::JoinOn	extends meta::external::query::sql::metamodel::JoinCriteria	{expression:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::Query	extends meta::external::query::sql::metamodel::Statement	{queryBody:meta::external::query::sql::metamodel::QueryBody[1]}
CLASS	meta::external::query::sql::metamodel::SingleColumn	extends meta::external::query::sql::metamodel::SelectItem	{alias:String[0..1]; expression:meta::external::query::sql::metamodel::Expression[1]}
CLASS	meta::external::query::sql::metamodel::AllColumns	extends meta::external::query::sql::metamodel::SelectItem	{}
CLASS	meta::external::query::sql::metamodel::Select	extends meta::external::query::sql::metamodel::Node	{distinct:Boolean[1]; selectItems:meta::external::query::sql::metamodel::SelectItem[*]}
CLASS	rl::functions::toPostgresModel::ModelConversionState	extends Any	{isRootSelect:Boolean[0..1]; processingSelect:Boolean[0..1]; processingFilter:Boolean[0..1]; extensions:p::extension::Extension[*]; dynaFunctionConverterMap:Any[0..1]}
CLASS	rl::metamodel::Alias	extends rl::metamodel::RelationalOperationElement	{name:String[1]; relationalElement:rl::metamodel::RelationalOperationElement[1]}
CLASS	rl::metamodel::TableAlias	extends rl::metamodel::Alias	{schema:String[0..1]}
CLASS	rl::metamodel::Column	extends rl::metamodel::RelationalOperationElement	{name:String[1]; type:rl::metamodel::datatype::DataType[1]}
CLASS	rl::metamodel::ColumnName	extends rl::metamodel::RelationalOperationElement	{name:String[1]}
CLASS	rl::metamodel::TableAliasColumnName	extends rl::metamodel::RelationalOperationElement	{alias:rl::metamodel::TableAlias[1]; columnName:String[1]}
CLASS	rl::metamodel::TableAliasColumn	extends rl::metamodel::RelationalOperationElement	{columnName:String[0..1]; alias:rl::metamodel::TableAlias[1]; column:rl::metamodel::Column[1]}
CLASS	rl::metamodel::datatype::DataType	extends Any	{}
CLASS	rl::metamodel::Window	extends rl::metamodel::RelationalOperationElement	{partition:rl::metamodel::RelationalOperationElement[*]; sortBy:rl::metamodel::SortByInfo[*]}
CLASS	rl::metamodel::SortByInfo	extends rl::metamodel::RelationalOperationElement	{sortByElement:rl::metamodel::RelationalOperationElement[1]; sortDirection:rl::metamodel::SortDirection[0..1]}
CLASS	rl::metamodel::WindowColumn	extends rl::metamodel::RelationalOperationElement	{columnName:String[1]; window:rl::metamodel::Window[1]; func:rl::metamodel::DynaFunction[1]}
CLASS	p::mapping::SetImplementation	extends Any	{root:Boolean[0..1]; id:String[0..1]; parent:Any[0..1]; superSetImplementationId:String[0..1]}
CLASS	p::mapping::PropertyMappingsImplementation	extends p::mapping::SetImplementation	{}
CLASS	p::mapping::InstanceSetImplementation	extends p::mapping::PropertyMappingsImplementation	{class:Any[0..1]}
CLASS	p::router::clustering::CrossSetImplementation	extends p::mapping::InstanceSetImplementation	{targetStore:p::store::Store[0..1]; varName:String[1]}
CLASS	rl::metamodel::RelationalMappingSpecification	extends Any	{}
CLASS	rl::mapping::RelationalInstanceSetImplementation	extends p::mapping::InstanceSetImplementation	{primaryKey:rl::metamodel::RelationalOperationElement[*]}
CLASS	rl::mapping::RootRelationalInstanceSetImplementation	extends rl::mapping::RelationalInstanceSetImplementation,rl::metamodel::RelationalMappingSpecification	{}
CLASS	p::mapping::PropertyMapping	extends Any	{}
CLASS	rl::mapping::RelationalPropertyMapping	extends p::mapping::PropertyMapping	{relationalOperationElement:rl::metamodel::RelationalOperationElement[1]}
CLASS	p::store::Store	extends m3::ModelElement	{includes:p::store::Store[*]; name:String[0..1]}
CLASS	meta::external::store::model::ModelStore	extends p::store::Store	{}
CLASS	meta::external::store::model::PureModelConnection	extends meta::core::runtime::Connection	{}
CLASS	meta::external::store::model::JsonModelConnection	extends meta::external::store::model::PureModelConnection	{class:Any[1]; url:String[1]}
CLASS	meta::external::store::model::ModelChainConnection	extends meta::core::runtime::Connection	{mappings:p::mapping::Mapping[*]}
CLASS	meta::external::store::relational::runtime::GenerationFeaturesConfig	extends meta::external::store::relational::runtime::RelationalQueryGenerationConfig	{enabled:String[*]; disabled:String[*]}
CLASS	p::mapping::Mapping	extends m3::ModelElement	{name:String[0..1]}
CLASS	rl::metamodel::relation::Relation	extends rl::metamodel::RelationalOperationElement	{}
CLASS	rl::metamodel::relation::NamedRelation	extends rl::metamodel::relation::Relation	{name:String[1]}
CLASS	rl::metamodel::relation::Table	extends rl::metamodel::relation::NamedRelation	{columns:rl::metamodel::Column[*]; schema:Any[0..1]}
CLASS	rl::metamodel::relation::SelectSQLQuery	extends rl::metamodel::RelationalOperationElement	{columns:rl::metamodel::RelationalOperationElement[*]; distinct:Boolean[0..1]; data:rl::metamodel::join::RootJoinTreeNode[0..1]; filteringOperation:rl::metamodel::RelationalOperationElement[*]; groupBy:rl::metamodel::RelationalOperationElement[*]; havingOperation:rl::metamodel::RelationalOperationElement[*]; qualifyOperation:rl::metamodel::RelationalOperationElement[*]; orderBy:rl::metamodel::OrderBy[*]; fromRow:rl::metamodel::Literal[0..1]; toRow:rl::metamodel::Literal[0..1]; commonTableExpressions:rl::metamodel::relation::CommonTableExpression[*]}
CLASS	rl::metamodel::join::RelationalTreeNode	extends rl::metamodel::RelationalOperationElement	{alias:rl::metamodel::TableAlias[1]; childrenData:Any[*]}
CLASS	rl::metamodel::join::RootJoinTreeNode	extends rl::metamodel::join::RelationalTreeNode	{}
CLASS	rl::metamodel::join::JoinTreeNode	extends rl::metamodel::join::RelationalTreeNode	{setMappingOwner:Any[0..1]; database:Any[0..1]; joinName:String[1]; join:Any[0..1]; joinType:rl::metamodel::join::JoinType[0..1]; lateral:Boolean[0..1]}
CLASS	rl::metamodel::OrderBy	extends Any	{column:rl::metamodel::RelationalOperationElement[1]; direction:rl::metamodel::SortDirection[1]}
CLASS	rl::metamodel::relation::CommonTableExpression	extends rl::metamodel::RelationalOperationElement	{name:String[1]; sqlQuery:Any[1]}
CLASS	rl::metamodel::relation::CommonTableExpressionReference	extends rl::metamodel::RelationalOperationElement	{name:String[1]}
CLASS	rl::metamodel::operation::JoinStrings	extends rl::metamodel::RelationalOperationElement	{strings:rl::metamodel::RelationalOperationElement[*]; prefix:rl::metamodel::RelationalOperationElement[0..1]; separator:rl::metamodel::RelationalOperationElement[0..1]; suffix:rl::metamodel::RelationalOperationElement[0..1]}
CLASS	rl::metamodel::RelationalOperationElementWithJoin	extends rl::metamodel::RelationalOperationElement	{relationalOperationElement:rl::metamodel::RelationalOperationElement[0..1]; joinTreeNode:rl::metamodel::join::JoinTreeNode[0..1]}
CLASS	rl::metamodel::relation::Union	extends rl::metamodel::RelationalOperationElement	{currentTreeNodes:Any[*]; setImplementations:Any[*]; queries:rl::metamodel::relation::SelectSQLQuery[*]}
CLASS	rl::metamodel::relation::UnionAll	extends rl::metamodel::relation::Union	{}
CLASS	rl::metamodel::relation::TdsSelectSqlQuery	extends rl::metamodel::relation::SelectSQLQuery	{}
CLASS	rl::metamodel::relation::TabularFunction	extends rl::metamodel::RelationalOperationElement	{name:String[1]; schema:Any[0..1]}
CLASS	rl::functions::pureToSqlQuery::metamodel::VarPlaceHolder	extends rl::metamodel::RelationalOperationElement	{name:String[1]; propertyPath:Any[*]; type:Any[1]; multiplicity:Any[0..1]}
CLASS	rl::functions::pureToSqlQuery::metamodel::VarSetPlaceHolder	extends rl::metamodel::relation::TdsSelectSqlQuery	{varName:String[1]}
CLASS	rl::functions::pureToSqlQuery::metamodel::VarCrossSetPlaceHolder	extends rl::metamodel::relation::Table	{varName:String[1]; crossSetImplementation:p::router::clustering::CrossSetImplementation[1]}
CLASS	rl::runtime::PostProcessorParameter	extends Any	{}
CLASS	rl::runtime::PostProcessor	extends Any	{sqlQueryPostProcessorForExecution:fn::Function<Any>[0..1]; sqlQueryPostProcessorForPlan:fn::ConcreteFunctionDefinition<Any>[0..1]}
CLASS	rl::runtime::PostProcessorWithParameter	extends Any	{postProcessor:fn::ConcreteFunctionDefinition<{->rl::runtime::PostProcessor[1]}>[1]; parameters:rl::runtime::PostProcessorParameter[*]}
CLASS	p::alloy::connections::PostProcessor	extends Any	{}
CLASS	rl::postProcessor::cteExtraction::ExtractSubQueriesAsCTEsPostProcessor	extends p::alloy::connections::PostProcessor	{}
CLASS	rl::metamodel::DatabaseMapper	extends Any	{database:String[1]; schemas:rl::metamodel::Schema[*]}
CLASS	rl::metamodel::SchemaMapper	extends Any	{from:rl::metamodel::Schema[1]; to:String[1]}
CLASS	rl::metamodel::TableMapper	extends Any	{from:rl::metamodel::relation::Table[1]; to:String[1]}
CLASS	rl::metamodel::RelationalMapper	extends m3::ModelElement,rl::runtime::PostProcessorParameter	{databaseMappers:rl::metamodel::DatabaseMapper[*]; schemaMappers:rl::metamodel::SchemaMapper[*]; tableMappers:rl::metamodel::TableMapper[*]}
CLASS	p::alloy::connections::RelationalMapperPostProcessor	extends p::alloy::connections::PostProcessor	{relationalMappers:rl::metamodel::RelationalMapper[*]}
CLASS	p::tds::TabularDataSet	extends Any	{columns:p::tds::TDSColumn[*]; rows:p::tds::TDSRow[*]}
CLASS	p::tds::TDSColumn	extends Any	{offset:Integer[0..1]; name:String[1]}
CLASS	p::tds::TDSRow	extends Any	{parent:p::tds::TabularDataSet[0..1]; values:Any[*]}
CLASS	rl::metamodel::Schema	extends m3::ModelElement	{tables:rl::metamodel::relation::Table[*]; views:rl::metamodel::relation::View[*]; name:String[0..1]; database:rl::metamodel::Database[1]}
CLASS	p::mapping::aggregationAware::AggregationAwareActivity	extends p::mapping::Activity	{rewrittenQuery:String[1]}
CLASS	rl::runtime::DataSource	extends Any	{}
CLASS	rl::mapping::RelationalActivity	extends p::mapping::Activity	{sql:String[1]; comment:String[0..1]; executionTimeInNanoSecond:Integer[0..1]; sqlGenerationTimeInNanoSecond:Integer[0..1]; connectionAcquisitionTimeInNanoSecond:Integer[0..1]; executionPlanInformation:String[0..1]; dataSource:rl::runtime::DataSource[0..1]}
CLASS	rl::metamodel::execute::ResultSet	extends Any	{executionTimeInNanoSecond:Integer[1]; connectionAcquisitionTimeInNanoSecond:Integer[1]; executionPlanInformation:String[0..1]; columnNames:String[*]; rows:rl::metamodel::execute::Row[*]}
CLASS	rl::metamodel::execute::Row	extends Any	{values:Any[*]; parent:rl::metamodel::execute::ResultSet[1]; /value():Any[1]}
CLASS	p::executionPlan::ExecutionPlan	extends Any	{rootExecutionNode:p::executionPlan::ExecutionNode[1]; processingTemplateFunctions:String[*]}
CLASS	p::executionPlan::ExecutionNode	extends Any	{executionNodes:p::executionPlan::ExecutionNode[*]}
CLASS	p::executionPlan::FunctionParametersValidationNode	extends p::executionPlan::ExecutionNode	{functionParameters:p::executionPlan::FunctionParameter[*]}
CLASS	p::graphFetch::executionPlan::GlobalGraphFetchExecutionNode	extends p::executionPlan::ExecutionNode	{}
CLASS	p::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode	extends p::graphFetch::executionPlan::GlobalGraphFetchExecutionNode	{}
CLASS	p::executionPlan::FunctionParameter	extends Any	{name:String[1]; supportsStream:Boolean[0..1]}
CLASS	rl::mapping::SQLExecutionNode	extends p::executionPlan::ExecutionNode	{sqlQuery:String[1]; sqlComment:String[0..1]; connection:meta::external::store::relational::runtime::DatabaseConnection[1]}
CLASS	rl::mapping::RelationalInstantiationExecutionNode	extends p::executionPlan::ExecutionNode	{}
CLASS	fn::Function<F>	extends Any	{}
CLASS	fn::FunctionDefinition<F>	extends fn::Function<F>	{}
CLASS	fn::ConcreteFunctionDefinition<F>	extends fn::FunctionDefinition<F>	{}
CLASS	fn::LambdaFunction<F>	extends fn::FunctionDefinition<F>	{}
CLASS	Class<T>	extends Type	{}
CLASS	rel::Column<T,X>	extends Any	{name:String[0..1]}
CLASS	Enumeration<T>	extends Type	{}
CLASS	m3::variant::Variant	extends Any	{}
CLASS	f::collection::List<T>	extends Any	{values:T[*]}
CLASS	f::collection::Pair<U,V>	extends Any	{first:U[1]; second:V[1]}
CLASS	f::collection::Map<U,V>	extends Any	{}
CLASS	f::math::mathUtility::RowMapper<T,U>	extends Any	{}
CLASS	p::graphFetch::RootGraphFetchTree<T>	extends Any	{}
CLASS	p::dataQuality::Checked<T>	extends Any	{}
CLASS	p::dataQuality::Defect	extends Any	{}
CLASS	f::relation::_Window<T>	extends Any	{}
CLASS	f::relation::SortInfo<T>	extends Any	{}
CLASS	f::relation::Frame	extends Any	{}
CLASS	f::relation::FrameValue	extends Any	{}
CLASS	f::relation::UnboundedFrameValue	extends f::relation::FrameValue	{}
CLASS	f::relation::_Range	extends f::relation::Frame	{}
CLASS	f::relation::_RangeInterval	extends f::relation::Frame	{}
CLASS	f::relation::Rows	extends f::relation::Frame	{}
CLASS	p::mapping::Result<T>	extends Any	{values:T[*]; activities:p::mapping::Activity[*]}
CLASS	p::tools::DebugContext	extends Any	{debug:Boolean[1]; space:String[1]}
CLASS	p::mapping::Activity	extends Any	{}
# --- NATIVE ENUMERATIONS (19)
ENUM	meta::external::query::sql::metamodel::JoinType	CROSS,INNER,LEFT,RIGHT,FULL
ENUM	meta::external::query::sql::metamodel::LogicalBinaryType	AND,OR
ENUM	meta::external::query::sql::metamodel::ComparisonOperator	EQUAL,NOT_EQUAL,LESS_THAN,LESS_THAN_OR_EQUAL,GREATER_THAN,GREATER_THAN_OR_EQUAL,IS_DISTINCT_FROM
ENUM	meta::external::query::sql::metamodel::SortItemOrdering	ASCENDING,DESCENDING
ENUM	meta::external::query::sql::metamodel::SortItemNullOrdering	FIRST,LAST,UNDEFINED
ENUM	rl::runtime::DatabaseType	DB2,H2,MemSQL,Sybase,SybaseIQ,Composite,Postgres,SqlServer,Hive,Snowflake,Presto,Trino,BigQuery,Redshift,Databricks,Spanner,Athena,Aurora,SparkSQL,DuckDB,Oracle,ClickHouse,DebugPrint
ENUM	p::executionPlan::features::Feature	PUSH_DOWN_ENUM_TRANSFORM,VARIANT_TYPE_AS_INPUT,LEGACY_SQL_NULL_UNSAFE_EQUALS
ENUM	f::date::DurationUnit	YEARS,MONTHS,WEEKS,DAYS,HOURS,MINUTES,SECONDS,MILLISECONDS,MICROSECONDS,NANOSECONDS
ENUM	f::date::Month	January,February,March,April,May,June,July,August,September,October,November,December
ENUM	f::date::Quarter	Q1,Q2,Q3,Q4
ENUM	f::date::DayOfWeek	Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,Sunday
ENUM	f::relation::SortType	ASC,DESC
ENUM	f::string::RegexpParameter	CASE_SENSITIVE,CASE_INSENSITIVE,MULTILINE,NON_NEWLINE_SENSITIVE
ENUM	f::date::StrictDateFormat	ISO8601
ENUM	f::date::DateTimeFormat	ISO8601_NanoSecondPrecision
ENUM	f::relation::JoinKind	LEFT,RIGHT,FULL,INNER
ENUM	f::hash::HashType	MD5,SHA1,SHA256
ENUM	rl::metamodel::SortDirection	ASC,DESC
ENUM	rl::metamodel::join::JoinType	INNER,LEFT_OUTER,RIGHT_OUTER,FULL_OUTER
# --- NATIVE FUNCTION OVERLOADS (721)
FN	p::graphFetch::execution::alloyConfig(includeType:Boolean[1], includeEnumType:Boolean[1], removePropertiesWithNullValues:Boolean[1], removePropertiesWithEmptySets:Boolean[1]):p::graphFetch::execution::AlloySerializationConfig[1]
FN	p::graphFetch::execution::alloyConfig(includeType:Boolean[1], includeEnumType:Boolean[1], removePropertiesWithNullValues:Boolean[1], removePropertiesWithEmptySets:Boolean[1], includeObjectReference:Boolean[1]):p::graphFetch::execution::AlloySerializationConfig[1]
FN	p::graphFetch::execution::alloyConfig(includeType:Boolean[1], includeEnumType:Boolean[1], removePropertiesWithNullValues:Boolean[1], removePropertiesWithEmptySets:Boolean[1], typeString:String[1], fullyQualifiedTypePath:Boolean[1]):p::graphFetch::execution::AlloySerializationConfig[1]
FN	p::graphFetch::execution::alloyConfig(includeType:Boolean[1], includeEnumType:Boolean[1], removePropertiesWithNullValues:Boolean[1], removePropertiesWithEmptySets:Boolean[1], typeString:String[1], fullyQualifiedTypePath:Boolean[1], includeObjectReference:Boolean[1]):p::graphFetch::execution::AlloySerializationConfig[1]
FN	p::graphFetch::execution::alloyConfig(includeType:Boolean[1], includeEnumType:Boolean[1], dateTimeFormat:String[1], removePropertiesWithNullValues:Boolean[1], removePropertiesWithEmptySets:Boolean[1], typeString:String[1], fullyQualifiedTypePath:Boolean[1], includeObjectReference:Boolean[1]):p::graphFetch::execution::AlloySerializationConfig[1]
FN	f::math::abs<T>(number:T[1]):T[1]
FN	f::math::acos(number:Number[1]):Float[1]
FN	f::collection::add<T>(set:T[*], index:Integer[1], val:T[1]):T[*]
FN	f::collection::add<T>(set:T[*], val:T[1]):T[*]
FN	f::date::calendar::annualized(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::cme(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::cw(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::cw_fm(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::CYMinus2(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::CYMinus3(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::mtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p12wa(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p12wtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p4wa(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p4wtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p52wtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p52wa(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::p12mtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pma(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pmtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pqtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::priorDay(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::priorYear(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pw(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pw_fm(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pwa(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pwtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pymtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pyqtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pytd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pywa(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::pywtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::qtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::reportEndDay(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::wtd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::calendar::ytd(date:Date[0..1], calendarType:String[1], endDate:Date[1], value:Number[0..1]):Number[0..1]
FN	f::date::adjust(d:Date[1], amount:Integer[1], unit:f::date::DurationUnit[1]):Date[1]
FN	lite::adjustTemporal(d:Date[1], amount:Integer[1], unit:f::date::DurationUnit[1]):Date[1]
FN	lite::trustOne<T>(values:T[*]):T[1]
FN	f::relation::aggregate<T,K,V,R>(r:rel::Relation<T>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<R>[1]
FN	f::relation::aggregate<T,K,V,R>(r:rel::Relation<T>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<R>[1]
FN	f::boolean::and(left:Boolean[1], right:Boolean[1]):Boolean[1]
FN	f::collection::and(bools:Boolean[*]):Boolean[1]
FN	f::relation::ascending<T>(column:rel::ColSpec<T>[1]):f::relation::SortInfo<T>[1]
FN	f::string::ascii(str:String[1]):Integer[1]
FN	p::tds::asc<T>(column:rel::ColSpec<T>[1]):f::relation::SortInfo<T>[1]
FN	f::math::asin(number:Number[1]):Float[1]
FN	f::relation::asOfJoin<T,V>(rel1:rel::Relation<T>[1], rel2:rel::Relation<V>[1], match:fn::Function<{T[1],V[1]->Boolean[1]}>[1]):rel::Relation<(T+V)>[1]
FN	f::relation::asOfJoin<T,V>(rel1:rel::Relation<T>[1], rel2:rel::Relation<V>[1], match:fn::Function<{T[1],V[1]->Boolean[1]}>[1], join:fn::Function<{T[1],V[1]->Boolean[1]}>[1]):rel::Relation<(T+V)>[1]
FN	f::relation::asOfJoin<T,V>(rel1:rel::Relation<T>[1], rel2:rel::Relation<V>[1], match:fn::Function<{T[1],V[1]->Boolean[1]}>[1], join:fn::Function<{T[1],V[1]->Boolean[1]}>[1], prefix:String[1]):rel::Relation<(T+V)>[1]
FN	f::math::atan2(y:Number[1], x:Number[1]):Float[1]
FN	f::math::atan(number:Number[1]):Float[1]
FN	f::collection::at<T>(set:T[*], index:Integer[1]):T[1]
FN	f::math::olap::averageRank():Number[1]
FN	f::math::average(numbers:Number[*]):Float[1]
FN	f::math::average<T>(partition:rel::Relation<T>[1], window:f::relation::_Window<T>[1], row:T[1], colToAgg:rel::ColSpec<((?:Number[1])?T)>[1]):Float[1]
FN	lite::avg(numbers:Number[*]):Float[1]
FN	f::boolean::between(value:Number[0..1], lower:Number[0..1], upper:Number[0..1]):Boolean[1]
FN	f::boolean::between(value:String[0..1], lower:String[0..1], upper:String[0..1]):Boolean[1]
FN	f::boolean::between(value:StrictDate[0..1], lower:StrictDate[0..1], upper:StrictDate[0..1]):Boolean[1]
FN	f::boolean::between(value:DateTime[0..1], lower:DateTime[0..1], upper:DateTime[0..1]):Boolean[1]
FN	f::math::bitAnd(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::bitOr(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::bitShiftLeft(value:Integer[1], bits:Integer[1]):Integer[1]
FN	f::math::bitShiftRight(value:Integer[1], bits:Integer[1]):Integer[1]
FN	f::math::bitXor(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::lang::cast<T|m>(source:Any[m], type:T[1]):T[m]
FN	f::lang::subType<T|m>(source:Any[m], object:T[1]):T[m]
FN	f::math::cbrt(number:Number[1]):Float[1]
FN	f::math::ceiling(number:Number[1]):Integer[1]
FN	f::string::char(code:Integer[1]):String[1]
FN	f::flow::coalesce<T>(value:T[0..1], ifEmpty:T[1]):T[1]
FN	f::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], ifEmpty:T[1]):T[1]
FN	f::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], value3:T[0..1], ifEmpty:T[1]):T[1]
FN	f::flow::coalesce<T>(value:T[0..1], ifEmpty:T[0..1]):T[0..1]
FN	f::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], ifEmpty:T[0..1]):T[0..1]
FN	f::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], value3:T[0..1], ifEmpty:T[0..1]):T[0..1]
FN	f::lang::compare(left:Any[1], right:Any[1]):Integer[1]
FN	f::collection::concatenate<T>(set1:T[*], set2:T[*]):T[*]
FN	f::relation::concatenate<T>(rel1:rel::Relation<T>[1], rel2:rel::Relation<T>[1]):rel::Relation<T>[1]
FN	f::collection::contains(collection:Any[*], val:Any[1]):Boolean[1]
FN	f::string::contains(source:String[1], val:String[1]):Boolean[1]
FN	f::collection::contains<T>(collection:T[*], val:T[1], comparator:fn::Function<{T[1],T[1]->Boolean[1]}>[1]):Boolean[1]
FN	f::math::corr(x:Number[*], y:Number[*]):Number[1]
FN	f::math::corr<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):Number[0..1]
FN	f::math::cosh(number:Number[1]):Float[1]
FN	f::math::cos(number:Number[1]):Float[1]
FN	f::math::cot(number:Number[1]):Float[1]
FN	f::collection::count<T>(values:T[*]):Integer[1]
FN	f::math::covarPopulation(x:Number[*], y:Number[*]):Number[1]
FN	f::math::covarPopulation<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):Number[0..1]
FN	f::math::covarSample(x:Number[*], y:Number[*]):Number[1]
FN	f::math::covarSample<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):Number[0..1]
FN	f::relation::cumulativeDistribution<T>(rel:rel::Relation<T>[1], w:f::relation::_Window<T>[1], row:T[1]):Float[1]
FN	f::runtime::currentUserId():String[1]
FN	f::date::dateDiff(d1:Date[1], d2:Date[1], du:f::date::DurationUnit[1]):Integer[1]
FN	f::date::year(d:Date[0..1]):Integer[0..1]
FN	f::date::monthNumber(d:Date[0..1]):Integer[0..1]
FN	f::date::weekOfYear(d:Date[0..1]):Integer[0..1]
FN	f::date::datePart(d:Date[0..1]):Date[0..1]
FN	f::date::dateDiff(d1:Date[0..1], d2:Date[0..1], du:f::date::DurationUnit[1]):Integer[0..1]
FN	f::date::datePart(d:Date[1]):StrictDate[1]
FN	f::date::date(year:Integer[1]):Date[1]
FN	f::date::date(year:Integer[1], month:Integer[1]):Date[1]
FN	f::date::date(year:Integer[1], month:Integer[1], day:Integer[1]):StrictDate[1]
FN	f::date::date(year:Integer[1], month:Integer[1], day:Integer[1], hour:Integer[1]):DateTime[1]
FN	f::date::date(year:Integer[1], month:Integer[1], day:Integer[1], hour:Integer[1], minute:Integer[1]):DateTime[1]
FN	f::date::date(year:Integer[1], month:Integer[1], day:Integer[1], hour:Integer[1], minute:Integer[1], second:Number[1]):DateTime[1]
FN	f::date::dayOfMonth(d:Date[1]):Integer[1]
FN	f::date::dayOfWeekNumber(d:Date[1]):Integer[1]
FN	f::date::dayOfWeekNumber(d:Date[1], firstDay:f::date::DayOfWeek[1]):Integer[1]
FN	f::date::dayOfWeek(d:Date[1]):f::date::DayOfWeek[1]
FN	f::date::mostRecentDayOfWeek(day:f::date::DayOfWeek[1]):Date[1]
FN	f::date::mostRecentDayOfWeek(d:Date[1], day:f::date::DayOfWeek[1]):Date[1]
FN	f::date::previousDayOfWeek(day:f::date::DayOfWeek[1]):Date[1]
FN	f::date::previousDayOfWeek(d:Date[1], day:f::date::DayOfWeek[1]):Date[1]
FN	f::date::firstDayOfWeek(d:Date[1]):Date[1]
FN	f::date::dayOfYear(d:Date[1]):Integer[1]
FN	f::string::decodeBase64(str:String[1]):String[1]
FN	f::relation::denseRank<T>(rel:rel::Relation<T>[1], w:f::relation::_Window<T>[1], row:T[1]):Integer[1]
FN	f::relation::descending<T>(column:rel::ColSpec<T>[1]):f::relation::SortInfo<T>[1]
FN	p::tds::desc<T>(column:rel::ColSpec<T>[1]):f::relation::SortInfo<T>[1]
FN	f::relation::distinct<T>(rel:rel::Relation<T>[1]):rel::Relation<T>[1]
FN	f::relation::distinct<X,T>(rel:rel::Relation<T>[1], columns:rel::ColSpecArray<(X?T)>[1]):rel::Relation<X>[1]
FN	lite::isNumeric(str:String[0..1]):Boolean[0..1]
FN	lite::convertTimeZoneFormat(d:DateTime[0..1], tz:String[1], fmt:String[1]):String[0..1]
FN	lite::convertDateFormat(str:String[0..1], fmt:String[1]):StrictDate[0..1]
FN	lite::convertDateTimeFormat(str:String[0..1], fmt:String[1]):DateTime[0..1]
FN	lite::parseDateFormat(str:String[0..1], fmt:String[1]):DateTime[0..1]
FN	lite::divideRound(left:Number[1], right:Number[1], scale:Integer[1]):Float[1]
FN	f::math::divide(dividend:Number[1], divisor:Number[1]):Float[1]
FN	f::math::divide(dividend:Number[1], divisor:Number[1], scale:Integer[1]):Decimal[1]
FN	f::relation::drop<T>(rel:rel::Relation<T>[1], size:Integer[1]):rel::Relation<T>[1]
FN	f::collection::drop<T>(set:T[*], count:Integer[1]):T[*]
FN	f::string::encodeBase64(str:String[1]):String[1]
FN	f::string::endsWith(source:String[1], val:String[1]):Boolean[1]
FN	f::string::endsWith(source:String[0..1], val:String[1]):Boolean[1]
FN	f::boolean::equal(left:Any[*], right:Any[*]):Boolean[1]
FN	f::boolean::eq(left:Any[1], right:Any[1]):Boolean[1]
FN	f::boolean::is(left:Any[1], right:Any[1]):Boolean[1]
FN	f::lang::eval<V|m>(func:fn::Function<{->V[m]}>[1]):V[m]
FN	f::lang::eval<T,V|m,n>(func:fn::Function<{T[n]->V[m]}>[1], param:T[n]):V[m]
FN	f::lang::eval<T,U,V|m,n,p>(func:fn::Function<{T[n],U[p]->V[m]}>[1], param1:T[n], param2:U[p]):V[m]
FN	f::collection::exists<T>(value:T[*], func:fn::Function<{T[1]->Boolean[1]}>[1]):Boolean[1]
FN	f::math::exp(exponent:Number[1]):Float[1]
FN	f::relation::extend<C,Z>(cl:C[*], f:rel::FuncColSpec<{C[1]->Any[0..1]},Z>[1]):C[*]
FN	f::relation::extend<C,Z>(cl:C[*], fs:rel::FuncColSpecArray<{C[1]->Any[*]},Z>[1]):C[*]
FN	f::relation::extend<T,K,V,R>(r:rel::Relation<T>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::extend<T,K,V,R>(r:rel::Relation<T>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::extend<T,Z>(r:rel::Relation<T>[1], f:rel::FuncColSpec<{T[1]->Any[0..1]},Z>[1]):rel::Relation<(T+Z)>[1]
FN	f::relation::extend<T,Z>(r:rel::Relation<T>[1], fs:rel::FuncColSpecArray<{T[1]->Any[*]},Z>[1]):rel::Relation<(T+Z)>[1]
FN	f::relation::extend<T,K,V,R>(r:rel::Relation<T>[1], window:f::relation::_Window<T>[1], agg:rel::AggColSpec<{rel::Relation<T>[1],f::relation::_Window<T>[1],T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::extend<T,K,V,R>(r:rel::Relation<T>[1], window:f::relation::_Window<T>[1], agg:rel::AggColSpecArray<{rel::Relation<T>[1],f::relation::_Window<T>[1],T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::extend<T,Z,W,R>(r:rel::Relation<T>[1], window:f::relation::_Window<T>[1], f:rel::FuncColSpec<{rel::Relation<T>[1],f::relation::_Window<T>[1],T[1]->Any[0..1]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::extend<T,Z,W,R>(r:rel::Relation<T>[1], window:f::relation::_Window<T>[1], f:rel::FuncColSpecArray<{rel::Relation<T>[1],f::relation::_Window<T>[1],T[1]->Any[*]},R>[1]):rel::Relation<(T+R)>[1]
FN	f::relation::filter<T>(rel:rel::Relation<T>[1], f:fn::Function<{T[1]->Boolean[1]}>[1]):rel::Relation<T>[1]
FN	p::tds::filter<T>(rel:rel::Relation<T>[1], f:fn::Function<{T[1]->Boolean[1]}>[1]):rel::Relation<T>[1]
FN	f::collection::filter<T>(value:T[*], func:fn::Function<{T[1]->Boolean[1]}>[1]):T[*]
FN	f::collection::find<T>(value:T[*], func:fn::Function<{T[1]->Boolean[1]}>[1]):T[0..1]
FN	f::date::firstDayOfMonth(d:Date[1]):Date[1]
FN	f::date::firstDayOfQuarter(d:Date[1]):StrictDate[1]
FN	f::date::firstDayOfYear(d:Date[1]):Date[1]
FN	f::lang::extractEnumValue<T>(enum:Enumeration<T>[1], value:String[1]):T[1]
FN	f::date::firstDayOfThisYear():Date[1]
FN	f::date::firstDayOfThisMonth():Date[1]
FN	f::date::firstDayOfThisQuarter():StrictDate[1]
FN	f::date::firstHourOfDay(d:Date[1]):DateTime[1]
FN	f::date::firstMillisecondOfSecond(d:Date[1]):DateTime[1]
FN	f::date::firstMinuteOfHour(d:Date[1]):DateTime[1]
FN	f::date::firstSecondOfMinute(d:Date[1]):DateTime[1]
FN	f::relation::first<T>(w:rel::Relation<T>[1], f:f::relation::_Window<T>[1], r:T[1]):T[0..1]
FN	f::collection::first<T>(set:T[*]):T[0..1]
FN	f::collection::first<T>(set:T[*], count:Integer[1]):T[*]
FN	f::relation::variant::flatten<T,Z>(valueToFlatten:T[*], columnWithFlattenedValue:rel::ColSpec<(Z=(?:T[1]))>[1]):rel::Relation<Z>[1]
FN	f::math::floor(number:Number[1]):Integer[1]
FN	f::collection::fold<T,V|m>(source:T[*], lambda:fn::Function<{T[1],V[m]->V[m]}>[1], init:V[m]):V[m]
FN	f::string::format(format:String[1], args:Any[*]):String[1]
FN	f::collection::forAll<T>(value:T[*], func:fn::Function<{T[1]->Boolean[1]}>[1]):Boolean[1]
FN	f::date::fromEpochValue(epoch:Integer[1]):Date[1]
FN	f::date::fromEpochValue(epoch:Integer[1], unit:f::date::DurationUnit[1]):Date[1]
FN	p::mapping::from<T>(source:rel::Relation<T>[1]):rel::Relation<T>[1]
FN	p::mapping::from<T>(source:rel::Relation<T>[1], runtime:Any[1]):rel::Relation<T>[1]
FN	p::mapping::from<T|m>(source:T[m], mapping:Any[1], runtime:Any[1]):T[m]
FN	p::mapping::withChainedMappings<T>(source:T[*], mappings:p::mapping::Mapping[*]):T[*]
FN	f::string::generation::generateGuid():String[1]
FN	f::meta::genericType(any:Any[*]):generics::GenericType[1]
FN	f::meta::getHiddenPayload(o:Any[1]):Any[1]
FN	f::collection::getAll<T>(class:Class<T>[1]):T[*]
FN	f::collection::getAll<T>(class:Class<T>[1], date:Date[1]):T[*]
FN	f::collection::getAll<T>(class:Class<T>[1], from:Date[1], to:Date[1]):T[*]
FN	f::collection::getAllForEachDate<T>(type:Class<T>[1], dates:Date[*]):T[*]
FN	f::collection::getAllVersions<T>(class:Class<T>[1]):T[*]
FN	f::collection::getAllVersionsInRange<T>(class:Class<T>[1], start:Date[1], end:Date[1]):T[*]
FN	f::variant::navigation::get(variant:m3::variant::Variant[0..1], key:String[1]):m3::variant::Variant[0..1]
FN	f::variant::navigation::get(variant:m3::variant::Variant[0..1], index:Integer[1]):m3::variant::Variant[0..1]
FN	p::graphFetch::execution::graphFetch<T>(source:T[*], col:rel::ColSpec<T>[1]):T[*]
FN	p::graphFetch::execution::graphFetch<T>(source:T[*], cols:rel::ColSpecArray<T>[1]):T[*]
FN	p::graphFetch::execution::graphFetch<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1]):T[*]
FN	p::graphFetch::execution::graphFetch<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1], batchSize:Integer[1]):T[*]
FN	p::graphFetch::execution::graphFetchChecked<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1]):p::dataQuality::Checked[*]
FN	p::graphFetch::execution::graphFetchChecked<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1], batchSize:Integer[1]):p::dataQuality::Checked[*]
FN	f::boolean::greaterThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Date[0..1], right:Date[1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Date[1], right:Date[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Date[1], right:Date[1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Number[0..1], right:Number[1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Number[1], right:Number[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Number[1], right:Number[1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:String[0..1], right:String[1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:String[1], right:String[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:String[1], right:String[1]):Boolean[1]
FN	f::boolean::greaterThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:Date[0..1], right:Date[1]):Boolean[1]
FN	f::boolean::greaterThan(left:Date[1], right:Date[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:Date[1], right:Date[1]):Boolean[1]
FN	f::boolean::greaterThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:Number[0..1], right:Number[1]):Boolean[1]
FN	f::boolean::greaterThan(left:Number[1], right:Number[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:Number[1], right:Number[1]):Boolean[1]
FN	f::boolean::greaterThan(left:String[0..1], right:String[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:String[0..1], right:String[1]):Boolean[1]
FN	f::boolean::greaterThan(left:String[1], right:String[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:String[1], right:String[1]):Boolean[1]
FN	f::collection::greatest<X>(values:X[*]):X[0..1]
FN	f::collection::greatest<X>(values:X[1..*]):X[1]
FN	p::tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:rel::FuncColSpecArray<{C[1]->Any[*]},Z>[1], aggs:rel::AggColSpec<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	p::tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:rel::FuncColSpecArray<{C[1]->Any[*]},Z>[1], aggs:rel::AggColSpecArray<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	f::collection::groupBy<K,V,U>(set:K[*], fns:fn::Function<{K[1]->Any[*]}>[*], aggs:Any[*], ids:String[*]):rel::Relation<K>[1]
FN	f::relation::groupBy<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	f::relation::groupBy<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	f::relation::groupBy<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpecArray<(Z?T)>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	f::relation::groupBy<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpecArray<(Z?T)>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<(Z+R)>[1]
FN	f::hash::hashCode(val:Any[*]):Integer[1]
FN	lite::hash(str:String[1]):String[1]
FN	f::hash::hash(str:String[1], algorithm:f::hash::HashType[1]):String[1]
FN	f::date::hasDay(d:Date[1]):Boolean[1]
FN	f::date::hasHour(d:Date[1]):Boolean[1]
FN	f::date::hasMinute(d:Date[1]):Boolean[1]
FN	f::date::hasMonth(d:Date[1]):Boolean[1]
FN	f::date::hasSecond(d:Date[1]):Boolean[1]
FN	f::date::hasSubsecondWithAtLeastPrecision(d:Date[1], precision:Integer[1]):Boolean[1]
FN	f::date::hasSubsecond(d:Date[1]):Boolean[1]
FN	f::collection::head<T>(set:T[*]):T[0..1]
FN	f::date::hour(d:Date[1]):Integer[1]
FN	f::lang::if<T|m>(test:Boolean[1], then:fn::Function<{->T[m]}>[1], else:fn::Function<{->T[m]}>[1]):T[m]
FN	f::lang::if<T|m>(condList:f::collection::Pair<fn::Function<{->Boolean[1]}>,fn::Function<{->T[m]}>>[*], last:fn::Function<{->T[m]}>[1]):T[m]
FN	f::string::indexOf(str:String[1], toFind:String[1]):Integer[1]
FN	f::string::indexOf(str:String[1], toFind:String[1], fromIndex:Integer[1]):Integer[1]
FN	f::collection::indexOf<T>(set:T[*], value:T[1]):Integer[1]
FN	f::collection::init<T>(set:T[*]):T[*]
FN	f::meta::instanceOf(instance:Any[1], type:Type[1]):Boolean[1]
FN	f::collection::in(value:Any[1], collection:Any[*]):Boolean[1]
FN	f::collection::in(value:Any[0..1], collection:Any[*]):Boolean[1]
FN	f::date::isAfterDay(d1:Date[1], d2:Date[1]):Boolean[1]
FN	f::date::isBeforeDay(d1:Date[1], d2:Date[1]):Boolean[1]
FN	f::collection::uniqueValueOnly<T>(values:T[*]):T[0..1]
FN	f::collection::uniqueValueOnly<T>(values:T[*], defaultValue:T[0..1]):T[0..1]
FN	f::collection::isDistinct<T>(set:T[*]):Boolean[1]
FN	f::collection::isDistinct(left:Any[1], right:Any[1]):Boolean[1]
FN	f::collection::isEmpty<T>(value:T[*]):Boolean[1]
FN	f::collection::isNotEmpty<T>(value:T[*]):Boolean[1]
FN	f::date::isOnDay(d1:Date[1], d2:Date[1]):Boolean[1]
FN	f::date::isOnOrAfterDay(d1:Date[1], d2:Date[1]):Boolean[1]
FN	f::date::isOnOrBeforeDay(d1:Date[1], d2:Date[1]):Boolean[1]
FN	f::string::jaroWinklerSimilarity(s1:String[1], s2:String[1]):Float[1]
FN	f::string::makeString(any:Any[*]):String[1]
FN	f::string::makeString(any:Any[*], separator:String[1]):String[1]
FN	f::string::makeString(any:Any[*], prefix:String[1], separator:String[1], suffix:String[1]):String[1]
FN	f::string::joinStrings(strings:String[*]):String[1]
FN	f::string::joinStrings(strings:String[*], separator:String[1]):String[1]
FN	f::string::joinStrings(strings:String[*], prefix:String[1], separator:String[1], suffix:String[1]):String[1]
FN	f::relation::join<T,V>(rel1:rel::Relation<T>[1], rel2:rel::Relation<V>[1], joinKind:f::relation::JoinKind[1], f:fn::Function<{T[1],V[1]->Boolean[1]}>[1]):rel::Relation<(T+V)>[1]
FN	lite::join<S,T,Z>(rel:rel::Relation<S>[1], slot:rel::FuncColSpec<{->rel::Relation<T>[1]},Z>[1], cond:fn::Function<{S[1],T[1]->Boolean[1]}>[1]):rel::Relation<(S+Z)>[1]
FN	f::relation::join<T,V>(rel1:rel::Relation<T>[1], rel2:rel::Relation<V>[1], joinKind:f::relation::JoinKind[1], f:fn::Function<{T[1],V[1]->Boolean[1]}>[1], prefix:String[1]):rel::Relation<(T+V)>[1]
FN	f::relation::lag<T>(w:rel::Relation<T>[1], r:T[1]):T[0..1]
FN	f::relation::lag<T>(w:rel::Relation<T>[1], r:T[1], offset:Integer[1]):T[0..1]
FN	f::relation::last<T>(w:rel::Relation<T>[1], f:f::relation::_Window<T>[1], row:T[1]):T[0..1]
FN	f::collection::last<T>(set:T[*]):T[0..1]
FN	f::relation::lead<T>(w:rel::Relation<T>[1], r:T[1]):T[0..1]
FN	f::relation::lead<T>(w:rel::Relation<T>[1], r:T[1], offset:Integer[1]):T[0..1]
FN	f::collection::least<X>(values:X[*]):X[0..1]
FN	f::collection::least<X>(values:X[1..*]):X[1]
FN	f::string::left(str:String[1], len:Integer[1]):String[1]
FN	lite::navigate<S,T,Z>(rel:rel::Relation<S>[1], target:rel::FuncColSpec<{->T[*]},Z>[1], pred:fn::Function<{S[1],T[1]->Boolean[1]}>[1]):rel::Relation<(S+Z)>[1]
FN	lite::navigate<C,T,Z>(cl:C[*], target:rel::FuncColSpec<{->T[*]},Z>[1], pred:fn::Function<{C[1],T[1]->Boolean[1]}>[1]):C[*]
FN	lite::navigate<T>(target:T[*], pred:fn::Function<{T[1]->Boolean[1]}>[1]):T[*]
FN	lite::legacyNavigate<S,C,T,Z>(rel:rel::Relation<S>[1], target:rel::FuncColSpec<{->C[*]},Z>[1], tgtRows:rel::Relation<T>[1], cond:fn::Function<{S[1],T[1]->Boolean[1]}>[1]):rel::Relation<(S+Z)>[1]
FN	lite::legacyNavigate<S,C,T,Z>(rel:rel::Relation<S>[1], target:rel::FuncColSpec<{->C[*]},Z>[1], tgtRows:rel::Relation<T>[1], cond:fn::Function<{S[1],T[1]->Boolean[1]}>[1], pairedCond:fn::Function<{S[1],T[1]->Boolean[1]}>[1]):rel::Relation<(S+Z)>[1]
FN	lite::typeAsDeclared<T>(value:Any[0..1], type:T[1]):T[0..1]
FN	lite::castAsDeclared<T>(value:Any[0..1], type:T[1]):T[0..1]
FN	f::meta::id(instance:Any[1]):String[1]
FN	rl::functions::toPostgresModel::convertElement(r:rl::metamodel::RelationalOperationElement[1], state:rl::functions::toPostgresModel::ModelConversionState[1]):meta::external::query::sql::metamodel::Node[1]
FN	rl::functions::toPostgresModel::newState():rl::functions::toPostgresModel::ModelConversionState[1]
FN	rl::functions::toPostgresModel::convertSelectSqlQuery(select:rl::metamodel::relation::SelectSQLQuery[1], state:rl::functions::toPostgresModel::ModelConversionState[1]):meta::external::query::sql::metamodel::Query[1]
FN	p::mapping::rootClassMappingByClass(_this:p::mapping::Mapping[1], class:Class<Any>[1]):p::mapping::SetImplementation[0..1]
FN	p::mapping::_classMappingByClass(_this:p::mapping::Mapping[1], class:Class<Any>[1]):p::mapping::SetImplementation[*]
FN	p::mapping::propertyMappingsByPropertyName(i:p::mapping::InstanceSetImplementation[1], propertyName:String[1]):p::mapping::PropertyMapping[*]
FN	p::mapping::classMappingById(_this:p::mapping::Mapping[1], id:String[1]):p::mapping::SetImplementation[0..1]
FN	p::mapping::superMapping(_this:p::mapping::PropertyMappingsImplementation[1]):p::mapping::PropertyMappingsImplementation[0..1]
FN	p::mapping::allSuperSetImplementations(set:p::mapping::PropertyMappingsImplementation[1], m:p::mapping::Mapping[1]):p::mapping::PropertyMappingsImplementation[*]
FN	rl::metamodel::mainTable(_this:rl::metamodel::RelationalMappingSpecification[1]):rl::metamodel::relation::Table[1]
FN	rl::mapping::resolvePrimaryKey(_this:rl::mapping::RootRelationalInstanceSetImplementation[1]):rl::metamodel::RelationalOperationElement[*]
FN	rl::metamodel::view(_this:rl::metamodel::Schema[1], name:String[1]):rl::metamodel::relation::View[0..1]
FN	rl::functions::typeInference::inferRelationalType(rop:rl::metamodel::RelationalOperationElement[1]):rl::metamodel::datatype::DataType[0..1]
FN	rl::metamodel::datatype::dataTypeToSqlText(type:rl::metamodel::datatype::DataType[1]):String[1]
FN	rl::metamodel::schema(_this:rl::metamodel::Database[1], name:String[1]):rl::metamodel::Schema[0..1]
FN	rl::postProcessor::cteExtraction::extractSubqueriesAsCTEs(select:rl::metamodel::relation::SelectSQLQuery[1]):rl::metamodel::relation::SelectSQLQuery[1]
FN	rl::postProcessor::cteExtraction::extractSubQueriesAsCTEsPostProcessor(s:rl::postProcessor::cteExtraction::ExtractSubQueriesAsCTEsPostProcessor[1]):rl::runtime::PostProcessorWithParameter[1]
FN	p::alloy::connections::relationalMapperPostProcessor(mapper:p::alloy::connections::RelationalMapperPostProcessor[1]):rl::runtime::PostProcessorWithParameter[1]
FN	rl::postProcessor::replaceTables(selectSQLQuery:rl::metamodel::relation::SelectSQLQuery[1], oldToNewPairs:f::collection::Pair<rl::metamodel::relation::Table,rl::metamodel::relation::Table>[*]):p::mapping::Result<rl::metamodel::relation::SelectSQLQuery,|1>[1]
FN	rl::postProcessor::nonExecutable(selectSQLQuery:rl::metamodel::relation::SelectSQLQuery[1], extensions:p::extension::Extension[*]):p::mapping::Result<rl::metamodel::relation::SelectSQLQuery,|1>[1]
FN	rl::metamodel::table(_this:rl::metamodel::Schema[1], name:String[1]):rl::metamodel::relation::Table[0..1]
FN	f::meta::evaluateAndDeactivate<T|m>(var:T[m]):T[m]
FN	rl::metamodel::execute::executeInDb(sql:String[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], timeOutInSeconds:Integer[1], fetchSize:Integer[1]):rl::metamodel::execute::ResultSet[1]
FN	rl::metamodel::execute::executeInDb(sql:String[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1]):rl::metamodel::execute::ResultSet[1]
FN	rl::metamodel::execute::fetchDbTablesMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:String[0..1], tablePattern:String[0..1]):rl::metamodel::execute::ResultSet[1]
FN	rl::metamodel::execute::fetchDbColumnsMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:String[0..1], tablePattern:String[0..1], columnPattern:String[0..1]):rl::metamodel::execute::ResultSet[1]
FN	rl::metamodel::execute::fetchDbSchemasMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:String[0..1]):rl::metamodel::execute::ResultSet[1]
FN	rl::metamodel::execute::fetchDbPrimaryKeysMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:String[0..1], tableName:String[1]):rl::metamodel::execute::ResultSet[1]
FN	meta::core::runtime::connectionByElement(runtime:meta::core::runtime::Runtime[1], store:Any[1]):meta::core::runtime::Connection[1]
FN	p::tools::noDebug():p::tools::DebugContext[1]
FN	p::mapping::execute<T>(f:fn::Function<{->T[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):p::mapping::Result<T>[1]
FN	p::mapping::execute<T>(f:fn::Function<{->T[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*], debug:Any[1]):p::mapping::Result<T>[1]
FN	p::router::execute<T>(f:fn::Function<{->T[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):p::mapping::Result<T>[1]
FN	p::router::execute<T>(f:fn::Function<{->T[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*], debug:Any[1]):p::mapping::Result<T>[1]
FN	p::router::preeval::preval<T>(f:fn::Function<{->T[*]}>[1], extensions:Any[*]):fn::Function<{->T[*]}>[1]
FN	p::router::preeval::preval<T>(f:fn::Function<{->T[*]}>[1], extensions:Any[*], debug:p::tools::DebugContext[1]):fn::Function<{->T[*]}>[1]
FN	rl::milestoning::concatenateTemporalTdsQueries<T>(lfs:fn::Function<{->T[*]}>[*]):fn::Function<{->T[*]}>[1]
FN	p::executionPlan::featureFlag::withFeatureFlags<T>(object:T[*], e:Any[*]):T[*]
FN	rl::extension::relationalExtensions():Any[*]
FN	meta::alloy::service::execution::setUpDataSQLsV2(csv:String[1], db:Any[1], dbConfig:Any[1]):String[*]
FN	meta::alloy::service::execution::setUpDataSQLs(records:f::collection::List<String>[*], db:Any[*], dbConfig:Any[1]):String[*]
FN	meta::alloy::service::execution::setUpDataSQLs(csv:String[1], db:Any[*]):String[*]
FN	meta::alloy::service::execution::setUpDataSQLs(csv:String[1], db:Any[*], dbConfig:Any[1]):String[*]
FN	p::executionPlan::executionPlan(func:fn::Function<{->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{->Any[*]}>[1], mapping:Any[1], runtime:Any[1], exeCtx:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*], debugContext:Any[1]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::toString::planToString(plan:Any[1], extensions:Any[*]):String[1]
FN	p::executionPlan::toString::planToStringWithoutFormatting(plan:Any[1], extensions:Any[*]):String[1]
FN	p::executionPlan::allNodes(node:p::executionPlan::ExecutionNode[1], extensions:Any[*]):p::executionPlan::ExecutionNode[*]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1]->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1]->Any[*]}>[1], mapping:Any[1], runtime:Any[1], exeCtx:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1],Any[1]->Any[*]}>[1], mapping:Any[1], runtime:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1],Any[1]->Any[*]}>[1], mapping:Any[1], runtime:Any[1], exeCtx:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{->Any[*]}>[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1]->Any[*]}>[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{Any[1],Any[1]->Any[*]}>[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	p::executionPlan::executionPlan(func:fn::Function<{->Any[*]}>[1], context:Any[1], extensions:Any[*]):p::executionPlan::ExecutionPlan[1]
FN	rl::functions::sqlQueryToString::createDbConfig(dbType:Any[1]):Any[1]
FN	rl::functions::sqlQueryToString::createDbConfig(dbType:Any[1], dbTimeZone:String[0..1]):Any[1]
FN	rl::functions::sqlstring::toSQLString(f:fn::Function<{->Any[*]}>[1], mapping:Any[1], databaseType:Any[1], extensions:Any[*]):String[1]
FN	rl::functions::sqlstring::toSQLStringPretty(f:fn::Function<{->Any[*]}>[1], mapping:Any[1], databaseTypeOrRuntime:Any[1], extensions:Any[*]):String[1]
FN	rl::functions::toDDL::dropSchemaStatement(schema:String[1]):String[1]
FN	rl::functions::toDDL::createSchemaStatement(schema:String[1]):String[1]
FN	rl::functions::toDDL::createTableStatement(database:rl::metamodel::Database[1], schema:String[1], tableName:String[1]):String[1]
FN	f::relation::toString<T>(rel:rel::Relation<T>[1]):String[1]
FN	f::relation::toString<T>(rel:rel::Relation<T>[1], typesAndMuls:Boolean[1]):String[1]
FN	rl::tests::csv::toCSV(t:p::tds::TabularDataSet[1]):String[1]
FN	rl::tests::csv::toCSV(t:p::tds::TabularDataSet[1], renderTdsNull:Boolean[1]):String[1]
FN	rl::tests::csv::toCSV(t:p::tds::TabularDataSet[1], dateTimeFormat:String[1], dateFormat:String[1], renderTdsNull:Boolean[1]):String[1]
FN	rl::functions::toDDL::createTableStatement(database:rl::metamodel::Database[1], tableName:String[1]):String[1]
FN	rl::functions::toDDL::dropTableStatement(database:rl::metamodel::Database[1], tableName:String[1]):String[1]
FN	rl::functions::toDDL::dropTableStatement(database:rl::metamodel::Database[1], schema:String[1], tableName:String[1]):String[1]
FN	rl::functions::toDDL::dropAndCreateTableInDb(database:rl::metamodel::Database[1], tableName:String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):Boolean[1]
FN	rl::functions::toDDL::dropAndCreateTableInDb(database:rl::metamodel::Database[1], schema:String[1], tableName:String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):Boolean[1]
FN	rl::functions::toDDL::dropAndCreateSchemaInDb(schema:String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):Boolean[1]
FN	f::io::print(param:Any[*], max:Integer[1]):Nil[0]
FN	f::io::print(param:Any[*]):Nil[0]
FN	f::io::println(param:Any[*], max:Integer[1]):Nil[0]
FN	f::io::println(param:Any[*]):Nil[0]
FN	rl::functions::toDDL::dropAndCreateSchemaInDb(schema:String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1], debug:Boolean[1]):Boolean[1]
FN	lite::legacyAssocPredicate<A,B,S,T>(a:A[1], b:B[1], src:rel::Relation<S>[1], tgt:rel::Relation<T>[1], cond:fn::Function<{S[1],T[1]->Boolean[1]}>[1]):Boolean[1]
FN	lite::legacyAssocPredicate<A,B>(a:A[1], b:B[1], srcSet:String[1], tgtSet:String[1], cond:fn::Function<{A[1],B[1]->Boolean[1]}>[1]):Boolean[1]
FN	lite::legacyLocalProperty(row:Any[1], prop:String[1]):Any[1]
FN	f::string::length(str:String[1]):Integer[1]
FN	f::boolean::lessThanEqual(left:Date[0..1], right:Date[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Date[0..1], right:Date[1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Date[1], right:Date[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Date[1], right:Date[1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Number[0..1], right:Number[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Number[0..1], right:Number[1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Number[1], right:Number[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Number[1], right:Number[1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:String[0..1], right:String[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:String[0..1], right:String[1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:String[1], right:String[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:String[1], right:String[1]):Boolean[1]
FN	f::boolean::lessThan(left:Date[0..1], right:Date[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
FN	f::boolean::lessThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
FN	f::boolean::greaterThan(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
FN	f::boolean::greaterThanEqual(left:Boolean[0..1], right:Boolean[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:Date[0..1], right:Date[1]):Boolean[1]
FN	f::boolean::lessThan(left:Date[1], right:Date[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:Date[1], right:Date[1]):Boolean[1]
FN	f::boolean::lessThan(left:Number[0..1], right:Number[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:Number[0..1], right:Number[1]):Boolean[1]
FN	f::boolean::lessThan(left:Number[1], right:Number[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:Number[1], right:Number[1]):Boolean[1]
FN	f::boolean::lessThan(left:String[0..1], right:String[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:String[0..1], right:String[1]):Boolean[1]
FN	f::boolean::lessThan(left:String[1], right:String[0..1]):Boolean[1]
FN	f::boolean::lessThan(left:String[1], right:String[1]):Boolean[1]
FN	f::lang::letFunction<T|m>(name:String[1], value:T[m]):T[m]
FN	f::string::levenshteinDistance(s1:String[1], s2:String[1]):Integer[1]
FN	f::relation::limit<T>(rel:rel::Relation<T>[1], size:Integer[1]):rel::Relation<T>[1]
FN	f::relation::limit<T>(rel:rel::Relation<T>[1], size:Integer[0..1]):rel::Relation<T>[1]
FN	f::collection::limit<T>(set:T[*], size:Integer[1]):T[*]
FN	f::collection::list<T>(values:T[*]):f::collection::List<T>[1]
FN	f::math::log10(value:Number[1]):Float[1]
FN	f::math::log(value:Number[1]):Float[1]
FN	f::string::lpad(str:String[1], len:Integer[1]):String[1]
FN	f::string::lpad(str:String[1], len:Integer[1], pad:String[1]):String[1]
FN	f::string::ltrim(str:String[1]):String[1]
FN	f::relation::map<T,V>(rel:rel::Relation<T>[1], f:fn::Function<{T[1]->V[*]}>[1]):V[*]
FN	f::collection::map<T,V|m>(value:T[m], func:fn::Function<{T[1]->V[1]}>[1]):V[m]
FN	f::collection::map<T,V>(value:T[0..1], func:fn::Function<{T[1]->V[0..1]}>[1]):V[0..1]
FN	f::collection::map<T,V>(value:T[*], func:fn::Function<{T[1]->V[*]}>[1]):V[*]
FN	f::string::matches(str:String[1], regex:String[1]):Boolean[1]
FN	f::string::regexpLike(string:String[1], regexp:String[1]):Boolean[1]
FN	f::string::regexpLike(string:String[1], regexp:String[1], regexpParameters:f::string::RegexpParameter[1..*]):Boolean[1]
FN	f::string::regexpCount(string:String[1], regexp:String[1]):Integer[1]
FN	f::string::regexpCount(string:String[1], regexp:String[1], regexpParameters:f::string::RegexpParameter[1..*]):Integer[1]
FN	f::string::regexpExtract(string:String[1], regexp:String[1], extractAll:Boolean[1]):String[*]
FN	f::string::regexpExtract(string:String[1], regexp:String[1], extractAll:Boolean[1], groupNumber:Integer[1]):String[*]
FN	f::string::regexpExtract(string:String[1], regexp:String[1], extractAll:Boolean[1], regexpParameters:f::string::RegexpParameter[1..*]):String[*]
FN	f::string::regexpExtract(string:String[1], regexp:String[1], extractAll:Boolean[1], groupNumber:Integer[1], regexpParameters:f::string::RegexpParameter[1..*]):String[*]
FN	f::string::regexpIndexOf(string:String[1], regexp:String[1]):Integer[1]
FN	f::string::regexpIndexOf(string:String[1], regexp:String[1], groupNumber:Integer[1]):Integer[1]
FN	f::string::regexpIndexOf(string:String[1], regexp:String[1], regexpParameters:f::string::RegexpParameter[1..*]):Integer[1]
FN	f::string::regexpIndexOf(string:String[1], regexp:String[1], groupNumber:Integer[1], regexpParameters:f::string::RegexpParameter[1..*]):Integer[1]
FN	f::string::regexpReplace(string:String[1], regexp:String[1], replacement:String[1], replaceAll:Boolean[1]):String[1]
FN	f::string::regexpReplace(string:String[1], regexp:String[1], replacement:String[1], replaceAll:Boolean[1], regexpParameters:f::string::RegexpParameter[1..*]):String[1]
FN	f::math::bitNot(arg:Integer[1]):Integer[1]
FN	f::math::zScore<T>(partition:rel::Relation<T>[1], window:f::relation::_Window<T>[1], row:T[1], colToZScore:rel::ColSpec<((?:Number[1])?T)>[1]):Float[1]
FN	f::date::formatDate(date:StrictDate[1], dateFormat:f::date::StrictDateFormat[1]):String[1]
FN	f::date::formatDate(dateTime:DateTime[1], dateTimeFormat:f::date::DateTimeFormat[1]):String[1]
FN	f::lang::match<T|m,n>(var:Any[*], functions:fn::Function<{Nil[n]->T[m]}>[1..*]):T[m]
FN	f::lang::match<T,P|m,n,o>(var:Any[*], functions:fn::Function<{Nil[n],P[o]->T[m]}>[1..*], with:P[o]):T[m]
FN	f::math::maxBy<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):T[0..1]
FN	f::math::maxBy<T>(values:T[*], key:fn::Function<{T[1]->Any[1]}>[1]):T[0..1]
FN	f::math::maxBy<T>(values:T[*], key:fn::Function<{T[1]->Any[1]}>[1], count:Integer[1]):T[*]
FN	f::math::maxBy<T>(values:T[*], keys:T[*]):T[0..1]
FN	f::math::maxBy<T>(values:T[*], keys:T[*], count:Integer[1]):T[*]
FN	f::date::max(left:Date[1], right:Date[1]):Date[1]
FN	f::date::max(dates:Date[*]):Date[0..1]
FN	f::date::max(left:DateTime[1], right:DateTime[1]):DateTime[1]
FN	f::date::max(dates:DateTime[*]):DateTime[0..1]
FN	f::math::max(left:Float[1], right:Float[1]):Float[1]
FN	f::math::max(values:Float[*]):Float[0..1]
FN	f::math::max(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::max(values:Integer[*]):Integer[0..1]
FN	f::math::max(left:Number[1], right:Number[1]):Number[1]
FN	f::math::max(numbers:Number[*]):Number[0..1]
FN	f::date::max(left:StrictDate[1], right:StrictDate[1]):StrictDate[1]
FN	f::date::max(dates:StrictDate[*]):StrictDate[0..1]
FN	f::math::mean(numbers:Number[*]):Float[1]
FN	f::math::median(numbers:Number[*]):Float[1]
FN	f::math::minus(left:Decimal[1], right:Decimal[1]):Decimal[1]
FN	f::math::minus(left:Float[1], right:Float[1]):Float[1]
FN	f::math::minus(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::minus(left:Number[1], right:Number[1]):Number[1]
FN	f::math::minus<T>(values:T[*]):T[1]
FN	f::date::minute(d:Date[1]):Integer[1]
FN	f::math::minBy<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):T[0..1]
FN	f::math::minBy<T>(values:T[*], key:fn::Function<{T[1]->Any[1]}>[1]):T[0..1]
FN	f::math::minBy<T>(values:T[*], key:fn::Function<{T[1]->Any[1]}>[1], count:Integer[1]):T[*]
FN	f::math::minBy<T>(values:T[*], keys:T[*]):T[0..1]
FN	f::math::minBy<T>(values:T[*], keys:T[*], count:Integer[1]):T[*]
FN	f::date::min(left:Date[1], right:Date[1]):Date[1]
FN	f::date::min(dates:Date[*]):Date[0..1]
FN	f::date::min(left:DateTime[1], right:DateTime[1]):DateTime[1]
FN	f::date::min(dates:DateTime[*]):DateTime[0..1]
FN	f::math::min(left:Float[1], right:Float[1]):Float[1]
FN	f::math::min(values:Float[*]):Float[0..1]
FN	f::math::min(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::min(values:Integer[*]):Integer[0..1]
FN	f::math::min(left:Number[1], right:Number[1]):Number[1]
FN	f::math::min(numbers:Number[*]):Number[0..1]
FN	f::date::min(left:StrictDate[1], right:StrictDate[1]):StrictDate[1]
FN	f::date::min(dates:StrictDate[*]):StrictDate[0..1]
FN	f::math::mode(numbers:Integer[*]):Integer[1]
FN	f::math::mode(numbers:Float[*]):Float[1]
FN	f::math::mode(numbers:Number[*]):Number[1]
FN	f::math::mod(dividend:Integer[1], divisor:Integer[1]):Integer[1]
FN	f::date::monthNumber(d:Date[1]):Integer[1]
FN	f::date::month(d:Date[1]):f::date::Month[1]
FN	rel::newTDSRelationAccessor<T>(tds:rel::Relation<T>[1]):rel::Relation<T>[1]
FN	lite::notEqualAnsi(left:Any[1], right:Any[1]):Boolean[1]
FN	lite::lessThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
FN	lite::lessThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
FN	lite::greaterThan(left:Any[0..1], right:Any[0..1]):Boolean[1]
FN	lite::greaterThanEqual(left:Any[0..1], right:Any[0..1]):Boolean[1]
FN	f::boolean::not(value:Boolean[1]):Boolean[1]
FN	f::date::now():DateTime[1]
FN	f::relation::nth<T>(w:rel::Relation<T>[1], f:f::relation::_Window<T>[1], r:T[1], offset:Integer[1]):T[0..1]
FN	f::relation::ntile<T>(rel:rel::Relation<T>[1], row:T[1], tileCount:Integer[1]):Integer[1]
FN	f::collection::objectReferenceIn(col:Any[1], values:Any[*]):Boolean[1]
FN	f::relation::offset<T>(w:rel::Relation<T>[1], r:T[1], offset:Integer[1]):T[0..1]
FN	f::boolean::or(left:Boolean[1], right:Boolean[1]):Boolean[1]
FN	f::collection::or(values:Boolean[*]):Boolean[1]
FN	lite::otherwise<T>(partial:T[1], fallback:T[0..1]):T[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1], sortInfo:f::relation::SortInfo<T>[*]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1], sortInfo:f::relation::SortInfo<T>[1], range:f::relation::_Range[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1], sortInfo:f::relation::SortInfo<T>[*], rows:f::relation::Rows[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1], sortInfo:f::relation::SortInfo<T>[*]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(sortInfo:f::relation::SortInfo<T>[*]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(sortInfo:f::relation::SortInfo<T>[1], range:f::relation::_Range[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(sortInfo:f::relation::SortInfo<T>[1], rangeInterval:f::relation::_RangeInterval[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1], sortInfo:f::relation::SortInfo<T>[1], rangeInterval:f::relation::_RangeInterval[1]):f::relation::_Window<T>[1]
FN	f::collection::max<X>(values:X[*]):X[0..1]
FN	f::collection::max<X>(values:X[1..*]):X[1]
FN	f::collection::min<X>(values:X[*]):X[0..1]
FN	f::collection::min<X>(values:X[1..*]):X[1]
FN	f::collection::max<T>(col:T[*], comp:fn::Function<{T[1],T[1]->Integer[1]}>[1]):T[0..1]
FN	f::collection::max<T>(col:T[1..*], comp:fn::Function<{T[1],T[1]->Integer[1]}>[1]):T[1]
FN	f::collection::min<T>(col:T[*], comp:fn::Function<{T[1],T[1]->Integer[1]}>[1]):T[0..1]
FN	f::collection::min<T>(col:T[1..*], comp:fn::Function<{T[1],T[1]->Integer[1]}>[1]):T[1]
FN	f::collection::pair<T,U>(first:T[1], second:U[1]):f::collection::Pair<T,U>[1]
FN	f::collection::newMap<U,V>(pairs:f::collection::Pair<U,V>[*]):f::collection::Map<U,V>[1]
FN	f::collection::get<U,V>(m:f::collection::Map<U,V>[1], key:U[1]):V[0..1]
FN	f::collection::put<U,V>(m:f::collection::Map<U,V>[1], key:U[1], value:V[1]):f::collection::Map<U,V>[1]
FN	f::collection::putAll<U,V>(m:f::collection::Map<U,V>[1], pairs:f::collection::Pair<U,V>[*]):f::collection::Map<U,V>[1]
FN	f::collection::putAll<U,V>(m:f::collection::Map<U,V>[1], o:f::collection::Map<U,V>[1]):f::collection::Map<U,V>[1]
FN	f::collection::keys<U,V>(m:f::collection::Map<U,V>[1]):U[*]
FN	f::collection::values<U,V>(m:f::collection::Map<U,V>[1]):V[*]
FN	f::string::parseBoolean(string:String[1]):Boolean[1]
FN	f::string::parseDate(string:String[1]):Date[1]
FN	f::string::parseDecimal(string:String[1]):Decimal[1]
FN	f::string::parseDecimal(string:String[1], precision:Integer[1], scale:Integer[1]):Decimal[1]
FN	f::string::parseFloat(string:String[1]):Float[1]
FN	f::string::parseInteger(string:String[1]):Integer[1]
FN	f::math::percentile(numbers:Number[*], p:Number[1]):Number[1]
FN	f::math::percentile(numbers:Number[*], p:Number[1], ascending:Boolean[1], continuous:Boolean[1]):Number[0..1]
FN	f::relation::percentRank<T>(rel:rel::Relation<T>[1], w:f::relation::_Window<T>[1], row:T[1]):Float[1]
FN	f::math::pi():Float[1]
FN	f::relation::pivot<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<Any>[1]
FN	f::relation::pivot<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<Any>[1]
FN	f::relation::pivot<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1], values:Any[1..*], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<Any>[1]
FN	f::relation::pivot<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpecArray<(Z?T)>[1], agg:rel::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<Any>[1]
FN	f::relation::pivot<T,Z,K,V,R>(r:rel::Relation<T>[1], cols:rel::ColSpecArray<(Z?T)>[1], agg:rel::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):rel::Relation<Any>[1]
FN	f::math::plus(left:Decimal[1], right:Decimal[1]):Decimal[1]
FN	f::math::plus(left:Float[1], right:Float[1]):Float[1]
FN	f::math::plus(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::plus(left:Number[1], right:Number[1]):Number[1]
FN	f::math::plus(left:String[1], right:String[1]):String[1]
FN	f::math::plus<T>(values:T[*]):T[1]
FN	f::math::pow(base:Number[1], exponent:Number[1]):Number[1]
FN	f::relation::project<C,T>(cl:C[*], x:rel::FuncColSpecArray<{C[1]->Any[*]},T>[1]):rel::Relation<T>[1]
FN	f::asserts::assert(condition:Boolean[1]):Boolean[1]
FN	f::asserts::assert(condition:Boolean[1], message:String[1]):Boolean[1]
FN	f::asserts::assert(condition:Boolean[1], messageFunction:fn::Function<{->String[1]}>[1]):Boolean[1]
FN	f::asserts::fail():Boolean[1]
FN	f::asserts::fail(message:String[1]):Boolean[1]
FN	f::string::chunk(source:String[1], val:Integer[1]):String[*]
FN	f::string::toRepresentation(any:Any[1]):String[1]
FN	f::asserts::assertEqWithinTolerance(expected:Number[1], actual:Number[1], delta:Number[1]):Boolean[1]
FN	f::asserts::assertError(f:fn::Function<{->Any[*]}>[1], message:String[1]):Boolean[1]
FN	f::asserts::assertError(f:fn::Function<{->Any[*]}>[1], message:String[1], line:Integer[0..1], column:Integer[0..1]):Boolean[1]
FN	f::asserts::assertInstanceOf(instance:Any[1], type:Type[1]):Boolean[1]
FN	f::asserts::assertInstanceOf(instance:Any[1], type:Type[1], message:String[1]):Boolean[1]
FN	f::relation::columns<T>(rel:rel::Relation<T>[1]):rel::Column<Nil,Any>[*]
FN	f::relation::assertTdsEquivalent<T,Z>(one:rel::Relation<T>[1], two:rel::Relation<Z>[1], delta:Number[1]):Boolean[1]
FN	f::relation::assertTdsEquivalent<T,Z>(one:rel::Relation<T>[1], two:rel::Relation<Z>[1], delta:Number[1], timeDeltaInSeconds:Number[1]):Boolean[1]
FN	p::tds::getString(row:p::tds::TDSRow[1], colName:String[1]):String[1]
FN	p::tds::tdsContains<T,Z>(object:T[1], fns:fn::Function<{T[1]->Any[0..1]}>[*], tds:rel::Relation<Z>[1]):Boolean[1]
FN	p::tds::tdsContains<T,Z>(object:T[1], fns:fn::Function<{T[1]->Any[0..1]}>[*], ids:String[*], tds:rel::Relation<Z>[1], crossOperation:fn::Function<{p::tds::TDSRow[1],p::tds::TDSRow[1]->Boolean[1]}>[1]):Boolean[1]
FN	p::tds::project<K>(set:K[*], fns:fn::Function<{K[1]->Any[*]}>[*], ids:String[*]):rel::Relation<K>[1]
FN	f::relation::project<T,Z>(r:rel::Relation<T>[1], fs:rel::FuncColSpecArray<{T[1]->Any[*]},Z>[1]):rel::Relation<Z>[1]
FN	f::date::quarterNumber(d:Date[1]):Integer[1]
FN	f::date::quarter(d:Date[1]):f::date::Quarter[1]
FN	f::collection::range(stop:Integer[1]):Integer[*]
FN	f::collection::range(start:Integer[1], stop:Integer[1]):Integer[*]
FN	f::collection::range(start:Integer[1], stop:Integer[1], step:Integer[1]):Integer[*]
FN	f::relation::rank<T>(rel:rel::Relation<T>[1], w:f::relation::_Window<T>[1], row:T[1]):Integer[1]
FN	f::collection::removeAllOptimized<T>(set:T[*], other:T[*]):T[*]
FN	f::collection::removeDuplicatesBy<T,V>(col:T[*], key:fn::Function<{T[1]->V[1]}>[1]):T[*]
FN	f::collection::removeDuplicates<T>(col:T[*]):T[*]
FN	f::collection::distinct<T>(s:T[*]):T[*]
FN	f::collection::removeDuplicates<T,V>(col:T[*], key:fn::Function<{T[1]->V[1]}>[0..1], eql:fn::Function<{V[1],V[1]->Boolean[1]}>[0..1]):T[*]
FN	f::collection::removeDuplicates<T>(col:T[*], eql:fn::Function<{T[1],T[1]->Boolean[1]}>[1]):T[*]
FN	f::math::rem(dividend:Number[1], divisor:Number[1]):Number[1]
FN	f::relation::rename<T,Z,K,V>(r:rel::Relation<T>[1], old:rel::ColSpec<((Z=(?:K[1]))?T)>[1], new:rel::ColSpec<(V=(?:K[1]))>[1]):rel::Relation<((T-Z)+V)>[1]
FN	f::relation::rename<T,Z,V>(r:rel::Relation<T>[1], oldCols:rel::ColSpecArray<(Z?T)>[1], newCols:rel::ColSpecArray<V>[1]):rel::Relation<((T-Z)+V)>[1]
FN	f::string::repeatString(str:String[0..1], count:Integer[1]):String[0..1]
FN	f::string::replace(str:String[1], toFind:String[1], replacement:String[1]):String[1]
FN	f::string::reverseString(str:String[1]):String[1]
FN	f::collection::reverse<T|m>(set:T[m]):T[m]
FN	f::string::right(str:String[1], len:Integer[1]):String[1]
FN	f::math::round(decimal:Decimal[1], scale:Integer[1]):Decimal[1]
FN	f::math::round(float:Float[1], scale:Integer[1]):Float[1]
FN	f::math::round(number:Number[1]):Integer[1]
FN	f::relation::rows(offsetFrom:Integer[1], offsetTo:Integer[1]):f::relation::Rows[1]
FN	f::relation::over<T>(cols:rel::ColSpec<T>[1], rows:f::relation::Rows[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1], rows:f::relation::Rows[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1], sortInfo:f::relation::SortInfo<T>[*], rows:f::relation::Rows[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1], sortInfo:f::relation::SortInfo<T>[1], range:f::relation::_Range[1]):f::relation::_Window<T>[1]
FN	f::relation::over<T>(cols:rel::ColSpecArray<T>[1], sortInfo:f::relation::SortInfo<T>[1], rangeInterval:f::relation::_RangeInterval[1]):f::relation::_Window<T>[1]
FN	f::relation::reduce<T,V,U|m>(rel:rel::Relation<T>[1], w:f::relation::_Window<T>[1], row:T[1], map:fn::Function<{T[1]->V[*]}>[1], agg:fn::Function<{V[*]->U[m]}>[1]):U[m]
FN	f::variant::convert::fromJson(json:String[1]):m3::variant::Variant[1]
FN	f::relation::variant::flatten<T>(valueToFlatten:T[*], columnWithFlattenedValue:rel::ColSpec<Any>[1]):rel::Relation<Any>[1]
FN	f::relation::lateral<T,V>(rel:rel::Relation<T>[1], f:fn::Function<{T[1]->rel::Relation<V>[1]}>[1]):rel::Relation<(T+V)>[1]
FN	f::relation::rows(offsetFrom:f::relation::UnboundedFrameValue[1], offsetTo:f::relation::UnboundedFrameValue[1]):f::relation::Rows[1]
FN	f::relation::rows(offsetFrom:f::relation::UnboundedFrameValue[1], offsetTo:Integer[1]):f::relation::Rows[1]
FN	f::relation::rows(offsetFrom:Integer[1], offsetTo:f::relation::UnboundedFrameValue[1]):f::relation::Rows[1]
FN	f::math::mathUtility::rowMapper<T,U>(value:T[0..1], key:U[0..1]):f::math::mathUtility::RowMapper<T,U>[1]
FN	f::relation::rowNumber<T>(rel:rel::Relation<T>[1], row:T[1]):Integer[1]
FN	f::string::rpad(str:String[1], len:Integer[1]):String[1]
FN	f::string::rpad(str:String[1], len:Integer[1], pad:String[1]):String[1]
FN	f::string::rtrim(str:String[1]):String[1]
FN	f::date::second(d:Date[1]):Integer[1]
FN	f::relation::select<T>(r:rel::Relation<T>[1]):rel::Relation<T>[1]
FN	f::relation::select<T,Z>(r:rel::Relation<T>[1], cols:rel::ColSpec<(Z?T)>[1]):rel::Relation<Z>[1]
FN	f::relation::select<T,Z>(r:rel::Relation<T>[1], cols:rel::ColSpecArray<(Z?T)>[1]):rel::Relation<Z>[1]
FN	p::graphFetch::execution::serialize<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1]):String[1]
FN	p::graphFetch::execution::serialize<T>(source:T[*], tree:p::graphFetch::RootGraphFetchTree<T>[1], config:Any[1]):String[1]
FN	f::math::sign(number:Number[1]):Integer[1]
FN	f::math::sinh(number:Number[1]):Float[1]
FN	f::math::sin(number:Number[1]):Float[1]
FN	f::relation::size<T>(rel:rel::Relation<T>[1]):Integer[1]
FN	f::collection::size<T>(col:T[*]):Integer[1]
FN	f::relation::slice<T>(rel:rel::Relation<T>[1], start:Integer[1], stop:Integer[1]):rel::Relation<T>[1]
FN	f::collection::slice<T>(set:T[*], start:Integer[1], end:Integer[1]):T[*]
FN	f::collection::sortByReversed<T,U|m>(col:T[m], key:fn::Function<{T[1]->U[1]}>[0..1]):T[m]
FN	f::collection::sortBy<T,U|m>(col:T[m], key:fn::Function<{T[1]->U[1]}>[0..1]):T[m]
FN	f::relation::sort<X,T>(rel:rel::Relation<T>[1], sortInfo:f::relation::SortInfo<(X?T)>[*]):rel::Relation<T>[1]
FN	p::tds::sort<T>(rel:rel::Relation<T>[1], col:String[1], direction:rl::metamodel::SortDirection[1]):rel::Relation<T>[1]
FN	f::relation::sort<T>(rel:rel::Relation<T>[1], cols:String[*]):rel::Relation<T>[1]
FN	f::collection::sort<T|m>(col:T[m]):T[m]
FN	f::collection::sort<T|m>(col:T[m], comp:fn::Function<{T[1],T[1]->Integer[1]}>[0..1]):T[m]
FN	f::collection::sort<T,U|m>(col:T[m], key:fn::Function<{T[1]->U[1]}>[0..1], comp:fn::Function<{U[1],U[1]->Integer[1]}>[0..1]):T[m]
FN	lite::sourceUrl(url:String[1]):rel::Relation<Any>[1]
FN	f::string::splitPart(str:String[0..1], delimiter:String[1], index:Integer[1]):String[0..1]
FN	f::string::split(str:String[1], delimiter:String[1]):String[*]
FN	rl::functions::sqlQueryToString::sqlFalse():Boolean[1]
FN	rl::functions::sqlQueryToString::sqlNull():Nil[0]
FN	rl::functions::sqlQueryToString::sqlTrue():Boolean[1]
FN	f::math::sqrt(number:Number[1]):Float[1]
FN	f::string::startsWith(source:String[1], val:String[1]):Boolean[1]
FN	f::string::startsWith(source:String[0..1], val:String[1]):Boolean[1]
FN	f::math::stdDevPopulation(numbers:Number[*]):Number[1]
FN	f::math::stdDevPopulation<T>(partition:rel::Relation<T>[1], window:f::relation::_Window<T>[1], row:T[1], colToAgg:rel::ColSpec<((?:Number[1])?T)>[1]):Number[1]
FN	f::math::stdDevSample(numbers:Number[*]):Number[1]
FN	f::math::stdDev(numbers:Number[*]):Number[1]
FN	f::math::stdDev<T>(w:rel::Relation<T>[1], f:f::relation::_Window<T>[1], r:T[1]):T[0..1]
FN	f::string::substring(str:String[1], start:Integer[1]):String[1]
FN	f::string::substring(str:String[1], start:Integer[1], end:Integer[1]):String[1]
FN	lite::sub(left:Decimal[1], right:Decimal[1]):Decimal[1]
FN	lite::sub(left:Float[1], right:Float[1]):Float[1]
FN	lite::sub(left:Integer[1], right:Integer[1]):Integer[1]
FN	lite::sub(left:Number[1], right:Number[1]):Number[1]
FN	f::math::sum(numbers:Float[*]):Float[1]
FN	f::math::sum(numbers:Integer[*]):Integer[1]
FN	f::math::sum(numbers:Number[*]):Number[1]
FN	rl::functions::database::tableReference(db:String[1], name:String[1]):rel::Relation<Any>[1]
FN	p::tds::tableToTDS(table:rel::Relation<Any>[1]):rel::Relation<Any>[1]
FN	rl::functions::database::tableReference(db:String[1], schema:String[1], name:String[1]):rel::Relation<Any>[1]
FN	f::collection::tail<T>(set:T[*]):T[*]
FN	f::collection::take<T>(rel:rel::Relation<T>[1], size:Integer[1]):rel::Relation<T>[1]
FN	f::collection::take<T>(set:T[*], size:Integer[1]):T[*]
FN	f::math::tanh(number:Number[1]):Float[1]
FN	f::math::tan(number:Number[1]):Float[1]
FN	lite::tds(tag:String[1], raw:String[1]):rel::Relation<Any>[1]
FN	f::math::times(left:Decimal[1], right:Decimal[1]):Decimal[1]
FN	f::math::times(left:Float[1], right:Float[1]):Float[1]
FN	f::math::times(left:Integer[1], right:Integer[1]):Integer[1]
FN	f::math::times(left:Number[1], right:Number[1]):Number[1]
FN	f::math::times<T>(values:T[*]):T[1]
FN	f::date::timeBucket(date:DateTime[1], quantity:Integer[1], unit:f::date::DurationUnit[1]):DateTime[1]
FN	f::date::timeBucket(date:StrictDate[1], quantity:Integer[1], unit:f::date::DurationUnit[1]):StrictDate[1]
FN	f::date::today():StrictDate[1]
FN	f::math::toDecimal(number:Number[1]):Decimal[1]
FN	f::math::toDegrees(radians:Number[1]):Float[1]
FN	f::date::toEpochValue(d:Date[1]):Integer[1]
FN	f::date::toEpochValue(d:Date[1], unit:f::date::DurationUnit[1]):Integer[1]
FN	f::math::toFloat(number:Number[1]):Float[1]
FN	f::string::toLowerFirstCharacter(str:String[1]):String[1]
FN	f::string::toLower(source:String[1]):String[1]
FN	f::variant::convert::toMany<T,V>(source:T[0..1], type:V[0..1]):V[*]
FN	f::multiplicity::toOneMany<T>(values:T[*]):T[1..*]
FN	f::multiplicity::toOneMany<T>(values:T[*], message:String[1]):T[1..*]
FN	f::multiplicity::toOne<T>(values:T[*]):T[1]
FN	f::multiplicity::toOne<T>(values:T[*], message:String[1]):T[1]
FN	f::math::toRadians(degrees:Number[1]):Float[1]
FN	f::string::toString(any:Any[1]):String[1]
FN	f::string::toUpperFirstCharacter(str:String[1]):String[1]
FN	f::string::toUpper(source:String[1]):String[1]
FN	f::variant::convert::toVariant(source:Any[*]):m3::variant::Variant[1]
FN	f::variant::convert::to<T,V>(source:T[0..1], type:V[0..1]):V[0..1]
FN	f::string::trim(str:String[1]):String[1]
FN	f::meta::type(any:Any[*]):Type[1]
FN	f::relation::unbounded():f::relation::UnboundedFrameValue[1]
FN	f::math::variancePopulation(numbers:Number[*]):Number[1]
FN	f::math::varianceSample(numbers:Number[*]):Number[1]
FN	f::math::variance(numbers:Number[*]):Number[1]
FN	f::math::variance<T>(w:rel::Relation<T>[1], f:f::relation::_Window<T>[1], r:T[1]):T[0..1]
FN	f::math::variance(numbers:Number[*], isSample:Boolean[1]):Number[1]
FN	f::math::wavg<T,U>(values:f::math::mathUtility::RowMapper<T,U>[*]):Float[1]
FN	f::math::wavgUtility::wavgRowMapper(value:Number[0..1], weight:Number[0..1]):f::math::mathUtility::RowMapper<Number,Number>[1]
FN	f::date::weekOfYear(d:Date[1]):Integer[1]
FN	f::relation::write<T>(source:rel::Relation<T>[1]):Integer[1]
FN	f::relation::write<T>(source:rel::Relation<T>[1], target:Any[1]):Integer[1]
FN	f::boolean::xor(left:Boolean[1], right:Boolean[1]):Boolean[1]
FN	f::date::year(d:Date[1]):Integer[1]
FN	f::collection::zip<T,U>(set1:T[*], set2:U[*]):f::collection::Pair<T,U>[*]
FN	f::relation::_range(offsetFrom:Number[1], offsetTo:Number[1]):f::relation::_Range[1]
FN	f::relation::_range(offsetFrom:f::relation::UnboundedFrameValue[1], offsetTo:Number[1]):f::relation::_Range[1]
FN	f::relation::_range(offsetFrom:Number[1], offsetTo:f::relation::UnboundedFrameValue[1]):f::relation::_Range[1]
FN	f::relation::_range(offsetFrom:Integer[1], offsetFromDurationUnit:f::date::DurationUnit[1], offsetTo:Integer[1], offsetToDurationUnit:f::date::DurationUnit[1]):f::relation::_RangeInterval[1]
FN	f::relation::_range(offsetFrom:f::relation::UnboundedFrameValue[1], offsetTo:Integer[1], offsetToDurationUnit:f::date::DurationUnit[1]):f::relation::_RangeInterval[1]
FN	f::relation::_range(offsetFrom:Integer[1], offsetFromDurationUnit:f::date::DurationUnit[1], offsetTo:f::relation::UnboundedFrameValue[1]):f::relation::_RangeInterval[1]
FN	f::relation::_range(offsetFrom:f::relation::UnboundedFrameValue[1], offsetTo:f::relation::UnboundedFrameValue[1]):f::relation::_Range[1]
```
