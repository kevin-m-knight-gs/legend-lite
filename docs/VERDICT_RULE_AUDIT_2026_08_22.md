# VERDICT RULE AUDIT — every rule vs engine source (2026-08-22)

(user: "do audit of every single rule from engine and then lets figure
out which ones are hacky on our side and fix all of them")

THE ENGINE'S ONE EQUALITY (verified in source, interpreted runtime):
`EqualityUtilities.equal` — (1) identity; (2) both-primitive → `eq`;
(3) DIFFERENT CLASSIFIERS → FALSE; (4) Map → mapEquals; (5) class
instances → equality.Key properties recursively, KEYLESS different
instances = FALSE. `EqualityUtilities.eq` (primitives) — SAME
primitive TYPE NAME required + `getValue().equals(...)`; non-boxed
primitives (dates) compare by NAME string. Floats are
BIGDECIMAL-backed and CANONICALIZED at construction
(FloatCoreInstance.canonicalizeBigDecimal: plain string, trailing
zeros stripped keeping one decimal, integral gets .0).

## Verdicts per rule

### DERIVED — engine-source-confirmed (no action)
| Rule | Engine ground |
|---|---|
| Float canonical render (fixed-point, strip-keep-one, integral .0) | canonicalizeBigDecimal VERBATIM — our render is the engine's own canonical form |
| Zeros unify (0.0 == -0.0 renders '0.0') | BigDecimal has no −0; canonicalize normalizes — now DERIVED, no longer witness-only |
| Temporal precision-sensitive record equality | AbstractPureDate.equals (components + exact subsecond string) |
| Temporal 9-digit DB convention (goldenEqualScalar/H2Verify) | DateFunctions.fromSQLTimestamp %09d |
| Collection equality ordered element-wise | EqualityUtilities.equal(ListIterable…) |
| assertEq identity wall for non-primitives | eq = identity for instances |
| Enum name equality within one enumeration | enum values are singleton instances; name ≡ identity per enumeration |
| Integer/Boolean/String canon + equality | value equality; casts are the H1 forms |
| assertSameElements = sort-then-ordered-equal | assertSameElements.pure |
| Map equality | mapEquals (key-wise) — wireTree's map arm approximates; formal check at Key-equality slice |
| Non-transitivity consequence (pair-moded compare) | follows from eq's same-type-name rule: NO cross-primitive-kind equality exists at all (see HACKY below — our pair rules are LESS strict than engine) |

### HACKY — grants the engine never makes (FIX: tighten, referee names any load-bearing wire bug)
| # | Our rule | Engine truth | Fix |
|---|---|---|---|
| X1 | integral×Decimal numeric grant (BigDecimal compareTo) — "witness testIntToDecimal" MIS-CITED: that test is Decimal×Decimal | eq: type names differ → FALSE | Delete the grant; add stamp-driven Decimal egress decode guard (a DECIMAL-stamped cell must decode BigDecimal); any corpus/PCT break = OUR kind-drift wire bug, fix at the seam |
| X2 | Decimal×Decimal compareTo (scale-blind) | getValue().equals — SCALE-SENSITIVE; engine tests spell the exact SQL-arithmetic scale and pass STRICT in both engine lanes | Tighten to equals; breaks reveal OUR scale drift vs engine SQL arithmetic — fix emission, not the compare |
| X3 | Float×Decimal fp-grant (string-BigDecimal compare) | cross-kind FALSE | Delete with X1 |
| X4 | int×float "statically FALSE" as a SPECIAL rule | not special — ALL cross-primitive-kind pairs are FALSE | Replace the numeric-tower kind class with per-kind classes + the ONE cross-kind rule (engine's classifier/type-name gate); valueMode survives only for SAME-kind Decimal scale…(no — dies with X2) |
| X5 | wireTree ALL-FIELDS structural equality applied to pure class instances | equality.Key properties or FALSE (keyless) | Model-defined Key equality at the K-arm (ctx has the stereotypes); wireTree stays for its JSON-document and SQL-struct domains only |
| X6 | 2-ULP Float tolerance | engine floats are EXACT DECIMAL (BigDecimal-backed) — the tolerance compensates OUR IEEE-double carrier | Reclassify with the true derivation; R3 decides keep-as-declared-carrier-policy vs decimal-backed float carriage (L) |

### THE 91 DECLINES — claim plan (after the tightenings)
- Class instances (~2/3): model-defined Key equality — engine Pair
  (first,second) and List (values) carry <<equality.Key>> IN SOURCE;
  OUR platform declarations omit the stereotypes (comments only) —
  carry them, add ctx.equalityKeyProperties (hierarchy walk like
  _Class.getEqualityKeyProperties), K-arm instance rule + keyed-
  instance byte canon (kind = instance:fqn, canon = key canons in
  declaration order). Also unlocks the 2 PCT eq/equal NonPrimitive
  exclusions.
- Pair/List/Map containers (~1/3): same mechanism (they are classes
  with Keys); Map via mapEquals.
- Unrefinable Numbers (few): runtime kinds now cover; re-census.

Slice order: (1) X1–X4 tightenings + decode guard (referee names the
compensated wire bugs); (2) Key equality (X5 + the 91); (3) re-census
+ ceiling drop; (4) R3 with X6's derivation.
