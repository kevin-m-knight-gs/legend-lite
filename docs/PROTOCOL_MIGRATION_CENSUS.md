# The protocol migration, as ONE worklist (2026-08-09)

Everything left in the parser is the same three steps:

1. the **protocol parser** reads the construct into a `P*` record,
2. a **transform** (`FromProtocol` / `MappingFromProtocol`) turns it into the model,
3. the **`ElementParser` arm** points at (1)+(2),

and — where one exists — a **straight-to-model parser gets deleted**.

Walls, drop-in defects, out-of-scope sections and "migration legs" are four
names for gaps in that one pipeline; they differ only in which report
surfaces them. This file is the single ranked worklist.

## The element arms

`✓` built · `—` absent · `DELETE` a straight-to-model parser still live

| element | protocol parse | transform | arm wired | straight-to-model to DELETE |
|---|---|---|---|---|
| Class | ✓ `parseClassDefinition` | ✓ `toClassDefinition` | ✓ | — |
| Native Class | ✓ | ✓ | ✓ | — |
| Enum | ✓ `parseEnumDefinition` | ✓ `toEnumDefinition` | ✓ | — |
| Profile | ✓ `parseProfileDefinition` | ✓ `toProfileDefinition` | ✓ | — |
| Association | ✓ `parseAssociationDefinition` | ✓ `toAssociationDefinition` | ✓ | — |
| Function | ✓ `parseFunctionProtocol` | ✓ `toFunctionDefinition` | ✓ | — |
| Database | ✓ `DatabaseProtocolParser` | ✓ `toDatabaseDefinition` | ✓ | — (R3 deleted it) |
| Mapping | ✓ `MappingProtocolParser` | ✓ `toMappingElement` | ✓ | — (M4 deleted it) |
| **Measure** | ✓ `parseMeasureDefinition`:1207 | **—** | **—** | — |
| **Runtime** | ✓ `parseRuntimeProtocol`:2198 | **—** | **—** | **`parseRuntimeBody`:2758, 31 lines** |
| **Connection** | ✓ `parseConnectionProtocol`:2333 | **—** | **—** | **`parseConnection`:2875, 104 lines** |
| **Service** | **—** | **—** | **—** | **`parseServiceDefinition`, 126 lines** |
| Native Function | partial (`toFunctionParams`) | partial | builds model | `parseNativeFunction`, 10 lines |
| Primitive | **—** | **—** | builds model | `parsePrimitiveExtension`, 15 lines |
| Data | ✓ `MappingProtocolParser.parseData` | n/a — carried opaque | ✓ (opaque) | — |

### What that table says

**Three sections are DUAL-PATH right now** — a protocol parser and a
straight-to-model parser over the same grammar, in the same file. This is
exactly the shape `###Mapping` was in before M4; it did not show up when
grepping for "shadow parsers" only because the twins are co-located rather
than in separate classes.

* **Connection** — and the twins have already DIVERGED. `parseConnectionProtocol`
  reads `testDataSetupSqls`; `parseConnection` routes through a stringly-typed
  `parseKeyValueBlock` that cannot represent an array, falls through to
  `parseQualifiedName`, and dies in the TYPE parser — which is why 22 corpus
  files report `expected type name, got BRACKET_OPEN` from a connection block.
  Divergence between twins is the cost of keeping them.
* **Runtime** — same pair, no known divergence yet.
* **Measure** — the protocol parser is already written and nothing calls it.
  A model type, a transform and one arm are missing. 34 corpus files report
  `unsupported top-level keyword` for it.

**Service is the only large element with NO protocol side at all** (126 lines
of straight-to-model), and it is simultaneously the biggest out-of-scope
section (135 elements) and the biggest unknown-section defect (27 files).

## The section arms (`###X`)

Ranked by drop-in defects. Every row is "no protocol parser exists":

| files | section | note |
|---:|---|---|
| 27 | Service | also 135 OUT_OF_SCOPE elements — the single biggest item anywhere |
| 13 | DataSpace | also 51 OUT_OF_SCOPE |
| 6 | Snowflake | app/M2M UDF sections |
| 5 | Diagram | 17 OUT_OF_SCOPE; we carry it opaque today |
| 3 | FileGeneration | |
| 2 | ExternalFormat | 77 OUT_OF_SCOPE |
| 1 each | Text · ServiceStore · MongoDB · MemSql · HostedService · GenerationSpecification | |

## Ranked worklist

Ordered by (files closed) ÷ (work), with the two "already half-built" items
first because they are the cheapest real wins on the board.

1. **Measure** — protocol parse EXISTS. Needs a model type, a transform, one
   arm. Touches ~34 defect files.
2. **Connection** — protocol parse EXISTS and is BETTER than the live model
   path. Needs a transform, one arm, and the deletion of 104 lines. Closes the
   22 `testDataSetupSqls` files, and ends a divergence that is actively
   producing wrong error messages. Widening the protocol's datasource
   vocabulary (`PDatasourceSpec` permits only `PH2Local | PStaticSpec`, while
   the model path knows four, and the corpus wants Snowflake/BigQuery/
   Databricks) also clears the **30 connection walls**.
3. **Runtime** — protocol parse EXISTS. Needs a transform, one arm, −31 lines.
4. **Service** — the biggest item and the only one that is genuinely from
   scratch: protocol parser, transform, arm, −126 lines. 27 defect files plus
   135 out-of-scope elements.
5. **The remaining sections** in the table above, in element order.

## Reading the numbers without fooling yourself

**`DEFECT` counts FILES, not causes.** A file leaves the list only when its
LAST gap closes. Closing a cause outright can move the total by zero — the
empty-enum fix closed 100% of its cause and moved 184 → 184, because both
files immediately blocked on their next gap. The `~src` fix moved it 198 →
184 rather than 198 → 181 for the same reason.

So: **cause counts measure progress; the file count measures coverage.** Quote
both or neither.

**`OUT_OF_SCOPE` (440) is not a defect** — it is sections we have not claimed.
It becomes defect-shaped only once we claim them.

**The oracle is version-skewed.** Jars are 5.88.1 against a 5.92.1-SNAPSHOT
checkout, so a handful of `LENIENT` rows are the reference being older than
the corpus, not us being wrong.
