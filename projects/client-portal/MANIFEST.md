# client-portal

Layer 3. Depends on **client-reporting** and **fee-billing**, and on nothing else. Package root
`client_portal::`, prefixes `Cpo` (classes), `CPO_` (tables and views), `Cpo_` (joins), `Cpo`
(filters), `cpo` (set ids).

Exports **classes**, a **store**, a **mapping** and **29 associations**. No enums, no profiles,
no functions, no `###Data`, no Runtime.

## What this project is, and what it deliberately is not

A portal is **not the reports**. client-reporting already produced the statements, priced them,
totalled them and delivered them. What is missing above that is everything deciding whether a
particular human, logged in from a particular device on a particular Tuesday, is shown a
particular document at all — and the record of what was in fact shown, which is the only thing
a complaint can be answered from two years later.

So this project owns six things and **none of them are numbers**:

| owns | class |
| --- | --- |
| ENTITLEMENT — who may see whose statements, at what depth, until when | `CpoUserEntitlement` |
| SESSION — the logged-in stretch everything else hangs off | `CpoSession` |
| NAVIGATION — the menu tree and the permission each branch demands | `CpoNavigationNode`, `CpoNodeAccess` |
| ACKNOWLEDGEMENT — what must be accepted, and what was | `CpoAcknowledgementRequest`, `CpoAcknowledgementReceipt` |
| OUTSTANDING — the invoices as the screen shows them | `CpoInvoiceView`, `CpoPaymentInstruction` |
| AUDIT — what was rendered, to whom, when, and against which text | `CpoDocumentView`, `CpoAuditEvent` |

**Four levels from the instrument master.** A holding line on a portal screen is
`core-instrument (L0) → position-keeping (L1) → client-reporting (L2) → here (L3)`, and this
project restates not one number from any of those levels.

## The DIAMOND, which is the shape to know before including this

```
client_portal::Store
  +-- client_reporting::Store --+-- client_core::Store --+-- core_party, core_geo
  |                             +-- position_keeping::Store
  |                             +-- valuation_core::Store
  +-- fee_billing::Store -------+-- client_core::Store --+-- core_party, core_geo
                                +-- fee_core::Store ------- core_tenor::Store
```

`client_core::Store` arrives by **two routes** and resolves to **one**. It does not duplicate
and does not error. This was probed with a two-table scaffold before any of the sixteen classes
was written, because fee-billing's MANIFEST warns of a real diamond for a downstream project
that reaches `core_tenor::Store` twice, and the failure mode would otherwise have surfaced very
late. The same holds for the two `Mapping` includes.

**Consequence for anything downstream of this project:** `include client_portal::Store` brings
in fourteen stores — `CRP_*`, `BIL_*`, `CLI_*`, `PK_*`, `VC_*`, `FEE_*`, `CTN_*`, `CP_*`,
`CG_*`, `CA_*`, `CI_*`, `CPR_*`, `CFX_*` — and **none of them may be included a second time**.
Same for the mappings.

## What is reachable and forbidden

Fourteen projects are in the closure and only **two** may be named. `CLI_CLIENT`, `PK_POSITION`
and `FEE_SCHEDULE` are all on the classpath and would compile; none is named anywhere in this
project. Every client id, mandate id and contact id here is a plain `String` column carried on
this project's own rows. Where a client-core name is genuinely wanted it is one more hop off a
client-reporting object that already reaches it:

    $entry.statement.clientName
    $entry.statement.clientMacroRegionName
    $portalInvoice.invoice.client.legalEntityName

## Exports — classes

| element | kind | note |
| --- | --- | --- |
| `client_portal::CpoPortalUser` | class | a LOGIN, not a client; key `userId`; derived `isActive()`, `hasNeverLoggedIn()`, `isStaff()` |
| `client_portal::CpoUserEntitlement` | class | **the class of this project** — the grant of one login over one client or mandate at one depth; key `entitlementId`; derived `isLive()`, `coversWholeRelationship()`, `rank(): Integer[1]`, `canAcknowledge()` |
| `client_portal::CpoSession` | class | one continuous visit; key `sessionId`; `impersonatedClientId` separates staff acting-as from the client's own reading; derived `isOpen()`, `isImpersonation()`, `timedOut()` |
| `client_portal::CpoNavigationNode` | class | one branch of the menu tree; key `nodeCode`; parent is a **{target} SELF-JOIN**; derived `isRoot()`, `isLive()` |
| `client_portal::CpoNodeAccess` | class | the permission a node demands before it renders; key `nodeAccessId`; derived `requiredRank(): Integer[1]` |
| `client_portal::CpoDocumentEntry` | class | THE JOIN POINT — the portal's decision to publish a document; key `entryId`; `statementId` and `deliveryId` both nullable; derived `isPublished()`, `isPortalOnly()`, `matchesDispatchedFile()` |
| `client_portal::CpoDocumentView` | class | one opening of one document by one user in one session — the audit row a complaint is answered from; key `viewId`; `openedDate` is stamped so the view can group on it; derived `wasEngaged()`, `hashAgrees()` |
| `client_portal::CpoAcknowledgementRequest` | class | a document awaiting acceptance and its deadline; key `requestId`; derived `isCancelled()`, `isOutstanding()`, `wasEscalated()` |
| `client_portal::CpoAcknowledgementReceipt` | class | the click that answered it, at most one per request so `requestId` is the whole key; `statementTextHash` records WHICH WORDING was accepted; derived `byStaffProxy()`, `isInformed()` |
| `client_portal::CpoInvoiceView` | class | an invoice as the SCREEN shows it; key `portalInvoiceId`; derived `isSettled()`, `hasBeenOpened()`, `agreesWithBiller()`, `settledFraction(): Float[1]` |
| `client_portal::CpoPaymentInstruction` | class | the client telling the portal to pay; key `instructionId`; derived `isSettled()`, `wasRejected()`, `isPartPayment()` |
| `client_portal::CpoAlert` | class | what the portal is putting in front of the client; key `alertId`; one nullable FK into EACH dependency on the same row (`noticeId`, `disputeId`); derived `isLive()`, `needsAction()`, `isRegulatory()` |
| `client_portal::CpoAuditEvent` | class | everything the portal did, succeeded or not — a `DENIED` event has no view row and is what an access review looks for; key `eventId`; derived `wasDenied()`, `isReadOfDocument()` |
| `client_portal::CpoEntryViewTotal` | class | AGGREGATE on a view: a document read as one row; derived `averageSecondsPerView(): Float[1]`, `wasRevisited()` |
| `client_portal::CpoUserActivityTotal` | class | AGGREGATE on a view: one login on one day; derived `averageDurationMs(): Float[1]`, `isHeavyDay()` |
| `client_portal::CpoClientOutstandingTotal` | class | AGGREGATE on a view: what one client owes across every invoice on their screen; derived `outstandingFraction(): Float[1]`, `isClear()` |

16 classes.

## Exports — store and mapping

| element | kind | note |
| --- | --- | --- |
| `client_portal::Store` | store | `include`s both dependency stores; 13 tables `CPO_*`, 3 views, 29 joins `Cpo_*`, 5 filters `Cpo*` |
| `client_portal::Mapping` | mapping | `include`s both dependency mappings; 16 class sets `cpo*`, 29 association mappings |

## The AGGREGATION shape — three views with `~groupBy`

Legend views, not database views: no DDL, nothing seeds them, and the engine folds the
`GROUP BY` into the SQL it generates. Each `~groupBy` **is** the primary key and is what the row
MEANS.

| view | `~groupBy` | one row is | measures |
| --- | --- | --- | --- |
| `CPO_ENTRY_VIEW_TOTAL` | `CPO_DOCUMENT_VIEW.ENTRY_ID` | a DOCUMENT, every opening of it ever | `count`, two `sum`, `min` and `max` of a DATE |
| `CPO_USER_DAY_TOTAL` | `CPO_AUDIT_EVENT.(USER_ID, EVENT_DATE)` | one login on one DAY — the grain is the PAIR | `count`, one `sum` |
| `CPO_CLIENT_OUTSTANDING_TOTAL` | `CPO_INVOICE_VIEW.CLIENT_ID` | a CLIENT, invoice and run and period all DROPPED | `count`, two `sum`, `max` of a DATE |

**An empty group produces no row.** A document nobody has ever opened is **absent** from
`CPO_ENTRY_VIEW_TOTAL` rather than present with a count of zero, so "never opened" must be read
from `CpoDocumentEntry` and not from the aggregate. `OPENED_DATE` and `EVENT_DATE` exist as
columns alongside their `TIMESTAMP`s precisely because a `~groupBy` takes column REFERENCES and
not expressions.

`CPO_CLIENT_OUTSTANDING_TOTAL` cannot be reconstructed from `fee_billing::BilRunTotal`: a run is
not a client.

## Exports — associations

29 of them. 22 are internal; **7 cross a project boundary and they reach BOTH dependencies**.

Every cross-boundary end puts a property on the DEPENDENCY'S class. Both MANIFESTs were read
before these names were chosen, because that namespace is shared and nobody owns it —
client-reporting has already put `clientStatements`, `deliveries` and nine more onto its own
classes.

### Into client-reporting

| association | ends |
| --- | --- |
| `CpoEntryStatement` | `CrpStatement[0..1]` `statement` ↔ `CpoDocumentEntry[*]` `portalEntries` |
| `CpoEntryDelivery` | `CrpDelivery[0..1]` `delivery` ↔ `CpoDocumentEntry[0..1]` `portalEntry` |
| `CpoRequestDisclosure` | `CrpCostsDisclosure[0..1]` `disclosure` ↔ `CpoAcknowledgementRequest[*]` `disclosureRequests` |
| `CpoAlertNotice` | `CrpDepreciationNotice[0..1]` `notice` ↔ `CpoAlert[*]` `portalAlerts` |

### Into fee-billing

fee-billing exports **no** associations of its own, so these three are the first properties any
project has put onto `BilInvoice` and `BilDispute`.

| association | ends |
| --- | --- |
| `CpoInvoiceViewInvoice` | `BilInvoice[0..1]` `invoice` ↔ `CpoInvoiceView[0..1]` `portalView` |
| `CpoInstructionInvoice` | `BilInvoice[0..1]` `instructedInvoice` ↔ `CpoPaymentInstruction[*]` `invoiceInstructions` |
| `CpoAlertDispute` | `BilDispute[0..1]` `dispute` ↔ `CpoAlert[*]` `disputeAlerts` |

### Internal

`CpoUserEntitlements` (`entitledUser`/`entitlements`), `CpoUserSessions` (`sessionUser`/
`sessions`), `CpoNodeParent` (`parentNode`/`childNodes` — **both ends the same set**),
`CpoNodeAccessRules` (`accessNode`/`accessRules`), `CpoEntryNode` (`entryNode`/`nodeEntries`),
`CpoEntryViews` (`entry`/`views`), `CpoSessionViews` (`viewSession`/`documentViews`),
`CpoUserViews` (`viewingUser`/`userViews`), `CpoEntryAckRequests` (`requestedEntry`/
`acknowledgementRequests`), `CpoRequestReceipt` (`request`/`receipt`), `CpoReceiptUser`
(`acceptingUser`/`receipts`), `CpoReceiptSession` (`receiptSession`/`sessionReceipts`),
`CpoInvoiceViewEntry` (`invoiceEntry`/`invoiceViews`), `CpoInvoicePayments` (`portalInvoice`/
`instructions`), `CpoInstructionUser` (`instructingUser`/`userInstructions`), `CpoAlertUser`
(`alertedUser`/`alerts`), `CpoAlertEntry` (`alertEntry`/`entryAlerts`), `CpoSessionAudit`
(`auditSession`/`auditEvents`), `CpoUserAudit` (`auditUser`/`userAuditEvents`),
`CpoEntryViewTotals` (`totalEntry`/`viewTotal`), `CpoUserActivityTotals` (`activityUser`/
`activityTotals`), `CpoClientOutstandingTotals` (`outstandingTotal`/`clientInvoiceViews`).

## Set ids (a GLOBAL namespace — reference these, do not guess)

    cpoUser        cpoEntitlement    cpoSession       cpoNavNode       cpoNodeAccess
    cpoDocumentEntry  cpoDocumentView  cpoAckRequest  cpoAckReceipt    cpoInvoiceView
    cpoPaymentInstruction  cpoAlert   cpoAuditEvent
    cpoEntryViewTotal   cpoUserActivityTotal   cpoClientOutstandingTotal

All sixteen are explicit and **none is marked root**, so the default ids
(`client_portal_CpoDocumentEntry` and the rest) **do not exist**. A downstream `extends [...]`
or cross-project `AssociationMapping` must name the id above.

## Store surface

| table | primary key | note |
| --- | --- | --- |
| `CPO_USER` | `USER_ID` | a login; `USER_TYPE` `STAFF` sees the portal as the client does |
| `CPO_ENTITLEMENT` | `ENTITLEMENT_ID` | `CLIENT_ID`/`MANDATE_ID` are client-core ids carried as **plain columns**, never joined |
| `CPO_SESSION` | `SESSION_ID` | `IMPERSONATED_CLIENT_ID` non-null is staff acting-as |
| `CPO_NAV_NODE` | `NODE_CODE` | `PARENT_NODE_CODE` points back at this table |
| `CPO_NODE_ACCESS` | `NODE_ACCESS_ID` | one branch can demand different things of different scopes |
| `CPO_DOCUMENT_ENTRY` | `ENTRY_ID` | FKs `STATEMENT_ID`, `DELIVERY_ID` (both nullable), `NODE_CODE`; `RENDERED_HASH` faces `CRP_DELIVERY.DOCUMENT_HASH` |
| `CPO_DOCUMENT_VIEW` | `VIEW_ID` | `OPENED_DATE` alongside `OPENED_AT` so the view groups on a DATE |
| `CPO_ACK_REQUEST` | `REQUEST_ID` | FK `DISCLOSURE_ID`, nullable |
| `CPO_ACK_RECEIPT` | `REQUEST_ID` | at most one per request |
| `CPO_INVOICE_VIEW` | `PORTAL_INVOICE_ID` | FK `INVOICE_ID`; `AMOUNT_SHOWN` stored, not read through the join |
| `CPO_PAYMENT_INSTRUCTION` | `INSTRUCTION_ID` | carries `INVOICE_ID` as well as `PORTAL_INVOICE_ID` |
| `CPO_ALERT` | `ALERT_ID` | `NOTICE_ID` (client-reporting) and `DISPUTE_ID` (fee-billing) on the same row |
| `CPO_AUDIT_EVENT` | `EVENT_ID` | `EVENT_DATE` alongside `EVENT_AT`; `TARGET_KIND`/`TARGET_ID` is a loose pointer on purpose |

Money is `DOUBLE`, counts are `INTEGER`, points in time are `TIMESTAMP`, dates are `DATE`.
`REAL` is not used: it parses and then cannot be read back at execution (F53).

Filters, declared and unapplied, all five **null tests** because a `Filter` will not take a
boolean literal (`IS_LANDING = true` is `Unexpected token 'true'`): `CpoLiveEntitlements`
(`REVOKED_ON is null`), `CpoOpenSessions` (`ENDED_AT is null`), `CpoPublishedEntries`
(`UNPUBLISHED_ON is null`), `CpoLiveAckRequests` (`CANCELLED_ON is null`),
`CpoUndismissedAlerts` (`DISMISSED_AT is null`).

## Joins

| join | note |
| --- | --- |
| `Cpo_NodeParent` | `CPO_NAV_NODE.PARENT_NODE_CODE = {target}.NODE_CODE` — the **SELF-JOIN**. Without `{target}` the condition reads as a column compared with itself and every node becomes its own parent |
| `Cpo_UserEntitlements`, `Cpo_UserSessions`, `Cpo_NodeAccessRules`, `Cpo_EntryNode`, `Cpo_EntryViews`, `Cpo_SessionViews`, `Cpo_UserViews`, `Cpo_EntryAckRequests`, `Cpo_RequestReceipt`, `Cpo_ReceiptUser`, `Cpo_ReceiptSession`, `Cpo_InvoiceViewEntry`, `Cpo_InvoicePayments`, `Cpo_InstructionUser`, `Cpo_AlertUser`, `Cpo_AlertEntry`, `Cpo_SessionAudit`, `Cpo_UserAudit` | single-column key joins, local |
| `Cpo_EntryViewTotal`, `Cpo_UserActivityTotal`, `Cpo_ClientOutstandingTotal` | onto the three views |
| `Cpo_EntryStatement`, `Cpo_EntryDelivery`, `Cpo_RequestDisclosure`, `Cpo_AlertNotice` | cross-project, into `CRP_*` |
| `Cpo_PortalInvoice`, `Cpo_InstructionInvoice`, `Cpo_AlertDispute` | cross-project, into `BIL_*` |

## Properties a downstream project navigates without declaring a join

| on class | property | reaches |
| --- | --- | --- |
| `CpoDocumentEntry` | `statement` | `CrpStatement`, set id `crpStatement` |
| `CpoDocumentEntry` | `delivery` | `CrpDelivery`, set id `crpDelivery` |
| `CpoAcknowledgementRequest` | `disclosure` | `CrpCostsDisclosure`, set id `crpCostsDisclosure` |
| `CpoAlert` | `notice` | `CrpDepreciationNotice`, set id `crpDepreciationNotice` |
| `CpoInvoiceView` | `invoice` | `BilInvoice`, set id `bilInvoice` |
| `CpoPaymentInstruction` | `instructedInvoice` | `BilInvoice`, set id `bilInvoice` |
| `CpoAlert` | `dispute` | `BilDispute`, set id `bilDispute` |

And the far ends, which are new properties on the dependencies' classes:

    $crpStatement.portalEntries->filter(e | $e.isPublished())
    $crpDelivery.portalEntry.renderedHash
    $bilInvoice.portalView.outstandingAmount
    $bilInvoice.invoiceInstructions->filter(i | $i.isSettled())
    $bilDispute.disputeAlerts->filter(a | $a.isLive())

Some longer walks this project makes possible:

    $entry.statement.valuationStatement.lines.position.instrument     // four levels down
    $entry.statement.clientMacroRegionName                            // six hops, four projects
    $user.entitlements->filter(e | $e.isLive() && $e.canAcknowledge())
    $user.sessions.documentViews->filter(v | $v.wasEngaged()).entry.title
    $request.receipt.statementTextHash                                // WHICH wording was accepted
    $invoiceView.invoice.chargeAfterLimits()                          // against .amountShown
    $invoiceView.outstandingTotal.outstandingFraction()

## Notes for downstream

- Two columns are stored that could have been derived, and both deliberately:
  `CpoInvoiceView.amountShown` against `BilInvoice.chargeAfterLimits()`, and
  `CpoDocumentEntry.renderedHash` against `CrpDelivery.documentHash`. A derived property would
  have made the two sides agree by construction; stored, a disagreement is **visible**, and a
  portal showing a different number or serving a different PDF than was dispatched is the
  defect worth catching.
- `CpoUserEntitlement` is dated on both sides and revocation is a date, not a delete. "Was she
  entitled to see it **on the day**" is the question, and revoking a grant must not rewrite what
  was legitimately shown last March.
- The acknowledgement pair works like client-reporting's check/notice pair: the REQUEST is the
  obligation and exists whether or not it was answered. Do not filter unanswered requests away —
  they are the queue.
- `CpoAuditEvent` is wider than `CpoDocumentView` on purpose. A `DENIED` event has no view row,
  and that asymmetry is the whole reason both classes exist.
- `CpoNodeAccess.requiredRank()` and `CpoUserEntitlement.rank()` are both `Integer[1]` and are
  meant to be compared. Neither is mapped; the ladder is
  `VIEW=1 < DOWNLOAD=2 < ACKNOWLEDGE=3 < INSTRUCT=4`.
- The three `*Total` classes are groups, not rows. Do not join a `CpoDocumentView` to a
  `CpoEntryViewTotal` expecting one reading.
- Tables and views are declared and unseeded. No `###Data` element, no `Runtime`.

## Verified

    python3 scripts/projects/check.py client-portal
    compiles  client-portal (+14 deps)  parse 522ms  compile 2394ms
