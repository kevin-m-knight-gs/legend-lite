# Deferred: mapping/service test-suite EXECUTION (and hosted services)

2026-08-10, decided during the engine-module deletion. Two features were
DELETED rather than ported, because the engine-lite implementations were
inventions and porting inventions is forbidden (conform-by-emission memory,
AGENTS.md). This file is the re-implementation charter.

## What was deleted

1. **Mapping testSuites runner** — `com.gs.legend.exec.TestSuiteRunner` +
   `MappingTestIntegrationTest` + `MappingTestSuiteParserTest`. The runner was
   homegrown: strip the leading `|` off the suite function, execute through
   QueryService, string-compare JSON with a whitespace-normalized fallback.
   None of that is the engine's assert semantics.
2. **Hosted services** — `com.gs.legend.service.{ServiceServer,
   ServiceRegistry, ServiceExecutor}` + `HostedServiceIntegrationTest` +
   `ServiceTestSuiteParserTest`. The engine flow re-parsed the service's
   functionBody TEXT and string-substituted path parameters into the query
   source (`$lastName` → `'Smith'`) — invented binding semantics. Real
   services bind parameters as query variables.

## Why re-implementation is CHEAPER than it looks: the parse already exists

The protocol layer is FULLY TYPED for every test-suite shape — built and
byte-parity-pinned during the W8/W9 wire legs (26,168-file PMCD parity):

- `Protocol.PMappingTestSuite` — id, the suite query as a parsed
  `ValueSpecification`, typed `PMappingTest`s (asserts + embedded data).
- `Protocol.PServiceTestSuite` — id, doc, typed `PSuiteData` (connection
  test data, 4.138 compact-form resolver entries), typed `PSuiteTest`s.
- `Protocol.PTestSuite` — function test suites (`functionTestSuite` wire).

Do NOT write a new parser. The work is:

1. **Semantic lowering**: replace the raw `testSuitesSource` string fields on
   `com.legend.model.MappingDefinition` / `ServiceDefinition` with typed
   records built from the protocol layer (kill the `"<suites>"` placeholder
   in `FromProtocol` and the vestigial raw `PMapping.testSuitesSource`
   bridge field at the same time — they exist only to feed the raw fields).
   Same pattern applies to the Persistence source fields when a persistence
   runtime ever lands.
2. **Execution harness conformed to the REAL engine**: assert semantics from
   legend-engine's testable framework (EqualTo / EqualToJson / EqualToTDS
   asserts, serialization format rules, connection test-data provisioning) —
   ground in the engine sources at $LEGEND_ENGINE_ROOT, oracle-verify like
   every other leg. The suite query executes via `Compiler.executeResolved`
   (the body is already a resolved-able AST — no text round-trip).
3. **Service param binding**: pattern path-params become query VARIABLES
   (engine's `Variable`-bound execution), never text substitution. The REST
   matching layer (regex + pathParams derived from `pattern()`) belongs in
   the server/rest layer per `ServiceDefinition`'s own javadoc, not on the
   parser record.

## Where the old behavior lives if needed for reference

Deleted at engine-module deletion; recover via git history of
`engine/src/main/java/com/gs/legend/{exec/TestSuiteRunner.java,service/}`
and the two integration tests (last present at 46df1188).
