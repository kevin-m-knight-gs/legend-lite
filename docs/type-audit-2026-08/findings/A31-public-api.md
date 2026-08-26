# A31 — PUBLIC API / TYPE SURFACE AUDIT

Scope: `com/legend/server/**` (2466 ln), `com/legend/ide/**` (622), `com/legend/cache/**` (235),
`com/legend/spi/**` (64), `com/legend/error/**` (150), and `com/legend/Compiler`'s public methods.

All numbers below come from code I ran. Probe sources: `/tmp/a31/{Escape,Http,Http2,Wire,Collide,Ide,Gaps,Amb,Cache2,Final,Graph,Api}.java`
and `/tmp/a31/spi/{Evil,SpiProbe}.java`. Raw outputs: `/tmp/a31/{escape.out,http.out,api.out,escapes.txt}`.

---

## 1. THE COMPLETE PUBLIC API SURFACE (enumerated by reflection — 201 public/protected members)

Full dump: `/tmp/a31/api.out`. The **type-bearing** entry points and what a caller gets back:

| Entry point | Returns | Type info the caller gets | Trustworthy? |
|---|---|---|---|
| `Compiler.parseModel(String)` | `ParsedModel` | untyped AST: `protocol.TypeExpression` = the name **as written** | no types at all; simple names unresolved |
| `Compiler.parseQuery(String)` | `ValueSpecification` | none | — |
| `Compiler.compileModel(String)` / `(List<ModelSource>)` | `ModelContext` | full `TypedClass`/`Type` graph | **best surface**; but keeps unresolved supertypes (`superClassFqns=[Foo]`, F14) |
| `Compiler.buildModel(ParsedModel)` | `ModelContext` | same | same |
| `Compiler.buildModule(ParsedModel)` | `BuiltModule(ctx, walls: Map<String,String>)` | typed ctx + errors **as strings** | wall map loses the exception type AND the Phase |
| `Compiler.parseSources(...)` | `ParsedModule(model, duplicateElements, sourceTexts)` | untyped | — |
| `Compiler.compileAllBodies(ctx)` | `Map<String,String>` | errors as **strings only** | Phase + exception class discarded |
| `Compiler.compileQuery(model,q)` | `TypedSpec` | `info()` = `ExprType(Type, Multiplicity)`; full `RelationType` with per-column type+multiplicity | **the richest, most trustworthy surface** |
| `Compiler.compile(m,q,rt)` | `String` (SQL) | none | — |
| `Compiler.plan/planStreaming(m,q,rt)` | `QueryPlan(sql, rootType: ExprType, shape)` | root `ExprType` + `ResultShape` | good |
| `Compiler.lowerResolved(...)` | `SqlQuery` (MIR) | typed MIR | good |
| `Compiler.execute*` (3 overloads) | `@Nullable ExecutionResult` | sealed quartet — see below | shape-dependent, see F7 |
| `Compiler.executeResolved(...)` | `@Nullable ExecutionResult` | same | same |
| `Compiler.executeStreaming(...)` | `void` (writes JSON) | **none** — bytes only | no types |
| `Compiler.executeWire(...)` | `List<String>` column **names** | names only, **no types**; `[]` for GRAPH (F7) | degraded |
| `QueryService.execute(...)` ×2 | `ExecutionResult` | as `Compiler.execute`, plus a `requireNonNull` wrapper that turns a null into an NPE | — |
| `QueryService.execute(...,out,fmt)` ×2 | `void` | **none** | — |
| `QueryService.executeWireJson(...)` | `WireData(json, columns)` | column **names** only | `[]` for GRAPH |
| `QueryService.executeSql(...)` | `ExecutionResult.Tabular` | **always `columns=[], rows=[], RelationType([])`** — the ResultSet is discarded (F16) | actively wrong |
| `QueryService.stream(...)` ×2 | `void` | none | — |
| `PureLspServer.handleMessage(String)` | `List<String>` JSON-RPC | diagnostics: `message` string + a fabricated range | **untrustworthy** (F11, F12) |
| `DiagramService.extract(String)` | `DiagramData` | `PropertyInfo(name, type: String, multiplicity: String)` — type is the **simple name as written** | **untrustworthy** (F14, F15) |
| `LegendHttpServer` `POST /engine/execute` | JSON envelope | `columns` (names) + untyped `data` | HTTP 200 on every failure (F9) |
| `LegendHttpServer` `POST /engine/sql` | `{success, message}` | **none** | F16 |
| `LegendHttpServer` `POST /engine/diagram` | diagram JSON | as `DiagramService` | F14/F15 |
| `LegendHttpServer` `POST /lsp` | JSON-RPC | as `PureLspServer` | F11/F12 |
| `SerializerRegistry.get/getOrNull/register/isSupported/availableFormats` | `ResultSerializer` | `formatId()`, `contentType()`, `supportsStreaming()` — **no `serialize` method exists** | dead seam (F19) |
| `ModelOrchestrator.resolve/resolveAll/index/imports/declaredFqns` | `PackageableElement` / `ParsedModel` / `ModelIndex` | untyped AST + token ranges | diverges from the compiler (F13) |
| `ModelIndexer.scan(TokenStream)` | `ModelIndex` | `Entry(fqn, ElementKind, tokenRange)` | F13 |
| `ContentStore.getOrCompute(Hash, Supplier<T>)` | `T` (unchecked cast) | **caller-asserted**, unverified | type-unsound (F17) |
| `Hash.of/ofUtf8/combine` | `Hash` | — | mostly sound (F24) |
| `HandleStore.getOrOpen(Hash, Predicate, Open)` | `T` | — | key ≠ value (F1) |
| `spi.SectionGrammar.parse(SectionSource, ElementSink)` | `void` | third party emits `(fqn, protocolJson)` — **zero validation** | F20/F21 |
| `error.LegendCompileException.phase()/element()` | `Phase` / `@Nullable String` | 9 phases declared, **5 reachable** | F4, F25 |

`ExecutionResult` is a sealed quartet; the type information differs per variant:

| Variant | Type info |
|---|---|
| `Tabular(columns, rows, returnType)` | per-column `Column(name, pureType, multiplicity)` — **the only per-field typed surface** |
| `Scalar(@Nullable Object value, Type returnType)` | one `Type` |
| `Collection(List<Object> values, Type returnType)` | one `Type`, no per-element type |
| `Graph(String json, Type returnType)` | only `ClassType[fqn=…]`; the JSON fields are **untyped text** (F7) |

---

## FINDINGS

### [UNSOUND] F1 — `cache/HandleStore` key does not determine the value: two unrelated models sharing one connection definition get each other's tables, and the compiler's static type is violated

`ConnectionResolver.java:88-91` is the ONLY live cache key in `core/`:

```java
private static Hash contentKey(ConnectionDefinition def) {
    return Hash.combine(Hash.ofUtf8(def.qualifiedName()),
            Hash.ofUtf8(def.toString()));
}
```

and `ConnectionResolver.java:100-102`:

```java
default -> STORE.getOrOpen(contentKey(def),
        ConnectionResolver::dead,
        () -> DriverManager.getConnection("jdbc:duckdb:"));
```

The key is `(connection FQN, ConnectionDefinition record)`. The **value** is a *live in-memory database*
whose content — the physical tables and their SQL column types — is not a function of that key. The model's
`Database` DDL, its `Mapping`, and its Pure types are all absent from the key. `HandleStore` javadoc
(`HandleStore.java:26-28`) makes this permanent: *"No eviction … Entries live for the process."*

**Repro** (`/tmp/a31/Collide.java`): two models, identical `###Connection`/`###Runtime` blocks, different
everything else. Model A declares `v: String[1]` over `V_C VARCHAR(50)`; model B declares `v: Integer[1]`
over `V_C INTEGER`. Both reach `QueryService` (public API) with runtime `rt::R`.

**Actual output:**

```
modelA == modelB ? false

[A] declared String / physical VARCHAR
    Tabular[columns=[Column[name=v, pureType=STRING, multiplicity=Bounded[lower=1, upper=1]]],
            rows=[Row[values=[not-a-number]]], ...]

[B] declared Integer / SAME cached connection, physical table is A's VARCHAR
    STATIC TYPE  : GenericType[rawFqn=meta::pure::metamodel::relation::Relation,
                   arguments=[RelationType[columns=[Column[name=v, type=INTEGER,
                   multiplicity=Bounded[lower=1, upper=1]]], dynamicColumns=[]]]]
    COLUMNS      : [Column[name=v, pureType=INTEGER, multiplicity=Bounded[lower=1, upper=1]]]
    RUNTIME VALUE: not-a-number   java class = java.lang.String
```

The compiler asserts `INTEGER[1]`; the value handed back is `java.lang.String "not-a-number"`. This is the
top-prize category and it is reachable from the **public HTTP surface** — `/engine/sql` lets any caller
create tables in that shared database and `/engine/execute` then reads them through a different model's
declared types. The `ContentStore` javadoc claims content addressing means a cache "**cannot desync**"
(`ContentStore.java:18-19`); here it desyncs because the key covers the *handle's definition* while the
value is the *database's mutable content*.

Also from the same probe: adding a comment inside the `###Connection` block does **not** change the key
(`def.toString()` is the parsed record, not source text), so `[C]` still hits A's database.

---

### [CRASH/ICE] F2 — 127 of 863 probes across the public API escape as raw internal exceptions, with no `Phase` and no user-facing type

`/tmp/a31/Escape.java` throws 36 bad queries × 13 query entry points, 24 bad models × 15 model entry
points, 5 bad runtime names × 4 entry points, and 15 null/degenerate-argument cases — **863 probes total**.
Every escaping `Throwable` is classified.

**Actual output** (`/tmp/a31/escape.out`):

```
total probes           : 863
no throw               : 291
LegendCompileException : 410
NotImplementedException: 35
INTERNAL escapes       : 127

--- by class/phase ---
(no throw)                         291
INTERNAL:IllegalArgumentException  48
INTERNAL:IllegalStateException     34
INTERNAL:NullPointerException      10
INTERNAL:SQLException              23
INTERNAL:StackOverflowError        12
NOTIMPL                            35
TYPED:MAPPING                      12
TYPED:MODEL                        40
TYPED:PARSE                       214
TYPED:RESOLVE                      24
TYPED:TYPE                        120
```

**Internal escapes per entry point** (exact counts):

```
Compiler.compile             3     QueryService.exec/CSV        7
Compiler.compileModel        1     QueryService.execute         9
Compiler.compileQuery        4     QueryService.execute(m)     13
Compiler.execute             8     QueryService.execute(rt)     4
Compiler.executeStreaming    5     QueryService.executeSql     15
Compiler.executeWire/CSV     7     QueryService.stream          5
Compiler.executeWire/JSON    5     QueryService.wireJson       10
Compiler.parseModel          1     QueryService.wireJson(m)    13
Compiler.parseQuery          1     QueryService.wireJson(rt)    4
Compiler.plan                4     SerializerRegistry.get       2
Compiler.planStreaming       3     DiagramService.extract       1
ModelOrchestrator.new        1     PureLsp.handleMessage        1
```

The distinct **causes**, each reachable from user input a caller can plausibly write:

| Trigger | Escapes as | Reached from |
|---|---|---|
| `\|%25:00:00` | `IllegalStateException: time literal '%25:00:00' is out of range` | 12 entry points |
| `\|%-1:00:00` | `IllegalStateException: time literal '%-1:00:00' is out of range` | 12 entry points |
| `\|'42abc'->cast(@Integer)` | `java.sql.SQLException: Conversion Error: Could not convert string '42abc' to INT64` | 8 |
| `\|[1,2,3]->at(99)` | `java.sql.SQLException: Invalid Input Error: … offset 99 where the collection is of size 3` | 8 |
| `project(~[])` | `IllegalStateException: result has 5 columns but the typed schema has 0 ? plan/schema mismatch` / `IllegalStateException: csv wire over a zero-column relation` | 5 |
| `…->groupBy(~[], ~[])` | same two | 5 |
| `…->toOne()` over 3 rows | `IllegalStateException: toOne() over a relation returned 3 row(s)` | 3 |
| 400-deep `(1+1)` nesting | `java.lang.StackOverflowError` (an **`Error`**, not an `Exception`) | 12 |
| any bad/absent runtime name via `QueryService` | `IllegalArgumentException: Runtime not found: …` (`ConnectionResolver.java:57,61,66,79`) | 45 |
| bad SQL through `executeSql` | raw `java.sql.SQLException` | 15 |
| `SerializerRegistry.get(null)` | `NullPointerException: Cannot invoke "Object.hashCode()" because "key" is null` | 1 |
| `PureLspServer.handleMessage(null)` | `NullPointerException: Cannot invoke "String.length()" because "this.src" is null` | 1 |

Full 127-line list is in `/tmp/a31/escapes.txt` and reproduced in the appendix at the end of this file.

**Why it matters:** the whole point of `error/LegendCompileException` (`LegendCompileException.java:3-19`)
is that a caller can tell "your input" from "our bug" from "not built" *by type*. On 127 of 863 probes the
caller gets `IllegalStateException` / `IllegalArgumentException` / `NPE` / `SQLException` / `StackOverflowError`,
which per that same javadoc means "genuine internal invariant violations (our bugs)" — for inputs that are
plainly the user's.

---

### [SILENT FALLBACK / DEAD] F3 — `Phase.RENDER` and `Phase.EXECUTE` have **zero** throw sites: no execution failure can ever carry a phase

`LegendCompileException.java:30` declares nine phases:

```java
public enum Phase { PARSE, RESOLVE, NORMALIZE, MODEL, TYPE, MAPPING, LOWER, RENDER, EXECUTE }
```

Exhaustive grep over `core/src/main/java` for every constant, excluding the declaration file:

```
Phase.PARSE     : 2 refs   (ParseException only)
Phase.RESOLVE   : 2 refs
Phase.NORMALIZE : 74 refs
Phase.MODEL     : 19 refs
Phase.TYPE      : 3 refs   (TypeInferenceException + Windows.java:184)
Phase.MAPPING   : 2 refs
Phase.LOWER     : 3 refs
Phase.RENDER    : 0 refs   <-- unreachable
Phase.EXECUTE   : 0 refs   <-- unreachable
```

There are exactly five concrete `LegendCompileException` subtypes: `parser/ParseException`(PARSE),
`error/ResolutionException`(RESOLVE), `error/ModelException`(NORMALIZE|MODEL),
`error/MappingResolutionException`(MAPPING), `compiler/spec/TypeInferenceException`(TYPE).
**Nothing constructs a `RENDER` or `EXECUTE` exception.** Consequence: every Phase-K failure — all 23
`SQLException`s in F2 — reaches the caller as a raw JDBC exception, and every Phase-J failure likewise.
`Phase.EXECUTE` is a promise the code cannot keep.

---

### [CRASH] F4 — `StackOverflowError` on `/engine/execute` closes the HTTP connection with **zero bytes**; the same input on `/engine/diagram` returns `{"error":""}`

`LegendHttpServer.java:173` catches `Exception`, not `Throwable`:

```java
} catch (Exception e) {
    e.printStackTrace();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", false);
    response.put("error", e.getMessage());
    sendResponse(exchange, 200, Json.toCompact(response));
}
```

`StackOverflowError` is an `Error`. It escapes the handler; the exchange is never written.

**Repro** (`/tmp/a31/Http.java`, `/tmp/a31/Http2.java`): POST `{"code": <fixture> + "\n|(((…1…+1)+1)…\n"}`
(600-deep parenthesis nesting) to `/engine/execute`. **Actual output:**

```
== StackOverflow : |(((((((((((...
      TRANSPORT-FAILURE java.io.IOException: HTTP/1.1 header parser received no bytes
```

The same class of input against `/engine/diagram` (which *does* catch `Throwable`, line 382) gives:

```
== /engine/diagram with a StackOverflow-inducing model (handler catches Throwable)
DiagramHandler error: java.lang.StackOverflowError
   HTTP 500  BODY: {"error":""}
```

`{"error":""}` because `StackOverflowError.getMessage()` is `null` and `Json.escape(null)` returns `""`
(`Json.java:635-636`). The server process survives (verified: a subsequent `/engine/sql` returned 200),
but the caller gets either nothing at all or an empty reason. Two endpoints on the same server, two
different failure behaviours for the same input class.

---

### [INFORMATION LOSS] F5 — CSV wire: NULL ≡ empty string, Integer ≡ String-of-digits, Boolean ≡ String, StrictDate/DateTime ≡ String

`/tmp/a31/Wire.java` runs the **same** query through `Compiler.executeWire(..., Format.JSON, w)` and
`(..., Format.CSV, w)` over a DuckDB table containing a real SQL `NULL` and a real empty string.

**(a) NULL vs empty string.** Static types differ: `nuls: STRING[0..1]`, `empt: STRING[1]`.

```
  [JSON] bytes  =[{"nuls":null,"empt":""},{"nuls":"notnull","empt":""},{"nuls":null,"empt":""}]
  [CSV]  bytes  =nuls,empt\r\n,\r\nnotnull,\r\n,\r\n
```

CSV row 1 is `,` — two zero-length fields. A consumer **cannot** distinguish the absent value from the
present empty one. JSON can (`null` vs `""`). RFC 4180 offers `""` vs nothing for exactly this, and the
renderer uses neither.

**(b) Integer vs String-of-digits.** `i: INTEGER[1]`, `numstr: STRING[1]`.

```
  [JSON] bytes  =[{"i":42,"numstr":"42"},{"i":7,"numstr":"x"},{"i":0,"numstr":"007"}]
  [CSV]  bytes  =i,numstr\r\n42,42\r\n7,x\r\n0,007\r\n
```

CSV row 1 is `42,42` — indistinguishable. JSON distinguishes by quoting.

**(c) Boolean vs String.** `b: BOOLEAN[1]`, `bs: STRING[1]`:

```
  [JSON] bytes  =[{"b":true,"bs":"true"},{"b":false,"bs":"false"},{"b":true,"bs":"true"}]
  [CSV]  bytes  =b,bs\r\ntrue,true\r\nfalse,false\r\ntrue,true\r\n
```

**(d) StrictDate / DateTime vs String** — lost in **both** formats:

```
  [JSON] bytes  =[{"dt":"2020-01-02","ts":"2020-01-02 03:04:05"}, …]
  [CSV]  bytes  =dt,ts\r\n2020-01-02,2020-01-02 03:04:05\r\n …
```

JSON renders a `STRICT_DATE` and a `DATE_TIME` as plain quoted strings, byte-identical to a `STRING`
column holding that text. Also note the DateTime wire form is `2020-01-02 03:04:05` — a space separator,
not ISO-8601 `T`, so it is not even self-describing to a consumer that tries to sniff it.

**(e) Scalar roots.** Two different static types, byte-identical CSV:

```
  scalar root Integer   |42     [G] INTEGER[1]   [CSV] value\r\n42\r\n
  scalar root String    |'42'   [G] STRING[1]    [CSV] value\r\n42\r\n
```

The CSV format carries **no** type channel at all: no header type row, no quoting discipline that
separates strings from numbers (`hello` and `42` are both bare). `executeWire` returns column *names*
only. So for CSV the caller's only type information is whatever it already knew.

---

### [INFORMATION LOSS] F6 — JSON wire: `Decimal` and `Float` are byte-identical

The task's specific question. **Actual output** (`/tmp/a31/Wire.java`):

```
==================== scalar root Float 42.0
  QUERY: |42.0
  [G] static type: ExprType[type=FLOAT, multiplicity=Bounded[lower=1, upper=1]]
  [JSON] bytes  =[{"value":42.0}]
  [CSV]  bytes  =value\r\n42.0\r\n

==================== scalar root Decimal 42.0d
  QUERY: |42.0d
  [G] static type: ExprType[type=PrecisionDecimal[precision=38, scale=1], multiplicity=Bounded[lower=1, upper=1]]
  [JSON] bytes  =[{"value":42.0}]
  [CSV]  bytes  =value\r\n42.0\r\n
```

`FLOAT` vs `PrecisionDecimal[precision=38, scale=1]` — two distinct static types, **identical wire bytes**
in both formats. The column case only *appears* to differ (`{"dec":1.500000,"f":1.5}`) because the DuckDB
column was declared `DECIMAL(20,6)` and prints its declared scale; that is a coincidence of the physical
schema, not a type tag. A `DECIMAL(20,1)` column holding `1.5` renders `1.5`, identical to the DOUBLE.
JSON numbers have no decimal/binary distinction and nothing in the envelope supplies one — `WireData`
carries `columns: List<String>` (names) and nothing else.

---

### [INFORMATION LOSS] F7 — GRAPH results (a bare `Class.all()` — the commonest query shape) carry **no** per-field type information on any surface, and `executeWire` reports `columns = []`

`Compiler.java:421-431`:

```java
if (shape == com.legend.exec.ResultShape.GRAPH) {
    if (format != com.legend.lowering.WireRender.Format.JSON) {
        throw new com.legend.error.NotImplementedException("graph results have no CSV wire");
    }
    var r = com.legend.exec.Executor.execute(...);
    out.write(r instanceof com.legend.exec.ExecutionResult.Graph g && g.json() != null ? g.json() : "[]");
    return java.util.List.of();
}
```

**Actual output** (`/tmp/a31/Graph.java`):

```
QUERY: model::Person.all()
[G] ExprType[type=ClassType[fqn=model::Person], multiplicity=Bounded[lower=0, upper=null]]
[plan shape] GRAPH
[executeWire JSON] columns = []
[executeWire JSON] bytes   = [{"firstName":"John","lastName":"Smith","age":30}, …]
[executeWire CSV] THREW com.legend.error.NotImplementedException: graph results have no CSV wire
[typed ExecutionResult] Graph[json=[…], returnType=ClassType[fqn=model::Person]]
```

Three fields in the payload, `columns = []`. `Compiler.executeWire`'s javadoc (`Compiler.java:405-410`)
promises *"the typed COLUMN NAMES (a plan fact — the response envelope's columns, correct even for a
zero-row result)"* — for GRAPH it silently returns the empty list instead. So `/engine/execute` answers
`"columns":[]` for `Class.all()`, and `ExecutionResult.Graph` gives only `ClassType[fqn=model::Person]`
plus untyped JSON text. The `? … : "[]"` on line 428-429 is additionally a silent fallback: a null graph JSON
becomes an empty result rather than an error.

---

### [INCONSISTENCY] F8 — `project(~[])` : HTTP says success, `Compiler.execute` throws `IllegalStateException`, the CSV wire throws a *different* `IllegalStateException`

Same query, three public entry points, three outcomes.

```
Compiler.execute      | project-empty-cols | IllegalStateException | result has 5 columns but the typed schema has 0 ? plan/schema mismatch
Compiler.executeWire/CSV | project-empty-cols | IllegalStateException | csv wire over a zero-column relation
QueryService.exec/CSV | project-empty-cols | IllegalStateException | csv wire over a zero-column relation
```

but over HTTP (`/tmp/a31/http.out`):

```
== ICE project(~[]) : model::Person.all()->project(~[])
      HTTP 200 ct=application/json
      BODY: {"success":true,"data":[{},{},{}],"columns":[],"rowCount":3}
```

The JSON wire path happily returns three **empty objects** and `rowCount: 3`. `->groupBy(~[], ~[])`
behaves identically. A caller choosing between `QueryService.execute` (typed) and
`QueryService.executeWireJson` (the one `/engine/execute` uses) gets a crash from one and a
silently-degenerate success from the other.

---

### [INFORMATION LOSS + LEAK] F9 — the HTTP error envelope drops `Phase` entirely, returns **HTTP 200 for every failure**, and leaks internal record dumps, internal FQNs, rendered SQL, and DB catalog internals

`LegendHttpServer.java:173-179` builds the entire error contract from `e.getMessage()`. The exception
class is discarded, the `Phase` is discarded, `element()` is discarded, and the status is 200.

**Actual output** (`/tmp/a31/http.out`), all HTTP 200:

```
{"success":false,"error":"scalar lowering not yet implemented for TypedCTime"}                       <- NotImplementedException
{"success":false,"error":"time literal '%25:00:00' is out of range"}                                  <- IllegalStateException (ICE)
{"success":false,"error":"[1:30] expected expression, got end of input"}                              <- ParseException (PARSE)
{"success":false,"error":"'nope::Nope' is not a known class, mapping, runtime, connection, or database"} <- ResolutionException (RESOLVE)
```

Four different taxonomic categories — user error, unbuilt feature, internal bug — arrive as the same
shape with the same status code. The taxonomy that `LegendCompileException` exists to provide does not
survive the HTTP boundary at all.

**Leaks to an unauthenticated caller:**

1. **Internal Java record dumps.** `|1 + 'a'` returns a 1063-byte body containing
   `ExprType[type=INTEGER, multiplicity=Bounded[lower=1, upper=1]]` and five
   `[TypedParameter[name=left, type=DECIMAL, multiplicity=Bounded[lower=1, upper=1]], …]` — the compiler's
   internal overload table, verbatim.
2. **Internal platform FQNs**: `meta::pure::functions::math::plus`,
   `meta::pure::functions::relation::sort`, `meta::pure::functions::collection::at`.
3. **The rendered SQL and the physical schema**:
   `"Conversion Error: Could not convert string '42abc' to INT64\n\nLINE 5:     SELECT CAST('42abc' AS BIGINT) AS value"`.
4. **Database catalog internals**: `Catalog Error: Table with name nosuch does not exist! Did you mean "pg_sequences"?`
5. **Internal class names and the classloader**, via `/lsp` (`PureLspServer.java:108` does an unchecked
   `(Json.Obj) contentChanges.items().get(0)`):

```
POST /lsp {"…","contentChanges":["oops"]}
HTTP 200 BODY: {"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"Internal error:
  class com.legend.server.Json$Str cannot be cast to class com.legend.server.Json$Obj
  (com.legend.server.Json$Str and com.legend.server.Json$Obj are in unnamed module of loader 'app')"}}
```

6. **Filesystem paths** appear verbatim (`IO Error: Cannot open file "/proc/legend-lite-secret/db.duckdb":
   No such file or directory`). In this route the path originates in the caller's own model text, so this
   is a leak only across tenants; I found **no** server-side path escaping to a caller.
   Note however that `LegendHttpServer.java:150-152` and `:209` print the caller's full query and model
   length to the server's stdout, and `:174`/`:246` `printStackTrace()` full internal stacks to stderr.

7. `/engine/diagram` returns **HTTP 500** for a user's malformed model while `/engine/execute` returns
   **HTTP 200** for the same class of error — the status code is not a usable signal either way.

---

### [INFORMATION LOSS] F10 — the IDE/LSP surface exposes **no type information at all**: no hover types, no completions, and diagnostics only for `PARSE`

`ide/` (622 lines) contains `ModelIndex`, `ModelIndexer`, `ModelOrchestrator`, `package-info`. There is no
hover, no completion, no signature help, and **no `Type` anywhere** — the deepest it goes is
`protocol.TypeExpression` (the name as written) via `ElementParser.parseSingle`. `package-info.java:5-7`
concedes the package is *"Currently unused by the batch pipeline"*; grep confirms `com.legend.ide` has no
importer in main outside itself.

The only diagnostics surface is `server/PureLspServer`, and `rebuildAndPublishAll` (`PureLspServer.java:137-165`)
runs **only** `Compiler.parseModel` — phase A/B. It never calls `compileModel` (D–F) or `compileAllBodies` (G).

**Repro** (`/tmp/a31/Ide.java` §3). **Actual output:**

```
unknown property type (MODEL)    compiler=ModelException: [1:1] Unknown type: 'NoSuchType' is not a known primitive, class, or enum
                                 LSP=*** CLEAN (no diagnostic) ***
body type error (TYPE)           compiler=(compileModel OK)
                                 LSP=*** CLEAN (no diagnostic) ***
unknown supertype (MODEL)        compiler=(compileModel OK)
                                 LSP=*** CLEAN (no diagnostic) ***
mapping unknown class (NORM)     compiler=ModelException: Unknown type: 'no::Such' is not a known primitive, class, or enum
                                 LSP=*** CLEAN (no diagnostic) ***
runtime unknown mapping          compiler=(compileModel OK)
                                 LSP=*** CLEAN (no diagnostic) ***
```

5/5 non-parse errors are invisible in the editor, two of which the compiler itself rejects outright. Of the
five reachable `Phase` values, the IDE surfaces exactly one.

---

### [INCONSISTENCY] F11 — LSP diagnostic positions are **re-derived by string-scraping the message** and discard `ParseException.line()/column()`, which the exception carries as typed fields

`PureLspServer.java:170-227` takes `e.getMessage()`, searches it for the literal substring `"line "`, then
falls back to searching the source text for whatever is inside the first pair of single quotes, and
fabricates the end column as `character + 20` (line 218). `ParseException` (`ParseException.java:31-37`)
exposes `line()` and `column()` directly; they are never consulted.

**Repro** (`/tmp/a31/Gaps.java` §A, one fresh server per document). **Actual output:**

```
--- Class a::A { p: String[1]; }\nClass a::B { q: String[1]; }\nClass a::C {
    TRUTH: ParseException.line()=3  .column()=13
    LSP  : …"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":20}}…

--- Class a::A { p: String[1]; }\n\n\n\nClass a::Bad { p: Nope }
    TRUTH: ParseException.line()=5  .column()=24
    LSP  : …"range":{"start":{"line":0,"character":27},"end":{"line":0,"character":47}}…

--- Class a::A {\n  p: String[1]\n}
    TRUTH: ParseException.line()=3  .column()=1
    LSP  : …"range":{"start":{"line":2,"character":0},"end":{"line":2,"character":20}}…

--- Class a::A { p: String[1]; }\nClass a::Zz { q: NoT[1]; ;;; }
    TRUTH: ParseException.line()=2  .column()=26
    LSP  : …"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":20}}…
```

**3 of 4 point at the wrong line entirely**; 4 of 4 have a fabricated 20-character span. Case 2 is the
scraper's failure mode in full view: the message is
`[5:24] expected BRACKET_OPEN but found BRACE_CLOSE ('}')`; there is no `"line "` in it, so the quote scan
extracts `}` and finds the *first* `}` in the file — on line 1, column 27 — and squiggles there.

Minor, same file: `documents` is a plain `HashMap` (`PureLspServer.java:23`) and `rebuildAndPublishAll`
iterates it, so a `didOpen` publishes for every open document in hash order:

```
  didOpen file:///1.pure -> notifications for [file:///1.pure]
  didOpen file:///2.pure -> notifications for [file:///2.pure, file:///1.pure]
  didOpen file:///3.pure -> notifications for [file:///2.pure, file:///1.pure, file:///3.pure]
  didOpen file:///4.pure -> notifications for [file:///2.pure, file:///4.pure, file:///1.pure, file:///3.pure]
```

The just-opened document is not first, and the order is not source order.

---

### [INCONSISTENCY] F12 — `ide/ModelIndexer` and `parser/ElementParser` disagree on 4 of 20 constructs, falsifying the stated parity invariant

`ModelIndexer.java:44-46` claims: *"Property-based parity tests assert
`shallow_scan(src).fqns() == eager_parse(src).elements().map(fqn)`."*

**Repro** (`/tmp/a31/Ide.java` §1), 20 constructs. **Actual output:**

```
duplicate-fqn      DIVERGE
    eager  Compiler.parseModel     : [a::X, a::X]
    shallow ide index.fqns()       : THREW ParseException: [2:1] duplicate top-level element 'a::X' (first declared as CLASS)
stereotype         DIVERGE
    eager  Compiler.parseModel     : THREW ParseException: [1:10] expected DOT but found GREATER_THAN ('>')
    shallow ide index.fqns()       : [a::X]
native-class       DIVERGE
    eager  Compiler.parseModel     : THREW ParseException: [1:1] Unsupported syntax
    shallow ide index.fqns()       : [a::X]
measure            DIVERGE
    eager  Compiler.parseModel     : [a::M]
    shallow ide index.fqns()       : THREW ParseException: [1:1] unsupported top-level keyword: VALID_STRING ('Measure')
divergences: 4 / 20
```

Both directions occur:
* `Measure` — the compiler parses it; `ModelOrchestrator` **cannot open the file at all**.
* `Class <<s>> a::X {…}` and `native Class a::X {}` — the IDE index lists an element the compiler refuses.
  `ModelIndexer.java:85-91` has dedicated `NATIVE` handling for a construct `ElementParser` rejects with
  `Unsupported syntax` — dead, divergent logic.
* `duplicate-fqn` — the shallow scan enforces FQN uniqueness (`ModelIndexer.java:98-104`); the **product
  front door** `Compiler.parseModel` returns `[a::X, a::X]`, two elements with one FQN, no complaint.

---

### [SILENT FALLBACK] F13 — `DiagramService.resolve` invents an inheritance edge the compiler does not have, and picks the target **arbitrarily**

`DiagramService.java:207-230` resolves a written type name against the model by (1) exact FQN, (2) the
referencing element's own package, (3) *"any model class whose simple name matches"* iterating a
`java.util.HashSet<String>`, (4) `return name` unresolved.

**Repro** (`/tmp/a31/Amb.java`): `Class c::Use extends Foo {}` with two `Foo` classes in different
packages, four package-name variants. **Actual output:**

```
=== a/b
   DIAGRAM  : [GeneralisationInfo[child=c::Use, parent=b::Foo]]
   COMPILER : TypedClass[qualifiedName=c::Use, typeParameters=[], superClassFqns=[Foo], properties=[], …]
   Q-TYPE   : TypeInferenceException: class c::Use has no property 'v'
=== p/q
   DIAGRAM  : [GeneralisationInfo[child=c::Use, parent=q::Foo]]
=== zz/aa
   DIAGRAM  : [GeneralisationInfo[child=c::Use, parent=zz::Foo]]
=== m1/m2
   DIAGRAM  : [GeneralisationInfo[child=c::Use, parent=m1::Foo]]
```

Four runs, four different winners, decided by `String.hashCode` bucket order of the FQNs. Meanwhile the
compiler holds `superClassFqns=[Foo]` — a bare, unresolved name that resolves to nothing, so `c::Use`
inherits no properties. The diagram endpoint therefore publishes a typed inheritance edge that **does not
exist in the compiled model**, and names a specific class arbitrarily. The repo's rule is "NO FALLBACKS.
NO DEFAULTING."; steps (2), (3) and (4) are three stacked fallbacks.

(Secondary, same probe: the compiler *silently accepting* `superClassFqns=[Foo]` unresolved is itself a
hole, but it lives in `NameResolver`/phase F — flagging it for whoever owns that area.)

---

### [INFORMATION LOSS] F14 — `DiagramService.PropertyInfo.type` is a **simple name**, so distinct types collapse

`DiagramService.java:84-86` builds the wire type as `simpleName(typeName(p.type()))`, where `typeName`
returns the name **as written** and `simpleName` then strips the package. **Actual output** over HTTP:

```
{"code": "Class a::Foo { v: String[1]; }\nClass b::Foo { v: Integer[1]; }\nClass c::Use { f: Foo[1]; }"}
-> {"classes":[{"id":"a::Foo",…},{"id":"b::Foo",…},
     {"id":"c::Use",…,"properties":[{"name":"f","type":"Foo","multiplicity":"[1]"}]}]}
```

`c::Use.f` is reported as `"Foo"` — the consumer cannot tell which `Foo`, and there is no other field
carrying the resolution (`resolve()` is applied only to supertypes and association ends, never to
`PropertyInfo`). Type *parameters* collapse the same way: `Class a::Pair<K,V>` yields
`{"name":"k","type":"K"}` — indistinguishable from a property whose type is a class named `K`.
`DiagramService.java:185-191` also has a `default -> t.toString()` arm that would emit a raw Java record
dump for any other `TypeExpression` variant.

---

### [INFORMATION LOSS] F15 — `QueryService.executeSql` runs the SQL and **discards the ResultSet**, returning a zero-column relation type

`QueryService.java:138-150`:

```java
try (java.sql.Statement stmt = conn.createStatement()) {
    stmt.execute(sql);
    return new ExecutionResult.Tabular(List.of(), List.of(),
            new com.legend.compiler.element.type.Type.RelationType(List.of()));
}
```

**Actual output** (`/tmp/a31/Gaps.java` §D):

```
executeSql("SELECT 1 AS one, 'x' AS two") = Tabular[columns=[], rows=[], returnType=RelationType[columns=[], dynamicColumns=[]]]
```

The class javadoc (`QueryService.java:47-51`) advertises this as the *"Raw-SQL escape hatch — runs
arbitrary SQL against the Runtime's connection"* returning an `ExecutionResult`. It returns a
**`RelationType` claiming zero columns** for a statement that produced two. Over HTTP the endpoint reports
`{"success":true,"message":"SQL executed successfully"}` for a `SELECT` whose rows were thrown away.

---

### [UNSOUND — latent] F16 — `cache/ContentStore`'s key is content but not *artifact type*: an unchecked cast that can launder a wrong-typed value

`ContentStore.java:62-68`:

```java
@SuppressWarnings("unchecked")
public synchronized <T> T getOrCompute(Hash key, Supplier<? extends T> compute) { …
    return (T) store.computeIfAbsent(key, k -> …);
}
```

The javadoc concedes *"the caller is responsible for using a key that uniquely determines this type"*
(line 60-61) while the class header claims content addressing means the cache **"cannot desync"** (line 18).
Both cannot hold: `Hash.ofUtf8(modelSource)` is a perfectly legal key for `compileModel(src)` *and* for
`parseModel(src)`.

**Repro** (`/tmp/a31/Cache2.java` §1). **Actual output:**

```
  caller A memoized: I am a compiled MODEL
  caller B THREW java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Integer
  laundered through Object: I am a compiled MODEL (java.lang.String)
```

The third line is the dangerous one: when the target type is a supertype (`Object`, an interface, an
unbounded generic) **no cast fires**, and the caller silently receives the other artifact. Severity is
latent-only because — see F18 — `ContentStore` currently has zero callers.

---

### [CRASH / UNSOUND] F17 — `spi/ElementSink.accept` validates nothing: a third-party grammar can inject duplicate, malformed, and null FQNs, and `compileModel` dies with a raw NPE

`spi/ElementSink.java:10-14` is the whole contract:

```java
public interface ElementSink { void accept(String fqn, String protocolJson); }
```

`parser/OverlayElementSink.java:39-43` turns each call straight into
`new OpaqueElementDefinition(qualifiedName, sectionName, protocolJson)` — and
`model/OpaqueElementDefinition.java:15-17` is a bare record with **no compact constructor and no
`requireNonNull`**. No FQN syntax check, no duplicate check, no JSON well-formedness check, no null check.

**Repro** (`/tmp/a31/spi/`): a `SectionGrammar` registered by a real `META-INF/services` file, claiming
the built-in `Diagram` section, emitting `model::Person` twice, `!! not an fqn !!`, and `(null, null)`.
**Actual output:**

```
overlay claims section: 'Diagram'
registry lookup('Diagram') = Evil
registry size = 26                       <- was 27; the built-in was OVERWRITTEN

--- Compiler.parseModel ---
  ClassDefinition  fqn=model::Person
  ConnectionDefinition  fqn=st::C
  OpaqueElementDefinition  fqn=model::Person
  OpaqueElementDefinition  fqn=model::Person
  OpaqueElementDefinition  fqn=!! not an fqn !!
  OpaqueElementDefinition  fqn=null
  unclaimedSections = []

--- Compiler.compileModel ---
  THREW java.lang.NullPointerException: Cannot invoke "Object.hashCode()" because "pk" is null

--- Compiler.compileQuery(model::Person.all()->project(~[a:p|$p.age])) ---
  THREW java.lang.NullPointerException: Cannot invoke "Object.hashCode()" because "pk" is null
```

Three elements now share the FQN `model::Person` — the real `ClassDefinition` and two opaque carriers —
and `ParsedModel` accepts it. A `null` FQN reaches the model builder and detonates as an
unclassified NPE. Two type invariants (FQN uniqueness; FQN non-null) are breakable from the SPI with
four lines of third-party code.

Note also what **vanished**: the model's real `###Diagram` content (`Diagram model::D {}`) is gone —
in the control run it appears as `GenericSectionElementDefinition fqn=model::D`. Shadowing a built-in
raw grammar silently deletes that section's elements, which is exactly the failure mode
`OverlayElementSink.java:18-23` warns about.

---

### [INCONSISTENCY] F18 — SPI registry / lexer desync: an overlay can shadow a lexable built-in and the parser silently ignores it

`SectionGrammarRegistry.java:110-116` registers ServiceLoader overlays **last** with `map.put`, so an
overlay wins over any built-in — the javadoc calls this deliberate (`SectionGrammar.java:14-16`).
But `ElementParser` only routes to the overlay for sections the **lexer** raw-skipped
(`ElementParser.java:356-406`), and the lexer's lexable-section list is independent of the registry.

**Actual output** across five claims (`/tmp/a31/spi/`):

```
claim=Pure        registry lookup('Pure') = Evil, size 26   -> parseModel still yields ClassDefinition model::Person
claim=Relational  registry lookup = Evil,        size 26   -> unchanged
claim=Connection  registry lookup = Evil,        size 26   -> unchanged (ConnectionDefinition st::C still parsed)
claim=Diagram     registry lookup = Evil,        size 26   -> overlay FIRES, see F17
claim=NOSUCH      registry lookup = Evil,        size 27   -> overlay registered, never reached
```

`ElementParser.java:407-416` throws loudly on the *reverse* desync ("section has a lexable grammar
registered but was raw-skipped by the lexer — lexer/registry drift"). The forward direction — a
**non-lexable** grammar registered for a section the lexer **does** lex — has no arm at all and is
silently ignored. The result is a registry whose `lookup(...).lexable()` answer is authoritative on one
path and dead on the other.

---

### [DEAD] F19 — `server/serial/*` is an extension seam nothing consults; `ResultSerializer` has no serialize method

`ResultSerializer` (30 ln) declares `formatId()`, `contentType()`, `supportsStreaming()` and **nothing that
takes a result**. `JsonSerializer` (33 ln) and `CsvSerializer` (32 ln) are singleton shells with those three
methods. `SerializerRegistry` (74 ln) is a public static `ConcurrentHashMap`.

Exhaustive grep over `core/src/main` + `nlq` + `experiments` + `tools`: **`SerializerRegistry`,
`ResultSerializer`, `JsonSerializer` and `CsvSerializer` have zero references outside
`server/serial/` itself.** The only other mentions are in `core/src/test` and a javadoc.

`QueryService.java:96-101` — the actual format dispatch — bypasses the registry entirely:

```java
com.legend.Compiler.executeWire(pureSource, query, runtimeName, connection,
        format == OutputFormat.CSV
                ? com.legend.lowering.WireRender.Format.CSV
                : com.legend.lowering.WireRender.Format.JSON,
        writer);
```

Consequences:
* **DOC-LIE.** `OutputFormat.java:13-14` claims `id()` is *"used by `SerializerRegistry` for dispatch"*.
  Nothing dispatches on it; `OutputFormat.id()` and `contentType()` have no main-source caller.
* **Broken extension contract.** `SerializerRegistry.register(...)` is public static and its javadoc
  offers *"Arrow, Protobuf, Parquet … via explicit calls or ServiceLoader"*. A third party can register
  one and it will never be invoked — there is nothing to invoke. There is also no ServiceLoader pass.
* **Silent default.** `format == CSV ? CSV : JSON` maps every non-CSV value to JSON. Harmless with two
  constants, a silent-coercion trap the moment a third is added.
* `register()` null-checks nothing: `register(s)` with a null `formatId()` NPEs inside `ConcurrentHashMap`.
* `SerializerRegistry.get(null)` escapes as `NullPointerException: Cannot invoke "Object.hashCode()"
  because "key" is null` rather than the documented `IllegalArgumentException` (measured, F2).

---

### [DEAD] F20 — `cache/ContentStore` — "the one sanctioned cache in `core/`" — has zero callers

`ContentStore.java:8-27` calls itself *"The one sanctioned cache in `core/`"* and says
*"`ArchitectureTest` (Invariant 3) funnels all caching here so this property holds module-wide."*

Exhaustive grep of `core/src/main/java` for `ContentStore`, `HandleStore`, `Hash.of`, `Hash.combine`
outside `com/legend/cache/`:

```
com/legend/server/ConnectionResolver.java:9,20,33,89,90
```

That is the entire list. `ContentStore` is never constructed or called in main source. The *only* live
cache in the module is `HandleStore`, at one call site (F1). Meanwhile real caches do exist outside the
package and are **name-keyed**, which is precisely what `Hash`'s javadoc (`Hash.java:20-25`) forbids:
`SerializerRegistry.SERIALIZERS` (keyed on `formatId()` `String`),
`SectionGrammarRegistry.REGISTRY` (keyed on section-name `String`),
`ModelOrchestrator.cache` (keyed on FQN `String`, `ModelOrchestrator.java:62`),
`PureLspServer.documents` (keyed on URI `String`).

---

### [RESOURCE] F21 — `HandleStore` never evicts, so caller-supplied connection text pins unbounded live JDBC handles

`HandleStore.java:26-28` states the design: *"No eviction: evicting an in-memory connection would silently
drop its tables. Entries live for the process."* On `LegendHttpServer`, the connection definition is part
of the caller-supplied `code` field, so an unauthenticated caller controls the key.

**Repro** (`/tmp/a31/Cache2.java` §3): 60 models differing only in the connection FQN (`st::C0`…`st::C59`),
each through `QueryService.executeSql`. **Actual output:**

```
  opened 60 distinct in-memory databases via /engine/sql-equivalent
  heap used before=1,720 KB  after=3,048 KB  delta=1,327 KB
  still-live databases after all 60 opens: 60 / 60
  T_0 not visible from db#59 (isolated) -> 60 separate live DBs
```

60/60 still live and independent after all opens; nothing is ever released. There is no bound, no TTL, and
no `close()` path anywhere in `HandleStore`.

---

### [DOC-LIE] F22 — `AGENTS.md` "every user-visible error carries one of these eight" is wrong on both the count and the membership

`AGENTS.md:92-94`:

> **A second, user-facing phase vocabulary exists** and does not use letters:
> `error/LegendCompileException.Phase` = `PARSE, RESOLVE, NORMALIZE, MODEL, TYPE, MAPPING, LOWER, EXECUTE`.
> Note there is **no `RENDER`**. Every user-visible error carries one of these eight.

Three falsifications:
1. The enum has **nine** members and `RENDER` **is** one of them (`LegendCompileException.java:30`;
   confirmed at runtime: `declared count = 9`, with `RENDER` printed).
2. `EXECUTE` — listed as reachable — has **zero** throw sites (F3).
3. "Every user-visible error carries one of these" is falsified 127 times over in F2, and 0 of 863 probes
   produced `NORMALIZE`, `LOWER`, `RENDER` or `EXECUTE`.

---

### [LOW] F23 — `Hash.combine`'s codomain overlaps `Hash.ofUtf8`'s

`Hash.java:67-74` implements `combine` as `ofUtf8(concat of the parts' hex)`. Therefore
`combine(h) == ofUtf8(h.hex())` for every `h`, and `combine()` (no parts) `== ofUtf8("")`.

**Actual output** (`/tmp/a31/Final.java`):

```
  h                              = 290f493c44f5d63d06b374d0a5abd292fae38b92cab2fae5efefe1b0e9347f56
  Hash.combine(h)                = e2adf7b42dc252e8bf1f8ef9c100104709692f97970a6e029833a3e880148f3a
  Hash.ofUtf8(h.hex())           = e2adf7b42dc252e8bf1f8ef9c100104709692f97970a6e029833a3e880148f3a
  combine(h) == ofUtf8(h.hex()) ? true
  combine()  == ofUtf8("")      ? true
```

A caller that hashes a 64-lowercase-hex-character *content* string collides with the Merkle identity of the
corresponding single hash. Contrived, and no current caller does it — but it is a genuine
cross-constructor collision in a type whose entire purpose is collision-freedom.
(Verified sound: `combine` **is** order-sensitive and non-associative, as documented —
`combine(a,b) != combine(b,a)` and `combine(a,b,c) != combine(combine(a,b),c)`.)

Separately, `ConnectionResolver.java:126-128` truncates a key to 64 bits for the H2 default arm
(`"jdbc:h2:mem:c_" + contentKey(def).hex().substring(0, 16)`) — a birthday bound of ~2^32 definitions
rather than the 2^128 the full hash provides. Not practically reachable; noted for completeness.

---

### [LOW] F24 — `LegendCompileException.element()` sometimes carries a synthesized internal key, so the driver's position decoration silently drops

`Compiler.java:91-103` decorates a `ModelException` with `[line:col]` by looking up
`parsed.elementOffsets().get(e.element())`, and falls through untouched when the lookup misses.

**Actual output** (`/tmp/a31/Final.java`):

```
  unknown prop type     -> ModelException phase=MODEL element=model::X            msg=[1:1] Unknown type: 'Nope' …
  mapping unknown class -> ModelException phase=MODEL element=a::M$class$no::Such msg=Unknown type: 'no::Such' …
  bad db ref            -> ModelException phase=MODEL element=a::M$class$no::C    msg=Unknown type: 'no::C' …
```

`a::M$class$no::Such` is not a declared element FQN, so the offset lookup misses and the message ships with
**no position at all** — while the first case gets `[1:1]`. Callers relying on `element()` as an FQN (the
javadoc at `LegendCompileException.java:57-64` says it is one) will also fail to resolve it.

---

## VERIFIED SOUND

Things I checked and found correct — coverage evidence.

**Typed-exception coverage that DOES work.** 410 of 863 probes (47.5%) produced a proper
`LegendCompileException` with a live `Phase`: `PARSE` 214, `TYPE` 120, `MODEL` 40, `RESOLVE` 24,
`MAPPING` 12. Malformed **models** are handled well: of 24 bad models × 15 entry points,
`Compiler.compileModel` produced exactly **one** internal escape and that was the `null`-argument case.
The parse and type layers are the strong part of this surface.

**`NotImplementedException` is used honestly.** 35 escapes, all genuinely unbuilt features
(`scalar lowering not yet implemented for TypedCTime` / `TypedSortBy`, `graph results have no CSV wire`,
`session is H2 but connection … declares DuckDB`, `runtime … mixes database types`,
`association property used other than as a navigation head`). None of the 35 was a disguised user error.

**`Compiler.compileQuery` is the trustworthy type surface.** Every `TypedSpec.info()` I inspected carried a
correct, fully-resolved `ExprType` with per-column `Type` + `Multiplicity`, including
`Bounded[lower=0, upper=1]` for the nullable DB column and `PrecisionDecimal[precision=38, scale=1]` for a
decimal literal. The compile-time types in F5/F6 are right; it is the wire that loses them.

**`Compiler.compileAllBodies` honours its "never throws" contract** — it returned a wall map (never threw)
on every one of the 24 bad models.

**JSON wire escaping and CSV RFC-4180 quoting are correct.** A value containing a comma and a double quote
round-trips properly in both: JSON `{"s":"a,b\"c"}`, CSV `"a,b""c"`. CSV uses CRLF and emits a header row
as documented.

**JSON does preserve four type distinctions CSV loses**: `null` vs `""`, number vs string, boolean vs
string, and it keeps integer/float lexical form. Those four are the reason F5 is a *CSV-specific* finding.

**`Hash` is structurally content-only** — there is genuinely no name/id/version/timestamp factory, the
64-hex length invariant is enforced in the compact constructor, and `combine` is order-sensitive and
non-associative exactly as documented (measured, F23).

**`HandleStore.getOrOpen` is correctly atomic.** The `ConcurrentHashMap.compute` form does eliminate the
check-then-act race the javadoc describes, and `CheckedCarrier` correctly rethrows the caller's checked
exception without leaking. The dead-handle predicate works (a closed connection is replaced).

**`ContentStore` LRU mechanics are correct** — access-order `LinkedHashMap` with `removeEldestEntry`, a
`maxEntries < 1` precondition, coarse `synchronized`, and a `requireNonNull` on a null-returning supplier.
Its problem is F16/F20, not its bookkeeping.

**`ModelOrchestrator` memoization is correct** on the 16 of 20 constructs where it agrees with the eager
parser: `resolve(fqn)` returns the same instance on repeat calls, `resolveAll()` preserves source order via
`LinkedHashMap`, and `imports()` is parsed once. `UnknownFqnException` carries the FQN.

**`ModelIndexer`'s balanced-delimiter scan handles the tricky cases** I threw at it: a `}` inside a string
literal in a constraint, `(...)`-bodied `Database`/`Mapping` vs `{...}`-bodied everything else, tagged-value
blocks, nested stereotype markers, section headers, imports, and trailing junk (which it rejects loudly
rather than absorbing).

**`spi` records are minimal and correct**: `SectionSource` is an immutable record with the offsets it
claims; `SectionGrammar.lexable()` defaults to `false` (fail-closed, the safe default). The package is a
genuine leaf. The problem is the *unvalidated* `ElementSink` (F17) and the routing desync (F18), not these
shapes.

**`error/` is a genuine leaf package** (`LegendCompileException.java:21-22` claim verified — it imports
nothing but `com.legend.Nullable` and `java.util.Objects`), `phase` is `requireNonNull`-checked, and
`position()` computes 1-based line/col correctly.

**`ExecutionResult.Tabular` per-column typing is correct end to end.** Across all the `Wire.java` cases the
`Column(name, pureType, multiplicity)` triple matched the `[G]` static type exactly, including
`STRING[0..1]` for the nullable column.

**The HTTP server survives an ICE.** After a `StackOverflowError` killed a request thread, subsequent
requests on all four endpoints returned normally. CORS preflight, 405 on non-POST, 400 on missing `code`/
`sql`/Runtime, and the 404 catch-all all behave as documented.

**`PureLspServer` JSON-RPC framing is correct**: `-32700` on unparseable input, `-32600` on a missing
method, `-32601` on an unknown method with an id (and silence without one), `initialize`/`shutdown`/`exit`
handled, `didClose` clears diagnostics. Its problems are semantic (F10/F11), not protocol-level.

---

## NOT COVERED

* **`server/Json.java` (938 of the 2466 `server/` lines)** — I exercised it only through the endpoints
  (parse, `toCompact`, `escape`, `Writer`, the `Node`/`Obj`/`Arr`/`Str` hierarchy). I did not fuzz the JSON
  parser itself (deep nesting, surrogate pairs, big numbers, duplicate keys). `Json.parse` on a
  script-supplied string is reachable from `/lsp`, `/engine/execute` and `/engine/diagram`, so this is a
  real gap. The one thing I did establish is that `Json.escape(null)` returns `""` (F4).
* **`LegendHttpServer.separateModelAndQuery`** (lines 277-318) — a brace-counting regex split of the
  caller's source. I used well-formed inputs where the `Runtime` block is last. Adversarial inputs (a `{`
  inside a string literal in the Runtime block, two Runtime blocks, a Runtime block nested in a comment)
  would very likely mis-split model from query; I did not test them. Its `RUNTIME_PATTERN`
  (`"Runtime\\s+([\\w:]+)\\s*\\{"`) also matches inside comments and strings.
* **`ConnectionResolver` non-DuckDB arms.** I exercised the DuckDB in-memory path throughout. The SQLite,
  H2 (four spec variants including the 64-bit-truncated default arm), and Postgres arms, and the
  `UsernamePassword` refusal, were read but not run.
* **`DiagramService.getTag` / stereotype extraction** — read, not adversarially tested. Note
  `DiagramService.java:77-78` takes `stereotypes().get(0)` unconditionally after an `isEmpty()` guard,
  which is safe, but only the first stereotype ever reaches the wire.
* **Streaming semantics.** I verified `stream(...)` produces the same failures as `execute(...)` but did
  not check incremental flush behaviour or partial-output-then-error (a mid-stream `SQLException` after
  rows are already on the wire would leave a truncated JSON array — plausible, untested).
* **`Compiler.execute`'s `@Nullable` return.** It is declared nullable and `QueryService.java:62-64` wraps
  it in `Objects.requireNonNull(..., "query produced no result")`, which would surface as an NPE. I probed
  four candidate queries (`|let x = 1;`, `print('x')`, `executeInDb(...)`, a bare `let`) and could not
  produce a null, so I cannot say whether the null branch is reachable.
* **Concurrency.** `ModelOrchestrator` is documented as not thread-safe and `PureLspServer.documents` is a
  bare `HashMap` mutated from `com.sun.net.httpserver` handler threads (`server.setExecutor(null)` uses a
  single dispatch thread, so this may be fine in practice) — I did not run a concurrent probe.
* **`Compiler.parseSources` multi-source merge** (duplicate handling, per-unit import isolation) — called
  once per bad model in the escape sweep, but its duplicate-element and cross-file-scope semantics are not
  audited.
* **ServiceLoader `SectionGrammar` beyond shadowing.** I tested claim-a-name and emit-garbage. I did not
  test a grammar that throws from `parse`, returns null from `name()`, or blocks.
* **Sampling note.** Every number in this report is exhaustive over the corpus stated, not sampled. The
  corpora themselves are hand-built (36 queries, 24 models, 20 IDE constructs, 5 runtime names, 5 SPI
  claims, 11 HTTP cases); they are not exhaustive over the language.

---

## APPENDIX — the full 127 internal-exception escapes

Format: `entry point | corpus case | exception class | message`.
Regenerate with `/home/user/probe/jrun.sh /tmp/a31/Escape.java`.

```
Compiler.compileQuery    | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.plan            | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.planStreaming   | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.compile         | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.execute         | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.executeStreaming | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.executeWire/JSON | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.executeWire/CSV | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
QueryService.execute     | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
QueryService.wireJson    | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
QueryService.exec/CSV    | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
QueryService.stream      | time-literal-overflow    | java.lang.IllegalStateException          | time literal '%25:00:00' is out of range
Compiler.execute         | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 1: SELECT CAST('42abc' AS BIGINT) AS value                ^
Compiler.executeStreaming | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 5:     SELECT CAST('42abc' AS BIGINT) AS value                    ^
Compiler.executeWire/JSON | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 5:     SELECT CAST('42abc' AS BIGINT) AS value                    ^
Compiler.executeWire/CSV | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 16:     SELECT CAST('42abc' AS BIGINT) AS value                     ^
QueryService.execute     | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 1: SELECT CAST('42abc' AS BIGINT) AS value                ^
QueryService.wireJson    | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 5:     SELECT CAST('42abc' AS BIGINT) AS value                    ^
QueryService.exec/CSV    | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 16:     SELECT CAST('42abc' AS BIGINT) AS value                     ^
QueryService.stream      | cast-bad-string          | java.sql.SQLException                    | Conversion Error: Could not convert string '42abc' to INT64  LINE 5:     SELECT CAST('42abc' AS BIGINT) AS value                    ^
Compiler.execute         | project-empty-cols       | java.lang.IllegalStateException          | result has 5 columns but the typed schema has 0 ? plan/schema mismatch
Compiler.executeWire/CSV | project-empty-cols       | java.lang.IllegalStateException          | csv wire over a zero-column relation
QueryService.execute     | project-empty-cols       | java.lang.IllegalStateException          | result has 5 columns but the typed schema has 0 ? plan/schema mismatch
QueryService.wireJson    | project-empty-cols       | java.sql.SQLException                    | Catalog Error: Table with name T_PERSON does not exist! Did you mean "pg_am"?  LINE 6:     FROM T_PERSON AS t0                  ^
QueryService.exec/CSV    | project-empty-cols       | java.lang.IllegalStateException          | csv wire over a zero-column relation
Compiler.execute         | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
Compiler.executeStreaming | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
Compiler.executeWire/JSON | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
Compiler.executeWire/CSV | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
QueryService.execute     | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
QueryService.wireJson    | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
QueryService.exec/CSV    | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
QueryService.stream      | at-out-of-range          | java.sql.SQLException                    | Invalid Input Error: The system is trying to get an element at offset 99 where the collection is of size 3
QueryService.wireJson    | cast-unrelated           | java.sql.SQLException                    | Catalog Error: Table with name T_PERSON does not exist! Did you mean "pg_am"?  LINE 5: ... t0.ID ASC NULLS LAST)), '[]') END AS VARCHAR) AS result FRO...
Compiler.compileQuery    | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.plan            | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.planStreaming   | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.compile         | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.execute         | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.executeStreaming | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.executeWire/JSON | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.executeWire/CSV | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
QueryService.execute     | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
QueryService.wireJson    | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
QueryService.exec/CSV    | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
QueryService.stream      | neg-time                 | java.lang.IllegalStateException          | time literal '%-1:00:00' is out of range
Compiler.compileQuery    | deep-nest                | java.lang.StackOverflowError             | null
Compiler.plan            | deep-nest                | java.lang.StackOverflowError             | null
Compiler.planStreaming   | deep-nest                | java.lang.StackOverflowError             | null
Compiler.compile         | deep-nest                | java.lang.StackOverflowError             | null
Compiler.execute         | deep-nest                | java.lang.StackOverflowError             | null
Compiler.executeStreaming | deep-nest                | java.lang.StackOverflowError             | null
Compiler.executeWire/JSON | deep-nest                | java.lang.StackOverflowError             | null
Compiler.executeWire/CSV | deep-nest                | java.lang.StackOverflowError             | null
QueryService.execute     | deep-nest                | java.lang.StackOverflowError             | null
QueryService.wireJson    | deep-nest                | java.lang.StackOverflowError             | null
QueryService.exec/CSV    | deep-nest                | java.lang.StackOverflowError             | null
QueryService.stream      | deep-nest                | java.lang.StackOverflowError             | null
Compiler.execute         | groupBy-empty            | java.lang.IllegalStateException          | result has 5 columns but the typed schema has 0 ? plan/schema mismatch
Compiler.executeWire/CSV | groupBy-empty            | java.lang.IllegalStateException          | csv wire over a zero-column relation
QueryService.execute     | groupBy-empty            | java.lang.IllegalStateException          | result has 5 columns but the typed schema has 0 ? plan/schema mismatch
QueryService.wireJson    | groupBy-empty            | java.sql.SQLException                    | Catalog Error: Table with name T_PERSON does not exist! Did you mean "pg_am"?  LINE 6:     FROM T_PERSON AS t0                  ^
QueryService.exec/CSV    | groupBy-empty            | java.lang.IllegalStateException          | csv wire over a zero-column relation
QueryService.wireJson    | join-nav                 | java.sql.SQLException                    | Catalog Error: Table with name T_PERSON does not exist! Did you mean "pg_am"?  LINE 6:     FROM T_PERSON AS t0                  ^
Compiler.execute         | toOne-many               | java.lang.IllegalStateException          | toOne() over a relation returned 3 row(s) ? the exactly-one contract (engine reader semantics)
QueryService.execute     | toOne-many               | java.lang.IllegalStateException          | toOne() over a relation returned 3 row(s) ? the exactly-one contract (engine reader semantics)
QueryService.wireJson    | toOne-many               | java.sql.SQLException                    | Catalog Error: Table with name T_PERSON does not exist! Did you mean "pg_am"?  LINE 6:     FROM T_PERSON AS t0                  ^
QueryService.execute(m)  | unknown-prop-type        | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | unknown-prop-type        | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | unknown-prop-type        | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | dup-element              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | dup-element              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | dup-element              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | dup-property             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | dup-property             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | dup-property             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | empty                    | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | empty                    | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | empty                    | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | self-extends             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | self-extends             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | self-extends             | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | cycle-extends            | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | cycle-extends            | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | cycle-extends            | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | assoc-unknown-class      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | assoc-unknown-class      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | assoc-unknown-class      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | mapping-unknown          | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | mapping-unknown          | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | mapping-unknown          | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | runtime-unknown-map      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | runtime-unknown-map      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | runtime-unknown-map      | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | bad-multiplicity         | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | bad-multiplicity         | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | bad-multiplicity         | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | only-import              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | only-import              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | only-import              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | fn-bad-body              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | fn-bad-body              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | fn-bad-body              | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(m)  | huge-fqn                 | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.wireJson(m) | huge-fqn                 | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.executeSql  | huge-fqn                 | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime
QueryService.execute(rt) | rt=<empty>               | java.lang.IllegalArgumentException       | Runtime not found: 
QueryService.wireJson(rt) | rt=<empty>               | java.lang.IllegalArgumentException       | Runtime not found: 
QueryService.execute(rt) | rt=no::Such              | java.lang.IllegalArgumentException       | Runtime not found: no::Such
QueryService.wireJson(rt) | rt=no::Such              | java.lang.IllegalArgumentException       | Runtime not found: no::Such
QueryService.execute(rt) | rt=test::TestRuntime::x  | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime::x
QueryService.wireJson(rt) | rt=test::TestRuntime::x  | java.lang.IllegalArgumentException       | Runtime not found: test::TestRuntime::x
QueryService.execute(rt) | rt=_                     | java.lang.IllegalArgumentException       | Runtime not found:  
QueryService.wireJson(rt) | rt=_                     | java.lang.IllegalArgumentException       | Runtime not found:  
Compiler.parseModel      | null                     | java.lang.NullPointerException           | source
Compiler.compileModel    | null                     | java.lang.NullPointerException           | model
Compiler.parseQuery      | null                     | java.lang.NullPointerException           | source
Compiler.compileQuery    | null-q                   | java.lang.NullPointerException           | query
Compiler.plan            | null-q                   | java.lang.NullPointerException           | source
QueryService.execute     | null-q                   | java.lang.NullPointerException           | source
QueryService.executeSql  | null-sql                 | java.sql.SQLException                    | sql query parameter cannot be null
QueryService.executeSql  | bad-sql                  | java.sql.SQLException                    | Parser Error: syntax error at or near "SELCT"  LINE 1: SELCT xxx         ^
DiagramService.extract   | null                     | java.lang.NullPointerException           | source
ModelOrchestrator.new    | null                     | java.lang.NullPointerException           | source
SerializerRegistry.get   | unknown                  | java.lang.IllegalArgumentException       | Unknown serialization format: arrow. Available formats: [csv, json]
SerializerRegistry.get   | null                     | java.lang.NullPointerException           | Cannot invoke "Object.hashCode()" because "key" is null
PureLsp.handleMessage    | null                     | java.lang.NullPointerException           | Cannot invoke "String.length()" because "this.src" is null
```
