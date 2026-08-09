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

## §M0 — the Mapping migration, sized (2026-08-08)

`MappingMigrationCensusTest`. Both paths read the same **1,503** corpus
mapping elements, which is the first good sign: unlike `BOOLEAN`, there is no
large hidden readability gap. `MigrationSizingTest` owns the readability
question and puts the cost at **24 legacy-only FILES, 18 of them one cause**
(`expected identifier, got DOLLAR`). Two harnesses must not both answer the
same question — that is how 2,298/2,575 and 2,398/2,798 came to look like a
regression.

**M1 slice order — property mappings by corpus frequency:**

| count | variant | | count | variant |
|---:|---|---|---:|---|
| 3,030 | `Column` | | 107 | `JoinTerminalColumn` |
| 813 | `Join` | | 102 | `EnumeratedColumn` |
| 256 | `Expression` | | 59 | `InlineEmbedded` |
| 192 | `Embedded` | | 20 | `OtherwiseEmbedded` |
| 163 | `LocalProperty` | | 11 | `EnumeratedExpression` |

`Column` + `Join` is **81%** of all property mappings. That is M1.

**Class mappings.** Model: `Relational` 1,813 · `Pure` 710 · `Union` 270 ·
`RelationFunction` 184 · `Inheritance` 26. Protocol: `PClassMappingRel` 1,806
· `PClassMappingPure` 722 · `PClassMappingOperation` 310 ·
`PClassMappingRelation` 109 · `PClassMappingAggregationAware` 33 ·
`PClassMappingMergeOperation` 11. Note the model has NO `Operation` variant:
the wire's 310 operation mappings land as `Union` (270) + `Inheritance` (26),
so the transform re-derives the distinction from the operation function.

**Protocol shapes needing a decision, and what is NOT one.** A first pass
flagged xstore and model-join as homeless; that was a naive name-match and it
was wrong — `AssociationMapping` permits `Relational | Cross | ModelJoin`, so
both have model homes (`PXStoreAssociationMapping` 65 → `Cross`,
`PModelJoinAssociationMapping` 95 → `ModelJoin`). The real list:

| count | shape | disposition |
|---:|---|---|
| 64 | `testSuites` | model keeps only RAW TEXT (`testSuitesSource`) — carried, not modelled |
| 33 | `AggregationAware` | **flattened by design**: keep `~mainMapping` flagged `aggregationAwareMain`, DROP `Views:` so rewrite asserts fail honestly (`MappingGrammarParser:456-512`). Reproduce the flattening; do not invent a variant. |
| 11 | `MergeOperation` | genuinely no `ClassMapping` variant — needs one, or a loud refusal |
| 11 | legacy `tests` | no model field at all |

**The model is mid-migration on a SECOND axis.** `MappingGrammarParser`
returns one of two shapes per element (`:105-120`): `LegacyMappingDefinition`
(the legacy DSL surface tree of `ClassMapping` variants, which
`MappingNormalizer` rewrites to canonical) or `MappingDefinition` (the
clean-sheet function form). All 1,503 corpus elements are legacy-DSL. So the
protocol→model transform targets the SURFACE tree, and the surface→function
rewrite stays where it is. Conflating the two axes would turn M into a
rewrite instead of a completion.

**What M must not repeat.** R3's only real regression came from an ORDER the
wire does not carry (flat table lists) and a NULL CONVENTION it does not
carry (enclosing-db self-references). The mapping analogues to watch are
property-mapping order, set-id ordering, embedded nesting order, and the
`~mainTable` / source-table conventions. Structural equality will not catch
these — gates 4/5/6 will.

## §M1–M4 — the mapping transform is COMPLETE; the switch is costed

`MappingFromProtocol` transforms **every** corpus mapping: 1,448 built by
both paths, **1,351 IDENTICAL**, 96 as-written, 1 legacy defect, **0 real
mismatches**, 0 refused. `MappingEquivalenceTest` is the safety net and it is
ratcheted at zero.

The harness carries THREE buckets, not one, because collapsing them hides
work: **AS-WRITTEN** (96) is what the wire structurally cannot preserve, each
verified against a named corpus source; **LEGACY DEFECT** (1) is where the
protocol path is RIGHT — an enumeration source value spelled `\\` denotes ONE
backslash, the wire decodes it, the legacy parser keeps the raw escape;
**MISMATCHED** is everything else, and it is 0.

Two protocol-parser gaps found and made honest rather than silently wrong:
`prop: EnumerationMapping m: COL` in a Relation class mapping was read with
`EnumerationMapping` AS the column name (it now refuses, pending the field on
all three registries), and `extends` on an Operation class mapping is
carried on the protocol record but kept OFF the wire, because engine parses
and drops it (`OperationClassMappingParseTreeWalker` TODO) — the same
treatment as a multi-pair include substitution.

### §M4 — the switch: LANDED (2026-08-08)

`ElementParser`'s `MAPPING` arm builds the model by transforming protocol.
`MappingGrammarParser` and `RelationalGrammarParser` are DELETED — 2,422
lines — along with the four differential harnesses whose only purpose was to
A/B them against the protocol path. One parse, one grammar, one place to fix.

**The leniency rule did most of the work, and the ORACLE decided each case.**
Eight of the eleven failures were adjudicated by running the fixture through
the real engine parser rather than by reading grammar files, and it overturned
calls in BOTH directions:

| fixture | verdict | why |
|---|---|---|
| `*Enum: EnumerationMapping` | OUR OVER-STRICTNESS | the star is on the shared `mappingElement` rule (`MappingParserGrammar.g4:59`) and `parseEnumerationMapping` never reads `ctx.STAR()` — engine accepts and drops it |
| `prop: EnumerationMapping: expr` | OUR LENIENCY, both parsers | both grammars require the id; the plan had filed this as a FEATURE GAP needing a carried flag. It needed deleting, not building. |
| `~clause` order / duplicates | protocol parser too lenient | `RelationalParserGrammar.g4:249` is a fixed ascending prefix, all before the property list |
| mapping-body section order | protocol parser too lenient | `MappingParserGrammar.g4:33-40`: includes, elements, `MappingTests?`, `testSuites?` |
| `testSuites` `query:` key | fixture was never Legend | a test admits only `doc`/`asserts`/`data` |
| `dbQualifiedColumnRefInsideFunctionArgs` | as-written | `IsNull` canonicalisation, plus the enclosing-db elision |
| `groupByKeyWithJoinNavigation` | real bug | a nav's TERMINAL is relative to the NAV's own root db, not to whatever encloses it; `~groupBy` keys have no PM record to lift the qualifier onto, so they arrived with none and the terminal kept a `db::DB` the legacy parser elided — enough to stop `GroupBySynthesis#groupByOpsMatch` recognising the key |

**Where the oracle was WRONG, and what that taught.** `xstoreMissingComma`:
engine's rule HAS the EOF anchor the old comment denied, so the engine parser
rejects it — and the corpus contains it anyway
(`tests/mft/xStore/testMappingCrossStore.pure:238`), because legend-PURE's
compiler completes the rule and discards the rest. **legend-engine is not the
only Legend.** The reference parser answers "what does engine's Pure grammar
do", which is not the same question as "what must legend-lite read". The old
test's conclusion was right and its cited reason was false — both halves worth
remembering.

**Gaps the CORPUS proved were load-bearing** (all invisible until the switch
was live, because §8 compiles one global model and a single unreadable setup
file zeroes every family):

* the `Relation` class-mapping arm read only bare column names — no row
  expressions, no `EnumerationMapping <id>:` head. Both now mirror the legacy
  `parseRelationCols`, CARRIED on the record and NOT emitted (engine's
  `relationFunctionPropertyMapping` JSON has no field for either, and
  inventing one would break wire parity for every Relation mapping);
* `~func <fqn>` with no signature spelling ran a scan hunting for a `]`,
  swallowed the property lines whole and stopped at a later `Integer[1]`;
* postfix `->get('k', @Type)` in a relational property mapping — a legend-lite
  Variant extension, 10 engine tests, `PRelTypeRef` carried on our own record
  set.

**Gate 4 is red on HEAD and was before this work.** The committed ledger and
the local checkout have drifted (census 2,759 vs the ledger's 2,798; seven
families below baseline). The proof here is therefore DIFFERENTIAL: family for
family identical to HEAD except `tests/mapping/modelJoin` 41 -> 42, h2-exec
315 verified / **0 diverged**, corpus parse walls ZERO. The ledger is left
untouched rather than regenerated — regenerating it would bless a 103 -> 90
drop on `tests/mapping/relation` and hide whatever caused it.

### §M5 — the fixture oracle (2026-08-08)

`FixtureAdjudicationTest` points the reference parser at LEGEND-LITE'S OWN
test tree, split by `assertThrows` context: a negative fixture engine accepts
is our over-strictness, a positive one it refuses is our leniency. This is the
only tier that can see a disagreement about a form the corpus never contains,
which is exactly how three leniencies survived for months pinned by our own
tests. It costs ~1s and is now part of gate 8.

First measurement: **974 adjudicable fixtures, 700 agree, 268 lenient, 6
over-strict.** The 268 cluster by the reference's own message (see the test's
Javadoc); the one to chase is **13x bare `VARCHAR` without a size** — a
construct engine owns and requires a parameter for, so no carve-out applies.
The `Type/multiplicity parameters are not authorized in Legend Engine` rows
are the opposite and are FINE: legend-pure defines them, engine subsets them
away.

Two limits, stated so the next reader does not chase phantoms: the oracle jars
are 5.88.1 against a 5.92.1-SNAPSHOT checkout, so some apparent leniency is
version skew; and `parseModel` PARSES without compiling, so a fixture we refuse
at parse time and engine accepts at parse time is usually refused by engine a
phase later — all six over-strict rows have that shape.

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
