# F10 PROPER — the kind-faithful carrier (spelling-as-tag)

Status: RATIFIED 2026-08-23 (user-reviewed end-to-end plan; this doc is
authoritative for the leg). Executes in four gated slices; each slice
ends with DELETIONS of compensating code — the proof the disease was
fixed upstream rather than patched again downstream.

## 1. The disease (one sentence)

Values whose kinds a single SQL column type cannot hold are encoded
THREE incompatible, lossy ways at construction, and every verdict-lane
compensation (canon strips, mixed-kind gate, tunnel demotions, the last
decline) exists to cope with that damaged wire.

The three encodings today:

| Path | Encoding | Loss |
|---|---|---|
| Mixed Number collection, literal at a root | DOUBLE-promoted array | Integer 1 becomes 1.0 (testMixedSortNoComparator's EXPECTED side; part of the wire census Number-erasure family) |
| Mixed collection through MixedEncoding selections/sort | identity channel = VARCHAR pure PRINT forms under a Number/Date label | label lies (VARCHAR wire under numeric label); decode needs the print-form recovery lane |
| Any-stamped positions | raw variant JSON (to_json) | temporals erase to JSON strings (Date vs String '2014-01-01' = SAME BYTES — engine equal('2014-01-01', %2014-01-01) FALSE is undecidable, DB canon and host referee equally blind); Decimals erase to doubles |

Conflated identity at the root: TWO different things share SqlType.JSON —
genuine **Variant** values (pure value type; contract IS the raw JSON
text; results deliberately not decoded) and the **Any/mixed carrier**
(our internal travel representation). The carrier borrowed Variant's
encoding; that borrowing is the original sin.

## 2. The design

**The kind-tag already exists: pure's literal grammar.** F10 v1 proved
the six spellings are mutually disjoint by construction:

    Integer  1          Float  1.0        Decimal  3.14D
    String   'hello'    Bool   true       Temporal %2014-01-01

A JSON string holding a spelling is unambiguous — `"1"` can only be an
Integer, `"'1'"` only a String, `"%2014-01-01"` only a Date. No
structural {k,v} tagging; the spelling IS the tag.

**The carrier gets its own honest label**: a new logical wire type
`LITERAL` (SqlType). Physical form: JSON (a collection cell = JSON
array of spelling-strings); VARCHAR on H2 (CarrierStrategies caps seam;
the delivery relation registers the physical pair). `JSON` label =
genuine Variant only, untouched. `LITERAL` label = the carrier. This is
the first emitter-written truthful label — a down payment on the T4
label-at-construction program, same direction.

**One owner for the grammar.** Today the spelling knowledge lives in
THREE places that disagree:
- `CanonicalRenderSql.literalCanon` (verdict lane; %-form temporals)
- `MixedEncoding` element ids (execution lane; dates WITHOUT %,
  datetime + '+0000')
- host-side print-form parsing (Executor's "lattice-typed roots recover
  kinds from the identity channel's print forms" lane) + decodeAny

F10 proper collapses encode (SQL-side), decode (host parse), and canon
into ONE module. For a LITERAL column the canon is the IDENTITY — bytes
are already canonical — so the +0000/D-suffix strips retire and
anyJsonCanon's dispatch shrinks.

**What stays.** The comparable channel (DOUBLE/TIMESTAMP ordering)
survives — only the identity channel's text + label change. Trees
(maps, instances) stay declared declines — the grammar deliberately has
no spelling for them (honest counted residue). Homogeneous typed
columns keep native SQL types. Variant semantics untouched. The 5
indexOf/substring rows stay permanent ledger (user ruling 2026-08-23 —
1-based is real core_relational pure semantics; never re-attempt).

## 3. The slices (each: full nine-gate chain, pins moved same-commit)

### Slice 1 — one spelling owner (zero behavior change)
Extract the grammar module (e.g. `lowering/LiteralSpelling`):
SQL-side spelling builders per kind + the host-side parser. literalCanon
and MixedEncoding delegate. Where their texts DIVERGE today (temporals:
%-form vs plain print, +0000) the module exposes the two named tables
(LITERAL grammar vs PRINT form) and callers keep byte-identical
behavior — divergences documented at the site, resolved in later
slices, never silently merged in this one. Gates prove nothing moved.

### Slice 2 — mixed collections ride LITERAL
- Add SqlType `LITERAL` + Executor decode arm (parse spellings; JSON
  array cell → element list) + census registration (LITERAL↔JSON /
  LITERAL↔VARCHAR delivery pairs).
- The two mixed egresses switch: (a) mixed literal collections at
  statement roots (today DOUBLE-promoted), (b) MixedEncoding
  selection/sort results (today VARCHAR print forms). Labels tell the
  truth.
- wrapWithCanon: LITERAL columns → literal-only channel, canon =
  identity (cell bytes).
- DELETE: AssertVerdicts mixedNumericKinds gate (the routing fact is
  dead once the carrier is trustworthy).
- Payoffs: testMixedSortNoComparator FLIPS TO PASS (both sides
  kind-faithful — the expected side stops corrupting 1 into 1.0);
  declines 1 → 0; mixed rows of the Number-erasure wire family
  re-bucket.

### Slice 3a — the temporal grammar (SHIPPED b1cfdd97)
Both halves precision-faithful (%-spellings + PureDateLiteral arm +
exact pure-unescape); AUDIT A1 fixed (subsecond strip deleted — probed
no-op on TIMESTAMP, wrong on text); numericOnly gate dissolved;
LiteralText relocated to values (Invariant 6a).

### Slice 3b — LANDED (M4 re-land 2026-08-25, on the typed IR, ZERO
### compensations)

The hetero-LITERAL claim is LIVE on main: value-lane Any-LUB
all-spellable collections ride Array(LITERAL) (Lowerer claim arm);
rowMajorCellList spells by static column kind; equality conforms by
emission (MixedEncoding.equalityEmission, stored-fact gated);
print consumers ride ONE recipe (LiteralSpelling.printForm — the
pureToString Any arm, format's slots via
MixedEncoding.printedFormatSlots, and PureSql.elementText's makeString
lane, which CURED the three residual rows: testSqlRealiasViews,
testViewAll, testViewSimpleFilter). The four compensations were
replaced structurally: LambdaWire -> LambdaBinding's unary+comparator
binding conventions at the ONE arg-lowering site (signature-keyed by
the rule table's own dispatch identity); per-arg judge loop -> stored
type facts; comparator FQN registry -> Scalars.COMPARATOR_NATIVES
beside the rules + Dedup's construction-site stamps; judge patches ->
SqlTyping's uniform() ERROR skip. decodeAnyPrecision HEALED (Decimal
exact through Any — probe flipped); the SqlTyping.admissible
collection-carrier row (L <- Array(L)) retired by measurement
(mismatch stayed 0 without it); the scalarRoot LITERAL-label arm
STAYS — it is the claim's label seam reading the stored fact (full
retirement = the T4 label-at-construction program). Corpus
scoreboard byte-identical; PCT 1115/0; untyped 717 -> 424 (corpus
lane) and 813 -> 808 (pct lane).

### Slice 3b — original census (the engine-true win)

CENSUS (2026-08-23, 24 sites). PRODUCERS to migrate — each spells by
its STATICALLY-KNOWN kind: hetero literal collections (Lowerer ~2313,
all-spellable elements → literal ArrayLit + Array(LITERAL) marker;
enum/instance elements keep JSON — enums have NO disjoint spelling),
concatenate's harmonization (Scalars ~1836: LIST_TRANSFORM per-arg
elemKind — this gives COMPUTED mixed collections the carrier claim,
closing the verdict gate's computed-mixed residue), lubCase branches,
Any-root boxing (spell by judged wire kind), contains/in needle wraps
(Scalars ~2112/2404 — spell the needle iff the collection is
LITERAL-carried), Pair Any-slots (Lowerer ~3053 — DEFERRED: feeds the
keyed-canon field layout, own mini-slice). CONSUMERS to update:
pureToString's Any arm (spelling → print form IN SQL: unquote strings,
strip %, keep D; the JSON-array print composition follows the cell
kind), CastPolicy.comparisonWireOperand, cast(@T)-over-Any (spelling
casts directly: CAST('1.0' AS DOUBLE) binds; temporals via the %-strip
+ temporal cast), decode (label-driven, already total), canon (LITERAL
branch already short-circuits). Relation-lane Any TDS cells (Lowerer
~2441 VARIANT_GET navigation) stay JSON — separately chartered.

LESSON (2026-08-23, built-and-reverted same day): the hetero-literal
claim LEAKED INTO THE RELATION LANE through the shared scalar() arm —
4 corpus regressions (spelled strings into JSON-cast consumers;
relation cells decoded through the wrong carrier). The claim needs an
explicit LANE FLAG (value lane vs relation lane) before it returns.
What SHIPPED as 3b groundwork: the TOTAL two-carrier readers
(first-char disjoint — decodeAny + pureToString), elementLiteral, the
carrier-marked in/contains/dedup consumer arms (dormant until the
claim returns), CarrierStrategies marker transparency, element-
preserving label flow (unconditional root judgment).

LESSON 2 (same day, second witness set): even VALUE-LANE gated, the
claim broke the corpus GRID-ASSERT equality lane — a spelled expected
literal meets `$result.values.rows.values` through a conformance
cast-to-Any (CastPolicy Array(JSON)) and cross-carrier `==`. The claim
is DESIGN-BLOCKED on the cross-carrier EQUALITY harmonization, which
CONVERGES with the audit's in-SQL equal() third-lane finding (A2):
3b-proper = {the claim + eq-lane harmonization + A2's three-lane
pinning} as ONE piece. Everything else (readers, lane flag, consumer
arms) is landed and ready.

RESOLUTION MAP (2026-08-24, third parking — now COMPLETE): the
harmonization arms LANDED and are correct (equality unspell-one-side;
conformance cast-to-Any unspell + per-element TO_VARIANT re-wrap —
reproduces the exact pre-carrier shape). Under the claim, PCT is CLEAN.
The ONE remaining blocker is an ADJUDICATION, not a design gap: corpus
GRID-EXTRACTION asserts (rows.values == [literal]) follow the ENGINE'S
TEXT-COMPARE convention — they only ever passed because both sides
erased to text; the typed-faithful carrier honestly exposes '4' != 4
(witness testInWithDynaFunction: engine expects [false, '4'] to equal
[Long 4, 'false'] — true only textually). PROPOSED RULING for user
review: the corpus referee's grid-extraction family compares in the
RENDER channel (the engine's own convention, already computed — the
referee prints "renders equal, comparison differs"); typed compare
stays everywhere else. On ratification the claim un-parks and slice 3
completes.

### Relation-cells leg — RESOLVED by the CARRIER RULE (2026-08-24)

Final state after a same-day build-measure-REVERSE cycle (user ruling):

**THE CARRIER RULE (ratified):** pick a pure collection's SQL carrier by
the CONTRACT'S REQUIRED PROPERTIES, never by which consumer complained.
rows.values is semantically an ORDERED LIST (tds.pure:79 values:Any[*],
row-major, TDSNull slots); the carrier with those properties is the
QUERY (rows = order, column types = kinds, NULL cells = slots) — the
grid it already had. The array-value carrier is for scalar-position
literal collections only.

What was built and REVERTED same day (gate-caught, correct-oracle run):
the Typer TypedMap flatten re-carried the read as a SQL LIST value —
no order guarantee (LIST() aggregate), no kinds (variant re-wrap), no
slot convention — and EVERY consumer needed carrier compensation
(equality, membership, print, instanceOf(TDSNull), ordering,
temporals): an N×M coupling treadmill. Reverted to identity; consumers
map onto the grid (at(k)/size() row-major arithmetic — cluster 33;
grid-vs-list asserts — the harness eval's row-major cell walk).

What SHIPPED and stays (each independently right, all gated):
- `^TDSNull()` types as a real INSTANCE [1] via NewChecker; the BARE
  reference keeps the sqlNull() Nil[0] funnel (presence test); the
  instance lowers to the SQL NULL literal — comparisons unchanged.
- TypedCollection.rowCells — the row-cells fact is CONSTRUCTION-
  DECLARED by the Typer's synthesis; the isRowCells shape-matcher is
  DELETED (label at construction, never sniff at consumption).
- MixedEncoding.variantElement + unwrapVariant — ONE owner for the
  variant wrap and its inverse; the six scattered TO_VARIANT shape-
  peelers now ask the owner.
- Executor wire-presence egress (Cell record, one fetch): on the
  variant lane a wire NULL decays (empty), a present cell decoding to
  null is a kept host-null slot; PureAsserts' direction-aware sentinel
  equivalence adjudicates.
- accessProperty split (tdsValuesRead) — G1 seam.

MEASURED OUTCOME on the reverted tree: BOTH original witnesses
(testInWithDynaFunction, testCompositionInExtend) PASS — the kept work
plus the eval's existing row-major walk close them; the value-
collection flatten was never necessary for its own witnesses. All
regressed families back at baseline (projection 146/155, inner-join
31/32, tds/tests 253/266).

DEFERRED (PROGRAM_MAP): corpus asserts are HOST-SIDE (harness eval
compare) vs PCT's SQL-side verdicts — the corpus→SQL-verdict migration
is an explicit corpus-burndown-phase leg, AFTER PCT reaches 100%; no
incremental drift before then (a half-migrated referee is a fourth
implementation).

### Slice 3 exit (unchanged)
Census TO_VARIANT construction sites first. Any-position ELEMENT
encoding switches from raw JSON scalars to spellings; decodeAny gains
the carrier arm; canon strips (+0000, D-suffix) DELETE. After this,
temporal/Decimal equality inside Any is byte-decidable:
`"%2014-01-01"` vs `"'2014-01-01'"` — different bytes, decidably
unequal, exactly pure's answer. Scope guard: a genuine Variant nested
in an Any position keeps raw JSON (no literal spelling exists — tree
territory, declared).

### Slice 4 — consumer decay (proof-of-cure sweep)
F16 adapter kind re-derivation shrinks (register predicts this);
SqlTypeCensus special cases re-adjudicate; print-form recovery lane
retires where the LITERAL decode replaced it; relation-lane variant
cells if separately chartered.

## 4. Wire facts already probed (DuckDB, 2026-08-23)
- JSON preserves numeric spelling text: '5.0'::JSON stays `5.0`
  (json_type DOUBLE), '1'::JSON stays `1` (UBIGINT); `[to_json(1),
  to_json(5.0)]` = JSON[] `[1, 5.0]`.
- decodeAny already discriminates Long-then-Double by text form.
- wrapWithCanon's jsonCol branch already routes JSON columns
  literal-only (its comment names Number-stamped mixed lists as the
  intended witness).

## 5. Open adjudications (settle at the slice, record here)
- Carrier temporal spelling = %-form (LITERAL grammar); print forms
  (toString) stay plain — one module, two named tables, never mixed.
- H2 physical carrier = VARCHAR via CarrierStrategies caps; delivery
  relation records the pair.
- Guardrail expectations: carrier-purity ratchet movements at slice 2
  (new emission sites are the SEMANTIC carrier — justify same-commit);
  SpellingsTest CODED registrations for any new SqlFn; decline/diverge
  ceilings BANK DOWN as families burn.

## 6. Non-goals
- Store-read DECIMAL(18,6)-under-DOUBLE and HUGEINT adopt-pending: the
  label-at-construction BUILDER leg (T4 territory), not carrier work.
- Prepared statements: later program (text-lane perturbation).
- StrictTime/Byte: no boundary SQL type today; unchanged.
