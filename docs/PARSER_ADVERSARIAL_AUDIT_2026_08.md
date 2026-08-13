# Adversarial Parser Audit — 2026-08-12

> **BURN-DOWN STATUS (same day, evening):** every oracle-verified
> divergence in this document is FIXED — the 279-case generative corpus
> runs 279/279 agree, 0 divergent buckets, and the hand probes all
> match. The corpus is now a permanent gate
> (`AdversarialParityTest`, gate 8) with per-family zero ratchets.
> Fix inventory: B1 (wire corruption + crash classes: infix-flag
> emitter rule + flag-preserving `withParameters` across ~20 walkers,
> TailEmitter sealed switch, BigDecimal decimal wire, quote-aware
> `splitFqn`/`unquoteSegments`, lexer EOF clamp, CMD-colspec spans,
> escape-aware islands, verbatim `processTextBlock` port + JDK
> `unescapeJava`, positioned errors for every raw-JDK-exception site),
> B2 (envelope: engine-true raw line-anchored sectionizing with hard
> boundaries in every scan loop, unterminated-comment refusal,
> once-only duplicate-field policy via `TokenStreamCursor.once` wired
> through 37 key loops + Profile, booleans out of IDENTIFIER_TOKENS,
> milestoning args relaxed, `.all`-with-parens lookahead, strict gates
> for `<>`/`$x[i]`/`comparator`/`[1+2]`/overflow-ints/Z-dates/
> projections/empty bodies/multi-stmt semicolons/single-line `'''`,
> deferred date component validation, `1e3d` lexing, float dialect
> split: CFloat on the ENGINE wire, legend-pure precision promotion on
> PLATFORM/LITE where PCT is the reference). Remaining findings tracked
> in the batch ledger (structural single-owner work, instrument
> hardening) — see git history from 47ba9965.

Deep adversarial audit of the legend-lite front end (lexer + parser +
section grammars + SPI), conducted as a skeptical legend-engine
maintainer evaluating a drop-in replacement claim. Method: full read of
all 39 files (~22.8k LOC incl. lexer), six parallel per-area deep
audits, smell census, and ~30 hand-crafted probes **adjudicated live
against the pinned engine 4.138.2 oracle** (the same jars the parity
harness uses).

Companion docs: DEEP_AUDIT_HANDOFF.md (charter), LENIENCY_CATALOG.md,
GATES.md. This audit does not re-derive their numbers; it tests what
they structurally cannot see.

> **Sibling audit, same day:** `PARSER_DEEP_AUDIT_2026_08.md` (commit
> 47ba9965) ran independently against the same head with a
> complementary scope split — it goes deeper on the EMITTER
> (INFIX_FAMILIES `^new` truncation, invalid-JSON decimals, the
> TailEmitter sealed-type hole), instrument integrity (the
> `classify()` VERSION-SKEW pardon, ratchet slack, dead GATES.md
> tables), and the engine-side Java-8 build objection; this audit goes
> deeper on the section grammars' policy holes (duplicate fields,
> NPE/NFE error shapes), the lexer-fusion `~filter` colspec crash
> family, and generative differential fuzzing. Overlapping findings
> (mid-line `###`, unterminated `/*`, escape-blind islands,
> projections, trailing-backslash crash, section-routing multi-owner,
> SPI gaps) were reached independently — treat those as
> double-confirmed. Both audits' claims that I re-executed held
> (1a `^new` truncation: confirmed; decimal invalid JSON: confirmed in
> bare/arg/collection positions — the doc's `let` example hits a
> loud span wall first). The two action lists should be merged and
> ratcheted together.

## Executive summary

The corpus scoreboard is real and honest: 5,920/5,920 oracle-accepted
documents byte-identical, SHA-pinned corpus, self-testing comparator,
shrink-only allowlists. **And yet ~30 adversarial probes written in one
afternoon produced ~16 oracle-verified divergences** — because the
corpus is 100% harvested from the engine/pure repos and structurally
cannot contain a form those repos never spell. The parser is
excellent where the corpus looks and thin exactly where it doesn't:
duplicate fields, malformed input, error *shape*, constructs no fixture
uses (`~filter` as a column name, enum value named `all`, non-literal
milestoning args).

**Verdict: not yet a drop-in.** Accept-side parity on known-good
sources: proven. Rejection-side parity, error contract, robustness
against adversarial input, and the extension-host story: not yet at the
bar. Every blocker below is fixable without architectural change.

## Oracle-verified divergences (found this audit, all absent from the corpus)

| # | Input | Engine 4.138.2 | legend-lite | Class |
|---|---|---|---|---|
| 1 | `->rename(~filter, ~x)` (column literally named `filter`) | ACCEPTS | `UnsupportedOperationException: ProtocolEmitter needs a source position for colSpec filter` | crash on legal input |
| 2 | `select(~groupBy)` | ACCEPTS | same internal crash | crash on legal input |
| 3 | `graphFetch(#{A{name('it\'s')}}#)` (escaped quote) | ACCEPTS | refuses "trailing backslash" | refuse-divergence (IslandScan escape-blind `indexOf`) |
| 4 | `A.all(now())` (non-literal milestoning arg) | ACCEPTS | refuses "expected milestoning expression" | over-restriction |
| 5 | `a::E.all` (enum value named `all`) | ACCEPTS | refuses "expected '(' after '.all'" | over-commitment on lookahead |
| 6 | graph-fetch alias `'O\'Brien': name` / comment in tree | ACCEPTS | refuses (deliberate "wire shape unprobed" wall) | designed gap, still a divergence |
| 7 | mid-line `###Mapping` (not line-anchored) | REFUSES (`SECTION_START` is `'\n###'`) | ACCEPTS as section | accept-divergence / invention |
| 8 | unterminated `/* comment` | REFUSES | ACCEPTS (silent comment-to-EOF) | accept-divergence |
| 9 | DataSpace `title:` twice | REFUSES "Field 'title' should be specified only once" | ACCEPTS (silent last-wins) | accept-divergence |
| 10 | JsonModelConnection `class:` twice | REFUSES | ACCEPTS | accept-divergence |
| 11 | `Class X projects Y {...}` (projection body) | REFUSES | ACCEPTS with silently-empty body | accept + silent data loss |
| 12 | `$x[0]` bracket index | REFUSES "Bracket operation is not supported" (curated msg) | parser accepts (ungated platform dialect), emitter dies with internal `UnsupportedOperationException` | dialect leak + crash |
| 13 | `'abc\` at EOF | clean positioned refusal | raw `StringIndexOutOfBoundsException` (lexer emits end offset past source) | error-shape break |
| 14 | DataSpace `Email {}` (missing address) | "Field 'address' is required" | raw `NullPointerException`, no position | error-shape break |
| 15 | `port: xyz;` | positioned "Unexpected token 'xyz'" | raw `NumberFormatException` | error-shape break |
| 16 | `$x->at(0); $x` two-statement body | REFUSES | ACCEPTS | accept-divergence (unadjudicated cause) |

Shared limitation (not a divergence): 5,000 nested parens →
`StackOverflowError` on BOTH sides. Neither has a depth guard; lite's
escapes as an `Error` outside the `ParseException` contract.

## Generative differential corpus (proof the technique scales)

A 279-case systematic corpus (8 families: every lexer keyword as
class-name/property/variable/param/enum-value/colspec, duplicated
fields per section, escape variants, comments in odd positions,
whitespace extremes, numeric/date edge literals, truncation ladder,
stray-token injection) ran through both parsers in seconds.
**260/279 agree**; the 19 divergences add four NEW families beyond the
hand probes:

| Family | Engine | lite | Notes |
|---|---|---|---|
| `true`/`false` as property/param/enum-value name | REFUSES | ACCEPTS | ×6 — `IDENTIFIER_TOKENS` admits TRUE/FALSE where the engine's identifier rule doesn't |
| `'aAb'` / `'a\101b'` escapes | ACCEPTS | REFUSES | the "stay loud until a corpus file demands them" call is wrong — the engine accepts these |
| `%2024-02-30` | ACCEPTS (defers validation to compiler) | parse-time refusal | confirms the date/time validation asymmetry |
| `%…T10:00:00Z` | REFUSES | ACCEPTS | the audit-M5 "fix" that added `Z` support made lite accept what 4.138.2 rejects |
| `99…9` (>long) int literal | REFUSES | ACCEPTS (BigInteger fallback) | acceptance inversion |
| `[1..9999999999]` multiplicity | positioned refusal | raw `NumberFormatException` | error-shape |
| DataSpace `description:` twice | REFUSES | ACCEPTS | third duplicate-field hole |
| `~filter`…`~src` colspec (all six) | ACCEPTS | internal `UnsupportedOperationException` | full CMD-token family confirmed |

Good news in the same data: keyword-as-identifier coverage is otherwise
complete (all 33 keywords agree in all six positions except the
booleans and CMD-colspecs), and truncations, stray tokens, comments,
and whitespace extremes agree 100% — the core grammar's rejection
behavior is much stronger than the section grammars'.

This corpus took minutes to build and found four families the
8,116-source harvested corpus structurally cannot contain. A
generative `AdversarialParityTest` (grammar-driven mutation against
the live oracle, divergences ratcheted like the leniency catalog)
should become gate 8's sibling — it is the single highest-yield
testing investment available to this project.

## What is genuinely strong

- **Performance**: ~9 MB/s source→PMCD-JSON single-threaded (measured,
  2,000-class doc, 30 ms); prior measurements: 54× engine speed, ~33×
  less allocation. The `int[]`-parallel TokenStream, lazy line index,
  shared-slice design are exactly right.
- **Concurrency**: 64 concurrent `parseDocument` calls byte-identical
  (measured); no mutable statics anywhere in the parser (grep-verified);
  benign-racy lazy caches documented.
- **Evidence culture**: probe citations, audit IDs, engine file:line
  references (~135 engine-source citations vs ~139 probe citations),
  named quirk flags (`EngineQuirks.RELATIONAL_ARITH_MISASSOCIATION` with
  the engine's own line numbers), self-testing comparator, ratchets.
  No TODO/FIXME debt (1 TODO mirroring an engine TODO).
- **Dialect architecture**: the three-level `Dialect` enum with no
  neutral default, caller-whitelisted platform surface, gated
  extensions — this is a better-articulated dialect story than the
  engine's own.
- **Operator layer**: `OperatorParts` is a faithful, contained,
  documented port of `DomainParseTreeWalker`'s accumulator walk,
  including its mis-association bug. Backtracking hygiene in SpecParser
  is clean (save/restore verified leak-free).
- **Island mechanism**: raw-substring reconstruction with coordinate
  padding is sounder than a token-join would be; comments/whitespace
  survive; `TokenStream.slice` keeps absolute coordinates.

## Structure & ownership (the "does everything have 1 owner?" question)

Mostly disciplined at the architecture level, leaky at the idiom level.

**Ownership violations (multiple owners for one concept):**
1. **THREE section-routing authorities**: `SectionGrammarRegistry`
   (self-described "THE authority"), `PmcdParser`'s hand-mirrored
   `TAIL_SECTIONS`/`TAIL_GRAMMARS`/`IMPORT_AWARE`/merged-activator
   tables, and `Lexer.LEXABLE_SECTIONS`. They already disagree: the
   PMCD path accepts `MemSqlFunction` inside `###Snowflake`; the
   registry path refuses it. A guardrail test pins lexer↔registry but
   not PmcdParser↔registry.
2. **Two decoration grammars** (`TokenStreamCursor.parseDecorations` vs
   `ElementParser.parseStereotypes/parseTaggedValue`) that have already
   drifted (comma optional vs required) — the handoff doc's own Leg 4
   flagged this and it's still live.
3. **Three escape tables**: the cursor's canonical `unescapeBody` plus
   two hand-rolled n/t/r-only decoders in ElementParser that silently
   corrupt `\b`/`\f`/octal on the ModelStore/CSV paths.
4. **Two type-argument grammars** (`TokenStreamCursor.parseType` vs
   `SpecParser.parseTypeArguments`) and **two type-variable-value
   parsers** with different acceptance (`@V('ok')` refuses in one,
   parses in the other — the probe-verified engine shape contradicts
   the annotation copy).
5. **The balanced-scan idiom has ~40+ hand copies** (12 in
   MappingProtocolParser with three different depth policies, 9 in
   ElementParser with three different bracket sets — two of which
   mis-slice brace-bearing expressions, ~10 in section grammars, 10
   island scanners of which Runtime's is the one that forgot depth
   tracking and is therefore buggy). The one divergent copy being the
   one latent bug is the whole case for a single owner.
6. **Section-grammar micro-patterns**: ~60 hand-rolled key-dispatch
   loops; `stringValue` ×7; boolean parse ×6; pkg/name split ×12;
   offset-cursor classes ×6. A ~150-line shared toolkit absorbs an
   estimated 800–1,000 lines and, more importantly, makes once-only
   and error-position POLICIES single-owner (today duplicate-field
   checking exists in ~4 of 23 grammars; the rest silently last-win).
7. **`TokenStreamCursor` is a god-interface**: 1,175 lines mixing cursor
   primitives, the type grammar, doc-string decoding, join-type
   validation, mapping-body disambiguation, decorations. The cursor
   extraction genuinely killed a duplication class, but it's now the
   junk drawer — domain rules parked on the lexical layer.

**Special-case hacks to pass tests?** Mostly no — with three honest
caveats. The bulk of "weird" code is deliberate engine emulation, each
site citing an engine source line or a named probe; that is the
byte-parity mandate working as designed. The caveats: (a)
`PmcdParser.ruleGroup` classifies elements by
`e.json().startsWith("{\"_type\":\"service\"")` — sniffing serialized
JSON prefixes to reorder elements is a genuine hack that breaks
silently if the emitter's field order changes; (b) probe-pinned span
arithmetic (`+3`/`-3`/`+2`/`-1`, the `1,1,2,8` section-span constants,
sign-encoded token indexes in AggregationAware) is scattered as bare
literals with no version tag and no named constants — when the 4.138.2
pin moves, finding them is a scavenger hunt; (c) the strict-flip left
sediment: ~230 lines of dead prior-design code (Persistence's
abandoned raw-capture path under a stale javadoc, dead
`measureSites`/`skipBalancedContent`/`unquoteString`/island-offset
constructor, dead `ACTIVATOR_SECTIONS`, dead mutable mapping-scope
fields whitelisted PAST the project's own guardrail).

**Crazy compensating ifs?** Localized, not pervasive. The hot spots:
`parseMember`'s 160-line closed string-dispatch, `parsePathLiteral`'s
~90-line quote-blind char surgery (the file's least engine-faithful
mechanism and the project's own "no shadow parser" tenet violated in
char-scan form), `sectionSpan`'s magic-constant branches, the tri-modal
lambda `;` policy, `parseServiceStoreClassMapping` (145 lines, 6 levels,
3 exit paths). Out-param style (`int[] endOut`, `boolean[]`,
sign-encoded ints, parallel lists) is the recurring control-flow smell —
three different sub-parser hand-off conventions where one record would do.

## Per-area findings (compressed; each verified by reading, probes noted)

### Lexer (717 LOC)
- Global context-free keyword table means `pattern`/`owners`/`type`…
  lex as keywords everywhere; handled via `IDENTIFIER_TOKENS` (verified
  complete against the keyword map) — works, but every new keyword must
  be added in two places or identifiers break.
- Tilde-command fusion (`~filter` → FILTER_CMD) is the root cause of
  divergences 1–2: the CMD-token colspec path was patched in shallowly
  (no span, no typed-colspec support, no annotations; `~src:Integer[1]`
  silently builds a wrong AST — traced).
- `'...\` at EOF emits end offset past source → any `text()` call
  throws raw SIOOBE (divergence 13).
- Multi-word merges (`PRIMARY KEY`, `is not null`) and `{target}`
  fusion are context-free and merge across newlines; low practical risk,
  same design smell.
- `### Pure` (space) yields an empty-name section header, refused later
  with the odd message `'' is not a known section parser`.
- Mid-line `###` recognized as a header (divergence 7): normal-mode
  scan lacks the line-anchor the opaque-skip path has and the engine's
  `'\n###'` rule mandates.
- Unterminated `/*` silently comments to EOF (divergence 8).

### TokenStreamCursor
- God-interface (see above); layering: parser interface constructs
  protocol nodes directly.
- `expect(type, customMessage)` skips `rejectInvalid()` — INVALID gets
  the custom message instead of the unlexable-input trap.
- Lenient comma handling in `parseTypeVariableValues` (`Numeric(10 2)`,
  `(,,10,)` accepted).
- `parseDecorations` accepts comma-less stereotype lists the engine
  refuses (engine: "Valid alternatives: [',', '>']").

### PmcdParser
- Int-literal site-kind dispatch (0–12) + `int[] end` out-params.
- `ruleGroup` JSON-prefix sniffing (see hacks).
- Diagram sections parsed TWICE (once for imports, once for elements).
- Parallel `heads` list for Runtime reordering — index-coupled lists.
- Dead `ACTIVATOR_SECTIONS`.
- `skipTo` linear from 0 per section (fine at current sizes).

### ElementParser (2,701 LOC, 105 commits in 6 weeks)
Majors: lexable-but-skipped section fallthrough silently vanishes
content (no final else); default-value `catch (ParseException) → null`
silently accepts what the engine rejects; `associationElement` accepts
`projects` on the strict model path returning a mis-typed element while
the protocol path refuses; relation-island content after the last `;`
silently dropped; `dataElement` parses every Data element twice with
desync risk; test-arg scanners balance PAREN/BRACKET but not BRACE
(brace-bearing lambda args mis-slice); `topLevelIndexes` vs
`skipTopLevelNonElement` disagree on stray-`)` handling; dead
fields/methods incl. mutable state whitelisted past the guardrail;
`text()`-at-EOF raw IOOBE sites; enum-name leakage in generic error
messages ("expected BRACE_CLOSE but found VALID_STRING") vs the
engine's ANTLR wording — the curated refusals are engine-verbatim, the
generic template is not.

### SpecParser (3,236 LOC)
Engine-quirk port verified correct and contained (A1); but: fused-CMD
colspec second-class path (B1/B2); bracket-index and `comparator`
parsed UNGATED on the strict surface (divergence 12); milestoning args
over-restricted (divergence 4); `.all` lookahead over-commits
(divergence 5); date-vs-time validation asymmetric (`%2024-02-30`
refuses at parse; times defer to compiler — engine defers both);
navigation-path char scanner is quote-blind (`#/T/p('a,b')#`
mis-splits — traced); island collector can't report an unterminated
island; `OperatorParts` divide-arm unreachable (dead or a silently
dropped engine path — needs engine citation); raw
`Long.parseLong`/`Integer.parseInt` overflow sites throw unlocated NFE.

### MappingProtocolParser (3,493 LOC) + IslandScan + QuotedSpecParser
IslandScan escape-blind quote scan = divergence 3 (verified live);
legacy `assert:` decodes without STRING check (`assert: foo;` yields
`expected == "o"` — silent model corruption; `assert: 5;` raw SIOOBE);
`+3`/`-3` span offset applied and reversed at distance; sign-encoded
token indexes + parallel lists in AggregationAware (dead `AggView`
record documents the abandoned fix); mutable `aggLambdaShift` invisible
parameter channel; 12 balanced-slice copies with divergent depth
policies; `readIsland` depth model omits `ISLAND_ARROW_EXIT` (disagrees
with the lexer's own depth rules); O(N) padded re-lex per island →
quadratic for island-dense files (the cached line index exists and is
not used here); `QuotedSpecParser` catches ALL RuntimeException as
"not a tree" (masks parser bugs as silent downgrades — the regression
mode its own comment records).

### DatabaseProtocolParser + 23 section grammars + SPI
Runtime's island scanner lacks depth tracking (nested-island
truncation — the one divergent copy of ten); NPE/NFE-as-error-handling
at ~8 sites (divergences 14–15); duplicate-field policy in ~4/23
grammars (divergences 9–10); `parseDecorations` result discarded in
FileGeneration (silent stereotype loss); MongoDB JSON-schema walker
drops `items`/`minimum`/`enum` silently and can't parse FLOAT or null;
~185 lines dead code in Persistence; error-message register is a
4-style patchwork (engine-verbatim / homegrown lowercase / keyed /
raw JDK).

**SPI**: right seam, not yet a real extension point — `ElementSink`
has no import channel (imports parsed and DROPPED in the SPI feed:
any import-aware overlay section silently mis-resolves); two span
conventions behind one interface; elements cross as untyped JSON
strings (an engine `PureGrammarParserExtension` cannot plug in);
sub-element seams (auth strategies, datasource specs, embedded data,
mapping-element parsers — the engine's 8 hook families plus
`IRelationalGrammarParserExtension`) are closed switches; ServiceLoader
overlays silently shadow built-ins including `###Pure` with zero
diagnostics; registry built in static init (untestable, one bad jar
poisons the JVM).

### Test/evidence assessment
Accept parity: genuinely proven (see summary). Gaps: rejection parity
rests on 43 positional pins; **message-text parity is asserted nowhere**
(the test enforcing it was deleted in the 2026-08-12 consolidation —
the doc claim survives, the gate does not); 381 sectioned error pins
counted-and-skipped; zero fuzzing; zero concurrency tests; harness
runs only on this machine, outside CI, and `allgates.sh` always exits 0;
the invention census (320 unadjudicated accepts + 25 VERSION-SKEW + 54
other-refusals) is open by charter.

## The maintainer verdict (drop-in: yes or no?)

**No — with a credible path to yes, and one deployment shape acceptable
today.**

What I'd say in the PR: *"This is the most rigorously evidenced parser
replacement I've reviewed — the accept-side byte-parity harness is
better than our own test discipline, the performance win is real
(~50×), and the dialect quarantine is cleaner than what we have. But a
parser's contract is fifty percent rejection, and on that half you have
43 pins where we have a grammar; my first afternoon of probing found
sixteen divergences your corpus can't see, four of them crashes with
raw JDK exceptions or internal invariant errors on inputs my parser
handles. You've also replaced a plugin HOST with a product: none of my
ecosystem's third-party grammar jars — 25+ in-tree modules, plus
closed-source overlays — can run on your SPI, your `ElementSink` drops
imports, and every future grammar change I merge becomes an unversioned
hand-port on your side, checked only against a corpus pinned to my
last release. And 22k lines of hand-rolled descent — however well
commented — moves grammar review from 'read the .g4 diff' to 'trust
your harness'."*

**The acceptable shape today**: lite registered as an engine
`SectionParser` extension for `###Pure` (the seam-proof configuration —
5,911/5,920 byte-identical through the engine's own SPI). The engine
stays host; extensions keep working; lite eats the hot section. That is
shippable now. Full-parser replacement is not.

**Conditions for full drop-in** (ranked):
1. Fix the crash class: thread spans through the fused-CMD colspec path
   (div. 1–2, 12), fix the lexer's EOF-backslash offset (13), convert
   every raw NPE/NFE/IOOBE/CCE site to positioned `ParseException`
   (14–15 + inventoried sites). An internal invariant error on legal
   input is disqualifying for a front end.
2. Fix escape handling in `IslandScan`/`parsePathLiteral` (3) and the
   `assert:` corruption; kill the quote-blind char scanners.
3. Close the acceptance envelope: line-anchor `###`, refuse unterminated
   block comments, gate bracket-index/`comparator` behind the dialect,
   un-restrict milestoning args and `.all`-as-enum-value, make
   duplicate-field once-only a shared policy across all 23 grammars
   (7–12, 4–5, 9–10).
4. Restore message-text parity as a gate (it existed; it was deleted);
   grow the sectioned negative corpus; add grammar-driven differential
   fuzzing against the live oracle — the one technique that would have
   found all sixteen divergences before I did.
5. Unify the three routing authorities; extract the balanced-scan /
   island-scan / key-dispatch toolkit so once-only and error-position
   policies have one owner; delete the ~230 lines of dead code; name
   and version-tag every probe-pinned span constant.
6. SPI: import channel, span contract, typed element carrier, and an
   adapter for engine `PureGrammarParserExtension` jars — or explicitly
   rescope the claim from "drop-in parser" to "drop-in ###Pure section
   parser behind the engine's SPI".

## Probe artifacts

Scratchpad probes (LexProbe, ParseProbe, EdgeProbe, OracleProbe,
DualProbe1-4, PerfProbe, ThreadProbe) adjudicate every divergence above
against `PureGrammarParser.newInstance().parseModel` from the pinned
4.138.2 jars. Worth promoting into a permanent `AdversarialParityTest`
in parser-equivalence: each divergence row is a ready-made fixture.
