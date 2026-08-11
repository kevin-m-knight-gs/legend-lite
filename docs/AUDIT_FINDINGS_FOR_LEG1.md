# Findings for the Leg-1 worklist — a mechanical shortcut, and a blind spot it will not close

Written 2026-08-10 by the parallel audit session, for whoever is executing
`DEEP_AUDIT_HANDOFF.md`. Two things worth having before you start hand-adjudicating
rows, and one correction to a headline number.

Everything below was executed. Artefacts are in the audit's scratch dirs; the recipes
are reproducible from this document.

---

## 1. A second oracle already sits in `~/.m2` and retires most of Leg 1 mechanically

**`legend-pure-m3-core:5.92.0`'s `M3Parser`.** It has no `###` concept, so it adjudicates
only the section-free rows — but that is **1,603 of the 1,960**.

Calibration first, because an oracle you have not calibrated is a rumour: it accepts
**3,352 of 3,388 (98.9%)** engine-accepted section-free files.

Run against the 1,960:

| outcome | n | what it means for Leg 1 |
|---|---:|---|
| **both grammars accept** | **1,174** | corroborated — the construct exists in a reference grammar. **This is most of your by-hand worklist, derived in ~40 seconds.** |
| **we refuse, legend-pure accepts** | **271** | construct GAPS — a different worklist, and arguably the more interesting one |
| **lenient beyond both grammars** | **2** | the actual "did we invent something?" answer |

The 271 gaps group cleanly: 35 `ISLAND_OPEN` in type position, 25 `<...|...>`
type-and-multiplicity args, 18 `Primitive X(...)`, 17 `Measure` bodies, 16 top-level
`^Instance`, 12 `#Person{}` tree paths.

**It independently confirms your own caution.** The handoff flags `TestDefaultValue.java#41`
as a provisional VERSION-SKEW label; M3 adjudication puts it among the **2** rows no
reference grammar accepts. Your instinct was right and this proves it without a bisect.

Cost: one test class, one test-scope dependency, ~40s. Compare with hand-bisecting
345 rows.

### The other options, costed

| option | cost | converts | notes |
|---|---|---:|---|
| **A. legend-pure M3 as second oracle** | 1 class + 1 dep, ~40s | **1,603** | above |
| B. strict whole-file reject parity over all 1,960 | extend the existing test | 1,960 | fails on 294 rows (269 ORACLE-DEFECT + 25 VERSION-SKEW) — i.e. it *derives* Leg 1 mechanically |
| C. error-position parity where both reject | ~20 lines | 1,666 | 19% exact column, 471 line-diverge |
| D. better snippet extraction | days | ~35 | **low yield — mis-extraction is only ~2%; the tolerant design holds.** Do not spend days here |
| E. newer engine as second oracle | needs network | 25 | you already doubt 13 of the 25 |

**Residue after all of it:** 105 `###`-sectioned rows where the strict surface accepts and
no oracle can adjudicate.

---

## 2. The blind spot none of those options closes — and the instrument that would

JaCoCo over `core/target/classes`, two runs: the 5,259 oracle-accepted sources versus
the 1,960.

**333 lines and 30 methods are reachable only via the 1,960.** Crossed with the core unit
suite, **7 methods / 143 lines are touched by nothing at all**:

```
lexer/Lexer#grow()                                    token-array doubling
parser/ElementParser#primitiveElement()               Primitive X extends Y
model/PrimitiveExtensionDefinition#<init>
parser/SpecParser#parseTildeCommandColSpec()          ~cmd:lambda:lambda ColSpec
parser/section/MongoDBSectionGrammar#parseCollection  validationLevel/Action/jsonSchema
parser/section/DiagramSectionGrammar$Raw#skipBalanced quoted-string handling
parser/section/DiagramSectionGrammar$Raw#fail
```

A mutation study against real gate runs (one single-point fault at a time, `-am`), now
**complete at 7 mutations**:

| mutation | code | verdict |
|---|---|---|
| remove `allVersionsInRange` keyword | unit-covered | **CAUGHT** — gate 1 + gate 8 |
| swap `allVersionsInRange` range endpoints | unit-covered | **CAUGHT** — gate 1 only |
| function-type return multiplicity forced to `[1]` | unit-covered | **CAUGHT** — gate 1 |
| `Primitive X extends Y` parsed backwards | **dark** | **SURVIVED everything** |
| `Lexer#grow` off-by-one (drops last end offset) | **dark** | **SURVIVED everything** |
| Mongo `validationLevel`/`Action` swapped | **dark** | **SURVIVED everything** |
| ColSpec lambda slots swapped | **dark** | **SURVIVED everything** — incl. all three newly-gated parity tests |

**7 for 7 predicted by coverage class alone**, zero exceptions. Every fault in a method
some unit test happens to touch was caught by **gate 1, and by gate 1 only**. Every fault
in the 7 dark methods survived the entire chain.

**Sharper still: no differential gate caught a single mutation in this study — not even
the three that were caught.** Byte parity reported `MATCH 26168 / DIFF 0 /
REFERENCE_REJECTED 1960`, unchanged, under all seven. Gate 8's one catch (removing the
`allVersionsInRange` keyword) came through `SpiSeamProofTest`'s aggregate leniency
ratchet — **a count crossing a threshold, not a byte comparison**. On the 1,960 the
differential machinery contributes nothing; unit tests are the entire defence.

That settles the mechanism with no ambiguity: on the 1,960, **the only thing between a
value-corrupting parser fault and production is whether someone wrote a unit test asserting
that specific field.** The byte gate contributes nothing there, by construction.
`SpecParserTest.classAllVersionsInRange` — not the corpus, not the oracle — is what
protects milestoning ranges today.

Note what this does to the known survivor story. Removing the `allVersionsInRange` keyword
**is** caught today — but by `SpiSeamProofTest`'s aggregate ratchet (`leniency census grew:
243 > 212`), which is one-directional and sees a **count, not a wrong answer**. The dark
mutations change no count. So the survivor class was never "keyword removal"; it is
**value corruption in dark code**, and that class is entirely alive.

**No accept/reject oracle closes this.** A, B, C and E all adjudicate *whether* a file
parses, not *what it parses to*. Two instruments would:

1. **Unit tests on those 7 methods.** Cheap, narrow, immediate.
2. **A canonical-text round-trip — `compose(parse(T)) == T`.** This catches the
   `Primitive X extends Y` swap directly, and it generalises: any value corruption that
   survives a byte-identical *parse* shows up when you render back.

That second one is worth noting in the merge conversation too. The composer has so far been
discussed as an upstream *contribution requirement* (`PureGrammarComposerExtension`, 12
methods, 19 implementors upstream, 0 here). It is also **the missing correctness
instrument** — the only thing that would have caught four of the six mutations above.

---

## 3. Corrections to two numbers in circulation

**"8,186 whole documents byte-identical" double-counts.** `PmcdEquivalenceTest.java:34-37`
calls `Corpus.all()` — which already includes inline snippets — and then re-adds
`InlineSnippets.extract` for both roots. 5,259 distinct accepted sources + 2,927 accepted
inline rows counted twice = 8,186 exactly. **The distinct figure is 5,259**, and the parity
is genuinely perfect on it: 5,259/5,259, zero diffs, zero refusals. Two independent agents
reproduced this. Restate the headline; the result underneath it survives.

**The 1,459-row catalog is not the 1,960.** **501 rows are in neither catalog** —
`LeniencyCatalogTest` skips them as *"we refuse too — not a leniency row"*. Nothing compares
them, not even error position. An independent replication of `classify()` reproduces the
742/682/25/10 split exactly, so the 1,459 denominator is right; it simply is not the whole
population. **142 of those 501 get a full PMCD document out of `PmcdParser.parseDocument`
while both element surfaces refuse and the engine errors.**

---

## 4. The finding that outranks all of the above

**Production runs the lenient surface. The reject-parity proof covers a path users never
execute.**

- `LegendHttpServer` routes `/engine/execute`, `/engine/sql`, `/engine/diagram`, `/lsp` →
  `Compiler.execute`/`plan` → `Compiler.compileModel` → **`ElementParser.parse`**, which
  leaves `legendStrict = false`.
- `legendStrict = true` is set in exactly two places — `ElementParser.at` (reached only by
  `PmcdParser` and the harness) and `ElementParser.parseStrict` (harness only). **No
  shipping route reaches either.**
- `StrictDialectParityTest` is *structurally* guaranteed to test the non-shipping surface:
  it requires `ElementParser.parse` to accept before it classifies a row as DIALECT, then
  asserts `parseStrict` refuses. All 742 rows are, by construction, files the shipping
  parser accepts.

Through the shipping API:

| input | engine | `Compiler.compileModel` (SHIPS) | `parseStrict` (GATED) |
|---|---|---|---|
| `Class my::Box<T> {...}` | REJECT | **ACCEPT** | REJECT |
| `.allVersionsInRange(...)` | REJECT | **ACCEPT** | REJECT |
| `Primitive my::PosInt extends Integer` | REJECT | **ACCEPT** | **ACCEPT** |

**843 of the 1,960 compile end-to-end through `Compiler.compileModel` to a
`PureModelContext`.** All three constructs above are genuine legend-pure grammar (`M3Parser`
has a `primitiveDefinition()` rule), so this is a real dialect and not invented syntax —
which is the good news. The bad news is that "drop-in replacement" is true of `PmcdParser`
and false of the compiler, and the quarantine work proves a property of the wrong one.

---

## 5. What we changed while auditing

`tools/allgates.sh` did not run `PmcdEquivalenceTest`, `StrictDialectParityTest`,
`LeniencyCatalogTest` or `ViewFilterParityTest` — the tests pinning this programme's
flagship claims. All four are now in gate 8 (`77babb32`, `fae8b55c`), verified passing
first, with the ~100s budget impact recorded in `docs/GATES.md` as an explicit decision.

**One measurement warning, learned expensively.** A first timing put
`StrictDialectParityTest` at 722s and nearly recorded it as unaffordable. It was a
slept/preempted run — the failure mode `GATES.md` already documents. Under `caffeinate -dims`
it is **34s, 21× faster**. Never time a gate on this machine without `caffeinate`, and treat
an outlier as suspect before treating it as data.
