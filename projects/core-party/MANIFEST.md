# core-party

Layer 0, no dependencies. Package root `core_party::`, prefixes `CP_` (tables), `Cp_` (joins),
`Cp` (filters), `cp` (set ids).

Legal entities, the identifiers they are known by, and how they own each other. The shape to
know before using it: an entity has no single identifier, so identity is always the pair
(entity, scheme) — `CP_ENTITY_IDENTIFIER` is keyed by both columns and by nothing shorter.
Eight of the thirteen tables carry a multi-column primary key.

## Exports

| element | kind | note |
| --- | --- | --- |
| core_party::LegalEntity | class | the registered legal person; `entityId` is the key everything else hangs off |
| core_party::Jurisdiction | class | legal system, not country — Delaware and Jersey are the entries that matter |
| core_party::LegalForm | class | GmbH/Ltd/Inc; keyed (jurisdictionCode, formCode) — form codes repeat across systems |
| core_party::RegistrationAuthority | class | the register/tax office/regulator, distinct from the number it issues |
| core_party::IdentifierScheme | class | LEI, BIC, TAX, INTERNAL; `isUniquePerEntity` says whether one value per entity is expected |
| core_party::EntityIdentifier | class | **the composite-key class**: keyed (entityId, schemeCode); `identifierValue` is not a key |
| core_party::EntityName | class | keyed (entityId, nameType) — one slot per LEGAL/TRADING/FORMER/TRANSLITERATED |
| core_party::EntityAddress | class | keyed (entityId, addressType); registered ≠ headquarters |
| core_party::EntityRegistration | class | keyed (entityId, authorityId) — several concurrent registrations are normal |
| core_party::EntityRelationship | class | ownership edge, keyed (parentEntityId, childEntityId, relationshipType) |
| core_party::EntityGroup | class | an enumerated consolidation/credit/reporting set, not derived from the edges |
| core_party::GroupMembership | class | keyed (groupId, entityId) |
| core_party::EntityClassification | class | keyed (entityId, taxonomyCode) — NACE/SIC/GICS/MiFID kept side by side |
| core_party::EntityIdentifiers | association | LegalEntity[1] `identifiedEntity` ↔ EntityIdentifier[*] `identifiers` |
| core_party::SchemeIdentifiers | association | IdentifierScheme[1] `scheme` ↔ EntityIdentifier[*] `issuedIdentifiers` |
| core_party::EntityNames | association | LegalEntity[1] `namedEntity` ↔ EntityName[*] `names` |
| core_party::EntityAddresses | association | LegalEntity[1] `addressedEntity` ↔ EntityAddress[*] `addresses` |
| core_party::EntityClassifications | association | LegalEntity[1] `classifiedEntity` ↔ EntityClassification[*] `classifications` |
| core_party::EntityRegistrations | association | LegalEntity[1] `registeredEntity` ↔ EntityRegistration[*] `registrations` |
| core_party::AuthorityRegistrations | association | RegistrationAuthority[1] `authority` ↔ EntityRegistration[*] `authorityRegistrations` |
| core_party::ParentEdges | association | LegalEntity[1] `parentEntity` ↔ EntityRelationship[*] `subsidiaryEdges` (downward) |
| core_party::ChildEdges | association | LegalEntity[1] `childEntity` ↔ EntityRelationship[*] `parentEdges` (upward) |
| core_party::GroupMemberships | association | EntityGroup[1] `group` ↔ GroupMembership[*] `memberships` |
| core_party::EntityMemberships | association | LegalEntity[1] `memberEntity` ↔ GroupMembership[*] `entityMemberships` |
| core_party::EntityIncorporation | association | Jurisdiction[0..1] `jurisdiction` ↔ LegalEntity[*] `incorporatedEntities` |
| core_party::EntityForm | association | LegalForm[0..1] `legalForm` ↔ LegalEntity[*] `entitiesOfForm`; two-column join |
| core_party::JurisdictionForms | association | Jurisdiction[1] `formJurisdiction` ↔ LegalForm[*] `legalForms` |
| core_party::JurisdictionAuthorities | association | Jurisdiction[1] `authorityJurisdiction` ↔ RegistrationAuthority[*] `authorities` |
| core_party::Store | store | 13 tables `CP_*`, 15 joins `Cp_*`, filter `CpActiveEntities` |
| core_party::Mapping | mapping | 13 class sets `cp*`, 15 association mappings; `~primaryKey` on every set |

## Tables

`CP_LEGAL_ENTITY`, `CP_JURISDICTION`, `CP_LEGAL_FORM`\*, `CP_REGISTRATION_AUTHORITY`,
`CP_IDENTIFIER_SCHEME`, `CP_ENTITY_IDENTIFIER`\*, `CP_ENTITY_NAME`\*, `CP_ENTITY_ADDRESS`\*,
`CP_ENTITY_REGISTRATION`\*, `CP_ENTITY_RELATIONSHIP`\*, `CP_ENTITY_GROUP`,
`CP_GROUP_MEMBERSHIP`\*, `CP_ENTITY_CLASSIFICATION`\*  (\* = composite primary key)

## Joins

`Cp_Entity_Identifier`, `Cp_Scheme_Identifier`, `Cp_Entity_Name`, `Cp_Entity_Address`,
`Cp_Entity_Classification`, `Cp_Entity_Registration`, `Cp_Authority_Registration`,
`Cp_Parent_Relationship`, `Cp_Child_Relationship`, `Cp_Group_Membership`,
`Cp_Entity_Membership`, `Cp_Entity_Jurisdiction`, `Cp_Jurisdiction_LegalForm`,
`Cp_Jurisdiction_Authority`, `Cp_Entity_LegalForm` (two-column)

## Set ids

`cpLegalEntity`, `cpJurisdiction`, `cpLegalForm`, `cpRegistrationAuthority`,
`cpIdentifierScheme`, `cpEntityIdentifier`, `cpEntityName`, `cpEntityAddress`,
`cpEntityRegistration`, `cpEntityRelationship`, `cpEntityGroup`, `cpGroupMembership`,
`cpEntityClassification`

## Notes for downstream

- Extending one of these sets, or naming one in an `AssociationMapping` across a project
  boundary, needs the set id above — the default ids are not what is declared here.
- `CP_LEGAL_ENTITY` carries `JURISDICTION_CODE` and `FORM_CODE` so a legal form can be
  reached; a join to `CP_LEGAL_FORM` on `FORM_CODE` alone returns every jurisdiction's form
  of that name.
- The hierarchy is two joins over one table (`Cp_Parent_Relationship`,
  `Cp_Child_Relationship`), not a `{target}` self-join: the direction is which column the
  entity id matches.
- Derived properties available: `LegalEntity.isDissolved()`, `EntityRelationship.isCurrent()`.
- No `###Data`, no Runtime, no seeded rows.
