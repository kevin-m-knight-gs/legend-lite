# A01 — `Type.java` / `ExprType.java` (the Type algebra) — adversarial audit

Scope: `core/src/main/java/com/legend/compiler/element/type/Type.java` (616 lines),
`.../ExprType.java`. Everything below is either a quoted `file:LINE` or a probe I ran
with its **pasted actual output**. All probes are in `/tmp/a01/*.java`, run through
`/home/user/probe/jrun.sh`. Nothing under `/home/user/legend-lite` was modified.

---

## FINDINGS

### [UNSOUND] Branch/collection LUB over two `PrecisionDecimal`s returns the *second* operand verbatim — the declared precision/scale is violated by the runtime value

**Evidence.** `Type.PrecisionDecimal` supplies no join. `InferenceKernel.commonSupertype`
(the only join) collapses both sides through `nominalFqn` (`InferenceKernel.java:1334`:
`case Type.PrecisionDecimal pd -> pd.basePrimitive().qualifiedName()`), so two decimals of
any shape both become `meta::pure::metamodel::type::Decimal`, and then:

```java
// InferenceKernel.java:1294-1296
if (ctx.isSubtype(fa, fb)) {
    return b;
}
```

`ctx.isSubtype(Decimal, Decimal) == true`, so `b` — an arbitrary side — is returned.
`IfChecker.java:68-70` calls `commonSupertype(then, else)`, so the **else** arm's shape wins.

**Repro 1 (unit, `/tmp/a01/Lub.java`).** Actual output:

```
commonSupertype(Decimal(20,5) , Decimal(10,2) ) = Decimal(10,2)
commonSupertype(Decimal(10,2) , Decimal(20,5) ) = Decimal(20,5)
commonSupertype(Decimal       , Decimal(10,2) ) = Decimal(10,2)
commonSupertype(Decimal(10,2) , Decimal       ) = Decimal
commonSupertype(Decimal(38,18), Decimal(38,0) ) = Decimal(38,0)
commonSupertype(Integer       , Decimal(10,2) ) = Number
ctx.isSubtype(Decimal,Decimal) = true
```

Not a join, not even commutative. `commonSupertype(Decimal, Decimal(10,2)) = Decimal(10,2)`
is backwards — bare `Decimal` is the *super*type.

**Repro 2 (end-to-end, `/tmp/a01/Eq2.java`, DuckDB).** Table
`T(AMT DECIMAL(10,2) NOT NULL, B DECIMAL(20,5) NOT NULL)`, row `(1.25, 12345678901234.56789)`:

```
#>{store::DB.T}#->select(~[AMT,B])->extend(~[z:r|if(true, |$r.B, |$r.AMT)])
   AMT declared=Decimal(10,2) actual=1.25[p=3,s=2]
   B   declared=Decimal(20,5) actual=12345678901234.56789[p=19,s=5]
   z   declared=Decimal(10,2) actual=12345678901234.56789[p=19,s=5]      <-- UNSOUND

#>{store::DB.T}#->select(~[AMT,B])->extend(~[z:r|if(false, |$r.B, |$r.AMT)])
   z   declared=Decimal(10,2) actual=1.25000[p=6,s=5]                     <-- scale claim violated

#>{store::DB.T}#->select(~[AMT,B])->extend(~[z:r|if(true, |$r.AMT, |1.5d)])
   z   declared=Decimal(38,1) actual=1.25[p=3,s=2]                        <-- scale claim violated
```

`z` is statically `Decimal(10,2)` (max 8 integer digits, 2 fractional) while the cell holds
14 integer digits and 5 fractional. Anything downstream that trusts `(p,s)` — wire encoding,
DDL emission (`PureSql.java:106` `new SqlType.Decimal(d.precision(), d.scale())`), rounding,
a `CAST(... AS DECIMAL(10,2))` — corrupts the value.

**Why it matters.** Top-prize category: compile-time type contradicted by the runtime value,
on a two-line query a user would plausibly write.

---

### [UNSOUND] `RelationType.pivotColumnType` matches the aggregate template with `lastIndexOf` — a template whose name is a *suffix* of another silently steals it, giving the column the wrong Pure type

**Evidence.** `Type.java:491-503`:

```java
int sep = name.lastIndexOf(PIVOT_SEPARATOR);
if (sep >= 0 && !dynamicColumns().isEmpty()) {
    String template = name.substring(sep + PIVOT_SEPARATOR.length());
    return dynamicColumns().stream()
            .filter(c -> c.name().equals(template)).findFirst()
            .map(Column::type)
            .orElseThrow(...);
}
```

Only the text after the **last** separator is considered a template name. If one aggregate
is named `b` and another `a__|__b`, the column `2011__|__a__|__b` resolves to template `b`.

**Repro (`/tmp/a01/Pv3.java`, DuckDB).** Actual output:

```
=== baseline two aggs
   COL city : STRING
   COL '2011__|__b' : INTEGER
   COL '2011__|__z' : FLOAT
   ROW [SAN :: java.lang.String] [2000 :: java.math.BigInteger] [2.25 :: java.lang.Double]

=== MISDIRECTED template: 'a__|__b' suffix collides with template 'b'
   COL city : STRING
   COL '2011__|__b' : INTEGER
   COL '2011__|__a__|__b' : INTEGER          <-- declared INTEGER
   ROW [SAN :: java.lang.String] [2000 :: java.math.BigInteger] [2.25 :: java.lang.Double]
                                              ^^^^ actual cell is a java.lang.Double
```

Query: `#TDS city,year,trees,coeff ...#->pivot(~[year], ~[b:x|$x.trees:y|$y->plus(),
'a__|__b':x|$x.coeff:y|$y->plus()])`.

**Why it matters.** The result column is stamped `Integer` while the JDBC cell is a `Double`.
Any consumer that switches on `pureType()` (wire encode, `Executor.shapeRow`'s
`instanceof Type.Primitive` gate at `Executor.java:797`, CSV render) sees the wrong kind.

---

### [UNSOUND] The whole `PrecisionDecimal` arithmetic algebra is dead code — aggregates keep the *input* precision, so `sum(Decimal(10,2))` is declared `Decimal(10,2)` and returns 11 digits

**Evidence.** `plus/minus/times/dividedBy/adjust` (`Type.java:200-242`), `DEFAULT_DECIMAL`
(`:175`) and `MIN_ADJUSTED_SCALE` (`:185`) have **zero** production callers. Repo-wide grep
(all modules, `/target/` and `core/src/test` excluded):

```
$ grep -rn --include=*.java "DEFAULT_DECIMAL\|MIN_ADJUSTED_SCALE\|\.dividedBy(\|PrecisionDecimal(" . | grep -v /target/ | grep -v core/src/test
core/src/main/java/com/legend/compiler/spec/TdsChecker.java:169
core/src/main/java/com/legend/compiler/spec/TdsChecker.java:224
core/src/main/java/com/legend/compiler/spec/Typer.java:1818
core/src/main/java/com/legend/compiler/spec/Typer.java:3184
core/src/main/java/com/legend/compiler/element/StoreCompiler.java:192
core/src/main/java/com/legend/compiler/element/StoreCompiler.java:193
core/src/main/java/com/legend/compiler/element/type/Type.java:158,175,185,221,229,237,240,241
```

The only `.plus(/.times(/.dividedBy(` call sites in the whole repo are in
`core/src/test/java/com/legend/compiler/element/type/PrecisionDecimalArithmeticTest.java`.
Instead, `InferenceKernel.primitiveFqn` (`:1395-1405`) collapses `PrecisionDecimal → Decimal`
before every overload check, so arithmetic is typed by the bare Pure signature.

**Repro (`/tmp/a01/Dec3.java`, DuckDB).** `AMT DECIMAL(10,2) NOT NULL`, three rows of
`99999999.99`:

```
Q: #>{store::DB.T_MONEY}#->groupBy(~[GRP], ~[total:x|$x.AMT:y|$y->plus()])
   DECLARED GRP:Integer  total:Decimal(10,2)
   ACTUAL   1 (Integer)  299999999.97 [prec=11,scale=2] (BigDecimal)     <-- 11 > 10

Q: #>{store::DB.T_MONEY}#->extend(~[s:r|$r.AMT + $r.AMT])
   DECLARED ... AMT:Decimal(10,2)  s:Decimal                             <-- (p,s) erased
   ACTUAL   ... 199999999.98 [prec=11,scale=2] (BigDecimal)

Q: #>{store::DB.T_MONEY}#->extend(~[m:r|$r.AMT * $r.F])                  (F DECIMAL(5,4))
   DECLARED ... m:Decimal                                                <-- (p,s) erased
   ACTUAL   ... 123449999.987655 [prec=15,scale=6] (BigDecimal)
```

`Type.PrecisionDecimal.times((10,2),(5,4))` would have derived `(16,6)`; nothing calls it.

**Why it matters.** Both an UNSOUND (aggregate result exceeds its declared precision) and an
INFORMATION LOSS across the Phase-G boundary (`Decimal(10,2) + Decimal(10,2) → Decimal`).

---

### [UNSOUND] A `#TDS` column annotated `:Decimal` is typed `Decimal(38,0)` — scale 0 — while its cells carry fractions

**Evidence.** `TdsChecker.java:169` `case "Decimal" -> new Type.PrecisionDecimal(
Type.PrecisionDecimal.MAX_PRECISION, 0);` and the inferred path `TdsChecker.java:224`
(the `21d`/`41.0d` suffix rule) does the same.

**Repro (`/tmp/a01/Dec4.java`).**

```
TDS Decimal-annotated col
   G: Relation<(x:Decimal(38,0)[1])> [1]
   E: Tabular[columns=[Column[name=x, pureType=PrecisionDecimal[precision=38, scale=0], ...]],
      rows=[Row[values=[1.25]], ...
```

Declared 0 fractional digits, cell `1.25`.

---

### [UNSOUND] A decimal literal wider than 38 integer digits is typed `Decimal(38,s)` and comes back as a `java.lang.Double`

**Evidence.** `Typer.decimalType` (`Typer.java:3178-3185`) validates only the **scale**:

```java
int scale = Math.max(0, value.scale());
if (scale > Type.PrecisionDecimal.MAX_PRECISION) { throw ... }
return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
```

The literal's own `precision()` is never compared against `38 - scale`.

**Repro (`/tmp/a01/Tn2.java`, DuckDB).**

```
1234567890123456789012345678901234567890.5d
   -> declared=PrecisionDecimal[precision=38, scale=1]  value=1.2345678901234568E39  class=java.lang.Double
12345678901234567890123456789012345678.5d
   -> declared=PrecisionDecimal[precision=38, scale=1]  value=1.2345678901234568E37  class=java.lang.Double
1.5d
   -> declared=PrecisionDecimal[precision=38, scale=1]  value=1.5  class=java.math.BigDecimal [prec=2,scale=1]
```

Declared `Decimal(38,1)` (≤ 37 integer digits); the literal has 38–40 integer digits, the
runtime class silently changes from `BigDecimal` to `Double`, and ~22 significant digits are
lost. A 41-digit literal instead leaks a raw JDBC error:
`SQLException: Conversion Error: Could not cast value 12345678901234568469964621180726096691200.000000 to DECIMAL(38,0)`.

---

### [CRASH/ICE] `DECIMAL(2,5)` in a `###Relational` table throws the compact constructor's `IllegalArgumentException` out of Phase F

**Evidence.** `StoreCompiler.java:192-193` builds `new Type.PrecisionDecimal(d.precision(),
d.scale())` straight from the parsed DSL, with no validation; `Type.java:164-167` throws.

**Repro (`/tmp/a01/Store.java`).** Model: `Database store::DB ( Table T ( ID INTEGER PRIMARY KEY, X DECIMAL(2,5) ) )`,
query `#>{store::DB.T}#`:

```
DECIMAL(10,2)          -> Relation<(ID:Integer[1], X:Decimal(10,2)[0..1])>
DECIMAL(2,5)           -> java.lang.IllegalArgumentException: scale must be in [0, precision], got scale=5, precision=2 @ com.legend.compiler.element.type.Type$PrecisionDecimal.<init>(Type.java:165)
DECIMAL(100,50)        -> Relation<(ID:Integer[1], X:Decimal(100,50)[0..1])>
DECIMAL(2000000000,0)  -> Relation<(ID:Integer[1], X:Decimal(2000000000,0)[0..1])>
NUMERIC(3,7)           -> java.lang.IllegalArgumentException: scale must be in [0, precision], got scale=7, precision=3 @ ...Type.java:165
DECIMAL(0,0)           -> Relation<(ID:Integer[1], X:Decimal(0,0)[0..1])>
NUMERIC(40,39)         -> Relation<(ID:Integer[1], X:Decimal(40,39)[0..1])>
```

A raw `java.lang.IllegalArgumentException` (not a `ModelException`) for a user typo in a
model file. **Also visible here:** `MAX_PRECISION` is *not* enforced — `DECIMAL(100,50)` and
`DECIMAL(2000000000,0)` are accepted, contradicting the `MAX_PRECISION`/`38` invariant the
javadoc claims (`Type.java:177-178`).

---

### [CRASH/ICE] A pivot aggregate column whose name contains `__|__` escapes as `IllegalStateException` from `Type.java:498`

**Evidence.** Same `lastIndexOf` rule; the `orElseThrow` at `Type.java:498-502` is on the
**execution egress**, called from `Executor.java:860 ← :766 ← :700`.

**Repro (`/tmp/a01/Pv.java`, DuckDB).** Actual output:

```
=== agg name with separator
    q=#TDS   city, year, treePlanted   NYC, 2011, 5000 ... #->pivot(~[year], ~['a__|__b':x|$x.treePlanted:y|$y->plus()])
  java.lang.IllegalStateException: pivot column '2011__|__a__|__b' matches no aggregate template [a__|__b]
      at com.legend.compiler.element.type.Type$RelationType.lambda$pivotColumnType$2(Type.java:502)
      at java.base/java.util.Optional.orElseThrow(Optional.java:403)
      at com.legend.compiler.element.type.Type$RelationType.pivotColumnType(Type.java:498)
      at com.legend.exec.Executor.pivotColumnType(Executor.java:860)
      at com.legend.exec.Executor.resolveColumns(Executor.java:766)
      at com.legend.exec.Executor.tabular(Executor.java:700)
```

The same throw is also reachable on a **late-bound** schema, where "trust the name" is
supposed to apply (`/tmp/a01/Rel.java`):

```
--- 3e late-bound schema + separator name
  isLateBound=true typeName=()
  lb.pivotColumnType("anything") -> null
  lb.pivotColumnType("a__|__b") -> THREW IllegalStateException: pivot column 'a__|__b' matches no aggregate template [*]
```

`pivotColumnType` never consults `isLateBound()`, so a raw-SQL grid whose DB column happens to
contain `__|__` throws instead of being trusted.

---

### [CRASH/ICE + UNSOUND] `extend` over a late-bound raw-SQL grid mis-types the schema (schema-algebra UNION keeps the `*` wildcard in `dynamicColumns` and drops the real columns), then crashes with `IndexOutOfBoundsException`

**Evidence.** `Type.RelationType.lateBound()` (`Type.java:440-445`) encodes "unknown columns"
as `columns=[] , dynamicColumns=[*: Any[0..1]]`. `InferenceKernel.resolveSchemaAlgebra`'s
UNION arm (`InferenceKernel.java:813-828`) computes `cols` from `lr.columns()` (empty) and
then `return new Type.RelationType(cols, lr.dynamicColumns());` — carrying the `*` template
forward while producing a schema of exactly one column. `isLateBound()` (`Type.java:449-453`)
now returns false because `columns()` is non-empty, so the executor's late-bound branch
(`Executor.java:726`) is skipped.

**Repro (`/tmp/a01/Raw2.java`, DuckDB, `executeInDb('select 1 as A, 2 as B', $c, 0, 1000)`):**

```
G raw grid          | Relation<()>             lateBound=true  dyn=[Column[name=*, type=ClassType[...Any], multiplicity=[0..1]]]
G raw grid + extend | Relation<(z:Integer[1])> lateBound=false dyn=[Column[name=*, type=ClassType[...Any], multiplicity=[0..1]]]
E raw grid          | Tabular[columns=[Column[name=A, pureType=...Any], Column[name=B, ...
E raw grid + extend | ERR java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1 @ com.legend.sql.SqlTyping.reconcileLabels(SqlTyping.java:133)
E raw + extend + select | ERR java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1 @ com.legend.sql.SqlTyping.reconcileLabels(SqlTyping.java:133)
```

The compiler claims the relation has exactly one column `z`; the real result has `A, B, z`.
That is an unsound schema *and* an internal crash.

Related, same probe: `select(~[A])` over a late-bound grid is refused —
`TypeInferenceException: in call to 'select', argument 2: unknown column 'A' in ()` — because
the `⊆` constraint path (`InferenceKernel.unifyConstraint`, `:307-325`) does not gate on
`isLateBound()`, while the property-read path (`Typer.java:2931-2932`) does. The "trust-name
rule" the javadoc declares at `Type.java:507-514` is applied inconsistently.

---

### [CRASH/ICE] `PlanText.pureTypeName` has no `PrecisionDecimal` arm — every plan-text over a `DECIMAL` store column throws

**Evidence.** `plan/PlanText.java:534-567` — the chain tests `== Type.Primitive.DECIMAL`
(`:557`) and ends at `:567` `throw new NotImplementedException("plan: pure type name for " + t);`.
`PlanText.tdsTuples` calls it per column: `:424` `.append(pureName(cols.get(i).type()))`, over
`Type.relationSchema(last.info().type())` (`:114-120`), whose columns are `PrecisionDecimal`
for any relation accessor over a `DECIMAL`/`NUMERIC` column (`StoreCompiler.java:192-193`).

**Repro (`/tmp/a01/Dec2.java`).**

```
#>{store::DB.T_MONEY}#  | Relation<(ID:Integer[1], AMT:Decimal(10,2)[0..1], BIG:Decimal(38,18)[0..1])> [1]
PlanText.pureTypeName(Decimal) = Decimal
PlanText.pureTypeName(Decimal(10,2)) THREW com.legend.error.NotImplementedException: plan: pure type name for PrecisionDecimal[precision=10, scale=2]
PlanText.pureTypeName(Decimal(38,18)) THREW com.legend.error.NotImplementedException: plan: pure type name for PrecisionDecimal[precision=38, scale=18]
```

---

### [SILENT FALLBACK] `TypeClassifier.classify` accepts *any* unknown generic head with no existence check, and silently drops the type-variable values

**Evidence.** `compiler/element/TypeClassifier.java:101-107`:

```java
case TypeExpression.Generic g -> {
    List<Type> args = new ArrayList<>(g.arguments().size());
    for (TypeExpression arg : g.arguments()) { args.add(classify(arg, typeParams)); }
    yield new Type.GenericType(g.name(), args);
}
```

No `findType`, no `isClassFqn` — unlike the `NameRef` arm three lines above, which throws
`ModelException("Unknown type: ...")` (`:98-99`). The parser's `typeVariableValues`
(`Generic.typeVariableValues`) are never consulted, so `(200)` / `(10,2)` / `(9)` vanish.

**Repro (`/tmp/a01/Pp2.java`, `/tmp/a01/Pp3.java`, `/tmp/a01/Pp4.java`).**

```
=== Varchar(200)      OK model compiled
=== Varchar bare      com.legend.error.ModelException: Unknown type: 'meta::pure::precisePrimitives::Varchar' ...
=== Numeric(10,2)     OK model compiled
=== Timestamp         com.legend.error.ModelException: Unknown type: 'meta::pure::precisePrimitives::Timestamp' ...
=== bogus             com.legend.error.ModelException: Unknown type: 'meta::pure::precisePrimitives::Bogus' ...
```

```
=== Varchar(200)   parameters=[TypedParameter[name=v, type=GenericType[rawFqn=meta::pure::precisePrimitives::Varchar, arguments=[]], ...]]
=== Numeric(10,2)  parameters=[TypedParameter[name=v, type=GenericType[rawFqn=meta::pure::precisePrimitives::Numeric, arguments=[]], ...]]
=== BogusGeneric(9) parameters=[TypedParameter[name=v, type=GenericType[rawFqn=totally::made::Up, arguments=[]], ...]]
```

Note the asymmetry: `Varchar` alone is a loud error, `Varchar(200)` is accepted; a fabricated
`totally::made::Up(9)` is accepted. The functions are then permanently uncallable:

```
z::f('hi')     G-ERR TypeInferenceException: in call to 'z::f', argument 1: expected Varchar<>, got String
z::g('x')      G-ERR TypeInferenceException: in call to 'z::g', argument 1: expected Up<>, got String
z::h(3)        G=Integer       Scalar[value=3, returnType=INTEGER]      (Int aliases fine)
```

`Varchar(200)` is not hypothetical: it appears in this repo's own reference fixtures
(`parser-equivalence/.../ProbeWireShapes.java:502,680`) and in the engine goldens quoted at
`docs/e2e-diagnosis-2026-08-15/bucket-02.md:412,427,2107`.

---

### [SILENT FALLBACK] Schema algebra silently treats a non-`RelationType` right operand as the empty schema

**Evidence.** `InferenceKernel.java:815` (UNION) and `:832` (DIFFERENCE):

```java
if (right instanceof Type.RelationType rr) { ... }        // else: nothing added / nothing dropped
```

The left operand is checked loudly (`:806-810` throws "schema-algebra left operand is not a
relation"); the right is not. `T+V` with a non-schema `V` returns `T` unchanged and `T-Z` with
a non-schema `Z` drops nothing — a guessed answer where the repo forbids one.

---

### [INFORMATION LOSS] The `precisePrimitives` alias table covers 10 names; the string/temporal/exact-numeric families are absent, and `UBigInt → INTEGER` loses range with no check anywhere

**Evidence.** `Type.java:122-139` — the whole table:

```java
String pp = "meta::pure::precisePrimitives::";
for (String n : new String[]{"TinyInt", "UTinyInt", "SmallInt",
        "USmallInt", "Int", "UInt", "BigInt", "UBigInt"}) { m.put(pp + n, INTEGER); }
m.put(pp + "Float4", FLOAT);
m.put(pp + "Double", FLOAT);
```

There are **no** `PrimitiveExtensionDefinition`s for these in `builtin/Pure.java` either —
`grep -n "TinyInt\|SmallInt\|BigInt\|Float4\|UInt" core/src/main/java/com/legend/builtin/Pure.java`
returns nothing but unrelated `isNumeric` hits. The 10 aliases are the *entire* precise-primitive
surface; `ModelBuilder.findPrimitiveExtension` (`:398-413`) only covers **user-declared**
extensions.

**Cross-check of every candidate name (`/tmp/a01/Pp.java`), actual output:**

```
TinyInt      -> Optional[INTEGER]      Numeric      -> Optional.empty
UTinyInt     -> Optional[INTEGER]      Decimal      -> Optional.empty
SmallInt     -> Optional[INTEGER]      Varchar      -> Optional.empty
USmallInt    -> Optional[INTEGER]      Char         -> Optional.empty
Int          -> Optional[INTEGER]      Timestamp    -> Optional.empty
UInt         -> Optional[INTEGER]      Date         -> Optional.empty
BigInt       -> Optional[INTEGER]      Binary       -> Optional.empty
UBigInt      -> Optional[INTEGER]      Boolean      -> Optional.empty
Float4       -> Optional[FLOAT]        String       -> Optional.empty
Double       -> Optional[FLOAT]        Integer/Float-> Optional.empty
```

**What the caller does on empty:** two different things.
`TypeClassifier.findType` → `Optional.empty()` → `classify` throws
`ModelException("Unknown type: ...")` (`TypeClassifier.java:98`) — *unless* the reference is
parameterized, in which case the Generic arm swallows it (previous finding).
`TdsChecker.annotatedType` (`:185-188`) throws `TypeInferenceException("TDS column type 'X'
is not a known primitive")`.

**Range:** of the eight integer aliases only `UBigInt` (0 .. 2⁶⁴−1) exceeds a 64-bit Pure
`Integer`. Nothing range-checks — the alias is a plain `Map.put`, and Pure `Integer` itself
is not bounded either (`/tmp/a01/Ints.java`):

```
  9223372036854775807 + 1 -> Scalar[value=9223372036854775808, returnType=INTEGER]
  9223372036854775807 * 2 -> Scalar[value=18446744073709551614, returnType=INTEGER]
```

Also from that probe, one declared Pure `Integer` decodes as four different Java classes
depending on the physical column, so no width information survives at all:

```
  ID declared=Integer value=1 (java.lang.Integer)
  BIG declared=Integer value=9223372036854775807 (java.lang.Long)
  TINY declared=Integer value=127 (java.lang.Byte)
  SMALL declared=Integer value=32767 (java.lang.Short)
```

**`Float4` / `Double` → FLOAT:** `Double` is exact (Pure `Float` *is* a double). `Float4` is
a 32-bit single; aliasing it to the 64-bit `FLOAT` is a widening — safe on read, but the
narrowing is erased for DDL/cast emission, so a `Float4` column is indistinguishable from a
`Double` one after `findByFqn`.

---

### [INCONSISTENCY] Three implementations of the `__|__` rule, with different behaviour on the same input — the "one owner" claim is false

- `Type.RelationType.pivotColumnType` (`Type.java:477-505`) — quote-strip + `lastIndexOf`
  template match, **throws** on a no-template suffix.
- `Fold.pivotIdentity` (`lowering/Fold.java:525-530`) — an independent copy of the quote-strip.
- `Fold.pivotColumn` (`lowering/Fold.java:867-882`) — an independent copy of the `lastIndexOf`
  template match that **silently falls through** on a no-template suffix ("a suffix matching no
  template stays plain too — the census keeps counting it").

`Type.java:455-463` says "THE PIVOT-COLUMN MATCHING RULE (one owner …)". Two of the three
disagree with it on the same input class. Two quoting conventions also coexist: this rule uses
**single** quotes, while `InferenceKernel.sameColumn` / `Typer.stripColQuotes`
(`InferenceKernel.java:445-452`, `Typer.java:2597-2601`) strip **double** quotes.

---

### [INCONSISTENCY] `presentPivotName` is not injective and mutates *static* column names; a TDS column `a__|__b` and an `extend`ed column `a__|__b` end up with different spellings

**Evidence.** `Type.java:470-475` wraps any separator-bearing name in literal single quotes
unless it already starts *and* ends with one. So physical `weird__|__x` and physical
`'weird__|__x'` both present as `'weird__|__x'` — the boundary cannot tell them apart, and
`pivotColumnType`/`Fold.pivotIdentity` then strip the quotes off the genuinely-quoted one and
dereference the wrong SQL column. `/tmp/a01/Rel.java`:

```
  phys=[2011__|__newCol]     present=['2011__|__newCol']   type=INTEGER
  phys=['weird__|__newCol']  present=['weird__|__newCol']  type=INTEGER   <-- collides with phys=[weird__|__newCol]
  phys=['x'__|__newCol]      present=[''x'__|__newCol']    type=INTEGER   <-- double-wrapped
```

**Reachable spelling divergence (`/tmp/a01/Pv2.java`, `/tmp/a01/Pv.java`).**
`TdsChecker.java:67-68` applies `presentPivotName` to TDS headers, so:

```
=== TDS sep header plain
  Tabular[columns=[Column[name='a__|__b', pureType=STRING ...       <-- quoted
=== TDS sep header, filter on it
  com.legend.compiler.spec.TypeInferenceException: relation has no column 'a__|__b'   <-- unreadable by its own name
=== extend col named with separator
  ... Column[name=a__|__b, pureType=INTEGER ...                     <-- NOT quoted
=== rename to separator-bearing
  ... Column[name=a__|__b, pureType=INTEGER ...                     <-- NOT quoted
=== TDS both spellings -> duplicate collapse
  com.legend.compiler.spec.SchemaInvariantException: duplicate column ''a__|__b'' in TDS header
```

Two distinct header names (`a__|__b`, `'a__|__b'`) collapse onto one; the TDS-born column is
unaddressable from `filter`; `extend`/`rename` produce the other spelling for the same name.

---

### [INCONSISTENCY] `RelationType`'s "unique by construction" duplicate check is a raw `equals`, unlike every column comparison around it

**Evidence.** `Type.java:529-535` uses `seen.add(c.name())` (exact string), while
`InferenceKernel.sameColumn` (`:445-452`) strips surrounding `"`, `InferenceKernel`'s UNION
uses `sameColumn` (`:819`) but its DIFFERENCE uses exact `drop.contains(c.name())` (`:837`),
and `pivotColumnType` matches on single quotes. Also, a name may be simultaneously a static
column *and* a dynamic template. `/tmp/a01/Rel.java`:

```
  RelationType([a, "a"]) -> ACCEPTED: (a:String[1], "a":Integer[1])   <-- sameColumn() calls these one column
  RelationType([a, A])   -> ACCEPTED (case-sensitive)
  RelationType([a, a])   -> THREW IllegalArgumentException: duplicate column 'a' in relation type
  RelationType([a],[a])  -> (a:String[1])                            <-- static/dynamic clash allowed
```

I could not reach the `"a"` spelling from Pure source (the parser unquotes colspec names —
`/tmp/a01/Q.java` shows `~['a']`, `~['a b']` all land unquoted), so this is latent rather than
demonstrated end-to-end.

---

### [INCONSISTENCY] Row-vs-Relation has three different answers in the repo

`Type.relationValued` (`Type.java:400-409`) declares: *"A bare struct with an at-most-one stamp
is ONE row and is NOT relation-rooted."* But the three boundary consumers use `schemaView`,
which does treat it as a table:

- `exec/ResultShape.java:41` — `if (Type.schemaView(root.type()) instanceof RelationType rt) return TABULAR;`
  ("a ROW root is a one-row TABLE at the boundary")
- `exec/Executor.java:712` `tabularSchema` — same, else `IllegalStateException`
- `lowering/Lowerer.java:271` — routes a bare struct through the relation pipeline

Call-site census (main only, `Type.java` itself excluded):
`Type.relation(` 68, `isRelation(` 48, `relationSchema(` 45, `schemaView(` 36,
`relationValued(` 18, `requireRelationSchema(` 89.

I could not produce a query whose **root** is a bare `RelationType` with an at-most-one stamp
(the only row-producing shapes fail earlier in lowering), so today the disagreement is not
observable at the root. Root-type census (`/tmp/a01/Roots.java`) — every relation op keeps the
`Relation<…>` wrapper; only `map(r|$r)` produces a bare struct, at `[*]`:

```
#TDS ...#                    | Relation<(city:String[1], n:Integer[1])>  [1]     isRelation=true  relValued=true
#TDS ...#->first()           | Relation<(...)>                          [0..1]  isRelation=true  relValued=true
#TDS ...#->tail()            | Relation<(...)>                          [*]     isRelation=true  relValued=true
#TDS ...#->map(r|$r)         | (city:String[1], n:Integer[1])           [*]     isRelation=false schemaView=true relValued=true
#TDS ...#->map(r|$r.n)       | Integer                                  [*]     relValued=false
```

Two adjacent observations from that census, both from the type algebra ignoring multiplicity:

- `isRelation`/`relationSchema`/`requireRelationSchema` never look at the stamp, so
  `Relation<T>[*]` (a *collection of tables*, which `tail()` produces) is indistinguishable
  from `Relation<T>[1]`.
- A bare row leaking into a *column* type is accepted by `RelationType` and then crashes the
  lowerer with internal errors rather than a compile error (`/tmp/a01/Win.java`):

```
G row var itself | Relation<(grp:String[1], id:Integer[1], v:Integer[1], self:(grp:String[1], id:Integer[1], v:Integer[1])[1])> [1]
E row var itself | ERR java.lang.IllegalStateException: extend/project columns [self] reference names unresolvable even after isolation @ com.legend.lowering.Lowerer.computedColumns(Lowerer.java:1357)
G lag ROW itself | Relation<(..., prev:(grp:String[1], id:Integer[1], v:Integer[1])[0..1])> [1]
E lag ROW itself | ERR java.lang.IllegalStateException: window value function 'lag' needs a property access naming its column @ com.legend.lowering.Lowerer.windowScalar(Lowerer.java:2283)
E map row        | ERR NotImplementedException: lowering not yet implemented for TypedMap @ Lowerer.relation(Lowerer.java:703)
E filter row eq  | ERR java.lang.IllegalStateException: filter predicate references column '<whole variable>' @ Lowerer.predicateOrThrow(Lowerer.java:1519)
```

---

### [INCONSISTENCY / DOC-LIE] "Subtyping is baked in here … so callers never normalize `PrecisionDecimal → DECIMAL`" — 7 sites do exactly the normalization the javadoc says is unnecessary, and 7 others forget to

**Evidence.** `Type.java:150-156` makes the claim. Records give structural equality, so
(`/tmp/a01/Rel.java`):

```
  PrecisionDecimal(38,18).equals(Primitive.DECIMAL) = false
  Primitive.DECIMAL.equals(PrecisionDecimal(38,18)) = false
```

Sites that **do** carry the extra `instanceof Type.PrecisionDecimal` arm (i.e. the
normalization the doc says is not needed): `exec/CsvSeed.java:144-145`,
`AssertVerdicts.java:873-874, 891-892`, `lowering/Scalars.java:2926`,
`lowering/LiteralSpelling.java:68-70, 110-112`, `lowering/DecimalKindRules.java:84-85`,
`lowering/MixedEncoding.java:134, 194-195, 435-438`, `lowering/Numerics.java:49-50`,
`lowering/CastPolicy.java:219`, `compiler/spec/InferenceKernel.java:577-580`.

Sites that test `== Type.Primitive.DECIMAL` with **no** `PrecisionDecimal` arm — each a
behaviour fork between a bare `Decimal` and a `Decimal(p,s)`:

| site | consequence for a `PrecisionDecimal` |
|---|---|
| `plan/PlanText.java:557` | falls through to the `throw` at `:567` (see CRASH above) |
| `lowering/Repr.java:51` | `format('%r', x)` omits the `D` suffix a bare `Decimal` gets |
| `lowering/DateCtorRule.java:100` | `date(y,m,d,h,mi,decimalSeconds)` loses sub-second precision |
| `lowering/Scalars.java:3467` | same predicate, `PureDateLiteral.Precision.SECOND` instead of `SUBSECOND` |
| `lowering/CastPolicy.java:144` | the "cast-to-Decimal keeps the value's own scale" rule does not fire |
| `lowering/CanonicalRenderSql.java:224` | a `Number` refinement list that cannot name a `PrecisionDecimal` |
| `compiler/spec/Typer.java:1815` | `refineDecimalCarrier` only fires on a bare `DECIMAL` |

The `PrecisionDecimal` case is also structurally unequal to *itself at another shape*
(`Decimal(38,1)` vs `Decimal(38,2)`), which is what makes the LUB finding above bite.
`FoldChecker.java:164` (`t instanceof PrecisionDecimal ? Primitive.DECIMAL : t`) is a fifth
hand-rolled normalization copy — precisely the "5 scattered normalization copies" scar the
javadoc claims was fixed.

---

### [DEAD] `plus` / `minus` / `times` / `dividedBy` / `adjust` / `DEFAULT_DECIMAL` / `MIN_ADJUSTED_SCALE`: zero production callers

Grep evidence above. The implementations themselves are **correct against Spark** — I wrote a
brute-force reference (`refAdjust/refPlus/refTimes/refDiv`, Spark 3.x `DecimalPrecision` with
`allowPrecisionLoss=true`) and enumerated the **whole** (p,s)×(p,s) space, p∈0..38, s∈0..p —
608,400 ordered pairs per operator (`/tmp/a01/Dec1.java`):

```
pairs=608400 throwsPlus=0 throwsMinus=0 throwsTimes=0 throwsDiv=0 mismatchesVsRef=0
```

So: no throw, no negative scale, no `scale > precision` violation, and no divergence from
Spark's `adjustPrecisionScale` anywhere in the declared domain. `minus` delegating to `plus`
is also **correct** — Spark's `Add` and `Subtract` share the rule, verified exhaustively
(`/tmp/a01/Dec5.java`): `minus==plus everywhere: true`.

The inherent (Spark-faithful, but silent) precision loss census over the same space:

```
pairs=606841   (p>=1)
times  : intDigitOverflow=173521  scaleTruncated=485478   e.g. (1,0)*(38,0) -> Decimal(38,0) needs 39 int digits, has 38
plus   : intDigitOverflow=36757   scaleTruncated=234832   e.g. (1,0)+(38,0) -> Decimal(38,0) needs 39 int digits, has 38
div    : intDigitOverflow=175453  scaleTruncated=554501   e.g. (1,0)/(32,32) -> Decimal(38,6) needs 33 int digits, has 32
```

i.e. 28.6% of `times` pairs produce a type that *provably* cannot hold the largest product.
This is Spark's documented behaviour, not a defect of this port — noted for completeness.

---

### [LOW] `int` overflow in the arithmetic once precision exceeds the (unenforced) 38 cap

Because the constructor accepts any `precision >= 0` (`Type.java:161-163`) — and
`StoreCompiler` feeds it straight from the DSL (see CRASH above) — the internal `+` in
`times` (`Type.java:215`) and `plus` (`:204`) overflows. `/tmp/a01/Dec5.java`:

```
  (2147483647,0) times itself -> THREW IllegalArgumentException: precision must be >= 0, got -1
  (2147483647,0) plus  itself -> THREW IllegalArgumentException: precision must be >= 0, got -2147483648
  (2147483647,0) div   itself -> THREW IllegalArgumentException: precision must be >= 0, got -2147483643
  (2000000000,0) times itself -> THREW IllegalArgumentException: precision must be >= 0, got -294967295
  (2000000000,0) plus  itself -> PrecisionDecimal[precision=38, scale=0]
```

Currently unreachable *only* because the arithmetic is dead code.

---

### [LOW] `typeName()` is not injective — it cannot be used for comparison or keying, and several sites do

**Collision census (`/tmp/a01/Rel.java`), actual output:**

```
  ClassType(a::B)              vs EnumType(a::B)                sameName=true  equals=false
  ClassType(Integer)           vs Primitive(Integer)            sameName=true  equals=false
  TypeVar(Integer)             vs Primitive(Integer)            sameName=true  equals=false
  GenericType(List<String>)    vs GenericType(List<String>)     sameName=true  equals=false   (x::List vs y::List)
  ClassType(Decimal(38,18))    vs PrecisionDecimal(Decimal(38,18))  sameName=true  equals=false
  RelationType(())             vs ClassType(())                 sameName=true  equals=false
  GenericType(Relation<(a:String[1])>) vs GenericType(Relation<(a:String[1])>)  sameName=true  equals=false
  TypeVar(T)                   vs ClassType(T)                  sameName=true  equals=false
  same columns, different dynamicColumns: equals=false sameTypeName=true [(a:String[1])]
```

Structural causes: `Primitive.typeName` (`:116-120`) and `GenericType.typeName` (`:307-314`)
take the **simple name** after the last `::` (so package identity is gone), while `ClassType`/
`EnumType` (`:261-263`, `:272-274`) return the **full FQN** (so kind identity is gone), and
`RelationType.typeName` (`:545-550`) omits `dynamicColumns` entirely.

**Sites that compare or key on it:**

- `compiler/spec/Typer.java:2589-2593` `simpleTypeName(t)` = `t.typeName()` cut at the last
  `::` — this is the `.columns.type` fold. Confirmed leak (`/tmp/a01/Dec4.java`):
  `#TDS x:Decimal, y:Integer …#.columns.type` → `Collection[values=[Decimal(38,0), Integer]]`,
  whereas the type-*value* channel (`lowering/Lowerer.java:2811-2818`,
  `simpleName(g.arguments().get(0).typeName())`) and `plan/PlanText.pureName` both spell
  `Decimal`. A `columns.type == [Decimal, …]` comparison therefore fails. Two classes
  `a::B` and `c::B` also both fold to `B`.
- `compiler/spec/Typer.java:2304-2307`
  `f.returnType().typeName().endsWith(String.valueOf(ret))` — signature demangling by
  **suffix** of a `typeName()`. For nominal returns `typeName()` is the FQN, so `endsWith("Person")`
  matches `model::Person`, `other::SuperPerson`, `x::NotAPerson` alike. This is the very
  suffix-match pattern `ModelBuilder.findPrimitiveExtension`'s own comment (`:391-395`) calls
  "the banned suffix-match pattern".
- `AssertVerdicts.java:347, 375-382, 1073-1074` — type identity via `typeName()` and a
  `switch` over the nine bare primitive spellings. A `PrecisionDecimal` never matches
  (`"Decimal(38,2)"` is not `"Decimal"`) and returns `null`; a `TypeVar("Integer")` matches
  and is canonicalized to the M3 `Integer` FQN.
- `lowering/Render.java:834-841` `pctTypeName` — falls back to `t.typeName()` for anything
  that is not a `Primitive` or `Variant`, so a PCT/TDS header prints `Decimal(38,0)`.

---

### [LOW] `isLateBound()` is keyed on the template *name* only

`Type.java:449-453` checks `columns().isEmpty() && dynamicColumns().size()==1 &&
name.equals("*")` — not the type or multiplicity. `/tmp/a01/Rel.java`:

```
--- 3f isLateBound false-positive: a REAL relation with one dynamic col named *
  isLateBound=true (type of the '*' template is INTEGER, not Any)
```

A pivot whose sole aggregate is named `*` over a zero-key group-by would be mistaken for a
raw-SQL grid. Also, `lateBound().typeName()` is `()` — indistinguishable from an empty
relation in every error message and in `PlanText`/`Render` output.

---

### [LOW] `GenericType` with a null argument raises a bare `NullPointerException` from `List.copyOf`

`Type.java:300-304` `Objects.requireNonNull`s `rawFqn` and `arguments` (the list) but not its
elements; `List.copyOf` then throws an unnamed NPE. `/tmp/a01/Rel.java`:
`GenericType with null arg -> THREW NullPointerException: null`. `FunctionType`
(`:323-327`) and `RelationType` (`:520-524`) have the same hole. `GenericType`'s copy is
otherwise correct — `new GenericType("R", new ArrayList<>(List.of(STRING)))
.equals(new GenericType("R", List.of(STRING)))` is `true`.

---

### [DOC-LIE] Three prose claims in `Type.java` contradicted by the code

1. `:150-156` "Subtyping is baked in here … so callers never normalize `PrecisionDecimal →
   DECIMAL` … the explicit fix for the engine scar where the legacy form required 5 scattered
   normalization copies." → 14+ call sites hand-roll the normalization or forget it (table above).
2. `:455-463` "THE PIVOT-COLUMN MATCHING RULE (one owner …)" → three implementations
   (`Type.pivotColumnType`, `Fold.pivotIdentity`, `Fold.pivotColumn`) that disagree.
3. `:177-178` "`MAX_PRECISION` — Widest SQL `DECIMAL` precision compatible with a 128-bit
   backing integer" → the constructor does not enforce it; `DECIMAL(2000000000,0)` compiles.

---

## VERIFIED SOUND

- **`PrecisionDecimal.plus/minus/times/dividedBy/adjust` are arithmetically correct.**
  Exhaustive: all 608,400 ordered (p₁,s₁)×(p₂,s₂) pairs for p∈0..38, s∈0..p, per operator,
  against an independently written Spark `DecimalPrecision` reference. Zero throws, zero
  mismatches (`/tmp/a01/Dec1.java`). Specifically ruled out: negative scale after `adjust`,
  `scale > precision` (constructor never fires in the declared domain), `MIN_ADJUSTED_SCALE`
  misuse, `int` overflow within p ≤ 38, and any `minus`-vs-`plus` scale asymmetry.
- `adjust` matches Spark's `adjustPrecisionScale` exactly, including the
  `max(38 − intDigits, min(scale, 6))` floor (both worked examples in the javadoc — `(49,15)→(38,6)`,
  `(49,5)→(38,5)` — are reproduced by the implementation).
- `RelationType`'s duplicate-column check does fire on an exact repeat, for both `columns`
  and `dynamicColumns` (`/tmp/a01/Rel.java`).
- `presentPivotName → pivotColumnType` round-trips correctly for every *well-formed* pivot
  name (no quotes in the physical name, template name separator-free): `2011__|__newCol`,
  `__|__newCol`, `a__|__b__|__newCol` all resolve to the right template; `presentPivotName`
  is idempotent on already-presented names.
- `pivotColumnType`'s null-return paths behave as documented: separator-free miss → `null`;
  separator present but `dynamicColumns` empty → `null`; static name match wins over the
  template path (so a legitimately separator-bearing static column is not mis-typed).
- `Type.relation / isRelation / relationSchema / schemaView / requireRelationSchema` agree
  with each other on all 21 root shapes I typed (`/tmp/a01/Roots.java`): every relation
  operator preserves the `Relation<…>` wrapper; a bare struct only ever appears via
  `map(r|$r)` or as a nested column type. `requireRelationSchema` is correctly loud on a bare
  struct.
- `GenericType`'s `arguments` defensive copy gives correct structural equality across
  `ArrayList`/`List.of` (`/tmp/a01/Rel.java`).
- `SchemaAlgebra` is **not** inert (item 7 answered). It is constructed by the parser
  (`parser/TokenStreamCursor.java:1089, 1096, 1101` for `=`, `+`/`-`, `⊆`), renamed in
  `NameResolver.java:470-474`, classified in `TypeClassifier.java:124-125`, and consumed in
  three live paths in `InferenceKernel`: `unifyConstraint` (`:302-327`, the `⊆` parameter
  constraint behind every `ColSpec<Z⊆T>` signature), `unifyWildcardEqual` (`:376+`, the
  `X=(?:K)` rename/flatten rule), and `resolveSchemaAlgebra` (`:805-845`, `T+V` / `T-Z`).
  Demonstrated live: `groupBy(~[city], ~[t:…])` → `Relation<(city:String[1], t:Integer[1])>`
  is `Z+R` resolved through `:813-828`; `extend` over a raw grid exercises the same arm
  (see the CRASH finding). Only `Op.EQUAL`/`Op.SUBSET` are rejected by `resolveSchemaAlgebra`
  and `Op.UNION`/`Op.DIFFERENCE` by `unifyConstraint` — a deliberate split, not dead code.
- `ExprType` (22 lines) is a plain null-checked pair with an `one()` factory; nothing to fault.
- `Type.Param.text()` / `Type.Column.text()` render as documented.

## NOT COVERED

- `PlanText`'s `NotImplementedException` on a `PrecisionDecimal` column is proven as a direct
  unit call plus the proof that such columns exist in a root schema; I did **not** drive a
  full `planToString(executionPlan(...))` Pure query to it (that path needs a mapping-rooted
  fixture; `docs/e2e-diagnosis-2026-08-15/bucket-02.md:412` documents the relation-root plan
  path as unimplemented anyway).
- The `RelationType(["a", "\"a\""])` / `T-Z` exact-vs-`sameColumn` inconsistency is proven at
  the constructor but I found no Pure source spelling that produces a `"`-wrapped column name
  (the parser unquotes colspec names), so it stays latent.
- `Executor`'s Integer/BigInteger/Byte/Short heterogeneity and the `at(5)` cardinality
  observation are outside the Type algebra; recorded as adjacent evidence only, for whoever
  owns the exec/multiplicity lane.
- I did not run the JUnit suite (`mvn` is forbidden by the brief); all evidence is from
  `jrun.sh` probes against the prebuilt `core/target/classes`.
- Multiplicity (`Multiplicity.java`) and `PlatformTypes.java` were read only where they
  intersect `Type` (`lateBound`, `trustedColumn`, `relationValued`); they are not in scope.
