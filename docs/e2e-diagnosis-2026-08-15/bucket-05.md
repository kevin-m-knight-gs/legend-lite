# Bucket 5 — Invalid/unsupported SQL we emitted

12 tests from the ledger; **10 still non-passing** at `9d1f2cd0`. 2 now pass (fixed upstream since the 2026-08-14 sweep) and are marked below.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: REAL DEFECT 8, EXECUTION-TARGET ARTIFACT 4

---

## `testAll`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

This is `meta::relational::tests::query::function::concatenate::testAll` (`Product.all()->concatenate(Product.all())`) — a BARE class-collection concatenate at the query root. `StoreResolver.anchoredNode` has exactly one arm that recognises a class-collection concatenate, and it is gated on a `TypedProject` sitting above it (`case TypedProject p when classConcatOf(p.source()) != null ->` at StoreResolver.java:355). With no project above it, the node falls through the switch to the generic `case TypedNativeCall nc -> structural(Pipelines.classEmptinessRewrite(...))` arm (StoreResolver.java:477-478), which only resolves the arguments and leaves `concatenate` as an ordinary Pure native call. Lowering then applies the SCALAR collection rule for `concatenate` (Scalars.java:1635-1657) — `new SqlExpr.Call(SqlFn.LIST_CONCAT, args)` — over two class-typed extents, whose scalar encoding is a JSON-object scalar subquery (`(SELECT CASE WHEN COUNT(*) = 1 THEN MIN(json_object(...)) ...)`), hence `list_concat(JSON, JSON)`. DuckDB has only `list_concat([ANY[]...])`, so the binder rejects it. The Scalars comment itself states the invariant that is being violated: "the relation overload is the TypedConcatenate set-op and never reaches scalar lowering" (Scalars.java:1630-1631) — for a class-collection root it DOES reach scalar lowering.

**Fix**

In `StoreResolver.anchoredNode` (core/src/main/java/com/legend/resolver/StoreResolver.java), add an arm ABOVE the generic `case TypedNativeCall nc -> structural(...)` at line 477, mirroring the existing project-distribution arm at 355:

    case TypedNativeCall nc when classConcatOf(nc) != null
            && nc.info().type() instanceof Type.ClassType -> {
        yield new TypedConcatenate(
                resolveNode(nc.args().get(0), context),
                resolveNode(nc.args().get(1), context),
                nc.info());
    }

The two arms resolve independently as class-result chains (each yields the full class envelope: u_type + pk_0 + mapped scalar columns), and `Lowerer.union(TypedConcatenate)` (Lowerer.java:534) already turns that into the SQL UNION ALL. Two things must travel with it: (a) the arms must be column-aligned before the union (the engine does this in `alignJoinAndPkColumnsForUnion`, pureToSQLQuery.pure:2740) — if legend-lite's class-result envelope is not already positionally identical across arms, align by name in `Lowerer.union`; (b) the class-result decode path must accept a union-rooted class envelope so `$result.values` materialises 8 Product instances (2 arms x 4 rows) rather than one. Keep `classConcatOf`'s `anchored()` gate so a scalar concatenate (`Product.all().name->concatenate(...)`) still takes the existing Scalars LIST_CONCAT path.

**How legend-engine does it** — legend-engine lowers concatenate over routed class extents to a UnionAll set-op, not a value concat: /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:2709 `processConcatenate` — it flattens the concatenate, processes each arm to a SelectWithCursor, adds the `u_type` Alias literal for non-DataType targets, and builds `^UnionAll(queries=$selects)` under a `^TableAlias(name='union', ...)` root (pureToSQLQuery.pure:2755-2770). It is registered for `concatenate_T_MANY__T_MANY__T_MANY_` at pureToSQLQuery.pure:10004.

**Risk** — The new arm must be strictly narrower than the existing generic native-call arm: gate on `nc.info().type() instanceof Type.ClassType` so scalar and TDS concatenates are untouched. Distributing the union eagerly could regress `testConcatenateDataType*` / `testConcatenateClass` (already-failing siblings in functions/tests) if their filtered-nav concatenates start routing here — they are `filter`-position, not root-position, so the ClassType+anchored gate should exclude them, but check. Tenet-2 trap: do NOT teach the harness to special-case `$result.values` over a JSON list — the union set-op is the platform's shape.

**Also unblocks** — Possibly the other bare-class concatenate shapes in functions/tests (testConcatenateInQualifierWithComplexReturnType, testConcatenateFlatWithOtherProperty) — but those are filter/qualifier-position, so treat as unproven.

**Falsifier** — Print the generated SQL for `Product.all()->concatenate(Product.all())`. If it is NOT a `list_concat(...)` over two JSON scalar subqueries — e.g. if it already contains `UNION ALL` and the failure is elsewhere — this diagnosis is wrong. Equivalently: if adding the arm at StoreResolver.java:477 does not change the emitted SQL, the concatenate is not reaching that switch at all.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:355 — `case TypedProject p when classConcatOf(p.source()) != null ->` : the ONLY class-concat arm, and it requires a project above
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1196 — `classConcatOf` returns the call when both args are `anchored()`; nothing else calls it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:477 — the catch-all `case TypedNativeCall nc -> structural(...)` that a bare concatenate lands on
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1635 — `for (String f : Pure.nativeKeysAt("concatenate")) { RULES.put(f, (n,args) -> ... new SqlExpr.Call(SqlFn.LIST_CONCAT, args)` — the rule that emitted list_concat
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1630 — comment: "the relation overload is the TypedConcatenate set-op and never reaches scalar lowering"
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testConcatenate.pure:39 — the test; its golden SQL is a `union all` of the two productTable selects wrapped in `unionalias_0` with a `'0' as u_type` column

</details>

---

## `testAssociationWithProjectionHandlingDups`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | medium |

**Root cause**

`Person.all()->filter(p | $p.firm.employees->exists(e | ...))->project(...)` has a TWO-HOP exists head (`firm.employees`). legend-lite's correlated-EXISTS material is registered per-path in `StoreResolver.registerExistsSubs`, and that loop hard-skips any path longer than one hop unless it is a `filterTwoHop`: `boolean filterTwoHop = path.size() == 2 && filterPaths.contains(path); if ((path.size() != 1 && !filterTwoHop) || existsSubs.containsKey(head)) continue;` (StoreResolver.java:2120-2123). The separate dotted two-hop registration at StoreResolver.java:1595-1642 only fires its `ChainedExists.explodedTwoHop` arm when `midToMany` is true — i.e. when the FIRST hop is to-many (StoreResolver.java:1615-1621). Here the first hop is `Person.firm`, which is `[1]`, so `midToMany` is false and control falls to the `chain`-based arm, which `continue`s out when `assocs.get("firm")` yields no AssocSub. With no `ExistsSub` registered under the dotted key, `Substitution.rewriteCallArms` finds nothing at either lookup site (`headPath.size()==1 && existsSubs.containsKey(head)` at Substitution.java:815-819, or `headPath.size()>=2 && existsSubs.containsKey(String.join(".",headPath))` at Substitution.java:866-872) and leaves the `exists` as an ordinary Pure collection call. Generic lowering then hands it to the DuckDB list-lambda idiom — `DuckDb.listExists` -> `listPredicate` -> `coalesce(list_bool_or(list_transform(coll, e -> pred)), false)` (DuckDb.java:190-202, lambda rendering at DuckDb.java:158-162) — and the predicate body for `$e.locations.placeOfInterest.name` is a correlated scalar subquery. DuckDB refuses subqueries inside lambda bodies: 'Binder Error: subqueries in lambda expressions are not supported'.

**Fix**

Two changes, both in the platform:

1. PRIMARY — teach `StoreResolver` to build the exists material for a two-hop head whose FIRST hop is to-one. In the dotted-path loop at StoreResolver.java:1595-1642, the `midToMany == false` case must not fall through to a `continue`: a to-one mid hop is exactly the engine's shape where the exists subselect attaches to the mid hop's join target. Concretely, when `chain = assocs.get(path[0..n-1])` is null for a to-one mid hop, materialise it (register the `firm` association join first, then build the `ExistsSub` on `employees` against that target) instead of bailing; register it under the dotted key `"firm.employees"` so Substitution.java:866 finds it. Target emission is the golden at functions/tests/testExists.pure:376: `left outer join (select distinct "persontable_2".FIRMID from personTable ... where "placeofinteresttable_0".NAME = 'Broadway') as "persontable_1" on ("firmtable_0".ID = "persontable_1".FIRMID) where "persontable_1".FIRMID is not null`.

2. GUARD (do this regardless) — `Lowerer`/`DuckDb.listExists` must never emit a list lambda whose body contains a `SqlExpr.ScalarSubquery`/`SqlExpr.Exists`. Add the check where the list-predicate call is built and throw `NotImplementedException` naming the unrewritten exists head. A loud wall beats a DuckDB binder error with no provenance (TENETS: a loud wall is better than wrong rows), and it converts every future instance of this class of miss into an attributable message.

**Risk** — Registering more two-hop exists subs changes join emission for any query with a to-one mid hop under exists — check the `functions/tests` exists family for golden-SQL drift (testExistsInAbstractProperty, testNestedExistsWithExistsInAbstractProperty). The guard in (2) will turn some currently-ERROR tests into different (louder) errors; that is the intended direction, not a regression. Tenet-2 trap: do not make the harness tolerate the DuckDB lambda error.

**Also unblocks** — testNestedExistsWithExistsInAbstractProperty (functions/tests) fails with "exists/forAll predicate references column 'firm_employees', unresolvable even after isolation" — same head (`firm`->`employees`), same missing two-hop exists material, different downstream symptom. Likely the same fix.

**Falsifier** — Dump the emitted SQL for this query. If it contains no `list_transform(` / `list_bool_or(` lambda, the DuckDB message came from somewhere other than `DuckDb.listExists` and the 'exists was never rewritten' story is wrong. Second, cheaper falsifier: run `Firm.all()->filter(p|$p.employees->exists(e|$e.locations.placeOfInterest.name=='Broadway'))` (the passing one-hop sibling) and confirm it emits the semi-join — if it too emits a list lambda, hop count is not the discriminator.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:2120 — `boolean filterTwoHop = path.size() == 2 && filterPaths.contains(path);` then `if ((path.size() != 1 && !filterTwoHop) || existsSubs.containsKey(head)) continue;`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1615 — `boolean midToMany = path.size() == 2 && !(ctx.findProperty(...).multiplicity() upper == 1)` : the exploded-two-hop arm is gated on a TO-MANY first hop
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:1633 — `if (chain == null) { continue; }` : the silent bail that leaves the exists un-rewritten
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:866 — the dotted-path exists lookup `target.existsSubs().containsKey(String.join(".", headPath))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DuckDb.java:190 — `protected String listExists(List<SqlExpr> args) { return listPredicate(args, "list_bool_or", false); }` and DuckDb.java:199-202 `coalesce(list_bool_or(list_transform(...)), false)` — the lambda that DuckDB rejects
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — `testAssociationHandlingDups` and `testAssociationThreeLevelDeep` (same predicate, ONE-hop head `$p.employees`, Firm root) are absent from the failure list; only the two-hop `$p.firm.employees` form fails. That isolates hop count as the discriminator.

</details>

---

## `testInWithDynaFunction`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

`Interaction.active` is declared `Boolean[1]` (simpleTestModel.pure:258) but mapped by a dynafunction that yields VARCHAR: `active : case(equal([db]interactionTable.active,'Y'), 'true', 'false')` (relationalSetUp.pure:881). `MappingNormalizer.coerceToDeclaredNumeric` wraps any expression property mapping whose declared kind is in {Float,Integer,Decimal,Number,DateTime,StrictDate,Boolean} in `Pure.Lite.CAST_AS_DECLARED` (MappingNormalizer.java:2325-2338). The Typer turns that into a wire-flagged `TypedCast` (Typer.java:1141-1146), and `Lowerer.cast` emits a REAL SQL cast unless the engine-text funnel is active: `if (c.wire() && EngineTextBoundary.active()) return value;` (Lowerer.java:3158-3164). So under EXECUTION the cast is always emitted — including when the property read is an OPERAND of a predicate. Result: `CAST(CASE WHEN t0.active = 'Y' THEN 'true' ELSE 'false' END AS BOOLEAN) IN ('false','something')`. DuckDB then coerces the IN-list to the LHS type BOOLEAN and dies converting the string 'something'. The engine never casts in SQL — the golden is `case when "root".active = 'Y' then 'true' else 'false' end in ('false','something')`, a pure VARCHAR comparison, and the Boolean conversion happens host-side on the result set. The gating axis in legend-lite is wrong: it is 'engine-text vs execution', when the correct axis is 'result position vs operand position'.

**Fix**

Make the wire coercion positional, not funnel-scoped. In `Lowerer.cast(TypedCast, SqlExpr)` (Lowerer.java:3157) the `c.wire()` early-return currently keys off `EngineTextBoundary.active()`. Replace that with: a wire cast is emitted into SQL ONLY when the value is a select-list/result column; in every operand position (comparison, IN/membership, arithmetic, join condition, filter predicate) it erases to the bare value. The cleanest implementation is to stop emitting it in SQL at all and do it where the engine does: strip the wire cast unconditionally in `Lowerer.cast` (making the `EngineTextBoundary` special case unnecessary), and record the declared kind on the output column so `exec/` converts on decode — String -> Boolean via the engine's own rule ('true' -> true, anything else -> false; Number != 0 -> true), matching SetImplTransformers.toBoolean. That also removes the DELIBERATE divergence documented at MappingNormalizer.java:2318-2323 (legend-lite errors on 'Y' where the engine yields false).

If the full host-side decode is too large for one change, the minimum correct fix is: in `Lowerer.cast`, return `value` for `c.wire()` whenever the cast is being lowered in predicate/operand position, keeping it only for projection columns. Do NOT simply drop the Boolean entry from the coercion set at MappingNormalizer.java:2329 — that would make the projected `active` column come back as the string 'false' and break the row assert `assertSameElements([false, '4'], ...)`.

**How legend-engine does it** — The engine's declared-Boolean coercion is a HOST-side result-set transformer, never SQL: /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-base/legend-engine-core-executionPlan-execution/legend-engine-executionPlan-execution/src/main/java/org/finos/legend/engine/plan/execution/result/transformer/SetImplTransformers.java:61-77 — `private Boolean toBoolean(Object o) { ... else if (o instanceof String) { return Boolean.parseBoolean((String) o); } else if (o instanceof Number) { return ((Number) o).intValue() != 0; } }`, wired in from RelationalResult.java:290 (`setTransformers.add(new SetImplTransformers(transformerInputs))`).

**Risk** — Erasing the cast in operand position changes the SQL type of any comparison against a coerced property — e.g. `$i.active == true` would become VARCHAR vs BOOLEAN. Handle that by coercing the LITERAL side to the mapping's SQL kind rather than the property side (which is also what H2 does implicitly). Moving the coercion host-side touches every declared-vs-column mismatch in the corpus (String-declared over numeric columns, DateTime over StrictDate); stage it behind the existing `castAsDeclared` node so the blast radius is one lowering rule. Tenet-2 trap: do not fix this by making the row comparator in the harness accept 'false' == false.

**Falsifier** — Print the SQL for this query. If the `IN` list is not preceded by `CAST(... AS BOOLEAN)` — i.e. the cast is somewhere else entirely — this diagnosis is wrong. Alternatively, grep the emitted SQL for `AS BOOLEAN)`: the failure detail already shows it verbatim, so the cheapest falsifier is to confirm the cast disappears when `EngineTextBoundary.enter()` is active (the toSQLString funnel) — if it does not, the wire flag is not reaching this node.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2325 — `static ValueSpecification coerceToDeclaredNumeric(...)`, set includes "Boolean", returns `new AppliedFunction(Pure.Lite.CAST_AS_DECLARED, ...)` at 2336
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1141 — `if (Pure.Lite.CAST_AS_DECLARED.equals(af.function())) { yield new TypedCast(ta.args().get(0), tr.target(), out, true); }` (the wire flag)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:3158 — `if (c.wire() && EngineTextBoundary.active()) { return value; }` — cast survives whenever the boundary is inactive, i.e. always at execution
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/EngineTextBoundary.java:1-38 — "Execution lowering keeps those casts (DuckDB does not wire-convert)" — the deliberate decision that is wrong in operand position
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:881 — `active : case(equal([db]interactionTable.active,'Y'), 'true', 'false')`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:258 — `active : Boolean[1];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testIn.pure:202 — golden SQL has NO cast: `... case when "root".active = 'Y' then 'true' else 'false' end in ('false', 'something') ...`

</details>

---

## `testIsolationOfFiltersWithoutAlias`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | L |
| confidence | low |

**Root cause**

Under `MappingWithLiteral`, `Person.lastName` is mapped to the bare literal `'Smith'` with no table alias (advancedRelationalSetUp.pure:401-403), so the qualified property `employeeByLastName('Smith')` = `$this.employees->filter(e|$e.lastName == $lastName)->toOne()` (simpleTestModel.pure:56) substitutes to a filtered navigation whose predicate is the alias-free constant `'Smith' = 'Smith'`. legend-engine isolates such a filter INTO the join ON clause and lets the to-many join fan out — golden (testFilters.pure:99): `from firmTable as "root" left outer join personTable as "persontable_0" on ("root".ID = "persontable_0".FIRMID and 'Smith' = 'Smith') left outer join addressTable ...`, 7 result rows. legend-lite instead produced a correlated SCALAR SUBQUERY for the navigation and DuckDB raised 'More than one row returned by a subquery used as an expression'. The emitting site is the 'single-column RELATION consumed in SCALAR position' arm of `Lowerer.scalar`: for a to-one-stamped relation it yields `new SqlExpr.ScalarSubquery(relation(rel))` (Lowerer.java:2786-2787), whose own comment states the intent — 'A TO-ONE read is the correlated scalar subquery (value-position filtered navigation): DuckDB raises on more than one row (pure toOne semantics)' (Lowerer.java:2748-2751). `employeeByLastName` returns `Person[0..1]`, so `isToOne()` holds, `toMany` is false, and that branch fires. That policy is wrong for a mapped relational navigation: the engine does not enforce `toOne()` in SQL, it joins. The normal path for such chains is the synthetic-head lift (`SyntheticHeads` mints `head#fN` and parks the predicate on the join target — SyntheticHeads.java:37-46); this chain did not take it. The discriminator against the PASSING sibling `testIsolationOfFiltersWithoutAliasWithChainedJoins` (same mapping, same alias-free literal predicate, not in failing.tsv) is that here the filtered nav is followed by a SECOND association hop (`.address.name`) and the same head is consumed twice with different leaves.

**Fix**

The value-position filtered navigation `$f.employees->filter(...)->toOne().address.name` must lift to a synthetic filtered head (`employees#f0`) with the predicate parked in the join ON, and the chained `address` hop must join off that head's target — NOT be encoded as a correlated scalar subquery. Two parts:

1. In `StoreResolver`'s filtered-nav lift, ensure a filtered head whose predicate contains NO reference to the target row's columns (here `'Smith' = 'Smith'` after literal-mapping substitution) is still eligible for the `#fN` lift and still mints a DISTINCT identity per distinct predicate — result3 of the test proves the engine mints two person aliases (`persontable_0` for the 'Roberts' filter, `persontable_1` for 'Smith'). A closed/constant predicate must be parked, not treated as 'no correlation, therefore subquery'.

2. In `Lowerer.scalar` (Lowerer.java:2779-2787), the `!toMany -> ScalarSubquery` branch is the wrong default for a MAPPED relational navigation. `toOne()` on a mapped navigation is a model-layer multiplicity assertion; legend-engine erases it in SQL. Either (a) restrict this branch to genuinely non-navigational relation values, or (b) at minimum, when the relation is a navigation frame that the resolver already has a join identity for, resolve through the join instead of the subquery. Do NOT 'fix' this by setting `scalar_subquery_error_on_multiple_rows=false` — that returns a random row (silently wrong).

**Risk** — Loosening the ScalarSubquery branch affects every value-position filtered navigation in the corpus; the branch is load-bearing for genuine to-one encodings (its own comment cites 'the correlated-scalar route serves [0..1] nav encodings'). Fix it upstream in the lift, not by weakening the lowering default, or a class of currently-correct to-one reads becomes fan-out. Tenet-2 trap: the test asserts 7 rows including duplicates — do not de-duplicate in the harness to make counts line up.

**Also unblocks** — Possibly testIsolatioWhereNoConstaintsAndInnerJoin (functions/tests/projection, FAIL: 4 rows instead of 7 — the same missing fan-out through a chained join) — unproven, different symptom.

**Falsifier** — Print the SQL legend-lite generates for `Firm.all()->project([f|$f.employeeByLastName('Smith').address.name, f|$f.employeeByLastName('Smith').firstName],['address','employeeFirstName'])` under `MappingWithLiteral`. If it contains no `(SELECT ...)` in the select list — i.e. the multi-row subquery comes from somewhere else, or the join was emitted and the error is from a different node — this diagnosis is wrong. That same dump immediately tells you WHICH node produced it and turns this from low to high confidence. Second probe: run the identical query under `simpleRelationalMapping` (where `lastName` maps to a real column); if it also emits a scalar subquery, the literal mapping is NOT the discriminator and the defect is the general filtered-nav-then-association-hop shape.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2748 — comment: "A TO-ONE read is the correlated scalar subquery (value-position filtered navigation): DuckDB raises on more than one row (pure toOne semantics)"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2786 — `if (!toMany) { yield new SqlExpr.ScalarSubquery(relation(rel)); }` — the exact emission the DuckDB error names
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2779 — `boolean toMany = ... || !(rel.info().multiplicity() instanceof Multiplicity.Bounded mb1 && mb1.isToOne());` — `Person[0..1]` makes this false
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/SyntheticHeads.java:37 — "SYNTHETIC HEAD identities — filtered navigations lift to head#fN chains (predicate parked for the join target)" — the path that should have been taken
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/join/advancedRelationalSetUp.pure:401 — `scope([dbInc]default.personTable) ( lastName : 'Smith' )`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testModel/simpleTestModel.pure:56 — `employeeByLastName(lastName:String[1]){$this.employees->filter(e|$e.lastName == $lastName)->toOne()}:Person[0..1];`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testFilters.pure:99 — golden: left outer join with `and 'Smith' = 'Smith'` in the ON, 7 rows expected — proof the engine does NOT enforce toOne here
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — `testIsolationOfFiltersWithoutAliasWithChainedJoins` (same mapping, same alias-free literal predicate, single leaf hop) is absent from the failure list

</details>

---

## `testGet` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `tests/mapping` |
| sweep status | ERROR |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | medium |

**Root cause**

NOTE the brief mis-attributes this: the failing test is `meta::relational::tests::mapping::dates::datetime::testGet` (tests/mapping/dates.pure:65), not `dynajoin::testGet` — the dynajoin query contains no settlementDateTime at all, while dates.pure:71 is `assertSize($result.values.settlementDateTime->filter(a|$a <= now()),13)`, which the platform's `$result.values` splice re-executes as SQL (StatementExecutor.java:2425-2431, 'The TYPED splice — rides the inliner's per-node hook: $r.values'), giving exactly `WHERE t0.settlementDateTime <= now()` on line 3 (`t0` alias from Lowerer.java:284 `return "t" + aliasCounter++;`). Mechanism: DuckDB's `now()` is TIMESTAMP WITH TIME ZONE; `settlementDateTime` binds as TIMESTAMP_NS (legend-lite deliberately binds nanosecond-precision timestamps that way — DuckDb.java:23-41 `timestampLit`), and DuckDB 1.5 refuses the implicit TIMESTAMP_NS <-> TIMESTAMPTZ comparison. This is a backend type-system difference from the engine's H2, not a semantic defect. IT IS ALREADY FIXED IN THE WORKTREE: commit 41dbd8c0 ('Goal #18: DuckDB dialect gaps — acos/asin domain, now() TZ, len(DOUBLE)') added `DuckDb.call`'s `if (c.fn() == SqlFn.NOW) return "CAST(now() AS TIMESTAMP)";`. The sweep behind this brief predates that commit.

**Fix**

No code change. Re-run `meta::relational::tests::mapping::dates::datetime::testGet` against HEAD (which already contains 41dbd8c0) and retire it from the ledger. If it still fails after the re-run, the residual is precision, not zone: `CAST(now() AS TIMESTAMP)` is microsecond-precision while the column is TIMESTAMP_NS — DuckDB casts implicitly across timestamp precisions, but if it does not, change `DuckDb.call`'s NOW arm (DuckDb.java:67) to `CAST(now() AS TIMESTAMP_NS)`. Separately, fix the brief-generation script: it resolves failing tests by SHORT NAME and silently picks one of several candidates (four `testGet`s and six `testQuery`s exist in tests/mapping alone) — it must carry the FQN the runner reports.

**Risk** — None from the existing fix; it is dialect-local and the engine-text goldens that pin bare `now()` render through EngineStyleH2 (EngineStyleH2.java:1449, 1504), not DuckDb.

**Also unblocks** — testQuery (dates::datetime::testQuery) — identical cause. Also, from the same commit and the same stale sweep: tests/query testFilterUsingArcCosFunction, tests/query testFilterUsingArcSinFunction, tests/mapping/sqlFunction testProject.

**Falsifier** — Re-run the test at HEAD. If the SQL in the failure still reads `<= now()` rather than `<= CAST(now() AS TIMESTAMP)`, then `SqlFn.NOW` is not reaching `DuckDb.call` on this path (e.g. the value-splice lowers through a different renderer) and the whole 'already fixed' conclusion is wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DuckDb.java:66 — `if (c.fn() == com.legend.sql.SqlFn.NOW) { return "CAST(now() AS TIMESTAMP)"; }` with the comment 'DuckDB returns TIMESTAMPTZ; the engine's H2 returns a plain (session-local naive) TIMESTAMP, and DuckDB 1.5 refuses implicit TIMESTAMP_NS<->TZ comparison'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:514 — `case NOW -> "now()";` — the bare spelling the DuckDb override now intercepts (DuckDb.call runs before super.call; dispatch is AnsiSqlRenderer.java:321 `case SqlExpr.Call c -> call(c, parentPrec)`)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DuckDb.java:23-41 — `timestampLit` binds sub-microsecond literals as `TIMESTAMP_NS '...'`, which is why the column side is TIMESTAMP_NS
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:284 — `return "t" + aliasCounter++;` — confirms `t0.` in the failing SQL is legend-lite's own lowering, so the SQL came from the normal pipeline via the values-splice
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/dates.pure:71 — `assertSize($result.values.settlementDateTime->filter(a|$a <= now()),13);`
- git log in the worktree: `41dbd8c0 Goal #18: DuckDB dialect gaps — acos/asin domain, now() TZ, len(DOUBLE)` is the 7th commit from HEAD (HEAD = 9d1f2cd0)
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv still lists `testFilterUsingArcCosFunction` ('Invalid Input Error: Unable to compute acos of 1.1'), `testFilterUsingArcSinFunction`, and `tests/mapping/sqlFunction testProject` ('No function matches ... len(DOUBLE)') — all three fixed by that same commit, proving the sweep predates it

</details>

---

## `testQuery` — ✅ NOW PASSES (fixed upstream; diagnosis retained for the record)

| | |
|---|---|
| family | `tests/mapping` |
| sweep status | ERROR |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | medium |

**Root cause**

Same mechanism and same already-landed fix as testGet. NOTE the brief mis-attributes this too: the failing test is `meta::relational::tests::mapping::dates::datetime::testQuery` (tests/mapping/dates.pure:76), not `inheritance::relational::union::testQuery` — the inheritance-union query is `Person.all()->filter(p|$p.roadVehicles->exists(r|$r.id == 1))` and mentions no settlementDateTime. dates.pure:82 is `execute(|Trade.all()->filter(i|$i.settlementDateTime <= now()), ...)`, which lowers to the multi-line Trade class-envelope SELECT whose WHERE lands on line 8, and the `t0.settlementDateTime IS NOT NULL AND ...` prefix is legend-lite's optional-operand null guard for the `[0..1]` property (the comparison-site null tolerance registered in Scalars.java's family table). DuckDB then refuses TIMESTAMP_NS vs TIMESTAMP WITH TIME ZONE. Fixed at HEAD by commit 41dbd8c0 via `DuckDb.java:66-68`.

**Fix**

No code change. Re-run `meta::relational::tests::mapping::dates::datetime::testQuery` at HEAD and retire it. Same fallback as testGet if a residual precision mismatch appears: widen the NOW arm at DuckDb.java:67 to `CAST(now() AS TIMESTAMP_NS)`. Also fix the brief generator to carry FQNs (see testGet).

**Also unblocks** — testGet (dates::datetime::testGet) — same commit, same line.

**Falsifier** — Re-run at HEAD; if the emitted SQL still contains bare `now()`, the DuckDb override is not on this lowering path and the conclusion is wrong.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DuckDb.java:66 — the `SqlFn.NOW -> CAST(now() AS TIMESTAMP)` override already present in the worktree
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/dates.pure:82 — `let result2 = execute(|Trade.all()->filter(i|$i.settlementDateTime <= now()), simpleRelationalMapping, ...); assertSize($result2.values, 13);`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/inheritance/testInheritanceRelationalUnion.pure:47 — the fqn the brief names is `Person.all()->filter(p|$p.roadVehicles->exists(r|$r.id == 1))`, which cannot produce `settlementDateTime` — the attribution is a short-name collision
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/dossiers/tests__mapping.md:89 — lists `tests/mapping/dates.pure:76  fqn meta::relational::tests::mapping::dates::datetime::testQuery` among six `testQuery` candidates for the one failing row
- /Users/neemsandv/.claude/jobs/5671074c/tmp/burndown/failing.tsv — the sibling fixes from commit 41dbd8c0 (acos/asin/len(DOUBLE)) are still listed as failures, dating the sweep before that commit

</details>

---

## `testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter`

| | |
|---|---|
| family | `tests/mapping/classMappingFilterWithInnerJoin` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

The query is Person.all()->project([p|$p.firm.employees->filter(...).lastName]) — it never reads Firm.legalName. legend-engine's golden for the Firm union is `select "root".ID as ID_0, null as ID_1 from FirmSet1 as "root" union all …` — ID columns only. We emit `SELECT t5.name AS legalName, t5.ID AS ID_0, NULL AS ID_1` because UnionSynthesis.synthMemberUnion projects every property in `common` (here Firm[FirmSet1]/{legalName} -> FirmSet1.name) into every arm regardless of demand, and SubselectPrune never prunes set-operation branches. `FirmSet1.name` does not exist at run time: the family's BeforePackage setUp (testClassMappingFilterWithInnerJoin.pure:25-36) runs classMappingFilterWithInnerJoin::mapping::createTablesAndFillDb (which calls union::createTablesAndFillDb -> `Create Table FirmSet1(id, name, NICKNAME)`) and THEN merge::setUp -> `Create Table FirmSet1(ID INTEGER, LegalName VARCHAR(200))`, so the live FirmSet1 has no `name`. The engine shares one test H2 database the same way and survives only because it never selects `name`. The DuckDB message wording ('Referenced table "t5" not found! Candidate tables: "t4"') is not an alias bug: this repo's own probe log records DuckDB 1.5.0 reporting a MISSING COLUMN inside a nested union-join subquery as a bogus missing-table error (docs/UPSTREAM_DEFECTS.md:51, U18).

**Fix**

In core/src/main/java/com/legend/lowering/SubselectPrune.java, give the `SqlSource.Subselect` arm of `rewriteSource` (line 275) a union branch: when `inner instanceof SqlUnion u`, all branches are `SqlSelect` with equal, non-empty projection counts, and the existing guards hold (`!r.starred().contains("*")`, `!r.starred().contains(sub.alias())`), compute the kept POSITIONS from the union's output names — position i is kept iff `u.outputs().get(i).name()` is in `r.cols().get(sub.alias())` or in `r.unqualified()` — then rebuild every branch with only those positions (`s.withProjections(keptPs, keptOutputs)`) and rebuild the SqlUnion with the narrowed `outputs()`. Keep at least one position (an empty SELECT list is not SQL), and drop positionally regardless of the projection's expression kind (a union arm's slot may be `NULL AS ID_1`, not a plain Column — unlike pruneProjections' plain-column-only rule, which must NOT be reused here because it would desynchronise the arms). Add a same-shape guard: bail if any branch is itself a SqlUnion or has distinct/groupBy/having/qualify. Do NOT fix this by reordering or isolating the harness's setup stream — legend-engine shares one test database and runs the same clobbering setUp order; compensating there is tenet-2 harness compensation and would hide the real over-projection.

**How legend-engine does it** — legend-engine emits the demanded columns only: the same union in the same mapping renders as `left outer join (select "root".ID as ID_0, null as ID_1 from FirmSet1 as "root" union all select null as ID_0, "root".ID as ID_1 from FirmSet2 as "root") as "unionalias_1"` — legend-engine/…/core_relational/relational/tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:84 (the assertSameSQL golden).

**Risk** — Pruning changes union `outputs()`, which the plan-text/result-column readers consult; keep the root query untouched (the existing pass already excludes it) and narrow branch and union outputs together. Advisory golden-SQL diffs move (toward the engine, since the engine also omits these). Tenet-2 trap: the tempting 'fix' here is the harness (per-test sessions / setup reordering / a private session router) — that is compensation, the engine has the same clobber.

**Also unblocks** — testProjectAndFilterSamePropertySameJoinInUnion (this unit) and likely other union-family tests whose arms carry undemanded columns; it is also the pass that keeps a store-declared-but-physically-absent column out of every union arm.

**Falsifier** — Run only this test with LEGEND_LITE_DUMP_SQL=1 (core/src/main/java/com/legend/exec/Executor.java:151 honours it) and read the emitted branch at line 16: if its FROM clause binds an alias DIFFERENT from the one its projections use (i.e. `FROM FirmSet1 AS t4` under `SELECT t5.name …`), then this is a genuine alias-generation bug and not a missing column, U18 does not apply, and pruning legalName will not fix it (t5.ID would still be unbound).

<details><summary>Evidence read (11 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1036-1048 — `common` is built by iterating every member's `pp.fields().keySet()` and keeping each scalar property; nothing consults query demand
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1104-1136 — per-arm loop `for (String prop : common) { … cols.add(new ColSpec(prop, …)) }`: every arm projects every common property
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SubselectPrune.java:275-286 — the Subselect arm prunes only when `inner instanceof SqlSelect`; a `SqlUnion` inner falls through with the comment 'set-operation inner: branches are positional — never pruned themselves'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SubselectPrune.java:21-39 — the pass's own rationale: ours 'enumerated the whole schema, which … BREAKS on corpus stores whose model declares columns the physical table never carries'
- …/core_relational/relational/tests/mapping/classMappingFilterWithInnerJoin/testRelationalSetUp.pure:184-195 — Firm[FirmSet1] maps `legalName: [unionDBWithInnerJoinFilter]FirmSet1.name`, Firm[FirmSet2] `FirmSet2.name`; those are the only scalar properties, so `common` = {legalName}
- …/tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:25-36 — the BeforePackage setUp calls mapping::createTablesAndFillDb() and then meta::relational::tests::mapping::merge::setUp() in that order
- …/tests/mapping/merge/testMerge.pure:60-62 — merge::createTables executes `Drop table if exists FirmSet1; Create Table FirmSet1(ID INTEGER, LegalName VARCHAR(200));`
- …/tests/mapping/union/testUnion.pure:440-441 — union::createTablesAndFillDb executes `Create Table FirmSet1(id INT, name VARCHAR(200), NICKNAME VARCHAR(200));` (the shape our SQL assumes)
- …/tests/mapping/classMappingFilterWithInnerJoin/testClassMappingFilterWithInnerJoin.pure:80-84 — the engine golden's Firm union arm is `select "root".ID as ID_0, null as ID_1 from FirmSet1 as "root"`: no legalName
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/UPSTREAM_DEFECTS.md:51 — U18: DuckDB 1.5.0 reports a missing COLUMN inside a nested union-join subquery as `Referenced table "tN" not found! Candidate tables: "tM"` (repo probe log T5Probe/T5Bisect; 1.1.3 reports honestly)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1752-1766 — the harness already records the same clobber story ('FirmSet1 (id,name,NICKNAME) clobbered to (ID,LegalName) … surfaced by DuckDB 1.5 as a bogus table t5 not found')

</details>

---

## `testProject`

| | |
|---|---|
| family | `tests/mapping/sqlFunction` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | medium |

**Root cause**

This is meta::relational::tests::mapping::sqlFunction::stringToFloat::testProject (testSqlFunctionsInMapping.pure:396-404), whose assertion is `[123.456, 100.001]->zip($result.values->cast(@TabularDataSet)->at(0).rows.values)->forAll(pair|assertEqWithinTolerance(...))`. The harness compiles that whole statement through the platform (EngineTestExecutor.evalSpliced -> Compiler.executeResolved), so `zip` lowers to SQL: Scalars.java:1146-1171 emits `least(coalesce(len(a),0), coalesce(len(b),0))` and Spellings.java:83 spells LIST_LENGTH as DuckDB `len`. Side `a` is the array literal (fine: len(ANY[])). Side `b` is the flat-cells read of the executed TDS, and it lowers to a BARE SCALAR SUBQUERY of type DOUBLE, hence `len(DOUBLE)`. The mechanism: the Typer erases `.rows` to an identity marker carrying the SOURCE's multiplicity (Typer.java:2418-2427) and returns the source unchanged for `.values` on a relation (Typer.java:2429/2463), and StatementExecutor's splice collapses `$r.values->at(0)` to the exec frame's chain with the chain's own stamp (StatementExecutor.java:2549-2571). A relation VALUE is stamped [1] (a project's out multiplicity — ProjectChecker.java:67), so in Lowerer.scalar's single-column relation-in-scalar-position arm `toMany` computes false (Multiplicity.isToOne() is upper==1 — Multiplicity.java:104-106) and it yields `new SqlExpr.ScalarSubquery(relation(rel))` instead of the LIST-aggregating `ValueCollections.columnList` route. The multi-column arm right above it already refuses to trust that stamp ('no toOne carve-out — a [1] stamp on a relation VALUE is the value's mult, not the row count'); the single-column arm still trusts it. Even if `len` bound, the bare subquery would raise 'more than one row' on this 2-row TDS.

**Fix**

Make the flat-cells read honest instead of patching zip. Preferred: mirror the existing `.rows` marker discipline for `.values` on a relation — in Typer.java:2429-2463 return `new TypedPropertyAccess(source, "values", new ExprType(rt2, Multiplicity.Bounded.ZERO_MANY))` instead of bare `source`; erase that marker beside the ROWS_MARKER erasures (StoreResolver.java:322-327 and StatementExecutor.java:2490-2495) and add two Lowerer arms: in relation() erase it to `relation(pa.source())` (beside Lowerer.java:495-499), and in scalar() lower it as the VALUE COLLECTION — `ValueCollections.rowMajorCellList` for >1 column, `ValueCollections.columnList` for exactly one — never the correlated-scalar route. Minimal alternative if the marker plumbing is too invasive: in Lowerer.java:2779-2785 force `toMany` for a relation whose stamp is exactly [1] (lower==1 && upper==1), leaving the correlated-scalar route to the [0..1] nav encodings the comment names — one line, but it changes every exactly-[1] single-column relation in scalar position, so it needs a corpus sweep. Do NOT 'fix' this by wrapping zip's arg in an ArrayLit the way the covar rules do: for this input that would produce a 1-element list containing a 2-row scalar subquery — a runtime 'more than one row' error and, worse, a silently truncated zip.

**Risk** — Both variants change how a single-column relation is consumed in scalar position (contains/in/makeString/zip consumers). The [0..1] nav encodings must keep the correlated-scalar route or filtered navigation regresses to lists; the marker variant is safe there because it only re-routes the explicit `.rows.values` spelling. After the fix the rest of the expression must still lower (list_transform over range + StructGet first/second at Lowerer.java:3014-3029, forAll -> LIST_FOR_ALL at Scalars.java:334) — plausible but unverified.

**Also unblocks** — Any corpus assert that consumes `$result…rows.values` of a ONE-column TDS inside a scalar function (zip/contains/in/makeString); the multi-column spelling already works, which is why this is nearly a singleton.

**Falsifier** — Run this one test with LEGEND_LITE_DUMP_SQL=1 and look at the second `len(` argument: if it is a list-producing expression (list aggregate / array) rather than a bare `(SELECT … )` scalar subquery, the diagnosis is wrong and the offending `len(DOUBLE)` is on the literal side instead.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1146-1171 — the zip rule: `count = LEAST(COALESCE(LIST_LENGTH(a),0), COALESCE(LIST_LENGTH(b),0))`, matching the failure text `…]), 0), coalesc…`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/Spellings.java:83 — `m.put(SqlFn.LIST_LENGTH, "len")`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2758-2795 — the `TypedSpec rel` scalar arm: multi-column always `rowMajorCellList`; single column takes `ScalarSubquery(relation(rel))` when `mb1.isToOne()`, else `ValueCollections.columnList`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2418-2427 and :2429-2463 — `.rows` becomes an identity marker with `source.info()`; `.values` on a relation returns `source` unchanged (identity), so the [1] stamp survives into lowering
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2549-2571 — `$r.values->at(0)` over a relation-rooted frame returns `spliced` (the chain), discarding at()'s own stamp
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/type/Multiplicity.java:104-106 — `isToOne() { return upper != null && upper == 1; }` — [1] passes
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2666-2679 — evalSpliced wraps the statement in a LambdaFunction and runs Compiler.executeResolved: assertion expressions really do become SQL
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1785-1790 — the corr/covar family already wraps a to-one side in an ArrayLit before len(); zip has no such wrap
- …/core_relational/relational/tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:396-404 — the test body: `[123.456, 100.001]->zip($result.values->cast(@TabularDataSet)->at(0).rows.values)->forAll(...)`

</details>

---

## `testProjectAndFilterSamePropertySameJoinInUnion`

| | |
|---|---|
| family | `tests/mapping/union` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

Same defect as test 1, with the honest DuckDB message. The query projects lastName, otherNames, firm.legalName, extraInformation — never firstName. But Person[set1] and Person[set2] of unionMappingWithSameJoinInPropertyInBothUnions both map `firstName : [myDB]PersonMaster.firstName`, so UnionSynthesis's `common` includes firstName (UnionSynthesis.java:1036-1048) and each union arm emits it (`:1104-1136`), giving `SELECT t0.firstName AS firstName, t0.lastName AS lastName, t1.extr…` with t0 = PersonMaster, t1 = PersonAdditional. The store DECLARES PersonMaster(ID, firstName, lastName, FirmID) (testUnion.pure:764-771) but the corpus's own setup creates `PersonMaster (ID INT, lastName VARCHAR(200), FirmID INT)` (testUnion.pure:496) — no firstName column, hence 'Table "t0" does not have a column named "firstName" … Candidate bindings: "lastName"'. SubselectPrune would have removed the undemanded projection, but it bails on set-operation inners (SubselectPrune.java:275-286). legend-engine's golden union base for this exact test carries only extrainfo/FirmID_0/FirmID_1/pk_0_0/pk_0_1/lastName/otherName — no firstName.

**Fix**

The SubselectPrune union-branch prune described for test 1 — identical change, no second edit needed. With it, the union base loses `firstName` (unreferenced under the union alias anywhere in the tree) and the arms bind cleanly against the physical PersonMaster. If you would rather fix it upstream, the alternative is to make the union extent demand-driven in the resolver (project only the properties the resolved query reads); that is a much larger change and cuts against the TENETS split (mapping normalization is eager Knowledge; demand is Work), so the SQL-exit prune is the right site.

**How legend-engine does it** — legend-engine's own golden for this test (testUnion.pure:342) shows the union base with only the demanded columns — the engine's relational plan projects per demand, never the class mapping's full property set.

**Risk** — Same as test 1: keep branch outputs and SqlUnion.outputs() in sync, bail when the union alias is star-read. After the fix the test may still fail on row/column ORDER (its expected rows are ['Scott, GDPR Redacted, Firm X, Not Available', …] and the engine golden's own header naming for this test is skewed) — that would be a new, separate finding, not this one.

**Also unblocks** — testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter (this unit).

**Falsifier** — Dump the SQL (LEGEND_LITE_DUMP_SQL=1) for this test: if the union base's first branch does NOT contain a `firstName` projection, the over-projection diagnosis is wrong and the column must be coming from somewhere else (e.g. a pk/witness column named firstName).

<details><summary>Evidence read (8 citations)</summary>

- …/core_relational/relational/tests/mapping/union/testUnion.pure:1225-1241 — Person[set1] and Person[set2] both declare `firstName : [myDB]PersonMaster.firstName`
- …/tests/mapping/union/testUnion.pure:496 — `Create Table PersonMaster (ID INT, lastName VARCHAR(200), FirmID INT);` — the physical table has no firstName
- …/tests/mapping/union/testUnion.pure:764-771 — the store's `Table PersonMaster (ID INT PRIMARY KEY, firstName VARCHAR(200), lastName VARCHAR(200), FirmID INT)`: declared but never created
- …/tests/mapping/union/testUnion.pure:342 — the engine golden's union base projects only extrainfo, FirmID_0/1, pk_0_0/1, lastName, otherName
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1036-1048 — `common` = every member's scalar property, demand-blind
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1104-1136 — each arm emits a ColSpec per `common` property (String values additionally wrapped in cast+toOne, which lower to a plain column read)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SubselectPrune.java:275-286 — union inners are never projection-pruned
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/SubselectPrune.java:303-317 — pruneProjections' guards and its plain-Column-only rule (the reason a union arm needs its own positional variant)

</details>

---

## `testCollectionDistinctFunction`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **REAL DEFECT** |
| effort | M |
| confidence | high |

**Root cause**

The query root is `Trade.all()->filter(...)->map(t|$t.product.name)->distinct()`. At G-phase, `DistinctChecker.check` (core/src/main/java/com/legend/compiler/spec/DistinctChecker.java:34-37) sees arg0's type is NOT a RelationType (at that point it is still `String[*]` off a class stream, pre-Phase-H) and therefore emits the *collection* overload as a plain TypedNativeCall — correct at G. Nothing downstream ever re-decides it after Phase H turns the stream into a relation. In `Lowerer.lower(TypedSpec)` (core/src/main/java/com/legend/lowering/Lowerer.java:204-256) the root arms are: TypedFrom unwrap, TypedSerializeGraph, TypedConcatenate, `spec.info().type() instanceof Type.RelationType`, and the `TypedMap` over-a-relation single-column-projection arm. A `distinct` TypedNativeCall matches NONE of them (its result type is String[*], and it is not a TypedMap), so it falls through to `scalarRoot(spec)` (Lowerer.java:262-278). scalarRoot calls `scalar(...)`, which routes the `distinct` call to `Scalars.RULES.get(Pure.DISTINCT_COLLECTION_KEY)` = `orderedDedup(args.get(0))` (Scalars.java:1383-1384, 2428-2436). orderedDedup builds `list_filter(<list>, (_ddx,_ddi) -> list_position(<list>, _ddx) = _ddi)`. Its `<list>` argument is the scalar lowering of the inner TypedMap-over-relation, which is a `SqlExpr.ScalarSubquery(select list(value) from (<proj>) t)` (Lowerer.java:2695-2730). So the emitted lambda BODY contains a subquery — and DuckDB's binder rejects exactly that, with the observed message. legend-lite already knows this failure mode: ValueCollectionOps.java:19-21 says verbatim that 'the list-space rules would re-embed the list subquery inside a SQL lambda, which DuckDB's binder rejects', but its relation-space escape hatch (`relationSpaceRewrite`, ValueCollectionOps.java:63-83) is registered only for `removeDuplicates` and `sort`, and only in SCALAR position, never for `distinct` and never at the query root.

**Fix**

Add a root arm to `Lowerer.lower(TypedSpec spec)` (core/src/main/java/com/legend/lowering/Lowerer.java), placed after the TypedFrom/TypedSerializeGraph/TypedConcatenate unwraps and BEFORE the `TypedMap` arm at :230, so the recursive call reuses that arm:

```java
// ->distinct()/->removeDuplicates() at the ROOT over a RELATION-derived
// collection IS relation-level SELECT DISTINCT (engine processDistinct:
// ^$mergedSQL(distinct = true)), never a list dedup — the list rule
// re-embeds the collect subquery inside a SQL lambda, which DuckDB's
// binder rejects (same reason as ValueCollectionOps' scalar-position
// rewrite). Phase G could not decide this: at G the source is still a
// CLASS stream, so DistinctChecker correctly picked the collection
// overload; relation-ness only exists after Phase H.
if (spec instanceof TypedNativeCall dc && dc.args().size() == 1
        && (Pure.nativeNamed("distinct", dc.callee().signatureKey())
            || Pure.nativeNamed("removeDuplicates", dc.callee().signatureKey()))
        && relationRootedCollection(dc.args().get(0))) {
    SqlQuery q = lower(dc.args().get(0));
    if (q instanceof SqlSelect s) {
        return Fold.distinctFolds(s) ? s.withDistinct()
                                     : isolate(s).withDistinct();
    }
    if (q instanceof SqlUnion u) {   // UNION already dedups
        return new SqlUnion(u.branches(), false, u.outputs());
    }
}
```

with the gate (a private static helper next to it):

```java
/** The arg of a root dedup lowers through a RELATION arm of lower():
 *  a relation-typed spec, or the relation->map(row|scalar) projection. */
private static boolean relationRootedCollection(TypedSpec a) {
    return a.info().type() instanceof Type.RelationType
        || (a instanceof TypedMap m
            && m.source().info().type() instanceof Type.RelationType);
}
```

Everything referenced already exists: `Fold.distinctFolds(SqlSelect)` at lowering/Fold.java:371, `SqlSelect.withDistinct()` at sql/SqlSelect.java:87, `isolate(...)` and `union(...)` in Lowerer, `Pure.nativeNamed` as used at ValueCollectionOps.java:71 and InnerDemand.java:513. Nothing else must change: the `Scalars` DISTINCT_COLLECTION_KEY rule MUST stay for genuinely list-valued arguments (`[1,1,2]->distinct()`, split results), which is precisely why the new arm is gated on relation-rootedness rather than replacing the rule. Expected emission becomes `select distinct <expr> as "value" from tradeTable as "root" left outer join ... where "accounttable_0".name = 'Account 2'` — the `as "value"` alias is legend-lite's standing root-projection convention (Lowerer.java:236 names the TypedFuncCol "value"), already tolerated on other passing root-map goldens because EngineTestExecutor.sqlTextVerify falls back to H2 row-replay for divergent text (harness/EngineTestExecutor.java:1008-1012).

**How legend-engine does it** — legend-engine registers `meta::pure::functions::collection::distinct_T_MANY__T_MANY_` to `processDistinct` — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:9974. `processDistinct` (same file :8171-8180) processes the nested value spec into a SelectSQLQuery and rebuilds it as `^$mergedSQL(distinct = true)` — a relation-level SELECT DISTINCT. There is no list/lambda dedup anywhere on the relational path; the collection overload and the TDS overload (:10239) and the Relation overload (:10305) all point at the same processDistinct.

**Risk** — Two things to watch. (1) The collection-mapper sub-case: when the inner TypedMap has a collection-valued mapper, the TypedMap arm returns `select unnest(value) from (proj)` — applying DISTINCT there gives `select distinct unnest(...)`, which is semantically right (Pure flattens then dedups) but should be spot-checked on DuckDB; if it binds badly, isolate first and put DISTINCT on the outer wrapper. (2) Do NOT weaken the gate to 'any many-multiplicity arg' — that would hijack literal-list and split-result dedups away from orderedDedup and silently change first-occurrence ordering semantics. Tenet-2 trap to avoid: it is tempting to make the harness tolerate the binder error or special-case this assert; the defect is squarely in `lowering/`, which the platform owns — fix it there and nowhere else.

**Also unblocks** — Any other corpus query whose ROOT is a class-rooted collection followed by `->distinct()` or `->removeDuplicates()` (e.g. the `Firm.all()->filter(...).legalName->distinct()` / `...->distinct()->take(2)` shapes in tests/advanced/testRelationalResultSourcing.pure) would take the same new arm. I did not verify which of those are currently failing, so I am not claiming them.

**Falsifier** — Dump the generated SQL for this one test (e.g. via the toSQLString path or an LL SQL-print switch, no full build needed if a targeted single-test run is permitted). If the text does NOT contain `list_filter(` with a `list_position(` inside its lambda, this diagnosis is wrong and the binder error comes from some other lambda site.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1383 — `RULES.put(Pure.DISTINCT_COLLECTION_KEY, (n, args) -> orderedDedup(args.get(0)));` the collection `distinct` overload is unconditionally a LIST rule.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2428 — `orderedDedup` returns `SqlExpr.Call(SqlFn.LIST_FILTER, [list, Lambda([_ddx,_ddi], LIST_POSITION(list,_ddx) = _ddi)])`; the SAME `list` expression is embedded inside the lambda body.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2695 — the `TypedMap m2 when m2.source().info().type() instanceof Type.RelationType` scalar arm yields `new SqlExpr.ScalarSubquery(agg)` where agg is `select list(sub.value) from (<proj>) sub` — i.e. the `list` fed to orderedDedup IS a subquery.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:204 — `lower(TypedSpec)`; its arms (TypedFrom, TypedSerializeGraph, TypedConcatenate, RelationType-typed, TypedMap-over-relation) contain no `distinct` arm, so the call reaches `return scalarRoot(spec);` at line 256.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/ValueCollectionOps.java:19 — class javadoc: 'The list-space rules would re-embed the list subquery inside a SQL lambda, which DuckDB's binder rejects (corpus testIsNotEmpty).' Same mechanism, already diagnosed for a sibling.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/ValueCollectionOps.java:71 — `relationSpaceRewrite` matches only `removeDuplicates` and `sort`; `distinct` is absent.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/DistinctChecker.java:34 — `if (!(a.args().get(0).info().type() instanceof Type.RelationType)) { return Typer.emitCall(...); }` — the G-phase fork that sends this call down the collection path.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/InnerDemand.java:503 — legend-lite ALREADY does the right thing for INNER chains: 'a trailing ->distinct() (the NATIVE-CALL spelling at this stage) rides OUTSIDE the resolved relation as a relation-level DISTINCT', wrapping the resolved relation in `new TypedDistinct(rel0, List.of(), rel0.info())` at :516-521. Only the ROOT lacks this.

</details>

---

## `testFilterUsingArcCosFunction`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Two stacked causes; the outer one is a legend-lite invention, the inner one is the actual wall.

OUTER (what produced this exact message): legend-lite does not lower `acos` to a bare `acos(...)`. Scalars.java:1578-1590 wraps it in a Pure-runtime domain guard: `CASE WHEN abs(cast(x as double)) > 1 THEN error('Unable to compute acos of ' || <floatRepr>) ELSE acos(x) END` (`guarded` at Scalars.java:2862 builds the CASE + `SqlFn.ERROR`; `SqlFn.ERROR` spells DuckDB's `error` at dialect/Spellings.java:68, and DuckDB's `error()` raises with the transport prefix 'Invalid Input Error: '). The query is `Trade.all()->filter(t | $t.id->divide(10)->acos() < 0.5)`; trade id 11 gives 1.1, the guard fires, the whole statement aborts. That guard was added deliberately — docs/PCT_BURNDOWN.md 'Slice 13' names 'sqrt/asin/acos domain guards' as a PCT error-parity win.

INNER (why removing the guard does not fix the test): the corpus golden expects bare `acos((1.0 * "root".ID) / 10)` and expects trade 11 to be silently excluded — that is H2/Java `Math.acos` semantics, which returns NaN out of domain, and NaN < 0.5 is false. DuckDB 1.5.0.0 does NOT do that: its ACOS operator raises. I verified the literal in the shipped native library.

**Fix**

Do not chase this test green; ledger it as an execution-target artifact. There is no correct change that makes it pass on DuckDB, because the assertion `assertSameElements([9, 10], $result.values.id)` depends on H2/Java returning NaN for acos(1.1) and DuckDB refusing to.

There IS a separate, real, small correction to make while you are here, and it should be made on its own merits rather than for this test: DELETE the acos/asin (and sqrt) domain guard from the relational lowering — Scalars.java:1569-1590 — so `acos` lowers to a bare `SqlExpr.Call.of(SqlFn.ACOS, args.get(0))` matching legend-engine's `acos(%s)`. That (a) makes the emitted text match the corpus golden `acos((1.0 * "root".ID) / 10)`, (b) removes a legend-lite-only semantic that legend-engine explicitly does not have, and (c) realigns legend-lite's PCT scoreboard with legend-engine's own manifest, where `testArcCosineError`/`testArcSineError`/`testSquareRootError` are expected failures on every relational adapter. Expect the PCT count to drop by those 3 tests; that drop is CORRECTNESS, not regression — pin them as expected failures mirroring relational-duckdb/EssentialFunctions_manifest.json.

What you must NOT do unless you consciously choose target-compensation over engine parity: rewrite the guard to yield NaN instead of raising (`CASE WHEN abs(x) > 1 THEN 'nan'::DOUBLE ELSE acos(x) END`). That would turn this test green (DuckDB orders NaN above all values, so `nan < 0.5` is false and trade 11 drops, giving [9,10]) but it invents a DuckDB emulation of H2's libm that legend-engine has nowhere, and it re-diverges the golden text. If you take it, take it as an explicit, documented dialect-level IEEE-domain compensation in DuckDb.java, not as a lowering rule — and only after deciding legend-lite's row reference is H2 rather than 'whatever the target does'.

**How legend-engine does it** — legend-engine pushes `acos` down verbatim, with no guard: pureToSQLQuery.pure:10214-area registers the trig natives to `processDynaFunction`, and sqlQueryToString/extensionDefaults.pure:187 is `dynaFnToSql('acos', $allStates, ^ToSql(format='acos(%s)'))`. Its DuckDB dialect is likewise bare — legend-engine-xt-relationalStore-duckdb/.../duckDBSqlDialect.pure:434 `pair(Acos, simpleFunctionProcessor('acos'))`. Decisively, legend-engine records the Pure domain-error PCT tests as EXPECTED FAILURES on relational adapters: relational-h2/EssentialFunctions_manifest.json:511-513 lists `testArcCosineError_Function_1__Boolean_1_` with `"expectedError": "No error was thrown"`, and relational-duckdb/EssentialFunctions_manifest.json:322-323 lists the same test with `"expectedError": "Unexpected error executing function with params [Anonymous_Lambda]"`. Real engine's relational path deliberately does not reproduce Pure's acos domain error on either target.

**Risk** — Removing the guard drops 3 PCT tests (testArcSineError, testArcCosineError, testSquareRootError) — legend-lite's PCT gate is quoted as 1109/1109 in PCT_BURNDOWN.md, so the gate must be re-pinned in the same commit or CI will read it as a regression. Also check no other corpus test currently relies on the guard's message. Tenet-2 trap: the tempting move here is to teach the harness to accept 'Invalid Input Error: Unable to compute acos of 1.1' as a pass, or to skip the test. Both are harness compensation for a platform-owned shape; a loud ledger entry is the honest outcome.

**Also unblocks** — testFilterUsingArcSinFunction — identical mechanism, same code path (Scalars.java:1578 loops over both names). The sqrt guard at Scalars.java:1569-1577 is the same class of invention but is not currently biting any test in this unit (sqrt of positive trade ids stays in domain).

**Falsifier** — Run `SELECT acos(1.1);` on DuckDB 1.5.0.0. If it returns NaN rather than raising 'ACOS is undefined outside [-1,1]', then the inner cause is gone, the verdict flips to REAL_DEFECT, and simply deleting the Scalars.java:1578-1590 guard fixes the test outright.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1578 — `for (String name : List.of("acos", "asin")) { ... guarded(GREATER(ABS(cast x as DOUBLE), 1), cat("Unable to compute " + name + " of ", floatRepr(x)), Call.of(fn, args.get(0))) }` — the message in the failure detail is composed here, character for character.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2862 — `guarded(cond,msg,value)` = `new SqlExpr.Case([When(cond, Call.of(SqlFn.ERROR, msg))], value)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/Spellings.java:68 — `m.put(SqlFn.ERROR, "error");` so the guard renders as DuckDB's `error(...)`, whose thrown class prints 'Invalid Input Error: <msg>'.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/docs/PCT_BURNDOWN.md:470 — 'Slice 13 (DATABASE-RAISED error parity ...): sqrt/asin/acos domain guards ("Unable to compute sqrt of -1.0", float print via floatRepr)'. The guard is intentional, not accidental.
- /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-compiled/src/main/java/org/finos/legend/pure/runtime/java/compiled/CoreHelper.java:146 — `throw new PureExecutionException(sourceInformation, "Unable to compute acos of " + input, ...)`. This message belongs to the PURE in-memory runtime, not to the relational push-down path.
- DuckDB 1.5.0.0 native library (~/.m2/repository/org/duckdb/duckdb_jdbc/1.5.0.0/duckdb_jdbc-1.5.0.0.jar, libduckdb_java.so_osx_universal) — `strings` shows the literals 'ACOS is undefined outside [-1,1]' and 'ASIN is undefined outside [-1,1]'. DuckDB raises out-of-domain; it does not return NaN. So removing legend-lite's guard only swaps which error is thrown.

</details>

---

## `testFilterUsingArcSinFunction`

| | |
|---|---|
| family | `tests/query` |
| sweep status | ERROR |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | XS |
| confidence | high |

**Root cause**

Identical to testFilterUsingArcCosFunction, same loop body. Query is `Trade.all()->filter(t | $t.id->divide(10)->asin() < 0.5)`; trade id 11 gives 1.1. legend-lite lowers `asin` through `Scalars.java:1578-1590`, which wraps it in `CASE WHEN abs(cast(x as double)) > 1 THEN error('Unable to compute asin of ' || floatRepr(x)) ELSE asin(x) END` (`guarded`, Scalars.java:2862; `SqlFn.ERROR` -> DuckDB `error`, Spellings.java:68), so the statement aborts with 'Invalid Input Error: Unable to compute asin of 1.1'. Underneath that, DuckDB 1.5.0.0's own ASIN operator also refuses out-of-domain input ('ASIN is undefined outside [-1,1]', verified as a literal in the shipped native library), whereas the corpus's H2 reference uses Java Math.asin and returns NaN, and NaN < 0.5 is false, so trade 11 is silently filtered out and the expected result is [1,2,3,4]. Removing legend-lite's guard therefore only exchanges one error for another; it does not produce the asserted rows.

**Fix**

Same as testFilterUsingArcCosFunction — one edit covers both. Ledger this test as an execution-target artifact (its expected rows require H2/Java NaN semantics that DuckDB refuses to provide). Separately and on its own merits, delete the asin/acos/sqrt domain guards at Scalars.java:1569-1590 so the natives lower to bare `SqlExpr.Call.of(SqlFn.ASIN, args.get(0))` / `SqlFn.ACOS` / `SqlFn.SQRT`, matching legend-engine's `asin(%s)`; re-pin testArcSineError / testArcCosineError / testSquareRootError as expected PCT failures mirroring legend-engine's relational-duckdb manifest. Do not add a NaN-emulating CASE in the lowering; if the project ever decides its row reference is H2 rather than the live target, that compensation belongs in DuckDb.java as an explicit, documented IEEE-domain shim, not in Scalars.

**How legend-engine does it** — legend-engine pushes `asin` down bare: pureToSQLQuery.pure:10214 registers `meta::pure::functions::math::asin_Number_1__Float_1_` to `processDynaFunction`, and sqlQueryToString/extensionDefaults.pure:190 renders it `^ToSql(format='asin(%s)')`; the DuckDB dialect is duckDBSqlDialect.pure:435 `pair(Asin, simpleFunctionProcessor('asin'))`. legend-engine's own PCT manifests record the Pure domain-error test as an expected failure on relational adapters — relational-h2/EssentialFunctions_manifest.json:514-515 `testArcSineError_Function_1__Boolean_1_` with `"expectedError": "No error was thrown"`, and relational-duckdb/EssentialFunctions_manifest.json:325-326 with `"expectedError": "Unexpected error executing function with params [Anonymous_Lambda]"`.

**Risk** — Same as the acos entry: the PCT scoreboard drops 3 tests and must be re-pinned in the same commit, otherwise the change reads as a regression. Tenet-2 trap: do not teach the harness to swallow this error or to skip the test — the shape is owned by lowering/ and dialect/.

**Also unblocks** — testFilterUsingArcCosFunction — same loop, same guard, same DuckDB wall.

**Falsifier** — Run `SELECT asin(1.1);` on DuckDB 1.5.0.0. If it returns NaN instead of raising, the verdict flips to REAL_DEFECT and deleting the Scalars.java:1578-1590 guard alone makes the test pass.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:1578 — `for (String name : List.of("acos", "asin")) { SqlFn fn = name.equals("acos") ? SqlFn.ACOS : SqlFn.ASIN; ... guarded(...) }` — one loop emits both guards; the asin message is built here.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2862 — `guarded` = `Case([When(cond, Call.of(SqlFn.ERROR, msg))], value)`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/Spellings.java:68 — `m.put(SqlFn.ERROR, "error")`.
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/math/trigonometry/asin.pure:32-34 — `testArcSineError` is a `<<PCT.test>>` asserting `assertError(| $f->eval(|2.0->asin()), 'Unable to compute asin of 2.0')`; this is the PCT test legend-lite's guard was built to satisfy.
- /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-compiled/src/main/java/org/finos/legend/pure/runtime/java/compiled/CoreHelper.java:131 — `throw new PureExecutionException(sourceInformation, "Unable to compute asin of " + input, ...)`; the message lives in the Pure in-memory runtime only.
- DuckDB 1.5.0.0 native library (libduckdb_java.so_osx_universal inside duckdb_jdbc-1.5.0.0.jar) — contains the literal 'ASIN is undefined outside [-1,1]', so bare `asin(1.1)` raises on this target too.

</details>

---
