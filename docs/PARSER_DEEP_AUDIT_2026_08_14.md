# Response — 2026-08-14 evening (same day, commits 341ac14c..99af226e)

> Written by the session the audit addresses. Every status below names its
> commit; the audit's text is left untouched below.

**The three questions, answered:**

1. *On what run did "census exclusions ZERO" go green?* On manual runs only —
   the audit is right, `SurfaceCensusTest` had never been enrolled. It is in
   gate 8 (both the `-Dtest` filter and the ran-check) as of `341ac14c`. The
   claims it backed were true when I ran it by hand; nothing re-ran it. That
   is an instrument failure and the finding stands.
2. *What bounds the leniency catalog (1,470) and bothReject (2,132)?* Today:
   nothing. Accepted as the top instrument gap alongside the circular
   VERSION-SKEW arm; both are tracked and not yet fixed.
3. *What was the basis for "done" on GQL?* Probe rows that were fixed points
   of both bugs — the battery could not fail on escapes or malformed numbers
   because no pinned row contained either. The basis was insufficient, both
   CRITICALs were real, and both are fixed in `341ac14c` (escapes ride RAW
   per the walker; numbers are .g4-enforced and normalized — the five
   invalid-JSON emitters now refuse).

**Finding status (fix commits on main):**

| finding | status |
|---|---|
| 1a GQL escapes decoded | FIXED `341ac14c` |
| 1a-bis GQL invalid-JSON numbers | FIXED `341ac14c` |
| 1a-ter block strings / empty +rules / reserved names / spread directives | FIXED `341ac14c` (SDL kinds beyond `type` still refuse loudly — open surface, safe direction) |
| 1b `^new` duplicate keys collapse | OPEN — Map→List refactor tracked |
| 1c non-BMP column drift | FIXED `341ac14c` (`columnOf` counts code points) |
| 1c-bis ES cardinality / fields-vs-properties / location validation / dup keys / semicolons / PSK | OPEN |
| 1d dup Table columns; timezone digits | OPEN |
| Persistence wrong-kind keys (sibling handoff protocol-check) | FIXED `99af226e` (`PERMITTED_FIELDS`) |
| 2a census not gated | FIXED `341ac14c` |
| 2b CI runs zero parser gates | OPEN — plan: checkout-free subset (batteries + fixture ratchet) in CI; the corpus sweep genuinely needs local checkouts |
| 2c circular VERSION-SKEW arm | OPEN |
| 2d catalog/bothReject unbounded | OPEN |
| 2e slack ratchets | FIXED `341ac14c` (417/337/2093; duplicate floor deleted) |
| 2f model-refuse-allowlist unverified | OPEN — needs a compile-stage probe per row |
| 3 architecture (offset mechanisms, island seam, PAuthSpecValue consumers, Trino downcast, cosmetic split) | OPEN — the island registry and SpanOrigin consolidation are the right next structural legs |

**Adopted from the audit's method:** mutation-differential fuzzing and the
handoff's protocol-check are the two instruments this estate lacked; both are
queued as standing gates.

---

# Deep parser audit #2 — legend-lite @ `bfef311b` (2026-08-14)

Audit of the claim that the parser is "100% bulletproof and done". Measured at
`0d66a629`, then **every finding re-verified at `bfef311b`** after that commit
landed mid-audit. `legend-engine` @ `4.137.0-36-g943d38b3dc2`, oracle jars @
`4.138.2`.

**Re-verification result (`bfef311b`, core rebuilt, all probes re-run):**

| finding | status at `bfef311b` |
|---|---|
| `AWSSecretsManagerSecret` refused | **FIXED** — now byte-identical. `bfef311b` landed it, and the "oracle-unreachable" ledger row that had excused it was indeed wrong |
| GQL: 5 inputs emit invalid JSON | **OPEN** — `1.`, `-`, `1e`, `007`, `1.2.3` all still unparseable |
| GQL: escape decoding | **OPEN** — 4 byte-diffs |
| GQL: block strings, empty `+`-rules, reserved-word names, number normalization | **OPEN** |
| `^new` duplicate keys collapse | **OPEN** — 3 byte-diffs |
| non-BMP column drift | **OPEN** — 5 diffs |
| ES duplicate auth key accepted | **OPEN** — engine: *"Field 'userName' should be specified only once"* |
| ES missing semicolon accepted | **OPEN** |
| ES `PSK` refused | **OPEN** — engine accepts |
| duplicate column in `Table` accepted | **OPEN** |
| date timezone digit count over-strict | **OPEN** — 2 cases |

One claim from a sub-agent I could not reproduce and have dropped:
`GCPWIFWithAWSIdP` on an ES connection — both parsers refuse my test shape, so I
have no evidence either way and it is not asserted below.

Method: full gate chain re-run; every prior finding re-tested; a **mutation-based
differential fuzzer** built and run against the real `PureGrammarParser`; new
surfaces attacked by hand. Docs banned as evidence. Everything below that says
CONFIRMED was executed, not reasoned about.

---

## Verdict

**"100% byte parity" is true and is a real, tight, zero-headroom result.
"100% bulletproof and done" is false, and the gap between those two sentences is
the whole audit.**

The byte claim covers the **6,489 of 8,891 corpus sources (73%) that the pinned
oracle accepts**. For that population: 6,489/6,489 byte-identical, 0 diffs,
0 refusals, both C12 ledgers empty, `MIN_DOCS_MATCHED` at its measured value with
zero slack. That survives scrutiny.

The other 27% has exactly one assertion on it: that it is non-empty.

And in ~40 minutes of hand-written and generated input I found **13 live
divergences from the real engine**, including a systematic wire bug in code that
landed yesterday. None is in any allowlist. None is reachable by the corpus.

---

## What genuinely improved — say it plainly

The 08-12 audit's findings were acted on, properly, not cosmetically:

| then | now |
|---|---|
| `^new` key-expression truncation (17/17 wrong) | **fixed** — `ProtocolEmitter.java:2610` now reads `chain.infix()` |
| quoted path segments with `::` mangled (10 cases) | **fixed** — `Protocol.unquoteSegments` is quote-aware and unescapes |
| 5 of 7 persistence entry variants emitted invalid JSON | **fixed** — exhaustive `switch` + throwing default, `TailEmitter.java:1518` |
| decimal `007d` emitted unparseable JSON; float→decimal retyping | **fixed** |
| section-boundary leniency family (mid-line `###`, block comments…) | **fixed** — all now both-refuse |
| `MAX_DROP_IN_DEFECTS = 184` | **0** |
| `MAX_UNJUSTIFIED_LENIENCY = 52` | **0** |
| `MIN_PINS = 43` negative pins | **424**, `REJECT_MISS = 0` |
| asymmetric rejects 276 | **18** |
| ~8,400 lines of assertion-free `Z*` scratch | ~2,300 |
| `OwnCorpusConformanceTest` / `OwnDialectCensusTest` in no gate | **both gated** |
| a rename could silently shrink gate 8 | **rename-goes-red check**, `allgates.sh:216-226` |

I re-ran all 25 of my prior confirmed defects. **Every one is fixed.** That is
real work and the burn-down was not cosmetic.

---

## 1. What I broke — 13 confirmed divergences

### 1a. CRITICAL — GraphQL string escapes are decoded; the engine keeps them raw

The engine's GraphQL walker strips the surrounding quotes and **does nothing
else** — `GraphQLGrammarParser.java:481-484`:

```java
String in = valueContext.stringValue().STRING().getText().trim();
value.value = in.substring(1, in.length() - 1);
```

and its composer's inverse just re-wraps (`GraphQLGrammarComposer.java:300-303`).
Escapes round-trip verbatim. Lite **decodes** them (`GqlParser.java:359-365`).

| input | engine wire value | lite wire value |
|---|---|---|
| `#GQL{query q { a(s: "x\"y") }}#` | `x\"y` | `x"y` |
| `#GQL{query q { a(s: "x\\y") }}#` | `x\\y` | `x\y` |
| `#GQL{query q { a(s: "x\ny") }}#` | `x\ny` (literal backslash-n) | newline |

Every GraphQL string containing a backslash produces a different wire. This
landed **yesterday** (`fa64b1f6`), with 10 pinned test rows and the claim
*"ALL 8 accepted probe forms BYTE-MATCHED the live oracle on first run."*

### 1a-bis. CRITICAL — five GQL inputs make lite emit syntactically invalid JSON

`Gql.IntValue`/`FloatValue` hold **raw source text** (`Gql.java:136-142`) and
`GqlEmitter.java:190-193` appends it unescaped. The engine parses through
`Long.parseLong` / `Double.parseDouble`, so it refuses anything malformed. Lite's
scanner (`GqlParser.java:287-309`) accepts shapes the `.g4` `INT`/`FLOAT` rules
reject and then emits them raw. I fed lite's output to Jackson:

| input | engine | lite | lite's output |
|---|---|---|---|
| `h(a: 1.)` | REFUSE | ACCEPT | `"value":1.` — **invalid JSON** |
| `h(a: -)` | REFUSE | ACCEPT | `"value":-` — **invalid JSON** |
| `h(a: 1e)` | REFUSE | ACCEPT | `"value":1e` — **invalid JSON** |
| `h(a: 007)` | REFUSE | ACCEPT | `"value":007` — **invalid JSON** |
| `h(a: 1.2.3)` | REFUSE | ACCEPT | `"value":1.2.3` — **invalid JSON** |
| `h(a: 99999999999999999999)` | REFUSE | ACCEPT | valid JSON, engine overflows |

`M.readTree(liteOutput)` throws on all five. This is worse than a byte diff — the
document cannot be deserialized by any consumer.

Non-canonical but legal numbers diverge too: `10.00` → engine `10.0`, lite
`10.00`; `1.5e3` → engine `1500.0`, lite `1.5e3`; `-0` → engine `0`. **The
engine's own corpus contains this shape** —
`testQueryToGraphFetch.pure:407` spells `minBalance: 10.00, maxBalance: 1000.50`.

### 1a-ter. HIGH — more confirmed GQL divergences

All executed, engine-verified:

- **Block strings silently mis-parse into three values.** `stringLiteral()` has no
  `"""` case, so `["""abc"""]` is read as `StringValue("")`, `EnumValue("abc")`,
  `StringValue("")` — and **accepted**. Engine refuses. Valid JSON, wrong rows.
- **`A` refused** (`GQL: unbuilt string escape '\u'`) — engine accepts;
  the `.g4` has `fragment ESC: '\\' (["\\/bfnrt] | UNICODE)`.
- **Four `+` rules accepted as empty** — `query { }`, `h()`, `query Q()`,
  `type Foo {}`. All four: engine refuses, lite accepts.
- **Four reserved words accepted as names** — `null`, `on`, `implements`,
  `directive` are implicit lexer literals in the `.g4` and are excluded from its
  `name` rule. Engine refuses all four; lite accepts all four.
- **SDL surface gap** — the engine walks six type kinds plus schema/directive;
  `GqlParser.definition()` handles only `query/mutation/subscription/fragment/type`.
  `enum`, `interface`, `input`, `scalar`, `union`, `schema`, `directive`,
  bare `type Foo`, `implements`, descriptions and field-level arguments are all
  refused. Loud, so safe-direction — but it directly contradicts `03312179`'s
  *"census exclusions ZERO — no named grammar gaps left."*

**~50 hand-written GraphQL cases → 20+ divergences, in code that landed
yesterday claiming byte-exactness.**

### 1b. HIGH — `^new` duplicate keys silently collapse

`SpecParser.java:1571` models key expressions as
`Map<String, KeyExpression> properties = new LinkedHashMap<>()` and `put`s into
it (`:1655`, `:1689`). The engine keeps a **list**. So a repeated key silently
loses all but the last:

```
^a::A(x = 1, x = 2)        engine: multiplicity 2, two keyExpressions
                           lite:   multiplicity 1, one keyExpression
^a::A(x = 1, y = 2, x = 3) engine: 3      lite: 2
^a::A(x = 1, x = 1)        engine: 2      lite: 1
```

This is a noun-test failure causing live wire data loss: the shape of the
collection is decided by a `Map` key, and the parser cannot represent what the
grammar allows. Found by the fuzzer (as a `multiplicity 5 vs 4` diff), reduced to
the three-line repro above.

### 1c. HIGH — non-BMP characters shift every column on the line

`TokenStream.columnOf` (`TokenStream.java:215-216`) computes
`offset - lineStarts()[...] + 1` on **UTF-16 code-unit** offsets. ANTLR counts
**code points**. So one astral character (emoji, CJK Ext-B, mathematical
alphanumerics) shifts every subsequent span on that line by +1; two shift by +2.

```
Class a::'b😀c' { }               engine endColumn 18, lite 19
Class a::'b😀😀c' { }             engine endColumn 19, lite 21
function a::f(): String[1] { 'a😀b' }   every later span on the line drifts
```

Verified BMP-safe (`é`, `中` are fine) and line-local (the next line recovers).
Corpus exposure: **zero `.pure` files in either checkout contain a non-BMP
character**, which is exactly why 6,489/6,489 is green. Emoji in a `doc.doc` tag
or a service description is entirely ordinary in a real user model.

### 1c-bis. HIGH — Elasticsearch store and auth: cardinality and validation gaps

The ES store/auth legs landed in the same window. Against
`ElasticsearchStoreParseTreeWalker` and `AuthenticationParseTreeWalker`:

- **`indices` and `properties` are required-and-single in the engine**
  (`validateAndExtractRequiredField` refuses both absence and repetition); lite
  makes them optional and, on repetition, **merges into one list**
  (`ElasticsearchSectionGrammar.java:51-68`, `:145-160`). So
  `properties: [p]; properties: [q];` emits both p and q where the engine refuses.
- **`fields` and `properties` are not interchangeable.** The `.g4` gives scalars
  only `fieldsDefinition` and complex types only `propertiesDefinition`, and makes
  the complex body mandatory. `:101-114` makes the body optional for every type
  and accepts either key for any type — producing a wire that cannot exist
  (`"properties"` inside a `keywordProperty`).
- **Empty arrays and trailing commas accepted** where the `.g4` allows neither
  (the `if (!c.match(COMMA)) break;` idiom at `:62`, `:118`, `:154`).
- **ApiKey `location` uppercased but never validated**
  (`ConnectionSectionGrammar.java:1716-1720`). Engine does
  `Location.valueOf(...)` against `{HEADER, COOKIE}`. `location: 'query'` →
  lite emits `"location":"QUERY"`, a value the protocol enum cannot deserialize.
- **Auth-island bodies silently overwrite duplicate keys** (`:1518`, `:1712`,
  `:1741`) — last value wins, first dropped. Every engine counterpart refuses
  repetition. Note `parseElasticsearchBody` at `:1553` *does* guard — the
  inconsistency is inside one file.
- **ES connection semicolons optional in lite, mandatory in the `.g4`**
  (`:1608` discards the `match` result).
- **The "general" auth island is an allowlist narrower than the registry** —
  CONFIRMED for `PSK`: the engine accepts
  `authentication: # PSK { psk: 'abc'; }#` on an ES connection and emits
  `"authSpec":{"_type":"PSK","psk":"abc"}`; lite refuses at
  `ConnectionSectionGrammar.java:1763` with *"unsupported auth island kind: PSK"*.
  (`AWSSecretsManagerSecret` was in this class and was fixed by `bfef311b`.)

None of this is corpus-reachable: every item is lite-accepts-engine-refuses, and
a corpus of engine-legal documents structurally cannot contain those shapes.

### 1d. Remaining confirmed divergences

- **Duplicate column in a Table accepted** — `Table T ( c VARCHAR(20)  c VARCHAR(20) )`:
  engine refuses (`Unexpected token 'c'. Valid alternatives: [',', ')']`), lite
  parses. LITE-LENIENT.
- **Date timezone digit count over-strict** — `%2025-01-01T12:34:56+000` and
  `+00`: engine accepts, lite refuses with
  *"timezone offset must be exactly 4 digits (HHMM)"*. LITE-STRICT, 2 cases.
- Plus the fuzzer's population below.

### 1e. The fuzzer — 4,235 mutants of 350 clean corpus documents

I built a mutation-based differential harness (delete/duplicate/truncate/swap/
splice/flip-bracket at text level, so neither parser's lexer biases the
mutations), seeded only with documents both parsers already agree on byte-for-byte.

```
both accept, byte-identical : 2481
both accept, BYTE-DIFF      :   12   <- wrong wire, silently
LITE-LENIENT (we accept)    :   25
LITE-STRICT  (we refuse)    :    2
both reject                 : 1715
   message identical        :   22  ( 1.3%)
   line identical           : 1556  (90.7%)
   column identical         : 1348  (78.6%)
StackOverflowError          :    0   <- previously a crash; now handled
```

**Message parity is 1.3% at scale.** Commit `2bf282a8` claims *"message-TEXT
parity gate restored"* — that gate (`MessageParityTest.java:57-72`) is a
`List.of(...)` of **7 hardcoded strings**. It is a strong comparison over a
denominator of seven.

**Column parity is 78.6%** — i.e. ~1 in 5 error columns is wrong on malformed
input. The "position-exactness lane" ratcheted the *line* floor 135→417 of 423
and never touched the column floor (see §2).

The lenient population includes real validation gaps: duplicate `contentType` in
a Service, missing required `data` in a Persistence test batch, duplicate
primary-key entries, duplicate table entries — all refused by the engine's
walker, all accepted by lite.

---

## 2. What the instruments do not measure

All verified against the current tree, not inferred.

### 2a. The census gate is not a gate

`SurfaceCensusTest` — the sole enforcer of *"census exclusions ZERO — no named
grammar gaps left"* (`03312179`) and of the 537-keyword g4 adjudication
(`0d66a629`) — **appears in no `-Dtest=` list, no `allgates.sh` line, and no CI
file.** I grepped `tools/`, `.github/`, `docs/GATES.md`: zero hits. It has never
been enrolled.

It also would not be a construct-coverage floor if it ran. Its "covered" side is
hardcoded `Set.of(...)` string literals (`SurfaceCensusTest.java:106-118`), not
behavior — delete the parser, keep the string, the census stays green. And it is
satisfiable by refusing more: add a row to `parser-surface-exclusions.tsv`.

### 2b. CI runs zero parser gates

`.github/workflows/gate.yml` is three steps, all `-pl core`:
`clean test`, `install`, `-Dtest=RelationalCorpusRunner`. **`parser-equivalence`
is never built in CI.** Every byte-parity, rejection-parity, message-parity,
sentinel, census and adversarial claim in this window is enforced only when a
human runs a shell script on a machine that happens to have both upstream
checkouts. A PR deleting `CorpusSweepTest.java` is green in CI.

### 2c. The leniency pardon became circular

`CorpusSweepTest.java:417-420`:

```java
if ("Unexpected token".equals(msg.trim())) {
    try {
        Surfaces.engine(text);          // our parser accepts it
        return "VERSION-SKEW-grammar";  // therefore the oracle is wrong
```

**Lite accepting the file is the evidence that the oracle is defective.** Being
more lenient than the reference is the pass condition. 23 rows live today.

The generic ANTLR catch-all *was* genuinely closed (it now requires a row in
`docs/version-skew-claims.tsv`) — but this arm sits above it and is worse.

### 2d. Two large populations with no ceiling at all

From the run I just did:

```
by class: {DIALECT-function-types=278, DIALECT-generics=333,
           DIALECT-milestoning-range=31, DIALECT-native-or-m2=102,
           ENGINE-TEST-SCOPED-section=19, GRAMMAR-REFUSAL-nullmsg=346,
           ORACLE-DEFECT-crash=337, VERSION-SKEW-claimed=1,
           VERSION-SKEW-grammar=23}
```

**1,470 sources where lite accepts what the oracle refuses.** `catalogByClass` is
built at `CorpusSweepTest.java:275` and **printed** at `:673`. It is asserted
against nothing.

`bothReject = 2,132` is counted at `:222` and asserted once, at `:369`:
`assertTrue(fAccepts > 0 && fBoth > 0, "degenerate sweep")` — a liveness check,
not a bound. **The 08-12 structural finding is unchanged: deleting grammar
capability that only affects oracle-rejected sources moves rows into
`bothReject` and every ratchet stays green.**

### 2e. Ratchets that guard nothing

| constant | value | measured | slack |
|---|---:|---:|---:|
| `MIN_LINE_AGREEMENT` (`RejectionParityTest.java:175`) | 40 | 417 | **377** |
| `MIN_COLUMN_EXACT` (`:171`) | 28 | 337 | **309** |
| `MIN_BEHAVIOUR_MATCHED` (`SectionParseSentinelTest.java:314`) | 793 | 2093 | **1300** |

Both `RejectionParityTest` floors were calibrated against a 43-pin population;
the population is now 424 and the constants never moved. The only live line floor
is a hardcoded `assertTrue(lineMatch >= 417)` at `:150`, which sits alongside —
and contradicts — the named constant at `:175`. The sentinel corpus could lose
60% of its files and go green.

`FixtureAdjudicationTest` walks `List.of("core", "engine")` (`:227`) — but
`engine/src/test/java` was deleted on 08-10. **Half its denominator vanished
under fixed ceilings** (`MAX_LENIENCY_KINDS = 21`, `MAX_OVER_STRICTNESS = 6`,
both unmoved since 08-08).

### 2f. A pardon list that grew 14× and never shrank

`docs/model-refuse-allowlist.tsv`: **5 rows → 71 rows**, all in one commit
(`9ad67391`), never reduced. Its header claims *"The ENGINE also refuses these at
ITS compile stage"* — that claim is verified by nothing; `CorpusSweepTest` only
checks membership.

That same commit is the one to audit hardest: it changed the corpus denominator
(8,116 → 8,891 sources, 1,777 rewritten manifest lines) **in the same commit** as
raising `MAX_DROP_IN_DEFECTS` 1 → 17, growing this pardon list 14×, and taking
two "EMPTY" ledgers to 68 and 3 rows. Its own body names the reason: *"C4's
pinned PURE_DECL heuristic never saw connection/auth/DQ sources… we refused,
**ledger silent**."* The ledgers had been reporting zero on a corpus that
structurally could not contain the failing shapes. That is precisely the exposure
the current "EMPTY" claims have.

---

## 3. Architecture — what moved

Verified per finding against current code.

**Fixed:** the `lexable()==true` silent-vanish arm (`ElementParser.java:411-424`,
now a positioned throw backed by `LexerRegistryAgreementTest`); the file-size cap
is now actually **enforced** (`CodeShapeGuardrailTest.java:35`).

**Partially fixed:** `PmcdParser` now consults the registry (`:90`, `:351`) and
`TAIL_GRAMMARS`/`ACTIVATOR_SECTIONS` are gone — but `IMPORT_AWARE` (`:58-62`) and
`TAIL_SECTIONS` (`:77-81`) survive, so adding a section is a 4-site edit, down
from 5. Multiplicity parsing narrowed to one shared helper with 11 call sites —
but 5 parse sites remain and **0 of 5 validate `upper >= lower`**; the omission
was converted from an accident into a documented decision.

**Unchanged:** the SPI is still 1 extension point vs the engine's 8, with no
import channel and no re-entrancy context. Section names are still hardcoded in
the lexer. Embedded data is still implemented twice into two protocol families
with two emitters and a live `.trim()`/`.strip()` divergence. `ElementParser` is
still 22 public entry points; `MappingProtocolParser` still hosts four unrelated
protocol families.

**Worse:**
- **Offset mechanisms went 4 → 7**, with composition sites now disagreeing four
  ways (`RuntimeSectionGrammar.java:183` composes column only on line 1;
  `PersistenceSectionGrammar.java:387` unconditionally; `ConnectionSectionGrammar.java:447`
  not at all; `SpecParser.java:3246` a fourth rule). `GqlParser` added the 7th.
- **Parser/emitter drift widened ~4×** — 181 distinct literal `_type` strings
  across `protocol/` against 119 parser switches, still with no shared table.
- **Islands got no seam.** Sections have a registry; GQL was added by widening
  the bare `switch (dslType)` at `SpecParser.java:3031-3060`. The island roster
  now lives in three non-agreeing places (`Lexer.java:559` for TDS, that switch,
  and `SurfaceCensusTest.java:110`) — and they already disagree: `""` (bare
  graph-fetch) is missing from the census, `TDS` is missing from the switch.
- **The new ES auth family opted out of the seam that makes the old one safe.**
  `PAuthSpecValue` (4 arms) has **zero** `FromProtocol`/model consumers — I
  checked all four: `PApiKeyAuth`, `PEpkAuth`, `PKerberosAuth`, `PMongoAuth` are
  referenced nowhere under `model/` or `compiler/`. Its sibling `PAuthStrategy`
  is consumed by an exhaustive sealed switch, so adding an arm there is a compile
  error; the new family has no such pressure.
- **`PTrinoKerberosAuth` is lossily downcast** — `FromProtocol.java:770-772` maps
  it to `AuthenticationSpec.DelegatedKerberos(k.serverPrincipal())`, dropping
  `kerberosRemoteServiceName` and `kerberosUseCanonicalHostname`, and making it
  indistinguishable from `PDelegatedKerberos` (`:751`). Byte parity stays green
  because the divergence is below the wire.
- **11 of 20 registered section grammars terminate in a model no-op** — e.g.
  `ElasticsearchSectionGrammar.java:34-41` parses a full index/property tree and
  returns `GenericSectionElementDefinition(..., Map.of(), null)`. "The section
  landed" means byte-parity only.
- **The now-enforced line cap is being satisfied by cosmetic splits.**
  `ServiceStubDataParser.java:13-16` says so outright — *"extracted from
  MappingProtocolParser (file-size guardrail)"* — takes the concrete god class
  rather than the cursor interface, and calls back into methods that had to be
  demoted from `private` to permit it. That is a cycle across a file boundary:
  the split reduced a line count and increased API surface.

---

## 4. Ranked actions

**Fix now — confirmed silent-wrong, all small:**

0. **`GqlParser` number scanning — five inputs emit invalid JSON.** Parse to
   `long`/`double` at parse time (refusing loudly on failure) and emit the
   normalized value; enforce the `.g4` `INT`/`FLOAT` shape in the scanner. This
   is the only finding here that produces output no consumer can deserialize.
1. `GqlParser.java:355-365` — **stop decoding GraphQL escapes.** The engine
   strips quotes only. Every backslash-bearing GraphQL string is currently wrong
   on the wire. Also: lex `"""` explicitly and refuse (today it silently
   mis-parses into three values), accept `\u`, require ≥1 in the four `+` rules,
   restrict `name` to the `.g4` set, and drop directives on `fragmentSpread` /
   `fragmentDefinition` to match the engine's walker.
2. `SpecParser.java:1571` — `^new` key expressions must be a **list, not a
   `Map<String, KeyExpression>`**. Duplicate keys are silently collapsing.
3. `TokenStream.java:215` — count **code points**:
   `source.codePointCount(lineStart, offset) + 1`. One line; closes the whole
   non-BMP span-drift class.
4. Date-literal timezone: accept the digit counts the engine accepts.
5. Refuse the duplicate-column-in-Table form.
6. Add an exhaustive `FromProtocol` switch over `PAuthSpecValue` even if every
   arm throws — the point is the compile error on the next arm. Fix the
   `PTrinoKerberosAuth` field loss.

**Instrument — these are what let 1–6 exist:**

7. **Put `SurfaceCensusTest` in gate 8.** It is currently cited as the proof of
   "census exclusions ZERO" and has never run.
8. **Make CI run gate 8.** Nothing about the parser is enforced by CI today.
9. Delete the circular arm at `CorpusSweepTest.java:417-420`. Our own leniency
   cannot be evidence of an oracle defect.
10. Put a down-only ceiling on the leniency catalog (1,470) and on `bothReject`
    (2,132). Right now the two largest populations in the harness are unbounded.
11. Ratchet `MIN_LINE_AGREEMENT` 40→417, `MIN_COLUMN_EXACT` 28→337,
    `MIN_BEHAVIOUR_MATCHED` 793→2093, and delete the duplicate line floor.
12. Fix `FixtureAdjudicationTest`'s dead `"engine"` module walk and re-measure
    both ceilings against the halved denominator.
13. **Adopt mutation-differential fuzzing as a standing gate.** My harness found
    12 byte-diffs and 27 verdict divergences in 4,235 mutants of documents that
    already agreed. It is ~200 lines and it is the only instrument here that can
    see the 27% of the corpus the oracle rejects. Gate on message text and the
    four-field span while you are there — message parity is 1.3%.
14. Build a **construct-coverage floor** independent of the oracle's verdict.
    Still absent; the census does not supply one.

**Structural, unchanged from the last audit:** one `SectionCatalog`; an island
registry mirroring the section registry; collapse the 7 offset mechanisms behind
one `SpanOrigin`; one embedded-data parser and one protocol family; grow the SPI
to the 8 points with a re-entrancy context.

---

## The three questions to put to the claimants

1. `SurfaceCensusTest` is in no gate list and no CI file. On what run did
   "census exclusions ZERO" and "537 keywords adjudicated" go green, and what
   re-runs it?
2. What bounds the 1,470-row leniency catalog and the 2,132-row `bothReject`
   population, and what stops a capability deletion from moving rows into them?
3. GQL landed yesterday with "ALL 8 probe forms byte-matched." I hand-wrote ~50
   and 20+ diverged, five of them producing JSON that Jackson cannot parse. The
   10 pinned rows use `5`, `"luke"`, `1.5`, `true`, `null`, `JEDI`, `[1,2]`,
   `{a:1}` — every one a fixed point of both bugs. What is the basis for "done"
   on a grammar whose test battery cannot fail?

---

## Correction to one sub-agent claim

`AdversarialParityTest` **is** in gate 8 — `tools/allgates.sh:206` lists it, and
I confirmed it in the run above. A sub-agent reported it as ungated because
`docs/GATES.md:192` still shows the pre-08-13 eleven-class filter. The doc is
stale; the script is authority. Noting it because it is itself an instance of the
documentation problem: `GATES.md`'s ratchet table is now wrong in 6 of 12 rows
(`MIN_PINS 43`→424, `MAX_PARSER_LENIENT_ACCEPTS 187`→181,
`MAX_DROP_IN_DEFECTS 184`→0, `MAX_LENIENT 69`→17,
`MAX_UNJUSTIFIED_LENIENCY 52`→0, `MIN_FILES_PARSED` no longer exists), and it
mentions neither `SurfaceCensusTest` nor `MessageParityTest`.
