# fee-billing

Layer 2. Depends on `fee-core` and `client-core`, and on nothing else. Package root
`fee_billing::`, prefixes `BIL_` (tables and views), `Bil_` (joins), `Bil` (filters),
`bil` (set ids).

Billing runs applying tenor-banded fees to clients. A **run** covers a **period** and cuts one
**invoice per client**; an invoice holds **lines by fee type**; a line's rate comes out of a
**band** found by a RANGE JOIN on tenor days.

The shape to know before using it: **the minimum and the cap are properties of the INVOICE,
not of a line.** Sum the lines, add adjustments, take credits off, and only then compare the
result to the agreement's floor and ceiling. The two corrections are stored on `BIL_INVOICE`
as `minimumTopUp` and `capReduction`; there is no per-line floor column anywhere in this
database, because flooring each line and adding them up gives a different and wrong answer.

    BIL_INVOICE_LINE -> BIL_RATE_BAND -> FEE_SCHEDULE          2 hops, first one by RANGE
    BIL_INVOICE      -> CLI_CLIENT -> CG_COUNTRY -> ...        into client-core's own chain
    BIL_AGREEMENT    -> FEE_TIER                               1 hop
    BIL_AGREEMENT    -> CLI_MANDATE                            1 hop

## Exports

| element | kind | note |
| --- | --- | --- |
| `fee_billing::BilBillingPeriod` | class | the stretch of calendar being billed; `closedOn is null` is the whole state machine |
| `fee_billing::BilBillingRun` | class | one pass of the biller over one period — DRAFT, FINAL and reruns are separate runs |
| `fee_billing::BilBillingAgreement` | class | the commercial terms: tier, currency, and the PER-INVOICE `minimumFee` / `maximumFee`. Dated |
| `fee_billing::BilFeeType` | class | MANAGEMENT / CUSTODY / TRANSACTION / PERFORMANCE reference, with the GL account |
| `fee_billing::BilRateBand` | class | one rate over a HALF-OPEN band `[minDays, maxDays)`; reaches `fee_core::FeeSchedule` |
| `fee_billing::BilInvoice` | class | **the class of this project**: what one client owes for one run |
| `fee_billing::BilInvoiceLine` | class | one charge, one fee type, one product; carries `tenorDays` and NO band id |
| `fee_billing::BilCredit` | class | money handed back; nets off at invoice level even when one line caused it |
| `fee_billing::BilAdjustment` | class | a signed correction, invoice-level or line-level (`lineId` is `[0..1]`) |
| `fee_billing::BilDispute` | class | a client saying a charge is wrong; resolves into a credit, not into different lines |
| `fee_billing::BilInvoiceTotal` | class | AGGREGATE, grain = invoice. Rooted on a view |
| `fee_billing::BilRunTotal` | class | AGGREGATE, grain = run. Rooted on a view |
| `fee_billing::BilFeeTypeTotal` | class | AGGREGATE, grain = (invoice, fee type). Rooted on a view |
| `fee_billing::Store` | store | 9 tables `BIL_*`, 3 views, 21 joins `Bil_*`, 4 filters `Bil*`; `include`s both dependency stores |
| `fee_billing::Mapping` | mapping | 13 sets `bil*`; `include`s both dependency mappings |

No associations and no enums are exported: the cross-project links are one-directional
properties on this project's classes, so nothing here adds a property to a dependency's class.

## Set ids

All thirteen are explicit, so a downstream `extends [...]` or cross-project
`AssociationMapping` must name them — the default ids (`fee_billing_BilInvoice` and friends)
do not exist.

    bilPeriod   bilRun        bilAgreement   bilFeeType    bilRateBand
    bilInvoice  bilInvoiceLine  bilCredit    bilAdjustment  bilDispute
    bilInvoiceTotal  bilRunTotal  bilFeeTypeTotal

## Properties a downstream project navigates

| on class | property | reaches |
| --- | --- | --- |
| `BilInvoiceLine` | `band` | `BilRateBand`, by RANGE on `tenorDays` |
| `BilInvoiceLine` | `product` | `fee_core::FeeProduct`, set id `feeProduct` |
| `BilRateBand` | `schedule` | `fee_core::FeeSchedule`, set id `feeSchedule`, three key columns |
| `BilBillingAgreement` | `tier` / `client` / `mandate` | `feeTier` / `cliClient` / `cliMandate` |
| `BilInvoice` | `client` | `client_core::CliClient`, set id `cliClient` |
| `BilInvoice` | `total` / `feeTypeTotals` | the aggregate sets |
| `BilBillingRun` | `total` | `BilRunTotal` |

Because `BilInvoice.client` reaches `CliClient`, everything client-core flattens is one more
hop away: `$invoice.client.domicileMacroRegionName`, `$invoice.client.regulatoryBlocCode`,
`$invoice.client.legalEntityName`.

Derived properties, none of them in the mapping:

    BilBillingPeriod.isOpen()
    BilBillingRun.isComplete()
    BilBillingAgreement.isCapped()  .isCurrent()
    BilRateBand.rate()  .spanDays()  .covers(days: Integer[1])
    BilInvoice.lineGross()  .lineNet()  .creditTotal()  .adjustmentTotal()
    BilInvoice.chargeBeforeLimits()  .chargeAfterLimits()
    BilInvoice.isBelowMinimum()  .isCapped()  .isPaid()  .hasOpenDispute()
    BilInvoiceLine.appliedRate()  .recomputedGross()  .averagePerItem()  .isDisputed()
    BilCredit.isApplied()  .isApproved()
    BilAdjustment.isPosted()  .isIncrease()
    BilDispute.isOpen()
    BilInvoiceTotal.averageLineNet()

`chargeAfterLimits()` is the one that matters: `chargeBeforeLimits() + minimumTopUp -
capReduction`. Do not try to reconstruct it from lines.

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `BIL_PERIOD` | `PERIOD_CODE` | |
| `BIL_RUN` | `RUN_ID` | FK `PERIOD_CODE` |
| `BIL_AGREEMENT` | `AGREEMENT_ID` | FK `CLIENT_ID`, `MANDATE_ID` (client-core), `TIER_CODE` (fee-core) |
| `BIL_FEE_TYPE` | `FEE_TYPE_CODE` | |
| `BIL_RATE_BAND` | `BAND_ID` | `MIN_DAYS`/`MAX_DAYS` are the far side of the range join; `(PRODUCT_CODE, TIER_CODE, EFFECTIVE_DATE)` reach `FEE_SCHEDULE` |
| `BIL_INVOICE` | `INVOICE_ID` | `MINIMUM_TOP_UP` and `CAP_REDUCTION` are the per-invoice corrections |
| `BIL_INVOICE_LINE` | `LINE_ID` | `TENOR_DAYS` and no band id |
| `BIL_CREDIT` | `CREDIT_ID` | FK `INVOICE_ID`, `SOURCE_INVOICE_ID` |
| `BIL_ADJUSTMENT` | `ADJUSTMENT_ID` | FK `INVOICE_ID`, `LINE_ID` (nullable) |
| `BIL_DISPUTE` | `DISPUTE_ID` | FK `INVOICE_ID`, `LINE_ID`, `CREDIT_ID` |

Views, which are the AGGREGATION shape — not database views, no DDL, nothing seeds them; the
engine folds the GROUP BY into generated SQL. Each `~groupBy` is the view's primary key and is
what the row MEANS:

| view | groupBy | measures |
| --- | --- | --- |
| `BIL_INVOICE_TOTAL` | `BIL_INVOICE_LINE.INVOICE_ID` | `count`, four `sum`, one `max` |
| `BIL_RUN_TOTAL` | `BIL_INVOICE.RUN_ID` | `count`, four `sum` |
| `BIL_FEE_TYPE_TOTAL` | `BIL_INVOICE_LINE.(INVOICE_ID, FEE_TYPE_CODE)` | `count`, two `sum` |

## Joins in `fee_billing::Store`

| join | condition | note |
| --- | --- | --- |
| `Bil_LineRateBand` | `PRODUCT_CODE = ... and TIER_CODE = ... and TENOR_DAYS >= MIN_DAYS and TENOR_DAYS < MAX_DAYS` | **the RANGE JOIN.** Half-open, so a line on a boundary matches one band. The two equalities are needed because the ladder is priced per product and per tier |
| `Bil_BandSchedule` | three columns onto `FEE_SCHEDULE` | cross-project; fee-core's key is composite so one or two columns does not identify a row |
| `Bil_LineProduct` | `BIL_INVOICE_LINE.PRODUCT_CODE = FEE_PRODUCT.PRODUCT_CODE` | cross-project |
| `Bil_AgreementTier` | `BIL_AGREEMENT.TIER_CODE = FEE_TIER.TIER_CODE` | cross-project |
| `Bil_InvoiceClient`, `Bil_AgreementClient` | onto `CLI_CLIENT.CLIENT_ID` | cross-project |
| `Bil_AgreementMandate` | onto `CLI_MANDATE.MANDATE_ID` | cross-project |
| `Bil_RunPeriod`, `Bil_InvoiceRun`, `Bil_InvoiceAgreement`, `Bil_InvoiceLines`, `Bil_LineFeeType`, `Bil_InvoiceCredits`, `Bil_InvoiceAdjustments`, `Bil_LineAdjustments`, `Bil_InvoiceDisputes`, `Bil_LineDisputes` | single-column key joins | local |
| `Bil_InvoiceTotal`, `Bil_RunTotal`, `Bil_InvoiceFeeTypeTotal`, `Bil_FeeTypeTotalFeeType` | onto the views | local |

Filters, declared and unapplied, for a downstream mapping to reference. All four are null
tests, because a `Filter` will not take a boolean literal:
`BilOpenPeriods`, `BilUnpaidInvoices`, `BilOpenDisputes`, `BilUnappliedCredits`.

## Notes for downstream

- `include fee_billing::Store` and `include fee_billing::Mapping`. Including this store
  transitively brings in `fee_core::Store`, `client_core::Store` and — one level further —
  `core_tenor::Store`, `core_party::Store` and `core_geo::Store`. **Do not include any of
  those five a second time.** The same applies to the mappings.
- The two includes here are disjoint at every level, which is why both fit in one database.
  A downstream project that also depends on, say, `funding-core` (which includes
  `core_tenor::Store` itself) has a real diamond and must include only one of the two.
- Anything reaching a fee-core schedule through `BilRateBand.schedule` inherits fee-core's
  composite-key rule: all three columns or nothing.
- The three `*Total` classes are groups, not rows. Do not join a `BilInvoiceLine` to
  `BilInvoiceTotal` expecting one line.
- Tables and views are declared and unseeded. No `###Data` element, no `Runtime`.

## Verify

    python3 scripts/projects/check.py fee-billing   # compiles
