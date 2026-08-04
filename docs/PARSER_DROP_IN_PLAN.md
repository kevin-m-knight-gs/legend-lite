# Parser drop-in — the delivery plan

> **Companion to `PARSER_DROP_IN.md`**, which holds the feasibility evidence. This document is the
> plan: what we build, in what order, and what has to be true before each step is allowed to
> proceed.
>
> **Branch:** `parser/drop-in`. **Status:** planning. All measurements cited are from
> `PARSER_DROP_IN.md` unless stated, and were taken 2026-08-03/04 against `legend-engine`
> `d0b4c3a2f68` / `legend-pure` `63a4b2c68` (both pulled to origin/master 2026-08-04).

---

## 0. The three deliverables

1. **This plan** — end-to-end, researched, with explicit gates.
2. **The equivalence harness** — the instrument. Highest-confidence artifact we can build, and the
   one with standalone value regardless of whether the replacement ships.
3. **legend-lite's parser, upgraded to 100% byte-identical output** — including the metamodel
   changes §3 shows are unavoidable.

### 0.1 Why we are doing this — not speed

Restated from `PARSER_DROP_IN.md` §0 because it changes the acceptance criteria:

| reason | measured |
|---|---|
| **Memory** | 1,287 B allocated per source char vs 25 — **52× less garbage**; 3.6 GB to parse 2.8 MB; +31.5 MB retained just to warm the parser |
| **Generated code** | 132 of 1,508 jar classes are ANTLR output but **46% of the bytes** (4.8 MB); 156 `.g4` / 14,568 lines deleted |
| **Build** | `antlr4-maven-plugin` at `generate-sources` in 41 modules, `treatWarningsAsErrors`, hand-managed `libDirectory` |
| **Currency** | ANTLR pinned 4.8-1 (2020); today it can only move together with all generated code |
| **Debuggability** | recursive descent vs an ATN simulator |
| speed | 54.5× — real, and the weakest of the six |

**Consequence for the plan: there is no latency gate.** The project is worth doing at 0% speedup.
Measuring parse-share of request latency is still worth a day, but as *sizing*, to know how loudly
to sell it — not as a kill switch.

---

## 1. The confidence principle

**Confidence comes from differential execution against the real parser, continuously — not from
analysis, and not from fixtures.**

Every number in the feasibility study that mattered came from running both parsers and comparing.
Every claim that came from reading code or docs needed correcting: the goldens were 19/26 stale, the
negative-test corpus was hidden behind a nested class name, Postgres's test tier was misdocumented
upstream, and my own "there are two parsers" framing was wrong. Analysis produced the questions.
Execution produced the answers.

Three corollaries that shape everything below:

- **Fixtures rot; live comparison does not.** Proved: upstream's only structured `.pure`↔protocol
  corpus drifted for 16 months because `validate()` was commented out.
- **A green that could have come from an absence of evidence is worthless.** My own harness scored a
  `BUILD FAILURE` as "0 failures" once. Every gate below must assert that work actually happened.
- **Search terms hide corpora.** 410 parser-error pins were invisible to me because I grepped an
  outer class name. Extract programmatically; never let a count come from a grep I chose.

---

## 2. Deliverable 2 — the equivalence harness

**Build this first. It is the deliverable with standalone value:** legend-lite's parser is already
in production *for legend-lite*, and has never been tested against any reference. The harness
improves an existing product on day one even if the replacement never ships.

### 2.1 Shape

A module (proposal: `parser-equivalence/`) depending on `legend-lite-core` **and** the real
`legend-engine-language-pure-grammar` + DSL grammar artifacts. One comparator, four sources of
input, three modes.

**The comparator** — given one source string, produce both parsers' `PureModelContextData` and
compare at three strengths:

| strength | what it compares | when |
|---|---|---|
| **S1 semantic** | `assertJsonEquals` at `returnSourceInfo=false` | the working gate for most of the project |
| **S2 positional** | full tree equality at `returnSourceInfo=true` | once §3.3 lands positions |
| **S3 byte** | `String.equals` on `getNewStandardObjectMapperWithPureProtocolExtensionSupports()` output | the final acceptance bar |

Pin the mapper explicitly. There are two in the repo and
`PureProtocolObjectMapperFactory.getNewObjectMapper()` is non-deterministic (no sorting, nulls
included); picking it silently measures nothing.

### 2.2 The corpora — extract programmatically, never by grep

| # | source | size | what it proves |
|---|---|---:|---|
| C1 | **`PARSER error at` pins** (legend-engine) | **410** across 41 files | rejects the right inputs, at the right **range**, with the right exception type |
| C2 | inline `###` snippets in Java tests (legend-engine) | **~3,191** across 253 files | parses what upstream parses |
| C3 | **EMIT models** | see §2.3 | realistic, whole-model, growing |
| C4 | `COMPILATION error at` pins | **532** | guaranteed-parseable (parsed, failed later) |
| C5 | inline snippets (legend-pure) | ~2,800 | M3-dialect breadth |
| C6 | `assertPureException` compile pins (legend-pure) | ~477 | guaranteed-parseable |
| C7 | round-trip suite inputs | 359 | text→protocol→text fidelity |
| C8 | regenerated golden pairs | 26 | the only *protocol-shape* oracle |
| C9 | `PureParserException` pins (legend-pure) | 69 | rejection — different types, so positions only |
| C10 | standalone grammar files | 668 (engine) + 274 (pure) | volume |

**Extractor requirements.** A source-level extractor (JavaParser or equivalent) that pulls every
`test(...)` / `compileTestSource(...)` / `createInMemorySource(...)` call site with its string
arguments and any adjacent expected-message literal. It must emit a machine-readable manifest and
**report its own coverage** — files scanned, call sites found, call sites it failed to parse — so a
missed corpus shows up as a number rather than as silence.

**Exclusions, recorded so they are not rediscovered:**
- `platform/pure/grammar/m3.pure` — 3,607 lines of `^Root.children[…]` bootstrap-instance syntax,
  **zero** normal declarations. Verified. Skews every frequency count.
- legend-pure error *message strings* — ANTLR-shaped and built on `m4.SourceInformation`, a
  different type. Use those cases for *which input fails and where*, never for message text.

### 2.3 EMIT — the realism tier, and the one hole nothing else fills

**Correction:** EMIT did not land in the 38 commits we were behind. The *framework* landed
**2026-05-07** (`aa0046a15aa`). What landed in that window is a **corpus explosion — 60 → 103 models
and 132 → 244 `.pure` files in eight days**, 42% of the whole corpus.

EMIT takes `.pure` files in **Legend grammar** and runs parse → compile → generate → test → plan.
Verified call shape, which our harness must replicate exactly:

```java
PureGrammarParser.newInstance().parseModel(readFile(file), file.getVirtualPath(), 0, 0, true);
builder.addPureModelContextData(fileData);
... return builder.withSectionIndexesMerged().build();
```

**Catalog: 82 models / 214 `.pure` / 11,379 lines** (excluding 21 framework self-test fixtures which
upstream explicitly says are not catalog examples). Section kinds — 11 of them: `###Pure` 89,
`###Mapping` 65, `###Data` 45, `###Relational` 37, `###Persistence` 9, `###Service` 8, `###Runtime` 7,
`###Connection` 7, `###GenerationSpecification` 2, `###FileGeneration` 2, `###ExternalFormat` 1.

**What EMIT uniquely gives us: multi-file, cross-referencing, user-shaped models.** 82 coherent
models where a `Mapping` in one file references a `Class` in another and a `Database` in a third.
That is precisely the shape that breaks `SectionIndex` merging, cross-file source-information
offsets, and qualified-name resolution — failure modes a snippet corpus can never reach. Nothing
else in either repository has this.

**Its decisive weakness: EMIT ships no expected-output artifact.** No golden JSON, no golden
composed grammar, nothing. `GrammarEMITTests` is seven lines and asserts *nothing* itself — the
whole pipeline's parse "assertion" is *did not throw*, and there is no composer step anywhere in
EMIT. So **we generate the oracle ourselves**: run the engine parser over the 214 files, serialise,
commit as our golden set, regenerate on every legend-engine bump. Cheap, but it is *our* artifact,
and each EMIT growth spurt is a regeneration event rather than free coverage.

**Reusability: good.** `EMITModelDiscovery.findEmitYamls(Path)` is plain `Files.walk`;
`EMITModelLoader.load(...)` returns an ordered, scope-tagged `EMITSourceSet` with no parser,
compiler or JUnit involvement. We can point it at any directory. Two sharp edges: classpath
discovery **only supports `file:` URLs** (models inside a jar are invisible — unpack, or use the
`Path` API), and `EMITTestSuiteBuilder` is hardwired to `PureGrammarParser.newInstance()` with **no
parser-injection seam**, so we drive the loader ourselves rather than reusing their runner.

**Composition — EMIT displaces nothing:**

| tier | corpus | why it is irreplaceable |
|---|---|---|
| realism / cross-file | **EMIT, 214 files** | only source of multi-file models; fastest-growing; lowest maintenance |
| breadth | ~3,176 inline `###` snippets | only source of the 7 exotic section kinds EMIT lacks (`###Diagram`, `###DataSpace`, `###ServiceStore`, activators…) |
| diagnostic parity | **410 `PARSER error at` pins** | the only expected-error oracle; EMIT has *no* negative tests by design |
| byte-identity | 26 regenerated golden pairs | the only upstream protocol-shape fixture |

### 2.3.1 Live grammar changes found in the window — and one that redesigns our comparator

**`4feb08b838d` — multi-line `'''…'''` string literals (#4998).** Two lines of `.g4`, very large
blast radius: it changes `CoreLexerGrammar`, so **every island DSL that inherits the core `STRING`
token now accepts text blocks**. Post-lex processing is **dedent → strip trailing whitespace per
line → unescape, in that order**, deliberately identical to legend-pure's `processMultilineString`.

> **This forces a harness design decision.** `CString` gained a `multiLine` boolean that is
> **excluded from `equals()`** but `@JsonInclude(NON_DEFAULT)` — so it is invisible to object
> equality and visible in serialised JSON. **A comparator built on `.equals()` would silently miss
> it.** Our comparator must diff **serialised JSON**, not object graphs. §2.1's S1/S2/S3 already do;
> this is the proof that it was the right call.

Do *not* "fix" the known gap: a text block inside a **tagged value** parses but composes back
single-line, because `TaggedValue` carries a bare `String`. That is deliberate.

**`b8219ce9996` — Service test grammar gains a second surface syntax (#4990).** `serviceTestSuite`
now has two mutually exclusive forms — legacy brace-and-colon, and a new paren-and-arrow form
mirroring Function tests. +160 lines of walker, +134 of composer. None of the 4 service EMIT models
use the new form yet.

**Just outside the window, check the baseline:** `1d4be2501f0` *"Colspec — handle clash with
constraint grammar"* is a genuine ambiguity fix in `DomainLexerGrammar`/`DomainParserGrammar`, and
`4ae65fe9bc5` added stereotypes/tagged values to ColSpecs in `M3ParserGrammar.g4`.

**These three changes in ~3 weeks are the standing-invariant argument made concrete** (§5): the
grammar is under active development and a replacement decays silently without a release-gated
differential.

### 2.4 Modes

- **Corpus mode** — C1–C10, run in CI.
- **Fuzz mode** — generate from the `.g4` grammars; the two parsers disagreeing is a perfect oracle
  needing no authored expectations. This is what finds the unknown unknowns that curated corpora
  cannot.
- **Shadow mode** — §4.4. The step that turns a test-suite claim into a production claim.

### 2.5 Anti-false-green requirements

Non-negotiable, each earned the hard way this session:

1. A run whose log lacks evidence of execution **fails**, never scores zero failures.
2. Every comparison asserts a non-zero input count.
3. Coverage of the extractor is reported, not assumed.
4. Corpus mode fails if the corpus *shrinks* between runs.

---

## 3. Deliverable 3 — upgrading legend-lite's parser

### 3.1 The architecture decision: one parser, clean-room protocol, zero dependency

**Decision: legend-lite reimplements the protocol. It does not depend on
`legend-engine-protocol-pure`.** `core` keeps its zero engine dependencies — pom *and* source. The
equivalence harness carries upstream artifacts at **test scope only**, to generate the reference.

This supersedes both earlier designs in this document (the two-sink factory, then the adopt-the-
protocol proposal). One parser, one output shape, and the output is *our* protocol classes emitting
*their* bytes.

#### Why clean-room is easier here, not harder

**Hand-rolled serialization is easier to make byte-identical than configuring Jackson to match
another Jackson.** Taking the dependency would mean reverse-engineering
`SORT_PROPERTIES_ALPHABETICALLY`, Jackson 2.10's creator-properties-first quirk, global `NON_NULL`,
four `NON_EMPTY` overrides, and ~20 custom serializers on the PMCD path — and then staying pinned to
Jackson 2.10 to preserve the ordering. Emitting the bytes ourselves, we write them in the order we
observe, and the harness tells us immediately when we are wrong.

Secondary wins, all of which the dependency would have cost us:

- legend-lite keeps **sealed hierarchies with javac-enforced exhaustive switches** — its house
  style, and the property that makes adding a variant a compile error rather than a runtime surprise.
- Records stay **immutable**; the protocol POJOs are mutable public-field classes.
- **No Jackson pin, no upstream protocol-version coupling** for our own internals.
- The `ArchitectureTest` / `NoEagerTypeReferences` guards stay meaningful.

#### The surface, measured — not 444 classes

Emitted `PureModelContextData` JSON across **2,182 files** covering every section kind in
legend-engine's corpus:

| | count |
|---|---:|
| distinct `_type` discriminators emitted | **121** |
| distinct field names across all nodes | **230** |
| *(for contrast: protocol classes on disk)* | *444 packageableElement / 628 v1 model / 735 total* |

**121 types and 230 field names is the real target**, and it is heavily skewed — the top five
discriminators are `string` (122,918), `func` (116,790), `var` (77,594), `packageableType` (61,442),
`property` (32,483). A handful of value-specification types dominate the hot path; the tail is rare
and can be added on demand, driven by harness failures rather than by reading 735 files.

Restricted to the `###Pure`-shaped subset (a 35-file sample covering Pure/Mapping/Data/graph-fetch),
the surface is **39 discriminators / 98 field names** — the size of the first milestone.

#### What we build

1. **`com.legend.protocol`** — a sealed record hierarchy mirroring the emitted shape, 121 types
   incrementally, driven by the harness.
2. **A byte-exact emitter.** Not Jackson. It reproduces the observed contract directly: `_type`
   first, then fields alphabetically; nulls omitted; map keys sorted; arrays in source order;
   `SectionIndex` appended last. Where upstream's custom serializers deviate (e.g.
   `EnumValueMappingSourceValue` emitting either an object or a bare string) we encode the deviation
   explicitly rather than inheriting it accidentally.
3. **The parser emits these directly**, with `SourceInformation` inline at construction — the only
   point where token positions are in hand.
4. **legend-lite's compiler keeps consuming its own sealed records**, via a transform from the
   protocol tree. That transform is also stage 2's input adapter (§3.1.1), so it is not throwaway.

#### The real risk, and the mitigation

**Drift.** We now own a shape upstream controls. The 38-commit window we just pulled contained three
grammar/protocol changes, including `CString.multiLine` — a field *excluded from `equals()`* and
*present in JSON when true*, which object-equality comparison would have missed entirely.

The mitigation is the whole point of Deliverable 2: **the harness compares our bytes against the
real parser's bytes on every run, and is CI-gated against every upstream release.** Clean-room
without that harness would be reckless; with it, drift surfaces as a failing diff naming the exact
JSON path.

#### 3.1.1 Why this generalises to the whole replacement programme

The end goal is progressive replacement — parser, then compiler, then sqlgen/plangen, then
execution. Legend's pipeline is four seams:

| stage | input → output | seam type |
|---|---|---|
| 1 parse | text → **`PureModelContextData`** | PMCD |
| 2 compile | PMCD → **`PureModel`** | PureModel |
| 3 plan | PureModel + query → **`ExecutionPlan`** | SingleExecutionPlan |
| 4 execute | plan → **`Result`** | Result |

Each stage must *speak* its neighbours' types at the boundary. Clean-room reimplementation of the
boundary type — proven byte-identical by a differential harness — is the pattern, and stage 1
establishes it. The alternative (depending on upstream's types at every seam) would make each later
stage progressively harder to detach, which is the opposite of the goal.

#### Held-tree cost, for the record

| output shape | held | per char |
|---|---:|---:|
| protocol tree, `returnSourceInfo=true` | 16.3 MB | 5.9 B/char |
| protocol tree, `returnSourceInfo=false` | 15.8 MB | 5.8 B/char |
| legend-lite `ParsedModel` | 10.5 MB | 3.8 B/char |

**`SourceInformation` is 3% of the protocol tree** — the part assumed expensive is nearly free. Our
clean-room records should land at or below 5.9 B/char since they can be immutable and lean; either
way it is negligible against the **1,287 B/char that ANTLR allocates** to produce the same tree.

### 3.3 The other parser work, in dependency order

1. **Fix the silent-drop lexer — prerequisite for everything.** `Lexer.java:287-293` raw-skips any
   section outside `{Pure, Mapping, Relational, Connection, Runtime}` and returns **success**. You
   cannot delegate a section you have already swallowed. Make it a loud, delegable miss.
2. **`###Service`** — `ElementParser.java:333` already has `case SERVICE`, but the lexer discards
   `###Service` bodies, which is how all 43 real ones are spelled. The parser supports a construct
   the lexer never delivers. Cheapest real coverage win in the tree.
3. **`###Connection`** — legend-lite fails 0/3 today despite `Connection` being whitelisted. A bug.
4. **`DataElement`** — `###Data` parses but produces 0/4 matching elements.
5. **`SectionIndex` + the section model** — the *entire* remaining element-count gap. With it,
   §9's 384/384 becomes exact rather than "after removing SectionIndex".
6. **Protocol emission** — a transformer, not a redesign: the AST record names and fields already
   mirror the protocol *"by mechanical renaming"* (`ValueSpecification.java:7-14`).
7. **Positions** (§3.2), then `returnSourceInfo=true` becomes reachable.
8. **Coverage** for remaining sections, and the §4.3 drop-list decisions.
9. **Delete `MappingGrammarParser.java:432-440`** — it deliberately swallows the tail of an XStore
   block after a missing comma. Incompatible with any equivalence claim.
10. **Fix the column-base disagreement** — `TokenStreamCursor.java:295` starts columns at 0,
    `LegendCompileException.java:66` at 1. Engine is 1-based with an inclusive end column (§3.2 of
    the feasibility doc).

---

## 4. Phasing and gates

### Phase 0 — harness (2–3 weeks)
Build §2. **Gate: harness green with the *engine* parser on both sides.** This proves the
instrument before it is used to judge anything.

### Phase 1 — prove the seam with zero sections claimed (1 week)
Ship the `ServiceLoader` jar registering **nothing**. Everything falls through to upstream; output
must be bit-identical to no-jar. Proves deployment, rollback, and packaging in isolation from
correctness. Converts "will the SPI accept an override?" from inference to fact.

### Phase 2 — parser upgrade (§3), section by section
Order: Connection → Diagram → Runtime → Relational → Mapping → Pure. Mapping requires the
`MappingElementContext` shim spike **before** committing — it is the difference between ~500 and
~1,800 lines.

**Per-section exit criteria, all required:**
- S1 semantic equivalence: 100% on that section's corpus slice
- 100% of that section's `PARSER error at` pins reproduced **including ranges**
- zero fuzz divergence over an agreed volume
- no regression in the other sections' slices

### Phase 3 — shadow mode
Both parsers on **real production traffic**; compare; log divergence; **always return upstream's
result.** Zero risk, unbounded corpus, inputs no test author imagined.

**Gate: no section flips until ≥30 days of shadow traffic at zero divergence.** This is the gate I
would defend hardest against being skipped — it is what makes it a production claim.

### Phase 4 — flip, one section at a time
Per-section flag, config rollback, shadow still running post-flip, auto-revert on divergence.

### Phase 5 — S3 byte-identity and the endgame
Only after S2 is green everywhere. Then: delete grammars, drop the ANTLR plugin, unpin.

---

## 5. Standing invariants

- **CI against every upstream release.** 156 grammar files under active development — and the 38
  commits we just pulled include *"Add multi-line ('''...''') string literals to the Legend
  grammar"*, a live grammar change. Upstream's own house style is `assertJsonEquals`, not byte
  equality, so we hold a stricter bar than they do and their drift will break us in ways it does not
  break them.
- **The composer moves in lockstep** — upstream convention, ~215 tests assert text→JSON→text.
- **Never let an unowned section parse "successfully."**
- **Re-run the extractor's coverage report every release**; a corpus that shrinks is a failure.

## 6. Kill and re-scope criteria

- **`MappingElementContext` shim fails** → Mapping cost triples; re-scope to delegate `###Mapping`
  permanently and claim the rest.
- **Fuzz divergence that does not converge** → the two grammars differ in ways nobody can
  enumerate; stop at S1 and never claim byte-identity.
- **§3.2 memory cost erodes §0.1** → fall back to positions-on-demand, and re-measure before
  proceeding.
- Not a kill criterion: parse being a small share of request latency (§0.1).

## 7. Open questions

1. **EMIT's true value as a corpus** — §2.3, research in flight.
2. **Does the `PureGrammarParserExtensions` registry reject a second claimant for `"Pure"`?**
   Duplicate keys throw at registry construction; our extension must be the only one claiming it,
   which is fine, but confirm the legacy `DomainParser` does not also register.
3. **Memory cost of the position component** (§3.2).
4. **Where the harness module lives** — `parser-equivalence/` vs `core/src/test`. Prefer a module:
   it needs upstream grammar artifacts that `core` must not depend on.
