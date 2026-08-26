# A25 — StatementExecutor (K-phase orchestrator) + Compiler surfaces

Scope read IN FULL: `core/src/main/java/com/legend/StatementExecutor.java` (3085 lines),
`com/legend/Compiler.java` (729), `com/legend/exec/{ResultShape,QueryPlan,ExecutionResult,Column,Row,
PostProcessBoundary,PctProbe,GridProbe,StoreNav}.java`, `com/legend/exec/Executor.java` (the envelope
producer), `compiler/spec/{ResultEnvelopeSplice,ExecuteChainAssembly,VerdictQueries}.java`,
`lowering/{SnapshotEnvelope,CheckedEnvelope,SeedableLets,PlanParams}.java`, plus
`MetamodelSteps.java`, `resolver/RawGridSchema.java`, `model/Function.java`,
`protocol/TypeExpression.java` (reached by the findings).

All probes live in `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a25/`
and were run with `/home/user/probe/jrun.sh`. Fixture: `/home/user/probe/fx/model.pure` +
`fx/ddl.sql`, runtime `test::TestRuntime`, DuckDB in-memory.

---

## FINDINGS

### [UNSOUND] `toOne()` / declared multiplicity is NEVER enforced on a GRAPH result

**Evidence.** `StatementExecutor.java:2816-2828`:

```java
private static void enforceToOneReader(TypedSpec root, ExecutionResult res) {
    if (root instanceof ...TypedNativeCall tw
            && com.legend.builtin.Pure.isToOneCall(tw.callee().qualifiedName())
            && ...
            && res instanceof ExecutionResult.Tabular tab      // <-- TABULAR ONLY
            && tab.rows().size() != 1) {
        throw new IllegalStateException("toOne() over a relation returned " ...);
    }
}
```

The GRAPH arm of the egress (`exec/Executor.java:394-401`) reads at most one row and never consults
`rootType.multiplicity()` — unlike the SCALAR arm (`Executor.java:302-308`, zero-row lower-bound check)
and the COLLECTION arm (`Executor.java:370-391`, lower-bound + drop check). So a class-rooted
(GRAPH-shaped) result has NO cardinality enforcement in either direction.

**Repro** (`T.java`, fixture model/ddl):

```
---- model::Person.all()->toOne()
   [G]  model::Person[[1]]  node=TypedNativeCall
   [K]  Graph returnType=model::Person cols=[json:String[null] ]
        rows=([{"firstName":"John",...},{"firstName":"Jane",...},{"firstName":"Bob",...}]{String} )

---- model::Person.all()->filter(p|$p.age > 999)->toOne()
   [G]  model::Person[[1]]  node=TypedNativeCall
   [K]  Graph returnType=model::Person cols=[json:String[null] ] rows=([]{String} )
```

Phase G declares **exactly one** Person; the runtime value holds **three** in the first case and
**zero** in the second. Neither is reported. The same shape over a relation
(`->project(~[a:p|$p.age])->toOne()`) correctly throws, and over a scalar collection
(`->map(p|$p.age)->toOne()`) the SQL throws. Only GRAPH is unguarded.

**Why it matters.** This is the top-prize class: a static type the runtime value violates, silently,
on input a user writes every day (`->toOne()` after a filter is the canonical single-object fetch).

---

### [UNSOUND] A GRAPH result's `returnType` is the ROOT class, not the class actually serialized

**Evidence.** `StatementExecutor.java:2691-2692`

```java
com.legend.compiler.element.type.ExprType shapeInfo =
        declaredInfo != null ? declaredInfo : root.info();
```

`root` here is the POST-`StoreResolver` root (the resolver's serialize envelope), whose `info().type()`
is the extent's class. It is handed to `Executor.execute(...)` and lands verbatim in
`new ExecutionResult.Graph(json, rootType.type())` (`Executor.java:394-401`).

**Repro.**

```
---- model::Person.all()->map(p|$p.addresses)
   [G]  model::Address[[*]]
   [K]  Graph returnType=model::Person cols=[json:String[null] ]
        rows=([{"street":"123 Main St","city":"New York"},{"street":"456 Oak Ave","city":"Boston"},
               {"street":"789 Main Rd","city":"Chicago"},{"street":"999 Pine Lane","city":"Detroit"}])
```

Every object in the JSON is an `Address` (street/city); the envelope says the result type is
`model::Person`. `->from(model::PersonMapping, test::TestRuntime)` does not fix it (the `declaredInfo`
rescue at `StatementExecutor.java:2436-2444` is only consumed for the `collectionDeclared` case at 2596).

`Compiler.plan()` reports the same wrong type:

```
  compileQuery     rootType= model::Address[[*]]
  plan             rootType= model::Person[[*]]  shape=GRAPH
  execute        = Graph returnType=model::Person ...
```

**Why it matters.** Directly answers the orchestrator's note: `compileQuery` and `plan`/`execute`
DISAGREE on the root ExprType here. A bridge that decodes the graph against `returnType()` builds
`Person` instances out of `Address` JSON.

---

### [UNSOUND] `walkResult` picks the result VARIANT from the Java runtime class; plan-walk `toOne` is a no-op

**Evidence.** `StatementExecutor.java:1708-1715`:

```java
private static ExecutionResult walkResult(Object w, Type declared) {
    if (w instanceof java.util.List<?> l) {
        return new ExecutionResult.Collection(new java.util.ArrayList<>(l), declared);
    }
    return new ExecutionResult.Scalar(w, declared);
}
```

The Collection-vs-Scalar decision is made from the JAVA class of the walked value; the Phase-G
multiplicity is never read. Compounding it, `MetamodelSteps.java:49-54`:

```java
case "meta::pure::functions::lang::cast",
        "meta::pure::functions::multiplicity::toOne",
        "meta::legend::lite::trustOne",
        "meta::pure::functions::multiplicity::toOneMany" -> {
    return recv;                       // <-- toOne is a NO-OP on the walk channel
}
```

**Repro.**

```
---- store::PersonDatabase.schemas->toOne().tables->toOne().name
   [G]  String[[1]]  node=TypedPropertyAccess
   [K]  Collection returnType=String cols=[value:String[null] ] rows=(T_PERSON{String} )(T_ADDRESS{String} )

---- store::PersonDatabase.schemas->toOne().tables->at(0).columns->toOne()
   [G]  meta::relational::metamodel::Column[[1]]
   [K]  Collection ... 5 values (ID, FIRST_NAME, LAST_NAME, AGE_VAL, PRIMARY_ADDR_ID)

---- store::PersonDatabase.schemas->toOne()
   [G]  meta::relational::metamodel::Schema[[1]]   (ResultShape.of => SCALAR/GRAPH)
   [K]  Collection(...)                            (variant disagrees even when the count is right)
```

`String[1]` comes back as a 2-element `ExecutionResult.Collection`. `Column[1]` comes back as 5.

---

### [UNSOUND] A scalar-collection result carries a RELATION `returnType` (the `u_map__` channel)

**Evidence.** `ResultShape.of(ExprType)` special-cases the single synthetic `u_map__` column and
returns COLLECTION (`exec/ResultShape.java:48-52`), but nothing un-relations the TYPE. In
`executeTyped` (`StatementExecutor.java:2436-2444`) `declaredInfo` is captured ONLY while unwrapping a
`TypedFrom` wrapper; without one, `shapeInfo = root.info()` (line 2692) is the post-H relation type.

**Repro.**

```
---- model::Person.all()->map(p|$p.age)
   [G]  Integer[[*]]
   [K]  Collection returnType=Relation<(u_map__age:Integer[1])>
        cols=[value:Relation<(u_map__age:Integer[1])>[null] ] rows=(30{Integer} )(28{Integer} )(45{Integer} )

---- model::Person.all()->map(p|$p.age)->from(model::PersonMapping, test::TestRuntime)
   [K]  Collection returnType=Integer cols=[value:Integer[null] ] rows=(30)(28)(45)   <-- CORRECT

---- model::Person.all()->at(0).firstName
   [G]  String[[1]]
   [K]  Scalar returnType=Relation<(u_map__firstName:String[1])> rows=(John{String} )
```

The declared cell type is `Relation<...>`; the actual cell is a bare `Integer`/`String`. The same
query with and without `->from(...)` reports two different `returnType`s — an INCONSISTENCY on top of
the unsoundness. `Compiler.plan()` propagates it too:
`plan rootType= Relation<(u_map__age:Integer[1])>[[*]]  shape=COLLECTION` — a self-contradictory
`QueryPlan` (relation type, collection shape).

---

### [UNSOUND / INFORMATION-LOSS] `Tabular.returnType()` and `Tabular.columns()` disagree on ARITY

**Evidence.** `exec/Executor.java:695-706`:

```java
final Type.RelationType schema = tabularSchema(rootType);
int n = rs.getMetaData().getColumnCount();
List<Column> columns = resolveColumns(rs, plan, schema, n);
...
return new ExecutionResult.Tabular(columns, rows, rootType.type());   // rootType, not `columns`
```

`columns` may be RECONSTRUCTED from JDBC (two of the three branches of `resolveColumns`), but
`returnType` is always the un-updated typed-HIR type. Consumers that read
`Type.schemaView(r.returnType())` — including `Compiler.wireSchema` (`Compiler.java:444-455`) and
`ExecutionResult.envelopeCarriers` (`ExecutionResult.java:39-46`) — see the stale schema.

**Repro A — dynamic pivot** (model `piv.pure`, DuckDB):

```
---- #>{test::DB.T_TREES}#->pivot(~CITY, ~total : x|$x.TREES : y|$y->sum())
   [G]  Relation<(YR:Integer[1], W:Float[0..1])>[[1]]
   [K]  Tabular returnType=Relation<(YR:Integer[1], W:Float[0..1])>          <-- 2 columns
        cols=[YR:Integer[null] W:Float[null] 'NYC__|__total':Integer[null] 'SF__|__total':Integer[null] ]
        rows=(2011 1.5 100 null)(2011 2.5 null 50)(2012 3.5 120 null)         <-- 4 cells/row
```

**Repro B — late-bound raw grid**:

```
---- {| let c = ^...TestDatabaseConnection(type=...DuckDB);
        executeInDb('select 1 as a, 2.5 as b, true as c', $c, 0, 1000);}
   [G]  Relation<()>[[1]]  node=TypedRawSqlRelation
   [K]  Tabular returnType=Relation<()>                                       <-- ZERO columns
        cols=[a:Any[[0..1]] b:Any[[0..1]] c:Any[[0..1]] ]                     <-- 3, all from JDBC
        rows=(1{Integer} 2.5{BigDecimal} true{Boolean} )
```

Column names/types here come entirely from `rs.getMetaData()` (`Executor.java:734-739`) — the
`Any[0..1]` "trust-name" reconstruction — and the multiplicity `[0..1]` is HARDCODED
(`Type.RelationType.trustedColumn`, `GridProbe.probeTypedColumns:54-59`) regardless of nullability.

---

### [INFORMATION-LOSS] `Column.multiplicity()` is DROPPED on every scalar/collection/graph envelope and on every pivot column

**Evidence.**
- `ExecutionResult.java:83-85` — `Scalar.columns()` = `List.of(new Column("value", returnType))` — the
  2-arg ctor, `multiplicity == null` (`Column.java:20-22`).
- `ExecutionResult.java:105-108` — `Collection.columns()`, same.
- `ExecutionResult.java:142-145` — `Graph.columns()` = `List.of(new Column("json", Type.Primitive.STRING))`
  — the type is STRING while `returnType()` is the class; multiplicity null.
- `Executor.java:766` — the PIVOT branch: `columns.add(new Column(name, pivotColumnType(schema, name, sqlType)))`
  — 2-arg ctor. The positional branch two lines up (`Executor.java:746-747`) DOES carry it.

So the same static column reports `a:Integer[1]` in a plain project and `YR:Integer[null]` when the
query happens to contain a pivot. Measured over 26 queries: every SCALAR/COLLECTION/GRAPH result and
every pivot result had at least one null-multiplicity column; only the positional TABULAR branch is
sound (`Diff.java` output, `MULT-NULL` flag).

---

### [SILENT FALLBACK / DATA LOSS] `executeWire` and `executeStreaming` silently DROP dynamic pivot columns

**Evidence.** `DynamicPivot.staticize` is applied only in `StatementExecutor.lowerAndPrepare`
(`StatementExecutor.java:2329-2331`). `Compiler.lowerQuery` (`Compiler.java:345-368`) — the shared
front for `plan`, `planStreaming`, `executeStreaming`, `executeWire` — never calls it, and the wire
schema is built from `Type.schemaView(root.info())` (`Compiler.java:432-433, 444-455`), which holds
only the static group-by columns.

**Repro** (`Surfaces.java`, same pivot query):

```
  execute        = Tabular ... cols=[YR W 'NYC__|__total' 'SF__|__total']
                   rows=(2011 1.5 100 null)(2011 2.5 null 50)(2012 3.5 120 null)
  executeStreaming=[{"YR":2011,"W":1.5},{"YR":2011,"W":2.5},{"YR":2012,"W":3.5}]
  executeWire(CSV)=cols=[YR, W] body=YR,W 2011,1.5 2011,2.5 2012,3.5
  executeWire(JSON)=cols=[YR, W] body=[{"YR":2011,"W":1.5},...]
```

The two aggregate columns — the entire point of the pivot — are gone from the wire and the stream,
with no error. Repo rule "NO FALLBACKS. NO DEFAULTING." — this is a silent drop of DATA, not just of
a type.

---

### [INCONSISTENCY] `executeWire`'s declared column names disagree with the wire body's keys; CSV fails where JSON succeeds

**Evidence.** `Compiler.java:432-439` returns `schema.columns().stream().map(Column::name).toList()`
from `wireSchema(l.root().info())`, while the BODY is rendered by `WireRender` from the lowered plan's
output aliases. When the root is a `->from`-wrapped scalar map, the two are computed from different
things.

**Repro.**

```
QUERY: model::Person.all()->map(p|$p.age)->from(model::PersonMapping, test::TestRuntime)
  executeWire(JSON)=cols=[value]  body=[{"u_map__age":30},{"u_map__age":28},{"u_map__age":45}]
  executeWire(CSV) =ERR NotImplementedException: csv wire: output column 'u_map__age' has no typed
                    relation column (dynamic-column plans are not wire-rendered yet)

QUERY: model::Person.all()->map(p|$p.age)          (no ->from)
  executeWire(JSON)=cols=[u_map__age] body=[{"u_map__age":30},...]     <-- leaks the SYNTHETIC name
  executeWire(CSV) =cols=[u_map__age] body=u_map__age 30 28 45
```

`Compiler.java:443-445` documents the contract: "a scalar/collection root is the one-column `value`
relation (the scalarRoot contract)". The JSON body honours it in neither case; the declared columns
honour it in one case and not the other; and CSV hard-fails on the case where JSON works.

Related: for a GRAPH root, `executeWire` returns `java.util.List.of()` for the columns
(`Compiler.java:430`) — the caller gets an empty column list for a non-empty result.

---

### [INCONSISTENCY] `execute()` lowers with `withEngineExistsJoinForm` UNCONDITIONALLY; every other surface gates it on `!temporalRoot`

**Evidence.** `StatementExecutor.java:2316-2319`:

```java
com.legend.lowering.Lowerer lowerer = new com.legend.lowering.Lowerer(
        t -> ...ClassLayouts.layoutOf(ctx, t, identity),
        f -> ctx.findClass(f).isPresent()).withEngineExistsJoinForm();   // <-- always
```

vs `Compiler.java:353-363` (`plan`/`planStreaming`/`executeStreaming`/`executeWire`) and
`Compiler.java:654-666` (`lowerResolved`):

```java
boolean temporalRoot = ...Temporal.anyTemporalGetAll(body, ctx);
...
if (!temporalRoot) { planLw = planLw.withEngineExistsJoinForm(); }
```

`grep -n "Temporal" StatementExecutor.java` shows the ONLY temporal check in the executor is at
line 513, inside the engine-TEXT (`toSQLString`/`planToString`) path — the execution path never
computes it.

**Repro** (model `temp.pure`, a `<<temporal.businesstemporal>>` class):

```
QUERY: q::Product.all(%2015-08-20)->filter(p|$p.classification->isNotEmpty())->project(~[n:p|$p.name])

  plan.sql / planStr.sql / lowerResolved(rrf=false|true) =
    SELECT t0.name AS n FROM ProductTable AS t0
    WHERE EXISTS (SELECT * FROM ClassificationTable AS t1
                  WHERE t0.type = t1.type AND t1.from_z <= DATE '2015-08-20' AND t1.thru_z > ...)
      AND t0.from_z <= ... AND t0.thru_z > ...

  LL_TMP_SQL=1 execute [exec-sql] =
    SELECT t0.name AS n FROM ProductTable AS t0
    LEFT OUTER JOIN ( SELECT DISTINCT t1.type FROM ClassificationTable AS t1
                      WHERE t1.from_z <= DATE '2015-08-20' AND t1.thru_z > ... ) AS t2
      ON t0.type = t2.type
    WHERE t2.type IS NOT NULL AND t0.from_z <= ... AND t0.thru_z > ...
```

`Compiler.java:294-300` documents `plan` as "the plan half of `execute`: the same pipeline ...
WITHOUT executing". For every temporal query the SQL you inspect is NOT the SQL that runs, and
`executeStreaming`/`executeWire` genuinely EXECUTE the other form. (I did not find a *value*
divergence between the two forms — the DISTINCT-key rewrite looks row-count preserving — so this is
ranked INCONSISTENCY + DOC-LIE rather than UNSOUND.)

---

### [DEFECT + DOC-LIE] `Function.signatureKey()` embeds the SOURCE POSITION; `Compiler.parseSources` duplicate detection is broken by it

**Evidence.** `model/Function.java:44-65`:

```java
/** THE stable overload identity: qualified name + canonical parameter
 *  spellings. Unique across the native catalog ... and stable across parses ... */
default String signatureKey() {
    StringBuilder key = new StringBuilder(qualifiedName()).append('(');
    for (var p : parameters()) {
        key.append(p.type()).append(':').append(p.multiplicity()).append(',');
    }
    return key.append(')').toString();
}
```

`p.type()` is a `protocol.TypeExpression`. `TypeExpression.NameRef` (`protocol/TypeExpression.java:73-92`)
overrides `equals`/`hashCode` to exclude `pos` but does **not** override the record's auto-generated
`toString()` — so the position IS in the string.

`Compiler.java:188-193` builds its module dedup key with the same spelling:

```java
String key = el instanceof FunctionDefinition fd
        ? "Function::" + fd.qualifiedName() + "(" + fd.parameters().stream()
                .map(pd -> String.valueOf(pd.type()) + String.valueOf(pd.multiplicity())) ...
```

**Repro** (`SigKey.java`, `SigKey2.java`):

```
A: my::f(NameRef[name=String, pos=SourceInfo[sourceId=, startLine=1, startColumn=19, ...]]:[1],)
B: my::f(NameRef[name=String, pos=SourceInfo[sourceId=, startLine=4, startColumn=22, ...]]:[1],)

identical offsets: duplicates=[Function::my::f(NameRef[... startLine=1 ...][1]) (f2.pure, kept f1.pure)] kept=1
shifted offsets:   duplicates=[] kept=2
buildModel THROW ModelException: function 'my::f' is defined more than once with the same signature
no-param shifted:  duplicates=[Function::my::g() (g2.pure, kept g1.pure)] kept=1
```

So `Compiler.java:196-198`'s claim — "FIRST definition wins (the corpus carries alternative models in
parent directories); the drop is REPORTED, never silent" — holds only for zero-parameter functions and
for parameterized functions that happen to sit at byte-identical offsets in both files. Everything else
falls through and the module compile hard-fails.

Same key is the `containsEffect` memo key (`StatementExecutor.java:2210`) and the effectful-recursion
frame key (`StatementExecutor.java:2178`). There it makes the key *over*-specific (no false merging);
within one `ModelContext` the definition object is shared, so recursion detection still works — but the
javadoc's "stable across parses" is false.

---

### [UNSOUND — analysis] `effectMemo` poisoning: a transitively EFFECTFUL function is classified PURE

**Evidence.** `StatementExecutor.java:113` creates ONE memo per body:
`java.util.Map<String, Boolean> effectMemo = new java.util.HashMap<>();`
and `containsEffect` (`StatementExecutor.java:2202-2263`) breaks cycles by writing `false`:

```java
String key = uc.callee().signatureKey();
Boolean known = memo.get(key);
if (known == null) {
    memo.put(key, false);   // in-progress: cycles score false
    boolean effectful = false;
    for (TypedSpec stmt : specs.compile(uc.callee()).body()) { ... }
    memo.put(key, effectful);
    known = effectful;
}
```

The in-progress `false` is *observed* by an intermediate callee, whose own (now wrong) `false` is then
**cached permanently** in the same memo.

**Repro** (`MemoProbe.java`, package `com.legend`, model `memo.pure`:
`my::a() { my::b(); my::w::exec('Create Table AA(id INT);'); true; }` and `my::b() { my::a(); }`):

```
containsEffect(my::a()) = true
containsEffect(my::b()) = false
--- memo contents ---
  true  <- my::a()
  false <- my::b()
  true  <- my::w::exec(NameRef[name=meta::pure::metamodel::type::String, pos=..]:[1],)
--- fresh-memo (ground truth) ---
containsEffect(my::a()) = true
containsEffect(my::b()) = true
```

With the shared memo `executeStatements` uses, a statement `my::b();` appearing AFTER `my::a();` in the
same body is classified NON-effectful and routes to the pure β-inline path (`StatementExecutor.java:196-199`
falls through to 216-385) instead of the statement-orchestration path.

**Honesty note.** Every end-to-end shape I could build for this also trips a loud downstream wall
(the `UserCallInliner`'s cycle check, or the effectful-recursion guard), so I could not exhibit a
*wrong answer* — only the wrong classification, which is proven above. Rank: latent unsoundness in
the effect analysis.

---

### [CRASH/ICE] Grid LIMIT-0 probe failure escapes as `IllegalStateException`, defeating the declared `throws SQLException`

**Evidence.** `StatementExecutor.java:1848-1858`:

```java
return sql -> {
    try { return com.legend.exec.GridProbe.probeTypedColumns(sql, connection, env.dialect()); }
    catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
};
```

**Repro.**

```
{| let c = ^...TestDatabaseConnection(type=...DuckDB);
   let r = ...executeInDb('select * from NO_SUCH_TABLE_XYZ', $c, 0, 1000); $r.columnNames;}

[K] THROW java.lang.IllegalStateException: java.sql.SQLException: Invalid Input Error: ...
     Catalog Error: Table with name NO_SUCH_TABLE_XYZ does not exist! ...
   at com.legend.StatementExecutor.lambda$gridOracle$6(StatementExecutor.java:1855)
   at com.legend.resolver.RawGridSchema.resolve(RawGridSchema.java:160)
```

`Compiler.execute` declares `throws java.sql.SQLException`; a caller that catches `SQLException`
around it will not catch this. A plain SQL typo in a grid query becomes an internal error.
Same wrapping at `StatementExecutor.java:2063-2065` (`spliceHook.inlineExecute`).

---

### [CRASH/ICE] Effectful recursion surfaces as `IllegalStateException`; and the recursion guard is RESET on the `execute()` runtime-argument path

**Evidence.** `StatementExecutor.java:2178-2183`:

```java
String key = call.callee().signatureKey();
if (frames.contains(key)) {
    throw new IllegalStateException("recursive effectful call: " + call.callee().qualifiedName());
}
```

**Repro** (`memo.pure` + `{| my::top();}` where `my::top` calls `my::a` which mutually recurses with `my::b`):

```
[THROW] java.lang.IllegalStateException: recursive effectful call: my::a
    at com.legend.StatementExecutor.executeCallStatement(StatementExecutor.java:2181)
    at com.legend.StatementExecutor.executeStatements(StatementExecutor.java:198)     (x4)
```

An `IllegalStateException` (not a `com.legend.error.*` user error) for source a user can write.

**Guard reset (code citation, not reproduced end-to-end).** `StatementExecutor.java:1927-1935`:

```java
if (n instanceof ...TypedUserCall uc) {
    if (containsEffect(uc, specs, new java.util.HashMap<>())) {          // FRESH memo
        executeCallStatement(uc, letPrefix, specs, env,
                new java.util.ArrayDeque<>());                            // FRESH frame stack
    }
```

An effectful user call inside an `execute(f, m, runtime)` RUNTIME argument starts with an empty frame
stack, so the recursion guard cannot see the enclosing frames — a cycle reached through that path
recurses until `StackOverflowError` instead of hitting the guard. (I could not build a corpus-shaped
`execute()` runtime argument in the time available; reported as a code-path risk.)

**PURE recursion is sound** — see VERIFIED SOUND.

---

### [SILENT FALLBACK] `streamStoreOf` swallows `NotImplementedException` and substitutes the class FQN as the STORE key

**Evidence.** `StatementExecutor.java:2145-2153`:

```java
if (x instanceof ...TypedGetAll ga) {
    try { return com.legend.lineage.ScanRelations.rootImpl(ctx, mappingFqn, ga.classFqn())[2]; }
    catch (com.legend.error.NotImplementedException e) { return ga.classFqn(); }
}
```

This value is the store IDENTITY used by (a) `supportsStream` (`StatementExecutor.java:2085-2096`,
which disqualifies a plan parameter when its usages span >1 store) and (b) `crossDbTdsPlan`
(`StatementExecutor.java:764-766, 794-796`), which decides whether a TDS join is CROSS-STORE and must
be split into `Allocation` nodes. A store-lookup failure silently produces a *distinct pseudo-store*,
so an intra-store join can be classified cross-store (or two failures can collide) — a wrong plan
shape produced from a swallowed error. Repo rule: "NO FALLBACKS."

Note also `StatementExecutor.java:2156` `return "";` for a chain with no `TypedGetAll` — and
`crossDbTdsPlan` guards on `!a.isEmpty() && !b.isEmpty()`, so the empty string is at least not
conflated. The `classFqn()` fallback is not guarded.

---

### [SILENT FALLBACK] `planDialect` defaults ANY unknown `DatabaseType` to the H2 engine-style renderer

**Evidence.** `StatementExecutor.java:1126-1138`:

```java
return switch (dbType) {
    case "DB2", "Composite" -> new ...EngineStyleDB2(quote, tz);
    default               -> new ...EngineStyleH2(quote, tz);
};
```

Contrast `toSqlString` two hundred lines above (`StatementExecutor.java:439-448`), which is LOUD for
the same question (`default -> throw new NotImplementedException("toSQLString for DatabaseType." + db ...)`).
The plan-text surface silently renders Snowflake/Postgres/BigQuery connections as H2.

---

### [SILENT FALLBACK] `PctProbe` types every non-DATE/non-TIMESTAMP result column as `VARCHAR`

**Evidence.** `exec/PctProbe.java:41-49`:

```java
String tn = md.getColumnTypeName(i) == null ? "" : md.getColumnTypeName(i).toUpperCase();
SqlType slot = tn.equals("DATE") ? SqlType.Scalar.DATE
        : tn.startsWith("TIMESTAMP") ? SqlType.Scalar.TIMESTAMP
        : SqlType.Scalar.VARCHAR;                              // INTEGER, DECIMAL, BOOLEAN, BLOB...
```

This `PlanProbe` feeds `Render.resolveAllDeferredTds` (`StatementExecutor.java:2344-2352`) and
`PctTdsWrap.wrap` (`StatementExecutor.java:2797-2801`) — i.e. it decides the SQL type of
deferred-TDS/pivot columns at the execution boundary. Everything that is not a date silently becomes
text. Compare `Executor.pureOfSqlType` (`Executor.java:868-877`) which is deliberately LOUD on an
unmapped SQL type — two places answering the same question with opposite policies.

Also: `PctProbe` reads `md.getColumnName(i)` while `GridProbe.probeColumns` (`exec/GridProbe.java:76`)
reads `md.getColumnLabel(i)` for the same purpose. On drivers where those differ (H2: `getColumnName`
is the underlying column, `getColumnLabel` is the alias) the two probes name the same columns
differently.

---

### [SILENT FALLBACK] `SeedableLets` swallows EVERY `RuntimeException` from the trial lowering

**Evidence.** `lowering/SeedableLets.java:37-50`:

```java
try { new Lowerer(...).lower(List.of(let, qe.getValue())); out.add(let); }
catch (RuntimeException notScalar) {
    // BROAD BY DESIGN ... ANY lowering failure ... means the same thing: not seedable.
    continue;
}
```

Reached on every `execute()` (`StatementExecutor.java:2325-2327`). A genuine lowering bug (NPE,
ClassCastException, IllegalStateException) inside a let's trial lowering is indistinguishable from
"not seedable", and the binding is dropped from the lowering prefix. Documented as reviewed, but it
is a blanket catch on a soundness-relevant decision.

---

### [SILENT FALLBACK] `planConnOf` drops the plan's `testDataSetupSqls` when the store is unknown

**Evidence.** `StatementExecutor.java:1049-1051`:

```java
String storeFqn = connectionStoreElementOf(rtArg);
com.legend.model.DatabaseDefinition db = storeFqn == null ? null
        : env.ctx().findDatabase(storeFqn).orElse(null);
```

`db == null` makes `sqls` stay `List.of()` (line 1076-1079), so a `TestDatabaseConnection` with a
present `testDataSetupCsv` silently emits an empty `testDataSetupSqls` block instead of failing. The
same `orElse(null)`-into-empty pattern repeats for the `LocalH2DatasourceSpecification` at 1091-1094.

---

### [UNSOUND] `StoreNav` always returns a `Collection`, and returns an EMPTY one for a `[1]`-typed lookup

**Evidence.** `exec/StoreNav.java:146-152`:

```java
public static @Nullable ExecutionResult tryEval(TypedSpec root, Map<String,TypedSpec> lets, ModelContext ctx) {
    List<Object> v = nav(resolve(root, lets), lets, ctx);
    return v == null ? null : new ExecutionResult.Collection(new ArrayList<>(v), root.info().type());
}
```

Multiplicity is never consulted; a missing schema/table returns `List.of()` (`StoreNav.java:186, 195, 202`).

**Repro.**

```
---- meta::relational::metamodel::schema(store::PersonDatabase, 'default')->toOne()
   [G]  meta::relational::metamodel::Schema[[1]]        (ResultShape.of => SCALAR)
   [K]  Collection ... 1 value                          <-- wrong VARIANT

---- meta::relational::metamodel::schema(store::PersonDatabase, 'nosuch')->toOne()
   [G]  meta::relational::metamodel::Schema[[1]]
   [K]  Collection ... rows=                            <-- ZERO values for a [1] type
```

Also `StoreNav.java:235-246`: `resolve` walks let bindings under `guard++ < 32` and then silently
returns the unresolved variable — a silent bound, not an error.

---

### [UNSOUND] The orchestration-handle arms return `Scalar(null, T)` regardless of the declared multiplicity

**Evidence.** `StatementExecutor.java:2622-2681` — four arms, all `return new ExecutionResult.Scalar(null, <type>)`:
2633 (`connectionByElement`), 2640 (cast of it), 2653 (`^Runtime`/`^ConnectionStore`), 2678 (the
TYPE-driven Runtime/ConnectionStore/Connection-subtype rule).

**Repro.**

```
---- {| let rt = ^meta::core::runtime::Runtime(); $rt.connectionStores;}
   [G]  meta::core::runtime::ConnectionStore[[*]]        (ResultShape.of => COLLECTION)
   [K]  Scalar returnType=meta::core::runtime::ConnectionStore rows=(null{-})

---- {| let c = ^...TestDatabaseConnection(type=...DuckDB); $c;}
   [G]  ...TestDatabaseConnection[[1]]
   [K]  Scalar ... rows=(null{-})                        <-- null under a [1] type
```

Both a wrong VARIANT (Scalar where the shape classification says Collection) and a null value under a
required-multiplicity type.

---

### [UNSOUND] The effectful-`map` arm returns `Scalar(null)` for a `[*]`-declared map

**Evidence.** `StatementExecutor.java:2556-2557` / `2569-2571`:

```java
ExecutionResult last = new ExecutionResult.Scalar(null, tm.info().type());
for (Object v : vals) { ... last = executeTyped(one, env); }
return last;
```

**Repro.**

```
{| let c = ^...TestDatabaseConnection(...); let sqls = ['Create Table Z4(id INT);','Insert into Z4 values (1);'];
   $sqls->map(s| ...executeInDb($s, $c, 0, 1000););}
   [G]  meta::relational::metamodel::execute::ResultSet[[2]]     (=> COLLECTION)
   [K]  Scalar returnType=...ResultSet rows=(null{-})
```

An EMPTY source collection also yields `Scalar(null, ...)` where G says `ResultSet[0]`.

---

### [INCONSISTENCY] `execute(...)->size()` folds to `1` unconditionally; `let r = execute(...); $r->size()` only when relation-rooted

**Evidence.** `compiler/spec/ResultEnvelopeSplice.java:207-215` gates the fold on
`fv.relationRooted()`; `ResultEnvelopeSplice.java:216-234` — the inline-`execute` form — does not
("NOT gated on relationRooted(): the query may be class-rooted") and returns `new TypedCInteger(1L, szi.info())`.
The same Pure spelled with a let binding and without gives different answers for a class-rooted query.

---

### [RUNTIME TYPE DECISION] The snapshot graph envelope picks OBJECT-vs-ARRAY from the ROW COUNT

**Evidence.** `lowering/SnapshotEnvelope.java:34-51` emits
`CASE WHEN COUNT(*) = 1 THEN MIN(<obj>) ELSE json_arrayagg(<obj>) END`. The JSON SHAPE of a
`[*]`-declared snapshot therefore depends on the DATA, not the declared multiplicity: a consumer
parsing by the declared type gets a bare object whenever the query happens to return one row.
(Documented as engine `JsonBuilder` parity; recorded here as a data-dependent type decision made
below the type system.)

---

### [BROAD CATCH] `executePlan`'s decline tunnel catches `RuntimeException` and re-executes bare — it will swallow the egress's own soundness walls

**Evidence.** `StatementExecutor.java:2716-2785`. The `catch (java.sql.SQLException | RuntimeException e)`
at 2719 (and again at 2759, 2774) re-runs the query WITHOUT the canon wrap and, at 2778-2782, can
return the host `LiteralFold` value instead. The `RuntimeException` arm will also catch the egress's
deliberate soundness walls — `Executor.java:356` ("NULL cell reached COLLECTION egress"),
`Executor.java:320` ("scalar-shaped result returned more than one row"), `Executor.java:769`
("plan/schema mismatch"), `Executor.java:796` ("a many-valued cell reached a scalar TDS slot") — and
turn a detected lowering defect into a silent re-run. Only fires on verdict/canon lanes
(`rider != null`), which is why I rank it below the others.

---

### [DOC-LIE] `Compiler.plan` is documented as "the same pipeline as `execute`, without executing"

`Compiler.java:294-300`. Falsified three times above: the `withEngineExistsJoinForm` gate, the missing
`DynamicPivot.staticize`, and the missing `RawGridSchema.stamp`/`SeedableLets`/`SqlPostProcessors`/
`CrossStoreGuard`/`DriverPkAppend` (all execute-only — see the phase table below).

---

## Phase-sequence table (task 7, line-by-line diff)

| step | `compileQuery` | `plan` / `planStreaming` | `executeStreaming` | `executeWire` | `lowerResolved` | `execute` / `executeResolved` |
|---|---|---|---|---|---|---|
| `compileModel` (A–F) | yes 724 | yes 347 | yes 347 | yes 347 | caller's ctx | yes 609 |
| G entry | `typeExpression` **725** | `typeQueryBody` 349 | 349 | 349 | 652 | `typeQueryBody` 40 (SE) |
| G½ `UserCallInliner` | **NO** | yes 352 | yes 352 | yes 352 | yes 653 | yes, **per statement, with `spliceHook`** (SE 218) |
| `Temporal.anyTemporalGetAll` | no | yes 353 | yes 353 | yes 353 | yes 654 | **NO** |
| H `StoreResolver` | **NO** | yes 355 | 355 | 355 | 656 | yes (SE 371, 2392, 1907) |
| `.withLetBindings(queryLets)` | – | **NO** | NO | NO | NO | **yes** (SE 372, 2393, 1908) |
| `RelationalRootForm` | – | no | no | no | optional 658 | no (only engine-text path, SE 517) |
| `withEngineExistsJoinForm` | – | `if (!temporal)` 361 | same | same | `if (!temporal)` 664 | **ALWAYS** (SE 2319) |
| `withStreamingGraphRoot` | – | streaming only 364 | yes | no | no | no |
| `withInstanceIds` | – | no | no | no | no | when identity lane (SE 2320) |
| `SeedableLets.withSeedableLetPrefix` | – | **no** | no | no | no | **yes** (SE 2326) |
| `RawGridSchema.stamp` (JDBC probe) | – | **no** | no | no | no | **yes** (SE 2427) |
| `SqlPostProcessors.apply(tableReplace)` | – | **no** | no | no | no | **yes** (SE 2330) |
| `DynamicPivot.staticize` (JDBC probe) | – | **no** | **no** | **no** | no | **yes** (SE 2329) |
| deferred-TDS resolve (`PctProbe`) | – | no | no | no | no | yes (SE 2337-2356) |
| `CrossStoreGuard.check` | – | **no** | no | no | no | yes (SE 376) |
| `DriverPkAppend` | – | no | no | no | no | when option (SE 381, 1914) |
| K-native arms (`executeInDb`, DDL, print, toSQLString, planToString, walk, StoreNav, asserts) | – | – | – | – | – | yes (SE 235-370, 2448-2577) |
| `enforceToOneReader` | – | – | – | – | – | yes, TABULAR only (SE 2615) |

(SE = `StatementExecutor.java`; bare numbers = `Compiler.java`.)

`compileQuery` running `typeExpression` **without** G½ inlining is the reason the orchestrator's
10-query agreement holds for simple queries and breaks for user-call-bearing and
resolver-retyped queries (the `map(p|$p.addresses)` case above).

---

## Dispatch-arm inventory + RUNTIME type decisions (task 1)

**`executeStatements` (SE:108-388)** — arms in order:
`aliasFrame` 124 · eager `execute()` let 133 · effectful let 143 (wall 154) · ordinary let 179 ·
trailing-let-is-its-value 183 · effectful `TypedUserCall` 196 · `AssertVerdicts` 205 · `hostChannel` 211 ·
`toSQLString`/`Pretty` 235 · `planToString` 247 · `replace(planToString…)` 256 ·
`planToStringWithoutFormatting` 279 · `$plan.processingTemplateFunctions` 296 · `planWalk` 346 ·
`assertError` 355 · result-position `execute()` 365 · default → H/lower/execute 371-385.

**`executeTyped` (SE:2417-2617)** — arms in order:
`RawGridSchema.stamp` 2427 · `TypedFrom` unwrap + `declaredInfo` 2438 · `runRuntimeSetups` 2445 ·
`executeInDb` 2448 · `StoreNav` 2456 · `dropAndCreateTableInDb` 2460 · DDL string generators 2468 ·
`orchestrationHandleArm` 2474 (four sub-arms 2630/2635/2642/2661) · DDL-string collection 2481 ·
`print`/`println` 2503 · `setUpDataSQLs` 2529 · effectful `map` 2540 · `dropAndCreateSchemaInDb` 2573 ·
`LiteralFold` 2586 · `lowerAndPrepare` 2594 · `collectionDeclared` 2596 · `executePctTds` 2608 ·
`executePlan` 2613 · `enforceToOneReader` 2615.

**Decisions made from a RUNTIME fact rather than a Phase-G annotation** (each judged for
disagreement with G; the ones I could exhibit are FINDINGS above):

| # | site | decides from | can disagree with G? |
|---|---|---|---|
| R1 | SE:1710 `walkResult` | `w instanceof java.util.List` | **YES — proven** (`String[1]` → Collection of 2) |
| R2 | SE:2548-2554 | the ExecutionResult VARIANT of the map source; `Scalar(null)` → empty list | yes (silently empties an effectful map) |
| R3 | SE:2559 | `v instanceof String` | throws (loud) |
| R4 | SE:2586 `LiteralFold.fold` | host-computed literal value | no disagreement observed |
| R5 | SE:2596-2602 + 2709-2711 | mixes PRE-resolution `from` type with POST-resolution root type; forces `COLLECTION` | **YES** — makes `plan()`'s `rootType`/`shape` pair self-inconsistent |
| R6 | SE:2661-2679 | the root's declared class only; ignores multiplicity | **YES — proven** (`ConnectionStore[*]` → Scalar(null)) |
| R7 | SE:2427 → `Executor.java:734-739` | `rs.getMetaData().getColumnName/TypeName` | **YES — proven** (`Relation<()>` vs 3 JDBC columns) |
| R8 | `Executor.java:855-862` `pivotColumnType` | falls back to `pureOfSqlType(jdbcTypeName)` | yes (JDBC types the column; multiplicity dropped) |
| R9 | SE:2329 `DynamicPivot.staticize` | a DB probe discovers the column NAMES | **YES — proven** (wire/stream drop them) |
| R10 | SE:2337-2356 | `PctProbe` JDBC type names → `pureOfSqlType` | yes (VARCHAR default, above) |
| R11 | SE:2822 `enforceToOneReader` | `tab.rows().size()` | correct for TABULAR; absent for GRAPH — **finding #1** |
| R12 | `Executor.java:294-323, 326-392` | `rs.next()` row counts vs declared bounds | sound where present |
| R13 | `Executor.java:462-465` | `v.getClass().getName().equals("org.duckdb.JsonNode")` → `decodeAny` | class-name sniff re-types the cell |
| R14 | `Executor.java:492-538` `decodeAny` | JSON GRAMMAR of the text at runtime | Long-vs-Double-vs-String decided by spelling |
| R15 | `SnapshotEnvelope.java:34-51` | SQL `COUNT(*) = 1` | **YES** — object-vs-array from the data |
| R16 | `Compiler.java:474-508` `dialectOf` | `connection.getMetaData()` product name + VERSION string (`2.1`/`2.2` → `H2`, else `H2Modern`) | changes the SQL EMITTED, decided by the live session |
| R17 | SE:1994-2031 `aliasFrame` | the runtime frame map | throws on `at(k>0)` (loud) |
| R18 | SE:395-421 `frameReplaceEnv` | the runtime frame map's rename unions | throws on conflict (loud) |
| R19 | `StoreNav.java:148-151` | always `Collection` | **YES — proven** |

Cell values are decoded by the PLAN's `OutputCol` SQL type (`Executor.java:807-830`), never by the
Pure type — the only two exceptions are R7/R8 (late-bound + pivot), exactly as `Executor.java:19-29`
claims.

---

## RESULT ENVELOPE field provenance (task 2)

| shape | `returnType` | `columns[].name` | `columns[].pureType` | `columns[].multiplicity` | `rows` |
|---|---|---|---|---|---|
| SCALAR | `shapeInfo.type()` (SE:2691-2692) — typed HIR, or the pre-resolution `from` type | **invented** `"value"` (`ExecutionResult.java:84`) | = `returnType` | **null** (dropped) | 1 row, cell decoded by plan `OutputCol` (+ `decodeAny` on Any/JsonNode) |
| COLLECTION | same, ELEMENT type — but see the `u_map__` finding | **invented** `"value"` (`:107`) | = `returnType` | **null** | 1 row per value |
| TABULAR | `rootType.type()` (`Executor.java:705`) — typed HIR, NEVER updated from `columns` | (a) typed-HIR schema, positional (`:746`); (b) **JDBC `getColumnName`** when `schema.isLateBound()` (`:734-739`); (c) **JDBC `getColumnName`** + `presentPivotName` on the pivot branch (`:763-766`) | (a) typed HIR; (b) hardcoded `Any`; (c) aggregate TEMPLATE, else **JDBC `getColumnTypeName`** → `pureOfSqlType` (`:855-862`) | (a) typed HIR; (b) hardcoded `[0..1]`; (c) **null** | cells by plan `OutputCol` (null for late-bound/pivot → wire kind decides) |
| GRAPH | `rootType.type()` — the ROOT class (see finding #2) | **invented** `"json"` (`:144`) | **hardcoded `STRING`**, contradicting `returnType()` | **null** | ONE row holding the whole array; `rowCount()` is always 1; `json == null` → `""` (`:149`); zero rows → `"[]"` (`Executor.java:398-400`) |

Fields **reconstructed from JDBC** (the drift surface): TABULAR branches (b) and (c) above; the
deferred-TDS/PCT column types via `PctProbe`; the `RawGridSchema` stamp via `GridProbe`. All three
drift cases are proven in the findings.

---

## Multi-statement bodies (task 3) — mostly SOUND

Tested with `T.java`; all G types from `Compiler.compileQuery`, all K results from `Compiler.execute`.

```
{| let x = 1; }                       G Integer[1]  node=TypedLet     K Scalar(Integer) 1     — trailing let IS its value
{| let x = 'abc'; let y = 2; }        G Integer[1]                    K Scalar(Integer) 2
{| let x = 1; let y = $x + 1; }       G Integer[1]                    K Scalar(Integer) 2
{| let x = 1; let y = $x->toString();} G String[1]                    K Scalar(String) "1"    — last stmt's type IS the body's
{| 1; 2; 3; }                         G Integer[1]                    K Scalar(Integer) 3
{| }                                  ParseException [1:4] "lambda body must contain at least one statement"
function my::empty(): Boolean[1] { }  ParseException [3:1] "Unexpected token '}'"
{| let x = [1,2,3]; $x->toOne(); }    G Integer[1]  K SQLException "Cannot cast a collection of size 3 to multiplicity [1]"  (enforced IN SQL)
{| let x = model::Person.all(); $x->size(); }   G Integer[1]  K Scalar 3
```

- The last statement's type IS the body's type in every case tested, `let`-trailing included.
- Zero-statement bodies are unreachable (clean parse errors), so `executeStatements`' `return null`
  (SE:112/387) — and `Compiler.execute`'s `@Nullable` return — is not reachable from user source.
- A `let` read at a different expected type is enforced (in SQL) rather than silently coerced.
- Effectful statements: `executeInDb` / `dropAndCreateTableInDb` / `dropAndCreateSchemaInDb` are typed
  `ResultSet[1]` / `Boolean[1]` / `Boolean[1]` and returned as `Scalar(null|true, <declared>)`
  (SE:2874, 2961, 2891) — sound. `print`/`println` are typed `Nil[0]` and returned as
  `Scalar(null, Nil)` (SE:2523) — sound (`ResultShape.of(Nil[0])` is SCALAR).
- KNOWN TRADE, verified: a `let` used twice EVALUATES twice
  (`UserCallInliner.java:108-115`). `{| let x = model::Person.all()->project(~[a:p|$p.age]); $x->concatenate($x);}`
  lowers to two independent `FROM T_PERSON` scans (`UNION ALL`) — documented, honest.

---

## Call frames (task 4) — the capture claim SURVIVED

`StatementExecutor.java:104-105` claims "parameters bound as lets; closed bodies make frames
capture-proof — no α-renaming needed". I tried to falsify it and could not:

```
my::caller1: my::w::exec('Create Table CAP(v VARCHAR);'); let x = 'CALLER_X'; my::callee('ARG_LITERAL');
             callee's parameter is also named x   ->  CAP holds 'ARG_LITERAL'      (no capture)
my::caller2: let p = 'FROM_CALLER_LET'; my::callee($p)  ->  CAP holds 'FROM_CALLER_LET'
my::caller3: let x='SHADOW'; let q='Q'; my::callee($q + '_' + $x)  ->  CAP holds 'Q_SHADOW'
my::shadow(x:String[1]) { let x = 'INNER'; $x; }  called with 'OUTER'  ->  'INNER'
```

Mechanism confirmed by reading `UserCallInliner.callArgumentFrame` (`:659-682`): each argument is
inlined **in the caller's scope** and bound as `TypedLet(<param name>, argValue)`; only that frame is
passed as the callee's `letPrefix` (SE:2185-2190), so the caller's other lets are simply not in scope.
`UserCallInliner.inlineBody` always returns a singleton (`:132 return List.of(root);`), so the
`.get(0)` at `callArgumentFrame:668` and at `ExecuteChainAssembly.chain:207` is safe (I checked
specifically for a `.get(0)`-picks-the-wrong-statement bug — there is none).
`reserveFreshNames`/`bumpPast` (`:135-155`) additionally reserves the `_i<N>` α-rename namespace
against user-written `_i0`.

**Recursion:** pure self-recursion (`my::fact`), mutual recursion (`my::even`/`my::odd`) and a
self-loop (`my::selfloop`) all terminate with a clean
`com.legend.error.NotImplementedException: store resolution left user call '…' uninlined` from
`StoreEscapees.check` — **no StackOverflow, no infinite expansion**. Effectful recursion is caught by
the frame guard (as an `IllegalStateException` — see the ICE finding).

---

## VERIFIED SOUND

- **Positional TABULAR columns.** When `n == schema.columns().size()` the name, Pure type AND
  multiplicity all come from the typed HIR (`Executor.java:742-748`) — verified on 6 project/groupBy
  queries (`a:Integer[[1]]`, `c:String[[0..1]]`, `firstName:String[[1]]`, `s:Integer[[1]]`).
- **`executeResolved` == `execute`.** Byte-identical results on all 26 differential queries — the
  driver really does funnel both through `StatementExecutor.execute`.
- **`plan` == `planStreaming` == `lowerResolved(rrf=false)`** for every NON-temporal, NON-pivot query
  tested (7 queries) — same SQL, same rootType, same shape. `planStreaming` differs only in the GRAPH
  root form (`json_object` per row vs `to_json(list(...))`), as designed.
- **`lowerResolved(relationalRootForm=true)`** differs from `false` ONLY for a bare class root
  (`SELECT t0.ID AS pk_0, …` vs the JSON envelope) — exactly the documented contract; identical for
  relation/scalar roots.
- **Multi-statement bodies** — see the table above; the last statement's type is the body's type in
  every case, trailing `let` included; empty bodies are clean parse errors.
- **Call frames** — no capture found; see task 4.
- **Pure recursion** — clean `NotImplementedException`, no StackOverflow.
- **`UserCallInliner.inlineBody` always returns a singleton**, so every `.get(0)` on it is safe.
- **`aliasFrame` / `ResultEnvelopeSplice` `at(k>0)`** — loud (`IllegalStateException`), never a
  silent envelope collapse (SE:2023-2027, `ResultEnvelopeSplice.java:247-254`).
- **`frameReplaceEnv`** — conflicting table renames throw rather than picking one (SE:407-413).
- **`print`/`println` with a nested effect** — a hard error, not a silent drop (SE:2515-2522), verified:
  `IllegalStateException: print/println argument contains an executeInDb-family call`.
- **DDL string generators** (`dropTableStatement`, `createTableStatement`, `dropSchemaStatement`,
  `createSchemaStatement`) — all four return `Scalar(<text>, ds.info().type())` with the Phase-G type,
  and a collection of them returns `Collection(..., STRING)`; the type agrees with G in all four.
- **`ResultShape.of`** — the closed switch is exhaustive over Type shapes; the `u_map__`, Variant and
  Nil special cases all behave as documented (verified against 26 queries: the only VARIANT mismatches
  are the `walkResult`/`StoreNav`/handle-arm findings, never `ResultShape.of` itself).
- **`CheckedEnvelope`** (graphFetchChecked) and **`VerdictQueries`** — read in full; both mint types
  from the typed HIR with no runtime type decision. `VerdictQueries.predicateVector` correctly stamps
  `Boolean[0..*]`.
- **`PostProcessBoundary`** and **`PlanParams`** — read in full; no type decision (thread-local map
  carrier and a one-line delegation to `Fold.planKindOf`).

---

## NOT COVERED

- **`AssertVerdicts` / `AssertErrorNative` / `PureAsserts` / `SeedSqlForms` / `AggAwareActivities` /
  `ConnectionLets` / `CrossStoreGuard` / `PlanAllocations` / `PlanEnvelope` / `ConnectionFlags`** —
  named from `StatementExecutor` but outside the listed scope; I read only their call sites. The
  assert-verdict lane (SE:205-210) and its canon rider are therefore untested end-to-end.
- **`crossDbTdsPlan` / `sequencePlan` / `planToString` / `toSQLString` / plan-node walks** — I read all
  of them and cite two defects (`streamStoreOf` fallback, `planDialect` default) but did not build a
  two-database corpus model to execute them. The plan-text goldens are a large surface I did not
  exercise.
- **`executeStreaming`'s GRAPH path over a streaming lowering with post-processors** — the streaming
  surface never applies `SqlPostProcessors`, but I did not build a `replaceTables` post-processor
  runtime to prove a divergence.
- **The `runRuntimeArgEffects` fresh-frame recursion-guard reset** (SE:1931-1933) — cited from code;
  I could not assemble a corpus-shaped `execute(f, mapping, runtime)` whose runtime argument is a
  recursive effectful helper within the time available.
- **H2 / SQLite dialects.** All execution probes ran on DuckDB. The `Compiler.dialectOf` version sniff
  (`2.1`/`2.2` → `H2` vs `H2Modern`, `Compiler.java:504-507`) is a live-session type decision I read
  but did not exercise; the `getColumnName`-vs-`getColumnLabel` split between `PctProbe` and
  `GridProbe` is most likely to bite on H2.
- **Exhaustiveness caveat.** The differential (`Diff.java`) covered 26 queries, not an exhaustive
  space; the pivot probe covered 4 shapes; the surface diff covered 11 queries across 8 surfaces. I
  sampled because the query space is unbounded — every FINDING above is backed by a specific repro,
  not by the sample.
