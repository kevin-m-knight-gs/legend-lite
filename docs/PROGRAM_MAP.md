# THE PROGRAM MAP

One document holding the WHOLE plan — the four ratified buckets, the
work they didn't name, the audit intake, and the longer arc — so "what
is the plan" is a thirty-second read here, never a chat archaeology.
Ratified 2026-08-23 (user review). Maintenance rule: this map names
programs and points at their charter docs; per-item state lives in
[OPEN_REGISTER.md](OPEN_REGISTER.md) — a program that closes updates
BOTH in the same commit.

Standing invariants that govern everything below: tenet #1 (Java
orchestrates, the DATABASE executes), single compiler with dialect
strategies, engine pure as the semantic spec (pinned to the
INTERPRETED lane — see audit intake #A5), asserts are verdicts always,
no exclusions — only named ledgers.

---

## BUCKET 1 — F10: the kind-faithful carrier (finish 100% first)

Charter: [F10_CARRIER_DESIGN.md](F10_CARRIER_DESIGN.md). Spelling-as-tag:
pure's literal grammar IS the kind tag; the LITERAL wire label splits
the carrier from genuine Variant's raw-JSON contract; one grammar owner
(`lowering/LiteralSpelling` SQL-side, `values/LiteralText` host-side).

| Slice | State | Content |
|---|---|---|
| 1 — one grammar owner | **SHIPPED** 079ebfda | three divergent copies collapsed, byte-identical |
| 2 — mixed collections | **SHIPPED** 13a3308a | LITERAL label lands; mixedSort FLIPS TO PASS; declines 1→0, ceilings pinned 0 |
| 2b — selections + sniffer death | **SHIPPED** d3fd1a88 | min/max/least/greatest/mode marked; `latticeKind` DELETED BY CENSUS (zero firings, both lanes) |
| 3 — Any positions migrate | **CLOSED — M4 RE-LAND EXECUTED 2026-08-25** (M4_PRELAND_CHARTER §4R landing record) | 3a 2026-08-23 (b1cfdd97): temporal grammar joined the carrier, #A1's subsecond strip DIED. 3b 2026-08-24 (bc8ba85a→3b251fc9): total two-carrier readers, lane flag, unspell-one-side equality, assumption audit (decodeAny gains NO carrier arm — spelling decode is LABEL-driven only, the LITERAL arm in unwrap). The hetero-LITERAL claim re-landed 2026-08-25 with ZERO of its four park compensations (each became a typed-IR capability: LambdaBinding conventions, stored facts, COMPARATOR_NATIVES, uniform() ERROR skip); the three residual view rows PASS via elementText/printForm; post-landing adversarial audit §4A done. `wip/slice3-claim-on-untyped-ir` is a superseded park artifact |
| 4 — consumer decay | **CLOSED 2026-08-27** (charter [ADAPTER_NECESSITY_CENSUS.md](ADAPTER_NECESSITY_CENSUS.md), four gated batches bc9ab622→) | Re-scoped mid-slice by user directive: derive the MINIMUM adapter from the typed surfaces, trust no audit (§5b). Cargo: nine Java arms dead by measurement, parseDate conforms by emission (the one narrowing witness cured platform-side). R2: four pure arms dead, fallbacks loud, Essential floor 295→297. R1: discovery = the pure-side M3 walk, all five regexes deleted by differential (which also caught the shadow-Pair injection bug + two collector bugs pre-landing). Fold-ins: D91 (equality.Key armed on every lane) + D94 (diamond layout ≡ findProperty). Admissibility row: already retired (77af0f37→3c353706, receipt in census §4); computed-mixed = the ceiling-0 mixedNumericKinds ledger row. Residue named in census §5 (keyless value-lane equality; Gap A transport). THE TRUTHFULNESS BURN (census §5c, user-directed 2026-08-27) then closed the purist list: scaffold dead (bare storeless execution + connection-derived dialect), shape decisions platform-typed (Java builds real List/Map instances), error PROVENANCE (U+001F sentinel + ONE RaisedErrors owner — the adapter arm AND the platform's broad strip both deleted; native errors surface whole, the 4 BigNumber pins now byte-match upstream's manifest), verbatim injection (decoration regexes dead), documented conventions + loud walls throughout, and the three-file split (PctExecuteNative/ModelPacker/ValueBridge). P7 is the ONE surviving declared-type read — chartered to T4 with the mechanism sized. |

## BUCKET 2 — PCT burndown (after F10)

Current: **1,105 / 1,118** (honest universe 1,117 — audit #A9's testGet
double-count). Relation 355/355, Standard 204/204, Unclassified 95/95,
Essential 316/327, Grammar 135/137. THE WINNABLE SET IS BURNED
(2026-08-27, eight gated batches 183aeb33→1d71da33; landing record in
[CHANNELB_BURNDOWN_HANDOFF.md](CHANNELB_BURNDOWN_HANDOFF.md) §0):
every remaining Essential row is ledger (below); the last Grammar
row (getAll::testBasic) is OWNED by the METAMODEL STORE leg
([METAMODEL_STORE_HANDOFF.md](METAMODEL_STORE_HANDOFF.md), chartered
2026-08-28: the metamodel lives IN the database — Class.all() = SQL
over a seeded metamodel.classes table through the ordinary store lane;
end-state chosen over a host-side fold by the cut-over-hard doctrine).
testPlusInIterate LANDED 2026-08-28 (fold-strategy closure — the
cross-tree-binding bug; Grammar 136/137).

**The ledger boundary (not burnable):**
- 5 indexOf/substring rows — user-adjudicated IRREDUCIBLE (register A1:
  1-based indexing is real core_relational pure semantics; a reverted
  draft proves the trap).
- 4 adjustBy*BigNumber rows — **FORMALLY ADJUDICATED 2026-08-27 (leg
  8)**: dates beyond DuckDB's physical range; the reference DuckDB
  target fails IDENTICALLY, and after the truthfulness burn's B7
  provenance work our four pinned error texts BYTE-MATCH the reference
  manifest's own enveloped texts — the divergence is the backend's
  physical range, not our pipeline. Ledger.
- 2 sort rows (testSimpleSortWithKey, testSimpleSortWithFunctionVariables)
  — **ADJUDICATED, user sign-off 2026-08-27 (ledger 9→11)**: independently
  re-diagnosed by the burn session AND the dossier branch
  (docs/channelb-dossiers leg5) as derived members of the register-A1
  substring family — the key/comparator machinery is correct (our
  actual IS ascending-by-1-based-substring-key); interpreted substring
  is Java 0-based. Not an ordering bug; not winnable while A1 stands.

**The winnable 25, by leg:**
| Leg | Rows | Notes |
|---|---|---|
| assertError source positions | 6 | DB errors carry no line/column; error-shape channel threads positions |
| higher-order match / function-value params | 6 | match-with-functions ×4 + comparator fn-ref resolution ×2 |
| toString over instances | 3 | + one overload-ambiguity row |
| sort-with-key / removeDuplicates order | 3 | comparator-with-key ordering; first-occurrence order over mixed |
| fold | 2 | **F17 diagnosed**: `+=` parsed but DROPPED at NewChecker.checkCopy (desugar to concatenate(receiver.prop, v)); unset to-many fields emit untyped NULL |
| grammar residue + parseDate | 5 | |

Plus the audit's SILENT singleton bugs (outrank several rows above —
wrong VALUES, not errors): `['ACTIVE']->contains('TIV')` → true
(substring match), `['abc']->indexOf('b')` → 2; and Part-1 residuals
(filter grows a set, `[]->map` stamp trip, `1/0` → Infinity, `times()`
→ Float, `^new` missing required [1] → null, `Box<Integer>` match arm,
partial cast()).

## BUCKET 3 — the adapter shrink (formally F10 slice 4)

The question ratified: is ExecuteLegendLiteQuery + pct-adapter the
MINIMUM set to run reference PCT without compensating for the platform?
Method: the necessity-proof census (the same audit form that killed the
host-logic arms — every arm proves itself against our tenets or dies).
Register F16 already predicts the decay ("adapter receives kind-faithful
values and ONLY boxes; declared-type consult arms decay as F10 lands").
Clause 2b doctrine governs what moves INTO the platform. Audit finding
#A7 (F15 shadow-parser regexes are Channel A's alone) bounds the scope.
Charter: [ADAPTER_NECESSITY_CENSUS.md](ADAPTER_NECESSITY_CENSUS.md)
(opened 2026-08-27 — the per-arm verdict table; records that most of
PCT_AUDIT §7's "free wins" were already executed by the F5.x program).

## BUCKET 4 — single-shot asserts (V12/V13, the thesis validator)

Notes NOT lost — register §1:
- **V12** one round trip per assert: side-tagged UNION ALL, per-side
  typed value columns NULL-padded (no promotion erasure — the LITERAL
  carrier is the prerequisite, now real), literals INLINE, tunnel rung
  fused→split→bare→fold. GO/NO-GO: TimingLedger query.exec share.
- **V13** whole-function fusion: *let IS WITH* (materialized CTE =
  evaluate-once; dissolves within-test F13 identity), asserts as the
  verdict OVERLAY (the graphFetch→serialize species), verdict table
  out, typed list() evidence columns. HAZARD recorded: eager CTE vs
  pure first-failure sequencing → fusion-gradient tunnel. Sequenced
  after V7 (perturbs the golden-text lane, like prepared statements).

---

## THE WORK THE FOUR BUCKETS DIDN'T NAME

| Program | Where | Note |
|---|---|---|
| **T4 nullability** | register 2b | **CLOSED 2026-08-26** (charter §4bZ-V E + D): the 6,472 pad rows machine-counted 100% literal NullLit (N0), slot truth declared at construction (N1: a projected literal NULL is nullable — reconcileLabels, one owner; the wholesale-flip framing retired), EQUALITY-0 pinned on all four lanes (N2). The VALUE-level decode tripwire landed (D1) and CAUGHT a real bug on sweep one: SqlUnion's ctor dropped the mapping-seam tag — cured by union-label reconciliation; int-or-null settled to proven-empty (56/219 ceiling-pinned), wire-unknown EQUALITY-0 |
| **The builder leg** (label-at-construction) | register T3/T4 | HUGEINT adopt-pending 108/130 (contract widens per testLargePlus; labels derive from the RELATION SCHEMA, needs builders not expr-sniffing) + Number-erasure wire residue (~73 PCT / ~179 corpus) |
| **In-SQL equal() third lane** | audit #A2 | the X1–X4 treatment or three-lane pinning (see audit intake) |
| **V8/X6 2-ULP retirement** | register | `ulp-policy=0` across the whole corpus = the evidence it waited for; same-arithmetic H2 referee design briefed |
| **V7 corpus-lane cutover** | register | LAST of the verdict programs; V13 sequenced behind it |
| **Parser strict flip** | A2/A3, DEEP_AUDIT_HANDOFF | invention census (53 skew + 42 crash) then flip |
| **Foundations Phase 3 dedup** | A4, FOUNDATIONS_PLAN §4 | |
| **Prepared statements** | A7 merge | deliberately LAST (text-lane perturbation) |
| **Nullability-inference leg** | [TYPE_E2E_AUDIT_2026_08.md](TYPE_E2E_AUDIT_2026_08.md) findings 2–3 | TypeFact carries no nullability, so expression re-reads under-declare — converse census measured 925 corpus / 49 pct breaches (engine-correct VALUES, under-declared labels; ceiling-pinned both lanes). Design with the flag's first real consumer: TypeFact dimension vs from-tree reconcile vs per-SqlFn NULL-propagation rules |
| **Corpus burn resume** | burn-to-zero memory | paused at 2,347/2,575; largely superseded by the longer arc below |

---

## AUDIT INTAKE — VERDICT_AND_PCT_AUDIT_2026_08_23

(read from `worktree-e2e-deep-diagnosis`; audited at 1a4b0d12 —
PRE-dates the F10 slices, so several findings were fixed the same day)

**Already fixed by the F10 slices (same day, independently):**
- The three declines of §3.5 — letFn (the host-only-PASS "rescue"
  instance), map (binder-error wrap → real cause was the missing
  property-nav flatten, test now PASSES), mixedSort (now PASSES on the
  carrier). Recommendation 9 is structural now: decline ceilings pinned
  **0** — any decline fails the suite.
- §3.4's worst category inversion (mixedSort "wrong answer laundered as
  engine parity") — we now return the RIGHT answer.

**Open, adopted, in priority order:**
| # | Finding | Disposition |
|---|---|---|
| A1 | temporalCanon strips subsecond zeros — contradicts CANONICAL_FORM_SPEC three ways; tests-can-pass-that-should-fail | fix INSIDE F10 slice 3's temporal arm (precision-faithful spelling) |
| A2 | in-SQL `equal()` carries every deleted grant; `assert(equal(...))` bypasses the verdict program | the X1–X4 treatment or three-lane pinning; own slice after F10 |
| A3 | InstanceEquality classifier-mismatch arm sits BELOW the hasIdentityField early return — product lane disagrees with assert lane (one expression, two answers) | small arm reorder + witness test; near-term |
| A4 | host canon has NO LocalDateTime arm (the 26 residue rows); DB canon lacks the non-finite wall the spec promises | slice 3 companion |
| A5 | "engine-exact" under-specified: the engine has THREE lanes (interpreted/compiled/relational) and our interpreted-lane pin is unrecorded | RECORDED here + spec revision |
| A6 | frontier manifest excuses by NAME; `expectedError` never read; split "lite errored" from "lite answered wrongly" | ChannelBDiff fix with the A-ABSENT work |
| A7 | A-ABSENT bucket: 9 B-only rows claim agreement with a channel that never ran them | adopt (register already proposed it) |
| A8 | relation denominator explanation wrong: VERSION SKEW (pom pins 4.133.0 = 348 tests) not "qualifier filters" | register correction |
| A9 | testGet FQN double-count — honest universe 1,117 | prefix-qualify the discovery key |
| A10 | CanonicalRenderSql (491 lines) carries no eval-ledger pin | add pin |
| A11 | CANONICAL_FORM_SPEC §2/§3 stale vs shipped code (scale-normalized vs scale-preserving) on a doc requiring witnesses | spec revision |
| A12 | 2-ULP did zero work | fold into V8 retirement |

**Part-1 silent-value bugs adopted into Bucket 2** (contains substring
match, indexOf +1, and the residual list).

---

## THE LONGER ARC (after the buckets)

1. **test-corpus branch**: parse+compile-ONLY census FIRST (cheap; the
   wall inventory before committing to the execution harness), then
   execution. The bet: hours on legend-engine → minutes here (current
   full corpus sweeps ~3 min for 2,575 tests).
2. **compileAll mode vs demand-driven**: §8 global compile already
   landed for the corpus (one model + per-test overlays) — this is
   mostly discovery/harness work, and it forces `###Data` user-test
   execution, whose charter already exists
   ([DEFERRED_TEST_EXECUTION.md](DEFERRED_TEST_EXECUTION.md)). Bonus
   differential: compile-order bugs.
3. **core_relational burndown** to near zero. FIRST SLICE of this leg
   (user ruling 2026-08-24): **corpus asserts migrate host-side →
   SQL-verdict lane.** The rcorpus harness adjudicates asserts
   HOST-SIDE (EngineTestExecutor eval + compare — the third-impl
   problem) while PCT's Channel B verdicts run in the database (K-arm,
   asserts-are-verdicts-ALWAYS). Ruling: NO incremental drift before
   this slice — a half-migrated referee is a fourth implementation;
   corpus stays host-side until PCT is 100%, then this migration runs
   as one leg with its own acceptance (the grid-extraction render
   convention — TDSNull sentinel, engine text-compare — moves into the
   verdict queries with an explicit row_number order key).
4. **All test.Test functions** (the plain-test universe the parser wall
   burn opened).
5. **Byte-identical PlanGen drop-in** — flagged honestly: a
   parser-parity-SIZED program (plan OBJECT-MODEL parity, not SQL-text
   parity). Same playbook when we get there: oracle-first census,
   byte-exact ratchets, family-by-family.
