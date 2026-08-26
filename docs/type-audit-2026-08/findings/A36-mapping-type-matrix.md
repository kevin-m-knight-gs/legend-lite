# A36 — The mapping property-type / column-type matrix, exhaustively

Scope: the ONE seam where a Pure property's declared type meets a physical SQL column type —
`###Mapping` `prop: [db] TABLE.COLUMN`. Everything below is either source I read in full (quoted
with `file.java:LINE`) or a probe I RAN with its pasted output. Probe sources live in `/tmp/A36/`
(`ColTypes.java`, `Matrix2.java`, `Backends.java`, `Bodies.java`, `Impact.java`, `Census.java`,
`Sqlite2.java`, `ByteIce.java`, `Bool10.java`, `BoolBk.java`, `Extra.java`, `Dbg.java`).
The full matrix TSV is `/home/user/audit/findings/A36-mapping-matrix.tsv` (874 cells + a header
block carrying the raw-JDBC ground truth and the classification legend).

---

## 0. THE COMPLETE ENUMERATIONS (deliverables 1 and 2)

### 0.1 The complete accepted column datatype set — read from the parser, then compiled

The single site is `parser/DatabaseProtocolParser.parseDbType` (`:365-392`), a closed `switch` on
the uppercased first identifier; the `default` arm is
`throw TokenStreamCursor.throwAt(tokens, typeStart, "unsupported column datatype: " + kindWord)`
(`DatabaseProtocolParser.java:390-391`).

I confirmed every spelling by compiling `Database store::D ( Table T ( ID INTEGER PRIMARY KEY, C <T> ) )`
(`/tmp/A36/ColTypes.java`, 100 candidates). **21 spellings are ACCEPTED:**

| spelling | model type (`FromProtocol.dataType`, `:308-355`) | Pure type (`StoreCompiler.columnType`, `:182-209`) |
|---|---|---|
| `INTEGER`, `INT` | `Integer_` | `Integer` |
| `BIGINT` | `BigInt` | `Integer` |
| `SMALLINT` | `SmallInt` | `Integer` |
| `TINYINT` | `TinyInt` | `Integer` |
| `DOUBLE` | `Double_` | `Float` |
| `FLOAT` | `Float_` | `Float` |
| `REAL` | `Real` | `Float` |
| `BIT` | `Bit` | `Boolean` |
| `DATE` | `Date_` | `StrictDate` |
| `TIMESTAMP` | `Timestamp` | `DateTime` |
| `SEMISTRUCTURED`, `JSON` | `SemiStructured` (both — see DOC note below) | `Variant` (ClassType) |
| `OTHER`, `ARRAY` | `Other` (both) | **throws** `ModelException` |
| `VARCHAR(n)` | `Varchar(n)` | `String` |
| `CHAR(n)` | `Char_(n)` | `String` |
| `BINARY(n)` | `Binary(n)` | `Byte` |
| `VARBINARY(n)` | `Varbinary(n)` | `Byte` |
| `DECIMAL(p,s)` | `Decimal(p,s)` | `Decimal(p,s)` |
| `NUMERIC(p,s)` | `Numeric(p,s)` | `Decimal(p,s)` |

Case-insensitive (`integer`, `varchar(10)`, `Timestamp`, `bigint` all ACCEPT). `VARCHAR`/`CHAR`/
`BINARY`/`VARBINARY` REQUIRE exactly one size arg and `DECIMAL`/`NUMERIC` exactly two, else a clean
`ParseException` ("Column data type VARCHAR requires 1 parameter (size) in declaration 'CVARCHAR'").
`INTEGER(10)`, `TIMESTAMP(3)`, `FLOAT(24)` are refused with `Unexpected token '('`.
So there are **18 distinct model datatypes** reachable, and `Distinct`, `Array`, `Object_` in
`model/RelationalDataType.java` are **unreachable from Pure source** (dead variants: `DISTINCT` and
`OBJECT` are rejected words; the `ARRAY` keyword walks to `Other`, never to `Array`).

**The complete REJECTED list** (all with `ParseException: [7:7] unsupported column datatype: X`),
confirmed by compiling each — the orchestrator's `BOOLEAN` finding is one of **44** siblings:

```
BOOLEAN  BOOL  TEXT  CLOB  BLOB  NVARCHAR  NCHAR  TIME  DATETIME  DATETIME2  SMALLDATETIME
TIMESTAMPTZ  TIMESTAMP_NTZ  UUID  MONEY  SMALLMONEY  SERIAL  BIGSERIAL  LONG  LONGTEXT
MEDIUMTEXT  TINYTEXT  VARCHAR2  NUMBER  DEC  FIXED  MEDIUMINT  INT2  INT4  INT8  FLOAT4
FLOAT8  BYTEA  XML  INTERVAL  YEAR  ENUM  SET  STRING  HUGEINT  UTINYINT  USMALLINT
UINTEGER  UBIGINT  BPCHAR  NTEXT  IMAGE  RAW  GRAPHIC  ROWID  GEOGRAPHY  GEOMETRY  VARIANT
OBJECT  MAP  STRUCT  LIST  CHARACTER  BINARY_FLOAT  BINARY_DOUBLE  DISTINCT
```
(`DOUBLE PRECISION` and `CHARACTER VARYING` fail on the second word: `Unexpected token 'PRECISION'`
/ `unsupported column datatype: CHARACTER`.)

**Note the shape of the hole:** there is no `BOOLEAN` and no `TIME` spelling. A user with a real
`BOOLEAN` column must write `BIT`; a user with a `TIME` column has no spelling at all — and
`StrictTime` is consequently unmappable (§1.7).

### 0.2 The complete Pure property type set reachable in a `Class`

All 12 platform primitives (`compiler/element/type/Type.java:74-85`): `String, Integer, Float,
Decimal, Boolean, Byte, Date, StrictDate, DateTime, StrictTime, LatestDate, Number` — all accepted
by the classifier at `[1]` and `[0..1]`. Plus the 10 `meta::pure::precisePrimitives::*` names the
classifier accepts (`Type.java:126-137`: `TinyInt, UTinyInt, SmallInt, USmallInt, Int, UInt, BigInt,
UBigInt` → aliased to `INTEGER`; `Float4, Double` → aliased to `FLOAT`), plus an enum-typed property.
23 property types × 2 multiplicities × 19 store spellings (18 distinct + `ARRAY`) = **874 cells**.

---

## FINDINGS

### [UNSOUND] The matrix: 100 of 874 cells silently return a value that violates the declared Pure type or value; 12 more are an ICE

Counts over the full cross product (`/home/user/audit/findings/A36-mapping-matrix.tsv`,
regenerate with `/home/user/probe/jrun.sh /tmp/A36/Matrix2.java`):

| class | cells | share |
|---|---:|---:|
| LOUD-ERROR (refused at compile) | 628 | 71.9 % |
| SOUND | 108 | 12.4 % |
| **SILENTLY-WRONG-TYPE** | **56** | **6.4 %** |
| **SILENTLY-WRONG-VALUE** | **44** | **5.0 %** |
| LOUD-RUNTIME (compiles; raw `SQLException` on some data) | 26 | 3.0 % |
| **ICE** | **12** | **1.4 %** |
| total | 874 | |

Of the **234 cells that compile and execute at all**, 100 (43 %) are silently unsound and 12 crash;
only 108 are sound. Separately, **all 117 executable `[1]` cells deliver a `null`** (§1.6).

The 56 SILENTLY-WRONG-TYPE cells, exhaustively (each appears at both `[1]` and `[0..1]`):

| declared property | over column | returned Java class (DuckDB) |
|---|---|---|
| `String` | `INTEGER` / `BIGINT` / `SMALLINT` / `TINYINT` | `Integer` / `Long` / `Short` / `Byte` |
| `String` | `DOUBLE` / `FLOAT` / `REAL` | `Double` / `Float` / `Float` |
| `String` | `BIT` | `Boolean` |
| `String` | `DATE` / `TIMESTAMP` | `PureDateLiteral.StrictDate` / `DateWithSecond` |
| `String` | `DECIMAL(10,2)` / `NUMERIC(10,2)` | `BigDecimal` |
| `Integer` | `DOUBLE` / `FLOAT` / `REAL` | `Double` / `Float` / `Float` |
| `Integer` | `DECIMAL(10,2)` / `NUMERIC(10,2)` | `BigDecimal` |
| `Float` | `INTEGER` / `BIGINT` / `SMALLINT` / `TINYINT` | `Integer` / `Long` / `Short` / `Byte` |
| `Decimal` | `INTEGER` / `BIGINT` / `SMALLINT` / `TINYINT` | `Integer` / `Long` / `Short` / `Byte` |
| `Decimal` | `DOUBLE` / `FLOAT` / `REAL` | `Double` / `Float` / `Float` |

The 44 SILENTLY-WRONG-VALUE cells: `Boolean` over each of the 9 numeric column types (18 cells —
every non-zero number becomes `true`), and the 26 precise-primitive **width lies** (§1.5).

**Repro (the flagship cell).** `/tmp/A36/Impact.java`, case C/C2, actual output:

```
--- C. Integer property over DECIMAL: the value keeps its fraction
    model p:Integer[0..1] over store DECIMAL(10,2) (physical DECIMAL(10,2)), rows 123.45,0.99
    SQL   SELECT t0.C AS v FROM TT AS t0
    OUT   BigDecimal(123.45)  | BigDecimal(0.99)  |
--- C2. ... and the fraction survives arithmetic typed as Integer
    query model::C.all()->project(~[v:x|$x.p->toOne() + 1])
    SQL   SELECT t0.C + 1 AS v FROM TT AS t0
    OUT   BigDecimal(124.45)  |
```

`p` is declared `Integer`. `$p + 1` is statically `Integer[1]`. It evaluates to `124.45`.

---

### [UNSOUND] There is NO property/column compatibility check anywhere. The loud arbiter is a GENERIC constructor check that the normalizer's own coercions are built to satisfy (deliverable 6)

I read `MappingNormalizer`, `StoreCompiler`, `ModelIntegrity`, `SqlTyping.tolerateRead` and
`exec/SqlTypeCensus` in full. **There is no code path in the repo that compares a property's
declared type against its mapped column's SQL type and refuses.** Stated unambiguously: no such
guard exists.

What DOES fire on 628 cells is a *generic* check, not a mapping check:

**`compiler/spec/NewChecker.java:94-101`** — the `^Class(prop = value)` conformance check applied to
the *synthesized* class-mapping function `<mapping>$class$<Class>`:

```java
TypedSpec value = t.synth(key.value(), env);
try {
    t.kernel().unify(prop.type(), value.info().type(), new Bindings());   // value must conform
} catch (TypeInferenceException e) {
    throw new TypeInferenceException("property '" + name + "' of '"
            + ni.className() + "': " + e.getMessage() + " (value: " + ... + ")");
}
```
raising via `compiler/spec/InferenceKernel.java:1447`
(`"expected " + formal.typeName() + ", got " + actual.typeName()`).

**Why it doesn't fire on the 100 unsound cells:** phase E rewrites the read so its *static* type
already IS the declared type, so `unify` trivially succeeds. I dumped the synthesized bodies
(`/tmp/A36/Bodies.java`, actual output):

| pair | synthesized `p =` value | what defeats the check |
|---|---|---|
| `String` × `VARCHAR(20)` | `trustOne($row.C)` | nothing to defeat — kinds match |
| `String` × `INTEGER` | `trustOne(castAsDeclared($row.C, @String))` | `castAsDeclared` retypes to `String` |
| `String` × `DATE` | `trustOne(castAsDeclared($row.C, @String))` | same |
| `Integer` × `DECIMAL(10,2)` | `trustOne(typeAsDeclared($row.C, @Integer))` | `typeAsDeclared` retypes to `Integer` |
| `Integer` × `DOUBLE` | `trustOne(typeAsDeclared($row.C, @Integer))` | same |
| `Float` × `INTEGER` | `trustOne(typeAsDeclared($row.C, @Float))` | same |
| `Decimal` × `INTEGER` | `trustOne(typeAsDeclared($row.C, @Decimal))` | same |
| `Boolean` × `INTEGER` | `trustOne(castAsDeclared($row.C, @Boolean))` | `castAsDeclared` |
| `Integer` × `VARCHAR(20)` | `trustOne(parseInteger(trustOne($row.C)))` | `parseInteger` is `String→Integer` |
| `DateTime` × `DATE` | `trustOne(cast($row.C, @DateTime))` | a real cast |
| `StrictDate` × `TIMESTAMP` | `trustOne($row.C)` | **nothing** → LOUD |
| `model::E` × `VARCHAR(20)` | `trustOne($row.C)` | **nothing** → LOUD |
| `Byte` × `BINARY(8)` | `trustOne($row.C)` | kinds match → passes, then ICEs at lowering |

The single normalizer site is **`normalizer/MappingNormalizer.coerceColumnToDeclared` (`:2389-2477`)**.
It never refuses. Its terminal arms:

* `:2401-2402` `if (colKind == null || colKind.equals(declared)) { return read; }` — "column not
  found in the store" and "kinds agree" share one silent arm (also reported by A18).
* `:2416-2419` `if ("String".equals(declared) || "Boolean".equals(declared))` → `castAsDeclared`.
* `:2431-2456` numeric-vs-numeric → `typeAsDeclared` (except `Float`←`Decimal` → `castAsDeclared`).
* `:2462-2474` numeric-declared over a `String` column → `parseInteger`/`parseFloat`/`parseDecimal`.
* `:2476` `return read;` — everything else falls through uncoerced (this is the arm that lets the
  generic `NewChecker` be loud).

And each retyping wrapper lowers to **nothing**:

* `lowering/Scalars.java:674-677` — `typeAsDeclared` lowers to
  `com.legend.sql.SqlTyping.tolerateRead(args.get(0))`, i.e. the argument unchanged.
* `sql/SqlTyping.java:252-258` — `tolerateRead` only flips a bit:
  ```java
  public static SqlExpr tolerateRead(SqlExpr e) {
      return e instanceof SqlExpr.Column c
              && c.type() instanceof TypeFact.Typed t && !t.tolerated()
              ? new SqlExpr.Column(c.table(), c.name(), new TypeFact.Typed(t.type(), true))
              : e;
  }
  ```
* `lowering/Scalars.java:488-490` — `trustOne` lowers to `args.get(0)`: "IDENTITY, no guard".

`compiler/element/ModelIntegrity.java` — the "eager reference-safety pass" — checks *existence and
arity only*. `checkMapping` (`:176-202`) verifies the bound class exists and that the synthesized
function has shape `(): Class[*]`; `checkColumnRef` (`:319-353`) verifies the table and column
*exist*. Neither ever reads `dataType()`. There is no property-mapping walk at all.

`exec/SqlTypeCensus` is explicitly a **counter, not a gate** — its own header says
"Pure measurement beside the comparison layer … nothing here can produce a result or affect
execution", and its only failure path is `catch (SQLException | RuntimeException e) { WIRE_UNKNOWN.increment(); }`
(`:209-220`). Worse, the `tolerated` bit set by `tolerateRead` diverts the mismatch out of the
`MISMATCH` bucket entirely (`SqlTypeCensus.java:483-500`). Proof (`/tmp/A36/Census.java`, actual
output) — the census *sees* the lie, names it, counts it, and executes anyway:

```
census BEFORE: plans=0 cols: agree=0 ... tolerated-origin=0 ... mismatch=0 ...
String[0..1] over INTEGER -> [42]
census AFTER : plans=1 cols: agree=0 subsumed=0 tolerated-origin=1 ... mismatch=0 untyped=0 | wire: agree=0 tolerated=1 ...
  1x wire-tolerated[DuckDb] VARCHAR <- INTEGER
  1x tolerated VARCHAR <- BIGINT
```

---

### [UNSOUND + INCONSISTENCY] A `String`-declared property over a non-string column is a THREE-WAY disagreement: object lane `Integer`, graph lane `"42"`, wire lane `42`

The `castAsDeclared` the normalizer emits for a `String` target is **unwrapped at a projected cell
root** by `lowering/CastPolicy.java:269-273`:

```java
static TypedSpec cellRootUnwrapWire(TypedSpec b) {
    if (b instanceof TypedCast tc && tc.wire()
            && tc.target() == Type.Primitive.STRING) {
        return cellRootUnwrapWire(tc.source());
    }
```
(called from `lowering/Lowerer.java:1393`, which then re-tags the bare read with
`SqlTyping.tolerateRead` so the census stays quiet). So the SQL is a bare column read and nothing
re-imposes `String` at decode.

Repro (`/tmp/A36/Dbg.java`, one model, DuckDB, `C INTEGER` holding `42`, `p: String[0..1]`):

```
proj=[42]
graph=Graph[json=[{"p":"42"}], returnType=ClassType[fqn=model::C]]
wireJson=[{"v":42}]
wireCsv=v
42
```

* `project(~[v:x|$x.p])` → `java.lang.Integer(42)`.
* `graphFetch(#{model::C{p}}#)->serialize(...)` → JSON **string** `"42"`.
* `Compiler.executeWire(..., JSON)` → JSON **number** `42`.

The graph lane honours the declared `String`; the object and wire lanes do not. Same for `String`
over `BIT` (`graph "true"` vs `wireJson true` vs `Boolean(true)`), over `DECIMAL` (`"123.45"` vs
`123.45` vs `BigDecimal`), over `DATE`/`TIMESTAMP`. Full grid in `/tmp/A36/backends.tsv`.

**And within one lane the declared type appears or vanishes depending on the operation applied**
(`/tmp/A36/Impact.java` D–D4, actual output, `p: String[0..1]` over `C INTEGER` = 42):

```
D  ->toUpper()      SQL SELECT upper(CAST(t0.C AS VARCHAR)) AS v            OUT String(42)
D2 ->length()       SQL SELECT length(CAST(CAST(t0.C AS VARCHAR) AS VARCHAR)) OUT Long(2)
D3 ->startsWith('4') SQL SELECT (... starts_with(CAST(t0.C AS VARCHAR),'4')) OUT Boolean(true)
D4 filter(p == '42') SQL SELECT t0.C AS v ... WHERE CAST(t0.C AS VARCHAR) = '42'  OUT Integer(42)
```

D4 is the clearest: the *same* property compares as a `String` in the `WHERE` and is delivered as an
`Integer` in the `SELECT`, in one query.

---

### [UNSOUND] `Decimal` over a `VARCHAR` column silently rounds every value to `DECIMAL(5,2)`

`lowering/Scalars.java:2319-2326` — the non-literal `parseDecimal` arm hardcodes the target:

```java
// NON-LITERAL (column) argument: the scale of the string
// is runtime data SQL cannot carry — the engine's OWN
// 1-arg contract is a hardcoded decimal(5, 2)
return new SqlExpr.Cast(
        SqlExpr.Call.of(SqlFn.RTRIM, args.get(0), new SqlExpr.StringLit("dD")),
        new SqlType.Decimal(5, 2));
```

`Decimal` is the *exact-arithmetic* contract, and this is exactly the mapping a real model uses for
a text-stored amount. Repro (`/tmp/A36/Impact.java` A/A2, actual output):

```
--- A. Decimal property over VARCHAR: hardcoded DECIMAL(5,2) truncates/overflows
    SQL   SELECT CAST(rtrim(t0.C, 'dD') AS DECIMAL(5, 2)) AS v FROM TT AS t0
    OUT   BigDecimal(1.23)  | BigDecimal(124.00)  |          <- stored '1.23456789' and '123.999'
--- A2. same, a value that OVERFLOWS DECIMAL(5,2)
    OUT   ERR java.sql.SQLException: Conversion Error: Could not convert string "12345.67" to DECIMAL(5,2)
```

Any amount ≥ 1000 makes the whole query die with a raw driver exception; anything below is silently
rounded to 2 dp. The declared type carries no precision, so nothing warns.

---

### [UNSOUND] The `meta::pure::precisePrimitives::*` width annotation is a pure lie: 26 cells deliver values outside the declared width

`Type.java:126-137` aliases all eight integer widths to `Primitive.INTEGER` and both float widths to
`Primitive.FLOAT`:
```java
for (String n : new String[]{"TinyInt", "UTinyInt", "SmallInt",
        "USmallInt", "Int", "UInt", "BigInt", "UBigInt"}) {
    m.put(pp + n, INTEGER);
}
m.put(pp + "Float4", FLOAT);
```
so `p: meta::pure::precisePrimitives::TinyInt[1]` over a `BIGINT` column type-checks and the
`ExecutionResult` column reports `Integer`. Actual output (matrix rows, `/tmp/A36/matrix3.tsv`):

```
meta::pure::precisePrimitives::TinyInt  [1] BIGINT   -> Long(9223372036854775807)  WIDTH outside TinyInt range [-128,127]
meta::pure::precisePrimitives::UTinyInt [1] BIGINT   -> Long(9223372036854775807)  WIDTH outside UTinyInt range [0,255]
meta::pure::precisePrimitives::Int      [1] BIGINT   -> Long(9223372036854775807)  WIDTH outside Int range [-2147483648,2147483647]
meta::pure::precisePrimitives::Float4   [1] DOUBLE   -> Double(1.7976931348623157E308) WIDTH outside Float4 range
```

Additionally **INCONSISTENT**: `MappingNormalizer.declaredPlatformKind` (`:2330-2349`) only
recognises the bare spelling or `meta::pure::metamodel::type::*`, so a precise primitive gets **no**
coercion at all — `precisePrimitives::Int` over a `VARCHAR` is refused
(`expected Integer, got String`) where plain `Integer` over the same column silently emits
`parseInteger`. Two spellings of the same type, two different mapping behaviours.

---

### [CRASH/ICE] `BINARY`/`VARBINARY` columns are unusable: the parser accepts them, `StoreCompiler` types them `Byte`, and the lowering boundary throws `IllegalStateException`

12 matrix cells are an ICE, all of this shape. It is not mapping-specific — the store-relation lane
crashes too. Repro (`/tmp/A36/ByteIce.java`, actual output):

```
java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
	at com.legend.lowering.PureSql.type(PureSql.java:100)
	at com.legend.lowering.LayoutTypes.sqlTypeOfUnguarded(LayoutTypes.java:79)
	at com.legend.lowering.Lowerer.outputsOf(Lowerer.java:3492)
	at com.legend.Compiler.plan(Compiler.java:333)
relation-lane ERR java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
```

`lowering/PureSql.java:91` is the cause: `case BYTE, STRICT_TIME -> null;`, and `:100` turns the null
into an internal `IllegalStateException` rather than a user-facing compile error. So the ONLY
type-correct pairing for a binary column (`Byte` property over `BINARY`) is the one that crashes.

---

### [UNSOUND] Every `[1]` property over a nullable column delivers `null` — 117 of 117 executable `[1]` cells, including the perfectly-matched diagonal

`MappingNormalizer` wraps every column read in `trustOne` to satisfy the `[1]` conformance check
(`NewChecker.java:112-125` explicitly documents this: "Synthesized mapping bodies conform by
EMISSION: MappingNormalizer wraps store reads bound to [1] properties in toOne(...)"), and
`trustOne` lowers to the identity with **no guard** (`Scalars.java:488-490`). Nothing checks that the
column is `NOT NULL`.

Every one of the 117 `[1]` cells that executes reports `RESULT_COL_TYPE = <T> mult=[1]` and returns
`null` for the NULL row (column `MULT_VIOLATION = NULL-UNDER-[1]` in the TSV; the distribution is
`117x null`, zero exceptions). Control (`/tmp/A36/Extra.java`): declaring the column
`C INTEGER NOT NULL` makes the same cell sound (`NOT NULL INTEGER, Integer[1] -> [42] col mult=[1]`),
so this is exactly "nullable column ↔ `[1]` property, unchecked".

---

### [INCONSISTENCY / BACKEND DIVERGENCE] The same model gives OPPOSITE Boolean answers on SQLite; and `Boolean` over a real `BIT` column — a MATCHING pairing — is unsound there

`Boolean` coercion is delegated to the dialect as a literal `CAST(x AS BOOLEAN)`. The rendered SQL is
**byte-identical on all three backends** (`/tmp/A36/backends.tsv`) — but SQLite has no BOOLEAN type,
so the cast is a NUMERIC-affinity no-op and the decoder (`exec/Executor` `case null, default -> v`)
hands the raw driver object straight through. Actual output (`/tmp/A36/BoolBk.java`):

```
  DuckDB  Boolean over CHAR(1)   value 'Y'   -> Boolean(true)
  H2      Boolean over CHAR(1)   value 'Y'   -> Boolean(true)
  SQLite  Boolean over CHAR(1)   value 'Y'   -> Integer(0)          <- opposite truth value
  DuckDB  Boolean over CHAR(1)   value 'N'   -> Boolean(false)
  SQLite  Boolean over CHAR(1)   value 'N'   -> Integer(0)
  DuckDB  Boolean over INTEGER   value 2     -> Boolean(true)
  SQLite  Boolean over INTEGER   value 2     -> Integer(2)
```

And the *correct* pairing is unsound on SQLite even with no class mapping involved
(`/tmp/A36/Sqlite2.java`, actual output):

```
Boolean      BIT   phys=BOOLEAN  rawJdbc=Integer(1) -> Integer(1)   | SELECT t0.C AS v FROM TT AS t0
== control: a store-relation query, no class mapping involved ==
  col C : Boolean
  row -> Integer(1)
```

**Cells whose outcome DIFFERS by backend** (full grid `/tmp/A36/backends.tsv`, 46 cells × 3 backends):

| cell | DuckDB | H2 | SQLite |
|---|---|---|---|
| `Boolean` × `BIT` (matching!) | `Boolean(true)` SOUND | `Boolean(true)` SOUND | **`Integer(1)` WRONG-TYPE** |
| `Boolean` × any of 9 numerics | `Boolean(true)` | `Boolean(true)` | **raw `Integer`/`Double`** |
| `Boolean` × `CHAR(1)` `'Y'`/`'N'` | `true`/`false` | `true`/`false` | **`0`/`0`** |
| `String`/`Float`/`Decimal` × `SMALLINT` | `Short(42)` | `Integer(42)` | `Integer(42)` |
| `String`/`Float`/`Decimal` × `TINYINT` | `Byte(42)` | `Integer(42)` | `Integer(42)` |
| `Decimal` × `DECIMAL(10,2)` (matching!) | `BigDecimal(123.45)` | `BigDecimal(123.45)` | **`Double(123.45)`** |
| `String` × `BIT`, GRAPH lane | `"true"` | **`"TRUE"`** | graph lane unsupported |
| `String` × `TIMESTAMP`, WIRE lane | `"2024-01-15 10:30:00"` | **`"2024-01-15T10:30:00"`** | n/a |
| every cell, GRAPH lane on SQLite | works | works | **`SQLiteException: no such function: list`** |

`Decimal` × `DECIMAL` on SQLite returning `Double` is a second matching-pairing unsoundness: an
exact-decimal contract delivered as a binary float.

---

### [INCONSISTENCY] The GRAPH lane and the WIRE lane disagree on temporals even where the cell is SOUND

Same model, same row, `p: DateTime[0..1]` over a `TIMESTAMP` column holding `2024-01-15 10:30:00`:

| lane | DuckDB | H2 |
|---|---|---|
| graph JSON | `{"p":"2024-01-15T10:30:00.000000000"}` | `{"p":"2024-01-15T10:30:00.000000000"}` |
| wire JSON | `{"v":"2024-01-15 10:30:00"}` | `{"v":"2024-01-15T10:30:00"}` |

Three different ISO spellings of one instant across two lanes and two dialects. And the graph lane's
spelling depends on the *declared property type*: the same TIMESTAMP column under a `String`-declared
property serializes as `"2024-01-15 10:30:00"` on DuckDB but `"2024-01-15 10:30:00"` / (wire)
`"2024-01-15T10:30:00"` on H2. Evidence: `/tmp/A36/backends.tsv` columns `GRAPH_JSON`/`WIRE_JSON`.

---

### [UNSOUND] `Decimal` over `DOUBLE` delivers binary-float error under an exact-arithmetic contract

`/tmp/A36/Impact.java` G/G2, actual output:

```
--- G. Decimal property over DOUBLE
    SQL   SELECT t0.C AS v FROM TT AS t0                       OUT Double(0.1)
--- G2. ... and the exactness claim is visible in arithmetic
    query model::C.all()->project(~[v:x|$x.p->toOne() * 3])
    SQL   SELECT t0.C * 3 AS v FROM TT AS t0                   OUT Double(0.30000000000000004)
```
Declared `Decimal`; the value is a `java.lang.Double` and `0.1 * 3` is `0.30000000000000004`.
Related: `Decimal` over `INTEGER`, `$p / 2` → `SELECT ((1.0 * t0.C) / 2)` → `Double(3.5)`; and
`sum()` over an `Integer`-declared property backed by `DECIMAL(10,2)` holding `1.5, 1.5` →
`BigDecimal(3.00)` (`/tmp/A36/Impact.java` K, L).

---

### [SILENT FALLBACK] A `LOUD-RUNTIME` class: 26 cells compile clean and then die on a raw driver `SQLException` that depends on the data

`Integer`/`Float`/`Decimal`/`Number`/`Boolean` over `VARCHAR`/`CHAR`, `Boolean` over
`DATE`/`TIMESTAMP`/`SEMISTRUCTURED`. These are not compile errors — the model is accepted and the
plan renders. Whether the query works depends entirely on the rows present:

```
--- I. Integer property over VARCHAR: one bad row kills the whole query
    rows '1','2','n/a'   SQL SELECT CAST(t0.C AS BIGINT) AS v FROM TT AS t0
    OUT   ERR java.sql.SQLException: Conversion Error: Could not convert string 'n/a' to INT64 ...
```
The exception is the raw JDBC `SQLException` — no Legend frame, no property/class named, no
indication that the *model* is what is wrong.

---

### [UNSOUND] The `EnumerationMapping` route silently NULLs an unmatched source value

The one legitimate enum path (a direct enum-to-column mapping is loud on all 19 column types).
`/tmp/A36/Census.java`, actual output:

```
  store VARCHAR(10) value 'a'
     SQL SELECT CASE WHEN t0.C = 'a' THEN 'A' ELSE CASE WHEN t0.C = 'b' THEN 'B' ELSE NULL END END AS v
     OUT java.lang.String(A)  declaredCol=model::E
  store VARCHAR(10) value 'zzz'
     OUT null                                   <- unmapped source value, silently NULL
  store INTEGER value 1
     SQL SELECT CASE WHEN t0.C = 'a' THEN 'A' ...
     OUT ERR SQLException: Conversion Error: Could not convert string 'a' to INT32
```
Note also: the enum carrier at the wire is a bare `java.lang.String` while the column's Pure type is
`model::E`; and nothing checks that the enumeration's source literals are comparable to the mapped
column's type (the `INTEGER` case is a raw driver error).

---

### [DOC-LIE] `MappingNormalizer.java:2412-2415` claims the `String` wire coercion "casts at execution". It does not.

```java
// String/Boolean-declared over a mismatched column is a WIRE
// coercion — castAsDeclared casts at execution but the
// engine-text funnel reads the expression bare ...
if ("String".equals(declared) || "Boolean".equals(declared)) {
    return new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, ...
```
`lowering/CastPolicy.java:269-273` unwraps precisely the `String`-target wire cast at a projected
cell root, so at execution there is no cast: `SELECT t0.C AS v` and `java.lang.Integer(42)` come
back (`/tmp/A36/Dbg.java`). The `Boolean` half of the same arm *does* cast; the `String` half does
not. `CastPolicy.java:35-47` even records that this "was applied at the PROJECTION boundary … and
rejected three ways", with "The SOUND seam is the PROPERTY-READ PAIRING … the next attempt builds
THERE" — i.e. the code itself documents that the check this audit is looking for was planned and
never built.

Two lesser doc-lies:
* `normalizer/RelationalKinds.java` header claims exhaustiveness fixed the "null meant BOTH column
  not found and variant unmapped" conflation; `MappingNormalizer.java:2401-2402` still folds
  `colKind == null` (not found) into the same silent arm as `colKind.equals(declared)`.
* `model/RelationalDataType.java:19-22` lists a `Bool` variant in its javadoc; there is no `Bool` in
  the `permits` clause and no `BOOLEAN` spelling in the parser.

---

## RANKED BY REAL-WORLD LIKELIHOOD — top 10 impact notes (deliverable 7)

1. **`Integer` property over a `DECIMAL`/`NUMERIC` column** (SILENTLY-WRONG-TYPE, all backends).
   The single most common modelling slip: a warehouse stores quantities/counts as `NUMERIC(18,0)`
   or `DECIMAL(10,2)` and the model calls them `Integer`. Phase E emits `typeAsDeclared`, which
   lowers to nothing; the SQL is a bare read; the value arrives as `java.math.BigDecimal`. Every
   downstream consumer that believes the static type — a Java client casting to `Long`, a JSON
   consumer expecting an integral token, an `if ($x.qty == 5)` comparison, `->toString()` — sees a
   scaled decimal. Worst of all the arithmetic stays fractional: `$p + 1` where `p: Integer[1]`
   returned `BigDecimal(124.45)` in my run. Nothing in the pipeline ever mentions the mismatch
   except a census counter that is designed not to fail.

2. **`String` property over an `INTEGER`/`BIGINT` code column** (SILENTLY-WRONG-TYPE, all backends,
   and a three-way lane disagreement). Modelling an account/product/CUSIP code as `String` while the
   column is numeric is standard practice, precisely because leading zeros and arithmetic must be
   suppressed. legend-lite emits `castAsDeclared(@String)` and then *unwraps it* at the projection
   cell root (`CastPolicy.java:269-273`), so the object lane returns `java.lang.Integer`. The REST
   consumer sees `{"v":42}` (a JSON number) from the wire lane but `{"p":"42"}` (a JSON string) from
   the graph lane — the same field, the same model, two payload shapes. A client that round-trips a
   code through the wire lane loses leading zeros and can overflow at 2^53 in JavaScript.

3. **`Boolean` property over a `CHAR(1)` `'Y'/'N'` flag** (SOUND on DuckDB/H2, **inverted on
   SQLite**). This is *the* legacy-schema pattern, and the DSL forces it: `BOOLEAN` is a rejected
   column datatype, so the store must say `CHAR(1)`. The coercion is a bare `CAST(C AS BOOLEAN)`
   delegated to the dialect. On SQLite, `'Y'` and `'N'` **both** become `Integer(0)` — every record
   reads as "not flagged", the query returns no error, the numbers are simply wrong. Portability of
   a model across backends is a first-class Legend promise; here it silently changes the answer.

4. **`Boolean` property over an `INTEGER`/`TINYINT` 0/1 flag** (SILENTLY-WRONG-VALUE on
   DuckDB/H2, SILENTLY-WRONG-TYPE on SQLite). The second-most-common flag encoding. On DuckDB/H2
   every non-zero collapses to `true`, so a tri-state column (`0`/`1`/`2 = unknown`) silently loses
   its third state and `-1` sentinels read as `true`. On SQLite the raw `Integer` comes back under a
   declared `Boolean`, so `$x.flag == true` is false for every row while `$x.flag` renders as `1`.

5. **`Date`-family property over a `VARCHAR` date column** — refused, but only by accident, and the
   refusal is unhelpful. `Date`, `StrictDate`, `DateTime`, `LatestDate` over `VARCHAR`/`CHAR` all
   fail with `TypeInferenceException: … expected StrictDate, got String (value: AppliedProperty[receiver=Variable[name=row, type=null, …]…)`.
   The message names an internal AST node, not the mapping line, and the coercion table has arms for
   `String`→numeric parsing but none for `String`→temporal. Since the DSL has no `TIME` and no
   `DATETIME` spelling, text-stored temporals are extremely common in the wild and there is no
   supported way to map them.

6. **`Integer`/`Float`/`Decimal` property over a `VARCHAR` numeric column** (LOUD-RUNTIME).
   Compiles clean, plans clean, and then dies at execution the first time a row holds `'n/a'`,
   `''`, or `'-'`. The failure is a raw `java.sql.SQLException` from the driver with no Legend
   context — an operator sees "Conversion Error: Could not convert string 'n/a' to INT64" and has no
   pointer back to the property mapping that caused it. One dirty row takes down the entire report.

7. **`Decimal` property over a `VARCHAR` amount column** (SILENTLY-WRONG-VALUE, then LOUD-RUNTIME).
   Worse than (6): before it fails it silently *rounds*. `Scalars.java:2326` hardcodes
   `DECIMAL(5,2)`, so `'1.23456789'` becomes `1.23` and `'123.999'` becomes `124.00` with no
   warning, and any amount ≥ 1000 throws. A money model reading a text-stored amount is both
   silently wrong for small values and hard-down for large ones — the worst possible combination.

8. **`Float` property over an `INTEGER`/`BIGINT` column** (SILENTLY-WRONG-TYPE). "Declare it Float,
   it's a measure" over an integral column is routine. `typeAsDeclared` is identity, so a
   `java.lang.Long` arrives under a `Float` contract. For values above 2^53 the consumer that
   *does* coerce to `double` loses precision that the Long carried
   (`9007199254740993` came back as `Long`, `/tmp/A36/Impact.java` F); for values below, integer
   division semantics can differ from float semantics in any downstream Java that switches on the
   runtime class. The `precisePrimitives::Float4` variant additionally claims 4-byte width while
   delivering `1.797e308`.

9. **`Decimal` property over a `DOUBLE` column** (SILENTLY-WRONG-TYPE, plus exactness loss).
   `Decimal` is chosen specifically to promise exact arithmetic. Over a `DOUBLE` column the read is
   the identity, the carrier is `java.lang.Double`, and `$p * 3` on a stored `0.1` returned
   `0.30000000000000004`. A financial model that switched a column to `DOUBLE` for storage reasons
   silently loses its exactness guarantee everywhere, with the type system still asserting it.

10. **Any `[1]` property over a nullable column** (multiplicity unsoundness, 117/117 cells,
    including every perfectly-matched pairing). Declaring `id: String[1]` over a column that is
    merely `VARCHAR(50)` (no `NOT NULL`) is what almost every model does — Legend's `[1]` is a
    modelling assertion, not a schema fact. `trustOne` lowers to the identity with no guard, so a
    `NULL` row flows straight through: the `ExecutionResult` column says `mult=[1]` and hands back
    `null`. Every consumer that trusts `[1]` — non-null Java field, required JSON member, `->toOne()`
    downstream — gets a null it was statically told could not exist. `Integer` over `BIGINT` with
    `9223372036854775807` (item E in `/tmp/A36/Impact.java`) is by contrast genuinely SOUND —
    legend-lite's Pure `Integer` is 64-bit — so "Integer over BIGINT > 2^31" is *not* a defect here;
    the `[1]`/null hole underneath it is.

---

## VERIFIED SOUND

* **108 cells are genuinely sound on DuckDB** (type-correct AND value-faithful for both the
  representative and the edge value). The complete sound pairing set, verified cell by cell:

  | property | sound over |
  |---|---|
  | `String` | `VARCHAR(n)`, `CHAR(n)`, `SEMISTRUCTURED`/`JSON` |
  | `Integer` | `INTEGER`/`INT`, `BIGINT`, `SMALLINT`, `TINYINT` (incl. 2^63−1 → `Long`) |
  | `Float` | `DOUBLE`, `FLOAT`, `REAL`, `DECIMAL(p,s)`, `NUMERIC(p,s)` (the last two via a real `CAST(… AS DOUBLE)`) |
  | `Decimal` | `DECIMAL(p,s)`, `NUMERIC(p,s)` |
  | `Boolean` | `BIT` (DuckDB/H2 only — see the SQLite finding) |
  | `StrictDate` | `DATE` |
  | `DateTime` | `TIMESTAMP`, and `DATE` via a real `CAST(… AS TIMESTAMP)` widening to midnight |
  | `Date` (abstract) | `DATE` → `StrictDate`, `TIMESTAMP` → `DateWithSecond` (both legal `Date` subtypes) |
  | `Number` (abstract) | all 9 numeric column types |
  | `precisePrimitives::*` | any column whose width is ≤ the declared width (e.g. `TinyInt`×`TINYINT`) |

* **The generic conformance check IS loud and IS well-targeted where it fires**: 628 cells are
  refused at compile time naming the property, the class and both types
  (`property 'p' of 'model::C': expected Integer, got Float`), including every enum-over-column
  pairing (38 cells), every `StrictTime`/`LatestDate` pairing (76 cells — those two types are
  unmappable to any of the 21 accepted column datatypes), `StrictDate` over `TIMESTAMP`, `Byte` over
  anything non-binary, and every property over `OTHER`/`ARRAY` (92 cells,
  `ModelException: SQL column type 'Other' has no scalar Pure type`).
* **The parser's datatype refusal is clean and complete**: 64 common SQL spellings rejected with a
  positioned `ParseException`, no fallback, no silent coercion to a nearby type. Arity refusals for
  the sized/precise kinds are equally clean.
* **`ModelIntegrity` existence checks are sound** for what they cover: unknown table, unknown column,
  unknown class, unknown association, wrong synthesized-function shape all refuse loudly with the
  site named.
* **`Compiler.plan`/`execute` never silently swallowed an exception** in 874 × 3 executions — every
  failure surfaced as a throwable I could classify.
* **`INT`≡`INTEGER`, `ARRAY`≡`OTHER`, `JSON`≡`SEMISTRUCTURED`** confirmed identical model-side by
  execution, not by reading (`/tmp/A36/Extra.java`).
* **A `NOT NULL` column makes the `[1]` cell sound** — verified, so the multiplicity hole is exactly
  "nullable column under a `[1]` property".

## NOT COVERED

* **Non-DuckDB coverage is targeted, not exhaustive.** The 874-cell matrix ran on DuckDB. SQLite and
  H2 were run over the 46 cells that matter (all 28 distinct SILENTLY-WRONG-TYPE pairs, all 9
  `Boolean` SILENTLY-WRONG-VALUE pairs, 3 width-lie pairs, and 6 sound controls) — the
  divergence table above. A full 874×3 sweep would need per-backend DDL/literal tables
  (SQLite rejects `DATE '…'` literal syntax; H2 aliases `FLOAT`→`DOUBLE`).
* **The graph and wire lanes** were run over those same 46 cells, not all 874.
* **Sized-type boundaries**: I did not test `VARCHAR(3)` holding a 10-char value, or
  `DECIMAL(4,1)` holding a wider physical value — i.e. whether the *declared size* in the store DSL
  is enforced against the physical column at all. Given no type check exists, the size almost
  certainly is not checked either, but I did not run it.
* **Embedded / inline-embedded / union / M2M property mappings** — only the plain
  `prop: [db] TABLE.COL` form and `EnumerationMapping` are in the matrix. A18 covers the other
  coercion entry points (`declaredAssertion` at `MappingNormalizer.java:2363`, the JoinTerminalColumn
  path); I did not re-derive them.
* **Views and milestoned tables** as the mapped source.
* **The `~groupBy` / `~distinct` / `~filter` interaction** with a mismatched declared type (A18
  reports the declared-type coercion being dropped under `~groupBy`; not re-tested here).
* **Whether any of these cells is engine-parity behaviour.** The brief forbids trusting the code
  comments that assert engine parity; I neither confirmed nor refuted them against a real engine.
