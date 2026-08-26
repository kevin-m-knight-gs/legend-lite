# A06 — Typer (relation/TDS constructs, lambdas, colspecs, dispatch table) + StaticFold / NormalizeFolds / SourceSubst / SpecCompiler / Checkers / Env / Frames

Scope read **in full**: `Typer.java` (3186 lines), `StaticFold.java` (594), `NormalizeFolds.java`,
`SourceSubst.java`, `SpecCompiler.java`, `Checkers.java`, `Env.java`, `Frames.java`, `CoreFn.java`,
plus every `compiler/spec/*Checker.java` (33 files) at least skim-read and the ones cited read in full.

All repros are `/home/user/probe/probe.sh` runs against `/home/user/probe/fx/model.pure` +
`/home/user/probe/fx/ddl.sql` + `test::TestRuntime` unless a `/tmp/a06/model*.pure` is named
(those are the fixture model with extra *functions* / one extra mapped property appended; no repo
file was modified).

---

## 0. THE DISPATCH TABLE (exact, exhaustive)

Measured with a probe over `com.legend.builtin.Pure.all()` and `CoreFn.values()` (`/tmp/a06/Disp.java`):

```
### CoreFn constants: 51
### total parse names: 54          (aliases: asc/ascending, desc/descending, select/newTDSRelationAccessor, limit/take)
### native defs (overloads): 721
### distinct native FQNs: 431
### distinct bare names: 398
### bare native names WITHOUT a CoreFn arm: 345
### CoreFn parse names with NO Pure native: 5 -> [castAsDeclared, legacyNavigate, new, tds, typeAsDeclared]
```

**Checker classes:** 33 `*Checker.java` files exist; **all 33** are referenced from the exhaustive
`switch` in `Typer.applyCore` (`Typer.java:1231-1329`). No orphan/dead checker. (`Checkers.java` is a
shared-helper class, not a construct checker; `TableReferenceChecker` owns two arms.)

Full CoreFn → checker map (one line per construct, from `Typer.java:1231-1329`):

| CoreFn | parse name(s) | checker | native FQNs registered under the bare name |
|---|---|---|---|
| LET | letFunction | LetChecker | lang::letFunction (1) |
| IF | if | IfChecker | lang::if (2) |
| NEW | new | NewChecker | **none** |
| TABLE_REFERENCE | tableReference | TableReferenceChecker | database::tableReference (2) |
| TABLE_TO_TDS | tableToTDS | TableReferenceChecker.checkTableToTds | tds::tableToTDS (1) |
| PROJECT | project | ProjectChecker | relation::project, tds::project (3) |
| SORT | sort | SortChecker | collection::sort, relation::sort, tds::sort (6) |
| RENAME | rename | RenameChecker | relation::rename (2) |
| FILTER | filter | FilterChecker | collection/relation/tds::filter (3) |
| MAP | map | MapChecker | collection::map, relation::map (4) |
| ASC | asc, ascending | SortChecker.sortInfo | tds::asc (1), relation::ascending (1) |
| DESC | desc, descending | SortChecker.sortInfo | tds::desc (1), relation::descending (1) |
| SELECT | select, newTDSRelationAccessor | SelectChecker | relation::select (3), relation::newTDSRelationAccessor (1) |
| DISTINCT | distinct | DistinctChecker | collection/relation::distinct (3) |
| CONCATENATE | concatenate | ConcatenateChecker | collection/relation::concatenate (2) |
| LIMIT / TAKE | limit / take | SlicingChecker.limit | collection/relation::limit (3) / collection::take (2) |
| DROP | drop | SlicingChecker.drop | collection/relation::drop (2) |
| SLICE | slice | SlicingChecker.slice | collection/relation::slice (2) |
| EXTEND | extend | ExtendChecker | relation::extend (10) |
| GROUP_BY | groupBy | GroupByChecker | collection/relation/tds::groupBy (7) |
| AGGREGATE | aggregate | AggregateChecker | relation::aggregate (2) |
| JOIN | join | JoinChecker | relation::join (2) |
| AS_OF_JOIN | asOfJoin | AsOfJoinChecker | relation::asOfJoin (3) |
| CAST / TO / TO_MANY | cast / to / toMany | CastChecker | lang::cast (1) / variant::convert::to (1) / toMany (1) |
| TYPE_AS_DECLARED / CAST_AS_DECLARED | typeAsDeclared / castAsDeclared | inline arm in `applyCore` | bare: **none**; FQN `meta::legend::lite::*` (1 each) |
| MATCH | match | MatchChecker | lang::match (2) |
| EVAL | eval | EvalChecker | lang::eval (3) |
| TDS | tds | TdsChecker | bare: **none**; FQN `meta::legend::lite::tds` (1) |
| SOURCE_URL | sourceUrl | SourceUrlChecker | lite::sourceUrl (1) |
| FLATTEN | flatten | FlattenChecker | relation::variant::flatten (2) |
| PIVOT | pivot | PivotChecker | relation::pivot (5) |
| COLUMNS | columns | ColumnsChecker | relation::columns (1) |
| SORT_BY / SORT_BY_REVERSED | sortBy / sortByReversed | SortChecker.sortBy | collection::sortBy(1) / sortByReversed(1) |
| GET_ALL (+ForEachDate/Versions/InRange) | getAll… | GetAllChecker | collection::getAll (3) + 1 each |
| FROM | from | FromChecker | mapping::from (3) |
| WRITE | write | WriteChecker | relation::write (2) |
| FOLD | fold | FoldChecker | collection::fold (1) |
| NAVIGATE / LEGACY_NAVIGATE | navigate / legacyNavigate | NavigateChecker | lite::navigate (3) / bare none, FQN lite::legacyNavigate (2) |
| GRAPH_FETCH / …CHECKED / SERIALIZE | graphFetch / graphFetchChecked / serialize | GraphFetchChecker | graphFetch::execution::* (4/2/2) |
| OVER | over | OverChecker | relation::over (15) |

### (b) Checkers registered for names NOT declared in `Pure.java`
Exactly **one**: `CoreFn.NEW("new")` → `NewChecker`. `Pure.all()` contains no native whose bare or
qualified name is `new` (verified by scanning all 721 defs). The other four names in the list
(`tds`, `typeAsDeclared`, `castAsDeclared`, `legacyNavigate`) *are* declared, under
`meta::legend::lite::*`; they are absent from the bare-name index on purpose
(`Pure.Index.FN_BY_BARE` excludes the lite package, `Pure.java:953-957`) and `CoreFn.of` refuses
the bare spelling for them (`CoreFn.java:167-170`). Consequence for `new`: any `new(...)` shape that
is not `(ptr, NewInstance)` falls to `applyGeneric` and dies with
`unknown function 'new' — no function of this name in the native or user catalog`
(`Typer.java:1239-1258` → `Typer.java:1628`). Clean, no ICE.

### (a) Natives with no checker — what happens when a user calls them
**345 of 398** distinct bare native names have no `CoreFn` arm. There is no "missing typing rule":
they all go through the *one* generic rule `Typer.applyGeneric` → `checkGeneric`
(`Typer.java:1343`, `1612`), which resolves the overload from the registered signature. So typing
never falls off a cliff — but **lowering does**, and it does so with internal exception classes.

Exhaustive measurement (`/tmp/a06/NatSweep*.java`): every one of the **721** declared overloads was
called with arguments synthesised from its own declared parameter types, then run through
`Compiler.compileQuery` (phase G) and `Compiler.plan` (A–J):

```
TOTAL overloads: 721
=== OK : 523                      (phase G types it)
=== TypeInferenceException : 198  (clean, user-facing)
                                  <-- ZERO internal exceptions at phase G
```
```
TOTAL typed-OK overloads planned: 523
=== [408] PLAN-OK
=== [  3] clean domain errors (MappingResolutionException — milestoning not declared)
=== [112] INTERNAL EXCEPTIONS:
    [ 87] IllegalStateException "no scalar lowering registered for resolved overload '<fqn>' with N parameter(s)"
    [ 19] NotImplementedException (TypedSortInfo/TypedOver/TypedSortBy/TypedPivot/TypedWrite/TypedFlatten/… )
    [  2] UnfoldableRef "aggregate '<fqn>' in scalar position"
    [  2] IllegalStateException "pattern 'x' has no capturing group 1"      (regexpIndexOf)
    [  1] IllegalStateException "unsupported sourceUrl scheme: x"
    [  1] IllegalStateException "MULTIPLICITY-STAMP INVARIANT VIOLATED …"   (collection::first/2)
```
Representative repros of each distinct mode (all typed cleanly at G first):
```
'ab'->meta::pure::functions::string::regexpIndexOf('a', 1)
  [G] type=Integer mult=[1]
  [PLAN-ERROR] java.lang.IllegalStateException: pattern 'a' has no capturing group 1

meta::pure::functions::asserts::assert(true)
  [PLAN-ERROR] IllegalStateException: no scalar lowering registered for resolved overload
               'meta::pure::functions::asserts::assert' with 1 parameter(s)

model::Person.all()->project(~[a:p|$p.age])->select(~[a])->first(2)
  [G] type=Relation<(a:Integer[1])> mult=[*]
  [PLAN-ERROR] NotImplementedException: lowering not yet implemented for TypedNativeCall
               ('meta::pure::functions::collection::first' in relation position)
```
That 87-entry family is the SILENT-FALLBACK-adjacent one: the *catalog* advertises the function
(so it type-checks and appears usable) while the lowering has no rule, and the failure surfaces as
a raw `java.lang.IllegalStateException`, not a compile diagnostic.

---

## FINDINGS

### [UNSOUND — SILENT WRONG ANSWER] 1. `SourceSubst.inlineLets` performs capture-UNSAFE β-substitution: renaming a bound variable changes the result

**Evidence.** `Typer.typeLambda` folds a multi-statement lambda body with
`SourceSubst.inlineLets(lam)` (`Typer.java:2047`). `inlineLets` (`SourceSubst.java:41-54`)
substitutes each `let`'s *value expression* into every later statement via `SourceSubst.substitute`.
`substitute`'s lambda arm (`SourceSubst.java:102-122`) only removes the **shadowed name** from the
substitution environment — it never α-renames the inner binder, so a free variable inside the
substituted value is captured by an inner lambda that happens to bind the same name.
`Typer.alphaRename` (`Typer.java:1573-1600`) exists for exactly this hazard and is used by
`inlineNormalized`, but **is not used on this path**.

**Repro / actual output** (the two queries differ only in the inner binder's *name*):
```
$ echo "[10]->map({x| let y = \$x; [7]->map(z|\$y)->toOne();})" > q.pure   # binder named z
[G] type=Integer mult=[1]
[PLAN] SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], z -> x), 1)), 1) AS value
[EXEC-ROW] Integer(10) |

$ echo "[10]->map({x| let y = \$x; [7]->map(x|\$y)->toOne();})" > q.pure   # binder named x
[G] type=Integer mult=[1]
[PLAN] SELECT list_extract(list_transform([10], x -> list_extract(list_transform([7], x -> x), 1)), 1) AS value
[EXEC-ROW] Integer(7) |
```
`7` is wrong: `$y` was bound to the OUTER `x` = 10. The SQL shows the capture verbatim
(`list_transform([7], x -> x)`). **No error, no warning — a silently different answer.**

The same bug also manufactures *false compile errors* when the captured binder has an
incompatible type:
```
$ model::Person.all()->filter(p| let y = $p.firstName; [1,2]->map(p|$y)->size() > 0;)
                     ->project(~[a:p|$p.firstName])
[G-ERROR] TypeInferenceException: cannot access 'firstName' on Integer
```
(`$p.firstName` was spliced into `map(p|…)` where `p` is now an Integer.)

**Why it matters.** α-equivalence is the most basic soundness property of a substituting compiler.
This is the highest-severity finding in this file set: it silently changes the meaning of valid
programs.

---

### [UNSOUND — SILENT WRONG ANSWER] 2. `StaticFold` integer `plus` overflows silently; the folded literal is off by 2^64 from the un-folded computation

**Evidence.** `StaticFold.java:254-256`
```java
if (args.stream().allMatch(a -> a instanceof Long)) {
    return args.stream().mapToLong(a -> (Long) a).sum();
}
```
No overflow check. `reify` (`StaticFold.java:553-557`) turns the wrapped `Long` into a `CInteger`,
which `Typer.synth` types `Integer[1]` (`Typer.java:152`).

**Repro** — model `/tmp/a06/model2.pure` (fixture + two ordinary user functions; the only difference
between them is the stereotype that turns on `Typer.inlineNormalized` → `StaticFold`,
`Typer.java:1454`, `1573`):
```pure
function <<functionType.NormalizeRequiredFunction>> my::ovf(): Integer[1]  { 9223372036854775807 + 1 }
function                                          my::plainovf(): Integer[1] { 9223372036854775807 + 1 }
```
```
### my::ovf()          (StaticFold path)
[G] type=Integer mult=[1]
[PLAN] SELECT -9223372036854775808 AS value
[EXEC-ROW] Long(-9223372036854775808) |

### my::plainovf()     (no fold)
[G] type=Integer mult=[1]
[PLAN] SELECT CAST(9223372036854775807 AS HUGEINT) + 1 AS value
[EXEC-ROW] BigInteger(9223372036854775808) |
```
The un-folded path deliberately widens to `HUGEINT` so the value is right; the folded path wraps.
**Two answers for byte-identical source, differing by 2^64.**

The second, *fully user-reachable without any stereotype*, entry point is
`Typer.java:620-629` — `<relation>.columns->map(c|…)` runs `StaticFold.foldToLiteral` on the whole
`map`:
```
### model::Person.all()->project(~[a:p|$p.age]).columns->map(c|9223372036854775807 + 1)
[G] type=Integer mult=[1]
[PLAN] SELECT -9223372036854775808 AS value
[EXEC-ROW] Long(-9223372036854775808) |
```

---

### [UNSOUND — SILENT WRONG ANSWER] 3. `StaticFold` compares values with Java `Object.equals`, so every mixed-numeric `equal` / `in` / `indexOf` folds to the WRONG answer

**Evidence.** `StaticFold.staticEquals` (`StaticFold.java:532-539`) ends in `return a.equals(b);`.
`Long(1).equals(Double(1.0))` is `false`. The same Java equality backs
`case "in"` → `coll.contains(x)` (`StaticFold.java:303-310`) and
`case "indexOf"` → `coll.indexOf(x)` (`StaticFold.java:332-339`).

**Repro** (model `/tmp/a06/model2.pure` / `model3.pure`, `<<NormalizeRequiredFunction>>` vs plain):
```
### my::eqm()   { 1 == 1.0 }         FOLDED   -> [PLAN] SELECT FALSE  -> Boolean(false)
### my::plaineqm() { 1 == 1.0 }      UNFOLDED -> [PLAN] SELECT 1 = CAST(1.0 AS DOUBLE) -> Boolean(true)

### my::inm()   { 1->in([1.0]) }     FOLDED   -> [PLAN] SELECT FALSE  -> Boolean(false)
###   1->in([1.0])                   UNFOLDED -> coalesce(1 IN (CAST(1.0 AS DOUBLE)), FALSE) -> Boolean(true)

### my::idxf()  { [10,20,30]->indexOf(20.0) }    FOLDED   -> SELECT -1  -> Integer(-1)
### my::idxPlain() (same body)                   UNFOLDED -> ... -> Integer(1)
```
Also reproducible without the stereotype through the `.columns->map(...)` channel:
```
### model::Person.all()->project(~[a:p|$p.age]).columns->map(c| 1 == 1.0)
[PLAN] SELECT FALSE AS value      [EXEC-ROW] Boolean(false)
### 1 == 1.0
[PLAN] SELECT 1 = CAST(1.0 AS DOUBLE) AS value   [EXEC-ROW] Boolean(true)
```
Control: `2 == 2` folds to `TRUE` and runs `true` — so the fold is only wrong across numeric kinds,
which is exactly where a user will not look.

---

### [CRASH/ICE] 4. `StaticFold.sortBy` throws a raw `ClassCastException` out of phase G on a heterogeneous collection

**Evidence.** `StaticFold.java:380-402`:
```java
Object k = evalWith(lam, e, scope);
if (!(k instanceof Comparable)) { return null; }
...
Comparator<Map.Entry<Object,Object>> cmp = Comparator.comparing(en -> (Comparable) en.getValue());
keyed.sort(cmp);          // <-- Long vs String: CCE
```
The guard checks each key is `Comparable` but never that the keys are *mutually* comparable.

**Repro / actual output:**
```
$ model::Person.all()->project(~[a:p|$p.age]).columns->map(c| [1,'a']->sortBy(x|$x)->makeString(''))
[G-ERROR] java.lang.ClassCastException: class java.lang.Long cannot be cast to class java.lang.String
          (java.lang.Long and java.lang.String are in module java.base of loader 'bootstrap')
[PLAN-ERROR] (same)
[EXEC-ERROR] (same)
```
A raw JDK exception escapes type-checking on input a user can write.

---

### [UNSOUND + INCONSISTENCY] 5. `NormalizeFolds` folds `size(x)` from the *static* multiplicity; the same expression returns 0 directly and 1 through a one-line user function

**Evidence.** `NormalizeFolds.java:66-78`:
```java
if (!relationish && arg.info().multiplicity() instanceof Multiplicity.Bounded b
        && b.upper() != null && b.lower() == b.upper()) {
    return new TypedCInteger((long) b.lower(), ExprType.one(Type.Primitive.INTEGER));
}
```
The premise ("a `[n..n]` multiplicity is a type-level truth") is false in this compiler: a property
declared `[1]` over a NULLABLE column is routinely empty at runtime — the platform's own direct
lowering of `size()` emits `CASE WHEN col IS NULL THEN 0 ELSE 1 END`, i.e. it *knows* this.

**Repro.** Model `/tmp/a06/model4.pure` = fixture + `addrId: Integer[1]` mapped to the nullable
`T_PERSON.PRIMARY_ADDR_ID`, + `function my::sz(s: Integer[1]): Integer[1] { $s->size() }`.
DDL `/tmp/a06/ddl4.sql` = fixture DDL with `PRIMARY_ADDR_ID = NULL` for John.
```
### model::Person.all()->project(~[nm:p|$p.firstName, n:p|$p.addrId->size()])     (DIRECT)
[PLAN] SELECT t0.FIRST_NAME AS nm, CASE WHEN t0.PRIMARY_ADDR_ID IS NULL THEN 0 ELSE 1 END AS n
[EXEC-ROW] String(John) | Integer(0) |
[EXEC-ROW] String(Jane) | Integer(1) |

### model::Person.all()->project(~[nm:p|$p.firstName, n:p|my::sz($p.addrId)])     (INLINED -> folded)
[PLAN] SELECT t0.FIRST_NAME AS nm, 1 AS n
[EXEC-ROW] String(John) | Integer(1) |     <-- WRONG
[EXEC-ROW] String(Jane) | Integer(1) |
```
Wrapping `->size()` in a trivial identity-ish user function changes the answer from 0 to 1.
Call site: `UserCallInliner.java:322` (`NormalizeFolds.foldInlined`).

---

### [SILENT FALLBACK] 6. A lambda parameter's declared type/multiplicity annotation that CONTRADICTS the inferred one is silently DISCARDED

**Evidence.** `Typer.typeLambda` (`Typer.java:2055-2086`) takes the parameter type from the
*signature* and only lets the source annotation win in one narrow case:
```java
Type paramType = kernel.resolve(ftype.params().get(i).type(), b);
...
if (pv.type() != null && paramType instanceof Type.ClassType ct
        && "meta::pure::metamodel::type::Any".equals(ct.fqn())) {
    paramType = namedType(pv.type());
    if (pv.multiplicity() != null) { paramMult = Multiplicity.from(pv.multiplicity()); }
}
```
Outside the `Any` case the annotation is never read, never compared, never reported. (The `Any`
case is reachable only from `meta::pure::executionPlan::executionPlan`, which has no lowering — so
in practice the annotation is *always* ignored.)

**Repro / actual output:**
```
### model::Person.all()->filter({p:String[1]| $p.age > 30})->project(~[a:p|$p.firstName])
[G] type=Relation<(a:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS a ...
[EXEC-ROW] String(Bob) |

### model::Person.all()->filter({p:model::Address[1]| $p.age > 30})->project(~[a:p|$p.firstName])
[G] type=Relation<(a:String[1])> mult=[1]
[EXEC-ROW] String(Bob) |
```
`$p.age` is resolved against `model::Person` even though the user annotated `p` as `String[1]` /
`model::Address[1]` — neither of which has an `age` property. Real Pure rejects a lambda-parameter
type that does not match the expected function type. The repo's own rule is "NO SILENT
DEFAULTING"; this is a silently discarded user declaration.

*(Well-behaved neighbours, for contrast: wrong arity → clean
`lambda has 2 parameter(s) but the function type expects 1`; wrong body type → clean
`expected Boolean, got Integer`; lambda in a non-lambda slot → clean `no overload of 'plus' matches
2 argument(s) of these shapes`.)*

---

### [SILENT FALLBACK — WRONG RESULT] 7. `collection::first(set, count)` silently drops its `count` argument

**Evidence.** `lowering/Scalars.java:1407-1411` registers ONE rule for **every** overload key at
the name `first`, and that rule ignores `args[1]`:
```java
for (String f : Pure.nativeKeysAt("first")) {
    RULES.put(f, (n, args) -> isToOne(n.args().get(0)) ? args.get(0)
            : new SqlExpr.Call(SqlFn.LIST_GET, List.of(args.get(0), new SqlExpr.IntLit(1))));
}
```
`Pure.nativeKeysAt("first")` covers `collection::first/1`, `collection::first/2`
(`first<T>(set:T[*], count:Integer[1]):T[*]`) and `relation::first/3`. `Typer.applyGeneric` resolves
the 2-arg overload correctly and emits a 2-arg `TypedNativeCall`; the lowering throws the count away.

**Repro / actual output:**
```
### [1,2,3]->first(2)
[G] type=Integer mult=[*]
[PLAN] SELECT UNNEST(list_filter([list_extract([1, 2, 3], 1)], x -> x IS NOT NULL)) AS value
[EXEC-ROW] Integer(1) |                      <-- 1 element, expected [1,2]

### [1,2,3]->first(3)  -> Integer(1)         ### [1,2,3]->first(0) -> Integer(1)
### ['a','b','c']->first(2) -> String(a)
### [1,2,3]->take(2)  -> Integer(1), Integer(2)     <-- take is correct, first is not
```
No error at any phase. The declared type `Integer[*]` is not violated, but the *value* is silently
truncated — a wrong answer for a plain library call.

Related, same name, plausible input:
```
### model::Person.all()->project(~[a:p|$p.age])->select(~[a])->first(2)
[G] type=Relation<(a:Integer[1])> mult=[*]
[PLAN-ERROR] NotImplementedException: lowering not yet implemented for TypedNativeCall
             ('meta::pure::functions::collection::first' in relation position)
```

---

### [UNSOUND + INCONSISTENCY + ICE] 8. `ProjectChecker.clampTdsCells` re-stamps a many-valued colspec body as `[0..1]`; `extend` does not — and both die with an internal `IllegalStateException`

**Evidence.** `ProjectChecker.java:67,76-94`:
```java
return new TypedProject(a.args().get(0), cols, clampTdsCells(a.out()));
...
for (Type.Column c : rt.columns()) {
    if (c.multiplicity().isMany()) {
        cols.add(new Type.Column(c.name(), c.type(), Multiplicity.Bounded.ZERO_ONE));
```
The justification in the javadoc ("a `[*]`-valued projection column EXPLODES into one row per
value") holds for a to-many *navigation* (SQL join row multiplication) but not for a body that is
a genuine collection value. `ExtendChecker` performs no such clamp
(`typeFuncColSpec` keeps the lambda result multiplicity, `Typer.java:2218`).

**Repro / actual output:**
```
### model::Person.all()->project(~[a:p|[1,2]])
[G] type=Relation<(a:Integer[0..1])> mult=[1]                <-- claims AT MOST ONE
[PLAN] SELECT [1, 2] AS a
[EXEC-ERROR] java.lang.IllegalStateException: a many-valued cell reached a scalar TDS slot ('a')
             — the lowering must explode scalar streams in SQL (E2)

### model::Person.all()->project(~[nm:p|$p.firstName])->extend(~[b:r|[1,2]])
[G] type=Relation<(nm:String[1], b:Integer[2])> mult=[1]      <-- honest [2], different from project
[PLAN] SELECT t0.FIRST_NAME AS nm, [1, 2] AS b
[EXEC-ERROR] java.lang.IllegalStateException: a many-valued cell reached a scalar TDS slot ('b') …
```
Two constructs, identical colspec body, two different column multiplicities — one of which
(`[0..1]`) is provably false. Neither produces a clean compile error.

Control (the case the clamp is actually for) is sound:
```
### model::Person.all()->project(~[nm:p|$p.firstName, a:p|$p.addresses.city])
[G] Relation<(nm:String[1], a:String[0..1])>
[PLAN] SELECT t0.FIRST_NAME AS nm, t1.CITY AS a FROM T_PERSON t0 LEFT OUTER JOIN T_ADDRESS t1 ON …
[EXEC-ROW] John|New York / John|Boston / Jane|Chicago / Bob|Detroit     (row multiplication: correct)
```

---

### [INCONSISTENCY / FORWARD-BACKWARD ASYMMETRY] 9. One declared Pure type `Integer[1]` decodes into THREE different Java classes depending on the construct

All four queries below declare exactly `Integer[1]` for the second column, from the same
`AGE_VAL INTEGER` source:
```
### project(~[…, ag:p|$p.age])                        [EXEC-COL] ag : Integer  -> Integer(30)
### groupBy(~[nm], ~[s:x|$x.ag:y|$y->sum()])          [EXEC-COL] s  : Integer  -> BigInteger(30)
### groupBy(~[nm], ~[c:x|$x.ag:y|$y->count()])        [EXEC-COL] c  : Integer  -> Long(1)
### groupBy(~[nm], ~[m:x|$x.ag:y|$y->max()])          [EXEC-COL] m  : Integer  -> Integer(28)
### extend(over(~nm), ~[rk:{p,w,r|$r.ag}:y|$y->sum()]) [EXEC-COL] rk: Integer  -> BigInteger(30)
### aggregate(~[s:x|$x.ag:y|$y->sum()])               [EXEC-COL] s  : Integer  -> BigInteger(103)
```
A consumer that pattern-matches on the decoded value's class (or calls `intValue()` / casts) sees
`java.lang.Integer`, `java.lang.Long` and `java.math.BigInteger` under one static type. The
`BigInteger` arm additionally admits values *outside* the 64-bit range under a type the rest of the
pipeline treats as 64-bit (`9223372036854775807 + 1` → `[G] Integer[1]` →
`BigInteger(9223372036854775808)`), i.e. the static type is not merely differently-carried but
too small. (A05 finding 2 reports the literal-range half; this is the *aggregate/decoder* half.)

---

### [CRASH — no clean compile error] 10. An empty colspec name type-checks and reaches the database as `AS ""`

```
### model::Person.all()->project(~['':p|$p.firstName])
[G] type=Relation<(:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS "" 
[EXEC-ERROR] java.sql.SQLException: Parser Error: zero-length delimited identifier at or near """"
```
`Typer.typedColSpecArray` / `typeFuncColSpec` validate *uniqueness* of colspec names
(`Typer.java:2213`, `3145`) but never non-emptiness, and `Type.RelationType`'s invariant
only checks duplicates (`Type.java:520-544`, throw at `:533`). The failure is a raw JDBC `SQLException` at phase K.

---

### [LOW / LATENT] 11. `__|__` — the reserved pivot separator — is accepted as an ordinary user column name

```
### model::Person.all()->project(~['a__|__b':p|$p.firstName])->select(~['a__|__b'])
[G] type=Relation<(a__|__b:String[1])> mult=[1]
[PLAN] SELECT t0.FIRST_NAME AS "a__|__b"
[EXEC-COL] a__|__b : String [STRING] mult=[1]      (executes fine)
```
`Type.RelationType.PIVOT_SEPARATOR = "__|__"` (`Type.java:427`) and the documented column-matching
rule treats `<value>__|__<template>` names as pivot-generated and resolves their type from the
aggregate template, calling a suffixed name that matches no template "a naming-contract bug — loud".
Nothing rejects (or escapes) the separator when a *user* writes it into a colspec, so a pivoted
relation carrying a user column with that spelling will be mis-classified at the egress. Could not
be executed end-to-end because `pivot` has no lowering (see below).

---

### [INCONSISTENCY + SILENT RETYPE] 12. `FlattenChecker`'s two arms disagree on the output column type, and the relation arm retypes ANY column to `Variant` with no semi-structured check

**Evidence.** `FlattenChecker.java:41-51` (collection arm) builds
`Relation<(<name>:<elementType>[0..1])>` and — by its own comment — bypasses the registered
signature entirely ("KNOWN GAP … this arm computes the schema directly"). `FlattenChecker.java:69-77`
(relation arm) replaces the named column's type with `Variant` **whatever it was**; the only check
is that the name exists.
```
### model::Person.all()->project(~[nm:…, ag:p|$p.age])->flatten(~ag)
[G] type=Relation<(nm:String[1], ag:meta::pure::metamodel::variant::Variant[1])>   <-- Integer became Variant
[PLAN-ERROR] NotImplementedException: class query under TypedFlatten is not resolvable yet (H2 vocabulary)

### [1,2,3]->flatten(~v)
[G] type=Relation<(v:Integer[0..1])> mult=[1]                                       <-- stays Integer
[EXEC-COL] v : Integer [INTEGER] mult=[0..1]   [EXEC-ROW] Integer(1) / Integer(2) / Integer(3)
```

---

### [UNSOUND] 13. `Typer.decimalType` computes precision from the scale ALONE, so a literal whose total digit count exceeds 38 gets a type that cannot hold it, and decodes as `Double`

**Evidence.** `Typer.java:3178-3185`:
```java
private static Type decimalType(BigDecimal value) {
    int scale = Math.max(0, value.scale());
    if (scale > Type.PrecisionDecimal.MAX_PRECISION) { throw …; }
    return new Type.PrecisionDecimal(Type.PrecisionDecimal.MAX_PRECISION, scale);
}
```
Integer digits are never counted. `PrecisionDecimal(p,s)` is SQL `DECIMAL(p,s)` — *total* p digits.

**Repro / actual output** (new angle vs A05 #1: this one has **scale 1**, so no rounding path is
involved at all):
```
### 12345678901234567890123456789012345678.5d          (38 integer digits + 1 decimal = 39 digits)
[G] typeRepr=PrecisionDecimal[precision=38, scale=1]
[PLAN] SELECT 12345678901234567890123456789012345678.5 AS value
[EXEC-COL] value : Decimal(38,1) [PrecisionDecimal[precision=38, scale=1]]
[EXEC-ROW] Double(1.2345678901234568E37)               <-- java.lang.Double under a Decimal(38,1) column

### 123456.78901234567890123456789012345678901234567
[G] typeRepr=PrecisionDecimal[precision=38, scale=38]   <-- 6 integer digits under scale==precision
[EXEC-ROW] Double(123456.78901234567)                   <-- ~22 significant digits lost
```
Control: `0.5d` → `Decimal(38,1)` → `BigDecimal(0.5)`. The carrier flips silently.
(Confirms/extends CONFIRMED.md V4 and A05 findings 1/3; the `scale=1` case above shows the defect is
in the *precision* computation, not only in over-scale rounding.)

---

### [UNSOUND — declared vs emitted scale] 14. `refineDecimalCarrier` stamps `Decimal(38,18)` on `parseDecimal`/`toDecimal` while the lowering casts to a different scale

**Evidence.** `Typer.java:1814-1822` + `DECIMAL_CARRIER_PRODUCERS` (`Typer.java:1824-1826`)
unconditionally rewrite the declared bare `Decimal` return of those two functions to
`PrecisionDecimal(38,18)`.
```
### '1.5'->parseDecimal()
[G] typeRepr=PrecisionDecimal[precision=38, scale=18]
[PLAN] SELECT CAST('1.5' AS DECIMAL(38, 1)) AS value          <-- scale 1
[EXEC-COL] value : Decimal(38,18)      [EXEC-ROW] BigDecimal(1.5)     (actual BigDecimal scale = 1)

### model::Person.all()->project(~[a:p|$p.age->toDecimal()])
[G] Relation<(a:Decimal(38,18)[1])>
[PLAN] SELECT CAST(t0.AGE_VAL AS DECIMAL(38, 0)) AS a         <-- scale 0
[EXEC-COL] a : Decimal(38,18)          [EXEC-ROW] BigDecimal(30)      (actual BigDecimal scale = 0)
```
The static type claims a fixed scale of 18; the value delivered has scale 1 / 0. For a fixed-point
type, scale IS part of the type.

---

### [ICE / already-known-family] 15. Two more internal exceptions escaping the Typer on plausible TDS-surface input

```
### model::Person.all()->project(~[nm:…, ag:…])->at(0).values->at(99)
[G-ERROR] java.lang.IllegalStateException: The system is trying to get an element at offset 99
          where the collection is of size 2                       (Typer.java:1084-1089)

### model::Person.all()->project(~[nm:…, ag:…])->at(0).values->at(1)
[G] type=Integer mult=[1]
[PLAN-ERROR] java.lang.IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED …
             ONE-STAMP/LIST-SHAPE mult=[1..1] sql=ScalarSubquery
             node=TypedNativeCall callee=meta::legend::lite::trustOne arg0=TypedPropertyAccess(ag)
```
The first is A05 finding 8's family (kept here because the second one — the `trustOne` wrap the
Typer itself inserts at `Typer.java:1090-1092` producing a `[1..1]` stamp over list-shaped SQL — is
the same call site and is reachable both this way and via `collection::first/2`).

---

### [DOC-LIE] 16. `NormalizeFolds`'s javadoc claims it runs "at TYPING (the emit seam)"; it never does

`NormalizeFolds.java:17-18`: *"SPEC-SOUND constant folds over typed nodes — ONE owner, applied at
TYPING (the emit seam) and at INLINING"*. Repo-wide grep for `NormalizeFolds.` finds exactly one
call site: `UserCallInliner.java:322` (`deepFoldInlined`). Nothing in `Typer`/`SpecCompiler` calls it.
Ranked low, but it matters for finding 5: the fold is *only* reachable through user-call inlining,
which is exactly why the two spellings of `->size()` disagree.

---

### [INFO LOSS / LOW] 17. Decimal arithmetic erases precision & scale; decimal division silently becomes `Float`

```
### 1.5d          -> [G] PrecisionDecimal[precision=38, scale=1]   -> BigDecimal(1.5)
### 1.5d + 1.5d   -> [G] DECIMAL   (bare, no p/s)                  -> BigDecimal(3.0)
### 1.5d * 1.5d   -> [G] DECIMAL                                   -> BigDecimal(2.25)
### 1.5d - 0.25d  -> [G] DECIMAL                                   -> BigDecimal(1.25)
### 1.5d / 3.0d   -> [G] FLOAT     -> SELECT ((1.0 * 1.5) / 3.0)   -> Double(0.5)
```
One arithmetic step turns `Decimal(38,1)` into an unparameterised `Decimal`; one division turns it
into a `Float`. (Corroborates CONFIRMED.md V13 — the `PrecisionDecimal` arithmetic algebra is never
consulted.)

---

### [INCONSISTENCY] 18. `rel.columns` and `rel->columns()` are two different types; only `map` over the property spelling is patched up by a fold hook

**Evidence.** The *property* read `<rel>.columns` is answered by `Typer.accessProperty` →
`columnsMeta(rt2, false)` (`Typer.java:2576-2589`), which builds a collection of **`TypedCString`
column NAMES**. The *function* call `columns(<rel>)` is answered by `ColumnsChecker.check`
(`ColumnsChecker.java:37-68`), which builds a collection of **`TypedNewInstance` `Column` objects**
carrying a `name` property. Real Pure's `TabularDataSet.columns` is `TDSColumn[*]` (objects), so the
property arm is the divergent one — and `Typer.tdsSchemaDesugars` papers over it for exactly one
operator by intercepting `map` over a `.columns` receiver and constant-folding it
(`Typer.java:620-629`).

**Repro / actual output** (`$R = model::Person.all()->project(~[a:p|$p.age, b:p|$p.firstName])`):
```
### $R.columns
[G] type=String mult=[2]                     -> SELECT UNNEST(list_filter(['a','b'], …))  -> String(a), String(b)

### $R->columns()
[G] type=meta::pure::metamodel::relation::Column mult=[2]
    -> SELECT UNNEST(list_filter([{'name': 'a'}, {'name': 'b'}], …))
    -> LinkedHashMap({name=a}), LinkedHashMap({name=b})

### $R.columns->map(c|$c.name)                <-- WORKS (StaticFold hook fires)
[PLAN] SELECT UNNEST(list_filter(['a', 'b'], x -> x IS NOT NULL)) AS value   -> String(a), String(b)

### $R.columns->filter(c|$c.name == 'a')      <-- SAME receiver, different operator
[G-ERROR] TypeInferenceException: cannot access 'name' on String

### $R->columns()->filter(c|$c.name == 'a')->size()   -> Long(1)     (the object spelling is fine)
```
`->map(c|$c.name)` and `->filter(c|$c.name == …)` over the *same* value disagree about whether the
elements have a `name` property. The fold hook is what makes the first one appear to work.

---

## VERIFIED SOUND (checked, found correct — coverage evidence)

**Dispatch table.**
* All 33 `*Checker.java` classes are reachable from `Typer.applyCore`; no dead checker, no
  fall-through arm (the `switch` is exhaustive over the sealed `CoreFn` enum, so a new construct
  is a compile error).
* All 721 declared native overloads were called with signature-derived arguments and type-checked:
  **zero internal exceptions at phase G** (523 OK, 198 clean `TypeInferenceException`). The generic
  application rule does not crash on any registered signature.
* `CoreFn.of` correctly refuses bare `tds`/`typeAsDeclared`/`castAsDeclared`/`legacyNavigate`
  (INTERNAL_DESUGAR gate, `CoreFn.java:167-170`) and correctly refuses to hijack a *user* function
  living under `meta::pure::*` (`CoreFn.java:176-181` requires an actual catalog native).

**Lambda typing.** wrong arity (relation + collection sources) → clean
`lambda has N parameter(s) but the function type expects M`; wrong body type → clean
`expected Boolean, got Integer`; lambda in a value slot → clean no-overload error; bare lambda
outside a call → clean `a bare lambda has no type outside a call position`; zero-arg lambda `{|1}`
types and executes (`Integer(1)`); a *nested* lambda over an association
(`filter(p|$p.addresses->filter(a|$a.city == $p.firstName)->isNotEmpty())`) types and lowers
correctly; an outer `let` captured by a lambda (`|let t = 30; …filter(p|$p.age > $t)…`) types and
executes correctly (`Bob`); a fully-annotated lambda bound to a `let` and `eval`-ed
(`|let f = {x:Integer[1]|$x + 1}; $f->eval(1);`) → `Integer(2)`.

**Colspec typing.**
* `~[a, b]` → `ColSpecArray<(a:?[1], b:?[1])>[1]` (`Typer.java:3129-3155`, `?` = `InferenceKernel.UNKNOWN_COLUMN_TYPE`);
  `~a` → `ColSpec<(a:?[1])>[1]`; `~[]` → `ColSpecArray<()>[1]` (legal, for `groupBy(~[], …)`).
* `~[a:x|$x.f]` → `FuncColSpecArray<{T[1]->…}, (a:<body type>[<body mult>])>[1]` (`Typer.java:2194-2240` (method starts `:2194`)).
* `~[a:x|$x.f:y|$y->sum()]` → `AggColSpecArray<mapF, reduceF, (a:<reduce body type>)>[1]`, with
  K/V solved in a **per-column copy** of the bindings (`Typer.java:2242-2280`).
* A bare mapped/aggregate colspec outside a call → clean
  `~a: mapped/aggregate column specifications need an enclosing call to type against`.
* Duplicate names are **pre-checked with friendly errors**, never reaching `RelationType`'s
  `IllegalArgumentException`: `select(~[a,a])` and `extend(~[c:…,c:…])` →
  `SchemaInvariantException: duplicate column 'c' in ~[…]` (`Typer.java:2213`, `2259`,
  `3145`); `extend(~[a:…])` onto an existing `a` → `the column 'a' already exists in the
  relation (…)`; `rename(~nm, ~ag)` onto an existing name → same; `join` of two relations sharing
  column names → same. I could not reach `Type.RelationType`'s raw `IllegalArgumentException`
  through any colspec path.
* Unknown column names are clean: `select(~[zzz])` →
  `in call to 'meta::pure::functions::relation::select', argument 2: unknown column 'zzz' in (…)`;
  `extend(~[c:r|$r.zzz])` → `relation has no column 'zzz'`.
* Quoted names work end-to-end: `~['a b':p|$p.firstName]` → `Relation<(a b:String[1])>` →
  `SELECT … AS "a b"` → executes; `select(~['a b'])` matches it.
* `select(~[])` / `distinct(~[])` are explicitly refused (`SelectChecker.java:23-25`,
  `DistinctChecker.java:42-44`).

**Relation construct result types vs execution** — `[G]` and `[EXEC-COL]/[EXEC-ROW]` matched
exactly (columns, order, names, Pure types, multiplicities, runtime Java classes) for:
`project`, `select`, `extend`, `join` (INNER, `T+V` union, 4 columns in left-then-right order),
`sort(descending)` (rows Bob45/John30/Jane28), `limit(2)` (2 rows), `drop(1)` (2 rows),
`distinct()`, `distinct(~[nm])`, `rename(~nm,~firstNm)` (name changes, type preserved, position
preserved), `filter`, `concatenate` (6 rows), `slice(1,3)` (2 rows), `from(runtime)` (identity),
`extend(over(~nm), ~[…])` (window, correct partitioned SQL), `aggregate` (single row), and
collection `fold({e,acc|$e+$acc},0)` → `Integer(6)`. The only column-type discrepancy found in
that sweep is the `Integer`→`BigInteger`/`Long` carrier flip (finding 9).
`concatenate` correctly rejects mismatched schemas (different names AND different column types).

**Other.** `Env` is a straightforward immutable map with correct shadowing semantics (`with`
overwrites, parents untouched) — no defect found. `SpecCompiler` threads `let` bindings forward in
both `check` (`SpecCompiler.java:331-333`) and `typeQueryBody` (`:363-365`) and re-wraps
`TypeInferenceException` with the enclosing function name — correct, no fallback. `Frames.bound`
has an explicit "NO fallback: an unrecognized bound is a loud error, never UNBOUNDED"
(`Frames.java:96-98`) and I found no path around it. `StaticFold` has **no** `divide` arm, so the
div-by-zero fold the brief asked about is *unreachable* — `1/0` inside the fold channel stays a
runtime `list_transform(…, c -> ((1.0*1)/0))` and both folded and unfolded forms return
`Double(Infinity)` (agreement). `StaticFold` has **no** `CDecimal` arm either
(`StaticFold.java:157-179`), so no decimal value can be constant-folded — no decimal fold
divergence exists. String concat folding agrees with the DB (`'a'+'b'` → `'ab'` both ways);
`makeString`/`toString` of a `Double` agree (`'1.0'` both ways); `removeDuplicates` on `[1,1.0]`
did not fold (returned to the runtime) and agreed (`2` both ways); `2 == 2` folds `TRUE` and agrees.
The `if(static-cond)` fold correctly drops the dead branch and, as designed, also hides a genuine
type error in it (`if(true,|1,|'x'+1)` compiles when folded, fails cleanly when not) — chartered
behaviour, listed for completeness rather than as a defect.

---

## NOT COVERED
* `asOfJoin`, `pivot`, `flatten` (relation arm) and `write` type at phase G but have **no lowering**
  (`NotImplementedException: class query under TypedAsOfJoin / TypedPivot / TypedFlatten / TypedWrite
  is not resolvable yet (H2 vocabulary)`), so their *runtime* column shape could not be compared to
  the `[G]` type. `pivot`'s `[G]` type is `Relation<()>` with the aggregates carried as
  `dynamicColumns=[Column[name=s, type=INTEGER, …]]` — plausible per its data-dependent design, but
  unverifiable end-to-end here.
* `graphFetch` / `graphFetchChecked` / `serialize` / `navigate` / `legacyNavigate` / `match` /
  `tds` / `sourceUrl` / `tableReference` / `getAllVersions*` were only exercised through the
  721-overload sweep (typing + planning), not with hand-written semantic repros — the brief scoped
  me to the relation/TDS half.
* The 87 "no scalar lowering registered" natives were counted and sampled, not individually
  repro'd through `probe.sh` (they are one failure mode with one message shape; the sample above is
  representative). Their *lowering* is phase-I territory.
* `UserCallInliner.java` and `InferenceKernel.java` were read only where a finding led into them
  (they belong to other auditors).
* `ExecuteChainAssembly`, `ResultEnvelopeSplice`, `CatalogGrids`, `VerdictQueries`, `GraphFetchChecker`
  internals — out of scope for this slug.
