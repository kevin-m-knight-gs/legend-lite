# Parser drop-in — the delivery plan

> **Companion to `PARSER_DROP_IN.md`**, which holds the feasibility evidence. This is the plan.
>
> **Branch:** `parser/drop-in`. Measurements taken 2026-08-03/04 against `legend-engine`
> `d0b4c3a2f68` and `legend-pure` `63a4b2c68` (both pulled to origin/master 2026-08-04).

---

## 1. The goal, and the one constraint that shapes everything

**Goal.** Replace legend-engine's grammar parser with legend-lite's — 100% complete, byte-identical,
production drop-in — as stage one of progressively replacing legend-engine (parser → compiler →
sqlgen/plangen → execution).

**The constraint.** *We keep our tenets. We only output the same thing.*

Upstream's protocol is mutable public-field POJOs, Jackson `@JsonSubTypes` rather than sealed
hierarchies, nulls throughout, and ~20 bespoke serializers. **None of that gets to infect
legend-lite.** The protocol is a **serialization contract**, not a design constraint on our types.

| | upstream's protocol | ours |
|---|---|---|
| mutability | mutable public fields | **100% immutable records** |
| variance | Jackson `@JsonSubTypes` | **sealed interfaces with `permits`** |
| dispatch | runtime type-id lookup | **javac-enforced exhaustive switch, no `default ->`** |
| unknown shape | silently absent / null | **throws** (AGENTS.md invariant 4) |
| position | mutable `sourceInformation` field | immutable component, set at construction |

**Everything upstream-shaped is quarantined in one class: the emitter.** That is "one owner per
behaviour" applied — no other class in legend-lite learns what upstream's JSON looks like.

### 1.1 The decision, in one line

**Clean-room reimplementation of the protocol. No dependency on `legend-engine-protocol-pure`.**
`core` keeps zero engine dependencies in pom *and* source. The equivalence harness carries upstream
artifacts at **test scope only**, to generate reference bytes.

### 1.2 Why clean-room is the easier path here

Taking the dependency would mean reverse-engineering `SORT_PROPERTIES_ALPHABETICALLY`, Jackson
2.10's creator-properties-first quirk, global `NON_NULL`, four `NON_EMPTY` overrides and ~20 custom
serializers — then staying pinned to Jackson 2.10 forever to preserve field ordering.
**Hand-rolled emission is easier to make byte-identical than configuring Jackson to match another
Jackson.** We write bytes in the order we observe; the harness says when we are wrong.

### 1.3 Why we are doing this at all — not speed

| reason | measured |
|---|---|
| **Memory** | 1,281 B allocated per source char vs 39 — **32.9×** (re-measured 2026-08-05; see PARSER_DROP_IN.md §0.1); 3.6 GB to parse 2.8 MB; +31.5 MB retained to warm the parser |
| **Generated code** | 132 of 1,508 jar classes are ANTLR output but **46% of the bytes**; 156 `.g4` / 14,568 lines deleted |
| **Build** | `antlr4-maven-plugin` at `generate-sources` in 41 modules |
| **Currency** | ANTLR pinned 4.8-1 (2020); today it can only move with all generated code |
| **Debuggability** | recursive descent vs an ATN simulator |
| speed | 54.5× — real, and the weakest of the six |

**There is no latency gate.** The project is justified at 0% speedup.

---

## 2. Architecture

### 2.1 The three layers

```
  text
    |   ONE parser: one lexer, one recursive-descent core, one grammar
    v
  com.legend.protocol.*          <- sealed, immutable records. OUR tenets.
    |                               SourceInfo inline, set at construction.
    +--> ProtocolEmitter --------> bytes   <- the ONLY upstream-shaped code
    |
    +--> FromProtocol --------> com.legend.model.*  <- legend-lite's compiler,
                                                          unchanged
```

- **One parser, one output.** Byte-identity by construction, not parallel maintenance.
- **`ProtocolEmitter` is a switch expression over the sealed hierarchy with no `default ->` arm.**
  Adding a protocol type without an emit rule is a **compile error** — the same javac-enforced
  discipline `AGENTS.md` invariant 3 already imposes on MIR → dialect rendering.
- **`FromProtocol` is stage 2's input adapter**, built now and exercised by legend-lite's entire
  existing test suite on every build. Not throwaway.

### 2.2 The surface, measured

Emitted PMCD JSON across **2,182 files** spanning every section kind in legend-engine's corpus:

| | count |
|---|---:|
| distinct `_type` discriminators | **121** |
| distinct field names, all nodes | **230** |
| *(protocol classes on disk, for contrast)* | *444 / 628 / 735* |

Heavily skewed — `string` 122,918, `func` 116,790, `var` 77,594, `packageableType` 61,442,
`property` 32,483. **The `###Pure` subset is 39 discriminators / 98 field names**, which is M1.
The tail is added **on demand, driven by harness failures**, never by reading 735 files.

### 2.3 Cost, for the record

| held tree, 343 files / 2.88 M chars | held | per char |
|---|---:|---:|
| protocol, `returnSourceInfo=true` | 16.3 MB | 5.9 |
| protocol, `returnSourceInfo=false` | 15.8 MB | 5.8 |
| legend-lite `ParsedModel` | 10.5 MB | 3.8 |

`SourceInformation` is **3%** of the protocol tree. Our immutable records should land at or under
5.9 B/char; either way it is negligible against ANTLR's 1,287 B/char *allocation*.

### 2.4 The byte contract the emitter must reproduce

Verified against `ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()`
— the mapper the HTTP endpoint uses. *(The repo's other mapper,
`PureProtocolObjectMapperFactory.getNewObjectMapper()`, is non-deterministic — no sorting, nulls
included. Picking it silently measures nothing.)*

- `_type` first, then **fields alphabetically**
- **nulls omitted** (`NON_NULL` global); four `NON_EMPTY` overrides (`Column`/`ColSpec`
  `stereotypes`/`taggedValues`)
- **map keys sorted**; **arrays in source order**; `SectionIndex` appended **last**
- Jackson 2.10 puts `@JsonCreator` properties **first in declaration order** — "alphabetical" is not
  universal and must be validated per type
- **`SourceInformation`**: 1-based lines; 1-based start column (`+1` over ANTLR's 0-based
  `charPositionInLine`); **inclusive** end column (`+ text.length()`, no `+1`); `lineOffset` applies
  to every line, `columnOffset` **only on line 1**
- **Deviations encoded explicitly, never inherited.** `EnumValueMappingSourceValue` emits either an
  object *or* a bare string; `CString.multiLine` is excluded from `equals()` but present in JSON when
  true. Each gets a named case with a comment citing the upstream site.

---

## 3. Deliverable A — the equivalence harness

**Built first.** Standalone value: legend-lite's parser is already in production *for legend-lite*
and has never been checked against any reference.

### 3.1 Comparator

Module `parser-equivalence/`, depending on `legend-lite-core` plus upstream grammar artifacts at
**test scope**. Given one source string, produce both parsers' JSON and compare at three strengths:

| | compares | when |
|---|---|---|
| **S1** | tree equality at `returnSourceInfo=false` | working gate for most of the project |
| **S2** | tree equality at `returnSourceInfo=true` | once positions land |
| **S3** | **`String.equals` on emitted bytes** | final acceptance |

**Compare serialized JSON, never object graphs.** Proven necessary: `CString.multiLine` is excluded
from `equals()` and present in JSON — object comparison would silently miss it.

For multi-file models replicate EMIT's exact call shape:
`parseModel(content, virtualPath, 0, 0, true)` -> `addPureModelContextData` -> `withSectionIndexesMerged()`.

### 3.2 Corpora — extracted programmatically, never by grep

| # | source | size | proves |
|---|---|---:|---|
| C1 | **`PARSER error at` pins** (engine) | **410** / 41 files | rejects the right inputs, at the right **range** |
| C2 | inline `###` snippets (engine) | **~3,191** / 253 files | breadth — only source of exotic section kinds |
| C3 | **EMIT models** | **82 models / 214 files / 11,379 lines** | realism: multi-file, cross-referencing |
| C4 | `COMPILATION error at` pins | **532** | guaranteed-parseable |
| C5 | inline snippets (legend-pure) | ~2,800 | M3 breadth |
| C6 | `assertPureException` compile pins | ~477 | guaranteed-parseable |
| C7 | round-trip inputs | 359 | text->protocol->text fidelity |
| C8 | regenerated golden pairs | 26 | protocol-shape oracle |
| C9 | `PureParserException` pins (pure) | 69 | rejection — positions only, **not messages** |
| C10 | standalone grammar files | 668 + 274 | volume |

**Extractor requirements.** Source-level (JavaParser or equivalent), pulling every `test(...)` /
`compileTestSource(...)` / `createInMemorySource(...)` call site with its string arguments and any
adjacent expected-message literal. It must emit a manifest and **report its own coverage** — files
scanned, call sites found, call sites it failed to parse. *410 pins were invisible because I grepped
an outer class name; a missed corpus must show up as a number, not as silence.*

**Exclusions, recorded so they are not rediscovered:**
- `platform/pure/grammar/m3.pure` — 3,607 lines, **2,186** of `^Root.children[...]` bootstrap-instance
  syntax, **zero** normal declarations. Verified. Skews every count.
- legend-pure error *message strings* — ANTLR-shaped, built on `m4.SourceInformation`. Use those
  cases for *which input fails and where*, never for message text.

### 3.3 EMIT

Framework landed 2026-05-07; the window we pulled contained a corpus explosion — **60 -> 103 models
and 132 -> 244 files in eight days**. Catalog: 82 models / 214 `.pure` / 11,379 lines, 11 section
kinds.

Uniquely gives **multi-file cross-referencing models** — a Mapping in one file referencing a Class in
another and a Database in a third. That is what breaks `SectionIndex` merging and cross-file
source-information offsets; no snippet corpus reaches it.

**It ships no expected output.** `GrammarEMITTests` is seven lines and asserts nothing; the parse
assertion is *did not throw*; there is no composer step. **We generate the oracle ourselves** from
its 214 files and regenerate on each engine bump. `EMITModelDiscovery.findEmitYamls(Path)` is plain
`Files.walk` and `EMITModelLoader` has no parser coupling, so we drive it ourselves. Two sharp edges:
classpath discovery supports only `file:` URLs, and their runner is hardwired to
`PureGrammarParser.newInstance()` with no injection seam.

### 3.4 Modes

- **Corpus** — C1–C10, in CI.
- **Fuzz** — generate from the `.g4` files; the two parsers disagreeing is a perfect oracle needing
  no authored expectations. Finds what curated corpora cannot.
- **Shadow** — §5, Phase 4.

### 3.5 Anti-false-green requirements

Non-negotiable; each earned the hard way:

1. A run whose log lacks evidence of execution **fails**, never scores zero failures.
2. Every comparison asserts a non-zero input count.
3. Extractor coverage is reported, not assumed.
4. Corpus mode fails if the corpus **shrinks** between runs.

---

## 4. Deliverable B — the protocol and the parser

### 4.1 `com.legend.protocol` — our tenets, their bytes

- **Sealed interfaces with explicit `permits`**, mirroring the emitted `_type` hierarchy.
- **Immutable records** throughout. `SourceInfo` is an immutable component set at construction — the
  only point where token positions are in hand.
- **`ProtocolEmitter` is a switch expression over the sealed hierarchy with no `default ->` arm.**
  A new protocol type without an emit rule is a compile error.
- **No fallbacks.** An unrecognized shape throws, naming the layer at fault.
- Built **incrementally, driven by harness failures** — 39 discriminators for M1, 121 eventually.

### 4.1.1 Where Protocol meets legend-lite: plumb the expressions, transform the elements

The two halves of `com.legend.model` are in different positions, so the answer is not uniform.

**Value specifications — one family, plumbed directly.** `com.legend.model.spec.*` was *designed*
to mirror the protocol. Its own javadoc: *"Record names and field names match the engine's
**verbatim** … lets downstream layers swap between core's standalone parser output and the engine's
protocol shapes by **mechanical renaming**."* `CInteger(Number value)` vs protocol
`CInteger{value, sourceInformation, multiplicity}` — same name, same field, ours lacks only the wire
extras. Duplicating 22 record types built to match verbatim would be silly.

*Plan:* evolve `com.legend.model.spec.*` into the protocol shape — add `sourceInformation` and the
missing wire fields, and **override `equals`/`hashCode` to exclude position** so the 111
`assertEquals(new CInteger(42L), spec)` assertions survive untouched. **The override needs its own
guard test**, or someone will "fix" it later and silently break those 111.

**Elements — two families plus a transform.** These genuinely differ in structure:

```java
// ours
ClassDefinition(qualifiedName, typeParams, superClasses /*TypeExpression*/,
                properties, derivedProperties, constraints, stereotypes, taggedValues, isNative)
// protocol
Class{ _package, name,                      // split, not one qualifiedName
       superTypes,                          // strings
       qualifiedProperties,                 // their name for derived
       originalMilestonedProperties,        // no equivalent in ours
       properties, constraints, stereotypes, taggedValues }   // and no isNative
```

Split `package`/`name` vs a single `qualifiedName`, a rename, a field they have that we lack, and one
we have that they lack. That is a real mapping, not a rename — **and the transform is the quarantine
boundary** keeping upstream's wire concerns out of `ModelBuilder`, exactly as `ProtocolEmitter`
quarantines them on the way out.

#### Are the element differences *right*, or accidental? — investigated, and the answer is mixed

I first asserted the element shapes differ for principled reasons. That was read off record
signatures and is **only partly true**. Evidence:

**Neither, in fact: our elements are *independent* — never protocol-derived at any hop.** Traced:

| hop | modelled on |
|---|---|
| `engine/src/main/java/com/gs/legend/model/def/ClassDefinition.java` | **the Pure grammar.** Its javadoc is *"Represents a Pure Class definition"* plus syntax examples — **no protocol reference of any kind** |
| `core/src/main/java/com/legend/model/ClassDefinition.java` | *"Mirrors engine's `com.gs.legend.model.def.ClassDefinition` record shape verbatim"* |

> **Naming trap, worth stating once.** `engine/` here is **legend-lite's own module**
> (package `com.gs.legend`), the predecessor `core/` is a rewrite of — **not** the FINOS
> `legend-engine` repo (`org.finos.legend.engine`). Six-plus `core/` model records say
> *"Mirrors engine's `com.gs.legend.model.def.*`"* and every one of them means the local module.

So the element shapes were designed **from the grammar**, independently, and nobody ever compared
them to the protocol. They are therefore not "deliberately different from the protocol" — the
protocol was simply never in the frame. They should be evaluated **field by field on merit**, not
defended as ours-by-design. `core/README.md:236` states the intent they drifted from: *"Parser
records = engine class names verbatim … Maximizes test portability against the engine corpus."*

One field *was* a considered upgrade, and shows the difference: `engine/` had
`superClasses: List<String>`; `core/` changed it to `List<TypeExpression>` to carry generics. That
is a real improvement over both the predecessor and the protocol's flat `superTypes`, and is worth
keeping.

**Principled — and it alone justifies the transform.** `PackageableElement.java:14-24` documents the
single `qualifiedName` as a deliberate omission:

> *"no `simpleName()` or `packagePath()` default methods. Those exist on engine's version and invite
> an attractive nuisance — a caller writes `modelContext.findClass(element.simpleName())`, the lookup
> hits the wrong class, and the bug surfaces only when two elements share a simple name across
> packages. Keys are `qualifiedName()`, full stop."*

That is a direct structural defence against the exact defect in `NAME_RESOLUTION_BUG.md`, where a
global suffix scan binds an unimported element by simple name. The protocol splits `package` and
`name`; **legend-lite must not**, and that is worth a transform on its own.

**Therefore:**

- **Keep the transform, for one documented reason** — the FQN discipline. Split `package`/`name` on
  the way *out* to the wire, never on the way *in* to the compiler.
- **Do not defend the rest.** `derivedProperties` vs `qualifiedProperties` is an inherited rename;
  the missing `originalMilestonedProperties` is a gap, not a choice; `ClassDefinition` vs `Class` is
  most likely a `java.lang.Class` clash. Where the protocol shape is neutral or better, converge.
- **`isNative` on classes is ours alone** — `DomainParserGrammar.g4` has `nativeFunction` but no
  native-class production. Verify whether the Legend DSL admits `native Class` at all before
  carrying the field.

**Validate the value-spec half with data in Phase 0 too.** That split is still read from signatures;
if overlap proves thinner than the javadoc claims once positions and multiplicity are in play, value
specs fall back to two families and a transform, like the elements.

### 4.2 Parser work, in dependency order

> **Status as of 2026-08-05.** A nine-agent section sweep re-measured this list against
> `266fe1d5`; see [`GRAMMAR_COMPATIBILITY_2026_08.md`](GRAMMAR_COMPATIBILITY_2026_08.md) **§10**.
> Items 1, 6, 10 and the exit criteria are **confirmed**; items 2, 7, 8 are annotated below;
> the ordering in Phase 3 and the `MappingElementContext` risk row are **superseded**.

1. **Fix the silent-drop lexer — prerequisite for everything.** `Lexer.java:287-293` raw-skips any
   section outside `{Pure, Mapping, Relational, Connection, Runtime}` and returns **success**. You
   cannot delegate a section you have already swallowed. Make it a loud, delegable miss.
   > **Confirmed and quantified 2026-08-05:** 1,363 of 7,211 sources (18.9%) yield zero
   > verdicts; ~2,452 `###Pure` elements are invisible as collateral. The priority call was right.
   >
   > **A second, co-equal prerequisite was missed here:** the harness gates. The `pureOnly`
   > filter (`ParserEquivalence.java:71-80`, duplicated in `SpiSeamProofTest` and
   > `RejectionParityTest`), the `.pure`-only corpus predicate, and the `parser-equivalence`
   > **classpath** each independently exclude a section before grammar work can be judged.
   > Neither is grammar work; both gate every section. See `GRAMMAR_COMPATIBILITY_2026_08.md` §1.
2. **A line index in the lexer.** Positions must be cheap at all 88 construction sites in `parser/`.
   Today line/column is computed *only when throwing*, by an O(offset) rescan
   (`TokenStreamCursor.java:285-301`). Build an `int[]` of line starts once per source and
   binary-search it. Self-contained; touches no AST.
   > **Likely DONE.** `PARSER_DROP_IN_STATUS.md` §3 describes `com.legend.lexer.TokenStream` as
   > carrying a *"lazily-built line index, binary search."* Verify before re-doing.
3. **Fix the column-base disagreement** — `TokenStreamCursor.java:295` starts at 0,
   `LegendCompileException.java:66` at 1. Engine is 1-based, inclusive end column.
4. **Emit protocol records** from the 88 sites (SpecParser 75, ElementParser 11, the two grammar
   parsers 1 each). *The other 660 construction sites in `core` are downstream desugaring/synthesis —
   not parsing, no positions needed, untouched.*
5. **`SectionIndex` + the section model** — the entire remaining element-count gap. With it, the
   384/384 element parity becomes exact rather than "after removing SectionIndex".
6. **`###Service`** — `ElementParser.java:333` already has `case SERVICE`, but the lexer discards
   `###Service` bodies, which is how all 43 real ones are spelled. The parser supports a construct
   the lexer never delivers. Cheapest real coverage win.
   > **Half stale.** The lexer/parser mismatch is confirmed, and it is the cheapest *lexing*
   > win. It is **not** a parity win: all 51 `###Service` corpus files are mixed-section, so
   > none is reachable by any byte-parity gate until §1 of the compatibility doc lands (the
   > `pureOnly` filter), and 28 of them sit in a service test-runner module we have no reason
   > to consume.
7. **`###Connection`** — fails 0/3 today despite being whitelisted. A bug.
   > **Understated, and not a bug.** Measured **17/56 sections parse (30.4%)**. It is a
   > *grammar divergence*: 1 of 8 connection types, 4 of 16 datasource specs, 3 of 12 auth
   > strategies, with invented keyword spellings (`Static{database:}` vs the engine's `name:`;
   > `UsernamePassword` vs `UserNamePassword`) that can never match however many bugs are
   > fixed. Also: `sqlQueryPostProcessors` is **not** a `###Connection` grammar key at all —
   > it is an M3 lambda-typed property settable only from Pure copy-construction.
8. **`DataElement`** — `###Data` parses but produces 0/4 matching elements.
   > **Framing stale.** 48 of 57 `###Data` files parse cleanly, with **zero** failures
   > attributable to `###Data` — all 9 are other sections in the same file. 42 single-section
   > files need only embedded-data kinds we already emit. Best cost/benefit in this list.
9. **`FromProtocol`** — the transform feeding legend-lite's compiler; also stage 2's adapter.
10. **Delete `MappingGrammarParser.java:432-440`** — it deliberately swallows the tail of an XStore
    block after a missing comma. Incompatible with any equivalence claim.
11. **Multi-line `'''...'''` strings** (upstream #4998) — dedent -> strip trailing whitespace per
    line -> unescape, **in that order**. `CString.multiLine` must be emitted identically. Note the
    deliberate upstream gap: a text block inside a **tagged value** parses but composes single-line.
    Do not "fix" it.

---

## 5. Phasing and gates

### Phase 0 — thin vertical slice (days)
One `###Pure` file -> our protocol records -> emitted bytes -> diff against the engine. Proves
emitter, positions and harness skeleton end-to-end before anything is built around them.
**Gate:** one file byte-identical at S3.

### Phase 1 — harness (2–3 weeks)
Build §3. **Gate: harness green with the *engine* parser on both sides** — proves the instrument
before it judges anything.

### Phase 2 — prove the seam with zero sections claimed (1 week)
Ship the `ServiceLoader` jar registering **nothing**. Everything falls through to upstream; output
must be bit-identical to no-jar. Proves deployment, rollback and packaging in isolation from
correctness. `PureGrammarParser.visitSection` consults extension `SectionParser`s **before** the four
hard-wired legacy ones, so a jar claiming `"Pure"` wins over `DomainParser` — no fork.

### Phase 2.5 — modularize the parser (added 2026-08-06; spec: `GRAMMAR_EXTENSIBILITY.md`)

Before building the remaining sections, restructure so every section grammar is a
**pluggable module** — the internal-overlay requirement (closed-source jars adding
sections not available open source) and the engine's own architecture. Steps, in
`GRAMMAR_EXTENSIBILITY.md` Consequence-2 order:

1. **`SectionGrammarRegistry`** — section name -> grammar module; built-ins register
   through the SAME registry as third parties (dogfooding keeps the plug-in path
   honest). Unknown section stops being lexer raw-skip and becomes "no grammar
   registered" — explicit and reportable (this also lands the loud-miss lexer fix).
2. **`legend-lite-spi` artifact** — `SectionGrammar { name(); parse(SectionSource,
   ElementSink); }`, raw text + offsets in (foreign grammars never adopt our lexer),
   protocol elements out; `ServiceLoader` discovery; small and stable, no core
   internals.
3. **Opaque-element carrier** — ONE new variant of sealed `Protocol.Element` holding a
   foreign section's protocol JSON; core indexes/names/routes it, never looks inside.
   The plug-in unit is a language module (parser + compiler hook), not a grammar.
4. **Embedded registries** — island/test-data seams, only when an overlay needs them.

Shadowing policy: extensions win over built-ins (that is what makes the drop-in work);
tests, not policy, catch abuse. **Gate:** all existing parity ratchets hold with
built-ins routed through the registry, and a proof-of-seam test registers a toy
`###` section from a test-only jar and sees it parse + report.

**Every Phase 3 section below lands as a registry module from day one** — no
retrofitting.

### Phase 3 — sections, in this order

> **Superseded 2026-08-05** by measurement — see `GRAMMAR_COMPATIBILITY_2026_08.md` §10.3.
> The order is now **Runtime -> Connection -> Relational -> Mapping**, with **Diagram removed**.
>
> - **Diagram is a byte-parity dead end.** Its 49 corpus files use legend-pure's **M3** dialect
>   (`Diagram fqn(width=, height=) { TypeView … }`); the engine grammar demands the Legend one
>   (`Diagram fqn { classView … }`). Two languages sharing a section name, so there is no
>   reference-adjudicable corpus at all. (This also resolves `PARSER_DROP_IN.md:713-716`'s
>   *"genuinely unexplained"* zero.) It remains the cheapest **lexing-coverage** win.
> - **Connection's "free inheritance" is the unbuilt part.** 8 types + 16 datasource specs +
>   12 auth strategies are exactly what is missing today (1, 4 and 3 built respectively).
> - **Runtime is the smaller proof** — 46 elements, 8 wire discriminators, 33 field names; the
>   smallest *complete* section, and it exercises the whole non-`Pure` loop end to end.
> - **Relational must precede Mapping**: it *is* ~9 of Mapping's 52 wire discriminators and
>   **8,620** of Mapping's node instances. Mapping-first builds that vocabulary twice.

~~**Connection** (39-line grammar, 105-line walker, and it inherits all 8 connection types plus 12
database datasource/auth modules free) -> **Diagram** -> **Runtime** -> **Relational** ->
**Mapping** -> **Pure**.~~

~~Mapping needs the `MappingElementContext` shim spiked **before** committing: 21 of 24 sub-parsers
are reachable through pure-`String` SPIs and delegate verbatim; only the 3 mapping-side SPIs are
welded to ANTLR nodes. Shim ~200–400 lines; without it Mapping is ~1,800.~~

> **The shim was never needed.** `LegendLiteSectionParser:150-155` emits JSON and hands it to the
> engine's own deserializer — no ANTLR context is ever constructed. `MappingParser`,
> `ConnectionParser` and `RuntimeParser` all return `ImportAwareCodeSection`, the exact type the
> bridge already builds; only `###Relational` differs (`DefaultCodeSection`), a one-line change.

**Per-section exit criteria — all required:**
- S1 100% on that section's corpus slice
- 100% of that section's `PARSER error at` pins reproduced **including ranges**
- zero fuzz divergence over an agreed volume
- no regression in other sections' slices

### Phase 4 — shadow mode
Both parsers on **real production traffic**; compare; log divergence; **always return upstream's
result.** Zero risk, unbounded corpus, inputs no test author imagined.
**Gate: no section flips until >=30 days of shadow traffic at zero divergence.**

### Phase 5 — flip
Per-section flag, config rollback, shadow still running post-flip, auto-revert on divergence.

### Phase 6 — endgame
S3 byte-identity everywhere; delete grammars; drop the ANTLR plugin; unpin.

---

## 6. Standing invariants

- **CI against every upstream release.** Three grammar/protocol changes landed in the ~3 weeks we
  were behind: multi-line strings (`CoreLexerGrammar`, so every island DSL inherits them), a second
  service-test surface syntax, and a Colspec/constraint ambiguity fix. Upstream's house style is
  `assertJsonEquals`, not byte equality — we hold a stricter bar, so their drift breaks us in ways it
  does not break them.
- **`core` keeps zero `org.finos.legend.engine` dependencies.** ArchUnit rule.
- **All upstream-shape knowledge lives in `ProtocolEmitter`.** ArchUnit rule.
- **The composer moves in lockstep** — upstream convention, ~215 tests assert text->JSON->text.
- **Never let an unowned section parse "successfully."**
- Re-run the extractor's coverage report every release; a shrinking corpus is a failure.

## 7. Risks and re-scope criteria

| risk | trigger | action |
|---|---|---|
| ~~`MappingElementContext` shim fails~~ | ~~spike in Phase 3~~ | **Struck 2026-08-05 — dead risk.** The bridge crosses the seam as JSON, not ANTLR nodes; no shim exists or is needed. See Phase 3. |
| Fuzz divergence does not converge | Phase 3 | stop at S1; never claim byte-identity |
| Protocol drift outpaces us | CI red on upstream bumps | pin an engine version per release; batch catch-up |
| Emitter accumulates upstream quirks | review | each deviation gets a named case + upstream citation; if the count grows unbounded, reconsider |

**Not a risk criterion:** parse being a small share of request latency (§1.3).

## 8. Beyond stage one

The four seams, each getting the same treatment — clean-room boundary type, byte-identity proven by
differential harness:

| stage | input -> output | seam |
|---|---|---|
| 1 parse | text -> **`PureModelContextData`** | this plan |
| 2 compile | PMCD -> **`PureModel`** | `FromProtocol` (§4.2) is already its input adapter |
| 3 plan | PureModel + query -> **`ExecutionPlan`** | |
| 4 execute | plan -> **`Result`** | |

Depending on upstream's types at each seam would make every later stage progressively harder to
detach. Clean-room at the boundary, proven by harness, is what keeps the programme detachable.

## 9. Open questions

1. **Memory of our clean-room records** vs the 5.9 B/char protocol tree — measure at Phase 0.
2. **Does the legacy `DomainParser` also register through the SPI?** Duplicate keys throw at registry
   construction; confirm we can be the sole claimant for `"Pure"`.
3. **Where `parser-equivalence` lives** — its own module, since it needs upstream artifacts `core`
   must never depend on.
4. **Fuzz volume** that constitutes a passing gate.
