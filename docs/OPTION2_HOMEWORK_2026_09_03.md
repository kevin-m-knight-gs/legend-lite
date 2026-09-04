# Option 2 homework — "the compiler compares, the database computes" (2026-09-03)

Question (user): before building option 2 (leave every value computation to the
database; the compiler only decides structure), do the homework: does it work for
the 9 debugPrint tests, does it scale to toPostgresModel, and what exactly does it
need? No sampling — every source read in full.

Sources read end to end:

| file | lines | what |
|---|---|---|
| `sqlQueryToString/dbSpecific/debugPrint/debugPrintExtension.pure` | 1050 | `wrapH2Boolean` + 5 helpers (493–562), 9 tests (624–1050) |
| `sqlDialectTranslation/toPostgresModel.pure` | 1069 | 81 functions, 1 class, a 190-entry converter map (268–465) |
| `sqlDialectTranslation/tests.pure` | 563 | 21 tests, `getTable`/`getColumn`/`assertConversion` helpers, `TestDb` |
| legend-pure `platform_store_relational/grammar/relational.pure` | — | the relational metamodel: 22 `<<equality.Key>>` (DynaFunction name+parameters, Literal value, Alias name+relationalElement …) |
| engine `core_external_store_relational_postgres_sql_model/metamodel.pure` | — | the SQL-node metamodel: 143 `<<equality.Key>>` (every node class is keyed) |

Plus our own code: `UserCallInliner`/`LiteralUnroll` (the unroll), `Lowerer` +
`ClassLayouts` + `LayoutTypes` (how a constructed instance becomes a SQL struct),
`InstanceEquality`/`CanonicalRenderSql` (how `assertEquals` on instances is judged),
`StoreResolver` (the "class query under TypedNewInstance" wall),
`RelationalCorpusRunner` (which files enter the runtime model), `Pure.java` +
`MetamodelWalk`/`MetamodelSteps` (the natives and the Java port that serve
toPostgresModel today).

---

## 1. The principle, made precise

**The compiler compares spelled tokens; the database computes values.**

STRUCTURAL (the compiler may decide, because the answer is visible in the program
text): which `match` arm a literal's class picks; a field the literal spelled; a
field the literal did not spell (empty, or the class's declared default);
`instanceOf`/`cast` on a literal's class; list shape over spelled lists — `at`,
`first`, `last`, `tail`, `init`, `slice`, `size`, `isEmpty`, `reverse`,
`concatenate`, `zip`, `fold`, `map`, `filter` (applied per element); identity of
two spelled scalars of the SAME kind (`'a' == 'a'`, `x->in(['case','if'])`, enum
== enum, integer == integer and integer < integer for spelled integers such as
`size()` results); a spelled enum's name (`toString`); `newMap`/`get`/`pair` over
spelled pairs; `groupBy`/`keyValues` over spelled pairs; `enumValues`;
`dynamicNew(Class, spelled KeyValues)`; `assert(true, …)` is a no-op.

COMPUTED (the database's, as a SQL scalar in the value): anything that produces a
NEW value — `toLower`, `toUpper`, `trim`, `startsWith`, `endsWith`, `contains`,
`replace`, string `+`, arithmetic (`-`, `/`, `*`, `floor`), `joinStrings`,
`format`, `toString` on numbers/dates, `1 == 1.0` (cross-kind).

A decision that depends on a COMPUTED value stays undecided at compile time: the
compiler carries BOTH outcomes and the database picks (`CASE`). Three residual
shapes exist, in increasing cost:

| residual | example | SQL | cost |
|---|---|---|---|
| scalar | `if(x->startsWith('"'), \|…, \|x)` | `CASE … END` of VARCHAR | already lowerable (TypedIf) |
| list | `if(schema == 'default', \|[], \|[schema])->concatenate(name)` | `CASE` of VARCHAR[] | already lowerable |
| shape | `if(cond, \|^DynaFunction(castBoolean…), \|$d)` | `CASE` of two structs | needs same struct type on both sides (see §4) |

and one residual that does NOT exist today:

| residual | example | needed by |
|---|---|---|
| conditional membership | `[e1,e2]->filter(x \| computed(x))->isNotEmpty()` — each element kept under its own SQL condition | debugPrint (isCastableDyna's filter) |

---

## 2. debugPrint (9 tests) — traced

`wrapH2Boolean` decides by: `match` on class (structural); `isCastableDyna` =
`instanceOf(DynaFunction) && name->in(['case','if']) && parameters->slice(1,3)->filter(x|isTrueFalseString(x))->isNotEmpty()`;
`isTrueFalseString` = `instanceOf(Literal) && value->match([String | toLower()->in(['false','true']), Any | false])`;
`isEqualComparingCastableDynaAndBoolean` = `name=='equal' && isCastableDyna(p0) && isBooleanExpr(p1)`;
`atRecursibleOperation` = `name->in(['and','or','not','group','if'])`.

The ONLY computed value on the whole path is `toLower` on a spelled string
(`'true'`, `'false'`, `'Y'`). Every other decision is a token compare.

| test | case/if nodes | undecided shape decisions under option 2 | tree depth |
|---|---|---|---|
| testWhenJustCase_thenIsWrapped | 1 | 1 (`isCastableDyna(case)`) | 2 |
| testWhenCaseNestedByAnd | 1 | 1 | 3 |
| testWhenCaseNestedByOr | 1 | 1 | 3 |
| testWhenCaseNestedByNot | 1 | 1 | 3 |
| testWhenCaseNestedByGroup | 1 | 1 | 3 |
| testWhenCaseEqualBooleanLit_thenBothWrapped | 1 | 1 (`isCastableDyna(p0)` inside the equal) | 4 |
| testWhenCaseEqualTrue_thenNoOp | 1 | 1 | 4 |
| testWhenCaseEqualYesNoLit_thenNoOp | 1 | 1 | 4 |
| testSomeAST_thenIsWrapped | 2 (`if`) | 2 | 8 |

Branch cost is trivial: at most two 2-way shape `CASE`s per test, and `and`/`or`/
`group`/`equal` nodes decide structurally (their name is not in `['case','if']`,
so the `&&` short-circuits before the filter). Recursion still terminates: the
descent measure is the literal argument's size, independent of which branch runs.

**Probe receipt (this session):** with the Java `toLower` fold switched off and
nothing else changed, the family went **9/9 → 0/9**, every test walling at
lowering with `instanceOf undecidable statically: RelationalOperationElement vs
'Literal'`. That is the filter lambda being lowered UN-APPLIED (its parameter `x`
unbound) because the unroll has no conditional-membership residual: today a
predicate that does not fold to a boolean makes the whole filter fall back to the
symbolic form. The verdict layer was never reached. So: **option 2 works for all 9
debugPrint tests once the conditional-membership residual and the shape CASE
exist; without them it works for none of them.**

Verdict feasibility (§4): DynaFunction is keyed (name, parameters), so
`assertEquals` compares key trees, not identity; `parameters :
RelationalOperationElement[*]` is carried as JSON (the declared class has no
stored properties → `LayoutTypes` gives the slot JSON), so the two branches of a
shape `CASE` — `castBoolean(d)` vs `d`, both DynaFunction — have the SAME struct
type `{name VARCHAR, parameters JSON, __id}` and DuckDB unifies them.

---

## 3. toPostgresModel (21 tests) — traced

### 3a. Loading facts (why the family is 1/21 today)

* `toPostgresModel.pure` is **not in the runtime model**. The corpus admits a
  sibling file only when it is store-only or function-only
  (`RelationalCorpusRunner` sibling rule); `toPostgresModel.pure` declares
  `Class ModelConversionState`, so it is skipped, and the cross-family closure
  pulls files only for class-mapping heads, `include`, and `extends`.
* What serves the tests instead: `Pure.java` declares `newState`,
  `convertElement`, `convertSelectSqlQuery` and `ModelConversionState` as
  NATIVES, and `MetamodelWalk` (905 lines) + `MetamodelSteps` (156) +
  `StatementExecutor.constructNode/constructOp` evaluate them in Java — a
  hand-written port of the conversion. This is the quarantined "metamodel
  channel" the architecture tests track. Under either option, running the real
  Pure means admitting the file and deleting that port.
* `DynaFunctionRegistry` (needed by `getDynaFunctionConverterMap`'s asserts) is an
  Enum in `sqlQueryToString/dbExtension.pure` — a file with classes, enums and
  functions, also not admitted today.
* Every class/enum the 1069-line file references must be declared for the file
  to type-check, whether or not a test reaches the code. Declared natively today:
  45 SQL-node classes, 65 relational-metamodel classes. Referenced but missing
  (counted from the source): SQL side ≈ 26 classes (ExistsPredicate,
  NotExpression, SubqueryExpression, Bitwise{Not,Shift,Binary}Expression, Trim,
  ArithmeticExpression, NegativeExpression, DecimalLiteral, AllColumnsReference,
  SearchedCaseExpression, WhenClause, Group, PivotedRelation, VariablePlaceholder,
  FreeMarkerOperation, FrameBound, extension::AsOfJoin/LateralJoin, five
  SemiStructured* nodes, ExtractFromSemiStructured) + ≈ 12 enums
  (LogicalBinaryType, ComparisonOperator, ArithmeticType, BitwiseBinaryOperator,
  BitwiseShiftDirection, TrimMode, JoinType, SortItemOrdering, SortItemNullOrdering,
  WindowFrameMode, FrameBoundType, TemporalUnit); relational side ≈ 22 (Pivot,
  Frame, FrameValue + 4 kinds, FrameValueDirection, FrameType, DataTypeInfo,
  ViewSelectSQLQuery, TableFunctionParamPlaceHolder, UnionOrJoin, VarPlaceHolder,
  VarSetPlaceHolder, VarCrossSetPlaceHolder, FreeMarkerOperationHolder, four
  SemiStructured* elements, CrossSetImplementation). Three functions from other
  engine libraries are referenced only in arms no test reaches
  (`pureToSqlQuery::findTableForColumnInAlias`, `extractTableAliasColumns`,
  `sqlQueryToString::parseSemiStructuredPathNavigation`) — signatures suffice.
* **Equality keys:** the engine's SQL-node metamodel carries 143
  `<<equality.Key>>` and the relational metamodel 22; our native declarations
  carry 4. `InstanceEquality` compares keyed classes by key tree and keyless
  classes by identity, so the declarations must be regenerated WITH their keys or
  every `assertEquals` on a constructed node tree compares identities and fails.
  (Defaults matter too: `FunctionCall.distinct: Boolean[1] = false` — the expected
  literal omits it, the conversion spells it.)

### 3b. The "90 functions" question

The converter map is DATA: 190 `pair(name, lambda)` entries built once per
`newState()` (plus 380 `assert`s over them and a `groupBy`/`keyValues` pass).
Only entries a test reaches are ever evaluated. The 21 tests reach **12**:
`and`, `equal`, `notEqual`, `greaterThan`, `sin`, `ltrim`, `firstDayOfWeek`,
`convertDate`, `isNull`, `in`, `denseRank`, `joinStrings`. Typing the file touches
all 190 (hence the declarations above), evaluating touches 12. Scale is not the
issue; the declarations and the loading rule are.

### 3c. Vocabulary census of the whole file (81 functions)

| kind | functions (count of call sites in the file) |
|---|---|
| structural | `at` 62, `toOne` 42, `cast` 37, `map` 26, `size` 20, `concatenate` 20, `isNotEmpty` 17, `instanceOf` 14, `match` 13, `tail` 7, `isEmpty` 7, `fold` 5, `newMap` 4, `get` 4, `last` 4, `filter` 4, `first` 3, `defaultIfEmpty` 3, `reverse` 2, `zip` 1, `init` 1, `toOneMany` 1, `in` 1, `contains` 2 (on spelled lists), `keyValues` 1, `groupBy` 1, `enumValues` 1, `dynamicNew` 3, `pair` ~210, `if` (plain and pair-list forms), enum `==`/`toString` |
| computed | `toString` 30 (on enums: structural; on numbers: only `printDataType`/`convertLiteral` Number arm — unreached), `startsWith` 2 + `endsWith` 1 + `replace` 3 + string `+` (toIdentifier/quoteIdentifier — on EVERY identifier, every test), integer `-` (convertLimit; test 21), `floor`/`/`/`*`/`range` (caseExpression — unreached), `joinStrings` 1 (convertVarPlaceHolder — unreached), `format` (assert messages — unreached), `getLowerBound` (unreached) |
| reflection | `genericType().rawType.name`, `type().name` (printDataType, convertSqlQuery — unreached) |
| effects | `assert` ×2 per converter entry (all decidable → must fold to no-op), `fail` (unreached) |
| store reads (tests.pure) | `TestDb.schemas.tables->filter(name==…)->toOne()` (getTable), `TestDb.schemas->first()`, `->filter(name=='default')`, `$table.columns->at(0)`, `rootClassMappingByClass`/`propertyMappingsByPropertyName`/`.joinTreeNode` (mapping rows) |

Under option 2 the computed set that any test REACHES is: `startsWith`,
`endsWith`, `replace`, string `+` (all inside `toIdentifier`/`quoteIdentifier`,
producing a scalar `CASE` per identifier) and one integer `-`. No computed value
decides a SHAPE in this family — every shape decision is a token compare
(`$d.name == 'exists'`, `name in […]`, `size() == 1`, `processingSelect`,
`isRootSelect`, `castToDate`). That is why option 2 costs this family almost
nothing beyond what any option needs.

### 3d. Per-test trace

Classes: **A** literal-only (no database value on the path), **B** a store read
supplies a scalar leaf, **C** recursion over row-backed trees.

| # | test | class | reached converters / helpers | undecided (kind) | store reads on path | needs |
|---|---|---|---|---|---|---|
| 1 | testConvertLiteral | A | convertLiteral (7 kinds + SQLNull), `literal()` helpers | 0 | — | primitive-kind match (String/Integer/Float/Decimal/Boolean/StrictDate/DateTime/Enum), assertInstanceOf |
| 2 | testConvertLiteralList | A | LiteralList arm, convertElementToExpression | 0 | — | — |
| 3 | testConvertCommonTableExpressionReference | A | qualifiedName → toIdentifier | 1 scalar | — | — |
| 4 | testConvertColumnName | A | convertColumn | 1 scalar | — | — |
| 5 | testConvertVarPlaceHolder | A | VarPlaceHolder arm | 0 | — | a type reference (`type = String`) as a literal leaf |
| 6 | testConvertVarSetPlaceHolder | A | VarSetPlaceHolder arm | 0 | — | — |
| 7 | testConvertVarCrossSetPlaceHolder | A | VarCrossSetPlaceHolder arm | 0 | in UNREAD fields only (`schema`, `crossSetImplementation`) | never lower an unread field |
| 8 | testConvertWindowColumn | A | convertWindowColumn, convertSortDirection (enum map), denseRank | ~6 scalar | — (getColumn's getTable is unread) | unspelled `window.frame` → empty (class default rule) |
| 9 | testConvertTabularFunction | B | TabularFunction arm | list of [subquery, 'ID'] + scalar | `$tf.schema.name` | M3 |
| 10 | testConvertDynaFnToLogicalExpression | A | convertDynaFunction (pair-list `if`, 6 token conditions), `and`/`greaterThan` converters (fold/tail), convertLiteral | 0 | — | pair-list `if` (typer has it), fold/tail unroll |
| 11 | testConvertDynaFnToFunctionCall | A | `sin`, `ltrim`, `firstDayOfWeek`→dateTruncCall, `convertDate` | scalar only | unread getTable | `if(size()==1)`, `if(castToDate)` decided |
| 12 | testConvertDynaFnToPredicate | A | `isNull`, `in` (match on InListExpression) | scalar | unread getTable | — |
| 13 | testConvertAlias | B | convertAlias ×3, convertTable, state copy | list (schema) + scalar | `$t.schema.name`, `$t.name` (alias1) | M3; static re-dispatch of the runtime match on the narrowed `Table` type |
| 14 | testConvertTableAliasColumnName | A | convertColumn | scalar | unread getTable | — |
| 15 | testConvertTableAliasColumn | B | TableAliasColumn arm | scalar | `$table.columns->at(0).name` | M3 + column ORDER (ordinal) in the store |
| 16 | testConvertTable | B | convertTable ×2 | list (schema) | `$t.schema.name`, `$t.name` | M3 + `Schema.tables`, `Table.schema` mappings |
| 17 | testConvertSelectSQLQueryWithCTE | B | convertSelectSQLQuery, convertJoinTreeNode (no children), convertAlias, convertTable, `children()` qualified property | list + scalar | getTable in the CTE body | M3, qualified-property inlining, unspelled→empty (pivot, distinct, filters…), zip/fold over empty |
| 18 | testConvertUnion | B | convertUnion (tail/fold), convertSelectSQLQuery ×2, convertTable ×2 | list + scalar | getTable ×2 (one in testSchema) | M3 |
| 19 | testConvertJoinStrings | A | convertJoinStrings (init/last/concatenate, defaultIfEmpty) | scalar | unread getTable | — |
| 20 | testConvertJoinTreeNode | C | preOrderTraversal over `getJoinTreeNode` ROWS, zip/fold over row lists, dynamicNew | — | mapping metamodel rows (JoinTreeNode trees) | recursion over row-backed trees = tier 2 |
| 21 | testConvertSelectSQLQuery | C | as 20 + `^$joinTreeNode(alias=…)` copy of a row + convertLimit (`5 - 2`) | scalar (int) | mapping rows + getTable | tier 2 |

Totals: **A = 13, B = 6, C = 2.**

---

## 4. What option 2 needs, mechanism by mechanism

| id | mechanism | exists today? | needed by |
|---|---|---|---|
| M1 | conditional-membership residual: `filter` (and `map`) over a spelled list whose predicate stays a SQL boolean after per-element substitution → each element guarded by its own condition; `isNotEmpty`/`size`/`at` over the guarded list lower to list SQL | **no** (a non-boolean predicate falls back to the un-applied lambda — the probe wall) | debugPrint 9 |
| M2 | shape `CASE`: `if(sqlBool, \|instanceA, \|instanceB)` with both branches the same class → `CASE` over two struct literals | TypedIf lowers to CASE; struct-branch unification unverified but guaranteed by the layout rule (same class ⇒ same struct type; polymorphic fields are JSON) | debugPrint 9 |
| M3 | a store read in SCALAR position inside a constructed instance (`$t.name`, `$t.schema.name`, `$table.columns->at(0).name`) lowers as a scalar subquery; `Schema.tables` + `Table.schema` mappings in the system store; column ordinal | **no** — this is the `class query under TypedNewInstance … not resolvable yet` wall; `Schema` maps only `views` today | toPostgresModel B (6) |
| M4 | loading: admit `toPostgresModel.pure` (sibling that declares a class) and `dbExtension.pure` (the registry enum); declare ≈ 60 missing classes/enums WITH their `<<equality.Key>>` (and regenerate the 110 existing native declarations with keys); signatures for 3 unreached library functions | **no** | toPostgresModel A+B (19) |
| M5 | structural folds not yet in `LiteralUnroll`: `fold`, `tail`, `init`, `reverse`, `defaultIfEmpty`, `newMap`/`get`/`pair`, `groupBy`/`keyValues` over spelled pairs, enum `toString`, integer `<`/`>` on spelled integers, `assert(true)` → no-op, `dynamicNew`; unspelled property → the class's default/empty; static re-dispatch of a runtime `match` on the narrowed static type after substitution; qualified-property inlining (`children()`); a type reference as a literal leaf | partly (`map`/`filter`/`at`/`slice`/`first`/`last`/`isEmpty`/`in`/`eq`/`newMap` typed, none folded) | toPostgresModel A+B |
| M6 | deletion: `NEW_STATE`, `CONVERT_ELEMENT`, `CONVERT_SELECT_SQL_QUERY`, `MODEL_CONVERSION_STATE` natives; `MetamodelWalk` conversion arms (~600 of 905 lines), `MetamodelSteps` arms, `StatementExecutor.constructNode/constructOp` | — | the whole point |
| M7 | tier 2 (recursive CTE over row-backed trees) | no, no other witness | toPostgresModel C (2) |

Under option 1 (Java string folds) the toPostgresModel list is IDENTICAL except
that `toIdentifier`'s `CASE`s become compile-time strings. Under option 3 (ask the
database for the constant at compile time) M1/M2 are replaced by a compile-time
connection in the compiler. The option choice therefore decides exactly two
things: how debugPrint's single `toLower` leaf is handled (M1+M2 vs 9 Java folds
vs a compile-time query), and the principle every future engine-library program
is held to.

---

## 5. Recommendation

1. Adopt the principle in §1 as a written rule of the unroll: structural folds
   compare spelled tokens; nothing in `LiteralUnroll` may produce a new scalar
   value. Delete the nine Java string folds and `and`/`or` value folds as part of
   landing M1+M2 (keep `not`, same-kind `equal`, `in`/`contains` over spelled
   scalars — those are token identity).
2. Land in this order, each a ratchet-moving batch:
   * **Batch 53 (now):** the unroll as it stands (+10, 0 lost), with the string
     folds still present and pinned in a ledger so their deletion is a visible
     step — or hold batch 53 until M1+M2 replace them. Either is honest; holding
     costs nothing but time.
   * **M1+M2** on debugPrint (9 tests keep passing with zero Java value
     computation) — the proof of option 2.
   * **M4+M5+M6** on toPostgresModel class A (13 tests) — the deletion of the Java
     port is the receipt.
   * **M3** on class B (6 tests): scalar subqueries inside constructed instances +
     the two store mappings + column order.
   * Class C (2 tests) is named tier-2 residue: recursion over the mapping's
     join-tree rows; no other corpus witness.
3. Expect the declarations (M4) to dominate the toPostgresModel effort, not the
   evaluator: ≈ 60 new class/enum declarations, all with equality keys, plus
   re-keying the 110 that exist.
