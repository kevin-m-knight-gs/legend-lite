# trade-lifecycle

Layer 2. Depends on **trade-capture** and **event-core** and nothing else.
Package root `trade_lifecycle::`; prefixes `TLC_` (tables), `Tlc_` (joins), `Tlc` (filters),
`tlc` (set ids).

27 classes, 7 enums, 33 associations, 27 tables, 33 joins, 2 filters, 7 EnumerationMappings.

**The shape to know before using it.** A post-trade change is not an UPDATE. Every change of
every kind — amend, novate, partially terminate, compress, unwind, exercise, expire, reset,
correct, or absorb a corporate action — arrives as a NEW `TLC_AMENDMENT` row whose
`SUPERSEDES_ID` names the row it replaces. Nothing is overwritten, so the chain of rows IS
the audit trail, and **the current version is the row that nothing supersedes** — not a flag,
not `max(versionNumber)`. `TlcAmendment.isCurrentVersion()` is `$this.supersededBy->isEmpty()`
and is the test to use.

**The two names a downstream project needs:** the class `trade_lifecycle::TlcAmendment` and
its set id **`[tlcAmendment]`**. All but four of this project's 33 associations have one end
on it.

**The self-join.** `Tlc_SupersededBy(TLC_AMENDMENT.SUPERSEDES_ID = {target}.AMENDMENT_ID)` —
one line, both sides the same table. `trade_lifecycle::TlcAmendmentChain` rides on it with
`TlcAmendment` on both ends, so both of its mapping ends name **`[tlcAmendment, tlcAmendment]`**,
the same set id twice; only the direction of travel tells `supersedes` from `supersededBy`.

## Cross-project associations

| association | this side | far side (project, set id) |
| --- | --- | --- |
| trade_lifecycle::TlcTradeAmendments | `amendedTrade: trade_capture::Trade[0..1]` | `lifecycleAmendments: TlcAmendment[*]` on `Trade` — `[tcTrade]` |
| trade_lifecycle::TlcTradeNovations | `novatedTrade: trade_capture::Trade[0..1]` | `lifecycleNovations: TlcNovation[*]` on `Trade` — `[tcTrade]` |
| trade_lifecycle::TlcTradeSnapshots | `snapshotTrade: trade_capture::Trade[0..1]` | `lifecycleSnapshots: TlcVersionSnapshot[*]` on `Trade` — `[tcTrade]` |
| trade_lifecycle::TlcTradeCompressionMembers | `compressedTrade: trade_capture::Trade[0..1]` | `lifecycleCompressionMembers: TlcCompressionMember[*]` on `Trade` — `[tcTrade]` |
| trade_lifecycle::TlcTradeCorporateActionImpacts | `impactedTrade: trade_capture::Trade[0..1]` | `lifecycleCorporateActionImpacts: TlcCorporateActionImpact[*]` on `Trade` — `[tcTrade]` |
| trade_lifecycle::TlcImpactCorporateAction | `corporateAction: event_core::EvtCorporateEvent[0..1]` | `tradeImpacts: TlcCorporateActionImpact[*]` on `EvtCorporateEvent` — `[evtBase]` |

Those six add properties to classes this project does not own. Every end name hung on
`trade_capture::Trade` is `lifecycle`-qualified, because `Trade` already carries `amendments`,
`versions`, `cancellations` and `allocations` from trade-capture, and a second `amendments`
would collide. If you also associate with `trade_capture::Trade` or
`event_core::EvtCorporateEvent`, avoid `lifecycleAmendments`, `lifecycleNovations`,
`lifecycleSnapshots`, `lifecycleCompressionMembers`, `lifecycleCorporateActionImpacts` and
`tradeImpacts`.

## Exports

| element | kind | note |
| --- | --- | --- |
| trade_lifecycle::TlcAmendment | class | THE chain node; every change of every kind is one of these; 3 constraints; derived `isCurrentVersion`, `isChainRoot`, `isLive` |
| trade_lifecycle::TlcAmendmentDetail | class | what actually changed, field by field, as rows |
| trade_lifecycle::TlcAmendmentReason | class | the controlled vocabulary of WHY; drives reportability |
| trade_lifecycle::TlcAmendmentApproval | class | proof a human agreed; survives the amendment being withdrawn |
| trade_lifecycle::TlcNovation | class | transfer of one side to a new counterparty; 2 constraints |
| trade_lifecycle::TlcNovationConsent | class | one party's answer; `DEEMED` is silence, not a yes anyone said |
| trade_lifecycle::TlcPartialTermination | class | notional reduced, trade still alive; derived `reductionRatio` (Float) |
| trade_lifecycle::TlcTermination | class | the trade ends; unlike a cancellation it leaves a settlement amount |
| trade_lifecycle::TlcCompressionCycle | class | one compression run; derived `notionalRemoved`, `compressionRatio` |
| trade_lifecycle::TlcCompressionMember | class | one trade's participation and per-trade outcome in one cycle |
| trade_lifecycle::TlcUnwind | class | early exit priced at market; carries the mid and the spread paid |
| trade_lifecycle::TlcExercise | class | an optional right taken up; amends the trade it is on |
| trade_lifecycle::TlcExpiry | class | the trade ending by itself — "nothing happened", asserted |
| trade_lifecycle::TlcRateReset | class | a floating fixing observed and applied |
| trade_lifecycle::TlcLifecycleCashflow | class | money that exists only because of an amendment |
| trade_lifecycle::TlcCorporateActionImpact | class | THE cross-dependency; ties one corporate action to one trade to the amendment it caused; derived `isMaterial` |
| trade_lifecycle::TlcEventNotification | class | the notice that starts a consent clock; channel is the evidence |
| trade_lifecycle::TlcLifecycleWorkflow | class | the human process, as distinct from the amendment's own status |
| trade_lifecycle::TlcWorkflowStep | class | one thing that had to be done; the skipped step is visible |
| trade_lifecycle::TlcAuditEntry | class | the SYSTEM's audit trail, as opposed to the economics' |
| trade_lifecycle::TlcEventSource | class | where the change came from; a feed and a person weigh differently |
| trade_lifecycle::TlcSupportingDocument | class | the paper an auditor asks for |
| trade_lifecycle::TlcRestructuring | class | credit event; successor obligation and recovery |
| trade_lifecycle::TlcAllocationAdjustment | class | moving an allocation after it was already reported |
| trade_lifecycle::TlcCorrection | class | the previous row never described reality; still supersedes, never deletes |
| trade_lifecycle::TlcBreakFee | class | the penalty for ending early; not the unwind settlement |
| trade_lifecycle::TlcVersionSnapshot | class | the trade AS IT READ after one amendment, so nobody replays the chain |
| trade_lifecycle::TlcAmendmentType | enum | 13 values: ECONOMIC_AMENDMENT, NON_ECONOMIC_AMENDMENT, NOVATION, PARTIAL_TERMINATION, FULL_TERMINATION, COMPRESSION, UNWIND, EXERCISE, EXPIRY, RATE_RESET, CORPORATE_ACTION, RESTRUCTURING, CORRECTION |
| trade_lifecycle::TlcAmendmentStatus | enum | DRAFT, PENDING_APPROVAL, APPROVED, APPLIED, SUPERSEDED, REJECTED, WITHDRAWN |
| trade_lifecycle::TlcConsentStatus | enum | REQUESTED, GRANTED, REFUSED, DEEMED, LAPSED |
| trade_lifecycle::TlcTerminationReason | enum | MUTUAL_AGREEMENT, COUNTERPARTY_DEFAULT, CREDIT_EVENT, REGULATORY_REQUIREMENT, PORTFOLIO_COMPRESSION, OPTIONAL_BREAK, ERROR_CORRECTION |
| trade_lifecycle::TlcCompressionMethod | enum | BILATERAL, MULTILATERAL, PORTFOLIO_RECONCILIATION, RISK_CONSTRAINED, COUPON_BLENDING |
| trade_lifecycle::TlcNotificationChannel | enum | SWIFT, FPML, EMAIL, CLIENT_PORTAL, FAX, PHONE |
| trade_lifecycle::TlcWorkflowState | enum | OPEN, IN_PROGRESS, BLOCKED, COMPLETED, CANCELLED |
| trade_lifecycle::Store | store | 27 `TLC_` tables; `include`s `trade_capture::Store` and `event_core::Store` |
| trade_lifecycle::Mapping | mapping | 27 class sets, 33 association mappings, 7 EnumerationMappings; `include`s both dependency mappings |

## Set ids (a GLOBAL namespace; name these, the defaults do not exist)

`tlcAmendment`, `tlcAmendmentDetail`, `tlcAmendmentReason`, `tlcAmendmentApproval`,
`tlcNovation`, `tlcNovationConsent`, `tlcPartialTermination`, `tlcTermination`,
`tlcCompressionCycle`, `tlcCompressionMember`, `tlcUnwind`, `tlcExercise`, `tlcExpiry`,
`tlcRateReset`, `tlcCashflow`, `tlcCorporateActionImpact`, `tlcNotification`, `tlcWorkflow`,
`tlcWorkflowStep`, `tlcAuditEntry`, `tlcEventSource`, `tlcDocument`, `tlcRestructuring`,
`tlcAllocationAdjustment`, `tlcCorrection`, `tlcBreakFee`, `tlcVersionSnapshot`.

Every set is declared with an explicit id, so `trade_lifecycle_TlcAmendment` and its siblings
do not exist. A downstream `extends [...]` or cross-project `AssociationMapping` must name the
`tlc` id above.

Note the three that do not match their class name mechanically: `TlcLifecycleCashflow` is
`[tlcCashflow]`, `TlcEventNotification` is `[tlcNotification]`, `TlcSupportingDocument` is
`[tlcDocument]`, and `TlcLifecycleWorkflow` is `[tlcWorkflow]`.

## Tables

`TLC_AMENDMENT`, `TLC_AMENDMENT_DETAIL`, `TLC_AMENDMENT_REASON`, `TLC_AMENDMENT_APPROVAL`,
`TLC_NOVATION`, `TLC_NOVATION_CONSENT`, `TLC_PARTIAL_TERMINATION`, `TLC_TERMINATION`,
`TLC_COMPRESSION_CYCLE`, `TLC_COMPRESSION_MEMBER`, `TLC_UNWIND`, `TLC_EXERCISE`,
`TLC_EXPIRY`, `TLC_RATE_RESET`, `TLC_LIFECYCLE_CASHFLOW`, `TLC_CORPORATE_ACTION_IMPACT`,
`TLC_EVENT_NOTIFICATION`, `TLC_WORKFLOW`, `TLC_WORKFLOW_STEP`, `TLC_AUDIT_ENTRY`,
`TLC_EVENT_SOURCE`, `TLC_SUPPORTING_DOCUMENT`, `TLC_RESTRUCTURING`,
`TLC_ALLOCATION_ADJUSTMENT`, `TLC_CORRECTION`, `TLC_BREAK_FEE`, `TLC_VERSION_SNAPSHOT`.

`TLC_AMENDMENT` carries `TRADE_ID` (to `TC_TRADE`), `SUPERSEDES_ID` (to itself), `REASON_ID`,
`SOURCE_ID`, `WORKFLOW_ID`. `TLC_CORPORATE_ACTION_IMPACT` carries `EVENT_ID` (to
`EVT_CORPORATE_EVENT`), `TRADE_ID` and `AMENDMENT_ID`. `TLC_NOVATION`,
`TLC_VERSION_SNAPSHOT` and `TLC_COMPRESSION_MEMBER` each carry `TRADE_ID`. None of those
foreign-key columns is mapped to a property — navigate the associations instead.

## Joins available to downstream projects that include this store

Self: **`Tlc_SupersededBy`**.

Cross-boundary: `Tlc_AmendmentTrade`, `Tlc_NovationTrade`, `Tlc_SnapshotTrade`,
`Tlc_CompressionMemberTrade`, `Tlc_ImpactTrade`, `Tlc_ImpactCorporateAction`.

Internal: `Tlc_AmendmentDetail`, `Tlc_AmendmentReasonLink`, `Tlc_AmendmentApproval`,
`Tlc_AmendmentNovation`, `Tlc_NovationConsent`, `Tlc_AmendmentPartialTermination`,
`Tlc_AmendmentTermination`, `Tlc_CycleMember`, `Tlc_AmendmentCompressionMember`,
`Tlc_AmendmentUnwind`, `Tlc_AmendmentExercise`, `Tlc_AmendmentExpiry`,
`Tlc_AmendmentRateReset`, `Tlc_AmendmentCashflow`, `Tlc_AmendmentImpact`,
`Tlc_AmendmentNotification`, `Tlc_AmendmentWorkflow`, `Tlc_WorkflowStep`,
`Tlc_AmendmentAudit`, `Tlc_AmendmentSource`, `Tlc_AmendmentDocument`,
`Tlc_AmendmentRestructuring`, `Tlc_AmendmentAllocationAdjustment`, `Tlc_AmendmentCorrection`,
`Tlc_AmendmentBreakFee`, `Tlc_AmendmentSnapshot`.

## Filters

`TlcStandingAmendments` (`TLC_AMENDMENT.WITHDRAWN_ON is null`),
`TlcOpenWorkflows` (`TLC_WORKFLOW.COMPLETED_ON is null`).
Both are null tests, because a `Filter` will not take a boolean literal. Note that neither
of them finds "the current version": that is the absence of a superseding row, which no
single-table filter can express — walk `supersededBy` instead.

## Internal associations

`TlcAmendmentChain` (`supersedes`/`supersededBy` — THE self-join),
`TlcAmendmentDetails` (`details`/`detailAmendment`),
`TlcAmendmentReasonLink` (`reason`/`reasonAmendments`),
`TlcAmendmentApprovals` (`approvals`/`approvalAmendment`),
`TlcAmendmentNovation` (`novations`/`novationAmendment`),
`TlcNovationConsents` (`consents`/`consentNovation`),
`TlcAmendmentPartialTermination` (`partialTerminations`/`partialTerminationAmendment`),
`TlcAmendmentTermination` (`terminations`/`terminationAmendment`),
`TlcCompressionCycleMembers` (`members`/`memberCycle`),
`TlcAmendmentCompressionMember` (`compressionMembers`/`compressionAmendment`),
`TlcAmendmentUnwind` (`unwinds`/`unwindAmendment`),
`TlcAmendmentExercise` (`exercises`/`exerciseAmendment`),
`TlcAmendmentExpiry` (`expiries`/`expiryAmendment`),
`TlcAmendmentRateReset` (`rateResets`/`rateResetAmendment`),
`TlcAmendmentCashflows` (`cashflows`/`cashflowAmendment`),
`TlcAmendmentCorporateActionImpact` (`corporateActionImpacts`/`impactAmendment`),
`TlcAmendmentNotifications` (`notifications`/`notificationAmendment`),
`TlcAmendmentWorkflow` (`workflow`/`workflowAmendments`),
`TlcWorkflowSteps` (`steps`/`stepWorkflow`),
`TlcAmendmentAudit` (`auditEntries`/`auditAmendment`),
`TlcAmendmentSource` (`source`/`sourceAmendments`),
`TlcAmendmentDocuments` (`documents`/`documentAmendment`),
`TlcAmendmentRestructuring` (`restructurings`/`restructuringAmendment`),
`TlcAmendmentAllocationAdjustment` (`allocationAdjustments`/`allocationAdjustmentAmendment`),
`TlcAmendmentCorrection` (`corrections`/`correctionAmendment`),
`TlcAmendmentBreakFee` (`breakFees`/`breakFeeAmendment`),
`TlcAmendmentSnapshot` (`snapshots`/`snapshotAmendment`).

## Enumeration mappings

`TlcAmendmentTypeMapping`, `TlcAmendmentStatusMapping`, `TlcConsentStatusMapping`,
`TlcTerminationReasonMapping`, `TlcCompressionMethodMapping`,
`TlcNotificationChannelMapping`, `TlcWorkflowStateMapping` — the only place the physical
code strings live (`NOVA`, `PTRM`, `CMPR`, `DEM`, `MUTL`, `SWFT` and the rest). Each also
accepts the spelled-out form, because the confirmation feeds send the short code and the
back office sends the long one.

## Class constraints

`TlcAmendment` (version positive, applied not before requested, a chain root is version 1),
`TlcNovation` (novated notional positive, transferor differs from transferee),
`TlcPartialTermination` (reduction positive, residual non-negative),
`TlcCompressionCycle` (at least one trade in), `TlcExercise` (exercised quantity positive),
`TlcAllocationAdjustment` (adjusted quantity positive), `TlcBreakFee` (fee non-negative),
`TlcVersionSnapshot` (version positive).

Every comparison in every constraint is FULLY PARENTHESISED — Pure binds `&&` tighter than
the comparison operators.

## Notes for downstream

- **The current version is the one nothing supersedes.** `TlcAmendment.status` may say
  `SUPERSEDED`, but that is a denormalisation set by whoever inserted the next row; the
  authoritative test is `$a.supersededBy->isEmpty()`, which is what `isCurrentVersion()`
  computes. `versionNumber` is likewise advisory — two systems inserting concurrently can
  produce the same number, and only one of them can be at the head of the chain.
- Walking `supersedes` repeatedly reaches the chain root, which is the first thing that ever
  happened to the trade. `isChainRoot()` is `$this.supersedesId->isEmpty()`.
- **A corporate action amends the trade like anything else.** `TlcCorporateActionImpact` has
  a nullable `AMENDMENT_ID`: an impact that has been *computed* but not yet *applied* has no
  amendment, which is not the same fact as an impact that did nothing. For "did nothing",
  see `isMaterial()` — a factor of exactly 1.0 with an amendment attached.
- `TlcVersionSnapshot` is derived data kept for speed. It never contradicts the chain; if it
  does, the chain wins.
- A `TlcTermination` and a `trade_capture::Cancellation` are different assertions. A
  cancellation says the trade should never have existed and reverses the position; a
  termination says it existed and has now stopped, and leaves a settlement amount behind.
- `TlcPartialTermination.reductionRatio()` and `TlcCompressionCycle.compressionRatio()` are
  `Float[1]`, because `/` in Pure widens to Float regardless of its operands.
- `TlcNovationConsent` with status `DEEMED` records consent that nobody gave: the notice
  period expired without a refusal. Do not treat it as `GRANTED` in an evidence trail.
- No `###Data` element and no Runtime, per the contract: the 27 tables are declared and
  unseeded.
