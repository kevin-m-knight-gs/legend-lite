# THE OPEN REGISTER

ONE list of every open item, with its source and size. Maintenance
rule (part of every slice's definition of done): a row moves to the
CLOSED section IN THE SAME COMMIT that closes it; new deferrals add a
row in the same commit that defers them. "What's unfinished?" must
always be a thirty-second read of this file.

Size classes: S (< half a gate cycle), M (one to a few slices),
L (a program leg).

## 1. Verdict work (CANONICAL_FORM_SPEC / STAMP_DISCIPLINE_PROGRAM)

| # | Item | Size | Notes |
|---|---|---|---|
| V7 | Corpus-lane cutover + harness arm DELETION | L | decoded-golden-text + grid problems are corpus-only; LAST, after PCT lane proves the system. PREREQUISITES V10a/V10b below. |
| V10a | Re-derive the golden temporal compare: replace instant-blind goldenEqualScalar with the ENGINE convention (normalize both sides to nine-digit components, compare exactly) | S/M | 2026-08-22 self-audit ("where else did we hack?"): instant-blind grants strictly more than engine equality; works only because the corpus domain doesn't exercise the gap |
| V10b | PROBE: written-precision propagation through temporal computation vs the engine RELATIONAL lane (adjust/timeBucket over partial-precision inputs) | S | the root-only scalarRoot literal swap fixed visible tests without answering mid-expression propagation; suspicion: engine-relational loses it identically — VERIFY, don't assume |
| V10c | Declared assumptions to pin or prove: STRING_AGG input-order (no contract), assert-side double-execution determinism, DECIMAL(38,18) float-unfold envelope | S | alarm-guarded today; guarded ≠ derived |
| V8 | R3 tolerance census: 2-ULP + the 21 cross-engine float rows | M | retire or declare; both counted today |
| V9 | Grid byte cutover closing slice (after V4/V8) | S | ledger says: ORDER BY + policy + GridCompare arm deletion, no emission |

## 2. Audit findings still open

| # | Item | Source | Size |
|---|---|---|---|
| A1 | 1==1.0 / indexOf base / substring base — PER-LANE adjudication w/ engine witnesses | COMPILER_SHORTCUT_AUDIT §4 | M (rides PCT burn bucket 4) |
| A2 | Parser invention census (53 skew + 42 crash rows) | DEEP_AUDIT_HANDOFF | M |
| A3 | Parser lenient→strict flip (LAST, after A2) | DEEP_AUDIT_HANDOFF:95 | M |
| A4 | Foundations Phase 3 de-duplication | FOUNDATIONS_PLAN §4 | M |
| A5 | u_map__ name sniff → explicit flag | deep-audit tier-2, AWAITS RATIFICATION | S |
| A6 | agg_N collision scan | deep-audit tier-2, AWAITS RATIFICATION | S |
| A7 | Raw-SQL literal-aware rewriting | deep-audit tier-2 (merges into prepared-statements leg) | M |
| A8 | static-final/ThreadLocal guard visibility | deep-audit tier-2, AWAITS RATIFICATION | S |
| A9 | missing-[1] on ^new | deep-audit tier-2, AWAITS RATIFICATION | S |
| A10 | nlq/server hardening (incl. uncached-connection closing) | deep-audit tier-2, AWAITS RATIFICATION | M |

## 3. Recorded engineering follow-ups (each noted in code/doc at its site)

| # | Item | Size |
|---|---|---|
| F1 | GraphEmission:2714 nested-nav TypedLimit (D6a family) | S |
| F2 | unwrapElemRefs Exists/ScalarSubquery pre-existing hole | S |
| F3 | CarrierStrategies CompactList strategy for H2 (145 loud h2-replay declines) | M |
| F4 | Scoped-run seeding artifact (-Drcorpus.only fails aggregationAware at HEAD) | M |
| F5 | Per-family corpus seeding (#112) | M |
| F6 | Derived-property identical-signature dup rejection | S |
| F7 | Dup-FQN coverage: services/connections/mappings namespaces | S |
| F8 | {target} + foreign-db join-ref validation (D6b skipped conservatively) | S |
| F9 | Invariant-3 register burn-down: wrap 21 write-once tables immutable | M |
| F10 | Variant-aware byte canon: the canon consumes TYPED values with carrier decode owned by the carrier — covers the mixed-kind-collection decline AND retires the accumulating carrier-text strips (+0000, D-suffix regexes) which are the tell of canon parsing raw carrier text | M |

## 4. Parked BY THE RATIFIED ARC ORDER (sequenced, not debt)

| # | Item | Size |
|---|---|---|
| P1 | Decoupled-PCT completion burn: walls (~50 files / 65 hidden tests, reflection + grammar families), instance-universe 13, date-error 5, big-number 4, A1's 3, prim-ext 2, misc; frontier-12 stays pinned | L |
| P2 | ###Data execution → test-corpus branch unlock (DEFERRED_TEST_EXECUTION.md; census first) | L |
| P3 | Corpus burn-to-zero resume (2,347/2,575 — 228 left) | L |
| P4 | Prepared statements (LAST — perturbs the golden-SQL text lane; absorbs A7) | L |

## 5. Declared leniencies (LIVE POLICY, counted — revisited at V7/V8)

- Corpus temporal golden compares are INSTANT-based (goldenEqualScalar,
  H2Verify.norm) — the engine's two-subsecond-spellings adjudication.
- 2-ULP Double×Double host tolerance; GridCompare sig-digit cell
  tolerance (the 21 rows). Both quarantined outside the byte channel.
- Float canon DECIMAL(38,18) unfold + non-finite pass-through
  (witness-free edges, referee-guarded).
- Latent Float×Decimal integral tension (host true / byte false) —
  zero witnesses, documented in R0 §3.
- STRING_AGG input-order contract (Render precedent, not a guarantee).
- Engine-frontier 12 (the engine's own relational executor fails them
  too) — pinned, burn if the engine moves.

## CLOSED

- V1 sql-verdict disagreement alarm pinned (all five ChannelB suites +
  corpus runner) — closed in the V1–V5 slice (32eb39ac) and this one
- V2 evalCanon broad catch adjudicated (error-shape register; split
  into prepCanon/runCanon tunnels this slice)
- V3 ArchUnit host-verdict reachability rule (32eb39ac)
- V4 assertSameElements byte cutover (32eb39ac)
- V5 assertEq onto the canon machinery (32eb39ac)
- V6 decline burn round 1: 207→97 (NUMBER plan-refinement,
  PrecisionDecimal=Decimal, enums claimed; PAIR RULES for pure's
  non-transitive numeric equality; mixed-kind-collection gate;
  zeros-unify amendment) — eead1066
- V6b the 97 survivors DECLARED (class instances + wire-tree
  containers per spec §4, + unrefinable Numbers) and CEILING-pinned
  (sqlDeclined ≤ 100, shrink-only, all five ChannelB suites) — the
  PCT-lane verdict system is COMPLETE: four families byte-decided,
  disagreement pinned 0, declines pinned and declared — this commit
