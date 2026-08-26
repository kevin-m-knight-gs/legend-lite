# V1-falsifier — adjudication of every filed UNSOUND / CRASH claim

**Method.** Every claim below was re-derived from scratch in `/tmp/claude-0/.../scratchpad/V1`
with my OWN fixtures (`m*.pure`, `d*.sql`, `Batch.java`, `K.java`, `T.java`, `Mult.java`,
`Sub.java`, `Sq.java`). No auditor's `/tmp/aNN/` file was reused. Nothing under
`/home/user/legend-lite` was modified. All output below is pasted from runs I made.

Harness: `Batch.java` (batch pipeline probe: G type / SQL / exec columns / exec rows in one JVM),
run via `/home/user/probe/jrun.sh`.

Verdicts used: CONFIRMED · CONFIRMED-BUT-OVERSTATED · NOT-REPRODUCED · MISATTRIBUTED ·
BY-DESIGN · DUPLICATE.

---

## 0. The adjudication the orchestrator asked for: `InferenceKernel` ~line 580

**Ruling: the "correction" corrects a claim nobody filed. The CODE claim is right; the
ATTRIBUTION is false.**

`CONFIRMED.md` V14 says *"(NOTE: `InferenceKernel.java:576-580` DOES handle it — an earlier
auditor's claim there is WRONG …)"*, and the new `# CORRECTIONS` §1 says the claim was
*"filed by A01/A03"*.

I grepped both files exhaustively for every reference to `InferenceKernel` in the 550-600 range:

```
A01-type-algebra.md:579:`lowering/CastPolicy.java:219`, `compiler/spec/InferenceKernel.java:577-580`.
A03-subtyping.md:412:| 3 | `compiler/spec/InferenceKernel.java:565` `conformsUpValueLattice` | ... omits LATEST_DATE
A03-subtyping.md:417:Quoted, `InferenceKernel.java:565-574`:
```

`A01:579` is inside the list headed *"Sites that **do** carry the extra
`instanceof Type.PrecisionDecimal` arm"*. **A01 states the opposite of the claim it is accused
of.** A03's only nearby citations are `conformsUpValueLattice:565` (a LATEST_DATE claim, not a
PrecisionDecimal claim) and `valueLub:554`. Neither auditor filed the refuted claim.

The code fact is correct and I confirm it (`InferenceKernel.java:576-581`, read in full):

```java
576    private static boolean isNumeric(Type t) {
577        return t instanceof Type.PrecisionDecimal
578                || (t instanceof Type.Primitive p
579                        && (p == Type.Primitive.NUMBER || p == Type.Primitive.INTEGER
580                                || p == Type.Primitive.FLOAT || p == Type.Primitive.DECIMAL));
581    }
```

**Verdicts:** code claim CONFIRMED; `CONFIRMED.md` V14 parenthetical + CORRECTIONS §1
attribution **NOT-REPRODUCED** (no such auditor claim exists — remove the correction, it
defames a finding that is correct).

**Separately, V14's census is materially incomplete** — see §3.

---

## 1. Adjudication table

### `/home/user/audit/CONFIRMED.md` (orchestrator's own list)

| source | finding | verdict | evidence |
|---|---|---|---|
| CONFIRMED.md | V1 `==` on optional operand → NULL under `Boolean[1]` | **CONFIRMED** | my `m.pure` (`qty: Integer[0..1]`): `[G] Relation<(n:String[1], eq:Boolean[1], gt:Boolean[1])>`; `[ROW] String(bob) \| null \| Boolean(false)` |
| CONFIRMED.md | V2 `cast(@Any)->cast(@T)` erases the check | **CONFIRMED** | `[G] Relation<(x:Integer[1])>`, `[SQL] SELECT t0.NAME AS x`, `[ROW] String(bob)` |
| CONFIRMED.md | V3 `cast` CONVERTS instead of asserting | **CONFIRMED** | `2.7->cast(@Integer)` → `SELECT CAST(CAST(2.7 AS DOUBLE) AS BIGINT)` → `Long(3)`; `2.5` → `Long(2)`; `'x'->cast(@Integer)` → raw `SQLException: Could not convert string 'x' to INT64` |
| CONFIRMED.md | V4 `Decimal(38,38)` literal decodes as Double | **CONFIRMED** | `[G] Decimal(38,38)` → `[ROW] Double(3.141592653589793)`; `1.5D` → `BigDecimal(1.5)` |
| CONFIRMED.md | V5 `Multiplicity.product` overflows raw int | **CONFIRMED** | own `Mult.java` reproduced all 5 lines verbatim; own `m2.pure` (`[0..65536]` chain) → `[G] Relation<(x:String[0])>`; `[50000]` chain → `IllegalArgumentException: lower must be >= 0, got -1794967296` |
| CONFIRMED.md | V6 StrictTime literal has no lowering | **CONFIRMED** | `\|%10:30:45` → `NotImplementedException: scalar lowering not yet implemented for TypedCTime` |
| CONFIRMED.md | V7 `Long.MIN_VALUE` decodes as BigDecimal | **CONFIRMED** | `[SQL] SELECT CAST(-9223372036854775808 AS DECIMAL(19,0))` → `BigDecimal(-9223372036854775808)` |
| CONFIRMED.md | V8 `native-catalog.txt` is a SELF-golden | **CONFIRMED** | `NativeFunctionTest:52-66` reads the resource and compares to `Pure.all().map(renderCanonical)`; its own comment says "regenerate the resource". No real legend-pure stdlib `.pure` in the repo (344 `.pure` files, all fixtures/corpus) |
| CONFIRMED.md | V9 three compile seams agree on root type | **CONFIRMED (limited)** | every probe I ran printed identical `[G] type` and `[PLAN] rootType`; I did not re-run all 10 of the orchestrator's queries |
| CONFIRMED.md | V10 `extends …type::Nil` ⇒ subtype of everything | **CONFIRMED** | own `m3.pure`+`Sub.java`: `isSubtype(fal::Evil, fal::Good)=true`, `(…,Integer)=true`, `(…,no::such::Type)=true`, `isSubtype(fal::Good, fal::Evil)=false` |
| CONFIRMED.md | V11 cross-kind `==` compiles and is true | **CONFIRMED** | `\|1 == '1'` → `SELECT 1 = '1'` → `Boolean(true)`; `\|true == 1` → `Boolean(true)` |
| CONFIRMED.md | V12 collection element LUB order-dependent | **CONFIRMED** | `\|[1.25d, 1.5d]` → `Decimal(38,1)[2]` / `BigDecimal(1.25)`; `\|[1.5d, 1.25d]` → `Decimal(38,2)[2]` |
| CONFIRMED.md | V13 `PrecisionDecimal` arithmetic is DEAD CODE | **CONFIRMED-BUT-OVERSTATED** | "ZERO callers" is false — `PrecisionDecimalArithmeticTest.java` calls `plus/minus/times/dividedBy` (10 call sites). Zero **production** callers is correct (A01 said this precisely). `DEFAULT_DECIMAL` and `adjust` genuinely have zero external refs |
| CONFIRMED.md | V14 `== Primitive.DECIMAL` site census | **CONFIRMED-BUT-OVERSTATED** | the "do NOT" list of 4 is **missing 3** — see §3. The InferenceKernel parenthetical is false — see §0. The `format('%r', …)` consequence reproduces exactly |
| CONFIRMED.md | V15 `project(~[])` → `SELECT *` → ICE | **CONFIRMED** | `[G] Relation<()>`, `[SQL] SELECT * FROM T_ITEM AS t0`, `[EXEC-ERR] IllegalStateException: result has 5 columns but the typed schema has 0` |
| CONFIRMED.md | V16 ROW-typed extend column | **CONFIRMED** | `[G] …b:(a:String[1])[1]`; `[COL] a:String[1] b_a:String[1]` |
| CONFIRMED.md | V17 many-valued cell clamped to `[0..1]` then ICE | **CONFIRMED** | `[G] Relation<(b:Integer[0..1])>`, `[SQL] SELECT [1, 2, 3] AS b`, ICE `a many-valued cell reached a scalar TDS slot ('b')` |
| CONFIRMED.md | V18 generic user fn return type never checked | **CONFIRMED** | own `m4.pure`: `[G] …b:Integer[1]`, `[SQL] … 'hello' AS b`, `[ROW] String(a) \| String(hello)` |
| CONFIRMED.md | V19 `sum()` violates `Integer[1]` both ways | **CONFIRMED** | empty group → `s:Integer[1]` / `[ROW] null`; two `Long.MAX_VALUE` rows → `[ROW] BigInteger(18446744073709551614)` |
| CONFIRMED.md | V20 `->toOne()` deleted in object space | **CONFIRMED** | `fal::Item.all()->toOne()->project(...)` → `SELECT t0.NAME AS a` (no guard), 2 rows out; `\|[]->toOne()` DOES emit `SELECT error('Cannot cast a collection of size 0 to multiplicity [1]')` |
| CONFIRMED.md | V23 user model redefines a platform native | **CONFIRMED** | with hijack `[G] String[1]` / `SELECT 'HIJACKED'`; control `[G] Integer[0..1]` / `list_extract([1,2,3],1)` |
| CONFIRMED.md | V24 let-inlining is capture-unsafe | **CONFIRMED** | `map(z\|$y)` → `list_transform([7], z -> x)` → `Integer(10)`; `map(y\|$y)` → `list_transform([7], y -> y)` → `Integer(7)` |
| CONFIRMED.md | V25 `first(set,count)` drops count | **CONFIRMED** | `\|[1,2,3]->first(2)` → `[list_extract([1,2,3], 1)]` → one row `Integer(1)`; `take(2)` → `array_slice(...,1,2)` → two rows |
| CONFIRMED.md | CORRECTIONS §1 (InferenceKernel) | **NOT-REPRODUCED** | no auditor filed the refuted claim; A01:579 says the OPPOSITE. See §0 |
| CONFIRMED.md | CORRECTIONS §2 (`PlanText:557`) | **CONFIRMED** | independently: `:557` is `if (t == …Primitive.DECIMAL) return "Decimal";`. A real `planToString(executionPlan(…))` over a `DECIMAL(10,2)` store column renders `type = TDS[(a, Decimal, DECIMAL(10,2), "")]` — no throw |

### A01-type-algebra

| finding | verdict | evidence |
|---|---|---|
| LUB over two `PrecisionDecimal`s returns the 2nd operand | **CONFIRMED** (DUPLICATE of V12 / A03) | own `K.java`: `lub(Decimal(20,5),Decimal(10,2))=Decimal(10,2)`; `lub(Decimal(10,2),Decimal(20,5))=Decimal(20,5)`; `lub(Decimal(10,2),Decimal(5,0))=Decimal(5,0)`; `lub(Decimal,Decimal(10,2))=Decimal(10,2)` |
| `pivotColumnType` `lastIndexOf` — suffix template steals | **CONFIRMED** | own TDS pivot: `[COL] … '2011__|__b':Integer '2011__|__a__|__b':Integer` but `[ROW] … BigInteger(2000) \| Double(2.25)` — the Double sits under an `Integer` column |
| `PrecisionDecimal` arithmetic is dead code | **CONFIRMED** | repo-wide grep (target excluded): `plus/minus/times/dividedBy` → only `PrecisionDecimalArithmeticTest`; `adjust` → 0 refs; `DEFAULT_DECIMAL` → 1 (its own declaration) |
| `#TDS` `:Decimal` column typed `Decimal(38,0)` | **CONFIRMED** (DUPLICATE of A14 #12) | `[G] Relation<(x:Decimal(38,0)[1], n:String[1])>`, `[ROW] BigDecimal(1.25)` |
| decimal literal > 38 integer digits → `Double` | **CONFIRMED** (DUPLICATE of V4 / A05 #1 / A17 F1) | `\|12345678901234567890123456789012345678901234567890.5d` → `[G] Decimal(38,1)` → `[ROW] Double(1.2345678901234567E49)` |
| `DECIMAL(2,5)` throws IAE **out of Phase F** | **CONFIRMED-BUT-OVERSTATED** | the IAE and the cited `StoreCompiler.java:192` are right, but the **phase is wrong**: `Compiler.compileModel` prints `compileModel OK`. The throw happens lazily in Phase G — see §2 for the stack. Bonus claim (MAX_PRECISION not enforced) CONFIRMED: `DECIMAL(100,50)`, `DECIMAL(2000000000,0)`, `NUMERIC(40,39)` all accepted |
| pivot agg name containing `__|__` → `IllegalStateException` | **CONFIRMED** (DUPLICATE of A14 #6) | `IllegalStateException: pivot column '2011__|__a__|__b' matches no aggregate template [a__|__b]` |
| `extend` over late-bound raw-SQL grid: schema loss + IOOBE | **CONFIRMED** | bare grid `[COL] A:Any[0..1] B:Any[0..1]`; `->extend(~[z:r\|1])` → `[G] Relation<(z:Integer[1])>` then `IndexOutOfBoundsException: Index 1 out of bounds for length 1`; `->select(~[A])` → `unknown column 'A' in ()` |
| `PlanText.pureTypeName` — "every plan-text over a DECIMAL store column throws" | **NOT-REPRODUCED + MISATTRIBUTED** | see §2. Real plan-text over `DECIMAL(10,2)` renders fine; the method named is `pureName` (`:534`), not `pureTypeName` (`:315`) |
| `TypeClassifier.classify` accepts any unknown generic head | **CONFIRMED** | `Varchar(200)` and `totally::made::Up(9)` compile; bare `Varchar` is `ModelException: Unknown type`; the fns are then uncallable (`expected Varchar<>, got String`) |
| schema algebra silently treats non-`RelationType` right as empty | **CONFIRMED** | `InferenceKernel.java:806-810` throws for the left; `:814` / `:831` are bare `if (right instanceof …)` with no else |

### A02-multiplicity

| finding | verdict | evidence |
|---|---|---|
| `Multiplicity.product` overflow | **CONFIRMED** (DUPLICATE of V5) | as V5 above |
| `[1]` property over NULLable column delivers null on every lane | **CONFIRMED** | own `m5.pure`: tabular `pa:Integer[1]` / `[ROW] String(Jane) \| null`; scalar `[G] Integer[1]` / `[ROW] null`; collection lane ICEs `NULL cell reached COLLECTION egress` |
| user `->toOne()` over empty `[0..1]` returns null | **CONFIRMED** | `project(~[a:p\|$p.nick->toOne()])` → `a:String[1]` / `[ROW] null` twice; scalar lane `[G] String[1]` / `[ROW] null` |
| declared UPPER bound never enforced at any egress | **CONFIRMED** | `ads: Ad[2]` → `[G] String[2]` with 3 rows; `->size()` = `Long(3)`. Grep confirms no `upper()` comparison in `exec/` at all |
| `[1]` navigation across a join returns 0 or N rows | **CONFIRMED** | `Ad.all()->size()` = `Long(4)`; `Ad.all()->map(a\|$a.owner)->size()` = `Long(7)` under `Person[1]`; LEFT OUTER JOIN emitted |
| `sum()`/`average()` over empty stamped `[1]`, return null | **CONFIRMED** (DUPLICATE of V19 / A07 #1 / A14 #2) | `[G] Integer mult=[1]` / `[ROW] null`; `Float mult=[1]` / `null` |
| `[2..1]` guarded only on class properties | **CONFIRMED** | class prop → clean `ModelException`; association end, fn param `[2..1]`, fn return `[3..2]` → raw `java.lang.IllegalArgumentException: upper (1) must be >= lower (2)` |
| unbound multiplicity VARIABLE accepted, reaches exec | **CONFIRMED** | `String[m]` compiles; tabular `[COL] a:String[m]` reaches the exec boundary; scalar lane `IllegalStateException: unresolved multiplicity variable reached lowering: Var[name=m]`; `if()` → `cannot union multiplicities [m] and [1]` |
| `if(true, \|[], \|[])` trips a stamp-invariant assertion | **CONFIRMED** | `[G] Nil mult=[0]` then `IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED … ONE-STAMP/LIST-SHAPE mult=[0..0] sql=Case node=TypedIf` |

### A03-subtyping

| finding | verdict | evidence |
|---|---|---|
| property override with incompatible type accepted | **CONFIRMED** (DUPLICATE of A07 #4) | `[G] String[1]`, `[SQL] SELECT 1 AS value`, `[ROW] Integer(1)` |
| `extends …type::Nil` ⇒ subtype of everything | **CONFIRMED** (DUPLICATE of V10) | as V10 |
| `commonSupertype(PD,PD)` returns the 2nd operand | **CONFIRMED** (DUPLICATE of A01 #1) | own `K.java` §6 |
| `match` dispatch ignores generic ARGUMENTS | **CONFIRMED** | `list([1,2])->match([List<String>\|'PICKED-STRING', List<Integer>\|'PICKED-INT'])` → `SELECT 'PICKED-STRING'`; reversed source order flips the answer |
| no user class / enum is a subtype of `Any` | **CONFIRMED** | `isSubtype(fal::Good, meta::pure::metamodel::type::Any) = false` (own probe) |
| `instanceOf` ICEs on every non-identical pair | **CONFIRMED** | `Person->instanceOf(Address)`, `instanceOf(1,Integer)`, `instanceOf(1,Number)` all `NotImplementedException: instanceOf undecidable statically`; identical pair → `SELECT TRUE` |
| `cast(@T)` performs no relatedness check | **CONFIRMED** | `Month.January->cast(@String)` → `SELECT 'January'`; `1->cast(@String)` → `CAST(1 AS VARCHAR)`; `1->cast(@Boolean)` IS guarded (`error('Cast exception: …')`) |
| cross-kind `equal` returns TRUE | **CONFIRMED** (DUPLICATE of V11) | `Month.January == 'January'` → `SELECT 'January' = 'January'` → `Boolean(true)`; user enum `fal::Color.RED == 'RED'` → `true` |
| unknown/typo'd superclass FQN silently accepted | **CONFIRMED** | `Class fal::Bad extends no::such::Base` compiles; `^fal::Bad(q='k').q` → `String(k)` |

### A04-inference-kernel

| finding | verdict | evidence |
|---|---|---|
| generic user fn return type never checked | **CONFIRMED** (DUPLICATE of V18) | as V18 |
| `compatibleRebind` "Any escape hatch" keeps the narrow binding | **CONFIRMED** | `['a','b']->concatenate([1,'x'])` → `[G] String[*]` → rows `String(a) \| String(b) \| String(1) \| String('x')` (note the LEAKED quotes); `[1,2]->concatenate([1,'a'])` → `[G] Integer[*]` → `SQLException: Cannot concatenate lists of types INTEGER[] and VARCHAR[]`. Kernel probe: `T` bound to a row, `unify(T, Any)` keeps the row; `T` bound to `Any`, `unify(T, row)` keeps `Any` |
| unbounded type variables (`abs<T>`) accept String/Boolean/Date | **CONFIRMED** | `'abc'->abs()` → `[G] String[1]` / `SELECT abs('abc')` → DuckDB binder error; `true->abs()` → `Boolean[1]`; `%2020-01-01->abs()` → `StrictDate[1]` |
| no OCCURS CHECK; `resolve` is ONE-LEVEL | **CONFIRMED** | own `K.java`: `unify(T, Relation<T>)` accepted; `hasFreeTypeVars(Relation<T>) = false`; `T:=X, X:=Integer` ⇒ `resolve(T) = TypeVar[name=X]` while `hasFreeTypeVars(T)=false`; `A:=B, B:=A` accepted |
| variable capture — no freshening | **CONFIRMED** | own `K.java` §4: formal `{T[1]->V[1]}` vs actual `{V[1]->Integer[1]}` ⇒ `T=TypeVar[V]`, `V=INTEGER`, `resolve(T)=TypeVar[V]`, `hasFreeTypeVars(T)=false` |
| raw `TypeVar` escapes to lowering | **CONFIRMED** | own `m17.pure`: `fal::wrap(1)` / `fal::pairUp(1,2)` → `IllegalStateException: unresolved type variable T reached the lowering boundary`; `fal::id(1)` / `fal::first2(1,2)` survive |

### A05-typer-core

| finding | verdict | evidence |
|---|---|---|
| 1. decimal literal precision hardcoded to 38, never checked | **CONFIRMED** | `\|12345678901234567890123456789012345678901234567890.5d` → `[G] Decimal(38,1)` → `Double(1.2345678901234567E49)`; `\|1.23456789012345678901234567890123456789012345d` → `Decimal(38,38)` → `Double(1.2345678901234567)` |
| 2. Integer literals beyond 64 bits stamped `Integer[1]` | **CONFIRMED** | `\|99999999999999999999999999` → `[G] Integer[1]` → `BigInteger(…)`; `\|9223372036854775808` → `BigInteger` |
| 3. the "explicit D-suffix keeps the loud reject" guard is unreachable | **CONFIRMED** | `SpecParser.parseDecimal:845-852` strips the `d`/`D` before storing `written`; the other 3 `CDecimal` producers copy or null it. All four spellings (`…d`, `…D`, bare, 50-digit) yield the identical rounded `0.12345678901234567890123456789012345679` |
| 4. derived/qualified property on a `[0..1]` receiver stamped `[1]` | **CONFIRMED** | own `m18.pure`: `[G] …plainCity:String[0..1], derivedTag:String[1], derivedPlain:String[1]`; `[ROW] String(no-addr) \| null \| String(CAT-) \| null` — a `String[1]` cell is null and `'CAT-'` is manufactured |
| 5. `tdsRowCellIndexRead` truncates the index to `int` | **CONFIRMED** | `values->at(4294967296)` → column `a`; `at(4294967297)` → column `b`; `at(18446744073709551617)` → column `b`; `at(4294967298)` → `IllegalStateException: … offset 2 … size 2` (reports the WRAPPED offset) |
| 6. `rows.values->at(k)` row-major stamps `[1]` for a missing row | **CONFIRMED** | `$t.rows.values->at(99)` → `[G] String[1]`, `SELECT … LIMIT 1 OFFSET 49`, `[ROW] null`. Ordinary `[1,2]->at(5)` DOES emit the runtime guard and fails loudly |
| 7. `Any` escape hatches turn `cast` into a no-op | **CONFIRMED** (DUPLICATE of V2) | as V2 |
| 8. raw `IllegalStateException` from `tdsRowCellIndexRead` | **CONFIRMED** | `values->at(99)` on a 2-col row → `[G-ERR] java.lang.IllegalStateException` at Phase G |

### A07-pure-registry

| finding | verdict | evidence |
|---|---|---|
| 21 signatures declare `[1]` but return NOTHING on empty input | **CONFIRMED (exhaustive)** | I read every signature string in `Pure.java` and ran all 16 distinct names over an empty extent. All 16 → `mult=[1]` + `[ROW] null`: sum, average, median, mode, variance, varianceSample, variancePopulation, stdDev, stdDevSample, stdDevPopulation, mean, percentile(0.5), at(0), minus, plus, times. Controls: `max`/`min` → `mult=[0..1]`; `size` → `Long(0)` |
| `collection::at()` bounds guard defeated by NULL propagation | **CONFIRMED** | `…->at(0)` on the empty extent → `[G] Integer[1]` / `[ROW] null`, no error |
| `[]` types `plus`/`minus`/`times` as `Nil[1]` | **CONFIRMED** | `\|[]->times()` → `[G] Nil mult=[1]`, `SELECT list_aggregate(NULL, 'product')`, `[COL] value:Nil`, `[ROW] null` |
| subclass may override a property with a DISJOINT type | **CONFIRMED** | own `m19.pure`: `Sup.code` → `String(007)`; `Sub.code` → `SELECT CAST(t0.CODE AS BIGINT)` → `Long(7)`. Same DB row, two answers |
| subclass may NARROW `[0..1]` → `[1]` | **CONFIRMED** | `Sup.note` → `c:String[0..1]`; `Sub.note` → `c:String[1]` with `[ROW] null` |
| class may `extends` a PRIMITIVE or an ENUM | **CONFIRMED** | `Class fal::NumLike extends …type::Integer` compiles; `isSubtype(NumLike, Number)=true`, `(NumLike, Integer)=true`; `Class extends <Enum>` also accepted |
| `[]` makes 27 native overload sets ambiguous | **CONFIRMED-BUT-OVERSTATED** | `\|[]->sum()` and `\|[]->max()` → `TypeInferenceException: ambiguous overload … 3 candidates tie` ✔. But `->sort([])` is **not** an ambiguity — it is a plain argument mismatch: `expected {T[1], T[1] -> Integer[1]}, got …Nil`. Two different diagnoses filed as one |

### A08-sqltyping

| finding | verdict | evidence |
|---|---|---|
| Pure `String[1]` over an INTEGER column delivers `java.lang.Integer` | **CONFIRMED** | own `m21.pure`: `[G] Relation<(q:Float[1], p:Decimal[1], t:String[1])>`, `[ROW] Integer(3) \| Integer(100) \| Integer(7)`. With any op applied: `upper(CAST(t0.TAG AS VARCHAR))` → `String(7)` |
| `sum()` over a tolerated read: label DOUBLE, value BigInteger | **CONFIRMED** | `[G] Relation<(sq:Float[1], sp:Number[1])>`, `[ROW] BigInteger(7) \| BigInteger(300)` — a `Float[1]` cell holding a `BigInteger` |
| `rem()` over decimals: stored fact `Decimal(38,s)`, wire DOUBLE | **CONFIRMED-BUT-OVERSTATED** | reproduces as an MIR-label/wire divergence, but the **Pure-visible** type is `Number[1]` and the value is `Double(0.75)` — a `Double` IS a `Number`, so this is not a Pure-level unsoundness. `#>{DB.T_R}#->extend(~[v:r\|$r.A->rem($r.B)])` → `v:Number[1]` / `Double(0.75)` |
| `[1]` property over a NULLABLE column delivers null | **CONFIRMED** (DUPLICATE of A02 #2) | as A02 |
| `sum()` past 64 bits stays Pure `Integer` | **CONFIRMED** (DUPLICATE of V19) | as V19 |
| `Byte[1]` over `VARBINARY`/`BLOB`: raw `IllegalStateException` | **CONFIRMED** | `[G] Relation<(b:Byte[1])>` then `IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary` |
| store column `DECIMAL(5,10)` throws IAE from a record ctor | **CONFIRMED** (DUPLICATE of A01) | `NUMERIC(3,7)` and `DECIMAL(2,5)` both `IllegalArgumentException: scale must be in [0, precision]` |
| `PrecisionDecimal(p>38)` never clamped → SQL the backend rejects | **CONFIRMED-BUT-OVERSTATED** | half A CONFIRMED: `DECIMAL(50,10)` in the model gives `[G] Relation<(W:Decimal(50,10)[1])>` over a physical `DECIMAL(30,10)`; `AnsiSqlRenderer:924` renders `"DECIMAL(" + p + ", " + s + ")"` with no clamp. Half B (backend rejection) was only shown by a **direct renderer call**; I could not drive a `DECIMAL(50,10)` cast out of any Pure query I tried |

### A10-lowering-discipline

| finding | verdict | evidence |
|---|---|---|
| `==`/`eq` yields SQL NULL under `Boolean[1]` | **CONFIRMED** (DUPLICATE of V1) | own fixture: `SELECT t0.QTY = 10 AS eq, (t0.QTY IS NOT NULL AND t0.QTY > 5) AS gt, (t0.QTY IS DISTINCT FROM 10) AS ne` → `[ROW] null \| Boolean(false) \| Boolean(true)` — three null disciplines under one stamp |
| `Decimal(38,38)` literal executes as DOUBLE | **CONFIRMED** (DUPLICATE of V4) | as V4 |
| StrictTime literal has no lowering | **CONFIRMED** (DUPLICATE of V6) | as V6 |
| *(A10 "VERIFIED SOUND": filtered-empty `->sum() = NULL (`[0..1]`)`)* | **FALSE — see §4** | it is `Integer[1]`, not `[0..1]`. A10 records its own contradiction of V19/A02/A07/A14 as a soundness result |

### A13-checkers-projection

| finding | verdict | evidence |
|---|---|---|
| `concatenate` over a collection literal re-orders columns / String in an Integer column | **CONFIRMED** | 2-arg form correctly refuses; the 3-element list form silently accepts. Transposition: `[ROW] Integer(30) \| Integer(40)` from a source row `b=30, a=40`. Mixed types (I had to reconstruct the fixture — `(b,a)` legs with types following names): `[G] Relation<(a:Integer[1], b:String[1])>` / `[ROW] String(1)…String(z) \| String(3)` — every `a` cell is a `java.lang.String` |
| schema-SUBSET element silently widened → invalid SQL | **CONFIRMED** | `[G] Relation<(id:Integer[1], name:String[1])>` then `SQLException: Set operations can only apply to expressions with the same number of result columns` |
| `project`/`extend` accept a ROW-typed column | **CONFIRMED** (DUPLICATE of V16) | as V16 |
| `duplicate column 'b_a' in relation type` at execution | **CONFIRMED** | `…->extend(~[b:r\|$r])->extend(~[b_a:r\|1])` → `IllegalArgumentException: duplicate column 'b_a' in relation type` |
| `rename` over a struct-valued column | **CONFIRMED** | `IllegalStateException: rename source column 'b' cannot be resolved after isolation` |
| relation-valued projection column | **CONFIRMED** | `IllegalStateException: no SQL type for generic Relation<(x:Integer[1])> at the lowering boundary` (both `project` and `extend`) |
| `project(~[])` accepted → `SELECT *` → ICE | **CONFIRMED** (DUPLICATE of V15) | as V15 |
| `clampTdsCells` `[0..1]` vs `extend` not clamping | **CONFIRMED** | `project(~[b:p\|[1,2,3]])` → `b:Integer[0..1]`; `extend(~[b:r\|[1,2,3]])` → `b:Integer[3]`. Both ICE at egress |
| all three `Frames.java` throw sites are raw `IllegalStateException` | **CONFIRMED** | 4/4 of my window-frame spellings → `IllegalStateException: window frame bound must be a numeric literal or unbounded(), got TypedNativeCall / TypedVariable`; `$f` → `window frame expects rows()/range(), got TypedVariable` |
| relation `sort` claims null-largest but emits no ASC null clause | **CONFIRMED (and understated)** | `sort(asc(~c))` → `ORDER BY t0.NICK` (no clause). Direct JDBC on that exact SQL: DuckDB `[Johnny][Zed][null]`, **SQLite `[null][Johnny][Zed]`, H2 `[null][Johnny][Zed]`**. A13's table claims H2 puts nulls LAST — that row is wrong; the defect is broader than filed |
| `extend` over a pivot mints two same-name columns | **CONFIRMED** | `[COL] city:String '2020__|__total':Integer '2021__|__total':Integer '2020__|__total':Integer` — 4 columns, 2 identically named |
| `concatenate` on a pivot result emits invalid SQL | **CONFIRMED** | `[G] Relation<(city:String[1])>` then `SQLException: Set operations can only apply to expressions with the same number of result columns` |
| `flatten` validates only the column NAME | **CONFIRMED** | `#TDS a,b / 1,x #->flatten(~b)` → `[G] …b:Variant[1]`, `UNNEST(_tds0.b)` → `SQLException: UNNEST() can only be applied to lists, structs and NULL, not VARCHAR` |
| `slice(from,to)` with `from > to` renders `LIMIT -2` | **CONFIRMED** | `SELECT … LIMIT -2 OFFSET 3` → `SQLException: LIMIT/OFFSET cannot be negative` |
| empty column name renders a zero-length delimited identifier | **CONFIRMED** | `SELECT t0.NAME AS ""` → `SQLException: Parser Error: zero-length delimited identifier` |

### A14-checkers-aggregate

| finding | verdict | evidence |
|---|---|---|
| `sum(Integer)`/`plus()` declares `Integer[1]`, DuckDB returns HUGEINT | **CONFIRMED** (DUPLICATE of V19 / A08 #5) | as V19 |
| 18 aggregates declare `[1]` and return NULL over an empty group | **CONFIRMED** | I re-ran the 16 distinct reductions (see A07 #1) plus the per-group cases: single-row group `stdDev()` → `[G] Relation<(out:Number[1])>` / `[ROW] null` |
| `y\|$y->plus()` over Boolean/String/Date | **CONFIRMED** | `BIT` column → `[G] …out:Boolean[1]` / `[ROW] BigInteger(1)`; VARCHAR → `SQLException: No function matches 'sum(VARCHAR)'`; DATE → `'sum(DATE)'`. (Note: `BOOLEAN` is not an accepted DSL spelling — A14's fixture text says `B BOOLEAN`; `BIT` is the spelling that parses) |
| `median(Decimal)` declares `Float[1]`, returns truncated `BigDecimal` | **CONFIRMED** | `[G] …out:Float[1]` / `[ROW] BigDecimal(1.87)` for the true median `1.875`. `min()` → `Number[0..1]` (precision erased); `first()` over the same column → `Decimal(10,2)[0..1]` |
| `y\|$y->plus()` over `Decimal(p,s)` declares `Decimal(p,s)` | **CONFIRMED** | 3×`99999999.99` → `[G] …out:Decimal(10,2)[1]` / `[ROW] BigDecimal(299999999.97)` (precision 11 > 10) |
| pivot column `__|__` crash | **CONFIRMED** (DUPLICATE of A01) | as A01 |
| legacy TDS `groupBy(['k'], agg('m', x\|$x, y\|$y->max()))` → `MAX(*)` | **CONFIRMED** | `[G] Relation<(grp:Integer[1], m:(grp:Integer[1], val:Integer[1])[0..1])>`, `SELECT _tds0.grp, MAX(*) AS m`, `SQLException: No function matches … 'max()'`. `count()` correctly rewrites to `COUNT(*)` |
| collection natives type-check in reduce position then ICE | **CONFIRMED** | `->last()` → `[G] o:Integer[0..1]` then `IllegalStateException: no aggregate lowering registered for resolved overload '…collection::last'`; `makeString(',')` likewise |
| `fold` with an element-first non-commutative body | **CONFIRMED** | `{e,acc\|$e->toString() + $acc}` → `IllegalStateException: fold body is not decomposable and the accumulator is scalar`; the mirror `{e,acc\|$acc + $e->toString()}` → `String(123)` |
| `fold` over `[]` with a non-String init | **CONFIRMED** | `\|[]->fold({a,b\|$a + $b}, 0)` → `CAST(NULL AS VARCHAR[])` → `SQLException: The initial value type must be the same as the list child type` |
| TDS data row with MORE cells than the header | **CONFIRMED** | `#TDS a,b / 1,2,3 #` → `[G]` OK, then `java.lang.IndexOutOfBoundsException: Index: 2 Size: 2` |
| TDS `:Decimal` always `Decimal(38,0)` | **CONFIRMED** (DUPLICATE of A01) | as A01 |
| pivot rows whose KEY is NULL are silently dropped | **CONFIRMED** | rows `(2011,'NYC',100)` + `(2011,NULL,50)` → `[COL] YR 'NYC__|__total'`, `[ROW] Integer(2011) \| BigInteger(100)`. The 50 is gone; no column, no row, no error |
| static pivot pins columns that do not exist | **CONFIRMED** | `pivot(~CITY, ['NYC','ZZZ'], …)` → `[COL] … 'ZZZ__|__total':Integer`, `[ROW] … \| null` |
| aggregates with no dialect spelling surface as raw JDBC errors | **NOT ADJUDICATED** | my batch harness is DuckDB-only; I did not re-derive the H2/SQLite matrix |

### A17-frontend-parse-types

| finding | verdict | evidence |
|---|---|---|
| F1 over-precision `Decimal` literals | **CONFIRMED** (DUPLICATE of V4 / A05 #1) | as A05 #1 |
| F2 explicit `d`-suffix truncation is unreachable dead code | **CONFIRMED** (DUPLICATE of A05 #3) | as A05 #3 |
| F3 unbound multiplicity variable escapes to the caller | **CONFIRMED** (DUPLICATE of A02 #8) | as A02 #8 |
| F4 `<Class>$prop$<name>` silently hijacks a derived property | **CONFIRMED** | own `m25.pure` vs `m25c.pure` control: control `SELECT 'DERIVED'` → `String(DERIVED)`; with `function fal::Item$prop$tag(...)` present `SELECT 'USER-WINS'` → `String(USER-WINS)`. No diagnostic |
| F5 a GENERIC head is never validated | **CONFIRMED** (DUPLICATE of A01's TypeClassifier finding) | as A01 |
| F7 `cast(@Relation<()>)` accepted → `SELECT *` → ICE | **CONFIRMED** | `[G] Relation<()>`, `[SQL] SELECT * FROM T_ITEM AS t0`, `IllegalStateException: result has 3 columns but the typed schema has 0` |
| F8 relation `cast` annotation is TRUSTED and lossy | **CONFIRMED** | `project(~[a:p\|$p.age / 4])->cast(@Relation<(a:Integer[1])>)` → `SELECT CAST(((1.0 * t0.AGE) / 4) AS BIGINT)` → `Long(8)` for 30/4 = 7.5. Over a String column → `SQLException: Could not convert string 'John' to INT64` |
| F9 duplicate column names → raw `IllegalArgumentException`, 3 sites | **CONFIRMED** | property, fn-return, and query-cast all `<<ICE>> java.lang.IllegalArgumentException: duplicate column 'a' in relation type` at `Type.java:533`. Control: `select(~[a,a])` → clean `SchemaInvariantException` |
| F10 float literal overflowing double → raw `NumberFormatException` | **CONFIRMED** | `\|1e309`, `\|-1e400`, `\|[1e400]` and a MODEL body `1e400` all `<<ICE>> NumberFormatException: Character I …` at `SpecParser.parseFloat(SpecParser.java:832)`. `\|1e-400` → OK `Decimal(38,38)` |
| F11 type-variable value overflow → raw `NumberFormatException` | **CONFIRMED** | `Varchar(99999999999999999999)` → `<<ICE>> NumberFormatException: For input string: "99999999999999999999"` at `TokenStreamCursor.parseTypeVariableValues(TokenStreamCursor.java:796)` |
| F12 `[5..2]` checked in 3, accepted in 3, ICE in 4 | **CONFIRMED** | clean `ModelException`: prop, derived prop. Silently **OK**: fn param, fn return, association end. `<<ICE>>`: relation col, fn-type param, fn-type result, query cast — all `IllegalArgumentException` at `Multiplicity$Bounded.<init>(Multiplicity.java:151)` |
| F13 `StackOverflowError` on deep type nesting | **CONFIRMED-BUT-OVERSTATED** | the SOE is real (`generic depth 5000 → <<ICE>> java.lang.StackOverflowError` at `TokenStreamCursor.parseQualifiedName:535`), but the quoted thresholds do NOT reproduce: **generic depth 1200 → OK**, **paren depth 800 → OK** here. The "~981 / ~919 / ~727" numbers are JVM-stack-size artefacts, not properties of the code |

---

## 2. Findings I believe are FALSE or materially overstated

### 2.1 A01 — "`PlanText.pureTypeName` has no `PrecisionDecimal` arm — every plan-text over a `DECIMAL` store column throws"  → **NOT-REPRODUCED + MISATTRIBUTED**

The **claimed consequence is false**. I drove a real, user-writable plan-text over a
`DECIMAL(10,2)` store column:

```
### |meta::pure::executionPlan::toString::planToString(
      meta::pure::executionPlan::executionPlan(
        |fal::Money.all()->project(~[a:p|$p.amt]), fal::M, fal::RT, []), [])
  [G] String mult=[1]
  [ROW] String(Relational
(
  type = TDS[(a, Decimal, DECIMAL(10,2), "")]
  resultColumns = [("a", DECIMAL(10,2))]
  sql = select "root".AMT as "a" from T_M as "root"
  connection = TestDatabaseConnection(type = "H2")
)
)
```

No throw. The mapped Pure property carries `Type.Primitive.DECIMAL` (handled at `:557`), not a
`PrecisionDecimal`. The relation-accessor route that *does* produce `PrecisionDecimal` cannot
reach `planToString` at all (`NotImplementedException: planToString: no getAll root`), and a
computed decimal-literal column hits a **different, earlier** guard:

```
### |…planToString(executionPlan(|fal::Money.all()->project(~[a:p|1.5d]), …), [])
  [EXEC-ERR] com.legend.error.NotImplementedException: plan: computed TDS column 'a' type spelling pending
```

MISATTRIBUTION: the method named in the title (`pureTypeName`, `PlanText.java:315`) is a
one-line delegate. The chain and the `throw` are in the *private* `pureName` at `:534-567`.
The bare code fact ("`pureName` has no `PrecisionDecimal` arm and throws at `:567`") is true and
is worth keeping — as a latent hole, not as "every plan-text over a DECIMAL store column
throws". The orchestrator's CORRECTIONS §2 reaches the same conclusion; I confirm it
independently.

### 2.2 A01 — "`DECIMAL(2,5)` … throws … **out of Phase F**"  → **CONFIRMED-BUT-OVERSTATED** (phase misattributed)

`Compiler.compileModel` on the same model **succeeds**:

```
compileModel OK
```

The IAE is raised lazily during Phase G, from a table *reference*:

```
THREW java.lang.IllegalArgumentException: scale must be in [0, precision], got scale=5, precision=2
   at com.legend.compiler.element.type.Type$PrecisionDecimal.<init>(Type.java:165)
   at com.legend.compiler.element.StoreCompiler.columnType(StoreCompiler.java:192)
   at com.legend.compiler.element.StoreCompiler.tableSchema(StoreCompiler.java:177)
   at com.legend.compiler.element.PureModelContext.resolveTableWithIncludes(PureModelContext.java:436)
   at com.legend.compiler.spec.TableReferenceChecker.check(TableReferenceChecker.java:73)
   at com.legend.compiler.spec.Typer.applyCore(Typer.java:1313)
   at com.legend.compiler.spec.SpecCompiler.typeExpression(SpecCompiler.java:174)
   at com.legend.Compiler.compileQuery(Compiler.java:725)
```

The cited `StoreCompiler.java:192` is correct; the phase is not. This matters: a model with a
bad DECIMAL passes model validation and only explodes when someone queries that table.

### 2.3 `CONFIRMED.md` V14 — the `== Primitive.DECIMAL` census is materially incomplete, and its parenthetical is false

V14 names **4** no-arm sites. There are **7**. I read every one of the 28 `Primitive.DECIMAL`
references in `core/src/main/java` and printed the surrounding lines:

no `PrecisionDecimal` arm (7): `plan/PlanText.java:557`, `lowering/Repr.java:51`,
`lowering/DateCtorRule.java:100`, `lowering/Scalars.java:3467`, `lowering/CastPolicy.java:144`,
**`lowering/CanonicalRenderSql.java:224`**, **`compiler/spec/Typer.java:1815`**
— the last two, plus `PlanText:557`, are missing from V14.

has the arm (14 incl. the ones V14 omits from its "correct" list):
`exec/CsvSeed.java:144`, **`AssertVerdicts.java:873`**, **`AssertVerdicts.java:891`**
(the arm is at `:895`), `lowering/Scalars.java:2926`, `lowering/LiteralSpelling.java:68`,
`:110`, `lowering/DecimalKindRules.java:84`, `lowering/MixedEncoding.java:134`, `:194`, `:435`,
**`lowering/Numerics.java:49`**, `lowering/CastPolicy.java:219`,
`compiler/spec/InferenceKernel.java:577`, `compiler/spec/FoldChecker.java:164`.

A01's census (which lists exactly these) is the accurate one. V14's parenthetical accusing an
auditor of a wrong `InferenceKernel` claim is false — see §0.

V14's stated observable consequence does reproduce exactly:
```
|format('%r', 1.25D)  ->  String('1.25')
|format('%r', 1.25)   ->  String('1.25')
```

### 2.4 `CONFIRMED.md` V13 — "ZERO callers" is literally false

```
$ grep -rn --include=*.java '\.plus(\|\.minus(\|\.times(\|\.dividedBy(' . | grep -v /target/
core/src/test/java/com/legend/compiler/element/type/PrecisionDecimalArithmeticTest.java:25  … (10 hits, all in that file)
```
The task brief says to grep the whole repo including tests before calling anything dead. There
IS a test. The substantive claim ("zero **production** callers; the decimal precision algebra is
never consulted by the compiler") holds, and A01 phrased it correctly ("zero production
callers … `core/src/test` excluded"). `DEFAULT_DECIMAL` and `adjust` do have zero external
references anywhere.

### 2.5 A08 — "`rem()` over decimals … [UNSOUND]"  → severity overstated

```
### #>{fst::DB.T_R}#->extend(~[v:r|$r.A->rem($r.B)])
  [G] Relation<(ID:Integer[1], A:Decimal(18,6)[1], B:Decimal(38,2)[1], v:Number[1])>
  [ROW] Integer(1) | BigDecimal(3.750000) | BigDecimal(3.00) | Double(0.75)
```
The **Pure** declared type of the result column is `Number[1]`, and a `java.lang.Double` is a
`Number`. Nothing at the Pure type surface is violated. The real defect is narrower: an internal
SQL-MIR `TypeFact` says `DECIMAL(38,6)` where the wire is `DOUBLE` — an internal-consistency /
label bug, not a top-prize unsoundness.

### 2.6 A08 — "`PrecisionDecimal(p>38)` … produces SQL the backend rejects"  → half unreachable

Half A confirmed (a `DECIMAL(50,10)` store declaration is trusted verbatim: `[G]
Relation<(W:Decimal(50,10)[1])>`, and `AnsiSqlRenderer:924` renders `DECIMAL(50, 10)` with no
clamp). Half B was demonstrated only by a *direct renderer call*. I tried four Pure spellings
that force a cast on that column (`plus()`, `if()`, `toString()`, list literal) and none emitted
a `DECIMAL(50,10)` cast. Backend rejection from user Pure text is not established.

### 2.7 A07 — "`[]` … `->sort([])` is a hard compile error [ambiguity]"  → mechanism wrong for one of the three

```
|[]->sum()          -> TypeInferenceException: ambiguous overload of '…math::sum': 3 candidates tie
|[]->max()          -> TypeInferenceException: ambiguous overload of '…math::max': 3 candidates tie
|[1,2]->sort([])    -> TypeInferenceException: in call to '…collection::sort', argument 2:
                       expected {T[1], T[1] -> Integer[1]}, got …type::Nil
```
`sort([])` is an ordinary argument-type mismatch, not an overload ambiguity. Filing it under the
ambiguity finding inflates the count.

### 2.8 A17 F13 — the StackOverflow *thresholds* do not reproduce

```
F13 generic depth 200    -> OK
F13 generic depth 900    -> OK
F13 generic depth 1200   -> OK          (A17 claims SOE at ~981)
F13 generic depth 5000   -> <<ICE>> java.lang.StackOverflowError
     at com.legend.parser.TokenStreamCursor.parseQualifiedName(TokenStreamCursor.java:535)
F13 paren depth 700      -> OK Integer
F13 paren depth 800      -> OK Integer  (A17 claims SOE at ~727)
```
The SOE is real; the three quoted numbers are properties of the auditor's JVM stack, not of the
parser, and should not be reported as measurements.

### 2.9 A13 — the `sort` null-order table's H2 row is wrong (finding is *understated*)

A13 reports H2 ordering nulls LAST (agreeing with the "null is largest" contract). Running the
exact SQL legend-lite emits (`SELECT … ORDER BY t0.NICK`, no NULLS clause) directly:

```
jdbc:sqlite::memory:  ORDER BY t0.NICK -> [null] [Johnny] [Zed]
jdbc:h2:mem:t1        ORDER BY t0.NICK -> [null] [Johnny] [Zed]
jdbc:duckdb:          ORDER BY t0.NICK -> [Johnny] [Zed] [null]
```
Two of the three backends invert the documented contract, not one.

### 2.10 A10 — a **false entry in its own VERIFIED SOUND section**

A10 lists as sound: *"filtered-empty `->sum()=NULL` (`[0..1]`)"*. It is not `[0..1]`:

```
### fal::Item.all()->filter(p|$p.qty > 200)->map(p|$p.qty)->sum()
  [G] Integer mult=[1]
  [ROW] null
```
This is exactly the V19 / A02 / A07 / A14 defect, recorded by A10 as a non-finding. A "verified
sound" claim that is false is worse than a missed finding: it launders a real defect.

### 2.11 A14 — fixture text that does not compile (`B BOOLEAN`)

A14's repro table is `T (GRP INTEGER, B BOOLEAN, …)`. The `###Relational` DSL has no `BOOLEAN`:
`ParseException: unsupported column datatype: BOOLEAN` (`DatabaseProtocolParser.java:391`; the
accepted spelling is `BIT`). The finding itself survives once the fixture is corrected — I
reproduced it with `BIT` — but the pasted fixture cannot have produced the pasted output as
written.

### 2.12 A02 vs A17 F12 on `[5..2]` association ends — NOT a contradiction

A02 says an association end `[2..1]` "throws a raw `IllegalArgumentException`"; A17 F12 says it
is "silently ACCEPTED". Both are right at different phases: `compileModel` accepts it
(`F12 assoc end [5..2] -> OK`), and the IAE fires only when a query navigates the end
(`fal::P.all()->project(~[a:p|$p.q.b])` → `IllegalArgumentException: upper (1) must be >= lower (2)`).
Adjudicated as complementary, not conflicting.

---

## 3. Duplicate clusters (one defect, many filings)

| canonical | also filed as |
|---|---|
| V4 — over-precision Decimal literal → `Double` | A01 #5, A05 #1, A10 #2, A17 F1 |
| V5 — `Multiplicity.product` int overflow | A02 #1 |
| V12 — `commonSupertype(PD,PD)` returns operand 2 | A01 #1, A03 #3 |
| V15 — `project(~[])` → `SELECT *` | A13 #7, (and A17 F7 is the same lowering hole via `cast(@Relation<()>)`) |
| V16 — ROW-typed extend column | A13 #3 |
| V17 — `clampTdsCells` | A13 #8 |
| V19 — `sum()` empty/overflow under `Integer[1]` | A02 #6, A07 #1, A08 #5, A10, A14 #1, A14 #2 |
| V2 — `cast` erasure via `Any` | A05 #7 |
| V11 — cross-kind `==` | A03 #8 |
| A01 pivot `__|__` ICE | A14 #6 |
| A01 TDS `:Decimal` → `Decimal(38,0)` | A14 #12 |
| A01 `TypeClassifier` unvalidated generic head | A17 F5 |
| A01 `DECIMAL(2,5)` store-column IAE | A08 #7 |
| A02 #2 `[1]` over a nullable column | A08 #4 |
| A03 #1 property override | A07 #4 |
| A02 #8 unbound multiplicity Var | A17 F3 |
| A05 #3 D-suffix dead guard | A17 F2 |

---

## 4. Tally

91 adjudicated rows across `CONFIRMED.md` V1-V25 + CORRECTIONS and A01/A02/A03/A04/A05/A07/A08/
A10/A13/A14/A17.

| verdict | count |
|---|---|
| CONFIRMED | 82 |
| CONFIRMED-BUT-OVERSTATED | 5 |
| NOT-REPRODUCED | 1 |
| NOT-REPRODUCED + MISATTRIBUTED | 1 |
| FALSE (a "VERIFIED SOUND" entry that is not sound) | 1 |
| BY-DESIGN | 0 |
| NOT ADJUDICATED | 1 |

Of the 82 CONFIRMED, **24 are DUPLICATE-tagged** — the same defect filed under a different name
by another auditor (see §3). So the fleet's ~91 adjudicated claims cover roughly **58 distinct
defects**.

§2 lists 12 rulings where a filing is false, materially overstated, mis-phased, incomplete, or
(twice) *under*stated; those include the 5 table-level CONFIRMED-BUT-OVERSTATED rows, the 2
NOT-REPRODUCED rows, the 1 FALSE row, and 4 rulings recorded against text outside the finding
tables (V13's "ZERO callers", V14's census, A13's H2 row, A14's `BOOLEAN` fixture).

**Bottom line.** The fleet's core claims are overwhelmingly real: I reproduced 82 of them from
scratch with my own fixtures, including every top-prize UNSOUND claim I tested. The failures are
concentrated in (a) *reachability* claims asserted from unit-level throws (A01 PlanText, A08 #8),
(b) *phase/site* attributions (A01 DECIMAL(2,5), A01 PlanText method name), (c) *census
completeness* (V14), (d) *environment-dependent measurements reported as code properties*
(A17 F13), and (e) one false VERIFIED-SOUND entry (A10). The single most important correction is
§0: `CONFIRMED.md`'s CORRECTIONS §1 retracts a finding **that was never filed**, and in doing so
mislabels A01 — which got that site right — as wrong.

## 5. NOT COVERED

- A06, A09, A11, A12, A15, A16, A18–A37 and V2-falsifier: outside my assigned list (they landed
  during my run).
- A14's dialect-capability matrix and A08's full 486-cell arithmetic matrix: my batch harness is
  DuckDB-only; I verified the individual DuckDB rows I could reach and the null-order question on
  H2/SQLite by direct JDBC, but did not re-derive either full matrix.
- `CONFIRMED.md` V21/V22/V26–V28 (added after my task started; V22 I incidentally confirmed —
  `BOOLEAN` is genuinely rejected by `DatabaseProtocolParser.java:391`).
