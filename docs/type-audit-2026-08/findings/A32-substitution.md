# A32-substitution — Phase H (`com.legend.resolver`) type-preservation audit

Scope: `Substitution.java` (3167, read in full), `TemporalFrame.java` (2778),
`CorrelatedSubselects.java` (2390), `Pipelines.java` (1531), `SyntheticHeads.java` (1263),
`NavMaterializer.java` (796), `InnerDemand.java` (721), `SubQueryLift`, `DriverPkAppend`,
`ChainedExists`, `Anchors`, `DateSplit`, `ViewFrames`, `AsorRef`, `ScalarValueReads`,
`LiteralFolds`, `CastNav`, `GenericTypeReflection`, `TemporalContext`, `RelationalRootForm`,
`RawGridSchema`.

**Central answer: NO.** `Substitution`'s own class javadoc
(`Substitution.java:51-56`) states:

```
 * <p>Discipline (plan risk #1): a replacement always carries the SAME
 * {@link ExprType} as the node it replaces &mdash; binding conformance is
 * G's guarantee (the body compiled through NewChecker's strict subsumption)
 * &mdash; so every enclosing node's info stays valid and no restamping pass
 * exists.
```

That is false. Of the 41 substitution rules enumerated below, **18 recompute the type
from a different source (b)** and **7 invent one outright (c)**. Measured with a
tree-differ over 40+ queries: type divergence occurs on the majority of navigating
queries, and in six cases the divergence reaches the *observable* result schema
(§FINDINGS 1-6).

Tooling used (all in `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a32/`):
- `TypeDiff.java` — captures BEFORE = `SpecCompiler.typeQueryBody` + `UserCallInliner.inlineBody`,
  AFTER = `new StoreResolver(ctx, specs).resolve(body, runtimeFqn)`, then walks both trees in
  structural correspondence and prints **every** node whose `ExprType` changed (`CHANGE`) and
  every node replaced by a different node kind with its before/after types (`REPLACE`).
- `MultiDb.java` — runs the same query against DuckDB **and** H2, printing the declared
  column pure-type/multiplicity next to the actual runtime Java class of every cell.
- `Cls.java`, `RootForm.java`, `DrvPk.java` — targeted probes.

---

## 1. THE SUBSTITUTION RULE TABLE

`(a)` = copies the replaced node's `ExprType`; `(b)` = recomputes it from another source
(row column, mapping binding, inlined body); `(c)` = invents one.

| # | Line | Input node shape | Output node shape | Type source | Class |
|---|------|------------------|-------------------|-------------|-------|
| 1 | 421-427 | `TypedLambda` (body-only) | `TypedLambda` | `lambda.info()` | **a** |
| 2 | 429-444 | `TypedLambda(p\|…)` | `TypedLambda(_rN\|…)` | new `FunctionType(rowType→oldFn.result())` | **b** (param retyped class→relation row; intended) |
| 3 | 451-460 | whole-instance map lambda | `TypedLambda(_rN\|$_rN)` | same as #2 | **b** |
| 4 | 481-562 | `tdsContains(x, fns, tds)` | `isNotEmpty(filter(rel,…))` | `n.info()` | **a** (inner `equal` = `BOOLEAN[1]` **c**) |
| 5 | 569-630 | `tdsContains/5` cross form | `isNotEmpty(filter(rel,…))` | `n.info()` | **a** |
| 6 | 661-664 | `getString($b, 'col')` | `TypedPropertyAccess(col)` | `new ExprType(c.type(), c.multiplicity())` of the TDS column | **b** |
| 7 | 815-826 | `exists($p.<embedded>, pred)` | rewritten **predicate body** | the body's own type | **b** |
| 8 | 827-832, 879-885, 913-938 | `isEmpty/isNotEmpty/exists($p.assoc…)` | `call.withChildren([rel,pred'])` (l.3026) | `call.info()` | **a** |
| 9 | 843-874 | emptiness over `concatenate(nav,nav)` | `or/and`-fold of per-branch calls | `call.info()` (l.2864) | **a** |
| 10 | 954-991 | `contains/in` with a resolvable relation | `isNotEmpty(filter(q.relation,…))` | `n.info()` | **a** |
| 11 | 992-999→2303-2350 | `contains($p.h.leaf, v)` | `isNotEmpty(filter(filter(pipe,corr),eq))` | `new ExprType(BOOLEAN, ONE)` | **c** |
| 12 | 1023-1065 | filter-position `equal` over a filtered nav leaf | `isNotEmpty(filter(rel,pred))` | `cmp.info()`; **inner leaf read forced `ExprType(leaf.type(), ONE)`** (l.1043-1047) | **a** / **c** |
| 13 | 1073-1142 | `not(<cmp over to-many crossing>)` | `TypedIf(isNotEmpty(read), not', <bool const>)` | `new ExprType(BOOLEAN, ONE)` | **c** |
| 14 | 1101-1111 | `not(<ordering cmp>)` | `lc.withChildren(...)` | `lc.info()` | **a** |
| 15 | 1154-1160, 1163-1174, 1204-1215, 1235-1245 | `$p.x.milestoning.f` / multi-hop leaf | `TypedPropertyAccess(col)` via `milestoneColumnRead` (2181-2215) | `new ExprType(c.type(), c.multiplicity())` **of the row column** | **b** |
| 16 | 1246-1261 | expression-valued sub-nav leaf | `Pipelines.prefixColumns(binding,…)` | the **binding's** own node types | **b** |
| 17 | 1266-1269 | `$p.a.b.…leaf` (chained assoc) | `assocLeaf` → prefixed column read | the **binding's** type | **b** |
| 18 | 1273-1295 | nested embedded ctor drill | `renameRowVar(ctorValue)` | the ctor value's type | **b** |
| 19 | 1302-1327 | head-join + embedded tail | `assocBindingRead` | binding's type | **b** |
| 20 | 1332-1341 | subtype-embedded tail (`stc_…`) | `assocBindingRead` | binding's type | **b** |
| 21 | 1386-1394 | `$p.businessDate/processingDate` (root ctx) | the injected date node | the **date literal/param's** type | **c** (`Date[1]` → `StrictDate[1]`/`DateTime[1]`) |
| 22 | 1398-1418 | same, for-each-date / version sweep | `milestoneColumnRead(col)` | row column's type | **b** |
| 23 | 1437 | `$p.prop` (1-hop) | `renameRowVar(binding)` | the **binding's** type | **b** |
| 24 | 1538-1543 | `objectReferenceIn($p, [])` | `TypedCBoolean(false)` | `ExprType(BOOLEAN, ONE)` | **c** |
| 25 | 1550-1562 | `objectReferenceIn` uniform key | `in(pkCol, [lits])` | `oc.info()` | **a** |
| 26 | 1564-1586 | `objectReferenceIn` multi-col | `or(and(eq…))` | `ExprType(BOOLEAN, ONE)` | **c** |
| 27 | 1608-1632 | pk column read | `TypedPropertyAccess` | `ExprType(col.type(), col.multiplicity())` | **b** |
| 28 | 1664-1669 | registered aggregate call | `TypedPropertyAccess(agg col)` | **`n.info()`** — the aggregate's type on a LEFT-joined column | **a** (see FINDING 4) |
| 29 | 1670-1681 | count/size aggregate | `TypedIf(isNotEmpty, read, 0)` | `n.info()` | **a** |
| 30 | 1745-1751→1912-1919 | `$p->filter(pred).leaf` | `TypedIf(pred', leaf', ∅)` | `pa.info()` | **a** |
| 31 | 1752-1753→2595-2808 | `$p.nav->filter(f)[->toOne()].leaf` | **`TypedProject`** (relation!) | `ExprType(Type.relation(outRow), pa.info().multiplicity())` | **c** (see FINDING 1) |
| 32 | 1754-1755→2817-2832 | `$p->subType(@S).prop` | `assocLeaf(subType$S, prop)` | binding's type | **b** |
| 33 | 1764-1768, 1924-1934 | `^X(k=v).k` | `rewrite(ctorValue)` | ctor value's type | **b** |
| 34 | 1778-1806 | `TypedNewInstance/NativeCall/Collection/Cast/If/MilestonedAccess` | `mapChildren` | node's own `info()` | **a** |
| 35 | 1807-1816 | nested `TypedLambda` | `TypedLambda(params, rewritten, l.info())` | `l.info()` | **a** |
| 36 | 1821-1837 | `coll->map(l\|body)` (object space) | `rewrite(inlineParam(body, l, coll))` | **the inlined body's** type | **b** (`[*]` → `[1]`, FINDING 2) |
| 37 | 1839-1873 | literals / `TableReference` / resolved relation material | identity | `n.info()` | **a** |
| 38 | 1879-1880 | `graphFetch(src, tree)` | `rewrite(src)` | the source's type | **b** |
| 39 | 1940-1958 | `$p.milestoning.x` on an unmilestoned table | `TypedCollection([])` | `ExprType(original.type(), ZERO_ONE)` | **c** (mult forced) |
| 40 | 2298-2301 | 1-element `TypedCollection` needle | its single element | element's type | **b** |
| 41 | 3034-3056 | `$p.head->project(cols)` | `TypedProject(corrRel, cols')` | `tp.info()` | **a** |

Support routines: `Pipelines.prefixColumns` (1306-1400) and `Pipelines.rewriteRowReads`
(1183-1300) both preserve each node's `info()` verbatim while re-pointing the row var —
so rules 16-20/23/32 inherit **the mapping binding's stamp**, not the query node's.

Every **(b)/(c)** row above is a candidate divergence; §2 shows which ones actually fire.

---

## FINDINGS

### [UNSOUND] 1. `filteredNavLeafRead` stamps `[1]` on a correlated subselect that can return 0 or >1 rows

**Evidence** — `Substitution.java:2790-2800`:
```java
        Type.RelationType outRow = new Type.RelationType(List.of(
                new Type.RelationType.Column(pa.property(), leafType,
                        pa.info().multiplicity())));
        // stamped with the READ's multiplicity ([0..1]) — the relation
        // REPRESENTS an optional scalar value, ...
        TypedSpec projected = new TypedProject(rel,
                List.of(new TypedFuncCol(pa.property(), leafFn)),
                new ExprType(Type.relation(outRow), pa.info().multiplicity()));
```
The comment says `[0..1]`; the code copies `pa.info().multiplicity()`, which is `[1]`
whenever the user wrote `->toOne()` (the guard at 2629-2636 only requires `upper()==1`).
`leafFn`'s own result is hard-stamped `Multiplicity.Bounded.ONE` (l.2788).

**Repro** (model `mA.pure`, T_ADDR has 1 NYC row for John, none for Jane/Bob):
```
ma::Person.all()->project(~[a:p|$p.addresses->filter(x|$x.city == 'NYC')->toOne().street])
```
**Actual output** (`probe.sh`, DuckDB):
```
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT (SELECT t1.STREET AS street FROM T_ADDR AS t1 WHERE t0.ID = t1.PERSON_ID AND t1.CITY = 'NYC') AS a
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(123 Main) |
[EXEC-ROW] null |
[EXEC-ROW] null |
```
A `String[1]` column produced `null` twice. TypeDiff confirms the node swap:
```
REPLACE $0.1.0: TypedPropertyAccess(street) :: String[1]  ==>  TypedProject :: Relation<(street:String[1])>[1]  *** TYPE String[1] -> Relation<(street:String[1])>[1] ***
```

With **two** NYC rows for John (`ddlA2.sql`) the same query gives a raw driver error,
not pure's cast message:
```
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: More than one row returned by a
subquery used as an expression - scalar subqueries can only return a single row.
```

**Why it matters** `[1]` is the strongest claim the type system makes. Consumers (egress
null-drop, `CompactList`, JSON serialization, downstream `+`) rely on it. Both failure
directions (0 rows → NULL; >1 rows → driver error) are reachable from an idiomatic
derived-property spelling.

---

### [UNSOUND] 2. H silently DELETES the user's explicit `->toOne()`, so the lowerer's size guard can never fire

**Evidence** — `Substitution.java:726-729` (`pathOf`, "THE PATH VIEW"):
```java
        if (n instanceof TypedNativeCall c && c.args().size() == 1
                && com.legend.builtin.Pure.isToOneCall(c.callee().qualifiedName())) {
            return pathOf(c.args().get(0), userVar);
        }
```
`$p.firm->toOne().legalName` therefore yields path `[firm, legalName]`, and the **whole**
node — `toOne()` included — is replaced by `assocLeaf(...)`, a bare prefixed column read.
`Lowerer.requiredOneEgress` (`Lowerer.java:422-429`) and `Scalars`' `toOne` rule
(`Scalars.java:441-483`), which would emit `SqlExpr.CheckedOne`, never see the call.

**Repro** (`mB.pure` / `ddlB.sql`: `firm: mb::Firm[0..1]` over a NON-unique join —
`T_F` holds two rows with `ID=10`; `Cid` has `FIRM_KEY=99`, matching nothing):
```
mb::Person.all()->project(~[n:p|$p.name, f:p|$p.firm->toOne().legalName])
```
**Actual output** (both backends):
```
  SQL   : SELECT t0.NAME AS n, t1.LEGAL_NAME AS f FROM T_P AS t0 LEFT OUTER JOIN T_F AS t1 ON t0.FIRM_KEY = t1.ID
  rootT : Relation<(n:String[1], f:String[1])>[1]
  --- jdbc:duckdb:
   COL n : String mult=[1]
   COL f : String mult=[1]
   ROW String(Ann) | String(AcmeTwo) |
   ROW String(Bob) | String(Beta) |
   ROW String(Ann) | String(AcmeOne) |
   ROW String(Cid) | NULL |
  --- jdbc:h2:mem:...
   ROW String(Ann) | String(AcmeOne) |
   ROW String(Ann) | String(AcmeTwo) |
   ROW String(Bob) | String(Beta) |
   ROW String(Cid) | NULL |
```
Both violations at once under `f : String[1]`: `Cid` → NULL (0 rows), `Ann` → **two rows**
(`toOne` over a 2-element set silently row-multiplies instead of raising). Identical on
DuckDB and H2.

Same for a `[*]` end (`mA.pure`): `ma::Person.all()->project(~[a:p|$p.addresses->toOne().street])`
declares `a : String[1]`, returns `123 Main / 456 Oak / 789 Pine / NULL` — John appears twice,
Bob is NULL.

**Why it matters** `toOne()` is the *only* way a user asserts cardinality in Pure. H erases
the assertion while keeping the `[1]` it produced.

---

### [UNSOUND] 3. `SubQueryLift` hard-codes `[0..1]` on an uncorrelated class subquery of any cardinality

**Evidence** — `SubQueryLift.java` (`resolveScalarRead`, tail):
```java
        // the [0..1] stamp IS the scalar-subquery contract
        if (resolved instanceof TypedProject rp) {
            return new TypedProject(rp.source(), rp.columns(),
                    new ExprType(rp.info().type(), optional));   // optional = ZERO_ONE
        }
```
`peelScalarWraps` (l.72-91) has already stripped the user's `toOne()`/`first()`, and
nothing constrains the resolved chain to one row.

**Repro** (`mA.pure`, `T_FIRM` has 2 rows):
```
ma::Person.all()->project(~[a:p|$p.lastName, b:p|ma::Firm.all()->toOne().legalName])->from(ma::M, ma::RT)
```
**Actual output:**
```
  SQL   : SELECT t0.LAST_NAME AS a, (SELECT t1.LEGAL_NAME AS legalName FROM T_FIRM AS t1) AS b FROM T_PERSON AS t0
  rootT : Relation<(a:String[1], b:String[1])>[1]
  --- jdbc:duckdb:
   [EXEC-ERROR] java.sql.SQLException: Invalid Input Error: More than one row returned by a subquery used as an expression - scalar subqueries can only return a single row. ...
  --- jdbc:h2:mem:...
   [EXEC-ERROR] org.h2.jdbc.JdbcSQLDataException: Scalar subquery contains more than one row; SQL statement: ... [90053-214]
```
And the 0-row direction, same shape:
```
ma::Person.all()->project(~[a:p|$p.lastName, b:p|ma::Firm.all()->filter(f|$f.legalName == 'ZZZ')->toOne().legalName])->from(ma::M, ma::RT)
   COL b : String mult=[1]
   ROW String(Smith) | NULL |
   ROW NULL         | NULL |
   ROW String(Jones) | NULL |
```
Note also the internal inconsistency: the lift stamps the relation `[0..1]` while the
enclosing project column keeps G's `[1]`.

---

### [UNSOUND] 4. Non-count aggregates over an EMPTY to-many navigation return NULL under a `[1]` claim

**Evidence** — the aggregate replacement copies the call's own `ExprType` onto a
LEFT-joined aggregate column (`Substitution.java:1664-1682`):
```java
        AggRead aggRead = target.aggReads().get(n);
        if (aggRead != null) {
            TypedSpec read = new TypedPropertyAccess(
                    new TypedVariable(target.freshRowVar(), ...),
                    aggRead.column(), n.info());
            if (!aggRead.zeroWhenEmpty()) {
                return read;                       // <-- no compensation
            }
            return new TypedIf(new TypedNativeCall(neCallee(), List.of(read), …),
                    read, Optional.of(TypedCInteger(0L, …)), n.info());
```
`zeroWhenEmpty` comes from `CorrelatedSubselects.isCountFamily`
(`CorrelatedSubselects.java:1637-1641`), which matches **only** `count` and `size`:
```java
static boolean isCountFamily(TypedNativeCall nc) {
        String q = nc.callee().qualifiedName();
        return q.equals("meta::pure::functions::collection::count")
                || q.equals("meta::pure::functions::collection::size");
```

**Repro** (`mA.pure`; firm `Beta` has no employees):
```
ma::Firm.all()->project(~[n:f|$f.legalName, s:f|$f.employees.age->sum()])
ma::Firm.all()->project(~[n:f|$f.legalName, s:f|$f.employees.lastName->joinStrings(',')])
ma::Firm.all()->project(~[n:f|$f.legalName, s:f|$f.employees.age->average()])
ma::Firm.all()->project(~[n:f|$f.legalName, s:f|$f.employees->size()])       # control
```
**Actual output** (identical on DuckDB and H2):
```
sum        rootT : Relation<(n:String[1], s:Integer[1])>[1]
           COL s : Integer mult=[1]   ROW String(Acme) | BigInteger(30) |   ROW String(Beta) | NULL |
joinStrings rootT: Relation<(n:String[1], s:String[1])>[1]
           COL s : String  mult=[1]   ROW String(Acme) | String(Smith) |    ROW String(Beta) | NULL |
average    rootT : Relation<(n:String[1], s:Float[1])>[1]
           COL s : Float   mult=[1]   ROW String(Acme) | Double(30.0) |     ROW String(Beta) | NULL |
size       COL s : Integer mult=[1]   ROW String(Acme) | Long(2) |          ROW String(Beta) | Long(0) |   <-- compensated
```
Real Pure gives `sum([]) = 0` and `joinStrings([], ',') = ''`; here both are NULL under
`[1]`. The compensation machinery exists (the `size` row proves it) and is simply not
wired to the other reducers.

---

### [UNSOUND] 5. `$p.milestoning.from/thru` — declared `DateTime`, value is `StrictDate` (a sibling type, not a subtype)

**Evidence** — G stamps the milestoning struct members `DATE_TIME` unconditionally
(`compiler/element/Temporal.java:44-53`):
```java
                && java.util.Set.of("from", "thru", "in", "out",
                        "snapshotDate").contains(prop)) {
            // DATE_TIME, not abstract Date: the wire keeps the physical precision
            return new ...ExprType(...Type.Primitive.DATE_TIME, ...Bounded.ZERO_ONE);
```
H's `milestoneColumnRead` (`Substitution.java:2181-2215`) then **recomputes** the type from
the physical row column (`new ExprType(c.type(), c.multiplicity())`) — and the enclosing
project column is never restamped, so G's wrong claim reaches the wire.

The lattice makes these siblings, not sub/supertypes
(`compiler/spec/InferenceKernel.java:565-573`):
```java
        if (declared == Type.Primitive.DATE) {
            return actual == Type.Primitive.STRICT_DATE
                    || actual == Type.Primitive.DATE_TIME;
        }
        return false;
```

**Repro** (`mT.pure` — `BT` declares `business(BUS_FROM=from_z, BUS_THRU=thru_z)` over `DATE` columns):
```
mt::BProd.all(%2020-06-01)->project(~[n:p|$p.name, f:p|$p.milestoning.from, t:p|$p.milestoning.thru])
```
**Actual output** — TypeDiff (H knows the truth internally):
```
  ROOT before = Relation<(n:String[1], f:DateTime[0..1], t:DateTime[0..1])>[1]
  ROOT after  = Relation<(n:String[1], f:DateTime[0..1], t:DateTime[0..1])>[1]
  CHANGE  $0.2.0: TypedPropertyAccess(from) :: DateTime[0..1]  ->  TypedPropertyAccess(from_z) :: StrictDate[0..1]
  CHANGE  $0.3.0: TypedPropertyAccess(thru) :: DateTime[0..1]  ->  TypedPropertyAccess(thru_z) :: StrictDate[0..1]
```
and the runtime class (`Cls.java`):
```
COL n declared=String   mult=[1]
COL f declared=DateTime mult=[0..1]
COL t declared=DateTime mult=[0..1]
ROW java.lang.String ... val=v1 || com.legend.values.PureDateLiteral$StrictDate ... val=2020-01-01
                              || com.legend.values.PureDateLiteral$StrictDate ... val=2021-01-01
```
The same query over a `TIMESTAMP`-columned processing table *does* return `DateWithSecond`,
so the `DATE_TIME` stamp is right only by accident of the physical column type.

---

### [UNSOUND] 6. Union NULL-crossing columns are stamped `[1]` on the row the resolver itself emits as `NULL`

**Evidence / Repro** — model `mI2.pure` (`Vehicle` mapped by `Operation{union(c1,b1)}` to
`Car`/`Bike` subclass sets):
```
mi::Vehicle.all()->project(~[v:x|$x.vin, d:x|$x->subType(@mi::Car).doors])
```
TypeDiff — the materialized row type H builds:
```
REPLACE $0.0: TypedGetAll(mi::Vehicle) :: mi::Vehicle[*] ==> TypedConcatenate ::
  Relation<(vin:String[1], stc_mi__Car___vin:String[1], stc_mi__Car___doors:Integer[1],
            stc_mi__Car___$member:Boolean[1], stc_mi__Bike___vin:String[1],
            stc_mi__Bike___gears:Integer[1], stc_mi__Bike___$member:Boolean[1])>[1]
```
…and the SQL it emits for those very `[1]` columns:
```
  SELECT t0.VIN AS vin, t0.DOORS AS stc_mi__Car___doors, TRUE AS "stc_mi__Car___$member" FROM CT AS t0
  UNION ALL
  SELECT t1.VIN AS vin, NULL AS stc_mi__Car___doors, NULL AS "stc_mi__Car___$member" FROM BT AS t1
```
Result (both backends):
```
   COL d : Integer mult=[1]
   ROW String(C-1) | Integer(4) |
   ROW String(C-2) | Integer(2) |
   ROW String(B-1) | NULL |
```
Every `[1]`-stamped member column holds NULL on the non-owning branch, and the divergence
is carried all the way out (`d : Integer[1]` returns NULL). The same class applies to every
LEFT-joined target row in the resolver: `ChainedExists.java:160-164` and the association
join builders copy `c.multiplicity()` from the right side verbatim into the joined row
type, so e.g. `firm_ID:Integer[1]` survives a `LEFT OUTER JOIN` that produces NULL
(observed in the `mA.pure` TypeDiff output for `$p.firm.legalName`).

---

### [CRASH/ICE] 7. `->map(p|$p.<nav>->toOne().<leaf>)` escapes as `IllegalStateException`

**Cause** — `ScalarValueReads.scalarMapAsProject` decides the per-cell multiplicity by
inspecting the **pre-substitution** mapper body (`StoreResolver.java:923-928` calls it
before `resolveChain`). `cellCanBeEmpty` (`ScalarValueReads.java:73-84`) unwraps toOne-family
wraps only at the **top** of the body; for `PropertyAccess(street, source=toOne($p.addresses))`
the top is a property access, so the body reads `String[1]` and the cell is stamped `[1]`.
Substitution then replaces the body with `trustOne($row.addresses_STREET)` over a nullable
LEFT-joined column, and the egress guard fires.

**Repro** (`mA.pure` / `ddlA.sql`; Bob has no address):
```
ma::Person.all()->map(p|$p.addresses->toOne().street)
```
**Actual output** (both DuckDB and H2):
```
  SQL   : SELECT t1.STREET AS u_map__street FROM T_PERSON AS t0 LEFT OUTER JOIN T_ADDR AS t1 ON t0.ID = t1.PERSON_ID
  rootT : Relation<(u_map__street:String[1])>[*]
  --- jdbc:duckdb:
   [EXEC-ERROR] java.lang.IllegalStateException: NULL cell reached COLLECTION egress ? the
   lowerer owns the null-drop (COMPILER_SHORTCUT_AUDIT ?5); a NULL here is a lowering
   defect, never an empty
  --- jdbc:h2:mem:...
   [EXEC-ERROR] java.lang.IllegalStateException: NULL cell reached COLLECTION egress ...
```
An internal invariant message, not a user-facing compile error, on a two-token query.

---

### [CRASH/ICE] 8. A user property named `pk_0` collides with the synthesized primary-key column

**Evidence** — `RelationalRootForm.java:91` names the synthesized columns positionally:
```java
            cols.add(new TypedFuncCol("pk_" + i++,
```
and then appends the leaves (`g.leaves()`) under their property names, with no collision
check.

**Repro** (`mPk.pure`: `Class mp::Thing { pk_0: String[1]; k_businessDate: String[1]; name: String[1]; }`,
table `T (ID INTEGER PRIMARY KEY, …)`), via `Compiler.lowerResolved(vs, ctx, rt, /*relationalRootForm*/ true)`:
```
QUERY: mp::Thing.all()
  ERR IllegalArgumentException: duplicate column 'pk_0' in relation type
```
The same shape applies to `k_<generatedDate>` (l.109-112) and `o_<sortAlias>` (l.125-127).

---

### [CRASH/ICE] 9. `DriverPkAppend` collides with a user projection column named like the driver PK

**Evidence** — `DriverPkAppend.java:135-155` appends `new TypedFuncCol(cd.name(), …)`; its
dedup test compares the **physical column read**, not the output column NAME:
```java
            if (p.columns().stream().anyMatch(fc -> { ...
                        && physicalName(pa.property(), joinPrefixes)
                                .equalsIgnoreCase(cd.name()); })) {
                continue;
            }
```

**Repro** (`DrvPk.java`, `mPk.pure`, PK column `ID`):
```
QUERY: mp::Thing.all()->project(~[ID:t|$t.name])
  before append: ...RelationType[columns=[Column[name=ID, type=STRING, multiplicity=Bounded[lower=1, upper=1]]]]
  ERR java.lang.IllegalArgumentException: duplicate column 'ID' in relation type
```
Control (no collision) works and shows the append:
```
QUERY: mp::Thing.all()->project(~[pk_0:t|$t.name])
  after  append: ...columns=[Column[name=pk_0, ...], Column[name=ID, type=INTEGER, ...]]
  SQL: SELECT t0.NAME AS pk_0, t0.ID AS ID FROM T AS t0
```
Reached from `StatementExecutor.java:381` whenever `env.addDriverTablePk()` is on.

---

### [CRASH/ICE] 10. `toOne()` over a >1-row nav surfaces as a raw JDBC exception, not pure's cast error

Covered by the outputs in FINDINGS 1 and 3. On DuckDB
`java.sql.SQLException: Invalid Input Error: More than one row returned by a subquery…`;
on H2 `org.h2.jdbc.JdbcSQLDataException: Scalar subquery contains more than one row … [90053-214]`.
Pure's message (`Cannot cast a collection of size N to multiplicity [1]`) is produced by
`Scalars`' `CheckedOne` path, which is unreachable here because H removed the call (FINDING 2).

---

### [SILENT FALLBACK] 11. A temporal class over a table with NO milestoning block is silently unfiltered

**Evidence** — `TemporalFrame.java:1386-1416`:
```java
            var b = ms == null ? null : ms.business();
            if (b == null) {
                // CAPABILITY TOLERANCE (engine relationalElementCanSupport-
                // Strategy + testLatestIgnoredForNonMilestonedMapped
                // goldens): a table that cannot support the strategy is
                // silently UNFILTERED, never an error
                return pipe;
            }
```
plus `... .orElse(null)` on `ctx.findTableMilestoning(...)` at lines 800, 1193, 1369, 1798,
1890, 2448, 2517 — a table whose milestoning cannot be resolved reaches the same arm.

**Repro** (`mCT.pure`: `<<temporal.businesstemporal>> ct::P` over a plain `PT` table):
```
ct::P.all(%1900-01-01)->project(~[n:p|$p.name, d:p|$p.businessDate])
```
**Actual output:**
```
  SQL   : SELECT t0.NAME AS n, DATE '1900-01-01' AS d FROM PT AS t0
  rootT : Relation<(n:String[1], d:Date[1])>[1]
   ROW String(v1) | StrictDate(1900-01-01) |
   ROW String(v2) | StrictDate(1900-01-01) |
   ROW String(v3) | StrictDate(1900-01-01) |
```
All three rows come back for a date decades before any data, each *asserting*
`businessDate = 1900-01-01`. Contrast with the loud arm for a *missing date argument*
(`fetch of temporal class 'mt::BProd' requires a milestoning date argument`) — a missing
argument is an error, a missing milestoning block is not.

---

### [SILENT FALLBACK] 12. Open-ended (`thru IS NULL`) version rows are invisible at every date, including `%latest`

**Evidence** — the window predicate is built with no null handling
(`TemporalFrame.java:1577-1584`):
```java
            cond = cmpCall("meta::pure::functions::boolean::and",
                    dateCmpCall("...lessThanEqual", col.apply(fromCol), date, boolT),
                    dateCmpCall("...greaterThan",  col.apply(thruCol), date, boolT), boolT);
```
and the column read is deliberately stamped `[1]` to suppress null guards
(`TemporalFrame.java:1458-1464`):
```java
            // MACHINE columns window UNGUARDED (h2New plan goldens) —
            // [1] keeps comparison-site null guards out of stamps
            return new TypedPropertyAccess(new TypedVariable(v, rowT),
                    c.name(), new ExprType(c.type(), Multiplicity.Bounded.ONE));
```
The physical column is nullable (`thru_z DATE`), so the `[1]` stamp is a claim the data
can violate; the consequence is that `NULL > date` → NULL → row dropped.

**Repro** (`mT2.pure` with a declared `INFINITY_DATE=%9999-12-31`; row 3 has `thru_z = NULL`):
```
m2::P.all(%2022-01-01) -> ROW String(v2)      # vNull absent
m2::P.all(%latest)     -> ROW String(v2)      # SQL: WHERE t0.thru_z = DATE '9999-12-31'
m2::P.all(%2021-01-01) -> ROW String(v2)
m2::P.all(%2020-01-01) -> ROW String(v1)
```
`vNull` (from 2021-01-01, open-ended) is returned by **no** date and by no `%latest`.
Without an `INFINITY_DATE` declaration (`mT.pure`) the same drop takes the *current* row of
a business-temporal table and *both* current rows of a bitemporal table
(`mt::XProd.all(%2020-06-01, %2020-06-01)` → `(0 rows)` for a table holding exactly one
open current version). No diagnostic in either case.

---

### [SILENT FALLBACK] 13. Snapshot-column type DEFAULTS to "is a date" when the column is absent from the row

`TemporalFrame.java:1478-1483`:
```java
            boolean snapColIsDate = row.columns().stream()
                    .filter(x -> x.name().equalsIgnoreCase(snapCol)).findFirst()
                    .map(x -> x.type() == Type.Primitive.STRICT_DATE)
                    .orElse(true);
```
A missing column silently yields `true` and the fetch date is day-truncated. Missing *type
data* defaulted, not walled — the repo forbids this. (I could not construct a model where
the snapshot column is off the row without hitting an earlier wall; reported on the code.)

---

### [SILENT FALLBACK] 14-17. Catch/orElse census over the package

Every `catch` in the scope files (exhaustive — `grep -c 'catch *('`, 13 total across the
package, 8 in scope files):

| Site | Caught | Behaviour | Class |
|------|--------|-----------|-------|
| `ClassSources.java:722` | `RuntimeException notBuildable` | `continue` — a subclass whose mapping fails to build is silently dropped from the parent source, so its `stc_…` columns vanish from the row type | **SILENT FALLBACK** (broad catch; swallows NPE/ISE too) |
| `ClassSources.java:910` | `NotImplementedException wall` | per-key deferral, re-thrown at read time via `throwIfDeferred` | acceptable (deferred, not swallowed) |
| `CorrelatedSubselects.java:1913` | `MappingResolutionException` | `om = null` → the embedded-cast arm is skipped and the read canonicalizes differently | **SILENT FALLBACK** |
| `CorrelatedSubselects.java:1948` | `MappingResolutionException` | `m = null` → `castTarget` returns null, subtype canonicalization skipped | **SILENT FALLBACK** (the javadoc claims only "undecidable dispatch" skips; a genuinely unresolvable mapping takes the same branch) |
| `InnerDemand.java:512` | `NotImplementedException \| LegendCompileException` | `return null` → "not an in-query read", different lowering | **SILENT FALLBACK** (narrowed from `RuntimeException`, documented; still a defaulting on a wall) |
| `AsorRef.java:97` | `IllegalArgumentException \| IndexOutOfBoundsException` | `return null`; caller walls | sound |
| `AssociationJoins.java:533` | `NotImplementedException` | re-throws with context | sound |
| `SyntheticHeads.java:101` | `NumberFormatException` | `IllegalStateException("malformed synthetic head (resolver bug)")` | reachable only from resolver-minted names — **not** user-reachable (`#f`/`#d`/`#c` are synthesized); dead-ish guard |
| `GraphEmission.java:1418,1551,1696,2482,2736` | `RuntimeException` ×4, `NotImplementedException` ×1 | out of my scope (graph channel) | noted for the graph auditor |

`orElse`-family on type data, in scope:
- `RelationalRootForm.java:78-83` — a declared `~primaryKey` column not found on the row is
  **silently skipped** (`if (col == null) continue;`), shifting the `pk_i` numbering; and
  `primaryKeyColumns` returns `List.of()` when the table definition is missing
  (l.243-246) — the identity columns quietly disappear. **SILENT FALLBACK.**
- `InnerDemand.java:82` — `ctx.findProperty(...).orElse(null)` then `return false`: an
  unresolvable property silently takes the non-aggregated (row-fanning) arm.
- `CastNav.java:68` — `ctx.findProperty(...).orElse(null)` → `return null`, caller walls. Sound.
- `ViewFrames.java:30,77` — `orElse(null)` → "not a view"; frame naming only, no type data.

---

### [INFORMATION LOSS] 18. `genericType().rawType` is re-derived into a package-less `String`

`GenericTypeReflection.rawTypeProjection` replaces the whole M3 expression with a
`TypedProject` of a `String` CASE, using `simpleName(fqn)` (l.126-129) — the package is
dropped, so `a::Car` and `b::Car` are indistinguishable — and hard-stamps the relation
`Multiplicity.Bounded.ONE` regardless of the source's `[*]`.

**Repro** (`mI2.pure`):
```
QUERY: mi::Vehicle.all()->map(x|$x->genericType().rawType)
  ROOT before = meta::pure::metamodel::type::Type[*]
  ROOT after  = Relation<(rawType:String[1])>[1]
  *** ROOT TYPE CHANGED ***
```
Result is correct-by-value here (`Car/Car/Bike`), but the type went from an M3 element to a
`String` and `[*]` to `[1]`. Additionally, when the union carries **no** membership
witnesses the code falls back to `simpleName(baseClassFqn)` (l.112-114) — silently
returning the *base* class for every row even though the javadoc says such an extent
"stays loud". Demonstrated on `mI.pure` (two `Vehicle[cN]` sets over different tables):
```
  SQL   : SELECT 'Vehicle' AS rawType FROM ( ... UNION ALL ... ) AS t2
   ROW String(Vehicle) | ROW String(Vehicle) | ROW String(Vehicle) |
```

---

### [FORWARD/BACKWARD ASYMMETRY] 19. One Pure `Integer` decodes to three different Java classes, one of them dialect-dependent

Same model, same declared column type, `mA.pure`:
```
plain column   COL b : Integer mult=[1]   ROW Integer(10)                        (both backends)
size()         COL s : Integer mult=[1]   ROW Long(2)                            (both backends)
max()          COL s : Integer mult=[0..1] ROW Integer(30)                       (both backends)
sum()          COL s : Integer mult=[1]   ROW BigInteger(30)  [duckdb]
                                          ROW Long(30)        [h2]
```
A consumer that switches on the runtime class of an `Integer[1]` cell gets a different
answer per aggregate and per dialect.

---

### [DOC-LIE] 20. Three false prose claims in the audited files

1. `Substitution.java:51-56` — "a replacement always carries the SAME `ExprType` as the node
   it replaces … no restamping pass exists". Contradicted by rules 2,3,6,7,11,13,15-23,
   27,31-33,36,38-40 of §1 and by every `*** TYPE X -> Y ***` line in the TypeDiff output.
2. `Substitution.java:2793-2796` — "stamped with the READ's multiplicity (`[0..1]`)". The
   code stamps `pa.info().multiplicity()`, which is `[1]` for the `->toOne()` spelling
   (FINDING 1).
3. `GenericTypeReflection.java:27-31` — "a multi-member extent WITHOUT witnesses … stays
   loud". It returns the base class name instead (FINDING 18).

---

## 2. THE CROSS-H TYPE DIFF (task item 2)

`TypeDiff.java` output, condensed to the divergences. Full logs:
`.../scratchpad/a32/full_A.txt`, `full_T.txt`. Corpus covers class root + relational
mapping, single/multi-hop association navigation, `[0..1]`/`[1]`/`[*]` ends, inheritance
+ union set-dispatch, embedded + `Otherwise`, M2M (`Pure { ~src }`), view-backed joins,
filters pushed through, aggregates, joins, milestoned/temporal (business, processing,
bitemporal, allVersions, dated property functions), and `pk_0`/raw-name collisions.

| Query | Node | BEFORE | AFTER |
|---|---|---|---|
| `Person.all()->project(~[a:p\|$p.lastName])` | leaf | `String[1]` | `trustOne :: String[1]` — kept |
| `…project(~[a:p\|$p.nick])` | var | `ma::Person[1]` | `Relation<…>[1]` (row var, expected) |
| `…project(~[a:p\|$p.firm.legalName])` | leaf | `String[0..1]` | `trustOne :: String[1]` ✱ |
| `…project(~[a:p\|$p.firm.revenue])` | leaf | `Integer[0..1]` | `trustOne :: Integer[1]` ✱ |
| `…project(~[a:p\|$p.firm.fid])` | leaf | `Integer[0..1]` | `trustOne :: Integer[1]` ✱ |
| `…project(~[a:p\|$p.addresses.city])` | leaf | `String[*]` | `trustOne :: String[1]` ✱ |
| `…project(~[a:p\|$p.addresses->map(x\|$x.city)])` | `TypedMap` | `String[*]` | `trustOne :: String[1]` ✱ (rule 36) |
| `…project(~[a:p\|$p.addresses.street->toOne()])` | leaf | `String[*]` | `PropertyAccess(addresses_STREET) :: String[0..1]` ✱ |
| `…project(~[a:p\|$p.addresses->toOne().street])` | whole | `String[1]` | `trustOne :: String[1]` — kept, **but `toOne` deleted** |
| `…$p.addresses->filter(..)->toOne().street` | leaf | `String[1]` | `TypedProject :: Relation<(street:String[1])>[1]` ✱✱ |
| `…project(~[a:p\|$p.addresses->size()])` | call | `Integer[1]` | `TypedIf :: Integer[1]` — kept |
| `Firm.all()->project(~[a:f\|$f.employees.age->sum()])` | call | `Integer[1]` | `PropertyAccess(employees_agg_agg_0) :: Integer[1]` — kept (col is nullable) |
| `…->max()` | call | `Integer[0..1]` | `PropertyAccess :: Integer[0..1]` — kept |
| `filter(p\|$p.addresses->isNotEmpty())` | head | `ma::Addr[*]` | `TypedFilter :: Relation<…>[1]` ✱ |
| `filter(p\|$p.addresses->exists(x\|…))` | pred λ | `{ma::Addr[1]→Boolean[1]}` | `{(ID:…,CITY:…)[1]→Boolean[1]}` ✱ |
| `filter(p\|$p.firm.legalName == 'Acme')` | leaf | `String[0..1]` | `trustOne :: String[1]` ✱ |
| `filter(p\|!($p.addresses.city == 'NYC'))` | `not` | `Boolean[1]` | `TypedIf :: Boolean[1]` — kept (rule 13) |
| `Person.all()->map(p\|$p.lastName)` | ROOT | `String[*]` | `Relation<(u_map__lastName:String[1])>[*]` ✱ |
| `Person.all()->map(p\|$p.firm.legalName)` | ROOT | `String[*]` | `Relation<(u_map__legalName:String[0..1])>[*]` ✱ |
| `Person.all()->map(p\|$p.addresses->toOne().street)` | ROOT | `String[*]` | `Relation<(u_map__street:String[1])>[*]` ✱ → ICE (F7) |
| `Vehicle.all()->map(x\|$x->genericType().rawType)` | ROOT | `meta::pure::metamodel::type::Type[*]` | `Relation<(rawType:String[1])>[1]` ✱✱ |
| `BProd.all(%d)->project(~[d:p\|$p.businessDate])` | leaf | `Date[1]` | `TypedCDate :: StrictDate[1]` ✱ |
| `PProd.all(%d)->project(~[d:p\|$p.processingDate])` | leaf | `Date[1]` | `TypedCDate :: StrictDate[1]` ✱ |
| `XProd.all(%p,%b)->…businessDate/processingDate` | leaves | `Date[1]` ×2 | `StrictDate[1]` ×2 ✱ |
| `BProd.all(%d)->…$p.milestoning.from` | leaf | `DateTime[0..1]` | `PropertyAccess(from_z) :: StrictDate[0..1]` ✱✱ (F5) |
| `BProd.all(%d)->…$p.milestoning.thru` | leaf | `DateTime[0..1]` | `PropertyAccess(thru_z) :: StrictDate[0..1]` ✱✱ (F5) |
| `ct::P.all(%d)->…$p.milestoning.from` (unmilestoned tbl) | leaf | `DateTime[0..1]` | `TypedCollection([]) :: DateTime[0..1]` — kept |
| `CompP.all()->project(~[n:p\|$p.full])` (M2M) | leaf | `String[1]` | `plus :: String[1]` — kept |
| `A.all()->project(~[b:x\|$x.bs.bid])` (view join) | leaf | `Integer[*]` | `trustOne :: Integer[1]` ✱ |
| `Vehicle.all()->project(~[v:x\|$x.vin])` (union) | root src | `mi::Vehicle[*]` | `Relation<(vin, stc_…_$member:Boolean[1] …)>[1]` ✱✱ (F6) |
| `Vehicle.all()->…$x->subType(@Car).doors` | leaf | `Integer[1]` | `TypedIf :: Integer[1]` — kept, cell NULL (F6) |
| `Person.all()->sortBy(p\|$p.age)->project(…)` | — | — | no divergence |
| `Person.all()` (graph root) | — | — | no divergence |

✱ = internal divergence (does not reach the result schema in this query).
✱✱ = divergence that reaches the observable result schema.

Every one of the ~25 queries that navigates an association shows at least one node whose
`ExprType` changed. The only shapes with **zero** divergence are same-table scalar
projections, `sortBy`, and the bare graph root.

---

## VERIFIED SOUND

- **`isEmpty`/`isNotEmpty` over a `[1]`-restamped nullable read still emits `IS NULL`.**
  `ma::Person.all()->filter(p|$p.firm.legalName->isEmpty())` →
  `WHERE t1.LEGAL_NAME IS NULL`, returns exactly `Bob`; the projection form gives
  `e : Boolean[1]` = `false/false/true`; the `if(...)` form gives `some/some/none`.
  The lowerer looks through the `trustOne` wrapper, so the internal `[1]` claim does not
  suppress the null test. Both DuckDB and H2.
- **Bitemporal argument order** is real pure's `(processingDate, businessDate)`:
  `mt::XProd.all(%2020-03-01, %2020-09-01)` emits `in_z <= DATE '2020-03-01' … from_z <=
  DATE '2020-09-01'` and reads back `businessDate = 2020-09-01`, `processingDate = 2020-03-01`.
- **Milestone window boundaries are half-open `[from, thru)`** and correct when
  `INFINITY_DATE` is declared: `%2020-01-01` → `v1`; `%2021-01-01` → `v2` (the boundary row
  flips exactly once); `%latest` → `WHERE thru_z = DATE '9999-12-31'` → `v2`.
- **A date before all versions returns 0 rows** (not a wrong row):
  `mt::BProd.all(%2018-01-01)` → `(0 rows)` under `Relation<(n:String[1])>` — the `[1]` is a
  per-cell claim, and a 0-row relation does not violate it.
- **`.all()` without a date on a temporal class is LOUD**:
  `MappingResolutionException: fetch of temporal class 'mt::BProd' requires a milestoning
  date argument (use allVersions() for the unfiltered extent)`.
- **A half-declared milestoning block is LOUD** (`TemporalFrame.java:1416-1425`) — verified
  by code read; the arm explicitly rejects FROM-without-THRU rather than returning unfiltered.
- **`count`/`size` over an empty navigation is zero-compensated** and returns `0`, not NULL
  (`Substitution.java:1670-1681`) — the control row in FINDING 4.
- **`allVersions()`** projects each row's own `from_z` as `businessDate` and returns all
  three versions — internally consistent with `TemporalFrame.GEN_BUSINESS_DATE`.
- **`Pipelines.rewriteRowReads` / `prefixColumns`** have genuinely closed vocabularies with
  loud `IllegalStateException` defaults (`Pipelines.java:1291-1296`, `1354-1359`); stripped-slot
  and converted-slot misuse throws rather than guessing.
- **`inlineParam`** (`Substitution.java:2219-2276`) preserves every node's `info()` and has a
  real capture guard (2258-2268) plus a loud default.
- **`Anchors`** memoizes by node identity, never iterates the map — the memo is exact.
- **`LiteralFolds.literalEquals`** compares numbers via `BigDecimal.compareTo` (so `1 == 1.0`)
  and falls back to `equals` cross-kind; no silent coercion of String↔Integer.
- **`TemporalContext.single`** is exhaustive over `MilestoningStrategy` and throws
  `NotImplementedException` on `null/default` rather than defaulting to BUSINESS
  (`TemporalContext.java:52-70`) — an old silent-default that was fixed.
- **`ClassSources.java:910` per-key deferral** re-throws at read time; a query that never
  reads the broken binding compiles, one that does still walls. Not a swallow.
- **`AsorRef.decode`** returns `null` on malformed input and the caller walls loudly
  (`Substitution.java:1527-1533`).
- **Substitution's out-of-vocabulary default** (`Substitution.java:1899-1906`) throws
  `NotImplementedException` naming the node kind — verified reachable and clean
  (`object-space expression node TypedGetAll is not substitutable yet (H2 vocabulary)`).
- **M2M (`Pure { ~src }`) composition** preserves types end to end: `$src.f + ' ' + $src.l`
  → `concat(...)` typed `String[1]`, `$src.a` → `trustOne(AGE) :: Integer[1]`, no divergence.
- **View-backed joins** (`View VA ( vid: TA.ID )` + `Join AB_J (VA.vid = TB.AID)`) resolve
  with correct column types; `ViewFrames` touches no type data.

---

## NOT COVERED

- `GraphEmission.java` (3429) and `StoreResolver.java` (3464) — outside my file list; their
  5 broad `catch (RuntimeException)` sites (`GraphEmission.java:1418, 1551, 1696, 2482, 2736`)
  are flagged for whoever owns the graph channel but not repro'd.
- `AssociationJoins.java` (2086) and `ClassSources.java` (1341) beyond the two catch sites.
- `RawGridSchema.java` — read only far enough to record that raw-grid cells are typed
  `Any[0..1]` by design (the schema is late-bound and stamped from a `LIMIT 0` probe); I did
  not build a raw-SQL-grid fixture, so the `Any[0..1]` claim vs actual decode is unverified.
- `JsonSourceFrame.java`, `FlattenOps.java`, `StoreEscapees.java`, `Space.java` — not in scope.
- `allVersionsInRange` — the LEGEND_LITE parser rejects the surface spelling
  (`ParseException: [1:55] .allVersionsInRange(%2019-01-01,%2022-01-01) is not supported`),
  so `TemporalContext.range` / `rangeAppliesTo` are unexercised from a query. Reachable only
  via the `getAll(C, start, end)` protocol spelling, which I did not construct.
- Snapshot milestoning (`SNAPSHOT_DATE`) — I could not build a model where the snapshot
  column is off the substitution row, so FINDING 13's `.orElse(true)` is a code-read finding
  without a runtime repro.
- `NavMaterializer.java` (796) — skimmed for `ExprType` construction (only `getOrDefault`
  on sub-tail lists and a prefix comparison; no type invention found) but not read in full.
- SQLite as a third backend (HOWTO lists it) — I ran DuckDB and H2 only, as the task named
  those two.
- `%latest` over a SNAPSHOT-milestoned table (explicitly walled at `TemporalFrame.java:1474-1477`)
  and `INFINITY_DATE`-absent `%latest` (walled at 1541-1548) — code-read only.
