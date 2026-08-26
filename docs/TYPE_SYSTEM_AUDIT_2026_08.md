# Type-system audit — 2026-08

**Full evidence base: [`type-audit-2026-08/`](type-audit-2026-08/) — start at its
[README](type-audit-2026-08/README.md).**

An adversarial audit of the type system from Pure source text through every pipeline stage to
decoded results: lexer → parser → name resolution → normalize/mapping → element compile (F) →
spec compile (G) → user-call inlining (G½) → store resolution (H) → lowering (I) → dialect
render (J) → execute (K) → wire → values decoded back into Pure-typed results.

## Finding

**The type system computes types with real rigour and then does not enforce them.**

The computation verifies out, exhaustively rather than by sample: the bounded multiplicity lattice
is correct over all 400 pairs and 8,000 triples; `PrecisionDecimal`'s arithmetic matches a
from-scratch Spark `DecimalPrecision` reference over all 608,400 ordered (p,s)×(p,s) pairs;
`isSubtype` is reflexive, transitive and antisymmetric over all 42,875 triples of 35 types; the
typed HIR is exactly sealed (70 permits / 70 files / 70 reflected subclasses) with **no variant
computing its own type**; and `com.legend.sql` is genuinely dependency-free — the only non-`java.*`
reference across all 18 files is a class-retention `@Nullable` marker, confirmed by reflection and
an independent javap constant-pool scan.

The enforcement is absent. The relational mapping performs no property-type/column-type check; a
generic function's declared return is never checked against its body; `cast` converts rather than
asserts; `->toOne()` is deleted at phase H; `PrecisionDecimal`'s precision algebra has zero
production callers; and no egress checks a value against its column's declared multiplicity.

**105 distinct defects** (S1 41, S2 27, S3 19, S4 12, S5 5), deduplicated ~5.9× from ~620 raw
findings, reduced to **14 root causes**.

## Four numbers

| | |
|---|---|
| **10.1%** | 1,010 of 10,030 executed fuzzed queries returned a value violating its own declared type. The 12-check oracle was mutation-tested — every check provably fires. |
| **117 / 117** | executable `[1]` cells in an exhaustive 874-cell mapping matrix delivered `null` under a lower bound of 1 — **including the matched diagonal**, where property and column types agree exactly. |
| **25.4%** | 183 of 721 native signatures diverge from real FINOS Legend, measured against 24,172 declarations extracted from `finos/legend-pure` @18cd1bb and `finos/legend-engine`. |
| **1 / 1,671** | end-to-end test methods relate a declared column type to the delivered Java carrier. The suite is green at 4,278 tests and blind to the seam where 15 of 19 checked findings sit. |

## The sharpest case

No type mismatch, no exotic input — an `Integer[1]` property mapped to an `INTEGER` column that is
merely nullable in the DDL:

```
[G]        Relation<(n:String[1], q:Integer[1])>
[PLAN]     SELECT t0.NAME AS n, t0.QTY AS q     <- no guard, no coalesce, no error
[EXEC-ROW] String(bob) | null                   <- null under Integer[1]
```

The only thing that makes a `[1]` property non-null is declaring the column `NOT NULL`, which the
mapping layer neither requires nor checks. `[1]` is an annotation, not a guarantee.

## Method

No code comment, javadoc, `docs/` file, `AGENTS.md` claim or commit message was treated as
evidence — only source read in full, or code actually run. Every finding carries a `file.java:LINE`
citation with quoted code, or a reproduction with pasted output.

Two independent falsifiers re-derived every critical claim from their own fixtures: **202 rulings —
185 CONFIRMED, 7 OVERSTATED, 2 NOT-REPRODUCED, 1 MISATTRIBUTED, 0 BY-DESIGN.** Nothing here is
intended behaviour that went undocumented. They also corrected the audit's own author four times;
those corrections are recorded in `CONFIRMED.md` rather than quietly applied.

## Status of the claims

- **39 findings** were reproduced independently by the audit author, with pasted output
  (`CONFIRMED.md`). Highest confidence.
- The remaining defects carry an auditor's citation or repro and a falsifier ruling where one
  applies (`MASTER.md` §1, falsifier verdicts in §6a).
- **One high-severity claim is explicitly UNCONFIRMED**: that open-ended milestoned rows
  (`thru IS NULL`) are invisible at every date including `%latest`. Reported with pasted output by
  one auditor, not independently reproduced. If real it outranks most of the table — check it first.
- Coverage is honest about its gaps: DuckDB dominates the evidence; SQLite and H2 were used for
  targeted cross-backend checks, not the full matrices; DB2 and the engine-style dialects have no
  driver available and were read only; M2M, cross-store joins and embedded/union mappings were
  reached only partially; the `nlq`, `pct` and `parser-equivalence` modules were not audited.

## Remediation

[`type-audit-2026-08/REMEDIATION.md`](type-audit-2026-08/REMEDIATION.md) — 16 fix specs, each
quoting the current lines, grepping the blast radius and naming a regression assertion; plus the
guard tests to **extend** rather than add (`ArchitectureTest`, `SqlTextRatchetTest`,
`PctDisciplineTest` all take the new rules), and an explicit don't-fix list with reasons.

Kept as a separate document with its own lifecycle, per this directory's standing convention
(`AUDITS.md`: *"separate docs are what got the first two executed"*).

The single structural recommendation is an **egress conformance check** at `ExecutionResult`
construction, asserting every returned cell against its declared column type and multiplicity.
`Executor` already hosts two checks of exactly this kind (`:769`, `:793`); this generalises them.
It catches essentially all of the unsound fuzz outcomes. It catches **none** of the wrong-but-
well-typed class — the let-inliner capture, `match`'s static dispatch, `cast`'s rounding — which
need their own fixes.

## Re-running the evidence

[`type-audit-2026-08/harness/`](type-audit-2026-08/harness/) carries the probe used throughout:
one command prints a query's Phase-G root type, the typed-HIR tree with per-node types, the
rendered SQL, and the execution result with each column's Pure type and each cell's Java runtime
class. See its `HOWTO.md`.
