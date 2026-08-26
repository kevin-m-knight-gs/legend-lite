# A18 — Phase E (normalizer) adversarial type-system audit

Scope: `core/src/main/java/com/legend/normalizer/` (11 068 lines) + `com/legend/model/`.
Everything below is either quoted source read in full, or a probe I RAN with its pasted output.
Probe sources live in `/tmp/A18/` (`TypeMismatch.java`, `EnumProbe.java`, `UnionProbe.java`,
`ViewProbe.java`, `ViewProbe2.java`, `GroupByProbe.java`, `Consumed.java`, `BogusTable.java`,
`Fallbacks.java`, `DecTrunc.java`, `NullMult.java`, `Pretty.java`, `NoMutate.java`).

---

## 0. THE COMPLETE SYNTHESIS RULE SET (deliverable 1)

Verified by pretty-printing the actual normalizer output (`/tmp/A18/Pretty.java`), not from docs.

Entry: `ModelNormalizer.normalize(ParsedModel) -> NormalizedModel`, called from
`Compiler.buildModel` (:230) / `Compiler.buildModule` (:264) only.

### E.0–E.4 (ModelNormalizer)
| site | synthesized |
|---|---|
| association qualified property | adopted onto the *opposite* end class (`adoptAssociationDerivedProperties`), then lifted as E.2 |
| derived property | `<owner>$prop$<name>(this:Owner[1], …):T[m]` |
| constraint | `<owner>$constraint$<name>(this:Owner[1]):Boolean[1]` (+ `$constraintMsg$` → `String[1]`) |
| service query | `<svc>$query(<params>):Any[*]` |
| legacy mapping | E.1 → `MappingNormalizer` |

### E.1 — one `FunctionDefinition` per ClassMapping, FQN `<mapping>$class$<Class>() : <Class>[*]`
(set-discriminated variant: `<mapping>$class$<Class>[<setId>]`), plus
`<mapping>$assoc$<Assoc>(a:A[1], b:B[1]) : Boolean[1]` per AssociationMapping.
The legacy record is REPLACED by a canonical `MappingDefinition` carrying
`ClassBinding(classFqn, kind, setId, extendsSetId, root, Realization.Ref(fn), primaryKeyColumns)`.

Pipeline shape (`MappingNormalizer.synthTableBackedParts`, :2042–2227):

```
tableReference(db,'T')                                  -- ~mainTable (or inferred, :1619)
  -> lite::join(~alias: |tableReference(db,'U'), {s,t|cond})*   -- physical join hops (pass 1/2/3)
  -> lite::legacyNavigate(~slot: |getAll(Target), {sr,tr|cond})* -- class-typed final hops
  -> filter(row | <inlined ~filter predicate>)?          -- Direct or JoinMediated
  -> groupBy(~[keys], ~[aggs])?                          -- ~groupBy
  -> distinct(...)?                                      -- ~distinct
  -> map(row | ^Class(<fields>))
```

Per-construct emission, exactly as observed:

| construct | synthesized Pure |
|---|---|
| `~mainTable [db] T` | `tableReference(db,'T')` as pipeline source |
| `~mainTable` **absent** | inferred from PMs' sole direct table (`inferMainTable` :1619); >1 table = loud |
| `prop: [db] T.COL` | `coerceColumnToDeclared($row.COL)` (see §1) |
| `prop: [db] <relOp>` | `coerceToDeclaredNumeric(RelOpTranslator.translate(op))` |
| `prop: [db]@J` (class-typed) | `$row.<navSlot>`; the hop is a `legacyNavigate` step |
| `prop: [db]@J \| T.COL` (JoinTerminalColumn) | `$row.<slot>.COL`, wrapped in `typeAsDeclared` on kind mismatch |
| `AssociationMapping(e1:[db]@J, e2:[db]@J)` | `lite::legacyAssocPredicate($a,$b, tableReference(srcT), tableReference(tgtT), {srcRow,tgtRow\|cond})` |
| multi-hop association end | injected as a class-typed Join PM on the owning set (`AssociationSynthesis.injectMultiHopAssociationPMs`) |
| `XStore { e[sA,sB]: $this.x == $that.y }` | same `legacyAssocPredicate` shape with the two ends' relations; PURE ends use the *String-setId* overload and `lite::legacyLocalProperty($row,'p')` for `+` reads |
| `View V(...)` as ~mainTable | expanded as a macro: `tableReference(phys) -> [filter] -> (groupBy \| project) -> [distinct]`, then the normal table pipeline (`ViewRelation.viewRelationExpr`) |
| `Operation union_...(s1,s2)` | `concatenate(project(thread1,~[…]), project(thread2,~[…])) -> [legacyNavigate lifts] -> map(u_row \| ^C(...))`; shared scalar props only; unmapped prop in a thread = `trustOne(cast([], @T))` |
| `Operation inheritance_...` | same, plus per-subtype dispatch columns `stc_<Sub>___<prop>` and a `stc_<Sub>___$member` witness |
| set-ID dispatch | `MappingDefinition.routedTargetSets` (`SetDispatch.routedTargetSets`) |
| `+lp: T[m]: [db] T.COL` | ctor key with `isLocal=true`; **declared `T[m]` is DISCARDED** (:2632) |
| `~filter` (Direct) | inlined predicate as `filter(row \| …)` |
| `~filter` (JoinMediated) | join chain + exists-shaped filter; with an explicit `(INNER)` join type the main table becomes a row-exploding subselect (`JoinChainEmission.innerFilteredSource`) |
| `~groupBy(keys)` | `groupBy(~[keys], ~[aggs])`; aggregate PMs (`sum count avg min max stdDev variance`) become 2-stage AggColSpecs with `Pure.wireEmissionName` |
| `~distinct` | `select(~[mappedCols]) -> distinct()`, or slot-carrying `distinct(rel, ~[cols+slots])` |
| `~primaryKey(...)` | **NO body emission** (:2162-2170); recorded on the binding, and ONLY plain `ColumnRef` entries survive (`declaredPrimaryKeyColumns` :2317-2330 — expression keys silently contribute nothing) |
| `prop: EnumerationMapping M: [db] T.COL` | nested `if(equal(read,'code'), \|Enum.VAL, \|<else>)` chain, innermost else = `[]` (:3028) |
| `Class: Pure { ~src S  p: $src.x }` | `map(getAll(S), {src \| ^C(p=$src.x)})` — **no** `trustOne` wrap (deliberate, :3336) |
| `sourceUrl` (JSON) set | `map(lite::sourceUrl('…'), {row \| ^C(p = to(get($row.data,'p'), @T))})` |
| `Relation { ~func f }` | `map(<inlined f body>, {rf_row \| ^C(p=$rf_row.COL)})` |
| `extends [set]` | flattened pre-pass (`resolveExtends`), child overrides on name |

**Every `[1]`-declared property value is wrapped in `meta::legend::lite::trustOne(...)`**
(`buildNewInstanceToOne` :3353-3400) except `navigate`/`legacyNavigate`/`otherwise`/`new` values
and `+local` keys.

Real observed output (from `/tmp/A18/Pretty.java` on `/tmp/A18/all.pure`):

```
FN m::M$class$m::Person(): m::Person[*]  [CLASS]
   map(distinct(filter(lite::join(tableReference(s::DB, 'PERSON'), PF:{ | tableReference(s::DB, 'FIRM')},
       {s,t | equal($s.FIRM_ID, $t.ID)}), {row | equal(lite::trustOne($row.ST), 'A')}),
       ~[ID, NAME, ST, A, B, PF]),
     {row | new(m::Person, ^m::Person(
        id=lite::trustOne($row.ID),
        name=lite::trustOne($row.NAME),
        st=lite::trustOne(if(equal($row.ST, 'A'), { | m::Status.ACTIVE},
                          { | if(equal($row.ST, 'I'), { | m::Status.INACTIVE}, { | []})})),
        computed=lite::trustOne(lite::castAsDeclared(plus(...), @Integer)),
        +localCol=$row.A,
        addr=new(m::Addr, ^m::Addr(street=lite::trustOne($row.STREET), zip=lite::trustOne($row.ZIP))),
        firmName=$row.PF.LEGAL_NAME))})

FN m::M$assoc$m::PF(a:m::Firm[1], b:m::Person[1]): Boolean[1]  [ASSOC]
   lite::legacyAssocPredicate($a, $b, tableReference(s::DB,'FIRM'), tableReference(s::DB,'PERSON'),
     {srcRow,tgtRow | equal($tgtRow.FIRM_ID, $srcRow.ID)})
```

---

## FINDINGS

### [UNSOUND] There is NO column-type / property-type COMPATIBILITY CHECK. There is only a coercion table, and where the coercion is a no-op the type system lies.

**Evidence.** The single site is `MappingNormalizer.coerceColumnToDeclared` (:2389-2477). It never
*validates*; it *relabels*:

```java
// MappingNormalizer.java:2400
String colKind = cd == null ? null : RelationalKinds.pureKindOf(cd.dataType());
if (colKind == null || colKind.equals(declared)) {
    return read;                                    // no check
}
...
if ("String".equals(declared) || "Boolean".equals(declared)) {
    return new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, ...);   // :2417
}
...
Set<String> numeric = Set.of("Float", "Decimal", "Integer", "Number");
if (numeric.contains(declared) && numeric.contains(colKind)) {
    if ("Float".equals(declared) && "Decimal".equals(colKind)) { ...castAsDeclared... }
    return new AppliedFunction(Pure.Lite.TYPE_AS_DECLARED, ...);   // :2455  TYPE-ONLY
}
...
return read;                                                        // :2476  no coercion at all
```

`typeAsDeclared` lowers to **the identity**:
```java
// lowering/Scalars.java:673-677
for (String f : Pure.nativeKeysAt("meta::legend::lite::typeAsDeclared")) {
    RULES.put(f, (n, args) -> com.legend.sql.SqlTyping.tolerateRead(args.get(0)));
}
```
and `SqlTyping.tolerateRead` (`sql/SqlTyping.java:252`) merely flips a `tolerated` bit on the
column's `TypeFact` so the declared/computed disagreement is *not* reported downstream.
`castAsDeclared` is a WIRE-flagged `TypedCast`; at a projected cell root a String-target wire cast
**unwraps** (`lowering/CastPolicy.cellRootUnwrapWire` + `Scalars.java:3485` comment).
Net effect: the property carries the *declared* Pure type and the *raw driver* value.

**Repro / actual output** (`/home/user/probe/jrun.sh /tmp/A18/TypeMismatch.java`, 36 cases, all run,
DuckDB, one class + one table each, query `m::C.all()->project(~[v:x|$x.v])`):

```
======== StrOnInt  Pure=String[1]  Col=INTEGER  data=42
  SQL   : SELECT t0.V AS v FROM T AS t0
  COL   : v : String mult=[1]
  ROW   : java.lang.Integer(42)                       <-- UNSOUND
======== IntOnVar  Pure=Integer[1]  Col=VARCHAR(100)  data='77'
  SQL   : SELECT CAST(t0.V AS BIGINT) AS v FROM T AS t0
  ROW   : java.lang.Long(77)                          (sound)
======== IntOnVarBad  Pure=Integer[1]  Col=VARCHAR(100)  data='abc'
  EXEC-ERR: java.sql.SQLException: Conversion Error: Could not convert string 'abc' to INT64
======== IntOnVarDec  Pure=Integer[1]  Col=VARCHAR(100)  data='77.9'
  ROW   : java.lang.Long(78)                          <-- SILENT ROUNDING (pure parseInteger throws)
======== IntOnDec  Pure=Integer[1]  Col=DECIMAL(10,2)  data=123.45
  SQL   : SELECT t0.V AS v FROM T AS t0
  COL   : v : Integer mult=[1]
  ROW   : java.math.BigDecimal(123.45)                <-- UNSOUND (and not an integer)
======== BoolOnChar  Pure=Boolean[1]  Col=CHAR(1)  data='Y'
  SQL   : SELECT CAST(t0.V AS BOOLEAN) AS v ...
  ROW   : java.lang.Boolean(true)                     <-- see DOC-LIE below
======== BoolOnInt7 Pure=Boolean[1] Col=INTEGER data=7   ROW : java.lang.Boolean(true)
======== DateOnVar  Pure=StrictDate[1] Col=VARCHAR(50)
  PLAN-ERR: TypeInferenceException: property 'v' of 'm::C': expected StrictDate, got String   (LOUD - good)
======== DecOnDec52  Pure=Decimal[1]  Col=DECIMAL(5,2)  ROW : java.math.BigDecimal(999.99)  (sound)
======== IntOnBig    Pure=Integer[1]  Col=BIGINT 9223372036854775807  ROW : java.lang.Long(9223372036854775807) (sound; Pure Integer is 64-bit)
======== IntOnBig31  Pure=Integer[1]  Col=BIGINT 3000000000           ROW : java.lang.Long(3000000000)          (sound)
======== FloatOnDec  Pure=Float[1]    Col=DECIMAL(10,2)  SQL: CAST(t0.V AS DOUBLE)  ROW : java.lang.Double(3.14) (sound)
======== DtOnDate    Pure=DateTime[1] Col=DATE  SQL: CAST(t0.V AS TIMESTAMP)  ROW : PureDateLiteral$DateWithSecond(2021-06-01T00:00:00+0000) (sound)
======== IntOnDouble Pure=Integer[1]  Col=DOUBLE 2.75
  SQL   : SELECT t0.V AS v FROM T AS t0
  COL   : v : Integer mult=[1]
  ROW   : java.lang.Double(2.75)                      <-- UNSOUND
======== StrOnTs     Pure=String[1]   Col=TIMESTAMP
  ROW   : com.legend.values.PureDateLiteral$DateWithSecond(2022-03-04T05:06:07+0000)  <-- UNSOUND
======== StrOnDec    Pure=String[1]   Col=DECIMAL(10,2)  ROW : java.math.BigDecimal(12.50)   <-- UNSOUND
======== FloatOnInt  Pure=Float[1]    Col=INTEGER        ROW : java.lang.Integer(7)          <-- UNSOUND
======== DecOnInt    Pure=Decimal[1]  Col=INTEGER        ROW : java.lang.Integer(7)          <-- UNSOUND
======== NumOnVar    Pure=Number[1]   Col=VARCHAR(20)    SQL: CAST(t0.V AS DOUBLE)  ROW : java.lang.Double(3.5) (sound)
======== BoolOnVar   Pure=Boolean[1]  Col=VARCHAR(10) 'Y'  ROW : java.lang.Boolean(true)
======== DateOnTs    Pure=StrictDate[1] Col=TIMESTAMP   PLAN-ERR: expected StrictDate, got DateTime  (LOUD)
======== IntOnBit    Pure=Integer[1]  Col=BIT           PLAN-ERR: expected Integer, got Boolean      (LOUD)
======== StrOnBit    Pure=String[1]   Col=BIT           ROW : java.lang.Boolean(true)                <-- UNSOUND
======== ByteOnVarchar Pure=Byte[1]   Col=VARCHAR(10)   PLAN-ERR: expected Byte, got String          (LOUD)
======== IntOnTinyint  Pure=Integer[1] Col=TINYINT 127  ROW : java.lang.Byte(127)                    <-- carrier drift
======== IntOnSmallint Pure=Integer[1] Col=SMALLINT     ROW : java.lang.Short(32767)                 <-- carrier drift
======== IntOnFloat  Pure=Integer[1]  Col=FLOAT 1.5     ROW : java.lang.Double(1.5)                  <-- UNSOUND
======== StrOnDate   Pure=String[1]   Col=DATE          ROW : PureDateLiteral$StrictDate(2021-06-01) <-- UNSOUND
======== DtOnVar     Pure=DateTime[1] Col=VARCHAR(40)   PLAN-ERR: expected DateTime, got String      (LOUD)
======== DecOnDouble Pure=Decimal[1]  Col=DOUBLE 1.1    ROW : java.lang.Double(1.1)                  <-- UNSOUND
======== VarOnInt    Pure=Variant[1]  Col=INTEGER       PLAN-ERR: expected Variant, got Integer      (LOUD)
======== StrOnJson   Pure=String[1]   Col=JSON          ROW : org.duckdb.JsonNode({"a":1})           <-- UNSOUND + DRIVER CLASS LEAK
======== IntOnChar   Pure=Integer[1]  Col=CHAR(3) '12'  SQL: CAST(t0.V AS BIGINT)  ROW : java.lang.Long(12) (sound)
======== DecOnVar    Pure=Decimal[1]  Col=VARCHAR(30) '1.23456789012345678901'
  SQL   : SELECT CAST(rtrim(t0.V, 'dD') AS DECIMAL(5, 2)) AS v FROM T AS t0
  ROW   : java.math.BigDecimal(1.23)                   <-- SILENT TRUNCATION (see separate finding)
```

**Score over the 36 modelled mismatches (all 36 run, output above):**
* **6 caught loudly** — DateOnVar, DateOnTs, DtOnVar, IntOnBit, ByteOnVarchar, VarOnInt.
* **11 UNSOUND** — the declared Pure type and the returned Java carrier are different Pure kinds:
  StrOnInt, IntOnDec, IntOnDouble, IntOnFloat, StrOnTs, StrOnDec, StrOnDate, StrOnBit,
  FloatOnInt, DecOnInt, DecOnDouble.
* **1 leaks a driver-private class** — StrOnJson returns `org.duckdb.JsonNode` under `String[1]`.
* **2 silently lose data** — IntOnVarDec (`'77.9'` → 78) and DecOnVar (20 significant digits → 1.23).
* **2 carrier drift** — IntOnTinyint → `java.lang.Byte`, IntOnSmallint → `java.lang.Short`, both
  under the same declared `Integer` that elsewhere yields `Integer`/`Long`.
* **1 hard runtime failure** — IntOnVarBad (`'abc'`), which is at least loud.
* the remaining 13 happen to be coerced correctly.

The unsoundness follows the property into every consumer, not just projection
(`/tmp/A18/Consumed.java`):
```
======== Integer on DECIMAL: plus 1   |   m::C.all()->project(~[u:x|$x.v + 1])
  SQL : SELECT t0.V + 1 AS u FROM T AS t0
  type: Relation<(u:Integer[1])>
  ROW : java.math.BigDecimal(124.45)
======== Integer on DECIMAL: graphFetch
  ROW : java.lang.String([{"id":1,"v":123.45}])            <-- Integer-declared JSON field is 123.45
======== Integer on DOUBLE: sum
  type: Relation<(s:Integer[1])>     ROW : java.lang.Double(2.75)
```

**Why it matters.** An unchecked mapping is a wholesale hole in the type system: everything
downstream (G's inference, I's MIR typing, J's render, K's decode, the wire) trusts a claim that
phase E never verified and phase I is explicitly told (`tolerateRead`) not to complain about.

---

### [UNSOUND] Same mismatch, three routes, three different outcomes (plain column / join-terminal column / view column)

`/tmp/A18/Final1.java` — `v: String[1]` over an `INTEGER` column:

```
======== String[1] property over an INTEGER column reached through a VIEW
  PLAN-ERR TypeInferenceException: property 'v' of 'm::C': expected String, got Integer      (LOUD)
======== String[1] property over an INTEGER JOIN-TERMINAL column (declaredAssertion path)
  SQL : SELECT t0.ID AS id, t1.A AS v FROM T AS t0 LEFT OUTER JOIN U AS t1 ON t0.FK = t1.ID
  COL : v : String
  ROW : java.lang.Integer(1) | java.lang.Integer(42)                                          (SILENT)
```
plus the plain-column route above (`StrOnInt`, also silent, different mechanism —
`castAsDeclared` vs `typeAsDeclared`).

Cause: three separate coercion entry points that do not agree —
`coerceColumnToDeclared` (:2389), `declaredAssertion` (:2363, JoinTerminalColumn),
`coerceToDeclaredNumeric` (:2291, Expression) — plus a fourth path (view) where
`findPhysicalColumn` returns `null` so *no* coercion is attempted (:2400 `colKind == null`).

Additional asymmetry within one property (`/tmp/A18/Consumed.java`): `String[1]` on INTEGER
returns `java.lang.Integer(42)` at a bare projection root but `"42"` when consumed
(`->toUpper()`, `->length()`, `filter(== '42')`, graphFetch) — because a String-target WIRE cast
unwraps *only* at a cell root.

---

### [UNSOUND] `trustOne` makes every `[1]`-declared property a lie over a nullable column

`buildNewInstanceToOne` (:3353-3400) wraps every value bound to a `[1]` property in
`meta::legend::lite::trustOne`, which types `T[*] -> T[1]` and lowers to **identity, no guard**:

```java
// builtin/Pure.java:790-798  (Lite.TRUST_ONE javadoc)
//   asserts [1] over an optional read WITHOUT a runtime guard
// lowering/Scalars.java:487-490
for (String f : Pure.nativeKeysAt(Pure.Lite.TRUST_ONE)) { RULES.put(f, (n, args) -> args.get(0)); }
```

`/tmp/A18/NullMult.java` — all four properties declared `[1]`, all four columns nullable:
```
======== m::C.all()->project(~[id:x|$x.id, n:x|$x.name, g:x|$x.age, w:x|$x.when])
  type: Relation<(id:Integer[1], n:String[1], g:Integer[1], w:StrictDate[1])>[1]
  COL : n : String mult=[1]
  ROW : Integer(1) | String(Ann) | Integer(30) | StrictDate(2020-01-01) |
  ROW : Integer(2) | NULL | NULL | NULL |                     <-- [1] cells are null
======== graphFetch
  ROW : String([{"id":1,...},{"id":2,"name":null,"age":null,"when":null}])
```
The synthesizer never consults the column's `NOT NULL` / PK flag, even though
`StoreCompiler.tableSchema` (:159-176) computes exactly that multiplicity for the store lane.
Contrast: the `#>{db.T}#` store lane correctly reports `Bounded[lower=0, upper=1]` for the same
columns (`/tmp/A18/Prec.java` output), so the information exists and is discarded at the mapping seam.

---

### [SILENT FALLBACK / WRONG DATA] A property mapping's table qualifier is ignored — any unresolvable table silently reads the MAIN table

```java
// normalizer/RelOpTranslator.java:103-116
static ValueSpecification columnRead(String table, String column,
        Map<String, ValueSpecification> tableScope, String defaultTable, PipelineView pipeline) {
    ValueSpecification base = tableScope.get(table);
    if (base == null && pipeline.ambiguousTables().contains(table)) { throw ambiguousTableRef(table, column); }
    if (base == null) base = tableScope.get(defaultTable);      // <<<< UNCONDITIONAL FALLBACK
    ...
    return new AppliedProperty(base, column);
}
```

`/tmp/A18/BogusTable.java` (T.A = 42, U.A = 999, no join between them):
```
======== v: [s::DB] NOSUCHTABLE.A  (A exists on the main table T)
  SQL : SELECT t0.ID AS id, t0.A AS v FROM T AS t0
  ROW : [1, 42]
======== v: [s::DB] U.A  (U is a real, unjoined table that HAS column A)
  SQL : SELECT t0.ID AS id, t0.A AS v FROM T AS t0
  ROW : [1, 42]                       <-- should be 999 (or loud); silently reads T.A
======== v: [s::NOSUCHDB] T.A
  SQL : SELECT t0.ID AS id, t0.A AS v FROM T AS t0
  ROW : [1, 42]
```
No warning, no poison, no error — just the wrong column, from the wrong table, in the wrong
database. The sibling path for the *same* concept is loud:
`RelOpTranslator.translate`'s `ColumnRef` arm (:205-218) throws
`"ColumnRef references table '…' not in scope"`. Two implementations of one rule; one of them
returns wrong rows.

---

### [CRASH/ICE] `java.lang.ArithmeticException: Rounding necessary` escapes from a union over an Integer-declared DECIMAL column

`/tmp/A18/UnionCrash.java` — `v: Integer[1]`, mapped to `INTEGER` in branch 1 and `DECIMAL(10,2)`
(value 33.75) in branch 2 of a `union_OperationSetImplementation_1__SetImplementation_MANY_`:

```
java.lang.ArithmeticException: Rounding necessary
	at java.math.BigDecimal.toBigIntegerExact(BigDecimal.java:3566)
	at com.legend.exec.Executor.unwrap(Executor.java:659)
	at com.legend.exec.Executor.shapeRow(Executor.java:785)
	at com.legend.exec.Executor.tabular(Executor.java:703)
	...
	at com.legend.Compiler.execute(Compiler.java:594)
```
Root cause is phase E: the union thread's value is already `typeAsDeclared(read, @Integer)` (a no-op
that claims Integer), so `UnionSynthesis`'s outer `coerceToDeclaredNumeric` sees source==target and
emits **no SQL cast** — `SELECT t1.V AS v FROM T2 AS t1`. The output column is then declared
BIGINT while DuckDB's `UNION ALL` widens to DECIMAL, and `Executor.unwrap`'s integral-decode guard
calls `toBigIntegerExact()` on 33.75. A raw `java.lang.ArithmeticException` reaches the caller —
no phase, no element, no user-facing message.

---

### [UNSOUND] Unmapped / case-mismatched / NULL enum source values decode to NULL inside a `[1]` enum property

`translateEnumeratedSource` (:2980-3075) builds `if(equal(read,'code'), |Enum.V, |<else>)` with

```java
// MappingNormalizer.java:3028
ValueSpecification tail = new PureCollection(List.of());     // innermost ELSE = []
```

`/tmp/A18/EnumProbe.java`, `st: m::Status[1]`, `ACTIVE:['A'], INACTIVE:['I'], PENDING:['P']`:
```
======== string codes, UNMAPPED value 'Z' present
  SQL : ... CASE WHEN t0.ST='A' THEN 'ACTIVE' ELSE CASE WHEN t0.ST='I' THEN 'INACTIVE'
        ELSE CASE WHEN t0.ST='P' THEN 'PENDING' ELSE NULL END END END AS st ...
  COL : st : m::Status mult=[1]
  ROW : java.lang.Integer(1) | java.lang.String(ACTIVE) |
  ROW : java.lang.Integer(2) | null |                          <-- NULL in a [1] enum slot
======== string codes, CASE-mismatch 'a'      ROW : java.lang.Integer(1) | null |
======== string codes, NULL column            ROW : java.lang.Integer(1) | null |
======== integer codes (unmapped 9)           ROW : java.lang.Integer(4) | null |
======== GRAPH: unmapped value 'Z', st declared [1]
  JSON  : [{"id":1,"st":"ACTIVE"},{"id":2,"st":null}]
```
So: **it decodes to NULL, silently — not a bogus enum, not an error.** The declared multiplicity
`[1]` is violated (the `[]` tail is laundered by the `trustOne` wrap described above).
Enum values also come back as **`java.lang.String`**, not an enum carrier, under a
`m::Status` column type.

Sub-cases checked, all in the same run:
* **duplicate code** `ACTIVE:['X'], INACTIVE:['X']` — accepted with no diagnostic; the FIRST-listed
  value wins (the chain is built back-to-front at :3040, so the first declared becomes the outermost
  `WHEN`). SQL: `CASE WHEN ST='X' THEN 'ACTIVE' ELSE CASE WHEN ST='X' THEN 'INACTIVE' …` — a
  provably dead second branch is emitted rather than rejected.
* **value the enum does not declare** (`BOGUS: ['b']`) — LOUD (:3037-3043). Good.
* **enumeration itself missing** — LOUD at name resolution. Good.
* **string codes against an INTEGER column** — no static check; fails at DuckDB run time
  (`Could not convert string 'A' to INT32`).
* the "value not declared" check itself is skipped when `model.findEnum(...)` misses
  (`:3034-3035  .map(EnumDefinition::values).orElse(null)` then `knownValues != null &&`).

---

### [UNSOUND / SILENT TRUNCATION] A `Decimal` property over a VARCHAR column is silently truncated to `DECIMAL(5,2)`

The normalizer's numeric-over-string arm (:2460-2474) emits a bare 1-arg `parseDecimal`:
```java
String parseFn = switch (declared) { case "Integer" -> "parseInteger"; case "Float","Number" -> "parseFloat";
                                     case "Decimal" -> "parseDecimal"; default -> null; };
return new AppliedFunction(parseFn, List.of(new AppliedFunction(Pure.Lite.TRUST_ONE, List.of(read))));
```
The Typer *refines* 1-arg `parseDecimal` to `PrecisionDecimal(38,18)`
(`compiler/spec/Typer.java:1814-1826`), but the lowering hard-codes `DECIMAL(5,2)`:
```java
// lowering/Scalars.java:2320-2327  (non-literal argument)
return new SqlExpr.Cast(SqlExpr.Call.of(SqlFn.RTRIM, args.get(0), new SqlExpr.StringLit("dD")),
        new SqlType.Decimal(5, 2));
```

`/tmp/A18/DecTrunc.java`:
```
SQL  : SELECT t0.ID AS id, CAST(rtrim(t0.V, 'dD') AS DECIMAL(5, 2)) AS v FROM T AS t0
type : ... Column[name=v, type=DECIMAL, multiplicity=[1..1]]
COL  : v : DECIMAL
ROW  : [1, 1.23]         -- input '1.23456789012345678901'
ROW  : [2, 999.99]
-- with a third row '123456.78':
EXEC-ERR java.sql.SQLException: Conversion Error: Could not convert string "123456.78" to DECIMAL(5,2)
```
Declared `Decimal[1]`, typed `Decimal(38,18)`, delivered `DECIMAL(5,2)`: any value with more than
2 decimals is silently rounded and any value ≥ 1000 is a hard runtime failure.

Related silent rounding on the same coercion family (`/tmp/A18/Fallbacks.java`, and DuckDB
semantics confirmed in `/tmp/A18/Duck.java`):
```
declared Integer over expression PM add(A,B) on DECIMAL
  SQL : SELECT t0.ID AS id, CAST(t0.A + t0.B AS BIGINT) AS v FROM T AS t0
  ROW : java.lang.Integer(1) | java.lang.Long(8)          -- 5 + 2.50 = 7.50 -> 8
CAST(7.5 AS BIGINT) -> 8      CAST(2.5 AS BIGINT) -> 3     CAST(-2.5 AS BIGINT) -> -3
CAST('77.9' AS BIGINT) -> 78
```

---

### [INFORMATION LOSS] DECIMAL(p,s), VARCHAR(n) and the integer widths do NOT reach the class-mapping lane

Deliverable 3, traced and proved.

`StoreCompiler.columnType` (:182-206) is the one SQL→Pure boundary:
```java
case RelationalDataType.Decimal d -> new Type.PrecisionDecimal(d.precision(), d.scale());
case RelationalDataType.Varchar v -> Type.Primitive.STRING;     // size DROPPED
case RelationalDataType.Char_  c -> Type.Primitive.STRING;      // size DROPPED
case RelationalDataType.TinyInt/SmallInt/Integer_/BigInt -> Type.Primitive.INTEGER;  // width DROPPED
```
Precision survives into the **store** lane and dies at the **mapping** lane, because the mapping's
declared property type wins and no coercion runs when the kinds match (`colKind.equals(declared)`
→ `return read`, :2401).

`/tmp/A18/Prec.java`, one model, two lanes, same table `T(D DECIMAL(10,2), N NUMERIC(5,1),
S VARCHAR(3), I BIGINT, TI TINYINT, F REAL)`:
```
======== #>{s::DB.T}#->select(~[D, N, S, I, TI, F])      (STORE lane)
  COL : D : PrecisionDecimal[precision=10, scale=2]
  COL : N : PrecisionDecimal[precision=5, scale=1]
  COL : S : STRING          COL : I : INTEGER   COL : TI : INTEGER   COL : F : FLOAT
  ROW : BigDecimal(12345678.91) BigDecimal(1234.5) String(abc) Long(9007199254740993) Byte(100) Float(0.1)

======== m::C.all()->project(~[d,n,s,i,ti,f])            (MAPPING lane, same columns)
  COL : d : DECIMAL         <-- bare Type.Primitive.DECIMAL; (10,2) GONE
  COL : n : DECIMAL         <-- (5,1) GONE
  COL : s : STRING   COL : i : INTEGER   COL : ti : INTEGER   COL : f : FLOAT
  ROW : BigDecimal(12345678.91) BigDecimal(1234.5) String(abc) Long(9007199254740993) Byte(100) Float(0.1)
```
Consequences:
* a bare `Decimal` lowers to `DECIMAL(38,18)` wherever a cast is needed
  (`lowering/PureSql.java:75  case DECIMAL -> new SqlType.Decimal(38, 18);`), so the declared
  (10,2) is replaced by the blanket carrier, not carried.
* **VARCHAR(n) length is enforced NOWHERE**: `VARCHAR(3)` mapped to `String[1]`, then
  `$x.s + 'XYZWWWW'` → `String[1]` = `"abcXYZWWWW"` (10 chars) with no diagnostic.
  Grep over all of `core/src/main/java` finds exactly four readers of `Varchar.size()`:
  `exec/Ddl.java:437-438` and `plan/PlanText.java:932-933` (DDL/plan TEXT emission) and
  `exec/MetamodelWalk.java:1287,1503` (concat width / engine-text kind join). None is a check;
  `compiler/element/StoreCompiler.java:194-195` and `normalizer/RelationalKinds.java:24-25` both
  discard the size outright.
* **TINYINT / SMALLINT / INTEGER / BIGINT all collapse to `Integer`**, and the decode is
  driver-object-keyed (`Executor.fetch/unwrap`, :567-676), so one declared Pure type `Integer`
  yields four different Java carriers — `java.lang.Byte(127)`, `java.lang.Short(32767)`,
  `java.lang.Integer(30)`, `java.lang.Long(3000000000)` — plus `java.math.BigInteger` for a
  `Number`-typed `sum()`. Nothing normalizes them.

---

### [UNSOUND / INCONSISTENCY] Union synthesis: branches are coerced differently from the single-set path, and an unmapped branch gives NULL under `[1]`

`UnionSynthesis.synthMemberUnion` (:1028) coerces each thread at :1168-1197 with
`coerceToDeclaredNumeric` + `cast(@String)` + `trustOne`. `/tmp/A18/UnionProbe.java`:

```
======== Integer prop: INTEGER in T1, VARCHAR in T2  /  String prop: VARCHAR in T1, INTEGER in T2
  SQL : SELECT t2.id, t2.v, t2.w FROM (
          SELECT t0.ID AS id, t0.V AS v, t0.W AS w FROM T1 AS t0
          UNION ALL
          SELECT t1.ID AS id, CAST(t1.V AS BIGINT) AS v, CAST(t1.W AS VARCHAR) AS w FROM T2 AS t1) AS t2
  ROW : java.lang.Integer(1) | java.lang.Long(10) | java.lang.String(aa) |
  ROW : java.lang.Integer(2) | java.lang.Long(20) | java.lang.String(99) |
```
The `w: String[1]` property over an INTEGER column returns the **string** `"99"` here, but the
**identical** modelling error on a single (non-union) set returns `java.lang.Integer(42)`
(`StrOnInt` above). Same declaration, same mismatch, two different runtime types depending on
whether the class happens to be union-mapped.

```
======== Integer prop over INTEGER / DECIMAL(10,2) branches
  EXEC-ERR java.lang.ArithmeticException: Rounding necessary          (see CRASH above)
======== Boolean prop over BIT / CHAR(1)     ROW : Boolean(true) | ... | Boolean(true)
======== StrictDate prop over DATE / TIMESTAMP
  SQL : ... CAST(t1.V AS DATE) AS v ...     ROW : StrictDate(2020-01-01) / StrictDate(2021-02-03)
        (the TIMESTAMP branch silently drops 04:05:06)
======== branch 2 does NOT map v (declared [1])
  SQL : ... UNION ALL SELECT t1.ID AS id, NULL AS v FROM T2 AS t1 ...
  COL : v : Integer mult=[1]
  ROW : java.lang.Integer(1) | java.lang.Integer(10) |
  ROW : java.lang.Integer(2) | NULL |                       <-- [1] violated by construction
```
The union relation type is the **union of the members' scalar property names** intersected with the
operation class's own scalar properties (`UnionSynthesis.java:1098-1112`), not the columns; a member that does not map a
property contributes `trustOne(cast([], @T))` — a typed NULL claiming `[1]`
(`UnionSynthesis.java:1176-1177` + `:1194`, via `MappingNormalizer.nullOfDeclaredType` :1553).

---

### [INFORMATION LOSS] View computed columns ARE inferred, but every aggregate infers the abstract `Number`, so no concrete numeric property can be mapped to it

Deliverable 6. View expansion (`ViewRelation.viewRelationExpr` :86-262) translates each
`~groupBy`/aggregate/computed column through `RelOpTranslator` and lets the ordinary Typer infer it —
so inference is real, and divergence from the declared property type is **loud**
(`/tmp/A18/ViewProbe.java`, `/tmp/A18/ViewProbe2.java`, all runs pasted below):

```
view computed A+1 -> Integer            SQL: t0.A + 1        type Integer   ROW Integer(11)   OK
view computed divide(A,B) -> Float      SQL: ((1.0*t0.A)/t0.B) type Float   ROW Double(2.666…) OK
view computed divide(A,B) -> Integer    PLAN-ERR expected Integer, got Float                   LOUD
view computed concat(C,'!') -> String   SQL: concat(t0.C,'!')  type String  ROW String(xy!)    OK
view computed concat(C,'!') -> Integer  PLAN-ERR expected Integer, got String                  LOUD
view computed case(...) -> String       SQL: CASE WHEN … END   type String  ROW String(ten)    OK
view computed case(...) -> Boolean      PLAN-ERR expected Boolean, got String                  LOUD
view groupBy A, count -> Integer        SQL: COUNT(t0.ID)      type Integer ROW java.lang.Long(1)
view groupBy A, sum(B) -> Number        SQL: SUM(t0.B)         type Number  ROW BigDecimal(3.75)
view groupBy A, sum(B) -> Decimal       PLAN-ERR expected Decimal, got Number
view groupBy A, sum(B) -> Integer       PLAN-ERR expected Integer, got Number
view groupBy A, sum(B) -> Float         PLAN-ERR expected Float,   got Number
view groupBy A, max(B) -> Decimal       PLAN-ERR expected Decimal, got Number
view groupBy A, avg(A) -> Integer       PLAN-ERR expected Integer, got Float
computed add(A_int, B_dec) -> Number    SQL: t0.A + t0.B       type Number  ROW BigDecimal(13.75)
computed add(A_int, B_dec) -> Decimal   PLAN-ERR expected Decimal, got Number
sum(INTEGER)  -> Number   ROW java.math.BigInteger(1)
sum(BIGINT)   -> Number   ROW java.math.BigInteger(9223372036854775806)
computed times(E,E) on BIGINT -> Integer   EXEC-ERR Out of Range Error: Overflow in multiplication of INT64
```
Divergences against DuckDB's real result types:
* `SUM(DECIMAL(10,2))` is `DECIMAL(38,2)` on DuckDB; the type system says `Number` and the wire
  delivers `java.math.BigDecimal`. `Number` is an abstract Pure type whose only inhabitants here are
  `BigDecimal`/`BigInteger` — neither is a concrete Pure primitive carrier.
* `COUNT(x)` is BIGINT; the type system says `Integer` and delivers `java.lang.Long` while a plain
  INTEGER column under the same `Integer` type delivers `java.lang.Integer`.
* The consequence is a usability wall: **a view aggregate cannot be mapped to any concrete numeric
  property at all**, only to `Number`.

The exact same `Number`-only wall exists for class-level `~groupBy`
(`/tmp/A18/GroupByProbe.java`: `sum` → Number, `max` → Number, `avg` → Float, `count` → Integer),
and there it is aggravated by the fact that `translatePmToField` short-circuits
**every** declared-type coercion under `~groupBy`:
```java
// MappingNormalizer.java:2574-2577
if (underGroupBy) {
    return new CtorField(pm.propertyName(), new AppliedProperty(rowBind, pm.propertyName()), false);
}
```

Also: a view carrying **any** non-`ColumnRef` projection is invisible to the store lane and the
error message misreports it as a missing table —
`#>{s::DB.V}#` → `TypeInferenceException: unknown table 'V' in database 's::DB'`
(`StoreCompiler.viewSchema` :83-108 returns `Optional.empty()`), while the class-mapping lane
happily reads the same view.

---

### [SILENT FALLBACK] `Compiler.compileModel` succeeds on user-model errors the engine rejects — mapping failures become deferred "poisons"

`MappingNormalizer` converts per-class / per-set / per-association normalization failures into map
entries instead of errors:
```java
// MappingNormalizer.java:326-338
} catch (NotImplementedException | ModelException e) {
    // DELIBERATE TRADE (audit 6, adjudicated): ModelException here means a USER-model error the
    // real engine rejects at compile time; we defer it to query time ...
    model.mappingPoisons.put(md.qualifiedName() + "::" + cm.className(), String.valueOf(e.getMessage()));
    continue;
}
```
(the same pattern at :313 per-set, :398 re-synthesis, :420 per-association).

`/tmp/A18/PoisonProbe.java`:
```
compileModel SUCCEEDED despite the broken EnumerationMapping. elements=PureModelContext
m2 compileModel SUCCEEDED (mapping spans two tables, no ~mainTable)
```
The second model additionally binds a property `x` that does not exist on `m::C` — also accepted.
The failure only surfaces at query time, re-wrapped as a store-resolution message:
`MappingResolutionException: runtime 't::R' has 0 mappings binding class 'm::C' … failed to
normalize this class: EnumerationMapping 'SM' maps value 'BOGUS' …`.
This is the documented trade, but it is exactly the "swallow an error, defer it, report it as
something else" shape the brief forbids, and `compileModel` (the *strict* entry point, not
`buildModule`) is the one that swallows.

---

### [INFORMATION LOSS] A `+` local property's declared type and multiplicity are parsed, carried, and then thrown away — and never checked

```java
// model/PropertyMapping.java:315-325 — the declaration IS modelled
record LocalProperty(String propertyName, TypeExpression type, Multiplicity multiplicity, PropertyMapping body)
// normalizer/MappingNormalizer.java:2632-2636 — and dropped
case PropertyMapping.LocalProperty lp -> {
    CtorField inner = translatePmToField(lp.body(), ...);
    yield new CtorField(lp.propertyName(), inner.value(), true);   // lp.type()/lp.multiplicity() unused
}
```
Grep over `core/src/main/java` shows **no** consumer of `LocalProperty.type()` /
`.multiplicity()` anywhere (only structural copies in `StoreSubstitutionRewrite`,
`ViewRelation`, `NameResolver`). The type checker then explicitly declines to check local keys:
```java
// compiler/spec/NewChecker.java:75-82
if (key.isLocal()) {
    // ... no class property to validate against; the value's own type stands
    properties.put(name, t.synth(key.value(), env)); return;
}
```
Verified in the emitted body: `+localCol=$row.A` — no `trustOne`, no coercion, no assertion, for a
declaration that said `Integer[1]`. `+fkFirm: Integer[1]: [s::D1] PT.FID` in the XStore probe emits
`+fkFirm=$row.FID` identically. So `+p: StrictDate[1]: [db] T.VARCHAR_COL` is accepted with no
diagnostic at any phase.

---

### [DOC-LIE + FORWARD/BACKWARD DIVERGENCE] The Boolean coercion comment claims loudness; it is silent and returns the OPPOSITE of the engine value it cites

```java
// MappingNormalizer.java:2285-2291
 * <p>DELIBERATE divergence (audit 19 F6): the engine's runtime rule for
 * declared-Boolean strings is {@code Boolean.parseBoolean} — 'Y' maps
 * to FALSE, silently. Our SQL cast ERRORS on such strings instead:
 * loud beats silently-different, and the corpus only ever feeds
 * 'true'/'false' (where the two agree).
```
It does not error. `/tmp/A18/Duck.java`:
```
CAST('Y' AS BOOLEAN) -> true      CAST('N' AS BOOLEAN) -> false
CAST('yes' AS BOOLEAN) -> true    CAST('t' AS BOOLEAN) -> true
CAST(7 AS BOOLEAN) -> true        CAST(0 AS BOOLEAN) -> false
```
and end-to-end (`/tmp/A18/TypeMismatch.java`, `/tmp/A18/Fallbacks.java`):
```
BoolOnChar  Pure=Boolean[1] Col=CHAR(1) 'Y'   ROW : java.lang.Boolean(true)
declared Boolean over case('Y','N') expression PM
  SQL : SELECT t0.ID AS id, CAST(CASE WHEN t0.A = 5 THEN 'Y' ELSE 'N' END AS BOOLEAN) AS b ...
  ROW : Integer(1) | Boolean(true) |     Integer(2) | Boolean(false) |
```
So on the stated reference semantics (`Boolean.parseBoolean("Y") == false`) lite returns the exact
opposite value, silently. Rank: the DOC-LIE is low, but the silent opposite-value divergence is a
real forward/backward asymmetry.

---

### [SILENT FALLBACK] `catch` / `orElse` / default census over the whole package

Counts (mechanical, `grep` over the 19 files):
`catch` = **10**, `orElseThrow` = **45**, `orElse(null)` = **41**, `orElse(<non-null>)` = **4**,
`getOrDefault` = **10**. Every site inspected; classification:

#### catch (10) — 5 legitimate, 5 silent
| file:line | code | verdict |
|---|---|---|
| `MappingNormalizer.java:221-236` | `withElement` — re-throw with element FQN attached | LEGIT (re-throws) |
| `MappingNormalizer.java:226` | `catch (NotImplementedException \| MappingResolutionException e) { throw new ModelException(...) }` | LEGIT-ish; converts a wall into an *attributable* error, which makes it droppable in tolerant mode |
| `MappingNormalizer.java:179`, `:193` | `if (wallSink == null \|\| e.element() == null) throw e; wallSink.putIfAbsent(...)` | LEGIT in tolerant mode only (strict re-throws) |
| `JoinChainEmission.java:812` | `catch (ModelException unmappedTarget) { return null; }` — declines the view-substitution optimization | LEGIT (a `null` here just skips an optimization) |
| **`MappingNormalizer.java:313-320`** | `catch (NotImplementedException \| ModelException e) { model.mappingPoisons.putIfAbsent(…, e.getMessage()); }` (per-SET) | **SILENT** |
| **`MappingNormalizer.java:326-341`** | per-CLASS poison — quoted above | **SILENT (worst offender)** |
| **`MappingNormalizer.java:398-403`** | per-CLASS poison on the include re-synthesis pass | **SILENT** |
| **`MappingNormalizer.java:420-440`** | per-ASSOCIATION poison (tolerant only) | **SILENT (tolerant)** |
| **`MappingNormalizer.java:1671-1675`** | `inferMainTableQuiet`: `try { return inferMainTable(rcm); } catch (ModelException e) { return null; }` | **SILENT** — swallows "property mappings span tables […]. Please specify a main table" |

#### The behaviour-changing `orElse` / default sites
| file:line | code | verdict |
|---|---|---|
| **`RelOpTranslator.java:110`** | `if (base == null) base = tableScope.get(defaultTable);` | **SILENT FALLBACK → WRONG DATA** (own finding above) |
| **`MappingNormalizer.java:2400-2402`** | `String colKind = cd == null ? null : …; if (colKind == null \|\| colKind.equals(declared)) return read;` | **SILENT** — "column not declared in the store" and "kinds agree" share one arm; the declared-type coercion is skipped without a word |
| **`MappingNormalizer.java:2380-2382`** | identical arm in `declaredAssertion` | **SILENT** |
| **`MappingNormalizer.java:2476`** | trailing `return read;` — any (declared, colKind) pair the table does not enumerate passes uncoerced | **SILENT** |
| **`MappingNormalizer.java:2504-2506`** | `DatabaseDefinition db = model.findDatabase(dbFqn).orElse(null); if (db == null) return null;` (feeds the two arms above) | **SILENT** |
| **`MappingNormalizer.java:3034-3036`** | `List<String> knownValues = model.findEnum(...).map(EnumDefinition::values).orElse(null);` then the "value not declared" check is guarded by `knownValues != null` | **SILENT** (check disappears on a miss) |
| **`ViewRelation.java:462-491`** | `columnPureKind(...)` returns `null` for a computed view column; the 3 call sites (`MappingNormalizer:1533`, `UnionSynthesis.java:1345/1886/1890`, `JoinChainEmission:521`) each branch on it | mixed: `MappingNormalizer.java:1533-1540` throws (LEGIT), the others skip |
| **`UnionSynthesis.java:1748-1752`** | `model.findJoin(...).orElse(null); if (jd0 == null) { mergeable = false; break; }` — an unresolvable join silently changes the union's route-merging decision | **SILENT** (comment claims it is "loud at the route's own emission") |
| **`UnionSynthesis.java:2303-2305`, `:2330-2332`** | `if (fjd == null) return;   // loud at the route's own emission` | SILENT-by-deferral (same pattern) |
| `AssociationSynthesis.java:107-108`, `:298-299` | `findAssociation(...).orElse(null); if (ad == null) continue;` — an unknown association skips the multi-hop injection | benign in practice: the standalone path is loud (`compileModel threw ModelException: AssociationMapping references unknown association 'm::NoSuchAssoc'`) — but the two paths disagree |
| `MappingNormalizer.java:679-681`, `:2965-2967` | unresolvable `include` skipped ("its own loud problem elsewhere") | benign |
| `StoreSubstitutionRewrite.java:66` | `m.getOrDefault(database, database)` | benign (identity substitution) |
| `MappingNormalizer.java:171` | `findLegacyMapping(...).orElse(md)` | benign |
| `ModelJoinNesting.java:73-75`, `:87` | `orElse(null)/orElse(false)` guards that fall through to a loud error | benign |
| remaining ~28 `orElse(null)` | "is this a view? / is this a class? / is this an association?" branch probes | benign |
| `MissProbe.knownMiss` (10 sites) | the censused `findClass(...).orElse(null)`; the class doc admits 9 of them are a real name-resolution gap ("a temporal superclass referenced by bare name would silently contribute nothing") | **latent silent hole, self-declared** |

**Worst offenders, in order:** `RelOpTranslator.java:110` (wrong rows, no diagnostic) >
`MappingNormalizer.java:2400/2380/2476` (the whole compatibility check is a fall-through) >
`MappingNormalizer.java:326` (user-model errors deferred out of `compileModel`) >
`MappingNormalizer.java:1673` (`inferMainTableQuiet` swallows a `ModelException`) >
`MappingNormalizer.java:3035` (enum-value validation disappears on a lookup miss).

---

## VERIFIED SOUND — phase ordering and model immutability (deliverable 8)

* **The normalizer never runs after the typer.** `grep -rn "ModelNormalizer\.\|MappingNormalizer.normalize"` over
  `core/src/main/java` outside the package returns exactly two hits, both in `Compiler`:
  `Compiler.java:230` (`buildModel`: `NameResolver.resolve` → `ModelNormalizer.normalize` →
  `PureModelContext.from`) and `Compiler.java:264` (`buildModule`, same order). No call site exists
  in `com.legend.compiler.spec` (G), `com.legend.resolver` (H), `com.legend.lowering` (I) or
  `com.legend.sql` (J).
* **It does not mutate the model.** `/tmp/A18/NoMutate.java`:
```
input elements count before=7 after=7
input ParsedModel UNCHANGED by normalize: true
normalize is a pure function (run1 == run2): true  (n1=10 n2=10)
poisons run1={} run2={}
-- and on the larger fixture: before=11 after=11, UNCHANGED true, pure true (18/18)
```
  `adoptAssociationDerivedProperties` (E.0) rebuilds `ClassDefinition`/`ParsedModel` records rather
  than mutating them; `ModelBuilder.mappingPoisons` / `mixedUnions`
  (`compiler/ModelBuilder.java:379,383`) are mutable, but they are diagnostic sinks on a builder
  created fresh inside `ModelNormalizer.normalize`, not model state.

---

## VERIFIED SOUND (other checks)

* **`castAsDeclared` on numeric-over-string does convert in SQL** at execution — `CAST(V AS BIGINT)`,
  `CAST(V AS DOUBLE)` — and fails loudly on unparseable text (`Could not convert string 'abc' to INT64`).
* **The loud arm of the coercion table is genuinely loud** and reports the property and class:
  StrictDate←VARCHAR, StrictDate←TIMESTAMP, DateTime←VARCHAR, Integer←BIT, Byte←VARCHAR,
  Variant←INTEGER all produce
  `TypeInferenceException: in function 'm::M$class$m::C': property 'v' of 'm::C': expected X, got Y`.
* **Enum mapping value validation**: a mapped value the enumeration does not declare is rejected
  (`MappingNormalizer.java:3037-3043`), and an unknown `EnumerationMapping` id / an ambiguous
  anonymous reference are both loud (`MappingNormalizer.java:3012-3030`).
* **`AssociationMapping` naming an unknown association** is loud:
  `ModelException: [7:1] AssociationMapping references unknown association 'm::NoSuchAssoc'`.
* **`~mainTable` inference across disagreeing tables** is loud:
  `Can't find the main table for class 'm::C': property mappings span tables [T, U]. Please specify
  a main table using the ~mainTable directive.` (the *quiet* variant at :1669 is the exception).
* **Ambiguous multi-path table references** are loud (`RelOpTranslator.ambiguousTableRef`, :190-197)
  when the pipeline registered the table as ambiguous.
* **View computed columns are type-inferred** through the ordinary Typer and every mismatch against
  the declared property type is caught (see the 16 view cases above).
* **`~groupBy` structure checks** are loud: a per-row formula that is neither a key nor an aggregate,
  a multi-arg aggregate, a non-key join column, and an unsupported PM kind under `~groupBy` all
  throw with a specific message (`GroupBySynthesis.java:162-170` unsupported PM, `:175-182` orphan formula, `:198-206` multi-arg aggregate, `:110-119` non-key join column, `:86-94` navigate shape).
* **Union member-set validation** is loud: a member that is not a Relational/Relation set, a member
  mapping an unrelated class (`isSubclassOf` check, `UnionSynthesis.java:416-426`), an empty member list, a JSON-source
  member, and a member with no inferable main table all throw.
* **View recursion** is caught (`ViewRelation.java:88-92`, cyclic view-on-view) and a view over more
  than one root table is rejected (`:527-532`).
* **M2M (`Class: Pure { ~src S }`) deliberately does NOT auto-wrap in `trustOne`** — the user must
  write the coercion, matching real pure (`MappingNormalizer.java:3336-3340`), verified in the
  emitted body `map(getAll(m::Src), {src | ^m::Tgt(tid=$src.sid, tname=$src.sname)})`.
* **`~primaryKey` is not lowered into the body** (verified in the dump: `pk=[ID]` on the
  `ClassBinding`, no `distinct` in the function) — matching the stated engine semantics. Note only
  plain `ColumnRef` entries are recorded (`declaredPrimaryKeyColumns` :2317-2330); an expression
  primary key contributes nothing, silently.
* **The `~distinct` narrowing** projects exactly the mapped columns (plus slots) before dedup, as
  documented, verified in the emitted body.

---

## NOT COVERED

* **Milestoning / temporal class mappings** (`isTemporalClass`, `isBitemporalClass`, temporal
  window conditions in `JoinChainEmission`) — large surface, not exercised; a temporal superclass
  referenced by bare simple name is a self-declared silent hole (`MissProbe` class doc).
* **`ImplicitInheritance`, `M2mRouteGuards`, `ModelJoinNesting`, `RelationReads`,
  `StoreSubstitutionRewrite`** — read for fallback patterns (results in the census) but not
  exercised end-to-end with executed queries.
* **`OtherwiseEmbedded` / `InlineEmbedded` / `aggregationAwareMain`** — code read, not executed.
* **`Relation { ~func }` (RelationFunction) mappings and the JSON `sourceUrl` path** — emission
  shape read from source and included in the rule table, but not run end-to-end.
* **XStore over PURE (M2M) ends** (`XStorePureEnds` route A) — shape read from source; only the
  Relational/Relational XStore was executed.
* **Dialects other than DuckDB.** Every execution result above is DuckDB. H2/SQLite were not used,
  so the coercion outcomes that depend on the database's `CAST` semantics (Boolean-from-string,
  Integer-from-decimal rounding, `parseDecimal`'s DECIMAL(5,2)) may differ there — which is itself
  a reason the coercions should not be delegated to the dialect.
* I sampled 36 (property type × column type) pairs rather than the full cross product
  (13 Pure primitives × 22 `RelationalDataType` variants = 286). The 36 cover every *kind pair*
  reachable in `coerceColumnToDeclared`'s decision table plus the boundary values the brief named.
