# Merge-readiness audit — the parser, adversarially (2026-08-07)

**The question:** if legend-lite's parser were proposed to FINOS legend-engine tomorrow, what would sink it?

**Scope: the parser only.** `com.legend.{lexer, parser, protocol}` plus the `values`/`error` leaves,
emitting `PureModelContextData` JSON. The compiler, resolver, lowering, SQL generation and
execution are **not** part of this review — a maintainer reviewing a parser PR does not inherit
them.

**Method.** Seven agents: five red team (integration/SPI, semantics, provenance, maintenance,
inward extensibility), one blue team building the strongest honest case *for*, one auditing the
equivalence harness itself. Two standing rules in every brief: **legend-lite's `docs/*.md` are
not evidence** — prior audits found them full of false claims, and several are falsified again
below — and **no sampling**; every census covers its whole population. Where an agent could
execute rather than read, it did: the drop-in was built and run inside the real engine, the
differential was run over all 7,219 corpus sources, the extraction was attempted, the clean
clone was built.

**Anchors.** legend-lite at `f4618969`+; legend-engine checkout `943d38b3dc2` (resolves to
`4.137.0-36`); reference parser jars pinned at **4.133.0**; legend-pure `d00cfd5ba`.

---

## §0 — The verdict

**The parity work is real. The drop-in claim is not, and the reason is not fixable by finishing
the burn-down.**

Three independent agents reproduced the gate: **24,508 elements byte-identical against a live
in-process legend-engine parser, zero divergence**. The extraction was attempted and works. The
artifact was packaged, registered through the engine's own `META-INF/services`, loaded, won the
dispatch ahead of `DomainParser`, and produced byte-identical spans on every construct tested.
That is a genuine engineering result and nothing below should be read as diminishing it.

But the same measurements establish four things that each independently block a merge, and one
of them cannot be answered with more work:

1. **legend-engine cannot compile it.** The engine targets Java 8 bytecode and actively enforces
   it. legend-lite is 224 records and 22 sealed hierarchies. This is a rewrite, not a port —
   and the rewrite voids the equivalence evidence.
2. **The parser accepts 74.9% of what the engine rejects**, including silently swallowing
   unknown `###Section` headers and discarding their contents.
3. **The error channel is not compatible in any degree** — 0 of 570 messages match, and 75.5% of
   engine error spans are structurally unrepresentable in legend-lite's exception type.
4. **The maintenance trade is 20,698 hand-written lines replacing 1,193 lines of declarative
   grammar**, against an upstream shipping 8.25 releases a month.

The blue team, working independently and instructed to build the strongest case *for*, reached
the same conclusion: **do not propose the parser for merge in its current form.** Its
recommended path — bug reports first, then the harness, then an optional out-of-tree extension
jar — is §10.

---

## §1 — What is genuinely proven

Stated first and without hedging, because the objections that follow are specific and bounded
rather than a general indictment.

- **24,508 elements byte-identical, with complete accounting.** Verified independently by three
  agents. The reference is a live `PureGrammarParser.newInstance()` (`ParserEquivalence.java:74`),
  not captured goldens. The comparison is `String.equals` on output from the engine's own
  `ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()` — the
  mapper its HTTP endpoint uses. An independent census counted 24,600 reference elements;
  24,508 matched + 87 out-of-scope + 5 drained = 24,600 exactly. **`LITE_MISSED` is genuinely 0.**
- **The seam works.** Packaged as a `PureGrammarParserExtension` and run against the real 4.133.0
  engine: it loads, wins dispatch, and produces byte-identical paths and `sourceInformation` on
  8/8 constructs. Mixed `###Pure` + `###Relational` files compose correctly and the engine's own
  composer still round-trips the result.
- **`sourceInformation` fidelity is excellent.** Thirteen adversarial probes — section line
  offsets, island column offsets, `###Pure` at line 1, two Pure sections split by a `###Mapping`,
  multi-line lambdas, CRLF, tabs, astral-plane emoji — **all match**.
- **Operator precedence matches byte-for-byte**, including faithful reproduction of the engine's
  own self-contradictory arithmetic mis-association.
- **The clean-room claim holds mechanically.** Zero shared code shingles between the 62-file
  contribution surface (12,097 windows) and 8,000 upstream Java files, at every threshold
  including an identifier-blind pass. The similarity harness was validated first: run
  legend-pure against legend-engine it finds 381 matched runs including a 157-line verbatim
  block. The zeros are real.
- **Dependency reduction is large and verifiable.** Running the engine's Pure parser needs
  **55 jars / 41.9 MB**. The extracted slice is **1.2 MB with zero third-party jars** — it does
  not even use Jackson; `ProtocolEmitter` hand-builds JSON into a `StringBuilder`.
- **The clean clone builds in 34 seconds**, all six modules, no local state.
- **The tightening set is small** — 160 inputs (3.2% of what the engine accepts), mostly in
  unbuilt sub-grammars.

---

## §2 — The wall: legend-engine cannot compile this

`legend-engine/pom.xml` sets `maven.compiler.release=8` (`:146`), enforces build JDK
`[11.0.10,12),[17,18)` (`:147`) — **21 is excluded** — and runs an active
`enforceBytecodeVersion maxJdkVersion 1.8` rule (`:559-560`). legend-lite is bytecode major 65.

Compiling the extracted parser at engine-permitted levels:

```
--release 8   ->  831 errors      (the engine's actual setting)
--release 11  ->  830 errors      (75 records, 22 sealed, 35 instanceof patterns, 19 switch rules)
--release 17  ->    9 errors      (switch patterns, deconstruction patterns)
```

224 of 430 core files use records. **This is not a port; it is a rewrite** — and a rewrite that
de-records and de-seals the parser invalidates the byte-parity evidence, which would have to be
re-earned from scratch.

The engine cannot move: it targets Java 8 consumers. This objection is not defeated by finishing
`###Data`, or by any amount of further parity work. **It is a packaging problem, and it dictates
the strategy in §10.**

---

## §3 — Correctness: what the parser does with input the engine refuses

Measured by a live differential over all 7,219 corpus sources — every source, both parsers,
no sampling.

| | engine ACCEPTS | engine REJECTS |
|---|---|---|
| **lite accepts** (shipping `parse`) | 4,789 | **1,700** |
| **lite rejects** | **160** | 570 |

**74.9% of everything legend-engine refuses, legend-lite accepts** — **92.3% (1,140/1,235) on
real `.pure` files**.

### 3.1 The shipping parser is not the tested parser

`legendStrict` is set only in `ElementParser.at()` and `parseStrict()` — both harness-only
entry points. `Compiler.parseSources:145` calls plain `parse()`. **642 corpus inputs are
accepted by the shipping parser and rejected by the tested one.** Whatever the rejection gate
proves, it proves about a code path no user runs. **Fix this before quoting any leniency number.**

### 3.2 Unknown `###Section` headers are silently swallowed — 226 corpus hits

```
###Service                        -> engine REJECTS  | lite ACCEPTS, 0 elements
###Mappng   (a typo)              -> engine REJECTS  | lite ACCEPTS, 0 elements
###Nonsense + ###Pure Class a::B  -> engine REJECTS  | lite ACCEPTS, 1 element
```

`###Service` alone is 78 hits; `###ExternalFormat` 37, `###Snowflake` 22, `###Persistence` 18,
`###DataSpace` 17, plus 13 more. A user who typos a header, or uses any DSL legend-lite does not
implement, gets a **successfully-parsed, silently empty model**. No wall, no warning.

This is the sharpest correctness objection in the audit. It is also a direct violation of the
project's own "loud walls over wrong rows" tenet, at the front door.

Two further silent-acceptance cases were found by A/B against vanilla inside the real engine:
trailing garbage after a valid class (`this is not legend at all !!!`) parses clean, and
`^my::A obj(x='hi')` parses with the element dropped. **Recomposing the second yields a
different program than went in** — silent data destruction in an SDLC round-trip. Root cause is
architectural: the parser scans for top-level marker tokens rather than parsing a grammar, so
unrecognised input is invisible. Token-scanning has no concept of "unconsumed input".

### 3.3 Nine valid upstream models crash the parser

Of the 160 tightenings, **9 are not clean rejections but internal crashes** — 4
`IllegalArgumentException`, 3 `NullPointerException`, 2 `IndexOutOfBoundsException`. By
legend-lite's own taxonomy (`error/LegendCompileException.java`), those types are "reserved for
genuine internal invariant violations (our bugs)". These are parser bugs reachable from valid
upstream models.

### 3.4 The error channel is not compatible in any degree

Over the 570 inputs both parsers reject:

| metric | result |
|---|---|
| byte-identical message | **0 / 570** |
| identical after stripping the `[l:c] ` prefix | **0 / 570** |
| same start line **and** column | 228 / 570 (40.0%) |
| same start line only | 342 / 570 — **40% differ on the line** |

And structurally, over all 2,270 engine parser rejections:

| engine span shape | n | lite can express? |
|---|---|---|
| point `[l:c]` | 556 (24.5%) | yes |
| same-line range `[l:c1-c2]` | 1,274 (56.1%) | **no** |
| multi-line range | 440 (19.4%) | **no** |

**75.5% of engine parser errors carry a range `ParseException` cannot represent.** There is no
`endLine`, no `endColumn`, no `sourceId`, no error-type enum. Studio squiggles, LSP diagnostics
and CI log parsers all consume that range. This is a missing field on the error type, not a
polish gap.

Inside the engine it is worse: the bridge throws a plain `RuntimeException`, so
`EngineException.findException` returns null and the engine falls back to
`sectionSourceInformation` — **every parse error squiggles the whole section**. Messages also
leak internals (`"SPI bridge failed on my::A"`). This one is **cheap**: `ParseException` already
carries `line()`/`column()`; mapping it to `EngineException` + `SourceInformation` is ~15 lines.

---

## §4 — The instrument

The gate's core discipline is better than most internal suites — `LITE_MISSED` must be 0, an
empty run fails, the corpus may not shrink, and the two-directional drain is real engineering.
Three limits matter for a merge conversation.

### 4.1 The denominator is constructed

`ParserEquivalence.compare` emits one `REFERENCE_REJECTED` verdict and returns when the
reference throws — **legend-lite is never invoked**. That fires on **2,270 of 7,219 sources
(31.4%)**, which is precisely the population where leniency lives. Only **68.6% of files and
46.5% of corpus characters** are adjudicated at all.

### 4.2 The denominator moves with harness configuration, not parser completeness

The harness loads 3 grammar jars. Adding two more, freely available and version-matched
(`data-space-grammar`, `text-grammar` @ 4.133.0), moved the reference element count
**24,600 → 24,865**. legend-lite's matched count stayed at 24,508, so "100.0% of comparable"
became **98.56%**. The full engine has ~20 more extension grammars.

**A reviewer's first instinct will be to add their own extension jars.** Disclosing this
yourself is worth far more than having it found.

### 4.3 Section coverage is 5 of 25, and the report reads as if it weren't

The corpus contains 25 distinct `###` headers; legend-lite claims five. `###Service` (110 files),
`###ExternalFormat` (65), `###Persistence` (52), `###DataSpace` (33) and others have **zero
accepted files — never adjudicated once**. The harness reports them as `REFERENCE_REJECTED`,
which reads like an engine limitation rather than a coverage gap.

Also: `WALL` is not a failure. The gate stays green with any number of walls (currently 10). The
pass condition is "no diff on what we choose to emit", not "we emit everything". And
`RejectionParityTest` gates **43 of 424 available pins**, excludes all non-Pure sections and all
COMPILATION errors, and its own header says messages are "deliberately NOT compared".

### 4.4 Fault sensitivity

*(Mutation-testing results pending — the agent is running. This section will be completed with
the kill rate and every surviving mutation explained from harness source. Until then, the gate's
demonstrated property is that it accounts for every element it sees, not that it would catch a
fault deliberately hidden from it.)*

---

## §5 — Extensibility, in both directions

### 5.1 Outward — legend-lite into legend-engine

Proven to work for `###Pure`, and only `###Pure`. Conformance against the engine's contract:

| contract | methods | legend-lite implements |
|---|---|---|
| `PureGrammarParserExtension` | 8 | **1** (`getExtraSectionParsers`) |
| section grammars | 26 | **1** (`Pure`) |
| `PureGrammarComposerExtension` | 12 | **0** |
| `CompilerExtension` | 43 | **0** |
| `PureProtocolExtension` | — | **0** |

**There is no composer at all** — no `"###"` is emitted anywhere in `src/main`; the pipeline is
one-way text→JSON. The repo's own contribution guidance requires "update both parser and
composer, and add a round-trip test" for grammar changes. And **414 `"PARSER error at"`
assertions across 42 engine test files** pin exact positions and text that legend-lite matches
none of.

### 5.2 Nothing ships

`parser-equivalence/` has **no `src/main` directory**; `LegendLiteSectionParser` is
package-private with a private constructor. Separately, **core has no driver that turns text
into a `PureModelContextData`** — the code that enumerates element sites and assembles the
document lives in the *test* module, keyed by a hardcoded `int` site-kind and a 10-name `_type`
array. Whatever seam is opened, that driver must move into core first.

### 5.3 Inward — third parties into legend-lite

A real ServiceLoader SPI exists in `src/main` (`com/legend/spi/`, wired at
`SectionGrammarRegistry.java:57-62`) and is genuinely invoked. But **four walls, each proven by
running a probe jar:**

- **The lexer owns section identity, not the registry.** `Lexer.LEXABLE_SECTIONS:275` is
  `private static final` and never consults the registry. An extension declaring
  `lexable() == true` — the documented way to use the shared lexer — is dropped with **zero
  elements and zero diagnostics**.
- **"Extensions WIN over built-ins" is false.** Registering a grammar named `"Mapping"` shows the
  map lookup wins but the built-in still runs. Shadowing is structurally impossible while the
  lexer owns the list.
- **Opaque elements are a dead end.** `ElementSink.accept(fqn, protocolJson)` produces an
  `OpaqueElementDefinition` that `ProtocolEmitter.element()` has no arm for and no type path to.
  A third-party section can contribute nothing to the product.
- **`###Service` is implemented but unroutable** — `case SERVICE ->` exists, tokens exist, but
  `"Service"` is in neither hardcoded list. Two lists that must agree, don't.

**The first three collapse to one ~15-line fix**: delete `LEXABLE_SECTIONS`, have
`skipSectionHeader` ask the registry.

### 5.4 The sealed-vs-pluggable question, answered

Of 23 sealed roots in the parser surface, **eight correspond name-for-name to methods on the
engine's `PureGrammarParserExtension`** — `PConnectionValue` ↔ `getExtraConnectionParsers`,
`PMilestoning` ↔ `getExtraMilestoningParsers`, `PEmbeddedDataValue` ↔ `getExtraEmbeddedDataParsers`,
and so on. The engine's SPI surface is a list of exactly the places its authors learned had to be
open. legend-lite sealed that list.

The resolution: **seal the root, and make one permitted variant the open one.** You keep
javac-enforced exhaustiveness at every `default`-less switch — the property the engine *cannot*
have, and whose absence its `ExecutionNodeVisitor` (17 `default` methods throwing "Not
implemented!") demonstrates the cost of — while permitting third-party extension. The engine
arrived at the same pattern from the other direction with `ClassInstance` under
`ValueSpecification`. legend-lite sealed the root and shipped no equivalent slot, which is the
strictly-worse combination.

Keep fully sealed: `PRelOp`, `PMapper`, `TypeExpression`, `Multiplicity`, `Realization`,
`PureDateLiteral`, `PureTimeLiteral` — closed vocabularies where exhaustiveness earns its keep.

### 5.5 The keyword table

Not a live collision bug — all 57 keywords are soft (all present in `IDENTIFIER_TOKENS`), and 14
probes including `Class my::A { type: …; query: …; and: … }` and `Class my::Table` all parse.
The barrier is that the mitigation is **global and closed**: adding one keyword needs three
coordinated core edits, and a token minted for one section is live in every other.

Measured against the engine: **592 keyword literals across its grammars, 106 claimed by more than
one, and 63 that map to different token names in different sections** — `name` means 12 different
things, `type` 5, `query` 4. Those 63 cannot be represented in one flat table.

---

## §6 — Provenance and paperwork

The clean-room claim holds (§1). What does not exist is the paperwork a FINOS contribution
requires:

- **2,111 commits, zero with `Signed-off-by`.** No DCO, no CLA.
- **No `LICENSE`, `NOTICE` or `CONTRIBUTING` file**, and no `<licenses>` element in any of the
  six poms. The Apache-2.0 claim exists only inside 163 file headers.
- **56 of the 62 contributed files have no licence header** — including all of `lexer/`,
  `protocol/spec/`, `values/`, `error/`, and `ElementParser`/`SpecParser`/`ProtocolEmitter`.
- **H2 is `compile` scope in `core`** (MPL-2.0/EPL-1.0).
- **The `TokenType` naming question has no answer in the tree.** 50 of 53 shared keywords use a
  token *name* byte-identical to upstream's ANTLR token (`MAPPING_TESTABLE_SUITES`,
  `SERVICE_AUTO_ACTIVATE_UPDATES`, `ALL_VERSIONS_IN_RANGE`). Keyword *strings* must match — they
  are the language. Token *names* are a free internal choice, so identical names are evidence the
  `.g4` files were read and transcribed. Entirely legitimate for a compatibility layer;
  completely uncited. **This is the sharpest provenance question a reviewer will ask.**

The gold standard already exists in the tree: `parser/EngineQuirks.java` names the upstream
method, file path and line range, explains the reproduced behaviour, and records the decision
date. Nothing else reaches that bar.

---

## §7 — Maintenance and ownership

- **Parser surface: 62 files / 23,194 LOC.** Full compile closure **105 files / 26,900 LOC**
  (a narrower protocol-only slice is 59 files / 17,485 LOC). Zero third-party runtime deps.
- **Separability holds** — `Typer`, `StoreResolver`, `Lowerer`, `MappingNormalizer`,
  `sql/dialect/*`, `builtin/Pure` and `Compiler` are all absent from the closure, confirmed by
  explicit BFS probe.
- **But there is a 4-package cycle** (`model` ↔ `parser` ↔ `protocol` ↔ `protocol.spec`) — they
  move as one unit. And `model/` is simultaneously the donor's own spine (55 non-parser
  importers), so extraction forks legend-lite's AST vocabulary and hands upstream a second one.
- **Extraction is one file away.** All 82 compile errors in the protocol-only extraction landed
  in `ElementParser.java`, which interleaves the contributable protocol path with the internal-model
  path. Everything else compiles standalone.
- **Freshness.** 42% of closure LOC is 0–3 days old; **75% was touched today or yesterday**.
  `ProtocolEmitter` is three days old with 58 commits since; `MappingProtocolParser`,
  `DatabaseProtocolParser` and `MappingEmitter` were first committed today.
- **Bus factor 1** — 2,158 of 2,163 authored commits are one person.
- **Rot rate: 99 releases in 12 months (8.25/month); 14 releases in the 26 days since the
  4.133.0 pin; 20 `.g4` files touched in that window**, including every core grammar legend-lite
  mirrors. A language-wide `'''…'''` documentation feature landed a month after the pin, touching
  **every** declaration form — legend-lite rejects all of it, faithfully, because it is faithful
  to the pin.
- **11 ratchet constants** to re-baseline by hand on any upstream bump.
- **The permanent obligation:** `ProtocolEmitter` reproduces Jackson 2.10's
  `SORT_PROPERTIES_ALPHABETICALLY` + `NON_NULL` + creator-ordering **by hand**. Every upstream
  change to `ObjectMapperFactory`, every new `@JsonInclude`, every renamed field becomes a silent
  byte diff — owned by us, not by the grammar authors who caused it.

---

## §8 — Compatibility claims in our own source that are false

Distinct from everything above, and the fastest way to lose a reviewer's trust: **source comments
that assert things about legend-engine which are not true of legend-engine.**

| site | claim | verdict |
|---|---|---|
| `ElementParser.java:93-97` + `ServiceDefinition.java:40-41` (decision **D-2**, 5 throw sites) | the engine "silently `skipToSemicolon`'s" unknown keys | **FALSE.** The engine rejects. `skipToSemicolon` appears **zero times in FINOS** — it is a method in legend-lite's *own* `com.gs.legend` prototype. |
| `MappingGrammarParser.java:1367` | "Trailing comma tolerated (engine accepts it)" | **FALSE**, with control: `EnumerationMappingParserGrammar.g4:19` has no such alternative. |
| `ElementParser.java:386-393` | the engine tolerates a stray top-level `)` | **FALSE.** The cited corpus file is a legend-pure M3 resource read by a different parser. |
| `AppliedFunction.java:29-57`, `AppliedProperty.java:17-26`, `FunctionDefinition.java:25-46`, `ServiceDefinition.java:27-42` | four javadocs headed "Deliberate divergence from engine's *X*", twice quoting "engine's own doc" | **MISATTRIBUTED.** None of the named members exist in FINOS; FINOS's `AppliedFunction`/`AppliedProperty` have no javadoc. The quotes are from legend-lite's own prototype. One claim — "Engine skips the `[testSuites]` block entirely" — is affirmatively false. |
| `SectionGrammar.java:14-16`, `SectionGrammarRegistry.java:57-59` | extensions shadow built-ins | **FALSE** (§5.3), and it cites the outward capability as precedent for an inward one that does not work. |
| `EngineQuirks.java` | the grep-able index of deliberate engine-bug reproductions | holds **one** constant, while two further sites in `DatabaseProtocolParser` are labelled `ENGINE QUIRK (probed)` and unregistered. |

**Two of these false premises are load-bearing for real leniency.** And `EngineQuirks`'s single
entry freezes an upstream parser bug as permanent semantics in an unconditional `static final
boolean` with no switch and no detector — correct today, undetectably wrong the day FINOS fixes
`DomainParseTreeWalker.processOp`.

---

## §9 — The honest claim

The sentence a PR description could defend, verbatim from the blue team:

> For the 24,508 `###Pure`/`Mapping`/`Relational`/`Runtime`/`Connection` elements that
> legend-engine 4.133.0's own parser produces from the 46.5% of the legend-engine and legend-pure
> corpora it accepts, legend-lite emits byte-identical `PureModelContextData` JSON with zero
> divergences and complete accounting; and for `###Pure` specifically, an engine running
> legend-lite behind its own `SectionParser` extension point produces byte-identical whole-model
> JSON on 4,051 files.

Everything outside that sentence — the other 20 sections, error messages, rejection behaviour,
and the 53.5% of corpus text neither parser adjudicates — is unproven.

**And the performance claim must be restated.** Measured by allocation over the whole corpus:

| path | B/char | vs engine |
|---|---|---|
| raw lex+parse → records | 38.0 | 41.9× less |
| + JSON emission | 63.5 | 25.0× less |
| **end-to-end via the engine's own SPI** | **612.4** vs 1,554.9 | **2.54× less** |

The SPI path serializes to a JSON string and hands it to the engine's Jackson mapper, which eats
93% of the advantage. **2.54× allocation / ~3× wall is the defensible number.** Quoting 32.9×,
52× or 41.9× for a drop-in quotes a number for a code path that does not exist. Similarly, the
grammars legend-lite's five sections actually replace total **1,193 lines** of `.g4`, not 14,659
— most of those files are Haskell, Protobuf, GraphQL and SQL.

---

## §10 — Recommended path

Ranked by value-to-threat ratio. This is the blue team's ordering and I endorse it.

**1. Bug reports plus failing tests — this week.** Near-zero cost, immediate credibility. Two
findings are ready:

- **Operator-precedence mis-association**, root-caused: `DomainParseTreeWalker.java:1857-1874`.
  When the accumulator is a relational comparison, the branch at `:1863-1867` replaces the
  comparison's **entire RHS**, where the non-relational branch at `:1869-1874` correctly descends
  into the RHS collection and replaces only its last element. One missing level of descent.
  Executed: `2 + 3 * 4` → `plus(2, times(3,4))` correct, but `1 < 2 + 3 * 4` →
  `lessThan(1, times(plus(2,3), 4))` — `1 < 20` instead of `1 < 14`. Silently changes arithmetic
  in any `a < b + c * d` predicate.
- **The engine's own serialize/deserialize round-trip is not a fixed point** on 8 files — e.g.
  `ColSpec classInstance multiplicity` is dropped by the deserializer. Found by legend-lite's
  harness, independent of legend-lite's parser.

**2. Contribute the differential harness.** The most valuable artifact here, and not a
consolation prize. Upstream has **no differential parser oracle**. Cost: one test-scope module,
no production dependency, 25s runtime. They get the corpus enumerator (7,219 sources including
3,765 Pure snippets auto-extracted from their own test Java), the byte comparator with
two-directional drain, and a **self-comparison mode** that needs no second parser at all and has
already found a real engine bug.

**3. Optional out-of-tree extension jar, `###Pure` only.** Zero cost to upstream —
`getExtraSectionParsers` is their own extension point and needs no core change. Delivers 2.54×
allocation on model loading. Blocked on the fixes below.

**4. In-tree single-section replacement.** Same value as (3), much higher threat. No reason to
prefer it.

**5. Full parser replacement.** Not defensible today. Do not propose.

### Fixes, each tied to the objection it defuses

| # | Fix | Defuses |
|---|---|---|
| 1 | **Reject unknown `###Section` headers loudly**; make the section parser reject any top-level token it did not consume | §3.2 — the sharpest correctness objection |
| 2 | **Test the parser that ships** — either make `legendStrict` the production default or gate on `parse()` | §3.1 — invalidates the current leniency number |
| 3 | **Close leniency 742 → 0** (or state the residue and why each is safe) | §3 |
| 4 | **Give `ParseException` `endLine`/`endColumn`/`sourceId`**, and map it to `EngineException` + `SourceInformation` in the bridge (~15 lines for the bridge half) | §3.4 — 75.5% of spans currently unrepresentable |
| 5 | **Build error-message/position parity measurement** | §3.4 — currently zero evidence |
| 6 | **Re-run the gate with every available grammar jar; publish the honest denominator; re-baseline against HEAD** | §4.2, §7 — pre-empts the reviewer's first instinct |
| 7 | **Split `ElementParser`** into protocol and internal-model halves | §7 — the artifact is not contributable until this lands |
| 8 | **Delete `Lexer.LEXABLE_SECTIONS`**; make the registry the single authority | §5.3 — three walls, ~15 lines |
| 9 | **Correct the six false compatibility claims in §8**; register the two unregistered quirks | §8 — trust |
| 10 | **Restate the performance claim as 2.54×** | §9 |
| 11 | **Paperwork**: DCO, `LICENSE`/`NOTICE`/`CONTRIBUTING`, headers on the 56 bare files, a provenance note on `TokenType` | §6 |

Fixes 1, 2, 8 and 9 are cheap and should land regardless of whether anything is ever proposed
upstream — they are defects in legend-lite's own terms.

---

## §11 — Honest gaps

- **Mutation testing is incomplete** (§4.4). The gate is known to account for every element it
  sees; whether it would catch a deliberately hidden fault is unmeasured at the time of writing.
- **No agent ran the engine's own grammar test modules** with the extension installed — the
  414 `"PARSER error at"` assertions are counted, not executed. Blocked on §2: building those
  modules needs a JDK the drop-in cannot run on.
- **The 4.133.0 pin vs the `4.137.0-36` corpus** means the parser being imitated and the source
  being parsed are four minor versions apart. Nobody re-ran the differential against a
  version-matched pair.
- **Compiler-stage semantics were explicitly out of scope** and are audited separately in
  `COMPILER_STAGE_AUDIT_2026_08.md`. If more than the parser is ever proposed, that surface has
  its own divergence set.
- **`nlq/` carries unattributed third-party data** (Yale Spider dev set) and an AGPL-3.0 model
  input (OpenBB). Irrelevant to a parser PR; live if the repo is ever donated whole.
