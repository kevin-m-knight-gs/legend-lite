# Bucket 8 — Lowering gap (I-phase)

7 tests from the ledger; **7 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: MISSING FEATURE 7

---

## `testSortByLambdaDeepOptional`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The failure is in the SECOND assert, not the query. The assert is `zip($result.values.address.name, zip($result.values.firstName, $result.values.lastName))->map(pair | $pair.first + '|' + $pair.second.first + '|' + $pair.second.second)`. The harness substitutes `$result` with the execute chain, so the resolver sees a relational `zip(...)->map(...)`. StoreResolver.resolveNode (StoreResolver.java:308-319) routes any TypedMap whose source is a 2-arg `meta::pure::functions::collection::zip` to CorrelatedSubselects.zipPairMap, which is the ONLY zip implementation in the codebase: it demands that BOTH arguments are 'scalar projections of the same class chain' (a TypedMap with a non-class result, or a bare property access off a class collection — zipSide, CorrelatedSubselects.java:549-583) and that their sources are value-equal, then rewrites the pair into a two-column TypedProject. Here arg1 is the INNER `zip(...)` TypedNativeCall, which zipSide does not recognise at all, so zipPairProject returns null and zipPairMap THROWS (CorrelatedSubselects.java:513-521). Crucially, widening zipSide to accept a nested zip would still be WRONG: `$result.values.address.name` has 11 elements (one of the 12 persons has no address) while the inner zip has 12 pairs, and pure's zip truncates to the shorter input — a two-/three-column relational project over Person yields 12 rows with one NULL name, so the assert's 11-element expected list would never match. The two-column-project trick can only model zip when both sides are [1] reads off the SAME row; this test is a genuinely positional, length-truncating host zip.

**Fix**

Give zip a shape that preserves pure's positional-with-truncation semantics instead of the same-row projection trick. Two viable routes:
(A) RELATIONAL, recommended: generalise CorrelatedSubselects.zipPairProject into a ROW-NUMBER positional INNER join. Resolve each zip argument independently into its own relation (a single-column projection for a scalar chain; for a nested zip, recursively into its own 2-column relation), wrap each in `SELECT *, ROW_NUMBER() OVER () AS rn`, and INNER JOIN on rn. The INNER join reproduces pure's truncate-to-shorter exactly, and ROW_NUMBER over the already-ordered subquery preserves position. Name the nested zip's columns so that `$pair.second.first` resolves — e.g. flatten to columns `first`, `second$first`, `second$second`, and teach the pair-field reader to walk the dotted names. SqlAgg.Fn.ROW_NUMBER already exists (core/src/main/java/com/legend/sql/SqlAgg.java:27) and the window machinery is in place (core/src/main/java/com/legend/lowering/Windows.java:41).
(B) HOST: execute each zip argument as its own query and pair the materialised lists in the K/exec layer, with `.first`/`.second` reads resolved against the host pair value. Semantically simplest, but legend-lite deliberately has no interpreter over Result values (see the RESULT class comment at core/src/main/java/com/legend/builtin/Pure.java, 'reads over it rewrite into SQL-bound queries — no interpreter, tenet #1'), so this route contradicts the existing architecture.
Whichever route, the throw at CorrelatedSubselects.java:517 should stay as the floor for shapes the new machinery still cannot express — a loud wall is correct here; what is NOT acceptable is widening zipSide to accept the nested zip and emitting a 3-column same-row project, which would silently return 12 mis-paired rows.

**How legend-engine does it** — legend-engine runs this assert in the Pure interpreter over the materialised Result values, where `meta::pure::functions::collection::zip` is a host list operation that truncates to the shorter input. The truncation is directly observable in the test itself: /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/functions/tests/testSort.pure:84-85 (assertSize 12 vs an 11-element zip expectation).

**Risk** — Route (A) changes the emitted SQL for every currently-passing zip assert (the flat two-scalar case would gain a ROW_NUMBER join it does not need). Keep the existing same-source fast path and use the ROW_NUMBER form only when the sources differ or a side is itself a zip. Tenet-2 trap: do NOT special-case this assert in EngineTestExecutor/ReflectAsserts — zip is a platform native and the platform owns it.

**Falsifier** — Delete the second assertEquals from a local copy of the test (or replace the nested zip with the flat `zip($result.values.firstName, $result.values.lastName)` used by the sibling passing tests) and re-run. If it still errors, the zip is not the cause and the query/sortBy is. Separately: if a probe shows `$result.values.address.name` returning 12 values (with a NULL) rather than 11, the truncation argument against the projection trick is wrong and the cheaper widening becomes viable.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:513-521 — zipPairMap: `if (zp == null) throw new NotImplementedException("zip over inputs that are not two scalar projections of the SAME class chain has no relational shape");`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:524-547 — zipPairProject: `Object[] a = zipSide(args.get(0)); Object[] b = zipSide(args.get(1)); if (a == null || b == null || !a[0].equals(b[0])) return null;` then builds a TypedProject of columns 'first'/'second'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:549-583 — zipSide accepts only TypedMap-with-scalar-result or a scalar TypedPropertyAccess over a ClassType source; a TypedNativeCall zip falls to `return null`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:308-319 — the only dispatch site for zip in the whole resolver
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:2083 — `zip<T,U>(set1:T[*], set2:U[*]):Pair<T,U>[*]` is the only zip in the catalog; there is no host-side implementation anywhere (grep for `functions::collection::zip` returns exactly these two Java sites)
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/functions/tests/testSort.pure:85 — the assert's expected list has 11 entries while line 84 asserts `assertSize($result.values, 12)`, proving zip truncates against a shorter navigated list

</details>

---

## `testSubAggregationWithDeepAndOverlap`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The column is `col(f:Firm[1]|$f.employees->map(e|2 + $e.locations.place->count()),'c')`. Its typed body is TypedMap(source=$f.employees, mapper=λe. 2 + count($e.locations.place)). In CorrelatedSubselects.aggScan the aggregate arms all require `Substitution.pathOf(nc.args().get(0), userVar)` to be non-null, where userVar is the OUTER project-lambda parameter `f`. Here the count's argument is rooted at the inner map parameter `e`, so pathOf returns null (Substitution.java:709 — the walk only follows toOne/auto-map/milestoned steps down to `new TypedVariable(userVar)`), every aggregate arm is skipped, and even the `path == null && containsToManyCrossing(..., userVar, ...)` loud-fallthrough guard does not fire because the crossing is under `e`, not `f`. The aggregate demand is therefore DROPPED. Control then reaches the VALUE-POSITION fan-out arm (CorrelatedSubselects.java:2166), which sees sp=["employees"] is a to-many head and registers `consumedPaths(mapper body, "e")` as BARE demands. StoreResolver.consumedPaths (StoreResolver.java:3048) does not skip aggregate arguments, so it yields ["locations","place"] and the arm bare-demands ["employees","locations","place"] — a FLAT left join through locationTable instead of a grouped subselect. With no AggDemand registered, the `count(...)` node is never rewritten into a grouped-subselect column read; it survives substitution as an ordinary scalar native and reaches Lowerer's scalar path, where Scalars.lower (Scalars.java:2381-2387) throws because `count` is registered only as an aggregate reducer (Aggregates.java:33 `family(SqlAgg.Fn.COUNT, "count")`) and has no entry in Scalars.RULES.

**Fix**

Three coordinated changes in the resolver.
(1) CorrelatedSubselects.aggScan (core/src/main/java/com/legend/resolver/CorrelatedSubselects.java, the VALUE-POSITION fan-out arm at line 2166): before harvesting `consumedPaths` as bare demands, recursively aggScan the mapper body with the mapper's OWN parameter as userVar against the ClassSource of the map's element class (Person, obtained the same way `buildAggMaterials` obtains `midAj.target()`), and register any demands it finds under the DOTTED chain key `sp.get(0) + "." + innerHead` (here "employees.locations"). Then EXCLUDE the paths consumed inside those aggregate arguments from the bare fan-out set, so `employees.locations.place` never becomes a flat join demand.
(2) CorrelatedSubselects.buildAggMaterials (line 100-122): the dotted-key branch currently assumes the mid hop is a to-ONE association it must materialize privately. Add the case where the mid head is ALREADY a demanded to-many slot on the pipe: reuse that slot's existing prefix rather than calling `assocMaterial.aggJoinMaterial` for the mid.
(3) CorrelatedSubselects.foldChainMid (line 141-186): it unconditionally mints a chain-private mid prefix (`midBase = head-before-dot + "_" + realHead(chainFinal) + "_mid"`) and emits a second TypedJoin. When the mid slot is shared (the OVERLAP the test name refers to — column 'b' already explodes `employees` into `persontable_0`), skip the extra TypedJoin and re-point the final hop's parent-side condition onto the EXISTING slot prefix. The golden proves the sharing: `left outer join personTable as "persontable_0" on (root.ID = persontable_0.FIRMID) left outer join (select persontable_2.ID as ID, count(locationtable_0.PLACE) as aggCol from personTable persontable_2 left outer join locationTable locationtable_0 … group by persontable_2.ID) as "persontable_1" on (persontable_0.ID = persontable_1.ID)` — ONE personTable explode, reused as the sub-aggregation's join-back key.
If this is judged too large for the current slice, the honest interim is to make aggScan WALL loudly when an aggregate is found under an inner map parameter over a to-many head, instead of silently demoting it to a bare join (that silent demotion is a wrong-rows hazard for any query that does not happen to trip the scalar-count wall).

**How legend-engine does it** — legend-engine's relational router pushes this sub-aggregation down to the Person level and keys the grouped subselect on the person PK, joining back to the already-exploded employees row. The shape is pinned by the test's own golden at /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/functions/tests/projection/testAggregation.pure:141 (the assertEquals SQL for testSubAggregationWithDeepAndOverlap).

**Risk** — Change (1) must not double-register: the same aggregate must not also appear in `bareOut`, or the flat locationTable join stays and rows explode (expected row count is 7). Change (3) touches the shared chain-agg path used by the passing `count($p.firm.employees)` family — the private-mid behaviour must remain the default and slot reuse must be opt-in on "the mid head is already a materialised to-many slot". Tenet-2 trap: do NOT teach the harness to special-case this column shape, and do NOT register `count` in Scalars.RULES to silence the wall — a scalar COUNT over an exploded flat join would return the wrong number silently.

**Also unblocks** — testSubAggregationWithDeepAndOverlap_WithColVar (same three columns; it needs this fix PLUS its own cast-peel fix before it can even reach here)

**Falsifier** — Set a breakpoint / println in CorrelatedSubselects.aggScan at the top of the aggregate branch (line ~1969) and in the fan-out arm (line 2166) while typing this query. If aggScan is entered for the `count` node with a non-null `path`, or the fan-out arm does NOT add ["employees","locations","place"] to bareOut, this diagnosis is wrong. Equivalently: dump `aggDemands` at StoreResolver.java:2787 — if it contains any entry keyed on "employees" carrying the count node, the diagnosis is wrong.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2384 — `throw new IllegalStateException("no scalar lowering registered for resolved overload '" + call.callee().qualifiedName() + "' with " + …parameters().size() + " parameter(s)")`, reached from `Scalars.lower` when `RULES.get(call.callee().signatureKey())` is null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Aggregates.java:33 — `family(SqlAgg.Fn.COUNT, "count");` registers count in REDUCERS only; I read all of Scalars.java's `family(SqlFn…)` registrations (lines 275,287,310,311,312,333,334,1592,2011) and there is no count/size rule
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1965-2158 — aggScan: every aggregate arm is guarded by `path != null` where `path = Substitution.pathOf(nc.args().get(0), userVar)`; the final `path == null` guard only walls when `containsToManyCrossing(nc.args().get(0), userVar, cs, toManyHead)` holds for the OUTER var
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2166-2181 — the VALUE-POSITION fan-out arm: for `TypedMap` over a to-many head it calls `StoreResolver.consumedPaths(b, mv, mp)` on the mapper body and adds head+path to `bareOut`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:3048-3062 — consumedPaths recurses through ALL children with no aggregate-call exclusion, so `count($e.locations.place)` contributes ["locations","place"]
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/Substitution.java:709-740 — pathOf bottoms out at a TypedVariable named userVar; a path rooted at a different variable returns null
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:100-122 — buildAggMaterials: a dotted `mid.final` key materializes the mid hop via `assocMaterial.aggJoinMaterial(temporal, cs, mid, …)` and anchors the final aggregation at `midAj.target()` — the closest existing shape to what this test needs

</details>

---

## `testSubAggregationWithDeepAndOverlap_WithColVar`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The test binds `let cols = [col(f:Firm[1]|…,'a'), col(…,'b'), col(…,'c')]->cast(@BasicColumnSpecification<Firm>)` and then calls `project($cols)`. The harness substitutes the let (HarnessSubstitution.substitute, the `case Variable var when lets.containsKey(var.name())` arm), so the columns argument arriving at ProjectChecker is `AppliedFunction("cast", [PureCollection[col(...)…], @BasicColumnSpecification<Firm>])`. ProjectChecker.check first tries `t.rawSchemaErasedExpansion(af.parameters().get(1))`, which returns null immediately because `cast` has a NATIVE overload (Typer.java:1358 `if (arityCands.stream().anyMatch(TypedFunction::isNative)) return null;`). normalizeLegacyForms then falls through all three arms: ps.size()==2 but ps.get(1) is neither a LambdaFunction, nor `isLegacyColumnCall` (its function is "cast", not "col"/"pathWithAlias"), nor a PureCollection — so it returns `af` unchanged. withMappedColumns then hits its `default ->` arm and throws "project expects ~[…] column specifications" (ProjectChecker.java:264). The cast wrapper is a pure type ascription with no runtime effect, so it is a shape gap, not a semantic one.

**Fix**

In ProjectChecker.check (core/src/main/java/com/legend/compiler/spec/ProjectChecker.java, right after the FQN canonicalization at line 44 and before the rawSchemaErasedExpansion attempt), peel a type-ascription wrapper from the columns position:

    if (af.parameters().size() >= 2
            && af.parameters().get(1) instanceof AppliedFunction cw
            && (cw.function().equals("cast") || cw.function().equals("to") || cw.function().equals("toMany"))
            && cw.parameters().size() == 2
            && cw.parameters().get(1) instanceof com.legend.protocol.TypeExpression te
            && te.typeName().endsWith("ColumnSpecification")) {
        List<ValueSpecification> np = new ArrayList<>(af.parameters());
        np.set(1, cw.parameters().get(0));
        af = af.withParameters(np);
    }

Keep the guard NARROW (target type must be in the ColumnSpecification family) so an unrelated cast in columns position still walls. This is exact: real pure's `cast` is a type assertion with no runtime effect, and `BasicColumnSpecification<T>` is the declared element type of the col() literals already in the collection.
NOTE: this fix alone does NOT make the test pass — after peeling, the query is byte-identical to testSubAggregationWithDeepAndOverlap and hits that test's deep sub-aggregation gap. Both fixes are required.

**How legend-engine does it** — legend-engine's ProjectChecker (whose desugar legend-lite's normalizeLegacyForms mirrors, per the class javadoc at ProjectChecker.java:22-28) types the columns argument against `BasicColumnSpecification<T>[*]`, so a cast to that exact type is a no-op ascription; the two-arg TDS project over a ColumnSpecification collection is the same entry point the literal spelling uses.

**Risk** — Peeling any cast in columns position would hide genuine type errors and could swallow a cast whose target is a different relation shape — hence the ColumnSpecification-family guard. No tenet-2 trap: this is compiler-side desugar, not harness rewriting.

**Falsifier** — Print `af.parameters().get(1).getClass()` at ProjectChecker.java:44 for this test. If it is a Variable rather than an AppliedFunction named "cast", the harness did not substitute the let and the fix must instead resolve the let binding — but the wall message is identical in both cases, and the peel is still needed for the cast.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/ProjectChecker.java:264 — `default -> throw new TypeInferenceException("project expects ~[…] column specifications");` inside withMappedColumns, which only accepts ColSpec / ColSpecArray
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/ProjectChecker.java:104-165 — normalizeLegacyForms: the ps.size()==2 arms test `ps.get(1) instanceof LambdaFunction || isLegacyColumnCall(ps.get(1))` and `ps.get(1) instanceof PureCollection`; nothing peels a wrapper call
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/ProjectChecker.java:167-178 — isLegacyColumnCall accepts only `col` (2 or 3 params) and `pathWithAlias`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1348-1360 — rawSchemaErasedExpansion returns null when any arity-matching candidate `isNative()`, which `cast` is
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/HarnessSubstitution.java:67-68 — `case Variable var when lets.containsKey(var.name()) -> substitute(lets.get(var.name()), lets);` so `$cols` is inlined before typing
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/CoreFn.java:81 — `CAST("cast")`, i.e. cast reaches the checker as an AppliedFunction named "cast"

</details>

---

## `testMultiConcatenate`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The test spells `p1->concatenate([p2, p3, p4])` — the n-ary TDS concatenate. legend-lite's catalog has no `meta::pure::tds::concatenate(tds1:TabularDataSet[1], tdss:TabularDataSet[*])` overload; grep over Pure.java shows only `collection::concatenate<T>(set1:T[*], set2:T[*])` (line 1167) and `relation::concatenate<T>(Relation<T>[1], Relation<T>[1])` (line 1168). The 3-element collection therefore binds the COLLECTION overload with T = the TDS relation type, whose `a.out().type()` is a Type.RelationType, so ConcatenateChecker's guard `if (!(a.out().type() instanceof Type.RelationType)) return emitCall(...)` does NOT fire and it builds `new TypedConcatenate(a.args().get(0), a.args().get(1), a.out())` where the RIGHT child is a TypedCollection of three relations (ConcatenateChecker.java:21-25). At lowering, Lowerer.union → collectBranches (Lowerer.java:533-547) recurses only on TypedConcatenate and otherwise calls `relation(spec)`, which reaches the frontier default at Lowerer.java:524 and throws 'lowering not yet implemented for TypedCollection'.

**Fix**

In ConcatenateChecker.check (core/src/main/java/com/legend/compiler/spec/ConcatenateChecker.java), fold a collection right-hand side into a left-associative TypedConcatenate chain, mirroring the fold already used at StatementExecutor.java:2164:

    if (a.args().get(1) instanceof com.legend.compiler.spec.typed.TypedCollection tc
            && !tc.elements().isEmpty()
            && tc.elements().stream().allMatch(e -> e.info().type() instanceof Type.RelationType)) {
        ExprType one = ExprType.one(a.out().type());
        TypedSpec folded = a.args().get(0);
        for (TypedSpec e : tc.elements()) {
            folded = new TypedConcatenate(folded, e, one);
        }
        return folded;
    }

Use multiplicity ONE for the folded result (the engine's n-ary TDS concatenate returns TabularDataSet[1], not [*]) so downstream `$result.values->toOne()` behaves as in testSimpleConcatenate. Lowerer.collectBranches then flattens the nested chain into the single 4-branch SqlUnion the golden expects: `select "unionalias_0"."lastName" from (A union all B union all C union all D) as "unionalias_0"`.
A defensible alternative is to add `case TypedCollection tc -> tc.elements().forEach(e -> collectBranches(e, out));` to Lowerer.collectBranches, but the checker fix is better: it keeps the typed tree well-formed (both TypedConcatenate children are relations) for the resolver passes in Pipelines that also walk TypedConcatenate (Pipelines.java:602, 953, 988).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:480-496 — `meta::pure::tds::concatenate(tds1:TabularDataSet[1], tdss:TabularDataSet[*]):TabularDataSet[1]` is the PRIMARY definition (`let newRows = $tds1.rows->concatenate($tdss.rows)`), and line 500-503 shows the binary form delegating to it via `$tds1->concatenate($tds2->toOneMany())`. Order is tds1's rows then tdss's rows in order — exactly the left-associative fold.

**Risk** — Folding to multiplicity ONE changes the ExprType the collection overload produced ([*]); check that nothing downstream depended on the many stamp. Guard the fold on ALL elements being relation-typed so a genuine scalar-collection concatenate (Scalars.java:1630-1637 registers that overload) is never captured.

**Falsifier** — Print the concrete class of `a.args().get(1)` in ConcatenateChecker.check for this test. If it is not a TypedCollection (e.g. the overload resolver rejected the call earlier, or the collection was already flattened), this diagnosis is wrong — but the observed message names TypedCollection specifically and TypedConcatenate is the only construct that puts a collection into relation position.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:524 — `default -> throw new NotImplementedException("lowering not yet implemented for " + spec.getClass().getSimpleName() + …)`; the message carries no callee suffix, matching a TypedCollection (not a TypedNativeCall)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:533-547 — union/collectBranches: recurses on TypedConcatenate.left/right, else `out.add(relation(spec))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/ConcatenateChecker.java:17-26 — the whole checker; it never inspects args.get(1) for a collection shape
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:1167-1168 — the only two concatenate signatures in the catalog; no tds n-ary form
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2164-2170 — the existing precedent for exactly this fold: `TypedSpec folded = queries.get(0); for (int qi = 1; …) folded = new TypedConcatenate(folded, queries.get(qi), folded.info());`

</details>

---

## `testJoinStringsTypeInference`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

The receiver chain is `simpleRelationalMapping->rootClassMappingByClass(Person)->cast(@RootRelationalInstanceSetImplementation)->map(x|$x->propertyMappingsByPropertyName('firstName'))->cast(@RelationalPropertyMapping).relationalOperationElement->toOne()`. `->map(x|…)` with a literal lambda is emitted as a TypedMap (MapChecker.java:39), so StatementExecutor.planWalk dispatches it to walkMapOver → walkMapBody. walkMapBody is a NARROW whitelist of exactly three metamodel natives — `view`, `mainTable`, `resolvePrimaryKey` — and `propertyMappingsByPropertyName` falls to its `default -> null` (StatementExecutor.java:1763). Because walkMapOver only appends non-null results, the map yields an EMPTY list rather than null, and the chain silently degrades: cast passes it through, `.relationalOperationElement` over an empty list yields an empty list, `toOne()` yields the empty list. The constructed `^DynaFunction(name='joinStrings', parameters=[<that chain>, ^Literal(',')])` then fails constructOp (argOp requires a size-1 list unwrapping to a Rop — StatementExecutor.java:1730-1735), falls into the MIXED-args DynH channel, and `inferRelationalType` on a DynH returns null (MetamodelWalk.infer requires `r instanceof Rop`). planWalk returns null, the statement falls back to the ordinary SQL pipeline, and the innermost native in the chain — `rootClassMappingByClass` — is the first thing Scalars.lower cannot lower, producing the reported wall. Every other piece the test needs already exists: MetamodelWalk.propertyMappingsByName (line 1054), Pm.relationalOperationElement (line 1144), and the joinStrings inference `Varchar(4000)` (line 1275).

**Fix**

In StatementExecutor.walkMapBody (core/src/main/java/com/legend/StatementExecutor.java:1741-1764), stop hand-maintaining a three-entry whitelist and route the map-lambda body through the SAME recv-dispatched vocabulary the main planWalk switch uses. Minimal version: add

    case "propertyMappingsByPropertyName" -> mb.args().size() == 2
            && mb.args().get(1) instanceof TypedCString pn
            ? com.legend.exec.MetamodelWalk.propertyMappingsByName(e, pn.value())
            : null;

Better version (removes the whole class of gap): extract the body of the main planWalk native switch (StatementExecutor.java:1298-1416) into a `metamodelStep(String simple, Object recv, TypedNativeCall c, …)` helper and have BOTH planWalk and walkMapBody call it, so `_classMappingByClass`, `classMappingById`, `superMapping`, `inferRelationalType`, `dataTypeToSqlText`, `schema`, `table` etc. work inside a map lambda too.
Separately, make walkMapOver FLATTEN a list-valued mapper result (`if (v instanceof List<?> vl) outM.addAll(vl); else outM.add(v);`) to match pure's map semantics — today propertyMappingsByName returns a List and would nest. (walkProp happens to re-flatten it downstream, so this is hygiene, not the blocker.)
Finally, consider making walkMapOver return NULL rather than an empty list when the mapper body is unrecognised, so an unsupported step produces the honest "walk failed" fallback instead of a silently empty chain.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalExtension.pure:1012-1018 — the engine's getDynaFunctionTypeInferenceMap 'joinStrings' entry: `^Varchar(size = max([4000, $size]))`, matching the asserted VARCHAR(4000). legend-lite's fixed-4000 form at MetamodelWalk.java:1275 agrees for this input (firstName is VARCHAR(200)).

**Risk** — Widening walkMapBody to the full metamodel vocabulary could let a map lambda succeed where the walk previously failed loudly and fell back to the SQL pipeline; that is the desired direction but it changes which tests take the walk path. Keep the fallback-to-pipeline behaviour intact for any step that still returns null.

**Also unblocks** — testSQLNullWithinCaseTypeInference1 (identical map-lambda shape; that test additionally needs the type-lattice fixes listed under its own entry)

**Falsifier** — Run this test with LL_TMP_DEBUG set and confirm the `[walk]` trace shows the chain aborting at the map step. If instead the trace shows `rootClassMappingByClass` itself returning null (i.e. MetamodelWalk.mapping(ctx, 'meta::relational::tests::simpleRelationalMapping') returns null because findLegacyMapping misses), the root cause is one step earlier and this diagnosis is wrong.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1741-1764 — walkMapBody's switch has only `view`, `mainTable`, `resolvePrimaryKey`, then `default -> null`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1770-1789 — walkMapOver wraps a single handle as List.of(h) and drops null mapper results, so an unknown body silently returns an EMPTY list
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1402-1408 — the MAIN planWalk switch DOES handle `propertyMappingsByPropertyName` (recv-dispatched to MetamodelWalk.propertyMappingsByName); only the map-lambda path lacks it
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1054-1066 — propertyMappingsByName returns a List<Pm> for a Cm receiver
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1144-1157 — Pm.relationalOperationElement builds a Rop (ColumnRef for a Column property mapping)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1275 — `case "joinstrings" -> new RelationalDataType.Varchar(4000);` — the value the test asserts
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/MapChecker.java:39 — `return new TypedMap(a.args().get(0), Args.lambda(a, 1), out);` confirming the map is a TypedMap node
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1725-1735 — argOp unwraps only size-1 lists to a Rop, else null, which kills constructOp for the DynaFunction

</details>

---

## `testSQLNullWithinCaseTypeInference1`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | S |
| confidence | high |

**Root cause**

Same first blocker as testJoinStringsTypeInference: `SimpleDb1Mapping->rootClassMappingByClass(PersonTable)->cast(…)->map(x|$x->propertyMappingsByPropertyName('isValid'))` dies in StatementExecutor.walkMapBody's three-entry whitelist, the walk degrades to an empty list, and the statement falls back to the SQL pipeline where Scalars.lower walls on `rootClassMappingByClass`. BUT this test has TWO further defects behind that wall, both of which would produce a WRONG answer once the walk is fixed. The mapped expression is `case(isNull(personTable.is_valid), sqlNull(), case(equal(personTable.is_valid,1), sqlTrue(), sqlFalse()))` and the test asserts BIT. (a) MetamodelWalk.inferOp has no arm for `sqltrue`/`sqlfalse` — I read the entire FunctionCall switch (MetamodelWalk.java:1231-1313) and it covers max/min/distinct, sum/average/avg, count, a boolean-dynafunction list, sqlnull, string transforms, position/length family, sub, joinstrings, concat, case, plus/minus/times/divide, then `default -> null`. So the INNER case infers null. (b) MetamodelWalk.safe (line 1441-1481) has no rule for the engine's `Other` absorbing element: with a=Other and b=Bit it falls through every branch and returns `a` (Other). The outer case walks args[1]=sqlNull→Other first, then safe(Other, <inner>), so the result would be OTHER, printing 'OTHER' instead of 'BIT'. The sibling test testSQLNullWithinCaseTypeInference2 passes only by accident of ORDER — there the Varchar branch comes first and `return a` happens to be right.

**Fix**

Three changes.
(1) StatementExecutor.walkMapBody (line 1741-1764): add `propertyMappingsByPropertyName` (or route through a shared metamodelStep helper) — identical to the testJoinStringsTypeInference fix.
(2) MetamodelWalk.inferOp (core/src/main/java/com/legend/exec/MetamodelWalk.java, the FunctionCall switch around line 1252): add `case "sqltrue", "sqlfalse" -> new RelationalDataType.Bit();`.
(3) MetamodelWalk.safe (line 1441): add the Other-absorption rule at the top, after the null checks and before the decimal/float branches:

    if (a instanceof RelationalDataType.Other) { return b instanceof RelationalDataType.Varchar || b instanceof RelationalDataType.Char_ ? b : b; }
    if (b instanceof RelationalDataType.Other) { return a; }

…with the Varchar nuance the engine spells explicitly: when the surviving type is Char/Varchar, the size is `max(sizeOf(t1 unless Other -> 0), sizeOf(t2 unless Other -> 0))` — i.e. an Other operand contributes size 0, which is exactly what makes testSQLNullWithinCaseTypeInference2's VARCHAR(3) correct for a principled reason instead of by argument order.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/relationalExtension.pure:1637-1666 — getDynaFunctionTypeInferenceMap entries: 'sqlTrue' -> ^Bit(), 'sqlFalse' -> ^Bit(), 'sqlNull' -> ^Other(). And lines 1994-2018 (safeTypeMap): the `Other` key maps to a list containing EVERY other datatype including Bit, so getSafeType(Other, X) = X; lines 1947-1965 (getSafeType) also show the Char/Varchar size rule treating an Other operand as size 0.

**Risk** — The safe() change is order-sensitive by construction; verify testSQLNullWithinCaseTypeInference2 (currently passing) still yields VARCHAR(3) and VARCHAR(4) after the rule lands — that test relies on the Other-contributes-0 size rule. Also re-check testSQLNullTypeInference (bare sqlNull -> OTHER) is untouched: safe() is never called there.

**Falsifier** — After fixing only walkMapBody, run this test: if it passes, defects (2) and (3) are not real. If it fails with 'expected BIT, got OTHER', (3) is confirmed; if it fails with a null DataType / toOne-on-empty, (2) is confirmed first.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:1741-1764 — walkMapBody's `default -> null` for anything but view/mainTable/resolvePrimaryKey
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1231-1313 — the full inferOp FunctionCall switch; no `sqltrue`/`sqlfalse` arm, `default -> null`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1293-1302 — the `case` arm: `for (i=1; i<args.size(); i+=2) acc = safe(acc, inferOp(args.get(i)));` then the odd-size else branch — matching the engine's range/concatenate shape
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1441-1481 — safe(a,b): returns b if a==null, a if b==null, then decimal/float/varchar rules, then `return a` — no Other-absorption
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/RelOpFromProtocol.java:128-171 — dynaFunc: only and/or/isNull/isNotNull/group/comparisons are special-cased, so `sqlTrue()`/`sqlFalse()`/`case(...)` all arrive as `RelationalOperation.FunctionCall` with those names
- /Users/neemsandv/legend/legend-engine/…/core_relational/relational/tests/shared.pure:223 — the mapping under test: `isValid : case(isNull(personTable.is_valid),sqlNull(),case(equal(personTable.is_valid,1),sqlTrue(),sqlFalse()))`

</details>

---

## `testProject`

| | |
|---|---|
| family | `tests/mapping` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

FIRST: the brief's `source` field is wrong. `tests/injection/testInjection.pure:50` (meta::relational::tests::injection::testProject) contains no `sort` anywhere in its body, its mapping, or its qualified property — it cannot emit this message. The brief's `family: tests/mapping` is the correct field; the failing test is `meta::relational::tests::mapping::dates::strictdate::testProject` at `tests/mapping/dates.pure:57`. I grepped every `::testProject(` in the corpus and extracted each body: exactly two contain `sort` (dates.pure:57 and dates.pure:100), and only dates.pure:57 sorts a MULTI-column relation. `tests/injection` sorts before `tests/mapping` alphabetically, so the brief's source looks like a short-name-collision artifact that took the first match.

MECHANISM. The assert is `assertEquals([1..15, %2014-12-01 ...], $result.values.rows.values->sort())` over `project([t|$t.id, t|$t.date], ['id','date'])` — a 2-column TDS. In Pure, `TDSRow.values : Any[*]` (tds.pure:79), so `$tds.rows.values` auto-maps to the row-major concatenation of every cell into one heterogeneous `Any[*]`, and `sort()` orders it with Pure's default comparator, which is TYPE-CLASS-MAJOR: all Numbers, then Dates, then Booleans, then Strings (legend-pure Compare.java). That is exactly why the expected list is 1..15 followed by the dates.

legend-lite loses the flatten in the Typer. `Typer.java:2429` handles `.values` over a `Type.RelationType` receiver: when the receiver is a ROW VARIABLE it synthesizes the per-column cell reads, but when the receiver is a RELATION VALUE (our case: `$result.values.rows.values`) it falls through to `return source;` at Typer.java:2463 — pure identity, with the comment 'the relation's wire flatten IS row-major cell order'. The flatten is real but it happens only at the WIRE: `EngineTestExecutor.Eval.values()` concatenates `r.values()` per row for a Tabular result. So the `->sort()` sits ABOVE the identity, i.e. in RELATION space, over a 2-column relation.

The Lowerer then reaches `relation()`'s frontier default. `Lowerer.java:514` routes 1-arg `sort` only through `ValueCollectionOps.isBareSingleColumnSort`, which requires `args().size()==1 && arg type instanceof RelationType && columns().size()==1` (ValueCollectionOps.java:33-41). With 2 columns that guard is false, `relationDistinct` is false, `isRelationToOne` is false, and control lands on the default at Lowerer.java:524, which prints exactly the observed `lowering not yet implemented for TypedNativeCall ('meta::pure::functions::collection::sort' in relation position)`.

So the wall is honest: the missing surface is 'order the FLATTENED cell sequence of a multi-column relation by Pure's cross-kind comparator'. legend-lite has the flatten carrier (`ValueCollections.rowMajorCellList`, ValueCollections.java:52-68, used from the scalar-position arm at Lowerer.java:2772) and it has the Any-LUB variant literal carrier (Lowerer.java:2225-2233), but it has NO cross-kind ordering anywhere: `Scalars.mixedElems` explicitly bails when the collection LUB is neither NUMBER nor DATE (Scalars.java:2506-2511), and the only value-space `sort` rule is bare `list_sort` (Scalars.java:883-913).

**Fix**

Three coordinated edits; nothing in the harness.

(1) STOP ERASING THE FLATTEN AT THE TYPER. In `Typer.java` the `.values`-over-`Type.RelationType` arm (Typer.java:2429) currently ends `return source;` (Typer.java:2463). Replace with an identity-TYPED marker, exactly mirroring the `.rows` marker three lines above (Typer.java:2416-2422):
    return new TypedPropertyAccess(source, PlatformTypes.CELLS_MARKER, source.info());
Add `public static final String CELLS_MARKER = "values";` beside `ROWS_MARKER` in `PlatformTypes.java:23`. Because the marker keeps `source.info()` (still the RelationType), no downstream type changes.

(2) ERASE IT WHERE `ROWS_MARKER` IS ERASED, so every currently-green shape is bit-identical: add the same guard at `StoreResolver.java:321-326` and at the defensive floor in `Lowerer.relation()` (Lowerer.java:494-500), both `-> resolveNode/relation(mk.source())`.

(3) NEW RELATION-POSITION RULE, inserted immediately BEFORE the `isBareSingleColumnSort` arm at Lowerer.java:514:
    case TypedNativeCall nc when CellOrder.isCellSort(nc) -> cellSort(nc);
where `isCellSort` = 1-arg `meta::pure::functions::collection::sort` whose single arg is the CELLS marker over a `Type.RelationType` with >1 column.
`cellSort` must NOT reuse `rowMajorCellList` + `LIST_SORT`: `TO_VARIANT` renders `to_json` (DuckDb.java:262) and DuckDB JSON is text-backed, so `list_sort` would order `"2014-12-01"` before `1` and `10` before `2` — wrong rows, not a wall. Emit instead a one-column relation built from a per-column UNION ALL that carries Pure's comparator as explicit sort keys:
    SELECT to_json(v) AS "value" FROM (
        <for each column i of rt>  SELECT <pureKindRank(rt.columns[i].type())> AS _k,
                                          <col_i cast to DOUBLE, or NULL if not numeric>    AS _n,
                                          <col_i cast to TIMESTAMP, or NULL if not a date>  AS _d,
                                          <col_i cast to BOOLEAN, or NULL if not boolean>   AS _b,
                                          <col_i cast to VARCHAR, or NULL if not a string>  AS _s,
                                          col_i AS v
                                   FROM <relation(marker.source())>
        UNION ALL ... )
    ORDER BY _k, _n, _d, _b, _s
`pureKindRank` transcribes legend-pure `Compare.PRIMITIVE_TYPE_COMPARISON_ORDER` (Compare.java:51) and the branch order in `Compare.compare` (Compare.java:75-110): Integer/Float/Decimal/Number -> 0, DateTime/StrictDate/Date/LatestDate -> 1, Boolean -> 2, String -> 3. Put `pureKindRank` in one place (a new `com.legend.lowering.CellOrder`) so any future cross-kind comparator reuses it rather than re-deriving it.
The result is a single VARIANT-carrier column, i.e. exactly the carrier discipline `ValueCollections.rowMajorCellList` (ValueCollections.java:52-68) and the Any-LUB literal arm (Lowerer.java:2225-2233) already use, so the expected side of the assert (a mixed Integer/date literal, which lowers through that same Any-LUB arm) meets the actual side on the same wire representation.
Finally correct the now-stale javadoc on `ValueCollectionOps.isBareSingleColumnSort` (ValueCollectionOps.java:29-32): the flattened CELL sequence of a multi-column relation DOES have a Pure-defined order; what has no natural order is a multi-column relation's ROWS.

**How legend-engine does it** — Semantics come from legend-pure, not legend-engine's relational layer, because the engine evaluates this tail in Pure after the store returns the TDS: `meta::pure::tds::TDSRow.values : Any[*]` at /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:79, and the default comparator's cross-kind order at /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-interpreted/src/main/java/org/finos/legend/pure/runtime/java/interpreted/natives/grammar/lang/Compare.java:51 and :75-110.

**Risk** — TENET-2 TRAP, the cheap wrong fix: the harness already has an order-leniency mechanism — `endsInSort` (EngineTestExecutor.java:3409-3430) and `compare(e, a, ordered && actual.sortedChain())` (EngineTestExecutor.java:2841). Teaching `endsInSort`/`Eval.values()` to sort host-side, or letting this chain claim `sortedChain=false` so `assertEquals` degrades to a multiset compare, would turn the test green while the platform still cannot express `rows.values->sort()`. That is harness compensation for a lowering gap and must not be done.
PLATFORM TRAP: naively relaxing `isBareSingleColumnSort` to allow >1 column and letting `naturalSort` ORDER BY all columns produces `[1, %2014-12-01, 2, %2014-12-01, ...]` at the wire (row-major interleave of an id-sorted relation) — silently wrong rows instead of a wall. Strictly worse than today.
REGRESSION SURFACE of edit (1): `.values` over a relation is a very common shape; if any consumer pattern-matches on the node identity rather than on `info().type()`, the added marker changes behaviour. This is why edits (2) must land in the same change and why the marker keeps `source.info()`. Grep every `instanceof Type.RelationType` guard that could now see a `TypedPropertyAccess` wrapper.
UNVERIFIED HALF: I did not confirm that a Tabular column of `to_json(...)` values decodes back to TYPED Java values at the wire (`H2Verify.carrierList` / `Eval.values()`); if it arrives as JSON text, `assertEquals` compares "1" to 1 and the fix lands red on a decode issue rather than an ordering one.

**Also unblocks** — None. I grepped every `rows.values->sort()` in the corpus: dates.pure:104, tests/mapping/association/testAssociationMappingInheritance.pure:73, tests/mapping/inheritance/testInheritanceRelational.pure:64, tests/mapping/inheritance/testInheritanceRelationalUnion.pure:65, tests/advanced/testQueryStructure.pure:315 and the seven in tds/tests/testTDSRestrictDistinct.pure are all SINGLE-column projections already served by the existing arm. dates.pure:57 is the only multi-column one, so this fix is worth exactly one test — weigh that against the M effort before scheduling it.

**Falsifier** — `meta::relational::tests::mapping::dates::datetime::testProject` (dates.pure:100) is the SAME assert shape — `$result.values.rows.values->sort()` — but over a ONE-column project, so it must take the `isBareSingleColumnSort`/`naturalSort` path and must NOT be in the failing set. If that test is also failing with this same message, the guard is not what is rejecting the call and the diagnosis is wrong. Direct confirmation, one line, no build: temporarily print `nc2.args().get(0).info().type()` at Lowerer.java:524 and check it is a `Type.RelationType` with 2 columns named id/date.

<details><summary>Evidence read (19 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/dates.pure:57-61 — `strictdate::testProject`: project([t|$t.id,t|$t.date],['id','date']) then `assertEquals([1,2,...,15, %2014-12-01, ... %2016-03-28], $result.values.rows.values->sort())` — 15 integers sorted, THEN 15 dates sorted
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/injection/testInjection.pure:50-56 — the brief's cited source: `Trade.all()->project([t|$t.name, t|$t.productAtTimeOfTrade.name],['a','b'])` + assertEquals/assertSameElements. No `sort` in the body, the mapping (lines 123-141), or the qualified property `productAtTimeOfTrade` (line 40) — this test cannot produce the reported message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:524 — the frontier default: `throw new NotImplementedException("lowering not yet implemented for " + spec.getClass().getSimpleName() + (spec instanceof TypedNativeCall nc2 ? " ('" + nc2.callee().qualifiedName() + "' in relation position)" : ""))` — character-for-character the observed message
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:514-515 — `case TypedNativeCall nc when ValueCollectionOps.isBareSingleColumnSort(nc) -> naturalSort(nc);` is the ONLY relation-position sort arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/ValueCollectionOps.java:33-41 — `isBareSingleColumnSort` requires `srt.columns().size() == 1`; its javadoc says 'a multi-column relation has no natural order and stays at the loud frontier default'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2463 — the `.values` arm over `Type.RelationType rt2`: the row-VARIABLE branch builds per-column cells with an Any LUB when the column types differ; the RELATION-VALUE branch ends `return source;` (identity), with the comment 'identity — the relation's wire flatten IS row-major cell order'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2416-2422 — the precedent for keeping a marker: `.rows` returns `new TypedPropertyAccess(source, "rows", source.info())`, identity-TYPED, 'erased by the K-side splice hook'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:494-500 — the defensive erasure arm for `PlatformTypes.ROWS_MARKER` in `relation()`; the mirror site for a cells marker
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:320-326 — the other ROWS_MARKER erasure ('`.rows` MARKER erases here (audit 20c H1) — any space')
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2497-2519 — `Eval.values()` for a Tabular: `t.rows().forEach(r -> out.addAll(r.values()))` — the row-major flatten that the Typer's identity defers to
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/ValueCollections.java:52-68 — `rowMajorCellList(rel, rt, sub)`: 'a MULTI-column relation as a ROW-MAJOR value collection. Cells ride the VARIANT carrier (to_json ...)' — the flatten machinery already exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2758-2773 — the SCALAR-position arm already routes a >1-column relation to `rowMajorCellList` ('MULTI-column = ROW-MAJOR cell flatten (the whole-TDS assert idiom)')
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:883-913 — the value-space `sort` rule: 1-arg form emits `SqlFn.LIST_SORT` over the list, or the `mixedElems` identity-preserving path
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Scalars.java:2501-2512 — `mixedElems` returns null unless the collection LUB is `Type.Primitive.NUMBER` or `Type.Primitive.DATE`: 'uniform-kind collections keep their native carrier'. An Integer+StrictDate mix (LUB Any) has NO ordering path
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lowering/Lowerer.java:2220-2233 — the Any-LUB heterogeneous literal carrier: `ArrayLit` of `TO_VARIANT(scalar(e))`, 'the one SQL carrier that keeps per-element runtime kinds'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/sql/dialect/DuckDb.java:262-264 — `variantConstruct` renders `to_json(x)`; DuckDB JSON is text-backed, so `list_sort` (DuckDb.java:230, `list_sort`) over a variant array orders JSON TEXT, not Pure values
- /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-interpreted/src/main/java/org/finos/legend/pure/runtime/java/interpreted/natives/grammar/lang/Compare.java:51 — `PRIMITIVE_TYPE_COMPARISON_ORDER = Integer, Float, Number, DateTime, StrictDate, Date, Boolean, String`
- /Users/neemsandv/legend/legend-pure/legend-pure-runtime/legend-pure-runtime-java-engine-interpreted/src/main/java/org/finos/legend/pure/runtime/java/interpreted/natives/grammar/lang/Compare.java:75-110 — `compare()`: numbers first (`num1 != null -> (num2 == null) ? -1 : ...`), then dates, then booleans — the cross-kind order the expected list encodes
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tds.pure:76-79 — `Class meta::pure::tds::TDSRow { parent : TabularDataSet[0..1]; values : Any[*]; ... }` — `rows.values` really is a heterogeneous Any list

</details>

---
