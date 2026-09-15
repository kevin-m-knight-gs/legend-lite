# The parked-work ledger

Work we decided NOT to do yet, recorded so it cannot be forgotten and cannot drift
silently. Every row is enforced by `core/src/test/java/com/legend/ParkedWorkLedgerTest.java`,
which runs in gate 1 of every chain: each row names an ANCHOR — a mechanical fact about
today's code that holds only while the item is still parked. Change the situation and the
anchor goes red, so whoever touches the area must close the row or restate it.

**The rules.**

1. A row leaves this ledger by being FIXED. Never by being loosened, never by being
   deleted because it is inconvenient.
2. Every row carries the date it was parked, WHO decided, WHY, the acceptance test that
   closes it, and the cost of leaving it parked.
3. The anchor must be mechanical. "We should remember to…" is not a row.
4. A green anchor is not approval. Each row is a debt with a stated price.

---

## PARK-1 — Cross-store associations: one shared predicate, not per-end

**Parked** 2026-09-15 by the user ("we can park xstore for now"), during the
mapping-normalizer audit burndown (FIXLIST P3-1).

**What we do today.** A cross-store (XStore) association's two ends must resolve to a
single shared predicate: both directions are canonicalized and compared, and a difference
is walled with *"has direction-specific conditions; a single shared predicate is required
for now"*.

**What the engine does.** The model is direction-SCOPED: `XStoreAssociationImplementation`
is an empty class and all semantics live per end in each `XStorePropertyMapping`'s
`crossExpression` (`mapping.pure:174-180`). None of the engine's eight XStore validations
compares end A to end B.

**Cost while parked.** Four engine fixtures sit in our own corpus manifest, parse and
round-trip, and cannot be normalized:

| fixture | shape |
|---|---|
| `testModelJoinsToRelationalJoins.pure:399-400` | four ordering comparisons, operands flipped per side |
| `relationMappingSetup.pure:638-639` | genuinely asymmetric bodies, not inverses |
| `testMappingCrossStore.pure:239-242` | four property mappings over two set pairs; we read set ids from the first only |
| `executionPlanTestSnowflake.pure:493-499` | a one-ended XStore, which the engine accepts |

The rule is also implemented TWICE verbatim (the column-space and property-space paths),
and neither copy has a test.

**Acceptance (what closes this row).** Per-end predicates carried to the resolver, set ids
read per property mapping rather than from the first, ONE implementation, and the four
fixtures above normalizing with rows matching the engine.

**Anchor.** The wall text `has direction-specific conditions` appears in exactly two
product files: `MappingNormalizer.java` and `XStorePureEnds.java`. Implementing per-end
predicates removes or moves it; unifying the duplicate changes the count.

---

## PARK-2 — A union read twice is built twice (no common-subexpression pass)

**Parked** 2026-09-15 during the same burndown (FIXLIST P4-1).

**What happens today.** A union-mapped class is one whose rows come from several tables
stacked together. When one query both filters on a related collection and aggregates over
it, we emit the stacked union TWICE — the filter builds its own deduplicated copy instead
of reusing the grouped relation that is already there — so the database scans the
underlying tables twice for one question. (Two aggregates over one union already share a
single copy; the duplication is specific to the filter.)

**Measured** by the audit on 2,000 firms against 200,000 people (NOT re-run since):

| | engine-shaped | ours |
|---|---|---|
| table scans | 2 | 4 |
| hash joins | 1 | 2 |
| best of 7 runs | 1.12 ms | 2.26 ms |

Rows are CORRECT. This is a leanness and speed defect, not a correctness one. The same
emission also carries a dead always-true condition and a left join plus a not-null test
that together are an inner join written the long way.

**Why it survives.** Nothing in the normal lowering path looks for a repeated subtree.
`SubselectPrune` is the only post-lowering pass and it prunes columns without ever
collapsing a wrapper; the CTE builder exists but only an opt-in parity post-processor
(`extractSubqueriesAsCtes`, behind the `extractCtes` flag) ever calls it.

**Acceptance (what closes this row).** A pass in the normal path that names a repeated
subtree once and points both readers at the name, with the measurement above closing.

**Anchor.** `extractSubqueriesAsCtes` is called from exactly one product file
(`SqlPostProcessors.java`, the opt-in path) and `new SqlWith(` is constructed in exactly
one (`SqlRewriter.java`, its structural copy-on-write). A real common-subexpression pass
moves at least one of those.
