# Upstream defects — filing-ready list for legend-engine / legend-pure

2026-08-14, Phase 4 of the parser-hardening program. Every row below is
ORACLE-VERIFIED against legend-engine 4.138.2 (the pinned jars) by the
differential harness; repros live in the referenced batteries/fixtures.
This file is the single filing queue — when a row is reported upstream,
add the issue link; when fixed upstream, the oracle-bump procedure
(below the table) retires it.

## Crash-class (parser throws raw unpositioned exceptions on user input)

| # | defect | evidence |
|---|---|---|
| U1 | `DomainParserGrammar$QualifiedNameContext.packagePath()` NPE family — 197 corpus sources crash the parser with a raw NPE instead of a positioned parse error | leniency catalog `ORACLE-DEFECT-crash` (338 rows total, this signature dominant) |
| U2 | Null-message `EngineException` family — 135 sources refused with `message == null` (no position, no text) | catalog `GRAMMAR-REFUSAL-nullmsg` + crash rows |
| U3 | GraphFetch `InstanceLiteralContext.instanceLiteralToken()` NPE; Domain `ToMultiplicityContext.getText()` NPE; `AssociationBodyContext.properties()` NPE | catalog signatures (2/2/1 rows) |
| U4 | Persistence `serviceOutputValue` two-meaning rule: 6 unguarded call sites NPE on identifier-vs-path confusion (e.g. graphFetch keys as identifier, TDS keys as navigation path) | `parity-quarantine.tsv` F23; sibling fixtures `neg-persistence-graphfetch-keys-identifier.pure`, `neg-persistence-tds-keys-navigation-path.pure` |
| U5 | DataSpace `Email {}` (missing address): raw NPE instead of "Field 'address' is required" | adversarial audit row 14 |

## Silent-wrong class (accepts and corrupts, or drops data)

| # | defect | evidence |
|---|---|---|
| U6 | F19: an UNTERMINATED final element in Connection-family sections is ACCEPTED (the parser stops at the last element it understood instead of requiring balance) | `MutationFuzzTest` drop-final-delimiter family (13 fixtures + truncate), `parity-quarantine.tsv` |
| U7 | `^new` key-expression truncation: an unparenthesised infix chain in a key expression keeps only the FIRST atom (`s='a'+'b'+$v` emits `'a'`) | ProbeWireShapes "burn zoo 2"; reproduced for byte parity in our emitter with the bug documented |
| U8 | HostedService walker PARSES `generateLineage`/`storeModel` values and DISCARDS them — the wire always says false | probe 2026-08-14 (all three booleans probed; only `autoActivateUpdates` sticks) |
| U9 | FunctionJar walker parses `activationConfiguration` and drops it entirely | probe t2-functionjar |
| U10 | `SnowflakeAppDeploymentConfiguration` is parsed then dropped (no element, no section entry); `BigQueryFunctionDeploymentConfiguration` emits a NAMELESS element (no name/package/sourceInformation) and a literal `null` in the sectionIndex element list | probe 2026-08-14; both reproduced for parity |
| U11 | Mongo store `include` and `Join` are parsed and dropped (includedStores stays `[]`) | probe t2-mongodb |
| U12 | F22 dead validation: walker validations exist in source but are unreachable (documented in the sibling handoff's defect catalog) | handoff §7 |

## Print/parse asymmetry

| # | defect | evidence |
|---|---|---|
| U13 | `printFunctionDefinition` on a concrete function emits `name(a:T[1],...):R[1] { body }` — a form NEITHER legend-engine's grammar NOR legend-pure's own M3 parser accepts. Print output is unparseable by every real parser | invention census D2; verified against M3Parser/M3CoreParser grammars |

## Span defects (reproduced for byte parity, named in SpanOrigin)

| # | defect | evidence |
|---|---|---|
| U14 | Connection-value reparse leaks its column offset into ANTLR ctx ends: auth islands +3, ES/Deephaven connection values +4 | probe matrix 2026-08-14; `SpanOrigin.overshootEnd` |
| U15 | Deephaven grammar's `appMultiplicity` wants `DOT DOT` but CoreLexer emits one `DOT_DOT` token — `[n..m]` is unreachable in the Deephaven grammar only | sibling negative + grammar cross-read |
| U16 | Deephaven connection `serverUrlDefinition` is the only field rule WITHOUT a trailing `SEMI_COLON` — `serverUrl: '...';` refuses | .g4 cross-read + fixture |

## Filing notes

- U1–U5 are one upstream theme: route every walker NPE/NFE through a
  positioned `EngineException` (the audit's own recommendation).
- U6 (F19) changes accept-behavior; flag as behavioral so downstream
  parsers (like this one) can drop their quarantine rows.
- U13 matters to anyone round-tripping printed Pure.
- We intentionally REPRODUCE U7/U8/U9/U10/U11/U14 for byte parity;
  each is marked in-code with the probe reference, so an upstream fix
  lets us delete the reproduction alongside the oracle bump.

## Oracle-pin bump procedure (4.138.2 → N)

1. Re-javap `DatabaseType` from the new jar (the 21-value closed set
   in `ConnectionSectionGrammar.DATABASE_TYPES`).
2. Re-run the FULL chain; expect `version-skew-claims.tsv` rows (the
   claimed checkout-unreleased grammar) to start PASSING — remove the
   claims that the new jar satisfies (shrink-only).
3. Re-probe the 22 oracle-unreachable census rows (`docs/
   g4-keyword-snapshot.tsv`) — newly reachable keywords need wire
   probes and grammar arms.
4. Walk THIS file: any upstream fix flips a reproduction into a
   divergence — the batteries will go red at the exact rows; delete
   the reproduction and the row together.
5. Re-pin the gate-time budget if the new jars change parse cost.
