# credit-limits

Layer 2. Depends on `credit-core` and `limit-core`, and on nothing else. Package root
`credit_limits::`, prefixes `CLM_` (tables), `Clm_` (joins), `Clm` (filters), `clm` (set ids).

Limit utilisation against milestoned exposures: how much of a credit limit was used on a day,
against the threshold that was in force on that day and the rating the obligor carried on that
day, plus the excess it produced, who signed for it, the uplifts that expire on their own, and
the daily report that publishes all of it.

Two things to know before depending on this project.

**1. There are TWO milestoned routes out of `credit_limits::ClmCreditLimit`, and both take a
date because the compiler will not let them not.**

    // OURS: ClmLimitStanding is <<temporal.businesstemporal>>, so `standing` is dated.
    credit_limits::ClmCreditLimit.all()->map(l | $l.standing(%2024-03-31))
    credit_limits::ClmCreditLimit.all()->map(l | $l.standingAsOf(%latest))

    // THEIRS: through credit-core to core-ratings' milestoned RatingVersion.
    credit_limits::ClmCreditLimit.all()->map(l | $l.ratingSymbolOn(%2024-03-31))

`$l.standing` with no date does not compile, exactly as `$o.obligorRatings` does not in
credit-core. `%latest` works on `standing` because `CLM_LIMIT_STANDING_MS` declares
`INFINITY_DATE`. The wrapped forms — `standingAsOf(date)`, `approvedThresholdOn(date)`,
`ratingSymbolOn(date)` — are the named functions to prefer.

**2. `ratingSymbolOn` returns `String[*]`, not a rating object.** The symbol, unnormalised, as
the agency published it — the same values `credit_core::CdcRatingPd.ratingSymbol` joins on. The
temporal class itself is deliberately never named in this project's source, because
`core-ratings` is a transitive dependency and not a declared one; a downstream project that
wants the object rather than the symbol should go via `credit_core::CdcObligor.ratingsAsOf`.

## Exports

| element | kind | note |
| --- | --- | --- |
| credit_limits::ClmCreditLimit | class | **the anchor**: limit-core's triple (bookId, counterpartyId, measureCode) tied to a credit-core obligor; both milestoned routes hang off it; constraints `monitoringWithinApproved`, `approvedThresholdNotNegative` |
| credit_limits::ClmLimitStanding | class | **`<<temporal.businesstemporal>>`** — the approved threshold WITH ITS SPAN; no from/thru properties; constraints `standingThresholdNotNegative`, `standingBandsOrdered` |
| credit_limits::ClmUtilisationLine | class | **the grain**: one limit, one date, one utilisation; freezes the threshold and the rating that applied; constraints `exposureCoversDrawn`, `pctInRange`, `appliedThresholdPositive` |
| credit_limits::ClmExposureContribution | class | which `credit_core::CdcExposure` contributed how much to a line; constraint `grossCoversContribution` |
| credit_limits::ClmExcess | class | the credit view of a threshold crossing; `breachId` optionally ties it to `limit_core::LimBreach`; constraints `observedExceedsThreshold`, `approvedWithinExcess`, `severityInRange` |
| credit_limits::ClmExcessApproval | class | sign-off on an excess, keyed (excessId, approvalLevel); approver is limit-core's; constraints `levelIsPositive`, `validityOrdered` |
| credit_limits::ClmTemporaryUplift | class | temporary increase with a NOT-NULL expiry; mirrors `limit_core::LimUplift` when granted there; constraints `expiryAfterStart`, `liftedCoversBase` |
| credit_limits::ClmRatingOverlay | class | the frozen daily rating capture, keyed (obligorId, asOfDate); `liveSymbolOn(date)` is the milestoned reconciliation against it; constraints `notchInRange`, `factorInRange` |
| credit_limits::ClmEscalationTicket | class | who was told, by when, and whether they answered; constraint `responseIsPositive` |
| credit_limits::ClmDailyLimitReport | class | the daily run header and its totals; constraint `excessesWithinLimits` |
| credit_limits::ClmDailyReportLine | class | one printed row, keyed (reportId, creditLimitId); holds what was published, and `ratingSymbolOn(date)` / `thresholdOn(date)` to re-check it |
| credit_limits::ClmMeasureBasis | class | how a credit exposure becomes the number a limit measure is compared against; constraint `factorInRange` |
| credit_limits::Store | store | 12 tables `CLM_*` (one of them milestoned), 19 joins `Clm_*`, filters `ClmLiveCreditLimits`, `ClmOpenExcesses`; `include`s both dependency stores |
| credit_limits::Mapping | mapping | 12 class sets `clm*`, `include`s both dependency mappings; no `AssociationMapping`s — every cross-project link is an ordinary property mapping |

No associations, no enums, no functions and no profiles are exported.

## Tables

`CLM_MEASURE_BASIS`, `CLM_CREDIT_LIMIT`, `CLM_LIMIT_STANDING_MS`\*†, `CLM_UTILISATION_LINE`\*,
`CLM_CONTRIBUTION`, `CLM_EXCESS`, `CLM_EXCESS_APPROVAL`\*, `CLM_TEMP_UPLIFT`,
`CLM_RATING_OVERLAY`\*, `CLM_ESCALATION_TICKET`, `CLM_DAILY_REPORT`, `CLM_DAILY_REPORT_LINE`\*
(\* = composite primary key, † = milestoned, `business(BUS_FROM = FROM_Z, BUS_THRU = THRU_Z,
INFINITY_DATE = %9999-12-31)`, PK `(CREDIT_LIMIT_ID, FROM_Z)`)

## Joins

Into limit-core, three-column on the limit triple: `Clm_Limit_LimLimit`,
`Clm_Limit_LimUtilisation`. Single-column: `Clm_Basis_LimMeasure`, `Clm_Excess_LimBreach`,
`Clm_Approval_LimApprover`, `Clm_Uplift_LimUplift`.

Into credit-core: `Clm_Limit_Obligor`, `Clm_Overlay_Obligor` (both to `CDC_OBLIGOR`, which is
the near end of credit-core's route to `CR_RATING_MS`), `Clm_Contribution_Exposure`.

Inside, into the milestoned table: `Clm_Limit_Standing` — a plain key equality naming NO date
column, because `FROM_Z`/`THRU_Z` are the engine's.

Inside, two-column on (limit, date): `Clm_Line_Contribution`, `Clm_Line_Excess`.

Inside, single-column: `Clm_Limit_Utilisation`, `Clm_Limit_Uplift`, `Clm_Limit_Excess`,
`Clm_Excess_Approval`, `Clm_Excess_Ticket`, `Clm_Report_Line`, `Clm_ReportLine_Limit`.

## Filters

`ClmLiveCreditLimits` (`CLM_CREDIT_LIMIT.RETIRED_ON is null`), `ClmOpenExcesses`
(`CLM_EXCESS.CLEARED_ON is null`). Null tests rather than boolean literals, which a `Filter`
will not take.

## Set ids

`clmMeasureBasis`, `clmCreditLimit`, `clmLimitStanding`, `clmUtilisationLine`,
`clmContribution`, `clmExcess`, `clmExcessApproval`, `clmUplift`, `clmRatingOverlay`,
`clmEscalationTicket`, `clmDailyReport`, `clmReportLine`

## Notes for downstream

- These set ids are explicit, so the default ids (`credit_limits_ClmCreditLimit`) do not exist.
  An `extends [...]` or a cross-project `AssociationMapping` must name the id above. Note
  `clmUplift`, `clmContribution`, `clmDailyReport` and `clmReportLine` are shorter than their
  class names.
- Including `credit_limits::Store` brings `credit_core::Store` and `limit_core::Store` with it,
  and with them `core_party::Store` (twice, by both paths of the diamond — which is fine and
  needs nothing said about it), `core_ratings::Store` and `core_book::Store`. Including any of
  them again is not needed. The same holds for `credit_limits::Mapping`.
- Derived properties available: `ClmCreditLimit.isLive()`, `ClmCreditLimit.monitoringHeadroom()`,
  `ClmUtilisationLine.headroomPct()`, `ClmUtilisationLine.utilisationRatio()`,
  `ClmUtilisationLine.isOverLimit()`, `ClmUtilisationLine.undrawnShare()`,
  `ClmExposureContribution.conversionPct()`, `ClmExcess.isOpen()`, `ClmExcess.isCritical()`,
  `ClmExcess.unapprovedAmount()`, `ClmTemporaryUplift.upliftPct()`,
  `ClmEscalationTicket.isOutstanding()`, `ClmDailyLimitReport.excessRatePct()`,
  `ClmDailyLimitReport.portfolioUtilisationPct()`. The dividing ones are `Float[1]`, because
  `/` widens.
- Dated properties available: `ClmCreditLimit.standing(date)` (generated),
  `ClmCreditLimit.standingAsOf(date)`, `ClmCreditLimit.approvedThresholdOn(date)`,
  `ClmCreditLimit.ratingSymbolOn(date)`, `ClmUtilisationLine.ratingSymbolOn(date)`,
  `ClmUtilisationLine.thresholdOn(date)`, `ClmExcess.ratingSymbolOn(date)`,
  `ClmDailyReportLine.ratingSymbolOn(date)`, `ClmDailyReportLine.thresholdOn(date)`,
  `ClmRatingOverlay.liveSymbolOn(date)`. All take `Date[1]`; the two `...On(date)` families
  return `String[*]` and `Float[*]` because a business date can select a span that is not
  there.
- Constraints are declared, not enforced by the store. `ClmUtilisationLine.headroomAmount` and
  `ClmCreditLimit`'s thresholds deliberately carry no upper bound: negative headroom is exactly
  what an excess looks like, and `utilisationPct` is allowed up to 1000 for the same reason.
- No `###Data`, no Runtime, no seeded rows.
