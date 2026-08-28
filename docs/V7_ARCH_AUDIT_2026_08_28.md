# V7 architecture audit — assert-to-DB, the whole arc (2026-08-28)

User-requested deep audit of the program from the §8 handoff through
leg 1 + consolidation + vocabulary adoption (..45acce60). Question
under audit: **architectural vs bespoke — is the design right for the
whole feature?** Receipts from code, not memory; plain language with
project terms mapped.

## Verdict

The load-bearing skeleton is genuinely architectural and matches the
user-designed fused end state. The verdict layer above it holds its
invariants BY CONVENTION in five places where it should hold them BY
STRUCTURE; one of those five is the same failure class that produced
the reverted attempt's 28-row phantom, currently prevented by
discipline only. Nothing blocks the next legs; two debts should be
paid before the resolver leg triples the arm count.

## Architectural (with receipts)

1. **One spelling grammar** — `LiteralSpelling` owns every canonical
   spelling; scalar sides, TDS cells, and inlined goldens meet in
   identical bytes. Receipt: the grid leg needed ZERO new comparison
   rules for matching cells, and the abstract-Date `%`-prefix bug was
   a one-line fix inherited by every consumer.
2. **One canon pipeline, one choke point** — wrap
   (`CanonicalRenderSql`) → immutable carrier frame (`CanonRider`) →
   ONE `getString` harvest with frame-derived position → byte
   compare. Receipt: the JDBC accessor ratchet stands at 13 with NO
   bump after the whole grid feature.
3. **Static dispatch** — grid-ness, wrap mode, fetch order, and the
   cells-vs-whole-TDS refusal all decide from DECLARED types before
   execution (the ratified no-runtime-sniffing rule). The one runtime
   dependency found (fetch order) was converted to static same-day.
4. **The migration is an instrument** — byte-identical scoreboard,
   named counted declines, dual-verdict alarm. Receipt: 22 real
   issues caught by the alarm in one leg (19 spelling divergences +
   fetch order + enum/sentinel).
5. **End-state alignment is proven** — the landed grid-canon SQL is
   the fusion spike's hand-written expression; golden inlining,
   evaluate-once, JSON emission were spike-verified before compiler
   code.

## The five debts (bespoke where structure belongs)

- **D1 — verdict/message/probe coupling is a copied pattern.** The
  invariant that killed the reverted attempt ("message, probe, and
  verdict derive from ONE judgment") lives as 5 hand-copies (5
  `probeSqlVerdict` sites, 5 matching message tails). Fix: one
  `Verdict{hostHeld, byteHeld, detail}` record + ONE finisher owning
  probe + message + outcome. **Do before the resolver leg.**
- **D2 — wrap/verdict split re-derives one decision.** The scalar
  byte channel's ~150 gate lines run at verdict time over facts known
  at wrap time (the audit-rederivation smell). Fix direction: the
  wrap emits a complete CLAIM DESCRIPTOR; the verdict layer executes
  it. The descriptor is also fusion's emission input. Own slice,
  pre-cutover.
- **D3 — the deliberate harness mirror.** Order views, render
  recognition, and flat-cells detection exist twice (harness = the
  verdict of record, production = the target) — chartered for the
  dual-referee period, alarmed by the outer census, justified ONLY by
  the cutover deleting the harness copy. Action: pin the deletion
  list (checkAssert lattice + harness endsInSort/render recognizers)
  as a cutover obligation in the batch-3 acceptance.
- **D4 — decline reasons are ~20 free strings.** The burn-down keys
  on their spellings; a rewording silently splits a census class.
  Fix: a small reason registry; batch into any next slice.
- **D5 — the rendered-text arm dispatches on SYNTAX.** `renderForm`
  recognizes source spellings (toCSV/replace/sep-join) — semantically
  defensible (the spelling IS the semantics for text asserts) but the
  one surviving pattern-matcher, uncensused for coverage. Minimum: a
  recognized-form census; real fix: fold into D2's claim descriptor.

## Recommendation (ordering awaiting user ratification)

1. D1 now (one finisher, ~a day, hardens everything after);
2. D4 opportunistically;
3. D2+D5 as one claim-descriptor slice before cutover;
4. D3 pinned into the batch-3 acceptance criteria.

The two temporary layers (per-arm host judging, the harness mirror)
both have enforced shrink paths — the size ledgers and the cutover.
The program is NOT building a parallel middle system: the
canon/spelling/carrier layers are end-state code that fusion consumes
directly.
