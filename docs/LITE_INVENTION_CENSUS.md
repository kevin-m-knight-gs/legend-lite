# LITE invention census — delete vs product feature

2026-08-14, user directive: "do a sweep of ALL LITE inventions and
categorize all of them as delete vs real product feature." Precedent:
the `}->` island exit (deleted same day — internal uses migrated to the
real `}#->` spelling, token and plumbing removed outright).

**The rule this census enforces:** an engine-lite *invention* — syntax
neither legend-engine nor legend-pure accepts — is never promoted to a
"lite extension". It gets migrated to real syntax and deleted. Only
DELIBERATE product features earn the `LEGEND_LITE` dialect gate.

**Why this list is complete by instrument, not by eyeball:**

- Everything the LITE surface accepts that the ENGINE surface refuses is
  measured by `OwnDialectCensusTest` (946 LITE-accepts vs 892
  ENGINE-accepts over the own-test snippet corpus); every such row must
  live in a whitelisted extension-test host tied to a declared family in
  `OWN_CORPUS_DECISIONS.md`. There is no unclassified residue.
- Inventions accepted on ALL surfaces (the `}->` class — invisible to
  the dialect census by construction) are guarded by the ENGINE-verdict
  differential gates: `MutationFuzzTest` (950 mutants),
  `FixtureCorpusParityTest` (266 sibling sources),
  `AdversarialParityTest`, and the corpus sweep's leniency catalog
  (ceiling 1470, every row classified). `}->` was found by exactly this
  instrument; nothing else of its class is currently detectable.

## KEEP — deliberate product features

| # | construct | gate | why it stays |
|---|---|---|---|
| K1 | Mapping-as-function + inline association (`X: Relational { fn }`, clean-sheet assoc predicate) | `refusesLiteExtensions`, §6+§8 | THE named design family of the mapping redesign; user-endorsed feature legs. One family — rises and falls together. |
| K2 | SQLite backend (`type: SQLite`, `specification: SQLite {}`) | §9 | Real product backend. Spelled exactly on the engine's DuckDB extension pattern so a future engine SQLite extension finds the text conformant. |
| K3 | Function-type literals + generics on declarations | §11 | The engine's own refusal says "not supported **yet**" — reserved-but-unbuilt engine grammar. Lite's type checker implements it end-to-end. Ahead of the engine, not contrary to it. |
| K4 | BigInteger widening of overflowing integer literals | SpecParser | Value REPRESENTATION, not syntax: legend-pure execution semantics (PCT huge-literal reference tests). The spelling is engine-legal; only the value model differs. |
| K5 | CDecimal promotion of precision-losing float literals | SpecParser | Same family as K4 (PCT decimal-exact reference). The `1.5f` suffix itself is engine-real (`CoreFragmentGrammar` has `('f'\|'F')?`). |

## DELETE — inventions (migrate internal uses to real syntax, then remove)

| # | construct | status | migration |
|---|---|---|---|
| D1 | `}->` fused island exit | **DELETED 2026-08-14** | Internal tests migrated to `}#->`; token, lexer arm, parser plumbing removed. |
| D2 | `comparator(a:T[1], b:T[1]): R[1] { body }` inline expression | OPEN | A PRINT-ONLY form: legend-pure's `printFunctionDefinition` emits it for a concrete function, but neither the engine grammar nor pure's own M3 parser reads it back (M3 `atomicExpression` has no inline named definition). Our parse arm exists solely for the PCT adapter's print→reparse round trip. Migration: the adapter prints function-valued arguments as REAL typed lambdas (`{a:T[1], b:T[1] \| body}` satisfies the same `Function<{T[1],T[1]->Boolean[1]}>` signature); then delete the `comparator` keyword, the parse arm, and migrate the two internal test pins. |
| D3 | `BOOLEAN` / `BOOL` relational column types | OPEN — user-ruled | Migrate lite's own corpus/tests to the engine-legal `BIT`; coordinate with the sibling session (model/execution side reads the column type); then delete the dialect-gated arm in `DatabaseProtocolParser`. |
| D4 | Unterminated LAST statement in a multi-statement code block | OPEN — verify then flip | The engine requires the final terminator, and so does REAL legend-pure (M3 `codeBlock: programLine (END_LINE (programLine END_LINE)*)?` — the multi-statement form carries the trailing `END_LINE`). Lite's tolerance is therefore an invention, not pure-compat. Migration: flip the gate to all dialects, fix whatever internal sources break. |

## NOT inventions — correctly tiered, no action

- The PLATFORM tier (`refusesPlatformDialect` gates): `<>`, date `Z`
  suffix, `.allVersionsInRange`, bracket indexing, class/association
  projections, m2 diagram views, single-line `'''`, parse-time date
  validation. These are REAL legend-pure dialect, needed to parse the
  legend-pure corpora; the LITE product surface already refuses every
  one of them.
- Engine-real oddities we reproduce byte-for-byte (probed): `~distinct`
  as a Mongo collection name, `mainCollection` tilde names, the float
  suffix, dotted/mixed store paths in Mongo connections, the doubled
  `_pure_protocol_type`, the nameless DeploymentConfiguration elements.
  These look invented but are the ENGINE's own behavior.

## Standing verification

`OwnDialectCensusTest` (whitelist shrink-only) + `MutationFuzzTest` +
`FixtureCorpusParityTest` + the sweep's leniency ceiling are the four
instruments that keep this census true going forward: a NEW invention
cannot land without going red in at least one of them.
