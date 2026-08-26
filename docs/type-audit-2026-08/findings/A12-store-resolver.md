# A12 — Phase H (StoreResolver / `com.legend.resolver`) — type-preservation audit

**Central question:** does every H rewrite preserve the Phase-G type of the node it replaces?
**Answer: no.** Five distinct classes of non-preservation found, three of them producing runtime
values that violate the compile-time claim.

All probes are in `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/A12/`
(`HDiff.java`, `HTree.java`, `EscapeeScan.java`, `EscapeeRepro.java`, model `big.pure`, DDL `dbig.sql`).
Run with `/home/user/probe/jrun.sh` / `/home/user/probe/probe.sh`. Every "Actual output" block below
is pasted verbatim from a run.

---

## FINDINGS

### [UNSOUND] H1 — object-space `->toOne()` is DELETED from the chain: the `[1]` assertion vanishes and N rows are returned silently

**Evidence** — `core/src/main/java/com/legend/resolver/StoreResolver.java:2587-2590`:

```java
if (cur instanceof TypedNativeCall nc && isClassToOne(nc)) {
    cur = nc.args().get(0);
    continue;
}
```

The node is replaced by its argument. Its `info()` (`C[1]`) is discarded and no LIMIT / row-count
guard is emitted anywhere. The relation-space sibling (`Scalars.java` `toOne` rule, lines 441-482)
*does* emit `CheckedOne(CompactList(...))`.

The javadoc at `StoreResolver.java:1073-1076` (quoted below) claims the opposite:

```java
/** {@code toOne(instances)}: multiplicity coercion over a class
 * collection — PASS-THROUGH in the pipeline (the engine raises on
 * N≠1; here the value compare sees all N and fails loud — a
 * documented, weaker-but-never-silent stand-in). */
```

It is silent, not loud. (Secondary category: DOC-LIE.)

**Repro**
```
echo 'm::Person.all()->toOne()->project(~[a:p|$p.firstName])' > /tmp/q.pure
/home/user/probe/probe.sh .../big.pure /tmp/q.pure s::RT .../dbig.sql
```
T_PERSON holds 3 rows.

**Actual output**
```
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS a
FROM T_PERSON AS t0
[EXEC-COL] a : String [STRING] mult=[1]
[EXEC-ROW] String(John) |
[EXEC-ROW] String(Jane) |
[EXEC-ROW] String(Bob) |
```
Contrast the relation-space path, which is correct:
```
query: m::Person.all()->project(~[a:p|$p.firstName])->map(r|$r.a)->toOne()
[PLAN] SELECT CASE WHEN ... len(...) <> 1 THEN error(concat('Cannot cast a collection of size ', ...
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: Cannot cast a collection of size 3 to multiplicity [1]
```

**Why it matters** Two spellings of the same Pure semantics diverge: one enforces the multiplicity
in SQL, the other silently returns 3 rows where the type says 1. Any consumer that trusts
`QueryPlan.rootType()` / `ExecutionResult.returnType()` to size the result is wrong.

**Same family, same site**
`StoreResolver.java:2591-2606` rewrites `at(cl, k)` → `TypedSlice(src, k, k+1, nc.info())`, keeping
`at`'s `C[1]` info. An out-of-range index yields **zero** rows instead of pure's size error:
```
query: m::Person.all()->at(10)->project(~[a:p|$p.firstName])
[G] type=Relation<(a:String[1])> mult=[1]
[EXEC-COL] a : String [STRING] mult=[1]
   (no [EXEC-ROW] lines — zero rows)
```
and `m::Person.all()->filter(p|$p.age>1000)->first()->toOne()->project(~[a:p|$p.firstName])`
likewise returns zero rows under a `[1]` claim, no error.

---

### [UNSOUND] H2 — every association-navigation LEFT JOIN copies the right side's column multiplicities verbatim; a `[1]`-declared end comes back NULL

**Evidence** — `core/src/main/java/com/legend/resolver/Pipelines.java:421-430` (join-slot arm) and
`Pipelines.java:557-568` (navigate arm), identical shape:

```java
Type.RelationType leftRow  = Type.requireRelationSchema(left.info().type());
Type.RelationType rightRow = Type.requireRelationSchema(tgt.info().type());
List<Type.Column> cols = new ArrayList<>(leftRow.columns());
for (Type.Column c : rightRow.columns()) {
    cols.add(new Type.Column(prefix + c.name(), c.type(), c.multiplicity()));   // <-- line 425
}
return new TypedJoin(left, tgt,
        new TypedEnumValue(JOIN_KIND_FQN, "LEFT", ...),
        cond, Optional.of(prefix), js.frameName(),
        new ExprType(Type.relation(new Type.RelationType(cols)), Multiplicity.Bounded.ONE),
        false /* resolver-synth */);
```

The join kind is a hard-coded `"LEFT"` (there is no `"INNER"` literal anywhere in
`AssociationJoins.java`; the only INNER re-stamp is `FlattenOps.innerizeFlattenJoin`,
`FlattenOps.java:191`). A LEFT OUTER JOIN makes *every* right-side column nullable, yet the
emitted schema keeps the right row's `[1]`. `StoreResolver.java:2036-2038` repeats the same copy
for the grouped-aggregate back-join.

**Repro (fx model — `person: Person[1]`, plus one orphan address row)**
```
DDL adds: INSERT INTO T_ADDRESS VALUES (5,99,'Orphan Way','Nowhere');   -- no matching person
query:    model::Address.all()->project(~[st:a|$a.street, pn:a|$a.person.firstName])
```

**Actual output**
```
[G] type=Relation<(st:String[1], pn:String[1])> mult=[1]
[PLAN] SELECT t0.STREET AS st, t1.FIRST_NAME AS pn
FROM T_ADDRESS AS t0
LEFT OUTER JOIN T_PERSON AS t1 ON t1.ID = t0.PERSON_ID
[EXEC-COL] st : String [STRING] mult=[1]
[EXEC-COL] pn : String [STRING] mult=[1]
[EXEC-ROW] String(123 Main St) | String(John) |
[EXEC-ROW] String(456 Oak Ave) | String(John) |
[EXEC-ROW] String(789 Main Rd) | String(Jane) |
[EXEC-ROW] String(999 Pine Lane) | String(Bob) |
[EXEC-ROW] String(Orphan Way) | null |          <-- null in a String[1] column
```

The resolver's own tree (probe `HTree.java`) shows H manufacturing the wrong claim while the
correct one is in hand one node below:
```
--- AFTER H ---   (m::Address.all()->project(~[pn:a|$a.person.firstName]))
TypedProject cols=[pn]  :: Relation<Rel<pn:String[1]>>[1]
  TypedJoin  :: Relation<Rel<ID:Integer[1], PERSON_ID:Integer[1], STREET:String[1], CITY:String[1],
                            person_ID:Integer[1], person_FIRST_NAME:String[1], person_LAST_NAME:String[1],
                            person_AGE_VAL:Integer[1], person_PRIMARY_ADDR_ID:Integer[0..1],
                            person_SAL:Float[0..1], person_KIND:String[0..1]>>[1]
    TypedTableReference T_ADDRESS :: ...
    TypedTableReference T_PERSON  :: ...
    TypedEnumValue LEFT  :: meta::pure::functions::relation::JoinKind[1]
```
`person_FIRST_NAME:String[1]` on the result of a **LEFT** join. Note that H *does* correctly track
physical nullability elsewhere in the very same row type (`person_SAL:Float[0..1]`,
`person_PRIMARY_ADDR_ID:Integer[0..1]`) — the LEFT-join weakening is simply not applied.

**Same defect on the milestoned navigation path**
```
query: m::Ord.all()->project(~[i:o|$o.id, n:o|$o.prod($o.odate).name])   -- prod: m::Prod[1]
[PLAN] ... LEFT OUTER JOIN PRODT AS t1 ON t0.PID = t1.ID AND t1.from_z <= t0.odate AND t1.thru_z > t0.odate
[EXEC-COL] n : String [STRING] mult=[1]
[EXEC-ROW] Integer(1) | String(Widget) |
[EXEC-ROW] Integer(2) | null |
```

**Same defect on the graph path**
```
query: m::Address.all()->graphFetch(#{m::Address{street, person{firstName}}}#)->serialize(...)
[EXEC-JSON] [ ... ,{"street":"Orphan Way","person":null}]     -- person is Person[1]
```

**Why it matters** Top prize: the compiler asserts `[1]`, the runtime hands back `null`. It affects
every association navigation in every terminal (project / graph / scalar map), and the wrong
multiplicity is *invented by H* out of a correct one.

---

### [UNSOUND] H3 — synthesized `trustOne` launders a physically-nullable `[0..1]` column into `[1]`, and lowers to identity (no guard)

**Evidence** — the mapping-body normalizer wraps *every* store read bound to a `[1]` property:
`core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3394-3396`
```java
wrapped.put(name, toOneDeclared && !exempt
        ? new KeyExpression(new AppliedFunction(com.legend.builtin.Pure.Lite.TRUST_ONE, List.of(v)), ...)
        : new KeyExpression(v, key.isAdd(), key.isLocal()));
```
whose javadoc (`MappingNormalizer.java:3332-3335`) promises
> "the MAPPING is the assertion that the read is to-one, and the residual null-check is `toOne`'s runtime semantics."

There is no residual null-check. `core/src/main/java/com/legend/lowering/Scalars.java:485-490`:
```java
// trustOne — the SQL-lane conformance wrap (Lite.TRUST_ONE):
// IDENTITY, no guard; SQL null-propagates ...
for (String f : Pure.nativeKeysAt(Pure.Lite.TRUST_ONE)) {
    RULES.put(f, (n, args) -> args.get(0));
}
```
and `Lowerer.java:413-416` confirms `"trustOne (synthesized conformance) never guards"`.

Meanwhile `compiler/element/StoreCompiler.java:108-110` computes the *correct* physical multiplicity:
```java
Multiplicity mult = (col.get().notNull() || col.get().primaryKey() || cm.primaryKey())
        ? Multiplicity.Bounded.ONE : Multiplicity.Bounded.ZERO_ONE;
```

**Repro** — `m::Employee.salary: Float[1]` mapped to `SAL DOUBLE` (nullable); Bob has `SAL = NULL`.
```
echo 'm::Employee.all()->project(~[s:p|$p.salary])' | jrun.sh HTree.java big.pure s::RT
```
**Actual output**
```
--- BEFORE H ---
TypedProject cols=[s]  :: Relation<Rel<s:Float[1]>>[1]
  TypedGetAll m::Employee  :: Employee[*]
  TypedLambda  :: fn[1]
    TypedPropertyAccess .salary  :: Float[1]
      TypedVariable $p  :: Employee[1]
--- AFTER H ---
TypedProject cols=[s]  :: Relation<Rel<s:Float[1]>>[1]
  TypedTableReference T_PERSON  :: Relation<Rel<..., SAL:Float[0..1], ...>>[1]
  TypedLambda  :: fn[1]
    TypedNativeCall trustOne  :: Float[1]           <-- re-stamps [0..1] as [1]
      TypedPropertyAccess .SAL  :: Float[0..1]      <-- H knows the truth here
```
and end to end:
```
query: m::Employee.all()->project(~[fn:p|$p.firstName, s:p|$p.salary])
[EXEC-COL] s : Float [FLOAT] mult=[1]
[EXEC-ROW] String(Bob) | null |
```
JSON path identical: `{"firstName":"Bob","lastName":"Jones","age":45,"salary":null}` for `Float[1]`.

**Why it matters** The physically-correct `[0..1]` is available *in the same node tree* and is
deliberately overwritten with an unenforced `[1]`. This is the mechanism behind H2 as well
(`trustOne(person_FIRST_NAME)` in the H2 tree dump). The doc claim of a residual null-check is a
DOC-LIE.

---

### [CRASH/ICE] H4 — the same null crashes the COLLECTION egress with an internal `IllegalStateException`

**Repro** (fx model + orphan address row):
```
model::Address.all()->map(a|$a.person.firstName)
```
**Actual output**
```
[G] type=String mult=[*]
[PLAN] SELECT t1.FIRST_NAME AS u_map__firstName
[EXEC-ERROR] java.lang.IllegalStateException: NULL cell reached COLLECTION egress — the lowerer owns
             the null-drop (COMPILER_SHORTCUT_AUDIT §5); a NULL here is a lowering defect, never an empty
```
The guard in `resolver/ScalarValueReads.java:59-62` weakens the cell multiplicity only when the
*body's own* stamp is `[0..1]`:
```java
if (cellMult instanceof Multiplicity.Bounded cb && cb.lower() == 1 && cellCanBeEmpty(body)) {
    cellMult = Multiplicity.Bounded.ZERO_ONE;
}
```
`cellCanBeEmpty` unwraps `toOne`-family wrappers and asks the operand's `info()`. For
`$a.person.firstName` the operand is a `trustOne` whose argument is a joined column stamped `[1]`
(finding H2), so the weakening never fires and the null escapes to the egress assertion.
An internal `IllegalStateException` on ordinary input (a dangling association key) is a CRASH per
the brief, not a user-facing error.

---

### [UNSOUND / INFO-LOSS] H5 — `->serialize(tree)` loses its `String[1]` type: `QueryPlan.rootType()` reports a **class** for a JSON string value

**Evidence** — `StoreResolver.java:2545-2551` dissolves the `TypedSerialize` wrapper (its `info()` is
never read), and `StoreResolver.java:2920-2921` stamps the envelope with the **getAll's** type:
```java
TypedSerializeGraph env = new GraphEmission(...)
        .buildGraphNode(cs, pipeline, m.slotPrefixes(), m.stripped(), fresh, tree, context,
                        /*arrayWrap*/ true, g.info(), checkedEnvelope);
```
`g` is the `TypedGetAll`, so the root's `ExprType` becomes `Class[*]`.

**Repro / Actual output**
```
query: m::Person.all()->graphFetch(#{m::Person{firstName,lastName}}#)->serialize(#{m::Person{firstName,lastName}}#)
[G] type=String mult=[1]                                     <-- Phase G
[PLAN] rootType=m::Person mult=[*]                           <-- after H
[EXEC] shape=Graph returnType=m::Person returnTypeRepr=ClassType[fqn=m::Person]
[EXEC-COL] json : String [STRING] mult=null
[EXEC-ROW] String([{"firstName":"John","lastName":"Smith"},...])
```
The value is a `String`; `ExecutionResult.returnType()` says `m::Person`.
Also an information loss: `m::Person.all()` and `m::Person.all()->graphFetch(t)->serialize(t)` have
*different* Phase-G types (`Person[*]` vs `String[1]`) and *identical* post-H types — H erases the
distinction between "give me objects" and "give me the JSON text".

---

### [INCONSISTENCY / INFO-LOSS] H6 — object-space scalar `->map` re-types `T[m]` as `Relation<…>[m]`; the reported COLLECTION column type becomes a Relation

**Evidence** — `resolver/ScalarValueReads.java:63-70`:
```java
Type.RelationType row = new Type.RelationType(List.of(new Type.Column(name, result.type(), cellMult)));
return new TypedProject(source, List.of(new TypedFuncCol(name, mapper)),
        new ExprType(Type.relation(row), valueMult));
```
`valueMult` is the ORIGINAL scalar collection's multiplicity, re-used as the *relation's*
multiplicity — the only relation node in the tree that is not `[1]`.

**Repro / Actual output**
```
query: m::Person.all()->map(p|$p.firstName)
[G] type=String mult=[*]
[PLAN] rootType=Relation<(u_map__firstName:String[1])> mult=[*]
[EXEC] shape=Collection returnType=Relation<(u_map__firstName:String[1])>
[EXEC-COL] value : Relation<(u_map__firstName:String[1])> ... mult=null
[EXEC-ROW] String(John) |
[EXEC-ROW] String(Jane) |
[EXEC-ROW] String(Bob) |
```
The column named `value` is declared to be a **Relation** and holds **Strings**.
The other producer of the same shape is correct:
```
query: [1,2,3]
[EXEC] shape=Collection returnType=Integer
[EXEC-COL] value : Integer [INTEGER] mult=null
```
Two paths to `ResultShape.COLLECTION` report incompatible types for the same kind of result.
(Downstream consumers are unaffected — `->joinStrings(',')` and `->size()` over the same map both
report `String[1]` / `Integer[1]` correctly — so the blast radius is the public root-type surface.)

---

### [SILENT FALLBACK] H7 — `assertNoStoreOnlyEscapees` does NOT visit every child: 7 walker holes, and the same holes blind the resolver's own `Anchors` classifier

`StoreEscapees.check` (`resolver/StoreEscapees.java:22-40`) walks with `n.children()`.
So do `Anchors.anchored`, `Anchors.containsGetAll`, and `Anchors.spaceOf`
(`resolver/Anchors.java:43-62` (`anchored`), `:117-126` (`containsGetAll`), `:73-82` (`spaceOf`)). A slot missing from `children()` is therefore
**both** classified `Space.INERT` (⇒ `resolveNode` returns the node unchanged — see
`StoreResolver.java:310-313`) **and** invisible to the post-condition check. The claimed
runtime enforcement does not hold.

**Exhaustive scan** (`EscapeeScan.java`, package `com.legend.resolver`): all **70**
`TypedSpec.getPermittedSubclasses()` variants, all **101** slots that can structurally carry a
`TypedSpec`, one `TypedGetAll` planted per slot.
(The 70 permitted variants vs 77 files: `TypedAggCol`, `TypedFuncCol`, `TypedGraphTree`,
`FoldStrategy`, `VarUse`, `WindowFrame`, `TypedSpec` itself are not variants.)

**Actual output (tail)**
```
variants=70
spec-carrying slots probed=101  seen=89  MISSED=12
---- HOLES (walker does NOT descend) ----
  TypedUserCall              .args               escapee-seen=false anchors-seen=true
  TypedAggColSpec            .col                escapee-seen=false anchors-seen=false
  TypedAggColSpecArray       .cols               escapee-seen=false anchors-seen=false
  TypedPivot                 .aggs               escapee-seen=false anchors-seen=false
  TypedFrom                  .mapping            escapee-seen=false anchors-seen=false
  TypedFrom                  .runtime            escapee-seen=false anchors-seen=false
  TypedGraphFetch            .tree               escapee-seen=false anchors-seen=false
  TypedSerialize             .tree               escapee-seen=false anchors-seen=false
  TypedExtendWindow          .window             escapee-seen=false anchors-seen=false
  TypedExtendWindow          .aggs               escapee-seen=false anchors-seen=false
  TypedExtendAgg             .aggs               escapee-seen=false anchors-seen=false
  TypedJoin                  .kind               escapee-seen=false anchors-seen=false
---- constructor failures (not probed) ----
   (none)
```
Of the 12, **5 are probe artifacts** (the slot cannot structurally hold a getAll or the node is
caught as itself): `TypedUserCall.args` (the `TypedUserCall` itself throws first),
`TypedFrom.mapping`/`.runtime` (`Optional<TypedPackageableRef>`), `TypedJoin.kind`
(`TypedEnumValue`), `TypedExtendWindow.window` (`TypedOver`, which holds no `TypedSpec`).

**The 7 real holes:**

| variant | un-visited content | source |
|---|---|---|
| `TypedAggColSpec` | `col.orderKey()` | `TypedAggColSpec.java:19-21` returns only `col.map(), col.reduce()` |
| `TypedAggColSpecArray` | every `cols[i].orderKey()` | `TypedAggColSpecArray.java:21-28` |
| `TypedExtendAgg` | every `aggs[i].orderKey()` | `TypedExtendAgg.java:23-32` |
| `TypedExtendWindow` | every `aggs[i].orderKey()` | `TypedExtendWindow.java:31-42` |
| `TypedPivot` | every `aggs[i].orderKey()` | `TypedPivot.java:35-45` |
| `TypedGraphFetch` | the whole `tree` (`TypedGraphTree.args` + nested trees) | `TypedGraphFetch.java:35-37` returns `List.of(source)` |
| `TypedSerialize` | the whole `tree` | `TypedSerialize.java:25-30` returns `source` (+ optional `config`) |

**INCONSISTENCY**: `TypedAggregate.children()` and `TypedGroupBy.children()` *do* emit
`a.orderKey()` (`TypedAggregate.java:23-35`, `TypedGroupBy.java:40-54`). Five sibling variants
carrying the identical `List<TypedAggCol>` do not. `SyntheticHeads.java:1075-1076` and
`StoreResolver.java:2945` hand-apply the rewrite to `orderKey` — local workarounds that confirm
the generic walkers miss it.

**Repro** (`EscapeeRepro.java`, package `com.legend.resolver` — builds each shape and calls
`new StoreResolver(ctx, specs).resolve(body, "test::TestRuntime")`):
```
TypedExtendAgg.aggs[].orderKey                 -> resolve() RETURNED OK; getAll still present = true
TypedPivot.aggs[].orderKey                     -> threw NotImplementedException: class query under TypedPivot is not resolvable yet (H2 vocabulary)
TypedSerialize.tree[].args                     -> threw NotImplementedException: object-space operation TypedProject is not supported yet
TypedGraphFetch.tree[].args                    -> threw NotImplementedException: class query under TypedGraphFetch is not resolvable yet (H2 vocabulary)
CONTROL TypedExtendAgg.aggs[].map (visited)    -> threw NotImplementedException: store resolution left getAll(model::Address) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedExtendAgg > TypedLambda]
```
The `TypedExtendAgg.orderKey` case is the decisive one: `resolve()` **returns successfully** with a
live `TypedGetAll` in the tree (deep reflective reach over record components, not `children()`),
while the identical getAll in the adjacent `.map` slot is caught loudly. The stated post-condition
("no `TypedGetAll`/`TypedUserCall` survives H, enforced at runtime") is false.

**Reachability from surface syntax.** I could not smuggle a getAll through a *user-written* query:
`orderKey` is only ever synthesized by the resolver itself (`Typer.java:2265` always passes `null`),
and the graph-tree arg path is inlined into `TypedSerializeGraph.leaves` by `GraphEmission`, after
which the walker *does* see it:
```
query: model::Person.all()->graphFetch(#{model::Person{firstName,'tg':tag(model::Address.all()->size())}}#)
                          ->serialize(#{model::Person{firstName,'tg':tag(model::Address.all()->size())}}#)
[PLAN-ERROR] NotImplementedException: store resolution left getAll(model::Address) unresolved
             ... [at root > TypedSerializeGraph > TypedLambda > TypedNativeCall > TypedNativeCall]
```
So today the holes are latent, not exploitable end-to-end. They are still real: the invariant is
enforced by a walk that is provably incomplete, and every future rewrite that parks an expression
in one of those 7 slots inherits a silent pass-through.

---

### [INFO-LOSS] H8 — the generated milestoning date properties are RECOMPUTED from the physical column, not copied

`Substitution.milestoneColumnRead` (`resolver/Substitution.java:2181`) takes the node being
replaced as `TypedSpec original` and **never reads it** — the replacement's type is rebuilt from the
row column:
```java
return new TypedPropertyAccess(
        new TypedVariable(rowVar, new ExprType(row, Multiplicity.Bounded.ONE)),
        c.name(), new ExprType(c.type(), c.multiplicity()));
```
This is a category-(b) recompute. Observable effect: the same declared Pure type decodes to two
different runtime classes depending on the physical column.
```
query: m::Prod.allVersions()->project(~[n:p|$p.name, bd:p|$p.businessDate])       -- from_z DATE
[EXEC-COL] bd : Date [DATE] mult=[1]
[EXEC-ROW] String(Widget) | StrictDate(2000-01-01) |

query: m::Acct.allVersions()->project(~[c:p|$p.code, pd:p|$p.processingDate])     -- in_z TIMESTAMP
[EXEC-COL] pd : Date [DATE] mult=[1]
[EXEC-ROW] String(AC1) | DateWithSecond(2000-01-01T00:00:00+0000) |
```
Both are legal `Date` subtypes so this is not unsound, but the precise type H holds
(`StrictDate` / `DateTime`) is discarded when the outer project column re-declares G's `Date`.
If a milestoning column were declared nullable, the recompute would produce `[0..1]` under a
`[1]` project column — the mismatch is unguarded by construction.

---

### [INCONSISTENCY, low] H9 — user-written `JoinType.LEFT_OUTER` has the same `[1]`-under-null defect (Phase G side)

For completeness, the relation-space join checker makes the identical mistake, so the H2 fix has a
sibling outside my scope:
```
query: m::Person.all()->project(~[a:p|$p.firstName])
        ->join(m::Address.all()->project(~[c:x|$x.city, d:x|$x.street]),
               meta::relational::metamodel::join::JoinType.LEFT_OUTER, {x,y|$x.a == $y.c})
[G] type=Relation<(a:String[1], c:String[1], d:String[1])> mult=[1]
[EXEC-ROW] String(Jane) | null | null |
```

---

## Item 1 — COMPLETE REWRITE-RULE INVENTORY

Legend for the type column: **(a)** copies the replaced node's `ExprType`, **(b)** recomputes it
from other data, **(c)** invents a new one. Every (b)/(c) is a candidate divergence; the ones that
actually diverge are cross-referenced to the findings.

### A. Space-independent normalizations — `StoreResolver.resolveNode`, lines 269-313

| # | in | out | type | note |
|---|---|---|---|---|
| A1 | `withFeatureFlags(x, …)` | `resolve(x)` | (b) call's own `info()` discarded, arg's used | benign (documented identity) |
| A2 | `TypedFrom` | `TypedFrom(resolve(SubQueryLift.lift(src)), …, from.info())` | **(a)** | sound |
| A3 | `map(zip(a,b), f)` | 2-column project (`CorrelatedSubselects.zipPairMap`) | (c) | not exercised in corpus |
| A4 | `x.<ROWS_MARKER>` over a relation | `resolve(x)` | (b) marker's own info dropped | sound (marker erasure) |

### B. `Space.OBJECT` — `objectNode`, lines 320-323

| # | in | out | type |
|---|---|---|---|
| B1 | `TypedMap` w/ class-result mapper | `resolve(substituteParam(mapper, source))` | (b) from spliced body |
| B2 | anything else object-space | `resolveChain(n)` → §D/§E | see below |

### C. `Space.ANCHORED` — `anchoredNode`, lines 328-497 (exhaustive switch; `default` throws)

| # | in | out | type |
|---|---|---|---|
| C1 | `TypedProject` over object space | `resolveChain` | (a) `p.info()` |
| C2 | `TypedProject` over class `concatenate` | `TypedConcatenate(resolve(proj₁), resolve(proj₂), p.info())` | **(a)** |
| C3 | `TypedIf` (class-typed) | `resolve(chosen branch)`; non-static condition ⇒ loud | (b) |
| C4 | `size()/count()` over class extent | `relation::size(resolveChain(project(src, [c:p|1])))`, `nc.info()` | (a) outer / (c) inner const relation |
| C5 | `TypedMap` scalar over instances | `ScalarValueReads.scalarMapAsProject` | **(c)** → **H6** |
| C6 | `TypedFilter` over a scalar hop | `foldScalarHopFilter` (one-column relation) | (c) |
| C7 | `TypedPropertyAccess` scalar over instances | `scalarReadAsProject` → same funnel as C5 | (c) |
| C8 | `TypedGroupBy` over object space | `resolveChain` | (a) `g.info()` |
| C9 | `TypedSerialize` over an anchored source | `resolveChain` → §E3 | **(c)** → **H5** |
| C10-C24 | `TypedPropertyAccess`(row) / `Filter` / `Project` / `Sort` / `Cast` / `SortBy` / `Limit` / `Drop` / `Slice` / `Distinct` / `GroupBy` / `Aggregate` / `Extend` / `ExtendWindow` / `ExtendAgg` / `Rename` / `Select` / `Concatenate` / `Join` / relation `Map` | `structural(n)` = `n.mapChildren(resolveNode)` | **(a)** — `withChildren` keeps `info` |
| C25 | `TypedNavigate` over a relation target | rebuilt with `nav.info()`, target kept verbatim | **(a)** |
| C26 | `TypedNativeCall` | `structural(Pipelines.classEmptinessRewrite(nc))` | (a)/(c) for the emptiness rewrite |
| C27 | `TypedCollection` | `structural` | (a) |
| C28 | `TypedCast` | `structural` | (a) |
| C29 | `.genericType().rawType` | `GenericTypeReflection.resolve` → string projection | (c) |
| C30 | bare value read over a chain | `resolve(Pipelines.autoMapRead(vpa))` | (b) |
| C31 | bare `TypedLambda` | `mapChildren(SubQueryLift.resolveClosed)` | (a) |
| C32 | anything else | **throws `NotImplementedException`** — the named wall | — |

### D. Chain collection / normalization — `StoreResolver` lines 2565-2650

| # | in | out | type |
|---|---|---|---|
| D1 | `distinct(cl)` | `TypedDistinct(arg, [], nc.info())` | (a) |
| D2 | `first(cl)` / `head(cl)` | `TypedLimit(arg, 1, nc.info())` | (a) |
| D3 | `toOne(cl)` | **`arg`** — node deleted, info dropped | **(none)** → **H1** |
| D4 | `at(cl, k)` literal k | `TypedSlice(arg, k, k+1, nc.info())` | (a), semantics weakened → **H1** |
| D5 | `sort(key, cmp)` | `TypedSortBy` via `classSortOf` | (a) |
| D6 | in-chain `from()` | node dropped, context re-scoped | (none) |
| D7 | class-result `map` | `substituteParam(mapper, source)` | (b) |
| D8 | class-typed property hop | flatten boundary; chain re-roots at target over a join | (c) |
| D9 | `filter/limit/drop/slice/sortBy/distinct` | collected as ops for §G | — |
| D10 | anything else | **throws** `object-space operation X is not supported yet` | — |
| D11 | `getAll` w/ `forEachDate` | rebuilt with resolved dates relation, `g.info()` | (a) |
| D12 | temporal class `.all()` w/o date | **throws `MappingResolutionException`** | — |

### E. Terminal emission — `StoreResolver` lines ~2905-2962

| # | in | out | type |
|---|---|---|---|
| E1 | `TypedProject` | `TypedProject(pipeline, substituted cols, p.info())` | **(a)** |
| E2 | `TypedGroupBy` | `TypedGroupBy(pipeline, subst keys, subst aggs, gb.info())` | **(a)** |
| E3 | graph tree present | `GraphEmission.buildGraphNode(..., g.info())` — `g` = the **getAll** | **(c)** → **H5** |
| E4 | implicit serialize (bare class root) | same, `g.info()` | (a) — coincides with the getAll's type |
| E5 | `TypedDistinct` in-chain | rebuilt narrowing to the class's own columns, `new ExprType(relation(kept), pipeline.info().multiplicity())` | (c) |
| E6 | `TypedSortBy` in-chain | rebuilt with `pipeline.info()` | (b) |

### F. Class-source construction — `ClassSources` / `ClassSource`

| # | in | out | type |
|---|---|---|---|
| F1 | `getAll(C)` | `TypedTableReference → [TypedJoinSlot]* → [~filter] → [~groupBy/~distinct]` + binding table | (c), row type from `StoreCompiler.tableSchema` (nullability-aware — correct) |
| F2 | `$p.prop` (scalar binding) | `bindings.get(prop)` = `trustOne(row.COL)` | **(c)** `[0..1]`→`[1]` → **H3** |
| F3 | `$p.prop` (class-typed, bare) | **throws** "graph output (Phase H4)" | — |
| F4 | `$p.businessDate` / `$p.processingDate` | `milestoneColumnRead` | **(b)** → **H8** |
| F5 | mixed-kind union extent | per-member arms + `new Type.Column(p.name(), p.type(), p.multiplicity())` | (c) — property-declared, ignores physical nullability |
| F6 | union/Operation mapping | member arms concatenated in declaration order, member-suffixed NULL-crossed key columns | (c) |

### G. Join / navigation materialization — `Pipelines`, `AssociationJoins`, `NavMaterializer`, `CorrelatedSubselects`, `ChainedExists`

| # | in | out | type |
|---|---|---|---|
| G1 | demanded `TypedJoinSlot` | `TypedJoin(left, tgt, LEFT, cond, prefix, …, Relation<left ++ prefix+right>[1])` | **(c)** → **H2** (`Pipelines.java:425`) |
| G2 | demanded `TypedNavigate` | same shape | **(c)** → **H2** (`Pipelines.java:560`) |
| G3 | **un-demanded** slot/navigate | `return left` — join CANCELLED, row type shrinks (`Pipelines.java:404-406`) | (c) — sound (columns provably unread) |
| G4 | filter-position to-many nav / class `isEmpty` | correlated `[NOT] EXISTS` over a nested ObjectRelation | (c) — verified sound, uses `SELECT DISTINCT` (no explosion) |
| G5 | aggregate over a to-many path in projection position | grouped subselect + back-join; `new Type.Column(prefix+c.name(), c.type(), c.multiplicity())` (`StoreResolver.java:2036-2038`) | **(c)** same defect as G1 |
| G6 | flatten hop | `FlattenOps.innerizeFlattenJoin` re-stamps the nav join **INNER** (`FlattenOps.java:191`); absent join ⇒ loud | (c) — row-set narrowing is deliberate |
| G7 | ON-form post-pass | `TypedJoin` rebuilt with `j.info()` after hoisting temporal stamp filters into the ON (`StoreResolver.java:186-206`) | **(a)** |

### H. Temporal — `TemporalFrame`, `TemporalContext`, `DateSplit`, `Anchors`, `ViewFrames`, `AsorRef`

| # | in | out | type |
|---|---|---|---|
| T1 | milestoning dates on a getAll | `TypedFilter(pipe, pred, pipe.info(), Stamp.TEMPORAL)` | **(a)** |
| T2 | `%latest` | `thruCol == INFINITY_DATE` literal stamped `DATE_TIME[1]` (`TemporalFrame.java:1550-1568`) | (c); missing `INFINITY_DATE` ⇒ loud `MappingResolutionException` |
| T3 | snapshot milestoning w/ a datetime arg | `datePart(date)` stamped `STRICT_DATE[1]` (`TemporalFrame.java:1514-1536`); partial date ⇒ loud | (c) — sound |
| T4 | `TypedMilestonedAccess` (`$o.prod(d)`) | nav join with the window ANDed into the ON | (c) → **H2** |
| T5 | two different dates on one chain | date-fingerprinted synthetic head (`DateSplit.splitDatedHeads`) | — |
| T6 | `allVersions()` / `allVersionsInRange` | `TemporalContext.range` / `NONE`; no filter | (a) |
| T7 | bitemporal `.all(p, b)` | `TemporalContext.bitemporal`, both windows on the ON | (c) |
| T8 | single date on a **bitemporal** class | fills the **BUSINESS** slot only (`TemporalContext.java:56-63`) | (c) — source dimension is lost, acknowledged in-code |

### I. Other passes

| # | pass | rewrite | type |
|---|---|---|---|
| I1 | `SubQueryLift.lift` | in-lambda class subquery → `[0..1]` scalar-subquery relation | (c) |
| I2 | `RawGridSchema.stamp` | late-bound `Relation<*: Any[0..1]>` → probed real columns (LIMIT-0 metadata read) | (c), runtime-grounded |
| I3 | `DriverPkAppend.apply` | appends PK columns to the graph root form | (c) |
| I4 | `RelationalRootForm.apply` | un-builds a `TypedSerializeGraph` into a flat pk+leaf project (toSQLString surface only) | (c) |
| I5 | `JsonSourceFrame.sourceUrlFrame` | JSON source → `ClassSource` over a synthesized frame | (c) |
| I6 | `LiteralFolds` | static-bool fold / thunk unwrap | (b) |
| I7 | `CastNav` | `->cast(@Sub)` navigation → leaf source at the cast target | (b) |
| I8 | `GenericTypeReflection` | `.genericType().rawType` → string projection over the class extent | (c) |

---

## Item 2 — BEFORE-H vs AFTER-H ROOT TYPE DIFF

Probe: `HDiff.java` — `SpecCompiler.typeQueryBody` + `UserCallInliner.inlineBody` (BEFORE) vs
`new StoreResolver(ctx, specs).resolve(body, runtime)` (AFTER); root = last statement.
Column names, types, multiplicities and **order** all compared (relation types rendered
positionally). Model `big.pure`, runtime `s::RT`.

| # | query | BEFORE-H | AFTER-H | VERDICT |
|---|---|---|---|---|
| 1 | `class-root` | Person[*] <sub>TypedGetAll</sub> | Person[*] <sub>TypedSerializeGraph</sub> | SAME |
| 2 | `class-root-mapping` | Relation<Relation<fn:String[1], ag:Integer[1]>>[1] <sub>TypedProject</sub> | idem <sub>TypedProject</sub> | SAME |
| 3 | `filter-pushed` | Relation<Relation<fn:String[1]>>[1] | idem | SAME |
| 4 | `filter-after` | Relation<Relation<fn:String[1], ag:Integer[1]>>[1] <sub>TypedFilter</sub> | idem | SAME |
| 5 | `assoc-nav-tomany` | Relation<Relation<fn:String[1], ct:String[0..1]>>[1] | idem | SAME |
| 6 | `assoc-nav-toone` | Relation<Relation<st:String[1], pf:String[1]>>[1] | idem | SAME (but see **H2** — the `[1]` is a lie) |
| 7 | `nested-nav` | Relation<Relation<c:String[0..1]>>[1] | idem | SAME |
| 8 | `subclass-root` | Relation<Relation<fn:String[1], s:Float[1]>>[1] | idem | SAME (see **H3**) |
| 9 | `subclass-inherited` | Relation<Relation<a:Integer[1]>>[1] | idem | SAME |
| 10 | `union-dispatch` | Relation<Relation<ln:String[1]>>[1] | idem | SAME |
| 11 | `union-filter` | Relation<Relation<ln:String[1]>>[1] | idem | SAME |
| 12 | `aggregate` | Relation<Relation<ln:String[1], t:Integer[1]>>[1] <sub>TypedGroupBy</sub> | idem | SAME |
| 13 | `agg-count` | Integer[1] <sub>TypedNativeCall</sub> | Integer[1] | SAME |
| 14 | `agg-max` | Relation<Relation<mx:Integer[0..1]>>[1] | idem | SAME |
| 15 | `join-relation` | Relation<Relation<fn:String[1], c:String[1]>>[1] <sub>TypedJoin</sub> | idem | SAME |
| 16 | `temporal-latest` | Relation<Relation<n:String[1]>>[1] | **H-ERROR** MappingResolutionException | H-ERR (loud, correct: no INFINITY_DATE declared) |
| 17 | `temporal-date` | Relation<Relation<n:String[1]>>[1] | idem | SAME |
| 18 | `temporal-nav` | Relation<Relation<i:Integer[1], n:String[1]>>[1] | idem | SAME (see **H2**) |
| 19 | `temporal-nav-outer` | Relation<Relation<i:Integer[1], n:String[1]>>[1] | idem | SAME (see **H2** — repro'd NULL) |
| 20 | `proc-temporal` | Relation<Relation<c:String[1]>>[1] | idem | SAME |
| 21 | `bitemporal` | Relation<Relation<n:String[1]>>[1] | idem | SAME |
| 22 | `bitemporal-nav` | Relation<Relation<i:Integer[1], n:String[1]>>[1] | idem | SAME |
| 23 | `sort-limit` | Relation<Relation<fn:String[1]>>[1] <sub>TypedLimit</sub> | idem | SAME |
| 24 | `distinct` | Relation<Relation<ln:String[1]>>[1] <sub>TypedDistinct</sub> | idem | SAME |
| 25 | `exists` | Relation<Relation<fn:String[1]>>[1] | idem | SAME |
| 26 | `isEmpty` | Relation<Relation<fn:String[1]>>[1] | idem | SAME |
| 27 | `map-scalar` | **String[*]** <sub>TypedMap</sub> | **Relation<Relation<u_map__firstName:String[1]>>[*]** <sub>TypedProject</sub> | **DIFF → H6** |
| 28 | `graph-serialize` | **String[1]** <sub>TypedSerialize</sub> | **Person[*]** <sub>TypedSerializeGraph</sub> | **DIFF → H5** |
| 29 | `graph-nested` | **String[1]** <sub>TypedSerialize</sub> | **Person[*]** <sub>TypedSerializeGraph</sub> | **DIFF → H5** |
| 30 | `extend` | Relation<Relation<ag:Integer[1], d:Integer[1]>>[1] <sub>TypedExtend</sub> | idem | SAME |
| 31 | `raw-grid` | Relation<Relation<ID:Integer[1]>>[1] <sub>TypedSelect</sub> | idem | SAME |
| 32 | `concat` | Relation<Relation<fn:String[1]>>[1] <sub>TypedConcatenate</sub> | idem | SAME |
| 33 | `first` | Relation<Relation<x:String[1]>>[1] | idem | SAME |
| 34 | `sortBy-nav` | Relation<Relation<c:String[1], p:Integer[1]>>[1] <sub>TypedSort</sub> | idem | SAME |

`temporal-latest` error text: `%latest usage for temporal fetch of 'm::Prod' requires table 'PRODT' to specify a milestoning 'INFINITY_DATE'`.

### Column ORDER stress (separate run, `ord.txt`) — all SAME, order preserved exactly

| # | query | BEFORE-H = AFTER-H |
|---|---|---|
| 1 | `ord-proj-5` (deliberately unsorted names) | Relation<Relation<z:String[1], a:String[1], m:Integer[1], b:String[1], y:String[1]>>[1] |
| 2 | `ord-nav-mix` (nav column FIRST) | Relation<Relation<c1:String[0..1], c0:String[1], c2:String[0..1]>>[1] |
| 3 | `ord-groupby` (keys `fn, ln` declared after `ln, fn` in source) | Relation<Relation<fn:String[1], ln:String[1], s:Integer[1], c:Integer[1]>>[1] |
| 4 | `ord-extend` | Relation<Relation<b:String[1], a:String[1], z:String[1], y:String[1]>>[1] |
| 5 | `ord-rename` | Relation<Relation<q:String[1], a:String[1]>>[1] |
| 6 | `ord-select` (`~[c, a]` reversed) | Relation<Relation<c:Integer[1], a:String[1]>>[1] |
| 7 | `ord-concat` (arms with swapped column meanings) | Relation<Relation<a:String[1], b:String[1]>>[1] |
| 8 | `ord-join` | Relation<Relation<a:String[1], c:String[1], d:String[1]>>[1] |

Forward fidelity checked too — the rendered SQL's select list order matches the declared order, e.g.
`ord-select` → `SELECT t0.AGE_VAL AS c, t0.FIRST_NAME AS a`, and `[EXEC-COL]` order matches.
**H preserves relation column order in every case tested.**

---

## Item 4 — MILESTONING / TEMPORAL

* **Point access**: `m::Prod.all(%2020-06-01)` ⇒
  `WHERE t0.from_z <= DATE '2020-06-01' AND t0.thru_z > DATE '2020-06-01'`. The filter node is
  `TypedFilter(pipe, pred, pipe.info(), Stamp.TEMPORAL)` — **type copied (a)**, sound.
* **Date argument type**: taken verbatim from the literal. A `StrictDate` argument against a
  PROCESSING dimension whose columns are `TIMESTAMP` renders `t0.in_z <= DATE '2020-06-01'` and
  relies on the dialect's implicit cast; a `DateTime` argument renders `TIMESTAMP '...'`. H does not
  coerce, and does not reject the cross-grain comparison. Snapshot milestoning *does* coerce
  (`datePart(...)` stamped `STRICT_DATE[1]`, `TemporalFrame.java:1514-1536`) and rejects partial
  dates loudly, so the two dimensions are treated inconsistently — noted, not a demonstrated defect.
* **Implicit date injection**: there is no injected *parameter*; the date is the literal/expression
  the user supplied, threaded through `TemporalContext` and spelled into the filter or the join ON.
  For an outer-row date (`$o.prod($o.odate)`) it becomes a correlated ON conjunct:
  `... ON t0.PID = t1.ID AND t1.from_z IS NOT NULL AND t0.odate IS NOT NULL AND t1.from_z <= t0.odate AND ...`.
* **Generated date properties** (`$p.businessDate` / `$p.processingDate`): typed by recompute from
  the physical column (**H8**). Under a point context the read folds to the context literal
  (`SELECT ... DATE '2020-06-01' AS bd`); under `allVersions()` it reads the row's own validity
  start column.
* **`%latest`**: `TypedCLatestDate` is `LatestDate[1]`; H rewrites it to
  `thruCol == <INFINITY_DATE literal>` with the literal stamped `DATE_TIME[1]`
  (`TemporalFrame.java:1550-1568`) regardless of the column's actual grain. A table without a
  declared `INFINITY_DATE` fails **loudly** — no defaulted constant. `%latest` over a SNAPSHOT table
  is a loud `MappingResolutionException`. Verified sound (no silent fallback).
* **`->asOfJoin` over a class query**: not supported —
  `NotImplementedException: class query under TypedAsOfJoin is not resolvable yet (H2 vocabulary)`.
  Loud, correct. (Relation-space `asOfJoin` is not a Phase-H rewrite.)
* **Milestoned class access**: `m::Prod.all()` without a date is rejected loudly
  (`fetch of temporal class 'm::Prod' requires a milestoning date argument`, `StoreResolver.java:2666-2673`)
  — no silent whole-extent fetch. `allVersions()` correctly emits an unfiltered scan.
* **Bitemporal**: `m::BiP.all(%…T…, %…)` and `$o.bip(p, b)` both preserve their G types and compose
  both windows.
* **Weakness**: `TemporalContext.single` fills the **BUSINESS** slot for a `BITEMPORAL` strategy
  given one date, losing the source dimension — acknowledged in-code
  (`TemporalContext.java:56-63`: *"A dimension-TAGGED propagation is the honest future shape; this
  API loses the source dimension"*).
* **Cardinality**: temporal navigation inherits **H2** — see repro under H2.

---

## Item 5 — ASSOCIATION JOINS / CARDINALITY

* **to-one direction (`Address.person : Person[1]`)** — **BROKEN**, see **H2**. LEFT OUTER JOIN
  produces NULL for an unmatched key while the column claims `String[1]`.
  Same for the milestoned nav and the graph path.
* **to-many direction (`Person.addresses : Address[*]`)** — `$p.addresses.city` is typed
  `String[0..1]` already at **G** (a `[*]` narrowed to `[0..1]`), and H emits a row-multiplying
  LEFT JOIN, so each output row does hold at most one city. The per-cell `[0..1]` claim holds.
  Row count goes 3 → 4 for 3 persons / 4 addresses. That is Legend's documented project-explodes
  semantics and the root type (`Relation<…>[1]`) makes no row-count claim, so **no cardinality
  claim is broken here**. The `[*] → [0..1]` narrowing is a Phase-G information loss, not H's.
* **filter-position to-many** — correct: routed to a correlated `EXISTS` over a `SELECT DISTINCT`
  subquery, no explosion:
  ```
  query: m::Person.all()->filter(p|$p.addresses->exists(a|$a.city=='New York' || $a.city=='Boston'))->size()
  [PLAN] SELECT (SELECT COUNT(1) FROM T_PERSON AS t0
                 LEFT OUTER JOIN ( SELECT DISTINCT t1.PERSON_ID FROM T_ADDRESS AS t1
                                   WHERE t1.CITY = 'New York' OR t1.CITY = 'Boston' ) AS t2
                 ON t0.ID = t2.PERSON_ID WHERE t2.PERSON_ID IS NOT NULL) AS value
  [EXEC-ROW] Long(1) |
  ```
  Correct (only John has both).
* **sortBy over a to-many nav** — rejected at G (`multiplicity [*] is not compatible with result [1]`),
  so the sort-key explosion path is unreachable.
* **INNER joins** — the only INNER re-stamp is `FlattenOps.innerizeFlattenJoin` for the flatten
  row-set contract, and a missing join throws rather than silently staying LEFT
  (`FlattenOps.java:191-201`). No silent row-dropping found.
* **Join elision** — `Pipelines.java:404-406` cancels an un-demanded join and returns the left row
  type. Sound: cancellation is gated on nothing reading through the prefix.

---

## Item 6 — GRAPH / JSON EMISSION (`GraphEmission.java`)

What it stamps: `TypedSerializeGraph(pipeline, rowVar, leaves, nested, arrayWrap, …, g.info())`
where each leaf is a `TypedFuncCol(key, TypedLambda([rowVar], [body], fnType))` and
`fnType`'s result `Param` is `(resultType, body.info().multiplicity())`
(`GraphEmission.java:382-398`). `resultType` is the body's type except for the DATE-family
special case, where the **declared property type wins over the body's**
(`GraphEmission.java:382-388`) — a deliberate (b) recompute.

Wire results (probe outputs):

| case | JSON | verdict |
|---|---|---|
| flat scalars | `[{"firstName":"John","lastName":"Smith","age":30}, …]` | sound |
| nested to-many | `[{"firstName":"John","addresses":[{"city":"New York","street":"123 Main St"},{"city":"Boston",…}]}, …]` | sound — collection of objects survives, order stable |
| to-one **null** | `[…,{"street":"Orphan Way","person":null}]` for `person: Person[1]` | **UNSOUND — H2** |
| nullable scalar under `[1]` | `{"firstName":"Bob",…,"salary":null}` for `salary: Float[1]` | **UNSOUND — H3** |
| **subclass instance** | `m::Employee.all()` emits `{"firstName":…,"salary":…}` with **no type discriminator at all** | see below |
| **polymorphic `->subType(@X)`** | `NotImplementedException: graph ->subType(@m::Employee): carrier column 'stc_m__Employee___salary' is not on the row (non-union subtype mapping) — not built yet` | loud, not silent |

**Does a subclass instance emit its own type, and can the reader recover it?**
Not by default. `TypedSerializeGraph.typeKeyName` is null unless the query passes an
`alloyConfig(...)` with `includeType = true` (`GraphEmission.serializeTypeConfig`,
`GraphEmission.java:3166-3200`). Without it, the emitted object carries no `@type` key, so a reader
of `[{"firstName":"John","salary":100.5}]` cannot tell `m::Employee` from `m::Person`. The root
type on the plan is `m::Employee` (finding H5 shows it is `Class[*]` rather than `String[1]`), which
is the only channel carrying the identity — and it is the *static* root class, so a heterogeneous
extent would be indistinguishable. Genuine polymorphic emission (`->subType`) over a non-union
subtype mapping is not implemented and fails loudly.

---

## VERIFIED SOUND

Things I checked and found correct — coverage evidence.

1. **Relation column ORDER is preserved by H in all 8 order-stress cases** plus all 34 corpus
   queries, including `select(~[c, a])` reversal, groupBy key reordering, extend append,
   rename in place, concatenate arms, and joins. The rendered SQL select list and the
   `[EXEC-COL]` order match the declared order.
2. **Relation-space wrappers are structurally sound.** All 20 `structural(n, ctx)` arms
   (`StoreResolver.java:400-470`) go through `n.mapChildren(...)` → `withChildren`, which by
   construction cannot re-found `info` — verified by reading every variant's `withChildren`
   (all 70 keep `info` as a field, none recompute it).
3. **Every `withChildren` I read carries `orderKey`/`documentation`/`frameName`/`stamp` through by
   field**, so the traversal holes in H7 are *visit* holes, not data-loss holes:
   `TypedExtendAgg.java:39-40`, `TypedAggColSpec.java:26-28`, `TypedAggColSpecArray.java:35-36`,
   `TypedPivot.java:59`, `TypedExtendWindow.java:56` all pass `a.orderKey()` explicitly.
4. **No silent pass-through on unsupported shapes.** The ANCHORED switch's `default` throws a named
   `NotImplementedException`; so does the chain-collection `default`. Verified live for
   `TypedGraphFetch` (bare), `TypedAsOfJoin`, `TypedPivot`, class-typed whole-value reads, getAll in
   a lambda body, and getAll in an `if` branch — all loud, none silent.
5. **`%latest` without a declared `INFINITY_DATE` fails loudly** rather than defaulting a constant
   (`TemporalFrame.java:1544-1550`) — an explicit anti-fallback, verified by running it.
6. **Temporal class `.all()` without a date fails loudly** (`StoreResolver.java:2666-2673`).
7. **Filter-position to-many navigation does not explode rows** — EXISTS over `SELECT DISTINCT`,
   verified by counting (correct answer 1, not 2).
8. **Nested graph collections round-trip**: to-many children array-wrap, order stable, one object
   per child row.
9. **Downstream consumers of the two DIFF'd roots are correct**: `serialize(...)->length()` →
   `Integer[1]` / `Long(63)`; `map(...)->joinStrings(',')` → `String[1]` / `"John,Jane,Bob"`;
   `map(...)->size()` → `Integer[1]` / `Long(3)`. The H5/H6 damage is confined to the root-type
   surface.
10. **Union / Operation dispatch mapping** (`u::PersonA` over `PA1`/`PA2`) preserves its type across
    H both bare and under a filter.
11. **Subclass mapping** (`m::Employee` as its own PM on the same table, inherited + own properties)
    preserves its type across H.
12. **Raw grid** (`#>{s::DB.T_PERSON}#->select(~[ID])`) is `INERT` and passes through H unchanged.
13. **`StoreEscapees` positively catches the shapes it can see**, with a useful ancestry path —
    e.g. `[at root > TypedSerializeGraph > TypedLambda > TypedNativeCall > TypedNativeCall]`.
14. **`Anchors` memoization is identity-keyed and never iterated** (`Anchors.java:38-61`), so the
    memo cannot be order-sensitive — read in full, no defect.
15. **70 of 70 `TypedSpec` variants enumerated and their `children()`/`withChildren()` read.**

---

## NOT COVERED

* **`M2M` / `PureInstanceSetImplementation` mappings and `JsonSourceFrame`.** I could not construct
  a working M2M model within budget (the corpus fixture is relational-only) so
  `ClassSources.mixedUnionSource`, `JsonSourceFrame.sourceUrlFrame` and the chain-mapping dispatch
  were read but not executed. `ClassSources.java:183` and `:488` stamp union/M2M row columns from
  the *property declaration* (`p.multiplicity()`) with no physical cross-check, so I expect H3 to
  reproduce there, but I did not run it.
* **`CorrelatedSubselects.java` (2390 lines) and `AssociationJoins.java` (2086 lines) read
  selectively**, targeted at the join-emission and multiplicity-stamping sites. The 38 + 20
  `ExprType` construction sites in those two files were not each individually traced; I traced the
  three that build join result schemas (`Pipelines:425`, `Pipelines:560`, `StoreResolver:2038`)
  because they are the ones that decide cardinality.
* **`Substitution.java` (3167 lines): 59 `ExprType` sites**, of which I traced the binding
  substitution funnel and `milestoneColumnRead`. The embedded-property, subType-leaf and
  filtered-nav-leaf arms (`Substitution.java:1745-1775`) were read but not exercised.
* **`GraphEmission.java` (3429 lines): 66 `ExprType` sites.** I exercised the flat, nested-to-many,
  to-one-null, subclass and `->subType` paths; not exercised: `alloyConfig` type keys,
  `includeObjectReference` / `AsorRef`, `graphFetchChecked` defect envelopes, `removeNullKeys` /
  `removeEmptySets`, and the union witness/PK order-key machinery.
* **`->asOfJoin` over class queries** is unimplemented, so its typing could not be tested.
* **Query-level reachability of the H7 walker holes.** Proven at the `resolve()` API level;
  I could not find a surface syntax that parks a getAll in one of the 7 slots (see H7's
  reachability note). A model with a milestoned qualified property inside a graph tree whose date
  argument is itself a class subquery is the most likely candidate and was not built.
* **`SyntheticHeads.java` (1263), `InnerDemand.java` (721), `NavMaterializer.java` (796)** read for
  their traversal and demand rules, not line-by-line for type construction.
* **Dialect/JDBC layers (J/K).** Noted in passing but out of scope: `groupBy` `SUM` over an
  `Integer[1]` column decodes to `BigInteger` while `COUNT` over the same declared type decodes to
  `Long` — two Java runtime classes for one declared Pure type. Belongs to a J/K auditor.
