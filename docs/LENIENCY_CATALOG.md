# Leniency Catalog

Every corpus source that **legend-lite accepts and the reference oracle
refuses**, adjudicated one by one. The row universe is generated (and
enforced) by `LeniencyCatalogTest`: it re-parses the ENTIRE corpus with the
oracle, and every refusal of a file we accept must classify into one of the
named classes below — an unclassified row fails the build. This document
records each class's JUSTIFICATION with the evidence that established it;
the test records membership row by row (`target/leniency-catalog.txt`).

Oracle: legend-engine 5.88.1 jars, extension set as loaded by
parser-equivalence. Corpus: full engine+pure checkouts plus Java-inline
snippets. Authoritative counts = `LeniencyCatalogTest` output
(2026-08-10, 1,484 rows after the GENUINELY-LITE fixes below):
`DIALECT-function-types=276, DIALECT-generics=300,
DIALECT-milestoning-range=30, DIALECT-native-or-m2=101,
EXTENSION-format=6, EXTENSION-section-jar=10,
ORACLE-DEFECT-InputMismatchException=345, ORACLE-DEFECT-crash=337,
VERSION-SKEW-grammar=79`. Per-class prose below keeps the sub-construct
evidence; where prose counts differ slightly from the classifier's
(message-keyed) buckets, the classifier is authoritative.

## Classes

### DIALECT-GAP — engine subsets legend-pure (~660 rows)
The engine's own grammar explicitly refuses constructs that legend-pure
requires and compiles in production. legend-lite sits between the two
dialects (the blend thesis); the corpus decides acceptance. Sub-classes,
each keyed by the ENGINE'S OWN refusal message:
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

### VERSION-SKEW — 5.88.1 oracle vs 5.92-era corpus (~90 rows)
The corpus is the CURRENT engine checkout; the oracle jars are 5.88.1.
Constructs added in between parse with us (we track the checkout) and
refuse with the oracle:
- **Relation mappings** (~41): `*X: Relation ~func ...`, `~src`, relation
  accessors `#>{db.table}#` (the oracle knows `~func` only — its error
  lists `['~func']` as the sole alternative at `~src`).
- **Primitive type definitions** (~5): `Primitive x::Y: String(...)`.
- **Misc newer grammar** (rest): individually spot-verified members of
  the `Unexpected token` residue whose constructs exist in the current
  checkout's .g4 sources.

### EXTENSION-GAP — oracle missing island/format extensions (~80 rows)
The vanilla oracle loads without certain grammar extensions; the island
or format is real engine grammar we support:
- **#TDS literals** (~46): `Can't find an embedded Pure parser for the
  type 'TDS'` or lexing runs to EOF inside `#TDS...#`.
- **#SQL / '>' islands** (23): same family (`SQL`, relation-accessor
  `'>'`).
- **Example schema format** (5): `Unknown schema format: Example`.
- **Unregistered sections** (10): `'X' is not a known section parser` —
  section jars absent from the oracle classpath.
- **csv/data islands in pure sources** (rest): oracle lexer consumes to
  EOF inside legend-pure data islands (verified: shared.pure ends
  well-formed; the refusal is mid-island).

### ORACLE-DEFECT — the oracle crashes (~540 rows)
Not a refusal but an exception escaping the oracle's own walker:
- **NullPointerException** (337): e.g.
  `DomainParserGrammar$ExpressionInstanceContext.qualifiedName() is null`
  (m2 `^X(...)` instance forms), ClassBody/AssociationBody/Multiplicity
  walker NPEs.
- **InputMismatchException with null message** (345 rows share the
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
- When the oracle is upgraded past 5.88.1, VERSION-SKEW must shrink to
  zero; rows that remain get re-adjudicated.
