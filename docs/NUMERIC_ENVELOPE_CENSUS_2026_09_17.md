# Numeric envelope — the census before the design (2026-09-17)

**Question (user):** lite's tenet is that Java never interprets values — the database
computes and formats. The 22 last-digit float rows in the stress corpus come from lite
deciding "Float means DOUBLE" at the READ. Is moving the conversion to the envelope
homework or guesswork? This file is the homework: the four sites and where they came from,
one census run with the read-time decisions flipped, and what the engine itself spells.

Nothing in this file is committed product behaviour. The flips were made in the working
tree, measured, and reverted (`git checkout`).

## 1. The four read-time decisions, and why each exists

| site | what it does | added by | why (the commit / comment) |
| --- | --- | --- | --- |
| `AnsiSqlRenderer` FloatLit | `CAST(100.0 AS DOUBLE)` | 6975118a6 2026-07-11 (PCT slice 7) | "a BARE decimal literal types as DECIMAL(p,s) in DuckDB and infects every aggregate over it" — PCT results printed as 'D' Decimals |
| `AnsiSqlRenderer` DIVIDE | `(CAST(a AS DOUBLE) / CAST(b AS DOUBLE))` | F-L 2026-09-16 | H2 divides NUMERICs at its own scale rule (36-digit results in one shape, 7-digit in another); pure's `divide(Number, Number)` IS a Float |
| `DeclaredCoercions` Decimal→Float | `castAsDeclared` = CAST AS DOUBLE at the read | fc2fe6bd3 2026-08-26 | a Float property over a DECIMAL column delivers a double on the wire |
| `Lowerer.aggExpr` QUANTILE_DISC | CAST AS DOUBLE | 1da104d99 2026-09-11 | engine golden prints `12.0` for a discrete percentile over integers (a print-form fact; not part of this census) |

The one legitimate conversion point already exists: `Render` (~658) casts a Number/Float
cell on a DECIMAL or DOUBLE slot to DOUBLE and prints it — in SQL. The PCT lane's wire
text goes through it (`Render.pctTds`). The stress runner does not print at all: it
compares JDBC cells BY VALUE (`TestAssertions`, BigDecimal `compareTo`, scale-insensitive).
The corpus verdicts judge a decimal-carried Float through the float canon (AssertVerdicts
B8). H2's `normalize` turns a BigDecimal into a double only on DOUBLE slots.

## 2. What the engine spells (legend-engine 4.145.0, extensionDefaults.pure)

| | engine |
| --- | --- |
| Float literal | bare — `LiteralProcessor(format='%s')` for Integer, Float and Decimal alike (line 134) |
| divide | `((1.0 * %s) / %s)` for EVERY dialect (line 218) — the `1.0 *` promotion, no DOUBLE cast |
| the conversion to double | its Java result transformer, once, per the Pure column type |

The stress corpus's expectations were validated against the engine on **DuckDB** (every
runtime is DuckDB). Nobody has validated them against the engine on H2.

### 2b. The engine's CODIFIED numeric rules, end to end (read 2026-09-17, pinned checkouts)

| layer | rule | where |
| --- | --- | --- |
| Pure typing | `divide(Number, Number):Float` — division IS a Float operation by signature; `plus/minus/times` keep the kind (`Float[*]→Float`, `Decimal[*]→Decimal`, `Number[*]→Number`); `divide(Decimal, Decimal, scale):Decimal` is the exact-arithmetic form | legend-pure `platform/pure/grammar/functions/math/operation/{divide,plus,minus,times}.pure` |
| SQL emission | literals bare for Integer, Float AND Decimal (`LiteralProcessor(format='%s')`); `divide` → `((1.0 * %s) / %s)` on every dialect; no read-time casts anywhere | `extensionDefaults.pure` :133-135, :218 |
| execution (Java) | a cell is read BY ITS JDBC TYPE: TINYINT..BIGINT → `Long`, REAL/FLOAT/DOUBLE → `Double`, DECIMAL/NUMERIC → `BigDecimal`; the per-column transformers convert ONLY Boolean and the date family — every numeric column is `Functions.identity()`. The engine never converts a decimal to a double by the Pure type | `RelationalResult.getValue` :572-603, `ResultColumn` :98-126, `SetImplTransformers.buildTransformer` |
| JSON serialization | the JDBC object is written by a stock Jackson `ObjectMapper` (only PureDate and Pair have custom serializers): a `BigDecimal` prints plain with its scale (`55.00000`), a `Double` prints Java's shortest form | `ExecutionResultObjectMapperFactory`, `JSONTDSSerializer` :144 |
| service-test verdict | `EqualToJson` parses both sides with `USE_BIG_DECIMAL_FOR_FLOATS` + exact BigDecimals and compares numbers by `decimalValue().compareTo` — SCALE-INSENSITIVE, value equality (`55.00000` == `55.0`; `55.00000000000001` ≠ `55.0`) | `TestAssertionHelper` :35-46, `JsonNodeComparator.compareNumbers` :153-157 |
| PCT verdict | the adapter converts the executed value BY THE FUNCTION'S DECLARED RETURN TYPE: Float → `cast(@Number)->toFloat()`, Decimal → `toDecimal()`, Integer → `round()`, Number → as is | `pct_relational.pure` :178-181 |

So the engine's design is: **the database types everything (bare literals, its own
arithmetic), the wire carries the JDBC kind untouched, and the ONE conversion to the
declared Pure kind happens at the verdict boundary, keyed by the STATIC type** (the PCT
adapter's `toFloat` for a Float-returning function) — or does not happen at all where the
verdict is value equality (service tests). Lite's `TestAssertions` already matches the
service-test rule (BigDecimal `compareTo`). Lite's `Render` envelope cast (`CAST(c AS
DOUBLE)` on a Float-declared cell) is the SQL spelling of the PCT adapter's `toFloat` —
legitimate because it is keyed by the declared type, never by the value.

What is NOT the engine's rule: `PctCensusGate`'s wire policy ("Float-declared TDS cells
seed DOUBLE literals; DOUBLE<>DECIMAL(p,s) is a divergence pinned at zero", §4bZ-V C
2026-08-26) — that is lite's own invention that made the wire kind match the Pure kind
up front, and it is exactly what bare literals violate (the 25–37 columns in §4).

## 3. The probe: division and literal typing on both backends

`SELECT` on H2 2.4.240 and DuckDB 1.4.4 (the shapes the corpus reaches: `tenorDays / 365.0`,
`DECIMAL(6,4) * 100.0`):

| expression | H2 | DuckDB |
| --- | --- | --- |
| `3653 / 365.0` | `10.0082192` (7 decimals) | `10.008219178082191` DOUBLE |
| `((1.0 * 3653) / 365.0)` — the ENGINE's spelling | `10.00821918` (8 decimals) | `10.008219178082191` DOUBLE |
| `CAST(3653 AS DOUBLE) / 365.0` — lite's F-L spelling | `10.0082191780821918` → double `10.008219178082191` | same |
| `3653.0 / 365` | `10.008219178082191780822` (21 decimals) | DOUBLE |
| `CAST(0.1234 AS DECIMAL(18,4)) * 100.0` | `12.34000` exact | `12.34000` DECIMAL(18,5) |

So: H2's NUMERIC division scale depends on the operands' scales (the divisor's scale
SHRINKS the result); DuckDB's decimal division is DOUBLE regardless. A bare-literal
multiply is exact on both. The engine's own spelling on H2 would print `10.00821918` for
`tenorYears`, which the corpus (DuckDB-validated) rejects — the F-L cast is the spelling
that makes H2 agree with the DuckDB-validated expectations, and pure's `divide` returns a
Float by signature, so casting the OPERANDS is the semantics of the operation, not a
Java interpretation of a value. **Keep F-L.**

## 4. The census: three flips at once, every lane

Flips: (1) FloatLit bare; (2) DIVIDE = the database's own division (INTEGER/INTEGER lane
casts its operands so 7 / 2 = 3.5 on H2); (3) Decimal→Float declared coercion = type
assertion, no read-time cast.

| lane | before | with the flips | rows |
| --- | --- | --- | --- |
| stress DuckDB shared | 41 fail | **20 fail** — 21 green, ZERO new | the 22 float rows minus `headroomToLimit` ×2 (`25.0 - g / cap * 100.0`: DuckDB decimal division is DOUBLE — a backend fact) |
| stress H2 fresh | 113 fail (4,607) | **155 fail (4,565)** — 50 new | `tenorYears` ×40, `sizeKb` ×10: H2 NUMERIC division scale (flip 2 removed the F-L cast) |
| G4 DuckDB corpus (108 EXACT) | 0 lost | **LOST 20** | zScoreTest; calendarAggregations testPwaValue/testUnionWithWtdAndPwa; map::testSubAggregation ×4 + projection::aggregation::testSubAggregation ×2; dataType::testSimpleTypeMapping(+Project); sqlFunction acos/asin/atan2/log/pow/sqrt/tan projects + tan filter; filter::lessThan::testLessThanWithArithmetic |
| G5 H2 corpus (440 EXACT) | 0 lost | **LOST 11, GAINED 10** | lost: qualifier::testSubAggregationInQualifier, calendarAggregations Annualized/Cme/Pwa/UnionWithWtdAndPwa, groupBy::testGroupByWithWavgAggregation (+tds ×2), dataType::testSimpleTypeMapping, relation::aggregation::testSubAggregationWithIfOnRelationMapping, lessThan::testLessThanWithArithmetic. GAINED: ten `mapping::embedded::…::testGroupBy*` rows (sums over DECIMAL now typed right on H2) |
| G6 PCT DuckDB | green | **RED**: PctCensusGate "wire divergence grew" Standard 25, Essential 29, Relation 29, Unclassified 29, Grammar 37 columns; plus 1 failure + 7 errors (206-suite), 1 + 3 (346), 1 + 0 (470), 1 + 0 (94), 1 + 2 (138) |
| G7 PCT H2 relation | 1 F / 26 E (baseline) | **unchanged** | |
| G9 Channel B dual verdict | 0 disagree | **26 disagree**, residue 22 | |
| G1 core suite | green | 4 failures | `DuckDbRenderTest.semanticContract` + `remainingExprVariants` (pin the DIVIDE/FloatLit spellings), `MapOptionalSourceTest.rigidNumberAcceptsFloat`, `EqualityWorldsConformanceTest.declaredDivergences` |

Logs kept: `$CLAUDE_JOB_DIR/tmp/census-g{1,4,5,6}.out`, `fail-census-{duck,h2}.txt`.

## 5. What the census says

1. **Flip 2 (division) is wrong** on the evidence: it costs 50 H2 rows and buys nothing
   the DuckDB lane needed (DuckDB divides decimals as DOUBLE anyway). F-L stays, with the
   justification in §3: `divide` is a Float operation by signature.
2. **Flips 1 and 3 together cure the 21 DuckDB rows** and cost 20 + 11 corpus rows, the
   PCT wire census, Channel B, and four unit pins. The losses are NAMED (§4) and they are
   not the 21 rows' shapes: they are sqlFunction projections over literals (`sqrt(2.0)`),
   sub-aggregations, calendar aggregations, wavg, a simple-type-mapping row — places where
   a DECIMAL-carried Float now reaches a verdict lane that prints or types it as Decimal.
   The envelope rule in `Render` does NOT cover those paths; the assumption in the
   design sketch ("the envelope converts once") is refuted for the corpus and PCT lanes as
   they stand.
3. **Attribution is unknown**: the census flipped 1 and 3 together. Which of the 21 DuckDB
   rows each flip cures, and which of the 31 corpus losses each causes, needs one census
   per flip (DuckDB lane 25 s + H2 2 min + chain 5.5 min each).

## 6. The homework that remains before a design

1. One census per flip (1 alone; 3 alone) — the same table as §4 for each.
2. For every lost corpus row: which verdict lane printed or typed the DECIMAL carrier, and
   whether the engine's own result (the golden) is a double there. If the golden is a
   double and our cell is a DECIMAL, the fix is in THAT lane's envelope (the same rule
   `Render` already applies), not in the read.
3. The PctCensusGate "wire divergence" rows: what wire type the engine's plan has for each
   of the 25–37 columns. If the engine's wire is DOUBLE where its SQL would be DECIMAL, the
   engine casts at its TDS boundary and lite's envelope must cast there too.
4. Only then the design: which of flips 1 and 3 land, and the envelope rule they need.

Related: docs/STRESS_CORPUS_BURNDOWN_HOMEWORK_2026_09_16.md §5 (the sketch this census
tests); docs/STRESS_CORPUS_THROUGH_LITE_2026_09_16.md rows F-L, F-AE.

## 7. Attempt 1 (2026-09-17): the design as sketched in §2b, every lane — REVERTED

Edits: bare literals (plain spelling); Decimal→Float coercion = type assertion; the
declared-kind DOUBLE cast at three envelopes — `Render` (Float cell on any numeric slot),
`Fold.jsonDateWrap` (Float JSON leaf), `LiteralSpelling.wireValueEgress` (a root whose SQL
label is DOUBLE); division untouched. Stress DuckDB 41 → 20, H2 4,607 → 4,619, zero new.

| lane | result | why |
| --- | --- | --- |
| G4 DuckDB corpus | LOST 14: map::testSubAggregation ×4, projection::aggregation::testSubAggregation ×2, sqlFunction acos/asin/atan2/log/pow/sqrt/tan projects + tan filter | the referee's EXPECTED side carries the engine SQL's own kind — `avg(1.0 * AGE)` replayed is DECIMAL, printed `19.75D` — and our envelope cast made the cell DOUBLE (`19.75`); the assert lane is kind-sensitive |
| G5 H2 corpus | LOST 1 (testSubAggregationWithIfOnRelationMapping: `'Firm D | 52'` for `52.0`), GAINED 10 (embedded groupBy sums over DECIMAL) | a Float cell inside `makeString` goes through `Fold.cellText`, which has no declared-kind rule |
| G6 PCT DuckDB | wire divergence 25/29/29/29/37 (Standard/Essential/Relation/Unclassified/Grammar) | `SqlTypeCensus` pins "a Float-declared column's wire is DOUBLE" at zero — lite's own policy (§4bZ-V C), not the engine's |
| G9 Channel B | 26 dual-verdict disagreements (sqrt, pow, cbrt, atan2, …) | the same wire policy in the Channel B census |
| G1 core | `LowerRelationTest.castErasure` `[42]` → `[42.0]`; `TypeInferenceIntegrationTest.testMixedDecimalIntegerArithmetic` `353791.470` → `353791.47`; `testBigFloatAbs` `123456789123456789.99` → `1.2345678912345678E+17`; `AuditRound3Test.floatReprLargeBand` | the value-egress arm keyed on the SQL label DOUBLE (PureSql maps FLOAT and NUMBER — and the root's label for a bare literal — to DOUBLE) fired on Number- and Decimal-declared roots; and lite's DESIGN carries precision-exact Float literals as decimals (AssertVerdicts B8: "the reference's own interpreted Float is BigDecimal-backed") — a DOUBLE cast destroys the written digits |
| G7 PCT H2 | unchanged | |

**Conclusion.** The product flips are the engine's rule; the VERDICT LANES are not yet.
Four things must adopt the engine's rules before the flips can land: (1) the corpus
referee's numeric judgement (`AssertVerdicts`): compare by value, kind by the declared type
at the boundary, a DECIMAL carrier for a Float-declared column is not a mismatch; (2) the
PCT wire census and (3) the Channel B census: the wire may be DECIMAL for a Float-declared
column — the DOUBLE requirement moves to the PCT envelope only, where the engine's adapter
converts; (4) the precision-exact Float design (B8): decide whether a Float literal beyond
double precision keeps its digits (lite today; also what the engine's SQL does, since it
never converts) — then the envelope cast must NOT apply to literal-rooted cells, only to
computed ones. `Fold.cellText` gets the declared-kind rule with the rest. Size: a lane leg
of its own, judged by the same table.
