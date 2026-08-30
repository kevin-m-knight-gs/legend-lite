# §4AD Placement Addendum — corrective design and landing record

**Status: EXECUTED IN FULL (user signed off 2026-08-29; landing
records in §8 — P0 bisect, P0.5 corrected unpark, P1 placement fix,
P2 filter-position pad guard; every design correction was
witness-forced and is recorded).**
Parent: NAV_ROUTING_DESIGN_4AD_SLICE1.md; spec source:
NAV_ROUTING_BATCH0_4AD.md §0b placement-bit table (measured, named
witnesses per cell). §§1-7 are preserved AS DESIGNED for the
epistemic record; where implementation corrected the design, the §8
records and inline CORRECTED markers govern.

---

## 1. The defect (referee-caught)

Batch 5 emitted ONE placement for parked qualifier predicates —
in-target (filtered subselect WHERE, row-PRESERVING) — for every
consumption position, and justified the value-position case with a
prose row-equivalence argument (NULL propagation + egress null-drop).
The batch-0 table had already MEASURED value position as **top WHERE,
row-DROPPING** and named the witness in the table itself.

The argument is false for pure's null-SKIPPING operators
(plus/concat: `sum(NULL, 'Test')`-style skips mint values from
surviving NULL rows). The batch-8 oracle unpark caught it:
`advanced::structure::testQualifierWithOperation` — engine golden 1
row, our pipeline more (phantom rows). `testTwoQualifiersWithOperation`
same. Batch 7's filter-position parking shares the mechanism; its
referee stayed green only because corpus filter-position consumptions
are equality-family (NULL fails the disjunct either way) — the hole is
unwitnessed there, not absent.

## 2. Deviation post-mortem (why it happened — binds §5's rules)

1. **Missing carrier made the right placement expensive.** A predicate
   born deep in expression resolution has no channel to the enclosing
   query's top WHERE; the in-target channel (predFilteredPipe) already
   existed. Convenience substituted for spec.
2. **Prose substituted for witness.** The equivalence claim was never
   enumerated over the operator family; null-skipping ops break it.
3. **The referee was sequenced last.** Batch 8 (oracle unpark) was the
   only verifier for the value lane, and the plan ran it after the
   claims it should have gated. Green chains in between were
   false confidence — those gates could not see the divergence.

## 3. The design: placement is a construction-time fact

One emission form (fanned join — scalar-subselect optimization
REJECTED by user ruling 2026-08-29: no second form, ≤1-strictness
belongs to CheckedOne, proven-PK to-one is a planner nullability fact).
Placement becomes the single remaining degree of freedom, decided
where the consumption position is KNOWN — the lift pre-pass — and
carried as a stamp on the synthetic head identity. Emission honors the
stamp; nothing re-derives position downstream
([[audit-rederivation-question]]).

Placement enum, one value per measured cell:

| stamp | cell (batch-0 table) | emission | witness |
|---|---|---|---|
| ROW_PRESERVING | projection/TDS column thread | pred stays in the join target (subselect WHERE ≡ ON — measured row-equivalent); missing nav ⇒ NULL cell (TDSNull) | testDerivedWithFiltering (+TwoProperties) — ALREADY CONFORMANT, no change |
| ROW_DROPPING | map VALUE position | ~~bare join + pred in top WHERE~~ **CORRECTED AT IMPLEMENTATION (§8 P1): pred stays IN-TARGET, join emitted INNER.** The hoist was refuted by the R4 witness: a null-safe pred is TRUE over the LEFT pad row (phantom mints); with INNER the pad row never exists. Row-identical to the engine's LEFT+WHERE on every measured cell; correct on the null-safe cell where the engine's own hoist phantoms | testQualifierWithOperation |
| ROW_DROPPING, multi-occurrence | value, multi-occurrence | per-occurrence join copies (unchanged); ALL parked preds ANDed in the ONE top WHERE | testTwoQualifiersWithOperation |
| CONSUMPTION_CONJOINED | FILTER position | ~~bare join + pred grouped in WHERE~~ **CORRECTED AT IMPLEMENTATION (§8 P2): the IN-TARGET park STAYS (the engine's own emission for these shapes is pred-in-ON, and its bundled fan counts are the measured rows — bare joins OVER-fanned three nested-OR goldens), PLUS the inlined qualifier pred conjoins the consuming comparison as a redundant-on-matched-rows PAD GUARD.** Suspended under AGGREGATION args (grouped route owns placement) and under NEGATION (the to-many negation arm's compensation vocabulary is equal/in — negated-pad stays ledgered residue) | testQualifierQueryWithOr + ValueMapPlacementTest.filterPositionGroupsQualPredWithCmp |

Notes:
- CONSUMPTION_CONJOINED is not top-level AND: under an OR filter,
  globally ANDing every occurrence's pred would wrongly require all
  occurrences to match. The pred travels WITH its consumption.
  PRECISION (audit): when the comparison is wrapped (negation,
  null-compensated `!=`), the pred conjoins OUTSIDE the wrapper but
  INSIDE the disjunct group — matching the measured grouping
  `(qual-pred AND consumption-pred)`, never inside the compensation.
- "ENCLOSING frame" DEFINED (audit — the batch-5 class of vagueness):
  the SELECT frame built from the NavPlan that carries the stamped
  head — exactly where that head's compositeConds land today. Nested
  contexts (groupBy isolation, union members) inherit the definition
  for free; witnesses cover flat cases only, so the definition, not
  the witnesses, governs nesting.
- CARRIER DESIGN SUPERSEDED BY CODE-LEVEL HOMEWORK (§6): the
  original whereConds-on-NavPlan sketch is RETIRED. Placement is
  decided AND consumed at the lift itself — a typed-tree rewrite,
  no stamp transport, no new SQL channel (the strongest form of
  construction-time fact: no carrier needed at all). Milestoning
  unaffected (temporal conds stay on the join-condition channel).
- Mid-hop bundling, identity⇒name, slot-claim ordering, the wall, the
  semi-join (isEmpty/exists) family: UNCHANGED.

## 4. Sequencing (each step gated; witnesses convert, never re-pin)

- **P0 — unpark-scope triage (read-only first). AUDIT CORRECTION: the
  over-reach hypothesis is WEAKER than first stated.** The flipped
  tests were read (testUnionBiTemporalSelfJoinDuplicateColumn.pure:
  59-81): they are HOST-evaluated `contains` asserts over
  `sqlRemoveFormatting()` — our GENERATED SQL TEXT — and the batch-8
  delta (H2Verify gates + runner pins) is referee-side only; it
  cannot change generated SQL. So cause = UNKNOWN with three live
  hypotheses: (a) verdict-channel rerouting via the Scalar-frame
  unpark, (b) union-alias-numbering nondeterminism (the asserts pin
  `unionalias_0/1` numbering), (c) a ScanOrder/wrapper interaction
  manifesting only under sweep conditions. Procedure: bisect — run
  the two tests on the committed tree, then with ONLY the H2Verify
  delta, then the full batch-8 tree; classify by evidence before any
  gate change. If over-reach: narrow the unpark to frames that
  compare golden rows. If real: they join the defect list.
- **P0.5 — LAND THE REFEREE FIRST (R2 applied to this plan; added
  at pre-flight review).** After P0 classifies the union pair, the
  corrected/narrowed unpark lands as its OWN gated slice — with the
  two value-witness divergences ceiling-pinned as NAMED DEFECTS
  (down-only, defect cited, burned by P1). This is the honest-
  instrument idiom, NOT re-pinning over a divergence: the defect
  becomes visible on main and the referee is ACTIVE before the fix
  lands, instead of batch 8 trailing the claims again. P4 then
  shrinks to pin regeneration only.
- **P1 — ROW_DROPPING placement** (lift-level rewrite per §6). Acceptance: both value witnesses convert to VERIFIED
  AGREEMENT in the batch-8 sweep; corpus families ≥ baseline; chain
  green. BLAST-RADIUS CHECKS (audit): milestoning conds must stay on
  the join channel (assert no temporal cond rides whereConds);
  egress null-drop over these lanes becomes redundant-but-idempotent
  (note, no change); mapping ~filters untouched; the 30 batch-5
  fnlr-value conversions re-verified by the family≥baseline gate.
- **P2 — CONSUMPTION_CONJOINED** for filter position. AUDIT FINDING:
  "stays green" alone is NOT acceptance — no corpus test
  distinguishes conjoined from in-target here (batch 7 was green by
  equality-luck, so the corpus has no null-skipping filter-position
  witness). P2 MUST ship a distinguishing witness: a shape test
  pinning the emitted SQL to the measured grouped form
  (testQualifierQueryWithOr shape: per-occurrence
  `(qual-pred AND consumption-pred)` groups under the filter's
  operator), plus a constructed null-skipping filter-position row
  witness that FAILS on in-target and PASSES on conjoined.
  Then: existing green set stays green, h2 0-diverged.
- **P3 — doc corrections.** Amend the batch-5 landing record in
  NAV_ROUTING_DESIGN_4AD_SLICE1.md: the row-equivalence claim was
  WRONG and referee-caught; link here. Batch-7 record gets the shared-
  mechanism note.
- **P4 — land batch 8** (H2Verify unpark + pins) only after P0–P2:
  zero divergences re-pinned, pins reflect converted witnesses.

## 5. Process rules adopted (so this class cannot recur)

- **R1 — measured cells bind emission.** When homework has measured a
  behavior cell, implementation matches the cell. Any deviation
  requires a WITNESS across the full operator family, never prose.
  Sharpened stop-condition: catching yourself writing a justification
  for why your emission differs from a measured spec cell IS
  "finding yourself hacking" — stop and surface it.
- **R2 — referee first.** No semantic-equivalence claim lands in a
  lane whose verifier is parked. Verifier/unpark work sequences
  BEFORE the claims that depend on it; if a plan orders it otherwise,
  flag the plan.
- **R3 — missing carrier is the work, not a license.** If the correct
  emission needs plumbing that doesn't exist, building the plumbing is
  the task; reusing an existing-but-wrong channel is the deviation.
- **R4 — acceptance needs a distinguishing witness (audit addition).**
  Every behavioral change ships at least one witness that FAILS
  before and PASSES after. "Existing tests stay green" is a
  non-regression gate, not acceptance — a change no test can
  distinguish is unfalsifiable and must construct its witness first
  (P2 is the live instance).

## 6. Implementation homework (code-level, 2026-08-29 — the receipts)

Read end-to-end before sign-off; every mechanism below is cited.

**How the pred flows today.** liftFilteredHeads (SyntheticHeads:281)
is a TYPED-TREE rewrite with distinct arms per consumption shape
(:305 map-over-filter-behind-toOne, :367 propertyAccess-over-filter,
:399 map-over-filter) that mints `prop#fN` heads and PARKS the pred
in the `preds` map (:1129, memo keyed on (prop, alpha-normalized
pred) :1146). Preds thread as `parkedPreds` through NavMaterializer
(:72-78, applied :380/:627) and reach the 8 predFilteredPipe emission
sites via ONE choke point: SyntheticHeads.applyToPipe (:171) /
allPreds (:148). The in-target placement is those callbacks; the
batch-5 equivalence PROSE also lives in code at NavMaterializer
:112-121 (the ~filter arm — that arm KEEPS in-target: it has its own
golden receipt, filter-in-ON via testFilterAfterFilter).

**THE DESIGN (Option A — lift-level rewrite; supersedes whereConds).**
Thread a position context down liftFilteredHeads exactly like the
existing `enabled` flag: PROJECTION (inside a TypedProject column
lambda), FILTER (inside a TypedFilter predicate), VALUE (everything
else, e.g. a TypedMap mapper). Then:
- PROJECTION → today's behavior verbatim (mint + park). Unchanged.
- VALUE → mint the head BARE (memo still keys (prop, pred) — copies
  per distinct pred, sharing for identical preds, matching the
  engine's two join copies in testTwoQualifiersWithOperation) and
  β-substitute the pred's parameter with nav-reads THROUGH the head
  (`$e.lastName` → `$f.<prop#fN>.lastName`); collect these conjuncts
  during the mapper walk and wrap the enclosing map/project SOURCE
  in one TypedFilter (AND of all occurrences' conjuncts — the
  measured one-shared-WHERE cell). Downstream needs ZERO new
  machinery: the injected filter is an ordinary typed filter (WHERE
  by the existing pipeline), and the pred's own inner navigations
  (address, manager in the witness) become ORDINARY chained reads
  that the association machinery emits as flat per-occurrence joins
  hanging off the head's copy — the engine golden's exact shape
  (persontable_0 → addresstable_0/persontable_1), BY CONSTRUCTION.
- FILTER (P2) → same β-substituted conjunct, conjoined at the
  consuming COMPARISON node inside the boolean tree (so OR-disjunct
  grouping matches testQualifierQueryWithOr's
  `(qual-pred AND firstName='Peter') OR (…)` golden).

**Witness shapes verified at source** (testQueryStructure.pure:60-105
+ goldens read in full): value witnesses are `map(f | qual.firstName
+ 'Test')` — 2-arg plus = scalar/concat route (consistent with the
batch-0 processPlus dispatch; no aggregation in the golden); the
engine emits bare flat joins + qualifier pred WHOLLY in top WHERE,
consumption adds NO null-drop conjunct.

**Named risks (disclosed, each with its referee):**
1. β-substitution must respect nested-lambda shadowing
   (alphaNormalize :1156 is the precedent; the lambda-walker trap is
   a known bite). Referee: unit pins on shadowed preds.
2. Cross-position occurrences of the SAME (prop,pred) — projection
   wants parked, value wants bare — is an UNMEASURED engine cell:
   memo key gains the placement class (copies fork). Safe both
   sides; probe the engine corpus for a witness before ratifying.
   PRE-FLIGHT PROBE (2026-08-29): the structure suite's cross-shape
   tests read — testMultipleIsolationWithSameProp (stacked preds
   MERGE into one subselect WHERE) and testQualifierWithForkAndOr-
   WithInline (same-pred qualifier + INLINE spelling SHARE one
   subselect; different-pred occurrences fork copies) are ALL
   projection-position: they ratify our memo/sharing rules and the
   projection subselect emission as ENGINE-SHAPED (not merely
   row-equal), but none mixes positions — the fork-by-placement
   rule stands as the safe default pending a corpus-wide scan
   (rides P1's survey rerun).
3. Injected-filter lowering: same-frame WHERE vs subselect wrap
   depends on fold policy — rows correct either way; text channel
   decides at implementation.
4. The toOne()-pierced strict read keeps its EXISTING loud wall
   (NavMaterializer :118-121, task #72) in P1 — ROW_DROPPING is its
   eventual retirement path, but that is a separate witnessed slice,
   not a P1 rider.
5. Deep/unsupported pred navigations wall loudly (route totality
   holds); the value witnesses REQUIRE 2-deep chains to work, so any
   gap is visible immediately, not silently compensated.
6. NULL-SEMANTICS of the injected conjunct (deep-homework find):
   TypedFilter predicates lower inside NullSemantics.enterFilter()
   (Lowerer:1459-1472 — null-safe equal arm, engine callingFromFilter
   parity), while the engine's MOVED qualifier preds compile plain
   ('persontable_0.LASTNAME = ''Smith''' in the golden). Literal
   comparisons are row-identical either way; column-column
   comparisons over double-NULL are not. PRE-FLIGHT PROBE
   (2026-08-29) DOWNGRADES THIS: NullSemantics' null-safe arm is the
   ENGINE'S OWN POSITION-BLIND rule (nullSafeEqualsOperation case 5
   — both lower bounds 0, config-gated at dbExtension.pure:928;
   literal comparisons keep bare '=' on both sides). The engine
   applies the same rule to moved preds, so injected-conjunct
   lowering matches by RULE PARITY. Residual: one double-NULL row
   witness in P1's unit pins; a dedicated TypedFilter.Stamp remains
   the fallback if that witness skews.

**Deeper receipts (round 2, all verified at source):**
- LIFT ORDERING: liftFilteredHeads runs at StoreResolver:2570,
  BEFORE path collection/registerNavigations (:2826) — an injected
  filter is ordinary tree content to every downstream phase. No
  mid-resolution injection anywhere.
- FOLD POLICY: Lowerer.filter (:1455) resolves the predicate against
  the CURRENT frame (tryPredicate) and merges into its WHERE; a
  subselect wrap (isolate :1485) is only the cannot-resolve
  fallback. Same-frame top-WHERE folding is the default — risk 3
  RESOLVED in our favor.
- PROVENANCE CHANNEL EXISTS: TypedFilter carries a Stamp
  (NONE/CORRELATION/TEMPORAL — TypedFilter.java:29) consumed by
  WhereMerge (engine WHERE conjunct-zone ordering). The injected
  filter rides the user zone (matching the golden's plain user-zone
  placement) or gains a dedicated stamp per risk 6.
- MINT/MEMO: parkFiltered (SyntheticHeads:207) memoizes on
  (realHead prop, alpha-canonical body) and pools closed vs
  correlated preds. The bare-mint variant = pool selection by
  placement; applyToPipe/allPreds naturally see nothing for
  value-pool heads — the 8 predFilteredPipe sites stay untouched.
- CORRELATED PREDS UNIFY (scope improvement): today a pred reading
  the outer row parks in corrPreds and applies at the join ON
  (row-preserving) — for VALUE position that is the SAME phantom
  hazard as the closed-pred hack. Under the injected-conjunct
  design the pred is rooted at the mapper param and can read the
  outer row DIRECTLY, so value-position correlated preds join the
  same injected filter — one code path, no pool split, engine
  MoveFilterOnTop parity. P1 covers both.
- GROUPED ROUTE IS OUT OF SCOPE BY MEASUREMENT: the explicit-reducer
  cell (grouped subselect joined back on PK) keeps its pred INSIDE
  the grouped subselect — the aggregate must see only matching rows
  and the join-back preserves roots. The placement table governs the
  FANNED form only.

## 7. Hack-deletion ledger (user directive 2026-08-29: the plan must
delete ALL shortcuts — every known item, with its disposition)

| # | item | disposition |
|---|---|---|
| 1 | in-target VALUE placement (batch 5 — THE defect) | **DELETED by P1** (closed preds) |
| 2 | corrPreds-at-ON for VALUE position (same hazard, correlated flavor) | **DELETED by P1** (unified conjunct) |
| 3 | in-target FILTER placement (batch 7 — green by equality-luck) | **DELETED by P2** (conjoined, shape-witnessed) |
| 4 | batch-5 equivalence PROSE in code (NavMaterializer:112-121) and in the design doc's batch-5 landing record | **CORRECTED by P3** — the ~filter ARM stays (it has its own golden receipt: filter-in-ON, testFilterAfterFilter) but the comment re-anchors on the receipt, not the argument |
| 5 | batch-8 uncommitted pins (1446/47/859) — pinned OVER the divergence | **REGENERATED at P4** from the post-fix sweep; never carried |
| 6 | ScanOrder at the assert boundary + StableScanOrder behind legend.exec.engineScanOrder | NOT a hack — user-designed feature + quarantined corpus-compat flag (runner-only); STAYS with its adjudication |
| 7 | ExistsJoinForm | NOT a hack — re-adjudicated engine-conformant (mirrors buildExistsAsJoinWithNullCheck); STAYS |
| 8 | loud walls: unliftedWall route-totality, toOne-pierced strict read (task #72), V4 union-assoc-sub, nested temporal targets, requireNoCorrelatedPred (StoreResolver:578) | walls are HONEST (loud, never wrong). Each keeps a named retirement leg; after P1, re-measure requireNoCorrelatedPred + task #72 — P1's uniform conjunct is their natural retirement path (separate witnessed slices) |
| 9 | §9 ledger deferrals (emission-anatomy leg, registerExistsSubs shrink blocked on the to-many-fact refactor, groupBy date-period lift gap) | named, owned legs — deferred by decision, not silence |
| 10 | pre-§4AD "row-equivalent" comments (FlattenOps:29/:147, StoreResolver:743 — INNER-hop flatten equivalences) | out of this program's scope; receipted in the flatten program; listed for completeness |

Marker sweep receipt: zero TODO/FIXME/XXX/hack/interim/stopgap
markers in any §4AD-touched file (grep, 2026-08-29). The only
undisclosed debt found by this census was item 2 — surfaced by the
homework itself and folded into P1's scope.

## 8. Landing records

**P0 EXECUTED 2026-08-29 (read-only bisect; verdict three-way, all
three prior hypotheses wrong):**
(1) the biTemporal…Quoting pair FAILS ON THE COMMITTED TREE — they
are pre-existing baseline failures (union family = 120/127 on the
bare tree, the 4 FAILs all pre-date this program); the batch-8 sweep
attribution was a MISREAD of fail lines. (2) The REAL flips under the
H2Verify delta alone: testChainedUnionsWithMapAggregation (extend::
and plain) — the unpark working as designed, exposing a REFEREE-
BOUNDARY gap, not a pipeline defect: the golden's raw NULL row is
sub-observable (the engine's own assert takes ONE value while its
golden SQL returns value+NULL — testUnionWithExtends.pure:291); our
pipeline drops that row in-DB (Blocker-1 egress design). (3) The
advanced pair diverges for real — ours-only [Test, Test, Test], the
predicted phantom-row signature of the batch-5 placement defect.

**P0.5 EXECUTED same day: the corrected unpark.** H2Verify:
Collection/Scalar frames verify; goldenRowsCompare flattens GOLDEN-
side single-column all-NULL rows for VALUE frames only (the
observable boundary; receipt in the code comment) — golden-side only,
so a lane wrongly keeping NULLs still fails; the false "RESOLVED by
batches 5+7" comment replaced with the named-defect truth. Corpus
sweep GREEN at measured pins: exec-passing 1,396 -> 1,448,
unable-to-exec 97 -> 45 (the 45 "parked on set-vs-row adjudication"
rows all verify — that adjudication was the placement defect wearing
a policy name), rescued ceiling 823 -> 861 (verification gained, not
text decayed — justified by the twin lane moves in the same commit),
tests/advanced baseline 66 -> 64 with testQualifierWithOperation +
testTwoQualifiersWithOperation as NAMED DEFECTS (burned by P1, which
restores 66). Scoreboard rewrite verified: all family pass counts
byte-stable except the named advanced row; remaining movement is
byte-match -> row-verified reclassification. The referee is now
ACTIVE ON MAIN with the defect visible — R2 satisfied; P4 collapses
into this record (pins already at measured).

**P1 EXECUTED 2026-08-29: THE PLACEMENT FIX — named defects BURNED
(advanced 64 -> 66, corpus 2,351 -> 2,353, full sweep green, zero
regressions).** Two design corrections happened DURING implementation,
both forced by witnesses — the process working as redesigned:

1. **WHERE-hoist SUPERSEDED by pred-in-target + INNER.** The §6 plan
   (bare join + hoisted top-WHERE pred) was implemented and REFUTED by
   its own R4 witness (ValueMapPlacementTest.doubleNullConjunctRule-
   Parity): a null-safe pred (`nick == nick2`, both nullable →
   IS NOT DISTINCT FROM) is TRUE over the LEFT pad row — hoisting
   phantoms exactly like batch 5, from the other side. The row-robust
   emission for the row-dropping cell: predicate IN-TARGET (unchanged
   from every other position — ONE park mechanism) + INNER join (pad
   rows never exist; no predicate family can mint). Row-identical to
   the engine's LEFT+WHERE on all measured cells. The placement bit
   collapsed to a JOIN-KIND fact.
2. **Forced-isolation adjudication.** The engine's forced-mode
   goldens (^RelationalDebugContext(forcedIsolation=...)) pin its
   strategies and PROVE them row-DIVERGENT in value position (forced
   golden keeps 4 rows incl. minted values; default keeps 1). The
   knob is debug mechanism, not semantics (batch-0 ruling); our one
   default-mode compiler declines forced VALUE-frame row compares as
   a COUNTED reason (H2Verify.FORCED_MECHANISM, set per test by the
   runner's existing forced-idiom detector). Row-preserving positions
   keep their referee.

LANDED SHAPE: liftValueMapFilter generalized to COMPUTED mapper
bodies (the defect boundary — computed bodies fell through to the
project route); toOne/first/head conformance wrappers SQL-erased at
the value arm (= the task-#72 retirement path for value position;
re-measure queued); parkFiltered memo forks by placement class;
innerValueHeads = the construction-time fact, consumed at AssocJoin
construction (new canonical-ctor field `rowDropping` — the defaulting
convenience ctors were DELETED after one silently bit at the
extra-identity site, ctor-trace-caught) and at the slot channel
(Pipelines.innerizeValueSlots — a lone '#' head CLAIMS the plain slot
under its real property name, so aliases translate via
navHeadByAlias). Witnesses: ValueMapPlacementTest 4/4 (R4
distinguishing, multi-occurrence fork/share, double-NULL parity);
corpus testQualifierWithOperation + testTwoQualifiersWithOperation =
VERIFIED AGREEMENTS; forced pair = counted declines. Guardrail: kind
vocabulary moved to AssociationJoins (StoreResolver back under
3500). OPEN CELLS added to the §7 ledger: OR-of-navs AS A VALUE
(conjunction semantics under INNER — unmeasured, disclosed); DEEP
value chains through mid hops (mid pads could still mint under
null-skipping — no corpus witness; mid-INNER extension when one
appears); forced FILTER-position null-skipping consumption
(engine-shared residue).

**P2 EXECUTED 2026-08-29: FILTER-POSITION PAD GUARD (builds on the
P1 record above).** The designed bare+grouped-WHERE
form was BUILT AND REFUTED BY REFEREES in one afternoon — the honest
sequence, each caught by a witness or the corpus, all corrections
sweep-green at the end:
(1) BARE joins over-fanned: three nested-OR goldens
(nestedFilterFunctionExpressionWithOrCondition et al.) assert 3 rows
and their goldens show the ENGINE emits pred-in-ON for these shapes —
its fan counts are shape-dependent (ON-form bundles, flat-form fans);
the batch-7 in-target fan IS the measured count. CORRECTED: in-target
park KEPT; the conjoined inlined qualifier pred rides ONLY as a PAD
GUARD (redundant over matched rows; both engine forms drop the pad
row — the flat form by WHERE grouping, the ON form by its
is-not-null pierce guard). The R4 witness converts ([] not
[Beta, Gamma]); fan counts everywhere = batch-7 = engine.
(2) AGGREGATION args suspend the conjoin (grouped route owns
placement; the conjunct widened boolean leaves across agg heads —
validation milestoning-aggregation trio walled, recovered).
(3) NEGATION suspends the conjoin (the to-many negation arm
transcribes engine null-compensation for equal/in only —
not(and(...)) walled validateComplexValidation6, recovered;
negated-consumption pad behavior = ledgered residue).
MECHANISM: FilterCtx pending-conjunct channel through the lift; the
wrapper attaches at the NEAREST boolean ancestor (per-disjunct
grouping under OR falls out structurally); andExpr via the one 2-arg
boolean::and. Text cost: 2 byte-matched asserts became row-verified
rescues (ceiling 861 -> 863, 0 diverged, measured). Witnesses 6/6;
validation 23/23; full sweep green, zero regressions. OPEN residue
(ledger): null-safe QUALIFIER preds (guard itself null-safe over
pad), negated and aggregated consumptions' pad corners — all
engine-shared, none corpus-witnessed.
