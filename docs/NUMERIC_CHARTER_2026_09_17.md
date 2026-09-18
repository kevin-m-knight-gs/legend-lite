# The numeric charter (2026-09-17)

Three rules. Each is the engine's, cited to the pinned checkouts (legend-engine 4.145.0,
legend-pure 5.99.0). Lite follows them in SQL, because lite never interprets a value in Java.

## Rule 1 — inside a query, the database's own types

Literals render bare; the database types them. Arithmetic, aggregates and functions run in
whatever kind the database gives them. No read-time cast turns a column or a literal into a
double.

- Engine: `LiteralProcessor(format='%s')` for Integer, Float and Decimal alike
  (`extensionDefaults.pure` :133-135); `plus/minus/times` keep the kind
  (`platform/pure/grammar/functions/math/operation/{plus,minus,times}.pure`).

**The one exception — division.** Pure declares `divide(Number, Number):Float`
(`divide.pure` :44-50), so the operation's own semantics is a double division; lite casts
both operands to DOUBLE. The engine writes `((1.0 * a) / b)` (`extensionDefaults.pure` :218).
On DuckDB the two agree. On H2 the engine's spelling rounds inside NUMERIC arithmetic
(probed: 8 decimals for `(1.0 * 3653) / 365.0`); lite's cast keeps the double, which is what
the DuckDB-validated corpus expects. DECLARED DIVERGENCE from the engine on H2.

## Rule 2 — at the boundary, the DECLARED kind is assigned; the carrier keeps its digits

Every value that leaves a query carries the KIND the model declared for it — Float, Decimal,
Integer — whatever kind the database computed it in. The conversion is a kind assignment,
never a double cast: a Float-declared value that arrives as a DECIMAL keeps its digits and
IS a Float.

- Engine: the TDS decode transformer `dataTypeTransformer`
  (`execution_relational_execute.pure` :341-347): `Float` or `Number` →
  `$a->cast(@Number) * 1.0`, `Decimal` → `(… * 1.0)->toDecimal()`. The PCT adapter does the
  same by the function's declared return type (`pct_relational.pure` :178-181: `toFloat`,
  `toDecimal`, `round`). In the REFERENCE runtime (interpreted; PCT runs there) a Float is
  BigDecimal-backed (`FloatCoreInstance extends PrimitiveCoreInstance<BigDecimal>`), so
  `* 1.0` keeps every digit; the engine's own relational PCT adapters pass
  `abs::testBigFloatAbs` (`123456789123456789.99` exact) with no expected-failure entry.
  Cells are READ by JDBC kind with identity numeric transformers
  (`RelationalResult.getValue` :572-603, `SetImplTransformers.buildTransformer`).

**Homework 2026-09-17 (read, not inferred).** The engine's relational PCT suites for DuckDB
and H2 are built with `PureTestBuilderCompiled` (`Test_Relational_DuckDB_PCT.java`:
`PLATFORM = "compiled"`; `Test_Relational_H2_PCT.java`). In the compiled runtime a Pure
Float IS `java.lang.Double` (`JavaPurePrimitiveTypeMapping.java` :56-58; a Float literal is
generated as `(java.lang.Double)<digits>`, :106-108); a Decimal is `java.math.BigDecimal`
(:110-112). The engine's checked-in DuckDB and H2 PCT manifests
(`pct-manifests/relational-duckdb/EssentialFunctions_manifest.json`, `relational-h2/…`) do
NOT exclude `abs::testBigFloatAbs`: compiled, both sides of that assertion are doubles and
the row passes by losing the same digits on both sides. Lite's PCT lane is the INTERPRETED
builder (`PureTestBuilderInterpreted`), whose Float is BigDecimal-backed, so the same row
passes there only by keeping the digits. The two runtimes disagree on this row; the server
runs compiled.

**Rule 2a — a Number-DECLARED native result has ONE owner for its kind (2026-09-17).** A
declaration of `Number` gives the boundary nothing to key on (`rem(5.5, 2)` computes as a
DECIMAL under Rule 1). The reference decides the kind in one place: every Number-returning
native ends in `NumericUtilities.toPureNumberValueExpression(result, anyOperandIsDecimal)`
(legend-pure interpreted `Rem.java` :61-82, `Abs.java` :50-70, `Power.java` :54,
`NumericAccumulator` for the Number overloads of plus/minus/times) — the kind is the JOIN of
the operand kinds: any Decimal → Decimal, all Integer → Integer, otherwise Float. Lite
owns that rule ONCE, in the typer's refinement of the registered signature's output
(`Typer.refineNumberKind`, the same seam as the Decimal carrier and parseDate), and the root
envelope is its one consumer. No per-function lowering rule, no adapter conversion.

Rejected the same day: mimicking the engine's PCT adapter, whose result crosses a JSON
channel (`pct_relational.pure` :136-139) that turns every fractional number into a Float —
the engine's own DuckDB manifest therefore EXPECTS `rem::testRemWithDecimals` to fail
(`0.14D` vs `0.14`). That is the engine's relational store's limitation, not the spec.
Lite's DuckDB executes the Pure functions themselves, so the Pure test is the reference and
lite passes that row.

**Probed 2026-09-17 and rejected — under the INTERPRETED harness:** casting a Float-declared value to DOUBLE at the boundary.
It reproduces the compiled runtime, not the reference: the PCT lane lost
`abs::testBigFloatAbs` (`123456789123456780.0` for `…789.99`). Lite's existing design —
an exact-digit Float literal is DECIMAL-carried under the Float label (AssertVerdicts B8,
`CFloat.exact`) — is Rule 2 already.

**Where a conversion IS exact and the print form demands it:** a Float-declared cell the
database computed as an INTEGER (`if(...)` over integer columns on H2) spells `52.0`, not
`52` — the Float print form; an integer-to-double cast loses nothing (`Fold.cellText`).

## Rule 3 — equality is same kind, same value

Two primitive values are equal only when their KINDS are the same and their values are
equal. The kind is the DECLARED kind (Rule 2), not the carrier: a Float declared side
carried as BigDecimal and a Float side carried as double are the SAME kind, compared by
value.

- Engine: `EqualityUtilities.eq` (legend-pure interpreted runtime): primitive type names
  equal AND `getValue().equals(...)`. In the reference both Float sides are BigDecimal-backed,
  so "value equals" is a BigDecimal compare. Lite: `PureAsserts.equalScalar` says same-kind
  by CARRIER (BigDecimal × Double = false) — the carrier is not the kind; this is the one
  rule lite still gets wrong, and it is the corpus referee's, not the product's.

The service-test channel is the one place the engine compares WITHOUT any conversion:
`JsonNodeComparator.compareNumbers` compares by `decimalValue().compareTo` — scale-blind
value equality. Lite's `TestAssertions` already matches.

## What this retires in lite

| lite invention | replaced by |
| --- | --- |
| `CAST(x AS DOUBLE)` on every Float literal (6975118a6) | Rule 1 |
| `castAsDeclared` → DOUBLE at the read for a Float property over a DECIMAL column (fc2fe6bd3) | Rule 1 + Rule 2 |
| "a Float-declared column's WIRE is DOUBLE", pinned at zero divergence in the PCT and Channel B censuses (§4bZ-V C, 2026-08-26) | Rule 2: the wire may be DECIMAL under a Float declaration; the label carries the kind |
| `equalScalar`'s carrier-keyed kind (BigDecimal × Double = false) | Rule 3: kind by declaration, value by compare |

## What the census and the two probes established (2026-09-17)

- Rule 1 alone (bare literals, no read-time cast) turns 21 of the 22 stress float rows
  green on DuckDB and 12 more on H2, with zero new stress failures
  (docs/NUMERIC_ENVELOPE_CENSUS_2026_09_17.md §4, §7). The remaining two are DuckDB's own
  decimal division (DOUBLE, a backend fact).
- The same flip costs 31 corpus rows, 25–37 PCT wire-census columns and 26 Channel B rows
  ONLY because the judges key kind by carrier (Rule 3) or pin the wire type (Rule 2) —
  judge rules, not product truth.
- A DOUBLE cast at the boundary is wrong (this file, Rule 2).

## The order of work

1. This charter.
2. The judges adopt Rules 2 and 3 with the emission unchanged: the referee's kind is the
   declared kind and its value compare is numeric across carriers (`PureAsserts.equalScalar`
   with the sides' declared types, which `assertEquals` already receives); the PCT and
   Channel B wire censuses accept a DECIMAL wire under a Float label as DELIVERED, not
   divergent (`SqlTypeCensus.delivers`); `Fold.cellText` spells a Float cell in the Float
   print form. Judge: the full chain stays green at today's emission (today's wires are
   already doubles, so this step should be invisible to every lane).
3. The emission adopts Rule 1: bare literals, no read-time Decimal→Float cast; division
   unchanged. Judge: the chain plus both stress lanes; the rows named first (census §4).
4. One commit per step, its chain record in docs/GATES.md; CI watched.

## Landed 2026-09-17 (step 1 of docs/JUDGING_TWO_MODES_2026_09_17.md)

- Rule 1: Float literals render bare; `SqlExpr.FloatLit` states the wire fact (DECIMAL of
  the digits' precision) so the boundary sees what it converts.
- Rule 2: the root envelope (`Fold.declaredKindEnvelope`) — the one conversion, keyed by the
  declared type, at the outermost select; a wire the platform types DOUBLE needs none.
- Rule 2a: `NumberKinds.refine` in the typer — the ONE owner of a Number-declared native
  result's kind (the reference's `NumericUtilities` join).
- The dialect delivers the platform's facts: H2's average is a DECFLOAT whatever its input
  (probed: `AVG(CAST(1.0 AS DOUBLE) * age)` → DECFLOAT; `1.0E0` is DECFLOAT too, so an
  exponent spelling changes nothing); `H2AvgDelivers` casts it to DOUBLE on H2's own wire.
- Rule 3 stays the judge's (step 2).
