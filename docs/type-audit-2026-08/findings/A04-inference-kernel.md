# A04 — `InferenceKernel` (unification / type-variable engine) — adversarial audit

Scope read IN FULL: `core/src/main/java/com/legend/compiler/spec/InferenceKernel.java` (1449 lines),
`Bindings.java` (94), `Application.java` (17), `Expected.java` (27), `Args.java` (82),
`SignatureMangle.java` (79), `CoreFn.java` (201).

Probes live in
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a04/`
(`K1.java` kernel unit probe, `K2.java` overload table, `K3.java` determinism, `K4.java`
capture/occurs/vacuity, `K5.java` SignatureMangle fuzz, `m2..m9.pure` models, `ddl8.sql`).
All outputs below are pasted verbatim from actual runs.

---

## FINDINGS

### [UNSOUND] A generic user function's declared return type is NEVER checked against its body — the check is a no-op because `unify(TypeVar, actual, new Bindings())` always succeeds

**Evidence (code path, three files):**

`InferenceKernel.java:74-77` — the first thing `unify` does with a type-variable *formal* is bind it:
```java
switch (formal) {
    case Type.ClassType c when c.fqn().equals(ANY_FQN) -> { }
    case Type.TypeVar v when isUnknown(v) -> { }
    case Type.TypeVar v -> bindOrCheckTypeVar(v, actual, b);
```
and `bindOrCheckTypeVar` on an *empty* `Bindings` takes the else-branch, `InferenceKernel.java:540-544`:
```java
} else {
    b.bindType(v.name(), actual);   // bind the actual unchanged
```
So `unify(T, <anything>, new Bindings())` **accepts everything**. Same for multiplicities —
`InferenceKernel.java:672-676`:
```java
case Multiplicity.Var v -> {
    if (!b.hasMult(v.name())) {
        b.bindMult(v.name(), actual);
```

`Typer.java:3164-3171` performs *every* Check-mode conformance test with a throwaway `Bindings`,
and the comment states the (false) premise:
```java
void requireConforms(ExprType actual, ExprType expected) {
    // Reuse the kernel: unify(expected, actual) checks actual <: expected for scalars
    // (throws on mismatch). Empty bindings — expected is concrete, nothing to solve.
    kernel.unify(expected.type(), actual.type(), new Bindings());
    kernel.unifyMult(expected.multiplicity(), actual.multiplicity(), actual.type(), new Bindings());
}
```
`SpecCompiler.java:152` is the caller that checks a user function body against its declared return:
```java
typer.requireConforms(stmt.info(), declaredReturn);
```
For `function my::f<T>(x:T[1]):T[1]`, `declaredReturn.type()` is `TypeVar("T")` — **not** concrete —
so the body check is vacuous.

**Repro (model `m7.pure` / `m9.pure`):**
```pure
function my::bad<T>(x: T[1]): T[1] { 'hello' }
function my::badm<T|m>(x: T[m]): T[m] { 'x' }
function my::good(x: Integer[1]): Integer[1] { 'hello' }   // non-generic control
```
```
/home/user/probe/probe.sh m7.pure <query>
```
**Actual output:**
```
--- baseline-nongeneric : my::good(1)
[G] type=Integer mult=[1]
[PLAN-ERROR] com.legend.compiler.spec.TypeInferenceException: in function 'my::good': declares return type Integer but body returns String (expected Integer, got String)
--- generic-bad : my::bad(1)
[G] type=Integer mult=[1]
[PLAN] SELECT 'hello' AS value
[EXEC] shape=Scalar returnType=String returnTypeRepr=STRING
[EXEC-COL] value : String [STRING] mult=null
[EXEC-ROW] String(hello) |
--- generic-badm : my::badm([1,2,3])
[G] type=Integer mult=[3]
[PLAN] SELECT 'x' AS value
[EXEC] shape=Scalar returnType=String returnTypeRepr=STRING
[EXEC-ROW] String(x) |
```
The non-generic control is correctly rejected; the generic one is not. `my::badm` gets BOTH the
type and the multiplicity wrong ( `Integer[3]` claimed, one `String` delivered ).

**Repro carried into a relation column type (model `m9.pure`):**
```pure
model::Person.all()->project(~[a:p|$p.age])->extend(~b:x|my::bad($x.a))
```
**Actual output:**
```
[G] type=Relation<(a:Integer[1], b:Integer[1])> mult=[1]
[PLAN] SELECT t0.AGE_VAL AS a, 'hello' AS b
[EXEC-COL] a : Integer [INTEGER] mult=[1]
[EXEC-COL] b : Integer [INTEGER] mult=[1]
[EXEC-ROW] Integer(30) | String(hello) |
[EXEC-ROW] Integer(28) | String(hello) |
[EXEC-ROW] Integer(45) | String(hello) |
```

**Why it matters:** the compiler publishes `b : Integer[1]` to the wire/result decoder and the cell
is a `java.lang.String`. Any consumer that trusts the declared column type (JDBC decode, downstream
arithmetic, serialization) is handed a value of the wrong Java class. This is the top-severity class
of defect in the brief, and the vacuity originates entirely inside the kernel's `unify`/`unifyMult`
variable arms.

**Direct kernel-level confirmation (`K4.java`, section B):**
```
=== B. requireConforms(new Bindings()) is VACUOUS when expected is a var ===
  unify(T, String, new Bindings())            -> ACCEPTED (no error)
  unifyMult(m, [1], new Bindings())           -> ACCEPTED (no error)
  unifyMult([1], <mult var m>)                -> ACCEPTED (actual Var: no check at all)
```
(The third line is a second, independent hole: `unifyMult`'s `Bounded` arm at
`InferenceKernel.java:701-702` guards on `actual instanceof Multiplicity.Bounded`, so a
multiplicity *variable* on the actual side is never checked at all.)

---

### [UNSOUND] `compatibleRebind`'s "Any escape hatch" lets an `Any`-typed argument enter a type variable already bound to a narrower type — the narrow binding is kept and the value is silently mistyped

**Evidence** — `InferenceKernel.java:1406-1409` (the code names it itself):
```java
/** A re-bind is OK only if it matches, or either side is {@code Any} (the escape hatch). */
private boolean compatibleRebind(Type existing, Type actual) {
    if (isAny(existing) || isAny(actual)) {
        return true;
    }
```
Returning `true` makes `bindOrCheckTypeVar` fall out of the `if (!compatibleRebind(...))` block at
`InferenceKernel.java:505` **without rebinding** — the earlier, narrower binding survives.

**Repro:**
```pure
['a','b']->concatenate([1,'x'])
```
`concatenate<T>(T[*],T[*]):T[*]` binds `T := String` from arg 1; arg 2 (`[1,'x']`) types `Any[2]`;
the escape hatch admits it and `T` stays `String`.

**Actual output:**
```
[G] type=String mult=[*]
[PLAN] SELECT UNNEST(list_filter(list_concat(['a', 'b'], CAST([CAST(1 AS VARCHAR), concat('''', replace(replace('x', '\', '\\'), '''', '\'''), '''')] AS VARCHAR[])), x -> x IS NOT NULL)) AS value
[EXEC] shape=Collection returnType=String returnTypeRepr=STRING
[EXEC-COL] value : String [STRING] mult=null
[EXEC-ROW] String(a) |
[EXEC-ROW] String(b) |
[EXEC-ROW] String(1) |
[EXEC-ROW] String('x') |
```
Two separate wrongnesses in the executed result: the Integer `1` is silently coerced to the string
`"1"`, and the String element `x` comes back as **`'x'` — with its quotes** (the `Any` lane's JSON
quoting leaks into a `String`-typed column). Forward/backward asymmetry: `'x'` went down and `'x'`
(4 chars) came back.

Mirror case, same hatch, this time producing an internal crash instead of a compile error:
```
--- [1,2]->concatenate([1,'a'])
[G] type=Integer mult=[*]
[EXEC-ERROR] java.sql.SQLException: Binder Error: Cannot concatenate lists of types INTEGER[] and VARCHAR[] - an explicit cast is required
--- [1,2]->concatenate(['a','b']->cast(@meta::pure::metamodel::type::Any))
[G] type=Integer mult=[*]
[EXEC-ERROR] java.sql.SQLException: Binder Error: Cannot concatenate lists of types INTEGER[] and VARCHAR[] - an explicit cast is required
```
`[1,2]->concatenate([1,'a'])` is typed `Integer[*]` and a String is provably in the list
(`[1,'a']` alone executes to `Long(1) | String(a)`).

Kernel-level confirmation (`K1.java` §6):
```
  T bound to row(a:Integer), unify with Any -> T = (a:Integer[1]) (accepted silently)
  T bound to Any, unify with row -> T = meta::pure::metamodel::type::Any
```
Both directions silently succeed; the second one silently *erases* a relation schema to `Any`.

---

### [UNSOUND / CRASH] Unbounded type variables: `abs<T>(T[1]):T[1]` and friends accept `String`, `Boolean`, `StrictDate` — the kernel has no bounded quantification and `paramTypeScore` scores a `TypeVar` `0` for every actual

**Evidence** — `InferenceKernel.java:1070` (`paramTypeScore`): `case Type.TypeVar ignored -> 0;` — a
type-variable formal matches *anything*. `unify`'s `TypeVar` arm likewise just binds. There is no
constraint/bound mechanism anywhere in the file (no `where`, no class hierarchy bound on a var).
`Pure.java:1116` declares `abs<T>(number:T[1]):T[1]`, `Pure.java:1899` `minus<T>(values:T[*]):T[1]`.

**Repro + actual output:**
```
--- 'abc'->abs()
[G] type=String mult=[1]
[PLAN] SELECT abs('abc') AS value
[EXEC-ERROR] java.sql.SQLException: Binder Error: Could not choose a best candidate function for the function call "abs(STRING_LITERAL)". In order to select one, please add explicit type casts.
--- true->abs()
[G] type=Boolean mult=[1]
[PLAN] SELECT abs(TRUE) AS value
[EXEC-ERROR] java.sql.SQLException: Binder Error: No function matches the given name and argument types 'abs(BOOLEAN)'. You might need to add explicit type casts.
--- %2020-01-01->abs()
[G] type=StrictDate mult=[1]
[PLAN] SELECT abs(DATE '2020-01-01') AS value
--- ['a','b']->minus()
[G] type=String mult=[1]
[EXEC-ERROR] java.sql.SQLException: Binder Error: No function matches the given name and argument types '-(INTEGER_LITERAL, VARCHAR)'. You might need to add explicit type casts.
--- model::Person.all()->project(~[a:p|$p.firstName])->extend(~b:x|$x.a->abs())
[G] type=Relation<(a:String[1], b:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS a, abs(t0.FIRST_NAME) AS b
[EXEC-ERROR] java.sql.SQLException: Binder Error: No function matches the given name and argument types 'abs(VARCHAR)'. You might need to add explicit type casts.
```
**Why it matters:** a plain user typo (`->abs()` on a text column) type-checks, plans, renders SQL,
and dies as a raw `java.sql.SQLException` from the JDBC driver rather than a
`TypeInferenceException`. That is a CRASH-class escape on input a user can plainly write, and the
Phase-G type `Relation<(a:String[1], b:String[1])>` is a claim the platform cannot honour.

---

### [UNSOUND] No OCCURS CHECK, and `resolve` is a ONE-LEVEL lookup — a bound variable can still contain free variables while `hasFreeTypeVars` reports the type as solved

**Evidence** — the binder never inspects `actual` for `v`, `InferenceKernel.java:540-541`:
```java
} else {
    b.bindType(v.name(), actual);   // bind the actual unchanged
```
and the substituter is a single map lookup with **no recursive re-resolution**,
`InferenceKernel.java:737-739`:
```java
case Type.TypeVar v when isUnknown(v) -> t;
case Type.TypeVar v -> b.type(v.name()).orElseThrow(() ->
        new TypeInferenceException("unbound type variable " + v.name()));
```
`hasFreeTypeVars` (`InferenceKernel.java:593-596`) asks only `!b.hasType(v.name())`, i.e. "is there a
row in the map", never "is the *value* ground":
```java
case Type.TypeVar v -> !isUnknown(v) && !b.hasType(v.name());
```

**Repro (`K1.java` §1-2, `K4.java` §C) — actual output:**
```
=== 1. OCCURS CHECK: unify(T, Relation<T>) ===
  bound T := Relation<T>
  hasFreeTypeVars(Relation<T>) = false
  resolve(T)         = Relation<T>
  resolve(Relation<T>) = Relation<Relation<T>>
  NO OCCURS CHECK -> accepted infinite type, resolve is ONE-LEVEL

=== 2. ONE-LEVEL SUBSTITUTION: T:=X then X:=Integer ===
  bindings: T=X X=Integer
  resolve(T) = X   (expected Integer)
  resolve(Relation<(c:T)>) = Relation<(c:X[1])>
  hasFreeTypeVars(T) = false (claims solved)
```
```
=== C. OCCURS: T := T self-binding, and cyclic chains ===
  unify(T,T) -> T=T  (self-binding accepted)
  A:=B, B:=A -> resolve(A)=B resolve(B)=A  (cycle accepted, no divergence because resolve is one-level)
  T := (c:T) -> resolve(T) = (c:T[1])
  resolve((c:T)) = (c:(c:T[1])[1])
```
It does **not** loop or stack-overflow — precisely because the substituter is unsound in the other
direction: it never re-descends. The infinite type is silently accepted, and the "resolved" output
still contains the variable while `hasFreeTypeVars` says it does not. That combination is exactly
what makes the vacuous-check finding above possible: `Typer.java:2129` gates on
`!kernel.hasFreeTypeVars(retType, b)` and then calls `kernel.unify(kernel.resolve(retType, b),
body.info().type(), new Bindings())` — when `retType` resolves to a bare variable that unify is a
no-op.

---

### [UNSOUND] Variable capture: no freshening / α-renaming anywhere; `Bindings` is keyed by the raw variable NAME, so a callee's variable and a caller's identically-spelled variable are the same slot

**Evidence** — `Bindings.java:53-54` is a plain `LinkedHashMap<String, Type>` keyed by the spelling;
`InferenceKernel` never renames (`grep -n "fresh\|alpha" InferenceKernel.java` finds only prose).
Nothing instantiates a signature's variables to fresh names before unification.

**Repro (`K4.java` §A)** — formal `{T[1] -> V[1]}` (a native's `T`,`V`) unified against a function
value whose type is `{V[1] -> Integer[1]}` where that `V` is the *caller's* variable:
```
=== A. VARIABLE CAPTURE: callee var V vs caller var V (no freshening) ===
  bindings: T=V  V=Integer
  resolve(T)  = V   <-- one-level: should be Integer after V:=Integer
  resolve(V)  = Integer   <-- the CALLER's V was captured and is now Integer
  hasFreeTypeVars(T) = false (claims fully solved)
```
The caller's `V` has been overwritten with `Integer` by the callee's own return-variable solution,
and `T` — which *should* now be `Integer` — resolves to the raw variable `V`.

Also reachable with same-name identification at user level: inside
`function my::selfT<T>(x:T[1]):T[1]{ $x->abs() }` the native `abs<T>`'s `T` and the user's `T` are the
same string; the accidental agreement is what makes it "work".

Mitigation observed (why this is not worse today): every native call allocates a fresh `Bindings`
(`InferenceKernel.java:1013`, and 15 other `new Bindings()` sites), and one signature's own variables
are distinct by construction, so a *two-native* collision inside one call was not reachable in my
tests. The mechanism is nevertheless entirely unguarded.

---

### [CRASH/ICE] A polymorphic user function's body is compiled once with its DECLARED variable types and never substituted — the raw `TypeVar` escapes to lowering as an `IllegalStateException`

**Evidence** — `resolveOutput` resolves only the *call's* output `ExprType`
(`InferenceKernel.java:866-868`):
```java
public ExprType resolveOutput(Type returnType, Multiplicity returnMult, Bindings b) {
    return new ExprType(resolve(returnType, b), resolveMult(returnMult, b));
}
```
Nothing substitutes into the callee's already-built `TypedFunction` (parameters / returnType) or into
its compiled body. `UserCallInliner.java:184` splices a body compiled generically:
```java
List<TypedSpec> body = specs.compile(call.callee()).body();
```

**Repro (model `m6.pure`):**
```pure
function my::wrap<T>(a: T[1]): T[*] { [$a] }
```
```
--- wrap : my::wrap(1)
[G] type=Integer mult=[*]
[PLAN-ERROR] java.lang.IllegalStateException: unresolved type variable T reached the lowering boundary
[EXEC-ERROR] java.lang.IllegalStateException: unresolved type variable T reached the lowering boundary
```
Same for `function my::pairUp<T>(a:T[1],b:T[1]):T[*]{ [$a,$b] }`. The `[G-TREE]` dump shows the call
node correctly typed `Integer[*]` while its `.callee` still reads
`parameters=[TypedParameter[name=a, type=TypeVar[name=T], ...]], returnType=TypeVar[name=T]`.
Thrown at `com/legend/lowering/PureSql.java:130-131`:
```java
case Type.TypeVar v -> throw new IllegalStateException(
        "unresolved type variable " + v.typeName() + " reached the lowering boundary");
```
`my::id<T>(x:T[1]):T[1]{ $x }` and `my::first2<T>(a:T[1],b:T[1]):T[1]{ $a }` survive (body is a bare
variable reference, so the argument node's own concrete type is spliced in); anything that *builds*
a node from `T` (a collection literal) crashes.

---

### [SILENT FALLBACK] `rename` on a quote-bearing column is a complete NO-OP — no rename, no error — because the output-shaping compares raw strings while the kernel's own column identity is quote-insensitive

**Evidence** — `InferenceKernel.java:441-448` defines THE column-identity rule:
```java
/** Column-name IDENTITY: a QUOTE-BEARING spelling ('"FIRST NAME"' —
 * quoted store declaration) and its stripped text are the SAME
 * column; the quotes are rendering metadata. */
static boolean sameColumn(String a, String b) { return stripColQ(a).equals(stripColQ(b)); }
```
`RenameChecker.java:78-81` ignores it:
```java
List<Column> cols = srcRt.columns().stream()
        .map(c -> c.name().equals(oldName) ? vCol : c)
        .toList();
```
plus two silent `return a.out();` fallbacks at `RenameChecker.java:71` and `:76`.

**Repro (model `m8.pure`, store column declared `"FIRST NAME"`):**
```pure
#>{store::DB.T_Q}#->rename(~'FIRST NAME', ~fn)
```
**Actual output:**
```
[G] type=Relation<(ID:Integer[1], "FIRST NAME":String[0..1])> mult=[1]
[PLAN] SELECT t0.ID, t0."FIRST NAME"
FROM T_Q AS t0
[EXEC-COL] ID : Integer [INTEGER] mult=[1]
[EXEC-COL] "FIRST NAME" : String [STRING] mult=[0..1]
[EXEC-ROW] Integer(1) | String(John) |
```
Control on the unquoted column of the same table works:
```
--- #>{store::DB.T_Q}#->rename(~ID, ~pk)
[G] type=Relation<(pk:Integer[1], "FIRST NAME":String[0..1])> mult=[1]
[PLAN] SELECT t0.ID AS pk, t0."FIRST NAME"
```
And the kernel *does* see the identity in the other operator — `extend(~'FIRST NAME':x|1)` on the same
relation correctly raises `SchemaInvariantException: the column 'FIRST NAME' already exists`.
So the user's rename request vanishes silently — strictly worse than an error.

---

### [SILENT FALLBACK] Schema algebra silently no-ops when the right operand does not resolve to a relation

**Evidence** — `InferenceKernel.java:813-841`, both arms guard with an `if` and have **no else**:
```java
case UNION -> {
    List<Type.Column> cols = new ArrayList<>(lr.columns());
    if (right instanceof Type.RelationType rr) {   // <-- no else
        ...
    }
    return new Type.RelationType(cols, lr.dynamicColumns());
}
case DIFFERENCE -> {
    Set<String> drop = new LinkedHashSet<>();
    if (right instanceof Type.RelationType rr) {   // <-- no else
        rr.columns().forEach(c -> drop.add(c.name()));
    }
    ...
```
The left operand gets a loud check three lines earlier (`InferenceKernel.java:807-810`); the right
gets none.

**Repro (`K1.java` §5) — actual output:**
```
  T+V where V=String: (a:Integer[1])   (silently ignored, no error)
  T-V where V=String: (a:Integer[1])   (silently drops nothing)
  T-Z dropping NONEXISTENT column: (a:Integer[1])  (no error)
```
This is the cleanest in-file falsification of the repo's "NO FALLBACKS. NO DEFAULTING." claim inside
the schema algebra itself: `T+V` with an unusable `V` silently degrades to `T`.

---

### [INCONSISTENCY] `T-Z` (DIFFERENCE) matches column names by RAW string equality while `T+V` (UNION) uses the quote-insensitive `sameColumn`

**Evidence** — `InferenceKernel.java:819` (UNION):
```java
if (lr.columns().stream().anyMatch(e -> sameColumn(e.name(), c.name()))) {
```
vs `InferenceKernel.java:833-838` (DIFFERENCE):
```java
rr.columns().forEach(c -> drop.add(c.name()));
...
if (!drop.contains(c.name())) {
```
`unionRows` (`:1363-1375`) and `bindRowAccumulating` (`:431`) also use `sameColumn`. DIFFERENCE is the
only site that does not.

**Repro (`K1.java` §5) — actual output:**
```
  T-Z with quoted lhs name, unquoted drop name: ("FIRST NAME":String[1], b:Integer[1])   (UNION uses sameColumn(); DIFFERENCE uses raw String equality)
  T+V quoted-vs-unquoted -> SchemaInvariantException: the column '"FIRST NAME"' already exists in the relation (FIRST NAME:String[1], b:Integer[1])
```
Same pair of names: UNION says "these are the same column", DIFFERENCE says "these are different".
In `Relation<T-Z+V>` (rename's signature) both run on the same schema, so a `Z` sourced with a
different quoting than `T` would leave the old column in place *and* add the new one.

---

### [SILENT FALLBACK] Conflicting bindings are silently widened to `Number`/`Any` instead of rejected, including for a user function's own declared type parameter

**Evidence** — three widening escapes inside `bindOrCheckTypeVar`:
`InferenceKernel.java:502` `b.bindType(v.name(), commonSupertype(existing, actual));` (class LUB),
`InferenceKernel.java:522-525`:
```java
if (isValueKind(existing) && isValueKind(actual)
        && !b.isRigid(v.name()) && !b.contravariant()) {
    b.bindType(v.name(), valueLub(existing, actual));
    return;
}
```
with `valueLub` (`:552-557`) returning `Number` for any two numerics and `Any` otherwise; and
`commonSupertype`'s terminal defaults `InferenceKernel.java:1302` /`:1305`:
```java
return ctx.findType(ancestor).orElseGet(InferenceKernel::anyType);
...
return anyType();
```

**Repro (model `m5.pure`):**
```pure
function my::pairUp<T>(a: T[1], b: T[1]): T[*] { [$a, $b] }
```
```
--- pair-mixed : my::pairUp(1, %2020-01-01)
[G] type=meta::pure::metamodel::type::Any mult=[*]
```
An `Integer` and a `StrictDate` were accepted for the *same* declared type parameter `T`; real
legend-pure rejects this. Kernel-level (`K1.java` §3/§7):
```
  T after String then Integer = meta::pure::metamodel::type::Any
  T Person then Address (covariant LUB) = meta::pure::metamodel::type::Any
  lub(Person, Integer) = meta::pure::metamodel::type::Any
  lub(String, Boolean) = meta::pure::metamodel::type::Any
```
The `[1,2]->concatenate([1.5])` case shows the numeric arm executing:
```
[G] type=Number mult=[*]
[EXEC-COL] value : Number [NUMBER] mult=null
[EXEC-ROW] Double(1.0) | Double(2.0) | Double(1.5) |
```
(Widening itself is direction-safe, but it hides a genuine unification failure and, as
`ancestorsOf` at `:1352` returns `List.of()` for a class the context cannot find, an unresolvable
supertype chain also silently lands on `Any`.)

---

### [SILENT FALLBACK / wrong dispatch] The signature-mangle tail is NOT injective for dispatch: the return-type filter uses `String.endsWith`, so two different mangled signatures resolve to the same overload

**Evidence** — there is **no `mangle()` in `SignatureMangle.java`**: the class only *demangles*
(`tailStart`, `stripTail`, `tailArity`, `tailReturnTypeName`), so nothing enforces round-tripping.
The consumer, `Typer.java:2304-2308`, filters candidates by arity and a **suffix** match on the
return type's name:
```java
return ctx.findFunction(base).stream()
        .filter(f -> f.parameters().size() == arity
                && f.returnType().typeName().endsWith(
                        String.valueOf(ret)))
        .toList();
```
`ClassType.typeName()` returns the full FQN, so `"model::SuperPerson".endsWith("Person")` and
`"StrictDate".endsWith("Date")` are both `true`. When ≥2 candidates survive, the mangled tail is
discarded and ordinary argument scoring picks the winner — i.e. the tail, which in the engine names
exactly one overload, has no effect.

**Repro 1 (model `m4.pure`):**
```pure
function my::h(x: Integer[1]): my::MyString[1] { ^my::MyString(v='a') }
function my::h(x: Number[1]):  String[1]       { 'b' }
```
```
--- tail-names-String-overload : my::h_Number_1__String_1_(1)
[G] type=my::MyString mult=[1]
[G] typeRepr=ClassType[fqn=my::MyString]
```
The reference explicitly names the `Number[1] -> String[1]` overload; the compiler dispatches to
`Integer[1] -> my::MyString[1]` and types the call `my::MyString`.

**Repro 2 (model `m3.pure`)** — two *different* mangled names, one target:
```
--- tail-says-Person : my::g_Number_1__Person_1_(1)        -> [G] type=model::SuperPerson
--- tail-says-SuperPerson : my::g_Integer_1__SuperPerson_1_(1) -> [G] type=model::SuperPerson
```
**Repro 3 (model `m2.pure`)** — primitive suffix collision, `StrictDate`.endsWith(`Date`):
```
--- mangled-date : my::f_Integer_1__Date_1_(1)
[G] type=StrictDate mult=[1]
```

Additional demangler weaknesses found by fuzz (`K5.java`, actual output excerpt):
```
  q_Integer_1__My_Type_1_             tailStart=15  base=q_Integer_1__My   arity=0   ret=Type
  h__A_1__B_1_                        tailStart=1   base=h                 arity=1   ret=B
  h_A_1___B_1_                        tailStart=6   base=h_A_1_            arity=0   ret=B
  _A_1_                               tailStart=0   base=                  arity=0   ret=A
  transform_Step_3_                   tailStart=9   base=transform         arity=0   ret=Step
```
The type-name alphabet is `[A-Za-z][A-Za-z0-9]*` — it cannot contain the `_` separator, so any type
whose simple name contains `_` mis-splits the tail (`My_Type` → base `q_Integer_1__My`, a silent
lookup miss). `stripTail("_A_1_")` returns the **empty string** as a base. `h__A_1__B_1_` and
`h_A_1__B_1_` (the `_?_` optional underscore) are distinct strings that demangle identically.
No case was found where `tailStart`'s regex and `tailArity`'s second, separately-written regex
disagreed on the segment count (14 fuzz inputs, all consistent).

---

### [SILENT FALLBACK — minor] `resolve` leaves unbound variables and multiplicity variables in place instead of failing; `unifyMult`/`unifyMultResult` skip whole checks for relation and Variant actuals

`InferenceKernel.java:783-789` (`resolveIfSolvable`) returns the raw `TypeVar` when unbound;
`InferenceKernel.java:791-794` (`resolveMultIfBound`) returns the raw `Multiplicity.Var`:
```java
private Multiplicity resolveMultIfBound(Multiplicity m, Bindings b) {
    return m instanceof Multiplicity.Var v ? b.mult(v.name()).orElse(m) : m;
}
```
`K1.java` §4:
```
  resolve(function with all-unbound vars) = {A[m] -> B[n]}  (NO throw: unbound vars ride out)
```
These are the exact vehicle by which raw variables reach lowering (see the ICE finding).

`InferenceKernel.java:263-267` and `:700-707` disable multiplicity conformance entirely when the
actual's type is a relation or a Variant. `K4.java` §D:
```
  formal [1], actual [*], actualType=Relation<..>  -> ACCEPTED (skip)
  unifyMult formal [1], actual [*], Relation<..>   -> ACCEPTED (relationSource skip)
```
`InferenceKernel.java:397` `colMult = b.mult(shadowMult(k)).orElse(colMult);` defaults a renamed
column's multiplicity to the *new* colspec's when the shadow slot is unbound.

---

### [INFORMATION LOSS — minor] `resolve` does not substitute into column multiplicities or into `dynamicColumns`

`InferenceKernel.java:796-802`: `resolveColumns` rebuilds each column as
`new Type.Column(c.name(), resolve(c.type(), b), c.multiplicity())` — the multiplicity is copied
verbatim, so a `Multiplicity.Var` on a column can never be solved. `InferenceKernel.java:752-753`
passes `r.dynamicColumns()` through unresolved while resolving `r.columns()`. Not shown reachable
from a query in my tests; flagged as latent.

---

### [DOC-LIE] Prose claims in the audited files contradicted by their own code

1. `Typer.java:3166-3167`: *"Empty bindings — expected is concrete, nothing to solve."* — false for
   every generic user function; this comment is the whole reason for the top finding.
2. `Bindings.java:13`: *"A native call instantiates its signature's variables fresh into one of
   these"* — no instantiation/freshening exists; variables are used under their declared spelling
   (`K4.java` §A).
3. `InferenceKernel.java:1406`: *"A re-bind is OK only if it matches, or either side is `Any` (the
   escape hatch)"* — accurately describes an unsound rule (see finding 2); the repo-wide
   "NO FALLBACKS. NO DEFAULTING." claim is falsified here and at `:813`, `:832`, `:502`, `:524`,
   `:1302`, `:1305`, `:397`, `:793`.
4. `SignatureMangle.java:20-22`: *"callers must filter by `tailArity` and treat no-arity-match as
   no-match, never fall back to the raw strip"* — the caller at `Typer.java:2413` **does** fall back
   to a bare-name existence check and returns an opaque `Function<Any>` ref, and the return-name
   filter is `endsWith`, not equality.
5. `InferenceKernel.java:26`: *"every operation is a pure function of its arguments"* — true of the
   kernel itself (`ctx` is the only state), verified; the mutable `Bindings` is caller-owned.

---

## VERIFIED SOUND

Checked and found correct (each with a run, not by reading):

**Schema algebra (item 4) — every case I could construct produced a clean, user-facing
`TypeInferenceException`/`SchemaInvariantException`, never an internal exception:**
- `extend` with an existing column name → `SchemaInvariantException: the column 'firstName' already
  exists in the relation (firstName:String[1], age:Integer[1])`.
- `select(~[nosuchcol])` → `in call to '...::select', argument 2: unknown column 'nosuchcol' in
  (firstName:String[1], age:Integer[1])`.
- `select(~[])` → `select(~[]) selects no columns`.
- `select(~[firstName, firstName])` and `extend(~[a:x|1, a:x|2])` → `SchemaInvariantException:
  duplicate column 'firstName' in ~[?]`. I could **not** get `Type.RelationType`'s constructor
  `IllegalArgumentException` ("duplicate column ... in relation type",
  `Type.java:531-534`) to escape as an ICE — every kernel path pre-checks with `sameColumn`
  (`:819`, `unionRows:1366`, `bindRowAccumulating:431`).
- `rename(~nosuch, ~zzz)` → `unknown column 'nosuch'`; `rename(~[a,b], ~[x])` → `rename has 2 old
  column(s) but 1 new name(s)`; `rename(~[firstName],~[age])` and `rename(~[a,b],~[z,z])` →
  `SchemaInvariantException: the column 'z' already exists`.
- `T-Z` dropping *all* columns yields the empty relation type `()` — legal at the kernel level, and
  not reachable from a query (`select(~[])` is rejected upstream, `drop` is SQL OFFSET not a column
  drop in this dialect).
- `⊆` accumulation across multiple sort keys (`sort([asc(~c), asc(~c)])`) and
  `groupBy(~[k], ~[c:map:reduce])` produce correct schemas.

**Overload resolution (item 6) — deterministic, and the specificity ordering is real:**
- Candidate lists are `ArrayList`s built in static-declaration order
  (`Pure.Index.FN_BY_FQN`/`FN_BY_BARE`, `Pure.java:949`/`:956` (verified), `computeIfAbsent(k -> new
  ArrayList<>()).add(...)`); the enclosing `HashMap` is only ever `get`-ed, never iterated for
  ordering. `resolveOverload` iterates that `List` (`InferenceKernel.java:905`). **No
  non-deterministically-ordered collection is iterated anywhere in the resolution path**
  (`ancestorsOf` uses a `HashSet` only as a *seen* filter and accumulates into a `List`,
  `InferenceKernel.java:1345-1360`).
- Empirically stable: `K3.java` (8 function names × 7 argument shapes, dumping both the candidate
  order and the chosen `signatureKey`) run **20 times in 20 separate JVMs** → 20/20 byte-identical
  (`20 -1476343963 -1476343932`).
- The specificity table behaves as documented (exact 2 / subtype 1 / var 0, ×20, plus multiplicity
  tightness). `max` has 6 overlapping overloads; observed selection (`K2.java`):
  `max(Integer,Integer)→Integer[1]`, `max(Integer,Float)→Number[1]`, `max(Float,Integer)→Number[1]`,
  `max(Integer,Decimal)→Number[1]`, `max(Number,Number)→Number[1]`, `max(Integer,Number)→Number[1]`;
  `minus(Integer,Float)`, `minus(Decimal,Float)`, `minus(Decimal,Integer)` all → the `Number` pair.
  Never ambiguous, never the wrong direction.
- Ties are handled explicitly: identical shape → first wins (deterministic,
  `InferenceKernel.java:936-960`); different shapes with exactly one native → the native
  (`:955-958`); otherwise `ambiguous overload of '<name>': N candidates tie`. Zero survivors →
  `no overload of '<name>' structurally matches the argument types (...)` with the full candidate
  list. No silent first-match-wins over an unordered collection.
- `scoreNonLambda`/`score`/`paramTypeScore` and `unify`/`unifyMult` agree on the carve-outs I probed
  (Nil-as-bottom, `Function<...>` wrapper unwrapping, TDS/relation nominals, interior result
  multiplicity) — the "two kernel halves must agree" invariant holds for those.

**Exception discipline:** `InferenceKernel` contains exactly **two** `catch` blocks — `:633`
(`unifyColumns`, re-raises with column context) and `:1019` (`resolveChosen`, re-raises with call
context). Both rethrow; neither swallows. Verdict on both: **legitimate**. The only `System.getenv`
(`:1020`, `LL_TDG_DEBUG`) is diagnostics-only.

**Exhaustiveness:** `unify` and `resolve` switch over the sealed `Type` hierarchy with no `default`
arm, so a new `Type` variant is a compile error, as claimed. `SchemaInvariantException extends
TypeInferenceException` and is a clean user-facing `LegendCompileException(Phase.TYPE)`.

**Small files:** `Application.java` and `Expected.java` are inert records/sealed interface — nothing
to falsify. `Args.java` — all five extractors throw `TypeInferenceException` on shape mismatch with
no default value or `null` return; `outputColumns` reads the resolved schema (never re-derives).
`Bindings.java` — `copy()` copies types, mults, `rigid` and `contravariantDepth`; new bindings do not
leak back, as documented. `CoreFn.of` — the `INTERNAL_DESUGAR` bare-name guard and the
FQN→bare-name catalog-native gate both behave as written (`substring(sep + 2)` is correct for the
2-char `::` separator).

---

## NOT COVERED

- **Reaching the one-level-substitution defect (`T:=X`, `X:=Integer`) from a query.** I proved it at
  the kernel API and proved its *consequence* (the vacuous `unify` against a resolved bare variable,
  and the ICE at lowering), but I did not find a surface query whose Phase-G **root** type prints a
  raw `X`. The higher-order surfaces I tried (`preval`, `concatenateTemporalTdsQueries`,
  `eval`-of-`eval`, `[]->map`) all solve their variables. Someone with the `Typer` scope (A03?)
  should re-test `Typer.java:2119-2131`.
- **`?` (`UNKNOWN_COLUMN_TYPE`) escaping into an executed relation schema.** `~col` values into
  generic `T[*]` slots are rejected loudly (`type variable T bound to Integer cannot also bind
  ColSpec<(aa:?[1])>`), and `rename`'s `ColSpecArray<V>` (whose `V` has no `K` to concretize it) is
  intercepted by `RenameChecker`'s array desugar, so the generic `T-Z+V` path never emits a `?`
  column. Not proven impossible.
- **`pivot` / `dynamicColumns` and `_Window`/`over` algebra** — I checked the code paths
  (`:752-753`, `:824-826`, `compatibleRebind`'s unsolved-fragment arm at `:1419-1424`) but ran no
  pivot query; the fixture has no pivot-shaped data.
- **`asOfJoin` / `join` `T+V` with a prefix argument**, and `navigate`/`legacyNavigate`'s `S+Z`
  algebra — read but not exercised.
- **Cross-dialect behaviour.** Every execution result above is DuckDB (the `probe.sh` default).
  SQLite/H2 not exercised.
- **The engine-side mangler.** `SignatureMangle` is a demangler only; I could not test round-trip
  injectivity against a real mangler because none exists in this repo.
- I did **not** run `mvn` (per the brief), so no existing JUnit suite was consulted; every claim
  above is from `jrun.sh`/`probe.sh` runs.
