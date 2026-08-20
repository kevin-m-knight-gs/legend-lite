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
   `test=<fqn>` via StampCensus.CONTEXT) — the (a)/(b)/(c) split needs
   per-witness provenance, which is the next slice's first step.
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
