# TDS erasure — batch 5 leg 4 design (2026-09-11)

**Goal.** Every legacy-TDS native in Pure.java carries upstream's exact signature
(`TabularDataSet`, `TDSRow`, `Table`) while the platform keeps Model B: a TDS *is* a
`Relation<T>` whose schema the checker knows, and a TDS row *is* the schema's row
struct. USER 2026-09-11: "Even TDS v Relation we need to brainstorm on how to use
the correct signature" — no ledger of respellings.

## 1. What upstream declares (the pinned checkouts)

| Native | Upstream signature |
|---|---|
| `filter` | `(tds:TabularDataSet[1], f:Function<{TDSRow[1]->Boolean[1]}>[1]):TabularDataSet[1]` |
| `sort` | `(TabularDataSet[1], String[1], SortDirection[1])`, `(TabularDataSet[1], String[*])`, `(TabularDataSet[1], SortInformation[*])` |
| `limit` | `(TabularDataSet[1], Integer[0..1])` (the optional form the corpus's `testOptionalLimit_WithValue` uses) |
| `tableToTDS` | `(Table[1]):TabularDataSet[1]` |
| `tdsContains` | `(T[1], Function<{T[1]->Any[0..1]}>[*], TabularDataSet[1])` and the 5-argument form |
| `concatenateTemporalTdsQueries` | `(LambdaFunction<{->TabularDataSet[1]}>[*])` |
| `TDSRow.getString(colName)` etc. | **qualified properties** of `Class meta::pure::tds::TDSRow` (the prelude carries them, batch 4a) — not functions |

Today Pure.java spells these over `Relation<T>` / `T` (six rows) and declares the
nine getters as functions under `meta::pure::tds::getX` FQNs upstream never
declares (nine rows). Fifteen divergent rows, plus `limit(Integer[0..1])`.

## 2. What the checker already does (measured, not designed here)

- `TabularDataSet` as a **formal** admits any relation carrier (`InferenceKernel`
  unify + score arms: "the SCHEMA-ERASING nominal over the relation carrier").
- `TDSRow` as a formal admits any bare row struct ("the ERASED row nominal … the
  callee is then monomorphized at its call site").
- `cast(@TabularDataSet)` over a relation is the identity, keeping the schema
  (`CastChecker`).
- `$r.getString('COL')` types through the Typer's row-cell arm and lowers through
  the `RowGetter` family (`RowGetters.read`) — by NAME of the getter, today via a
  `TypedNativeCall` to our invented `meta::pure::tds::getString` function.

So the erasure of *parameters* exists. What is missing is the erasure of the
**result** and the getters' true identity.

## 3. The two rules to add

**R1 — declared `TabularDataSet` result refines to the argument's relation.**
When the chosen overload's declared return type is `TabularDataSet` and one
argument's actual type is a relation carrier (`Type.isRelation`), the call's
result type is that argument's type (same schema, same carrier). This is a
*refinement of the registered signature's output, never a bypass of its checks* —
the same pattern as `refineDecimalCarrier` / `refineParseDate` in
`Typer.checkGenericTyped`. It keeps Model B's schema flowing through `filter`,
`sort`, `limit`, `tableToTDS` (whose argument is a `Table` accessor: the result
is `Relation<schema of the table>`) and `concatenateTemporalTdsQueries` (the
lambdas' result relation).

Real pure's own execution does the same thing dynamically: a `TabularDataSet`
value carries its columns at run time; we carry them at type time. Emission is
unchanged.

**R2 — a `TDSRow`-typed lambda parameter is the receiver's row struct.** When a
deferred lambda types against `Function<{TDSRow[1]->…}>` and the enclosing call's
relation argument is known (it always is: `filter(tds, f)`), the lambda parameter
is typed as that relation's row struct, not as the nominal `TDSRow`. Then
`$r.getString('COL')` types through the existing row-cell arm with the column
known, and `$r.FIRST_NAME` (not legal on a real `TDSRow`, but the corpus never
writes it inside a TDS lambda) stays exactly as today. Where the kernel already
monomorphizes `TDSRow` params "at the call site", R2 is that rule stated for the
deferred-lambda path.

## 4. The getters

The nine `meta::pure::tds::getX` function signatures leave Pure.java. The prelude's
`TDSRow` declares `getString(colName:String[1]){…}:String[1]` and the rest as
qualified properties; the platform already OWNS those derived properties
(`isPlatformOwnedDerivedProperty`, the "TDSRow's cell accessors" suppression), so
`$r.getString('x')` is a qualified-property call on the row struct, typed by the
row-cell arm and lowered by `RowGetters` exactly as now. What changes is only the
node: a qualified-property access instead of a native call to an invented FQN. The
`RowGetter` family then keys on the TDSRow property names (typed: the family enum
carries the property name, not a Pure.java overload) — the claim registry's
FAMILY kind already allows members without overloads? **No** — `NativeFn.Member`
requires `overloads()`; the getters become a family whose overloads are EMPTY and
whose identity is the qualified property (a small `Member` variant, or the
RowGetter family moves to a `DerivedProperty` registry beside `Subsumed`). Decide
at implementation: the smallest truthful shape is RowGetter members with zero
overloads and a `propertyName()`.

## 4b. The representation (after the first attempt — USER 2026-09-11: "are we hacking to pass tests?")

The first implementation put R2 into lambda-parameter typing and the getters into
three routes; every chain then exposed one more site (two-row lambdas,
concatenated-query lambdas, non-literal column names, `.rows` values) and each got a
patch. Measured over the corpus, the engine core and the prelude, the facts that
settle it:

| fact | corpus | engine core |
|---|---:|---:|
| lambda parameters annotated `TDSRow[1]` | 143 | 18 |
| lambdas with TWO `TDSRow` parameters | 6 | 0 |
| function parameters typed `TDSRow` / returning `TDSRow` | 22 / 0 | 4 / 1 |
| `.rows` reads (already row structs in our typer) | 1805 | 43 |
| getter calls with a LITERAL column name | 1108 | |
| getter calls with a NON-literal name (`$r.getString($c.name)`) | 52 | |
| `cast(@TDSRow)` | 0 | 0 |

So a nominal `TDSRow` VALUE arises in exactly one way: a `TDSRow` written in a type
position (a signature parameter, a lambda annotation, a class property such as
`TabularDataSet.rows`). And upstream's getters type their result by DECLARATION
(`getString(colName):String[1]`) — real pure does not know the columns at type
time either.

**One rule, one place — `TDSRow` erases where a type expression becomes a `Type`.**
`TypeClassifier.classify` (signatures, class properties) and
`TypeAnnotations.namedType` (annotations) map the class `TDSRow` to the erased row
`Type.RelationType.lateBound()`. Nothing downstream ever sees a `ClassType TDSRow`:
lambda parameters, two-row lambdas, `.rows` elements and function parameters are all
rows; a concrete row struct conforms to the erased row (the kernel's column
unification: an empty formal column set admits any actual); the kernel's `TDSRow`
special arms go. No pairing rule, no context threading.

**The getters are `TDSRow`'s qualified properties, implemented by the platform.**
The prelude declares them; their lifted definitions (`TDSRow$prop$getString(this,
colName):String[1]`) are the TYPING source (no longer suppressed in
`FunctionCompiler`); the inliner never splices them (their bodies are m3
reflection); the census counts them with the natives (implemented in Java). Two
shapes, one family (`NativeFn.RowGetter`, keyed by the property NAME — the
`TDS_ROW_GETTERS` and `JoinChecker.TDS_GETTERS` string sets go):
- a LITERAL column name FOLDS to the row's column read at type time — a compile-time
  decision over spelled text, like every LiteralUnroll fold — typed by the schema when
  the row is concrete and by the getter's DECLARED type when the row is erased;
- a NON-literal name stays a call to the lifted property (`TypedUserCall`), typed by
  its declaration, and `RowGetters` lowers it by name once inlining/unroll has made
  the name literal (the same residual rule the natives followed).

**`TabularDataSet` stays nominal; R1 refines the result** (declared
`TabularDataSet` → the argument's actual relation, or the relation a query lambda
argument returns), the `refineDecimalCarrier` pattern. Its parameter half exists in
the kernel already.

## 5. Sequence

1. Adopt upstream's text for the six TDS rows + `limit(Integer[0..1])` (re-key the
   membership; generate).
2. R1 in `Typer.checkGenericTyped` (one refinement function, unit-tested on
   `filter`/`sort`/`limit`/`tableToTDS`).
3. R2 in the deferred-lambda path (the `TDSRow` formal takes the relation
   argument's row struct).
4. The getters: delete the nine signatures; RowGetter becomes property-keyed;
   `Typer`'s row-cell arm and `RowGetters.read` switch from the native-call node to
   the qualified-property node.
5. Chain. Expected corpus movement: zero (the corpus's TDS tests already pass under
   Model B; the change is which declaration they type against).

## 6. Not in scope

`project`/`groupBy` legacy TDS forms over `T[*]` with `ColumnSpecification` and
`AggregateValue` (upstream declares them over `TabularDataSet` and over `T[*]`
both): they are in leg 5's invented-arity set (`GROUP_BY__C_MANY…`,
`EXTEND__C_MANY…`) and get the same R1 treatment once their text is adopted.

## 7. Landed (2026-09-11)

Implemented once from §4b; chain green on the third run (19 → 8 → 0 lost, every
fix at the representation — receipts in `docs/GATES.md`, "BATCH 5 LEG 4"). What the
implementation settled beyond §4b: (a) R1 is the KERNEL's output rule
(`InferenceKernel.resolveOutput(returnType, mult, bindings, args)` → `TdsErasure`),
read by the eager and the deferred call paths alike, and it substitutes inside the
output's shape (`concatenateTemporalTdsQueries` returns
`LambdaFunction<{->TabularDataSet}>`); §5 step 2's "in `Typer.checkGenericTyped`"
was the eager path only. (b) §3's R2 does not exist: with the row erased at the type
position there is no per-call "receiver's row" rule to write; a `TDSRow`-typed
helper is schema-erased (`isSchemaErased` knows `isLateBound`) and inlines. (c) The
erased-row classes are the accessor family's OWNERS — `TDSRow` and the ResultSet's
`execute::Row` (`value(name):Any[1]`), one rule (`NativeFn.RowGetter.isOwner`).
(d) Upstream declares `getString(colName:String[1])` beside
`getString(col:TDSColumn[1])`: the lifted overload is resolved by the kernel like
any qualified property. (e) The typed class carries its qualified properties
(ClassCompiler's owned-accessor skip and the `TDS_ROW_OWNED_ACCESSORS` table are
gone); the last string-matched getter (`Substitution.crossCellSubst`) reads the
typer's fold. Not touched, measured: the kernel's two nominal-TDSRow formal arms
(InferenceKernel ~181, ~2046) — dead once no TDSRow reaches the kernel; leg 5
deletes them with a measurement.
