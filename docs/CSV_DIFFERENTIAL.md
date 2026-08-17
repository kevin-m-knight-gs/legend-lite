# CSV Differential — F4.2b probe (2026-08-17)

Probe: env-gated (`LL_CSV_PROBE`) compare in `EngineTestExecutor`'s toCSV
strip arm — side A = the harness's Java render of the stripped grid
(`csvText`), side B = the UNSTRIPPED expression through the platform (the
registered toCSV lowering: the DATABASE's text). One full DuckDB corpus
sweep, referee green, zero-delta. Raw rows: `core/target/csv-differential.tsv`
(regenerate with the env var; the probe deletes with F4.3).

## Totals

| verdict | count |
|---|---:|
| EXACT (byte-equal) | **105** |
| DIFFERS | 20 |
| MULTISET / B_ERROR / B_NULL / B_NOT_SCALAR | 0 |

**Scope**: the plain `->toCSV()` tail arm (125 assertions). The
`toCSV()->replace('\n', sep)` calendar spelling (CSVJOIN channel) was not
probed — it composes the same lowering and joins F4.3's cutover with its own
comparison policy.

Unknowns settled (plan §F4.2b):
1. **F4.3's true red is ~20 assertions in 4 mechanisms**, not "up to 165".
2. **`Scalars.floatRepr`'s first genuine workout found ZERO float
   divergences** — every EXACT row's numeric cells agree byte-for-byte.
3. **Row-order leniency is load-bearing for ZERO probed assertions**
   (no MULTISET verdicts — exact and multiset comparison coincide here).

## The four mechanisms (20 rows)

1. **`ID` header skew — 14 rows, all `meta::relational::validation::*`**:
   side A carries a trailing `ID` column side B lacks
   (`CONSTRAINT_ID,ENFORCEMENT_LEVEL,MESSAGE,ID` vs `...,MESSAGE`). The
   harness's validation channel and the platform's projection disagree
   about the ID column — adjudicate WHO owns the extra column before the
   cutover (smells like a harness-side channel addition; Phase-6
   territory).
2. **Abstract-Date cell spelling — 4 rows (2 tests × 2 asserts)**,
   `meta::pure::tds::tests::extensions::columnValueDifference*`:
   A `2014-12-01` vs B `2014-12-01 00:00:00`. The column types abstract
   `Date` and rides TIMESTAMP (F5.4), so the DB spells the datetime form;
   the harness spells the VALUE's date-only precision. Adjudicate against
   the engine's own rendering of these tests (value-precision vs
   slot-kind) — may be a typer-inference improvement (the column could
   type StrictDate).
3. **Collection-valued cell — 1 row**
   (`projection::function::concatenate::testConcatenateWithFilter`):
   A `Firm X,Firm X` vs B `Firm X,[Firm X]` — a to-many cell prints
   pure's list form `[..]` on the DB side, bare on the harness side.
   One cell-rule divergence to adjudicate against engine `toCSVString`
   semantics for collection cells.
4. **Trailing-line edge — 1 row**
   (`mapping::embedded::testIsEmpty`): B carries the engine-spec trailing
   shape (`joinStrings('', '\n', '\n')`), A drops a line. The SPEC sides
   with B; the harness render is the deviation.

## F4.3 marching orders

Adjudicate mechanisms 1–3 (engine-source-grounded), fix producers or
declare expected-red per §0.4; mechanism 4 is already engine-true on the
DB side. Then delete `csvText`/`csvCell`/`csvEquals`' RENDERING half and
the strip, keeping the comparison POLICY (order/ULP) per the plan's
keep/drop table — and delete this probe.

## Adjudication results (2026-08-17, F4.3a)

- **Mechanism 1 FIXED**: `DriverPkAppend` is now TRANSPARENT to the toCSV
  render tail (the engine applies `addDriverTablePkForProject` to the
  projections regardless of the text form; the harness strip used to remove
  the tail first). All 18 validation-family probe rows EXACT.
- **Mechanism 2 FIXED**: an abstract-`Date` cell renders by the engine's own
  `formatDateTime` rule — a DATE slot is a StrictDate read (slot-kind, typed);
  over a TIMESTAMP slot the rule is DEFINED OVER THE VALUE (`hasHour`),
  spelled in SQL (`HH:mm:ss = 00:00:00` → date form). The one residue (a TRUE
  midnight DateTime under an abstract slot prints date-only) is the F5.4
  slot-erasure, identical on the engine's own read-back. 4 rows EXACT.
- **Mechanism 4 ADJUDICATED, no change**: the engine golden for
  `embedded::testIsEmpty` is `'name,firm\n\n'` — byte-identical to the
  platform's empty shape. The harness render is the deviation; the test is
  already baseline-red.
- **Mechanism 3 RESIDUE (1 row, named)**:
  `testConcatenateWithFilter` — the platform spells the cell `[Firm X]`
  where the engine golden says `Firm X`. Root cause is NOT the render: the
  TDS project column contract is to-one while the lowering emits a
  list-valued slot (OutputCol says VARCHAR, the wire carries an array) that
  the Executor unwraps at egress — a pre-existing type-contract deviation
  the render cannot see statically. (A count-based render was tried and
  DELETED: zero live firings, and its LIST_GET/LIST_LENGTH emissions
  violate the carrier-purity tenet — a to-many cell demand gets a
  SEMANTIC node with dialect strategies when one appears.) The fix is
  the OutputCol/slot reconciliation — filed in FOUNDATIONS_PLAN §9. F4.3's cutover carries it as a declared
  §0.4 expected-red of AT MOST ONE test.

**Post-adjudication probe: 123 EXACT / 2 DIFFERS** (the named residue + the
already-red testIsEmpty) across the full corpus.
