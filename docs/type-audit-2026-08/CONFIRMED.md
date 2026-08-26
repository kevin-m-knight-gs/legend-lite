# Orchestrator-verified findings (I reproduced these myself, not taken on an agent's word)

## V1 — `==` on an optional operand yields NULL under a `Boolean[1]` column  [UNSOUND]
Model: `qty: Integer[0..1]` -> nullable INTEGER column.
Query: `model::Item.all()->project(~[n:r|$r.name, eq:r|$r.qty==10, gt:r|$r.qty>3])`
```
[G]    Relation<(n:String[1], eq:Boolean[1], gt:Boolean[1])>
[PLAN] SELECT t0.NAME AS n, t0.QTY = 10 AS eq, (t0.QTY IS NOT NULL AND t0.QTY > 3) AS gt
[EXEC] String(bob) | null | Boolean(false)
```
`>` is null-guarded, `==` is not, in the SAME projection. `eq` is declared lower-bound 1 and is null.
Also a VALUE bug: the codebase's own convention for an empty operand is `false` (what `gt` does).

## V2 — `cast(@Any)` then `cast(@T)` erases the check entirely  [UNSOUND, severe]
Query: `model::Item.all()->project(~[x:r|$r.name->cast(@Any)->cast(@Integer)])`
```
[G]        Relation<(x:Integer[1])>
[PLAN]     SELECT t0.NAME AS x        <- bare VARCHAR passthrough
[EXEC-COL] x : Integer  mult=[1]
[EXEC-ROW] String(a)                  <- java.lang.String under an Integer[1] column
```

## V3 — `cast` CONVERTS instead of asserting  [semantics + UNSOUND]
`|2.7->cast(@Integer)` -> `SELECT CAST(CAST(2.7 AS DOUBLE) AS BIGINT)` -> `Long(3)`  (rounds!)
`|2.5->cast(@Integer)` -> `Long(2)`  (banker's rounding)
`|2.7->cast(@Integer)+1` -> `Long(4)`
Pure's `cast` is a checked downcast, never a numeric conversion.
`|'42abc'->cast(@Integer)` -> raw `java.sql.SQLException: Conversion Error` escapes to the caller.

## V4 — high-scale Decimal literal decodes as Double  [UNSOUND, precision loss]
`|3.14159265358979323846264338327950288419D` -> `[G] Decimal(38,38)` -> `[EXEC-ROW] Double(3.141592653589793)`
`|1.5D`                                       -> `[G] Decimal(38,1)`  -> `[EXEC-ROW] BigDecimal(1.5)`
The Java carrier silently switches on the literal's scale; ~22 significant digits lost in the first case.

## V5 — `Multiplicity.product` overflows raw `int`  [UNSOUND + ICE]
Direct algebra probe:
```
[0..65536]      . [0..65536]      = [0]            <- claims ALWAYS EMPTY for ~4.3e9 possible values
[0..2147483647] . [0..2147483647] = [0..1]         <- claims AT MOST ONE
[50000]         . [50000]         -> IllegalArgumentException: lower must be >= 0, got -1794967296
[0..46341]      . [0..46341]      -> IllegalArgumentException: upper (-2147479015) must be >= lower (0)
[2..1073741824] . [2..1073741824] -> IllegalArgumentException: upper (0) must be >= lower (4)
```
Reachable from ordinary Pure source: `Class model::A { bs: model::B[0..65536]; }` etc, then
`model::A.all()->project(~[x:a|$a.bs.cs.v])` -> `[G] Relation<(x:String[0])>`.
The silent wraparounds are worse than the crashes.

## V6 — StrictTime literal has no lowering  [CRASH/ICE on valid input]
`|%10:30:45` -> `[G] StrictTime[1]` then
`com.legend.error.NotImplementedException: scalar lowering not yet implemented for TypedCTime`

## V7 — `Long.MIN_VALUE` literal typed Integer decodes as BigDecimal  [carrier inconsistency]
`|-9223372036854775808` -> `[G] Integer[1]` -> `SELECT CAST(-9223372036854775808 AS DECIMAL(19,0))` -> `BigDecimal(...)`
The value is exactly representable as a `long`; the carrier still switches.

## V8 — `native-catalog.txt` is a SELF-golden, not an oracle  [DOC-LIE / unverified claim]
`Pure.java` header: "HAND-CURATED port ... Every signature is VERBATIM to its real .pure source
(verified per function; NO divergence categories remain as of 2026-07-08)".
`NativeFunctionTest.catalogMatchesTheGoldenFile` renders `Pure.all()` and compares it to
`src/test/resources/native-catalog.txt`, which is generated from `Pure.all()`. It pins the catalog
against ITSELF. Nothing in the repo checks any signature against real legend-pure.

## V9 — the three compile seams agree on root type
`Compiler.compileQuery` (G) / `Compiler.plan` (A-J) / `Compiler.execute` (A-K) produced identical
root `ExprType` on all 10 probe queries incl. scalars, collections, relations, and an error case.
No divergence found. (VERIFIED SOUND.)

## V10 — `extends ...type::Nil` makes a class a subtype of EVERYTHING  [UNSOUND, total]
Model: `Class model::Evil extends meta::pure::metamodel::type::Nil { z: String[1]; }`
```
isSubtype(model::Evil, model::Person)                        = true
isSubtype(model::Evil, meta::pure::metamodel::type::Integer) = true
isSubtype(model::Evil, no::such::Type)                       = true   <- a type that does not exist
isSubtype(model::Person, model::Evil)                        = false
```
Three lines of ordinary Pure collapse the subtype relation. The bottom-type arm fires INSIDE the
recursion, so it answers `true` before checking whether the RHS is even a real type.

## V11 — cross-kind `==` compiles and returns true  [UNSOUND]
```
|1 == '1'     -> [G] Boolean[1] -> Boolean(true)
|true == 1    -> [G] Boolean[1] -> Boolean(true)
|'a' == 1     -> [G] Boolean[1] -> java.sql.SQLException: Could not convert string 'a' to INT32
```
Real Pure rejects `==` between unrelated types at compile time. Here it type-checks, and the answer
is either wrong (`true`) or a raw JDBC exception, depending on the DB's coercion.

## V12 — collection element LUB is ORDER-DEPENDENT and can under-declare scale  [UNSOUND]
```
|[1.25d, 1.5d] -> [G] Decimal(38,1)[2] -> BigDecimal(1.25)   <- declared scale 1, value scale 2
|[1.5d, 1.25d] -> [G] Decimal(38,2)[2] -> BigDecimal(1.50)
```
The join of two PrecisionDecimals returns the SECOND operand verbatim instead of a least upper
bound, so the element type flips with source order and can be narrower than a member value.

## V13 — `PrecisionDecimal` arithmetic is DEAD CODE  [dead + INFO-LOSS]
`Type.PrecisionDecimal.{plus,minus,times,dividedBy,adjust}` and `DEFAULT_DECIMAL` have ZERO
PRODUCTION callers (verified by grep over `core/src/main/java`). They are called from exactly one
place in the repository: `PrecisionDecimalArithmeticTest.java`, 9 call sites. My original wording
"zero callers" was wrong. Only `MAX_PRECISION` is referenced,
from `TdsChecker:169,224` and `Typer:166,170,3180-3184`. The carefully-derived
MS-SQL/Hive/Spark precision algebra is never consulted; decimal arithmetic erases (p,s) instead.

## V14 — sites comparing `== Type.Primitive.DECIMAL` without the `PrecisionDecimal` arm
There are 31 `Primitive.DECIMAL` references in main. Nine comparison sites correctly add
`|| t instanceof Type.PrecisionDecimal` (CsvSeed:144, LiteralSpelling:68/110, DecimalKindRules:84,
MixedEncoding:134/194/435, Scalars:2926, CastPolicy:219). **SEVEN do not**:
`Repr.java:51`, `DateCtorRule.java:100`, `Scalars.java:3467`, `CastPolicy.java:144`,
`PlanText.java:557`, `CanonicalRenderSql.java:224`, `Typer.java:1815`.
(I first published only the four; the last three were found by a falsifier and I verified each.)
Observable consequence of `Repr:51`:
```
|format('%r', 1.25D) -> String('1.25')    <- quoted as a STRING, no D suffix
|format('%r', 1.25)  -> String('1.25')    <- Float and Decimal are indistinguishable
```

## V15 — `project(~[])` types 0 columns but emits `SELECT *`  [type/plan disagreement -> ICE]
```
model::Item.all()->project(~[])
[G]    Relation<()>[1]          (RelationType columns=[])
[PLAN] SELECT *
[EXEC] java.lang.IllegalStateException: result has 3 columns but the typed schema has 0 - plan/schema mismatch
```
The frontend and the lowering disagree about the same node; the executor catches it as an internal
error rather than the checker rejecting `~[]` (SelectChecker and DistinctChecker DO guard this).

## V16 — a ROW-typed extend column violates BOTH its declared name and type  [UNSOUND]
```
model::Item.all()->project(~[a:p|$p.name])->extend(~[b:r|$r])
[G]        Relation<(a:String[1], b:(a:String[1])[1])>     <- column b declared as a ROW
[PLAN]     SELECT t0.NAME AS a, t0.NAME AS b
[EXEC-COL] a : String[1]
[EXEC-COL] b_a : String[1]                                  <- name is b_a, type is String
```
Declared column `b : (a:String[1])[1]`; delivered column `b_a : String[1]`. A consumer reading the
declared schema by name or by type finds neither.

## V17 — a many-valued projected cell is clamped to `[0..1]` then ICEs  [INFO-LOSS -> ICE]
```
model::Item.all()->project(~[b:p|[1,2,3]])
[G]    Relation<(b:Integer[0..1])>     <- clampTdsCells silently rewrites [3] to [0..1]
[PLAN] SELECT [1, 2, 3] AS b           <- but the lowering emits an ARRAY
[EXEC] IllegalStateException: a many-valued cell reached a scalar TDS slot ('b')
```
The clamp discards the real bound with no diagnostic, and the lowering does not honour the clamp.

## V18 — a generic user function's declared return type is NEVER checked  [UNSOUND — cleanest proof]
```
function my::bad<T>(x: T[1]): T[1] { 'hello' }

model::Item.all()->project(~[a:p|$p.name])->extend(~[b:x|my::bad(1)])
[G]        Relation<(a:String[1], b:Integer[1])>
[PLAN]     SELECT t0.NAME AS a, 'hello' AS b
[EXEC-COL] b : Integer  mult=[1]
[EXEC-ROW] String(a) | String(hello)
```
`T` is bound to Integer from the ARGUMENT; the body is never checked against `T`; a String literal
is delivered in a column the compiler declares `Integer[1]`.

## V19 — `sum()` violates its `Integer[1]` declaration in two directions  [UNSOUND]
Empty group:
```
...->filter(x|$x.qty>1000)->project(~[a:p|$p.qty])->groupBy(~[], ~[s:x|$x.a:y|$y->sum()])
[G] Relation<(s:Integer[1])>   [EXEC-COL] s : Integer mult=[1]   [EXEC-ROW] null
```
Overflow (two BIGINT rows of Long.MAX_VALUE):
```
[G] Relation<(s:Integer[1])>   [EXEC-ROW] BigInteger(18446744073709551614)
```
The declared type is `Integer[1]` in both cases: null violates the lower bound, and 2^64-2 is
outside any 64-bit Integer. Note also the ordinary case returns `BigInteger(15)`, so one declared
Pure type has at least three Java carriers.

## V20 — `->toOne()` is SILENTLY DELETED in object space  [UNSOUND — the assertion vanishes]
```
model::Item.all()->toOne()->project(~[a:p|$p.name])
[G]    Relation<(a:String[1])>[1]
[PLAN] SELECT t0.NAME AS a          <- no LIMIT, no count guard, no error()
[EXEC] String(a)
[EXEC] String(bob)                  <- 3 rows out of a ->toOne()
```
`toOne()` is Pure's runtime cardinality ASSERTION. Phase H drops the node. Contrast the
relation-space path, which does emit the guard: `|[]->toOne()` renders
`SELECT error('Cannot cast a collection of size 0 to multiplicity [1]')`. So the guard exists —
object-space `toOne()` just isn't wired to it.

## V21 — the same Decimal column decodes exactly on DuckDB and CORRUPTED on SQLite  [UNSOUND]
One model, one query, one `DECIMAL(38,9)` column holding `12345678901234567.123456789`:
```
DuckDB: declared=Decimal -> BigDecimal(12345678901234567.123456789)
SQLite: declared=Decimal -> Long(12345678901234568)
```
SQLite loses all 9 fractional digits AND rounds the integer part, under an identical static type
and with no error. Backend-dependent value corruption behind one type claim.

## V22 — `BOOLEAN` is not an accepted column datatype in the `###Relational` DSL
`Table T (FLAG BOOLEAN)` -> `ParseException: [16:33] unsupported column datatype: BOOLEAN`
on all three backends. A Pure `Boolean[1]` property therefore has no natural physical column type.

## V23 — a user model can REDEFINE a platform stdlib native  [UNSOUND / isolation hole]
Adding this to an ordinary user model:
```
function meta::pure::functions::collection::first(c: Integer[*]): String[1] { 'HIJACKED' }
```
changes the meaning of `first` for the whole model:
```
|[1,2,3]->first()
  with hijack: [G] String[1]      [PLAN] SELECT 'HIJACKED' AS value    -> String(HIJACKED)
  control:     [G] Integer[0..1]  [PLAN] SELECT list_extract([1,2,3],1) -> Integer(1)
```
User source silently wins over the platform native, taking the TYPE with it. (Note the control's
`Integer[0..1]` is correct — the platform signature itself is right.)

## V24 — the let-inliner is CAPTURE-UNSAFE  [WRONG RESULTS]
*(Corrected after falsification — my first repro was invalid; see the CORRECTIONS section.)*

Two programs differing ONLY in the inner lambda's binder name, with `$y` free in both bodies —
genuinely alpha-equivalent — give different answers:
```
|[10]->map({x| let y = $x; [7]->map(x|$y)->toOne();})   -> Integer(7)    WRONG (correct is 10)
     SQL: list_transform([10], x -> list_transform([7], x -> x))
                                                    ^^^^^^^^^^ the substituted $x is captured

|[10]->map({x| let y = $x; [7]->map(w|$y)->toOne();})   -> Integer(10)   correct
     SQL: list_transform([10], x -> list_transform([7], w -> x))
```
Mechanism: `let y = $x` makes the inliner substitute `$y := $x`. When that `$x` lands in a scope
where `x` has been re-bound by the inner lambda, it is captured. No alpha-renaming is performed.
Both forms type as `Integer[1]` — the TYPE is fine, the VALUE is silently wrong.

## V25 — `first(set, count)` silently drops its count argument  [WRONG RESULTS]
```
|[1,2,3]->first(2) -> [G] Integer[*]  SELECT UNNEST(list_filter([list_extract([1,2,3], 1)], ...))
|[1,2,3]->take(2)  -> [G] Integer[*]  SELECT UNNEST(list_filter(array_slice([1,2,3], 1, 2), ...))
```
`first(2)` lowers to `list_extract(..., 1)` — a ONE-element list. The 2-arg overload resolves, then
the lowering applies the 1-arg rule. `take(2)` (the correct sibling) emits `array_slice(...,1,2)`.

## V26 — the RELATIONAL MAPPING performs NO property-type / column-type check  [UNSOUND — root cause]
```
Class model::Item { s: String[1]; i: Integer[1]; }
Mapping: s -> T_ITEM.NUM (INTEGER column) ; i -> T_ITEM.DEC (DECIMAL(10,2) column)

model::Item.all()->project(~[s:x|$x.s, i:x|$x.i])
[G]        Relation<(s:String[1], i:Integer[1])>
[PLAN]     SELECT t0.NUM AS s, t0.DEC AS i      <- bare passthrough: no cast, no check, no error
[EXEC-COL] s : String[1]        i : Integer[1]
[EXEC-ROW] Integer(42)        | BigDecimal(123.45)
```
A `String[1]` property over an INTEGER column delivers `java.lang.Integer`.
An `Integer[1]` property over a DECIMAL column delivers `java.math.BigDecimal(123.45)` — not even
integral. The mapping IS the bridge between the Pure type world and the physical schema, and it is
unvalidated. This is the root cause behind a large share of the other decode findings.

## V27 — `^Class()` does not enforce required `[1]` properties  [UNSOUND]
```
Class model::Person { firstName: String[1]; age: Integer[1]; }

{| let p = ^model::Person(); $p.firstName; }
[G]    String[1]
[PLAN] SELECT NULL AS value
[EXEC] null
```
Construction supplies neither required property. The constructor call type-checks, the property read
is typed `String[1]`, and the delivered value is null. `NewChecker` iterates the SUPPLIED keys, so a
key that is absent is never noticed.

## V28 — `plan()` and `execute()` render DIFFERENT DIALECTS for the same runtime  [INCONSISTENCY]
Runtime declared `type: H2`. Query: `project(~[u:p|$p.name->startsWith('a')])`.
```
Compiler.plan(model, query, "test::R").sql()
  -> SELECT starts_with(t0.NAME, 'a') AS u FROM T_ITEM AS t0
running that exact SQL on the H2 session it was planned for:
  -> ERR Function "STARTS_WITH" not found
Compiler.execute(model, query, "test::R", h2Connection)
  -> true    (execute renders H2-correct SQL)
```
`plan()` is the documented plan-inspection / `toSQLString` surface. It emits SQL the target database
cannot run, while `execute()` emits correct SQL for the same model, query and runtime.
(This refines V9: the seams agree on TYPES; they disagree on dialect SELECTION.)

## V29 — `match` dispatches on the STATIC type, picking the wider arm  [WRONG RESULTS]
```
|'x'->cast(@Any)->match([s:String[1]|10, a:Any[1]|20])
[G]    Integer[1]
[PLAN] SELECT 20 AS value
```
The value is a String; real Pure's `match` dispatches on the RUNTIME type and answers 10. Here the
static type (`Any`, from the cast) selects the first STATICALLY conforming arm — `Any[1]` — so the
narrower `String[1]` arm is unreachable. `TypedMatchRuntime`'s own javadoc says it exists to prevent
exactly this.

## V30 — type-checks-then-cannot-execute: constructs with no lowering arm  [CRASH on valid input]
```
|%10:30:45          [G] StrictTime[1]        -> NotImplementedException: ... for TypedCTime
|[1,2,3]->sortBy(x|$x)  [G] Integer[3]       -> NotImplementedException: ... for TypedSortBy
|~a                 [G] ColSpec<(a:?[1])>[1] -> NotImplementedException: ... for TypedColSpec
```
Note `|~a` also shows an UNRESOLVED type variable `?` escaping into a Phase-G type.
A fleet auditor measured this systematically: of 721 native overloads, 523 type-check at G and
**112 of those 523 then die with an internal exception at plan** (87 of them "no scalar lowering
registered").

## V31 — a malformed time literal escapes as a raw internal exception  [ICE]
```
|%25:00:00 -> java.lang.IllegalStateException: time literal '%25:00:00' is out of range
```
Not a `LegendCompileException`; no phase, no position. The parallel date form
`%2020-01-01T25:00:00` produces a clean ParseException, so the discipline exists — the time path
just doesn't use it.

## V32 — decimal arithmetic ERASES precision and scale  [INFO-LOSS, corroborates V13]
```
|0.01D                 -> [G] Decimal(38,2)   (literal typing keeps (p,s))
|0.001D->plus(0.002D)  -> [G] Decimal         (BARE — (p,s) gone)
```
The operands carry precision and scale; the result does not. This is the observable consequence of
V13: `Type.PrecisionDecimal.plus/minus/times/dividedBy` — the derivation that would compute the
result's (p,s) — has zero callers, so arithmetic falls back to an unparameterized `Decimal`.

## V33 — a declared `Decimal(p,s)` property becomes the degenerate type `Decimal<>`  [UNSOUND]
```
Class model::Money { amount: Decimal(18,4)[1]; }

compiled: amount type = Decimal<>
          repr = GenericType[rawFqn=meta::pure::metamodel::type::Decimal, arguments=[]]
query   : Relation<(a:Decimal<>[1])>
```
The declared precision and scale are silently discarded and the property lands as a `GenericType`
with an EMPTY argument list. This type is neither `Type.Primitive.DECIMAL` nor a
`Type.PrecisionDecimal`, so **every** decimal-handling site fails to recognise it — both the 9 sites
that correctly test `== Primitive.DECIMAL || instanceof PrecisionDecimal` and the 4 that test only
the former (see V14). A model author writing the most natural thing — a money column with declared
precision — gets a type the rest of the compiler does not know about.

## V34 — quoted FQN segments unquote into the path, colliding distinct elements
```
Class test::'A::B' { v: String[1]; }
Class test::A::B   { w: String[1]; }
-> ModelException: [2:1] Duplicated element 'test::A::B'
```
Two syntactically distinct declarations become one element. Caught as a duplicate when both are
present (benign); the risk is the one-sided case, where a reference to one silently resolves onto
the other.

## V35 — the native catalog DIVERGES from real FINOS Legend; the "VERBATIM" claim is false
Verified by the orchestrator by READING the cloned upstream sources
(`finos/legend-pure` @18cd1bb, `finos/legend-engine`), not by inference.

`Pure.java:14-19` claims: "HAND-CURATED port of the real legend-pure/legend-engine native catalog.
Every signature is VERBATIM to its real .pure source (verified per function; NO divergence
categories remain as of 2026-07-08)."

**percentile** — real, `core_functions_standard/math/aggregator/percentile.pure:17,25`:
```
percentile(numbers: Number[*], percentile: Float[1]): Number[0..1]
percentile(numbers: Number[*], percentile: Float[1], ascending: Boolean[1], continuous: Boolean[1]): Number[0..1]
```
legend-lite, `native-catalog.txt:542-543`:
```
percentile(numbers:Number[*], p:Number[1]):Number[1]                     <- real: Number[0..1]; param Float[1] not Number[1]
percentile(numbers:Number[*], p:Number[1], ascending:Boolean[1], continuous:Boolean[1]):Number[0..1]
```
Two divergences, plus lite's own two overloads disagree with EACH OTHER on the return multiplicity.
A `[1]` return licenses the compiler to promise non-null for an aggregate that is null on empty input.

**first** — real, `platform/pure/grammar/functions/collection/slice/first.pure:24`, the ONLY declaration:
```
meta::pure::functions::collection::first<T>(set:T[*]):T[0..1];
```
legend-lite, `native-catalog.txt:177-178`, adds an invented second overload:
```
first<T>(set:T[*], count:Integer[1]):T[*]      <- no such function upstream
```
This EXPLAINS V25: the count argument is dropped at lowering because the overload is an invention
with no real semantics behind it.

**limit** — lite carries both `size:Integer[1]` and `size:Integer[0..1]` overloads
(`native-catalog.txt:413-414`); the `[0..1]` form lets `limit([])` compile and the LIMIT clause
silently vanish from the SQL.

Fleet-wide measurement over all 721 signatures vs 24,172 extracted real declarations:
**538 EXACT / 16 return-type differences / 131 argument differences / 36 functions that do not exist
upstream = 183/721 (25.4%) divergent.** Nothing in the repo can detect this: the only guard renders
`Pure.all()` and compares it to a file generated from `Pure.all()` (V8), and that renderer additionally
DROPS generic multiplicity arguments and relation-column multiplicities, so it could not see these
differences even if the golden file were independent.

# CORRECTIONS — claims that did NOT survive re-checking
These were filed by auditors, forwarded by me, and then refuted. Recorded so the report does not
carry them.

1. **WITHDRAWN — this "correction" was itself wrong.** I wrote that A01/A03 had claimed
   `InferenceKernel` mishandles `PrecisionDecimal` near line 580. No auditor filed that; A01 in fact
   lists `InferenceKernel.java:577-580` among the sites that DO carry the arm. I mis-attributed my
   own grep hit to an agent and then "refuted" it. The code fact stands (`isNumeric` opens with
   `t instanceof Type.PrecisionDecimal ||`); the retraction was unnecessary and the attribution unfair
   to A01, whose census was accurate and more complete than mine.
2. **`PlanText.java:557` throwing for any DECIMAL store column** (filed by A01, forwarded by me to A29).
   REFUTED by A29 with a direct call: `:557` IS the `Primitive.DECIMAL` arm and returns "Decimal";
   a real render of a `DECIMAL(10,2)` column gives `TDS[(price, Decimal, DECIMAL(10,2), "")]`.
   The real hole is `:567`, which has no `PrecisionDecimal`, `LATEST_DATE` or `STRICT_TIME` arm —
   `planToString{|let p = 1.5d; ...}` throws `NotImplementedException`.
3. **"CDecimal/CFloat are double-backed"** (hypothesis put to A26). REFUTED: `CDecimal` is backed by
   `BigDecimal` and `CFloat`'s `double` correctly matches Pure `Float`. No precision is lost at parse.
4. **"seams disagree"** — needed splitting rather than refuting. The three compile seams agree on
   root TYPE (V9); they disagree on DIALECT SELECTION (V28). Reported as two separate facts.

## V36 — the suite is GREEN and cannot see any of this  [meta-finding]
Verified by the orchestrator from the surefire reports of a full run in a separate copy of the repo:
```
484 test classes -> TOTAL tests=4278 failures=0 errors=0 skipped=16
```
Of the 19 findings put to a coverage check: **0 COVERED, 3 PINNED, 15 UNCOVERED.**

The measurement that explains why. Of 1,671 test methods that execute a query end to end:
- 888 (53.1%) assert rows/values
- 4 (0.24%) assert a result column's `pureType()` / `multiplicity()`
- exactly 1 (0.06%) relates a declared column type to the DELIVERED Java carrier
  (`PivotCheckerTest:400-421`, scoped to `__|__` pivot columns, at `Number` granularity)
Whole tree: 7 `Column.pureType()` assertion lines vs 1,943 `.rows()` sites vs 87 `"SELECT` literals.

Compile-time typing is densely and adversarially tested. Row values are densely tested. **The seam
between them — does the value conform to the type the compiler promised — is unguarded**, and that is
exactly where 15 of the 19 findings sit.

Three findings are PINNED — an existing test asserts the CURRENT WRONG behaviour, so fixing the bug
turns the suite red:
- **V3 (cast converts)**: `TypeConversionCheckerTest` casts a String column to `@Integer` and asserts
  arithmetic on it (`testCastThenSum:57`, `testCastThenFilterNumeric:81`, `testCastThenArithmetic:103`).
  The SAME FILE at `:363` asserts "Relational cast is a type assertion, should not emit SQL CAST()".
  The contradiction sits 300 lines apart in one file.
- **V11 (cross-kind `==`)**: `EqualityWorldsConformanceTest.declaredDivergences:90` pins
  `1 == 1.0 -> true` and `8 == 8D -> true` as "declared divergences" — known and accepted.
- **V13 (dead decimal algebra)**: `PrecisionDecimalArithmeticTest` has 9 tests including a 20x20x20x20
  sweep, on code whose ONLY caller in the repository is that test file. The algebra is exhaustively
  tested and entirely unused; deleting the dead code would turn 9 tests red.

## V37 — error discipline degrades MONOTONICALLY down the pipeline  [structural]
Orchestrator's own census over `core/src/main/java` — `throw new X` sites, counting
`LegendCompileException`/`ModelException`/`ParseException`/`TypeInferenceException`/
`MappingResolutionException`/`SchemaInvariantException` as user-facing and everything else as internal:
```
total throw new = 1168 ; user-facing = 318 ; INTERNAL = 850 (72%)

parser                 total=  31 internal=   8 ( 25%)
compiler/spec (Typer)  total= 205 internal=  31 ( 15%)
compiler/element       total=  34 internal=  17 ( 50%)
normalizer             total= 114 internal=  58 ( 50%)
resolver               total= 241 internal= 204 ( 84%)
lowering               total= 144 internal= 140 ( 97%)
sql                    total=  54 internal=  54 (100%)
sql/dialect            total=  47 internal=  47 (100%)
exec                   total=  28 internal=  28 (100%)
plan                   total=  31 internal=  31 (100%)
values                 total=  37 internal=  37 (100%)
protocol               total=  42 internal=  42 (100%)
```
`AGENTS.md` states every user-visible error carries one of eight `LegendCompileException.Phase`
values. That holds in the frontend and collapses after it. `NotImplementedException` — the
most-thrown type, 293 throw sites — `extends RuntimeException`, not `LegendCompileException`
(`error/NotImplementedException.java:8`).

A fleet sweep of 247 queries over the whole `builtin/Pure` native surface produced **85 internal
exceptions versus 5 user-facing errors** — and every one of those queries had passed phase G. This
is the mechanism behind V30: type-checks-then-cannot-execute is not an edge case, it is the default
failure mode of the back half.

## FAIRNESS NOTE on the "NO FALLBACKS" invariant
The repo's most-cited rule is "NO FALLBACKS. NO DEFAULTING." A mechanical census of 1,560
type-decision sites found **34 (2.2%)** that can silently produce a type not derived from the model
(10 classed VIOLATION, 24 SUSPICIOUS; 24 proven reachable by a real run; 5 proven unsound).
Also measured: `default ->` 442 but `default:` **0**; `orElseThrow` 153 vs `orElseGet` 17;
`FIXME`/`XXX` **0**, `TODO` 4; 140 catch blocks of which **none substitutes a `Type`**.
So the invariant is ~97.8% held at the site level, and that should be said plainly: the problem this
audit found is NOT a codebase riddled with fallbacks. It is the absence of any check that a produced
VALUE conforms to its declared type.

## V38 — structural equality ERASES class identity  [UNSOUND]
```
Class model::A { x: Integer[1]; y: String[1]; }
Class model::B { x: Integer[1]; y: String[1]; }     <- a DIFFERENT class, same shape

|^model::A(x=1,y='p') == ^model::B(x=1,y='p')
[G]    Boolean[1]
[PLAN] SELECT {'x': 1, 'y': 'p'} = {'x': 1, 'y': 'p'} AS value
```
The emitted SQL is a bare struct-to-struct comparison; neither class FQN appears anywhere in it.
Two instances of unrelated classes with the same property shape compare equal. An auditor further
found that `<<equality.Key>>` annotations are ignored on the execute path entirely — the
key-respecting comparator (`lowering/InstanceEquality.java`) is armed only on the assert-verdict
lane, so `Compiler.execute` always compares raw SQL structs.

## V39 — `[1]` is not honoured even by a PERFECTLY MATCHED mapping  [UNSOUND — the sharpest form]
No type mismatch at all. An `Integer[1]` property mapped to an `INTEGER` column. The column is
merely nullable in the DDL:
```
Class model::Item { name: String[1]; qty: Integer[1]; }
Table T_ITEM ( ID INTEGER PRIMARY KEY, NAME VARCHAR(100) NOT NULL, QTY INTEGER )

model::Item.all()->project(~[n:r|$r.name, q:r|$r.qty])
[G]        Relation<(n:String[1], q:Integer[1])>
[PLAN]     SELECT t0.NAME AS n, t0.QTY AS q          <- no guard, no coalesce, no error
[EXEC-COL] q : Integer  mult=[1]
[EXEC-ROW] String(a)   | Integer(10)
[EXEC-ROW] String(bob) | null                        <- null under Integer[1]
```
The exhaustive 874-cell mapping matrix found this in **117 of 117 executable `[1]` cells, including
the matched diagonal**. The only thing that makes a `[1]` property actually non-null is declaring the
physical column `NOT NULL` — which the mapping layer neither requires nor checks.

This is the thesis of the audit in one line: **`[1]` is an annotation, not a guarantee.**

## Matrix headline (fleet-measured, exhaustive)
874 cells = 23 Pure property types (incl. 10 precisePrimitives + an enum) x 2 multiplicities x 19
accepted column spellings, each run 3x (representative / edge / NULL) on DuckDB:
**100 silently unsound · 12 ICE · 108 sound · 628 rejected at compile · 26 loud at runtime.**
The `###Relational` DSL accepts 21 spellings mapping to 18 model types and REJECTS 64 common SQL
spellings — `BOOLEAN` among them, and there is no `TIME` spelling at all, so `StrictTime` and
`LatestDate` cannot be mapped to any column type.
5. **My own V24 as first filed was WRONG.** I wrote that
   `[10]->map({x| let y=$x; [7]->map(z|$y)->toOne();})` vs `...map(y|$y)...` were alpha-equivalent
   and differed in answer. They are not alpha-equivalent — renaming `z` to `y` over a FREE `y` is a
   capturing rename, and the second program's answer (7) is correct lexical shadowing. Confirmed by
   running the let-free form `|[10]->map({y| [7]->map(y|$y)->toOne();})`, which also gives 7 with no
   inliner involved. The underlying capture defect IS real; V24 above now carries the valid repro
   (differing only in the inner binder name, with `$y` free in both).
