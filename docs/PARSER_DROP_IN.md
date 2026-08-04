# Can legend-lite's parser replace legend-engine's? — a feasibility study

> **Question asked.** Could legend-lite's hand-written parser be turned into a byte-identical,
> 100%-complete, faster drop-in replacement for legend-engine's production parser?
>
> **Everything below is measured**, on an idle machine, 2026-08-04, against
> `legend-engine 4.135.0` source with the real `legend-engine-language-pure-grammar:4.133.0`
> artifact on the classpath. No number here is derived from a sample share or an estimate unless
> it says so.

---

## 1. Verdict

**Three answers, because the question contains three different projects.**

**First, a disambiguation that changes the question.** There are **four** things called "a parser"
in these repos, not one: **(A)** the Legend DSL grammar parser (text → `PureModelContextData`),
**(B)** legend-pure's M3 parser (text → `CoreInstance` graph), **(C)** the SQL parsers, **(D)**
foreign-format parsers (GraphQL, Protobuf, Haskell, MongoDB). **(A) is the production parser for
the engine server** — and (A) and (B) never touch each other at runtime; the core grammar module
has *zero* legend-pure dependencies. Everything below is about (A).

**(a) "Replace *the* legend-engine parser" — no, and nobody should try.** "The parser" is not a
component; it is an ecosystem: **41 `*-grammar` Maven modules, 156 `.g4` files, 14,568 lines of
ANTLR, 165 Java files / 28,157 LOC of tree-walkers**, a 25-extension `ServiceLoader` SPI, and a
**444-class** protocol target. Plus, by upstream's own convention (*"Grammar changes: update both
parser and composer, and add a round-trip test"*), the **87-file composer** must move in lockstep.
That is a multi-year rewrite of somebody else's product.

**(b) "Replace the `###Pure` section parser behind the same interface" — plausible, and this is
where all the value is.** `###Pure` is 859 of the section markers in legend-engine's own corpus,
it is the hot path, and legend-lite already parses it at near element-level parity (§4). The
concrete target is `DomainParser` + `DomainParseTreeWalker.java` (**2,003 lines**) and
`M3ParserGrammar.g4` (280 lines) — not 156 grammars.

**And the drop-in seam exists and is clean** (§6.1). This is the single most encouraging finding
in the study: a third-party jar can take over `###Pure` **without forking the engine**.

**(c) "Byte-identical" — well-defined, and the answer hinges on one flag.** With
`returnSourceInformation=true` (the API default) it requires reproducing every ANTLR token's exact
line and column, including the offset introduced by the parser prepending a synthetic section
header. With `returnSourceInformation=false`, `NON_NULL` erases the field entirely and the problem
collapses to structural fidelity — which is exactly the mode upstream's own 359-test round-trip
suite runs in.

**The speed claim is real and larger than expected: 54.5× on the cleanest comparison available**
(§2). It survives every methodological attack I could mount at it. But — §6 — **I have not
established that parsing is a bottleneck in legend-engine's request path**, and that is the single
question that decides whether any of this is worth doing.

---

## 2. The performance measurement

### 2.1 Headline

294 real Legend-grammar files from legend-engine whose **only** section is `###Pure` — so neither
parser can skip anything — 2,772,223 chars, both parsers accepting all 294. Best-of-7 after 4
warm-up passes, one JVM, same run:

| | time | throughput |
|---|---:|---:|
| legend-engine (ANTLR) | **940.1 ms** | 2.95 MB/s |
| legend-lite (recursive descent) | **17.2 ms** | 160.80 MB/s |
| | | **54.5×** |

A second, independent population — 212 files from the relational corpus that both parsers accept —
gives **60.5×** (1128.7 ms vs 18.7 ms). Two disjoint corpora, same order of magnitude.

### 2.2 Four attacks on the number, and why it survives

**"You're comparing different files."** The first run had legend-engine accepting 212/400 and
legend-lite 400/400 — a meaningless 64×. Every number above is on the **intersection only**.

**"It's per-call overhead, not throughput."** Measured directly: 212 calls on the *smallest*
accepted file cost 15.7 ms, i.e. **0.074 ms fixed cost per call — 1% of the 1128.7 ms run.** The
gap is per-character, not per-invocation. (legend-lite's fixed cost is 0.0029 ms.)

**"legend-lite isn't parsing function bodies."** It is, eagerly, at parse time —
`ElementParser.java:946`, `SpecParser.parseCodeBlock(...)`, producing a full
`List<ValueSpecification>` AST held on the record. `FunctionDefinition.java:42-46` documents this as
a deliberate divergence from engine, which defers body parsing to a compiler-stage re-parse.

**"legend-lite skips sections it doesn't whitelist, so it's doing less work."** True in general —
and precisely why §2.1 restricts to `###Pure`-only files, where there is nothing it is permitted to
skip.

### 2.3 Where the engine's time actually goes — JFR, 12 iterations over the 212-file set

| | samples | share |
|---|---:|---:|
| `org.antlr.v4.runtime` | 4,090 | **89%** |
| `org.finos.legend.engine` (protocol construction) | 458 | 10% |

Top frames: `ParserATNSimulator.closure_` (701), `closureCheckingStopState` (526),
`execATNWithFullContext` (222), `ATNConfigSet.add` (221), `adaptivePredict` (125).

By stack attribution:

| | share of sampled stacks |
|---|---:|
| under `execATNWithFullContext` — **ALL(\*) full-context fallback** | **19.9%** |
| other `ParserATNSimulator` (SLL-mode ATN simulation) | 41.6% |
| lexing | 4.0% |
| everything else | 34.4% |

**This is the most important technical finding in the study.** The engine's parse cost is *not*
protocol construction — it is ANTLR's adaptive-prediction machinery, 89% of it. Which means the
speedup is a property of **hand-written recursive descent versus ATN simulation**, not of
legend-lite skipping work. Adding `SourceInformation` to legend-lite would add cost to the 10%
side, not the 89% side: **the win largely survives closing the gap.**

### 2.4 The competing cheap fix, and its honest ceiling

Upstream knows about `PredictionMode.SLL` — and uses it in exactly **two** places in the entire
repository (`DomainParseTreeWalker.java:1518,1549`, on the navigation and graph sub-parsers),
**never on the main parse path**. Every main grammar parser is constructed bare:
`new CodeParserGrammar(new CommonTokenStream(lexer))` (`PureGrammarParser.java:118`).

So there is an obvious two-line intervention: SLL with an LL fallback on error. **But the profile
caps its value at ~1.25×** — full-context fallback is only 19.9% of stacks, and the remaining
41.6% of ATN simulation is intrinsic to ANTLR's adaptive prediction and would remain.

That cuts both ways, and it matters: it means **upstream cannot cheaply close this gap**, so the
54× is not an artifact of a missing config flag. It also means anyone proposing the replacement
should first send upstream the two-line SLL patch, because it is nearly free and it is the honest
control experiment.

---

## 3. What "byte-identical" would actually mean

Byte-identity is **well-defined**, provided you pin
`ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()` — the mapper
the HTTP endpoint uses (`TransformGrammarToJson.java:63`), and one of two in the repo. The other
(`PureProtocolObjectMapperFactory.getNewObjectMapper()`) is a bare `new ObjectMapper()` with no
sorting and nulls included; a differential harness that picks it silently measures the wrong thing.

Rules under the canonical mapper:

| property | behaviour |
|---|---|
| Field order | `_type` first, then **alphabetical** (`MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`) |
| Nulls | **omitted** (`JsonInclude.Include.NON_NULL`, global) |
| Map keys | sorted (`ORDER_MAP_ENTRIES_BY_KEYS`) |
| Array order | **source order** — elements appended as sections are visited; `SectionIndex` appended last |
| Discriminator | `_type`, on 101 `@JsonTypeInfo` sites |

Three landmines:

- **~20 custom serializers sit on the `PureModelContextData` path** and bypass the generic rules
  entirely. `EnumValueMappingSourceValueSerializer` emits *either* an object *or* a bare JSON
  string depending on runtime type.
- **Jackson is pinned at 2.10.5**, where `SORT_PROPERTIES_ALPHABETICALLY` puts `@JsonCreator`
  properties *first, in declaration order*, then the rest alphabetically. `PureModelContextData`
  has a 20-arg `@JsonCreator`. "Alphabetical" is therefore not universal and must be validated
  per class.
- **Upstream's own house style is semantic, not byte, equality** — its `CLAUDE.md` says *"use
  `JsonUnit.assertJsonEquals` for JSON comparison (not `String.equals`)"*, with 56 call sites.
  Targeting byte-identity holds us to a stricter bar than upstream holds itself, which means
  upstream drift will break us in ways it does not break them.

### 3.1 The flag that decides the project's size

`GrammarToJson.java:60` — `@DefaultValue("true") @QueryParam("returnSourceInformation")`.

- **`true`** (default): every node carries `SourceInformation{sourceId, startLine, startColumn,
  endLine, endColumn}`. Byte-identity requires reproducing ANTLR's exact token positions —
  including the shift caused by `PureGrammarParser.java:110` prepending a synthetic
  `DEFAULT_SECTION_BEGIN` to the input.
- **`false`**: `NON_NULL` drops the field entirely. This is the mode upstream's **359 round-trip
  tests** use, with the comment *"NOTE: no need to get source information"*.

There is **no** stripping utility anywhere — no `withoutSourceInformation()`, no mixin. Suppression
happens at *build* time inside `ParseTreeWalkerSourceInformation.getSourceInformation`, which
returns `null` outright. The codebase's only way to compare two `PureModelContextData` ignoring
positions is to re-parse with the flag off.

### 3.2 The exact coordinate convention a replacement must reproduce

From `ParseTreeWalkerSourceInformation`:

```java
int startLine   = startToken.getLine() + lineOffset;
int startColumn = startToken.getCharPositionInLine() + 1 + (startToken.getLine() == 1 ? columnOffset : 0);
int endLine     = endToken.getLine() + lineOffset;
int endColumn   = endToken.getCharPositionInLine() + endToken.getText().length() + (endToken.getLine() == 1 ? columnOffset : 0);
```

**1-based lines; 1-based start column** (`+1` over ANTLR's 0-based `charPositionInLine`);
**inclusive end column** — `+ text.length()` with deliberately no `+1`. `lineOffset` applies to
every line; `columnOffset` applies **only when the token is on line 1**, which is the island-grammar
rebasing rule for embedded `#{ … }#` and `###Section` bodies.

Two further wrinkles: several nodes carry **two** source informations (`ClassMapping` has
`sourceInformation` *and* `classSourceInformation`; likewise `StereotypePtr.profileSourceInformation`,
`EnumValueMapping`) so the UI can point at a reference token separately from the element. And
`parseLambda` wraps input in `"function go():Any[*]{"` and applies a **negative** column offset of
exactly that prefix's length.

`SourceInformation` has **no `equals`/`hashCode`** — the `unknown` sentinel is compared by
reference throughout, which is the root of one of the two upstream bugs noted in §8.

---

## 4. How close is legend-lite, really?

Better than reading the code suggests, and worse in one specific place.

### 4.1 Element-level parity — measured

910 real Legend-grammar files with `###` sections; 372 parsed successfully by **both**:

| | |
|---|---:|
| identical element count, raw | **0** |
| **identical after removing `SectionIndex`** | **370 / 372 = 99.5%** |
| still differing | 2 |

Every mismatch was the same off-by-one: legend-engine emits a **`SectionIndex`** as a synthetic
`PackageableElement` in every `PureModelContextData` (372 of them across the corpus); legend-lite
has no such concept. Remove it and the two parsers agree on element extraction almost perfectly.

Elements the engine produced across that corpus: Class 4093, Function 1477, SectionIndex 372,
Association 228, Mapping 145, Enumeration 111, PackageableRuntime 16, DataElement 6, Profile 2.

**This is an element-count test, not a field-fidelity test.** It says the two parsers agree on
*what is there*. It says nothing about whether every field inside each element matches.

### 4.2 The three structural blockers

**Blocker 1 — no source positions, anywhere. This is the project.**
`grep -rn "SourceInformation" core/` returns **zero matches in the entire repository.**
`Token` is `record Token(TokenType, String text, int start, int end)` — char offsets, no line, no
column. `TokenStream` is three parallel `int[]`. Line/column is computed *only when throwing*, by
an O(offset) linear rescan (`TokenStreamCursor.java:285-301`). The finest position granularity
that exists is **one char offset per top-level element**, in a side index
(`ParsedModel.elementOffsets`) that is **discarded** when `PureModelContext.from(...)` rebuilds via
the 2-arg constructor (`PureModelContext.java:85-86`).

Adding it is not a serialization change. It touches `Token`, `TokenStream`'s storage layout, all
13 element records and all 19 `ValueSpecification` variants, and every one of ~300 construction
sites across the four parser files.

*Two latent bugs found in passing:* `TokenStreamCursor.java:295` starts columns at **0**;
`LegendCompileException.java:66` starts them at **1**. The codebase disagrees with itself about
column base. And legend-lite reports a **point**, where legend-engine reports a **range**.

**Blocker 2 — sections are erased in the lexer.** `Lexer.java:274-296`: a `###` header is skipped
and never emitted as a token; sections outside
`LEXABLE_SECTIONS = {Pure, Mapping, Relational, Connection, Runtime}` have their **entire body
silently discarded**. That includes `###Service` — 43 occurrences in legend-engine's corpus — which
would vanish with no error. There is no `SectionIndex` equivalent, and no per-section lexer mode,
so `###Relational` is tokenized with `###Pure`'s rules.

**Blocker 3 — coverage is roughly half.** legend-lite's `PackageableElement` has **13** variants.
Absent: `Diagram`, `Text`, `DataSpace`, `Binding`/`ExternalFormat`, `FileGeneration`,
`GenerationSpecification`, `Data`, `SectionIndex`, `Measure`/`Unit`, `flatData`, `serviceStore`,
and all non-relational connections but one.

### 4.3 The deliberate-drop list

A long tail of information legend-lite parses and throws away by design — stereotypes/tagged values
on associations, enums, enum values, databases and derived properties; property default values;
constraint `~enforcementLevel`/`~message`/`~owner`; function-level constraint blocks; graph-fetch
root class names; multiplicity type parameters. **Comments are not retained anywhere** — but they
are not retained by legend-engine either (`CoreLexerGrammar.g4` uses `-> skip`, not
`-> channel(HIDDEN)`), so that one is parity, not a gap.

One item is a genuine correctness hazard for any round-trip claim:
`MappingGrammarParser.java:432-440` **deliberately swallows the tail of an XStore block after a
missing comma** rather than erroring.

---

## 5. The oracle problem

You cannot land this without a differential test harness, and **upstream does not have one to
inherit.** What exists, ranked by usefulness:

| oracle | size | source info? | verdict |
|---|---:|---|---|
| `TestGrammarToJsonApi` — exact `String` equality on grammar→JSON | **25 assertions** | **yes** | The only byte-identity contract upstream pins. Small, `@Deprecated`, and the ground truth for field ordering. |
| `122390239DF2390/` — `(grammar.pure, protocol.json)` pairs | **26 pairs** | **yes** | The only structured corpus. **Unasserted** (`validate()` is commented out), last touched 2025-03-21, and **zero Mapping/Relational/Service coverage**. |
| Round-trip suite — text → protocol → JSON → protocol → text | **359 tests / 406 sites** | **no** (`returnSourceInfo=false`) | Largest and actively green, but a **text** oracle. |
| `testFrom`/`testTo` — JSON tree equality | **7 sites** | yes | Exactly the right shape, barely used. |
| Corpus-scale walker | **1**, assertion commented out | — | `TemporaryGrammarTest_WIP` also writes into the source tree when run. |

**The largest untapped seam: ~3,191 inline `###` grammar snippets across 253 Java test classes.**
Extracting those string literals would roughly 5× the file-based corpus, and the round-trip classes
already ship expected-output pairs beside them.

Available raw material: 3,000 `.pure` files in legend-engine, **668** of which are true standalone
grammar documents, plus 101 PMCD-shaped `.json`.

---

## 6. The risk that outranks every other

**I have not measured whether parsing is a bottleneck in legend-engine's request path.**

Everything in §2 says the parser is 54× faster. Nothing in this study says that matters. In a
typical Legend request the parser hands a `PureModelContextData` to the *compiler*, which builds the
`PureModel` graph — and compilation is normally far more expensive than parsing. If parse is 3% of
request latency, a 54× parser buys 2.9%, and no amount of correctness engineering makes that worth
replacing a production component.

**This must be measured before anything else is built.** It is a day of work: instrument
`grammarToJson` and a representative compile+execute path, and report parse as a share of wall
clock. Every other item in §7 is contingent on that number.

### 6.1 The seam — resolved, and it is a jar, not a fork

`PureGrammarParser.visitSection` dispatches with **extensions taking precedence over the built-ins**:

```java
SectionParser sectionParser = this.extensions.getExtraSectionParser(parserName);
if (sectionParser == null) {
    DEPRECATED_SectionGrammarParser legacyParser = parserLibrary.getParser(parserName, ...);
    if (legacyParser == null) { throw new EngineException("'" + parserName + "' is not a known section parser", ...); }
    section = legacyParser.parse(...);
} else {
    section = sectionParser.parse(codeSection, elementConsumer, parserContext);
}
```

`###Pure`, `###Mapping`, `###Connection` and `###Runtime` are the four **legacy** parsers,
hard-wired in the `PureGrammarParser` constructor into a `DEPRECATED_PureGrammarParserLibrary` and
*not* registered through the SPI. But the extension map is consulted **first**. So a jar that
registers a `SectionParser` with `getSectionTypeName() == "Pure"` via
`META-INF/services/...PureGrammarParserExtension` **wins over the built-in `DomainParser`** — no
patch, no fork, and it is removable by dropping the jar.

Two caveats: `PureGrammarParserExtensions.indexByKey` throws `IllegalArgumentException` at
*registry construction* if two extensions claim the same keyword, so this is exclusive; and
`PureGrammarParser.newInstance()` is constructed **per HTTP request**, so any per-instance state a
replacement holds must be cheap to build or shared statically.

### 6.2 Secondary risks

- **Upstream drift.** 156 grammar files under active development, and byte-identity is a stricter
  bar than upstream holds itself to (§3). Without a corpus-scale differential gate running against
  every upstream release, this decays silently.
- **The composer is coupled.** Upstream requires parser and composer to move together, and ~215
  grammar test classes assert text → JSON → text round-trip identity. A replacement parser that
  changes *anything* the composer round-trips through will surface as composer failures.

---

## 7. If it goes ahead — the staged plan

Each stage is independently valuable and independently abandonable.

**Stage 0 — measure the bottleneck (§6).** Gate everything on it. If parse is not a material share
of request latency, stop here and write that down.

**Stage 0.5 — send upstream the SLL patch.** Two lines, ~1.25× by the profile, and it is the
control experiment that proves the 54× isn't a config artifact. Costs nothing and builds standing.

**Stage 1 — build the differential harness.** 668 grammar files + ~3,191 extracted inline snippets,
both parsers, compare `PureModelContextData` **semantically** (`assertJsonEquals`) with
`returnSourceInfo=false`. This is the instrument; without it every later stage is guesswork. Expect
to find field-level divergence that §4.1's element-count test cannot see.

**Stage 2 — emit the protocol at all.** legend-lite has zero Jackson annotations and no
`PureModelContextData` anywhere. Its AST record *names and fields already mirror the protocol
"by mechanical renaming"* (`ValueSpecification.java:7-14`), so this is a transformer, not a
redesign. Add `SectionIndex` and the section model (Blocker 2) here — 99.5% element parity becomes
100%.

**Stage 3 — source positions (Blocker 1).** The big one. Widen `Token`/`TokenStream` to carry line
and column, thread a range through all 32 record types. Fix the 0-vs-1 column disagreement while
there. Only now is `returnSourceInfo=true` on the table.

**Stage 4 — coverage.** Close the element gap for the DSLs that matter, and decide, per item on the
§4.3 drop list, whether to retain it. Delete the XStore silent-swallow.

**Stage 5 — ship as an extension** (§6.1): a jar registering a `SectionParser` for `"Pure"`, which
takes precedence over the built-in `DomainParser` with no fork. Keep the differential harness as a
release gate against every upstream version, and expect to have to move the composer with it.

---

## 8. Two upstream bugs found in passing

Worth reporting regardless of whether this project proceeds, and worth *not* reproducing:

- **`EngineException.mayUpdateSourceInformation`** calls
  `this.sourceInformation.equals(SourceInformation.getUnknownSourceInformation())`, but
  `SourceInformation` defines **no `equals`** — so it degrades to reference identity and only fires
  for the literal singleton. Combined with the section-level guard
  `engineException.getSourceInformation() != null` (never null, because the field defaults to the
  sentinel), an `EngineException` thrown without position gets rethrown carrying a useless
  `X/0,0,0,0` instead of the enclosing section's real range.
- **`PureGrammarParserExtensions`** has the duplicate-key conflict messages for
  `indexTestAssertionDataParsers` and `indexMappingIncludeParsers` **swapped**.

Also: upstream's own `CLAUDE.md` documents `EngineErrorType` as
`COMPILATION`/`EXECUTION`/`PARSER`/`INTERNAL`. The enum has exactly **three** values —
`PARSER`, `COMPOSER`, `COMPILATION`. The execution axis is a separate `ExceptionCategory` enum.

---

## 9. What NOT to do

- **Don't aim at "the legend-engine parser."** 41 modules, 156 grammars, 669 protocol classes. Aim
  at `###Pure`, which is where the corpus and the time both are.
- **Don't start with byte-identity.** Start with `returnSourceInfo=false` semantic equality; it is
  upstream's own round-trip contract and it collapses the hardest blocker (§3.1).
- **Don't trust the element-count parity as evidence of fidelity** (§4.1). It compares *how many*,
  not *what's inside*.
- **Don't pick the wrong ObjectMapper** (§3). One of the two is non-deterministic and using it makes
  every comparison meaningless.
- **Don't assume the 26 checked-in golden pairs are current.** Nothing asserts them and they are
  ~16 months stale.
- **Don't skip Stage 0.** A 54× parser that is 3% of request time is a 2.9% win on a component
  somebody else maintains, and that is not a good trade at any level of engineering quality.
