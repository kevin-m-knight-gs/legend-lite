# product-catalogue

Layer 3. Package root `product_catalogue::`, prefix `PCT_` / `Pct_` / `pct`.
Depends on **product-pricing** and **static-distribution**, and on nothing else.

The product catalogue as distributed to sales. Not what the firm manufactures and not how it
is priced — both of those arrive inherited — but what a salesperson may actually offer: the
approved products, their target market, the client categories they may be sold to, the
documentation each needs, the jurisdictions they may not be sold in, and the ones withdrawn
but still held.

Exports **classes, a store and a mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

28 classes, 21 tables, 32 joins, 12 filters, 28 mapping sets (2 of them `~distinct`).

## The overlay, four projects deep

    CI_INSTRUMENT             core-instrument   what the market trades
      -> PRD_PRODUCT          product-core      what the firm sells
      -> PPR_PRODUCT_PRICING  product-pricing   how the firm prices it
      -> pct.PCT_CATALOGUE_ENTRY                what sales may actually offer

All four hang off `CI_INSTRUMENT`, not off each other: the main table of every set below
`[pprPricedProduct]` is `CI_INSTRUMENT`, so this project's columns are reached through
`@Pct_EntryInstrument` and every catalogue property mapping is written through it.

## Four schemas in one database

`product_catalogue::Store` includes both dependency stores, so a downstream project that
includes it sees:

| schema | whose | how to reference |
| --- | --- | --- |
| `pct` | ours | `[product_catalogue::Store]pct.PCT_CATALOGUE_ENTRY.SALES_STATUS` |
| `sdi` | static-distribution's | `[static_distribution::Store]sdi.SDI_ACK.ACKNOWLEDGED_AT` |
| `stc` / `uom` | static-core's and core-units', transitively — visible, unused here |
| *(default)* | `CI_*`, `PRD_*`, `PPR_*`, `VC_*`, `CP_*`, `CF_*`, `RD_*`, `CG_*` |

core-instrument arrives **twice** (product-pricing → product-core → core-instrument, and
static-distribution → reference-data → core-instrument). A diamond include is fine and
resolves to one. Do not add `include core_instrument::Store` or `include product_core::Store`
on top: redundant, and a reference to an undeclared dependency.

`product_catalogue::Mapping` includes `product_pricing::Mapping` and
`static_distribution::Mapping`; those carry `product_core::Mapping`, `valuation_core::Mapping`,
`static_core::Mapping`, `reference_data::Mapping`, `core_instrument::Mapping`,
`core_geo::Mapping`, `core_units::Mapping`, `core_price::Mapping` and `core_fx::Mapping`
transitively — do not include any of them again.

## Exports

| element | kind | note |
| --- | --- | --- |
| `product_catalogue::PctCatalogueProduct` | class | ROOT of the catalogue overlay; **extends `product_pricing::PprPricedProduct`**. Sales status, catalogue, channel, desk, target market, minimum ticket, listing dates. `isListed()`, `hasJurisdictionBar()`, `minimumTicketInThousands(): Float[1]`, `salesLabel()` |
| `product_catalogue::PctOfferableProduct` | class | extends PctCatalogueProduct; offerable today with no further permission — quote turnaround, self-service. `isImmediateQuote()` |
| `product_catalogue::PctRestrictedProduct` | class | extends PctCatalogueProduct; sellable only with a named sign-off — approver role, review date, monthly client cap. `hasReviewDate()` |
| `product_catalogue::PctWithdrawnProduct` | class | extends PctCatalogueProduct; **withdrawn but still held** — run-off end, successor. `isStillHeld()` |
| `product_catalogue::PctPipelineProduct` | class | extends PctCatalogueProduct; approved in principle, not yet launched. `isBlocked()` |
| `product_catalogue::PctCataloguedAutocall` | class | **extends `product_pricing::PprPricedAutocall`** (the other branch); headline coupon, capital-at-risk flag, illustration count. `headlineCouponRate(): Float[1]` |
| `product_catalogue::PctCatalogue` | class | one named edition for one audience and region; reaches `static_distribution::SdiDataset`. `isLive()` |
| `product_catalogue::PctCatalogueVersion` | class | one published version; reaches `static_distribution::SdiPublication`. `isCurrent()`, `offerableShare(): Float[1]` |
| `product_catalogue::PctCatalogueLine` | class | one product's line on one version's pages; composite PK. `isMarkedForRemoval()` |
| `product_catalogue::PctCatalogueDistribution` | class | one version sent to one static-distribution consumer; `acknowledgement` targets **`sdiAck`**. Composite PK. `wasDelivered()` |
| `product_catalogue::PctSalesChannel` | class | the route to the client: voice, adviser, digital, third party. `isOpen()` |
| `product_catalogue::PctSalesDesk` | class | the desk owning the client relationship; `manufacturer` targets the `~distinct` pricing-desk set. `isOpen()` |
| `product_catalogue::PctClientCategory` | class | retail / professional / eligible counterparty, with the tests each pulls in |
| `product_catalogue::PctCategoryEligibility` | class | one verdict per (product, category), with rationale and review date; composite PK. `mayBeSold()` |
| `product_catalogue::PctTargetMarket` | class | the positive target market as a five-part profile. `horizonYears(): Float[1]`, `canLoseMoreThanCapital()` |
| `product_catalogue::PctNegativeTarget` | class | who the product must NOT be sold to; composite PK, `isHardBar` |
| `product_catalogue::PctDistributionStrategy` | class | advised / execution-only / portfolio management, and which tests each requires |
| `product_catalogue::PctJurisdictionBar` | class | one jurisdiction one product may not be sold in; composite PK; `liftedOn` null = in force. `isInForce()` |
| `product_catalogue::PctSalesRestriction` | class | a standing restriction short of a bar; `barredJurisdiction` targets the `~distinct` set. `isInForce()` |
| `product_catalogue::PctWithdrawal` | class | the paperwork behind a withdrawal: reason, author, run-off plan. `isStillHeld()` |
| `product_catalogue::PctDocumentRequirement` | class | what documentation a product needs before sale; composite PK. `maxAgeMonths(): Float[1]` |
| `product_catalogue::PctDocument` | class | a document instance that exists; `expiresOn` null = current. `isCurrent()` |
| `product_catalogue::PctDocumentLanguage` | class | one translation of one document; composite PK. `isUsable()` |
| `product_catalogue::PctIndicativePrice` | class | the price the catalogue prints, per (product, date); reaches `product_pricing::PprPricingRun`. `midPrice(): Float[1]`, `offerVsModelPct(): Float[1]` (calls `product_pricing::pprModelVsMarketPct`), `spreadRate(): Float[1]` |
| `product_catalogue::PctCommissionTerm` | class | what the firm earns per (product, channel); composite PK. `upfrontRate(): Float[1]`, `firstYearBasisPoints(): Float[1]` |
| `product_catalogue::PctSalesTraining` | class | the competency a salesperson must hold. `refreshYears(): Float[1]` |
| `product_catalogue::PctBarredJurisdiction` | class | **`~distinct`**, no table — the distinct `JURISDICTION_CODE` across `pct.PCT_JURISDICTION_BAR`. `label()` |
| `product_catalogue::PctManufacturingDesk` | class | **`~distinct`**, no table — the distinct `PRICING_DESK` across product-pricing's `PPR_PRODUCT_PRICING`. `label()` |
| `product_catalogue::Store` | store | includes `product_pricing::Store` and `static_distribution::Store`; schema `pct` with 21 tables, 32 joins, 12 filters |
| `product_catalogue::Mapping` | mapping | includes both dependency mappings; 28 sets, 2 of them `~distinct`, 6 of them `extends [...]` across a project boundary |

## The two `~distinct` sets

Neither has a table of its own. Each collapses a column that repeats on every row of a table
that is keyed on something else.

    *product_catalogue::PctBarredJurisdiction[pctBarredJurisdiction]: Relational
    {
       ~distinct
       ~primaryKey ( [product_catalogue::Store]pct.PCT_JURISDICTION_BAR.JURISDICTION_CODE )
       ~mainTable [product_catalogue::Store]pct.PCT_JURISDICTION_BAR
       jurisdictionCode: [product_catalogue::Store]pct.PCT_JURISDICTION_BAR.JURISDICTION_CODE,
       jurisdictionName: [product_catalogue::Store]pct.PCT_JURISDICTION_BAR.JURISDICTION_NAME,
       jurisdictionRegion: [product_catalogue::Store]pct.PCT_JURISDICTION_BAR.JURISDICTION_REGION
    }

| set | deduplicates on | source table (its own PK) | why it collapses |
| --- | --- | --- | --- |
| `pctBarredJurisdiction` | `pct.PCT_JURISDICTION_BAR.JURISDICTION_CODE` | `pct.PCT_JURISDICTION_BAR` (`INSTRUMENT_ID`, `JURISDICTION_CODE`) | one row per (product, jurisdiction): a country barred for forty products is forty rows there and one here |
| `pctManufacturingDesk` | `PPR_PRODUCT_PRICING.PRICING_DESK` | `PPR_PRODUCT_PRICING` (`INSTRUMENT_ID`) | the desk name is stamped on every priced product: a desk pricing three hundred products is three hundred rows there and one here |

`~primaryKey` names the **collapsed** column, never the source table's own key. Keying
`pctBarredJurisdiction` on the full `(INSTRUMENT_ID, JURISDICTION_CODE)` pair, or
`pctManufacturingDesk` on `INSTRUMENT_ID`, would leave one row per source row and the
`~distinct` would collapse nothing — and nothing in the graph would say so. Directive order is
`~distinct`, `~primaryKey`, `~mainTable`.

`pctBarredJurisdiction` shares its main table with `pctJurisdictionBar`, which maps the same
table at its own full grain. Two sets, one table, two different answers to "what is a row" —
which is the whole point of `~distinct`.

Both are legitimate join targets: `PctSalesRestriction.barredJurisdiction[pctBarredJurisdiction]`
goes through `@Pct_RestrictionJurisdiction`, and `PctSalesDesk.manufacturer[pctManufacturingDesk]`
through `@Pct_DeskManufacturer`, which crosses the project boundary into product-pricing's
default schema. A property mapped onto a `~distinct` set compiles.

## Inheritance across the project boundary

Six sets `extend` a set that lives in another project or in this one's own root:

| set | extends | filter column |
| --- | --- | --- |
| `pctProduct` | **`[pprPricedProduct]`** (product-pricing) | `LISTED_ON is not null` |
| `pctOfferable` | `[pctProduct]` | `SALES_STATUS = 'OFFERABLE'` |
| `pctRestricted` | `[pctProduct]` | `SALES_STATUS = 'RESTRICTED'` |
| `pctWithdrawn` | `[pctProduct]` | `SALES_STATUS = 'WITHDRAWN'` |
| `pctPipeline` | `[pctProduct]` | `SALES_STATUS = 'PIPELINE'` |
| `pctAutocall` | **`[pprAutocall]`** (product-pricing) | `SALES_WRAPPER = 'AUTOCALL_NOTE'` |

`pctProduct` → `pprPricedProduct` → `prdProduct` → `ciBase` is five links and three project
boundaries on one row. The two discriminators are deliberately different columns:
`SALES_STATUS` cuts the four status subtypes, `SALES_WRAPPER` cuts the one concrete payoff,
which sits on a different branch of the taxonomy and must not be cut by the same column twice.

Every property a subclass adds is mapped on its OWN set. A property declared on a subclass
cannot be mapped on the parent's set — which is why `PctWithdrawnProduct`'s five columns are
on `[pctWithdrawn]` and not on `[pctProduct]`.

## Set ids (a GLOBAL namespace — name these explicitly, do not redeclare them)

`pctBarredJurisdiction`, `pctManufacturingDesk` (the two `~distinct` sets), then
`pctProduct`, `pctOfferable`, `pctRestricted`, `pctWithdrawn`, `pctPipeline`, `pctAutocall`,
`pctCatalogue`, `pctVersion`, `pctLine`, `pctDistribution`, `pctChannel`, `pctSalesDesk`,
`pctClientCategory`, `pctEligibility`, `pctTargetMarket`, `pctNegativeTarget`, `pctStrategy`,
`pctJurisdictionBar`, `pctRestriction`, `pctWithdrawal`, `pctDocRequirement`, `pctDocument`,
`pctDocLanguage`, `pctIndicativePrice`, `pctCommission`, `pctTraining`.

Note `pctRestricted` (the product subtype) and `pctRestriction` (the standing restriction) are
two different sets. All ids are explicit, so the DEFAULT ids
(`product_catalogue_PctCatalogueProduct`) do not exist: a downstream `extends [...]`,
`AssociationMapping` end or class-valued property mapping must name the id above.

## Tables — all in schema `pct`

| table | primary key | note |
| --- | --- | --- |
| `pct.PCT_CATALOGUE_ENTRY` | `INSTRUMENT_ID` VARCHAR(60) | the wide overlay; `SALES_STATUS` and `SALES_WRAPPER` are the two discriminators; `LISTED_ON`, `DE_LISTED_ON` |
| `pct.PCT_CATALOGUE` | `CATALOGUE_CODE` VARCHAR(20) | FK `DATASET_CODE` → `sdi`; `AUDIENCE`, `RETIRED_ON` |
| `pct.PCT_CATALOGUE_VERSION` | `VERSION_ID` VARCHAR(40) | FKs `CATALOGUE_CODE`, `PUBLICATION_ID` → `sdi`; `SUPERSEDED_ON` null = current |
| `pct.PCT_CATALOGUE_LINE` | `VERSION_ID`, `INSTRUMENT_ID` | `POSITION_NUMBER`, `PRINTED_OFFER_PRICE`, `REMOVAL_NOTE` |
| `pct.PCT_DISTRIBUTION` | `VERSION_ID`, `CONSUMER_ID` | `PUBLICATION_ID` + `CONSUMER_ID` is the key of `sdi.SDI_ACK`; `FAILURE_CODE` null = delivered |
| `pct.PCT_CHANNEL` | `CHANNEL_CODE` VARCHAR(12) | `IS_ADVISED`, `NEEDS_SUITABILITY_TEST`, `RETIRED_ON` |
| `pct.PCT_SALES_DESK` | `DESK_CODE` VARCHAR(20) | `MANUFACTURING_DESK` VARCHAR(60) matches `PPR_PRODUCT_PRICING.PRICING_DESK` |
| `pct.PCT_CLIENT_CATEGORY` | `CATEGORY_CODE` VARCHAR(24) | `MAX_COMPLEXITY_SCORE` |
| `pct.PCT_CATEGORY_ELIGIBILITY` | `INSTRUMENT_ID`, `CATEGORY_CODE` | `ELIGIBILITY_STATUS`, `REVIEW_DUE_ON` |
| `pct.PCT_TARGET_MARKET` | `TARGET_MARKET_CODE` VARCHAR(20) | `HORIZON_MONTHS`, `LOSS_BEARING` |
| `pct.PCT_NEGATIVE_TARGET` | `TARGET_MARKET_CODE`, `EXCLUSION_CODE` | `IS_HARD_BAR` |
| `pct.PCT_DISTRIBUTION_STRATEGY` | `STRATEGY_CODE` VARCHAR(20) | `COOLING_OFF_DAYS` |
| `pct.PCT_JURISDICTION_BAR` | `INSTRUMENT_ID`, `JURISDICTION_CODE` | `JURISDICTION_NAME`/`_REGION` denormalised per jurisdiction; `LIFTED_ON` null = in force |
| `pct.PCT_SALES_RESTRICTION` | `RESTRICTION_ID` VARCHAR(40) | FKs `CHANNEL_CODE`, `JURISDICTION_CODE`; `EXPIRES_ON` |
| `pct.PCT_WITHDRAWAL` | `WITHDRAWAL_ID` VARCHAR(40) | `HOLDINGS_MAY_CONTINUE`, `RUNOFF_END_ON` |
| `pct.PCT_DOC_REQUIREMENT` | `INSTRUMENT_ID`, `DOCUMENT_TYPE` | `IS_PRE_SALE`, `MAX_AGE_DAYS`, `WAIVER_STATUS` |
| `pct.PCT_DOCUMENT` | `DOCUMENT_ID` VARCHAR(40) | `EXPIRES_ON` null = current |
| `pct.PCT_DOC_LANGUAGE` | `DOCUMENT_ID`, `LANGUAGE_CODE` | `IS_VERIFIED` |
| `pct.PCT_INDICATIVE_PRICE` | `INSTRUMENT_ID`, `AS_OF_DATE` | FK `RUN_ID` → `PPR_PRICING_RUN`; `SPREAD_BPS` FLOAT |
| `pct.PCT_COMMISSION` | `INSTRUMENT_ID`, `CHANNEL_CODE` | `UPFRONT_BPS`, `TRAIL_BPS` FLOAT; `MUST_BE_DISCLOSED` |
| `pct.PCT_TRAINING` | `TRAINING_CODE` VARCHAR(20) | `REFRESH_MONTHS`, `NEEDS_EXAM` |

Money is `NUMERIC(20,6)`, rates and ratios are `FLOAT`, bounded counts are `SMALLINT`. No
`REAL` anywhere (docs/UPSTREAM_FINDINGS.md F53).

## Joins

The one the overlay hangs on: `Pct_EntryInstrument`
(`CI_INSTRUMENT.INSTRUMENT_ID = pct.PCT_CATALOGUE_ENTRY.INSTRUMENT_ID`).

From the entry out: `Pct_EntryCatalogue`, `Pct_EntryChannel`, `Pct_EntryDesk`,
`Pct_EntryTargetMarket`, `Pct_EntryStrategy`, `Pct_EntryTraining`.

From `CI_INSTRUMENT` straight to the product-shaped child tables (each keys on
`INSTRUMENT_ID`, so it is one hop, and each is traversed in both directions):
`Pct_InstrumentEligibility`, `Pct_InstrumentBar`, `Pct_InstrumentDocRequirement`,
`Pct_InstrumentDocument`, `Pct_InstrumentRestriction`, `Pct_InstrumentIndicativePrice`,
`Pct_InstrumentCommission`, `Pct_InstrumentWithdrawal`, `Pct_InstrumentLine`.

Inside `pct`: `Pct_CatalogueVersion`, `Pct_LineVersion`, `Pct_DistributionVersion`,
`Pct_EligibilityCategory`, `Pct_NegativeTargetMarket`, `Pct_DocumentRequirement`
(**composite, on one line**: product AND document type), `Pct_DocumentLanguage`,
`Pct_RestrictionChannel`, `Pct_CommissionChannel`, `Pct_RestrictionJurisdiction` (lands on
the `~distinct` jurisdiction set).

Into product-pricing's default schema: `Pct_DeskManufacturer` (lands on the other `~distinct`
set), `Pct_PriceRun`.

Into static-distribution's `sdi`: `Pct_CatalogueDataset`, `Pct_VersionPublication`,
`Pct_DistributionConsumer`, `Pct_DistributionAck` (**composite, on one line**: publication AND
consumer, because that pair is `sdi.SDI_ACK`'s key).

## Filters

Applied by the mapping: `PctListedRows` (`pct.PCT_CATALOGUE_ENTRY.LISTED_ON is not null`),
`PctOfferableRows`, `PctRestrictedRows`, `PctWithdrawnRows`, `PctPipelineRows`,
`PctAutocallWrapperRows`.

Declared and unapplied, for a downstream mapping to reference:
`PctBarInForceRows` (`pct.PCT_JURISDICTION_BAR.LIFTED_ON is null`),
`PctCurrentDocumentRows` (`pct.PCT_DOCUMENT.EXPIRES_ON is null`),
`PctPermittedCategoryRows` (`ELIGIBILITY_STATUS = 'PERMITTED'`),
`PctCurrentVersionRows` (`pct.PCT_CATALOGUE_VERSION.SUPERSEDED_ON is null`),
`PctFailedDistributionRows` (`pct.PCT_DISTRIBUTION.FAILURE_CODE is not null`),
`PctStillHeldRows` (`pct.PCT_WITHDRAWAL.RUNOFF_END_ON is null`).

Reference them as `[product_catalogue::Store]PctBarInForceRows` — the qualifier names the
store the filter LIVES in. None uses a boolean literal: `Filter X(T.IS_ACTIVE = true)` fails
to parse with `Unexpected token 'true'`. A `~filter` reached through a join needs a database
pointer on BOTH sides:
`~filter [product_catalogue::Store]@Pct_EntryInstrument | [product_catalogue::Store]PctListedRows`.

## Properties a downstream project navigates

| on class | property | type | reaches |
| --- | --- | --- | --- |
| `PctCatalogue` | `dataset` | `static_distribution::SdiDataset[0..1]` | target set `sdiDataset` |
| `PctCatalogueVersion` | `publication` | `static_distribution::SdiPublication[0..1]` | target set `sdiPublication` |
| `PctCatalogueDistribution` | `consumer`, `acknowledgement` | `SdiConsumer[0..1]`, `SdiAcknowledgement[0..1]` | target sets `sdiConsumer`, **`sdiAck`**, the latter over a composite join |
| `PctIndicativePrice` | `pricingRun` | `product_pricing::PprPricingRun[0..1]` | target set `pprPricingRun` |
| `PctSalesDesk` | `manufacturer` | `PctManufacturingDesk[0..1]` | the `~distinct` set over `PPR_PRODUCT_PRICING` |
| `PctSalesRestriction` | `barredJurisdiction` | `PctBarredJurisdiction[0..1]` | the `~distinct` set over `PCT_JURISDICTION_BAR` |
| `PctCatalogueProduct` | `catalogue`, `channel`, `salesDesk`, `targetMarketProfile`, `distributionStrategy`, `requiredTraining` | | two-hop, `@Pct_EntryInstrument > @…` |
| `PctCatalogueProduct` | `categoryEligibilities`, `jurisdictionBars`, `documentRequirements`, `documents`, `salesRestrictions`, `indicativePrices`, `commissionTerms` | `[*]` | one hop from `CI_INSTRUMENT` |
| `PctCatalogueLine`, `PctCategoryEligibility`, `PctJurisdictionBar`, `PctSalesRestriction`, `PctWithdrawal`, `PctDocumentRequirement`, `PctIndicativePrice`, `PctCommissionTerm` | `product` | `PctCatalogueProduct[0..1]` | target set `pctProduct`, the same joins traversed the other way |

`PctIndicativePrice.offerVsModelPct()` calls `product_pricing::pprModelVsMarketPct`, which
calls `valuation_core::vcDeviationPct` — so a catalogue break and a pricing break are the same
number computed once, four projects apart.

## Verify

    python3 scripts/projects/check.py product-catalogue   # compiles
