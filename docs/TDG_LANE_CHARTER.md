# The ###Data / TDG lane charter (2026-08-30)

The LARGEST remaining decline bucket: `assert-test-data-csv` = **117
asserts, exactly pinned** (RelationalCorpusRunner lane guard), all in ONE
engine family — `meta::relational::testDataGeneration::tests` (~35
tests). Census attributed row-by-row 2026-08-30 (probe on the decline
site, full sweep, reverted); raw rows preserved in the session register.

## Census — the 117 by assert form (measured, no sampling)

| form | count | contract |
|---|---|---|
| assertSqlEquals/2 | 45 | the SQL text the generation pipeline emits (`.sqls`) — engine H2 spellings, golden-SQL doctrine territory |
| assertTestData/3 | 35 | the generated ROWS (the row contract — the actual test data) |
| assertSize/2 | 26 | generated row counts |
| assertEquals/2 | 10 | `necessaryColumns` lists + CSV strings (incl. quoted-columns, no-data cases) |
| assertEqualsH2Compatible/3 | 1 | dual-golden SQL compare |

Families inside the tests: joins/unions (incl. union-to-union multiple
levels + string hashing), inheritance (multi-level, multi-table), views
(root, view-on-view, embedded-in-chained-join, nested), milestoning
(business/processing/bitemporal, with and without dates), OLAP groupBy,
qualifiers, constants, quoted columns, multiple start rows.

## Current architecture (the debt, named)

Generation is ALREADY production Java — `testdatagen/TestDataGenerator`
(eval-ledger registered): `generate(...)` → `Result(sqls, dataCsvString,
...)`, plus `necessaryColumns`, `seedDataString`, `compareCsv`,
`planText`. But ORCHESTRATION and ADJUDICATION are harness-side
(EngineTestExecutor #46): `tdgLetArm` intercepts four binding shapes
(planGenerate / seedDataString / csvCensus / generate), runs the
generator, stores Results keyed by let name; `checkTdgAssert`
adjudicates asserts against those Results in the HARNESS. The
dual-channel names every such assert a `assert-test-data-csv` decline —
correctly, because the production verdict path never sees them. This is
the harness-as-third-implementation pattern (harness-platformization
program) applied to a whole feature: the platform can GENERATE but
cannot EVALUATE the generation surface.

## Target architecture (tenets applied)

The statement list (lets + asserts) rides `evalSpliced` →
`StatementExecutor` → `AssertVerdicts` UNCHANGED — no TDG special-casing
in the harness at all:

1. **K-natives for the TDG surface**: StatementExecutor evaluates the
   engine's testDataGeneration functions by calling the EXISTING
   production generator (Java orchestrates; the generator's row
   materialization already executes through the database). Signatures
   validated against the real engine .pure
   (validate-against-registered-signature).
2. **Result values are platform values**: `Result` surfaces as typed
   platform data (the CSV string, the sqls list, the row relation) so
   downstream asserts are ordinary value asserts.
3. **assertTestData/3 = a routed verdict form** in AssertVerdicts — the
   row contract compares IN THE DATABASE (verdict-in-DB doctrine),
   never a Java text compare.
4. **assertSqlEquals over `.sqls` joins the sql-text referee lane**
   (golden-SQL doctrine: engine H2 text is advisory/exec-verified like
   every other golden — the existing exec-passing machinery applies).
5. **Harness #46 arms DELETE** (tdgLetArm TDG branches, checkTdgAssert,
   TestDataGenForm routing); the 117 decline pin burns toward 0 with a
   named residue for anything adjudicated otherwise.

## Slices (each gated, ratchets moved with attribution)

- **S1 (smallest, proves the seam)**: `necessaryColumns` +
  CSV-census forms → K-natives + routed assertEquals. ~10 asserts.
- **S2 (the row contract)**: generate/seedDataString natives +
  assertTestData/3 + assertSize/2 routed verdicts. ~62 asserts.
- **S3 (text lane)**: assertSqlEquals/2 + H2Compatible → sql-text
  referee lanes (exec-verify the generated SQL where executable). ~46
  asserts.
- **S4 (deletion + pins)**: harness arms deleted; decline pin 117 → 0
  (or exact named residue); JavaEvalLedger unchanged (the generator was
  always production).

## Open questions for sign-off

1. assertSqlEquals rows land in the sql-text lane's OUTCOME buckets —
   acceptable to grow exec-passing/unable-to-exec pins there, with the
   same attribution discipline?
2. planTestDataGeneration (plan-text flavored bindings) — S3 or defer
   with the plan-text lane?
3. Milestoning-dates variants ride S2 or split if the temporal seam
   resists?
