# settlement-core

Layer 1. Depends on **core-party** and **core-calendar** and on nothing else. Package root
`settlement_core::`, prefixes `SC_` (tables), `Sc_` (joins), `Sc` (filters), `sc` (set ids),
`Sc` (class and enum names).

Settlement instructions: what settles, where, by which delivery method, against whose SSI,
and on what date. The shape to know before using it: **an instruction carries a market MIC,
never a calendar id.** The calendar is two joins away (`SC_SETTLEMENT_INSTRUCTION` →
`CC_MARKET` → `CC_CALENDAR`) and is exposed as join-chain properties on the instruction, so
re-pointing a market at a different calendar re-dates nothing and stales nothing.

## Exports

| element | kind | note |
| --- | --- | --- |
| settlement_core::ScSettlementInstruction | class | the instruction; carries the four join-chain properties below |
| settlement_core::ScSettlementLeg | class | one side of an instruction — DVP has two, FOP has one |
| settlement_core::ScPlaceOfSettlement | class | the CSD/ICSD/agent, keyed by BIC; `operator` is a `core_party::LegalEntity`, `calendar` a `core_calendar::CcCalendar` |
| settlement_core::ScMarketSettlementProfile | class | per (market, asset class): default venue and delivery method; `market` is a `core_calendar::CcMarket` |
| settlement_core::ScCounterparty | class | the settlement-side counterparty; legal identity is `entity`, a `core_party::LegalEntity` |
| settlement_core::ScCustodian | class | agent bank at a venue; `entity` is a `core_party::LegalEntity` |
| settlement_core::ScStandingInstruction | class | the SSI, dated `effectiveFrom`/`effectiveTo`; `isOpenEnded()`, `isUsable()` |
| settlement_core::ScSettlementAccount | class | the account number an SSI points at — securities or cash |
| settlement_core::ScSettlementEvent | class | dated audit trail of status transitions |
| settlement_core::ScFailReason | class | CSD fail codes; a table, not an enum — venues extend their code lists |
| settlement_core::ScCutoff | class | time of day after which a venue drops an instruction to the next value date |
| settlement_core::ScSettlementBatch | class | the venue's release run; fails cluster by batch |
| settlement_core::ScDeliveryMethod | enum | DVP, RVP, FOP, DWP, PVP |
| settlement_core::ScInstructionStatus | enum | NEW, VALIDATED, MATCHED, SETTLED, FAILED, CANCELLED |
| settlement_core::ScSettlementDirection | enum | DELIVER, RECEIVE — per leg, not per instruction |
| settlement_core::ScSsiStatus | enum | ACTIVE, PENDING, SUSPENDED, EXPIRED |
| settlement_core::Store | store | 12 `SC_*` tables, 23 `Sc_*` joins, filters `ScUnsettled`, `ScOpenSsi`; includes `core_party::Store` and `core_calendar::Store` |
| settlement_core::Mapping | mapping | 12 class sets `sc*`, 4 enumeration mappings; includes `core_party::Mapping` and `core_calendar::Mapping` |

## Store detail

| name | kind | note |
| --- | --- | --- |
| SC_PLACE_OF_SETTLEMENT | table | PK PLACE_BIC; PLACE_NAME, PLACE_KIND, COUNTRY_CODE, OPERATOR_ENTITY_ID→CP, CALENDAR_ID→CC, IS_ACTIVE |
| SC_MARKET_PROFILE | table | PK PROFILE_ID; MARKET_MIC→CC, ASSET_CLASS, DEFAULT_PLACE_BIC, DEFAULT_DELIVERY_METHOD, ALLOWS_PARTIAL |
| SC_COUNTERPARTY | table | PK COUNTERPARTY_ID; ENTITY_ID→CP, SETTLEMENT_BIC, IS_SELF_CLEARING, ONBOARDED_DATE |
| SC_CUSTODIAN | table | PK CUSTODIAN_ID; CUSTODIAN_NAME, ENTITY_ID→CP, PLACE_BIC, PARTICIPANT_CODE |
| SC_STANDING_INSTRUCTION | table | PK SSI_ID; COUNTERPARTY_ID, CURRENCY_CODE, PLACE_BIC, CUSTODIAN_ID, SSI_STATUS, EFFECTIVE_FROM, EFFECTIVE_TO |
| SC_SETTLEMENT_ACCOUNT | table | PK ACCOUNT_ID; SSI_ID, PLACE_BIC, ACCOUNT_NUMBER, ACCOUNT_TYPE, CURRENCY_CODE |
| SC_SETTLEMENT_INSTRUCTION | table | PK INSTRUCTION_ID; TRADE_REF, COUNTERPARTY_ID, SSI_ID, PLACE_BIC, MARKET_MIC→CC, BATCH_ID, ASSET_CLASS, DELIVERY_METHOD, INSTRUCTION_STATUS, TRADE_DATE, INTENDED_SETTLEMENT_DATE, ACTUAL_SETTLEMENT_DATE, SETTLEMENT_CURRENCY, SETTLEMENT_AMOUNT (DOUBLE), IS_ON_HOLD |
| SC_SETTLEMENT_LEG | table | PK LEG_ID; INSTRUCTION_ID, ACCOUNT_ID, DIRECTION, LEG_KIND, QUANTITY, AMOUNT, CURRENCY_CODE |
| SC_SETTLEMENT_EVENT | table | PK EVENT_ID; INSTRUCTION_ID, EVENT_TYPE, RESULTING_STATUS, EVENT_DATE, EVENT_TIME, FAIL_REASON_CODE |
| SC_FAIL_REASON | table | PK REASON_CODE; REASON_NAME, REASON_CATEGORY, IS_COUNTERPARTY_FAULT |
| SC_CUTOFF | table | PK CUTOFF_ID; PLACE_BIC, CURRENCY_CODE, INSTRUCTION_KIND, CUTOFF_TIME, OFFSET_DAYS |
| SC_SETTLEMENT_BATCH | table | PK BATCH_ID; BATCH_NAME, PLACE_BIC, BATCH_DATE, RELEASE_TIME, IS_RELEASED |
| ScUnsettled | filter | `SC_SETTLEMENT_INSTRUCTION.ACTUAL_SETTLEMENT_DATE is null` |
| ScOpenSsi | filter | `SC_STANDING_INSTRUCTION.EFFECTIVE_TO is null` |

## Joins

Internal: `Sc_InstructionCounterparty`, `Sc_InstructionSsi`, `Sc_InstructionPlace`,
`Sc_InstructionLeg`, `Sc_InstructionEvent`, `Sc_InstructionBatch`, `Sc_LegAccount`,
`Sc_SsiCounterparty`, `Sc_SsiPlace`, `Sc_SsiCustodian`, `Sc_SsiAccount`, `Sc_AccountPlace`,
`Sc_CustodianPlace`, `Sc_PlaceCutoff`, `Sc_ProfilePlace`, `Sc_BatchPlace`,
`Sc_EventFailReason`.

Crossing into a dependency's store: `Sc_InstructionMarket` and `Sc_ProfileMarket`
(→ `CC_MARKET`), `Sc_PlaceCalendar` (→ `CC_CALENDAR`), `Sc_CounterpartyEntity`,
`Sc_CustodianEntity`, `Sc_PlaceOperator` (→ `CP_LEGAL_ENTITY`).

## Set ids

`scSettlementInstruction`, `scSettlementLeg`, `scPlaceOfSettlement`, `scMarketProfile`,
`scCounterparty`, `scCustodian`, `scStandingInstruction`, `scSettlementAccount`,
`scSettlementEvent`, `scFailReason`, `scCutoff`, `scSettlementBatch`

## Enumeration mappings

`ScDeliveryMethodMapping`, `ScInstructionStatusMapping`, `ScSettlementDirectionMapping`,
`ScSsiStatusMapping` — each maps two spellings per value (ISO 15022 mnemonic and word),
so `DVP: ['DVP', 'APMT']`. These names are global; use them by name if you re-map one of
these enums against your own column.

## The join chain

Four properties on `ScSettlementInstruction` are mapped through **two joins** ending in a
dependency's column:

    settlementCalendarId:  [settlement_core::Store]@Sc_InstructionMarket > @Cc_MarketCalendar | [core_calendar::Store]CC_CALENDAR.CALENDAR_ID
    settlementCentre:      ... | [core_calendar::Store]CC_CALENDAR.FINANCIAL_CENTRE
    settlementWeekendDays: ... | [core_calendar::Store]CC_CALENDAR.WEEKEND_DAYS
    counterpartyLegalName: [settlement_core::Store]@Sc_InstructionCounterparty > @Sc_CounterpartyEntity | [core_party::Store]CP_LEGAL_ENTITY.LEGAL_NAME

The store qualifier is required on the first hop and optional afterwards; the pipe before
the final column is not optional at all.

## Notes for downstream

- Every set id above is explicit, so the default ids (`settlement_core_ScSettlementInstruction`
  and friends) **do not exist**. An `extends [...]` or a cross-project `AssociationMapping`
  must name the `sc*` id.
- Same applies to the dependencies as seen from here: property mappings to
  `core_party::LegalEntity` name `[cpLegalEntity]`, to `core_calendar::CcCalendar`
  `[ccCalendar]`, to `core_calendar::CcMarket` `[ccMarket]`.
- Including `settlement_core::Store` transitively brings in `CP_*` and `CC_*`; do not
  `include core_calendar::Store` a second time alongside it.
- `intendedSettlementDate` is a stored column, not derived. The T+n that produced it is
  `core_calendar::CcSettlementCycle` reached via `instruction.market.settlementCycles`, and
  the holiday count via `instruction.market.calendar.holidayCounts` (the View — a
  calendar-year with no holidays forms no group and is simply absent).
- Derived properties available: `ScSettlementInstruction.isSettled()`, `.isFailing()`,
  `.settledLate()`; `ScStandingInstruction.isOpenEnded()`, `.isUsable()`.
- No `###Data` element and no Runtime: the tables are declared and unseeded.
