# reference-data

Layer 1. Package root `reference_data::`, prefix `RD_` / `Rd_` / `rd`.
Depends on **core-instrument** and **core-geo**, and on nothing else.

Reference data over the instrument master. Two things here are worth a downstream project's
attention:

1. **Five eligibility universes that are views of somebody else's table.** Each is a class
   extending `core_instrument::Instrument` whose mapping set `extends [ciBase]` — core-
   instrument's root set — and applies a `~filter` declared in `reference_data::Store` over
   `CI_INSTRUMENT`. No row is copied and no table of ours is involved. This is the pattern
   for "the subset of the master that qualifies for X".
2. **`RdIssuer`, a `~distinct` set with no table at all.** The master carries `ISSUER_NAME`
   on every one of its rows; the distinct values of that column ARE the issuer universe, and
   it is complete by construction — an issuer cannot be missing while any of its instruments
   is in the master. `RdVenueUsage` does the same for `(MIC, currency)`.

## Elements

| element | kind | note |
| --- | --- | --- |
| `reference_data::RdListedInstrument` | class | extends `core_instrument::Instrument`; instruments naming a primary exchange |
| `reference_data::RdRatedInstrument` | class | extends `Instrument`; a rating exists on the master record |
| `reference_data::RdIndexEligibleInstrument` | class | extends `Instrument`; free float published, so index-eligible |
| `reference_data::RdRestrictedInstrument` | class | extends `Instrument`; suspended on the master |
| `reference_data::RdSovereignIssue` | class | extends `Instrument`; issued by a state, not a company |
| `reference_data::RdIssuer` | class | **`~distinct`**; the distinct issuer names across `CI_INSTRUMENT`. No table |
| `reference_data::RdVenueUsage` | class | **`~distinct`**; the distinct (MIC, currency) pairs the master uses. No table |
| `reference_data::RdIssuerProfile` | class | the curated issuer record; reaches `core_geo::CgCountry` |
| `reference_data::RdIssuerIdentifier` | class | one issuer's id under one scheme (LEI, BIC, ticker); composite PK |
| `reference_data::RdIssuerHierarchyLink` | class | the dated parent edge of the issuer tree |
| `reference_data::RdRatingAgency` | class | a recognised agency; ECAI status |
| `reference_data::RdRatingGrade` | class | one grade on one agency's scale; `notchRank` is the comparable value. Composite PK |
| `reference_data::RdInstrumentRating` | class | one agency's dated rating of one instrument; composite PK |
| `reference_data::RdRatingEligibilityRule` | class | the minimum notch a mandate requires, and the split-rating policy |
| `reference_data::RdIndexProvider` | class | who publishes an index |
| `reference_data::RdIndexDefinition` | class | one index: weighting scheme, rebalance frequency |
| `reference_data::RdIndexMembership` | class | instrument in index at a weight, from a date; composite PK |
| `reference_data::RdIndexRebalance` | class | a scheduled reconstitution; announcement vs effective date |
| `reference_data::RdListingVenue` | class | an onboarded venue keyed by ISO 10383 MIC; reaches `core_geo::CgCountry` |
| `reference_data::RdVenueSegment` | class | a segment MIC inside a venue |
| `reference_data::RdVenueTradingSession` | class | when a venue is open; auction windows |
| `reference_data::RdListing` | class | one instrument on one venue — the many-to-many the master cannot hold. Composite PK |
| `reference_data::RdRestrictionReason` | class | why something is restricted, and who may override |
| `reference_data::RdSanctionsProgramme` | class | the regime a restriction derives from |
| `reference_data::RdRestrictedListEntry` | class | one instrument or issuer restricted for a dated period |
| `reference_data::RdClassificationScheme` | class | a taxonomy — CFI, GICS, NACE |
| `reference_data::RdInstrumentClassification` | class | one instrument's code under one scheme; composite PK |
| `reference_data::RdReferenceSource` | class | a vendor or system reference data arrives from |
| `reference_data::RdSourcePrecedence` | class | which source wins which field, by rank; composite PK |
| `reference_data::RdDataQualityRule` | class | a validation over a reference field, as data |
| `reference_data::Store` | store | 23 `RD_` tables, 20 `Rd_` joins, 7 `Rd` filters. Includes both dependency stores |
| `reference_data::Mapping` | mapping | 30 sets. Includes both dependency mappings |

30 classes. No enums, no profiles, no functions, no associations — this project exports
classes, a store and a mapping.

## The cross-project set extension

`reference_data::Mapping` includes `core_instrument::Mapping`, and five of its sets extend
core-instrument's root set **`[ciBase]`**:

    reference_data::RdListedInstrument[rdListed] extends [ciBase]: Relational
    {
       ~filter [reference_data::Store]RdListedRows
       venueMic: [core_instrument::Store]CI_INSTRUMENT.PRIMARY_EXCHANGE_MIC,
       ...
    }

Three facts a downstream project should copy from this:

* The **filter is declared in `reference_data::Store`**, which `include`s
  `core_instrument::Store`, and is written over `[core_instrument::Store]CI_INSTRUMENT`.
  It is referenced as `[reference_data::Store]RdListedRows` — the qualifier names the store
  the filter LIVES in, not the store the table lives in.
* The extending set restates **nothing**: primary key, main table and all twenty inherited
  property mappings come from `[ciBase]`.
* No filter uses a boolean literal. `Filter X(T.IS_ACTIVE = true)` fails to parse, so each
  is a null test or a string comparison.

## The `~distinct` sets

    *reference_data::RdIssuer[rdIssuer]: Relational
    {
       ~distinct
       ~primaryKey ( [core_instrument::Store]CI_INSTRUMENT.ISSUER_NAME )
       ~mainTable [core_instrument::Store]CI_INSTRUMENT
       issuerName: [core_instrument::Store]CI_INSTRUMENT.ISSUER_NAME,
       domicileCountryCode: [core_instrument::Store]CI_INSTRUMENT.COUNTRY_OF_ISSUE
    }

Directive order is `~distinct`, `~primaryKey`, `~mainTable`. `~primaryKey` names the
COLLAPSED columns, not the table's own `INSTRUMENT_ID` — keying on the table PK would leave
one row per instrument and the `~distinct` would collapse nothing.

## Set ids (a GLOBAL namespace — extend these, do not redeclare them)

`rdListed`, `rdRated`, `rdIndexEligible`, `rdRestricted`, `rdSovereign` (the five that
`extends [ciBase]`), `rdIssuer`, `rdVenueUsage` (the two `~distinct` sets), then
`rdIssuerProfile`, `rdIssuerIdentifier`, `rdIssuerHierarchyLink`, `rdRatingAgency`,
`rdRatingGrade`, `rdInstrumentRating`, `rdRatingRule`, `rdIndexProvider`, `rdIndex`,
`rdIndexMembership`, `rdIndexRebalance`, `rdVenue`, `rdVenueSegment`, `rdVenueSession`,
`rdListing`, `rdRestrictionReason`, `rdSanctionsProgramme`, `rdRestrictedEntry`,
`rdClassificationScheme`, `rdInstrumentClassification`, `rdReferenceSource`,
`rdSourcePrecedence`, `rdDqRule`.

Every one of them is explicit, so the DEFAULT ids (`reference_data_RdIssuer`) do not exist.
A downstream `extends [...]`, `AssociationMapping` end or class-valued property mapping must
name the explicit id above.

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `RD_ISSUER` | `ISSUER_ID` | `LEGAL_NAME` is what joins back to the `~distinct` issuer set |
| `RD_ISSUER_XREF` | `ISSUER_ID`, `SCHEME` | |
| `RD_ISSUER_HIERARCHY` | `LINK_ID` | FKs `CHILD_ISSUER_ID`, `PARENT_ISSUER_ID` |
| `RD_RATING_AGENCY` | `AGENCY_ID` | |
| `RD_RATING_GRADE` | `AGENCY_ID`, `GRADE_CODE` | `NOTCH_RANK` is the only cross-agency comparable |
| `RD_INSTRUMENT_RATING` | `INSTRUMENT_ID`, `AGENCY_ID`, `RATING_DATE` | |
| `RD_RATING_RULE` | `RULE_ID` | |
| `RD_INDEX_PROVIDER` | `PROVIDER_ID` | |
| `RD_INDEX` | `INDEX_ID` | FK `PROVIDER_ID` |
| `RD_INDEX_MEMBERSHIP` | `INDEX_ID`, `INSTRUMENT_ID`, `EFFECTIVE_DATE` | |
| `RD_INDEX_REBALANCE` | `REBALANCE_ID` | FK `INDEX_ID` |
| `RD_VENUE` | `MIC` | `COUNTRY_CODE` is the alpha-2 core-geo keys on; no other geography column |
| `RD_VENUE_SEGMENT` | `SEGMENT_MIC` | FK `MIC` |
| `RD_VENUE_SESSION` | `SESSION_ID` | FK `MIC` |
| `RD_LISTING` | `INSTRUMENT_ID`, `MIC` | |
| `RD_RESTRICTION_REASON` | `REASON_CODE` | |
| `RD_SANCTIONS_PROGRAMME` | `PROGRAMME_ID` | |
| `RD_RESTRICTED_LIST` | `ENTRY_ID` | exactly one of `INSTRUMENT_ID` / `ISSUER_ID` is set |
| `RD_CLASSIFICATION_SCHEME` | `SCHEME_ID` | |
| `RD_INSTRUMENT_CLASSIFICATION` | `INSTRUMENT_ID`, `SCHEME_ID` | |
| `RD_REFERENCE_SOURCE` | `SOURCE_ID` | |
| `RD_SOURCE_PRECEDENCE` | `FIELD_NAME`, `SOURCE_ID` | lowest rank with a value wins |
| `RD_DQ_RULE` | `RULE_ID` | |

Joins into **core-geo**: `Rd_IssuerCountry`, `Rd_VenueCountry`, `Rd_ProgrammeCountry` — all
many-to-one onto `CG_COUNTRY`, so core-geo's own two-hop chain can be appended.
Joins into **core-instrument**: `Rd_RatingInstrument`, `Rd_MembershipInstrument`,
`Rd_ListingInstrument`, `Rd_ClassificationInstrument` — all onto `CI_INSTRUMENT`.
Joins within this project: `Rd_IssuerIdentifier`, `Rd_HierarchyParent`, `Rd_GradeAgency`,
`Rd_RatingGrade` (composite, on one line), `Rd_IndexProvider`, `Rd_MembershipIndex`,
`Rd_RebalanceIndex`, `Rd_SegmentVenue`, `Rd_SessionVenue`, `Rd_ListingVenue`,
`Rd_RestrictedReason`, `Rd_RestrictedProgramme`, `Rd_ClassificationScheme`,
`Rd_PrecedenceSource`.

Filters over `CI_INSTRUMENT`, applied to the five `extends [ciBase]` sets:
`RdListedRows` (`PRIMARY_EXCHANGE_MIC is not null`),
`RdRatedRows` (`CREDIT_RATING is not null`),
`RdIndexEligibleRows` (`FREE_FLOAT_PCT is not null`),
`RdRestrictedRows` (`STATUS = 'SUSPENDED'`),
`RdSovereignIssueRows` (`ISSUING_SOVEREIGN is not null`).

Filters on our own tables, declared and unapplied for a downstream mapping to reference:
`RdCurrentRestriction` (`RD_RESTRICTED_LIST.RELEASE_DATE is null`),
`RdActiveVenue` (`RD_VENUE.RETIRED_DATE is null`).

## Properties a downstream project navigates

| on class | property | type | reaches |
| --- | --- | --- | --- |
| `RdIssuerProfile` | `domicile` | `core_geo::CgCountry[0..1]` | into core-geo, target set `cgCountry` |
| `RdIssuerProfile` | `domicileMacroRegionCode` | `String[0..1]` | THREE hops: ours, then core-geo's two, to `CG_MACRO_REGION.CODE` |
| `RdIssuerProfile` | `identifiers` | `RdIssuerIdentifier[*]` | |
| `RdIssuerHierarchyLink` | `parent` | `RdIssuerProfile[0..1]` | |
| `RdListingVenue` | `country` | `core_geo::CgCountry[0..1]` | into core-geo |
| `RdListing` | `instrument`, `venue` | `core_instrument::Instrument[0..1]`, `RdListingVenue[0..1]` | `instrument` targets `[ciBase]` |
| `RdInstrumentRating` | `instrument`, `grade` | `Instrument[0..1]`, `RdRatingGrade[0..1]` | |
| `RdIndexMembership` | `index`, `instrument` | `RdIndexDefinition[0..1]`, `Instrument[0..1]` | |
| `RdIndexRebalance` | `index` | `RdIndexDefinition[0..1]` | |
| `RdVenueSegment`, `RdVenueTradingSession` | `venue` | `RdListingVenue[0..1]` | |
| `RdRestrictedListEntry` | `reason`, `programme` | `RdRestrictionReason[0..1]`, `RdSanctionsProgramme[0..1]` | |
| `RdInstrumentClassification` | `scheme`, `instrument` | `RdClassificationScheme[0..1]`, `Instrument[0..1]` | |
| `RdSourcePrecedence` | `source` | `RdReferenceSource[0..1]` | |
| `RdRatingGrade` | `agency` | `RdRatingAgency[0..1]` | |
| `RdIndexDefinition` | `provider` | `RdIndexProvider[0..1]` | |

Tables are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py reference-data   # compiles
