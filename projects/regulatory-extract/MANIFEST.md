# regulatory-extract

Layer 2. Depends on **trade-capture**, **reference-data** and **client-core**, and on nothing
else. Package root `regulatory_extract::`; prefixes `REX_` (tables), `Rex_` (joins), `Rex`
(filters), `rex` (set ids).

31 classes, 27 tables in a schema of their own, 36 joins, 8 filters, 31 mapping sets. No
enums, no profiles, no functions, no associations — this project exports **classes, a store
and a mapping**.

**The shape to know before using it.** Everything this project owns lives in the **`rex`
SCHEMA**, so every reference — here, and in any downstream project — is
`[regulatory_extract::Store]rex.REX_TRANSACTION.COLUMN`. The three dependencies put their
tables in the default schema, so theirs stay `[trade_capture::Store]TC_TRADE.CURRENCY`. A
downstream mapping that forgets the `rex.` segment fails with an error naming the column.

**The widest fan-in at layer 2**, and the thing to copy from it: the store includes three
stores and the mapping includes three mappings, and behind them core-instrument arrives twice
(trade-capture *and* reference-data), core-party twice (trade-capture *and* client-core) and
core-geo twice (reference-data *and* client-core). **A diamond include is fine** — it
resolves to one database and one mapping, nothing is duplicated, and it costs about 1.7 s to
compile the closure. What is not fine is adding `include core_instrument::Store` on top:
that is a redundant include *and* a reference to an undeclared dependency. Include your
DIRECT dependencies only. No `core_` package name appears anywhere in these three files.

**The two names a downstream project needs:** `regulatory_extract::RexTransactionReport` and
its root set id **`[rexTransaction]`**. Everything else hangs off one of them.

## The construct: four `~filter` subtypes over one table

`[rexTransaction]` is the ROOT set (`*`) over `rex.REX_TRANSACTION`, so
`RexTransactionReport.all()` is the whole extract. Four sets `extends [rexTransaction]`,
each adding one `~filter` declared in this project's store and only the columns that mean
something once the row is known to be of that type. None restates the key, the main table or
any of the fifty inherited property mappings.

| set | class | filter | predicate |
| --- | --- | --- | --- |
| `rexMifir` | `RexMifirTransactionReport` | `RexMifirRows` | `REGIME_CODE = 'MIFIR'` |
| `rexEmir` | `RexEmirTradeReport` | `RexEmirRows` | `REGIME_CODE = 'EMIR'` |
| `rexRejected` | `RexRejectedTransactionReport` | `RexRejectedRows` | `REPORT_STATUS = 'REJECTED'` |
| `rexUnsubmitted` | `RexUnsubmittedTransactionReport` | `RexUnsubmittedRows` | `SUBMITTED_TIME is null` |

The last two **overlap** the first two deliberately: a rejected MiFIR report is a row of both
`rexMifir` and `rexRejected`, because regime is an identity and rejection is a state. A
hierarchy that forced them disjoint could not express "the MiFIR backlog".

No filter uses a boolean literal — `Filter X(T.IS_CLEARED = true)` fails to parse — so each
is a string comparison or a null test.

## Exports

| element | kind | note |
| --- | --- | --- |
| `regulatory_extract::RexTransactionReport` | class | the report LINE, not the trade; root set, 4 constraints, `isSubmitted`/`isAccepted`/`isLate` |
| `regulatory_extract::RexMifirTransactionReport` | class | `extends` the base; MiFIR RTS 22 view — transmission fields 25–27 |
| `regulatory_extract::RexEmirTradeReport` | class | EMIR view — UTI, counterparty LEI, clearing, collateralisation, valuation |
| `regulatory_extract::RexRejectedTransactionReport` | class | the rows the authority refused; NCA code and resubmission count |
| `regulatory_extract::RexUnsubmittedTransactionReport` | class | the backlog: built, not sent. Held reason and holder |
| `regulatory_extract::RexReportingRegime` | class | MiFIR, EMIR, SFTR as a row; `fieldCount` is 65 for RTS 22 |
| `regulatory_extract::RexReportingObligation` | class | why a population of trades is in scope, and who reports — us, them, or a delegate |
| `regulatory_extract::RexReportField` | class | one numbered field of one regime's schema, AS DATA. Composite PK (regime, number) |
| `regulatory_extract::RexFieldValue` | class | what was actually SENT for that field, after formatting. Composite PK (report, regime, number) |
| `regulatory_extract::RexTradingCapacity` | class | MiFIR 29: DEAL, MTCH, AOTC — decides what else the report must carry |
| `regulatory_extract::RexDecisionMaker` | class | MiFIR 57/59: a person, an algorithm, or nobody. Reaches `trade_capture::Trader` |
| `regulatory_extract::RexAlgorithm` | class | a trading algorithm as registered with the NCA; versioned and withdrawable |
| `regulatory_extract::RexNaturalPersonIdentifier` | class | NIDN/CCPT/CONCAT with a member-state priority rank. Composite PK (maker, scheme) |
| `regulatory_extract::RexReportPersonRole` | class | who decided and who executed, one row each. Composite PK (report, role) |
| `regulatory_extract::RexWaiver` | class | MiFIR 61 reference: RFPT, NLIQ, OILQ, SIZE |
| `regulatory_extract::RexReportWaiver` | class | one waiver claimed on one report — field 61 is repeatable. Composite PK |
| `regulatory_extract::RexShortSaleIndicator` | class | MiFIR 62: SESH, SELL, SSEX, UNDI. UNDI is a legal answer, not a gap |
| `regulatory_extract::RexPostTradeFlag` | class | MiFIR 63: BENC, ACTX, LRGS, ILQD…; `grantsDeferral` separates label from legal effect |
| `regulatory_extract::RexExtractRun` | class | one execution of the extract job; trades read vs produced vs suppressed |
| `regulatory_extract::RexReportDestination` | class | ARM, NCA or trade repository; its own schema version and cut-off |
| `regulatory_extract::RexSubmissionBatch` | class | one file sent; accepted or rejected as a whole before any report in it is |
| `regulatory_extract::RexSubmissionEntry` | class | a report's place in a batch. Composite PK (batch, report) — resubmission is many-to-many |
| `regulatory_extract::RexValidationRule` | class | OUR pre-send rule, as data; `severity` and `blocksSubmission` |
| `regulatory_extract::RexValidationError` | class | one failure of one rule; `resolvedTime is null` is the open queue |
| `regulatory_extract::RexRejectionFeedback` | class | the authority's own reply, in their vocabulary. Deliberately not merged with the above |
| `regulatory_extract::RexRemediationAction` | class | what we did about it; `rootCauseDomain` names the upstream project three times in four |
| `regulatory_extract::RexReconciliationBreak` | class | one field our EMIR report and the counterparty's disagree on; keys on the shared UTI |
| `regulatory_extract::RexReportingEntity` | class | the firm entity in field 4, held as an LEI value — core-party is not a dependency |
| `regulatory_extract::RexClientReportingProfile` | class | the bridge to client-core: LEI status, delegation, suppression |
| `regulatory_extract::RexVenueReportingProfile` | class | the bridge to reference-data: is this MIC reportable, or is it 'XOFF' |
| `regulatory_extract::RexTradeEligibility` | class | the bridge to trade-capture: why there IS or IS NOT a report. Composite PK (trade, regime) |
| `regulatory_extract::Store` | store | 27 `REX_` tables in schema `rex`, 36 `Rex_` joins, 8 `Rex` filters; `include`s all three dependency stores |
| `regulatory_extract::Mapping` | mapping | 31 sets, 4 of them `extends [rexTransaction]` with a `~filter`; `include`s all three dependency mappings |

## The cross-project references (the reason this project exists)

Six class-valued property mappings and nineteen chained ones leave this project. Each names
the dependency's **explicit** set id — the defaults (`trade_capture_Trade`) do not exist in
any of the three.

| on class | property | type | target set |
| --- | --- | --- | --- |
| `RexTransactionReport` | `trade` | `trade_capture::Trade[0..1]` | `[tcTrade]` |
| `RexTransactionReport` | `executionVenue` | `reference_data::RdListingVenue[0..1]` | `[rdVenue]` |
| `RexDecisionMaker` | `trader` | `trade_capture::Trader[0..1]` | `[tcTrader]` |
| `RexClientReportingProfile` | `client` | `client_core::CliClient[0..1]` | `[cliClient]` |
| `RexVenueReportingProfile` | `venue` | `reference_data::RdListingVenue[0..1]` | `[rdVenue]` |
| `RexTradeEligibility` | `trade` | `trade_capture::Trade[0..1]` | `[tcTrade]` |

Flattened chain properties, for a report that groups without navigating:

| on class | property | reaches |
| --- | --- | --- |
| `RexTransactionReport` | `tradeCurrency`, `tradeExecutionTime`, `tradeInstrumentId` | `TC_TRADE`, 1 hop |
| `RexTransactionReport` | `venueName`, `venueCountryCode` | `RD_VENUE`, 1 hop |
| `RexTransactionReport` | `clientName`, `clientType` | `CLI_CLIENT`, **2 hops** through this project's own profile row |
| `RexDecisionMaker` | `traderFullNameSource`, `traderDeskName` | `TC_TRADER`, 1 hop |
| `RexClientReportingProfile` | `clientRelationshipName/Type`, `clientDomicileCountryCode`, `clientStatus` | `CLI_CLIENT`, 1 hop |
| `RexVenueReportingProfile` | `venueRegisteredName`, `venueHomeCountryCode`, `venueRetiredDate` | `RD_VENUE`, 1 hop |
| `RexTradeEligibility` | `tradeStatusCode`, `tradeDateSource`, `tradeNotionalSource` | `TC_TRADE`, 1 hop |

**There is no join to `CI_INSTRUMENT` and none to `CP_LEGAL_ENTITY`,** although both stores
are present transitively. An instrument is `$report.trade.instrument`, which is
trade-capture's own association and returns a typed `Bond` or `CallOption`; a legal person is
`$report.clientProfile.client.legalEntity`, which is client-core's. Going through the
dependency's model rather than round it is what keeps this project's declared closure honest.

## Set ids (a GLOBAL namespace; extend or name these, the defaults do not exist)

`rexTransaction` (root), `rexMifir`, `rexEmir`, `rexRejected`, `rexUnsubmitted`, `rexRegime`,
`rexObligation`, `rexField`, `rexFieldValue`, `rexTradingCapacity`, `rexDecisionMaker`,
`rexAlgorithm`, `rexPersonIdentifier`, `rexReportPerson`, `rexWaiver`, `rexReportWaiver`,
`rexShortSale`, `rexPostTradeFlag`, `rexExtractRun`, `rexDestination`, `rexBatch`,
`rexSubmission`, `rexValidationRule`, `rexValidationError`, `rexRejection`, `rexRemediation`,
`rexReconBreak`, `rexReportingEntity`, `rexClientProfile`, `rexVenueProfile`,
`rexEligibility`.

## Store surface — all inside `Schema rex`

| table | primary key | note |
| --- | --- | --- |
| `REX_TRANSACTION` | `REPORT_ID` | the one table the four subtypes share: base block, MiFIR block, EMIR block, rejection block, held block. FKs `TRADE_ID`, `CLIENT_ID`, `REPORTING_ENTITY_ID`, `EXTRACT_RUN_ID`, none mapped to a property |
| `REX_REGIME` | `REGIME_CODE` | `FIELD_COUNT` is 65 for MiFIR |
| `REX_OBLIGATION` | `OBLIGATION_ID` | |
| `REX_FIELD` | `REGIME_CODE`, `FIELD_NUMBER` | there is no global field 57 |
| `REX_FIELD_VALUE` | `REPORT_ID`, `REGIME_CODE`, `FIELD_NUMBER` | three key columns |
| `REX_TRADING_CAPACITY` | `CAPACITY_CODE` | |
| `REX_DECISION_MAKER` | `DECISION_MAKER_ID` | FK `ALGORITHM_ID`, and `TRADER_ID` out to `TC_TRADER` |
| `REX_ALGORITHM` | `ALGORITHM_ID` | |
| `REX_PERSON_IDENTIFIER` | `DECISION_MAKER_ID`, `SCHEME_CODE` | dual nationality is two rows |
| `REX_REPORT_PERSON` | `REPORT_ID`, `ROLE_CODE` | |
| `REX_WAIVER` | `WAIVER_CODE` | |
| `REX_REPORT_WAIVER` | `REPORT_ID`, `WAIVER_CODE` | field 61 is repeatable |
| `REX_SHORT_SALE_INDICATOR` | `INDICATOR_CODE` | |
| `REX_POST_TRADE_FLAG` | `REPORT_ID`, `FLAG_CODE` | |
| `REX_EXTRACT_RUN` | `RUN_ID` | |
| `REX_DESTINATION` | `DESTINATION_CODE` | cut-off is text, in the destination's own timezone |
| `REX_BATCH` | `BATCH_ID` | FKs `RUN_ID`, `DESTINATION_CODE` |
| `REX_SUBMISSION` | `BATCH_ID`, `REPORT_ID` | resubmission makes this many-to-many |
| `REX_VALIDATION_RULE` | `RULE_ID` | |
| `REX_VALIDATION_ERROR` | `ERROR_ID` | |
| `REX_REJECTION` | `FEEDBACK_ID` | |
| `REX_REMEDIATION` | `ACTION_ID` | |
| `REX_RECON_BREAK` | `BREAK_ID` | pairs on `UNIQUE_TRADE_IDENTIFIER`, not on our report id |
| `REX_REPORTING_ENTITY` | `REPORTING_ENTITY_ID` | LEI held as a value; core-party is not a dependency |
| `REX_CLIENT_PROFILE` | `PROFILE_ID` | `CLIENT_ID` is client-core's key and the only column of theirs kept |
| `REX_VENUE_PROFILE` | `PROFILE_ID` | `MIC` is reference-data's key for `RD_VENUE` |
| `REX_TRADE_ELIGIBILITY` | `TRADE_ID`, `REGIME_CODE` | assessed once per regime |

Joins that leave the project, legal only because of the three includes:
`Rex_ReportTrade`, `Rex_EligibilityTrade`, `Rex_MakerTrader` (trade-capture),
`Rex_ReportVenue`, `Rex_VenueProfileVenue` (reference-data),
`Rex_ClientProfileClient` (client-core).

Two of the internal joins are **two-column** and on one line:
`Rex_FieldValueField` (regime + field number, because a field number alone is ambiguous
across regimes) and `Rex_ReportEligibility` (trade + regime, because matching on the trade
alone would return every regime's assessment).

## Filters

Applied, as the four subtype discriminators: `RexMifirRows`, `RexEmirRows`,
`RexRejectedRows`, `RexUnsubmittedRows`.

Declared and unapplied, for a downstream mapping to reference:
`RexOpenValidationErrors` (`REX_VALIDATION_ERROR.RESOLVED_TIME is null`),
`RexUnresolvedBreaks` (`REX_RECON_BREAK.RESOLVED_DATE is null`),
`RexLiveObligations` (`REX_OBLIGATION.END_DATE is null`),
`RexInForceRegimes` (`REX_REGIME.RETIRED_ON is null`).

## Class constraints

`RexTransactionReport` (transaction reference present, executing entity LEI is 20 characters,
reported quantity positive, acceptance not before submission) and `RexTradeEligibility`
(a reportable trade must be dated). Every comparison is FULLY PARENTHESISED — Pure binds
`&&` tighter than the comparison operators, so an unparenthesised `$this.a > 0 && $this.b`
does not compile.

## Notes for downstream

- Include `regulatory_extract::Store` and you get `TC_*`, `RD_*`, `CLI_*` and, transitively,
  `CI_*`, `CP_*`, `CB_*` and `CG_*`. Do not include any of them a second time.
  `regulatory_extract::Mapping` carries the same closure.
- Reaching one of these tables means writing the schema: `rex.REX_TRANSACTION`, never
  `REX_TRANSACTION`.
- `rexRejected` and `rexUnsubmitted` are not disjoint from `rexMifir` and `rexEmir`. Counting
  reports by summing the four sets double-counts.
- `RexTransactionReport` holds the REPORTED economics; `trade` holds what was booked. When
  the two disagree that disagreement is the finding, so do not "fix" either from the other.
- An excluded trade is a `RexTradeEligibility` row with `isReportable = false` and a reason.
  A trade with NO eligibility row is the defect: the extract never looked at it.
- An unmatched EMIR report is a `RexReconciliationBreak` with `pairingStatus = 'NOT_PAIRED'`,
  which is a worse finding than a value mismatch, not a missing row.
- Tables are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py regulatory-extract   # compiles
