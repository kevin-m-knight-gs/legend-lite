# A05 — `compiler/spec/Typer.java` (dispatch core, literals, env/vars, property access, call typing)

Scope: `core/src/main/java/com/legend/compiler/spec/Typer.java` (3186 lines), read in full.
All repros run with `/home/user/probe/jrun.sh` and `/home/user/probe/probe.sh`; every "Actual output"
block below is pasted verbatim from a run.

Harness note: a bare query string is a single expression, so multi-statement repros are written as
zero-arg lambdas `{| let x = …; …; }` (Typer's `LambdaFunction` standalone arm, Typer.java:214-268).

---

## FINDINGS

### [UNSOUND] 1. Decimal literal precision is hardcoded to 38 and never checked; over-precision literals are stamped `Decimal(38,s)` and come back as `Double`

**Evidence** — `Typer.java:3178-3185`:
```java
private static Type decimalType(BigDecimal value) {
    int scale = Math.max(0, value.scale());
    if (scale > Type.PrecisionDecimal.MAX_PRECISION) {
        throw new TypeInferenceException("decimal literal '" + value.toPlainString()
            + "' needs scale " + scale + ", exceeding the maximum of " + Type.PrecisionDecimal.MAX_PRECISION);
    }
    return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
}
```
Only **scale** is validated. `value.precision()` (total significant digits) is never consulted, and the
compact constructor `Type.java:160-169` only rejects `scale < 0 || scale > precision` — so
`PrecisionDecimal(38, 1)` is happily constructed for a value with 51 digits.

**Repro**
```
model::Person.all()->project(~[big: p | 12345678901234567890123456789012345678901234567890.5d, nm: p|$p.firstName])
/home/user/probe/probe.sh /home/user/probe/fx/model.pure q.pure test::TestRuntime /home/user/probe/fx/ddl.sql
```
**Actual output**
```
[G] type=Relation<(big:Decimal(38,1)[1], nm:String[1])> mult=[1]
[PLAN] SELECT 12345678901234567890123456789012345678901234567890.5 AS big, t0.FIRST_NAME AS nm
[EXEC-COL] big : Decimal(38,1) [PrecisionDecimal[precision=38, scale=1]] mult=[1]
[EXEC-ROW] Double(1.2345678901234567E49) | String(John) |
```
Second repro, scale exactly 38 (39 significant digits after the promotion rounding of finding 3):
```
model::Person.all()->project(~[ok: p | 0.1d, s38: p | 1.23456789012345678901234567890123456789012345d])
```
```
[G] type=Relation<(ok:Decimal(38,1)[1], s38:Decimal(38,38)[1])> mult=[1]
[PLAN] SELECT 0.1 AS ok, 1.23456789012345678901234567890123456789 AS s38
[EXEC-ROW] BigDecimal(0.1) | Double(1.2345678901234567) |
```

**Why it matters** — the compiler claims `Decimal(38,1)` / `Decimal(38,38)`; the value that comes back is a
`java.lang.Double` with 17 significant digits. The declared *type* is wrong (precision 51 vs 38), the
declared *Java carrier* is wrong (Double vs BigDecimal), and 34 digits are silently lost. In-precision
decimals (`0.1d`) correctly decode to `BigDecimal`, so the asymmetry is caused purely by the missing
precision check. `Decimal(38,38)` is additionally uninhabited by its own literal: 39 unscaled digits
cannot fit precision 38.

---

### [UNSOUND] 2. Integer literals beyond 64 bits are stamped `Integer[1]` with no range check; decode returns `BigInteger` or `Double`

**Evidence** — `Typer.java:152`:
```java
case CInteger lit -> new TypedCInteger(lit.value(), ExprType.one(Type.Primitive.INTEGER));
```
`CInteger.value()` is a `Number` (`CInteger.java:14`) and `SpecParser.parseInt` (`SpecParser.java:790-801`)
widens to `BigInteger` on `Long` overflow. Typer accepts it unconditionally.

**Repro**
```
model::Person.all()->project(~[maxl: p | 9223372036854775807, over: p | 9223372036854775808,
                               huge: p | 99999999999999999999999999999999999999999999])
```
**Actual output**
```
[G] type=Relation<(maxl:Integer[1], over:Integer[1], huge:Integer[1])> mult=[1]
[PLAN] SELECT 9223372036854775807 AS maxl, 9223372036854775808 AS over, 99999999999999999999999999999999999999999999 AS huge
[EXEC-COL] maxl : Integer [INTEGER] mult=[1]
[EXEC-COL] over : Integer [INTEGER] mult=[1]
[EXEC-COL] huge : Integer [INTEGER] mult=[1]
[EXEC-ROW] Long(9223372036854775807) | BigInteger(9223372036854775808) | Double(1.0E44) |
```

**Why it matters** — three different Java runtime classes (`Long`, `BigInteger`, `Double`) all wear the same
static Pure type `Integer[1]`. The 44-digit literal round-trips as `1.0E44`, silently losing 27 digits;
a consumer that expects `Integer` to be a 64-bit integral (the engine's contract) gets a floating-point
approximation. No compile-time diagnostic anywhere.

---

### [UNSOUND + DEAD CODE + DOC-LIE] 3. The "explicit D-suffixed decimal keeps the loud reject" guard is unreachable — every over-scale decimal literal is silently rounded

**Evidence** — `Typer.java:156-174`:
```java
case CDecimal lit -> {
    BigDecimal dv = lit.value();
    // ...
    // An EXPLICIT D-suffixed decimal keeps the loud reject —
    // silent truncation of a declared decimal lies.
    if (dv.scale() > Type.PrecisionDecimal.MAX_PRECISION
            && (lit.written() == null
                    || !lit.written().toUpperCase(java.util.Locale.ROOT)
                            .endsWith("D"))) {
        dv = dv.setScale(Type.PrecisionDecimal.MAX_PRECISION,
                java.math.RoundingMode.HALF_EVEN);
    }
    yield new TypedCDecimal(dv, ExprType.one(decimalType(dv)));
}
```
But the only two producers of `CDecimal` (`SpecParser.java:833` and `SpecParser.java:845-852`) **strip** the
suffix before storing `written`:
```java
private CDecimal parseDecimal() {
    String text = text();
    int litTok = pos;
    pos++;
    char last = text.charAt(text.length() - 1);
    if (last == 'd' || last == 'D') text = text.substring(0, text.length() - 1);
    return new CDecimal(new BigDecimal(text), text, spanOf(litTok, litTok));
}
```
So `written()` never ends with `D`, the guard's second conjunct is **always true**, and the rounding
always fires. Consequently `decimalType`'s `TypeInferenceException` (finding 1) is **unreachable via the
parser**: `dv.scale()` is capped at 38 before it is called.

**Repro / Actual output**
```
=== 0.1234567890123456789012345678901234567890d          (scale 40, EXPLICIT D suffix)
    TYPE  : Decimal(38,38) [1]
    NODE  : TypedCDecimal[value=0.12345678901234567890123456789012345679, …]
=== 0.12345678901234567890123456789012345678901234567890d  (scale 50, EXPLICIT D suffix)
    TYPE  : Decimal(38,38) [1]
    NODE  : TypedCDecimal[value=0.12345678901234567890123456789012345679, …]
=== 0.1234567890123456789012345678901234567890            (no suffix — promoted)
    TYPE  : Decimal(38,38) [1]
    NODE  : TypedCDecimal[value=0.12345678901234567890123456789012345679, …]
```
The D-suffixed and the bare literal are treated identically. The comment's promise is not kept.

**Why it matters** — the repo forbids silent truncation/defaulting. A user who explicitly writes a
50-digit `d` literal gets a silently HALF_EVEN-rounded 38-digit value, and the code path that was written
to prevent exactly this is dead. Ranked here (not as pure DEAD-CODE) because the observable behaviour is
silent value truncation, not just an unused branch.

---

### [UNSOUND] 4. A derived / qualified property on a `[0..1]` receiver is stamped with the property's declared `[1]` — the receiver's optionality is erased

**Evidence** — two sites wrap the receiver in `toOne` and then take the declared return multiplicity.

`Typer.java:2789-2799` (zero-arg derived read, `$h.opt.upper`):
```java
// A [0..1] receiver β-inlines like [1] (ledger cluster 48: …)
// the receiver wraps in toOne at this synth site
return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(),
        List.of(exactlyOne
                ? ap.receiver()
                : new AppliedFunction(com.legend.builtin.Pure.Lite.TRUST_ONE,
                        List.of(ap.receiver())))), env);
```
`Typer.java:537-546` (parameterized qualifier, `$h.opt.byN(2)`):
```java
java.util.List<ValueSpecification> qargs = new ArrayList<>(af.parameters());
if (!(recv.info().multiplicity() instanceof Multiplicity.Bounded rb1 && rb1.lower() == 1)) {
    qargs.set(0, new AppliedFunction(com.legend.builtin.Pure.Lite.TRUST_ONE, List.of(qargs.get(0))));
}
return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(), qargs), env);
```
The lifted body function declares `this:Owner[1]` (`DerivedProps.java:44-47`), so the result multiplicity
is always the property's declared one.

**Repro (static)** — model with `Holder.opt: Base[0..1]`, `Base.upper() {…}: Integer[1]`:
```
=== {h: t::Holder[1] | $h.opt.id}          -- plain property, correct
    TYPE  : {t::Holder[1] -> Integer[0..1]} [1]
=== {h: t::Holder[1] | $h.opt.upper()}     -- derived property, WRONG
    TYPE  : {t::Holder[1] -> Integer[1]} [1]
=== {h: t::Holder[1] | $h.opt.byN(2)}      -- parameterized qualifier, WRONG
    TYPE  : {t::Holder[1] -> Integer[1]} [1]
=== {b: t::Base[0..1] | $b.upper}          -- WRONG
    TYPE  : {t::Base[0..1] -> Integer[1]} [1]
```

**Repro (end to end)** — `/tmp/.../a05/model3.pure`: `P[1] <-> A[0..1]` over a left join, `A.plain(){$this.city}:String[1]`,
`A.tag(){'CAT-' + $this.city}:String[1]`, one `P` row with no matching `A`:
```
m::P.all()->project(~[nm: p | $p.name, plainCity: p | $p.a.city,
                      derivedTag: p | $p.a.tag(), derivedPlain: p|$p.a.plain()])
```
**Actual output**
```
[G] type=Relation<(nm:String[1], plainCity:String[0..1], derivedTag:String[1], derivedPlain:String[1])> mult=[1]
[PLAN] SELECT t0.NAME AS nm, t1.CITY AS plainCity, concat('CAT-', t1.CITY) AS derivedTag, t1.CITY AS derivedPlain
[EXEC-COL] derivedPlain : String [STRING] mult=[1]
[EXEC-ROW] String(has-addr) | String(NYC) | String(CAT-NYC) | String(NYC) |
[EXEC-ROW] String(no-addr) | null       | String(CAT-)     | null        |
```

**Why it matters** — `derivedPlain` is declared `String[1]` and the runtime value is `null`. That is a
straight nullability lie on the same query where the *non*-derived `plainCity` correctly reports
`String[0..1]`. `derivedTag` is worse: the SQL `concat` swallows the NULL and **manufactures** `'CAT-'`,
a value the object graph never contained. The code comments argue this matches an engine corpus pin, but
the emitted static type still claims a cardinality the emitted SQL violates, so consumers relying on
`[1]` (egress walls, non-null decoders, downstream `[1]`-only signatures) are lied to.

---

### [UNSOUND] 5. `tdsRowCellIndexRead` truncates the cell index to `int` — a 32-bit wraparound silently reads a *different* column

**Evidence** — `Typer.java:1080-1092`:
```java
if (!(af.parameters().get(1) instanceof CInteger ki)) {
    return null;
}
int k = ki.value().intValue();
if (k < 0 || k >= prt.columns().size()) {
    throw new IllegalStateException(
        "The system is trying to get an element at offset " + k
        + " where the collection is of size " + prt.columns().size());
}
```
`ki.value()` is a `Number` that may be a `Long` or a `BigInteger` (finding 2); `intValue()` truncates.
The same bug is in the row-major arm at `Typer.java:1060` (`long k = wk.value().longValue()`).

**Repro / Actual output** (2-column row `~[a:Integer, b:String]`):
```
=== {| let t = model::Person.all()->project(~[a: p|$p.age, b: p|$p.firstName]); $t.rows->at(0).values->at(4294967296);}
    TYPE  : Integer [1]                     <-- 2^32 truncates to 0 -> column a
=== …->at(4294967297);}
    TYPE  : String [1]                      <-- 2^32+1 truncates to 1 -> column b
=== …->at(18446744073709551617);}
    TYPE  : String [1]                      <-- 2^64+1 (BigInteger) truncates to 1 -> column b
=== …->at(4294967298);}
    THROW : java.lang.IllegalStateException: The system is trying to get an element at offset 2 where the collection is of size 2
```
Full pipeline for `at(4294967297)`:
```
[G] type=String mult=[1]
[PLAN] SELECT (SELECT t1.b FROM ( SELECT t0.AGE_VAL AS a, t0.FIRST_NAME AS b FROM T_PERSON AS t0 LIMIT 1 ) AS t1) AS value
[EXEC-ROW] String(John) |
```

**Why it matters** — an out-of-range index produces a *successful* query reading the wrong column, with no
diagnostic at any phase. The one case that does error reports the wrapped offset (`offset 2`) rather than
the offset the user wrote, which is also a misleading diagnostic.

---

### [UNSOUND] 6. `rows.values->at(k)` row-major arithmetic stamps `[1]` for a row index that does not exist

**Evidence** — `Typer.java:1051-1069`:
```java
int cc = wrt.columns().size();
long k = wk.value().longValue();
if (cc > 0 && k >= 0) {
    return synth(new AppliedFunction(Pure.Lite.TRUST_ONE, List.of(
            new AppliedProperty(
                    new AppliedFunction("at", List.of(vp.receiver(), new CInteger(k / cc))),
                    wrt.columns().get((int) (k % cc)).name()))), env);
}
```
The column index `k % cc` is bounds-safe, but the ROW index `k / cc` is not checked against anything
(it cannot be — the row count is dynamic), and the whole read is wrapped in `TRUST_ONE`, hard-stamping `[1]`.

**Repro / Actual output** — fixture has 3 rows, 2 columns; `at(99)` means row 49:
```
{| let t = model::Person.all()->project(~[a: p|$p.age, b: p|$p.firstName]); $t.rows.values->at(99);}

[G] type=String mult=[1]
[PLAN] SELECT (SELECT t1.b FROM ( SELECT t0.AGE_VAL AS a, t0.FIRST_NAME AS b FROM T_PERSON AS t0 LIMIT 1 OFFSET 49 ) AS t1) AS value
[EXEC-COL] value : String [STRING] mult=null
[EXEC-ROW] null |
```
Compare: the *ordinary* collection `at` is honest and emits a runtime guard —
`{| [1,2]->at(5)}` produces `SELECT CASE WHEN 5 >= len(...) THEN error('The system is trying to get an
element at offset 5 …') …` and fails loudly. Only the TDS row-major path drops the guard.

**Why it matters** — `String[1]` claimed, `null` delivered, no error at G, J or K.

---

### [UNSOUND] 7. `Any` from Typer's escape hatches turns `cast` into a no-op lie — the top 3 sites

`cast` never converts or verifies an `Any`-typed value; whatever the Typer stamped as `Any` keeps its
original Java carrier while the declared Pure type becomes whatever the user asked for.

**Site A — `relationColumn`'s late-bound "trusted column"** (`Typer.java:2922-2937`):
```java
private static Type.Column relationColumn(Type.RelationType rel, String name) {
    return rel.columns().stream()
            .filter(c -> c.name().equals(name)).findFirst()
            .orElseGet(() -> rel.columns().stream()
                    .filter(c -> stripColQuotes(c.name()).equals(stripColQuotes(name)))
                    .findFirst()
                    .orElseGet(() -> {
                        if (rel.isLateBound()) {
                            return Type.RelationType.trustedColumn(name);
                        }
                        throw new TypeInferenceException("relation has no column '" + name + "'");
                    }));
}
```
`Type.RelationType.trustedColumn` (`Type.java:511-514`) returns `Any[0..1]`.

Repro (`/tmp/.../a05/Any1.java`, DuckDB, SQL `select DATE '2020-03-04' as D, CAST(1.5 AS DECIMAL(10,2)) as C, 7 as I, TRUE as B`):
```
=== …rows->map(r|$r.value('I'))->toOne()->cast(@String);}
   G: String [1]
   EXEC: Scalar[value=7, returnType=STRING]  javaClass=java.lang.Integer
=== …rows->map(r|$r.value('B'))->toOne()->cast(@Integer);}
   G: Integer [1]
   EXEC: Scalar[value=true, returnType=INTEGER]  javaClass=java.lang.Boolean
```
A value declared `Integer[1]` is delivered as `java.lang.Boolean`. A value declared `String[1]` is delivered
as `java.lang.Integer`. Neither the SQL nor the decode does anything about the cast.

The same trusted-column fallback also accepts names that do not exist, deferring the failure to raw JDBC:
```
=== …executeInDb('select 1 as A, 2 as B', $c, 0, 1000).rows->map(r|$r.value('NOSUCHCOL'));}
   G-TYPE: meta::pure::metamodel::type::Any [*]
   EXEC-THROW: java.sql.SQLException: Binder Error: Values list "t0" does not have a column named "NOSUCHCOL"
```

**Site B — `rowCells`' mixed-cell `Any`** (`Typer.java:1113-1116`):
```java
Type collElem = mixed || elem == null
        ? new Type.ClassType(com.legend.compiler.element.type.PlatformTypes.ANY)
        : elem;
```
Repro / Actual output:
```
{| model::Person.all()->project(~[a: p|$p.age, b: p|$p.firstName])->map(r | $r.values->map(v|$v->cast(@Integer)))}

[G] type=Integer mult=[*]
[PLAN] SELECT list_transform([coalesce(to_json(t0.AGE_VAL), CAST('null' AS JSON)),
                             coalesce(to_json(t0.FIRST_NAME), CAST('null' AS JSON))], v -> v) AS value
[EXEC-COL] value : Integer [INTEGER] mult=null
[EXEC-ROW] ArrayList([30, "John"]) |
```
Declared `Integer[*]`; the decoded value is an `ArrayList` containing the String `"John"`. Composing
anything numeric on top blows up in the database rather than the compiler:
```
… ->map(v|$v->cast(@Integer))->sum()      [EXEC-ERROR] java.sql.SQLException: Binder Error: No matching aggregate function
… $r.values->at(1)->cast(@Integer) + 1    [EXEC-ERROR] java.sql.SQLException: Binder Error: No function matches the given name and argument types '+(JSON, INTEGER_LITERAL)'
```

**Site C — `cast(@Any)` as a universal launderer.** Any Typer-produced `Any` (also `lateBoundGridMarker`'s
`.values` → `Any[*]`, `Typer.java:2558-2565`) can be re-cast to a concrete type with a plain passthrough plan:
```
model::Person.all()->project(~[bad: p | $p.firstName->cast(@meta::pure::metamodel::type::Any)->cast(@Integer),
                               n:   p | $p.age->cast(@meta::pure::metamodel::type::Any)->cast(@String)])

[G] type=Relation<(bad:Integer[1], n:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS bad, t0.AGE_VAL AS n
[EXEC-COL] bad : Integer [INTEGER] mult=[1]
[EXEC-COL] n : String [STRING] mult=[1]
[EXEC-ROW] String(John) | Integer(30) |
```
An `Integer[1]` column carrying `String(John)` and a `String[1]` column carrying `Integer(30)`, all the way
out of the wire. (The direct `1->cast(@String)` also passes — that arm lives in `CastChecker`, A06's scope —
but every `Any` the Typer manufactures is a free entrance to it.)

**Why it matters** — this is the "`Any` as don't-know" unsoundness generator the brief asks for: three
Typer sites hand out `Any`, and nothing downstream ever re-checks it.

---

### [CRASH/ICE] 8. `java.lang.IllegalStateException` escapes from `tdsRowCellIndexRead` on plausible user input

**Evidence** — `Typer.java:1084-1089` (quoted in finding 5).

**Repro / Actual output**
```
{| let t = model::Person.all()->project(~[a: p|$p.age, b: p|$p.firstName]); $t.rows->at(0).values->at(99);}

    THROW : java.lang.IllegalStateException: The system is trying to get an element at offset 99 where the collection is of size 2
            at com.legend.compiler.spec.Typer.tdsRowCellIndexRead(Typer.java:1088)
            at com.legend.compiler.spec.Typer.applyFunction(Typer.java:457)
            at com.legend.compiler.spec.Typer.synth(Typer.java:184)
            at com.legend.compiler.spec.Typer.typeBody(Typer.java:117)
```
Through the full pipeline:
```
[G-ERROR] java.lang.IllegalStateException: The system is trying to get an element at offset 99 where the collection is of size 2
[PLAN-ERROR] java.lang.IllegalStateException: …
[EXEC-ERROR] java.lang.IllegalStateException: …
```
Every other user-facing wall in this file uses `TypeInferenceException` / `SchemaInvariantException` /
`NotImplementedException` / `ResolutionException`. This one is a raw JDK unchecked exception.

---

### [SILENT FALLBACK] 9. `values->at(<non-literal index>)` silently degrades to the identity read — the whole ROW is typed as if it were the element

**Evidence** — `Typer.java:1080-1082`:
```java
if (!(af.parameters().get(1) instanceof CInteger ki)) {
    return null;          // <- falls through to the ordinary `at`, whose receiver
}                         //    is the IDENTITY .values read (tdsValuesRead, Typer.java:2700-2708)
```
`tdsValuesRead` returns `source` unchanged for a pick-rooted row, so the ordinary `at(T[*], Integer[1]):T[1]`
binds `T` to the **row struct type** and hands the whole row back typed as one cell.

**Repro / Actual output** (all three below are non-`CInteger` index expressions):
```
=== {| let t = …project(~[a: p|$p.age, b: p|$p.firstName]); $t.rows->at(0).values->at(-1);}
    TYPE  : (a:Integer[1], b:String[1]) [1]
=== … $t.rows->at(0).values->at(0+1);}
    TYPE  : (a:Integer[1], b:String[1]) [1]
=== … let i = 1; $t.rows->at(0).values->at($i);}
    TYPE  : (a:Integer[1], b:String[1]) [1]
```
(compare `->at(1)` with a literal, which correctly gives `String[1]`.)
Through the pipeline:
```
[G] type=(a:Integer[1], b:String[1]) mult=[1]
[PLAN-ERROR] com.legend.error.NotImplementedException: lowering not yet implemented for TypedNativeCall
             ('meta::pure::functions::collection::at' in relation position)
```

**Why it matters** — the type of `values->at(k)` depends on whether `k` is a *syntactic literal*. A variable
index, an arithmetic index, or a negative index all get a type no element of the collection can have.
The `k < 0` branch at line 1084 is therefore also dead for source-written negatives (`-1` parses as
`minus(1)`, an `AppliedFunction`).

---

### [SILENT FALLBACK] 10. The late-bound "trust the name" column fallback accepts any identifier; the failure lands as a raw `SQLException` at phase K

**Evidence** — `Typer.java:2930-2933` (quoted in finding 7, site A).
The repo's own rule is "NO FALLBACKS. NO DEFAULTING." This one guesses a type (`Any[0..1]`) for a name
that the compiler has no evidence exists.

**Repro / Actual output** — already pasted in finding 7 (`NOSUCHCOL` → `Binder Error` from DuckDB;
`$r.NOSUCHCOL` → `WHERE t0.NOSUCHCOL IS NOT NULL` → `Binder Error`; `$r.value('ZZZ')->cast(@Integer) > 0`
→ `Binder Error: Referenced table "t0" not found!`).

---

### [INCONSISTENCY] 11. Two different least-upper-bound rules for "element type of a heterogeneous collection"

**Evidence**
`Typer.collection` (`Typer.java:2461-2470`) uses the lattice:
```java
Type elementType = elements.stream()
        .map(e -> e.info().type())
        .reduce(kernel::commonSupertype)
        .orElseGet(() -> new Type.ClassType(PlatformTypes.NIL));
```
`Typer.rowCells` (`Typer.java:1101-1116`) uses equality-or-`Any`:
```java
if (elem == null) { elem = c.type(); }
else if (!elem.equals(c.type())) { mixed = true; }
…
Type collElem = mixed || elem == null ? new Type.ClassType(PlatformTypes.ANY) : elem;
```

**Repro / Actual output**
```
=== [1, 1.5]                                                                    TYPE : Number [2]
=== {| model::Person.all()->project(~[a: p|$p.age, b: p|$p.age * 1.0])->map(r | $r.values)}
                                                                                TYPE : meta::pure::metamodel::type::Any [*]
```
Same two element types (`Integer`, `Float`); the collection literal says `Number`, the row-cells read says
`Any`. Information loss on the second path, and it feeds finding 7 site B.

---

### [INCONSISTENCY] 12. A bare (unsuffixed) float literal silently becomes a `Decimal` past 17 significant digits, flipping the plan from working to failing

**Evidence** — `SpecParser.java:815-836` promotes a `FLOAT` token to `CDecimal` whenever the decimal-exact
value differs from the `double`; `Typer.java:156-173` then types it as `Decimal`.

**Repro / Actual output** (literal typing)
```
=== 1.2345678901234567        TYPE : Float [1]
=== 1.23456789012345678       TYPE : Decimal(38,17) [1]        <- one extra digit
=== 3.141592653589793         TYPE : Float [1]
=== 3.1415926535897932384626433832795   TYPE : Decimal(38,31) [1]
```
**Repro / Actual output** (observable behaviour change)
```
model::Person.all()->project(~[f: p | 1.2345678901234567, d: p | 1.23456789012345678,
   sumF: p|$p.age + 1.2345678901234567, sumD: p|$p.age + 1.23456789012345678])

[G] type=Relation<(f:Float[1], d:Decimal(38,17)[1], sumF:Number[1], sumD:Number[1])> mult=[1]
[PLAN] SELECT CAST(1.2345678901234567 AS DOUBLE) AS f, 1.23456789012345678 AS d,
              t0.AGE_VAL + CAST(1.2345678901234567 AS DOUBLE) AS sumF,
              t0.AGE_VAL + 1.23456789012345678 AS sumD
[EXEC-ERROR] java.sql.SQLException: Conversion Error: Could not cast value 30 to DECIMAL(18,17) when casting from source column AGE_VAL
```
Typing one more digit into a literal changes the arithmetic's overload, the emitted SQL, and turns a
working query into a runtime conversion error — while both are stamped the same abstract `Number[1]`.

---

### [SILENT FALLBACK] 13. `functionCandidates(AppliedFunction)` swallows `RuntimeException` from import candidates

**Evidence** — `Typer.java:2320-2343`:
```java
for (String fqn : af.candidateFqns()) {
    try {
        union.addAll(ctx.findFunction(fqn));
    } catch (RuntimeException e) {
        // an import candidate whose overloads are ALL signature-broken …
        if (firstBroken == null) { firstBroken = e; }
    }
}
if (union.isEmpty() && firstBroken != null) { throw firstBroken; }
return union;
```
Classification: **SILENT FALLBACK**. A broken import candidate is dropped from overload resolution and the
user is never told; the surviving candidate may be a different function than the one they meant. Catching
bare `RuntimeException` also swallows genuine ICEs (NPE/CCE) originating inside `findFunction`.

---

### [SILENT FALLBACK] 14. `checkWithDeferred`'s candidate retry loop reports only the FIRST failure and discards the rest

**Evidence** — `Typer.java:1717-1729`:
```java
for (TypedFunction cand : ranked) {
    try {
        return bindDeferredAndBuild(cand, raw, typed.clone(), env);
    } catch (SchemaInvariantException invariant) {
        throw invariant;   // the program's defect, never a candidate mismatch — no retry
    } catch (TypeInferenceException e) {
        if (firstFailure == null) { firstFailure = e; }
    }
}
throw java.util.Objects.requireNonNull(firstFailure);
```
Classification: **legitimate mechanism, silent in effect**. Backtracking overload search is defensible
(mirrors real Pure's `FunctionExpressionProcessor`), but (a) failures 2..n are dropped entirely, so the
reported error can be from a candidate the user never meant, and (b) a candidate that *succeeds* with a
worse-but-passing typing wins silently over one whose deferred slot failed for a reason worth reporting.

---

### [SILENT FALLBACK] 15. Two `catch (TypeInferenceException) { continue; }` in the arity pre-filter

**Evidence**
`Typer.java:1974-1984` in `lambdaAritiesFit`:
```java
Integer want;
try {
    want = extractFunctionType(pt).params().size();
} catch (TypeInferenceException e) {
    // a deferred LAMBDA against a non-function, non-variable param can never type
    if (raw.get(i) instanceof LambdaFunction) { return false; }
    continue;
}
```
`Typer.java:2012-2018` in `lambdaArityMismatch`:
```java
try {
    want = extractFunctionType(c.parameters().get(i).type()).params().size();
} catch (TypeInferenceException e) {
    continue;
}
```
Classification: **borderline-legitimate** (exception-as-predicate over `asFunctionType`, which already
exists as a null-returning variant at `Typer.java:2110-2119` and should have been used). The second one is
diagnostic-only. The first can silently keep an unfittable candidate in the ranked list when the deferred
argument is a `PureCollection` of lambdas rather than a bare lambda.

---

### [INFO LOSS / LOW] 16. Runtime and connection elements are hardcoded to `Any[1]`; `from(runtime, mapping)` and `from(mapping, runtime)` are indistinguishable at G

**Evidence** — `Typer.java:2387-2391`:
```java
// An execution-context element (runtime/connection) is a value
// of type Any[1] — exactly what from/write's signature parameters declare.
if (ctx.isExecutionContextElement(ref.fullPath())) {
    return new TypedPackageableRef(ref.fullPath(), ExprType.one(InferenceKernel.anyType()));
}
```
Note the neighbouring arms *do* mint real metaclasses (`Mapping` at 2384, `Database` at 2377) — only the
runtime/connection arm gives up.

**Repro / Actual output**
```
=== {| test::TestRuntime}       TYPE : meta::pure::metamodel::type::Any [1]
=== {| store::TestConnection}   TYPE : meta::pure::metamodel::type::Any [1]
=== {| model::PersonMapping}    TYPE : meta::pure::mapping::Mapping [1]
=== {| test::TestRuntime->cast(@Integer)}   TYPE : Integer [1]

model::Person.all()->project(~[a:p|$p.age])->from(test::TestRuntime, model::PersonMapping)
[G] type=Relation<(a:Integer[1])> mult=[1]                    <- typechecks
[PLAN-ERROR] com.legend.error.MappingResolutionException: unknown mapping 'test::TestRuntime'
```
Swapped arguments pass phase G and only fail at H. Ranked low because H's error is clean.

---

### [LOW] 17. A `@Type` annotation is a first-class *value* of that type; `@Integer + 1` type-checks and dies at H

**Evidence** — `Typer.java:2989-2992`:
```java
private TypedSpec typeRef(TypeAnnotation ta) {
    Type target = annotationType(ta);
    return new TypedTypeRef(target, ExprType.one(target));
}
```
**Repro / Actual output**
```
=== {| @Integer + 1}   TYPE : Integer [1]
=== {| 'x' + @String}  TYPE : String [1]
=== {| @t::Base}       TYPE : t::Base [1]

model::Person.all()->project(~[z: p | @Integer + 1])
[G] type=Relation<(z:Integer[1])> mult=[1]
[PLAN-ERROR] com.legend.error.NotImplementedException: object-space expression node TypedTypeRef is not
             substitutable yet (H2 vocabulary): TypedTypeRef[target=INTEGER, …]
```
The "prototype value" convention exists so `cast<T|m>` can bind `T`; the cost is that a type annotation in
any value position silently passes G.

---

### [LOW] 18. Mangled function references fall back to `Function<Any>`

**Evidence** — `Typer.java:2410-2420`:
```java
String base = SignatureMangle.stripTail(ref.fullPath());
if (base != null && !ctx.findFunction(base).isEmpty()) {
    return new TypedPackageableRef(ref.fullPath(),
            ExprType.one(new Type.GenericType(
                    "meta::pure::metamodel::function::Function",
                    List.of(InferenceKernel.anyType()))));
}
```
A mangled id whose arity does not disambiguate becomes an opaque `Function<Any>` value rather than an
error. Same `Any`-as-don't-know family as finding 7, but the value is not directly castable to a scalar,
so ranked low.

---

### [LOW / DEAD] 19. `elementOverride` is synthesised on every class, always folds to an empty collection, and hardcodes `[0..1]` regardless of the composed multiplicity

**Evidence** — `Typer.java:2839-2847` and `Typer.java:2900-2905`:
```java
if (ap.property().equals("elementOverride")) {
    yield new ExprType(new Type.ClassType(Pure.ELEMENT_OVERRIDE.qualifiedName()),
            Multiplicity.Bounded.ZERO_ONE);
}
…
if (ap.property().equals("elementOverride") && source.info().type() instanceof Type.ClassType) {
    return new com.legend.compiler.spec.typed.TypedCollection(
            List.of(), new ExprType(member.type(), Multiplicity.Bounded.ZERO_ONE));
}
```
`Multiplicity mult = compose(...)` computed at line 2899 is discarded on this branch. Harmless today
(the value is always empty) but it is a hardcoded answer that ignores the receiver.

**Repro / Actual output**
```
=== {| model::Person.all()->map(p|$p.elementOverride)}
    TYPE  : meta::pure::metamodel::extension::ElementOverride [*]
    NODE  : … TypedCollection[elements=[], info=…ZERO_ONE…]
```

---

### [LOW] 20. `Nil[1]` (an uninhabited type) is stamped statically; the contradiction is only caught by generated SQL

**Evidence** — `Typer.collection` yields `Nil[0]` for `[]` (`Typer.java:2466-2470`); nothing rejects
`Nil` reaching a `[1]` slot.
**Repro / Actual output**
```
=== {| []->toOne()}   TYPE : meta::pure::metamodel::type::Nil [1]

model::Person.all()->project(~[z: p | []->toOne()])
[G] type=Relation<(z:meta::pure::metamodel::type::Nil[1])> mult=[1]
[PLAN] SELECT error('Cannot cast a collection of size 0 to multiplicity [1]') AS z
[EXEC-ERROR] java.sql.SQLException: Invalid Input Error: Cannot cast a collection of size 0 to multiplicity [1]
```
Loud in the end, so low — but a statically-impossible type survives all of phase G.

---

### [LOW] 21. `classFqnOf` silently returns the input name when the class is unknown

**Evidence** — `Typer.java:91-94`:
```java
String classFqnOf(String name) {
    return ctx.findClass(name)
            .map(c -> c.qualifiedName()).orElse(name);
}
```
Classification: **silent fallback**, single consumer (`FromChecker.java:62`, an unchecked-body walk).
Unverified downstream impact; reported for completeness.

---

### [DOC-LIE] 22. `ValueSpecification.java` javadoc describes a 3-variant `permits` clause that has 24 entries

`ValueSpecification.java:16-30`: *"The `permits` clause currently lists only the variants emitted by
SpecParser as of Phase C.1: literals, `Variable`, and `PureCollection`."* The actual clause
(`ValueSpecification.java:45-71`) permits 24 types (25 concrete records once `ColumnInstance` is expanded to
`ColSpec` + `ColSpecArray`). The same javadoc promises a `UnitInstance` variant in "C.5"; no such record exists.

### [DOC-LIE] 23. `Typer`'s class javadoc claims the class "owns exactly two things"

`Typer.java:70-82`: *"this class owns exactly two things — the forms … and the one generic application
rule"*. The file also owns ~700 lines of legacy-TDS surface rewriting (`tdsSchemaDesugars`,
`tdsGetterDesugars`, `olapGroupByDesugar`, `windowColsProjectDesugar`, `projectWithColumnSubsetDesugar`,
`renameColumnsDesugar`, `tdsRowCellIndexRead`, `rowCellRead`), plus the NormalizeRequired inliner
(`requiresNormalization` / `inlineNormalized` / `rawSchemaErasedExpansion` / `alphaRename`).

### [DOC-LIE] 24. The `CDecimal` comment's promise is falsified by the parser — see finding 3.

---

### [BOUNDARY — not Typer, reported for the parser auditor] `1e400` crashes with `NumberFormatException`

```
=== 1e400
  THROW java.lang.NumberFormatException: Character I is neither a decimal digit number, decimal point, nor "e" notation exponential mark.
     at java.base/java.math.BigDecimal.valueOf(BigDecimal.java:1371)
     at com.legend.parser.SpecParser.parseFloat(SpecParser.java:832)
     at com.legend.parser.SpecParser.parsePrimary(SpecParser.java:701)
```
`Double.parseDouble("1e400")` is `Infinity`; `BigDecimal.valueOf(Infinity)` throws. Same for `-1e400` and
`1.7976931348623157e309`. Never reaches Typer. (`1.0e308`, `-0.0`, `0.0` all type as `Float[1]` fine.)

---

## VERIFIED SOUND

**1. `synth`'s dispatch is genuinely exhaustive over the sealed `ValueSpecification` hierarchy.**
`ValueSpecification.java:45-71` permits 24 types; `ColumnInstance` is itself sealed over `ColSpec` and
`ColSpecArray`, giving **25 concrete records**. `Typer.synth` (`Typer.java:130-262`) is a switch expression
with **no `default` arm**, so a new variant is a compile error, not a runtime surprise. I enumerated all 25
and confirmed each has an arm and checked its behaviour from source where reachable:

| # | Variant | Typer arm | Repro | Outcome |
|---|---|---|---|---|
| 1 | `AppliedFunction` | 184 | `1->toString()` | `String[1]` |
| 2 | `AppliedProperty` | 185 | `$p.firstName` | `String[*]` |
| 3 | `CBoolean` | 154 | `true` | `Boolean[1]` |
| 4 | `CDate` | 177 | `%2020-01-01` | `StrictDate[1]` |
| 5 | `CDecimal` | 156-174 | `1.5d` | `Decimal(38,1)[1]` (findings 1,3) |
| 6 | `CFloat` | 155 | `1.5` | `Float[1]` |
| 7 | `CInteger` | 152 | `1` | `Integer[1]` (finding 2) |
| 8 | `CLatestDate` | 180 | `%latest` | `LatestDate[1]` |
| 9 | `GraphFetchLiteral` | 136 | `->graphFetch(#{…}#)->serialize(#{…}#)` | `String[1]` |
| 10 | `QuotedTreeCall` | 140-141 | (internal, `QuotedSpecParser`) | types as the wrapped call |
| 11 | `PathLiteral` | 128 | `#/model::Person/firstName#` | `{model::Person[1]->String[1]}[1]` |
| 12 | `SqlIsland` | 142-146 | `#SQL{select 1}#` | clean `NotImplementedException` |
| 13 | `GqlIsland` | 147-151 | `#GQL{query { a } }#` | clean `NotImplementedException` |
| 14 | `TdsLiteral` | 144 (`tl`) | `#TDS{…}#` | desugars to `tds(...)` |
| 15 | `CString` | 153 | `'x'` | `String[1]` |
| 16 | `CByteArray` | 131-135 | *unreachable from queries* — only `ServiceSectionGrammar.java:280` builds one | clean `NotImplementedException` (honest) |
| 17 | `CTime` | 178-179 | `%10:20:30` | `StrictTime[1]` |
| 18 | `ColSpec` | 209 | `~a` | `ColSpec<(a:?[1])>[1]` |
| 19 | `ColSpecArray` | 210 | `~[a,b]` | `ColSpecArray<(a:?,b:?)>[1]` |
| 20 | `EnumValue` | 211 | `t::Color.RED` | `t::Color[1]`; `t::Color.PURPLE` → clean `TypeInferenceException` |
| 21 | `LambdaFunction` | 214-268 | `{x:Integer[1]\|$x}` | function type; unannotated bare lambda → clean error |
| 22 | `NewInstance` | 200-208 | `^model::Person(...)` | `model::Person[1]` |
| 23 | `NewInstanceCast` | 271-289 | `^C($src)` | nominal; unknown class → clean error |
| 24 | `PackageableElementPtr` | 190-199 | `model::Person` | `Class<model::Person>[1]` |
| 25 | `TypeAnnotation` | 181 | `@Integer` | `Integer[1]` (finding 17) |

**No unhandled variant exists.** The only "holes" are the deliberate `NotImplementedException` arms
(12, 13, 16), all of which are clean user-facing errors, not ICEs. The other sub-dispatches are likewise
exhaustive: `applyCore` over the `CoreFn` enum (`Typer.java:1287-1334`, no default), `annotationType` over
`TypeAnnotation` (`Typer.java:2995-3005`, `Wildcard` → clean `TypeInferenceException`).
Non-exhaustive helper switches with defaults (`alphaRename` → `mapChildren`, `legacyRankName` → `null`,
`deferredShapesMatch` → `true`) are structural-recursion / predicate helpers, not typing decisions.

**2. Literal typing — static type and multiplicity.** All `[1]` (`ExprType.one`). `CInteger`→`Integer`,
`CString`→`String`, `CBoolean`→`Boolean`, `CFloat`→`Float`, `CTime`→`StrictTime`, `CLatestDate`→`LatestDate`.
`CDate` splits correctly by precision (`Typer.java:3110-3115`, verified): `%2020`→`Date`,
`%2020-01-01`→`StrictDate`, `%2020-01-01T10:00:00`→`DateTime`, `%2020-01-01T10:00:00.123`→`DateTime`.

**3. String literals.** Empty, escaped quote, escaped backslash, `\t`, `\n`, `\uXXXX`, raw non-BMP-adjacent
unicode, embedded double quote, `%` — all type `String[1]` with the correct decoded code points:
(verbatim, escapes shown as the probe printed them — the console rendered non-ASCII as `?`, so the
`codepoints=` column is the authoritative decode)
```
SRC=''  -> String[1]  value=[] codepoints= len=0
SRC='abc'  -> String[1]  value=[abc] codepoints=\u0061\u0062\u0063 len=3
SRC='a\'b'  -> String[1]  value=[a'b] codepoints=\u0061\u0027\u0062 len=3
SRC='a\\b'  -> String[1]  value=[a\b] codepoints=\u0061\u005c\u0062 len=3
SRC='tab\there'  -> String[1]  codepoints=\u0074\u0061\u0062\u0009\u0068\u0065\u0072\u0065 len=8
SRC='nl\nhere'  -> String[1]  codepoints=\u006e\u006c\u000a\u0068\u0065\u0072\u0065 len=7
SRC='uni\u00e9'  -> String[1]  value=[uni?] codepoints=\u0075\u006e\u0069\u00e9 len=4
SRC='???'  -> String[1]  value=[???] codepoints=\u00e9\u4e2d\u6587 len=3
SRC='q"dq'  -> String[1]  value=[q"dq] codepoints=\u0071\u0022\u0064\u0071 len=4
SRC='%'  -> String[1]  value=[%] codepoints=\u0025 len=1
SRC='a\\'  -> String[1]  value=[a\] codepoints=\u0061\u005c len=2
```

**4. `PureCollection` (`Typer.java:2445-2492`).**
- Empty `[]` → `Nil[0..0]` (bottom type, `PlatformTypes.NIL`), *not* a fresh TypeVar and not `Any` — correct
  and lets `if(c,{|1},{|[]})` be `Integer[0..1]` (verified).
- Multiplicity is the **sum of element bounds**, not the element count: `[1]`→`[1]`, `[1,2,3]`→`[3]`,
  `['a',[]]`→`[1]` (the empty splices to zero elements), `[[],[]]`→`Nil[0]`.
- Nested collections splice per Pure value semantics: `[[1,2],[3]]` → `Integer[3]` with 3 flat elements.
- Heterogeneous LUB via `kernel.commonSupertype`. Verified matrix:
  `[1,1.5]`→`Number[2]`, `[1,1.5d]`→`Number[2]`, `[1.5,1.5d]`→`Number[2]`, `[1,'a']`→`Any[2]`,
  `[1,true]`→`Any[2]`, `['a',true]`→`Any[2]`, `[%2020-01-01,%2020-01-01T10:00:00]`→`Date[2]`,
  `[%latest,%2020-01-01]`→`Date[2]`, `[%2020-01-01,'a']`→`Any[2]`, `[%10:20:30,%2020-01-01]`→`Any[2]`.
  Heterogeneous collections do **not** error; they widen to `Any`, which is the documented Pure rule.

**5. `Env` / `VarUse` / `TypedVariable` — no fallback path exists.**
`Env` (`Env.java`, 41 lines) is an immutable `LinkedHashMap` copy-on-`with`; `lookup` returns `Optional`.
There is exactly **one** `lookup` call site in the whole `spec` package (`Typer.java:182`) and it is
`orElseThrow(() -> new TypeInferenceException("unbound variable '$" + v.name() + "'"))`. No default, no
`Any`, no fresh type variable. Verified behaviours:
```
{| let x = 1; $x;}                              -> Integer [1]
{| let x = 1; let x = 'a'; $x;}                 -> String [1]      (rebinding shadows)
{| let x = 'a'; let x = 1; $x + 1;}             -> Integer [1]
{| let x = 1; [1,2,3]->map(x | $x + 1);}        -> Integer [3]     (lambda param shadows the let)
{| let x = 'a'; [1,2,3]->map(x | $x + 1);}      -> Integer [3]     (String let does NOT leak in)
{| let x = 'a'; [1,2,3]->map(y | $x);}          -> String [3]      (outer capture works)
{| [1,2,3]->map(x | [4,5]->map(x | $x));}       -> Integer [*]     (nested same-name shadow)
{| let p = 'shadow'; model::Person.all()->map(p | $p.firstName);}  -> String [*]
{| $y; }                                        -> TypeInferenceException: unbound variable '$y'
{| let y = $y; $y;}                             -> TypeInferenceException: unbound variable '$y'  (use-before-let)
{| let x = []; $x;}                             -> Nil [0]
{| let x = [1,2,3]; $x;}                        -> Integer [3]
```
`LetChecker` (41 lines) guards arity and the name-literal shape before indexing and resolves the output
through the registered `letFunction` signature — no defaulting.

**6. Multiplicity composition along a navigation path is correct.**
`Typer.compose` (`Typer.java:2938-2947`) delegates to the single owner `Multiplicity.product`, which
annihilates on `[0..0]`, absorbs unbounded, and is loud (`IllegalStateException`) only when an unresolved
variable meets a non-identity bound. Verified against a purpose-built model
(`Holder.one:[1] / opt:[0..1] / many:[*] / twoToFive:[2..5]`, `Base.tags:[*] / nick:[0..1]`):
```
{h: t::Holder[1]    | $h.one.id}         -> Integer[1]
{h: t::Holder[1]    | $h.opt.id}         -> Integer[0..1]
{h: t::Holder[1]    | $h.many.id}        -> Integer[*]
{h: t::Holder[1]    | $h.opt.tags}       -> String[*]      ([0..1].[*] == [*])   <- the case the brief names
{h: t::Holder[1]    | $h.many.nick}      -> String[*]
{h: t::Holder[1]    | $h.twoToFive.id}   -> Integer[2..5]
{h: t::Holder[1]    | $h.twoToFive.tags} -> String[*]
{h: t::Holder[0..1] | $h.one.id}         -> Integer[0..1]
{h: t::Holder[*]    | $h.one.id}         -> Integer[*]
{h: t::Holder[*]    | $h.many.tags}      -> String[*]
{h: t::Holder[2..3] | $h.twoToFive.tags} -> String[*]
```

**7. Property lookup walls are clean.** `accessProperty`'s `ClassType` arm (`Typer.java:2827-2864`) and its
`default` arm (`Typer.java:2896-2898`) both raise `TypeInferenceException`:
```
$b.nope on t::Base        -> class t::Base has no property 'nope'                          (Typer.java:2850)
$a.id on Any[1]           -> class meta::pure::metamodel::type::Any has no property 'id'   (Typer.java:2850)
$c.name on an enum        -> cannot access 'name' on t::Color                              (Typer.java:2897)
$b.id.foo on a primitive  -> cannot access 'foo' on Integer                                (Typer.java:2897)
$b.nick.length on String  -> cannot access 'length' on String                              (Typer.java:2897)
$p.businessDate on a non-temporal class -> class model::Person has no property 'businessDate'
```
Inheritance works (`t::Derived` sees `t::Base.id`), association-injected properties work
(`$p.addresses.person.addresses.city` → `String[*]`), and the parameterized-class arm
(`Typer.java:2869-2887`) validates the type-parameter arity before instantiating.
`relationColumn`'s **statically-schema'd** path is loud for unknown columns (`relation has no column 'x'`);
only the late-bound branch fails open (finding 10).

**8. Unknown function names are loud** (`Typer.java:1626-1633`): *"unknown function 'x' — no function of this
name in the native or user catalog…"*. Unmatched overloads are loud with the full candidate list
(verified for `plus` on `Nil`, `sort` with a `[]` comparator, `select(~nosuch)`).

**9. Duplicate colspec names are loud** (`SchemaInvariantException` at `Typer.java:3145` / `2214` / `2260`).

**10. Ordinary collection `at` is honest.** `[1,2]->at(5)` types `Integer[1]` but emits a guarded plan
(`CASE WHEN 5 >= len(...) THEN error(...)`) and fails loudly at execution. Only the TDS row-major path
(finding 6) drops that guard.

**11. `refineParseDate` (`Typer.java:1640-1665`) and `refineDecimalCarrier` (`Typer.java:1812-1826`)** were
read in full; both are narrow, whitelisted refinements of an already-resolved signature output
(`parseDate` on a literal string; `parseDecimal`/`toDecimal` → `Decimal(38,18)`), and both fall through to
the unrefined output rather than guessing. No defect found.

---

## NOT COVERED

- **The `*Checker` layer** (`IfChecker`, `CastChecker`, `MapChecker`, `ProjectChecker`, `GroupByChecker`,
  `JoinChecker`, `NavigateChecker`, `GraphFetchChecker`, `TdsChecker`, …) — A06's half. I touched them only
  where the Typer routes into them (`applyCore`, `Typer.java:1287-1334`, verified exhaustive over `CoreFn`).
  In particular `CastChecker`'s failure to reject `1->cast(@String)` is reported here only as the
  *consumer* of the Typer's `Any` (finding 7); the cast rule itself is A06's.
- **`InferenceKernel`** internals (`unify`, `resolveOverload`, `scoreNonLambda`, `commonSupertype`,
  `hasFreeTypeVars`). I verified `commonSupertype`'s *observable* LUB matrix (VERIFIED SOUND §4) but did not
  read the lattice implementation.
- **Lowering (I/J) and decode (K)** — used as black-box oracles via `probe.sh` only.
- **Milestoning** (`TypedMilestonedAccess`, `Temporal.strategyOf`, `AllVersions*` spellings at
  `Typer.java:548-590` and `2801-2823`) — read but not exercised; needs a temporal model + mapping I did
  not build.
- **`requiresNormalization` / `inlineNormalized` / `rawSchemaErasedExpansion` / `alphaRename`
  (`Typer.java:1400-1605`)** — read in full; the α-hygiene and the recursion guard look correct, but I did
  not build a `NormalizeRequiredFunction` corpus case to exercise them.
- **`functionCandidates`' import-ambiguity union (finding 13)** is cited from code only; I did not construct
  a model with signature-broken overloads to observe the swallow.
- **`SpecParser`** — out of scope; the `1e400` `NumberFormatException` and the >17-digit float→decimal
  promotion (finding 12) are flagged for whoever owns the lexer/parser.
