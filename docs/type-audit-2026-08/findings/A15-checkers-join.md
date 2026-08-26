# A15 — checkers: the JOIN family (join / asOfJoin / from / getAll / navigate + AssociationJoins)

Scope read in full: `compiler/spec/{JoinChecker,AsOfJoinChecker,FromChecker,GetAllChecker,NavigateChecker,Checkers}.java`,
`compiler/spec/typed/{TypedJoin,TypedJoinSlot,TypedAsOfJoin,TypedFrom,TypedGetAll,TypedNavigate,TypedMilestonedAccess}.java`,
`resolver/AssociationJoins.java`, `resolver/ChainedExists.java`, plus the schema-algebra owner
`compiler/spec/InferenceKernel.java:803-845` and the join lowering `lowering/Lowerer.java:1890-1935`.

Fixtures used: `/home/user/probe/fx` and my own models under `/tmp/a15/`
(`nav2.pure`+`nav.ddl.sql`, `dup.pure`+`dup.ddl.sql`, `ms.pure`+`ms2.ddl.sql`, `xs2.pure`+`xs.ddl.sql`,
`qcol.pure`+`qcol.ddl.sql`).

---

## THE SCHEMA-COMBINATION RULE (answer to Q1)

There is exactly **one** rule and it is signature-driven. The four join surfaces register
`Relation<T+V>[1]` (`builtin/Pure.java:1487,1495,1174,1175`), and `T+V` is evaluated in
`InferenceKernel.resolveSchemaAlgebra`:

```
813:            case UNION -> {
814:                List<Type.Column> cols = new ArrayList<>(lr.columns());
815:                if (right instanceof Type.RelationType rr) {
816:                    for (Type.Column c : rr.columns()) {
819:                        if (lr.columns().stream().anyMatch(e -> sameColumn(e.name(), c.name()))) {
820:                            throw new SchemaInvariantException("the column '" + c.name()
821:                                    + "' already exists in the relation " + lr.typeName());
822:                        }
823:                        cols.add(c);        // <-- type AND multiplicity copied VERBATIM
824:                    }
825:                }
828:                return new Type.RelationType(cols, lr.dynamicColumns());
```

So: **left columns in order, then right columns in order; a name collision is a loud
`SchemaInvariantException` (a `TypeInferenceException` subclass — a clean user error, NOT the
`RelationType` constructor's `IllegalArgumentException` ICE); nothing is dropped, renamed, or
silently deduped; and the right side's column MULTIPLICITIES are copied unchanged regardless of
join kind.** That last clause is finding #1.

The 5-argument overloads take a bespoke path (`Checkers.prefixedUnion`, `Checkers.java:48-71`)
that prefixes right columns and raises its own clean error on a manufactured collision.

**Collision behaviour, all four kinds, executed** (`/tmp/a15/qc_<K>.pure`, both sides carry `id`):

```
#### COLLISION INNER / LEFT / RIGHT / FULL   (identical for all four)
[G-ERROR] com.legend.compiler.spec.SchemaInvariantException: the column 'id' already exists in the relation (id:Integer[1], name:String[1])
```
No ICE, no silent drop, no silent rename. **This part is sound.**

**JOIN KINDS.** `meta::pure::functions::relation::JoinKind` = `{LEFT, RIGHT, FULL, INNER}`
(`builtin/Pure.java:718-723`). There is **no CROSS**; `JoinKind.CROSS` errors cleanly
(`enumeration ... has no value 'CROSS'`). A cross join is expressible as a constant-true condition
(verified: 2x2 -> 4 rows). The legacy TDS spelling adds `JoinType{INNER,LEFT_OUTER,RIGHT_OUTER,FULL_OUTER}`
which desugars 1:1 (`JoinChecker.java:177-184`), and `JoinType.OUTER` (present in `model/JoinType.java:20`)
has **no** `JoinKind` counterpart — it throws `unknown JoinType value 'OUTER'`.

---

## FINDINGS

### [UNSOUND — TOP SEVERITY] A `[1]`-declared column is NULL after LEFT / RIGHT / FULL / ASOF join
**Evidence** `InferenceKernel.java:823` (`cols.add(c)` — verbatim multiplicity) is the only place the
join output schema is built for the 4-arg form; `Checkers.java:68` (`new Type.Column(name, c.type(),
c.multiplicity())`) for the prefix form; `resolver/StoreResolver.java:829-832` and
`resolver/AssociationJoins.java:1069-1071` for the resolver-synthesized nav joins. **None of them
consults the join kind.** `Lowerer.java:1919-1925` maps the kind straight to
`SqlSource.Join.Kind.{INNER,LEFT,RIGHT,FULL}`, so the null-extended side is real SQL NULL.

The type system **can** express nullability and does elsewhere: a store table reference honours
`NOT NULL` -> `[1]` and nullable -> `[0..1]`:
```
$ probe.sh nav2.pure tref.pure navt::NavRuntime nav.ddl.sql
[G] type=Relation<(ID:Integer[1], FIRST_NAME:String[1], AGE_VAL:Integer[1], PRIMARY_ADDR_ID:Integer[0..1])> mult=[1]
```
So this is an omission in the join rule, not a missing capability.

**Repro** `/tmp/a15/q_LEFT.pure` (also `q_RIGHT`, `q_FULL`), model `/tmp/a15/empty.pure`:
```
#TDS
  id, name
  1, Alice
  2, Bob
#->join(#TDS
    person_id, score
    1, 90
    9, 77
  #, meta::pure::functions::relation::JoinKind.LEFT, {l, r | $l.id == $r.person_id})
```
**Actual output — LEFT:**
```
[G] type=Relation<(id:Integer[1], name:String[1], person_id:Integer[1], score:Integer[1])> mult=[1]
[PLAN] SELECT * FROM (VALUES (1,'Alice'),(2,'Bob')) AS _tds0(id,name)
       LEFT OUTER JOIN (VALUES (1,90),(9,77)) AS _tds1(person_id,score) ON _tds0.id = _tds1.person_id
[EXEC-COL] person_id : Integer [INTEGER] mult=[1]
[EXEC-COL] score : Integer [INTEGER] mult=[1]
[EXEC-ROW] Integer(1) | String(Alice) | Integer(1) | Integer(90) |
[EXEC-ROW] Integer(2) | String(Bob) | null | null |          <-- two [1] columns are NULL
```
**RIGHT** (left side null-extended): `[EXEC-ROW] null | null | Integer(9) | Integer(77) |`
**FULL** (both): rows 2 and 3 above, both NULL-bearing.
**INNER**: no violation (verified — 1 row, no NULLs).

The same violation reaches through every other join surface:
* **5-arg prefix** (`/tmp/a15/qp1.pure`): `r_id:Integer[1], r_score:Integer[1]` -> `Integer(2)|String(Bob)|null|null|`
* **legacy TDS shared-key** (`/tmp/a15/lq1.pure`, `JoinType.LEFT_OUTER, ['id']`): `score:Integer[1]` -> `Integer(2)|String(Bob)|null|`
* **asOfJoin** — lowers to `ASOF LEFT JOIN` (`/tmp/a15/aq1.pure`):
  `[PLAN] ... ASOF LEFT JOIN ... ON _tds0.trade_time > _tds1.quote_time`
  `[EXEC-ROW] Integer(2) | DateWithSecond(2024-01-15T09:00:00+0000) | null | null | null |`
  with `quote_id:String[1], quote_time:DateTime[1], price:Integer[1]`.
  Same for the 4-arg match+key form when the key condition fails (`/tmp/a15/aq_cond.pure`).
* **an always-false condition on a LEFT join** (`{l,r|false}`, `/tmp/a15/qc_false.pure`) — one row, both right columns NULL.
* **cross-store LEFT join** (`/tmp/a15/xq3.pure`) — `r_ID:Integer[1]` -> `null`.

**Why it matters** This is the canonical unsoundness: the compiler hands downstream consumers
(`toOne()`, arithmetic, decoding, serialization) a `[1]` guarantee the value violates. Every outer
join in the product is affected. The fix is one line per site — widen the null-extended side's
columns to `[0..1]` (lower bound 0) when the kind is LEFT/RIGHT/FULL/ASOF.

---

### [UNSOUND — TOP SEVERITY] A `[1]`-declared association navigation returns NULL (dangling FK, nullable FK, milestoned)
**Evidence** Every resolver-built navigation join is a LEFT join —
`resolver/StoreResolver.java:3186-3190 leftKind()` is used at `AssociationJoins.java:1074`,
`StoreResolver.java:1931,2048`, `ChainedExists.java:188`, `NavMaterializer.java:583,649,743`,
`CorrelatedSubselects.java:171,240,323,809,1205,1316,1526`. `StoreResolver.java:838` is the only site
that can pick INNER (`rowPreserving ? leftKind() : innerKind()`), and even there the schema built at
lines 829-832 copies `c.multiplicity()` verbatim. Nothing anywhere validates that the mapping's join
is total, and `Property.multiplicity()` from the model is passed through untouched by
`TypedPropertyAccess` typing.

**Repro A — dangling FK.** Model `/tmp/a15/nav2.pure` declares `Association navm::Person_Address
{ person: Person[1]; addresses: Address[*]; }`; `T_ADDRESS` row 4 has `PERSON_ID=99` (no such person).
```
$ probe.sh nav2.pure nq2.pure navt::NavRuntime nav.ddl.sql
   query: navm::Address.all()->project(~[city:a|$a.city, owner:a|$a.person.firstName])
[G] type=Relation<(city:String[1], owner:String[1])> mult=[1]
[EXEC-COL] owner : String [STRING] mult=[1]
[EXEC-ROW] String(New York) | String(John) |
[EXEC-ROW] String(Boston)   | String(John) |
[EXEC-ROW] String(Chicago)  | String(Jane) |
[EXEC-ROW] String(Nowhere)  | null |            <-- Person[1] navigation is NULL
```
The typed HIR itself carries the false claim (`$p.primaryAddr :: navm::Address[1]`):
```
TypedPropertyAccess :: String[1]
  TypedPropertyAccess :: navm::Address[1]
   .property = primaryAddr
   .info = ExprType[type=ClassType[fqn=navm::Address], multiplicity=Bounded[lower=1, upper=1]]
```

**Repro B — nullable FK (the brief's `PRIMARY_ADDR_ID`).** `primaryAddr: Address[1]` joined on
`T_PERSON.PRIMARY_ADDR_ID = T_ADDRESS.ID`; Bob and Zoe have `PRIMARY_ADDR_ID = NULL`:
```
$ probe.sh nav2.pure nq4.pure navt::NavRuntime nav.ddl.sql
   query: navm::Person.all()->project(~[name:p|$p.firstName, pa:p|$p.primaryAddr.street])
[G] type=Relation<(name:String[1], pa:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS name, t1.STREET AS pa
       FROM T_PERSON AS t0 LEFT OUTER JOIN T_ADDRESS AS t1 ON t0.PRIMARY_ADDR_ID = t1.ID
[EXEC-COL] pa : String [STRING] mult=[1]
[EXEC-ROW] String(John) | String(123 Main St) |
[EXEC-ROW] String(Jane) | String(789 Main Rd) |
[EXEC-ROW] String(Bob)  | null |
[EXEC-ROW] String(Zoe)  | null |
```

**Repro C — the graph shape lies the same way** (`/tmp/a15/nq6.pure`, `/tmp/a15/nq7.pure`):
```
[EXEC-JSON] [{"city":"New York","person":{"firstName":"John"}}, ... ,{"city":"Nowhere","person":null}]
[EXEC-JSON] [{"firstName":"John","primaryAddr":{...}}, ... ,{"firstName":"Bob","primaryAddr":null},{"firstName":"Zoe","primaryAddr":null}]
```
`person: Person[1]` and `primaryAddr: Address[1]` both serialize as JSON `null`.

**Repro D — milestoned access (`TypedMilestonedAccess`).** Model `/tmp/a15/ms.pure`,
`classification: q::Classification[1]`, product P2's `ctype` matches no classification:
```
$ probe.sh ms.pure mq1.pure q::RT ms.ddl.sql
   query: q::Product.all(%2015-08-20)->project(~[n:p|$p.name, c:p|$p.classification(%2015-08-20).ctype])
[G] type=Relation<(n:String[1], c:String[1])> mult=[1]
[EXEC-ROW] String(P1) | String(STOCK) |
[EXEC-ROW] String(P2) | null |
```

**Repro E — `navigate` PRE_MAP sub-row.** `NavigateChecker.java:131-133` fixes the sub-row column at
`[1]` ("§3.4: rows multiply, the SUB-ROW COLUMN is to-one"); the lowering is a LEFT join:
```
$ probe.sh nav2.pure navq1.pure navt::NavRuntime nav.ddl.sql
  #>{navs::NavDb.T_PERSON}#->navigate(~pa: #>{navs::NavDb.T_ADDRESS}#, {r,a| $r.PRIMARY_ADDR_ID == $a.ID})
[G] type=Relation<(... , pa:(ID:Integer[1], PERSON_ID:Integer[1], COUNTRY_ID:Integer[0..1], STREET:String[1], CITY:String[1])[1])>
[EXEC-ROW] Integer(3) | String(Bob) | Integer(45) | null | null | null | null | null | null |
```
The whole `[1]` sub-row and all four of its `[1]` inner columns are NULL for Bob and Zoe.

**Why it matters** Every `[1]` and `[1..*]` association end in every mapping is an unverified
promise. Legend's own `->toOne()`-free code paths, decoders and graph serializers rely on it.

---

### [UNSOUND] A to-one navigation whose mapping join matches N>1 rows silently DUPLICATES rows (tabular) or CRASHES (graph)
**Evidence** No site validates that an association's join is functional. `AssociationJoins.java:985-989`
only rejects a *non-concrete* multiplicity; a concrete `[0..1]`/`[1]` end is joined the same way as
`[*]` (`associationJoin` -> `leftKind()`), and `Property.multiplicity()` flows into the column type
untouched.

**Repro — tabular.** `/tmp/a15/dup.pure`: `Association dupm::Item_Owner { items: Item[*]; owner: Owner[0..1]; }`,
`Join Item_Owner(T_ITEM.ZONE = T_OWNER.ZONE)`; zone 1 has ONE item and TWO owners.
```
$ probe.sh dup.pure dq3.pure dupt::DupRuntime dup.ddl.sql   (baseline: Item.all() -> 2 rows)
[EXEC-ROW] String(Widget) |
[EXEC-ROW] String(Gadget) |

$ probe.sh dup.pure dq1.pure dupt::DupRuntime dup.ddl.sql
   query: dupm::Item.all()->project(~[item:i|$i.iname, own:i|$i.owner.oname])
[G] type=Relation<(item:String[1], own:String[0..1])> mult=[1]
[PLAN] SELECT t0.INAME AS item, t1.ONAME AS own FROM T_ITEM AS t0 LEFT OUTER JOIN T_OWNER AS t1 ON t0.ZONE = t1.ZONE
[EXEC-ROW] String(Widget) | String(Alice) |
[EXEC-ROW] String(Widget) | String(Bob)   |     <-- Widget duplicated; owner is declared [0..1]
[EXEC-ROW] String(Gadget) | String(Carol) |
```
2 items in, 3 rows out. The `[0..1]` claim is violated by silent row multiplication.

**Repro — graph, same model.** The graph lowering *trusts* the `[0..1]` claim and emits a **scalar
subquery**, which then blows up with a raw JDBC error:
```
$ probe.sh dup.pure dq4.pure dupt::DupRuntime dup.ddl.sql
  dupm::Item.all()->graphFetch(#{ dupm::Item { iname, owner { oname } } }#)->serialize(...)
[PLAN] SELECT CAST(coalesce(to_json(list(json_object('iname', t0.INAME, 'owner',
       (SELECT json_object('oname', t1.ONAME) AS result FROM T_OWNER AS t1 WHERE t0.ZONE = t1.ZONE)) ...
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: More than one row returned by a subquery
             used as an expression - scalar subqueries can only return a single row.
```

**Repro — milestoned `[1]` end with two overlapping versions** (`/tmp/a15/ms.pure` + `ms2.ddl.sql`,
classification 'Cur' and 'Dup' both valid at 2015-08-20):
```
[EXEC-ROW] String(P1) | String(STOCK) |
[EXEC-ROW] String(P1) | String(STOCK) |     <-- one product, two rows, classification is [1]
[EXEC-ROW] String(P2) | null |
... graph form: [EXEC-ERROR] java.sql.SQLException: Invalid Input Error: More than one row returned by a subquery
```

**Why it matters** One unchecked claim produces two different failures: silent wrong answers in the
tabular path and an internal SQL error surfaced verbatim to the user in the graph path.

---

### [UNSOUND / DATA LOSS] `FULL_OUTER` legacy shared-key join drops the right side's key values
**Evidence** `JoinChecker.java:239-243`:
```
243:        boolean rightKeeps = kind.value().equals("RIGHT_OUTER");
```
The shared-key desugar renames one side's key copy away and `TypedSelect`s it out
(`JoinChecker.java:264-296`). Only `RIGHT_OUTER` keeps the right's copy; `FULL_OUTER` — where
**neither** side is preserved — keeps only the LEFT copy, so a right-only row loses its key. The
correct emission needs `coalesce(l.k, r.k)`; the comment at lines 239-243 asserts the
"OUTER-PRESERVED side keeps its keys" rule without noticing FULL has no such side.

**Repro** `/tmp/a15/lqk_FULL_OUTER.pure`, left `{(1,Alice),(3,Carol)}`, right `{(1,90),(2,80)}`, `['id']`:
```
[G] type=Relation<(id:Integer[1], name:String[1], score:Integer[1])> mult=[1]
[PLAN] SELECT _tds0.id, _tds0.name, t0.score
[EXEC-ROW] Integer(1) | String(Alice) | Integer(90) |
[EXEC-ROW] Integer(3) | String(Carol) | null |
[EXEC-ROW] null       | null          | Integer(80) |    <-- id should be 2, the value is silently lost
```
For comparison `RIGHT_OUTER` (which does apply `rightKeeps`) is correct — and note it also **reorders
the output schema** to `(name, id, score)` while every other kind produces `(id, name, score)`:
```
#### SHARED-KEY RIGHT_OUTER
[G] type=Relation<(name:String[1], id:Integer[1], score:Integer[1])> mult=[1]
[EXEC-ROW] String(Alice) | Integer(1) | Integer(90) |
[EXEC-ROW] null          | Integer(2) | Integer(80) |
```
so the same logical operation yields a kind-dependent column ORDER — a second, lesser defect.

---

### [UNSOUND / SILENT WRONG COLUMN] `Checkers.prefixedUnion` uses exact-name uniqueness while the type system's column identity is quote-insensitive
**Evidence** Two different identity rules for the same concept:
* `InferenceKernel.java:442-447` — `sameColumn(a,b)` strips a quoting wrapper, and the schema UNION at
  line 819 uses it.
* `Checkers.java:56-67` — `prefixedUnion` uses a plain `LinkedHashSet<String>` + `names.add(name)`
  (exact equality), and `Type.RelationType`'s constructor (`Type.java:527-533`) also uses exact equality.

So a prefixed join can mint two columns the type system itself regards as the same name, and a later
by-name read silently picks the wrong one.

**Repro** `/tmp/a15/qcol.pure` (`T_Q` has a store-quoted column `"r_ID"`), `/tmp/a15/qcol.ddl.sql`
(`T_Q = (ID=1, "r_ID"=777)`, `T_R = (ID=1, RV='rv1')`):
```
$ probe.sh qcol.pure qcolj.pure qs::QRt qcol.ddl.sql
  #>{qs::QDb.T_Q}#->join(#>{qs::QDb.T_R}#, JoinKind.INNER, {a,b|$a.ID==$b.ID}, 'r')
[G] type=Relation<(ID:Integer[1], "r_ID":Integer[0..1], r_ID:Integer[1], r_RV:String[0..1])> mult=[1]
[EXEC-ROW] Integer(1) | Integer(777) | Integer(1) | String(rv1) |
```
Two columns, `"r_ID"` and `r_ID`. Then:
```
$ probe.sh qcol.pure qcolj3.pure qs::QRt qcol.ddl.sql        (... ->select(~[r_ID]))
[G] type=Relation<("r_ID":Integer[0..1])> mult=[1]
[PLAN] SELECT t0."r_ID"
[EXEC-ROW] Integer(777) |
```
The user asked for `r_ID` (the join's prefixed right-side ID, value **1**) and silently got the left
side's `"r_ID"` (value **777**), with the multiplicity flipping from `[1]` to `[0..1]` too. The
non-prefix path *does* catch the same pair loudly:
```
$ probe.sh qcol.pure qcolj2.pure qs::QRt
[G-ERROR] SchemaInvariantException: the column 'r_ID' already exists in the relation ("r_ID":Integer[0..1])
```
Two implementations of one rule, disagreeing, with a wrong-value outcome.

---

### [CRASH/ICE] lite-INTERNAL join/navigate vocabulary is reachable from user query text by FQN, then throws `NotImplementedException`
**Evidence** `CoreFn.of` (`compiler/spec/CoreFn.java:159-181`) applies the internal-only guard on the
**bare-name branch only**:
```
167:            if (com.legend.builtin.Pure.INTERNAL_DESUGAR.contains(parseName)) {
168:                return Optional.empty();
169:            }
...
177:        if (parseName.contains("::")
178:                && !com.legend.builtin.Pure.nativeFunctionsAt(parseName).isEmpty()) {
179:            int sep = parseName.lastIndexOf("::");
180:            return Optional.ofNullable(BY_NAME.get(parseName.substring(sep + 2)));   // <-- no guard
181:        }
```
`Lite.LEGACY_NAVIGATE` is in `INTERNAL_DESUGAR` (`Pure.java:851`) yet its FQN gets through; and
`JoinChecker.java:47-48` computes `liteSlotSpelling` from the FQN **before** canonicalizing it away at
lines 52-54, so `meta::legend::lite::join` reaches the slot path.

**Repro 1** — slot join, `/tmp/a15/slot2.pure`:
```
#>{navs::NavDb.T_PERSON}#->meta::legend::lite::join(~pa: #>{navs::NavDb.T_ADDRESS}#, {r,a| $r.PRIMARY_ADDR_ID == $a.ID})

[G] type=Relation<(ID:Integer[1], ..., pa:(ID:Integer[1], ...)[1])> mult=[1]     <-- type-checks
[PLAN-ERROR] com.legend.error.NotImplementedException: TypedJoinSlot (pipeline slot join 'pa')
             escaped Phase H store resolution - a resolver gap, not a missing lowering rule
```
The bare 3-arg spelling is correctly refused (`no overload of 'join' matches 3 argument(s)`).

**Repro 2** — `/tmp/a15/lnav2.pure`:
```
#>{navs::NavDb.T_PERSON}#->meta::legend::lite::legacyNavigate(~pa: navm::Address.all(), #>{navs::NavDb.T_ADDRESS}#, {r,a| ...})
[G] type=Relation<(..., pa:navm::Address[1])> mult=[1]
[PLAN-ERROR] com.legend.error.NotImplementedException: class query under TypedNavigate is not resolvable yet (H2 vocabulary)
```
Bare `legacyNavigate` is refused (`no candidates at all`); the FQN is not.

`NotImplementedException` (`error/NotImplementedException.java:8`) is a plain `RuntimeException`, not a
`LegendCompileException` — so this is an internal error escaping on input a user can plainly write,
and its text blames an internal "resolver gap".

---

### [SILENT FALLBACK] An empty collection `[]` and a `Boolean[0..1]` are both accepted as a `Boolean[1]` join condition
**Evidence** `Typer.java:2144-2151` skips the result-multiplicity check entirely for a Nil body:
```
2144:        boolean nilBody = body.info().type() instanceof Type.ClassType nbc
2146:                && PlatformTypes.NIL.equals(nbc.fqn());
2148:        if (!nilBody) {
2149:            kernel.unifyMultResult(...);
2151:        }
```
and `InferenceKernel.unifyMultResult` (`:257-275`) checks **only the upper bound**
("LOWER bound stays LENIENT for lambda results"), so `[0..1]` satisfies `[1]`.

**Repro** `/tmp/a15/b03.pure` — `{l, r | []}`:
```
[G] type=Relation<(id:Integer[1], name:String[1], pid:Integer[1], score:Integer[1])> mult=[1]
[PLAN] SELECT * FROM (VALUES (1,'Alice')) AS _tds0(id,name)
       JOIN (VALUES (1,90)) AS _tds1(pid,score) ON NULL
```
`/tmp/a15/b02.pure` — `{l, r | meta::legend::lite::isNumeric($l.name)}` (declared `Boolean[0..1]`):
accepted; lowered to `ON lower(_tds0.name) = upper(_tds0.name)`.
`ON NULL` is at least portable — verified 0 rows (INNER) / 1 row (LEFT) on DuckDB, SQLite and H2
(`/tmp/a15/OnNull.java`) — but a `[]`-bodied predicate silently becoming "match nothing" is a
defaulting the repo forbids. A **non-Boolean** body is correctly refused
(`{l,r|$l.id}` -> `expected Boolean, got Integer`).

---

### [SILENT FALLBACK -> DIALECT-DIVERGENT RESULTS] A type-mismatched join comparison (String = Integer) is never rejected
**Evidence** `equal` is registered as `(Any[*], Any[*]) -> Boolean[1]`, so the condition type-checks;
the lowering emits the comparison verbatim. Nothing in `JoinChecker`/`AsOfJoinChecker` inspects operand
types.

**Repro** `/tmp/a15/qc_mismatch.pure`:
```
[G] type=Relation<(id:Integer[1], name:String[1], pid:Integer[1], score:Integer[1])> mult=[1]   (compiles clean)
[PLAN] ... JOIN (VALUES (1,90),(2,91)) AS _tds1(pid,score) ON _tds0.name = _tds1.score
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Could not convert string 'Alice' to INT32 ...
```
**All three databases, same SQL** (`/tmp/a15/ThreeDb.java`; `L=(1,'Alice'),(2,'Bob'),(3,'90')`, `R=(1,90),(2,91)`):
```
=== jdbc:duckdb:            SELECT ... FROM L JOIN R ON L.name = R.score
    ERROR SQLException: Conversion Error: Could not convert string 'Alice' to INT32
=== jdbc:sqlite::memory:
    row: Integer(3) | String(90) | Integer(1) | Integer(90) |
    rows=1                                                   <-- silently coerced and MATCHED
=== jdbc:h2:mem:...
    ERROR JdbcSQLDataException: Data conversion error converting "Alice" [22018-214]
```
End-to-end through legend-lite with a store model (`/tmp/a15/Dialects.java`, `AV:String = ID:Integer`):
DuckDB `SQLException: Could not convert string 'a1' to INT32`; SQLite `rows=0`, no error.
**One program, three behaviours, no compile-time diagnosis.**

Conditions referencing only ONE side (`{l,r|$l.id > 1}`) or NEITHER side (`{l,r|1==1}`) are accepted
and produce the SQL-correct semi-/cross-product — that is legitimate and matches SQL.

---

### [SILENT REWRITE / INCONSISTENCY] `JoinChecker.sideAgnosticTdsCond` silently re-points a condition read to the other side — but only for the getter spelling
**Evidence** `JoinChecker.java:88-158`. A `getInteger('col')` read whose column is absent on its own
side and present on the other is rewritten to the other parameter before typing.

**Repro** `/tmp/a15/sw1.pure` vs `/tmp/a15/sw2.pure` (left has `id`, right has `pid`):
```
{a, b | $a.getInteger('pid') == $b.getInteger('id')}
   -> compiles; [PLAN] ... ON _tds1.pid = _tds0.id      (both reads silently swapped)
{a, b | $a.pid == $b.id}
   -> [G-ERROR] TypeInferenceException: relation has no column 'pid'
```
Same logical mistake, two opposite outcomes. Also note that `swapMisplacedReads`' documented
"Ambiguity (present on both) keeps the spelled side" branch (`JoinChecker.java:86-87`) is
**unreachable in any successfully compiling join**: a name present on both sides makes the `T+V`
union throw before the result is used (verified — the collision error fires).

---

### [SILENT DEFAULTING] `TypedFrom.connectionNameIn` invents `type = "H2"` — even when a type IS spelled
**Evidence** `TypedFrom.java:82-84` and its raw mirror `:130-134`:
```
 82:                String db = ni.properties().get("type") instanceof
 83:                        TypedEnumValue ev ? String.valueOf(ev.value()) : "H2";
 84:                return simple + "(type = \"" + db + "\")";
```
**Repro** `/tmp/a15/FromProbe.java`:
```
connectionNameIn(no type prop)              = RelationalDatabaseConnection(type = "H2")
connectionNameIn(type='DuckDB' as String)   = RelationalDatabaseConnection(type = "H2")
```
The second case is worse than a default: the connection *does* carry a type, spelled as a String
literal rather than an enum, and the answer is a confident wrong one.

---

### [INCONSISTENCY] `TypedFrom` folds the same `testDataSetupSqls` list two different ways
**Evidence** `TypedFrom.foldLiteral` for a typed collection concatenates with **no separator**
(`TypedFrom.java:265-274`); `TypedFrom.foldRawLiteral` for the raw-protocol mirror joins with
**`'\n'`** (`:451-463`) and additionally returns `null` for an empty list where the typed one returns
`""`. The reference-runtime path takes the first, a helper-constructed runtime
(`from(m, testRuntimeH2())`) takes the second. The blobs are then split and **executed**
(`StatementExecutor.java:2287-2296` -> `RawSql.splitStatements` -> `Executor.executeRaw`).

**Repro** `/tmp/a15/FromProbe2.java`, same `testDataSetupSqls = ['CREATE TABLE X(A INT)', 'INSERT INTO X VALUES (1)']`:
```
TYPED path : [CREATE TABLE X(A INT)INSERT INTO X VALUES (1)]      <-- one corrupt statement
RAW   path : [CREATE TABLE X(A INT)
INSERT INTO X VALUES (1)]                                         <-- two statements
```

---

### [ARCHITECTURE — answer to Q6] `TypedFrom.java` is 425/492 lines of extraction logic living inside a "typed HIR record"
Read in full. The record itself is 8 fields plus `children()`/`withChildren()` and is a pure type
passthrough (`FromChecker.java:27` `t.checkGeneric(af, env)`; verified — `from(rt)` and
`from(mapping, rt)` leave the type unchanged). Everything else is **static metadata mining**:

| lines | what it does |
|---|---|
| 68-158 | `connectionNameIn` + `rawConnectionNameIn` — plan-text connection spelling |
| 164-246 | `jsonSourcesIn` + `collectJson` + `collectJsonRaw` — JSON model connections |
| 249-277 | `foldLiteral` — `'+'`-folded string literals over the TYPED tree |
| 284-310 | `chainMappingsIn` — `ModelChainConnection` mapping FQNs |
| 319-426 | `sqlSetupsIn` + `collectSqlSetups` + `collectSqlSetupsRaw` — LocalH2 setup SQL, with let-binding resolution and a depth-3 nested-helper expansion |
| 430-467 | `foldRawLiteral` — the same folding over the RAW protocol tree |

Assessment: it **computes no types**, so it is not a type-rule duplication — but it is a second,
*unchecked* interpreter of `com.legend.protocol.spec.ValueSpecification` (a mini evaluator with
variable binding, string folding and call inlining) parked in a supposedly inert record, and it is
consumed by two different callers (`FromChecker.java:59-73` and the executor's `buildFrame`). It
diverges from itself (previous two findings). It also carries **four defaulting convenience
constructors** (`TypedFrom.java:31-61`), directly contradicting the discipline the sibling records
state in their own comments — `TypedJoin.java:31-43` ("NO short overload… every construction names
every field") and `TypedGetAll.java:31-33` ("NO convenience constructor"). `TypedJoinSlot.java:33-36`
and `TypedNavigate.java:55-65` violate it too. The stated rule is not enforced repo-wide.

---

### [DOC-LIE] Three javadocs claim `asOfJoin`'s prefix renames EVERY right column; the code prefixes only the colliding ones
**Evidence** `AsOfJoinChecker.java:19-26` ("EVERY right-side column is renamed … the SAME rule as
`join`. **Deliberate divergence from engine-lite**, which prefixes only the overlapping columns here
… an engine inconsistency we do not carry"), `TypedAsOfJoin.java:20-22` (same claim), and
`Checkers.java:44-46` ("both joins prefix ALL right columns"). The code at
`AsOfJoinChecker.java:77-83` does exactly the opposite:
```
 82:        Type.RelationType schema = Checkers.prefixedUnion(left, right, prefix,
 83:                c -> leftNames.contains(c.name()));
```
**Repro** (`/tmp/a15/aq_prefix.pure` vs `/tmp/a15/qp_join5.pure`, both sides carry `id`; asOfJoin's
right also has a non-colliding `price`, join's has a non-colliding `price`):
```
asOfJoin 5-arg: Relation<(id, t, extra, q_id, q_t, price)>      <-- price NOT prefixed
join     5-arg: Relation<(id, extra, q_id, q_price)>            <-- price IS prefixed
```
The doc asserts the engine inconsistency was removed; it was preserved.

### [DOC-LIE] `JoinChecker.java:45-47` — "a user-written bare 'join' must never reach it"
True for the bare name, false for the FQN spelling. See the CRASH/ICE finding above.

---

### [SILENT FALLBACK / INCONSISTENCY] `allVersions()` on a non-temporal class silently degrades; `all(%date)` on the same class walls
**Evidence** `GetAllChecker.checkVersions` (`:35-42`) sets `versionSweep=true` with no temporal check,
and the flag is then ignored downstream for a class with no stereotype.
**Repro** (`/tmp/a15/nav2.pure`, `navm::Person` has no temporal stereotype):
```
navm::Person.allVersions()->project(~[n:p|$p.firstName])
[G] type=Relation<(n:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS n
[EXEC-ROW] String(John) | String(Jane) | String(Bob) | String(Zoe)     (4 rows — the plain extent)

navm::Person.all(%2024-01-01)->project(~[n:p|$p.firstName])
[G] type=Relation<(n:String[1])> mult=[1]                              (also type-checks)
[PLAN-ERROR] MappingResolutionException: milestoned fetch of 'navm::Person': the class declares no temporal stereotype
```
One milestoning spelling errors, the sibling silently returns a non-versioned answer.
(`allVersionsInRange(%a,%b)` is a parser gap: `ParseException: .allVersionsInRange(...) is not supported`.)

---

### [INFORMATION LOSS] `navigate` PRE_MAP: the declared root type is a NESTED sub-row, the executed result is FLAT columns
**Evidence/Repro** same run as Repro E above. Declared:
```
returnType=Relation<(ID:Integer[1], FIRST_NAME:String[1], AGE_VAL:Integer[1], PRIMARY_ADDR_ID:Integer[0..1],
                     pa:(ID:Integer[1], PERSON_ID:Integer[1], COUNTRY_ID:Integer[0..1], STREET:String[1], CITY:String[1])[1])>
```
Executed columns:
```
[EXEC-COL] ID | FIRST_NAME | AGE_VAL | PRIMARY_ADDR_ID | pa_ID | pa_PERSON_ID | pa_COUNTRY_ID | pa_STREET | pa_CITY
```
5 declared columns (one nested) vs 9 flat result columns, and no column named `pa` exists in the
result. A consumer decoding by the declared type cannot find the sub-row.

---

### [SILENT FALLBACK / DEAD TYPE LOGIC] The per-hop `(INNER)` / `(LEFT)` / `(OUTER)` join-type annotation on a mapping property join chain is parsed, stored, and then completely ignored
**Evidence** `model/JoinChainElement.java:25` carries `@Nullable JoinType joinType` and
`model/MappingFromProtocol.java:530` populates it from the source text. An **exhaustive** grep of
`core/src/main/java` for `joinType` (32 hits across 13 files) shows it is only ever
*carried* (`compiler/NameResolver.java:1335,1359`; `normalizer/StoreSubstitutionRewrite.java:76,91`),
*re-emitted to protocol* (`protocol/ProtocolEmitter.java:357-359`) or *reflected*
(`exec/MetamodelWalk.java:476,1193`). The only site that ever **reads** a hop join type to change
behaviour is the FILTER variant `FilterMapping.JoinMediated.joinType`
(`normalizer/MappingNormalizer.java:2059, 2846`). `normalizer/JoinChainEmission.java`,
`normalizer/ModelJoinNesting.java`, the whole `resolver/` package and the lowerer never consult it —
every property-mapping hop is LEFT-joined unconditionally.

**Repro** `/tmp/a15/nav3.pure` maps the SAME two-hop chain twice, once with `(INNER)` and once plain:
```
countryViaInner: [NavDb] @Person_Address > (INNER) @Address_Country | T_COUNTRY.CNAME,
countryViaPlain: [NavDb] @Person_Address >         @Address_Country | T_COUNTRY.CNAME
```
```
$ probe.sh nav3.pure hop1.pure navt::NavRuntime nav.ddl.sql
   query: navm::Person.all()->project(~[n:p|$p.firstName, ci:p|$p.countryViaInner, cp:p|$p.countryViaPlain])
[PLAN] SELECT t0.FIRST_NAME AS n, t2.CNAME AS ci, t2.CNAME AS cp
       FROM T_PERSON AS t0
       LEFT OUTER JOIN T_ADDRESS AS t1 ON t0.ID = t1.PERSON_ID
       LEFT OUTER JOIN T_COUNTRY AS t2 ON t1.COUNTRY_ID = t2.ID
[EXEC-ROW] String(John) | String(USA) | String(USA) |
[EXEC-ROW] String(John) | String(USA) | String(USA) |
[EXEC-ROW] String(Jane) | null | null |
[EXEC-ROW] String(Bob)  | null | null |
[EXEC-ROW] String(Zoe)  | null | null |
```
The two chains render to the *identical* expression on the *identical* alias (`t2.CNAME` for both) —
the `(INNER)` hop is not merely un-emitted, the chains are deduplicated as if the annotation did not
exist. With an INNER hop, Jane/Bob/Zoe would have been dropped; instead the author's declared join
semantics is silently discarded and the rows survive with NULLs. `JoinType.OUTER` is accepted by the
parser (`model/JoinType.java:35`) and has no `JoinKind` counterpart at all, so it could not be honoured
even if the annotation were read.

---

### [MEDIUM — answer to Q7] Cross-store joins are not a type-level concept; the wall is connection identity, not store identity
**Evidence/Repro** `/tmp/a15/xs.pure` declares two Databases `xs::DbA`, `xs::DbB` in one runtime.
```
#>{xs::DbA.T_A}#->join(#>{xs::DbB.T_B}#, JoinKind.LEFT, {a,b|$a.ID==$b.ID}, 'r')
[G] type=Relation<(ID:Integer[1], AV:String[1], r_ID:Integer[1], r_BV:String[1])> mult=[1]
[PLAN] SELECT t0.*, t1.ID AS r_ID, t1.BV AS r_BV FROM T_A AS t0 JOIN T_B AS t1 ON t0.AV = t1.BV
```
The type system says nothing about stores; the plan renders a single, **unqualified** SQL join as if
both tables were in one schema. With the two stores on **different** connections execution is walled:
```
[EXEC-ERROR] com.legend.error.NotImplementedException: query touches stores bound to DIFFERENT connections
             {xs::DbA=xs::ConnA, xs::DbB=xs::ConnB} under runtime 'xs::RtAB' - multi-connection execution is not modeled
```
With both mapped to the **same** connection (`/tmp/a15/xs2.pure`) it just runs:
```
[EXEC-ROW] Integer(1) | String(a1) | Integer(1) | String(b1) |
[EXEC-ROW] Integer(2) | String(a2) | null | null |
```
So the store boundary is enforced only as a side effect of connection routing; two logically distinct
Databases that happen to share a connection are joined silently — and the result carries the `[1]`
NULL unsoundness on top. (Also `NotImplementedException` here is a raw `RuntimeException`, not a
compile error.)

---

### [LOW] `from()` does not distinguish a mapping reference from a runtime reference
**Evidence** `FromChecker.java:83-93` slots refs positionally with no kind check; any
`TypedPackageableRef` (a **Class** included) is accepted at type time.
**Repro**
```
->from(navt::NavRuntime, navm::NavMapping)  [G] ok  -> [PLAN-ERROR] unknown mapping 'navt::NavRuntime'
->from(navm::NavMapping)                    [G] ok  -> [PLAN-ERROR] unknown runtime 'navm::NavMapping'
->from(navm::Person, navm::Address)         [G] ok  -> [PLAN-ERROR] unknown mapping 'navm::Person'
```
Errors are clean, so this is a diagnosis-quality issue, not unsoundness.

### [LOW — answer to Q4] `asOfJoin` performs no temporal check on its as-of columns
`AsOfJoinChecker` types the match lambda as a plain `{T[1],V[1]->Boolean[1]}`; nothing requires a
temporal type or that the two sides agree.
```
String as-of  ({t,q| $t.k > $q.k2}, k/k2:String)   -> compiles, 1 row (lexicographic 'bbb' > 'aaa')
Integer as-of (k/k2:Integer)                        -> compiles, closest-match semantics on integers
DateTime vs StrictDate                              -> compiles, matches
Date vs DateTime                                    -> compiles, no match (Date promotes to midnight)
```
Result multiplicity claim vs reality: `asOfJoin` yields exactly **one row per left row** (verified 2x2
-> 2 rows; a tie on the right picks one row — 1 row out for 2 tied right rows), i.e. `[*]` in, `[*]`
out, never multiplying. That much matches. A `Date` column decoded back as a `StrictDate` Java value
is sound (StrictDate <: Date).

---

## VERIFIED SOUND

* **Schema union / collision, exhaustive over all 4 `JoinKind` values** — `INNER`, `LEFT`, `RIGHT`,
  `FULL` all raise the same clean `SchemaInvariantException` on a shared column name. **No**
  duplicate-`RelationType` `IllegalArgumentException` ICE, no silent drop, no silent rename.
* **Prefix-manufactured collision** (`left` has `r_id`, prefix `'r'`) -> clean
  `TypeInferenceException: join prefix 'r_' produces duplicate column 'r_id' - choose a prefix that
  does not collide` (`Checkers.java:63-67`), i.e. the raw invariant blow-up is deliberately intercepted.
* **`navigate` PRE_MAP alias collision** (`~ID:` over a source that already has `ID`) -> clean
  `SchemaInvariantException`.
* **Malformed-input surface, all clean `TypeInferenceException`s (no ICE):** unknown JoinKind value
  (`CROSS`), 1-parameter condition lambda, non-relation right side, unknown column in the condition,
  non-literal prefix argument, string where the asOfJoin condition lambda belongs, `from(42)`,
  4-argument `from`, `join` with a non-Boolean condition.
* **Store nullability is faithfully typed** on table references: `NOT NULL` -> `[1]`, nullable ->
  `[0..1]` (`#>{navs::NavDb.T_PERSON}#` -> `PRIMARY_ADDR_ID:Integer[0..1]`).
* **`join` 5-arg prefix really does prefix ALL right columns** (as documented for `join`), separator
  auto-inserted (`'r'` -> `r_`), and a caller-supplied trailing `_` is not doubled
  (`JoinChecker.java:412-414`).
* **Legacy TDS desugars** — `join(tds, JoinType.K, ['id'])` shared-key dedup and
  `join(tds, JoinType.K, ['a'], ['b'])` explicit pair both work; INNER / LEFT_OUTER / RIGHT_OUTER key
  survivorship is correct (only FULL_OUTER is wrong, above); duplicate key entries dedup
  (`JoinChecker.java:237`); the `__jk_` synthetic prefix is ordinal-bumped against both sides.
* **`userCondition` null-safe equality** — a user join lambda lowers `==` to
  `IS NOT DISTINCT FROM` (`Lowerer.java:1907-1918`), verified in the plan text, and the construct is
  portable: `L JOIN R ON (L.k IS NOT DISTINCT FROM R.k)` over `{1,NULL}x{1,NULL}` returns **2 rows on
  DuckDB, SQLite and H2 alike** (`/tmp/a15/IND.java`). Resolver-synthesized navigation joins correctly
  take the verbatim-`=` arm.
* **`asOfJoin` lowering** — DuckDB `ASOF LEFT JOIN`, one row per left row, correct closest-match
  selection (10:30 -> 10:15/100, 11:30 -> 11:15/105), 4-arg key condition ANDed correctly.
* **3-deep navigation** (`$p.addresses.country.cname`) lowers to correctly chained LEFT joins and the
  values are right (`John|USA` x2, others null).
* **`ChainedExists.explodedTwoHop`** (read in full) — its private prefix loop
  (`ChainedExists.java:149-159`) does guard the composite row it builds at line 165, so no ICE there.
* **`from()`** is a genuine type passthrough; the mapping/runtime slotting logic
  (`FromChecker.java:80-93`) behaves as documented for the 1-, 2- and 3-argument reference shapes.
* **`GetAllChecker`** — `T` binds from the class reference; `Class.all()`, `Class.all(%d)` and
  `Class.allVersions()` all produce `T[*]`; a missing mapping is deliberately deferred to the
  back-end (documented compile-vs-link split) and surfaces as a clean `MappingResolutionException`.

## NOT COVERED

* **H2 and SQLite end-to-end for store-backed models.** `specification: H2 { }` is refused by the
  parser (`ParseException: unsupported datasource specification: H2 (corpus-censused shapes only)`),
  and SQLite cannot execute the aliased-`VALUES` form TDS literals emit (a documented gap in
  `sql/dialect/Lexicon.java:32-37`). Dialect divergence was therefore proven on raw SQL against all
  three JDBC drivers plus one store-backed DuckDB/SQLite pair.
* **`allVersionsInRange` / `getAllForEachDate` / `TypedGetAll.forEachDate`** — parser refuses the
  first; the other two were read but not exercised.
* **XStore paths in `TypedFrom`** (`jsonSourcesIn`, `chainMappingsIn`, `ModelChainConnection`) —
  read in full and probed at the API level, but no end-to-end M2M/XStore query was run.
* **~1700 of `AssociationJoins.java`'s 2086 lines** — union hops (`chainedUnionHop`,
  `memberPairedCondition`), correlated-predicate lifting (`andCorrelatedIntoCondition`,
  `corrPredOnJoinedRow`), embedded pass-through and the `#69` aggregated-navigation machinery were
  read structurally for type/multiplicity decisions (all of which copy `c.multiplicity()` verbatim,
  the pattern reported above) but were not individually exercised with queries.
* **`TypedJoinSlot` end-to-end** — only reachable via the FQN escape hatch, which fails in Phase H
  before any execution; the normalizer-emitted path was not driven directly.
