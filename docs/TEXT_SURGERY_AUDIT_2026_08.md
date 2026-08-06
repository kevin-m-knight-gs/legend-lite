# Text-surgery audit — regex and string manipulation (2026-08-06)

**The question:** where does legend-lite reconstruct, by operating on text, structure that it
already had — and what does that cost?

**Measured against** legend-lite `28a3e999`, legend-engine `943d38b3dc2`. Six parallel
audits: four over regex (drop-in · pipeline · measurement · `engine/`), one designing
ArchUnit enforcement, one over string surgery. Every instance enumerated; nothing sampled.
Counterexamples were constructed and verified, not asserted.

> **Why this matters more here than in most codebases.** legend-lite's premise
> (`PARSER_DROP_IN.md`) is that a hand-written recursive-descent parser replaces ANTLR with
> *precise, span-carrying* parsing. A regex inside that surface is a second, undocumented
> grammar running beside the first — with its own idea of what an identifier is, and no
> token positions. Byte parity (19,269/19,269) is a property of the recursive-descent
> parser; every regex is surface that property does not cover.

---

## §0 — Scope and denominator

**Regex — 8 entry forms, 81 files, ~334 occurrences.** The surface is fully bounded: zero
`StringTokenizer`, `Scanner`, `splitAsStream`, `asPredicate`.

Bytecode census of `core/src/main` (905 classes, 29 use regex):

| form | call sites |
|---|---:|
| `java.util.regex.*` | 86 |
| `String.split(String[,int])` | 48 |
| `String.matches(String)` | 24 |
| `String.replaceAll/replaceFirst` | 8 |
| **total** | **166** |

**String surgery — bounded by a rule, not by an API.** An instance qualifies only if the
string being operated on *had* structure that this codebase destroyed (minted a name from
parts, joined a list, rendered an IR to text, flattened a token stream). Operations on
genuinely foreign text — corpus SQL, golden literals, CSV input — are out by construction
and were each verified rather than assumed.

**Reproduce:**
```
grep -rnE 'java\.util\.regex|Pattern\.compile|Pattern\.matches|Pattern\.quote|Matcher|\.split\(|\.matches\(|\.replaceAll\(|\.replaceFirst\(' --include='*.java' */src
```

---

## §1 — Findings ranked by severity

### 1.1 Wrong answers — silent, user-visible

| # | site | what happens |
|---|---|---|
| 1 | `resolver/GenericTypeReflection.java:86` | `pfx.substring(4, len-3).replace("__","::")` — for `Class my::A__B`, `$x->genericType().rawType` returns **`B`**. Wrong string in a query result, no diagnostic. |
| 2 | `parser/ElementParser.java:1736` | `body.indexOf(':')` finds the first colon **anywhere in the block, including inside data cells**. A relation block whose cell contains `10:30` is silently misparsed — path, columns and rows all garbage. Quote-aware splits sit on *either side of it* in the same method. |
| 3 | `lowering/Scalars.java:2942-2944` | Sub-second truncation and zone-drop **in the lowerer**. A `+0530` TDS cell (legal per `TdsChecker:197`) shifts the instant 5.5 h; `.123456789` truncates to `.123456` while the *same value* as a `%`-literal survives at ns precision via `MatchFold:96`. It also renders `DuckDb.java:36`'s `TIMESTAMP_NS` branch **dead for every TDS literal** — the lowerer destroys the information the dialect exists to preserve. |
| 4 | `compiler/spec/Typer.java:2055, 2159` · `normalizer/MappingNormalizer.java:1059` | **Three demanglers** (four counting the segment counter at `Typer:2162`, which uses a *second, different* grammar for the same string). Each fires only on a lookup **miss**, and each can silently redirect a call to a *different, existing* function: `compute_Step_2_()` → `compute()`. |
| 5 | `resolver/CorrelatedSubselects.java:1662` | `indexOf(stPrefix)` matches the marker *anywhere*, and the result keys a `LinkedHashMap`. Two hops carrying the same subtype column overwrite each other — the cast leaf binds to the wrong hop. |
| 6 | `resolver/CorrelatedSubselects.java:650` | `n.matches(Pattern.quote(k) + "_\\d+")` absorbs a **physical column** named `ID_2` as a routed union split key. The grouped subselect then groups by it. Wrong rows. |
| 7 | `compiler/spec/TdsChecker.java:170, 175, 197` | Type inference by character class, **inside the type checker**. `1e5` infers `Integer`, then `Long.parseLong("1e5")` crashes at lowering. `+05:30` (colon offset, fully legal ISO-8601) infers `String`; its sibling `+0530` is accepted and then mis-lowered by #3. |
| 8 | `resolver/TemporalFrame.java:712, 880` | The `_nav_` probe enumerates two spellings of an unbounded mint ladder; on a miss it returns `null`, `outerColumnDate` is null at `:1686`, and **the join is emitted without its temporal window** — version fan, extra rows. |
| 9 | `normalizer/UnionSynthesis.java:900` vs `resolver/Substitution.java:1968` vs `resolver/SyntheticHeads.java:987` | Three decoders of one encoding disagreeing on **first vs last** separator. A property named `a__b` resolves to the wrong leaf. |
| 10 | `parser/SpecParser.java:2510, 2686, 2705, 2939` | Four quote-blind or comment-blind scans in path-literal and graph-fetch parsing. `:2939` re-scans raw source **120 lines after a correct token walk in the same file produced `contentText`**. |
| 11 | `normalizer/UnionSynthesis.java:1860` | `split(",")` over a `String.join(",")` from `:1697`. A quoted column named `"a,b"` flips the query between the merged and suffixed-NULL union forms. Different SQL, different rows. |

### 1.2 Wrong bytes — corrupt emitted output

| # | site | what happens |
|---|---|---|
| 12 | `sql/dialect/EngineStyleH2.java:1239-1241` | Three literal `String.replace` calls repair the parent's casing, content-unaware. A window over `case when st = 'HANDED OVER (2020)'` ships **`'HANDED over (2020)'`** — a corrupted data value in SQL. **No regex involved.** |
| 13 | `protocol/Protocol.java:541` | Strips quotes without unescaping. `Class test::'O\'Brien'` emits `O\'Brien`; the declaration decoder yields `O'Brien`. See §4. |
| 14 | `sql/dialect/EngineStyleH2.java:474, 557` | `expr(...).replace("'", "\\'")` rewrites SQL-style `''` escapes *inside* rendered literals when splicing into Freemarker templates. |
| 15 | `protocol/Protocol.java:515, 535` | `splitFqn`'s `lastIndexOf("::")` cuts **inside** a quoted segment; `unquoteSegments` re-splits the halves and silently gives up. |
| 16 | `sql/dialect/EngineStyleH2.java:1549` | `castTypeName(...).toLowerCase()` lowercases a quoted struct field name inside its own quotes. |
| 17 | `protocol/ProtocolEmitter.java:1347` | `tbl.value().split("\\.")` — a catalog-qualified reference emits a 4-element wire path where 3 are expected. The parser *had* `(db, schema, table)` separately and re-joined them. |
| 18 | `sql/dialect/EngineStyleH2.java:640` | Fixed offset 10 assumes a 4-digit non-negative year; `PureDateLiteral.validateYear` permits neither assumption. |

### 1.3 Crashes on legal input

| # | site | trigger |
|---|---|---|
| 19 | `resolver/SyntheticHeads.java:88` | `head.charAt(i+1)` on a property named `'a#'` → `StringIndexOutOfBoundsException`. **The constructor guard written for exactly this case never runs**, because `of()` reads the char before constructing. Reachable from 58 call sites. |
| 20 | `plan/PlanText.java:71` | `sql.substring(7, sql.indexOf(" from "))` — a fromless scalar plan (`select 1`) gives `substring(7,-1)`. A nested `select … from …` finds the *inner* `from`. |
| 21 | `parser/PureModelParser.java:941` (engine) | `"1..".split("\\.\\.")` → trailing empty dropped → `parts[1]` → AIOOBE. |
| 22 | `harness/TestBody.java:552` +9 more | `String.valueOf(e.getMessage()).split("\\n")[0]` — **10 copies**. `"\n".split("\n")` returns a **zero-length array**, so `[0]` throws **inside a catch block**, converting a clean `Unsupported` verdict into a hard crash. |
| 23 | `normalizer/UnionSynthesis.java:1474` · `model/ClassMapping.java:57` | Name collisions (§2) surface as `IllegalArgumentException: duplicate column …` from `Type.java:369` — no phase, no element, no mention of either colliding property. |

### 1.4 The one users cannot avoid

| # | site | why it's different |
|---|---|---|
| 24 | `compiler/element/type/Type.java:351` + `lowering/Lowerer.java:3045` | `PIVOT_SEPARATOR = "__|__"` puts **user data** into column identifiers and re-parses them. Pivot on `(city, year)`: `('NY__\|__2020','x')` and `('NY','2020__\|__x')` mint the same key. Every other collision here is avoidable by not naming a class `A__B`; this one is triggered by ordinary `VARCHAR` data. |

---

## §2 — Nine encodings

The architecture audits' central finding — *missing noun → encode into a name → re-parse at
N consumers* — is **primarily a string-surgery pathology**. A regex sweep catches only the
three decoders that happen to use `Pattern`.

| # | encoding | mint | decoders | ambiguous? | missing concept |
|---|---|---|---|---:|---|
| E1 | `stc_<Fqn>___<prop>` subtype dispatch | `ClassMapping.java:57` | **14** (3 bypass the contract) | **yes ×3** | `SubTypeCarrier(classFqn, property, witness, leafPath)` as a tag on `Type.Column` |
| E1a | `…___<prop>__<leaf>` flattened leaf | `UnionSynthesis:960` | 3, **disagreeing on first-vs-last separator** | yes | — |
| E2 | `emb__<path>__<sub>` embedded leaves | `UnionSynthesis:1474` | 4 re-mints, **no decoder** (good) | yes → crash | `EmbeddedLeafRef(List<String> path, String sub)` |
| E3 | `_nav_` navigation slots | `JoinChainEmission:563` | 4 | mint sound, **decode incomplete** | `NavSlot` — **`Pipeline.navSlotByProp` already is this registry**, just not in `TemporalFrame`'s scope |
| E4 | `#fN`/`#dN`/`#cN` synthetic heads | `SyntheticHeads:57-118` | via contract, 58 sites | **no — this one is built right** | none; see §5 |
| E5 | `<key>_<n>` routed union members | `UnionSynthesis:2277, 2249` | 2 (one throws honestly, one guesses) | yes | `RoutedKey(physicalColumn, memberOrdinal)` — the mint already holds the map |
| E6 | `fqn.replace("::","_")` default set id | `MappingNormalizer:582` | **7 duplications** of the contract | yes, but **detected** (`ModelBuilder:412`) | `SetId` value type |
| E7 | `__\|__` pivot key | `Type.java:351` | `Executor:495`, `Fold:455` | **yes — by data** | `PivotCell(values, template)` + opaque aliases |
| E8 | function-signature mangle | `Protocol:237-270` | `TestBody:1095` | yes — `my__func` cuts at the first `__` | `FunctionSignature` with `mangled()` a rendering |
| E9 | `<alias>_<col>` slot prefix | `AssociationJoins:465` | — | — | **the file maintains both the ` ` key and the flat one** — the correct key is written beside the wrong one |

E9 is worth reading directly. `AssociationJoins.java:465` writes both an unambiguous
` `-separated key *and* the ambiguous flat one, because a downstream flattener only
understands the latter. It is the clearest statement in the tree that the authors know which
key is correct.

---

## §3 — Measurement integrity

A regex here does not risk a wrong answer; it changes **what is counted**.

| # | gate | blind spot |
|---|---|---|
| M1 | `^###(\w+)` pureOnly skip — **3 verbatim copies** (`ParserEquivalence:71`, `SpiSeamProofTest:51`, `RejectionParityTest:137`) | **1,363 of 7,211 sources (18.9%)** yield *zero verdicts* — including their fully-comparable `###Pure` regions. Recovering them is **+12.7% corpus** against `MIN_ELEMENTS_COMPARED = 19,269`. |
| M2 | `InlineSnippets.PURE_DECL:31` | **544 `###Mapping` literal runs rejected**; 1,838 `###`-bearing runs total. The class doc argues extraction fidelity is safe because *"the REFERENCE parser adjudicates"* — but this gate runs **before** the reference sees anything. **The design's own safety argument does not cover its own gate.** |
| M3 | `SectionParseSentinelTest:34` | Downstream of M2 — its inline half only sees what `PURE_DECL` admitted. The two are coupled and must be re-pinned together. The pin is on `parsed`, not `inScope`, so a scope collapse reports as grammar drift. |
| M4 | `H2Verify.java:559` | **Verified broken.** `replaceAll("\\.?0+$","")` binds to the *seconds* field when there is no fraction: `'…00:00:00'` → `'…00:00:'`, `'…00:00:10'` → `'…00:00:1'`. `norm(a) == norm(b)` is **false** for the two forms it exists to reconcile. Applied to both sides of the H2 oracle. |
| M5 | `CodeShapeGuardrailTest:116, 124` | `SIG` is anchored to **exactly 4 spaces** → **177 nested-class methods never length-checked**. `MUTABLE_FIELD` misses annotated fields and wrapped initializers → **2 live escapes** (`TemporalFrame:2465`, `SyntheticHeads:289`), neither allowlisted. And **two existing allowlist entries are `@Nullable`-annotated, so the pattern never matched them** — those lines are dead, which means the audit that added them read the source, not the gate's output. |
| M6 | `ErrorShapeGuardrailTest:185` | Asserts **zero** control flow on exception text. Escaped by a parameter hoist: `ExecuteLegendLiteQuery:216` passes `getMessage()` into a helper; `:1034` branches on `message.contains(...)`. |
| M7 | `ErrorShapeGuardrailTest:146` | `\breturn ([^;]{1,80});` — a returned expression **longer than 80 chars is not counted**, which is the case most likely to be a fabricated answer. The cap inverts the intent. |
| M8 | `RelationalCorpusRunner:235, 239` | `-Drcorpus.only=,` → zero-length array → treated as **unscoped** → **rewrites `docs/RELATIONAL_CORPUS.md`**. And `contains` not equality: **27 of 169 families over-match** — `tests` runs **62**, `tests/mapping` runs 28. There is **no way to scope to `tests/mapping/union` alone**. |
| M9 | `RelationalCorpusRunner:740` | The cross-family closure matches heads `(Relational\|Pure)` only. Corpus heads: Relational 1097, **Operation 250, Relation 130, ModelJoin 75** — **455 (29%) never produce a request**. A regex miss is not a request: nothing is attempted, nothing is logged, and the compile failure is **attributed to the model**. The affected families are exactly the weakest ones on the scoreboard. |
| M10 | `Runner:1157` | `unknownTypePull` retries a test on match and finalizes ERROR on miss — a live pass/fail gate coupled to **nine hand-written message strings in `core/`**, readable only from `getMessage()`, never the cause chain. |
| M11 | CSV cell splitting | **Three parsers, one correct.** `Ddl.csvCells` is quote-aware; `CsvSeed:60,89` and `TestDataGenerator:1264` are not — corrupting the **seed** side and the **golden** side respectively. Four sites also disagree on the block separator (`-+` ×3, `-{3,}` ×1). |
| M12 | `TestSuiteRunner:147` | `replaceAll("\\s+","")` on both sides: `{"name":"John Doe"}` and `{"name":"JohnDoe"}` compare **equal**. A false-pass path. |

---

## §4 — Documentation that is false

Each was used to *justify* a decision, so a reader who trusts it will not check.

| # | claim | reality |
|---|---|---|
| 1 | `Protocol.java:505` — *"the only place in legend-lite that splits an FQN"* | **33 sites.** And **15 files carry a private `simpleName(String)` clone**. `PackageableElement` deliberately refuses to expose `simpleName()` as an "attractive nuisance" — the refusal was right, but the replacement (a shared quote-aware `QualifiedName`) was never built, so fifteen sites reimplemented it with the same bug. |
| 2 | `TokenStreamCursor:337` — *"`foo::'bar'::baz` is not legal Pure in any position"* | Contradicted by `fqnSegmentText:384`, **three lines below**, which accepts a `STRING` as any FQN segment. |
| 3 | `SyntheticHeads` — *"the ONE owner of the `'#'`-suffix convention"* | `#p` is minted at `NavMaterializer:748` and read at `Substitution:1177`, outside the registry. `JoinIdentity.of("prop#p")` would throw *"malformed synthetic head (resolver bug)"*. |
| 4 | `ElementParser:2094` javadoc — *"LOUD on anything else"* | `.find()`, not `.matches()`. Extra keys in the island are **silently dropped**. The loudness covers only a wrong flavour prefix and the *absence* of the two fields. |
| 5 | `PropertyMapping` javadoc — *"Parsed at construction time so consumers don't need regex"* | It **is** regex, just relocated. A JSON key containing `-` (routine) yields `Optional.empty()` and the mapping silently loses its key. |
| 6 | `Protocol.java:541` region | `TokenStreamCursor.unquoteAndUnescape` documents *"audit M11 found **EIGHT copies**, half of which forgot the escapes."* This is the ninth, and it forgot the escapes. |

Carried forward from `CORPUS_STUDY_2026_08_ALL.md` §9, still open: `EngineStyleH2:1277`
(dateadd case), `Scalars:79` (*"IS NOT DISTINCT FROM appears in no golden"* — it appears 113
times), `MappingNormalizer:2358`, `RelationalGrammarParser:76`, `MappingGrammarParser:430`.

---

## §5 — What is already right

Do not "fix" these; several are the templates for the remediation.

- **`com.legend.lexer` — zero regex.** Its *entire import list* is four lines: `HashMap`,
  `Map`, `ArrayList`, `List`. A pure character scanner throughout.
- **`SyntheticHeads.JoinIdentity`** — a real value type, `encoded()`/`of()` inverse in one
  place, a loud guard on malformed input, 58 call sites routed through the contract, and a
  self-documented residual. **This is the shape every other encoding should take.**
- **`Pipelines:1024`** — detects the `_<n>` ambiguity and **refuses to decide**, naming both
  possibilities in the message. The correct response to an un-first-classed name.
- **`ModelBuilder:412-427`** — catches the set-id collision with a proper `ModelException`
  naming both classes.
- **`Runner` stereotype detection** — typed `StereotypeApplication`, one classifier for the
  whole harness. The historical 77 `ToFix`-without-`Test` miscount **cannot recur here**.
- **No message-template failure grouping** — buckets merge on the raw message; the javadoc
  explains the merge hazard it avoids.
- **`RawSql.splitStatements`** — a character scanner that skips quoted literals. A semicolon
  inside a string does not split.
- **`RawSqlBoundary`** — regex over genuinely foreign text, by explicit contract: *"never
  against platform-GENERATED SQL."*
- **`Ddl.csvCells`**, **`AnsiSqlRenderer.ident`**, **`PlatformTypes`** (which *is* the fix for
  one instance of the `endsWith("::X")` bug class), **`mintNavSlotAlias` + `prefixFor`**
  (conservative, include-aware collision checks).
- **Zero unescaped metacharacters** anywhere in the tree. **Zero `replaceAll` that should be
  `replace`** in `engine/`; the four in `core/` all use genuine regex.

**Stated plainly:** the case for enforcement is prevention, drop-in purity, and the 52×
allocation figure that justifies the project — **not a metacharacter bug on the floor today.**

---

## §6 — ArchUnit enforcement

Verified against `core/target/classes`: **compiles clean, all five rules green, +0.062 s** on
a 1.85 s / 23-test baseline.

### The trap

```java
noClasses().should().dependOnClassesThat().resideInAPackage("java.util.regex..")
```

catches **86 of 166 call sites in `core/src/main` — 52%**. The other 80 are `java.lang.String`
methods, invisible to any package rule. A package can be "regex-free" by the naive rule while
calling `split` on every line.

The construct that closes it is `callMethodWhere(DescribedPredicate<JavaMethodCall>)`, matching
`split(String)`, `split(String,int)`, `matches(String)`, `replaceAll`, `replaceFirst` **by exact
signature** — so the 42 literal `replace(CharSequence,CharSequence)` and 10 `replace(char,char)`
calls survive, as do `Map.replaceAll(BiFunction)` and `List.replaceAll(UnaryOperator)`.

**Demonstrated both ways.** With an empty allowlist it names all 8 drop-in instances with
`file:line`. And it correctly ignores four sites a grep gate flags: two `Map`/`List.replaceAll`,
one user-defined `.matches()`, and one `java.util.regex` mention **in a comment**. That
precision is the argument for ArchUnit over a CI grep.

**Allowlists must be FQN strings, not class literals** — 11 of the 29 regex-using classes are
package-private, so the `doNotBelongToAnyOf(X.class)` shape used by invariants 4c/4d cannot
name them.

### The rules

- **8a / 8a′** — the drop-in `{lexer, parser, protocol, values, error}` has one grammar. Two
  tests because `String`'s methods need `callMethodWhere` and the package needs
  `dependOnClassesThat`. 4-entry **shrinking** allowlist, each with its exit criterion.
- **8b / 8c** — `java.util.regex` and the `String` methods allowlisted **class by class**
  across `com.legend..`. Graduated rather than binary: a repo-wide ban is unlandable at 29
  classes, so it would ship with blanket package exclusions — the landfill invariant 2 exists
  to prevent. Naming classes makes every new regex a review event.
- **8d** — `com.legend.lexer` is regex-free, **no allowlist, ever**. Passes today.

### Free today

**~180 of 418 files** are already clean and lockable at zero cost: `lexer`, `values`, `error`,
`cache`, `model..`, `protocol.spec`, **`compiler.spec.typed` (76 files — the tier that should
never need text inspection doesn't)**, `sql`, `ide`, `builtin`, `validation`, `lineage`,
`compiler.element.type`.

### What ArchUnit cannot see

1. **Dead imports.** `MappingGrammarParser:55-56` and `RelationalGrammarParser:52-53` import
   `Pattern`/`Matcher` with **zero uses**; javac erases them, so no bytecode. → Checkstyle
   `UnusedImports`. Free deletions that also clear the way for 8a.
2. **`parser-equivalence`** is not on core's test classpath — and per §3 that is where the
   costly harness regexes live. → a sibling `ArchitectureTest` in that module, whose rule is
   *"the section regex is defined once"* rather than "no regex".
3. **Whether a permitted regex is correct.** That stays with the corpus gates — which are
   **currently red** (`SpiSeamProofTest`: 212 > 182 after upstream drift). Invariant 8 is a
   rule about shape and does not substitute for re-ratcheting that census.
4. `Scanner` / `StringTokenizer` — **zero today**, so add them to 8b's banned set now, while
   it is free.

---

## §7 — Remediation order

**Tier 0 — free, do first**
1. Delete the 4 dead imports (2 files).
2. Land **8d** (lexer) — passes today, locks in a held property.
3. Land **8b/8c** with the measured allowlists — freezes the surface at 29 classes.
4. Add `Scanner`/`StringTokenizer` to the ban while the count is zero.

**Tier 1 — measurement first, because everything else is measured by it**
5. **M4** `H2Verify.norm` — delete the regex; the temporal case belongs beside the existing
   `Boolean`/`Number` arms (`ts.toLocalDateTime().toString()` already omits a zero nanos field).
6. **M8** `-Drcorpus.only` — `split(",", -1)` + filter empties; match `equals(x) || startsWith(x + "/")`.
7. **M1/M2/M3** — one `Corpus.isPureOnly`; widen `PURE_DECL`; re-pin the sentinel and its scope
   together.
8. **M5** — add `(?:@[\w.]+\s+)*` to the field patterns, drop the same-line `;`, replace the
   4-space anchor. Then re-check the two dead allowlist entries.
9. **M9/M10** — widen the class-mapping head set; replace the message regex with a typed
   `UnresolvedElementException` carrying the FQN.

**Tier 2 — the drop-in**
10. `ElementParser:2094` — re-parse the island with `TokenStreamCursor`; give the record
    `store`, `id`, `SourceInfo`; throw on unknown keys.
11. `ElementParser:1736/1746` — one quote-aware pass, reusing the state machine `csvCells` and
    `parseRelationIslandElements` already have.
12. `SpecParser` path literals — hoist segment recognition into `scanPathArgs`; gate identifiers
    on `Lexer.isIdentPart` so the parser and lexer share one definition.
13. `Protocol:515/535/541` — one quote-aware `::` scanner; call
    `TokenStreamCursor.unquoteAndUnescape` instead of the ninth copy.
14. `ProtocolEmitter:1347` — carry `(db, schema, table)` from the parser.

**Tier 3 — the encodings (§2), highest severity first**
15. E1 `SubTypeCarrier` (fixes #1, #5, #9 and the `stc_x___y` envelope drop).
16. E7 `PivotCell` + opaque aliases (the only data-triggered collision).
17. E3 — put `Pipeline.navSlotByProp` in `TemporalFrame`'s scope; delete the probe.
18. E5 `RoutedKey`; E2 `EmbeddedLeafRef`; E8 `FunctionSignature`; E6 `SetId`.
19. `SyntheticHeads:88` — length check; add `Kind.OCCURRENCE_SPLIT` for `#p`.

**Tier 4 — the sweeps**
20. One `Errors.firstLine(Throwable)` — retires 10 crash sites and 9 allowlist entries.
21. One `SqlStatements.split` — retires 3 non-quote-aware copies.
22. `Ddl.csvCells` everywhere — retires 2 naive CSV parsers and the separator drift.
23. A shared `QualifiedName` — retires 15 `simpleName` clones and ~20 `endsWith("::X")` sites,
    per `PlatformTypes`' own pattern.

**Do not** start Tier 3 before Tier 1. Encoding fixes change row counts, and the instruments
that would measure them are the ones in Tier 1.

---

## §8 — Honest gaps

- **The pivot collision's failure mode is unresolved.** Whether `__|__` produces a duplicate-
  column crash or a silently merged cell depends on whether the output schema is built as a
  `RelationType` before or after execution. `dynamicColumns` suggests execution-time discovery,
  which would mean **merged cells, not a crash**. Worth settling first — it changes the verdict.
- **`unknownTypePull`'s live blast radius (M10) is unmeasured.** That path has no counter,
  unlike the seed and H2 paths. Determining how many tests currently PASS *because* the regex
  matched needs a sweep with it disabled.
- **`_nav_` ladder reachability.** The decoder gap is structural and certain; forcing the mint
  to bump requires a physical column colliding with a nav property name *and* a second
  colliding with the `_nav_` fallback. Not constructed.
- **Whether the engine accepts the inputs behind #15/#17.** `'my.table'` inside `#>{}` and
  `'a::b'` as an FQN segment are producible by legend-lite's lexer and grammatically
  admissible, but were not run through legend-engine. If it rejects them, both drop from
  "wrong bytes" to "leniency divergence" — still a drop-in defect, one severity lower.
- **`ServiceDefinition:108` exposure.** Splicing unquoted user text into a compiled regex is
  proven (`/api/v1.0/data` matches `/api/v1X0/data`); whether any deployed pattern contains a
  metacharacter depends on service definitions outside this repo. The only test of that
  function uses a metacharacter-free pattern — which is why it does not catch it.
- **No JDK was available in two of the six sandboxes.** Those agents verified counterexamples
  against the documented `String.split` contract and an equivalent engine rather than by
  execution, and said so per finding.
