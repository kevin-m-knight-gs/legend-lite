# A11-dialects — adversarial audit of `core/src/main/java/com/legend/sql/dialect/`

Scope: all 21 files in `com.legend.sql.dialect` (6,280 LOC), plus the seams that feed
them (`Compiler.dialectOf`, `lowering/PureSql`, `lowering/CastPolicy`,
`exec/Executor.unwrap`, `resolver/RawGridSchema`).

Everything below is either an exact `file:LINE` citation with quoted code, or a probe
whose **actual pasted output** follows. Probes live in
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/probes/`.
Backends actually executed: DuckDB v1.5.0, SQLite 3.47.1, H2 2.1.214 (all three JDBC
drivers on the classpath; product/version strings verified at run time).

---

## 0. Ground truth: which dialect object serves which backend

`Compiler.dialectOf(ctx, runtimeFqn, connection)` (Compiler.java:474-507) and
`Compiler.dialectOf(ctx, runtimeFqn)` (Compiler.java:510-565) resolve as follows
(**probe `Asym.java`, pasted output in §F3**):

| connection | declared `type:` | runtime given | dialect object |
|---|---|---|---|
| DuckDB | DuckDB | yes | `DuckDb` |
| SQLite | SQLite | yes | `AnsiSqlRenderer(Lexicon.SQLITE, TypeNames.ANSI, Spellings.DUCKDB)` |
| H2 2.1/2.2 | H2 | yes | `H2` |
| H2 2.3+ | H2 | yes | `H2Modern` (not executable here — only 2.1.214 on the classpath) |
| **any** | any | **null or unknown FQN** | **`DuckDb`** (silent fallback — see F3) |
| n/a (`toSQLString`) | H2 / DB2 / Composite | — | `EngineStyleH2` / `EngineStyleDB2` / `EngineStyleComposite` |

There is **no SQLite dialect class** — `Lexicon.java:32-41` says "SQLite was exactly
that and its class is gone". SQLite therefore inherits `AnsiSqlRenderer`'s
`normalize()` (identity), its `passes()` (no `SubstringClamp`), `TypeNames.ANSI`, and
**DuckDB's function spellings** (`Spellings.DUCKDB`). That single decision is the root
of findings F1, F2, F4, F5, F6, F8.

---

## FINDINGS

### [UNSOUND] F1 — SQLite: every `Boolean[1]` column carries `java.lang.Integer`, contradicting `SqlDialect`'s own contract

**Evidence.** `SqlDialect.java:5-11` states the contract verbatim:

```java
 * A backend's JDBC driver may hand back dialect-flavored Java objects
 * (SQLite: dates as Strings, booleans as ints); {@link #normalize} converts
 * to the canonical representation for a PURE type so the typed-result
 * contract holds on every backend.
```

`AnsiSqlRenderer` — the object SQLite actually gets — **does not override
`normalize`** (verified: `grep -n normalize AnsiSqlRenderer.java DuckDb.java
EngineStyleH2.java EngineStyleDB2.java` → no matches). Only `H2.java:475` overrides it.
So the "SQLite: booleans as ints" case the javadoc names is precisely the case with no
converter.

**Repro** (`probes/Multi.java`, fixture `fx/n_*.pure`, `fx/n_ddl.sql`):

```
QUERY: model::Rec2.all()->project(~[x:r|$r.opt->isEmpty()])
---- DuckDB ----
  [SQL] SELECT t0.OPT IS NULL AS x FROM T2 AS t0
  [COLS] x:Boolean[1]
  [ROW] Boolean(true) |
---- SQLite ----
  [SQL] SELECT t0.OPT IS NULL AS x FROM T2 AS t0
  [COLS] x:Boolean[1]
  [ROW] Integer(1) |
  [ROW] Integer(0) |
---- H2 ----
  [COLS] x:Boolean[1]
  [ROW] Boolean(true) |
```

Same for `['a','b']->contains($r.name)` → `[COLS] x:Boolean[1]` / `[ROW] Integer(0)`,
and for a mapped Boolean property (`$r.flag`) → `[COLS] f:Boolean[1]` / `[ROW] Integer(1)`.

`exec/PureAsserts.java:210-212` is the repo's own carrier table: `Integer -> "Integer"`,
`Boolean -> "Boolean"`. So an `Integer` in a `Boolean[1]` slot is a type-system
violation by the repo's own definition.

**Why it matters.** The declared static type is violated by the wire value on the
simplest possible predicate query. Everything downstream (JSON serialization,
`assertEquals`, `instanceOf`) sees the wrong Pure kind.

---

### [UNSOUND] F2 — SQLite: `Decimal[1]` columns carry `java.lang.Double`; declared scale and exact value are destroyed

**Evidence.** `exec/Executor.java:649-661` is the whole decode: `dialect.normalize(...)`
(identity for SQLite) then an arm that repairs `BigDecimal`→integral **only** for
`BIGINT/INTEGER/HUGEINT`. There is **no arm for `SqlType.Decimal`**, and no
SQLite `normalize`.

`TypeNames.ANSI` has no DECIMAL row at all: `AnsiSqlRenderer.java:923-924` renders
`DECIMAL(p, s)` structurally for every dialect. SQLite's type-affinity rules turn any
`DECIMAL(...)` into REAL.

**Repro** (`probes/Multi.java`, `probes/SqliteAff.java`):

```
QUERY: model::Rec.all()->project(~[n:r|$r.name, a:r|$r.amt, q:r|$r.qty])
---- DuckDB ----  [COLS] a:Decimal[1]   [ROW] BigDecimal(1.500000)
---- SQLite ----  [COLS] a:Decimal[1]   [ROW] Double(1.5)
---- H2 ----      [COLS] a:Decimal[1]   [ROW] BigDecimal(1.500000)
```

```
SELECT CAST('1.234567890123456789' AS DECIMAL(38, 18)) AS v, typeof(...) t
    -> v=1.2345678901234567(Double) t=real(String)          # SQLite: 18 digits -> 17
SELECT CAST('12345678901234567890.123' AS DECIMAL(38,18)) v
    -> v=1.2345678901234567E19(Double) t=real
```

And through a Pure cast:

```
QUERY: model::Rec2.all()->project(~[x:r|$r.qty->cast(@Decimal)])
---- SQLite ----  [SQL] SELECT CAST(t0.QTY AS DECIMAL(38, 18)) AS x ...
                  [COLS] x:Decimal[1]   [ROW] Integer(3)     # not even a Double
```

**Why it matters.** `Decimal` is the one Pure type whose entire purpose is exactness.
On SQLite it is silently a binary float, and the compiler still stamps `Decimal[1]`.

---

### [UNSOUND] F3 — SQLite: `StrictDate[1]` / `DateTime[1]` columns produced by a cast carry `java.lang.Integer(0)`; `JSON` casts collapse to `0`

**Evidence.** `TypeNames.base()` (TypeNames.java:47-62) hands SQLite the names
`DATE`, `TIMESTAMP`, `TIMESTAMPTZ`, `BOOLEAN`, `HUGEINT`. SQLite has **none** of these
types; its `CAST` accepts any name-token and applies affinity rules, so every one of
them silently reinterprets rather than erroring. `TypeNames.java:17` claims
"absent = unsupported (loud)" — but presence of a name SQLite does not implement is
just as silent as absence would have been loud.

**Repro** (`probes/CastExec.java` — one `SELECT CAST(<sample> AS <rendered name>)` per
distinct cell of the type matrix, executed on all three drivers):

```
rendered CAST type              | DuckDB                        | SQLite                        | H2
BOOLEAN                         | OK true [Boolean/BOOLEAN]     | OK 1 [Integer/INTEGER]        | OK true [Boolean/BOOLEAN]
HUGEINT                         | OK 17014118346046~ [BigInteger]| OK 92233720368547~ [Long]     | ERR Unknown data type: "HUGEINT"
DATE                            | OK 2020-01-02 [LocalDate]     | OK 2020 [Integer/INTEGER]     | OK 2020-01-02 [Date/DATE]
TIMESTAMP                       | OK 2020-01-02 03:~ [Timestamp]| OK 2020 [Integer/INTEGER]     | OK 2020-01-02 03:~ [Timestamp]
TIMESTAMPTZ                     | OK 2020-01-02T01:~ [OffsetDT] | OK 2020 [Integer/INTEGER]     | ERR Unknown data type: "TIMESTAMPTZ"
JSON                            | OK [1,2,3] [JsonNode/JSON]    | OK 0 [Integer/INTEGER]        | OK bytes:"[1,2,3]" [byte[]/JSON]
DECIMAL(38, 18)                 | OK 1.234567890123~[BigDecimal]| OK 1.234567890123~ [Double]   | OK 1.234567890123~[BigDecimal]
DECIMAL(10, 2)                  | OK 1.24 [BigDecimal]          | OK 1.239 [Double]  (no round) | OK 1.24 [BigDecimal]
VARCHAR(5)                      | OK abcdefghij (no truncate)   | OK abcdefghij (no truncate)   | OK abcde (TRUNCATES)
VARCHAR[]                       | OK [a, b] [DuckDBArray]       | OK ['a','b'] [String/TEXT]    | ERR Syntax error
DECIMAL(38, 18)[]               | OK [1.50000000000~]           | ERR SQL error                 | ERR Syntax error
MAP(VARCHAR, BIGINT)            | OK NULL [MAP(VARCHAR,BIGINT)] | ERR SQL error                 | ERR Unknown data type: "MAP"
STRUCT(f INTEGER, g VARCHAR)    | OK NULL [STRUCT(...)]         | ERR SQL error                 | ERR Unknown data type: "STRUCT"
```

`HUGEINT` on SQLite is worse than "wrong type": the value
`170141183460469231731687303715884105727` came back as `9223372036854775807`
— **silent saturation to Long.MAX_VALUE** (`probes/SqliteAff.java`):

```
SELECT CAST('170141183460469231731687303715884105727' AS HUGEINT)  -> 9223372036854775807(Long)
SELECT CAST('9223372036854775808' AS BIGINT)                       -> 9223372036854775807(Long)
SELECT CAST('true' AS BOOLEAN)                                     -> 0(Integer)
```

End-to-end through Pure:

```
QUERY: model::Rec2.all()->project(~[x:r|$r.name->cast(@StrictDate)])
---- DuckDB ----  [EXEC-ERROR] Conversion Error: invalid date field format: "abc"
---- SQLite ----  [COLS] x:StrictDate[1]   [ROW] Integer(0)          <-- silent
---- H2 ----      [EXEC-ERROR] Cannot parse "DATE" constant "abc"

QUERY: model::Rec2.all()->project(~[x:r|$r.name->cast(@DateTime)])
---- SQLite ----  [COLS] x:DateTime[1]     [ROW] Integer(0)          <-- silent

QUERY: model::Rec2.all()->project(~[x:r|$r.name->cast(@Integer)])
---- SQLite ----  [COLS] x:Integer[1]      [ROW] Integer(0)          <-- silent (both peers raise)
```

**Why it matters.** Three of the twelve `SqlType.Scalar` spellings SQLite is handed do
not exist there and are silently reinterpreted; two more (`BOOLEAN`, `DECIMAL`) change
the carrier class. A user gets `0` where DuckDB and H2 both raise.

---

### [UNSOUND] F4 — SQLite: `String[1]` `toString()` on a temporal returns **null** for every row (DuckDB's `strftime` argument order is emitted verbatim)

**Evidence.** `Spellings.java:120` maps `SqlFn.STRFTIME -> "strftime"` and
`Compiler.java:561-564` hands `Spellings.DUCKDB` to SQLite. DuckDB is
`strftime(ts, fmt)`; SQLite is `strftime(fmt, timestring)`. The arguments are emitted
in DuckDB order, so SQLite reads the timestamp as a format string and the format as a
time value — **and returns NULL instead of erroring**.

**Repro:**

```
QUERY: model::Rec.all()->project(~[x:r|$r.ts->toString()])
---- DuckDB ----
  [SQL] SELECT strftime(t0.TS, '%Y-%m-%dT%H:%M:%S.%g+0000') AS x FROM T_REC AS t0
  [COLS] x:String[1]
  [ROW] String(2020-01-02T03:04:05.000+0000) |
---- SQLite ----
  [SQL] SELECT strftime(t0.TS, '%Y-%m-%dT%H:%M:%S.%g+0000') AS x FROM T_REC AS t0
  [COLS] x:String[1]
  [ROW] null |
  [ROW] null |
  [ROW] null |
---- H2 ----
  [PLAN-ERROR] DialectCapability: minimal-fraction date format reached a dialect without a trim-zeros token
```

Direct confirmation (`probes/Sq2.java`):

```
SELECT strftime('2020-01-02 03:04:05', '%Y-%m-%dT%H:%M:%S.%g+0000') AS v  ->  NULL
SELECT strftime('%Y-%m-%d', '2020-01-02 03:04:05') AS v                   ->  2020-01-02 (String)
```

**Why it matters.** This is the top prize: multiplicity `[1]` (lower bound 1, i.e.
non-null by construction) is violated by a `null` cell, silently, on every row.
Nothing downstream can distinguish it from a legitimately absent value — and `[1]`
says absence is impossible.

---

### [UNSOUND] F5 — the raw-SQL late-bound hole: **every** `cast(@T)` off a grid cell is an unchecked assertion; the compiler's claimed type is arbitrary

This is the item 7 "designed hole". Characterized precisely below.

**Mechanism (code).**
1. `Typer.java:1392-1393` types `executeInDb(sql, …)` as
   `Type.relation(Type.RelationType.lateBound())`.
2. `Type.java:440-445`: `lateBound()` = zero real columns + one dynamic wildcard;
   `Type.java:511-514` `trustedColumn(name)` = `Any[0..1]` for **any** name asked for
   ("THE TRUST-NAME RULE").
3. `lowering/CastPolicy.java:75-95`: a cast emits SQL **only** when
   `isSqlPrimitive(target) && isSqlPrimitive(src)`. `src` here is `Any` (a `ClassType`),
   so `isSqlPrimitive(src)` is false → `return value;` (line 96). **No SQL CAST is
   emitted.**
4. `CastPolicy.crossKindRaise` (line 181-206) returns null when `familyOf(src) == null`
   — and `familyOf(Any)` is null (line 208-213). **The cross-kind safety net is
   disabled** on raw grids.
5. `exec/Executor.java:783-806` + `:812-828`: for a late-bound schema the SQL type is
   `null` and "the wire KIND decides"; there is no check against the Pure type the
   compiler stamped.

Net: `cast(@T)` on a raw-grid cell is a **pure re-labelling with zero enforcement at
any of the three layers**.

**Repro** (`probes/Raw2.java`, DuckDB session, no model):

```
### [4] executeInDb('select 1.7 as A', $c, 0, 1000).rows->map(r|$r.value('A')->cast(@Integer));
   [G] Integer [*]
   [SQL] SELECT t0.A AS value FROM (select 1.7 as A) AS t0        <-- no CAST at all
   [COL] value : Integer
   [ROW] BigDecimal(1.7)                                          <-- Integer[*] holding 1.7

### [5] ... ->cast(@Integer))->sum();
   [G] Integer [1]
   [SQL] SELECT list_sum((SELECT LIST(t1.value) FROM ( SELECT t0.A AS value FROM (select 1.7 as A) AS t0 ) AS t1)) AS value
   [SCALAR] BigDecimal(1.7)                                       <-- Integer[1] = 1.7

### [6] executeInDb('select 42 as A', ...).rows->map(r|$r.value('A')->cast(@String));
   [G] String [*]     [COL] value : String     [ROW] Integer(42)

### [7] ... ->cast(@StrictDate));
   [G] StrictDate [*] [COL] value : StrictDate [ROW] Integer(42)

### [8] ... ->cast(@Boolean));
   [G] Boolean [*]    [COL] value : Boolean    [ROW] Integer(42)
        (contrast: the SAME cast off a typed column emits
         error('Cast exception: Integer cannot be cast to Boolean') — crossKindRaise
         is alive there and dead here)

### [9] executeInDb('select cast(1 as HUGEINT) * 100000000000000000000 as A', ...)->cast(@Integer)
   [G] Integer [*]    [ROW] BigInteger(100000000000000000000)     <-- Pure Integer is 64-bit
```

**Missing / drifted columns.** Naming a column that does not exist type-checks clean
and dies as a raw driver exception at execution:

```
### [3] executeInDb('select 1 as A, 2 as B', ...).rows->map(r|$r.value('NOPE'));
   [G] meta::pure::metamodel::type::Any [*]
   [SQL] SELECT t0.NOPE AS value FROM (select 1 as A, 2 as B) AS t0
   [EXEC-ERR] java.sql.SQLException: Binder Error: Values list "t0" does not have a column named "NOPE"
```

**Extra columns** are harmless (`SELECT *` adopts headers, `Executor.java:726-740`).
**Type disagreement** with a downstream operator surfaces as a driver binder error, not
a compile error:

```
### executeInDb('select 42 as A', ...).rows->map(r|$r.value('A')->cast(@String)->toUpper());
   [G] String [*]
   [SQL] SELECT upper(t0.A) AS value FROM (select 42 as A) AS t0    <-- cast elided
   [EXEC-ERR] java.sql.SQLException: Binder Error: No function matches 'upper(INTEGER)'
```

**Blast radius, precisely:**
- Cast **soundness**: 0% enforced. Any Pure primitive can be asserted onto any database
  value; the wire carries the database's Java class. Demonstrated for
  `Integer/String/StrictDate/Boolean` targets.
- `crossKindRaise` (the "burn lane" that makes `1->cast(@Boolean)` raise on typed
  columns) is **entirely bypassed** for raw grids.
- Aggregates over mis-cast cells inherit the wrong kind (`sum()` → `BigDecimal` in an
  `Integer[1]` slot).
- Nonexistent column names: no compile diagnostic; a raw `java.sql.SQLException`
  (CRASH-class, not a `LegendCompileException`).
- Range: `Integer[*]` can hold a `BigInteger` beyond 64 bits.
- The hole is **entered by any `executeInDb`/`fetchDb*` call**, and by
  `Compiler.execute(model, query, connection)` with no runtime, which is a public
  3-arg overload (Compiler.java:577).

---

### [UNSOUND / SILENT-FALLBACK] F6 — `RawSqlBoundary.h2ToDuckDb` regex-rewrites **inside string literals**, silently changing user data

**Evidence.** `RawSqlBoundary.java:151-185` applies five unanchored regexes to the whole
statement text with no lexer. `RawSqlAdapt.java:22-31` runs it on every
`SqlSource.RawSql` for the DuckDB dialect (`DuckDb.java:85-88` puts `RawSqlAdapt` in
`passes()`), i.e. on every corpus/user-authored `executeInDb` string on a DuckDB
session.

**Repro A — the boundary in isolation (`probes/Boundary.java`):**

```
REWRITE  IN : select 'CURRENT_TIMESTAMP()' as A
          OUT: select 'CURRENT_TIMESTAMP' as A
REWRITE  IN : select 'count(*), 1' as A, 2 as B
          OUT: select 'count(*) AS "COUNT(*)", 1' as A, 2 as B
REWRITE  IN : select 'create schema foo' as A
          OUT: select 'Create schema if not exists foo' as A
REWRITE  IN : select * from t where name = 'drop schema x if exists'
          OUT: select * from t where name = 'Drop schema if exists x'
REWRITE  IN : create table t (id int, txt varchar(10) default 'a FLOAT b')
          OUT: create table t ("id" int, "txt" varchar(10) default 'a DOUBLE b')
```

**Repro B — end-to-end through the compiler (`probes/Raw3.java`):**

```
### executeInDb('select \'CURRENT_TIMESTAMP()\' as A', $c, 0, 1000).rows
   [SQL] SELECT * FROM (select 'CURRENT_TIMESTAMP' as A) AS t0
   [COL] A : meta::pure::metamodel::type::Any
   [ROW] String(CURRENT_TIMESTAMP)                <-- the user's literal lost its "()"

### executeInDb('select \'create schema foo\' as A', $c, 0, 1000).rows
   [SQL] SELECT * FROM (select 'Create schema if not exists foo' as A) AS t0
   [ROW] String(Create schema if not exists foo)
```

**Why it matters.** The class's own javadoc (RawSqlBoundary.java:20-23) admits the
contract is "aspirational" and calls itself "the one sanctioned home for pattern-based
SQL rewriting". In practice it is a data-corrupting text filter that a user cannot
opt out of on DuckDB, and it also mutates `WHERE` predicates and `DEFAULT` clauses.

---

### [INCONSISTENCY / UNSOUND] F7 — `length()` diverges across backends on astral characters and on non-strings

**Evidence + repro** (`probes/Multi.java`, `probes/Str.java`):

```
QUERY: model::Rec.all()->project(~[x:r|$r.name->length()])   # row 2 = 'Ünïcödé😀'
---- DuckDB ----  [SQL] SELECT length(CAST(t0.NAME AS VARCHAR)) ...  [ROW] Long(8)
---- SQLite ----  [SQL] SELECT length(t0.NAME) ...                   [ROW] Integer(8)
---- H2 ----      [SQL] SELECT length(t0.NAME) ...                   [ROW] Long(9)
```

```
length('😀ab')   ->  DuckDB 3 (Long) | SQLite 3 (Integer) | H2 4 (Long)
```

H2 counts **UTF-16 code units**; DuckDB and SQLite count **code points**. The declared
Pure type is `Integer[1]` on all three; the value differs. `Spellings.java:83` maps
`LENGTH -> "length"` for both the DuckDB and H2 rows with no note of this.

Second divergence in the same function: `DuckDb.java:73-77` wraps every non-`StringLit`
argument in `CAST(... AS VARCHAR)` ("the engine's implicit varchar coercion"); SQLite
and H2 get the bare call, which on a decimal yields a different number:

```
length(CAST(1.50 AS DECIMAL(18,6)))  ->  DuckDB ERR | SQLite 3 | H2 8
```

---

### [UNSOUND / INCONSISTENCY] F8 — `substring` start-clamp exists only on DuckDB; SQLite silently returns one character too few

**Evidence.** `lowering/Scalars.java:1422-1430` emits `SUBSTRING` **verbatim**
("the DuckDB start-clamp is SubstringClamp, that dialect's own rewrite pass").
`SubstringClamp` is registered only in `DuckDb.passes()` (`DuckDb.java:85-88`).
`SubstringClamp.java:13-21` reasons about exactly two backends:

```
 * H2 clamps a sub-1 start at its own runtime; DuckDB counts empties — so
 * the DuckDB EXECUTION dialect clamps in the rewrite chain, and the
 * engine-TEXT dialect (which has no such pass) stays verbatim ...
```

SQLite is not considered, and SQLite does **not** clamp.

**Repro:**

```
QUERY: model::Rec.all()->project(~[x:r|$r.name->substring(0,3)])       # NAME='abc'
---- DuckDB ----  [SQL] SELECT substr(t0.NAME, 1, 3) AS x ...   [ROW] String(abc)
---- SQLite ----  [SQL] SELECT substr(t0.NAME, 0, 3) AS x ...   [ROW] String(ab)    <-- WRONG
---- H2 ----      [SQL] SELECT substr(t0.NAME, 0, 3) AS x ...   [ROW] String(abc)
```

Raw confirmation (`probes/Str.java`): `substr('abcdef',0,3)` → DuckDB `ab`
(pre-clamp), SQLite `ab`, H2 `abc`. Negative starts agree (`-2,3` → `ef` on all
three); `1,3` and `2,100` agree.

---

### [UNSOUND] F9 — SQLite: case functions are ASCII-only; `toUpper`/`toLower` silently leave non-ASCII unchanged

```
QUERY: model::Rec.all()->project(~[x:r|$r.name->toUpper()])
---- DuckDB ----  [ROW] String(ÜNÏCÖDÉ😀)
---- SQLite ----  [ROW] String(ÜNïCöDé😀)     <-- 'ü' uppercased (it was already Ü), ï/ö/é untouched
---- H2 ----      [ROW] String(ÜNÏCÖDÉ😀)
```
```
upper('ünïcödé')  ->  DuckDB ÜNÏCÖDÉ | SQLite üNïCöDé | H2 ÜNÏCÖDÉ
lower('ÜNÏCÖDÉ')  ->  DuckDB ünïcödé | SQLite ÜnÏcÖdÉ | H2 ünïcödé
```

Type is `String[1]` on all three; value differs. No wall, no note in `Spellings`.

---

### [UNSOUND] F10 — decimal arithmetic: the compiler's precision/scale derivation is DEAD CODE, and every backend computes a different type

**Evidence.** `Type.PrecisionDecimal.plus/minus/times/dividedBy` +
`adjustPrecisionScale` (Type.java:201-245, ~45 lines of the
"MS-SQL → Hive → Spark → Calcite lineage") are referenced **nowhere in
`core/src/main`** — only by `core/src/test/.../PrecisionDecimalArithmeticTest.java`
(verified: `grep -rn "dividedBy\|adjustPrecisionScale" core/src/main` returns only
Type.java itself). The type checker instead uses the flat Pure signature
`plus(Decimal[1], Decimal[1]): Decimal[1]` (`builtin/Pure.java:1996`) and
`times(Decimal,Decimal): Decimal` (`Pure.java:2204`), so `amt * amt` is typed plain
`Decimal`, which `lowering/PureSql.java:75` maps to `SqlType.Decimal(38,18)`.
No dialect emits a widening cast around arithmetic.

**What the compiler's own (unused) rules say vs. what the backends do**
(`probes/Dec.java`):

```
Type.PrecisionDecimal rules for DECIMAL(18,6) op DECIMAL(18,6):
  plus      -> PrecisionDecimal[precision=19, scale=6]
  times     -> PrecisionDecimal[precision=37, scale=12]
  dividedBy -> PrecisionDecimal[precision=38, scale=20]
Type.PrecisionDecimal rules for DECIMAL(38,18) op DECIMAL(38,18):
  plus      -> PrecisionDecimal[precision=38, scale=17]
  times     -> PrecisionDecimal[precision=38, scale=6]
  dividedBy -> PrecisionDecimal[precision=38, scale=6]

== DuckDB
   SELECT A + A  -> 246913.578024 [BigDecimal / DECIMAL(18,6) prec=18 scale=6]
   SELECT A * A  -> ERR Out of Range Error: Overflow in multiplication of DECIMAL(18)
                        (123456789012 * 123456789012)
== SQLite
   SELECT A + A  -> 246913.578024        [Double / FLOAT prec=0 scale=0]
   SELECT A * A  -> 1.5241578753153482E10[Double / FLOAT prec=0 scale=0]
   SELECT B * B  -> 1.5241578753238834   [Double / FLOAT prec=0 scale=0]
   SELECT B / B  -> 1.0                  [Double / FLOAT prec=0 scale=0]
== H2
   SELECT A + A  -> 246913.578024                          [NUMERIC prec=19 scale=6]
   SELECT A * A  -> 15241578753.153483936144               [NUMERIC prec=36 scale=12]
   SELECT A / A  -> 1.000000000000000000000000000000000000 [NUMERIC prec=54 scale=36]
   SELECT B + B  -> 2.469135780246913578                   [NUMERIC prec=39 scale=18]
   SELECT B * B  -> 1.524157875323883675019051998750190521 [NUMERIC prec=76 scale=36]
   SELECT B / B  -> 1.000...(76 zeros)                     [NUMERIC prec=114 scale=76]
```

Divergences against the compiler's claimed type, per dialect:

| op on `DECIMAL(38,18)` | claimed (p,s) | DuckDB | SQLite | H2 |
|---|---|---|---|---|
| `+` | (38,17) | (38,18)-ish, no overflow | REAL, p=0 s=0 | **(39,18) — precision 39 > MAX_PRECISION 38** |
| `*` | (38,6)  | **runtime OVERFLOW at (18,6)²** | REAL, 17 sig digits | **(76,36)** |
| `/` | (38,6)  | — | REAL | **(114,76)** |

Through the pipeline (`probes/Multi.java`):

```
QUERY: model::Rec.all()->project(~[a:r|$r.amt, sum2:r|$r.amt + 1.5d, prod:r|$r.amt * $r.amt, div:r|$r.amt / 3.0d])
---- DuckDB ---- [SQL] SELECT t0.AMT AS a, t0.AMT + 1.5 AS sum2, t0.AMT * t0.AMT AS prod, ((1.0 * t0.AMT) / 3.0) AS div ...
                 [EXEC-ERROR] Out of Range Error: Overflow in multiplication of DECIMAL(18)
---- SQLite ---- [COLS] a:Decimal[1] sum2:Decimal[1] prod:Decimal[1] div:Float[1]
                 [ROW] Double(0.333333) | Double(1.833333) | Double(0.11111088888899999) | Double(0.111111)
---- H2 -------- [ROW] BigDecimal(0.333333) | BigDecimal(1.833333) | BigDecimal(0.111110888889) | Double(0.111111)
```

Note also `prod` for row 2: SQLite `0.11111088888899999`, H2 `0.111110888889` — the same
Pure expression, the same declared `Decimal[1]`, two different values.

Secondary: `Decimal / Decimal` types as `Float[1]` (Pure.java:1282
`divide(Number,Number): Float`), so `dividedBy` could never fire even if it were wired.

**Why it matters.** A whole datatype-derivation subsystem exists, is unit-tested, and
is not connected to anything; meanwhile the actual result precision is whatever the
backend happens to do, and one backend (DuckDB) **hard-fails** on a multiplication the
compiler's own rule says fits in 38 digits.

---

### [SILENT FALLBACK] F11 — `Compiler.dialectOf` silently defaults to the DuckDB renderer for a null or unknown runtime

**Evidence.** Compiler.java:510-518:

```java
    static com.legend.sql.dialect.SqlDialect dialectOf(ModelContext ctx,
            @com.legend.Nullable String runtimeFqn) {
        if (runtimeFqn == null) {
            return new com.legend.sql.dialect.DuckDb();
        }
        var rt = ctx.findRuntime(runtimeFqn);
        if (rt.isEmpty()) {
            return new com.legend.sql.dialect.DuckDb();
        }
```

An **unknown** runtime FQN is not an error — it is a DuckDB dialect. This survives the
connection-aware overload for every non-H2 product (Compiler.java:477-480).

**Repro** (`probes/Asym.java`, SQLite connection):

```
== SQLite conn + runtime=null            -> DuckDb
== SQLite conn + runtime='test::NOPE'    -> DuckDb
== SQLite conn + runtime='test::R'       -> AnsiSqlRenderer
```

The public 3-arg `Compiler.execute(model, query, connection)` (Compiler.java:577) always
passes `runtimeFqn = null`, so every raw-SQL/`executeInDb` query on a SQLite or H2
session gets the **DuckDB** renderer (including `RawSqlAdapt`'s text rewriting, F6).
This is exactly the "unknown binding … silently guessed/defaulted" the brief forbids.

---

### [INCONSISTENCY] F12 — `Compiler.plan()` renders **DuckDB** SQL for an H2 runtime while `Compiler.execute()` renders H2 SQL

**Evidence.** Compiler.java:545-547:

```java
                // H2 rides the ANSI-flavored DuckDB renderer: the corpus
                // executes H2-typed connections on the session's DuckDB, and
                // every emission H2 sees is the ANSI subset.
                case H2 -> distinct.add("DuckDB");
```

**Repro** (`probes/Asym.java`, same model + same query + same runtime `test::R` whose
connection declares `type: H2`):

```
== Compiler.plan(H2-runtime)  [dialectOf(ctx,runtime)] ==
   SELECT date_part('year', t0.D) AS x, starts_with(t0.NAME, 'a') AS y FROM T_REC AS t0
== execute path [dialectOf(ctx,runtime,conn)] -> H2
   SELECT extract(YEAR FROM t0.D) AS x, (LEFT(t0.NAME, CHAR_LENGTH('a')) = 'a') AS y FROM T_REC AS t0
```

Neither `date_part` nor `starts_with` exists on H2 2.1.214 (H2.java:83-95 exists
precisely because they don't). The public `plan()` surface therefore hands the caller
SQL that cannot run on the declared backend. The comment above the arm is also a
**DOC-LIE**: H2 connections do *not* ride the DuckDB renderer at execution — H2.java is
selected by `dialectOf(ctx, runtime, connection)`.

---

### [CRASH/ICE] F13 — the sole `UnsupportedOperationException` in `dialect/` is reachable from an ordinary `toSQLString` query

**Evidence.** EngineStyleH2.java:1079-1082:

```java
            // engine-H2 text has no struct vocabulary — a named wall
            // (SHAPE in the plan branch), not a dialect bug
            throw new UnsupportedOperationException(
                    "plan: struct extraction has no engine-H2 spelling");
```

**Repro** (`probes/Engine2.java`, H2 session, fixture `fx/rec_H2.pure`):

```
### toSQLString(|model::Rec.all()->project(~[x:r|pair($r.name, $r.qty)->toString()]),
                model::M, meta::relational::runtime::DatabaseType.H2, [])
   ERR java.lang.UnsupportedOperationException: plan: struct extraction has no engine-H2 spelling
```

`toSQLString` is a user-callable native (StatementExecutor.java:429-466), so this is a
plain `java.lang.UnsupportedOperationException` escaping to the user on input a user
could plausibly write. Per `DialectCapability.java:6-13` it should be a
`DialectCapability` (the portability sweep classifies those UNSUPPORTED and everything
else FAIL/ERROR).

Same channel, same class of problem, different exception type:

```
### toSQLString(|... ~[x:r|$r.ts->toString()] ..., DatabaseType.H2, [])
   ERR java.lang.IllegalStateException: strftime format has no engine-H2 formatdatetime
       spelling yet: [Column[...TS...], FormatLit[parts=[YEAR4, Text[s=-], MONTH2, ...
```

(EngineStyleH2.java:1522.)

---

### [INCONSISTENCY] F14 — a JSON cast on a JSON-less dialect throws bare `IllegalStateException`, not `DialectCapability`

AnsiSqlRenderer.java:914-922:

```java
            case com.legend.sql.SqlType.Scalar s -> {
                String n = typeNames.scalarNames().get(s);
                if (n == null) {
                    throw new IllegalStateException(s + " cast reached a"
                            + " dialect without " + s + " support");
                }
```

…while nine lines below, the STRUCT arm (line 929-932) throws `DialectCapability` for
the identical situation. Hook matrix (`probes/Hooks.java`, `cast JSON` row):

```
cast JSON | ANSI IllegalStateException | SQLite IllegalStateException | DuckDb OK | H2 OK
          | H2Modern OK | EngStyH2 IllegalStateException | EngStyDB2 ISE | EngStyCmp ISE
```

`JSON` is the only scalar with an absent row today, so the whole "absence is LOUD at
the read site" mechanism in `TypeNames.java:12-18` yields the *wrong* loudness class in
100% of its live cases.

---

### [SILENT FALLBACK] F15 — four `default ->` arms in `dialect/` silently produce a value with no downstream wall

The repo's rule (`com/legend/package-info.java:31-38`):

> Sealed exhaustiveness over `default ->`: switches over sealed roots list every
> variant (javac then flags new ones). The sanctioned exceptions: guarded-pattern
> switches need a coverage default (make it THROW), and best-effort rewrite walkers may
> pass unknown nodes through ONLY where a downstream loud wall is guaranteed.

Full classification of the **25** `default ->` arms in `dialect/` (§E has the counts):

**Violations — silent value defaults, no loud wall downstream (4):**

1. `CarrierStrategies.java:301-307`
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
   The result becomes a **PIVOT output COLUMN NAME** (`CarrierStrategies.java:157`:
   `litText(v) + "__|__" + u.alias()`). A Float/Decimal/Date/Null pivot key therefore
   names a column with a **Java record `toString()`** — e.g.
   `FloatLit[value=1.5, type=Typed[...]]__|__x`. Runs on every non-DuckDB dialect
   (`Caps.H2`, which is also what SQLite gets).

2. `EngineStyleH2.java:444-449` — `case null, default -> "subselect"`: an unrecognized
   source is silently named `subselect` in the engine-text alias generator.

3+4. `H2.java:454-469` (`booleanShaped`, two nested `default -> false`). Demonstrated
   wrong answer (`probes/BoolShape.java`):

   ```
   DuckDb : SELECT CAST(t.B AS VARCHAR) AS v FROM T AS t
   H2     : SELECT CAST(t.B AS VARCHAR) AS v FROM T AS t
   DuckDB value: true
   H2 value    : TRUE
   ```
   H2.java:437-442's own javadoc says the reference must print `'true'` and that H2's
   `CAST(bool AS VARCHAR)` prints `'TRUE'`. `booleanShaped` classifies **by node shape**
   and never consults the node's `TypeFact` — the `SqlExpr.Column` in the repro carries
   `TypeFact.Typed(BOOLEAN)` and is still classified `false`.

**Sanctioned (21):** 5 throwing (`AnsiSqlRenderer:676`, `DuckDb:245`, `DuckDb:278`,
`EngineStyleH2:1422`, `H2:223`); 4 `super.` delegations
(`EngineStyleDB2:250`, `EngineStyleH2:884`, `EngineStyleH2:1585`, `H2:195`);
9 `-> null` guarded arms that fall through to a throw or to `super`
(`CarrierStrategies:252,687`, `EngineStyleDB2:194,216,270`,
`EngineStyleH2:1335,1515,1605`, `H2:120`); 3 statement no-ops
(`CarrierStrategies:198` `continue`, `EngineStyleH2:406`, `EngineStyleH2:1224`).

---

### [DOC-LIE] F16 — `TypeNames`' "absence is LOUD" contract does not hold: three names are rendered that the target rejects or reinterprets

`TypeNames.java:12-18`:

> Absence is LOUD at the read site — a scalar with no spelling here is "this backend
> cannot cast to that type", never a silent fallback.

The mechanism only fires for names that are *absent*. Names that are *present but wrong*
sail through the renderer (`probes/Hooks.java`, last three rows: `cast ARRAY`,
`cast TIMESTAMPTZ`, `cast HUGEINT` render **OK on all eight dialects**) and then fail —
or silently lie — at the database:

| rendered | H2 2.1.214 (from `probes/CastExec.java`) | SQLite |
|---|---|---|
| `TIMESTAMPTZ` (`TypeNames.h2()` keeps the base row) | `ERR Unknown data type: "TIMESTAMPTZ"` | silently `2020 (Integer)` |
| `VARCHAR[]` (`AnsiSqlRenderer:926`, structural, no capability gate) | `ERR Syntax error` | silently `TEXT` |
| `MAP(K,V)` (`AnsiSqlRenderer:927-928`, structural, no gate) | `ERR Unknown data type: "MAP"` | `ERR` |
| `HUGEINT` | `ERR` | silent saturation to `Long.MAX_VALUE` |

TIMESTAMPTZ is reachable end-to-end:

```
QUERY: model::Rec2.all()->project(~[x:r|meta::pure::functions::string::parseDate('2015-04-15T17:00:00+0200')])
---- DuckDB ---- [SQL] SELECT timezone('UTC', CAST('...' AS TIMESTAMPTZ)) AS x ...
                 [ROW] DateWithSecond(2015-04-15T15:00:00+0000)
---- SQLite ---- [EXEC-ERROR] no such function: timezone
---- H2 -------- [EXEC-ERROR] Function "TIMEZONE" not found  (TIMESTAMPTZ would fail next)
```

Related DOC-LIE: `Lexicon.java:32-41` documents SQLite's gaps as
"aliased VALUES sources … typed DATE/TIMESTAMP literals, and everything with no ANSI
encoding at all" — the actual gaps measured here are far larger (see F17), and the
listed ones are the *loud* subset, not the dangerous one.

Related DOC-LIE: `CarrierStrategies.Caps` (CarrierStrategies.java:31-42) says
"SQLite/MariaDB have correlated explosion but no native lists; H2 has neither" — yet
SQLite is handed `Caps.H2` (`AnsiSqlRenderer.java:100-102`,
`new CarrierStrategies(CarrierStrategies.Caps.H2)`), i.e. `correlatedExplode = false`.
There is no `Caps.SQLITE`.

---

### [INFORMATION LOSS] F17 — SQLite is handed DuckDB's function vocabulary; ~half of a normal query surface fails loudly, one silently

`Compiler.java:561-564` gives SQLite `Spellings.DUCKDB`. Measured on the real driver
(`probes/Multi.java`, `probes/runmany.sh`):

| Pure expression | SQLite outcome |
|---|---|
| `$r.name->startsWith('a')` | `no such function: starts_with` |
| `$r.name->contains('%')` / `->indexOf('b')` | `no such function: strpos` |
| `$r.d->year()` | `no such function: date_part` |
| `$r.d->adjust(10, DAYS)` | `no such function: to_days` |
| `$r.d->dateDiff(%2020-01-01, DAYS)` | `near "'2020-01-01'": syntax error` (typed DATE literal) |
| `$r.d > %2020-01-01` (filter) | `near "'2020-01-01'": syntax error` |
| `$r.qty->hashCode()` | `DialectCapability: signed 64-bit hashCode …` |
| `$r.rate->round()` | `DialectCapability: banker's ROUND …` |
| `[1,'a']->size()` | `DialectCapability: LIST_FILTER …` |
| `parseDate('…+0200')` | `no such function: timezone` |
| **`$r.ts->toString()`** | **silently `null` — F4** |

Loud failures are honest capability walls. The single silent one (F4) is the defect;
but the sheer count means the SQLite dialect row is essentially unusable for anything
beyond flat column projection, while the compiler happily plans it.

---

### [DEAD TYPE LOGIC] F18 — `Type.PrecisionDecimal`'s arithmetic derivation (45 lines) is unreachable from `core/src/main`

See F10. `plus`/`minus`/`times`/`dividedBy`/`adjust` (Type.java:201-245) have zero
callers outside `core/src/test/.../PrecisionDecimalArithmeticTest.java`.

---

## E. Exhaustiveness audit (task 3) — exact counts

Measured with `grep -o` over the 21 files:

| metric | count |
|---|---|
| `switch (` occurrences (code) | **42** |
| `default ->` arms | **25** |
| `default:` statement labels | **0** (the one `default:` hit is prose in H2.java:273) |
| switches with **no** default arm (javac-enforced totality) | **17** |
| `throw new DialectCapability` | **25** |
| `throw new IllegalStateException` | **21** |
| `throw new UnsupportedOperationException` | **1** |
| total throw sites in `dialect/` | **47** |

Per-file `default ->`: AnsiSqlRenderer 1, CarrierStrategies 4, DuckDb 2,
EngineStyleDB2 4, EngineStyleH2 9, H2 5.

**Verdict on the repo claim.** The claim in `com/legend/package-info.java:31-38` is
*qualified*, not absolute ("switches over sealed roots list every variant … sanctioned
exceptions …"). It is **substantially honest**: 17/42 switches are total, and 21/25
defaults are throwing, delegating, or guarded. It is **violated by 4 arms** (F15), one
of which produces a demonstrably wrong SQL value (`H2.booleanShaped`) and one of which
can emit a Java record `toString()` as a SQL column name (`CarrierStrategies.litText`).

### E.1 Capability-wall matrix (`probes/Hooks.java`) — one MIR node per hook, rendered on all 8 dialect objects

```
hook              | ANSI    | SQLite  | DuckDb | H2      | H2Modern | EngStyH2 | EngStyDB2 | EngStyCmp
arrayLit          | DC      | DC      | OK     | OK      | OK       | DC       | DC        | DC
structLit         | DC      | DC      | OK     | DC      | OK       | DC       | DC        | DC
structGet         | DC      | DC      | OK     | DC      | OK       | UOE      | UOE       | UOE
lambda            | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
variantGet        | DC      | DC      | OK     | DC      | OK       | DC       | DC        | DC
variantElements   | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
variantConstruct  | DC      | DC      | OK     | OK      | OK       | DC       | DC        | DC
hashSigned        | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
roundHalfEven     | DC      | DC      | OK     | OK      | OK       | DC       | DC        | DC
bitOp BIT_AND     | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
listCall FILTER   | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
listExists        | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
listForAll        | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
allDistinct       | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
unnestProj        | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
membership        | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
reduceCollection  | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
fold              | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
cast JSON         | ISE     | ISE     | OK     | OK      | OK       | ISE      | ISE       | ISE
cast STRUCT       | DC      | DC      | OK     | DC      | DC       | DC       | DC        | DC
cast ARRAY        | OK      | OK      | OK     | OK(*)   | OK(*)    | OK       | OK        | OK
cast TIMESTAMPTZ  | OK      | OK(*)   | OK     | OK(*)   | OK(*)    | OK       | OK        | OK
cast HUGEINT      | OK      | OK(*)   | OK     | OK      | OK       | OK       | OK        | OK
```
(DC = `DialectCapability`, ISE = `IllegalStateException`, UOE =
`UnsupportedOperationException`. `(*)` = renders fine, **rejected or silently
reinterpreted by the real database** — see F3/F16.)

### E.2 Throw sites proven REACHABLE by a plausible user query (8 distinct sites)

| site | query | outcome |
|---|---|---|
| `AnsiSqlRenderer:687` hashSigned | `$r.qty->hashCode()` | DC on SQLite **and** H2 |
| `AnsiSqlRenderer:692` roundHalfEven | `$r.rate->round()` | DC on SQLite (H2 has an arm) |
| `AnsiSqlRenderer:734` listCall | `[1,'a']->size()`, `[]->cast(@String)->size()` | DC on SQLite **and** H2 |
| `AnsiSqlRenderer:901` arrayLit | `toSQLString(|…[1,2]->size()…, H2, [])` | DC |
| `AnsiSqlRenderer:905` structLit | `toSQLString(|…pair(a,b)->first()…, H2, [])` | DC |
| `EngineStyleH2:1081` structGet | `toSQLString(|…pair(a,b)->toString()…, H2, [])` | **UOE** |
| `EngineStyleH2:1522` strftime | `toSQLString(|…$r.ts->toString()…, H2, [])` | **ISE** |
| `H2:305` SUBSEC_MIN | `$r.ts->toString()` on an H2 connection | DC |

### E.3 Throw sites judged genuinely unreachable (internal invariants, 9)

`AnsiSqlRenderer:132` (QUALIFY past its own pass), `:213` (Dual source),
`:368` (`TempTableInSplice` on an executable dialect), `:372` (`PlanParam`),
`:426` (`DeferredTdsString`), `:536` ("infix operator fell through");
`DuckDb:245`/`:278` ("not a list call"/"not a bit op" — private dispatch invariants);
`FoldToListReduce:32`. Each is guarded by a pass that runs unconditionally before the
writer, or by a private dispatcher whose caller already filtered the enum.

---

## A. The complete type-name matrix (task 1), by RUNNING the code

`probes/TypeMatrix.java` — direct calls to `AnsiSqlRenderer.castTypeName` on every
dialect object, for every `SqlType.Scalar` plus representative composites:

```
SqlType               | ANSI             | SQLite           | DuckDb           | H2               | H2Modern         | EngStyleH2       | EngStyDB2        | EngStyComp
BOOLEAN               | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN          | BOOLEAN
INTEGER               | INTEGER          | INTEGER          | INTEGER          | INTEGER          | INTEGER          | INTEGER          | INTEGER          | INTEGER
BIGINT                | BIGINT           | BIGINT           | BIGINT           | BIGINT           | BIGINT           | BIGINT           | BIGINT           | BIGINT
HUGEINT               | HUGEINT          | HUGEINT          | HUGEINT          | NUMERIC(38)      | NUMERIC(38)      | HUGEINT          | HUGEINT          | HUGEINT
DOUBLE                | DOUBLE PRECISION | DOUBLE PRECISION | DOUBLE           | DOUBLE PRECISION | DOUBLE PRECISION | DOUBLE PRECISION | DOUBLE PRECISION | DOUBLE PRECISION
VARCHAR               | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
DATE                  | DATE             | DATE             | DATE             | DATE             | DATE             | DATE             | DATE             | DATE
TIMESTAMP             | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP        | TIMESTAMP
TIMESTAMPTZ           | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ      | TIMESTAMPTZ
JSON                  | !!ISE            | !!ISE            | JSON             | JSON             | JSON             | !!ISE            | !!ISE            | !!ISE
LITERAL               | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
TEMPORAL_TEXT         | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR          | VARCHAR
Decimal(38,18)        | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)  | DECIMAL(38, 18)
Decimal(10,2)         | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)   | DECIMAL(10, 2)
Array(VARCHAR)        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]        | VARCHAR[]
Array(Decimal(38,18)) | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[] | DECIMAL(38,18)[]
Map(VARCHAR,BIGINT)   | MAP(VARCHAR, BIGINT) (identical on all eight)
Struct(f INT, g VARCHAR)| !!DC           | !!DC             | STRUCT(f INTEGER, g VARCHAR) | !!DC | !!DC   | !!DC             | !!DC             | !!DC
```

Only **two** cells differ from the ANSI baseline across the whole matrix
(`HUGEINT` and `DOUBLE`), plus JSON/STRUCT capability gates. `EngineStyleH2`,
`EngineStyleDB2` and `EngineStyleComposite` all use `TypeNames.ANSI` verbatim
(EngineStyleH2.java:222 `super(Lexicon.ENGINE_STYLE, TypeNames.ANSI, Spellings.DUCKDB)`),
so a DB2 golden would spell `HUGEINT`, `TIMESTAMPTZ` and `VARCHAR[]` — none of which DB2
has. (Not executable here; reported as analysis only.)

**Cross-check against real DB semantics: see the F3 execution matrix above.** Summary
of every cell the database rejects or reinterprets:
- **SQLite** rejects: `DECIMAL(38,18)[]`, `MAP(...)`, `STRUCT(...)`.
  Silently reinterprets: `BOOLEAN`→INTEGER, `HUGEINT`→INTEGER (saturating),
  `DATE`/`TIMESTAMP`/`TIMESTAMPTZ`→INTEGER (leading-digit parse!), `JSON`→INTEGER 0,
  `DECIMAL(p,s)`→REAL (no rounding, no scale), `VARCHAR[]`→TEXT.
  Only `INTEGER`, `BIGINT`, `DOUBLE [PRECISION]`, `VARCHAR` behave.
- **H2 2.1.214** rejects: `HUGEINT` (remapped to `NUMERIC(38)` — correct),
  `TIMESTAMPTZ` (**not** remapped — bug), `VARCHAR[]`, `DECIMAL(38,18)[]`,
  `MAP(...)`, `STRUCT(...)`. Note H2 **truncates** `VARCHAR(5)` where DuckDB/SQLite do
  not — but the renderer never emits a sized VARCHAR, so that is latent only.
- **DuckDB** accepts every cell.

---

## B. `SqlExpr.Cast` — the Pure types that can reach it (task 2)

`SqlExpr.Cast` (SqlExpr.java:905) takes an `SqlType`, **not** a String; the
"pureTypeName" carve-out no longer exists in the IR. The Pure→SQL type decision happens
one layer up in `lowering/PureSql.type` (PureSql.java:95-171) and
`lowering/CastPolicy.lower` (CastPolicy.java:49-150).

Every Pure type name that can reach `new SqlExpr.Cast(value, PureSql.type(target))`
(gated by `CastPolicy.isSqlPrimitive`, CastPolicy.java:227-232) and its rendered cast
(measured by running `probes/Multi.java` on all three backends):

| Pure target | reaches Cast? | rendered (ANSI/SQLite) | DuckDb | H2 / H2Modern |
|---|---|---|---|---|
| `String` | yes | `VARCHAR` | `VARCHAR` | `VARCHAR` |
| `Integer` | yes | `BIGINT` | `BIGINT` | `BIGINT` |
| `Float` | yes | `DOUBLE PRECISION` | `DOUBLE` | `DOUBLE PRECISION` |
| `Number` | **no** — `isWidening` ⇒ identity | (no cast) | (no cast) | (no cast) |
| `Decimal` | yes | `DECIMAL(38, 18)`; `DECIMAL(38, 0)` when the source is `Integer` (CastPolicy.java:144-148) | same | same |
| `Decimal(p,s)` (`PrecisionDecimal`) | yes | `DECIMAL(p, s)` | same | same |
| `Boolean` | yes | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` |
| `StrictDate` | yes | `DATE` | `DATE` | `DATE` |
| `DateTime` | yes | `TIMESTAMP` | `TIMESTAMP` | `TIMESTAMP` |
| `Date` | yes (non-widening sources) | `TIMESTAMP` | `TIMESTAMP` | `TIMESTAMP` |
| `LatestDate` | **no** — excluded by `isSqlPrimitive` ⇒ identity | — | — | — |
| `Byte` | **no** — `PureSql.primitiveCarrier` returns null | — | — | — |
| `StrictTime` | **no** — same | — | — | — |
| enum types | via `PureSql.type` = `VARCHAR` | `VARCHAR` | `VARCHAR` | `VARCHAR` |
| `Any` / `Variant` | `JSON` | **ISE** (no row) | `JSON` | `JSON` |
| `Nil` | `VARCHAR` | `VARCHAR` | `VARCHAR` | `VARCHAR` |
| `List<T>` | `Array(T)` | `T[]` | `T[]` | `T[]` (H2 rejects) |
| `Pair<U,V>` | `Struct` | **DC** | `STRUCT(...)` | DC / OK |
| `Map<U,V>` | `Map` | `MAP(K,V)` | `MAP(K,V)` | `MAP(K,V)` (H2 rejects) |
| `Relation<T>` / row | throws `NotImplementedException` (PureSql.java:165) | — | — | — |
| `Function<…>` | throws ISE (PureSql.java:158) | — | — | — |

**Pure type names that NO dialect maps** — the answer to "what happens":
`Byte` and `StrictTime`. **They throw, and they throw *before* any dialect is
consulted** — the Pure name is never emitted raw into SQL. The throw is a bare
`java.lang.IllegalStateException` from `PureSql.java:99-104`, i.e. an ICE class rather
than a user-facing compile error:

```
QUERY: model::Rec2.all()->project(~[x:r|$r.name->cast(@StrictTime)])
---- DuckDB ---- [PLAN-ERROR] IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary
---- SQLite ---- [PLAN-ERROR] IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary
---- H2 -------- [PLAN-ERROR] IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary

QUERY: model::Rec2.all()->project(~[x:r|$r.name->cast(@Byte)])
---- (all three) [PLAN-ERROR] IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
```

(Interesting asymmetry: `CastPolicy` would have *elided* those casts as no-ops —
`isSqlPrimitive` excludes BYTE/STRICT_TIME/LATEST_DATE, CastPolicy.java:227-232 — so
the value would have kept its source kind under a `StrictTime` label. It is the
projection's **output column typing** (`Lowerer.sqlTypeOf` → `PureSql.type`) that
raises, not the cast itself. `cast(@LatestDate)` is the case where the cast is elided
*and* the output type maps (to TIMESTAMP), so it silently succeeds as an assertion.)

Cross-kind casts that CAN never succeed are compiled into a runtime raise, which each
dialect spells differently — and SQLite has no spelling at all:

```
QUERY: model::Rec2.all()->project(~[x:r|$r.qty->cast(@Boolean)])
---- DuckDB ---- [SQL] SELECT error('Cast exception: Integer cannot be cast to Boolean') AS x ...
                 [EXEC-ERROR] Invalid Input Error: Cast exception: Integer cannot be cast to Boolean
---- SQLite ---- [SQL] SELECT error('Cast exception: Integer cannot be cast to Boolean') AS x ...
                 [EXEC-ERROR] no such function: error       <-- the raise itself is unspellable
---- H2 -------- [SQL] SELECT SIGNAL('45000', 'Cast exception: Integer cannot be cast to Boolean') AS x ...
                 [EXEC-ERROR] Cast exception: Integer cannot be cast to Boolean
```

---

## C. Temporal rendering per dialect (task 5) — executed

| Pure | DuckDB SQL / value | SQLite SQL / value | H2 SQL / value |
|---|---|---|---|
| `%2020-01-01` literal | `DATE '2020-01-01'` ✅ | `DATE '2020-01-01'` → **syntax error** | `DATE '2020-01-01'` ✅ |
| `$r.d->year()` | `date_part('year', D)` → `Long(2020)` | `date_part(...)` → **no such function** | `extract(YEAR FROM D)` → `Integer(2020)` |
| `$r.d->adjust(10, DAYS)` | `CAST(D + to_days(10) AS DATE)` → `2020-01-12` | `to_days` → **no such function** | `CAST(dateadd(DAY, 10, D) AS DATE)` → `2020-01-12` |
| `$r.d->dateDiff(%…, DAYS)` | `date_diff('day', …)` → `-1, -531, 7306` | **syntax error** (literal) | `DATEDIFF(DAY, …)` → `-1, -531, 7306` |
| `$r.ts->toString()` | `strftime(TS, '%Y-…%g+0000')` → `2020-01-02T03:04:05.000+0000` | same SQL → **`null` (F4)** | `DialectCapability: minimal-fraction date format …` (H2.java:305) |
| `parseDate('…+0200')` | `timezone('UTC', CAST(… AS TIMESTAMPTZ))` → `2015-04-15T15:00:00+0000` | `no such function: timezone` | `Function "TIMEZONE" not found` |
| `$r.d`, `$r.ts` plain read | `StrictDate`/`DateWithSecond` ✅ | `StrictDate`/`DateWithSecond` ✅ (string columns parse at `Executor:673`) | ✅ |

Divergences: **DuckDB↔H2 agree on every value they can both compute**
(year, adjust, dateDiff, comparison filters). SQLite can compute *none* of them except
plain column reads; the only silent one is `toString()` → `null`.

`DateFmt` coverage (`AnsiSqlRenderer.formatText:945+` vs `H2.formatText:289-318` vs
`EngineStyleH2:1605` / `EngineStyleDB2:270`): H2 covers 13 of the 14 `DateFmt.Part`
constants; `SUBSEC_MIN` is a `DialectCapability` (H2.java:305-307), and it is the one
`toString()` on a `DateTime` needs — so the single missing part makes the most common
temporal-to-string call unsupported on H2.

---

## D. String semantics per dialect (task 6) — executed

| aspect | DuckDB | SQLite | H2 | verdict |
|---|---|---|---|---|
| `substring(0,3)` on `'abc'` | `abc` (clamped by `SubstringClamp`) | **`ab`** | `abc` (engine clamps) | F8 — SQLite wrong |
| `substr(x,-2,3)`, `(1,3)`, `(2,100)` | agree | agree | agree | sound |
| `length('😀ab')` | 3 | 3 | **4** | F7 — H2 counts UTF-16 units |
| `length(<non-string>)` | wrapped `CAST … AS VARCHAR` | bare | bare | F7 (secondary) |
| `toUpper`/`toLower` non-ASCII | full Unicode | **ASCII-only** | full Unicode | F9 |
| `a + b` concat | `concat(a,b)` | `concat(a,b)` | `concat(a,b)` | sound — `concat` treats NULL as `''` on all three; the renderer never emits `\|\|` (which *does* diverge: `'a' \|\| NULL` = NULL everywhere but is unused) |
| `==` comparison | binary, case-sensitive | binary, case-sensitive | binary, case-sensitive | sound (`'a'='A'` false on all three) |
| ordering `'a' < 'B'` | false | false | false | sound |
| LIKE escaping | n/a — the renderer emits **no `LIKE`**; `MATCHES` → `regexp_matches`/`regexp_like`; SQLite has neither → loud | | | latent: SQLite's `LIKE` is case-**in**sensitive by default (`'abc' LIKE 'ABC'` → 1) — a hazard the moment a `LIKE` spelling is added |
| `indexOf`/`contains` | `strpos` | **no such function** | `LOCATE` (args swapped, correct) | loud |
| Boolean→text | `CAST(b AS VARCHAR)` → `true` | → **`1`** | `CASE …'true'…` → `true` | F1/F15 |
| Decimal→text | `1.500000` | **`1.5`** | `1.500000` | F2 knock-on |

---

## F. Additional pasted evidence

### F1 (see finding F15) H2 boolean-shape divergence — `probes/BoolShape.java`
```
DuckDb : SELECT CAST(t.B AS VARCHAR) AS v FROM T AS t
H2     : SELECT CAST(t.B AS VARCHAR) AS v FROM T AS t
DuckDB value: true
H2 value    : TRUE
```

### F2 EngineStyle dialects are user-reachable — `probes/Engine.java` (H2 session)
```
toSQLString(|Rec.all()->project(~[x:r|$r.name]), M, DatabaseType.H2, [])
   -> select "root".NAME as "x" from T_REC as "root"
toSQLString(|... ~[x:r|$r.name->substring(0,3)] ..., DatabaseType.H2, [])
   -> select substring("root".NAME, 0, 3) as "x" from T_REC as "root"
toSQLString(|... , DatabaseType.DB2, [])
   -> select substr("root".NAME, 0, 3) as "x" from T_REC as "root"
toSQLString(|... , DatabaseType.Composite, [])
   -> select substring("root".NAME, 0, 3) as "x" from T_REC as "root"
toSQLString(|... ~[x:r|$r.name->length()] ..., DatabaseType.Composite, [])
   -> select char_length("root".NAME) as "x" from T_REC as "root"
```

### F3 `Compiler.dialectOf` behaviour — `probes/Asym.java` (full output)
```
== Compiler.plan(H2-runtime)  [dialectOf(ctx,runtime)] ==
   SELECT date_part('year', t0.D) AS x, starts_with(t0.NAME, 'a') AS y FROM T_REC AS t0
== execute path [dialectOf(ctx,runtime,conn)] -> H2
   SELECT extract(YEAR FROM t0.D) AS x, (LEFT(t0.NAME, CHAR_LENGTH('a')) = 'a') AS y FROM T_REC AS t0
== SQLite conn + runtime=null            -> DuckDb
== SQLite conn + runtime='test::NOPE'    -> DuckDb
== SQLite conn + runtime='test::R'       -> AnsiSqlRenderer
```

### F4 Driver versions actually used
```
== DuckDB DuckDB v1.5.0
== SQLite SQLite 3.47.1
== H2 H2 2.1.214 (2022-06-13)
```

---

## VERIFIED SOUND

Checked and found correct (with the evidence that established it):

1. **`castTypeName` structural composition** (AnsiSqlRenderer.java:914-937) — Decimal,
   Array, Map, Struct recursion is identical on all 8 dialects and matches the type
   tree; verified exhaustively by `probes/TypeMatrix.java` over 18 type shapes × 8
   dialects (144 cells).
2. **DuckDB is complete against the type matrix** — every one of the 19 distinct
   rendered type names executes correctly on DuckDB 1.5.0 with the expected carrier
   (`probes/CastExec.java`).
3. **`TypeNames.H2`'s HUGEINT remap** (`TypeNames.java:42` → `NUMERIC(38)`) is correct:
   H2 accepts it and returns `BigDecimal` for a 38-digit value.
4. **H2's function-shape arms are all correct where they exist** — `extract(UNIT FROM x)`,
   `dateadd(UNIT,n,d)`, `DATEDIFF(UNIT,a,b)` (sign parity verified: `-1/-531/7306`
   byte-identical to DuckDB's `date_diff`), `LOCATE(needle, hay)` (argument swap
   correct: `2/0/0` identical to `strpos`), `LEFT/CHAR_LENGTH` for `startsWith`
   (`true/false/false` identical), and banker's `ROUND` (`2/0/-1` identical to
   `ROUND_EVEN`). All executed on H2 2.1.214.
5. **`H2.normalize`** (H2.java:474-489) — the two arms it declares (JSON `byte[]`→String,
   DOUBLE `BigDecimal`→`Double`) both fire correctly; `Float[1]` columns come back as
   `Double` on H2 in every probe.
6. **`SubstringClamp`** is correct *for the dialect it is registered on*: `substr(x,0,3)`
   → `substr(x,1,3)` and non-literal starts → `greatest(st, 1)` (SubstringClamp.java:29-32).
   The `IntLit`-vs-expression split avoids a pointless `greatest` on constants.
7. **`QualifyToSubselect`** — the pass/writer invariant holds: `supportsQualify()` is
   true for DuckDb and H2, false for the ANSI base and the engine-text dialects, and
   `AnsiSqlRenderer.select:130-135` throws if a QUALIFY ever reaches a non-supporting
   writer. The pass registration in `AnsiSqlRenderer.passes():100-107` is conditional on
   exactly that predicate.
8. **`opSpelling` / precedence** (AnsiSqlRenderer.java:64-66 + the `INFIX` table at
   :68-79) — the "declared weakest-binding, walk decides parens" discipline is
   structurally sound; no arm hand-parenthesizes.
9. **`stringLit` NUL handling** (AnsiSqlRenderer.java:866-885) — the `chr(0)` splice with
   `split(..., -1)` correctly round-trips trailing empty segments.
10. **Cross-kind cast raise** (`CastPolicy.crossKindRaise`) works correctly on
    *typed* columns: `Integer->@Boolean` produces `error(...)` / `SIGNAL(...)` on DuckDB
    and H2 with the exact reference message. (It is bypassed on `Any` sources — F5.)
11. **`Executor.unwrap` integral repair** (Executor.java:651-660) — a scale-0
    `BigDecimal` in a `BIGINT`/`INTEGER`/`HUGEINT` slot correctly becomes `Long`/
    `BigInteger`; verified via the `HUGEINT * 10^20` raw-grid probe returning
    `BigInteger`.
12. **Temporal decode** — `StrictDate`/`DateWithSecond` (`PureDateLiteral`) is produced
    identically on all three backends for `DATE`/`TIMESTAMP` columns, including SQLite,
    where the string-cell arm (`Executor.java:675-676`) parses correctly.
13. **`concat` NULL semantics agree** on all three backends (`concat('a',NULL)` = `'a'`),
    and the renderer never emits `||` (which would diverge).
14. **String comparison and ordering** are byte/collation-identical on all three
    (`'a'='A'` false, `'a'<'B'` false).
15. **`Lexicon` quoting** — reserved-word sets are per-backend and the `H2` execution
    lexicon correctly adds `right` over `H2_ENGINE_TEXT` (Lexicon.java:88-92); the
    `PLAIN` pattern + reserved-word check in `ident()` is the only quoting path.
16. **`EngineStyleComposite`** does exactly what it claims: `char_length` (vs DB2's
    `CHARACTER_LENGTH(...,CODEUNITS32)`) and full-keyword `substring` (vs DB2's
    `substr`) — both verified through `toSQLString`.
17. **`H2.sortKey` NULLS-LAST pin** (H2.java:274-281) and the aggregate-internal twin
    (H2.java:362-378) are consistent with each other — the same default is applied in
    both places.
18. **17 of 42 switches in `dialect/` carry no default arm**, so javac genuinely
    enforces totality there; 21 of the 25 defaults are throwing, delegating, or guarded.

---

## NOT COVERED

1. **`H2Modern`** could not be *executed*: only h2-2.1.214 is on the classpath, and
   `Compiler.dialectOf` selects `H2Modern` only for `!ver.startsWith("2.1"|"2.2")`. Its
   type matrix and capability row are covered by `probes/TypeMatrix.java` /
   `probes/Hooks.java` (renderer-level), but its `(j)."field"` / 1-based `[i]` /
   `FORMAT JSON` claims (H2Modern.java:26-115) are unverified against a real 2.3+
   engine.
2. **`EngineStyleDB2` / `EngineStyleComposite` against a real DB2.** No DB2 driver
   exists here. Their rendered SQL was captured via `toSQLString` but never executed, so
   the `TypeNames.ANSI` mismatches for DB2 (`HUGEINT`, `TIMESTAMPTZ`, `VARCHAR[]`,
   `MAP(...)`) are reported as analysis, not measurement.
3. **`CarrierStrategies` (1,264 lines)** was audited only where it bears on the type
   system: `Caps`, the static-pivot emulation (`litText`, F15), the FULL-OUTER
   emulation, and the AS-OF emulation entry conditions. Its explode/collect rewrite
   family (R3a/R5b, ~600 lines) was **not** exhaustively verified — I could not
   construct Pure queries reaching most arms within the session budget.
4. **`EngineStyleH2` (1,664 lines)** — I verified its walls, its `default ->` arms, and
   a handful of golden outputs. The byte-exactness of its alias/format contract against
   the real engine is untestable here and was not attempted.
5. **`UnqualifyPivotArgs` and `FoldToListReduce`** were read but not exercised by a
   repro; both are DuckDB-only passes and I found no type-level defect by reading.
6. **`litText` pivot-column-name corruption (F15 item 1)** is proven by code reading
   (`CarrierStrategies.java:157` + `:301-307`) but I did **not** land an end-to-end Pure
   pivot repro — the `groupBy`/`pivot` surface rejected every spelling I tried within
   budget. Severity is therefore stated as SILENT-FALLBACK on citation, not on measured
   output.
7. **`Lexicon` reserved-word completeness** — I did not diff the three word lists against
   the engines' actual grammars.
8. **Streaming execution path** (`Compiler.planStreaming` / `executeStreaming`) was not
   exercised; all probes used the materialized `execute`.
9. **`H2` ARRAY-cast reachability** — the renderer emits `VARCHAR[]` for H2 and H2
   rejects it (proven at the SQL level), but I could not build a Pure query that emits
   an `Array` cast on an H2 session (the collection family walls earlier, at
   `LIST_FILTER`). Reachability unestablished.
