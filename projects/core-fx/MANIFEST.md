# core-fx

Layer 0, no dependencies. FX rates keyed by **(currency pair, cob date, rate type)**, the
market conventions that decide which way a rate points, and the conversion functions
downstream projects call instead of re-implementing.

## Exports

| element | kind | note |
| --- | --- | --- |
| `core_fx::CfxCurrencyPair` | class | pair definition: base/quote, `quotedInverted`, `pipFactor`, `quotePrecision`; derived `convention(): String[1]` |
| `core_fx::CfxRate` | class | one published rate; identity is `pairCode` + `cobDate` + `rateType`; derived `spread(): Float[1]` |
| `core_fx::CfxFixingSource` | class | publisher and snap instant (WMR 16:00 Europe/London, ECB 14:15 Europe/Berlin) |
| `core_fx::CfxCrossRate` | class | a cross triangulated through a vehicle currency, naming its two legs |
| `core_fx::convert` | function | `(amount: Float[1], rate: Float[1]): Float[1]` — the conversion function |
| `core_fx::invert` | function | `(rate: Float[1]): Float[1]` — `1.0 / rate` |
| `core_fx::cross` | function | `(legOne: Float[1], legTwo: Float[1]): Float[1]` — EURJPY = EURUSD * USDJPY |
| `core_fx::forwardOutright` | function | `(spot: Float[1], forwardPoints: Float[1], pipFactor: Float[1]): Float[1]` |
| `core_fx::convertQuoted` | function | `(amount: Float[1], rate: Float[1], quotedInverted: Boolean[1]): Float[1]` — convention-safe convert |
| `core_fx::midOf` | function | `(bid: Float[1], ask: Float[1]): Float[1]` |
| `core_fx::Store` | store | tables `CFX_CURRENCY_PAIR`, `CFX_RATE`, `CFX_FIXING_SOURCE`, `CFX_CROSS_RATE`; joins `Cfx_RatePair`, `Cfx_RateSource`, `Cfx_CrossLegOnePair`, `Cfx_CrossLegTwoPair`; filter `CfxOfficialOnly` |
| `core_fx::Mapping` | mapping | sets `cfxCurrencyPair`, `cfxRate`, `cfxFixingSource`, `cfxCrossRate` |

## The composite key

`CFX_RATE` has a three-column primary key and `cfxRate` declares the matching three-column
`~primaryKey`:

    PAIR_CODE, COB_DATE, RATE_TYPE

Because on one cob date one pair has several rates that are different numbers:

| rateType | what it is |
| --- | --- |
| `SPOT` | the T+2 rate at the close snap |
| `FORWARD` | an outright for a `tenor` — spot plus `forwardPoints / pipFactor` |
| `FIXING` | the official benchmark (WM/R 16:00 London, ECB 14:15 CET) |

A two-column key would make the EURUSD spot and the EURUSD fixing for a date the same
instance, and one of them would vanish from any result.

## Quoting conventions

The rate means **quote currency per ONE unit of base currency**. `quotedInverted` is true
when the market puts USD on the *base* side, so valuing a foreign-currency amount into USD
needs `core_fx::invert` first.

| pair | base | quote | reads as | `quotedInverted` | `pipFactor` | `quotePrecision` |
| --- | --- | --- | --- | --- | --- | --- |
| EURUSD | EUR | USD | USD per 1 EUR, ~1.0850 | false | 10000 | 5 |
| GBPUSD | GBP | USD | USD per 1 GBP, ~1.2700 | false | 10000 | 5 |
| USDJPY | USD | JPY | JPY per 1 USD, ~157.200 | **true** | 100 | 3 |
| USDCHF | USD | CHF | CHF per 1 USD, ~0.8850 | **true** | 10000 | 5 |

EURJPY and the other crosses are not quoted directly; they live in `CFX_CROSS_RATE` and are
built with `core_fx::cross` through USD.

## Calling this project

```
// value a EUR amount in USD -- EURUSD is not inverted, so a plain multiply
core_fx::convert($amountEur, $eurUsd.mid)

// value a JPY amount in USD -- USDJPY IS inverted
core_fx::convert($amountJpy, core_fx::invert($usdJpy.mid))

// or let the pair decide
core_fx::convertQuoted($amount, $rate.mid, $pair.quotedInverted)
```

No `###Data` element and no Runtime: this project is compiled, not executed.
