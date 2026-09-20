# The wire type on the output slot — homework before the leg (2026-09-19)

**Question (USER, after the two 3.3 rows resisted three partial fixes):** the host judge stays as
an optional judge; what is the *correct* way for a verdict to know a column's WIRE kind, so that
no consumer has to look behind a plan's outputs?

## 1. What the IR already rules (read, not re-decided)

`docs/TYPED_SQL_IR.md` "THE LABEL FLIP — EXECUTED (2026-08-24; mismatch = 0, pinned)" and the
charter's §4bZ carry-through relation (`SqlTyping.carryThrough`, `tolerateRead`):

- An `OutputCol` label is the **pure-contract erasure** (the declared kind's SQL mapping); the
  projection expression's stored type fact is the **wire**.
- `SqlSelect`'s canonical constructor reconciles each slot (`SqlTyping.reconcileSlot`): equal or
  subsumed keeps the contract; an untagged difference is a label lie and the label ADOPTS the wire;
  a read **tagged at the mapping seam** across a registered pair (`VARCHAR`/`DOUBLE` label over an
  integer wire — the engine's raw carry-through at a declared property/column mismatch) keeps the
  contract label and marks the slot `tolerated`; the tag propagates through stamped re-reads. The
  wire census (`SqlTypeCensus`) reads the same relation; corpus mismatch is pinned at 0.
- Union slots reconcile the same way (`SqlTyping` ~238–262).

So `addressId : String` over `addressTable.ID INT` is not a lie and not an accident: its slot is
`VARCHAR, tolerated = true` by design, and the integer wire lives only on the projection's fact.
My earlier framing ("the slot is lying") was wrong about the design; the design is fine — it just
never put the wire TYPE on the slot, only the fact that it differs.

## 2. Census

**Writers of the contract label** (the declared Pure kind → `sqlTypeOf(c.type())` into the slot):
`Lowerer` 1854 (`Fold.slot` at the paired projection door), 2413 (mixed carrier slot), 2712 / 2777
(class-value slots), 3351, 3490 (`outputsOf`, the store boundary — PHYSICAL origin);
`LayoutTypes:73`; `CollectionRelations:126`. Every one is a declaration erasure; none knows the
physical type, and none should.

**Where label and wire meet** (one owner): `SqlSelect` ctor → `SqlTyping.reconcileSlot(expr, out,
grouped)` (projection frames), the union reconcile, `expandStar` (verbatim copy). The projection's
type fact is in hand exactly here.

**Readers that mean the CONTRACT** (must not change): `Executor.fetch/unwrap` keyed by
`sqlTypeOf(plan, i)` — the slot is the CARRIER decode instruction (LITERAL / TEMPORAL_TEXT /
DECIMAL_TEXT / JSON labels; a late-bound grid passes null and the JDBC kind decides);
`LiteralSpelling.wireValueEgress(expr, declared, lane)` keyed by the declared label (the engine
transformer's key), `Fold.conformValueEgress` (copies the slot with the conformed target);
the host judge's tabular decode takes declared kinds from the SCHEMA (`Executor` 788–822), not
the slot. Host mode is therefore untouched by adding a wire fact.

**Readers that mean the WIRE and compensate today:**
- `CanonicalRenderSql.wrapTdsCanon` ~470: reads the projection's type fact when the projections
  align with the outputs — correct while it has the unwrapped plan; its OWN outer frame then
  re-references every column as `Column.of(null, col)` (label-typed), so the wrapped plan's
  outputs no longer carry the wire (this is what the rendered road hit: `__cell`/`__rowcanon`
  appended, and `12` typed by the VARCHAR label again).
- `wrapWithCanon`'s unrefined-Number and Any arms: `Type.kindOfSqlType(valueCol.type())` — the
  LABEL, called "wire" in the comments; right only when label = wire.
- `VerdictQueries.wireSchema(outputs)`: the label.
- `WireTypes.reconcile`: the projection's fact (bare column references only).
- `SqlTypeCensus`: recomputes the wire through the typing judgment.

## 3. The decision (USER, after the census): the slot IS the wire; the label-as-contract and
## the tolerated tag are deleted

"Stamp both" was considered and rejected: it would carry a Pure fact in the SQL layer beside a
second slot, perpetuating the compromise the standalone-SQL vision forbids. The ruling:

- **Model reasoning goes by the STORE.** Typing, lowering and the SQL we emit come from the
  declaration, as the engine's own SQL generation does. A fixture that contradicts the store is
  never adopted into the model or the product plan.
- **Touching real values goes by the WIRE.** The output slot holds what the expression computes
  (the compiler's wire belief). The executor decodes what the driver returns. The judge, which
  WRITES SQL about values (the canon), reads the database's reported type for the verdict side
  only (`WireTypes.reconcile`, on the verdict copy of the plan — never written back).
- **Disagreement is COUNTED, loudly.** Declared (schema) vs wire (slot): the model-mismatch census
  (a String property over a store-declared INT column is legitimate and stays there). Slot vs JDBC
  metadata: the fixture-skew census ("your test database does not match your store").

**The four conditions that make the tag deletable for sure (census of every reader):**
1. `SqlTyping.reconcileSlot` and the union reconcile adopt the computed type unconditionally; the
   carry-through arm and the two tag doors (`Scalars` typeAsDeclared rule, `Lowerer` unwrap site;
   `tolerateRead`) go. Nullability keeps the M-N3 flip.
2. The `SUM` typing rule types the integer family `HUGEINT` plainly (its tagged arm existed only
   so a declared DOUBLE label could stand over an `orderTable INT` fixture made FLOAT —
   `testReprocessGroupByAlias`, the witness row).
3. The executor's integral-exactness guard tests the VALUE's scale (a scale-0 BigDecimal under an
   integral slot decodes exact; a non-integral cell decodes as what it is) instead of asserting the
   stamp with `toBigIntegerExact` — the tag was silently standing in for this on H2 DECFLOAT sums.
4. The census reclassifies: declared-vs-wire (schema against slot, `carryThrough` as the
   classifier) and slot-vs-metadata; the `tolerated-*` counters go. Nothing pins them (checked:
   no test, gate or register reads them; `Runner.FIXTURE_SKEW` does not exist).

**Then, mechanically:** `TypeFact.Typed.tolerated`, `OutputCol.tolerated`, `tolerateRead`,
`carryThrough`'s reconcile use and ~20 copy sites are deleted; the declared-kind egress
conversions (`conformValueEgress`) take the declared kinds EXPLICITLY from their three callers
(root `ExprType` in `Lowerer`, the `RelationType` in `Fold`'s map channel, the schema in the grid
wrap) instead of reading them back off the slot; the wire consumers read the slot
(`wrapTdsCanon`'s projection walk goes; `wrapWithCanon`'s Number/Any arms, `wireSchema`,
`WireTypes.reconcile` compare slot to reported type directly). Task #23 lands on top: the raw
`executeInDb` grid framed by the database's reported columns (`WireTypes.staticized`, in the
stash), the rendered road slicing the data columns by the rider's recorded width, and
`wireDecidedKinds` typing a String / Number / Any cell by the slot.

## 4. Measurement plan (the leg is done when all hold)

1. Unit: `SqlTypingTest` (the label-flip battery), `WireTypesTest`, `VerdictQueriesRenderedTest`,
   `SqlCanonConformanceTest`; the new pin `wire == computed` (mismatch stays 0).
2. Four lanes exact: DuckDB host 108, H2 host 412, DuckDB database lost 2 → 0 / gained 0 /
   accepted 21 / 2, H2 database 75 → 74 or 75 (`validateComplexValidation3` if it is on the H2
   roster; `testExecuteInDbToTDS` stays — it walls at lowering on H2, variant navigation) / 71.
3. PCT relation 469/0 (DuckDB) and the H2Modern floor; stress 4,700 / 20; chain green.
4. Ledger pins re-pinned with reasons where plan wiring moved (`AssertVerdicts`,
   `StatementExecutor`); the `withOutputs` door 2 → 3 (the raw-grid frame: externally discovered
   outputs, the pivot probe's class); the deletions (tag fields, doors, copy sites) are the proof.
5. The witness rows: `testReprocessGroupByAlias` (condition 2/3) stays green on both engines.

## 5. Risks named

- A copy site that rebuilds an `OutputCol` from fields and forgets `wire` silently reverts that
  column to `wire = type`; the `wire == computed` pin catches it on any executed plan.
- `wrapWithCanon`'s Number arm switching from label to wire changes the canon kind for any
  unrefined-Number side over a DECIMAL wire whose label said DOUBLE — the H2 calendar family is
  Float-declared (untouched), but the lanes decide, not the reasoning.

## 6. Executed (2026-09-19) — receipts

All four conditions built as written in §3; deletions: `TypeFact.Typed.tolerated`,
`OutputCol.tolerated` (+ the 4-arg constructor), `SqlTyping.tolerateRead`, `SqlTyping.carryThrough`,
the SUM rule's tagged arm, the census's three `tolerated-*` counters and `wire-tolerated` bucket,
~20 copy sites, `wrapTdsCanon`'s projection walk. Measured per §4: four lanes exact (DuckDB
database lost 0 / gained 0; H2 74 / 71), PCT 469/0 + H2Modern floor, stress held, label census
mismatch 0, chain green; witness `testReprocessGroupByAlias` green on both engines. Record:
docs/DATABASE_MODE_HOMEWORK_2026_09_18.md §4w. Not yet done: printing the declared-vs-wire and
slot-vs-metadata counts in the lane summary (condition 4's reporting half).
