# ops-control

Layer 3. Depends on **custody-recon** and **settlement-flow**, and on nothing else. Package
root `ops_control::`, prefixes `OPS_` (tables), `Ops_` (joins), `Ops` (filters), `ops` (set
ids), `Ops` (class names).

Operational controls over reconciliation and settlement. A control has an owner, a
frequency, an evidence requirement and a tolerance; on a given business date it either
**operated** or it did not; a control that failed raises an **issue** with a remediation
date; somebody **attests** — signs — that the control ran; and three KRIs say how bad it is
getting: ageing breaks, unmatched instructions, unreconciled cash.

Nothing here re-models a break, an instruction, a movement or a position. Everything being
controlled is reached by **association**, which is how `custody_recon::ReconBreak` acquires
an `opsIssues` property and `settlement_flow::SflCashSettlementLink` acquires
`opsUnmatchedKris` without either project knowing this one exists.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
`###Data`, no Runtime.

## The two constructs

**Associations reaching BOTH dependencies.** Eleven of the twenty-five associations cross the
boundary — five into custody-recon, six into settlement-flow — and `OpsIssue` is the class
that touches both at once: `reconBreak` points at `custody_recon::ReconBreak` and
`settlementBreak` / `settlementClaim` at `settlement_flow::SflReconciliationBreak` /
`SflSettlementClaim`. An association property is declared ONLY in the `Association`, never
again in the class body; declaring it in both is a duplicate-property failure.

**Class constraints.** Ten of the thirteen classes carry a `[ ... ]` block. Every constraint
is fully parenthesised — `&&` binds tighter than the comparison operators, so
`$this.a >= 0 && $this.b >= 0` does not parse as intended and does not compile. Constraints
are stated only over `[1]` properties; a constraint over a `[0..1]` property does not
type-check, which is why every "is it null" rule (`isOpen()`, `operated()`, `isValid()`) is a
derived property with `->isEmpty()` instead.

The rules worth knowing, because they are the model's actual content:

| class | the constraint that matters |
| --- | --- |
| OpsControl | `frequencyDaysPositive: ($this.frequencyDays >= 1)` — a control that never runs is not a control |
| OpsControlRun | `exceptionsWithinItems: ($this.exceptionCount <= $this.itemsChecked)` — you cannot find more exceptions than you looked at items |
| OpsKri | `redBeyondAmber: ($this.redThreshold >= $this.amberThreshold)` — amber comes before red or the RAG is unreadable |
| OpsAgeingBreakKri | `agedLadder: (($this.aged90Count <= $this.aged60Count) && ($this.aged60Count <= $this.aged30Count))` — the ageing ladder, asserted rather than trusted |
| OpsIssue | `severityInRange: (($this.severity >= 1) && ($this.severity <= 5))` |

## Elements

| element | kind | note |
| --- | --- | --- |
| ops_control::OpsControl | class | the standing control: owner, frequency, evidence requirement, tolerance, remediation window; key `controlCode`; `isLive()`, `runsPerYear()` (Float — `/` widens) |
| ops_control::OpsControlOwner | class | who carries the control and who it escalates to; key `ownerId`; `isFirstLine()` |
| ops_control::OpsEvidenceRequirement | class | what proof the control must leave — kind, count, retention, signature, four eyes; key `evidenceCode`; `isDualControl()` |
| ops_control::OpsControlRun | class | **it operated or it did not**; TWO-column key `(controlCode, cobDate)`; `operatedOn` null is the did-not-operate case; `operated()`, `isWithinTolerance()`, `isClean()`, `exceptionRate()` (Float) |
| ops_control::OpsEvidenceItem | class | one artefact lodged against one day; THREE-column key `(controlCode, cobDate, artefactSeq)` |
| ops_control::OpsAttestation | class | **somebody signs that the control ran**; THREE-column key `(controlCode, cobDate, attestationSeq)` — a four-eyes control is two rows, preparer and countersignatory; `isValid()` (not revoked), `isLate()` |
| ops_control::OpsIssue | class | a failed control, with **a remediation date** and an extension count; key `issueId`; carries custody-recon's SEVEN-column break key AND settlement-flow's two ids; `isOpen()`, `isSevere()`, `hasBeenExtended()`, `isFromReconciliation()`, `isFromSettlement()` |
| ops_control::OpsRemediationAction | class | one step towards closing an issue, with owner and date; key `(issueId, actionSeq)`; `originalDueOn` kept beside `dueOn` so slippage is visible; `isComplete()`, `hasSlipped()` |
| ops_control::OpsKri | class | a named KRI with amber and red thresholds; key `kriCode`; `kriFamily` is AGEING_BREAK / UNMATCHED_INSTRUCTION / UNRECONCILED_CASH; `amberToRedSpan()` |
| ops_control::OpsKriObservation | class | one reading on one day, thresholds copied not referenced; key `(kriCode, cobDate)`; `isRed()`, `isAmber()`, `headroomToRed()` |
| ops_control::OpsAgeingBreakKri | class | **KRI one, ageing breaks**; key `(reconCode, cobDate, breakType)` — custody-recon's `RCN_BREAK_TYPE_TOTAL` key exactly; `agedShare()` (Float), `isStale()` |
| ops_control::OpsUnmatchedInstructionKri | class | **KRI two, unmatched instructions**; key `(cobDate, linkId)`; `isAged()`, `hasClaim()` |
| ops_control::OpsUnreconciledCashKri | class | **KRI three, unreconciled cash**; key `(cobDate, reconId)`; `averageItemAmount()` (Float — the Integer count is `->toFloat()`d first), `isAged()` |
| ops_control::Store | store | 13 `OPS_*` tables, 25 `Ops_*` joins, 3 filters; includes `custody_recon::Store` and `settlement_flow::Store` |
| ops_control::Mapping | mapping | 13 class sets `ops*`, 25 association mappings; includes `custody_recon::Mapping` and `settlement_flow::Mapping`. No enumeration mappings — this project declares no enums |

13 classes.

## Set ids (a GLOBAL namespace — reference these, do not guess)

`opsControl`, `opsControlOwner`, `opsEvidenceRequirement`, `opsControlRun`,
`opsEvidenceItem`, `opsAttestation`, `opsIssue`, `opsRemediationAction`, `opsKri`,
`opsKriObservation`, `opsAgeingBreakKri`, `opsUnmatchedInstructionKri`,
`opsUnreconciledCashKri`

All explicit and all root (`*`), so the default ids (`ops_control_OpsControl` and friends)
**do not exist**. A downstream `extends [...]` or cross-project `AssociationMapping` must
name the `ops*` id. Upstream ids that stay relevant downstream: `rcnDefinition`, `rcnRun`,
`rcnBreak`, `rcnBreakTypeTotal`, `rcnAssigneeLoad`, `sflCashSettlementLink`,
`sflReconciliation`, `sflReconciliationBreak`, `sflSettlementClaim`.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| OPS_CONTROL | CONTROL_CODE | OWNER_ID, EVIDENCE_CODE internal; RECON_CODE → `RCN_DEFINITION` |
| OPS_OWNER | OWNER_ID | ESCALATION_LEVEL 1..5 |
| OPS_EVIDENCE_REQUIREMENT | EVIDENCE_CODE | REQUIRES_SIGNATURE / REQUIRES_FOUR_EYES are BIT |
| OPS_CONTROL_RUN | CONTROL_CODE, COB_DATE | **OPERATED_ON null = did not operate**; RECON_CODE + COB_DATE + RUN_SEQ is custody-recon's three-column run key |
| OPS_EVIDENCE_ITEM | CONTROL_CODE, COB_DATE, ARTEFACT_SEQ | |
| OPS_ATTESTATION | CONTROL_CODE, COB_DATE, ATTESTATION_SEQ | REVOKED_ON null means the signature still stands |
| OPS_ISSUE | ISSUE_ID | seven `BREAK_*` columns → `RCN_BREAK`; SETTLEMENT_BREAK_ID → `SFL_RECON_BREAK`; CLAIM_ID → `SFL_SETTLEMENT_CLAIM`; CLOSED_ON null means open |
| OPS_REMEDIATION_ACTION | ISSUE_ID, ACTION_SEQ | DUE_ON beside ORIGINAL_DUE_ON |
| OPS_KRI | KRI_CODE | AMBER_THRESHOLD, RED_THRESHOLD |
| OPS_KRI_OBSERVATION | KRI_CODE, COB_DATE | thresholds copied onto the row |
| OPS_AGEING_BREAK | RECON_CODE, COB_DATE, BREAK_TYPE | ASSIGNED_TO pairs with RECON_CODE for `RCN_ASSIGNEE_TOTAL` |
| OPS_UNMATCHED_INSTRUCTION | COB_DATE, LINK_ID | LINK_ID → `SFL_CASH_SETTLEMENT_LINK`; CLAIM_ID → `SFL_SETTLEMENT_CLAIM` |
| OPS_UNRECONCILED_CASH | COB_DATE, RECON_ID | RECON_ID → `SFL_RECONCILIATION`; SETTLEMENT_BREAK_ID → `SFL_RECON_BREAK` |

Filters `OpsLiveControl` (`OPS_CONTROL.RETIRED_ON is null`), `OpsOpenIssue`
(`OPS_ISSUE.CLOSED_ON is null`) and `OpsNotOperated` (`OPS_CONTROL_RUN.OPERATED_ON is null`)
— null tests, because a `Filter` will not take a boolean literal.

## Joins

Internal (14): `Ops_ControlOwner`, `Ops_ControlEvidenceRequirement`, `Ops_ControlRuns`,
`Ops_RunEvidenceItems`, `Ops_RunAttestations`, `Ops_RunIssues`, `Ops_IssueActions`,
`Ops_ActionOwner`, `Ops_ControlKris`, `Ops_KriOwner`, `Ops_KriObservations`,
`Ops_KriAgeingBreaks`, `Ops_KriUnmatchedInstructions`, `Ops_KriUnreconciledCash`.

Into custody-recon (5), each matching its target's key in full:

| join | columns |
| --- | --- |
| Ops_ControlReconDefinition | RECON_CODE → `RCN_DEFINITION` |
| Ops_RunReconRun | **three** — RECON_CODE, COB_DATE, RUN_SEQ → `RCN_RUN` |
| Ops_IssueReconBreak | **seven** — the whole break key including BREAK_TYPE → `RCN_BREAK` |
| Ops_AgeingBreakTypeTotal | **three**, onto the VIEW `RCN_BREAK_TYPE_TOTAL` |
| Ops_AgeingAssigneeLoad | **two**, onto the VIEW `RCN_ASSIGNEE_TOTAL` (date dropped) |

Into settlement-flow (6): `Ops_IssueSettlementBreak`, `Ops_IssueSettlementClaim`,
`Ops_UnmatchedLink`, `Ops_UnmatchedClaim`, `Ops_UnreconciledReconciliation`,
`Ops_UnreconciledSettlementBreak` — one column each, because settlement-flow has synthetic
ids where custody-recon has composite keys.

## Associations

Across the boundary — these add a property to a class this project does not own:

| association | end on the dependency's class | end here |
| --- | --- | --- |
| OpsControlReconDefinition | `ReconDefinition.opsControls[*]` | `OpsControl.reconDefinition[0..1]` |
| OpsRunReconRun | `ReconRun.opsControlRuns[*]` | `OpsControlRun.reconRun[0..1]` |
| OpsIssueReconBreak | `ReconBreak.opsIssues[*]` | `OpsIssue.reconBreak[0..1]` |
| OpsAgeingBreakTypes | `BreakTypeTotal.opsAgeingKris[*]` | `OpsAgeingBreakKri.breakTypeTotal[0..1]` |
| OpsAgeingAssignee | `AssigneeLoad.opsQueueKris[*]` | `OpsAgeingBreakKri.assigneeQueue[0..1]` |
| OpsIssueSettlementBreak | `SflReconciliationBreak.opsIssues[*]` | `OpsIssue.settlementBreak[0..1]` |
| OpsIssueSettlementClaim | `SflSettlementClaim.opsIssues[*]` | `OpsIssue.settlementClaim[0..1]` |
| OpsUnmatchedLink | `SflCashSettlementLink.opsUnmatchedKris[*]` | `OpsUnmatchedInstructionKri.cashLink[0..1]` |
| OpsUnmatchedClaim | `SflSettlementClaim.opsUnmatchedKris[*]` | `OpsUnmatchedInstructionKri.unmatchedClaim[0..1]` |
| OpsUnreconciledReconciliation | `SflReconciliation.opsCashKris[*]` | `OpsUnreconciledCashKri.cashReconciliation[0..1]` |
| OpsUnreconciledBreak | `SflReconciliationBreak.opsCashKris[*]` | `OpsUnreconciledCashKri.cashSettlementBreak[0..1]` |

The same property NAME may appear on two of these (`opsIssues` lands on both
`custody_recon::ReconBreak` and `settlement_flow::SflReconciliationBreak`) because the
classes differ; two associations adding the same name to the SAME class would not compile.

Inside this project: `OpsControlOwnership` (`owner`/`ownedControls`), `OpsControlEvidence`
(`evidenceRequirement`/`requiringControls`), `OpsControlRuns` (`runs`/`control`),
`OpsRunEvidence` (`evidenceItems`/`evidencedRun`), `OpsRunAttestations`
(`attestations`/`attestedRun`), `OpsRunIssues` (`issues`/`failedRun`),
`OpsIssueRemediation` (`actions`/`remediatedIssue`), `OpsActionOwner`
(`actionOwner`/`assignedActions`), `OpsControlKris` (`kris`/`kriControl`),
`OpsKriOwnership` (`kriOwner`/`ownedKris`), `OpsKriObservations` (`observations`/`kri`),
`OpsKriAgeingBreaks` (`ageingBreaks`/`ageingKri`), `OpsKriUnmatched`
(`unmatchedInstructions`/`unmatchedKri`), `OpsKriUnreconciled`
(`unreconciledCash`/`cashKri`).

Every `AssociationMapping` end names `[sourceSetId, targetSetId]`, because this project and
both dependencies declare their set ids explicitly and the bare `prop: [db]@Join` form only
resolves against defaults.

## Notes for downstream

- Do NOT `include core_account::Store`, `core_instrument::Store` or `core_calendar::Store`
  alongside `ops_control::Store`. core-account already arrives here by four routes
  (custody-core and position-keeping under custody-recon, cash-core under settlement-flow);
  the diamond compiles because nothing re-declares those tables, and an extra `include`
  would.
- Currency is a `String` code on every class here, deliberately, matching both dependencies.
- The tolerance and the KRI thresholds are COPIED onto the run and the observation rather
  than read from the definition. Widening a control's tolerance next quarter must not
  retrospectively pass today, and re-baselining a KRI must not retint last month's chart.
- Derived properties that divide are declared `Float[1]`;
  `OpsUnreconciledCashKri.averageItemAmount()` writes `$this.itemCount->toFloat()` because
  `Float / Integer` does not type as `Float`.
- No `###Data` element and no Runtime: the tables are declared and unseeded.

## Verified

    python3 scripts/projects/check.py ops-control
    compiles  ops-control (+11 deps)
