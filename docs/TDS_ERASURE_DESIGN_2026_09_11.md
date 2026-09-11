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
