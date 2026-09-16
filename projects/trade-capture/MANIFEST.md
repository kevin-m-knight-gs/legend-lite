# trade-capture

Layer 1. Depends on **core-instrument**, **core-party**, **core-book** and nothing else.
Package root `trade_capture::`; prefixes `TC_` (tables), `Tc_` (joins), `Tc` (filters),
`tc` (set ids).

26 classes, 7 enums, 31 associations, 26 tables, 31 joins, 2 filters, 7 EnumerationMappings.

**The shape to know before using it.** This project owns no identity of its own for an
instrument, a party or a book. `trade_capture::Trade` has no `instrumentId` property, no
`bookId`, no `counterpartyEntityId` — the foreign keys are columns on `TC_TRADE` and are
reached from the model only through the six CROSS-PROJECT associations below. Navigate
`$trade.instrument`, `$trade.book`, `$trade.counterparty.legalEntity`; those resolve through
the far project's own mapping, so an instrument that comes back is already a `Bond` or a
`CallOption` and not a bare `Instrument`.

**The two names a downstream project needs:** the class `trade_capture::Trade` and its set
id **`[tcTrade]`**. Everything else in this project hangs off one of them.

## Cross-project associations (the reason this project exists)

| association | this side | far side (project, set id) |
| --- | --- | --- |
| trade_capture::TradeInstrument | `trades: Trade[*]` | `instrument: core_instrument::Instrument[0..1]` — `[ciBase]` |
| trade_capture::LegInstrument | `instrumentLegs: TradeLeg[*]` | `legInstrument: core_instrument::Instrument[0..1]` — `[ciBase]` |
| trade_capture::TradeBook | `bookedTrades: Trade[*]` | `book: core_book::Book[0..1]` — `[cbBook]` |
| trade_capture::CounterpartyEntity | `counterpartyRoles: Counterparty[*]` | `legalEntity: core_party::LegalEntity[0..1]` — `[cpLegalEntity]` |
| trade_capture::AllocationEntity | `entityAllocations: Allocation[*]` | `allocationEntity: core_party::LegalEntity[0..1]` — `[cpLegalEntity]` |
| trade_capture::ClearingBrokerEntity | `brokerRoles: ClearingBroker[*]` | `brokerEntity: core_party::LegalEntity[0..1]` — `[cpLegalEntity]` |

These six add properties to classes this project does not own. If you also associate with
`core_instrument::Instrument`, `core_party::LegalEntity` or `core_book::Book`, your end names
must avoid `trades`, `instrumentLegs`, `bookedTrades`, `counterpartyRoles`,
`entityAllocations` and `brokerRoles`.

## Exports

| element | kind | note |
| --- | --- | --- |
| trade_capture::Trade | class | the execution itself; 5 constraints; derived `grossConsideration`, `averagePrice`, `signedQuantity` |
| trade_capture::TradeLeg | class | one economic leg of a multi-leg trade; may be on its own instrument |
| trade_capture::BlockTrade | class | the parent fill several trades were struck against |
| trade_capture::Allocation | class | the split of a trade to one end client |
| trade_capture::AllocationInstruction | class | the client's standing split instruction, separate from what it produced |
| trade_capture::Confirmation | class | proof the counterparty agreed; the timestamp is the point |
| trade_capture::Amendment | class | one post-capture change, as a row, so history survives |
| trade_capture::Cancellation | class | withdrawal of a trade; reverses the position, unlike an amendment |
| trade_capture::TradeVersion | class | immutable snapshot of economics at one version number |
| trade_capture::TradeIdentifier | class | one of the refs the trade is known by outside this system |
| trade_capture::TradeCharge | class | one money line; `isIncludedInNet` decides whether it enters consideration |
| trade_capture::CommissionSchedule | class | the agreed rate card, held apart from the charge it generated |
| trade_capture::SettlementInstruction | class | where the cash and the securities go; up to two per trade |
| trade_capture::ExecutionVenue | class | the place of execution; the MIC is what a regulator recognises |
| trade_capture::Trader | class | the person who executed; derived `fullName` |
| trade_capture::Salesperson | class | the person who owns the client relationship; derived `fullName` |
| trade_capture::SalesCredit | class | revenue attribution; several rows per trade sum to 100 |
| trade_capture::Counterparty | class | the counterparty ROLE over a legal entity, not the entity |
| trade_capture::ClearingBroker | class | the broker a give-up goes to; a role, with clearing-specific static data |
| trade_capture::GiveUp | class | handover of a trade for clearing; can be rejected |
| trade_capture::TradeMatch | class | pairing with the counterparty's record; exists as soon as matching is attempted |
| trade_capture::TradeBreak | class | one field on which the two records disagree |
| trade_capture::TradeApproval | class | sign-off for a trade that broke a control |
| trade_capture::TradeNote | class | contemporaneous free text; often the only account of intent |
| trade_capture::CaptureSource | class | the upstream system the trade arrived from |
| trade_capture::TradeTag | class | deliberately untyped key/value label |
| trade_capture::TradeSide | enum | BUY, SELL, BUY_TO_COVER, SELL_SHORT |
| trade_capture::TradeStatus | enum | NEW, VERIFIED, CONFIRMED, ALLOCATED, SETTLED, CANCELLED, AMENDED |
| trade_capture::ConfirmationMethod | enum | ELECTRONIC, SWIFT, EMAIL, FAX, PAPER |
| trade_capture::AllocationStatus | enum | PENDING, PARTIAL, ALLOCATED, REJECTED |
| trade_capture::VenueType | enum | EXCHANGE, MTF, OTF, SYSTEMATIC_INTERNALISER, OTC, DARK_POOL |
| trade_capture::ChargeType | enum | COMMISSION, EXCHANGE_FEE, CLEARING_FEE, SETTLEMENT_FEE, STAMP_DUTY, TRANSACTION_TAX, RESEARCH |
| trade_capture::MatchStatus | enum | UNMATCHED, MATCHED, DISPUTED, RESOLVED |
| trade_capture::Store | store | 26 `TC_` tables; `include`s all three dependency stores |
| trade_capture::Mapping | mapping | 26 class sets, 31 association mappings, 7 EnumerationMappings; `include`s all three dependency mappings |

## Internal associations

`TradeTrader` (`trader`/`tradedTrades`), `TradeVenueLink` (`venue`/`venueTrades`),
`TradeCounterpartyLink` (`counterparty`/`counterpartyTrades`), `TradeLegs` (`legs`/`legTrade`),
`TradeBlockLink` (`block`/`blockTrades`), `TradeAllocations` (`allocations`/`allocationTrade`),
`InstructedAllocations` (`instruction`/`instructedAllocations`),
`TradeConfirmations` (`confirmations`/`confirmationTrade`),
`TradeAmendments` (`amendments`/`amendmentTrade`),
`TradeCancellationLink` (`cancellations`/`cancelledTrade`),
`TradeVersions` (`versions`/`versionTrade`),
`TradeIdentifiers` (`tradeIdentifiers`/`identifiedTrade`),
`TradeCharges` (`charges`/`chargeTrade`),
`CounterpartyCommission` (`commissionSchedule`/`scheduleCounterparties`),
`TradeSettlementInstructions` (`settlementInstructions`/`settlementTrade`),
`TradeGiveUps` (`giveUps`/`giveUpTrade`), `BrokerGiveUps` (`clearingBroker`/`brokerGiveUps`),
`TradeMatches` (`matches`/`matchTrade`), `MatchBreaks` (`breaks`/`breakMatch`),
`TradeApprovals` (`approvals`/`approvalTrade`), `TradeNotes` (`notes`/`noteTrade`),
`TradeCaptureSourceLink` (`captureSource`/`sourceTrades`), `TradeTags` (`tags`/`tagTrade`),
`TradeSalesCredits` (`salesCredits`/`creditTrade`),
`SalespersonCredits` (`salesperson`/`personCredits`).

## Set ids (a GLOBAL namespace; extend or name these, the defaults do not exist)

`tcTrade`, `tcTradeLeg`, `tcBlockTrade`, `tcAllocation`, `tcAllocationInstruction`,
`tcConfirmation`, `tcAmendment`, `tcCancellation`, `tcTradeVersion`, `tcTradeIdentifier`,
`tcTradeCharge`, `tcCommissionSchedule`, `tcSettlementInstruction`, `tcVenue`, `tcTrader`,
`tcSalesperson`, `tcSalesCredit`, `tcCounterparty`, `tcClearingBroker`, `tcGiveUp`,
`tcTradeMatch`, `tcTradeBreak`, `tcTradeApproval`, `tcTradeNote`, `tcCaptureSource`,
`tcTradeTag`.

Every set is declared with an explicit id, so `trade_capture_Trade` and its siblings do not
exist. A downstream `extends [...]` or cross-project `AssociationMapping` must name the `tc`
id above.

## Tables

`TC_TRADE`, `TC_TRADE_LEG`, `TC_BLOCK_TRADE`, `TC_ALLOCATION`, `TC_ALLOCATION_INSTRUCTION`,
`TC_CONFIRMATION`, `TC_AMENDMENT`, `TC_CANCELLATION`, `TC_TRADE_VERSION`, `TC_TRADE_XREF`,
`TC_TRADE_CHARGE`, `TC_COMMISSION_SCHEDULE`, `TC_SETTLEMENT_INSTRUCTION`, `TC_VENUE`,
`TC_TRADER`, `TC_SALESPERSON`, `TC_SALES_CREDIT`, `TC_COUNTERPARTY`, `TC_CLEARING_BROKER`,
`TC_GIVE_UP`, `TC_TRADE_MATCH`, `TC_TRADE_BREAK`, `TC_TRADE_APPROVAL`, `TC_TRADE_NOTE`,
`TC_CAPTURE_SOURCE`, `TC_TRADE_TAG`.

`TC_TRADE` carries the foreign keys `INSTRUMENT_ID` (to `CI_INSTRUMENT`), `BOOK_ID` (to
`CB_BOOK`), `COUNTERPARTY_ID`, `TRADER_ID`, `VENUE_ID`, `BLOCK_ID`, `SOURCE_ID`.
`TC_COUNTERPARTY`, `TC_CLEARING_BROKER` and `TC_ALLOCATION` each carry `ENTITY_ID` to
`CP_LEGAL_ENTITY`. None of those columns is mapped to a property.

## Joins available to downstream projects that include this store

Cross-boundary: `Tc_TradeInstrument`, `Tc_LegInstrument`, `Tc_TradeBook`,
`Tc_CounterpartyEntity`, `Tc_AllocationEntity`, `Tc_ClearingBrokerEntity`.

Internal: `Tc_TradeTrader`, `Tc_TradeVenue`, `Tc_TradeCounterparty`, `Tc_TradeBlock`,
`Tc_TradeSource`, `Tc_TradeLeg`, `Tc_TradeAllocation`, `Tc_AllocationInstruction`,
`Tc_TradeConfirmation`, `Tc_TradeAmendment`, `Tc_TradeCancellation`, `Tc_TradeVersionLink`,
`Tc_TradeIdentifier`, `Tc_TradeCharge`, `Tc_CounterpartyCommission`, `Tc_TradeSettlement`,
`Tc_TradeGiveUp`, `Tc_BrokerGiveUp`, `Tc_TradeMatch`, `Tc_MatchBreak`, `Tc_TradeApproval`,
`Tc_TradeNote`, `Tc_TradeTag`, `Tc_TradeSalesCredit`, `Tc_SalespersonCredit`.

## Filters

`TcLiveTrades` (`TC_TRADE.CANCEL_DATE is null`),
`TcUnacknowledgedConfirmations` (`TC_CONFIRMATION.ACKNOWLEDGED_TIME is null`).
Both are null tests, because a `Filter` will not take a boolean literal.

## Enumeration mappings

`TcTradeSideMapping`, `TcTradeStatusMapping`, `TcConfirmationMethodMapping`,
`TcAllocationStatusMapping`, `TcVenueTypeMapping`, `TcChargeTypeMapping`,
`TcMatchStatusMapping` — the only place the physical code strings live. Each accepts a short
code and the long form (`'B'` and `'BUY'`), because the venue feeds and the back office
disagree about which they send.

## Class constraints

`Trade` (quantity positive, price non-negative, notional agrees with quantity × price,
settlement not before trade date, currency is three letters), `TradeLeg`, `BlockTrade`
(filled not above block), `Allocation`, `AllocationInstruction` (split in 0–100),
`CommissionSchedule` (basis points in 0–1000, minimum not above maximum), `TradeCharge`,
`SalesCredit` (credit percentage in 0–100), `Amendment`, `TradeVersion`.

Every comparison in every constraint is FULLY PARENTHESISED. Pure binds `&&` tighter than
the comparison operators, so `$this.a > 0.0 && $this.a < 1.0` parses as
`(($this.a > 0.0) && $this.a) < 1.0` and fails to compile.

## Notes for downstream

- `Trade.notional` is stored, not derived; `grossConsideration()` recomputes it and a class
  constraint holds the two within a cent. Use the stored one for reporting — it is what the
  venue sent.
- A single-leg trade has NO `TradeLeg` rows rather than one, so `$trade.legs->isEmpty()` is
  the test for "vanilla", not `size() == 1`.
- `Counterparty` and `ClearingBroker` are ROLES. The same `core_party::LegalEntity` can be
  behind both, and joining two trades "by counterparty" is not the same as joining them by
  legal entity.
- An unmatched trade is a `TradeMatch` row with `matchStatus = UNMATCHED`, not a missing row.
- No `###Data` element and no Runtime, per the contract.
