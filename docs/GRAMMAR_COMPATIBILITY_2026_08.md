# Grammar compatibility — every `###` section (2026-08-05)

**The question:** what is required for legend-lite's parser to be 100% compatible with all
legend-engine grammar constructs?

**Measured against** legend-engine `0881165f9be` (origin/master), legend-lite `152783df`
(origin/main). Nine parallel analyses: one per grammar family, plus one on the harness
method. Every number below was measured, not estimated; where an agent could not run
something, it is flagged in §9.

> **Companion:** [`PARSER_DROP_IN_STATUS.md`](PARSER_DROP_IN_STATUS.md) is the `###Pure`
> state of play and remains the architecture reference. **Its §3.1 snapshot is stale** —
> see §7.

---

## §0 — Where we actually are

### The gate, measured today

```
corpus sources        : 7217
verdicts              : 19269
  MATCH               : 19269
  DIFF                :     0
  WALL                :     0
  PARSE_FAIL          :     0
  REFERENCE_REJECTED  :     0
```

`walls-detail.txt` and `parsefails-detail.txt` are both **0 bytes**.

### But the suite is RED

| test | status |
|---|---|
| `CorpusEquivalenceTest` | PASS — 19,269/19,269 |
| `RejectionParityTest` | PASS — 43/43 pins |
| `SectionParseSentinelTest` | **PASS by one** — 857 vs `MIN_FILES_PARSED = 856` |
| `SpiSeamProofTest` | **FAIL** — `leniency census grew: 212 > 182` |

The SPI seam itself is clean (4,011 files byte-identical, 0 DIFF, 0 SPI-REJECTS). What
broke is the **leniency ratchet**: 212 files vanilla engine rejects that we accept, against
a bound set at `d0b4c3a2f68`. **Attribute the 30 new accepts and re-ratchet before landing
anything else** — work on a red gate forfeits the signal.

### Section coverage

legend-lite lexes **5** of ~25 engine sections (`Lexer.java:274-276`). Everything else is
raw-skipped **and returns success** (`:287-296`) — a `###Service`-only file "parses" by
deletion, yielding zero elements and no diagnostic.

Byte-parity exists for **`###Pure` only** — and §2 shows that claim has a hole in it.

Measured parse rates for the four other lexed sections:

| section | sections in scope | parse | note |
|---|---:|---:|---|
| `###Relational` | 276 | **99.3%** | only 2 live failures; the problems are silent (§3) |
| `###Mapping` | 923 sources | **75.8%** | inline tier — the roundtrip suites — fails at **40%** |
| `###Runtime` | 87 | **75.9%** | |
| `###Connection` | 56 | **30.4%** | not a subset of the engine grammar — a different one |

---

## §1 — Three prerequisites that gate everything

None is grammar work. All three were found independently by multiple agents.

### 1.1 The `pureOnly` gate throws away whole files

`ParserEquivalence.java:71-80` returns **zero verdicts** for any source containing a
non-`Pure` section header — *"not a wall, just out of scope."* The same regex-and-skip is
duplicated in `SpiSeamProofTest.java:51-61` and `RejectionParityTest.java:137-147`.

The cost is not what it looks like. Those files' **`###Pure` elements are discarded too**:

```
Pure-kind elements in Pure-only files (harness scope today) : 15,275
Pure-kind elements in MIXED files (not compared today)      :  2,452
```

**Deleting one filter is worth ~2,452 elements — a ~16% coverage increase with zero new
emit rules.** If a DIFF appears when it lands, it was always there and the gate was hiding
it. That is itself the finding.

### 1.2 The corpus predicate excludes the tiers where the sections live

- `Corpus.java:48` filters `endsWith(".pure")`. That hides **22 `.legend` files** (where
  every real `#SQL{…}#` island lives) and **45 `.txt` files** under the persistence test
  runner — complete multi-section models, five times the volume of the `.pure` persistence
  corpus and far richer.
- `InlineSnippets.PURE_DECL` (`:31-32`) admits only snippets containing a line starting
  `Class|Enum|Association|Profile|Measure|function|import`. It **structurally cannot admit
  a `###Mapping`-only snippet.**

In the inline corpus, **`###Mapping` (724) outnumbers `###Pure` (592)**, with `###Relational`
450, `###Service` 247, `###Connection` 214. All excluded by that one regex.

### 1.3 The lexer has one global keyword table

The engine disambiguates section-scoped keywords — `data`, `class`, `tests`, `keys`,
`parameters`, `title` — **by grammar slot**. legend-lite has a single `KEYWORDS` map.

> *"That is a structural change, not an additive one, and it is the real gating cost — not
> the grammar size."*

This is why the nominally-cheap sections (Text at 5 rules, FunctionJar at 70 lines) are not
cheap yet. It is a shared prerequisite, paid once.

### 1.4 A fourth, narrower one: the classpath

`parser-equivalence/pom.xml:38-53` declares three grammar modules. For any section outside
them the **reference parser itself throws** `'X' is not a known section parser` — so byte
parity is *undefined*, not failing.

Good news, and the single most important structural fact in this document:

> **legend-engine is invoked LIVE, in-process. There are no captured goldens.**
> `ParserEquivalence.java:62` builds a real `PureGrammarParser.newInstance()`, which
> ServiceLoads every extension on the classpath.

`###Mapping`, `###Connection` and `###Runtime` are legacy built-ins *inside a jar already
declared*; `###Relational` and `###Diagram` come from the two extension jars already there.
**Extending byte-parity to those five needs zero new dependencies and zero capture step.**

---

## §2 — The `###Pure` 100% has a hole

There is no `###SQL`, `###TDS` or `###GraphQL` section — those strings are `group()` labels.
They are **embedded-Pure-parser islands inside `###Pure` value specifications**, dispatched
by one production (`DomainParseTreeWalker.java:1475-1500`) with five tags:

| tag | meaning | legend-lite |
|---|---|---|
| `""` | graph-fetch tree | ✅ byte-identical |
| `">"` | relation store accessor | ✅ byte-identical |
| `"SQL"` | SQL expression | ❌ hard reject (`SpecParser.java:2826`) |
| `"TDS"` | TDS literal | ⚠️ **silently wrong bytes** |
| `"GQL"` | GraphQL document | ❌ hard reject |

`#>{db.tbl}#` and `#SQL{…}#` are **the same grammar production**. The `###Pure` claim is
100% of the two tags whose jars are on the harness classpath.

### The silent one

`Lexer.java:426-429` short-circuits on the literal prefix `TDS` *before* the ISLAND_OPEN
branch — inherited from a predecessor lexer targeting legend-pure's brace-less `#TDS\n…\n#`.
It also swallows the engine's `#TDS{…}#` and emits:

```java
AppliedFunction("tds", [CString("TDS"), CString(raw)])   // ours
ClassInstance("TDS", TDSContainer{tdsString})            // engine
```

Different `_type`; and `raw` still carries the `#TDS{` opener and `}#` closer the engine
strips. Worse, the scan terminates on the first `#`, so any `#` inside a body truncates the
token and the remainder re-lexes as Pure.

> *"It parses 'successfully' and produces wrong bytes — it would never show as a wall, only
> as a DIFF, and only if compared."*

**Why it stays green:** every real island lives in `.legend` files the corpus does not read
(§1.2), **and** the reference parser would throw first for lack of the extension jar (§1.4).
Two independent blindfolds. Lesson 1 of `PARSER_DROP_IN_STATUS.md` — a green that could have
come from an absence of evidence.

### Cheapest high-value fixes in this document

| fix | cost |
|---|---|
| `#TDS{…}#` — guard the prefix on the next char (`{` → island), add the island case | **hours** |
| `.trim()` the island tag (`SpecParser.java:2781`) — 2 real `#GQL {` spellings exist | **minutes** |
| `#SQL{…}#` — payload is verbatim text, one string field | **hours** |
| Make them provable: `.legend` in the corpus, extension jars in the pom | ~1 day |

---

## §3 — Silent drops (the highest-value category)

Every item below was verified by **executing** the parser and reading the record. These are
wrong answers, not gaps — they produce a clean parse and incorrect content.

### 3.1 An entire element vanishes

**`Operation merge_…` produces nothing.** `MappingGrammarParser.java:322-336` balanced-brace-
skips the body and returns **without adding anything to the accumulator**. The model loads
with the class unmapped and **no diagnostic**. This is exactly what
`audit-21a-parse-leniency.md` was written about.

### 3.2 The rest

| # | drop | site | scale |
|---|---|---|---|
| 1 | `IdentifiedConnection.id` | `ElementParser.java:2065` | **100 sites** — every runtime |
| 2 | `SingleConnectionRuntime` body swallowed whole; mappings *and* connection vanish, file parses clean | `ElementParser.java:2012-2021` | 11 |
| 3 | Embedded connections detached from store + id | `ElementParser.java:2076` | 40 |
| 4 | The whole `specification:`/`auth:` payload — parsed, validated, recorded, **never read** (verified accessor-by-accessor: zero non-parser call sites) | `ConnectionDefinition` | all |
| 5 | Database stereotypes / taggedValues | `RelationalGrammarParser.java:76-77` | 22 engine test sites |
| 6 | `!=` and `<>` collapse to one enum — engine emits `notEqual` vs `notEqualAnsi`, **different SQL** | `RelationalGrammarParser.java:820-831` | 13 |
| 7 | Per-hop `(INNER)` parsed, threaded through two rebuild passes, then dropped — `JoinChainEmission.java` has **zero** `joinType` references; every hop emits LEFT OUTER | `:435,443` | 5 in-section / 74 file-wide |
| 8 | Unknown milestoning kind silently discarded (no `else`); engine throws | `RelationalGrammarParser.java:184-198` | latent |
| 9 | Repeated milestonings overwrite — engine keeps a `List` | `DatabaseDefinition.java:94` | latent |
| 10 | `JSON` folded onto `SemiStructured` — engine has a distinct wire type | `RelationalDataType.java:140` | 2 |
| 11 | Nested `AssociationMapping` header `*` and `[setId]` | `MappingGrammarParser.java:266-272` | 66 |
| 12 | `+localProp` type + multiplicity on the **Pure and Relation** paths — but **kept** on Relational. An inconsistency, not a design. | `:1490-1493`, `:612-616` | 297 |
| 13 | Embedded property mapping `[id]` — engine assigns it to **two** keys (`.id` and `.target`) | `:1035-1037` | — |
| 14 | `union_` vs `special_union_` conflated | `:322-336` | 304 |
| 15 | AggregationAware `Views:` — the entire `AggregateSpecification` discarded | `:517-524` | 4 |
| 16 | `testSuites:` held as an unparsed `String` | `:99-120` | 58 |
| 17 | Service `owners` and `autoActivateUpdates` | `ElementParser.java:1895-1911` | — |
| 18 | `#TDS{…}#` wire shape (§2) | `Lexer.java:426-429` | latent |

### 3.3 False rejections and leniency divergences

Both break rejection parity, which is a gate.

- **False rejection:** `duplicate connection binding for store 'X'` fires on legal grammar
  (`RuntimeParserGrammar.g4:59` permits multiple ids per store). `ElementParser.java:2078-2081`.
- **We accept what the engine rejects:** `DECIMAL(32)` one-arg, bare `VARCHAR`/`BOOLEAN`/
  `DISTINCT`, `==` as comparison, `->` arrow calls in relational ops, include-after-elements,
  View clauses in any order, anonymous `EnumerationMapping :`, `Otherwise` fallback bodies
  wider than the grammar permits.
- **A capability drift:** `[db] fn(args)` is not parsed in Database context but **is** handled
  by `MappingGrammarParser.java:1152-1156`. One engine rule, two parsers, divergent capability.

---

## §4 — Comments that assert the opposite of engine behaviour

Eight found across this study and the corpus study. They matter because each was used to
*justify* dropping data, and a reader who trusts them will not check.

| # | comment | reality |
|---|---|---|
| 1 | `ElementParser.java:2065` — *"engine doesn't keep this"* | `RuntimeParseTreeWalker.java:176` keeps it; it is a wire field |
| 2 | `ElementParser.java:2012-2014` — *"engine skips the body and returns an empty runtime. We match that here."* | The engine **requires** `mappings` and `connection` and builds a `SingleConnectionEngineRuntime` |
| 3 | `RelationalGrammarParser.java:76-77` — *"parity: engine consumes and drops"* | The engine retains both. **`PARSER_DROP_IN_STATUS.md` §4.1 already recorded this exact belief being refuted by a harness DIFF.** Second occurrence of known-refuted lore. |
| 4 | `MappingGrammarParser.java:430-457` — *"ENGINE PARITY (audit 21a §4b): the engine's XStore rule … no EOF anchor"* | `XStoreAssociationMappingParserGrammar.g4:12-14` **has** `EOF`. The engine rejects; we reproduce *legend-pure's* behaviour. The citation does not hold. |
| 5 | `RelationalGrammarParser.java:261-264` — one-arg `DECIMAL` justification | Contradicted by `TestRelationalGrammarParser.java:185-198` |
| 6 | `EngineStyleH2.java:1277-1283` — uppercase `dateadd` units | Contradicted by `h2Extension1_4_200.pure:96` |
| 7 | `Scalars.java:79-81` — *"IS NOT DISTINCT FROM appears in no golden"* | Appears **113** times |
| 8 | `MappingNormalizer.java:2358-2362` — *"the corpus only ever feeds 'true'/'false'"* | Falsified by the test that fails on it |

**#3 and #4 are the concerning ones**: one is a repeat of a claim already disproven *in this
project's own documentation*, and the other launders itself through an audit reference.

---

## §5 — Per-section state and cost

### Lexed today

| section | parse | byte-parity | headline gap |
|---|---:|---|---|
| `###Pure` | — | **100% of 2/5 island tags** | §2 |
| `###Relational` | 99.3% | none | 6 silent drops; no `sourceInformation` anywhere in `DatabaseDefinition` |
| `###Mapping` | 75.8% | none | no `PMapping`; ~74 engine protocol classes; `Realization.Ref`/`Inline` has **no wire counterpart** |
| `###Runtime` | 75.9% | none | `Map<String,String>` cannot express ordered, id-carrying, embedded connections |
| `###Connection` | 30.4% | none | 1/8 types, 4/16 specs, 3/12 auth; wrong keyword spellings that can never match |

### Not lexed

| family | sections | engine size | corpus (`.pure`) | verdict |
|---|---|---|---:|---|
| metadata | Diagram, Text, DataSpace, GenerationSpec, FileGeneration, DataQuality | 676 g4 lines, 41 protocol classes | 73 files | **Diagram: zero byte-parity value** (§7); Text/GenSpec/FileGen cheap; DataSpace/DataQuality expensive, 0 files unlock |
| data | Data, ExternalFormat | small | 57 + 12 | **Data is the best ratio** — 42 single-section files needing only kinds we already emit; ~350 LOC |
| service | Service, ServiceStore, HostedService, FunctionJar | 1,208 + 1,332 engine lines | 51 + 10 + 0 + 0 | defer; **but ServiceStore's mapping element + connection are live defects today** |
| persistence | Persistence (+ cloud, relational) | 181 rules, 147 protocol classes | 9 | last — but wire the 45 `.txt` files first |
| stores | MongoDB, Elasticsearch, Snowflake, MemSql, BigQuery, Deephaven | 67–407 g4 lines each | 1–4 each | low; Snowflake/MemSql/BigQuery are flat `key: value;` and parse standalone |

### Naming traps

`###Snowflake` (three element kinds), `###BigQuery`, `###MemSql`, `###Deephaven`,
`###Elasticsearch`, `###MongoDB` — **not** the module names. `PersistenceContext` is an
element keyword *inside* `###Persistence`, not a section.

### Calibration

Engine `###Mapping` = 481 lines (g4 + walker) → legend-lite's parser = 1,595 lines,
**≈3.3×** — and that is parse-only, before protocol records (~15 LOC each) or emitter arms
(~25-40 LOC each).

---

## §6 — The plan

### Phase A — make the instrument section-agnostic (no new grammar)

1. **Attribute the 30 new SPI-ACCEPTS; fix or re-ratchet.** The suite is red.
2. Collapse the **four** duplicated five-marker lists (`ParserEquivalence.java:91-106`,
   `LegendLiteSectionParser.java:102-110`, `RejectionParityTest.java:101-113`,
   `ProtocolEmitter.java:70-76`) into one section→(marker, parse-fn, fqn-fn) table.
3. **Delete the `pureOnly` gate** in all three places. Expect MATCH ≈ 19,269 → ~21,700 with
   zero new emit rules.
4. Widen `InlineSnippets.PURE_DECL`; add `.legend` and `.txt` to `Corpus`.
5. **Give `SectionParseSentinelTest` reference adjudication** — it currently scores
   engine-rejected garbage as our failure, and it uses the *lenient* `ElementParser.parse`,
   not the strict drop-in surface.
6. Make an unlexable section a **named wall**, not a silent success (`Lexer.java:287-296`).
7. Add a fifth verdict for closed-world walls (§8), or move them to the emitter.

### Phase B — the islands (§2)

`#TDS{}` wire shape, tag `.trim()`, `#SQL{}`. Hours of work, and it closes a live silent
wrong-bytes defect inside the surface that currently reports 100%.

### Phase C — fix the drops (§3) before any emitter work

> **Starting the emitter before the drops would encode them into the byte comparison as
> walls and hide them again.**

That is the same trap as the corpus study's advisory arms — a measurement that launders a
defect into a known limitation.

### Phase D — byte parity, section by section

**Runtime → Connection → Relational → Mapping.**

Not the plan's `Connection → Diagram → Runtime → Relational → Mapping`. Two measured
corrections: **Diagram drops out** (§7), and **Runtime goes first** — 46 elements, 8
discriminators, 33 fields, the smallest complete section, and it proves the whole non-Pure
loop end to end. Relational before Mapping because Relational **is** ~9 of Mapping's
discriminators and 8,620 of its node instances.

The protocol question is settled by measurement:

```
grep -rniE "sourceinfo|sourceInformation|startLine|SourceSpan" core/src/main/java/com/legend/model/  →  0
```

`com.legend.model` carries **zero source positions**. A model→protocol emitter cannot recover
spans that were never captured — option (b) is impossible, not expensive. New protocol
records, per §2.3's own noun test; move `RelationalOperation`/`RelationalDataType` into
`protocol` rather than mirroring them.

### Phase E — every section lexed

Turn the raw-skip into a typed opaque-section token; register built-ins through the same
`SectionGrammarRegistry` third parties would use. Only then does "every section lexed" mean
anything — today a `###Service` file parses by deletion.

### First three commits

1. **`harness: unify section scope and site discovery; drop the pureOnly gate`** — proves the
   scoping is a filter, not an architecture. +~2,452 elements, zero new emit rules.
2. **`harness: reference-adjudicate the sentinel; split closed-world walls from parse gaps`** —
   turns one brittle 857/856 counter into a per-grammar burn-down list.
3. **`protocol: PRuntime + emitter; compare ###Runtime byte-for-byte`** — proves the `###Pure`
   method transfers to a non-Pure section at the smallest possible size.

---

## §7 — Corrections to existing docs

- **`PARSER_DROP_IN_STATUS.md` §3.1 is stale.** Its "7 walls / 55 PARSE_FAILs" are superseded
  by §4.1 and §4.1b *in the same document* ("ABSOLUTE ZERO"). Measured today: 0 and 0. Mark
  §3.1 superseded — it is a layered log read top-down.
- **`PARSER_DROP_IN_PLAN.md:411`'s `MappingElementContext` risk is dead.** The shim was never
  needed: `LegendLiteSectionParser.java:150-155` emits JSON and hands it to the engine's own
  deserializer; no ANTLR context is ever constructed. `MappingParser`, `ConnectionParser` and
  `RuntimeParser` all return `ImportAwareCodeSection` — the exact type the bridge already
  builds. Only `###Relational` differs (`DefaultCodeSection`), a one-line change. **Docs and
  code disagree; code wins.**
- **`PARSER_DROP_IN.md:713-716`'s "genuinely unexplained" `###Diagram` zero is explained.**
  The corpus files use legend-pure's **M3** diagram DSL (`Diagram fqn(width=, height=) {
  TypeView … }`); the engine grammar demands the Legend one (`Diagram fqn { classView … }`).
  Different languages sharing a section name.

  **This resolves a conflict between two of the nine analyses.** Diagram is the cheapest
  *lexing-coverage* win (104 g4 lines, 49 files unlocked, 44 hidden `###Pure` declarations)
  **and** a byte-parity dead end (zero reference-adjudicable corpus). Both are true; they
  answer different questions. Do it for coverage, never for parity.
- **`FileGenerationParseTreeWalker.java:65-69`'s "generation type not supported" branch is dead
  code** — `substring`/`toLowerCase` cannot throw what it catches. Do **not** replicate a type
  allowlist the engine does not have.

---

## §8 — A taxonomy gap worth fixing

~115 of the sentinel's 257 failures (45%) are **closed-world enumeration walls**, not gaps:
`unsupported top-level keyword` ×38, `unsupported class mapping type` ×22, `unknown database
type (expected one of [DuckDB, SQLite, H2, Postgres, Snowflake, BigQuery])` ×4.

These are legend-lite closing a world the engine leaves open. Architecturally they are exactly
what `WALL` means — but they surface as `PARSE_FAIL` because there is no emitter downstream
to wall in. They are legitimately out of scope for a *pipeline* (there is no Databricks
backend) and legitimately **in** scope for a *parser* drop-in (the engine parses them fine).

Lesson 5 of `PARSER_DROP_IN_STATUS.md` — *the parser must stay total; the emitter owns what
the wire can express* — applied to the section grammars. Either add a fifth verdict, or move
these walls from the parser to the emitter.

The remaining split: ~120 (47%) real grammar gaps — the honest burn-down list — and ~15 (6%)
snippet-extraction artifacts that would vanish under reference adjudication.

---

## §9 — Honest gaps

- **Wire byte shapes are unprobed for every section except `###Pure`.** Nine agents read
  `.g4` files, walkers and protocol classes; none ran `ProbeWireShapes` against a non-Pure
  input. Key ordering, `sourceInformation` spans, null-vs-omitted for optionals, island
  `columnOffset` arithmetic — all inferred from source. On `###Pure` evidence, **that is
  where the time actually goes**: the progression from 1,750 to 19,269 matches was almost
  entirely span-convention discovery, not grammar coverage. Every cost estimate here is
  *grammar* cost.
- **Supply chain, not engineering.** `legend-engine-xt-serviceStore-grammar`, the persistence
  grammar, external-format and generation grammars are **not in `~/.m2`** (98 artifacts
  checked). Until they are on the classpath, `###Service` (51 files, 247 inline),
  `###Persistence`, `###ExternalFormat`, `###GenerationSpecification` and `###FileGeneration`
  have **no reference oracle at all** — no byte-parity claim is possible for them.
- **The 212 SPI-ACCEPTS are un-attributed.** Sampled ones match the documented-benign
  categories; 30 are new since the ratchet was set.
- **A directional hole in the harness.** `ParserEquivalence` iterates over *legend-lite's*
  element sites. An element the **reference** produces but we do not yields no verdict at all
  — `REFERENCE_REJECTED` fires only in the opposite direction. The `pureOnly` gate currently
  masks this; fixing the gate without fixing the direction converts a silent skip into a
  silent pass.
- **Composer parity is not started.** Upstream asserts text→JSON→text byte-exactly in ~215
  tests. Every section's composer must move in lockstep, and several normalize on compose
  (`platform: Default` injection, bare `include` → `include mapping`, Diagram float
  `22.3E-9` → `2.23E-8`).
- **`###QueryPostProcessor`** — `RelationalGrammarParserExtension.java:103` registers a second
  section from the same module. Not inventoried.
- **A fourth unhandled island tag `#qc {`** — 1 corpus occurrence, extension unidentified.
