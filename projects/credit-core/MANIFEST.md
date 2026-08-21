# credit-core

Layer 1. Depends on `core-party` and `core-ratings`, and on nothing else. Package root
`credit_core::`, prefixes `CDC_` (tables), `Cdc_` (joins), `Cdc` (filters), `cdc` (set ids).

Credit exposures by obligor and facility, with PD, LGD, EAD and the rating-implied PD curve
that connects them. The one thing to know before depending on this project:

**`credit_core::CdcObligor.obligorRatings` reaches `core_ratings::RatingVersion`, which is
`<<temporal.businesstemporal>>`. That property therefore takes a DATE.** Nothing in
`credit_core::` is itself milestoned; the association is where the temporal obligation
crosses the boundary and lands on whoever asks the question:

    credit_core::CdcObligor.all()->map(o | $o.obligorRatings(%2024-03-31))
    credit_core::CdcObligor.all()->map(o | $o.obligorRatings(%latest))

`$o.obligorRatings` with no date does not compile: *"Error in 'credit_core::CdcObligor': The
property 'obligorRatings' ... "*. `%latest` works because `CR_RATING_MS` declares
`INFINITY_DATE`. `CdcObligor.ratingsAsOf(date)` is the same navigation wrapped in a named
function.

## Exports

| element | kind | note |
| --- | --- | --- |
| credit_core::CdcObligor | class | the credit view of a counterparty; `entityId` is both the core-party key and the key `CR_RATING_MS` is milestoned by |
| credit_core::CdcExposureClass | class | Basel exposure class: PD floor and risk-weight floor live here, not on the obligor |
| credit_core::CdcFacility | class | committed/uncommitted line; carries the credit conversion factor for the undrawn part |
| credit_core::CdcExposure | class | **the grain**: one obligor, one facility, one date, drawn and undrawn held apart |
| credit_core::CdcPdModel | class | a named, versioned, calibrated PD model |
| credit_core::CdcRatingPd | class | **the rating-implied PD mapping**; composite key (modelId, ratingSymbol) |
| credit_core::CdcLgdSegment | class | LGD by seniority/collateral, with a downturn LGD |
| credit_core::CdcRiskMeasure | class | PD, LGD and EAD as applied to one exposure on one date; derived `expectedLoss()` |
| credit_core::CdcGuarantee | class | third-party guarantee; reaches `core_party::LegalEntity` independently of the obligor |
| credit_core::CdcDefaultEvent | class | dated default trigger, with cure |
| credit_core::CdcRecovery | class | recovered cash net of recovery cost — what calibrates LGD |
| credit_core::CdcPortfolio | class | reporting bucket; crosses legal entities on purpose |
| credit_core::CdcObligorRatings | association | **crosses a temporal boundary**: CdcObligor[0..1] `ratedObligor` ↔ core_ratings::RatingVersion[*] `obligorRatings` (dated) |
| credit_core::CdcObligorEntity | association | core_party::LegalEntity[0..1] `obligorEntity` ↔ CdcObligor[*] `creditObligors` |
| credit_core::CdcGuarantorEntity | association | core_party::LegalEntity[0..1] `guarantorEntity` ↔ CdcGuarantee[*] `guaranteesGiven` |
| credit_core::CdcObligorFacilities | association | CdcObligor[0..1] `obligor` ↔ CdcFacility[*] `facilities` |
| credit_core::CdcFacilityExposures | association | CdcFacility[0..1] `facility` ↔ CdcExposure[*] `exposures` |
| credit_core::CdcExposureMeasures | association | CdcExposure[0..1] `measuredExposure` ↔ CdcRiskMeasure[*] `riskMeasures` |
| credit_core::CdcPortfolioExposures | association | CdcPortfolio[0..1] `portfolio` ↔ CdcExposure[*] `portfolioExposures` |
| credit_core::CdcObligorDefaults | association | CdcObligor[0..1] `defaultedObligor` ↔ CdcDefaultEvent[*] `defaultEvents` |
| credit_core::CdcEventRecoveries | association | CdcDefaultEvent[0..1] `defaultEvent` ↔ CdcRecovery[*] `recoveries` |
| credit_core::CdcFacilityGuarantees | association | CdcFacility[0..1] `guaranteedFacility` ↔ CdcGuarantee[*] `guarantees` |
| credit_core::CdcModelRatingPds | association | CdcPdModel[0..1] `pdModel` ↔ CdcRatingPd[*] `ratingPds` |
| credit_core::CdcObligorExposureClass | association | CdcExposureClass[0..1] `exposureClass` ↔ CdcObligor[*] `classifiedObligors` |
| credit_core::Store | store | 12 tables `CDC_*`, 12 joins `Cdc_*`, filter `CdcPerformingObligors`; `include`s both dependency stores |
| credit_core::Mapping | mapping | 12 class sets `cdc*`, 12 association mappings; `include`s both dependency mappings |

## Tables

`CDC_OBLIGOR`, `CDC_EXPOSURE_CLASS`, `CDC_FACILITY`, `CDC_EXPOSURE`, `CDC_PD_MODEL`,
`CDC_RATING_PD`\*, `CDC_LGD_SEGMENT`, `CDC_RISK_MEASURE`, `CDC_GUARANTEE`,
`CDC_DEFAULT_EVENT`, `CDC_RECOVERY`, `CDC_PORTFOLIO`  (\* = composite primary key)

## Joins

Inside: `Cdc_Obligor_Facility`, `Cdc_Facility_Exposure`, `Cdc_Exposure_Measure`,
`Cdc_Portfolio_Exposure`, `Cdc_Obligor_Default`, `Cdc_Event_Recovery`,
`Cdc_Facility_Guarantee`, `Cdc_Model_RatingPd`, `Cdc_Obligor_ExposureClass`

Leaving the project: `Cdc_Obligor_Entity`, `Cdc_Guarantor_Entity`
(both to `CP_LEGAL_ENTITY`), and `Cdc_Obligor_Rating`
(`CDC_OBLIGOR.ENTITY_ID = CR_RATING_MS.ENTITY_ID`, into the milestoned table).

## Set ids

`cdcObligor`, `cdcExposureClass`, `cdcFacility`, `cdcExposure`, `cdcPdModel`, `cdcRatingPd`,
`cdcLgdSegment`, `cdcRiskMeasure`, `cdcGuarantee`, `cdcDefaultEvent`, `cdcRecovery`,
`cdcPortfolio`

## Notes for downstream

- Every set id above is explicit, so the default ids (`credit_core_CdcObligor` and friends)
  do NOT exist. `extends [...]` and any cross-project `AssociationMapping` must name the
  explicit id.
- The join into the milestoned table names **no date column**. `FROM_Z` / `THRU_Z` are the
  engine's; writing the predicate by hand would break `%latest`. The `AssociationMapping`
  end is an ordinary `[cdcObligor, crRatingVersion]: [credit_core::Store]@Cdc_Obligor_Rating`
  — the milestoning needs nothing restated on this side.
- Rating symbols are joined to PDs by VALUE, not by key: `CdcRatingPd.ratingSymbol` holds the
  same unnormalised symbols `core_ratings::RatingVersion.rating` does (`BBB-`, `Baa3`), and
  `agencyId` is what keeps the two agencies' scales apart inside one model.
- Derived properties available: `CdcObligor.hasDefaulted()`, `CdcObligor.ratingsAsOf(date)`,
  `CdcExposure.utilisation()`, `CdcExposure.grossExposure()`, `CdcRatingPd.pdInBasisPoints()`,
  `CdcRiskMeasure.expectedLoss()`, `CdcRecovery.netRecovery()`. The dividing ones are
  `Float[1]`, because `/` widens.
- `CdcGuarantee` reaches `core_party::LegalEntity` by its own join, so a guarantor can be
  rated by exactly the route the obligor is rated by — reuse `Cdc_Guarantor_Entity` then the
  party project's own joins.
- No `###Data`, no Runtime, no seeded rows.
