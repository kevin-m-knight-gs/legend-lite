# Parser drop-in — status and handoff

> **Read this first if you are picking the work up.** It is the state of play, the reasoning that
> got here, and the mistakes already made so they are not made twice.
>
> **Branch:** `parser/drop-in` (17 commits, not merged). **Companions:** `PARSER_DROP_IN.md`
> (feasibility evidence), `PARSER_DROP_IN_PLAN.md` (the plan).
>
> Measured against `legend-engine d0b4c3a2f68` / `legend-pure 63a4b2c68`, both pulled to
> origin/master 2026-08-04.

---

## 1. The goal

Replace legend-engine's grammar parser with legend-lite's — 100% complete, **byte-identical**,
production drop-in — as stage one of progressively replacing legend-engine
(parser → compiler → sqlgen/plangen → execution).

**Not for speed.** Speed is the weakest of six reasons:

| reason | measured |
|---|---|
| **Memory** | 1,287 B allocated per source char vs 25 — **52×**; 3.6 GB to parse 2.8 MB; +31.5 MB retained just to warm the parser |
| **Generated code** | 132 of 1,508 jar classes are ANTLR output but **46% of the bytes**; 156 `.g4` / 14,568 lines deleted |
| **Build** | `antlr4-maven-plugin` at `generate-sources` in 41 modules |
| **Currency** | ANTLR pinned 4.8-1 (2020); today it can only move with all generated code |
| **Debuggability** | recursive descent vs an ATN simulator |
| speed | 54.5× — real, and least important |

**There is no latency gate.** The project is justified at 0% speedup.

---

## 2. The architecture — and the correction that matters most

### 2.1 Where it landed (correct)

**The drop-in is a closed subsystem that depends on nothing but the JDK.**

```
com.legend.lexer    ─┐
com.legend.parser   ─┤  the drop-in — JDK only, extractable and shippable alone
com.legend.protocol ─┘
        ↑ consumed by
com.legend.model.FromProtocol      ← legend-lite's adapter (NOT part of the drop-in)
com.legend.model → compiler → lowering → sql → exec
```

- **One parser, one output.** The parser builds `com.legend.protocol` records — nothing else.
- **`ProtocolEmitter` turns them into bytes** byte-identical to legend-engine's. It is the only
  upstream-shaped code in the tree, and it knows nothing about `com.legend.model`.
- **`FromProtocol` is legend-lite's consumer**, living on the model side. It is the direct analogue
  of what legend-engine's compiler does with `PureModelContextData`, and it is **stage 2's input
  adapter** — not throwaway.
- **Protocol types are a SUPERSET of the wire's.** legend-lite's parser is deliberately more lenient
  than the Legend grammar (it reads legend-pure's M3 corpus). Protocol must carry everything the
  parser can produce so the parser stays **total**; the *emitter* walls on what the wire cannot
  express.

### 2.2 Our tenets are kept; only the bytes match theirs

The protocol is a **serialization contract, not a design constraint**.

| | upstream's protocol | ours |
|---|---|---|
| mutability | mutable public fields | **100% immutable records** |
| variance | Jackson `@JsonSubTypes` | **sealed interfaces with `permits`** |
| dispatch | runtime type-id lookup | **javac-enforced exhaustive switch, no `default ->`** |
| unknown shape | silently absent / null | **throws** (AGENTS.md invariant 4) |

`ProtocolEmitter`'s dispatch has no `default` arm, so adding a protocol variant without an emit rule
is a **compile error** — the same discipline invariant 3 imposes on MIR → dialect.

### 2.3 The correction — DONE 2026-08-04

**Landed, by MOVE rather than duplication.** `com.legend.protocol` reached into
`com.legend.model` for `TypeExpression`, `Multiplicity`, and two records nested inside
`ClassDefinition`. Instead of giving protocol duplicate types and transforming, the shared
syntax vocabulary was recognized as what it is — **parse products** — and moved INTO protocol:

- `TypeExpression`, `Multiplicity`, `SourceInfo`, `Realization` → `com.legend.protocol`
- the whole untyped value-spec AST `com.legend.model.spec.*` → `com.legend.protocol.spec.*`
  (this **resolves §4.1.1's one-family question**: the value-spec family IS the protocol now;
  the `CInteger`/`CString` position work is on the right side of the boundary after all)
- `ClassDefinition.{ParameterDefinition, DerivedPropertyDefinition, ConstraintDefinition}` →
  top-level `com.legend.protocol` records (they are syntax; `PropertyDefinition` stays nested
  in the model element, which remains a separate family — the FQN-key discipline)
- `ProtocolToModel` → `com.legend.model.FromProtocol`

**Elements remain two families + transform** (`FromProtocol`), for the documented reason:
model keys by single `qualifiedName` (NAME_RESOLUTION_BUG.md defense); the wire's
`package`/`name` split happens only on emission.

**Enforced by ArchUnit invariant 7** (`ArchitectureTest`), all allowlists:
- **7a** — `lexer` depends on the JDK, full stop.
- **7b** — `protocol` depends only on `values` + JDK. No model, no parser, no lexer.
- **7c** — `parser` depends only on `lexer + protocol + model + values + error` — with
  `model..` marked shrinking: it dies when the last element kind emits protocol records.
- **6j amended** — `model → protocol` is the sanctioned direction; `6c'` strengthened —
  the resolver sees no parse product at all.

The extractable drop-in artifact is therefore `{lexer, parser, protocol, values, error}`,
where `values`/`error` are themselves JDK-only leaves (6g).

---

## 3. What is built

**Branch `parser/drop-in`, 17 commits.** ~2,077 lines added.

| | |
|---|---|
| `com.legend.protocol.Protocol` | sealed, immutable protocol records |
| `com.legend.protocol.ProtocolEmitter` | byte-exact emitter, no `default` arm, walls loudly |
| `com.legend.protocol.{TypeExpression, Multiplicity, SourceInfo, Realization, spec.*}` | the parse vocabulary, moved to the bottom (§2.3) |
| `com.legend.model.FromProtocol` | protocol → model adapter, on the model side |
| `com.legend.lexer.TokenStream` | lazily-built line index, binary search |
| `parser-equivalence/` | **the differential harness** |

### 3.1 Current numbers

```
core suite            : 1625 tests, 0 failures

parser-equivalence:
  corpus sources      : 2289 files
  verdicts            : 6053
    MATCH (byte-equal): 5725
    DIFF  (BUG)       :    0
    WALL  (no rule)   :  273
    PARSE_FAIL        :   55
  coverage            : 5725 of 5725 comparable (100.0%)
```

Progression: 1,750 → (superTypes) → 5,152 → (annotations) → 5,638 → (generics) → **5,725**,
DIFF 0 throughout. Remaining walls: constraints 222, qualifiedProperties 40, defaultValue 9,
generic multiplicity args 2. (Walls report the FIRST failure per element — clearing one wall
grows the next's count as elements progress deeper.)

### 3.2 How to run it

```bash
export JAVA_HOME=/Users/neemsandv/jdk/jdk-21.0.11+10/Contents/Home
export PATH=$JAVA_HOME/bin:/Users/neemsandv/jdk/apache-maven-3.9.9/bin:$PATH

mvn -o test -pl core                  # 1608 tests
mvn -o install -pl core -DskipTests   # harness consumes the installed jar
mvn -o test -pl parser-equivalence    # the differential gate
cat parser-equivalence/target/equivalence-report.txt
```

Corpus roots default to `~/legend/legend-engine` and `~/legend/legend-pure`; override with
`-Dlegend.engine.root` / `-Dlegend.pure.root`.

### 3.3 The harness

Compares **emitted bytes**, per element, against the live upstream parser.

- **Bytes, never object graphs** — `CString.multiLine` is excluded from `equals()` and present in
  JSON; a graph comparator would silently miss it.
- **Mapper pinned** to `getNewStandardObjectMapperWithPureProtocolExtensionSupports()`. The repo's
  other mapper is non-deterministic; using it measures nothing.
- **Per element by FQN**, so a file containing unsupported constructs still yields verdicts for the
  supported ones — that is what turns the corpus into a ranked worklist.
- **DIFF is the only failing outcome.** WALL is expected while coverage grows.
- **Four anti-false-green guards**: a run with no verdicts fails; non-zero input asserted; coverage
  reported; **the corpus may not shrink** (baselines `MIN_ELEMENTS_COMPARED`, `MIN_MATCHES` — raise
  them as coverage grows, and say why in the commit if ever lowered).

---

## 4. The worklist

### 4.1 Immediate

1. ~~**The §2.3 refactor.**~~ **DONE 2026-08-04** — protocol standalone, ArchUnit invariant 7
   enforces it (7a lexer=JDK-only, 7b protocol=values+JDK, 7c parser surface pinned).
2. **The ValueSpecification emitter** — 270 of the 359 remaining walls, and the foundation for
   everything after `Class`. Wire shapes already captured:
   - `lambda` `{"_type":"lambda","body":[…],"parameters":[…]}` — no `sourceInformation` on the lambda itself
   - `func` `{"_type":"func","function":…,"parameters":[…],"sourceInformation":…}`
   - `property` `{"_type":"property","parameters":[…],"property":…,"sourceInformation":…}`
   - `var` `{"_type":"var","name":…,"sourceInformation":…}` — but in lambda `parameters`,
     `{"_type":"var","multiplicity":{…},"name":…}` with **no** sourceInformation
   - `integer` `{"_type":"integer","sourceInformation":…,"value":1}`
3. ~~`Generic` type expressions~~ **DONE 2026-08-04** — `TypeExpression.NameRef`/`Generic` carry
   `@Nullable pos` (excluded from equality, guarded by `TypeExpressionEqualityTest`); `parseType`
   threads spans; the emitter recurses (`GenericTypeEmissionTest` pins engine bytes end-to-end;
   generic SUPERTYPES emit base path only — engine drops the args from the wire, verified).
   The harness reflection is gone too: `ElementParser.at(ts, i)` + `topLevelIndexes` +
   public `parseClassDefinition` are the per-element protocol entry points.
   **Remaining `Class` walls**: constraints 222, qualifiedProperties 40, defaultValue 9,
   generic multiplicity arguments 2 (`<T|m>` — wall until the wire shape is probed).

### 4.2 Then, in order

Finish `Class` → the rest of `###Pure` (Enum, Association, Profile, Function, Measure) → other
sections. Section frequency across legend-engine (2,002 occurrences): Pure 904, Mapping 533,
Relational 245, Runtime 78, Diagram 60, Connection 49, Service 43, then a tail.

**Before other sections, fix the silent-drop lexer.** `Lexer.java:287-293` raw-skips any section
outside `{Pure, Mapping, Relational, Connection, Runtime}` and returns **success**. You cannot
delegate a section you have already swallowed. This is the prerequisite for the SPI fall-through
that makes coverage incremental.

### 4.3 Corpora not yet wired

`Corpus.java` covers file-based tiers only. Still to extract: **~3,191 inline `###` snippets across
253 Java test files** (needs a source-level extractor), **410 `PARSER error at` pins** (the only
expected-error oracle anywhere), 532 + ~477 guaranteed-parseable positives, 26 regenerated golden
pairs.

---

## 5. Lessons — the expensive ones

**Every one of these cost real time. They are the most valuable part of this document.**

1. **A green that could have come from an absence of evidence is worthless.** My first byte-identity
   experiment reported a spectacular zero failures. It was a `BUILD FAILURE` — no tests ran, no
   surefire XML, and the counter scored "no evidence" as "no failures." Every gate must assert that
   work actually happened.

2. **Search terms hide corpora.** 410 parser-error pins were invisible because I grepped an outer
   class name and the base classes are *nested* (`TestGrammarParser.TestGrammarParserTestSuite`).
   Extract programmatically; never let a count come from a search term you chose.

3. **Fixtures rot; live comparison does not.** Upstream's only structured `.pure`↔protocol corpus
   drifted for 16 months because `validate()` was commented out. 19 of 26 differ from today's
   parser — 8 provably golden-stale, **0 parser-wrong**.

4. **Measure before believing your own instrument.** The harness said 3,927 `superTypes` walls; my
   sanity grep said ~940 and I nearly "corrected" it. The grep was line-anchored and missed
   multi-line declarations — the real count is 4,579 `extends`. The instrument beat the spot-check.

5. **Put limits in the layer that owns them.** My first wiring made the *parser* wall on
   non-`NameRef` types and silently default a multiplicity parameter: 75 failures, 699 errors. The
   parser must stay total; the emitter owns what the wire can express.

6. **Silent drops are the enemy, including ours.** The harness's first run found property default
   values emitted as quietly-missing bytes because `ElementParser` documented *"parsed and DROPPED
   for now."* A documented gap no test had covered. Loud walls, always.

7. **Do not ship a shortcut with a label on it.** I added a `spans` side-table and called it
   "Phase-0 scaffolding" — knowingly breaking *one parser one output* and *no mutable sidecar state*.
   Reverted. If it needs a disclaimer, it needs a different design.

8. **The project's own guardrails work — let them.** The null gate caught two uninitialised
   `@NonNull` fields; ArchUnit caught a package cycle (`model → protocol → model`) and an invariant
   violation I had not noticed. All three were right.

9. **Check provenance before defending a divergence.** I claimed legend-lite's element shapes
   differed from the protocol "for principled reasons." Traced: `engine/`'s records were modelled on
   the **Pure grammar**, with no protocol reference at all, and `core/` mirrors those. They are
   *independent*, not principled — judge them field by field. (One *is* principled: the single
   `qualifiedName` with no `simpleName()`, a structural defence against the simple-name collision in
   `NAME_RESOLUTION_BUG.md`.)

---

## 6. Traps and facts worth not rediscovering

- **The SPI seam is a jar, not a fork.** `PureGrammarParser.visitSection` consults extension
  `SectionParser`s **before** the four hard-wired legacy ones, so a jar registering `"Pure"` wins
  over `DomainParser`. `PureGrammarParser.newInstance()` is constructed **per HTTP request**.
- **`SourceInformation` convention**: 1-based lines, 1-based start column, **inclusive** end column
  (`+ text.length()`, no `+1`). `columnOffset` applies **only on line 1**.
- **An asymmetry**: a stereotype's `sourceInformation` covers the whole `a::P.s1`; a **tag's covers
  only the tag name**. Verified, encoded in `PTag`'s javadoc — do not "fix" it.
- **Class annotations follow the keyword**: `Class <<stereo>> {tags} a::B`, not before it.
- **Exclude `m3.pure`** from any corpus: 3,607 lines, 2,186 of `^Root.children[…]` bootstrap-instance
  syntax, zero normal declarations.
- **legend-pure's error messages are not reusable** — ANTLR-shaped, built on a different
  `SourceInformation` type. Use those cases for *which input fails and where*, never message text.
- **`engine/` inside legend-lite is our own predecessor module** (`com.gs.legend`), not the FINOS
  repo. Six-plus `core/` records say *"Mirrors engine's `com.gs.legend.model.def.*`"* and every one
  means the local module.
- **Upstream moves.** Three grammar/protocol changes landed in the ~3 weeks we were behind:
  multi-line `'''…'''` strings (in `CoreLexerGrammar`, so every island DSL inherits them), a second
  service-test surface syntax, and a Colspec/constraint ambiguity fix. `CString.multiLine` is
  **excluded from `equals()` and present in JSON** — the finding that forced byte comparison.

---

## 7. Not yet started

Fuzz mode; shadow mode (30-day zero-divergence gate before any flip); CI wiring; the empty-jar seam
proof; the composer, which upstream requires to move in lockstep (~215 tests assert
text→JSON→text).
