# PCT expected-failure ledger (36 of 1109)

The PCT suites (`pct/` module, legend-pure's PCT framework over the
LegendLite adapter) run 1109 tests: 1073 pass outright, 36 are ledgered
as `expectedFailures`. **Nothing is skipped**: every ledgered test
executes on every build and must fail with its pinned message
(contains-matched). A ledgered test that starts passing, or fails with a
different shape, FAILS THE BUILD — the ledger self-polices. Surefire
failures gate the build (`testFailureIgnore` was removed after an audit
found it hiding 4 real reds; see `8cb07093`).

The justification standard is **reference parity**: each entry is also
an expected failure of the official legend-engine DuckDB adapter
(`Test_Relational_DuckDB_*_PCT` exclusion sets) — the reference ledgers
these against its own relational backend rather than building carriers.
This document exists so a future in-house PCT runner inherits the ledger
with its reasons, not just its names.

## A. Instance identity & metamodel reflection — impossible over a SQL wire (14)

Legend-lite's #1 tenet is that values execute in the database and return
as data. Reference identity (`assertIs`, `eq` on instances, "give me the
ORIGINAL let-bound object back") and runtime expression reflection
(`deactivate()` returning the ValueSpecification tree) do not exist on
the other side of a value serialization boundary — for us or for any
SQL-executing adapter, which is why the reference excludes the same
class.

| test | note |
|---|---|
| collection::find::testFindInstance | assertIs against original instance |
| collection::find::testFindUsingVarForFunction | assertIs against original instance |
| collection::head::testHeadComplex | nested instance REBUILDS correctly (see below); assert then requires the original address |
| collection::first::testFirstComplex | same as testHeadComplex |
| boolean::equality::eq::testEqNonPrimitive | eq = reference equality; wire inlines captured instances by value |
| boolean::equality::equal::testEqualNonPrimitive | same |
| boolean::equality::eq::testEqPrimitiveExtension | user-defined primitive extension types not in the SQL type system |
| boolean::equality::equal::testEqualPrimitiveExtension | same |
| collection::filter::testFilterInstance | filter by instance identity |
| collection::getAll::testBasic | getAll with no store/mapping context (class-query dispatch needs a mapping) |
| collection::map::testMapRelationshipFromManyToMany | assertIs on let-bound instances |
| collection::map::testMapRelationshipFromManyToOne | same |
| lang::match::testMatchWithMixedReturnType | deactivate() reflects the expression tree; no runtime metamodel exists |
| string::toString::testComplexClassToString | prints the metamodel form of a class instance |

History note (2026-07-28): `testHeadComplex`/`testFirstComplex` were
previously ledgered as a "nested property navigation compile gap". An
audit-driven re-examination found the real bug in the PCT ADAPTER's
result reconstruction (`ExecuteLegendLiteQuery.structToInstance`
resolved every nested property against the PARENT's type and collapsed
multi-valued properties to their first element) — fixed by
`classPropertyTypeOf` (interpreter-metamodel property-type resolution)
and full multi-valued reconstruction. The platform side was always
correct (nested struct carrier verified). Both tests then progress to
their true wall: the assert wants the interpreter's original instance
address, which is category A.

## B. PCT-harness serialization loss — the input is broken before we see it (4)

The PCT wire serializes the interpreter's expression to TEXT for the
adapter. Legend-pure's serializer emits `^$x(prop = expr)`
copy-with-update as `copy('', prop)` — the override VALUE is gone before
our adapter receives anything; OneToOne's captured `$address` never
reaches the serialization at all. The copy-with-update feature itself
works (`TypedCopyInstance`, probed directly).

- collection::fold::testFold
- collection::fold::testFoldFiltering
- collection::fold::testFoldToMany
- collection::map::testMapRelationshipFromOneToOne

Revisitable: the adapter holds the interpreter's `processorSupport`, so
values could be reconstructed from the metamodel instead of text —
future adapter work, not platform work.

## C. The substring/indexOf base divergence — contradictory golden sets (7)

Legend-engine's own two runtimes disagree about string index bases:
platform Pure is 0-based; the engine's RELATIONAL pushdown is 1-based —
`substring` passes the start index verbatim into 1-based SQL
(`testFilterUsingParseIntegerFunction` pins the unshifted SQL text AND
its rows verify 1-based on H2), and `indexOf` translates to `locate()`
verbatim (`testSqlFunctionsInMapping` pins `select locate('o', …)` with
rows `[12,12]`; the propertyfunc mapping family composes `position()`
with 1-based arithmetic). One pipeline cannot satisfy both golden sets;
the corpus (engine-relational parity) is this project's acceptance
surface, and the reference DuckDB adapter ledgers the identical
failures — byte-identical diffs for indexOf testSimple ("expected: 4
actual: 5") and testIndexOfOneElement ("expected: 0 actual: 1").

- string::indexOf::testSimple — 1-based locate parity (C1.5c)
- string::indexOf::testFromIndex — 1-based from/result; the reference
  excludes it outright (no translation at all — ours runs)
- collection::indexof::testIndexOfOneElement — a String[1] receiver
  resolves to string::indexOf; same base divergence

- string::substring::testStart
- string::substring::testStartEnd
- collection::sort::testSimpleSortWithKey — its sort KEY is a substring; the comparator/key sort machinery itself is pinned green in `ValueSortComparatorTest` (a capability the reference lacks entirely: it excludes ALL comparator sorts with "No SQL translation exists")
- collection::sort::testSimpleSortWithFunctionVariables — same

## D. Error source-COLUMN precision (6)

`assertError` pins message + line + column offset within the test
expression. Message and line match real pure exactly; the failing call
executes inside DuckDB and the intra-expression COLUMN cannot be
recovered without source-span plumbing through the SQL wire. Pin shape:
`"Execution error column mismatch. Actual: 23 where expected: 37"` —
only the column number differs. Reference-excluded as well.

- collection::at::testAtError
- date::testDayOfMonthError, testHourError, testMinuteError, testNewDateError, testSecondError

## E. Timestamp carrier domain (4)

Expected results at years 1,400,000–800,000,000 — outside DuckDB's
entire TIMESTAMP range (290309 BC–294246 AD). Honoring them means a
string-domain calendar reimplementation, i.e. abandoning
"database executes" for dates. In-domain BigNumber adjusts (minutes,
microseconds) pass.

- date::testAdjustByDaysBigNumber, testAdjustByHoursBigNumber, testAdjustByMonthsBigNumber, testAdjustByWeeksBigNumber

## F. Result-wire representation hole (1)

- relation::composition::testVariantArrayColumn_joinStrings —
  `joinStrings` over the empty collection is `''` (correct in SQL); the
  TDS text wire cannot represent an empty-string cell (reads as null).
  The pin is the VERBATIM full expected/actual text of the official
  legend-engine DuckDB PCT's pin for the identical failure.

## Maintenance rules

1. New entries require the reference-parity check: find the same test in
   the reference DuckDB adapter's exclusions, or write down explicitly
   why our architecture (not our laziness) fails it.
2. Pins are OUR actual messages, as tight as determinism allows
   (addresses/timing fragments dropped); loose pins keep matching when a
   test regresses for a NEW reason.
3. A ledger entry that stops failing is a FIX — delete the entry (the
   framework forces this: unexpected passes fail the build).
4. Never restore `testFailureIgnore`. Divergences are ledgered here and
   in the pins, or they are bugs.
