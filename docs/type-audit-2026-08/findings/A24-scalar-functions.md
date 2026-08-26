# A24 — Scalar (non-relation) Pure function type contracts, verified against reality

Method: every probe below was RUN. `Compiler.compileQuery` gave the declared Phase-G
type+multiplicity, `Compiler.plan` gave the emitted SQL, `Compiler.executeResolved` ran it on
DuckDB and reported the decoded Java class + value. Harness + fixtures:
`/tmp/claude-0/-home-user-legend-lite/08c9472b-ec93-5963-a98b-42a9c76b294a/scratchpad/a24/`
(`H.java`, `m.pure`, `d.sql`). The full matrix (1549 rows) is
`/home/user/audit/findings/A24-scalar-matrix.tsv`.

Fixture model `t::D` has, for every primitive kind, a NOT-NULL column and a nullable twin
(`i/iN`, `f/fN`, `s/sN`, `b/bN`, `d/dN` StrictDate, `dt/dtN` DateTime, `dec/decN`
DECIMAL(10,2), `c/cN` enum), plus the raw table relation `#>{t::DB.T_D}#`.

## 0. RECONCILIATION (task item 1)

Counts produced by `Recon.java` (reflects `Scalars.RULES` + `Pure.all()`):

| set | count |
|---|---|
| declared natives (`Pure.all()`, distinct signature keys) | **721** |
| scalar-lowerable (`Scalars.RULES`) | **425** |
| declared AND lowerable | **425** |
| lowerable but NOT declared | **0** |
| declared but NOT scalar-lowerable | **296** |

`Scalars.RULES` is keyed by `NativeFunctionDefinition.signatureKey()` and is populated ONLY from
`Pure.nativeKeysAt(...)`, so "lowerable-not-declared" is structurally 0.

The 296 declared-not-lowerable split (full list: `/home/user/audit/findings/A24-declared-not-lowerable.txt`; classified by hand):
* **~150 relation/TDS algebra** handled elsewhere (`Lowerer` dispatches by
  `Pure.nativeNamed`, `Lowerer.java:3415`): project/filter/groupBy/extend/join/over/pivot/
  window/rows/_range/select/rename/limit/drop/slice/distinct/lateral/asOfJoin/write/…
* **~90 non-executable plumbing**: `meta::relational::*` (DDL, executeInDb, toSQLString,
  postprocessors, toPostgresModel, typeInference), `executionPlan::*`, `graphFetch::*`,
  `mapping::*`, `router::*`, `alloy::*`, `meta::relational::tests::csv::toCSV`.
* **28 `date::calendar::*`** (ytd, mtd, wtd, priorDay, CYMinus2, p12wa, …) — handled by
  `CalendarAgg.java`, not `Scalars`.
* **11 special forms** handled directly in `Lowerer`/`Typer`: `lang::if`, `lang::cast`,
  `lang::eval`, `lang::match`, `lang::letFunction`, `lang::extractEnumValue`, `lang::subType`,
  `meta::instanceOf` (`Scalars.instanceOfFold`), `collection::map/filter/fold/…`,
  `variant::navigation::get`, `otherwise`.
* **1 genuinely orphaned scalar**: `meta::pure::functions::boolean::is(Any[1],Any[1])` — see
  finding [CRASH-1].

## FINDINGS

### [UNSOUND] `==` / `eq` on cross-kind primitives compiles and returns a wrong-but-plausible answer
Evidence: `Scalars.java:84-152` — the `equal`/`eq` rule guards only (a) literal-empty operands,
(b) partial-date precision, (c) enums (`EnumSourceValues.decodeInvert`). There is NO
primitive-kind guard; the Integer/String/Boolean cross-kind case falls straight through to
`NullSemantics.equalNullArms` -> bare SQL `=`. `MixedEncoding.kindMismatch`
(`MixedEncoding.java:289-295`) only separates *primitive vs class*, never Number vs String vs
Boolean, and it is called by the `in` rule but never by `equal`. The kind vocabulary that WOULD
decide it (`Numerics.compareKind`, Numbers<Dates<Booleans<Strings) exists and is used only by
`compare` (`Scalars.java:873-874`).

Repro / actual output (each line: query -> declared -> SQL -> value):
```
|1 == '1'                             Boolean[1]  SELECT 1 = '1'                     Boolean(true)
|1 != '1'                             Boolean[1]  SELECT 1 <> '1'                    Boolean(false)
|1 == true                            Boolean[1]  SELECT 1 = TRUE                    Boolean(true)
|'1' == true                          Boolean[1]  SELECT '1' = TRUE                  Boolean(true)
|1.0 == '1'                           Boolean[1]  SELECT CAST(1.0 AS DOUBLE) = '1'   Boolean(true)
|1.0d == '1'                          Boolean[1]  SELECT 1.0 = '1'                   Boolean(true)
|eq('2024-03-15', %2024-03-15)        Boolean[1]  SELECT '2024-03-15' = DATE '...'   Boolean(true)
|equal('2024-03-15', %2024-03-15)     Boolean[1]  SELECT '2024-03-15' = DATE '...'   Boolean(true)
|eq(true, 1)                          Boolean[1]  SELECT TRUE = 1                    Boolean(true)
```
Pure `equal` is value+type equality: all nine are FALSE in real Pure. DuckDB's implicit
coercion turns each into TRUE/FALSE silently. `1 == %2024-03-15` at least errors
(`Conversion Error: Unimplemented type for cast (INTEGER -> DATE)`), so the family is not even
internally consistent.

Full 8x8x6 cross-type matrix (Integer/Float/Decimal/String/StrictDate/DateTime/Boolean/enum x
`== != < > <= >=`, 384 cells) is in the matrix file under ids `cx.*`. Summary:
* `<`,`>`,`<=`,`>=`: the checker REJECTS every cross-kind pair at Phase G (`no overload of
  'lessThan' structurally matches …`). Sound.
* `==`,`!=`: accept EVERY pair (the signature is `equal(Any[*],Any[*])`). Number/String/Boolean
  pairs return a wrong answer; Date-vs-{Number,String,Bool} raise a raw DuckDB conversion
  error; enum-vs-anything correctly folds to `FALSE`.

Why it matters: this is the highest-severity shape named in the brief — it compiles, executes,
and returns a plausible boolean that is wrong.

### [UNSOUND] `==` is the ONLY comparison with no null guard: a declared `Boolean[1]` comes back NULL
Corroborates the orchestrator's note and extends it to the full operator table. Mechanism:
`NullSemantics.equalNullArms` (NullSemantics.java:~135) emits `NULL_SAFE_EQUAL` only when
**both** operands are `SqlExpr.Column` **and both** are `[0..1]`; every other shape gets the bare
`EQUAL`. Ordering comparisons instead go through `NullSemantics.optionalOperandGuards`
(registered at `Scalars.java:153-176`), which adds `IS NOT NULL AND …` for every optional
non-literal operand.

Guard/no-guard table — measured on `t::D` with a NULL in every nullable column
(matrix ids `ng.*`, `eq*`):

| construct | emitted SQL | value when operand NULL | declared |
|---|---|---|---|
| `col? == lit` | `t0.I_N = 7` | **NULL** | Boolean[1] VIOLATED |
| `col? == col(required)` | `t0.I_V = t0.I_N` | **NULL** | Boolean[1] VIOLATED |
| `col? == col?` | `IS NOT DISTINCT FROM` | true | ok |
| `col? == []` / `[] == col?` | `IS NULL` | true | ok |
| `col? != …` | `IS DISTINCT FROM` | true | ok |
| `enumcol? != lit` | `<> 'RED' OR IS NULL` | true | ok |
| `col? < > <= >= lit` | `(IS NOT NULL AND …)` | false | ok |
| `not(col? == lit)` | `IS DISTINCT FROM` | true | ok |
| `col?->in([...])` | `coalesce(… IN …, FALSE)` | false | ok |
| `col?->isEmpty()/isNotEmpty()` | `IS NULL` / `IS NOT NULL` | true/false | ok |
| `col?->contains/startsWith/endsWith` | `(IS NOT NULL AND …)` | false | ok |
| `col? && / \|\| / not(col?)` | — | Phase-G REJECT (`[0..1] not compatible with [1]`) | ok |

Verified for all nine nullable column types (Integer, Float, Decimal, String, StrictDate,
DateTime, Boolean, enum, and column-vs-column). `==` is the sole outlier, in every one.
Pure's `equal` over an empty operand is FALSE, so NULL is doubly wrong.

### [UNSOUND] `toOne()` CONVERTS instead of asserting — it is a universal NULL injector into `[1]`
`multiplicity::toOne<T>(values:T[*]):T[1]`. On a literal the emptiness check fires; on a column
it does not.
```
|[]->toOne()                                    -> EXEC-ERR "Cannot ... toOne" (correct)
|[1,2]->toOne()                                 -> EXEC-ERR (correct)
t::D.all()->project(~[x:p|$p.iN->toOne()])      -> Relation<(x:Integer[1])>  value = null
t::D.all()->project(~[x:p|$p.iN->toOne('boom')])-> Relation<(x:Integer[1])>  value = null
```
INCONSISTENCY: the literal path checks, the column path does not. This is what makes the next
finding reach essentially the whole scalar catalog.

### [UNSOUND] 135 declared-`[1]` results return NULL — no scalar function null-checks its input
NULL propagation sweep (task item 3): every scalar family called with a NULL argument injected
through `$p.<nullable>->toOne()`. Result buckets over 164 targeted probes: **123 returned NULL,
41 returned a value, 0 raised an error.** Across the whole 1549-row matrix, **135 cells declared
`[1]` came back `null`.**

Every one of these declared `[1]` and produced NULL (matrix ids `nu.* nb.* ns.* nd.* nbo.*`):
```
abs sign round floor ceiling sqrt exp log log10 cbrt toFloat toDecimal toDegrees toRadians
sin cos tan atan sinh cosh tanh cot bitNot toString mod rem pow atan2 divide divide/3
plus minus times / bitAnd bitOr bitXor bitShiftLeft bitShiftRight round/2
length toUpper toLower trim ltrim rtrim reverseString ascii encodeBase64
toUpperFirstCharacter toLowerFirstCharacter parseInteger parseFloat parseDecimal
parseBoolean parseDate toString indexOf substring substring/3 replace matches lpad rpad
left right hash levenshteinDistance jaroWinklerSimilarity format regexpCount regexpLike
regexpReplace year monthNumber dayOfMonth dayOfYear weekOfYear quarterNumber
dayOfWeekNumber toEpochValue month quarter dayOfWeek datePart firstDayOfMonth
firstDayOfQuarter firstDayOfWeek firstDayOfYear firstHourOfDay firstMinuteOfHour
firstSecondOfMinute firstMillisecondOfSecond hour minute second adjust dateDiff
mostRecentDayOfWeek previousDayOfWeek timeBucket formatDate isAfterDay isOnDay
fromEpochValue date(y,m,d) not and or xor  ==  at
```
Two do not even need a nullable input to break their `[1]`:
```
|mod(7,0)                      Integer[1]  SELECT MOD(MOD(7,0)+0,0)     -> null
|format('100%%', [])           String[1]   SELECT printf('100%%', NULL) -> null
t::D...groupBy(~[],~[s:x|$x.dec:y|$y->stdDev()])  Number[1] STDDEV_SAMP(one row) -> null
```
`mod(7,0)` returning NULL while `rem(7,0)` raises `Invalid Input Error: Cannot divide 7 by zero`
is also an internal inconsistency.

### [UNSOUND] `hasDay/hasHour/hasMinute/hasMonth/hasSecond/hasSubsecond` on a COLUMN emit an INTEGER, and the decoder hands back `java.lang.Integer` for a declared `Boolean[1]`
Evidence `Scalars.java:811-829`:
```java
return n.args().get(0) instanceof TypedCDate
        ? new SqlExpr.BoolLit(has)
        : new SqlExpr.IntLit(has ? 1 : 0);
```
Repro / actual output:
```
t::D.all()->project(~[x:p|$p.d->hasDay()])   Relation<(x:Boolean[1])>  SQL: 1 AS x   Integer(1)
t::D.all()->project(~[x:p|$p.d->hasHour()])  Relation<(x:Boolean[1])>  SQL: 0 AS x   Integer(0)
t::D.all()->project(~[x:p|$p.d->hasSubsecond()])                       SQL: 0 AS x   Integer(0)
t::D.all()->project(~[x:p|$p.dt->hasHour()])                           SQL: 1 AS x   Integer(1)
```
A `Boolean[1]` cell whose runtime class is `java.lang.Integer`. The same emission also produces
non-ANSI SQL that only works because DuckDB is permissive:
```
t::D.all()->filter(p|$p.d->hasDay())->project(~[x:p|$p.i])   -> ... WHERE 1
t::D.all()->project(~[x:p|$p.d->hasDay() && $p.b])           -> 1 AND t0.B_V AS x
```
The literal path is correct (`|%2024-03-15->hasDay()` -> `Boolean(true)`), so the same Pure
function returns two different Java types depending on whether its argument is a literal.

### [UNSOUND] `times<T>(values:T[*]):T[1]` over an Integer list returns a Double
Evidence `Scalars.java:260-273`: the collection form lowers to `SqlFn.LIST_PRODUCT`, and the
code's OWN comment at `Scalars.java:275-281` states "DuckDB's LIST_PRODUCT degrades to DOUBLE" —
but the repair (`Numerics.decimalChain`) is applied only to DECIMAL-bearing literal lists.
```
|times([2,3,4])   declared Integer[1]   SQL: SELECT list_product([2, 3, 4])   ->  Double(24.0)
```
Sibling: `|plus([true,false])` declared `Boolean[1]` -> `list_sum([TRUE,FALSE])` ->
`BigInteger(1)`.

### [UNSOUND] `Integer[1]` decodes to FOUR different Java classes, one of which exceeds the 64-bit Pure Integer range
Census of 30 expressions all declared `Integer[1]` (matrix ids `i01..i30`):

| Java class | examples |
|---|---|
| `java.lang.Integer` | `1`, `abs(-3)`, `1+2`, `bitAnd`, `[1,2,3]->at(1)`, `mode`, `max([..])`, `compare`, `cast(@Integer)`, `minus([10,2,3])`, `$p.i` column |
| `java.lang.Long` | `length`, `sign`, `round`, `floor`, `size`, `bitShiftLeft`, `year`, `parseInteger`, `hashCode`, `range`, `levenshteinDistance`, `regexpCount`, `dateDiff` |
| `java.math.BigInteger` | `sum([1,2,3])`, `groupBy(...->sum())`, `9223372036854775807 + 1` |
| `java.math.BigDecimal` | `-9223372036854775808` |

Two consequences: (a) any consumer casting a declared `Integer[1]` cell to a fixed Java type
will `ClassCastException` on ~half the catalog; (b) `9223372036854775807 + 1` yields
`BigInteger(9223372036854775808)` and `9223372036854775807 * 2` yields
`BigInteger(18446744073709551614)` — values **outside** the 64-bit range Pure's `Integer`
denotes, in a cell typed `Integer[1]`. (`Scalars.hugeWiden` deliberately widens near-edge
literals to HUGEINT; nothing narrows back.)

### [UNSOUND] `parseDate` / `firstDayOfQuarter` / `firstDayOfThisQuarter` type `StrictDate[1]` but produce DateTime values
```
|'2024-03-15'->parseDate()          StrictDate[1]  CAST('2024-03-15' AS TIMESTAMP)
                                                   -> DateWithSecond(2024-03-15T00:00:00+0000)
|%2024-03-15T10:30:45->firstDayOfQuarter()  StrictDate[1] -> DateWithSecond(2024-01-01T00:00:00+0000)
|firstDayOfThisQuarter()                    StrictDate[1] -> DateWithSecond(2026-07-01T00:00:00+0000)
```
Contrast `datePart()`, also declared `StrictDate[1]`, which correctly yields `StrictDate`. The
Date/StrictDate/DateTime distinction is otherwise carried faithfully (see VERIFIED SOUND).

### [UNSOUND] `parseDecimal(s, precision, scale)` and `toDecimal` claim `Decimal(38,18)` while emitting a different scale
Evidence `Typer.java:1814-1826` (`refineDecimalCarrier` hardcodes `PrecisionDecimal(38,18)` for
exactly `parseDecimal` and `toDecimal`) versus `DecimalKindRules.toDecimal`
(`DecimalKindRules.java:59-73`, which emits `DECIMAL(38,0)` for an INTEGER input).
```
|'4.25'->parseDecimal(10,2)   Decimal(38,18)[1]  SQL CAST('4.25' AS DECIMAL(10, 2))  BigDecimal(4.25)
t::D...project(~[x:p|$p.i->toDecimal()])  Decimal(38,18)[1]
                                          SQL CAST(t0.I_V AS DECIMAL(38, 0))  BigDecimal(7)
```
The user-supplied `(10,2)` arguments are visible to the lowering but invisible to the type; and
the type refiner and the lowering of the SAME function disagree on the scale (18 vs 0).

### [UNSOUND] a 38-scale decimal literal types `Decimal(38,38)` and comes back a `java.lang.Double`
```
|0.123456789012345678901234567890123456789d
   declared Decimal(38,38)[1]
   SQL      SELECT 0.12345678901234567890123456789012345679 AS value   (39th digit dropped at parse)
   value    Double(0.12345678901234568)      <- 17 significant digits, class Double not BigDecimal
```
Two losses in one cell: silent round-to-38 at parse, then a `Decimal(38,38)`-typed value
delivered as a binary double.

### [UNSOUND] `meta::legend::lite::hash(String[1]):String[1]` returns a Long
```
|meta::legend::lite::hash('abc')   declared String[1]
   SQL   CAST(CAST(xor(hash('abc'), CAST(9223372036854775808 AS UBIGINT)) AS HUGEINT) - ... AS BIGINT)
   value Long(1924864467101078684)
```
A `String[1]` cell carrying `java.lang.Long`. (Reachable only by FQN — see [DEAD-2].)

### [UNSOUND] Decimal arithmetic drops all precision/scale; the derivation that exists is dead code
`Type.PrecisionDecimal.plus/minus/times/dividedBy` (`Type.java:201-233`, the Spark/Calcite
derivation) has **zero production callers** — confirmed by
`grep -rn '\.plus(\|\.minus(\|\.times(\|\.dividedBy(' core/src/main/java/com/legend/` (only
BigDecimal/BigInteger hits) and by the usage census: the only main-source references to
`PrecisionDecimal` are subtype/scoring collapses in `InferenceKernel` (12 sites, all
`basePrimitive()`-style), `Typer`'s literal typing + the two-name carrier refinement, and
lowering spellings. **Nothing computes an arithmetic result precision.** What actually decides
the result type is the native's declared return type, i.e. bare `Primitive.DECIMAL`.

Measured on the real `DECIMAL(10,2)` table column (matrix ids `tr*`, `dp*`, `dl*`):
```
#>{t::DB.T_D}#->project(~[x:p|$p.DEC_V])                  Decimal(10,2)[1]  BigDecimal(123.45)
                          ->project(~[x:p|$p.DEC_V->abs()]) Decimal(10,2)[1]  BigDecimal(123.45)   (abs<T> keeps it)
                          ->project(~[x:p|$p.DEC_V + $p.DEC_V]) Decimal[1]    BigDecimal(246.90)
                          ->project(~[x:p|$p.DEC_V * $p.DEC_V]) Decimal[1]    BigDecimal(15239.9025)   scale 4
   x4 product                                            Decimal[1]    BigDecimal(232254628.20950625) scale 8
                          ->project(~[x:p|$p.DEC_V / $p.DEC_V]) Float[1]      Double(1.0)
                          ->project(~[x:p|$p.DEC_V + 1])        Number[1]     BigDecimal(124.45)
                          ->project(~[x:p|$p.DEC_V + 1.5])      Number[1]     Double(124.95)   (decimal -> double!)
                          ->project(~[x:p|$p.DEC_V->round(1)])  Decimal[1]    CAST(ROUND_EVEN(...) AS DECIMAL(38,1))
|1.1d + 2.22d      Decimal[1] (precision lost; operands were Decimal(38,1) and Decimal(38,2))
```
So `Decimal(10,2) * Decimal(10,2)` -> static `Decimal` (which every downstream spelling reads as
the (38,18) default) while the runtime value is a (20,4)-shaped decimal. If the dead derivation
WERE wired it would still not match: it yields (21,4) for one product and (38,6) for the
4-way product, versus the runtime's scale 8.

**REFUTED (the orchestrator's fourth note, second half):** `sum` over a `Decimal(10,2)` column
does NOT keep `(10,2)` — the declared result WIDENS to `Number[1]`, because the only matching
overload is `math::sum(numbers:Number[*]):Number[1]`:
```
#>{t::DB.T_D}#->groupBy(~[],~[s:x|$x.DEC_V:y|$y->sum()])
   declared Relation<(s:Number[1])>   SQL SUM(t0.DEC_V)   BigDecimal(99999999990.00)   (1000 rows)
t::D.all()->groupBy(~[],~[s:x|$x.dec:y|$y->sum()])  -> Relation<(s:Number[1])> too
```
The claim's first half (zero production callers of PrecisionDecimal arithmetic) is CONFIRMED.
`decimal.big`: `|99999999.99d * 99999999.99d` surfaces a raw
`SQLException: Out of Range Error: Overflow in multiplication of DECIMAL(18)` at a cell typed
`Decimal[1]`.

### [UNSOUND] `date::quarter():Quarter[1]` returns a number, not the enum
```
|%2024-03-15->quarter()    declared meta::pure::functions::date::Quarter[1]  ->  Long(1)
|%2024-03-15->month()      declared ...date::Month[1]                        ->  String("March")
|%2024-03-15->dayOfWeek()  declared ...date::DayOfWeek[1]                    ->  String("Friday")
```
`Quarter`'s enum values are `Q1..Q4` (`Pure.java:688-693`); `1` is not one of them, and it is not
even a String like its two siblings. All three break the declared enum type at runtime (an enum
value decodes as a raw scalar), but `quarter` additionally produces a value with no
corresponding enum member.

### [CRASH-1] `meta::pure::functions::boolean::is` is declared, resolvable, and has no lowering — `IllegalStateException` escapes
```
|meta::pure::functions::boolean::is(1,1)
  Phase G: Boolean[1]   (accepted)
  PLAN-ERR: IllegalStateException: no scalar lowering registered for resolved overload
            'meta::pure::functions::boolean::is' with 2 parameter(s)
```
`Scalars.lower` (`Scalars.java:2590-2613`) raises `IllegalStateException` for any resolved
overload with no rule; `is` is the one user-callable scalar in that hole.

### [CRASH-2] `DurationUnit.NANOSECONDS` — a declared enum member — is an internal error in adjust/dateDiff/adjustTemporal
`DateShifts.intervalFn` (`DateShifts.java:66-81`) has cases for YEARS..MICROSECONDS and
`default -> throw new IllegalStateException("unknown DurationUnit for interval arithmetic: ")`.
`NANOSECONDS` is declared at `Pure.java:672-678`.
```
|%2024-03-15T10:30:45->adjust(1, ...DurationUnit.NANOSECONDS)
  PLAN-ERR: IllegalStateException: unknown DurationUnit for interval arithmetic: NANOSECONDS
|%2024-03-15T10:30:45->adjust(-1, ...NANOSECONDS)               same
|meta::legend::lite::adjustTemporal(%..., 1, ...NANOSECONDS)    same
|dateDiff(%2024-01-01T00:00:00, %2025-03-15T10:30:45, ...NANOSECONDS)
  PLAN-ERR: IllegalStateException: unknown DurationUnit for dateDiff: NANOSECONDS
```
All 9 other DurationUnit members work in all three functions (30 probes, matrix `dt.adjust.*`,
`dt.adjustTemporal.*`, `dt.diff.*`).

### [CRASH-3] Generic `plus/minus/times/abs<T>` accept non-numeric `T`; the type error surfaces as a raw DuckDB binder error
The catalog's `abs<T>(number:T[1]):T[1]`, `plus<T>(values:T[*]):T[1]`, `minus<T>`, `times<T>`
carry no numeric bound, and nothing in `Scalars` rejects a non-numeric binding.
```
|-'abc'      G: String[1]   SQL: SELECT 0 - CAST(1 AS VARCHAR)... EXEC-ERR Binder Error: No function matches '-(INTEGER_LITERAL, VARCHAR)'
|-1->toString()  G: String[1] SQL: SELECT 0 - CAST(1 AS VARCHAR)  same Binder Error   <- ordinary user text
|-true       G: Boolean[1]  EXEC-ERR Binder Error
|abs('a')    G: String[1]   EXEC-ERR Binder Error: Could not choose a best candidate
|abs(true)   G: Boolean[1]  EXEC-ERR Binder Error
|abs(%2024-03-15)  G: StrictDate[1]  EXEC-ERR Binder Error
|minus(['a','b'])  G: String[1]   EXEC-ERR Binder Error
|times(['a','b'])  G: String[1]   EXEC-ERR Binder Error: No matching aggregate function
|[1,2,3]->indexOf('a')  G: Integer[1]  EXEC-ERR Conversion Error
```
`-1->toString()` is the one that matters: it is text a user writes by accident (unary minus binds
looser than `->`), it type-checks as `String[1]`, and it dies in the database.

### [CRASH-4] Other internal exceptions escaping on writable input
```
|[1,2,3]->max({a,b|$a - 1})      PLAN-ERR IllegalStateException: comparator max/min: the two
                                 comparator sides must apply the SAME key   (Comparators.java:63)
|meta::legend::lite::trustOne([1,2])  PLAN-ERR IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED
t::D.all()->project(~[x:p|$p.iN->toOneMany()])
        Phase G types it Relation<(x:Integer[0..1])>  -- the native declares T[1..*]!
        EXEC-ERR IllegalStateException: a many-valued cell reached a scalar TDS slot
|%25:00:00                       G-ERR IllegalStateException: time literal '%25:00:00' is out of range
```
The `toOneMany` row is also a multiplicity divergence at Phase G: declared `T[1..*]`, assigned
`[0..1]`.

### [SILENT FALLBACK] `asin`/`acos` fabricate `NaN` from NULL and from out-of-domain columns
The DuckDB rewrite ends with `ELSE (CASE WHEN (x) BETWEEN -1 AND 1 THEN asin(x) ELSE 'NaN'::DOUBLE END)`.
NULL is not `BETWEEN -1 AND 1`, so:
```
t::D.all()->project(~[x:p|$p.iN->toOne()->asin()])   Float[1]  ->  Double(NaN)
```
while the literal path raises the intended error (`|asin(2)` -> `Invalid Input Error: Unable to
compute asin of 2.0`). A NULL input silently becomes a NaN value, and an out-of-domain *column*
value silently becomes NaN instead of erroring like the literal.

### [SILENT WRONG ANSWER] `first(set, count)` ignores `count`
`Scalars.java:1407-1411` registers ONE rule for every `first` overload and hardcodes
`LIST_GET(list, 1)`:
```java
for (String f : Pure.nativeKeysAt("first")) {
    RULES.put(f, (n, args) -> isToOne(n.args().get(0)) ? args.get(0)
            : new SqlExpr.Call(SqlFn.LIST_GET, List.of(args.get(0), new SqlExpr.IntLit(1))));
}
```
```
|[1,2,3]->first(2)  declared Integer[*]  SQL UNNEST(list_filter([list_extract([1,2,3], 1)], ...))  -> 1
|[1,2,3]->first(3)  identical SQL                                                                  -> 1
```
Expected `[1,2]` and `[1,2,3]`. The second argument never reaches the SQL.

### [SILENT WRONG ANSWER] `mod` is NOT "always-positive" for a negative divisor
`SqlFn.java:15` states "MOD is always-positive". `AnsiSqlRenderer.java:575` emits
`MOD(MOD(a,b)+b, b)`, which inherits the divisor's sign.
```
|mod(7,3)   -> 1     |mod(-7,3)  -> 2     (positive: matches DuckDbValidityTest.java:95's claim)
|mod(7,-3)  -> -2    |mod(-7,-3) -> -1    (NEGATIVE: contradicts the stated contract)
```
The repo's own tests only pin positive divisors (`DuckDbValidityTest.java:95` "mod(-7,3) must be
POSITIVE 2", `TypeInferenceIntegrationTest.java:1203-1207` `mod(-12,5) => 3`), so the negative-
divisor half is untested and diverges from the documented invariant.

### [INCONSISTENCY] three different index bases inside one string/collection API
Measured (matrix `st.idx*`, `c.indexOf*`, `st.splitPart*`, `st.re.indexOf`, `st.sub*`):

| function | base | miss sentinel | evidence |
|---|---|---|---|
| `string::indexOf(str, sub)` | **1-based** | **0** | `'abcabc'->indexOf('b')` -> `strpos(...)` -> 2; `indexOf('z')` -> 0 |
| `collection::indexOf(set, v)` | **0-based** | **-1** | `[1,2,3]->indexOf(2)` -> 1; `indexOf(9)` -> -1 |
| `string::regexpIndexOf` | **0-based** | **-1** | `regexpIndexOf('a1b2','[0-9]')` -> 1; NULL input -> -1 |
| `string::splitPart(s,d,i)` | **0-based** | — | `'a,b'->splitPart(',',0)`->'a', `(',',1)`->'b' (SQL `split_part(..., i+1)`) |
| `string::substring(s,start[,end])` | **1-based, `end` treated as LENGTH** | — | `'abcdef'->substring(2)` -> 'bcdef'; `substring(2,4)` -> `substr('abcdef',2,4)` -> 'bcde' |

`Scalars.java:1422-1430` and `1479-1485` document the substring/indexOf choice as a deliberate
engine-golden divergence from "platform pure's 0-based", but nothing reconciles it with the
0-based `collection::indexOf`, `regexpIndexOf` and `splitPart` sitting beside it. Out-of-range
substring indices silently clamp rather than erroring:
`substring(-1)`->'abcdef', `substring(99)`->'', `substring(4,2)`->'de', `substring(2,99)`->'bcdef'.

### [INCONSISTENCY] `cast(@T)` converts rather than asserts, and the two numeric kinds round differently
Corroborates the orchestrator's note and extends it:
```
|2.7->cast(@Integer)   Integer[1]  CAST(CAST(2.7 AS DOUBLE) AS BIGINT)  -> Long(3)
|2.5->cast(@Integer)   Integer[1]                                       -> Long(2)   half-EVEN
|1.5->cast(@Integer)                                                    -> Long(2)
|2.5d->cast(@Integer)  Integer[1]                                       -> Long(3)   half-UP
|'2'->cast(@Integer)   Integer[1]  CAST('2' AS BIGINT)                  -> Long(2)   String -> Integer!
|1->cast(@Boolean)     Boolean[1]                                       -> EXEC-ERR Cast error
```
So `cast` (a downcast assertion in Pure) performs String->Integer conversion and value-changing
numeric rounding, and picks a different rounding mode for `Float` (half-even) than for
`Decimal` (half-up).

### [DIVERGENCE] parse/format functions accept malformed input silently
```
|'yes'->parseBoolean()   Boolean[1]  CAST('yes' AS BOOLEAN)  ->  Boolean(true)   (Pure: error)
|'4.2'->parseInteger()   Integer[1]                          ->  Long(4)         (silently truncates)
|' 42 '->parseInteger()  Integer[1]                          ->  Long(42)
|'Infinity'->parseFloat()Float[1]                            ->  Double(Infinity)
|'abc'->parseInteger()/parseFloat()/parseDecimal()  -> raw SQLException Conversion Error
|'ab'->repeatString(-1)  String[0..1]                        ->  ''              (Pure: error)
|'abc'->left(-1)         String[1]                           ->  'ab'            (DuckDB semantics)
|'abcdef'->lpad(3)       String[1]                           ->  'abc'           (truncates)
|7 / 0                   Float[1]                            ->  Double(Infinity)
```
`Infinity`/`NaN` are not Pure `Float` values; `Float[1]` claiming them is the same class of
defect as the NaN fabrication above.

### [DIVERGENCE] `toString` lexical form differs between the literal and the column path for the same Pure type
```
|%2024-03-15T10:30:45->toString()                          -> "2024-03-15T10:30:45+0000"
t::D.all()->project(~[x:p|$p.dt->toString()])  (same value) -> "2024-03-15T10:30:45.000+0000"
|formatDate(%2024-03-15T10:30:45, DateTimeFormat.ISO8601_NanoSecondPrecision)
                                                           -> "2024-03-15T10:30:45.000000000"  (no offset)
|date(0,1,1)  /  |%0000-01-01                              -> "0-01-01"   (year not zero-padded)
```
Full `toString` census across every type is in the matrix (`ts.*`): Integer `1`, Float `1.5`,
`1.0`->`"1.0"`, Decimal `1.50d`->`"1.50"`, Boolean `true`/`false`, StrictDate `2024-03-15`,
partial dates `2024` / `2024-03`, enum `DAYS` / column enum `RED`.
`toRepresentation`: `1`, `'a'`, `%2024-03-15`; on a NULL string it yields `''`.
`makeString` renders a NULL element as the literal text `TDSNull`.

### [DEAD-1] `Scalars.KNOWN_ABSENT` is 100% stale — a live silent-fallback hatch
`Scalars.java:2514-2521` lists 39 names "known to be ABSENT from our catalog"; `familyIfPresent`
silently skips registration for any name in that set. Measured (`Known.java`): **all 39 are
present** in the catalog (`ascii, atan2, bitAnd, bitOr, bitShiftLeft, bitShiftRight, bitXor,
cbrt, char, cosh, datePart, encodeBase64, generateGuid, hash, hashCode, left,
levenshteinDistance, log10, lpad, ltrim, matches, mean, median, mode, now, reverseString, right,
rpad, rtrim, sinh, split, splitPart, tanh, toLowerFirstCharacter, toUpperFirstCharacter,
toVariant, today, xor, zip`), 0 genuinely absent. The set is dead today, but it is exactly the
"silently guessed / skipped" mechanism the brief forbids: if any of those 39 is renamed, the
registration silently vanishes and the failure moves from class-load to
`IllegalStateException: no scalar lowering registered` at query time.

### [DEAD-2] `Type.PrecisionDecimal` arithmetic derivation — 4 public methods, 0 production callers
See the decimal finding above. `Type.java:201-233` (`plus`, `minus`, `times`, `dividedBy`,
`adjust`, `MIN_ADJUSTED_SCALE`) is exercised only by
`core/src/test/java/com/legend/compiler/element/type/PrecisionDecimalArithmeticTest.java`.

### [DEAD-3] the entire StrictTime surface is unreachable (scope of the orchestrator's third note)
Answer to "establish exactly which functions that blocks": **zero declared natives are blocked,
because the catalog declares NONE.** `grep -c StrictTime core/src/test/resources/native-catalog.txt`
= **0**; the only StrictTime declaration anywhere is the bare class
`Pure.java:205` (`native Class ...StrictTime extends ...Any`, i.e. not even a temporal type).
What IS blocked is the literal itself — the lexer, parser (`SpecParser.java:947`), protocol
(`CTime`), value model (`PureTimeLiteral`, 188 lines) and typer (`Typer.java:178`) all build a
fully-typed `StrictTime[1]`, and every use dies at lowering:
```
|%10:30:45              StrictTime[1]  PLAN-ERR NotImplementedException: scalar lowering not yet implemented for TypedCTime
|[%10:30:45]            same       |%10:30:45 == %10:30:45   same     |%10:30:45->toString()  same
|%10:30:45.123  |%23:59:59          same
t::D.all()->project(~[x:p|%10:30:45])  PLAN-ERR NotImplementedException: object-space expression node TypedCTime is not substitutable yet
|%25:00:00              G-ERR IllegalStateException: time literal '%25:00:00' is out of range   (ICE, not a clean compile error)
```
So: a complete front-half type (lexer through Phase G) with no back half at all.

### [DOC-LIE] `SqlFn.java:15` "MOD is always-positive"
Falsified above by `mod(7,-3) = -2`.

### [DOC-LIE] `Scalars.java:48` "An unregistered overload is a loud error naming the signature"
True but the error is `IllegalStateException`, i.e. an internal error, not a user-facing compile
error — see [CRASH-1].

## VERIFIED SOUND

Coverage instrumentation (`Cov2.java`/`Cov3.java` walk the typed HIR and collect the
`signatureKey` Phase G actually resolved):
* **225 / 225** distinct scalar-lowerable function NAMES exercised (0 missed).
* **402 / 425** registered scalar OVERLOADS resolved by at least one probe.
* **1549 matrix cells**; 1048 OK, 232 Phase-G rejects, 82 database errors, 19 legitimate empties,
  **135 null-in-`[1]` divergences, 13 class divergences, 8 ICEs, 12 not-implemented walls.**

Checked and found correct:
* **Ordering comparisons `< > <= >=`**: every cross-kind pair rejected at Phase G (48 of 48
  off-diagonal cells); every same-kind pair (Number/String/Date/DateTime/Boolean) correct;
  all four operators guard optional operands with `IS NOT NULL AND …`; the `[0..1]` overload
  variants are genuinely reachable (verified `lessThan(Number[0..1],Number[1])`,
  `(Number[0..1],Number[0..1])`, String/Date/Boolean equivalents).
* **`&&`, `||`, `not`, `xor`**: correct values; `[0..1]` argument cleanly REJECTED at Phase G.
  `and([])` = true, `or([])` = false (correct Pure identities).
* **`in`**: `coalesce(..., FALSE)` — `1->in([1,2,3])` true, `9->in(...)` false, `1->in([])` false,
  `1->in([1,'a'])` true, enum-vs-other statically FALSE. (`1->in(['a','b'])` still raises a raw
  conversion error — `kindMismatch` does not cover primitive-vs-primitive.)
* **`isEmpty`/`isNotEmpty`/`contains`/`isDistinct`/`between`** — all correct including the
  nullable-column forms; `contains` with a comparator lambda binds both operands correctly
  (`[1,2,3]->contains(9,{a,b|$a==$b})` -> false, `contains(2,...)` -> true).
* **Rounding**: `round` is half-even in both directions (`round(0.5)=0, 1.5=2, 2.5=2, -0.5=0,
  -1.5=-2, -2.5=-2`), `floor`/`ceiling` correct for all 10 probed values;
  `round(x, scale)` uses `ROUND_EVEN`, `divide(a,b,scale)` uses `ROUND_HALF_UP` (deliberate,
  `DecimalKindRules.java:38-53`).
* **`pow`**: `pow(2,10)=1024.0`, `pow(2,0.5)=1.414…`, `pow(2,-2)=0.25`, `pow(-2,3)=-8.0`,
  `pow(-2,0.5)=NaN`, `pow(0,0)=1.0`. Declared `Number[1]`; Double satisfies it.
* **Trig / bit / stats**: sin cos tan asin acos atan sinh cosh tanh cot atan2 pi cbrt sqrt exp
  log log10 toDegrees toRadians; bitAnd bitOr bitXor bitNot bitShiftLeft bitShiftRight;
  average mean median mode stdDev/Sample/Population variance/Sample/Population percentile (2 and
  4 arg) corr covarPopulation covarSample — all correct values, correct declared types.
  `variance(x,false)` correctly switches to `var_pop` (1.25 vs 1.6667).
* **String unicode**: `length` counts CODE POINTS — `'😀'` -> 1, `'中文'` -> 2, `'é'` (precomposed)
  -> 1; `ascii('😀')` -> 128512; `reverseString('😀ab')` -> `'ba😀'`; `substring('😀ab',0,1)` -> `'😀'`
  (no UTF-16 surrogate splitting anywhere). Turkish-i and ß behave as ICU default
  (`'i'->toUpper()`='I', `'I'->toLower()`='i').
* **Strings**: replace, split, splitPart, trim/ltrim/rtrim, toUpper/toLower,
  toUpper/LowerFirstCharacter, startsWith/endsWith/contains/matches, joinStrings (1/2/4-arg),
  makeString (1/2/4-arg), format (%s %d %.2f, multi-arg), lpad/rpad (2/3-arg, pad string
  repeats correctly), left/right, chunk (even and uneven), repeatString, encodeBase64/
  decodeBase64 (round-trips), md5/sha1/sha256 (values match the known digests for 'abc'),
  levenshteinDistance, jaroWinklerSimilarity, generateGuid (36 chars), currentUserId,
  regexpCount/Extract/IndexOf/Like/Replace including every `RegexpParameter` and groupNumber
  overload.
* **Dates**: `date()` ctors 1..6 args produce exactly the right precision class
  (Year, YearMonth, StrictDate, DateWithHour, DateWithMinute, DateWithSecond, DateWithSubsecond)
  and reject invalid components (month 13, Feb 30, Feb 29 of 2023) — with 2024-02-29 accepted.
  Year 0, year 1, year 9999 and year -1 all construct. All 10 DurationUnits except NANOSECONDS
  work in `adjust`/`adjustTemporal`/`dateDiff`; month-end and leap-day arithmetic correct
  (`%2024-01-31 +1 MONTH` -> 2024-02-29, `%2024-02-29 +1 YEAR` -> 2025-02-28); DST-boundary
  `%2024-03-10T01:30:00 +1 HOUR` -> 02:30 (naive timestamps, no DST shift — consistent).
  year/monthNumber/dayOfMonth/dayOfYear/hour/minute/second/weekOfYear/quarterNumber/
  dayOfWeekNumber (with and without a first-day argument)/toEpochValue (with and without unit)/
  fromEpochValue (with and without unit) all correct; every `has*` predicate correct on
  literals; `mostRecentDayOfWeek`/`previousDayOfWeek` (with and without an anchor) correct for
  Monday/Friday/Sunday; `isAfterDay/isBeforeDay/isOnDay/isOnOrAfterDay/isOnOrBeforeDay` correct;
  `timeBucket`, `formatDate`, `convertDateFormat`, `convertDateTimeFormat`, `parseDateFormat`,
  `convertTimeZoneFormat` (UTC->America/New_York = 06:30:45) correct.
* **Date/StrictDate/DateTime distinction is carried faithfully** in the column path
  (`$p.d` -> `Relation<(x:StrictDate[1])>`/`StrictDate`, `$p.dt` -> `DateTime`/`DateWithSecond`)
  and in partial-date equality (`%2024 == %2024` true, `%2024 == %2024-03-15` false,
  `%2024 < %2024-03-15` true; `$p.d == $p.dt` false, `$p.d < $p.dt` true).
* **Collections (scalar forms)**: add (2 and 3 arg), at, concatenate, distinct, exists, find,
  forAll, head, init, last, tail, indexOf, size, sort (bare / comparator / key+comparator),
  reverse, removeDuplicates (1/2/3 arg), removeDuplicatesBy, uniqueValueOnly (1/2 arg),
  range (1/2/3 arg incl. negative step; step 0 correctly errors), zip, pair, list, newMap,
  get, keys, values, put, putAll (both overloads), maxBy/minBy (lambda, lambda+count, keys,
  keys+count), max/min with comparators, greatest/least, coalesce (all 6 overloads).
  `[1,2,3]->at(9)` and `at(-1)` correctly raise.
* **`sqlTrue`/`sqlFalse`/`sqlNull`/`print`/`println`/`fromJson`/`toVariant`/`type`/
  `evaluateAndDeactivate`/`meta::type`** all behave as declared.
* **Reconciliation is structurally airtight**: 0 lowerable-not-declared, and every rule key is
  drawn from the catalog, so `Scalars` cannot dispatch a signature the catalog does not declare.

## NOT COVERED

* **23 of 425 registered overloads never resolved** by any probe, all of them
  relation/window-shaped or aggregation-carrier overloads that fall outside "scalar":
  `math::average/stdDev/stdDevPopulation/variance` over `(Relation<T>, _Window<T>, T[, ColSpec])`;
  `relation::first/last` over `(Relation<T>, _Window<T>, T)`; `relation::concatenate`,
  `relation::sort` (both spellings), `tds::sort`; `corr/covarPopulation/covarSample/maxBy/minBy`
  over `RowMapper<T,U>[*]`; `collection::max/min` `X[*]`/`X[1..*]`/comparator variants that lost
  overload resolution to `math::max/min` for my numeric literals (they ARE reachable — verified
  `max(['a','b'])` resolves `collection::max(X[1..*])`); `minBy<T>(T[*], key, count)`;
  `meta::legend::lite::typeAsDeclared` (Phase G rejects every argument shape I tried).
* **Non-DuckDB dialects.** Everything ran on DuckDB only. Several findings are dialect-visible
  (`WHERE 1` from `hasDay`, `MOD(MOD(a,b)+b,b)`, `'NaN'::DOUBLE`) and may behave differently or
  fail outright on H2/SQLite/Postgres-style renderers; I did not cross-check.
* **`Aggregates`/`Windows`/`CalendarAgg` reducer paths.** Aggregate functions were exercised only
  in scalar/list position plus a couple of `groupBy` probes; the window/OLAP forms belong to a
  relation auditor.
* **The 28 `date::calendar::*` natives** (ytd/mtd/CYMinus2/…): declared, not in `Scalars`,
  handled by `CalendarAgg` — out of scalar scope, not tested.
* **Concurrency of `NullSemantics`' `ThreadLocal` filter-position flags** — noted but not probed.
