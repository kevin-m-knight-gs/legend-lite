# client-reporting

Layer 2. Depends on **client-core**, **position-keeping** and **valuation-core**, and on
nothing else. Package root `client_reporting::`, prefixes `Crp` (classes), `CRP_` (tables),
`Crp_` (joins), `Crp` (filters), `crp` (set ids).

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
`###Data`, no Runtime.

## The shape: a THREE-WAY FAN-IN, not a diamond

The three dependencies share no upstream project at all — client-core sits on core-party and
core-geo, position-keeping on core-account and core-instrument, valuation-core on core-price
and core-fx. Six layer-0 projects arrive here through three disjoint paths, which is why
`client_reporting::Store` can `include` all three without any store appearing twice, and why
the transitive closure is nine projects.

What each dependency contributes is a different KIND of thing:

| dependency | contributes | reached by |
| --- | --- | --- |
| client-core | WHO it is for and what was agreed | `CLIENT_ID`, `MANDATE_ID`, `CONTACT_ID` — 1-column joins, plus one 4-hop chain |
| position-keeping | WHAT they held and what moved | the 4-column position key, the 5-column movement key, the 3-column account-day VIEW key |
| valuation-core | WHAT IT WAS WORTH | the 3-column valuation key `(runId, instrumentId, cobDate)`, and the run id |

A `CrpValuationLine` carries a position key AND a valuation key on the same row and stores
neither project's numbers twice: `positionMarketValue` is read through `@Crp_LinePosition` and
`valuationDirtyPrice` through `@Crp_LineValuation`, so a line that disagrees with its sources
is visible rather than baked in.

## The AGGREGATION shape

Four store `View`s, each mapped to a class whose primary key is the view's `~groupBy`. Legend
views, not database views: no DDL, nothing seeds them, and the engine folds the `GROUP BY`
into the SQL it generates.

| view | grouping | one row is |
| --- | --- | --- |
| `CRP_VALUATION_LINE_TOTAL` | statement | a statement's holdings, totalled |
| `CRP_DISCLOSURE_COST_TOTAL` | disclosure, cost category | the six-line cost summary above the detail |
| `CRP_CLIENT_PERIOD_TOTAL` | client, period end | everything one client was sent — the grouping DROPS the mandate and the type |
| `CRP_DELIVERY_OUTCOME_TOTAL` | delivery | a delivery's retry history |

A statement with no lines forms **no group** and is absent rather than appearing with a total
of zero. `CRP_CLIENT_PERIOD_TOTAL` also joins back out to `CLI_CLIENT`, so an aggregate here
crosses a project boundary **from a view**.

## Exports — classes

| element | kind | note |
| --- | --- | --- |
| `client_reporting::CrpStatementRun` | class | one execution of the statement factory; key `runId`; derived `isComplete()` |
| `client_reporting::CrpReportingProfile` | class | the agreed reporting terms, keyed by `clientId` alone; frequency, channel, language, and which disclosures apply; derived `isOptedOut()` |
| `client_reporting::CrpStatement` | class | THE ROOT — one client, one mandate, one period, one type; key `statementId`; carries 4 chain properties reaching client-core and core-geo; derived `isWithdrawn()`, `documentLabel()` |
| `client_reporting::CrpDocumentTemplate` | class | the versioned wording a statement was rendered from; key `templateCode`; derived `isCurrent()` |
| `client_reporting::CrpSuppression` | class | a dated hold on a client's documents; key `suppressionId`; derived `isActive()` |
| `client_reporting::CrpValuationStatement` | class | the valuation body, keyed by `statementId`; carries the 2-column account key and the valuation run id; 4 chain properties into position-keeping and valuation-core; derived `netChange()`, `pricedLineCount()`, `unpricedPct(): Float[1]` |
| `client_reporting::CrpValuationLine` | class | one holding line — the join point of the project; key `(statementId, lineSeq)`; carries BOTH the 4-column position key and the valuation run id; derived `costBasis()` |
| `client_reporting::CrpTransactionStatement` | class | the transaction body; key `statementId`; derived `turnover()` |
| `client_reporting::CrpTransactionLine` | class | one transaction; key `(statementId, lineSeq)`; carries the 5-column movement key |
| `client_reporting::CrpCashFlowLine` | class | contributions, withdrawals, fees and income — money rather than securities; key `(statementId, lineSeq)` |
| `client_reporting::CrpCostsDisclosure` | class | the costs-and-charges disclosure in BOTH forms; `basis` is `EX_ANTE` or `EX_POST`; key `disclosureId`; derived `isExAnte()`, `annualisedCostsPct(): Float[1]` |
| `client_reporting::CrpCostLine` | class | one prescribed cost category line; key `(disclosureId, lineSeq)`; derived `amountBps()` |
| `client_reporting::CrpCostIllustration` | class | one year of the ex-ante cumulative-effect table; key `(disclosureId, yearNo)`; derived `costDrag()` |
| `client_reporting::CrpBenchmark` | class | the agreed benchmark; key `benchmarkCode`; `isTotalReturn` and `isComposite` |
| `client_reporting::CrpBenchmarkComponent` | class | one index inside a composite, with its dated weight; key `(benchmarkCode, componentSeq)` |
| `client_reporting::CrpPerformanceReport` | class | portfolio return against the agreed benchmark; key `reportId`; `returnMethod` is TWR or MWR; derived `excessReturnPct()`, `hasOutperformed()` |
| `client_reporting::CrpPerformancePeriod` | class | one standard period (MTD/YTD/3Y/SI); key `(reportId, periodCode)`; derived `excessReturnPct()` |
| `client_reporting::CrpAttributionReport` | class | why the excess return happened; key `attributionId`; allocation / selection / interaction; derived `totalActiveBps()` |
| `client_reporting::CrpAttributionLine` | class | one segment's contribution; key `(attributionId, segmentCode)`; derived `activeWeightPct()`, `totalEffectBps()` |
| `client_reporting::CrpDepreciationCheck` | class | the daily threshold evaluation, kept whether or not it breached; key `checkId`; `multipleCrossed` stops a second notice for the same fall; derived `hasBreached()`, `valueLost()` |
| `client_reporting::CrpDepreciationNotice` | class | the ten-percent notice and its deadline; key `noticeId`; `dueByDate` is close of the NEXT BUSINESS DAY and is stored, not computed; `isLate` records the breach; derived `wasSent()` |
| `client_reporting::CrpDeliveryChannel` | class | the medium; key `channelCode`; `isDurableMedium` is the regulatory question |
| `client_reporting::CrpDelivery` | class | one dispatch of one document; key `deliveryId`; either a statement or a notice; derived `wasDelivered()` |
| `client_reporting::CrpDeliveryAttempt` | class | one try; key `(deliveryId, attemptSeq)`; derived `succeeded()` |
| `client_reporting::CrpAcknowledgement` | class | the client's confirmation, at most one per delivery, so `deliveryId` is the whole key; `isExplicit` separates a signed return from a read receipt |
| `client_reporting::CrpStatementRecipient` | class | who it was addressed to and who was copied; key `(statementId, contactId)`; 2 chain properties into client-core; derived `isSuppressed()` |
| `client_reporting::CrpStatementLineTotal` | class | AGGREGATE on a view: a statement's lines totalled; derived `averageLineValue(): Float[1]` |
| `client_reporting::CrpDisclosureCostTotal` | class | AGGREGATE on a view: costs by category |
| `client_reporting::CrpClientPeriodTotal` | class | AGGREGATE on a view: a client's whole period pack, mandate and type dropped; derived `pagesPerStatement(): Float[1]` |
| `client_reporting::CrpDeliveryOutcomeTotal` | class | AGGREGATE on a view: a delivery's attempts; derived `neededRetry()` |

30 classes.

## Exports — store and mapping

| element | kind | note |
| --- | --- | --- |
| `client_reporting::Store` | store | `include`s all three dependency stores; 26 tables `CRP_*`, 4 views, 41 joins `Crp_*`, 5 filters `Crp*` |
| `client_reporting::Mapping` | mapping | `include`s all three dependency mappings; 30 class sets `crp*`, 41 association mappings |

## Exports — associations

41 of them. 29 are internal; **12 cross a project boundary and they reach all three
dependencies**.

### Into client-core

| association | ends |
| --- | --- |
| `CrpStatementClient` | `CliClient[0..1]` `client` ↔ `CrpStatement[*]` `clientStatements` |
| `CrpStatementMandate` | `CliMandate[0..1]` `mandate` ↔ `CrpStatement[*]` `mandateStatements` |
| `CrpRecipientContact` | `CliClientContact[0..1]` `contact` ↔ `CrpStatementRecipient[*]` `recipientRows` |
| `CrpProfileClient` | `CliClient[0..1]` `profiledClient` ↔ `CrpReportingProfile[0..1]` `reportingProfile` |
| `CrpSuppressionClient` | `CliClient[0..1]` `suppressedClient` ↔ `CrpSuppression[*]` `suppressions` |
| `CrpCheckMandate` | `CliMandate[0..1]` `checkedMandate` ↔ `CrpDepreciationCheck[*]` `depreciationChecks` |
| `CrpPeriodTotalClient` | `CliClient[0..1]` `totalClient` ↔ `CrpClientPeriodTotal[*]` `periodTotals` — **from a VIEW** |

### Into position-keeping

| association | ends |
| --- | --- |
| `CrpLinePosition` | `Position[0..1]` `position` ↔ `CrpValuationLine[*]` `valuationLines` — 4-column join |
| `CrpTransactionMovement` | `PositionMovement[0..1]` `movement` ↔ `CrpTransactionLine[*]` `transactionLines` — 5-column join |
| `CrpStatementAccountDayTotal` | `AccountDayTotal[0..1]` `accountDayTotal` ↔ `CrpValuationStatement[*]` `valuationStatements` — **into the dependency's AGGREGATE** |

### Into valuation-core

| association | ends |
| --- | --- |
| `CrpLineValuation` | `VcValuation[0..1]` `valuation` ↔ `CrpValuationLine[*]` `reportedLines` — 3-column join |
| `CrpStatementValuationRun` | `VcValuationRun[0..1]` `valuationRun` ↔ `CrpValuationStatement[*]` `statementsProduced` |

### Internal

`CrpRunStatements` (`run`/`statements`), `CrpStatementTemplate` (`template`/
`renderedStatements`), `CrpStatementValuation` (`statement`/`valuationStatement`),
`CrpStatementTransaction` (`transactionOfStatement`/`transactionStatement`),
`CrpValuationStatementLines` (`valuationStatement`/`lines`), `CrpTransactionStatementLines`
(`transactionStatement`/`transactionLines`), `CrpTransactionCashFlows` (`cashStatement`/
`cashFlows`), `CrpStatementDisclosures` (`disclosureStatement`/`costsDisclosures`),
`CrpDisclosureCostLines` (`disclosure`/`costLines`), `CrpDisclosureIllustrations`
(`illustratedDisclosure`/`illustrations`), `CrpStatementPerformance` (`performanceStatement`/
`performanceReport`), `CrpPerformancePeriods` (`report`/`periods`), `CrpPerformanceBenchmark`
(`benchmark`/`performanceReports`), `CrpBenchmarkComponents` (`componentBenchmark`/
`components`), `CrpPerformanceAttribution` (`attributedReport`/`attributionReports`),
`CrpAttributionLines` (`attributionReport`/`attributionLines`), `CrpCheckNotices`
(`depreciationCheck`/`notices`), `CrpNoticeStatement` (`noticeStatement`/`statementNotices`),
`CrpStatementDeliveries` (`deliveredStatement`/`deliveries`), `CrpNoticeDeliveries` (`notice`/
`noticeDeliveries`), `CrpDeliveryChannelUse` (`channel`/`channelDeliveries`),
`CrpProfileChannel` (`preferredChannel`/`profiles`), `CrpDeliveryAttempts` (`delivery`/
`attempts`), `CrpDeliveryAcknowledgement` (`acknowledgedDelivery`/`acknowledgement`),
`CrpStatementRecipients` (`recipientStatement`/`recipients`), `CrpStatementLineTotals`
(`totalValuationStatement`/`lineTotal`), `CrpDisclosureCostTotals` (`totalDisclosure`/
`costTotals`), `CrpClientPeriodTotals` (`clientPeriodTotal`/`statementsInPeriod`),
`CrpDeliveryOutcomeTotals` (`totalDelivery`/`outcomeTotal`).

## Set ids (a GLOBAL namespace — reference these, do not guess)

`crpStatementRun`, `crpReportingProfile`, `crpStatement`, `crpDocumentTemplate`,
`crpSuppression`, `crpValuationStatement`, `crpValuationLine`, `crpTransactionStatement`,
`crpTransactionLine`, `crpCashFlowLine`, `crpCostsDisclosure`, `crpCostLine`,
`crpCostIllustration`, `crpBenchmark`, `crpBenchmarkComponent`, `crpPerformanceReport`,
`crpPerformancePeriod`, `crpAttributionReport`, `crpAttributionLine`, `crpDepreciationCheck`,
`crpDepreciationNotice`, `crpDeliveryChannel`, `crpDelivery`, `crpDeliveryAttempt`,
`crpAcknowledgement`, `crpStatementRecipient`, `crpStatementLineTotal`,
`crpDisclosureCostTotal`, `crpClientPeriodTotal`, `crpDeliveryOutcomeTotal`.

**None is marked root**, and every id is named explicitly, so the default ids
(`client_reporting_CrpStatement` and the rest) **do not exist**. A downstream `extends [...]`
or cross-project `AssociationMapping` must name the id above.

## Properties a downstream project navigates without declaring a join

| on class | property | reaches |
| --- | --- | --- |
| `CrpStatement` | `clientName` | `CLI_CLIENT.CLIENT_NAME`, 1 hop |
| `CrpStatement` | `mandateBenchmarkCode`, `mandateBaseCurrencyCode` | `CLI_MANDATE`, 1 hop |
| `CrpStatement` | `clientMacroRegionName` | `CG_MACRO_REGION.NAME`, **4 hops across two projects** |
| `CrpValuationStatement` | `accountTotalMarketValue`, `accountPositionCount` | `PK_ACCOUNT_DAY_TOTAL`, a VIEW in position-keeping |
| `CrpValuationStatement` | `valuationRunReportingCurrency`, `valuationRunStatus` | `VC_VALUATION_RUN` |
| `CrpValuationLine` | `positionClosingQuantity`, `positionMarketValue` | `PK_POSITION`, 4-column join |
| `CrpValuationLine` | `valuationDirtyPrice`, `valuationFxRate` | `VC_VALUATION`, 3-column join |
| `CrpTransactionLine` | `movementQuantityDelta`, `movementSourceSystem` | `PK_POSITION_MOVEMENT`, 5-column join |
| `CrpStatementRecipient` | `contactFullName`, `contactIsAuthorisedSignatory` | `CLI_CLIENT_CONTACT` |

The association ends, for callers that want the objects:

    $statement.client.domicileCountry.subRegion.macroRegion.code
    $statement.mandate.restrictions->filter(r | $r.isHardLimit)
    $statement.valuationStatement.lines.position.instrument
    $statement.valuationStatement.lines.valuation.valueInReporting()
    $statement.valuationStatement.accountDayTotal.totalPnl()
    $statement.deliveries->filter(d | $d.wasDelivered()).acknowledgement.method
    $notice.noticeDeliveries.attempts->filter(a | $a.succeeded())

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `CRP_STATEMENT_RUN` | `RUN_ID` | |
| `CRP_REPORTING_PROFILE` | `CLIENT_ID` | FK into client-core; one current arrangement per client |
| `CRP_STATEMENT` | `STATEMENT_ID` | FKs `RUN_ID`, `CLIENT_ID`, `MANDATE_ID`, `TEMPLATE_CODE` |
| `CRP_DOCUMENT_TEMPLATE` | `TEMPLATE_CODE` | |
| `CRP_SUPPRESSION` | `SUPPRESSION_ID` | FK `CLIENT_ID` |
| `CRP_VALUATION_STATEMENT` | `STATEMENT_ID` | FKs `INSTITUTION_ID` + `ACCOUNT_NO` + `COB_DATE`, `VALUATION_RUN_ID` |
| `CRP_VALUATION_LINE` | `STATEMENT_ID, LINE_SEQ` | carries the whole position key AND the valuation run id |
| `CRP_TRANSACTION_STATEMENT` | `STATEMENT_ID` | |
| `CRP_TRANSACTION_LINE` | `STATEMENT_ID, LINE_SEQ` | carries the 5-column movement key |
| `CRP_CASH_FLOW_LINE` | `STATEMENT_ID, LINE_SEQ` | |
| `CRP_COSTS_DISCLOSURE` | `DISCLOSURE_ID` | `BASIS` is `EX_ANTE` or `EX_POST` |
| `CRP_COST_LINE` | `DISCLOSURE_ID, LINE_SEQ` | |
| `CRP_COST_ILLUSTRATION` | `DISCLOSURE_ID, YEAR_NO` | |
| `CRP_BENCHMARK` | `BENCHMARK_CODE` | |
| `CRP_BENCHMARK_COMPONENT` | `BENCHMARK_CODE, COMPONENT_SEQ` | |
| `CRP_PERFORMANCE_REPORT` | `REPORT_ID` | FKs `STATEMENT_ID`, `MANDATE_ID`, `BENCHMARK_CODE` |
| `CRP_PERFORMANCE_PERIOD` | `REPORT_ID, PERIOD_CODE` | |
| `CRP_ATTRIBUTION_REPORT` | `ATTRIBUTION_ID` | FK `REPORT_ID` |
| `CRP_ATTRIBUTION_LINE` | `ATTRIBUTION_ID, SEGMENT_CODE` | |
| `CRP_DEPRECIATION_CHECK` | `CHECK_ID` | FKs `CLIENT_ID`, `MANDATE_ID`, and the 2-column account key |
| `CRP_DEPRECIATION_NOTICE` | `NOTICE_ID` | `DUE_BY_DATE` is the obligation, in one column |
| `CRP_DELIVERY_CHANNEL` | `CHANNEL_CODE` | |
| `CRP_DELIVERY` | `DELIVERY_ID` | `STATEMENT_ID` and `NOTICE_ID` both nullable, exactly one set |
| `CRP_DELIVERY_ATTEMPT` | `DELIVERY_ID, ATTEMPT_SEQ` | `ATTEMPT_DATE` alongside `ATTEMPTED_AT` so the view groups on a DATE |
| `CRP_ACKNOWLEDGEMENT` | `DELIVERY_ID` | at most one per dispatch |
| `CRP_STATEMENT_RECIPIENT` | `STATEMENT_ID, CONTACT_ID` | `CONTACT_ID` is client-core's |

Money and values are `DOUBLE`, counts are `INTEGER`, points in time are `TIMESTAMP` and dates
are `DATE`. `REAL` is not used anywhere: it parses and then cannot be read back at execution
(docs/UPSTREAM_FINDINGS.md F53).

Filters, declared and unapplied, all of them **null tests** because a `Filter` will not take a
boolean literal: `CrpIssuedStatements` (`WITHDRAWN_ON is null`), `CrpPendingDeliveries`
(`DELIVERED_AT is null`), `CrpUnsentNotices` (`SENT_AT is null`), `CrpCurrentTemplates`,
`CrpActiveSuppressions`.

## Notes for downstream

- `client_reporting::Store` includes all three dependency stores, which between them already
  carry `CP_*`, `CG_*`, `CA_*`, `CI_*`, `CPR_*` and `CFX_*`. A downstream store that includes
  this one gets every one of them and must not include any of them a second time.
- `client_reporting::Mapping` includes all three dependency mappings for the same reason.
- Extending one of these sets, or naming one across a project boundary, needs the explicit set
  id above.
- The ten-percent obligation is modelled as a pair: `CrpDepreciationCheck` is every evaluation
  and `CrpDepreciationNotice` is the ones that produced a notice. A check that found nothing
  is the evidence that the obligation was met, so do not filter it away.
- `CrpDepreciationNotice.dueByDate` is stored rather than derived. This project does not depend
  on core-calendar and could not compute the next business day; more importantly, the deadline
  that applied must not move when a calendar is later corrected.
- Tables and views are declared and unseeded. No `###Data` element, no Runtime.

## Verified

    python3 scripts/projects/check.py client-reporting
    compiles  client-reporting (+9 deps)   parse 332ms  compile 1599ms
