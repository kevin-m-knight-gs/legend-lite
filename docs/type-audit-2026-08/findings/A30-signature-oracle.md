# A30 — SIGNATURE ORACLE: legend-lite `Pure.java` vs. real FINOS Legend Pure

## ORACLE PROVENANCE (task 1 — SOURCE-VERIFIED, no knowledge-based judgements needed)

Both upstream repos were obtained over the network and are on disk:

| repo | path | HEAD | `.pure` files |
|---|---|---|---|
| `finos/legend-pure`   | `/home/user/finos/legend-pure`   | `18cd1bb3824a863e728940309992987d238300da` | 281 |
| `finos/legend-engine` | `/home/user/finos/legend-engine` | `ef037ea68d04f50bb3188a174f9bed380271dc91` | 3247 |

(`mcp__Claude_Code_Remote__add_repo` reported `read_available` for `finos/legend-pure` and the
anonymous git lane served both clones. The first `legend-engine` clone attempt got a transient
HTTP 503; the retry succeeded.)

I wrote a Pure declaration extractor
(`/tmp/claude-0/.../scratchpad/oracle/extract.py` + `norm.py`) that walks every `.pure` file,
strips comments/strings, skips `<<stereotype>>` and `{tagged.value='...'}` header blocks, and
parses **every** `function` / `native function` declaration into
(qualifiedName, typeParams, multParams, params(name,type,mult), returnType, returnMult).
Result: **24 368** real declarations parsed, **24 172** outside `src/test`.
Pure's post-constraint syntax (`Integer[1][name: expr]`) is handled.

The legend-lite side is parsed from the **raw signature strings** inside
`core/src/main/java/com/legend/builtin/Pure.java` (722 `signature("…")` call sites, 1 of which is
the helper itself → **721** signatures), not from the rendered golden file, so no renderer
artifacts pollute the diff. Type-parameter names are alpha-renamed on both sides before comparison,
and FQNs are shortened to their last segment, so `T` vs `Z` and
`meta::pure::metamodel::type::Boolean` vs `Boolean` are not counted as differences.

**Everything below is SOURCE-VERIFIED against those two checkouts. Nothing is knowledge-based.**

## HEADLINE NUMBERS (task 2)

Diffing all 721 legend-lite signatures against the 24 172 real non-test declarations:

| bucket | count | % |
|---|---|---|
| **EXACT** — same FQN, same arg type+mult tuple, same return type+mult | **538** | 74.6% |
| **RET_DIFF** — same FQN + same args, DIFFERENT return type or multiplicity | **16** | 2.2% |
| **ARG_DIFF** — same FQN, no real overload with a matching arg tuple | **131** | 18.2% |
| **NO_SUCH_FUNCTION** — no function with that FQN exists anywhere in real Legend | **36** | 5.0% |

So **183 of 721 (25.4%)** of the catalog diverges from real Legend. The file header
(`Pure.java:14-19`) claims *"Every signature is VERBATIM to its real .pure source (verified per
function; NO divergence categories remain as of 2026-07-08)"*.

Real natives legend-lite is **missing**, restricted to the 11 core `meta::pure::functions::*`
families legend-lite claims to support: **246** (see §MISSING). Across all 51 packages
legend-lite touches: **1031**.

---

# FINDINGS

### [UNSOUND] `percentile`/`corr`/`covarPopulation`/`covarSample` narrow real Pure's `[0..1]` return to `[1]` — the compiler promises non-null and the runtime returns null

The single most dangerous divergence class: legend-lite's declared **return multiplicity is
strictly tighter** than every same-arity real overload.

Exhaustive list (computed, not sampled — 5 of 721):

| function | legend-lite | real Legend | evidence |
|---|---|---|---|
| `math::percentile(Number[*], p)` | `Number[1]` | `Number[0..1]` | Pure.java:1987 vs `core_functions_standard/math/aggregator/percentile.pure:17` |
| `math::corr(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | Pure.java:1222 vs `.../corr.pure:22` |
| `math::covarPopulation(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | Pure.java:1228 vs `.../covarPopulation.pure:22` |
| `math::covarSample(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | Pure.java:1230 vs `.../covarSample.pure:22` |
| `collection::take(Relation<T>[1],Integer[1])` | `Relation<T>[1]` | *(no such overload; real `collection::take(T[*],Integer[1]):T[*]`)* | Pure.java:2199 vs `take.pure:40` |

Real source, verbatim:
```
core_functions_standard/math/aggregator/percentile.pure:17
function <<PCT.function, functionType.ReducerFunction>> meta::pure::functions::math::percentile(numbers: Number[*], percentile: Float[1]): Number[0..1]
core_functions_standard/math/aggregator/corr.pure:22
meta::pure::functions::math::corr(numbersA:Number[*], numbersB:Number[*]):Number[0..1]
```
legend-lite, verbatim (`Pure.java:1987`):
```java
PERCENTILE__NUMBER_MANY__NUMBER_1 = signature("native function meta::pure::functions::math::percentile(numbers:…Number[*], p:…Number[1]):…Number[1];");
```
(Note the parameter type also diverges: real takes `Float[1]`, legend-lite `Number[1]`.)

**Repro** (`/home/user/probe/probe.sh` on the standard fixture):
```
model::Person.all()->project(~[a:p|$p.age])->filter(r|$r.a > 1000)->groupBy(~[], ~[s: x|$x.a : y|$y->percentile(0.5)])
```
**Actual output:**
```
[PLAN] SELECT QUANTILE_CONT(t0.AGE_VAL, CAST(0.5 AS DOUBLE)) AS s
[PLAN] rootType=Relation<(s:Number[1])> mult=[1]
[EXEC-COL] s : Number [NUMBER] mult=[1]
[EXEC-ROW] null | 
```
The compiler statically types the column `Number[1]`; the runtime hands back `null`.
Real Legend types this column `Number[0..1]`, so a real Legend model would be forced to handle
the empty case. **This is exactly the "wrong RETURN MULTIPLICITY licenses the compiler to promise
non-null" failure mode.**

### [UNSOUND] `stdDev` / `variance`: legend-lite drops real Pure's mandatory `isBiasCorrected` param and widens `[1..*]` to `[*]`, keeping the `[1]` return

Real (`core_functions_standard/math/aggregator/stdDev.pure:17`):
```
function <<PCT.function, functionType.ReducerFunction>> meta::pure::functions::math::stdDev(numbers:Number[1..*], isBiasCorrected: Boolean[1]):Number[1]
```
Real (`.../variance.pure:17`): `variance(numbers:Number[*], isBiasCorrected: Boolean[1]):Number[1]`

legend-lite (`Pure.java:2178`, `Pure.java:2239`):
```java
STD_DEV__NUMBER_MANY   = signature("native function meta::pure::functions::math::stdDev(numbers:…Number[*]):…Number[1];");
VARIANCE__NUMBER_MANY  = signature("native function meta::pure::functions::math::variance(numbers:…Number[*]):…Number[1];");
```
Real Pure has NO 1-arg `stdDev`/`variance`. `stdDev`'s input multiplicity was widened from
`[1..*]` (at least one sample) to `[*]` (possibly empty), while the return stayed `[1]` — i.e.
the exact guard real Pure uses to make `Number[1]` honest was removed.

**Repro / actual output:**
```
model::Person.all()->project(~[a:p|$p.age])->filter(r|$r.a > 1000)->groupBy(~[], ~[s: x|$x.a : y|$y->stdDev()])
  [PLAN] SELECT STDDEV_SAMP(t0.AGE_VAL) AS s      rootType=Relation<(s:Number[1])>
  [EXEC-COL] s : Number [NUMBER] mult=[1]
  [EXEC-ROW] null |
```
Same for `variance()` (`VAR_SAMP` → `null` under a `Number[1]` column).

### [SILENT FALLBACK] `relation::limit` accepts `Integer[0..1]` (real Legend requires `Integer[1]`) and an empty limit is silently dropped

Real (`core_functions_relation/relation/functions/slice/limit.pure:18`):
```
native function <<PCT.function>> meta::pure::functions::relation::limit<T>(rel:Relation<T>[1], size:Integer[1]):Relation<T>[1];
```
legend-lite (`Pure.java:1828`):
```java
LIMIT__RELATION_1__INTEGER_0_1 = signature("native function meta::pure::functions::relation::limit<T>(rel:…Relation<T>[1], size:…Integer[0..1]):…Relation<T>[1];");
```
The adjacent comment (`Pure.java:1826`) admits the `[0..1]` was lifted from the **TDS** `limit`
and "registered on the relation carrier's spelling" — a knowing divergence, while the file
header claims none remain.

**Repro / actual output** (fixture has 3 rows):
```
model::Person.all()->project(~[a:p|$p.age])->limit(2)
  [PLAN] SELECT t0.AGE_VAL AS a FROM T_PERSON AS t0 LIMIT 2       → 2 rows
model::Person.all()->project(~[a:p|$p.age])->limit([])
  [PLAN] SELECT t0.AGE_VAL AS a FROM T_PERSON AS t0               → 3 rows   (LIMIT silently gone)
  [G] rootClass=TypedProject     (the limit node is not even built)
```
Real Legend rejects `limit([])` at compile time. legend-lite accepts it and silently ignores the
operation — the repo's own "NO FALLBACKS. NO DEFAULTING." rule.

### [UNSOUND] `date::datePart` narrows real Pure's `Date[1]` return to `StrictDate[1]`

Real (`platform/pure/essential/date/extract/datePart.pure:43`):
```
native function <<PCT.function>> meta::pure::functions::date::datePart(d:Date[1]):Date[1];
```
The real doc block immediately above it (lines 35-38) is explicit that granularity is preserved:
`%1973-11->datePart() // %1973-11   -- granularity kept`.

legend-lite (`Pure.java:1245`): `datePart(d:Date[1]):StrictDate[1]` — plus an extra
**invented** overload at `Pure.java:1243` `datePart(d:Date[0..1]):Date[0..1]` that real Pure does
not have. `StrictDate` is a *subtype* of `Date`: for a year- or month-granularity `Date`
(`%1973-11`) real Pure returns a value that is **not** a `StrictDate`, so the narrowed static
type is not justified by the function's own documented semantics.

### [UNSOUND / INFO-LOSS] `relation::extend` is redeclared over an unconstrained collection `C[*]`, erasing the schema-algebra result type — and crashes on the shape it thereby admits

Real (`core_functions_relation/relation/functions/transformation/extend.pure:20,26`):
```
meta::pure::functions::relation::extend<T,Z>(r:Relation<T>[1], f:FuncColSpec<{T[1]->Any[0..1]},Z>[1]):Relation<T+Z>[1];
meta::pure::functions::relation::extend<T,Z>(r:Relation<T>[1], fs:FuncColSpecArray<{T[1]->Any[*]},Z>[1]):Relation<T+Z>[1];
```
legend-lite (`Pure.java:1309-1310`):
```java
EXTEND__C_MANY__FUNC_COL_SPEC_1       = signature("native function meta::pure::functions::relation::extend<C,Z>(cl:C[*], f:…FuncColSpec<{C[1]->…Any[0..1]},Z>[1]):C[*];");
EXTEND__C_MANY__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<C,Z>(cl:C[*], fs:…FuncColSpecArray<{C[1]->…Any[*]},Z>[1]):C[*];");
```
Two independent divergences: the receiver is any collection instead of `Relation<T>[1]`, and the
result type is `C[*]` instead of `Relation<T+Z>[1]` — the added column is **erased from the type**.

**Repro / actual output:**
```
model::Person.all()->extend(~b:p|$p.age + 1)     ==> model::Person[*]         (column b gone from the type)
[1,2,3]->extend(~b:x|$x + 1)                     ==> Integer[*]               (type-checks at all!)
```
and the second one then dies inside the compiler:
```
[1,2,3]->extend(~b:x|$x + 1)
[PLAN-ERROR] com.legend.error.NotImplementedException: scalar lowering not yet implemented for TypedExtend
[EXEC-ERROR] com.legend.error.NotImplementedException: scalar lowering not yet implemented for TypedExtend
```
→ **CRASH/ICE** on input a user can plausibly write, reachable *only because* the signature was
widened away from real Legend's `Relation<T>[1]`.

### [UNSOUND] 36 signatures name functions that do not exist anywhere in real Legend — and every one I could form a call for is reachable from user-written Pure (task 4)

The header (`Pure.java:14-19`) says the invented ones are "individually-commented INVENTED
pipeline natives … internal plumbing". Two falsifications:

1. **The word `INVENTED` appears exactly once in the whole 2266-line file — in that header itself.**
   `grep -c INVENTED core/src/main/java/com/legend/builtin/Pure.java` → `1`. They are not
   individually commented as invented.
2. **They are not internal.** They are ordinary entries in the same catalog and resolve from
   user query text.

Exact enumeration (36; 33 under `meta::legend::lite::`, plus 3 that squat on real Legend package
names). `R?` = reachable from user-written Pure, verified by running
`Compiler.compileQuery(model, q)`:

| # | signature | Pure.java | R? | probe result |
|---|---|---|---|---|
| 1 | `meta::legend::lite::adjustTemporal(Date[1],Integer[1],DurationUnit[1]):Date[1]` | 1160 | YES | `Date[1]` |
| 2 | `meta::legend::lite::avg(Number[*]):Float[1]` | 1193 | YES | `Float[1]` |
| 3 | `meta::legend::lite::castAsDeclared(Any[0..1],T[1]):T[0..1]` | 1548 | YES | `String[1]` (**note: `[1]`, not the declared `[0..1]`**) |
| 4 | `meta::legend::lite::convertDateFormat(String[0..1],String[1]):StrictDate[0..1]` | 1278 | YES | `StrictDate[0..1]` |
| 5 | `meta::legend::lite::convertDateTimeFormat(String[0..1],String[1]):DateTime[0..1]` | 1279 | YES | `DateTime[0..1]` |
| 6 | `meta::legend::lite::convertTimeZoneFormat(DateTime[0..1],String[1],String[1]):String[0..1]` | 1275 | YES | `String[0..1]` |
| 7 | `meta::legend::lite::divideRound(Number[1],Number[1],Integer[1]):Float[1]` | 1281 | YES | `Float[1]` |
| 8 | `meta::legend::lite::greaterThan(Any[0..1],Any[0..1]):Boolean[1]` | 1936 | YES | `Boolean[1]` |
| 9 | `meta::legend::lite::greaterThanEqual(Any[0..1],Any[0..1]):Boolean[1]` | 1937 | YES | `Boolean[1]` |
| 10 | `meta::legend::lite::hash(String[1]):String[1]` | 1437 | YES | `String[1]` |
| 11 | `meta::legend::lite::isNumeric(String[0..1]):Boolean[0..1]` | 1274 | YES | `Boolean[0..1]` |
| 12 | `meta::legend::lite::join(Relation<T>[1],FuncColSpec<…>[1],cond:…):Relation<T+Z>[1]` | 1494 | (colspec-shaped; not exercised) | — |
| 13-14 | `meta::legend::lite::legacyAssocPredicate(…)` ×2 | 1776,1781 | (needs Row types; not exercised) | — |
| 15 | `meta::legend::lite::legacyLocalProperty(Any[1],String[1]):Any[1]` | 1788 | YES | `Any[1]` |
| 16-17 | `meta::legend::lite::legacyNavigate(…)` ×2 | 1523,1529 | (needs Relation+colspec; not exercised) | — |
| 18 | `meta::legend::lite::lessThan(Any[0..1],Any[0..1]):Boolean[1]` | 1934 | YES | `Boolean[1]` |
| 19 | `meta::legend::lite::lessThanEqual(Any[0..1],Any[0..1]):Boolean[1]` | 1935 | YES | `Boolean[1]` |
| 20-21 | `meta::legend::lite::navigate(Relation…)`, `navigate(C[*],FuncColSpec…)` | 1520,1521 | (colspec-shaped) | — |
| 22 | `meta::legend::lite::navigate(T[*],Function<{T[1]->Boolean[1]}>[1]):T[*]` | 1522 | YES | `Integer[*]` `[TypedNavigate]` |
| 23 | `meta::legend::lite::notEqualAnsi(Any[1],Any[1]):Boolean[1]` | 1926 | YES | `Boolean[1]` |
| 24 | `meta::legend::lite::otherwise(T[1],T[0..1]):T[1]` | 1954 | YES | `Integer[1]` |
| 25 | `meta::legend::lite::parseDateFormat(String[0..1],String[1]):DateTime[0..1]` | 1280 | YES | `DateTime[0..1]` |
| 26 | `meta::legend::lite::sourceUrl(String[1]):Relation<Any>[1]` | 2162 | YES | `Relation<(data:Variant[1])>[1]` `[TypedSourceUrl]` |
| 27-30 | `meta::legend::lite::sub(Decimal/Float/Integer/Number ×2):same` | 2183-2186 | YES | `1->…::sub(2)` ⇒ `Integer[1]`; `1.0->…::sub(2.0)` ⇒ `Float[1]` |
| 31 | `meta::legend::lite::tds(String[1],String[1]):Relation<Any>[1]` | 2203 | YES | `Relation<(b:String[1])>[1]` `[TypedTds]` |
| 32 | `meta::legend::lite::trustOne(T[*]):T[1]` | 1164 | YES | `[1,2,3]->…::trustOne()` ⇒ `Integer[1]` |
| 33 | `meta::legend::lite::typeAsDeclared(Any[0..1],T[1]):T[0..1]` | 1541 | YES | `String[1]` (**not the declared `[0..1]`**) |
| 34-35 | `meta::pure::mapping::execute(Function,Any,Any,Any[*][,Any]):Result<T>[1]` ×2 | 1628,1629 | (needs Result plumbing) | real Legend has **no** `meta::pure::mapping::execute` — the real entry point is `meta::pure::router::execute(FunctionDefinition, Mapping, Runtime, Extension[*])`, `core/pure/router/router_entry.pure:20` |
| 36 | `meta::pure::tds::getString(TDSRow[1],String[1]):String[1]` | 2079 | rejects non-TDSRow arg | In real Legend `getString` is a **qualified property on `TDSRow`**, not a free function — no `meta::pure::tds::getString` declaration exists in either checkout |

**Verbatim probe output for the reachable ones** (`Compiler.compileQuery`, fixture model):
```
[1,2,3]->meta::legend::lite::trustOne()                 ==> Integer[1]   [TypedNativeCall]
1->meta::legend::lite::sub(2)                           ==> Integer[1]   [TypedNativeCall]
meta::legend::lite::tds('a','b')                        ==> Relation<(b:String[1])>[1]   [TypedTds]
[1,2]->meta::legend::lite::avg()                        ==> Float[1]     [TypedNativeCall]
'x'->meta::legend::lite::hash()                         ==> String[1]    [TypedNativeCall]
1->meta::legend::lite::otherwise(2)                     ==> Integer[1]   [TypedNativeCall]
1->meta::legend::lite::notEqualAnsi(2)                  ==> Boolean[1]   [TypedNativeCall]
1->meta::legend::lite::lessThan(2)                      ==> Boolean[1]   [TypedNativeCall]
meta::legend::lite::sourceUrl('http://x')               ==> Relation<(data:…Variant[1])>[1]   [TypedSourceUrl]
'2020-01-01'->meta::legend::lite::convertDateFormat('yyyy')  ==> StrictDate[0..1]
1->meta::legend::lite::typeAsDeclared(@String)          ==> String[1]    [TypedNativeCall]
1->meta::legend::lite::castAsDeclared(@String)          ==> String[1]    [TypedCast]
'12'->meta::legend::lite::isNumeric()                   ==> Boolean[0..1]
[1,2]->meta::legend::lite::navigate(x|$x > 1)           ==> Integer[*]   [TypedNavigate]
1->meta::legend::lite::legacyLocalProperty('p')         ==> Any[1]
1->meta::legend::lite::divideRound(2, 3)                ==> Float[1]
%2020-01-01->meta::legend::lite::adjustTemporal(1, …DurationUnit.DAYS) ==> Date[1]
1->meta::legend::lite::greaterThan(2) / greaterThanEqual(2) / lessThanEqual(2) ==> Boolean[1]
'x'->meta::legend::lite::convertDateTimeFormat('yyyy') / parseDateFormat('yyyy') ==> DateTime[0..1]
%2020-01-01T00:00:00->meta::legend::lite::convertTimeZoneFormat('UTC','yyyy') ==> String[0..1]
```
**21 of the 36 are demonstrably callable from user Pure.** None of them exists in any real Legend
model, so any query using them is unportable, and any Legend model ported *to* legend-lite gets a
type system whose public surface is 21 functions wider than the language it claims to implement.

Sub-finding (**INCONSISTENCY**): `typeAsDeclared`/`castAsDeclared` are declared to return `T[0..1]`
(Pure.java:1541,1548) but the type checker produces `String[1]` for a `[1]` input — the catalog
entry is not what the compiler uses.

### [UNSOUND] `relation::tableReference` — different arity, different return type from the real function of the same FQN

Real (`core_relational/relational/…/tableReference.pure:17`):
`meta::relational::functions::database::tableReference(db:Database[1], schema:String[1], name:String[1]):Table[1]`

legend-lite (`Pure.java:2190`, `Pure.java:2197`):
`tableReference(String[1],String[1]):Relation<Any>[1]` and `tableReference(String[1],String[1],String[1]):Relation<Any>[1]`

The first param is a `String` instead of the `Database` element, the 2-arg form does not exist
upstream, and the return type is `Relation<Any>` instead of `Table`. The header lists
`tableReference` as one of the "INVENTED pipeline natives", but it is **not** invented — it is a
real function whose signature was rewritten, which is worse: a Legend model that calls the real
3-arg `tableReference(db, schema, table)` will not type-check here, and vice versa.

### [INFO-LOSS] Whole-family divergence: `meta::pure::tds::*` is redeclared over `Relation<T>` instead of `TabularDataSet`

7 of the 11 `meta::pure::tds::` signatures swap real Legend's `TabularDataSet` / `TDSRow` for
`Relation<T>`:

| lite (`Pure.java`) | real |
|---|---|
| `tds::filter(Relation<T>[1], Function<{T[1]->Boolean[1]}>[1]):Relation<T>[1]` (1322) | `filter(TabularDataSet[1], Function<{TDSRow[1]->Boolean[1]}>[1]):TabularDataSet[1]` `tds.pure:434` |
| `tds::sort(Relation<T>[1],String[1],SortDirection[1]):Relation<T>[1]` (2157) | `sort(TabularDataSet[1],String[1],SortDirection[1]):TabularDataSet[1]` `tds.pure:369` |
| `tds::project(K[*],Function[*],ids:String[*]):Relation<K>[1]` (2089) | `project(K[*],Function[*],ids:String[*]):TabularDataSet[1]` `tds.pure:347` |
| `tds::groupBy(C[*],FuncColSpecArray…,AggColSpec…):Relation<Z+R>[1]` (1428) | `groupBy(TabularDataSet[1],String[*],AggregateValue<T,U>[*]):TabularDataSet[1]` `tds.pure:629` |
| `tds::groupBy(… AggColSpecArray …)` (1429) | *(no such real overload)* |
| `tds::tdsContains(T[1],Function[*],tds:Relation<Z>[1]):Boolean[1]` (2085) | `…tds:TabularDataSet[1]…` `tds.pure:824` |
| `tds::tdsContains(… tds:Relation<Z>[1], crossOperation …)` (2087) | `…tds:TabularDataSet[1]…` `tds.pure:831` |

plus `tds::asc`/`tds::desc` which take `ColSpec<T>[1]` and return `SortInfo<T>[1]` where real
Legend takes `String[1]` and returns `SortInformation[1]` (`tds.pure:695,702`), and
`tds::tableToTDS(Relation<Any>[1]):Relation<Any>[1]` where real is
`tableToTDS(Table[1]):TableTDS[1]` (`tableToTDS.pure:22`).
Note the `project` return type `Relation<K>[1]` is not even internally coherent: `K` is the
*source element* type, not a relation schema — and the compiler in fact produces
`Relation<(a:Integer[1])>[1]` for `project([p|$p.age],['a'])`, so the catalog entry is dead text.

### [UNSOUND] `collection::first(T[*], Integer[1]):T[*]` is an invented overload that real Legend does not have

Real (`platform/pure/grammar/functions/collection/slice/first.pure:18`) declares **exactly one**
`first`:
```
native function <<PCT.function>> { doc.doc='Returns the first element of the collection, or nothing if the collection is empty', … }
    meta::pure::functions::collection::first<T>(set:T[*]):T[0..1];
```
legend-lite adds `Pure.java:1345`
`first<T>(set:T[*], count:Integer[1]):T[*]`.
**Repro / actual output:**
```
[1,2,3]->first(2)
  [G] type=Integer mult=[*]
  [PLAN] SELECT UNNEST(list_filter([list_extract([1, 2, 3], 1)], x -> x IS NOT NULL)) AS value
  [EXEC-ROW] Integer(1) |
```
It compiles, plans and executes — and returns **1** row for `first(2)`, so the invented overload is
also wrong on its own terms.

### [DIVERGENCE] 16 return-type / return-multiplicity differences (exhaustive)

Full list, all source-verified (lite line vs real file:line):

| function (args) | legend-lite return | real return | real source |
|---|---|---|---|
| `collection::add(T[*],Integer[1],T[1])` | `T[*]` | `T[1..*]` | `add.pure:44` |
| `collection::add(T[*],T[1])` | `T[*]` | `T[1..*]` | `add.pure:56` |
| `math::corr(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | `corr.pure:22` |
| `math::covarPopulation(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | `covarPopulation.pure:22` |
| `math::covarSample(Number[*],Number[*])` | `Number[1]` | `Number[0..1]` | `covarSample.pure:22` |
| `date::datePart(Date[1])` | `StrictDate[1]` | `Date[1]` | `datePart.pure:43` |
| `date::dayOfWeekNumber(Date[1],DayOfWeek[1])` | `Integer[1]` | `Integer[1]` **+ post-constraint** `firstDayMondayOrSundayOnly` | `dayOfWeekNumber.pure:17` |
| `date::fromEpochValue(Integer[1])` | `Date[1]` | `DateTime[1]` | `dateExtension.pure:543` |
| `date::fromEpochValue(Integer[1],DurationUnit[1])` | `Date[1]` | `DateTime[1]` | `dateExtension.pure:548` |
| `graphFetch::graphFetchChecked(T[*],tree)` | `Checked[*]` (raw) | `Checked<T>[*]` | `graphFetch.pure:32` |
| `graphFetch::graphFetchChecked(T[*],tree,Integer[1])` | `Checked[*]` (raw) | `Checked<T>[*]` | `graphFetch.pure:38` |
| `relational::extension::relationalExtensions()` | `Any[*]` | `Extension[*]` | `extension.pure:62` |
| `relation::columns(Relation<T>[1])` | `Column<Nil,Any>[*]` | `Column<Nil,Any\|*>[*]` | `columns.pure:18` |
| `tds::project(K[*],Function[*],ids)` | `Relation<K>[1]` | `TabularDataSet[1]` | `tds.pure:347` |
| `sqlQueryToString::sqlNull()` | `Nil[0]` | `SQLNull[1]` | `dbExtension.pure:1025` |
| `math::wavgUtility::wavgRowMapper(Number[0..1],Number[0..1])` | `RowMapper<Number,Number>[1]` | `WavgRowMapper[1]` | `mathUtility.pure:19` |

Also (found via the same run, in the ARG_DIFF bucket because a parameter also differs):
`postProcessor::replaceTables` and `postProcessor::nonExecutable` declare `Result<SelectSQLQuery>[1]`
where real is `Result<SelectSQLQuery|1>[1]` (`postProcessor.pure:342`, `nonExecutablePostProcessor.pure:24`).

### [DIVERGENCE] 131 argument-shape divergences, bucketed (exhaustive listing in Appendix A)

| bucket | count |
|---|---|
| no real overload of that **arity** exists (invented arity) | 34 |
| legend-lite uses `Any` where real has a concrete type (type erasure) | 18 |
| same arity, some **parameter multiplicity** differs | 37 |
| same arity, parameter **types** differ only | 42 |

Highest-consequence members:

* **`math::plus` / `minus` / `times` (17 signatures, Pure.java:1895-1899, 1996-2001, 2204-2208).**
  Real Legend declares these **only** in the variadic collection form
  (`plus(Integer[*]):Integer[1]`, `plus(Float[*])`, `plus(Decimal[*])`, `plus(Number[*])`,
  `plus.pure:44,58,66,…`). legend-lite adds 2-arg forms for every numeric type **and**
  `math::plus(String[1],String[1]):String[1]` — real Legend's string concatenation is
  `meta::pure::functions::string::plus(String[*]):String[1]`, a different package.
* **`math::divide(Number[1],Number[1],Integer[1]):Decimal[1]` (Pure.java:1283).** Real
  (`divide.pure:66`) is `divide(Decimal[1],Decimal[1],Integer[1]):Decimal[1]`. legend-lite widens
  the operands to `Number`, so `1.5->divide(2.0, 3)` type-checks here and is a compile error upstream.
* **`math::abs<T>(T[1]):T[1]` (Pure.java:1116).** Real has four monomorphic overloads only —
  `abs(Integer[1])`, `abs(Float[1])`, `abs(Number[1])`, `abs(Decimal[1])` (`abs.pure:40,47,53,58`).
  The unconstrained `T` accepts non-numeric receivers.
* **`math::maxBy`/`minBy` (10 signatures, Pure.java:1876-1880, 1901-1905).** Real's key argument is
  `Number[*]` or `RowMapper<T,Number>[*]` (`maxBy.pure:19,25,30,35`); legend-lite generalises the
  key to `Function<{T[1]->Any[1]}>[1]` and `T[*]`, admitting non-orderable keys.
* **`relation::over` (16 signatures, Pure.java:1955-1964, 2124-2128).** Real threads the
  subset constraint through every partition/sort spec: `ColSpec<(?:?)⊆T>[1]`,
  `SortInfo<(?:?)⊆T>[*]` (`over.pure:18,31,42,54,65,77,85,109,117,…`). legend-lite drops `⊆T`
  everywhere (`ColSpec<T>[1]`, `SortInfo<T>[*]`), so a window partitioned on a column that is not
  in the relation is no longer a type error.
* **`relation::rename` (Pure.java:2111).** The `ColSpecArray<Z⊆T>,ColSpecArray<V>` overload does not
  exist upstream — real Legend has exactly one `rename` (`rename.pure:18`).
* **`relation::sort(Relation<T>[1], String[*])` (Pure.java:2158).** Real
  (`sort.pure:18`) is `sort<T,Z>(Relation<Z>[1], SortInfo<T⊆Z>[*])`. legend-lite adds a
  string-name-based sort — no column-membership checking. Verified reachable:
  `…->sort(['a'])` ⇒ `Relation<(a:Integer[1])>[1]`.
* **`relation::join` / `asOfJoin` 5-arg `prefix:String[1]` overloads (Pure.java:1175, 1495)** — real
  Legend has 3- and 4-arg forms only (`join.pure:26`, `asofjoin.pure:18,20`).
* **`relation::write(Relation<T>[1]):Integer[1]` (Pure.java:2254)** — real requires the accessor:
  `write<T>(Relation<T>[1], RelationElementAccessor<T>[1]):Integer[1]` (`write.pure:20`).
* **`mapping::from(Relation<T>[1]):Relation<T>[1]` (Pure.java:1353)** — real `from` has 16
  overloads and every one takes at least a mapping/runtime/dataspace second argument
  (`mappingExtension.pure:1..391`). A 1-arg `from` does not exist.
* **`lang::compare(Any[1],Any[1]):Integer[1]` (Pure.java:1216)** — real is
  `compare<T>(T[1],T[1]):Integer[1]` (`compare.pure:17`); the `Any` version admits comparing a
  `String` to a `Date`.
* **`collection::max/min(T[*], comparator)` (Pure.java:1969,1971)** — real
  (`max.pure:37`, `min.pure:37`) requires `T[1..*]` and returns `T[1]`.
* **18 executionPlan/router/toSQLString entries** replace `Mapping`, `Runtime`, `Extension`,
  `DatabaseType`, `ExecutionContext`, `DebugContext`, `Store`, `Database`, `ExecutionPlan`,
  `DbConfig` with bare `Any` — e.g. `meta::pure::router::execute(Function,mapping:Any[1],
  runtime:Any[1],extensions:Any[*])` vs real
  `execute(FunctionDefinition<{->T[y]}>[1], m:Mapping[1], runtime:Runtime[1], extensions:Extension[*]):Result<T|y>[1]`
  (`router_entry.pure:20`), and `meta::core::runtime::connectionByElement(Runtime[1],Any[1])` vs
  real `(Runtime[1],Store[1])` (`runtimeExtension.pure:79`).

### [MISSING] 246 real functions in the 11 core families legend-lite claims to support are absent (task 2)

Per family (real-count / lite-has / missing):

```
boolean          15 / 12 /   3   equalJsonStrings, isFalse, isTrue
collection       86 / 57 /  29   agg, allButOneAreEmpty, appendTreeToNode, containsAll, containsAny,
                                 defaultIfEmpty, dropAt, getIfAbsentPutWithKey, getMapStats,
                                 getPropertyValues, intersection, isEqual, keyValues, lookup, merge,
                                 mergeInstance, newMultiValueMap, oneOf, paginated,
                                 parseObjectReferences, partition, remove, removeAll, repeat,
                                 replaceAll, replaceTreeNode, toIndexed, toNumbered, union
date             67 / 50 /  17   ISO8601DateFormat, ISO8601DateTimeFormat, SimpleDateTimeFormat, add,
                                 average, convertTimeZone, daysOfMonth, firstDayOfThisWeek,
                                 formatDateISO8601, formatDateTimeISO8601NanoSecondPrecision, hasYear,
                                 inSeconds, isLeap, subtract, systemDefaultTimeZones, timeFromSeconds,
                                 validateDateTimeFormat
hash              2 /  2 /   0
lang             21 /  8 /  13   copy, dynamicNew, evaluate, identity, mayRemoveOverride, mutateAdd,
                                 new, orElse, rawEvalProperty, removeOverride, stringIdentity,
                                 toMultiplicity, whenSubType
math             77 / 60 /  17   angularDistanceInRadians, binFloor, calculateInverseFunction,
                                 containsVariableExpression, covariance, distanceHaversineDegrees,
                                 distanceHaversineRadians, distanceSphericalLawOfCosinesDegrees,
                                 distanceSphericalLawOfCosinesRadians, earthRadius,
                                 findVariableExpression, flipParametersValues,
                                 handlesParamReconstructionForDivision, random,
                                 reverseFunctionPrecedence, squareOfHalfTheChord,
                                 wrapInsideNumberInstanceValue
meta            116 /  6 / 110   (the whole reflection surface: properties, propertyByName, subTypeOf,
                                 genericTypeClass, functionReturnType, newClass, reactivate, …)
multiplicity      5 /  2 /   3   hasLowerBound, isMultiplicityConcrete, isZeroOne
relation         71 / 42 /  29   aggColSpec, aggColSpecArray, colSpec, colSpecArray, equalAll, equalAny,
                                 eval, exists, funcColSpec, funcColSpecArray, greaterThanAll/Any,
                                 greaterThanEqualAll/Any, in, joinStrings, lessThanAll/Any,
                                 lessThanEqualAll/Any, printMul, s, toCSVString, wrapPrimitiveInTDS, …
string           70 / 45 /  25   decodeUrl, encodeUrl, equalIgnoreCase, getCharType, humanize, inRange,
                                 isAlphaNumeric, isDigit, isLetter, isLowerCase, isNoLongerThan,
                                 isNoShorterThan, isUUID, isUpperCase, lastIndexOf, makeCamelCase,
                                 makeStringMatch, pad, parseCSV, plus, splitIntoLines,
                                 splitOnCamelCase, substr, substringAfter, substringBefore
```
Verified reachable-or-not by probe: `defaultIfEmpty`, `orElse`, `substringBefore`, `lastIndexOf`,
`isTrue` all fail with
`TypeInferenceException: unknown function '<name>' — no function of this name in the native or user catalog`.
(That is a *clean* error, not a crash — see VERIFIED SOUND.) Also missing but benign-by-design:
`isEmpty(Any[0..1])` / `isNotEmpty(Any[0..1])` overloads (`isEmpty.pure:31`), the
`or(Boolean[1..*])` overload (`or.pure:22`), `average(Integer[*])`/`average(Float[*])`
(`average.pure:22,27`), `get(T[*],String[1]):T[0..1]` and `get(List<T>[1],Integer[1]):T[1]`
(`get.pure:17`, `collectionExtension.pure:220`), and 8 of the 12 real `date::max`/`date::min`
`[1..*]→[1]` overloads.

### [INCONSISTENCY] The catalog is NOT the single source of truth: 8 more function names are typed by the compiler but appear in no signature at all

`Pure.java:29-33` javadoc: *"the single source of truth for Pure-name strings in the system.
Every consumer … should reference natives by these constants, not by string lookups."*

`core/src/main/java/com/legend/compiler/spec/Typer.java` type-checks these names via literal string
desugars with no catalog entry (`tdsVocab(fn, simple)` at `Typer.java:607,615,636,643,672,681,694`
and `af.function().equals(...)` at `Typer.java:652,657`):

| name | in Pure catalog? | in real Legend? | probe (`Compiler.compileQuery`) |
|---|---|---|---|
| `union` | **no** | yes (`collectionExtension.pure:67`) | `[1,2]->union([3])` ⇒ `Integer[*]` |
| `paginated` | **no** | yes (`collectionExtension.pure:236`) | `[1,2,3]->paginated(1,2)` ⇒ `Integer[*]` `[TypedSlice]` |
| `renameColumns` | **no** | yes | `…->renameColumns([pair('a','b')])` ⇒ `Relation<(b:Integer[1])>[1]` |
| `renameColumn` | **no** | yes | `…->renameColumn('a','b')` ⇒ `Relation<(b:Integer[1])>[1]` |
| `restrict` | **no** | yes | `…->restrict(['a'])` ⇒ `Relation<(a:Integer[1])>[1]` `[TypedSelect]` |
| `restrictDistinct` | **no** | yes | `…->restrictDistinct(['a'])` ⇒ `[TypedDistinct]` |
| `columnValues` | **no** | yes | `…->columnValues('a')` ⇒ `Integer[*]` `[TypedMap]` |
| `olapGroupBy`, `projectWithColumnSubset`, `window`, `func`, `pathWithAlias` | **no** | yes | desugared in `Typer` |
| `columnByName`, `getNullableString`, `isNotNull` | **no** | **no** | special-cased in code but currently rejected at the wall |

Consequence for this audit and for any future one: **auditing the 721 signatures does not audit the
public function surface.** The `union`/`restrict`/`columnValues` family bypasses the catalog entirely,
so nothing pins their argument or result shapes against anything.

### [DOC-LIE + TEST-GAP] `catalogMatchesTheGoldenFile` is a self-comparison, and its renderer is blind to the two multiplicity forms that matter (task 5)

**(a) The golden file is a render of the code it "guards".** Confirmed by running the test's own
`renderCanonical` over `Pure.all()` and diffing against the resource:
```
catalog size (Pure.all())    = 721
golden file size (non-#)     = 721
LINE DIFFERENCES golden-vs-code = 0
```
(`/tmp/…/scratchpad/probes/GoldenProbe.java`, run via `jrun.sh`.) The test at
`NativeFunctionTest.java:52-66` reads `src/test/resources/native-catalog.txt` and asserts it equals
that render; its own comment says *"regenerate the resource"* on change. It therefore pins the
catalog **against itself** and tests nothing about real Legend. Confirmed as the orchestrator read it.

**(b) The renderer erases information, so the golden file cannot even catch some *internal*
changes.** `NativeFunctionTest.renderType` (`NativeFunctionTest.java:90-107`) renders `Generic` as
`name<args>` — dropping `multiplicityArguments()` — and renders `RelationType` columns as
`name:type` — dropping the column multiplicity. Demonstrated by running both variants of three
signature pairs through the exact renderer:
```
A: …:Result<String|1>[1];      B: …:Result<String|*>[1];
  renderA = test::f():…Result<…String>[1]
  renderB = test::f():…Result<…String>[1]
  GOLDEN FILE SEES A DIFFERENCE? false

A: …:Relation<(a:Integer[1])>[1];   B: …:Relation<(a:Integer[0..1])>[1];
  GOLDEN FILE SEES A DIFFERENCE? false

A: …(x:Relation<(a:Integer[1])>[1]):Integer[1];  B: …(x:Relation<(a:Integer[*])>[1]):Integer[1];
  GOLDEN FILE SEES A DIFFERENCE? false
```
The parser *does* retain `multiplicityArguments` (probe: `Generic[…, multiplicityArguments=[1]…]`),
and `Pure.java` does use the syntax elsewhere (`Result<T|m>`, `Result<…SelectSQLQuery|1>`) — so
this is renderer loss, not parser loss. Net effect: **flipping a relation column from `[1]` to
`[0..1]` inside any signature produces a ZERO-line diff in the only catalog-wide guard.**

**(c) The other 30 tests in `NativeFunctionTest` do not help.** `*_pinShape` tests
(lines 132-311) restate the same declaration as a hand-built `NativeFunctionDefinition` in Java —
same data, twice. `headlineNativesAreAllPresent` (line 360) only checks 37 **simple names** exist.
`noTwoOverloadsCollapseToSameSignatureKey` (line 109) checks internal uniqueness.
`everyTypePositionFqnInNativeSignaturesResolvesToCatalog` (line 1167) checks internal closure.
None of them references any upstream artifact.

**(d) The one mechanism that touches real Legend is opt-in and absent here.**
`core/src/test/java/com/legend/rcorpus/Corpus.java:48-51` reads a local legend-engine checkout at
`${legend.engine.root}` defaulting to `${user.home}/legend/legend-engine`, and
`Corpus.available()` (line 68) makes the whole sweep skip when the directory is missing.
`ls ~/legend/legend-engine` → `No such file or directory` on this machine, so it skips. And even
when present it runs *relational test queries*, so it can only catch a divergence that some corpus
query happens to exercise — it cannot see extra permissiveness (invented overloads), wrong return
multiplicities on paths the corpus does not hit, or missing functions the corpus does not call.

**(e) `Pure.java` parses its own catalog with legend-lite's own superset dialect.**
`Pure.signature()` (`Pure.java:1090-1092`) calls
`ElementParser.parse(pureSignature, Dialect.LEGEND_PLATFORM)` — and `Pure.java` is one of only two
files whitelisted for that platform surface (`PlatformSurfaceGuardrailTest.java:32-49`). So a
signature written in syntax the real engine would reject still loads cleanly.

**Honest confidence in the catalog: 74.6% of its signatures are byte-for-byte defensible against
upstream; 25.4% are not; and no mechanism in the repository can detect that, because the only
catalog-wide guard compares the catalog to a file generated from the catalog.** The header's
claim of "NO divergence categories remain" is false in at least the 6 categories tabulated above.

---

## TASK 3 — HIGH-RISK FAMILIES: declared vs. declared vs. observed

All `==>` lines are actual output of `Compiler.compileQuery` on the standard fixture
(`/home/user/probe/fx/model.pure`); `[PLAN]/[EXEC]` lines are actual `probe.sh` output.

| fn | real Legend (file:line) | legend-lite (Pure.java) | observed |
|---|---|---|---|
| `first` | `first<T>(T[*]):T[0..1]` `first.pure:18` — **only overload** | same at :1344 **+ invented** `first(T[*],Integer[1]):T[*]` :1345 | `[1,2,3]->first()` ⇒ `Integer[0..1]`; `[1,2,3]->first(2)` ⇒ `Integer[*]`, executes, **returns 1 row** |
| `last` | `last<T>(T[*]):T[0..1]` `last.pure:38`; relation form `last.pure:18` | identical (:1498,:1499) | `[1,2,3]->last()` ⇒ `Integer[0..1]` ✔ |
| `at` | `at<T>(T[*],Integer[1]):T[1]` `at.pure:41` | identical (:1178) | `at(0)` ⇒ `Integer[1]`; `at(99)` ⇒ `Integer[1]` statically, runtime `SQLException: … trying to get an element at offset 99 where the collection is of size 3` — matches real Pure's own runtime-checked semantics |
| `get` | `get(Map<U,V>[1],U[1]):V[0..1]` `get.pure:17`; **also** `get(T[*],String[1]):T[0..1]` and `get(List<T>[1],Integer[1]):T[1]` | only the Map form (:1975) + 2 variant-navigation forms | Map form ✔; the other two real overloads are missing |
| `max`/`min` | `max(T[1..*]):T[1]`, `max(T[*]):T[0..1]`, `max(T[1..*],cmp):T[1]` `max.pure:17,27,37` | those 3 **+ invented** `max(T[*],cmp):T[0..1]` :1969 | `[1,2,3]->max()` ⇒ `Integer[0..1]` ✔; `[]->max()` ⇒ `TypeInferenceException: ambiguous overload of 'meta::pure::functions::date::max': 7 candidates tie` — real Pure resolves this |
| `sum` | `sum(Number[*]):Number[1]`, `sum(Integer[*])`, `sum(Float[*])` `sum.pure:17,22,27` | identical (:2187-2189) | `[1,2,3]->sum()` ⇒ `Integer[1]` ✔; `[]->sum()` ⇒ `ambiguous overload … 3 candidates tie`; over an empty SQL group ⇒ `[EXEC-ROW] null` under an `Integer[1]` column (**real Pure declares `[1]` too — shared unsoundness, not a divergence**) |
| `average` | `average(Number[*]):Float[1]` + `Integer[*]`/`Float[*]` overloads `average.pure:17,22,27` | only `Number[*]` (:1180) + window form | `[]->average()` ⇒ `Float[1]`; empty group ⇒ shared with real |
| `toOne` | `toOne(T[*]):T[1]`, `toOne(T[*],String[1]):T[1]` `toOne.pure:46,64` | identical (:2224,:2225) | `[]->toOne()` ⇒ `Nil[1]` statically; runtime `SQLException: Cannot cast a collection of size 0 to multiplicity [1]` — matches real |
| `toOneMany` | `toOneMany(T[*]):T[1..*]` ×2 `toOneMany.pure:18,33` | identical (:2222,:2223) | `[]->toOneMany()` ⇒ `Nil[1..*]` — matches real's own claim |
| `removeDuplicates` | 3 overloads `removeDuplicates.pure:48,62,78` | all 3 identical (:2099,:2107,:2108) | `[1,2,3]->removeDuplicates()` ⇒ `Integer[*]` ✔ |
| `fold` | `fold(T[*],Function…,accumulator:V[m]):V[m]` `fold.pure:52` | identical except param name `init` (:1348) | `⇒ Integer[1]` ✔ (param name only) |
| `filter` | `collection::filter` `filter.pure:18`, `relation::filter` `filter.pure:20`, `tds::filter(TabularDataSet…)` `tds.pure:434` | first two identical; **`tds::filter` retyped over `Relation<T>`** (:1322) | `⇒ Integer[*]` / `Relation<…>[1]` ✔ |
| `map` | 4 overloads `map.pure:19,35,43` + relation `map.pure:18` | all 4 identical (:1847-1850) | `[1,2,3]->map(x\|$x+1)` ⇒ `Integer[3]` ✔ |
| `size` | `size(Any[*]):Integer[1]` `size.pure:17`; relation `size.pure:18` | `size<T>(T[*])` (:2151) — `T` instead of `Any`; relation identical | `⇒ Integer[1]` ✔ (benign) |
| `isEmpty` | `isEmpty(Any[*])` + `isEmpty(Any[0..1])` `isEmpty.pure:17,31` | one overload `isEmpty<T>(T[*])` (:1472) | `⇒ Boolean[1]` ✔ |
| `indexOf` | collection `indexOf.pure:38`; string `indexOf.pure:39,45` | all 3 identical (:1453-1455) | `⇒ Integer[1]` ✔ |
| `parseInteger` / `parseFloat` / `parseDecimal` | `parseInteger.pure:38`, `parseFloat.pure:38`, `parseDecimal.pure:17,23` | all identical (:1986,:1985,:1983,:1984) | `'x'->parseInteger()` ⇒ `Integer[1]` statically; runtime `SQLException: Could not convert string 'x' to INT64` — matches real |
| `substring` | `substring.pure:38,53` | identical (:2181,:2182) | `⇒ String[1]` ✔ |
| `toString` | `toString(Any[1]):String[1]` `toString.pure:47`; relation ×2 | all identical (:2227,:1742,:1743) | `⇒ String[1]` ✔ |
| `cast` | `cast<T\|m>(Any[m],T[1]):T[m]` `cast.pure:49` | identical (:1203) | `1->cast(@String)` ⇒ `String[1]` `[TypedCast]` ✔ |
| `instanceOf` | `instanceOf(Any[1],Type[1]):Boolean[1]` `instanceOf.pure:47` | identical (:1457) | `⇒ Boolean[1]` ✔ |
| `match` | `match.pure:53,73` | both identical (:1874,:1875) | ✔ |
| `if` | `if(Boolean[1],Function,invalid:Function):T[m]` `if.pure:43`; Pair form `if.pure:65` | identical **except param name** `else` vs `invalid` (:1451) | `if(true,\|1,\|2)` ⇒ `Integer[1]` ✔ |
| `and` | `boolean::and(Boolean[1],Boolean[1])` `and.pure:17`; `collection::and(Boolean[*])` `and.pure:17` | both identical (:1167,:1168) | ✔ |
| `or` | same two **+ `collection::or(Boolean[1..*]):Boolean[1]`** `or.pure:22` | first two only (:1944,:1945) | ✔; third overload missing |
| `not` | `not(Boolean[1]):Boolean[1]` `not.pure:17` | identical (:1938) | ✔ |

---

## VERIFIED SOUND

* **538 of 721 signatures are byte-identical** (modulo type-parameter naming and FQN abbreviation)
  to a real declaration in `finos/legend-pure` @18cd1bb or `finos/legend-engine` @ef037ea. That
  includes the whole calendar family (30 signatures), the whole `toOne`/`toOneMany`/`cast`/`match`
  group, all of `parse*`/`substring`/`toString`/`indexOf`, `at`, `last`, `map`, `fold` (bar the
  param name), `filter`, `removeDuplicates`, `and`/`or`/`not`, and the `sum`/`stdDevPopulation`/
  `stdDevSample`/`variancePopulation`/`varianceSample` aggregates.
* **The three runtime-guarded "unsound-looking" claims are faithful to real Pure, not divergences:**
  `at(T[*],Integer[1]):T[1]`, `toOne(T[*]):T[1]`, `parseInteger(String[1]):Integer[1]`. All three
  produce a clean SQL-level error at runtime rather than a wrong value
  (`Invalid Input Error: … element at offset 99 …`, `Cannot cast a collection of size 0 to
  multiplicity [1]`, `Conversion Error: Could not convert string 'x' to INT64`).
* **`sum` over an empty group returning null under an `Integer[1]` column is real Pure's own
  claim** (`sum.pure:17` declares `Number[*]:Number[1]`) — I checked before attributing it.
  Same for `variancePopulation`/`varianceSample`/`stdDevSample`/`stdDevPopulation` 1-arg forms.
* **The unknown-function wall is clean, not a crash.** `defaultIfEmpty`, `orElse`,
  `substringBefore`, `lastIndexOf`, `isTrue`, `isNotNull`, `isNull`, `columnByName`,
  `getNullableString` all produce
  `TypeInferenceException: unknown function '<n>' — no function of this name in the native or user
  catalog (unported platform function, or a misspelling)` — a user-facing message, no stack escape.
* **The parser genuinely supports every grammar form in the catalog** (class-load succeeds; 721/721
  parse), including schema algebra `T-Z+V`, subset `⊆`, wildcard columns `(?:K)`, multiplicity
  parameters `<T|m>`, and generic multiplicity arguments `Result<T|m>`. Verified independently by
  parsing three hand-written signatures through `ElementParser.parse(…, LEGEND_PLATFORM)`.
* **`noTwoOverloadsCollapseToSameSignatureKey` is genuinely useful** — it would catch a real
  duplicate — it just says nothing about upstream fidelity.

## NOT COVERED

* **Overload *resolution* semantics** (which candidate wins for a given call) were only spot-checked
  — `[]->max()` and `[]->sum()` fail with "ambiguous overload … N candidates tie", which real Pure
  resolves. I did not enumerate every ambiguity the invented overloads create; that deserves its own
  pass.
* **Native *class* and *enum* catalogs** (`Pure.allNativeClasses()`, `allNativeEnums()`, pinned at
  sizes 48/… by `NativeFunctionTest:389,1105`) were not diffed against real `Class`/`Enum`
  declarations. Only functions.
* **The 12 invented signatures I could not form a call for** (`legend::lite::join`,
  `legacyAssocPredicate` ×2, `legacyNavigate` ×2, two `navigate` colspec forms,
  `mapping::execute` ×2, and the `tds::getString` TDSRow form) are reachable *in principle* — they
  are ordinary catalog entries and the resolver found `getString` by name (it rejected only the
  argument type: `expected meta::pure::tds::TDSRow, got Integer`) — but I did not build the
  colspec/Row-shaped receivers needed to complete the call, so I report them as "not exercised"
  rather than claiming reachability.
* **`legend-pure` and `legend-engine` `src/test` `.pure` sources were excluded** from the oracle
  (24 368 total → 24 172 non-test). A handful of the 36 NO_SUCH_FUNCTION entries could in principle
  exist only in a test source; I spot-checked `getString` and `mapping::execute` by grep and they do
  not.
* **Only the two upstream repos were consulted.** Functions that live in other FINOS repos
  (`legend-shared`, XT plugins not vendored into legend-engine) would show as NO_SUCH_FUNCTION here;
  none of the 36 look like that (33 are `meta::legend::lite::*`).
* **No `mvn` was run** (brief prohibition). All evidence is from `jrun.sh`/`probe.sh` probes and
  direct source reads.

---

## APPENDIX A — full 131 argument-shape divergences

Raw generated listing (`L` = legend-lite, `R` = real; type params alpha-renamed to `$0,$1,…`,
multiplicity params to `%0,%1,…`; at most 3 real candidates shown per entry, nearest arity first).
Regenerate with `python3 show3.py` in `/tmp/…/scratchpad/oracle`.

```
* meta::pure::functions::math::abs @1116
   L ($0[1]):$0[1]
   R (Integer[1]):Integer[1]   [abs.pure:40]
   R (Float[1]):Float[1]   [abs.pure:47]
   R (Number[1]):Number[1]   [abs.pure:53]
   R ...(+1 more real overloads)
* meta::pure::tds::asc @1171
   L (ColSpec<$0>[1]):SortInfo<$0>[1]
   R (String[1]):SortInformation[1]   [tds.pure:702]
* meta::pure::functions::relation::asOfJoin @1175
   L (Relation<$0>[1],Relation<$1>[1],Function<{$0[1],$1[1]->Boolean[1]}>[1],join:Function<{$0[1],$1[1]->Boolean[1]}>[1],prefix:String[1]):Relation<$0+$1>[…
   R (Relation<$0>[1],Relation<$1>[1],Function<{$0[1],$1[1]->Boolean[1]}>[1]):Relation<$0+$1>[1]   [asofjoin.pure:18]
   R (Relation<$0>[1],Relation<$1>[1],Function<{$0[1],$1[1]->Boolean[1]}>[1],join:Function<{$0[1],$1[1]->Boolean[1]}>[1]):Relation<$0+$1>[1]   [asofjoin.pure:20]
* meta::pure::functions::math::olap::averageRank @1179
   L ():Number[1]
   R (Any[*]):Map<Any,Integer>[1]   [mathExtension.pure:56]
* meta::pure::functions::lang::compare @1216
   L (Any[1],Any[1]):Integer[1]
   R ($0[1],$0[1]):Integer[1]   [compare.pure:17]
* meta::pure::functions::math::corr @1223
   L (RowMapper<$0,$1>[*]):Number[0..1]
   R (RowMapper<Number,Number>[*]):Number[0..1]   [corr.pure:31]
   R (Number[*],Number[*]):Number[0..1]   [corr.pure:17]
* meta::pure::functions::collection::count @1227
   L ($0[*]):Integer[1]
   R (Any[*]):Integer[1]   [count.pure:17]
* meta::pure::functions::math::covarPopulation @1229
   L (RowMapper<$0,$1>[*]):Number[0..1]
   R (RowMapper<Number,Number>[*]):Number[0..1]   [covarPopulation.pure:27]
   R (Number[*],Number[*]):Number[0..1]   [covarPopulation.pure:17]
* meta::pure::functions::math::covarSample @1231
   L (RowMapper<$0,$1>[*]):Number[0..1]
   R (RowMapper<Number,Number>[*]):Number[0..1]   [covarSample.pure:27]
   R (Number[*],Number[*]):Number[0..1]   [covarSample.pure:17]
* meta::pure::tds::desc @1271
   L (ColSpec<$0>[1]):SortInfo<$0>[1]
   R (String[1]):SortInformation[1]   [tds.pure:695]
* meta::pure::functions::math::divide @1283
   L (Number[1],Number[1],Integer[1]):Decimal[1]
   R (Decimal[1],Decimal[1],Integer[1]):Decimal[1]   [divide.pure:66]
   R (Number[1],Number[1]):Float[1]   [divide.pure:44]
* meta::pure::functions::relation::extend @1309
   L ($0[*],FuncColSpec<{$0[1]->Any[0..1]}?,$1>[1]):$0[*]
   R (Relation<$0>[1],FuncColSpec<{$0[1]->Any[0..1]}?,$1>[1]):Relation<$0+$1>[1]   [extend.pure:20]
   R (Relation<$0>[1],FuncColSpecArray<{$0[1]->Any[*]}?,$1>[1]):Relation<$0+$1>[1]   [extend.pure:26]
   R (Relation<$0>[1],AggColSpec<{$0[1]->$1[0..1]}?,{$1[*]->$2[0..1]},$3>[1]):Relation<$0+$3>[1]   [extend.pure:32]
   R ...(+5 more real overloads)
* meta::pure::functions::relation::extend @1310
   L ($0[*],FuncColSpecArray<{$0[1]->Any[*]}?,$1>[1]):$0[*]
   R (Relation<$0>[1],FuncColSpec<{$0[1]->Any[0..1]}?,$1>[1]):Relation<$0+$1>[1]   [extend.pure:20]
   R (Relation<$0>[1],FuncColSpecArray<{$0[1]->Any[*]}?,$1>[1]):Relation<$0+$1>[1]   [extend.pure:26]
   R (Relation<$0>[1],AggColSpec<{$0[1]->$1[0..1]}?,{$1[*]->$2[0..1]},$3>[1]):Relation<$0+$3>[1]   [extend.pure:32]
   R ...(+5 more real overloads)
* meta::pure::tds::filter @1322
   L (Relation<$0>[1],Function<{$0[1]->Boolean[1]}>[1]):Relation<$0>[1]
   R (TabularDataSet[1],Function<{TDSRow[1]->Boolean[1]}>[1]):TabularDataSet[1]   [tds.pure:434]
* meta::pure::functions::collection::first @1345
   L ($0[*],Integer[1]):$0[*]
   R ($0[*]):$0[0..1]   [first.pure:18]
* meta::pure::functions::collection::fold @1348
   L ($0[*],Function<{$0[1],$1[%0]->$1[%0]}>[1],init:$1[%0]):$1[%0]
   R ($0[*],Function<{$0[1],$1[%0]->$1[%0]}>[1],accumulator:$1[%0]):$1[%0]   [fold.pure:52]
* meta::pure::mapping::from @1353
   L (Relation<$0>[1]):Relation<$0>[1]
   R (FunctionDefinition<{->$0[%0]}>[1],packageableRuntime:PackageableRuntime[1]):$0[%0]   [mappingExtension.pure:346]
   R (FunctionDefinition<{->$0[%0]}>[1],runtime:Runtime[1]):$0[%0]   [mappingExtension.pure:351]
   R (FunctionDefinition<{->$0[%0]}>[1],%0:Mapping[1],packageableRuntime:PackageableRuntime[1]):$0[%0]   [mappingExtension.pure:356]
   R ...(+13 more real overloads)
* meta::pure::mapping::from @1354
   L (Relation<$0>[1],Any[1]):Relation<$0>[1]
   R ($0[%0],SingleExecutionParameters[1]):$0[%0]   [mappingExtension.pure:1]
   R ($0[%0],ExecutionEnvironmentInstance[1]):$0[%0]   [mappingExtension.pure:6]
   R ($0[%0],PackageableRuntime[1]):$0[%0]   [mappingExtension.pure:306]
   R ...(+13 more real overloads)
* meta::pure::mapping::from @1358
   L ($0[%0],Any[1],Any[1]):$0[%0]
   R ($0[%0],Mapping[1],PackageableRuntime[1]):$0[%0]   [mappingExtension.pure:311]
   R (TabularDataSet[1],Mapping[1],PackageableRuntime[1]):TabularDataSet[1]   [mappingExtension.pure:316]
   R ($0[%0],Mapping[1],Runtime[1]):$0[%0]   [mappingExtension.pure:371]
   R ...(+13 more real overloads)
* meta::pure::mapping::withChainedMappings @1363
   L ($0[*],Mapping[*]):$0[*]
   R ($0[%0],Mapping[*]):$0[%0]   [mappingExtension.pure:391]
* meta::pure::graphFetch::execution::graphFetch @1388
   L ($0[*],ColSpec<$0>[1]):$0[*]
   R ($0[*],RootGraphFetchTree<$0>[1]):$0[*]   [graphFetch.pure:19]
   R ($0[*],RootGraphFetchTree<$0>[1],Integer[1]):$0[*]   [graphFetch.pure:25]
* meta::pure::graphFetch::execution::graphFetch @1389
   L ($0[*],ColSpecArray<$0>[1]):$0[*]
   R ($0[*],RootGraphFetchTree<$0>[1]):$0[*]   [graphFetch.pure:19]
   R ($0[*],RootGraphFetchTree<$0>[1],Integer[1]):$0[*]   [graphFetch.pure:25]
* meta::pure::tds::groupBy @1428
   L ($0[*],FuncColSpecArray<{$0[1]->Any[*]}?,$1>[1],aggs:AggColSpec<{$0[1]->$2[*]},{$2[*]->$3[0..1]},$4>[1]):Relation<$1+$4>[1]
   R (TabularDataSet[1],String[*],AggregateValue<$0,$1>[*]):TabularDataSet[1]   [tds.pure:629]
   R ($0[*],Function<{$0[1]->Any[*]}>[*],aggValues:AggregateValue<$0?,$1?,$2>[*],ids:String[*]):TabularDataSet[1]   [tds.pure:839]
* meta::pure::tds::groupBy @1429
   L ($0[*],FuncColSpecArray<{$0[1]->Any[*]}?,$1>[1],aggs:AggColSpecArray<{$0[1]->$2[*]},{$2[*]->$3[0..1]},$4>[1]):Relation<$1+$4>[1]
   R (TabularDataSet[1],String[*],AggregateValue<$0,$1>[*]):TabularDataSet[1]   [tds.pure:629]
   R ($0[*],Function<{$0[1]->Any[*]}>[*],aggValues:AggregateValue<$0?,$1?,$2>[*],ids:String[*]):TabularDataSet[1]   [tds.pure:839]
* meta::pure::functions::collection::groupBy @1430
   L ($0[*],Function<{$0[1]->Any[*]}>[*],aggs:Any[*],ids:String[*]):Relation<$0>[1]
   R ($0[*],Function<{$0[1]->$1[1]}>[1]):Map<$1,List<$0>>[1]   [groupBy.pure:41]
* meta::pure::functions::lang::if @1451
   L (Boolean[1],Function<{->$0[%0]}>[1],else:Function<{->$0[%0]}>[1]):$0[%0]
   R (Boolean[1],Function<{->$0[%0]}>[1],invalid:Function<{->$0[%0]}>[1]):$0[%0]   [if.pure:43]
   R (Pair<Function<{->Boolean[1]}>?,Function<{->$0[%0]}>>[*],last:Function<{->$0[%0]}>[1]):$0[%0]   [if.pure:65]
* meta::pure::functions::collection::isDistinct @1471
   L (Any[1],Any[1]):Boolean[1]
   R ($0[*],RootGraphFetchTree<$0>[1]):Boolean[1]   [collectionExtension.pure:37]
   R ($0[*]):Boolean[1]   [collectionExtension.pure:32]
* meta::pure::functions::collection::isEmpty @1472
   L ($0[*]):Boolean[1]
   R (Any[*]):Boolean[1]   [isEmpty.pure:17]
   R (Any[0..1]):Boolean[1]   [isEmpty.pure:31]
* meta::pure::functions::collection::isNotEmpty @1473
   L ($0[*]):Boolean[1]
   R (Any[*]):Boolean[1]   [isNotEmpty.pure:17]
   R (Any[0..1]):Boolean[1]   [isNotEmpty.pure:34]
* meta::pure::functions::relation::join @1495
   L (Relation<$0>[1],Relation<$1>[1],JoinKind[1],Function<{$0[1],$1[1]->Boolean[1]}>[1],prefix:String[1]):Relation<$0+$1>[1]
   R (Relation<$0>[1],Relation<$1>[1],JoinKind[1],Function<{$0[1],$1[1]->Boolean[1]}>[1]):Relation<$0+$1>[1]   [join.pure:26]
* meta::core::runtime::connectionByElement @1609
   L (Runtime[1],Any[1]):Connection[1]
   R (Runtime[1],Store[1]):Connection[1]   [runtimeExtension.pure:79]
* meta::pure::router::execute @1635
   L (Function<{->$0[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*]):Result<$0>[1]
   R (FunctionDefinition<{->$0[%0]}>[1],m:Mapping[1],runtime:Runtime[1],extensions:Extension[*]):Result<$0|%0>[1]   [router_entry.pure:20]
   R (FunctionDefinition<{->$0[%0]}>[1],m:Mapping[1],runtime:Runtime[1],exeCtx:ExecutionContext[1],extensions:Extension[*]):Result<$0|%0>[1]   [router_entry.pure:25]
   R (FunctionDefinition<{->$0[%0]}>[1],%0:Mapping[1],runtime:Runtime[1],extensions:Extension[*],debug:DebugContext[1]):Result<$0|%0>[1]   [router_entry.pure:50]
   R ...(+1 more real overloads)
* meta::pure::router::execute @1636
   L (Function<{->$0[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*],debug:Any[1]):Result<$0>[1]
   R (FunctionDefinition<{->$0[%0]}>[1],m:Mapping[1],runtime:Runtime[1],extensions:Extension[*]):Result<$0|%0>[1]   [router_entry.pure:20]
   R (FunctionDefinition<{->$0[%0]}>[1],m:Mapping[1],runtime:Runtime[1],exeCtx:ExecutionContext[1],extensions:Extension[*]):Result<$0|%0>[1]   [router_entry.pure:25]
   R (FunctionDefinition<{->$0[%0]}>[1],%0:Mapping[1],runtime:Runtime[1],extensions:Extension[*],debug:DebugContext[1]):Result<$0|%0>[1]   [router_entry.pure:50]
   R ...(+1 more real overloads)
* meta::pure::router::preeval::preval @1645
   L (Function<{->$0[*]}>[1],extensions:Any[*]):Function<{->$0[*]}>[1]
   R (FunctionDefinition<$0>[1],Extension[*]):FunctionDefinition<$0>[1]   [preeval.pure:53]
   R (FunctionDefinition<$0>[1],Extension[*],DebugContext[1]):FunctionDefinition<$0>[1]   [preeval.pure:58]
   R (FunctionDefinition<Any>[1],State[1],Extension[*]):PrevalWrapper<FunctionDefinition<Any>>[1]   [preeval.pure:79]
   R ...(+3 more real overloads)
* meta::pure::router::preeval::preval @1646
   L (Function<{->$0[*]}>[1],extensions:Any[*],debug:DebugContext[1]):Function<{->$0[*]}>[1]
   R (FunctionDefinition<$0>[1],Extension[*]):FunctionDefinition<$0>[1]   [preeval.pure:53]
   R (FunctionDefinition<$0>[1],Extension[*],DebugContext[1]):FunctionDefinition<$0>[1]   [preeval.pure:58]
   R (FunctionDefinition<Any>[1],State[1],Extension[*]):PrevalWrapper<FunctionDefinition<Any>>[1]   [preeval.pure:79]
   R ...(+3 more real overloads)
* meta::relational::milestoning::concatenateTemporalTdsQueries @1656
   L (Function<{->$0[*]}>[*]):Function<{->$0[*]}>[1]
   R (LambdaFunction<{->TabularDataSet[1]}>[*]):LambdaFunction<{->TabularDataSet[1]}>[1]{letconcat=^SimpleFunctionExpression(func=concatenate_TabularDataSe…   [milestoning.pure:753]
* meta::pure::executionPlan::featureFlag::withFeatureFlags @1660
   L ($0[*],Any[*]):$0[*]
   R ($0[*],Enum[*]):$0[*]   [executionPlanFeature.pure:29]
* meta::alloy::service::execution::setUpDataSQLsV2 @1671
   L (String[1],Any[1],Any[1]):String[*]
   R (String[1],Database[*],DbConfig[1]):String[*]   [toDDL.pure:198]
* meta::alloy::service::execution::setUpDataSQLs @1681
   L (List<String>[*],Any[*],Any[1]):String[*]
   R (String[1],Database[*],DatabaseType[1]):String[*]   [helperFunctions.pure:183]
   R (String[1],Database[*],DbConfig[1]):String[*]   [toDDL.pure:186]
   R (List<String>[*],Database[*],DbConfig[1]):String[*]   [toDDL.pure:209]
   R ...(+2 more real overloads)
* meta::alloy::service::execution::setUpDataSQLs @1682
   L (String[1],Any[*]):String[*]
   R (String[1],Database[*]):String[*]   [helperFunctions.pure:188]
   R (List<String>[*],Database[*]):String[*]   [helperFunctions.pure:193]
   R (String[1],Database[*],DatabaseType[1]):String[*]   [helperFunctions.pure:183]
   R ...(+2 more real overloads)
* meta::alloy::service::execution::setUpDataSQLs @1683
   L (String[1],Any[*],Any[1]):String[*]
   R (String[1],Database[*],DatabaseType[1]):String[*]   [helperFunctions.pure:183]
   R (String[1],Database[*],DbConfig[1]):String[*]   [toDDL.pure:186]
   R (List<String>[*],Database[*],DbConfig[1]):String[*]   [toDDL.pure:209]
   R ...(+2 more real overloads)
* meta::pure::executionPlan::executionPlan @1690
   L (Function<{->Any[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1691
   L (Function<{->Any[*]}>[1],mapping:Any[1],runtime:Any[1],exeCtx:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1694
   L (Function<{->Any[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*],debugContext:Any[1]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::toString::planToString @1695
   L (Any[1],Any[*]):String[1]
   R (ExecutionPlan[1],Extension[*]):String[1]   [executionPlan_print.pure:32]
   R (ExecutionPlan[1],Boolean[1],Extension[*]):String[1]   [executionPlan_print.pure:37]
* meta::pure::executionPlan::toString::planToStringWithoutFormatting @1697
   L (Any[1],Any[*]):String[1]
   R (ExecutionPlan[1],Extension[*]):String[1]   [executionPlan_print.pure:27]
* meta::pure::executionPlan::allNodes @1699
   L (ExecutionNode[1],Any[*]):ExecutionNode[*]
   R (ExecutionNode[1],Extension[*]):ExecutionNode[*]   [executionPlan_execution.pure:67]
* meta::pure::executionPlan::executionPlan @1704
   L (Function<{Any[1]->Any[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1705
   L (Function<{Any[1]->Any[*]}>[1],mapping:Any[1],runtime:Any[1],exeCtx:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1706
   L (Function<{Any[1],Any[1]->Any[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1707
   L (Function<{Any[1],Any[1]->Any[*]}>[1],mapping:Any[1],runtime:Any[1],exeCtx:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1708
   L (Function<{->Any[*]}>[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1709
   L (Function<{Any[1]->Any[*]}>[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1710
   L (Function<{Any[1],Any[1]->Any[*]}>[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::pure::executionPlan::executionPlan @1711
   L (Function<{->Any[*]}>[1],context:Any[1],extensions:Any[*]):ExecutionPlan[1]
   R (FunctionDefinition<Any>[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:25]
   R (FunctionDefinition<Any>[1],Extension[*],DebugContext[1]):ExecutionPlan[1]   [executionPlan_generation.pure:30]
   R (FunctionDefinition<Any>[1],ExecutionContext[1],Extension[*]):ExecutionPlan[1]   [executionPlan_generation.pure:35]
   R ...(+8 more real overloads)
* meta::relational::functions::sqlQueryToString::createDbConfig @1715
   L (Any[1]):Any[1]
   R (DatabaseType[1]):DbConfig[1]   [dbExtension.pure:250]
   R (DatabaseConnection[1]):DbConfig[1]   [dbExtension.pure:260]
   R (DatabaseType[1],String[0..1]):DbConfig[1]   [dbExtension.pure:255]
   R ...(+1 more real overloads)
* meta::relational::functions::sqlQueryToString::createDbConfig @1716
   L (Any[1],String[0..1]):Any[1]
   R (DatabaseType[1],String[0..1]):DbConfig[1]   [dbExtension.pure:255]
   R (DatabaseType[1]):DbConfig[1]   [dbExtension.pure:250]
   R (DatabaseConnection[1]):DbConfig[1]   [dbExtension.pure:260]
   R ...(+1 more real overloads)
* meta::relational::functions::sqlstring::toSQLString @1723
   L (Function<{->Any[*]}>[1],mapping:Any[1],databaseType:Any[1],extensions:Any[*]):String[1]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],databaseType:DatabaseType[1],extensions:Extension[*]):String[1]   [toSQLString.pure:63]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],databaseType:DatabaseType[1],quoteIdentifier:Boolean[0..1],extensions:Extension[*]):String[1]   [toSQLString.pure:68]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],databaseType:DatabaseType[1],extensions:Extension[*],debug:DebugContext[1]):String[1]   [toSQLString.pure:73]
   R ...(+3 more real overloads)
* meta::relational::functions::sqlstring::toSQLStringPretty @1728
   L (Function<{->Any[*]}>[1],mapping:Any[1],databaseTypeOrRuntime:Any[1],extensions:Any[*]):String[1]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],databaseType:DatabaseType[1],extensions:Extension[*]):String[1]   [toSQLString.pure:35]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],runtime:Runtime[1],extensions:Extension[*]):String[1]   [toSQLString.pure:40]
   R (FunctionDefinition<{->Any[*]}>[1],mapping:Mapping[1],databaseType:DatabaseType[1],dbTimeZone:String[0..1],extensions:Extension[*]):String[1]   [toSQLString.pure:58]
* meta::pure::functions::relation::limit @1828
   L (Relation<$0>[1],Integer[0..1]):Relation<$0>[1]
   R (Relation<$0>[1],Integer[1]):Relation<$0>[1]   [limit.pure:18]
* meta::pure::functions::math::maxBy @1876
   L (RowMapper<$0,$1>[*]):$0[0..1]
   R (RowMapper<$0,Number>[*]):$0[0..1]   [maxBy.pure:25]
   R ($0[*],Number[*]):$0[0..1]   [maxBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [maxBy.pure:35]
   R ...(+1 more real overloads)
* meta::pure::functions::math::maxBy @1877
   L ($0[*],Function<{$0[1]->Any[1]}>[1]):$0[0..1]
   R ($0[*],Number[*]):$0[0..1]   [maxBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [maxBy.pure:35]
   R (RowMapper<$0,Number>[*]):$0[0..1]   [maxBy.pure:25]
   R ...(+1 more real overloads)
* meta::pure::functions::math::maxBy @1878
   L ($0[*],Function<{$0[1]->Any[1]}>[1],count:Integer[1]):$0[*]
   R ($0[*],Number[*]):$0[0..1]   [maxBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [maxBy.pure:35]
   R (RowMapper<$0,Number>[*]):$0[0..1]   [maxBy.pure:25]
   R ...(+1 more real overloads)
* meta::pure::functions::math::maxBy @1879
   L ($0[*],$0[*]):$0[0..1]
   R ($0[*],Number[*]):$0[0..1]   [maxBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [maxBy.pure:35]
   R (RowMapper<$0,Number>[*]):$0[0..1]   [maxBy.pure:25]
   R ...(+1 more real overloads)
* meta::pure::functions::math::maxBy @1880
   L ($0[*],$0[*],Integer[1]):$0[*]
   R ($0[*],Number[*],Integer[1]):$0[*]   [maxBy.pure:30]
   R ($0[*],Number[*]):$0[0..1]   [maxBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [maxBy.pure:35]
   R ...(+1 more real overloads)
* meta::pure::functions::math::minus @1895
   L (Decimal[1],Decimal[1]):Decimal[1]
   R (Integer[*]):Integer[1]   [minus.pure:45]
   R (Float[*]):Float[1]   [minus.pure:59]
   R (Decimal[*]):Decimal[1]   [minus.pure:67]
   R ...(+2 more real overloads)
* meta::pure::functions::math::minus @1896
   L (Float[1],Float[1]):Float[1]
   R (Integer[*]):Integer[1]   [minus.pure:45]
   R (Float[*]):Float[1]   [minus.pure:59]
   R (Decimal[*]):Decimal[1]   [minus.pure:67]
   R ...(+2 more real overloads)
* meta::pure::functions::math::minus @1897
   L (Integer[1],Integer[1]):Integer[1]
   R (Integer[*]):Integer[1]   [minus.pure:45]
   R (Float[*]):Float[1]   [minus.pure:59]
   R (Decimal[*]):Decimal[1]   [minus.pure:67]
   R ...(+2 more real overloads)
* meta::pure::functions::math::minus @1898
   L (Number[1],Number[1]):Number[1]
   R (Integer[*]):Integer[1]   [minus.pure:45]
   R (Float[*]):Float[1]   [minus.pure:59]
   R (Decimal[*]):Decimal[1]   [minus.pure:67]
   R ...(+2 more real overloads)
* meta::pure::functions::math::minus @1899
   L ($0[*]):$0[1]
   R (Integer[*]):Integer[1]   [minus.pure:45]
   R (Float[*]):Float[1]   [minus.pure:59]
   R (Decimal[*]):Decimal[1]   [minus.pure:67]
   R ...(+2 more real overloads)
* meta::pure::functions::math::minBy @1901
   L (RowMapper<$0,$1>[*]):$0[0..1]
   R (RowMapper<$0,Number>[*]):$0[0..1]   [minBy.pure:35]
   R ($0[*],Number[*]):$0[0..1]   [minBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [minBy.pure:40]
   R ...(+1 more real overloads)
* meta::pure::functions::math::minBy @1902
   L ($0[*],Function<{$0[1]->Any[1]}>[1]):$0[0..1]
   R ($0[*],Number[*]):$0[0..1]   [minBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [minBy.pure:40]
   R ($0[*],Number[*],Integer[1]):$0[*]   [minBy.pure:29]
   R ...(+1 more real overloads)
* meta::pure::functions::math::minBy @1903
   L ($0[*],Function<{$0[1]->Any[1]}>[1],count:Integer[1]):$0[*]
   R ($0[*],Number[*]):$0[0..1]   [minBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [minBy.pure:40]
   R ($0[*],Number[*],Integer[1]):$0[*]   [minBy.pure:29]
   R ...(+1 more real overloads)
* meta::pure::functions::math::minBy @1904
   L ($0[*],$0[*]):$0[0..1]
   R ($0[*],Number[*]):$0[0..1]   [minBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [minBy.pure:40]
   R ($0[*],Number[*],Integer[1]):$0[*]   [minBy.pure:29]
   R ...(+1 more real overloads)
* meta::pure::functions::math::minBy @1905
   L ($0[*],$0[*],Integer[1]):$0[*]
   R ($0[*],Number[*],Integer[1]):$0[*]   [minBy.pure:29]
   R ($0[*],Number[*]):$0[0..1]   [minBy.pure:19]
   R (RowMapper<$0,Number>[*],Integer[1]):$0[*]   [minBy.pure:40]
   R ...(+1 more real overloads)
* meta::pure::metamodel::relation::newTDSRelationAccessor @1925
   L (Relation<$0>[1]):Relation<$0>[1]
   R (TDS<$0>[1]):TDSRelationAccessor<$0>[1]   [tds.pure:28]
* meta::pure::functions::collection::objectReferenceIn @1942
   L (Any[1],Any[*]):Boolean[1]
   R (Any[1],String[*]):Boolean[1]   [collectionExtension.pure:49]
* meta::pure::functions::relation::over @1955
   L (ColSpec<$0>[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:31]
   R (ColSpecArray<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:54]
   R (SortInfo<(?:?)⊆$0>[*]):_Window<$0>[1]   [over.pure:77]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1956
   L (ColSpec<$0>[1],SortInfo<$0>[*]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1957
   L (ColSpec<$0>[1],SortInfo<$0>[1],_Range[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1958
   L (ColSpec<$0>[1],SortInfo<$0>[*],Rows[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1959
   L (ColSpecArray<$0>[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:31]
   R (ColSpecArray<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:54]
   R (SortInfo<(?:?)⊆$0>[*]):_Window<$0>[1]   [over.pure:77]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1960
   L (ColSpecArray<$0>[1],SortInfo<$0>[*]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1961
   L (SortInfo<$0>[*]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:31]
   R (ColSpecArray<(?:?)⊆$0>[1]):_Window<$0>[1]   [over.pure:54]
   R (SortInfo<(?:?)⊆$0>[*]):_Window<$0>[1]   [over.pure:77]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1962
   L (SortInfo<$0>[1],_Range[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1963
   L (SortInfo<$0>[1],_RangeInterval[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @1964
   L (ColSpec<$0>[1],SortInfo<$0>[1],_RangeInterval[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::collection::max @1969
   L ($0[*],Function<{$0[1],$0[1]->Integer[1]}>[1]):$0[0..1]
   R ($0[1..*],Function<{$0[1],$0[1]->Integer[1]}>[1]):$0[1]   [max.pure:37]
   R ($0[1..*]):$0[1]   [max.pure:17]
   R ($0[*]):$0[0..1]   [max.pure:27]
* meta::pure::functions::collection::min @1971
   L ($0[*],Function<{$0[1],$0[1]->Integer[1]}>[1]):$0[0..1]
   R ($0[1..*],Function<{$0[1],$0[1]->Integer[1]}>[1]):$0[1]   [min.pure:37]
   R ($0[1..*]):$0[1]   [min.pure:17]
   R ($0[*]):$0[0..1]   [min.pure:27]
* meta::pure::functions::math::percentile @1987
   L (Number[*],Number[1]):Number[1]
   R (Number[*],Float[1]):Number[0..1]   [percentile.pure:17]
   R (Number[*],Float[1],Boolean[1],Boolean[1]):Number[0..1]   [percentile.pure:25]
* meta::pure::functions::math::percentile @1988
   L (Number[*],Number[1],Boolean[1],Boolean[1]):Number[0..1]
   R (Number[*],Float[1],Boolean[1],Boolean[1]):Number[0..1]   [percentile.pure:25]
   R (Number[*],Float[1]):Number[0..1]   [percentile.pure:17]
* meta::pure::functions::math::plus @1996
   L (Decimal[1],Decimal[1]):Decimal[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::math::plus @1997
   L (Float[1],Float[1]):Float[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::math::plus @1998
   L (Integer[1],Integer[1]):Integer[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::math::plus @1999
   L (Number[1],Number[1]):Number[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::math::plus @2000
   L (String[1],String[1]):String[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::math::plus @2001
   L ($0[*]):$0[1]
   R (Integer[*]):Integer[1]   [plus.pure:44]
   R (Float[*]):Float[1]   [plus.pure:58]
   R (Decimal[*]):Decimal[1]   [plus.pure:66]
   R ...(+2 more real overloads)
* meta::pure::functions::relation::assertTdsEquivalent @2076
   L (Relation<$0>[1],Relation<$1>[1],Number[1]):Boolean[1]
   R (Relation<Any>[1],Relation<Any>[1],Number[1]):Boolean[1]   [tdsEquivalent.pure:20]
   R (Relation<Any>[1],Relation<Any>[1],Number[1],Number[1]):Boolean[1]   [tdsEquivalent.pure:25]
* meta::pure::functions::relation::assertTdsEquivalent @2077
   L (Relation<$0>[1],Relation<$1>[1],Number[1],Number[1]):Boolean[1]
   R (Relation<Any>[1],Relation<Any>[1],Number[1],Number[1]):Boolean[1]   [tdsEquivalent.pure:25]
   R (Relation<Any>[1],Relation<Any>[1],Number[1]):Boolean[1]   [tdsEquivalent.pure:20]
* meta::pure::tds::tdsContains @2085
   L ($0[1],Function<{$0[1]->Any[0..1]}>[*],tds:Relation<$1>[1]):Boolean[1]
   R ($0[1],Function<{$0[1]->Any[0..1]}>[*],tds:TabularDataSet[1]):Boolean[1]   [tds.pure:824]
   R ($0[1],Function<{$0[1]->Any[0..1]}>[*],ids:String[*],tds:TabularDataSet[1],crossOperation:Function<{TDSRow[1],TDSRow[1]->Boolean[1]}>[1]):Boolean[1]   [tds.pure:831]
* meta::pure::tds::tdsContains @2087
   L ($0[1],Function<{$0[1]->Any[0..1]}>[*],ids:String[*],tds:Relation<$1>[1],crossOperation:Function<{TDSRow[1],TDSRow[1]->Boolean[1]}>[1]):Boolean[1]
   R ($0[1],Function<{$0[1]->Any[0..1]}>[*],tds:TabularDataSet[1]):Boolean[1]   [tds.pure:824]
   R ($0[1],Function<{$0[1]->Any[0..1]}>[*],ids:String[*],tds:TabularDataSet[1],crossOperation:Function<{TDSRow[1],TDSRow[1]->Boolean[1]}>[1]):Boolean[1]   [tds.pure:831]
* meta::pure::functions::collection::removeDuplicatesBy @2098
   L ($0[*],Function<{$0[1]->$1[1]}>[1]):$0[*]
   R ($0[*],Function<{$0[1]->Any[1]}>[1]):$0[*]   [removeDuplicatesBy.pure:42]
* meta::pure::functions::relation::rename @2111
   L (Relation<$0>[1],ColSpecArray<$1⊆$0>[1],ColSpecArray<$2>[1]):Relation<$0-$1+$2>[1]
   R (Relation<$0>[1],ColSpec<$1=(?:$2)⊆$0>[1],ColSpec<$3=(?:$2)>[1]):Relation<$0-$1+$3>[1]   [rename.pure:18]
* meta::pure::functions::relation::over @2124
   L (ColSpec<$0>[1],Rows[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @2125
   L (ColSpecArray<$0>[1],Rows[1]):_Window<$0>[1]
   R (ColSpec<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:42]
   R (ColSpecArray<(?:?)⊆$0>[1],Rows[1]):_Window<$0>[1]   [over.pure:65]
   R (SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:85]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @2126
   L (ColSpecArray<$0>[1],SortInfo<$0>[*],Rows[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @2127
   L (ColSpecArray<$0>[1],SortInfo<$0>[1],_Range[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::over @2128
   L (ColSpecArray<$0>[1],SortInfo<$0>[1],_RangeInterval[1]):_Window<$0>[1]
   R (String[*],SortInfo<(?:?)⊆$0>[*],Frame[0..1]):_Window<$0>[1]   [over.pure:18]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[*],Rows[1]):_Window<$0>[1]   [over.pure:109]
   R (ColSpec<(?:?)⊆$0>[1],SortInfo<(?:?)⊆$0>[1],_Range[1]):_Window<$0>[1]   [over.pure:117]
   R ...(+13 more real overloads)
* meta::pure::functions::relation::variant::flatten @2131
   L ($0[*],ColSpec<Any>[1]):Relation<Any>[1]
   R ($0[*],ColSpec<$1=(?:$0)>[1]):Relation<$1>[1]   [flatten.pure:23]
* meta::pure::graphFetch::execution::serialize @2146
   L ($0[*],RootGraphFetchTree<$0>[1],Any[1]):String[1]
   R ($0[*],RootGraphFetchTree<$0>[1],AlloySerializationConfig[1]):String[1]   [graphFetch.pure:176]
   R (Checked<$0>[*],RootGraphFetchTree<$0>[1],AlloySerializationConfig[1]):String[1]   [graphFetch.pure:183]
   R ($0[*],RootGraphFetchTree<$0>[1]):String[1]   [graphFetch.pure:69]
   R ...(+1 more real overloads)
* meta::pure::functions::collection::size @2151
   L ($0[*]):Integer[1]
   R (Any[*]):Integer[1]   [size.pure:17]
* meta::pure::tds::sort @2157
   L (Relation<$0>[1],String[1],SortDirection[1]):Relation<$0>[1]
   R (TabularDataSet[1],String[1],SortDirection[1]):TabularDataSet[1]   [tds.pure:369]
   R (TabularDataSet[1],SortInformation[*]):TabularDataSet[1]   [tds.pure:362]
   R (TabularDataSet[1],String[*]):TabularDataSet[1]   [tds.pure:376]
* meta::pure::functions::relation::sort @2158
   L (Relation<$0>[1],String[*]):Relation<$0>[1]
   R (Relation<$1>[1],SortInfo<$0⊆$1>[*]):Relation<$1>[1]   [sort.pure:18]
* meta::pure::functions::math::stdDev @2178
   L (Number[*]):Number[1]
   R (Number[1..*],Boolean[1]):Number[1]   [stdDev.pure:17]
* meta::pure::functions::math::stdDev @2180
   L (Relation<$0>[1],_Window<$0>[1],$0[1]):$0[0..1]
   R (Number[1..*],Boolean[1]):Number[1]   [stdDev.pure:17]
* meta::relational::functions::database::tableReference @2190
   L (String[1],String[1]):Relation<Any>[1]
   R (Database[1],String[1],String[1]):Table[1]   [tableReference.pure:17]
* meta::pure::tds::tableToTDS @2194
   L (Relation<Any>[1]):Relation<Any>[1]
   R (Table[1]):TableTDS[1]   [tableToTDS.pure:22]
* meta::relational::functions::database::tableReference @2197
   L (String[1],String[1],String[1]):Relation<Any>[1]
   R (Database[1],String[1],String[1]):Table[1]   [tableReference.pure:17]
* meta::pure::functions::collection::take @2199
   L (Relation<$0>[1],Integer[1]):Relation<$0>[1]
   R ($0[*],Integer[1]):$0[*]   [take.pure:40]
* meta::pure::functions::math::times @2204
   L (Decimal[1],Decimal[1]):Decimal[1]
   R (Integer[*]):Integer[1]   [times.pure:44]
   R (Float[*]):Float[1]   [times.pure:58]
   R (Decimal[*]):Decimal[1]   [times.pure:66]
   R ...(+1 more real overloads)
* meta::pure::functions::math::times @2205
   L (Float[1],Float[1]):Float[1]
   R (Integer[*]):Integer[1]   [times.pure:44]
   R (Float[*]):Float[1]   [times.pure:58]
   R (Decimal[*]):Decimal[1]   [times.pure:66]
   R ...(+1 more real overloads)
* meta::pure::functions::math::times @2206
   L (Integer[1],Integer[1]):Integer[1]
   R (Integer[*]):Integer[1]   [times.pure:44]
   R (Float[*]):Float[1]   [times.pure:58]
   R (Decimal[*]):Decimal[1]   [times.pure:66]
   R ...(+1 more real overloads)
* meta::pure::functions::math::times @2207
   L (Number[1],Number[1]):Number[1]
   R (Integer[*]):Integer[1]   [times.pure:44]
   R (Float[*]):Float[1]   [times.pure:58]
   R (Decimal[*]):Decimal[1]   [times.pure:66]
   R ...(+1 more real overloads)
* meta::pure::functions::math::times @2208
   L ($0[*]):$0[1]
   R (Integer[*]):Integer[1]   [times.pure:44]
   R (Float[*]):Float[1]   [times.pure:58]
   R (Decimal[*]):Decimal[1]   [times.pure:66]
   R ...(+1 more real overloads)
* meta::pure::functions::variant::convert::toMany @2221
   L ($0[0..1],$1[0..1]):$1[*]
   R (Variant[0..1],$0[0..1]):$0[*]   [toMany.pure:29]
   R (Variant[0..1],$0[0..1],String[1],Pair<String,Class<Any>>[*]):$0[*]   [toMany.pure:21]
* meta::pure::functions::variant::convert::to @2231
   L ($0[0..1],$1[0..1]):$1[0..1]
   R (Variant[0..1],$0[0..1]):$0[0..1]   [to.pure:30]
   R (Variant[0..1],$0[0..1],String[1],Pair<String,Class<Any>>[*]):$0[0..1]   [to.pure:20]
* meta::pure::functions::math::variance @2239
   L (Number[*]):Number[1]
   R (Number[*],Boolean[1]):Number[1]   [variance.pure:17]
* meta::pure::functions::math::variance @2243
   L (Relation<$0>[1],_Window<$0>[1],$0[1]):$0[0..1]
   R (Number[*],Boolean[1]):Number[1]   [variance.pure:17]
* meta::pure::functions::math::wavg @2246
   L (RowMapper<$0,$1>[*]):Float[1]
   R (RowMapper<Number,Number>[*]):Float[1]   [wavg.pure:36]
   R (Number[*],Number[*]):Float[1]   [wavg.pure:18]
* meta::pure::functions::relation::write @2254
   L (Relation<$0>[1]):Integer[1]
   R (Relation<$0>[1],RelationElementAccessor<$0>[1]):Integer[1]   [write.pure:20]
* meta::pure::functions::relation::write @2255
   L (Relation<$0>[1],Any[1]):Integer[1]
   R (Relation<$0>[1],RelationElementAccessor<$0>[1]):Integer[1]   [write.pure:20]
```

## APPENDIX B — full 36 NO_SUCH_FUNCTION entries

```
meta::legend::lite::adjustTemporal(Date[1],Integer[1],DurationUnit[1]):Date[1]   @Pure.java:1160
meta::legend::lite::avg(Number[*]):Float[1]   @Pure.java:1193
meta::legend::lite::castAsDeclared(Any[0..1],$0[1]):$0[0..1]   @Pure.java:1548
meta::legend::lite::convertDateFormat(String[0..1],String[1]):StrictDate[0..1]   @Pure.java:1278
meta::legend::lite::convertDateTimeFormat(String[0..1],String[1]):DateTime[0..1]   @Pure.java:1279
meta::legend::lite::convertTimeZoneFormat(DateTime[0..1],String[1],String[1]):String[0..1]   @Pure.java:1275
meta::legend::lite::divideRound(Number[1],Number[1],Integer[1]):Float[1]   @Pure.java:1281
meta::legend::lite::greaterThan(Any[0..1],Any[0..1]):Boolean[1]   @Pure.java:1936
meta::legend::lite::greaterThanEqual(Any[0..1],Any[0..1]):Boolean[1]   @Pure.java:1937
meta::legend::lite::hash(String[1]):String[1]   @Pure.java:1437
meta::legend::lite::isNumeric(String[0..1]):Boolean[0..1]   @Pure.java:1274
meta::legend::lite::join(Relation<$0>[1],FuncColSpec<{->Relation<$1>[1]}?,$2>[1],cond:Function<{$0[1],$1[1]->Boolean[1]}>[1]):Relation<$0+$2>[1]   @Pure.java:1494
meta::legend::lite::legacyAssocPredicate($0[1],$1[1],Relation<$2>[1],Relation<$3>[1],Function<{$2[1],$3[1]->Boolean[1]}>[1]):Boolean[1]   @Pure.java:1776
meta::legend::lite::legacyAssocPredicate($0[1],$1[1],String[1],String[1],Function<{$0[1],$1[1]->Boolean[1]}>[1]):Boolean[1]   @Pure.java:1781
meta::legend::lite::legacyLocalProperty(Any[1],String[1]):Any[1]   @Pure.java:1788
meta::legend::lite::legacyNavigate(Relation<$0>[1],FuncColSpec<{->$1[*]}?,$3>[1],tgtRows:Relation<$2>[1],cond:Function<{$0[1],$2[1]->Boolean[1]}>[1]):Relation<$0+$3>[1]   @Pure.java:1523
meta::legend::lite::legacyNavigate(Relation<$0>[1],FuncColSpec<{->$1[*]}?,$3>[1],tgtRows:Relation<$2>[1],cond:Function<{$0[1],$2[1]->Boolean[1]}>[1],pairedCond:Function<{$0[1],$2[1]->Boolean[1]}>[1]):Relation<$0+$3>[1]   @Pure.java:1529
meta::legend::lite::lessThan(Any[0..1],Any[0..1]):Boolean[1]   @Pure.java:1934
meta::legend::lite::lessThanEqual(Any[0..1],Any[0..1]):Boolean[1]   @Pure.java:1935
meta::legend::lite::navigate(Relation<$0>[1],FuncColSpec<{->$1[*]}?,$2>[1],pred:Function<{$0[1],$1[1]->Boolean[1]}>[1]):Relation<$0+$2>[1]   @Pure.java:1520
meta::legend::lite::navigate($0[*],FuncColSpec<{->$1[*]}?,$2>[1],pred:Function<{$0[1],$1[1]->Boolean[1]}>[1]):$0[*]   @Pure.java:1521
meta::legend::lite::navigate($0[*],Function<{$0[1]->Boolean[1]}>[1]):$0[*]   @Pure.java:1522
meta::legend::lite::notEqualAnsi(Any[1],Any[1]):Boolean[1]   @Pure.java:1926
meta::legend::lite::otherwise($0[1],$0[0..1]):$0[1]   @Pure.java:1954
meta::legend::lite::parseDateFormat(String[0..1],String[1]):DateTime[0..1]   @Pure.java:1280
meta::legend::lite::sourceUrl(String[1]):Relation<Any>[1]   @Pure.java:2162
meta::legend::lite::sub(Decimal[1],Decimal[1]):Decimal[1]   @Pure.java:2183
meta::legend::lite::sub(Float[1],Float[1]):Float[1]   @Pure.java:2184
meta::legend::lite::sub(Integer[1],Integer[1]):Integer[1]   @Pure.java:2185
meta::legend::lite::sub(Number[1],Number[1]):Number[1]   @Pure.java:2186
meta::legend::lite::tds(String[1],String[1]):Relation<Any>[1]   @Pure.java:2203
meta::legend::lite::trustOne($0[*]):$0[1]   @Pure.java:1164
meta::legend::lite::typeAsDeclared(Any[0..1],$0[1]):$0[0..1]   @Pure.java:1541
meta::pure::mapping::execute(Function<{->$0[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*]):Result<$0>[1]   @Pure.java:1628
meta::pure::mapping::execute(Function<{->$0[*]}>[1],mapping:Any[1],runtime:Any[1],extensions:Any[*],debug:Any[1]):Result<$0>[1]   @Pure.java:1629
meta::pure::tds::getString(TDSRow[1],String[1]):String[1]   @Pure.java:2079
```

## APPENDIX C — full 16 RET_DIFF entries

```
* add(T[*],Integer[1],T[1])  Pure.java:1118
    LITE ret T[*]   REAL ret T[1..*]   src legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/collection/transformation/add.pure:42 native=True
* add(T[*],T[1])  Pure.java:1119
    LITE ret T[*]   REAL ret T[1..*]   src legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/collection/transformation/add.pure:56 native=True
* corr(Number[*],Number[*])  Pure.java:1222
    LITE ret Number[1]   REAL ret Number[0..1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/math/aggregator/corr.pure:17 native=False
* covarPopulation(Number[*],Number[*])  Pure.java:1228
    LITE ret Number[1]   REAL ret Number[0..1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/math/aggregator/covarPopulation.pure:17 native=False
* covarSample(Number[*],Number[*])  Pure.java:1230
    LITE ret Number[1]   REAL ret Number[0..1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/math/aggregator/covarSample.pure:17 native=False
* datePart(Date[1])  Pure.java:1245
    LITE ret StrictDate[1]   REAL ret Date[1]   src legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/date/extract/datePart.pure:43 native=True
* dayOfWeekNumber(Date[1],DayOfWeek[1])  Pure.java:1256
    LITE ret Integer[1]   REAL ret Integer[1][firstDayMondayOrSundayOnly:$firstDay->in([DayOfWeek.Monday,DayOfWeek.Sunday])]?   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/date/extract/dayOfWeekNumber.pure:17 native=False
* fromEpochValue(Integer[1])  Pure.java:1351
    LITE ret Date[1]   REAL ret DateTime[1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/corefunctions/dateExtension.pure:543 native=False
* fromEpochValue(Integer[1],DurationUnit[1])  Pure.java:1352
    LITE ret Date[1]   REAL ret DateTime[1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/corefunctions/dateExtension.pure:548 native=False
* graphFetchChecked(T[*],RootGraphFetchTree<T>[1])  Pure.java:1394
    LITE ret Checked[*]   REAL ret Checked<T>[*]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/graphFetch/graphFetch.pure:32 native=False
* graphFetchChecked(T[*],RootGraphFetchTree<T>[1],Integer[1])  Pure.java:1395
    LITE ret Checked[*]   REAL ret Checked<T>[*]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/graphFetch/graphFetch.pure:38 native=False
* relationalExtensions()  Pure.java:1666
    LITE ret Any[*]   REAL ret Extension[*]   src legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/extensions/extension.pure:62 native=False
* columns(Relation<T>[1])  Pure.java:2067
    LITE ret Column<Nil,Any>[*]   REAL ret Column<Nil,Any|*>[*]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-relation/legend-engine-pure-functions-relation-pure/src/main/resources/core_functions_relation/relation/functions/columns.pure:18 native=True
* project(K[*],Function<{K[1]->Any[*]}>[*],ids:String[*])  Pure.java:2089
    LITE ret Relation<K>[1]   REAL ret TabularDataSet[1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:347 native=False
* sqlNull()  Pure.java:2166
    LITE ret Nil[0]   REAL ret SQLNull[1]   src legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/sqlQueryToString/dbExtension.pure:1025 native=False
* wavgRowMapper(Number[0..1],Number[0..1])  Pure.java:2252
    LITE ret RowMapper<Number,Number>[1]   REAL ret WavgRowMapper[1]   src legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-standard/legend-engine-pure-functions-standard-pure/src/main/resources/core_functions_standard/math/aggregator/mathUtility.pure:19 native=False
```
