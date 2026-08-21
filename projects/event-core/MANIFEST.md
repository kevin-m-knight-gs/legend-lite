# event-core

Layer 1. Package root `event_core::`, prefix `EVT` / `Evt` / `evt`.
Depends on **core-instrument** and **core-calendar**, and on nothing else.

Corporate actions — cash dividend, stock split, rights issue, merger, spin-off, tender
offer, call redemption — over one wide table told apart by a discriminator, plus the
elections a holder makes on the ones that are elective.

**The two names a downstream project needs:** the base class
`event_core::EvtCorporateEvent` and the base set id **`[evtBase]`**. Extend the class to
add an action type; extend the set id to map it (a Filter in `event_core::Store` and
`extends [evtBase]` in the mapping is the whole of it).

**Where the calendar comes in.** An action's ex, record and pay dates are announced as
plain dates on `EvtCorporateEvent`, unadjusted. Whether each one is a business day is a
separate row of `EvtEventDate`, which carries the `core_calendar::CcCalendar` it was
checked against, the `EvtBusinessDayConvention` that applied, and the `adjustedDate` it
rolled to. A pay date rolls FOLLOWING; a record date is a register snapshot and does not
roll at all — which is why the answer is per date and not per event.

## Exports

| element | kind | note |
| --- | --- | --- |
| event_core::EvtCorporateEvent | class | BASE class; eventId, type, status, instrument, market, ex/record/pay dates, elective flag |
| event_core::EvtCashDividend | class | extends EvtCorporateEvent; gross/net per share, withholding, frequency, special |
| event_core::EvtStockSplit | class | extends EvtCorporateEvent; from/to shares, reverse flag, `adjustmentFactor` |
| event_core::EvtRightsIssue | class | extends EvtCorporateEvent; subscription price, ratio, tradability, subscription window |
| event_core::EvtMerger | class | extends EvtCorporateEvent; acquirer, exchange ratio, cash per share, consideration |
| event_core::EvtSpinOff | class | extends EvtCorporateEvent; spun-off entity, distribution ratio, cost-basis split |
| event_core::EvtTenderOffer | class | extends EvtCorporateEvent; ELECTIVE — offer price, `electionDeadline`, minimum, proration |
| event_core::EvtCallRedemption | class | extends EvtCorporateEvent; redemption price %, call type, notice date, partial |
| event_core::EvtEventDate | class | one of an event's dates, checked against a `core_calendar::CcCalendar`; own table |
| event_core::EvtElectionOption | class | one choice published on an elective event, with its own deadline and default flag |
| event_core::EvtEventElection | class | what one holder elected on one option, with quantity and allocation |
| event_core::EvtEventType | enum | CASH_DIVIDEND, STOCK_SPLIT, RIGHTS_ISSUE, MERGER, SPIN_OFF, TENDER_OFFER, CALL_REDEMPTION |
| event_core::EvtEventStatus | enum | ANNOUNCED, CONFIRMED, EFFECTIVE, CANCELLED, LAPSED |
| event_core::EvtDateRole | enum | ANNOUNCEMENT, EX, RECORD, PAY, ELECTION_DEADLINE, EFFECTIVE |
| event_core::EvtBusinessDayConvention | enum | FOLLOWING, MODIFIED_FOLLOWING, PRECEDING, NONE |
| event_core::EvtConsiderationType | enum | CASH, SECURITIES, CASH_AND_SECURITIES, RIGHTS |
| event_core::EvtElectionStatus | enum | PENDING, SUBMITTED, ACKNOWLEDGED, REJECTED, DEFAULTED |
| event_core::EvtInstrumentEvents | association | CROSSES into core-instrument; adds `corporateEvents` to `core_instrument::Instrument` |
| event_core::Store | store | includes core_instrument::Store and core_calendar::Store; 4 tables |
| event_core::Mapping | mapping | includes both dependencies' mappings; root set `[evtBase]`, 7 subtype sets, 6 EnumerationMappings |

11 classes, 6 enums, 1 association.

## The association

```
Association event_core::EvtInstrumentEvents
{
   instrument: core_instrument::Instrument[0..1];
   corporateEvents: event_core::EvtCorporateEvent[*];
}
```

It puts `corporateEvents` on `core_instrument::Instrument`, so anything already holding an
instrument navigates to its actions without naming this project. Its mapping ends are
`instrument[evtBase, ciBase]` and `corporateEvents[ciBase, evtBase]`, both over
`@Evt_EventInstrument` — the set ids are explicit because neither project has a default id.

## Set ids (extend these; they are a GLOBAL namespace)

`evtBase` (root, `*event_core::EvtCorporateEvent`), then `evtCashDividend`,
`evtStockSplit`, `evtRightsIssue`, `evtMerger`, `evtSpinOff`, `evtTenderOffer`,
`evtCallRedemption` — each `extends [evtBase]` — plus `evtEventDate`,
`evtElectionOption` and `evtElection` on their own tables.

## Store detail

| name | kind | note |
| --- | --- | --- |
| EVT_CORPORATE_EVENT | table | PK EVENT_ID; EVENT_TYPE discriminator, EVENT_TYPE_CODE, EVENT_STATUS, INSTRUMENT_ID, MARKET_MIC, ANNOUNCEMENT/EX/RECORD/PAY dates, IS_ELECTIVE, plus per-type nullable columns |
| EVT_EVENT_DATE | table | PK EVENT_DATE_ID; EVENT_ID, DATE_ROLE, ANNOUNCED_DATE, ADJUSTED_DATE, IS_BUSINESS_DAY, CONVENTION, CALENDAR_ID, MARKET_MIC |
| EVT_ELECTION_OPTION | table | PK OPTION_ID; EVENT_ID, OPTION_CODE, CONSIDERATION_TYPE, CASH/SHARES per share, IS_DEFAULT, RESPONSE_DEADLINE |
| EVT_ELECTION | table | PK ELECTION_ID; EVENT_ID, OPTION_ID, ACCOUNT_REFERENCE, QUANTITY_ELECTED, ELECTION_STATUS, SUBMITTED_ON, ALLOCATED_QUANTITY |
| Evt_EventDate | join | EVT_EVENT_DATE.EVENT_ID = EVT_CORPORATE_EVENT.EVENT_ID |
| Evt_EventElectionOption | join | EVT_ELECTION_OPTION.EVENT_ID = EVT_CORPORATE_EVENT.EVENT_ID |
| Evt_EventElection | join | EVT_ELECTION.EVENT_ID = EVT_CORPORATE_EVENT.EVENT_ID |
| Evt_ElectionOption | join | EVT_ELECTION.OPTION_ID = EVT_ELECTION_OPTION.OPTION_ID |
| Evt_EventInstrument | join | **crosses** — EVT_CORPORATE_EVENT.INSTRUMENT_ID = CI_INSTRUMENT.INSTRUMENT_ID |
| Evt_EventMarket | join | **crosses** — EVT_CORPORATE_EVENT.MARKET_MIC = CC_MARKET.MARKET_MIC |
| Evt_DateCalendar | join | **crosses** — EVT_EVENT_DATE.CALENDAR_ID = CC_CALENDAR.CALENDAR_ID |
| Evt_DateMarket | join | **crosses** — EVT_EVENT_DATE.MARKET_MIC = CC_MARKET.MARKET_MIC |
| EvtCashDividendRows | filter | EVENT_TYPE = 'CASH_DIVIDEND' |
| EvtStockSplitRows | filter | EVENT_TYPE = 'STOCK_SPLIT' |
| EvtRightsIssueRows | filter | EVENT_TYPE = 'RIGHTS_ISSUE' |
| EvtMergerRows | filter | EVENT_TYPE = 'MERGER' |
| EvtSpinOffRows | filter | EVENT_TYPE = 'SPIN_OFF' |
| EvtTenderOfferRows | filter | EVENT_TYPE = 'TENDER_OFFER' |
| EvtCallRedemptionRows | filter | EVENT_TYPE = 'CALL_REDEMPTION' |
| EvtLiveEvents | filter | CANCELLED_ON is null — a null test, because a Filter will not take a boolean literal |

## Enumeration mappings

`EvtEventTypeMapping`, `EvtEventStatusMapping`, `EvtDateRoleMapping`,
`EvtBusinessDayConventionMapping`, `EvtConsiderationTypeMapping`,
`EvtElectionStatusMapping` — the only place the physical code strings live (`DVCA`,
`SPLF`, `TEND`, `MODF`, `DFLT` and the rest, each also accepting its spelled-out form).

## Discriminator values

`EVENT_TYPE`: `CASH_DIVIDEND`, `STOCK_SPLIT`, `RIGHTS_ISSUE`, `MERGER`, `SPIN_OFF`,
`TENDER_OFFER`, `CALL_REDEMPTION`. It stays a raw `String` on the base class, because it
is what the subtype `~filter`s match — before any enum transformation could have run.
`EVENT_TYPE_CODE` is the same classification as a mapped `EvtEventType`.

## Notes for downstream

- `isElective` is stored. A mandatory action has no `electionOptions` rows because none
  exist, which is not the same fact as none having loaded, so do not infer electiveness
  from an empty list.
- Only `EvtTenderOffer` carries a first-class `electionDeadline`; a rights issue's
  equivalent is `subscriptionEndDate`. Both also appear as an `ELECTION_DEADLINE` row in
  `EVT_EVENT_DATE` when they were checked against a calendar.
- `EvtEventElection.allocatedQuantity` is populated only on a prorated offer, so its
  presence is the proration flag.
- `EvtStockSplit.adjustmentFactor` is `Float[1]`, not Integer: `/` in Pure widens to Float
  even when both operands are Integer.
- No `###Data` element and no Runtime: the four tables are declared and unseeded.
