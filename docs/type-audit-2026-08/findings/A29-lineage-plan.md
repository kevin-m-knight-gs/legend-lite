# A29 — lineage / plan / validation type audit

Scope: `core/src/main/java/com/legend/lineage/{ScanRelations,ScanColumns,PkInference}.java`,
`core/src/main/java/com/legend/validation/{ValidateDesugar,DriverPkOption}.java`,
`core/src/main/java/com/legend/plan/{PlanText,RelationalMapperRenames,InProtocol,PlanEnumForm,PlanSupportFunctions,PlanNode,PurePrint,PlanConn}.java`.

All probes are under `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a29/`.
Nothing under `/home/user/legend-lite` was modified; `mvn` was never run.

---

## 0. THE COMPLETE `Type -> plan text` MAPPING (task item 1, exhaustive)

`PlanText.pureTypeName` was driven over **every constructible `Type` variant** (all 12 `Primitive`
constants + all 8 non-primitive variants) by direct call (`a29/PT1.java`).
`Type` is a `sealed interface` (`compiler/element/type/Type.java:46-51`), so this is the total universe.

```
Primitive.NUMBER                           OK   -> Number
Primitive.INTEGER                          OK   -> Integer
Primitive.FLOAT                            OK   -> Float
Primitive.DECIMAL                          OK   -> Decimal
Primitive.STRING                           OK   -> String
Primitive.BOOLEAN                          OK   -> Boolean
Primitive.BYTE                             THROW-> NotImplementedException: plan: pure type name for BYTE
Primitive.DATE                             OK   -> Date
Primitive.STRICT_DATE                      OK   -> StrictDate
Primitive.DATE_TIME                        OK   -> DateTime
Primitive.LATEST_DATE                      THROW-> NotImplementedException: plan: pure type name for LATEST_DATE
Primitive.STRICT_TIME                      THROW-> NotImplementedException: plan: pure type name for STRICT_TIME
PrecisionDecimal(10,2)                     THROW-> NotImplementedException: plan: pure type name for PrecisionDecimal[precision=10, scale=2]
PrecisionDecimal(38,18) DEFAULT            THROW-> NotImplementedException: ... PrecisionDecimal[precision=38, scale=18]
PrecisionDecimal(0,0)                      THROW-> NotImplementedException: ... PrecisionDecimal[precision=0, scale=0]
ClassType(model::Person)                   THROW-> NotImplementedException: plan: pure type name for ClassType[fqn=model::Person]
EnumType(model::Country)                   OK   -> model::Country
TypeVar(T)                                 THROW-> NotImplementedException: plan: pure type name for TypeVar[name=T]
GenericType(Relation,[()])                 THROW-> NotImplementedException: plan: pure type name for GenericType[...]
GenericType(List,[Integer])                THROW-> NotImplementedException: plan: pure type name for GenericType[...]
FunctionType({->Integer[1]})               THROW-> NotImplementedException: plan: pure type name for FunctionType[...]
RelationType((a:Integer[1]))               THROW-> NotImplementedException: plan: pure type name for RelationType[...]
RelationType.lateBound()                   THROW-> NotImplementedException: plan: pure type name for RelationType[...]
SchemaAlgebra(T+V)                         THROW-> NotImplementedException: plan: pure type name for SchemaAlgebra[...]
```

**10 arms present** (9 primitives + `EnumType`, `PlanText.java:536-566`); **13 holes** at the terminal
`throw` on `PlanText.java:567`. Of those, three are proven reachable from user-writable Pure (below).

The companion `PlanText.spell(RelationalDataType)` was also driven over **all 21 variants**
(`RelationalDataType` is sealed, `model/RelationalDataType.java:41-98`):
`BigInt/SmallInt/TinyInt/Integer_/Float_/Double_/Real/Bit/Timestamp/Date_/Varchar/Char_/Decimal/Numeric`
render; `Distinct/Other/SemiStructured/Binary/Varbinary/Array/Object_` throw
(`PlanText.java:936-951`). `Distinct/Other/Array/Object_` are unreachable — `StoreCompiler.columnType`
(`compiler/element/StoreCompiler.java:198-206`) rejects them at model compile with a clean
`ModelException`. `Binary/Varbinary` map to `Primitive.BYTE` and wall earlier (see NOT-A-FINDING §B).

---

## FINDINGS

### [CRASH/ICE] `PlanText.pureName` has no `PrecisionDecimal` arm — a `D`-suffixed decimal literal in a plan let is an internal error

**Evidence.** `PlanText.java:557` is the `Primitive.DECIMAL` arm; `PrecisionDecimal` is a *separate*
`Type` variant (`Type.java:47,158`) and has **no arm**, so it falls to
`PlanText.java:567 throw new NotImplementedException("plan: pure type name for " + t)`.
A `CDecimal` literal types to `PrecisionDecimal(38, scale)` (`compiler/spec/Typer.java:154-173`,
`Typer.java:3184`), and `PlanAllocations.node` (`PlanAllocations.java:53-63`) has **no `TypedCDecimal`
arm in its literal switch**, so a decimal let takes the `PureExp` path and calls
`PlanText.pureTypeName(let.info().type())` at `PlanAllocations.java:76-77`.

**Repro** (`a29/dec.pure` + the query below, run through `probe.sh`):
```
meta::pure::executionPlan::toString::planToString(
  meta::pure::executionPlan::executionPlan(
    {|let p = 1.5d; model::Item.all()->project(~[name:i|$i.name])->limit(1);},
    model::ItemMapping, test::ItemRuntime, []), [])
```
**Actual output:**
```
[EXEC-ERROR] com.legend.error.NotImplementedException: plan: pure type name for PrecisionDecimal[precision=38, scale=1]
```
The same query with `let p = 1.5;` (Float) renders the full `Sequence(...Allocation type = Float...)` plan text.

**Adjudication of the orchestrator's forwarded claim.** The claim as stated —
"*`PlanText.java:557,567 pureTypeName` has NO `PrecisionDecimal` arm*" — is **CONFIRMED**.
The consequence attached to it — "*and throws `NotImplementedException` for any DECIMAL store column*"
— is **REFUTED**. Direct call: `pureTypeName(Primitive.DECIMAL)` returns `"Decimal"` (§0 above).
Driving a real plan-text render for a model with a `DECIMAL(10,2)` store column
(`a29/dec.pure`, `PRICE DECIMAL(10,2) NOT NULL`, property `price: Decimal[1]`) **succeeds**:
```
Relational
(
  type = TDS[(name, String, VARCHAR(100), ""), (price, Decimal, DECIMAL(10,2), "")]
  resultColumns = [("name", VARCHAR(100)), ("price", DECIMAL(10,2))]
  sql = select "root".NAME as "name", "root".PRICE as "price" from T_ITEM as "root"
  connection = TestDatabaseConnection(type = "H2")
)
```
Reason: a *class property* declared `Decimal[1]` carries `Primitive.DECIMAL`; only the
*store relation* type carries `PrecisionDecimal` (`StoreCompiler.java:192-193`,
`case RelationalDataType.Decimal d -> new Type.PrecisionDecimal(d.precision(), d.scale())`),
proven by `#>{store::ItemDb.T_ITEM}#->select(~[NAME, PRICE])`:
```
[G] type=Relation<(NAME:String[1], PRICE:Decimal(10,2)[1])>
[EXEC-COL] PRICE : Decimal(10,2) [PrecisionDecimal[precision=10, scale=2]] mult=[1]
```
So the hole is real but the trigger is a decimal *literal/relation* type, not a decimal *store column*.

**Why it matters.** `NotImplementedException` escaping for `1.5d` — the ordinary way to write a decimal
in Pure — is an internal error on input a user plausibly writes.

---

### [CRASH/ICE] Two more reachable `pureName` holes: `%latest` and `%hh:mm:ss` in a plan let

Same site (`PlanText.java:567`), same path (`PlanAllocations.java:53-63` has no `TypedCLatestDate` /
`TypedCTime` arm either; both are minted at `Typer.java:178-180`).

**Repro / actual output** (`a29/dec.pure`, planToString wrapper as above):
```
### QUERY: let p = %latest; model::Item.all()->project(~[name:i|$i.name])->limit(1);
[EXEC-ERROR] com.legend.error.NotImplementedException: plan: pure type name for LATEST_DATE

### QUERY: let p = %12:30:00; model::Item.all()->project(~[name:i|$i.name])->limit(1);
[EXEC-ERROR] com.legend.error.NotImplementedException: plan: pure type name for STRICT_TIME
```

---

### [CRASH/ICE] Computed TDS column over two source columns — plan text is an internal error

`PlanText.tdsTuples` falls to `pureDbSpelling(cols.get(i).type())` for a computed projection when
`uniformCaseColumn` finds more than one distinct source column; `pureDbSpelling`
(`PlanText.java:722-740`) covers only NUMBER/FLOAT/INTEGER/BOOLEAN/STRING and returns `null` for
everything else, which becomes `PlanText.java:419 throw new NotImplementedException("plan: computed TDS column '...' type spelling pending")`.

**Repro** (`a29/enum2.pure`, two enum columns):
```
{|model::Trade.all()->project(~[x:t|if($t.tid > 1, |$t.sideA, |$t.sideB)])}
```
**Actual output:**
```
[EXEC-ERROR] com.legend.error.NotImplementedException: plan: computed TDS column 'x' type spelling pending
```
The one-source variant `if($t.tid > 1, |$t.sideA, |$t.sideA)` renders. Any computed column typed
Decimal / Date / StrictDate / DateTime / Enum-over-two-sources hits the same wall.

---

### [CRASH/ICE] In-protocol constant spelling walls on any non-String/non-Integer collection

`InProtocol.constantsText` (`InProtocol.java:219-226`) handles only `SqlExpr.StringLit` and
`SqlExpr.IntLit`; `default -> throw new NotImplementedException("in-protocol constant spelling for ... pending")`.

**Repro** (`a29/dec.pure`, 60 float literals over the TestDatabaseConnection threshold of 50):
```
{|let z = 1; model::Item.all()->filter(i|$i.price->in([1.5,2.5,...,60.5]))->project(~[name:i|$i.name]);}
```
**Actual output:**
```
[EXEC-ERROR] com.legend.error.NotImplementedException: in-protocol constant spelling for FloatLit pending
```

---

### [SILENT FALLBACK / INFORMATION LOSS] `InProtocol` hardcodes `VARCHAR(1024)` and `type = String` for *every* temp-table collection — the "others wall at the caller" comment is false

`InProtocol.java:161-162`:
```java
String colType = "VARCHAR(1024)";   // String collections — the
// only element kind the goldens pin; others wall at the caller
```
and `InProtocol.java:167-170` `PlanText.allocation(s.tempVar(), "  type = String\n", PlanText.constantBare("String", ...))`.
There is **no wall at the caller**: `PlanEnvelope.emit` (`PlanEnvelope.java:26-31`) calls
`InProtocol.apply` / `allNodeTexts` with no element-type gate.

**Repro** — an **Integer** collection of 60 elements:
```
{|let z = 1; model::Item.all()->filter(i|$i.qty->in([1,2,...,60]))->project(~[name:i|$i.name]);}
```
**Actual output (excerpt):**
```
        Allocation
        (
          type = String                      <-- Integer collection typed String
          name = tempVarForIn_4
          value = ( Constant ( type = String
                values=[1, 2, 3, ..., 60] ) )
        )
        CreateAndPopulateTempTable
        (
          type = Void
          inputVarNames = [tempVarForIn_4]
          tempTableName = tempTableForIn_4
          tempTableColumns = [(ColumnForStoringInCollection, VARCHAR(1024))]   <-- INT column declared VARCHAR
          connection = TestDatabaseConnection(type = "H2")
        )
        Relational ( ... sql = select "root".NAME as "name" from T_ITEM as "root"
          where "root".QTY in (select "temptableforin_4_0".ColumnForStoringInCollection
                               as ColumnForStoringInCollection from tempTableForIn_4
                               as "temptableforin_4_0") ... )
```
`QTY` is `INTEGER`; the plan compares it against a temp column declared `VARCHAR(1024)`.
Two distinct losses: (a) the element Pure type is erased to `String`; (b) the physical column type is
erased to `VARCHAR(1024)` — which also silently truncates any string element longer than 1024 chars.

**Category:** SILENT FALLBACK + INFORMATION LOSS + DOC-LIE (the inline comment's claimed wall does not exist).

---

### [INFORMATION LOSS] `InProtocol` constant lists are unparseable: no quoting, no separator escaping, no type tag

`InProtocol.java:213-229` spells constants RAW (comment: *"Constant values spell RAW (no quotes)"*).
Consequences, both reproduced (`a29/dec.pure`, 60-element string collections):

1. **String vs Integer are indistinguishable.** `['1','2', 's1'...]` renders
   `values=[1, 2, s1, s2, ...]` under `type = String` — byte-identical in the numeric prefix to the
   Integer collection above, which also renders `values=[1, 2, ...]` under `type = String`.
2. **Separator collision.** A member `'a,b'` renders as `a,b`:
```
values=[a,b, c'd, s1, s2, ..., s58]
```
   `'a,b'` and `'c\'d'` are unrecoverable — the list cannot be split back into 60 elements.

No consumer can reconstruct the constant list or its element type from the plan text.

---

### [INCONSISTENCY / wrong SQL] Plan text picks the FIRST-declared enumeration mapping, ignoring which one the property mapping declares — execution picks the right one

`PlanText.enumMappingOf` (`PlanText.java:448-467`) returns the first `EnumerationMapping` matching the
enum FQN; `enumMappingIdOf` (`PlanText.java:469-473`) and `enumMapFnOf` (`PlanText.java:527-532`)
build on it. The disambiguating sibling `enumMappingIdFor` (`PlanText.java:481-516`, which *does*
consult the property mapping's declared `enumMappingId`) is used **only** by `tdsTuples`.
The plan **parameter** path uses the undisambiguated one: `StatementExecutor.java:951-955`
```java
String emFn = p.type() instanceof ...Type.EnumType et
        ? com.legend.plan.PlanText.enumMapFnOf(env.ctx(), mappingFqn, et.fqn())
        : null;
```

**Repro** (`a29/enum2.pure`): one enum `model::Side`, two enumeration mappings
`MapOne {BUY:['B'], SELL:['S']}` and `MapTwo {BUY:['BUY_LONG'], SELL:['SELL_SHORT']}`;
`sideA` uses MapOne over `SIDE_A`, `sideB` uses **MapTwo** over `SIDE_B`.
Query: `{s: model::Side[1]| model::Trade.all()->filter(t|$t.sideB == $s)->project(~[a:t|$t.sideA])}`.

**Actual plan text (excerpt):**
```
sql = select "root".SIDE_A as "a" from T_TRADE as "root" where (${optionalVarPlaceHolderOperationSelector(s,
      equalEnumOperationSelector(enumMap_model_TMapping_MapOne(s),
        '"root".SIDE_B in (${enumMap_model_TMapping_MapOne(s)})',
        '"root".SIDE_B = ${enumMap_model_TMapping_MapOne(s)}'), '0 = 1')})
```
The filter is on `SIDE_B` (MapTwo) but the plan splices **`enumMap_..._MapOne`**, whose freemarker
map (dumped by `a29/EnumProbe.java`) is `{"BUY":"'B'", "SELL":"'S'"}` — so the rendered predicate is
`"root".SIDE_B = 'B'`, and `SIDE_B` only ever holds `'BUY_LONG'`/`'SELL_SHORT'`. **Zero rows, silently.**

The **execution** path gets it right — same model, literal enum:
```
[PLAN] SELECT ... FROM T_TRADE AS t0 WHERE t0.SIDE_B = 'BUY_LONG'
```
Two implementations of "which enumeration mapping applies here" that disagree; the plan surface's is
a first-declared guess. `PlanText.java:514 return candidates.isEmpty() ? null : candidates.get(0).mappingId();`
is the same first-wins guess inside `enumMappingIdFor` whenever the property-mapping search misses.

---

### [SILENT FALLBACK / lossy] `PlanSupportFunctions.enumMapTemplateFunction` does not escape source values

`PlanSupportFunctions.java:101-112` builds the freemarker enum map with
`case StringValue st -> "'" + st.value() + "'"` and joins a value's source list with `", "` into a
**single string**, which the runtime template splits with `enumVal?split(",")`
(`PlanSupportFunctions.java:64`).

**Repro** (`a29/enum3.pure`, source values `O'Brien` and `a,b`), actual output of `a29/EnumProbe.java`:
```
<#function enumMap_model_TMapping_Esc inputVal><#assign enumMap = {"BUY":"'O'Brien'", "SELL":"'a,b'"}> ...
```
* `'O'Brien'` spliced into SQL is `= 'O'Brien'` — a syntax error / injection surface.
* `'a,b'` inside the comma-joined carrier makes `?split(",")` see two source values, flipping
  `equalEnumOperationSelector` from the `=` arm to the `in` arm for a single-valued mapping.

(The **execution** path escapes correctly — same model gives
`CASE WHEN t0.SIDE = 'O''Brien' THEN 'BUY' ...`. The defect is on the plan surface only.)

---

### [UNSOUND] An unmapped enum source value decodes to `null` under a `[1]` (NOT NULL) enum column

Not a plan-text defect but surfaced by the enum probes in this scope (task item 2, "an unmapped value").
`a29/enum.pure`: `SIDE VARCHAR(10) NOT NULL` (so `StoreCompiler.java:170` stamps
`Multiplicity.Bounded.ONE`), property `side: model::Side[1]`, enumeration mapping covers `'B','BOT','S'`.
Row 4 holds `'ZZZ'`.

**Actual output:**
```
[EXEC-COL] side : model::Side [EnumType[fqn=model::Side]] mult=[1]
[EXEC-ROW] Integer(4) | null | null |
```
The compiler statically claims `model::Side[1]`; the runtime yields `null`. Same for the numeric-code
enum (`GRADE = 9`, mapping covers 1 and 2). The decode `CASE` has `ELSE NULL`
(`... ELSE CASE WHEN t0.SIDE = 'S' THEN 'SELL' ELSE NULL END END`) with no
"unmapped value" diagnostic. Also note the runtime Java class for an `EnumType` column is
`java.lang.String`, not an enum instance.

---

### [SILENT FALLBACK / wrong lineage] `ScanColumns` reports **NO** lineage for a pivot — the pivot key and aggregate columns are dropped

`ScanColumns.collectEnv` (`ScanColumns.java:205-206`) is
```java
case SqlSource.Pivot p -> collectEnv(p.source(), outer, env, out);
```
It descends into the pivot's *source* only. `SqlSource.Pivot` also carries `on` (the pivot key
expressions) and `usings` (the aggregate reducers over real columns) — `sql/SqlSource.java:30-31` —
and neither is ever visited. `rootSpine` (`ScanColumns.java:132`) has the same shape.

**Repro** (`a29/lin.pure`, `a29/LinProbe.java`):
```
#>{store::LDb.T_A}#->pivot(~[Y], ~sx: x|$x.X: y|$y->sum())
```
**Actual output:**
```
[SQL]  SELECT * FROM (PIVOT T_A AS t0 ON "Y" USING SUM(X) AS "_|__sx") AS t1
[IR]   ... Pivot[source=Table[name=T_A, alias=t0, ...],
               on=[Column[table=t0, name=Y, ...]],
               usings=[Using[agg=Reducer[fn=SUM, args=[Column[table=t0, name=X, ...]]], alias=sx, ...]], ...]
(no [LIN] lines at all)
```
The identical `groupBy` form reports both columns correctly:
```
#>{store::LDb.T_A}#->groupBy(~[Y], ~sx: x|$x.X: y|$y->sum())
[LIN]  T_A.X <TableAliasColumn>
[LIN]  T_A.Y <TableAliasColumn>
```
**Why it matters.** A pivot query's lineage is reported as *reading nothing*. The class javadoc
(`ScanColumns.java:36-38`) claims *"Silent drops are wrong lineage, so the expression walk is TOTAL"* —
the reflective totality (`useChildren`, `ScanColumns.java:293-304`) covers `SqlExpr` only; the
`SqlSource` switch in `collectEnv` has no reflective fallback. **DOC-LIE + SILENT DROP.**

Sibling silent-empty resolvers in the same switch (`ScanColumns.java:199-208`):
`Values`, `RawSql`, `VarSetPlaceholder`, `SourceUrl` all register `(col, ctx, o) -> { }`, so any column
demanded through those sources contributes nothing. `ScanColumns.java:270-273`
`Resolver r = env.get(c.table()); if (r != null) {...}` silently drops a column whose alias is not in scope.

---

### [INFORMATION LOSS] Lineage is a flat SET of physical columns with no per-output edges and no types

`ScanColumns.strings` returns `List<String>` of `"TABLE.COLUMN <Context>"`
(`ScanColumns.java:47-53`, `181-182`). Verified by construction over the whole feature matrix
(`a29/lin.pure`, `a29/LinProbe.java`):

| case | query | reported lineage | verdict |
|---|---|---|---|
| plain select | `T_A->select(~[X,Y])` | `T_A.X`, `T_A.Y` `<TableAliasColumn>` | correct |
| renamed column | `T_A->rename(~X,~RENAMED)->select(~[RENAMED])` | `T_A.X` | correct, name lost |
| CASE over one source | `extend(~[c: if(X>5,\|Y,\|Y)])->select(~[c])` | `T_A.X`, `T_A.Y` | correct set, **no edge `c <- {X,Y}`** |
| CASE over two sources (join) | `T_A join T_B ... extend(~[c: if(X>5,\|Y,\|Z)])` | `T_A.ID`,`T_B.ID` `<JoinTreeNode>`; `T_A.X`,`T_A.Y`,`T_B.Z` `<TableAliasColumn>` | correct set |
| UNION of two tables | `T_A->select(~[X,Y])->concatenate(T_B->select(~[X,Z])->rename(~Z,~Y))` | `T_A.X`,`T_A.Y`,`T_B.X`,`T_B.Z` | correct — resolves through the branch rename |
| computed over two inputs | `extend(~[s: $r.X + $r.BX])` | *(walled in Phase G: `no overload of plus` for `Integer[0..1]+Integer[0..1]`)* | not reachable |
| pivot dynamic column | `pivot(~[Y], ~sx: X : sum)` | **empty** | **WRONG (finding above)** |
| self-join | `T_A join T_A' ...` | `T_A.ID`, `T_A.Y` | both sides collapse — indistinguishable |

Because the output is a set keyed by physical `table.column`, the query
`project(~[c: CASE over X and Y])` is indistinguishable from `project(~[x:X, y:Y])`. **There is no
output-column -> input-column edge anywhere in this surface**, and no Pure type accompanies any
lineage row.

---

### [INFORMATION LOSS] Neither lineage surface uses the typed HIR — both re-derive structure

Explicit answer to task item 4:

* **`ScanColumns`** consumes the *lowered SQL IR* (`SqlQuery`/`SqlExpr`/`SqlSource`, imports at
  `ScanColumns.java:6-10`). Everything above phase I is gone: Pure types, multiplicities, enum
  identity, decimal precision. Entered from `Compiler.lowerResolved(...)` (harness
  `test/.../LineageForm.java:100-104`).
* **`ScanRelations`** consumes the *parse-space protocol AST* (`protocol.spec.ValueSpecification`,
  `LambdaFunction`, `AppliedFunction`; imports at `ScanRelations.java:15-22`) plus the raw mapping
  model. It runs **before** Phase G — no `TypedSpec` type is ever consulted.
* **`PkInference`** likewise (`PkInference.java:8-13`).

So all three re-derive structure rather than reading the typed HIR, and none of the three carries a
type on any lineage/PK output.

---

### [SILENT FALLBACK / wrong result] `PkInference` dispatches on the function's SIMPLE NAME — a user function named `sort`/`filter`/`select`/… is silently treated as the built-in

`PkInference.java:46-49`:
```java
String simple = af.function().substring(af.function().lastIndexOf(':') + 1);
...
switch (simple) { case "tableReference" -> ...; case "filter","limit","drop","slice","sort","extend","from" -> infer(ctx, ps.get(0)); ... }
```
No FQN check, no arity check, no `ctx` lookup to confirm the callee *is* the platform relation op.

**Repro** (`a29/pk2.pure`, `a29/PkProbe.java`) — two functions with **identical bodies**, differing
only in name:
```pure
function fn::sort(r: Relation<Any>[1]): Relation<Any>[1]
{ $r->groupBy(~[V], ~cnt: x|$x.K1 : y|$y->count()) }
function fn::useSort(): Relation<Any>[1] { fn::sort(#>{store::PkDb.T_COMP}#) }

function fn::mystery(r: Relation<Any>[1]): Relation<Any>[1]
{ $r->groupBy(~[V], ~cnt: x|$x.K1 : y|$y->count()) }
function fn::useMystery(): Relation<Any>[1] { fn::mystery(#>{store::PkDb.T_COMP}#) }
```
**Actual output:**
```
fn::useSort        pk=[K1, K2]     <-- WRONG: "sort" hit the pass-through arm; the body groups by V
fn::useMystery     pk=[V]          <-- correct: the default arm composed through the body
```
Sixteen names are capturable: `tableReference, filter, limit, drop, slice, sort, extend, from, select,
rename, distinct, groupBy, join, aggregate, concatenate, pivot, getAll`.

---

### [SILENT FALLBACK] `PkInference` join concatenation produces a duplicate key column name

`PkInference.java:86-91` concatenates both sides' PK name lists with no dedup and no
disambiguation. When both sides carry a PK column of the same name the result names a column that
cannot exist in the joined relation (`Type.RelationType`'s constructor *rejects* duplicate column
names, `Type.java:529-535`).

**Repro** (`a29/pk2.pure`: `T_COMP(K1,K2 pk)` join `T_OTHER(K1 pk)`):
```
fn::dupJoin        pk=[K1, K2, K1]
```

---

### [CRASH/ICE] `ValidateDesugar` throws a raw `IllegalStateException` for a `validate(...)` query with no `.all()` root

`ValidateDesugar.java:450 throw new IllegalStateException("no getAll root in validate query")`.
The two recursive catch arms above it (`:434-438`, `:443-447`) swallow it while scanning siblings,
but it escapes at the top level.

**Repro** (`a29/vd.pure`, `a29/VdProbe2.java`):
```
meta::relational::validation::validate({|1+1}, model::VMap, test::VRuntime, [])
```
**Actual output:**
```
  THROW java.lang.IllegalStateException: no getAll root in validate query
```
An `IllegalStateException` — not a `LegendCompileException` / `NotImplementedException` — on input a
user could write (any `validate` over a non-`getAll`-rooted query, e.g. a `#>{db.T}#` relation query).

---

### [DEFECT] `ValidateDesugar` duplicates a constraint inherited through a diamond — duplicate violation rows

`ValidateDesugar.constraintsInHierarchy` (`ValidateDesugar.java:217-228`) walks *every* superclass
and `addAll`s, with **no dedup and no visited-set**:
```java
List<ConstraintDefinition> out = new ArrayList<>(cd.constraints());
ctx.findClass(cd.qualifiedName()).ifPresent(tc -> {
    for (String sup : tc.superClassFqns()) {
        ctx.findClassDefinition(sup).ifPresent(sd -> out.addAll(constraintsInHierarchy(sd, ctx)));
    }
});
```

**Repro** (`a29/vd.pure`): `Base[baseC]` <- `Mid1[mid1C]`, `Mid2[mid2C]` <- `Diamond[dC]`.
```
### meta::relational::validation::validate({|model::Diamond.all()}, model::VMap, test::VRuntime, [])
  desugared to 5 constraint projection(s):
    'dC' | 'Error' | '' |
    'mid1C' | 'Error' | '' |
    'baseC' | 'Error' | '' |
    'mid2C' | 'Error' | '' |
    'baseC' | 'Error' | '' |      <-- DUPLICATE
  [G] Result<Relation<(CONSTRAINT_ID:String[1], ENFORCEMENT_LEVEL:String[1], MESSAGE:String[1])>>[1]
```
Every row violating `baseC` is reported **twice** in the validation result set.
(Inheritance *cycles* are safe — `Compiler.compileModel` rejects them first:
`ModelException: [2:1] Inheritance cycle: model::CA -> model::CB -> model::CA`, `a29/vd3.pure`.)

---

### [MISSING RULE / UNSOUND] `ValidateDesugar` never checks the `~message` type — the contract's `MESSAGE:String` column can come back Integer

`ValidateDesugar.java:349-355` builds the MESSAGE projection lambda from `c.message()` with no type
check; the other two columns are always `CString`.

**Repro** (`a29/vd.pure`, `mi( ~function: $this.n > 0 ~message: $this.n )`):
```
### meta::relational::validation::validate({|model::MsgInt.all()}, model::VMap, test::VRuntime, [])
  desugared to 1 constraint projection(s):
    'mi' | 'Error' | <AppliedProperty> |
  [G] Result<Relation<(CONSTRAINT_ID:String[1], ENFORCEMENT_LEVEL:String[1], MESSAGE:Integer[1])>>[1]
```
The `validate` contract's violation TDS is `(CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE)` all String;
here `MESSAGE` is `Integer[1]` and nothing objects.

**Second-order** — with *two* constraints of differing message type (`a29/vd2.pure`, class `MsgMix`),
the synthesized `concatenate` fails with a self-contradictory diagnostic:
```
[G-ERROR] TypeInferenceException: in call to 'meta::pure::functions::collection::concatenate', argument 2:
   column mismatch: type variable T bound to relation [CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE]
   cannot also bind relation [CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE]
```
(the message prints column *names* only, so it reads as "X cannot bind X"). The user wrote no
`concatenate`; the desugar did.

---

### [MISSING RULE / SILENT MIS-BINDING] `validate(...)`'s runtime argument is "whatever follows the first element pointer" — it can silently be the extensions list

`ValidateDesugar.java:132-136`:
```java
} else if (mapping == null && a instanceof PackageableElementPtr) {
    mapping = a;
    runtime = af.parameters().get(++i);
}
```
No check that `a` names a Mapping, none that the next argument names a Runtime, and `++i` can index
the extensions slot (`n-1`) because the loop bound is `i < n - 1`.

**Repro** (`a29/vd2.pure`):
```
meta::relational::validation::validate({|model::MsgInt.all()}, ['mi'], model::VMap, [])
```
The desugar binds `mapping = model::VMap`, `runtime = []` (the extensions collection) and emits
`execute(lambda, model::VMap, [], [])`. **Actual output:**
```
  desugared to 1 constraint projection(s):
    'mi' | 'Error' | <AppliedProperty> |
  [G-ERROR] TypeInferenceException: multiplicity [0] is not compatible with [1]
```
A missing runtime is reported as an arity error deep in the typer, with no mention of `validate`.

---

### [SILENT FALLBACK] `ValidateDesugar` silently drops a `createConstraintContextInformation` enforcement-level override that is not a bare `EnumValue`

`ValidateDesugar.java:256-259`:
```java
ValueSpecification level = af.parameters().get(2);
String levelName = level instanceof ...EnumValue ev ? ev.value() : null;
```
`null` then fails the `override[0] != null` guard at `:359`, so the override is discarded and the
constraint's own level (or the `"Error"` default) is used — **no diagnostic**. Any level passed as a
variable, a qualified enum reference, or a function result is silently ignored.

---

### [DEFAULTING] `ValidateDesugar` defaults a missing enforcement level to the literal `"Error"`

`ValidateDesugar.java:356-357 String level = c.enforcementLevel() == null ? "Error" : c.enforcementLevel();`
Verified: every projection above prints `'Error'`. Documented as engine parity
(*"~enforcementLevel defaults Error (the engine's own default)"*, `:348`); recorded because the repo
brief forbids defaulting and this one is unconditioned and unlogged. It is also never validated —
whatever string the parser accepted is emitted verbatim into the `ENFORCEMENT_LEVEL` column
(`~enforcementLevel: Warn` -> `'Warn'`, verified on `a29/vd2.pure` class `LvlOdd`).

---

### [SILENT FALLBACK / INCONSISTENCY] `ScanRelations.rootImpl` picks the FIRST class mapping for a multi-set class; two sibling routines are loud about the same input

`ScanRelations.rootImplOrNull` (`ScanRelations.java:636-646`) returns on the first
`typeMatches(r.className(), classFqn)` hit, with no ambiguity check. `classMappingFor`
(`ScanRelations.java:2207-2225`) throws when `hits.size() != 1`, and the store resolver throws too.

**Repro** (`a29/union.pure`: `model::P[set1]` over `T_P1`, `model::P[set2]` over `T_P2`;
`a29/pkg/RiProbe.java`):
```
rootImpl(model::UMap, model::P) = [UMap, set1, store::UDb, T_P1]     <-- silent first-wins
rootFor = Optional.empty                                             <-- loud path (via classMappingFor)
```
and the pipeline for the same model/mapping:
```
[EXEC-ERROR] com.legend.error.MappingResolutionException: class 'model::P' is not mapped in mapping
  'model::UMap' (class is mapped through multiple set IDs; ...)
```
`rootImpl` is the public API `PlanText.single`/`typeBlock` use to print
`type = Class[impls=(<class> | <mapping>.<setId>)]` (`PlanText.java:138-140`), so the plan text would
name exactly one of two equally valid sets. Three implementations of one rule, two loud, one guessing.

---

### [SILENT FALLBACK] `ScanRelations.rootFor` swallows every `RuntimeException` into `Optional.empty()`

`ScanRelations.java:2596-2603`:
```java
static Optional<ClassMapping.Relational> rootFor(ModelContext ctx, LegacyMappingDefinition md, String classFqn) {
    try { return Optional.of(classMappingFor(ctx, md, classFqn, null)); }
    catch (RuntimeException e) { return Optional.empty(); }
}
```
An NPE / `ClassCastException` / `IllegalStateException` anywhere inside `classMappingFor` becomes
"this class has no root mapping". (`ErrorShapeGuardrailTest` pins this file at exactly 1 broad catch,
so it is a *reviewed* boundary — but it still converts an ICE into a wrong answer rather than a
counted decline.)

---

### [INFORMATION LOSS] Cross-store placeholder columns are hard-typed `INT` in `resultColumns`

`PlanText.java:674`:
```java
String spelled = VAR_SET_SENTINEL.equals(pcf[0]) ? "INT" : spell(...);
```
reached from the `catch (NotImplementedException e)` at `PlanText.java:662-671`: any star-top column
that resolves through **no** physical branch of a placeholder-bearing FROM tree is spelled `INT`
regardless of its real store type. The same rule sits in `resolveStarColumn`'s `VarSetPlaceholder`
arm (`PlanText.java:811-820`). Cited as engine parity (`pureToSQLQuery.pure:583`), but on this
surface a `VARCHAR(200)` column crossing a cross-store splice prints `INT` in `resultColumns`.
Code-only citation — I did not build a two-database cross-store fixture (see NOT COVERED).

---

### [LOW / positional coupling] `PlanEnumForm.apply` matches SQL projections to relation-type columns BY INDEX

`PlanEnumForm.java:37-38`:
```java
boolean isEnum = i < rt.columns().size()
        && rt.columns().get(i).type() instanceof Type.EnumType;
```
The i-th SQL projection is assumed to be the i-th relation-type column. Any lowering that reorders,
inserts, or star-expands projections relative to the terminal's `RelationType` mis-labels which
projection is the enum, and the guard `i < rt.columns().size()` silently treats surplus projections as
non-enum. No repro found (the projection order held in every shape I built); reported as a fragile
invariant, not a demonstrated defect.

### [LOW] `PlanEnumForm.rewritePredicate` default arm leaves non-`=`/`in` enum predicates in decode form

`PlanEnumForm.java:147-149 default -> return c;`. An enum column compared with any `SqlFn` other than
`EQUAL/NOT_EQUAL/IN/AND/OR/NOT/COALESCE` keeps the execution-side decode `CASE` in the plan SQL
instead of the raw column + selector template — an internal inconsistency in the plan-surface
"enum columns stay raw" doctrine.

### [DOC-LIE] `ScanColumns` class javadoc claims a total walk

`ScanColumns.java:36-38`: *"Silent drops are wrong lineage, so the expression walk is TOTAL: unhandled
composite nodes recurse over their record components reflectively."* True of `SqlExpr` (`useChildren`,
`:293-304`); **false** of `SqlSource` (`collectEnv`, `:171-210` — an explicit switch that drops
`Pivot.on`/`Pivot.usings` and no-ops four source kinds). See the pivot finding.

### [DOC-LIE] `InProtocol.java:161-162` "others wall at the caller"

No such wall exists. See the `VARCHAR(1024)` finding.

---

## 2. CATCH / orElse / DEFAULT CENSUS (task item 7) — every site in the scoped files

Classification: **LOUD** = throws a diagnosable error; **CONTROL** = ordinary control flow, no type
data lost; **SILENT** = a type/binding/lineage fact is guessed, defaulted or dropped without a diagnostic.

| file:line | site | class |
|---|---|---|
| `PlanText.java:373` | `docs.getOrDefault(name, "")` — no doc -> `""` | CONTROL |
| `PlanText.java:450,482` | `ctx.findLegacyMapping(...).orElse(null)` -> no enum-mapping id emitted | SILENT (id dropped from the TDS tuple) |
| `PlanText.java:455-465` | first-match enumeration mapping, exact then simple-name | **SILENT** (see finding) |
| `PlanText.java:514` | `candidates.get(0).mappingId()` after disambiguation misses | **SILENT** |
| `PlanText.java:567` | `throw NotImplementedException` — 13 uncovered `Type` variants | **LOUD but reachable ICE** (3 proven) |
| `PlanText.java:662-671` -> `:674` | varset column -> hard `"INT"` | **SILENT type default** |
| `PlanText.java:756` | `DecodeShapes.sourceColumn(l).orElse(null)` -> not a uniform case | CONTROL |
| `PlanText.java:808,892` | `catch (NotImplementedException)` in join arms -> try the other side | CONTROL |
| `PlanText.java:851,917` | `default -> { }` then throw | LOUD |
| `PlanText.java:872` | `containsVarSet default -> false` | SILENT (new `SqlSource` variant silently "no varset") |
| `PlanText.java:387,419` | M2M / computed TDS type spelling | **LOUD, reachable ICE** (computed proven) |
| `PlanText.java:936-951` | `spell` pending arms (7 of 21) | LOUD (4 unreachable, 2 walled earlier) |
| `InProtocol.java:161` | `colType = "VARCHAR(1024)"` | **SILENT type erasure** |
| `InProtocol.java:168-169` | `type = String` for every collection | **SILENT type erasure** |
| `InProtocol.java:222` | non String/Int constant | **LOUD, reachable ICE** |
| `InProtocol.java:132` | `thresholdFor` -> `null` for non-Test non-DB2 | CONTROL (protocol off) |
| `PlanEnumForm.java:37` | positional projection/column match | SILENT (fragile) |
| `PlanEnumForm.java:93` | `.findFirst().orElse(null)` (no selector param) | CONTROL |
| `PlanEnumForm.java:147` | `default -> return c` | SILENT (decode kept in plan text) |
| `PlanEnumForm.java:180,234` | `default -> false` / reflective recurse | CONTROL |
| `PlanSupportFunctions.java:106` | unsupported enum source-value kind | LOUD (no repro) |
| `PlanSupportFunctions.java:103` | unescaped `'value'` | **SILENT corruption** (finding) |
| `PurePrint.java:250-252` | `default -> throw NotImplementedException` | LOUD (reachable for a Float/Boolean/Date PureExp let) |
| `RelationalMapperRenames.java:283-286` | `findDatabase(...).orElse(null)` -> `return dbFqn` | SILENT (unknown db is its own defining db) |
| `RelationalMapperRenames.java:297` | include search exhausted -> `return dbFqn` | SILENT |
| `RelationalMapperRenames.java:321-323` | already-catalog-qualified -> unchanged | CONTROL |
| `RelationalMapperRenames.java:340-342` | no mapper for this schema -> unchanged | CONTROL |
| `RelationalMapperRenames.java:350-354` | ambiguous across dbs -> **throws** | LOUD (good) |
| `RelationalMapperRenames.java:357-359` | `getOrDefault(..., original)` | CONTROL |
| `ScanColumns.java:157` | `joinedSubselects default -> { }` | CONTROL |
| `ScanColumns.java:199-208` | `Values`/`RawSql`/`VarSetPlaceholder`/`SourceUrl` no-op resolvers | **SILENT lineage drop** |
| `ScanColumns.java:205` | `Pivot -> collectEnv(source)` only | **SILENT lineage drop** (finding) |
| `ScanColumns.java:270-273` | `if (r != null)` — unknown alias | **SILENT lineage drop** |
| `ScanColumns.java:289` | reflective `useChildren` | CONTROL (this half *is* total) |
| `ScanColumns.java:315` | `catch (ReflectiveOperationException) -> throw ISE` | LOUD |
| `PkInference.java:63` | `kept.containsAll(pk) ? pk : List.of()` | CONTROL (conservative) |
| `PkInference.java:68-75` | multi-column rename silently leaves the PK unrenamed | SILENT |
| `PkInference.java:83-84` | `groupBy` with <2 params -> `List.of()` | CONTROL |
| `PkInference.java:92-94` | aggregate/concatenate/pivot/getAll -> `List.of()` | CONTROL (conservative, correct) |
| `PkInference.java:95-111` | default arm: compose or `List.of()` | **SILENT** (name-collision finding) |
| `PkInference.java:119-131` | unknown table / no PK -> `List.of()` | SILENT (unresolvable table = "no key") |
| `ValidateDesugar.java:87` | `default -> stmt` (non-rewritable node) | CONTROL |
| `ValidateDesugar.java:145-149` | `NewInstance`/`new` argument silently skipped | SILENT (an exeCtx flag is dropped) |
| `ValidateDesugar.java:152-158` | unknown argument -> throws | LOUD |
| `ValidateDesugar.java:257-259` | non-`EnumValue` level -> `null` -> override dropped | **SILENT** (finding) |
| `ValidateDesugar.java:274,302` | `contextInfo` returns false -> caller's wall | LOUD |
| `ValidateDesugar.java:356-357` | `"Error"` default level | **DEFAULTING** (finding) |
| `ValidateDesugar.java:380` | `(AppliedFunction) col` unchecked cast | safe by `isColArg` (`:305-314`), still an unchecked cast in a rewrite |
| `ValidateDesugar.java:360,369` | `(String) override[0]`, `(String) override[2]` from `Object[]` | unchecked casts; guarded, but the `Object[]` carrier defeats the type system |
| `ValidateDesugar.java:434-438` | `catch NotImplementedException -> rethrow; catch RuntimeException -> ignore` | SILENT (swallows NPE/CCE) |
| `ValidateDesugar.java:443-447` | `catch RuntimeException -> ignore` — **also swallows `NotImplementedException`**, unlike `:434` | **SILENT + INCONSISTENT with the adjacent arm** |
| `ValidateDesugar.java:450` | `throw new IllegalStateException` | **reachable ICE** (finding) |
| `ValidateDesugar.java:418-427` | multi-import collision -> constrained candidate, else first import | SILENT (first-wins when 2+ candidates both have constraints) |
| `ScanRelations.java:636-646` | `rootImplOrNull` first match | **SILENT** (finding) |
| `ScanRelations.java:994-996` | `catch NotImplementedException -> return jn` (join label) | SILENT (label degrades to the join name) |
| `ScanRelations.java:1311,1526,1788,1808,2288` | `catch NotImplementedException -> return List.of()/return/continue` | SILENT (union/target expansion silently skipped) |
| `ScanRelations.java:2409,2418` | `catch IllegalStateException ignore` (root-class scan) | SILENT |
| `ScanRelations.java:2600` | `catch RuntimeException -> Optional.empty()` | **SILENT** (finding) |
| `ScanRelations.java:1019,1770,2151,2580` | `default -> throw NotImplementedException` | LOUD |
| `ScanRelations.java:2346-2353` | `typeMatches` tail match, guarded by "at most one side qualified" | CONTROL (guard is correct) |
| `DriverPkOption.java:21-22` | `ThreadLocal.withInitial(() -> false)` | SILENT default + never cleared: `set()` has no `remove()`, so the flag leaks across runs on a pooled thread (`StatementExecutor.java:39` reads it) |

---

## VERIFIED SOUND

* **`PlanText.pureTypeName` for a `DECIMAL(10,2)` store column** — direct call *and* a real plan-text
  render both produce `Decimal` / `DECIMAL(10,2)`. The forwarded claim's consequence is refuted (§ first finding).
* **`PlanText.spell`** — exhaustive over all 21 `RelationalDataType` variants; the 4 truly pending
  arms (`Distinct`, `Other`, `Array`, `Object_`) are unreachable because `StoreCompiler.columnType`
  (`StoreCompiler.java:198-206`) rejects them at model compile with a clean `ModelException`.
* **Enum disambiguation on the TDS-tuple path.** With two enumeration mappings over one enum and two
  properties, `PlanText.enumMappingIdFor` picks correctly:
  `type = TDS[(a, model::Side, VARCHAR(10), "", MapOne), (b, model::Side, VARCHAR(10), "", MapTwo)]`.
* **Enum decode escaping on the EXECUTION path.** Source values `O'Brien` and `a,b` render as
  `CASE WHEN t0.SIDE = 'O''Brien' THEN 'BUY' ELSE CASE WHEN t0.SIDE = 'a,b' THEN 'SELL' ELSE NULL END END`
  and decode correctly (`BUY`, `SELL`). Numeric-code and string-code enumeration mappings both work.
* **Enum property mapping without an `EnumerationMapping` id** is a clean compile error, not a guess:
  `TypeInferenceException: ... property 'sideB' of 'model::Trade': expected model::Side, got String`.
* **`ScanColumns` lineage is correct** for: plain select; renamed column (resolves through
  `X AS RENAMED`); CASE over one source; CASE over two sources across a join (join keys correctly
  tagged `<JoinTreeNode>`, values `<TableAliasColumn>`); **UNION of two tables including a
  cross-branch rename** (`T_A.X, T_A.Y, T_B.X, T_B.Z`); filter/sort/groupBy keys; class-mapped
  `project` through a mapping. Full matrix table above.
* **`PkInference` does NOT guess when no key exists.** `T_NOPK` (no PK) -> `[]`; `select` dropping a
  PK column -> `[]`; `filter` over a keyless table -> `[]`. Composite PK -> `[K1, K2]`;
  `select` keeping the whole PK -> `[K1, K2]`; single-column `rename` -> `[KA, K2]`; chained renames
  -> `[KA, KB]`; `distinct(~[V])` -> `[V]`. **No silent guess found** — the conservative arms hold.
* **`PkInference` over a Float PK (`FK FLOAT PRIMARY KEY`) -> `[FK]` and a Decimal PK
  (`DK DECIMAL(10,2) PRIMARY KEY`) -> `[DK]`** — accepted with no type check. Any store PK column is
  a key regardless of type; there is no type check anywhere in the file (PK output is `List<String>`).
  Nullable columns cannot be store PKs (`StoreCompiler.java:170` stamps a PK column `[1]`), and a
  `distinct(~[A])` over a nullable `A` yields `[A]`, which is uniqueness-correct.
* **`ValidateDesugar`'s missing Boolean check is caught downstream.** `nb: $this.n` (Integer body)
  desugars to `not($this.n)` and the typer rejects it:
  `TypeInferenceException: in call to 'meta::pure::functions::boolean::not', argument 1: expected Boolean, got Integer`
  (poor diagnostic — points at synthesized code — but sound).
* **`ValidateDesugar` cannot StackOverflow on an inheritance cycle** — `Compiler.compileModel` rejects
  cycles first (`ModelException: Inheritance cycle: model::CA -> model::CB -> model::CA`).
* **`ValidateDesugar`'s `not(not(x))` collapse is logically correct** (`:334-340`): `¬¬x = x`.
* **`RelationalMapperRenames` is type-free** — it maps table-name strings to table-name strings; no
  `Type` is read or written anywhere in the file. Its cross-database ambiguity case is LOUD
  (`:350-354`), which is the right shape.
* **`PlanNode` / `PlanConn`** are plain data records with null-tolerant list normalization; no type
  logic, nothing to falsify.
* **`ValidateDesugar` full read**: 6 validation rules and 5 rewrites, enumerated in the findings above.

---

## NOT COVERED

* **Cross-store / multi-database plans.** The `VAR_SET_SENTINEL -> "INT"` default
  (`PlanText.java:674`, `:800-807`), `spliceLeftVar`/`colsPlanFor`/`spliceLeftVarQuery`
  (`PlanText.java:570-625`) and `PlanText.single`'s `colsPlan` parameter are reported by **code
  citation only** — building a two-/three-database fixture with a real cross-store TDS join was out
  of budget. The `INT` default is verbatim in the source; its observable effect is not repro'd here.
* **M2M / ModelChainConnection paths.** `PlanText.tdsTuples`'s `m2m` branch (`:381-389`),
  `pureDbSpelling` under M2M, and `ScanRelations.rootImplOrNull`'s `ClassMapping.Pure` chase
  (`:651-682`) are read but not exercised — no M2M fixture built.
* **`ScanRelations` tree output (`treeString`, ~1900 of its 2604 lines).** I read the header, the
  `Node` model, `rootImpl`/`rootImplOrNull`, `classMappingFor`/`targetCm`/`typeMatches`/`mainTableOf`,
  `joinLabel`/`mangleCond`, and censused every catch/orElse/default in the file, but I did **not**
  build fixtures for union navigation, milestoning, views, subType restriction, association mappings,
  or the tdg walk. The one finding I report there (`rootImpl` first-wins) is repro'd; the rest of the
  file is covered by the catch census only.
* **`PlanText` node builders not driven end-to-end**: `scalarRelational`, `relationalBlock`,
  `freeMarkerConditional`'s false-block splice, `functionParametersNode` with >1 parameter,
  `createAndPopulateTempTable` with several input vars. Read, not executed.
* **`InProtocol`'s conditional (under-threshold, many-var) arm** — I exercised the over-threshold arm
  only; the `${...?c}` freemarker condition builder (`:175-199`) is read, not run.
* **`PurePrint`'s reachable ICE** — `PurePrint.java:250` throws for any node kind outside
  `{Variable, CString, CInteger, NativeCall}`; I did not construct a `PureExp`-shaped let whose value
  is e.g. a Float literal, so no repro is attached.
* **Quoted column identifiers through `ScanColumns.resolveThrough`** (`:227` compares
  `col.equalsIgnoreCase(p.outputName())` with **no** quote strip, unlike `PlanText.strip`) — I could
  not get a quoted column through the `~[...]` colspec parser
  (`ParseException: expected column name after '~'`), so the suspected drop is unconfirmed.
* **`Byte` / `Variant` reaching `pureName`** — both wall earlier
  (`IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary`;
  `NotImplementedException: class-typed property '$j.payload' used as a whole value is graph output (Phase H4)`),
  so those `pureName` holes are unreachable by the routes I found. Note in passing (outside my files):
  the BYTE wall is an `IllegalStateException`, i.e. an ICE, for a model a user can write —
  `Class model::Blob { data: Byte[*]; }` mapped to a `VARBINARY(64)` column.
* **`DriverPkOption`'s ThreadLocal leak** is reported by inspection; I did not build a thread-pool
  repro.
