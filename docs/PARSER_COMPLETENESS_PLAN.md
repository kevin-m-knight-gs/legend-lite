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
