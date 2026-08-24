# F10 SLICE-3 ASSUMPTION AUDIT (2026-08-24, user-ordered)

Trigger: three consecutive WRONG mechanism theories about one corpus
witness (render-convention → compiled-tolerance → transform-everything),
each killed only by reading engine source. This audit re-grades EVERY
design decision the F10 slices shipped: VERIFIED (engine source read,
line citable) / MEASURED (gates+witnesses, no engine claim) / ASSUMED
(needs homework) / WRONG (fix listed). The standing lesson, now a rule:
**a mechanism claim about the engine is not design input until the
source line is cited.**

## The homework that ended the guessing (all engine-source verified)

| Fact | Where |
|---|---|
| Physical test id column is VARCHAR(200) — the store schema's INT declaration is a deliberate lie in their test setup | relationalSetUp.pure:1397 vs :107 |
| TDS cell conversion is a NARROW table by declared column type: Boolean (`$a == 'true' \|\| $a == true`), Decimal (toDecimal), enum (transform); ALL ELSE IDENTITY | relationalMappingExecution.pure buildExecutionResultInTDS (~:507-515) |
| RelationalPropertyMapping.transform is ENUM-ONLY | legend-pure platform_store_relational/functions.pure:218 |
| Compiled equal(): Number-vs-non-Number FALSE; tail is plain equals; NO toString fallback | CompiledSupport.java:1028-1104 |
| In-pure JDBC extraction is BY PHYSICAL JDBC TYPE (INTEGER→Long, VARCHAR→String) | ResultSetValueHandlers.java:143-157 |
| TDSRow.values is a PLAIN property (Any[*]) → rows.values auto-maps and FLATTENS | tds.pure:76-79 |
| assertSameElements = assertEquals(sort, sort) → equal() — fully typed | assertSameElements.pure:17-25 |
| Engine transformer table (platform Java lane): Boolean + temporals only, else identity | SetImplTransformers.java |

## Decision grades

### VERIFIED (engine-source receipts)
- A1 subsecond preservation (spec §3 + AbstractPureDate exact-subsecond
  equality + DuckDB minimal-print probe).
- %-temporal literal grammar round-trip (PureDateLiteral.parse read;
  +0000 normalization confirmed).
- hashCode Integer[1] signed-64; percentile Number[0..1] (earlier
  same-method homework).
- Engine-text marker transparency (golden-caught, engine spelling
  preserved byte-exact).

### MEASURED (gates + witnesses; no engine claim embedded)
- LITERAL label + physical VARCHAR pair; canon = cell identity.
- CarrierStrategies marker transparency on list-less backends.
- unspell/unspellMarked structural inversion (one-owner symmetry).
  ACTION: add the encode∘unspell identity unit test so drift is
  structural, not reviewed-for.
- Lane flag mechanics (witnessed by the 4-regression round-trip).

### ASSUMED — homework now attached
- "floatCanon vs floatPrint differ only at exponent edges" (2b id
  switch): measured green, but the equivalence-domain claim was never
  proven. ACTION: a domain probe test (band sweep) or revert ids to
  print form with a re-spell at the carrier boundary.
- "value lane == safe for the hetero claim": the corpus grid-assert
  witnesses show VALUE-LANE consumers with relation-shaped inputs
  exist. The consumer census was partial. ACTION: the claim stays
  parked until the relation-cells leg (below) closes the consumer set.
- "contains/in byte-membership over spellings == pure equality per
  element": sound for the six scalar kinds by grammar disjointness —
  but never engine-source-cited for contains' own semantics. ACTION:
  read collection contains/in .pure and cite.

### WRONG — found by this audit
1. **decodeAny spelling arms (3b groundwork) = WIRE SNIFFING.** Bare-
   token coincidence was the justification; the engine's rule is
   DECLARED TYPE DECIDES, and the '4'→Long witness shows sniffing
   mis-types raw text on legacy wires. FIX (this commit): spelling
   decode is LABEL-DRIVEN ONLY (the LITERAL arm in unwrap); decodeAny
   loses the spelling arms.
2. **pureToString first-char dispatch (3b groundwork) = same disease.**
   A raw VARCHAR-under-Any cell whose text starts with ' or % would
   mis-print. FIX (this commit): the spelling-print path gates on the
   JUDGED/labeled LITERAL wire, never on cell text.
3. **The identity arm's "wire flatten IS row-major" claim**
   (Typer values-over-relation): FALSE for multi-column relations
   (arity witness testCompositionInExtend 12 vs 36).
4. **Grid cells decoded by guessing** ('4'→Long): ours, pre-existing,
   exposed by the carrier.

## THE CHARTERED LEG THIS AUDIT OPENS — relation-cells (rows.values)

Engine-true target, receipts above: `rows.values` over a relation =
flatten(map(rows, r → [typed cells])), each cell carrying its DECLARED
column pure type through egress and decode; the engine's narrow
conversion table (Boolean/Decimal/enum) applied where OUR wire is
textual; NO value sniffing anywhere. This subsumes Bug A (typed cells)
and Bug B (flatten arity), un-parks the hetero claim (the last
consumer family closes), and completes F10 slice 3. Sized M: Typer
synthesis (map+cells), relation-lane cell carriage decision, corpus
witnesses as the acceptance tests (testInWithDynaFunction,
testCompositionInExtend, testUsingSameAggFunctionTwice ×2 must pass
TYPED).

## THE REDESIGN THIS AUDIT RATIFIES — spell at EGRESS ONLY

The commit review's structural lesson: EVERY consumer failure this
slice hit (format, equality, conformance casts, dedup leak-through)
traces to ONE cause — values spelled MID-EXPRESSION, forcing every
downstream consumer to cope (the unspell machinery is compensation by
construction). The clean architecture: expressions carry RAW values +
the carrier MARKER; the spelling happens ONCE at the egress boundary
(scalarRoot), where representation decisions belong — the same
principle as label-at-construction. Under this shape, unspell,
unspellMarked, the equality/cast harmonization arms, and the needle
arms ALL DELETE (nothing mid-expression ever sees a spelling). This is
the opening design decision of the relation-cells leg.

## THE MECHANISM — the sniff-stop pin (landed this commit)

ArchitectureTest.spellingDecodeIsLabelDriven: LiteralText is reachable
ONLY from the Executor's label-dispatch funnel — decode by declared
label is now a compile-gate, not a review hope. The decodeAny
label-keying (call it only where label==JSON) rides the relation-cells
leg when labels there become honest.

## RE-AUDIT 2026-08-24 (slices 1-3, post carrier-rule reversal)

Graded re-check of everything above, after the relation-cells leg
resolved by REVERSAL (F10_CARRIER_DESIGN.md "RESOLVED by the CARRIER
RULE").

HELD UP (receipts re-verified): slice-1 one-owner grammar + sniff-stop
ArchUnit pin (ArchitectureTest.spellingDecodeIsLabelDriven); slice-3a
temporal grammar; floatCanon ASSUMED item CLOSED
(SqlCanonConformanceTest.floatCanonAgreesWithHostAcrossMagnitudes);
contains/in citation CLOSED (contains.pure cited at the arm); carrier
ratchet pins exact (no slack — an earlier line-count grep under-counted
vs the test's occurrence semantics).

FOUND WITNESS-LESS, NOW PINNED (the corpus fold hides ^TDSNull() from
the compiler, so nothing exercised these): TdsNullTypingPinTest
(ctor = instance [1] via NewChecker; bare ref = sqlNull funnel);
VariantElementLawTest (the value law's five arms + the one-inverse
symmetry). STILL OPEN: a product-surface pin for the wire-presence
egress keep (present-wire json-null slot in an Any collection) —
deferred because no verified product construction reaches it
post-reversal; needs homework on variant toMany null semantics first,
never a fabricated pin.

EXECUTED FROM THE LEDGER (this commit): Lowerer.relationDepth + its
try/finally + the mutable-field allowlist row DELETED — the re-audit
found the counter WRITE-ONLY (no reader ever landed; the lane-flag
justification gated a claim that stayed parked).

LEDGER EXECUTED — SPELL-DEBT BURN-DOWN (user-ordered, same day): the
census found the compensation arms' presumed feeder (the mixed-identity
selection channel's LITERAL-marked outputs reaching mid-expression
consumers) has NO live flow — probed by deletion: all five ChannelB
suites clean (Relation 355/355, Essential census intact — mixedSort
included), full chain green. DELETED: unspell + unspellMarked (the
structural-inverse pair), the equality unspell-one-side arm, format's
decomposition, CastPolicy's Any-conformance re-wrap; ArrayLit ratchet
tightened 42→39 (the three inversion rebuilds). DOCTRINE recorded at
each site: when the parked hetero claim lands, consumers conform BY
EMISSION (spell the static side / spelling→print transform / label
rides through casts) — never by inverting.

ADJUDICATION CORRECTION — the needle-arm ledger rows were WRONG: the
contains/in needle arms are not compensation, they are conform-by-
emission already (the needle SPELLS to match the collection's grammar,
byte-comparable — the tenet's own pattern). They KEEP, dormant until a
LITERAL-carried collection reaches membership.

STILL KEPT (correct by design): the selection/sort identity-channel
spellings (runtime-decided kinds — json cannot carry Decimal-vs-Float
or temporal kinds, the pure grammar can; the CARRIER RULE applied:
the spelling is the one carrier with the required property), their
LITERAL marks (the label conduit to egress), Dedup's mark preservation
(a label conduit, not compensation), and the root mixedNumericArray
(already AT the egress boundary).

## THE UNWIND LEDGER (user review 2026-08-24 — executed by the
## relation-cells leg, checked off in its commits)

The test for each artifact: WAS THE RAW VALUE STILL AVAILABLE when we
spelled it? Yes → eager-spelling hack, UNWINDS. No (runtime-decided
kind) → the kind-faithful carrier, the DESIGN, stays.

| Artifact | Verdict |
|---|---|
| unspell + unspellMarked (structural inverse) | DELETE |
| equality harmonization arm (unspell-one-side) | DELETE |
| CastPolicy conformance-cast unspell + TO_VARIANT re-wrap | DELETE |
| format's decomposition unspell | DELETE |
| dormant contains/in needle spelling arms | DELETE |
| eager hetero-literal spelling (the claim mechanics) | REPLACED by egress-side spelling from typed info |
| Lowerer.relationDepth + try/finally + MUTABLE_FIELD_ALLOWLIST row | DELETE — the boundary IS the lane; nobody needs a position counter (the mutable-state guardrail flagged this correctly and the allowlist justification was a rationalization) |
| LITERAL label + LiteralSpelling/LiteralText + label-driven decode + sniff-stop pin | KEEP — the design |
| selection/sort identity-channel spellings (runtime-decided kinds) | KEEP — the genuine carrier; made mixedSort pass |
| selection markers (Cast LITERAL on select/sort results) | KEEP minimal — the label conduit to egress |
| engine-text marker transparency | KEEP (golden-pinned) |
