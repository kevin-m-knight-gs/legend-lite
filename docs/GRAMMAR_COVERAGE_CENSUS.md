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

## Phase 2 — island drive (DONE 2026-08-15)

15 island grammars driven via shape extractors (regex + quote-aware
balanced-brace scan; island coverage counts ONLY error-free fragments,
so a mis-routed fragment cannot inflate through ANTLR error recovery).
Entry rules resolve by engine convention: `definition`, else the rule
named after the grammar (island walkers' own entry), else first rule —
a vacuous first-rule entry shows as the visible "1-rule" signature.

New baseline: **39 grammars driven, 1,209/2,662 rules (45.4%),
undriven 44 -> 27.** Strong islands: GraphFetchTree 85.7%,
RelationalDatabaseConnection 85.7%, ModelStoreData 82.4%,
EnumerationMapping 75.0%, ModelConnection 72.7%, Navigation 68.8%,
PureInstanceClassMapping 44.7%. Ratchets: drives >= 39,
coverage >= 1200, undriven <= 27.

**EXTRACTION-PENDING** (driven but the fragment shape is wrong — the
extractor, not the corpus, is the limit; fix means probing the exact
walker-fed text): AggregationAware (34/34 err), DataSourceSpecification
(125/181 err + vacuous), AuthenticationStrategy (38/61 err),
PostProcessor (1 rule). These four rows are honest instrument debt.

## A2 completion (2026-08-15): every extractable grammar driven

Best-rule drive landed (per-kind value grammars have NO single entry —
per fragment, the first rule that parses error-free with FULL token
consumption wins; datasource 42.1%, auth 57.1%, store-specific
connectors 25-65%, DeephavenConnection 90.9%). **Baseline: 41 grammars
driven, 1,447/3,914 rules (37.0% — the denominator grew as value
grammars joined), undriven 44 -> 2.** Ratchets: drives >= 40,
coverage >= 1400, undriven <= 2.

The 2 remaining are **CORPUS-ABSENT** (verified: zero instances of the
island exist in any corpus source): `ElasticsearchConnection`,
`RelationElementsData`. Closable only by fixtures, not extraction.
Low-coverage EXTRACTION-PENDING rows remain honest instrument debt:
PostProcessor 0%, RelationalMapper 0%, EqualToAssertion 0%,
PersistenceCloud/Relational ~1%, aggAware 5.5%, Code 1-rule — each
needs its true walker-fed shape probed.

## Phase 3 — remaining work-list

1. **Uncovered-rule closure** (the big one): per uncovered rule of a
   driven grammar, an oracle-probed byte-pinned fixture, or an
   unreachability proof (dead grammar — upstream finding). Start with
   the thin activator/store grammars + the 2 corpus-absent islands.
2. **EXTRACTION-PENDING shapes** (list above) — probe walker-fed text.
3. **Generative gate seeding**: the uncovered lists are exactly the
   productions a grammar-driven generator should weight first.
