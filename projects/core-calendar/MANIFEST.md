# core-calendar

Layer 0, no dependencies. Package root `core_calendar::`, prefix `CC` / `Cc` / `cc`.

Business calendars, the holidays they observe, the markets struck against them and the
T+n settlement cycle in force per market and asset class. The holiday count is exposed as
a store **View** (`CC_HOLIDAY_COUNT`), so a downstream settlement-date calculation reads
one pre-aggregated row per calendar-year instead of scanning the holiday table.

## Exports

| element | kind | note |
| --- | --- | --- |
| core_calendar::CcCalendar | class | named calendar: TARGET, USNY, GBLO, JPTO; `holidays`, `holidayCounts` |
| core_calendar::CcHoliday | class | one dated non-business day on one calendar, with its name |
| core_calendar::CcMarket | class | venue keyed by MIC (XNYS, XLON, XETR, XTKS); `calendar`, `settlementCycles` |
| core_calendar::CcSettlementCycle | class | T+n per market per asset class, with `effectiveFrom` |
| core_calendar::CcHolidayCount | class | holidays per calendar per year; mapped to the View, not to a table |
| core_calendar::Store | store | tables CC_CALENDAR, CC_HOLIDAY, CC_MARKET, CC_SETTLEMENT_CYCLE; view CC_HOLIDAY_COUNT |
| core_calendar::Mapping | mapping | sets ccCalendar, ccHoliday, ccMarket, ccSettlementCycle, ccHolidayCount |
| core_calendar::ccSettlementLagDays | function | `(CcSettlementCycle[1]): Integer[1]` — the n in T+n |
| core_calendar::ccIsTPlusTwo | function | `(CcSettlementCycle[1]): Boolean[1]` |
| core_calendar::ccBusinessDaysInYear | function | `(CcHolidayCount[1]): Integer[1]` — 261 minus the View's count |

## Store detail

| name | kind | note |
| --- | --- | --- |
| CC_CALENDAR | table | PK CALENDAR_ID; CALENDAR_NAME, FINANCIAL_CENTRE, COUNTRY_CODE, TIME_ZONE, WEEKEND_DAYS, IS_ACTIVE |
| CC_HOLIDAY | table | PK HOLIDAY_ID; CALENDAR_ID, HOLIDAY_DATE, HOLIDAY_YEAR, HOLIDAY_NAME, IS_OBSERVED, IS_HALF_DAY |
| CC_MARKET | table | PK MARKET_MIC; MARKET_NAME, CALENDAR_ID, SETTLEMENT_CURRENCY, IS_ACTIVE |
| CC_SETTLEMENT_CYCLE | table | PK CYCLE_ID; MARKET_MIC, ASSET_CLASS, CYCLE_CODE, SETTLEMENT_DAYS, CUTOFF_TIME, EFFECTIVE_FROM |
| CC_HOLIDAY_COUNT | view | `~groupBy (CC_HOLIDAY.CALENDAR_ID, CC_HOLIDAY.HOLIDAY_YEAR)`; PK CALENDAR_ID + HOLIDAY_YEAR; HOLIDAY_COUNT = `count(CC_HOLIDAY.HOLIDAY_DATE)` |
| Cc_CalendarHoliday | join | CC_HOLIDAY.CALENDAR_ID = CC_CALENDAR.CALENDAR_ID |
| Cc_MarketCalendar | join | CC_MARKET.CALENDAR_ID = CC_CALENDAR.CALENDAR_ID |
| Cc_MarketCycle | join | CC_SETTLEMENT_CYCLE.MARKET_MIC = CC_MARKET.MARKET_MIC |
| Cc_CalendarHolidayCount | join | CC_HOLIDAY_COUNT.CALENDAR_ID = CC_CALENDAR.CALENDAR_ID — reaches the View |
| CcObservedHolidays | filter | CC_HOLIDAY.IS_OBSERVED = 1 |

## Notes for downstream

- The View is an aggregation, so a calendar-year with no holiday rows forms **no group** and
  is absent rather than present with a count of zero. Anything that needs the zero must
  outer-join from `CC_CALENDAR`.
- No `###Data` element and no Runtime: the tables are declared and unseeded.
