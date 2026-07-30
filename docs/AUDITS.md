# Audits — index

Five audit rounds have run on `legend-lite`. Their output is four documents with **separate
lifecycles** — do not merge them into one queue; separate docs are what got the first two executed.

| Doc | Fixes | Status |
|---|---|---|
| [`ARCHITECTURE_REMEDIATION.md`](ARCHITECTURE_REMEDIATION.md) | **Shape** — phase boundaries, N-backend decoupling, concept ownership | T0–T3 executed (corpus 1,258 → 2,074). T4 pending |
| [`CORRECTNESS_REMEDIATION.md`](CORRECTNESS_REMEDIATION.md) | **Answers** — silent wrong rows, and the scoreboard's own honesty | Tier C0 (four diagnostics) is the entry point |
| [`TENET_REMEDIATION.md`](TENET_REMEDIATION.md) | **Who does the work** — tenet #1 conformance, 20 ranked violations | V0 (tenet text) first; it makes the rest adjudicable |
| [`AUDIT_PROGRAM.md`](AUDIT_PROGRAM.md) | **What to audit next** — exhaustive `null` / `try` / `if` sweeps | Plan, not findings. Audit N is a gate and starts now |
| [`AUDIT_23_SPECIAL_CASING.md`](AUDIT_23_SPECIAL_CASING.md) | **Keyed special-casing** — every name/FQN/magic-string conditional across ~37k LOC, censused | Complete. Read before any `if` audit |
| [`AUDIT_2026_07.md`](AUDIT_2026_07.md) | Earlier round | Complete |

## Reading order for a new session

1. **`AUDIT_PROGRAM.md` §1–§2** — the method (why enumerations beat themes) and the sequencing
   criterion (*does this corrupt the instrument we steer with?*). Both generalize beyond their own doc.
2. **`CORRECTNESS_REMEDIATION.md` §1** — the meta-finding: every scoreboard bucket was mislabeled, and
   always flatteringly. Read before trusting any pass rate.
3. **`TENET_REMEDIATION.md` §1.1** — five invariant headers falsified, every one flattering. Read
   before trusting any `CONTRACT:` comment.
4. Then whichever tier you're working.

## Three things every one of them agrees on

- **A comment is a claim, not evidence.** Ten falsified self-descriptions across two audits, all
  drifting toward more discipline than the code holds.
- **Prefer a gate to a finding.** `CsvSeed` regressed `ARCHITECTURE` T3.1 because the fix was
  hand-applied to three sites and nothing checked the third.
- **Report a denominator.** An audit that publishes only its hits reads as complete when it isn't.

## What each doc says NOT to do

Each carries a refuted-hypotheses section, kept so the next pass doesn't reopen settled questions:
`ARCHITECTURE` §2, `CORRECTNESS` §6, `TENET` §7, `AUDIT_PROGRAM` §8. Read the relevant one before
proposing work in its area.
