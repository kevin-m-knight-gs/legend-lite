# BIND-ONCE TYPING — HANDOFF (2026-08-31, session end)

**READ THIS WHOLE DOCUMENT BEFORE WRITING ANY CODE. Homework first:
answer §3 with receipts before touching the typer.** This is the next
leg of the harness-deletion program (parent charter:
`WHOLETEST_COMPILATION_CHARTER.md`; blueprint:
`EMBEDDED_UNION_NAV_HANDOFF_2026_08_31.md` §7 item 1).

## 1. The assignment

The whole-test flip is DEFAULT-ON (417 tests platform-scored; ratchet
pinned fallbacks ≤2,155 shrink-only / flipped ≥418 grows-only, runner
lane guards). The biggest burnable fallback bucket (~430 wall-type
rows, `target/wholetest-flip-fallbacks.txt` after any sweep) is ONE
platform gap in several costumes: statements typed in isolation lose
what `let` bound when the bound thing is a DEFERRED expression —
its meaning decided at the USE site, not the binding site:

| costume | census rows | witness error |
|---|---|---|
| let-bound column specs | ~130 (`~firstName`/`~id`/`~name`/`~tradeId`…) | "mapped/aggregate column specifications need an enclosing call to type against" |
| let-bound bare lambdas | ~30 | "a bare lambda has no type outside a call position" |
| let-bound mapping/runtime refs | ~10 | "from() argument must be a mapping or runtime reference, got TypedVariable" |
| FunctionDefinition-shaped args | ~70 | "class meta::pure::metamodel::function::FunctionDefinition has no property …" |
| TDG inline-args strictness | ~32 | "generateTestData needs its query lambda and mapping reference INLINE" |
| long tail (Any-through-let overload misses) | ~150 | various |

Fix = BIND-ONCE: the typer's cross-statement symbol table carries,
for deferred kinds ONLY, the bound EXPRESSION (not just a type); a
consuming call types through the name as if the expression were
inline. Evaluation semantics unchanged — the rhs still evaluates
exactly once in statement order; only the CHECKER looks through.

## 2. Critical prior art — do NOT build a second mechanism

The platform ALREADY carries let expressions in two places. The leg
is probably an EXTENSION of one of them, not new machinery
(one-router-one-evaluator; the resolver leg's lesson — a "missing
feature" often has a half-built owner):

1. `StatementExecutor.ExecEnv.queryLets` — "run-scoped accumulator of
   inliner-consumed lets … every resolver seeds its let env from here
   (engine inScopeVars)". The inliner (`UserCallInliner`) consumes
   SOME lets already.
2. `SpecCompiler.typeQueryBody` — types the multi-statement body; find
   where a `TypedLet`'s value is (or isn't) visible to later
   statements' call typing.

## 2.5 The design shape (settled in conversation 2026-08-31 — verify
## §3's receipts against it, don't re-derive from scratch)

WHY these lets differ from the ones already handled — it's a PHASE
fact: the witness errors are TypeInferenceExceptions thrown while
typing the BINDING STATEMENT ITSELF. The checker works bottom-up; to
produce a TypedLet it must type the rhs first, and a colspec/bare
lambda/mapping ref has no type in isolation — so typing dies at the
`let`, and the existing machinery (queryLets/UserCallInliner), which
only ever sees successfully TYPED statements, never runs. The
existing lets that work are (a) ordinary values, where the variable's
TYPE is all a later statement needs, and (b) query-position lets the
inliner relocates AFTER typing succeeded.

The walk got away with these shapes via `EngineTestExecutor.subst()`
— raw AST splice of let values into consumers BEFORE typing (the
harness's third-implementation trick this program deletes). BIND-ONCE
is that trick at the right layer: the TYPER, on a let whose rhs is a
deferred kind, PARKS the raw expression under the variable's name
instead of failing, and resolves it at the consuming call site where
the signature supplies the context. Evaluation semantics untouched:
the rhs still evaluates exactly once, in statement order — only
checker visibility changes. The genuinely new code is the
defer-instead-of-fail decision for a CLOSED list of expression kinds;
parking/consuming plumbing should reuse the inliner/queryLets owners
(homework §3.2 maps them first).

## 3. HOMEWORK (answer ALL with receipts before coding)

1. How does the ENGINE type these shapes? (Its compiler resolves
   let-bound colspecs/lambdas at use sites — find the mechanism name
   and semantics in the local checkouts; "inScopeVars" is the thread
   to pull.)
2. Map the platform's existing let-visibility: what does
   typeQueryBody/UserCallInliner/queryLets already look through, and
   where exactly does each witness error throw? (One file:line per
   costume.)
3. Census the CONSUMPTION SITES the ~430 need (project/filter/from/
   generateTestData/…): which call signatures must learn to type
   through a variable? Counted, from the fallback census witnesses.
4. Decide the deferred-kind set PRECISELY (colspec, lambda literal,
   mapping ref, runtime ref, …?) and the aliasing rules: reassignment,
   one binding consumed by two differently-typed calls (a lambda used
   against two element types — does the engine allow it? receipts).
5. Only then: one mechanism per sweep, ratchet + corpus byte-stability
   + disagree=0 as containment; the fallback census is the scoreboard
   (every burned site moves the pin down in the same commit).

## 4. Standing rules (unchanged from the parent program)

One catalog/no silhouette dispatch; no host evaluation of pure; no
hacks/shortcuts/fallbacks; burn = fixed not documented; one gate chain
per batch (`tools/allgates.sh`, roots at /Users/neemsandv/legend);
push after green batches; tree frozen during gates; measure before
claiming (this session's charter-number correction is the standing
witness); if a regression survives two bisections, STOP and revert.

## 5. Parked adjacent lanes (do not absorb into this leg)

text-policy 1,545 (item-4 byte-parity owns it; report if stalled —
user ruling); grid-canon 13 (V8/X6 ULP program); effectful 65
(cutover confidence); wall-exec no-scalar-lowering 327-family and
TypedMap 120 (separate execution legs — bind-once may shrink their
denominators, re-census after landing); walk-arm deletions (start
when their feeding buckets empty).
