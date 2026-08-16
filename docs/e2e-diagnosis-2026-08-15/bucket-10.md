# Bucket 10 — Harness SHAPE

15 tests from the ledger; **15 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: MISSING FEATURE 7, HARNESS GAP 3, REAL DEFECT 3, EXECUTION-TARGET ARTIFACT 1, TESTS ENGINE INTERNALS 1

---

## `testComplexOrExistsToManyProperty`

| | |
|---|---|
| family | `functions/tests` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | S |
| confidence | high |

**Root cause**

The failing statement is `$result.values.legalName->map( f | assert(['Firm X','Firm C','Firm A']->contains($f)));` — an ASSERTION LOOP over values the execute() already materialised, not a query. The harness statement router has no form for it: enumDriverLoop deliberately refuses it (it requires every map element to be a literal enum or a dotted read off an element pointer, and its comment names this very test as the misfire it was narrowed to exclude, EngineTestExecutor.java:2226-2235); resultVarLoop requires the source to be a PureCollection of Variables (line 2190-2192); alloyFallback does not apply. So the statement falls through to the K-natives arc (EngineTestExecutor.java:519-535), where it is substituted, wrapped with the exec statements because it references the exec var `$result`, and pushed through the whole compile pipeline. The typer accepts it (assert is a known platform native), and the H-phase resolver walls: StoreResolver's TypedMap arm only fires when the map's source is already a RelationType (StoreResolver.java:466-471), and here the source is a class-query-derived String collection, so control reaches the named default `throw new NotImplementedException("class query under " + n.getClass().getSimpleName() + " is not resolvable yet (H2 vocabulary)")` at StoreResolver.java:505-510. EngineTestExecutor catches the NotImplementedException and reports `statement 'map' failed through the pipeline: ...` (line 553-558). The wall is honest; the routing decision that produced it is the defect.

**Fix**

Add a statement-position ASSERT-LOOP arm in EngineTestExecutor's main loop, immediately BEFORE the K-natives arc at EngineTestExecutor.java:519. Match: `AppliedFunction` with simple name `map`, 2 parameters, second parameter a `LambdaFunction` with exactly 1 parameter, AND every statement in that lambda body is a harness assert call (`harnessVocabName(f) && simpleName(f).startsWith("assert")`). On a match: evaluate the map SOURCE host-side with the existing `eval(subst(src, lets), lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn)` (the same call assertSameElements already uses to read `$result.values.legalName`), then for each element of `Eval.values()` lift it to a literal ValueSpecification (CString/CInteger/CFloat/CBoolean — the harness already builds CStrings this way, e.g. JsonAssertCanon.java:163) and push the substituted assert statements onto the front of the worklist so they score through the normal checkAssert/scoreAssert path. If ANY element is not liftable to a literal, return null from the arm and let the statement fall through to today's behaviour — a loud wall, never a skipped assertion. Do NOT widen enumDriverLoop; the lambda-body-is-all-asserts test is the discriminator that keeps a genuine query-shaped map out.

**Risk** — The arm must not swallow a map whose lambda body is a QUERY that merely ends in an assert, and must not silently skip elements it cannot lift — either would convert a real failure into a vacuous pass. Gate on ALL body statements being assert-family, and wall (do not skip) on a non-liftable element. TENET-2 CHECK: this is not compensation — `assert` is Pure host vocabulary that the platform's relational pipeline has no business compiling, and the values being iterated are already materialised by the platform's own execute().

**Also unblocks** — testSubtypeMapping.pure:56 (`$result1->map(p | assertInstanceOf($p, MyProduct));`) is the only other test-level occurrence of this idiom in the whole core_relational corpus; it is not currently in the 276, so treat this as a single-test fix with a small regression-guard benefit.

**Falsifier** — If the sweep detail changes to a row-level `assert: ...` failure after this arm lands, the routing was the whole problem. If instead `eval($result.values.legalName)` itself walls, then the read (not the map) is the gap and the fix must move upstream into the read path.

<details><summary>Evidence read (5 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:508 — `default -> throw new NotImplementedException("class query under " + n.getClass().getSimpleName() + " is not resolvable yet (H2 vocabulary)");` — the exact message text in the sweep, reached because the TypedMap arm above (line 466-471) is guarded by `m.source().info().type() instanceof Type.RelationType`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:556 — `return new Outcome.Unsupported("statement '" + af3.function() + "' failed through the pipeline: " + ...)` in the K-natives arc, which is the only producer of that prefix.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2226 — the enumDriverLoop comment: "STRICT literal-enum elements only (DatabaseType.H2 ...): a map over an arbitrary property chain is a QUERY, and enumTail's loose property match must never unroll it (the testComplexOrExistsToManyProperty misfire)" — a previous pass unrolled this shape by accident and the guard was tightened, leaving no correct arm behind.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2190 — resultVarLoop requires `m.parameters().get(0) instanceof PureCollection pc && pc.values().stream().allMatch(v -> v instanceof Variable)`, which a property chain is not.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/testExists.pure:85 — the statement in question; the two asserts around it (`assertSize($result.values, 3)` and `assertSameSQL(...)`) are ordinary forms the harness already handles, and the sibling tests in the same file that lack this map statement (testComplexExistsToManyProperty at line 73, testNotExists at line 88) are in no failing brief.

</details>

---

## `H2Test`

| | |
|---|---|
| family | `functions/tests/projection` |
| sweep status | SHAPE |
| **verdict** | **EXECUTION-TARGET ARTIFACT** |
| effort | L |
| confidence | high |

**Root cause**

This corpus test is not a semantics test at all — its own comment says "this is testing that legend-h2 is actually being used rather than vanilla h2". It executes raw SQL `SELECT case when false = 'false' then 'Ok' else 'Error' END` and expects 'Ok'. legend-engine ships a PATCHED H2 2.1.214 build in which `org.h2.value.TypeInfo.areComparable` was edited to allow BOOLEAN<->VARCHAR and BOOLEAN<->NUMERIC comparison (the two `@legend-fix` lines). legend-lite routes this statement to STOCK org.h2: the executeInDb READ path in HostEval dispatches to `DbMetaData.query(sql, replayStream())` (HostEval.java:376-381), which opens a fresh `jdbc:h2:mem:execquery<n>` connection (DbMetaData.java:92-93) using H2Verify.SETTINGS (`MODE=LEGACY;DATABASE_TO_UPPER=false;...`, H2Verify.java:152-160) against the driver found by `Class.forName("org.h2.Driver")` (H2Verify.java:52-58) — i.e. unpatched H2, which raises 90110 "Values of types BOOLEAN and CHARACTER VARYING(5) are not comparable". MODE=LEGACY does not help: the legend change is in TypeInfo.areComparable, which is mode-independent. The SQLException propagates out of the K-natives arc (EngineTestExecutor re-throws SQLExceptions) and is caught by Runner.tryRunNoExecute (Runner.java:1161-1172), whose text becomes the ' — wall: ' suffix on the no-execute SHAPE line (Runner.java:1312-1315).

**Fix**

RECOMMENDED: do not fix — ledger it. This test asserts that legend-engine's forked H2 jar is on the classpath. legend-lite's primary execution target is DuckDB and its second target is stock H2; neither is legend-h2, and vendoring a patched H2 into a clean-room rewrite buys nothing but this one test. Record it as an execution-target artifact with the citation above.
IF it must go green, the ONLY defensible change is to stop routing executeInDb READS to a different database than the writes went to: in HostEval.java:376-381, execute the raw statement on the SESSION's own connection (the DuckDB workspace the test's setup already populated) and keep the H2 replay only as the fallback for the metadata natives that genuinely need H2's column-naming (COUNT(*) etc.). DuckDB coerces the untyped string literal in `false = 'false'` and would return 'Ok'. Do NOT rewrite the SQL text at the boundary and do NOT special-case this statement — either would be harness compensation for a target difference.

**How legend-engine does it** — legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-h2/legend-engine-xt-relationalStore-h2-execution-2.1.214/src/main/java/org/h2/value/TypeInfo.java:1011 — the `@legend-fix` that makes this test pass in the engine; the module exists solely to ship a patched org.h2 (it also overrides org/h2/engine/Mode.java and adds org/finos/legend/h2/H2Defaults.java, whose getDefaultH2Properties() is the source of the NON_KEYWORDS/MODE=LEGACY string legend-lite mirrors in H2Verify.SETTINGS).

**Risk** — The alternative fix is broad and risky: the H2 read route exists specifically for 'engine-parity column naming', so moving executeInDb reads to DuckDB will change result-set column names for every other executeInDb-reading test (mutation, metadata, sqlQueryToString families) and is very likely a net regression. If attempted, it must be gated and swept. There is no tenet-2 trap in the 'do not fix' option.

**Falsifier** — Run `SELECT CASE WHEN false = 'false' THEN 'Ok' ELSE 'Error' END` on the DuckDB target directly. If DuckDB also rejects the BOOLEAN/VARCHAR comparison, the alternative fix does not work either and 'do not fix' becomes the only answer. (I did not run this — it is the single cheapest observation that decides the fix.)

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/HostEval.java:376 — `if (PlatformTypes.EXECUTE_IN_DB.equals(fqn)) { ... return DbMetaData.query(String.valueOf(asList(sqlv).get(0)), replayStream()); }` with the comment "the READ path: run the query over the replayed H2 second target (engine-parity column naming)".
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/DbMetaData.java:92 — `try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:execquery" + id + SETTINGS, "sa", ""))` then `st.executeQuery(sql)` — the raw corpus SQL runs verbatim on H2, not on the DuckDB session.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/H2Verify.java:54 — `Class.forName("org.h2.Driver")`; and H2Verify.java:157 — `";MODE=LEGACY;DATABASE_TO_UPPER=false" + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=..."` — stock driver, engine-mirrored settings.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-h2/legend-engine-xt-relationalStore-h2-execution-2.1.214/src/main/java/org/h2/value/TypeInfo.java:1011 — `case Value.GROUP_BOOLEAN:  return true;   // @legend-fix : allow varchar to boolean comparisons` inside the GROUP_CHARACTER_STRING branch of areComparable (line 1000 carries the matching numeric-to-boolean fix). areComparable normalises the pair with a swap at line 935-943, and VARCHAR's value type sorts below BOOLEAN, so this is the branch `false = 'false'` takes.
- /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-dbExtension/legend-engine-xt-relationalStore-h2/legend-engine-xt-relationalStore-h2-execution-2.1.214/src/test/java/org/h2/legendTests/TestBooleanComparison.java:26 — the fork's own unit test asserts exactly this query returns 'Ok', confirming the corpus test is a classpath assertion about that fork.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/functions/tests/projection/testIn.pure:181 — the corpus comment: "//TODO: Move to another location - this is testing that legend-h2 is actually being used rather than vanilla h2".

</details>

---

## `testTableToTdsWithCrossJoin`

| | |
|---|---|
| family | `lineage/scanRelations` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

The query's join condition is `{a,b| true}` — a cross join. `parseTdsJoinChain` gets past its own guard (the last parameter IS a 2-param LambdaFunction with a non-empty body, so the check at ScanRelations.java:306-311 passes) and calls `attachTdsJoin` (line 312). `attachTdsJoin`'s very first act is to require the lambda body to be an `equal` AppliedFunction with two parameters (lines 337-340); the body here is a `CBoolean` literal, so line 341-342 throws `NotImplementedException("scanRelations: tableToTDS join condition beyond a single equality pending")`. The surface is genuinely absent: there is no branch anywhere in ScanRelations for a join condition that references no columns. Everything ELSE this test needs is already correct — personTable is a bare `tableToTDS` source so `keepAll=true` keeps all seven columns (line 430-434), firmTable's `project([col(..'ID','firmID'), col(..'CEOID','ceoID')])` narrows it to {CEOID, ID} (lines 459-468), and `tableToTdsRoots` returns from the join path at line 245 before the string-literal narrowing pass, so the stray 'ID'/'CEOID' strings do not shrink personTable.

**Fix**

In `/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java`:

(1) Thread the current LEFT node through the chain. Change `parseTdsJoinChain` (line 296) to return the `Node` it produced: in the base case `TdsSrc base = parseTdsSource(...); roots.add(base.node()); return base.node();`, and in the join case `Node left = parseTdsJoinChain(ctx, af.parameters().get(0), roots, aliases, byTable); ... attachTdsJoin(cl, right, aliases, byTable, left); return left;` (the join's result relation is still rooted at the left tree, matching the engine's `RootJoinTreeNode(alias=$leftAliasForJoinNode)` at pureToSQLQuery.pure:6769).

(2) In `attachTdsJoin`, replace the unconditional throw at lines 337-343 with a CROSS-JOIN branch taken when the condition references no tds column reads at all:

```java
ValueSpecification body = cl.body().get(cl.body().size() - 1);
if (!(body instanceof AppliedFunction eq
        && eq.function().substring(eq.function().lastIndexOf(':') + 1).equals("equal")
        && eq.parameters().size() == 2)) {
    // engine scanRelations.pure:625-632 — a condition that references NO
    // columns (true / false / 1 = 1) keeps pureToSqlQuery's original
    // 'tdsJoin' name and demands no columns on either side
    if (!containsTdsColRead(body)) {
        right.labelOverride = "tdsJoin";
        left.children.put(right.table + "(tds_join_" + left.children.size() + ")", right);
        return;
    }
    throw new NotImplementedException("scanRelations: tableToTDS"
            + " join condition beyond a single equality pending");
}
```
where `containsTdsColRead(v)` is a small recursive helper returning true if any sub-node satisfies the existing `tdsColRead(v) != null` predicate (line 391). Keep `right.cond` NULL — the engine adds no condition columns here, and `cond` is only consumed by the `Rel` data record, not by `print`.

CRITICAL: keep the throw for a condition that DOES read columns but is not a single equality (e.g. `and(eq, eq)` multi-key joins). Narrowing that wall would silently drop join columns from the tree.

**How legend-engine does it** — The literal `tdsJoin` name comes from `^Join(name='tdsJoin', ...)` / `^JoinTreeNode(..., joinName='tdsJoin')` in /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/pureToSQLQuery/pureToSQLQuery.pure:6755 and :6768. scanRelations normally RENAMES that join to the condition mangle (`^$orgJoin(name = $updatedOperation->buildUniqueName(true, $extensions), ...)`, scanRelations.pure:692), but `processJoinFromTreeNode` first computes `tableAliasColumns = $j.join.operation->extractTableAliasColumns()` (scanRelations.pure:596) — EMPTY for a `true` operation — and takes the `$updatedAliases->size() == 0` branch at scanRelations.pure:625-632, whose comment is literally "Expect this case to arise when join does not reference any columns. Example join operation - true, false, 1 = 1". That branch returns `^$orgJoin(aliases = ...)` WITHOUT touching `name`, so the printed label stays `tdsJoin`.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every citation resolves and says what is claimed, and I could not break the mechanism or the fix.

Mechanism: ScanRelations.java:306-311 guards only arity (`lastParam(af) instanceof LambdaFunction cl || cl.parameters().size() != 2 || cl.body().isEmpty()`), so `{a,b| true}` passes and reaches attachTdsJoin at 312. attachTdsJoin's first act (337-343) requires an `equal` AppliedFunction with 2 params and otherwise throws the exact literal. CBoolean exists as a record in protocol/spec. The observed detail in docs/RELATIONAL_CORPUS_ALL.md:1294 is 'scanRelations: scanRelations: tableToTDS join condition beyond a single equality pending' — the attachTdsJoin message with the harness prefix, which rules out the 306-311 guard exactly as the falsifier says.

Expected output: I read the golden at scanRelationsTests.pure (testTableToTdsWithCrossJoin) — `personTable [ADDRESSID, AGE, FIRMID, FIRSTNAME, ID, LASTNAME, MANAGERID]` with child `firmTable(tdsJoin) [CEOID, ID]`, asserted via the NO-ARG `relationTreeAsString()` so labels ARE shown (LineageRelationsForm.java:81-85 maps no-arg to "J" and line 127 passes showLabels=true). print (ScanRelations.java:846-851) renders labelOverride verbatim in parens. LineageRelationsForm.stripAliasBreadcrumbs (151-175) is a no-op on the token 'tdsJoin' (no `_d`/`_m` run), so the emitted label compares equal.

Columns: personTable is a bare tableToTDS → parseTdsSource sets keepAll=true (line 431) and keeps all 7; firmTable's project narrows via lines 459-467 to {CEOID, ID}; tableToTdsRoots returns at line 245 from the join path before the string-literal narrowing at 264-278, so 'ID'/'CEOID'/'firmID'/'ceoID' string literals cannot shrink personTable. All as claimed.

Engine ground truth checks out: pureToSQLQuery.pure:6755/6761/6768 name every tds join `'tdsJoin'`, and scanRelations.pure:629-635 takes the `$updatedAliases->size() == 0` branch whose comment is literally 'Expect this case to arise when join does not reference any columns. Example join operation - true, false, 1 = 1' and returns `^$orgJoin(aliases = ...)` WITHOUT touching `name` — so the label legitimately stays 'tdsJoin'. (The cited line 625-632 is 629-635 in the file I read; a 4-line offset, not a substantive error.)

Fix attack: threading `left` through parseTdsJoinChain changes a void method to Node-returning; its only caller is line 242, which ignores the result, so no sibling behaviour moves. attachTdsJoin is private static with a single caller. `right.cond` staying null is fine — Rel's cond field is @Nullable and print never reads it. The insistence on a real recursive `containsTdsColRead` rather than `instanceof CBoolean` is the right call and preserves the wall for `and(eq,eq)` multi-key conditions. Effort S holds: one file, ~20 lines, no new IR node, no golden re-alignment.

</details>

**Citation issues found in review** — Minor, non-substantive: the engine citation 'scanRelations.pure:625-632' is actually 629-635 in the file on disk. Content is exactly as described.

**Risk** — The `containsTdsColRead` guard must be a real check, not `body instanceof CBoolean` — otherwise `{a,b| 1 == 1}` or a `false` literal would still wall while a genuine unsupported column condition could slip through as a silent cross join, which is worse than a wall (a cross join tree with no columns is a plausible-looking lie). Threading the left node through `parseTdsJoinChain` changes a void method to Node-returning; the only caller is line 242 which ignores the result, so no other behavior moves.

**Falsifier** — If the emitted tree is `root / personTable[all 7] / firmTable(tdsJoin)[CEOID, ID]` the diagnosis holds. The cheapest disproof: if `parseTdsJoinChain`'s existing guard at lines 306-311 fires FIRST (message "tableToTDS join without a 2-param condition lambda") then `{a,b| true}` is not being parsed as a 2-param LambdaFunction and the root cause is in the parser, not attachTdsJoin — but the brief's observed message is the attachTdsJoin one, which already rules this out.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:337-343 — `if (!(body instanceof AppliedFunction eq && ...equals("equal") && eq.parameters().size() == 2)) { throw new NotImplementedException("scanRelations: tableToTDS join condition beyond a single equality pending"); }` — the exact message literal in the brief
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:306-312 — the preceding guard only checks the lambda's ARITY, so a constant-bodied 2-param lambda reaches attachTdsJoin
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:373-388 — attachTdsJoin's tail: parent lookup is `byTable.get(l[0])` (derived from the condition), and the label is always the `equal_"joinleft_"...` mangle; both are unreachable without a column-comparison condition
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:845-857 — `print` renders `labelOverride` verbatim in parentheses when non-null and non-empty, so setting it to "tdsJoin" produces the golden's `(tdsJoin)`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:995-1009 — the test: `join(tableToTDS(personTable), tableToTDS(firmTable)->project([col(..'ID','firmID'),col(..'CEOID','ceoID')]), INNER, {a,b| true})`, expected `personTable [ADDRESSID, AGE, FIRMID, FIRSTNAME, ID, LASTNAME, MANAGERID]` with child `firmTable(tdsJoin) [CEOID, ID]`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/protocol/spec/CBoolean.java — the node kind a `true` literal parses to (file exists in the spec package listing)

</details>

---

## `testTableToTdsWithJoinAndUnion`

| | |
|---|---|
| family | `lineage/scanRelations` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | S |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

`ScanRelations.scanRoots` only splits `concatenate` when it is the OUTERMOST expression of the query body (ScanRelations.java:126-137 calls `splitConcatenate(body, branches)` on the top-level body only). In this test the body is the outer `join(...)` and the concatenate sits UNDER it as parameter 0, so `branches` has size 1 and the whole `join(concatenate(A,B), addressTable, INNER, lambda)` is handed to `tableToTdsRoots`. `containsCall(n,"join")` (line 222) is true, so `parseTdsJoinChain` (line 296) recurses on `af.parameters().get(0)` — the `concatenate(...)` — which is not a `join`, so it falls to `parseTdsSource(ctx, concatenate(...))` (line 315). `parseTdsSource` peels only `project` wrappers (lines 413-418), so `cur` is still the concatenate; `collectTableToTds` (line 506) recurses through the concatenate's parameters and returns BOTH personTable and firmTable; `found.size() != 1` at line 421 throws `NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` at line 422-423. `LineageRelationsForm.tryRun` catches it (line 134) and returns `Outcome.Unsupported("scanRelations: " + msg)` — exactly the observed detail (the doubled `scanRelations:` prefix is the harness prefix plus the message's own prefix).

**Fix**

In `/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java`, teach `splitConcatenate` (lines 146-158) to DISTRIBUTE a `join` over a concatenate on its left operand — the AST mirror of the engine's "one base tree per UnionAll query, each getting a copy of the join child". Insert before the existing `out.add(v)`:

```java
if (v instanceof AppliedFunction jf
        && "join".equals(jf.function().substring(jf.function().lastIndexOf(':') + 1))
        && jf.parameters().size() >= 3) {
    List<ValueSpecification> lefts = new ArrayList<>();
    splitConcatenate(jf.parameters().get(0), lefts);
    if (lefts.size() > 1) {
        for (ValueSpecification l : lefts) {
            List<ValueSpecification> ps = new ArrayList<>(jf.parameters());
            ps.set(0, l);
            splitConcatenate(jf.withParameters(ps), out);   // recurse: nested joins
        }
        return;
    }
}
```
(`AppliedFunction.withParameters` already exists — it is used at JoinChecker.java:112.) Gate it on `join` only; do NOT distribute generically over any wrapper, because the engine only splits where the union alias is a join operand.

Nothing else changes: `scanRoots` then sees `branches.size() == 2`, calls `tableToTdsRoots` once per branch (each with its OWN fresh `aliases`/`byTable` maps and its own freshly-built addressTable `Node`, because `collectTableToTds` constructs new Nodes per call at line 519), and the existing `if (branches.size() > 1) out.sort(comparing(nd -> nd.table))` at line 138-141 yields firmTable then personTable — exactly the golden order. Column demand also falls out correctly: `parseTdsSource` narrows personTable/firmTable to {ID} via the project alias map (lines 459-468), and addressTable is a bare source so `keepAll = true` (line 431) keeps all five columns. Labels are suppressed anyway (`relationTreeAsString(false)` -> `showLabels=false`, LineageRelationsForm.java:81-85).

**How legend-engine does it** — legend-engine builds the relation tree from the GENERATED SQL, not from the Pure AST. `processRelationJoin` puts the left operand behind a `TableAlias(name='"joinleft_"'+nodeId, relationalElement=$query1)` (pureToSQLQuery.pure:6729-6768); when `$query1` is a `UnionAll` (the concatenate), `generateRelationTreeFromRelationalTreeNode`'s RootJoinTreeNode case calls `$r.alias->generateRelationTreeFromRelationalOperationElement(...)` which returns ONE tree PER union query (scanRelations.pure:484-491, the `u:UnionAll[1]` case), and then `let result = $baseTreesWithColumns->map(tree | ... $tree->addChildTrees($childTrees))` (scanRelations.pure:537-539) gives EACH base tree its own copy of the join child. Root ordering is `sortBy(x | relation.name + '->' + columns...)` at scanRelations.pure:358.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Citations all resolve and the mechanism reproduces on paper.

Mechanism: scanRoots calls splitConcatenate on the TOP-LEVEL body only (ScanRelations.java:129), and splitConcatenate (147-158) flattens only a literal 2-param `concatenate` spine with no rule for an enclosing operator. In this test the body IS the outer join, so branches.size()==1. tableToTdsRoots sees containsCall(n,"join") (222); the wrapper-peeling loop at 232-239 does not fire because top already IS the join; parseTdsJoinChain recurses on parameters().get(0) = the concatenate, which is not a join, so it falls to parseTdsSource at 315; parseTdsSource peels only `project` (413-418) so cur is still the concatenate; collectTableToTds (506-531) recurses through AppliedFunction parameters and returns BOTH personTable and firmTable; found.size()!=1 throws at 421-423. LineageRelationsForm.java:134-137 wraps it with the 'scanRelations: ' prefix. docs/RELATIONAL_CORPUS_ALL.md:1295 records exactly the doubled-prefix string for the lineage/scanRelations family. Confirmed end to end.

Expected output: golden is two sibling roots `firmTable [ID]` and `personTable [ID]`, each with `addressTable [COMMENTS, ID, NAME, STREET, TYPE]`, asserted via relationTreeAsString(false) → showLabels=false (LineageRelationsForm:81-85, 127). I traced both branches after the proposed distribution: each parseTdsSource narrows its own root to {ID} via the eID→[table,ID] alias map (459-467); addressTable is bare so keepAll=true keeps all 5; attachTdsJoin resolves l via the branch-local `aliases` and parent via the branch-local `byTable` (fresh LinkedHashMaps per tableToTdsRoots call, lines 240-241) and collectTableToTds builds a NEW Node per call (line 519), so the two addressTable children are independent objects. `if (branches.size() > 1) out.sort(comparing(nd -> nd.table))` at 138-142 yields firmTable before personTable — the golden order. Everything the diagnosis claims falls out.

Fix attack: AppliedFunction.withParameters exists (AppliedFunction.java:95-98) and preserves candidateFqns/pos/propertyCall/grouped/infix; JoinChecker.java:112 does use it. The recursion terminates (the re-entered join's param0 splits to size 1 and falls through to out.add). Regression surface is essentially nil: the new branch fires only when v is a `join` whose param0 splits into >1, and any such query today unconditionally throws inside tableToTdsRoots' join path, so no green test can be sitting on that shape. I specifically checked the nearest sibling, testTdsJoinConcatenateAndJoin (join over concatenate, 5-param string-key join form) — it is class-sourced, so collectTableToTds finds ZERO tableToTDS and it throws the same message today and after the change; no regression, no accidental fix. Effort S holds: ~15 lines in one file, no new IR node, no goldens to re-align.

One scope caveat the diagnosis does not state: splitConcatenate is reached ONLY from scanRoots. relTree (ScanRelations.java:185) calls tableToTdsRoots directly, so the identically-named testDataGeneration/tests twin (docs/RELATIONAL_CORPUS_ALL.md:1411, single 'scanRelations:' prefix) will NOT be fixed by this change. V09's diagnosis is correctly tagged lineage/scanRelations and line 1295 is a real, distinct failing test, so the item stands — but do not expect two greens from it.

</details>

**Risk** — `splitConcatenate` is called only from `scanRoots` (line 129), so the blast radius is the root-splitting of every scanRelations query. A query whose join left operand is a concatenate but whose branches are MAPPING-driven (not tds) would now also split and go through `buildRoots` per branch; no such test exists in the corpus (the only two concatenate uses in scanRelationsTests.pure are this test and testTdsJoinConcatenateAndJoin). Tenet-2 trap to avoid: do NOT special-case this in `LineageRelationsForm` or widen `stripAliasBreadcrumbs` to paper over an ordering difference — the root order is ScanRelations' to own, and line 138-141 already owns it.

**Falsifier** — Run the test with `LL_LINEAGE_DEBUG` unset and check that the produced tree is byte-identical to the golden. If it is not, the cheapest single check is: after the change, does `scanRoots` receive `branches.size() == 2`? Add a one-line stderr print of `branches.size()`; if it is still 1, the parser does not shape `->concatenate(...)` as `AppliedFunction("concatenate", [left,right])` and the distribution guard never fires.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:129 — `splitConcatenate(body, branches);` is applied only to the top-level body, so a concatenate nested under a join is never split
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:146-158 — `splitConcatenate` flattens only a literal `concatenate(a,b)` spine; it has no rule to push an enclosing operator into the branches
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:315 — `TdsSrc base = parseTdsSource(ctx, v, aliases, byTable);` is the base case reached with v = the concatenate
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:419-424 — `collectTableToTds(ctx, cur, found); if (found.size() != 1) throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` — the exact message literal in the brief
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/LineageRelationsForm.java:134-137 — `catch (NotImplementedException e) { return new EngineTestExecutor.Outcome.Unsupported("scanRelations: " + e.getMessage()...) }` — the harness path that produces the reported string
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:920-936 — the test body: `tableToTDS(personTable)->project([col(..'ID','eID')])->concatenate(tableToTDS(firmTable)->project([col(..'ID','eID')]))->join(tableToTDS(addressTable), INNER, {a,b|$a.getInteger('eID')==$b.getInteger('ID')})`, expected two sibling roots firmTable[ID] and personTable[ID], each with an addressTable child, asserted via `relationTreeAsString(false)`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:138-142 — `if (branches.size() > 1) out.sort(comparing(nd -> nd.table))` — the root ordering rule that already matches the golden's firmTable-before-personTable, and which the fix reuses for free

</details>

---

## `testTdsJoinConcatenateAndJoin`

| | |
|---|---|
| family | `lineage/scanRelations` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | medium |

**Root cause**

This query contains NO `tableToTDS` at all — every tds source is a mapping-driven `testJoinTDS_Person.all()->project([col({p|$p.firstName},'First_1'), col({p|$p.age},'Age_1')])`. But `tableToTdsRoots` decides which scanner to use purely on `containsCall(n, "join")` (ScanRelations.java:222), so ANY query containing `join` is routed into the tableToTDS-only recognizer. `parseTdsJoinChain` recurses to the base of the spine and calls `parseTdsSource` (line 315), whose `collectTableToTds` (line 506) only recognizes a literal `tableToTDS(tableReference(db,'s','t'))` (lines 511-518) and therefore returns ZERO nodes; `found.size() != 1` at line 421 throws the same 'join side is not a single table source' message — same literal as testTableToTdsWithJoinAndUnion but a DIFFERENT cause (zero sources, not two). This is a genuinely absent surface, and there are four further walls behind it, all of which must land together: (a) the join uses the STRING-PAIR form `join(right, JoinType.LEFT_OUTER, 'First_1', 'First_2')` (5 parameters, last two CStrings) — `lastParam(af) instanceof LambdaFunction` at line 307 would fail with "tableToTDS join without a 2-param condition lambda"; (b) `->extend(^BasicColumnSpecification<TDSRow>(name='Restated', func=r|false))` sits MID-SPINE between the join chain and the concatenate, and the wrapper-peeling loop at lines 232-239 only peels at the very top, never inside `parseTdsJoinChain`'s recursion; (c) the concatenate is again a join operand (the testTableToTdsWithJoinAndUnion gap); (d) each node's `[AGE, FIRSTNAME]` demand comes from the MAPPING (testJoinTDSMapping maps firstName->personTable.FIRSTNAME, age->personTable.AGE), i.e. it needs `buildRoots`' mapping walk plus a projected-alias -> physical-column map that ScanRelations does not currently expose from the mapping walk.

**Fix**

Do NOT patch this into the current recognizer — it needs a real generalization, and it should be ledgered as its own work item rather than bundled with the other two U57 fixes. The shape of the work, in `/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java`:

1. Replace `parseTdsSource`'s tableToTDS-only base case (lines 408-472) with a kind-dispatching `scanTdsSource(ctx, expr, mappingFqn)` that peels the result-shaping wrappers (`project`, `filter`, `extend`, `restrict`, `sort`, `groupBy`, `olapGroupBy`, `distinct`, `take`) and then: if the base is `tableToTDS(tableReference(...))` keep today's path; if the base is `Class.all()` delegate to `buildRoots(ctx, wrappedLambda, mappingFqn, false, extentRoots)`; otherwise wall loudly with the base's function name in the message.

2. Have that function also return the alias map `projectedAlias -> [table, physicalColumn]`. For the class-mapped case this requires resolving `col({p|$p.firstName}, 'First_1')` to `personTable.FIRSTNAME` — i.e. the mapping walk must expose, per leaf property, the (table, column) it landed on. `walk`/`dispatchPms` already computes that when it does `node.cols.add(...)`; the smallest change is to have `buildRoots` optionally record a `Map<propertyPath, String[]{table,column}>` alongside the Node tree.

3. Accept the string-pair join form in `parseTdsJoinChain`/`attachTdsJoin`: when `af.parameters().size() == 5` and parameters 3 and 4 are CStrings (or PureCollections of CStrings — reuse `JoinChecker.columnNames`' shape, JoinChecker.java:314-326), synthesize the same `(lAlias, rAlias)` pair the lambda form produces and take the identical attach path.

4. Apply the `join`-over-`concatenate` distribution from the testTableToTdsWithJoinAndUnion fix (that one is a prerequisite here, not a duplicate).

5. Child ordering: replace the insertion-ordinal child key `right.table + "(tds_join_" + parent.children.size() + ")"` (line 386-387) with the engine's sort key `table + "->" + cols + "->" + joinLabel`, so siblings order by join label as scanRelations.pure:562-564 does.

6. Separately, widen `LineageRelationsForm.stripAliasBreadcrumbs` (LineageRelationsForm.java:153) from `_d(?:#\d+|y\d+)?` to `_d(?:#\d+|y\d+|\d+)?` (and the same in the continuation alternation at line 163) so `_d0`/`_d1` node ids are fully consumed. This is a widening of an existing, documented, BOTH-SIDES normalization of pureToSqlQuery's internal counters — not a compensation — but it is worthless on its own and must land with the tree work.

**How legend-engine does it** — The engine never sees this as a special case: `generatRelationalTrees` routes the function, generates SQL via `toSQLQuery`, and walks the resulting `TdsSelectSqlQuery` (scanRelations.pure:397-441). Each `Class.all()->project(...)` operand has already become a plain `SelectSQLQuery` over `personTable` with only the projected columns, so `generateRelationTreeFromRelationalOperationElement`'s `sel:SelectSQLQuery` case (scanRelations.pure:463-481) collects exactly FIRSTNAME/AGE from `$sel.columns`. The flat (non-nested) child layout comes from `addChildTree` (scanRelations.pure:566-574): when the join's other table has the same relation name as the parent, the child is appended to the parent rather than pushed down. Sibling order is `sortBy(relation.name + '->' + columns + '->' + join.name)` (scanRelations.pure:562-564) on the UNSTRIPPED join name.

**Risk** — Step 5 (sibling ordering by join label) changes child order for EVERY tds-join tree, including the currently-passing testTableToTdsWithJoin, testTableToTdsWithMultipleJoin, testTableToTdsWithJoinToSameTable and testTableToTdsWithOLAPGroupBy — those must be re-checked, and testTableToTdsWithMultipleJoin's golden in particular pins firmTable-before-locationTable under personTable. Tenet-2 trap: the temptation here is to make `LineageRelationsForm` treat an unrecognized tds-join shape as 'advisory' again — that is exactly the laundering the class comment at LineageRelationsForm.java:28-32 says was deleted for manufacturing ~half this family's greens. Keep the wall until the scanner really produces the tree.

**Also unblocks** — Step 6 (the `_d0` breadcrumb-stripper widening) would unblock the label compare for any other tds-join golden whose node ids carry `_d<digit>`; I did not survey the full corpus for those, so I am not claiming specific tests.

**Falsifier** — Cheapest disproof of the 'needs the mapping walk' claim: temporarily rewrite the test query in a scratch .pure file replacing every `testJoinTDS_Person.all()->project([...])` with the equivalent `tableToTDS(tableReference(dbInc,'default','personTable'))->project([col(r|$r.getString('FIRSTNAME'),'First_1'), ...])` and run it. If the existing recognizer then produces the golden's tree shape (modulo column names), the only real gaps are the class-mapped source and the string-pair join form; if it still fails, the mid-spine `extend` / concatenate-under-join / sibling-ordering gaps are also load-bearing and the XL estimate stands.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:222 — `if (containsCall(n, "join")) { ... return out; }` routes ANY join-containing query into the tds recognizer, regardless of whether a tableToTDS exists
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:506-528 — `collectTableToTds` matches only `"tableToTDS".equals(simple) && param0 is AppliedFunction ending in "tableReference"`; a `Class.all()->project(...)` source contributes nothing
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:419-424 — `if (found.size() != 1) throw ... "join side is not a single table source"` — fires on found.size()==0 here
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:306-311 — the condition-lambda guard that the string-pair join form would hit next
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:232-239 — the wrapper peel is applied ONCE to the top expression, not inside parseTdsJoinChain's recursion, so a mid-spine `extend` is unreachable
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTDSJoin.pure:48-61 — `Mapping meta::relational::tests::tds::tdsJoin::testJoinTDSMapping ( testJoinTDS_Person : Relational { scope([dbInc]) ( personID : personTable.ID, firstName : personTable.FIRSTNAME, ..., age : personTable.AGE, ...) } )` — confirms the golden's [AGE, FIRSTNAME] is a mapping-derived demand on personTable
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:1012-1058 — the test body and golden: two personTable roots, one with three flat personTable children and one with two
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/JoinChecker.java:216-292 — legend-lite ALREADY understands the legacy string-key join form (`columnNames` accepts a CString or a collection of CStrings; the 5-arg distinct-name spelling falls through to the modern desugar), so the alias-pair extraction can be reused rather than reinvented
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/LineageRelationsForm.java:151-175 — `stripAliasBreadcrumbs`' regex `_d(?:#\d+|y\d+)?(?![A-Za-z])|_m\d+` does NOT consume the digits of a `_d0`/`_d1` node id: on the golden's `_d_d0_d#3"First_2"` it leaves a stray `0`, so even a perfect tree would still fail the label compare

</details>

---

## `testFlatten_ViaNoArgMapping`

| | |
|---|---|
| family | `modelToModelToRelational/milestoned` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

The mapping this test executes against does not exist as a corpus element — it is BUILT AT RUNTIME. `let mapping = ...::getNoArgFlattenMapping()` calls a helper whose body is `meta::legend::compileLegendGrammar('###Mapping\nMapping ...(...)')->at(0)->cast(@Mapping)` followed by `^$mapping(includes = ^MappingInclude(included = milestoningMapSmall, owner = $mapping))`. legend-lite has exactly one compileLegendGrammar surface, EngineTestExecutor.clgArm, and it only understands a FunctionDefinition payload: it parses the string, keeps `el instanceof FunctionDefinition` only, and when the selected index is out of range returns the ORIGINAL rhs untouched (EngineTestExecutor.java:826-838). A ###Mapping payload therefore yields zero collected elements and the let stays bound to the raw user call. Two further gaps sit behind it: (a) Runner.expandHelperCalls does not β-expand a let-bound helper unless it matches pairIdiom/singleExecute/executeTerminal/planChain (Runner.java:696-701), so clgArm never even sees the compileLegendGrammar call — it is inside the helper body; (b) there is no channel to register a dynamically parsed element into the per-test ModelContext, and no support for `^$mapping(includes=...)` copy-with-includes. The reported wall is the downstream symptom: `$mapping` substitutes into `->from($mapping,$runtime)` as the un-folded call, the typer types it as TypedUserCall of type Mapping[1], and FromChecker's loop finds it is neither a TypedPackageableRef (FromChecker.java:35) nor a ClassType `meta::core::runtime::Runtime` (FromChecker.java:53-56), so it throws at FromChecker.java:74-76. Runner scores the test SHAPE because no execute(|...) exists and tryRunNoExecute walled (Runner.java:1307-1315).

**Fix**

Build a dynamic-element channel, in this order.
(1) PLATFORM: give ModelContext a per-test overlay registration API for elements parsed at test time (the parse itself already exists: `com.legend.parser.ElementParser.parse(src, Dialect.LEGEND_ENGINE)`, used at EngineTestExecutor.java:828). Register ALL PackageableElement kinds the payload declares under their declared FQNs.
(2) EngineTestExecutor.clgArm (EngineTestExecutor.java:796-838): stop filtering to FunctionDefinition. Index `->at(i)` over the parsed element list in declaration order; if the selected element is a FunctionDefinition keep today's behaviour (return its body as a zero-arg lambda); otherwise register it via (1) and return `new PackageableElementPtr(<declared fqn>)`. That alone makes `from()` see a TypedPackageableRef.
(3) Runner.expandHelperCalls (Runner.java:647-701): add a `clgIdiom` arm to the let-bound gate — `fd2.body()` contains a call whose simple name is `compileLegendGrammar` — so `let mapping = getNoArgFlattenMapping()` β-expands and clgArm sees the call. Keep the existing narrow gate for everything else (the comment at Runner.java:641-646 records that broad let-expansion cost 53 tests).
(4) Support the copy-with-includes: in the same let arm, recognise `^$var(includes = ^MappingInclude(included = <ref>, owner = $var))` where `$var` is bound to a registered dynamic Mapping, and re-register that mapping FQN with the referenced mapping appended to its includes list. This is what supplies the ~src class mappings (milestoningMapSmall) the M2M transform resolves through.
(5) Only then does the actual semantic surface become reachable: the transform reads `$src.synonymsMilestoned` / `$src.synonymsMilestonedViaAssociation` — a NO-ARG milestoned property on a business-temporal source inside an M2M transform, whose date must come from the root `.all($bdate)`. Expect to build that in the resolver too; the corpus itself flags it ("milestoned property without args is not supported by pure ide compiler but works with engine", milestonedSourceToNonMilestonedTargetProperty.pure:263).

**How legend-engine does it** — `meta::legend::compile` is a real engine NATIVE (legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-functions-legendCompiler/legend-engine-pure-functions-legendCompiler-pure/src/main/resources/core_external_compiler/compiler.pure:19 — `native function meta::legend::compile(s:String[1]):PackageableElement[*];`). It returns PackageableElement[*], not FunctionDefinition[*] — the engine compiles the payload into the live graph and hands back whatever kinds it declared, which is exactly the generality clgArm lacks.

**Risk** — Registering parsed elements into the shared ModelContext must be per-test and must not leak across the family session (the runner reuses a global module — see Runner.moduleContextFor0). Step (3) is the classic regression vector: widening the helper-expansion gate has already cost this project 53 tests once; gate it strictly on the presence of a compileLegendGrammar call. TENET-2 TRAP: do not make the harness synthesise the expected mapping by hand-writing the ClassMapping objects, and do not special-case the FQN — the mapping must come from the platform's own ElementParser and be compiled by the platform's own element compiler, otherwise this becomes harness compensation for a missing platform surface.

**Also unblocks** — Nothing else in the 276 that I can confirm. The other compileLegendGrammar corpus users (graphFetch/tests/testGraphFetchMilestoning.pure:645,684,723 and testCrossStoreGraphFetchMilestoning.pure) pass a FunctionDefinition payload, which clgArm already handles.

**Falsifier** — Bind `let mapping = <any existing corpus Mapping ref>` in place of the helper call in a scratch copy of the test. If it then walls somewhere else (e.g. the no-arg milestoned property) rather than running, the dynamic-mapping channel is not the whole story and step (5) dominates the effort. Conversely, if FromChecker's throw is reached with `got TypedPackageableRef` after step (2), the slotting logic at FromChecker.java:81-91 — not the ref recognition — is the next wall.

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/FromChecker.java:74 — `throw new TypeInferenceException("from() argument " + i + " must be a mapping or runtime reference, got " + a.args().get(i).getClass().getSimpleName())`; the only accepting arms above are TypedPackageableRef (line 35) and an instance whose static type is ClassType `meta::core::runtime::Runtime` (lines 53-56).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:826 — clgArm's element loop is `if (el instanceof com.legend.model.FunctionDefinition fd) { fns.add(fd); }`; line 834 `if (idx < 0 || idx >= fns.size()) { return rhs; }` returns the call unchanged for a Mapping payload.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:696 — let-bound helper calls expand only when `pairIdiom || singleExecute || executeTerminal || planChain`; getNoArgFlattenMapping matches none, so its compileLegendGrammar body is never exposed.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/modelToModelToRelational/milestoned/milestonedSourceToNonMilestonedTargetProperty.pure:291 — `getNoArgFlattenMapping()` body: builds a ###Mapping grammar string, `compileLegendGrammar($mappingStr)->at(0)->cast(@Mapping)`, then `^$mapping(includes = ^MappingInclude(included = milestoningMapSmall, owner = $mapping))`.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/modelToModelToRelational/milestoned/milestonedSourceToNonMilestonedTargetProperty.pure:201 — the sibling `testFlatten_ViaAllVersionsMapping` is byte-identical except `let mapping = <mapping element ref>`; it is in no failing brief, i.e. it passes. That isolates the delta to the runtime-built mapping, not to graphFetch/serialize/from.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/typed/TypedUserCall.java:17 — TypedUserCall's `children()` returns only `args`, so even the ClassType-Runtime arm's TypedFrom.chainMappingsIn walk (which recurses over children) cannot see a `^ModelChainConnection` that lives inside a callee BODY.

</details>

---

## `testFlatten_ViaNoArgMapping_ViaAssociation`

| | |
|---|---|
| family | `modelToModelToRelational/milestoned` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | XL |
| confidence | high |

**Root cause**

Identical to testFlatten_ViaNoArgMapping. The only difference in the test body is that the helper is `getNoArgFlattenMapping_ViaAssociation()` and its grammar payload transforms `$src.synonymsMilestonedViaAssociation.synonym` instead of `$src.synonymsMilestoned.synonym`. The mapping is still produced by `compileLegendGrammar(<###Mapping string>)->at(0)->cast(@Mapping)` plus `^$mapping(includes=^MappingInclude(...))`, clgArm still discards it because it collects only FunctionDefinition elements, and `from($mapping,$runtime)` still receives the un-folded TypedUserCall and throws at FromChecker.java:74.

**Fix**

Same five steps as testFlatten_ViaNoArgMapping — there is no separate work for this test beyond step (5), where the milestoned navigation runs through an ASSOCIATION property rather than an owned property. Verify the resolver's association-side milestoning propagation once the mapping is reachable.

**How legend-engine does it** — Same as test 1: core_external_compiler/compiler.pure:19 (`native function meta::legend::compile`).

**Risk** — Same as testFlatten_ViaNoArgMapping. Additionally: the association leg means the milestoning date must propagate across an AssociationMapping, which is a distinct resolver path from the owned-property leg — do not assume one green implies the other.

**Also unblocks** — testFlatten_ViaNoArgMapping (the same fix unblocks both).

**Falsifier** — If step (2) lands and this test then walls with a message naming `synonymsMilestonedViaAssociation` (rather than from()), the mapping channel was the whole harness-side story and the remaining work is purely resolver-side milestoning.

<details><summary>Evidence read (3 citations)</summary>

- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/modelToModelToRelational/milestoned/milestonedSourceToNonMilestonedTargetProperty.pure:312 — `getNoArgFlattenMapping_ViaAssociation()` is the same compileLegendGrammar + `^$mapping(includes=...)` shape, differing only in the transform expression.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:826 — the FunctionDefinition-only element filter that drops the Mapping payload.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/FromChecker.java:74 — the identical throw; the sweep's message text for both tests is byte-identical, which is consistent with one mechanism.

</details>

---

## `resolveSchemaTest`

| | |
|---|---|
| family | `tds/tests` |
| sweep status | SHAPE |
| **verdict** | **TESTS ENGINE INTERNALS** |
| effort | XL |
| confidence | high |

**Root cause**

The corpus helper meta::relational::tds::schema::tests::assertSchemaRoundTripEquality/1 β-expands on the try-run path into meta::pure::tds::schema::tests::assertSchemaRoundTripEquality($query, extensions()) — a 2-arg call. EngineTestExecutor's statement loop routes any AppliedFunction whose simple name startsWith("assert") and whose FQN starts with "meta::" into checkAssert; there is no arm for that name, so the switch falls to `default -> return UNSUPPORTED_MARKER`, and scoreAssert stamps the observed message. That is only the surface. The assert's actual contract is `$query->eval().columns == meta::pure::tds::schema::resolveSchema($query, $extensions)` — i.e. it compares the Pure INTERPRETER's runtime TDS columns against legend-engine's Pure-implemented static schema-inference (`resolveSchema` → `resolveSchemaImpl` over the SchemaState algebra, extension-dispatched). legend-lite implements neither: there is no in-memory Pure evaluator for `Address.all()->project(...)->join(...)` with no mapping/store, and no resolveSchema surface (grep for resolveSchema in core/src/main hits only NameResolver's relational SchemaDefinition resolver and InferenceKernel's SchemaAlgebra — unrelated names).

**Fix**

DO NOT FIX — ledger it. The right change is a wall that names the real gap instead of the assert-form gap: in EngineTestExecutor.checkAssert, before the `default -> UNSUPPORTED_MARKER` arm, add a name check that a meta::-qualified assert* function which is NOT in the harness's known-assert set and IS resolvable via ctx.findFunctionDefinition reports `unsupported("corpus assert helper '<fqn>' has no harness arm; it needs <named pure surface>")` so the row stops reading as a harness gap. If the feature is ever wanted, it is two independent pieces: (a) an in-memory Pure TDS evaluator for `Class.all()->project/join/groupBy/columnValueDifference/extendWithDigestOnColumns` with no store — legend-lite has no object-graph instances at all, so this is a new subsystem; (b) a static schema resolver equivalent to meta::pure::tds::schema::resolveSchema. legend-lite's Typer already computes a Type.RelationType for a compiled query, so (b) alone could be exposed, but (a) is the blocker and implementing the assert host-side in the harness would be textbook tenet-2 harness compensation (the harness would be re-deriving the schema the platform is supposed to own).

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tdsSchema.pure:17 (meta::pure::tds::schema::resolveSchema) and :38-71 (SchemaState algebra); assert body at .../core/pure/tds/testTdsSchema.pure:375-380

**Risk** — Widening harnessVocabName or adding a generic assert* fallback that folds unknown asserts to PASS would hollow-pass this and every other corpus assert helper. Any change here must only improve the MESSAGE, never the verdict.

**Also unblocks** — Any other corpus test whose assert helper lives under meta:: but has no harness arm (the same message shape). Within this unit, none.

**Falsifier** — Grep legend-lite core/src/main for any evaluator that materializes Class instances without a relational mapping (e.g. an `all()`-over-graph path). If one exists, part (a) is not a blocker and the verdict weakens toward MISSING_FEATURE.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:499-501 — statement loop: `if (stmt instanceof AppliedFunction af && harnessVocabName(af.function()) && simpleName(af.function()).startsWith("assert"))` routes to checkAssert
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1798-1799 — harnessVocabName: `return !fn.contains("::") || fn.startsWith("meta::");` so any meta::-qualified corpus helper named assert* is hijacked
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2043-2045 — checkAssert's `default -> { return UNSUPPORTED_MARKER; }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:892-895 — the exact message literal: "assert form '" + af.function() + "/" + af.parameters().size() + "' is not supported yet"
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:627-639 — expandHelperCalls with assertExpansion=true inlines assert-carrying helper bodies on the try-run path, which is how the /1 call becomes a /2 call
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tests/testTdsSchema.pure:134-137 — the corpus helper: `assertSchemaRoundTripEquality(query){ meta::pure::tds::schema::tests::assertSchemaRoundTripEquality($query, extensions()); }`
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/testTdsSchema.pure:364-380 — the /2 body: `let expected = $query->eval().columns; assertSchemaRoundTripEquality($expected, $query, $extensions)` → `$query->meta::pure::tds::schema::resolveSchema($extensions)` → assertSchemaEquality
- /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/tds/tdsSchema.pure:17-71 — resolveSchema/resolveSchemaImpl and the SchemaState class with restrict/extend/groupBy/columnValueDifference: a Pure-implemented static schema algebra

</details>

---

## `testAlloyTestDatGenForNestedViews`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | M |
| confidence | high |

**Root cause**

The test's whole contract lives inside the PARAMETERIZED leg of `mayExecuteAlloyTest({clientVersion, serverVersion, host, port | let result = pathToElement('meta::protocols::pure::'+$clientVersion+'::...::alloyGenerateTestDataWithSeedInteractive_...')->cast(...)->evaluate([...]); assertTestData(<csv>, $result->cast(@String)->toOne(), $db);}, {|true})`. EngineTestExecutor's mayExecute* arm inlines the parameterized leg ONLY when its body references none of its own parameters (EngineTestExecutor.java:337-347). This body references $clientVersion, $host, $port and $serverVersion, so `inner` stays null, `zeroArgLambdaArg` picks the `{|true}` fallback (line 349-351), and the sole statement spliced is the literal `true`, which the CBoolean arm skips without counting. verified=0, advisory=0, executed=0, so Runner.score falls to `no verifying assertions` (Runner.java:1474). CRITICAL COROLLARY: every sibling `*_Alloy` test in testDataGeneration.pure takes the SAME branch — none of them run their alloy leg either. They score PASS only because Runner.score has a rule `verified()==0 && executed()>0 -> PASS("0 asserts — N statement(s) executed")`, and each of them happens to carry a statement-position `createTablesAndFillDb();` that bumps executed. testAlloyTestDatGenForNestedViews is the one test in that family whose setup is a LET (`let runtime = ...::setUp()`), so executed stays 0 and the hollowness surfaces. Second, independent gap: even if the leg were inlined, TestDataGenForm recognises only `generateTestData`, `planTestDataGeneration`, `generateSeedDataString` and `getRelationalCSVDataFromQuery` (TestDataGenForm.java:46/54/57/86/148) — not the alloy `pathToElement(...)->evaluate(...)` entry point — so `$result` would not bind to a tdg Result and `assertTestData/3` would return UNSUPPORTED_MARKER at EngineTestExecutor.java:1504-1520.

**Fix**

Two changes, both in the harness (this is engine test-protocol vocabulary, which the harness legitimately owns).
(1) EngineTestExecutor.java:332-348 — when the mayExecute* parameterized leg references ONLY the four decorative server parameters (clientVersion, serverVersion, host, port), inline it with those four bound as literals in `lets` (e.g. clientVersion='v1_30_0', serverVersion='v1_30_0', host='localhost', port=0). A leg that references any OTHER parameter must keep falling through to the zero-arg leg. Do this before the `break`, so the existing unreferenced-params case is unchanged.
(2) TestDataGenForm — add an alloy recogniser next to `hasGenerate` (TestDataGenForm.java:44-46): match `pathToElement(<foldable string ending in alloyGenerateTestDataWith{Seed,DefaultSeed}{Interactive,SemiInteractive}_...>)->cast(@Function<...>)->evaluate([list(a0), list(a1), ...])`, unwrap the `list(...)` argument wrappers, and route positions 0/1/2/3/4/5 (query, mapping, runtime, executionContext, tableRowIdentifiers, hashStrings) into the SAME `TestDataGenForm.run(...)` path that `generateTestData` uses; drop host/port/version/extensions. Bind the result so that `$result->cast(@String)->toOne()` reads as the binding's `dataCsvString` (i.e. teach TestDataGenForm.read to treat a bare cast/toOne chain over an alloy binding as kind "dataCsvString"). assertTestData then verifies by ROWS through TestDataGenerator.compareCsv exactly as the non-alloy twin does.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/protocols/pure/v1_30_0/invocations/execution_relational_testData.pure:132 — `alloyGenerateTestDataWithSeedInteractive(f, m, pureRuntime, context, tableRowIdentifiers, hashStrings, host, port, version, extensions):String[1]` delegates to `alloyGenerateTestDataWithSeed(...)`, which POSTs a TestDataGenerationWithSeedInput and returns the response entity — i.e. the same CSV string the in-process generateTestData produces. host/port/version are pure transport.

**Risk** — Change (1) is the sharp edge: it will START RUNNING the alloy leg in ~20 sibling tests that currently score a hollow PASS via the executed>0 rule. Expect several of them to flip PASS->SHAPE/FAIL. That is a scoreboard regression but an HONESTY improvement — those passes are currently vacuous. Land (2) with (1) so the tests that can verify do verify, and be prepared to explain the delta. TENET-2 TRAP: do not implement the alloy entry point by having the harness compute or hard-code the CSV; it must call the platform's TestDataGenerator, which is what makes it faithful rather than compensating.

**Also unblocks** — Potentially all ~20 `meta::relational::testDataGeneration::tests::alloy::*_Alloy` tests: today they pass vacuously (0 asserts). After this fix they would actually verify. None of them are in the 276 today, so the net effect on the failing count could be negative in the short term while being correct.

**Falsifier** — Instrument (do not fix) the mayExecute* arm to log which leg it inlines for each *_Alloy test. If it reports the alloy leg (not `{|true}`) for testSimpleSingleTable_Alloy, my reading of referencesAny is wrong and the root cause is elsewhere (most likely the missing alloy recogniser alone).

<details><summary>Evidence read (6 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:341 — `if (lfA.body().stream().noneMatch(st -> referencesAny(st, ps))) { inner = lfA; }` then `break;` — the parameterized alloy leg is inlined only when its params are unreferenced; the comment above it (lines 326-331) says exactly this.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:349 — `if (inner == null) { inner = zeroArgLambdaArg(wrap, lets); }` — the `{|true}` leg wins.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1474 — `yield new Outcome(fqn, Status.SHAPE, "no verifying assertions")` reached only when verified==0 AND executed==0 AND advisory==0 AND sqlDiffs empty; the PASS-on-executed rule sits immediately above it.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:3039 — the failing test's setup is `let runtime = meta::relational::testDataGeneration::tests::model::setUp();` with NO statement-position call; contrast testSimpleSingleTable_Alloy at line 1862 and testConstant_Alloy, both of which carry a bare `createTablesAndFillDb();` statement and are in no failing brief.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/TestDataGenForm.java:46 — `return findCall(rhs, "generateTestData") != null;` is the only generate recogniser; there is no alloyGenerateTestData* arm anywhere in the file.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:1450 — the NON-alloy twin `TestDatGenForNestedViews` runs the identical query/mapping/rowIdentifiers through plain `generateTestData` and asserts the identical CSV; it is in no failing brief, which proves legend-lite's TestDataGenerator already computes this case correctly (so the view-on-view-on-view mapping is NOT the blocker here).

</details>

---

## `testTableToTdsWithJoinAndUnion`

| | |
|---|---|
| family | `testDataGeneration/tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | S (revised up from M by adversarial review) |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

ScanRelations models a `tableToTDS(...)->join(...)` spine as a chain of SINGLE-table sides. parseTdsJoinChain recurses on the join's left parameter and hands each side to parseTdsSource, which strips only `->project(...)` wrappers (ScanRelations.java:404-410) and then requires `collectTableToTds` to find exactly one node: `if (found.size() != 1) throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` (ScanRelations.java:418-424). In this test the join's LEFT side is `project(tableToTDS(personTable)) ->concatenate( project(tableToTDS(firmTable)) )` — a UNION, so collectTableToTds returns two nodes and the wall fires. The concatenate splitter that does exist (scanRoots, ScanRelations.java:123-142) only splits at the TOP of the query body; here concatenate is nested underneath the join, and the tdg consumer relTree bypasses scanRoots entirely, calling tableToTdsRoots directly (ScanRelations.java:180-190). A second, consequential gap is behind the first: even with the left side split, attachTdsJoin resolves the join column through a single flat alias map (`aliases`, populated with putIfAbsent so the left-most branch wins) and attaches the right node to ONE parent via `byTable.get(l[0])` (ScanRelations.java:370-386) — but the engine's expected tree has addressTable as a child of BOTH personTable and firmTable. NOTE ON ATTRIBUTION: the brief's `source` line (lineage/scanRelations/scanRelationsTests.pure:920) conflicts with its `family` (testDataGeneration/tests). Three functions share this short name. The failure text is a BARE NotImplementedException message with no prefix, which is the exact shape tdgLetArm produces (`new Outcome.Unsupported(e.getMessage().split("\\n")[0])`, EngineTestExecutor.java:1418-1426), so the failing test is almost certainly `meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion` (testDataGeneration.pure:1326). The same fix covers the lineage twin regardless.

**Fix**

In /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:
(1) Make a tds side able to be a UNION. Change `TdsSrc` (line ~320) from `record TdsSrc(Node node, Map<String,String[]> own)` to `record TdsSrc(List<Node> nodes, Map<String,String[]> own)` (or add a parallel `unionNodes` list). In parseTdsSource, before the `found.size() != 1` check, split the (project-stripped) expression with the existing `splitConcatenate` helper (line 147) and parse each branch independently, merging their alias maps; keep the wall for the genuinely unrecognised case (found.size()==0, or >1 tableToTDS in a single non-concatenate branch).
(2) Make the alias map branch-aware: `aliases` must map alias -> LIST of [table, physCol] rather than the first binding only (today `own.forEach(aliases::putIfAbsent)` at the end of parseTdsSource silently drops the second branch's 'eID').
(3) In attachTdsJoin (line 327): iterate every owner the left alias resolves to; for each, attach its OWN copy of the right node (deep-copy Node: table/db/schema/cols/keepAll, and a per-parent `cond` built from that parent's own column) rather than sharing one instance. Preserve the existing `labelOverride` construction per copy.
(4) In parseTdsJoinChain (line 294): `roots.addAll(base.nodes())` instead of `roots.add(base.node())`, and sort multi-branch roots by table name to match the engine (reuse the comparator already at line 140) so firmTable precedes personTable.
No change is needed in TestDataGenerator — once relTree returns the two-root/one-child-each tree, the existing per-relation fetch generation produces the four goldens.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelations.pure:482 — the engine never sees this as an AST union at all: it routes the function to SQL and matches on the RelationalOperationElement, where `u:UnionAll[1] | $u.queries->map(q | ... generateRelationTreeFromRelationalOperationElement ...)` produces ONE tree per union branch, each branch carrying its own join subtree. The root then sorts children by relation name + column names (scanRelations.pure:352-360, the `sortBy` in the 4-arg runtime scanRelations), which is where the firmTable-before-personTable order comes from.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Citations all resolve and the mechanism reproduces on paper.

Mechanism: scanRoots calls splitConcatenate on the TOP-LEVEL body only (ScanRelations.java:129), and splitConcatenate (147-158) flattens only a literal 2-param `concatenate` spine with no rule for an enclosing operator. In this test the body IS the outer join, so branches.size()==1. tableToTdsRoots sees containsCall(n,"join") (222); the wrapper-peeling loop at 232-239 does not fire because top already IS the join; parseTdsJoinChain recurses on parameters().get(0) = the concatenate, which is not a join, so it falls to parseTdsSource at 315; parseTdsSource peels only `project` (413-418) so cur is still the concatenate; collectTableToTds (506-531) recurses through AppliedFunction parameters and returns BOTH personTable and firmTable; found.size()!=1 throws at 421-423. LineageRelationsForm.java:134-137 wraps it with the 'scanRelations: ' prefix. docs/RELATIONAL_CORPUS_ALL.md:1295 records exactly the doubled-prefix string for the lineage/scanRelations family. Confirmed end to end.

Expected output: golden is two sibling roots `firmTable [ID]` and `personTable [ID]`, each with `addressTable [COMMENTS, ID, NAME, STREET, TYPE]`, asserted via relationTreeAsString(false) → showLabels=false (LineageRelationsForm:81-85, 127). I traced both branches after the proposed distribution: each parseTdsSource narrows its own root to {ID} via the eID→[table,ID] alias map (459-467); addressTable is bare so keepAll=true keeps all 5; attachTdsJoin resolves l via the branch-local `aliases` and parent via the branch-local `byTable` (fresh LinkedHashMaps per tableToTdsRoots call, lines 240-241) and collectTableToTds builds a NEW Node per call (line 519), so the two addressTable children are independent objects. `if (branches.size() > 1) out.sort(comparing(nd -> nd.table))` at 138-142 yields firmTable before personTable — the golden order. Everything the diagnosis claims falls out.

Fix attack: AppliedFunction.withParameters exists (AppliedFunction.java:95-98) and preserves candidateFqns/pos/propertyCall/grouped/infix; JoinChecker.java:112 does use it. The recursion terminates (the re-entered join's param0 splits to size 1 and falls through to out.add). Regression surface is essentially nil: the new branch fires only when v is a `join` whose param0 splits into >1, and any such query today unconditionally throws inside tableToTdsRoots' join path, so no green test can be sitting on that shape. I specifically checked the nearest sibling, testTdsJoinConcatenateAndJoin (join over concatenate, 5-param string-key join form) — it is class-sourced, so collectTableToTds finds ZERO tableToTDS and it throws the same message today and after the change; no regression, no accidental fix. Effort S holds: ~15 lines in one file, no new IR node, no goldens to re-align.

One scope caveat the diagnosis does not state: splitConcatenate is reached ONLY from scanRoots. relTree (ScanRelations.java:185) calls tableToTdsRoots directly, so the identically-named testDataGeneration/tests twin (docs/RELATIONAL_CORPUS_ALL.md:1411, single 'scanRelations:' prefix) will NOT be fixed by this change. V09's diagnosis is correctly tagged lineage/scanRelations and line 1295 is a real, distinct failing test, so the item stands — but do not expect two greens from it.

</details>

**Risk** — attachTdsJoin's `byTable` map is documented as a LEFT-owner registry so a same-table right side does not shadow the accumulated left (the self-join test). Making it multi-valued must not break testTableToTdsWithJoinToSameTable or testTableToTdsWithMultipleJoin. Sharing one right Node across parents (instead of copying) would produce correct-looking text here but corrupt `cond` for the self-join and multi-join goldens — copy. The root sort must stay gated on branches>1, or single-root goldens reorder.

**Also unblocks** — The two same-named twins: `meta::pure::lineage::scanRelations::test::testTableToTdsWithJoinAndUnion` (scanRelationsTests.pure:920) and `meta::relational::testDataGeneration::tests::alloy::testTableToTdsWithJoinAndUnion` (testDataGeneration.pure:2964) — whichever of the three is not the one currently reported.

**Falsifier** — If the failing test is actually the LINEAGE twin (scanRelationsTests.pure:920) rather than the tdg twin, the fix is the same but the assert is `relationTreeAsString(false)`; check by grepping the sweep for whether the outcome carries an `assert form ...` prefix (tdg walls arrive bare, lineage walls arrive through LineageForm). Either way the diagnosis stands; only the alsoFixes list changes.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:421 — `if (found.size() != 1) { throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source"); }` inside parseTdsSource; the `while` above it strips only `project`.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:294 — parseTdsJoinChain: `parseTdsJoinChain(ctx, af.parameters().get(0), ...)` for the left, `parseTdsSource(ctx, af.parameters().get(1), ...)` for the right, then `attachTdsJoin(cl, right, aliases, byTable)`; the base case is `TdsSrc base = parseTdsSource(ctx, v, ...); roots.add(base.node());` — no concatenate handling anywhere on this path.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:372 — `Node parent = Objects.requireNonNull(byTable.get(l[0]), ...); parent.children.put(right.table + "(tds_join_" + parent.children.size() + ")", right);` — a single parent, a single shared right Node.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:129 — scanRoots splits concatenate at the top of the body only, and sorts roots by table name when there is more than one branch (line 137-141: `out.sort(Comparator.comparing(nd -> nd.table))`).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:181 — relTree calls `tableToTdsRoots(ctx, query)` directly (not scanRoots), so the tdg consumer never gets even the top-level concatenate split.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:80 — `List<ScanRelations.Rel> roots = ScanRelations.relTree(ctx, resolvedQuery, mappingFqn);` — the tdg path goes straight through the walling code.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/testDataGeneration/tests/testDataGeneration.pure:1352 — the engine's own golden for the tdg twin asserts FOUR fetch SQLs: firmTable, then addressTable joined off testDataGen_Temp_firmTable, then personTable, then addressTable joined off testDataGen_Temp_personTable. That is exactly one addressTable child per union branch, branches ordered firmTable-before-personTable.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:920 — the lineage twin's expected tree is `firmTable [ID] / addressTable[...]` then `personTable [ID] / addressTable[...]`, corroborating the duplicated-child shape and the by-table root order.

</details>

---

## `testExecuteInDbToTDS`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

Two layers. Immediate wall: `let result = executeInDbToTDS(...)` matches EngineTestExecutor.letSetupArm (the callee's body transitively contains executeInDb), so the call compiles through the platform. Typer.requiresNormalization treats a function as a NormalizeRequired (TDS-erased) function if it is stereotyped `NormalizeRequiredFunction` OR any parameter is schema-erased OR **its RETURN type is schema-erased** (Typer.java:1236). meta::relational::metamodel::execute::resultSetToTDS(resultSet:ResultSet[1]):TabularDataSet[1] carries NO stereotype and takes a ResultSet, but its return is TabularDataSet, so the return-only clause fires. inlineNormalized then calls SourceSubst.inlineLets on the body, which fails because the body has a non-let intermediate statement (`$tds.rows->map(r|mutateAdd($r,'parent',$tds));` between the lets and the trailing `$tds;`), and throws the observed message. Real gap underneath: even with the misclassification removed, executeInDb is a phase-K boundary that returns `new ExecutionResult.Scalar(null, call.info().type())` — an opaque handle with no columnNames/rows — so resultSetToTDS's body cannot read it; and `$result->toCSV()` has no implementation anywhere in core/src/main.

**Fix**

Three coordinated changes, in this order. (1) Typer.java:1228-1237 — stop letting the return-only clause claim functions that cannot be normalized. Keep the stereotype and the erased-PARAMETER clauses unconditional; make the return-only clause conditional on inlinability: compute `boolean returnOnly = !stereotyped && f.parameters().stream().noneMatch(p -> isSchemaErased(p.type())) && isSchemaErased(f.returnType())`, and in inlineNormalized (Typer.java:1281) when `folded == null && returnOnly`, fall back to `emitCall(chosen, args, out)` instead of throwing. Keep the throw for stereotyped / erased-parameter functions — that contract must stay loud. (2) StatementExecutor.executeInDb (line 3273) must materialize the last statement's JDBC ResultSet into a real value instead of Scalar(null): return an ExecutionResult carrying columnNames (List<String>) and rows, typed as meta::relational::metamodel::execute::ResultSet, and register that metaclass in builtin/Pure.java with `columnNames: String[*]` and `rows: Row[*]` (Row.values: Any[*]) so resultSetToTDS's body (`$resultSet.columnNames->size()->range()->map(...)`, `$resultSet.rows->map(...)`) types and evaluates. (3) Implement `meta::pure::tds::toCSV(TabularDataSet[1]):String[1]` (header line + one line per row, '\n'-terminated, per the test's expected 'Count\n1\n'). `mutateAdd($r,'parent',$tds)` is a no-op for this test's asserts and can stay unimplemented only if step (1) leaves resultSetToTDS as an ordinary call whose body is evaluated statement-by-statement and that statement is tolerated; otherwise it needs a host-side no-op arm.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalExtension.pure:73 (resultSetToTDS, unstereotyped) vs /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tds/tdsExtension.pure:22,96,150,172,209 (the genuinely `<<functionType.NormalizeRequiredFunction>>` TDS functions) — the engine marks normalization explicitly; it does not infer it from the return type.

**Risk** — Change (1) widens what types as an ordinary call; if emitCall cannot produce a usable TabularDataSet type for a call it previously inlined, other TDS-helper tests could regress from 'inlined and typed' to 'called and untyped'. Change (2) is the tenet-2 trap in reverse: do NOT special-case executeInDbToTDS in the harness to return a fabricated TDS — the ResultSet value is platform surface (StatementExecutor owns executeInDb), and faking it in EngineTestExecutor would be harness compensation.

**Also unblocks** — Unknown without a sweep; any other corpus test that binds an executeInDb result and reads it would need change (2) too.

**Falsifier** — Add the `NormalizeRequiredFunction` stereotype question to the probe: if some OTHER corpus test depends on a non-stereotyped, TDS-returning helper being inlined (i.e. change (1) alone regresses the sweep), then the return-only clause is load-bearing and the fix must be narrowed further (e.g. inline only when inlineLets succeeds, otherwise emitCall — which is exactly what the proposed conditional does, so this falsifier is really about whether emitCall can type a TDS-returning call at all).

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1228-1237 — requiresNormalization: `stereotyped || f.parameters().stream().anyMatch(p -> isSchemaErased(p.type())) || isSchemaErased(f.returnType())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1250-1252 — isSchemaErased returns true for PlatformTypes.TABULAR_DATA_SET
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1281-1288 — inlineNormalized: `folded = SourceSubst.inlineLets(...); if (folded == null) throw new TypeInferenceException("NormalizeRequired function '" + ... + "' has non-let intermediate statements — cannot inline")` (exact message)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1196-1230 — letSetupArm: compiles the rhs when ctx.findFunctionDefinition(...) has executeInDb in its body, and wraps a LegendCompileException as `"let-bound setup: " + message` (exact prefix)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:3273-3301 — executeInDb K-native: runs the raw SQL and returns `new ExecutionResult.Scalar(null, call.info().type())` with the comment 'an opaque ResultSet handle: … a test that READS it will surface loudly here when that day comes'
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalExtension.pure:73-85 — resultSetToTDS has NO stereotype and its body is let,let,let,let,let, then the non-let `$tds.rows->map(r|mutateAdd($r,'parent',$tds));`, then `$tds;`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/relationalExtension.pure:87-90 — executeInDbToTDS = executeInDb($sql,$connFn)->resultSetToTDS()
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testRelationalExtension.pure:132-141 — the test body: rows->at(0).get('Count') and $result->toCSV()

</details>

---

## `testEnumTheSame`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

`employeeTestMapping.enumerationMappings` is parsed as an EnumValue node, not a property access: SpecParser.parseDotPostfix turns `<PackageableElementPtr>.<name>` into `new EnumValue(ptr.fullPath(), name, ...)` unconditionally. Typer.enumValue then has exactly ONE disambiguation arm for 'this dotted form is really element-metamodel property access': the receiver is a Database (Typer.java:2652-2661). A Mapping receiver has no arm, so it falls to `ctx.findEnum(ev.fullPath()).orElseThrow(...)` and throws the observed "unknown enumeration 'meta::relational::tests::mapping::enumeration::model::mapping::employeeTestMapping'". Behind that misattributed message the whole enumeration-mapping metamodel surface is absent: builtin Pure's MAPPING_METACLASS declares only `name`, there is no EnumerationMapping metaclass, and neither `toDomainValue` nor `enumerationMappingByName` exists anywhere in core/src/main.

**Fix**

Two parts, both in the platform. (A) Typer.enumValue (Typer.java:2643): generalise the Database special case into an element-kind dispatch. When `ctx.findEnum(ev.fullPath()).isEmpty()`, look the FQN up as a packageable element and, when it is one, emit `TypedPropertyAccess(new TypedPackageableRef(fqn, ExprType.one(new Type.ClassType(<metaclass fqn>))), ev.value(), <property's ExprType>)` where <metaclass fqn> is meta::relational::metamodel::Database for a database (existing arm), meta::pure::mapping::Mapping for `ctx.findMapping(...).isPresent()`, and so on for future kinds; when the FQN names no element at all, keep the current 'unknown enumeration' throw. Also fix the message for the element-exists-but-property-missing case so it names the element's KIND, not 'unknown enumeration'. (B) Build the enumeration-mapping metamodel surface: extend Pure.MAPPING_METACLASS (Pure.java:406) with `enumerationMappings: meta::pure::mapping::EnumerationMapping<Any>[*]`; add a native class `meta::pure::mapping::EnumerationMapping<T>` with `name: String[1]`, `enumeration: Enumeration<T>[1]`, `enumValueMappings: meta::pure::mapping::EnumValueMapping[*]` (enum: Any[1], sourceValues: Any[*]); add natives `meta::pure::mapping::toDomainValue(EnumerationMapping<T>[1], Any[1]):Any[1]` and `meta::pure::mapping::enumerationMappingByName(Mapping[1], String[1]):EnumerationMapping<Any>[0..1]`; and back them in com.legend.exec.MetamodelWalk by projecting MappingDefinition.enumerationMappings() into walk nodes so `->first()->toOne()->toDomainValue('FTC')` evaluates host-side to a TypedEnumValue-shaped value that assertEquals can compare against `EmployeeType.CONTRACT`. toDomainValue must reproduce legend-pure's semantics exactly, including the 'exactly one match' assert.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_EnumerationMapping.pure:18 — meta::pure::mapping::toDomainValue

**Risk** — Part (A) widens what a dotted `X.y` on a packageable element can mean; a Class element named like an enumeration could now resolve to metamodel property access instead of erroring. Keep the enum lookup FIRST (as today) so real enum values are unaffected. Tenet-2 trap: do not add a toDomainValue arm to EngineTestExecutor — enumeration-mapping navigation is model surface the platform owns; the harness must only assert on it.

**Also unblocks** — testEnumMappings and testEnumMappingsWithInclude (same file, lines 165-185) use tradeMapping->enumerationMappingByName('X')->toDomainValue(...) — part (B) is exactly what they need; they do not need part (A) because they use the arrow form.

**Falsifier** — If Typer.enumValue is not the throw site — i.e. the message came from Typer.java:1070 (extractEnumValueFold) instead — the parse is not the EnumValue dot-form and this whole diagnosis is wrong. Distinguish by the message punctuation: line 2665 emits `unknown enumeration 'X'` with quotes and no trailing context, which is what the sweep shows; 1070 emits the same text but only reachable from an extractEnumValue call, which this test does not make.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/parser/SpecParser.java:1350-1359 — `if (receiver instanceof PackageableElementPtr ptr) return new EnumValue(ptr.fullPath(), name, ptr.pos(), spanOf(...));`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2643-2665 — enumValue: the comment 'Enum.VALUE and <dbElement>.property parse identically — a DATABASE element on the left is store-METAMODEL property access', the Database-only arm at 2652-2661, then the `unknown enumeration '" + ev.fullPath() + "'` throw at 2665 (exact message)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:406 — MAPPING_METACLASS = `native Class meta::pure::mapping::Mapping extends meta::pure::metamodel::ModelElement { name: String[0..1]; }` — no enumerationMappings, no classMappings, no includes
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/ModelContext.java:77 — `Optional<com.legend.model.MappingDefinition> findMapping(String fqn);` exists, so the Mapping arm has a lookup to use
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/MappingDefinition.java:39 — the compiled model already carries `List<EnumerationMapping> enumerationMappings`
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_EnumerationMapping.pure:18-22 — toDomainValue: filter enumValueMappings where sourceValues contains the value, assert exactly one, return `.enum`
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMapping.pure:94-101 — the test body

</details>

---

## `testStoreSubstitution`

| | |
|---|---|
| family | `tests/mapping/include` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | high |

**Root cause**

Two independent absences, the assert form being the one that surfaces first. (1) `assertIs` is a real legend-pure platform assert (meta::pure::functions::asserts::assertIs(Any[1],Any[1]) = assert(is($expected,$actual), ...)) but EngineTestExecutor.checkAssert has no arm for it — the switch falls to `default -> UNSUPPORTED_MARKER` and scoreAssert stamps "assert form 'assertIs/2' is not supported yet". (2) Even with an arm, the operand `simpleRelationalMappingInc->resolveStore(dbInc)` cannot be evaluated: `meta::pure::mapping::resolveStore` (and its helper findSubstituteStore) exists nowhere in legend-lite — grep over core/src/main finds only NameResolver's unrelated resolveStoreSubstitution(s) helpers. The DATA is present (MappingDefinition.includes() → MappingInclude.substitutions() → StoreSubstitution(originalStore, replacementStore)); legend-lite consumes it eagerly as a REWRITE in normalizer/StoreSubstitutionRewrite rather than exposing the query 'which store does this mapping resolve X to?'.

**Fix**

(A) Platform: add `meta::pure::mapping::resolveStore(_this:Mapping[1], store:Store[1]):Store[1]` as a native in builtin/Pure.java, backed by a host-side fold in com.legend.exec.MetamodelWalk that mirrors legend-pure exactly — for each MappingInclude of the mapping, first recurse into the included mapping (findSubstituteStore), then map the result (or, if none, the argument store) through this include's substitutions; first non-empty wins; if nothing matches, return the argument store unchanged. Represent the result as the store's FQN string wrapped in the walk's element-handle node, NOT as Scalar(null). (B) Harness: add an `assertIs`/`assertIsNot` arm to checkAssert (EngineTestExecutor.java, next to assertEquals) that accepts 2-4 args (message args ignored, matching the pure overloads), evaluates both sides, and compares by ELEMENT IDENTITY (walk-node FQN equality), returning UNSUPPORTED_MARKER — never a pass — when either side evaluates to null/an opaque handle. Do (A) before (B): (B) alone would compare two Scalar(null) handles and hollow-pass all four asserts.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:110-114 (resolveStore) and :137-146 (findSubstituteStore over MappingInclude)

**Risk** — Tenet-2 trap, stated explicitly: adding ONLY the assertIs arm makes this test pass vacuously (both sides null ⇒ identical) while verifying nothing. That is the worst possible outcome here — a wrong PASS. Also: legend-lite's eager StoreSubstitutionRewrite means the substitution data must still be readable from MappingDefinition.includes() AFTER normalization; if normalization consumes/erases includes, resolveStore must read the pre-normalization model.

**Also unblocks** — Any corpus test using assertIs (a common pure assert) — the arm is reusable; the resolveStore native is specific to the mapping/include family.

**Falsifier** — Check whether `dbInc` in value position already evaluates to something with identity in legend-lite (grep StatementExecutor for a Store/Database-typed value arm). If a Database element value already yields its FQN rather than Scalar(null), then only (A) plus a naive (B) is needed and the hollow-pass risk is lower. If it yields Scalar(null), (B) must guard as described.

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:1782-2045 — checkAssert's full switch: arms for assert/assertFalse, assertEquals/assertEq/assertEqualsH2Compatible/assertNotEquals, assertSameElements, assertContains, assertEqWithinTolerance, assertSize, assertEmpty, assertNotEmpty, assertInstanceOf, assertTdsEquivalent, assertSameSQL, assertJsonStringsEqual — no assertIs; `default -> UNSUPPORTED_MARKER` at 2043
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:892-895 — the message literal
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/MappingInclude.java:22-33 — MappingInclude(mappingPath, List<StoreSubstitution>) with StoreSubstitution(originalStore, replacementStore)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/StoreSubstitutionRewrite.java:32-62 — legend-lite applies substitutions by REWRITING included class mappings, not by answering resolveStore
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:3036-3070 — the orchestration-handle arm: Connection/Runtime-typed values return `ExecutionResult.Scalar(null, ...)`; this is the hollow-pass trap for an identity assert over element values
- /Users/neemsandv/legend/legend-pure/legend-pure-core/legend-pure-m3-core/src/main/resources/platform/pure/essential/tests/assertIs.pure:16-24 — assertIs = assert(is($expected,$actual), message)
- /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/functions_Mapping.pure:105-114 — findSubstituteStore(Mapping) folds over includes; resolveStore returns the substitute or the original
- /Users/neemsandv/legend/legend-pure/.../platform_dsl_mapping/functions_Mapping.pure:137-146 — findSubstituteStore(MappingInclude): recurse into the included mapping FIRST, then map the result through this include's storeSubstitutions
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/relationalSetUp.pure:785-787 — `Mapping meta::relational::tests::simpleRelationalMapping ( include simpleRelationalMappingInc[dbInc->db] ...` confirming the expected answers

</details>

---

## `testRelationStoreAccessorOnView`

| | |
|---|---|
| family | `tests/mapping/relation` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

An asymmetric unquote between the protocol→model conversion of a table's COLUMN DECLARATION and of a COLUMN REFERENCE, which makes every view that projects a double-quoted column unresolvable. DatabaseProtocolParser.parseIdentifier deliberately keeps a relational QUOTED_STRING's quotes as the wire name. FromProtocol.table() then stores the ColumnDefinition name BARE plus a `quoted` flag (`unquote(c.name())`, `isQuoted(c.name())`). RelOpFromProtocol.columnRef() unquotes the SCHEMA and TABLE but passes `c.column()` through verbatim — so a ColumnRef keeps its quotes. StoreCompiler.viewSchema compares `c.name().equals(cr.column())`, i.e. bare `FIRST NAME` against quoted `"FIRST NAME"`, finds no column, and returns Optional.empty(); resolveTable therefore reports nothing and TableReferenceChecker throws "unknown table 'personView' in database '...testDB'". Note that StoreCompiler.tableSchema RE-ADDS the quotes when building a Type.Column (`col.quoted() ? "\""+col.name()+"\"" : col.name()`), which is the project's stated convention — viewSchema is simply not following it. testDB's personView projects `name: personTable."FIRST NAME"`, so it hits this on its third column mapping.

**Fix**

Minimal and convention-consistent: in StoreCompiler.viewSchema (StoreCompiler.java:96-97), compare against the SAME quote-bearing identity tableSchema builds, not the bare stored name — replace `.filter(c -> c.name().equals(cr.column()))` with `.filter(c -> (c.quoted() ? "\"" + c.name() + "\"" : c.name()).equals(cr.column()))`. Factor the expression into a private `static String columnIdentity(ColumnDefinition c)` used by BOTH tableSchema (line 169) and viewSchema so the two cannot drift again. Do NOT instead unquote the column in RelOpFromProtocol.columnRef: ColumnRef.column() is consumed by the renderers and by ScanRelations/MappingNormalizer/ViewRelation, which rely on the as-written spelling to emit `"FIRST NAME"` in SQL; changing it there would silently unquote generated SQL.

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Mechanism holds end to end and I could reproduce every link by reading. (a) The observed wall in the sweep brief is verbatim `no execute(|...) call [...] — wall: unknown table 'personView' in database 'meta::relational::tests::mapping::relation::testDB'`, which can only come from TableReferenceChecker.java:73-75. (b) ModelContext.findTable -> PureModelContext.findTable:346-350 -> resolveTableWithIncludes:436 -> StoreCompiler.resolveTable:30-41, so viewSchema is genuinely on the path. (c) The parser keeps quotes on both sides: parseTable's column decl uses parseIdentifier (DatabaseProtocolParser.java:324) and the relational-op column ref uses it too (:1017-1027), and parseIdentifier (:90-99) returns QUOTED_STRING text raw. (d) FromProtocol.table():206-208 stores the decl BARE + quoted=true; FromProtocol.view():251-259 routes the projection through RelOpFromProtocol.op, and columnRef():71-79 unquotes schema/table but NOT c.column(). So viewSchema's `c.name().equals(cr.column())` compares `FIRST NAME` to `"FIRST NAME"` and bails on personView's third mapping. (e) Falsifier discharged: the DB clearly loads — tests.pure has 66 tests in this file and only 6 fail, and the other 5 in family tests/mapping/relation reached execution (status FAIL, not this wall), so 'db absent' is excluded. Fix is right for this test and does not touch ColumnRef, so SQL rendering of quoted columns is unaffected. Two caveats that V10.md drops but the underlying row carries: (i) the fix will NOT make the test green — its asserts pin the engine's plan-JSON envelope ('"sql":"select \"personview_0\"…' plus the '"result" : {"columns"…' block); the residual is golden-text. (ii) The proposed comparison is exact-identity (re-add quotes, then equals). It only handles a view whose ref spells the column exactly as declared. The codebase already owns a quote-insensitive comparator for precisely this — InferenceKernel.sameColumn/stripColQ:376-383 — and using that normalization would also cover a view referencing a quoted column with the bare spelling (or vice versa). I'd take sameColumn's rule over re-adding quotes, but the stated fix does resolve personView.

</details>

**Citation issues found in review** — DatabaseProtocolParser.java:282-294 is parseTabularFunction, not the Table column declaration; the real Table column decl is :311-345 (parseIdentifier at :324). Same conclusion, wrong function cited. TableReferenceChecker cite says 70-73; the throw is at 73-75.

**Risk** — After this fix the test still has to clear its own asserts, which pin the ENGINE's exact plan JSON envelope (`'"sql":"select \"personview_0\"…'` and the `"result" : {"columns" …}` block). ElqSplice binds executeLegendQuery's result to `toString(<query>)` (ElqSplice.java:88-104), not to a plan JSON envelope, so assert 1 will not hold and the test will move from SHAPE to FAIL on golden engine text. That is honest progress (the wall is real and worth removing) but the test will not go green from this fix alone — the residual is GOLDEN_TEXT_ONLY. Also add a regression check that generated SQL for a quoted column still renders quoted.

**Also unblocks** — Any other test that reaches a view projecting a double-quoted column — the same viewSchema bail-out returns 'unknown table' for the view. Unknown count without a sweep.

**Falsifier** — Compile any view in the corpus that projects ONLY unquoted columns through `#>{db.thatView}#`. If that also reports 'unknown table', the cause is not the quoted column but the module never loading the database, and this diagnosis is wrong. (The message is shared between 'db absent' and 'view schema unresolved' because ModelContext.findTable flatMaps over findDatabase — a second falsifier is worth having: make the two cases emit different messages.)

<details><summary>Evidence read (9 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/TableReferenceChecker.java:70-73 — `t.model().findTable(dbRef.fullPath(), resolvedName).orElseThrow(() -> new TypeInferenceException("unknown table '" + resolvedName + "' in database '" + dbRef.fullPath() + "'"))` (exact message)
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/StoreCompiler.java:30-41 — resolveTable falls back to `findViewDef(db,name).flatMap(v -> viewSchema(db, v))`, with the comment 'a view with a non-column-ref projection stays unresolved (same outcome as an unknown name)'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/StoreCompiler.java:96-100 — `var col = td.get().columns().stream().filter(c -> c.name().equals(cr.column())).findFirst(); if (col.isEmpty()) return Optional.empty();`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/element/StoreCompiler.java:167-170 — tableSchema: `col.quoted() ? "\"" + col.name() + "\"" : col.name()` with the comment 'a QUOTED declaration carries its quotes IN the column identity (the Typer's quote-bearing RelationType convention)'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/FromProtocol.java:206-208 — `new DatabaseDefinition.ColumnDefinition(unquote(c.name()), dataType(c.type()), ..., isQuoted(c.name()))`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/RelOpFromProtocol.java:71-79 — table/schema go through unquote(), the column does not: `new RelationalOperation.ColumnRef(db, table, c.column())`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/parser/DatabaseProtocolParser.java:90-99 — parseIdentifier: 'Relational identifiers admit QUOTED spellings, which KEEP their quotes as the wire name'
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/parser/DatabaseProtocolParser.java:282-294 — table column decls use the same parseIdentifier, so `"FIRST NAME"` reaches the wire quoted
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/relation/relationMappingSetup.pure:22-29 and :77-82 — personTable declares `"FIRST NAME" VARCHAR(100) NOT NULL` and personView projects `name: personTable."FIRST NAME"`

</details>

---

## `testAdjustDateTranslationInMappingAndQuery`

| | |
|---|---|
| family | `tests/mapping/sqlFunction` |
| sweep status | SHAPE |
| **verdict** | **HARNESS GAP** |
| effort | XS |
| confidence | high |

**Root cause**

The statement `$toAssertDbTypes->map({db | let s1 = toSQLString(...); let s2 = toSQLString(...); assert($s1 == $s2); })` is host orchestration whose lambda body carries an `assert` — vocabulary the platform can never type. EngineTestExecutor already has the unroller for exactly this shape (enumDriverLoop, which substitutes each literal enum element into the lambda body and splices the statements), but enumDriverLoop takes only the raw statement and never dereferences `lets`: its source check is `esrc instanceof PureCollection ? pc0.values() : List.of(esrc)`, and here esrc is the Variable `$toAssertDbTypes` bound by the preceding `let toAssertDbTypes = [DatabaseType.DB2];`. A Variable is neither an EnumValue nor an AppliedProperty over a PackageableElementPtr, so the strict element check fails and enumDriverLoop returns null. resultVarLoop and alloyFallback also miss, so the statement falls through to the K-natives arc, is substituted and pushed into the platform, and StoreResolver.resolveNode has no arm for a TypedMap whose source is a collection of enum values (the only TypedMap arm requires a RelationType source), producing the observed 'class query under TypedMap is not resolvable yet (H2 vocabulary)'. driverPairLoop, immediately below enumDriverLoop, already does the let-dereference correctly — the two helpers simply diverged.

**Fix**

Give enumDriverLoop the same let-dereference driverPairLoop already has. Change its signature to `enumDriverLoop(ValueSpecification stmt, Map<String, ValueSpecification> lets)`, thread `lets` through spliceForms (EngineTestExecutor.java:762) and its call site (line 374), and inside enumDriverLoop replace `ValueSpecification esrc = emap.parameters().get(0);` with a deref: `ValueSpecification esrc = emap.parameters().get(0); if (esrc instanceof Variable v) { ValueSpecification bound = lets.get(v.name()); if (bound != null) esrc = bound; }`. Do NOT substitute the whole statement before spliceForms — resultVarLoop deliberately pattern-matches a PureCollection of VARIABLES as its elements, and a blanket subst would destroy that match. Keep the strict literal-enum element check exactly as it is (its comment records the testComplexOrExistsToManyProperty misfire it prevents).

**Risk** — Widening what enumDriverLoop unrolls could swallow a genuine relational `$var->map(...)` QUERY as a host loop. The strict element check (all elements literal EnumValues or dotted reads off a PackageableElementPtr) is what prevents that and must be kept — only the SOURCE lookup changes, not the element predicate. After the unroll the test still has to clear its remaining asserts: `assert($s1 == $s2)` compares two toSQLString results (likely routed advisory), and the real verification is `assertEquals([%2003-07-12T…, %2003-07-13T…], $result.values->at(0).rows.values)` plus assertSameSQL over `dateadd(day, -7, "root".dateTime)` — i.e. the adjust()/DurationUnit.DAYS lowering must be correct on DuckDB. Removing the wall does not guarantee a PASS.

**Also unblocks** — None found — `$var->map({...})` in a test body appears exactly once in the corpus (testSqlFunctionsInMapping.pure:631); every other `->map({` is inside library code, not a test statement.

**Falsifier** — Set LL_TMP_DEBUG and re-run just this test: if the try-run ledger shows the `map` statement arriving with a PureCollection source rather than a Variable, the let never bound and the diagnosis is wrong. Equivalently, check that `let toAssertDbTypes = [DatabaseType.DB2];` really lands in `lets` (EngineTestExecutor.java:467, `lets.put(name.value(), purifiedSetup(rhs, ctx))`) rather than being consumed by letSetupArm or the tdg arm.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2206-2247 — enumDriverLoop(ValueSpecification stmt): no `lets` parameter; `ValueSpecification esrc = emap.parameters().get(0); List<ValueSpecification> evs = esrc instanceof PureCollection pc0 ? pc0.values() : List.of(esrc);` then the STRICT allMatch over EnumValue / AppliedProperty-over-PackageableElementPtr
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2262-2265 — driverPairLoop does the dereference the other helper lacks: `ValueSpecification src = m.parameters().get(0); if (src instanceof Variable var) { src = lets.get(var.name()); }`
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:762-771 — spliceForms(stmt) is called with the RAW statement and no lets; it tries enumDriverLoop, then resultVarLoop, then alloyFallback
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:374-379 — the spliceForms call site, before the letFunction arm and before the assert arm
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:531-556 — the K-natives arc that swallows the statement and reports `"statement '" + af3.function() + "' failed through the pipeline: " + message` (exact message shape, af3.function()=='map')
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/resolver/StoreResolver.java:466-510 — the only TypedMap arm requires `m.source().info().type() instanceof Type.RelationType`; the `default ->` at 506-509 throws `"class query under " + n.getClass().getSimpleName() + " is not resolvable yet (H2 vocabulary)"` (exact message)
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/sqlFunction/testSqlFunctionsInMapping.pure:627-641 — the test body: `let toAssertDbTypes = [DatabaseType.DB2]; $toAssertDbTypes->map({db | let s1 = ...; let s2 = ...; assert($s1 == $s2); });` then the real execute + assertEquals on rows + assertSameSQL

</details>

---
