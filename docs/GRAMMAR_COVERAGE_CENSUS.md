# Grammar-Coverage Census — measuring "total"

2026-08-14, bulletproof-and-total program step 1. Every parity claim
quantifies over sources that EXIST in the corpus; this census measures
what fraction of the ENGINE'S OWN GRAMMAR those sources exercise. An
uncovered rule is a grammar path where lite could diverge with every
gate green — the enumerated residue IS the completeness work-list.

Instrument: `GrammarCoverageCensusTest` — TRIGGERED, not scheduled
(2026-08-14 cadence ruling): its inputs are both pinned (corpus
manifest SHA, oracle jar version), so its output is constant between
pin changes. Run it, ratchets enforced, on exactly three triggers:
corpus manifest change, oracle-pin bump, census-code change. It
discovers every
generated `*ParserGrammar` in the pinned oracle jars, maps corpus
sections to their section grammars (ranked prefix match; connection/
authentication value-grammar packages penalized), drives each section
fragment through the ENGINE's own lexer/parser reflectively, and
records every parser rule (and labeled-alternative context class) that
fires. Report: `target/grammar-coverage.tsv`.

## Phase-1 baseline (oracle 4.138.2, corpus 8,891 sources)

- **24 section grammars driven; 1,020 / 2,069 rules covered (49.3%).**
  Ratchets: coverage floor 1020 (up-only), driven-grammar floor 24,
  undriven ceiling 44 (down-only), unmapped-sections ceiling 1.
- Well-covered: Domain 93.8% (152/162), Deephaven 88.9%, Diagram
  88.5%, Elasticsearch 88.0%, Runtime 84.0%, Data/GenerationSpec
  82.4%, ExternalFormat 81.8%, Service 75.6%.
- Thin (the corpus barely exercises them — divergence could hide
  here): MongoDBSchema 13.8%, HostedService 15.0%, FunctionJar 18.5%,
  MemSqlFunction/BigQueryFunction 18.9%, AuthenticationDemo 19.3%,
  ServiceStore 21.9%, Snowflake 23.4%.
- Mid: DataQuality 54.9%, DataSpace 54.7%, Relational 58.4%, Mapping
  61.3%, Persistence 62.0%, Connection 66.7% (section shell only —
  values are islands).
- Unmapped section: `QueryPostProcessor` (17 fragments — no generated
  grammar for it on the oracle classpath).
- **44 grammars discovered but not driven** — island and value
  grammars (graph-fetch tree, connection values, flatdata, navigation,
  …). Phase 1 drives only section-level fragments; these are reparsed
  by the engine from walker-extracted sub-fragments.

## Honest limits (reported, not hidden)

1. Rule-level (+ labeled-alt context classes) granularity — an
   unlabeled alternative inside a covered rule is not separately
   observable from the parse tree.
2. Fragments the engine error-recovers still contribute coverage and
   are tallied under `errFragments`.
3. Coverage of the ENGINE's grammar says nothing about LITE parsing
   the same fragment identically — that is the byte-parity gates' job;
   the census only bounds what those gates could have SEEN.

## Phase 2 — the work-list this census generates

1. **Island drive**: extract the sub-fragments the engine walkers
   reparse (graph-fetch trees, connection values, mapping islands) and
   drive their 44 grammars; shrink the undriven ceiling.
2. **Uncovered-rule closure**: for each uncovered rule of a driven
   grammar, either (a) write an oracle-probed fixture that exercises
   it (byte-pinned, joins the fixture corpus), or (b) prove it
   unreachable from any input (dead grammar — an upstream finding).
   Start with the thin activator/store grammars.
3. **Generative gate seeding**: the uncovered lists are exactly the
   productions a grammar-driven generator should weight first.
