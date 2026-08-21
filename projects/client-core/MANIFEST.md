# client-core

Layer 1. Depends on `core-party` and `core-geo`, and on nothing else. Package root
`client_core::`, prefixes `CLI_` (tables), `Cli_` (joins), `Cli` (filters), `cli` (set ids).

The client RELATIONSHIP, which is not the legal person. `core_party::LegalEntity` is who
someone is; `CliClient` is what we have with them — a MiFID classification, an onboarding
case, a relationship manager, a set of mandates. One entity carries several client
relationships (one per booking centre) and a prospect is a client relationship with no
confirmed entity at all, so the link is `[0..1]` / `[*]` and never a shared key.

The shape to know before using it: **`CLI_CLIENT` holds one two-letter country code and no
other geography.** Sub-region, macro-region and bloc are all reached by join chain, so a
downstream project that wants "clients by coverage region" reads a mapped property and a
downstream project that wants the objects navigates `domicileCountry` into core-geo.

    CLI_CLIENT  -> CG_COUNTRY -> CG_SUB_REGION -> CG_MACRO_REGION     3 hops
    CLI_CLIENT  -> CG_BLOC_MEMBERSHIP -> CG_BLOC                      2 hops
    CLI_MANDATE -> CLI_CLIENT -> CG_BLOC_MEMBERSHIP -> CG_BLOC        3 hops
    CLI_MANDATE -> CLI_CLIENT -> CG_COUNTRY -> CG_SUB_REGION -> CG_MACRO_REGION   4 hops

## Exports

| element | kind | note |
| --- | --- | --- |
| `client_core::CliClient` | class | the relationship; `clientId` is the key everything else hangs off. Carries the flattened chain properties |
| `client_core::CliClientCategory` | class | MiFID category reference: RETAIL, PROFESSIONAL, ELIGIBLE_COUNTERPARTY; `protectionLevel` 1..3 is ordered |
| `client_core::CliClassification` | class | the DATED assignment of a category to a client — re-categorisation is a new row, not an update |
| `client_core::CliDomicile` | class | dated domicile per purpose (TAX/REGULATORY/RESIDENCE/CORRESPONDENCE); `countryCode` is an alpha-2 |
| `client_core::CliOnboardingState` | class | ordered state reference; `allowsTrading` is true for APPROVED alone |
| `client_core::CliOnboardingCase` | class | one pass through onboarding — a periodic review opens a new case against a live client |
| `client_core::CliRelationshipManager` | class | the covering employee; NOT a `core_party::LegalEntity` |
| `client_core::CliCoverageTeam` | class | the desk an RM sits on; the level coverage is usually reported at |
| `client_core::CliMandate` | class | what the client asked us to do; carries the benchmark, base currency and fee, plus 3- and 4-hop chain properties |
| `client_core::CliMandateRestriction` | class | one restriction per row, typed; `scopeValue` is typed by `restrictionType` |
| `client_core::CliRiskProfile` | class | current suitability profile, keyed by client alone |
| `client_core::CliClientContact` | class | a named human at the client; `isAuthorisedSignatory` decides whether an instruction can be acted on |
| `client_core::CliClientDomiciles` | association | CliClient[1] `client` ↔ CliDomicile[*] `domiciles` |
| `client_core::CliClientClassifications` | association | CliClient[1] `classifiedClient` ↔ CliClassification[*] `classifications` |
| `client_core::CliCategoryClassifications` | association | CliClientCategory[1] `category` ↔ CliClassification[*] `categoryAssignments` |
| `client_core::CliClientOnboarding` | association | CliClient[1] `onboardedClient` ↔ CliOnboardingCase[*] `onboardingCases` |
| `client_core::CliCaseState` | association | CliOnboardingState[1] `state` ↔ CliOnboardingCase[*] `casesInState` |
| `client_core::CliClientCoverage` | association | CliRelationshipManager[0..1] `relationshipManager` ↔ CliClient[*] `coveredClients` |
| `client_core::CliManagerTeam` | association | CliCoverageTeam[1] `coverageTeam` ↔ CliRelationshipManager[*] `managers` |
| `client_core::CliClientMandates` | association | CliClient[1] `mandateClient` ↔ CliMandate[*] `mandates` |
| `client_core::CliMandateRestrictions` | association | CliMandate[1] `mandate` ↔ CliMandateRestriction[*] `restrictions` |
| `client_core::CliClientRiskProfile` | association | CliClient[0..1] `profiledClient` ↔ CliRiskProfile[0..1] `riskProfile` |
| `client_core::CliClientContacts` | association | CliClient[1] `contactClient` ↔ CliClientContact[*] `contacts` |
| `client_core::CliClientLegalEntity` | association | **crosses into core-party**: `core_party::LegalEntity[0..1]` `legalEntity` ↔ CliClient[*] `clientRelationships` |
| `client_core::CliClientDomicileCountry` | association | **crosses into core-geo**: `core_geo::CgCountry[0..1]` `domicileCountry` ↔ CliClient[*] `clientsDomiciled` |
| `client_core::CliDomicileCountry` | association | **crosses into core-geo**: `core_geo::CgCountry[1]` `country` ↔ CliDomicile[*] `domicileRecords` |
| `client_core::Store` | store | 12 tables `CLI_*`, 16 joins `Cli_*`, 4 filters `Cli*`; `include`s both dependency stores |
| `client_core::Mapping` | mapping | 12 class sets `cli*`, 14 association mappings; `include`s both dependency mappings |

## Properties a downstream project navigates

The flattened chain properties, so a report can group without navigating anything:

| on class | property | reaches |
| --- | --- | --- |
| `CliClient` | `domicileMacroRegionCode` / `domicileMacroRegionName` | `CG_MACRO_REGION`, 3 hops |
| `CliClient` | `regulatoryBlocCode` | `CG_BLOC`, 2 hops through the dated membership |
| `CliClient` | `legalEntityName` | `CP_LEGAL_ENTITY.LEGAL_NAME`, 1 hop |
| `CliClient` | `incorporationJurisdictionName` | `CP_JURISDICTION`, 2 hops |
| `CliMandate` | `clientBlocCode` | `CG_BLOC`, 3 hops from a table with no geography |
| `CliMandate` | `clientMacroRegionName` | `CG_MACRO_REGION`, 4 hops |

The association ends, for callers that want the objects:

    $client.domicileCountry.subRegion.macroRegion.code
    $client.domicileCountry.blocMemberships->filter(m | $m.status == 'MEMBER').bloc.code
    $client.legalEntity.jurisdiction.jurisdictionName
    $client.classifications->filter(c | $c.isCurrent()).category.protectionLevel
    $client.mandates.restrictions->filter(r | $r.isHardLimit)

Derived properties: `CliClient.isLive()`, `CliClassification.isCurrent()`,
`CliDomicile.isCurrent()`, `CliOnboardingCase.isOpen()`, `CliMandate.isOpen()`,
`CliRelationshipManager.isInPost()`.

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `CLI_CLIENT` | `CLIENT_ID` | FK `ENTITY_ID` (core-party), `DOMICILE_COUNTRY_CODE` + `REGULATORY_BLOC_ID` (core-geo), `RM_ID` |
| `CLI_CLIENT_CATEGORY` | `CATEGORY_CODE` | |
| `CLI_CLASSIFICATION` | `CLASSIFICATION_ID` | surrogate, not (client, category): opt-up/down/up would collide |
| `CLI_DOMICILE` | `DOMICILE_ID` | FK `CLIENT_ID`, `COUNTRY_CODE` |
| `CLI_ONBOARDING_STATE` | `STATE_CODE` | |
| `CLI_ONBOARDING_CASE` | `CASE_ID` | FK `CLIENT_ID`, `STATE_CODE` |
| `CLI_RELATIONSHIP_MANAGER` | `RM_ID` | FK `TEAM_ID`, `DESK_COUNTRY_CODE` |
| `CLI_COVERAGE_TEAM` | `TEAM_ID` | |
| `CLI_MANDATE` | `MANDATE_ID` | FK `CLIENT_ID`; `TARGET_AUM`/`MANAGEMENT_FEE_BPS` are DOUBLE |
| `CLI_MANDATE_RESTRICTION` | `RESTRICTION_ID` | FK `MANDATE_ID` |
| `CLI_RISK_PROFILE` | `CLIENT_ID` | one current profile per client |
| `CLI_CLIENT_CONTACT` | `CONTACT_ID` | FK `CLIENT_ID` |

Joins, all many-to-one so a chained property mapping stays single-valued:
`Cli_ClientDomicile`, `Cli_ClientClassification`, `Cli_CategoryClassification`,
`Cli_ClientOnboardingCase`, `Cli_CaseState`, `Cli_ClientManager`, `Cli_ManagerTeam`,
`Cli_ClientMandate`, `Cli_MandateRestriction`, `Cli_ClientRiskProfile`, `Cli_ClientContact`,
`Cli_ClientEntity` (out to core-party), `Cli_ClientCountry`, `Cli_DomicileCountry`,
`Cli_ManagerCountry` (out to core-geo), `Cli_ClientBlocMembership` (**two-column**).

`Cli_ClientBlocMembership` matches `DOMICILE_COUNTRY_CODE = CG_BLOC_MEMBERSHIP.COUNTRY_CODE
and REGULATORY_BLOC_ID = CG_BLOC_MEMBERSHIP.BLOC_ID`. Matching the country alone would be
one-to-many — a country accedes to several blocs — and a chained property over it would
multiply rows silently. The bloc id column on `CLI_CLIENT` exists to make that hop
many-to-one.

Filters, declared and unapplied, for a downstream mapping to reference:
`CliLiveClients` (`RELATIONSHIP_END_DATE is null`), `CliOpenCases`, `CliCurrentDomicile`,
`CliCurrentClassification`.

Mapping set ids: `cliClient`, `cliClientCategory`, `cliClassification`, `cliDomicile`,
`cliOnboardingState`, `cliOnboardingCase`, `cliRelationshipManager`, `cliCoverageTeam`,
`cliMandate`, `cliMandateRestriction`, `cliRiskProfile`, `cliClientContact`.

## Notes for downstream

- Extending one of these sets, or naming one in an `AssociationMapping` across a project
  boundary, needs the set id above — the default ids are not what is declared here.
- `client_core::Store` includes both dependency stores, so a downstream store that includes
  this one gets `CP_*` and `CG_*` too, and must not include them a second time.
- `client_core::Mapping` includes both dependency mappings for the same reason.
- A client's geography is `DOMICILE_COUNTRY_CODE` and nothing else. Do not add a region
  column downstream: chain off `Cli_ClientCountry` instead.
- Tables are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py client-core   # compiles
