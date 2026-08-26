# A23 — the typed SQL MIR (`com.legend.sql`): closure, sealedness, type completeness

Scope: all 18 files of `core/src/main/java/com/legend/sql/` (3736 lines), read in full.
Every structural claim tested MECHANICALLY by reflection / bytecode scan over
`core/target/classes`, and every typing rule tested by running it.
Probes live in `/tmp/claude-0/.../scratchpad/probes/` (MirReflect, MirFields, DecBoundary,
DecWire, DecSweep, DecSweep2, DecIllegal, FactWire, VariantTypes, RewriteType, Misc,
NameChannel, OlaWire, NullOuts, LitText, TypeCensus).

---

## 0. THE TRUE COUNTS (mechanical — `MirReflect.java`, reflection over `target/classes`)

All 8 interfaces in `com.legend.sql` are `sealed` with an explicit `permits` clause.
`Class#isSealed()` = true for every one; there is no unsealed interface in the package.

| Root | AGENTS.md §3a claims | **ACTUAL (reflection)** |
|---|---|---|
| `SqlQuery` | `SqlSelect, SqlUnion` | **2** — SqlSelect, SqlUnion ✅ |
| `SqlSource` | **8** | **9** — Pivot, SourceUrl, Table, VarSetPlaceholder, Dual, Subselect, Values, **RawSql**, Join |
| `SqlExpr` | **32** | **36** — Column, Star, StarExcept, StringLit, IntLit, FloatLit, DecimalLit, BoolLit, NullLit, DateLit, TimestampLit, FormatLit, ArrayLit, OrderedListAgg, StructLit, StructGet, Call, Case, Exists, ScalarSubquery, CheckedOne, CompactList, DeferredTdsString, WindowCall, Lambda, Cast, FoldCall, JsonObject, JsonArrayAgg, PlanParam, Group, RowOrder, ReduceCollection, Membership, TempTableInSplice, SqlAgg.Reducer |
| `SqlAgg` | `Fn (~35) + Reducer` | **3 kinds** (Reducer, RankingFn, ValueFn); **`Fn` = 41 constants** (5 of them `marker()`: WAVG, HASH_LIST, IS_DISTINCT_MARK, UNIQUE_VALUE_ONLY, QDISC_DESC) |
| `SqlType` | `Scalar, Decimal, Array, Map` (4) | **5** — Scalar, Decimal, Array, Map, **Struct**; `Scalar` = **12** constants (BOOLEAN, INTEGER, BIGINT, HUGEINT, DOUBLE, VARCHAR, DATE, TIMESTAMP, TIMESTAMPTZ, JSON, LITERAL, TEMPORAL_TEXT) |
| `DateFmt` | — | **2** — `Part` (14 constants) + `Text`; sealed |
| `TypeFact` | (not listed) | **4** — Typed, Bottom, Raises, Unknown |
| `SqlExpr.WindowCall.Frame.Bound` | (not listed) | **7** — UnboundedPreceding, Preceding, CurrentRow, Following, UnboundedFollowing, IntervalPreceding, IntervalFollowing |
| `SqlFn` (enum, not sealed iface) | — | **168** constants |
| package totals | "15 files, ~1,743 LOC" | **18 files, 3736 LOC**; 98 compiled classes; **75 records, 190 record components, 34 String-typed** |

---

## FINDINGS

### [UNSOUND] `SqlTyping.decimalLitType` gates on `precision`, DuckDB gates on `width` — every `0.xxx` decimal literal carries a false type, and at scale 38 the CARRIER flips to Double

**Evidence** — `core/src/main/java/com/legend/sql/SqlTyping.java:1188-1215`:
```java
static TypeFact decimalLitType(java.math.BigDecimal v) {
    if (v.scale() < 0) { v = v.setScale(0); }
    if (v.scale() > 0) {
        if (v.precision() > 38) {          // :1200  <-- the switch
            return T_DOUBLE;               // :1201
        }
        return typed(new SqlType.Decimal(Math.max(v.precision(), 1), v.scale()));
    }
    int bits = v.toBigIntegerExact().bitLength();
    if (bits >= 128) { return T_DOUBLE; }  // :1219-1224
    if (bits >= 64)  { return T_HUGEINT; } // :1226
    return typed(new SqlType.Decimal(Math.max(v.precision(), 1), 0));
}
```
`BigDecimal.precision()` counts SIGNIFICANT digits. DuckDB 1.5.0 sizes a bare decimal
literal by the **rendered width** `max(integerDigits,1) + scale`. The two agree only when
the value has at least one non-zero integer digit.

**Repro / actual output** (`DecSweep2.java` — MIR fact vs the wire of the *dialect's own
emission*, 287 rows across intDigits ∈ {0,1,2,5,20,38,39} × scale 0..40):
```
TOTAL rows probed = 287; fact<>wire MISMATCHES = 38
...all 38 in the intDigits==0 family:
int  scl  prec   MIR fact         wire type        javaClass    verdict
0    1    1      D(1,1)           D(2,1)           BigDecimal   MISMATCH
0    17   17     D(17,17)         D(18,17)         BigDecimal   MISMATCH
0    37   37     D(37,37)         D(38,37)         BigDecimal   MISMATCH
0    38   38     D(38,38)         DOUBLE           Double       MISMATCH   <-- CARRIER FLIP
```
End-to-end (`probe.sh`, fixture model, DuckDB):
```
######## |0.00000000000000000000000000000000000001D
[G] type=Decimal(38,38) mult=[1]
[PLAN] SELECT 0.00000000000000000000000000000000000001 AS value
[EXEC-COL] value : Decimal(38,38) [PrecisionDecimal[precision=38, scale=38]] mult=null
[EXEC-ROW] Double(1.0E-38) |
```
A compile-time `Decimal(38,38)` delivered as a `java.lang.Double`.

**Why it matters** — this is the same defect class the orchestrator reported, but the
boundary is one step wider than "39 significant digits": a 38-significant-digit value
with no integer part *also* goes through DOUBLE. `subsumes()` (`SqlTyping.java:290-303`)
cannot forgive the off-by-one either — it requires `declared.precision() >= computed.precision()`
at equal scale, and here the declared is the NARROWER one.

---

### [UNSOUND] `decimalLitType` constructs a STRUCTURALLY ILLEGAL `SqlType.Decimal(p,s)` with `s > p` for every leading-zero decimal — `0.01D` included

`Math.max(v.precision(), 1)` is used as the precision, but `v.scale()` is used verbatim.
For any `0.0…1`-shaped literal `precision < scale`, so the type says *scale greater than width*,
which is not a legal SQL DECIMAL.

**Repro / actual output** (`DecIllegal.java`, 41 rows `0.<z zeros>1`, z=0..40):
```
literal                                    prec  scl MIR fact      wire       java            note
0.01                                          1    2 D(1,2)        D(3,2)     BigDecimal(0.01) ILLEGAL(scale>precision)
0.0001                                        1    4 D(1,4)        D(5,4)     BigDecimal(0.0001) ILLEGAL(scale>precision)
0.00000000000000000000000000000000000001      1   38 D(1,38)       DOUBLE     Double(1.0E-38) ILLEGAL *** CARRIER MISMATCH
(z=0..40, 41 rows)  ILLEGAL SqlTypes = 40   CARRIER mismatches = 4
```
The type cannot even be spelled — `AnsiSqlRenderer.castTypeName` (`AnsiSqlRenderer.java:924-925`)
renders `SqlType.Decimal` as `"DECIMAL(" + p + ", " + s + ")"`:
```
  render(Cast(0.01, Decimal(1,38))) = SELECT CAST(0.01 AS DECIMAL(1, 38)) AS v
  exec -> Binder Error: DECIMAL type scale cannot be greater than width
```
Today this fact reaches `reconcileLabels`/`OutputCol` but no emitter casts to it, so it
does not currently blow up end-to-end; the illegal value is nevertheless the tree's stored
truth and one adoption away from an emitted `DECIMAL(1,38)`.

---

### [UNSOUND, corroborated] the 39-significant-digit → DOUBLE switch (orchestrator's report) — exact rule and full boundary

Corroborated, and the switch is the single line `SqlTyping.java:1200 if (v.precision() > 38)`.
Reproduced independently (`DecBoundary.java`, `DecWire.java`):
```
1.2345678901234567890123456789012345678   p38 s37 | Decimal(38,37) | DECIMAL(38,37) | BigDecimal(1.2345678901234567890123456789012345678)
1.23456789012345678901234567890123456789  p39 s38 | DOUBLE         | DOUBLE         | Double(1.2345678901234567)
3.14159265358979323846264338327950288419  p39 s38 | DOUBLE         | DOUBLE         | Double(3.141592653589793)
```
and end-to-end `|3.14159265358979323846264338327950288419D` → `[G] Decimal(38,38)` /
`[EXEC-ROW] Double(3.141592653589793)`; `|1.5D` → `[G] Decimal(38,1)` / `[EXEC-ROW] BigDecimal(1.5)`.

**THE FULL BOUNDARY MAP** (`DecSweep2.java` + `DecIllegal.java`; `i` = integer digits
including the leading `0`, `s` = scale, `p` = `BigDecimal.precision()`):

| input shape | MIR fact (`decimalLitType`) | DuckDB 1.5.0 wire | JDBC carrier | agree? |
|---|---|---|---|---|
| `s == 0`, `bits < 64` | `Decimal(p,0)` | emitted as `CAST(x AS DECIMAL(p,0))` → `DECIMAL(p,0)` | BigDecimal | ✅ (conformed by emission, `AnsiSqlRenderer.java:399-404`) |
| `s == 0`, `64 ≤ bits < 128` | `HUGEINT` | `HUGEINT` | BigInteger | ✅ |
| `s == 0`, `bits ≥ 128` | `DOUBLE` | `DOUBLE` | Double | ✅ |
| `s > 0`, `i ≥ 1`, `i + s ≤ 38` | `Decimal(i+s, s)` | `DECIMAL(i+s, s)` | BigDecimal | ✅ |
| `s > 0`, `i ≥ 1`, `i + s > 38` | `DOUBLE` (p = i+s > 38) | `DOUBLE` | Double | ✅ |
| **`s > 0`, `i == 0`, `1 ≤ s ≤ 37`** | **`Decimal(p, s)` with `p ≤ s`** | **`DECIMAL(s+1, s)`** | BigDecimal | ❌ precision understated by ≥1; **illegal type whenever `p < s`** |
| **`s > 0`, `i == 0`, `s ≥ 38`** | **`Decimal(p,38)` (p ≤ 38)** | **`DOUBLE`** | **Double** | ❌ **CARRIER FLIP** |
| `s < 0` (`1E+3`) | normalized to scale 0, then the scale-0 rows | as above | | ✅ |

The rule that decides the carrier is therefore: **BigDecimal iff DuckDB's own width
`max(integerDigits,1) + scale ≤ 38`; Double otherwise** — and the MIR predicts that with
`BigDecimal.precision() > 38`, which is only equivalent when `integerDigits ≥ 1`.

---

### [SILENT FALLBACK] `CarrierStrategies.litText` default arm turns a record's Java `toString()` into a pivot output-column NAME

`core/src/main/java/com/legend/sql/dialect/CarrierStrategies.java:301-307`:
```java
private static String litText(SqlExpr v) {
    return switch (v) {
        case SqlExpr.StringLit s2 -> s2.value();
        case SqlExpr.IntLit i     -> String.valueOf(i.value());
        case SqlExpr.BoolLit b    -> String.valueOf(b.value());
        default -> v.toString();     // :306
    };
}
```
consumed at `:157` as the pivot projection's alias (`litText(v) + "__|__" + u.alias()`).

**Actual output** (`LitText.java`, reflective call on the real method):
```
  StringLit  -> "EU"
  IntLit     -> "7"
  BoolLit    -> "true"
  FloatLit   -> "FloatLit[value=1.5, type=Typed[type=DOUBLE, tolerated=false]]"
  DecimalLit -> "DecimalLit[value=1.5, type=Typed[type=Decimal[precision=2, scale=1], tolerated=false]]"
  DateLit    -> "DateLit[iso=2020-01-01, type=Typed[type=DATE, tolerated=false]]"
  NullLit    -> "NullLit[type=Bottom[]]"
```
A `pivot(~col, [%2020-01-01, …], ~agg)` on a non-native-pivot backend would name its
generated columns with Java debug text. Dialect-side; I did not pin an end-to-end
corpus query that reaches it, so reachability is plausible-not-proven.

---

### [DESIGN — the forbidden catch-all EXISTS] `Call(ADD_INTERVAL | ADD_INTERVAL_TEMPORAL | TIME_BUCKET, [StringLit(<DuckDB function name>), …])` is a free-form-name channel wearing a `StringLit` costume

AGENTS.md §3a: *"No `FunctionCall(String name, args)` catch-all in MIR. Every operation is
its own typed record."* and the stop sign *"a `FunctionCall("someFunc", args)` in a lowering"*
and *"`private static String mapXxxName(String pureName)` in a lowering"*.

All three are violated by the "part-literal-first" convention:

- **Producer** — `core/src/main/java/com/legend/lowering/DateShifts.java:65-79` is literally the
  banned `mapXxxName`: `static String intervalFn(String unitName)` mapping `"YEARS" -> "to_years"`,
  `"DAYS" -> "to_days"`, … The result is wrapped in `new SqlExpr.StringLit(...)` and placed as
  arg 0 (`DateShifts.java:62-63,110-112`, `Scalars.java:780-782,3064-3065`, `CalendarAgg.java:196`).
- **Consumer** — `core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:633-635`:
  ```java
  case ADD_INTERVAL, ADD_INTERVAL_TEMPORAL -> opSpelling(expr(a.get(2), 5) + " + "
          + ((SqlExpr.StringLit) a.get(0)).value()
          + "(" + expr(a.get(1), 0) + ")", parentPrec);
  ```
  The MIR string becomes the SQL function name **verbatim, unquoted, unvalidated**.
  Same at `:639-643` for `TIME_BUCKET`.
- **Three other dialects RE-PARSE that DuckDB name back into their own keyword with a
  `switch (String)`**: `H2.java:212-224 dateUnit(String unitFn)`,
  `EngineStyleH2.java:1411-1425 dbUnitOf(String unitFn)`, `EngineStyleDB2.java:209-224`.
  `EngineStyleH2.java:1475-1478` splices `DATE_DIFF`'s unit string bare into `datediff(<u>, …)`.
  The same convention carries the *operation selector* for `EXTRACT` (`'doy'`, `'isodow'`),
  `DATE_TRUNC` (`'week'|'month'|'quarter'|'year'|'day'`), `DATE_DIFF`, `TIMEZONE` (`'UTC'`).

**Repro / actual output** (`NameChannel.java`):
```
  ADD_INTERVAL StringLit("to_days")             -> SELECT DATE '2020-01-01' + to_days(3) AS v
  ADD_INTERVAL StringLit("NOT_A_FUNCTION")      -> SELECT DATE '2020-01-01' + NOT_A_FUNCTION(3) AS v
  ADD_INTERVAL StringLit("1) ; DROP TABLE t --")-> SELECT DATE '2020-01-01' + 1) ; DROP TABLE t --(3) AS v

  DuckDb.render(Call(ADD_INTERVAL,[Column,…])) -> java.lang.ClassCastException:
      class com.legend.sql.SqlExpr$Column cannot be cast to class com.legend.sql.SqlExpr$StringLit
  H2.render(same)                              -> java.lang.ClassCastException: (same)

  unknown unit "to_fortnights":
    DuckDb : SELECT DATE '2020-01-01' + to_fortnights(1) AS v      <-- silently emitted
    H2     -> DialectCapability: interval unit 'to_fortnights' reached a dialect without a dateadd unit
```
No user-controlled text reaches arg 0 today (`DateShifts.intervalFn` throws on an unknown
`DurationUnit`), so this is **not** an injection vector. It is a structural hole: a
typed `enum IntervalUnit` component would make every one of the above a javac error;
today an unknown unit is a *silent* wrong emission on DuckDB and a loud wall on H2 —
exactly the "no fallbacks" asymmetry the charter forbids — and a wrong arg-0 shape is a
`ClassCastException` ICE at 7 unchecked-cast sites
(`AnsiSqlRenderer.java:634,640,642`, `EngineStyleH2.java:1483,1488`, `H2.java:79,86`).

**Answering the task directly**: `SqlFn.java` is *not* the catch-all — it is a closed
168-constant enum whose renderer arms are exhaustive per dialect. `RawSql.java` is *not*
a catch-all either — it is a String utility class (`splitStatements`, `isSingleQuery`,
`skipLeadingComments`), no MIR variant. The catch-all is the `StringLit`-arg-0 convention
above, plus the two SQL-text carriers listed in §3 below.

---

### [INFORMATION LOSS / INCONSISTENCY] `SqlRewriter` DROPS `SqlExpr.Lambda`'s stored `TypeFact`; `SqlExpr.withChildren` preserves it

`core/src/main/java/com/legend/sql/SqlRewriter.java:245-248`:
```java
case SqlExpr.Lambda l -> {
    SqlExpr b = rewriteExpr(l.body());
    yield b == l.body() ? l : new SqlExpr.Lambda(l.params(), b);   // 2-arg ctor -> UNKNOWN
}
```
vs `core/src/main/java/com/legend/sql/SqlExpr.java:184-186`:
```java
// supplied-leaf knowledge (the builder's, like Column's type): a body swap keeps it
case Lambda l -> new Lambda(l.params(), cs.get(0), l.type());
```
**Actual output** (`RewriteType.java`):
```
  Lambda BEFORE                : Typed[type=Array[element=BIGINT], tolerated=false]
  Lambda .withChildren(same)   : Typed[type=Array[element=BIGINT], tolerated=false]
  Lambda AFTER SqlRewriter     : Unknown[]   <-- body was rewritten
  PRESERVED? false
```
Every other rebuild in `SqlRewriter` either recomputes from children (correct by design)
or explicitly threads supplied knowledge (`Pivot.Using.type()` at `:106`,
`StructLit.Field.declared()` at `:164-165`, `Column.type()` never rebuilt). Lambda is the
one loss. Verified sound for the rest: a 36-variant `withChildren(children())` round-trip
loses **0** types (`Misc.java` §7).

---

### [DEAD TYPE LOGIC] `SqlExpr.Lambda`'s `TypeFact` component is never populated by ANY producer

`grep -rn "new SqlExpr.Lambda(" core/src/main/java` finds **39** construction sites — all in
`com/legend/lowering/*` and `com/legend/sql/dialect/*`, and **every one uses the 2-arg
(UNKNOWN) constructor**. The only readers of `Lambda.type()` are `SqlExpr.java:186` and
`:873`, both of which merely carry it forward. `SqlTyping.callType(LIST_TRANSFORM…)` and
`SqlTyping.foldType` deliberately read `lambda.body().type()`, never `lambda.type()`.
So the component is structurally present and semantically always `Unknown` — the "M2 leaf
supply" the javadoc promises never happened. (This is why the drop above is currently harmless.)

---

### [WRONG TYPING RULE] `DATE_TRUNC_DAY` is typed `TIMESTAMP` but every dialect emits a `DATE`

`SqlTyping.java:389-397` groups `DATE_TRUNC_DAY` with `DATE_TRUNC` and yields `T_TIMESTAMP`.
But `AnsiSqlRenderer.java:624` spells it `"CAST(" + expr(a.get(0),0) + " AS DATE)"`, and
`EngineStyleH2.java:1528` spells `cast(truncate(...) ...)`.
**Actual output** (`FactWire.java`, DuckDB 1.5.0):
```
Call(DATE_TRUNC_DAY,ts)   MIR fact TIMESTAMP   wire DATE   LocalDate(2020-01-01)   <<< FACT != WIRE
```
This is precisely the "rule-vs-emission lie" the file's own `CEILING/FLOOR/SIGN` comment
(`SqlTyping.java` CEILING/FLOOR/SIGN arm, ~:551-563) says was hunted down and fixed — one instance was missed.
Reachable from user Pure: `datePart()`, `isOnDay`, `isAfterDay`, `isOnOrAfterDay`
(`lowering/Scalars.java:716-718, 784-806`). The pure-level decode is driver-object-kind-keyed,
so it currently recovers (`|%2020-01-05T10:20:30->datePart()` → `[EXEC-COL] StrictDate` /
`[EXEC-ROW] StrictDate(2020-01-05)`), but the MIR's stored fact is false and
`subsumes(TIMESTAMP, DATE)` is exactly the arm that hides it.

---

### [WRONG TYPING RULE] `OrderedListAgg` is unconditionally `T_VARCHAR`; the emission is a LIST

`SqlExpr.java:546-550`: `record OrderedListAgg(SqlExpr value, SqlExpr orderBy, TypeFact type) … { public OrderedListAgg { type = SqlTyping.T_VARCHAR; } }`
`AnsiSqlRenderer.java:410-411`: `"list(" + expr(ola.value(),0) + " ORDER BY " + … + ")"`.
**Actual output** (`OlaWire.java`):
```
MIR fact for OrderedListAgg(BIGINT col) = Typed[type=VARCHAR, tolerated=false]
SQL  = SELECT list(t.a ORDER BY t.a) AS v FROM (SELECT * FROM (VALUES (3), (1)) AS t(a)) AS t
WIRE type = INTEGER[]   javaClass = org.duckdb.DuckDBArray   value = [1, 3]
```
Masked today because the single producer (`lowering/Scalars.java:1113-1128`) immediately wraps
it in `ScalarSubquery` + `Cast(_, Array(LITERAL))`, which overrides the fact. Latent.

---

### [WRONG TYPING RULE + DEAD] `EPOCH_SECONDS` typed `BIGINT`; DuckDB's `epoch()` returns `DOUBLE`; no lowering producer exists

`SqlTyping.java:329` lists `EPOCH_SECONDS` in the `T_BIGINT` arm; `Spellings.java:67` maps it
to `"epoch"`.
```
Call(EPOCH_SECONDS,ts)   MIR fact BIGINT   wire DOUBLE   Double(1.5778728E9)   <<< FACT != WIRE
```
`grep -rn "EPOCH_SECONDS" core/src/main/java` shows no `SqlFn.EPOCH_SECONDS` construction
anywhere in `lowering/` — the entry is unreachable, with a wrong rule waiting.

---

### [INFORMATION LOSS / INCONSISTENCY] `reconcileLabels` drops `OutputCol.tolerated` on the ADOPT path; `reconcileUnionLabels` preserves it

`SqlTyping.java:163` — the adoption branch uses the 3-arg `OutputCol` ctor, which defaults
`tolerated=false`:
```java
os.set(i, new OutputCol(oc.name(), computed, oc.nullable()));
```
`reconcileUnionLabels` (`SqlTyping.java:213-215`) computes `oc.tolerated() || tol` and keeps it.
**Actual output** (`Misc.java` §1/§1b):
```
  BEFORE: OutputCol[name=v, type=VARCHAR, nullable=false, tolerated=true]
  AFTER : OutputCol[name=v, type=BIGINT, nullable=false, tolerated=false]   tolerated preserved? false
  union outputs: OutputCol[name=v, type=VARCHAR, nullable=false, tolerated=true]
```
The `tolerated` tag is the §4bZ engine-compat provenance the wire census reads to decide
whether a label/wire divergence is registered or "loud". Losing it on one of the two query
nodes makes an already-registered divergence go red at the next level.

---

### [MIR fact ≠ wire] four more construction-time facts DuckDB does not deliver

`FactWire.java` — MIR stored fact vs the DuckDB type of the **dialect's own emission**:

| node | MIR fact | wire | Java |
|---|---|---|---|
| `IntLit(1)` / `IntLit(2147483647)` | `BIGINT` | `INTEGER` | `Integer` |
| `Call(PLUS, IntLit, IntLit)` | `BIGINT` | `INTEGER` | `Integer` |
| `ArrayLit[1,2]` | `Array(BIGINT)` | `INTEGER[]` | `DuckDBArray` |
| `Reducer(LIST, IntLit)` | `Array(BIGINT)` | `INTEGER[]` | `DuckDBArray` |

`IntLit` is spelled bare (`AnsiSqlRenderer.java:390 String.valueOf(i.value())`), so the
backend widths it by magnitude. Below `2^31` the fact is a lie. The Pure-level decode is
driver-kind-keyed and recovers (`|1` → `Integer(1)`, `|[1,2,3]` → `Integer`), so these are
INCONSISTENCY, not unsoundness, but they are the same class as the DATE_TRUNC_DAY defect.

Observed in passing (outside my scope, flagged for the aggregate/frontend auditors):
`model::Person.all()->project(~[a:p|$p.age])->groupBy(~[], ~[l:x|$x.a:y|$y->sum()])` gives
`[EXEC-COL] l : Integer` with `[EXEC-ROW] BigInteger(103)`; and `|[0.01D, 2.5D]` gives
`[G] type=Decimal(38,1)` with `[EXEC-ROW] BigDecimal(0.01)` (scale 2 in a scale-1 slot).

---

### [WEAK INVARIANT] `SqlAgg`'s "position typing" guards the KIND but not the `Fn`

`SqlAgg.java:5-12` claims *"`LAG` in a GROUP BY position is a javac error, not a runtime one."*
True at the *kind* level (`RankingFn`/`ValueFn` do not implement `SqlExpr`), false at the
`Fn` level: `Reducer` accepts any of the 41 `Fn` constants.
**Actual output** (`Misc.java` §2):
```
  new SqlAgg.Reducer(Fn.LAG, ...) compiled and is an SqlExpr: true
  its TypeFact: Unknown[]
  marker reducer WAVG accepted too, fact=Unknown[] marker()=true
```
16 of 41 `Fn` values yield `UNKNOWN` in `Reducer` position (the 5 markers + the 11
window-only functions) — i.e. the type system's own census counts a mis-constructed node
as "no rule yet" instead of walling.

---

### [LATENT NPE] `SqlQuery.outputs()` is declared non-null but `reconcileLabels` is `@Nullable`-in/`@Nullable`-out, so a null-outputs `SqlSelect` is constructible

`SqlTyping.java:71-73` declares `static @Nullable List<OutputCol> reconcileLabels(..., @Nullable List<OutputCol> outputs)`;
`SqlSelect.java:22-33`'s compact constructor assigns that straight into the *unannotated*
`List<OutputCol> outputs` component; `SqlQuery.java:14` declares `List<OutputCol> outputs();`.
**Actual output** (`NullOuts.java`):
```
  s.outputs() = null
  new ScalarSubquery(nullOutputsSelect)   -> java.lang.NullPointerException (SqlTyping.scalarSubqueryType — SqlTyping.java:733)
  Subselect(...).outputs().size()         -> java.lang.NullPointerException
  SqlUnion(null outputs) constructed, outputs=null
```
I found no production site passing null (grep for the 11-arg `new SqlSelect(... null, null, null)`
across `lowering/`, `resolver/`, `exec/`, `sql/` returns 0 MIR hits), so this is latent only.

---

### [DOC-LIE] AGENTS.md §3a — six claims contradicted by the code

`/home/user/legend-lite/AGENTS.md:205-235`.

1. *"Sealed roots in `com.legend.sql` (15 files, ~1,743 LOC)"* → **18 files, 3736 LOC**.
2. *"`SqlSource` | 8"* → **9**; the omitted variant is `SqlSource.RawSql`, i.e. exactly the
   one that carries raw SQL text.
3. *"`SqlExpr` | 32"* → **36**.
4. *"`SqlAgg` carries `enum Fn` (~35)"* → **41**.
5. *"`SqlType` | `Scalar` enum, `Decimal(p,s)`, `Array`, `Map`"* → **5 variants**, `Struct` omitted.
6. *"**No MIR record has a `String` field encoding a SQL operation.** The single carve-out is
   `SqlExpr.Cast(expr, pureTypeName)` — a *Pure* type name mapped by the dialect."*
   → **`SqlExpr.Cast` has no String component at all.** Reflection:
   `Cast(value: SqlExpr, target: com.legend.sql.SqlType, conform: boolean, type: TypeFact)`.
   The carve-out described does not exist; the javadoc at `SqlExpr.java:892` still says
   *"the target rides as a PURE type"*, which is also false. Meanwhile there are **34**
   String-typed record components (§2 below) and a real operation-name channel (above).

Also contradicted:
- *"Render methods are switch **expressions** with **no `default ->` arm**"* and the stop sign
  *"`default ->` in a render method"* → **25 `default ->` arms** in `com/legend/sql/dialect/`,
  including `AnsiSqlRenderer.java:676` inside `call()` itself.
- The stop sign *"`private static String mapXxxName(String pureName)` in a lowering"* →
  `lowering/DateShifts.java:65 static String intervalFn(String unitName)`.
- *"`ArchitectureTest:80` and `:215` enforce that `sql/` depends on nothing else."* The rule at
  `:215` (`sqlLayerIsFullyStandalone`) scopes `com.legend.sql..`, which **includes
  `com.legend.sql.dialect`** and therefore permits `com.legend.sql → com.legend.sql.dialect`.
  The "No MIR type references a dialect" claim is TRUE in fact (I verified it, §3) but is
  **not** what that test enforces.
- `RawSql.java:13-14` javadoc `{@link com.legend.exec.RawSqlBoundary}` — no such class;
  it is `com.legend.sql.dialect.RawSqlBoundary`.

---

## VERIFIED SOUND

### 1. Sealedness (mechanical, `MirReflect.java`)
All 8 interfaces in `com.legend.sql` report `Class#isSealed() == true` with a non-null
`getPermittedSubclasses()`: `SqlQuery`(2), `SqlSource`(9), `SqlExpr`(36), `SqlAgg`(3),
`SqlType`(5), `TypeFact`(4), `DateFmt`(2), `SqlExpr.WindowCall.Frame.Bound`(7).
No unsealed interface, no permits-less `sealed`. Leaf-count == direct-permit-count for
every root (the hierarchies are flat).

### 2. "No method on a MIR type returns SQL; no `toSql()`/`render()`" — **HOLDS for methods**
Reflection lists every declared `String`-returning method across all 98 classes. There is no
`toSql`, no `render`, no `sql()` **method**. Classified exhaustively:
- 60 auto-generated record `toString()` (structure dumps, not SQL);
- 34 record component accessors (`name()`, `alias()`, `iso()`, `value()`, `table()`, `url()`,
  `field()`, `varName()`, `frameName()`, `tempTableName()`, `unit()`, `enumMapFn()`, `sql()`
  on `SqlSource.RawSql` — a *reader* of carried text);
- `SqlSource#alias()` (interface), overridden to **throw** in `Dual` and `Join`
  (verified: `IllegalStateException: a Dual (FROM-less) source has no alias — caller bug` /
  `a nested join has no single alias — resolve per side`);
- `SqlSelect.Projection#outputName()` — returns the alias or the bare column's name;
- `Json#str()` / `Json#unescapeString(String)` — JSON, not SQL;
- `RawSql#skipLeadingComments(String)` (+ `splitStatements → List<String>`) — *consume* a
  caller-supplied SQL blob, they compose nothing.

**Two carriers do hold SQL TEXT as data**, which the claim's wording does not cover:
- `SqlSource.Join.Kind.sql` — a `public final String` on the MIR enum holding `"JOIN"`,
  `"LEFT OUTER JOIN"`, `"RIGHT OUTER JOIN"`, `"FULL OUTER JOIN"`, `"CROSS JOIN"`,
  `"CROSS JOIN LATERAL"`, `"ASOF LEFT JOIN"`, `"LEFT JOIN LATERAL"`, spliced verbatim by
  `AnsiSqlRenderer.java:245 sb.append(j.kind().sql)` and
  `EngineStyleH2.java:877 j.kind().sql.toLowerCase(Locale.ROOT)`. The MIR owns the join
  spelling; a dialect with a different one cannot express it.
- `SqlSource.RawSql.sql` — a whole statement, rendered
  `AnsiSqlRenderer.java:231-232 sb.append("(").append(r.sql()).append(") AS ").append(ident(r.alias()))`.
  Demonstrated: `SELECT * FROM (SELECT 1 AS a /* anything at all */) AS r`.

### 3. "No MIR type references a dialect" — **HOLDS, mechanically**
- Reflection over fields / method returns / method params / ctor params / record components /
  interfaces / superclasses of all 98 classes: **zero** references containing `dialect`.
- Bytecode constant-pool scan (`javap -p -c` over `com/legend/sql/*.class`): **zero**
  `com.legend.*` references outside `com.legend.sql.*`.

### 4. "`com.legend.sql` is dependency-free" — **HOLDS, with one marker annotation**
Every `import` in all 18 files (`grep -n "^import" *.java`, sorted, complete):
`java.math.BigDecimal`, `java.util.{List, ArrayList, Map, LinkedHashMap, Optional}` — and
**one** exception: `SqlSelect.java:3 import com.legend.Nullable;`.
Fully-qualified non-`java`, non-`com.legend.sql` references: `com.legend.Nullable` only
(24 uses across SqlExpr, SqlSource, SqlTyping, Json, DecodeShapes). `com.legend.Nullable` is
`@Retention(CLASS)` — a marker annotation with no runtime presence, which is why the bytecode
scan is clean. **`com.legend.compiler.element.type.*` appears NOWHERE** in the package
(nor `com.legend.compiler.*`, `com.legend.parser.*`, `com.legend.exec.*`, `com.legend.lowering.*`),
neither in source nor in bytecode. The only cross-package mention is the broken javadoc
`@link` in `RawSql.java:14`.

### 5. Every `String` record component, with declaring record and verdict (EXHAUSTIVE — 34 String-typed + 4 String-bearing collections, from `RecordComponent` reflection over all 75 records)

| # | declaring record . component | verdict |
|---|---|---|
| 1 | `DateFmt.Text.s` | **data** — verbatim punctuation between format directives (`"-"`, `":"`, `"T"`, `" "`, `"."`, `"+0000"`) |
| 2 | `OutputCol.name` | **name** — output column label |
| 3 | `SqlExpr.Column.table` | **name** — source alias (nullable = unqualified) |
| 4 | `SqlExpr.Column.name` | **name** — column identifier |
| 5 | `SqlExpr.DateLit.iso` | **data** — the value, as ISO text rather than `java.time.LocalDate` |
| 6 | `SqlExpr.TimestampLit.iso` | **data** — same; `DuckDb.timestampLit` *parses* it (`lastIndexOf('.')`, sub-µs digit count) to pick `TIMESTAMP_NS` |
| 7 | `SqlExpr.StringLit.value` | **data** — *except* when it is arg 0 of `ADD_INTERVAL`/`ADD_INTERVAL_TEMPORAL`/`TIME_BUCKET`/`EXTRACT`/`DATE_TRUNC`/`DATE_DIFF`/`TIMEZONE`, where it is a **SQL OPERATION SELECTOR** (see the catch-all finding) |
| 8 | `SqlExpr.DeferredTdsString.alias` | **name** |
| 9 | `SqlExpr.PlanParam.name` | **name** — freemarker template variable |
| 10 | `SqlExpr.PlanParam.enumMapFn` | **OPERATION** — a freemarker FUNCTION NAME spliced by `EngineStyleH2.java:493 String fn = p.enumMapFn() + "(" + pn + ")"`. Plan-text channel only (never executed), but a free-form name nonetheless |
| 11 | `SqlExpr.RowOrder.table` | **name** |
| 12 | `SqlExpr.Star.table` | **name** |
| 13 | `SqlExpr.StarExcept.table` | **name** |
| 14 | `SqlExpr.StructGet.field` | **name** — struct field |
| 15 | `SqlExpr.StructLit.Field.name` | **name** |
| 16 | `SqlExpr.TempTableInSplice.tempTableName` | **name** — plan-text temp table |
| 17 | `SqlExpr.WindowCall.Frame.Bound.IntervalPreceding.unit` | **OPERATION** — a free-form SQL INTERVAL UNIT keyword in a frame bound |
| 18 | `SqlExpr.WindowCall.Frame.Bound.IntervalFollowing.unit` | **OPERATION** — same |
| 19 | `SqlSelect.Projection.alias` | **name** |
| 20 | `SqlSelect.SortKey.outputName` | **name** — the TDS column a name-keyed sort addresses |
| 21 | `SqlSource.Pivot.alias` | **name** |
| 22 | `SqlSource.Pivot.Using.alias` | **name** |
| 23 | `SqlSource.RawSql.sql` | **RAW SQL TEXT** — a complete statement, spliced verbatim into FROM |
| 24 | `SqlSource.RawSql.alias` | **name** |
| 25 | `SqlSource.SourceUrl.url` | **OPERATION (encoded)** — the dialect *parses* it and composes a whole subquery by scheme: `DuckDb.java:173-187` dispatches `data:` → `SELECT unnest(CAST(<payload> AS JSON[]))`, `file:` → `read_json_objects(<path>)`, else throws. A structured variant would carry scheme + payload |
| 26 | `SqlSource.SourceUrl.alias` | **name** |
| 27 | `SqlSource.Subselect.alias` | **name** |
| 28 | `SqlSource.Subselect.frameName` | **name + MARKER** — a model identity, but also carries the synthetic sentinel `Subselect.EXISTS_KEYS_FRAME = "existsKeys"` that *switches dialect behaviour* (string-compared, not a typed flag) |
| 29 | `SqlSource.Table.name` | **name** |
| 30 | `SqlSource.Table.alias` | **name** |
| 31 | `SqlSource.Values.alias` | **name** |
| 32 | `SqlSource.VarSetPlaceholder.varName` | **name** — plan variable |
| 33 | `SqlSource.VarSetPlaceholder.alias` | **name** |
| 34 | `SqlType.Struct.Field.name` | **name** |
| a | `SqlExpr.Lambda.params : List<String>` | **names** — lambda parameters |
| b | `SqlExpr.StarExcept.except : List<String>` | **names** — excluded columns |
| c | `SqlSource.Values.columns : List<String>` | **names** |
| d | `PlanProbe.typeNames : Map<String,String>` | **BACKEND TYPE NAMES as raw text** — see §7 |

Score: **28 names, 3 data, 3 operation-encoding (`PlanParam.enumMapFn`, two `unit`s), 1 raw
SQL text, 1 scheme-encoded URL, 1 behaviour-switching marker, 1 conditionally-operation
(`StringLit.value`)** — versus AGENTS.md's "zero, except a `Cast` component that does not exist".

### 6. TYPE COMPLETENESS — every `SqlExpr` variant (`VariantTypes.java`, all 36 constructed and interrogated)

All 36 variants carry a `TypeFact type` record component (verified by reflection). What that
component actually holds:

| always Typed (13) | conditional on children (16) | **hard-wired UNKNOWN (5)** | supplied-leaf (2) |
|---|---|---|---|
| StringLit→VARCHAR, IntLit→BIGINT, FloatLit→DOUBLE, BoolLit→BOOLEAN, DateLit→DATE, TimestampLit→TIMESTAMP, NullLit→BOTTOM, Exists→BOOLEAN, Membership→BOOLEAN, RowOrder→BIGINT, JsonObject→JSON, JsonArrayAgg→JSON, DeferredTdsString→VARCHAR, OrderedListAgg→VARCHAR *(wrong, see finding)*, Cast→`typed(target)` | DecimalLit, ArrayLit, StructLit, StructGet, Call, Case, ScalarSubquery, CheckedOne, CompactList, WindowCall, FoldCall, Group, ReduceCollection, SqlAgg.Reducer | **Star** `SqlExpr.java:378-381`, **StarExcept** `:364-368`, **FormatLit** `:521-525`, **PlanParam** `:487-497`, **TempTableInSplice** `:743-746` | **Column** (`Column.of` supplies; bare ctor = UNKNOWN), **Lambda** (never supplied — dead, see finding) |

**What the dialect does with an absent type** — cross-referenced by grepping all of
`com/legend/sql/dialect/` for `TypeFact` / `.type()` consumption. The result is short:
**the renderers read a node's `TypeFact` in exactly ONE place** —
`AnsiSqlRenderer.java:399-404` (`DecimalLit` scale-0 → emit `CAST(… AS DECIMAL(p,0))`).
`CarrierStrategies.java:283` and `UnqualifyPivotArgs.java:29,36` only *thread* a `Column`'s
existing fact through a rebuild. So the type system's coverage does not drive rendering at all;
**the dialect never "infers" a missing type — it emits text and lets the BACKEND infer**, and
the resulting fact/wire divergences are the ones catalogued above (`IntLit`, `DecimalLit`,
`ArrayLit`, `Reducer(LIST)`, `DATE_TRUNC_DAY`, `EPOCH_SECONDS`, `OrderedListAgg`).
The five hard-wired UNKNOWNs are honest: `Star`/`StarExcept` are not scalar values,
`FormatLit` is a format ride-along, `PlanParam`/`TempTableInSplice` are plan-text-only nodes
that wall in every executable dialect.

Partiality inside the conditional group, measured:
- **14 of 168 `SqlFn` entries** yield `UNKNOWN` for BIGINT / VARCHAR / `Array(BIGINT)` arguments
  alike: `LIST_TRANSFORM, LIST_FLATTEN, MAP_FROM_LISTS, MAP_FROM_ENTRIES, MAP_EMPTY,
  MAP_EXTRACT, MAP_KEYS, MAP_VALUES, TIME_BUCKET, LIST_ZIP, LIST_APPEND, LIST_REDUCE,
  ROUND_HALF_UP, FLOOR_RAW` (several of these are shape-dependent by design and do type
  correctly on the right argument shape — e.g. `MAP_KEYS` over a `Map` type).
- **16 of 41 `SqlAgg.Fn`** yield `UNKNOWN` in `Reducer` position (5 markers + 11 window-only).
- A live census over 17 lowered plans from the fixture model
  (`TypeCensus.java`) walked 49 expression nodes: **49 Typed, 0 Bottom, 0 Raises, 0 Unknown**
  — on this small corpus the ratchet is at zero. (Sampled, not exhaustive: the fixture model
  has no lists, maps, windows, pivots or plan params.)

### 7. `RawSql.java`, `PlanProbe.java`, `TypeFact.java`, `DecodeShapes.java`, `OutputCol.java` — what they are and whether they weaken the closure

- **`TypeFact.java` (51 lines)** — the 4-variant type verdict (`Typed(SqlType, tolerated)`,
  `Bottom`, `Raises`, `Unknown`), sealed, stored on every `SqlExpr`. **Strengthens** the
  closure: it is the type vocabulary itself, and `Unknown` is an honest partiality marker
  rather than a guess. No external dependency.
- **`OutputCol.java` (23 lines)** — `record OutputCol(String name, SqlType type, boolean nullable,
  boolean tolerated)`. The query's declared output contract, in the MIR's own `SqlType`
  vocabulary. **Does not weaken** the closure; the 3-arg convenience ctor is what silently
  drops `tolerated` at `SqlTyping.java:163` (see finding).
- **`DecodeShapes.java` (144 lines)** — pure structural analysis of enum-decode `CASE` chains
  (`flattenDecode`, `sourceExpr`, `sourceColumn`, `stripDecodes`). No types, no dialect, no
  SQL text. **Does not weaken** the closure. One caveat for the rewrite question:
  `stripDecodes` is a rewrite that **deliberately does not preserve the replaced node's type** —
  ```
  decode chain fact  : Typed[type=VARCHAR]
  stripped node fact : Typed[type=INTEGER]   (Column)
  type preserved? false
  ```
  (`Misc.java` §3). That is intended (it replaces the decoded name with the raw source column),
  but it means "every rewrite preserves the SqlType" is not a global invariant.
- **`PlanProbe.java` (14 lines)** — `record PlanProbe(List<OutputCol> outs, Map<String,String> typeNames)`,
  the LIMIT-0 metadata probe's view of a plan. **This is the one real leak of an untyped
  vocabulary into the MIR package**: `typeNames` maps a column name to a *JDBC type NAME string*
  (`"DECIMAL"`, `"HUGEINT"`, …), bypassing `SqlType` entirely. It is produced at
  `exec/PctProbe.java:30-50` from `ResultSetMetaData` and consumed by
  `lowering/PctTdsWrap.java:38,66`, `lowering/Render.java:395-403` and
  `StatementExecutor.java:2793` — i.e. backend type *text* is carried across the execution
  seam back into lowering. It weakens the "SQL layer has its OWN type vocabulary" premise
  (`SqlType.java:3-8`) even though it does not break the package's dependency closure.
- **`RawSql.java` (112 lines)** — a `final` utility class, no MIR variant: `splitStatements`
  (quote-aware top-level `;` splitting), `isSingleQuery` (first-keyword classification against
  a 6-entry `Set<String> QUERY_KEYWORDS = {SELECT, WITH, VALUES, TABLE, SHOW, DESCRIBE}`),
  `skipLeadingComments`, `skipString`. **It is not the forbidden catch-all** — it constructs no
  node and composes no SQL. It does put a small **SQL LEXER** inside the "sealed, immutable,
  data-only" MIR package, which is a category leak (the package javadoc claims "data-only
  records"); and it is paired with `SqlSource.RawSql`, the variant that carries an entire
  SQL statement as a MIR component. Together they are the genuine, documented hole in the
  "no SQL text in the MIR" ratchet.
- **`Json.java` (193 lines)**, not in my brief's list but in the package: a full JSON reader
  (`parse`, `parseOne`, `unescapeString`) with a `lenient` mode that tolerates missing commas
  in engine goldens. Same category leak as `RawSql` — a parser living in the data-only MIR
  package. Its decimal handling is correct and deliberate (`Json.java:177-185`: decimal tokens
  parse to `BigDecimal`, never `double`) — worth contrasting with the `decimalLitType` defect
  above, where the same precision concern was not carried through.

### 8. Other invariants I checked and found CORRECT
- `SqlSource.Join`'s compact constructor enforces ON/kind coupling: CROSS and CROSS_LATERAL
  reject a non-null `on`, every other kind requires one (`SqlSource.java:130-140`).
- `SqlSelect`'s compact constructor rejects `from == null` with the "spells `SqlSource.Dual`,
  never null" message (verified: `NullPointerException` thrown).
- `SqlUnion` rejects fewer than 2 branches.
- `children()`/`withChildren()` is exhaustive over all 36 variants with no `default` arm, and a
  `withChildren(children())` round-trip preserves the stored `TypeFact` on **all 36**
  (`Misc.java` §7: `covered 36 variants (36 distinct); type lost on withChildren: 0`).
- `SqlRewriter` preserves `Pivot.Using.type`, `StructLit.Field.declared`, `Column.type`
  (including the `tolerated` tag), and recomputes composite facts from rewritten children.
  `Reducer`'s tolerated SUM promotion survives a rebuild (`Typed[HUGEINT, tolerated=true]`).
- `SqlTyping.subsumes` is genuinely narrow (TIMESTAMP←DATE, same-scale Decimal widening) and
  `carryThrough` is tag-gated to `Column`s stamped at the mapping seam (`tolerateRead` refuses
  to tag any non-`Column` node) — the "admissibility relation is deleted" claim is TRUE.
- `SqlAgg.Fn.marker()` returns true for exactly {WAVG, HASH_LIST, IS_DISTINCT_MARK,
  UNIQUE_VALUE_ONLY, QDISC_DESC}, matching its javadoc.
- The `numericPromotion` / `decimalArith` / `branchPromote` / `remDecimalType` lattices are
  internally consistent (int widths 1/2/3 → INTEGER/BIGINT/HUGEINT; DOUBLE dominates;
  BOTTOM propagates strictly; UNKNOWN poisons). I did not re-probe their DuckDB receipts.

---

## NOT COVERED
- I did not re-verify the ~17 probed DuckDB decimal-arithmetic receipts behind
  `decimalArith`/`branchPromote`/`remDecimalType`, nor the aggregate promotion matrix behind
  `reducerType` beyond the SUM/AVG/LIST/COUNT/STRING_AGG rows in `FactWire.java`. Those are
  large empirical claims that deserve their own auditor.
- The live UNKNOWN census (§6) is over 17 fixture-model queries only — the fixture has no
  lists, maps, structs, windows, pivots, plan params, unions of >2 branches, or temporal
  carriers, so the "0 UNKNOWN" result is NOT a corpus-wide claim. I state it as sampled.
- `CarrierStrategies.java` (1264 lines), `EngineStyleH2.java` (1664), `EngineStyleDB2.java`,
  `RawSqlBoundary.java` were read only where they consume MIR types (my brief is the MIR).
  The `litText` fallback and the 25 `default ->` arms are reported from that partial read.
- Reachability of the `litText` fallback and of an emitted illegal `DECIMAL(p,s)` with `s > p`
  was not pinned to a specific corpus query; both are demonstrated at the API level only.
- The `PlanProbe` type-name channel's downstream consumers (`PctTdsWrap`, `Render.java:395`,
  `StatementExecutor:2793`) were located but not audited.
