# Audit: the three planner defects the tenth line found (branch `core/iq-finds`, 2026-10-14)

The independent audit agent's report on the branch's first three commits (22b4db1d4, e2224812b, 704084d52 over main
afd07af19). The commits were then rewritten with the two should-fixes and the four nits taken (the landed commits are
named in GATES, "The three planner defects the tenth line found"). Read-only; the auditor ran no Bazel. Quoted as
delivered.

---

Audit of core/iq-finds (22b4db1d4, e2224812b, 704084d52 over afd07af19). I ran only git and source reads, no Bazel.

**Verdict: ready after fixes.** 0 blockers, 2 should-fix, 4 nits. The three reported defects are fixed and each test
would fail on the parent code. But the integer fix leaves the same wrap in LiteralUnroll's own equality path, which is
the main `==` fold.

**Should-fix**

S1. The `==` fold over two spelled integers still wraps. This is the same defect class, on the main path.
- `compiler/spec/LiteralUnroll.java:84-85`: `literal()` turns a TypedCInteger into `Scalar(INTEGER, i.value().longValue())`.
- `equalityFold` compares those Scalars with `.equals` (`:415-417`). It is reached for `equal`/`eq` at `:556-557`.
- So `99999999999999999999 == 118446744073709551615` still folds to TRUE.
- The same wrapped Scalar also drives `contains` (`:454-464`), `in` (`:578-580`), the map `get` lookup (`:527-535`)
  and groupBy keys (`UserCallInliner.java:1054` via `scalarValue`). In groupBy, two different large keys merge into
  one group.
- Path: `LiteralUnroll.fold` runs on every inliner rewrite (`UserCallInliner.java:1163`). `NormalizeFolds.fold` runs
  only through `foldInlined` → `deepFoldInlined` (`UserCallInliner.java:1133-1147`, inlined bodies only). So the
  NormalizeFolds eq fix does not cover the user-query `==` path.
- Fix (one line): at `:85` use `i.big()`.
  - No reader casts a Scalar value to Long (grepped LiteralUnroll and UserCallInliner).
  - `big()` also evens out Integer-boxed values (`CorrelatedSubselects.java:2486` builds `new TypedCInteger(1, ...)`),
    so it is safer than storing raw `i.value()`.
- Add an assertion that goes through `LiteralUnroll.fold` for `equal` and `eq` (BEYOND vs BEYOND_PLUS_2_64).
- The commit message and the IN_FLIGHT announcement say the eq/equal fold was "NormalizeFolds (eq, equal)". That claim
  is true, but it is not the path a user query takes.

S2. Some later rebuilds of a TypedConcatenate drop the united stamps again. The announcement does not name them.
- `grep "new TypedConcatenate("` finds sites that take one side's info:
  - `resolver/ImportDataFlowAppend.java:76` — `l.info()`
  - `resolver/DriverPkAppend.java:58` — `l.info()`
  - `resolver/StoreResolver.java:486` — `a.info().type()`
  - `resolver/StackBuilder.java:1934` — `out.info().type()`
  - `resolver/SyntheticHeads.java:248` — `member.info()`, the right side
- The first two rebuild an existing concatenate after adding a column to each side. They replace the typer's united
  info (now correct) with the left side's.
- StoreResolver:486, StackBuilder:1934 and SyntheticHeads:248 unite sides built from one projection or pipe, so their
  stamps likely match. `ClassConcatenates.java:56` checks the two schemas are equal first, so it is safe.
- I did not prove the reported symptom (a test folded away) is reachable through these resolver-stage rebuilds.
- Either rebuild those two with `ConcatenateChecker.unionType` over the two sides, or name them in the announcement as
  known and out of scope, with a dated note.

**Answers to the brief's questions**

1. What goes into `TypedCInteger.value`:
   - Long: the parser `NumberLiterals.java:37`, `ProtocolReader.java:289`, `SqlToPure.java:2657`, and most
     synthesised sites.
   - BigInteger: only beyond long (`NumberLiterals.java:42`, `ProtocolReader.java:289`, `SqlToPure.java:2657`).
   - Integer: autoboxed at `CorrelatedSubselects.java:2486/2520` and `GroupLambdaAggs.java:98`.
   - `big()` is exact for all three.
   - Other `longValue()` reads in compiler/spec and lowering are the index/offset/precision kind, not comparisons of
     two spelled integers, and are out of scope. They still wrap silently for literals at or above 2^63. For example,
     `at(18446744073709551616)` picks element 0 at `ExecuteChainAssembly.java:267-268`,
     `ResultEnvelopeSplice.java:347/480/551` and `CastChecker.java:82`. Others: `Lowerer.java:545-547/1305/2450`,
     `ConstBounds.java:23`, `Frames.java:127/147`, `Scalars.java:763`, `TdsDesugars.java:713`, and StaticFold's
     Long-only evaluator (`StaticFold.java:315`, the PLUS/MINUS cases near `:474`). A ledger row would be the right
     home.
2. The test reaches the product path. `LiteralUnroll.fold` sends a TypedNativeCall to `nativeFold` (`:335`), and
   `is()` matches by group membership (`:373-379`). The test's `call()` takes the first overload in the group, which
   may be a Date overload rather than the `NUMBER_1__NUMBER_1` the typer picks. The fold result is the same, but see
   nit N1.
3. StampCensus:
   - MANY arm: unchanged. `definitelyScalar` never matched a Case (`StampCensus.java:170-182`).
   - A no-ELSE CASE with list branches: unchanged. Both `listShaped` (needs an ELSE, `:154-157`) and `nullOnly` return
     false.
   - The asymmetry hides no new lie: both readings of a missing ELSE lead to "don't fire".
   - There is an older gap: `CASE WHEN c THEN <list> END` does not fire, while the same CASE with `ELSE NULL` does
     (the test's "mixed" case). Reading a missing ELSE as NULL in `listShaped` would close it. Out of scope.
   - The test's no-ELSE assertion (`StampCensusTest.java:35`) also passes on the parent code, so it does not pin the
     defect. The other two assertions in that test do.
4. unionType:
   - `relationSchema` is non-null only for a RELATION_CARRIERS GenericType with exactly one RelationType argument
     (`Type.java:433-437`). So both callers always take the GenericType branch, which keeps rawFqn and multArguments.
     RelationType has only columns and dynamicColumns, both kept.
   - `Multiplicity.union` (min lower, max upper, unbounded wins; `Multiplicity.java:83-96`) is the right join for a
     column stamp.
   - A Var stamp now throws loudly where it used to pass silently. Var only comes from signature text and
     `SignatureApart:157`; I found no column producer.
   - Columns beyond the common prefix keep the left's, which matches the arity rule (`ConcatenateChecker.java:89-95`).
   - The TypedRename info held the left's schema before this change. The union is a wider and still-correct stamp, so
     it is no regression for any reader.
   - ExecuteChainAssembly: the null guard keeps the old behaviour for non-relation queries, and
     `folded.info().multiplicity()` keeps the old multiplicity. Both are right.
5. See S2.
6. The tests catch the defects: the parent code wraps BEYOND to 7766279631452241919 (below Long.MAX_VALUE), so the
   gt, lt and eq assertions fail there. The concatenate test's [0..0] and [1] cases fail on the parent too.
   `[]->cast(@String)` is exactly what the SQL islands emit for NULL/STRING (`SqlToPure.java:3384,3393`).
   BOOLEAN/OTHER emit a bare `[]` (`:3389-3391`), which the test does not cover. The new test classes run under the
   existing per-package targets `core_tests_compiler` and `core_tests_lowering` (`core/BUILD.bazel:523-536`); no
   BUILD change is needed.
7. All added lines and commit-message lines are within 120 columns. All six dates read 2026-10-14. The commit
   messages match the diffs, with the S1 caveat on scope.

**Nits** (each a one-line fix)

- N1 `core/src/test/java/com/legend/compiler/spec/ExactIntegerFoldTest.java:51`: filter on the typer's own overload
  (`GREATER_THAN__NUMBER_1__NUMBER_1` and so on) rather than the first in the group, so the test copies what the
  typer builds.
- N2 `compiler/spec/ConcatenateChecker.java:139`: the `: leftType` fallback can never be reached and would silently
  return the stamps un-united. Make it throw.
- N3 `core/src/test/java/com/legend/lowering/StampCensusTest.java:35`: say in its message that this pins existing
  behaviour; it is not a regression check (it passes on the parent).
- N4 `ExactIntegerFoldTest.java:32` (Javadoc) says "99999999999999999999 + 2^64" but sits above BEYOND as well as
  BEYOND_PLUS_2_64. Move it onto the constant it describes.

---

## Taken

- S1: `LiteralUnroll.literal` builds the spelled-integer `Scalar` from `big()`, so the inliner's equality fold,
  `contains`, `in`, a map's `get` and groupBy keys all compare the exact value; `ExactIntegerFoldTest` pins `eq` and
  `equal` through `LiteralUnroll.fold` as well.
- S2: one factory, `TypedConcatenate.unionInfo(left, right, m)`, computes every relation concatenate's info from both
  sides: the typer's (`ConcatenateChecker.positional`, `ExecuteChainAssembly`) and the resolver's five rebuilds
  (`ImportDataFlowAppend`, `DriverPkAppend`, `StoreResolver`, `StackBuilder`, `SyntheticHeads`) alike.
  `ClassConcatenates` keeps its two: the first unites schema-equal sides behind an equality check, the second is the
  class-collection overload with the typer's own info.
- N1: the test names the typer's overloads (`GREATER_THAN__NUMBER_1__NUMBER_1`, `LESS_THAN__NUMBER_1__NUMBER_1`,
  `EQ__ANY_1__ANY_1`, `EQUAL__ANY_MANY__ANY_MANY`) by `FunctionId.of`.
- N2: the fallback throws (the factory's; the checker's copy is gone).
- N3, N4: taken as written.
- Question 1's index-kind `longValue()` reads: a ledger row (PARK-37) with the `at()` read as its anchor.
- Question 3's older `listShaped` gap (a no-ELSE CASE of list branches never fires): left as the auditor placed it,
  out of this landing's scope; said in GATES.
