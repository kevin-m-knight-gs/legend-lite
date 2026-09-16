# custody-core

Layer 1. Depends on **core-account** and **core-instrument**, and on nothing else.

Custody holdings keyed by account, instrument and depot. A position says how much an
account owns; a holding says *where it is*. The same stock, in the same account, at two
depositories is **two holdings** — that is the whole reason custody reconciliation
exists, and it is why `DEPOT_CODE` is part of the key rather than an attribute.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions,
no associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**Three-column composite primary key.** `custody_core::Depot` — one safekeeping account,
opened for one client account, at one place — is keyed by
`(institutionId, accountNo, depotCode)`. A depot code such as `MAIN` or `OMNIBUS-EU` is
unique only inside the account that owns it, exactly as core-account's account number is
unique only inside its institution. A downstream store joining to `CST_DEPOT` must match
**all three** columns.

The holding one level down is therefore **four** columns wide —
`(institutionId, accountNo, instrumentId, depotCode)` — because the (account, instrument,
depot) grain lands on a two-column account key. Every child of a holding
(`CST_PENDING_MOVEMENT`, `CST_HOLDING_ENCUMBRANCE`, `CST_STATEMENT_LINE`) repeats all
four plus its own discriminator.

**Embedded properties.** `CustodyHolding.quantities` and
`CustodyHolding.placeOfSafekeeping` (with `registration` nested inside it) are
complex-typed properties whose columns live on the **same row**, `CST_HOLDING`. No join
is emitted to read them. `custody_core::HoldingQuantity`, `PlaceOfSafekeeping` and
`Registration` are value types with no table and **no root set implementation** — they
are reachable only through the holding that embeds them and cannot be mapped or queried
as roots.

## Elements

| element | kind | note |
| --- | --- | --- |
| custody_core::Custodian | class | a firm holding securities for somebody else; key `custodianId`; role GLOBAL/SUB/LOCAL_AGENT; derived `isAppointed()` |
| custody_core::Depository | class | CSD/ICSD at the bottom of the chain; key `depositoryId` |
| custody_core::CustodyMarket | class | settlement market whose rules set the cycle; key `marketCode` |
| custody_core::SubCustodianLink | class | one hop of the sub-custodian chain; THREE-column key `(globalCustodianId, marketCode, subCustodianId)`; derived `isCurrent()` |
| custody_core::DepositoryMembership | class | a custodian's participant id at a depository; key `(custodianId, depositoryId)` |
| custody_core::Depot | class | THE THREE-COLUMN KEY `(institutionId, accountNo, depotCode)`; derived `isOpen()`, `depotKey()` |
| custody_core::CustodyHolding | class | the ROOT; key `(institutionId, accountNo, instrumentId, depotCode)`; embeds `quantities` and `placeOfSafekeeping`; derived `holdingKey()`, `hasPendingSettlement()` |
| custody_core::HoldingQuantity | class | EMBEDDED value type on the holding row: settled vs traded, pending receipt/delivery, available, blocked; derived `projectedSettledQuantity()`, `tradedLessSettled()` |
| custody_core::PlaceOfSafekeeping | class | EMBEDDED value type on the holding row: type, BIC, name, country, POS account id; nests `registration` |
| custody_core::Registration | class | EMBEDDED value type nested in `PlaceOfSafekeeping`: NOMINEE/BENEFICIAL/BEARER, registered name, register ref |
| custody_core::PendingMovement | class | one receipt or delivery in flight; key `(institutionId, accountNo, instrumentId, depotCode, movementRef)`; derived `isReceipt()`, `isOutstanding()` |
| custody_core::HoldingEncumbrance | class | pledge/loan/court order over part of a holding; key `(..., encumbranceCode)`; derived `isActive()` |
| custody_core::CustodyStatementLine | class | what the CUSTODIAN said, kept beside what we say; key `(..., statementDate)` |
| custody_core::Store | store | includes core_account::Store and core_instrument::Store; tables CST_CUSTODIAN, CST_DEPOSITORY, CST_MARKET, CST_SUB_CUSTODIAN_LINK, CST_DEPOSITORY_MEMBERSHIP, CST_DEPOT, CST_HOLDING, CST_PENDING_MOVEMENT, CST_HOLDING_ENCUMBRANCE, CST_STATEMENT_LINE |
| custody_core::Mapping | mapping | includes core_account::Mapping and core_instrument::Mapping; 10 set ids, root `cstHolding` |

13 classes. No filters are declared, so nothing here occupies the global filter namespace.

## Set ids (a GLOBAL namespace — extend these, do not re-declare them)

`cstHolding` is the **root** set (`*custody_core::CustodyHolding`); the rest are explicit
and have no default id:

`cstCustodian`, `cstDepository`, `cstMarket`, `cstSubCustodianLink`,
`cstDepositoryMembership`, `cstDepot`, `cstHolding`, `cstPendingMovement`,
`cstHoldingEncumbrance`, `cstStatementLine`.

Because these are explicit, a downstream `extends [...]` or cross-project
`AssociationMapping` must name them — `custody_core_CustodyHolding` does not exist. Two
ids from the dependencies stay relevant downstream: `caAccount` (core-account) and
`ciBase` (core-instrument).

## Store detail

| table | primary key | note |
| --- | --- | --- |
| CST_CUSTODIAN | CUSTODIAN_ID | the safekeeping chain's nodes |
| CST_DEPOSITORY | DEPOSITORY_ID | CSD/ICSD/NCSD |
| CST_MARKET | MARKET_CODE | settlement market |
| CST_SUB_CUSTODIAN_LINK | GLOBAL_CUSTODIAN_ID, MARKET_CODE, SUB_CUSTODIAN_ID | three columns; the same two firms are appointed market by market |
| CST_DEPOSITORY_MEMBERSHIP | CUSTODIAN_ID, DEPOSITORY_ID | DIRECT or INDIRECT participation |
| CST_DEPOT | INSTITUTION_ID, ACCOUNT_NO, DEPOT_CODE | **three columns** |
| CST_HOLDING | INSTITUTION_ID, ACCOUNT_NO, INSTRUMENT_ID, DEPOT_CODE | wide: QTY_* and POS_* (incl. POS_REG_*) are the embedded blocks |
| CST_PENDING_MOVEMENT | + MOVEMENT_REF | RECEIVE/DELIVER, intended vs actual settlement date |
| CST_HOLDING_ENCUMBRANCE | + ENCUMBRANCE_CODE | RELEASED_ON null means still biting |
| CST_STATEMENT_LINE | + STATEMENT_DATE | the custodian's own figure |

| join | columns |
| --- | --- |
| Cst_DepositoryMarket | MARKET_CODE |
| Cst_LinkGlobalCustodian | CST_SUB_CUSTODIAN_LINK.GLOBAL_CUSTODIAN_ID = CST_CUSTODIAN.CUSTODIAN_ID |
| Cst_LinkSubCustodian | CST_SUB_CUSTODIAN_LINK.SUB_CUSTODIAN_ID = CST_CUSTODIAN.CUSTODIAN_ID |
| Cst_LinkMarket | MARKET_CODE |
| Cst_MembershipCustodian | CUSTODIAN_ID |
| Cst_MembershipDepository | DEPOSITORY_ID |
| Cst_DepotCustodian | CUSTODIAN_ID |
| Cst_DepotDepository | DEPOSITORY_ID |
| Cst_DepotMarket | MARKET_CODE |
| Cst_HoldingDepot | INSTITUTION_ID **and** ACCOUNT_NO **and** DEPOT_CODE |
| Cst_HoldingMovements | all four holding key columns |
| Cst_HoldingEncumbrances | all four holding key columns |
| Cst_HoldingStatementLines | all four holding key columns |
| Cst_DepotAccount | CST_DEPOT -> CA_ACCOUNT on INSTITUTION_ID **and** ACCOUNT_NO |
| Cst_HoldingAccount | CST_HOLDING -> CA_ACCOUNT on INSTITUTION_ID **and** ACCOUNT_NO |
| Cst_HoldingInstrument | CST_HOLDING.INSTRUMENT_ID = CI_INSTRUMENT.INSTRUMENT_ID |

## Verified

    python3 scripts/projects/check.py custody-core
    compiles  custody-core (+2 deps)
