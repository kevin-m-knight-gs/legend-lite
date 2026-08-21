# static-core

Layer 1, depends on `core-types` and `core-units`. Static reference data as a firm
distributes it: published code lists, the values in them with their effective windows, the
standard constants that go out alongside them, and the desk accountable for each.

Prefixes: elements `Stc`, tables `STC_`, joins `Stc_`, set ids `stc`.

Every table is declared inside the `stc` SCHEMA, so a downstream project that includes this
store must reference the tables schema-qualified:

    ~mainTable [static_core::Store]stc.STC_CODE_LIST
    listCode: [static_core::Store]stc.STC_CODE_LIST.LIST_CODE

`static_core::Store` **includes** `core_units::Store`, so core-units' `uom` schema is
visible through it; still refer to those tables as `[core_units::Store]uom.CU_UNIT.…`.
`static_core::Mapping` **includes** `core_units::Mapping`, so its sets (`cuUnit`, …) are
already in scope downstream — do not include it twice.

## Exports

| element | kind | note |
| --- | --- | --- |
| static_core::StcDataOwner | class | the desk accountable for a list: `ownerCode`, `ownerName`, `contactEmail`, `jurisdiction: core_types::CtCountry[1]`, `isPrimarySource`; qualified `label()` |
| static_core::StcCodeList | class | list header: `listCode`, `listName`, `domainCode`, `ownerCode`, `reviewDays`, `publishedFrom`, `retiredOn: StrictDate[0..1]`; `owner`, `values`; qualified `nextReviewDate(): Date[1]` (via `core_types::ctValueDate`), `isRetired()`. Stereotyped `<<core_types::CtGovernance.reviewed>>` |
| static_core::StcCodeValue | class | one member of a list: `listCode`, `valueCode`, `displayName`, `description[0..1]`, `displayOrder`, `effectiveFrom`, `effectiveThru[0..1]`; `list`; qualified `isCurrent()` |
| static_core::StcStandardValue | class | a distributed constant: `standardCode`, `standardName`, `magnitude`, `unitCodeRef`, `ownerCode`, `effectiveFrom`, `tolerancePct`; `unit: core_units::CuUnit[1]`, `owner`; qualified `magnitudeInBaseUnit()`, `label()` |
| static_core::Store | store | includes `core_units::Store`; schema `stc`; tables STC_DATA_OWNER, STC_CODE_LIST, STC_CODE_VALUE, STC_STANDARD_VALUE; joins Stc_ListOwner, Stc_ValueList, Stc_StandardOwner, Stc_StandardUnit |
| static_core::Mapping | mapping | includes `core_units::Mapping`; set ids stcDataOwner, stcCodeList, stcCodeValue, stcStandardValue; enumeration mapping `StcCountryCode` for `core_types::CtCountry` |

## Tables

| table | primary key | note |
| --- | --- | --- |
| stc.STC_DATA_OWNER | OWNER_CODE CHAR(6) | OWNER_NAME VARCHAR(60), CONTACT_EMAIL VARCHAR(80), JURISDICTION CHAR(2), IS_PRIMARY_SOURCE BIT |
| stc.STC_CODE_LIST | LIST_CODE CHAR(8) | DOMAIN_CODE CHAR(4), OWNER_CODE CHAR(6), REVIEW_DAYS SMALLINT, PUBLISHED_FROM DATE, RETIRED_ON DATE (null while live) |
| stc.STC_CODE_VALUE | LIST_CODE, VALUE_CODE | composite; DISPLAY_NAME VARCHAR(60), DESCRIPTION VARCHAR(200), DISPLAY_ORDER SMALLINT, EFFECTIVE_FROM DATE, EFFECTIVE_THRU DATE (null = current) |
| stc.STC_STANDARD_VALUE | STANDARD_CODE VARCHAR(20) | MAGNITUDE NUMERIC(20,8), UNIT_CODE CHAR(6) → uom.CU_UNIT, OWNER_CODE CHAR(6), EFFECTIVE_FROM DATE, TOLERANCE_PCT DOUBLE |

## Joins

| join | condition |
| --- | --- |
| Stc_ListOwner | stc.STC_CODE_LIST.OWNER_CODE = stc.STC_DATA_OWNER.OWNER_CODE |
| Stc_ValueList | stc.STC_CODE_VALUE.LIST_CODE = stc.STC_CODE_LIST.LIST_CODE |
| Stc_StandardOwner | stc.STC_STANDARD_VALUE.OWNER_CODE = stc.STC_DATA_OWNER.OWNER_CODE |
| Stc_StandardUnit | stc.STC_STANDARD_VALUE.UNIT_CODE = uom.CU_UNIT.UNIT_CODE — **cross-project, cross-schema**: this project's `stc` schema onto core-units' `uom` schema, usable because `static_core::Store` includes `core_units::Store` |

## Notes for downstream projects

- `StcStandardValue.unit` is mapped through `Stc_StandardUnit` with the explicit target set
  id `cuUnit`; core-units names its sets, so the default `core_units_CuUnit` id does not
  exist and any `extends [...]` or `AssociationMapping` you write must name `cuUnit` too.
- The set ids here are explicit as well (`stcCodeList`, …), so name them the same way.
- Both `retiredOn` and `effectiveThru` are null-when-current rather than boolean flags; a
  store `Filter` cannot take a boolean literal, so filter with `is null`.
- No `###Data` element, no Runtime; tables are declared and unseeded. `REAL` is not used —
  NUMERIC(20,8) and DOUBLE carry the non-integer columns.
