# Parser completeness: model everything legend-engine models

**Governing rule.** If legend-engine models an element, legend-lite models it —
in the model layer the compiler can see, with byte-exact protocol on the wire.
No opaque carriers for built-in sections, no "we read it" that means "we skipped
it", no section accepted without being read.

Anything short of that is a number, not a capability. This document is the
worklist that gets us there, with counts measured on 2026-08-08 against the
whole 33-grammar oracle.

---

## §1 The root cause: a HALF-FINISHED migration (corrected 2026-08-08)

An earlier draft of this section called the duplication "two registries that
drifted", as though legend-lite had chosen two parsers. That was wrong, and the
codebase says so: `PARSER_DROP_IN_PLAN.md` §8 specifies a protocol-to-model
adapter, `com.legend.model.FromProtocol` implements it, and
`ArchitectureTest.java:368` already carries the note that `com.legend.model` is
*"shrinking: dies with the last model-record output"*.

**The design is: parse ONCE into protocol, transform into the model.** It is
fully landed for every `###Pure` element and never landed for the rest:

| element | model built by | status |
|---|---|---|
| Class, Enum, Profile, Association, Function | `FromProtocol.to*(parse*Protocol())` | migrated |
| **Mapping** | `MappingGrammarParser` (1,614 lines) | **legacy dual parser** |
| **Relational** | `RelationalGrammarParser` (807 lines) | **legacy dual parser** |
| Runtime, Connection, Service | own parsers | legacy |

So the duplication is not architecture, it is **debt that was scheduled for
deletion and never deleted** — and it is precisely where every gap found on
2026-08-08 lives. `~primaryKey` had been readable by the protocol parser for
months while the legacy parser the COMPILER depends on had never learned it.

This also explains the element-set gaps without needing a separate theory:
`Measure` and `Data` have protocol and no model because nothing has written
their `FromProtocol` arm; `Service` has a legacy model parser and no protocol
because it predates the migration entirely.

**Consequences for this plan.** Unification is COMPLETION, not a rewrite, and
it dissolves work rather than adding it:

* the "fix every grammar gap twice" tax disappears;
* Measure and Data need a `FromProtocol` arm, not bespoke model plumbing;
* ~2,400 lines of legacy parser get deleted rather than maintained;
* the parity gate (protocol parser) and the corpus/PCT gates (model parser)
  stop testing different code, which is why neither noticed the drift.

**Sizing it is the first task, not a guess.** Run both parsers over the corpus
and count constructs each accepts that the other refuses, in BOTH directions.
That says whether completing the migration is a cleanup or a project, before
anything is committed to it.

**Exit criterion for §1:** `MappingGrammarParser` and `RelationalGrammarParser`
are deleted; the compiler consumes `FromProtocol` output only; one parse, one
grammar, one place to fix.

## §2 Phase 0 — stop the abuse (do first, no exceptions)

`OpaqueElementDefinition` is documented as *an overlay grammar's element that
core never opens — compiling it is the extension's job*. It was then used for
built-in sections we understand perfectly well, which is how ###Data came to
"parse" into a blob nobody can resolve, and nearly how Measure and the
non-relational connections did too. That reverts a loud, accurate failure into
a silent hole — the exact defect this whole programme exists to remove.

Concretely, the danger is real and not theoretical: `Compiler.java:341` resolves
a runtime's connections through `ctx.findConnection(fqn)`. An element carried
opaquely is never found, so the H2/dialect mismatch check silently passes.

1. **Lock the carrier.** Restrict `OpaqueElementDefinition` construction to the
   SPI element sink (its only legitimate producer — a third-party grammar core
   genuinely cannot open), and add a guard test that fails if any other site
   constructs one. Keep the SPI seam working; make misuse impossible rather than
   merely discouraged.
2. **Do NOT rip out the ###Data crutch before its replacement exists.** Byte
   parity for the 77 Data elements depends on the section tokenizing, and the
   relational corpus reads library files that contain ###Data. Sequence is
   §3.1 first, crutch removed in the same commit.
3. **No new opaque uses.** Measure and the connection flavours are already
   reverted; they stay defects until modelled (§3.2, §3.3).

---

## §3 The 242 files legend-engine reads and we do not

Ranked, with what each actually needs. "Wiring" was the wrong word for any of
these: the grammar sometimes exists, but the MODEL never does.

### 3.1 Model gaps — the element has protocol but no model

| files | element | work |
|---|---|---|
| ~29 | **Measure** | `MeasureDefinition` model element + unit types visible to the resolver + arms in the compiler switches |
| 77 elems | **Data** | `DataElementDefinition` model element, replacing the opaque crutch |
| — | **Json column type** | engine's wire has a distinct `Json` (walker:492); our `RelationalDataType` has only `SemiStructured`, and JSON columns land there. Needs a sealed variant plus arms in all five switches (`Ddl:338`, `PlanText:792`, `StoreCompiler:198`, `RelationalKinds:44`, `TestDataGenerator:1299`) — each a semantic decision, none currently test-covered. Found by R3. |

### 3.2 Connection model is relational-shaped

`ConnectionDefinition` is a record of `storeName / databaseType /
specification / authentication`. A `JsonModelConnection` has a class and a URL
and fits none of it, and `findConnection` can only return the relational shape.

| files | need |
|---|---|
| ~29 | top-level Json / Xml / ModelChain connections |
| 11 | embedded `RelationalDatabaseConnection` inside a runtime |
| 6 | `Snowflake` datasource specification |
| 6 | `Test` auth flavour |

**Work:** generalise `ConnectionDefinition` into a sealed hierarchy
(`RelationalConnection | JsonModelConnection | XmlModelConnection |
ModelChainConnection`), with `findConnection` returning the base and the H2
dialect check narrowing to the relational arm.

### 3.3 Real grammar gaps in sections we already own

| files | error | note |
|---|---|---|
| 27 | `expected identifier, got PRIMARY_KEY_CMD` | embedded `~primaryKey` inside a property block — reproduction: `relational-emit-models/relational-embedded/mapping/employeeMapping.pure:28` |
| 22 | `expected type name, got BRACKET_OPEN` | Pure grammar |
| 13 | `expected identifier, got LESS_THAN` | Pure grammar |
| 15 | `expected SRC_CMD but found ...` | mapping `~src` |
| 10 | `expected BRACKET_CLOSE but found PAREN_OPEN` | |
| 9 | `unknown DSL island type: '#SQL{'` | SQL island inside ###Pure functions |
| 5 | `Enum must have at least one value` | we refuse an empty enum the engine accepts |
| 18 | `unsupported class mapping type: 'ServiceStore'` | needs §4 ServiceStore |

**Exit criterion for §3:** `OUR-DEFECT` reaches 0 in `CorpusCensusTest`, i.e.
every file legend-engine reads, legend-lite reads.

---

## §4 The unbuilt sections — 184 files skipped, 440 elements out of scope

Each needs all three registries: lexable section, model element, protocol shape
with byte parity. Ranked by elements the oracle produces.

| elements | section | notes |
|---|---|---|
| 135 | **Service** | model + parser already exist; section is closed and protocol missing — cheapest real section |
| 77 | ExternalFormat | |
| 53 | Persistence | |
| 51 | DataSpace | |
| 32 | ServiceStore | also unblocks 18 defect files in §3.3 |
| 31 | Snowflake | |
| 17 | Diagram | engine dialect only — legend-pure's `typeView` dialect is a different language (see §6) |
| 7 | FileGeneration | |
| 7 | MemSql | |
| 5 each | BigQuery, Deephaven, GenerationSpecification | |
| 4 | HostedService | |
| 3 | DataQualityValidation | |

**Exit criterion for §4:** `OUT_OF_SCOPE` reaches 0 and no file is "read by
skipping".

---

## §5 The leniency we cannot yet justify — 180 files

Of the 1,519 files legend-lite reads and legend-engine refuses, most are
defensible: ~694 are files the engine CRASHES on (NPE in its own
`DomainParserGrammar` walker) and ~430 are legal Pure it deliberately subsets
away (`Type and/or multiplicity parameters are not authorized in Legend
Engine`). Those are the blend thesis working.

The remaining ~180 are `Unsupported syntax` / `Unexpected token` — a real
syntactic refusal where we took the file anyway. Those are the only rows where
we may simply be wrong, and 429 of the 1,519 are inline snippets scraped from
Java tests, which include deliberately-invalid negative fixtures. Reading one of
those is a bug wearing breadth's clothes.

**Work:** adjudicate the 180 by hand or by oracle (§6); each becomes either a
named, justified superset entry or a fixed leniency.

---

## §6 The second oracle (offline, never a gate)

`ONLY-WE-READ-IT` currently means "our parser did not throw". legend-pure would
adjudicate it, but has no standalone parse API: its grammar tests extend
`AbstractPure*TestWithCoreCompiled`, which boots a `PureRuntime` with the
platform repositories compiled and then `createInMemorySource` +
`compile()`. Driving `M3AntlrParser` directly dies on `Cannot find Root`,
because the repository needs bootstrapping and the bootstrap source (`m3.pure`)
is M4 syntax the M3 parser itself cannot read.

**Work:** a separate offline analysis that boots a runtime once and adjudicates
the 1,519 + 481. Minutes per run, so it never joins the gate chain. Note the
version skew: jars are 5.88.1, the checkout is 5.92.1-SNAPSHOT.

---

## §R3 — the switch: LANDED (2026-08-08)

`ElementParser`'s `DATABASE` arm now reads
`FromProtocol.toDatabaseDefinition(DatabaseProtocolParser.parse(...))`. The
###Relational model is a transform on protocol. All eight gates green.

The 7 named core failures reproduced exactly as predicted, and closing them
cost less than the list implied — but the list was **not** the cost of R3.
That claim came from the core suite alone, and the core suite does not
generate SQL. Gate 4 regressed **nine families** on the first full run.

**What the 7 actually were** (four transform bugs, two mis-pinned tests, one
consequence):

* `multiGrainFilterTrackedSeparately` — `FromProtocol` tested for
  `"multiGrain"`; the wire spells it `"multigrain"`
  (`RelationalParseTreeWalker.java:662`). Every MultiGrainFilter was silently
  filed as a plain Filter.
* `joinNavigationMultiHopWithTerminal` + `MappingNormalizerTest` — the
  protocol parser read an ATOM after a join-nav's `|`, where engine reads a
  whole `booleanOperation` (`RelationalParserGrammar.g4:227`), so
  `@J | T.X = 1` wrapped the nav in a comparison instead of putting the
  comparison inside it. One bug, two failures.
* `filterRejectsBareIdentifierMatchingEngine` — both paths refuse a bare
  identifier; only the wording differed. The protocol parser now says what
  engine's walker says (`Missing table or alias for column 'X'`, walker:993).
* `joinMediatedFilterRequiresSourceDbQualifier` — same: a rejection with a
  message that did not name the requirement.
* `databaseTableMilestoningCapturesInclusivityAndInfinityDate` — the plan
  said the transform "loses inclusivity/infinity-date detail". It does not:
  inclusivity was always correct, and the only difference was the `%` prefix
  on INFINITY_DATE. `%` is Pure grammar; the wire carries the bare ISO
  string. The TEST was pinning the legacy parser's verbatim token capture.
  `TemporalFrame:1540` already tolerated both spellings.
* `viewFilterJoinMediatedLocalTarget` — a genuine leniency of ours, and the
  test pinned it. `viewFilterMappingJoin` ends in a databasePointer
  (`g4:143`), so `~filter [DB] @J | F` with a bare local target is invalid.
  All 36 corpus uses spell the `[DB]`. Leniency dropped; the test now pins
  the rejection, with a second test covering the legal spelling.

**What the core suite could not see.** Four more holes of the same kind as
`BOOLEAN` — reachable from the protocol parser, with no arm in the transform:

* `Numeric` had no `FromProtocol` case at all: a NUMERIC column crashed.
* `Json` had none either. Engine's wire has a distinct `Json` type
  (walker:492); our MODEL does not, and the legacy parser collapsed it into
  `SemiStructured`. Carried over unchanged and recorded — see §3.1.
* The protocol parser minted an `"Array"` wire kind. Engine has none: the
  ARRAY keyword walks to `Other` (walker:465). A byte-parity defect that no
  corpus file exercises, so gate 8 never saw it.
* A bare `VARCHAR` became `Varchar(0)` instead of the model's unbounded
  `Varchar(MAX_VALUE)`.

**And one that only gate 4 could find.** The flat `tables()`/`views()` lists
are the bare-name lookup mirror. The wire orders schemas the way engine's
walker appends them — named schemas first, the synthetic `default` last —
and `FromProtocol` flattened in that order, so a `schemaB.personTable` with
3 columns shadowed the default-schema `personTable` with 7. Bare names mean
the default schema; the flat lists are now built default-first while
`schemas` keeps the wire's order. That one line was worth 19 tests in
`tests/mapping/join` alone.

`MigrationEquivalenceTest` structural mismatches: 25 → 18. The
`JoinNavigation` half of that was a plain transform bug — the enclosing-db
as-written rule that `columnRef` applies was never applied to nav roots —
not the "principled floor" the constant's comment claimed.

**The lesson for the rest of this plan.** Structural equality over the corpus
is necessary and nowhere near sufficient: 538 of 561 databases were already
identical while nine families generated wrong SQL. Gates 4/5/6 are the
switchover proof. Nothing else is.

## §7 Order of attack

1. **Phase 0** (§2) — lock the carrier, no new opaque uses. DONE.
2. **Size the migration gap** (§1) — both parsers over the corpus, both
   directions. Cheap, and it decides everything after it.
3. **§3.3 grammar gaps** — but land each fix on the PROTOCOL parser and let
   `FromProtocol` carry it, so the work moves toward deleting the legacy
   parser rather than deepening it.
3. **§3.1 Measure + Data model** — removes the ###Data crutch in the same
   commit that replaces it.
4. **§3.2 connection hierarchy** — 52 files, unblocks embedded connections.
5. **§4 Service** — the biggest section and the closest to done.
6. **§4 remaining sections** in element order.
7. **§5/§6** — adjudicate leniency once an oracle exists.

Each step keeps the standing gate discipline: `CorpusCensusTest` numbers move in
the right direction, `MAX_*` ratchets descend, `allgates` green before push.
