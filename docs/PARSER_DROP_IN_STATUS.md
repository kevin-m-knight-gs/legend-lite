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
core suite            : 1634 tests, 0 failures

parser-equivalence:
  corpus sources      : 2289 files
  verdicts            : 6053
    MATCH (byte-equal): 5991
    DIFF  (BUG)       :    0
    WALL  (no rule)   :    7
    PARSE_FAIL        :   55
  coverage            : 5991 of 5991 comparable (100.0%)
```

**CLASS EMISSION IS COMPLETE** (→ 5,991): qualifiedProperties (bare bodies, typed-var
params, REAL stereotypes/taggedValues — the "engine consumes and drops" comment was
engine-lite lore, refuted by a harness DIFF), ~owner (single identifier; engine rejects a
list), braced + zero-param pipe lambdas (every inline lambda spans PIPE..body-end),
dot-spelled property calls (property nodes with appended args, NAME-token span —
dot..close-paren belongs only to the .all()/getAll desugar), strictDate literals, and the
LET RULE: a let value's TOP node takes the letFunction's own span, whatever its kind, while
nested nodes keep theirs (ProbeWireShapes "let zoo", all value kinds verified; the override
rides beside the node — baking it into pos corrupts the n-ary chain-span derivation).
Remaining 7 walls: generic multiplicity args 2, Named 2, NewInstance 2, path-literal var 1.

**Element probes for the rest of ###Pure are already captured** (scratchpad probe5.txt +
ProbeWireShapes): Enumeration (_type "Enumeration" — capitalized!), profile (bare
stereotype/tag name-span lists), association (reuses the property machinery), function
(name is SIGNATURE-MANGLED: f_Integer_1__String_MANY__Integer_1_). Engine REJECTS native
functions and Measure in ###Pure outright — both permanently out of comparable scope.

Progression: 1,750 → (superTypes) → 5,152 → (annotations) → 5,638 → (generics) → 5,725 →
(defaults) → 5,733 → (constraints) → 5,864 → (ptr/enum/lambda/float/unary/level/externalId)
→ **5,948**, DIFF 0 throughout. Remaining walls: qualifiedProperties 40, constraint ~owner 3,
braced-lambda span 3, generic multiplicity args 2, dot-spelled property call 1,
ptr span (one synthesis path) 1, CDate 1.

**The harness caught its first real fidelity bugs and they are FIXED**: `~externalId` was
parsed-and-dropped (audit-21a's exact failure mode) — 2 DIFFs, now emitted; a third DIFF
exposed that the wire emits a DOT-spelled call (`$t.getInteger('count')`) as a PROPERTY
node, not a func — recorded via `AppliedFunction.propertyCall` (equality-excluded spelling
marker) and walled until the property-node shape is probed.

**More wire facts pinned** (ConstraintEmissionTest, all engine-verified): `.all()` →
`getAll` spanning DOT..close-paren (dot-calls and arrow-calls span differently); an enum
value is a property on a `packageableElementPtr` (no enumValue node); an INLINE lambda
spans pipe..body-end and its untyped params are bare `{"_type":"var","name":…}` while typed
ones carry genericType+multiplicity+declaration span; unary minus is a one-parameter func
(no collection) spanning the operator token; `~enforcementLevel` sorts first,
`~externalId` second, among constraint fields.

**The value-spec emitter now covers the constraint surface**: literals (boolean/integer/
string — a string's span INCLUDES its quotes), `var`, property access, calls, collections,
and the full operator families with their VERIFIED span conventions (ProbeWireShapes):
infix arithmetic + comparisons span op..RHS-end; `equal`/`and`/`or` span the operator token
only; `!` spans bang..operand-end; calls span the name token only; `plus`/`minus`/`times`
are N-ARY (one collection parameter, multiplicity [n..n], span first-op..chain-end, built by
flattening our climb's left spine); `divide` stays binary; `!=` is `not(equal(...))`, both
spanning the `!=` token. Constraints emit `functionDefinition`/`messageFunction` lambdas
with the synthesised span-less `$this` parameter (conform-by-emission).

**A planned wall died on evidence**: `s + 'a' + 'b' == 'x'` — our grammar ALREADY matches
engine's flat binding (`==` binds into the preceding operand, so the `equal` lands inside
the `plus` collection on both sides). Byte-pinned in `ConstraintEmissionTest`. Only the
explicitly PARENTHESISED equal-over-arithmetic form remains walled (bytes unprobed).

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

### 4.1 ###Pure — **100% COMPLETE (2026-08-05, commit 5342f934)**

**10,106 verdicts / 10,106 MATCH / 0 DIFF / 0 WALL / 0 PARSE_FAIL / 0 REFERENCE_REJECTED**
over ~2,289 corpus files. Every ###Pure element the engine parses — Class, Enumeration, Profile,
Association, Function (including legend-testable test-suite blocks) — emits byte-identically.
Ratchets: `MIN_ELEMENTS_COMPARED = MIN_MATCHES = 10106` (lowered from 10,375 with cause: 269
phantom sites — decl keywords appearing as identifiers — produced PARSE_FAIL verdicts matching no
real engine element; the site finder now uses a POSITIVE predecessor rule: a declaration keyword
counts only at stream start, after `}`, or after `;`).

Late-arc wire rules, all probed via `ProbeWireShapes` and encoded in `ProtocolEmitter`:

- **Graph fetch** (`GraphFetchLiteral` carrier, charwise island scan): classInstance
  rootGraphFetchTree with the `_type` key **doubled** on root + property nodes (engine Jackson
  quirk); spans absolute (class-name / name-token); the let rule overrides the OUTER span only.
  Args: string/integer/boolean/var reuse expression shapes (var span = name only, no `$`);
  `%dates` are always `dateTime` and **keep** the `%`; dotted enums are real `enumValue` nodes;
  collections carry `multiplicity` size/size and **no** sourceInformation. `'alias':prop` rides an
  `alias` field; `p->subType(@X)` rides `subType` after `subTrees`; `//` island comments skip.
- **Path-literal args** (typed `PathArg`): `%date` → `dateTime` **without** the `%`, same `a-1`
  shift rule as `%latest`; `Enum.VALUE` → span-less `enumValue`.
- **CTime** → `strictTime`, written form verbatim (record now carries `written` + `pos`).
- **RelationType in signatures**: span-less wrapper genericType + rawType; column span =
  name (quotes included)..type-end; undeclared column multiplicity is `0..1` **on the wire**
  (declared multiplicity walls — unprobed).
- **Function test suites** (`PTestSuite`/`PFunctionTest`/`PTestParam`): unnamed block → id
  `"default"` spanning the braces; named → name..close-paren; test span includes the semicolon;
  single `equalTo` assertion, id `"default"`, spanning the expected; `parameters` key only when
  the call has args, bound to signature parameter names by position; `tests` is the last key.
- **Type-variable values** (`Varchar(200)`, `Numeric(10,2)`): `Generic.typeVariableValues`;
  rawType span covers the whole application.
- **Aggregation kinds** `(shared|composite|none)`: `aggregation` key, UPPERCASE, first.
- **Lexer**: bare-fraction floats (`.5`) route into the numeric scanner's fractional branch.

The harness now writes `target/walls-detail.txt` and `target/parsefails-detail.txt` — per-element
worklists — on every run.

### 4.1b Corpus expansion + burn-down — **ABSOLUTE ZERO (2026-08-05, 9168a5a9)**

The corpus widened twice past §4.1: the WHOLE engine+pure checkouts (3,450 files) and the
C4/C5 **inline snippets** (`InlineSnippets.java`: ~2,000 Java test files → 3,719 candidate
snippets, reference-parser-adjudicated). Final state: **19,258 verdicts / 19,258 MATCH /
0 DIFF / 0 WALL / 0 PARSE_FAIL** — every comparable element anywhere in both checkouts is
byte-identical. Ratchets `19,258/19,258`. Late rules worth knowing: the engine's flat
boolean/comparison chain reproduces at EMISSION (`rotateFlatBoolean` — the parse stays
semantic; a parse-side attempt broke 11 engine-suite tests and was reverted); caret
specials match exact simple/FQN spellings (+`^TdsOlapRank`); bare `Result` defaults
`<Any|1>`; function constraint blocks and colspec-annotation drops are engine data-loss
reproduced; test suites serialize fully (equalToJson/equalToRelation, testData islands:
modelStore / relationalCSVData / relation blocks with wart-for-wart span shifts);
declaration names unquote, references keep raw quoted spellings.

### 4.1c Rejection parity — **live (RejectionParityTest)**

The NEGATIVE corpus: `PARSER error at [l:c]` (engine) and
`Parser error at (resource:... line:l column:c)` (legend-pure) pins pair by adjacency
with their input snippets. **39/39 Pure-only pins REJECTED, 0 misses**, `MIN_PINS 39`;
error-line agreement 19/39 (informational — positions, never messages). It forced:
`true`/`false` are not FQN segments; test call names must match the enclosing function;
type/multiplicity parameters reject on the ENGINE-STRICT surface
(`ElementParser.legendStrict`, set by `at()`/`parseStrict`) while legend-lite's own
dialect keeps them. The plan's larger 410/69 counts include non-Pure sections and
non-adjacent call shapes — a visible extension point.

### 4.2 Then, in order

Other sections. Section frequency across legend-engine (2,002 occurrences): Pure 904 (**done**),
Mapping 533, Relational 245, Runtime 78, Diagram 60, Connection 49, Service 43, then a tail.

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
