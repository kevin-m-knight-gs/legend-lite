# A08 — PURE-TYPE -> SQL-TYPE boundary

Scope: `com.legend.sql.*` (SqlTyping, SqlType, TypeFact, DecodeShapes, SqlExpr, SqlAgg,
SqlFn, DateFmt, SqlSource, SqlSelect, SqlUnion, OutputCol, SqlRewriter), the forward
encoder `lowering/PureSql.java` + `lowering/LayoutTypes.java`, and the inverse
`exec/Executor.java` + `exec/SqlTypeCensus.java`.

`SqlTyping.java` was read in full (1268 lines). It does **not** contain the Pure→SQL
mapping: the actual `Type -> SqlType` function is `lowering/PureSql.type(Type)`
(package-private, `PureSql.java:95-172`), with `LayoutTypes.sqlTypeOf` in front of it for
class layouts. All tables below were produced by RUNNING that code.

Probes used (all under `/tmp/a08`, run with `/home/user/probe/jrun.sh`):
`PureSqlTable.java`, `Compose.java`, `AggMatrix.java`, `ArithMatrix.java`, `RemRepro.java`,
`RewriteType.java`, `VarTypes.java`, `Variants.java`, `CastName.java`, `RT.java`,
`Census.java`. Fixtures under `/tmp/a08/fx`.

---

## 1. THE COMPLETE FORWARD TABLE — `PureSql.type(Type)` (actual run output)

```
=== PRIMITIVES (all 12) ===
Primitive.NUMBER      -> DOUBLE
Primitive.INTEGER     -> BIGINT
Primitive.FLOAT       -> DOUBLE
Primitive.DECIMAL     -> Decimal(38,18)
Primitive.STRING      -> VARCHAR
Primitive.BOOLEAN     -> BOOLEAN
Primitive.BYTE        -> THROWS IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
Primitive.DATE        -> TIMESTAMP
Primitive.STRICT_DATE -> DATE
Primitive.DATE_TIME   -> TIMESTAMP
Primitive.LATEST_DATE -> TIMESTAMP
Primitive.STRICT_TIME -> THROWS IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary

=== PrecisionDecimal (p,s) — IDENTITY, NEVER CLAMPED ===
(0,0)->Decimal(0,0)   (1,0)->Decimal(1,0)   (1,1)->Decimal(1,1)   (5,2)->Decimal(5,2)
(10,2)->Decimal(10,2) (18,6)->Decimal(18,6) (19,0)->Decimal(19,0) (38,0)->Decimal(38,0)
(38,18)->Decimal(38,18) (38,38)->Decimal(38,38) (2,2)->Decimal(2,2) (3,1)->Decimal(3,1)
(9,4)->Decimal(9,4)   (12,12)->Decimal(12,12) (38,6)->Decimal(38,6) (7,0)->Decimal(7,0)
(100,50)->Decimal(100,50)          <-- p>38, unclamped; see F8

=== ClassType ===
Any      -> JSON
Nil      -> VARCHAR
Variant  -> JSON
model::Person            -> THROWS IllegalStateException: no SQL type for Pure class ... (class values do not reach SQL until Phase H lowers their sources)
meta::pure::tds::TDSRow  -> THROWS (same)
meta::pure::tds::TDSNull -> THROWS (same)
  (LayoutTypes.sqlTypeOf front-end: a model class WITH a layout -> Struct(fields...);
   a model class with NO layoutable properties -> Scalar.JSON (silent);
   a class revisited during a property cycle -> Scalar.JSON (silent, cycle guard))

=== EnumType ===
any EnumType -> VARCHAR

=== TypeVar ===
TypeVar(T) -> THROWS IllegalStateException: unresolved type variable T reached the lowering boundary

=== GenericType ===
List<String>                 -> Array(VARCHAR)
List<Integer>                -> Array(BIGINT)
List<List<Integer>>          -> Array(Array(BIGINT))
List<Decimal>                -> Array(Decimal(38,18))
List<PrecisionDecimal(10,2)> -> Array(Decimal(10,2))
List<StrictTime>             -> THROWS IllegalStateException (element wall propagates)
Pair<String,Integer>         -> Struct(first:VARCHAR, second:BIGINT)
Map<String,Integer>          -> Map(VARCHAR,BIGINT)
Map<Decimal,DateTime>        -> Map(Decimal(38,18),TIMESTAMP)
Class<Person> (metaclass)    -> VARCHAR
Relation<(a:Integer[1])>     -> THROWS IllegalStateException: no SQL type for generic Relation<...>
Function<...>                -> THROWS IllegalStateException: no SQL type for generic Function<...>
Foo<Integer> (unknown)       -> THROWS IllegalStateException: no SQL type for generic Foo<Integer>
List<T> (unresolved var)     -> THROWS IllegalStateException: unresolved type variable T

=== RelationType (bare row/schema struct) ===
RelationType[(a:Integer[1])] -> THROWS NotImplementedException: a row value has no SQL carrier yet (Row-vs-Relation split)
RelationType[] (empty)       -> THROWS NotImplementedException (same)

=== FunctionType ===
{->String[1]} -> THROWS IllegalStateException: a function value has no SQL type

=== SchemaAlgebra ===
T+V -> THROWS IllegalStateException: unresolved schema algebra T+V reached the lowering boundary

=== null ===
null -> THROWS java.lang.NullPointerException: null   (bare NPE from the switch's null check)
```

**Types mapping to a "default"/catch-all**: none by `default ->` arm — `PureSql.type` is an
exhaustive sealed switch with no default (verified by reading; a new `Type` variant is a
javac error). But there ARE three *silent* catch-alls upstream of it in `LayoutTypes`:
a layoutless model class → `JSON` (`LayoutTypes.java:84-91`), a property-cycle revisit →
`JSON` (`LayoutTypes.java:56-59`), and `Nil` → `VARCHAR` (`PureSql.java:119-124`).

**Types that throw**: `BYTE`, `STRICT_TIME`, non-carrier `ClassType`, `TypeVar`,
non-carrier `GenericType` (incl. `Relation<...>` and `Function<...>`), `FunctionType`,
`RelationType`, `SchemaAlgebra`, and `null` (bare NPE). 9 of the 12 primitives + 4 of the
9 `Type` variants can reach a throw.

**MANY-TO-ONE COLLAPSES** (and whether decode can undo them — see §2):

| SqlType | Pure types collapsed into it | decode can separate? |
|---|---|---|
| `DOUBLE` | `NUMBER`, `FLOAT` | **NO** — both come back `Float` |
| `TIMESTAMP` | `DATE`, `DATE_TIME`, `LATEST_DATE` | **NO** — all come back `DateTime` |
| `VARCHAR` | `STRING`, every `EnumType`, `Nil`, `Class<X>` metaclass | **NO** — all come back `String` |
| `JSON` | `Any`, `Variant` | **NO** — and JSON has no decode row at all (throws) |
| `Decimal(38,18)` | `DECIMAL`, `PrecisionDecimal(38,18)` | **NO** — both come back `Decimal` |
| `Decimal(p,s)` | every distinct `PrecisionDecimal(p,s)` | **NO** — `(p,s)` is stripped |

---

## 2. THE INVERSE — `Executor.pureOfSqlTypeOrNull` (Executor.java:882-908) and the composition

Actual run output of `Compose.java`:

```
PURE TYPE                     encode           decode(back)      verdict
NUMBER                     -> DOUBLE           -> Float             LOSSY
INTEGER                    -> BIGINT           -> Integer           ROUND-TRIPS
FLOAT                      -> DOUBLE           -> Float             ROUND-TRIPS
DECIMAL                    -> DECIMAL(38,18)   -> Decimal           ROUND-TRIPS
STRING                     -> VARCHAR          -> String            ROUND-TRIPS
BOOLEAN                    -> BOOLEAN          -> Boolean           ROUND-TRIPS
BYTE                       -> ENCODE-THROWS
DATE                       -> TIMESTAMP        -> DateTime          LOSSY
STRICT_DATE                -> DATE             -> StrictDate        ROUND-TRIPS
DATE_TIME                  -> TIMESTAMP        -> DateTime          ROUND-TRIPS
LATEST_DATE                -> TIMESTAMP        -> DateTime          LOSSY
STRICT_TIME                -> ENCODE-THROWS
PrecisionDecimal(10,2)     -> DECIMAL(10,2)    -> Decimal           LOSSY   (p,s dropped)
PrecisionDecimal(38,18)    -> DECIMAL(38,18)   -> Decimal           LOSSY
PrecisionDecimal(5,0)      -> DECIMAL(5,0)     -> Decimal           LOSSY
PrecisionDecimal(38,0)     -> DECIMAL(38,0)    -> Decimal           LOSSY
PrecisionDecimal(18,6)     -> DECIMAL(18,6)    -> Decimal           LOSSY
PrecisionDecimal(1,1)      -> DECIMAL(1,1)     -> Decimal           LOSSY
Any                        -> JSON             -> NO-MAPPING(null)  LOSSY
Variant                    -> JSON             -> NO-MAPPING(null)  LOSSY
Nil                        -> VARCHAR          -> String            LOSSY
EnumType(model::Color)     -> VARCHAR          -> String            LOSSY
Class<Person>              -> VARCHAR          -> String            LOSSY
List<String>               -> VARCHAR[]        -> NO-MAPPING(null)  LOSSY
Pair<String,Integer>       -> STRUCT           -> NO-MAPPING(null)  LOSSY
Map<String,Integer>        -> MAP(VARCHAR,BIGINT) -> NO-MAPPING(null)  LOSSY

--- pureOfSqlTypeOrNull over every SqlType.Scalar spelling + common DuckDB wires ---
  BOOLEAN        -> Boolean          DECIMAL(10,2)            -> Decimal   ((p,s) STRIPPED)
  INTEGER        -> Integer          NUMERIC(5,3)             -> Decimal
  BIGINT         -> Integer          TIMESTAMP WITH TIME ZONE -> NO-MAPPING (loud variant THROWS)
  HUGEINT        -> Integer          TIMESTAMP_NS             -> NO-MAPPING
  DOUBLE         -> Float            INTEGER[]                -> NO-MAPPING
  VARCHAR        -> String           STRUCT(a INTEGER)        -> NO-MAPPING
  DATE           -> StrictDate       MAP(VARCHAR, INTEGER)    -> NO-MAPPING
  TIMESTAMP      -> DateTime         UBIGINT / UINTEGER       -> NO-MAPPING
  TIMESTAMPTZ    -> NO-MAPPING       BLOB / UUID / INTERVAL   -> NO-MAPPING
  JSON           -> NO-MAPPING       TIME / FLOAT4 / BIT      -> NO-MAPPING
  LITERAL        -> NO-MAPPING       VARCHAR(10)              -> String
  TEMPORAL_TEXT  -> NO-MAPPING
```

`pureOfSqlType` (the LOUD variant, used for pivot-generated columns, Executor.java:868-879)
throws `IllegalStateException` for every `NO-MAPPING` row above — so a dynamic pivot whose
aggregate wires JSON, an ARRAY, a STRUCT, a MAP, a TIMESTAMPTZ, a UUID or a BLOB is an
internal error, not a typed result. Also note `HUGEINT -> Integer`: a pivoted `SUM` over
integers decodes to Pure `Integer` while the driver hands back a `BigInteger` (see F5).

---

## FINDINGS

### [UNSOUND] A Pure `String[1]` property over an INTEGER column delivers a `java.lang.Integer`

**Evidence.** `MappingNormalizer.coerceColumnToDeclared` (`MappingNormalizer.java:2416-2420`)
promises: *"String/Boolean-declared over a mismatched column is a WIRE coercion —
castAsDeclared casts at execution"*. But at a `project()` column root the cast is
deliberately stripped: `CastPolicy.cellRootUnwrapWire` (`CastPolicy.java:269-273`)

```java
if (b instanceof TypedCast tc && tc.wire() && tc.target() == Type.Primitive.STRING) {
    return cellRootUnwrapWire(tc.source());
}
```

and the bare read is then TAGGED tolerated (`Lowerer.java:1391-1405`), so
`SqlTyping.reconcileLabels` keeps the VARCHAR label over an INTEGER wire via
`SqlTyping.carryThrough` (`SqlTyping.java:238-244`).

**Repro.** model `/tmp/a08/fx/skew.pure`: `tag: String[1]` mapped to `T_ORDER.TAG INTEGER`;
query `model::Order.all()->project(~[t:o|$o.tag])`.

**Actual output:**
```
[SQL] SELECT t0.QTY AS q, t0.PRICE AS p, t0.TAG AS t FROM T_ORDER AS t0
[LABEL] q  sqlType=DOUBLE   nullable=false tolerated=true
[LABEL] p  sqlType=BIGINT   nullable=false tolerated=false
[LABEL] t  sqlType=VARCHAR  nullable=false tolerated=true
[META]  t  wire=INTEGER  class=java.lang.Integer
[PURE]  q : Float      [PURE] p : Decimal      [PURE] t : String
[ROW]   Integer(3) | Integer(100) | Integer(7) |
```
`t : String` holds `java.lang.Integer(7)`. `q : Float` holds `java.lang.Integer(3)`.
`p : Decimal` holds `java.lang.Integer(100)`.

The SAME property with any operation applied DOES cast:
```
[SQL] SELECT upper(CAST(t0.TAG AS VARCHAR)) AS t FROM T_ORDER AS t0
[ROW] String(7) |
```
so the runtime carrier of a Pure `String[1]` column depends on whether the projection is bare.

**Why it matters.** A downstream host consumer of a `String` cell gets a boxed Integer;
`Float` gets an Integer; `Decimal` gets an Integer. The census is deliberately blind:
```
[CENSUS] ... wire: agree=0 tolerated=2 delivered=1 diverge=0
[CLASS] 1x wire-tolerated[DuckDb] VARCHAR <- INTEGER
[CLASS] 1x wire-tolerated[DuckDb] DOUBLE  <- INTEGER
```

---

### [UNSOUND] `sum()` over a tolerated read: label DOUBLE, wire HUGEINT, value `BigInteger`

**Evidence.** `SqlTyping.reducerType` SUM arm, `SqlTyping.java:853-866`:
```java
yield t0.tolerated()
        ? new TypeFact.Typed(SqlType.Scalar.HUGEINT, true)
        : T_HUGEINT;
```
with the comment *"sum wires DOUBLE while the stamp says HUGEINT; the tag lets the declared
DOUBLE label stand, **which matches the actual wire**"*. That claim is false when the
physical column is an integer type: the wire really is HUGEINT.
`reconcileLabels` then keeps DOUBLE because `carryThrough(DOUBLE, HUGEINT)` is true
(`SqlTyping.java:145-147, 238-244`).

**Repro.** `/tmp/a08/fx/skew.pure` (quantity: Float[1] over `QTY INTEGER`,
price: Decimal[1] over `PRICE INTEGER`), query
`model::Order.all()->groupBy(~[], ~[sq:o|$o.quantity:x|$x->sum(), sp:o|$o.price:x|$x->sum()])`.

**Actual output:**
```
[SQL] SELECT SUM(t0.QTY) AS sq, SUM(t0.PRICE) AS sp FROM T_ORDER AS t0
[LABEL] sq  sqlType=DOUBLE  nullable=false tolerated=true
[LABEL] sp  sqlType=DOUBLE  nullable=false tolerated=true
[META]  sq  wire=HUGEINT  class=java.math.BigInteger
[PURE]  sq : Float     [PURE] sp : Number
[ROW]   BigInteger(7) | BigInteger(300) |
[CENSUS] ... wire: agree=0 tolerated=2 diverge=0
[CLASS] 2x wire-tolerated[DuckDb] DOUBLE <- HUGEINT
[CLASS] 2x tolerated-derived DOUBLE <- HUGEINT
```
A Pure `Float` cell holds a `java.math.BigInteger`. The tripwire counts 0 divergences.

---

### [UNSOUND] `rem()` over decimals: stored fact `Decimal(38,s)`, DuckDB returns DOUBLE

**Evidence.** `SqlTyping.remDecimalType` (`SqlTyping.java:1235-1254`) always yields a
`Decimal(min(w,38), s)` for a decimal-bearing numeric pair. DuckDB 1.5.0 falls back to
DOUBLE whenever the no-carry union shape would saturate precision 38. Exhaustive matrix
(`ArithMatrix.java`, 9 numeric kinds x 9 x 6 operators = 486 cells against a live DuckDB):

```
MISMATCH REM  HUGEINT        DECIMAL(3,1)   declared=DECIMAL(38,1)  wire=DOUBLE
MISMATCH REM  HUGEINT        DECIMAL(10,2)  declared=DECIMAL(38,2)  wire=DOUBLE
MISMATCH REM  HUGEINT        DECIMAL(18,6)  declared=DECIMAL(38,6)  wire=DOUBLE
MISMATCH REM  HUGEINT        DECIMAL(38,2)  declared=DECIMAL(38,2)  wire=DOUBLE
MISMATCH REM  DECIMAL(3,1)   HUGEINT        declared=DECIMAL(38,1)  wire=DOUBLE
MISMATCH REM  DECIMAL(10,2)  HUGEINT        declared=DECIMAL(38,2)  wire=DOUBLE
MISMATCH REM  DECIMAL(18,6)  HUGEINT        declared=DECIMAL(38,6)  wire=DOUBLE
MISMATCH REM  DECIMAL(18,6)  DECIMAL(38,2)  declared=DECIMAL(38,6)  wire=DOUBLE
MISMATCH REM  DECIMAL(38,2)  HUGEINT        declared=DECIMAL(38,2)  wire=DOUBLE
MISMATCH REM  DECIMAL(38,2)  DECIMAL(18,6)  declared=DECIMAL(38,6)  wire=DOUBLE

total=486 mismatches=10 unknown(no-rule)=140
```
PLUS/MINUS/TIMES/MOD/INT_DIVIDE: **0 mismatches** — `decimalArith`'s storage-class cap is correct.

**Repro from Pure source** (`/tmp/a08/fx/rem.pure`, `a: Decimal[1]` over `DECIMAL(18,6)`,
`b: Decimal[1]` over `DECIMAL(38,2)`), query `model::R.all()->project(~[v:r|$r.a->rem($r.b)])`:
```
[SQL] SELECT CASE WHEN t0.B = 0 THEN error(...) ELSE MOD(t0.A, t0.B) END AS v FROM T_R AS t0
[LABEL] v  sqlType=Decimal[precision=38, scale=6]
[META]  v  wire=DOUBLE  class=java.lang.Double
[ROW]   Double(0.75) |
[CENSUS] ... diverge=1
[CLASS] 1x wire[DuckDb] label=DECIMAL(38,6) <> meta=DOUBLE
```
The repo's own wire tripwire (pinned at EQUALITY-0 across the corpus) **fires** on this
shape, so no existing test covers it.

---

### [UNSOUND — NULLABILITY] A `[1]` property over a NULLABLE column delivers `null`; a `[1]` enum column delivers `null` for an unmapped source value

**Evidence.** The SQL label's `nullable` flag comes only from the Pure multiplicity —
`PureSql.nullable(Multiplicity)` (`PureSql.java:241-243`), used at `Lowerer.java:400` and
`:3493`. The store DOES know better: `StoreCompiler.java:108-110` types a column without
`NOT NULL`/`PRIMARY KEY` as `Multiplicity.Bounded.ZERO_ONE`. Nothing reconciles the two at
the mapping seam.

**Repro A** — class declares `sString: String[1]`, `sInteger: Integer[1]`,
`sStrictDate: StrictDate[1]`; the DuckDB row is all NULL
(`/tmp/a08/fx/model.pure` + `/tmp/a08/fx/nul_ddl.sql`):
```
[LABEL] h sqlType=VARCHAR nullable=false   [LABEL] b sqlType=BIGINT nullable=false
[LABEL] d sqlType=DATE    nullable=false
[EXEC-COL] h : String [STRING] mult=[1]
[EXEC-COL] b : Integer [INTEGER] mult=[1]
[EXEC-COL] d : StrictDate [STRICT_DATE] mult=[1]
[EXEC-ROW] null | null | null |
[CENSUS] cols: agree=3 ... bottom-mult-backlog=0 | wire: diverge=0
```

**Repro B** — an EnumerationMapping whose source value is not in the map
(`/tmp/a08/fx/enum.pure`, row `(4, 9)` with `RED:1, GREEN:2, BLUE:3`):
```
[G] type=Relation<(c:model::Color[1])> mult=[1]
[SQL] SELECT CASE WHEN t0.COLOR_CODE = 1 THEN 'RED' ELSE CASE WHEN t0.COLOR_CODE = 2 THEN 'GREEN'
      ELSE CASE WHEN t0.COLOR_CODE = 3 THEN 'BLUE' ELSE NULL END END END AS c FROM T_ITEM AS t0
[LABEL] c  sqlType=VARCHAR  nullable=false
[EXEC-COL] c : model::Color [EnumType[fqn=model::Color]] mult=[1]
[EXEC-ROW] String(RED) | String(GREEN) | String(BLUE) | null |
[CENSUS] cols: agree=1 ... bottom-mult-backlog=0 | wire: agree=1 diverge=0
```
The `SqlTyping.reconcileLabels` N1 rule (`SqlTyping.java:118-128`) only re-marks a slot
nullable when the projection IS a literal `NullLit`; a NULL arriving from inside a CASE's
`otherwise` (the enum decode) or from the database keeps `nullable=false`. `BOTTOM_MULT`
stays 0.

**Why it matters.** The compiler claims exactly-one and the runtime delivers zero. Also the
Pure column type is `model::Color` while the carrier is a bare `java.lang.String` — an
enum cell is indistinguishable from a String cell on the wire (see the asymmetry table).

---

### [UNSOUND] `sum()` of Pure `Integer`s past 64 bits stays Pure `Integer`, delivered as `BigInteger`

**Evidence.** `PureSql.java:72` declares `case INTEGER -> SqlType.Scalar.BIGINT` with the
header claim *"Pure Integer is 64-bit"*. `SqlTyping.reducerType` widens SUM to HUGEINT
(`SqlTyping.java:853-866`), and the Pure result type of `sum()` stays `Integer`.

**Repro** (`/tmp/a08/fx/big.pure`, `N BIGINT` holding `9223372036854775807` twice):
```
[SQL] SELECT SUM(t0.N) AS s, MAX(t0.N) AS m FROM T_BIG AS t0
[LABEL] s  sqlType=HUGEINT   [LABEL] m  sqlType=BIGINT
[META]  s  wire=HUGEINT class=java.math.BigInteger
[PURE]  s : Integer   [PURE] m : Integer
[ROW]   BigInteger(18446744073709551614) | Long(9223372036854775807) |
[CENSUS] cols: agree=2 | wire: agree=2 diverge=0
```
A Pure `Integer` (documented 64-bit) cell holds 2^64-2. No overflow error anywhere.
`SqlTypeCensus`'s own "adopt-pending BIGINT <- HUGEINT" comment (`SqlTypeCensus.java:176-188`)
acknowledges *"integer aggregates legitimately exceed 64 bits"* — but nothing narrows the
Pure type or raises.

---

### [CRASH/ICE] `Byte[1]` over a `VARBINARY`/`BLOB` column: raw `IllegalStateException` at lowering after Phase G accepted it

**Evidence.** `StoreCompiler.columnType` (`StoreCompiler.java:184-185`) maps
`Binary`/`Varbinary` to `Type.Primitive.BYTE`; `PureSql.primitiveCarrier`
(`PureSql.java:91`, `case BYTE, STRICT_TIME -> null`) returns `null` for `BYTE`, and `PureSql.type` throws
(`PureSql.java:96-104`).

**Repro** (`/tmp/a08/fx/byte.pure`, `blob: Byte[1]` over `BLOB_COL VARBINARY(100)`):
```
[G] type=Relation<(b:Byte[1])> mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
[EXEC-ERROR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
```
The model type-checks all the way through Phase G, then dies with a bare
`java.lang.IllegalStateException` — no `LegendCompileException.Phase.LOWER`, no source
position, no property/table name. Same shape for `STRICT_TIME`.

---

### [CRASH/ICE] A store column `DECIMAL(5,10)` throws `IllegalArgumentException` from a record constructor

**Evidence.** `StoreCompiler.java:192` builds `new Type.PrecisionDecimal(d.precision(), d.scale())`
directly from the parsed DDL; `Type.PrecisionDecimal`'s compact constructor
(`Type.java:160-167`, message at :166) rejects `scale > precision`.

**Repro** — change the fixture Database to `S_DECIMAL DECIMAL(5,10)`:
```
java.lang.IllegalArgumentException: scale must be in [0, precision], got scale=10, precision=5
  at com.legend.compiler.element.type.Type$PrecisionDecimal.<init>(Type.java:165)
  at com.legend.compiler.element.StoreCompiler.columnType(StoreCompiler.java:192)
  at com.legend.compiler.element.StoreCompiler.tableSchema(StoreCompiler.java:177)
```
No database/table/column named in the message.

---

### [UNSOUND / RENDER] `PrecisionDecimal(p>38)` is never clamped and produces SQL the backend rejects

**Evidence.** `Type.PrecisionDecimal.MAX_PRECISION = 38` exists (`Type.java:178`) and
`adjust()` clamps derived arithmetic — but `PureSql.type`'s
`case Type.PrecisionDecimal d -> new SqlType.Decimal(d.precision(), d.scale())`
(`PureSql.java:106`) is a pure identity, and `StoreCompiler.java:192` copies the DDL
precision verbatim.

**Repro A** — store declares `S_DECIMAL DECIMAL(50,10)`:
```
[LABEL] a  sqlType=Decimal[precision=50, scale=10]
[META]  a  wire=DECIMAL(30,10)  class=java.math.BigDecimal
```
The plan's label claims a precision SQL cannot express.

**Repro B** — the same type in a CAST (`CastName.java`, real DuckDB):
```
Decimal[precision=50, scale=10]  SELECT CAST(1 AS DECIMAL(50, 10)) AS v  -> DB-ERR (parse failure)
Decimal[precision=0, scale=0]    SELECT CAST(1 AS DECIMAL(0, 0)) AS v    -> DB-ERR (parse failure)
Array[element=Decimal(50,10)]    SELECT CAST(1 AS DECIMAL(50, 10)[]) AS v-> DB-ERR
```
`PrecisionDecimal(0,0)` is constructible (the ctor allows precision 0) and renders
`DECIMAL(0, 0)`, which no backend accepts.

---

### [SILENT FALLBACK] `carryThrough` IS forgiveness-by-kind-pair, contradicting the file's own contract

**Evidence.** `SqlTyping.java:274-276` states:
> *"A (declared, computed) pair that differs and matches none of the named relations ADOPTS
> the wire and goes loud — **there is no forgiveness-by-kind-pair left in the type system**."*

`SqlTyping.carryThrough` (`SqlTyping.java:238-244`) is exactly a kind-pair table:
```java
return (declared == SqlType.Scalar.VARCHAR || declared == SqlType.Scalar.DOUBLE)
        && (computed == SqlType.Scalar.BIGINT || computed == SqlType.Scalar.INTEGER
                || computed == SqlType.Scalar.HUGEINT);
```
The gate is a *tag*, and the tag is stamped at EVERY declared-property/column kind mismatch
(`Scalars.java:674-676` typeAsDeclared; `Lowerer.java:1391-1405` stripped wire cast). Once
tagged, `reconcileLabels` keeps the tolerance even for an EQUAL pair
(`SqlTyping.java:145-155`) and it propagates up through stamped re-reads
(`SqlExpr.Column.of`, `SqlExpr.java:324-328`) and through identity reducers
(`SqlTyping.java:886-887`). Net effect: the label/wire tripwire is permanently muted for
that column at every level. Both UNSOUND findings above ride this arm; both show
`diverge=0`.

---

### [SILENT FALLBACK] `SqlExpr.Column.of(table, outs, name)` silently produces an UNKNOWN-typed column for a name the source does not declare

**Evidence.** `SqlExpr.java:340-345`:
```java
public static Column of(@Nullable String table, List<OutputCol> outs, String name) {
    return outs.stream().filter(c -> c.name().equals(name))
            .findFirst().map(oc -> of(table, oc))
            .orElseGet(() -> new Column(table, name));   // <- UNKNOWN, no error
}
```
**Actual output** (`VarTypes.java`):
```
Column.of(table, outs, MISSING NAME) -> Unknown
```
A misspelled/unresolvable column name becomes a type-less node instead of a loud error;
it then propagates as UNKNOWN through every rule and lands in the census's "untyped"
coverage bucket, indistinguishable from a genuine missing rule.

---

### [INFORMATION LOSS] Written temporal precision survives only at the query ROOT, not in a `project()` column

**Evidence.** `RootLiterals.swap` (`RootLiterals.java:36-50`) rewrites a
`DateWithHour`/`DateWithMinute`/`DateWithSubsecond` literal to a `TEMPORAL_TEXT`-stamped
StringLit, and is called from exactly one site: `Lowerer.java:323` (the egress root).

**Repro — root position:**
```
$ echo '%2020-01-01T10:00:00.000' | ...
[SQL] SELECT '2020-01-01T10:00:00.000' AS value
[LABEL] value sqlType=TEMPORAL_TEXT
[ROW]  DateWithSubsecond(2020-01-01T10:00:00.000+0000)
$ echo '%2020-01-01T10:00' | ...
[ROW]  DateWithMinute(2020-01-01T10:00+0000)
```
**Repro — the same literals inside `project()`:**
```
[SQL] SELECT TIMESTAMP '2020-01-01T10:00:00.000' AS v1, TIMESTAMP '2020-01-01T10:00:00.100' AS v2,
             TIMESTAMP '2020-01-01T10:00:00.123456' AS v3, '2015' AS v4, '2015-04' AS v5,
             TIMESTAMP '2020-01-01T10:00:00' AS v6, TIMESTAMP '9999-12-31T00:00:00.0000' AS v7 ...
[LABEL] v1 TIMESTAMP  v2 TIMESTAMP  v3 TIMESTAMP  v4 TEMPORAL_TEXT  v5 TEMPORAL_TEXT  v6 TIMESTAMP  v7 TIMESTAMP
[ROW] DateWithSecond(2020-01-01T10:00:00+0000)      <- v1: written ".000" LOST
    | DateWithSubsecond(2020-01-01T10:00:00.1+0000) <- v2: written ".100" trimmed to ".1"
    | DateWithSubsecond(2020-01-01T10:00:00.123456+0000)
    | Year(2015) | YearMonth(2015-04)
    | DateWithSecond(2020-01-01T10:00:00+0000)      <- v6: written "T10:00" widened to seconds
    | DateWithSecond(9999-12-31T00:00:00+0000)      <- v7: written ".0000" LOST
```
And in the SAME query the host-folded print of the same literal keeps the precision:
```
[SQL] SELECT '2020-01-01T10:00:00.000+0000' AS a, '2020-01-01T10:00:00.100+0000' AS b, ...
[ROW]  String(2020-01-01T10:00:00.000+0000) | String(2020-01-01T10:00:00.100+0000) | ...
```
So `%2020-01-01T10:00:00.000->toString()` and `%2020-01-01T10:00:00.000` (as a cell) give
two different answers for the same value. The `SqlType.Scalar.TEMPORAL_TEXT` javadoc
(`SqlType.java:22-38`) explicitly lists *"written subsecond digit counts"* as what the
carrier exists to protect; in TDS-column position it is not applied.

**Timezone (verified sound in part):** `%2020-01-01T10:00:00.000+0530` lowers to
`TIMESTAMP '2020-01-01T04:30:00.000'` and returns `DateWithSecond(2020-01-01T04:30:00+0000)`
— the INSTANT is preserved (normalized to UTC, which is Pure's print convention); only the
subsecond digit count is lost. `SqlType.Scalar.TIMESTAMPTZ` is never produced by
`PureSql.type` and has no decode row.

---

### [FORWARD/BACKWARD ASYMMETRY] Six distinguishable Pure-type families collapse irreversibly

Summarised from §1/§2, each verified by running both directions:

| Pure | encode | decode | separable? |
|---|---|---|---|
| `Number` vs `Float` | both DOUBLE | both `Float` | no |
| `Date` / `DateTime` / `LatestDate` | all TIMESTAMP | all `DateTime` | no |
| `String` / `EnumType` / `Nil` / `Class<X>` | all VARCHAR | all `String` | no |
| `Any` / `Variant` | both JSON | **no decode row — throws** | no |
| `PrecisionDecimal(p,s)` | `DECIMAL(p,s)` | `Decimal` — `(p,s)` stripped at `Executor.java:889-893` | no |
| `List<T>` / `Pair` / `Map` | ARRAY / STRUCT / MAP | **no decode row — throws** | no |

The `(p,s)` strip is explicit:
```java
// Executor.java:886-893
String t = sqlType.toUpperCase();
int paren = t.indexOf('(');
if (paren > 0) { t = t.substring(0, paren).strip(); }
```
`DECIMAL(10,2)` → `DECIMAL` → `Type.Primitive.DECIMAL` ≡ `Decimal(38,18)` in the forward
direction. Confirmed live: a `Decimal[1]` property over a `DECIMAL(10,2)` column carries an
`OutputCol` label of `Decimal(10,2)` (the store's precision, adopted by `reconcileLabels`)
but reports Pure type `Decimal` and returns `BigDecimal(123.45)` — the `(10,2)` is only
recoverable from the store, never from the result contract.

---

### [INCONSISTENCY] `SqlRewriter` drops `Lambda.type()`; `SqlExpr.withChildren` preserves it

**Evidence.** Two implementations of the same "rebuild this node with new children" contract:
```java
// SqlExpr.java:184-186 — preserves, with an explicit comment
// supplied-leaf knowledge (the builder's, like Column's type): a body swap keeps it
case Lambda l -> new Lambda(l.params(), cs.get(0), l.type());

// SqlRewriter.java:245-248 — drops it (short ctor stamps UNKNOWN)
case SqlExpr.Lambda l -> {
    SqlExpr b = rewriteExpr(l.body());
    yield b == l.body() ? l : new SqlExpr.Lambda(l.params(), b);
}
```
**Actual output** (`RewriteType.java`):
```
[Lambda] before type=Typed[type=Array[element=BIGINT], tolerated=false]
[Lambda] withChildren(same body)    type=Typed[type=Array[element=BIGINT], tolerated=false]
[Lambda] SqlRewriter (child changed) type=Unknown[]                <-- DROPPED
[Lambda] withChildren(child changed) type=Typed[type=Array[element=BIGINT], tolerated=false]
```
`sql/dialect/FoldToListReduce.java:36,47,50,90,94` also rebuilds Lambdas through the short
ctor. Today every production Lambda already carries UNKNOWN, so the drop is latent — but it
is a divergence between the two traversal contracts and it defeats the M2 lambda stamping
the file's own comments plan for.

**SqlRewriter otherwise preserves or correctly recomputes every annotation** — measured, all
identity or recompute:
```
[Column tolerated]  before=Typed(VARCHAR,tolerated=true)  after=Typed(VARCHAR,tolerated=true)
[Cast(Decimal(10,2),conform=true)] target/conform preserved
[ArrayLit] Array(BIGINT) preserved     [StructLit] declared-field types preserved
[ReduceCollection] HUGEINT preserved   [FoldCall] BIGINT preserved
[Select] outputs preserved
```
`SqlSource.Values.outputs`, `SqlSource.Pivot.outputs`, `SqlSource.Pivot.Using.type`,
`Subselect.alias/frameName`, `SortKey.nullOrder/outputName` and `JsonArrayAgg.Key.desc` are
all threaded explicitly (`SqlRewriter.java:85-111, 226-241, 270-288`).

---

### [INCONSISTENCY] One Pure `Integer` type, three Java carriers

Measured across the aggregate matrix and the round-trip probes: a Pure `Integer` cell is
delivered as `java.lang.Integer` (INTEGER wire), `java.lang.Long` (BIGINT wire) or
`java.math.BigInteger` (HUGEINT wire), decided by the physical column/aggregate, not by the
Pure type. Same for `Decimal`: `BigDecimal` normally, `java.lang.Integer` over a skewed
column, `java.lang.Double` after `rem()`.

---

### [DEAD / INSTRUMENT-BLIND] `SqlTypeCensus.MISMATCH` is structurally unreachable

`SqlSelect`'s compact constructor calls `SqlTyping.reconcileLabels` (`SqlSelect.java:23-33`),
which ADOPTS the computed type into the label whenever the pair differs and matches no named
relation. `SqlTypeCensus.walk` (`SqlTypeCensus.java:442-521`) then compares the SAME
declared/computed pair on the SAME node — after reconciliation has already made them equal
(or tolerated, or subsumed). Demonstrated: a hand-built select declaring BIGINT over an
INTEGER-typed projection comes out of the constructor already rewritten:
```
[Select] outputs before=[OutputCol[name=c, type=INTEGER, nullable=false, tolerated=false]]
```
(the `BIGINT` I passed is gone). Every corpus run therefore reports `mismatch=0` by
construction, not by correctness — the "label-lie census" the class javadoc describes cannot
observe a lie.

---

### [DOC-LIE] `AGENTS.md` §3a "The MIR is closed and pure data" — four wrong counts/claims

`AGENTS.md:205-232` (the claims at :213, :216, :220-222):

| Claim | Actual (measured) |
|---|---|
| ``SqlExpr`` \| **32** variants | **36** — `SqlExpr.class.getPermittedSubclasses().length == 36` |
| ``SqlAgg`` carries `enum Fn` (**~35**) | **41** — `SqlAgg.Fn.values().length == 41` |
| ``SqlType`` \| `Scalar` enum, `Decimal(p,s)`, `Array`, `Map` | 5 variants — **`Struct` is omitted** (`SqlType.java:52-59`) |
| "No MIR record has a `String` field encoding a SQL operation. The single carve-out is `SqlExpr.Cast(expr, **pureTypeName**)` — a *Pure* type name" | `SqlExpr.Cast`'s target is `SqlType target` (`SqlExpr.java:905`) — a **SQL** type, not a Pure type name. The carve-out as written does not describe the code. And the invariant itself is violated — see the exhaustive list below. |

`SqlSource` = 8 variants (claim correct); `SqlQuery` permits `SqlSelect, SqlUnion` (correct).

---

### [DOC-LIE / invariant violation] SQL operations encoded as `String` — the exhaustive list

Every one of the 36 variants' record components was enumerated reflectively
(`Variants.java`). **18** `String`/`List<String>` components exist in total:

```
S Column.table            S Column.name             S Star.table
S StarExcept.table        S StarExcept.except       S StringLit.value
S DateLit.iso             S TimestampLit.iso        S StructLit.Field.name
S StructGet.field         S DeferredTdsString.alias S Lambda.params
S PlanParam.name          S PlanParam.enumMapFn     S RowOrder.table
S TempTableInSplice.tempTableName
S WindowCall.Frame.Bound.IntervalPreceding.unit
S WindowCall.Frame.Bound.IntervalFollowing.unit
```
Of these, the ones that **encode a SQL operation** (verified by reading the renderer that
splices them verbatim):

1. `WindowCall.Frame.Bound.IntervalPreceding.unit` — `AnsiSqlRenderer.java:819-820`
   `"INTERVAL " + p.n() + " " + p.unit() + " PRECEDING"` — a bare SQL interval keyword.
2. `WindowCall.Frame.Bound.IntervalFollowing.unit` — `AnsiSqlRenderer.java:821-822`, same.
3. `PlanParam.enumMapFn` — `EngineStyleH2.java:493` `String fn = p.enumMapFn() + "(" + pn + ")";`
   — a function NAME spliced into plan SQL.
4. `SqlSource.RawSql.sql` — carried SQL text, rendered `"(" + r.sql() + ")"`
   (`AnsiSqlRenderer.java:231`). (Acknowledged in its javadoc as "CARRIED data".)
5. `SqlSource.Join.Kind.sql` — the ANSI join spelling (`"LEFT OUTER JOIN"`, …) is a `public
   final String` on the MIR enum (`SqlSource.java:148-172`, field at :161), rendered by
   `sb.append(j.kind().sql)` (`AnsiSqlRenderer.java:245`). SQL spelling inside the MIR.

Plus a positional convention that is the same violation without a declared field:

6. `SqlExpr.StringLit.value` in argument 0 of `ADD_INTERVAL`, `ADD_INTERVAL_TEMPORAL`,
   `TIME_BUCKET`, `EXTRACT`, `DATE_DIFF` is a **SQL function name or unit keyword** spliced
   bare. `AnsiSqlRenderer.java:632-643` says so outright:
   ```java
   // (unitFn literal, amount, date) — the unit FUNCTION NAME rides
   // as a string literal and renders bare: d + to_years(n).
   case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> opSpelling(expr(a.get(2), 5) + " + "
           + ((SqlExpr.StringLit) a.get(0)).value() + "(" + expr(a.get(1), 0) + ")", parentPrec);
   case TIME_BUCKET -> "time_bucket(" + ((SqlExpr.StringLit) a.get(0)).value() + "(" ...
   ```
   `H2.java:79-88` (`dateadd(` + unit + `)`, `extract(` + unit + ` FROM ...)`),
   `EngineStyleH2.java:1475-1489` (`datediff(" + u.value() + ...`) do the same.

7. **The Lowerer holds DuckDB function names**, violating `AGENTS.md` invariant 2 ("MUST
   NOT … contain SQL syntax or SQL function names"): `lowering/DateShifts.java:63-75`
   ```java
   new SqlExpr.StringLit("to_days"), ...
   case "YEARS" -> "to_years"; case "MONTHS" -> "to_months"; case "WEEKS" -> "to_weeks";
   case "DAYS" -> "to_days"; case "HOURS" -> "to_hours"; ... case "MICROSECONDS" -> "to_microseconds";
   ```
   plus `lowering/CalendarAgg.java:197` (`"to_years"`), `lowering/Scalars.java:3065`
   (`"to_days"`), `lowering/Scalars.java:657-663` (the `date_trunc` unit strings
   `"month"`, `"year"`, `"week"`, `"quarter"`, `"day"`, `"hour"`, `"minute"`, `"second"`).

---

## 3. `SqlExpr` — variant count and which variants carry no type

**36 permitted subtypes** (reflectively verified, not 32):
Column, Star, StarExcept, StringLit, IntLit, FloatLit, DecimalLit, BoolLit, NullLit,
DateLit, TimestampLit, FormatLit, ArrayLit, OrderedListAgg, StructLit, StructGet, Call,
Case, Exists, ScalarSubquery, CheckedOne, CompactList, DeferredTdsString, WindowCall,
Lambda, Cast, FoldCall, JsonObject, JsonArrayAgg, PlanParam, Group, RowOrder,
ReduceCollection, Membership, TempTableInSplice, SqlAgg.Reducer.

**Type carried by each, measured** (`VarTypes.java`, default/typical construction):

```
Column(no stamp)      Unknown      Column(stamped)     Typed(BIGINT)
Star                  Unknown      StarExcept          Unknown
StringLit             Typed(VARCHAR)   IntLit          Typed(BIGINT)
FloatLit              Typed(DOUBLE)    DecimalLit(1.25) Typed(Decimal(3,2))
BoolLit               Typed(BOOLEAN)   NullLit         Bottom
DateLit               Typed(DATE)      TimestampLit    Typed(TIMESTAMP)
FormatLit             Unknown      ArrayLit(empty)     Unknown
ArrayLit(int)         Typed(Array(BIGINT))   OrderedListAgg  Typed(VARCHAR)
StructLit             Typed(Struct(...))     StructGet(untyped src) Unknown
Call(UPPER)           Typed(VARCHAR)   Call(LIST_ZIP: no rule) Unknown
Case                  Typed(BIGINT)    Exists          Typed(BOOLEAN)
ScalarSubquery        Typed(BIGINT)    CheckedOne      Typed(BIGINT)
CompactList           Typed(Array(BIGINT))  DeferredTdsString Typed(VARCHAR)
WindowCall(ROW_NUMBER) Typed(BIGINT)   WindowCall(LAG untyped arg) Unknown
Lambda                Unknown      Cast                Typed(VARCHAR)
FoldCall(agreeing)    Typed(BIGINT)    JsonObject      Typed(JSON)
JsonArrayAgg          Typed(JSON)      PlanParam       Unknown
Group                 Typed(BIGINT)    RowOrder        Typed(BIGINT)
ReduceCollection      Typed(HUGEINT)   Membership      Typed(BOOLEAN)
TempTableInSplice     Unknown      SqlAgg.Reducer(COUNT) Typed(BIGINT)
```

**Variants that force the dialect / the backend to infer a type** (constructor-stamped
UNKNOWN or Bottom, i.e. the IR carries no SQL type for the renderer to consult):
`Star` (`SqlExpr.java:380-382`), `StarExcept` (`:366-369`), `FormatLit` (`:521-526`),
`PlanParam` (`:496-498`), `TempTableInSplice` (`:743-747`) — 5 forced; plus
`NullLit` → `Bottom` (`:443-446`, renders bare `NULL`, the backend resolves it by context);
plus `Lambda` (`:847-854`, parameters are `List<String>` with no types — the backend infers
every lambda parameter type) and `Column` (`:310-315`, UNKNOWN unless the builder stamps it,
and the lookup overload falls back silently — see the finding above). `ArrayLit` of an
empty/all-NULL list is UNKNOWN by rule (`SqlTyping.java:678-684`) and renders `[]`, whose
element type only the backend knows. That is **9 of 36**.

---

## 4. `SqlAgg.Fn` — 41 constants (not "~35"), full result-type matrix vs live DuckDB 1.5.0

`SqlAgg.Fn.values().length == 41`; `marker()` = `[WAVG, HASH_LIST, IS_DISTINCT_MARK,
UNIQUE_VALUE_ONLY, QDISC_DESC]`.

`AggMatrix.java` ran every one of the 41 constants against real DuckDB columns of
BOOLEAN / INTEGER / BIGINT / HUGEINT / DOUBLE / DECIMAL(10,2) / VARCHAR / DATE / TIMESTAMP
(369 cells) and compared `SqlTyping.reducerType(fn, typed(t))` against
`ResultSetMetaData.getColumnTypeName` + the actual value class.

**Result: 0 disagreements on every cell that DuckDB can execute.** Verbatim highlights:

```
ok SUM  BOOLEAN->HUGEINT/HUGEINT/BigInteger    ok SUM INTEGER->HUGEINT/HUGEINT/BigInteger
ok SUM  BIGINT->HUGEINT   ok SUM HUGEINT->HUGEINT   ok SUM DOUBLE->DOUBLE
ok SUM  DECIMAL(10,2)->DECIMAL(38,2)/DECIMAL(38,2)/BigDecimal(69.12)
ok COUNT <every input>->BIGINT/BIGINT/Long
ok AVG  INTEGER/BIGINT/HUGEINT/DOUBLE/DECIMAL(10,2)->DOUBLE ; DATE/TIMESTAMP->TIMESTAMP
ok MIN/MAX/ANY_VALUE/MODE/QUANTILE_DISC/ARG_MAX/ARG_MIN: identity on all 9 kinds
ok MEDIAN INTEGER..DOUBLE->DOUBLE ; DECIMAL(10,2)->DECIMAL(10,2) ; VARCHAR->VARCHAR ;
          BOOLEAN->BOOLEAN ; DATE/TIMESTAMP->TIMESTAMP
ok QUANTILE_CONT INTEGER..DOUBLE->DOUBLE ; DECIMAL(10,2)->DECIMAL(10,2) ; DATE/TS->TIMESTAMP
ok STDDEV_SAMP/POP, VAR_SAMP/POP, VARIANCE, STDDEV, CORR, COVAR_SAMP/POP -> DOUBLE on every
          numeric input
ok STRING_AGG <every input>->VARCHAR ; BOOL_AND/BOOL_OR BOOLEAN->BOOLEAN
ok LIST <T> -> T[]  (BOOLEAN[]/INTEGER[]/BIGINT[]/HUGEINT[]/DOUBLE[]/DECIMAL(10,2)[]/
          VARCHAR[]/DATE[]/TIMESTAMP[]; value class org.duckdb.DuckDBArray)
```

Notes (not defects, but recorded):
* `reducerType` claims DOUBLE for `STDDEV*/VAR*/CORR/COVAR*` and BOOLEAN for
  `BOOL_AND/BOOL_OR` **unconditionally**, including inputs DuckDB rejects
  (`STDDEV_SAMP(DATE)`, `BOOL_AND(VARCHAR)`, …). No value can violate a type for an
  expression that cannot execute, so this is not unsoundness — but the rule is broader than
  the probe that justified it.
* the 5 `marker()` pseudo-reducers are UNKNOWN and error at the backend — correct.
* the 11 window-only kinds return UNKNOWN from `reducerType` and are typed instead by
  `SqlTyping.windowType` (`SqlTyping.java:787-798`): `ROW_NUMBER/RANK/DENSE_RANK/NTILE ->
  BIGINT`, `PERCENT_RANK/CUME_DIST -> DOUBLE`, `LAG/LEAD/FIRST_VALUE/LAST_VALUE/NTH_VALUE ->
  arg0's fact`. Measured against DuckDB: BIGINT/BIGINT/BIGINT/BIGINT, DOUBLE/DOUBLE, and
  element-preserving on all 9 kinds — **all correct**.

---

## 5. `SqlType.Decimal(p,s)` / `Array` / `Map` — construction and preservation

**Construction** — `PureSql.type` builds them structurally and recursively
(`PureSql.java:106, 133-152`): `List<T> -> Array(type(T))`, `Pair<U,V> -> Struct(first,second)`,
`Map<U,V> -> Map(type(U), type(V))`, `PrecisionDecimal(p,s) -> Decimal(p,s)`.
Nesting works: `List<List<Integer>> -> Array(Array(BIGINT))`.
`LayoutTypes.sqlTypeOf` builds `Struct` from a class layout, wrapping many-valued properties
in `Array` (`LayoutTypes.java:69-80`).

**Preservation through the pipeline** (measured end-to-end on DuckDB):
```
newMap([pair(1,2.5),pair(2,3.5)])->keys()
  [SQL] SELECT UNNEST(list_filter(map_keys(map_from_entries([{'first': 1, 'second': CAST(2.5 AS DOUBLE)},...
  [LABEL] value BIGINT   [PURE] Integer   [ROW] Integer(1), Integer(2)
newMap(...)->get(1)
  [LABEL] value DOUBLE   [PURE] Float     [ROW] Double(2.5)
pair(1, 2.5)
  [LABEL] Struct[first:BIGINT, second:DOUBLE]  wire=STRUCT("first" INTEGER,"second" DOUBLE)
  [PURE] Pair<Integer, Float>   [ROW] LinkedHashMap({first=1, second=2.5})
[1.5d, 2.25d]
  [LABEL] Decimal(38,2)  wire=DECIMAL(3,2)  (registered subsumption: same scale, wider label)
```
Map key/value and Struct field types survive `MAP_KEYS`/`MAP_EXTRACT`/`MAP_VALUES`
(`SqlTyping.java:372-381`) and `structGetType` (`:709-723`) correctly.

**`SqlRewriter` preservation** — read in full (306 lines). Every arm either keeps the node
identity or rebuilds it in a way that recomputes the type from the same rule, and the
non-recomputable annotations (`Cast.target`/`conform`, `StructLit.Field.declared`,
`Pivot.Using.type`, `Values.outputs`, `Subselect.frameName`, `SortKey.nullOrder/outputName`,
`CheckedOne.scalarCarrier/atLeastOnly`, `FoldCall.accIsList/homogeneous`,
`JsonArrayAgg.Key.desc`) are all threaded explicitly. **The single exception is the `Lambda`
arm**, reported above.

One structural note (not itself a defect): rebuilding a `SqlSelect` re-runs
`reconcileLabels`, so a pass that changes a projection's type silently re-derives the output
label. Because dialect passes run inside `render()` (`AnsiSqlRenderer.java:82-90`) on a tree
that is then discarded, the `Executor` decodes with the PRE-pass labels — any pass that
changes a wire type would make the decode label stale. No current DuckDB pass
(`CarrierStrategies`, `UnqualifyPivotArgs`, `FoldToListReduce`, `SubstringClamp`,
`RawSqlAdapt`) was observed to do so.

---

## 6. Temporal typing summary

| Pure | SqlType | DuckDB wire | driver object | decoded Pure value |
|---|---|---|---|---|
| `StrictDate` | `DATE` | DATE | `java.time.LocalDate` | `StrictDate(2020-03-04)` |
| `DateTime` | `TIMESTAMP` | TIMESTAMP | `java.sql.Timestamp` (re-fetched as `LocalDateTime`) | `DateWithSubsecond(2020-03-04T05:06:07.891+0000)` |
| `Date` (abstract) over TIMESTAMP | `TIMESTAMP` | TIMESTAMP | `java.sql.Timestamp` | `DateWithSecond(2021-07-08T09:10:11+0000)` |
| `Date` (abstract) over DATE column | `TIMESTAMP` (label) | DATE | `java.time.LocalDate` | `StrictDate(2021-07-08)` — the `subsumes` TIMESTAMP←DATE arm, **sound** (StrictDate ⊑ Date) |
| `LatestDate` | `TIMESTAMP` | — | — | not reachable in a project column: `NotImplementedException: object-space expression node TypedCLatestDate is not substitutable yet` |
| `StrictTime` | **throws** at `PureSql` | — | — | a `StrictTime[1]` property is caught earlier by the mapping type checker (`TypeInferenceException`) — loud, sound |
| partial `%2015` / `%2015-04` | `TEMPORAL_TEXT` | VARCHAR | `String` | `Year(2015)` / `YearMonth(2015-04)` |

`DateFmt` was read in full (81 lines): it is a closed `sealed interface` of 13 `Part`
constants + `Text(String s)`, with 8 fixed format lists. `Text.s` is verbatim literal text,
not a SQL operation — the format never travels as a C-format string, matching its javadoc.
No finding.

**Timezone round-trip:** an instant IS preserved. `%2020-01-01T10:00:00.000+0530` lowers to
`TIMESTAMP '2020-01-01T04:30:00.000'` and returns `DateWithSecond(2020-01-01T04:30:00+0000)`
— offset normalized to UTC at lowering (Pure prints `+0000` by contract). The subsecond
digit-count loss is the separate finding above.

---

## VERIFIED SOUND

* **`PureSql.type` is genuinely exhaustive** over the sealed `Type` and the `Primitive`
  enum: no `default ->` arm, so a new variant is a compile error. Every unsupported kind has
  its own arm with a kind-naming message (verified by reading `PureSql.java:95-172` and by
  running all 9 `Type` variants + all 12 primitives).
* **The aggregate promotion rules are exact.** All 41 `SqlAgg.Fn` constants x 9 input SQL
  types run against live DuckDB 1.5.0: **zero** disagreements between
  `SqlTyping.reducerType`/`windowType` and the actual wire type on every executable cell,
  including `SUM(int)->HUGEINT/BigInteger`, `AVG(int)->DOUBLE`, `COUNT->BIGINT/Long`,
  `SUM(DECIMAL(10,2))->DECIMAL(38,2)`, `MEDIAN(DECIMAL)->DECIMAL(10,2)`,
  `AVG(DATE)->TIMESTAMP`, `LIST(T)->T[]`.
* **`decimalArith` (PLUS/MINUS/TIMES) and `numericPromotion` are exact.** 486-cell matrix
  over 9 numeric kinds x 6 operators against live DuckDB: 0 mismatches on PLUS, MINUS,
  TIMES, MOD, INT_DIVIDE (the 10 failures are all `REM`, reported above). The int64
  storage-class cap at `SqlTyping.java:1011` (`p<=18 && p2<=18 ? 18 : 38`) reproduces
  DuckDB's behaviour exactly, including `(18,0)+(18,0)->(18,0)`.
* **The `subsumes` TIMESTAMP←DATE arm is sound**: an abstract `Date[1]` property over a DATE
  column decodes to `StrictDate`, which is a legitimate inhabitant of Pure `Date`.
* **The `subsumes` same-scale decimal-widening arm is sound**: `[1.5d, 2.25d]` labels
  `Decimal(38,2)` over a `DECIMAL(3,2)` wire and delivers `BigDecimal(1.50)` unchanged.
* **Map / Pair / nested-list carrier types survive the whole pipeline** — key, value, field
  and element types are preserved through `MAP_KEYS`/`MAP_EXTRACT`/`MAP_VALUES`/`StructGet`
  and delivered as `LinkedHashMap`/`List` with the right leaf classes.
* **The `(SqlExpr.StringLit) a.get(0)` unchecked casts in the temporal renderers are guarded
  upstream**: a non-literal `DurationUnit` is rejected with
  `NotImplementedException: a non-literal DurationUnit argument (TypedVariable) is not
  modeled` before reaching the renderer. No ClassCastException reachable from Pure source.
* **`SqlRewriter` preserves every non-recomputable annotation except `Lambda.type()`** —
  measured node by node (`RewriteType.java`).
* **`Executor.unwrap`'s integral-decode guard works**: a scale-0 `BigDecimal` arriving under
  an INTEGER/BIGINT/HUGEINT label decodes to `long`/`BigInteger`, never blurring into
  `Decimal` (`Executor.java:653-660`).
* **`DecodeShapes`** (144 lines, read in full) is pure structural shape analysis with
  `Optional` returns and no null sentinels; `stripDecodes` rebuilds `Call`/`Case`/`Cast`/
  `Group` through their canonical constructors, so types are recomputed, not dropped.
* **`Executor.pureOfSqlType` has no silent String default** — the loud variant throws for an
  unmapped SQL type name, and the audit-15 regression it names (a silent `String` default) is
  genuinely gone.
* **A `StrictTime[1]` property over a VARCHAR column is a clean, typed compile error**
  (`TypeInferenceException: property 'sTime' … expected StrictTime, got String`), not a
  crash.

---

## NOT COVERED

* **H2 / SQLite / DB2 / engine-style dialects.** Every runtime measurement here is DuckDB
  1.5.0 (the fixture runtime and the only executable dialect the probe harness wires up).
  `H2.java`, `EngineStyleH2.java`, `EngineStyleDB2.java` and `CarrierStrategies` were read
  where relevant to the String-splice findings but not executed. In particular the
  `CarrierStrategies.Caps.H2` array→JSON-text carrier substitution and its interaction with
  stale pre-pass output labels is unmeasured.
* **`LayoutTypes`' JSON fallbacks were read, not executed.** A layoutless model class and a
  stored-property cycle both return `Scalar.JSON` silently; I did not build a store/mapping
  that reaches either arm, so I report them as read code, not as a repro'd finding.
* **`SqlTyping.reconcileUnionLabels`** (`SqlTyping.java:180-226`) was read but not exercised
  with a non-uniform-branch union; the "mixed branch types keep the contract" arm is an
  untested silent-keep path.
* **The 140 `UNKNOWN` cells in the arithmetic matrix** (decimal REM/MOD/INT_DIVIDE corners,
  temporal operands) are honest no-rule cells; I did not enumerate what each would need.
* **`SqlFn`'s 200+ constants** were not individually round-tripped — only the ~60 covered by
  `SqlTyping.callType`'s explicit arms were reasoned about, and only the temporal/interval
  family was executed.
* **`PlanParam`/`TempTableInSplice`/`VarSetPlaceholder`/`DeferredTdsString`** are plan-text
  vocabulary and were not executed (they are loud errors in executable dialects by design).
* **Streaming (`Compiler.executeStreaming`) and the graph/JSON envelope root shapes** — all
  measurements used the TABULAR shape.
