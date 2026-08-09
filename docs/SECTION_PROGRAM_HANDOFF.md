# Section program — handoff (2026-08-09, HEAD `d5d67630`)

For a clean session. **Validate first, then work.** Everything below is
reproducible; nothing needs to be taken on trust, and several numbers in this
repo's history were wrong precisely because they were.

The goal: **every one of legend-engine's 25 sections parses through
legend-lite's protocol path, transformed into the model, with no second
parser anywhere.** Today 5 sections are claimed, 1 is finished.

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
| MATCH (byte-equal) | **25,474** | elements whose JSON is byte-identical to engine's |
| DIFF | **0** | a non-zero here is a BUG, not a gap |
| WALL | 104 | elements we REFUSE to produce (loud, named) |
| LITE_EXTRA | 0 | we never invent an element engine did not produce |
| PARSE_FAIL | 14 | |
| OUT_OF_SCOPE | 440 | sections we have not claimed — **not defects** |
| LENIENT | 55 | we accept, reference refuses |
| DEFECT | 184 | reference accepts, we refuse — **the coverage debt** |

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
| 2 | **Pure** | **1** (+5 islands) | 0 | 0 | one element short: **Measure** |
| 3 | **Connection** | **85** | **30** | 0 | **DUAL-PATH**; biggest item on the board |
| 4 | **Service** | 27 | 0 | **135** | straight-to-model, no protocol side |
| 5 | **Mapping** | 25 | **62** | 0 | protocol-first (M4); walls are other STORES + `include dataspace` |
| 6 | DataSpace | 13 | 0 | 51 | unbuilt |
| 7 | Snowflake | 6 | 0 | 31 | unbuilt |
| 8 | Diagram | 5 | 0 | 17 | carried opaque |
| 9 | Runtime | 3 | 0 | 0 | **DUAL-PATH**; protocol side exists |
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
protocol-first but blocked largely on OTHER sections. `###Connection` is 46%
of all remaining defects.**

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
| **Runtime** | ✓ `parseRuntimeProtocol`:2198 | — | — | `parseRuntimeBody`:2758, 31 lines |
| **Connection** | ✓ `parseConnectionProtocol`:2333 | — | — | `connectionElement`, 104 lines |
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

## 2.4 Architecture target

`docs/GRAMMAR_EXTENSIBILITY.md`: **"the engine's parser is a section
dispatcher, not a grammar."** One parser per section, registered by name,
raw section text in, protocol elements out.

Where it stands:
- `SectionGrammarRegistry` EXISTS, routes `###Name` → owner, with
  `ServiceLoader` overlays consulted after built-ins so an extension can
  shadow a built-in (engine's own rule).
- `com.legend.spi.SectionGrammar` declares `parse(SectionSource, ElementSink)`.
- **`BuiltIn.parse` THROWS.** Built-ins bypass the seam and go through the
  monolithic switch. The seam is real for third parties and unused by us —
  the inverse of the dogfooding rule its own javadoc states.

Finishing it: `PureSectionGrammar`, `RelationalSectionGrammar`,
`MappingSectionGrammar`, `ConnectionSectionGrammar`… each owning its elements,
`ElementParser` shrinking to the dispatcher. Then "is section X done?" is
answered by opening one class, and adding `###Persistence` is a new class
rather than a new arm in a switch someone has to find.

---

# PART 3 — THE WORK

## 3.1 The recipe (every item is the same shape)

1. **Adjudicate** the construct — §1.5/§1.6. Engine grammar, legend-pure, corpus.
2. **Protocol parser** reads it into a `P*` record.
3. **Transform** in `FromProtocol` / a section transform.
4. **Arm** points at (2)+(3), tagged PROTOCOL-FIRST.
5. **Delete** the straight-to-model twin if one exists.
6. **Emitter** arm if the record is new — but see §3.3 on carried-not-emitted.
7. **Gate**: `allgates.sh` green; DEFECT/LENIENT ratchets DESCEND.

## 3.2 Ranked worklist

1. **Connection — 85 defects + 30 walls.** Biggest by far, dual-path, and the
   protocol side already EXISTS and is BETTER than the live one. Needs a
   transform, an arm, −104 lines. Widen `PDatasourceSpec` (it permits only
   `PH2Local | PStaticSpec` while the model knows four and the corpus wants
   Snowflake/BigQuery/Databricks) to clear the 30 walls. Also unblocks much of
   `###Mapping`. **Strong candidate to be the FIRST real `SectionGrammar`** —
   doing it section-first lands the migration and proves the architecture in
   one move, instead of migrating into the switch and re-doing it later.
2. **Measure** — protocol parse EXISTS (`:1207`) and NOTHING calls it. Needs a
   model type, a transform, one arm. Finishes `###Pure`.
3. **Runtime — 3 defects.** Protocol parse EXISTS. Transform, arm, −31 lines.
   Cheap; retires the third dual-path section.
4. **Service — 27 defects + 135 oos.** Biggest single section and the only one
   genuinely from scratch.
5. **DataSpace** (13+51), **ExternalFormat** (2+77), **Persistence** (0+53),
   **ServiceStore** (1+32), **Snowflake** (6+31), then the tail.

## 3.3 Conventions you must not break

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

## 3.4 Corrections made this session (do not re-derive)

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

## 3.5 Related documents

- `docs/PROTOCOL_MIGRATION_CENSUS.md` — the census this handoff summarises
- `docs/GATES.md` — the chain, the ratchet table, the root-flag trap
- `docs/GRAMMAR_EXTENSIBILITY.md` — the section-dispatcher target
- `docs/PARSER_COMPLETENESS_PLAN.md` §M0–M5 — the Mapping migration, landed
