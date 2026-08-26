# A13 — Projection / selection family of Phase-G checkers

Scope read in full: `compiler/spec/ProjectChecker.java` (280), `SelectChecker.java` (28),
`ColumnsChecker.java` (69), `RenameChecker.java` (88), `DistinctChecker.java` (64),
`ExtendChecker.java` (163), `OverChecker.java` (67), `Frames.java` (149),
`typed/TypedExtendWindow.java` (60), `typed/WindowFrame.java` (37), `SlicingChecker.java` (43),
`SortChecker.java` (218), `FilterChecker.java` (21), `ConcatenateChecker.java` (49),
`FlattenChecker.java` (79), plus the shared `Args.java`, and the collaborators the rules
delegate to (`InferenceKernel.unifyMultResult`/`commonSupertype`/`unionRows`,
`Type.RelationType`, `lowering/Sorts.java`, `lowering/Fold.sortNulls`,
`lowering/ConstBounds.java`, `exec/Executor.java` egress).

Harness: `/tmp/a13/Batch.java` (model+DDL loaded once, one compile/plan/execute block per query),
run through `/home/user/probe/jrun.sh`. Fixture `/home/user/probe/fx`, plus
`/tmp/a13/ddl_null.sql` (adds a person with no address so a `[0..1]` column really is null),
`/tmp/a13/model_sqlite.pure`, `/tmp/a13/model_h2.pure` (same model, SQLite/H2 connection),
`/tmp/a13/model_fn.pure` (adds a 2-arg user function). All output below is pasted verbatim.

---

## TYPING RULES AS IMPLEMENTED

Notation: `R<T>` = `Relation<schema>`; `T+Z` / `T-Z` / `Z⊆T` are the kernel's schema algebra.

| checker | rule as implemented |
|---|---|
| `ProjectChecker` | `project(R<T>[1], ~[n₁:{T[1]->Any[0..1]}, …]) : R<(n₁:τ₁[m₁'], …)>[1]` where `τᵢ`/`mᵢ` come from the checked lambda bodies and **`mᵢ' = [0..1]` whenever `mᵢ` is many** (`clampTdsCells`, ProjectChecker.java:76-95). Legacy `project([λ],[names])`, `project([λ])`, `col(fn,'n'[,doc])`, `pathWithAlias` and bare `~prop` all desugar to the modern colspec form first (`normalizeLegacyForms`/`legacyToModern`/`withMappedColumns`). **No arity guard: zero columns is accepted.** |
| `SelectChecker` | `select(R<T>[1], ~[X])`, `X⊆T` → `R<X>[1]`, column order = the *selection's* order. `X = ∅` rejected (SelectChecker.java:23-25). |
| `ColumnsChecker` | `columns(R<T>[1]) : Column[n]` where `n = |T.columns|` — a *static fold* into a literal `TypedCollection` of `Column` instances (exact-size multiplicity, narrower than the registered `[*]`). Late-bound schema → `NotImplementedException`. |
| `RenameChecker` | `rename(R<T>[1], ~old:K, ~new) : R<T-Z+V>[1]` with `K` (type **and** multiplicity) carried old→new, then re-ordered so the renamed column keeps the **source position** (`positionPreserving`, RenameChecker.java:63-87). Array form desugars to a left-to-right chain of scalar renames. |
| `DistinctChecker` | `distinct(R<T>[1]) : R<T>[1]`; `distinct(R<T>[1], ~[X]) : R<X>[1]`, `X⊆T`. `~col` → `~[col]`. Non-relation source falls through to the collection overload. `X = ∅` rejected. |
| `ExtendChecker` | three forms, each checked generically: scalar `extend(R<T>, FuncColSpec<{T[1]->Any[0..1]},Z>) : R<T+Z>`; aggregate `extend(R<T>, AggColSpec<…,R>) : R<T+R>`; windowed `extend(R<T>, over(…), cols) : R<T+Z>`. Reducer presence (`function2`) picks agg vs scalar. **No `clampTdsCells` equivalent** — an extend column keeps whatever multiplicity its lambda has. |
| `OverChecker` | `over(~part…, asc/desc(~key)…[, frame]) : _Window<T>[1]`; `T` binds as an unsolved fragment and the enclosing `extend`'s `_Window<T>` validates it against the real source row. |
| `Frames` | classify `rows(a,b)` → `ROWS`, `_range(…)` → `RANGE`, DurationUnit present → interval `RANGE`. Bound sign decides: `n<0` PRECEDING, `n>0` FOLLOWING, `n==0` CURRENT ROW, `unbounded()` UNBOUNDED. Bound legality (from ≤ to) is deferred to lowering. |
| `SlicingChecker` | all three schema-preserving: `limit/take(R<T>,Integer) : R<T>`, `drop(R<T>,Integer) : R<T>`, `slice(R<T>,s,e) : R<T>` over `[s,e)`. `limit(rel, [])` = identity. **No bound validation at all.** |
| `SortChecker` | `sort(R<T>[1], SortInfo<X⊆T>[*]) : R<T>[1]`. Bare `~col` → `asc(~col)`; legacy `'COL'`, `['A','B']`, `('COL',SortDirection.X)` desugar. A flag `pureNullOrder = !legacyStringShape(af)` rides the node and decides null placement downstream. |
| `FilterChecker` | `filter(R<T>[1], {T[1]->Boolean[1]}[1]) : R<T>[1]` — purely generic; no local validation. |
| `ConcatenateChecker` | `concatenate(R<T>[1], R<T>[1]) : R<T>[1]` — the **shared `T`** is the entire schema check. Non-relation result → collection overload. A relation-typed `TypedCollection` RHS folds into a left-assoc chain. |
| `FlattenChecker` | relation arm: `flatten(R<T>[1], ~c) : R<T[c ↦ Variant]>[1]` — the named column is widened to `Variant` **keeping its original multiplicity**; only the column *name*'s existence is validated. Collection arm: `flatten(τ[*], ~c) : R<(c:τ[0..1])>[1]`. |

---

# FINDINGS

### [UNSOUND] `concatenate` over a collection literal silently re-orders columns and lets a String land in a column declared Integer

**Evidence.** `ConcatenateChecker.java:31-46` folds an n-ary
`a->concatenate([b, c])` into a left-associative `TypedConcatenate` chain **without
re-checking any element's schema against the accumulator** — it only guards that every
element is relation-typed:

```java
if (a.args().get(1) instanceof TypedCollection tc
        && !tc.elements().isEmpty()
        && tc.elements().stream().allMatch(e -> Type.isRelation(e.info().type()))) {
    ...
    for (TypedSpec e : tc.elements()) { acc = new TypedConcatenate(acc, e, one); }
```

The only schema check is the shared `T` on the *collection as a whole*, and the collection
literal's own element type is computed by the **join**, not by unification —
`InferenceKernel.java:1249-1251`:

```java
if (a instanceof Type.GenericType ga && b instanceof Type.GenericType gb
        && ga.rawFqn().equals(gb.rawFqn()) ... ) {
    return new Type.GenericType(ga.rawFqn(), List.of(unionRows(ra, rb)));
}
```
`unionRows` (InferenceKernel.java:1363-1372) is *"the union of two row-structs, keeping
first-seen order; a repeated name must agree"* — it is **order-insensitive**, so
`(a,b)` ∪ `(b,a)` = `(a,b)` with no complaint, while the 2-argument `concatenate` path
rejects the very same pair.

**Repro / actual output** (`/tmp/a13/q_cat4.txt`):

```
################ Q: |#TDS \n a, b \n 1, 2 \n #->concatenate([#TDS \n b, a \n 30, 40 \n #])
  [G-ERROR] com.legend.compiler.spec.TypeInferenceException: in call to 'meta::pure::functions::collection::concatenate', argument 2: column mismatch: type variable T bound to relation [a, b] cannot also bind relation [b, a]
################ Q: |#TDS \n a, b \n 1, 2 \n #->concatenate([#TDS \n a, b \n 10, 20 \n #, #TDS \n b, a \n 30, 40 \n #])
  [G] TypedConcatenate :: Relation<(a:Integer[1], b:Integer[1])>[1]
  [SQL] SELECT * FROM (VALUES (1, 2)) AS _tds0(a, b) UNION ALL SELECT * FROM (VALUES (10, 20)) AS _tds1(a, b) UNION ALL SELECT * FROM (VALUES (30, 40)) AS _tds2(b, a)
  [COLS] a:Integer[1]  b:Integer[1]
  [ROW] Integer(1) | Integer(2) |
  [ROW] Integer(10) | Integer(20) |
  [ROW] Integer(30) | Integer(40) |          <-- source row was b=30, a=40: SWAPPED
```

Adding one extra element to the list turns a compile error into a silently wrong answer.
With mixed column types it becomes a hard type violation
(`/home/user/probe/probe.sh` on `/tmp/a13/qcat_unsound.pure`):

```
[G] type=Relation<(a:Integer[1], b:String[1])> mult=[1]
[EXEC-COL] a : Integer [INTEGER] mult=[1]
[EXEC-COL] b : String [STRING] mult=[1]
[EXEC-ROW] String(1) | String(x) |
[EXEC-ROW] String(2) | String(y) |
[EXEC-ROW] String(z) | String(3) |
```

Column `a` is declared `Integer[1]`; every returned value is a `java.lang.String`, and the
third is `"z"` — not even an integer. DuckDB widened the whole UNION ALL column to VARCHAR
because leg 3 supplied a VARCHAR in position 1.

**Why it matters.** Top prize category: the static type is `Integer[1]`, the runtime value is
`String("z")`. Any downstream consumer decoding by declared type is broken, and in the
same-type case the data is silently transposed with no error anywhere.

---

### [UNSOUND] A schema-SUBSET element inside the same collection literal is silently widened, then renders invalid SQL

Same mechanism (`unionRows` merges by name), different symptom: an element with *fewer*
columns is widened to the union and passes the `T`-equality check.

```
################ Q: |#TDS \n id, name \n 1, Alice \n #->concatenate(#TDS \n id \n 2 \n #)
  [G-ERROR] com.legend.compiler.spec.TypeInferenceException: ... T bound to relation [id, name] cannot also bind relation [id]

################ Q: |#TDS \n id, name \n 1, Alice \n #->concatenate([#TDS \n id, name \n 2, Bob \n #, #TDS \n id \n 3 \n #])
  [G] TypedConcatenate :: Relation<(id:Integer[1], name:String[1])>[1]
  [SQL] SELECT * FROM (VALUES (1, 'Alice')) AS _tds0(id, name) UNION ALL SELECT * FROM (VALUES (2, 'Bob')) AS _tds1(id, name) UNION ALL SELECT * FROM (VALUES (3)) AS _tds2(id)
  [EXEC-ERROR] java.sql.SQLException: Binder Error: Set operations can only apply to expressions with the same number of result columns
```

Compile-clean, plan-clean, then a raw JDBC `SQLException` — the "never bad SQL" invariant
is broken by a Phase-G hole.

---

### [UNSOUND] `project`/`extend` accept a ROW-typed column; the runtime returns a differently-named, differently-typed column

`~[b:r|$r]` (the whole row as a cell) types as a nested `RelationType` column. The generic
`FuncColSpec<{T[1]->Any[0..1]},Z>` admits it (a row struct is `Any`) and neither checker
rejects a non-scalar cell type.

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[b:r|$r])
  [G] TypedExtend :: Relation<(a:Integer[1], b:(a:Integer[1])[1])>[1]
  [SQL] SELECT t0.AGE_VAL AS a, t0.AGE_VAL AS b FROM T_PERSON AS t0
  [COLS] a:Integer[1]  b_a:Integer[1]
  [ROW] Integer(30) | Integer(30) |
```

Declared column `b : (a:Integer[1])[1]`; actual column `b_a : Integer[1]`. **Both the name and
the type of a declared output column are violated.** It propagates through the whole family —
`select(~[b])`, `distinct()`, `sort(~b)`, `concatenate` all keep claiming `b:(a:Integer[1])[1]`
and all return `b_a:Integer[1]`:

```
################ Q: ...->extend(~[b:r|$r])->select(~[b])
  [G] TypedSelect :: Relation<(b:(a:Integer[1])[1])>[1]
  [COLS] b_a:Integer[1]
################ Q: ...->extend(~[b:r|$r])->distinct(~[b])
  [G] TypedDistinct :: Relation<(b:(a:Integer[1])[1])>[1]
  [COLS] b_a:Integer[1]
```

---

### [CRASH/ICE] `IllegalArgumentException: duplicate column 'b_a' in relation type` escapes at execution

The same struct-column flattening collides with an ordinary user column name and trips
`Type.RelationType`'s record-constructor precondition (`Type.java:531-534`,
`throw new IllegalArgumentException("duplicate column '" + c.name() + "' in relation type")`)
at the execution boundary — an internal-invariant class, not a `LegendCompileException`:

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[b:r|$r])->extend(~[b_a:r|1])
  [G] TypedExtend :: Relation<(a:Integer[1], b:(a:Integer[1])[1], b_a:Integer[1])>[1]
  [SQL] SELECT t0.AGE_VAL AS a, t0.AGE_VAL AS b, 1 AS b_a FROM T_PERSON AS t0
  [EXEC-ERROR] java.lang.IllegalArgumentException: duplicate column 'b_a' in relation type
```

(This answers the brief's "does `RelationType`'s duplicate check throw an ICE?" — yes, on this
path. The *direct* collision spellings are all clean: `rename(~a,~b)` where `b` exists,
`extend(~[a:…])` where `a` exists, `~[a,a]`, `rename(~[a,b],~[x,x])` all raise
`SchemaInvariantException`, a `LegendCompileException` subtype.)

---

### [CRASH/ICE] `rename` over a struct-valued column: `IllegalStateException` at plan time

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[b:r|$r])->rename(~b, ~c)
  [G] TypedRename :: Relation<(a:Integer[1], c:(a:Integer[1])[1])>[1]
  [PLAN-ERROR] java.lang.IllegalStateException: rename source column 'b' cannot be resolved after isolation
  [EXEC-ERROR] java.lang.IllegalStateException: rename source column 'b' cannot be resolved after isolation
```
Site: `lowering/Lowerer.java:1783-1786`.

---

### [CRASH/ICE] A relation-valued projection column: `IllegalStateException: no SQL type for generic Relation<…>`

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[b:r|#TDS \n x \n 1 \n #])
  [G] TypedExtend :: Relation<(a:Integer[1], b:Relation<(x:Integer[1])>[1])>[1]
  [PLAN-ERROR] java.lang.IllegalStateException: no SQL type for generic Relation<(x:Integer[1])> at the lowering boundary
################ Q: model::Person.all()->project(~[a:p|$p.age])->project(~[b:r|#TDS \n x \n 1 \n #])
  [G] TypedProject :: Relation<(b:Relation<(x:Integer[1])>[1])>[1]
  [PLAN-ERROR] java.lang.IllegalStateException: no SQL type for generic Relation<(x:Integer[1])> at the lowering boundary
```
Site: `lowering/PureSql.java:156`. Neither `ProjectChecker` nor `ExtendChecker` requires a
column cell type to be a *scalar*, so a nested table type is minted into a relation schema and
walls internally three phases later.

---

### [UNSOUND + CRASH] `project(~[])` is accepted, lowers to `SELECT *`, and ICEs at the egress

`SelectChecker.java:23-25` and `DistinctChecker.java:42-44` both explicitly reject the empty
colspec array, with the same comment (*"`~[]` is legal where zero columns MEAN something
(groupBy's whole-relation aggregate); a zero-column PROJECTION is not it"*). **`ProjectChecker`
has no such guard** — it is the one checker in the family that admits it.

```
################ Q: model::Person.all()->project(~[])
  [G] TypedProject :: Relation<()>[1]
  [SQL] SELECT * FROM T_PERSON AS t0
  [EXEC-ERROR] java.lang.IllegalStateException: result has 5 columns but the typed schema has 0 — plan/schema mismatch

################ Q: |#TDS \n id, name \n 1, Alice \n 2, Bob \n #->project(~[])
  [G] TypedProject :: Relation<()>[1]
  [SQL] SELECT * FROM (VALUES (1, 'Alice'), (2, 'Bob')) AS _tds0(id, name)
  [EXEC-ERROR] java.lang.IllegalStateException: result has 2 columns but the typed schema has 0 — plan/schema mismatch

################ Q: model::Person.all()->project(~[])->limit(1)
  [G] TypedLimit :: Relation<()>[1]
  [SQL] SELECT * FROM T_PERSON AS t0 LIMIT 1
  [EXEC-ERROR] java.lang.IllegalStateException: result has 5 columns but the typed schema has 0 — plan/schema mismatch
```

Three defects in one: (a) the type claim `Relation<()>` is violated by a 5-column result;
(b) an empty projection list silently becomes `SELECT *` — a defaulting the repo forbids —
which even leaks the **unmapped** physical column `PRIMARY_ADDR_ID`; (c) the failure is an
`IllegalStateException` (`exec/Executor.java:769-770`), not a user-facing compile error.
The empty project also poisons `columns()`: `project(~[])->columns()` types `Column[0]` yet
executes to one `null` row.

---

### [UNSOUND + CRASH] `clampTdsCells` claims `[0..1]` for a cell the lowering never explodes; `extend` does not clamp at all

`ProjectChecker.java:76-95` rewrites any many-valued projection column to `[0..1]`, justified
by *"a `[*]`-valued projection column EXPLODES into one row per value"*. That is true for a
to-many **navigation** but not for a plain collection expression:

```
################ Q: model::Person.all()->project(~[b:p|[1,2,3]])
  [G] TypedProject :: Relation<(b:Integer[0..1])>[1]
  [SQL] SELECT [1, 2, 3] AS b FROM T_PERSON AS t0
  [EXEC-ERROR] java.lang.IllegalStateException: a many-valued cell reached a scalar TDS slot ('b') — the lowering must explode scalar streams in SQL (E2)
```
The claim is `[0..1]`; the emitted SQL is a DuckDB LIST literal, and `exec/Executor.java:796-800`
raises an ISE. `ExtendChecker` has no clamp at all, so the multiplicity lie is even more direct:

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[b:r|[1,2,3]])
  [G] TypedExtend :: Relation<(a:Integer[1], b:Integer[3])>[1]
  [SQL] SELECT t0.AGE_VAL AS a, [1, 2, 3] AS b FROM T_PERSON AS t0
  [EXEC-ERROR] java.lang.IllegalStateException: a many-valued cell reached a scalar TDS slot ('b') — ... (E2)
```
`b:Integer[3]` is a *cardinality-3 cell* in a relation schema — a shape the TDS contract (per
`ProjectChecker`'s own comment) says cannot exist. `flatten` then inherits it verbatim:
`…->extend(~[b:r|[1,2,3]])->flatten(~b)` types `b : Variant[3]`.

---

### [CRASH/ICE] All three `Frames.java` throw sites are raw `IllegalStateException` on ordinary user input

`Frames.java:31`, `:97`, `:111`. The file even *imports* `LegendCompileException` and
`ModelException` (lines 11-12) and uses neither. Per `error/LegendCompileException.java:14-19`
an `IllegalStateException` is *"reserved for genuine internal invariant violations (our bugs)"* —
these are user input.

```
################ Q: ...extend(over(~g, asc(~a), rows(1+1, 3)), ~[b:{p,w,r|$r.a}:y|$y->sum()])
  [G-ERROR] java.lang.IllegalStateException: window frame bound must be a numeric literal or unbounded(), got TypedNativeCall
################ Q: ...extend(over(~g, asc(~a), rows(0-1, 0-5)), ...)
  [G-ERROR] java.lang.IllegalStateException: window frame bound must be a numeric literal or unbounded(), got TypedNativeCall
################ Q: ...extend(over(~g, asc(~a), -1->rows(-5)), ...)
  [G-ERROR] java.lang.IllegalStateException: window frame bound must be a numeric literal or unbounded(), got TypedNativeCall
################ Q: {|let n = 1; ...extend(over(~g, asc(~a), $n->rows(2)), ...);}
  [G-ERROR] java.lang.IllegalStateException: window frame bound must be a numeric literal or unbounded(), got TypedVariable
################ Q: {|let f = rows(0,1); ...extend(over(~g, asc(~a), $f), ...);}
  [G-ERROR] java.lang.IllegalStateException: window frame expects rows()/range(), got TypedVariable
################ Q: ...extend(over(~g, asc(~a), _range(1+1, DurationUnit.DAYS, 2, DurationUnit.DAYS)), ...)
  [G-ERROR] java.lang.IllegalStateException: interval frame bound needs a literal Integer and a DurationUnit literal
```

Note `rows(-1, 0)` and `(-1)->rows(-5)` *do* work (the unary-minus unwrap at `Frames.java:74-79`
handles those), so whether a negative bound compiles depends on whether the parser produced
unary or binary minus — `-1->rows(-5)` fails while `(-1)->rows(-5)` succeeds. Contrast the
*legality* check, which is handled correctly and cleanly at lowering:
`1->rows(-1)` and `5->rows(2)` → `com.legend.error.ModelException: Invalid window frame boundary
- lower bound of window frame cannot be greater than the upper bound!`

---

### [UNSOUND] Relation `sort` claims pure null-largest semantics but emits no ASC null clause — SQLite inverts it, inside the same query as the window sort

`lowering/Fold.java:377-385`:

```java
static SqlSelect.SortKey.@Nullable NullOrder sortNulls(boolean ascending) {
    // PURE null ordering: null is LARGEST — ASC nulls last (DuckDB's
    // default, no clause emitted), DESC nulls FIRST ...
    return ascending ? null : SqlSelect.SortKey.NullOrder.NULLS_FIRST;
}
```
ASC deliberately emits **nothing** and relies on the backend default. The window ORDER BY does
*not* — `lowering/Lowerer.java:2177-2178` emits `NULLS_LAST` for ascending explicitly. On SQLite
the two disagree **within one rendered statement** (`/tmp/a13/q_win_null.txt`, `ddl_null.sql`
adds one person with no address):

```
=== DUCKDB ===
  [SQL] SELECT t1.CITY AS c, t0.FIRST_NAME AS n, RANK() OVER (ORDER BY t1.CITY NULLS LAST) AS rk ... ORDER BY t1.CITY
  [ROW] String(Boston) | String(John) | Long(1) |
  ...
  [ROW] null | String(Nil) | Long(5) |            <-- null LAST, rank 5
=== SQLITE ===
  [SQL] (identical SQL text)
  [ROW] null | String(Nil) | Integer(5) |         <-- null FIRST in ORDER BY, but rank 5
  [ROW] String(Boston) | String(John) | Integer(1) |
```
Plain sorts show the same split:
```
=== DUCKDB ===  ...->sort(asc(~c))   [ROW] Boston, Chicago, Detroit, New York, null
=== SQLITE ===  ...->sort(asc(~c))   [ROW] null, Boston, Chicago, Detroit, New York
=== H2     ===  ...->sort(asc(~c))   [ROW] Boston, Chicago, Detroit, New York, null
```
`sort(desc(~c))` is consistent on all three (explicit `NULLS FIRST` is emitted and honoured).
So the documented ordering contract holds on DuckDB/H2 by luck of the backend default and is
violated on SQLite. `SortChecker`'s `pureNullOrder` flag is a *claim* the renderer only
half-implements. DOC-LIE component: the comment asserts the rule the code does not enforce.

---

### [UNSOUND] `extend` over a pivot result can mint two output columns with the same name

`ExtendChecker`/the kernel's collision check reads `RelationType.columns()` only; a pivot's
data-derived columns live in `dynamicColumns()` and are invisible to it.

```
################ Q: |#TDS \n city, year, n \n NY, 2020, 1 \n NY, 2021, 2 \n #->pivot(~[year], ~[total:x|$x.n:y|$y->plus()])->extend(~['2020__|__total':r|1])
  [G] TypedExtend :: Relation<(city:String[1], 2020__|__total:Integer[1])>[1]
  [SQL] SELECT t0.*, 1 AS "2020__|__total" FROM (PIVOT ... USING SUM(n) AS "_|__total") AS t0
  [COLS] city:String[null]  '2020__|__total':Integer[null]  '2021__|__total':Integer[null]  '2020__|__total':Integer[null]
  [ROW] String(NY) | BigInteger(1) | BigInteger(2) | Integer(1) |
```
Four result columns, two of them named `'2020__|__total'` — the "column names are unique BY
CONSTRUCTION" invariant (`Type.java:526-535`) is violated in the delivered result. Any by-name
consumer now silently picks one of two.

---

### [UNSOUND] `concatenate` on a pivot result passes the schema check on the static half and emits invalid SQL

```
################ Q: |#TDS \n city, year, n \n NY, 2020, 1 \n NY, 2021, 2 \n #->pivot(~[year], ~[total:x|$x.n:y|$y->plus()])->concatenate(#TDS \n city \n LA \n #)
  [G] TypedConcatenate :: Relation<(city:String[1])>[1]
  [SQL] SELECT * FROM (PIVOT ...) AS t0 UNION ALL SELECT * FROM (VALUES ('LA')) AS _tds1(city)
  [EXEC-ERROR] java.sql.SQLException: Binder Error: Set operations can only apply to expressions with the same number of result columns
```
The pivot leg physically has three columns; `ConcatenateChecker`'s shared-`T` check sees one.

---

### [SILENT FALLBACK] A name-less *computed* project column is named after a USER FUNCTION when its trailing arguments happen to be variables

`ProjectChecker.java:196-216`. The comment says the arm exists for *milestoned property
functions* and that "shape alone admitted ANY function (`$p.x->toUpper()` named `'toUpper'`)"
was a prior audit finding. The remedy added is a **catalog-native** guard only
(`Pure.nativeKeysAt(laf.function()).isEmpty()`); user functions still slip through.

```
################ Q: {|let k = 2; model::Person.all()->project([p|model::shift($p.firstName, $k)]);}
  [G] TypedProject :: Relation<(model::shift:String[1])>[1]
  [SQL] SELECT t0.FIRST_NAME AS "model::shift" FROM T_PERSON AS t0
  [COLS] model::shift:String[1]
  [ROW] String(John) |
################ Q: {|let k = 2; model::Person.all()->project([p|$p.firstName->model::shift($k)]);}
  [G] TypedProject :: Relation<(model::shift:String[1])>[1]
################ Q: model::Person.all()->project([p|$p.firstName->model::shift(2)])
  [G-ERROR] com.legend.compiler.spec.TypeInferenceException: a name-less project column must be a property navigation ...
```
(model `/tmp/a13/model_fn.pure` declares `function model::shift(s:String[1], n:Integer[1]):String[1]`.)
Whether a column silently acquires the name `model::shift` or the query is rejected depends
only on whether the second argument is a variable or a literal. The resulting column name
contains `::`, which no other part of the system expects in a column identifier.

---

### [SILENT FALLBACK] `filter` accepts a `Boolean[0..1]` predicate against its declared `Boolean[1]` slot; NULL silently means "drop the row"

Registered signature (`builtin/Pure.java:1319`):
`filter<T>(rel:Relation<T>[1], f:Function<{T[1]->Boolean[1]}>[1]):Relation<T>[1]`.
`InferenceKernel.unifyMultResult` (InferenceKernel.java:255-273) checks **only the upper
bound** for function-result slots:

```java
boolean upperOk = fb.upper() == null || (ab.upper() != null && ab.upper() <= fb.upper());
if (!upperOk) { throw new TypeInferenceException("multiplicity " + ab.text() + " is not compatible with result " + fb.text()); }
```
So `[2]` is rejected but `[0..1]` sails through:

```
################ Q: ...->filter(r|[true,false])
  [G-ERROR] com.legend.compiler.spec.TypeInferenceException: multiplicity [2] is not compatible with result [1]
################ Q: ...->filter(r|if($r.c->isEmpty(), |[], |true))
  [G] TypedFilter :: Relation<(c:String[0..1], n:String[1])>[1]
  [G-TREE]  TypedLambda :: {(c:String[0..1], n:String[1])[1] -> Boolean[0..1]}[1]
  [SQL] ... WHERE CASE WHEN t1.CITY IS NULL THEN NULL ELSE TRUE END
  [ROW] Boston | Chicago | Detroit | New York        (the null-city row is dropped)
```
A partial predicate is admitted where a total one is declared, and the missing value is
resolved to "false" by SQL 3VL with no diagnostic anywhere.

---

### [CRASH/SILENT] `flatten` validates only that the column NAME exists — never that the column is flattenable

`FlattenChecker.java:65-77` checks `schema.columns().stream().anyMatch(c -> c.name().equals(cs.name()))`
and then unconditionally widens that column to `Variant`, keeping its multiplicity. Nothing
checks the column's *type*.

```
################ Q: |#TDS \n id, name \n 1, Alice \n #->flatten(~name)
  [G] TypedFlatten :: Relation<(id:Integer[1], name:meta::pure::metamodel::variant::Variant[1])>[1]
  [SQL] SELECT _tds0.id, UNNEST(_tds0.name) AS name FROM (VALUES (1, 'Alice')) AS _tds0(id, name)
  [EXEC-ERROR] java.sql.SQLException: Binder Error: UNNEST() can only be applied to lists, structs and NULL, not VARCHAR
################ Q: |#TDS \n id, name \n 1, Alice \n 2, Bob \n #->flatten(~id)
  [G] TypedFlatten :: Relation<(id:Variant[1], name:String[1])>[1]
  [EXEC-ERROR] java.sql.SQLException: Binder Error: UNNEST() can only be applied to lists, structs and NULL, not INTEGER
```
The collection arm has the same hole (it wraps *any* scalar as a one-column relation):
```
################ Q: 1->flatten(~v)
  [G] TypedCollectionRelation :: Relation<(v:Integer[0..1])>[1]
  [SQL] SELECT * FROM ( SELECT UNNEST(1) AS v ) AS t0
  [EXEC-ERROR] java.sql.SQLException: Binder Error: UNNEST() can only be applied to lists, structs and NULL, not INTEGER
################ Q: 'x'->flatten(~v)
  [EXEC-ERROR] java.sql.SQLException: Binder Error: UNNEST() can only be applied to lists, structs and NULL, not VARCHAR
```
Working cases: `[1,2,3]->flatten(~v)` → `Relation<(v:Integer[0..1])>`, 3 rows, correct;
`[]->flatten(~v)` → `Relation<(v:Nil[0..1])>`, 0 rows, correct.
`flatten(~zzz)` and `flatten(~[name])` / `flatten(~name:x|$x)` are clean compile errors.

---

### [CRASH] `slice(from, to)` with `from > to` renders `LIMIT -2`

`SlicingChecker` performs no bound validation; `lowering/Lowerer.java:606-611` computes
`.withLimit(ConstBounds.intOf(s.stop()) - start)` with no non-negativity check.

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->slice(2,0)
  [G] TypedSlice :: Relation<(a:Integer[1])>[1]
  [SQL] SELECT t0.AGE_VAL AS a FROM T_PERSON AS t0 LIMIT -2 OFFSET 2
  [EXEC-ERROR] java.sql.SQLException: Binder Error: LIMIT/OFFSET cannot be negative
```

---

### [CRASH] An empty column name renders a zero-length delimited identifier

No checker in the family validates the column *name*. Rendering quotes it faithfully:

```
################ Q: model::Person.all()->project(~['':p|$p.age])
  [G] TypedProject :: Relation<(:Integer[1])>[1]
  [SQL] SELECT t0.AGE_VAL AS "" FROM T_PERSON AS t0
  [EXEC-ERROR] java.sql.SQLException: Parser Error: zero-length delimited identifier at or near """"
################ Q: model::Person.all()->project(~[a:p|$p.age, b:p|$p.firstName])->rename(~a, ~'')
  [G] TypedRename :: Relation<(:Integer[1], b:String[1])>[1]
  [EXEC-ERROR] java.sql.SQLException: Parser Error: zero-length delimited identifier at or near """"
```

---

### [INCONSISTENCY] Late-bound (raw-SQL grid) relations: `project` trusts every column name, every other operator trusts none

`Type.RelationType.trustedColumn` / `isLateBound` (Type.java:449-513) define a "trust-name"
rule; only the property-read path applies it. Same source relation, six operators
(`/tmp/a13/q_lb.txt`, `executeInDb('select 1 as A, 2 as B', …)`):

```
  bare              [G] TypedRawSqlRelation :: Relation<()>[1]   -> [COLS] A:Any[0..1]  B:Any[0..1]
  ->select(~[A])    [G-ERROR] ... unknown column 'A' in ()
  ->rename(~A, ~B)  [G-ERROR] ... unknown column 'A' in ()
  ->sort(desc(~NOPE)) [G-ERROR] ... unknown column 'NOPE' in ()
  ->distinct()      [G-ERROR] distinct(~[]) names no columns          <-- misleading message
  ->concatenate(#TDS A,B / 5,6#) [G-ERROR] ... T bound to relation [] cannot also bind relation [A, B]
  ->columns()       [G-ERROR] NotImplementedException: columns() over a LATE-BOUND relation ...
  ->project(~[x:r|$r.A])    [G] Relation<(x:Any[0..1])>[1]   -> works
  ->project(~[x:r|$r.NOPE]) [G] Relation<(x:Any[0..1])>[1]   -> [EXEC-ERROR] SQLException: ... does not have a column named "NOPE"
  ->limit(1)        [G] Relation<()>[1]  -> [COLS] A:Any[0..1]  B:Any[0..1]   (works, schema claim empty)
```
`select(~[A])` on a relation that demonstrably *has* an `A` is rejected, while
`project(~[x:r|$r.NOPE])` on a column that does not exist compiles and fails at the database.
`distinct()`'s message is factually wrong (the relation is late-bound, not zero-column) —
`DistinctChecker.java:42-44` reaches its `~[]` guard for a reason it does not name.

---

### [INCONSISTENCY] Dynamic pivot columns survive some projection operators and are dropped by others, under an identical claimed type

All six queries below claim `Relation<(city:String[1])>` after the pivot:

```
  bare pivot        [COLS] city  '2020__|__total'  '2021__|__total'
  ->sort(asc(~city))[COLS] city  '2020__|__total'  '2021__|__total'   (SELECT *)
  ->limit(1)        [COLS] city  '2020__|__total'  '2021__|__total'   (SELECT *)
  ->extend(~[q:…])  [COLS] city  '2020__|__total'  '2021__|__total'  q
  ->select(~[city]) [COLS] city                                       (explicit list — pivot cols GONE)
  ->project(~[c:…]) [COLS] c                                          (explicit list — pivot cols GONE)
  ->rename(~city,~town) [COLS] town                                   (explicit list — pivot cols GONE)
  ->distinct()      [COLS] city                                       (explicit list — pivot cols GONE)
```
`columns()` reports only the static half in every case:
`pivot(…)->columns()->map(c|$c.name)` → `[ROW] String(city)`. Whether the data-derived columns
reach the caller is decided by an unrelated lowering detail (explicit projection list vs `*`),
not by anything visible in the type.

---

### [INCONSISTENCY] One declared Pure type, three Java runtime classes

All three columns below are declared `Integer[1]`:

```
  project(~[a:p|$p.age])                        [ROW] Integer(30)          java.lang.Integer
  extend(over(~g,asc(~a)), ~[b:{p,w,r|$p->rank($w,$r)}])  [ROW] Long(1)    java.lang.Long   (DuckDB)
                                                 same query on SQLite:      java.lang.Integer
  extend(~[b:x|$x.a:y|$y->sum()])               [ROW] BigInteger(103)      java.math.BigInteger
```
Forward/backward asymmetry: a consumer decoding by declared type must handle three classes for
one Pure type, and the class also varies by dialect.

---

### [MESSAGE QUALITY] `concatenate`'s type-mismatch diagnostic is uninformative; `extend(~[])` leaks an internal class name

```
################ Q: model::Person.all()->project(~[v:p|$p.age])->concatenate(model::Person.all()->project(~[v:p|$p.age->toFloat()]))
  [G-ERROR] ... column mismatch: type variable T bound to relation [v] cannot also bind relation [v]
```
The message lists only column *names*, so every type/multiplicity mismatch reads as
"`[v]` cannot bind `[v]`". (Verified identical for Integer-vs-Float, Integer-vs-Decimal,
Decimal-vs-Float, String[1]-vs-String[0..1], Integer-vs-String.)

```
################ Q: model::Person.all()->project(~[a:p|$p.age])->extend(~[])
  [G-ERROR] com.legend.compiler.spec.TypeInferenceException: expected mapped column specification(s), got TypedAggColSpecArray
```

---

### [DEAD] Unreachable local error paths

* `SortChecker.java:215` (`"sort expects asc(~col) or desc(~col) keys, got …"`) and
  `OverChecker.java:63` (`"unsupported over(…) argument: …"`) are unreachable — the generic
  overload resolution rejects the same inputs first
  (`sort([asc(~a), 1])` and `over(1)` both produce
  `TypeInferenceException: no overload of '…' structurally matches the argument types …`).
* `Frames.java:11-12` imports `LegendCompileException` and `ModelException` and uses neither —
  the exact two classes it should be throwing instead of `IllegalStateException`.

---

# VERIFIED SOUND

Everything below was executed and the returned values checked against the claimed
column types and multiplicities.

**Duplicate / colliding names — all clean `SchemaInvariantException` (a `LegendCompileException`):**
`project(~[a:…, a:…])`, `project(~[age, age])`, `select(~[age, age])`, `distinct(~[a,a])`,
`extend(~[b:…, b:…])`, `extend(~[a:…])` onto an existing `a`,
`extend(over(~g), ~[a:…])` onto an existing `a`, `rename(~a, ~b)` onto an existing `b`,
`rename(~[a,b], ~[b,a])`, `rename(~[a,b], ~[x,x])`.

**Unknown-column diagnostics — clean, and they name the schema:**
`select(~[zzz])`, `distinct(~[zzz])`, `sort(~zzz)`, `sort('zzz')`, `rename(~zzz, ~c)`,
`flatten(~zzz)`, `extend(~[c:r|$r.b, b:…])` in either order ("relation has no column 'b'" —
an extend column correctly cannot see a sibling added in the same call).

**`select`** — column order follows the *selection*, not the source: `select(~[b,a])` →
`Relation<(b:String[1], a:Integer[1])>`, `SELECT t0.FIRST_NAME AS b, t0.AGE_VAL AS a`, rows match.
`select(~a)` (bare colspec) desugars correctly. `select(~[])` rejected.

**`rename`** — `rename(~a, ~c)` keeps the **source position** (`Relation<(c:Integer[1], b:String[1])>`,
not `(b, c)`), matching `positionPreserving`'s stated intent. `rename(~a, ~a)` is a legal no-op.
Array form desugars to a chain and pairs positionally. Arity mismatch is a clean error.

**`distinct`** — `distinct()` → `SELECT DISTINCT` over all columns, 2 rows from 3, type preserved;
`distinct(~[a])` narrows the schema *and* the projection consistently; `~col` sugar works;
`distinct(~[])` rejected; the collection overload (`[1,2,2,3]->distinct()`) correctly routes to
the library path (`Integer[*]`, 3 rows).

**`concatenate`, 2-argument form — strict and correct.** Rejected: different names
(`[id,name]` vs `[id,nom]`), different order (`[id,name]` vs `[name,id]`, `[a,b,c]` vs `[c,b,a]`),
different arity (`[id,name]` vs `[id]`, vs `[id,name,extra]`), Integer vs Float, Integer vs Decimal,
Decimal vs Float, Integer vs String, `String[1]` vs `String[0..1]`. Accepted only on exact
schema identity, and then `UNION ALL` returns exactly the union with the declared types.
Chained `a->concatenate(b)->concatenate(c)` is correct.

**`sort`** — all shapes checked: `~col`, `asc(~col)`, `desc(~col)`, `[asc,desc]` multi-key,
legacy `'COL'`, `['A','B']`, `('COL', SortDirection.DESC)`, `asc('COL')`, repeated key
`[asc(~a), desc(~a)]`, chained `sort()->sort()` (correctly isolates into a subselect so the
outer key wins), sort over a `[0..1]` column. `SortDirection.SIDEWAYS` → clean
`"sort direction must be ASC or DESC"`. Values and orderings verified row by row on DuckDB.
(Null placement is the finding above; everything else in `sort` behaved.)

**`limit`/`drop`/`slice`** — `limit(2)`, `limit(0)` (0 rows), `limit(1000)` (all rows),
`limit(9223372036854775807)`, `take(2)`, `drop(1)`, `drop(100)` (0 rows), `slice(0,2)`
(correctly folds to a bare `LIMIT 2`), `slice(1,100)`, `slice(1,1)` (empty, `[s,e)` half-open).
`limit([])` is the identity per `SlicingChecker.java:22-29`. `limit(1.5)` / `limit('2')` are
clean overload-resolution errors. Non-literal bounds wall as `NotImplementedException`, the
designated backlog class — not an ICE.

**`filter`** — non-Boolean bodies rejected (`Integer`, `String`), `[2]`-valued rejected,
out-of-scope variable → `"unbound variable '$zzz'"`, wrong lambda arity rejected.
Null handling in the *lowering* is genuinely careful: `!($r.c == 'x')` and `$r.c != 'x'` both
render `IS DISTINCT FROM` and correctly KEEP the null row (Pure semantics), `->isEmpty()`
renders `IS NULL`, `->startsWith` renders `(col IS NOT NULL AND starts_with(...))`.

**`extend` window forms** — `over(~g)`, `over(asc(~a))`, `over(~g, asc(~a))` all render the right
`PARTITION BY` / `ORDER BY`; `rank()` and `lead()` produce correct values;
`lead(...)` correctly types `Integer[0..1]` and returns real nulls at partition ends.
`over()` with zero args, a 3-param `{p,w,r|…}` lambda outside a window, a 1-param lambda inside
one, and `over(~zzz)` are all clean compile errors. Frames that classify:
`unbounded()->rows(0)`, `unbounded()->rows(unbounded())`, `rows(0,1)`, `rows(-1,0)`,
`unbounded()->_range(0)`, `_range(unbounded(),unbounded())` — all render the expected
`ROWS/RANGE BETWEEN …` and the running aggregates are numerically correct.

**`columns`** — folds statically to the right names and an exact-size multiplicity;
`columns(rel, 1)` → clean arity error; a class value → clean "expected a Relation";
late-bound → clean `NotImplementedException`; `->size()` and `->map(c|$c.name)` fold correctly.

**Column-name handling** — SQL keywords (`select`, `from`, `order by`), spaces, embedded
double quotes, non-ASCII (`日本語`) and the pivot separator `__|__` are all quoted correctly
and round-trip through execution with the declared type intact. (Only the *empty* name breaks —
finding above.)

**Legacy `project` desugars** — `project([λ],['n'])`, `project(λ,'n')`, `project([λ])` with a
property leaf, arity mismatch between lambdas and names, non-string names, non-lambda column
expressions, `project()`, `project('x')`, `project(a,b,c)` — all behave as documented and error
cleanly where they should.

---

# NOT COVERED

* **`distinct` over a byte-array or Function-typed column.** No spelling in this dialect
  produces such a relation column; I could only reach the nested-relation case (covered above,
  and it is unsound for an unrelated reason). Variant columns only arise from `flatten`, whose
  own precondition gap fires first.
* **Interval window frames end-to-end.** `_range(n, DurationUnit.X, …)` classifies and renders
  (`RANGE BETWEEN INTERVAL 1 DAYS FOLLOWING AND …`) but every execution fails on DuckDB with
  `No function matches '+(INTEGER, INTERVAL)'` because the fixture has no temporal column to
  order by. Whether interval frames are correct over a DATE order key is untested — it needs a
  model with a date-typed property, which I did not build.
* **H2 is only partly covered.** The `LocalH2` connection spelling parses and I ran the sort
  null-placement battery on it; I did not re-run the whole battery there. SQLite similarly was
  used only for the sort/window null-order comparison (its TDS-literal gap, documented at
  `sql/dialect/Lexicon.java:36-37`, blocks the `#TDS`-based concatenate battery).
* **`ProjectChecker`'s `col(fn,'name','doc')` documentation-carrying path** — I exercised the
  2-arg `col` desugar but not the 3-arg documentation round-trip through
  `.columns.documentation`.
* **`ExtendChecker.legacyColToSpec`'s `^BasicColumnSpecification(...)` arm** — read but not
  exercised; I could not produce that spelling from surface syntax.
* **Streaming / wire execution paths** (`executeStreaming`, `executeWire`) — every finding here
  was reproduced through `Compiler.compileQuery` / `plan` / `execute` only.
