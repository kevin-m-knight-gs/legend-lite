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
>
> **Prior art:** [`PARSER_DROP_IN_PLAN.md`](PARSER_DROP_IN_PLAN.md) **§4.2 and Phase 3
> already carry a non-`Pure` section worklist.** This document was written without
> consulting it; §10 reconciles the two. Several findings here are confirmations of items
> the plan already listed — credited there — and two of the plan's items are now stale.

---

## §0 — Where we actually are

### The gate, measured today

```
corpus sources        : 7219
verdicts              : 19273
  MATCH               : 19273
  DIFF                :     0
  WALL                :     0
  PARSE_FAIL          :     0
  REFERENCE_REJECTED  :     0
```

`walls-detail.txt` and `parsefails-detail.txt` are both **0 bytes**.

### The suite is GREEN — and the ratchet measures the wrong thing

> **Corrected 2026-08-05** (re-measured, not quoted). This section previously reported the
> suite RED on `SpiSeamProofTest` at `212 > 182`. **That was stale.** Commit `56d5449d`
> ("Corpus burn-down Tier 1") drove the census **182 → 170** by rejecting the new `'''`
> doc-string sugar loudly; `MAX_LENIENT_ACCEPTS` is now 170 and the measured census is
> exactly 170. `mvn -o test -pl parser-equivalence` → **4 tests, 0 failures**.

| test | status |
|---|---|
| `CorpusEquivalenceTest` | PASS — 19,273/19,273 |
| `RejectionParityTest` | PASS — 43/43 pins (error-**line** agreement 20/43 — asserted on nothing) |
| `SectionParseSentinelTest` | PASS — 857 vs `MIN_FILES_PARSED = 857` |
| `SpiSeamProofTest` | PASS — 170 vs `MAX_LENIENT_ACCEPTS = 170` |

**The live problem is not the ratchet's value. It is what the ratchet measures.**

`MAX_LENIENT_ACCEPTS` does not measure the parser — it measures the **bridge**.
`LegendLiteSectionParser.parseSection:109-170` is a *site scanner*, not a section parser: it
collects `topLevelIndexes` for five element kinds plus `measureSites`, parses those, and
**silently ignores every other token in the section**. Measured over the whole corpus:

```
vanilla-rejected pure-only files    : 1830
  legend-lite parseStrict ACCEPTS   :  742   (40.5%)
  SPI bridge accepts                :  170
```

**legend-lite's own strict parser accepts 742 files vanilla rejects — 4.4× the bounded 170.**
The bridge hides the difference by never looking. Attributed, of the 170: ~20 are genuinely
benign (extension-less classpath, engine bugs), ~30 are real parser leniency
(`allVersionsInRange`, `Primitive`, `enforcementLevel`), and **~120 are the bridge skipping
tokens it does not recognise**. `56d5449d` is the failure mode in miniature — 182→170 was
earned by adding a *token-scan guard*, not by fixing a parser defect.

**Do not lower `MAX_LENIENT_ACCEPTS` further.** Add `MAX_PARSER_LENIENT_ACCEPTS` measured
through `parseStrict` (baseline **742**); make the bridge total; then the two converge.

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

### 1.0 The denominator, measured exactly

Added 2026-08-05 by re-implementing the gate's own predicates over `Corpus.all()`:

```
corpus sources           : 7219
  skipped: not pure-only : 1370   (19.0%)   ← §1.1, the pureOnly regex
  skipped: reference NULL: 1810   (25.1%)   ← whole file, silent, no verdict
  IN SCOPE               : 4039   (55.9%)
```

**The gate adjudicates 55.9% of the corpus.** The second bucket is its own mechanism:
`ParserEquivalence.java:170-172` catches `Throwable` from the reference parser and returns
`null`; `:83-86` then returns zero verdicts for the whole file — not a `REFERENCE_REJECTED`
verdict, not even a skip counter. **So the report's `REFERENCE_REJECTED: 0` is not evidence
of anything**, and `0 WALL` means "no wall among the 4,039 files the engine parsed whole."

That bucket is not randomly distributed: it holds **109 files containing `#TDS`, 33 with `#/`,
and 8 with `#>{`** — precisely the island constructs whose span rules are weakest (§2).

**A cheap, exact fix exists.** The comparator (`ParserEquivalence.compare:107-143`) iterates
*legend-lite's* sites and looks each up in the reference's map; the opposite direction has no
code path. Draining the map after the loop and emitting a `LITE_MISSED` verdict per leftover
key converts today's silent skips into named worklist rows. Measured, that hole hides exactly
two things:

| hidden | count |
|---|---:|
| `SectionIndex` — one per accepted Pure-only file, never compared | **4,039** |
| `Measure` — `ParserEquivalence:92-106` omits the site kind that `LegendLiteSectionParser:119` scans | **32** |

Re-run excluding `SectionIndex` and adding `measureSites`: reference elements **19,305** =
legend-lite sites **19,305**, zero files where reference > sites. The two front doors disagree
about what an element is, and that is the whole of the discrepancy.

> **This corrects `PARSER_DROP_IN_STATUS.md` §3.1**, which states *"Engine REJECTS native
> functions and Measure in `###Pure` outright — both permanently out of comparable scope."*
> Native is correctly out of scope. **Measure is not** — the reference emits 32 Measure
> elements from Pure-only sources, and `Measure` appears in the engine's own
> `Valid alternatives` list.

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

### 8.1 Adjudicated — the sentinel has no oracle, and 43% of its failures are legal

**Corrected 2026-08-05.** The 257 failures were re-run with the reference parser adjudicating
each one:

```
in scope 1114   lite failures 257
  reference ACCEPTS (real drop-in defect) : 146   (57%)
  reference also rejects (legal refusal)  : 111   (43%)
exception types: ParseException 250, IllegalArgumentException 4, NullPointerException 3
```

The "~115 of 257 (45%)" above is confirmed to the file — the exact figure is **116**. What it
did not know: **only 48 of the 116 are demonstrable defects today.** The other 68 sit on files
the reference *also* rejects, mostly for want of the serviceStore / persistence /
external-format grammar jars (§9). They are **unadjudicated, not innocent**.

`SectionParseSentinelTest` ratchets `MIN_FILES_PARSED` on this mixed signal, so it cannot
distinguish a fixed gap from an upstream file that got less legal. **Give it a reference
oracle before trusting its floor.**

Highest-value single fix by *proven* blast radius: **`MappingGrammarParser.java:505`**
(`AggregationAware ~mainMapping kind 'Pure' is not supported`) — 20 files, **20/20**
reference-accepted, one `if`.

### 8.2 The mirror image, on the byte-parity path

The closed-world walls have an opposite number that this section missed:
`~enforcementLevel: <any identifier>` (`ElementParser.java:766` → `:809`) validates **nothing**,
where the engine grammar closes the production to `('Error'|'Warn')`. Same defect class,
opposite sign, and unlike the walls it is reachable from `at()` — the actual drop-in surface.

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

---

## §10 — Reconciliation with `PARSER_DROP_IN_PLAN.md`

This document was produced by a fresh nine-agent sweep that did **not** consult the plan's
§4.2 worklist or Phase 3 ordering. That was a process error; the reconciliation below is the
correction. Where the plan got there first, it is credited.

### 10.1 The plan already knew — this sweep confirms and quantifies

| plan item | plan's statement | what this sweep adds |
|---|---|---|
| §4.2 **#1** | *"Fix the silent-drop lexer — prerequisite for everything. You cannot delegate a section you have already swallowed."* | Confirmed independently by four agents, and **quantified**: 1,363 of 7,211 sources (18.9%) yield zero verdicts; ~2,452 `###Pure` elements invisible. The plan's priority ranking was right. |
| §4.2 **#10** | *"Delete `MappingGrammarParser.java:432-440` — it deliberately swallows the tail of an XStore block after a missing comma. Incompatible with any equivalence claim."* | Confirmed at `:430-457`, **and the justification is disproven**: the comment cites *"ENGINE PARITY (audit 21a §4b): the engine's XStore rule … no EOF anchor"*, but `XStoreAssociationMappingParserGrammar.g4:12-14` **has** `EOF`. The engine rejects; we reproduce *legend-pure's* behaviour. So it is a rejection-parity divergence too, not only a drop. |
| §4.2 **#6** | `###Service` — *"the parser supports a construct the lexer never delivers."* | Confirmed exactly (`ElementKind.SERVICE` live and wired through `ModelBuilder`/`NameResolver`/`ModelNormalizer`; section not in `LEXABLE_SECTIONS`). **But see §10.3 — the plan's "cheapest real coverage win" does not survive measurement.** |
| §4.2 **#11** | multi-line `'''…'''`, with the deliberate upstream tagged-value gap: *"Do not 'fix' it."* | Not re-examined here. Flagged so it is not lost. |
| Phase 3 exit criteria | S1 100% on the slice; **100% of that section's `PARSER error at` pins reproduced including ranges**; no cross-section regression | This sweep found the pins exist and are ready: ~46 for Connection/Runtime, ~40 for Relational. The plan's criteria are the right ones and should govern. |

### 10.2 Plan items now stale

| plan item | status at `266fe1d5` |
|---|---|
| §4.2 **#2** — *"A line index in the lexer… today line/column is computed only when throwing, by an O(offset) rescan"* | **Appears done.** `PARSER_DROP_IN_STATUS.md` §3 lists `com.legend.lexer.TokenStream` as *"lazily-built line index, binary search."* Verify before re-doing. |
| §4.2 **#7** — `###Connection` *"fails 0/3 today despite being whitelisted. A bug."* | **Understated.** Measured **17/56 sections parse (30.4%)**, and the shape is not a bug but a divergence: 1 of 8 connection types, 4 of 16 datasource specs, 3 of 12 auth strategies, with **invented keyword spellings** (`Static{database:}` vs engine `name:`; `UsernamePassword` vs `UserNamePassword`) that can never match. §5. |
| §4.2 **#8** — `###Data` *"parses but produces 0/4 matching elements."* | **Framing is stale.** 48 of 57 `###Data` files parse cleanly, with **zero failures attributable to `###Data`** (all 9 are other sections). 42 single-section files need only embedded-data kinds legend-lite already emits. §5 ranks it the best cost/benefit in the worklist. |
| Risk register line 411 — *"`MappingElementContext` shim fails → delegate `###Mapping` permanently"* | **Dead risk.** The shim was never needed: `LegendLiteSectionParser:150-155` emits JSON and hands it to the engine's own deserializer; no ANTLR context is constructed. `MappingParser`/`ConnectionParser`/`RuntimeParser` all return `ImportAwareCodeSection` — the exact type the bridge already builds. Only `###Relational` differs (`DefaultCodeSection`), a one-line change. **Strike this row.** |

### 10.3 Direct conflicts — the plan's ordering vs this sweep's measurements

**Phase 3 order.** The plan: **Connection → Diagram → Runtime → Relational → Mapping → Pure**,
justifying Connection first on grammar size (*"39-line grammar, 105-line walker, and it
inherits all 8 connection types plus 12 database datasource/auth modules free"*).

This sweep measured: **Runtime → Connection → Relational → Mapping**, with **Diagram removed**.

| point | evidence |
|---|---|
| **Diagram has no reference-adjudicable corpus** | Its 49 corpus files use legend-pure's **M3** dialect (`Diagram fqn(width=, height=) { TypeView … }`); the engine grammar demands the Legend one (`Diagram fqn { classView … }`). Different languages sharing a section name. This also resolves `PARSER_DROP_IN.md:713-716`'s *"genuinely unexplained"* zero. Diagram is the cheapest **lexing-coverage** win and a **byte-parity dead end** — both true, different questions. |
| **Runtime is the smaller proof** | 46 elements, 8 wire discriminators, 33 field names — the smallest *complete* section, and it exercises the whole non-`Pure` loop end to end. Connection is 25 elements but drags in 16 datasource specs and 12 auth strategies, i.e. the plan's "free inheritance" is the part that is **not** built (4 of 16 and 3 of 12 today). |
| **Relational must precede Mapping** | Relational **is** ~9 of Mapping's 52 wire discriminators and **8,620 of Mapping's node instances** (`Table`, `column`, `relational`, `elemtWithJoins`, `dynaFunc`, `literal`, …). Doing Mapping first means building that vocabulary twice. |

**`###Service` priority.** The plan calls it the *"cheapest real coverage win"*. Measured: all
**51** `###Service` corpus files are mixed-section — **none** is reachable by any byte-parity
gate today, and 28 of them cluster in a service test-runner module legend-lite has no reason
to consume. The cheap *lexer* win is real; the *parity* win is zero until §1's gates move.

### 10.4 What this sweep adds that the plan does not cover

- **The `###Pure` 100% has a hole** (§2). `#SQL{}`, `#TDS{}`, `#GQL{}` are the *same production*
  as `#>{db.tbl}#`; we handle 2 of 5 island tags and `#TDS{}` emits the wrong wire shape
  silently. This is inside the section the plan treats as finished.
- **A second exclusion mechanism** (§1.4): the harness classpath carries three grammar modules,
  so for other sections the *reference* parser throws first — byte parity is **undefined**, not
  failing. Each section needs a pom line as well as a gate change.
- **Per-section construct inventories** with corpus counts, parse rates, and cost.
- **Eighteen silent drops** and **eight false comments** (§3, §4).

### 10.5 Recommended resolution

Adopt the plan's **§4.2 dependency ordering and Phase 3 exit criteria** — they are sound and
this sweep did not improve on them. Replace only:

1. **The Phase 3 section order** with `Runtime → Connection → Relational → Mapping`, per §10.3.
2. **Strike** the `MappingElementContext` risk row.
3. **Re-scope** items #7 and #8 to the measured findings.
4. **Add** §1.4 (classpath) as a prerequisite beside §4.2 #1 (silent-drop lexer) — both gate
   every section, and neither is grammar work.
