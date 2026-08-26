# A28 — Phase-F element-compiler extras (equality keys, layouts, temporal, constraints,
# derived props, synth FQNs, model integrity, store column types)

Scope: `compiler/element/{EqualityKeys, ClassLayouts, Temporal, MilestoningStrategy,
TypedConstraint, TypedClass, TypedEnum, TypedNominal, TypedParameter, TypedElement,
TypedFunction, Property, StoreCompiler, FunctionCompiler, PureModelContext, ModelIntegrity}`,
`compiler/{DerivedProps, ModelBuilder, SymbolTable, SynthFqn}`,
`lowering/{LayoutTypes, InstanceEquality}`, `exec/InstanceIds`.

All repros below were RUN. Harness: `/home/user/probe/probe.sh` (model+query -> G type, SQL,
exec rows with runtime Java classes) and `/home/user/probe/jrun.sh` for direct API probes.
Scratch models: `/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a28/`.

---

## FINDINGS

### [UNSOUND] Instance equality erases the CLASS on the ordinary execute path: two different classes with identical property sets compare TRUE

`InstanceEquality` (the arm that implements the engine's exact-classifier rule) is
**opt-in** and only armed on the assert-verdict lane:

```
Lowerer.java:2944-2946
    case TypedNativeCall n when instanceKeysOf != null
            && InstanceEquality.claims(n) -> { ... }
StatementExecutor.java:2320-2323
    if (identity) {
        lowerer = lowerer.withInstanceIds(env.instanceIds()::idOf,
                t2 -> EqualityKeys.resolve(ctx, t2));
    }
```
`identity` is true only for `rider != null || identityLane` (StatementExecutor.java:2594),
i.e. the canon-rider / assert-condition path. Every ordinary `Compiler.execute` query falls
through to `Scalars.lower`, which emits a **raw SQL struct comparison** with no classifier tag.

Model (`eq_model.pure`): `model::A{x:Integer[1]; y:String[1]}`,
`model::B{x:Integer[1]; y:String[1]}`, `model::Sup{x:Integer[1]}`, `model::Sub extends Sup{}`.

Repro + actual output (probe.sh, no runtime needed):

```
### ^model::A(x=1, y='p') == ^model::B(x=1, y='p')
[PLAN] SELECT {'x': 1, 'y': 'p'} = {'x': 1, 'y': 'p'} AS value
[EXEC-ROW] Boolean(true) |

### ^model::Sub(x=1) == ^model::Sup(x=1)
[PLAN] SELECT {'x': 1} = {'x': 1} AS value
[EXEC-ROW] Boolean(true) |

### eq(^model::A(x=1, y='p'), ^model::B(x=1, y='p'))
[PLAN] SELECT {'x': 1, 'y': 'p'} = {'x': 1, 'y': 'p'} AS value
[EXEC-ROW] Boolean(true) |

### [^model::A(x=1,y='p')]->contains(^model::B(x=1,y='p'))
[EXEC-ROW] Boolean(true) |
```

`InstanceEquality.java:115-117` is the code that WOULD have folded these to FALSE:
```java
if (!lf.equals(rf)) {
    return new SqlExpr.BoolLit(false);
}
```
…and `InstanceEquality.java:143-147` the same for `contains`/`in`. Neither runs on the
user-facing path. Proof that the two lanes disagree (direct `Lowerer` probe, `IdLane.java`):

```
### ^model::A(x=1, y='p') == ^model::B(x=1, y='p')
  IDLANE SQL: SELECT FALSE AS value
  PLAIN  SQL: SELECT {'x': 1, 'y': 'p'} = {'x': 1, 'y': 'p'} AS value
### ^model::Sub(x=1) == ^model::Sup(x=1)
  IDLANE SQL: SELECT FALSE AS value
  PLAIN  SQL: SELECT {'x': 1} = {'x': 1} AS value
### [^model::A(x=1,y='p')]->contains(^model::B(x=1,y='p'))
  IDLANE SQL: SELECT FALSE AS value
  PLAIN  SQL: SELECT coalesce(list_contains([{'x':1,'y':'p'}], {'x':1,'y':'p'}), FALSE) AS value
```

**Why it matters**: the type-erasure unsoundness the audit asked to prove. Class identity is a
static fact the compiler holds and throws away at lowering; a `model::A` and a `model::B` with
coincidentally matching layouts are indistinguishable to `==`, `eq`, `contains` and `in`. It is
also an INCONSISTENCY: the same expression answers `true` under `Compiler.execute` and `false`
under the assert-verdict lane.

### [UNSOUND] `<<equality.Key>>` is ignored on the ordinary execute path — keyed classes compare NON-key properties too

`EqualityKeys.resolve` correctly computes the key tree (verified exhaustively, see VERIFIED
SOUND), but only `InstanceEquality`/`CanonicalRenderSql` consume it, and only in the identity
lane. On the ordinary path the whole struct is compared.

Model (`keys_model.pure`): `Class model::K1 { <<equality.Key>> id: Integer[1]; note: String[1]; }`
plus an identical `model::K2`.

```
### ^model::K1(id=1, note='a') == ^model::K1(id=1, note='b')
[PLAN] SELECT {'id': 1, 'note': 'a'} = {'id': 1, 'note': 'b'} AS value
[EXEC-ROW] Boolean(false) |          <-- engine rule: TRUE (keys equal)

### ^model::K1(id=1, note='a') == ^model::K2(id=1, note='a')
[PLAN] SELECT {'id': 1, 'note': 'a'} = {'id': 1, 'note': 'a'} AS value
[EXEC-ROW] Boolean(true) |           <-- engine rule: FALSE (classifier mismatch)
```
Identity lane, same two expressions, for contrast:
```
  IDLANE SQL: ... json_object('_type','model::K1','id', ... 'id') ...   (key-only canon)
  IDLANE SQL: SELECT FALSE AS value
```
Both directions are wrong on the user path, in opposite ways.

### [UNSOUND] A derived (qualified) property on a `[0..1]` receiver is stamped `[1]` and comes back NULL; a receiver-independent body MANUFACTURES a value

Confirms and extends the orchestrator's report. Mechanism, in the Typer (both the zero-arg and
the parameterized arm), is an unguarded `trustOne` wrap:

```
Typer.java:2795-2799   (zero-arg derived read)
    return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(),
            List.of(exactlyOne ? ap.receiver()
                    : new AppliedFunction(Pure.Lite.TRUST_ONE, List.of(ap.receiver())))), env);
Typer.java:534-546     (parameterized qualifier)
    if (!(recv.info().multiplicity() instanceof Multiplicity.Bounded rb1 && rb1.lower() == 1)) {
        qargs.set(0, new AppliedFunction(Pure.Lite.TRUST_ONE, List.of(qargs.get(0))));
    }
```
`trustOne` **types** `T[*] -> T[1]` (Pure.java:1164) and **lowers to identity with no guard**
(`Scalars.java:484-490`: `RULES.put(f, (n, args) -> args.get(0))`). So the `[1]` stamp is
minted and SQL null-propagates through the inlined body.

RELATIONAL repro (real store read, not a synthetic constructor) — `dpr_model.pure`:
`Emp{name:String[1]; dept:Dept[0..1]}`, `Dept{dname:String[1]; hc:Integer[1];
label(){$this.dname}:String[1]; konst(){99}:Integer[1]}`, rows `('has-dept',10)`, `('no-dept',NULL)`.

```
### dpr::Emp.all()->project(~[n:e|$e.name, l:e|$e.dept.label(), k:e|$e.dept.konst(), d:e|$e.dept.dname])
[G] type=Relation<(n:String[1], l:String[1], k:Integer[1], d:String[0..1])>
[EXEC-COL] l : String [STRING] mult=[1]
[EXEC-COL] k : Integer [INTEGER] mult=[1]
[EXEC-COL] d : String [STRING] mult=[0..1]
[EXEC-ROW] String(has-dept) | String(Sales) | Integer(99) | String(Sales) |
[EXEC-ROW] String(no-dept)  | null         | Integer(99) | null         |
```

**Full extent, mapped** (constructor form, `dp_model.pure`):
| receiver | member kind | G stamp | runtime value |
|---|---|---|---|
| `[0..1]` empty | STORED `.n` | `Integer[0..1]` | `null` — SOUND |
| `[0..1]` empty | derived `wider()` (reads `$this.n`) | `Number[1]` | `null` — **UNSOUND** |
| `[0..1]` empty | derived `sname()` (String) | `String[1]` | `null` — **UNSOUND** |
| `[0..1]` empty | derived `isBig()` (Boolean) | `Boolean[1]` | `null` — **UNSOUND** |
| `[0..1]` empty | derived with params `withParam(5)` | `Integer[1]` | `null` — **UNSOUND** |
| `[0..1]` empty | derived `konst(){42}` | `Integer[1]` | `Integer(42)` — **value manufactured from an absent receiver** |
| `[*]` empty | derived `wider()` | `Number[*]` | correct auto-map |

The manufactured-value case is worse than the stamp violation:
```
### ^dp::Holder(child=[]).child.konst()->isEmpty()
[PLAN] SELECT 42 IS NULL AS value
[EXEC-ROW] Boolean(false) |
```
Pure yields empty for a property read off an empty receiver; here `isEmpty()` answers `false`.

### [UNSOUND] Milestoning struct members are stamped `DateTime[0..1]` but decode as `StrictDate` when the physical column is `DATE`

`Temporal.java:39-51` types `from/thru/in/out/snapshotDate` as `Type.Primitive.DATE_TIME`
unconditionally ("DATE_TIME, not abstract Date: the wire keeps the physical precision").
`StoreCompiler.java:198` maps a `DATE` column to `Type.Primitive.STRICT_DATE`.
`StrictDate` is **not** a subtype of `DateTime` (probed: `ctx.isSubtype(StrictDate, DateTime) == false`;
both are subtypes of `Date`).

Model `ms_model.pure`: `Class <<temporal.businesstemporal>> ms::Prod`, table
`ProdT( milestoning(business(BUS_FROM=from_z, BUS_THRU=thru_z)) … from_z DATE, thru_z DATE )`.

```
### ms::Prod.all(%2015-08-20)->project(~[n:p|$p.name, f:p|$p.milestoning.from, t:p|$p.milestoning.thru])
[G] type=Relation<(n:String[1], f:DateTime[0..1], t:DateTime[0..1])>
[PLAN] SELECT t0.name AS n, t0.from_z AS f, t0.thru_z AS t
[EXEC-COL] f : DateTime [DATE_TIME] mult=[0..1]
[EXEC-COL] t : DateTime [DATE_TIME] mult=[0..1]
[EXEC-ROW] String(P1) | StrictDate(2015-01-01) | StrictDate(2016-01-01) |
[EXEC-ROW] String(P2) | StrictDate(2015-06-01) | StrictDate(9999-12-31) |
```
A `TIMESTAMP`-declared milestone column DOES decode as `DateWithSecond`, so the stamp is right
for exactly one of the two legal physical spellings:
```
### ms::Bi.all(%2015-08-20, %2015-08-20)->project(~[n:p|$p.name, f:p|$p.milestoning.from, i:p|$p.milestoning.in])
[EXEC-ROW] String(B1) | StrictDate(2015-01-01) | DateWithSecond(2015-01-01T00:00:00+0000) |
```

Other temporal type facts checked (exhaustive over the 3 strategies x 8 member names, via a
direct `Temporal.generatedMember` probe):
- `businessDate` / `processingDate` -> `Date[1]`; runtime value is a `StrictDate` when the
  query date is `%2015-08-20`. `[1]` is honest (it is the query's own date) and `StrictDate <: Date`,
  so this one is SOUND (information loss only: the abstract `Date` stamp is wider than the value).
- `milestoning` -> `[0..1]`, class `BusinessDateMilestoning` or `ProcessingDateMilestoning`.

### [UNSOUND] Two supertypes declaring the same property with different MULTIPLICITY: `findProperty` says `[1]`, the layout says `[*]`; the runtime value is a list under an `Integer[1]` stamp

`ClassLayouts.java:126-131` compares only the TYPE when detecting an inherited conflict:
```java
Type.Column prev = out.put(stored.name(), col);   // keeps the first position
if (prev != null && isSuper && !prev.type().equals(col.type())) { throw new IllegalStateException(...); }
```
Multiplicity is never compared, and `out.put` lets the LAST super win. `PureModelContext.findProperty`
(PureModelContext.java:196-201) walks supers in declaration order and returns the FIRST hit.
The two disagree.

Model: `Class model::SupM1 { w: Integer[1]; }  Class model::SupM2 { w: Integer[*]; }
Class model::DiamondMult extends model::SupM1, model::SupM2 { }`

```
### layout probe
model::DiamondMult layout = [Column[name=w, type=INTEGER, multiplicity=Bounded[lower=0, upper=null]]]
                  sqlTypeOf = Struct[fields=[Field[name=w, type=Array[element=BIGINT]]]]

### ^model::DiamondMult(w=1)->map(d|$d.w)
[G] type=Integer mult=[1]
[PLAN] SELECT list_extract(list_transform([{'w': [1]}], d -> d.w), 1) AS value
[EXEC-COL] value : Integer [INTEGER] mult=null
[EXEC-ROW] ArrayList([1]) |          <-- an ArrayList under an Integer[1] stamp
```
`ModelIntegrity` accepts the model; nothing checks inherited-property compatibility.

### [UNSOUND] A subclass may redeclare an inherited property with an INCOMPATIBLE type; a supertype-stamped read then gets the subclass's value type

`ModelIntegrity.checkClass` only rejects duplicate names *within one class body*
(ModelIntegrity.java:105-111). Nothing compares a redeclaration against the inherited
declaration; `ClassLayouts.collect`'s conflict throw is gated on `isSuper` so a subclass
redeclaration silently overwrites (ClassLayouts.java:126).

```
Class model::Sup2 { p: Integer[1]; }
Class model::SubRedeclDiffType extends model::Sup2 { p: String[1]; }

### ^model::SubRedeclDiffType(p='hello')->cast(@model::Sup2)->map(s|$s.p)
[G] type=Integer mult=[1]
[PLAN] SELECT list_extract(list_transform([{'p': 'hello'}], s -> s.p), 1) AS value
[EXEC-COL] value : Integer [INTEGER] mult=null
[EXEC-ROW] String(hello) |           <-- a String under an Integer[1] stamp
```
(Layout probe confirms the layout took `STRING`: `layout(plain) = [Column[name=p, type=STRING, …]]`.)

### [UNSOUND] `SqlType.Struct.Field` has no nullability and no bound: a `[1]` class property is representationally identical to `[0..1]`, and `[2..5]`/`[1..*]` to `[*]`

`LayoutTypes.sqlTypeOfUnguarded` (LayoutTypes.java:70-78) builds
`new SqlType.Struct.Field(c.name(), many ? new SqlType.Array(ft) : ft)`.
`SqlType.Struct.Field` is `record Field(String name, SqlType type)` (SqlType.java:57) — no
nullability flag, no cardinality bound. Probe over `Class model::Mults { req: Integer[1];
opt: Integer[0..1]; oneMany: Integer[1..*]; many: Integer[*]; bounded: Integer[2..5]; }`:

```
layout(plain) = [req INTEGER[1], opt INTEGER[0..1], oneMany INTEGER[1..*],
                 many INTEGER[*], bounded INTEGER[2..5]]
sqlTypeOf     = Struct[fields=[Field[req,BIGINT], Field[opt,BIGINT],
                 Field[oneMany,Array[BIGINT]], Field[many,Array[BIGINT]],
                 Field[bounded,Array[BIGINT]]]]
```
Five distinct Pure multiplicities collapse to two SQL shapes. Runtime witnesses that the
`[1]` claim is then violated:
```
### ^mi::Req()                     (Class mi::Req { r: Integer[1]; o: String[0..1]; })
[PLAN] SELECT {'r': NULL, 'o': NULL} AS value
[EXEC-ROW] LinkedHashMap({r=null, o=null}) |
### ^mi::Req().r
[G] type=Integer mult=[1]
[EXEC-ROW] null |                    <-- Integer[1] stamp, null value

### ^mi::S(n=1).me                 (Class mi::S { me: mi::S[1]; n: Integer[1]; })
[G] type=mi::S mult=[1]
[PLAN] SELECT NULL AS value
[EXEC-ROW] null |
### ^mi::S(n=1).me.n
[G] type=Integer mult=[1]
[EXEC-ROW] null |
```
Note the asymmetry inside `NewChecker`: supplying a WRONG-cardinality value is rejected
(`^model::Mults(..., bounded=[9])` -> "declares multiplicity Bounded[2,5] but the value has
Bounded[1,1]"), but OMITTING a required `[1]` property is accepted and lowers to NULL.

### [CRASH/ICE] `DECIMAL(p,s)` / `NUMERIC(p,s)` with `s > p` escapes Phase F as a raw `IllegalArgumentException` (confirms the other auditor's report, and localizes it)

`Compiler.compileModel` SUCCEEDS — `ModelIntegrity` never resolves column types. The raw
exception escapes from the *lookup*:

```
Database dt::DB ( Table T ( id INTEGER PRIMARY KEY, c DECIMAL(2,5) ) )

compileModel OK: PureModelContext
java.lang.IllegalArgumentException: scale must be in [0, precision], got scale=5, precision=2
	at com.legend.compiler.element.type.Type$PrecisionDecimal.<init>(Type.java:165)
	at com.legend.compiler.element.StoreCompiler.columnType(StoreCompiler.java:192)
	at com.legend.compiler.element.StoreCompiler.tableSchema(StoreCompiler.java:177)
	at com.legend.compiler.element.StoreCompiler.resolveTable(StoreCompiler.java:32)
	at com.legend.compiler.element.PureModelContext.resolveTableWithIncludes(PureModelContext.java:436)
	at com.legend.compiler.element.PureModelContext.findTable(PureModelContext.java:350)
```
`NUMERIC(2,5)` is identical (StoreCompiler.java:193). Not a `ModelException`, so it surfaces
as an internal error, and it is LAZY: an unqueried table with a bad decimal ships silently.

### [CRASH/ICE] `BINARY(n)` / `VARBINARY(n)` type-check to `Byte` and then blow up at the lowering boundary

`StoreCompiler.java:196-197` maps both to `Type.Primitive.BYTE`. `PureSql.java:101` has no SQL
type for `BYTE`. Phase G happily stamps the column:
```
BINARY(10)     G: Relation<(c:Byte[0..1])>
               [PLAN-ERROR] java.lang.IllegalStateException: no SQL type for Pure primitive BYTE at the lowering boundary
VARBINARY(10)  (identical)
```
A raw `IllegalStateException`, on a DDL spelling the parser explicitly accepts.

### [CRASH/ICE] The navigate-slot flat-name convention (`slot + "_" + col`) collides with a real column and throws `IllegalArgumentException: duplicate column`

`Lowerer.java:1843` + `Lowerer.java:1860-1866` (`navSlotPrefix`/`navFlatColumn`) mint flat names as `slot_COL`; `Executor.java:920` reconstructs
them the same way. Neither checks for a pre-existing column of that name. Phase G types the
query fine (the slot is a nested row-struct); Phase I dies.

```
Database store::D ( Table T1 ( A_B INTEGER PRIMARY KEY, K INTEGER NOT NULL )
                    Table T2 ( B INTEGER PRIMARY KEY, Z VARCHAR(10) NOT NULL ) )

### #>{store::D.T1}#->navigate(~A: #>{store::D.T2}#, {r,t| $r.K == $t.B})
[G] type=Relation<(A_B:Integer[1], K:Integer[1], A:(B:Integer[1], Z:String[1])[1])> mult=[1]
[PLAN-ERROR] java.lang.IllegalArgumentException: duplicate column 'A_B' in relation type
	at com.legend.compiler.element.type.Type$RelationType.<init>(Type.java:533)
	at com.legend.lowering.Lowerer.navigate(Lowerer.java:1846)
	at com.legend.Compiler.plan(Compiler.java:333)
```
(For completeness: **class layouts do NOT flatten** — `ClassLayouts` keeps a nested class as a
nested struct, verified with `Class cl::Outer2 { a_b: String[1]; a: cl::Inner[1]; }` ->
`Struct[Field[a_b,VARCHAR], Field[a, Struct[Field[b,BIGINT]]]]`. The mangling collision lives
in the relation/navigate slot path only.)

### [CRASH/ICE] A class with no stored properties in value position throws `IllegalStateException`

`ClassLayouts.layout` returns `Optional.empty()` for a property-less class (ClassLayouts.java:104),
and `Lowerer.java:2679` turns that into an internal error:
```
### ^model::Empty() == ^model::Empty()      (Class model::Empty { })
[PLAN-ERROR] java.lang.IllegalStateException: class value ^model::Empty(?) has no canonical layout
             — the class declares no stored properties (or no model rides this lowering)
```
Same for a class carrying only derived properties. Note the two owners disagree about what such
a class IS: `ClassLayouts.layoutOf` says "no layout" (loud) while `LayoutTypes.sqlTypeOf` says
`SqlType.Scalar.JSON` (silent) — probe:
```
### model::NoProps      layout = Optional.empty   sqlTypeOf = JSON
### model::OnlyDerived  layout = Optional.empty   sqlTypeOf = JSON
```

### [CRASH/ICE] Two supertypes declaring the same property with different TYPES: `IllegalStateException` at Phase I, and the message names the wrong class

Model compiles clean (`ModelIntegrity` has no such check); the wall is `ClassLayouts.java:127-131`:
```
Class model::SupX { v: Integer[1]; }  Class model::SupY { v: String[1]; }
Class model::Diamond extends model::SupX, model::SupY { }

### ^model::Diamond(v=1)
[G] type=model::Diamond mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: class 'model::SupY' inherits conflicting
             declarations of property 'v' (Integer vs String)
```
The message blames `model::SupY` (which inherits nothing) instead of `model::Diamond`; the
throw uses `cls.qualifiedName()` of the *currently-walked super*, not the demanding class.

### [CRASH/ICE] Cross-axis milestoning members type-check and then hit a "resolver bug" ICE

`Temporal.generatedMember` (Temporal.java:39-51) accepts the UNION `{from,thru,in,out,snapshotDate}`
for BOTH `BusinessDateMilestoning` and `ProcessingDateMilestoning`. Exhaustive probe:
```
--- meta::pure::milestoning::BusinessDateMilestoning
    from -> DateTime[0..1]   thru -> DateTime[0..1]   in -> DateTime[0..1]
    out  -> DateTime[0..1]   snapshotDate -> DateTime[0..1]
--- meta::pure::milestoning::ProcessingDateMilestoning   (identical five)
```
So `.in` on a business-only class is accepted by Phase G and dies in the resolver:
```
### ms::Prod.all(%2015-08-20)->project(~[n:p|$p.name, i:p|$p.milestoning.in, o:p|$p.milestoning.out, s:p|$p.milestoning.snapshotDate])
[G] type=Relation<(n:String[1], i:DateTime[0..1], o:DateTime[0..1], s:DateTime[0..1])> mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: resolver bug: undemanded navigation 'milestoning.in'
             — the demand scan and the rewrite disagreed
```
(`Substitution.java:2049`.) `$p.processingDate` on the same class IS correctly refused
("class ms::Prod has no property 'processingDate'"), so the guard exists for the generated
date properties and is simply missing for the milestoning struct's members.

### [CRASH/ICE] Property names differing only by case reach the database as a duplicate struct key

`ModelIntegrity.checkClass` uses an exact-match `HashSet` (ModelIntegrity.java:104-111), so
`abc` and `ABC` both survive. DuckDB struct keys are case-insensitive:
```
Class mi::D { abc: Integer[1]; ABC: String[1]; }
### ^mi::D(abc=1, ABC='z')
[PLAN] SELECT {'abc': 1, 'ABC': 'z'} AS value
[EXEC-ERROR] java.sql.SQLException: Binder Error: Duplicate struct entry name "ABC"
```

### [CRASH] Comparing an instance to a primitive lowers to invalid SQL instead of `false`

`equal(Any[*], Any[*])` type-checks, and the lowering emits `struct = scalar`:
```
### ^model::A(x=1, y='p') == 1
[PLAN] SELECT {'x': 1, 'y': 'p'} = 1 AS value
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Unimplemented type for cast (INTEGER -> STRUCT(x INTEGER, y VARCHAR))
### ^model::A(x=1, y='p') == 'p'
[EXEC-ERROR] java.sql.SQLException: Type VARCHAR with value 'p' can't be cast to the destination type STRUCT(x INTEGER, y VARCHAR)
```
Pure's answer is `false`. `InstanceEquality.claims` (InstanceEquality.java:51-71) requires BOTH
operands to be instances, so even the identity lane declines and falls through to the same
generic rule.

Related, same family: an instance collection whose LUB is a supertype also produces a raw
`SQLException` — `[^model::SubRedeclDiffType(p='hello'), ^model::Sup2(p=1)]->map(s|$s.p)` ->
`Binder Error: Cannot deduce template type 'T' in function: 'list_value(T, [T...]) -> T[]'`.

### [SILENT FALLBACK] A user-declared function CAN carry the `$` sigil (quoted identifiers) and SILENTLY SHADOWS a synthesized derived-property body

`SynthFqn`'s javadoc (SynthFqn.java:18-20) claims: *"The `$` sigil is non-user-writable in a
Pure identifier, so a synth FQN can never collide with a user-authored function name in the
shared `findFunction` lookup slot."* — **false.** `TokenStreamCursor.parseIdentifier` (line
633-641) admits a QUOTED identifier whose name is the unquoted text.

```
Class syn::A { n: Integer[1]; x() { $this.n } : Integer[1]; }
function syn::'A$prop$x'(this: syn::A[1]): Integer[1] { 999 }

### ^syn::A(n=1).x()
[PLAN] SELECT 999 AS value
[EXEC-ROW] Integer(999) |            <-- the user function's body, not the property's
```
Same result with the function declared BEFORE the class. Direct `findFunction` probe shows both
definitions living in the one slot with identical typed signatures:
```
### findFunction(syn::A$prop$x)
   syn::A$prop$x params=[this: syn::A[1]] ret=INTEGER[1] body=[CInteger[value=999]]
   syn::A$prop$x params=[this: syn::A[1]] ret=INTEGER[1] body=[AppliedProperty($this, n)]
### findProperty(syn::A, x) = Derived[..., bodyFunctionFqn=syn::A$prop$x]
```
The `$constraint$` slot has the same hole but ICEs instead of shadowing:
```
Class syn::B [ chk: $this.n > 0 ] { n: Integer[1]; }
function syn::'B$constraint$chk'(this: syn::B[1]): Boolean[1] { true }
### syn::B.all()->graphFetchChecked(#{syn::B{n}}#)->serialize(#{syn::B{n}}#)
[PLAN-ERROR] java.lang.IllegalStateException: resolver bug: synthesized function
             'syn::B$constraint$chk' has 2 overloads; synthesized FQNs are unique
```
(`ClassSources.java:107` / `:632`.) A class whose own NAME contains `$` fails differently, with
a raw `IllegalArgumentException` from `SynthFqn.check` (SynthFqn.java:111-114):
```
Class syn::'B$prop$q' { n: Integer[1]; q() { $this.n } : Integer[1]; }
[G-ERROR] java.lang.IllegalArgumentException: synth-FQN ownerFqn contains the reserved '$' sigil: syn::B$prop$q
```

### [SILENT FALLBACK] `ModelIntegrity.checkDuplicateSignatures` is defeated by SOURCE POSITIONS — two identical user function definitions compile and the first silently wins

`Function.signatureKey()` (Function.java:60-64) builds the key by string-appending the parameter
`TypeExpression`:
```java
StringBuilder key = new StringBuilder(qualifiedName()).append('(');
for (var p : parameters()) { key.append(p.type()).append(':').append(p.multiplicity()).append(','); }
```
`TypeExpression.NameRef` overrides `equals`/`hashCode` to ignore `pos` (TypeExpression.java:83-91)
but its record `toString()` still prints it. `NameResolver` mints a fresh, position-FREE `NameRef`
only for names it actually rewrites; a FULLY QUALIFIED parameter type keeps its source position.

```
Class dup::T { n: Integer[1]; }
function dup::g(a: dup::T[1]): Integer[1] { 1 }
function dup::g(a: dup::T[1]): Integer[1] { 2 }

### dup::g(^dup::T(n=1))
[PLAN] SELECT 1 AS value
[EXEC-ROW] Integer(1) |              <-- accepted; first definition silently wins

### signature keys
KEY: dup::g(NameRef[name=dup::T, pos=SourceInfo[…startLine=4…]]:[1],)
KEY: dup::g(NameRef[name=dup::T, pos=SourceInfo[…startLine=9…]]:[1],)
```
With SIMPLE type names the check DOES fire (`function dup::f(a: Integer[1])` twice ->
`ModelException: function 'dup::f' is defined more than once with the same signature`), because
resolution normalized both to a pos-free `NameRef`. This is exactly the hole that let the
`$prop$` shadowing above through:
```
KEY: syn::A$prop$x(NameRef[name=syn::A, pos=SourceInfo[…startLine=8…]]:[1],)     (user)
KEY: syn::A$prop$x(NameRef[name=syn::A, pos=null]:[1],)                          (synthesized)
```
ModelIntegrity.java:140-144 states the check exists precisely so that "silently letting one win
answers calls with an arbitrary body" cannot happen. It does happen.

### [SILENT FALLBACK] Class constraints are NEVER type-checked at Phase F; a constraint referencing a nonexistent property produces a fully queryable context

`ModelIntegrity.checkClass` does `functions.requireShape(fqn, FunctionCompiler::returnsBooleanOne,
site, "returning Boolean[1]")` (ModelIntegrity.java:135-136). For the INLINE (sugar) form this is
**vacuous**: `ModelNormalizer.synthConstraintFunction` (ModelNormalizer.java:307-322) hard-codes
```java
new TypeExpression.NameRef(Pure.BOOLEAN.qualifiedName()),
Multiplicity.Concrete.PURE_ONE,
List.of(c.expression()),
```
i.e. the DECLARED return type is always `Boolean[1]` no matter what the body is. The shape
predicate reads only the declaration, so it can never fail.

Matrix (model compiles + runs in every row; the only errors appear when `graphFetchChecked`
demands the predicate body):

| constraint | `compileModel` | `.all()->project(...)` | `graphFetch/serialize` | `graphFetchChecked` |
|---|---|---|---|---|
| `[validAge: $this.age > 0]` (violated by data) | OK | rows returned | rows returned, no defect | defect emitted, correct |
| `[bad: $this.nope > 0]` (no such property) | **OK** | **rows returned** | **rows returned** | `TypeInferenceException: in function 'con::P$constraint$bad': class con::P has no property 'nope'` |
| `[nb: $this.age]` (returns Integer) | **OK** | **rows returned** | **rows returned** | `… declares return type Boolean but body returns Integer` |
| `[ob: $this.flag]` (`Boolean[0..1]`) | **OK** | **rows returned** | **rows returned** | `… multiplicity [0..1] is not compatible with [1]` |

Actual defect output for the violated case (proving the evaluation IS in the DB, not host-side —
`CheckedEnvelope.wrap` emits `CASE WHEN NOT <pred> THEN json_object(...)`):
```
[EXEC-JSON] [{"defects":[],"value":{"name":"ok","age":30}},
             {"defects":[{"id":"validAge","externalId":null,
                          "message":"Constraint :[validAge] violated in the Class P",
                          "enforcementLevel":"Error","ruleType":"ClassConstraint",
                          "ruleDefinerPath":"con::P","path":[]}],
              "value":{"name":"bad","age":-5}}]
```
**Why it matters**: `ModelIntegrity`'s own javadoc (lines 18-32) promises *"every reference a
model element makes … checked ONCE, whole-model, at construction. An invalid model never becomes
a queryable context, even if nothing ever demands the bad element."* For constraint bodies that
is false: a model with a constraint over a nonexistent property is queryable, and only one
specific query form ever surfaces the error.

Same laziness applies to derived-property bodies (`[nb]`-style errors only when the property is
used): `multNarrow(){[1,2,3]}:Integer[1]` and `optDeclOne(){$this.opt}:Integer[1]` both compile
and only fail on use.

### [SILENT FALLBACK] `ModelIntegrity`'s association check accepts an association END typed as an ENUM or a PRIMITIVE, and lets duplicate end names shadow

ModelIntegrity.java:62-65 only calls `classifier.classify(...)` on the two target names — a KIND
check is never made:
```java
model.associations().forEach(a -> withElement(a.qualifiedName(), () -> {
    classifier.classify(a.property1().targetClass(), List.of());
    classifier.classify(a.property2().targetClass(), List.of());
}, wallSink));
```
```
Association mi::AS  { toC:  mi::C[1]; toE: mi::E[*]; }     (mi::E is an Enum)
Association mi::AS2 { toC2: mi::C[1]; toS: String[*]; }
Association mi::AS3 { p: mi::C3[1];   p:  mi::C3[*]; }

compileModel OK
findProperty(mi::C , toE) = Stored[name=toE, type=EnumType[fqn=mi::E], multiplicity=[*]]
findProperty(mi::C , toS) = Stored[name=toS, type=STRING,             multiplicity=[*]]
findProperty(mi::C3, p)   = Stored[name=p,   type=ClassType[mi::C3],  multiplicity=[*]]   <- second end won
```

### [SILENT FALLBACK / INCONSISTENCY] A class may `extend` an ENUM; `ModelContext.isSubtype` then says the class IS a subtype of the enum, while the Typer refuses the same relation

```
Enum mi::E2 { A, B }
Class mi::X extends mi::E2 { n: Integer[1]; }

compileModel OK
findClass(mi::X)  = TypedClass[..., superClassFqns=[mi::E2], ...]
findClass(mi::E2) = Optional.empty            <- the super does not resolve as a class
isSubtype("mi::X","mi::E2") = true            <- but the lattice says yes

### mi::takesEnum(^mi::X(n=1))     (function mi::takesEnum(e: mi::E2[1]): String[1])
[G-ERROR] TypeInferenceException: in call to 'mi::takesEnum', argument 1: expected mi::E2, got mi::X
```
Two subtype oracles, two answers, on a model nothing rejected.

### [INCONSISTENCY] A BITEMPORAL class's `milestoning` property is typed `BusinessDateMilestoning`, losing the processing axis

`Temporal.java:29-38`:
```java
if (prop.equals("milestoning") && strat != null) {
    return new ExprType(new Type.ClassType("meta::pure::milestoning::"
            + (strat == MilestoningStrategy.PROCESSING ? "ProcessingDateMilestoning"
                                                       : "BusinessDateMilestoning")),
            Multiplicity.Bounded.ZERO_ONE);
}
```
`BITEMPORAL` falls into the else branch. Probe:
```
--- ms::Bi  strategy=BITEMPORAL
    businessDate -> Date[1]
    processingDate -> Date[1]
    milestoning -> meta::pure::milestoning::BusinessDateMilestoning[0..1]
```
The mis-typing is masked only because the milestoning classes accept each other's members (see
the cross-axis ICE above); fix one without the other and `$p.milestoning.in` on a bitemporal
class becomes a hard type error.

### [INFORMATION LOSS] `Decimal(p,s)` with `p > MAX_PRECISION` is accepted with no check

`Type.PrecisionDecimal` documents `MAX_PRECISION = 38` ("widest SQL decimal compatible with a
128-bit backing integer") at Type.java:177-178 but the constructor (Type.java:160-168) only
rejects `precision < 0` and `scale ∉ [0, precision]`.
```
DECIMAL(50,2)  ->  G: Relation<(c:Decimal(50,2)[0..1])>   [EXEC-ROW] BigDecimal(1.25)
DECIMAL(0,0)   ->  G: Relation<(c:Decimal(0,0)[0..1])>    [EXEC-ROW] BigDecimal(1.25)
```
`Decimal(0,0)` is likewise accepted for a column holding `1.25`.

### [INFORMATION LOSS] `NUMERIC` and `DECIMAL`, and the four integer widths, collapse

`StoreCompiler.columnType` (StoreCompiler.java:182-209) maps `Numeric`→`PrecisionDecimal` exactly
like `Decimal`, and TinyInt/SmallInt/Integer_/BigInt all→`Integer`. The runtime Java class,
however, still varies with the physical column, so one Pure type covers four wire classes:
```
BIGINT   -> c:Integer[0..1]   [EXEC-ROW] Long(42)
SMALLINT -> c:Integer[0..1]   [EXEC-ROW] Short(42)
TINYINT  -> c:Integer[0..1]   [EXEC-ROW] Byte(42)
INTEGER  -> c:Integer[0..1]   [EXEC-ROW] Integer(42)
DOUBLE   -> c:Float[0..1]     [EXEC-ROW] Double(1.5)
FLOAT    -> c:Float[0..1]     [EXEC-ROW] Float(1.5)
REAL     -> c:Float[0..1]     [EXEC-ROW] Float(1.5)
```

### [DEAD] `RelationalDataType.fromName`, `.Distinct`, `.Array`, `.Object_`; `SymbolTable.nameOf`/`allFqns`; `ModelBuilder.symbols()`

- `RelationalDataType.fromName(String)` has ZERO callers in `core/src/main` and `core/src/test`
  (grep). The DDL path goes through `DatabaseProtocolParser.parseDbType` -> `FromProtocol.dataType`.
- `parseDbType` (DatabaseProtocolParser.java:363-390) has no `DISTINCT` and no `OBJECT` arm and
  maps `ARRAY` to `"Other"`. So `RelationalDataType.Distinct`, `.Array` and `.Object_` are
  unreachable from Pure source, and `StoreCompiler.columnType`'s arms for them
  (StoreCompiler.java:200, 206, 207) are dead.
- `SymbolTable.nameOf(int)` and `SymbolTable.allFqns()` have zero production callers, as does
  `ModelBuilder.symbols()`.

### [DOC-LIE] Four prose claims contradicted by the code

1. `SynthFqn.java:18-20` — "The `$` sigil is non-user-writable in a Pure identifier, so a synth
   FQN can never collide with a user-authored function name." Refuted above (quoted identifiers).
2. `ModelIntegrity.java:20-24` — "An invalid model never becomes a queryable context, even if
   nothing ever demands the bad element." Refuted: bad constraint bodies, bad derived-property
   bodies, `DECIMAL(2,5)` columns, `OTHER`/`ARRAY` columns, class-extends-enum, enum/primitive
   association ends, inherited property conflicts all produce a queryable context.
3. `RelationalDataType.java:19,34` — the javadoc lists `{@link Bool}` as a nullary variant and
   says "the boolean variant is spelled {@link Bool}". **No `Bool` record exists** in the permits
   list; the boolean variant is `Bit`. `fromName`'s javadoc (line 109) also advertises `BOOLEAN`
   as an accepted "extra" — there is no `BOOLEAN` case, and the DDL parser refuses it:
   `[G-ERROR] ParseException: unsupported column datatype: BOOLEAN`.
4. `ClassLayouts.java:109-113` — "two SUPERS declaring the same name with different types is a
   genuine conflict — LOUD, never first-wins (audit)". True for TYPES; silently LAST-WINS for
   MULTIPLICITIES (see the `DiamondMult` unsoundness).

---

## VERIFIED SOUND

**`EqualityKeys.resolve` itself** — exhaustive probe over 10 class shapes; every answer matches
the documented rule:
```
model::K1            -> keys=[id]                                (keyed)
model::K2            -> keys=[id]
model::KeylessSup    -> null                                     (keyless: declines)
model::KeyedSub      -> keys=[b]                                 (own class first)
model::KeyedSup      -> keys=[s]
model::UnkeyedRedecl -> null                                     (un-keyed redeclaration REMOVES the super's key)
model::NestKeyed     -> keys=[inner nested=[K1.id]]              (class-typed key nests)
model::NestKeyless   -> null                                     (class-typed key over a keyless class poisons)
model::SelfKey       -> null                                     (key cycle)
model::ManyKey       -> keys=[vals many=true]                    ([*] key marked many)
```
`EqualityKeys.fqnOf` returns the raw class for both `ClassType` and `GenericType` and null
otherwise, as documented.

**`InstanceEquality` (the arm itself)** — when armed, its verdicts are correct: cross-class ->
`FALSE`; keyed same-class -> key-only canon (`json_object('_type','model::K1','id', …)`);
keyless same-class -> identity canon over `__id`; `contains` -> membership over canon texts.
`InstanceIds.idOf` mints per construction-site node (`i1`, `i2`, …) as documented.

**`ClassLayouts` layout composition** — inherited-first ordering, subclass override in the
inherited position, generic type-argument substitution, `__id` append only under `withIdentity`,
platform-carrier exclusion, and the `__id` name-collision guard (`Class model::IdColl { __id: String[1]; other: Integer[1]; }` ->
`IllegalStateException: class 'model::IdColl' declares a property named __id — colliding with the
synthetic identity field`; the PLAIN layout of the same class is built without complaint)
all behave as written.

**`LayoutTypes` cycle guard** — `Class model::CycA { b: CycB[1]; }` / `Class model::CycB { a: CycA[1]; }`
yields `Struct[Field[b, Struct[Field[a, JSON]]]]`; the revisited class rides variant JSON as
documented, no stack overflow.

**Milestoning row selection** — the emitted filter is
`WHERE t0.from_z <= DATE '…' AND t0.thru_z > DATE '…'` (from-inclusive, thru-exclusive). Boundary
behaviour checked at three dates against rows `(2015-01-01, 2016-01-01)`, `(2016-01-01, NULL)`,
`(2015-06-01, 9999-12-31)`: `%2015-01-01` -> P1 only; `%2016-01-01` -> P2 only (P1 correctly
excluded at its exclusive thru); `%2016-06-01` -> P2 only. A NULL `thru` is NOT treated as
open-ended (the NULL comparison drops the row) — engine parity uses `INFINITY_DATE`, so this is
consistent, but a model relying on NULL-as-infinity silently loses rows. Reported here rather
than as a finding because the SQL is a faithful rendering of the declared milestoning.

**`Temporal.strategyOf`** — resolves the three stereotypes and inherits through supers
(`MilestoningStrategy.ofStereotypeOrNull` accepts both `temporal` and
`meta::pure::profiles::temporal` profile spellings and returns null for unknown names).
`businessDate`/`processingDate` are correctly REFUSED on a class lacking that axis
(`class ms::Prod has no property 'processingDate'`).

**Constraint evaluation is DB-side** — `CheckedEnvelope.wrap` builds `CASE WHEN NOT <pred> THEN
json_object('id',…,'enforcementLevel',…,'ruleType','ClassConstraint',…) END`, filtered by
`list_filter(..., x -> x IS NOT NULL)`. No host-side predicate evaluation; the repo's "the DB
executes" claim holds here. Constraint metadata compiles correctly (name, level,
predicate/message FQNs) and inherited constraints are collected through the superclass walk
(`GraphEmission.java:520-553`).

**Constraint `~enforcementLevel` spelling** — the parser closes the set to `Error|Warn`
(`ElementParser.java:1185-1193`); `ClassCompiler.java:81` then does `equalsIgnoreCase("Warn")`.
The parser gate makes the case-insensitive comparison unreachable from source, so no defaulting
is observable from Pure text (it would matter only on a protocol/JSON ingest path).

**Derived-property signature handling** — `DerivedProps.lift` builds
`<owner>$prop$<name>(this:Owner[1], <params>)` with the class's type parameters propagated and
the generic receiver `Owner<T,…>`; `splitPropFqn` round-trips. Declared-vs-body checking, when it
runs, is correct in both directions:
```
wider()      body Integer -> declared Number[1]     accepted (widening), value Integer(1)
narrower()   body Integer -> declared Integer[1]    accepted, value Integer(2)
multWide()   body [1]     -> declared Integer[*]    accepted
multNarrow() body [3]     -> declared Integer[1]    REJECTED: "multiplicity [3] is not compatible with [1]"
optDeclOne() body [0..1]  -> declared Integer[1]    REJECTED: "multiplicity [0..1] is not compatible with [1]"
withParam(k) parameters                             accepted, value Integer(6)
callsOther() derived calling derived                accepted, value Integer(1)
r() { $this.r() } recursive                         NotImplementedException (designed error, not an ICE)
```

**`ModelIntegrity` checks that DO work** (each exercised):
inheritance cycle (`Inheritance cycle: mi::A -> mi::B -> mi::A`); duplicate element FQN across
kinds (`Duplicated element 'dk::T'`); duplicate stored-property name within one class;
duplicate enum value; invalid multiplicity bounds; unresolvable property/derived/parameter/
function type names; missing derived-property realizer; missing/wrong-shaped constraint
realizer (Door-4 ref form only); mapping class/association bindings and their realizer shapes;
duplicate function signatures **when parameter types are written unqualified**; store
join/filter ColumnRefs against the include closure.

**`StoreCompiler` DDL column-type mapping — exhaustive** (every spelling `parseDbType` accepts,
plus the refused ones, each RUN end to end):

| DDL spelling | protocol kind | model type | Pure type | result |
|---|---|---|---|---|
| `INTEGER`, `INT` | Integer | `Integer_` | `Integer` | ok, `Integer(42)` |
| `BIGINT` | BigInt | `BigInt` | `Integer` | ok, `Long(42)` |
| `SMALLINT` | SmallInt | `SmallInt` | `Integer` | ok, `Short(42)` |
| `TINYINT` | TinyInt | `TinyInt` | `Integer` | ok, `Byte(42)` |
| `DOUBLE` | Double | `Double_` | `Float` | ok, `Double(1.5)` |
| `FLOAT` | Float | `Float_` | `Float` | ok, `Float(1.5)` |
| `REAL` | Real | `Real` | `Float` | ok, `Float(1.5)` |
| `BIT` | Bit | `Bit` | `Boolean` | ok, `Boolean(true)` |
| `DATE` | Date | `Date_` | `StrictDate` | ok, `StrictDate(2020-01-01)` |
| `TIMESTAMP` | Timestamp | `Timestamp` | `DateTime` | ok, `DateWithSecond(…)` |
| `SEMISTRUCTURED` | SemiStructured | `SemiStructured` | `Variant` | ok, `JsonNode({"a":1})` |
| `JSON` | **Json→SemiStructured** | `SemiStructured` | `Variant` | ok (Json/SemiStructured conflated) |
| `VARCHAR(n)` | Varchar | `Varchar` | `String` | ok |
| `CHAR(n)` | Char | `Char_` | `String` | ok |
| `BINARY(n)` | Binary | `Binary` | `Byte` | **ICE at lowering** (finding above) |
| `VARBINARY(n)` | Varbinary | `Varbinary` | `Byte` | **ICE at lowering** |
| `DECIMAL(p,s)` | Decimal | `Decimal` | `Decimal(p,s)` | ok; `s>p` **ICEs**; `p>38` unchecked |
| `NUMERIC(p,s)` | Numeric | `Numeric` | `Decimal(p,s)` | same as DECIMAL |
| `OTHER` | Other | `Other` | — | clean `ModelException: SQL column type 'Other' has no scalar Pure type` |
| `ARRAY` | **Other** | `Other` | — | same message (says 'Other', misleading) |
| `VARCHAR` (bare) | — | — | — | `ParseException: Column data type VARCHAR requires 1 parameter (size)` |
| `VARCHAR(10,2)` | — | — | — | same arity ParseException |
| `BOOLEAN` | — | — | — | `ParseException: unsupported column datatype: BOOLEAN` |
| `DISTINCT` | — | — | — | `ParseException: unsupported column datatype: DISTINCT` |
| `OBJECT` | — | — | — | `ParseException: unsupported column datatype: OBJECT` |
| `TEXT` | — | — | — | `ParseException: unsupported column datatype: TEXT` |

Nothing is accepted-but-unmapped-silently and nothing falls back to a default type: every
unmapped kind throws. The two defects are `s>p` (raw IAE) and `BINARY/VARBINARY` (late IllegalState).
Column nullability is derived correctly: `notNull || primaryKey` -> `[1]`, else `[0..1]`
(StoreCompiler.java:169-171), and views add `cm.primaryKey()` to the same rule.

**`PureModelContext`** — memoization, overlay `ADD-never-SHADOW` guard
(`withExecutionOverlay` refuses a runtime/connection FQN the model declares, and eagerly
validates the runtime's mapping names), include-closure table/milestoning/view resolution with a
`seen` cycle guard, and `findProperty`'s three-leg walk (own -> supers -> association index) all
behave as documented. `findProperty`'s super walk is FIRST-HIT depth-first — correct for a linear
chain, but see the DiamondMult finding for the multi-super case.

**`TypedClass` / `TypedEnum` / `TypedNominal` / `TypedParameter` / `TypedElement` / `Property` /
`TypedConstraint`** — pure data records; defensive `List.copyOf`, null checks, FQN-only
cross-references, empty-enum tolerance. `ClassCompiler` reads the `<<equality.Key>>` stereotype
into `Property.Stored.equalityKey` correctly (verified via the key probe above).

**`FunctionCompiler`** — the native+user merge, the `meta::pure::*` bare-name courtesy scoped to
core packages, the platform-owned/PCT suppression (both announced on stderr, not silent), and
on-demand lifting of native-catalog derived properties. Note `compileAll`'s DROP-AT-OVERLOAD
behaviour (FunctionCompiler.java:150-163) is self-documented as a known hole; it is unreachable
in a strict build because integrity fails first.

---

## NOT COVERED

- **`ModelBuilder.java` (53 kLOC) in full.** I read and exercised only the parts my scope needed:
  FQN interning/id storage, `duplicateElements`, `findFunction`'s per-FQN overload list,
  `findAssociationEnd`, `findTable/findView/findJoin`, and the poison maps. The mapping/union
  synthesis surfaces and the legacy-mapping retention path were not audited.
- **`NameResolver.java`** is out of scope but is the root cause of the position-bearing
  `signatureKey` asymmetry; I did not audit which other name classes keep their positions.
- **`CanonicalRenderSql`** (the canon the identity lane emits) was read only far enough to show
  which lane consumes `EqualityKeys`; its canon-text grammar and the decline paths were not audited.
- **Multi-source / module compile (`wallSink`) path.** All my probes used the single-source
  strict form. The TOLERANT variant's "POISON-NOT-DROP" behaviour (ModelIntegrity.java:81-85) and
  `Compiler.compileModel(List<ModelSource>)` were not exercised.
- **Bitemporal `snapshotDate` milestoning kinds** (`BUS_SNAPSHOT_DATE`, `PROCESSING_SNAPSHOT_DATE`)
  and `INFINITY_DATE` / `THRU_IS_INCLUSIVE` variants: the grammar was read
  (DatabaseProtocolParser.java:470-556) but only the from/thru and in/out forms were run.
- **Protocol/JSON ingest (`FromProtocol.dataType`)** column kinds that the Pure DDL parser cannot
  produce (`Json` as a distinct kind, sizes/precisions arriving null -> 0). I probed only the
  Pure-source surface.
- **Dialects other than DuckDB.** Every execution repro used `jdbc:duckdb:`; the struct-equality
  and struct-key-case behaviours in particular are DuckDB-specific at the wire, though the
  Pure-level type erasure that produces them is dialect-independent.
