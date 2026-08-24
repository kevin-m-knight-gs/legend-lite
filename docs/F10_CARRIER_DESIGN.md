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

### Slice 3 — Any positions migrate (the engine-true win)
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
