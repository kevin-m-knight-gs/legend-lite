# The protocol migration, as ONE worklist (2026-08-09)

Everything left in the parser is the same three steps:

1. the **protocol parser** reads the construct into a `P*` record,
2. a **transform** (`FromProtocol` / `MappingFromProtocol`) turns it into the model,
3. the **`ElementParser` arm** points at (1)+(2),

and — where one exists — a **straight-to-model parser gets deleted**.

Walls, drop-in defects, out-of-scope sections and "migration legs" are four
names for gaps in that one pipeline; they differ only in which report
surfaces them. This file is the single ranked worklist.

## THE ONE SHAPE

`ElementParser.parseSingleElement` is now a pure dispatch table — every arm is
`case X -> xElement();`, nothing else — and every `xElement()` is tagged
**PROTOCOL-FIRST** or **STRAIGHT-TO-MODEL** in its javadoc. So the migration
state is readable at the work, and this document is its index rather than its
only record.

It previously carried FIVE shapes for the same operation: a bare transform
call, a transform behind a helper, a `yield` block threading `endOut`, the
same plus a section-line lookup and two catch clauses, and a straight-to-model
call. Two of those helpers were one-line wrappers over the protocol path that
read exactly like the un-migrated arms — which is how a reader (this one)
concluded that eight arms were un-migrated when the real number was four.

## The denominator: all 25 sections legend-engine can parse

Asked of engine's own registries by `EngineSectionRosterTest`, not grepped.
There are **TWO** registries and missing the second is how a denominator ends
up wrong: extension sections implement `SectionParser` and are
ServiceLoader-discovered, while the four CORE sections implement
`DEPRECATED_SectionGrammarParser` and are wired in by hand — so `Pure`,
`Mapping`, `Connection` and `Runtime` appear in NEITHER a ServiceLoader walk
nor a grep for section literals. `PureGrammarParser:153` dispatches every
`###X` through one lookup, so the roster is the union: **21 + 4 = 25**.

(A first cut of this table said 22, listed 25 names, and collapsed twelve of
them into two "1 each" rows. Every part of that was wrong.)

**Defects are attributed to the SECTION they occur in**, not to the section
their error message happens to name — a first cut counted only
`'X' is not a known section parser` and so reported `###Pure: 0` while 37 of
its files were failing on `Measure`. A section is not done because its name
stopped appearing in error text.

| # | section | defects | walls | oos | state |
|--:|---|---:|---:|---:|---|
| 1 | **Relational** | **0** | **0** | 0 | **DONE** — protocol-first (R3) |
| 2 | **Connection** | **51** | **30** | 0 | DUAL-PATH; protocol side exists and is better than the live one |
| 3 | **Pure** | **37** | 0 | 0 | mostly protocol-first; **Measure**, **native function**, **Primitive** are not |
| 4 | **Service** | **27** | 0 | **135** | straight-to-model, no protocol side |
| 5 | **Mapping** | **25** | **62** | 0 | protocol-first (M4); walls are other STORES + `include dataspace` |
| 6 | DataSpace | 13 | 0 | 51 | no section parser |
| 7 | Snowflake | 6 | 0 | 31 | no section parser |
| 8 | Diagram | 5 | 0 | 17 | carried opaque |
| 9 | Runtime | 3 | 0 | 0 | DUAL-PATH; protocol side exists |
| 10 | FileGeneration | 3 | 0 | 7 | no section parser |
| 11 | ExternalFormat | 2 | 0 | 77 | no section parser; 2nd biggest oos |
| 12 | ServiceStore | 1 | 0 | 32 | no section parser |
| 13 | MemSql | 1 | 0 | 7 | no section parser |
| 14 | BigQuery | 1 | 0 | 5 | no section parser |
| 15 | GenerationSpecification | 1 | 0 | 5 | no section parser |
| 16 | HostedService | 1 | 0 | 4 | no section parser |
| 17 | DataQualityValidation | 1 | 0 | 3 | no section parser |
| 18 | FunctionJar | 1 | 0 | 3 | no section parser |
| 19 | MongoDB | 1 | 0 | 3 | no section parser |
| 20 | Text | 1 | 0 | 1 | no section parser |
| 21 | Persistence | 0 | 0 | 53 | no section parser; 3rd biggest oos |
| 22 | Deephaven | 0 | 0 | 5 | no section parser |
| 23 | Elasticsearch | 0 | 0 | 1 | no section parser |
| 24 | QueryPostProcessor | 0 | 0 | 0 | no section parser; not seen in the corpus |
| 25 | **Data** | 0 | 0 | 0 | claimed, carried opaque |

**We claim 5 of 25 sections and only ONE is finished.** `###Relational` has
zero defects and zero walls. `###Pure` and `###Mapping` are routinely called
done — they are protocol-first, which is a different and weaker claim.

## The element arms inside the sections we claim

`✓` built · `—` absent · **DELETE** = a straight-to-model parser still live

| element | protocol parse | transform | arm | straight-to-model to DELETE |
|---|---|---|---|---|
| Class · Native Class | ✓ | ✓ | ✓ | — |
| Enum | ✓ | ✓ | ✓ | — |
| Profile | ✓ | ✓ | ✓ | — |
| Association | ✓ | ✓ | ✓ | — |
| Function | ✓ | ✓ | ✓ | — |
| Database | ✓ `DatabaseProtocolParser` | ✓ | ✓ | — (R3 deleted it) |
| Mapping | ✓ `MappingProtocolParser` | ✓ | ✓ | — (M4 deleted it) |
| Data | ✓ `parseData` | opaque | ✓ | — |
| **Measure** | ✓ `parseMeasureDefinition`:1207 | **—** | **—** | — |
| **Runtime** | ✓ `parseRuntimeProtocol`:2198 | **—** | **—** | **`parseRuntimeBody`:2758, 31 lines** |
| **Connection** | ✓ `parseConnectionProtocol`:2333 | **—** | **—** | **`connectionElement`, 104 lines** |
| **Service** | **—** | **—** | **—** | **`serviceElement`, 126 lines** |
| Native Function | partial | partial | model | 10 lines |
| Primitive | **—** | **—** | model | 15 lines |

**Three sections are DUAL-PATH today** — a protocol parser and a
straight-to-model parser over the same grammar, in the same file. That is what
`###Mapping` was before M4; it stayed invisible because the twins are
co-located rather than in separate classes, so grepping for shadow *parsers*
found nothing.

Connection's twins have already **diverged**: `parseConnectionProtocol` reads
`testDataSetupSqls`, while the model twin routes through a stringly-typed
`parseKeyValueBlock` that cannot represent an array, falls through to
`parseQualifiedName`, and dies in the TYPE parser — which is the whole reason
22 corpus files report `expected type name, got BRACKET_OPEN` from a
connection block. Divergence is the running cost of keeping a twin.

## The architecture this is converging on

`docs/GRAMMAR_EXTENSIBILITY.md` already states the target: **"the engine's
parser is a section dispatcher, not a grammar."** One parser per section,
registered by name, each taking raw section text and emitting protocol
elements.

We are half-way there and it is worth being precise about which half:

* `SectionGrammarRegistry` EXISTS and routes `###Name` → owner, with
  `ServiceLoader` overlays consulted after built-ins so an extension can
  shadow a built-in — the same rule engine uses.
* `com.legend.spi.SectionGrammar` declares the real surface,
  `parse(SectionSource, ElementSink)`.
* **But built-ins do not use it.** `SectionGrammarRegistry.BuiltIn.parse`
  throws — built-in sections parse through the monolithic `ElementParser`
  switch instead. So the seam is real for third parties and bypassed by us,
  which is the reverse of the dogfooding rule the registry's own javadoc
  states.

Finishing it means each built-in section becomes a `SectionGrammar` that owns
its elements — `PureSectionGrammar`, `RelationalSectionGrammar`,
`MappingSectionGrammar`, `ConnectionSectionGrammar`, … — and `ElementParser`
shrinks to the dispatcher. Then "is section X done?" is answerable by looking
at one class, the census below stops being a document that can drift from the
code, and adding `###Persistence` is a new class rather than a new arm in a
switch someone has to find.

## Ranked worklist

By section, because that is the unit of both the architecture and the
completeness question. Element-level work sits inside its section.

1. **Connection** (51 defects + 30 walls) — the biggest, and the protocol side
   already EXISTS and is better than the live model path. Transform, arm,
   −104 lines, and widen `PDatasourceSpec` (it permits only
   `PH2Local | PStaticSpec` while the model knows four and the corpus wants
   Snowflake/BigQuery/Databricks) to clear the walls. Ends a divergence that
   is actively producing wrong diagnostics.
2. **Pure — Measure** (~34 of Pure's 37) — protocol parse EXISTS and nothing
   calls it. Needs a model type, a transform, one arm.
3. **Runtime** (3 defects) — protocol parse EXISTS. Transform, arm, −31 lines.
   Cheap, and finishes the third dual-path section.
4. **Service** (27 defects + 135 oos) — biggest single item and the only one
   genuinely from scratch.
5. **Mapping's walls** (62) — mostly OTHER STORES (ServiceStore 26, MongoDB 1)
   and `include dataspace`, so this largely resolves as those sections land.
6. **DataSpace** (13 + 51), **ExternalFormat** (2 + 77), **Persistence**
   (0 + 53), **ServiceStore** (1 + 32), **Snowflake** (6 + 31), then the tail.

## Reading the numbers without fooling yourself

**`DEFECT` counts FILES, not causes.** A file leaves the list only when its
LAST gap closes. Closing a cause outright can move the total by zero — the
empty-enum fix closed 100% of its cause and moved 184 → 184 because both files
immediately blocked on their next gap. The `~src` fix moved 198 → 184 rather
than → 181 for the same reason. **Cause counts measure progress; the file
count measures coverage. Quote both or neither.**

**`OUT_OF_SCOPE` (440) is not a defect** — it is sections we have not claimed.
It becomes defect-shaped only once we claim them.

**The oracle is version-skewed.** Jars are 5.88.1 against a 5.92.1-SNAPSHOT
checkout, so a handful of `LENIENT` rows are the reference being older than
the corpus rather than us being wrong.
