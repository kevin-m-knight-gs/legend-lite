# VERDICT DISAGREEMENT BURN — the 9 rows CLOSED AS NAMED RESIDUE (2026-08-30)

Standing: charter §4N adjudicated these as WIRE-FIDELITY findings.
This document carries the engine-source receipt set, the ATTEMPTED
canon burn and why it was REVERTED (the wrong layer), and the final
disposition: the 9 are a DESIGNED referee divergence, pinned EXACT.

## Final disposition

The dual-channel disagreement is between our PRODUCTION equality
(pure-faithful: Decimal scale-sensitive per R2, temporal
subsecond-string-exact per R7 — the value-lane "follow the type
system literally" ruling) and the ENGINE OBSERVABLE (its own decode
is lenient: DECIMAL erased to double in the store lane (R3),
identity in the execute transformer (R8); temporals NINE-digit in
the transform path (R5) but raw-driver-print in the TDS path (R6) —
TWO conventions our ONE-CARRIER architecture deliberately unified).
The harness referee matches the engine observable; production
matches pure. They disagree BY DESIGN on exactly this class.

ATTEMPTED AND REVERTED (the wrong-layer lesson): normalizing the
canon spellings (decimalCanon zero-strip + temporalCanon nine-digit)
made the BYTE channel agree while production still said false —
which FIRED THE DUAL-VERDICT ALARM (3 new inner disagreements,
host=false sql=true). A referee must not be edited into agreement
with one side of a genuine semantic split; the split itself is the
fact. Reverted same session, receipt in the [canon] ALARM lines.

BURNING BELOW 9 requires ONE of: (a) re-ruling production equality
for wire-decoded Decimal/DateTime to the engine's decode-lenient
observable (a charter-level pure-vs-engine VALUE ruling — user's
call, the lane-ruling family); or (b) per-lane decode conventions
(re-fragmenting the one-carrier design). Neither is taken
unilaterally. The 9 are pinned EXACT with this document as the
receipt.

## Receipts (all read at source, paths in the reference checkouts)

- R1 — H2 wire scale (EMPIRICAL, our pinned oracle jar 2.4.240,
  H2ScaleProbe): `DECIMAL(18,6)` holding 1.234000 → getBigDecimal =
  scale 6 (`1.234000`); `.equals(new BigDecimal("1.234"))` = false.
- R2 — engine compiled equality (CompiledSupport.java:969-1011):
  `eq(Number,Number)` = `equals || toString-equals`; BigDecimal-vs-
  Double explicitly FALSE; `equal()` delegates numbers to the same
  eq. Scale-SENSITIVE. (Matches X2's receipt.)
- R3 — engine store-lane DECIMAL decode
  (ResultSetValueHandlers.java:112-120): `getBigDecimal(i)
  .doubleValue()` — DECIMAL wire ERASES to double in the legend-pure
  store lane.
- R4 — engine execute-lane DECIMAL decode (RelationalResult.java:
  596-601): raw `getBigDecimal` (scale-carrying).
- R5 — engine timestamp transform (ResultSetValueHandlers.java:82-90
  and RelationalResult.java:561-570): BOTH transform paths call
  `PureDate/DateFunctions.fromSQLTimestamp` →
  `String.format("%09d", getNanos())` (DateWithSubsecond.java:57) —
  transformed temporal values carry EXACTLY NINE subsecond digits.
- R6 — engine execute-lane RAW value path (RelationalResult.java:
  465-473): TDS-side timestamp stays a bare `java.sql.Timestamp`
  (its own toString/CSV formatting — NOT nine-digit).
- R7 — pure date equality (AbstractPureDate.java:31-51): field-wise
  + `Objects.equals(getSubsecond(), ...)` — subsecond STRING exact
  (`.000000000` ≠ `.000000`).

## Consequences

1. **Temporal spelling is PER RESULT LANE**, by the engine's own
   code: transformed/object/graph values = nine digits (R5); TDS raw
   cells = the driver Timestamp's own print (R6). The 2 graphFetch
   nine-digit rows burn by spelling OUR wire-cell canon at nine
   digits IN THE GRAPH/VALUE lanes; testDayOfMonth (TDS lane) needs
   the TDS-side convention derived from the engine CSV/grid
   formatter before any change.
2. **Decimal — DERIVED (R8 closes it)**: R8 = the execute-lane
   transformer (core SetImplTransformers.java:32-48, buildTransformer
   :86-105) special-cases ONLY Boolean and the Date family; Decimal
   passes through IDENTITY, and TEMPORARY_DATATYPE_TRANSFORMER
   passes BigDecimal unchanged. Combined with R3 (store lane erases
   DECIMAL to double via doubleValue()), the engine's own passing
   dataType goldens can only be passing through SCALE-ERASED
   observables (the store/interpreted lane those .pure suites run
   in). CONSEQUENCE: for WIRE-vs-literal Decimal pairs the engine
   observable is numeric (scale-erased); our verdict canon for
   Decimal cells normalizes trailing fractional zeros on BOTH sides
   (single owner: decimalCanon — the literal side flows through the
   same speller). X2's scale-sensitive receipt (R2) stands for
   IN-MEMORY Decimal equality; the wire arrives erased before that
   equality ever runs — both receipts coexist by layer.
3. Sort-TIE row (testConcatenateWithJoin): the charter's phantom
   class — golden encodes ONE legal tie order. Disposition: NAMED
   ceiling residue (a re-plan legally produces the other order).
4. Milestoning TDSNull row-string: repro needed (null-cell concat
   spelling across lanes).

## Plan

- Slice D1: graph-lane nine-digit canon (+ unit pin) — burns 2.
- Slice D2: locate the engine Decimal reconciliation (read pure-lane
  property transform end-to-end); then canon per finding — burns 4.
- Slice D3: TDS temporal convention (engine grid/CSV formatter
  receipt) — burns 1 (testDayOfMonth).
- Slice D4: TDSNull row-string repro + fix or named residue — 1.
- Slice D5: tie row → named ceiling with receipt — 1.

## SQL-EXEC LANES — the register (2026-08-30 close)

UNABLE-TO-EXEC = 45, EXACT-pinned since P0.5; every row named below
(sweep attributions in the session logs). unverifiable = 5 (h2-exec),
attributed alongside. "Burned" here means named + pinned + receipted;
the two starred buckets are the genuinely burnable follow-up legs.

| bucket | n | disposition |
|---|---|---|
| graph-keys mismatch (golden selects assoc/stitch aliases the frame does not carry: [lastName, zzfirmId] vs [lastName] etc.) | 12 | *BURNABLE LEG: a stitch-key rule (golden-only aliases that are association-key columns of the mapping = engine assembly bookkeeping, droppable like pk_/u_type) — referee surgery with mask-risk, needs its own witnesses |
| enum-decoded columns (decode map underivable) | 7 | *BURNABLE LEG: per-test derivation reads (mappingFqnOf/decodeOf return null for these — frame-kind widening probed 2026-08-30, measured ZERO and reverted; the gap is mapping recovery, not frame kind) |
| predicate-diverged | 6 | fragment-check predicates recorded as divergence by design (slice-3 2026-08-28 record) |
| both-ours | 5 | both comparison sides are OUR sql (no engine golden to execute) — counted by construction |
| tempTableForIn missing tables | 4 | goldens reference ENGINE-RUNTIME temp artifacts (TEMPTABLEFORIN_N) — unexecutable as standalone text BY CONSTRUCTION |
| no-generator-noreplay | 4 | no SQL generator on the assert's route — named |
| match-noreplay | 2 | text byte-matched, no replay needed — bookkeeping row |
| forced-isolation VALUE frames | 2 | engine debug-mechanism pins (P2 adjudication, addendum §8) |
| column arity | 2 | golden/frame arity differs by harness-added columns — layer difference |
| row-cardinality skew | 1 | distinct rows agree, duplication differs — the set-vs-row counted class |

h2-exec unverifiable 5: paginate-with-variables, groupBy date-period
(the ledgered lift-gap leg), tempTable query-chaining x2,
view-chains-with-business-date — each a named standing leg.
## ROW-BY-ROW RESEARCH (2026-08-30, user-ordered — every row, no sampling)

UNABLE-TO-EXEC = 35 after the stitch-key burn. Every row classified
at SOURCE (engine .pure + our harness code), corrections to the first
register marked:

| rows | class | verdict (receipt) |
|---|---|---|
| 7 | enum-decode (5 groupBy address.type + testDayOfWeekFunction + tdsFilter/tdsProject enum reads) | **BURNABLE — ONE ROOT FIX**: PlanText.enumMappingOf iterates only the NAMED mapping's enumerationMappings; the GE EnumerationMapping lives in simpleRelationalMappingInc and every test names the INCLUDER (relationalSetUp.pure:785 include; :418 GE def). Fix = include traversal. |
| 4 | tempTableForIn missing tables (testInExecutionWithTempTableAndQueryChaining + OnIntegerColumn) | **BURNABLE — CORRECTED from "by construction"**: the temp table's contents ARE statement 0's own golden results (test source :306-316: stmt 0 = select distinct FIRSTNAME, stmt 1 reads tempTableForIn_validFirstNames). A chained-replay synthesis (exec stmt 0 -> materialize tempTableForIn_<var> -> exec stmt 1) is fully derivable. Medium leg. |
| 2 | arity golden-1-vs-frame-2 (same two tempTable tests, their stmt-0 frames) | same chained-replay leg (our stmt-0 frame carries an extra column vs the 1-column golden — investigate inside that leg) |
| 6 | predicate-diverged | BY-DESIGN recorded text divergences (EngineTestExecutor:2190 — slice-3 policy: an evaluated fragment-check that fails records divergence, "no golden exists to row-replay a fragment"). Burn = per-fragment text convergence (emission-anatomy). |
| 5 | both-ours | **ZERO DEBT — mislabeled lane**: two OUR-side renderings compared for IDENTITY and VERIFIED (EngineTestExecutor:1233-1245); nothing to execute against by definition. Lane-relabel candidate. |
| 4 | no-generator-noreplay | no SQL generator on the assert route — named; attribution run queued |
| 2 | match-noreplay | our text BYTE-MATCHED the engine golden; only the replay is blocked (ADVISORY_MARKER — seed class). Strongest possible text evidence already in hand. |
| 2 | forced-isolation VALUE frames | adjudicated (P2, addendum §8) |
| 1 | row-cardinality skew (testQualifierQueryWithOr) | adjudicated fan-shape class: distinct rows agree, duplication differs (in-target vs flat fan counts; charter decision-2 territory; divergenceOrSkew is the designed verdict). Burn = flat-form emission (emission-anatomy). |
| 2 | graph-keys frame-extra (milestoning stock) | our single-statement frame carries MORE than the one golden selects — engine multi-statement assembly; undecidable from one golden (verify claim when that leg opens) |

Burnable now, ranked: enum include-traversal (7, small), tempTable
chained replay (6, medium). Everything else is by-design, adjudicated,
or an emission-anatomy dependency — no floating rows remain.

## CLOSING-ARC RESULTS (2026-08-30, commit 7cfdf144)

Burned since the row-by-row register: enum include-traversal (8 rows;
implemented HARNESS-side after the Java-eviction ledger correctly
rejected the PlanText placement), tempTable chained replay (8 asserts
— referee capability, NOT platform: our compiler inlines in-lists;
the synthesis makes ENGINE goldens executable for verification), and
the probe-confirmed PLATFORM bug fix (orderedDedup lambda-subquery —
value-lane distinct now executes). UNABLE-TO-EXEC = 21, every row
named: predicate-diverged 6 (by-design text records), both-ours 5
(verified identity checks — zero debt), no-generator 4 (toSQLString
generate-only shapes — no execution exists, category-inapplicable),
match-noreplay 2 (BYTE-MATCHED engine text, replay blocked:
testSqlGenerationForAdjustStrictDateUsage{InFilters,InProjection}ForH2,
tdsExtend::testDecimal attributed), forced 2 (P2 adjudication),
tempTable statement-pairing arity 2, fan-shape skew 1, graph
frame-extra 2 (multi-statement stitch).
