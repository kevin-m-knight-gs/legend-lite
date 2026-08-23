# R0 — The Canonical Form Spec (verdict-in-DB byte channel)

Charter: docs/STAMP_DISCIPLINE_PROGRAM.md § CANONICAL-RENDER VERDICTS
LEG. Every row below is traceable to docs/CANONICAL_RENDER_HOMEWORK.md
(H1–H6), which traces to real legend-pure/legend-engine sources, PCT
pins, or corpus witnesses. This doc is the SPEC the render owner
implements and the cutover slices cite; changes here are spec changes
and need a witness.

## 0. The claim

For every value kind IN THE CLAIMED DOMAIN (§4), pure equality holds
iff the canonical renders are byte-equal:

    equal(e, a)  ⟺  render(e) == render(a)

The database computes both renders; the byte compare IS the verdict of
record. The host lattice (PureAsserts) survives ONLY as the parallel
referee (ratified dual-verdict design): computed next to every DB
verdict inside the harness, disagreement = pinned shrink-only census
row, never a rescue, unreachable from the product path (ArchUnit).

## 1. Two channels, never mixed

| Channel | Canonical form | Owner |
|---|---|---|
| SCALAR (assert operands, scalar/list values) | pure `toString()` per H1 | Render (grown by R1) |
| GRID (TDS verdicts) | engine `toCSV()` per H2 | Render.java (EXISTS — engine cell rules verbatim, corpus-proven) |

A scalar assert never sees the grid form and vice versa (H2 key
finding). The channels differ ONLY on temporals:
scalar = `yyyy-MM-ddTHH:mm[:ss[.S+]]+0000` (GMT-normalized, subsecond
precision preserved as written); grid = `yyyy-MM-dd HH:mm:ss` (space,
seconds, zoneless) / `yyyy-MM-dd` date-only.

## 2. Per-kind canonical render (scalar channel)

| Kind | Canonical form | Ground |
|---|---|---|
| Integer | `1`, `-1` — no decoration | H1 testIntegerToString |
| Boolean | `true` / `false` | H1 |
| String | verbatim bytes, unquoted | H1 (toRepresentation quoting is NOT the canonical channel) |
| Float | fixed-point ALWAYS, never exponent; trailing zeros collapse to one (`17.000`→`17.0`); integral floats keep `.0`; leading zero enforced (`0.01`) | H1 testFloatToString×4; DuckDB bare CAST DIVERGES on small magnitudes (`1.3421e-08`) → R1 builds a no-exponent format expression |
| Decimal | SCALE-NORMALIZED print (trailing zeros stripped, integral keeps `.0`) | FORCED by equality: pure Decimal equality is numeric/scale-blind (assertEq(8D, toDecimal(8)) pin, PureAsserts compareTo) — a scale-preserving print would over-refuse. H6: no contrary print witness exists. |
| Date (partial) | `2014`, `2014-01`, `2014-01-01` — precision preserved, zero-padded | H1 |
| DateTime | `yyyy-MM-ddTHH:mm[:ss[.S+]]+0000`, GMT-normalized, subsecond precision preserved as written (`.000` ≠ `.0` ≠ none) | H1; +0000 STRFTIME sites already implement this (audit: "+0000 archaeology closed clean") |
| List | `[a, b, c]` — elements by this table, `, ` separator, nested allowed | H1 testListToString |
| Enum value | bare member name | H1 |
| Empty/null cell (grid) | `''`; `TDSNull` only under renderTdsNull=true | H2 toCSVString:232 — the sentinel's origin |

Grid channel: the table above via H1 for non-temporal cells + CSV
escaping (quote iff `,` `"` `\n` `\r`; `"` doubles) + header/`\n`
framing per H2.

## 3. Equality lattice the byte channel must reproduce

From H4 (PureAsserts), the cross-kind rows the canonical form makes
byte-decidable:

- integral × integral: by value → identical canonical integers. OK.
- integral × Decimal: NUMERIC equal (PCT testIntToDecimal) → both
  render scale-normalized (`8` vs `8.0`: **R1 rule — Decimal canonical
  of an integral value renders WITHOUT `.0`**, matching Integer, so
  numeric equality stays byte-decidable. This is the one place the
  render is chosen by the lattice, not by toString: toString has no
  witness (H1 OPEN row) and equality is the binding requirement.)
- integral × Float: NOT equal in pure → `8` vs `8.0` byte-differ. OK.
- Float × Float: exact equal → byte-equal. The 2-ULP tolerance is a
  DECLARED NUMERIC POLICY outside the byte channel (§4), R3 census
  decides retire-vs-keep.
- Temporal precision: distinct subsecond precisions are DISTINCT pure
  values → precision-preserving render byte-differs. OK.
- TDSNull sentinel: expected-direction only (H4) — grid channel
  renders `''`; the sentinel policy rides the harness expected-side
  decode, not the render.

## 4. The claimed domain and the named residue

IN: Integer, Boolean, String, finite Float, Decimal, Date/DateTime
(all precisions), enum values, lists thereof, TDS grids thereof.

OUT (loud residue — walls or the declared policy, never silent):
- NaN / ±Infinity / -0.0: ZERO witnesses in the pure essential tree
  and the engine relational tree (H6, measured). Non-finite floats
  WALL at the render with a named reason.
- Huge-magnitude prints (1e300): no witness (H6) — same wall.
- assertEqWithinTolerance (6 corpus sites): declared numeric policy,
  outside the byte channel by definition.
- Class instances: identity ids are not canonical (H1) — instance
  equality stays a host/referee concern, out of the byte channel.

## 4b. THE PERMANENT BOUNDARY (V11 close, user-converged 2026-08-22)

The DB channel was never going to claim everything, and this section
makes the boundary a finished design statement instead of unmet
ambition. The DB is the verdict of record FOR THE CLAIMED DOMAIN; the
host lattice is the verdict of record FOR THE RESIDUE BELOW (openly,
not as a fallback); the host shadows every DB verdict as the permanent
referee. Declines are a boundary census, NOT debt — each lands in one
of three tiers:

- **Tier A — no NATIVE type (nearly empty once carriers are allowed):**
  decimals beyond 38 significant digits / integers beyond HUGEINT as
  native kinds (text-encodable, so even these are Tier-B-with-work).
  NUL-bearing strings turned out to be NEITHER tier (user catches
  2026-08-22, twice): DuckDB VARCHAR holds NUL fine — the failure was
  our StringLit renderer putting the raw byte into statement text
  (lexer death). FIXED at the spelling (chr(0) splice). A "Tier A"
  claim should be re-verified against the DB's actual value domain
  before it is believed.
- **Tier B — erased by SQL's TYPE system but encodable as data
  (CLAIMABLE; the pattern is proven):** per-element kind variance
  (mixed collections, if-branch CASE promotion, the print-form
  identity carrier) → F10 variant carrier; temporal written-precision
  → ALREADY CLAIMED via the precision-faithful VARCHAR convention.
  When the type channel erases, move the information into the value
  channel — never guess.
- **Tier C — not data at all (permanently host/model territory):**
  model-defined equality (equality.Key stereotypes — X5 claims the
  byte compare via key-derived canon, the SEMANTICS stay model-owned);
  object identity (eq() on non-primitives — rows have no identity);
  orchestration handles (Connection/Runtime); the metamodel itself
  (Package/Multiplicity/ValueSpecification, lambdas as values).

Every decline reason maps to a tier; a decline that fits no tier is a
bug in the wrap, not a new category.

## 5. Cutover order (R2, per-family hard cutovers)

By H5 distribution (assert sites over the corpus universe):

1. assertEquals scalars (~692 literal-expected sites)
2. assertEquals collections (~376 list-literal sites)
3. assertEquals grids (toCSV text — 105 sites already spell toCSV)
4. assertSameElements (402): canonical ORDER BY + render + byte compare
5. assertSize (185) is already SQL-shaped; assert-predicate (32) is
   VerdictQueries.predicateVector (landed); small tails last.

Each family cutover DELETES its verdict-affecting host arms in the
same slice (eval-ledger pins DOWN every commit); PureAsserts moves to
the referee as the permanent parallel host verdict, disagreement
census pinned 0 shrink-only.

## Amendments

- **§3 kind qualification (R1a follow-on, 2026-08-22):** the byte
  compare is over (kindClass, text) pairs — the render alone is not
  injective across kinds (String "8" vs Integer 8). The numeric tower
  is ONE kind class.
- **§2/§4 zeros unify (2026-08-22, witness parseFloat('-000.000') via
  the PCT relational lane):** pure grants 0.0 == -0.0, so the
  canonical Float render of EVERY zero is "0.0" — negative zero moved
  from residue INTO the claimed domain, unified. Same ruling shape as
  integral Decimal: where print and equality conflict, equality binds
  the canonical form.
