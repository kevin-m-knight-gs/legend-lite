# order-execution

Layer 2. Depends on **order-core** and **trade-capture**, and on nothing else. Package root
`order_execution::`; prefixes `OEX_` (tables), `Oex_` (joins), `Oex` (filters), `oex` (set
ids).

29 classes, 43 associations, 29 tables, 43 joins, 2 filters, 29 class sets, 43
AssociationMappings. No enums, no functions, no profiles, no `###Data`, no Runtime.

**The shape to know before using it.** This is the project that joins an ORDER to the TRADES
it became. order-core owns the order and the venue's raw execution; trade-capture owns the
booked trade; neither knows about the other, and `order_execution::Fill` is the row that
holds both ends. A fill reaches `order_core::Order` (`filledOrder`) and
`order_core::Execution` (`sourceExecution`) directly, and reaches `trade_capture::Trade`
through `TradeBooking` — which is a ROW rather than a foreign key because booking can fail,
be retried, and produce a trade whose economics differ from the fill's.

**The names a downstream project needs:** the class `order_execution::Fill` and its set id
**`[oexFill]`**; `order_execution::OrderFillSummary` **`[oexOrderFillSummary]`** for the
order-level roll-up; `order_execution::TradeBooking` **`[oexTradeBooking]`** for the seam
into trade-capture; and `order_execution::Store` / `order_execution::Mapping`, both of which
already `include` order-core's and trade-capture's — and therefore, transitively,
core-instrument's, core-party's and core-book's.

## Cross-project associations (the reason this project exists)

ELEVEN cross into order-core and FIVE into trade-capture.

| association | this side | far side (project, set id) |
| --- | --- | --- |
| order_execution::FillOrder | `filledOrder: order_core::Order[0..1]` | `orderFills: Fill[*]` on `order_core::Order` — `[ordOrder]` |
| order_execution::FillSourceExecution | `sourceExecution: order_core::Execution[0..1]` | `executionFill: Fill[0..1]` — `[ordExecution]` |
| order_execution::SummaryOrder | `summarisedOrder: order_core::Order[0..1]` | `fillSummary: OrderFillSummary[0..1]` — `[ordOrder]` |
| order_execution::ArrivalOrder | `arrivalOrder: order_core::Order[0..1]` | `arrivalSnapshot: ArrivalSnapshot[0..1]` — `[ordOrder]` |
| order_execution::BenchmarkOrder | `benchmarkedOrder: order_core::Order[0..1]` | `executionBenchmarks: ExecutionBenchmark[*]` — `[ordOrder]` |
| order_execution::ShortfallOrder | `shortfallOrder: order_core::Order[0..1]` | `implementationShortfall: ImplementationShortfall[0..1]` — `[ordOrder]` |
| order_execution::AssessmentOrder | `assessedOrder: order_core::Order[0..1]` | `bestExecAssessment: BestExecutionAssessment[0..1]` — `[ordOrder]` |
| order_execution::ParticipationOrder | `participationOrder: order_core::Order[0..1]` | `participationMeasures: ParticipationMeasure[*]` — `[ordOrder]` |
| order_execution::InstructionOrder | `instructedOrder: order_core::Order[0..1]` | `executionInstructions: ClientExecutionInstruction[*]` — `[ordOrder]` |
| order_execution::QuoteVenueLink | `quoteVenue: order_core::Venue[0..1]` | `venueQuotes: VenueQuote[*]` — `[ordVenue]` |
| order_execution::PerformanceVenueLink | `performanceVenue: order_core::Venue[0..1]` | `venuePerformances: VenuePerformance[*]` — `[ordVenue]` |
| order_execution::BookingTradeLink | `bookedTrade: trade_capture::Trade[0..1]` | `executionBookings: TradeBooking[*]` — `[tcTrade]` |
| order_execution::BookingBreakTradeLink | `breakTrade: trade_capture::Trade[0..1]` | `executionBookingBreaks: BookingBreak[*]` — `[tcTrade]` |
| order_execution::AllocationTradeLink | `tradeAllocation: trade_capture::Allocation[0..1]` | `executionFillAllocations: FillAllocation[*]` — `[tcAllocation]` |
| order_execution::CostChargeLink | `sourceCharge: trade_capture::TradeCharge[0..1]` | `executionCostLines: ExecutionCostLine[*]` — `[tcTradeCharge]` |
| order_execution::PerformanceMifidVenueLink | `mifidVenue: trade_capture::ExecutionVenue[0..1]` | `venuePerformanceLines: VenuePerformance[*]` — `[tcVenue]` |

**Property names this project ADDS to classes it does not own.** A second property of the
same name on the same class fails the whole graph, so a downstream project that also
associates with these classes must avoid these names:

* on `order_core::Order` — `orderFills`, `fillSummary`, `arrivalSnapshot`,
  `executionBenchmarks`, `implementationShortfall`, `bestExecAssessment`,
  `participationMeasures`, `executionInstructions`
* on `order_core::Execution` — `executionFill`
* on `order_core::Venue` — `venueQuotes`, `venuePerformances`
* on `trade_capture::Trade` — `executionBookings`, `executionBookingBreaks`
* on `trade_capture::Allocation` — `executionFillAllocations`
* on `trade_capture::TradeCharge` — `executionCostLines`
* on `trade_capture::ExecutionVenue` — `venuePerformanceLines`

(These are on top of what the dependencies had already added: order-core's
`instrumentOrders` / `bookOrders` and trade-capture's `trades`, `instrumentLegs`,
`bookedTrades`, `counterpartyRoles`, `entityAllocations`, `brokerRoles`.)

## Exports

| element | kind | note |
| --- | --- | --- |
| order_execution::Fill | class | THE unit; one enriched fill against one order at one price; 5 constraints incl. positive price |
| order_execution::ExecutionReport | class | the FIX 35=8 that told us about the fill; several per fill, sequenced |
| order_execution::FillAmendment | class | a venue correction to a published fill; old and new values side by side |
| order_execution::AllocationSet | class | the COMPLETE split of one fill; carries the sums-to-the-fill constraint |
| order_execution::FillAllocation | class | one account's share of one fill (account held as a String ref) |
| order_execution::AllocationBreak | class | a split that does not add up, as a row someone clears |
| order_execution::OrderFillSummary | class | one order's fills totalled; carries the no-overfill constraint |
| order_execution::TradeBooking | class | the record that a fill became a trade — the front/back office seam |
| order_execution::BookingBreak | class | one field on which the fill and the booked trade disagree |
| order_execution::ExecutionCostLine | class | one money line on a fill; `isExplicit` separates invoice from estimate |
| order_execution::ArrivalSnapshot | class | the market at order arrival; the origin of every best-ex number |
| order_execution::ExecutionBenchmark | class | one benchmark value over one interval (VWAP, TWAP, open, close) |
| order_execution::SlippageMeasure | class | one fill against one benchmark, signed so positive is a client cost |
| order_execution::MarketImpactEstimate | class | temporary and permanent impact, split, plus the model's prediction |
| order_execution::ReversionMeasure | class | how far the price came back, at one horizon, net of the market's move |
| order_execution::ImplementationShortfall | class | delay + execution + opportunity + explicit, with a components-agree constraint |
| order_execution::VenueQuote | class | the venue's own bid/ask at the moment of the fill |
| order_execution::QuoteComparison | class | the fill price against that quote: INSIDE / AT_TOUCH / OUTSIDE / AT_MID |
| order_execution::ExecutionPolicy | class | the firm's best-ex policy, versioned and dated |
| order_execution::BestExecutionAssessment | class | the verdict on one order: score, outcome, reviewer |
| order_execution::BestExecutionFactor | class | one scored, weighted factor behind that verdict |
| order_execution::BestExecutionReport | class | the periodic published report; publication has a date and a version |
| order_execution::VenuePerformance | class | one venue's execution quality over one period (RTS 27-shaped) |
| order_execution::ParticipationMeasure | class | target vs achieved share of market volume over an interval |
| order_execution::LiquidityCapture | class | passive / aggressive / midpoint, and the spread earned or paid |
| order_execution::ExecutionSession | class | the trading session a fill happened in; auction fills are not comparable |
| order_execution::LatencyMeasure | class | the round trip, leg by leg, with a clock-synchronised flag |
| order_execution::ExecutionException | class | something that went wrong around a fill, raised and cleared |
| order_execution::ClientExecutionInstruction | class | what the client told us; may override the policy |
| order_execution::Store | store | 29 `OEX_` tables; `include`s `order_core::Store` and `trade_capture::Store` |
| order_execution::Mapping | mapping | 29 class sets, 43 AssociationMappings; `include`s both dependency mappings |

## Internal associations

`FillReports` (`reports`/`reportFill`), `FillAmendments` (`fillAmendments`/`amendedFill`),
`FillAllocations` (`fillAllocations`/`allocationFill`),
`FillAllocationSet` (`allocationSet`/`setFill`),
`SetAllocations` (`setAllocations`/`owningSet`),
`SetBreaks` (`allocationBreaks`/`breakSet`),
`SummaryFillLink` (`summary`/`summaryFills`),
`FillBooking` (`booking`/`bookingFill`),
`BookingBreaks` (`bookingBreaks`/`breakBooking`),
`FillCostLines` (`costLines`/`costFill`),
`FillSlippage` (`slippageMeasures`/`slippageFill`),
`BenchmarkSlippage` (`benchmarkSlippages`/`slippageBenchmark`),
`FillImpact` (`marketImpact`/`impactFill`),
`ImpactReversion` (`reversions`/`reversionImpact`),
`ShortfallArrival` (`arrival`/`arrivalShortfalls`),
`FillQuote` (`prevailingQuote`/`quotedFills`),
`FillQuoteComparison` (`quoteComparison`/`comparisonFill`),
`QuoteComparisons` (`quoteComparisons`/`comparedQuote`),
`AssessmentFactors` (`factors`/`factorAssessment`),
`AssessmentPolicy` (`policy`/`policyAssessments`),
`AssessmentReport` (`report`/`reportAssessments`),
`ReportVenuePerformance` (`reportVenuePerformance`/`performanceReport`),
`FillLiquidity` (`liquidityCapture`/`captureFill`),
`FillSession` (`session`/`sessionFills`),
`SessionParticipation` (`participationSession`/`sessionParticipation`),
`FillLatency` (`latency`/`latencyFill`),
`FillExceptions` (`exceptions`/`exceptionFill`).

## Set ids (a GLOBAL namespace; extend or name these, the defaults do not exist)

`oexFill`, `oexExecutionReport`, `oexFillAmendment`, `oexAllocationSet`,
`oexFillAllocation`, `oexAllocationBreak`, `oexOrderFillSummary`, `oexTradeBooking`,
`oexBookingBreak`, `oexCostLine`, `oexArrivalSnapshot`, `oexBenchmark`, `oexSlippage`,
`oexMarketImpact`, `oexReversion`, `oexShortfall`, `oexVenueQuote`, `oexQuoteComparison`,
`oexPolicy`, `oexAssessment`, `oexFactor`, `oexBestExReport`, `oexVenuePerformance`,
`oexParticipation`, `oexLiquidityCapture`, `oexSession`, `oexLatency`, `oexException`,
`oexClientInstruction`.

Every set is declared with an explicit id, so `order_execution_Fill` and its siblings do NOT
exist. A downstream `extends [...]` or cross-project `AssociationMapping` must name the `oex`
id above.

## Tables

`OEX_FILL`, `OEX_EXEC_REPORT`, `OEX_FILL_AMENDMENT`, `OEX_ALLOCATION_SET`,
`OEX_FILL_ALLOCATION`, `OEX_ALLOCATION_BREAK`, `OEX_ORDER_FILL_SUMMARY`,
`OEX_TRADE_BOOKING`, `OEX_BOOKING_BREAK`, `OEX_COST_LINE`, `OEX_ARRIVAL_SNAPSHOT`,
`OEX_BENCHMARK`, `OEX_SLIPPAGE`, `OEX_MARKET_IMPACT`, `OEX_REVERSION`, `OEX_SHORTFALL`,
`OEX_VENUE_QUOTE`, `OEX_QUOTE_COMPARISON`, `OEX_POLICY`, `OEX_BESTEX_ASSESSMENT`,
`OEX_BESTEX_FACTOR`, `OEX_BESTEX_REPORT`, `OEX_VENUE_PERFORMANCE`, `OEX_PARTICIPATION`,
`OEX_LIQUIDITY_CAPTURE`, `OEX_SESSION`, `OEX_LATENCY`, `OEX_EXCEPTION`,
`OEX_CLIENT_INSTRUCTION`.

`OEX_FILL` carries the foreign keys `ORDER_ID` (to `ORD_ORDER`), `EXECUTION_ID` (to
`ORD_EXECUTION`), `SUMMARY_ID`, `QUOTE_ID`, `SESSION_ID`. `OEX_TRADE_BOOKING` and
`OEX_BOOKING_BREAK` carry `TRADE_ID` (to `TC_TRADE`); `OEX_FILL_ALLOCATION` carries
`TC_ALLOCATION_ID`; `OEX_COST_LINE` carries `CHARGE_ID`; `OEX_VENUE_QUOTE` carries
`VENUE_ID`; `OEX_VENUE_PERFORMANCE` carries `VENUE_ID`, `TC_VENUE_ID` and `REPORT_ID`. None
of the cross-project keys is mapped to a property — the model reaches those things by
association.

## Joins available to downstream projects that include this store

Cross-boundary into order-core: `Oex_FillOrder`, `Oex_FillSourceExecution`,
`Oex_SummaryOrder`, `Oex_ArrivalOrder`, `Oex_BenchmarkOrder`, `Oex_ShortfallOrder`,
`Oex_AssessmentOrder`, `Oex_ParticipationOrder`, `Oex_InstructionOrder`, `Oex_QuoteVenue`,
`Oex_PerformanceVenue`.

Cross-boundary into trade-capture: `Oex_BookingTrade`, `Oex_BookingBreakTrade`,
`Oex_AllocationTrade`, `Oex_CostCharge`, `Oex_PerformanceMifidVenue`.

Internal: `Oex_FillReport`, `Oex_FillAmendment`, `Oex_FillAllocation`,
`Oex_FillAllocationSet`, `Oex_SetAllocation`, `Oex_SetBreak`, `Oex_SummaryFill`,
`Oex_FillBooking`, `Oex_BookingBreakLink`, `Oex_FillCostLine`, `Oex_FillSlippage`,
`Oex_BenchmarkSlippage`, `Oex_FillImpact`, `Oex_ImpactReversion`, `Oex_ShortfallArrival`,
`Oex_FillQuote`, `Oex_FillQuoteComparison`, `Oex_QuoteComparison`, `Oex_AssessmentFactor`,
`Oex_AssessmentPolicy`, `Oex_AssessmentReport`, `Oex_ReportVenuePerformance`,
`Oex_FillLiquidity`, `Oex_FillSession`, `Oex_SessionParticipation`, `Oex_FillLatency`,
`Oex_FillException`.

## Filters

`OexLiveFills` (`OEX_FILL.BUST_TIME is null`),
`OexOpenBookingBreaks` (`OEX_BOOKING_BREAK.RESOLVED_AT is null`).
Both are null tests, because a `Filter` will not take a boolean literal.

## Class constraints

24 of the 29 classes carry a constraint block. The three the theme demands:

* **`OrderFillSummary.filledQuantityNotAboveOrderQuantity`** — `($this.filledQuantity <=
  $this.orderQuantity)`. The summary snapshots the order's quantity onto its own row so this
  is checkable without a navigation, and so that resizing the order later cannot
  retrospectively legalise an old overfill.
* **`Fill.fillPriceIsPositive`** — `($this.fillPrice > 0.0)`. Also `fillQuantityIsPositive`,
  a gross-agrees-with-quantity×price tolerance, `reportedAt >= executedAt`, and a
  three-letter currency check.
* **`AllocationSet.allocatedQuantitySumsToFill`** — two one-sided comparisons inside an
  `&&`, each fully parenthesised. The invariant is on the SET, not on `FillAllocation`,
  because only the set knows the total.

Others: `FillAmendment`, `FillAllocation` (a share cannot exceed the fill), `TradeBooking`
(booked cannot exceed filled), `ExecutionCostLine`, `ArrivalSnapshot` (ask not below bid),
`ExecutionBenchmark`, `SlippageMeasure`, `MarketImpactEstimate`, `ReversionMeasure`,
`ImplementationShortfall` (components agree with the stored total), `VenueQuote`,
`QuoteComparison`, `ExecutionPolicy`, `BestExecutionAssessment` (score in 0–100),
`BestExecutionFactor` (score in 0–100, weight in 0–1), `BestExecutionReport`,
`VenuePerformance` (fill rate in 0–1), `ParticipationMeasure`, `LiquidityCapture`,
`ExecutionSession`, `LatencyMeasure`.

EVERY comparison is FULLY PARENTHESISED. Pure binds `&&` tighter than the comparison
operators, so `$this.a >= 0.0 && $this.a <= 1.0` parses as
`(($this.a >= 0.0) && $this.a) <= 1.0` and fails to compile.

## Notes for downstream

- **The diamond compiles.** `order_core::Store` includes core-instrument's and core-book's;
  `trade_capture::Store` includes those two AND core-party's. Including both here pulls
  core-instrument and core-book in TWICE by two different routes, and it is fine — the
  tables are not duplicated and the joins resolve. Same for the two mappings.
- **This project has no enums.** The code fields (`execType`, `liquidityFlag`, `sessionType`,
  `costType`, `outcome`, `priceOutcome`) are Strings, because those vocabularies are
  venue-defined and change without notice. If you need them typed, map them yourself; do not
  expect an `EnumerationMapping` here.
- **Quantities here are `Float`, not order-core's `Integer`.** An allocated fill is fractional
  and an average is not a share count. Comparing `Fill.fillQuantity` against
  `order_core::Order.quantity` therefore crosses a type boundary: widen the Integer with
  `->toFloat()`, since `Integer * Float` types as `Number`, which is not a subtype of `Float`.
- A **busted** fill is a row with a `bustTime`, not a missing row. Everything hanging off it
  — allocations, slippage, bookings — is still there and must be excluded deliberately;
  `Fill.isLive()` and the `OexLiveFills` filter are the two ways to do that.
- `TradeBooking` is a ROW, not a foreign key. A fill with no booking is unbooked; a fill with
  a booking whose `bookingStatus` is `REJECTED` is a failure someone is working on. Those are
  different states and `$fill.booking->isEmpty()` only detects the first.
- `order_core::Execution` and `order_execution::Fill` are two records of ONE event. order-core
  holds what the venue reported; this holds the desk's enriched version. Do not treat a count
  of one as a count of the other.
- No `###Data` element and no Runtime, per the contract.
