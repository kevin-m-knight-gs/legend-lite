# Harness audit 2026-09-07 — evidence base

The summary is [`../HARNESS_AUDIT_2026_09_07.md`](../HARNESS_AUDIT_2026_09_07.md). This
directory is the working detail behind it: the tables, per-site enumerations, measured
counts and concrete refactors that the summary compresses to a line each.

Seven adversarial reviewers, each briefed to be harsh but correct and to label anything
unverified. `docs/END_TO_END_PLAN_2026_09_08.md` was treated as CLAIMS to test, never as
evidence. Every headline number was re-verified by the orchestrator against source or a
run before it entered the summary.

| File | Covers | Most actionable content |
|---|---|---|
| [`referee.md`](referee.md) | `H2Verify` / `ReplayOracle` / `PlanReplay` (2,692 LOC) | the 30 Java-comparison sites classified reducible/irreducible; the relaxation table; distance to "the database judges" per class |
| [`ambient-state.md`](ambient-state.md) | the 12 surviving ThreadLocals + mutable statics | the full inventory with who-writes/who-reads; the field-by-field sweep plan; the `FactLedger` design |
| [`guards.md`](guards.md) | ~23 guard tests | per-guard slip-past snippets; ratchet slack table; the 12 false "ONE OWNER" claims |
| [`roster-and-floor.md`](roster-and-floor.md) | the measured gate runs | all 121 DuckDB failures categorized; the H2 590 broken into families; why §7's arithmetic fails |
| [`main-residue.md`](main-residue.md) | oracle machinery in `core/src/main` | per-file PRODUCT/ORACLE verdict; the 12-step deletion path with LOC and blocker |
| [`strength.md`](strength.md) | what a pass proves | the strength ladder derivation; traced journeys; semantic fidelity spot-checks |
| [`driver.md`](driver.md) | `MinimalCorpus` / `Corpus` / `DuckWorkspaces` (1,123 LOC) | every path to a false pass, marked reachable/not |

## Reading order

For **step 6 (referee in the database)**: `referee.md` → `strength.md`.
For **step 1 (thread-local sweep)**: `ambient-state.md` → `main-residue.md`.
For **the floor and what DONE means**: `roster-and-floor.md` → `strength.md` → summary §10.
For **why the suite missed the regression**: `guards.md`.

## Provenance and confidence

- **Measured by execution**: everything in `roster-and-floor.md` (gates run twice per lane)
  and the per-test counts in `strength.md` (an independent driver over `MinimalCorpus`
  with `ReplayOracle.OUTCOMES` snapshotted per test).
- **Measured by re-implementing a guard's own algorithm** over its own file set:
  every "measured actual" in `guards.md`.
- **Read from source, orchestrator-verified**: §2's dangling fields, the broken normalizer,
  the post-hoc rescue, the single gate assertion, the vacuous tests, the front-door split,
  `equal(1,1.0)`.
- **Labelled INFERRED where it is inferred.** Two reviewers could not run anything (no JDK
  on their sandbox) and said so; their counts are re-derivations, not executions.

Claims that failed verification were dropped and are listed in the summary's §9 — including
two of the orchestrator's own.
