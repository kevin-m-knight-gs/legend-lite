# A10 — Lowering discipline: "The Lowerer does no type inference"

Invariant under test (AGENTS.md §2, `[CONVENTION]` — nothing enforces it):
the Lowerer MAY read type annotations + HIR structure, but MUST NOT infer/resolve
types, do model lookups, compatibility checks, or `instanceof`-on-TYPE dispatch to
pick a MIR shape. **Verdict: the claim is false.** The Lowerer performs model
lookups, family-lattice compatibility checks, TYPE-identity folds, and re-infers
literal types in a way that contradicts the frontend annotation — several of these
produce runtime values that violate the compile-time type.

All repros: `/home/user/probe/probe.sh` and a batch harness over a DuckDB fixture
`/tmp/fx2` (Class `Rec{id,name,nick[0..1],qty:Integer[0..1],amt:Float[0..1],
price:Decimal[0..1],d:StrictDate[0..1],ts:DateTime[0..1],flag:Boolean[0..1]}`),
rows: (1,alice,al,10,…), (2,bob,NULL,NULL,…), (3,carol,ca,30,…).

---

## FINDINGS

### [UNSOUND] `==`/`eq` yields SQL NULL under a `Boolean[1]` (non-null) annotation
`Scalars.java:84-156` — the `equal`/`eq` rule **deliberately** omits the
`optionalOperandGuards` that every ordering operator applies (its own comment,
:79: "equal takes NO optionalOperandGuards DELIBERATELY … the residual both-NULL
divergence … is the reference engine's own behavior"). So a nullable operand
leaks SQL three-valued NULL into a column the frontend typed `Boolean[1]`
(lower=1 ⇒ required, non-null).

Repro:
```
model::Rec.all()->project(~[eq:r|$r.qty == 10, gt:r|$r.qty > 5, ne:r|$r.qty != 10])
```
```
[G]  Relation<(eq:Boolean[1], gt:Boolean[1], ne:Boolean[1])>   (all lower=1, upper=1)
[PLAN] SELECT t0.QTY = 10 AS eq,
              (t0.QTY IS NOT NULL AND t0.QTY > 5) AS gt,
              (t0.QTY IS DISTINCT FROM 10) AS ne
[EXEC-ROW] Boolean(true)  | Boolean(true)  | Boolean(false)
[EXEC-ROW] null           | Boolean(false) | Boolean(true)     <-- bob, qty NULL
[EXEC-ROW] Boolean(false) | Boolean(true)  | Boolean(true)
```
Column `eq` is `Boolean[1]` yet cell 2 is `null`. `gt` (`>`) and `ne` (`!=`)
guard correctly. Pure `==` is total (`[]==10` is `false`, never empty), so the
NULL is wrong in value AND violates the multiplicity. Top prize: static
type/multiplicity contradicted by the runtime value.

### [INCONSISTENCY] comparison operators disagree on null under identical `Boolean[1]`
Same evidence as above. `>,>=,<,<=,between,startsWith,endsWith,contains(String[0..1])`
route through `NullSemantics.optionalOperandGuards` (`Scalars.java:172-182,598-604,
849-866,2120-2130`) → `X IS NOT NULL AND …` → `false` for null.
`==`/`eq` do NOT (`Scalars.java:84`). `!=` uses `IS DISTINCT FROM`
(`NullSemantics.notEqualNullArms`). Three different null disciplines for operators
the frontend stamps identically `Boolean[1]`. Two of the three honor the stamp; `==`
does not.

### [UNSOUND] Decimal(38,38) literal executes as DOUBLE — precision lost, wrong runtime class
`SqlTyping.decimalLitType:1195-1204` — the SQL layer RE-INFERS a decimal literal's
type: `if (v.precision() > 38) return T_DOUBLE;`. The Lowerer emits the literal
bare (`scalarInner: case TypedCDecimal c -> new SqlExpr.DecimalLit(c.value())`,
`Lowerer.java:2359`), carrying no cast to the frontend's declared precision, and
the dialect renders `d.value().toPlainString()` (`AnsiSqlRenderer.java:399-405`),
which DuckDB re-reads as DOUBLE for >38 significant digits.

Repro:
```
|3.14159265358979323846264338327950288419D
```
```
[G]   Decimal(38,38) mult=[1]              <-- frontend annotation
[SQL] SELECT 3.14159265358979323846264338327950288419 AS value
[COL] value : Decimal(38,38)
[ROW] Double(3.141592653589793)            <-- runtime: Double, 16 digits, not BigDecimal
```
Compile-time type `Decimal`, runtime class `java.lang.Double`, precision truncated
from 38 to ~16 digits. This is type inference in the lowering/SQL layer overriding
the frontend, and it is unsound (`probed: typeof(3.14…39digits) = DOUBLE` in DuckDB).

### [DISCIPLINE — backbone] The Lowerer does MODEL LOOKUPS to pick MIR shape
`ClassLayouts.layoutOf(ctx,t)` walks the compiled model (`ctx.findClass`, super-class
walk, stored-property collection — `ClassLayouts.java:81-120`) and
`ctx.findClass(f).isPresent()` are injected as the Lowerer's `classLayout`/`classExists`
(`Compiler.java:359-360`, `:662`, `StatementExecutor.java:520`, `:2317`). The Lowerer
then dispatches MIR shape on the model:
- `Lowerer.java:2588` `classLayout.apply(src.type()).isPresent() && isMany` → `manyPropertyMap` (LIST_TRANSFORM+StructGet)
- `Lowerer.java:2591` `classLayout.apply(...).isPresent()` → `StructGet` extraction
- `Lowerer.java:2384` `classLayout.apply(ct).isEmpty()` → variant-JSON carrier vs struct
- `Lowerer.java:2617,2677` `classLayout.apply(...).orElseThrow()` → the struct layout of `^Class(...)`
- `LayoutTypes.java:70,89` model layout + `classExists.test(ct.fqn())` → `JSON` vs loud wall
- `SeedableLets.java:38-39` constructs a whole trial `Lowerer` over `ctx` to probe seedability

This is precisely "no model lookups" / "no `instanceof`-on-TYPE to pick a MIR shape",
performed pervasively. The whole class-value lowering path is model-driven.

### [DISCIPLINE + UNSOUND] CastPolicy does family-lattice COMPATIBILITY CHECKS and makes cast CONVERT
`CastPolicy.java` computes `Type.Primitive.Family` (`familyOf:208-213`,
`.family()` :212) and runs compatibility decisions: `crossKindRaise:181-206`
(different families ⇒ emit `error(...)`), `isWidening:216-225`, `isSqlPrimitive:227-232`.
On a "narrowing" primitive cast it INSERTS a converting SQL `CAST` (`:75-95`,
`:164-168`) — its own comment (:71-73) admits "DELIBERATE divergence: pure's cast
never converts". Result: `cast` silently changes values.

Repro (decimal/float → integer rounds; timestamp → date drops time):
```
|2.7->cast(@Integer)     -> SQL CAST(CAST(2.7 AS DOUBLE) AS BIGINT)  -> Long(3)
|2.5->cast(@Integer)     -> Long(2)      (half-to-even, silent)
|1.99999999999->cast(@Integer)           -> Long(2)
|%2024-06-15T13:45:59->cast(@StrictDate) -> StrictDate(2024-06-15)   (time silently dropped)
|'42abc'->cast(@Integer) -> SQLException "Could not convert string '42abc' to INT64"  (raw JDBC error, not a Pure cast error)
|123456789012345678901234567890.5D->cast(@Integer) -> SQLException overflow
```
Real Pure `cast(@T)` is a checked reinterpret that never converts; here it is a
family-lattice-driven value conversion (discipline violation) that truncates/rounds
(unsound) or escapes as an internal `SQLException` on bad input (crash on plausible
user input).

### [DISCIPLINE] TYPE-identity folds pick MIR shape from the type (forbidden dispatch)
The Lowerer branches on TYPE identity to emit a constant, exactly the forbidden
"`instanceof CInteger` to pick a MIR shape" pattern:
- `Lowerer.java:2837-2838,3383-3408` `enumTypeMismatch` — `(Type.EnumType)a.fqn().equals((Type.EnumType)b.fqn())` and primitive-kind check ⇒ `new SqlExpr.BoolLit(false)`.
- `MixedEncoding.kindMismatch:289-295` (needle/elem primitive-vs-classish) ⇒ `in` folds to `BoolLit(false)` (`Scalars.java:2434-2437`).
- `Scalars` equal-rule: `partialPrecision`/`isFullPrecisionDate`/`PlatformTypes.isAny`
  (`:111-131`) ⇒ `BoolLit(false)` / different SQL by date precision.
- `Scalars.instanceOfFold:2563-2587`, `Numerics.compareKind` (compare :868-890),
  `Scalars.type()` :1890-1906 — all TYPE-directed shape/constant selection.

Consequence (lowerer decides, frontend did not):
```
|model::Color.RED == model::Size.RED   [G] Boolean[1]   [SQL] SELECT FALSE   [ROW] false
```
Frontend admits the comparison and stamps `Boolean[1]`; the Lowerer inspects the two
enum FQNs and folds to a constant. The type dispatch is doing semantic work the
invariant reserves for the frontend.

### [INFO-LOSS] heterogeneous enum list collapses enum identity to bare strings
`Lowerer.java:2443-2453` / `scalarInner` Any-collection arm. A `[Color.RED, Size.SMALL]`
LUBs to `Any`, rides the `to_json(name)` carrier, and both elements decode as plain
`String`:
```
|[model::Color.RED, model::Size.SMALL]   [G] Any[2]
[SQL] SELECT UNNEST(list_filter([to_json('RED'), to_json('SMALL')], ...))
[ROW] String(RED)   [ROW] String(SMALL)
```
Which enumeration each value belonged to is gone (both are just `'RED'`/`'SMALL'`).
Encode does not preserve, decode cannot recover.

### [DECODE ASYMMETRY] `Integer[1]` beyond Long decodes as BigDecimal/BigInteger, not Long
`scalarInner:2353-2355` promotes an over-Long integer literal to `DecimalLit`;
`Scalars.hugeWiden`/the `minus` rule cast to `DECIMAL`/`HUGEINT`. The column stays
`Integer` but the Java runtime class flips:
```
|-9223372036854775808   [G] Integer[1]   [SQL] CAST(-9223372036854775808 AS DECIMAL(19,0))   [ROW] BigDecimal(-9223372036854775808)
|9223372036854775808    [G] Integer[1]                                                       [ROW] BigInteger(9223372036854775808)
```
One Pure type `Integer` decodes to three Java classes (`Long`, `BigInteger`,
`BigDecimal`) depending on magnitude — inconsistent decode for a single static type.

### [DISCIPLINE — §6] backend-specific tokens/behaviour baked into lowering as StringLit
No raw SQL statements exist in `lowering/` (grep clean), but backend knowledge does:
- `DateShifts.intervalFn:65-79` returns DuckDB interval-constructor **function names**
  `"to_years","to_months","to_weeks","to_days","to_hours",…` as `StringLit`
  arguments (used by `adjust`/`timeBucket`/day-shifts, `Scalars.java:738,782`,
  `DateShifts.java:63,111`). `AnsiSqlRenderer.java:634-642` reads `"to_weeks"` back
  out — a backend function-name vocabulary chosen in lowering, not the dialect.
- `CanonicalRenderSql.java:519-522` compares DuckDB `json_type()` **output strings**
  `"VARCHAR","BIGINT","UBIGINT","DOUBLE","BOOLEAN"` — DuckDB-specific runtime type
  names hardcoded into lowering.
- EXTRACT/DATE_TRUNC part tokens `"isodow","doy","quarter","week","month",…`
  (`Scalars.java:607-664`, `DateShifts.java:55,205`) — portable SQL part names but
  still SQL-vocabulary string data authored in lowering.

Judged on the code: `Render.java`, `PureSql.java`, `LiteralSpelling.java`,
`CanonicalRenderSql.java` build `SqlExpr`/`SqlFn` MIR nodes (no `toSql()`, no raw SQL
text, no SQL function as a call target) — **sanctioned** as MIR builders. Their
string literals are value/CSV/JSON payload (`'`, `\`, `%`, `D`, `\n`, `#TDS`, `"`),
not SQL syntax. `PureSql.type` is the sanctioned Pure→SqlType boundary (maps to the
`SqlType` enum only). The genuine leaks are the two backend-coupled cases above.

### [CRASH] StrictTime literal has no lowering — ICE on valid input
`%10:30:45` (a `TypedCTime`, `StrictTime[1]`) reaches the scalar default arm and
throws `NotImplementedException: scalar lowering not yet implemented for TypedCTime`
(`Scalars.java` scalar tail default; `PureSql.type` also throws for `STRICT_TIME`,
`PureSql.java:91`). Valid Pure input, internal exception.

---

## VERIFIED SOUND (coverage)

- **Literal spelling (§7) is mostly injection-safe and value-faithful.**
  `AnsiSqlRenderer.stringLit:866-887` doubles `'` and splices NUL via `chr(0)`;
  probed exact round-trips: `o'brien`→`'o''brien'`, `a\'b` preserved, unicode
  preserved, newline preserved, NUL `('a'||chr(0)||'b')` → `ab` intact. Date/timestamp
  literals `DATE '…'`/`TIMESTAMP '…'` are not string-interpolated from user text at
  the scalar-literal path, so `%0500-03-04`, `%99999-01-01`, 9-digit subsecond
  (`TIMESTAMP_NS`) render and round-trip. The ONE value-changing spelling is the
  39-digit Decimal→DOUBLE finding above.
- **NULL-safe operators that DO honor the stamp:** `>,>=,<,<=,between,startsWith,
  endsWith,contains(String[0..1]),!=` all coalesce/guard to a total Boolean — verified
  false-for-null.
- **`isEmpty`/`isNotEmpty`/`size`/`and`/`or` empty-identity:** `[]->size()=0`,
  `[]->isEmpty()=true`, filtered-empty `->sum()=NULL`(`[0..1]`), `->size()=0` via
  `COUNT`; consistent with the annotated multiplicities.
- **`in`/`not in` null handling:** `COALESCE(… , FALSE)` and `NOT(in) OR IS NULL`
  produce total Booleans (verified rows).
- **Collection carriers (§5):** `[]`→Nil[0]→SQL NULL→decodes null; homogeneous
  `Integer[3]`/`StrictDate[2]`→plain arrays; heterogeneous `[1,'a']`→`Any[2]` via a
  `VARCHAR[]` of pure-literal spellings decodes element kinds faithfully
  (`Long(1)`/`String(a)`) incl. `at(0)`/`at(1)`/`size`/`contains`; enum lists keep
  the enum column type when homogeneous. Encode/decode agree EXCEPT the heterogeneous
  ENUM case (info-loss finding).
- **`toDecimal` scale rules** (`DecimalKindRules`): Integer→`Decimal(38,0)`,
  otherwise `Decimal(38,18)` — reads the annotation, no re-inference (sound within
  the 38-digit envelope).
- **`Stamps`/`StampCensus`** read `Multiplicity.Bounded` bounds only (annotation
  reads, allowed); `StampCensus.check` is a consistency assert, not inference.

## Grep census (whole `lowering/` package, classified)

Raw hit counts: `instanceof Type.`=88, `instanceof TypedC`=50, `.type()==`=11,
`.type().equals`=5, `Primitive.`=165, `isSubtype`=0, `findClass`=1, `findProperty`=0,
`ModelContext`=2, `typeName()`=13, `.family()`=1, `PlatformTypes`=93.

- **(a) annotation reads (allowed)** — the large majority: `n.args().get(i).info().type()
  == Type.Primitive.X` to select a registered rule, `PlatformTypes.isVariant/isAny/isNil/
  isListCarrier/…` carrier predicates, `PureSql.type`/`sqlTypeOf` at the Pure→SQL boundary,
  `Multiplicity` bound reads. Bulk of `Primitive.` (Scalars 35, MixedEncoding 24, Render 18,
  LiteralSpelling 14), all `PlatformTypes` (93), `typeName()` (error messages, 13).
- **(b) structural / literal-node dispatch (borderline — the invariant's own
  forbidden example is `instanceof CInteger`)**: the 50 `instanceof TypedC*`
  (Lowerer 20, Scalars 11) fold literals by node kind (e.g. `TypedCInteger`,
  `TypedCDate`) to pick spelling/MIR shape.
- **(c) FORBIDDEN — TYPE inference / model lookup / compatibility / TYPE-fold:**
  `CastPolicy` family lattice (`.family()`:212, `familyOf`:208, `crossKindRaise`:181,
  `isWidening`:216, `isSqlPrimitive`:227; 8×`instanceof Type.` + 21×`Primitive.`);
  model lookups `classLayout.apply`(Lowerer:2384,2588,2591,2617,2677; LayoutTypes:70),
  `classExists.test`(LayoutTypes:89), `ctx.findClass`(SeedableLets:39);
  TYPE-identity folds `enumTypeMismatch`(Lowerer:3383-3392, `.fqn().equals`),
  `MixedEncoding.kindMismatch`(:289), `instanceOfFold`(Scalars:2563), equal-rule
  date-precision/`isAny` folds(Scalars:111-131); and the SQL-layer re-inference
  `SqlTyping.decimalLitType` (precision>38→DOUBLE) that the DecimalLit MIR carries.

## NOT COVERED
- Windows/CalendarAgg/Pivots/graph-serialize (`serializeGraph`, `SnapshotEnvelope`,
  `CheckedEnvelope`) lowering was read but not exercised end-to-end for null/precision
  soundness.
- Map/Pair carriers (`newMap`/`put`/`keys`) not executed.
- H2/EngineStyle dialect re-mapping of the DuckDB interval tokens not traced (finding
  §6 stands on the DuckDB path, which reads `to_weeks` back at `AnsiSqlRenderer:642`).
- Whether the frontend independently rejects `Color.RED == Size.RED` in a stricter mode
  (here it typed it `Boolean[1]` and the fold ran).
