# Phase 4 redesign + Z1 re-judgment — one decision, proposed 2026-08-19

Status: EXECUTED 2026-08-19 — see the execution record (§E) at the bottom.
AMENDED by homework: the
"position decides" principle is RETIRED — legend-engine has NO SQL translation for
any assert (its adapters execute only the inner expression in the store), and the
one expression-position assert in the corpus (testComplexOrExistsToManyProperty:85,
`values->map(f|assert(...))`) is a verdict over an already-executed result.
**Asserts are verdicts, always.** (In execution the Scalars SQL assert rule was
DELETED outright — the quantified K-arm serves the map witness, so no residual was
needed; the "demote then die in Phase 5" fallback below is superseded.)

Context: the Phase-2 deep audit ratified **Charter Clause 2c** (two worlds, one
spec: verdicts belong to the host adjudication layer, in-query computation to the
compiler — neither reimplements the other). Channel B as built violated it:
it compiled the assert library's pure bodies into SQL to produce verdicts, and the
five burn slices taught the SQL world tricks it should never have needed. Those
changes are on main. This document re-judges every one of them under Clause 2c and
proposes the channel-B design that makes the deleted ones unnecessary.

## A. The new channel-B assert seam (the design)

A PCT test body is statements whose roots are assert-family calls:
`assertEquals(expected, $f->eval(|expr))`. Under Clause 2c:

1. **The assert call is the VERDICT layer** — a K-dispatch arm in the statement
   executor (exactly the shape `assertError` already has). It is *platform-owned
   dispatch*, not harness code: statement-root calls to the assert family
   (`assertEquals`, `assertSameElements`, `assertSize`, `assertEq`,
   `assertEqWithinTolerance`, `assert`, `assertFalse`, `assertEmpty`, …)
   route to the K-arm.
2. **Each argument executes through the full pipeline, in the database** —
   the expected side and the actual side each compile and run as ordinary
   expressions (tenet #1 intact; the expression under test exercises parser,
   compiler, lowering, and DuckDB end-to-end, which is channel B's whole point).
3. **The verdict is World 1**: `PureAsserts.assertEquals(e, a)` (or the family
   member) over the two fetched sides — the spec-exact, pinned, Phase-2 layer.
   The assert library's pure bodies are never β-inlined into SQL again.
4. Asserts NOT at a statement root (compositions) decline loudly with the shape —
   a named gap, not a silent skip. (The essential corpus is statement-rooted
   almost universally; the census will say exactly how many decline.)

This is the same execute-args-then-adjudicate shape the corpus harness has always
used — but owned by the platform, with the Phase-2 layer as the single verdict
authority. It also makes channel B measure per test what channel A measures
(our execution of the expressions), plus our parser/compiler, minus the
interpreter — an apples-to-apples diff at last.

## B. Re-judgment of the five burn slices (what keeps, what dies)

### KEEP — real platform fixes, independent of any assert seam
| Change | Why it stays |
|---|---|
| `assertError` natives + K-arm (message compare) | Clause-2c-shaped already; spec = interpreted `AssertError.java` |
| Date guards: month-aware day (`Invalid day: 2016-12-32`), spec messages | `DateFunctions.validateDay` verbatim; referee-clean |
| `InferenceKernel` rigid-lattice conformance (`eval({a:Number[1]|…}, 1.0)`) | genuine inference bug; engine-true; unit-pinned |
| `ListEncodings.map` wire-shape (wrap scalar sources, unwrap to-one results, null-guard [0..1]) | fixed a REAL user-visible break (`head()->map(...)` was a Binder error); pinned by `MapOptionalSourceTest` |
| `MAP_KEYS`/`MAP_VALUES` in `LIST_PRODUCERS` | factual (map readers produce lists) |
| Promoted-decimal carrier-edge rounding (44-digit literal) | parser/typer literal handling; spec-grounded; independent of asserts |
| `TypedNativeCall.pos` + the `withChildren` conversions + equality-excludes-pos | span metadata is foundational compiler hygiene; the conversions fixed a real rebuild-drops-fields trap class |
| The frontier oracle (engine DuckDB manifest snapshot) + three-bucket diff | census machinery, not semantics |
| Guardrail extractions (`ListEncodings`, `ConnectionFlags`, `DateCtorRule`) | refactors, behavior-free |

### DELETE — existed only to serve assert bodies in SQL
| Change | Why it dies | Consequence |
|---|---|---|
| **The U+001E sentinel channel end-to-end**: `Scalars.withSrc` span-in-message emission, `AssertErrorNative`'s decode + line/col arms, the adapter's sentinel strip | production error messages must not carry a control-character wire protocol; source info is not observable from database errors | `assertError` verifies MESSAGE only; line/col args refuse loudly. The 6 line/col tests become AGREE-FAIL (channel A cannot pass them either — they were B-FIXES-A vanity, not conformance) |
| `CastPolicy` `[1]==1` unwrap arm | literal-only partial collection equality — closed-vocabulary smell | W2 collection-vs-scalar equality becomes a **named gap** (loud), owned by the future wire-shape leg; W1 handles all verdicts |
| `CastPolicy.equalityWireOperand` dual-wrap arm (with its IN-needle and property-nav carve-outs) | same — carve-outs bolted after regressions are the tell | same |
| `Scalars` static-empty equality arm (`[] == x` → emptiness) with its Nil/Any/value-kind carve-outs | same (the `[] == []` verdicts move to World 1) | the conformance fixture's `[] == []` row re-pins to W2's honest verdict (SQL NULL — a DECLARED divergence, like the null row already is) |

The deleted arms' PCT rows all pass under design A (their comparisons move to
World 1, which handles empties, `[x]≡x`, and mixed trees correctly and
pinned). Prediction to verify at execution: essential PASS ≥ 290 minus the 6
line/col rows moving from B-FIXES-A to AGREE-FAIL; **TRUE-WIRE-BUG stays 0**.

## C. What this does NOT decide (flagged, separate rulings)

- **Z2 — wire-policy scope**: `equalScalar` applies the corpus wire policies
  (TDSNull, 2-ULP, temporal bridge) unconditionally; whether a product caller
  should get them, or whether they parameterize per channel, is its own design
  call.
- **The wire-shape unification leg** (W2 collection equality done properly,
  one carrier convention): named, future, unblocked by these deletions.
- Phase-3 findings batch (at-fold deletion, single-query design, skip census):
  parked, unchanged by this proposal.

## D. Execution plan (if approved)

1. Build the K-arm assert dispatch (design A) with its own spec tests.
2. Delete the B-list in one commit-series with the referee + conformance
   fixture at every step; re-pin channel B's census to measured.
3. Re-run the essential census; update the three-bucket + frontier pins;
   record the 6 line/col rows' honest reclassification.
4. Full ladder (suite, referee, gates); plan + charter records; push.
5. Post-push re-audit (the phases-1&2 loop), then the parked Phase-3 batch.

## E. Execution record (2026-08-19)

Executed in full, one batch, all gates green.

**Built:** `AssertVerdicts` (the K-arm) — statement-root assert-family dispatch
intercepted PRE-INLINE in `StatementExecutor.executeStatements` at the `bare` stage
(after the effect check); arguments execute in the database via the new
`StatementExecutor.evalValue`; `PureAsserts` adjudicates. The QUANTIFIED arm:
`map(f|assert(pred[,literal-msg]))` rebuilds the TypedMap with the predicate as
mapper body, evaluates in DB, judges the boolean vector host-side, first false
raises the message. Unknown assert-family members fall through (null) — witnessed
growth only. `side()` flattens ExecutionResult honestly (null scalar→empty,
java.sql.Array cells→elements, Tabular/Graph decline loudly). Spec tests:
`AssertVerdictsTest` (3) carries the real assertEquals.pure bodies and proves the
pre-inline intercept.

**Deleted (the B-list, wholesale):** the Scalars SQL assert rule (born 8d04fe5d);
the U+001E sentinel channel end-to-end (Scalars.withSrc emission, AssertErrorNative
span decode + line/col arms, the adapter's sentinel strip — line/col expectations
now refuse loudly via NotImplementedException); the equality seam arms
(CastPolicy.equalityWireOperand dual-wrap + carve-outs, Scalars static-empty arm,
the [1]==1 unwrap) — CastPolicy and the equal rule reverted to their pre-Phase-4
shape (comparisonWireOperand both operands).

**World-1 completeness (spec-witnessed, exposed by the honest crossing):**
array-cell flattening; OffsetDateTime repr arm; integral×Decimal equality is
NUMERIC (spec witness testIntToDecimal); the temporal string-carrier bridge is
SYMMETRIC — the designed partial-precision carrier sits on either side, the parse
is the typing-bug catch, not the direction (old one-direction pin re-adjudicated
in PureAssertsTest; JavaEvalLedgerTest pin 250→264 with written justification).

**Registers:** F1.3b root java.sql pin += AssertVerdicts; JDBC census MAIN/TEST
rows added; ChannelBEssentialTest re-pinned at measured.

**Measured (prediction §B verified):** census **PASS=290/327** (kept), diff
**AGREE-PASS=286 AGREE-FAIL=21 WIRE-BUG=14 B-FIXES-A=4 DECLINED=2**, frontier
**ENGINE-FRONTIER=14, TRUE-WIRE-BUG=0 (pinned)**. The 6 line/col rows reclassified
B-FIXES-A→AGREE-FAIL exactly as predicted. Better than the seam-arm era
(280/15/20/10) on every honest axis. Functions referee byte-identical (FAILS and
SHAPES) vs the frozen baseline; core suite 4156/4156; ALLGATES GREEN (1,2,4,5,6,7,8).

### E.1 Post-push re-audit (2026-08-19, the phases-1&2 loop)

Six findings, all fixed and re-verified (referee byte-identical, census
290/286/14/0 unchanged, core 4156):

1. **ScalarStats still registered a SQL rule for `assertEqWithinTolerance`**
   (`abs(e-a) <= d` — a verdict computed in SQL, the exact Clause-2c shape).
   DELETED; the witnesses (times/plus/pow/cbrt PCT tests) are statement-root —
   the K-arm's territory — and the census confirmed the rule was dead code.
2. **`TypedNativeCall`'s source-span channel was write-only** — burn slice 1
   threaded the parser's name-token span in solely for the sentinel embedder,
   which this redesign deleted. The `pos` field, the 4-arg `emitCall`, AND the
   custom semantic-equality override (which existed only to exclude `pos` and
   had caused two referee regressions) all DELETED — the record is back to
   default (fully semantic) equality. Protocol nodes keep their spans; a
   future diagnostics leg re-threads from there.
3. Orphaned "assert in VALUE position" comment in Scalars — deleted.
4. `AssertErrorNative` javadoc still cited the deleted `Scalars#withSrc`
   sentinel — rewritten to the message-only contract.
5. `CarrierPurityRatchetTest` pins tightened back (ArrayLit 37→36,
   SqlFn.LIST_ 138→137) — the deleted seam arms were their justification.
6. `AssertVerdicts` registered in `JavaEvalLedgerTest` (221 stripped lines,
   adjudication orchestration — arguments still execute in the database).

### E.2 Cross-phase zoom-out (2026-08-19, user-directed: audit phases 1-4 as one arc)

Promise-vs-reality sweep over every phase's contract:

- **Phase 1 promises**: GridReads/HostEval/HostResultSet — files GONE, zero live
  refs (survivors are provenance comments). BUT the "ledger rows deleted, not
  bumped" clause was not honored: `JavaEvalLedgerTest` still carried
  `HostEval.java, 132` and its missing-file arm silently `continue`d, so the
  stale row was invisible. FIXED: row deleted, and a missing file now FAILS
  loudly ("EVICTED WHOLE — delete this ledger row") — the cleanup contract is
  mechanical, not cultural. Channel-list and E4.e comments updated.
- **Phase 2 promises**: wireEquals GONE (file and refs); two comments still
  cited it as the LIVING spec (Scalars' mean-cast rationale, Json's Decimal
  strictness) — reworded to the live semantics/owner (PureAsserts.equalScalar).
  One-owner check: harness TdsEquivalence is a 53-line delegating shim to
  GridCompare; EngineTestExecutor delegates equality — no third impl anywhere.
- **Phase 3 promises**: GridSplice/ResultNav GONE, ResultSet platform class
  live, tripwire green in-suite. Open items are exactly the PARKED findings
  batch (at-fold deletion, single-query design, skip census) — nothing new.
- **Phase 4**: this document's §E/§E.1.
- **Boundary crossings adjudicated legal**: `PureAsserts.repr` used by
  TestDataGenerator/builtin (SPELLING reuse, not verdict logic); GridCompare
  main-with-harness-consumers is Clause 2b by design (platform owns the
  policy, harness consumes it).
- **StatementExecutor size pin PULLED FORWARD** (was first recorded as an
  observation; the standing don't-defer directive says otherwise): registered
  in `JavaEvalLedgerTest` at 2,695 stripped lines, shrink-only — the
  K-orchestrator absorbs by design, and absorption that should have been
  COMPILATION is exactly what a silent-growth watch catches. The ledger's own
  javadoc still cited deleted `GridReads.tryLower` as live — fixed with it.
- **Observation (no action, recorded)**: AssertVerdicts declines Tabular/Graph
  loudly today; GridCompare is the designed route for grid verdicts when a
  witness appears. Z2 (wire-policy scope: equalScalar applies TDSNull/2-ULP/
  temporal-bridge unconditionally — should a product caller get test-path
  tolerances?) remains the most consequential open ruling.

## F. The UPSTREAM-DEFECT category (ratified 2026-08-19)

The line-by-line adjudication of channel A's ~36 expected failures surfaced a
THIRD category beside fixable and irreducible: **upstream language defects** —
places where legend-pure's two executors (interpreted vs relational) give
DIFFERENT answers for the same expression, and upstream documents the split
only by excluding those tests from its relational-DuckDB manifest. The
witnessed family: indexOf/substring base offsets (interpreted 0-based/-1-miss;
relational translates to locate()/substring verbatim, 1-based/0-miss — the
engine's own corpus goldens pin the 1-based answers its PCT tests contradict).

A 0-based emission was BUILT AND MEASURED (essential 290→297, B-FIXES-A 4→11,
functions referee byte-identical) then REVERTED by ruling: with one executor we
must pick a side, either side breaks parity with half of upstream, and the
product surface (drop-in/corpus, engine-relational parity) wins. These rows
live OUT of the burn queue permanently, named as the defect they are.
**The ENGINE-FRONTIER diff category IS this defect class** — every frontier row
is upstream disagreeing with itself; the census (Essential 14, Grammar 5,
Standard 1) is our standing inventory of the language's own ambiguities.

The irreducible set proper, after the full 36-row adjudication:
**object identity (eq/equalNonPrimitive ×2 — a wire cannot carry reference
identity) and expression-tree reflection (testMatchWithMixedReturnType ×1).**
Everything else is fixable, upstream-defect, or already B-FIXES-A (8 rows,
including the null-vs-empty joinStrings wire case).

### F.1 Carrier-domain: adjustBy BigNumber ×4 (probed + ratified 2026-08-19)

`testAdjustBy{Hours,Days,Weeks,Months}BigNumber` target years 1,410,404 /
33,803,336 / 236,611,261 / 800,002,016. DuckDB 1.5.0 probe (every
construction route into a wide timestamp, all FAIL):

- `TIMESTAMP_S '<literal>'` string parse: capped at the µs-TIMESTAMP range —
  year 290000 parses, year 300000 dies (`Could not convert string to INT64`),
  so TIMESTAMP_S's int64-seconds STORAGE range is unreachable from strings;
- `CAST(BIGINT AS TIMESTAMP_S)`: `Unimplemented type for cast`;
- `to_timestamp(bigint)` / `make_timestamp(bigint)`: µs-domain results,
  capped ~294247;
- `strptime` with an 8-digit year: parse error;
- interval arithmetic: `to_days(12345678912)` overflows the INT32 days field
  (and the months case needs 9.6e9 months, also INT32-overflow);
- `epoch()` returns DOUBLE (precision loss at these magnitudes), and
  `CAST(DOUBLE AS TIMESTAMP_S)` is also unimplemented.

There is NO DuckDB date/datetime construct that can materialize a value
beyond year ~294,247. Under tenet #1 (the database executes; no host-side
date carrier), these four rows are **carrier-domain irreducible**. Ratified
by the user 2026-08-19 ("if there is no date/datetime construct that works
in duckdb ... we add that to irreducible").

**The locked irreducible census (14):** index-base upstream-defect ×7
(sortWithKey/sortWithFunctionVariables ride substring's base, collection
indexOf ×1, string substring ×2, string indexOf ×2), object identity ×2,
match/deactivate ×1, BigNumber carrier-domain ×4. Ceiling: 1036/1050.
