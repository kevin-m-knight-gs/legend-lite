# Deep audit — legend-lite parser

> **BURN-DOWN STATUS (2026-08-12 evening, merged with
> PARSER_ADVERSARIAL_AUDIT_2026_08.md's list):** the ranked action list
> is substantially LANDED. Fix-now items: 1a `&& chain.infix()` +
> INFIX_FAMILIES deleted (plus flag-preserving `withParameters` across
> every generic walker — two corpus regressions proved the flag dies in
> any copier that rebuilds by hand); 1e TailEmitter sealed switch;
> 1b quote-aware `unquoteSegments`/`splitFqn` + segment unescape;
> decimals emit `BigDecimal.toString()`; floats DIALECT-SPLIT (CFloat
> only on the ENGINE wire — PCT's legend-pure reference needs the
> promotion at PLATFORM/LITE, G6-verified); lexer backslash clamp;
> projections refuse on strict paths; H4 shift remap deleted (62-bit
> runtime guard in SQL; the Out-of-Range prefix strip stays, documented,
> because PCT interval tests need the native text); H2 classify()
> VERSION-SKEW pardon → `docs/version-skew-claims.tsv` (24 rows,
> shrink-only adjudication obligations); H5 advisory-SQL ceiling seeded
> at MEASURED 297 (this doc's 246 counted tests, not diffs); M1
> OwnCorpus/OwnDialect tests in gate 8 + per-class ran-check (the
> failIfNoSpecifiedTests flag is REQUIRED by the -am core build — the
> script now verifies each class ran instead); 1c section-boundary
> family fixed by engine-true raw sectionizing (line-anchored `###` is a
> hard stop in every lexer scan loop); `'''` = verbatim
> `processTextBlock` port; `1e3d` lexes; `[2..1]` carries (record
> invariant removed — engine's POJO has none); structural: registry is
> THE routing authority (PmcdParser tables deleted), ruleGroup typed,
> ~236-line Persistence fossil deleted, Z* probes deleted (44 files),
> benchmark methodology fixed (comparable work, min-of-3, blackhole).
> `AdversarialParityTest` (gate 8) pins every divergence family at
> zero. Remaining open: the SPI eight-point growth (§2c/action 13 — the
> genuinely large item), full balanced-scan toolkit adoption, composer,
> Java-8 reactor build. See git history from 47ba9965.

Run 2026-08-12 against `main` @ `f2e8ecb7` (+ one local CI commit), with
`legend-engine` @ `4.137.0-36-g943d38b3dc2` and oracle jars @ `4.138.2`.

Method: audit by pipeline stage (contract → re-derivation probe → placement),
docs banned as evidence, every finding cited to `file:line`. Claims that could
be executed were executed — the empirical section below is differential output
from lite's parser and the real `PureGrammarParser` running in one JVM.

---

## 0. The headline claim, verified

I ran the full eight-gate chain under `caffeinate`. **All eight green, 6m14s.**

```
G1 core suite     31s   4047 tests, 0 fail
G2 core install    8s
G4 DuckDB corpus 101s   319 h2-exec verified, 0 diverged
G5 h2 corpus      49s   23/23
G6 PCT full       83s   1109/1109
G7 PCT h2modern   27s   (one-directional gate, as documented)
G8 parser parity  75s
```

Gate 8's actual numbers:

```
oracle accepts        : 5920
  docs byte-MATCH     : 5920      <- the PMCD claim
  docs DIFF (BUG)     : 0
  we-refuse (BUG)     : 0
oracle rejects        : 2196  (both-reject 1920 -> 276 asymmetric)
files byte-identical  : 5911     <- SPI seam
engine JSON-asymmetry : 9
parseStrict lenient   : 187
files in scope 1768 / parse cleanly 1589 / parse failures 179
```

**The claim is true within its scope and false as stated.** Precisely:

- "100% byte compatible including full PMCD" means: over the **5,920 documents
  the 4.138.2 oracle accepts**, out of an 8,116-source SHA-pinned manifest,
  lite's PMCD JSON is byte-identical. That is real, currently enforced, and
  strong — it includes every `sourceInformation` span.
- It does **not** mean the parsers agree on the language. 2,196 corpus sources
  the oracle rejects are outside the byte claim; 276 of those are asymmetric
  (in `docs/refusal-allowlist.tsv`), 26 of them on the shipped document surface.
- "byte-identical" is the **serialized PMCD JSON**, not source-text round-trip.
  `PmcdParser.parseDocument` hand-builds that JSON with a `StringBuilder`
  (`PmcdParser.java:97-128`) and is byte-matched against Jackson's output.
- There is **no grammar composer** anywhere in the repo. Engine uses
  `compose(parse(T)) == T` as its bijectivity oracle
  (`TestGrammarRoundtrip.java:127`) specifically to detect inference in its own
  parser. That oracle does not exist here, and JSON parity cannot replace it —
  both sides can agree on a lossy answer.

### Scope caveat found while verifying

Oracle jars are pinned at **4.138.2** (`parser-equivalence/pom.xml:28`); the
corpus files come from the checkout at **4.137.0-36**. Newer grammar reading
older files is the safe direction, but it means **any construct introduced in
4.138 has zero corpus coverage by construction**.

---

## 1. Empirical: I broke byte parity in 30 minutes with hand-written input

This is the most important result in the audit. I wrote ~90 small Pure documents
covering constructs the corpus does not contain, and ran them through lite and
the real engine parser side by side. **Three defect classes, all confirmed, none
in the allowlist, none reachable by the 5,920-document sweep.**

### 1a. CRITICAL — `^new` key expressions silently truncate. 17/17.

`ProtocolEmitter.java:2577-2590` strips an infix chain to its first atom
(a genuine engine quirk, correctly reproduced). It decides "is this infix?" by
testing the **function name** against a hardcoded set
(`INFIX_FAMILIES`, `ProtocolEmitter.java:2338-2340`) — not by reading the
`AppliedFunction.infix` flag, which the parser sets correctly at eleven sites
and which **no code in the repository ever reads** (`AppliedFunction.java:71`;
`asInfix()` at `:93` has zero call sites).

So any key expression whose function is *named* like an operator but *spelled*
as a prefix or arrow call is truncated to its first argument and the rest is
deleted from the wire.

```
^a::A(v = plus(1, 2))          -> BYTE-DIFF, lite 268 chars shorter
^a::A(v = (1)->plus(2))        -> BYTE-DIFF
... and 15 more: minus, times, divide, lessThan, lessThanEqual,
    greaterThan, greaterThanEqual, equal, and, or  (17/17 diverge)

controls, genuinely infix:  1 + 2 | 1 - 2 | true && false | 1 < 2  -> all ok
```

Concretely, `^a::A(h = true, x = plus(1,2))` emits
`"expression":{"_type":"integer","value":1}` where the engine emits the full
`{"_type":"func","function":"plus","parameters":[1,2]}`. The `plus` call is gone.

**Fix: add `&& chain.infix()` at `ProtocolEmitter.java:2579`; delete
`INFIX_FAMILIES`.** One line. The field already exists and is already correct.

The same fact is re-derived a *third* time, differently, in the compiler:
`InfixArith.NARY_ARITH_CARRIERS` (`InfixArith.java:30-43`) uses a different name
set (has FQN spellings the emitter lacks, lacks the comparisons the emitter has),
consumed at `Typer.java:281`. Two copies that already disagree.

### 1b. HIGH — quoted path segments containing `::` split wrong. 10 cases.

`Protocol.unquoteSegments` (`Protocol.java:2740-2762`) scans for `::` **without
respecting quotes**, so `a::'b::c'` splits into `a` / `'b` / `c'` — none of which
starts-and-ends with a quote, so nothing unquotes, and the caller's
`lastIndexOf("::")` then yields the wrong package and name.

```
Class a::'b::c' { }     engine: package "a",      name "b::c"
                        lite:   package "a::'b",  name "c'"
```

Confirmed on: class name, package segment, enum, profile, association,
supertype reference, property type reference, import statement, mapping name —
9 of 9. Plus a separate escaping bug in the same function:

```
Class a::'b\'c' { }     engine: name "b'c"       lite: name "b\'c"
```

`unquotePath` has **104 call sites** across the parser (`DatabaseProtocolParser`,
`MappingProtocolParser`, `TokenStreamCursor`, six section grammars).

Corpus coverage of this construct: I grepped both upstream checkouts.
**Zero `.pure` files** use a quoted path segment containing `::` or an escaped
quote. The 5,920-document sweep cannot see any of this.

**Fix: make `unquoteSegments` quote-aware and unescape inside quoted segments.**
~20 lines, one function.

### 1c. MEDIUM — a leniency family the corpus cannot reach

Six cases where lite accepts what the engine refuses, one where it refuses what
the engine accepts:

| input | engine | lite |
|---|---|---|
| `Class a::B { } ###Mapping` (mid-line `###`) | refuse | **accept** |
| ` ###Mapping` (leading space) | refuse | **accept** |
| `###Mapping` inside a block comment | refuse | **accept** |
| `###Mapping` inside a multi-line string | refuse | **accept** |
| `/* Class a::B { }` (unterminated block comment) | refuse | **accept** |
| `99999999999999999999` (overflowing integer) | refuse | **accept** |
| `[1 + 2]`, `[1 + 2, 3]`, `[true && false]`, `[1 < 3]` | refuse | **accept** |
| `1 <> 2` | refuse | **accept** |
| `'aAb'` (unicode escape) | **accept** | refuse |

The section-boundary cases share one root cause, and it is architectural: the
**engine sectionizes on raw `\n###` before any lexing** — `CodeLexerGrammar.g4`
is three rules and knows no section names. **Lite sectionizes inside its lexer**,
after comment and string handling, against a hardcoded 25-name set
(`Lexer.java:283-290`, recognition at `:214` is not line-anchored while the
opaque-skip terminator at `:318` is). Two different models of what a section is.

The collection cases are a one-rule slip: engine's `expressionsArray`
(`M3ParserGrammar.g4:102`) takes `expression`, which cannot contain an
arithmetic or boolean part; lite calls `parseCombinedExpression`
(`SpecParser.java:988, 994`). The argument-list rule is correct, so this is a
slip rather than a policy.

### 1d. Error messages: 0/14 identical

Nothing in the estate compares error message text — `RejectionParityTest.java:22`
says so explicitly. I measured it. Fifteen hand-written syntax errors, fourteen
refused by both:

```
message-identical  :  0 / 14
line-identical     : 13 / 14
column-identical   : 12 / 14
```

Positions are **better** than the docs claim. Messages are categorically
different, and structurally so: the engine's messages carry a grammar-derived
alternatives list —

```
engine: Unexpected token '}'. Valid alternatives: ['=', ';']
lite  : expected SEMI_COLON but found BRACE_CLOSE ('}')
```

That list comes from `e.getExpectedTokens()` against the ATN
(`ParserErrorListener.java:59-67`). A hand-written recursive-descent parser has
no follow-set to compute it from — it would have to be typed by hand at every
call site and kept in sync forever. Studio renders that list.

Also: `ParseException` (`ParseException.java:16-17`) carries a single
`(line, column)`; engine's `SourceInformation` carries a four-field span. Studio
underlines ranges.

### 1e. CRITICAL by inspection — five persistence entry kinds emit invalid JSON

`TailEmitter.java:1459-1469` appends `,"<key>":` and *then* handles only
`Node` and `Scalar`. `PPersistenceEntry` is sealed with **seven** variants
(`Protocol.java:1368-1412`), all seven constructed by the general `parseNode`
that the assertion body uses (`PersistenceSectionGrammar.java:376-504`, assertion
site `:671-682`). A `Strings`, `Pointer`, `PathValue`, `PathList` or `NodeList`
entry emits a key with **no value** — syntactically invalid JSON.

This is the only `instanceof` chain over a sealed protocol type in all four
emitters. Everywhere else the codebase uses an exhaustive `switch` with no
`default`, which is exactly the compile-error discipline `ProtocolEmitter.java:36`
claims. The one place the discipline was abandoned is the one place it fails
silently.

**Fix: `switch` over the sealed type with a throwing default.** One line
restores the invariant.

---

### 1f. More silent-wrong, found by reading (not yet executed)

The correctness sweep found these by tracing code paths end to end against the
engine's `.g4` and walkers. Highest-value ones:

- **`'''…'''` text blocks implement none of the engine's algorithm.**
  `TokenStreamCursor.java:886-915` vs `PureGrammarParserUtility.processTextBlock`
  (`:120-158`). Five independent divergences, the largest being **no unescape at
  all**: `{doc.doc = '''\na\tb\n'''}` yields a literal backslash-t. Also: indent
  is taken from the closing line only (engine uses the min over all non-blank
  lines), no per-line trailing-whitespace strip, no CRLF normalization, no
  `[ \t]*` skip after the opener.
- **Decimal literals emit the raw source lexeme, producing invalid JSON.**
  `ProtocolEmitter.java:2056-2064` appends `dec.written()` unquoted.
  `let x = 007d;` → `"value":007`. `let x = .5d;` → `"value":.5`. Neither is
  parseable JSON. Fix: emit `dec.value().toString()`.
- **Floats that lose double precision are silently retyped as `decimal`**
  (`SpecParser.java:808-812`). The javadoc claims "we match that behaviour
  verbatim"; engine `DomainParseTreeWalker.java:1163` builds `CFloat`
  unconditionally. `1.0000000000000001` → engine `{"_type":"float","value":1.0}`,
  lite `{"_type":"decimal",…}`. Wrong type *and* wrong value.
- **`Class P projects Q { ... }` skips the body and emits an EMPTY class** —
  `ElementParser.java:823-832`, no wall, every dialect. The association twin at
  `:1263` correctly throws.
- **`Association A projects B` silently becomes a `ClassDefinition`** —
  `ElementParser.java:1234-1250` returns the wrong element kind, while the
  protocol entry at `:1263` throws on identical input. The two entry points
  disagree.
- **Lexer runs past end-of-source on a trailing backslash** —
  `Lexer.java:364,375` do `pos += 2` with no clamp; the token is emitted with
  `end == length + 1` and `TokenStream.text` substrings past the end. A file
  whose last three characters are `'a\` raises a raw
  `StringIndexOutOfBoundsException`.
- **Over-strict where the engine is permissive:** date component validation
  (`SpecParser.java:895-909` rejects `%2021-02-29`, `%2020-13-01`,
  `%2020-01-01T25:00:00` — engine stores digit runs with no range check);
  `[2..1]` multiplicity (`TokenStreamCursor.java:1081`); `1e3d` is not lexed at
  all (`Lexer.java:385-407` only runs `scanExponent` inside the fractional
  branch).
- **Island string scanning is not escape-aware** — `IslandScan.java:107-116`
  uses `indexOf('\'')`, so `#{I {byName('it\'s')}}#` — valid engine input — is
  refused with a misleading "trailing backslash" error.
- **Key-drop whitelists**: MongoDB `jsonSchema` copies only 8 known keys and
  invents `bsonType: "object"` (`MongoDBSectionGrammar.java:168-198`);
  Persistence accepts arbitrary keys and passes them **straight to the wire**
  unvalidated and unescaped (`PersistenceSectionGrammar.java:365`,
  `TailEmitter.java:1140-1156`); milestoning keys are `kv.put(key, …)` with any
  identifier legal, so `THRU_IS_INCLUSSIVE=true` is silently ignored
  (`DatabaseProtocolParser.java:450`).

Checked and **matching** the engine (so the rule is not broken here): function
`preConstraints`/`postConstraints` being parsed and dropped; graph-fetch alias
naked quote-strip; `[*]` vs `[0..*]`; unary-minus folding; the `%2020-01`
leading-`%` quirk; `ProtocolEmitter.str` is a correct JSON escaper including
Jackson's uppercase control-char hex.

---

## 1g. Hardcoded to pass tests — one confirmed, and one large laundering path

The direct answer to "did we hardcode things just to pass tests?"

**Clean where it matters most.** A full sweep of `core/src/main/java/com/legend/parser/**`
and `com/legend/protocol/**` found **no corpus file name, element name, package
name or SHA in any code condition**. The `engine-fixture#NNN` and
`TestMappingGrammarParser` strings that appear are comments citing the fixture
that established a grammar decision — evidence trails, not branches. That is a
real defence and worth stating.

**But two things are load-bearing and shouldn't be.**

**H4 — a literal test expectation returned from a remap function.**
`pct/.../ExecuteLegendLiteQuery.java:1035-1040`:

```java
if ((message.contains("shift value") && message.contains("is out of range"))
        || message.contains("Overflow in left shift")
        || message.contains("Overflow in right shift")) {
    return "Unsupported number of bits to shift - max bits allowed is 62";
}
```

That returned string is exactly what the PCT `assertError` shift tests expect.
Implement a shift limit of 31, or 63, or produce a shift error for an unrelated
reason — every one is rewritten into the passing message. The test can never
detect the real boundary. The adjacent prefix-strip (`:1043-1052`) additionally
erases the DuckDB error *class*, so a `Conversion Error` compares equal to an
`Invalid Input Error`.

**H2 — `CorpusSweepTest.classify()` is a pardon function, and its fall-through
pardons every ordinary ANTLR syntax error.**
The javadoc at `:332-335` says classify is *"DIAGNOSTIC labeling … the ALLOWLIST
FILES are what gate acceptance, never this method."* I verified that is false:
for the PLATFORM-surface population, `classify()` returning **anything non-null**
is the pass (`:243-247` → `assertEquals(0, unclassified.size())` at `:305`). The
allowlist TSVs gate only the `docAccepts || strictAccepts` population.

And the fall-through at `:423-431` returns `"VERSION-SKEW-grammar"` for
`Unexpected token` / `mismatched input` / `extraneous input` / `missing … at …`
— *the* standard ANTLR grammar-refusal messages. Essentially any grammar
divergence on that surface is labelled version skew and pardoned. (The
`Field 'X' is required` arm correctly returns null; that one is right.)

Compounding it: `parser-equivalence/pom.xml:27-29` states the invariant in
writing — the oracle is *ahead* of the corpus checkout, *"so VERSION-SKEW must be
zero"* — and **nothing asserts it**. There is also a live allowlist row justified
in the impossible direction (`relationMappingSetup.pure`, "the CHECKOUT's newer
grammar" — the checkout is older).

**H1 — the structural one: byte parity is blind on ~27% of the corpus, and
deleting a capability scores as improvement.**
Bytes are compared only where the oracle accepts. ~2,196 of 8,116 sources sit in
the oracle-rejected region where nothing is compared; `bothReject` is counted and
never bounded (`CorpusSweepTest.java:215-216`), and
`SectionParseSentinelTest.java:161-162` scores `catch (Throwable) { legalRefusals++;
matched++; }` as "matching behaviour". So removing a grammar capability exercised
only in oracle-rejected files moves files LENIENT→MATCHED and pushes **four
ratchets green at once**. The project's own mutation experiment found exactly
this (`git show e5f47c44`, `allVersionsInRange`); it was plugged with unit tests,
but the structural hole is unchanged.

**H5 — golden-SQL divergence cannot fail the build; 246 tests currently rely on
it.** `EngineTestExecutor.java:891-895` routes `sql-text:` failures into an
advisory counter; `Runner.java:1447-1450` marks the test PASS with
`", N advisory sql diff(s)"`. The column is written to the scoreboard and never
asserted. Structurally wrong SQL passes if one row assert also passes.

**M1 — two enforcement tests that assert real invariants never execute.**
`tools/allgates.sh:176-178` runs an explicit `-Dtest=` list that omits
`OwnCorpusConformanceTest` (asserts `violations`, `overflows`, `unclassified` all
empty) and `OwnDialectCensusTest` (asserts `unquarantined`, `unmarkedExtension`
empty). `docs/OWN_CORPUS_DECISIONS.md:7` names the former as the authority for a
zero-unclassified claim that is never computed. This exact failure mode has
already happened twice (`77babb32`, `fae8b55c`).

**The admitted-defect inventory**, read from source (GATES.md's register is
stale):

| ceiling | value | file:line |
|---|---:|---|
| `MAX_PARSER_LENIENT_ACCEPTS` | 187 | `CorpusSweepTest.java:93` |
| `MAX_DROP_IN_DEFECTS` | 184 | `SectionParseSentinelTest.java:255` |
| `MAX_LENIENT` | 69 | `SectionParseSentinelTest.java:280` |
| `MAX_UNJUSTIFIED_LENIENCY` | 52 (⊂ 69) | `SectionParseSentinelTest.java:326` |
| `MAX_SEAM_LENIENT_ACCEPTS` | 22 | `CorpusSweepTest.java:85` |
| `MAX_LENIENCY_KINDS` | 21 kinds ≈ 268+ fixtures | `FixtureAdjudicationTest.java:100` |
| `MAX_ENGINE_JSON_ASYMMETRY` | 9 | `CorpusSweepTest.java:89` |
| `MAX_OVER_STRICTNESS` | 6 | `FixtureAdjudicationTest.java:101` |

≈**477 admitted parse/parity defects across eight ceilings**, plus 276
allowlist rows, plus uncapped populations that nothing bounds at all (246
advisory SQL passes, 276 non-passing corpus tests, 223 stereotype-excluded
tests).

**Legitimate data tables, checked and cleared** — worth naming so the fixes
don't touch them: `docs/refusal-allowlist.tsv` (276 rows, per-row reasons, has
only ever shrunk: 620→290→283→281→276); `docs/model-refuse-allowlist.tsv` (5
rows); `CorpusManifestTest` (SHA-256 per source, 8,116 rows — this is what makes
the absolute floors mean anything); `ComparatorSelfTest` (proves the comparators
can report a difference); `EngineQuirks` (one constant, one documented engine
bug, one use site); `allgates.sh:59-77` (treats an `Assumptions` skip as
failure). And the PCT `EssentialFunctions` expected-failures were verified
against the real DuckDB adapter manifest — the ledgered diffs are identical,
including the one disclosed divergence. That one is honest.

---

## 2. Architecture

### 2a. The macro defect: two section routers, and the registry is not the authority

`SectionGrammarRegistry.java:11` calls itself "THE section-routing authority".
It is consulted from **two lines in main**, both in `ElementParser` (`:397`,
`:468`). `PmcdParser` — the drop-in surface — never reads it. It carries its own
hardcoded `switch` (`:273-341`), its own `TAIL_SECTIONS` list (`:66-70`, comment:
"mirror of the registry's elementwise grammars"), its own `TAIL_GRAMMARS` map
(`:604-636`), its own `IMPORT_AWARE` set (`:58-62`), and its own
`ACTIVATOR_SECTIONS` (`:72-77`, declared and never read).

Adding one built-in section is a **five-site edit** across two packages, four of
those sites unguarded. Section knowledge has five homes: the lexer's name set,
the registry, `ElementParser`'s three boundary walks, `PmcdParser`'s four tables,
and each grammar's `lexable()` flag. The failure mode has already fired once —
`Lexer.java:276-278` records "Elasticsearch, 2026-08-10", and the fix was to add
a string to the second list rather than delete one of them.

### 2b. The dogfooding claim is false, and shadowing is a diagnostic-free no-op

`SectionGrammarRegistry.java:11-17`: *"Built-ins register through the SAME
registry a third-party overlay will use — the dogfooding rule that keeps the
plug-in path honest."*

Built-ins share the map; they do not share the contract.
`SectionGrammar.parse(SectionSource, ElementSink)` — the SPI's only method — is
invoked from **exactly one call site in the repository**
(`ElementParser.java:440`, guarded by `!lexable()`). Every built-in's `parse()`
override is dead code in main and in test. The four `BuiltIn` stubs for
`Pure`/`Mapping`/`Relational`/`Data` throw when called
(`SectionGrammarRegistry.java:34-46`) — the honest statement that they are
lookup placeholders.

Consequence: an extension that registers `name() == "Mapping"` wins the map, and
then nothing happens. The lexer still tokenizes with Pure rules;
`claimedSections()` only claims `LexableSectionGrammar` instances, which an
SPI-only class cannot be; `parseSingleElement` runs the built-in; `PmcdParser`
never consulted the registry at all. **Zero diagnostics.**

Worse, there is a **silently unhandled arm**: a registered grammar with
`lexable() == true` (the SPI default is `false`, but it is a public method a
third party may override) matches none of the three arms at
`ElementParser.java:396-447`. Its elements vanish with no exception, no record,
not even in `unclaimedSections()`. The existing drift guardrail
(`LexerRegistryAgreementTest.java:33-38`) explicitly exempts overlays.

### 2c. The SPI is one-eighth of what the host needs

Engine's `PureGrammarParserExtension` has **eight** extension points
(`extension/PureGrammarParserExtension.java:33-68`), used by **27 non-test
implementations** registered through 29 `META-INF/services` files.

`com/legend/spi/` is three types, **64 lines**, with one extension point.
`ElementSink.accept(String fqn, String protocolJson)` can only deliver a
top-level packageable element by FQN. It cannot express:

- a **mapping element** (`getExtraMappingElementParsers` — returns a
  `ClassMapping`, nested inside someone else's element)
- a **connection value** (`getExtraConnectionParsers` — returns a `Connection`,
  a value, not an element)
- an **embedded data payload** (`getExtraEmbeddedDataParsers` — and it is handed
  the extension registry back, for re-entrant dispatch)
- a **test assertion**, a **mapping include**, an **embedded pure island**

`RelationalGrammarParserExtension` alone implements four of the eight. Under the
lite SPI, relational mappings and relational connections become unrepresentable.
There is also no **import channel**: the SPI feeds parse imports and discard the
return value (`ElementwiseSectionGrammar.java:36-38`,
`ConnectionSectionGrammar.java:58-61`), so an overlay section can never be
import-aware.

Registry semantics also regress: engine throws on a conflict
(`PureGrammarParserExtensions.java:190-201`); lite does `m.put` in a
`ServiceLoader` loop (`SectionGrammarRegistry.java:111`) — silent
last-writer-wins, ordered by classpath, at class-initialization time with no
per-instance injection point and no error isolation.

### 2d. Below section level there is no seam at all

`MongoDBSectionGrammar` and `ServiceStoreSectionGrammar` are SPI-registered and
own their `###` store syntax — while their **mapping** syntax is hardcoded as
arms 565-575 / 588-735 / 765-779 of a private if-chain in
`MappingProtocolParser`. The store half is pluggable; the mapping half requires
a core fork.

Same for expressions: adding an operator touches **seven sites in three
packages**; adding an island form means editing `SpecParser`'s
`switch (dslType)` with its `default -> throw` (`SpecParser.java:2898-2912`).

### 2e. God classes, and the 3500-line cap that is producing line-shaving

Six main-source files sit at **3483–3500 lines against a 3500-line guardrail**
(`StoreResolver` 3500, `Lowerer` 3500, `MappingNormalizer` 3499, `Scalars` 3494,
`MappingProtocolParser` 3493, `StatementExecutor` 3483). There is **no
enforcement mechanism anywhere** — no checkstyle `FileLength` rule, no script,
only prose.

The evidence that it is being managed by hand rather than by design:

- Four files carry javadoc saying they exist *because of* the cap —
  `MappingEmitter.java:16-18` ("split from ProtocolEmitter when the Mapping leg
  outgrew the file guardrail"), `ConnectionEmitters.java:9-12`, `RelationReads.java:26`,
  `ImplicitInheritance.java:20`. Relief valves, not responsibility splits.
- `MappingEmitter` still carries the extraction scar: `:274-393`, `:572-599`,
  `:602-647` are indented one level too deep with dangling closes — a mechanical
  lift with no re-layout.
- `MappingProtocolParser` has **five orphaned javadoc blocks** (`:201`, `:935`,
  `:2609`, `:2682`, `:3408`) — a doc comment immediately followed by another, so
  the first documents nothing. Zero such blocks in the other parser files. That
  is the fingerprint of methods being shuffled under a line budget.

`ElementParser` (2701 lines, **22 public entry points**) is five stages fused:
file driver, section router, dialect policy gate, declaration grammar, testable
grammar, embedded-data char scanners, annotation grammar, and static token-scan
utilities. `MappingProtocolParser` (3493) contains **fifteen distinct grammars**,
three of which (embedded data, test suites, ServiceStore/MongoDB class mappings)
are not mapping vocabulary and already have homes or public seams elsewhere.

`Protocol.java` (2765) is **not** a god class — ~200 immutable records with four
helpers, cohesion is "the wire". The fix there is to flatten the nested records
to top-level in a package, not to split.

### 2f. One home per feature — the violations

| feature | homes | already diverged? |
|---|---|---|
| embedded data / test payloads | `ElementParser:1691-2277` **and** `MappingProtocolParser:213-3347`, into **two protocol families** (`PTestPayload` vs `PEmbeddedDataValue`) with **two emitters** | **yes** — `ElementParser:1792` refuses non-`ExternalFormat` ModelStore entries that `MappingProtocolParser:2622` accepts; `csvCells` uses `.trim()`, `splitCells` uses `.strip()` |
| annotation grammar `<<p.s>> {p.t='v'}` | `ElementParser:2579-2692`, `TokenStreamCursor:756-815`, predicate also in `SpecParser:2084` | **yes** — different wire spans (`spanOf(profStart,pos-1)` vs `spanOf(vS,vS)`), three different `{` disambiguation rules |
| `joinSequence` | `DatabaseProtocolParser:771` and `:549`, `MappingProtocolParser:1651` | **yes** — the view-filter copy supports neither typed joins nor mid-chain re-anchor |
| multiplicity parsing | 3 copies in `MappingProtocolParser` (`:1254`, `:1821`, `:2149`) plus the shared `TokenStreamCursor:1065` | **yes** — all three copies omit the `upper >= lower` check the shared one enforces |
| import grammar | `ElementParser:769`, `SectionImports:18`, `PmcdParser:381` inline, `PmcdParser:241` scan | different strictness |
| graph-fetch island grammar | `SpecParser:3076-3213` (token re-lex) **and** `IslandScan.java` (409 lines, charwise) | **yes** — empty-body refusal is dialect-gated in one, unconditional in the other |
| "slice to a delimiter at depth 0" | **16 copies** in `MappingProtocolParser` + ~38 more across the parser (54 `int depth =` sites) | — |
| sub-parse span origin | **4 incompatible mechanisms** (`slice`, pad-relex, re-lex+shift, charwise rescan) | **yes** — of three offset-composition sites, `ConnectionSectionGrammar:488` does not compose at all, `PersistenceSectionGrammar:785` composes unconditionally, `RuntimeSectionGrammar:174` composes with the correct first-line guard |

### 2g. Re-derivation (the highest-yield probe)

- The lexer knows **island extent** (`islandDepth`, `Lexer.java:123`) and discards
  it — recomputed by **12 hand-rolled depth loops in 7 files**, three of them the
  flat form the codebase itself documents as broken
  (`ElementParser.java:2088-2091` names the truncation bug; the canonical correct
  implementation is **private**).
- The lexer knows **section end offsets** and stores `pos+3` instead — recomputed
  as `nameOffset - 3` at `ElementParser:473` and `:660`, and as `headerStart - 1`
  in `PmcdParser:153`. **Three end conventions for one boundary.**
- The lexer knows whether a **delimited literal was terminated** (it exits the
  scan loop differently at EOF, `Lexer.java:358/368/380/477/486`) and encodes it
  nowhere — re-derived from characters at four sites, forgotten at a fifth
  (`docStringValue:887` does `substring(3, len-3)` on unterminated input).
- The parser knows the **section it is in**; `parseSingleElement` re-asks
  `inNonPureSection(offset)` per element (`ElementParser:604`), and
  `nonPureRanges` (`:648`) is a cache built to make the re-derivation cheap
  rather than to remove it. This produces a real bug: `Mapping x() (...)` written
  inside `###Relational` is **accepted by the drop-in surface** because the gate
  only tests "not Pure".
- The emitter re-derives ~14 facts the parser knew — and the signature case is
  `PmcdParser.java:434-445`, which recovers element kind by
  `e.json().startsWith("{\"_type\":\"service\"")`. The typed element was
  stringified two frames earlier.
- The parser **corrupts a span so the emitter's inference lands**:
  `MappingProtocolParser:1800` calls `withSpanStart(op, oS)` purely because
  `MappingEmitter:1486` derives the otherwise-mapping's span from the operation's.
- `PEmbeddedPropertyMapping` (`Protocol.java:696`) has no `source`/`target`
  fields; the parser computes `srcId`/`tgtId` at `:1708-1722` and **drops both**
  at `:1811`. `prop[a,b] ( ... )` loses both ids silently.
- **Seven copies** of `lastIndexOf("::") + substring` across the compiler, plus a
  single-colon variant at `Typer.java:2672`.

---

## 3. Performance

The lexer is close to optimal: three parallel `int[]`, no per-token object, text
on demand, enum-switch dispatch, zero regex, zero streams in `parser/`, no
exception-driven backtracking on hot paths. Two genuine super-linear behaviours:

**H1 — one line, zero risk, the best ratio in the audit.**
`TokenStream.java:231` seeds `int line = 0;` when rebuilding `tokenPositions`
for a slice. Token starts are file-absolute, so a slice whose first token is at
file line L pays L iterations before resolving its first token. Cost is
**O(K·L)** for K slices — and there are 37 slice sites, one per lambda /
property mapping / constraint / default value. A 10k-line mapping with 500
property mappings burns ~5M wasted iterations.
Fix: `int line = n > 0 ? lineOf(starts[0]) - 1 : 0;` — `lineOf` is already an
O(log L) binary search over the shared `lineStarts`. Behaviour identical.

**H2 — the island path is O(K·N).**
`MappingProtocolParser.java:2954-2958` scans **from file offset 0** counting
newlines to derive a line/column that `tokens.lineOf()` already holds in
O(log L). Then `:2960-2968` builds a whitespace pad of that size, copies it, and
re-lexes it — and `Lexer.java:134` allocates ≥48 KB of int arrays per call
regardless of island size. Twelve call sites. Same idiom at `:1566` and `:3209`.

**H3 — graph-fetch islands are lexed twice and parsed twice** (main lexer →
text reconstruction → `Lexer.tokenize` → full parse → *plus* a third charwise
pass in `IslandScan`). Root cause: `parseDsl` hands sub-grammars a
re-concatenated `String` instead of a token slice, destroying offsets — which is
precisely why the 409-line `IslandScan` exists to recover them.

Smaller: `PmcdParser.skipTo` is a linear rescan per section (`:668`, should be
binary search); section-index dedup is O(E²) via `List.contains` (`:110-118`);
`parseQualifiedName` allocates 5 objects for the single-segment case
(`TokenStreamCursor:462`, 118 call sites); the lexer allocates a String per
identifier just to do a keyword map lookup (`Lexer.java:558`, ~40% of tokens).

**`ParseSpeedBenchmarkTest` is not a valid measurement.** It obeys the
`System.nanoTime` rule, but: the two sides do different work (lite parses **and
serializes the whole document to JSON**; the oracle only parses — this alone
disqualifies the ratio); one timed sample per source, no repetitions, no median
or min; fixed order, oracle always first; no GC settling; `catch (Throwable
ignored)` **inside the timed region**; results discarded with no blackhole.

---

## 4. The adversarial case (a hostile legend-engine maintainer)

The three objections that would actually kill the PR:

**1. It cannot be built in that repository, and fixing that invalidates the
evidence.** `legend-engine/pom.xml:144-146` sets `release=8` and `:559-560`
enforces `maxJdkVersion=1.8` as a build-failing rule. legend-lite is Java 21
with **590 record/sealed declarations across 273 files**, and its correctness
argument rests on exhaustive sealed switches (`ProtocolEmitter.java:36-37`).
The port is a rewrite, and no line of the 5,920-document measurement describes
the code that would land.

**2. The SPI cannot host the extension ecosystem, and the gap is structural.**
27 extensions, 8 extension points, versus 1. Their own bridge registers exactly
one parser — `SectionParser.newParser("Pure", ...)`
(`LegendLiteSectionParser.java:44`) — so the proven seam replaces `DomainParser`
and nothing else. "Drop-in replacement for `PureGrammarParser`" is, on the
shipped evidence, a drop-in replacement for **one of ~30 section parsers**.

**3. It is not their protocol and it has no error channel.** A parallel
`Protocol` hierarchy that explicitly refuses to depend on
`legend-engine-protocol-pure` (`Protocol.java:11-13`), reconstituted via a
Jackson round-trip per element; the serialization contract would live in two
repos, and `ProtocolEmitter.java:22-29` hardcodes their Jackson configuration as
literal rules. Meanwhile: 0/14 error messages match, `ParseException` carries a
point not a span, and the grammar-derived alternatives list has no equivalent.

Also raised, all verifiable: `parseLambda` (20 call sites),
`parseValueSpecification` (9), `parseGraphFetch` (5) and the `columnOffset` /
`returnSourceInfo` parameters of `parseModel` **do not exist** in lite's public
surface; `shiftSpans` never touches columns; `ServiceLoader` at class-init with
no isolation and no per-instance extension set; no depth guard anywhere in the
parser — I confirmed 5,000 nested parens is a `StackOverflowError`.

**What would change their mind** (the actionable half):

- A green `legend-engine` reactor build with the lite module in-tree passing the
  bytecode enforcer, **plus the parity sweep re-run against that artifact**.
- A working `RelationalGrammarParserExtension` against the lite SPI, exercising
  all four points it uses today, passing `TestRelationalGrammarRoundtrip` and
  `TestMappingGrammarParser` unmodified.
- Typed protocol emission with no JSON string intermediate, **and** a gated
  error-parity harness: mutate each of the 5,920 passing documents into a syntax
  error, gate on identical error type, message, and four-field span.

And, said plainly: the differential harness itself is worth contributing
standalone, as are the upstream crashes and the `1 < 2 + 3 * 4` mis-association
that no engine test pins.

---

## 5. Test estate

**8,429 lines of assertion-free scratch code compile on every gate run.**
44 `Z*.java` + `ProbeWireShapes.java`: 116 `@Test` methods, **6 assertions total**
in 3 files, run by **no gate** (gate 8 passes an explicit `-Dtest=` list). They
are `javac`-compiled on every `clean test`, 15 of them compile against production
parser APIs — so dead code can turn the flagship gate red on a signature change,
which has already happened (`c07c8a10`). Worse, `docs/COVERAGE_CENSUS.md:20,29,58`
derives its headline coverage numbers from three probes **that have never run**.

**16 of 25 section grammars have no gated hand-written fixture**; 7 of those also
have zero `.pure` corpus files (`BigQuery`, `HostedService`, `FunctionJar`,
`Text`, `Deephaven`, `MongoDB`, `QueryPostProcessor`). `PmcdParser` has **no unit
test at all**.

**The drop-in dialect is barely unit-tested.** `ElementParserTest` (3,483 lines,
207 tests): 134 use `Platform.model`, **3** use `Engine.at`. `SpecParserTest`
(3,009 lines, 218 tests): 236 `Platform.spec`, **zero** at `LEGEND_ENGINE`. The
6,492 lines of parser unit test exercise a deliberately broader dialect than the
one the parity claim is about.

**Ratchets that currently guard nothing:** `MAX_DROP_IN_DEFECTS = 184`
(`SectionParseSentinelTest.java:255`) while the section program reported DEFECT
zero on 2026-08-09 — the test's own comment at `:252` makes exactly this
complaint about the *previous* ceiling ("sitting 82 above the actual count, so it
could not have caught a regression of any size worth catching") and then leaves
the new one 184 above. `MAX_LENIENT` history `57 → 55 → 68 → 69`;
`MAX_UNJUSTIFIED_LENIENCY` history `127 → 39 → 51 → 52` — a "down-only" ratchet
that has gone up twice.

`FixtureAdjudicationTest`'s ratchet counts **message-kinds (21), not fixtures** —
so fifty new lenient fixtures pass green if their message text-matches an
existing kind.

`CorpusSweepTest.java:103` is still an `Assumptions.assumeTrue` — an absent
corpus skips **green**; the mitigation is a shell check in `allgates.sh`, not a
test floor. And gate 8 runs with `-Dsurefire.failIfNoSpecifiedTests=false`, so
any future rename silently shrinks the gate.

---

## 6. Documentation integrity

This matters because the next session will read the docs first.

**Six tests cited as proof no longer exist**: `PmcdEquivalenceTest`,
`StrictDialectParityTest`, `LeniencyCatalogTest`, `SpiSeamProofTest`,
`CorpusEquivalenceTest`, `MappingEquivalenceTest` — deleted in the 08-12 sweep
collapse. I verified all six absent.

`docs/GATES.md`'s section headed **"Live ratchet constants (the authority — read
them, do not trust this table)"** is 100% dead: every row cites a deleted file,
and its values (22,725 / 170 / 742 / 8 / 4,051) have no relation to the live ones
(5,920 / 22 / 187 / 9 / 5,911). Its gate-8 command names three deleted classes.
`DEEP_AUDIT_HANDOFF.md` calls `PmcdEquivalenceTest` "the audit's strongest
regression net".

The headline element number **has no gate behind it any more**. Element-level
comparison was demoted to a diagnostic (`CorpusSweepTest.java:58-63`); the
justification is one empirical observation plus a structural argument. The
merge-readiness audit had specifically identified `MIN_MATCHES`-at-zero-headroom
as "the real teeth".

Also stale in the *other* direction, which is worth saying: the lexer's
"silent swallow" is fixed — skips are recorded as `TokenStream.SkippedSection`
and consumed at `ElementParser.java:396`. And three comments still claim
"###Service emission walls loudly" (`ProtocolEmitter.java:80-83`,
`Protocol.java:1005-1010`, `ServiceSectionGrammar.java:26`) while
`TailEmitter.service` emits a complete 160-line service wire.

---

## 7. Ranked action list

**Fix now (small, confirmed, silent-wrong):**

1. `ProtocolEmitter.java:2579` — add `&& chain.infix()`, delete `INFIX_FAMILIES`.
   Closes 17/17 confirmed key-expression truncations. One line.
2. `TailEmitter.java:1459` — exhaustive `switch` over `PPersistenceEntry`.
   Stops five variants emitting invalid JSON. One line + a throw.
3. `Protocol.unquoteSegments` — make it quote-aware and unescape inside quoted
   segments. Closes 10 confirmed byte-diffs across 104 call sites. ~20 lines.
4. `ProtocolEmitter.java:2056` — emit `dec.value().toString()`, not the raw
   lexeme. `007d` currently emits unparseable JSON.
5. `SpecParser.java:808` — always `CFloat` for a FLOAT token; the
   float→decimal retyping is wrong type and wrong value.
6. `Lexer.java:364,375` — clamp `pos = Math.min(pos + 2, length)`. A trailing
   backslash currently crashes with `StringIndexOutOfBoundsException`.
7. `ElementParser.java:823` and `:1234` — throw for class/association
   projections instead of emitting an empty class / the wrong element kind.
8. Delete the shift-message remap (`ExecuteLegendLiteQuery.java:1035-1040`).
   It is the only literal test-knowledge in the tree.
9. Delete the `VERSION-SKEW-grammar` fall-through in `CorpusSweepTest.classify()`
   and correct the javadoc at `:332-335`. Largest laundering path, one edit.
10. `TokenStream.java:231` — seed `line` by binary search. Removes an O(K·L)
    term. One line, zero behavioural risk.
11. `InfixArith.NARY_ARITH_CARRIERS` — replace the name set with `af.infix()`.
12. `SpecParser.java:988,994` — collection elements should use `parseExpression`
    at engine level, or declare the widening. Gate `<>` on dialect (`:544`).
13. Port `processTextBlock` verbatim into `docStringValue`, with the unescape
    flag; and route quoted FQN segments through `unescapeBody`.

**Structural, in dependency order:**

8. One `SectionCatalog` — delete `PmcdParser`'s four tables and
   `Lexer.LEXABLE_SECTIONS`; route `PmcdParser` through the registry. Kills the
   five-site edit, the drift class, and the unhandled-`lexable()` arm.
9. Extract `EmbeddedDataParser` + `TestSuiteParser`; delete `Protocol.PTestPayload`
   and `ProtocolEmitter.testPayload`. Closes a live divergence and deletes a
   duplicate protocol family.
10. Promote the shared primitives — `sliceUntil`, `paddedRelex`,
    `parseJoinSequence`, one `parseMultiplicity`, one `parseDecorations`,
    one `SpanOrigin`. Mechanical, no wire change, immediately shrinks
    `MappingProtocolParser` below any cap.
11. Make islands lex in place (`Lexer.tokenize(source, from, to)` with absolute
    offsets). Deletes `IslandScan`'s duplicate graph grammar, three
    offset-composition rules, the pad idiom, and H2/H3.
12. Migrate `Pure`/`Mapping`/`Relational`/`Data` to real `LexableSectionGrammar`s.
    Makes the dogfooding claim true and fixes the "not Pure" section-binding gate.
13. Grow the SPI to the eight points, with a context object for re-entrancy and a
    return type carrying imports. This is the merge blocker, and it is the one
    item on this list that is genuinely large.

**Instrument and hygiene:**

14. Add `OwnCorpusConformanceTest` and `OwnDialectCensusTest` to the gate-8 list;
    drop `-Dsurefire.failIfNoSpecifiedTests=false` so a rename goes red.
15. Assert on advisory SQL diffs with a down-only ceiling at 246, and assert
    `VERSION-SKEW-grammar == 0` per the pom's own written invariant.
16. Delete the 41 assertion-free `Z*` probes (~7,200 lines); re-derive
    `COVERAGE_CENSUS.md` from code that runs, or mark it unverified.
17. Ratchet every ceiling to its measured value and make them self-tightening
    (fail when `actual < ceiling - N`), the way gate 7 already works. Restore a
    per-fixture ceiling alongside `MAX_LENIENCY_KINDS`.
18. Replace `Assumptions.assumeTrue` in `CorpusSweepTest` with a corpus-size
    floor.
19. Build the error-parity harness: mutate the 5,920 passing documents, gate on
    message + four-field span. This is the largest unmeasured surface that is
    cheap to measure — and it is the artifact the adversarial reviewer names.
20. Design a **construct-coverage floor** independent of the oracle's verdict.
    This is the only structural item on the list: without it, deleting grammar
    capability will keep scoring as improvement.
21. Widen `InlineSnippets.PURE_DECL` to the existing `OWN_DECL` and regenerate
    the manifest; the review is the diff, which is what the manifest is for.
22. Correct `docs/GATES.md`, `DEEP_AUDIT_HANDOFF.md`, `COVERAGE_CENSUS.md` and
    the three stale "Service walls loudly" comments.
23. Enforce the 3500-line cap in the build or drop it. A number with no
    enforcement and no design rule behind it produces files that stop at 3493.
