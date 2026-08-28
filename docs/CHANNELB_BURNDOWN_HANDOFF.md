# Channel B PCT burndown — the handoff (opened 2026-08-27, fresh-session charter)

## 0. LANDING RECORD — THE BURN IS COMPLETE (2026-08-27, same day)

**Essential 297 → 316/327, Grammar 133 → 135/137, eight gated batches,
each pushed after a full green allgates** (`183aeb33`, `9465a235`,
`2aa40bee`, `e9d3bfba`, `cd61bfd4`, `01d509dd`, `1d71da33`). Every
winnable row in the roster below is burned; the remainder is the
ledger (9 rows + the DRAFT 2-row sort amendment pending user
sign-off) and the 2 UNOWNED Grammar rows (see PROGRAM_MAP). The §2/§3
mechanism notes below are HISTORY — most did not survive contact (the
dossier branch `docs/channelb-burndown-dossiers` re-diagnosed legs
5/6/7/7b; its README synthesis is the accurate post-mortem):

| Leg | Landed as | The mechanism that was actually true |
|---|---|---|
| 1 fold/F17 | 183aeb33 | '+=' desugar via AppliedProperty; NULL struct slots render their declared type |
| 2 positions | 9465a235 | span rides the raise emission (U+001E inside the U+001F envelope → RaisedErrors.Positioned) |
| 3 match LUB | 2aa40bee | engine FunctionType LUB + the Env.exprAlias let-syntax channel (the hidden second wall) |
| 4 fn refs | e9d3bfba | name qualification only (mangled-id base resolution); eta-expansion pre-existed |
| 5 mixed sort | e9d3bfba | rank-struct comparable per interpreted Compare.java groups; the 2 key-sort rows were A1-substring contamination, NOT ordering bugs |
| 6 toString | cd61bfd4 (tie) + 01d509dd | Nil-tie-break (superseded by registry provenance when it lands) + qualifier-shadows-native + '@_'||__id default print |
| 7 parseDate | cd61bfd4 | NOT a date leg: the A24/D92 boolean-carrier fork (has* now BoolLit unconditionally) |
| 7b walls | cd61bfd4 (R0 only) | the 6 parse walls bank ZERO rows (no PCT.test functions); the 2-row item was elementFqns() publishing primitive extensions |
| 3b deactivate | 1d71da33 | compile-time reflection fold to TypedTypeRef; TypedMatch carries its declared all-branch LUB |
| 8 BigNumber | (doc, this commit) | formal adjudication in PROGRAM_MAP — our 4 pins byte-match the reference manifest |

Deferred, explicitly NOT burndown: the 6 parse walls (drop-in-parity
work, leg 7b R1–R4 — SpecParser at 3440/3500 splits first); the
registry-provenance partition (dossier leg6 D7 — deletes the Nil
tie-break, fixes []->sum/[]->max/->sort([]) family-wide). The 2
formerly-unowned Grammar rows BOTH LANDED 2026-08-28
(testPlusInIterate — fold-strategy closure; getAll::testBasic — the
metamodel-store leg, METAMODEL_STORE_HANDOFF.md §10): Grammar is
137/137, the FULL lane. Next arc: the test-corpus program (§5).

---

**Mission (user-ratified):** burn Channel B PCT to **100% modulo the
nine adjudicated ledger rows** (5 indexOf/substring 1-based + 4
adjustBy*BigNumber large dates). THEN the test-corpus arc (PROGRAM_MAP
"longer arc" #1): parse+compile-only census first — the wall inventory
IS the point — execution second; per the standing user ruling, the
corpus assert migration to SQL verdicts runs as ONE leg only after PCT
is done, which this burndown delivers. Burning Channel B burns the
matching Channel A rows for free — the mechanisms are all
platform-side.

**Predecessor state:** F10 slice 4 + the truthfulness-burn campaign
are CLOSED and pushed (`bc9ab622..8c2c87f0`, 19 commits 2026-08-27;
charter = [ADAPTER_NECESSITY_CENSUS.md](ADAPTER_NECESSITY_CENSUS.md),
read §5b/§5c before touching the adapter). The pure adapter reads the
test's declared type ZERO times; error provenance rides the U+001F
sentinel (`exec.RaisedErrors`, ONE owner at the Executor funnel);
`CFloat` carries exact digits under the Float label (B8 — proven from
the reference source: their interpreted Float IS BigDecimal-backed).
**Lowerer.java sits AT its 3500-line hard guardrail — the seam-split
PRECEDES any future work in that file.**

---

## 1. The roster (measured 2026-08-27, full lane 1115/0 green)

Channel B: Relation 355/355, Standard 204/204, Unclassified 95/95 —
DONE. Remaining: **Essential 297/327 (15 FAIL + 15 ERROR)** and
**Grammar 133/137 (4 ERROR)**.

### The ledger (modulo — stays, 9 rows)
- `indexof::testIndexOfOneElement`, `indexOf::testFromIndex`,
  `indexOf::testSimple`, `substring::testStart`,
  `substring::testStartEnd` — user-adjudicated IRREDUCIBLE (register
  A1: 1-based indexing IS real core_relational pure semantics; a
  reverted draft proves the trap — do NOT re-attempt).
- `testAdjustBy{Days,Hours,Months,Weeks}BigNumber` — dates beyond
  DuckDB's physical range; the reference DuckDB target fails
  identically (its manifest carries the SAME enveloped error texts —
  see the B7 receipts). **Leg 8 formalizes this adjudication into the
  ledger — it is still marked "pending one formal adjudication" in
  PROGRAM_MAP Bucket 2.**

### The winnable 21 (Essential) + 4 (Grammar), with today's exact errors

| Leg | Rows | Today's error |
|---|---|---|
| 1. fold / F17 | testFoldFiltering, testFoldToMany | `unknown function 'otherNames'` (post-desugar — see §2, IN FLIGHT) |
| 2. assertError positions | testAtError, testDayOfMonthError, testHourError, testMinuteError, testNewDateError, testSecondError | `assertError line/column: source position is not observable from database errors` |
| 3. match over function values | testMatchWithFunctionsAsParam, ...ManyMatch, ...ExtraParamsAndFunctionsAsParam | `no common supertype for {Integer[1] -> Integer[1]} and {String[1] -> Integer[2]}` — the kernel needs a FunctionType common-supertype rule |
| 3b. deactivate port | testMatchWithMixedReturnType | `unknown function 'deactivate'` — unported platform function |
| 4. named-function refs as values | testContainsWithFunction, testRemoveDuplicatesPrimitiveStandardFunctionExplicit | `'comparator_...'/'cmp_...' is not a known class, mapping, runtime...` — a user function passed BY REFERENCE resolves as an element ref |
| 5. comparator ordering | testSimpleSortWithFunctionVariables, testSimpleSortWithKey (expected DESC ['Smith','Doe','Branche']), testRemoveDuplicatesPrimitiveStandardFunctionMixedTypes (first-occurrence order `[1,2,3,'1','3']`) | wrong ORDER — comparator/key semantics don't reach ORDER BY |
| 6. toString over instances | testPersonToString, testComplexClassToString | `toString over ClassType[...] is not modeled`; + testRemoveDuplicatesEmptyListExplicit: `ambiguous overload of 'relation::toString': 2 candidates tie` |
| 7. parseDate kinds | testParseDateTypes | `Assert failed` (instanceOf checks over parsed kinds — likely近 after B8/J8a; re-diagnose first) |
| 7b. Grammar residue | 4 ERROR rows | file-level parse walls in the grammar sources: `new.pure [113:72] expected BRACE_OPEN but found PAREN_OPEN`, `addColumns.pure expected type name after '@'`, `getUnitValue.pure`/`newUnit.pure` (units grammar), `cast.pure expected EXTENDS but found PAREN_OPEN`, `toMultiplicity.pure` — the walls also cost 3 essential discovery rows (walls ≤ 20 pin in ChannelBEssentialTest) |
| 8. BigNumber formal adjudication | (ledger write-up, no code) | |

## 2. Leg 1 IN FLIGHT — F17 fold: the exact state

**Diagnosed (register receipt):** `^$acc(prop += $x)` — the parsed
`KeyExpression.isAdd` flag is DROPPED at `NewChecker.checkCopy`, so
`+=` silently behaved as `=` (wrong accumulators, a silent-value
class). Second half: unset to-many fields emit untyped NULL (the old
`VARCHAR[] -> "NULL"` cast error).

**The desugar was written and PROBE-TESTED, then REVERTED to leave the
tree clean for this handoff.** Re-apply verbatim in
`core/src/main/java/com/legend/compiler/spec/NewChecker.java`,
`checkCopy`, replacing `TypedSpec value = t.synth(key.value(), env);`:

```java
            // F17: '+=' APPENDS to the receiver's property (real pure's
            // copy-add semantics) — desugared HERE to
            // concatenate(receiver.prop, value), so downstream the
            // override is the ordinary construction shape. The parsed
            // isAdd flag was previously dropped: '+=' silently behaved
            // as '=' (the fold family's wrong accumulators).
            com.legend.protocol.spec.ValueSpecification overrideExpr = key.value();
            if (key.isAdd()) {
                var propRead = new com.legend.protocol.spec.AppliedFunction(
                        name, java.util.List.of(receiver),
                        java.util.List.of(), null, true, false, false);
                overrideExpr = new com.legend.protocol.spec.AppliedFunction(
                        "concatenate",
                        java.util.List.of(propRead, key.value()),
                        java.util.List.of(), null, false, false, false);
            }
            TypedSpec value = t.synth(overrideExpr, env);
```

**The probe's finding (the next step):** with the desugar in, both
fold tests moved FAIL→ERROR: `unknown function 'otherNames'` — the
injected property-READ node (`AppliedFunction(name, [receiver],
propertyCall=true)`) fell into the FUNCTION lookup instead of property
resolution. Find how the Typer routes parser-built `propertyCall=true`
nodes (the `$x.prop` path works everywhere, so the difference is
something the parser sets that this hand-built node lacks — check
`candidateFqns`, `pos`, or a separate synth entry for property
access) and match it. THEN re-probe:
`cd pct && mvn -o test -Dtest=ChannelBEssentialTest -Dchb.only=fold
-Dlegend.engine.root=... -Dlegend.pure.root=...` — 8 fold tests PASS
today; the two targets must join them, none may regress. The
`VARCHAR[]->NULL` half may vanish with the desugar (the accumulator
init shape changes) — re-diagnose only if it survives.

## 3. Mechanism notes per leg (design seeds, verify before building)

- **Leg 2 (positions):** B7's provenance envelope is the channel. The
  raise emission (`SqlFn.ERROR` render arm, AnsiSqlRenderer + H2)
  knows its node; thread the PURE SOURCE POSITION inside the sentinel
  (`chr(31)||'<line>:<col>|'||msg||chr(31)` — pick a separator that
  can't collide, or a second sentinel char), parse it off in
  `RaisedErrors` (position stashed on the rethrown SQLException —
  SQLState or a subclass), consume in `AssertErrorNative` (delete its
  loud line/column refusal). Positions must be the TEST source's own —
  Channel B compiles the reference .pure files directly, so our spans
  ARE their coordinates; VERIFY on one witness before building all.
  Production text stays clean (the funnel strips the envelope). NOTE
  Lowerer is at its size limit — the emission arm lives in the
  RENDERERS, not Lowerer, so this should not touch Lowerer; if it
  must, the seam-split comes first.
- **Leg 3 (function-type LUB):** `InferenceKernel.commonSupertype`
  needs a FunctionType rule (contravariant params / covariant returns,
  or the engine's actual rule — READ the reference's
  `functionType`/`genericsUtil` first; engine pure is the spec).
- **Leg 4 (fn refs as values):** the resolver treats a bare user
  function name as an element ref. The reference test passes
  `comparator` (a ConcreteFunctionDefinition in the same source) as a
  VALUE. Our parser/resolver needs function-reference-as-value
  (PackageableElementPtr → function lookup at synth). Related: the
  adapter's collector (`collectRoots`) already ships `::tests::`
  callees — check the FN-REF (non-call) path ships too for Channel A.
- **Leg 5 (comparator ordering):** sort(col, fn) with a COMPARATOR
  lambda — the lowering must translate comparator semantics to ORDER
  BY direction/keys. `LambdaBinding`'s comparator conventions exist
  (M4 landing); the gap is the ordering DIRECTION family. Witness
  expectation is DESC — read the test's comparator body first.
- **Leg 6 (instance toString):** read the REFERENCE's toString
  semantics for instances (interpreted `toRepresentation`/print of an
  instance — package-qualified name + properties or the @anonymous
  form). This is a print-form leg: ONE owner, presumably beside
  LiteralSpelling/printForm. The ambiguous-overload row is separate:
  overload scoring tie between relation::toString candidates — fix
  scoring, not a special case.
- **Leg 7b (grammar walls):** each wall names its construct — units
  (`newUnit`/`getUnitValue`), `cast.pure` EXTENDS-form, `new.pure`
  PAREN after 113:72, `addColumns` '@' type ref. Parser legs; the
  parity-program discipline applies (oracle-first: check how the
  ENGINE parser reads each construct; our parser must accept what the
  corpus contains — these files are IN the reference corpus, so the
  drop-in program wants them anyway).

## 4. Discipline (unchanged, binding)

Full `tools/allgates.sh` per green batch (~7.2 min budget), detached
with caffeinate, `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine`
`LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure`; tree FROZEN
during any run; push after every green batch; no subagents; measure
before claiming; `mvn -o -pl core install` before any hand-run pct
lane (stale-jar trap, 4×); pct census counters are PER-JVM CUMULATIVE
(wrong-denominator lesson); `-Dchb.only=<substr>` scopes Channel B;
probe uncertain wire behavior on the 1.5.0 reference jar and probe THE
EMISSION; ChannelB floors only bank DOWN-to-better (Essential ≥297
now — bank as legs land, justified same-commit); the walls≤20 pin in
ChannelBEssentialTest shrinks as leg 7b burns (3 essential rows return
to discovery when their walls fall — the 327 total may GROW; the
discovery pin `== 327` must move WITH the wall fixes, justified).

## 5. After the burn: the test-corpus arc

PROGRAM_MAP "longer arc" #1–4, in order: (a) parse+compile-ONLY census
over all `test.Test` functions (cheap; the wall inventory before
committing to execution); (b) execution (charter exists:
[DEFERRED_TEST_EXECUTION.md](DEFERRED_TEST_EXECUTION.md) for ###Data);
(c) the corpus assert migration to the SQL-verdict lane as ONE leg
(user ruling: no incremental drift before it). The Gap A adjudication
(census §5c) stays: the structural transport waits until the remaining
rows are dominated by identity/position families — leg 2 may CHANGE
that calculus (positions get a channel without Gap A), so re-count
after leg 2.
