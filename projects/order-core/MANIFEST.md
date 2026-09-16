# order-core

Layer 1. Depends on **core-instrument** and **core-book**, and on nothing else. The order
book as a desk keeps it: a client order arrives as one parent order, an algorithm slices it
into child orders, each routed to a venue, and every slice is itself a row of `ORD_ORDER`
whose `PARENT_ORDER_ID` points back into the same table.

The two constructs this project exists to exercise:

* the `{target}` **SELF-JOIN** `Ord_ParentOrder(ORD_ORDER.PARENT_ORDER_ID = {target}.ORDER_ID)`
  and the `order_core::OrderParent` association over it, whose two ends are both
  `order_core::Order` and whose AssociationMapping ends name **`[ordOrder, ordOrder]`** —
  the same set id twice;
* five **enums with EnumerationMappings**, which are the only place the FIX-ish physical
  code strings live.

Prefixes: tables `ORD_`, joins `Ord_`, filters `Ord`, set ids `ord`.

**The names a downstream project needs:** the class `order_core::Order`, its set id
**`[ordOrder]`**, the enum `order_core::OrderState`, and `order_core::Store` /
`order_core::Mapping` — both of which already include the core-instrument and core-book
stores and mappings, so including either of mine gives you theirs too.

| element | kind | note |
| --- | --- | --- |
| order_core::Order | class | THE order; recursive via `parentOrder` / `childOrders`; quantity, filledQuantity, remainingQuantity, prices, timestamps |
| order_core::Execution | class | one fill against one order at one price |
| order_core::Venue | class | exchange, MTF, dark pool or internaliser a child order is routed to |
| order_core::RoutingInstruction | class | one line of an order's routing plan: how much, where, in what sequence |
| order_core::OrderStateChange | class | one recorded transition of an order's state, with who and when |
| order_core::Allocation | class | split of a filled order across client accounts (account held as a String ref) |
| order_core::OrderAmendment | class | a request to reprice, resize or pull a live order, accepted or not |
| order_core::Trader | class | the person accountable for the order |
| order_core::OrderSource | class | the channel the order arrived on: FIX session, sales trader, rebalancer |
| order_core::ExecutionAlgorithm | class | the algo that slices a parent into children |
| order_core::OrderRestriction | class | a constraint blocking free routing (restricted list, locate, no-cross) |
| order_core::OrderType | enum | MARKET, LIMIT, STOP, STOP_LIMIT, PEG |
| order_core::TimeInForce | enum | DAY, GTC, IOC, FOK, GTD |
| order_core::OrderState | enum | NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED, EXPIRED |
| order_core::OrderSide | enum | BUY, SELL, SELL_SHORT, BUY_TO_COVER |
| order_core::ExecutionCapacity | enum | AGENCY, PRINCIPAL, RISKLESS_PRINCIPAL |
| order_core::OrderParent | association | **self-join**; `parentOrder[0..1]` / `childOrders[*]`, both ends `Order` |
| order_core::OrderInstrument | association | `instrument[0..1]` (`core_instrument::Instrument`) / `instrumentOrders[*]` |
| order_core::OrderBook | association | `book[0..1]` (`core_book::Book`) / `bookOrders[*]` |
| order_core::OrderExecutions | association | `executions[*]` / `executedOrder[0..1]` |
| order_core::ExecutionVenueLink | association | `executionVenue[0..1]` / `venueExecutions[*]` |
| order_core::OrderVenueLink | association | `routedVenue[0..1]` / `venueOrders[*]` |
| order_core::OrderRoutingLink | association | `routingInstructions[*]` / `routedOrder[0..1]` |
| order_core::RoutingVenueLink | association | `targetVenue[0..1]` / `venueRoutingInstructions[*]` |
| order_core::OrderStateHistory | association | `stateChanges[*]` / `stateChangeOrder[0..1]` |
| order_core::OrderAllocations | association | `allocations[*]` / `allocatedOrder[0..1]` |
| order_core::OrderAmendments | association | `amendments[*]` / `amendedOrder[0..1]` |
| order_core::OrderTraderLink | association | `trader[0..1]` / `traderOrders[*]` |
| order_core::OrderSourceLink | association | `source[0..1]` / `sourceOrders[*]` |
| order_core::OrderAlgorithmLink | association | `algorithm[0..1]` / `algorithmOrders[*]` |
| order_core::OrderRestrictions | association | `restrictions[*]` / `restrictedOrder[0..1]` |
| order_core::Store | store | includes `core_instrument::Store` and `core_book::Store`; tables ORD_ORDER, ORD_EXECUTION, ORD_VENUE, ORD_ROUTING_INSTRUCTION, ORD_STATE_CHANGE, ORD_ALLOCATION, ORD_AMENDMENT, ORD_TRADER, ORD_ORDER_SOURCE, ORD_ALGORITHM, ORD_RESTRICTION |
| order_core::Mapping | mapping | includes `core_instrument::Mapping` and `core_book::Mapping`; one set per class, 5 EnumerationMappings, 15 AssociationMappings |

## Set ids (a GLOBAL namespace — extend or reference these, do not redeclare them)

`ordOrder`, `ordExecution`, `ordVenue`, `ordRoutingInstruction`, `ordStateChange`,
`ordAllocation`, `ordAmendment`, `ordTrader`, `ordOrderSource`, `ordAlgorithm`,
`ordRestriction`.

Every set is declared explicitly, so the DEFAULT ids (`order_core_Order` and friends) do NOT
exist. A downstream `extends [...]` or cross-project `AssociationMapping` must name the
explicit id — e.g. `[ordOrder]`.

## Store names

Tables as listed above. Joins:

`Ord_ParentOrder` (**the self-join**), `Ord_OrderInstrument` (to `CI_INSTRUMENT`),
`Ord_OrderBook` (to `CB_BOOK`), `Ord_OrderExecution`, `Ord_ExecutionVenue`,
`Ord_OrderVenue`, `Ord_OrderRouting`, `Ord_RoutingVenue`, `Ord_OrderStateChange`,
`Ord_OrderAllocation`, `Ord_OrderAmendment`, `Ord_OrderTrader`, `Ord_OrderSource`,
`Ord_OrderAlgorithm`, `Ord_OrderRestriction`.

Filter: `OrdUncancelledOrders` (`ORD_ORDER.CANCEL_REASON is null`) — written as a null test
because a `Filter` will not take a boolean literal.

## Enumeration mappings

`OrdOrderTypeMapping`, `OrdTimeInForceMapping`, `OrdOrderStateMapping`,
`OrdOrderSideMapping`, `OrdExecutionCapacityMapping`. Each maps two source values per
constant: the FIX tag character and the spelled-out code, e.g. `FILLED: ['2', 'FILLED']`,
`IOC: ['3', 'IOC']`, `PEG: ['P', 'PEGGED']`. `OrdOrderStateMapping` is used twice — on
`Order.state` and on both `OrderStateChange.fromState` and `.toState`.

## Column / Pure type conventions

`VARCHAR` on `String`, `INTEGER` on `Integer`, `DOUBLE` on `Float`, `BIT` on `Boolean`,
`DATE` on `StrictDate`, `TIMESTAMP` on `DateTime`. `REAL` is not used.

## Derived properties

`Order.fillRatio(): Float[1]` — `filledQuantity / quantity`; `/` widens two Integers to
Float, so the declared type must be `Float`. `Order.isChildOrder()`, `Order.isWorkedOut()`,
`Execution.grossConsideration(): Float[1]` (the quantity is `->toFloat()`d first, because
`Integer * Float` is typed `Number`, which is not a subtype of `Float`), `Trader.fullName()`.

No `###Data` element and no Runtime, per the contract.
