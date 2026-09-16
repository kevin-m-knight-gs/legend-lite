# cash-core

Layer 1. Depends on **core-account** (the account master) and **core-types** (the currency
and country enums, and the rounding function). The cash book: movements in and out of an
account, the balances they produce, the bank statements they are reconciled against, and the
sweeps that concentrate them.

Exports **classes**, a **store** and a **mapping**. No enums of its own, no profiles, no
functions, no associations, no `###Data`, no Runtime.

Prefixes: elements `Csh`, tables `CSH_`, joins `Csh_`, set ids `csh`.

## The three constructs a downstream project should know about

**A currency is an enum from another project.** `core_types::CtCurrency` is the type of
`CshCashMovement.currency`, `CshCashBalance.currency`, `CshStatement.currency`,
`CshStatementLine.currency`, `CshBalanceBreak.currency`, `CshCashAccountProfile.baseCurrency`,
`CshCashPool.poolCurrency`, `CshSweepRule.sweepCurrency` and `CshInterestAccrual.currency`.
There is no local currency enum and no currency String above the mapping. The columns hold
ISO 4217 codes and `cash_core::Mapping` declares `core_types::CtCurrency: EnumerationMapping
CshCurrencyMapping` — all 25 values, each accepting the alpha code and the numeric code
(`USD: ['USD', '840']`), because a cash book reads both our own files and ISO 20022 messages.
`core_types::CtCountry` is mapped the same way by `CshCountryMapping` and is applied inside
an embedded block. A downstream project that wants a different physical encoding declares its
own `EnumerationMapping` for the same upstream enum; it does not need this one.

**Embedded properties.** `CshCashMovement.paymentReference` (and
`paymentReference.orderingParty` nested inside it) and `CshStatementLine.paymentReference` are
complex-typed properties whose columns live on the **same row** as their owner. No join is
emitted to read them. `cash_core::CshPaymentReference` and `cash_core::CshOrderingParty` are
value types with no table and no root set implementation — they are only reachable through
the class that embeds them and cannot be mapped or queried as roots. The same value type is
embedded on two different tables on purpose: the reference frozen on our movement and the
bank's copy on the statement line are different facts that have to be compared.

**Every account reference is two columns.** core-account keys an account by
`(institutionId, accountNo)` and there is no synthetic account id, so every class here that
points at an account carries both columns and every join to `CA_ACCOUNT` matches both.
`CshSweepRule` carries **two** such pairs — `SOURCE_*` and `TARGET_*` — reached by two
separate joins to the same table.

## Elements

| element | kind | note |
| --- | --- | --- |
| cash_core::CshCashAccountProfile | class | cash-handling config of one account; key `(institutionId, accountNo)`; `baseCurrency: CtCurrency`; derived `isOpenForCash()`, `accountKey()` |
| cash_core::CshCashMovement | class | the central fact: cash in or out; key `(institutionId, accountNo, movementId)`; `valueDate` **and** `bookingDate`; `currency: CtCurrency`; embeds `paymentReference`; derived `isCredit()`, `signedAmount()`, `isBackValued()`, `roundedAmount()`, `money()` |
| cash_core::CshPaymentReference | class | EMBEDDED value type on the movement row **and** the statement-line row: end-to-end id, scheme, purpose, remittance text; nests `orderingParty`; derived `isTraceable()` |
| cash_core::CshOrderingParty | class | EMBEDDED value type nested in `CshPaymentReference`: who sent the money; `country: core_types::CtCountry[0..1]` |
| cash_core::CshMovementStatusEvent | class | status history of a movement; key `(institutionId, accountNo, movementId, statusSeq)` |
| cash_core::CshCashBalance | class | balance of one account in one currency on one date; key `(institutionId, accountNo, currency, balanceDate)`; `balanceBasis` says VALUE or BOOKING; derived `netMovement()`, `isOverdrawn()`, `unavailableAmount()` |
| cash_core::CshStatement | class | the bank's statement of an account for a date and currency; same key shape as the balance; derived `statedNetMovement()` |
| cash_core::CshStatementLine | class | one line of a statement; key `(institutionId, accountNo, currency, statementDate, lineNo)`; embeds `paymentReference`; derived `isUnmatched()` |
| cash_core::CshBalanceBreak | class | our balance vs the bank's; key `(institutionId, accountNo, currency, balanceDate, breakCode)`; derived `isOpen()` |
| cash_core::CshCashPool | class | a set of accounts swept together plus the header account; key `poolId` — the only single-column key here |
| cash_core::CshSweepRule | class | standing sweep instruction; key `(sourceInstitutionId, sourceAccountNo, ruleSeq)`; names a source **and** a target account; derived `isActive()`, `routeKey()` |
| cash_core::CshSweepExecution | class | one run of a sweep rule; key `(sourceInstitutionId, sourceAccountNo, ruleSeq, executionDate)`; derived `didMove()` |
| cash_core::CshInterestAccrual | class | interest accrued on a day's balance; key `(institutionId, accountNo, currency, accrualDate)`; derived `dailyRate()` |
| cash_core::Store | store | `include core_account::Store`; tables CSH_CASH_ACCOUNT_PROFILE, CSH_CASH_MOVEMENT, CSH_MOVEMENT_STATUS_EVENT, CSH_CASH_BALANCE, CSH_STATEMENT, CSH_STATEMENT_LINE, CSH_BALANCE_BREAK, CSH_CASH_POOL, CSH_SWEEP_RULE, CSH_SWEEP_EXECUTION, CSH_INTEREST_ACCRUAL |
| cash_core::Mapping | mapping | `include core_account::Mapping`; enumeration mappings CshCurrencyMapping, CshCountryMapping; set ids cshCashAccountProfile, cshCashMovement, cshMovementStatusEvent, cshCashBalance, cshStatement, cshStatementLine, cshBalanceBreak, cshCashPool, cshSweepRule, cshSweepExecution, cshInterestAccrual |

Set ids are explicit, so a downstream `extends [...]` or cross-project `AssociationMapping`
must name them — `cshCashMovement`, not `cash_core_CshCashMovement`.

## Store detail

| table | primary key | note |
| --- | --- | --- |
| CSH_CASH_ACCOUNT_PROFILE | INSTITUTION_ID, ACCOUNT_NO | one row per account; POOL_ID is the pool membership |
| CSH_CASH_MOVEMENT | INSTITUTION_ID, ACCOUNT_NO, MOVEMENT_ID | wide: PAY_* is the embedded reference, PAY_ORD_* the ordering party nested in it |
| CSH_MOVEMENT_STATUS_EVENT | INSTITUTION_ID, ACCOUNT_NO, MOVEMENT_ID, STATUS_SEQ | history; CSH_CASH_MOVEMENT.STATUS is the current value |
| CSH_CASH_BALANCE | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, BALANCE_DATE | an account holds more than one currency, so the currency is in the key |
| CSH_STATEMENT | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, STATEMENT_DATE | deliberately the same grain as CSH_CASH_BALANCE |
| CSH_STATEMENT_LINE | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, STATEMENT_DATE, LINE_NO | PAY_*/PAY_ORD_* again — the bank's copy of the reference |
| CSH_BALANCE_BREAK | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, BALANCE_DATE, BREAK_CODE | RESOLVED_ON null means still open |
| CSH_CASH_POOL | POOL_ID | HEADER_INSTITUTION_ID/HEADER_ACCOUNT_NO is the concentration account |
| CSH_SWEEP_RULE | SOURCE_INSTITUTION_ID, SOURCE_ACCOUNT_NO, RULE_SEQ | TARGET_INSTITUTION_ID/TARGET_ACCOUNT_NO is the other end; DEACTIVATED_ON null means live |
| CSH_SWEEP_EXECUTION | SOURCE_INSTITUTION_ID, SOURCE_ACCOUNT_NO, RULE_SEQ, EXECUTION_DATE | a rule runs at most once a day |
| CSH_INTEREST_ACCRUAL | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE, ACCRUAL_DATE | same grain as the balance it is computed from |

| join | columns |
| --- | --- |
| Csh_ProfileAccount | INSTITUTION_ID **and** ACCOUNT_NO, to CA_ACCOUNT |
| Csh_MovementAccount | INSTITUTION_ID **and** ACCOUNT_NO, to CA_ACCOUNT |
| Csh_BalanceAccount | INSTITUTION_ID **and** ACCOUNT_NO, to CA_ACCOUNT |
| Csh_StatementAccount | INSTITUTION_ID **and** ACCOUNT_NO, to CA_ACCOUNT |
| Csh_AccrualAccount | INSTITUTION_ID **and** ACCOUNT_NO, to CA_ACCOUNT |
| Csh_PoolHeaderAccount | HEADER_INSTITUTION_ID **and** HEADER_ACCOUNT_NO, to CA_ACCOUNT |
| Csh_SweepRuleSourceAccount | SOURCE_INSTITUTION_ID **and** SOURCE_ACCOUNT_NO, to CA_ACCOUNT |
| Csh_SweepRuleTargetAccount | TARGET_INSTITUTION_ID **and** TARGET_ACCOUNT_NO, to CA_ACCOUNT |
| Csh_MovementProfile | INSTITUTION_ID and ACCOUNT_NO |
| Csh_BalanceProfile | INSTITUTION_ID and ACCOUNT_NO |
| Csh_MovementStatusEvents | INSTITUTION_ID, ACCOUNT_NO and MOVEMENT_ID |
| Csh_BalanceStatement | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE and BALANCE_DATE = STATEMENT_DATE |
| Csh_BalanceBreaks | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE and BALANCE_DATE |
| Csh_BalanceAccruals | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE and BALANCE_DATE = ACCRUAL_DATE |
| Csh_StatementLines | INSTITUTION_ID, ACCOUNT_NO, CURRENCY_CODE and STATEMENT_DATE |
| Csh_StatementLineMovement | INSTITUTION_ID, ACCOUNT_NO and MOVEMENT_ID |
| Csh_PoolSweepRules | POOL_ID |
| Csh_PoolMembers | POOL_ID |
| Csh_SweepRuleExecutions | SOURCE_INSTITUTION_ID, SOURCE_ACCOUNT_NO and RULE_SEQ |
| Csh_SweepExecutionMovement | SOURCE_INSTITUTION_ID, SOURCE_ACCOUNT_NO and MOVEMENT_ID |

## Verified

    python3 scripts/projects/check.py cash-core
    compiles  cash-core (+2 deps)
