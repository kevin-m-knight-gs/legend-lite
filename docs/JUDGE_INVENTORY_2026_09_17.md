# The judge inventory (2026-09-17)

Every place in the test harness that decides pass or fail for a numeric value, read from
the code — the map that did not exist while three numeric attempts were tried. "Kind
source" is where each judge decides what a Float is; "numeric rule" is how it compares.

## A. The corpus referee (engine `<<test.Test>>` assertions → verdict), `AssertVerdicts`

The verdict of record is the BYTE verdict when the SQL canon channel claims the pair;
the HOST lattice judges only when the byte channel declines (`finish()`:
`held = byteHeld != null ? byteHeld : hostHeld`).

| # | judge | lives in | what it compares | kind source | numeric rule |
| --- | --- | --- | --- | --- | --- |
| A1 | BYTE canon, scalar/collection sides | `CanonicalRenderSql.wrapWithCanon` → `LiteralSpelling.leaf/literal` (SQL) | the database-printed canonical TEXT of each side's value column, sorted for multisets | the side's DECLARED Pure type (`t`); an unrefined Number gets one candidate per fine kind, selected by the runtime value kind | Float: `floatCanon(CAST(v AS VARCHAR))` — the carrier's own text (a DECIMAL prints its scale: `32.00`); Decimal: scale-preserving text; Integer: bare |
| A2 | BYTE canon, grid (TDS) sides | `CanonicalRenderSql.wrapTdsCanon` (SQL) → `TdsCompare.tdsRowCanons/tdsCellCanons` | per-cell canonical text, rows framed | the grid's DECLARED column types (`schema.columns().get(i).type()`) | same leaf rules as A1 |
| A3 | HOST lattice, scalar/collection sides | `PureAsserts.equal/equalScalar/assertSameElements` (Java) | fetched JDBC values | the CARRIER (BigDecimal/Double/Long) — since 7e39648f5 also the DECLARED kind when both sides are Float (`floatDeclared`) | same kind required; Double×Double by canonical string then 2-ULP; BigDecimal×BigDecimal `equals` (scale-sensitive); Float-declared BigDecimal×Double by `compareTo` |
| A4 | HOST lattice, TDS-row sides | `AssertVerdicts.tdsRowValuesVerdict` → `PureAsserts.equal(e, a)` (untyped) + `TdsCompare.rowTupleMultiset` | flat cell lists | the CARRIER only (the `floatDeclared` flag is NOT threaded here) | as A3 without the declared-kind arm; plus `ulpOnlyCellDrift` (2-ULP grid leniency when the host holds and the byte canon differs) |
| A5 | JSON document judge (graph results) | `AssertVerdicts` `ASSERT_JSON_STRINGS_EQUAL` → `JsonCompare.document` | parsed JSON trees | the JSON token kind (a number with a point parses BigDecimal; an integer parses Long) | BigDecimal×BigDecimal by `compareTo` (scale-blind); Long×BigDecimal UNEQUAL on purpose; text otherwise |
| A6 | rendered-text judges (`toCSV`, `#TDS` strings, makeString cells) | `Render.csv` / `Fold.cellText` (SQL) → compared as strings by the assert | the TEXT the database prints for each cell | the cell's declared Pure type at `cellText` (Float → `declaredDouble`, known facts only, since 7e39648f5) | string equality |
| A7 | the tolerance assert | `PureAsserts.assertEqWithinTolerance` | two numbers | carrier | exact arithmetic for exact kinds, double otherwise |

## B. The SQL-text replay arms (the golden SQL text executed on our database), `SqlTextVerdicts` + `spec/harness/ReplayOracle`, `H2Verify`

| # | judge | what it compares | kind source | numeric rule |
| --- | --- | --- | --- | --- |
| B1 | replayed golden rows vs our rows (`H2Verify.norm` cell canon) | order-insensitive rows of normalized cell text | none — the carrier's text | integral values EXACT; fractional values rounded to 10 significant digits (a declared cross-engine tolerance, `leniency("float-10-digits")`) |

## C. The stress corpus runner (service test suites), `TestAssertions`

| # | judge | what it compares | kind source | numeric rule |
| --- | --- | --- | --- | --- |
| C1 | `equalToJson` | expected JSON tree vs our JDBC cells | none | BigDecimal `compareTo` (scale-blind), then 2-ULP for finite doubles (f659d747a) — the engine's own service-test rule plus the referee's policy |

## D. The PCT lane (`Test_LegendLite_*_PCT`, interpreted builder)

| # | judge | what it compares | kind source | numeric rule |
| --- | --- | --- | --- | --- |
| D1 | the PCT test's own `assertEq/assertEquals` in the INTERPRETED runtime | the pure value our adapter returns vs the test's literal | OUR ADAPTER (`pct_adapter.pure`): the value keeps the WIRE kind (a DECIMAL → pure Decimal `32.0D`) — the engine's adapter converts by the DECLARED return type (`resultToType`) | pure `eq`: same kind + `BigDecimal.equals` after canonicalization (interpreted Float is BigDecimal-backed) |
| D2 | `PctCensusGate` / `SqlTypeCensus` wire census | the plan's declared SQL label vs the JDBC wire type per column | the plan label (DOUBLE for Float) | not a value judge — a type pin; DECIMAL under DOUBLE is DELIVERED since 7e39648f5 |
| D3 | `PctDisciplineTest` | adapter size and shape pins | — | — |

## E. Channel B (dual verdict), `pct/…/channelb`

| # | judge | what it compares | rule |
| --- | --- | --- | --- |
| E1 | the SAME A1–A4 machinery over the PCT tests, counting host-vs-byte DISAGREEMENTS | host lattice verdict vs byte canon verdict per assertion | pinned at 0 disagreements; declines pinned at a residue |

## F. Unit pins that encode a numeric spelling

`DuckDbRenderTest` (literal/DIVIDE text), `LowerRelationTest.aggregateOneRow` (`AVG(t0.AGE)`),
`EqualityWorldsConformanceTest` (World 1 vs World 2 per pair), `MapOptionalSourceTest`
(`Double` result object), `TypeInferenceIntegrationTest.testBigFloatAbs` (exact digits),
`AuditRound3Test.floatReprLargeBand` (Float print form of 1e18), `JavaEvalLedgerTest`
(judge files may only shrink), `PctCensusGate`/`ChannelB*` pins.

## Where the kind decision SHOULD live (Rule 2, compiled reference)

Every judge above except D1 reads the plan's root select. The root select's projections
already carry the declared SQL label (DOUBLE for a Float column — what D2 compares against).
ONE SQL pass over the root select — a projection labelled DOUBLE whose expression is not
already DOUBLE is cast — makes the wire carry the declared kind for A1–A4, B1, C1, D2, E1 at
once, and the JSON leaf (A5) and text cell (A6) builders apply the same rule at their two SQL
sites. D1 converts by the declared return type in the adapter (the engine's own rule). After
that no Java judge needs a kind rule: A3's `floatDeclared` arm, A4's gap, the `declaredDouble`
guards on "known facts", and the threaded flags are DELETED, not extended.

## Classification of the last red run (census §9) against this inventory

| red rows | judge | why red | fixed by the seam? |
| --- | --- | --- | --- |
| G4: sub-aggregations ×6 ("canonical sorted renders differ, host agreed") | A1 | Float side canon printed the DECIMAL carrier's scale (`32.00` ≠ `32.0`) | yes — the root cast makes the wire DOUBLE before the canon |
| G4: sqlFunction sqrt/acos/asin/atan2/log/pow/tan ×8, dataType ×2 (`[1.0000000000000000D …]` vs `[1.0 …]`) | A4 | `tdsRowValuesVerdict` judges by carrier; the expected side arrived DECIMAL | yes — both sides' roots cast; A4 then sees Double×Double |
| G5: `'Firm C | 35.50000000000'`, `'19.75000000000 4'` | A6 | `cellText` converted only on KNOWN facts; H2 facts unknown | yes — the cell rule keyed by the declared type, not the fact |
| G5: JSON `68` for `68.0` | A5 | JSON leaf keyed on a known fact; H2 unknown → INTEGER printed | yes — the JSON leaf rule keyed by the declared type |
| G6: `32.0` vs `32.0D`, `1.5` vs `1.5D` ×6 | D1 | adapter returns the wire kind; the pure-side conversion written did not take effect | separate: the adapter conversion (D1), to be debugged with one row + a print |
| G9: sqrt/pow/cbrt/trig disagreements | E1 = A1 | same as the G4 A1 rows | yes |
| G9 declines +2 (rem with floats) | E1 | the canon wrap declined (a cast on an unknown-fact carrier errored?) | to read after the seam |

So: ONE seam (root-select cast by label) + two SQL sites (JSON leaf, text cell) keyed by the
declared type + the PCT adapter conversion. Nothing else. Every previously-red row is
attributed; none needs a judge-specific rule.
