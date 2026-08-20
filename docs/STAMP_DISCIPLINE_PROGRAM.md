# MULTIPLICITY-STAMP DISCIPLINE PROGRAM

Commissioned 2026-08-20 (host-logic audit follow-on; user: "go - build the
stamp instrument and census first"). Root defect, in ListShapes' own words:
*"pure multiplicity stamps are unreliable after substitution — many-stamped
reads stay scalar; values readers return lists from to-one-stamped
subqueries"* — which forced runtime SHAPE-SNIFFING (ListShapes, numList,
~29 isToOne arms) instead of trusting static types. The program: make the
stamp always true, make count-changes explicit emissions, enforce with a
post-lowering invariant, then delete the sniffing.

## The instrument

`lowering/StampCensus` — hooked at the ONE scalar funnel
(`Lowerer.scalar`), env-gated `LL_STAMP_COUNT=1`, measurement only.
Reports only PROVABLE mismatches:
- `ONE-STAMP/LIST-SHAPE` — scalar-stamped (`upper <= 1`) expression whose
  SQL is definitely a list (ArrayLit / list-producing call / list-valued
  subquery / array cast).
- `MANY-STAMP/SCALAR-SHAPE` — many-stamped expression whose SQL is a
  definite scalar (literal or scalar cast).
- `VAR-STAMP-AT-LOWERING` — a multiplicity VARIABLE surviving to lowering
  (lowering should only ever see concrete bounds).

`NullLit` is excluded BY DESIGN: SQL NULL is the designed carrier for
pure `[]` in scalar AND list positions — the first census run counted the
convention itself 62,460 times before the exclusion (instrument
false-positive, fixed same day; the lesson is recorded here so the
exclusion is never "simplified" away).

Absence of a line is NOT proof of health (unknowable shapes — column
reads, opaque calls — are never reported); presence IS proof of a lie.

## Census (2026-08-20, full corpus @ real roots + all five PCT suites)

1,021 corpus + 304 PCT provable events; both runs GREEN (instrument is
behavior-neutral). Five cause-classes:

| # | Class | Events | Witness rows |
|---|---|---|---|
| C1 | Collection literal stamped `[1]`/`[0..1]` lowers to ArrayLit (TypedCollection 861, TypedCast 200, TypedNewInstance 5) | ~1,066 | the slice-2 "singleton-list-literal" class residue |
| C2 | `toOne()` stamped `[1]` rides a definite list through (ScalarSubquery 127, Call 54) | 181 | the documented "to-one-stamped subqueries return lists" |
| C3 | value-collection `sort`/`distinct`/`reverse`/`list` one-stamped but list-valued (TypedSort 12, TypedDistinct 20, list/reverse calls 7) | 39 | same family, different constructors |
| C4 | multiplicity VARIABLES at lowering (`y`, `acc`, `_i1/_i2/_i5`) | 37 (PCT only) | generic instantiation fails to bind multiplicity vars |
| C5 | many-stamped property read provably scalar (`u_map__active`) | 1 | the documented "many-stamped reads stay scalar" |

Unique patterns: ~13 corpus + ~13 PCT — the worklist is FINITE and small.

## Fix order (each slice: fix cause-class → census shrinks → gates → pin)

1. **C4 — CLOSED (2026-08-20, one line)**: the lambda-parameter seam
   resolved the TYPE through the kernel binding but took the
   MULTIPLICITY raw from the signature (Typer lambda-scope build) —
   `Var("m")` flowed into scope for sort comparators and fold
   accumulators. Fix: resolve through the binding when bound (a var
   still OPEN there stays, and the census keeps counting it). Census
   37 → 0; core 4166/0; all five PCT suites unchanged. PCT census
   304 → 267 (all remaining = C1/C3).
2. **C2 — MEASURED NEGATIVE RESULT (2026-08-20), redesigned**: the
   blanket toOne unwrap (`list[1]` when the operand is definitely
   list-shaped) was built and run against the FULL corpus: functions
   referee byte-identical, but milestoning −16 / union −23. Diagnosis:
   the ScalarSubquery witnesses are values-reader lists whose FULL list
   downstream consumes — the `[1]` stamp is the lie, not the shape, and
   many of those `toOne`s are resolver-SYNTHESIZED conformance markers
   (the conform-by-emission family), not user value-ops. REVERTED; the
   correct C2 decomposes by PROVENANCE (the TypedJoin.userCondition
   pattern):
   - (a) USER-written toOne over a genuinely-single sloppy shape →
     explicit unwrap at the seam;
   - (b) SYNTHESIZED conformance toOne → ride-through BY DESIGN, marked,
     and the census stops counting it as a lie;
   - (c) values-reader subquery stamps → fixed at their PRODUCER
     (the stamp becomes `[*]`), which is the real C2 residue.
   Attribution instrument upgraded first (census lines carry
   `test=<fqn>` via StampCensus.CONTEXT); witness verified: the biggest
   C2 test (testSimpleTypeMappingProject, 18 events) has NO user toOne —
   all synthesized. SIZED (2026-08-20): the provenance mechanism is a
   `Pure.Lite.conformToOne` internal native (provenance = FQN, the
   internal-natives partition governs it; zero field churn) — but
   synthesized toOnes come from TWO layers (17 protocol-level
   `AppliedFunction("toOne", ...)` sites in the normalizer's
   UnionSynthesis + the typed-level GraphEmission/JsonSourceFrame
   family) and 89 recognizer sites reference the toOne name/FQN and
   need the audit for whether they must also match conformToOne
   (peelers yes, others per-site). A full slice with full-corpus
   verification — NOT a quick tail (the blanket-unwrap regression is
   the caution).
3. **C3** (same seam family): value-collection `sort`/`distinct`
   one-stamped lowerings — adjudicate with the same provenance split.
4. **C1** (biggest, mostly mechanical): scalar-stamped collection
   literals lower to their element (singleton) or the designed empty —
   the 29 consumer-side isToOne guards then start deleting.
5. **C5**: the one many-stamped scalar read — fix at its resolver site.
6. **Flip the instrument to an INVARIANT**: census == 0 on full sweep →
   `LL_STAMP_COUNT` check becomes a failing gate; then DELETE the
   shape-sniffing (ListShapes' runtime arms) as consumers stop needing it.

Everything downstream (the canonical-render/byte-compare verdict design,
HOST_LOGIC_AUDIT fix queue items 3-4) builds on stamps being
enforced-true; it stays parked until the census is zero.

## Guard-emission probe (2026-08-20, both backends — CLEARS the checked toOne emission)

P1 filter-before-projection, P2 dead-branch constant-fold, P3 CASE
per-row laziness, P4 AND short-circuit, P5 subquery under pushdown: NO
spurious guard firings on DuckDB or H2. Must-fire: DuckDB `error(...)`
raises with the message under the standard prefix; H2 via
`CREATE ALIAS RAISE_ERROR` (source-code alias; enabled by default per
H2_BACKEND.md) raises with the message embedded and as the cause.
Emission design: ONE semantic node (checked narrowing) with per-dialect
spellings; engine-text channel renders the inner value (engine-verbatim
noOp view, the NULLS-suppression precedent). ORDERING (the blanket-
unwrap lesson): the guard lands ONLY AFTER synthesized conformance
toOnes are replaced and producer stamps honest — guarding a fake toOne
whose list downstream consumes regresses identically to the revert.

DISCOVERED FORK to rule on separately (out of C2's census scope): toOne
over an EMPTY scalar carrier ([0..1] → SQL NULL). Pure throws ("size
0"); engine's processNoOp lets NULL flow, and the corpus (drop-in
surface) pins NULL-flow behavior broadly. The census never counted this
(NullLit is the designed empty carrier). Needs its own adjudication —
upstream interpreted-vs-relational family, same as index-base.

## C2 emitter map (2026-08-20, fingerprinted via arg0 digests + SQL dumps)

- dataType/projection cell reads: `toOne(prop)` over `SELECT LIST(col)
  FROM (filtered scan)` — single by DATA (filter on a unique key), never
  provable. KEY INSIGHT: dropping the LIST agg yields SQL's NATIVE
  scalar-subquery semantics — >1 row raises, 1 row yields the value,
  0 rows NULL — i.e. the DB-native form IS pure's checked toOne for
  subquery operands, no error() emission needed (message parity vs
  pure's "Cannot cast..." wording is the only residue).
- union `toOne(x_pk/y_pk/...)`: per-member PK collects whose LIST the
  merge genuinely consumes — the agg-strip would raise at runtime here;
  the union synthesis must become an honest [*] flow + explicit merge
  BEFORE the strip can be unconditional. ONE LEG, sequenced:
  synthesis-fix then agg-strip.
- Typer TDS-getter desugars (5 sites): STAMPS ARE HONEST (toOne sits
  inside an isEmpty-guarded branch); their shape rides the same
  LIST-collect and heals with the strip.
- GraphEmission.toOneJoinEquals: a TYPE-LIE used as a lowering
  side-channel (make [0..1]==[0..1] verbatim) — replace with the honest
  mechanism that already exists (NullSemantics.enterVerbatimEquality /
  the TypedJoin.userCondition route).
- C2-i landed meanwhile: provably-single sources (LIMIT≤1 chains, Dual)
  lower cell reads as plain scalar subqueries (Fold.provablySingleRow).
