# A27 — DDL generation, CSV seeding, and test-data generation

Scope: `core/src/main/java/com/legend/exec/Ddl.java` (458), `exec/CsvSeed.java` (154),
`core/src/main/java/com/legend/testdatagen/TestDataGenerator.java` (1693),
`exec/SqlTypeCensus.java` (747), `sql/dialect/TypeNames.java` (70). All read in full.

## Scope correction (read this first — it re-frames items 1, 4, 5 of the task)

Two premises in the task brief are false against the code, and every finding below is
positioned against the real shape:

1. **`Ddl.java` never sees a Pure `Type`.** Its whole type surface is
   `RelationalDataType` &rarr; DDL text (`Ddl.java:391-456`). The Pure `Type` of a store
   column is produced *elsewhere*, by `StoreCompiler.columnType`
   (`compiler/element/StoreCompiler.java:182-208`), and the ONE place a Pure `Type` is
   turned into DDL without going through the query pipeline is
   **`CsvSeed.ddlType(Type)` (`CsvSeed.java:128-153`)**. The soundness question is
   therefore the *composition*:
   `RelationalDataType --StoreCompiler.columnType--> Pure Type --{Ddl.spell | CsvSeed.ddlType}--> DDL`,
   and the defects live in the gap between what `columnType` claims and what the two
   spellers create.
2. **`testdatagen/` does not synthesize data.** `TestDataGenerator` is the engine's
   `generateTestData`: it *extracts* existing rows from a live database given seed row
   identifiers, materializes them into DuckDB temp tables, and dumps CSV. There is no
   value generator to check against a declared type, so task items 4/5 are answered as:
   *does the extract/serialize/re-ingest loop preserve the declared type, the `[1]`
   lower bound, the primary key, and the requested row set?* (Answer: no, on all four.)

There are **six** independent Pure/store-type &rarr; SQL-type mappings in the repo, not
three. Full census in §T1/§T2 below.

---

# FINDINGS

### [UNSOUND] 1. `CsvSeed` pins EVERY decimal column to `DECIMAL(38, 9)`, silently truncating the declared scale

**Evidence** — `exec/CsvSeed.java:144-147`:
```java
        if (t == Type.Primitive.DECIMAL
                || t instanceof Type.PrecisionDecimal) {
            return "DECIMAL(38, 9)";
        }
```
`ddlType`'s only call site is `CsvSeed.java:79`, fed from
`ctx.findTable(dbFqn, table).columns().get(c).type()`, i.e. the Pure type
`StoreCompiler.columnType` assigned: `Decimal(p,s)` &rarr; `Type.PrecisionDecimal(p,s)`.
The declared `(p,s)` is present and is thrown away.

**Repro** (`/home/user/audit/findings/A27-repros/FullE2E.java`, store column `C_DEC3818 DECIMAL(38,18)`,
CSV cell `12345678901234567890.123456789012345678`, DuckDB):
```
[G] root type = ... Column[name=big, type=DECIMAL, ...]
[SQL] SELECT t0.ID AS id, t0.C_DEC3818 AS big, ... FROM T_ALL AS t0
  id       wire=BIGINT           value=1
  big      wire=DECIMAL(38,9)    value=12345678901234567890.123456789      java=BigDecimal
  small    wire=DECIMAL(38,9)    value=12345678.990000000                  java=BigDecimal
```
The compiler claims `Decimal(38,18)`; nine fractional digits are gone from the stored
value. The `Decimal(10,2)` column comes back at scale 9 (`12345678.990000000`), so a
golden asserting `12345678.99` differs too.

The repo's **own** wire tripwire catches it (`/home/user/audit/findings/A27-repros/Final.java`, section B):
```
   CENSUS: ... wire: agree=4 ... diverge=1
     1x wire[DuckDb] label=DECIMAL(10,2) <> meta=DECIMAL(38,9)
```
`RelationalCorpusRunner.java:744-748` asserts `wireDivergeCount() == 0` ("HARDENED TO
EQUALITY at zero"), so this seed path emits exactly the divergence the corpus pins away.

**Why it matters** — top-prize unsoundness: the static type says `Decimal(38,18)`, the
storage the same pipeline created cannot represent it, and nothing raises.

---

### [UNSOUND] 2. Execution DDL drops `NOT NULL` and `PRIMARY KEY`; `Multiplicity[1]` columns hold NULL and duplicate keys at runtime

**Evidence** — `StoreCompiler.java:170-171` assigns the multiplicity (`tableSchema`; the identical rule is at `:110-111` for `viewSchema`):
```java
            Multiplicity mult = (col.notNull() || col.primaryKey())
                    ? Multiplicity.Bounded.ONE : Multiplicity.Bounded.ZERO_ONE;
```
Neither execution speller emits a constraint:
* `Ddl.java:62-98` — `H2_EXEC`/`DUCK_EXEC` append only `"name" TYPE`; the `NOT NULL` /
  `PRIMARY KEY(...)` arms are gated on `f == Flavor.ENGINE_TEXT` (lines 80-96).
* `CsvSeed.java:70-81` — `CREATE TABLE t (col TYPE, ...)`, no constraints at all.
* `StatementExecutor.java:2950-2960` — `dropAndCreateTableInDb` *records* the PK ALTER
  for the H2 mirror text (`RawSqlBoundary.recordMeta`) and never executes it.

**Repro A** (`/home/user/audit/findings/A27-repros/Final.java`, real `dropAndCreateTableInDb` through
`Compiler.execute`, DuckDB):
```
== A: dropAndCreateTableInDb, then violate the [1] / PK contract ==
   created ID INTEGER nullable=1
   created NAME VARCHAR nullable=1
   created AMT DECIMAL(10,2) nullable=1
   NULL into NAME[1], duplicate PK, NULL PK: ALL ACCEPTED
   Tabular[columns=[Column[name=id, pureType=INTEGER, multiplicity=Bounded[lower=1, upper=1]],
                    Column[name=name, pureType=STRING, multiplicity=Bounded[lower=1, upper=1]], ...],
           rows=[Row[values=[1, null, 1.00]], Row[values=[1, dup, 2.00]], Row[values=[null, nopk, 3.00]]], ...]
```
`id: Integer[1]` and `name: String[1]` are declared `Bounded[lower=1, upper=1]` and the
executed rows contain `null` in both, plus a duplicated primary key.

**Repro B** (`/home/user/audit/findings/A27-repros/NotNull.java`, the CSV seed path, `ID INTEGER PRIMARY KEY,
NAME VARCHAR(20) NOT NULL`, CSV `1,` / `1,dup` / `,orphan`):
```
COMPILER CLAIM (ModelContext.findTable): [Column[name=ID, ..., multiplicity=Bounded[lower=1, upper=1]],
                                          Column[name=NAME, ..., multiplicity=Bounded[lower=1, upper=1]]]
CsvSeed DDL/INSERT:
  CREATE TABLE T (ID BIGINT, NAME VARCHAR)
  INSERT INTO T (ID, NAME) VALUES ('1', NULL), ('1', 'dup'), (NULL, 'orphan')
EXEC RESULT: ... rows=[Row[values=[1, null]], Row[values=[1, dup]], Row[values=[null, orphan]]] ...
```

The `Ddl.java:21-26` header calls the constraint omission "a DELIBERATE DIVERGENCE"
justified by DuckDB re-seed tolerance. That justification does not extend to
`Multiplicity.ONE`: the compiler is still handing callers a non-null guarantee the
storage does not enforce.

---

### [UNSOUND] 3. Pure `Integer` is 64-bit; `Ddl` gives it 32-bit `INTEGER`/`INT` (and 16-/8-bit `SMALLINT`/`TINYINT`)

**Evidence** — `StoreCompiler.java:186-189` maps `TinyInt`/`SmallInt`/`Integer_`/`BigInt`
all to `Type.Primitive.INTEGER` (Pure `Integer`, carried as `Long` —
`PureSql.primitiveCarrier:72` gives it `SqlType.Scalar.BIGINT`).
`Ddl.java:425-428` spells them back at their narrow widths:
```java
            case RelationalDataType.SmallInt ignored -> "SMALLINT";
            case RelationalDataType.TinyInt ignored -> "TINYINT";
            case RelationalDataType.Integer_ ignored -> "INTEGER";
```
and `Ddl.java:412-415` spells `Integer_` as `INT` for `ENGINE_TEXT`.

**Repro** (`/home/user/audit/findings/A27-repros/DropCreate2.java`, real `dropAndCreateTableInDb` then an insert):
```
T_INT     OK   created: id INTEGER; n INTEGER;
T_INT insert 3000000000 -> REJECT: ... Conversion Error: Type INT64 with value 3000000000 can't be cast
```
Cross-DB (`/home/user/audit/findings/A27-repros/EdgeVals.java`), inserting the Pure `Integer` edge
`9223372036854775807`:
```
--- store Integer_  pure=Integer[64-bit]  ddl=INTEGER
      duckdb  REJECT ... Conversion Error: Type INT64 with value 9223372036854775807 can't be cast
      sqlite  ok    got=9223372036854775807  (Long)
      h2      REJECT Numeric value out of range: "9223372036854775807" in column "C"
--- store SmallInt  pure=Integer[64-bit]  ddl=SMALLINT   (value 40000)
      duckdb REJECT / sqlite ok / h2 REJECT
--- store TinyInt   pure=Integer[64-bit]  ddl=TINYINT    (value 200)
      duckdb REJECT / sqlite ok / h2 REJECT
```
SQLite accepts them only because it is dynamically typed — it stores a `Long` in a
column declared `TINYINT`, which is the same unsoundness with the loudness removed.

**Note the asymmetry with `CsvSeed`**, which gets this right (`INTEGER -> BIGINT`,
`CsvSeed.java:129-131`) — so the same store column is 64-bit-capable when seeded and
32-bit when created by `dropAndCreateTableInDb`.

---

### [UNSOUND] 4. Pure `Float` is a double; `Ddl` gives `Real` a 4-byte `REAL` (and `ENGINE_TEXT` gives `Float_` a DuckDB 4-byte `FLOAT`)

**Evidence** — `StoreCompiler.java:190-192` maps `Float_`/`Double_`/`Real` all to
`Type.Primitive.FLOAT` (a double: `PureSql.primitiveCarrier:73` &rarr; `DOUBLE`).
`Ddl.java:431-433`:
```java
            case RelationalDataType.Float_ ignored -> "FLOAT";
            case RelationalDataType.Double_ ignored -> "DOUBLE";
            case RelationalDataType.Real ignored -> "REAL";
```
`Ddl.java:392-395` adds a DuckDB delta for `Float_` only — `Real` has none, and
`ENGINE_TEXT` (`Ddl.java:399-401` &rarr; `dataTypeToSqlText` &rarr; `spell`) has neither.

**Repro** (`/home/user/audit/findings/A27-repros/EdgeVals.java`, inserting `0.1234567890123456789`, a value a
Pure `Float` holds exactly as `0.1234567890123457`):
```
--- store Real  pure=Float[double]  ddl=REAL
      duckdb  LOSS  got=0.12345679  (Float)
      sqlite  LOSS  got=0.123456789012346  (Double)
      h2      LOSS  got=0.12345679  (Float)
--- store Real  pure=Float[double]  ddl=REAL   insert 1.7976931348623157E308 (Double.MAX_VALUE)
      duckdb  REJECT ... Conversion Error: Type DOUBLE with value 1.7976931348623157e+308
      h2      LOSS  got=Infinity  (Float)
--- store Float_ pure=Float[double] ddl=FLOAT
      duckdb  LOSS  got=0.12345679  (Float)      <- the ENGINE_TEXT spelling on DuckDB
      h2      LOSS  got=0.12345678901234568  (Double)
```
`Double.MAX_VALUE` in a `Real` column is rejected outright by DuckDB and becomes
`Infinity` on H2 — a value the Pure type claims to hold.

---

### [UNSOUND] 5. Pure `String` is unbounded; `Ddl` emits `VARCHAR(n)`/`CHAR(n)` — H2 rejects overflow and pads `CHAR`

**Evidence** — `StoreCompiler.java:194-195`: `Varchar(n)`/`Char_(n)` &rarr;
`Type.Primitive.STRING` (no width). `Ddl.java:437-438` re-emits the width.

**Repro** (`/home/user/audit/findings/A27-repros/EdgeVals.java`):
```
--- store Varchar(10)  pure=String[unbounded]  ddl=VARCHAR(10)   insert 26 chars
      duckdb  ok  (DuckDB ignores the width)
      sqlite  ok
      h2      REJECT Value too long for column "C CHARACTER VARYING(10)": "'ABCDEFGHIJKLMNOPQRSTUVWXYZ' (26)"
--- store Char_(3)     pure=String[unbounded]  ddl=CHAR(3)       insert 10 chars
      h2      REJECT Value too long for column "C CHARACTER(3)": "'ABCDEFGHIJ' (10)"
--- store Char_(3)     pure=String[unbounded]  ddl=CHAR(3)       insert 'A'
      duckdb  ok    got=A
      sqlite  ok    got=A
      h2      LOSS  got=A      (three chars: H2 CHAR-pads)
```
The `CHAR` padding is silent forward/backward asymmetry: `'A'` goes down, `'A  '` comes
back, and `$x == 'A'` is then false on H2 and true on DuckDB for identical Pure input.

---

### [UNSOUND] 6. Pure `DateTime` carries arbitrary sub-second digits; `TIMESTAMP` truncates to microseconds on DuckDB and rounds on H2

**Evidence** — `PureDateLiteral.DateWithSubsecond` (`values/PureDateLiteral.java:323`)
holds the subsecond as a `String` of arbitrary length; `StoreCompiler.java:197` maps
`Timestamp` &rarr; `DATE_TIME` and `Ddl.java:435` spells `TIMESTAMP`.

**Repro** (`/home/user/audit/findings/A27-repros/EdgeVals.java`, `2024-01-02 03:04:05.123456789`):
```
      duckdb  LOSS  got=2024-01-02 03:04:05.123456     (truncated)
      sqlite  ok    got=2024-01-02 03:04:05.123456789  (stored as text)
      h2      LOSS  got=2024-01-02 03:04:05.123457     (rounded — the value CHANGED)
```
H2 rounds rather than truncates, so the same Pure literal decodes to two different
DateTimes depending on backend. Confirmed again through the seed path
(`/home/user/audit/findings/A27-repros/RoundTrip.java`): `2024-01-02 03:04:05.123456789` &rarr;
`2024-01-02 03:04:05.123456`.

---

### [CRASH/ICE] 7. `Ddl.spell` throws a bare `IllegalStateException` for `OTHER`/`ARRAY` columns — writable in Pure source, and `dropAndCreateTableInDb` ICEs on them

**Evidence** — `Ddl.java:450-455`:
```java
            case RelationalDataType.Other ignored -> throw new IllegalStateException(
                    "no DDL spelling for store column type " + t);
            case RelationalDataType.Array ignored -> throw new IllegalStateException(...);
```
`DatabaseProtocolParser.java:383` accepts both spellings from Pure source:
`case "OTHER", "ARRAY" -> "Other";`.

**Repro** (`/home/user/audit/findings/A27-repros/DropCreate2.java`, `Table T_OTHER(id INTEGER PRIMARY KEY, blobc OTHER)`
and `Table T_ARRAY(id INTEGER PRIMARY KEY, arrc ARRAY)`, driven through the real
`Compiler.execute(... dropAndCreateTableInDb ...)`):
```
T_OTHER   java.lang.IllegalStateException: no DDL spelling for store column type Other[]
T_ARRAY   java.lang.IllegalStateException: no DDL spelling for store column type Other[]
```
A bare `java.lang.IllegalStateException` escapes as an internal error on input a user can
write. The *sibling* path is well-behaved: `StoreCompiler.columnType` raises a proper
`ModelException("SQL column type 'Other' has no scalar Pure type")` for the same column
(verified, `/home/user/audit/findings/A27-repros/Fallbacks.java`). Two owners, two error classes.

---

### [CRASH] 8. `Ddl` generates DDL its own execution targets reject: `BINARY(n)`/`VARBINARY(n)` on DuckDB, `VARCHAR(2147483647)` / `DECIMAL(0,0)` on H2

**Evidence** — `Ddl.java:439-440` spell `BINARY(size)` / `VARBINARY(size)` for every
flavor; `Ddl.java:391-403` has DuckDB deltas only for `Float_` and `Bit`.
`RelationalDataType.fromName:140` and `FromProtocol.java:337-338` produce
`Varchar(Integer.MAX_VALUE)` for a size-less `Varchar`, and `FromProtocol.java:342-344`
produces `Decimal(0,0)` for a precision-less `Decimal`.

**Repro** (`/home/user/audit/findings/A27-repros/DropCreate2.java` — real pipeline, DuckDB):
```
T_BIN     java.sql.SQLException: ... Binder Error: Type 'BLOB' does not take any type parameters
T_VBIN    java.sql.SQLException: ... Binder Error: Type 'BLOB' does not take any type parameters
```
Acceptance matrix (`/home/user/audit/findings/A27-repros/DbAccept.java`, `/home/user/audit/findings/A27-repros/Extra.java`) — every DDL
type `Ddl.java` can emit, created on all three drivers:
```
DDL type               | duckdb                    | sqlite      | h2
BINARY(4)              | REJECT (BLOB no params)   | OK          | OK
VARBINARY(8)           | REJECT (BLOB no params)   | OK          | OK
OTHER                  | REJECT (no such type)     | OK          | OK -> JAVA_OBJECT
VARCHAR(2147483647)    | OK                        | OK          | REJECT (Precision must be ...)
DECIMAL(0, 0)          | REJECT                    | OK          | REJECT
NUMERIC(0, 0)          | REJECT                    | OK          | REJECT
VARCHAR(0) / CHAR(0)   | OK                        | OK          | REJECT
(everything else in the table below: accepted by all three)
```
The H2 rows matter because the `H2_EXEC` text is recorded and **replayed on a real H2
connection** by the advisory mirror (`StatementExecutor.java:2934-2939` records
`Ddl.createTable(def, schema)`; `H2Verify` replays the ledger).

---

### [CRASH] 9. `CsvSeed.sqls` throws for a table that has ANY `BINARY`/`VARBINARY`/`SEMISTRUCTURED` column, even when the CSV never mentions it

**Evidence** — `CsvSeed.java:74-80` builds the `CREATE TABLE` from **all** of
`tableType.get().columns()`, not from the CSV header, and `ddlType` has no arm for
`Type.Primitive.BYTE` or `ClassType(Variant)` (`CsvSeed.java:151-152`).

**Repro** (`/home/user/audit/findings/A27-repros/WideProbe.java`; the CSV block names only
`ID,C_DEC1002,C_DEC3818,C_VC10`):
```
=== CsvSeed.sqls (the EXECUTION form: SeedSqlForms:112) ===
Exception in thread "main" com.legend.error.NotImplementedException: csv seed DDL type for BYTE is not mapped
	at com.legend.exec.CsvSeed.ddlType(CsvSeed.java:151)
	at com.legend.exec.CsvSeed.blockSqls(CsvSeed.java:79)
	at com.legend.exec.CsvSeed.sqls(CsvSeed.java:43)
```
A single `BINARY(4)` column anywhere on the table makes the whole seed unusable.
`TestDataGenerator.compareCsv` on the **same** table is perfectly happy — its `duckType`
maps `Binary`/`SemiStructured` to `VARCHAR` (`/home/user/audit/findings/A27-repros/BinCmp.java`):
```
  loadSide temp col B    store=Binary[size=4]         duckType=VARCHAR
  loadSide temp col J    store=SemiStructured[]        duckType=VARCHAR
  CsvSeed.sqls on the same table -> com.legend.error.NotImplementedException: csv seed DDL type for BYTE is not mapped
```
So the assert side and the seed side disagree about which *tables* are supported at all.

---

### [INCONSISTENCY / UNSOUND] 10. `setUpDataSQLs` has TWO mappings: the text a golden asserts is not the DDL that executes

**Evidence** — `SeedSqlForms.java` routes the same platform call two ways:
* bare `setUpDataSQLs(csv, db)` &rarr; `assertForm` &rarr; `Ddl.setUpDataSqlsText`
  (`SeedSqlForms.java:57-61`), which spells from `RelationalDataType`;
* `setUpDataSQLs(csv, db)->map(s|executeInDb(...))` — the shape every corpus setup body
  uses — &rarr; `mappedExecutionForm` &rarr; `CsvSeed.sqls`
  (`SeedSqlForms.java:105-113`, dispatched at `StatementExecutor.java:2540-2546`),
  which spells from the Pure `Type`.

**Repro** (`/home/user/audit/findings/A27-repros/WideProbe.java`, one store, one CSV, both forms):
```
=== CsvSeed.sqls (the EXECUTION form: SeedSqlForms:112) ===
  CREATE TABLE T_ALL (ID BIGINT, C_BIGINT BIGINT, C_SMALLINT BIGINT, C_TINYINT BIGINT, C_INT BIGINT,
                      C_FLOAT DOUBLE, C_DOUBLE DOUBLE, C_REAL DOUBLE, C_BIT BOOLEAN, C_TS TIMESTAMP,
                      C_DATE DATE, C_VC10 VARCHAR, C_CHAR3 VARCHAR,
                      C_DEC1002 DECIMAL(38, 9), C_DEC3818 DECIMAL(38, 9), C_NUM50 DECIMAL(38, 9))
=== Ddl.setUpDataSqlsText (the ASSERT form: SeedSqlForms:59) ===
  Create Table T_ALL(ID INT NOT NULL,C_BIGINT BIGINT NULL,C_SMALLINT SMALLINT NULL,C_TINYINT TINYINT NULL,
                     C_INT INT NULL,C_FLOAT FLOAT NULL,C_DOUBLE DOUBLE NULL,C_REAL REAL NULL,C_BIT BIT NULL,
                     C_TS TIMESTAMP NULL,C_DATE DATE NULL,C_VC10 VARCHAR(10) NULL,C_CHAR3 CHAR(3) NULL,
                     C_DEC1002 DECIMAL(10, 2) NULL,C_DEC3818 DECIMAL(38, 18) NULL,C_NUM50 NUMERIC(5, 0) NULL,
                     PRIMARY KEY(ID));
```
Every integer width, every decimal scale, both string widths, the boolean spelling, and
all constraints differ. **Which one execution uses:** `CsvSeed` — the `->map(executeInDb)`
arm. What breaks when the `Ddl` text is right: the golden text promises `DECIMAL(38,18)`
and `PRIMARY KEY`, so a reviewer reading the asserted SQL concludes the seeded table can
hold 18 fractional digits and reject duplicate keys; neither is true of the table the
test actually queried (findings 1 and 2 above).

Running the asserted text itself is also not viable on either engine
(`/home/user/audit/findings/A27-repros/E2E.java`):
```
### ASSERT form (Ddl.setUpDataSqlsText) run on H2
  SQL: Drop schema if exists default cascade;
   -> ERROR Syntax error ... expected "identifier"
  SQL: insert into T_ALL (...) values (1,12345678.99,...,9223372036854775807,...);
   -> ERROR Numeric value out of range: "9223372036854775807" in column "C_INT"
### ASSERT form run on DuckDB
  Create Table T_BIN(... C_BIN BINARY(4) NULL, PRIMARY KEY(ID));
   -> ERROR Binder Error: Type 'BLOB' does not take any type parameters
```

---

### [INCONSISTENCY] 11. SIX independent type mappings over the same relation — full diff in §T1/§T2

Summary of the disagreements (evidence tables below):
| pair | disagreement |
|---|---|
| `Ddl.spell` vs `TestDataGenerator.duckType` | `SmallInt/TinyInt/Integer_`: `SMALLINT/TINYINT/INTEGER` vs `BIGINT`; `Real`: `REAL` vs `DOUBLE`; `Char_(n)`: `CHAR(n)` vs `VARCHAR`; `Bit`: `BIT` vs `BOOLEAN`; `Binary/Varbinary/SemiStructured`: `BINARY(n)/VARBINARY(n)/JSON` vs `VARCHAR`; `Distinct/Other/Array/Object_`: **throw** vs `VARCHAR` |
| `Ddl.spell` vs `Ddl.dataTypeToSqlText` | `Integer_`: `INTEGER` vs `INT`; `Other`: **throw** vs `OTHER` |
| `Ddl.spell` vs `PlanText.spell` | `Binary/Varbinary/SemiStructured/Distinct/Other/Array/Object_`: `Ddl` spells `BINARY(n)`/`JSON`/throws where `PlanText` throws `NotImplementedException` for all seven |
| `CsvSeed.ddlType` vs `PureSql.type`+`TypeNames` | every decimal: `DECIMAL(38,9)` vs `DECIMAL(p,s)`; `Variant`: **throw** vs `JSON`; `LatestDate`: **throw** vs `TIMESTAMP` |
| `TypeNames.H2` vs `SqlTypeCensus.normalizeMeta` | finding 13 |
| `SqlTypeCensus.wireSpelling` vs `SqlTypeCensus.metaToType` | `metaToType(wireSpelling(Decimal))` is `null` for **every** decimal (`SqlTypeCensus.java:355-367` has no `DECIMAL(...)` arm) — the census cannot round-trip its own spelling |

---

### [INCONSISTENCY / DATA CORRUPTION] 12. Two CSV parsers, three different results for the same cell

**Evidence** — `Ddl.csvCells` (`Ddl.java:287-311`) is quote-aware; `CsvSeed`
(`CsvSeed.java:93`) is `lines[i].split(",", -1)` with no quote handling at all, plus
`.strip()` at `CsvSeed.java:104`; `TestDataGenerator.flushBlock`
(`TestDataGenerator.java:1264-1270`) is a third `split(",", -1)` + `.strip()`.

**Repro** (`/home/user/audit/findings/A27-repros/RT2.java`, one store, one CSV cell, both generators):
```
cell=["a,b"]
  CsvSeed: INSERT INTO T (ID, S) VALUES ('1', '"a')       <- cell SPLIT, quote kept, 'b' lost
  Ddl:     insert into T (ID,S) values (1,'a,b');
cell=["a""b"]
  CsvSeed: INSERT INTO T (ID, S) VALUES ('1', '"a""b"')
  Ddl:     insert into T (ID,S) values (1,'a""b');        <- CSV escape NOT unescaped ("a"b" expected)
cell=[ "x"]
  CsvSeed: INSERT INTO T (ID, S) VALUES ('1', '"x"')
  Ddl:     insert into T (ID,S) values (1,' "x"');
```
Embedded newline (`/home/user/audit/findings/A27-repros/RT2.java`) corrupts differently in each:
```
  CsvSeed: INSERT INTO T (ID, S) VALUES ('1', 'line1'), ('line2', NULL)   <- 'line2' becomes the BIGINT id
  Ddl:     insert into T (ID,S) values (1,'line1');
  Ddl:     insert into T (ID) values (line2);                             <- bare identifier in a numeric column
```

---

### [INCONSISTENCY] 13. `SqlTypeCensus.normalizeMeta` contradicts `TypeNames.H2` — every H2 `DOUBLE` and every H2 `DECIMAL` read is counted as a wire DIVERGENCE

**Evidence** — `TypeNames.java:54` declares H2's own spelling:
```java
        m.put(SqlType.Scalar.DOUBLE, "DOUBLE PRECISION");
```
`SqlTypeCensus.normalizeMeta` (`SqlTypeCensus.java:400-418`) has arms for
`CHARACTER VARYING`, `DECIMAL*`, `NUMERIC*`, `TIMESTAMP*`, `STRUCT`, `MAP`, `ARRAY` —
**none for `DOUBLE PRECISION`**, and `metaToType` (`:355-367`) has no arm either, so
`delivers()` returns false. H2 also reports a bare `DECIMAL` (no precision) from
`getColumnTypeName`, which fails `delivers`'s `meta.substring(8, ...)` parse
(`SqlTypeCensus.java:337-346`).

**Repro** (`/home/user/audit/findings/A27-repros/CensusRun.java`, a plain `Float[0..1]` and `Decimal[0..1]`
column read over H2 through `Compiler.execute`):
```
H2 plan sql: SELECT t0.ID AS id, t0.F AS f, t0.D AS d, t0.S AS s FROM T AS t0
CENSUS after H2: plans=1 cols: agree=4 ... | wire: agree=1 tolerated=0 delivered=1 ... diverge=2 unknown=0
  1x wire[H2] label=DOUBLE <> meta=DOUBLE PRECISION
  1x wire[H2] label=DECIMAL(10,2) <> meta=DECIMAL
  samples: {wire[H2] label=DECIMAL(10,2) <> meta=DECIMAL=[d], wire[H2] label=DOUBLE <> meta=DOUBLE PRECISION=[f]}
```
Per-dialect coverage of `normalizeMeta`/`metaToType` (`/home/user/audit/findings/A27-repros/Census.java`) — the
rows that produce `null` are the ones the census scores as divergence:
```
### H2      DOUBLE -> DOUBLE PRECISION -> null   FLOAT -> DOUBLE PRECISION -> null   REAL -> REAL -> null
            CHAR(3) -> CHARACTER -> null         DECIMAL(10,2) -> DECIMAL -> null    NUMERIC -> DECIMAL -> null
            INTEGER/SMALLINT/TINYINT -> null
### DuckDB  FLOAT/REAL -> FLOAT -> null          BIT -> BIT -> null                  DECIMAL(10,2) -> ok(exact match)
            INTEGER/SMALLINT/TINYINT -> null
```
`SqlTypeCensus.java:287` claims the diverge bucket is one "every lane pins at
EQUALITY-0", and `RelationalCorpusRunner.java:744-748` asserts exactly that. The
`DOUBLE PRECISION` gap means the pin holds only because no H2-lane query happens to
project a `Float` column.

---

### [FORWARD/BACKWARD ASYMMETRY] 14. `generateTestData`'s CSV scrub is destructive — generate &rarr; seed &rarr; generate is not idempotent, and `assertTestData` then fails

**Evidence** — `TestDataGenerator.java:1088-1093`, the CSV cell projection:
```java
                    projs.add("replace(replace(replace(" + q(c)
                            + ", chr(39), ' '), ',', ';'), chr(10), ' ')"
                            + " as " + q(c));
```
Commas become `;`, single quotes become a space, newlines become a space. Nothing marks
the substitution, and re-ingest cannot invert it.

**Repro** (`/home/user/audit/findings/A27-repros/Tdg.java`, source rows `'a,b'`, `'O''Brien'`, `''`,
`e'line1\nline2'`):
```
=== generated CSV ===
AMT,ID,NAME,TS
-0.000000000000000001,2,O Brien,---null---          <- O'Brien
0.000000000000000000,3,,---null---                  <- '' (empty string)
12345678901234567890.123456789012345678,1,a;b,2024-01-02 03:04:05.123456   <- a,b
---null---,4,line1 line2,---null---                 <- line1\nline2
```
Full loop (`/home/user/audit/findings/A27-repros/Tdg2.java`): generate &rarr; `CsvSeed` re-seed &rarr; generate:
```
  RESEEDED ID=1 NAME=[a;b] AMT=12345678901234567890.123456789 TS=2024-01-02 03:04:05.123456
  RESEEDED ID=3 NAME=<NULL> AMT=0E-9 TS=null
=== regenerated CSV from the reseeded db ===
0.000000000,3,---null---,---null---
12345678901234567890.123456789,1,a;b,2024-01-02 03:04:05.123456

compareCsv(original-gen, regenerated) = assertTestData: rows of 'T_P' differ (2 asymmetric rows)
expected:
0.000000000000000000,3,,---null---
12345678901234567890.123456789012345678,1,a;b,2024-01-02 03:04:05.123456
got:
0.000000000,3,---null---,---null---
12345678901234567890.123456789,1,a;b,2024-01-02 03:04:05.123456
```
Two independent losses show up: the `DECIMAL(38,9)` truncation (finding 1) and the
empty-string/NULL conflation (finding 16). The generator's own comparator declares the
round trip a failure.

---

### [SILENT FALLBACK] 15. `limit 20` silently truncates the row set the caller explicitly named

**Evidence** — `TestDataGenerator.java:276` (root fetch), `:344` (view-child seed fetch),
`:370` (join-child fetch): every fetch SQL ends `+ " limit 20"`, unconditionally. No
count check, no warning, no wall.

**Repro** (`/home/user/audit/findings/A27-repros/Tdg3.java`, 25 explicit `createRowIdentifier` entries):
```
row identifiers requested: 25
CSV data rows produced:    20
fetch SQL: select "root"."ID", "root"."NAME" from T_P as "root" where ("root"."ID" = 1) or ... or ("root"."ID" = 25) limit 20
```
Five rows the caller named by primary key are dropped with no signal. For a child fetch
the same cap silently narrows an association's cardinality: a parent with 25 children
yields exactly 20 in the generated data (confirmed in `/home/user/audit/findings/A27-repros/Tdg.java`, `T_C`
block: `k1..k20`, `k21..k25` absent).

---

### [SILENT FALLBACK / INFORMATION LOSS] 16. Empty string, whitespace-only string, and the literal `---null---` all become SQL NULL

**Evidence** — `CsvSeed.java:104,108-110`:
```java
                String tok = c < vals.length ? vals[c].strip() : "";
                ...
                if (tok.isEmpty() || tok.equals("---null---")) {
                    sql.append("NULL");
```
Identical logic in `TestDataGenerator.loadSide` (`TestDataGenerator.java:1224`).

**Repro** (`/home/user/audit/findings/A27-repros/RoundTrip.java`, `S VARCHAR(200)`, DuckDB):
```
empty string              in=[]        insertSQL: VALUES ('1', NULL)  readback: <SQL NULL>   *** NO ROUND-TRIP ***
literal ---null--- token  in=[---null---]  ... VALUES ('1', NULL)     readback: <SQL NULL>   *** NO ROUND-TRIP ***
single space              in=[ ]       insertSQL: VALUES ('1', NULL)  readback: <SQL NULL>   *** NO ROUND-TRIP ***
leading/trailing space    in=[  hi  ]  insertSQL: VALUES ('1', 'hi')  readback: [hi]         *** NO ROUND-TRIP ***
```
Combined with finding 2 this is how a `String[1]` column ends up NULL from a CSV whose
author wrote an empty string, not a null (`/home/user/audit/findings/A27-repros/NotNull.java`).

Complete round-trip census over the seed path (same repro, DuckDB):
| case | round-trips? |
|---|---|
| empty string | **no** — becomes NULL |
| `---null---` literal | **no** — becomes NULL |
| single space / leading+trailing space | **no** — stripped, or NULL |
| embedded comma (raw or quoted) | **no** — cell split, tail lost |
| embedded newline | **no** — becomes a second corrupt row |
| embedded double quote | yes |
| embedded single quote (`O'Brien`) | yes |
| unicode (`U+e9 U+4e2d U+6587 U+1f600`) | yes (verified by codepoint, `/home/user/audit/findings/A27-repros/RT2.java`) |
| 38-digit decimal | **no** — `...012345678` &rarr; `...789` (finding 1) |
| negative zero, double | yes (`-0.0`) |
| negative zero, decimal (`-0.000000000000000000`) | **no** — `0E-9` (sign and scale lost) |
| date before 1000AD (`0042-03-04`) | yes |
| timestamp sub-second | **no** — microsecond truncation (finding 6) |
| boolean `true` | yes |
| boolean `1` | **no** — `1` &rarr; `true` |

---

### [SILENT FALLBACK] 17. An unknown table or database produces a plausible-looking seed instead of an error

**Evidence** — `CsvSeed.java:82-84`:
```java
        } else {
            out.add("DELETE FROM " + qualified);
        }
```
and `Ddl.findTable` returns `null` (`Ddl.java:281`), after which `insertText`
(`Ddl.java:320-333`) treats every column as non-numeric and every cell as a string.

**Repro** (`/home/user/audit/findings/A27-repros/Fallbacks.java`):
```
=== Ddl.setUpDataSqlsText with an UNKNOWN table in the CSV ===
  insert into NOSUCH (A,B) values ('1','x');
=== CsvSeed.sqls with an UNKNOWN table / null db ===
  known-db,unknown-table: DELETE FROM NOSUCH
  known-db,unknown-table: INSERT INTO NOSUCH (A, B) VALUES ('1', 'x')
  null-db: DELETE FROM T
  null-db: INSERT INTO T (ID) VALUES ('7')
  unknown-db: DELETE FROM T
  unknown-db: INSERT INTO T (ID) VALUES ('7')
```
A typo'd database FQN silently degrades from "create the typed table and seed it" to
"delete whatever table has that name and insert strings into it".

Related, same file: `Ddl.setUpDataSqlsText`'s include closure drops an unresolvable
include with `lookup.apply(inc).ifPresent(...)` (`Ddl.java:366-367`) — the tables of a
missing include contribute nothing and nothing says so.

---

### [SILENT FALLBACK] 18. `TestDataGenerator.duckType` collapses seven distinct store types to `VARCHAR`

**Evidence** — `TestDataGenerator.java:1298-1306`:
```java
            case RelationalDataType.Binary ignored -> "VARCHAR";
            case RelationalDataType.Varbinary ignored -> "VARCHAR";
            case RelationalDataType.Distinct ignored -> "VARCHAR";
            case RelationalDataType.Other ignored -> "VARCHAR";
            case RelationalDataType.SemiStructured ignored -> "VARCHAR";
            case RelationalDataType.Array ignored -> "VARCHAR";
            case RelationalDataType.Object_ ignored -> "VARCHAR";
```
The comment three lines above (`:1296-1297`) says "EXPLICIT per variant so a new variant
is a compile error, never a silent VARCHAR (T3.1)". Being written out per-arm makes the
*addition* of a variant loud; it does not make the *coercion* anything but silent. A
`SEMISTRUCTURED` column compared through `compareCsv` is diffed as text, so
`{"a":1, "b":2}` and `{"b":2, "a":1}` are unequal, and the same column is `JSON` in
`Ddl.spell` (`Ddl.java:445`) and `JSON` at the wire (`PureSql`/`TypeNames`) — three
different opinions about one column. Verified reachable in `/home/user/audit/findings/A27-repros/BinCmp.java`.

---

### [SILENT FALLBACK] 19. A demanded column name that matches no declared column silently enters the fetch list and the CSV header

**Evidence** — `TestDataGenerator.java:126-130` (census) and `:219-222` (fetch col map):
```java
            cols.add(loc.def().columns().stream()
                    .map(DatabaseDefinition.ColumnDefinition::name)
                    .filter(n -> n.equalsIgnoreCase(c))
                    .findFirst().orElse(c));
```
`orElse(c)` — a mapping that names a column the table does not declare is not walled; the
raw name flows into `necessaryColumns` output (which never executes, so the wrong census
is returned silently) and into the fetch SQL (where it becomes a DB-level error whose
message names a column the user never sees in the store). The sibling lookup
`column(def, name)` (`TestDataGenerator.java:1014`) *does* throw for the same condition —
two policies for one question in one file.

---

### [SILENT FALLBACK] 20. `collectTableCols`'s `default -> { }` drops the columns of any unhandled join-condition node

**Evidence** — `TestDataGenerator.java:733-767`; the switch handles `ColumnRef`,
`TargetColumnRef`, `Comparison`, `BooleanOp`, `Group`, `IsNull`, `IsNotNull`,
`FunctionCall`, and then:
```java
            default -> {
            }
```
A join condition built from any other `RelationalOperation` variant contributes **no**
columns to the parent fetch, so the child fetch's `ON` clause references a column that
was never materialized into the parent temp table. The failure surfaces as a DuckDB
"column not found" against a generated temp-table name, not as a wall naming the
unsupported node — and `renderCondition` (`:817`) *does* have a loud wall for the same
node kinds, so the two halves of the same walk disagree about whether the shape is
supported.

---

### [CRASH / MISATTRIBUTED ERROR] 21. `seedDataString` blames the primary key for a NULL that came from a nullable data column

**Evidence** — `TestDataGenerator.java:1349-1354`:
```java
        if (v == null) {
            throw new NotImplementedException(
                    "testDataGen: NULL row-identifier cell — a primary"
                    + " key produced no value");
        }
```
`seedDataString` (`:1385-1400`) builds `cols` from the PK columns **plus** every scanned
query column, then calls `pureRepr` on all of them (`:1418-1421`). Any NULL in a
`[0..1]` data column hits the PK message.

**Repro** (`/home/user/audit/findings/A27-repros/Tdg3.java`; `AMT DECIMAL(38,18)` is nullable and all rows have
`AMT IS NULL`; `ID` is a fully populated PK):
```
=== seedDataString on a table with a NULL nullable column ===
THROW com.legend.error.NotImplementedException: testDataGen: NULL row-identifier cell — a primary key produced no value
```
Also fires for the ordinary `generateTestData` query shape in `/home/user/audit/findings/A27-repros/Tdg.java`.

---

### [UNSOUND / INJECTION] 22. `Ddl.insertText` splices a numeric column's CSV cell into the SQL raw and unquoted

**Evidence** — `Ddl.java:330-333`:
```java
            String cell = cells.get(c);
            boolean numeric = col != null && isNumericType(col.dataType());
            values.add(numeric ? cell.strip()
                    : "'" + cell.replace("'", "''") + "'");
```
No validation that the cell is a number.

**Repro** (`/home/user/audit/findings/A27-repros/NotNull.java`, `ID INTEGER PRIMARY KEY`):
```
=== Ddl raw-numeric-cell passthrough (isNumericType -> cell emitted UNQUOTED) ===
  insert into T (ID,NAME) values (1); DROP TABLE T; --,'x');
  insert into T (ID,NAME) values (not_a_number,'x');
```
The first splices a second statement into the generated SQL; the second emits a bare
identifier where a number is required (which resolves as a *column reference* on H2
rather than failing on the value). An empty numeric cell produces `values (,'orphan')`
— syntactically invalid SQL (same repro).

---

### [SILENT FALLBACK] 23. Row/header arity mismatches are absorbed differently by each generator

**Repro** (`/home/user/audit/findings/A27-repros/Misc.java`, header `ID,S`):
```
row=[1,a,EXTRA,MORE]
   CsvSeed: INSERT INTO T (ID, S) VALUES ('1', 'a')     <- 2 extra cells dropped
   Ddl:     insert into T (ID,S) values (1,'a');
row=[1]
   CsvSeed: INSERT INTO T (ID, S) VALUES ('1', NULL)    <- missing cell -> NULL
   Ddl:     insert into T (ID) values (1);              <- missing cell -> column dropped
```
`CsvSeed.java:103-104` (`c < vals.length ? ... : ""`) vs `Ddl.java:318`
(`c < header.size() && c < cells.size()`). Extra data is discarded without a word by both;
a short row means "NULL" to one and "omit the column" to the other.

Also `CsvSeed.java:53-55` silently returns for a block with fewer than three lines, while
`TestDataGenerator.flushBlock` (`:1260-1263`) throws `NotImplementedException` on the same
shape (`/home/user/audit/findings/A27-repros/Misc.java`):
```
=== CsvSeed block with fewer than 3 lines (silently dropped) ===
  []
```

---

### [DEAD TYPE LOGIC] 24. Four of `CsvSeed.ddlType`'s eight arms are unreachable

`ddlType`'s only caller is `CsvSeed.java:79`, whose argument is always a column type from
`ModelContext.findTable` &rarr; `PureModelContext.findTable:346` &rarr;
`StoreCompiler.tableSchema/viewSchema` &rarr; `StoreCompiler.columnType:182-208`. That
function's entire range is `{BOOLEAN, INTEGER, FLOAT, PrecisionDecimal(p,s), STRING,
BYTE, STRICT_DATE, DATE_TIME, ClassType(Variant)}` or a thrown `ModelException`.
Therefore:
* `Type.Primitive.NUMBER` (`CsvSeed.java:132`) — unreachable
* `Type.Primitive.DECIMAL` (`:144`) — unreachable (`columnType` always builds a
  `PrecisionDecimal`)
* `Type.Primitive.DATE` (`:141`) — unreachable
* `Type.EnumType` (`:148`) — unreachable; **no store column is ever enum-typed**, so the
  task's "enum domain" question has no seed-side surface at all
* `Type.Primitive.BYTE` and `ClassType(Variant)` fall to the throw at `:151` — the
  *reachable* gap (finding 9); `LATEST_DATE`/`STRICT_TIME` are unreachable throws.

Full input/output census in §T2.

---

### [DOC-LIE] 25. `Ddl.csvCells`'s javadoc claims CSV semantics it does not implement

`Ddl.java:284-286` says "Quote-aware split; a cell whose FIRST character is the quote
unquotes (CSV semantics)". It strips the outer quotes (`Ddl.java:304-309`) but never
un-doubles an escaped quote, so RFC-4180 `"a""b"` (meaning `a"b`) yields `a""b`
(`/home/user/audit/findings/A27-repros/Misc.java`):
```
  a,"b""c",d               -> [a, b""c, d]
  a,"unterminated,d        -> [a, "unterminated,d]
```

### [DOC-LIE] 26. `TestDataGenerator.java:1296-1297` — "never a silent VARCHAR (T3.1)"

See finding 18: seven store types are coerced to `VARCHAR`. Writing the arms out
individually makes future variants a compile error; it does not make these coercions
non-silent.

### [DOC-LIE] 27. `SqlTypeCensus.java:287` — "the diverge bucket every lane pins at EQUALITY-0"

True as an assertion (`RelationalCorpusRunner.java:744-748`), false as a description of
the census's correctness: finding 13 shows two false-positive divergences from a
four-column H2 read of ordinary `Float`/`Decimal` columns.

### [SILENT FALLBACK] 28. `SqlTypeCensus` swallows `RuntimeException`

`SqlTypeCensus.java:210` catches `java.sql.SQLException | RuntimeException` and converts
any failure — including a genuine NPE/CCE bug inside the probe — into a
`wire-unknown[...] probe-error=` counter. Isolation from execution is the stated intent
and is achieved; the cost is that a *census* bug is indistinguishable from a *driver*
hiccup in the numbers the corpus pins on. `:344` (`catch NumberFormatException |
IndexOutOfBoundsException -> return false`) is conservative and correct in direction.
`TestDataGenerator.java:1689` (`catch (SQLException ignored)` in `dropTemps`) is
cleanup-only and benign.

---

# §T1 — THE COMPLETE `RelationalDataType` &rarr; DDL TABLE (run, not read)

Produced by `/home/user/audit/findings/A27-repros/MapTable.java` (reflective invocation of the private
`Ddl.spell(RelationalDataType, Flavor)`, `Ddl.dataTypeToSqlText`,
`TestDataGenerator.duckType`, `PlanText.spell`, `RelationalKinds.pureKindOf`,
`StoreCompiler.columnType` over every constructible variant). Pasted verbatim:

```
RelationalDataType       | Ddl H2_EXEC      | Ddl DUCK_EXEC    | Ddl ENGINE_TEXT  | TDG.duckType     | PlanText.spell | RelKinds.pure  | StoreCompiler.columnType
BigInt[]                 | BIGINT           | BIGINT           | BIGINT           | BIGINT           | BIGINT         | Integer        | INTEGER
SmallInt[]               | SMALLINT         | SMALLINT         | SMALLINT         | BIGINT           | SMALLINT       | Integer        | INTEGER
TinyInt[]                | TINYINT          | TINYINT          | TINYINT          | BIGINT           | TINYINT        | Integer        | INTEGER
Integer_[]               | INTEGER          | INTEGER          | INT              | BIGINT           | INT            | Integer        | INTEGER
Float_[]                 | FLOAT            | DOUBLE           | FLOAT            | DOUBLE           | FLOAT          | Float          | FLOAT
Double_[]                | DOUBLE           | DOUBLE           | DOUBLE           | DOUBLE           | DOUBLE         | Float          | FLOAT
Real[]                   | REAL             | REAL             | REAL             | DOUBLE           | REAL           | Float          | FLOAT
Bit[]                    | BIT              | BOOLEAN          | BIT              | BOOLEAN          | BIT            | Boolean        | BOOLEAN
Timestamp[]              | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP      | DateTime       | DATE_TIME
Date_[]                  | DATE             | DATE             | DATE             | DATE             | DATE           | StrictDate     | STRICT_DATE
Varchar[size=10]         | VARCHAR(10)      | VARCHAR(10)      | VARCHAR(10)      | VARCHAR          | VARCHAR(10)    | String         | STRING
Varchar[size=2147483647] | VARCHAR(2147483647) | VARCHAR(2147483647) | VARCHAR(2147483647) | VARCHAR | VARCHAR(2147483647) | String  | STRING
Char_[size=3]            | CHAR(3)          | CHAR(3)          | CHAR(3)          | VARCHAR          | CHAR(3)        | String         | STRING
Binary[size=4]           | BINARY(4)        | BINARY(4)        | BINARY(4)        | VARCHAR          | THROW NotImplementedException | Byte  | BYTE
Varbinary[size=8]        | VARBINARY(8)     | VARBINARY(8)     | VARBINARY(8)     | VARCHAR          | THROW NotImplementedException | Byte  | BYTE
Decimal[p=10, s=2]       | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10,2)  | Decimal        | PrecisionDecimal[10,2]
Decimal[p=38, s=18]      | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38,18) | Decimal        | PrecisionDecimal[38,18]
Decimal[p=38, s=9]       | DECIMAL(38, 9)   | DECIMAL(38, 9)   | DECIMAL(38, 9)   | DECIMAL(38, 9)   | DECIMAL(38,9)  | Decimal        | PrecisionDecimal[38,9]
Numeric[p=5, s=0]        | NUMERIC(5, 0)    | NUMERIC(5, 0)    | NUMERIC(5, 0)    | DECIMAL(5, 0)    | NUMERIC(5,0)   | Decimal        | PrecisionDecimal[5,0]
Distinct[]               | THROW ISE        | THROW ISE        | THROW ISE        | VARCHAR          | THROW NIE      | Distinct       | THROW ModelException
Other[]                  | THROW ISE        | THROW ISE        | OTHER            | VARCHAR          | THROW NIE      | Other          | THROW ModelException
SemiStructured[]         | JSON             | JSON             | JSON             | VARCHAR          | THROW NIE      | Variant        | ClassType[...::variant::Variant]
Array[element=Integer_]  | THROW ISE        | THROW ISE        | THROW ISE        | VARCHAR          | THROW NIE      | Array          | THROW ModelException
Object_[Varchar,Integer_]| THROW ISE        | THROW ISE        | THROW ISE        | VARCHAR          | THROW NIE      | Object         | THROW ModelException
```
(ISE = `java.lang.IllegalStateException`, NIE = `com.legend.error.NotImplementedException`.)

### DB acceptance of every generated DDL type (`/home/user/audit/findings/A27-repros/DbAccept.java`, all 3 drivers)

```
DDL type               | duckdb                | sqlite       | h2
BIGINT/SMALLINT/TINYINT/INTEGER/INT | OK       | OK           | OK
FLOAT                  | OK -> FLOAT (4-byte)  | OK -> FLOAT  | OK -> DOUBLE PRECISION
DOUBLE                 | OK -> DOUBLE          | OK -> DOUBLE | OK -> DOUBLE PRECISION
REAL                   | OK -> FLOAT (4-byte)  | OK -> REAL   | OK -> REAL (4-byte)
BIT                    | OK -> BIT (BITSTRING) | OK -> BIT    | OK -> BOOLEAN
BOOLEAN/TIMESTAMP/DATE | OK                    | OK           | OK
VARCHAR(10)            | OK -> VARCHAR         | OK           | OK -> CHARACTER VARYING
VARCHAR(2147483647)    | OK                    | OK           | REJECT: Precision ("2147483647") must be b...
CHAR(3)                | OK -> VARCHAR         | OK -> CHAR   | OK -> CHARACTER  (pads on write)
BINARY(4)              | REJECT: Type 'BLOB' does not take any type parameters | OK | OK
VARBINARY(8)           | REJECT: Type 'BLOB' does not take any type parameters | OK | OK
DECIMAL(10,2)/(38,18)/(38,9) | OK              | OK -> DECIMAL| OK -> DECIMAL
NUMERIC(5, 0)          | OK -> DECIMAL(5,0)    | OK           | OK -> NUMERIC
JSON                   | OK -> JSON            | OK           | OK -> JSON (reads back as byte[])
OTHER                  | REJECT: Type with name OTHER does not exist | OK | OK -> JAVA_OBJECT
DECIMAL(0,0)/NUMERIC(0,0) | REJECT             | OK           | REJECT
VARCHAR(0)/CHAR(0)     | OK                    | OK           | REJECT
```
Note `BIT` on DuckDB is a **bitstring**, not a boolean:
`INSERT 1 into BIT -> OK got=00000000000000000000000000000001`;
`INSERT true into BIT -> OK got=00000001`. `Ddl.spell`'s `DUCK_EXEC` delta
(`Ddl.java:396-398`) is what keeps the executed DDL correct; the `H2_EXEC` text —
which is replayed on H2 by the advisory mirror — spells `BIT`, which H2 reads as
`BOOLEAN`, so the two targets happen to agree.

Also note `JSON` on H2 reads back as `byte[]` with the value re-quoted
(`"{\"a\":1}"`), a decode asymmetry (`/home/user/audit/findings/A27-repros/EdgeVals.java`).

# §T2 — THE COMPLETE Pure `Type` &rarr; `CsvSeed.ddlType` TABLE + THREE-WAY DIFF

Every constructible Pure `Type` through `CsvSeed.ddlType` (`/home/user/audit/findings/A27-repros/MapTable.java`):
```
Primitive[Number]        -> DOUBLE                        (UNREACHABLE from findTable)
Primitive[Integer]       -> BIGINT
Primitive[Float]         -> DOUBLE
Primitive[Decimal]       -> DECIMAL(38, 9)                (UNREACHABLE)
Primitive[String]        -> VARCHAR
Primitive[Boolean]       -> BOOLEAN
Primitive[Byte]          -> THROW NotImplementedException  (REACHABLE: BINARY/VARBINARY columns)
Primitive[Date]          -> TIMESTAMP                     (UNREACHABLE)
Primitive[StrictDate]    -> DATE
Primitive[DateTime]      -> TIMESTAMP
Primitive[LatestDate]    -> THROW NotImplementedException  (unreachable)
Primitive[StrictTime]    -> THROW NotImplementedException  (unreachable)
PrecisionDecimal(38,18)  -> DECIMAL(38, 9)                <- finding 1
PrecisionDecimal(10,2)   -> DECIMAL(38, 9)                <- finding 1
PrecisionDecimal(38,0)   -> DECIMAL(38, 9)
PrecisionDecimal(0,0)    -> DECIMAL(38, 9)
PrecisionDecimal(38,38)  -> DECIMAL(38, 9)
PrecisionDecimal(5,5)    -> DECIMAL(38, 9)
ClassType[model::Person] -> THROW NotImplementedException
EnumType[model::Color]   -> VARCHAR                       (UNREACHABLE)
TypeVar[T]               -> THROW NotImplementedException
GenericType[Relation<T>] -> THROW NotImplementedException
RelationType[(a:Integer[1])] -> THROW NotImplementedException
FunctionType[{ -> Integer[1]}] -> THROW NotImplementedException
SchemaAlgebra[T+V]       -> THROW NotImplementedException
null                     -> THROW NotImplementedException  (no NPE — `t == Type.Primitive.X` is null-safe)
```

Three-way diff against the query pipeline's own mapping
(`/home/user/audit/findings/A27-repros/Diff3.java`; `PureSql.type` is the Pure&rarr;`SqlType` boundary at
`lowering/PureSql.java:68-93`; `TypeNames` is the dialect CAST spelling;
`SqlTypeCensus.wireSpelling`/`metaToType` is the wire comparison vocabulary):
```
PURE TYPE       | CsvSeed.ddlType | PureSql.type      | TypeNames.DUCKDB | TypeNames.H2     | Census.wireSpell | Census.metaToType(round-trip)
Number          | DOUBLE          | DOUBLE            | DOUBLE           | DOUBLE PRECISION | DOUBLE           | DOUBLE
Integer         | BIGINT          | BIGINT            | BIGINT           | BIGINT           | BIGINT           | BIGINT
Float           | DOUBLE          | DOUBLE            | DOUBLE           | DOUBLE PRECISION | DOUBLE           | DOUBLE
Decimal         | DECIMAL(38, 9)  | Decimal[38,18]    | DECIMAL(38,18)   | DECIMAL(38,18)   | DECIMAL(38,18)   | null(unknown)   <-- 2 disagreements
String          | VARCHAR         | VARCHAR           | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
Boolean         | BOOLEAN         | BOOLEAN           | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN
Byte            | THROW(NIE)      | THROW(ISE)        | -                | -                | -                | -
Date            | TIMESTAMP       | TIMESTAMP         | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP
StrictDate      | DATE            | DATE              | DATE             | DATE             | DATE             | DATE
DateTime        | TIMESTAMP       | TIMESTAMP         | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP
LatestDate      | THROW(NIE)      | TIMESTAMP         | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP       <-- disagreement
Decimal(38,18)  | DECIMAL(38, 9)  | Decimal[38,18]    | DECIMAL(38,18)   | DECIMAL(38,18)   | DECIMAL(38,18)   | null(unknown)   <-- disagreement
Decimal(10,2)   | DECIMAL(38, 9)  | Decimal[10,2]     | DECIMAL(10,2)    | DECIMAL(10,2)    | DECIMAL(10,2)    | null(unknown)   <-- disagreement
Decimal(5,0)    | DECIMAL(38, 9)  | Decimal[5,0]      | DECIMAL(5,0)     | DECIMAL(5,0)     | DECIMAL(5,0)     | null(unknown)   <-- disagreement
model::Color    | VARCHAR         | VARCHAR           | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
Variant         | THROW(NIE)      | JSON              | JSON             | JSON             | JSON             | JSON            <-- disagreement
Any             | THROW(NIE)      | JSON              | JSON             | JSON             | JSON             | JSON
Nil             | THROW(NIE)      | VARCHAR           | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
model::Person   | THROW(NIE)      | THROW(ISE)        | -                | -                | -                | -
T               | THROW(NIE)      | THROW(ISE)        | -                | -                | -                | -
```
**Which one execution uses**: `CsvSeed.ddlType` creates the physical seed table;
`PureSql.type`+`TypeNames` types every CAST the query pipeline emits;
`SqlTypeCensus` only measures. So the *storage* is `DECIMAL(38,9)` while the *query
contract* is `DECIMAL(p,s)` — findings 1 and 10.

---

# VERIFIED SOUND

Checked and found correct (all by running, not reading):

* **`Ddl` spelling of `BigInt`/`Double_`/`Timestamp`/`Date_`/`Decimal(p,s)`/`Numeric(p,s)`
  /`Varchar(n)`/`SemiStructured`** — accepted by all three drivers and round-tripping the
  Pure edge value exactly (`BIGINT` holds `9223372036854775807`; `DECIMAL(38,18)` holds
  `12345678901234567890.123456789012345678` on DuckDB and H2 bit-for-bit;
  `DATE` holds `0001-01-02` and `9999-12-31` on all three).
* **`Ddl`'s DuckDB deltas** (`Ddl.java:392-398`): `Float_ -> DOUBLE` and `Bit -> BOOLEAN`
  are exactly the two spellings DuckDB would otherwise get wrong (`FLOAT` is 4-byte,
  `BIT` is a bitstring). Verified by creating both flavors and reading the metadata.
* **`Ddl.dataTypeToSqlText`'s two deltas** (`Integer_ -> INT`, `Other -> OTHER`) — I
  confirmed these are the only two, and that `MetamodelWalk.java:1578` and the
  `ENGINE_TEXT` flavor are its only readers, so the "spelled ONCE" claim holds.
* **`Ddl.createTable` column quoting**: `ENGINE_TEXT` quotes only pre-quoted, reserved,
  or space-bearing names (`Ddl.java:113-132`); the exec flavors full-quote. Both behave as
  documented on the wide-type model.
* **`Ddl`'s include closure** (`collectClosure`, `Ddl.java:354-379`): include-first,
  group-by-schema, first-table-wins de-dup — behaves as described; only the unresolvable-
  include drop is silent (finding 17).
* **`CsvSeed`'s uniform quoted-literal insert policy** (`CsvSeed.java:112-119`): every
  value rides as a quoted literal and the DB casts. Verified this is genuinely uniform
  (no host-side type dispatch), and that DuckDB casts `'9223372036854775807'` into
  `BIGINT` and `'2024-01-02 03:04:05.123456789'` into `TIMESTAMP` correctly.
* **`CsvSeed`'s single-quote escaping** (`.replace("'", "''")`) — `O'Brien` round-trips.
* **Unicode round-trip through the seed path** — verified by codepoint, not by console
  rendering: `U+e9 U+4e2d U+6587 U+1f600` in == out, `equal = true`.
* **Dates before 1000 AD** — `0042-03-04` and `0001-01-02` round-trip through `DATE` on
  all three drivers.
* **`CsvSeed`'s DROP-then-CREATE** (never `CREATE OR REPLACE`) — H2 2.1.214 confirmed to
  have no `OR REPLACE` for tables; the two-statement form works on both engines.
* **`TestDataGenerator`'s DB-side discipline**: hashing, the CSV scrub, per-table dedup
  (`union` / `select distinct`), and the row-text assembly are all genuinely in SQL; row
  values cross into Java only as display strings (`csvEnvelope`, `:1030-1120`). Verified
  by reading the emitted SQL in the `sqls` list of a real run.
* **`TestDataGenerator`'s child-fetch join over the parent temp table** — the generated
  child rows are always join-reachable from the fetched parents (no orphans), verified on
  a `P[1] <-> C[*]` association.
* **`TestDataGenerator.compareCsv`'s `EXCEPT`-both-ways diff** — correctly reports 0 for
  identical input and correctly normalizes decimal spellings (`1.5` vs
  `1.500000000000000000` compare equal through `DECIMAL(38,18)` temps).
* **`TestDataGenerator.necessaryColumns`** — PK ++ non-nullable ++ tree ++ milestoning in
  encounter order, de-duplicated; matches the code and the run
  (`[default, T_P, ID,NAME,AMT,TS]`).
* **`SqlTypeCensus` is measurement-only** — I traced every write: `LongAdder`s and two
  `ConcurrentHashMap`s, no path back into planning or execution;
  `Executor.java:88/276/282/574` are the only call sites, and `probeWire`'s catch
  (`:210`) guarantees isolation. The claim "nothing here can produce a result or affect
  execution" holds.
* **`SqlTypeCensus.delivers`'s integer-width chain and same-scale decimal narrowing**
  (`:328-348`) are directionally correct (narrower fits wider), and `wire-delivered[...]
  BIGINT <- INTEGER` fires exactly where expected.
* **`TypeNames`' "absence is LOUD" contract** — `scalarNames` genuinely has no default;
  `ANSI` lacks `JSON`, `DUCKDB`/`H2` add it; no silent fallback in the record itself.
* **`Type.PrecisionDecimal`'s constructor invariants** — rejects `precision < 0` and
  `scale ∉ [0, precision]`, so `DECIMAL(5,7)` cannot be constructed.
* **`CsvSeed.ddlType(null)`** does not NPE (`t == Type.Primitive.X` is null-safe); it
  reaches the loud throw.

---

# NOT COVERED

* **`Ddl.setUpDataSqlsTextFromRecords`** (`Ddl.java:219-257`) — the pre-split records
  form. I read it in full and it shares `insertText`/`findTable` with the string form
  (so findings 17, 22, 23 apply verbatim), but I did not construct a `list([...])`
  literal query to drive it end-to-end through `SeedSqlForms.assertForm`.
* **`TestDataGenerator`'s view-fetch leg** (`viewFetchSql`, `emitViewFetches`,
  `expandIfView`, `substituteViewRefs`, ~250 lines) — read in full; every unhandled shape
  is a loud `NotImplementedException` wall (`:422`, `:466`, `:472`, `:556`, `:584`,
  `:824`, `:900`), so there is no silent-fallback surface there. I did not build a
  view-on-view fixture to exercise the inner-first ordering.
* **Milestoning date filters** (`milestoningFilter` `:589-641`, `planMilestone`
  `:1637-1680`) — read in full; the business/processing dimension ORDER differs between
  the two (`business` first in the executed filter, `processing` first in the plan text,
  each with an in-code justification). I did not build a bitemporal fixture to confirm
  whether that ordering difference is observable.
* **`planText`** (`:1436-1560`) — read; it reaches `PlanText.spell`, which throws
  `NotImplementedException` for `Binary`/`Varbinary`/`SemiStructured`/`Distinct`/`Other`/
  `Array`/`Object_` (confirmed in §T1), so a plan for a table with any such column walls.
  I did not drive `planTestDataGeneration` through the harness to paste that output.
* **`hashStrings` mode** (`:1080-1088`) — the SQL sha256 tiling. Not exercised.
* **The H2 advisory mirror replay end-to-end** (`H2Verify`) — I established that
  `StatementExecutor.java:2934-2939` records the `H2_EXEC` text and that `H2Verify`
  replays recorded statements, but I did not run a mirror session to observe an
  `INTEGER`-overflow or `VARCHAR(2147483647)` decline in the ledger.
* **Whether the corpus's H2 lane actually projects a `Float` column** — finding 13 proves
  the mapping inconsistency and reproduces the divergence in isolation; I did not run the
  full corpus (forbidden: `mvn` rewrites `core/target`) to see whether the pinned
  `wireDivergeCount() == 0` assertion currently fires.
* **`Distinct` / `Array` / `Object_` store columns from protocol JSON** — `FromProtocol`
  can build `Distinct` and `Other`, but no `Array`/`Object_` arm exists there
  (`FromProtocol.java:310-353`), so those two `duckType`/`spell` arms may be entirely
  dead; I did not exhaustively prove no other construction site exists.
