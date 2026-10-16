# MongoDB as a first-class database of legend-lite: the study and the plan (2026-10-16)

**Status: research and proposals (2026-10-16). Nothing is scheduled or announced in `docs/IN_FLIGHT.md`; the
decisions of §4 are proposals for the user.** The ask (the user, 2026-10-16): learn what backends legend-lite supports
today (all SQL), then plan MongoDB as a first-class database, PCT lanes included, built "the pristine way": the way the
dialect consolidation program (the ninth line, `docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md`) lays out the end
state, so that Mongo is done right the first time rather than consolidated later. That program names MongoDB as out of
its own scope ("MongoDB, Elasticsearch and SingleStore are new targets, not consolidation", its §0) and the backend
order already places it next after the corpus-on-every-database work (`docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §2 item 4,
§11, §20; `docs/IN_FLIGHT.md`, the eighth line's "Next").

§0 is the answer; §1 the backends today; §2 the dialect program's end state and where Mongo fits; §3 what the tree
already holds for Mongo; §4 the decisions; §5 the design; §6 the PCT lane; §7 the landings; §8 the asks to the other
lines; §9 the risks and the measurements that settle them; §10 the sources.

---

## 0. Start here

1. **What exists.** Eleven executing databases, every one SQL over JDBC: DuckDB (the platform's own engine and the PCT
   reference lane), H2, Postgres, SQLite, MariaDB, MySQL, Trino, ClickHouse, Snowflake, Databricks and BigQuery, each a
   class in `core/src/main/java/com/legend/sql/dialect/` chosen by one registry, `database/Databases.dialect(type)`;
   plus three engine-text printers (`EngineStyleH2`, `EngineStyleDB2`, `EngineStyleComposite`) that write legend-engine's
   golden SQL text and execute nothing. The `DatabaseType` enum (`model/ConnectionDefinition.java:54`) is legend-engine's
   21 values plus lite's SQLite, MariaDB and MySQL (register row S48); the 13 others (DB2, SqlServer, Oracle, ...) are
   refused by name in every switch. The database enters the pipeline at three seams: plan side, `Databases.dialect`
   (phase J); execution side, `exec/Sessions` and the JDBC runners (phase K); the test harness, `PctBackend.Lane`.
   Every JDBC use is funnelled to `exec`, `server`, `testdatagen` and the root by `ArchitectureTest
   .javaSqlIsFunnelledToTheCharteredSeam`.
2. **The answer in one paragraph.** MongoDB is a **render target of the one compiler over the same `SqlQuery` IR**: a
   class beside the SQL dialects that decides, for every IR node, function and aggregate, what the aggregation pipeline
   spells, emulates or refuses by name; a **connector beside JDBC** that opens a `MongoClient`, runs the rendered
   command with its bound values and carries the one text the database wrote; and a **test backend beside the embedded
   servers** that starts a pinned `mongod` Bazel unpacked, as `EmbeddedClickHouse` starts ClickHouse. That is exactly the
   dialect program's acceptance test (§4 there: "a new database is three classes and one line in each of three
   registries"), and exactly what `docs/CLOUD_BACKENDS_PCT_2026_10_11.md` §11 already says of the non-SQL stores:
   "beside `SqlDialect`, not inside it: the Lowerer's output (the `SqlQuery` IR) stays the input, and a new target
   translates it into the store's own query language. The executor gains a second chartered seam beside JDBC." The MIR
   does not change (AGENTS.md invariant 3a: "new dialect = one class implementing `render(SqlQuery)`"); the Lowerer does
   not change; the PCT harness gains a lane the way ClickHouse's was gained. The execution tenet holds unchanged: the
   store executes, the pipeline builds the wire text, Java carries a string, and what Mongo cannot express is a
   `DialectCapability` wall, never a value computed in Java.
3. **The one structural dependency, and the ask to make now.** The dialect program's Phase 5 (the connector interface,
   `DatabaseConnector` and `Connectors.of(type)`) and Phase 6 (`TestBackend`) are not built yet (Phases 0 to 4 are on
   main: neema2/legend-lite#39, #45, #49). Today a session IS a `java.sql.Connection` (`Sessions.Session.connection()`,
   `PlanSessions`, `PlanRunner`, `QueryService.execute(..., Connection, ...)`). If Phase 5 is designed JDBC-only, Mongo
   has to fork the executor and the harness, which is the consolidation debt this plan exists to avoid. **The ask to the
   ninth line (§8.1): a connector's session is a sealed type over transports, the connector owns `check`, `version`,
   `run` and the bulk load, and `RenderedStatement` carries the language its text is in.** Mongo is then Phase 5's and
   Phase 6's first non-JDBC instance, built with the dialect session or right after it.
4. **The language is the engine's own.** legend-engine's `###MongoDB` store (collections with a JSON schema),
   `MongoDBConnection` and the `MongoDB` class mapping already parse, compose and round-trip in lite byte for byte
   (`parser/section/MongoDBSectionGrammar.java`, `protocol/Protocol.PMongoDatabase`, `PMongoDbConnection`,
   `PClassMappingMongoDb`), carried opaque in the model and never compiled or run. First-class means those compile,
   resolve, lower, render and execute; and the Relation surface (`#>{db.Collection}#`, `project`, `filter`, `groupBy`,
   ...) over a collection, which is what PCT and DataCube use, comes first (decision D3).
5. **Order.** After the eighth line's corpus-on-every-database harness, as the user ordered (§20 there), and with the
   dialect program's Phase 5 and 6: the seam first (one pure landing, measured by every existing lane's SQL dump staying
   byte-identical), then the server and the lane scaffold, then the target in two halves (scalar suites, then the
   relation suites), then the store and the mapping, then the corpus. Six landings, about 12k to 16k lines (§7).
6. **The rules this line keeps**, inherited from the Postgres, IQ and Cypher lines and the dialect program: zero FAIL,
   zero BUG, zero ERROR, a refusal by name beats a possibly wrong answer; Java never computes a value; every per-database
   decision in Mongo's own files (the owner guard holds it there); every pin dated with its reason; every landing
   announced in `IN_FLIGHT` first and recorded in `docs/GATES.md`.

---

## 1. The backends today

| database | dialect class | lines | how its PCT lane runs | lane in `//gates` | notes |
|---|---|---:|---|---|---|
| DuckDB | `DuckDb` | 1,027 | in-process, `jdbc:duckdb:` | `pct_duckdb` (per push) | the platform's own engine (`Databases.PLATFORM`), the PCT reference lane; refuses nothing in the capability matrix |
| H2 | `H2` (+`H2Modern`) | — | in-process, `jdbc:h2:mem:` | `pct_h2` (Relation only) | also the SQL-text replay referee (`Databases.REPLAY_REFEREE`); refuses 48 functions (lists, DuckDB-only functions, D1) |
| Postgres | `Postgres` | 2,343 | embedded 16, Bazel-unpacked (`@embedded_postgres`) | `pct_postgres` (per push) | refuses root: the unprivileged recipe in a cloud container (`docs/GATES.md`) |
| SQLite | `SQLite` | 3,156 | in-process, `jdbc:sqlite::memory:` | heavy | regular expressions as a recursive walk in SQL; the 1 MB statement cap pass |
| MariaDB, MySQL | `MariaDb`, `MySql` | — | embedded 11.4 / 8.4 servers (`tools/mariadb`, `tools/mysql`) | heavy | MySQL a family member of MariaDB (one grouped arm delegates) |
| Trino | `Trino` | — | embedded 483 coordinator, memory connector | heavy | one JVM child per target |
| ClickHouse | `ClickHouse` | 2,880 | embedded 25.8 static build (`tools/clickhouse/clickhouse.bzl`) | heavy | the pattern a `mongod` lane copies: a pinned tarball, sha256, one binary kept, Linux x86_64 only |
| Snowflake, Databricks, BigQuery | one class each | — | a real account (`CloudLane`, credentials from a file outside the repo) | heavy, `external`, `local` | |

Facts the plan stands on, each read in the tree:

- **The plan-side seam** is `SqlDialect` (`sql/dialect/SqlDialect.java`, 198 lines): `render(SqlQuery)` → text,
  `renderStatement(SqlQuery)` → `RenderedStatement(sql, binds)` (text with one `?` per bound parameter, in placeholder
  order, and `TypeHole`s where H2 needs a type written at prepare time), plus the SQL-only members: `render(SqlDdl)`,
  `render(SqlDml)`, `sessionSetup()`, `sessionTable`, `raw`/`fromEngineText` (decision D2), `physicalName`/`storedName`
  (D8), `catalogType`, `bareNameReaches`, `copySelectList`, `hostOperations`, `script`/`scriptAbort`, and the reads of the
  driver's answers (`normalize`, `wireTypeName`, `typingWireName`, `deliversOwn`, Phase 4). Every dialect writes
  through one `SqlWriter` (text in order, each bind at its place; `docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md` §10, E).
- **The base renderer spells nothing** since Phase 1 (2026-10-13): `AnsiSqlRenderer` is abstract in every vocabulary
  point (`call`, `reducer`, `scalarTypeName`, `formatText`, `structTypeName`, `passes`); each concrete dialect's `call`
  is one exhaustive switch over `SqlFn` (206 constants) with one expression per arm, its `reducer` one over
  `SqlAgg.Fn` (21), and `DialectVocabularyTest` fails a `default`, a block arm or a function handed to the base. The
  capability matrix (`docs/dialect-capability/`, `//core:capability_matrix`) reads those switches from the source of
  every class in the package, and `//core:capability_reach` measures which arms the five PCT suites reach on each
  database. A new class in `sql/dialect/` is read with no BUILD line (the program's §4 acceptance test).
- **The IR** (`com.legend.sql`, 15 files): `SqlQuery` permits `SqlSelect` and `SqlUnion`; `SqlSource` has `Pivot`,
  `SourceUrl`, `Table`, `Cte`, `VarSetPlaceholder`, `Dual`, `Subselect`, `Values`, `RawSql`, `Catalog`, `Join` (kinds
  INNER, LEFT, RIGHT, FULL, CROSS, CROSS_LATERAL, ASOF_LEFT, LEFT_LATERAL); `SqlExpr` has 46 variants, lambdas among
  them (`Lambda`, `FoldCall`); `SqlWith` carries CTEs with a `recursive` flag. Every dialect runs its own `passes()`
  (`SqlRewriter`s) at render entry: the carrier strategy first (`CarrierStrategies`, over the dialect's `CAPS`), then
  its own shapes (`PivotAsGroupBy`, `AsOfAsPick`, `QualifyToSubselect`, `LateralExplodeToUnion`,
  `FoldsAsRecursiveCtes`, `CommonSubexpressions`, ...). This is the mechanism a Mongo target uses to turn IR shapes it
  cannot spell directly into ones it can.
- **The execution side.** The new path: `PlanMaker.plan` renders through `dialect.renderStatement` into an
  `ExecutionPlan.Sql(statement, slots, target, ...)`, and `exec/PlanRunner` runs it with no model and no dialect:
  opens a `Sessions.Session` (a `java.sql.Connection`), checks the product name and version (`Sessions.check`,
  `Sessions.version`), runs the target's session statements, prepares the statement, binds each slot
  (`setObject`, `setNull`, `createArrayOf`), and carries the one text column the database wrote. The legacy path
  (`StatementExecutor`, `exec/Executor` with its 24 `ResultSet` sites, `Execution.execute*`) still serves the server, the
  corpus and PCT channel A (`PctExecuteNative` → `QueryService.execute(model, query, runtime, Connection,
  ExecuteOptions.PCT_RENDER)`); the database owner's line is deleting it ("step 4: the callers switch, the old paths
  deleted", `docs/IN_FLIGHT.md` line 3).
- **The wire is written by the database**, never assembled in Java: `lowering/WireRender.wrap` composes the CSV or JSON
  text of the whole result as one SQL value (`Render.csvWire`, `jsonWire`, `jsonWireRows`), and PCT's TDS text the same
  way (`Render.pctTds`: header from the typed schema, rows newline-prefixed, cells in Pure's print forms). The runner
  carries a string (Charter C1.2). Parameters never touch text (`EXECUTION_PLAN_BOUNDARY` P3: "JDBC bind parameters for
  SQL; a command document's values for a future Mongo-like store").
- **The harness.** `PctBackend.Lane` (an enum, one arm per database: `open()`, `openSystem()`, the storeless runtime's
  declaration), chosen by `LEGENDLITE_PCT_BACKEND`; `test/StorelessRuntime` declares an empty `###Relational Database`
  bound to a `RelationalDatabaseConnection` of the lane's type (legend-engine's PCT adapter's shape); five suite
  classes per lane (`Test_LegendLite_<Db>_<Suite>Functions_PCT`), each expected failure pinned by a stable part of its
  message, a pinned test that starts passing failing the run; `PctCensusGate`'s per-suite ceilings; channel B
  (`channelb/ChannelB`, `ChannelBLane`'s PASS floors and wire-bug ceilings per lane); the lanes in `pct/BUILD.bazel`
  (`pct_<db>_<suite>`, five `junit_test`s and a suite, `manual` and in `//gates:heavy` for every database but DuckDB,
  H2 and Postgres). A server lane also hosts the platform's **system database** (`exec/SystemDatabase.routeFor`: the
  metamodel store seeded as session-private temporary tables on a system connection the host configures).
- **The guards a new database meets** (each moves only with a dated reason, AGENTS.md): `ArchitectureTest
  .JDBC_AND_DRIVERS` (the driver packages funnelled to the seam; `com.mongodb..` and `org.bson..` join it),
  `DialectBoundaryTest` (counts dialect constructions, 11 today, and every driver), `DialectLeakRatchetTest` and
  `DialectOwnerExclusivityTest` (`DatabaseOwners.DATABASES` already lists `MongoDB` as `mongo`: every identifier naming
  Mongo outside an owner is a counted leak, so Mongo's files must be owners by path or class name),
  `DialectVocabularyTest`, `CodeShapeGuardrailTest` (250 lines a method), `CarrierPurityRatchetTest`,
  `JavaEvalLedgerTest` (the named residue), `SqlTextRatchetTest`, `JdbcSurfaceCensusTest`, the capability matrix's
  diff test in `//:generated`, `PctCensusGate` and `ChannelBLane`.
- **This container** reaches `fastdl.mongodb.org` (a pinned `mongod` tarball downloads; HTTP 200 measured 2026-10-16)
  and Maven Central (`org.mongodb:mongodb-driver-sync`; 200), and has a Docker client; GitHub release pages are
  refused by the proxy (403, `docs/CLOUD_SESSIONS.md`). Bazel is not installed in this session; nothing here was
  built or run.

---

## 2. The dialect program's end state, and where Mongo fits

The program's §4, the target every phase builds toward, restated with Mongo's row filled in:

| layer | the one class per database | Mongo's | registry |
|---|---|---|---|
| **query target** (plan side, no driver) | `sql/dialect/<Db>.java`: all of the database's text, its function, type and keyword decisions, its capabilities, reading the driver's names back | `sql/dialect/MongoDb.java`: the aggregation pipeline for every IR shape, the operator for every function and aggregate, `$documents` for literal relations, the wire text as the pipeline's tail, BSON type names, every refusal by name | `Databases.dialect(type)` (today) |
| **connector** (execution side) | `exec/connect/<Db>Connector.java` (Phase 5, not built): product, URL from a connection spec, driver, private instance, bulk load, driver cells, raise, in-memory declaration | `MongoDbConnector`: a `MongoClient` from a `MongoDBConnection`'s servers and auth; runs an `aggregate` command with its `let` values; carries the text; `insertMany` as the bulk load; the server's version check; the system database as a session-private database | `Connectors.of(type)` (Phase 5; `Sessions`' switches until then) |
| **test backend** (harness) | `testing/.../<Db>Backend.java` (Phase 6, not built; absorbs `PctBackend.Lane`'s arms): start the server, a fresh workspace, fixture isolation, the referee's reader, the connection block, seeding, pins and ceilings | `MongoDbBackend` over `EmbeddedMongoDb` (`tools/mongodb/mongodb.bzl`): a workspace is a database per package, isolation a database copy (`$out`/`$merge` or re-seed), the connection block a `MongoDBConnection` | `TestBackends.of(type)` (Phase 6; `PctBackend.Lane` until then) |

What Mongo reuses from the program **verbatim**: the exhaustive-switch discipline and its guard; the capability matrix
and the reach measurement (a Mongo column appears on the next regeneration, and `reach.tsv` gains its lane); the
carrier-strategy pass over a `CAPS` row (Mongo's lists, structs and maps are native, like DuckDB's); the shared shape
passes (`PivotAsGroupBy` for a static pivot, `AsOfAsPick` for an as-of join, `QualifyToSubselect`); decision D2 for raw
text (text written for Mongo runs as written; the engine's H2 text is refused by name); Phase 4's reads
(`wireTypeName`: BSON type names into the wire vocabulary); Phase 0's guards.

What Mongo **stresses**, each a question the program's remaining phases must answer with a non-SQL member in mind:

1. **`SqlDialect` is the SQL interface.** `render(SqlDdl)`, `render(SqlDml)`, `sessionSetup()` (SQL strings the executor
   runs), `fromEngineText`, `catalogType`, `copySelectList`, `script` are SQL-only. Two shapes are possible (decision
   D2 of §4): Mongo implements `SqlDialect` and refuses every SQL-only member by name; or a supertype `QueryTarget`
   holds what every target has (`renderStatement`, `passes`, the vocabulary switches, the wire reads, `raw`,
   `sessionSetup`) and `SqlDialect extends QueryTarget` keeps the rest.
2. **The session is a `java.sql.Connection` by type.** `Sessions.Session.connection()`, `Sessions.Opener`,
   `PlanSessions.Source`, `PlanRunner.text` (prepare, bind, `executeQuery`, `getString(1)`), `Sessions.check` and
   `Sessions.version` (JDBC metadata), `QueryService.execute(..., Connection, ...)`, `PctBackend.Lane.open()`. Phase 5 is
   where this becomes a connector's own (`check`, `version`, `run`), and the sealed session type is the ask of §8.1.
3. **The statement is SQL by name and by type.** `ExecutionPlan.Sql`, `RenderedStatement.sql`, `ExecutionPlan.Format`,
   `SetupStep.Statement(String sql)`. The plan's text is the TARGET's language; the record needs to say which
   (`Language.SQL`, `Language.MONGO_COMMAND`), and the runner dispatches on it (a sealed switch, no default).
4. **`DatabaseType` is relational.** Every registry, `Target.type()`, `ExecutionPlan.Target.Database`,
   `StorelessRuntime`, the connection grammar and the owner guard key on it. Decision D4 of §4.
5. **The legacy executor path.** `Executor`'s `ResultSet` readers type cells from JDBC (24 sites); PCT channel A and the
   corpus reach the database through it. Mongo should never gain a second reader there: it runs through `PlanRunner`
   only, which means channel A on Mongo takes the plan path (§6, §8.2).

---

## 3. What the tree already holds for Mongo

Legend-engine's MongoDB store, the vocabulary (decision D9: the lexer, parser, protocol and model may carry any
database's name and decide nothing by it), parsed and composed since the parser drop-in program:

- **The store**: `###MongoDB` `Database qn ( include qn; Collection name ( validationLevel: strict|moderate;
  validationAction: error|warn; jsonSchema: { ... }; ) Join ... )` (`parser/section/MongoDBSectionGrammar.java`, 277
  lines; `Protocol.PMongoDatabase` with `PMongoCollection(name, validationLevel, validationAction, PBsonSchema)`;
  `TailEmitter.mongoDatabase` writes the engine's JSON). `include` and `Join` parse and are dropped, as the engine's
  walker drops them (the fixture `scripts/parser/fixtures/t2-mongodb.pure` records why). The JSON schema is read into
  typed BSON schema nodes at parse time, as the engine does ("`bsonType: object` is mandatory").
- **The mapping**: `*Class[id]: MongoDB { ~filter [db] name  ~mainCollection [db] Coll  ~binding qn }`
  (`MappingProtocolParser.parseMongoDbClassMapping`, `Protocol.PClassMappingMongoDb`; `MappingFromProtocol.classMapping`
  returns null for it: "a FOREIGN store class mapping rides the protocol only").
- **The connection**: `MongoDBConnection { database: name; store: qn; serverURLs: [host:port, ...]; authentication:
  # UserPassword { username: '...'; password: <vault secret> }#; }` (`ConnectionReader.mongo`, `PMongoDbConnection(databaseName,
  serverUrls, auth, ...)`, `AuthenticationComposer`, `MongoComposer`); in the model it is a
  `GenericSectionElementDefinition("Connection", "MongoDBConnection", ...)` carrying only the database name
  (`FromProtocol.java:426`), so `Compiler.executesOn` refuses a runtime binding it ("connection ... is not defined").
- **The guards** already know the name: `DatabaseOwners.DATABASES` maps `MongoDB` to the pattern `mongo`;
  `DialectLeakRatchetTest`'s product pattern lists `mongo|elastic`; `DialectOwnerExclusivityTest`'s allowlist names
  `PMongoDbConnection`. So a `sql/dialect/MongoDb.java` is an owner of Mongo by its class name with no guard edit, a
  `core/src/main/mongodb/` source set needs one line in `DatabaseOwners.of` beside `core/src/main/duckdb/`, and
  anything else naming Mongo is a leak the ratchet counts.
- **Nothing executes.** No `DatabaseType`, no dialect, no session, no lane, no data seeding. `docs/EXECUTION_PLAN
  _BOUNDARY_2026_10_05.md` already decided the parameter form for "a future Mongo-like store" (P3: a command document's
  values, never text), and that legend-engine's Mongo plan nodes need its generated Java, which lite never runs.

---

## 4. Decisions for the user

Each row: the question, the recommendation, the alternative and why it is not recommended.

| # | question | recommendation | alternative, and why not |
|---|---|---|---|
| **D1** | Is Mongo a target of the one compiler over the `SqlQuery` IR, or a lowering of its own from the typed Pure (as legend-engine's `pureToMongoQuery` is)? | **A target over the IR.** One class in `sql/dialect/` deciding every IR point by exhaustive switch, its own `passes()` reshaping what it cannot spell. Invariant 3a ("MIR does not change; new dialect = one class"), §11's stated direction, and every program asset (the vocabulary guard, the capability matrix, the reach measurement, the carrier strategies, the shape passes, PCT's storeless path, which lowers up to the dialect) apply unchanged. | A second lowering duplicates the Lowerer's structural work (joins through mappings, folds, windows, carriers) for one database, is invisible to the matrix and the guards, and is a program ported (Charter C6.1) where the engine's Pure-to-MQL is the spec. |
| **D2** | The seam's shape: Mongo implements `SqlDialect` and refuses its SQL-only members, or `SqlDialect` splits a `QueryTarget` supertype? | **Split, with the dialect program (Phase 5's landing).** `QueryTarget`: `renderStatement`, `render` (text), `passes`, the vocabulary switches, `sessionSetup`, `raw`, `wireTypeName`, `typingWireName`, `deliversOwn`, `normalize`, `forServer`, `needsStaticPivot`; `SqlDialect extends QueryTarget` keeps DDL, DML, `fromEngineText`, `physicalName`/`storedName`, the catalog, `copySelectList`, `hostOperations`, `script`. `Databases.target(type)` answers a `QueryTarget`; a caller that needs SQL asks `Databases.dialect(type)`, which refuses a non-SQL type by name. `RenderedStatement` gains `Language language` (SQL, MONGO_COMMAND), carried into `ExecutionPlan.Sql` and switched on by the runner. | Implementing `SqlDialect` whole is smaller (no interface edit) but every SQL-only member becomes a refusal arm, the plan record keeps calling a command "sql", and the executor's `sessionSetup` strings would be run as SQL: the leak-in-disguise the program spent Phase 1 removing. If the dialect session prefers it for Phase 5's timing, it is acceptable as a first cut with the split scheduled. |
| **D3** | Which model surface first: legend-engine's `MongoDB` class mapping (`~mainCollection`, `~binding`) or lite's Relation surface over a collection (`#>{db.Collection}#`)? | **The Relation surface first, the class mapping on it.** A `###MongoDB` Collection with a JSON schema is a relation: its top-level properties are the columns (D6). `project`, `filter`, `groupBy`, `join`, `extend`, windows and the wire all run through the IR; PCT, DataCube and the corpus use this surface. The engine's class mapping is then a relation class mapping over that relation: each property reads the same-named field, `~filter` the named filter, `~binding` checked against the schema and not otherwise read. | The class mapping first reproduces the engine's narrow subset (filters on root fields, graph fetch) and defers everything PCT measures. |
| **D4** | How does Mongo enter the type registries: `DatabaseType.MongoDB` as a lite extension (S48's precedent: SQLite, MariaDB, MySQL), or a wider engine kind beside `DatabaseType`? | **`DatabaseType.MongoDB`, a lite extension**, declared by a `MongoDBConnection` and never writable in a `RelationalDatabaseConnection` (`LITE_DATABASE_TYPES` refuses it on the drop-in surface, as S48's three are; a new register row says so). `FromProtocol` reads `PMongoDbConnection` into a `ConnectionDefinition(databaseType = MongoDB, specification = MongoServers(urls, database), authentication)`. javac then forces an arm in every switch over the type (`Databases` ×4, `Sessions` ×5, `StorelessRuntime`, `EngineText`, the grammar): the program's "adding a constant breaks the build until each spells it or refuses it by name". | A second enum (`Engine = DatabaseType ∪ {MongoDB}`) threaded through `Target`, `ExecutionPlan`, `Sessions`, `PctBackend` is the wider edit for no gain in checking; it would also leave `DatabaseType`'s switches unable to name Mongo at all. |
| **D5** | How are parameters bound? | **As the `aggregate` command's `let` variables** (MongoDB 5.0+; re-check at the pin): the writer writes `"$$p<n>"` where the SQL writer writes `?`, in the same bind order, and the connector builds `let: {p0: v0, ...}` from the plan's slots. Values are BSON (typed), never text; a list binds as one array (`Binding.Array`); no `TypeHole` ever (BSON carries the type). `$match` on a variable goes through `$expr`, which the pipeline writer does anyway. | Splicing literals into the command text is what P3 forbids. `$function` (server-side JavaScript) is never used (D11). |
| **D6** | How is a collection typed as a relation? | **From its declared JSON schema, and only from it**: `bsonType` string → String, int/long → Integer, double → Float, decimal → Decimal, bool → Boolean, date → DateTime, objectId → String (its hex, through `$toString`), object and array → Variant (as Postgres's jsonb is); a property not in `required` is nullable; a collection with no `jsonSchema` is refused by name ("declares its fields or is not readable as a relation"); `additionalProperties` adds nothing. No sampling, no inference from values (Charter C2.2, invariant 4). DataCube's catalog reads the collection's validator (`listCollections` → `options.validator.$jsonSchema`) through `catalogType`, and refuses a collection without one. | Inferring columns from documents is deciding types from values. |
| **D7** | The null model: BSON distinguishes a missing field from `null`, and its comparisons are total (no three-valued logic; `null` sorts first, mixed types in BSON type order). | **SQL's semantics, emulated**: a missing field reads as `null` (`$ifNull` at the scan), comparisons answer `null` when an operand is `null` (`$cond` over `$type` checks), `AND`/`OR` three-valued, `ORDER BY` with NULLS FIRST/LAST as the IR's `SortKey` says (a computed sort key), `=` never matches `null`. The capability matrix records each as **E** (emulated). | Mongo's own semantics would make every PCT null test a pin and every cross-database answer differ. |
| **D8** | The server for the lane and its licence. | **A pinned MongoDB Community `mongod` (8.0.x LTS) Bazel downloads from `fastdl.mongodb.org` and starts as a child process**, as the Postgres, MariaDB, MySQL, Trino and ClickHouse lanes do; only the Apache-2.0 Java driver (`mongodb-driver-sync`, `mongodb-driver-core`, `bson`) is on a classpath, test-only until the server opens Mongo. The server is SSPL-1.0 (not Apache-2.0 as ClickHouse's binary is): nothing of it enters the repository or a shipped artifact, and running it in a test is not offering it as a service. The user confirms the licence posture; `tools/mongodb/mongodb.bzl` records it. | FerretDB (Apache-2.0, the wire protocol over Postgres) is not MongoDB: measuring against it measures the wrong thing. Docker is available here but the lanes are Docker-free by design. |
| **D9** | Version floor. | **8.0** (manual, to re-check at the pin): `$documents` for a literal relation at the database level (5.1), `let` on `aggregate` (5.0), `$setWindowFields` (5.0), `$sortArray` (5.2), `$median` and `$percentile` (7.0), `$toHashedIndexKey` is not a digest (no MD5/SHA in MQL: refused), PCRE2 regular expressions (6.1). The connector refuses an older server by name, as `PlanRunner.check` refuses an H2 version. | |
| **D10** | The PCT wire on Mongo: who writes the text? | **The pipeline, as SQL does**: each cell to text (`$toString`, `$dateToString`, the Float repr emulated, Variant as JSON through `$toString`... measured in PR0), rows joined in order (`$setWindowFields` with a whole-partition `$push` over the sorted order, which the manual documents as ordered, else `$group` after `$sort`, measured), the whole as one string in one document's one field; the connector carries it. A result past the 16 MB BSON document limit is a named wall, as SQLite's 1 MB statement cap is. | Reading documents into rows in Java and formatting them is C2.5 (rendering ruled representations) and C2.3 (reshaping results). The streaming JSON form (`JSON_PER_ROW`) is one document per row, the punctuation the runner's, as today. |
| **D11** | Never. | No `$function`, `$accumulator` or `$where` (server-side JavaScript: a program written by the dialect, and a security surface); no `mapReduce`; no reading of a value in Java to decide anything. | |
| **D12** | Where this line sits. | **A new line, the twelfth**, announced in `IN_FLIGHT` when the user says so; it owns `sql/dialect/MongoDb*.java`, `core/src/main/mongodb/`, `testing/.../EmbeddedMongoDb.java`, `tools/mongodb/`, the Mongo PCT suites and lanes, and the Mongo arms of the registries; the seam itself (PR1) is the ninth line's Phase 5 with this line's ask, built together or in sequence (§8.1). | |

---

## 5. The design

### 5.1 The query target: `sql/dialect/MongoDb.java`

One class, with helpers beside it in the same package (`MongoDbStages`, `MongoDbExpressions`, `MongoDbWire`), every
one an owner by its class name. It writes **Extended JSON (canonical mode)** into the one writer: the text of an
`aggregate` command document, with `"$$p<n>"` at each bind. The command is text because the plan is text
(`ExecutionPlan.Sql.statement`), and Extended JSON is lossless for every BSON type; the connector parses it with the
driver's own `BsonDocument.parse`.

**The IR to the pipeline**, shape by shape (the `passes()` first, then the writer):

| IR | pipeline |
|---|---|
| `SqlSource.Table` (a collection) | the command's `aggregate: "<collection>"`; a scan projects every declared column with `$ifNull` for a missing field (D7), `_id` only when declared |
| `Values`, `Dual` | `aggregate: 1` with `$documents: [ {...}, ... ]` (`Dual` is `$documents: [{}]`): PCT's storeless expressions need no collection and no loading |
| `SqlSelect.where` | `$match: { $expr: <expr> }` |
| `projections` | `$project` (computed columns as `$addFields` first where a later projection reads an earlier one, as `Fold` already isolates) |
| `groupBy`, aggregates, `having` | `$group: { _id: {k: ...}, agg: {...} }`, then `$project` to the output names, then `$match` for `having` |
| `orderBy`, `limit`, `offset` | `$sort` (over computed keys for NULLS FIRST/LAST and the IR's collation), `$skip`, `$limit` |
| `distinct` | `$group` on every output |
| `Join` INNER, LEFT | `$lookup` with `let` and a `pipeline` (the ON condition as `$expr`), then `$unwind` (`preserveNullAndEmptyArrays` for LEFT); RIGHT as the mirrored LEFT; FULL as LEFT `$unionWith` the unmatched right (`$lookup` + `$match` on an empty array); CROSS as `$lookup` with no condition; `CROSS_LATERAL` and `LEFT_LATERAL` as `$lookup` whose pipeline reads the left row's `let` variables; `ASOF_LEFT` through the shared `AsOfAsPick` pass |
| `Subselect`, `Cte` | a sub-pipeline inside `$lookup` (over `$documents` or the collection), or inlined where it is the only source; a recursive `SqlWith` is `$graphLookup` only where the shape is a plain reachability walk, else refused by name (the Cypher line's recursion native decides its own rule per dialect) |
| `SqlUnion` | `$unionWith` (ALL), then `$group` for DISTINCT |
| `Pivot` static | the shared `PivotAsGroupBy` pass; a dynamic pivot through `needsStaticPivot()` (the two-phase staticization) |
| `WindowCall`, `Frame` | `$setWindowFields: { partitionBy, sortBy, output: { x: { <fn>, window: { documents: [a, b] | range: [a, b] } } } }`; ranking functions as `$rank`, `$denseRank`, `$documentNumber`; `lag`/`lead` as `$shift`; `ntile`, `percent_rank`, `cume_dist` emulated from counts |
| `Exists`, `InSubquery`, `ScalarSubquery` | `$lookup` with the subquery's pipeline, then `$size`/`$arrayElemAt` in `$expr` |
| `Lambda`, `FoldCall`, lists | native: `$map`, `$filter`, `$reduce`, `$concatArrays`, `$slice`, `$sortArray`, `$zip`, `$range`, `$arrayElemAt`, `$size`, `$setUnion`, ...; structs and maps as embedded documents (`$getField`, `$setField`, `$objectToArray`, `$arrayToObject`, `$mergeObjects`); `CAPS` says lists, structs and maps are native, as DuckDB's does |
| `Cast` | `$convert` with the BSON type the Pure type names (`scalarTypeName`'s switch), `onError` refused (an error is an error) |
| `DateLit`, `TimestampLit`, date parts and formats | `{"$date": ...}` literals, `$dateAdd`, `$dateDiff`, `$dateTrunc`, `$dateToString` with the format the `formatText` switch spells, `$year`.. `$isoWeek`, `$dayOfWeek` |
| `RawSql` (written for Mongo) | the text is a pipeline array written by the user, run as written (D2); the engine's H2 text refused by name |
| `Catalog` | `listCollections` is a command, not a stage: refused as a grid in the first landings (Postgres refuses it too today); DataCube's catalog goes through `catalogType` (D6) |
| the wires (`csvWire`, `jsonWire`, `pctTds`) | the same composition, in `$project`/`$setWindowFields`/`$group`/`$reduce` (D10) |

**The vocabulary**: `call` is one switch over `SqlFn` (206), `reducer` one over `SqlAgg.Fn` (21), each arm one
expression: spelled (`$abs`, `$ceil`, `$floor`, `$round`, `$trunc`, `$sqrt`, `$pow`, `$exp`, `$ln`, `$log10`, `$mod`,
the trigonometric operators, `$concat`, `$toUpper`, `$toLower`, `$strLenCP`, `$substrCP`, `$indexOfCP`, `$split`,
`$trim`/`$ltrim`/`$rtrim`, `$regexMatch`, `$regexFind`, `$regexFindAll`, `$replaceOne`/`$replaceAll`, `$toInt`,
`$toLong`, `$toDouble`, `$toDecimal`, `$toString`, `$toDate`, `$toBool`, `$type`, `$cond`, `$switch`, `$ifNull`,
`$in`, `$size`, `$rand`, ...), emulated (integer division and `MOD` over the sign rules, `GREATEST`/`LEAST` with null
handling, `REPEAT`, `LPAD`/`RPAD`, `REVERSE` over `$reduce`, `SPLIT_PART`, `STRING_AGG` as `$push` then `$reduce` with
`$concat`, `MODE` as a nested `$group`, `VAR_SAMP`/`VAR_POP` from `$stdDevSamp`/`$stdDevPop` squared, `CORR`/`COVAR`
from sums, `ARG_MAX`/`ARG_MIN` as `$top`/`$bottom`, `QUANTILE` as `$percentile`, `MEDIAN` as `$median`), or refused by
name (`MD5`, `SHA1`, `SHA256`, Levenshtein, Jaro-Winkler and every other walk that needs recursion in an expression;
`HUGEINT` arithmetic past a Long; a date before year 1 in `$dateToString`, measured). Every window function is written
by `windowCall`, every lowering marker refused, exactly as the other tables do (`DialectVocabularyTest`'s
`ONE_EXPRESSION_TABLES`).

**Raising an error** (Pure's `assert`, a `raise` the SQL dialects spell through `raiseEnvelope`): MQL has no `$error`;
the measured candidate is a conversion that fails naming its input (`$toInt` of the message text), whose message the
connector's `RaisedErrors` reads back, as the SQL dialects' envelopes are read. Measured in PR0, refused by name until
then.

**What it reads back**: `wireTypeName` folds BSON type names (`int`, `long`, `double`, `decimal`, `string`, `bool`,
`date`, `objectId`, `object`, `array`, `null`) into the wire vocabulary; `deliversOwn` says a Variant arrives as a
document and a Decimal as Decimal128. There is no `normalize` of JDBC cells: the connector hands the one text through.

### 5.2 The connector: `core/src/main/mongodb/com/legend/exec/MongoDbConnector.java`

Its own source set with the driver on its compile classpath, as `core/src/main/duckdb` holds `DuckDbAppenderLoad` and
`DuckDbCells` beside the DuckDB driver and core compiles against no driver; found through `ServiceLoader` (the
`BulkLoad`/`DriverCells` precedent, `core/BUILD.bazel:329`), or named directly by Phase 5's `Connectors.of(type)` if that
registry lives beside the drivers. It owns:

- `open(ConnectionDefinition)`: a `MongoClient` from the `MongoServers` specification (hosts, the database) and the
  authentication (user and password from the vault reference the model names, as the cloud lanes read theirs: the
  platform holds no credential, C3b);
- `check(session, type)` and `version(session)` from `buildInfo`/`hello` (the version floor, D9);
- `run(RenderedStatement, values)`: `BsonDocument.parse` of the command text, `let` from the slots, `runCommand` on the
  session's database, the one text field of the one result document returned as the plan's text (or the per-row
  documents for `JSON_PER_ROW`), the cursor exhausted;
- the bulk load (`SetupStep.Rows` → `insertMany` of documents the dialect's seed render spells, every cell typed by
  BSON: the loader "spells no SQL and types no value" rule holds because the dialect wrote the documents);
- `raisedMessage` (`RaisedErrors`): the raised text read out of a command error, as MariaDB's is read out of a session
  variable;
- the system database (`SystemDatabase.routeFor` on a server engine): the metamodel tables seeded once per graph as
  collections `metamodel_<table>` in a session-private database (named by the session, dropped with it), read through the
  same table renames; or, for the first landings, metamodel reads refused by name on Mongo (the few PCT tests that read
  it, `getAll`'s `testBasic`, pinned with that reason).

Nothing in `core/src/main/java` imports `com.mongodb` or `org.bson`; `ArchitectureTest.JDBC_AND_DRIVERS` gains both so
the funnel rule sees them, and `DialectBoundaryTest`'s driver census names the driver.

### 5.3 The test backend: `EmbeddedMongoDb`, `tools/mongodb/`, the lane

- `tools/mongodb/mongodb.bzl`: the pinned Community tarball per platform (version, sha256), one binary kept (`mongod`;
  `mongosh` is not needed), a `MONGODB_ROOT` marker, the hub `select` over `//tools/platforms` and
  `EMBEDDED_MONGODB_COMPATIBLE`, the ClickHouse rule's shape line for line (`tools/clickhouse/clickhouse.bzl`). Linux
  x86_64 first; macOS arm64 and Linux arm64 are pins to add when a runner needs them. The module extension in
  `MODULE.bazel` beside `embedded_clickhouse`. The cloud-session distdir script covers the tarball if the proxy refuses
  Bazel's downloader (`docs/CLOUD_SESSIONS.md`).
- `testing/src/main/java/com/legend/testing/EmbeddedMongoDb.java`: started once per JVM on a free loopback port with
  `--dbpath` in the test's temporary directory, `--bind_ip 127.0.0.1`, a user created at start when the lane's
  connection declares one (the engine's `MongoDBConnection` requires an `authentication` block, measured in PR0: the
  grammar may need a lite `# None {}#` arm, a register row if so), stopped at exit, restarted if it dies, its log's tail
  in the failure, as `EmbeddedClickHouse` does.
- The lane: `PctBackend.Lane.MONGODB` (`LEGENDLITE_PCT_BACKEND=mongodb`; its `open()` answers the connector's session,
  which is why the lane enum's return type moves with Phase 5), the storeless runtime for Mongo
  (`StorelessRuntime.onServer` writing a `###MongoDB Database storeless::Store ( )` and a `MongoDBConnection` with the
  server's URL and database `pct`), the five suite classes `Test_LegendLite_MongoDB_<Suite>Functions_PCT` with every
  failure pinned at the first measurement, `PctCensusGate`'s ceilings for the lane, `ChannelBLane`'s pins, and in
  `pct/BUILD.bazel` five `junit_test`s plus a suite and the channel B target, `manual`, with the driver jars as their
  runtime deps and the server as `data`; `//gates:heavy` lists them. The driver's three jars in
  `tools/deps/jars_table.bzl` (`users = ["pct"]` until the server opens Mongo, then core's connector library).
- Phase 6's `MongoDbBackend` (when the interface exists): `openWorkspace()` is a database per package, `isolateFixture()`
  a copy of the fixture's collections (`$out` into the workspace, or a re-seed), `referee()` none (the H2 referee judges
  SQL text; a Mongo lane judges rows only, the `OTHER_LANE` shape the corpus already has for Postgres), `seed` through
  the connector's bulk load.

### 5.4 The model side

- **The store** (`###MongoDB`): a `PMongoDatabase` becomes a store the compiler knows (today it is "carried"): its
  collections are tables with the columns D6 derives, their declared types the BSON types (`storedTypes`, so the
  dialect can read an `objectId` as text and a `date` as DateTime). `include` and `Join` are parsed and dropped by the
  engine itself; lite does the same until a case needs them.
- **The table reference** `#>{db.Collection}#` resolves as it does for a relational store (`TableReferenceChecker`), the
  scan a `SqlSource.Table` whose `name` is the collection, `quoting` bare; a collection or field name with `$` or `.`
  is refused by name.
- **The class mapping** (`*Class: MongoDB { ~mainCollection [db] Coll ~filter ... ~binding ... }`): a relation class
  mapping over the collection's relation, each property the same-named field (nested paths as Variant reads), the
  filter the named one, the binding checked for the class and otherwise unread; `MappingFromProtocol.classMapping` stops
  returning null for it. This is the engine's drop-in surface; parity with the engine's own Mongo test cases in the
  pinned 4.145.0 tree is PR6's.
- **The connection**: `MongoDBConnection` → `ConnectionDefinition(MongoDB, MongoServers, auth)` (D4); the server's
  `ConnectionResolver` opens it through the connector; `EngineText` refuses Mongo by name (legend-engine's Mongo plans
  are not SQL text; their plan nodes are out of scope, `EXECUTION_PLAN_BOUNDARY` §1).
- **Data**: `###Data` rows and `SetupStep.Rows` seed a collection through the bulk load; the `###Relational` CSV seed
  forms do not apply.
- **The register** (`docs/SEMANTICS_REGISTER.md`) gains a row for each deliberate difference: `DatabaseType.MongoDB` as
  a lite extension; a `StrictDate` stored as a BSON date at midnight UTC (BSON has no date-only type); Decimal at
  Decimal128's 34 digits (DuckDB's 38); integers as 64-bit only; a missing field read as null (D7); strings measured in
  code points (`$strLenCP`), which is Pure's rule; the regular-expression dialect (PCRE2 against DuckDB's RE2); the
  Float's text where `$toString` differs from `Scalars.floatRepr` (measured).

---

## 6. The PCT lane on Mongo

- **Channel A** (`PctExecuteNative`): the expression and its injected model execute on the lane's storeless runtime.
  Today the bridge hands a `java.sql.Connection` to `QueryService.execute` and the result comes back through the
  legacy executor (`ExecutionResult.TdsText` under `PCT_RENDER`). On Mongo the bridge hands the connector's session,
  and the result comes through the plan path: `TypedQuery.executionPlan` gains a `PCT_TDS` output (the plan emits
  `Render.pctTds`'s text, the E1 precedent) and `PlanRunner.run` carries it. That is the database owner's step 4 ("the
  callers switch") applied to one caller first (§8.2); it also means the Mongo lane never touches the 24 `ResultSet`
  sites of `Executor`.
- **Channel B** (`ChannelB`): lite's own compiler runs the PCT sources on the lane's database; nothing Mongo-specific
  beyond the session.
- **The census** (`SqlTypeCensus`, `PctCensusGate`): the wire is one text, as it is on every lane under `PCT_RENDER`;
  the lane's ceilings are measured on its own target.
- **What to expect** (from the shape of the other lanes, to be measured, never promised): lists, structs, maps and
  Variant are native, so the H2-class refusals (48) do not appear; dates carry milliseconds (microsecond cases pin,
  as on MariaDB); Decimal128 and 64-bit integers pin the big-number cases (`testBigFloatAbs`, `AdjustBy*BigNumber`,
  as they do on ClickHouse); the digests and the string-distance walks refuse (2 on BigQuery, the same here);
  regular-expression dialect differences pin a few (SQLite's 10); the platform's own 34 (instance identity, error
  positions, the 0-based `indexOf`) are the DuckDB lane's. Other lanes first measured between 40 (Snowflake, Trino) and
  83 (BigQuery) own pins and burned down to 4 to 42; a Mongo lane is likely to land in that range and burn down the same
  way. The number is the first run's to tell.
- **The capability matrix** gains a Mongo column on regeneration (`bazel run //docs:update_dialect_capability`), and
  `reach.tsv` the lane's reach: that page is the acceptance artifact for "every function decided, by name".

---

## 7. The landings

Each lands green on its own (`//gates:local`; every existing lane's whole-statement SQL dump unchanged for a pure move;
the corpus rosters unchanged; the leak ratchet only shrinking), announced in `IN_FLIGHT` first, with an independent
review and a `docs/GATES.md` entry, as every line does.

| # | landing | content | judge | size |
|---|---|---|---|---:|
| **PR0** | the measurement | no product code: 20 to 30 PCT cases translated to pipelines by hand and run on `mongod` 8.0 in a container (the tarball or Docker), settling the open measurements of §9 (ordered `$push`, `$toString` of doubles and decimals, `$documents` with `let` at the database level, the 16 MB limit's error, the raise envelope, `$dateToString` before year 1, PCRE2 against the PCT regular expressions, the auth block the grammar needs); the answers recorded in this document's §9 | the measurements, each with its command and answer | about a week; a §9 revision |
| **PR1** | the seam (with the ninth line: Phase 5's landing) | `QueryTarget` above `SqlDialect` (D2); `RenderedStatement.Language` into `ExecutionPlan.Sql`; the sealed session and the connector interface (`check`, `version`, `run`, `bulkLoad`, `raisedMessage`, `openPrivate`); `PlanRunner`'s dispatch on the language; `Connectors.of(type)` replacing `Sessions`' switches; `DatabaseType.MongoDB` with its grammar arm and register row; `MongoDBConnection` read into a `ConnectionDefinition`; the guards' pins with dated reasons (`JDBC_AND_DRIVERS`, `DialectBoundaryTest`, `DatabaseOwners`) | a pure move: all 102 lane targets on the 11 databases pass with the baseline's SQL by digest (the program's §0 proof), `//gates:local` green; Mongo's arms all refuse by name | about 1,000 lines, mostly moved |
| **PR2** | the server and the lane scaffold | `tools/mongodb/mongodb.bzl`, the module extension, `EmbeddedMongoDb`, the driver jars, `PctBackend.Lane.MONGODB`, the storeless runtime for Mongo, the lanes in `pct/BUILD.bazel` and `//gates:heavy`, the five suite classes with every test pinned (the first measurement: the target refuses everything), channel B's pins | the lane runs end to end and every pin is dated | about 800 lines |
| **PR3** | the target, scalar half | `MongoDb` with its passes and `CAPS`, `$documents` and `$project`, the 206-arm function table and the 21-arm aggregate table over literal relations, parameters as `let`, the wires (D10), the raise envelope; the Essential and Grammar suites burned down; the capability matrix regenerated with the Mongo column | the Essential and Grammar lanes, both channels; the matrix diff test; every other lane's SQL unchanged | 4,000 to 6,000 lines (Postgres is 2,343, ClickHouse 2,880, SQLite 3,156; a pipeline is more text per shape) |
| **PR4** | the target, relation half | `$match`, `$group`, `$sort`/`$skip`/`$limit`, `$lookup` joins in every kind, `$unionWith`, `$setWindowFields`, subselects as sub-pipelines, `PivotAsGroupBy` and `AsOfAsPick` applied, the TDS, CSV and JSON wires over relations; the Relation, Standard and Unclassified suites burned down | the three lanes, both channels; the matrix and `reach.tsv` re-measured | about 3,000 lines |
| **PR5** | the store and the mapping | `###MongoDB` compiled as a store (D6), `#>{db.Collection}#`, the `MongoDB` class mapping over it, `MongoDBConnection` with its authentication, the server's resolver, seeding through the bulk load, the system database on Mongo (or its named refusal), DataCube's catalog through `catalogType` | lite's own Mongo cases (a `lite/*.yaml` set as the SQL lines keep), each judged by the same Pure query over the same rows on DuckDB and on Postgres (the cross-database differential, rows compared); zero FAIL, zero BUG, zero ERROR | about 2,500 lines |
| **PR6** | the corpus and the engine's cases | the corpus's 77 covering tests on the Mongo lane through the eighth line's harness (a `MongoDbBackend` of Phase 6), then whatever of the corpus reaches a `###MongoDB` store; the engine's own MongoDB store test cases from the pinned tree as test input (its expected pipelines are not the spec; its expected answers are), each passing or refused by name | the rosters, pinned and shrinking | about 1,500 lines |
| later | the product opens Mongo (the server's driver set, `//core:drivers`), DataCube on a Mongo store, the recursion native's Mongo rule (`$graphLookup`), Elasticsearch as the second non-SQL target over PR1's seam | | |

Total: about 12,000 to 16,000 lines over six landings. PR1 is the only one that touches every database's path, and it
is a pure move proven by the lane dumps; PR2 to PR6 add Mongo's own files and the Mongo arms of exhaustive switches.

---

## 8. The asks to the other lines (to post in `IN_FLIGHT` when the user starts the line)

1. **To the ninth line (the dialect program), for Phase 5 and 6, now.** Design the connector interface with a non-JDBC
   member: `Connectors.of(type)` answers a `DatabaseConnector` whose session is a sealed type (`Jdbc(Connection)`, and a
   second variant the Mongo connector defines), and the connector owns `check`, `version`, `run(RenderedStatement,
   values)`, `bulkLoad`, `raisedMessage`, `openPrivate` and `inMemoryDeclaration`; `RenderedStatement` carries its
   language; `TestBackend.open()` answers that session, not a `Connection`. `Sessions.jdbcProduct(type)` becomes the
   JDBC connector's own, so a type with no JDBC product is not a hole in a shared switch. Whether `SqlDialect` splits a
   `QueryTarget` supertype in Phase 5 (recommended, D2) or Mongo implements `SqlDialect` whole as a first cut is the
   program's call to make with the user; either way `Databases` keeps one exhaustive switch per fact. This program
   owns every file under `sql/dialect/`: Mongo's files there are announced in its section and merged between its phases.
2. **To the fourth line (the database owner), for step 4.** A `PCT_TDS` output of the plan path (`TypedQuery.Output`,
   `PlanMaker`), so PCT channel A can run through `PlanRunner` on a lane with no JDBC session; and the order in which
   the old executor paths go, so Mongo never gains a `ResultSet` reader of its own. The plan's text format and the
   runner's dispatch on a statement's language are its files (`executionplan/ExecutionPlan`, `exec/PlanRunner`,
   `exec/PlanSessions`).
3. **To the eighth line (the corpus on every database).** PR6 waits for its harness (`MinimalCorpus`'s backend behind
   Phase 6's `TestBackend`): a Mongo backend is one more `openWorkspace()` with no referee, the `OTHER_LANE` shape.
4. **To the compiler line.** Nothing in `builtin/`: legend-engine's `DynaFn` table has no Mongo column, so D10's split
   does not reach it; the Mongo natives' meanings are the IR's, decided in the dialect.
5. **To the Studio and DataCube lines.** Nothing until PR5: then a `MongoDBConnection` in the connection form and a
   collection in the catalog (D6).

---

## 9. Risks, and the measurements that settle them (PR0)

1. **The seam is designed JDBC-only** (the one structural risk): Mongo would fork the executor and the harness, the
   debt this plan exists to avoid. Settled by §8.1, before PR1.
2. **Row order of the wire's `$push`**: the manual documents `$setWindowFields` output as ordered by `sortBy`; `$group`
   after `$sort` is commonly relied on and not guaranteed in so many words. Measured: 10,000 sorted rows pushed both
   ways, compared to the sorted order, repeated.
3. **The 16 MB document limit**: a result text past it fails in the server. Measured: the error's code and text, so the
   wall is named; `JSON_PER_ROW` (one document a row) is the form for large results, as streaming is today.
4. **Text forms**: `$toString` of a double (`1.0`, `1e21`, `0.1+0.2`), of a Decimal128 (scale kept?), of a date
   (`$dateToString` with milliseconds and the `+0000` zone text PCT expects), of a Variant (key order, spacing) against
   `Scalars.floatRepr`, `DateFmt` and the JSON key order the SQL dialects produce. Each difference is an emulation or a
   register row.
5. **Null and type order**: BSON's total order and missing-vs-null (D7) against the PCT null tests; measured on the
   collection suite's null cases.
6. **Decimal128** (34 digits, rounding mode) against the PCT decimal cases that DuckDB passes at 38.
7. **Regular expressions**: PCRE2 against the PCT patterns (`REGEXP_EXTRACT_ALL`, lookarounds, `\d` classes).
8. **The raise envelope** (§5.1): whether a failing `$convert` carries the message text whole in the command error.
9. **`$documents` with `let` at the database level** (`aggregate: 1`): measured, since the PCT's storeless expressions
   depend on it.
10. **The authentication block**: the engine's `MongoDBConnection` grammar has no "none" form; whether the lane creates a
    user at start or lite accepts a declared lite extension (`# None {}#`, a register row) is decided by what the
    grammar accepts (read in the pinned tree) and measured on the embedded server.
11. **The system database on Mongo** (the metamodel store as collections): the cost of seeding per graph on a server
    engine was measured for the SQL servers (the ClickHouse session's limits, §0 of the dialect plan); Mongo's
    session-private database is cheaper to drop but the seed is the same size. Refusal by name is the honest first
    answer if the measurement says so.
12. **Licence posture** (D8): the user's confirmation.
13. **Size**: a pipeline is more text per IR shape than SQL; PR3's estimate is the widest. The capability matrix keeps
    the count honest: every arm is decided, spelled or refused, and the pages show which.

---

## 10. Sources

Read in this tree at 59b547a (main, 2026-10-16), each cited above by path:

- `AGENTS.md` (the invariants: 3, 3a, 4, 7, 8; the execution and reference-checkout tenets);
  `docs/TENET_CHARTER.md` (Clauses 1, 2, 6); `docs/WORLD_MAP.md`.
- `docs/DIALECT_LEAK_CENSUS_AND_PLAN_2026_10_12.md` (§0 the handoff and the proof method; §2 the verdict; §4 the
  target; §5 the phases, 0 to 4 done; §6 decisions D1 to D11, P1b); `docs/dialect-capability/README.md`;
  `docs/IN_FLIGHT.md` (lines 3, 1183 to 1300 the eighth line, 1301 to 1482 the ninth line, 1689 the rules).
- `docs/CLOUD_BACKENDS_PCT_2026_10_11.md` (§2 the order, §11 the candidates and the non-SQL stores, §16 channel B,
  §19 ClickHouse, §20 the corpus on every database); `docs/CYPHER_PLAN_2026_10_15.md` and
  `docs/CYPHER_STUDY_2026_10_15.md` §0 (the method: a spec, an oracle, a ledger, refusals by name);
  `docs/EXECUTION_PLAN_BOUNDARY_2026_10_05.md` (P3, §10 E); `docs/PLAN_EXECUTION_SPLIT_AND_DATABASE_OWNER_2026_10_03.md`;
  `docs/STORE_TYPES_HOMEWORK_2026_10_02.md`; `docs/SEMANTICS_REGISTER.md` (S43, S48, S63); `docs/GATES.md` (the
  lanes); `docs/CLOUD_SESSIONS.md`; `docs/PCT_AUDIT.md` §1.
- Code: `sql/dialect/SqlDialect.java`, `AnsiSqlRenderer.java` (`passes`), `SqlWriter.java`, `RenderedStatement.java`,
  `DialectCapability.java`; `sql/SqlSource.java`, `SqlSelect.java`, `SqlExpr.java`, `SqlFn.java`, `SqlAgg.java`;
  `database/Databases.java`, `Target.java`; `model/ConnectionDefinition.java`; `exec/Sessions.java`, `Executor.java`,
  `PlanRunner.java`, `BulkLoad.java`; `executionplan/ExecutionPlan.java`; `PlanMaker.java`; `Compiler.java`
  (`dialectOf`, `executesOn`); `lowering/WireRender.java`, `Render.java` (`pctTds`), `PctTdsWrap.java`;
  `test/StorelessRuntime.java`; `parser/section/MongoDBSectionGrammar.java`, `MappingProtocolParser.java`,
  `protocol/Protocol.java`, `ConnectionReader.java`, `model/FromProtocol.java`, `MappingFromProtocol.java`;
  `core/src/test/java/com/legend/ArchitectureTest.java` (`JDBC_AND_DRIVERS`, the funnel rules), `DatabaseOwners.java`;
  `pct/BUILD.bazel`, `pct/.../extension/PctBackend.java`, `PctExecuteNative.java`, `channelb/ChannelBLane.java`,
  `Test_LegendLite_ClickHouse_EssentialFunctions_PCT.java`, `PctCensusGate.java`;
  `testing/.../EmbeddedClickHouse.java`, `tools/clickhouse/clickhouse.bzl`, `tools/deps/jars_table.bzl`,
  `core/BUILD.bazel` (`drivers`, `duckdb_load`), `gates/BUILD.bazel`; `scripts/parser/fixtures/t2-mongodb.pure`.
- MongoDB facts (the manual, from memory, each marked "re-check at the pin" and settled by PR0): the version floors of
  §4 D9; `let` on `aggregate`; `$documents`; `$setWindowFields`; Decimal128; the 16 MB document limit; PCRE2; no
  digest or recursion operators in the expression language; `$function` as server-side JavaScript. Nothing from
  MongoDB's documentation or code enters the repository.
