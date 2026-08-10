# Leniency Catalog

Every corpus source that **legend-lite accepts and the reference oracle
refuses**, adjudicated one by one. The row universe is generated (and
enforced) by `LeniencyCatalogTest`: it re-parses the ENTIRE corpus with the
oracle, and every refusal of a file we accept must classify into one of the
named classes below — an unclassified row fails the build. This document
records each class's JUSTIFICATION with the evidence that established it;
the test records membership row by row (`target/leniency-catalog.txt`).

Oracle: legend-engine **4.138.2** jars (W10 re-pin, 2026-08-10; the
previous pin was 4.133.0 — an earlier revision of this doc mis-recorded
it as "5.88.1"), extension set as loaded by parser-equivalence. Corpus:
full engine+pure checkouts plus Java-inline snippets. Authoritative
counts = `LeniencyCatalogTest` output (2026-08-10 post-re-pin + extension close-out, 1,459
rows): `DIALECT-function-types=278, DIALECT-generics=331,
DIALECT-milestoning-range=31, DIALECT-native-or-m2=102,
ENGINE-TEST-SCOPED-section=10,
ORACLE-DEFECT-InputMismatchException=344, ORACLE-DEFECT-crash=338,
VERSION-SKEW-grammar=25`. Per-class prose below keeps the sub-construct
evidence; where prose counts differ slightly from the classifier's
(message-keyed) buckets, the classifier is authoritative.

The 4.138.2 re-pin's effects: the G8 ledger grew to 26,168 byte-equal /
0 DIFF / 0 WALL / 0 MISSED (relation `~func`/`~src` class mappings,
compact service test suites, `'''...'''` multi-line strings — all newly
wire-verified); EXTENSION-island-parser retired (the newer extensions
collection ships the `#TDS`/`#SQL`/relation-accessor parsers);
VERSION-SKEW collapsed 79 → 25.

## The two surfaces (dialect quarantine, 2026-08-10)

legend-lite parses through TWO modes on one parser
(`TokenStreamCursor.legendStrict()`):

- **ENGINE-STRICT** (`ElementParser.at`/`parseStrict` — the drop-in
  surface, all user code): refuses every DIALECT construct below with the
  engine's own message. Rejection parity is EXECUTABLE:
  `StrictDialectParityTest` re-parses the corpus and asserts every
  DIALECT-classified row ALSO refuses here (707 rows, 0 leaks), and
  `SpiSeamProofTest` ratchets the strict surface's residual leniency
  (302 = EXTENSION + VERSION-SKEW + ORACLE-DEFECT rows only).
- **PURE MODE** (`ElementParser.parse` — the internal pipeline and the
  legend-pure corpora): the superset dialect. The DIALECT rows below are
  therefore NOT leniency on the product surface; they are pure-mode
  features — legend-lite's reimplemented legend-pure (Pure.java natives,
  typer/lowerer semantics), whose parsing the corpus gates exercise.

## Classes

### DIALECT-GAP — pure-mode features, strict-refused (~707 rows)
The engine's own grammar explicitly refuses constructs that legend-pure
requires and compiles in production. Pure mode accepts them; the strict
surface refuses each with the ENGINE'S OWN message (the classifier keys
below). Sub-classes:
- **Generics** (~300): `Type and/or multiplicity parameters are not
  authorized in Legend Engine` — `Class A<T>` etc. The engine names its
  own subset.
- **Function types** (~276): `The type {X[m]->Y[n]} is not supported yet`
  — function-type literals in signatures; required across the legend-pure
  platform sources.
- **Milestoning ranges** (27): `.allVersionsInRange(...) is not supported`.
- **Native functions** (~35): `Unsupported syntax` at `native function`
  declarations (verified: dayOfYear.pure:15 and the class's rows all point
  at native declarations) — legend-pure platform sources are built from
  natives.
- **m2 mapping dialect** (~20): m2-only mapping forms the modern grammar
  refuses — inline `EnumerationMapping X: COL` property syntax
  (embeddedMapping.pure:26), m2 embedded/otherwise shapes
  (`employeeFirmDenormTable` alternatives `['(', '@']`), m2 aggregation-
  aware forms (TestAggregationAwareMapping snippets, `Unsupported syntax`).

### VERSION-SKEW — checkout-unreleased grammar (25 rows)
The corpus is the CURRENT engine checkout, which carries UNRELEASED
grammar the 4.138.2 release does not know yet (TDS-DSL compilation
snippets, newer modelJoin test spellings, relation setups). We parse
them (we track the checkout); the released oracle refuses with a bare
"Unexpected token". Membership is MECHANICALLY adjudicated: on that bare
message the classifier consults OUR strict surface — an engine-verbatim
strict refusal names the row's construct and moves it to the matching
DIALECT class; a strict ACCEPT is what "checkout-unreleased" means
(ZSkewResidueProbe). The 4.133-era members (relation mappings, `~src`,
`#>` accessors, `Primitive` definitions) all became oracle-ACCEPTED at
4.138.2 and now sit in the byte-exact ledger.

### ENGINE-TEST-SCOPED — grammar living only in the engine's OWN tests (10 rows)
`###AuthenticationDemo`: its section parser exists exclusively in the
engine's `src/test` sources and no `-tests` jar is published — a
PRODUCTION engine refuses these files exactly as our strict surface does
(`'AuthenticationDemo' is not a known section parser`). Pure mode skips
the section as an unclaimed carrier; nothing to build.

The former EXTENSION-format class (6 rows) is CLOSED: `Example` schema
format is engine-test-scoped too, and `WHATSCHEME` was a Snowflake
negative fixture — both now refuse on BOTH surfaces via engine-parity
validation (SchemaSet format ∈ {FlatData, JSON, XSD}; permissionScheme ∈
{DEFAULT, SEQUESTERED}), with the ledgers proving the sets are
production-true. The 4.133-era `#TDS`/`#SQL`/`'>'` island rows retired at
the 4.138.2 re-pin (the extensions collection ships those parsers).

### ORACLE-DEFECT — the oracle crashes (~682 rows)
Not a refusal but an exception escaping the oracle's own walker:
- **NullPointerException** (338): e.g.
  `DomainParserGrammar$ExpressionInstanceContext.qualifiedName() is null`
  (m2 `^X(...)` instance forms), ClassBody/AssociationBody/Multiplicity
  walker NPEs — plus one `NumberFormatException` from the oracle's
  unicode-escape parser (`For input string: "sers" under radix 16`,
  TestProfile.java#52).
- **InputMismatchException with null message** (344 rows share the
  `null`-message bucket with the NPEs; the split is recorded per row in
  the generated report).
A crash cannot adjudicate our acceptance either way; these rows are
recorded as oracle defects with their exception classes.

### GENUINELY-LITE — was OUR leniency; now FIXED (9 rows, closed)
Engine-required fields our typed grammars did not enforce. All fixed as
structured engine-parity refusals in this audit:
- `ownership` required on SnowflakeApp, SnowflakeM2MUdf, MemSqlFunction,
  HostedService, FunctionJar (NOT BigQueryFunction — accepted corpus
  fixtures omit it; the corpus adjudicates).
- `functionName` required on MemSqlFunction.
- `deploymentSchema`, `deploymentStage` required on SnowflakeM2MUdf.
- `asserts` required on Persistence test batches (the long-mis-attributed
  sentinel row TestPersistenceCompilationFromGrammar#30 — the field
  belongs to PERSISTENCE batches, not service tests).
Earlier fixes in the same family: Relational sink requires `database`,
ObjectStorage sink requires `binding` (W8); join-type/mapping-test-format
validation and five persistence negatives (pre-W2).

## Standing rules
- New refusal rows must classify or the catalog test fails — leniency can
  no longer grow silently in ANY class.
- A row may only enter DIALECT-GAP/VERSION-SKEW on the engine's own
  message or a verified construct; "we are a superset" is never accepted
  as a justification by itself.
- Every DIALECT row must REFUSE on the strict surface
  (`StrictDialectParityTest`) — a dialect construct reachable from
  `parseStrict` is a quarantine leak and fails the build.
- On every oracle upgrade, VERSION-SKEW re-adjudicates: previously-skew
  constructs the new jar accepts must enter the byte-exact ledger, and
  bare-message residue is adjudicated by the strict surface (dialect vs
  still-unreleased). Done at 4.138.2 (79 → 25); repeat at the next bump.
