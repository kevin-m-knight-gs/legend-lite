# index-core

Layer 1. Depends on **core-instrument** and **core-geo**, and on nothing else.
Package root `index_core::`, prefix `IDX_` / `Idx_` / `idx`.

Index constituents and weights, aggregated by region. An index is a rule: the weights are
restruck at a dated **rebalance**, they sum to 100 as of that rebalance, and a constituent is
an **instrument** (core-instrument) whose issuer sits in a **country** (core-geo). The
project is the join between those two dependencies and holds no copy of either — no ISIN, no
issuer name, no sub-region.

The thing downstream projects come here for is the **aggregation**: index weight by region
and by country, per rebalance, as two store `~groupBy` views. That is what a tracking fund
actually reads — a regional cap, a currency hedge ratio and a settlement headache are all
decided off a rollup row and none of them off the constituent list.

## Elements

| element | kind | note |
| --- | --- | --- |
| `index_core::IdxProvider` | class | benchmark administrator (MSCI, FTSE Russell); authorised flag |
| `index_core::IdxIndexFamily` | class | a range sharing one methodology; where shared rules live |
| `index_core::IdxIndex` | class | the index: currency, base date/level, weighting scheme, rebalance frequency |
| `index_core::IdxEligibilityRule` | class | a screen a name must pass; versioned by `appliesFrom` |
| `index_core::IdxWeightCap` | class | UCITS 5/10/40 and friends — single-name AND group cap, both needed |
| `index_core::IdxRebalance` | class | the dated review. `announcementDate` and `effectiveDate` are different dates |
| `index_core::IdxConstituent` | class | **the grain**: one instrument in one index at one rebalance, and its weight |
| `index_core::IdxRegionWeight` | class | **AGGREGATION** — weight by region per rebalance, over view `IDX_REGION_WEIGHT` |
| `index_core::IdxCountryWeight` | class | **AGGREGATION** — weight by issuer country, joinable to `core_geo::CgCountry` |
| `index_core::IdxIndexLevel` | class | published level per (index, date): price, gross return, net return, divisor |
| `index_core::IdxTrackingFund` | class | the fund with the mandate to hold the index |
| `index_core::IdxFundHolding` | class | what the fund holds vs what the index says; `activeWeightBps` is the point |
| `index_core::Store` | store | includes both dependency stores; 11 tables, 2 views, 15 joins, 4 filters |
| `index_core::Mapping` | mapping | includes both dependency mappings; 12 class sets, 15 association sets |

12 classes, 15 associations.

## Associations — the ends a downstream project navigates

Internal:

| on class | property | type |
| --- | --- | --- |
| `IdxIndexFamily` | `provider` | `IdxProvider[1]` |
| `IdxProvider` | `families` | `IdxIndexFamily[*]` |
| `IdxIndex` | `family` | `IdxIndexFamily[1]` |
| `IdxIndexFamily` | `indices` | `IdxIndex[*]` |
| `IdxIndex` | `rebalances`, `eligibilityRules`, `weightCaps`, `levels`, `trackingFunds` | `[*]` each |
| `IdxRebalance`, `IdxEligibilityRule`, `IdxWeightCap`, `IdxIndexLevel`, `IdxTrackingFund` | `index` | `IdxIndex[1]` |
| `IdxRebalance` | `constituents` | `IdxConstituent[*]` |
| `IdxConstituent` | `rebalance` | `IdxRebalance[1]` |
| `IdxRebalance` | `regionWeights` | `IdxRegionWeight[*]` |
| `IdxRebalance` | `countryWeights` | `IdxCountryWeight[*]` |
| `IdxRegionWeight`, `IdxCountryWeight` | `rebalance` | `IdxRebalance[1]` |
| `IdxTrackingFund` | `holdings` | `IdxFundHolding[*]` |
| `IdxFundHolding` | `fund` | `IdxTrackingFund[1]` |

**Crossing into the dependencies** — these four are the reason the project exists. Note the
ends added to the UPSTREAM classes: an instrument and a country gain index properties without
core-instrument or core-geo knowing indices exist.

| association | this end | upstream end |
| --- | --- | --- |
| `IdxConstituentInstrument` | `IdxConstituent.instrument: core_instrument::Instrument[0..1]` | `Instrument.indexMemberships: IdxConstituent[*]` |
| `IdxFundHoldingInstrument` | `IdxFundHolding.instrument: core_instrument::Instrument[0..1]` | `Instrument.fundHoldings: IdxFundHolding[*]` |
| `IdxConstituentIssuerCountry` | `IdxConstituent.issuerCountry: core_geo::CgCountry[0..1]` | `CgCountry.issuedConstituents: IdxConstituent[*]` |
| `IdxCountryWeightCountry` | `IdxCountryWeight.country: core_geo::CgCountry[0..1]` | `CgCountry.indexCountryWeights: IdxCountryWeight[*]` |

`[0..1]` on every upstream end deliberately: an index file arrives before the instrument
master has the name, and an unmatched line is a mapping break to be worked, not a row to be
rejected.

Four hops to a coverage region, using core-geo's own chain and declaring no join for it:

    $constituent.issuerCountry.subRegion.macroRegion.code   // live classification
    $constituent.regionCode                                 // what the vendor said that day

They are allowed to differ, and when they do the index has been reclassified.

## Derived properties

| on class | property | note |
| --- | --- | --- |
| `IdxRebalance.totalWeightPct()` | `Float[1]` | `$this.constituents.weightPct->sum()` — 100 for a complete review |
| `IdxConstituent.weightFraction()` | `Float[1]` | `weightPct / 100.0`; `Float` because `/` widens |
| `IdxConstituent.isCapped()` | `Boolean[1]` | whether a cap bound this name |

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `IDX_PROVIDER` | `PROVIDER_ID` | |
| `IDX_INDEX_FAMILY` | `FAMILY_ID` | FK `PROVIDER_ID` |
| `IDX_INDEX` | `INDEX_ID` | FK `FAMILY_ID` |
| `IDX_ELIGIBILITY_RULE` | `RULE_ID` | FK `INDEX_ID` |
| `IDX_WEIGHT_CAP` | `CAP_ID` | FK `INDEX_ID`; `SINGLE_NAME_CAP_PCT` + `GROUP_CAP_PCT` |
| `IDX_REBALANCE` | `REBALANCE_ID` | FK `INDEX_ID` |
| `IDX_CONSTITUENT` | `CONSTITUENT_ID` | FK `REBALANCE_ID`, `INSTRUMENT_ID` → `CI_INSTRUMENT`, `ISSUER_COUNTRY_CODE` → `CG_COUNTRY`; plus `REGION_CODE`, the vendor's label |
| `IDX_INDEX_LEVEL` | `INDEX_ID` + `LEVEL_DATE` | composite |
| `IDX_TRACKING_FUND` | `FUND_ID` | FK `INDEX_ID` |
| `IDX_FUND_HOLDING` | `HOLDING_ID` | FK `FUND_ID`, `INSTRUMENT_ID` → `CI_INSTRUMENT` |
| `IDX_REGION_WEIGHT` | `REBALANCE_ID` + `REGION_CODE` | **VIEW**, `~groupBy` over `IDX_CONSTITUENT`: `sum(WEIGHT_PCT)`, `count(CONSTITUENT_ID)` |
| `IDX_COUNTRY_WEIGHT` | `REBALANCE_ID` + `ISSUER_COUNTRY_CODE` | **VIEW**, same rollup grouped on the core-geo FK |

The store carries `include core_instrument::Store` and `include core_geo::Store`, which is
what lets its joins name `CI_INSTRUMENT` and `CG_COUNTRY` unqualified.

A grouping produces no row for an empty group: a region with no constituents at a rebalance
is absent, not zero. Anything needing the zero must outer-join from its own region list.

Joins, all many-to-one: `Idx_FamilyProvider`, `Idx_IndexFamily`, `Idx_IndexRebalance`,
`Idx_RebalanceConstituent`, `Idx_IndexRule`, `Idx_IndexCap`, `Idx_IndexLevel`,
`Idx_IndexFund`, `Idx_FundHolding`, `Idx_RebalanceRegionWeight`,
`Idx_RebalanceCountryWeight`, and the four that leave the project —
`Idx_ConstituentInstrument`, `Idx_HoldingInstrument`, `Idx_ConstituentCountry`,
`Idx_CountryWeightCountry`.

Filters, declared and unapplied, for a downstream mapping to reference:
`IdxLiveIndexRows` (`IS_ACTIVE = 1`), `IdxSurvivingConstituentRows` (`IS_DELETION = 0` — a
deletion row states that a name left and double-counts its weight if kept),
`IdxPlacedConstituentRows` (`ISSUER_COUNTRY_CODE is not null`),
`IdxEffectiveRebalanceRows` (`STATUS = 'EFFECTIVE'`).

## Set ids (a GLOBAL namespace — extend or target these by name)

`idxProvider`, `idxIndexFamily`, `idxIndex`, `idxEligibilityRule`, `idxWeightCap`,
`idxRebalance`, `idxConstituent`, `idxRegionWeight`, `idxCountryWeight`, `idxIndexLevel`,
`idxTrackingFund`, `idxFundHolding`. All are root sets (`*`), one per class.

`index_core::Mapping` includes `core_instrument::Mapping` and `core_geo::Mapping`, so a
downstream mapping that includes this one inherits `[ciBase]`, the 20 instrument subtype sets
and the 11 `cg*` sets as well. A downstream `AssociationMapping` end pointing into this
project must name one of the `idx*` ids above; the default ids do not exist.

Tables are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py index-core   # compiles
