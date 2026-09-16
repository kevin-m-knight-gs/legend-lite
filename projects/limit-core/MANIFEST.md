# limit-core

Layer 1. Depends on `core-party` and `core-book`, and on nothing else. Package root
`limit_core::`, prefixes `LIM_` (tables), `Lim_` (joins), `Lim` (filters), `lim` (set ids).

Credit and market limits: what one book may run against one counterparty on one measure, how
much of it is used, what happens when it is exceeded, and the temporary uplifts that expire on
their own.

The shape to know before using it: a limit is keyed by the TRIPLE `(bookId, counterpartyId,
measureCode)` and by nothing shorter. The same book has a notional limit and a tenor limit
against the same counterparty; those are two rows with two thresholds, two utilisations and two
breach histories. Every join back to a limit matches on all three columns — matching on book
and counterparty alone puts a tenor limit's usage on a notional limit. `LIM_LIMIT`,
`LIM_UTILISATION` and `LIM_THRESHOLD_BAND` carry three-column keys; `LIM_UTILISATION_SNAPSHOT`
and `LIM_LIMIT_REVIEW` carry the triple plus a date.

The second construct is CLASS CONSTRAINTS: ten of the twelve classes declare them, and each
constraint body is fully parenthesised because `&&` binds tighter than the comparison operators.

## Exports

| element | kind | note |
| --- | --- | --- |
| limit_core::LimLimit | class | **the three-column composite-key class**: keyed (bookId, counterpartyId, measureCode); hard/soft/warning thresholds; constraints `softWithinHard`, `bandsOrdered` |
| limit_core::LimMeasure | class | what is being limited — NOTIONAL, TENOR, PV01; the measure leg of the key |
| limit_core::LimFramework | class | the governance policy a limit is written under; owned by a `core_party::LegalEntity` |
| limit_core::LimThresholdBand | class | amber/red trigger percentages, keyed (frameworkId, measureCode, bandCode) — a second three-column key |
| limit_core::LimUtilisation | class | current usage on the limit's own triple; constraints `utilisationNotNegative`, `grossCoversNet`, `peakCoversCurrent` |
| limit_core::LimUtilisationSnapshot | class | one day's frozen usage, keyed (triple + asOfDate) |
| limit_core::LimBreach | class | a threshold crossing; keyed `breachId`, carries the triple; `closedOn` null while open |
| limit_core::LimBreachApproval | class | sign-off on a breach, keyed (breachId, approvalLevel) — one row per level of the chain |
| limit_core::LimApprover | class | who may sign, at what level, up to `maxApprovalAmount` |
| limit_core::LimUplift | class | temporary increase with a NOT-NULL expiry; constraint `expiryAfterStart` |
| limit_core::LimEscalationRule | class | who is told and how fast, keyed (frameworkId, severityCode) |
| limit_core::LimLimitReview | class | periodic re-approval, keyed (triple + reviewDate) |
| limit_core::Store | store | 12 tables `LIM_*`, includes `core_party::Store` and `core_book::Store`; 15 joins `Lim_*`; filters `LimLiveLimits`, `LimOpenBreaches` |
| limit_core::Mapping | mapping | 12 class sets `lim*`, includes `core_party::Mapping` and `core_book::Mapping`; `~primaryKey` on every set |

No associations, no enums, no functions and no profiles are exported. The cross-project links
are ordinary property mappings, not `AssociationMapping`s.

## Tables

`LIM_LIMIT`\*, `LIM_MEASURE`, `LIM_FRAMEWORK`, `LIM_THRESHOLD_BAND`\*, `LIM_UTILISATION`\*,
`LIM_UTILISATION_SNAPSHOT`\*, `LIM_BREACH`, `LIM_BREACH_APPROVAL`\*, `LIM_APPROVER`,
`LIM_UPLIFT`, `LIM_ESCALATION_RULE`\*, `LIM_LIMIT_REVIEW`\*  (\* = composite primary key)

## Joins

Across the boundary: `Lim_LimitBook` (`LIM_LIMIT.BOOK_ID = CB_BOOK.BOOK_ID`),
`Lim_LimitCounterparty` (`LIM_LIMIT.COUNTERPARTY_ID = CP_LEGAL_ENTITY.ENTITY_ID`),
`Lim_FrameworkEntity`.

Three-column: `Lim_LimitUtilisation`, `Lim_LimitSnapshot`, `Lim_LimitBreach`,
`Lim_LimitUplift`, `Lim_LimitReview`.

Single-column: `Lim_LimitMeasure`, `Lim_LimitFramework`, `Lim_BreachApproval`,
`Lim_ApprovalApprover`, `Lim_UpliftApprover`, `Lim_FrameworkBand`, `Lim_FrameworkEscalation`.

## Filters

`LimLiveLimits` (`LIM_LIMIT.RETIRED_ON is null`), `LimOpenBreaches`
(`LIM_BREACH.CLOSED_ON is null`). Null tests rather than boolean literals, which a `Filter`
will not take.

## Set ids

`limLimit`, `limMeasure`, `limFramework`, `limBand`, `limUtilisation`, `limSnapshot`,
`limBreach`, `limBreachApproval`, `limApprover`, `limUplift`, `limEscalationRule`, `limReview`

## Notes for downstream

- These set ids are explicit, so the default ids (`limit_core_LimLimit`) do not exist. An
  `extends [...]` or a cross-project `AssociationMapping` must name the id above.
- Including `limit_core::Store` brings `core_party::Store` and `core_book::Store` with it, and
  including `limit_core::Mapping` brings both their mappings — including them again as well is
  not needed.
- A limit is reached from a book by `Lim_LimitBook` and from a counterparty by
  `Lim_LimitCounterparty`; the upstream ends are `cbBook` and `cpLegalEntity`.
- Constraints are declared, not enforced by the store. `LimUtilisation.availableAmount`
  deliberately carries no non-negative constraint: negative headroom is exactly what a breach
  looks like.
- Derived properties available: `LimLimit.softHeadroom()`, `LimLimit.softRatioPct()`,
  `LimLimit.isLive()`, `LimUtilisation.headroomPct()`, `LimUtilisation.nettingBenefit()`,
  `LimBreach.isCritical()`, `LimBreach.isOpen()`, `LimUplift.upliftPct()`,
  `LimLimitReview.proposedChange()`.
- No `###Data`, no Runtime, no seeded rows.
