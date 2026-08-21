# core-geo

Layer 0, no dependencies. Package root `core_geo::`, prefix `CG_` / `Cg_` / `cg`.

Country, region and economic bloc. The project exists for its **join chain**: three tables in
a line, so a downstream project holding nothing but an ISO 3166-1 alpha-2 code can reach a
coverage region in two hops and an economic bloc in two more, navigating association ends
rather than declaring joins of its own.

    CgCountry --subRegion--> CgSubRegion --macroRegion--> CgMacroRegion
    CgCountry --blocMemberships--> CgBlocMembership --bloc--> CgBloc

Codes are the real ones: ISO 3166-1 alpha-2/alpha-3/numeric, ISO 3166-2 subdivisions, UN M49
sub-regions, ISO 639-1 languages, IANA time zones, ISO 4217 currency codes as strings.

## Elements

| element | kind | note |
| --- | --- | --- |
| `core_geo::CgMacroRegion` | class | coverage region -- AMER, EMEA, APAC. Top of the chain |
| `core_geo::CgSubRegion` | class | UN M49 sub-region (Western Europe 155, Northern America 021) |
| `core_geo::CgCountry` | class | ISO 3166-1. PK is `countryCode`, the alpha-2. The anchor everything downstream keys on |
| `core_geo::CgSubdivision` | class | ISO 3166-2 state/province/canton, `US-NY`, `DE-BY` |
| `core_geo::CgCity` | class | a place with coordinates and a clock; capitals and financial centres |
| `core_geo::CgTimeZone` | class | IANA zone; offset in MINUTES, so +05:45 is representable |
| `core_geo::CgBloc` | class | EU, EEA, USMCA, ASEAN, MERCOSUR, EFTA, GCC |
| `core_geo::CgBlocMembership` | class | the DATED hop between country and bloc -- accession and exit |
| `core_geo::CgTradeAgreement` | class | the instruments a bloc rests on (USMCA, CETA, EEA Agreement) |
| `core_geo::CgCountryProfile` | class | population, area, GDP, income group, OECD flag, per reference year |
| `core_geo::CgOfficialLanguage` | class | ISO 639-1; many per country (CH has four) |
| `core_geo::CgSubRegionInMacroRegion` | association | `macroRegion` [1] / `subRegions` [*] |
| `core_geo::CgCountryInSubRegion` | association | `subRegion` [1] / `countries` [*] |
| `core_geo::CgCountryBlocMembership` | association | `country` [1] / `blocMemberships` [*] |
| `core_geo::CgBlocMembershipBloc` | association | `bloc` [1] / `memberships` [*] |
| `core_geo::CgCountrySubdivision` | association | `country` [1] / `subdivisions` [*] |
| `core_geo::CgCountryCity` | association | `country` [1] / `cities` [*] |
| `core_geo::CgCityTimeZone` | association | `timeZone` [1] / `citiesInZone` [*] |
| `core_geo::CgCountryProfileLink` | association | `country` [0..1] / `profile` [0..1] |
| `core_geo::CgBlocTradeAgreement` | association | `bloc` [1] / `tradeAgreements` [*] |
| `core_geo::CgCountryLanguage` | association | `country` [1] / `officialLanguages` [*] |
| `core_geo::Store` | store | 11 tables `CG_*`, 10 joins `Cg_*`, 2 filters `Cg*` |
| `core_geo::Mapping` | mapping | 11 class sets `cg*`, 10 association mappings |

## Properties a downstream project navigates

The association ends are the public surface. They are named to read as a sentence at the
call site and will not be renamed.

| on class | property | type | reaches |
| --- | --- | --- | --- |
| `CgCountry` | `subRegion` | `CgSubRegion[1]` | hop 1 of the chain |
| `CgCountry` | `blocMemberships` | `CgBlocMembership[*]` | hop 1 of the bloc chain |
| `CgCountry` | `subdivisions` | `CgSubdivision[*]` | |
| `CgCountry` | `cities` | `CgCity[*]` | |
| `CgCountry` | `profile` | `CgCountryProfile[0..1]` | |
| `CgCountry` | `officialLanguages` | `CgOfficialLanguage[*]` | |
| `CgSubRegion` | `macroRegion` | `CgMacroRegion[1]` | hop 2 of the chain |
| `CgSubRegion` | `countries` | `CgCountry[*]` | |
| `CgMacroRegion` | `subRegions` | `CgSubRegion[*]` | |
| `CgBlocMembership` | `country` | `CgCountry[1]` | |
| `CgBlocMembership` | `bloc` | `CgBloc[1]` | hop 2 of the bloc chain |
| `CgBloc` | `memberships` | `CgBlocMembership[*]` | |
| `CgBloc` | `tradeAgreements` | `CgTradeAgreement[*]` | |
| `CgCity` | `country`, `timeZone` | `CgCountry[1]`, `CgTimeZone[1]` | |
| `CgTimeZone` | `citiesInZone` | `CgCity[*]` | |
| `CgSubdivision` | `country` | `CgCountry[1]` | |
| `CgCountryProfile` | `country` | `CgCountry[0..1]` | |
| `CgOfficialLanguage` | `country` | `CgCountry[1]` | |

Two hops from a country, in Pure:

    $client.domicile.subRegion.macroRegion.code
    $client.domicile.blocMemberships->filter(m | $m.status == 'MEMBER').bloc.code

Or without navigating at all -- `CgCountry.macroRegionCode` and `CgCountry.macroRegionName`
are mapped through the two-join chain `@Cg_CountrySubRegion > @Cg_SubRegionMacroRegion`, so a
report can group by macro-region without knowing that sub-regions exist.

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `CG_MACRO_REGION` | `MACRO_REGION_ID` | |
| `CG_SUB_REGION` | `SUB_REGION_ID` | FK `MACRO_REGION_ID` |
| `CG_COUNTRY` | `COUNTRY_CODE` | alpha-2. FK `SUB_REGION_ID`, and no other geography column |
| `CG_SUBDIVISION` | `SUBDIVISION_CODE` | FK `COUNTRY_CODE` |
| `CG_CITY` | `CITY_ID` | FK `COUNTRY_CODE`, `TIME_ZONE_ID` |
| `CG_TIME_ZONE` | `TIME_ZONE_ID` | |
| `CG_BLOC` | `BLOC_ID` | |
| `CG_BLOC_MEMBERSHIP` | `MEMBERSHIP_ID` | FK `COUNTRY_CODE`, `BLOC_ID` |
| `CG_TRADE_AGREEMENT` | `AGREEMENT_ID` | FK `BLOC_ID` |
| `CG_COUNTRY_PROFILE` | `COUNTRY_CODE` | one current vintage, `REFERENCE_YEAR` says which |
| `CG_OFFICIAL_LANGUAGE` | `LANGUAGE_ID` | FK `COUNTRY_CODE` |

Joins, all written many-to-one so a chained property mapping stays single-valued:
`Cg_CountrySubRegion`, `Cg_SubRegionMacroRegion`, `Cg_CountryMembership`, `Cg_MembershipBloc`,
`Cg_CountrySubdivision`, `Cg_CountryCity`, `Cg_CityTimeZone`, `Cg_CountryProfile`,
`Cg_BlocAgreement`, `Cg_CountryLanguage`.

Filters, declared and unapplied, for a downstream mapping to reference:
`CgPlacedCountry` (country has a sub-region, so it can walk the chain),
`CgCurrentBlocMembership` (`STATUS = 'MEMBER'`).

Mapping set ids: `cgMacroRegion`, `cgSubRegion`, `cgCountry`, `cgSubdivision`, `cgCity`,
`cgTimeZone`, `cgBloc`, `cgBlocMembership`, `cgTradeAgreement`, `cgCountryProfile`,
`cgOfficialLanguage`. A downstream `AssociationMapping` end pointing into this project must
name one of these as its target set.

Tables are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py core-geo   # compiles
