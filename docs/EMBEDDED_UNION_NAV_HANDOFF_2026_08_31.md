# EMBEDDED-UNION NAVIGATION — HANDOFF (2026-08-31, session end)

**READ THIS WHOLE DOCUMENT BEFORE WRITING ANY CODE. The user's
standing order for the next session: FULL HOMEWORK AND DEEP RESEARCH
FIRST, execution only after the research questions in §6 are answered
with receipts.** The prior session applied a multi-mechanism
change-set incrementally without per-mechanism sweeps and ended with
an unlocated regression; the working tree was REVERTED to `822b57e1`.
The exact final diff is preserved VERBATIM in
`docs/EMBEDDED_UNION_NAV_2026_08_31.patch` (737 lines, 4 files) —
consult it as a design record; do NOT blindly reapply it.

## 1. The assignment (unchanged)

Burn the resolver-bug-4 (ERROR census, `docs/RELATIONAL_CORPUS.md`):

| # | test | shape | status at revert |
|---|---|---|---|
| 1 | `testDataGeneration::tests::testUnionToUnion` | union-of-unions, embedded ctor field navs | **WAS FIXED by the change-set (3/3)** — the one proven win |
| 2 | `mapping::union::testAdvancedEmbeddedInMappingQuery` | `Firm.all()->filter(f\|$f.bridge.employees->exists(e\|$e.lastName==...))`, `unionMappingWithEmbeddedProperty2` | progressed 4 walls deep; last error §5 |
| 3 | `mapping::union::extend::testAdvancedEmbeddedInMappingQuery` | same shape, extend variant | tracks #2 exactly |
| 4 | `testUnionToUnionJoinSequenceWithMultipleChildrenInUnionSourceTree` | **DIFFERENT sub-shape**: stripped JOIN SLOT `PersonSet1PersonAdditional` (slot-chain, not embedded-ctor) | untouched — treat as its own witness class |

All four share the wall `resolver bug: undemanded navigation —
consumed expression reads STRIPPED join slot 'X' (the demand scan and
the rewrite disagreed)` but #4 is a join-slot chain, not the
embedded-ctor class.

## 2. The root cause (established with receipts)

This is a MISSING FEATURE, not a patch: **embedded sub-PMs that are
class-typed Joins need the union NAV LIFT**, and today nothing serves
them.

- `UnionSynthesis` line ~1110 comment admits it: "a sub reading a
  hoisted join slot stays undistributed (loud downstream, never a
  silently-wrong projection)" — the witnesses' wall IS that promised
  loudness.
- `isThreadProjectable` (UnionSynthesis ~1298) deliberately accepts
  the one-hop `$row.employees` read because the design intent is "the
  union class then looks like any nav-slot class" — i.e. the
  recomposed ctor keeps the read and a UNION-LEVEL navigation serves
  it. That navigation was never built: `collectNavLifts` scans only
  TOP-LEVEL member Join PMs, never descends into
  `PropertyMapping.Embedded.propertyMappings()`.
- A class-typed collection can never be a scalar member thread
  column, so distributing it as `emb__bridge__employees` (what
  happens today) is semantically impossible to serve.

## 3. What the change-set built (see the .patch for exact code)

Four mechanisms, in dependency order:

**A. UnionSynthesis (normalizer)**
1. `scanJoinPms`: the lift scan extended with EMBEDDED descent
   (recursive through `PropertyMapping.Embedded`), scan-time
   `targetByProp` (target class recorded once, from the property's
   OWNING class), and a `declaredOwner` chain gate.
   ⚠ CRITICAL LESSON: the old lift-loop's union-class re-derivation
   was NOT duplication — it was a SEMANTIC FILTER (lift only
   union-DECLARED properties; subtype-only PMs stay member-local for
   stc subtype dispatch). Deleting it broke 21 graphFetch subType
   tests ("class Address has no property 'coordinate'" —
   `Street extends Address { coordinate }`). The declaredOwner walk
   restored it. ANY rework must keep this gate.
2. `collectEmbLeaves`: class-typed SAME-NAME one-hop subs divert to a
   new `navSubs` set (never `embSubs` — no scalar thread column);
   `rebuildEmbCtor` recomposes them as plain `$row.<sub>` reads (the
   lift serves them). `navSubs` must mirror ALL of `embSubs`'
   reconciliation prunes (declared-class leaf prune, nested-path
   prune, poison prune) — missing them caused the subType breakage's
   second half.
3. VERIFIED CORRECT emission (debug dump receipt): Firm union's
   `employees` lift = `entries=2 ords=[0,1]
   srcKeys={0:{ID=ID_0},1:{ID=ID_1}}
   cond=(s.ID_0==t.FIRMID_0 or s.ID_1==t.FIRMID_1...)` — per-pair
   suffixed BOTH sides. The lift construction itself is right.

**B. StoreResolver — demand + exists registration**
4. `registerNavigations` ctor DRILL: the embedded-head drill loop
   stops at MID components (`mid + 1 < path.size()`), so a 2-hop
   filter path `[bridge, employees]` never drills to the leaf nav
   read → the lifted nav stays undemanded. Relaxing to
   `mid < path.size()` fixes demand BUT fans graphFetch projection
   trees (testEmbeddedToRootMapping 3→5 rows — projection paths must
   NOT root-join a to-many nav). Gates tried: filter-paths-only +
   `containsConcatenate(cs.pipeline())`. Even fully gated, the
   innerjoin regression (§4) persisted, so the drill is NOT its
   cause — but drill-COMPLETELY-off was never tested (session
   stopped mid-bisect).
5. `registerExistsSubs` direct-nav arm extended for embedded heads:
   unwrap optional `toOne` on the head binding (the recomposed ctor
   arrives as BARE `TypedNewInstance` — receipt: `[regex-sub]
   hb=TypedNewInstance`), detect ctor leaf = same-row nav read,
   `navAlias` = the ctor field's property, `registerKey` = the DOTTED
   consumer path (`bridge.employees`), inner leaves via
   `InnerDemand.lambdas(ops, path)` (the dotted string does NOT match
   segmented paths — `List.of(dotted)` is wrong), `navToMany` from
   `nav.info().multiplicity()` (class-property lookup can't speak
   dotted names).
6. `materializeRoot`'s fallback target resolver: was 3-arg
   (targets=null) → the target's own nav-reading member cols wall
   ("demanded navigate step without a target resolver"). Made
   self-referential → **StackOverflow** (Firm↔Person cycle, 614
   frames). Cycle guard (loud) + `narrowMembers` cut → first-level
   narrowing DROPPED columns substitution later reads (needs are
   colspec-computed and blind to binding-level prefix reads) →
   scoped to RECURSIVE resolves only.

**C. Pipelines**
7. `TargetResolver` 3-arg default (`needed` hint); `materialize`
   wraps the resolver with per-alias needs (`collectNavNeeds`:
   colspec tails + predicate target-side keys; whole-object read ⇒
   full width). `narrowMembers` (filter/concatenate/project descent,
   same-set across branches → union arity preserved).
8. BUILT AND REVERTED inside the session (do not rebuild):
   - unconditional `closeOverConditions` in materialize — the seven
     caller-side closures are PER-ROUTE JUDGMENT, not forgetfulness
     (unconditional closure pulled sibling slots through undemanded
     chained conditions → innerjoin deep-composite wall);
   - project-arm colspec NAV demand (`ownNavDemand`) — dead weight
     once the lift + distribution exist (testUnionToUnion passes
     without it).

**D. Substitution**
9. `rewriteCallArms` emptiness dispatch accepted only single-segment
   heads; generalized to the dotted join of `headPath` (the dotted
   ExistsSub is otherwise unreachable — only the concatenate arm ever
   looked up dotted keys). ⚠ arm ORDER inside rewriteCallArms is
   load-bearing (comment says so); the generalization was
   probe-disabled at session end, exonerated for the graphFetch fan
   (that was the drill) but never re-validated after.

## 4. THE UNRESOLVED REGRESSION (the reason for the revert)

`meta::relational::tests::mapping::innerjoin::isolation::testIsolationOfInnerJoins`
- Control: **passes on clean 822b57e1**, fails with the full diff.
- Error: `chained joinslot condition reads a further sibling slot —
  deep composite chains are not built here`
  (`CorrelatedSubselects.compositeChainTarget` ~1495, the
  `allowUpstreamSlotReads=false` guard).
- Bisection matrix (each toggled INDIVIDUALLY on the full diff, test
  still failing after each):
  - unconditional closure reverted → fails
  - project-arm nav demand removed → fails
  - dotted exists dispatch off → fails
  - UnionSynthesis embedded descent off → fails
  - navSubs distribution off → fails
  - drill fully off → **NEVER TESTED**
- Remaining suspects, in likelihood order:
  1. the TOP-LEVEL lift-scan consolidation itself: `targetByProp`
     now records the target from the MEMBER owner's property type;
     the old loop used the UNION class's property type. If they
     differ (subtype narrowing), different lifts synthesize for the
     SHARED Person/Firm unions that many families use → changed
     pipelines everywhere.
  2. the needs-wrapper + recursive resolver replacing the plain
     fallback lambda in `materializeRoot`.
  3. the drill (union-gated, but the innerjoin family's model may
     include unions on the navigated classes).
- ⚠ METHODOLOGY ORDER for the new session: do NOT bisect by toggling
  arms on the full diff. Start from CLEAN and apply ONE mechanism at
  a time (A1-scan-consolidation alone first, full sweep; then +A2;
  then +B4; ...), sweeping between each. The prior session's
  everything-at-once accretion is what made the culprit unfindable.

## 5. Where witnesses #2/#3 stood at revert (the next wall)

With everything active, the exists FIRES and composes, but the final
filter predicate reads
`t_n.FirmID_0 == _r0.ID  OR  t_n.FirmID_1 == _r0.ID` where `_r0` (the
Firm union row) has only `ID_0/ID_1` — lowering walls "filter
predicate references column 'ID'". Facts:
- `t_n` = Person target rows (FirmID_0/1 = Person-union member keys ✓)
- the Firm-side `employees` lift's own condition is CORRECT
  (`s.ID_0 == t.FIRMID_0 ...`, §3.3 receipt) — so the failing
  condition is NOT the lift's. Its shape matches the PERSON-side
  `firm` lift REVERSED (raw `t.ID` on an un-unionized firmTable
  read), suggesting the exists material came from the ASSOCIATION
  route (`ctx.findAssociationOf(Firm,'employees')` at
  StoreResolver ~2395) or an orientation that picked Person.firm's
  PM — not the dotted registration. UNPROVEN: tag ExistsSub with its
  creation site (debug field) and rerun to identify the producer
  before changing anything.
- Also note: `_r0`'s row already carries `employees_*` prefixed
  columns — the nav ALSO materialized flat. Two servings of one
  navigation = double machinery; decide which one owns exists.

## 6. HOMEWORK FOR THE NEW SESSION (answer ALL before coding)

1. **Engine spec first**: read how legend-engine itself compiles
   `unionMappingWithEmbeddedProperty2` + `$f.bridge.employees->exists`
   (pureToSqlQuery union OR-join generation; run/inspect the engine
   test's actual SQL if possible). The expected SQL is the design
   contract — the prior session never fetched it.
2. **Route table**: enumerate all ExistsSub creation sites
   (StoreResolver 1646/1747/2306+navArm/2344+assocArm,
   ChainedExists 205) and all consumption arms (Substitution
   rewriteCallArms — order matters) into a one-page table: key form,
   condition orientation, target pipeline provenance. Then answer:
   which route SHOULD own embedded-union exists?
3. **Identify the §5 condition's producer** (creation-site tag).
4. **Resolve the innerjoin regression suspect list** (§4) by
   clean-base incremental application with full sweeps between.
5. **Witness #4** (`JoinSequence`, slot-chain shape): census its
   shape separately; do not assume the embedded-ctor design covers it.
6. **Check `synthetics` keying** (predTailsFor/allPreds) for dotted
   headKeys — the prior session assumed alignment, never verified.
7. Only then: reassemble the change as ONE designed slice (the .patch
   is the parts list), land with the standard gates.

## 7. AFTER the resolver leg: THE HARNESS-DELETION BLUEPRINT

The real end goal (user directive): the test harness does ONLY
discover/provision/run/score — every other line dies. Current
inventory (measured 2026-08-31; 12,835 test-side lines, ~2,300 are
the legitimate end-state Runner):

1. **Whole-test compilation on the platform** (~1,800 lines die):
   `EngineTestExecutor.run()`'s statement walk + `ElqSplice`,
   `RuntimeIfForm`, `AssertLoopForm`, `ExecCallFinder`,
   wrapper/eta-expansion arms, per-driver loop, let/substitution
   machinery. Platform work: compile a whole multi-statement test
   function as one unit (execute-frames / bind-once, PROGRAM_MAP).
   Flip PER TEST with a counted shrink-only fallback census; corpus
   byte-stability + disagree=0 pins are the containment. THE SPINE —
   most other arms hang off this walk.
2. **Metamodel-as-data, plan-nodes-as-rows first** (user-gated):
   deletes quarantine 172 witness rows + 20 wall tests +
   `ReflectAsserts`/`PlanAsserts`/plan-let arms + TypedMap-65 walls.
3. **Feature grind — declined 30**: ~20 one-test features (window
   `over` in plan lane, multi-node sequence plans, TableTDS, M2M
   substitution…), `assertIs/2`, `JSONArray` + trailing-JSON (4).
4. **Emission byte-parity** (~1,700 lines die): §S5 TDG pretty
   renderer + alias-spelling anatomy (FAIL-59's diff classes) +
   predicate-diverged 6 → `assertSqlEquals`/`assertSameSQL` become
   plain in-DB text equality; `H2Verify` replay oracle + mirror +
   `TestDataGenForm` + transcript referee + `H2ExtensionFunctions`
   die (mirror dies with h2-session convergence).
5. **Per-family host folds** (~1,100 lines): `ConnEquality`,
   `JsonAssertCanon`, `ObjectRefs`, `LineageForm`(s) — each needs its
   data-side representation (lineage-as-data rides item 2).
6. **ERROR/FAIL burn (139+59 tests)** — cutover confidence metric:
   activity-recording 13, stamp-invariant 10 (rides item 2's family),
   reflection 10, host-channel 9, postprocessor hook 8, `SQLQuery`
   type 8, resolver-bug 4 (THIS handoff's leg), long tail.
7. **Cutover**: delete `EngineTestExecutor`, retire the dual-verdict
   probe + divergence censuses (migration instruments);
   `Runner`/`RelationalCorpusRunner` shrink to
   discover/provision/run/score.

Dependency order: 1 unblocks the walk deletion; 2 gates the
quarantine mass; 3/4/5/6 are parallel burn lanes; 7 is mechanical at
zero. All counters measured in this doc's session; re-measure before
relying (scoreboard = `docs/RELATIONAL_CORPUS.md`, sweep =
`mvn -pl core test -Dtest=RelationalCorpusRunner` with
`-Dlegend.engine.root/-Dlegend.pure.root` at /Users/neemsandv/legend).

## 8. Session-state pointers

- Tree reverted to `822b57e1` (5 pushed commits today end there:
  chained-fetch 26 burned; disagree 9→0 EXACT; valueRead Java-eval
  retirement; ledger honesty fix).
- The full working diff: `docs/EMBEDDED_UNION_NAV_2026_08_31.patch`
  (contains TWO probe-disables — `false &&` at the dotted dispatch
  and the embedded descent — and debug printStackTrace hooks in
  three walls; these are scaffolding, strip on any reuse).
- Standing rules that bit this session: one gate chain per batch;
  measure per mechanism, never accrete; a "duplicate" derivation may
  be a semantic gate — prove redundancy before consolidating;
  public orphans escape the dead-PRIVATE-method guard (grep callers
  after every revert).

## 9. LANDING RECORD (2026-08-31, second session)

Landed as ONE designed slice (~230 lines, no Pipelines/Substitution
changes): M1 scan consolidation (lift target from the DECLARED owner —
dissolves §4 suspect 1 by construction), M2 embedded descent, M3
navSubs recomposition, M4 nav-arm exists registration keyed by the
dotted consumer path with **outerNavSteps** addressing (union-frame),
gated to UNION pipelines. Witnesses #1/#2/#3 PASS (corpus 2338→2341,
#2/#3 clean, #1 text-rescued 898→899); consumption rides the EXISTING
dotted arm (Substitution ~948) — D9 was never needed. §5's bad
condition: proven (creation-site tag) to be the nav-arm consuming a
MEMBER-THREAD navigate via deep last-wins navSteps — not the assoc
route. §4's regression: the embedded-head gate firing on NON-union
frames (inside B5 itself, why the toggle matrix never found it);
containsConcatenate gate fixes it. Drill, needs-wrapper, recursive
resolver, unconditional closure: all UNNECESSARY — single-serving falls
out of exists-owns-filter-paths (engine receipt: no employees_* on the
Firm row). Witness #4 (slot-chain) remains — separate design, engine
contract already asserted in its own test (assertSameSQL, testUnion
.pure:240).
