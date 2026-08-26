# V2-falsifier — adversarial re-adjudication of the LATER findings files

**Mandate.** Assume every filed finding is wrong until personally reproduced. Every repro below was
re-derived from scratch in `/tmp/v2f` with **my own model/DDL fixtures** (`m1..m6.pure`, `mx*.pure`,
`TM.java`, `BT.java`, `EN.java`, `UC.java`, `XB.java`, `B.java`, `Counts*.java`, `J.java`, `PS.java`) —
no auditor fixture file was reused. Cross-backend claims were re-run by me on **DuckDB v1.5.0,
SQLite 3.47.1 and H2 2.1.214** through `Compiler.plan` / `Compiler.execute`.

Scope adjudicated: `A06, A09, A12, A15, A16, A18` in full for every UNSOUND/CRASH item; `A20, A22`
sampled on their distinctive claims; then a second pass over `A23, A24, A27, A28, A29, A30, A32`
as they landed mid-run; plus the orchestrator's `CONFIRMED.md` items **V18, V20, V23, V24, V25** as
explicitly instructed. (`V1-falsifier.md` did not exist at any point during my run, so nothing was
skipped as already-ruled. `A33` never appeared.)

---

## VERDICT TABLE

| source-file | finding | verdict | evidence |
|---|---|---|---|
| CONFIRMED.md | V18 generic user fn declared return never checked | CONFIRMED | my `m1.pure`: `function my::bad<T>(x:T[1]):T[1]{'hello'}`; `...->extend(~[b:x\|my::bad(1)])` → `[G] Column[name=b, type=INTEGER, mult=Bounded[lower=1,upper=1]]`, `[PLAN] SELECT t0.WNAME AS a, 'hello' AS b`, `[EXEC-ROW] String(alpha) \| String(hello)` |
| CONFIRMED.md | V20 `->toOne()` deleted in object space | CONFIRMED | `shop::Widget.all()->toOne()->project(~[a:p\|$p.name])` → `SELECT t0.WNAME AS a FROM T_WIDGET AS t0` (no LIMIT/guard), 2 rows. Graph form: `[G] ClassType[shop::Widget] mult=[1]` vs `[PLAN-TYPE] … mult=[*]`, 2 objects returned. Relation form still guards (`error('Cannot cast a collection of size 3 …')`). |
| CONFIRMED.md | V23 user model redefines `collection::first` | CONFIRMED | hijack model → `\|[1,2,3]->first()` = `[G] STRING[1]`, `SELECT 'HIJACKED'`; control = `[G] INTEGER[0..1]`, `list_extract([1,2,3],1)`. Only the matching overload is hijacked (`['a','b']->first()` unaffected). |
| CONFIRMED.md | V24 let-inlining capture, repro `[10]->map({x\|let y=$x;[7]->map(y\|$y)->toOne();})` ⇒ 7 | **CONFIRMED-BUT-OVERSTATED** | The **defect is real** but this repro does not demonstrate it and the stated mechanism is backwards. See §FALSE/OVERSTATED #1 — correct repro supplied. |
| CONFIRMED.md | V25 `first(set,count)` drops count | CONFIRMED (DUPLICATE of A06 #7) | `\|[1,2,3]->first(2)` → `SELECT UNNEST(list_filter([list_extract([1,2,3],1)],…))` → 1 row `Integer(1)`; `first(0)`/`first(3)` render byte-identical SQL; `take(2)` → `array_slice([1,2,3],1,2)` → 2 rows. |
| A06 | header counts 51 CoreFn / 54 parse names / 721 defs / 431 FQNs / 398 bare / 345 without arm | CONFIRMED (recomputed) | `Counts.java`+`Counts2.java` over `Pure.all()`/`CoreFn.values()`: `721 / 431 / 398 / 51 / 54 / 345`. (`CoreFn.of` rejects 349 bare names — the extra 4 are `tds,typeAsDeclared,castAsDeclared,legacyNavigate`, which the file's §(b) explains correctly.) Confirmed: **no** native named `new`. |
| A06 | 1. `SourceSubst.inlineLets` capture-unsafe | CONFIRMED (canonical statement of the bug) | `\|[10]->map({x\|let y=$x;[7]->map(x\|$y)->toOne();})` → `list_transform([7], x -> x)` → `Integer(7)`; control binder `w` → `list_transform([7], w -> x)` → `Integer(10)`. |
| A06 | 2. `StaticFold` integer `plus` overflows silently | CONFIRMED | `…project(~[a:p\|$p.qty]).columns->map(c\|9223372036854775807+1)` → `SELECT -9223372036854775808` → `Long(-9223372036854775808)`; unfolded `\|9223372036854775807+1` → `CAST(… AS HUGEINT)+1` → `BigInteger(9223372036854775808)`. |
| A06 | 3. `StaticFold` uses `Object.equals` across numeric kinds | CONFIRMED | folded `1==1.0` → `SELECT FALSE` → `Boolean(false)`; unfolded → `1 = CAST(1.0 AS DOUBLE)` → `Boolean(true)`. `in`: `FALSE` vs `true`. `indexOf(20.0)`: `Integer(-1)` vs `Integer(1)`. |
| A06 | 4. `StaticFold.sortBy` raw `ClassCastException` out of G | CONFIRMED | `…columns->map(c\|[1,'a']->sortBy(x\|$x)->makeString(''))` → `[G-ERROR] java.lang.ClassCastException: class java.lang.Long cannot be cast to class java.lang.String` |
| A06 | 5. `NormalizeFolds` folds `size()` from static multiplicity | CONFIRMED | nullable `ADDR_ID` under `addrId:Integer[1]`: direct `->size()` → `CASE WHEN t0.ADDR_ID IS NULL THEN 0 ELSE 1 END` → `Integer(0)`; via `my::sz()` → `SELECT … 1 AS n` → `Integer(1)`. Same row, two answers. |
| A06 | 6. contradictory lambda param annotation silently discarded | CONFIRMED (stronger than filed) | `filter({p:String[1]\|$p.qty>15})` compiles and runs. I also used a **non-existent class** `filter({p:shop::WidgetX[1]\|…})` — also accepted; the annotation is not even name-resolved. |
| A06 | 7. `first(set,count)` drops count | CONFIRMED (canonical for V25) | see V25 row |
| A06 | 8. `clampTdsCells` re-stamps `[*]`→`[0..1]`; `extend` does not; both ICE | CONFIRMED (dup of V17) | `project(~[a:p\|[1,2]])` → `Column[a, INTEGER, Bounded[0,1]]`, `SELECT [1,2] AS a`, `[EXEC-ERROR] IllegalStateException: a many-valued cell reached a scalar TDS slot ('a')`. `extend(~[b:r\|[1,2]])` → `Bounded[2,2]`, same ICE. |
| A06 | 9. one `Integer` decodes to 3 Java classes | CONFIRMED (one nit) | project→`Integer(10)`, groupBy sum→`BigInteger(10)`, count→`Long(1)`, max→`Integer(10)`, aggregate sum→`BigInteger(30)`. Nit: `max` column is `Bounded[0,1]`, not `[1]` as the preamble says; the 3-carrier claim stands. |
| A06 | 10. empty colspec name reaches DB as `AS ""` | CONFIRMED | `project(~['':p\|$p.name])` → `[G] Column[name=]`, `SELECT t0.WNAME AS ""`, `SQLException: Parser Error: zero-length delimited identifier` |
| A06 | 11. `__\|__` accepted as a user column name | CONFIRMED | `project(~['a__\|__b':…])->select(~['a__\|__b'])` executes, `[EXEC-COL] a__\|__b : String[1]` |
| A06 | 12. `FlattenChecker` arms disagree; relation arm forces `Variant` | CONFIRMED | relation arm on an INTEGER column → `Column[ag, ClassType[…::Variant], [1]]` then `NotImplementedException: class query under TypedFlatten…`; collection arm `\|[1,2,3]->flatten(~v)` → `Column[v, INTEGER, [0..1]]`, executes. |
| A06 | 13. `Typer.decimalType` computes precision from scale alone | CONFIRMED | `\|12345678901234567890123456789012345678.5d` → `PrecisionDecimal[38,1]` → `[EXEC-ROW] Double(1.2345678901234568E37)`; control `\|0.5d` → `BigDecimal(0.5)`. |
| A06 | 14. `refineDecimalCarrier` declares `Decimal(38,18)`, lowering casts elsewhere | CONFIRMED | `\|'1.5'->parseDecimal()` → `[G] PrecisionDecimal[38,18]`, `SELECT CAST('1.5' AS DECIMAL(38,1))`; `$p.qty->toDecimal()` → declared `Decimal(38,18)`, `CAST(t0.WQTY AS DECIMAL(38,0))`. |
| A06 | 15. two further ICEs (`at(99)`, `trustOne` stamp) | CONFIRMED | `…->at(0).values->at(99)` → `IllegalStateException: The system is trying to get an element at offset 99 where the collection is of size 2`; `->at(1)` → `IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED … callee=meta::legend::lite::trustOne` |
| A06 | 16. `NormalizeFolds` javadoc "runs at TYPING" | CONFIRMED (DOC-LIE) | repo-wide grep: the only call site is `UserCallInliner.java:322`; the other two hits are a javadoc line and a test comment. |
| A06 | 17. decimal arithmetic erases (p,s); `/` becomes Float | CONFIRMED | `\|1.5d+1.5d` → `[G] DECIMAL` (bare); `\|1.5d/3.0d` → `[G] FLOAT`, `SELECT ((1.0*1.5)/3.0)` → `Double(0.5)` |
| A06 | 18. `rel.columns` vs `rel->columns()` are different types | CONFIRMED | `.columns` → `STRING[2]`, `UNNEST(list_filter(['a','b'],…))`; `->columns()` → `ClassType[…relation::Column][2]`, `LinkedHashMap({name=a})`. `.columns->map(c\|$c.name)` works, `.columns->filter(c\|$c.name=='a')` → `TypeInferenceException: cannot access 'name' on String`. |
| A09 | Table 1 cross-backend carriers | CONFIRMED (re-run on all 3) | SMALLINT: DuckDB `Short` / SQLite `Integer` / H2 `Integer`. `DECIMAL(38,18)`: DuckDB+H2 `BigDecimal` exact, SQLite `Double(1.2345678901234567E19)`. `BOOLEAN`: DuckDB+H2 `Boolean`, SQLite `Integer(1)`. |
| A09 | F1 H2 `StrictDate` Julian drift | CONFIRMED | H2: `0001-01-01`→`1-01-03`, `1000-01-01`→`999-12-27`, `1582-10-04`→`1582-09-24`; `1582-10-15`/`2024-01-01` OK. Same rows on DuckDB and SQLite are exact. GRAPH egress of the same H2 row is exact (`"cDate":"0001-01-01"`), the DateTime lane is exact. |
| A09 | F2 SQLite `Decimal`→`Double`, `Boolean`→`Integer` | CONFIRMED | see Table 1 row |
| A09 | F3 narrowing gate off by one bit (2^62) | CONFIRMED | H2: `4611686018427387902+1` → `Long(4611686018427387903)`; `4611686018427387903+1` → `BigInteger(4611686018427387904)`. `9223372036854775806+1`: DuckDB `BigInteger`, SQLite `Long`, H2 `BigInteger`. |
| A09 | F4 `u_map__*` user column mis-shapes a table as a scalar | CONFIRMED (all 3 backends) | 1 row: `[COLS] value:Relation<(u_map__foo:Integer[1])>` with `[ROW] java.lang.Integer(1)`. >1 row: `IllegalStateException: scalar-shaped result returned more than one row`. 2 columns → TABULAR again. |
| A09 | F5 dynamic-pivot columns bypass the codec | **CONFIRMED-BUT-OVERSTATED** | Integer→`BigInteger` on DuckDB and `mult=null` on every pivot column reproduce; **`Float`→`BigDecimal` on H2 did not** — see §FALSE/OVERSTATED #2. |
| A09 | F6 `StrictDate[1]` over a physical TIMESTAMP → `DateWithSubsecond` | CONFIRMED (all 3) | store says `C_DATE DATE`, physical is `TIMESTAMP`: `[COLS] d:StrictDate[1]`, `[ROW] PureDateLiteral$DateWithSubsecond(2024-02-29T13:45:56.5+0000)` on DuckDB, SQLite and H2. |
| A09 | F7 SQLite `Integer[1]` overflow → `Double` | CONFIRMED | `$x.cBig+1` under `Relation<(b:Integer[1])>`: DuckDB `Out of Range Error: Overflow in addition of INT64`, H2 `Numeric value out of range`, **SQLite `java.lang.Double(9.223372036854776E18)`**. `$x.cInt+1`: SQLite `Long(2147483648)`. |
| A09 | F8 non-finite Float makes the graph JSON unparseable | CONFIRMED | `sql/Json.parse("[{\"eDbl\":Infinity}…]")` → `NumberFormatException: For input string: ""`; `server/Json.parse` → `IllegalArgumentException: Invalid number: expected digit, got 'I' at line 1 col 38` |
| A09 | F9 `sql/Json.num` overflows `Long.parseLong` | CONFIRMED | DuckDB+H2 graph JSON of a `DECIMAL(38,0)` = `[{"cDec0":99999999999999999999999999999999999999}]`; both readers → `NumberFormatException: For input string: "999…"` |
| A09 | F10 `LiteralText.parse` throws on non-finite / >long | CONFIRMED | reached through the pipeline: `\|[1.0e308->times(10.0),'a']` → `[EXEC-ERROR] NumberFormatException: For input string: "inf.0"`. Direct sweep: all 16 spellings I tried (`9223372036854775808`, 38 nines, `Infinity/-Infinity/NaN/inf/-inf/nan`, `%latest`, `""`, `" "`, `null`, `TDSNull`, `0x1p3`, `1_000`, `inf.0`) throw. |
| A09 | F11 `project(~[])` ICEs at decode | CONFIRMED (dup of V15) | all 3 backends: `SELECT * FROM T_REC AS t0` → `IllegalStateException: result has 3 columns but the typed schema has 0 — plan/schema mismatch` |
| A09 | F17 `exec/Column.multiplicity` has zero read sites | CONFIRMED (repo-wide, incl. tests) | `grep -rn "Column::multiplicity"` = 0 hits; no `.columns()…​.multiplicity()` read anywhere in `core/src/main`, `core/src/test`, `pct/`, `nlq/`, `tools/`, `experiments/`. Only the 3 write sites in `Executor` and the record component. |
| A09 | F18 `pureOfSqlType` refuses common spellings | CONFIRMED (exact list) | `PS.java` over the switch: `DOUBLE PRECISION, BOOLEAN NOT NULL, INT, INT4, INT8, TIMESTAMP WITH TIME ZONE, TIMESTAMPTZ, TIME, JSON, UUID, BIT, CHARACTER VARYING, NUMBER, VARBINARY, BLOB, SMALLINT UNSIGNED` → `IllegalStateException: no Pure primitive mapped for SQL type '…' (pivot-generated column)`. Accepted spellings match the filed table. |
| A09 | F19 enum → bare `String`, DuckDB Variant leaks `JsonNode` | CONFIRMED | `EN.java`: `[COL] st : m::Status mult=[1]`, `[ROW] java.lang.String(ACTIVE)`. `TM.java` StrOnJson: `org.duckdb.JsonNode({"a":1})` under `String[1]`. |
| A09 | F21 `PureDateLiteral` javadoc grammar admits `T<hour><TZ>` | CONFIRMED (DOC-LIE) | `parse("2024-02-29T13Z"/"…T13+0500"/"…T13-0500")` → `IllegalArgumentException: expected ':' after hour at position 13`; `…T13:00Z` parses. |
| A12 | H1 object-space `->toOne()` deleted | CONFIRMED (DUPLICATE of V20) | see V20 row |
| A12 | H2 nav LEFT JOIN copies right multiplicities; `[1]` end is NULL | CONFIRMED | my `m3.pure` (`emp: Emp[1]`, orphan `T_ADDR` row with `EMP_ID=99`): `[EXEC-COL] pn : String mult=[1]`, `LEFT OUTER JOIN T_EMP AS t1 ON t1.ID = t0.EMP_ID`, `[EXEC-ROW] String(Orphan Way) \| null`. Graph path: `{"street":"Orphan Way","emp":null}` under `Emp[1]`. |
| A12 | H3 `trustOne` launders nullable `[0..1]` into `[1]`, no guard | CONFIRMED | `sal: Float[1]` over nullable `SAL DOUBLE`: `SELECT t0.FN AS f, t0.SAL AS s` (no guard), `[EXEC-COL] s : Float mult=[1]`, `[EXEC-ROW] String(Bob) \| null` |
| A12 | H4 the same null ICEs the COLLECTION egress | CONFIRMED | `biz::Addr.all()->map(a\|$a.emp.fn)` → `IllegalStateException: NULL cell reached COLLECTION egress — the lowerer owns the null-drop …; a NULL here is a lowering defect, never an empty` |
| A12 | H5 `serialize(tree)` loses `String[1]`; plan rootType is a class | CONFIRMED | `[G] STRING mult=[1]` vs `[PLAN-TYPE] ClassType[fqn=biz::Emp] mult=[*] shape=GRAPH` for the identical query |
| A12 | H6 object-space `map` retypes `T[m]` as `Relation<…>[m]` | CONFIRMED | `biz::Emp.all()->map(e\|$e.fn)` → `[G] STRING[*]`, but `[EXEC-COL] value : Relation<(u_map__fn:String[1])>` with `String` values |
| A15 | `[1]` column NULL after LEFT / RIGHT / FULL / ASOF | CONFIRMED | TDS joins: LEFT `[EXEC-ROW] Integer(2)\|String(Bob)\|null\|null` under `person_id:[1], score:[1]`; RIGHT `null\|null\|Integer(9)\|Integer(77)`; FULL both; INNER clean. asOfJoin (`ASOF LEFT JOIN`) → `Integer(2)\|…\|null\|null\|null` under `quote_id:[1], quote_time:[1], price:[1]`. |
| A15 | `[1]` association navigation NULL | CONFIRMED (DUPLICATE of A12 H2) | see A12 H2 row |
| A15 | `FULL_OUTER` shared-key join drops the right key | CONFIRMED | `JoinType.FULL_OUTER, ['id']` → `SELECT _tds0.id, _tds0.name, t0.score` → `[EXEC-ROW] null \| null \| Integer(80)` (id 2 lost). `RIGHT_OUTER` keeps it **and** reorders the schema to `(name,id,score)`. |
| A15 | `prefixedUnion` exact-name vs quote-insensitive identity → wrong column | CONFIRMED | store column `"r_ID"`=777 + prefixed join minting `r_ID`=1 → `[G] (ID:[1], "r_ID":[0..1], r_ID:[1], r_RV:[0..1])`; `->select(~[r_ID])` → `SELECT t0."r_ID"` → `Integer(777)` and mult flips to `[0..1]`. Non-prefix path raises `SchemaInvariantException` loudly. |
| A15 | lite-internal join vocabulary reachable by FQN then ICEs | CONFIRMED | `->meta::legend::lite::join(~pa: …, {r,a\|…})` type-checks, then `NotImplementedException: TypedJoinSlot (pipeline slot join 'pa') escaped Phase H store resolution`; the bare spelling is refused (`no overload of 'join' matches 3 argument(s)`). |
| A15 | `[]` accepted as a `Boolean[1]` join condition | CONFIRMED | `{l,r\|[]}` → `JOIN … ON NULL`, no diagnostic, 0 rows |
| A15 | type-mismatched join comparison never rejected | CONFIRMED | `{l,r\|$l.name==$r.person_id}` compiles clean → `ON _tds0.name = _tds1.person_id` → `SQLException: Conversion Error: Could not convert string 'Alice' to INT32` |
| A15 | per-hop `(INNER)` annotation parsed then ignored | CONFIRMED | two chains, one `(INNER)` one plain, render to the **same alias**: `SELECT …, t2.CNAME AS ci, t2.CNAME AS cp … LEFT OUTER JOIN … LEFT OUTER JOIN …`; Jane/Bob survive with `null\|null`. |
| A16 | `cast(@Class)` is an unchecked re-label | CONFIRMED | `…project(~[a:p\|$p.firstName])->cast(@mm::Employee)` → `[EXEC-COL] value : mm::Employee`, `[EXEC-ROW] String(Bob)`. Sibling `cast(@mm::Dog)` types clean, walls only at lowering. |
| A16 | `cast(@Relation<(c:T[1])>)` promotes multiplicity | CONFIRMED | source column `nick:String[0..1]` → `[G] Column[a, STRING, Bounded[1,1]]`, `SELECT t0.NICK AS a` (no SQL emitted), `[EXEC-ROW] null` on row 2 |
| A16 | relation cast never consults `crossKindRaise` | CONFIRMED | scalar `$p.age->cast(@Boolean)` → `SELECT error('Cast exception: Integer cannot be cast to Boolean')`; relation `cast(@Relation<(a:Boolean[1])>)` → `SELECT CAST(t0.AGE_VAL AS BOOLEAN)` → `Boolean(true)` ×3 |
| A16 | cast laundering (widen-then-narrow is a no-op) | CONFIRMED | `1->cast(@Number)->cast(@Float)` → `SELECT 1` → `Integer(1)` under `Float[1]`; `1->cast(@Float)` → `CAST(1 AS DOUBLE)` → `Double(1.0)`. `%2020-01-02->cast(@Date)->cast(@DateTime)` → `StrictDate(2020-01-02)` under `DateTime[1]`. |
| A16 | `cast(@StrictTime)` escapes as raw `IllegalStateException` | CONFIRMED | `\|'hello'->cast(@StrictTime)` → `java.lang.IllegalStateException: no SQL type for Pure primitive STRICT_TIME at the lowering boundary` |
| A16 | `match` on `[0..1]` against a `[1]` branch promotes | CONFIRMED | `$p.nick->match([s:String[1]\|$s])` → `SELECT t0.NICK AS a`, `[EXEC-COL] a : String mult=[1]`, `[EXEC-ROW] null`. Adding the `[0..1]` branch correctly emits `CASE WHEN t0.NICK IS NOT NULL …`. |
| A16 | `MatchFold` takes the wider static arm | CONFIRMED | `1->cast(@Number)->match([i:Integer[1]\|'int', n:Number[1]\|'num'])` → `SELECT 'num'` → `String(num)` |
| A16 | `if` over relations fabricates a union RelationType | CONFIRMED | `[G] Relation<(a:Integer[1], b:String[1])>` vs `[PLAN-TYPE] Relation<(a:Integer[1])>`; `->select(~[b])` → `IllegalStateException: select/distinct columns [b] cannot all be resolved even after isolation` |
| A16 | `^Class(...)` never checks required properties | CONFIRMED | `^mm::Person()` → `SELECT {'eid': NULL, 'firstName': NULL, 'age': NULL, 'nick': NULL}`; `{\|let p=^mm::Person(); $p.firstName;}` → `[G] STRING[1]`, `SELECT NULL`, `[EXEC-ROW] null` |
| A16 | zero-param callee α-hygiene hole (`_iN` capture) | CONFIRMED | `fn::zeroI0()` → `list_transform([5], _i0 -> list_transform([1000], _i0 -> _i0 + _i0))` → `Integer(2000)`; control `fn::useX(5)` → `Integer(1005)` |
| A16 | inlining discards the call site's declared type | CONFIRMED | `fn::widen(x:Integer[1]):Any[1]{$x}`, `\|fn::widen(1)` → `[G] ClassType[…::Any] mult=[1]` vs `[PLAN-TYPE] INTEGER mult=[1]` |
| A16 | `inlineCall` swallows `NotImplementedException` → misattributed error | CONFIRMED | `fn::rec(5)` → `[G] INTEGER[1]` then `NotImplementedException: store resolution left user call 'fn::rec' uninlined — the call shape is not supported by the resolver yet [at root]` (the recursion-cycle message is discarded) |
| A16 | function bodies not checked at model-compile time | CONFIRMED | `fn::narrowRet(p:mm::Person[1]):Integer[1]{$p.firstName}` — model compiles; `[G] Relation<(a:Integer[1])>`; error only at PLAN: `TypeInferenceException: in function 'fn::narrowRet': declares return type Integer but body returns String` |
| A16 | `map` ignores a declared lambda param type; `eval` enforces | CONFIRMED | `[1,2,3]->map({x:String[1]\|$x})` → `[G] INTEGER[3]`, runs; `{x:Integer[1]\|$x+1}->eval('s')` → `TypeInferenceException: eval argument 1: expected Integer, got String` |
| A16 | `let` re-binding a name with a new type is accepted | CONFIRMED | `{\|let x=1; let x='str'; $x;}` → `[G] STRING[1]`, `SELECT 'str'` |
| A18 | no column/property compatibility check — 36-case coercion matrix | CONFIRMED (26/36 re-run: all 11 UNSOUND, all 6 LOUD, 5 controls, 4 loss/drift) | own generator `TM.java`/`TM2.java`. UNSOUND all reproduce: StrOnInt→`Integer(42)`, IntOnDec→`BigDecimal(123.45)`, IntOnDouble→`Double(2.75)`, IntOnFloat→`Float(1.5)`, StrOnTs→`DateWithSecond`, StrOnDec→`BigDecimal`, StrOnDate→`StrictDate`, StrOnBit→`Boolean(true)`, FloatOnInt→`Integer(7)`, DecOnInt→`Integer(7)`, DecOnDouble→`Double(1.1)`. LOUD all reproduce: DateOnVar/DateOnTs/DtOnVar/IntOnBit/ByteOnVarchar/VarOnInt. Loss: IntOnVarDec `'77.9'`→`Long(78)`; DecOnVar 20 digits→`BigDecimal(1.23)` via `CAST(rtrim(…) AS DECIMAL(5,2))`. Drift: TINYINT→`Byte`, SMALLINT→`Short`. |
| A18 | `trustOne` makes every `[1]` over a nullable column a lie | CONFIRMED (DUPLICATE of A12 H3) | see A12 H3 row |
| A18 | property-mapping table qualifier ignored → reads MAIN table | CONFIRMED | `BT.java`: `v: [s::DB] U.A` (U.A=999, unjoined) → `SELECT t0.ID AS id, t0.A AS v FROM T AS t0` → `1 42`. Identical for `NOSUCHTABLE.A` and `[s::NOSUCHDB] T.A`. No diagnostic. |
| A18 | `ArithmeticException: Rounding necessary` escapes from a union | CONFIRMED | `UC.java` (union of `V INTEGER` and `V DECIMAL(10,2)`=33.75 under `v:Integer[1]`): `SELECT t2.id, t2.v FROM (… UNION ALL …)`, then `java.lang.ArithmeticException: Rounding necessary … at com.legend.exec.Executor.unwrap(Executor.java:659)` |
| A18 | unmapped / case-mismatched / NULL enum → NULL under `[1]` | CONFIRMED | `EN.java`: `[COL] st : m::Status mult=[1]`; rows `'A'`→`String(ACTIVE)`, `'Z'`→`null`, `'a'`→`null`, `NULL`→`null`. Graph: `[{"id":1,"st":"ACTIVE"},{"id":2,"st":null},…]`. |
| A18 | `Decimal` over VARCHAR silently truncated to `DECIMAL(5,2)` | CONFIRMED | `SELECT CAST(rtrim(t0.V,'dD') AS DECIMAL(5,2)) AS v` → `BigDecimal(1.23)` from `'1.23456789012345678901'` |
| A20 | `->toOne()` no-op in lowering | DUPLICATE (canonical: CONFIRMED.md V20 / A12 H1) | see V20 row |
| A20 | `LEFT` join keeps right columns at `[1]` | DUPLICATE (canonical: A15) | see A15 row |
| A20 | `mod(0)` typed `Integer[1]` returns NULL; `rem(0)` raises | CONFIRMED | `\|10->mod(0)` → `SELECT MOD(MOD(10,0)+0,0)` → `[EXEC-ROW] null` under `Integer[1]`; `\|10->rem(0)` → `error('Cannot divide 10 by zero')` |
| A20 | unary `-` / `abs()` typed over an unbounded type var | CONFIRMED | `\|-'abc'` → `[G] STRING mult=[1]`, `SELECT 0 - 'abc'` → conversion error; `\|'abc'->abs()` → `[G] STRING[1]`, `SELECT abs('abc')` → binder error |
| A20 | set operations rendered without parentheses | CONFIRMED | `…->limit(1)->concatenate(…)` → `… LIMIT 1\nUNION ALL\nSELECT …` → `Parser Error: syntax error at or near "UNION"` |
| A20 | `->size()` after `groupBy` | CONFIRMED (shape) | `SELECT (SELECT COUNT(*) FROM ( … GROUP BY … ) AS t1)` — nested count over the grouped subquery |
| A20 | `sort(...)->drop(0)` emits `OFFSET 0` and hard-crashes DuckDB | CONFIRMED | `ORDER BY t0.WNAME OFFSET 0` → `SQLException: INTERNAL Error: Attempted to access index 0 within vector of size 0` **and the connection is invalidated for all later statements** (`FATAL Error: … database has been invalidated`) |
| A20 | `slice(hi,lo)` renders a negative LIMIT | CONFIRMED | `slice(2,0)` → `LIMIT -2 OFFSET 2` → `Binder Error: LIMIT/OFFSET cannot be negative` |
| A20 | Pure `Integer` arithmetic not widened at INT32 | CONFIRMED | `rec::Rec.all()->project(~[b:x\|$x.cInt+1])` → `SELECT t0.C_INT + 1` → DuckDB `Overflow in addition of INT32`, H2 `Numeric value out of range`, SQLite `Long(2147483648)` |
| A20 | empty column name renders `""` | DUPLICATE (canonical: A06 #10) | see A06 #10 row |
| A22 | `TypedCDecimal.info()` from scale only | DUPLICATE (canonical: A06 #13 / V4) | see A06 #13 row |
| A22 | `TypedMatchRuntime` lowering takes the static arm | DUPLICATE (canonical: A16 MatchFold) | see A16 row |
| A22 | missing lowering arms reachable from plain queries | CONFIRMED | `\|[1,2,3]->sortBy(x\|$x)` → `NotImplementedException: scalar lowering not yet implemented for TypedSortBy`; `X.all()->cast(@X)->project(…)` → `NotImplementedException: lowering not yet implemented for TypedCast` (an identity cast!) |
| A22 | `%25:00:00` ICEs while the date path raises cleanly | CONFIRMED | `\|%25:00:00` → `java.lang.IllegalStateException: time literal '%25:00:00' is out of range`; `\|%2020-01-01T25:00:00` → `com.legend.parser.ParseException: [1:2] invalid date literal … invalid hour: 25` |
| A22 / A15 | `TypedFrom` defaults an unspelled/String-spelled connection type to `"H2"` (two sites) | CONFIRMED (code) | `TypedFrom.java:82-83` `… instanceof TypedEnumValue ev ? String.valueOf(ev.value()) : "H2"` and the raw mirror at `:131-133` `… instanceof …EnumValue ev ? ev.value() : "H2"` — read in full, both are unconditional non-enum → `"H2"`. |

**First-pass counts — 90 rulings.** CONFIRMED **83** (4 of which I also flag as duplicating a
canonical entry elsewhere) · CONFIRMED-BUT-OVERSTATED **2** · DUPLICATE **5** · NOT-REPRODUCED **0** ·
MISATTRIBUTED **0** · BY-DESIGN **0**.
Per source file: A06 19 · A09 16 · A16 15 · A20 10 · A15 8 · A12 6 · A18 6 · A22 5 · CONFIRMED.md 5.
(Findings I did not personally re-run are simply absent from the table — I make no claim about them.)

---

## FALSE OR MATERIALLY OVERSTATED

### 1. `CONFIRMED.md` V24 — the repro does not show capture, and the stated mechanism is backwards

V24 claims `|[10]->map({x| let y = $x; [7]->map(y|$y)->toOne();})` ⇒ `7` is wrong, that it is
"α-equivalent" to the `z`-binder version, and that "the let-inliner substitutes `$y := $x` without
α-renaming, so an inner binder of the same name captures it."

Three problems:

**(a) The two programs are not α-equivalent.** In `[7]->map(z|$y)` the body's `y` is a *free*
occurrence of the let variable. Renaming the binder `z`→`y` puts that free occurrence under a binder
of the same name — that is a *capturing* rename, which is precisely what α-conversion is defined to
exclude. The two programs are different programs, so a different answer is not evidence of anything.

**(b) Under this compiler's own (consistent, lexical) shadowing rule, `7` is the RIGHT answer.**
With no `let` anywhere and therefore no inlining at all, shadowing behaves identically:
```
$ |[10]->map({y| [7]->map(y|$y)->toOne();})
[PLAN] SELECT list_extract(list_transform([10], y -> list_extract(list_transform([7], y -> y), 1)), 1) AS value
[EXEC-ROW] Integer(7)
```
The inner binder shadows the outer one. Same for `let q`/binder `q` (`Integer(7)`), and
`|[10]->map({x| let y = $x; [7]->map(y|$y + 100)->toOne();})` → `Integer(107)` — consistent
shadowing throughout.

**(c) The mechanism is the opposite of what was written.** The buggy SQL V24 quotes is
`list_transform([7], y -> y)`. If the substitution `$y := $x` *had* fired and *had* been captured,
the SQL would read `y -> x`. It reads `y -> y`, i.e. the substitution **did not fire** because the
name resolver correctly bound `$y` to the inner lambda parameter. V24 describes a capture that did
not happen.

**The underlying defect is nevertheless real** — A06 finding 1 states it correctly, and here is my
own from-scratch repro that actually isolates it (substituting `$x` into a scope that re-binds `x`):
```
$ |[10]->map({x| let y = $x; [7]->map(x|$y)->toOne();})      <-- TRUE capture
[G]    INTEGER mult=[1]
[PLAN] SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], x -> x), 1)), 1) AS value
[EXEC-ROW] Integer(7)                                        <-- WRONG, correct is 10

$ |[10]->map({x| let y = $x; [7]->map(w|$y)->toOne();})       <-- control, binder w
[PLAN] SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], w -> x), 1)), 1) AS value
[EXEC-ROW] Integer(10)                                       <-- correct
```
Here the free variable `$x` produced by the let-inliner genuinely lands under an inner binder named
`x` and is captured. **Verdict: CONFIRMED-BUT-OVERSTATED** — keep the defect, replace V24's repro and
mechanism sentence with A06 #1's (or the one above). As written, V24 would be refuted by anyone who
checked it.

### 2. `A09` F5 — the H2 half of the cross-backend claim does not reproduce

F5's headline is "`Float` -> `BigDecimal` on H2, `Integer` -> `BigInteger` on DuckDB". I re-ran a
dynamic pivot (`#TDS`-literal source, `~[tot:…->plus(), av:…->average()]`) on all three backends with
my own fixture. With integral averages and with non-integral averages (values 1,2 and 2,3 so
`av` = 1.5 / 2.5, matching the filed output's values):
```
DuckDB  [COLS] city:String '2011__|__tot':Integer '2011__|__av':Float …
        [ROW]  java.lang.String(NYC) | java.math.BigInteger(3) | java.lang.Double(1.5) | java.math.BigInteger(5) | java.lang.Double(2.5)
H2      [COLS] city:String '2011__|__tot':Integer '2011__|__av':Float …
        [ROW]  java.lang.String(NYC) | java.lang.Long(3)       | java.lang.Double(1.5) | java.lang.Long(5)       | java.lang.Double(2.5)
SQLite  [PLAN-ERROR] com.legend.sql.dialect.DialectCapability: pivot reached a dialect without a PIVOT strategy
```
* **Reproduces:** `Integer`-declared pivot column carries `BigInteger` on DuckDB (vs `Integer`/`Long`
  in every non-pivot lane) — the codec really is bypassed.
* **Reproduces:** every pivot column's `multiplicity()` is `null` (my `[COLS]` line prints a bracket
  only when non-null; the pivot columns print none, the ordinary columns print `[1]`).
* **Does NOT reproduce:** `Float` → `BigDecimal` on H2. I get `java.lang.Double` on H2, identical to
  DuckDB. Since `H2.normalize`'s `BigDecimal→double` arm cannot fire with `type == null`, a
  `BigDecimal` would indeed ride out — but H2 2.1.214 did not hand one back for `AVG` here, so the
  claim as stated (and the "`PureAsserts.equalScalar(BigDecimal 1.5, Double 1.5) = false`, so the
  same pivot compares unequal between backends" consequence) is not supported by my run.

**Verdict: CONFIRMED-BUT-OVERSTATED** — the mechanism and the DuckDB half stand; the H2 `Float →
BigDecimal` half and the cross-backend-inequality consequence should be dropped or re-derived with
the exact H2 session that produced it.

### 3. Smaller imprecisions worth correcting (not false)

* **A06 #9** — the preamble says "All four queries below declare exactly `Integer[1]`", but `max()`
  declares `Integer[0..1]` in my run (`Column[m, INTEGER, Bounded[0,1]]`), and six queries are listed,
  not four. The substantive claim (`Integer` / `Long` / `BigInteger` under one Pure type) is fully
  confirmed.
* **A06 header** — "CoreFn parse names with NO Pure native: 5". Only `new` has no native at all; the
  other four (`tds`, `typeAsDeclared`, `castAsDeclared`, `legacyNavigate`) *are* registered under
  `meta::legend::lite::*` and are merely absent from the bare-name index. The file's own §(b) says
  this correctly, so the headline number is a measurement against `FN_BY_BARE`, not an error — but it
  reads as stronger than it is.
* **A18 coercion matrix** — `IntOnFloat` gives `java.lang.Float(1.5)` here, not `java.lang.Double(1.5)`.
  Same category (a float carrier under `Integer[1]`); the class name in the file is wrong.
* **A16 "function bodies are never checked"** (title) vs its own body ("real and eventually loud —
  but it fires a phase late"). The body is right and the title over-reads; for a *monomorphic*
  function the declared return IS checked at Phase G½ (`TypeInferenceException: in function
  'fn::narrowRet': declares return type Integer but body returns String`). The genuinely unchecked
  case is the **generic** one (`CONFIRMED.md` V18), where no error ever fires and a `String` is
  delivered under `Integer[1]`. Those are two different defects and should not be conflated.

---

## SECOND PASS — files that landed while I was working (A23, A24, A27, A28, A29, A30, A32)

Sampled on their distinctive UNSOUND/CRASH claims, same method (my own fixtures, my own runs).

| source-file | finding | verdict | evidence |
|---|---|---|---|
| A23 | `decimalLitType` builds `SqlType.Decimal(p,s)` with `s > p` | CONFIRMED (code, read in full) | `SqlTyping.java:1188…`: `return typed(new SqlType.Decimal(Math.max(v.precision(), 1), v.scale()));` — for `0.01` `BigDecimal.precision()==1, scale()==2` ⇒ `Decimal(1,2)`. No guard anywhere on the path. |
| A23 | 38-scale decimal literal flips the carrier to `Double` | CONFIRMED (DUPLICATE of A06 #13 / V4) | `\|0.00000000000000000000000000000000000001D` → `[G] PrecisionDecimal[38,38]` → `[EXEC-ROW] Double(1.0E-38)`; `\|0.01D` → `BigDecimal(0.01)` |
| A24 | `toOne()` on a COLUMN does not assert; literal path does | CONFIRMED | `t::D.all()->project(~[x:p\|$p.iN->toOne()])` → `[EXEC-COL] x : Integer mult=[1]`, `SELECT t0.IN_V AS x`, `[EXEC-ROW] null`; `\|[]->toOne()` → `error('Cannot cast a collection of size 0 …')` |
| A24 | no scalar function null-checks its input; declared `[1]` returns NULL | CONFIRMED (sampled) | `…->toOne()->abs()` → `SELECT abs(t0.IN_V)` → `null` under `Integer[1]`; `…->toString()->toUpper()` → `null` under `String[1]` |
| A24 | `mod(7,0)`→NULL under `Integer[1]`; `format('100%%',[])`→NULL under `String[1]` | CONFIRMED | `SELECT MOD(MOD(7,0)+0,0)` → `null`; `SELECT printf('100%%', NULL)` → `null`. `rem(7,0)` raises — same-family inconsistency confirmed. |
| A24 | `hasDay/hasHour/…` on a COLUMN emit an INTEGER under `Boolean[1]` | CONFIRMED | `$p.d->hasDay()` → `[G] Column[x, BOOLEAN, [1]]`, `SELECT 1 AS x`, `[EXEC-ROW] java.lang.Integer(1)`; `$p.d->hasHour()` → `SELECT 0 AS x` → `Integer(0)`; literal `\|%2024-03-15->hasDay()` → `SELECT TRUE` → `Boolean(true)` |
| A24 | `times([Integer])` declared `Integer[1]` returns `Double` | CONFIRMED | `\|times([2,3,4])` → `[G] INTEGER[1]`, `SELECT list_aggregate([2,3,4],'product')` → `Double(24.0)`. (SQL spelling here is `list_aggregate(...,'product')`, not the filed `list_product` — same effect.) |
| A24 | `plus([true,false])` declared `Boolean[1]` returns `BigInteger` | CONFIRMED | `SELECT list_sum([TRUE, FALSE])` → `BigInteger(1)` under `[G] BOOLEAN[1]` |
| A24 | `lite::hash(String[1]):String[1]` returns a `Long` | CONFIRMED | `\|meta::legend::lite::hash('abc')` → `[EXEC-COL] value : String`, `[EXEC-ROW] Long(1924864467101078684)` |
| A24 | `toDecimal` claims `Decimal(38,18)`, emits another scale | CONFIRMED (DUPLICATE of A06 #14) | `\|1->toDecimal()` → declared `Decimal(38,18)`, `SELECT CAST(1 AS DECIMAL(38,0))` |
| A27 | 2. execution DDL drops `NOT NULL` and `PRIMARY KEY` | CONFIRMED | own `Ddl.createTable` probe: compiler schema = `Column[ID, INTEGER, Bounded[1,1]], Column[NAME, STRING, Bounded[1,1]]`; `DUCK_EXEC`/`H2_EXEC` = `Create Table T("ID" INTEGER, "NAME" VARCHAR(20), …);` — no constraint at all. `ENGINE_TEXT` = `… ID INT NOT NULL, … PRIMARY KEY(ID);`. |
| A27 | 3. Pure `Integer` (64-bit) spelled `INTEGER`/`SMALLINT`/`TINYINT` | CONFIRMED | same run: `BIGV BIGINT, SMALLV SMALLINT, TINYV TINYINT` all type as Pure `INTEGER` in the compiler schema, and are spelled back at 64/16/8 bits; `Integer_` → `INTEGER` (exec) / `INT` (engine text) |
| A27 | 4. Pure `Float` (double) spelled `REAL` (4-byte) | CONFIRMED | `RE Real[]` → compiler `Column[RE, FLOAT, …]`, DDL `"RE" REAL` |
| A27 | 7. `Ddl.spell` throws a bare `IllegalStateException` for `OTHER` | CONFIRMED | `Ddl.createTable(..., DUCK_EXEC)` on a table with an `OTHER` column → `java.lang.IllegalStateException: no DDL spelling for store column type Other[]` (H2_EXEC identical). `ModelContext.findTable` on the same table raises a clean `ModelException`, so the ICE is specific to the DDL generator. |
| A28 | instance equality erases the class on the ordinary execute path | CONFIRMED | `\|^eq::A(x=1,y='p') == ^eq::B(x=1,y='p')` → `SELECT {'x':1,'y':'p'} = {'x':1,'y':'p'}` → `Boolean(true)`; `^eq::Sub(x=1) == ^eq::Sup(x=1)` → `Boolean(true)`; `[^eq::A(...)]->contains(^eq::B(...))` → `coalesce(list_contains(…), FALSE)` → `Boolean(true)` |
| A28 | `<<equality.Key>>` ignored on the ordinary execute path | CONFIRMED | `\|^eq::K(k=1,n='a') == ^eq::K(k=1,n='b')` (only `k` is keyed) → `SELECT {'k':1,'n':'a'} = {'k':1,'n':'b'}` → `Boolean(false)`; the non-key property is compared |
| A29 | `PlanText.pureName` has no `PrecisionDecimal` arm | CONFIRMED | `planToString(executionPlan({\|let p = 1.5d; …}, …))` → `NotImplementedException: plan: pure type name for PrecisionDecimal[precision=38, scale=1]`; the same query with `let p = 1.5` renders `Sequence(…)`. A29's own **REFUTATION** of the forwarded "any DECIMAL store column" consequence is correct and I endorse it. |
| A30 | `percentile` / `stdDev` declared `[1]` return NULL on an empty group | CONFIRMED (runtime half) | `…->filter(x\|$x.qty>10000)->groupBy(~[], ~[s:…->percentile(0.5)])` → `[EXEC-COL] s : Number mult=[1]`, `SELECT QUANTILE_CONT(…)`, `[EXEC-ROW] null`; same for `stdDev`. **Not adjudicated:** the "real Pure declares `[0..1]`" half — I have no independent legend-pure oracle either (this is exactly `CONFIRMED.md` V8's point). |
| A30 | `relation::limit` accepts an empty limit and silently drops it | CONFIRMED | `…->limit([])` compiles → `SELECT t0.WNAME AS a FROM T_WIDGET AS t0` (no `LIMIT`), all rows returned, no diagnostic |
| A32 | 2. H deletes the user's explicit `->toOne()` | DUPLICATE (canonical: CONFIRMED.md V20 / A12 H1) | see V20 row |
| A32 | 7. `->map(p\|$p.<nav>->toOne().<leaf>)` escapes as `IllegalStateException` | CONFIRMED | `biz::Emp.all()->map(p\|$p.addrs->toOne().street)` (Zoe has no address) → `SELECT t1.STREET AS u_map__street … LEFT OUTER JOIN` → `IllegalStateException: NULL cell reached COLLECTION egress …`. Control `map(p\|$p.addrs.street)` on the same data succeeds. |

**Second-pass counts — 21 rulings.** CONFIRMED **20** (2 of which duplicate a canonical entry) ·
DUPLICATE **1** · NOT-REPRODUCED **0** · MISATTRIBUTED **0** · BY-DESIGN **0** ·
CONFIRMED-BUT-OVERSTATED **0**.

**Grand total — 111 rulings.** CONFIRMED **103** (6 of which duplicate a canonical entry elsewhere) ·
CONFIRMED-BUT-OVERSTATED **2** · DUPLICATE **6** · NOT-REPRODUCED **0** · MISATTRIBUTED **0** ·
BY-DESIGN **0**.
Per source file: A06 19 · A09 16 · A16 15 · A20 10 · A15 8 · A24 8 · A12 6 · A18 6 ·
CONFIRMED.md 5 · A22 5 · A27 4 · A23 2 · A28 2 · A30 2 · A32 2 · A29 1.

**Not adjudicated in the second pass:** A19, A21, A25, A26, A33 (A33 never appeared), A37, the
oracle-dependent bulk of A30 (its 36 invented signatures / 246 missing functions / 131 argument
divergences — all require the real finos/legend-pure sources, which this environment does not have),
and the non-sampled tail of A23/A24/A27/A28/A29/A32. I make no claim about those.

---

## METHOD NOTES / LIMITS

* Every "declared `[1]` but returns null" claim in this table was verified against the **printed
  `Multiplicity` record**, not a rendered string — e.g. `Column[name=pn, type=STRING,
  multiplicity=Bounded[lower=1, upper=1]]`. Lower bound really is 1 in every case I marked CONFIRMED.
* Every "ICE on user input" claim was reached from **Pure query text I typed**, through
  `Compiler.compileQuery` / `Compiler.plan` / `Compiler.execute`. No hand-built AST, no reflection
  into checker internals, except the two deliberately unit-level probes (`Executor.pureOfSqlType`
  for A09 F18 and `LiteralText.parse`/`Json.parse` for A09 F9/F10), where the finding is itself about
  a decoder entry point.
* Dead-code claims (A06 #16, A09 F17) were grepped over the **whole repository** — `core/src/main`,
  `core/src/test`, `pct/`, `nlq/`, `tools/`, `experiments/` — not just main.
* Cross-backend claims (A09 Table 1, F1, F2, F3, F5, F6, F7, F11; A20 INT32 widening) were re-run by
  me on DuckDB **and** SQLite **and** H2 via `XB.java`, with `type:` matched to each session so
  `Compiler.dialectOf` binds the right dialect. Two claims changed under that treatment (F5 above,
  and F7's SQLite arm which is *stronger* than filed: `cInt+1` also silently succeeds as `Long`).
* Counts recomputed rather than trusted: A06's registry census (all six numbers), A18's coercion
  score (26 of 36 cases re-run, chosen to cover **all** 11 UNSOUND and **all** 6 LOUD claims), A09
  F18's accept/refuse table (34 spellings).
* **Not adjudicated:** the non-UNSOUND/non-CRASH tail of A15 (`allVersions` degradation, cross-store
  Q7, `asOfJoin` temporal check, the three `asOfJoin` DOC-LIEs), A12 H7/H8/H9, A18's view-aggregate
  and `+`-local-property findings, A09 F12–F16/F19–F20 beyond what is tabled, and A19/A21/A23 (A19
  and A23 landed near the end of my run; A21 is a CSV census, not adjudicable by repro). I make no
  claim about those either way.
