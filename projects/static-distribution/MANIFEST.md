# static-distribution

Layer 2. Package root `static_distribution::`, prefix `SDI_` / `Sdi_` / `sdi`.
Depends on **static-core** and **reference-data**, and on nothing else.

What happens to static data after it is published. static-core says what the content IS;
this project ships it: a **dataset** is published on a **schedule**, every **publication**
carries a version, a checksum and a row count, each **consumer** subscribes to a subset, and
each consumer must **acknowledge**. The acknowledgement row is created WITH the publication
and its timestamp is null until the consumer fills it in, so "has not acknowledged" is a null
test over rows that exist rather than an anti-join against rows that do not — the
unacknowledged publication is the operational problem this schema is shaped around.

Exports **classes, a store and a mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## Three schemas in one database

`static_distribution::Store` includes both dependency stores, so a downstream project that
includes it sees:

| schema | whose | how to reference |
| --- | --- | --- |
| `sdi` | ours | `[static_distribution::Store]sdi.SDI_PUBLICATION.CHECKSUM` |
| `stc` | static-core's | `[static_core::Store]stc.STC_CODE_LIST.LIST_CODE` |
| `uom` | core-units', via static-core | `[core_units::Store]uom.CU_UNIT.UNIT_CODE` |
| *(default)* | reference-data's | `[reference_data::Store]RD_REFERENCE_SOURCE.SOURCE_ID` |

Everything of ours is schema-qualified. `static_distribution::Mapping` includes
`static_core::Mapping` and `reference_data::Mapping`; those already carry
`core_units::Mapping`, `core_instrument::Mapping` and `core_geo::Mapping` transitively — do
not include any of them again.

## Exports

| element | kind | note |
| --- | --- | --- |
| `static_distribution::SdiChannel` | class | the pipe a publication goes down: transport, endpoint, `retiredOn` null while in service; `isRetired()`, `label()` |
| `static_distribution::SdiConsumer` | class | a downstream system: `ackSlaHours`, `escalationTeam`, `onboardedOn`/`offboardedOn`; `channel`, `subscriptions`; `isActive()`, `label()` |
| `static_distribution::SdiDataset` | class | the unit of distribution; reaches `static_core::StcCodeList`, `static_core::StcDataOwner`, `reference_data::RdReferenceSource` and `SdiUpstreamField`; `isLive()`, `label()` |
| `static_distribution::SdiSubscription` | class | one consumer's standing request for one dataset; composite PK; `isMandatory`; `isCurrent()` |
| `static_distribution::SdiSchedule` | class | the cadence: frequency, cutoff time + zone, `graceMinutes`; `label()` |
| `static_distribution::SdiPublication` | class | one run: `versionNumber`, `checksum`, `rowCount` vs `expectedRowCount`, `publishedAt`; `versionLabel()`, `isRetracted()`, `completenessRatio(): Float[1]` |
| `static_distribution::SdiPublicationItem` | class | one content row in one publication, with `changeType` ADD/UPDATE/DELETE; three-part PK; reaches `static_core::StcCodeValue`; `isDeletion()` |
| `static_distribution::SdiAcknowledgement` | class | **the operational record**: `acknowledgedAt` null = outstanding; `isOutstanding()`, `checksumAgrees()`. `publication` is `[1]` |
| `static_distribution::SdiDeliveryAttempt` | class | one push attempt over one channel; `succeeded()`, `isRetry()` |
| `static_distribution::SdiRetraction` | class | a publication withdrawn: reason, author, replacement version; `hasReplacement()` |
| `static_distribution::SdiEntitlement` | class | what a consumer may receive by domain, and whether it may redistribute; composite PK; `domain` targets the `~distinct` set; `isCurrent()` |
| `static_distribution::SdiDistributedDomain` | class | **`~distinct`**, no table — the distinct `DOMAIN_CODE` values across `stc.STC_CODE_LIST`; `label()` |
| `static_distribution::SdiUpstreamField` | class | **`~distinct`**, no table — the distinct `FIELD_NAME` values across `RD_SOURCE_PRECEDENCE`; `label()` |
| `static_distribution::Store` | store | includes `static_core::Store` and `reference_data::Store`; schema `sdi` with 10 tables, 19 joins, 4 filters |
| `static_distribution::Mapping` | mapping | includes both dependency mappings; 13 sets, 2 of them `~distinct` |

13 classes.

## The two `~distinct` sets — one per dependency schema

This is the "deduplicating across two schemas" of the project theme. Neither set has a table
of its own; each collapses a column that repeats on every row of a DEPENDENCY's table.

    *static_distribution::SdiDistributedDomain[sdiDomain]: Relational
    {
       ~distinct
       ~primaryKey ( [static_core::Store]stc.STC_CODE_LIST.DOMAIN_CODE )
       ~mainTable [static_core::Store]stc.STC_CODE_LIST
       domainCode: [static_core::Store]stc.STC_CODE_LIST.DOMAIN_CODE,
       publishingOwnerCode: [static_core::Store]stc.STC_CODE_LIST.OWNER_CODE
    }

| set | deduplicates on | source table (its own PK) | why it collapses |
| --- | --- | --- | --- |
| `sdiDomain` | `stc.STC_CODE_LIST.DOMAIN_CODE` | `stc.STC_CODE_LIST` (`LIST_CODE`) | many published lists share one subject area |
| `sdiUpstreamField` | `RD_SOURCE_PRECEDENCE.FIELD_NAME` | `RD_SOURCE_PRECEDENCE` (`FIELD_NAME`, `SOURCE_ID`) | one row per (field, source): a field with four ranked sources is four rows there and one here |

`~primaryKey` names the **collapsed** column, never the source table's own key. Keying
`sdiDomain` on `LIST_CODE` or `sdiUpstreamField` on the full `(FIELD_NAME, SOURCE_ID)` pair
would leave one row per source row and the `~distinct` would collapse nothing. Directive
order is `~distinct`, `~primaryKey`, `~mainTable`.

Both sets are legitimate join targets: `SdiEntitlement.domain[sdiDomain]` goes through
`@Sdi_EntitlementDomain` and `SdiDataset.governedField[sdiUpstreamField]` through
`@Sdi_DatasetField`. A property mapped onto a `~distinct` set compiles.

## Set ids (a GLOBAL namespace — name these explicitly, do not redeclare them)

`sdiDomain`, `sdiUpstreamField` (the two `~distinct` sets), then `sdiChannel`, `sdiConsumer`,
`sdiDataset`, `sdiSubscription`, `sdiSchedule`, `sdiPublication`, `sdiPublicationItem`,
`sdiAck`, `sdiDelivery`, `sdiRetraction`, `sdiEntitlement`.

Note `sdiAck` (not `sdiAcknowledgement`) and `sdiDelivery` (not `sdiDeliveryAttempt`). All of
them are explicit, so the DEFAULT ids (`static_distribution_SdiPublication`) do not exist: a
downstream `extends [...]`, `AssociationMapping` end or class-valued property mapping must
name the id above.

## Tables — all in schema `sdi`

| table | primary key | note |
| --- | --- | --- |
| `sdi.SDI_CHANNEL` | `CHANNEL_CODE` CHAR(8) | `TRANSPORT`, `ENDPOINT_URI`, `IS_ENCRYPTED`, `RETIRED_ON` (null = in service) |
| `sdi.SDI_CONSUMER` | `CONSUMER_ID` VARCHAR(30) | FK `CHANNEL_CODE`; `ACK_SLA_HOURS`, `OFFBOARDED_ON` (null = live) |
| `sdi.SDI_DATASET` | `DATASET_CODE` VARCHAR(20) | FKs `LIST_CODE`→`stc`, `OWNER_CODE`→`stc`, `SOURCE_ID`→reference-data, `PRIMARY_FIELD_NAME`→reference-data; `RETIRED_ON` |
| `sdi.SDI_SUBSCRIPTION` | `CONSUMER_ID`, `DATASET_CODE` | `DELIVERY_FORMAT`, `IS_MANDATORY`, `CANCELLED_ON` (null = standing) |
| `sdi.SDI_SCHEDULE` | `SCHEDULE_ID` VARCHAR(30) | FK `DATASET_CODE`; `CUTOFF_TIME` + `TIME_ZONE` as text, `GRACE_MINUTES` |
| `sdi.SDI_PUBLICATION` | `PUBLICATION_ID` VARCHAR(40) | `VERSION_NUMBER` INTEGER, `CHECKSUM` CHAR(64), `ROW_COUNT`/`EXPECTED_ROW_COUNT`, `PUBLISHED_AT` TIMESTAMP, `RETRACTED_ON` |
| `sdi.SDI_PUBLICATION_ITEM` | `PUBLICATION_ID`, `LIST_CODE`, `VALUE_CODE` | three-part; `CHANGE_TYPE`, `ITEM_CHECKSUM` |
| `sdi.SDI_ACK` | `PUBLICATION_ID`, `CONSUMER_ID` | `ACKNOWLEDGED_AT` TIMESTAMP **null until acknowledged**, `REPORTED_CHECKSUM`, `ACK_STATUS` |
| `sdi.SDI_DELIVERY` | `ATTEMPT_ID` VARCHAR(40) | FKs publication, consumer, channel; `ATTEMPT_NUMBER`, `FAILURE_CODE` (null = success) |
| `sdi.SDI_RETRACTION` | `RETRACTION_ID` VARCHAR(40) | FK `PUBLICATION_ID`; `REPLACEMENT_PUBLICATION_ID` |
| `sdi.SDI_ENTITLEMENT` | `CONSUMER_ID`, `DOMAIN_CODE` | `DOMAIN_CODE` CHAR(4) matches `stc.STC_CODE_LIST.DOMAIN_CODE`; `MAY_REDISTRIBUTE`, `REVOKED_ON` |

No `REAL` anywhere; `completenessRatio()` is derived in Pure, not stored.

## Joins

Within `sdi`: `Sdi_ConsumerChannel`, `Sdi_SubscriptionConsumer`, `Sdi_SubscriptionDataset`,
`Sdi_ScheduleDataset`, `Sdi_PublicationDataset`, `Sdi_PublicationSchedule`,
`Sdi_ItemPublication`, `Sdi_AckPublication`, `Sdi_AckConsumer`, `Sdi_DeliveryPublication`,
`Sdi_DeliveryConsumer`, `Sdi_DeliveryChannel`, `Sdi_RetractionPublication`,
`Sdi_EntitlementConsumer`.

Across the schema boundary into static-core's `stc`: `Sdi_DatasetList`, `Sdi_DatasetOwner`,
`Sdi_ItemValue` (**composite, on one line**: `LIST_CODE` and `VALUE_CODE` together, because a
value code is only unique inside its list), `Sdi_EntitlementDomain` (lands on the `~distinct`
domain set's main table).

Into reference-data's default schema: `Sdi_DatasetSource`, `Sdi_DatasetField` (lands on the
other `~distinct` set).

## Filters — declared, unapplied, for a downstream mapping to reference

`SdiOutstandingAck` (`sdi.SDI_ACK.ACKNOWLEDGED_AT is null` — the operational one),
`SdiLiveDataset` (`sdi.SDI_DATASET.RETIRED_ON is null`),
`SdiCurrentSubscription` (`sdi.SDI_SUBSCRIPTION.CANCELLED_ON is null`),
`SdiFailedDelivery` (`sdi.SDI_DELIVERY.FAILURE_CODE is not null`).

Reference them as `[static_distribution::Store]SdiOutstandingAck` — the qualifier names the
store the filter LIVES in. Schema-qualified column references inside a `Filter` body compile.
None uses a boolean literal.

## Properties a downstream project navigates

| on class | property | type | reaches |
| --- | --- | --- | --- |
| `SdiDataset` | `sourceList` | `static_core::StcCodeList[0..1]` | target set `stcCodeList` |
| `SdiDataset` | `contentOwner` | `static_core::StcDataOwner[0..1]` | target set `stcDataOwner` |
| `SdiDataset` | `upstreamSource` | `reference_data::RdReferenceSource[0..1]` | target set `rdReferenceSource` |
| `SdiDataset` | `governedField` | `SdiUpstreamField[0..1]` | the `~distinct` set |
| `SdiPublicationItem` | `codeValue` | `static_core::StcCodeValue[0..1]` | target set `stcCodeValue`, composite join |
| `SdiEntitlement` | `domain` | `SdiDistributedDomain[0..1]` | the `~distinct` set |
| `SdiPublication` | `dataset`, `schedule`, `items`, `acknowledgements` | | |
| `SdiAcknowledgement` | `publication`, `consumer` | `SdiPublication[1]`, `SdiConsumer[0..1]` | `publication` is `[1]` so `checksumAgrees()` can read its checksum |
| `SdiConsumer` | `channel`, `subscriptions` | | |
| `SdiDeliveryAttempt` | `publication`, `consumer`, `channel` | | |

## Verify

    python3 scripts/projects/check.py static-distribution   # compiles
