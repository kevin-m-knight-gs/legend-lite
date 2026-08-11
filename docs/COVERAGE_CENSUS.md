# Coverage Census — "what might we not parse?" answered completely

**Date:** 2026-08-11. **Question:** beyond the corpus, which
user-expressible engine constructs has NO parity layer ever exercised?
**Method:** three independent axes, triangulated. **Answer:** a bounded
worklist of ~53 protocol tags + ~35 grammar keywords (heavily
overlapping) + 2 known production-level gaps — everything else is
either proven covered or out of text-parity scope by construction.

## The three axes

1. **Inverse-parity net** (GATED since `ea62f28b`,
   `PmcdEquivalenceTest`): every oracle-accepted corpus source must
   parse through lite's MODEL path — zero unexplained refusals. Caught
   two wrong-arity-association crashes on day one. This proves
   everything the corpus CONTAINS.
2. **Keyword census** (`ZKeywordCoverageProbe`): all 670 word-shaped
   token literals across the engine's 73 `.g4` grammars, checked for
   presence in at least one both-accepted source. 567 covered; 103
   uncovered, of which 89 are the embedded Haskell/Protobuf codegen
   grammars (not Legend text) — **~35 real keywords** across 14 groups
   (auth flavors, datasource specs, rare Service/Persistence/DataQuality
   fields, RelationalMapper).
3. **Protocol-type census** (`ZFullRosterCensusProbe`): the FULL
   Jackson `_type` roster — 1,033 tags from every `@JsonSubTypes` in the
   protocol jars + the extension registry — against the tags actually
   occurring in the oracle's serialization of BOTH corpuses (engine
   checkouts + our own test snippets; the union matters — `duckDB`
   is uncovered by the engine corpus but proven by ours). 245 covered.
   Of the 788 uncovered, package triage disposes almost all:

   | bucket | ~tags | disposition |
   |---|---|---|
   | Elasticsearch v7 spec types (querydsl/aggregations/analysis/mapping) | ~373 | island-DSL metamodel — the ES section's parity is at section level, not per-type |
   | sql.metamodel | 72 | the SQL API's AST — arrives as JSON, not Pure text |
   | mongodb / deephaven / protobuf3 / graphQL / haskell metamodels | ~156 | same: island/API metamodels |
   | executionPlan nodes/results | ~60 | runtime OUTPUT, never parsed from text |
   | valueSpecification legacy + deprecated | ~21 | old wire forms kept for deserialization only; relevant ONLY if lite ever consumes stored protocol JSON (deferred charter) |
   | context/api/misc | ~23 | SDLC/API-side |
   | **user-typable residue** | **53** | **the worklist** |

## The consolidated fixture worklist

Dedup of axis-2 keywords and axis-3 tags by construct family
(exact tag lists: `parser-equivalence/target/protocol-roster.txt`,
regenerate with the probes):

- **Mapping production arms** (the Binding-transformer family — arms
  under COVERED keywords, invisible to axis 2): `bindingTransformer`
  (probe-proven gap), `inlineEmbeddedRelationFunctionPropertyMapping`,
  `modelJoinPropertyMapping`, `mappingClass`, TabularFunc.
- **Connection auth**: kerberos, delegatedKerberos, oauth,
  gcpWorkloadIdentityFederation, TrinoDelegatedKerberosAuth,
  encryptedPrivateKey, gcpWithAWSIdP + AWS vault credential kinds
  (awsDefault/awsStatic/awsSTSAssumeRole/awssecretsmanager) +
  vault secrets (environment/properties) + service-store security
  (apiKey/http, singleSecuritySchemeReq).
- **Datasource specs**: Trino, redshift, h2Embedded, GlobalAurora,
  ExtractSubQueriesAsCTEsPostProcessor, generationFeaturesConfig.
- **Service**: postValidations, DID, mcpServer, runtimeComponents,
  deploymentOwnership, userListOwnership, multiExecutionParameters
  (+ alias-tag suspects like `PureSingleExecution` — some registered
  names are legacy aliases of covered tags; adjudicate as such).
- **Persistence variants**: snapshot ingest modes (nontemporal/
  unitemporal/bitemporal), batchId milestoning kinds, dedup strategies
  (maxVersion/anyVersion), partitioning (fieldBased...), cronTrigger,
  streamingPersister, sourceSpecifiesFromDateTime, deleteIndicator.
- **Misc**: DataSpace combined/featuredDiagrams, Diagram
  hideStereotype/hideTaggedValue, DataQuality field set, Elasticsearch
  HalfFloat/Short, Deephaven columnDefinition, RelationalMapper
  (DatabaseMappers/SchemaMappers/TableMappers), relational `Array`/
  `Other` datatypes, ModelConnection/ModelStringInput, legacyRuntime,
  m3 literals byteArray/unitInstance, EqualToTDS assertion, `toBytes`.

## Reachability correction (2026-08-11, same day — heuristics retired)

The package-name triage above OVER-EXCLUDED. `ZPmcdReachabilityProbe`
computes the definitive scope: a static walk of the protocol type graph
from `PureModelContextData` (fields + generics + Jackson subtype
expansions; 762 reachable classes). Verdicts over the 788 uncovered:

- **567 UNREACHABLE — proven out of scope**, no judgment calls:
  execution-plan nodes, test results, the SQL API AST, and the
  Elasticsearch QUERY-DSL/aggregations types (request shapes produced
  by execution translation, not typable in any section).
- **221 REACHABLE — the true in-scope worklist**, ~3× the heuristic
  estimate. The corrections the heuristics missed:
  - **Island STORE CONTENT is in scope**: MongoDB schema/aggregation
    types (46), Deephaven metamodel (41), Elasticsearch index-mapping
    property types (~52) ride inside store elements in PMCD. The
    "island-DSL out of scope" disposition was wrong for definitions —
    only the request/query DSLs are out.
  - Legacy `valueSpecification.deprecated` forms and the
    `pure.v1.model.context` pointers are structurally reachable but
    likely not producible by the MODERN grammar — each gets adjudicated
    at fixture time (expected disposition: alias/legacy-wire, proven
    per item rather than assumed).

Fixture COUNT is far below tag count: one rich MongoDB store fixture
covers dozens of bsonType tags at once; realistic pack size is ~30–40
fixtures for the 221 tags plus the ~35 keywords.

## Method limits (stated, with their nets)

- Keyword presence proves an arm was TOUCHED, not every alternative —
  production-level gaps under covered keywords escape axis 2 (Binding
  did). Axis 3 catches the subtype-shaped ones; FIELD-shaped arms
  (bindingTransformer is a field) escape both — those come from the
  fixture pass and the engine's grammar-roundtrip tests.
- Alias `_type` names can read as uncovered when the modern spelling is
  covered — adjudicated per-tag during fixturing, never assumed.

## Next

Write the fixture pack (~60–70 minimal fixtures after dedup), each
adjudicated oracle-vs-lite; land a gated coverage test that counts BOTH
corpuses + the pack, so "nothing user-typable is unproven" becomes an
invariant instead of a question. Then the Binding wire-batch; strict
flip last.
