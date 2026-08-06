# Parser implementation audit (2026-08-05)

**The question:** the parser reports 19,273/19,273 MATCH. Is it right, or is it green?

**Anchors.** legend-lite `266fe1d5`; legend-engine `943d38b3dc2` (`.g4` and walker sources);
oracle `legend-engine-language-pure-grammar-4.133.0` from `~/.m2` — the artifact
`parser-equivalence/pom.xml:38-39` declares, so it is the correct oracle for gate claims.
JDK 21.0.11, macOS aarch64.

**Method.** Five independent agents, one per axis: totality/strictness, span fidelity, grammar
divergence, architecture/duplication, robustness/diagnostics. Each was instructed to *execute*
rather than infer, to write nothing into the repo, and to report what it could not determine.
Every divergence below is an executed counterexample against the live engine unless explicitly
marked *(read from source)*. Docs were banned as evidence; where a doc is cited it is as the
thing being falsified.

> **Companions:** [`GRAMMAR_COMPATIBILITY_2026_08.md`](GRAMMAR_COMPATIBILITY_2026_08.md) is the
> section-coverage question (what we do not parse at all). This is the correctness question
> (what we parse *wrongly*). [`TEXT_SURGERY_AUDIT_2026_08.md`](TEXT_SURGERY_AUDIT_2026_08.md)
> is the string/regex question. §8 reconciles all of this against
> [`PARSER_DROP_IN_PLAN.md`](PARSER_DROP_IN_PLAN.md) §4.2.

---

## §0 — The verdict

**The parser is a sound recursive-descent implementation of a grammar that is not quite the one
legend-engine implements, measured by an instrument that cannot see the difference.**

The green is real — 19,273 byte-identical elements is not nothing, and it was earned. But it is
green over **55.9% of the corpus**, on **five element kinds**, in **one section**, against a
corpus that **does not contain the shapes where we diverge**. Three independent blindfolds, each
verified, each sufficient on its own.

The single most important finding is not a byte divergence at all:

> **`1 && 2 + 3` and `1 < 2 + 3 * 4` produce a different *semantic tree* in legend-lite than in
> legend-engine.** Not different bytes — a different tree, which the compiler then types and
> lowers. legend-lite-the-pipeline and legend-engine-the-pipeline compute different answers for
> those expressions today, and no gate in the repo compares that.

---

## §1 — Findings, severity-ranked

Severity is *consequence if it fires*, not likelihood. Corpus frequency is stated separately
because "zero occurrences today" is a property of the corpus, not of the parser.

### Tier 1 — wrong answers, not just wrong bytes

**1.1 Boolean-then-arithmetic parses to the wrong tree. 120 of 170 measured DIFFs.**

```
function t::f(): Any[*] { 1 && 2 + 3; }

ENG: plus[ and(1,2), 3 ]
OUR: and( 1, plus[2,3] )
```

`M3ParserGrammar.g4:210`'s `booleanPart: (AND|OR) expression`, and `expression` **cannot
contain** an `arithmeticPart` — so the arithmetic falls out to the `combinedExpression` loop and
*wraps* the boolean. `SpecParser.java:548-563` parses the RHS with
`parseCombinedArithmeticOnly()`. The javadoc at `:540-547` claims it is *"matching engine's
grammar."* **It is not.**

`ProtocolEmitter.rotateFlatBoolean:1191-1210` patches this at emission — but only at depth 1
(`:1197-1200` rotates when the bool's *unrotated* right child is a `FLAT_COMPARISONS` node; when
it is an `and`/`or` that only becomes one after its own later rotation, the outer decision is
made on the wrong shape). So `1 || 2 < 3 && 4` diverges too.

**Critically, the rotation is emission-only.** `com.legend.model` receives the unrotated tree.
The wire can be patched into agreement; the compiler's input cannot.

**1.2 The engine mis-associates arithmetic after a relational operator — and we are right.**

```
function t::f(): Any[*] { 1 < 2 + 3 * 4; }

ENG: lessThan( 1, times[ plus[2,3], 4 ] )   →  1 < (2+3)*4  = 1 < 20
OUR: lessThan( 1, plus[ 2, times[3,4] ] )   →  1 < 2+(3*4)  = 1 < 14
```

`DomainParseTreeWalker.processOp:1857-1876` only ever rewrites the accumulator's **last
parameter**, so a second, tighter operator re-targets a node that has already been promoted. The
engine contradicts itself: standalone `2 + 3 * 4` is `plus[2, times[3,4]]` — which matches us —
but the same subexpression after a relational operator is mis-associated.

`SpecParser.java:565-575` documents the deliberate choice to follow real Pure. Nothing documents
that legend-engine departs from it. **legend-lite is arithmetically right and byte-wrong**, and
a byte-identity mandate means reproducing the engine's bug. That is a decision for a human, not
a defect to fix silently. It should be recorded as a named deviation either way.

**1.3 Interrupted n-ary runs flatten too far.**

```
1 + 2 * 3 + 4

ENG: plus[ plus[1, times[2,3]], 4 ]      ← two 2-element collections
OUR: plus[ 1, times[2,3], 4 ]            ← one 3-element collection
```

The `* 3` closes the engine's `(PLUS expression)+` context; the second `+` opens a new one.
Our precedence climb sees one `plus` run and `ProtocolEmitter.naryArithmetic:1412-1430` flattens
the whole left spine. Fires for `+`/`-` runs interrupted by `*` or `/`; **not** for same- or
lower-tier interruptions (`1 + 2 < 3 + 4` matches).

**Exhaustive sweep of `1 ⊕ 2 ⊗ 3 ⊘ 4` over `{+ - * / < == && ||}`: 512 inputs, 170 DIFF (33%),
0 caught by any existing gate.**

### Tier 2 — wrong bytes, reachable, uncovered

**2.1 The same literal emits two different values depending on which scanner reads it.**

```
token path      : "value":"a\nb"      (real newline)
graph-fetch path: "value":"a\\nb"     (literal backslash-n)
```

`SpecParser` has **three** implementations of the same literal families: the token-level ones
(`parseInteger:721`, `parseString:798`, `parseFloat:753`, `parseDecimal:777`, …), plus two char
scanners, `scanPathArgs:2593` and `scanGraphArgs:3152`, which are the same dispatch skeleton
written twice and disagree five ways:

| | `scanPathArgs` | `scanGraphArgs` |
|---|---|---|
| `%latest` | `Latest` (:2622) | **`unsupported`** (:3214) |
| `true`/`false` | **`unsupported`** (:2670) | `CBoolean` (:3247) |
| `$var` | **`unsupported`** (:2674) | `Variable` (:3191) |
| `//` comments | **not handled** | skipped (:3174) |
| string escapes | `unescapeString` (:2650) | **raw substring** (:3187) |

Only the third implementation — the token-level one — is correct: it alone handles floats,
decimals, `strictTime`, and real spans. Neither char scanner handles floats, so `p(1.5)` becomes
`CInteger(5)` in graph-fetch and two `IntArg`s in a path literal.

**2.2 The assertion-position relation island span is wrong at every indentation but one.**

The engine's rule, pinned by execution: the `RelationElement` span **always** starts at
island-local `(1,1)` — `startColumn = columnOffset + 1`, the column just after `#{`.
`ElementParser.java:1762-1772` instead takes the block's real first-non-whitespace column and
hand-corrects with `sCol -= 1` / `sLine -= 1; eLine -= 1; eCol += 2`.

| probe | engine | legend-lite |
|---|---|---|
| content on next line, indent 6 | `startColumn 28` | `7` ✗ |
| content on next line, indent 28 (== `columnOffset + 1`) | `28` | `28` ✓ |
| content on the `#{` line | `28` | `27` ✗ |

It is right only for the one indentation that coincides with `columnOffset + 1`. Coverage:
`grep -rn 'Relation #{'` across both checkouts returns **one hit, and it is a Java error-message
string**. Zero tests in `core/src/test` or `parser-equivalence/src` mention `assertionSpans`,
`relationBlock`, or `RelationElement`. The code cites *"probe 'relation span fit' t1-t3 + corpus
#14/#62"*; none of those probes survives in the tree.

**2.3 `#>{…}#` as a bare `let` value stamps the let span on both nodes.** The engine gives the
outer `classInstance` the let span while the **inner `value` keeps the island's own span**;
`ProtocolEmitter.java:1320-1327` and `:1341-1352` reuse one `span` variable for both. All 22
corpus occurrences are `#>{…}#->select(…)`, where the let span lands on the outer `select` and
the bug is invisible. `let x = #>{…}#;` bare occurs **0 times**.

**2.4 `#TDS{…}#` emits the wrong wire shape.** Documented in
`GRAMMAR_COMPATIBILITY_2026_08.md` §2; re-confirmed here with the additional fact that the node
carries **no span at all** (`SpecParser.java:2726-2730`, three span-less nodes) and the emitter
throws `requirePos` rather than emitting. 1,998 `#TDS` occurrences in `.pure` corpus files;
**109 of them sit in files the gate skips entirely.**

**2.5 The canonical quoted-name decoder is the weaker of the two.**
`TokenStreamCursor.unquoteAndUnescape:450-483` documents itself as *"THE quoted-name decoder …
audit M11 found EIGHT copies, half of which forgot the escapes."* It handles 5 escapes and
throws on the rest. `SpecParser.unescapeString:811-845` handles 8, with a cited justification
(legend-pure `M4Fragment.g4 EscSeq` + `StringEscape.UNESCAPE_PURE`'s terminal rule). Same lexer
token, two answers, 5 for 5:

```
SPEC 'x\"y' -> CString[value=x"y]     NAME 'x\"y' -> THROW: unsupported escape '\"'
SPEC 'x\by' -> CString[value=x\by]    NAME 'x\by' -> THROW: unsupported escape '\b'
SPEC 'x\fy' -> CString[value=x\fy]    NAME 'x\fy' -> THROW: unsupported escape '\f'
SPEC 'x\ y' -> CString[value=x y]     NAME 'x\ y' -> THROW: unsupported escape '\ '
SPEC 'x\qy' -> CString[value=xqy]     NAME 'x\qy' -> THROW: unsupported escape '\q'
```

`\b` and `\f` are legal per `SpecParser`'s own cited grammar. So `Enum test::E { 'x\by' }` is a
parse error while `|'x\by'` is a value. **The de-duplication kept the weaker table.** Correct
implementation: `SpecParser.unescapeString`'s switch inside `unquoteAndUnescape`'s framing —
neither alone. Two further tables exist (`ElementParser:1457`, `:1541`, identity default so
`\b`→`b`) and two byte-identical no-op strippers (`RelationalGrammarParser.stripQuoted:798`,
`MappingGrammarParser.stripColQuotes:1591`).

**2.6 Quoted names decode inconsistently across postfix forms.**
`$x->'meta::pure::functions::math::abs'()` → engine `"function":"meta::pure::…::abs"`, ours
`"function":"'meta::pure::…::abs'"` — quotes retained. The **dot**-postfix path unquotes
correctly (`$x.'my prop'` matches). Two decodings in one parser: `parseArrowPostfix:1278` →
`parseQualifiedName` → `fqnSegmentText` returns the raw spelling; the engine's
`PureGrammarParserUtility.fromIdentifier` unquotes.

Related: **a quoted name can never start an expression.** `parsePrimary`'s
`case STRING -> parseString()` (`SpecParser.java:651`) fires before the identifier arm, so
`'abs'($x)` throws where the engine builds `abs($x)`.

### Tier 3 — rejection-parity divergences inside the "100%" section

These are all `###Pure`, i.e. **inside the surface that reports 100%**. Byte parity cannot see
them because it only compares files both parsers accept.

| # | input | engine | legend-lite | site |
|---|---|---|---|---|
| R1 | constraint clauses in any order | rejects — `complexConstraint` fixes `~owner? ~externalId? ~function ~enforcementLevel? ~message?` | **accepts** | `ElementParser.java:737-768` |
| R2 | `~function: … ~externalId: 'x'` | rejects | **accepts and silently loses `externalId`** | `:692-707`, `:761-767` |
| R3 | repeated `~externalId` | rejects | accepts, any order, any repetition | `:692` |
| R4 | `Class t::C [ ] { … }` (empty constraints) | rejects — requires ≥1 | accepts | `:657` |
| R5 | `function t::f(): Integer[1] { }` | rejects — `codeBlock` requires ≥1 `programLine` | accepts, `body: []` | `SpecParser.java:376-378` |
| R6 | `q() {}: Integer[1];` | rejects | accepts | same |
| R7 | duplicate `tags:` in a Profile | rejects — *"should be specified only once"* | accepts | profile body loop |
| R8 | constraints **after** the function body | rejects — `constraints?` sits between signature and body | **accepts, silently ignoring the block** | `ElementParser.java:1189-1220` |
| — | `a <> b` | **no such token** — parse error | lexed `NOT_EQUAL`, desugared to `not(equal(…))` | `SpecParser.java:459` |
| — | `x[0]` / `x['k']` | `AppliedProperty("oneString")`, gated off → *"Bracket operation is not supported"* | `at(x,0)` / `AppliedProperty(x,"k")` | `:2416-2428` |
| — | `~enforcementLevel: <anything>` | grammar closes it to `('Error'\|'Warn')` | **validates nothing** | `:766` → `:809` |
| — | `%2020-01-02T10:00:00Z` | rejects | accepts | `Lexer.java:418-422` |

Correctly enforced, verified BOTH-REJECT: `Class` header order, `extends` before `constraints`,
aggregation position, property slot order, every separator and trailing-comma case, `true`/`false`
as FQN segments.

### Tier 4 — crashes and diagnostics

**4.1 Twelve raw JDK exceptions escape on the *unmutated* corpus — all on input the engine
accepts.**

| type | site | n | note |
|---|---|---:|---|
| `IllegalArgumentException` | `EnumDefinition.<init>:31` | 7 | `Enum my::E {}` is **accepted by the engine** |
| `NullPointerException` | `ElementParser.parseConnection:2174` | 3 | engine's first complaint is a *different* field (`name`, not `database`) |
| `IndexOutOfBoundsException` | `FromProtocol.toAssociationDefinition:79` | 2 | `ends.get(0), ends.get(1)` with no guard |

All three are **model-record constructors doing validation the parser never performed** — the
throw is topologically outside the parser and semantically inside it. None carries a phase, a
position, or an element name. Under fuzz (~130,000 inputs) nine distinct non-`ParseException`
sites appear, two of which are **broken internal invariants**:
`TokenStream.text:85` (`Range [2341, 2385) out of bounds for length 2384` — a token's recorded
end exceeds its own source, ×29) and `TokenStream.startLine:189` (index one past the token
count, ×3).

**4.2 A stray `#` can misreport its position by 60,002 lines.** `Lexer.java:464` scans forward
to the next `{` or `#` and emits `ISLAND_OPEN` spanning whatever it crossed. Executed: a lone `#`
on **line 3** of a 60,007-line file reports

```
[60005:0] unknown DSL island type: '#\n}\nClass my::C0\n{'
```

— 60,002 lines from the fault, with 4 lines of unrelated source named as the "island type", and
**80,012 of ~140,000 tokens absorbed into island mode**.

**4.3 Diagnostics name the found token as an internal enum.** 122 distinct message heads; 42
start with `expected`; **11 name the token actually found**; **2 enumerate valid alternatives**.
The engine's shape is `Unexpected token 'X'. Valid alternatives: ['Class', 'Association', …]` on
every syntactic error. A user reading `expected identifier, got PIPE` has to know legend-lite's
lexer to learn that `PIPE` means `|`.

**4.4 A 1 MB integer literal costs 12.4 s of CPU in the parser.** `SpecParser.java:728` falls
back to `new BigInteger(text)` on `Long` overflow — O(d²), clean 4× per doubling (10k digits
1 ms → 320k digits 1,171 ms → 1M digits 12,382 ms; 395 of 400 stack samples in
`destructiveMulAdd`). The engine never pays it because it rejects the literal outright. This is
in the parser, before any size or auth policy the compiler applies.

**4.5 No recursion depth guard anywhere** — but this is *parity*, not a defect. Head-to-head in
one JVM: both overflow at 2,000 nested parens; at 2,000 nested `[` **legend-lite accepts and the
engine overflows**. ANTLR generates recursive-descent parsers; only its *prediction* is
ATN-driven. Under `-Xss256k` our ceilings fall to 98–192, which is writable by a human, so a
guard is still worth adding — as an improvement, not a fix.

### Tier 5 — architecture

**5.1 The drop-in's driver is not in the drop-in.** `ElementParser.at()` + site discovery +
`ProtocolEmitter` is the only path that produces protocol bytes and the only path the byte-parity
claim covers. It has **zero `src/main` callers** — `ProtocolEmitter` has none at all, and `grep`
for `ElementParser.(at|topLevelIndexes|measureSites|parseStrict)` in `src/main` returns one hit,
an internal recursion at `SpecParser.java:1980`. The loop that makes it usable is written twice,
both in the test tree.

**5.2 …and the two copies disagree about what an element is.** `ParserEquivalence:92-106`
discovers 5 kinds; `LegendLiteSectionParser:119` also scans `measureSites`. That single
difference is the whole of the 32 hidden `Measure` elements in §3.2.

**5.3 The layering claim is inverted.** `PARSER_DROP_IN_PLAN.md` §2.1 draws `FromProtocol` above
the drop-in, consuming it, and says it is "NOT part of the drop-in." `ElementParser` **calls** it
at 9 sites (`:378, :383, :391, :392, :876, :1078, :1797, :1799, :1800`). Only `com.legend.lexer`
is literally JDK-only (4 files, 0 non-JDK imports, confirmed); the residue is **29 distinct
`com.legend.model` types concentrated in 3 files**. `protocol` is genuinely clean — its
`com.legend.model` occurrences are all javadoc `{@link}` — so invariant 7b holds for real.

To delete 7c, in dependency order: (1) protocol has **no counterpart** for `Mapping`, `Database`,
`Connection`, `Runtime`, `Service` — those are new records, not a migration; (2) `ParsedModel` /
`PackageableElement` are the *return types* of `parse()`, with **269 call sites in `core`**;
(3) the 9 `FromProtocol` calls; (4) `LegacyMappingDefinition.TableReference` as parser field
state (`ElementParser.java:149`).

**Free first step:** all three dirty files carry the same copy-pasted 35-line
`com.legend.model` import block, of which **22 / 21 / 24 are unused**. Deleting them cuts the
declared surface by ~2/3 with zero behaviour change and makes the real residue visible.

**5.4 Twenty hand-rolled balanced-skip loops, three underflow behaviours.** There is **no
`skipBalanced` on `TokenStreamCursor`** — that absence is why 20 copies exist.
`ElementParser.skipBalancedContent:2288` counts only the passed pair;
`MappingGrammarParser.skipBalancedBlock:695` counts all three kinds;
`SpecParser.skipBalancedForLookahead:1831` is the **only one that reports failure** — every other
runs to EOF and returns as if it succeeded. `ElementParser` calls the first two interchangeably.
Three loops let depth go negative and eat the enclosing closer.

**5.5 Six sites use the token stream to find a region's bounds and then re-parse the region from
characters.** `ElementParser:1491`, `:1679`, `:1363`, `:2067`; `SpecParser:2777→2934`;
`MappingGrammarParser:113`. In every case the lexer had already tokenized the region. The
`parseDsl → wrapGraphFetch` pair is the sharpest: `parseDsl:2788-2818` runs a nesting-depth
state machine handling `ISLAND_BRACE_OPEN/CLOSE` translation, then `wrapGraphFetch:2934` re-reads
`tokens.source()` and rescans with a naive `{`/`}` counter. **The nesting state machine is
discarded, not just the text.** Conversely only `parseDsl` counts nested islands at all — all
four `ElementParser` island readers use flat `while (peek() != ISLAND_END) advance()`, so a
nested `#{ }#` terminates the outer island early. Each has the half the other is missing.

**5.6 Dead and duplicated code in the dispatch that most needs to be readable.**
`ProtocolEmitter.java:1356-1373` is a verbatim duplicate of `:1289-1306` and is **unreachable**.
`TokenStreamCursor.spanOf:594` and `ElementParser.span:2392` are two identical implementations of
the same three lines.

---

## §2 — Why the gate is green: three blindfolds, each verified

**2.1 The shapes do not occur.** A token-level, depth-aware scan of all 3,475 `.pure`/`.legend`
files in both checkouts, using legend-lite's own lexer, finds: divide-runs **5**, interrupted
n-ary runs **5**, bool-then-arith **3** (all false positives on inspection). So the largest
divergence family — 120 of 170 — has **zero real occurrences**.

**2.2 The one file that systematically tests precedence is `REFERENCE_REJECTED` in its
entirety.** `legend-pure/.../grammar/tests/composition.pure` contains
`testMultiplicationPrecedesAddition`, `testMultiplicationAndDivisionPrecedeAddition`,
`testArithmeticPrecedesRelationalOperations`, `testComplexArithmeticPrecedesRelationalOperations`,
`testBasicConjunctionPrecedence`. Every function carries a PCT header `<Z|y>`, so the engine
answers *"Type and/or multiplicity parameters are not authorized in Legend Engine."* A
`REFERENCE_REJECTED` verdict is not a failure. **Strip the PCT header and two of the five
assertions DIFF.** The gate stays green on the exact corpus written to test this.

**2.3 The n-ary collapse erases the evidence.** For `plus`/`minus`/`times` the flatten *destroys*
the per-node spans, so the run-context mismatch is structurally unobservable there. It survives
only in `divide` and the comparisons, which stay binary. The emitter is hiding the parse's
disagreement with the grammar rather than resolving it.

And `ProbeWireShapes.java:235-242`'s "precedence zoo" contains exactly `2 * 4 + 2` and
`2 + 2 * 4` — **the two cases that do match**. Never probed anywhere: any divide chain, any
comparison chain, any interrupted run, any bool-then-arith, `<>`, `x[i]`, or any three-operator
expression at all.

---

## §3 — The instruments, and what they actually measure

Fix these before anything in §1, because every §1 fix is judged by them.

### 3.1 The gate adjudicates 55.9% of the corpus

```
corpus sources           : 7219
  skipped: not pure-only : 1370   (19.0%)
  skipped: reference NULL: 1810   (25.1%)
  IN SCOPE               : 4039   (55.9%)
```

The second bucket is its own mechanism: `ParserEquivalence.java:170-172` catches `Throwable` from
the reference and returns `null`; `:83-86` then returns **zero verdicts for the whole file** — not
a verdict, not a counter. So **`REFERENCE_REJECTED: 0` is not evidence of anything**, and `0 WALL`
means "no wall among the 4,039 files the engine parsed whole." That bucket holds **109 `#TDS`,
33 `#/`, 8 `#>{`** files — precisely the islands whose span rules are weakest.

### 3.2 The comparison is one-directional, and the fix is four lines

`ParserEquivalence.compare:107-143` iterates **legend-lite's** sites and looks each up in the
reference map. The opposite direction has no code path. Drain the map after the loop; every
leftover key is a `LITE_MISSED` verdict. Measured, that converts 4,071 silent skips into named
rows:

| hidden | count |
|---|---:|
| `SectionIndex` — one per accepted Pure-only file, never compared | **4,039** |
| `Measure` — the site kind `ParserEquivalence` omits | **32** |

Re-run excluding `SectionIndex` and adding `measureSites`: **19,305 = 19,305**, zero files where
reference exceeds sites. `Kind.REFERENCE_REJECTED` is also **misnamed** — it fires when the
reference produced nothing for an FQN *we* produced, which is the opposite of its name.

### 3.3 `MAX_LENIENT_ACCEPTS` bounds the bridge, not the parser

```
vanilla-rejected pure-only files    : 1830
  legend-lite parseStrict ACCEPTS   :  742   (40.5%)
  SPI bridge accepts                :  170
```

`LegendLiteSectionParser.parseSection:109-170` is a **site scanner**: it collects
`topLevelIndexes` for five kinds plus measures, parses those, and **silently ignores every other
token in the section**. `topLevelIndexes` (`ElementParser.java:200-224`) only counts a keyword at
stream start, after `}`, or after `;`.

Attributed, of the 170: **~20 genuinely benign** (extension-less classpath, engine bugs), **~30
real parser leniency** (`allVersionsInRange`, `Primitive`, `enforcementLevel`), **~120 the bridge
skipping tokens it does not recognise**.

Commit `56d5449d` is the failure mode in miniature: 182 → 170 was earned by adding a *token-scan
guard*, not by fixing a parser defect — and that guard is unsound, using the same predecessor
rule, so it misses a `native` that follows a non-`;`/`}` token (measured: 3 of 378 files).

**Do not lower `MAX_LENIENT_ACCEPTS` further.** Add `MAX_PARSER_LENIENT_ACCEPTS` measured through
`parseStrict` — baseline **742**. Make the bridge total; then the two converge and both guards
become deletable.

### 3.4 `SectionParseSentinelTest` has no oracle; 43% of its failures are legal

```
in scope 1114   lite failures 257
  reference ACCEPTS (real drop-in defect) : 146   (57%)
  reference also rejects (legal refusal)  : 111   (43%)
```

`MIN_FILES_PARSED` ratchets that mixed signal, so it cannot distinguish a fixed gap from an
upstream file that got less legal. Of the 116 closed-world-wall failures, **only 48 are
demonstrable defects today**; 68 sit on files the reference also rejects for want of grammar jars
— unadjudicated, not innocent.

Highest proven blast radius in the whole list: **`MappingGrammarParser.java:505`**
(`AggregationAware ~mainMapping kind 'Pure' is not supported`) — 20 files, **20/20**
reference-accepted, one `if`.

### 3.5 `RejectionParityTest`'s 43 pins are 40% mispaired

`RejectionParityTest.java:66` compares our line to the literal scraped from the upstream Java
test, and the extractor pairs runs **by adjacency** (`:126-131`). **17 of 43 pins carry a line
number that cannot exist in the snippet they are attached to** — e.g. pin `6:1` on a 1-line
snippet, and one pin whose "input" is literally the *expected error message* of the previous
test.

Re-run against the engine's actual `getSourceInformation()`:

| | |
|---|---:|
| line agrees with the engine's real position | **40 / 43** |
| genuine line divergences | 3 (all DSL-island; on 2 of them the *engine's* position is out of range for its own input) |

**So "20/43 error-line agreement" was never a parser accuracy figure.** On the column axis, of
the 40 line-agreeing pins: **28 are exactly `engine − 1`** — the `TokenStreamCursor.java:295`
base offset, one character — and **11 are genuinely different tokens**, always *later* than the
engine, because we fail where the construct becomes unparseable while ANTLR fails at the first
token that cannot begin a valid alternative. That second class is a byte-identity blocker
independent of the base fix, and it is arguably better diagnostics.

Three renderers of `[line:col]` coexist and disagree: `TokenStreamCursor.java:295` (**0-based**,
O(offset) rescan — the error path), `LegendCompileException.java:65-66` (1-based, rescan),
`TokenStream.columnOf:143` (1-based, binary search over the cached line index). **The error path
is the only one still 0-based and the only one still doing the rescan the line index was built to
remove.**

---

## §4 — What is right, and worth protecting

Stated because it changes what the remediation should be.

- **Absolute spans across sub-parses.** `TokenStream.slice:245-265` preserves absolute char
  offsets and **shares** the parent's `lineStarts:263`, so all ~17 sub-parse sites produce
  file-absolute spans. This is the most load-bearing correctness property in the pipeline and it
  holds.
- **~30 desugar span rules verified against the live engine** — `.all()` → `getAll` spanning
  DOT..close-paren; `!=` → `not(equal(…))` both spanning the operator; the n-ary collection span
  switching to the *claiming* operator when a tighter op takes the run's last operand
  (`2 + 2 * 4` → `plus`'s collection spans `* 4`); `let`'s top-node override; `^X(…)` span-less
  throughout. These are subtle and correct.
- **The `%`-literal's three position-dependent value conventions**, all reproduced: MONTH keeps
  the `%`, DAY drops it, graph-fetch arguments always keep it, path-literal arguments always drop
  it.
- **Error construction is genuinely de-duplicated.** All 138 throw sites route through
  `TokenStreamCursor.error` → `throwAt` — the only `new ParseException` in `core/src/main`. Two
  documented bypasses, both needing a non-cursor position. **This is the model the other seven
  duplication sets should follow.**
- **Nothing hangs.** 5 MB identifiers, 5 MB strings, 1M-segment FQNs, 100k classes: all
  milliseconds. Adversarial input produces positioned `ParseException`s, not hangs.

---

## §5 — Corrections to published claims

| doc | claim | measured |
|---|---|---|
| `PARSER_DROP_IN.md` §0.1 | legend-lite 25 B/char, **52×** less garbage | **38.9 B/char, 32.9×** — engine baseline reproduces to 0.4%, so this is a legend-lite regression since 2026-08-04. Fixed in `1d38da0a`. |
| `PARSER_DROP_IN_PLAN.md` §2.1 | the parser "depends on nothing but the JDK"; `FromProtocol` is not part of the drop-in | true of `lexer` only; `ElementParser` calls `FromProtocol` at 9 sites |
| `PARSER_DROP_IN_STATUS.md` §3.1 | native functions **and Measure** are "permanently out of comparable scope" | native yes; **Measure no** — the reference emits 32 from Pure-only sources |
| `PARSER_DROP_IN_STATUS.md` §4.1c | `parseStrict` is the drop-in surface | nothing on the drop-in path calls it. `at()` is. `parseStrict` has **one** caller (`RejectionParityTest:99`) and **zero** unit tests |
| `SpecParser.java:540-547` | boolean RHS parsing "matches engine's grammar" | it does not (§1.1) |
| `TokenStreamCursor.unquoteAndUnescape:450` | "THE quoted-name decoder" | the weaker of two (§2.5) |
| `TokenStreamCursor.isFqnSegmentToken:339` | `foo::'bar'::baz` "is not legal Pure in any position" | false — `packagePath: identifier (…)*` and `identifier` includes `STRING`. The code path works; only the doc is wrong. But the predicate is used at **24 sites** as a general "is this a name" test, where it wrongly excludes quoted names |
| `TokenType.java:171` | identifiers are `[A-Za-z_$][A-Za-z0-9_$]*` | wrong in both directions — `$` is not a legal first char, digits are |
| `MappingGrammarParser.java:432-440` | the engine's XStore rule has "no EOF anchor" | `XStoreAssociationMappingParserGrammar.g4:12-14` **has** `EOF` |
| this audit's own brief | ANTLR's ATN "does not fail the same way" on deep nesting | **my error.** ANTLR generates recursive descent; only prediction is ATN-driven. Parity on parens, we go deeper on collections |

`legendStrict` (`ElementParser.java:187`) gates exactly **two** sites — `:448` and `:1056`, both
throwing the same message — in 9,356 lines of parser. It is one predicate discovered by one test,
and its own allowlist entry in `CodeShapeGuardrailTest.java:67` names its provenance. Four known
dialect deltas sit **outside** the flag (`Primitive … extends`, `Class X projects Y`,
`~enforcementLevel`, and `native function` — refused only in the *bridge*).

---

## §6 — Where the identifier rules stand

The engine has **no single identifier rule**: `CoreParserGrammar.g4` leaves `identifier` abstract
and each DSL overrides it (M3 7 alternatives, Domain 25, Mapping 21, Relational 30 + `STRING`,
Runtime 9, Connection 3, **Persistence 110**, and 14 DB-connection grammars with exactly 1).
Section-scoped *lexers* mean `mapping` is simply `VALID_STRING` inside `###Pure`.

legend-lite has one global lexer, one global `KEYWORDS` table, and one global
`IDENTIFIER_TOKENS` (57 types) — **simultaneously wider than Domain's 25 and narrower than
Persistence's 110**. Inside that, six distinct "what is a legal name here" rules coexist
(`Lexer.isIdentStart/isIdentPart`, `IDENTIFIER_TOKENS`, `isFqnSegmentToken`, the path-literal
`\w+` at `SpecParser:2527`, and the digit-leading re-glue `intLeadsIdentifier:377-409`).

This is `GRAMMAR_COMPATIBILITY_2026_08.md` §1.3's "one global keyword table" prerequisite, seen
from the parser side. It is the reason the nominally-cheap sections are not cheap.

---

## §7 — Recommended sequence

Ordered so each step is falsifiable and none manufactures a false green.

**Phase A — make the instrument honest. Nothing else is measurable until this lands.**

1. **Drain the reference map; add `LITE_MISSED`.** Four lines. Converts 4,071 silent skips into
   named rows (§3.2).
2. **Add `measureSites` to `ParserEquivalence`.** One line; makes the two front doors agree.
3. **Emit a verdict — not silence — when the reference throws.** The 1,810-file bucket is 25% of
   the corpus and holds the weakest island constructs (§3.1).
4. **Fix the `RejectionParityTest` extractor's adjacency pairing**, or compare against
   `getSourceInformation()` rather than the scraped literal. 17 of 43 pins are mispaired (§3.5).
5. **Add `MAX_PARSER_LENIENT_ACCEPTS = 742`** beside the bridge ratchet (§3.3).
6. **Give `SectionParseSentinelTest` a reference oracle** (§3.4).

**Phase B — the one-character and one-`if` fixes, now visible.**

7. `TokenStreamCursor.java:295` → 1-based column, and use the line index instead of the rescan.
   **28 of 40 pins** (§3.5).
8. `MappingGrammarParser.java:505` — 20 files, 20/20 reference-accepted (§3.4).
9. Delete the 67 unused imports across the three dirty parser files (§5.3); delete
   `ProtocolEmitter:1356-1373` and one of the two `spanOf` implementations (§5.6).

**Phase C — the correctness work, in dependency order.**

10. **One literal scanner.** Merge `scanPathArgs` / `scanGraphArgs` into the token-level readers,
    or make both delegate to them. Fixes §2.1 outright and is a precondition for trusting any
    island span.
11. **`SpecParser.unescapeString`'s switch inside `unquoteAndUnescape`'s framing** — one decoder,
    the stronger table (§2.5). Then delete the four stragglers.
12. **Decide §1.2 explicitly, in writing, before touching §1.1.** Whether to reproduce the
    engine's arithmetic mis-association is a product decision, not a bug fix, and §1.1's fix
    changes the same code.
13. **Then §1.1 and §1.3 together** — move the reconciliation out of `ProtocolEmitter` and into
    the parse, so `com.legend.model` receives the same tree the wire describes. Expect DIFF to
    rise before it falls, and **restore `composition.pure` with its PCT headers stripped as a
    pinned test in the same commit** — otherwise the gate cannot see the fix either (§2.2).
14. Ordering/optionality R1–R8 (§ Tier 3) — mechanical, and each is a rejection-parity pin.
15. Assertion-position relation island span (§2.2), with a test, since none exists.

**Phase D — robustness.**

16. Route `EnumDefinition`, `FromProtocol`, `ImportScope` validation through `ParseException`
    with positions, or move it downstream of parse entirely (§4.1).
17. Bound the `INVALID`-run scan in `Lexer.java:464` (§4.2).
18. Reject integer literals over a digit bound before `BigInteger` (§4.4).
19. Add a depth guard (§4.5) — parity today, but 98–192 under a small stack.

**Do not** attempt 13 before 1–4. **Do** land 1–9 first; they are cheap, and seven of the nine
are verified.

---

## §8 — Reconciliation with `PARSER_DROP_IN_PLAN.md` §4.2

`GRAMMAR_COMPATIBILITY_2026_08.md` §10 reconciles the *section* worklist. This audit touches
three further items:

| plan item | status |
|---|---|
| §4.2 **#2** — build a line index | **Done, but not adopted where it matters.** `TokenStream` has it; `TokenStreamCursor.java:295`, the error path, still does the O(offset) rescan the index was built to remove. |
| §4.2 **#3** — column-base disagreement | **Confirmed at head, and now quantified.** 28 of 40 line-agreeing pins are exactly `engine − 1`; 11 are a genuinely different token and will not move (§3.5). |
| §4.2 **#4** — emit protocol from the 88 sites | **The count is right; the topology is not.** `at()` is already the protocol path, but it has no `src/main` driver and 9 of its sites detour through `FromProtocol` (§5.1, §5.3). |
| §7 risk — *"emitter accumulates upstream quirks; if the count grows unbounded, reconsider"* | **The trigger has fired, in a form the row did not anticipate.** `rotateFlatBoolean` and `naryArithmetic` are not quirks *recorded* in the emitter — they are a *semantic disagreement* patched at emission, leaving the model layer with a different tree (§1.1). |

---

## §9 — Honest gaps

- **68 of the 116 closed-world sentinel failures are unadjudicated.** The serviceStore,
  persistence, external-format and generation grammar jars are not in `~/.m2`, so there is no
  oracle. Not decidable on this machine.
- **§2.2's Relational/Mapping ordering counterexamples (R9–R14, T1–T2) are code-derived, not
  executed** — the `pureOnly` gate and the bridge's scope exclude those sections. The `.g4`
  citations and the engine's own pinned tests (`TestMappingGrammarParser.java:562-565`,
  `TestRelationalGrammarRoundtrip.java:365-375`) are solid; the exact accept/reject boundary is
  not verified by execution.
- **`MappingGrammarParser` and `RelationalGrammarParser` — 2,427 LOC — have no oracle at all.**
  103 unit tests, every one a hand-written expectation authored alongside the parser. Excluded
  from byte parity, the SPI seam, and rejection parity. Their only harness contact is
  `SectionParseSentinelTest`, which by its own javadoc *"compares nothing."* **This is the single
  largest unmeasured surface in the parser.**
- **Which allocation site caused the 25 → 38.9 B/char regression.** Attribution to
  `tokenPositions` / `SourceInfo` is reasoning from the diff surface, not a profiler run.
  Retained heap was not re-measured at all.
- **Whether the engine's §1.2 mis-association is intentional.** `isStrictlyLowerPrecedence` reads
  like an intended fix; the double-promotion looks like an oversight. No engine test pins
  `1 < 2 + 3 * 4`. The issue tracker was not searched.
- **Whether `TokenStream.text:85` / `startLine:189`'s broken invariants are reachable from
  unmutated input.** They appeared only under fuzz (29 and 3). The invariant is broken either
  way; no minimal non-mutated reproducer was constructed.
- **Reachability of the 12 clean-corpus crashes through the HTTP surface.**
  `LegendHttpServer.java:382` catches `Throwable`, so it renders *something*; no request was
  driven to see what.
- **The engine's multi-line `#/…/#` end-column formula.** Two data points, no closed form.
  legend-lite walls loudly there (`ProtocolEmitter:1580`), so this is a gap to fill rather than a
  bug to fix.
- **Section-scoped identifier narrowing.** The 45 `getParserGrammarIdentifierInclusionTestCode`
  suites were not enumerated. Persistence admits `TRUE`/`FALSE` as identifiers where no other
  section does; a single global `isFqnSegmentToken` cannot express that.
