# product-core

Layer 1. Depends on **core-instrument** and **core-types**, and nothing else.

The product taxonomy: what the firm SELLS, as opposed to what the market trades. Every class
here extends `core_instrument::Instrument` — a product *is* an instrument — and every mapping
set extends core-instrument's base set **`[ciBase]`**, directly (`prdProduct`) or through one
of the five family sets. 26 classes: a root, 5 families, 17 concrete products and 3
governance classes on their own tables.

Prefixes: elements `Prd`, tables `PRD_`, joins `Prd_`, filters `Prd`, set ids `prd`.

| element | kind | note |
| --- | --- | --- |
| product_core::Product | class | ROOT of the taxonomy; **extends `core_instrument::Instrument`**. Catalogue key, family, target market, charges, risk indicator, approvals. `<<core_types::CtGovernance.reviewed>>` |
| product_core::VanillaProduct | class | extends Product; linear payoff, no embedded optionality |
| product_core::StructuredProduct | class | extends Product; payoff engineered from embedded derivatives, KID required |
| product_core::FundProduct | class | extends Product; pooled vehicle, UCITS flag, management and performance fees |
| product_core::FinancingProduct | class | extends Product; collateral, haircut, term, triparty |
| product_core::DepositProduct | class | extends Product; balance-sheet liability, insurance scheme, break terms |
| product_core::ListedVanillaProduct | class | extends VanillaProduct; venue MIC, exchange code, block threshold, CCP |
| product_core::OtcVanillaProduct | class | extends VanillaProduct; master agreement, CSA, confirmation template |
| product_core::DeltaOneProduct | class | extends VanillaProduct; synthetic wrapper, funding spread, replication basket |
| product_core::CapitalProtectedNote | class | extends StructuredProduct; protection level and provider, conditional flag |
| product_core::YieldEnhancementProduct | class | extends StructuredProduct; enhanced coupon, downside barrier, autocall |
| product_core::ParticipationProduct | class | extends StructuredProduct; participation rate, cap level, currency hedge |
| product_core::LeveragedProduct | class | extends StructuredProduct; leverage factor, knock-out, stop loss |
| product_core::CreditLinkedNote | class | extends StructuredProduct; reference entity, credit event set, recovery |
| product_core::MutualFundProduct | class | extends FundProduct; share class, accumulating flag, benchmark |
| product_core::HedgeFundProduct | class | extends FundProduct; strategy style, lock-up, notice, high-water mark |
| product_core::ExchangeTradedFundProduct | class | extends FundProduct; replication style, listing MIC, creation unit, APs |
| product_core::MoneyMarketFundProduct | class | extends FundProduct; NAV type (CNAV/LVNAV/VNAV), WAM, liquidity floor |
| product_core::RepoProduct | class | extends FinancingProduct; repo rate, open flag, GC basket, repurchase date |
| product_core::SecuritiesLendingProduct | class | extends FinancingProduct; lending fee, exclusivity, recall notice |
| product_core::MarginLoanProduct | class | extends FinancingProduct; advance rate, call trigger, committed, limit |
| product_core::TermDepositProduct | class | extends DepositProduct; fixed rate, rollover instruction, negotiable |
| product_core::StructuredDepositProduct | class | extends DepositProduct; linked index, min/max return, participation |
| product_core::ProductApproval | class | one committee decision; own table, composite key (product, approval ref) |
| product_core::RegulatoryCategory | class | MiFID/PRIIPs classification code table; own table |
| product_core::ProductRiskIndicator | class | periodic PRIIPs risk-indicator observation; own table, keyed (product, date) |
| product_core::Store | store | `include core_instrument::Store`; tables PRD_PRODUCT, PRD_APPROVAL, PRD_REG_CATEGORY, PRD_RISK_INDICATOR |
| product_core::Mapping | mapping | `include core_instrument::Mapping`; 23 sets extending `[ciBase]`, 3 own-table sets, 2 EnumerationMappings |

## Set ids (extend these; they are a GLOBAL namespace)

`prdProduct` **extends `[ciBase]`** — the id a downstream project extends to add a product
type. Then the five families, each `extends [prdProduct]`:

`prdVanilla`, `prdStructured`, `prdFund`, `prdFinancing`, `prdDeposit`

and the seventeen concrete sets, each extending its family:

- `[prdVanilla]` → `prdListedVanilla`, `prdOtcVanilla`, `prdDeltaOne`
- `[prdStructured]` → `prdCapitalProtected`, `prdYieldEnhancement`, `prdParticipation`, `prdLeveraged`, `prdCreditLinked`
- `[prdFund]` → `prdMutualFund`, `prdHedgeFund`, `prdEtfProduct`, `prdMoneyMarketFund`
- `[prdFinancing]` → `prdRepo`, `prdSecuritiesLending`, `prdMarginLoan`
- `[prdDeposit]` → `prdTermDeposit`, `prdStructuredDeposit`

plus `prdApproval`, `prdRegulatoryCategory`, `prdRiskIndicator` on their own tables.

There is no default set id anywhere in this project — every set is named — so a downstream
`extends [...]` or `AssociationMapping` must use the ids above, not `product_core_Product`.

## Store names

Tables `PRD_PRODUCT` (the wide product-control overlay, keyed by `INSTRUMENT_ID`),
`PRD_APPROVAL`, `PRD_REG_CATEGORY`, `PRD_RISK_INDICATOR`.

Joins `Prd_ProductInstrument` (`CI_INSTRUMENT.INSTRUMENT_ID = PRD_PRODUCT.INSTRUMENT_ID`),
`Prd_ProductApproval`, `Prd_ProductRegCategory`, `Prd_ProductRiskIndicator`.

Filters `PrdCatalogueRows`, then `PrdVanillaProductRows`, `PrdStructuredProductRows`,
`PrdFundProductRows`, `PrdFinancingProductRows`, `PrdDepositProductRows`,
`PrdListedVanillaRows`, `PrdOtcVanillaRows`, `PrdDeltaOneRows`, `PrdCapitalProtectedRows`,
`PrdYieldEnhancementRows`, `PrdParticipationRows`, `PrdLeveragedRows`, `PrdCreditLinkedRows`,
`PrdMutualFundRows`, `PrdHedgeFundRows`, `PrdEtfProductRows`, `PrdMoneyMarketFundRows`,
`PrdRepoRows`, `PrdSecuritiesLendingRows`, `PrdMarginLoanRows`, `PrdTermDepositRows`,
`PrdStructuredDepositRows`.

## Discriminator values (all NEW; none collide with core-instrument's)

The rows are `CI_INSTRUMENT` rows and the discriminator is core-instrument's own, so this
project only adds values it does not already use. A manufactured product is its own
instrument row with its own ISIN, so it never competes with an `EQUITY` or a `BOND` row.

`INSTRUMENT_TYPE` (the five families): `VANILLA_PRODUCT`, `STRUCTURED_PRODUCT`,
`FUND_PRODUCT`, `FINANCING_PRODUCT`, `DEPOSIT_PRODUCT`.
*(core-instrument holds `EQUITY`, `BOND`, `FUTURE`, `OPTION`, `SWAP`, `ETF`, `WARRANT`,
`DEPOSITARY_RECEIPT`.)*

`INSTRUMENT_SUBTYPE` (the seventeen concrete products): `LISTED_VANILLA`, `OTC_VANILLA`,
`DELTA_ONE`, `CAPITAL_PROTECTED`, `YIELD_ENHANCEMENT`, `PARTICIPATION`, `LEVERAGED`,
`CREDIT_LINKED`, `MUTUAL_FUND`, `HEDGE_FUND`, `ETF_PRODUCT`, `MONEY_MARKET_FUND`, `REPO`,
`SEC_LENDING`, `MARGIN_LOAN`, `TERM_DEPOSIT`, `STRUCTURED_DEPOSIT`.
*(core-instrument holds `COMMON`, `PREFERRED`, `GOVERNMENT`, `CORPORATE`, `CONVERTIBLE`,
`COMMODITY`, `EQUITY_INDEX`, `IRS`, `CDS`, `TRS`.)*

`PRD_PRODUCT.CATALOGUE_STATUS`: `CATALOGUED` is what `PrdCatalogueRows` selects.

## Enumeration mappings

`PrdCurrencyMapping` (`core_types::CtCurrency`, all 25 values) and `PrdCountryMapping`
(`core_types::CtCountry`, all 26 values). core-types exports enums and deliberately no
mapping, so the physical code strings live here, in the project that owns the column.

## Notes for downstream projects

- **The main table of every product set is the dependency's `CI_INSTRUMENT`**, not
  `PRD_PRODUCT`. `PRD_PRODUCT` is an overlay reached through `Prd_ProductInstrument`, so a
  property mapping added downstream must be written
  `[product_core::Store]@Prd_ProductInstrument | PRD_PRODUCT.COL` — and a downstream set
  that maps its OWN table must join from `CI_INSTRUMENT`, not from `PRD_PRODUCT`.
- `prdProduct`'s `~filter` uses the join form
  `~filter [db]@Prd_ProductInstrument | [db]PrdCatalogueRows`. Both database pointers are
  required; `~filter [db]@Join | FilterName` is a parse error (`Unexpected token`).
- `Product.ongoingChargeRate()` calls `core_types::ctBasisPointsToRate` and is `Float[1]`; it
  uses `->toOne()` on an optional column, so it is a compile-time convenience, not a
  null-safe accessor.
- To add a product type: a class extending `product_core::Product` (or a family), a `Filter`
  in your own store on `CI_INSTRUMENT.INSTRUMENT_SUBTYPE` with a value not listed above, and
  a set `extends [prdProduct]` (or `[prdVanilla]`, …) in your own mapping.

No `###Data` element and no Runtime, per the contract.
