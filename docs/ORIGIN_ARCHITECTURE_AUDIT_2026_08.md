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

## LANDING RECORD — slice 2 EXECUTED (2026-08-29, ALLGATES GREEN)

Outputs-from-projections landed exactly as chartered; the deletions
are the receipts:

- `SqlSelect.Projection` carries its declared `OutputCol` (3rd
  component). A star projection carries null and expands the starred
  source's whole list — origin, tolerance and join-pad weakening
  included (`SqlSelect.outputsFrom`/`expandStar`, structural descent,
  no name lookup). An explicit projection's attached output normalizes
  to DERIVED at construction: its name is the QUERY's own declaration,
  and the origin-spending renderer labels every such projection
  explicitly from `p.out()` (the engine's own convention).
- A projection frame's `outputs` REBUILD in the canonical constructor
  at every construction — a rebuild can never hold a stale claim
  (residue test 2's exact disease: `starSideOrigin` captured PHYSICAL
  from the raw scan before the left side was isolated into a
  subselect, and the patched list never recomputed). Star FRAMES
  (empty projections) keep caller outputs — source passthrough, the
  three physical doors, TDS/VALUES literals, and the no-prefix join
  frame.
- DELETED: `Fold.stampJoinOrigins` (#4/#5), `Fold.starSideOrigin`
  (#6), the union blanket-DERIVED gap (#7 — `reconcileUnionLabels`
  adopts the FIRST branch's origin per slot: SQL's own
  first-branch-label rule, and branch outputs now derive from branch
  projections so the inherited fact is construction truth), the
  renderer's positional projection↔outputs pairing and its star guard
  (#8 — `AnsiSqlRenderer.select` renders `projection(p)`; H2 reads
  `p.out()`), and `SqlTyping.reconcileLabels` with its star-tail
  shift arm (subsumed by per-slot `reconcileSlot` at pairing time).
- API: `withProjections(ps, outs)` → `withProjections(ps)` +
  `withOutputs` (star frames only, walls on projection frames). The
  ~70 call sites migrated by compiler-driven totality;
  `SqlSelect.paired` zips construction-lockstep lists (loud on size
  mismatch — the old silent-desync bug surfaces at the site),
  `Fold.named` attaches contract slots by name for star-headed
  builders (extend/window/prefix-join — name-keyed at CONSTRUCTION
  against the declared schema, not consumption re-derivation).
  `padJoinOutputs` survives (it owns nullability, not spelling) and
  now transports origin through its weaken. Several sites got
  shorter (Pivots' hand-built keyedOutputs list is gone; the pruners
  no longer maintain parallel lists).

Residue outcome — the audit predicted both named rows heal;
measurement says one, plus three unpredicted:

- testPropertyProjectionQueryWithInnerJoinClassMappingWithMilestoning
  TableFilter — HEALED (probed): the `"t5".name` bare-PHYSICAL read
  spells DERIVED because the wrap's outputs rebuild from its
  `Star(t2)` projection at every rebuild.
- testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter
  — RE-DIAGNOSED, not an origin skew: the Firm union extent projects
  an UNDEMANDED `"t5".name AS "legalName"` whose physical column does
  not exist on the family's session (its own store recreates FirmSet1
  with LegalName); the engine's plan prunes that projection at birth.
  Our prune is blocked by the `"t7".*` star over the union frame
  (`SubselectPrune.pruneUnion` bails on starred aliases). DEMAND/
  PRUNING divergence — a separate leg, recorded, not repaired by
  spelling.
- h2 sweep 1367 → 1372 (floor RATCHETED to 1372): the milestoning row
  plus 3 label-consistency rows healed by explicit `AS "name"`
  labeling now reaching star-bearing frames (the old renderer guard
  disabled labeling for the whole select when any star was present).

Gate receipts: full chain GREEN in 440s (G1 4339/0/0; G4 DuckDB
census byte-identical, exec-passing 1385 / text-only 44 / unable 97 /
csv 117 intact; G5 h2 1372/2575, capability walls 946, seeds ≤ 6;
G6-G9 green). One migration bug was caught by the first chain run and
fixed before landing: the extend builder paired star-headed projection
lists POSITIONALLY (the very coupling this slice deletes) — replaced
with `Fold.named` attachment; 258 red tests → 0.

## FINISH RECORD — slice 2 residuals burned (2026-08-29, same day,
## user-directed: "finish the work", ALLGATES GREEN again)

The landing record above disclosed two structural residuals; both are
now gone, and the door is pinned:

- **Pad truth stamped at the join node**: `SqlSource.Join.outputs()`
  delivers its sides with the padded side weakened to nullable — the
  join is the ONE owner holding both the kind and the sides (§E3 M-N2
  moved home). `Fold.padJoinOutputs` DELETED (the name-keyed repair
  pass over schema-asserted outputs had nothing left to repair); its
  `PAD_FRAME_WEAKENED` counter deleted with it (frame weakening is a
  derived fact now — no construction event exists to count).
- **No frame asserts schema outputs where source truth exists**: the
  no-prefix `joined()` branch and the cross-lateral flatten frame are
  plain `SqlSelect.starOf(join)` — outputs are the source's own,
  origins included. The blanket-DERIVED star-join-frame residual is
  gone.
- **`outputsOf` pinned so it cannot be abused**
  (CodeShapeGuardrailTest.schemaAssertedOutputsOnlyShrink): after the
  burn its every surviving call is a schema DOOR (three physical
  doors, TDS/VALUES literals, Pivot source) or the per-projection
  CONTRACT supplier (`paired`/`named` attachment); `withOutputs` has
  ONE production caller (the dynamic-pivot probe — outputs discovered
  by JDBC probe, genuinely external knowledge). Both counts pinned
  shrink-only (23 / 2). `outputsOf` is not deleted outright because
  the LABEL FLIP doctrine (TYPED_SQL_IR §3/§6) needs the
  pure-contract erasure supplied per slot — deleting it would make
  labels pure wire truth and abandon the subsumption-keeps-contract
  rule; the pin makes the remaining surface closed instead.
- Receipts: full chain GREEN again (~440s); h2 sweep EXACTLY 1372
  (floor held, no lane movement — the derivation flip is
  behavior-preserving where measured); DuckDB census byte-identical.

Still open (unchanged by this finish, separate legs): the
chained-joins DEMAND/PRUNING divergence above, and the pair-native
loop form at the ~9 `paired()` zip sites (construction-lockstep,
loud-on-desync — honest but not terminal).

## Process note (recorded so it binds)

The smells all entered during the 03:00-05:00 fix-probe-fix cycle
under a red chain — the mode that produces reconciliation passes.
The repo's own idiom is a POST-LANDING adversarial audit for exactly
this (F10 §4A precedent); the convergence landing skipped it and the
user caught it in review. Rule going forward: a landing that added
any reconciliation/fallback arm gets its audit pass before the next
leg starts.
