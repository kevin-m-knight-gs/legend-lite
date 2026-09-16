# legal-core

Layer 1, depends on **core-party** and nothing else. Package root `legal_core::`, prefixes
`LGL_` (tables), `Lgl_` (joins), `Lgl` (filters), `lgl` (set ids).

Master agreements between two parties — ISDA Masters, GMRAs, GMSLAs — their governing law,
their credit support annexes, their close-out mechanics, the netting opinions that make the
close-out enforceable, and the amendments and protocol adherences that change them.

The shape to know before using it: a master agreement is a **single agreement between exactly
two legal persons**, and the signatories are not columns on the agreement. They are rows in
`AgreementParty`, keyed `(agreementId, partyRole)`, each pointing at a `core_party::LegalEntity`
through the association `LglPartyEntity`. Nine of the twelve tables carry a multi-column
primary key; `LGL_ELIGIBLE_COLLATERAL` and `LGL_NETTING_OPINION` are keyed by three columns.

## Exports

| element | kind | note |
| --- | --- | --- |
| legal_core::LglControlled | profile | stereotypes `legallyReviewed`, `templateDeviation`, `negotiated`; tags `reviewedBy`, `reviewDate`, `opinionRef`, `deviationTicket` |
| legal_core::MasterAgreement | class | the umbrella contract; `agreementId` is the key almost everything else hangs off; `<<legallyReviewed>>` + tagged |
| legal_core::AgreementType | class | published form (ISDA 2002, GMRA 2011); `hasSingleAgreementClause` is the netting precondition |
| legal_core::GoverningLaw | class | the law and the forum clause; NOT either party's jurisdiction of incorporation |
| legal_core::AgreementParty | class | **the reach into core-party**: keyed (agreementId, partyRole), carries `entityId` |
| legal_core::CreditSupportAnnex | class | keyed (agreementId, annexCode) — one master carries a VM annex AND an IM annex; `isTitleTransfer` decides whose property the collateral is |
| legal_core::EligibleCollateral | class | keyed (agreementId, annexCode, assetCode); `haircutPercentage()` derived from `valuationPercentage` |
| legal_core::NettingOpinion | class | keyed (jurisdictionCode, typeCode, counterpartyType) — capital relief is this exact triple |
| legal_core::CloseOutTerms | class | keyed by agreement alone; MARKET_QUOTATION / LOSS / CLOSE_OUT_AMOUNT, AET, set-off |
| legal_core::ElectionTerm | class | keyed (agreementId, electionCode) — the negotiated Schedule elections; `electedValue` is `<<negotiated>>` |
| legal_core::Amendment | class | keyed (agreementId, amendmentNumber), append-only; `isTemplateDeviation` is `<<templateDeviation>>` |
| legal_core::Protocol | class | a published multilateral protocol (IBOR Fallbacks, Stay, EMIR Portfolio Rec) |
| legal_core::ProtocolAdherence | class | keyed (protocolCode, entityId) — adherence is by ENTITY, not by agreement |
| legal_core::LglAgreementForm | association | AgreementType[1] `agreementType` ↔ MasterAgreement[*] `agreementsOfType` |
| legal_core::LglAgreementLaw | association | GoverningLaw[1] `governingLaw` ↔ MasterAgreement[*] `agreementsUnderLaw` |
| legal_core::LglAgreementParties | association | MasterAgreement[1] `partyAgreement` ↔ AgreementParty[*] `parties` |
| legal_core::LglPartyEntity | association | **crosses to core-party**: LegalEntity[1] `partyEntity` ↔ AgreementParty[*] `agreementRoles` |
| legal_core::LglAgreementAnnexes | association | MasterAgreement[1] `annexAgreement` ↔ CreditSupportAnnex[*] `creditSupportAnnexes` |
| legal_core::LglAnnexCollateral | association | CreditSupportAnnex[1] `collateralAnnex` ↔ EligibleCollateral[*] `eligibleCollateral`; two-column join |
| legal_core::LglAgreementCloseOut | association | MasterAgreement[1] `closeOutAgreement` ↔ CloseOutTerms[0..1] `closeOutTerms` |
| legal_core::LglAgreementElections | association | MasterAgreement[1] `electedAgreement` ↔ ElectionTerm[*] `elections` |
| legal_core::LglAgreementAmendments | association | MasterAgreement[1] `amendedAgreement` ↔ Amendment[*] `amendments` |
| legal_core::LglProtocolAdherences | association | Protocol[1] `adheredProtocol` ↔ ProtocolAdherence[*] `adherences` |
| legal_core::LglAdherentEntity | association | **crosses to core-party**: LegalEntity[1] `adherentEntity` ↔ ProtocolAdherence[*] `protocolAdherences` |
| legal_core::LglTypeOpinions | association | AgreementType[1] `opinionAgreementType` ↔ NettingOpinion[*] `nettingOpinions` |
| legal_core::LglOpinionJurisdiction | association | **crosses to core-party**: Jurisdiction[1] `opinionJurisdiction` ↔ NettingOpinion[*] `jurisdictionOpinions` |
| legal_core::Store | store | `include core_party::Store`; 12 tables `LGL_*`, 13 joins `Lgl_*`, filter `LglLiveAgreements` |
| legal_core::Mapping | mapping | `include core_party::Mapping`; 12 class sets `lgl*`, 14 association mappings; `~primaryKey` on every set |

## Properties added to core-party classes

Downstream projects see these on the upstream classes once legal-core is in the graph:

| class | property | type |
| --- | --- | --- |
| core_party::LegalEntity | `agreementRoles` | legal_core::AgreementParty[*] |
| core_party::LegalEntity | `protocolAdherences` | legal_core::ProtocolAdherence[*] |
| core_party::Jurisdiction | `jurisdictionOpinions` | legal_core::NettingOpinion[*] |

## Tables

`LGL_MASTER_AGREEMENT`, `LGL_AGREEMENT_TYPE`, `LGL_GOVERNING_LAW`, `LGL_AGREEMENT_PARTY`\*,
`LGL_CREDIT_SUPPORT_ANNEX`\*, `LGL_ELIGIBLE_COLLATERAL`\*\*, `LGL_NETTING_OPINION`\*\*,
`LGL_CLOSE_OUT_TERMS`, `LGL_ELECTION_TERM`\*, `LGL_AMENDMENT`\*, `LGL_PROTOCOL`,
`LGL_PROTOCOL_ADHERENCE`\*  (\* = composite primary key, \*\* = three-column primary key)

## Joins

Inside legal-core: `Lgl_Agreement_Form`, `Lgl_Agreement_Law`, `Lgl_Agreement_Party`,
`Lgl_Agreement_Annex`, `Lgl_Agreement_CloseOut`, `Lgl_Agreement_Election`,
`Lgl_Agreement_Amendment`, `Lgl_Protocol_Adherence`, `Lgl_Type_Opinion`,
`Lgl_Annex_Collateral` (two-column).

Into core-party: `Lgl_Entity_AgreementParty`, `Lgl_Entity_Adherence`,
`Lgl_Jurisdiction_Opinion`.

## Set ids

`lglMasterAgreement`, `lglAgreementType`, `lglGoverningLaw`, `lglAgreementParty`,
`lglCreditSupportAnnex`, `lglEligibleCollateral`, `lglNettingOpinion`, `lglCloseOutTerms`,
`lglElectionTerm`, `lglAmendment`, `lglProtocol`, `lglProtocolAdherence`

## Notes for downstream

- Every set id above is explicit, so the default ids (`legal_core_MasterAgreement`) do NOT
  exist. An `extends [...]` or a cross-project `AssociationMapping` must name the `lgl*` id.
- The three cross-boundary joins are declared in `legal_core::Store`, which is why that store
  `include`s `core_party::Store`. A downstream store that wants them should include
  `legal_core::Store` rather than redeclare them.
- Filters available: `LglLiveAgreements` (`TERMINATION_DATE is null`). Declared for downstream
  use; nothing here maps a narrowed set.
- Derived properties available: `MasterAgreement.isLive()`, `CreditSupportAnnex.hasThreshold()`,
  `EligibleCollateral.haircutPercentage()`, `NettingOpinion.isQualified()`,
  `CloseOutTerms.usesCloseOutAmount()`, `ProtocolAdherence.isEffective()`.
- The netting question a downstream project will want to ask is a three-way lookup, not a
  flag: take the counterparty's `core_party::LegalEntity`, its jurisdiction, the master's
  `typeCode`, and the counterparty type, and look for a `NettingOpinion` with
  `supportsCloseOutNetting` and no `qualification`. There is deliberately no
  `MasterAgreement.isNettable` property, because it would have to guess the counterparty type.
- `threshold` null ≠ zero: null means the term was left to the Schedule, zero means it was
  negotiated to nil. Same for `independentAmount`.
- No `###Data`, no Runtime, no seeded rows.
