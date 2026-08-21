# core-book

Layer 0, no dependencies. The firm's book hierarchy: a trading book reports to another
trading book, books hang off desks, desks off business units, business units off divisions.

The construct this project exists to exercise is the `{target}` SELF-JOIN --
`Join Cb_ParentBook(CB_BOOK.PARENT_BOOK_ID = {target}.BOOK_ID)` -- and the
`core_book::BookParent` association that rides on it, whose two ends are both
`core_book::Book`.

Prefixes: tables `CB_`, joins `Cb_`, filters `Cb`, set ids `cb`.

| element | kind | note |
| --- | --- | --- |
| core_book::Book | class | trading book; recursive via `parentBook` / `childBooks` |
| core_book::Desk | class | trading desk that owns a set of top-level books |
| core_book::BusinessUnit | class | group of desks reported as one P&L line |
| core_book::Division | class | top of the roll-up; segment reporting level |
| core_book::BookOwner | class | person accountable for a book |
| core_book::Strategy | class | trading strategy a book runs |
| core_book::BookClassification | class | banking vs trading book, regulatory bucket |
| core_book::BookLimit | class | risk limit set on a book |
| core_book::CostCentre | class | finance hierarchy a desk charges to |
| core_book::BookMandate | class | what a book is permitted to hold, and how much |
| core_book::BookApproval | class | governance record for open / amend / close |
| core_book::BookTransfer | class | a book moving from one desk to another |
| core_book::BookParent | association | `parentBook[0..1]` / `childBooks[*]`, both ends `Book` |
| core_book::BookDesk | association | `desk[0..1]` / `books[*]` |
| core_book::DeskBusinessUnit | association | `businessUnit[0..1]` / `desks[*]` |
| core_book::BusinessUnitDivision | association | `division[0..1]` / `businessUnits[*]` |
| core_book::BookOwnership | association | `owner[0..1]` / `ownedBooks[*]` |
| core_book::BookStrategyLink | association | `strategy[0..1]` / `strategyBooks[*]` |
| core_book::BookClassificationLink | association | `classification[0..1]` / `classifiedBooks[*]` |
| core_book::BookLimitLink | association | `limits[*]` / `limitBook[0..1]` |
| core_book::DeskCostCentre | association | `costCentre[0..1]` / `costCentreDesks[*]` |
| core_book::BookMandateLink | association | `mandates[*]` / `mandateBook[0..1]` |
| core_book::BookApprovalLink | association | `approvals[*]` / `approvalBook[0..1]` |
| core_book::BookTransferLink | association | `transfers[*]` / `transferBook[0..1]` |
| core_book::Store | store | tables CB_BOOK, CB_DESK, CB_BUSINESS_UNIT, CB_DIVISION, CB_BOOK_OWNER, CB_STRATEGY, CB_BOOK_CLASSIFICATION, CB_BOOK_LIMIT, CB_COST_CENTRE, CB_BOOK_MANDATE, CB_BOOK_APPROVAL, CB_BOOK_TRANSFER |
| core_book::Mapping | mapping | one set per class; set ids `cbBook`, `cbDesk`, `cbBusinessUnit`, `cbDivision`, `cbBookOwner`, `cbStrategy`, `cbBookClassification`, `cbBookLimit`, `cbCostCentre`, `cbBookMandate`, `cbBookApproval`, `cbBookTransfer` |

Joins available to downstream projects that include this store: `Cb_ParentBook` (self-join),
`Cb_BookDesk`, `Cb_BookOwner`, `Cb_BookStrategy`, `Cb_BookClassification`,
`Cb_DeskBusinessUnit`, `Cb_DeskCostCentre`, `Cb_BusinessUnitDivision`, `Cb_BookLimit`,
`Cb_BookMandate`, `Cb_BookApproval`, `Cb_BookTransfer`.

Filter: `CbOpenBooks` (`CB_BOOK.CLOSE_DATE is null`).

No `###Data` element and no Runtime, per the contract.
