# A14 — AGGREGATION family audit (GroupBy / Aggregate / Fold / Pivot / Tds + lowering)

Scope read IN FULL: `compiler/spec/{GroupByChecker,AggregateChecker,FoldChecker,PivotChecker,TdsChecker}.java`,
`compiler/spec/typed/{TypedAggregate,TypedAggCol,TypedAggColSpec,TypedAggColSpecArray,TypedFold,FoldStrategy,TypedPivot,TypedGroupBy,TypedExtendAgg}.java`,
`lowering/{Aggregates,Fold,CalendarAgg,Pivots}.java`, `sql/SqlAgg.java`.
Supporting reads: `sql/SqlTyping.java` (`reducerType`, `foldType`), `exec/Executor.java` (egress decode),
`exec/DynamicPivot.java`, `sql/dialect/{CarrierStrategies,FoldToListReduce}.java`, `lowering/{LambdaBinding,Scalars,Stamps,PureSql}.java`,
`compiler/element/type/Type.java` (`RelationType`, `PIVOT_SEPARATOR`, `dynamicColumns`, `pivotColumnType`).

**Note on scope wording:** `lowering/Fold.java` (1040 lines) is NOT the `fold` function's lowering — it is the
SQL *fold-vs-isolate* (clause-slot) authority. It was read in full anyway. The `fold` FUNCTION lowers through
`lowering/LambdaBinding.lowerFold` (dispatched at `lowering/Lowerer.java:2742`) and
`sql/dialect/FoldToListReduce.java`; both were read in full.

**Central answer: NO.** The declared Pure type of an aggregate result is routinely NOT the type (or even the
cardinality) the database produces. 18 of the 31 reachable aggregates declare `[1]` and return SQL NULL over an
empty group; `sum(Integer)` declares `Integer[1]` and returns values outside the 64-bit Pure Integer domain;
`plus()` over a Boolean/Date/String column declares that column's type and returns a number or a raw SQL error.

---

## Reconciliation of the two aggregate vocabularies

`SqlAgg.Fn` has 39 constants. `Aggregates.REDUCERS` (probe `EnumAgg.java`) has **93 registered Pure signature
keys** mapping onto **25 distinct `Fn`s**. Reachable from a Pure reduce lambda:
`ANY_VALUE, ARG_MAX, ARG_MIN, AVG, BOOL_AND, BOOL_OR, CORR, COUNT, COVAR_POP, COVAR_SAMP, HASH_LIST,
IS_DISTINCT_MARK, MAX, MEDIAN, MIN, MODE, QUANTILE_CONT, STDDEV_POP, STDDEV_SAMP, STRING_AGG, SUM,
UNIQUE_VALUE_ONLY, VAR_POP, VAR_SAMP, WAVG`.

**In `SqlAgg.Fn` but NOT reachable from any Pure reducer registration** (14):
`LIST`, `QUANTILE_DISC`, `QDISC_DESC`, `VARIANCE`, `STDDEV`, `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `PERCENT_RANK`,
`CUME_DIST`, `NTILE`, `LAG`, `LEAD`, `FIRST_VALUE`, `LAST_VALUE`, `NTH_VALUE`.
Of these: `LIST` is Lowerer-internal (hashCode/percentile-desc composition), `QUANTILE_DISC`/`QDISC_DESC` are
produced by the 4-arg `percentile` lowering (`Lowerer.java:3353,3357`), and the ranking/value kinds are
window-only (`Windows.java`). **`VARIANCE` and `STDDEV` are registered ONLY as window-only kinds
(`lowering/Windows.java:54-55`)** — the GROUP BY lane never emits them (Pure `variance` maps to `VAR_SAMP`,
`stdDev` to `STDDEV_SAMP`). Not a defect, but the enum comment "THE aggregate-function vocabulary" over-claims.

**Pure aggregate-shaped natives NOT in the catalog** (verified by probe `P16.java`): `last`, `toOne`,
`makeString`, `at` — all type-check in reduce position and then throw `IllegalStateException` at plan time
(see finding CRASH-1).

---

## FINDINGS

### [UNSOUND] `sum(Integer)` / `plus()` declares `Integer[1]` but DuckDB returns HUGEINT — values escape the 64-bit Pure Integer domain

**Evidence.** `builtin/Pure.java:2188`
```java
SUM__INTEGER_MANY = signature("native function meta::pure::functions::math::sum(numbers:...Integer[*]):...Integer[1];");
```
`sql/SqlTyping.java:853-866` knows the truth and stores it on the SQL side only:
```java
case SUM -> { if (integerFamily(t) || t == SqlType.Scalar.BOOLEAN) { ... yield T_HUGEINT; } ...
```
Nothing propagates that back to the Pure type. `exec/Executor.java:566-590 fetch()` is a JDBC pass-through — the
declared Pure type never coerces the cell.

**Repro + actual output** (`P2.java`, DuckDB 1.5.0):
```
Q: |#TDS grp:Integer, val:Integer / 1, 9223372036854775807 / 1, 9223372036854775807 #
     ->aggregate(~out:x|$x.val:y|$y->sum())
  [G]    Relation<(out:Integer[1])> [1]
  [SQL]  SELECT SUM(_tds0.val) AS out FROM (VALUES (1, 9223372036854775807), (1, 9223372036854775807)) AS _tds0(grp, val)
  [COL]  out : Integer mult=[1]
  [ROW]  BigInteger(18446744073709551614)
```
The value survives further Pure operations, growing:
```
...->extend(~o2:x|$x.out + 1)
  [COLS] out:Integer  o2:Integer
  [ROW]  java.math.BigInteger(18446744073709551614) | java.math.BigInteger(18446744073709551615)
```
**Why it matters.** Pure `Integer` is 64-bit (`CInteger` holds a `long`; TDS lowering calls `Long.parseLong`).
The repo's own consumption idiom is `((Number) v).longValue()` (e.g. `GroupByCheckerTest.java:66`):
```
BigInteger("18446744073709551614").longValue() = -2
BigInteger("9223372036854775808").longValue()  = -9223372036854775808
```
so a caller obeying the declared type silently reads `-2` for `18446744073709551614`. Cross-backend it is also
*inconsistent*: the same query on H2 returns `Long(30)` and on SQLite `Integer(30)` for a small sum
(`P3.java` h2/sqlite runs), so the runtime carrier of a declared `Integer` aggregate is backend-dependent.

---

### [UNSOUND] 18 aggregates declare `[1]` and return SQL NULL over an empty group (and over an all-NULL group)

**Evidence — exhaustive matrix** (`P14.java`, every catalog aggregate reachable with a scalar reduce, executed
over a zero-row relation on DuckDB):
```
AGG                  DECLARED(out)          ACTUAL over ZERO rows
sum                  Integer[1]             1 row(s): NULL     <<<< [1] VIOLATED
plus                 Integer[1]             1 row(s): NULL     <<<< [1] VIOLATED
count                Integer[1]             1 row(s): Long(0)
size                 Integer[1]             1 row(s): Long(0)
average              Float[1]               1 row(s): NULL     <<<< [1] VIOLATED
mean                 Float[1]               1 row(s): NULL     <<<< [1] VIOLATED
median               Float[1]               1 row(s): NULL     <<<< [1] VIOLATED
mode                 Integer[1]             1 row(s): NULL     <<<< [1] VIOLATED
percentile2          Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
stdDev               Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
stdDevSample         Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
stdDevPopulation     Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
variance             Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
varianceSample       Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
variancePopulation   Number[1]              1 row(s): NULL     <<<< [1] VIOLATED
hashCode             Integer[1]             1 row(s): Long(-4658895280553007687)
isDistinct           Boolean[1]             1 row(s): Boolean(true)
joinStrings          String[1]              1 row(s): NULL     <<<< [1] VIOLATED
joinStrings0         String[1]              1 row(s): NULL     <<<< [1] VIOLATED
and                  Boolean[1]             1 row(s): NULL     <<<< [1] VIOLATED
or                   Boolean[1]             1 row(s): NULL     <<<< [1] VIOLATED
wavg                 Float[1]               1 row(s): NULL     <<<< [1] VIOLATED
corr                 Number[0..1]           1 row(s): NULL      (honest)
covarSample          Number[0..1]           1 row(s): NULL      (honest)
covarPopulation      Number[0..1]           1 row(s): NULL      (honest)
maxBy                String[0..1]           1 row(s): NULL      (honest)
minBy                String[0..1]           1 row(s): NULL      (honest)
min                  Integer[0..1]          1 row(s): NULL      (honest)
max                  Integer[0..1]          1 row(s): NULL      (honest)
first                Integer[0..1]          1 row(s): NULL      (honest)
uniqueValueOnly      Integer[0..1]          1 row(s): NULL      (honest)
```
It is not only the empty relation. The same happens per-group:
* `stdDev` / `variance` / `varianceSample` on any **single-row group** (`P2.java`, `matrix_full.txt`):
  `|#TDS grp,val / 1,10 #->aggregate(~out:x|$x.val:y|$y->stdDev())` → `[G] Relation<(out:Number[1])>` →
  `[ROW] null`.
* `sum` over a group whose values are all NULL (`P2.java`):
  `1,10 / 1,null / 2,null ->groupBy(~grp, ~out:...sum())` → `out:Integer[1]` → `[ROW] Integer(2) | null`.
* A calendar aggregate whose CASE matches no row (`P11.java`, `pw_fm`): `o:Number[1]` → `[ROW] String(NYC) | null`.

**Why it matters.** `[1]` is the compiler's promise that a value is present. Any consumer that trusts it
NPEs or mis-reads. It is also a *wrong value*, not just a wrong stamp: Pure defines the empty-collection
identities (`and([])=true`, `or([])=false`, `joinStrings([])=''`), which is exactly why the `[1]` declaration
exists — see the next finding.

---

### [INCONSISTENCY] The empty-collection identity is implemented in the SCALAR lane and NOT in the AGGREGATE lane — same Pure function, two answers

**Evidence.** `lowering/Scalars.java:186-219` (the `and`/`or` rules at :188 and :209) implements Pure's identities explicitly:
```java
// The EMPTY collection takes each reduction's IDENTITY (and([]) is
// true, or([]) is false — list_aggregate over [] is NULL; audit).
... SqlExpr.Call.of(SqlFn.COALESCE, new SqlExpr.Call(SqlFn.LIST_BOOL_AND, args), new SqlExpr.BoolLit(true))
```
`lowering/Stamps.java:26-36` (`toOne` javadoc) claims **"THE FORK IS CLOSED (audit §4, slice 4)"**. It is not closed: the
aggregate lane (`lowering/Aggregates.java` → `Lowerer.aggExpr`) emits a bare `BOOL_AND` / `STRING_AGG` / `SUM`
with no COALESCE.

**Repro + actual output** (`P15.java`, one DuckDB session, same functions):
```
== SCALAR lane: []->and()
   [SQL] SELECT coalesce(list_aggregate(list_filter([TRUE], b -> b = FALSE), 'bool_and'), TRUE) AS value
   [VAL] Boolean(true)
== AGG lane: and() over an empty group
   [SQL] SELECT BOOL_AND(_tds0.b) AS o FROM (VALUES (1, 10, 'a', TRUE)) AS _tds0(g, n, s, b) WHERE _tds0.g > 99
   [TYPE] Relation<(o:Boolean[1])>
   [VAL] NULL

== SCALAR lane: []->or()          -> Boolean(false)
== AGG lane:    or() empty group  -> NULL     (declared Boolean[1])
== SCALAR lane: []->joinStrings(',')          -> String()   (empty string, correct)
== AGG lane:    joinStrings empty group       -> NULL       (declared String[1])
```
`sql/dialect/AnsiSqlRenderer.java:708-713` states the contract for the collection lane
("The expansion MUST honor Pure's empty-collection semantics"); the aggregate lane has no equivalent.

---

### [UNSOUND] `y|$y->plus()` over a Boolean / String / Date column: the checker returns the COLUMN's type and the lowering emits `SUM(col)`

**Evidence.** `builtin/Pure.java:2001` — `plus<T>(values:T[*]):T[1]` is fully generic, so `T` binds to whatever
the map produces. `lowering/Aggregates.java:32` registers *every* `plus` overload as `SUM`:
```java
// Pure spells numeric reduction via plus: y|$y->plus() == sum.
family(SqlAgg.Fn.SUM, "plus");
```
There is no numeric guard anywhere between the two.

**Repro + actual output.** Real table `T (GRP INTEGER, B BOOLEAN, S VARCHAR, DT DATE, TS TIMESTAMP)`, query
`#>{test::DB.T}#->groupBy(~GRP, ~out:x|$x.<col>:y|$y->plus())` (`P3.java`):

| column | declared | DuckDB | H2 | SQLite |
|---|---|---|---|---|
| `B BOOLEAN` | `Boolean[1]` | `BigInteger(1)` | `Long(1)` | `Integer(1)` |
| `S VARCHAR` | `String[1]` | `SQLException: No function matches 'sum(VARCHAR)'` | `JdbcSQLSyntaxErrorException: SUM or AVG on wrong data type` | **`Double(0.0)`** |
| `DT DATE` | `StrictDate[1]` | `SQLException: No function matches 'sum(DATE)'` | `SUM or AVG on wrong data type` | **`Double(2020.0)`** |
| `TS TIMESTAMP` | `DateTime[1]` | `SQLException: No function matches 'sum(TIMESTAMP)'` | `SUM or AVG on wrong data type` | **`Double(4040.0)`** |

**Why it matters.** On DuckDB/H2 the compiler hands a type-checked query to the DB which then rejects it —
a raw `SQLException` reaching the user, not a compile error. On SQLite the query *succeeds* and returns a
`Double` where the compiler promised `String` / `StrictDate` / `DateTime` — a silent, totally wrong answer.
`plus()` over Boolean is unsound on **every** backend.

---

### [UNSOUND] `median(Decimal)` declares `Float[1]` but returns a truncated `BigDecimal`; `min/max(Decimal)` erase the precision

**Evidence.** `builtin/Pure.java:1894` — `median(numbers:Number[*]):Float[1]` (the comment there asserts
"BOTH overloads return Float[1]"). `sql/SqlTyping.java:885-891` disagrees on the SQL side:
```java
case MEDIAN, QUANTILE_CONT -> { ... if (t instanceof SqlType.Decimal) { yield typed(t); } ... }
```
So the SQL fact says `DECIMAL(10,2)` while the Pure type says `Float`. Nothing reconciles them.

**Repro + actual output** (`P3.java`, table column `DEC DECIMAL(10,2)` holding `1.25, 2.50` in group 1):
```
median()   Decimal(10,2)   declared Float[1]   DuckDB: BigDecimal(1.87) ; BigDecimal(3.75)
median()   Decimal(10,2)   declared Float[1]   H2:     BigDecimal(1.875) ; BigDecimal(3.75)
```
Two defects at once: (a) the Java class is `BigDecimal`, not a Float carrier; (b) DuckDB truncates the true
median `1.875` to **`1.87`** because the result is pinned to the column's scale — a Float result would have
been exact. H2 returns `1.875`, so the *value* is backend-dependent too.
`percentile(0.5)` over the same column: declared `Number[1]`, DuckDB `BigDecimal(1.87)`, H2 `BigDecimal(1.875)`.

Related information loss on the same input: `min()`/`max()`/`sum()` over `DECIMAL(10,2)` declare
**`Number[0..1]` / `Number[1]`** — the precision and scale present in the source schema are thrown away
(overload resolution picks `min(Number[*]):Number[0..1]`), while `first()` and `uniqueValueOnly()` over the
*same* column keep `Decimal(10,2)` (they are `T`-generic). Two element-preserving reducers, two different
declared types for identical input.

---

### [UNSOUND] `y|$y->plus()` over a `Decimal(p,s)` column declares `Decimal(p,s)` — the sum does not fit

**Evidence.** `plus<T>(T[*]):T[1]` again. `SqlTyping.reducerType` correctly computes `Decimal(38,s)` for the
SQL side (`SqlTyping.java:871-872`), but the Pure declared type stays `Decimal(p,s)`.

**Repro + actual output** (`P4.java`; `T(GRP INTEGER, DEC DECIMAL(10,2))`, three rows of `99999999.99`):
```
Q: #>{test::DB.T}#->groupBy(~GRP, ~out:x|$x.DEC:y|$y->plus())
  [G]   Relation<(GRP:Integer[0..1], out:Decimal(10,2)[1])>
  [SQL] SELECT t0.GRP, SUM(t0.DEC) AS out FROM T AS t0 GROUP BY t0.GRP
  [DUCKDB-TYPEOF] sum(dec)=DECIMAL(38,2)   max(dec)=DECIMAL(10,2)
  [ROW] java.lang.Integer(1) | java.math.BigDecimal(299999999.97) scale=2 precision=11
```
`precision=11 > 10` — the value cannot be represented in the declared `Decimal(10,2)`.
The same happens at the ceiling (`P13.java`): a TDS `Decimal` column summing `1e38-1 + 1` declares
`Decimal(38,0)` and returns `BigDecimal(100000000000000000000000000000000000000)` — 39 digits.

---

### [CRASH/ICE] `pivot column '…' matches no aggregate template` — a group-key column whose name contains `__|__` (or any *quoted* column name) crashes the result decoder

**Evidence.** `compiler/element/type/Type.java:477-505` (`RelationType.pivotColumnType`):
```java
int sep = name.lastIndexOf(PIVOT_SEPARATOR);
if (sep >= 0 && !dynamicColumns().isEmpty()) {
    String template = name.substring(sep + PIVOT_SEPARATOR.length());
    return dynamicColumns().stream().filter(c -> c.name().equals(template)).findFirst()
            .map(Column::type)
            .orElseThrow(() -> new IllegalStateException("pivot column '" + name + "' matches no aggregate template " + ...));
}
```
The by-name pre-check compares against `columns()`, whose names for a **quoted** relational column carry the
literal double quotes (`compiler/element/StoreCompiler.java:176`:
`col.quoted() ? "\"" + col.name() + "\"" : col.name()`), while the result-set metadata name is bare — so a
quoted column never matches by name and falls through to the separator branch.

**Repro + actual output** (`P6.java`, DuckDB):
```
Database test::DB ( Table T ("A__|__B" INTEGER, CITY VARCHAR(100), TREES INTEGER) )
Q: #>{test::DB.T}#->pivot(~CITY, ~total : x|$x.TREES : y|$y->sum())
  [G]  static=[Column[name="A__|__B", type=INTEGER, ...]] dyn=[Column[name=total, type=INTEGER, ...]]
  [SQL] SELECT * FROM (PIVOT T AS t0 ON "CITY" USING SUM(TREES) AS "_|__total") AS t1
  [EXEC-ERR] java.lang.IllegalStateException: pivot column 'A__|__B' matches no aggregate template [total]
```
G and the SQL both succeed; the internal exception escapes from the decoder.

**The same name-mismatch also causes a SILENT FALLBACK** for quoted columns without the separator
(`exec/Executor.java:855-862` → `pureOfSqlType`), which re-derives the Pure type from the SQL type name and
loses precision (`exec/Executor.java:900: case "DECIMAL","NUMERIC" -> Type.Primitive.DECIMAL`):
```
Table T ("my col" DECIMAL(10,2), CITY VARCHAR(100), TREES INTEGER)
  [G]   static=[Column[name="my col", type=PrecisionDecimal[precision=10, scale=2], ...]]
  [COL] |my col| : Decimal mult=NULL          <-- precision/scale gone
```

---

### [SILENT FALLBACK] Static-pivot emulation names output columns with a Java record `toString()` for every non String/Int/Bool pivot key

**Evidence.** `sql/dialect/CarrierStrategies.java:301-307`:
```java
private static String litText(SqlExpr v) {
    return switch (v) {
        case SqlExpr.StringLit s2 -> s2.value();
        case SqlExpr.IntLit i -> String.valueOf(i.value());
        case SqlExpr.BoolLit b -> String.valueOf(b.value());
        default -> v.toString();
    };
}
```
used at `CarrierStrategies.java:157` to build `litText(v) + "__|__" + u.alias()`. `DynamicPivot.discover`
(`exec/DynamicPivot.java:90-119`) regenerates `FloatLit` / `DecimalLit` / `DateLit` / `TimestampLit` for the
corresponding key kinds, so all four hit the `default` arm.

**Repro + actual output** (`P6.java` / `P7.java`, H2 = `needsStaticPivot()`):
```
pivot key DOUBLE     -> [COL] |'FloatLit[value=1.5, type=Typed[type=DOUBLE, tolerated=false]]__|__total'|
pivot key DATE       -> [COL] |'DateLit[iso=2020-01-01, type=Typed[type=DATE, tolerated=false]]__|__total'|
pivot key DECIMAL    -> [COL] |'DecimalLit[value=1.50, type=Typed[type=Decimal[precision=3, scale=2], tolerated=false]]__|__total'|
pivot key TIMESTAMP  -> [COL] |'TimestampLit[iso=2020-01-01 00:00:00, type=Typed[type=TIMESTAMP, tolerated=false]]__|__total'|
```
DuckDB (native PIVOT) produces `'2020-01-01__|__total'` for the same DATE key, so the user-visible schema of
the same query differs by backend and, on H2, is Java debug output. This is exactly the "NO FALLBACKS. NO
DEFAULTING." class the brief names; `DynamicPivot.discover` was deliberately made exhaustive
(`"an unmapped kind now throws"`) but `litText` right after it was not.

---

### [CRASH/ICE] Legacy TDS `groupBy(['k'], agg('m', x|$x, y|$y->max()))` types the aggregate as a ROW STRUCT and emits `MAX(*)`

**Evidence.** `compiler/spec/GroupByChecker.java:151-164` rewrites the identity map to the constant `1` **only**
when the aggregator is `count`, and the comment claims the rest "dies loud":
```java
// the row-count idiom agg('cnt', x|$x, y|$y->count()): an
// IDENTITY selector over the row maps to the constant 1 — ...
// Gated on the aggregator BEING count: max/min/sum over $x would silently aggregate the
// constant (the engine emits broken SQL and dies loud there).
if (... && isCountAgg(aggFn)) { mapFn = new LambdaFunction(mapFn.parameters(), List.of(new CInteger(1))); }
```
It does not die loud in the compiler.

**Repro + actual output** (`P9.java`):
```
Q: |#TDS grp:Integer, val:Integer / 1,10 / 1,20 / 2,30 #->groupBy(['grp'], agg('m', x|$x, y|$y->max()))
  [G]  TypedGroupBy :: Relation<(grp:Integer[1], m:(grp:Integer[1], val:Integer[1])[0..1])>[1]
  [SQL] SELECT _tds0.grp, MAX(*) AS m FROM (VALUES ...) AS _tds0(grp, val) GROUP BY _tds0.grp
  [EXEC-ERR] java.sql.SQLException: Binder Error: No function matches the given name and argument types 'max()'
```
The declared column type is a **relation row-struct** (`m:(grp:Integer[1], val:Integer[1])`), which no result
column can ever be, and the emitted `MAX(*)` is not valid SQL on any dialect. (DOC-LIE sub-finding: the comment
asserts a loud death that does not happen at the compiler.)

---

### [CRASH/ICE] Pure collection natives type-check in reduce position, then throw `IllegalStateException` at plan time

**Evidence.** `lowering/Aggregates.java:139-146`:
```java
static com.legend.sql.SqlAgg.Fn reducerFor(TypedFunction callee) {
    com.legend.sql.SqlAgg.Fn name = REDUCERS.get(callee.signatureKey());
    if (name == null) { throw new IllegalStateException("no aggregate lowering registered for resolved overload '" + callee.qualifiedName() + "'"); }
```
The Phase-G checker (`AggregateChecker` / `GroupByChecker` → `checkGeneric` → `Args.aggCols`) never consults
`Aggregates.isReducer`, so anything with a `T[*]` first parameter passes.

**Repro + actual output** (`P16.java`, `->groupBy(~g, ~o:x|$x.n:y|<reduce>)`):
```
$y->last()                       G=Integer[0..1]   PLAN-ERR IllegalStateException: no aggregate lowering registered for resolved overload 'meta::pure::functions::collection::last'
$y->toOne()                      G=Integer[1]      PLAN-ERR IllegalStateException: no aggregate lowering registered ... 'meta::pure::functions::multiplicity::toOne'
$y->makeString(',')              G=String[1]       PLAN-ERR IllegalStateException: no aggregate lowering registered ... 'meta::pure::functions::string::makeString'
$y->at(0)                        G=Integer[1]      PLAN-ERR IllegalStateException: no aggregate lowering registered ... 'meta::pure::functions::collection::at'
$y->sort()->first()              G=Integer[0..1]   PLAN-ERR IllegalStateException: aggregate reducer argument of kind TypedNativeCall is not supported (literals only)
$y->removeDuplicates()->count()  G=Integer[1]      PLAN-ERR IllegalStateException: aggregate reducer argument of kind TypedNativeCall is not supported (literals only)
$y->fold({e,acc|$acc+$e}, 0)     G=Integer[1]      PLAN-ERR IllegalStateException: aggregate reduce must be a native reducer call, got TypedFold
```
`last` is the sharpest: `first()` IS registered (`Aggregates.java:47` → `ANY_VALUE`), so `first`/`last`
asymmetry is invisible until execution.

---

### [CRASH/ICE] `fold` with an element-first non-commutative body type-checks and then dies at plan time

**Evidence.** `compiler/spec/FoldChecker.java:140-155` only accepts `op(elem, acc)` when `op` is proven
commutative for the accumulator's type; everything else falls to `CollectionBuild`
(`FoldChecker.java:83`). `sql/dialect/FoldToListReduce.java:30-34` then refuses:
```java
if (!f.accIsList()) {
    if (!f.homogeneous()) {
        throw new IllegalStateException("fold body is not decomposable and the"
                + " accumulator is scalar — rewrite accumulator-first ...");
```
**Repro + actual output** (`P8.java`):
```
Q: [1,2,3]->fold({e, acc | $e->toString() + $acc}, '')
  [G] String[1]   strategy=CollectionBuild
  [PLAN-ERR] java.lang.IllegalStateException: fold body is not decomposable and the accumulator is scalar ...
```
The mirror-image query `{e, acc | $acc + $e->toString()}` compiles and returns `String(123)`. Also note the
`FoldChecker.java:150-151` commutativity gate tests `init.type() instanceof Type.Primitive`, which
**excludes `Type.PrecisionDecimal`** (a separate record) — a Decimal accumulator can never take the
commutative retry and always lands in this same wall.

---

### [CRASH/ICE] `fold` over an untyped empty literal `[]` with a non-String init produces a raw DuckDB binder error

**Evidence.** `lowering/PureSql.java:193-224` (`typedList`) casts an unknown-typed source to the *element's*
carrier; for a `[]`-born (Nil-typed) source that carrier is VARCHAR
(`lowering/PureSql.java:119-123` (in `PureSql.type`): *"Nil is the BOTTOM type … VARCHAR is the carrier of an always-null column."*).
`FoldToListReduce` then emits `list_reduce(VARCHAR[], (acc,x) -> acc + x, 42)`.

**Repro + actual output** (`P8.java`):
```
Q: []->fold({e, acc | $acc + $e}, 42)
  [G]   Integer[1]   strategy=MapReduce
  [SQL] SELECT list_reduce(coalesce(list_transform(CAST(NULL AS VARCHAR[]), e -> e), []), (acc, __mr_x) -> acc + __mr_x, 42) AS value
  [EXEC-ERR] java.sql.SQLException: Binder Error: The initial value type must be the same as the list child type or a common super type
```
With a String init (`[]->fold({e,acc|$acc+$e}, 'Z')`) the identical shape works and returns `String(Z)`, so the
failure is purely the silent VARCHAR default for the Nil element type.

---

### [CRASH/ICE] A TDS data row with MORE cells than the header throws `IndexOutOfBoundsException`

**Evidence.** `compiler/spec/TdsChecker.java:82-91` pads short rows and never checks long ones:
```java
List<String> row = splitCells(lines[i]);
while (row.size() < names.size()) { row.add(""); }
rows.add(row);
```
`TdsChecker.inferredType` (`:193-200`) then does `row.get(col)` and the lowering indexes by header position.

**Repro + actual output** (`P13.java`):
```
Q: |#TDS a:Integer / 1, 2 #
  [G] [Column[name=a, type=INTEGER, multiplicity=[1]]]
  [PLAN-ERR] java.lang.IndexOutOfBoundsException: Index: 1 Size: 1
```
(A short row is silently NULL-padded — `|#TDS a:Integer, b:Integer / 1 #` gives `b:Integer[0..1]` and
`[ROW] Integer(1) | null` — arguably a silent defaulting of missing data, though the `[0..1]` stamp is honest.)

---

### [UNSOUND] TDS `:Decimal` always declares `Decimal(38,0)` — scale 0 — while the data and results carry scale > 0

**Evidence.** `compiler/spec/TdsChecker.java:169`:
```java
case "Decimal" -> new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, 0);
```
**Repro + actual output** (`P13.java`):
```
Q: |#TDS v:Decimal / 1.25d / 2.50d #
  [G]   [Column[name=v, type=PrecisionDecimal[precision=38, scale=0], multiplicity=[1]]]
  [ROW] BigDecimal(1.25){scale=2}
  [ROW] BigDecimal(2.50){scale=2}
```
Every aggregate over such a column inherits the lie (`plus()` declares `Decimal(38,0)[1]`, returns scale-2).

---

### [SILENT FALLBACK / INFORMATION LOSS] TDS `:Number` annotation is silently narrowed to `Float`

**Evidence.** `compiler/spec/TdsChecker.java:168`: `case "Float", "Number" -> Type.Primitive.FLOAT;`
The class javadoc says an unknown annotated type "fails loudly (engine dropped the silent default-to-String
because it masked Variant bugs)" — but a *known* abstract annotation is silently replaced by a concrete subtype.

**Repro + actual output** (`P13.java`):
```
Q: |#TDS v:Number / 1 / 2 #
  [G]   [Column[name=v, type=FLOAT, multiplicity=[1]]]
  [SQL] SELECT * FROM (VALUES (CAST(1.0 AS DOUBLE)), (CAST(2.0 AS DOUBLE))) AS _tds0(v)
  [ROW] Double(1.0) / Double(2.0)
```
Integral `Number` data becomes `Double` in the emitted VALUES, so `1` round-trips as `1.0`.

---

### [INFORMATION LOSS] Pivot results drop the multiplicity of EVERY column, including statically-known group keys

**Evidence.** `exec/Executor.java:765` uses the two-argument `Column` constructor, which
`exec/Column.java:18-21` documents as *"multiplicity unknown at this construction site"*:
```java
columns.add(new Column(name, pivotColumnType(schema, name, sqlType)));
```
The group-key column's multiplicity IS statically known (it comes straight from `schema.columns()`), but the
branch is entered for all `n` columns.

**Repro + actual output** (`P5.java`; contrast the two runs of the SAME query):
```
-- 2 pivot values present (pivot branch taken)
  [COL] |YR| : Integer mult=null
  [COL] |'NYC__|__total'| : Integer mult=null
  [COL] |'SF__|__total'| : Integer mult=null
-- empty source, 0 pivot values (positional branch taken)
  [COL] |YR| : Integer mult=[0..1]
```

---

### [UNSOUND / INFORMATION LOSS] Pivot rows whose key is NULL are silently dropped — their measures vanish from the result

**Evidence.** `exec/DynamicPivot.java:27-28` and `:77` filter `IS NOT NULL` on the key
(*"NULL keys are skipped: a NULL never names an output column"*); DuckDB's native PIVOT does the same.
Nothing accounts for the discarded rows.

**Repro + actual output** (`P5.java`, DuckDB; total TREES = 150):
```
Rows: (2011,'NYC',100), (2011,NULL,50)
Q: #>{test::DB.T}#->pivot(~CITY, ~total : x|$x.TREES : y|$y->sum())
  [COL] |YR| ; |'NYC__|__total'|
  [ROW] Integer(2011) | BigInteger(100)          <-- the 50 is gone, no column, no row, no error
Rows: (2011,NULL,100), (2011,NULL,50)
  [COL] |YR|
  [ROW] Integer(2011)                            <-- all 150 gone
```

---

### [UNSOUND] Static pivot pins columns that do not exist in the data; their template says `[1]` and the cell is NULL

**Evidence.** `compiler/spec/PivotChecker.java:49-79` records the listed values and builds the dynamic-column
templates from each aggregate's reduce body type/multiplicity; `lowering/Pivots.java:99-115` pre-filters and
emits `PIVOT … IN (v…)`.

**Repro + actual output** (`P5.java`):
```
Q: #>{test::DB.T}#->pivot(~CITY, ['NYC','ZZZ'], ~total : x|$x.TREES : y|$y->sum())
  [G]   dyn templates = [Column[name=total, type=INTEGER, multiplicity=Bounded[lower=1, upper=1]]]
  [SQL] SELECT * FROM (PIVOT ( SELECT * FROM T AS t0 WHERE list_contains(['NYC','ZZZ'], CITY) ) AS t0
                       ON "CITY" IN ('NYC','ZZZ') USING SUM(TREES) AS "_|__total") AS t1
  [COL] |YR| ; |'NYC__|__total'| ; |'ZZZ__|__total'|
  [ROW] Integer(2011) | BigInteger(100) | null
```
The template's `[1]` is what the `->cast(@Relation<(…)>)` idiom concretizes, so the `[1]` lie propagates into
whatever schema the user casts to.

---

### [INCONSISTENCY] `count()` decodes as three different Java classes depending only on the backend

**Evidence / actual output** (`P3.java`, identical query and identical declared type `Integer[1]`):
```
DuckDB: Long(1) ; Long(2)
H2:     Long(1) ; Long(2)
SQLite: Integer(1) ; Integer(2)
```
and `median(Integer)` on a single-row group: DuckDB `Double(30.0)`, H2 `Integer(30)`, SQLite `Integer(30)` —
all declared `Float[1]`. `mode()` on SQLite returns `null` for a *non-empty* group where DuckDB returns the
value (declared `Integer[1]`). `sum(Integer)`: DuckDB `BigInteger`, H2 `Long`, SQLite `Integer`.
(`exec/PureAsserts.java:200-219` (`carrierTypeName`) deliberately maps `Integer|Long|BigInteger -> "Integer"`, so the *name* test
tolerates this; the *value range* does not — see the first finding.)

---

### [CRASH] Aggregates that type-check but have no spelling on the connected dialect surface as raw JDBC/dialect exceptions

**Actual output** (`P3.java`):
```
H2:      first()  -> JdbcSQLSyntaxErrorException: Function "ANY_VALUE" not found
H2:      hashCode() -> DialectCapability: signed 64-bit hashCode reached a dialect without a spelling
SQLite:  first() / percentile() / stdDev() / stdDevPopulation() / variance() / and() / or() / hashCode()
         -> SQLiteException: no such function: ANY_VALUE / QUANTILE_CONT / STDDEV_SAMP / STDDEV_POP /
            VAR_SAMP / BOOL_AND / BOOL_OR / LIST
```
Phase G/I accept all of these; there is no dialect-capability check before rendering for the aggregate lane.

---

### [INFORMATION LOSS] `median(BIGINT)` on SQLite returns a numerically wrong value

**Actual output** (`P3.java`, SQLite, group of `{9223372036854775806, 1}`, declared `Float[1]`):
```
median()   Integer(BIGINT)   Float[1]   Double(-0.5) ; Integer(3)
```
Correct median ≈ `4.6116860184273879e18`. DuckDB returns `4.611686018427388E18` for the same data. The SQLite
median emulation overflows. (Adjacent to scope — reported for completeness, low confidence on the exact
emulation site.)

---

## VERIFIED SOUND

* **`fold` accumulator/lambda-return type checking is enforced, both directions** (`P8.java`):
  `[1,2,3]->fold({e,acc|$e->toString()}, 0)` → `TypeInferenceException: expected Integer, got String`;
  `[1,2,3]->fold({e,acc|$e}, 'z')` → `TypeInferenceException: expected String, got Integer`. This comes from
  the generic signature `fold<T,V|m>(source:T[*], lambda:{T[1],V[m]->V[m]}[1], init:V[m]):V[m]`
  (`builtin/Pure.java:1348`), checked by `Typer.checkGeneric` before `FoldChecker` classifies.
* **`fold` is NOT lowered onto a SQL aggregate — order is preserved and non-associative folds are correct.**
  All strategies emit `list_reduce` (`sql/dialect/FoldToListReduce.java`), a sequential left fold.
  Verified answers (`P8.java`, DuckDB):
  `[1,2,3]->fold({e,acc|$acc-$e},100)` = `Integer(94)` (= ((100-1)-2)-3 ✓);
  `[1,2,3]->fold({e,acc|$e-$acc},100)` = `Integer(-98)` (= 3-(2-(1-100)) ✓, `(acc,e) -> e - acc` in the SQL);
  `['a','b','c']->fold({e,acc|$acc+$e},'')` = `String(abc)` ✓;
  `['a','b','c']->fold({e,acc|$e+$acc},'')` = `String(cba)` ✓;
  `[2,5,10]->fold({e,acc|$acc/$e},1000.0)` = `Double(10.0)` ✓;
  `[2,3,4]->fold({e,acc|$acc*$e},1)` = `Integer(24)` ✓.
  The MapReduce strategy `list_transform` + `list_reduce` also preserves order; the only operand swap
  (`commutativeElementTransform`) is gated to `plus`/`times` on non-String primitives and `and`/`or`, all of
  which are genuinely commutative. **No silent reordering was found.**
* **`fold` over a *typed* empty relation returns the init**: `[1,2,3]->filter(x|$x>100)->fold({e,acc|$acc+$e},42)`
  → `Integer(42)`, `[G] Integer[1]` — correct.
* **Aggregate output-name collision with a group key is rejected cleanly**: `groupBy(~grp, ~grp:…)` →
  `SchemaInvariantException: the column 'grp' already exists in the relation (grp:Integer[1])`.
  Collision with a *non-key* source column (`~val:…`) correctly shadows it (the source column is consumed).
* **A group key referencing a missing column is rejected cleanly**: `groupBy(~nope, …)` →
  `TypeInferenceException: unknown column 'nope' in (grp:Integer[1], val:Integer[1])`.
* **Grouping by a NULLABLE key is honest and correct**: `[G] grp:Integer[0..1]`, NULL forms its own group,
  and the aggregate over it is computed (`P2.java`: `[ROW] null | BigInteger(25)`). Same for a real table
  column (`GRP:Integer[0..1]`, `P4.java`).
* **Grouping by ZERO columns** (`~[]`) yields `Relation<(out:Integer[1])>[1]` and lowers to an ungrouped
  `SELECT SUM(...)` — identical to `aggregate(...)`. Over an empty relation it correctly yields exactly ONE
  row (SQL's ungrouped-aggregate rule), so the *cardinality* is right; only the cell's `[1]` is violated
  (covered above). `groupBy` WITH keys over an empty relation correctly yields **0 rows**.
* **Grouping by an expression** works via `extend` then `groupBy`, and the `GROUP BY` repeats the expression
  (`GROUP BY _tds0.grp + 1`) rather than the alias — correct and portable.
* **The legacy `groupBy` desugars** are correct for the shapes they claim: the arity-4 alias-list form and the
  arity-3 TDS `agg('name', map, agg)` form both produce the modern node with the right schema, and the
  alias-count mismatch is a clean `TypeInferenceException` (`legacy groupBy expects 2 alias(es) …, got 1`).
  The `agg('cnt', x|$x, y|$y->count())` row-count idiom correctly rewrites to `COUNT(*)`.
* **`count()` over a nullable column emits `COUNT(col)`, not `COUNT(*)`** — correct Pure semantics (a Pure
  collection holds no empties, so NULL cells are not counted).
* **`distinct()->count()` inside a reduce lambda** correctly lowers to `COUNT(DISTINCT col)`.
* **`extend(~col:map:reduce)` (TypedExtendAgg)** correctly emits `SUM(col) OVER ()` and the resulting column is
  readable downstream (`->project(~[t:x|$x.tot])`) with the schema intact.
* **`Type.RelationType`'s duplicate-column invariant** is enforced by construction (both `columns` and
  `dynamicColumns`), and `TdsChecker` rejects a duplicate header column with `SchemaInvariantException`.
* **Pivot name mangling survives the awkward values** it was tested with: a value containing the separator
  (`a__|__total` → `'a__|__total__|__total'`, resolved by `lastIndexOf` to template `total`), a value with a
  single quote (`'O'Brien__|__total'`), a value with a double quote (`'a\"b__|__total'`), and a value equal to
  a static group column's name (`'YR__|__total'` alongside `YR`) all decode with the correct template type on
  both DuckDB and H2. An empty source correctly yields the group-key columns only.
* **Pivot dynamic-column TYPES are inherited from the aggregate template, not sniffed** — confirmed for
  `sum` (Integer) and for a two-template pivot where `t1:sum` and `t2:max` produce different declared and
  actual carriers (`BigInteger(150)` vs `Integer(100)`).
* **`corr`/`covarSample`/`covarPopulation`/`maxBy`/`minBy` (rowMapper aggregates) are honestly typed `[0..1]`**
  and decompose correctly into two SQL arguments (`CORR(a,b)`, `ARG_MAX(s,a)`, …).
* **`wavg` composes correctly** to `((1.0 * SUM(v*w)) / SUM(w))` and the WAVG marker never reaches a renderer.
* **`uniqueValueOnly` composes correctly** to `CASE WHEN COUNT(DISTINCT x)=1 THEN MAX(x) ELSE <default> END`,
  and `isDistinct` to `COUNT(DISTINCT x) = COUNT(x)`.
* **`joinStrings` 3-argument form** correctly wraps: `concat('[', STRING_AGG(s, ','), ']')`.
* **`percentile` 4-arg flags** route correctly: `(asc, discrete)` → `QUANTILE_DISC`, `(desc, discrete)` → the
  `LIST`/`list_reverse_sort`/`list_extract` composition; both cast to DOUBLE.
* **Calendar aggregation (`CalendarAgg`)** joins the calendar table twice and emits the transcribed CASE; the
  declared type is `Number[…]` (`builtin/Pure.java:1124-1165` — every calendar native returns `Number[0..1]`),
  which correctly covers both the `BigInteger` (plain `ytd`) and `Double` (`annualized`, which divides) results.
  An untranscribed calendar function raises `NotImplementedException` from `CalendarAgg.condition`, not a
  silent pass-through. Executed end-to-end against a hand-built `LegendCalendarSchema.NY_Calendar` (`P11.java`).
* **`Aggregates.isReducer` is the single membership test** (no parallel name list), and `isDemandReducer`
  correctly excludes `ANY_VALUE` only.
* **`SqlAgg.Reducer`'s canonical constructor recomputes `type` from `SqlTyping.reducerType`** — a caller cannot
  supply a lying type fact. `SqlAgg` correctly makes `RankingFn`/`ValueFn` non-`SqlExpr` so a window-only
  function in a GROUP BY position is a javac error.
* **`TypedAggCol` has no short constructor** (the `orderKey`/`orderAsc` defaulting hazard is genuinely closed),
  and every `withChildren` rebuild in `TypedGroupBy`/`TypedAggregate`/`TypedPivot`/`TypedExtendAgg`/
  `TypedAggColSpec(Array)` re-threads all fields and calls `expectChildren`.
* **TDS type inference** behaves as documented for the cases tested: mixed `21 / 41.14 / 71` widens the whole
  column to Float; an all-`null` column is `String[0..1]`; an unknown annotated type fails loudly
  (`TDS column type 'Blob' is not a known primitive`); a `:Date`-annotated datetime cell keeps the full instant
  (`DateWithSubsecond(2020-01-01T21:00:00.123+0000)`) rather than truncating to midnight.

---

## NOT COVERED

* **`StrictTime` is unreachable in the relation lane.** The TDS literal path dies with
  `IllegalStateException: no TDS cell rendering for Pure type StrictTime` (`lowering/Scalars.java:2968` — a
  lowering gap, not an aggregate one), and `parser/DatabaseProtocolParser.java:366-390` has no `TIME` datatype, so no relational column can
  carry it. The StrictTime row of the requested matrix therefore cannot be produced. `Byte`/`LatestDate`
  columns were likewise not reachable.
* **`Variant` columns** were not put through the aggregate matrix (they are the A-other auditors' territory and
  no aggregate in the catalog accepts them).
* **SQLite/H2 coverage is partial by necessity**: `first`, `percentile`, `stdDev*`, `variance*`, `and`, `or`,
  `hashCode` have no spelling on SQLite, and `first`/`hashCode` none on H2, so their declared-vs-actual rows
  are "dialect error" rather than a value. All of DuckDB's rows are complete.
* **Two pivot values colliding after mangling** could not be constructed: producing a collision requires an
  aggregate template whose *name* contains `__|__`, and the colspec grammar rejects both `~a__|__t:` and
  `~"a__|__t":` (`ParseException: [1:33] expected column name after '~'`). The adjacent reachable case —
  a *group-key column* named with the separator — is reported above as a CRASH.
* **The `n == schema.columns().size()` precedence** in `exec/Executor.java:742` (the positional branch is
  tried before the pivot branch) is a latent mis-decode if a pivot ever produces exactly as many result columns
  as the static schema has. I could only reach that state with **zero** pivoted columns, where the positional
  read is correct. Not reported as a defect — unproven.
* **`orderKey`/`orderAsc` on `TypedAggCol`** (ordered aggregates) — no user-level surface for spelling an
  ordered aggregate colspec was found in the parser, so the ordered path was exercised only through
  `Fold.orderUnionAggregate` reading (not executed).
* **Streaming execution (`Compiler.executeStreaming`) and the wire serializer** were not exercised against the
  overflowing `sum` value; only the materialized `ExecutionResult` path was.
* **`lowering/Fold.java`'s clause-slot predicates** (`groupByFolds`, `windowFolds`, `filterSlot`, …) were read
  in full but only incidentally exercised (through the `groupBy → filter/sort/extend` chains in the probes);
  a dedicated slot-commutation audit is another auditor's scope.

---

## Probe inventory

All probes live in `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a14/`
and run with `/home/user/probe/jrun.sh <file>`:

| file | what it produces |
|---|---|
| `EnumAgg.java` | the 93-entry `Aggregates.REDUCERS` dump + `SqlAgg.Fn` reconciliation |
| `Matrix.java` | 25 aggregates × 9 TDS column types, declared vs actual (`matrix_full.txt`) |
| `P2.java` | overflow, empty groups, nullable keys, zero-column groupBy, name collisions |
| `P3.java` | 18 aggregates × 8 real-table column types on DuckDB / H2 / SQLite (`P3.java <db>`) |
| `P4.java` | Decimal precision overflow + DuckDB `typeof` cross-check |
| `P5.java` | pivot edge cases (separator, quotes, NULL keys, empty, static values) |
| `P6.java` / `P7.java` | pivot ICE, `litText` toString leak, quoted-column egress fallback |
| `P8.java` | fold strategies, order sensitivity, empty source, acc/lambda type checking |
| `P9.java` | legacy groupBy desugars, extend-agg, expression/constant maps |
| `P10.java` | rowMapper aggregates + 4-arg percentile |
| `P11.java` | calendar aggregation end-to-end |
| `P12.java` | overflow harm (`longValue()` truncation) |
| `P13.java` | TdsChecker edges (Decimal scale, Number narrowing, long rows) |
| `P14.java` | exhaustive zero-row `[1]`-violation matrix |
| `P15.java` | scalar-lane vs aggregate-lane empty-identity divergence |
| `P16.java` | unregistered reduce natives |
