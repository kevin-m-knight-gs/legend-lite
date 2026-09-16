# core-account

Layer 0, no dependencies. The account master: accounts keyed by `(institutionId, accountNo)`,
with the ownership block denormalised onto the account row.

Exports **classes**, a **store** and a **mapping**. No enums, no profiles, no functions, no
associations, no `###Data`, no Runtime.

## The two constructs a downstream project should know about

**Composite primary key.** An account number is unique only within the institution that
issued it, so `core_account::Account` is keyed by the pair `(institutionId, accountNo)` and
every child record carries both columns plus its own discriminator. There is no synthetic
single-column account id to join on: a downstream store joining to `CA_ACCOUNT` must match
**both** `INSTITUTION_ID` and `ACCOUNT_NO`, or it will match accounts at other institutions.

**Embedded properties.** `Account.ownership` (and `ownership.taxResidence` nested inside it),
`Account.correspondence`, and `AccountMandate.holder` are complex-typed properties whose
columns live on the **same row** as their owner. No join is emitted to read them.
`core_account::Ownership`, `TaxResidence`, `Correspondence` and `MandateHolder` are value
types with no table and no root set implementation — they are only reachable through the
class that embeds them, and cannot be mapped or queried as roots.

## Elements

| element | kind | note |
| --- | --- | --- |
| core_account::Institution | class | account-issuing bank/broker/custodian; key `institutionId` |
| core_account::Branch | class | branch of an institution; composite key `(institutionId, branchCode)` |
| core_account::Account | class | the root; composite key `(institutionId, accountNo)`; embeds `ownership`, `correspondence`; derived `isOpen()`, `accountKey()` |
| core_account::Ownership | class | EMBEDDED value type on the account row: owner name, type, LEI, since; nests `taxResidence` |
| core_account::TaxResidence | class | EMBEDDED value type nested in `Ownership`: country, TIN, treaty status, form expiry |
| core_account::Correspondence | class | EMBEDDED value type on the account row: address of record and email |
| core_account::AccountIdentifier | class | external names (IBAN, SWIFT, MPID); key `(institutionId, accountNo, scheme)` |
| core_account::AccountStatusEvent | class | lifecycle transitions; key `(institutionId, accountNo, effectiveFrom)` |
| core_account::AccountRestriction | class | holds and freezes; key `(institutionId, accountNo, restrictionCode)`; derived `isActive()` |
| core_account::AccountMandate | class | signing authority; key `(institutionId, accountNo, mandateSeq)`; embeds `holder` |
| core_account::MandateHolder | class | EMBEDDED value type on the mandate row: named person and their id |
| core_account::Store | store | tables CA_INSTITUTION, CA_BRANCH, CA_ACCOUNT, CA_ACCOUNT_IDENTIFIER, CA_ACCOUNT_STATUS_EVENT, CA_ACCOUNT_RESTRICTION, CA_ACCOUNT_MANDATE; joins Ca_AccountInstitution, Ca_BranchInstitution, Ca_AccountBranch, Ca_AccountIdentifiers, Ca_AccountStatusEvents, Ca_AccountRestrictions, Ca_AccountMandates |
| core_account::Mapping | mapping | set ids caInstitution, caBranch, caAccount, caAccountIdentifier, caAccountStatusEvent, caAccountRestriction, caAccountMandate |

## Store detail

| table | primary key | note |
| --- | --- | --- |
| CA_INSTITUTION | INSTITUTION_ID | the only single-column key in the project |
| CA_BRANCH | INSTITUTION_ID, BRANCH_CODE | branch codes repeat between institutions |
| CA_ACCOUNT | INSTITUTION_ID, ACCOUNT_NO | wide: OWNER_*, TAX_* and CORR_* are the embedded blocks |
| CA_ACCOUNT_IDENTIFIER | INSTITUTION_ID, ACCOUNT_NO, SCHEME | one identifier per scheme per account |
| CA_ACCOUNT_STATUS_EVENT | INSTITUTION_ID, ACCOUNT_NO, EFFECTIVE_FROM | history; CA_ACCOUNT.STATUS is the current value |
| CA_ACCOUNT_RESTRICTION | INSTITUTION_ID, ACCOUNT_NO, RESTRICTION_CODE | LIFTED_ON null means still in force |
| CA_ACCOUNT_MANDATE | INSTITUTION_ID, ACCOUNT_NO, MANDATE_SEQ | HOLDER_* is the embedded holder block |

| join | columns |
| --- | --- |
| Ca_AccountInstitution | CA_ACCOUNT.INSTITUTION_ID = CA_INSTITUTION.INSTITUTION_ID |
| Ca_BranchInstitution | CA_BRANCH.INSTITUTION_ID = CA_INSTITUTION.INSTITUTION_ID |
| Ca_AccountBranch | INSTITUTION_ID **and** BRANCH_CODE |
| Ca_AccountIdentifiers | INSTITUTION_ID **and** ACCOUNT_NO |
| Ca_AccountStatusEvents | INSTITUTION_ID **and** ACCOUNT_NO |
| Ca_AccountRestrictions | INSTITUTION_ID **and** ACCOUNT_NO |
| Ca_AccountMandates | INSTITUTION_ID **and** ACCOUNT_NO |

## Verified

    python3 scripts/projects/check.py core-account
    compiles  core-account (+0 deps)
