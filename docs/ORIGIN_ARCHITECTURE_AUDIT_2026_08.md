# ORIGIN-MACHINERY ARCHITECTURE AUDIT (2026-08-29)

Scope: the identifier-ORIGIN work landed 92174a4e → de1a58f6 (the
convergence slices 1-3). Landed GREEN on all gates — this audit asks
the question gates cannot: is each mechanism ARCHITECTURAL (fact
stamped at construction, spent at consumption) or a COMPENSATION
(re-derived, reconciled, guessed)? The audit form is the standing one
(audit-rederivation-question): per mechanism, "does it re-derive at
consumption what construction knew?" User challenge that triggered
this: "sounds like we hacked instead of implemented architecturally."

Verdict up front: the SPINE is sound; the JOIN/UNION stamping is a
construct-then-reconcile patch with a name-string lookup and a
kind-guess fallback — three instances of one disease, whose receipt
is the 2-row union residue (h2 floor 1367 vs 1369). The remediation
DELETES all three mechanisms rather than repairing them.

## The mechanism table

| # | mechanism | where | verdict |
|---|---|---|---|
| 1 | `OutputCol.Origin` stamped at birth; 3 PHYSICAL doors (table scan / sourceUrl / rawSql) explicit; convenience ctors DERIVED by contract | OutputCol, Lowerer.outputsOf doors | **SOUND** — fact at construction |
| 2 | `Column.of(table, OutputCol)` inherits origin; `derived()`/`physical()` explicit doors; raw ctors deleted from production; guard pin 7 shrink-only | SqlExpr.Column, CodeShapeGuardrailTest | **SOUND** — totality compiler-enforced |
| 3 | Renderer spends the fact: DERIVED → aliasIdent (unconditional quote, the engine's own convention), PHYSICAL → bare-unless-special, origin-less → WALL | H2.columnName/aliasIdent | **SOUND** — consumption reads, never derives; failure is loud |
| 4 | `outputsOf(info)` builds outputs from the TYPE SCHEMA, join builders then run `stampJoinOrigins` to CORRECT the blanket origin | Lowerer.outputsOf + Fold.stampJoinOrigins | **RECONCILIATION** — construct-then-patch. The projection list assembled in the same method knows every answer positionally; we derive outputs from a source that cannot know (the schema) and bolt on a repair pass |
| 5 | `stampJoinOrigins`' side-inheritance: match output NAME against left/right source outputs by string | Fold.stampJoinOrigins | **RE-DERIVATION** — consumption re-discovers by name what construction knew by position. Receipt: union branches suffix names (`ID_0`/`ID_1`), the lookup loses the thread, 2 tests stamped wrong |
| 6 | `starSideOrigin` fallback: guess origin from the starred side's KIND when the name lookup misses | Fold.starSideOrigin | **FALLBACK-GUESS** — a fallback that exists because #5 can miss; the residue generator by our own standards |
| 7 | Union outputs: `outputsOf(c.info())` blanket-DERIVED, no branch inheritance | Lowerer.union | **GAP** — the union layer never got the reconciliation the join layer got, which is where #5's misses originate |
| 8 | Renderer pairs `projections()` with `outputs()` POSITIONALLY to label alias-less projections; a STAR desynchronizes the indices so a guard disables the mechanism per-select | AnsiSqlRenderer.select + H2.projection | **COUPLING** — two parallel lists that must agree by index (the star syntax-error bug was this coupling's first bite); `withProjections(ps, outs)` (70 call sites) makes every builder responsible for keeping them aligned by hand |
| 9 | Prefixed slot columns (`c.name()+"_"+sc.name()`) hardcoded DERIVED inside outputsOf's schema walk | Lowerer.outputsOf | ACCEPTABLE — the prefix IS an invented name; but it lives in the wrong owner (see remediation) |

Mechanisms 4-8 are one disease: **outputs are constructed from the
wrong source of truth.** The schema knows types; only the PROJECTIONS
know spelling provenance. Everything downstream of that wrong choice
is compensation.

## Remediation — outputs derive FROM projections (deletes, not repairs)

**The rule: a select's `OutputCol` list is BUILT from its projection
list at construction.** Each projection either carries the OutputCol
it passes through (star/column passthrough → the source's OutputCol,
origin and all) or mints a fresh one (aliased/computed → DERIVED,
type from the expression's TypeFact). `outputsOf(info)` remains only
for frames whose outputs genuinely are schema-born — the three
PHYSICAL doors and TDS/values literals.

Deleted outright by the rule:
- `Fold.stampJoinOrigins` (#4, #5) — outputs are right at birth.
- `Fold.starSideOrigin` (#6) — no miss path exists.
- The union gap (#7) — union outputs = merge of branch outputs,
  per-name, inheriting branch origins; same constructor rule.
- The renderer's positional pairing (#8) — a projection that KNOWS
  its OutputCol needs no index arithmetic; the star guard survives
  only as "a star projection carries the source's whole list".

Mechanically: a `Projection` gains its declared `OutputCol` (or the
builder API takes pairs), `withProjections(ps, outs)` collapses to
`withProjections(pairs)` at the ~70 call sites — most of which
already compute both halves side by side today and will get SHORTER.
Expected side effects: the 2 union-wrap residue rows heal (floor
1367 → 1369), and the h2 lane's label story becomes provable by
construction instead of by sweep.

Sequencing: this is the natural FIRST SLICE of the ratified SQL-IR
backend-agnosticism program (the origin fact was slice 1; making the
outputs list honest is slice 2) and should land BEFORE the §4AD
conformance leg builds new join shapes on top of the current
reconciliation.

## Process note (recorded so it binds)

The smells all entered during the 03:00-05:00 fix-probe-fix cycle
under a red chain — the mode that produces reconciliation passes.
The repo's own idiom is a POST-LANDING adversarial audit for exactly
this (F10 §4A precedent); the convergence landing skipped it and the
user caught it in review. Rule going forward: a landing that added
any reconciliation/fallback arm gets its audit pass before the next
leg starts.
