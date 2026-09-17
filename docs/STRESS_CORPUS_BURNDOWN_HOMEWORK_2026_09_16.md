# Stress corpus — burn-down homework (2026-09-16, late)

The remaining rows after F-M / F-O / F-Q (commits 07ad22dde, f3f945f01, 71a407160), read
one by one with the engine's behaviour beside each. Numbers are the DuckDB shared lane
unless marked H2. Every row below was diagnosed from the fail file, the corpus source,
the engine's Pure/Java, and where needed a SQL dump (`LEGEND_LITE_DUMP_SQL=1`) or a
standalone probe; nothing here is a guess and the two open verifications are marked.

| lane | pass | fail | skipped |
|---|---|---|---|
| DuckDB shared (gate 10, MIN_PASS 4,564) | 4,564 | 156 | 16 |
| H2 fresh per test (MIN_PASS_H2 4,509) | 4,509 | 211 | 16 |

The 156 DuckDB rows fall into eleven families. The 55 H2-only rows add four more.

## The ladder, ordered by rows

| # | rows | family | verdict | one-line fix shape |
|---|---|---|---|---|
| 1 | 73 | `orElse` unknown | FIX | the prelude generator does not scan the engine file that defines it |
| 2 | 15 | `isAlphaNumeric` dynafunction unsupported | FIX | one DynaFn lowering (`regexp_matches(x, '^[a-zA-Z0-9]*$')`) |
| 3 | 8 + 5 + 1 | aggregate / `isEmpty` over a navigation whose join is OR or a range | FIX (silent-wrong first) | correlate by the parent's key, carry the whole condition |
| 4 | 14 | timestamp text without `+0000` (TDS 12, graph 6, two overlap) | FIX | the runner's cell and the JSON envelope use the canonical rule |
| 5 | 11 | last-digit floats (`* 100.0`, decimal division, avg, sum) | DESIGN | numbers stay decimal in SQL; double at the envelope only |
| 6 | 4 | `dateDiff` in HOURS off by one | ENGINE-QUARANTINE | lite matches Pure's Java; the engine's H2 SQL diverges |
| 7 | 2 | `hier::Profile` got String | WALL | external-format `Binding` property mapping is not built |
| 8 | 2 + 2 | scalar subquery returns many rows (view-backed to-one child) | FIX | correlation substituted into the view's own alias (`HAVING t.B = t.B`) |
| 9 | 2 | `Otherwise` embedded: counterparty null | FIX | the fallback set's join is never taken |
| 10 | 2 | timestamp column read as Date (`firstHourOfDay`) | FIX | the truncation table casts to Date; the engine keeps DateTime |
| 11 | 1 | `groupBy` over an enum-mapped column splits by raw value | FIX | group by the projected expression, not the source column |
| 12 | 1 | routed head + nested association: `t2.OTC_ID` not found | FIX | the F-O demand seam applied to the nested widening |
| 13 | 4 (F32, D_PaymentDense counted above) | — | — | — |
| H2-a | 54 | `EPOCH_MS` not found on H2 | FIX | H2 render arm for the hour/minute/second elapsed form |
| H2-b | 9 | `CHAR(n)` padded (`'KG    '`) | VERIFY then FIX | H2 pads CHAR; the engine's seeding yields `'KG'` |
| H2-c | 1 | `REVERSE` not found on H2 | FIX | the engine's H2 extension alias; the corpus lane registers it, the stress opener does not |
| H2-d | 1 | `firstDayOfWeek` Sunday vs Monday | FIX | engine H2 spelling: `dateadd(DAY, -(mod(dayofweek(x)+5, 7)), x)` |

## 1. `orElse` — 73 rows

**Engine.** `meta::pure::functions::lang::orElse<T>(maybe:T[0..1], dflt:T[1]):T[1]` is a plain
Pure function in `legend-engine-core/.../core/pure/corefunctions/langExtension.pure`,
defined as `coalesce($maybe, $dflt)`. Lite has all six `coalesce` natives.

**Lite.** The generated prelude (`core/src/main/resources/com/legend/builtin/prelude.pure`)
does not contain it; its one `orElse` mention is inside a copied engine body. The prelude
generator's engine roots (`PreludeGeneratorTest.ENGINE_SPEC_ROOTS`) include
`legend-engine-core/legend-engine-core-pure`, so the file is in scope; the function is not
picked up — find why (a roster/filter on `corefunctions`, or the `lang` package), fix the
GENERATOR, regenerate. Never hand-add it (generate from spec).

**Corpus.** Seven derived properties (`dailyChange`, `dailyReturnPct`, `versusPeers`,
`change`, `changeInBps`, `rateFraction`, `feeOn`) and one withdrawn property
(`trading::Trade.netCost`, `docs/UPSTREAM_FINDINGS.md` F7). Judge: the 73 rows plus the
`netCost` note.

## 2. `isAlphaNumeric` — 15 rows

The whole `combo::` family: `combo::ComboMapping` fails to normalize because
`DynaFn.IS_ALPHA_NUMERIC` is `Resolution.UNSUPPORTED`. Engine spellings: DuckDB
`regexp_matches(%s,'^[a-zA-Z0-9]*$')`; H2 `regexpPattern('%s')` over
`transformAlphaNumericParamsDefault` (same pattern). One `SqlFn` + two render arms.

## 3. Navigation whose join is OR / a range — 8 silent-wrong + 5 loud + 1

The three joins: `Trade_ClearingRoute (venue = venue OR type = product)`,
`Line_ExemptionRule (cpty = cpty OR country = country)`, `Trade_BrokerageTier (notional
BETWEEN min AND max)`, and the self-join `Pillar_LongerPillar (... AND tenor < {target}.tenor)`.

**Silent-wrong (8 rows, the worse kind).** `CorrelatedSubselects.parentKeysLenient` descends
`or` and collects BOTH equalities as if they were conjunctive equi keys; the grouped
subselect then groups and re-joins on both keys. Witness: `clearingRoutesCount` 8 (every
route) where the engine says 2; `routedTradesCount` 0 where the engine says 17;
`matchingRules` 3 vs 0; `linesReached` 0 vs 1. Also `CV6 isLastPillar` (`longerPillars
->isEmpty()` over the range self-join): true vs false.

**Loud (5 rows).** The range join has no equality at all: "requires equi-join parent keys".

**Fix shape (one rule).** An aggregate or emptiness test over a navigation correlates by the
PARENT'S KEY (its mapping `~primaryKey`, already a fact), not by columns mined from the
condition; the condition rides whole as the target filter of the correlated subquery
(`parentCopy JOIN target ON cond` — the "association-route head" arm at
`CorrelatedSubselects` ~880 already hand-builds exactly that join). The key-mining path
stays for conjunctive equi conditions if it emits the same rows; the OR/range shapes must
never reach it. Judge: the 14 rows; corpus lanes exact.

## 4. Timestamp text — 14 rows

Engine PURE_TDSOBJECT and graph output spell a DateTime as
`2024-06-03T09:07:00.000000000+0000`. Lite: the runner's `ServiceTestRunner.cell` prints
`d.toEngineString()` (no nanos, no zone); the graph envelope formats with
`DateFmt.ISO_NANO` (nanos, no zone). `CanonicalForm.render` already holds the rule
(hour-or-finer precision → `toEngineString() + "+0000"`). Fix: both paths use that one
rule; the JSON format gains the literal `+0000`. Judge: Q0/Q1/Q2/Q3/Q6/Q7/Q9/Q11, BO0,
SP_defTradeCount, CB_C10/C11/C13/SchemaQualified/Scoped, GG_* six trees.

## 5. Last-digit floats — 11 rows (DESIGN decision)

Probed on both engines with the exact expressions:

| expression | H2 (engine) | DuckDB with lite's rendering | why |
|---|---|---|---|
| `DECIMAL(6,4) * 100.0` | 55.00000 exact | `* CAST(100.0 AS DOUBLE)` → 55.00000000000001 | lite renders every Float literal as `CAST(x AS DOUBLE)` (`AnsiSqlRenderer` FloatLit) |
| `25.0 - g / cap * 100.0` (NUMERIC cols) | 8.9 exact NUMERIC | 8.899999999999999 | today's DIVIDE is a double division; DuckDB's decimal division is double anyway |
| `AVG(DECIMAL)`, `SUM(DECIMAL)` | exact then one conversion | double accumulation (`Lowerer` casts reducers) | |

The engine keeps SQL arithmetic in the database's decimal typing and converts to a double
ONCE at the envelope. Lite decides "Float means DOUBLE" at the READ (`PureSql` maps
FLOAT/NUMBER → DOUBLE; FloatLit; DIVIDE; reducer casts). Rows: confidencePct ×4,
zeroRateBps, headroomToLimit ×2, mean ×2, totalScheduled, converted, inBase.

Proposal: numeric conformance happens at the ENVELOPE, not at the read — a Float literal
renders bare (`100.0`, decimal on both engines), DIVIDE stays a double division only when
an operand is already double, reducers keep the column's decimal type, and the one
`CAST AS DOUBLE` sits where the engine's sits (`Render` output slot). Two of the eleven
(`mean` = avg over DECIMAL(18,4)) may still differ in the last digit because DuckDB's
decimal AVG divides in double; measure before claiming. This touches the conformance-cast
provenance rule (user cast vs synthesized = the same node) and is a design leg, not a
patch. Judge: the 11 rows AND the corpus lanes (108/440) AND PCT — the corpus goldens pin
today's spellings in places.

## 6. `dateDiff` HOURS — 4 rows (ENGINE-QUARANTINE candidate)

Pure's definition (`PureDate.DateDiff.getDiffHours`) is `TimeUnit.MILLISECONDS.toHours(ms)`:
elapsed time, truncated. Lite's `Scalars.elapsed` emits exactly that (`(epoch_ms(b) -
epoch_ms(a)) // 3600000`). The engine's H2 SQL emits `datediff(HOUR, a, b)`, which counts
clock-hour BOUNDARIES: 15:15 → 12:40 is −2 elapsed hours but −3 boundaries; 14:30 →
next-week 10:02 is 163 elapsed, 164 boundaries. The expected rows carry the H2 answers
(−3, 164). Lite is right by Pure's own definition; the engine's relational path is the
divergence. Rows: REGX ×2, DSLocal_RegulatorySubmission, MO2 + DSLocal_MiddleofficeConfirmation
(hoursToMatch). Verdict: record as engine-quarantined (the referee's ENGINE-ASYM class),
never "fix" lite toward the boundary count. The user decides whether the corpus's expected
values get re-derived.

## 7. `hier::Profile` — 2 rows (WALL)

`profile: Binding hier::ProfileBinding : [hier::IssuerDB]HIER_ISSUER.PROFILE_JSON` — an
external-format Binding property mapping (a JSON column bound to a class). Lite reads the
column as a String. Not built; a feature, not a bug. Ledger it as a capability gap.

## 8. View-backed to-one child in a graph tree — 2 (+2 H2)

`GG_BookTree`, `DSTree_CapitalGain`: the `rollup` child (association `Book_Rollup` onto the
aggregated view `NOTIONAL_BY_BOOK`) lowers to a scalar subquery whose correlation was
substituted INTO the view's own alias: `... FROM (SELECT t3.BOOK_ID ... FROM TRADE t3 GROUP
BY t3.BOOK_ID HAVING t3.BOOK_ID = t3.BOOK_ID) AS t4` — a tautology, every book's rollup,
"more than one row returned by a subquery". The parent-side read (`t0.BOOK_ID`) was
rewritten to the view's row var. Fix: the correlation predicate over a view-backed target
keeps its parent side on the enclosing row (the `correlatedGraphChild` free-variable
channel), and never pushes into the view's HAVING. Judge: the two trees.

## 9. `Otherwise` embedded — 2 rows

`O1_CounterpartyOtherwise`, `CF_Confluence`: `counterparty` is an embedded block with an
`Otherwise` fallback to the root set `cptyRoot`; for TRD-0004 the inline columns are null
and the engine falls through to the COUNTERPARTY row (`CP-0002`); lite returns null — the
fallback join is not taken. Read `GraphEmission`/`Substitution` `otherwiseOf` handling for
the projection path (graph takes the fallback per V1 §D.5; the projection read may not).

## 10. `firstHourOfDay` on a timestamp — 2 rows

`CB_C14`, `CB_C7`: expected `2024-08-08T00:00:00.000000000+0000`, got `2024-08-08`. The
truncation table casts the result to Date ("WITH the Date cast", `Scalars` ~651). The
engine keeps the DateTime type for `firstHourOfDay`/`firstMinuteOfHour` (only
`firstDayOfMonth`-class truncations are Dates). Fix: the cast follows the function's Pure
return type.

## 11. `groupBy` over an enum-mapped column — 1 row

`F32`: the projection maps `SIDE` through the enumeration (`'B'`/`'BOT'` → BUY, `'S'` →
SELL) but the GROUP BY groups by the RAW `t0.SIDE`; the data has both `B` and `BOT`, so BUY
splits into two groups: 10 rows vs 9. Fix: group by the projected column expression (the
CASE), the engine's shape. Judge: F32 and the corpus lanes (groupBy goldens).

## 12. Routed head + nested association — 1 row

`D_PaymentDense`: `paidTrade[paymentBase, otcBase]` is a routed navigation; its target
projects the key as `__route0_0` and drops `OTC_ID`; the F-M nested widening then joins
`optionTerms` `ON t2.OTC_ID = t5.OTC_ID` → binder error. Fix: before `widenNestedAssocs`
joins, demand the nested condition's parent-side keys on the routed target pipe
(`StackBuilder.demandForCondition`, the F-O seam). Small.

## H2-only

- **H2-a `EPOCH_MS` (54 rows).** `Scalars.elapsed` emits `EPOCH_MS` for hour/minute/second
  differences; the H2 dialect renders `DATE_DIFF` but has no `EPOCH_MS` arm. Add one
  (`DATEDIFF(MILLISECOND, TIMESTAMP '1970-01-01', x)`), which keeps Pure's elapsed
  semantics on H2 too (see §6 — do NOT route through `DATEDIFF(HOUR, …)`).
- **H2-b `CHAR(n)` padding (9 rows).** Probed with the engine's session settings: H2 returns
  `'KG    '` for `CHAR(6)`; DuckDB treats CHAR as VARCHAR. The engine's expected rows say
  `'KG'`. VERIFY how the engine's `testDataSetupCsv` seeding types its columns (the Pure
  that turns the connection's CSV into setup SQL; the runtime datasource only receives
  `setupSQLs`) — if it creates VARCHAR from the CSV, lite's declared-type DDL is MORE
  faithful to the store than the engine's own seeding, and the honest fix is on the READ
  (H2 CHAR reads trimmed, as the engine's result path evidently does), not in the DDL.
- **H2-c `REVERSE` (1 row).** The engine's H2 extension registers
  `legend_h2_extension_reverse_string`; lite's corpus lane registers the same aliases per
  session (`H2ExtensionFunctions`), the stress opener does not. Either register them in
  the stress opener or render `REVERSE_STRING` natively on H2 — the corpus-lane precedent
  says register.
- **H2-d `firstDayOfWeek` (1 row).** Engine H2 spelling `dateadd(DAY, -(mod(dayofweek(x)+5,
  7)), x)` (Monday); lite's H2 gives Sunday. One render arm.

## Order

1. §1 `orElse` (73, generator fix) → 2. §3 OR/range navigation (14, the silent-wrong ones
first) → 3. §4 timestamp text (14) → 4. §2 `isAlphaNumeric` (15) → 5. §8, §12, §11, §10, §9
(small, 8 rows) → 6. H2-a/c/d (56) → 7. §5 numeric policy (design leg, 11) → 8. §6
quarantine ruling (4) and H2-b verification (9) → 9. §7 wall (2).

Each leg: name the rows it turns green BEFORE editing; judge on both stress lanes and the
corpus lanes; raise MIN_PASS / MIN_PASS_H2 in the same commit; never lower a floor.
