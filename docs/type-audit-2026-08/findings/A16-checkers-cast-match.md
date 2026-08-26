# A16 — CAST / MATCH / IF / LET / NEW / EVAL / MAP / UserCallInliner

Scope: the type-manipulating constructs. Files read **in full**:
`compiler/spec/{CastChecker,MatchChecker,IfChecker,LetChecker,MapChecker,EvalChecker,NewChecker,UserCallInliner}.java`,
`compiler/spec/typed/{TypedCast,TypedMatch,TypedMatchRuntime,TypedIf,TypedLet,TypedMap,TypedEval,TypedNewInstance,TypedNewInstanceCast,TypedCopyInstance,TypedTypeRef,TypedPackageableRef,TypedEnumValue}.java`,
`lowering/{MatchFold,CastPolicy}.java`, `resolver/CastNav.java`.
Supporting reads: `lowering/Lowerer.java` cast/match arms (2780, 3123, 3191–3289),
`compiler/spec/InferenceKernel.java` `commonSupertype`/`unionRows` (1238–1374),
`resolver/StoreEscapees.java`, `resolver/Substitution.java:1885`, `Compiler.java`, `SpecCompiler.java`.

**Fixtures** (copied next to this file): `A16-fixtures/mf.pure`, `A16-fixtures/ddl2.sql`.
All repros below are `/home/user/probe/probe.sh A16-fixtures/mf.pure <q> test::RT A16-fixtures/ddl2.sql`
unless stated otherwise. Table data: `('1','John','Smith',30,'Johnny','ENG')`,
`('2','Jane','Smith',28,NULL,'OPS')`, `('3','Bob','Jones',45,'Bobby',NULL)`.

---

## FINDINGS

### [UNSOUND] `cast(@Type)` is a pure static re-typing — no runtime type check ever exists; a relation cast to a class hands back a `String` typed as a model class

`CastChecker.check` (CastChecker.java:23–41) runs `t.checkGeneric` against the registered
`cast<T|m>(Any[m], type:T[1]):T[m]`. The source parameter is `Any[m]`, so **every** source
conforms — there is no subtype/relatedness check between source and target at all. The
emitted `TypedCast` reaches `CastPolicy.lower` (CastPolicy.java:49–97), whose non-primitive
arm is:

```java
// CastPolicy.java:96
            return value;
```

i.e. identity. Pure's `cast` is a *checked downcast* (`Cast.java` raises
"Cast exception: X cannot be cast to Y" on the runtime value). legend-lite emits **no** type
test whatsoever; the only "check" is a *statically decided*, value-independent
`error('Cast exception: …')` literal for cross-family primitives (`crossKindRaise`,
CastPolicy.java:181–206).

**Repro (compiler accepts, runtime returns the wrong Java type):**
```
model::Person.all()->filter(p|$p.age > 40)->project(~[a:p|$p.firstName])->cast(@model::Employee)
```
**Actual output:**
```
[G] type=model::Employee mult=[1]
[G] typeRepr=ClassType[fqn=model::Employee]
[PLAN] SELECT (SELECT t0.FIRST_NAME AS a FROM T_PERSON AS t0 WHERE t0.AGE_VAL > 40) AS value
[PLAN] rootType=model::Employee mult=[1]
[EXEC] shape=Scalar returnType=model::Employee returnTypeRepr=ClassType[fqn=model::Employee]
[EXEC-COL] value : model::Employee [ClassType[fqn=model::Employee]] mult=null
[EXEC-ROW] String(Bob) |
```
Static type `model::Employee[1]`; runtime value `java.lang.String`. No cast, no check, no error.

**Companion (unrelated sibling classes compile clean):**
```
model::Person.all()->cast(@model::Dog)->project(~[b:d|$d.breed])
  [G] TypedCast :: model::Dog[*]   .target = ClassType[fqn=model::Dog]
  [PLAN-ERROR] com.legend.error.NotImplementedException: lowering not yet implemented for TypedCast
```
`Person`→`Dog` (siblings under `Entity`, both in `A16-fixtures/mf.pure`) is accepted by G with
type `model::Dog[*]`; only the lowering wall stops it.

Clean rejections that DO work: `@model::NoSuchClass` → `unknown type 'model::NoSuchClass'`;
`@T` at query level → `unknown type 'T'`; `@T` inside `function fn::castT<T>(x:Any[1]):T[1]`
→ `in function 'fn::castT': unknown type 'T' in @T` (so a TypeVar target is never silently bound).

**Why it matters.** Every `->cast(@Class)` in the corpus is an unchecked re-label. Where the
lowering happens to have a channel (scalar subquery above), a wrong-typed value is handed to
the decoder under a class type it does not satisfy.

---

### [UNSOUND] `cast(@Relation<(c:T[1])>)` silently promotes a column's multiplicity: the identity check ignores multiplicity, so a `[0..1]` column is re-declared `[1]` and NULLs flow

`Lowerer.relationCast` decides "identity ⇒ emit the source unchanged" by comparing **name and
type only**:

```java
// Lowerer.java:3238-3243
        boolean identity = tgtRow.columns().size() == srcRow.columns().size();
        for (int i = 0; identity && i < tgtRow.columns().size(); i++) {
            Type.Column tc = tgtRow.columns().get(i);
            Type.Column sc = srcRow.columns().get(i);
            identity = tc.name().equals(sc.name()) && tc.type().equals(sc.type());
        }
```
`Type.Column.multiplicity()` is never consulted, here or anywhere else on the cast path.

**Repro:**
```
model::Person.all()->project(~[a:p|$p.nick])->cast(@Relation<(a:String[1])>)
```
**Actual output:**
```
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.NICK AS a
[PLAN] rootType=Relation<(a:String[1])> mult=[1]
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(Johnny) |
[EXEC-ROW] null |
[EXEC-ROW] String(Bobby) |
```
The source column is `String[0..1]` (`[G-TREE] TypedProject :: Relation<(a:String[0..1])>[1]`).
The cast re-declares it `[1]` — a *mandatory* column — with **no** SQL emitted at all, and
row 2 comes back `null`. A downstream consumer trusting `mult=[1]` (JSON serializer, wire
encoder, `->toOne()` elision) is handed empty in a non-empty slot.

---

### [UNSOUND / INCONSISTENCY] the relation-cast path never consults `crossKindRaise`: `Integer -> Boolean` raises in scalar position but silently forges `true` in a relation cast

Scalar path: `CastPolicy.lower` calls `crossKindRaise` (CastPolicy.java:63-66) and emits an
`error(...)`. Relation path: `Lowerer.tryRelationCast` (Lowerer.java:3276–3280) does

```java
                    SqlExpr v = from.equals(tc.type())
                            || !CastPolicy.isSqlPrimitive(tc.type()) || !CastPolicy.isSqlPrimitive(from)
                            ? r.expr()
                            : new SqlExpr.Cast(r.expr(), PureSql.type(tc.type()));
```
— no `crossKindRaise`, no `isWidening`, no family check.

**Repro A (scalar, correct):** `model::Person.all()->project(~[a:p|$p.age->cast(@Boolean)])`
```
[PLAN] SELECT error('Cast exception: Integer cannot be cast to Boolean') AS a
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: Cast exception: Integer cannot be cast to Boolean
```
**Repro B (relation, silent forgery):** `model::Person.all()->project(~[a:p|$p.age])->cast(@Relation<(a:Boolean[1])>)`
```
[G] type=Relation<(a:Boolean[1])> mult=[1]
[PLAN] SELECT CAST(t0.AGE_VAL AS BOOLEAN) AS a
[EXEC-COL] a : Boolean [BOOLEAN] mult=[1]
[EXEC-ROW] Boolean(true) |
[EXEC-ROW] Boolean(true) |
[EXEC-ROW] Boolean(true) |
```
Ages 30/28/45 all become `true`. Two implementations of the same cast rule, opposite answers.

---

### [UNSOUND / FORWARD-BACKWARD ASYMMETRY] cast laundering: an intermediate *widening* cast makes the following *narrowing* cast a no-op, so the declared type and the decoded Java type diverge

`CastPolicy.lower`'s converting arm is gated on `!PureSql.type(src).equals(PureSql.type(target))`
(CastPolicy.java:75–77). `PureSql.type(Number) == PureSql.type(Float) == DOUBLE`, so
`Number -> Float` is identity — but a `Number`-typed expression reached that point through
`Integer -> Number`, which is *also* identity (widening, CastPolicy.java:216–225). The SQL is
still `BIGINT`; the Pure type is now `Float`.

**Repro (two-step vs one-step, same query family):**
```
1->cast(@Number)->cast(@Float)          1->cast(@Float)
```
**Actual output:**
```
### 1->cast(@Number)->cast(@Float)
[G] type=Float mult=[1]
[PLAN] SELECT 1 AS value
[EXEC-ROW] Integer(1) |            <-- java.lang.Integer decoded under Float[1]

### 1->cast(@Float)
[G] type=Float mult=[1]
[PLAN] SELECT CAST(1 AS DOUBLE) AS value
[EXEC-ROW] Double(1.0) |
```
Same for temporals (`PureSql.type(Date) == PureSql.type(DateTime) == TIMESTAMP`):
```
### %2020-01-02->cast(@Date)->cast(@DateTime)
[G] type=DateTime mult=[1]
[PLAN] SELECT DATE '2020-01-02' AS value
[EXEC-ROW] StrictDate(2020-01-02) |     <-- PureDateLiteral.StrictDate under DateTime[1]

### %2020-01-02->cast(@DateTime)
[PLAN] SELECT CAST(DATE '2020-01-02' AS TIMESTAMP) AS value
[EXEC-ROW] DateWithSecond(2020-01-02T00:00:00+0000) |
```

**Exhaustive supporting matrix.** I drove `CastPolicy.crossKindRaise / isWidening /
isSqlPrimitive / lower` directly over all **12 × 12 = 144** primitive pairs
(package-private probe in `com.legend.lowering`, run via `jrun.sh`). Every cell classified.
The cells that lower to **IDENTITY (no runtime check, target type ≠ source type)** are:

| src | targets lowered as IDENTITY |
|---|---|
| String | StrictTime, LatestDate |
| Integer / Float / Decimal | Number |
| Number | Float |
| Date | DateTime, StrictTime, LatestDate |
| StrictDate | Date, StrictTime, LatestDate |
| DateTime | Date, StrictTime, LatestDate |
| StrictTime | String, Date, StrictDate, DateTime, LatestDate |
| LatestDate | String, Date, StrictDate, DateTime, StrictTime |
| Byte | (none — every cross target raises) |

`Integer/Float/Decimal -> Number` and `StrictDate/DateTime -> Date` are genuine widenings and
sound. **All the others re-label a value into a type it does not have.**

---

### [CRASH/ICE] `cast(@StrictTime)` (and any `StrictTime`/`Byte`-typed relation column) escapes as a raw `java.lang.IllegalStateException` from the lowering boundary

`StrictTime` is a first-class Pure primitive that G accepts, but it has no SQL carrier.

**Repro:** `'hello'->cast(@StrictTime)` — also
`model::Person.all()->project(~[a:p|$p.firstName])->cast(@Relation<(a:StrictTime[1])>)`
```
[G] type=StrictTime mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary
[EXEC-ERROR] java.lang.IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary
```
An internal `IllegalStateException` on input a user can plainly write, not a
`LegendCompileException`/`NotImplementedException`.

---

### [UNSOUND] `match` on a `[0..1]` input against a single `[1]`-declared branch silently promotes the multiplicity; the runtime returns NULL in a `[1]` slot

`MatchChecker.multAccepts` (MatchChecker.java:288–296):

```java
    private static boolean multAccepts(Variable param, Multiplicity inputMult) {
        if (!(inputMult instanceof Multiplicity.Bounded in) || in.upper() != null && in.upper() <= 1) {
            return true;   // to-one input: any branch multiplicity accepts
        }
```
A `[0..1]` input has `upper() == 1`, so **a `[1]` branch accepts it unconditionally**, and the
body is then typed with the parameter bound at the branch's **declared** multiplicity
(MatchChecker.java:95–97):
```java
            Multiplicity bound = param.multiplicity() != null
                    ? Multiplicity.from(param.multiplicity()) : input.info().multiplicity();
```
The runtime-count guard `optionalRuntimeDispatch` bails unless **both** a `[1]` branch and an
empty-accepting branch are present (MatchChecker.java:238: `if (oneBranch == null || emptyBranch == null) return null;`).

**Repro:** `model::Person.all()->project(~[a:p|$p.nick->match([s:String[1]|$s])])`
```
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.NICK AS a
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(Johnny) |
[EXEC-ROW] null |
[EXEC-ROW] String(Bobby) |
```
Real Pure raises "Match failure" when no branch accepts the empty value; here the `[1]`
declaration is simply believed and NULL is returned under it.

*Contrast (this one is correct):* adding the empty-accepting branch takes the
`optionalRuntimeDispatch` path and emits the count test —
`$p.nick->match([s:String[1]|'one', t:String[0..1]|'none'])` →
`SELECT CASE WHEN t0.NICK IS NOT NULL THEN 'one' ELSE 'none' END AS a`.

---

### [INCONSISTENCY / WRONG BRANCH] `MatchFold` re-introduces exactly the wider-arm selection that `TypedMatchRuntime` was created to prevent

`MatchChecker.java:63–68` states the rule and the reason:
> "RUNTIME dispatch guard: if any branch's declared type is a STRICT SUBTYPE of the input's
> static type, the first-accepting static rule would silently take a wider arm where real pure
> (Match.java walks the runtime value) takes the narrow one — keep ALL branches in a
> TypedMatchRuntime …"

`Lowerer.java:3123` then hands that node to `MatchFold.fold`, which does first-**statically**-
conforming arm selection (MatchFold.java:28–37):
```java
    static TypedSpec fold(TypedMatchRuntime mr) {
        for (TypedMatchRuntime.Arm arm : mr.arms()) {
            if (staticConforms(mr.input().info().type(), arm.typeFqn())) {
                return inlineParam(arm.body(), arm.param(), mr.input());
```

**Repro:** `1->cast(@Number)->match([i:Integer[1]|'int', n:Number[1]|'num'])`
```
[G] rootClass=TypedMatchRuntime
[G] type=String mult=[1]
[PLAN] SELECT 'num' AS value
[EXEC-ROW] String(num) |
```
The runtime value is the Integer literal `1` (Pure's `cast` does not change a value's runtime
type), so real Pure's `Match` takes the *first* arm and answers `'int'`. legend-lite answers
`'num'`. Two components implement the same dispatch rule with opposite semantics.

Also note the class-input case is a hard wall rather than a wrong answer
(`MatchFold.staticConforms` answers `false` for any non-primitive), e.g.
`$p.age->cast(@Number)->match([i:Integer[1]|'int', f:Float[1]|'float'])` →
`NotImplementedException: object-space expression node TypedMatchRuntime is not substitutable yet (H2 vocabulary)`.

---

### [UNSOUND] `if` over relation branches fabricates a `RelationType` that **neither** branch satisfies (the LUB is the column **union**, i.e. a lower bound), and it admits selects of phantom columns that then ICE

`IfChecker.java:68–70` takes `t.kernel().commonSupertype(then, else)`.
`InferenceKernel.commonSupertype` (1254–1256) routes relation pairs to `unionRows`
(1363–1374), which **merges the column lists**. Width subtyping runs the other way: a relation
with `{a}` is *not* a subtype of `{a,b}`, so the union is the greatest lower bound, not the LUB.

**Repro 1 — the declared query return type is not what executes:**
```
if(true, |model::Person.all()->project(~[a:p|$p.age]), |model::Person.all()->project(~[b:p|$p.firstName]))
```
```
[G] type=Relation<(a:Integer[1], b:String[1])> mult=[1]
[PLAN] SELECT t0.AGE_VAL AS a
FROM T_PERSON AS t0
[PLAN] rootType=Relation<(a:Integer[1])> mult=[1]
[EXEC] shape=Tabular returnType=Relation<(a:Integer[1])> ...
[EXEC-COL] a : Integer [INTEGER] mult=[1]
[EXEC-ROW] Integer(30) |
```
`Compiler.compileQuery` publishes a schema with a column `b:String[1]` that the executed
result does not have.

**Repro 2 — the phantom column is *selectable*, and the pipeline then ICEs:**
```
if(true, |model::Person.all()->project(~[a:p|$p.age]), |model::Person.all()->project(~[b:p|$p.firstName]))->select(~[b])
```
```
[G] type=Relation<(b:String[1])> mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: select/distinct columns [b] cannot all be resolved even after isolation
[EXEC-ERROR] java.lang.IllegalStateException: select/distinct columns [b] cannot all be resolved even after isolation
```
A raw `java.lang.IllegalStateException` (CRASH/ICE) is the user-facing outcome of a query the
type system said was well-typed. With a runtime condition the fabricated type survives to the
resolver wall instead (`NotImplementedException: class query under if() with a runtime condition
is not resolvable yet` / `lowering not yet implemented for TypedIf` for TDS-literal branches).

Column-name **collisions** are caught (`if(true, |proj(~[a:Integer]), |proj(~[a:String]))` →
`TypeInferenceException: column 'a' appears with conflicting types`) — only the disjoint case
fabricates.

---

### [UNSOUND] `^Class(...)` never checks that required (`[1]`) properties are supplied; the value violates its own class contract and a `[1]` property read returns null

`NewChecker.check` (NewChecker.java:67–166) iterates **only over the supplied keys**
(`ni.properties().forEach(...)`). There is no pass over the class's declared properties, so no
"missing required property" check exists — real Pure's `NewValidator` performs one.

**Repro:** `{| let p = ^model::Person(); $p.firstName; }`
```
[G] type=String mult=[1]
[PLAN] SELECT NULL AS value
[EXEC] shape=Scalar returnType=String returnTypeRepr=STRING
[EXEC-COL] value : String [STRING] mult=null
[EXEC-ROW] null |
```
and `{| let p = ^model::Person(); $p.firstName->length(); }` →
`SELECT length(CAST(NULL AS VARCHAR))` → `[EXEC-ROW] null |` under `Integer[1]`.
The bare construction is equally silent:
```
^model::Person()
[G] type=model::Person mult=[1]
[PLAN] SELECT {'id': NULL, 'firstName': NULL, 'lastName': NULL, 'age': NULL, 'nick': NULL, 'friend': NULL} AS value
[EXEC-ROW] LinkedHashMap({id=null, firstName=null, lastName=null, age=null, nick=null, friend=null}) |
```
Everything else about `^` is checked and correct — see VERIFIED SOUND.

---

### [UNSOUND / WRONG VALUE] UserCallInliner α-hygiene has a hole: a **zero-parameter** callee's binders are not α-renamed, and the fresh `_iN` namespace does not reserve them — the next inlined binder **captures** them

Three facts combine:

1. `lambda()` skips α-renaming entirely when the substitution env is empty
   (UserCallInliner.java:529–530: `private TypedLambda lambda(TypedLambda l, Map<String, TypedSpec> env) { if (env.isEmpty()) { ... }`).
2. A **zero-parameter** callee is inlined with an **empty** `callEnv`
   (UserCallInliner.java:185–200 builds one entry per parameter), so its own lambda binders
   survive verbatim into the tree.
3. `reserveFreshNames` (UserCallInliner.java:100–107, 135–155) only scans the **query body**,
   on the stated — and false — assumption at lines 103–104:
   > "Callee bodies are closed, so the query body is the only collision source."

   so `fresh` is still `0`, and `bind()` (UserCallInliner.java:587–592) then mints `_i0`.

**Repro** (functions in `A16-fixtures/mf.pure`):
```pure
function fn::useX(v: Integer[1]): Integer[1]  { [1000]->map(x   | $x   + $v)->sum() }
function fn::zeroI0(): Integer[1]             { [5]   ->map(_i0 | fn::useX($_i0))->sum() }
```
query: `fn::zeroI0()`   — correct answer is `useX(5)` = `1000 + 5` = **1005**.

**Actual output:**
```
[G] type=Integer mult=[1]
[PLAN] SELECT list_extract(list_transform([5], _i0 -> list_extract(list_transform([1000], _i0 -> _i0 + _i0), 1)), 1) AS value
[EXEC-ROW] Integer(2000) |
```
Post-inlining tree (pre/post diff probe):
```
[POST-TREE]
  TypedNativeCall :: Integer[1] meta::pure::functions::math::sum
    TypedMap :: Integer[1]
      TypedCollection :: Integer[1]
        TypedCInteger :: Integer[1] 5
      TypedLambda :: {Integer[1] -> Integer[1]}[1] params=[_i0]
        TypedNativeCall :: Integer[1] meta::pure::functions::math::sum
          TypedMap :: Integer[1]
            TypedCollection :: Integer[1]
              TypedCInteger :: Integer[1] 1000
            TypedLambda :: {Integer[1] -> Integer[1]}[1] params=[_i0]     <-- captures
              TypedNativeCall :: Integer[1] meta::pure::functions::math::plus
                TypedVariable :: Integer[1] name=_i0
                TypedVariable :: Integer[1] name=_i0                      <-- was the OUTER _i0
```
Control: `fn::useX(5)` → `SELECT list_extract(list_transform([1000], _i0 -> _i0 + 5), 1)` →
`[EXEC-ROW] Integer(1005)`.
Control that the guard *does* work when the name is in the **query** body:
`{| {| [5]->map(_i0 | fn::useX($_i0))->sum() }->eval(); }` →
`list_transform([5], _i0 -> ... list_transform([1000], _i1 -> _i1 + _i0) ...)` → `1005`. ✔

Classic capture-avoiding-substitution bug: **silently wrong value, no diagnostic**. Trigger
requires a user identifier literally matching `_i<digits>` inside a zero-parameter function's
body; the guard that exists for exactly this hazard is scoped to the wrong tree.

---

### [INFORMATION LOSS / INCONSISTENCY] inlining does NOT preserve the call site's type: the callee's declared return type is silently replaced by its body's actual type, so `compileQuery` and `plan`/`execute` disagree

`UserCallInliner.inlineCall` splices `deepFoldInlined(reduceStatements(body, callEnv))` and
returns it as-is (UserCallInliner.java:201–212); nothing re-stamps the result with
`call.info()` (the declared return). The class doc calls this the "no-restamp discipline"
(lines 36–41).

**Repro:** `function fn::widen(x: Integer[1]): Any[1] { $x }`, query `fn::widen(1)`
```
[PRE ] root=TypedUserCall :: meta::pure::metamodel::type::Any[1]
[POST] root=TypedCInteger :: Integer[1]
[DIFF] rootTypeChanged=true   pre=meta::pure::metamodel::type::Any[1]  post=Integer[1]
```
End-to-end:
```
[G] type=meta::pure::metamodel::type::Any mult=[1]
[PLAN] SELECT 1 AS value
[PLAN] rootType=Integer mult=[1]
[EXEC] shape=Scalar returnType=Integer returnTypeRepr=INTEGER
```
`Compiler.compileQuery` says the query returns `Any[1]`; `Compiler.plan` / `Compiler.execute`
say `Integer[1]`. The declared return type is a contract the caller was type-checked against
and G½ discards it. (The direction here is a narrowing, so no value is mistyped — but any
consumer of the G root type, e.g. a published service signature, is told a different type from
what runs. The same mechanism erases a `match`'s branch-narrowed type: see the relation-match
note under NOT-A-DEFECT below.)

---

### [SILENT FALLBACK] `UserCallInliner.inlineCall` swallows **every** `NotImplementedException` from the callee and lets the call stand; the user then gets a misattributed error

```java
// UserCallInliner.java:213-220
        } catch (NotImplementedException e) {
            // The body cannot β-reduce ...— the CALL STANDS with rewritten args.
            return new TypedUserCall(call.callee(), args, call.info());
```
The catch is not narrowed to recursion: any `NotImplementedException` raised while compiling or
rewriting the callee body is discarded together with its message.

**Repro A — recursion.** `function fn::rec(x:Integer[1]):Integer[1] { fn::rec($x) + 1 }`,
query `fn::rec(5)`. The inliner *builds* the precise message
(`"recursion cycle involving fn::rec/1 (…) — recursive functions cannot lower to SQL"`,
UserCallInliner.java:174–180) and then throws it away. What the user sees:
```
[G] type=Integer mult=[1]
[PLAN-ERROR] com.legend.error.NotImplementedException: store resolution left user call 'fn::rec' uninlined — the call shape is not supported by the resolver yet [at root]
```
(`StoreEscapees.java:28–34`. The cycle-naming recovery via `UserCallInliner.selfRecursive`
exists only on the *object-space* wall, `Substitution.java:1885–1897`, which this root-position
query never reaches.) No stack overflow — good — but the diagnosis is lost.

**Repro B — mutual recursion inlines one level and leaves a residual call:**
`fn::mutA(5)` where `mutA` calls `mutB` calls `mutA`:
```
[POST-TREE]
  TypedNativeCall :: Integer[1] meta::pure::functions::math::plus
    TypedUserCall :: Integer[1] fn::mutB
      TypedCInteger :: Integer[1] 5
    TypedCInteger :: Integer[1] 1
[PLAN-ERROR] NotImplementedException: store resolution left user call 'fn::mutB' uninlined — the call shape is not supported by the resolver yet [at root > TypedNativeCall]
```

**Repro C — an unrelated cause misreported as "call shape".**
`function fn::twoStmt(x:Integer[1]):Integer[1] { $x + 1; $x + 2; }`, query `fn::twoStmt(3)`.
Real cause: `reduceStatements` (UserCallInliner.java:271–275) throws
`"a non-let intermediate statement (TypedNativeCall) in an inlined function body is not supported"`.
What the user sees:
```
[PLAN-ERROR] NotImplementedException: store resolution left user call 'fn::twoStmt' uninlined — the call shape is not supported by the resolver yet [at root]
```
The repo's stated rule is "NO FALLBACKS. NO DEFAULTING." — this is a catch-and-degrade.

---

### [UNSOUND at the model/G boundary] function bodies are **not** checked against their declared signature at model-compile time; `compileModel` + `compileQuery` both publish a return type the function can never produce

The declared-return check exists but runs inside `SpecCompiler.compile(callee)`, which is only
driven from `UserCallInliner.inlineCall` (G½) — or from the diagnostics-only
`Compiler.compileAllBodies`, whose own javadoc admits it (Compiler.java:675):
> "…so a function nobody calls never surfaces its type errors"

**Repro:**
```pure
function fn::lastLetWrong(x: Integer[1]): String[1]  { let y = $x + 1; }
function fn::narrowRet(p: model::Person[1]): String[1] { $p.nick }   // body is String[0..1]
```
```
[MODEL] compiled OK
[WALLS] 3
  fn::lastLetWrong(...Integer:[1],) => in function 'fn::lastLetWrong': declares return type String but body returns Integer (expected String, got Integer)
  fn::narrowRet(...model::Person:[1],) => in function 'fn::narrowRet': declares return type String but body returns String (multiplicity [0..1] is not compatible with [1])
  fn::castT(...Any:[1],) => in function 'fn::castT': unknown type 'T' in @T
```
`Compiler.compileModel` returns successfully with all three present. And phase G believes the
declared type:
```
### fn::lastLetWrong(3)
[G] type=String mult=[1]
[PLAN-ERROR] TypeInferenceException: in function 'fn::lastLetWrong': declares return type String but body returns Integer
### model::Person.all()->project(~[a:p|fn::narrowRet($p)])
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN-ERROR] TypeInferenceException: in function 'fn::narrowRet': declares return type String but body returns String (multiplicity [0..1] is not compatible with [1])
```
So `Compiler.compileQuery` hands back `String[1]` / `Relation<(a:String[1])>` for calls that can
never produce them. The error is real and eventually loud — but it fires a phase late, and any
consumer of the F/G surfaces (IDE, service signature publication) sees a clean lie.

---

### [SILENT ACCEPTANCE / INCONSISTENCY] `map` ignores a lambda parameter's **declared** type; `eval` enforces it

`EvalChecker.java:106–115` explicitly unifies a declared parameter type against the argument
("A DECLARED param type is a contract the argument must meet"). `MapChecker.check`
(MapChecker.java:17–40) delegates to `t.checkGeneric` and performs no such check; the generic
path binds the parameter from the **source element type** and the declaration is discarded.

```
### [1,2,3]->map({x:String[1]|$x})
[G] type=Integer mult=[3]
[PLAN] SELECT UNNEST(list_filter(list_transform([1, 2, 3], x -> x), x -> x IS NOT NULL)) AS value
[EXEC-ROW] Integer(1) | Integer(2) | ...

### {x:Integer[1]|$x + 1}->eval('s')
[G-ERROR] TypeInferenceException: eval argument 1: expected Integer, got String
```
No unsoundness follows (the body is typed at the true element type — `[1,2,3]->map({x:String[1]|$x->toUpper()})`
correctly fails with `expected String, got Integer`), but a plainly ill-typed lambda is accepted
in one construct and rejected in the sibling.

---

### [LENIENCY] `let` re-binding the same name with a different type is silently accepted (Pure forbids re-assignment)

```
### {| let x = 1; let x = 'str'; $x; }
[G] type=String mult=[1]
[PLAN] SELECT 'str' AS value
[EXEC-ROW] String(str) |
```
`SpecCompiler.typeQueryBody` (185–203) just overwrites the binding
(`scope = scope.with(let.name(), let.value().info())`); real Pure raises
"The variable 'x' is already defined". Type-safe here (the later binding wins consistently),
but it accepts a program Pure rejects.

---

### [DOC-LIE] `UserCallInliner`'s α-hygiene invariant is stated as unconditional and as query-body-only; both halves are false

- Class doc, UserCallInliner.java:45–48: "**α-hygiene** — INSIDE an inlined body every binder
  (lambda parameter, let name, match parameter) is renamed to a fresh `_i<N>`,
  **unconditionally**: an argument's free variables can never be captured."
  Contradicted by `lambda()`'s `if (env.isEmpty())` early return (line 530) and by the capture
  repro above. Match parameters are additionally never renamed at all — `case TypedMatch m`
  (lines 423–432) puts `m.param()` straight into the env; `TypedMatchRuntime` arm params are
  not renamed either (they fall to `default -> n.mapChildren`).
- Line 103–104: "Callee bodies are closed, so the query body is the only collision source."
  False — see the capture finding.

### [DOC-LIE] `TypedMatchRuntime`'s "SQL lowering … walls LOUDLY" is false for the scalar channel

TypedMatchRuntime.java:20–21: "Consumed ONLY by the host evaluation channel; SQL lowering has no
call frame for runtime type dispatch and walls LOUDLY." `Lowerer.java:3123` routes it through
`MatchFold.fold` and lowers it to a statically chosen arm (see the wrong-branch finding above);
only the *object-space* (`Substitution`) and *relation* channels wall.

### [DEAD/DEFENSIVE] `CastPolicy`'s LITERAL-marked variant guard is self-declared dead

CastPolicy.java:99–114 ends "Dormant until the claim lands: **no live flow routes a
LITERAL-marked value here today**." Reported for completeness only.

---

## VERIFIED SOUND

Everything below was executed, not reasoned about.

**Cast**
- Unknown target type: `@model::NoSuchClass` → `TypeInferenceException: unknown type 'model::NoSuchClass' in @model::NoSuchClass`. Clean.
- Unbound type variable: `@T` at query level and `@T` inside `function fn::castT<T>(x:Any[1]):T[1]` → `unknown type 'T'` / `unbound type variable T`. No silent binding.
- Multiplicity is preserved by `cast` exactly (`cast<T|m>…:T[m]`): `[1,2,3]->cast(@String)` → `String[3]`; `[1,2,3]->cast(@Number)` → `Number[3]`. You cannot change multiplicity with a scalar cast.
- Converting primitive casts really do convert and really do fail loudly on bad data:
  `$p.age->cast(@String)` → `SELECT CAST(t0.AGE_VAL AS VARCHAR)` → `String(30)/String(28)/String(45)`;
  `$p.firstName->cast(@Integer)` → `SELECT CAST(t0.FIRST_NAME AS BIGINT)` → `SQLException: Could not convert string 'John' to INT64`.
  (This is a *deliberate* divergence from Pure per CastPolicy.java:71–73; it is type-consistent, so not filed as unsound.)
- Cross-family scalar casts raise: all 144 primitive pairs classified (table above); `boolean↔anything`, `temporal↔numeric`, `Byte↔anything` all emit `error('Cast exception: …')`.
- `cast(@TabularDataSet)` over a relation is the documented identity (`CastChecker.java:33–39`) and the schema survives: `…->project(~[a:p|$p.age])->cast(@meta::pure::tds::TabularDataSet)` keeps `Relation<(a:Integer[1])>` and returns `Integer(30)/(28)/(45)`.
- Relation cast **narrowing** (fewer columns), reordering, and same-type identity behave as documented; a cast naming columns absent from a static source is a loud `NotImplementedException` (Lowerer.java:3224–3230).

**Match**
- Result type is the **selected branch body's** type under static dispatch, not the signature's `Any[*]`: `$p.age->match([i:Integer[1]|'int', s:String[1]|1])` → `Relation<(a:String[1])>`, `SELECT 'int'`.
- Non-exhaustive match is a clean compile error: `$p.age->match([s:String[1]|'x'])` → `TypeInferenceException: match: no branch matches input type 'Integer[1]'`.
- Overlapping branches: **declaration order** decides. `match([n:Number[1]|'num', i:Integer[1]|'int'])` → `'num'`; reversed → `'int'`. Matches Pure for a statically-exact input.
- LUB over runtime arms with unrelated body types collapses to `Any` and encodes/decodes coherently: `1->cast(@Number)->match([i:Integer[1]|1, n:Number[1]|'str'])` → `[G] Any[1]`, `SELECT to_json('str')`, `[EXEC-ROW] String(str)` under `Any`.
- The `optionalRuntimeDispatch` emission (M4) is correct when both branches exist — `CASE WHEN t0.NICK IS NOT NULL THEN 'one' ELSE 'none' END`.
- `TypedMatchRuntime` is genuinely **not** host-evaluated: its only consumers are `MatchFold` (lowering), `StoreNav` (a source-walk that just descends to `mr.input()`), and `CollectionLanes` (returns `false`). No Java evaluation of Pure values — the repo's "Java executes nothing" claim holds for this node.

**If**
- Non-Boolean condition: `if($p.age, |'x', |'y')` → `TypeInferenceException: expected Boolean, got Integer`.
- `Boolean[0..1]` condition is **rejected**, from the registered signature's multiplicity (IfChecker.java:60–64): `if($p.flag, |'x', |'y')` → `if condition must be Boolean[1], got multiplicity Bounded[lower=0, upper=1]`.
- Branch multiplicity is a real union, not a hardcoded `[1]`: `if(1>0, |'a', |['x','y'])` → `String[1..2]`; `if(1>0,|['p','q','r'],|['x','y'])` → `String[2..3]` and returns 3 rows. Else-less `if` correctly yields `[0..1]` and emits `ELSE NULL`.
- Unrelated scalar branch types → `Any` with a coherent JSON carrier: `if($p.age>30,|'big',|1)` → `CASE WHEN t0.AGE_VAL > 30 THEN to_json('big') ELSE to_json(1) END`, `[EXEC-ROW] JsonNode(1)/JsonNode(1)/JsonNode("big")` — values correct.
- Nested ifs type and evaluate correctly (`Any` LUB, nested `to_json`): rows `JsonNode(1)/JsonNode(true)/JsonNode("old")` for the 3-way age test.
- SQL three-valued logic on a NULL condition behaves as `ELSE`: `if($p.flag->toOne(), |'x', |'y')` → `x/y/y` (row 2's FLAG is NULL). The declared result type `String[1]` still holds.
- `condList` fold and the bare-`pair` overload both nest right and evaluate correctly: `if([pair(|1>2,|'a'), pair(|2>1,|'b')], |'z')` → `CASE WHEN 1 > 2 THEN 'a' ELSE CASE WHEN 2 > 1 THEN 'b' ELSE 'z' END END` → `'b'`.
- Relation branches with a **name-colliding, type-conflicting** column are rejected (`column 'a' appears with conflicting types`).

**Let**
- The let-bound variable's type is the value's type; the whole let statement's type is the value's type; a **trailing** let IS its value (`{| let x = 5; }` → `Integer[1]`, `SELECT 5`, `Integer(5)`).
- Shadowing with the same type works (`{| let x = 1; let x = 2; $x + 1; }` → `3`).
- Use before assignment: `{| $y + 1; }` → `TypeInferenceException: unbound variable '$y'`.
- Relation-typed let splices its pipeline: `{| let r = …project(~[a:…]); $r; }` → `Relation<(a:Integer[1])>` end to end.
- A last-statement let matching the declared return type is fine (`fn::lastLetOk(3)` → `SELECT 3 + 1` → `Integer(4)`); a mismatching one is caught (see the lazy-check finding).

**New / copy**
- Unknown property: `^model::Person(firstName='a', bogus=1)` → `class 'model::Person' has no property 'bogus'`.
- Wrong-typed value: `^model::Person(firstName=1)` → `property 'firstName' of 'model::Person': expected String, got Integer`.
- `[2]` into a `[1]` slot: `^model::Person(firstName=['a','b'])` → `declares multiplicity Bounded[lower=1, upper=1] but the value has Bounded[lower=2, upper=2]`. Full subsumption, as documented.
- `[1]` into a `[*]` slot is accepted and wrapped: `^model::Person(tags='a')` → `'tags': ['a']`.
- Subclass value in a superclass-typed slot: `^model::Person(…, friend=$employee)` → `$p.friend.id` → `String(9)`. Covariance works.
- A sibling/unrelated value in a class slot is rejected: `friend='notAnEntity'` → `expected model::Entity, got String`.
- Copy-with-update `^$p(age=99)` type-checks its overrides (`^$p(age='x')` → `expected Integer, got String`) and evaluates (`$q.age` → `Integer(99)`).
- `TypedNewInstance` order preservation is enforced by construction (LinkedHashMap copy, TypedNewInstance.java:16–23) — the property order in the emitted STRUCT matched declaration order on every run.

**Eval / map**
- `eval` arity: `{x:Integer[1]|$x+1}->eval(5,6)` → `eval: lambda has 1 parameter(s) but 2 argument(s) were supplied`.
- `eval` argument type: `…->eval('s')` → `eval argument 1: expected Integer, got String`.
- `eval` on a non-function: `5->eval(1)` → `eval expects a lambda, a function reference, ~col, or a function-typed value; got Integer`.
- Function value in a variable, then evaled: `{| let f = {x:Integer[1]|$x+1}; $f->eval(5); }` → `SELECT 5 + 1`. Its errors go through the registered `eval` signature and are precise: wrong arg type → `type variable T bound to Integer cannot also bind String`; wrong arity → `function shape mismatch: expected 2 parameter(s), got 1 ({Integer[1] -> Integer[1]})`.
- Untyped lambda parameter binds from the argument: `{x|$x}->eval('s')` → `String[1]`.
- `map` arity mismatch → `no overload of 'map' matches the argument types (lambda has 2 parameter(s) but the function type expects 1)`; non-function mapper → structural no-overload error; body type errors propagate.
- `map` result multiplicity: `[1,2,3]->map(x|$x+1)` → `Integer[3]`; `[1,2,3]->map(x|[$x,$x])` → `Integer[*]` with `flatten(...)`.

**UserCallInliner**
- α-renaming is correct in every non-`_iN` collision I could construct: caller lambda param `y` vs callee lambda param `y`; caller `x` vs callee `x`; a callee binder literally named `_i0` when the **query** also spells `_i0` (fresh correctly started at `_i1`). Pre/post trees pasted in the working notes; all values correct.
- Generic user functions preserve types exactly across inlining:
  `fn::gid<T>(x:T[1]):T[1]` → `fn::gid(5)` pre/post `Integer[1]`; `fn::gid('s')` pre/post `String[1]`;
  `fn::gpick<T,V|m,n>(a:T[m], b:V[n]):T[m]` → `fn::gpick(['a','b'],1)` pre/post `String[2]`.
- Subtype argument: `fn::takesEntity($employee)` inlines to `TypedPropertyAccess :: String[1] .id` over the `model::Employee[1]` instance; `rootTypeChanged=false`.
- Callee let chains β-reduce to one expression: `fn::letChain(3)` (`let a=$x+1; let b=$a*2; $b+$x;`) → `SELECT (3 + 1) * 2 + 3` → `Integer(11)`.
- No infinite inlining / no `StackOverflowError` on direct or mutual recursion (the cycle guard fires; only the *diagnosis* is lost — filed above).
- `TypedMatch` β-reduction cannot narrow a node's type unsoundly: `MatchChecker.runtimeMatch` diverts to `TypedMatchRuntime` whenever any branch type is a strict subtype of the input, so a surviving static `TypedMatch` only ever binds the parameter at a type **wider than or equal to** the input's — substitution therefore widens or preserves, never narrows.

**Nodes read with no finding**
`TypedTypeRef` (prototype-value convention, exercised by every cast repro), `TypedPackageableRef`
(`model::Person` → `Class<model::Person>[1]`), `TypedEnumValue` (`model::Color.RED` →
`EnumType[fqn=model::Color][1]`; `model::Color.PURPLE` → `enumeration model::Color has no value 'PURPLE'`),
`TypedCopyInstance`, `TypedMap.singleHopProperty`, `TypedNewInstanceCast` + `resolver/CastNav.java`
(M2M cast navigation; every unsupported shape is a loud `NotImplementedException` — I could not
reach it from a user query, see NOT COVERED).

---

## NOT COVERED

- **`TypedNewInstanceCast` / `CastNav` end-to-end.** Both are normalizer/resolver-emitted
  (model-to-model mappings). I read them in full and found only loud walls
  (`CastNav.java:50–56`, `:108–110`), but I did not build an M2M mapping fixture to exercise
  the composed-source path, so no behavioural claim is made about them.
- **`cast` on a `Variant` source.** `CastPolicy.lower`'s variant arm (CastPolicy.java:98–149,
  including the `ModelException("The type X is not supported yet!")` class-target wall and the
  `VARIANT_ELEMENTS` / `SqlType.Array` to-many arms) was read but not executed — the fixture has
  no Variant/JSON column. Another auditor covering Variant should re-run the cast matrix with a
  variant source.
- **`->to(@T)` / `->toMany(@T)`.** Same `TypedCast` node and same `CastPolicy`, so the identity
  table above applies, but I ran only `cast`. The multiplicity arms (`to`:`[0..1]`,
  `toMany`:`[*]`) are untested here.
- **`if` with relation branches under a runtime condition, executed.** Both routes wall
  (`class query under if() with a runtime condition is not resolvable yet`;
  `lowering not yet implemented for TypedIf` for TDS-literal branches), so the fabricated
  union type is proven at G and at the `select(~[b])` ICE but not at execution.
- **`IfChecker.failThenValue`** (IfChecker.java:152–174), which stamps a `fail(...)` node with a
  *different* expression's `ExprType`. Read; I could not get `meta::pure::functions::asserts::fail`
  to resolve in this fixture, so the "bottom" stamping is unverified.
- **The `env.containsKey(let.name())` ICE guard** (UserCallInliner.java:437–439,
  `"resolver bug: a let name '…' collided with an inlining binding"`). I could not reach it —
  query-level lets are consumed by `inlineBody` before `rewrite`, callee lets by
  `reduceStatements`, and lambda-body lets by `lambda()`'s own arm. Likely defensive/unreachable.
- **`UserCallInliner.bindStringParam` / `callArgumentFrame`** (lines 630–682) — the
  `StatementExecutor` staging path, not reachable from `Compiler.plan`/`execute`. Read only.
- **Argument evaluation-count semantics.** The inliner duplicates an argument expression once
  per occurrence (verified with literals: `fn::letChain(3)` → `(3 + 1) * 2 + 3`). The class doc
  admits this trade for *lets* (lines 113–115) but not for *arguments*; I did not construct a
  non-deterministic argument to show a divergence.
- **`project` column-multiplicity clamping** (`project(~[a:p|$p.tags])` types the column
  `String[0..1]` for a `String[*]` property, and `project(~[a:p|if(…,|$p.firstName,|['x','y'])])`
  clamps a `String[1..2]` expression to `String[0..1]`). Observed while testing `if`/`cast`;
  it is a `project`-checker question and belongs to whoever owns `ProjectChecker`. Flagging it
  here so it is not lost.
- **Enum / class-ref decoding.** `model::Color.RED` returns `java.lang.String("RED")` under
  `model::Color[1]`, and `model::Person` returns `String("Person")` under `Class<model::Person>[1]`.
  Possibly the intended wire form; the decode auditor (A09) owns the call.
