# core-instrument

Layer 0, no dependencies. The instrument master: one base class, eight asset-class subtypes
and twelve second-level subtypes, all over the single table `CI_INSTRUMENT`, told apart by a
discriminator column and a `~filter` per subtype set.

**The two names a downstream project needs:** the base class `core_instrument::Instrument`
and the base set id **`[ciBase]`**. Extend the class to add a type; extend the set id to map
it. Every subtype set below extends `[ciBase]` directly or through its asset class.

| element | kind | note |
| --- | --- | --- |
| core_instrument::Instrument | class | BASE class; id, type, name, currency, identifiers, status, dates |
| core_instrument::Equity | class | extends Instrument; share class, shares outstanding, votes, yield |
| core_instrument::Bond | class | extends Instrument; coupon, face value, day count, seniority, call |
| core_instrument::Future | class | extends Instrument; contract month/size, tick, notice dates, margin |
| core_instrument::Option | class | extends Instrument; strike, expiry, style, put/call, underlying |
| core_instrument::Swap | class | extends Instrument; notional, two legs, clearing |
| core_instrument::ExchangeTradedFund | class | extends Instrument; tracked index, expense ratio, NAV, leverage |
| core_instrument::Warrant | class | extends Instrument; strike, exercise ratio, dilution, notice period |
| core_instrument::DepositaryReceipt | class | extends Instrument; underlying, ratio, depositary bank, sponsorship |
| core_instrument::CommonStock | class | extends Equity; residual claim, votes |
| core_instrument::PreferredStock | class | extends Equity; preferred rate, cumulative, liquidation preference |
| core_instrument::GovernmentBond | class | extends Bond; sovereign, inflation linkage, auction date |
| core_instrument::CorporateBond | class | extends Bond; issuer ticker, sector, secured, covenants |
| core_instrument::ConvertibleBond | class | extends Bond; conversion ratio and price, underlying equity |
| core_instrument::CommodityFuture | class | extends Future; delivery location, grade, storage cost |
| core_instrument::EquityIndexFuture | class | extends Future; underlying index, multiplier |
| core_instrument::CallOption | class | extends Option; right to buy |
| core_instrument::PutOption | class | extends Option; right to sell |
| core_instrument::InterestRateSwap | class | extends Swap; reset frequency, index tenor |
| core_instrument::CreditDefaultSwap | class | extends Swap; reference entity, recovery, restructuring |
| core_instrument::TotalReturnSwap | class | extends Swap; reference asset, funding spread |
| core_instrument::InstrumentIdentifier | class | one instrument's identifier under one scheme; own table |
| core_instrument::AssetClassDefinition | class | the asset-class code table, own table |
| core_instrument::AssetClass | enum | EQUITY, FIXED_INCOME, LISTED_DERIVATIVE, OTC_DERIVATIVE, FUND, STRUCTURED |
| core_instrument::InstrumentStatus | enum | ACTIVE, SUSPENDED, MATURED, DELISTED |
| core_instrument::OptionStyle | enum | AMERICAN, EUROPEAN, BERMUDAN |
| core_instrument::SettlementMethod | enum | CASH, PHYSICAL |
| core_instrument::CouponType | enum | FIXED, FLOATING, ZERO, STEP_UP |
| core_instrument::Store | store | tables CI_INSTRUMENT, CI_INSTRUMENT_XREF, CI_ASSET_CLASS |
| core_instrument::Mapping | mapping | root set `[ciBase]`, 20 filtered subtype sets, 5 EnumerationMappings |

## Set ids (extend these; they are a GLOBAL namespace)

`ciBase` (root, `*core_instrument::Instrument`), then `ciEquity`, `ciBond`, `ciFuture`,
`ciOption`, `ciSwap`, `ciEtf`, `ciWarrant`, `ciDepositaryReceipt` — each
`extends [ciBase]` — then `ciCommonStock`, `ciPreferredStock` (extend `[ciEquity]`),
`ciGovernmentBond`, `ciCorporateBond`, `ciConvertibleBond` (extend `[ciBond]`),
`ciCommodityFuture`, `ciEquityIndexFuture` (extend `[ciFuture]`), `ciCallOption`,
`ciPutOption` (extend `[ciOption]`), `ciInterestRateSwap`, `ciCreditDefaultSwap`,
`ciTotalReturnSwap` (extend `[ciSwap]`), plus `ciInstrumentIdentifier` and
`ciAssetClassDefinition` on their own tables.

## Store names

Tables `CI_INSTRUMENT`, `CI_INSTRUMENT_XREF`, `CI_ASSET_CLASS`.
Join `Ci_InstrumentIdentifier`.
Filters `CiEquityRows`, `CiBondRows`, `CiFutureRows`, `CiOptionRows`, `CiSwapRows`,
`CiEtfRows`, `CiWarrantRows`, `CiDepositaryReceiptRows`, `CiCommonStockRows`,
`CiPreferredStockRows`, `CiGovernmentBondRows`, `CiCorporateBondRows`,
`CiConvertibleBondRows`, `CiCommodityFutureRows`, `CiEquityIndexFutureRows`,
`CiCallOptionRows`, `CiPutOptionRows`, `CiInterestRateSwapRows`,
`CiCreditDefaultSwapRows`, `CiTotalReturnSwapRows`.

## Enumeration mappings

`CiAssetClassMapping`, `CiInstrumentStatusMapping`, `CiOptionStyleMapping`,
`CiSettlementMethodMapping`, `CiCouponTypeMapping` — the only place the physical code
strings live. `INSTRUMENT_TYPE` and `INSTRUMENT_SUBTYPE` stay raw `String` on the base
class, because they are the discriminator the filters read, not a classification.

## Discriminator values

`INSTRUMENT_TYPE`: `EQUITY`, `BOND`, `FUTURE`, `OPTION`, `SWAP`, `ETF`, `WARRANT`,
`DEPOSITARY_RECEIPT`.
`INSTRUMENT_SUBTYPE`: `COMMON`, `PREFERRED`, `GOVERNMENT`, `CORPORATE`, `CONVERTIBLE`,
`COMMODITY`, `EQUITY_INDEX`, `IRS`, `CDS`, `TRS`.
`PUT_CALL`: `CALL`, `PUT`.

No `###Data` element and no Runtime, per the contract.
