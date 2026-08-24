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
| 3 — Any positions migrate | OPEN | TO_VARIANT sites (Lowerer 2320-2343 heterogeneous elements, 335 root boxing, 923, 3055; MixedEncoding lubCase 219-222) switch raw JSON scalars → spellings; the TEMPORAL arm lands on BOTH grammar halves **precision-faithful** (this is where audit finding #A1, the subsecond-stripping contradiction, gets fixed); decodeAny gains the carrier arm; genuine Variant keeps raw JSON. EXIT DELETIONS: the +0000/D-suffix canon strips. Engine-true win: `equal('2014-01-01', %2014-01-01)` byte-decidable |
| 4 — consumer decay | OPEN | the ExecuteLegendLiteQuery / pct-adapter NECESSITY CENSUS (bucket 3 below is this slice's formal content); census admissibility rows re-adjudicate (DOUBLE←VARCHAR "Number-slot identity carrier" row retires); computed-mixed collections get a carrier claim or a named ledger row; wire pins bank to new floors |

## BUCKET 2 — PCT burndown (after F10)

Current: **1,084 / 1,118** (honest universe 1,117 — audit #A9's testGet
double-count). Relation 355/355, Standard 204/204, Unclassified 95/95;
all remaining rows in Essential (30) + Grammar (4).

**The ledger boundary (not burnable):**
- 5 indexOf/substring rows — user-adjudicated IRREDUCIBLE (register A1:
  1-based indexing is real core_relational pure semantics; a reverted
  draft proves the trap).
- 4 adjustBy*BigNumber rows — dates beyond DuckDB's physical range;
  reference fails identically; pending one formal adjudication, then
  ledger.

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
| **T4 nullability** | register 2b | ~6.5k multiplicity-echo rows re-label WHOLESALE + the VALUE-level decode tripwire (metadata is blind — DuckDB spells all-NULL columns INTEGER) |
| **The builder leg** (label-at-construction) | register T3/T4 | HUGEINT adopt-pending 108/130 (contract widens per testLargePlus; labels derive from the RELATION SCHEMA, needs builders not expr-sniffing) + Number-erasure wire residue (~73 PCT / ~179 corpus) |
| **In-SQL equal() third lane** | audit #A2 | the X1–X4 treatment or three-lane pinning (see audit intake) |
| **V8/X6 2-ULP retirement** | register | `ulp-policy=0` across the whole corpus = the evidence it waited for; same-arithmetic H2 referee design briefed |
| **V7 corpus-lane cutover** | register | LAST of the verdict programs; V13 sequenced behind it |
| **Parser strict flip** | A2/A3, DEEP_AUDIT_HANDOFF | invention census (53 skew + 42 crash) then flip |
| **Foundations Phase 3 dedup** | A4, FOUNDATIONS_PLAN §4 | |
| **Prepared statements** | A7 merge | deliberately LAST (text-lane perturbation) |
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
