# Harness simplification — implementation plan

**Goal.** The equivalence harness should make no decisions. It runs both parsers on the same
text and compares the results. Every judgment currently in the harness moves to one of three
places: **into the parser** (where it is under test), **into a data file** (where it is
reviewable), or **into a report** (where it is not load-bearing).

**Companion:** [`HARNESS_AUDIT_2026_08.md`](HARNESS_AUDIT_2026_08.md) is the evidence base. This
document is the work list. Every claim below was measured; where a number appears, it came from
running something, not from a doc.

---

## The target shape

```java
record Outcome(String thrown, List<String> elements, String document) {}

// three assertions, and only one of them is about bytes
assertEquals(a.thrown() == null, b.thrown() == null);   // 1. verdict symmetry
assertEquals(a.document(),       b.document());          // 2. the byte claim
// 3. a self-test elsewhere: a known-different pair must report a difference
```

`elements` is carried for **diagnostics only** — on a document failure it localises to an element
index and a JSON path. It is not asserted.

**Why one byte gate and not two.** `ParserEquivalence.referenceElements` iterates
`pmcd.getElements()`, skips the `SectionIndex`, and buckets the rest by FQN.
`PmcdEquivalenceTest` serializes **the same `pmcd` object** — they share it through
`OracleParses`. Same corpus, same skip rule. So the element ledger's universe is a strict subset
of the document's, and byte-equal documents imply byte-equal elements. A 52-mutation study found
**no fault killed by the element gate that the document gate missed**, while **16 of 17 chain
escapes were caught only by the document gate** (element order, duplicated elements, SectionIndex
position, import dedupe, `::*` stripping, rule-group ordering).

Two assertions of the same property are not defence-in-depth. They are two chances to get the
assertion wrong — and in this codebase every laundering path found by the audit lives in the
element ledger, because it is the one with something to classify.

---

## Environment hazards — read before running anything

1. **`mvn -pl parser-equivalence test` resolves `legend-lite-core` from `~/.m2`, not the reactor.**
   Always `-am`, or `mvn -o -pl core install -DskipTests` first. This has already produced one
   phantom regression report in this project.
2. **`/tmp` is shared with another account (`neema`) that also builds this repo.** Fixed paths
   collide silently; you will read someone else's output. Use `mktemp -d`.
3. **Never time anything without `caffeinate -dims`.** A 34s test measured 722s on a slept run and
   was nearly recorded as unaffordable.
4. **Gates 4/5/8 need `~/legend/legend-engine` and `~/legend/legend-pure`.** `tools/allgates.sh`
   now checks this up front; a missing checkout is a failure, not a skip.
5. **The full chain is budgeted at 5.5 minutes** (`docs/GATES.md`). Breaking that ceiling is an
   explicit decision to be argued and recorded there, not absorbed.

---

## Phase 0 — the self-test (independent, do first)

**Why first:** it is the only fix that closes the audit's worst finding, it depends on nothing
else, and it is ~15 lines. With all three comparators hardwired to "equal" the entire chain
reported **13/13 green**; with them blinded *and* 93,168 `sourceInformation` corruptions applied,
only a hand-written 3-case probe went red.

**Do:** add `ComparatorSelfTest` to `parser-equivalence`, in gate 8.

- Feed the document comparator a pair that differs by one byte; assert it reports a difference.
- Feed it an identical pair; assert it does not.
- Do the same for whatever compares element lists, if that path survives Phase 3.

**Acceptance:** the test fails if you replace any comparison with `return true`. Verify that by
actually doing it in a scratch copy.

---

## Phase 1 — unify the lite side

**Why:** the two gates currently run different lite code. `ParserEquivalence:199,205,315` call the
`legendStrict=false` overloads for Mapping, Data and Diagram; `PmcdParser:430,436,327` pass `true`.
Demonstrated divergence: `allVersionsInRange` inside `###Mapping` — the harness accepts, production
refuses. **The measuring instrument is more permissive than the thing measured.**

`ParserEquivalence` also re-implements ~346 lines of `PmcdParser`'s site scanners, dispatch and
lookup tables (57–80% line similarity), with four further surviving divergences: flattened
element ordering, a dropped `DeephavenApp` activator marker, a `_type` string mismatch
(`execEnvironment` vs `executionEnvironmentInstance`), and regex-based section discovery versus
the lexer's comment-aware header records.

**Do:** make `ParserEquivalence` obtain its element list from
`PmcdParser.parseSections(source)` — already `public`, already returning
`List<DocSection>` with `List<DocElement>{path, json}`. Delete the harness-local
`pureSites`, `markerSites`, `textMarkerSites`, `tailSites`, `connectionSites`, `runtimeSites`,
`sectionRanges`, the 12-arm dispatch, and the `TAIL_GRAMMARS`/`TAIL_SECTIONS`/`MARKERS` tables.

**Expect:** the gate stays green. **If it does not, that is a real finding** — it means the two
lite paths disagreed on the corpus and the element ledger was measuring the more lenient one.
Record what diverged before fixing it.

**Acceptance:** `CorpusEquivalenceTest` green with zero harness-local site enumeration remaining;
`grep -c "private static.*Sites" ParserEquivalence.java` returns 0.

---

## Phase 2 — prove the element gate is implied

**Do not skip this.** Set inclusion is an argument; this is the demonstration.

**Do:** with Phase 1 landed, run the full corpus and assert the joint property — **no source
where the document comparison passes and the element comparison fails.** Emit the count.

**Acceptance:** zero such sources across the corpus. Record the number in the commit message.
If it is not zero, stop and investigate: something in the envelope or the element serialization
differs between the two paths and Phase 3 is unsafe.

---

## Phase 3 — demote the element ledger to a diagnostic

**Do:**

- Remove `CorpusEquivalenceTest`'s assertions and ratchets: `MIN_ELEMENTS_COMPARED`,
  `MIN_MATCHES`, `MAX_WALLS`, `MAX_PARSE_FAILS`, `MAX_LITE_MISSED`.
- Remove the verdict kinds that only exist for per-element pairing: `WALL`, `LITE_MISSED`,
  `LITE_EXTRA`, `OUT_OF_SCOPE`, `PARSE_FAIL`, and the drain at `:364-457`.
- Keep the element diff as **output on failure**: when a document comparison fails, print the
  positional element list difference — element index, path, and the first divergent JSON path.
- If you keep any element comparison at all, make it **positional `List.equals`**, not FQN
  pairing. Positional is order- and length-sensitive and therefore strictly stronger: it catches
  truncation, duplication and reordering, which FQN pairing is structurally blind to.

**What this removes, concretely:** eight verdict kinds become two outcomes; the
`LITE_MISSED == 0` assertion that one walled site anywhere in a file can defeat; the `WALL`
bucket with a ceiling of 108 against an actual of 0; and `LITE_EXTRA`/`OUT_OF_SCOPE`, which have
no assertion at all today.

**Acceptance:** `ParserEquivalence` under 200 lines. Gate 8 green. The mutation set from the audit
(or a fresh one) still kills what it killed before — **re-run it; do not assume.**

---

## Phase 4 — verdict symmetry (the 1,960)

**This is the phase with real risk. Read the sequencing note.**

Today `ParserEquivalence:110-116` and `PmcdEquivalenceTest:40-43` both `continue` when the oracle
throws. That is **1,960 of 8,067 sources (25.2%)** on which legend-lite is never run by any byte
gate. It emits a complete PMCD document for **373** of them. A mutation study proved this is not
theoretical: removing `allVersionsInRange` from the keyword table survived all four gates, because
the reference rejects every file containing it.

**The target assertion:** oracle threw ⟺ we threw.

**It will fail on roughly 1,472 files today** — the shipping surface accepts 1,472 sources the
oracle rejects (`parseStrict` 284, `parseDocument` 373). Most are genuine legend-pure dialect, not
defects. **Do not turn this on as a switch.**

**Sequence:**

1. **Report only.** Land it printing a categorised list, asserting nothing. Commit the list as
   `docs/refusal-asymmetry.tsv` — one row per source: id, oracle message, our verdict.
2. **Allowlist as data.** Convert that list into a checked-in allowlist file: one line per source,
   with a **reason**. The assertion becomes "every asymmetry is in the allowlist." This is
   categorically different from `LeniencyCatalogTest.classify()`, which decides at runtime and can
   absorb anything — a file is reviewable and diffable.
3. **Ratchet the file, not a count.** The allowlist may only shrink. Adding a line is a reviewed
   change with a stated reason.
4. **Burn down.** Each removed line is a real parity fix.

**Cheap accelerator, already validated:** `legend-pure-m3-core:5.92.0`'s `M3Parser` is already in
`~/.m2` and adjudicates the 1,603 section-free rows of the 1,960 (calibrated at 98.9% agreement
with the engine on section-free files). It classifies 1,174 as corroborated by a reference
grammar, 271 as gaps where we refuse and legend-pure accepts, and **2 as accepted by no reference
grammar at all**. Use it to populate the allowlist reasons mechanically rather than by hand.
See [`AUDIT_FINDINGS_FOR_LEG1.md`](AUDIT_FINDINGS_FOR_LEG1.md).

**Acceptance:** the assertion is live; the allowlist file exists with a reason per line; a test
asserts the file only shrinks.

---

## Phase 5 — move compensation into the parser

Each of these is the harness supplying behaviour the parser should have.

| # | Site | Move to |
|---|---|---|
| 5a | `LegendLiteSectionParser:92-96` — a hard-coded `throw` on `native` declarations so our accept set matches the engine's | the parser. It is a real parity requirement and belongs where it is tested. |
| 5b | `LegendLiteSectionParser:169-186` — `shiftSpans`, post-hoc JSON rewriting of every `sourceInformation` | pass the section offset **into** the parser so it emits correct coordinates; delete the rewrite. *(The formula itself is correct — it was verified against the engine's bytecode. This is about where it lives.)* |
| 5c | `PmcdEquivalenceTest:84-89` — a two-entry **message-prefix allowlist** inside a bucket asserted to zero, pardoning exactly the construct `SectionParseSentinelTest` counts as the single live drop-in DEFECT | fix the two constructs, or move them into the Phase 4 allowlist file. A prefix match will absorb unrelated future refusals. |
| 5d | `SpiSeamProofTest:91-97` — a failed byte comparison gets a second chance via the oracle's own round-trip, then lands in a bucket capped at 8 (currently exactly 8) | either canonicalise **both** sides through the same function — `f(a) == f(b)` is an equivalence relation, `a == f(b)` is not — or fix the parser. The 9th non-fixed-point file byte-**matches**, so the omission is construct-dependent, not policy. |
| 5e | `ParserEquivalence` import handling — the SPI bridge re-implemented the loop and lost `Protocol.unquotePath` | call `Protocol.unquotePath`. **This is a live byte bug:** `import a::'b c'::*;` emits `["a::'b c'"]` where vanilla emits `["a::b c"]`. The seam proof passes only because no corpus file has a quoted import segment. |

**Acceptance:** `grep -n "startsWith(\"" PmcdEquivalenceTest.java` returns nothing; `shiftSpans`
is deleted; the quoted-import case byte-matches.

---

## Phase 6 — corpus as data

**Why:** `InlineSnippets.PURE_DECL` rejects **85.5%** of candidate runs, including **1,819 that
carry a `###Section` header** — structurally unmatchable, since a section fixture *begins* `###X`.
The project applies a wider pattern (`OWN_DECL`) to its own fixtures, which would admit 1,343 of
those rejects. The corpus pattern carries a comment freezing it **because the ledger totals depend
on it**. That is selection tuned to the result, and it is why the
`Elasticsearch7ClusterConnection` gap is invisible to every gate.

Also: nothing asserts which upstream revision was measured. The SHAs live only in
`.github/workflows/gate.yml`.

**Do:**

- Generate a **manifest**: one row per source — id, SHA-256, tier. Check it in.
- The corpus loader reads the manifest and fails if a file is missing or its hash differs.
- Regenerating the manifest is a reviewed diff, so corpus drift becomes visible.
- Deduplicate: the reported source count is inflated **6.6%** (8,067 reported, **7,534 distinct**)
  because 497 rows in the newest tier duplicate inline snippets verbatim.
- Replace the `catch (Exception) { continue; }` in `Corpus.add` with a counted, reported skip.

**Acceptance:** the manifest exists; changing an upstream checkout without regenerating it fails
the gate; the reported count equals the distinct count.

---

## Phase 7 — move analysis out of the gates

`LeniencyCatalogTest.classify()` files **684 of 1,472 rows (46.5%)** as "the oracle is broken" —
346 of them because ANTLR's `InputMismatchException` carries a null message, which is its
*ordinary* grammar-refusal path. Its only assertion is that every row carries a label, and no
class has a count ceiling, so it is a total sink.

**Do:** make it produce a report. Move the assertion to Phase 4's allowlist, which bounds the
population by construction. Fix the null-message branch: an `InputMismatchException` with a real
offending token is a grammar refusal, not an oracle defect.

---

## What is explicitly NOT in scope

- **Do not delete the element diff output.** Losing "which element, which JSON path" makes every
  future failure harder to diagnose. Demote it; do not remove it.
- **Do not widen `PURE_DECL` and the ratchets in the same commit.** Widening the corpus moves
  every number; do it alone so the delta is attributable.
- **Do not chase the corpus for `M04`-shaped faults.** One mutation — the island end-column losing
  its walker offset — survives everything, and a reachability probe showed the branch **never
  fires across the entire corpus**. It is executed by three unit tests and asserted by none. More
  corpus will never reach it. That needs **fixture-level oracles**: hand-built inputs adjudicated
  live against the engine, in the shape of `ViewFilterParityTest`. Worth doing, separately.

---

## Order, and why

Phases 0–3 are safe and mostly deletion. Phase 4 is the one that changes what the gate asserts,
and it must land as report → allowlist → ratchet, never as a switch. Phases 5–7 are independent of
each other and can be done in any order once 0–3 are in.

**Land each phase on its own commit with the full 8-gate chain green**
(`LEGEND_ENGINE_ROOT=... LEGEND_PURE_ROOT=... caffeinate -dims bash tools/allgates.sh`), and
record the measured numbers in the commit message — not numbers copied from this document, which
will be stale by then.

**The end state:** a harness of roughly 100 lines that reads a manifest, runs two parsers, and
makes three assertions — plus an allowlist file whose every line is a known, reasoned, shrinking
piece of parity debt. Nothing in it decides anything.
