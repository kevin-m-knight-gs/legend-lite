# Section program — handoff (2026-08-09)

For a clean session. **Validate first, then work.** Everything below is
reproducible; nothing needs to be taken on trust, and several numbers in this
repo's history were wrong precisely because they were.

**THE GOAL, stated as a burn-down:** every one of legend-engine's **25
sections** and **41 packageable element types** parses through legend-lite's
PROTOCOL path, is transformed into the model, is routed through
`SectionGrammarRegistry`, and has **no straight-to-model twin left anywhere**.

Today (final update 2026-08-09, **DEFECT ZERO**): **24 of 25 sections are
claimed by REGISTERED grammars** (only QueryPostProcessor — no grammar, no
corpus presence — remains a BuiltIn-less name): dedicated `SectionGrammar`s
for Connection, Runtime, Service, DataSpace, Persistence, Snowflake, the
shared `GenericKeyedSectionGrammar` for twelve small sections, and the RAW
`DiagramSectionGrammar` for the unlexable one. **The drop-in surface reads
everything the 5.88.1 reference reads.** Remaining program: the WIRE ledger
(WALL 33 + OUT_OF_SCOPE — byte-exact emission for the newly claimed
sections), `nativeFunctionElement`/`primitiveElement` (the last two
straight-to-model arms, neither dual), and the oracle-version-skew LENIENT
rows a jar upgrade retires.

Both denominators are pinned by gate 8 (`EngineSectionRosterTest`,
`EngineElementRosterTest`) so they cannot quietly drift when the oracle jars
move.

---

# PART 1 — VALIDATE (do this before believing anything)

## 1.1 The single most important trap

`engine/src/test/.../rcorpus/Corpus.java:47` reads the **system property**
`-Dlegend.engine.root`, defaulting to `$HOME/legend/legend-engine`. It does
**NOT** read the `LEGEND_ENGINE_ROOT` environment variable — that name exists
only for `tools/allgates.sh`, which converts it into the `-D` flag.

On this machine `$HOME/legend/legend-engine` is a **stale July tag** with
2,759 test functions against the real checkout's 2,798. Export the env var,
run `mvn` by hand, and you sweep the wrong corpus with no warning.

That cost an hour on 2026-08-08, produced a false "main is red / seven-family
regression" report, and **hid two real parse walls**.

**Three tells, in the order they appear:**

| tell | wrong checkout | right checkout |
|---|---|---|
| census line | `2759 total, 2538 runnable` | `2798 total, 2575 runnable` |
| h2-exec | `0 verified` | `319 verified, 0 diverged` |
| runtime | ~320s | ~90s |

**Rule: run the chain through `tools/allgates.sh`, which cannot make this
mistake.** If you must run `mvn` by hand, pass
`-Dlegend.engine.root=/Users/neemsandv/legend/legend-engine` explicitly.
Note `/Users/neemsandv/`, not `/Users/neema/`.

## 1.2 Validate the whole chain

```bash
cd /Users/neema/legend/legend-lite
LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine \
LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure \
GATES_LOG=/tmp/gates.log caffeinate -i bash tools/allgates.sh
grep -E "^G[0-9]_EXIT" /tmp/gates.log
```

Expect `ALLGATES_DONE — GREEN (gates: 1,2,3,4,5,6,7,8)` and all `_EXIT=0`,
in ~5.5 minutes.

| gate | expectation |
|---|---|
| 1 core | 1,675 tests, 0 failures (`clean` is load-bearing — NullAway binds to `default-compile`) |
| 2 install | required before 3–8; **`mvn -pl <mod> test` resolves core from `~/.m2`, not the reactor** |
| 3 engine suite | 2,729 tests, 0 failures, 19 skipped |
| 4 DuckDB corpus | census `2798 / 2575 / 223`, h2-exec `319 verified, 0 diverged`, scoreboard written |
| 5 h2 corpus | green (portability; writes no scoreboard) |
| 6 PCT full | 1,109 tests, 0 failures |
| 7 PCT h2modern | `Tests run: 348, Failures: 1, Errors: 22` — **this gate is inverted**, it goes red on improvement |
| 8 parser equivalence | 6 tests, 0 failures |

## 1.3 Validate the headline numbers

```bash
grep -E "MATCH \(byte|DIFF |WALL |PARSE_FAIL|coverage:" \
  parser-equivalence/target/equivalence-report.txt
grep -E "files in scope|MATCHED |LENIENT |DEFECT " \
  parser-equivalence/target/section-sentinel-report.txt
```

| metric | value | meaning |
|---|---:|---|
| MATCH (byte-equal) | **25,510** | elements whose JSON is byte-identical to engine's |
| DIFF | **0** | a non-zero here is a BUG, not a gap |
| WALL | 33 | elements we REFUSE to produce (loud, named) — was 104 at session start |
| LITE_EXTRA | 0 | we never invent an element engine did not produce |
| PARSE_FAIL | 14 | |
| OUT_OF_SCOPE | 440 | sections we have not claimed — **not defects** |
| MATCHED | 1,045 | was 875 at program start |
| LENIENT | 69 | we accept, reference refuses — ADJUDICATED rows only: oracle-version-skew (5.88.1 jars vs 5.92.1 corpus) and engine-subsets-pure, each named next to the re-frozen ratchets; every genuinely-lite leniency found on the way to zero was FIXED, not absorbed (join-type case, mapping-test formats, five negative persistence fixtures) |
| DEFECT | **0** | **ZERO (2026-08-09): the drop-in reads everything the reference reads.** Was 184 at session start. The burn-down is DONE; what remains is the WIRE-fidelity ledger (WALL 33 + OUT_OF_SCOPE) and the oracle-version-skew LENIENT rows that a jar upgrade retires. |

## 1.4 How to read those numbers without fooling yourself

**`DEFECT` counts FILES, not causes.** A file leaves only when its LAST gap
closes. Closing a cause outright can move the total by ZERO — the empty-enum
fix closed 100% of its cause and moved 184 → 184 because both files
immediately blocked on their next gap. The `~src` fix moved 198 → 184 rather
than → 181 for the same reason.

> **Cause counts measure progress. The file count measures coverage. Quote
> both or neither.**

**Any bucketing of the 184 MUST sum to 184.** Two attributions in this repo's
history did not sum, and both were wrong in ways that flattered a section.
One reported `###Pure: 37` by lumping every `unsupported top-level keyword`
into Pure, when 34 of 35 name CONNECTION element types. Reproduce with:

```bash
grep "^  DEFECT" parser-equivalence/target/section-sentinel-report.txt \
  | sed 's/.* :: //' | sed 's/\[[0-9]*:[0-9]*\] //' \
  | sed "s/'[^']*'/'X'/g" | sort | uniq -c | sort -rn
```

**The reference parser is version-skewed.** Oracle jars are 5.88.1 against a
5.92.1-SNAPSHOT checkout, so some `LENIENT` rows are the reference being older
than the corpus rather than us being wrong (the `relation-emit-models` set is
the known example).

## 1.5 The deepest lesson: the oracle answers a DIFFERENT question

`PureGrammarParser` answers **"what does legend-engine's grammar accept?"**
That is **not** the same as **"what must legend-lite read?"**

Proof, and it is not hypothetical. legend-engine's xStore rule has an EOF
anchor, so the engine parser REJECTS a missing comma. legend-**pure**'s
compiler accepts it, discards everything after the gap, and
`core_relational/relational/tests/mft/xStore/testMappingCrossStore.pure:238`
is written that way — with goldens that assume the dropped entries are absent.
A parser that "correctly" refused it could not read the corpus at all.

On 2026-08-08 the leniency was removed on the oracle's verdict and the corpus
immediately stopped compiling. **The old code was right and the justification
written next to it was false.** Both halves of that matter.

> **Adjudication procedure.** For any accept/refuse question:
> 1. what does legend-engine's grammar do? (run it — see §1.6)
> 2. what does legend-pure do? (does the corpus contain the form?)
> 3. if they disagree, the CORPUS decides, and you write down why.

## 1.6 How to ask the oracle a question

Add a scratch probe under `parser-equivalence/src/test/java/com/legend/equivalence/`:

```java
PureGrammarParser engine = PureGrammarParser.newInstance();
try { engine.parseModel("###Mapping\nMapping my::M ( ... )\n"); /* ACCEPT */ }
catch (Exception e) { /* REJECT: e.getMessage() names the valid alternatives */ }
```

Engine's error messages list the valid alternatives at that position, which is
usually the whole answer. **`parseModel` PARSES; it does not compile** — so a
construct we refuse at parse time and engine accepts at parse time is often
refused by engine one phase later. Check WHERE the rejection happens before
calling it our over-strictness.

## 1.7 The standing rules

**LENIENCY RULE (user, 2026-08-08).** Never allow leniency on a construct
Legend owns. If legend-engine **or** legend-pure require something, that is
hard. The only defensible supersets are things NEITHER defines — Pure features
engine subsets away (function types, `{T[1]->U[1]}`).

**Corollaries earned this session:**
- Being stricter than an engine **bug** is defensible; stricter than an engine
  **rule** is not. (`~filter` without `~src` NPEs in engine at
  `ClassMappingFirstPassBuilder:127`; we refuse it in words.)
- A cited justification is not a checked one. Three leniencies carried
  comments citing engine parity for the opposite of what engine does.
- Drop a leniency from **every** parser, not just the protocol one.

---

# PART 2 — STATE

## 2.1 The denominator: 25 sections, from TWO registries

`EngineSectionRosterTest` (gate 8) reads both and pins 25.

| registry | interface | discovery | n |
|---|---|---|---:|
| extensions | `SectionParser` | `ServiceLoader` | 21 |
| **core** | `DEPRECATED_SectionGrammarParser` | hand-wired | **4** |

The core four — `DomainParser`→Pure, `MappingParser`→Mapping,
`ConnectionParser`→Connection, `RuntimeParser`→Runtime — appear in **neither**
a ServiceLoader walk **nor** a grep for section literals (the names are behind
constants). Both earlier attempts to count sections missed them and reported
22. `PureGrammarParser:153` dispatches every `###X` through one lookup, so the
roster is the union.

## 2.2 All 25 sections

Defects attributed to the section they OCCUR in; sums to 184.

| # | section | defects | walls | oos | state |
|--:|---|---:|---:|---:|---|
| 1 | **Relational** | **0** | **0** | 0 | **DONE** — protocol-first (R3) |
| 2 | **Pure** | 0 (+5 islands) | 0 | 0 | **Measure wired 2026-08-09** (MeasureDefinition model + transform + arm; the byte-parity-proven `parseMeasureDefinition` finally has a model-path caller) |
| 3 | **Connection** | 3 | 0 | 0 | **first real `SectionGrammar`** (2026-08-09); twin deleted; widening LANDED (Snowflake/Spanner/Databricks/BigQuery specs, SnowflakePublic/GCP/ApiToken/MiddleTier auths, quoteIdentifiers — probed byte-exact, +15 MATCH, −30 WALL); left: foreign flavors only (Deephaven 2, MongoDB 1) |
| 4 | **Service** | 0 | 0 | **135** | **real `SectionGrammar`** (2026-08-09): Single AND Multi executions, embedded anonymous runtimes (raw), owners/title/ownership/postValidations, ExecutionEnvironment elements; twin deleted; oos stays (no wire shape claimed — `PService` emission walls) |
| 5 | **Mapping** | 25 | **62** | 0 | protocol-first (M4); walls are other STORES + `include dataspace` |
| 6 | DataSpace | 0 | 0 | 51 | **real `SectionGrammar`** (2026-08-09): contexts/executables/diagrams typed, testData+supportInfo raw; no wire shape claimed |
| 7 | Snowflake | 6 | 0 | 31 | unbuilt |
| 8 | Diagram | 5 | 0 | 17 | carried opaque |
| 9 | Runtime | 0 | 0 | 0 | **real `SectionGrammar`** (2026-08-09); twins deleted; multi-binding + embedded islands + connectionStores all parse |
| 10 | FileGeneration | 3 | 0 | 7 | unbuilt |
| 11 | ExternalFormat | 2 | 0 | 77 | unbuilt; 2nd biggest oos |
| 12 | ServiceStore | 1 | 0 | 32 | unbuilt |
| 13 | MemSql | 1 | 0 | 7 | unbuilt |
| 14 | BigQuery | 1 | 0 | 5 | unbuilt |
| 15 | GenerationSpecification | 1 | 0 | 5 | unbuilt |
| 16 | HostedService | 1 | 0 | 4 | unbuilt |
| 17 | DataQualityValidation | 1 | 0 | 3 | unbuilt |
| 18 | FunctionJar | 1 | 0 | 3 | unbuilt |
| 19 | MongoDB | 1 | 0 | 3 | unbuilt |
| 20 | Text | 1 | 0 | 1 | unbuilt |
| 21 | Persistence | 0 | 0 | 53 | unbuilt; 3rd biggest oos |
| 22 | Deephaven | 0 | 0 | 5 | unbuilt |
| 23 | Elasticsearch | 0 | 0 | 1 | unbuilt |
| 24 | QueryPostProcessor | 0 | 0 | 0 | unbuilt; absent from the corpus |
| 25 | **Data** | 0 | 0 | 0 | claimed, carried opaque |

**`###Pure` and `###Relational` are effectively finished. `###Mapping` is
protocol-first but blocked largely on OTHER sections.**

**2026-08-09 re-census after the Connection migration (sums to 162):** the
per-section defect attribution above predates the migration — clearing a
file's connection gap surfaces its NEXT gap, so the buckets moved: `'X' is
not a known section parser` 85 (Service/DataSpace/Snowflake/Diagram files),
`unsupported class mapping type` 19 (Mapping), **Runtime-twin causes 32**
(embedded `RelationalDatabaseConnection`/`ModelChainConnection` islands 14,
`duplicate connection binding for store` 12 — engine allows several
connections per store, the model twin's `Map` cannot —, unknown Runtime keys
6, incl. `connectionStores`), connection spec/auth widening 11, foreign
connection flavors 3, DSL islands 3, embedded data kinds 3,
`include dataspace` 2, misc 4 (`expected type name, got PAREN_OPEN` 2, one
top-level keyword, one column-reference).

## 2.3 Element arms inside claimed sections

`ElementParser.parseSingleElement` is a pure dispatch table; every arm is
`case X -> xElement();` and every `xElement()` is tagged **PROTOCOL-FIRST** or
**STRAIGHT-TO-MODEL** in its javadoc. Read the code, not this table, if they
ever disagree.

| element | protocol parse | transform | arm | straight-to-model to DELETE |
|---|---|---|---|---|
| Class · Native Class · Enum · Profile · Association · Function | ✓ | ✓ | ✓ | — |
| Database | ✓ `DatabaseProtocolParser` | ✓ | ✓ | — |
| Mapping | ✓ `MappingProtocolParser` | ✓ | ✓ | — |
| Data | ✓ `parseData` | opaque | ✓ | — |
| **Measure** | ✓ `parseMeasureDefinition`:1207 | — | — | — |
| **Runtime** | ✓ `RuntimeSectionGrammar` | ✓ `FromProtocol.toRuntimeElement` | ✓ (+ section dispatch) | **deleted 2026-08-09** |
| **Connection** | ✓ `ConnectionSectionGrammar` (all 4 flavors) | ✓ `FromProtocol.toConnectionElement` | ✓ (+ section dispatch) | **deleted 2026-08-09** |
| **Service** | — | — | — | `serviceElement`, 126 lines |
| Native Function | partial | partial | model | 10 lines |
| Primitive | — | — | model | 15 lines |

**Three sections are DUAL-PATH** — protocol parser AND straight-to-model
parser over the same grammar, in the same file. This is what `###Mapping` was
before M4; it hid because the twins are co-located rather than in separate
classes, so grepping for shadow *parsers* found nothing.

Connection's twins have **already diverged**: `parseConnectionProtocol` reads
`testDataSetupSqls`; the model twin routes through a stringly-typed
`parseKeyValueBlock` that cannot represent an array, falls through to
`parseQualifiedName`, and dies inside the TYPE parser. That is the entire
reason ~22 corpus files report the nonsense `expected type name, got
BRACKET_OPEN` **from a connection block**. Divergence is the running cost of
keeping a twin.

## 2.4 The element denominator: 41 element types

`EngineElementRosterTest` (gate 8) reads
`PureProtocolExtension.getExtraProtocolToClassifierPathMap()` — the same map
the JSON layer uses, so it cannot drift from what engine can serialise — and
pins **41**.

**The burn-down is 25 sections x the element types each contains.** A section
is not done because its header parses; it is done when every element it can
contain round-trips. Our protocol coverage of the 41:

| we have a protocol record | we do NOT |
|---|---|
| Class · Enumeration · Profile · Association · Function · **Measure** · Database · Mapping · DataElement · SectionIndex | **PackageableConnection** · **PackageableRuntime** · Diagram · Text · Service · Binding · DataSpace · Persistence · PersistenceContext · ServiceStore · MongoDatabase · DeephavenStore · Elasticsearch7Store · SnowflakeApp · SnowflakeM2MUdf · HostedService · FunctionJar · BigQueryFunction · MemSqlFunction · DataQuality (+ variants) · ExternalFormatSchemaSet · FileGenerationSpecification · GenerationSpecification · ExecutionEnvironmentInstance · RelationalMapper · AuthenticationDemo · DeephavenApp · BigQueryFunctionDeploymentConfiguration · MemSqlFunctionDeploymentConfiguration |

**10 of 41.** Note `Measure` HAS a protocol record and is unwired.
(Verified 2026-08-09: `PConnection` and `PRuntime` ARE `Protocol.Element`
records with emitter arms — the earlier "no P* record reachable from the
emitter" caveat was wrong. `PackageableConnection` is now fully wired;
`PackageableRuntime` still lacks its transform/arm.)

## 2.5 EVERY dual parser, and the deletion list

A **dual parser** is a straight-to-model parse method whose grammar is also
read by a protocol parser. They are the migration's actual debt: while two
exist they diverge, silently, and the divergence surfaces as a nonsense error
somewhere else entirely.

Verified inventory (updated 2026-08-09 after the Connection AND Runtime
migrations — every dual parser is GONE; recheck with the greps below):

| # | straight-to-model | lines | protocol twin | status |
|--:|---|---:|---|---|
| 1 | `nativeFunctionElement` | 10 | partial | straight-to-model only |
| 2 | `primitiveElement` | 15 | — none | straight-to-model only |

**Both must go**, and each needs a protocol parser first. Deleted this
session: `connectionElement`, `parseModelConnectionBody`,
`parseKeyValueBlock`, `parseRuntimeBody`, `singleConnectionRuntimeElement`,
`parseRuntimeConnections`, `parseEmbeddedJsonModelConnection` (the
2026-08-08 census mis-attributed the last as a Connection helper — it was
the RUNTIME model path's island parser), and `serviceElement` (126 lines,
replaced by `ServiceSectionGrammar`).

Outside core: `engine/src/main/java/com/gs/legend/parser/PureModelParser.java`
(2,573 lines) is a THIRD full parser with its own `parseMapping`,
`parseDatabase`, `parseConnection`, `parseRuntime`, `parseService`. It is live
(24 test files, plus `BuiltinClassRegistry`/`JavaCodeGenerator`/
`PureModelBuilder`). **User decision 2026-08-09: the whole `engine` module is
being deleted, so leave it.** Do not spend effort retiring it.

Recheck the inventory rather than trusting it:

```bash
grep -nE "^\s+private [A-Z][A-Za-z]*(Definition|Element)? [a-z][A-Za-z]*\(" \
  core/src/main/java/com/legend/parser/ElementParser.java
grep -nE "Protocol\.P[A-Za-z]+ parse[A-Za-z]*\(" \
  core/src/main/java/com/legend/parser/ElementParser.java
```

Anything in the first list whose grammar appears in the second is a dual
parser. **A green build is not evidence they agree** — Connection's twins were
both green and disagreed about arrays for months.

## 2.6 Architecture: split by section, route through the registry

`docs/GRAMMAR_EXTENSIBILITY.md`: **"the engine's parser is a section
dispatcher, not a grammar."** That is the target, and the machinery already
exists — built-ins just bypass it.

### What exists

`SectionGrammarRegistry` routes `###Name` → owner. Built-ins are registered
first, then `ServiceLoader` overlays, **so an extension claiming a built-in
name WINS** — engine's own shadowing rule, and precisely what lets
legend-lite's `LegendLiteSectionParser` take over `###Pure` inside a real
engine. An unknown section becomes a reportable
`ParsedModel.unclaimedSections()` entry rather than lexer silence.

`com.legend.spi` is the future `legend-lite-spi` artifact and is THREE types
only — keep it that way, and never let an implementation reach into core:

```java
public interface SectionGrammar {
    String name();                                   // the ###Name it owns
    default boolean lexable() { return false; }      // may the SHARED lexer tokenise it?
    void parse(SectionSource src, ElementSink out);  // raw text in, elements out
}
```

`lexable()` is the important one: a foreign grammar (Diagram colour literals,
an opaque DSL) must return `false` and receive raw text, because it never
adopts our lexer. Built-ins return `true` and flow through the main token
pipeline.

### What does NOT exist

**Updated 2026-08-09: `###Connection` is now a REAL registered grammar** —
`com.legend.parser.section.ConnectionSectionGrammar`, routed by
`ElementParser.parseModel`'s claimed-section dispatch (a LEXABLE section
whose registered grammar implements the core-internal
`LexableSectionGrammar` parses as a WHOLE through it, from the shared token
stream; the SPI `parse(SectionSource, ElementSink)` surface re-lexes and
drives the SAME methods). The remaining FIVE built-ins
(`Pure, Mapping, Relational, Runtime, Data`) are still `BuiltIn` stubs whose
`parse` THROWS — the bypass shrinks one section at a time.

### Migrating ONE section to the seam — the recipe

Do this per section; it is the same shape every time.

1. **New class** `XSectionGrammar implements SectionGrammar` in
   `com.legend.parser.section`, `name()` = `"X"`, `lexable()` = true for
   built-ins.
2. **Move**, do not copy, the section's element parsing into it. The protocol
   parser for each element moves too, or is called from it.
3. `parse(SectionSource, ElementSink)` emits PROTOCOL elements to the sink;
   the model transform stays on the `FromProtocol` side of the seam.
4. **Register** it in `SectionGrammarRegistry.build()` in place of the
   `BuiltIn(name)` stub, so built-ins and overlays go through ONE path.
5. **Delete** the corresponding `ElementParser` arm and any straight-to-model
   twin from §2.5.
6. **Gate.** Byte parity (`MATCH`, `DIFF 0`) must not move; `DEFECT`/`LENIENT`
   must not rise.

**Do it for `###Connection` first.** It is the biggest cluster (85), it is
dual-path, and its protocol side already exists and is better than the live
one — so section-first lands the migration AND proves the architecture in one
move, instead of migrating it into the switch and re-doing it later.

When every built-in is a `SectionGrammar`, `ElementParser` is a dispatcher,
"is section X done?" is answered by opening one class, and adding
`###Persistence` is a new class rather than a new arm in a switch.

# PART 3 — THE WORK

## 3.1 The recipe (every item is the same shape)

1. **Adjudicate** the construct — §1.5/§1.6. Engine grammar, legend-pure, corpus.
2. **Protocol parser** reads it into a `P*` record.
3. **Transform** in `FromProtocol` / a section transform.
4. **Arm** points at (2)+(3), tagged PROTOCOL-FIRST.
5. **Delete** the straight-to-model twin if one exists.
6. **Emitter** arm if the record is new — but see §3.3 on carried-not-emitted.
7. **Gate**: `allgates.sh` green; DEFECT/LENIENT ratchets DESCEND.

## 3.2 Definition of DONE (all three, per section)

1. every element type the section can contain has a protocol record, a
   transform and a wired arm;
2. the section is a registered `SectionGrammar`, not an `ElementParser` arm;
3. **no straight-to-model parser for it exists anywhere** (§2.5).

A section that satisfies (1) alone is "protocol-first", which is what
`###Pure` and `###Mapping` are. It is not done.

## 3.3 Ranked worklist

1. ~~**Connection**~~ — **LANDED 2026-08-09** as the first real
   `SectionGrammar`: DEFECT 184 → 162, byte parity untouched, twin deleted.
   The §2.6 recipe is now PROVEN — reuse it verbatim.
2. ~~**Runtime**~~ — **LANDED 2026-08-09** as the second `SectionGrammar`:
   DEFECT 162 → 142; `RuntimeDefinition` now holds several connections per
   store (`Map<String, List<String>>`), non-json embedded islands hoist to
   anonymous `$`-sigil elements registered by `ModelBuilder.ingestRuntime`,
   `connectionStores` parses; all three runtime twins deleted.
3. ~~**Connection spec/auth widening**~~ — **LANDED 2026-08-09** (probe:
   `ZConnWidenProbe`): +15 MATCH, WALL 104 → 74, every spec/auth defect
   bucket at zero. DEFECT stayed 142 — those files' NEXT gap is an
   unregistered section (`'X' is not a known section parser` grew 97 → 108),
   which is worklist items 5–6's territory.
4. ~~**Measure**~~ — **LANDED 2026-08-09**: `MeasureDefinition` + transform
   + arm. `###Pure` is done.
5. ~~**Service**~~ — **LANDED 2026-08-09** as the fourth `SectionGrammar`
   (no wire shape claimed; oos stays until the harness scope is claimed).
6. ~~**DataSpace**~~ — **LANDED 2026-08-09** as the fifth `SectionGrammar`.
7. **Remaining defect landscape (101):** `'X' is not a known section
   parser` ~40 (Snowflake 12, Diagram 6, ExternalFormat 4,
   FileGeneration 3, tail), `unsupported class mapping type` 19
   (ServiceStore/XStore mapping arms), `expected type name, got
   ISLAND_OPEN` remnants, DSL islands 3, embedded data kinds 3,
   `include dataspace` 2, foreign connection flavors 3, misc. Re-derive
   with the bucketing grep in §1.4 before working — the sums must hit 101.
8. Then: **ExternalFormat** (77 oos), **Persistence** (53 oos),
   **ServiceStore** (32 oos), **Snowflake** (31 oos), and the tail.

## 3.4 Conventions you must not break

**Carried-not-emitted.** Some fields ride the protocol record and are
deliberately NOT emitted, because engine's JSON has no slot and inventing one
breaks byte parity for every affected element: Relation `enumMappingId`/`expr`,
Operation `extends` (engine parses and drops it — two `//TODO`s in
`OperationClassMappingParseTreeWalker`), `testSuitesSource`.

**As-written model conventions.** A database qualifier equal to the enclosing
database elides to null; `isNull(x)` and `x is null` collapse to one node.
Model-level only — `DIFF 0` proves they never reach the wire.

**legend-lite supersets** (engine cannot read these; no oracle can check them):
`->get('k', @Type)` in a relational property mapping (`PRelTypeRef`, pinned by
`RelationalTypeRefEmissionTest` which states outright that it is not parity),
and the clean-sheet function-form mapping (`CleanSheetProtocolShapeTest` — the
corpus has ZERO of these, so it is the arm's ONLY net).

**Gate 8 owns the fixture oracle.** `FixtureAdjudicationTest` points the
reference parser at legend-lite's OWN test fixtures, split by `assertThrows`
context. It ratchets on distinct KINDS (21), not fixture count — an earlier
version counted fixtures and turned red for *writing tests* of an
already-counted construct. Its top actionable finding is **13× bare `VARCHAR`
with no size**, which engine requires a parameter for.

## 3.5 Corrections made this session (do not re-derive)

| claim | reality |
|---|---|
| "main is red, 7-family regression" | measurement artifact of the stale checkout; main was green |
| "the migration is complete" | true of Mapping/Relational, stated unscoped |
| "8 arms build the model directly" | 4 — two were one-line wrappers over the protocol path |
| "22 sections" | 25; two registries |
| "###Pure has 37 defects" | 1; the 34 were Connection element types |
| `~src` required on Pure mappings | OPTIONAL — cost 17 files |
| empty Enum illegal | legal; the value list is optional |
| `prop: EnumerationMapping: expr` is a feature gap | a LENIENCY in both parsers |
| `*Enum: EnumerationMapping` must be refused | OUR over-strictness; engine accepts and ignores the star |
| xStore missing comma must be refused | legend-pure accepts; the corpus depends on it |

Every defect introduced this session was caught by a gate once the gates were
run correctly. That is evidence for the gates, not for the author — the errors
were overwhelmingly in REPORTING, not in the parser.

## 3.6 Related documents

- `docs/PROTOCOL_MIGRATION_CENSUS.md` — the census this handoff summarises
- `docs/GATES.md` — the chain, the ratchet table, the root-flag trap
- `docs/GRAMMAR_EXTENSIBILITY.md` — the section-dispatcher target
- `docs/PARSER_COMPLETENESS_PLAN.md` §M0–M5 — the Mapping migration, landed
