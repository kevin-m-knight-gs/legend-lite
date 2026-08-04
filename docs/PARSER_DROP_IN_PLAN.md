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
| **Memory** | 1,287 B allocated per source char vs 25 — **52×**; 3.6 GB to parse 2.8 MB; +31.5 MB retained to warm the parser |
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
    +--> ProtocolToModel --------> com.legend.model.*  <- legend-lite's compiler,
                                                          unchanged
```

- **One parser, one output.** Byte-identity by construction, not parallel maintenance.
- **`ProtocolEmitter` is a switch expression over the sealed hierarchy with no `default ->` arm.**
  Adding a protocol type without an emit rule is a **compile error** — the same javac-enforced
  discipline `AGENTS.md` invariant 3 already imposes on MIR → dialect rendering.
- **`ProtocolToModel` is stage 2's input adapter**, built now and exercised by legend-lite's entire
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

**Validate this split with data in Phase 0.** It is read from record signatures. If value-spec
overlap proves thinner than the javadoc claims once positions and multiplicity are in play, value
specs fall back to two families and a transform, like the elements.

### 4.2 Parser work, in dependency order

1. **Fix the silent-drop lexer — prerequisite for everything.** `Lexer.java:287-293` raw-skips any
   section outside `{Pure, Mapping, Relational, Connection, Runtime}` and returns **success**. You
   cannot delegate a section you have already swallowed. Make it a loud, delegable miss.
2. **A line index in the lexer.** Positions must be cheap at all 88 construction sites in `parser/`.
   Today line/column is computed *only when throwing*, by an O(offset) rescan
   (`TokenStreamCursor.java:285-301`). Build an `int[]` of line starts once per source and
   binary-search it. Self-contained; touches no AST.
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
7. **`###Connection`** — fails 0/3 today despite being whitelisted. A bug.
8. **`DataElement`** — `###Data` parses but produces 0/4 matching elements.
9. **`ProtocolToModel`** — the transform feeding legend-lite's compiler; also stage 2's adapter.
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

### Phase 3 — sections, in this order
**Connection** (39-line grammar, 105-line walker, and it inherits all 8 connection types plus 12
database datasource/auth modules free) -> **Diagram** -> **Runtime** -> **Relational** ->
**Mapping** -> **Pure**.

Mapping needs the `MappingElementContext` shim spiked **before** committing: 21 of 24 sub-parsers are
reachable through pure-`String` SPIs and delegate verbatim; only the 3 mapping-side SPIs are welded
to ANTLR nodes. Shim ~200–400 lines; without it Mapping is ~1,800.

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
| `MappingElementContext` shim fails | spike in Phase 3 | delegate `###Mapping` permanently; claim the rest |
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
| 2 compile | PMCD -> **`PureModel`** | `ProtocolToModel` (§4.2) is already its input adapter |
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
