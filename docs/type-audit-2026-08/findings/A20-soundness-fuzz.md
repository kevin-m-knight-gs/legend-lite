# A20 — SOUNDNESS FUZZING OF THE WHOLE PIPELINE

Differential/property fuzzer over `com.legend.Compiler.plan(...)` + `com.legend.Compiler.execute(...)`
against DuckDB with seeded data. Everything below is either (a) pasted output of code I ran, or
(b) a quoted `file.java:LINE`. No claim here rests on a comment or a doc.

---

## 0. THE MACHINE

| artefact | path |
|---|---|
| fuzzer / oracle / shrinker | `/home/user/audit/fuzz/Fuzz.java` |
| batch evidence printer (type + SQL + rows) | `/home/user/audit/fuzz/Ev.java` |
| **oracle mutation self-test** | `/home/user/audit/fuzz/OracleSelfTest.java` |
| shell driver (compile once, N shards, disjoint seeds) | `/home/user/audit/fuzz/run.sh` |
| fixed model (9 primitive-typed props + nullable + enum + subclass + 1:many + 0..1 + 4 mapped tables) | `/home/user/audit/fuzz/model.pure` |
| seed data (6 widgets, 2 gadgets, 6 parts, 3 owners; NULLs, leap day, `2147483647`, `1e30`, `-45.60`) | `/home/user/audit/fuzz/ddl.sql` |
| full failure log | `/home/user/audit/findings/A20-fuzz-log.txt` |

Repro a single query:
`/home/user/probe/jrun.sh /home/user/audit/fuzz/Fuzz.java one '<query>'`
**Replay a seed** (prints the exact generated query):
`/home/user/probe/jrun.sh /home/user/audit/fuzz/Fuzz.java gen <seed>`
Repro a shard: `SHARDS=4 PER=1600 BASE=<seed> OUT=<dir> /home/user/audit/fuzz/run.sh`
Shard *i* of a campaign consumes seeds `[BASE + i*100000, +PER)`; **every failure below carries the
seed that produced it**. Campaign bases: run1 `100000`, run2 `900000`, run3 `5000000`.

Verified replay (seed 1100017, run2 shard-2):
```
$ jrun.sh Fuzz.java gen 1100017
fz::Widget.all()->project(~[c0:w|$w.stamp, c1:w|$w.note->toOne(), c2:w|$w.price])->extend(over(~c0),
  ~c3:{p,w,rw|$rw.c2}:y|$y->max())->rename(~c3, ~c4)->rename(~c1, ~c5)->select(~[c2, c5, c0])->limit(2)
```
byte-identical to that shard's logged `orig:` line.

> **Caveat, stated up front:** seeds from campaign **run1** (bases `100000`–`400000`) and from the two
> pilot batches (`1000`–`1200`, `7000000`) were produced by an *earlier revision* of the generator —
> before the function vocabulary, `pivot`, and the schema-collision steps were added — so they do
> **not** replay byte-identically against the current `Fuzz.java` (the query prefix matches; the tail
> diverges). Only run2/run3 seeds (`900000`+, `5000000`+) replay exactly. This costs nothing for the
> findings themselves: **every finding below is stated as a literal hand-written query** that I ran
> through `Ev.java`, independent of any generator revision.

### Generator
Seeded `java.util.Random(seed)`. Root = `<Class>.all()->project(~[...])` over `fz::Widget`
(all of `String/Integer/Float/Decimal/Boolean/StrictDate/DateTime`, a nullable `String[0..1]`, an
enum), `fz::Gadget` (subclass), `fz::Part`, `fz::Owner`, including association navigation
(`$w.owner.ownerName` = 0..1, `$w.parts.partName` = 1:many, `$p.widget.qty` = many→1) and optional
`->toOne()`. Then 1–5 pipeline stages drawn from `filter, extend, extend(over(...)) window +
window-aggregate, select (sometimes reordered), rename (sometimes colliding), sort, limit, drop,
distinct, groupBy, join(INNER|LEFT), concatenate(self-prefix), pivot`, plus an optional terminal
(`size, slice, select(~c), columns()->size, distinct()->size`). Expressions are type-directed and
draw on ~90 `builtin/Pure.java` natives (arith, comparison, `if`, string, date, math, bit, parse).

### Shrinker
Greedy: repeatedly try deleting each pipeline stage, then each root projection column; keep a
deletion only when the re-run yields the **same (kind, signature)**. Runs on first sighting of a
signature, up to 8 distinct shrunk shapes per signature (keyed by the op-set of the shrunk query),
capped at 400 shrinks per shard.

### THE ORACLE — declared Pure type → admissible Java carrier
Justification is the repo's own decode contract, not my taste:
`Executor.unwrap` (`core/.../exec/Executor.java:641-679`) makes **every** temporal a
`com.legend.values.PureDateLiteral` ("the wire's temporal type is PureDateLiteral, FULL STOP");
`PureAsserts.kindOf` (`core/.../exec/PureAsserts.java:203-207`) declares `Byte|Short|Integer|Long|
BigInteger` all to be Pure `Integer`; `PureSql.java:129` — `case Type.EnumType e -> SqlType.Scalar.VARCHAR`
— makes `String` the enum carrier.

| declared | admissible Java |
|---|---|
| `String` | `java.lang.String` |
| `Integer` | `Long \| Integer \| Short \| Byte \| BigInteger` |
| `Float` | `Double \| Float` |
| `Decimal` / `Decimal(p,s)` | `BigDecimal` (and `scale ≤ s`, `precision-scale ≤ p-s`) |
| `Number` | any `java.lang.Number` |
| `Boolean` | `Boolean` |
| `StrictDate` | `PureDateLiteral.StrictDate` |
| `DateTime` | `PureDateLiteral.DateWith{Hour,Minute,Second,Subsecond}` |
| `Date` | any `PureDateLiteral` |
| `EnumType(fqn)` | `String` |

Twelve checks run per query: `PLAN_EXEC_TYPE_DIVERGE`, `SHAPE_DIVERGE`, `COL_COUNT`, `COL_ORDER`,
`COL_TYPE_DECL`, `ROWCOUNT_MULT`, `NULL_IN_ONE`, `JAVA_CLASS`, `DECIMAL_SCALE`,
`DECIMAL_PRECISION`, `INT_RANGE`, `ROW_ARITY`. Negative oracle: any escaping throwable that is not a
`com.legend.error.LegendCompileException` subtype and not a `NotImplementedException` is an **ICE**,
keyed by `exceptionClass@<first com.legend.* stack frame>`; a `SQLException` is **BAD_SQL**, keyed by
its digit-normalised message head.

**The oracle is not dead code — every check was mutation-tested.** `OracleSelfTest` feeds each check a
deliberately violating `(QueryPlan, ExecutionResult)` pair:

```
FIRES   COL_COUNT   -> COL_COUNT: declared 2 ([x, y]) but got 1 ([x])
FIRES   COL_ORDER   -> COL_ORDER: pos 0 declared 'x' got 'y' (declared=[x, y])
FIRES   COL_TYPE_DECL   -> COL_TYPE_DECL: pos 0 'x' declared String but result column type Integer
FIRES   NULL_IN_ONE   -> NULL_IN_ONE: column 'x' declared String[1] but row 1 is NULL
FIRES   JAVA_CLASS   -> PLAN_EXEC_TYPE_DIVERGE: plan=Relation<(x:String[1])> exec=Relation<(x:Integer[1])>
FIRES   DECIMAL_SCALE   -> DECIMAL_SCALE: column 'x' declared Decimal(10,2) but value 1.2345 has scale 4
FIRES   DECIMAL_PRECISION   -> DECIMAL_PRECISION: column 'x' declared Decimal(10,2) but value 123456789012.00 needs 12 integral digits
FIRES   INT_RANGE   -> INT_RANGE: column 'x' declared Integer but value 170141183460469231731687303715884105727 does not fit a 64-bit signed integer
FIRES   ROWCOUNT_MULT   -> ROWCOUNT_MULT: root declared [1] but produced 3 value(s)
FIRES   SHAPE_DIVERGE   -> SHAPE_DIVERGE: plan shape=TABULAR but result is Scalar
FIRES   PLAN_EXEC_TYPE_DIVERGE   -> PLAN_EXEC_TYPE_DIVERGE: plan=Integer exec=String
FIRES   ROW_ARITY   -> ROW_ARITY: 2 columns but row 1 has 1 cells
CLEAN   control (conforming result) []
```

---

## 1. COUNTS AND TAXONOMY

Three campaigns, 8s per-query timeout, one DuckDB connection per shard (rebuilt on DB invalidation).

**Primary (clean) campaign — run2 + run3, 8 shards, uniform generator:**

```
generated = 12800     compiled = 10462     executed = 10030
```
(`compiled` = `Compiler.plan` produced SQL, i.e. no `LegendCompileException`/`NotImplementedException`;
`executed` = the SQL ran and returned a result set.)

| kind | count | share of generated |
|---|---:|---:|
| `OK` (executed, all 12 oracle checks pass) | 9020 | 70.5% |
| `USER_ERR` (clean `LegendCompileException`) | 2170 | 17.0% |
| **`UNSOUND` (oracle violation)** | **1010** | **7.9%** |
| **`BAD_SQL` (compiler emitted SQL the DB rejects)** | **431** | **3.4%** |
| `NOT_IMPL` (`NotImplementedException`) | 168 | 1.3% |
| **`ICE`** | **1** | 0.008% |
| `TIMEOUT` | 0 | — |

Distinct failure signatures, primary campaign:

| n | signature |
|---:|---|
| 960 | `UNSOUND :: NULL_IN_ONE` |
| 234 | `BAD_SQL :: Parser Error: syntax error at or near "UNION"` |
| 169 | `BAD_SQL :: Binder Error: No function matches … '-(INTEGER_LITERAL, …)' / 'abs(VARCHAR)'` |
| 48 | `UNSOUND :: JAVA_CLASS` |
| 13 | `BAD_SQL :: Binder Error: Aggregate with only constant parameters has to be bound in the root subquery` |
| 4 | `BAD_SQL :: Out of Range: Cannot left-shift negative number` |
| 4 | `BAD_SQL :: Out of Range: Overflow in addition of INT32` |
| 2 | `UNSOUND :: DECIMAL_SCALE` |
| 2 | `BAD_SQL :: Out of Range: Overflow in multiplication of INT32` |
| 2 | `BAD_SQL :: Out of Range: Overflow in multiplication of DECIMAL(18)` |
| 1 | `BAD_SQL :: Binder Error: GROUP BY term out of range` |
| 1 | `BAD_SQL :: Invalid Input: Unable to compute sqrt of -N` |
| 1 | `BAD_SQL :: Conversion Error: DOUBLE 1e+30 out of range for INT64` |
| 1 | `ICE :: IllegalStateException@lowering.Lowerer.lambda$scalarRoot$3:327` |

**Exploratory campaign run1 (5200 generated, 4581 compiled, 3777 executed)** found the same classes
plus two the primary run did not: `BAD_SQL :: INTERNAL Error: Attempted to access index 0 within
vector of size 0` (1 hit, seed **400595**) and `TIMEOUT` (3 hits, all first-query JVM warm-up, not
real). **Caveat, stated explicitly:** that one DuckDB internal error *invalidates the whole database
connection*, so run1 shard-3's remaining 587 queries all failed with `FATAL Error: database has been
invalidated`. Those 587 are an artefact of my harness, not 587 distinct defects; I added
`Fuzz.ensureConn()` (reconnect + re-seed on any `SQLException`) and re-ran as run3, which is why the
primary numbers above exclude run1.

Plus ~170 hand-directed probes (calibration + ICE hunt), all in the log.

**Grand total: 18 000 generated queries + ~170 directed probes.**

---

## FINDINGS

### [UNSOUND] `->toOne()` is a no-op in lowering: `[1]` is claimed, `NULL` is delivered

**Repro** (seed 1100017, 300027, 900006, 200032, 400010 — 5 independent shrinks land here)
```
fz::Widget.all()->project(~[c0:w|$w.note->toOne()])
```
**Actual output**
```
ROOT   Relation<(c0:String[1])>[1]   shape=TABULAR
SQL    SELECT t0.NOTE AS c0
       FROM T_WIDGET AS t0
COLS   c0:String[1]
ROW    String(n1) |
ROW    null |
ROW    String(n3) |
ROW    null |
```
`note` is declared `String[0..1]` in the model and `NOTE` is a nullable VARCHAR. `toOne()` re-stamps
the multiplicity to `[1]` and emits **no** guard whatsoever — the rendered SQL is the bare column.
Same for the 0..1 association (`$w.owner.ownerName->toOne()`, seed 200032) and for a 1:many
navigation (`$w.parts.weight->toOne()`, seed 300027). `$w.note->toOne()->toOne()` behaves identically.

**Why it matters** This is the canonical multiplicity-narrowing operator. Every downstream consumer
that trusts `[1]` (non-null field binding, `Optional`-free decoding, join-key assumptions) is reading
a lie. In real Pure `toOne` *fails* on an empty value; here it silently succeeds.

---

### [UNSOUND] `LEFT` join keeps the right-hand columns at `[1]`

**Repro** (seeds 200142, 400150, 1000100, 1100467, 1200075, 1200364, 300026, 900162 — 8 distinct shrinks)
```
fz::Widget.all()->project(~[c0:w|$w.name])
  ->join(fz::Part.all()->project(~[c1:z|$z.partName]), JoinKind.LEFT, {l, rr | $l.c0 == $rr.c1})
```
**Actual output**
```
ROOT   Relation<(c0:String[1], c1:String[1])>[1]   shape=TABULAR
SQL    SELECT * FROM ( SELECT t0.NAME AS c0 FROM T_WIDGET AS t0 ) AS t1
       LEFT OUTER JOIN ( SELECT t2.PART_NAME AS c1 FROM T_PART AS t2 ) AS t3 ON t1.c0 = t3.c1
COLS   c0:String[1]  c1:String[1]
ROW    String(alpha) | null |
ROW    String(beta)  | null |
ROW    String(gamma) | null |
```
`c1` is declared `String[1]`; every row of it is `NULL`.

**Why it matters** `LEFT OUTER JOIN` null-extension is the defining semantics of the operator. The
schema algebra for `join` copies the right relation's column multiplicities verbatim instead of
weakening every lower bound to 0 when `JoinKind.LEFT`. The type is wrong for *every* left-outer join
with a non-matching row — the single most common relational shape there is.

---

### [UNSOUND] Aggregates are typed `[1]` although SQL aggregates return `NULL` — and `max()` disagrees with `sum()`

**Repro A — empty input** (from the shrink family at seeds 100135, 400204, 1000706, 900648)
```
fz::Widget.all()->project(~[c0:w|$w.qty])->limit(0)->groupBy(~[], ~[c1:v|$v.c0:y|$y->sum()])
fz::Widget.all()->project(~[c0:w|$w.qty])->limit(0)->groupBy(~[], ~[c1:v|$v.c0:y|$y->max()])
```
**Actual output**
```
ROOT   Relation<(c1:Integer[1])>[1]        <-- sum()
COLS   c1:Integer[1]
ROW    null |

ROOT   Relation<(c1:Integer[0..1])>[1]     <-- max(), same input
COLS   c1:Integer[0..1]
ROW    null |
```
`sum` claims `[1]`, `max` claims `[0..1]`, for byte-identical inputs. Only `max` is right.

**Repro B — all-NULL group over an optional source** (seeds 1200016, 100200, 400156)
```
fz::Widget.all()->project(~[c0:w|$w.parts.weight, c1:w|$w.ratio])
  ->groupBy(~[c1], ~[c6:v4|$v4.c0:v5|$v5->average()])
```
```
ROOT   Relation<(c1:Float[1], c6:Float[1])>[1]
SQL    SELECT t0.RATIO AS c1, AVG(t1.WEIGHT) AS c6 FROM T_WIDGET AS t0
       LEFT OUTER JOIN T_PART AS t1 ON t0.ID = t1.WIDGET_ID GROUP BY t0.RATIO
COLS   c1:Float[1]  c6:Float[1]
ROW    Double(3.0) | null |
```
The *source* column `c0` is correctly `Float[0..1]`; the aggregate over it is stamped `Float[1]`.

**Repro C — window aggregate, same defect** (seeds 100320, 1000115)
```
fz::Widget.all()->project(~[c0:w|$w.parts.weight, c3:w|$w.qty])
  ->extend(over(~c3), ~c5:{p,w,rw|$rw.c0}:y|$y->plus())
```
```
ROOT   Relation<(c0:Float[0..1], c3:Integer[1], c5:Float[1])>[1]
ROW    null | Integer(7) | null |
```

**Repro D — sample statistics on a one-row group** (seeds 300309, 1000300, 1200136, 400225, …)
```
fz::Widget.all()->project(~[c0:w|$w.ratio])->groupBy(~[c0], ~[c1:v|$v.c0:y|$y->stdDevSample()])
```
```
ROOT   Relation<(c0:Float[1], c1:Number[1])>[1]
SQL    SELECT t0.RATIO AS c0, STDDEV_SAMP(t0.RATIO) AS c1 FROM T_WIDGET AS t0 GROUP BY t0.RATIO
COLS   c0:Float[1]  c1:Number[1]
ROW    Double(1.5)  | Double(0.0) |
ROW    Double(0.25) | null |
ROW    Double(3.0)  | null |
```
`stdDevSample`/`variance` are `NULL` by definition for n=1; the type says `Number[1]`.

**Repro E — string aggregate over an empty relation** (seeds 100097, 100135, 1100626, 300404)
```
fz::Part.all()->project(~[c2:w|$w.partName])->filter(v7|false)
  ->groupBy(~[], ~[c10:v8|$v8.c2:v9|$v9->joinStrings(',')])
→ NULL_IN_ONE: column 'c10' declared String[1] but row 1 is NULL
```

**Why it matters** `NULL_IN_ONE` — this family plus `toOne()` and LEFT-join above — is **960 of the
1010 oracle violations (95%)** in the primary campaign. The rule "an aggregate of `T[*]` is `T[1]`"
is simply false over SQL: `SUM/AVG/MIN/MAX/STRING_AGG/STDDEV_SAMP` of an empty or all-NULL group is
`NULL`. That `max` alone is typed `[0..1]` proves there is no single owner of the rule
(**INCONSISTENCY**), which is exactly how this regenerates.

---

### [UNSOUND] Unary `-`, unary `+`, `abs()`, `plus()`/`times()` are typed over an **unbounded** type variable — `-aString` type-checks and claims `String[1]`

**Evidence** `core/src/main/java/com/legend/builtin/Pure.java` registers the collection folds with a
bare `<T>`, i.e. **no bound**:
```java
native function meta::pure::functions::math::minus<T>(values:T[*]):T[1];
native function meta::pure::functions::math::plus<T>(values:T[*]):T[1];
native function meta::pure::functions::math::times<T>(values:T[*]):T[1];
native function meta::pure::functions::math::abs<T>(number:T[1]):T[1];
```
`com.legend.compiler.element.type.Type.TypeVar` is `record TypeVar(String name)` — the type model
**cannot represent a bound at all**, so `T` unifies with `String`, `Boolean`, `StrictDate`,
`fz::Color`, anything. `SpecParser.parseUnaryAndPrimary` (`parser/SpecParser.java:597-603`) desugars
prefix `-x` to a one-argument `minus`, which lands on exactly that overload.

**Repro + actual output**
```
QUERY  fz::Widget.all()->project(~[c0:w|-$w.name])
ROOT   Relation<(c0:String[1])>[1]                     <-- claims String
SQL    SELECT 0 - t0.NAME AS c0 FROM T_WIDGET AS t0
EXEC-ERR java.sql.SQLException: Binder Error: No function matches the given name and argument
         types '-(INTEGER_LITERAL, VARCHAR)'.

QUERY  fz::Widget.all()->project(~[c0:w|$w.name->abs()])
ROOT   Relation<(c0:String[1])>[1]
SQL    SELECT abs(t0.NAME) AS c0 FROM T_WIDGET AS t0
EXEC-ERR java.sql.SQLException: Binder Error: No function matches ... 'abs(VARCHAR)'.

QUERY  fz::Widget.all()->project(~[c0:w|+$w.name])       <-- SUCCEEDS
ROOT   Relation<(c0:String[1])>[1]
SQL    SELECT t0.NAME AS c0 FROM T_WIDGET AS t0
ROW    String(alpha) |
```
Also confirmed for `-$w.active`, `-$w.made`, `$w.active->abs()`, `$w.made->abs()`,
`$w.color->abs()`, `[$w.name,$w.name]->plus()`, `[$w.active,$w.active]->times()` (see
`/tmp/calib3.log`, reproduced in the log file). The *binary* form is correctly rejected —
`1 - true` raises `TypeInferenceException: no overload of 'minus' structurally matches` — so the
arity-1 collection overload is the only hole.

**Why it matters** Top-prize unsoundness: a static type (`String[1]`) is assigned to an expression
for which **no runtime value can exist**. `+x` is worse than the errors — it is a **silent identity
fallback** on any type, which the repo's own rules forbid ("NO FALLBACKS"). 169 of 431 BAD_SQL hits
in the primary campaign are this family, reached by the generator purely through negative numeric
literals (`-2->toString()->contains('a')` parses as `minus(Boolean)` and type-checks).

---

### [UNSOUND] `Decimal(p,s)` claimed by `if()` does not cover the branch join; `Decimal` arithmetic drops precision entirely

**Repro A** (seeds 100309, 200049, 1177)
```
fz::Widget.all()->project(~[c0:w|if($w.active, |$w.price, |1.5D)])
```
```
ROOT   Relation<(c0:Decimal(38,1)[1])>[1]
SQL    SELECT CASE WHEN t0.ACTIVE THEN t0.PRICE ELSE 1.5 END AS c0 FROM T_WIDGET AS t0
COLS   c0:Decimal(38,1)[1]
ROW    BigDecimal(12.34)       <-- scale 2, declared scale 1
ROW    BigDecimal(1.50)        <-- scale 2, declared scale 1
```
and
```
fz::Widget.all()->project(~[c0:w|if($w.active, |(-2.25D * -2.25D), |-2.25D)])
ROOT   Relation<(c0:Decimal(38,2)[1])>[1]
ROW    BigDecimal(5.0625)      <-- scale 4, declared scale 2
```
The branch join takes one branch's scale rather than `max`.

**Repro B — precision is not merely wrong, it is *erased***
```
fz::Widget.all()->project(~[c0:w|($w.price * $w.price)])
ROOT   Relation<(c0:Decimal[1])>[1]        <-- bare Decimal, no (p,s)
SQL    SELECT t0.PRICE * t0.PRICE AS c0 FROM T_WIDGET AS t0
EXEC-ERR Out of Range Error: Overflow in multiplication of DECIMAL(18) (9999999999 * 9999999999).

fz::Widget.all()->project(~[c0:w|$w.price->toDecimal()])->extend(~c1:v|($v.c0 * $v.c0 * $v.c0))
ROOT   Relation<(c0:Decimal(38,18)[1], c1:Decimal[1])>[1]
EXEC-ERR Out of Range Error: Needed scale 54 to accurately represent the multiplication result …
```
`Type.PrecisionDecimal.times()` exists (`type/Type.java:214`) and would compute `(20,4)` and `(38,54→clamped)`;
the lowering never calls it, emits no widening cast, and stamps the result plain `Decimal`.

**Why it matters** Two *different* Decimal typings coexist — `if()` produces a `PrecisionDecimal`,
`*` produces a bare `Decimal` (**INCONSISTENCY**, **INFORMATION LOSS** at the G→I boundary) — and
neither is sound: the first under-declares scale, the second loses the information needed to emit a
cast, so ordinary in-range data (`99999999.99`, a `DECIMAL(10,2)` column) raises a DB overflow.

---

### [UNSOUND] `StrictDate`-typed values come back as `PureDateLiteral.DateWithSecond`

**Repro** (seeds 900247, 900974, 1000833, 1100289, 1100639, 1200582 — `parseDate`; 900069, 1000072,
1100548 — `firstDayOfQuarter`)
```
fz::Widget.all()->project(~[c0:w|'2021-01-05'->parseDate()])
ROOT   Relation<(c0:StrictDate[1])>[1]
SQL    SELECT CAST('2021-01-05' AS TIMESTAMP) AS c0 FROM T_WIDGET AS t0
COLS   c0:StrictDate[1]
ROW    DateWithSecond(2021-01-05T00:00:00+0000)

fz::Widget.all()->project(~[c0:w|$w.made->firstDayOfQuarter()])
ROOT   Relation<(c0:StrictDate[1])>[1]
SQL    SELECT date_trunc('quarter', t0.MADE) AS c0 FROM T_WIDGET AS t0
COLS   c0:StrictDate[1]
ROW    DateWithSecond(2021-01-01T00:00:00+0000)
```
Compare the *direct* column read, which is correct: `$w.made` → `StrictDate[1]` →
`StrictDate(2021-01-05)`.

**Why it matters** **FORWARD/BACKWARD ASYMMETRY**: the forward path casts a `StrictDate`-typed
expression to `TIMESTAMP`, so the backward path can only reconstruct a `DateTime` shape. Per
`PureDateLiteral`'s own doc block, `StrictDate` and `DateWithSecond` are *different Pure types*
(`StrictDate` vs `DateTime`), so equality, formatting and round-tripping all diverge from the
declared type. Sibling `firstDayOfMonth()` is declared `Date` (which the value satisfies) while
`firstDayOfQuarter()` is declared `StrictDate` (which it does not) — the same lowering
(`date_trunc`), two different declared types: **INCONSISTENCY**.

---

### [UNSOUND] `mod(0)` is typed `Integer[1]` and returns `NULL`; `rem(0)` raises instead

**Repro**
```
fz::Widget.all()->project(~[c0:w|$w.qty->mod(0)])
ROOT   Relation<(c0:Integer[1])>[1]
SQL    SELECT MOD(MOD(t0.QTY, 0) + 0, 0) AS c0 FROM T_WIDGET AS t0
COLS   c0:Integer[1]
ROW    null | (×6)

fz::Widget.all()->project(~[c0:w|$w.qty->rem(0)])
EXEC-ERR java.sql.SQLException: Invalid Input Error: Cannot divide 10 by zero
```
**Why it matters** Two spellings of the same operation, two behaviours, and the `mod` one produces a
`NULL` under a `[1]` stamp. (The triple-nested `MOD(MOD(x,0)+0,0)` rendering is separately worth a
look.)

---

### [CRASH/ICE] `IllegalStateException: a scalar query has no row scope` — a filter over a grouped relation followed by `->size()`

**Minimal repro** (seed 5200047, shrunk by the fuzzer then by hand)
```
fz::Widget.all()->project(~[c0:w|$w.name])
  ->groupBy(~[], ~[c8:v6|$v6.c0:v7|$v7->count()])
  ->filter(v11|($v11.c8 < 1))
  ->size()
```
**Actual output**
```
PLAN-ERR java.lang.IllegalStateException: a scalar query has no row scope for $v11.c8
EXEC-ERR java.lang.IllegalStateException: a scalar query has no row scope for $v11.c8
```
**Evidence** `core/src/main/java/com/legend/lowering/Lowerer.java:326-329`
```java
e = scalar(spec, (var, name) -> {
    throw new IllegalStateException("a scalar query has no row scope for $"
            + var + "." + name);
});
```
**Why it matters** This is the repo's own "genuine internal invariant violation" bucket
(`error/LegendCompileException.java:16-18`) escaping to the user on a query anyone could write —
count rows, filter the count, ask how many are left. It is an ICE, not a diagnostic.

---

### [CRASH/ICE] `StackOverflowError` escapes from six distinct frames on deeply-nested but legal expressions

**Repros and thresholds** (bisected; full listing in `/tmp/ice3.log`, `/tmp/ice4.log`, in the log file)

| repro | first `com.legend.*` frame |
|---|---|
| `project(~[c0:w|$w.qty + 1 + 1 …])`, 320 terms (200 OK) | `resolver.SyntheticHeads.liftFilteredHeads:298`, `compiler.spec.Typer.checkGeneric:1619`, `compiler.spec.Typer.synth:126` |
| `project(~[c0:w|(((…($w.name + 'x')…)))])`, 200 deep (175 OK) | `lowering.Lowerer.scalarInner:2350`, `lowering.Lowerer.scalarStructural:2580`, `lowering.Lowerer.scalarValueTailArms:2889`, `lowering.Lowerer.scalarRelationalArms:2828` |
| 400 nested `if($w.active, |…, |2)` (300 OK) | `parser.SpecParser.parseUnaryAndPrimary:587` |
| 500 nested parenthesised concat | `compiler.NameResolver.resolveVs:1481` |

**Why it matters** Every phase — parser, name resolver, type checker, store resolver, lowering — is
unbounded-recursive with no depth guard, and the failure is a `java.lang.Error`, so a caller that
catches `RuntimeException` does not even see it. Machine-generated Pure (the NLQ module in this repo
generates queries) hits these depths.

---

### [BAD-SQL] Set operations are rendered without parentheses — any `concatenate` after `limit`/`drop`/`sort` emits unparseable SQL

**Evidence** `core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:115-121`
```java
case SqlUnion u -> {
    String op = u.all() ? "UNION ALL" : "UNION";
    for (int i = 0; i < u.branches().size(); i++) {
        if (i > 0) { nl(sb, depth).append(op); nl(sb, depth); }
        query(sb, u.branches().get(i), depth);
    }
}
```
Each branch is written inline; nothing wraps a branch that carries `ORDER BY` / `LIMIT` / `OFFSET`.

**Repro** (seeds 100010, 100011, 1121, 1125, 1173, 7000000, 7000055 …)
```
fz::Widget.all()->project(~[c0:w|$w.name])->sort([ascending(~c0)])
  ->concatenate(fz::Widget.all()->project(~[c0:w|$w.name]))
```
```
SQL    SELECT t0.NAME AS c0
       FROM T_WIDGET AS t0
       ORDER BY t0.NAME
       UNION ALL
       SELECT t1.NAME AS c0
       FROM T_WIDGET AS t1
EXEC-ERR java.sql.SQLException: Parser Error: syntax error at or near "UNION"
```
Identical with `->limit(2)` or `->drop(1)` on either branch. `->distinct()` before `concatenate` is
fine (it becomes `SELECT DISTINCT`, not a clause).

**Why it matters** **234 of 431 BAD_SQL hits (54%)** in the primary campaign. `sort → concatenate`
and `limit → concatenate` are ordinary user pipelines; the plan compiles, `Compiler.plan()` returns a
string, and only the database says no.

---

### [BAD-SQL] `->size()` after `groupBy` emits `COUNT(COUNT(x))`

**Repro** (seeds 300258/…, 13 hits)
```
fz::Widget.all()->project(~[c0:w|$w.qty])->groupBy(~[], ~[c1:v|$v.c0:y|$y->count()])->size()
```
```
ROOT   Integer[1]   shape=SCALAR
SQL    SELECT (SELECT COUNT(COUNT(t0.QTY)) FROM T_WIDGET AS t0) AS value
EXEC-ERR java.sql.SQLException: Binder Error: Aggregate with only constant parameters has to be
         bound in the root subquery
```
The `size()` of an aggregated relation is fused into the aggregate's own SELECT instead of wrapping
it in a derived table. `fz::Widget.all()->project(~[c0:w|$w.name])->size()` alone is fine
(`SELECT (SELECT COUNT(t0.NAME) …) → Long(6)`), so the defect is specifically the `groupBy`+`size`
composition.

---

### [BAD-SQL / INCONSISTENCY] Pure `Integer` arithmetic is not widened — `2147483647 + 1` dies on INT32, while `9223372036854775807 + 1` is widened to HUGEINT

**Repro — pure literals, no data involved**
```
fz::Widget.all()->project(~[c0:w|(2147483647 + 1)])
ROOT   Relation<(c0:Integer[1])>[1]
SQL    SELECT 2147483647 + 1 AS c0 FROM T_WIDGET AS t0
EXEC-ERR java.sql.SQLException: Out of Range Error: Overflow in addition of INT32 (2147483647 + 1)!
```
**Contrast, same query shape one power of two up:**
```
fz::Widget.all()->project(~[c0:w|(9223372036854775807 + 1)])
SQL    SELECT CAST(9223372036854775807 AS HUGEINT) + 1 AS c0 FROM T_WIDGET AS t0
ROW    BigInteger(9223372036854775808)
```
Same with a column: `($w.qty + 1)` and `($w.qty * $w.qty)` over the `2147483647` row →
`Overflow in addition/multiplication of INT32`.

**Why it matters** The widening decision keys off the **literal's magnitude**, not off the operand
type's domain: a value that *is* a Pure `Integer` and *is* representable is rejected, while the
declared type is identical in both cases. `Executor.unwrap` (`exec/Executor.java:655-660`) is happy to
carry a `BigInteger` beyond 64 bits, so the backend already assumes arbitrary width — the frontend's
arithmetic lowering does not. **INCONSISTENCY + INFORMATION LOSS.**

---

### [BAD-SQL] A constant `extend` column is inlined into `GROUP BY` as a literal, which SQL reads as an ordinal

**Repro** (seed 1100xxx family; log line `GROUP BY term out of range`)
```
fz::Owner.all()->project(~[c0:w|$w.ownerName])->extend(~c2:v1|-6)
  ->groupBy(~[c2], ~[c5:v3|$v3.c0:v4|$v4->joinStrings(',')])
```
```
SQL    SELECT -6 AS c2, STRING_AGG(t0.OWNER_NAME, ',' ORDER BY t0.rowid ASC) AS c5
       FROM T_OWNER AS t0
       GROUP BY -6
EXEC-ERR java.sql.SQLException: Binder Error: GROUP BY term out of range - should be between 1 and 2
```
**Why it matters** `GROUP BY <integer literal>` is *positional* in SQL. Constant-folding a grouping
key into the `GROUP BY` clause changes the query's meaning whenever the constant happens to be a
valid ordinal (`GROUP BY 1` would silently group by the first output column instead of erroring) —
a **silent wrong-answer** hazard, not just an error.

---

### [BAD-SQL] `sort(...)->drop(0)` emits `OFFSET 0` and hard-crashes DuckDB, invalidating the connection

**Repro** (seed 400595)
```
fz::Widget.all()->project(~[c0:w|$w.name])->sort([ascending(~c0)])->drop(0)
```
```
SQL    SELECT t0.NAME AS c0 FROM T_WIDGET AS t0 ORDER BY t0.NAME OFFSET 0
EXEC-ERR java.sql.SQLException: INTERNAL Error: Attempted to access index 0 within vector of size 0
         … This error signals an assertion failure within DuckDB.
```
Every subsequent statement on that connection then fails with
`FATAL Error: … database has been invalidated because of a previous fatal error` — 587 consecutive
queries in run1 shard-3.

**Why it matters** The DuckDB assertion is DuckDB's bug, but `drop(0)` is a *no-op* that legend-lite
chooses to render as `OFFSET 0`; not emitting a no-op clause avoids it. Ranked here because the blast
radius is the whole session, not one query.

---

### [BAD-SQL] `slice(hi, lo)` renders a negative `LIMIT`; `limit(-1)` reports the wrong diagnostic

```
fz::Widget.all()->project(~[c0:w|$w.qty])->slice(3, 1)
→ Binder Error: LIMIT/OFFSET cannot be negative

fz::Widget.all()->project(~[c0:w|$w.qty])->limit(-1)
→ NotImplementedException @ lowering.ConstBounds.intOf:58
  "dynamic slicing bounds are not lowered yet (literal expected), got TypedNativeCall"
```
**Evidence** `core/src/main/java/com/legend/lowering/ConstBounds.java:38-58` folds only
`plus/minus/times` **with exactly two arguments**; `-1` arrives from `SpecParser` as the *one*-argument
`minus` (see the unbounded-`T` finding), falls through, and reports "literal expected" about a
literal. Reversed `slice` bounds are not validated at all.

---

### [BAD-SQL] An empty column name renders a zero-length delimited identifier

```
fz::Widget.all()->project(~['':w|$w.name])
→ Parser Error: zero-length delimited identifier at or near """"
```
Quoted column names are otherwise handled correctly (`~['a b c':…]`, `~[select:…]`, `~['order':…]`
all execute); only the empty name escapes validation into the SQL text.

---

### [BAD-SQL] Unguarded numeric conversions: `round()` on a large Float, `bitShiftLeft` on a negative

```
fz::Widget.all()->project(~[c0:w|$w.ratio->round()])
SQL    SELECT CAST(ROUND_EVEN(t0.RATIO, 0) AS BIGINT) AS c0
EXEC-ERR Conversion Error: Type DOUBLE with value 1e+30 can't be cast … destination type INT64

fz::Widget.all()->project(~[c0:w|$w.qty])->extend(~c1:v|$v.c0->bitShiftLeft(2))
SQL    SELECT t0.QTY AS c0, (CAST(t0.QTY AS BIGINT) << 2) AS c1
EXEC-ERR Out of Range Error: Cannot left-shift negative number -3
```
`round : Float[1] -> Integer[1]` is total in the type system but partial in the lowering (the
`BIGINT` cast is narrower than the `BigInteger` carrier the decoder is willing to produce — the same
width inconsistency as the INT32 finding).

---

### [INFO] `cast(@Relation<…>)` is unchecked; the Java carrier for Pure `Integer` is not stable

```
fz::Widget.all()->project(~[c0:w|$w.name])
  ->cast(@meta::pure::metamodel::relation::Relation<(c0:Integer)>)
→ Conversion Error: Could not convert string 'alpha' to INT32 when casting from source column NAME
```
A relation cast that is statically impossible (String schema → Integer schema) compiles and is
discovered by the database. Separately, one declared Pure type `Integer` reaches the caller as three
Java classes depending on the plan shape — `java.lang.Integer` (direct column), `java.lang.Long`
(`->size()`, or the `BigDecimal`-integral path in `Executor.unwrap:660`), `java.math.BigInteger`
(`SUM`, HUGEINT). `PureAsserts.kindOf` accepts all three, so I did not score it as unsound, but a
consumer that pattern-matches on `Long` will silently miss the other two.

---

## VERIFIED SOUND

Checked, and found correct. Each line is coverage evidence, not an assumption.

* **Column count, column ORDER and per-column declared type** — `COL_COUNT` / `COL_ORDER` /
  `COL_TYPE_DECL` fired **0** times in 18 000 generated queries, including `select` with a shuffled
  column list (`->select(~[c1, c0])` correctly yields `Relation<(c1:Integer[1], c0:String[1])>` and
  the result columns come back in that order), `rename`, `groupBy` key reordering, `join` output
  concatenation, and `concatenate`. All three checks are proven live by the mutation self-test.
* **`plan().rootType()` == `execute().returnType()`** — `PLAN_EXEC_TYPE_DIVERGE` fired 0 times: the
  compile-only surface and the execution surface agree on the root type for every query that reached
  execution.
* **`ResultShape` classification** — `SHAPE_DIVERGE` fired 0 times; `TABULAR`/`SCALAR`/`COLLECTION`
  always matched the `ExecutionResult` variant actually produced.
* **Root multiplicity vs delivered value count** — `ROWCOUNT_MULT` fired 0 times for scalar and
  collection roots (`->size()`, `->columns()->size()`, `->distinct()->size()`).
* **Row arity** — `ROW_ARITY` fired 0 times; every row had exactly `columns().size()` cells.
* **Direct primitive decode** — `String→String`, `Integer→Integer/Long/BigInteger`, `Float→Double`,
  `Decimal→BigDecimal`, `Boolean→Boolean`, `StrictDate→PureDateLiteral.StrictDate`,
  `DateTime→PureDateLiteral.DateWithSecond` all conform for plain column reads, `select`, `sort`,
  `limit`, `drop`, `distinct`, `filter`, `rename`, `concatenate`, `INNER join`.
* **Enum mapping round-trip** — `fz::Color` maps to `CASE WHEN COLOR='RED' THEN 'RED' …` and decodes
  to `String`, consistently with `PureSql.java:129`; `filter(… == fz::Color.RED)` inverts through
  the enumeration mapping correctly; `groupBy … max()` over an enum yields `fz::Color[0..1]`.
* **`[*]` navigation in `project`** — `$w.parts.partName` (statically `String[*]`) is typed
  `String[0..1]` and lowered to a `LEFT OUTER JOIN` row expansion; the per-cell contract
  (`[0..1]` admits NULL) holds on every row.
* **Binary arithmetic overload checking** — `1 - true`, `$v.c0` used as a Boolean predicate,
  `joinStrings` over an `Integer` column, `sort` on a column dropped by `select`, `concatenate` of
  mismatched schemas, `select(~[c0, c0])`, `extend`/`rename`/`groupBy` onto an existing column name,
  `sort([])`, `$w.owner.owner.ownerName` — **all** produce clean `TypeInferenceException` /
  `SchemaInvariantException` (both are `LegendCompileException` subtypes), never an ICE.
* **`lead()`/`lag()` window results are correctly typed `[0..1]`** — `$p->lead($rw).c0` is
  `Integer[0..1]` and NULL at partition edges: the type matches.
* **`max()` over an empty group is correctly typed `[0..1]`** (which is what makes `sum()`'s `[1]` a
  defect rather than a design choice).
* **Deep pipelines and wide schemas are fine** — 300 chained `filter`s and a 300-column `project`
  both execute correctly; only *expression* nesting (not pipeline length or width) overflows the
  stack.
* **Quoted / keyword-like column names** — `~[select:…]`, `~[from:…]`, `~['order':…]`,
  `~['a b c':…]` all render and filter correctly.
* **Subclass mapping** — `fz::Gadget` (extends `fz::Widget`, own table) projects inherited and own
  properties correctly and `concatenate`s with a `fz::Widget` projection.

---

## NOT COVERED

* **`pivot` over a class-rooted query is unreachable** — `NotImplementedException: class query under
  TypedPivot is not resolvable yet (H2 vocabulary)`. The oracle's dynamic-column branch
  (`RelationType.dynamicColumns`) therefore never executed against real data; only `#TDS`-literal
  roots (which I did not fuzz) can reach it.
* **Other dialects.** Everything here is DuckDB. H2 and SQLite renderers, and the
  `EngineStyle*`/`CarrierStrategies` dialect passes, are untested by this fuzzer — the
  set-operation-parenthesisation and INT32-widening defects are dialect-independent by inspection of
  `AnsiSqlRenderer`, but I did not run them elsewhere.
* **GRAPH shape.** No bare class root (`fz::Widget.all()` with no `project`) was fuzzed, so
  `ExecutionResult.Graph`, `serializeGraph`, and the JSON envelope are unexercised. `ResultShape.GRAPH`
  never occurred.
* **Milestoning / temporal roots, `asOfJoin`, `lateral`, `reduce`, `write`, `variant`/semi-structured,
  M2M mappings, union/inheritance mappings, `#TDS` literals, user-defined functions and the
  `UserCallInliner` (Phase G½), streaming (`planStreaming`/`executeWire`), and multi-store runtimes**
  — all outside the generated grammar.
* **`Multiplicity.Var` leakage.** `requireBounded` never threw; no generated query produced an
  unresolved multiplicity variable at the boundary. Unbounded generics *were* exercised (the `abs`/
  `minus` finding) but only through the type-variable, not the multiplicity-variable, channel.
* **Byte / Binary / `StrictTime` / `LatestDate` primitives** are not in the model, so their carriers
  are unchecked.
* **Concurrency, plan caching, and the effect of repeated compilation of the same model** were not
  probed; each query recompiles the model from source.
* **DuckDB itself is a confound for the BAD_SQL class**: I classified anything the database rejected
  as a legend-lite defect. For `sqrt(-2.75)`, `parseInteger('abc')`, `parseDate('nope')`, division by
  zero and `bitShiftLeft(-3)` a reasonable person could argue the *user* asked for a partial
  function; I have ranked those lowest and separated them from the ones where the emitted SQL is
  syntactically or structurally wrong (UNION, `COUNT(COUNT())`, `GROUP BY -6`, `OFFSET 0`,
  `0 - VARCHAR`, INT32 arithmetic).
