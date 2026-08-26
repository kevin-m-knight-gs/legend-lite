# A22 — TYPED HIR (`compiler/spec/typed/`, 77 files, 70 sealed variants)

Scope: every file in `core/src/main/java/com/legend/compiler/spec/typed/` read in full
(77 files: 70 `TypedSpec` variants + `TypedSpec.java` + 6 helper types `FoldStrategy`,
`TypedAggCol`, `TypedFuncCol`, `TypedGraphTree`, `VarUse`, `WindowFrame`). No sampling.
Everything below is either an exact `file:LINE` citation with quoted code, or a probe I ran
with its pasted output.

Probes used (all in `/tmp/a22/`, run with `/home/user/probe/jrun.sh`):
`Sweep.java` (batch compile + variant census + plan), `Coh.java` (structural coherence rules,
112 queries), `Meta.java` (reflection over the sealed hierarchy), `Malformed.java`
(hand-built malformed nodes pushed through I/J/K), `Lit.java`, `Exec.java`, `Written.java`.

---

## 0. SEALING / VARIANT COUNT (task item 1)

`TypedSpec.java:17-85` is `public sealed interface TypedSpec permits …` with **70** names.

Cross-check (mechanical, `comm` over the sorted permits list vs the sorted set of files
containing `implements TypedSpec`):

```
=== permits count: 70
=== files implementing TypedSpec: 70
=== permits with no file:   (empty)
=== implementor not permitted:   (empty)
```

Reflection agrees: `TypedSpec.class.getPermittedSubclasses().length == 70` (`Meta.java` output
line 1: `permits count = 70`).

The 7 non-variant files in the package are `FoldStrategy` (its own sealed interface),
`TypedAggCol`, `TypedFuncCol`, `TypedGraphTree`, `VarUse`, `WindowFrame`, `TypedSpec` itself.
**No discrepancy. This item is clean.**

---

## FINDINGS

### [UNSOUND] `TypedCDecimal.info()` is computed from SCALE ONLY — a literal whose precision exceeds 38 is stamped `Decimal(38,s)` it cannot hold, and comes back as a `Double`

**Evidence** — `Typer.java:3178-3184` (the whole rule):

```java
private static Type decimalType(BigDecimal value) {
    int scale = Math.max(0, value.scale());
    if (scale > Type.PrecisionDecimal.MAX_PRECISION) {
        throw new TypeInferenceException("decimal literal '" + value.toPlainString()
                + "' needs scale " + scale + ", exceeding the maximum of " + …);
    }
    return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
}
```

`value.precision()` is never consulted. `Type.PrecisionDecimal`'s own compact constructor
(`Type.java:159-168`) only checks `0 <= scale <= precision`, so `(38, 5)` is accepted for a
45-digit value. `TypedCDecimal` has **no compact constructor at all**
(`TypedCDecimal.java:8`), so nothing downstream re-checks.

**Repro**
```
/home/user/probe/jrun.sh /tmp/a22/Exec.java '|1234567890123456789012345678901234567890.12345d'
```
**Actual output**
```
### |1234567890123456789012345678901234567890.12345d
  SQL: SELECT 1234567890123456789012345678901234567890.12345 AS value
  rootType: ExprType[type=PrecisionDecimal[precision=38, scale=5], multiplicity=Bounded[lower=1, upper=1]]
  returnType=PrecisionDecimal[precision=38, scale=5]
  col value : PrecisionDecimal[precision=38, scale=5]
  row Double(1.2345678901234568E39) |
```
Node-level proof (`/tmp/a22/Lit.java`):
```
### |1234567890123456789012345678901234567890.12345d
  node=TypedCDecimal
  info=ExprType[type=PrecisionDecimal[precision=38, scale=5], …]
  value(java.math.BigDecimal) = 1234567890123456789012345678901234567890.12345  precision=45 scale=5
```

**Why it matters** — the compiler asserts `Decimal(38,5)`; the value in the node needs 45
digits, and the runtime value that comes back is a **`Double`**, not a `BigDecimal`, with
~29 digits of the mantissa gone. A well-formed decimal (`|1.5d`, `|12345678901234567890123456789012345678d`)
correctly returns `BigDecimal`, so the `Double` is specifically the overflow path.
This is the textbook "compiler assigns a static type the runtime value violates".

---

### [UNSOUND] Silent HALF_EVEN truncation of every over-scale decimal literal — the "explicit `d`-suffix keeps the loud reject" guard is DEAD CODE

**Evidence** — `Typer.java:156-174`:

```java
case CDecimal lit -> {
    BigDecimal dv = lit.value();
    // … An EXPLICIT D-suffixed decimal keeps the loud reject —
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

The guard depends on `CDecimal.written()` ending in `D`. But `SpecParser.parseDecimal`
(`SpecParser.java:844-851`) **strips the suffix before storing `written`**:

```java
char last = text.charAt(text.length() - 1);
if (last == 'd' || last == 'D') text = text.substring(0, text.length() - 1);
return new CDecimal(new BigDecimal(text), text, spanOf(litTok, litTok));
```

Those are the only two `new CDecimal(...)` sites in the compiler
(`SpecParser.java:833` and `:851`; `grep -rn "new CDecimal("` over `core/src/main/java`),
and neither can produce a `written` ending in `D`.

**Repro**
```
/home/user/probe/jrun.sh /tmp/a22/Written.java
```
**Actual output**
```
|3.14159265358979323846264338327950288419716939937510d  -> CDecimal value=3.14159265358979323846264338327950288419716939937510  scale=50  written=3.14159265358979323846264338327950288419716939937510  endsWithD=false
|1.5d  -> CDecimal value=1.5  scale=1  written=1.5  endsWithD=false
```
Consequence (`/tmp/a22/Lit.java`), the D-suffixed literal loses 20 digits and rounds:
```
### |3.14159265358979323846264338327950288419716939937510d
  node=TypedCDecimal
  info=ExprType[type=PrecisionDecimal[precision=38, scale=38], …]
  value(java.math.BigDecimal) = 3.14159265358979323846264338327950288420  precision=39 scale=38
```
(`…288419716939937510` → `…288420`.) Execution then returns `Double(3.141592653589793)`.

**Why it matters** — the repo's own rule is "NO FALLBACKS. NO DEFAULTING", and this file's own
comment promises a loud reject for explicit decimals. Instead **every** over-scale decimal
literal silently rounds. Also note the resulting node is *itself* incoherent: `precision=39`
with a declared `precision=38` (caught by my coherence probe as a second violation).

---

### [UNSOUND] `TypedMatchRuntime` — its only lowering is the exact first-accepting STATIC rule the node exists to prevent; a runtime match silently takes the wrong arm

**Evidence** — `TypedMatchRuntime.java:11-21` states the node's whole reason to exist:

> RUNTIME-dispatched `match` — kept when static dispatch cannot decide soundly: at least one
> branch's declared type is a STRICT SUBTYPE of the input's static type, so **the first-accepting
> rule would silently take a wider arm where real pure … takes the narrow one**.

Its only lowering is `Lowerer.java:3122-3124`:
```java
// static-dispatch match fold (MatchFold doc)
case com.legend.compiler.spec.typed.TypedMatchRuntime mr ->
        scalar(MatchFold.fold(mr), columns);
```
and `MatchFold.java:28-36` implements precisely the forbidden rule:
```java
static TypedSpec fold(TypedMatchRuntime mr) {
    for (TypedMatchRuntime.Arm arm : mr.arms()) {
        if (staticConforms(mr.input().info().type(), arm.typeFqn())) {
            return inlineParam(arm.body(), arm.param(), mr.input());
        }
    }
    throw new NotImplementedException("scalar match: no arm statically accepts input type " …);
}
```
`staticConforms` (`MatchFold.java:40-55`) returns `true` for `…::Any` unconditionally and
`false` for every non-`Primitive` input, so an `Any`-typed input matches the `Any` arm
*wherever it sits in the list*.

**Repro**
```
/home/user/probe/jrun.sh /tmp/a22/Exec.java \
 "|'x'->cast(@meta::pure::metamodel::type::Any)->match([s:String[1]|10, a:meta::pure::metamodel::type::Any[1]|20])" \
 '|1->cast(@meta::pure::metamodel::type::Any)->match([i:Integer[1]|100, a:meta::pure::metamodel::type::Any[1]|200])'
```
**Actual output**
```
### |'x'->cast(@meta::pure::metamodel::type::Any)->match([s:String[1]|10, a:meta::pure::metamodel::type::Any[1]|20])
  SQL: SELECT 20 AS value
  row Integer(20) |
### |1->cast(@meta::pure::metamodel::type::Any)->match([i:Integer[1]|100, a:meta::pure::metamodel::type::Any[1]|200])
  SQL: SELECT 200 AS value
  row Integer(200) |
```

**Why it matters** — the value is a compile-time-known `String 'x'`; the `String` arm is
**first**; real Pure's `Match` walks the runtime value and returns **10**. legend-lite returns
**20**. That is a silently wrong answer, produced by the one code path the node was introduced
to guard. The sibling case (`[i:Integer[1]|…, a:Any[1]|…]` on a known Integer) returns the
`Any` arm too. Related: with no `Any` arm at all the fold throws instead
(`'x'->cast(@Any)->match([i:Integer[1]|1, s:String[1]|2])` →
`NotImplementedException: scalar match: no arm statically accepts input type meta::pure::metamodel::type::Any`),
so `TypedMatchRuntime` is *either* wrong *or* unimplemented — never right.

---

### [CRASH/GAP] Missing lowering arms — `TypedCTime` corroborated, and 7 MORE variants reachable from plain queries

The orchestrator's `TypedCTime` report is **CORROBORATED**. I enumerated the lowering
dispatch's handled set mechanically and diffed it against the 70 permitted variants:

```
grep -oE "case (com\.legend\.compiler\.spec\.typed\.)?Typed[A-Za-z]+" Lowerer.java
  relation() body (lines 456-700)   → handled set W1
  scalar chain (lines 2342-3130)    → handled set W2   (scalarInner → scalarStructural
                                       → scalarRelationalArms → scalarValueTailArms)
W1 ∪ W2 missing from the 70:
  TypedAggColSpec TypedAggColSpecArray TypedCTime TypedColSpec TypedColSpecArray
  TypedEval TypedFuncColSpec TypedFuncColSpecArray TypedGetAll TypedGraphFetch
  TypedMatch TypedMilestonedAccess TypedNewInstanceCast TypedOver TypedSerialize
  TypedSortInfo TypedTypeRef TypedUserCall                                    (18)
```

I then built a query reaching each. Results (`/tmp/a22/Sweep.java`, all pasted verbatim):

| variant | query | outcome |
|---|---|---|
| `TypedCTime` | `\|%10:30:45` | `NotImplementedException: scalar lowering not yet implemented for TypedCTime` |
| **`TypedSortBy`** (collection form) | `\|[1,2,3]->sortBy(x\|$x)` | `NotImplementedException: scalar lowering not yet implemented for TypedSortBy` |
| **`TypedCast`** (class-collection form) | `model::Person.all()->cast(@model::Person)->project(~[a:p\|$p.age])` | `NotImplementedException: lowering not yet implemented for TypedCast` |
| `TypedColSpec` | `\|~a` | `NotImplementedException: scalar lowering not yet implemented for TypedColSpec` |
| `TypedColSpecArray` | `\|~[a,b]` | `… not yet implemented for TypedColSpecArray` |
| `TypedSortInfo` | `\|ascending(~a)` | `… not yet implemented for TypedSortInfo` |
| `TypedOver` | `\|over(~a)` | `… not yet implemented for TypedOver` |
| `TypedTypeRef` | `\|@Integer` | `… not yet implemented for TypedTypeRef` |

The two NEW ones that matter are:

**(a) `TypedSortBy` in collection position has no lowering at all.** `Lowerer.java:585`
(`case TypedSortBy sb -> Sorts.sortBy(this, sb);`) is the *only* `TypedSortBy` arm and it sits
inside `relation()`. `sortBy` is a registered COLLECTION native
(`TypedSortBy.java:8-18`: `sortBy<T,U|m>(col:T[m], key:{T[1]->U[1]}[0..1]):T[m]`), so
`[1,2,3]->sortBy(x|$x)` type-checks fine (`root=TypedSortBy type=Integer[3]`) and then dies:
```
### Q: |[1,2,3]->sortBy(x|$x)
  [G] root=TypedSortBy type=Integer[3]
  [PLAN-ERR] NotImplementedException: scalar lowering not yet implemented for TypedSortBy
### Q: |[1,2,3]->sortBy(x|$x)->first()
  [G] root=TypedNativeCall type=Integer[0..1]
  [PLAN-ERR] NotImplementedException: scalar lowering not yet implemented for TypedSortBy
```
Note the near-miss: `[1,2,3]->sort()` works (`SELECT UNNEST(list_filter(list_sort([1, 2, 3]),…))`),
and relation `sortBy` works — only the collection `sortBy` node is unreachable.

**(b) `TypedCast` over a CLASS collection.** `Lowerer.java` guards its relation `TypedCast`
arm with `when Type.relationSchema(c.source()…) instanceof RelationType && …`; a class-typed
cast falls to the frontier default:
```
### Q: model::Person.all()->cast(@model::Person)->project(~[a:p|$p.age])
  [G] root=TypedProject type=Relation<(a:Integer[1])>[1]
  [PLAN-ERR] NotImplementedException: lowering not yet implemented for TypedCast
```

The remaining 10 of the 18 are **legitimately not lowering's job** and I verified each:
`TypedGetAll` / `TypedUserCall` / `TypedNewInstanceCast` / `TypedGraphFetch` /
`TypedMilestonedAccess` / `TypedSerialize` are Phase-H vocabulary (`StoreEscapees.java:17-39`
asserts the first two never survive; `Lowerer.java:674-682` gives `TypedJoinSlot` its own named
resolver-gap message); `TypedMatch` and `TypedEval` are β-reduced by `UserCallInliner`
(verified: `|{x:Integer[1]|$x + 1}->eval(1)` → `SELECT 1 + 1`,
`|'x'->match([s:String[1]|1, i:Integer[1]|2])` → `SELECT 1`); and
`TypedFuncColSpec/Array`, `TypedAggColSpec/Array` cannot stand alone at all — the checker
refuses them (`~a:x|1` → `TypeInferenceException: ~a: mapped/aggregate column specifications
need an enclosing call to type against`).

Severity note: all of these are `NotImplementedException` — the repo's designated
"feature not built" type (`error/NotImplementedException.java:3-8`), loud and named. They are
gaps, not hidden ICEs.

---

### [CRASH/ICE] `%25:00:00` escapes as `IllegalStateException` from the Typer, while the parallel date path raises a clean `ParseException`

**Evidence** — `Typer.java:178`:
```java
case CTime lit -> new TypedCTime(lit.requireValue(),
        ExprType.one(Type.Primitive.STRICT_TIME));
```
`CTime.requireValue()` (`CTime.java:35-41`):
```java
public PureTimeLiteral requireValue() {
    if (value == null) {
        throw new IllegalStateException(
                "time literal '%" + written + "' is out of range");
    }
    return value;
}
```

**Repro / Actual output** (`/tmp/a22/Trace.java`)
```
### |%25:00:00
  java.lang.IllegalStateException: time literal '%25:00:00' is out of range
    at com.legend.protocol.spec.CTime.requireValue(CTime.java:37)
    at com.legend.compiler.spec.Typer.synth(Typer.java:178)
    …
### |%2020-01-01T25:00:00
  ERR com.legend.parser.ParseException: [1:2] invalid date literal '%2020-01-01T25:00:00': invalid hour: 25
```

**Why it matters** — same class of user typo, two different error contracts: a clean
`ParseException` for the date and an `IllegalStateException` (an internal-error type) for the
time. `CTime.java:32-34` says "validation is the compiler's job, not the parser's" — but the
compiler's version of validation is an unchecked internal exception.

---

### [INCONSISTENCY / SILENT-SKIP] `TypedAggCol.orderKey` is a traversal child in 2 of the 7 carriers that hold `TypedAggCol`s

`TypedAggCol.java:16-17` carries `@Nullable TypedLambda orderKey`, and its own comment
(`:19-22`) records that dropping it "silently turned an ordered aggregate into an unordered
one at rebuild sites (remediation T2.2)". `children()` treatment is nonetheless split:

| carrier | `children()` includes `orderKey`? | cite |
|---|---|---|
| `TypedGroupBy` | **yes** | `TypedGroupBy.java:44-50` |
| `TypedAggregate` | **yes** | `TypedAggregate.java:26-32` |
| `TypedAggColSpec` | no | `TypedAggColSpec.java:19-21` (`List.of(col.map(), col.reduce())`) |
| `TypedAggColSpecArray` | no | `TypedAggColSpecArray.java:21-27` |
| `TypedExtendAgg` | no | `TypedExtendAgg.java:23-31` |
| `TypedExtendWindow` | no | `TypedExtendWindow.java:31-41` |
| `TypedPivot` | no | `TypedPivot.java:35-44` |

Because `TypedSpec.children()` is the single traversal spine every generic pass reuses
(`TypedSpec.java:90-97`), any variable-substituting walker (`UserCallInliner.rewriteSwitch`'s
`default -> n.mapChildren(...)`, `Substitution`, `VarUse.reads`) **cannot see inside the
orderKey lambda** in those five carriers. The `withChildren` implementations do preserve the
field verbatim, so nothing is *dropped* — but a rename/substitution that should reach it
silently does not.

**Reachability caveat (stated honestly):** the only site that sets a non-null `orderKey` today
is `CorrelatedSubselects.java:1070` (`grep -rn "new TypedAggCol("` finds 8 sites; the other 7
pass `null`), which runs *after* the inliner. So I could not build a user query that trips it.
It is a live structural inconsistency, not a demonstrated wrong answer.

---

### [INCONSISTENCY / SILENT-SKIP] `TypedGraphFetch.tree` / `TypedSerialize.tree` hold `TypedSpec` args that are deliberately NOT children

`TypedGraphTree.java:14-17` carries `List<TypedSpec> args`. `TypedGraphFetch.children()`
returns `List.of(source)` only (`TypedGraphFetch.java:29-38`, with the comment "tree ARGS are
deliberately NOT children — they stay VERBATIM through rewrites"); same in
`TypedSerialize.java:24-29`. Consequence: `UserCallInliner`'s β-substitution and α-renaming
never enter graph-tree arguments — the design compensates by re-resolving them through
`UserCallInliner.queryLets()` (`UserCallInliner.java:68-75, 124-127`). Recording it as a
documented divergence from the "one traversal spine" invariant, not as a defect; the fixture
model has no milestoned association so I could not exercise the compensating path.

---

### [SILENT FALLBACK] `TypedFrom` defaults an unknown connection type to `"H2"` — twice

`TypedFrom.java:82-84`:
```java
String db = ni.properties().get("type") instanceof
        TypedEnumValue ev ? String.valueOf(ev.value()) : "H2";
return simple + "(type = \"" + db + "\")";
```
and the raw-AST mirror at `TypedFrom.java:131-134`:
```java
String db = ke != null && ke.value()
        instanceof com.legend.protocol.spec.EnumValue ev
        ? ev.value() : "H2";
```
A `^RelationalDatabaseConnection(...)` with a missing or non-literal `type` property is
reported to the plan surface as `RelationalDatabaseConnection(type = "H2")`. The repo forbids
defaulting; this guesses a *dialect*. Citation-only — I could not build a runtime repro with
the fixture runtime (it declares `type: DuckDB`), so I am not claiming an observed wrong plan.

Same file, `TypedFrom` is also the **only** list-bearing variant with no compact constructor
at all (`TypedFrom.java:23-29`): `chainMappings`, `jsonSources` and `sqlSetups` are stored by
reference with no `List.copyOf`/`Map.copyOf`, unlike all 29 other copy-on-construct variants.

---

### [DOC-LIE] `TypedFilter` / `TypedLimit` / `TypedDrop` / `TypedSlice` javadoc claims "info() is the source type unchanged"; for the collection overload it is not

`TypedFilter.java:11-17` ("Filter only removes rows/elements, so `info()` is the source type
unchanged"), `TypedLimit.java:15` / `TypedDrop.java:13` / `TypedSlice.java:15`
("@param info the source type unchanged").

My coherence probe encodes that claim as a rule and it fails 8 times over 112 queries:
```
VIOLATION [filter-preserves-type] TypedFilter :: Integer[0..*] vs source Integer[3]   <<< |[1,2,3]->filter(x|$x > 1)
VIOLATION [limit-preserves-type]  TypedLimit  :: Integer[0..*] vs source Integer[3]   <<< |[1,2,3]->limit(2)
VIOLATION [limit-preserves-type]  TypedLimit  :: Integer[0..*] vs source Integer[3]   <<< |[1,2,3]->take(2)
VIOLATION [drop-preserves-type]   TypedDrop   :: Integer[0..*] vs source Integer[3]   <<< |[1,2,3]->drop(10)
VIOLATION [slice-preserves-type]  TypedSlice  :: Integer[0..*] vs source Integer[3]   <<< |[1,2,3]->slice(0,10)
```
The `[*]` is signature-driven (`Pure.java:1323`:
`filter<T>(value:T[*], func:{T[1]->Boolean[1]}[1]):T[*]`) and matches real Pure, so this is a
*widening*, i.e. information loss (`[3]->slice(0,2)` is provably `[2]`, stamped `[0..*]`), not
unsoundness. Ranked as DOC-LIE + INFORMATION LOSS. The relation overloads DO preserve the type
exactly (0 violations across all relation queries in the corpus).

---

### [ROBUSTNESS] `TypedAggregate.withChildren` / `TypedGroupBy.withChildren` call the arity guard AFTER indexing

`TypedAggregate.java:37-47` and `TypedGroupBy.java:55-72` both run `kids.get(i++)` inside the
rebuild loop and only then `TypedSpec.expectChildren(kids, i, …)`. A short `kids` list throws
`IndexOutOfBoundsException` instead of the designed named message
(`TypedSpec.java:128-134`: "withChildren arity guard — count drift throws, never skews"). Every
other variant calls `expectChildren` first. Low severity (internal-caller-only path).

---

## 1. VARIANT TABLE — components, `info()` storage, computed-type audit (task item 2)

Full reflective dump: `Meta.java`. **Result: all 70 variants store `info` as a record component
— zero variants COMPUTE `info()`.** The "typed HIR is inert annotated data" invariant holds for
`info()` across the whole hierarchy:

```
=== variants with NO stored `info` component: []
```

Components per variant (arity in brackets; `info` is a component of every one):

```
TypedRawSqlRelation     [2] sql, info
TypedCInteger           [2] value:Number, info
TypedCString            [2] value:String, info
TypedCBoolean           [2] value:boolean, info
TypedCFloat             [2] value:double, info
TypedCDecimal           [2] value:BigDecimal, info
TypedVariable           [2] name, info
TypedPropertyAccess     [3] source, property, info
TypedNativeCall         [3] callee:TypedFunction, args:List<TypedSpec>, info
TypedUserCall           [3] callee:TypedFunction, args:List<TypedSpec>, info
TypedLet                [3] name, value, info
TypedCollection         [3] elements:List<TypedSpec>, info, rowCells:boolean
TypedIf                 [4] condition, thenBranch, elseBranch:Optional, info
TypedLambda             [3] parameters:List<String>, body:List<TypedSpec>, info
TypedFilter             [4] source, predicate:TypedLambda, info, stamp:Stamp
TypedMap                [3] source, mapper:TypedLambda, info
TypedColSpec            [2] name, info
TypedColSpecArray       [2] names:List<String>, info
TypedSortInfo           [3] column, ascending:boolean, info
TypedFuncColSpec        [2] col:TypedFuncCol, info
TypedFuncColSpecArray   [2] cols:List<TypedFuncCol>, info
TypedAggColSpec         [2] col:TypedAggCol, info
TypedAggColSpecArray    [2] cols:List<TypedAggCol>, info
TypedExtend             [3] source, columns:List<TypedFuncCol>, info
TypedGroupBy            [4] source, keys:List<GroupKey>, aggs:List<TypedAggCol>, info
TypedAggregate          [3] source, aggs:List<TypedAggCol>, info
TypedEnumValue          [3] enumFqn, value, info
TypedCDate              [2] value:PureDateLiteral, info
TypedCTime              [2] value:PureTimeLiteral, info
TypedCLatestDate        [1] info
TypedTypeRef            [2] target:Type, info
TypedCast               [4] source, target:Type, info, wire:boolean
TypedMatch              [6] input, param, body, extraParam:Optional, extra:Optional, info
TypedMatchRuntime       [5] input, arms:List<Arm>, extraParam:Optional, extra:Optional, info
TypedEval               [3] fn, args:List<TypedSpec>, info
TypedTds                [2] rows:List<List<String>>, info
TypedSourceUrl          [2] url, info
TypedFlatten            [3] source, column, info
TypedCollectionRelation [3] value, column, info
TypedPivot              [5] source, pivotColumns, values:List<TypedSpec>, aggs, info
TypedSortBy             [5] source, key:TypedLambda, ascending:boolean, keyAlias, info
TypedGetAll             [5] classFqn, milestoning:List<TypedSpec>, versionSweep, forEachDate, info
TypedMilestonedAccess   [5] source, property, dates:List<TypedSpec>, sweep, info
TypedFrom               [8] source, mapping:Optional, runtime:Optional, chainMappings,
                            jsonSources:Map, sqlSetups:List, connectionName, info
TypedWrite              [3] source, destination:Optional, info
TypedFold               [5] source, reducer:TypedLambda, init, strategy:FoldStrategy, info
TypedNavigate           [8] source, alias:Optional, target, predicate, pairedPredicate:Optional,
                            frameName, form:Form, info
TypedGraphFetch         [4] source, tree:List<TypedGraphTree>, info, checked:boolean
TypedNewInstanceCast    [4] classFqn, source, info, targetSetId
TypedSerialize          [4] source, tree:List<TypedGraphTree>, config:Optional, info
TypedSerializeGraph    [17] source, rowVar, leaves, nested, arrayWrap, bareValue, classFqn,
                            info, inlineChild, subTypePatches, orderKeys, typeKeyName,
                            fqTypePath, checkedConstraints, removeNullKeys, removeEmptySets,
                            objectRefPrefix
TypedOver               [4] partitions, sortKeys:List<TypedSortKey>, frame:Optional<WindowFrame>, info
TypedExtendWindow       [5] source, window:TypedOver, columns, aggs, info
TypedExtendAgg          [3] source, aggs:List<TypedAggCol>, info
TypedJoin               [8] left, right, kind:TypedEnumValue, condition, prefix:Optional,
                            frameName, info, userCondition:boolean
TypedJoinSlot           [6] source, alias, target, condition, frameName, info
TypedAsOfJoin           [6] left, right, match, condition:Optional, prefix:Optional, info
TypedSelect             [3] source, columns:List<String>, info
TypedDistinct           [3] source, columns:List<String>, info
TypedConcatenate        [3] left, right, info
TypedLimit              [3] source, count, info
TypedDrop               [3] source, count, info
TypedSlice              [4] source, start, stop, info
TypedPackageableRef     [2] fullPath, info
TypedProject            [4] source, columns:List<TypedFuncCol>, info, wireForm:boolean
TypedTableReference     [3] store, table, info
TypedSort               [4] source, keys:List<TypedSortKey>, pureNullOrder:boolean, info
TypedRename             [3] source, renames:List<ColRename>, info
TypedNewInstance        [3] classFqn, properties:Map<String,TypedSpec>, info
TypedCopyInstance       [4] source, classFqn, overrides:Map<String,TypedSpec>, info
```

### Variants that COMPUTE something inside the record (none compute `info()`; five compute other things)

| variant | member | what it computes | verdict |
|---|---|---|---|
| `TypedProject` | `docsFold()` `:51-66` | builds a fresh `TypedCollection` of `TypedCString` docs and **synthesises an `ExprType`** `(STRING, Bounded(n,n))` | the one place a typed node mints a type. Undocumented columns are skipped, so `n != columns.size()` — matches Pure's `[0..1]` flatten semantics, so I judge the rule CORRECT, but it is type computation inside "inert data". |
| `TypedFold` | `columnCollectBody()` `:49-68` | re-derives the COLUMN-COLLECT fold shape by structural sniffing (checks the reducer body is a `concatenate(x, $acc)`) even though `FoldStrategy` was already classified at check time (`FoldChecker`) | INCONSISTENCY with the repo's own stated rule — `TypedCollection.java:16-20`: "label at construction, don't sniff at consumption; the shape-matcher … was the disease's own idiom applied to ourselves". Two classifications of the same fold now exist. |
| `TypedMap` | `singleHopProperty()` `:36-47` | structural sniff for `map(src, v\|$v.prop)` | sanctioned: documented as "THE canonical link-reader (D3)", single owner. |
| `TypedRawSqlRelation` | `lateBoundCellRead()` `:48-59` | strips `toOne` wrappers, tests `RelationType.isLateBound()` | reads only `info()`; no type minted. OK. |
| `TypedFrom` | `connectionNameIn` `:68-108`, `jsonSourcesIn` `:164-204`, `chainMappingsIn` `:284-310`, `sqlSetupsIn` `:319-362`, plus raw-AST mirrors `:110-158, 208-246, 367-426` | four literal-folding walkers, two of them duplicated against the **untyped** `protocol.spec` AST | 300 of `TypedFrom`'s 492 lines are resolver logic living on a data record; carries the `"H2"` silent default (finding above). The duplication (`collectJson` vs `collectJsonRaw`, `collectSqlSetups` vs `collectSqlSetupsRaw`, `foldLiteral` vs `foldRawLiteral`) is two implementations of one rule. |

`TypedSerializeGraph` (269 lines) computes nothing: it is 8 telescoping compat constructors,
one `asArrayWrapped()` field-preserving copy (`:93-99`), a `PK_ORDER_PREFIX` constant, and
`children()`/`withChildren()` that traverse `leaves ∪ nested ∪ subTypePatches(leaves, member,
children) ∪ orderKeys ∪ checkedConstraints(predicate, message)` symmetrically. I checked the
two orders line-by-line against each other (`:202-221` vs `:224-268`): they agree, and
`expectChildren(kids, i, …)` is asserted after the walk. **Verified sound.**

---

## 2. VALIDATION TABLE — compact constructors (task item 3)

Mechanically extracted (`awk` over each `public <Name> {` block). **30 of 70** variants have a
compact constructor; **all 30 do defensive copies only**, except two:

- `TypedMatchRuntime.java:34-39` — `if (arms.isEmpty()) throw new IllegalArgumentException(...)`
- `TypedNewInstanceCast.java:26-29` — `Objects.requireNonNull(classFqn)`, `requireNonNull(source)`

**40 of 70 have no compact constructor at all**: `TypedAggColSpec, TypedAsOfJoin, TypedCBoolean,
TypedCDate, TypedCDecimal, TypedCFloat, TypedCInteger, TypedCLatestDate, TypedCString, TypedCTime,
TypedCast, TypedColSpec, TypedCollectionRelation, TypedConcatenate, TypedDrop, TypedEnumValue,
TypedFilter, TypedFlatten, TypedFold, TypedFrom, TypedFuncColSpec, TypedIf, TypedJoin,
TypedJoinSlot, TypedLet, TypedLimit, TypedMap, TypedMatch, TypedNavigate, TypedPackageableRef,
TypedPropertyAccess, TypedRawSqlRelation, TypedSlice, TypedSortBy, TypedSortInfo, TypedSourceUrl,
TypedTableReference, TypedTypeRef, TypedVariable, TypedWrite`.

Therefore:
- **0/70 validate `info != null`.** (Reflective probe: `Meta.java` constructs 33 of the 70 with
  every component `null` and gets an object back; e.g.
  `TypedCInteger  CONSTRUCTED-WITH-ALL-NULL  children=0`,
  `TypedFilter  CONSTRUCTED-WITH-ALL-NULL  children=children() threw NullPointerException`.
  The 37 "rejections" are all incidental `List.copyOf(null)`/`Map.copyOf(null)` NPEs, not checks.)
- **0/70 validate type/multiplicity coherence with children.**
- **0/70 validate the declared relation schema against the colspec list.**

### Malformed instances DO survive to output (`/tmp/a22/Malformed.java`)

I built each malformed node directly and pushed it through `Lowerer.lower` → `DuckDb().render`
→ JDBC. Base query: `#>{store::PersonDatabase.T_PERSON}#->project(~[a:r|$r.ID, b:r|$r.AGE_VAL])`.

| # | malformation | lower | render | declared plan `outputs` | DB result |
|---|---|---|---|---|---|
| M1 | schema = 1 col, colspec list = 2 | ok | ok | `[a:BIGINT]` (1) | 2 columns returned |
| M2 | schema = 3 cols (`a,b,ghost`), colspec list = 2 | ok | ok | `[a:BIGINT, b:BIGINT, ghost:VARCHAR]` (3) | 2 columns returned |
| M3 | schema names `zzz,yyy` vs colspecs `a,b` | ok | ok | `[zzz:BIGINT, yyy:BIGINT]` | SQL emits `AS a, AS b` |
| M4 | column `a` declared `String`, lambda body `Integer` | ok | ok | `[a:BIGINT, b:BIGINT]` | Integer |
| M5 | `TypedFilter.info` = 1 col, source = 2 cols | ok | ok | `[a,b]` | 2 columns |
| M8 | `TypedLimit.info` = 1 col, source = 2 cols | ok | ok | `[a,b]` | 2 columns |
| M10 | `Decimal(3,1)` stamping `12345678901.5` | ok | ok | `Decimal(12,1)` | `12345678901.5` |
| M11 | `TypedCString("hello")` stamped `Integer` | ok | ok | `[value:VARCHAR]` | `hello` |
| M6 | `TypedSelect(~[nosuch])` | **CAUGHT** | — | — | `IllegalStateException: select/distinct columns [nosuch] cannot all be resolved even after isolation` |
| M7 | `TypedSort` on absent key | **CAUGHT** | — | — | `IllegalStateException: sort key 'nosuch' cannot be resolved after isolation` |
| M9 | `[1,2,3]` stamped `[1]` | **CAUGHT** | — | — | `IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED … ONE-STAMP/LIST-SHAPE mult=[1..1] sql=ArrayLit node=TypedCollection` |

Actual output for the decisive rows:
```
---- M2 schema=3col colspecs=2
   SQL: SELECT t0.ID AS a, t0.AGE_VAL AS b FROM T_PERSON AS t0
   OUTPUTS: [OutputCol[name=a, type=BIGINT, …], OutputCol[name=b, type=BIGINT, …], OutputCol[name=ghost, type=VARCHAR, …]]
   COLS: a:INTEGER b:INTEGER
---- M3 schema names zzz,yyy vs colspecs a,b
   SQL: SELECT t0.ID AS a, t0.AGE_VAL AS b FROM T_PERSON AS t0
   OUTPUTS: [OutputCol[name=zzz, type=BIGINT, …], OutputCol[name=yyy, type=BIGINT, …]]
   COLS: a:INTEGER b:INTEGER
```

**Verdict:** for `TypedProject` (arity, names, types), `TypedFilter`, `TypedLimit` and the scalar
literals, a malformed node reaches the rendered SQL *and the plan's declared output contract*
with no diagnostic, and the two disagree — a name-keyed decoder finds nothing, an arity-keyed
one runs off the end. Column-name and multiplicity lies ARE caught (by the lowerer's column
resolver and the stamp-discipline guard respectively) — that is real, working defence, but it
lives entirely outside the typed HIR. **I could not construct any of M1-M11 from a real query**
(the 112-query coherence sweep found zero project/select/extend/groupBy/rename/join arity or
name violations), so this is "the node type does not defend itself", not "the checker emits
garbage today".

---

## 3. WALKER × VARIANT MATRIX (task item 4)

I inventoried every generic `TypedSpec` walk (`grep -rn "\.children()"` over
`core/src/main/java`, 47 files) and classified the traversal style. Walkers that recurse via
`children()` with no `switch` are **exhaustive by construction** and cannot silently skip.

| # | walker | file:lines | style | default arm | missing variants |
|---|---|---|---|---|---|
| W1 | `Lowerer.relation()` | `Lowerer.java:456-700` | switch | `throw NotImplementedException` (LOUD) | 39 — see below |
| W2 | `Lowerer` scalar chain | `Lowerer.java:2342-3130` | 4 chained switches | `throw NotImplementedException` (LOUD) | 41 |
| W1∪W2 | lowering as a whole | | | | **18** (listed in the finding above) |
| W3 | `CollectionLanes.valueLane` | `CollectionLanes.java:104-206` | switch, **no default** | javac-enforced | **0** ✅ |
| W4 | `StoreEscapees.check` | `StoreEscapees.java:21-39` | `for (c : n.children())` | n/a | **0** ✅ |
| W5 | `VarUse.reads` | `VarUse.java:18-31` | `for (c : n.children())` | n/a | **0** ✅ |
| W6 | `UserCallInliner.rewriteSwitch` | `UserCallInliner.java:327-517` | switch | `default -> n.mapChildren(...)` | **0** ✅ (delegating) |
| W7 | `UserCallInliner.reserveFreshNames` | `UserCallInliner.java:135-145` | switch + children walk | `default -> {}` then walks children | binder-bearing variants not in the switch: `TypedMatch.param`, `TypedMatchRuntime.Arm.param`, `TypedMatch/MatchRuntime.extraParam`. Harmless in practice: a *used* binder is seen through its `TypedVariable` occurrences; only an unused `_iN`-named match param escapes. |
| W8 | `Substitution.rewrite` (object space) | `Substitution.java:1740-1907` | switch | `throw NotImplementedException` (LOUD) | **45** |
| W9 | `Substitution.inlineParam` | `Substitution.java:2219-2277` | switch | `throw NotImplementedException` (LOUD) | **52** |
| W10 | `Anchors.objectSpine` | `Anchors.java:84-116` | switch | `default -> false` (**SILENT**) | **60** |
| W11 | `Pipelines.narrowViewFrame` | `Pipelines.java:290-337` | switch | `default -> pipeline` (SILENT, conservative no-op) | 68 |
| W12 | `SyntheticHeads` rewriters | `SyntheticHeads.java:531, 1123` | switch | `default -> n` (SILENT identity) | — |
| W13 | `GraphEmission` rewriter | `GraphEmission.java:3413` | switch | `default -> n` (SILENT identity) | — |
| W14 | `TypedFrom.collectJson/collectChain/collectSqlSetups` | `TypedFrom.java:177-204, 290-310, 336-362` | `for (c : n.children())` | n/a | **0** ✅ (but blind to non-child `tree` args) |
| W15 | `UserCallInliner.referencesVar` / `deepFoldInlined` | `UserCallInliner.java:308-325, 685-694` | children walk / mapChildren | n/a | **0** ✅ |

**W1 missing (relation position, 39):** TypedAggColSpec, TypedAggColSpecArray, TypedCBoolean,
TypedCDate, TypedCDecimal, TypedCFloat, TypedCInteger, TypedCLatestDate, TypedCString, TypedCTime,
TypedColSpec, TypedColSpecArray, TypedCollection, TypedCopyInstance, TypedEnumValue, TypedEval,
TypedFold, TypedFuncColSpec, TypedFuncColSpecArray, TypedGetAll, TypedGraphFetch, TypedIf,
TypedLambda, TypedLet, TypedMap, TypedMatch, TypedMatchRuntime, TypedMilestonedAccess,
TypedNewInstance, TypedNewInstanceCast, TypedOver, TypedPackageableRef, TypedSerialize,
TypedSerializeGraph, TypedSortInfo, TypedTypeRef, TypedUserCall, TypedVariable, TypedWrite.
(Most are scalar-only by nature — W1∪W2 is the meaningful diff.)

**W2 missing (scalar position, 41):** TypedAggColSpec, TypedAggColSpecArray, TypedAggregate,
TypedAsOfJoin, TypedCTime, TypedColSpec, TypedColSpecArray, TypedCollectionRelation,
TypedConcatenate, TypedDistinct, TypedEval, TypedExtend, TypedExtendAgg, TypedExtendWindow,
TypedFlatten, TypedFuncColSpec, TypedFuncColSpecArray, TypedGetAll, TypedGraphFetch,
TypedGroupBy, TypedJoin, TypedJoinSlot, TypedMatch, TypedMilestonedAccess, TypedNavigate,
TypedNewInstanceCast, TypedOver, TypedPivot, TypedProject, TypedRawSqlRelation, TypedRename,
TypedSelect, TypedSerialize, **TypedSort, TypedSortBy**, TypedSortInfo, TypedSourceUrl,
TypedTableReference, TypedTds, TypedTypeRef, TypedUserCall.
Note `scalarValueTailArms` has a catch-all `case TypedSpec rel when Type.schemaView(rel.info()…)`
(`Lowerer.java:3061`) that rescues most relation-typed nodes in scalar position — which is why
`TypedSort` is fine but `TypedSortBy` (whose collection form is NOT relation-typed) is not.

**W8 missing (45)** — and unlike the lowering gaps, these are reachable from perfectly ordinary
queries. Proved consequences (`/tmp/a22/Sweep.java`, verbatim):
```
### Q: model::Person.all()->project(~[a:p|%10:30:45])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedCTime is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|%latest])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedCLatestDate is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|@Integer->toString()])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedTypeRef is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|[1,2,3]->drop(1)->size()])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedDrop is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|[1,2,3]->slice(0,2)->size()])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedSlice is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|[1,2,3]->fold({e,acc|$acc+$e},0)])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedFold is not substitutable yet (H2 vocabulary)
### Q: model::Person.all()->project(~[a:p|model::Person])
  [PLAN-ERR] NotImplementedException: object-space expression node TypedPackageableRef is not substitutable yet (H2 vocabulary)
```
This is a **position inconsistency**: `|[1,2,3]->fold({e,a|$a+$e},0)` lowers fine standalone
(`SELECT list_reduce(coalesce([1, 2, 3], []), (a, e) -> a + e, 0) AS value`) but the same
sub-expression inside a class projection is rejected at H. `|%2020-01-01` works in a class
projection but `|%10:30:45` does not.

**W10 `Anchors.objectSpine` is the only genuinely SILENT walker among the big ones** — its
`default -> false` (`Anchors.java:113`) means "not object space", which routes the node away
from store resolution. Its lane-preserving set is `{TypedFrom, TypedFilter, TypedLimit,
TypedDrop, TypedSlice, TypedSortBy, TypedGetAll, class-result TypedMap/TypedPropertyAccess,
5 native-call shapes}`. `TypedCast` is missing, and that is exactly the finding above:
```
### Q: model::Person.all()->cast(@model::Person)->project(~[a:p|$p.age])
  [PLAN-ERR] NotImplementedException: lowering not yet implemented for TypedCast
```
The silent `false` sends a class-typed cast down the non-object path; the failure then surfaces
three phases later as a *lowering* error, misdiagnosing a resolver gap. (`TypedConcatenate` and
`TypedGraphFetch` are also absent from W10 but are rescued elsewhere: `concatenate` on classes
plans fine, `graphFetch` gets its own named `StoreResolver.java:494-496` wall.)

---

## 4. COHERENCE PROBE (task item 5)

`/tmp/a22/Coh.java` — compiles a corpus, walks the typed HIR through `children()`, and checks
20 locally-derivable rules: source-type preservation (`filter`/`limit`/`drop`/`slice`/`sort`/
`sortBy`), `project` schema-arity + per-column name + per-column type vs the lambda body type,
`select` arity + names-present-in-source, `extend`/`extendAgg`/`extendWindow` arity arithmetic,
`groupBy` = keys+aggs, `aggregate` = aggs, `rename` arity, `concatenate` three-way type
equality, `join`/`asOfJoin` arity = left+right, `let` = value type, collection multiplicity =
element count, `cast` info-type = target, `funcColSpec` column type = body type,
`if` then-type when there is no else, `getAll` is many, **and the lambda-param rule**
(a `filter`/`map` lambda's declared parameter type must equal what its source supplies, at `[1]`).

Corpus: 114 lines covering literals (all 8 kinds incl. boundary/overflow values), relation
sources (`#>{db.T}#`, `#TDS…#`, `Class.all()`), every relation operator
(`project select extend rename distinct sort sortBy limit drop slice filter groupBy aggregate
pivot concatenate join asOfJoin extend(over(...)) write`), collection ops
(`map filter fold sortBy sort reverse distinct concatenate slice drop limit at size sum`),
control flow (`if match eval let`), `graphFetch`/`serialize`, casts, enums, and nested/class
projections.

```
=== queries=112 compiled=101 violations=10
```
All 10 violations are the two families already reported (2 × `decimal-precision` = UNSOUND;
8 × collection `filter/limit/drop/slice` multiplicity widening = DOC-LIE). **Zero** violations
of every schema-arity, column-name, column-type, join-arity, rename-arity, concatenate,
let-type, collection-count, cast-target and **lambda-param** rule across 101 compiled queries.
That is strong positive evidence: the Typer's schema algebra and lambda binding are internally
consistent on everything I could reach.

---

## 5. LITERAL NODES (task item 6)

| node | Java backing field | Pure type | covers the range? |
|---|---|---|---|
| `TypedCInteger` | **`Number`** (`Long`, or `BigInteger` past `Long.MAX_VALUE`) | `Integer` | **Yes at the node.** `\|9223372036854775808` → `value(java.math.BigInteger)`; `\|1` → `value(java.lang.Long)`. Arbitrary precision preserved in the HIR. |
| `TypedCFloat` | `double` | `Float` | Yes (Pure `Float` is IEEE double). Note the parser PROMOTES an inexact decimal-text float to `CDecimal` (`SpecParser.java:815-837`), so `\|0.1` stays `TypedCFloat` only because `BigDecimal.valueOf(0.1).equals(new BigDecimal("0.1"))`. |
| `TypedCDecimal` | **`java.math.BigDecimal`** ✅ | `Decimal` | **Field type is correct** — NOT `double`. The failure is in the *type stamp*, not the storage: see the two UNSOUND findings above (precision-blind `decimalType`, dead D-suffix guard). |
| `TypedCString` | `String` | `String` | Yes. |
| `TypedCBoolean` | `boolean` | `Boolean` | Yes. |
| `TypedCDate` | `com.legend.values.PureDateLiteral` (structured sealed: `Year`, `YearMonth`, `StrictDate`, `DateWithSubsecond`, …) | `Date`/`StrictDate`/`DateTime` | Yes; precision-typed at `Typer.java:177` via `dateType()`. `%2020`→`Date`, `%2020-01-01`→`StrictDate`, `%…T10:00`→`DateTime`, nanosecond subseconds preserved (`DateWithSubsecond[…123456789]`). |
| `TypedCTime` | `com.legend.values.PureTimeLiteral` (`TimeWithMinute`, `TimeWithSecond`, `TimeWithSubsecond`) | `StrictTime` | Node is fine and accepts 12-digit subseconds (`%23:59:59.999999999999` → `subsecond=999999999999`). But it **has no lowering at all**, and out-of-range values escape as `IllegalStateException` (two findings above). |
| `TypedCLatestDate` | *(no value field — `[1]` component: `info` only)* | `LatestDate` | Yes; a pure marker. Lowers to `TIMESTAMP '9999-12-31 00:00:00.0000'`. |

Range probes actually run (`/tmp/a22/Lit.java`, `/tmp/a22/Exec.java`):
```
|9223372036854775807 → Long   → row Long(9223372036854775807)
|9223372036854775808 → BigInteger → row BigInteger(9223372036854775808)
|99999999999999999999999999999999999999999 (41 digits) → BigInteger in the node,
                                              but row Double(1.0E41)   ← precision lost at K
|12345678901234567890123456789012345678d (38 digits) → row BigDecimal(1234…678)   ✅
|1.5d → row BigDecimal(1.5)   ✅
|%9999-12-31 → SELECT DATE '9999-12-31'   ✅
|%0001-01-01 → SELECT DATE '1-01-01'      ← zero-padding lost in the rendered literal (low)
```
The 41-digit-integer `Double(1.0E41)` is the same class of defect as the decimal one but the
loss happens on the K side (DuckDB widens past `HUGEINT` to `DOUBLE`); the HIR node itself is
faithful. Flagging for the decode auditor rather than claiming it here.

---

## VERIFIED SOUND

- **Sealing is exact.** 70 `permits` names ↔ 70 implementing files ↔
  `getPermittedSubclasses().length == 70`. No orphan, no phantom. (§0)
- **`info()` is a stored field on all 70 variants** — no variant computes its own type.
  The "schema-in-info()" invariant (`TypedSpec.java:7-16`) holds. (§1)
- **`children()`/`withChildren()` are mutual inverses** — I read all 70 pairs. Every
  `withChildren` reassembles in the same positional order `children()` emits, preserves every
  non-child field explicitly (`TypedFilter.stamp`, `TypedJoin.frameName/userCondition/prefix`,
  `TypedGetAll.versionSweep/forEachDate`, `TypedCast.wire`, `TypedProject.wireForm`,
  `TypedSort.pureNullOrder`, `TypedSortBy.keyAlias/ascending`, `TypedNavigate.frameName/form`,
  `TypedGraphFetch.checked`, `TypedNewInstanceCast.targetSetId`, `TypedCollection.rowCells`,
  `TypedSerializeGraph`'s 10 flags), and 62 of 70 assert arity via `expectChildren`. The 8
  without an explicit guard are the variadic ones (`TypedCollection`, `TypedLambda`,
  `TypedNativeCall`, `TypedUserCall`, `TypedGetAll`) plus the four that compute their own
  expected count. `TypedSerializeGraph`'s 17-component version — the largest — is correct.
- **`CollectionLanes.valueLane` (`CollectionLanes.java:104-206`) is genuinely exhaustive** over
  all 70 variants with no `default` — javac is the referee, exactly as its comment claims.
  The one prose claim in the package I tried hardest to falsify, and it held.
- **`StoreEscapees`, `VarUse`, `UserCallInliner.rewriteSwitch`'s default,
  `UserCallInliner.referencesVar`, `deepFoldInlined`, and `TypedFrom`'s four collectors all
  traverse via `children()`** and therefore cannot silently skip a variant.
- **The multiplicity-stamp guard catches a lying collection.** Hand-building
  `TypedCollection([1,2,3], Integer[1])` throws
  `MULTIPLICITY-STAMP INVARIANT VIOLATED … ONE-STAMP/LIST-SHAPE`. Real, working defence.
- **The lowerer's column resolver catches schema-name lies.** `TypedSelect(~[nosuch])` and
  `TypedSort` on an absent key both throw named `IllegalStateException`s.
- **101 real queries, 20 coherence rules, zero structural violations.** Every relation operator's
  declared schema matched its own components (arity, names, types) and every lambda's declared
  parameter type matched what its source supplies.
- **`TypedNewInstance`/`TypedCopyInstance` use `LinkedHashMap`, not `Map.copyOf`** — the
  determinism fix their comments describe is real and correct (`Map.copyOf` would randomize
  `children()` order per JVM run).
- **Relation-form `filter/limit/drop/slice/sort/select/distinct/rename/extend/groupBy/
  aggregate/concatenate/join/asOfJoin/project/pivot/extendWindow` all preserve or compute their
  schemas correctly** on every query in the corpus.
- `TypedFold.withChildren` correctly round-trips the `FoldStrategy.MapReduce` sub-lambdas
  (`TypedFold.java:33-42`) — the remediation-T2.1 comment is accurate.

---

## NOT COVERED

- **Milestoning / temporal nodes.** `TypedMilestonedAccess`, `TypedGetAll.versionSweep/
  forEachDate`, `TypedCLatestDate` in its real (milestoning) position, and the
  `TypedGraphFetch`/`TypedSerialize` non-child `tree` args: the fixture model
  (`/home/user/probe/fx/model.pure`) has no temporal class and no milestoned association, so I
  could not build queries that exercise them. The `TypedGraphTree.args`-not-a-child structural
  point is reported from code only.
- **`TypedRawSqlRelation`, `TypedSourceUrl`, `TypedWrite`, `TypedJoinSlot`, `TypedNavigate`,
  `TypedNewInstanceCast`, `TypedCollectionRelation`, `TypedFlatten`, `TypedMatchRuntime`'s
  class-hierarchy arms** — reachable only through mapping/store shapes (or a `sourceUrl` native
  that is not registered: `unknown function 'meta::pure::functions::relation::sourceUrl'`) that
  the fixture cannot express. `TypedWrite` reached H and walled
  (`class query under TypedWrite is not resolvable yet`).
- **`TypedFrom`'s four literal-folding collectors** (`connectionNameIn`, `jsonSourcesIn`,
  `chainMappingsIn`, `sqlSetupsIn` — ~300 of its 492 lines, including the duplicated raw-AST
  mirrors) are reported by code reading only. The `"H2"` default is a citation, not a repro:
  building an instance-runtime `^RelationalDatabaseConnection` without a `type` property needs
  a corpus-style runtime I did not have.
- **`TypedSerializeGraph` in anger.** I verified `children()`/`withChildren()` symmetry by
  reading, and reached it through `serialize(#{…}#)` on the fixture, but did not exercise
  `subTypePatches`, `checkedConstraints`, `objectRefPrefix`, `typeKeyName` or `orderKeys` —
  all resolver-synthesised for shapes the fixture cannot produce.
- **`TypedOver.frame` / `WindowFrame`** bounds (`IntervalPreceding`/`IntervalFollowing`) —
  reached `over(~col)` only, not `rows(a,b)` / `_range(...)`.
- **Cross-dialect rendering** of the malformed and boundary nodes (I used DuckDB only;
  `DATE '1-01-01'` in particular may behave differently on H2/SQLite).
- **`Multiplicity.Var` leakage** into typed nodes — `requireBounded` exists
  (`Multiplicity.java:41-47`) but I did not hunt for a query that lands a `Var` on a node's
  `info()`; that belongs to the multiplicity auditor (A02).
- The parser-side `NumberFormatException` on `|1.0e400` (`SpecParser.java:832`,
  `BigDecimal.valueOf(Double.POSITIVE_INFINITY)`) is an ICE on plausible input, but it is the
  parser auditor's finding, not the typed HIR's — noted here only because I hit it while
  probing `TypedCFloat`'s range.
