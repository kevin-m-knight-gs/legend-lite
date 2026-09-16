# A class-typed property mapped over a join with no target set id fails at execution

    core_calendar::CcSettlementCycle[ccSettlementCycle]: Relational
    {
        ...
        market: [core_calendar::Store] @Cc_MarketCycle      // no target set id
    }

compiles. Navigating it — `$x.market.mic` — fails at test-suite initialisation with

    Assert failure at (resource:/platform/pure/essential/tests/assert.pure line:26 column:5),
      "meta::pure::router::store::routing::Void not supported!"

which names no class, no property, no mapping, no store and no file. Writing
`market[ccMarket]: [core_calendar::Store] @Cc_MarketCycle` makes it pass.

## The set id is required, and the root marker does not substitute

Six cases, each in its own file and JVM:

| target set id | target set marked `*` | query resolved against | |
| --- | --- | --- | --- |
| named | no | its own mapping | routes |
| named | no | a mapping that INCLUDES it | routes |
| absent | no | its own mapping | **Void** |
| absent | no | a mapping that INCLUDES it | **Void** |
| absent | **yes** | its own mapping | **Void** |
| absent | **yes** | a mapping that INCLUDES it | **Void** |

So this is not about including a mapping, and it is not about which set is the root. The id
is simply mandatory, and neither the grammar nor the compiler says so.

A join CHAIN that ends `| [store]TABLE.COLUMN` lands on a column rather than a class and
correctly needs no id. The corpus has 281 of those and they are fine.

## Why it went unfound for so long

This is the failure that was seen once when two projects were first linked, recorded as a
hop-count or schema problem, and reverted. `probe_boundary_navigation.py` then ran fourteen
navigation cases — one hop and two, both edge styles, with and without a schema, with and
without a primary key — and every one PASSED, because every property in it was written with a
set id. The corpus always writes them. The absent thing was never the variable.

## The scale of it

Sweeping all 56 projects found **112 such properties in 8 of them**:

| project | count |
| --- | --- |
| risk-core | 31 |
| custody-core | 29 |
| cash-core | 28 |
| core-account | 11 |
| core-calendar | 5 |
| core-units | 4 |
| product-core | 3 |
| core-instrument | 1 |

All 56 projects compiled, alone with their closure and together, throughout. Every one of
those 112 navigations would have failed at execution with the message above.

`core-instrument` is the sharpest case: it is LINKED into the executable corpus and all 29
of its services pass, because none of them happens to navigate `identifiers`.

This is the argument for executing the graph rather than compiling it, in one number.

Reproduce with `scripts/corpus/probe_missing_setid.py`. The check that now prevents it is
`unroutable()` in `scripts/projects/check.py`.
