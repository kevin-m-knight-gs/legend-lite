# The equivalence harness, audited (2026-08-11)

**The question asked:** is the harness doing plain orchestration and a straight comparison of two
outputs, or is it making decisions and compensating for things?

**Method.** Five agents, one per axis: the comparison path; the verdict machinery; falsification
(mutate the *output* and the *harness*, not the parser); the corpus and denominators; production
fidelity. Two standing rules in every brief — **no document was admitted as evidence**, including
javadoc and commit messages, and **no sampling**. Where a comment claimed a behaviour, the claim
was tested against the code. Everything below was executed against the real oracle at HEAD.

**Baseline, re-measured rather than quoted** (the figures in circulation were stale): gate 8 is
**13 tests**, all green. **8,067 corpus sources; 28,757 elements byte-compared; 0 DIFF, 0 WALL,
0 PARSE_FAIL, 0 LITE_EXTRA, 0 LITE_MISSED, 0 OUT_OF_SCOPE; 2,034 files the oracle rejects.**
Whole-document PMCD: 6,033 match / 0 diff. SPI seam: 4,481 byte-identical.

---

## §0 — The answer

**The comparison is honest. The subject is not, and the instrument has no self-test.**

Three findings, in the order they matter:

1. **The comparator has real teeth for element content.** Every single-byte, single-field,
   single-digit corruption of emitted JSON was caught — twice over, by two independent gates —
   and the comparison is symmetric: corrupting the *oracle's* bytes produces the mirror result.
   That layer is genuinely sound and the redundancy is real.
2. **Blind the three comparators and the entire chain reports a perfect green.** Nothing in the
   suite tests the instrument. With the comparators hardwired to "equal" *and* every element's
   `sourceInformation` corrupted — 93,168 mutations — only one test went red, and it is a
   hand-written 3-case probe that exists for an unrelated reason.
3. **Seven of eleven gated tests drive surfaces no shipping route reaches.** `parseStrict` has
   zero callers in `src/main`; so does `PmcdParser.parseDocument`. Production calls the lenient
   `ElementParser.parse`, which is **5.2× as lenient** as the surface the rejection gates measure.

So: a rigorous comparison, of a program that is not the one that ships, measured by an instrument
that cannot tell you when it has stopped working.

---

## §1 — What the comparator actually does

Traced end to end, both sides, with file:line.

**Oracle side:** `Corpus.all()` → `OracleParses.acquire` → `PureGrammarParser.newInstance()`
(full extension classpath) → `.parseModel(src.text())` → serialized by **the engine's own**
`ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports()`. No harness
serialization anywhere.

**legend-lite side:** `Lexer.tokenize(src.text())` → site scanners → per-kind parse →
`ProtocolEmitter.emitElement` → a hand-built JSON `String`, **never** round-tripped through Jackson.

**The final compare is `String.equals` on exactly those strings** — `ParserEquivalence:299,353`,
`PmcdEquivalenceTest:60`, `SpiSeamProofTest:89`. An exhaustive grep for
`replace|trim|strip|sort|substring|readTree|writeValueAsString` across all 13 gated classes found
every other hit to be on an error message, a report line, or a post-verdict diagnostic. **Nothing
touches a compared operand.**

**Both sides receive `src.text()` byte-identically** — no prefix, no wrapping, no normalisation.

### The span surgery, adjudicated

`LegendLiteSectionParser:169-186` (`shiftSpans`) performs post-hoc JSON surgery, adding a line
offset to every `sourceInformation` node. It exists, on the SPI path only — nothing on the
`CorpusEquivalenceTest` or `PmcdEquivalenceTest` paths touches emitted JSON.

It was adjudicated against the engine's **bytecode**, not its documentation: `visitSection` builds
the walker as `SECTION_START.getLine() - 2 + parentLineOffset`, with the columnOffset literally
`iconst_0`. `shiftSpans` reproduces that formula. **Legitimate coordinate translation.**

The engine's synthetic `"\n###Pure\n"` prefix is likewise compensated — but in *production* code
(`PmcdParser.sectionSpan:166-215`), derived from probes against engine behaviour, and then
compared against the oracle. That is the right place for it: the emulation is in the code under
test, not in the measuring instrument.

### Controls

A one-byte name change → `DIFF | $.name: expected="LaunderDiff" actual="launderdiff"`.
A dropped key → `DIFF | $.properties[0].stereotypes: expected=[] actual=null`. Exact JSON paths.

---

## §2 — Where it compensates

### Live today

**2.1 A message-prefix allowlist inside a bucket asserted to zero.**
`PmcdEquivalenceTest:84-89` pardons two exact message prefixes. One of them is
`"a Relation mapping source must name a function"` — **precisely the construct
`SectionParseSentinelTest` reports as the project's single live drop-in DEFECT**. The same defect
is counted by one gate and excused by another. The pardon matches by *string prefix*, so any
future refusal worded similarly is absorbed for free.

**2.2 Ordinary grammar refusals filed as upstream defects.**
`LeniencyCatalogTest.classify():94-96` files any throwable with a null message under
`ORACLE-DEFECT-<class>`. ANTLR's `InputMismatchException` — its *ordinary* "input doesn't match
the grammar" path — carries a null message by construction. Result: **684 of 1,472 rows (46.5%)
are filed as "the oracle is broken"**, 346 of them by this mechanism. The test asserts only that
every row carries a label; **no class has a count ceiling**, so it is a total sink and leniency
can grow without limit.

**2.3 The SPI bridge supplies parity the parser does not have.**
`LegendLiteSectionParser:92-96` hard-codes a `throw` on `native` declarations so legend-lite's
accept set matches the engine's. The harness is manufacturing agreement.

### Structural — at zero today, forced and confirmed

| # | Path | Forced result |
|---|---|---|
| 2.4 | Emitter throw → **WALL** (`ParserEquivalence:296,350`) | Confirmed. A failure to produce bytes files into a ceiling-108 bucket instead of the zero-asserted DIFF. |
| 2.5 | **The `LITE_MISSED == 0` defeat** (`:386-444`) | Confirmed **on a genuine blind spot**: `connectionSites` knows 7 connection flavours, the engine registers 8. `Elasticsearch7ClusterConnection` reports `LITE_MISSED`; add any walling connection site to the same file and it becomes `WALL` — **1 → 0**. Covers 7 element kinds and 13 tail sections. |
| 2.6 | `legalRefusals++; matched++` (`SectionParseSentinelTest:156-165`) | A drop-in defect becomes a legal refusal the moment the oracle stops accepting the file — **and raises the pass floor while doing so.** 112 rows, uncapped. |
| 2.7 | `rejectMatch++` inside `catch(Throwable)` (`RejectionParityTest:85-104`) | Confirmed: a forced `StackOverflowError` **scored as a correct rejection** and the gate passed. |

### The asymmetry bucket

`SpiSeamProofTest:91-92` gives a **failed** byte comparison a second chance: reserialize the
oracle through its own round-trip, and if that now equals legend-lite's output, file it as
`engineAsymmetry` (ceiling 8, currently exactly 8).

The predicate is `liteJson == serialize(deserialize(oracleJson))`. Adjudication required
disassembling every legend jar on the classpath to look for a reader of `ColSpec.multiplicity`:
**zero readers**, so the field really is dead in the published 4.138.2 jars and today's 8 rows are
benign.

Three caveats the test itself cannot express:

- **The test performs none of that verification.** Its predicate says "the engine's deserializer
  discards it"; production hands `parseModel` an **object graph** with no JSON round-trip, so a
  serialize-only field that *were* read in-process would land in the same bucket with the same green.
- **A genuine legend-lite defect satisfies the predicate by construction** — any omission of a
  field the deserializer drops matches exactly. That is what all 8 rows *are*.
- **The 9th non-fixed-point file byte-matches** — there legend-lite *does* emit the multiplicity.
  So the omission is construct-dependent, not a principled policy.

---

## §3 — What the harness is blind to

Perturbations applied to emitted output, one at a time, with application counts proving each fired.

| perturbation | CorpusEquiv | PmcdEquiv | SpiSeam | other 7 tests |
|---|---|---|---|---|
| one char in a string value | RED | RED | RED | blind |
| drop a field / add a field | RED | RED | RED | blind |
| number ±1, `_type` change | RED | RED | RED | blind |
| `sourceInformation` line/column +1 | RED | RED | RED | blind |
| **reorder two adjacent fields** | RED | RED | **GREEN — blind** | blind |
| **truncate element list by one** | **blind** | RED | **blind** | blind |
| **duplicate an element** | **blind** | RED | **blind** | blind |
| **swap two elements' order** | **blind** | RED | **blind** | blind |

Two real holes: the SPI seam round-trips through the engine's Jackson, which re-serialises in
canonical order, so **field reordering is invisible there**; and **document structure — element
count, duplication, order — is guarded by exactly one test**, `PmcdEquivalenceTest`, which is also
the one test with no floor on work done. The other seven gated tests never look at an emitted byte.

**Symmetry holds:** corrupting the oracle's bytes produces the mirror-image result on each gate.

### Breaking the instrument

- **Three comparators hardwired to "equal" → 13/13 green, exit 0.** The only numeric trace was
  SPI's identical-file count *rising* 4,481 → 4,489, and no ratchet can see an increase.
- **Comparators blinded + 93,168 `sourceInformation` corruptions → only `ViewFilterParityTest`
  goes red** — a hand-written 3-case probe that exists for an unrelated reason.
- **Relabelling DIFF as OUT_OF_SCOPE hid all 28,757 divergences.** `OUT_OF_SCOPE` and `LITE_EXTRA`
  have no assertion of any kind; only a hardcoded `compared > 0` caught it, and only because
  *every* row was mislabelled. Any partial mislabel is silent.

### Starved corpus

Empty corpus: `CorpusEquivalenceTest` and `SectionParseSentinelTest` `Assumptions`-skip;
**`PmcdEquivalenceTest` reports "0 match, 0 diff" and PASSES**; `StrictDialectParityTest` and
`LeniencyCatalogTest` pass with zero rows. Only `SpiSeamProofTest`'s floor fails.

**Three gated tests have no did-work floor at all — including the sole guard for document structure.**

### The largest silent regressions, demonstrated

- **Uniform corpus loss of 9.1%** (734 files, 2,467 elements, 554 documents) → **all 13 green**.
  It goes red at 14.3%; the binding ratchet passed by a margin of 11 files.
- **137 injected drop-in defects → all 13 green.** `SectionParseSentinelTest`'s "engine accepts,
  we refuse" count went **1 → 138** and nothing turned red: `MAX_DROP_IN_DEFECTS = 184` sits
  **183 above reality**. `CorpusEquivalenceTest` and `PmcdEquivalenceTest` were bit-identical
  throughout — they do not use `parseStrict`.

---

## §4 — The subject problem

**Production calls exactly one API: `ElementParser.parse` (lenient)** — from `Compiler:70,146`,
`DiagramService:67`, `ConnectionResolver:32`, `PureLspServer:146`.

- **`ElementParser.parseStrict` has zero callers in `src/main`.** (Four grep hits are a javadoc
  mention, the declaration, and `parseStrictTime` — an unrelated method.) Four gated tests build
  their entire claim on it.
- **`PmcdParser.parseDocument` also has zero callers in `src/main`** — production-*shaped*, but on
  no shipping path.

Measured over the whole corpus:

```
accepted:  ElementParser.parse       7,498   <- ships
           ElementParser.parseStrict 6,310   <- 4 gates measure this
           PmcdParser.parseDocument  6,406

LENIENT (oracle refuses, we accept):
           parse 1,472 (24% of corpus)   parseStrict 284   parseDocument 373
```

**The shipping parser is 5.2× as lenient as the surface the rejection gates measure.** And
`RejectionParityTest` reports 42/43 pins passing under `parseStrict` — while `ElementParser.parse`
rejects only **37**. Production accepts 5 of the 42 inputs the engine refuses.

### A third implementation, with a live bug

`LegendLiteSectionParser` is in `src/test`, package-private, registers **one** section, and the
module jar contains **no classes at all** (7 entries, all `META-INF`). It drives legend-lite a
third distinct way — its own site discovery, its own import loop:

```
import a::'b c'::*;
  vanilla  "imports":["a::b c"]
  SPI      "imports":["a::'b c'"]      DIFF at byte 873
```

`PmcdParser` gets this right because it calls `Protocol.unquotePath`; the bridge re-implemented the
loop and lost the unquoting. `SpiSeamProofTest` asserts zero diffs and passes **only because no
corpus file contains a quoted import segment.**

### Harness-vs-production divergences that survive at HEAD

- **`legendStrict` for Mapping / Data / Diagram**: `ParserEquivalence:199,205,315` call the lenient
  overloads; `PmcdParser:430,436,327` pass `true`. Demonstrated: `allVersionsInRange` inside
  `###Mapping` — harness accepts, production refuses.
- **Element ordering**: production applies grammar-rule ordering; the harness has none. Because it
  pairs by FQN, **the element ledger is structurally order-blind**.
- **`DeephavenApp`**: rejected by `parse` and `parseStrict`, accepted by `parseDocument`, never
  scanned by the harness. Three surfaces, three answers, one input.
- **Section discovery**: production uses the lexer's comment-aware header records; the harness uses
  `(?m)^###(\w+)`. A `###Pure` inside a block comment — **the engine splits and rejects;
  `PmcdParser` accepts.** Neither gate catches it.

---

## §5 — Denominators

Substantially honest — exclusion is named and counted almost everywhere, `REFERENCE_REJECTED` is a
verdict rather than silence, and the drain makes the element comparison bidirectional. Three
exceptions:

- **The source count is inflated 6.6%** — 8,067 reported, **7,534 distinct**; 497 rows in the newest
  `engine-grammar-fixtures` tier duplicate inline snippets verbatim.
- **"coverage: 100.0% of comparable" sits above a denominator omitting 51.14% of corpus
  *characters*.** 74.79% of files but only 48.86% of characters — rejected files average **3.1×
  larger**. The report prints file counts and never character mass.
- `EngineElementRosterTest` bills itself as the element denominator while running on 31 of 36
  protocol providers.

**Selection that functions as compensation:** `InlineSnippets.PURE_DECL` rejects **85.5%** of
candidate runs, including **1,819 that carry a `###Section` header** — structurally unmatchable,
since a section fixture *begins* `###X`. The project applies a wider pattern (`OWN_DECL`) to its
own fixtures, which would admit 1,343 of those rejects. The corpus pattern carries a comment
freezing it *because the ledger totals depend on it*. That is selection tuned to the result — and
it is why the `Elasticsearch7ClusterConnection` gap is invisible: 11 rejected inline runs, 0
admitted.

**A genuine strength:** the section-grammar oracle is **complete** — 25 shipped, 25 on the
classpath, set-difference empty in both directions. (The "33 jars" figure was wrong; it is 25.)
The relational sub-extensions are 8 of 12, but the four missing cost **0 sources** today — because
their only fixtures live in exactly the Java literals the extractor rejects. Two exclusions that
happen to align.

**Nothing pins the upstream revision.** The SHAs live only in the CI workflow. With both roots
absent, `PmcdEquivalenceTest` does not skip — it *runs*, on 774 rows instead of 6,033, reports
"0 diff", and succeeds. (`tools/allgates.sh` now checks the roots up front; the underlying floors
are still missing.)

---

## §6 — Fix list

Ordered by value per unit effort.

| # | Fix | Closes |
|---|---|---|
| 1 | **A comparator self-test** — feed each comparator a known-different pair and assert it reports a DIFF | §0.2 — the worst finding. The instrument currently cannot tell you it has stopped working. |
| 2 | **`MIN_DOCUMENTS_COMPARED` on `PmcdEquivalenceTest`**, plus row floors on `StrictDialectParityTest` and `LeniencyCatalogTest` | §3 — three gated tests have no did-work floor, including the sole document-structure guard |
| 3 | **Assert `LITE_EXTRA` and `OUT_OF_SCOPE`**; bound `legalRefusals`, `bothReject`, `skippedNonPure` | §2, §3 — unasserted buckets absorb anything routed into them |
| 4 | **Ratchet every ceiling to its measured value** — `MAX_DROP_IN_DEFECTS` 184 → ~5, `MAX_WALLS` 108 → 0, `MAX_PARSE_FAILS` 14 → 0, `MAX_LITE_MISSED` 19 → 0, `MIN_MATCHES` → 28,757 | §3 — 137 injected defects passed green |
| 5 | **Point the strict gates at a surface with a `src/main` caller**, or give `parseStrict`/`parseDocument` one | §4 — the largest structural finding |
| 6 | **Delete the `legendStrict=false` overloads from `ParserEquivalence`** (3 call sites) | §4 — the instrument is more permissive than the thing measured |
| 7 | **Make the SPI bridge call `Protocol.unquotePath`**; delete its hand-rolled import loop | §4 — a live byte bug |
| 8 | **Remove the message-prefix pardon** in `PmcdEquivalenceTest:84-89`, or make it construct-specific | §2.1 |
| 9 | **Fix `classify()`** — an `InputMismatchException` with a real offending token is a grammar refusal, not an oracle defect; re-adjudicate the 346 | §2.2 |
| 10 | **Have the harness delegate site enumeration to `PmcdParser`** instead of reimplementing ~48% of it | §4 |

---

## §7 — What this does not say

The element-content comparison is sound and I would defend it: every byte-level corruption was
caught, twice, symmetrically, with exact JSON paths. `PmcdEquivalenceTest` in particular is the
real thing — whole-document, byte-exact, including `SectionIndex` and element order, with an
inverse net that requires the **actual shipping** `ElementParser.parse` to read every
oracle-accepted source. `OracleParses` memoisation is honest. The classpath quarantine fencing
engine test-jars into a separate profile is the strongest hygiene in the module. The corpus is fed
unmodified to both sides.

The findings above are about where that instrument is blind — the 25.2% the oracle rejects, the
code paths the harnesses do not share, and the fact that nothing checks the checker.
