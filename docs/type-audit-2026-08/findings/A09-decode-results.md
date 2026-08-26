# A09 — THE WAY BACK: JDBC ResultSet -> typed Pure values

Scope: `exec/Executor.java` (read in full, 930 lines), `ExecutionResult`, `Column`, `Row`,
`ResultShape`, `SqlTypeCensus`, `DynamicPivot`, `CanonicalForm`, `CanonRider`, `MetamodelWalk`,
`PostProcessBoundary`, `InstanceIds`, `lowering/WireRender`, `lowering/Render` (wire half),
`lowering/CanonicalRenderSql`, `sql/Json` (full), `server/serial/*` (full),
`values/PureDateLiteral` (full, 662 lines), `values/PureTimeLiteral` (full), `values/LiteralText`.

All three backends were driven through the real `Compiler.execute` / `Compiler.executeWire`
pipeline with a custom probe (`Mb.java`, `Raw.java`, `DateSweep.java`, `GraphJson.java`,
`JsonBig.java`, `Pivot.java`, `Consequence.java`, `SlotSkew.java`, `DateProp.java`, `Lit.java`,
`Misc.java`) under `/tmp/claude-0/.../scratchpad/a09`:

* DuckDB `v1.5.0` (`jdbc:duckdb:`)
* SQLite `3.47.1` (`jdbc:sqlite::memory:`)
* H2 `2.1.214` (`jdbc:h2:mem:…` + `exec/H2Settings.SETTINGS`; dialect `com.legend.sql.dialect.H2`)

---

## THE DECODE PATH (map, from source read in full)

There is exactly ONE typed cell reader. `Executor.fetch(rs, i, sqlType)` (Executor.java:566-591)
calls **`rs.getObject(i)` and nothing else** — plus one re-fetch
`rs.getObject(i, java.time.LocalDateTime.class)` when the driver object is a `java.sql.Timestamp`.
No `getInt/getLong/getDouble/getBoolean/getFloat/getShort/getByte` exists anywhere on the value
path. `Executor.unwrap` (593-679) then:

1. `SqlType.Scalar.LITERAL` + String -> `values.LiteralText.parse`
2. `SqlType.Struct` + `java.sql.Struct` -> ordered `LinkedHashMap` (arity-checked, loud)
3. `SqlType.Array` + `byte[]`/`String` starting `[` -> `sql.Json.parse`
4. `SqlType.Array` + `java.sql.Array` -> `List`, elements recursively unwrapped
5. `dialect.normalize(v, type)` (H2 only: JSON `byte[]`->String; **BigDecimal->double when the
   declared type is DOUBLE**)
6. `BigDecimal` under a declared `BIGINT|INTEGER|HUGEINT` -> `long` if `bitLength() < 63`, else `BigInteger`
7. a `switch` mapping `java.sql.Timestamp` / `java.sql.Date` / `LocalDate` / `LocalDateTime` /
   `OffsetDateTime` / temporal-typed `String` -> `values.PureDateLiteral`
8. everything else: **the driver object, verbatim**

Shape dispatch is `Executor.runShape` (287-403): TABULAR / SCALAR / COLLECTION / GRAPH, chosen by
`ResultShape.of`. `Column.pureType` comes from the typed HIR schema, never from JDBC metadata —
except the dynamic-pivot branch (749-767) and the late-bound-grid branch (726-741).

### Table 1 — Pure type -> Java class in `Row.values`, per backend

Query (identical on all three):
`model::Rec.all()->project(~[cInt,cBig,cSmall,cTiny,cDouble,cFloat,cReal,cDec,cDec0,cVarchar,cChar,cBool,cDate,cTs])`
Declared column Pure types are IDENTICAL on all three (pasted from `[EXEC]`):
`cInt:Integer[1] cBig:Integer[1] cSmall:Integer[1] cTiny:Integer[1] cDouble:Float[1] cFloat:Float[1]
cReal:Float[1] cDec:Decimal[1] cDec0:Decimal[1] cVarchar:String[1] cChar:String[1] cBool:Boolean[1]
cDate:StrictDate[1] cTs:DateTime[1]`

| store col   | SQL      | Pure       | DuckDB                | SQLite                     | H2                      |
|-------------|----------|------------|-----------------------|----------------------------|-------------------------|
| C_INT       | INTEGER  | Integer    | `Integer` 2147483647  | `Integer` 2147483647       | `Integer` 2147483647    |
| C_BIG       | BIGINT   | Integer    | `Long`                | `Long`                     | `Long`                  |
| C_SMALL     | SMALLINT | Integer    | **`Short`**           | `Integer`                  | `Integer`               |
| C_TINY      | TINYINT  | Integer    | **`Byte`**            | `Integer`                  | `Integer`               |
| C_DOUBLE    | DOUBLE   | Float      | `Double`              | `Double`                   | `Double`                |
| C_FLOAT     | FLOAT    | Float      | **`Float`** (32-bit)  | **`Double`**               | **`Float`** (32-bit)    |
| C_REAL      | REAL     | Float      | **`Float`**           | **`Double`**               | **`Float`**             |
| C_DEC       | DEC(38,18)| Decimal   | `BigDecimal` exact    | **`Double` 1.2345678901234567E19** | `BigDecimal` exact |
| C_DEC0      | DEC(38,0)| Decimal    | `BigDecimal` exact    | **`Double` 1.0E38**        | `BigDecimal` exact      |
| C_VARCHAR   | VARCHAR  | String     | `String "hello"`      | `String "hello"`           | `String "hello"`        |
| C_CHAR      | CHAR(10) | String     | `String "abc"`        | `String "abc"`             | **`String "abc       "`** |
| C_BOOL      | BIT      | Boolean    | `Boolean true`        | **`Integer 1`**            | `Boolean true`          |
| C_DATE      | DATE     | StrictDate | `StrictDate`          | `StrictDate`               | `StrictDate` (**shifted pre-1582, see F1**) |
| C_TS        | TIMESTAMP| DateTime   | `DateWithSubsecond`   | `DateWithSubsecond`        | `DateWithSubsecond`     |

Additional carriers observed elsewhere in the same run: `BigInteger` (Integer, HUGEINT arithmetic),
`org.duckdb.JsonNode` (Variant on DuckDB) vs `String` (Variant on H2), `String` (Enum, all three),
`Byte`/`Short` (Integer, DuckDB only).

**DIFF:** 8 of 14 columns disagree on the Java class or the value across backends, for the SAME
declared Pure type in the SAME query. Findings F2, F3, F5 below.

---

## FINDINGS

### [UNSOUND — TOP] F1. H2 `StrictDate` decode silently shifts every date before 1582-10-15 (Julian drift)

**Evidence** — `Executor.java:667-668`:
```java
            case java.sql.Date d ->
                    PureDateLiteral.fromLocalDate(d.toLocalDate());
```
`fetch()` re-fetches a `java.sql.Timestamp` through `java.time` precisely because
"the driver's `java.sql.Timestamp` construction DROPS the BC era" (Executor.java:559-565), but the
sibling `java.sql.Date` arm was left on the broken carrier. H2's driver returns `java.sql.Date`
for a DATE column; `java.sql.Date.toLocalDate()` goes through the Julian/Gregorian hybrid calendar.
DuckDB returns `java.time.LocalDate` and is unaffected; SQLite returns text and is unaffected.

**Attribution repro** (`Raw.java`, raw JDBC, no legend code):
```
### H2
  col1 sqlTypeName=DATE javaClass=java.sql.Date value=0001-01-03 toLocalDate=0001-01-03 getTime=-62135596800000
  col1 as LocalDate = 0001-01-01          <-- the correct value IS available via getObject(i, LocalDate.class)
### DuckDB
  col1 sqlTypeName=DATE javaClass=java.time.LocalDate value=0001-01-01
```

**End-to-end repro** (`DateSweep.java`, real `Compiler.execute`, `StrictDate[1]` column, H2):
```
BAD   wrote=0001-01-01  StrictDate-> 1-01-03    DateTime-> 1-01-01T00:00:00+0000
BAD   wrote=0100-01-01  StrictDate-> 100-01-03  DateTime-> 100-01-01T00:00:00+0000
BAD   wrote=0400-01-01  StrictDate-> 399-12-31  DateTime-> 400-01-01T00:00:00+0000
BAD   wrote=1000-01-01  StrictDate-> 999-12-27  DateTime-> 1000-01-01T00:00:00+0000
BAD   wrote=1500-01-01  StrictDate-> 1499-12-23 DateTime-> 1500-01-01T00:00:00+0000
BAD   wrote=1582-01-01  StrictDate-> 1581-12-22 DateTime-> 1582-01-01T00:00:00+0000
BAD   wrote=1582-10-04  StrictDate-> 1582-09-24 DateTime-> 1582-10-04T00:00:00+0000
ok    wrote=1582-10-15  StrictDate-> 1582-10-15
ok    wrote=2024-01-01  StrictDate-> 2024-01-01
BAD count = 16 / 33
```
The SAME calendar day carried as a TIMESTAMP decodes correctly (right-hand column) — so the two
temporal lanes of one engine disagree, which is the proof this is our decode and not the store.

**Also proven divergent against the GRAPH egress** (`GraphJson.java`, same H2 row, same column):
```
[h2 DATE graph  ] [{"eDate":"0001-01-01"}]
[h2 DATE tabular] StrictDate 1-01-03
```

**Why it matters** — silent wrong DATA under an unchanged declared type. Up to a 10-day error, no
error, no wall. Fix is one line: re-fetch DATE as `java.time.LocalDate` the way TIMESTAMP already
re-fetches `LocalDateTime`.

---

### [UNSOUND — TOP] F2. SQLite: `Decimal[1]` decodes to `Double` and `Boolean[1]` to `Integer`

**Evidence** — `unwrap` (Executor.java:593-679) has NO arm asserting the decoded object's class
against the declared SQL/Pure type: step 8 is `case null, default -> v` — the driver object rides
out verbatim. `AnsiSqlRenderer` (the SQLite dialect, `Compiler.dialectOf`) inherits the base
`normalize`, which is the identity.

**Repro** (`Consequence.java`, `model::Rec.all()->project(~[v:x|$x.cDec])`, declared `Decimal[1]`):
```
== Decimal[1]  (DECIMAL(38,18) column)
  DUCKDB  BigDecimal   12345678901234567890.123456789012345678   canon=Text[value=12345678901234567890.123456789012345678]
  SQLITE  Double       1.2345678901234567E19                     canon=Text[value=12345678901234567000.0]
  H2      BigDecimal   12345678901234567890.123456789012345678   canon=Text[value=12345678901234567890.123456789012345678]
  PureAsserts.equalScalar(DUCKDB, SQLITE) = false
  PureAsserts.equalScalar(SQLITE, H2)     = false

== Boolean[1]  (BIT/BOOLEAN column)
  DUCKDB  Boolean      true   canon=Text[value=true]
  SQLITE  Integer      1      canon=Text[value=1]
  H2      Boolean      true   canon=Text[value=true]
  PureAsserts.equalScalar(DUCKDB, SQLITE) = false
```
A `Decimal(38,0)` of 38 significant digits comes back as `Double 1.0E38` (Table 1) — 37 digits gone.

**Consequence proven, not asserted**: `exec/PureAsserts.java:275-281` makes Decimal equality
`e instanceof BigDecimal && a instanceof BigDecimal && be.equals(ba)`, so the platform's own
equality returns FALSE between the SQLite and DuckDB decode of one query. `exec/CanonicalForm`
renders the two carriers as different byte texts (above). Same for Boolean: canon text `"1"` vs
`"true"`.

**Why it matters** — the compiler stamps `Decimal[1]` / `Boolean[1]`; the runtime hands back a
`Double` / `Integer`. Nothing walls. (SQLite's NUMERIC affinity is the upstream cause, but the
decode seam is where the declared type is available and unchecked — the repo's own doctrine at
Executor.java:650-654 is "the DECLARED type drives the codec", and it is applied to exactly one
case, `BigDecimal` under an integral label.)

---

### [UNSOUND] F3. `Integer[1]` -> `BigInteger` for every value in `[2^62, 2^63)` — the narrowing gate is off by one bit

**Evidence** — `Executor.java:655-661`:
```java
        if (v instanceof java.math.BigDecimal bd
                && (type == com.legend.sql.SqlType.Scalar.BIGINT
                        || type == com.legend.sql.SqlType.Scalar.INTEGER
                        || type == com.legend.sql.SqlType.Scalar.HUGEINT)) {
            java.math.BigInteger bi = bd.toBigIntegerExact();
            return bi.bitLength() < 63 ? (Object) bi.longValue() : bi;
        }
```
`BigInteger.bitLength()` excludes the sign bit, so a long-representable value needs `<= 63`, not
`< 63`. Computed (`SlotSkew.java`):
```
  4611686018427387903    bitLength=62  gate(<63)=true   actuallyFitsLong=true
  4611686018427387904    bitLength=63  gate(<63)=false  actuallyFitsLong=true      <-- 2^62
  -4611686018427387905   bitLength=63  gate(<63)=false  actuallyFitsLong=true
  9223372036854775807    bitLength=63  gate(<63)=false  actuallyFitsLong=true      <-- Long.MAX
  -9223372036854775808   bitLength=63  gate(<63)=false  actuallyFitsLong=true      <-- Long.MIN
```

**Repro** — the gate observed end to end on H2 (`Mb.java`, root type `Integer[1]` in all four rows):
```
[H_2p62_m1][SQL]  SELECT CAST(4611686018427387902 AS HUGEINT) + 1 AS value
[H_2p62_m1][ROW]  Long<4611686018427387903>
[H_2p62][SQL]     SELECT CAST(4611686018427387903 AS HUGEINT) + 1 AS value
[H_2p62][ROW]     BigInteger<4611686018427387904>          <-- flip, exactly at 2^62
```
and the same expression across backends (`q_bound.txt`, `9223372036854775806 + 1`):
```
DUCKDB  BigInteger<9223372036854775807>
SQLITE  Long<9223372036854775807>
H2      BigInteger<9223372036854775807>
```

**Why it matters** — one Pure `Integer[1]` value has three possible Java carriers
(`Integer|Short|Byte|Long|BigInteger`) depending on magnitude AND backend. `PureAsserts` survives
this by widening (`Long` vs `BigInteger` = true, verified), but `CanonicalForm`, `DynamicPivot`'s
typed-literal switch (no `BigInteger` arm — `DynamicPivot.java:90-119` throws
`NotImplementedException` on an unmapped kind) and every external consumer of `Row.values` do not.

---

### [UNSOUND] F4. `ResultShape.of` mis-shapes a table as a scalar when the user names one column `u_map__*`

**Evidence** — `ResultShape.java:41-54`:
```java
        if (Type.schemaView(root.type()) instanceof Type.RelationType rt) {
            if (rt.columns().size() == 1
                    && rt.columns().get(0).name().startsWith(
                            com.legend.sql.SqlSelect.SYNTH_MAP_COL)) {
                return isMany(root.multiplicity()) ? COLLECTION : SCALAR;
            }
            return TABULAR;
        }
```
`SqlSelect.SYNTH_MAP_COL = "u_map__"` (`sql/SqlSelect.java:45`) — a *prefix* test against a
user-writable identifier. `u_map__foo` is a legal Pure column name and parses/plans fine.

**Repro** (`Mb.java`, `q_shape.txt`, table has ONE row):
```
[NORMAL_1COL][SHAPE]     TABULAR rootType=Relation<(foo:Integer[1])> mult=[1]
[NORMAL_1COL][EXEC]      Tabular ret=Relation<(foo:Integer[1])> :: foo:Integer/[1]
[NORMAL_1COL][ROW]       Integer<2147483647>

[SYNTH_NAME_1ROW][SQL]   SELECT t0.C_INT AS u_map__foo FROM T_TYPES AS t0
[SYNTH_NAME_1ROW][SHAPE] SCALAR  rootType=Relation<(u_map__foo:Integer[1])> mult=[1]
[SYNTH_NAME_1ROW][EXEC]  Scalar ret=Relation<(u_map__foo:Integer[1])> :: value:Relation<(u_map__foo:Integer[1])>/MULT_NULL
[SYNTH_NAME_1ROW][ROW]   Integer<2147483647>
```
Identical on DuckDB, SQLite and H2. The result is an `ExecutionResult.Scalar` whose
**`returnType` is a `Relation<…>` type and whose `value` is a bare `Integer`** — and whose
`columns()` (ExecutionResult.java:83-85) is a single `Column("value", <the RelationType>)`.
Renaming the column back, or adding a second column, restores TABULAR:
```
[SYNTH_NAME_2COL][SHAPE] TABULAR rootType=Relation<(u_map__foo:Integer[1], b:Integer[1])> mult=[1]
```
With more than one row the mis-shape turns into an internal error (CRASH/ICE, plausible user input):
```
[SYNTH_NAME_NROW][EXEC-ERROR] IllegalStateException: scalar-shaped result returned more than one row
                              — the to-one contract was not enforced upstream
```

**Related, on the INTENDED lane too**: `map(x|$x.cInt)` produces
`Relation<(u_map__cInt:Integer[1])>[*]` -> COLLECTION whose `returnType` is that Relation type while
`values` holds `Integer`s — but `ExecutionResult.Collection`'s javadoc (ExecutionResult.java:93)
says "`returnType` is the ELEMENT type". Executor.java:392 passes `rootType.type()` unconditionally.
Repro (all three backends):
```
[MAPTOONE][EXEC] Collection ret=Relation<(u_map__cInt:Integer[1])> :: value:Relation<(u_map__cInt:Integer[1])>/MULT_NULL
[MAPTOONE][ROW]  Integer<2147483647>
```

---

### [UNSOUND] F5. Dynamic-pivot columns bypass the SQL-type codec: `Float` -> `BigDecimal` on H2, `Integer` -> `BigInteger` on DuckDB

**Evidence** — a pivot result column has **no plan output**, so `sqlTypeOf` returns `null`
(Executor.java:823-824: `if (hasPivot(plan)) { return null; }`). With `type == null`, neither
`H2.normalize`'s DOUBLE arm (`sql/dialect/H2.java:485-488`, which requires
`type == SqlType.Scalar.DOUBLE`) nor `unwrap`'s integral narrowing (655-661, which requires
`BIGINT|INTEGER|HUGEINT`) can fire. The `Column` is still typed from the aggregate template.

**Repro** (`Pivot.java`, `#TDS … #->pivot(~[year], ~[tot:…->plus(), av:…->average()])`):
```
### DuckDB
  COL '2011__|__tot' : Integer   COL '2011__|__av' : Float
  ROW String<NYC> | BigInteger<5000> | Double<1.5> | BigInteger<7600> | Double<2.5> |
### H2
  COL '2011__|__tot' : Integer   COL '2011__|__av' : Float
  ROW String<NYC> | Long<5000>   | BigDecimal<1.5> | Long<7600>       | BigDecimal<2.5> |
```
A column declared `Float` holds a `BigDecimal` on H2. `PureAsserts.equalScalar(BigDecimal 1.5,
Float/Double 1.5) = false` (pasted in `Consequence.java` output) — so the same pivot compares
unequal between backends.

**Same site, second defect**: every pivot column is built with the 2-arg `Column` constructor
(Executor.java:766), so `multiplicity()` is `null` for all of them:
```
  COL city : String mult=NULL
  COL '2011__|__tot' : Integer mult=NULL
```

---

### [UNSOUND] F6. A `StrictDate[1]` column over a physical TIMESTAMP decodes to a SUBSECOND `DateWithSubsecond`

**Evidence** — `unwrap`'s temporal switch keys on the DRIVER OBJECT, not the declared Pure/SQL type
(Executor.java:662-678). The `PureSql` carrier for `STRICT_DATE` is `SqlType.Scalar.DATE`
(`lowering/PureSql.java:76`), but nothing checks the returned object against it.

**Repro** (`SlotSkew.java`, store declares `D DATE`, the physical table is `D TIMESTAMP`):
```
--- StrictDate[1] declared over a physical TIMESTAMP column ---
  declared column type = StrictDate   runtime value = DateWithSubsecond<2024-02-29T13:45:56.5+0000>
  StrictDate contract says DAY precision; precision carried = SUBSECOND
```
No wall, no diagnostic. A `StrictDate` value carrying hour/minute/second/subsecond violates the
Pure type hierarchy (`PureDateLiteral` javadoc lines 68-73: `StrictDate` -> `StrictDate`,
`DateWithSubsecond` -> `DateTime`), and `PureAsserts` temporal equality is precision-sensitive
(PureAsserts.java:317-325), so such a value can never equal a real `StrictDate`.

---

### [UNSOUND] F7. SQLite: `Integer[1]` arithmetic overflow silently returns a `Double`

**Repro** (`Mb.java`, `q_ovf.txt`, `model::Rec.all()->project(~[b:x|$x.cBig + 1])`,
declared `Relation<(b:Integer[1])>` on all three):
```
DUCKDB  [EXEC-ERROR] SQLException: Out of Range Error: Overflow in addition of INT64 (9223372036854775807 + 1)!
SQLITE  [ROW] Double<9.223372036854776E18|bits=4890909195324358656>
H2      [EXEC-ERROR] JdbcSQLDataException: Numeric value out of range: "9223372036854775807"
```
and for `$x.cInt + 1` (2147483647 + 1) the three backends give: DuckDB INT32 overflow error,
**SQLite `Long<2147483648>` (silently succeeds)**, H2 out-of-range error. Three behaviours for one
Pure expression under one static type. The SQLite `Double` under `Integer[1]` is the unsound half.

---

### [CRASH] F8. The DB-built GRAPH/wire JSON is not valid JSON for a non-finite `Float`; both of the repo's own readers throw

**Evidence** — `ExecutionResult.java:131`: "Graph result: the json IS a well-formed JSON array built
by the database." `Executor.java:394-401` writes it through with `String.valueOf(dialect.normalize(...))`
— no validation.

**Repro** (`GraphJson.java`, DuckDB, `model::Edge.all()->graphFetch(#{model::Edge {eDbl}}#)->serialize(...)`):
```
[duck graph json] [{"eDbl":0.0},{"eDbl":1e308},{"eDbl":Infinity},{"eDbl":NaN},{"eDbl":-Infinity}]
[com.legend.sql.Json.parse] THREW java.lang.NumberFormatException: For input string: ""
[com.legend.server.Json.parse] THREW java.lang.IllegalArgumentException: Invalid number: expected digit, got 'I' at line 1 col 38
```
The HTTP `/query` endpoint parses exactly this text
(`server/LegendHttpServer.java:163` `Json.Node data = Json.parse(wire.json());`), so the endpoint
fails on any query returning an infinite or NaN Float. The same value on the WIRE-JSON path
(`Compiler.executeWire`, `JsonBig.java`) produces the identical invalid text.
H2 does not even get that far — it refuses the whole statement:
```
[GRAPH_EDGE][EXEC-ERROR] JdbcSQLDataException: Data conversion error converting "Infinity"; SQL statement:
SELECT CAST(COALESCE(JSON_ARRAYAGG(JSON_OBJECT('eBig': t0.E_BIG, … 'eDbl': t0.E_DBL, …
```
And the CSV wire spells them `inf` / `nan` / `-inf` — none of which is a Pure Float literal:
```
[wire csv ] eDbl | 0.0 | 1e+308 | inf | nan | -inf |
```
(`CanonicalForm.renderFloat`, exec/CanonicalForm.java:104-107, calls non-finite floats
`Residue("non-finite-float")` — out of the byte channel's claimed domain — yet the wire emits them.)

---

### [CRASH] F9. `sql/Json.num` overflows `Long.parseLong` on a `Decimal(38,0)` in the DB-built JSON

**Evidence** — `sql/Json.java:183-185`:
```java
        return t.contains(".") || t.contains("e") || t.contains("E")
                ? (Object) new java.math.BigDecimal(t)
                : (Object) Long.parseLong(t);
```
An integer-form token wider than a `long` has no arm.

**Repro** (`JsonBig.java`, DuckDB, `Decimal[1]` column of 38 nines):
```
[graph json, Decimal(38,0)] [{"cDec0":99999999999999999999999999999999999999}]
  sql/Json.parse    THREW java.lang.NumberFormatException: For input string: "99999999999999999999999999999999999999"
  server/Json.parse THREW java.lang.NumberFormatException: For input string: "99999999999999999999999999999999999999"
[wire json] cols=[cDec0, cBig]  [{"cDec0":99999999999999999999999999999999999999,"cBig":9223372036854775807}]
  sql/Json.parse    THREW java.lang.NumberFormatException  (same)
  server/Json.parse THREW java.lang.NumberFormatException  (same)
```
`sql/Json` is the reader `unwrap` itself uses for the JSON-carried Array lane (Executor.java:630) and
that the HTTP server uses for its response envelope. The same token reaches a `HUGEINT`-valued Pure
`Integer` too. `sql/Json.num` also builds an EMPTY token for any unexpected first character and then
calls `Long.parseLong("")` — the "For input string: \"\"" seen in F8.

---

### [CRASH] F10. `LiteralText.parse` throws `NumberFormatException` out of the decode on a non-finite Float and on integers beyond `long`

**Evidence** — `values/LiteralText.java:234-240`:
```java
        if (s.endsWith("D") || s.endsWith("d")) { return new java.math.BigDecimal(...); }
        if (s.contains(".") || s.contains("e") || s.contains("E")) { return Double.valueOf(s); }
        return Long.valueOf(s);
```
This is the host half of the `SqlType.Scalar.LITERAL` channel (`Executor.java:603-606`).

**Repro — reached through the real pipeline** (`Mb.java`, DuckDB, Any lane):
```
[ANY_INF2] query  [1.0e308->times(10.0), 'a']
[ANY_INF2][SHAPE] COLLECTION rootType=meta::pure::metamodel::type::Any mult=[2]
[ANY_INF2][EXEC-ERROR] NumberFormatException: For input string: "inf.0"
```
(the lowering's float-repr spelling appends `.0` to DuckDB's `inf`, so the Double arm is taken and
`Double.valueOf("inf.0")` throws.)

**Direct surface sweep** (`Lit.java`, 37 spellings; every failure listed):
```
  "9223372036854775808"                     -> THREW NumberFormatException
  "99999999999999999999999999999999999999"  -> THREW NumberFormatException
  "Infinity" "-Infinity" "NaN" "inf" "-inf" "nan"  -> THREW NumberFormatException
  "%latest"                                 -> THREW IllegalArgumentException: expected digits for year at position 0 in 'latest'
  "" " " "null" "TDSNull" "0x1p3" "1_000"   -> THREW NumberFormatException
```
`"null"` and `"TDSNull"` are the SQL-null spellings the ENCODE side already knows
(`lowering/Scalars.java:2903-2906` treats both as `NullLit`), and `LiteralText.parse`'s own javadoc
says "Null stays null (the empty value)" — but only a Java `null` is handled, not those texts.
The class header also claims the six forms are "mutually disjoint by construction … never guessing";
the integer arm has no width guard and the float arm no non-finite guard.

---

### [CRASH] F11. `project(~[])` — an empty relation root — ICEs at the decode with a plan/schema mismatch

**Repro** (`Mb.java`, `q_shape2.txt`, all three backends identically):
```
[EMPTY_PROJ][SQL]   SELECT * FROM T_TYPES AS t0
[EMPTY_PROJ][SHAPE] TABULAR rootType=Relation<()> mult=[1]
[EMPTY_PROJ][EXEC-ERROR] IllegalStateException: result has 15 columns but the typed schema has 0
                         — plan/schema mismatch
```
`Executor.resolveColumns` (Executor.java:768-771) is right to refuse; the defect is upstream (the
zero-column relation lowers to `SELECT *`), but the user-visible outcome is an internal
`IllegalStateException` on a one-token query. This is the "empty relation" case of the shape audit.

---

### [INFORMATION LOSS] F12. Three egresses of the same `DateTime` value produce three different spellings; the CSV egress silently truncates sub-seconds

**Repro** (`GraphJson.java` + `JsonBig.java`, one DuckDB row, `cTs = TIMESTAMP '2024-02-29 13:45:56.123456'`):
```
TABULAR  (Row.values)  DateWithSubsecond  2024-02-29T13:45:56.123456+0000
GRAPH    (json)        [{"cTs":"2024-02-29T13:45:56.123456000"}]
WIRE JSON              [{"cTs":"2024-02-29 13:45:56.123456"}]
WIRE CSV               cTs | 2024-02-29 13:45:56            <-- sub-seconds GONE
```
The CSV truncation is structural, not a value accident — `sql/DateFmt.java:57-60`:
```java
    static final List<DateFmt> CSV_DATETIME = List.of(Part.YEAR4,
            new Text("-"), Part.MONTH2, new Text("-"), Part.DAY2,
            new Text(" "), Part.HOUR2, new Text(":"), Part.MIN2,
            new Text(":"), Part.SEC2);
```
no subsecond part at all, used by `lowering/Render.java:725-728` for every `DATE_TIME`/`LATEST_DATE`
CSV cell. A `DateTime[1]` value cannot round-trip through the CSV wire.

Same probe, second loss: a NULL `String[0..1]` and an empty `String[1]` are the SAME CSV bytes —
`Render.java:731-734` renders SQL NULL as `""`:
```
[wire csv ] oInt,oVarchar | ,
```

---

### [INFORMATION LOSS] F13. DuckDB's `json_object` crushes a `Decimal` to a double in the GRAPH egress; the TABULAR egress of the same column is exact

**Repro** (`GraphJson.java`, DuckDB, one column, one row — GRAPH vs TABULAR of the same cell):
```
[duck DECIMAL graph ] [{"cDec":12345678901234567000.0}]
[duck DECIMAL tabular] BigDecimal 12345678901234567890.123456789012345678
```
and over the full edge row set (`Mb.java`, `q_graph.txt`):
```
DuckDB GRAPH: "eDec1818":-12345678901234567000.0 , "eDec1818":100000000000000000000.0 , "eDec1818":1e-18
H2     GRAPH: "cDec":12345678901234567890.123456789012345678      (exact)
```
So the same Pure `Decimal` property serializes exactly on H2 and lossily on DuckDB, and lossily in
GRAPH but exactly in TABULAR on the same backend. A JSON consumer reading the DuckDB graph gets a
value that is not the stored value. (Backward/forward asymmetry: `sql/Json.num` deliberately reads
decimal tokens as `BigDecimal` "audit 18 — wireEquals-grade exactness"; the writer threw the digits
away before the reader ever saw them.)

Same probe, related: a 32-bit `Float` column serializes as its double widening on DuckDB
(`"cFloat":3.4028234663852886e38`) but as the 32-bit shortest repr on H2 (`"cFloat":3.4028235E38`),
and H2 uses an uppercase `E` exponent where DuckDB uses lowercase `e` — two lexical forms of one
Pure Float on the JSON wire.

---

### [INFORMATION LOSS] F14. `H2.normalize` narrows a full-precision `BigDecimal` to `double` at the decode seam

**Evidence** — `sql/dialect/H2.java:485-488`:
```java
        if (jdbcValue instanceof java.math.BigDecimal bd
                && type == com.legend.sql.SqlType.Scalar.DOUBLE) {
            return bd.doubleValue();
        }
```
This is the ONLY `doubleValue()` on the decode path (the grep of `exec/`, `lowering/`, `sql/`,
`values/` for `doubleValue()|(double)|Double.parseDouble|floatValue()` is reproduced in
VERIFIED SOUND below; every other hit is a comparator, a canon renderer, or an encode-side literal).

**Repro** (`Mb.java` `q_agg.txt` + `Raw2.java` for attribution). Executed plan on H2:
```
[AVG_DEC][SQL] SELECT AVG(t0.C_DEC) AS m FROM T_TYPES AS t0
[AVG_DEC][EXEC] Tabular ret=Relation<(m:Float[1])> :: m:Float/[1]
[AVG_DEC][ROW]  Double<1.2345678901234567E19|bits=4892433759222981601>
```
What the driver actually delivered for that statement (`Raw2.java`, raw JDBC, same H2 session):
```
SELECT AVG(V) FROM D  ->  NUMERIC / java.math.BigDecimal = 12345678901234567890.1234567890123456780000000000
```
21 significant digits are discarded by our code, not by the database. The declared Pure type here is
`Float`, so this is the label winning — but it is exactly the situation `SqlTypeCensus`'s own
label-lie census exists to police (the wire says NUMERIC, our label says DOUBLE), and the narrowing
is unconditional and irreversible.

---

### [INFORMATION LOSS] F15. `PureDateLiteral.fromLocalDateTime` discards written sub-second precision

**Evidence** — `values/PureDateLiteral.java:223-234`: nano == 0 collapses to `DateWithSecond`;
otherwise `String.format("%09d", nano).replaceFirst("0+$", "")` strips trailing zeros. The class
header (lines 57-62) states the opposite contract: subsecond is "Carried as `String` (not numeric)
to preserve arbitrary precision: `123`, `123456`, and `123456789` are all legal Pure values and
**must round-trip byte-exact**."

**Repro** (`Mb.java`, `q_edge.txt`, DuckDB, values written with explicit precision):
```
wrote TIMESTAMP '2024-01-01 00:00:00'          -> DateWithSecond   <2024-01-01T00:00:00+0000>
wrote TIMESTAMP '2024-06-01 12:00:00.5'        -> DateWithSubsecond<2024-06-01T12:00:00.5+0000>
wrote TIMESTAMP '2024-02-29 13:45:56.123456'   -> DateWithSubsecond<2024-02-29T13:45:56.123456+0000>
```
A DateTime written `.500000` (6 digits) returns as `.5` (1 digit) — a different `PureDateLiteral`
record, and `PureAsserts` temporal equality is record equality (precision-sensitive,
PureAsserts.java:317-325). The method's own javadoc documents the choice as adjudicated; the class
header's byte-exact claim is the lie.

---

### [INCONSISTENCY] F16. `ResultShape.of` and `Type.relationValued` classify a bare `RelationType` differently

`compiler/element/type/Type.java:400-409`:
```java
    /** … A bare struct with an at-most-one stamp is ONE row and is NOT
     * relation-rooted. No tree walking — the type and stamp decide. */
    static boolean relationValued(ExprType info) {
        return isRelation(info.type())
                || (info.type() instanceof RelationType
                        && info.multiplicity().isMany());
    }
```
`exec/ResultShape.java:41-53` reaches the same question through `Type.schemaView`, which returns a
bare `RelationType` **regardless of multiplicity** (Type.java:392-398), and returns TABULAR.
So a bare-struct root at `[1]`/`[0..1]` is "not relation-rooted" to one owner and "a table" to the
other. Two implementations of one typing rule. (ResultShape's javadoc calls it deliberate — "a ROW
root is a one-row TABLE at the boundary" — which makes it a documented disagreement, not an
accident, but it is still two rules.)

Second inconsistency at the same site: TABULAR ignores multiplicity entirely, so `Relation<…>[1]`,
`[0..1]` and `[*]` all shape identically and the `requireBounded` lower-bound enforcement that
SCALAR (Executor.java:302-308) and COLLECTION (370-391) perform has no TABULAR counterpart.
`->first()` on an empty relation therefore returns a 0-row Tabular with no check, while the
equivalent `[1]`-stamped scalar root raises.

---

### [DEAD TYPE LOGIC] F17. `exec/Column.multiplicity` has ZERO read sites in the entire repository

Requested item 7. Repo-wide grep (`core/src/main`, `core/src/test`, `nlq/`, `tools/`, `experiments/`)
for any read of an `exec.Column`'s `multiplicity()`: **none**. The only occurrences are the three
WRITE sites inside `Executor` (lines 738, 747, 921) and the field declaration. The one consumer of
any `exec.Column` accessor in main is `Executor.java:794` reading `pureType()` for the
many-valued-cell guard; `name()` is read by `AssertVerdicts.java:86,88,1104`. Nothing reads
`multiplicity()`, so nothing "does" anything with `null`.

It IS null on four of the five construction paths, observed in every probe run:
* `ExecutionResult.Scalar.columns()` (ExecutionResult.java:83-85) — `new Column("value", returnType)`
* `ExecutionResult.Collection.columns()` (106-108) — same
* `ExecutionResult.Graph.columns()` (143-145) — same
* the dynamic-pivot branch (Executor.java:766) — `new Column(name, pivotColumnType(...))`

pasted (`Mb.java`, every SCALAR/COLLECTION/GRAPH run): `value:Integer/MULT_NULL`,
`json:String/MULT_NULL`; (`Pivot.java`): `COL '2011__|__tot' : Integer mult=NULL`.
Only the positional TABULAR branch (742-748) and the late-bound branch (734-739) populate it.

Consequence: the `Column` record is a public result-API type whose nullable field would NPE any
external consumer that trusts the javadoc's "The multiplicity rides since F5.2" (Column.java:12).
Today it is write-only.

---

### [GAP] F18. `Executor.pureOfSqlType` refuses H2's own metadata spelling for a DOUBLE column

Exhaustive table of the switch (Executor.java:893-904) plus the spellings real drivers hand back
(`Pivot.java`; `orNull` = `pureOfSqlTypeOrNull`, `loud` = `pureOfSqlType`):
```
  TINYINT/SMALLINT/INTEGER/BIGINT/HUGEINT -> INTEGER      FLOAT/DOUBLE/REAL -> FLOAT
  BOOLEAN -> BOOLEAN    DATE -> STRICT_DATE    TIMESTAMP -> DATE_TIME
  DECIMAL/NUMERIC -> DECIMAL   VARCHAR/CHAR/TEXT/STRING/BPCHAR -> STRING
  DECIMAL(38,9) -> DECIMAL     VARCHAR(100) -> STRING     integer/Integer -> INTEGER  (case-folded)
  REFUSED (orNull=null, loud=IllegalStateException):
    DOUBLE PRECISION, BOOLEAN NOT NULL, INT, INT4, INT8, TIMESTAMP WITH TIME ZONE, TIMESTAMPTZ,
    TIME, BLOB, JSON, UUID, BIT, CHARACTER VARYING, NUMBER, VARBINARY, SMALLINT UNSIGNED
```
`DOUBLE PRECISION` is precisely what H2 2.1.214 reports for a DOUBLE column — proven by raw JDBC
(`Raw.java`): `col3 sqlTypeName=DOUBLE PRECISION`. So a Float-valued pivot column on a schema
rebuilt downstream of the pivot (the one documented "JDBC metadata types a column" path,
Executor.java:855-877) ICEs on H2. I could not reach that fallback with `sort`/`filter`/`limit`
after a pivot (the aggregate templates survived all three — pasted in `Pivot.java` output), so this
is a latent gap, not a demonstrated crash. `TIMESTAMPTZ` is also absent although
`Executor.isTemporalType` (681-688) explicitly handles `SqlType.Scalar.TIMESTAMPTZ`.

`DynamicPivot.discover` (DynamicPivot.java:90-119) has the same shape of gap on the *key* side: no
arm for `BigInteger` (which DuckDB returns for HUGEINT — proven in F3), `byte[]`, `OffsetDateTime`
or `org.duckdb.JsonNode`; an unmapped kind throws `NotImplementedException`.

---

### [INFORMATION LOSS] F19. Enum values decode to bare `String`; DuckDB Variant leaks `org.duckdb.JsonNode`

`model::Color[1]` column (`Mb.java`, `q_ext.txt`, all three backends):
```
[ENUM][EXEC] Tabular ret=Relation<(e:model::Color[1])> :: e:model::Color/[1]
[ENUM][ROW]  String<RED>
```
The runtime value is indistinguishable from a `String[1]` cell — `unwrap` has no enum arm
(Executor.java:662-678 default), and `Row.values` carries no type tag. `CanonicalForm.render`
therefore renders an enum and the string `"RED"` identically, and `PureAsserts.equalScalar` would
call them equal.

`Variant[1]` (`Mb.java`, `q_shape2.txt`):
```
DUCKDB [VARIANT][ROW] JsonNode<1>      (org.duckdb.JsonNode — a driver-private class in Row.values)
H2     [VARIANT][ROW] String<1>
```
`cellRead` (Executor.java:462-465) matches `org.duckdb.JsonNode` by full class-name string and
decodes it only when the root is NOT a Variant — for a Variant root the driver object is handed to
the caller verbatim.

---

### [SILENT DEFAULT] F20. Four null-to-default coercions at the egress

The repo forbids silent defaulting. Four exist on the way back:
1. `Executor.wireText` (Executor.java:195-196): `String s = rs.getString(1); return s == null ? "" : s;`
2. `Executor.runShape` GRAPH (398-400): no row -> `"[]"`; a PRESENT-but-NULL cell ->
   `String.valueOf(null)` = the 4-char text `"null"`, not an array, despite
   `ExecutionResult.Graph`'s "the json IS a well-formed JSON array" contract.
3. `ExecutionResult.Graph.rows()` (ExecutionResult.java:149): `json != null ? json : ""`.
4. `Executor.streamWireRows` (218-219) / `streamGraph` (246-248): a null cell is written as the
   literal text `null`.
I could not make (1) fire — the wire renders `COALESCE` so a zero-row result yields `'a\r\n'` (CSV
header only) and `'[]'` (JSON), pasted in `Misc.java` output. (2)-(4) are reachable in principle;
none is walled.

---

### [DOC-LIE] F21. `PureDateLiteral`'s own quoted grammar admits `T<hour><TZ>`; the parser refuses it

Javadoc lines 19-23 quote engine's `CoreFragmentGrammar.g4`:
`Date : '%' ('-')? Digit+ ('-' Digit+ ('-' Digit+ ('T' DateTime TimeZone?)?)?)?` with
`DateTime : Digit+ (':' …)?` — i.e. an hour-only DateTime may carry a TimeZone. Line 419's comment
says the opposite ("TZ-after-hour is not legal per engine") and the code refuses:
```
PROBE REFUSE 2024-02-29T13Z     -> IllegalArgumentException: expected ':' after hour at position 13
PROBE REFUSE 2024-02-29T13+0500 -> IllegalArgumentException: expected ':' after hour at position 13
PROBE REFUSE 2024-02-29T13-0500 -> IllegalArgumentException: expected ':' after hour at position 13
```
One of the two prose claims is false. Everything else about the grammar is correct — see below.

---

## VERIFIED SOUND

**1. The `wasNull` audit is CLEAN — the classic 0-for-null bug does not exist.**
Repo-wide grep for `\.get(Int|Long|Double|Boolean|Float|Short|Byte)\(` excluding
`System.getProperty`, over `core/`, `nlq/`, `tools/`, `experiments/`:
* `core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:1172` —
  `long asymmetric = rs.getLong(1);` over `SELECT count(*) FROM (… EXCEPT … UNION ALL …)`.
  `count(*)` is never NULL, so the missing `wasNull` is harmless. **Only unguarded main-source site.**
* `core/src/main/java/com/legend/server/Json.java:62,63,80` — javadoc examples, not code.
* `core/src/test/.../CorpusDifferentialTest.java:147-148` — `rs.getDouble(i)` followed by
  `rs.wasNull() ? NULL : …`. **Correctly guarded.**
* all remaining hits are `assertEquals(…, rs.getInt(…))` in tests and `experiments/backend-probes`.

The value path uses `rs.getObject` exclusively (Executor.java:569), so a NULL is a Java `null` by
construction. Verified end to end: `model::Opt` with all eight `[0..1]` columns NULL returns
`null | null | null | null | null | null | null | null` on **all three backends**, with the declared
types intact (`oInt:Integer/[0..1] … oTs:DateTime/[0..1]`). A NULL Integer under `[0..1]` returns
`null`, never `0`.

**2. `PureDateLiteral` parse/render round-trip — 860 forms, exhaustive over the grammar, 0 failures.**
`DateProp.java` enumerates every shape the parser accepts: 18 year spellings (incl. `-0`, `0000`,
`02024`, `±999999999`) x {bare, `-month` x 8, `-month-day` x 8x7}, x {`Thh` x 6, `:mm` x 4,
`:ss` x 5, `.subsec` x 13} x 9 timezone suffixes (``, `Z`, `±0000`, `±0530`, `±2359`, `+0100`):
```
total=860 canonicalIdempotenceFail=0 textRoundTripDiff=626 rejected=26 tzFail=0
```
* `parse(x.toEngineString()).equals(x)` holds for all 860 — the documented round-trip identity.
* All 26 rejections are genuine invalid days (`2024-02-30`, `1-2-29`, `2024-09-31`, …) — no shape
  is wrongly refused.
* 626 texts differ from the source only by canonicalisation (leading-zero normalisation, TZ folded
  to GMT) — documented behaviour, and idempotent on the second pass.
* TZ normalisation checked against hand-computed `java.time` references, 5/5 exact, including a
  cross-year and a cross-day case at the ±23:59 extremes:
```
ok   2024-01-01T00:30+0100      -> 2023-12-31T23:30
ok   2024-01-01T00:30-0100      -> 2024-01-01T01:30
ok   2024-03-01T00:00:59+0530   -> 2024-02-29T18:30:59
ok   2024-01-01T00:00:00.123-2359 -> 2024-01-01T23:59:00.123
ok   0001-01-01T00:00+0100      -> 0-12-31T23:00
```
* `PureTimeLiteral`: 67 forms, `timeCanonFail=0 timeReject=0`. Hour-only (`10`) and TZ-bearing
  (`10:30:45.123+0000`) forms are refused, matching its javadoc.

**3. `PureTimeLiteral` / `StrictTime` never reaches the decode.** `lowering/PureSql.java:91`
maps `BYTE, STRICT_TIME -> null` and `type()` throws on it; a `StrictTime` root fails at lowering
(`NotImplementedException: scalar lowering not yet implemented for TypedCTime`, pasted, all three
backends). There is no StrictTime carrier on the way back, so nothing can decode wrongly.

**4. NULL handling in the TABULAR/GRAPH lanes is faithful.** NULL cells arrive as Java `null` on all
three backends and serialise as JSON `null` in both the GRAPH and the wire-JSON egress:
```
[GRAPH_NULL][JSON] [{"oInt":null,"oBig":null,"oDouble":null,"oDec":null,"oVarchar":null,"oBool":null,"oDate":null,"oTs":null}]
[wire json] [{"oInt":null,"oVarchar":null}]  -> sql/Json.parse OK, server/Json.parse OK (Null[] nodes)
```
(The CSV egress is the exception — F12.)

**5. The COLLECTION null wall works.** `Executor.java:354-368` refuses a NULL cell on a non-variant
COLLECTION egress and `385-391` refuses a below-lower-bound count. `Misc.java`:
`model::Opt.all()->map(x|$x.oInt)` over a single all-NULL row returns `Collection rows=0` (the
lowerer dropped it in SQL, as the comment claims), not a NULL element. The SCALAR arm's
second-row check fires correctly (proven in F4).

**6. `SqlTypeCensus` is measurement-only and cannot corrupt a value.** `probeWire`/`settleWire`
touch only `LongAdder`s and a `ThreadLocal` watch list; the whole probe body is wrapped in
`catch (SQLException | RuntimeException)` (SqlTypeCensus.java:210-221) that counts an unknown
instead of throwing, and `WIRE_WATCH.remove()` runs defensively at entry (109). No path returns a
value into `runShape`.

**7. Numeric-narrowing grep, evaluated site by site.** `doubleValue()|(double)|Double.parseDouble|floatValue()`
over `exec/`, `lowering/`, `sql/`, `values/` — every hit:
* `sql/dialect/H2.java:487` — **the only narrowing on the decode path** (F14).
* `exec/CanonicalForm.java:54` `Float.doubleValue()` — canon render only; but it makes the byte
  channel carrier-dependent: `CanonicalForm.render(3.4028235e38f)` =
  `340282346638528860000000000000000000000.0` while `render(3.4028235e38d)` =
  `340282350000000000000000000000000000000.0` (pasted, `Consequence.java`) — two texts for one Pure
  Float value, selected by whether the backend handed back `Float` or `Double` (Table 1). Reported
  as part of F2/F5 rather than separately.
* `exec/GridCompare.java:162,188,350-351`, `exec/PureAsserts.java:104-105,295,313` — comparison
  tolerance/ordering only, never a stored value.
* `exec/PureAsserts.java:120,348`, `exec/MetamodelWalk.java:431`, `lowering/ConstBounds.java:23`,
  `lowering/Lowerer.java:471,473,1230,2329,2355`, `lowering/Scalars.java:837`,
  `lowering/DecimalKindRules.java:78`, `lowering/Windows.java:202-204` — `longValue()`/`intValue()`
  on already-integral compile-time constants (LIMIT/OFFSET/scale/window bounds), not wire values.
* `exec/DynamicPivot.java:103` `Double.parseDouble(Float.toString(f))` — deliberately the
  shortest-round-trip widening, documented, correct.
* `lowering/Scalars.java:2924` `Double.parseDouble(stripDecimalSuffix(cell))` — encode side, a
  FLOAT-declared TDS literal cell; the Decimal arm two lines below builds a `BigDecimal`.
**No `BigDecimal` is narrowed to `double` anywhere on the way back except `H2.java:487`.**

**8. Numeric edges that DO round-trip byte-for-byte.** `Mb.java` `q_edge.txt`, DuckDB, checked by
raw bit pattern:
`Long.MIN/MAX` (`Long<-9223372036854775808>`, `Long<9223372036854775807>`), `Integer.MAX+1`
(`Long<2147483648>`), 38-digit `Decimal(38,0)` both signs (`BigDecimal … scale=0 prec=38`),
`Decimal(38,18)` with all 38 digits significant (`BigDecimal 12345678901234567890.123456789012345678
scale=18 prec=38`), `Double.MAX` (`bits=9218868437227405311`), `1e308` (`bits=9214871658872686752`),
`Float` subnormal `1.4E-45` (`bits=1`), `Float.MIN_NORMAL` `1.1754944E-38` (`bits=8388608`),
`Float.MAX` `3.4028235E38` (`bits=2139095039`), `+Infinity` (`bits=9218868437227405312`),
`-Infinity` (`bits=-4503599627370496`), `NaN` (`bits=9221120237041090560`). All exact on DuckDB and
H2. `-0.0` comes back as `+0.0` (`bits=0`) on all three, but raw JDBC shows the databases
themselves store `+0.0` (`Raw.java`: DuckDB `col3 … value=0.0 bits=0`), so that is not a decode
defect; `CanonicalForm.renderFloat` (CanonicalForm.java:108-114) unifies the zeros deliberately.

**9. Struct/array unwrap is arity-checked, not zipped short** (Executor.java:607-640) and the
many-valued-cell guard (793-801) refuses a list in a primitive TDS slot rather than repairing it —
both read in full, both loud on violation.

**10. `InstanceIds`, `PostProcessBoundary`, `CanonRider`, `MetamodelWalk`, `server/serial/*`**
carry no value typing. `server/serial/{Json,Csv}Serializer` are metadata-only records
(`formatId`/`contentType`/`supportsStreaming`); no serializer composes result values in Java, so
the wire text is entirely `Render.jsonWire`/`Render.csvWire` (which is why F8/F12/F13 are
plan-render defects, not serializer defects). `MetamodelWalk` is store-metamodel navigation
(`literalLong` at 427-431 narrows a compile-time LIMIT literal), not a JDBC decode.

**11. The `harvestCanon` side channel** (Executor.java:409-419) reads only `rs.getString(2+i)` into
`CanonRider.rows()` — text, row-aligned with the value decode, never fed back into a value.

---

## NOT COVERED

* **`Byte[1]` (Binary/Varbinary columns) has no decode at all** — `lowering/PureSql.java:91` maps
  `BYTE -> null` and any query touching a class or table with a binary column dies at lowering with
  `IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary` (pasted,
  all three backends). The Executor's `byte[]` handling is only reachable through the Array-as-JSON
  arm (Executor.java:624-632). I could not exercise `Byte` round-trip; it is unreachable, not sound.
* **`LatestDate`** decodes as `DateWithSecond<9999-12-31T00:00:00+0000>` on DuckDB and H2 (pasted,
  `q_lit.txt`), i.e. the sentinel, correctly; the SQLite path failed at render
  (`near "AS": syntax error`) so LatestDate is unverified on SQLite.
* **`java.sql.Struct` cells** — I read the unwrap code in full but could not construct a query whose
  plan emits a `SqlType.Struct` output on any of the three backends within the time budget; the
  struct-column flattening (`flattenStructColumns`, 908-928) was exercised only via the schema path.
* **The late-bound raw-grid branch** (`schema.isLateBound()`, Executor.java:726-741, which adopts
  ResultSet header names as `Any[0..1]` "trusted columns") needs a raw-SQL grid root I did not have
  a fixture for; its `Type.RelationType.trustedColumn` fallback to `Any` is a defaulting site I flag
  as unexamined rather than sound.
* **Timezone-bearing SQL types** (`TIMESTAMPTZ`, `OffsetDateTime` cells) — the `unwrap` arm at
  672-674 normalises to UTC and drops the offset; no backend in this harness produces a
  TIMESTAMPTZ column by default, so I have code reading but no run.
* **The `->pivot` SQL-type fallback** (F18) is proven unreachable through `sort`/`filter`/`limit`
  but I did not enumerate all downstream operators, so I cannot say it is dead.
* **SQLite GRAPH / Variant / UNNEST lanes** are refused by the dialect
  (`no such function: list`, `DialectCapability: UNNEST/toVariant reached a dialect without …`), so
  the SQLite column of Tables for those lanes is empty by capability, not by test.
