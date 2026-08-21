# trade-surveillance

Layer 3. Depends on **order-execution** and **trade-lifecycle**, and on nothing else.
Package root `trade_surveillance::`; prefixes `SUR_` (tables), `Sur_` (joins), `Sur`
(filters), `sur` (set ids).

33 classes, 11 enums, 47 associations, 33 tables, 47 joins, 3 filters, 33 class sets, 47
AssociationMappings, 11 EnumerationMappings. No functions, no profiles, no `###Data`, no
Runtime.

**The shape to know before using it.** An alert is not a verdict, it is a QUESTION with a
funnel behind it. A `SurSurveillanceModel` raises a `SurAlert` over a `SurEvidenceWindow`;
`SurTriage` closes most of them; the survivors are pulled into a `SurCase` through
`SurCaseAlertLink`; a case has a `SurAnalyst` who owns it, a `SurDeskEnquiry` the desk may
answer, a `SurEscalation` above the analyst, and at the end possibly a
`SurSuspiciousTransactionReport`. `SurModelPerformance` measures the whole funnel back onto
the model, and its `staysSwitchedOn()` is the switch: a false-positive rate above the
tolerance SNAPSHOTTED on the row takes the model off.

**The `{target}` self-join.** `Sur_RelatedCase(SUR_CASE.RELATED_CASE_ID = {target}.CASE_ID)`
— one line, both sides the same table. `trade_surveillance::SurCaseRelation` rides on it
with `SurCase` on both ends, so both of its mapping ends name **`[surCase, surCase]`**, the
same set id twice; only the direction of travel tells `relatedCase` (the case this one
points at) from `linkedCases` (every case that points at this one). `SurCaseLinkReason` is
the label on the edge, and it is load-bearing: `DUPLICATE` means one of the two should be
closed, `SAME_TRADER` means both stay open and are worked together.

**The names a downstream project needs:** `trade_surveillance::SurAlert` **`[surAlert]`**,
`trade_surveillance::SurCase` **`[surCase]`**,
`trade_surveillance::SurSurveillanceModel` **`[surModel]`**,
`trade_surveillance::SurEvidenceWindow` **`[surEvidenceWindow]`**, and
`trade_surveillance::Store` / `trade_surveillance::Mapping`, both of which already
`include` order-execution's and trade-lifecycle's — and therefore, transitively,
order-core's, trade-capture's, event-core's, core-instrument's, core-book's, core-party's
and core-calendar's.

## Cross-project associations

NINE, four into `order_execution::Fill`, two into `order_execution::OrderFillSummary`, one
into `order_execution::TradeBooking`, two into `trade_lifecycle::TlcAmendment`.

| association | this side | far side (project, set id) |
| --- | --- | --- |
| trade_surveillance::SurFillEvidenceLink | `evidenceFill: order_execution::Fill[0..1]` | `surveillanceFillEvidence: SurFillEvidence[*]` — `[oexFill]` |
| trade_surveillance::SurWashBuyFillLink | `buySideFill: order_execution::Fill[0..1]` | `surveillanceWashBuyMatches: SurWashTradeMatch[*]` — `[oexFill]` |
| trade_surveillance::SurWashSellFillLink | `sellSideFill: order_execution::Fill[0..1]` | `surveillanceWashSellMatches: SurWashTradeMatch[*]` — `[oexFill]` |
| trade_surveillance::SurFrontRunAheadFillLink | `aheadFill: order_execution::Fill[0..1]` | `surveillanceFrontRunLeads: SurFrontRunningSequence[*]` — `[oexFill]` |
| trade_surveillance::SurAlertOrderSummaryLink | `alertOrderSummary: order_execution::OrderFillSummary[0..1]` | `surveillanceAlerts: SurAlert[*]` — `[oexOrderFillSummary]` |
| trade_surveillance::SurMessageRateOrderSummaryLink | `metricOrderSummary: order_execution::OrderFillSummary[0..1]` | `surveillanceMessageRates: SurMessageRateMetric[*]` — `[oexOrderFillSummary]` |
| trade_surveillance::SurWashBookingLink | `matchBooking: order_execution::TradeBooking[0..1]` | `surveillanceWashMatches: SurWashTradeMatch[*]` — `[oexTradeBooking]` |
| trade_surveillance::SurAmendmentEvidenceLink | `evidenceAmendment: trade_lifecycle::TlcAmendment[0..1]` | `surveillanceAmendmentEvidence: SurAmendmentEvidence[*]` — `[tlcAmendment]` |
| trade_surveillance::SurPatternAmendmentLink | `patternHeadAmendment: trade_lifecycle::TlcAmendment[0..1]` | `surveillanceAmendmentPatterns: SurAmendmentPattern[*]` — `[tlcAmendment]` |

**Property names this project ADDS to classes it does not own.** A second property of the
same name on the same class fails the whole graph, so a downstream project that also
associates with these classes must avoid these names:

* on `order_execution::Fill` — `surveillanceFillEvidence`, `surveillanceWashBuyMatches`,
  `surveillanceWashSellMatches`, `surveillanceFrontRunLeads`
* on `order_execution::OrderFillSummary` — `surveillanceAlerts`,
  `surveillanceMessageRates`
* on `order_execution::TradeBooking` — `surveillanceWashMatches`
* on `trade_lifecycle::TlcAmendment` — `surveillanceAmendmentEvidence`,
  `surveillanceAmendmentPatterns`

Every one is `surveillance`-prefixed, because `Fill` already carries seventeen end names
from order-execution and `TlcAmendment` twenty-eight from trade-lifecycle, and both
manifests had to be read before any of these could be named.

**This project deliberately names NOTHING from trade-capture, order-core, event-core,
core-instrument, core-party, core-book or core-calendar**, all of which are visible in the
closure. Traders, desks, books, accounts, instruments and venues are held here as String
refs (`traderRef`, `deskRef`, `bookRef`, `accountRef`, `instrumentRef`, `venueRef`) for
exactly that reason. `trade_capture::Trade` is reached, when it is reached at all, by
navigating a dependency's own association from `Fill` or `TlcAmendment`.

## Exports

| element | kind | note |
| --- | --- | --- |
| trade_surveillance::SurSurveillanceModel | class | one detection scenario, versioned and switchable; carries the false-positive tolerance |
| trade_surveillance::SurModelParameter | class | one tuned threshold with the dates it was in force, who changed it, who approved it |
| trade_surveillance::SurModelCalibration | class | the tuning run against history — evidence the threshold was chosen, not guessed |
| trade_surveillance::SurModelPerformance | class | THE feedback edge; `falsePositiveRate()` and `staysSwitchedOn()` against a snapshotted tolerance |
| trade_surveillance::SurSurveillanceRun | class | one batch execution over one business date; a replay is flagged so it is not double-counted |
| trade_surveillance::SurAlert | class | THE unit; one model's claim about one trader on one day; 2 constraints, 3 derived |
| trade_surveillance::SurEvidenceWindow | class | the interval the evidence was drawn over, and the boundary of what the alert can argue |
| trade_surveillance::SurEvidenceItem | class | one artefact inside the window; `isMaterial` separates argument from context |
| trade_surveillance::SurFillEvidence | class | an item that IS an order-execution fill, with the fill's numbers copied onto the row |
| trade_surveillance::SurAmendmentEvidence | class | an item that IS a trade-lifecycle amendment; `daysAfterTrade` is what makes it interesting |
| trade_surveillance::SurAmendmentPattern | class | the amendment CHAIN as a signal; chain length, economic share, single-handed share |
| trade_surveillance::SurOrderBookSnapshot | class | the book at one instant — the spoofing evidence, which cannot be reconstructed later |
| trade_surveillance::SurBookLevel | class | one price level with OUR share of it separated out; layering is unarguable without it |
| trade_surveillance::SurCancellationBurst | class | orders placed and pulled; the short median lifetime is the whole allegation |
| trade_surveillance::SurMessageRateMetric | class | quote stuffing; `orderToTradeRatio` is `[0..1]` because zero trades is the case |
| trade_surveillance::SurPriceImpactObservation | class | the move net of the market's move; `excessMoveBps()` is what survives a big index day |
| trade_surveillance::SurClosingPriceInfluence | class | share of the closing auction, last-minute order count, whether the date is a benchmark |
| trade_surveillance::SurWashTradeMatch | class | both legs of a possible wash; ownership test and net position change can disagree |
| trade_surveillance::SurFrontRunningSequence | class | the house order ahead of the client's; early AND profitable, or it is not a case |
| trade_surveillance::SurWatchList | class | the list insider dealing is only detectable against; restricted vs watch is not the same |
| trade_surveillance::SurWatchListEntry | class | one name on one list for one span of dates; the span is the point |
| trade_surveillance::SurNewsEvent | class | the announcement the trade may have been ahead of |
| trade_surveillance::SurTriage | class | the first-line review that closes most alerts; auto vs by-hand, and the minutes it cost |
| trade_surveillance::SurTriageReason | class | the controlled vocabulary of WHY; `isFalsePositiveReason` is what feeds model tuning |
| trade_surveillance::SurCase | class | the alert that survived triage; the SELF-JOIN class, 3 constraints, 4 derived |
| trade_surveillance::SurCaseAlertLink | class | one alert's membership of one case, as a row, with who pulled it in |
| trade_surveillance::SurAnalyst | class | the human who owns the case; certification and open-case limit |
| trade_surveillance::SurCaseAssignment | class | who held the case between which moments; ownership moves and the history survives |
| trade_surveillance::SurInvestigationStep | class | one thing the analyst actually did — "investigated" vs "merely closed" |
| trade_surveillance::SurDeskEnquiry | class | the question put to the desk and the answer; silence is its own escalation trigger |
| trade_surveillance::SurCommunicationRecord | class | the chat or call attached to the case; `isPrivileged` limits what the regulator sees |
| trade_surveillance::SurEscalation | class | the ask and the decision, in one row; an undecided escalation is a committee's inbox |
| trade_surveillance::SurSuspiciousTransactionReport | class | the filing; WITHDRAWN and REJECTED differ, and only the second leaves the duty outstanding |
| trade_surveillance::SurAlertType | enum | 12 values: SPOOFING, LAYERING, WASH_TRADE, MARKING_THE_CLOSE, MOMENTUM_IGNITION, FRONT_RUNNING, INSIDER_DEALING, QUOTE_STUFFING, RAMPING, CROSS_MARKET_MANIPULATION, AMENDMENT_PATTERN, UNAUTHORISED_TRADING |
| trade_surveillance::SurAlertStatus | enum | NEW, IN_TRIAGE, TRIAGED_OUT, UNDER_INVESTIGATION, ESCALATED, REPORTED, CLOSED_NO_ACTION, CLOSED_DUPLICATE, SUPPRESSED |
| trade_surveillance::SurSeverity | enum | LOW, MEDIUM, HIGH, CRITICAL |
| trade_surveillance::SurEvidenceKind | enum | ORDER, EXECUTION, FILL, AMENDMENT, QUOTE, MARKET_DATA, COMMUNICATION, NEWS, POSITION, REFERENCE_DATA |
| trade_surveillance::SurTriageOutcome | enum | CLOSED_FALSE_POSITIVE, CLOSED_EXPLAINED, CLOSED_DUPLICATE, CLOSED_BELOW_MATERIALITY, CLOSED_KNOWN_BEHAVIOUR, PROMOTED_TO_CASE, DEFERRED_PENDING_DATA |
| trade_surveillance::SurCaseStatus | enum | OPEN, PENDING_DESK_RESPONSE, PENDING_REVIEW, ESCALATED, REPORTED, CLOSED |
| trade_surveillance::SurCaseOutcome | enum | NO_MARKET_ABUSE, INTERNAL_POLICY_BREACH, REPORTED_TO_REGULATOR, REFERRED_TO_HR, REFERRED_TO_LEGAL, INCONCLUSIVE |
| trade_surveillance::SurCaseLinkReason | enum | DUPLICATE, SAME_TRADER, SAME_DESK, SAME_INSTRUMENT, SAME_CLIENT, SAME_PATTERN, PARENT_INVESTIGATION, SUPERSEDES — the label on the self-join |
| trade_surveillance::SurModelState | enum | DEVELOPMENT, PARALLEL_RUN, LIVE, SUSPENDED, RETIRED |
| trade_surveillance::SurReportStatus | enum | DRAFT, LEGAL_REVIEW, READY_TO_SUBMIT, SUBMITTED, ACKNOWLEDGED, REJECTED, WITHDRAWN |
| trade_surveillance::SurMarketPhase | enum | PRE_OPEN, OPENING_AUCTION, CONTINUOUS, INTRADAY_AUCTION, CLOSING_AUCTION, POST_CLOSE, HALTED |
| trade_surveillance::Store | store | 33 `SUR_` tables; `include`s `order_execution::Store` and `trade_lifecycle::Store` |
| trade_surveillance::Mapping | mapping | 33 class sets, 47 AssociationMappings, 11 EnumerationMappings; `include`s both dependency mappings |

## Set ids (a GLOBAL namespace; name these, the defaults do not exist)

`surModel`, `surModelParameter`, `surModelCalibration`, `surModelPerformance`, `surRun`,
`surAlert`, `surEvidenceWindow`, `surEvidenceItem`, `surFillEvidence`,
`surAmendmentEvidence`, `surAmendmentPattern`, `surBookSnapshot`, `surBookLevel`,
`surCancellationBurst`, `surMessageRate`, `surPriceImpact`, `surClosingInfluence`,
`surWashMatch`, `surFrontRunning`, `surWatchList`, `surWatchListEntry`, `surNewsEvent`,
`surTriage`, `surTriageReason`, `surCase`, `surCaseAlertLink`, `surAnalyst`,
`surCaseAssignment`, `surInvestigationStep`, `surDeskEnquiry`, `surCommunication`,
`surEscalation`, `surReport`.

Every set is declared with an explicit id, so `trade_surveillance_SurAlert` and its
siblings do NOT exist. A downstream `extends [...]` or cross-project `AssociationMapping`
must name the `sur` id above.

Note the seven that do not match their class name mechanically: `SurSurveillanceModel` is
`[surModel]`, `SurSurveillanceRun` is `[surRun]`, `SurOrderBookSnapshot` is
`[surBookSnapshot]`, `SurMessageRateMetric` is `[surMessageRate]`,
`SurPriceImpactObservation` is `[surPriceImpact]`, `SurClosingPriceInfluence` is
`[surClosingInfluence]`, `SurWashTradeMatch` is `[surWashMatch]`,
`SurFrontRunningSequence` is `[surFrontRunning]`, `SurCommunicationRecord` is
`[surCommunication]` and `SurSuspiciousTransactionReport` is `[surReport]`.

## Tables

`SUR_MODEL`, `SUR_MODEL_PARAMETER`, `SUR_MODEL_CALIBRATION`, `SUR_MODEL_PERFORMANCE`,
`SUR_RUN`, `SUR_ALERT`, `SUR_EVIDENCE_WINDOW`, `SUR_EVIDENCE_ITEM`, `SUR_FILL_EVIDENCE`,
`SUR_AMENDMENT_EVIDENCE`, `SUR_AMENDMENT_PATTERN`, `SUR_BOOK_SNAPSHOT`, `SUR_BOOK_LEVEL`,
`SUR_CANCELLATION_BURST`, `SUR_MESSAGE_RATE`, `SUR_PRICE_IMPACT`, `SUR_CLOSING_INFLUENCE`,
`SUR_WASH_MATCH`, `SUR_FRONT_RUNNING`, `SUR_WATCH_LIST`, `SUR_WATCH_LIST_ENTRY`,
`SUR_NEWS_EVENT`, `SUR_TRIAGE`, `SUR_TRIAGE_REASON`, `SUR_CASE`, `SUR_CASE_ALERT`,
`SUR_ANALYST`, `SUR_CASE_ASSIGNMENT`, `SUR_INVESTIGATION_STEP`, `SUR_DESK_ENQUIRY`,
`SUR_COMMUNICATION`, `SUR_ESCALATION`, `SUR_STR`.

`SUR_CASE` carries `RELATED_CASE_ID` (to ITSELF) and `OWNER_ID`. `SUR_ALERT` carries
`RUN_ID`, `MODEL_ID`, `WINDOW_ID`, `WATCH_ENTRY_ID` and `SUMMARY_ID` (to
`OEX_ORDER_FILL_SUMMARY`). `SUR_FILL_EVIDENCE`, `SUR_WASH_MATCH` (twice, `BUY_FILL_ID` and
`SELL_FILL_ID`) and `SUR_FRONT_RUNNING` carry keys to `OEX_FILL`; `SUR_WASH_MATCH` also
carries `BOOKING_ID` to `OEX_TRADE_BOOKING`; `SUR_AMENDMENT_EVIDENCE` and
`SUR_AMENDMENT_PATTERN` carry `AMENDMENT_ID` to `TLC_AMENDMENT`. None of the cross-project
keys is mapped to a property — the model reaches those things by association.

## Joins available to downstream projects that include this store

Self: **`Sur_RelatedCase`**.

Cross-boundary into order-execution: `Sur_FillEvidenceFill`, `Sur_WashBuyFill`,
`Sur_WashSellFill`, `Sur_FrontRunAheadFill`, `Sur_AlertOrderSummary`,
`Sur_MessageRateOrderSummary`, `Sur_WashBooking`.

Cross-boundary into trade-lifecycle: `Sur_AmendmentEvidenceAmendment`,
`Sur_PatternAmendment`.

Internal: `Sur_ModelParameter`, `Sur_ModelCalibration`, `Sur_ModelPerformance`,
`Sur_ModelRun`, `Sur_RunAlert`, `Sur_AlertModel`, `Sur_AlertWindow`, `Sur_WindowItem`,
`Sur_ItemFillEvidence`, `Sur_ItemAmendmentEvidence`, `Sur_AlertAmendmentPattern`,
`Sur_WindowBookSnapshot`, `Sur_SnapshotLevel`, `Sur_WindowCancellationBurst`,
`Sur_WindowMessageRate`, `Sur_WindowPriceImpact`, `Sur_WindowClosingInfluence`,
`Sur_AlertWashMatch`, `Sur_AlertFrontRunning`, `Sur_ListEntry`, `Sur_EntryNews`,
`Sur_AlertWatchEntry`, `Sur_AlertTriage`, `Sur_TriageReasonLink`, `Sur_TriageAnalyst`,
`Sur_CaseAlertLinkCase`, `Sur_CaseAlertLinkAlert`, `Sur_CaseAssignment`,
`Sur_AssignmentAnalyst`, `Sur_CaseOwner`, `Sur_CaseStep`, `Sur_StepAnalyst`,
`Sur_CaseEnquiry`, `Sur_CaseCommunication`, `Sur_CaseEscalation`, `Sur_CaseReport`,
`Sur_EscalationReport`.

## Filters

`SurOpenAlerts` (`SUR_ALERT.CLOSED_AT is null`),
`SurOpenCases` (`SUR_CASE.CLOSED_ON is null`),
`SurLiveModels` (`SUR_MODEL.RETIRED_ON is null`).
All three are null tests, because a `Filter` will not take a boolean literal. None of them
finds "a model that should stay switched on": that is a measured rate compared against a
snapshotted tolerance, which no single-table filter can express — use
`SurModelPerformance.staysSwitchedOn()`.

## Internal associations

`SurCaseRelation` (`relatedCase`/`linkedCases` — THE self-association),
`SurModelParameters` (`parameters`/`parameterModel`),
`SurModelCalibrations` (`calibrations`/`calibrationModel`),
`SurModelPerformances` (`performanceRecords`/`performanceModel`),
`SurModelRuns` (`runs`/`runModel`),
`SurRunAlerts` (`runAlerts`/`alertRun`),
`SurAlertModelLink` (`modelAlerts`/`alertModel`),
`SurAlertWindow` (`evidenceWindow`/`windowAlert`),
`SurWindowItems` (`items`/`itemWindow`),
`SurItemFillEvidence` (`fillEvidence`/`evidenceItem`),
`SurItemAmendmentEvidence` (`amendmentEvidence`/`amendmentEvidenceItem`),
`SurAlertAmendmentPatterns` (`amendmentPatterns`/`patternAlert`),
`SurWindowBookSnapshots` (`bookSnapshots`/`snapshotWindow`),
`SurSnapshotLevels` (`levels`/`levelSnapshot`),
`SurWindowCancellationBursts` (`cancellationBursts`/`burstWindow`),
`SurWindowMessageRates` (`messageRates`/`messageRateWindow`),
`SurWindowPriceImpacts` (`priceImpacts`/`impactWindow`),
`SurWindowClosingInfluence` (`closingInfluence`/`influenceWindow`),
`SurAlertWashMatches` (`washMatches`/`washMatchAlert`),
`SurAlertFrontRunning` (`frontRunningSequences`/`frontRunningAlert`),
`SurWatchListEntries` (`entries`/`entryList`),
`SurEntryNewsEvents` (`newsEvents`/`newsEntry`),
`SurAlertWatchListEntry` (`watchListEntry`/`entryAlerts`),
`SurAlertTriage` (`triage`/`triageAlert`),
`SurTriageReasonLink` (`reason`/`reasonTriages`),
`SurTriageAnalyst` (`reviewer`/`reviewedTriages`),
`SurCaseAlertMembership` (`alertLinks`/`linkCase`),
`SurAlertCaseMembership` (`caseLinks`/`linkAlert`),
`SurCaseAssignments` (`assignments`/`assignmentCase`),
`SurAnalystAssignments` (`caseAssignments`/`analyst`),
`SurCaseOwner` (`owner`/`ownedCases`),
`SurCaseSteps` (`steps`/`stepCase`),
`SurStepAnalyst` (`analystSteps`/`stepAnalyst`),
`SurCaseEnquiries` (`deskEnquiries`/`enquiryCase`),
`SurCaseCommunications` (`communications`/`communicationCase`),
`SurCaseEscalations` (`escalations`/`escalationCase`),
`SurCaseReports` (`reports`/`reportCase`),
`SurEscalationReport` (`escalationReports`/`reportEscalation`).

## Enumeration mappings

`SurAlertTypeMapping`, `SurAlertStatusMapping`, `SurSeverityMapping`,
`SurEvidenceKindMapping`, `SurTriageOutcomeMapping`, `SurCaseStatusMapping`,
`SurCaseOutcomeMapping`, `SurCaseLinkReasonMapping`, `SurModelStateMapping`,
`SurReportStatusMapping`, `SurMarketPhaseMapping` — the only place the physical code
strings live (`SPOOF`, `MKCLS`, `QSTUF`, `CFP`, `PROM`, `CLSA` and the rest). Each also
accepts the spelled-out form, because the vendor surveillance platform emits the short code
and the case-management system emits the long one.

## Class constraints

25 of the 33 classes carry a constraint block. The three the theme demands:

* **`SurModelPerformance.triagedOutNotAboveRaised`** — `($this.alertsTriagedOut <=
  $this.alertsRaised)`, plus `alertsRaisedIsPositive` so `falsePositiveRate()` has a
  denominator. The tolerance is snapshotted onto the row, so re-tuning the model cannot
  retrospectively legalise an old period.
* **`SurCase.relatedCaseIsNotSelf`** —
  `(($this.relatedCaseId->isEmpty()) || ($this.relatedCaseId->toOne() != $this.caseId))`.
  The one keying error the self-join cannot detect for itself: a case pointing at itself
  produces a match on `Sur_RelatedCase` and an infinite walk.
* **`SurAmendmentPattern.chainLengthAtLeastTwo`** — `($this.chainLength >= 2)`. One
  amendment is a correction; the pattern only exists from two.

Others: `SurSurveillanceModel` (lookback positive, tolerance in 0–1, retired not before
deployed), `SurModelParameter`, `SurModelCalibration`, `SurSurveillanceRun`, `SurAlert`
(score in 0–100, closed not before raised), `SurEvidenceWindow` (end not before start),
`SurEvidenceItem`, `SurFillEvidence` (weight in 0–1), `SurAmendmentEvidence`,
`SurOrderBookSnapshot` (ask not below bid), `SurBookLevel` (own share not above the level),
`SurCancellationBurst` (cancelled not above placed), `SurMessageRateMetric` (interval
positive), `SurPriceImpactObservation` (volume share in 0–1), `SurClosingPriceInfluence`
(own volume not above the auction), `SurWashTradeMatch`, `SurFrontRunningSequence`,
`SurWatchList`, `SurWatchListEntry`, `SurTriage` (confidence in 0–1), `SurTriageReason`,
`SurAnalyst`, `SurCaseAssignment`, `SurInvestigationStep`, `SurDeskEnquiry`,
`SurEscalation`, `SurSuspiciousTransactionReport`.

EVERY comparison is FULLY PARENTHESISED. Pure binds `&&` tighter than the comparison
operators, so `$this.a >= 0.0 && $this.a <= 1.0` parses as `(($this.a >= 0.0) && $this.a)
<= 1.0` and fails to compile. Every rule over an OPTIONAL property is written
`(($this.x->isEmpty()) || ($this.x->toOne() >= $this.y))`, because a constraint over a
`[0..1]` does not type-check on its own.

## Notes for downstream

- **An alert is not a finding.** `SurAlert.status` is a denormalisation; the authoritative
  test for "this survived first line" is `survivedTriage()`, which is
  `$this.caseLinks->isNotEmpty()`. An alert with a `SurTriage` whose outcome is
  `PROMOTED_TO_CASE` but no `SurCaseAlertLink` is a promotion nobody finished.
- **The false-positive rate is the switch.** `SurModelPerformance.staysSwitchedOn()`
  compares the measured rate against `toleranceAtReview`, NOT against
  `SurSurveillanceModel.falsePositiveTolerance`. The two differ exactly when somebody has
  moved the tolerance since the period was reviewed, which is the case the snapshot exists
  for. Note also that not every triaged-out alert is the model's fault — the split lives on
  `SurTriageReason.isFalsePositiveReason`, and a model whose alerts are all
  `CLOSED_EXPLAINED` is working correctly and is merely unlucky in its desk.
- **A case that points at itself breaks the walk.** `Sur_RelatedCase` reads
  `RELATED_CASE_ID` against `{target}.CASE_ID`; the `relatedCaseIsNotSelf` constraint is
  the only thing standing between a keying error and a cycle. The chain is not otherwise
  bounded — nothing stops A linking to B linking to A, and a reader walking `relatedCase`
  repeatedly must carry its own visited set.
- **The amendment chain is a signal, not just provenance.** `SurAmendmentPattern`
  deliberately points at the HEAD of a trade-lifecycle chain — the row nothing supersedes,
  which is `TlcAmendment.isCurrentVersion()` — so walking `supersedes` from
  `patternHeadAmendment` replays exactly what the pattern measured. A pattern whose
  `chainLength` disagrees with the walk means the chain grew after the alert was raised,
  which is itself worth looking at.
- **`orderToTradeRatio` is `[0..1]` on purpose.** An interval with ten thousand messages
  and no trades is the quote-stuffing case, and there is no ratio to store for it.
  `tradedNothing()` is the test; treating the empty as a zero ratio inverts the finding.
- **`SurEvidenceWindow.isTruncated` weakens everything hanging off it.** A window that hit
  a row cap does not contain everything in its own interval, so a count taken from it is a
  floor, not a total.
- **Quantities and prices here are `Float`**, matching order-execution rather than
  order-core's `Integer` share counts. Comparing against an order-core quantity crosses a
  type boundary: widen with `->toFloat()`, since `Integer * Float` types as `Number`, which
  is not a subtype of `Float`.
- **A `SurTriage` with `isAutoTriaged` true is a machine closing an alert.** It is a
  different fact from an analyst closing one, and `wasWorkedByHand()` is the test. A
  surveillance estate whose triage is 95% automatic has a control, not a review.
- **`SurDeskEnquiry.isSatisfactory` and `hasResponse()` are independent.** Silence is not an
  unsatisfactory answer; it is `escalatedForNoResponse`, and only the second of those two
  leaves the desk with something to explain.
- **`SurSuspiciousTransactionReport` covers orders as well as transactions.** An order
  that was never executed is reportable on its own; `isSuspiciousOrder` and
  `isSuspiciousTransaction` are not alternatives and a case can be both.
- The include diamond closes. `order_execution::Store` includes `trade_capture::Store` and
  `order_core::Store`; `trade_lifecycle::Store` includes `trade_capture::Store` and
  `event_core::Store`. trade-capture arrives here twice by two routes and resolves to one —
  the tables are not duplicated and the joins resolve. Same for the two mappings.
- No `###Data` element and no Runtime, per the contract: the 33 tables are declared and
  unseeded.
