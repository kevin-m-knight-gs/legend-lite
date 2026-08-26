# Adversarial audit — the legend-lite type system, end to end

**Scope.** Pure source text → lexer → parser → name resolution → normalization/mapping →
element compile (F) → spec compile (G) → user-call inlining (G½) → store resolution (H) →
lowering to typed SQL MIR (I) → dialect render (J) → JDBC execute (K) → wire → values decoded
back into Pure-typed results.

**Method.** No code comment, javadoc, `docs/` file, `AGENTS.md` claim or commit message was
treated as evidence — only source read in full, or code actually run. Roughly 30 specialist
auditors, a property fuzzer, a repo-wide census, a signature-conformance oracle built from the
real upstream repositories, two independent falsifiers, and a synthesis pass. Every finding in
`CONFIRMED.md` was additionally re-derived and re-run by the orchestrator from an independent
fixture, with output pasted.

**Published report:** the narrative version of this audit is an Artifact; this directory is the
evidence base.

## Files

| File | What it is |
|---|---|
| `CONFIRMED.md` | 39 findings personally reproduced by the orchestrator, with pasted output, plus a CORRECTIONS section recording every claim withdrawn or amended — including the orchestrator's own. |
| `MASTER.md` | The synthesized defect table: 90 distinct defects (S1 35, S2 24, S3 15, S4 11, S5 5), deduplicated ~4.8× from ~430 raw findings across 27 reports, with root causes and a per-stage map. Written before the falsifiers and the last two matrices landed; its §6 says so. |
| `V1-falsifier.md`, `V2-falsifier.md` | Independent adjudication. 202 rulings between them: 185 CONFIRMED, 7 OVERSTATED, 2 NOT-REPRODUCED, 1 MISATTRIBUTED, 0 BY-DESIGN. |

## Headline

The type system computes types with real rigour and then does not enforce them.

The computation is good — exhaustively verified: the multiplicity lattice is correct over all
8,000 triples; `PrecisionDecimal`'s arithmetic matches a Spark reference over all 608,400 (p,s)
pairs; `isSubtype` is a true partial order over all 42,875 triples; the typed HIR is exactly
sealed at 70/70 with no variant computing its own type; the MIR is genuinely dependency-free.

The enforcement is not. The relational mapping performs no property-type/column-type check at
all; a generic function's declared return is never checked against its body; `cast` converts
rather than asserts; `->toOne()` is deleted at phase H; `PrecisionDecimal`'s precision algebra
has zero production callers; and no egress checks a value against its column's declared
multiplicity. A seeded fuzzer found **1,010 of 10,030 executed queries (10.1%)** returning a
value that violates its own declared type.

The sharpest single case needs no type mismatch at all: an `Integer[1]` property mapped to a
nullable `INTEGER` column delivers `null` under `Integer[1]`. An exhaustive 874-cell mapping
matrix found this in **117 of 117 executable `[1]` cells, including the matched diagonal**.

## Why the suite doesn't catch it

The suite is green — 4,278 tests, 0 failures, 484 classes. Of 19 findings put to a coverage
check: **0 covered, 3 pinned, 15 uncovered**. Of 1,671 test methods that execute a query end to
end, 888 (53.1%) assert rows/values, **4 (0.24%)** assert a column's `pureType()`/`multiplicity()`,
and exactly **1 (0.06%)** relates a declared column type to the delivered Java carrier.
Compile-time typing is densely tested; row values are densely tested; the seam between them is
unguarded, and that is where 15 of the 19 findings sit.

## The one external ground truth

`NativeFunctionTest.catalogMatchesTheGoldenFile` renders `Pure.all()` and compares it to a file
generated from `Pure.all()`. It verifies nothing about real Legend. Diffing all 721 signatures
against 24,172 declarations extracted from `finos/legend-pure` @18cd1bb and `finos/legend-engine`:
**538 exact, 131 argument differences, 16 return-type/multiplicity differences, 36 functions that
do not exist upstream — 183/721 (25.4%) divergent.**

## Recommended first change

An egress-time conformance check at `ExecutionResult` construction: assert every returned cell
against its declared column type and multiplicity. By the fuzz taxonomy this catches the 960
`NULL_IN_ONE` and 48 `JAVA_CLASS` results — ~99.8% of the 1,010 unsound outcomes. It does **not**
catch well-typed but wrong values (the capture bug, `match`'s static dispatch, `cast`'s rounding);
those need their own fixes.
