# Canonical-Render Homework (pre-R0 fact tables)

Charter: docs/STAMP_DISCIPLINE_PROGRAM.md § CANONICAL-RENDER VERDICTS
LEG. Rule: every R0 row must be traceable to a SOURCE (real
legend-pure/engine checkout), a PCT pin, or a corpus witness. No
guessing, no sampling. Checkouts: /Users/neemsandv/legend/legend-pure,
/Users/neemsandv/legend/legend-engine.

## H1 — pure's normative print spec (toString / toRepresentation)

Source: platform/pure/essential/string/toString/toString.pure (PCT
tests = the behavioral spec) and toRepresentation.pure.

| Kind | toString canonical form | Witness |
|---|---|---|
| Integer | `1`, `-1` — no decoration | testIntegerToString |
| Float | `3.14`; trailing zeros COLLAPSE to one (`17.000`→`17.0`); integral floats keep `.0`; NEVER exponent notation (`1.3421e8`→`134210000.0`, `134.21e-10`→`0.000000013421`); leading zero enforced (`.01`→`0.01`) | testFloatToString\* (4 tests) |
| Boolean | `true` / `false` | testBooleanToString |
| String | verbatim, unquoted (toString); toRepresentation quotes + escapes `\\` and `\n` | testStringToString / toRepresentation:80-84 |
| DateTime | `yyyy-MM-ddTHH:mm[:ss[.S+]]+0000` — GMT-NORMALIZED (`-0500` input prints shifted `+0000`); SUBSECOND PRECISION PRESERVED AS WRITTEN (`.0000` ≠ `.000` ≠ `.0` ≠ none — four distinct prints); minute-precision keeps `T00:00+0000` | testSimpleDateToString, testDateTimeWithTimezoneToString, testDateTimeToString |
| Date (partial) | `2014`, `2014-01`, `2014-01-01` — precision preserved, zero-padded | testDateToString |
| Decimal | toString = bare number (see OPEN row); toRepresentation = toString + `D` | toRepresentation.pure:24 |
| List | `[a, b, c]`, nested `[[a, b], c]` — elements by toString, `, ` separator | testListToString |
| Pair | `<a, b>` | testPairToString |
| Enum value | bare member name `CITY` (no enum prefix) | testEnumerationToString |
| Enum type / Class | simple name `STR_GeographicEntityType`, `STR_Person` | testEnumerationToString, testClassToString |
| Class instance | `Anonymous_…`/`@_…` id (NOT canonical — identity, excluded from byte channel) or user-defined toString() qualified property | testPersonToString, testComplexClassToString |
| toRepresentation deltas | String quoted+escaped; Date prefixed `%`; Decimal suffixed `D`; else = toString | toRepresentation.pure |

OPEN H1 rows:
- Decimal SCALE in toString (`17.00d` → `17.00` or `17.0`?): no pure
  platform witness found; hunt engine PCT expected files (H6).

## H2 — engine's grid (TDS) canonical form: `toCSV()`

Source: core_relational/relational/helperFunctions/helperFunctions.pure
:208-250 + corefunctions/dateExtension.pure:384-391. The corpus's grid
asserts spell `$result.values->toCSV()` against expected text — THIS
is the grid byte channel, already engine-defined:

| Rule | Form | Source |
|---|---|---|
| Frame | header = column names joined `,`; rows joined `\n`; TRAILING `\n` | toCSV:208-212 |
| TDSNull | `''` (empty cell); `'TDSNull'` only under renderTdsNull=true — THE sentinel's origin | toCSVString:232 |
| Temporal w/ hour | `yyyy-MM-dd HH:mm:ss` — SPACE separator, NO subsecond, NO zone (≠ scalar toString's `T…+0000`!) | SimpleDateTimeFormat |
| Date-only | `yyyy-MM-dd` | ISO8601DateFormat |
| Everything else | pure `toString()` (H1 forms) + CSV escaping | toCSVString:234 |
| CSV escaping | quote iff contains `,` `"` `\n` `\r`; `"` doubles to `""` | escapeCSVString:245-250 |

KEY FINDING for R0: TWO canonical temporal forms BY CHANNEL — scalar
channel = pure toString (`T`, subsecond preserved, `+0000`); grid
channel = toCSV (` `, seconds precision, zoneless). R0 defines both;
they never mix (a scalar assert never sees the grid form and vice
versa). Also: grid cell floats/decimals ride H1's toString rules —
the Decimal-scale OPEN row applies to BOTH channels.

## H3 — our emission census (what the platform already renders in SQL)

**HEADLINE: the GRID canonical owner ALREADY EXISTS.** Render.java
(916 lines, F4.1/F4.2) IS the engine's toCSV constructed as a SQL plan
projection — engine cell rules verbatim (including the
escape-only-the-datetime-branch quirk), "Java never touches the value
bytes", corpus-proven. The grid byte channel is therefore mostly
PLUMBING (route grid verdicts through Render + byte compare), not new
emission. The real emission work is the SCALAR channel.

**DuckDB raw-cast print probe (2026-08-21, empirical), vs pure H1:**

| Value | DuckDB `::VARCHAR` | pure toString | Verdict |
|---|---|---|---|
| 3.14 | `3.14` | `3.14` | agree |
| 17.000 (DOUBLE) | `17.0` | `17.0` | agree |
| 134210000.0 | `134210000.0` | same | agree |
| 0.000000013421 | `1.3421e-08` | `0.000000013421` | **DIVERGES — DuckDB switches to exponent for small; pure never does. Scalar render needs a no-exponent format expression (rtrim-zeros + fixed-point), not bare CAST.** |
| 1e300 | `1e+300` | (no witness — 300-digit print? OPEN) | H6 row |
| 17.00::DECIMAL(10,2) | `17.00` | OPEN (H1) | scale is the TYPE'S (DECIMAL(10,4) pads `17.1000`) — feeds the scale-blind-equality tension |
| 1 (BIGINT) | `1` | `1` | agree |
| true | `true` | `true` | agree |
| NaN / Inf / -0.0 | `nan` / `inf` / `-0.0` | `NaN`/`Infinity` (Java print, needs witness) | **DIVERGES in spelling — edge rows, H6 witnesses required** |

Other existing scalar print sites (corpus-pinned): dateLiteralPrint +
STRFTIME/CONCAT (pure toString's `+0000` DateTime — the audit's
"+0000 archaeology closed clean" confirmed these implement pure's own
spec); Scalars:602/611 STRFTIME rules; PctTdsWrap (PCT wire channel).

## H4 — host-arm policy inventory (absorb or retire, each named)

Partial (from the audit + direct read):
- PureAsserts.equalScalar lattice: integral×integral by value;
  integral×Decimal NUMERIC (PCT testIntToDecimal witness);
  integral×Float FALSE; Decimal×Decimal compareTo (scale-blind!);
  Float×Float exact-then-2-ULP (policy); NaN never equal; ±Inf by
  identity; TDSNull sentinel expected-direction only; temporal
  string-carrier bridge (symmetric).
- NOTE the tension for R0: pure equality is SCALE-BLIND for Decimal
  (compareTo) but Decimal toString may PRESERVE scale — byte-equality
  ⟺ pure-equality then FAILS for Decimal unless the canonical form
  normalizes scale. R0 must decide: canonical Decimal = scale-
  normalized print (strip trailing zeros) — pending the H6 witness.
Remaining arms: the audit's per-surface adjudication tables
(docs/HOST_LOGIC_AUDIT_2026_08_20.md §Per-surface adjudication) ARE
the source-grounded inventory — repr/reprSide/joined (second spelling
owner beside Render; eviction: failure messages compose from
DB-rendered reprs), sorted/typeRank/withinRank (third impl of pure
total order; eviction via SQL ORDER BY), GridCompare
renderedText/lineEquals/cellEquals (compares RENDER OUTPUT vs expected
text with a sig-digit float tolerance — burn-pressure policy,
corpus-scoped, census overdue), grids/tdsEquivalent (Java cell
compare; eviction: EXCEPT ALL / row_number / abs-delta in SQL),
JsonCompare (clean, two leaf rules, keep). Decimal note: pure Decimal
equality is NUMERIC (assertEq(8D, toDecimal(8)) pin + PureAsserts
integral×Decimal), so the canonical Decimal render MUST be
scale-normalized or byte-equality over-refuses — R0 row.

## H5 — corpus assert distribution (measured over the engine
relational test tree = the corpus universe)

| Family | Count | Byte-channel route |
|---|---|---|
| assertEquals | 1,401 | scalar/list canonical render + byte compare (grid subset via toCSV text — 105 call sites in 16 files spell toCSV already) |
| assertSameElements | 402 | canonical ORDER BY + render + byte compare |
| assertSize | 185 | COUNT(*) — already SQL-shaped |
| assert (predicate) | 32 | VerdictQueries.predicateVector (landed) |
| assertEqWithinTolerance | 6 | declared numeric policy — OUTSIDE byte channel |
| assertNotEmpty/assertEq/assertEmpty/assertInstanceOf | 5/4/3/1 | small tails |

assertEquals operand shapes (grep census): ~467 string-literal
expected, ~376 list-literal, ~225 numeric-literal, 10 date-literal.
The expected literals are ENGINE-RENDERED canonical text — the ground
truth the byte channel compares against.

## H6 — edge witnesses (NaN/±Inf/-0.0/Decimal-scale/empty)

**MEASURED: ZERO NaN/Infinity/-0.0 witnesses** in the pure essential
tree AND the engine relational test tree. DECISIVE for R0: the edge
catalog's scariest rows are OUT OF THE BYTE CHANNEL'S CLAIMED DOMAIN —
non-finite floats route to the definitional residue (loud wall or the
surviving host arm), not solved in the serialization. The 1e300-print
question likewise has no witness (same ruling). Decimal-scale: value
witnesses exist (assertEq(3.8D, toDecimal(3.8)); 8D numeric) but no
toString pin — R0 rules scale-normalized canonical render on the
equality-⟺ requirement, not on a print witness.

## Homework verdict → R0 shape

1. GRID channel: ALREADY DONE (Render.java = engine toCSV in SQL,
   corpus-proven). R2's grid cutover is plumbing + GridCompare arm
   deletion, not emission.
2. SCALAR channel: pure-toString render mostly exists for temporals
   (+0000 sites); needs a no-exponent float format expression and a
   scale-normalized Decimal print; Integer/Boolean/String are bare
   casts that already agree.
3. Non-finite floats + huge-magnitude prints: out of claimed domain
   (no witnesses) — loud residue, never silently absorbed.
4. Tolerance (2-ULP, sig-digit): declared numeric policies OUTSIDE the
   byte channel; R3 census decides retire-vs-keep, corpus-scoped.
5. The distribution says assertEquals + assertSameElements = 1,803 of
   2,039 assert sites — the two families R2 cuts over first.
