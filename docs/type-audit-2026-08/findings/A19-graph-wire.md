# A19 — OBJECT/GRAPH path and WIRE format

Scope: `graphFetch`/`serialize` typing, the GRAPH JSON envelope, the product
CSV/JSON wire, `MetamodelWalk`, polymorphism, `ResultShape` boundaries.

All repros below were RUN. Harness: `/home/user/probe/jrun.sh`-style single-file
probes (`/tmp/a19/*.java`), DuckDB v1.5.0 in-memory, `Compiler.execute` /
`Compiler.executeWire` / `Compiler.executeStreaming`.

Throughout, a literal NUL byte in pasted output is written as `<U+0000>` and a
literal astral character as `<U+D83D><U+DE00>` (my probes print non-ASCII as
`<U+XXXX>` so the evidence stays byte-exact).

## Fixtures used (all in /tmp/a19)

`kitchen.pure` — one class with a property of every reachable Pure primitive
(String, Integer, Float, Decimal(38,10), Boolean, DateTime, StrictDate, abstract
Date, an enum) plus a nested class (`Inner`), a `[*]` object collection
(`inners`, `tags`) and a `[*]` primitive collection (`tagNames`), mapped
relationally to DuckDB. `kitchen.sql` loads values at the edges:

```
INSERT INTO T_K VALUES (1, 'plain', 42, 1.5, 3.25, true, TIMESTAMP '2024-03-15 13:45:56.123456', DATE '2024-03-15', TIMESTAMP '2024-03-15 13:45:56.123456', 'RED');
INSERT INTO T_K VALUES (2, '', 9223372036854775807, 1.7976931348623157e308, 9999999999999999999999999999.9999999999, true, TIMESTAMP '9999-12-31 23:59:59.999999', DATE '9999-12-31', TIMESTAMP '9999-12-31 23:59:59.999999', 'GREEN');
INSERT INTO T_K VALUES (3, 'a'||chr(34)||'b'||chr(92)||'c'||chr(10)||'d'||chr(9)||'e'||chr(0)||'f'||chr(128512)||'g'||chr(8232)||'h', -9223372036854775808, -1.7976931348623157e308, -9999999999999999999999999999.9999999999, false, TIMESTAMP '1900-01-01 00:00:00', DATE '1900-01-01', TIMESTAMP '1900-01-01 00:00:00', 'BLUE');
INSERT INTO T_K VALUES (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO T_K VALUES (5, 'sci', 0, 0.30000000000000004, 0.0000000001, false, TIMESTAMP '1970-01-01 00:00:00.000001', DATE '1970-01-01', TIMESTAMP '1970-01-01 00:00:00', 'RED');
```

Row 3's string is: `a`, `"`, `b`, `\`, `c`, LF, `d`, TAB, `e`, NUL, `f`,
U+1F600 (non-BMP), `g`, U+2028, `h`.

---

# FINDINGS

### [UNSOUND] GRAPH JSON destroys every Decimal — `to_json(list(json_object(...)))` re-encodes through DOUBLE

**Evidence.** `core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:485-496`:

```java
protected String jsonArrayAgg(SqlExpr.JsonArrayAgg j) {
    return j.orderKeys().isEmpty()
            ? "coalesce(json_group_array(" + expr(j.value(), 0) + "), '[]')"
            : "coalesce(to_json(list(" + expr(j.value(), 0) + " ORDER BY " + ... + ")), '[]')";
}
```

The doc comment above it (`AnsiSqlRenderer.java:479-483`) claims "`to_json` over
the JSON list yields the same array value". It does not: DuckDB re-parses each
nested JSON object and coerces its numbers to DOUBLE.

**Repro (isolated, DuckDB v1.5.0 direct — `/tmp/a19/Duck2.java`), actual output:**

```
-- SELECT CAST(list(json_object('d', d)) AS VARCHAR) FROM D
   [{"d":12345678901234567890.1234567891}, {"d":3.2500000000}, {"d":9007199254740993.0000000000}]
-- SELECT CAST(to_json(list(json_object('d', d))) AS VARCHAR) FROM D
   [{"d":12345678901234567000.0},{"d":3.25},{"d":9007199254740992.0}]
-- SELECT CAST(json_group_array(json_object('d', d)) AS VARCHAR) FROM D
   [{"d":12345678901234567000.0},{"d":3.25},{"d":9007199254740992.0}]
-- SELECT CAST('[' || string_agg(CAST(json_object('d', d) AS VARCHAR), ',') || ']' AS VARCHAR) FROM D
   [{"d":12345678901234567890.1234567891},{"d":3.2500000000},{"d":9007199254740993.0000000000}]
```

Both idioms the graph envelope emits lose it; the `string_agg` idiom (used by the
TABULAR JSON wire) does not.

**Repro through the product path.** `dec` is declared `Decimal` (store
`DECIMAL(38,10)`). Query
`ks::Kitchen.all()->graphFetch(#{ks::Kitchen{f,dec}}#)->serialize(#{ks::Kitchen{f,dec}}#)`
over `/tmp/a19/dec.sql`.

Actual `ExecutionResult.Graph.json()`:
```
[{"f":2.0,"dec":1.1},{"f":100.0,"dec":0.1},{"f":1e100,"dec":12345678901234567000.0},{"f":0.0,"dec":1e-10},{"f":1e-7,"dec":9007199254740992.0},{"f":3.0,"dec":123.45}]
```
The same rows through the TABULAR path (`->project(~[f:x|$x.f, dec:x|$x.dec])`):
```
COL f : FLOAT
COL dec : DECIMAL
ROW Double(2.0) | BigDecimal(1.1000000000)
ROW Double(100.0) | BigDecimal(0.1000000000)
ROW Double(1.0E100) | BigDecimal(12345678901234567890.1234567891)
ROW Double(0.0) | BigDecimal(1E-10)
ROW Double(1.0E-7) | BigDecimal(9007199254740993.0000000000)
ROW Double(3.0) | BigDecimal(123.4500000000)
```

`12345678901234567890.1234567891` becomes `12345678901234567000.0` (wrong by
~890.12) and `9007199254740993` becomes `9007199254740992` (off by one, 2^53+1).
On the full kitchen fixture the 38-digit value
`9999999999999999999999999999.9999999999` arrives as `1e28`.

**Why it matters.** Top-prize class: the compiler assigns `Decimal` and the graph
wire carries a value that is NOT that Decimal. Silent, no warning. It affects
every `graphFetch->serialize` root and every nested graph child (children use the
same `to_json(list(...))` idiom — e.g.
`(SELECT coalesce(to_json(list(json_object('label', t1.LABEL, 'n', t1.N) ORDER BY ...)), '[]') ...)`).
Scale is also lost even where the value survives (`1.1000000000` → `1.1`).

---

### [UNSOUND / INCONSISTENCY] The SAME query returns two different JSON documents through `Compiler.execute` vs `Compiler.executeStreaming`

**Repro** (`/tmp/a19/Stream.java`, same model, same rows, same connection object):

```
STREAM>>>[{"f":2.0,"dec":1.1000000000},{"f":100.0,"dec":0.1000000000},{"f":1e100,"dec":12345678901234567890.1234567891},{"f":0.0,"dec":0.0000000001},{"f":1e-7,"dec":9007199254740993.0000000000},{"f":3.0,"dec":123.4500000000}]<<<
SNAP  >>>[{"f":2.0,"dec":1.1},{"f":100.0,"dec":0.1},{"f":1e100,"dec":12345678901234567000.0},{"f":0.0,"dec":1e-10},{"f":1e-7,"dec":9007199254740992.0},{"f":3.0,"dec":123.45}]<<<
EQUAL=false
```

**Evidence.** `Compiler.executeStreaming` (`Compiler.java:378-398`) lowers with
`withStreamingGraphRoot()` → `lowering/StreamingGraphRoot.java:27-41` emits ONE
`json_object` per JDBC row and `exec/Executor.streamGraph:227-252` writes bytes
verbatim, so decimals survive. `Compiler.execute` goes through
`SqlExpr.JsonArrayAgg` and loses them. Streaming is *correct*; the default path is
wrong. Two owners of one contract, two answers.

---

### [UNSOUND / INVALID JSON] Float ±Infinity / NaN emit bare `Infinity` / `NaN` — invalid JSON that the platform's OWN reader rejects

**Repro.** `/tmp/a19/nan.sql` inserts `'Infinity'::DOUBLE`, `'-Infinity'::DOUBLE`,
`'NaN'::DOUBLE` into the `F DOUBLE` column (Pure type `Float`), then
`ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s,f}}#)->serialize(#{ks::Kitchen{s,f}}#)`:

```
SHAPE=Graph
RAW>>>[{"s":"inf","f":Infinity},{"s":"-inf","f":-Infinity},{"s":"nan","f":NaN}]<<<
LEN=73
REPARSE_FAIL java.lang.NumberFormatException: For input string: ""
```

`REPARSE_FAIL` is `com.legend.sql.Json.parse` — THE platform's own JSON reader
(`sql/Json.java:38-47`) — failing on the platform's own wire output.
`Json.num()` (`sql/Json.java:171-186`) scans only `+-0123456789.eE`, consumes zero
characters, and `Long.parseLong("")` throws.

The tabular JSON wire (`Compiler.executeWire(..., Format.JSON)`) produces the
identical invalid text. The CSV wire produces a third spelling:

```
== CSV cols=[s, f]
s,f<CR><LF>
inf,inf<CR><LF>
-inf,-inf<CR><LF>
nan,nan<CR><LF>
```

**Why it matters.** RFC 8259 has no `Infinity`/`NaN` literals. Any conformant
consumer fails, and so does this repo's own reader — the wire is not
round-trippable at all for these Float values.

---

### [UNSOUND] `includeType` emits the STATIC class, not the runtime class — the wire lies about polymorphic instances

**Evidence.** `lowering/Lowerer.java:848-851`:

```java
if (g.typeKeyName() != null && g.classFqn() != null && !g.bareValue()) {
    baseKv.add(new SqlExpr.StringLit(g.typeKeyName()));
    baseKv.add(new SqlExpr.StringLit(SnapshotEnvelope.typeName(g.classFqn(), g.fqTypePath())));
```

`g.classFqn()` is the fetched (static) class. Only the `subTypePatches` branch
(`Lowerer.java:872-877`) ever uses the concrete `p.subTypeFqn()`.

**Repro** (`/tmp/a19/poly.pure`: `Dog extends Animal`, `Cat extends Animal`,
union-mapped; DOG holds Rex+Fido, CAT holds Tom):

```
Q: poly::Animal.all()->graphFetch(#{poly::Animal{name}}#)->serialize(#{poly::Animal{name}}#)
  [JSON] [{"name":"Rex"},{"name":"Fido"},{"name":"Tom"}]

Q: ...->serialize(#{poly::Animal{name}}#, ^meta::pure::graphFetch::execution::AlloySerializationConfig(typeKeyName='@type', includeType=true, fullyQualifiedTypePath=true))
  [SQL] SELECT CAST(coalesce(to_json(list(json_object('@type', 'poly::Animal', 'name', t2.name) ORDER BY t2.u_serial_ord__ DESC NULLS LAST, t2."stc_poly__Dog___$member" DESC NULLS LAST, t2."stc_poly__Cat___$member" DESC NULLS LAST)), '[]') AS VARCHAR) AS result FROM ( SELECT t0.NAME AS name, TRUE AS "stc_poly__Dog___$member", ... FROM DOG AS t0 UNION ALL SELECT t1.NAME AS name, ..., TRUE AS "stc_poly__Cat___$member", ... FROM CAT AS t1 ) AS t2
  [JSON] [{"@type":"poly::Animal","name":"Rex"},{"@type":"poly::Animal","name":"Fido"},{"@type":"poly::Animal","name":"Tom"}]
```

Rex and Fido are `poly::Dog`; Tom is `poly::Cat`. The `@type` discriminator —
whose whole purpose is to let a reader recover the concrete class — claims
`poly::Animal` for all three. The plan literally projects the discriminators
(`TRUE AS "stc_poly__Dog___$member"`) and ignores them.

Only when the user hand-writes a `->subType(@X)` branch per subtype is it correct:
```
Q: ...#{poly::Animal{name, ->subType(@poly::Dog){breed}, ->subType(@poly::Cat){lives}}}# with includeType
  [JSON] [{"@type":"poly::Dog","name":"Rex","breed":"Lab"},{"@type":"poly::Dog","name":"Fido","breed":"Pug"},{"@type":"poly::Cat","name":"Tom","lives":9}]
```

**Answer to "does the wire carry the actual class / does the reader recover it":**
NO by default (no class information at all), and WRONG when `includeType` is on.

---

### [UNSOUND / SILENT FALLBACK] `graphFetch`/`serialize` never validate the tree's ROOT CLASS — a non-existent class compiles and runs

**Evidence.** `compiler/spec/GraphFetchChecker.java:97`:

```java
return new Checked(source, validate(t, ct.fqn(), tree, fn, env));
```

`ct.fqn()` is the SOURCE class. The `#{Class{...}}#` literal's own `className`
(`protocol/spec/GraphFetchLiteral.className()`) is never resolved, never compared
to the source, never used at all.

**Repro** (poly model, `poly::Dog.all()` — Dog and Cat both declare `name`):

```
Q: poly::Dog.all()->graphFetch(#{poly::Dog{name}}#)->serialize(#{poly::Cat{name}}#)
  [JSON] [{"name":"Rex"},{"name":"Fido"}]
Q: poly::Dog.all()->graphFetch(#{poly::Cat{name}}#)->serialize(#{poly::Dog{name}}#)
  [JSON] [{"name":"Rex"},{"name":"Fido"}]
Q: poly::Dog.all()->graphFetch(#{ks::Nonexistent{name}}#)->serialize(#{ks::Nonexistent{name}}#)
  [G] TypedSerialize :: String[1]
  [SHAPE] GRAPH root=poly::Dog[*]
  [JSON] [{"name":"Rex"},{"name":"Fido"}]
```

A tree naming a class that does not exist in the model compiles, plans and
executes.

Related, same root cause: the `graphFetch` tree is **entirely ignored** — only the
`serialize` tree drives emission, so `serialize` can emit properties that were
never fetched:
```
Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s}}#)->serialize(#{ks::Kitchen{s,i}}#)
  [SQL] ... json_object('s', t0.S, 'i', t0.I) ...
  [JSON] [{"s":"plain","i":42},{"s":"","i":9223372036854775807},...]
```

---

### [UNSOUND] A required (`[1]`) enum whose source value is outside the EnumerationMapping silently becomes `null`

**Evidence.** The enum leaf lowers to a chained CASE with a terminal `ELSE NULL`.

**Repro** (`/tmp/a19/kitchen_req.pure` declares `col: Colour[1]`;
`/tmp/a19/enum.sql` row 2 has `COL='PURPLE'`, row 3 has `COL=NULL`):

```
Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s,col}}#)->serialize(#{ks::Kitchen{s,col}}#)
  [SQL] ... 'col', CASE WHEN t0.COL = 'RED' THEN 'RED' ELSE CASE WHEN t0.COL = 'GREEN' THEN 'GREEN' ELSE CASE WHEN t0.COL = 'BLUE' THEN 'BLUE' ELSE NULL END END END ...
  [JSON] [{"s":"a","col":"RED"},{"s":"b","col":null},{"s":"c","col":null}]

Q: ks::Kitchen.all()->project(~[s:x|$x.s, c:x|$x.col])
  [SHAPE] TABULAR root=Relation<(s:String[0..1], c:ks::Colour[1])>[1]
    ROW String(a) | String(RED)
    ROW String(b) | null
    ROW String(c) | null
```

Static type `ks::Colour[1]`; produced value `null`, on both paths. An unmapped
source code is also indistinguishable from a genuine SQL NULL — a silent
coercion where the repo forbids defaulting.

---

### [UNSOUND] `includeEnumType` fabricates the string `"ks::Colour."` for a NULL enum

**Evidence.** `resolver/GraphEmission.java:3351-3374` (`enumPrefixed`) builds
`plus('<enumFqn>.', body)`; DuckDB's `concat` SKIPS NULL arguments (unlike `||`),
so a null enum yields the bare prefix. The code comment at `:3348-3350` admits it:
*"a NULL enum rides concat's null-skip — acceptable until the removeNull sub-slice
owns null keys"*.

**Repro** (`kitchen.sql` row 4 is the all-NULL row):
```
Q: ...serialize(#{ks::Kitchen{s,col}}#, meta::pure::graphFetch::execution::alloyConfig(false, true, false, false))
  [SQL] ... 'col', concat('ks::Colour.', CASE WHEN t0.COL = 'RED' THEN 'RED' ELSE ... ELSE NULL END) ...
  [JSON] [{"s":"plain","col":"ks::Colour.RED"},{"s":"","col":"ks::Colour.GREEN"},{"s":"a\"b\\c\nd\te<U+0000>f<U+D83D><U+DE00>g<U+2028>h","col":"ks::Colour.BLUE"},{"s":null,"col":"ks::Colour."},{"s":"sci","col":"ks::Colour.RED"}]
```
Row 4: `s` is correctly `null`, `col` is the invented string `"ks::Colour."`.
Without `includeEnumType` the same row gives `"col":null`. The wire carries a
non-null value where the value is empty.

---

### [UNSOUND] Graph roots drop multiplicity: `->toOne()` over 0 rows yields `[]`, over 2 rows yields 2 objects, silently

**Evidence.** `resolver/StoreResolver.java:1073-1080`:

```java
/** {@code toOne(instances)}: multiplicity coercion over a class
 * collection — PASS-THROUGH in the pipeline (the engine raises on
 * N&ne;1; here the value compare sees all N and fails loud — a
 * documented, weaker-but-never-silent stand-in). */
static boolean isClassToOne(TypedNativeCall c) { ... }
```

`exec/Executor.java:394-400` is the WHOLE GRAPH arm:
```java
case GRAPH -> new ExecutionResult.Graph(
        rs.next() ? String.valueOf(dialect.normalize(rs.getObject(1), SqlType.Scalar.JSON)) : "[]",
        rootType.type());
```
No row-count / lower-bound check — unlike the SCALAR arm (which raises
"Cannot cast a collection of size 0 to multiplicity …" and "scalar-shaped result
returned more than one row") and the COLLECTION arm (which enforces the declared
lower bound).

**Repro (0 rows):**
```
Q: ks::Kitchen.all()->filter(x|$x.i == 999999)->toOne()
  [G] TypedNativeCall :: ks::Kitchen[1]
  [SHAPE] GRAPH root=ks::Kitchen[*]        <-- multiplicity silently widened at Phase H
  [JSON] []
```
**Repro (2 rows, `/tmp/a19/two.sql`):**
```
Q: ks::Kitchen.all()->toOne()
  [G] TypedNativeCall :: ks::Kitchen[1]
  [SHAPE] GRAPH root=ks::Kitchen[*]
  [JSON] [{"s":"one",...},{"s":"two",...}]
```
Static `[1]`; runtime 0 and 2. Nothing fires. Also note the plan's own
`rootType()` reports `[*]` — the `[1]` bound is gone by Phase H, which is why no
downstream check can ever see it.

---

### [UNSOUND] The executor reports the WRONG class for a navigated graph root

**Repro:**
```
$ probe.sh kitchen.pure <<< 'ks::Kitchen.all()->map(x|$x.inners)'
[G] type=ks::Inner mult=[*]
[G] typeRepr=ClassType[fqn=ks::Inner]
[PLAN] rootType=ks::Kitchen mult=[*]
[EXEC] shape=Graph returnType=ks::Kitchen returnTypeRepr=ClassType[fqn=ks::Kitchen]
[JSON] [{"label":"inner-one","n":7},{"label":"in\"ner\\two","n":8},{"label":"x","n":-1}]
```

Phase G types the root `ks::Inner[*]`; the plan and `ExecutionResult.returnType()`
both claim `ks::Kitchen`, while the payload is `Inner` objects (`label`/`n`, which
`Kitchen` does not have). A consumer that trusts `returnType()` to select a
deserializer decodes into the wrong class.

---

### [UNSOUND / INVALID JSON] Duplicate tree entries produce duplicate JSON keys; the repo's own reader silently keeps the last

**Repro (`s` twice — accepted with no error):**
```
Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s,s}}#)->serialize(#{ks::Kitchen{s,s}}#)
  [SQL] ... json_object('s', t0.S, 's', t0.S) ...
  [JSON] [{"s":"plain","s":"plain"},{"s":"","s":""},...]
```
**Repro (two DIFFERENT types under one alias):**
```
Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{'x': s, 'x': i}}#)->serialize(#{ks::Kitchen{'x': s, 'x': i}}#)
  [SQL] ... json_object('x', t0.S, 'x', t0.I) ...
  [JSON] [{"x":"plain","x":42},{"x":"","x":9223372036854775807},{"x":"a\"b\\c\nd\te<U+0000>f<U+D83D><U+DE00>g<U+2028>h","x":-9223372036854775808},{"x":null,"x":null},{"x":"sci","x":0}]
```
Round-tripping that through the platform reader (`/tmp/a19/Dup.java`):
```
parsed=[{x=42}]
keys=[x] x=42 class=java.lang.Long
```
The `String` leaf is silently discarded and `x` decodes as `Long`, although the
static tree declared the first `x` a `String`. RFC 8259 leaves duplicate names
undefined; consumers disagree; nothing in the compiler rejects it.

---

### [CRASH/ICE + JSON INJECTION] `objectReference` builds JSON by raw string concatenation — a `"` in a PK yields invalid JSON, a `\` crashes the query

**Evidence.** `lowering/SnapshotEnvelope.java:100-116` (`asorWrap`):

```java
// STRING pks json-quote; numerics stay bare ({"pk$_0":"A"})
boolean strPk = ...;
parts.add(new SqlExpr.StringLit(
        (i > 0 ? "," : "") + "\"pk$_" + i + "\":" + (strPk ? "\"" : "")));
parts.add(new SqlExpr.Cast(scalar.apply(k), com.legend.sql.SqlType.Scalar.VARCHAR));
if (strPk) {
    parts.add(new SqlExpr.StringLit("\""));
}
```

The PK value is interpolated between two raw `"` characters with **no JSON
escaping**.

**Repro A (quote in PK).** Model `/tmp/a19/asor.pure` (`k: String[1]` is the PK),
rows `('normal','x')` and `('has"quote','y')`, config
`alloyConfig(false,false,false,false,true)`. Base64-decoding the emitted
`objectReference` (`/tmp/a19` python snippet):

```
DECODED: ...,"timeZone":"GMT","type":"H2"}:0000000021:{"pk$_0":"has"quote"}
DECODED: ...,"timeZone":"GMT","type":"H2"}:0000000018:{"pk$_0":"normal"}
```
`{"pk$_0":"has"quote"}` is not valid JSON — the reference cannot be decoded back
to the primary key it names.

**Repro B (backslash in PK).** Adding row `('back\slash','z')`:
```
[EXEC-ERR] java.sql.SQLException: Conversion Error: Invalid hex escape code
encountered in string -> blob conversion of string
"001:010:...:0000000022:{"pk$_0":"back\slash"}": \sla
```
A plausible primary-key value kills the whole query.

Side observation at the same site: the ASOR prefix embeds a hard-coded
`{"_type":"RelationalDatabaseConnection","authenticationStrategy":{"_type":"h2Default"},"datasourceSpecification":{"_type":"h2Local"},"element":"","postProcessorWithParameter":[],"postProcessors":[],"timeZone":"GMT","type":"H2"}`
constant — an H2 connection descriptor emitted verbatim for a DuckDB runtime
(`resolver/AsorRef` / `GraphEmission.asorPrefix`).

---

### [CRASH/ICE] A `VARBINARY`/`BINARY` column merely *declared* in a Database kills every plan over that table

**Evidence.** `lowering/PureSql.java:92` — `case BYTE, STRICT_TIME -> null;` and
`PureSql.type()` (`:94-105`) throws `IllegalStateException` on the null.

**Repro** (`/tmp/a19/byt.pure`; `BLOBBY` is neither mapped nor referenced by any
query):

```
Database by::DB ( Table TT (ID INTEGER PRIMARY KEY, NAME VARCHAR(50), BLOBBY VARBINARY(10)) )
Mapping by::M ( by::T : Relational { ~mainTable [by::DB] TT name: TT.NAME } )

Q: by::T.all()->graphFetch(#{by::T{name}}#)->serialize(#{by::T{name}}#)
  [PLAN-ERR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
Q: by::T.all()->project(~[n:x|$x.name])
  [PLAN-ERR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
Q: by::T.all()
  [PLAN-ERR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
```

An internal `IllegalStateException` (not a user-facing compile error), on a model
a user can plausibly write, for a column that plays no part in the query.
(Discovered while building the kitchen-sink fixture: I had to delete the column
from the `Database` block entirely, not just from the mapping, to get any plan.)

---

### [CRASH/ICE + INCONSISTENCY] `->at(n)` out of range in the metamodel channel throws a raw `IndexOutOfBoundsException`

**Evidence.** `MetamodelSteps.java:55-62`:
```java
case "meta::pure::functions::collection::at" -> {
    if (recv instanceof java.util.List<?> l
            && c.args().get(1) instanceof ...TypedCInteger ix) {
        return l.get((int) (long) ix.value());
    }
}
```
No bounds check.

**Repro** (`/tmp/a19/mm.pure`, a view with 4 column mappings):
```
Q: mm::DB->schema('default')->toOne()->view('V1').columnMappings->at(4).relationalOperationElement->meta::relational::functions::typeInference::inferRelationalType()->toOne()->meta::relational::metamodel::datatype::dataTypeToSqlText()
  [EXEC-ERR] java.lang.IndexOutOfBoundsException: Index 4 out of bounds for length 4
```
Contrast — the ordinary `at()` lowering emits a clean Pure-worded error:
```
Q: [1,2,3]->at(7)
  [SQL] SELECT CASE WHEN 7 >= len(...) OR 7 < 0 THEN error(concat('The system is trying to get an element at offset ', ...)) ELSE ... END AS value
  [EXEC-ERR] java.sql.SQLException: Invalid Input Error: The system is trying to get an element at offset 7 where the collection is of size 3
Q: ks::Kitchen.all()->map(x|$x.s)->at(99)
  [EXEC-ERR] java.sql.SQLException: Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 4
```
Two implementations of one typing rule; one ICEs.

---

### [INFORMATION LOSS] CSV wire cannot distinguish SQL NULL from the empty string

**Evidence.** `lowering/Render.java:734-738` (`cell`):
```java
return new SqlExpr.Case(List.of(new SqlExpr.Case.When(
        SqlExpr.Call.of(SqlFn.IS_NULL, c),
        new SqlExpr.StringLit(renderTdsNull ? "TDSNull" : ""))), rendered);
```
`csvWire` (`Render.java:184-240`) always passes `renderTdsNull = false`, so a NULL
cell renders as the empty field — exactly what an empty string renders as. The
`TDSNull` spelling exists but is unreachable from the product wire.

**Proof** (`/tmp/a19/csvedge.sql`: row 1 has `S = NULL`, row 2 has `S = ''`;
`executeWire(..., Format.CSV)`, control characters annotated):

```
s,i<CR><LF>
,1<CR><LF>                       <- s IS NULL
,2<CR><LF>                       <- s = '' (empty string)
"a,b",3<CR><LF>
"q""q",4<CR><LF>
"l1<CR><LF>
l2",5<CR><LF>
"""""",6<CR><LF>
tab<U+0009>x,7<CR><LF>
nul<U+0000>x,8<CR><LF>
```
Byte-identical fields for two distinct Pure values, with no out-of-band signal.
The JSON wire over the same rows distinguishes them correctly
(`{"s":null,"i":1},{"s":"","i":2}`), so the loss is specific to CSV and is not
recoverable by the consumer.

(Escaping itself is correct RFC 4180 — see VERIFIED SOUND. TAB and NUL are
emitted RAW: legal per RFC 4180, but a raw NUL byte in a `text/csv` payload is
worth flagging.)

---

### [INFORMATION LOSS] CSV wire truncates DateTime sub-seconds and collapses midnight DateTimes to a bare date

**Repro** (`/tmp/a19/dt.pure` + `dt.sql`, `executeWire(..., Format.CSV)`):
```
n,dOnDate,dOnTs,sd,dtm<CR><LF>
midnight,2024-03-15,2024-03-15,2024-03-15,2024-03-15 00:00:00<CR><LF>
timed,2024-03-15,2024-03-15 13:45:56,2024-03-15,2024-03-15 13:45:56<CR><LF>
nulls,,,,<CR><LF>
bc,0001-01-01,0001-01-01,0001-01-01,0001-01-01 00:00:00<CR><LF>
```
`dtm` is `DateTime[0..1]` holding `2024-03-15 13:45:56.123456`; the CSV cell is
`2024-03-15 13:45:56` — `.123456` is gone (`Render.java:727-731` formats with
`DateFmt.CSV_DATETIME`, which carries no sub-second part).

`dOnTs` is `Date[0..1]` over a TIMESTAMP column: the same static type renders
`2024-03-15` for a midnight value and `2024-03-15 13:45:56` for a timed one — a
VALUE-dependent lexical form (`Render.java:707-724`). A true midnight DateTime
and a date become indistinguishable.

---

### [BACKWARD ASYMMETRY / INCONSISTENCY] Four lexical forms for one DateTime; two of them are not re-parseable by the platform's own date reader

For the single source value `TIMESTAMP '2024-03-15 13:45:56.123456'` typed
`DateTime`:

| exit | text | `PureDateLiteral.parse` |
|---|---|---|
| GRAPH JSON (`Compiler.execute`) | `"2024-03-15T13:45:56.123456000"` | OK (`DateWithSubsecond`) |
| tabular JSON wire (`executeWire` JSON) | `"2024-03-15 13:45:56.123456"` | **FAILS** |
| CSV wire | `2024-03-15 13:45:56` | **FAILS** |
| tabular JDBC decode | `DateWithSubsecond(2024-03-15T13:45:56.123456+0000)` | OK |
| `Render.dateTimeText` (TDS/PCT lane, `Render.java:542-556`) | `...T..:..:...SSS+0000` (3 digits) | OK |

**Repro** (`/tmp/a19/Dt.java`, actual output):
```
2024-03-15T13:45:56.123456000  ->  DateWithSubsecond engine='2024-03-15T13:45:56.123456000' toString='2024-03-15T13:45:56.123456000+0000' roundtrip=true
2024-03-15T13:45:56.123456     ->  DateWithSubsecond engine='2024-03-15T13:45:56.123456'    roundtrip=true
2024-03-15 13:45:56            ->  ERR IllegalArgumentException: expected 'T' after day at position 10 in '2024-03-15 13:45:56'
0001-01-01                     ->  StrictDate engine='1-01-01' toString='1-01-01' roundtrip=false
1-01-01                        ->  StrictDate engine='1-01-01' roundtrip=true
```

Notes:
(a) the GRAPH form carries **no timezone designator at all**, so the instant is
ambiguous, while both the CSV/TDS spelling and the Java decode append `+0000`;
(b) `lowering/Fold.java:961-964` (`jsonDateWrap`) pads DuckDB's microsecond field
with a literal `"000"` to fake nanoseconds, producing a 9-digit fraction no other
exit in the system emits;
(c) year 1 pads to `0001-01-01` on the JSON and CSV wires but the Java decode
prints `1-01-01` (`values/PureDateLiteral.java:283-285` uses `%d` for the year) —
the two spellings are not the same lexical value.

---

### [INFORMATION LOSS] The abstract `Date` wire form is chosen at RUNTIME by the physical value's type, not by the static type

**Evidence.** `lowering/Fold.java:968-975`:
```java
if (t == com.legend.compiler.element.type.Type.Primitive.DATE) {
    arms.add(new SqlExpr.Case.When(
            SqlExpr.Call.of(SqlFn.EQUAL,
                    SqlExpr.Call.of(SqlFn.TYPEOF, e),
                    new SqlExpr.StringLit("DATE")),
            SqlExpr.Call.of(SqlFn.STRFTIME, e, new SqlExpr.FormatLit(DateFmt.DATE))));
}
```
A `typeof(...)` test **in the emitted SQL** decides the JSON lexical form.

**Repro** (`dt.pure`: `dOnDate` maps to a DATE column, `dOnTs` to a TIMESTAMP
column; BOTH are declared `Date[0..1]`):
```
[SQL] ... 'dOnDate', CASE WHEN t0.D IS NULL THEN NULL WHEN typeof(t0.D) = 'DATE' THEN strftime(t0.D, '%Y-%m-%d') ELSE concat(strftime(t0.D, '%Y-%m-%dT%H:%M:%S.%f'), '000') END,
      'dOnTs',   CASE WHEN t0.TS IS NULL THEN NULL WHEN typeof(t0.TS) = 'DATE' THEN strftime(t0.TS, '%Y-%m-%d') ELSE concat(strftime(t0.TS, '%Y-%m-%dT%H:%M:%S.%f'), '000') END ...
[JSON] [{"n":"midnight","dOnDate":"2024-03-15","dOnTs":"2024-03-15T00:00:00.000000000","sd":"2024-03-15","dtm":"2024-03-15T00:00:00.000000000"},
        {"n":"timed",   "dOnDate":"2024-03-15","dOnTs":"2024-03-15T13:45:56.123456000","sd":"2024-03-15","dtm":"2024-03-15T13:45:56.123456000"},
        {"n":"nulls","dOnDate":null,"dOnTs":null,"sd":null,"dtm":null},
        {"n":"bc","dOnDate":"0001-01-01","dOnTs":"0001-01-01T00:00:00.000000000","sd":"0001-01-01","dtm":"0001-01-01T00:00:00.000000000"}]
```
Same static type, two different JSON value shapes (bare date vs full instant),
selected by the storage. A reader cannot know from the type which to expect. The
JDBC decode shows the same split: `StrictDate(2024-03-15)` for `dOnDate` vs
`DateWithSecond(2024-03-15T00:00:00+0000)` for `dOnTs`.

---

### [BUG] `removePropertiesWithNullValues` does not remove the FIRST property's null (and does nothing at all for a one-leaf tree)

**Evidence.** `lowering/SnapshotEnvelope.java:58-80` (`mergePatchObject`) folds one
`json_object` per key through `json_merge_patch`:
```java
return pieces.size() == 1 ? pieces.get(0)
        : new SqlExpr.Call(com.legend.sql.SqlFn.JSON_MERGE_PATCH, pieces);
```
RFC 7386 removes null-valued keys **from the patch**, never from the target — so
piece 0 is never patched, and a single piece is returned unwrapped.

**Repro** (`kitchen.sql` row 4 is all-NULL):
```
Q: ...#{ks::Kitchen{i,s,col}}# with alloyConfig(false,false,true,false)
  [SQL] ... json_merge_patch(json_object('i', t0.I), json_object('s', t0.S), json_object('col', ...)) ...
  [JSON] [{"i":42,"s":"plain","col":"RED"},...,{"i":null},{"i":0,"s":"sci","col":"RED"}]
                                                ^^^^^^^^ s and col removed, i retained
Q: ...#{ks::Kitchen{s}}# with alloyConfig(false,false,true,false)
  [SQL] ... json_object('s', t0.S) ...        <- no merge patch at all
  [JSON] [{"s":"plain"},{"s":""},...,{"s":null},{"s":"sci"}]
```
The flag's effect depends on the property's POSITION in the tree.
(`removeEmptySets` on its own works: `{"s":"","tagNames":[]}` becomes `{"s":""}`.)

---

### [SILENT FALLBACK] `alloyConfig`'s `dateTimeFormat` argument is silently discarded

**Evidence.** `resolver/GraphEmission.java:3176-3195` decodes the 8-arg
`alloyConfig` positionally and never reads `a.get(2)` — the registered
`dateTimeFormat:String[1]` parameter (`builtin/Pure.java:1115`, `ALLOY_CONFIG__8`):
```java
int rn = n == 8 ? 3 : 2;
String key = n >= 6 ? strArg(a.get(n == 8 ? 5 : 4), "@type") : "@type";
boolean fq = n >= 6 ? boolArg(a.get(n == 8 ? 6 : 5)) : true;
...
```
The class doc directly above (`:3163-3166`) claims: *"every OTHER envelope-changing
flag walls loudly — never a silently-ignored config"*.

**Repro:**
```
Q: ...serialize(#{ao::T{k,v}}#, alloyConfig(true, false, 'yyyy', false, false, '@type', true, false))
  [SQL] SELECT CAST(coalesce(to_json(list(json_object('@type', 'ao::T', 'k', t0.K, 'v', t0.V) ORDER BY t0.K ASC NULLS LAST)), '[]') AS VARCHAR) AS result FROM TT AS t0
Q: ...serialize(#{ao::T{k,v}}#, alloyConfig(true, false, false, false))
  [SQL] SELECT CAST(coalesce(to_json(list(json_object('@type', 'ao::T', 'k', t0.K, 'v', t0.V) ORDER BY t0.K ASC NULLS LAST)), '[]') AS VARCHAR) AS result FROM TT AS t0
```
Byte-identical SQL. The requested date format has no effect and no wall.

Same method, same class: `boolArg` (`GraphEmission.java:3241-3244`) returns
`false` and `strArg` (`:3246-3249`) returns its default for ANY non-literal
argument, so a config assembled from a variable is silently read as all-defaults
instead of walling. The `^AlloySerializationConfig(...)` branch has the same shape
(`:3211-3220`: `if (v instanceof TypedCString cs) { key = cs.value(); }` with no
else).

---

### [BUG / INCONSISTENCY] A graph-tree ALIAS is not unescaped — string escapes survive into the JSON key

**Evidence.** `parser/SpecParser.java:3346-3352`:
```java
String raw = text();
alias = raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : raw;
```
The quotes are stripped by index; the string-literal unescape is never applied.
Every other string-literal position in the language does unescape.

**Repro** (controls first):
```
Q: 'a\'b'                                    [SCALAR] String(a'b)            <- correct
Q: 'a\\b'                                    [SCALAR] String(a\b)            <- correct
Q: ks::Kitchen.all()->project(~['a\'b':x|$x.s])
   [G] TypedProject :: Relation<(a'b:String[0..1])>[1]                       <- correct
Q: ...graphFetch(#{ks::Kitchen{'a\'b': s}}#)->serialize(#{ks::Kitchen{'a\'b': s}}#)
   [SQL] ... json_object('a\''b', t0.S) ...
   [JSON] [{"a\\'b":"plain"},...]            <- key decodes to  a\'b  (4 chars), not  a'b
Q: ...#{ks::Kitchen{'a\\b': s}}#
   [SQL] ... json_object('a\\b', t0.S) ...
   [JSON] [{"a\\\\b":"plain"},...]           <- key decodes to  a\\b  (4 chars), not  a\b
```
(The alias is correctly ESCAPED into JSON — `'a"b'` gives the key `a\"b` — so this
is a decode bug, not an injection.)

---

### [INCONSISTENCY] `serialize(...)`'s String value depends on its syntactic position AND on the row count

**Evidence.** `lowering/Lowerer.java:3119-3121` wraps a scalar-position
`TypedSerializeGraph` in `SnapshotEnvelope.fold`
(`lowering/SnapshotEnvelope.java:34-51`), which emits
`CASE WHEN COUNT(*) = 1 THEN MIN(<object>) ELSE <array> END`. The root position
(`Lowerer.java:260-262`) does not fold.

**Repro** (`/tmp/a19/one.sql` — exactly ONE row):
```
Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s}}#)->serialize(#{ks::Kitchen{s}}#)
  [SQL] SELECT CAST(coalesce(to_json(list(json_object('s', t0.S) ORDER BY ...)), '[]') AS VARCHAR) AS result FROM T_K AS t0
  [JSON] [{"s":"only"}]                                      (13 chars)

Q: 'X' + ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s}}#)->serialize(#{ks::Kitchen{s}}#)
  [SQL] SELECT concat('X', (SELECT CAST(CASE WHEN COUNT(*) = 1 THEN MIN(json_object('s', t0.S)) ELSE coalesce(to_json(list(...)), '[]') END AS VARCHAR) ...)) AS value
  [SCALAR] String(X{"s":"only"})                             (array wrapper gone)

Q: ks::Kitchen.all()->graphFetch(#{ks::Kitchen{s}}#)->serialize(#{ks::Kitchen{s}}#)->size()
  [SCALAR] Long(12)                                          (12, not 13)
```
The same expression is `[{...}]` at the root and `{...}` when nested — and only
when the result happens to hold exactly one row. `serialize` is neither
referentially transparent nor data-shape-independent.

---

### [INFORMATION LOSS] Enums are lexically indistinguishable from Strings on every path

`col: Colour` yields `"col":"RED"` in the graph JSON, `RED` in CSV, and
`String(RED)` (a `java.lang.String`, not any enum carrier) in the tabular JDBC
decode under a column typed `ks::Colour`. Nothing on any wire records that the
value is an enumeration member. `includeEnumType` is the only recovery, it is off
by default, and it is broken for nulls (finding above).

---

### [ANALYSIS + EMPIRICAL] `MetamodelWalk` — a 1580-line HOST-SIDE evaluator that does NOT run during a normal `Compiler.execute`, but does perform runtime type inference when reached

**What it is.** A host-side interpreter over the compiled *store metamodel*
(`DatabaseDefinition`, `MappingDefinition`) and over SQL-protocol node values. It
is reached from `StatementExecutor.planWalk` (called at `StatementExecutor.java:346`
for EVERY statement, before Phase H) and from `MetamodelSteps`.

**Empirical determination (class-load instrumentation).** A class is loaded only
on first active use, so `-verbose:class` is a sound entry probe.

Negative controls — GRAPH and TABULAR queries over `kitchen.pure`:
```
$ java -verbose:class -cp ... Raw kitchen.pure q_nobyte.pure  test::KRuntime kitchen.sql | grep -i metamodel
   (no output)
$ java -verbose:class -cp ... Raw kitchen.pure q_tab_all.pure test::KRuntime kitchen.sql | grep -ci metamodel
0
```
1005 `com.legend.*` classes load (including `com.legend.StatementExecutor`,
`com.legend.StatementExecutor$ExecEnv`, `com.legend.exec.Executor`);
`com.legend.exec.MetamodelWalk` and its nested records load ZERO times.
**No MetamodelWalk path executes during a normal `Compiler.execute`, graph or
tabular.**

Positive control (proving the instrument is live) — query = the bare element
reference `store::KDB`:
```
[4.442s][info][class,load] com.legend.exec.MetamodelWalk source: .../core/target/classes/
[4.443s][info][class,load] com.legend.exec.MetamodelWalk$Db source: .../core/target/classes/
SHAPE=Scalar
COL value : ClassType[fqn=meta::relational::metamodel::Database]
ROW Db(Db[db=DatabaseDefinition[qualifiedName=store::KDB, ...]])
```
`Compiler.execute` returns an `ExecutionResult.Scalar` whose declared Pure type is
`meta::relational::metamodel::Database[1]` and whose runtime Java value is a
`com.legend.exec.MetamodelWalk$Db` record, produced with **no JDBC round trip at
all**. So a blanket "Java executes nothing and the DB executes everything" is
false for this surface — although the repo's own ledger
(`core/src/test/java/com/legend/JavaEvalLedgerTest.java:31-39`) registers it
honestly as the "metamodel channel", and
`ArchitectureTest.theInterpreterPerformsNoJdbc` (`:531-555`) mechanically
guarantees no database VALUE can enter it (it forbids
`java.sql..`/`org.duckdb..`/`org.h2..` dependencies from these classes). Verdict:
host-side evaluation of *typed model* values — yes; host-side evaluation of
*database result* values — no.

**The RUNTIME (K-phase) type decisions it makes** — each is a place where the
static type could disagree with the produced value; all in
`core/src/main/java/com/legend/exec/MetamodelWalk.java`:

1. `inferOp` (`:1230-1352`) — an entire SQL type-inference engine executed at
   execution time: `max`/`min`/`distinct` carry their argument's type; `sum`/`avg`
   PROMOTE Float/Real to `Double_`; `count` → `Integer_`; the whole boolean
   dyna-function family (`and`,`or`,`not`,`equal`,`in`,`like`,…) → `Bit`;
   `sqlnull` → `Other`; `substring`/`trim`/`upper`/… keep their input's type;
   `position`/`length`/`charindex` → `Integer_`; `concat`/`group_concat`
   **sum the operand sizes**; `joinstrings` → a fixed `Varchar(4000)`; `case` and
   the arithmetic ops fold through `safe`.
2. `safe(a,b)` (`:1460-1507`) — the type-lattice join, computed at runtime: two
   decimals widen to `Decimal(maxIntDigits + maxScale, maxScale)`; `Other` absorbs
   into the other operand; any float side wins over integers; two varchars take
   the max size.
3. `columnType` (`:1525-1568`) — resolves `table.column` against the live model by
   **case-insensitive** name matching (`equalsIgnoreCase`) and by stripping schema
   qualification (`table.substring(lastIndexOf('.') + 1)`); a miss returns `null`
   with no wall; a VIEW column resolves recursively through its expression.
4. `RelationalOperation.Literal` → `new RelationalDataType.Varchar(str.length())`
   (`:1334-1336`) — the inferred TYPE depends on the runtime VALUE's length.
5. `nodeOf` (`:124-141`) — "a one-element collection IS its element" plus
   materializing class-declared ctor defaults (`distinct = false`), at runtime.

**Empirical demonstration** (`/tmp/a19/mm.pure`, view
`V1 ( ~groupBy (TT.ID) id: TT.ID, ab: concat(TT.A, TT.B), cd: TT.C, cnt: count(TT.ID) )`
where `A VARCHAR(10)`, `B VARCHAR(7)`, `C DECIMAL(10,2)`):
```
Q: mm::DB->schema('default')->toOne()->view('V1').columnMappings->at(1).relationalOperationElement->...inferRelationalType()->toOne()->...dataTypeToSqlText()
  [SCALAR] String(VARCHAR(17))     <- 10 + 7, summed host-side at execution time
Q: ...at(2)...  [SCALAR] String(DECIMAL(10, 2))
Q: ...at(3)...  [SCALAR] String(INT)
```

**Silent-null policy.** The class doc (`:19-23`) claims "Every unrecognized shape
returns null (the caller's walk falls through to its own walls) — never a silent
wrong answer". In practice the `default -> null` arms in `inferOp` (`:1348`),
`convertOp`, `infer` (`:1220-1228`), `sqlText` (`:1575-1580`) and `columnType`
mean an unknown SQL function, an unknown column, or a mis-cased table name
silently produces "no type" rather than walling — the repo's own stated
NO-FALLBACK rule.

---

### [DOC-LIE] `AnsiSqlRenderer.jsonArrayAgg`: "to_json over the JSON list yields the same array value"

`sql/dialect/AnsiSqlRenderer.java:479-483`. Falsified above: the round trip
through `to_json` converts nested DECIMALs to DOUBLE.

### [DOC-LIE] `StoreResolver.isClassToOne`: "the value compare sees all N and fails loud — a documented, weaker-but-never-silent stand-in"

`resolver/StoreResolver.java:1073-1077`. Falsified above: N=0 yields `[]` and N=2
yields two objects, both silently, with nothing to compare against.

### [DOC-LIE] `GraphEmission.serializeTypeConfig`: "every OTHER envelope-changing flag walls loudly — never a silently-ignored config"

`resolver/GraphEmission.java:3163-3166`. Falsified above: `dateTimeFormat` is
silently dropped from the 8-arg `alloyConfig`.

### [DEAD/UNREACHABLE] `Render.cell`'s `TDSNull` arm is unreachable from the product CSV wire

`lowering/Render.java:736-737` renders `TDSNull` when `renderTdsNull` is true, but
`csvWire` (`Render.java:220`) hardcodes `false`. Only the `toCSV(…, true)`
Pure-function path can reach it, so the product wire has no way to express NULL.

---

# VERIFIED SOUND

**Graph-fetch tree typing (task 1) — all seven required cases were run:**

| case | result |
|---|---|
| property that does not exist | clean `TypeInferenceException: graphFetch tree: class ks::Kitchen has no property 'nosuchprop'` — no ICE, at G, PLAN and EXEC |
| SUBCLASS property on a superclass-typed root (`#{poly::Animal{breed}}#`) | clean `TypeInferenceException: graphFetch tree: class poly::Animal has no property 'breed'` |
| subtype branch `->subType(@poly::Dog){breed}` | compiles; emits `CASE WHEN t2."stc_poly__Dog___$member" THEN json_object('name',…,'breed',…) ELSE json_object('name',…) END`; JSON `[{"name":"Rex","breed":"Lab"},{"name":"Fido","breed":"Pug"},{"name":"Tom"}]` — correct |
| deeply nested (`#{Kitchen{s, inners{label, owner{s}}}}#`) | correct and well-formed: `[{"s":"plain","inners":[{"label":"inner-one","owner":{"s":"plain"}},{"label":"in\"ner\\two","owner":{"s":"plain"}}]},{"s":"","inners":[]},…]` |
| `[*]` property (`inners`, `tags`) | correlated subquery per hop, array-wrapped, empty → `[]`: `[{"inners":[{…},{…}]},{"inners":[]},…]` |
| empty tree `#{ks::Kitchen{}}#` | clean `ParseException: [1:45] Unexpected token '}'` |
| same property twice | **compiles — see the duplicate-key finding** |

Also verified: a scalar property carrying a sub-tree walls cleanly
(`graphFetch tree: property 's' is not class-typed and cannot carry a sub-tree`);
`GraphFetchChecker.validate:110-125` walls a `->subType` naming an unknown class
or a non-subtype; `graphFetch` without `serialize` walls cleanly
(`NotImplementedException: class query under TypedGraphFetch is not resolvable yet
(H2 vocabulary)`). No tree shape I tried produced an ICE.

**JSON string escaping is correct and NOT injectable.** Every value and every key
goes through DuckDB's `json_object`, which RFC 8259-escapes. Tested explicitly with
`"`, `\`, LF, TAB, NUL, a non-BMP astral char (U+1F600) and U+2028; actual bytes:
```
"s":"a\"b\\c\nd\te f<U+D83D><U+DE00>g<U+2028>h"
```
`"` → `\"`, `\` → `\\`, LF → `\n`, TAB → `\t`, NUL → ` `, the astral character
survives as a correct surrogate pair, and the whole document round-trips through
`com.legend.sql.Json.parse` (`REPARSE_OK class=java.util.ArrayList`). An alias
containing `"` produces the key `a\"b` — escaped, not injected. (U+2028 is emitted
raw; legal JSON and legal in ES2019+ string literals.)

**CSV escaping is correct RFC 4180** (proof pasted in the NULL finding above):
comma → quoted; `"` → doubled inside quotes; LF and CRLF → quoted; the literal
`""` → `""""""`; header names go through the SAME `escapeCsv` SQL expression
(`Render.java:894-905`), so there is exactly one escape owner and no second Java
copy.

**Integers are faithful at both extremes.** `9223372036854775807` and
`-9223372036854775808` survive the graph JSON, the tabular JSON wire, the CSV wire
and the JDBC decode as exact JSON integers / `java.lang.Long` — no string-wrapping,
no double rounding.

**Float is faithful and unambiguously typed.** `1.7976931348623157e308`,
`-1.7976931348623157e308`, `0.30000000000000004`, `1e-7`, `1e100`, `2.0`, `100.0`,
`3.0` all round-trip exactly; a whole-valued Float keeps its `.0` so it is never
confusable with an Integer. (`-0.0` normalises to `0.0` — DuckDB behaviour.)

**Booleans** are native JSON `true`/`false` in both JSON paths, `true`/`false` in
CSV, `java.lang.Boolean` in the decode.

**NULL is faithful in both JSON paths**, per property, including the all-NULL row:
`{"s":null,"i":null,"f":null,"dec":null,"b":null,"dt":null,"sd":null,"dd":null,"col":null}`.

**`StrictDate`** is `"2024-03-15"` / `"9999-12-31"` / `"0001-01-01"` in the graph
JSON and `StrictDate(...)` in the decode — faithful (modulo the year-1 padding
divergence noted above).

**`graphFetchChecked`** produces the engine-shaped defect envelope, evaluated
entirely in SQL, with the constraint applied per row (`/tmp/a19/chk.pure`):
```
[{"defects":[],"value":{"name":"ok","n":5}},
 {"defects":[{"id":"posN","externalId":null,"message":"Constraint :[posN] violated in the Class T","enforcementLevel":"Error","ruleType":"ClassConstraint","ruleDefinerPath":"ck::T","path":[]}],"value":{"name":"bad","n":-1}}]
```

**`ResultShape` boundary cases (task 7).**
- zero objects → `[]` (both `filter`-to-empty and `toOne`/`first` over empty).
- one object → `[{...}]` at the root (an array, never a bare object) — see the
  snapshot-fold finding for the nested position.
- a scalar root (`ks::Kitchen.all()->size()`) → `SCALAR`, `Long(5)`;
  `->map(x|$x.s)` → `COLLECTION` of `String` with the null cell dropped
  (`n=4` from 5 rows, one NULL).
- a class-typed root WITHOUT a serialize envelope (an instance literal
  `^ks::Inner(label='hi', n=3)`) correctly classifies SCALAR, not GRAPH — the
  node-aware split in `exec/ResultShape.java:23-35` — and lowers to a SQL struct
  `SELECT {'label': 'hi', 'n': 3} AS value`; a collection of instance literals
  classifies COLLECTION.
- a relation nested inside an object is not expressible in a graph tree (the
  checker requires a class-typed property to carry a sub-tree) — walls cleanly.
- `TypedSerializeGraph` is the ONLY thing that makes a root GRAPH
  (`ResultShape.java:27-29`), so the tabular/graph split is decided exactly once
  and never re-derived.

**JSON key ORDER equals fetch-tree order** in every run; `@type` leads the object
when `includeType` is on.

**Union member serial order is deterministic**: `u_serial_ord__` + member witness
DESC + PK ASC keys (`Lowerer.java:911-955`), and an explicit user `sortBy`
suppresses the PK keys rather than being silently overridden
(`GraphEmission.containsExplicitSort:440-444`).

**To-many PRIMITIVE leaves** produce the correct bare-value array shape:
`{"s":"plain","tagNames":["t1","t,2"]}`, `{"s":"","tagNames":[]}`,
`{"s":"…","tagNames":["t\"3"]}` — values escaped, empty → `[]`.

**To-one class children** serialize as a bare nested object, not a one-element
array: `[{"owner":{"s":"plain"}},{"owner":{"s":"plain"}},{"owner":{"s":"…"}}]`.

**`ClassLayouts.collect`** (`compiler/element/ClassLayouts.java:114-133`) walls
loudly on conflicting inherited property declarations rather than first-wins, and
`layoutOf(withIdentity)` walls on a user property named `__id` (`:71-75`).

**`LayoutTypes`** (`lowering/LayoutTypes.java:46-67`) has a real cycle guard on the
layout walk (revisited class → `SqlType.Scalar.JSON`) rather than recursing
forever.

**`JsonCompare`** (`exec/JsonCompare.java:99-113`) deliberately keeps
`Long`-vs-`BigDecimal` UNEQUAL in the document lane — exactly the right rule for
catching the decimal defect above. It is a test-side comparator, not on the
product path.

**`server/serial/*`** (`CsvSerializer`, `JsonSerializer`, `ResultSerializer`,
`SerializerRegistry`) carry no value logic at all — only `formatId` /
`contentType` / `supportsStreaming` metadata. The text really is plan-rendered;
`SerializerRegistry.get` throws on an unknown format rather than defaulting. No
finding.

**`Compiler.executeWire` refuses CSV for GRAPH results** loudly
(`Compiler.java:422-425`: `NotImplementedException: graph results have no CSV
wire`) rather than silently flattening an object graph — correct.

**`PctTdsWrap.slotName`** (`lowering/PctTdsWrap.java:206-214`) throws rather than
defaulting an untyped slot to VARCHAR, and `typedColumns` walls when a pivot
column matches more than one `using` alias — no silent backend-typed guess.

---

# NOT COVERED

- **`Byte` and `StrictTime` end to end.** Neither has an SQL carrier
  (`lowering/PureSql.java:92`: `case BYTE, STRICT_TIME -> null`). `Byte` cannot be
  planned at all (see the ICE finding); `StrictTime` cannot even be *mapped* — no
  `RelationalDataType` maps to it (`compiler/element/StoreCompiler.java:182-208`,
  `normalizer/RelationalKinds.java:22-51`), so `tm: TT.TM` over a VARCHAR column
  walls at Phase H with `property 'tm' of 'st::T': expected StrictTime, got
  String`. Their JSON fidelity is therefore untestable through the relational
  graph path — a structural coverage gap, not a defect I could exercise.
- **Non-DuckDB dialects.** All execution here is DuckDB v1.5.0. The H2 renderer
  uses `COALESCE(JSON_ARRAYAGG(...), JSON '[]')` (`sql/dialect/H2.java:419-434`),
  a different idiom whose decimal / Infinity behaviour I did NOT measure; the H2
  session path also requires an all-H2 runtime declaration my fixtures do not
  have. The decimal finding is proven for the DuckDB reference dialect only
  (which is also the one `probe.sh` and most of the corpus use).
- **`resolver/JsonSourceFrame.java`** — read in full but not executed: it needs a
  `JsonModelConnection(url='data:application/json,…')` fixture I did not build.
  Unverified observation for a follow-up: `sourceUrlFrame:170-173` wraps each raw
  object span in bare `"` quotes
  (`rows.add(List.of("\"" + objects.get(i) + "\"", …))`) and relies on
  `Scalars.tdsCell`'s variant arm stripping exactly one outer pair — the same
  unescaped-quote-wrap shape as the ASOR bug above, but I did not run it.
- **`Decimal` values wider than the fixed `SqlType.Decimal(38, 18)` carrier** that
  `PureSql.type` assigns to the bare `DECIMAL` primitive
  (`lowering/PureSql.java:75`). I only exercised store-declared `DECIMAL(38,10)`;
  a Pure-side Decimal literal with >18 fractional digits is a separate probe.
- **`removeEmptySets` × to-one class children**, `mixedUnionChild`, embedded
  (`inlineChild`) graph children, milestoned/temporal graph trees, the
  `AllVersions` sweep spelling, and qualifier (`prop(args)`) tree leaves — all
  read in `GraphEmission` but not executed; each needs a substantial dedicated
  fixture.
- **`MetamodelWalk`'s `convertElement` / `toPostgresModel` half** (`:216-696`,
  ~480 lines) — read, but only the `infer` / `sqlText` / `schema` / `table` /
  `view` / `database` arms were driven empirically.
- **The PCT `Render.pctTds` wire** — read in full; its typing walls look correct,
  but it is a test-harness wire and I did not execute it.
- **`protocol/spec/PathLiteral.java` and `NewInstance.java`** — read; no defect
  found in their own code, and their behaviour is exercised only indirectly here
  (instance literals lower to SQL structs, which I did verify). One low-value
  note: `GraphFetchLiteral.equals` (`:80-87`) compares only
  `className`/`desugared`/`unsupported`, ignoring `subTrees`/`subTypeTrees`, so
  two literals with different wire trees but the same desugaring compare equal.
