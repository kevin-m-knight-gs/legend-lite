# Parser audit — every dimension of disagreement with legend-engine and legend-pure

> **Method: code and live oracles only.** Nothing here is read from a doc, a
> comment, or a committed TSV. Every number is produced by running legend-lite's
> parser and the **real** `PureGrammarParser` (legend-engine's ANTLR front-end)
> over the same input and diffing the outcome.
>
> Run at `172fd472` — i.e. **after** the invention-audit execution commit, which
> deleted the `Bool`/`traverse` machinery, added `NativeCatalogGovernanceTest`,
> and tightened the drop-in surface. Gate 8 re-run from source: GREEN, and
> `parseStrict lenient` has fallen **181 → 22**.
>
> Corpus: **3,180 `.pure` files** across the legend-engine checkout
> (`943d38b3dc2`), plus 5,622 mutants generated from real corpus seeds.

---

## 0. The oracle is not clean — read this before any other number

**301 of the 3,180 corpus files (9.5%) crash the real engine's parser** with an
uncaught `NullPointerException`, surfaced as *"An exception of type
'NullPointerException' occurred, please notify developer"*.

legend-lite returns a **named diagnostic** on 300 of those 301:

| n | legend-lite says | engine |
|---:|---|---|
| 267 | `copy-with-update (^$var) is not supported` | NPE |
| 12 | `The type {ObjectField[1],String[1],Map<Any,Any>[1],DebugContext[1]->Node[*]} is not supported yet` | NPE |
| 21 | eight further named type/lambda walls | NPE |

Every comparison below has to be read against this. "The engine rejects X" is
sometimes "the engine crashed on X", which is not a verdict and cannot be
parity-matched. It is also an **upstream defect worth reporting to FINOS**: the
engine's grammar NPEs on 9.5% of its own `.pure` sources.

---

## 1. Dimensions, and where each stands

| # | dimension | question | result |
|---|---|---|---|
| D1 | **acceptance** | do we accept what the engine rejects? | **17 corpus files**, 2 causes |
| D2 | **rejection** | do we reject what the engine accepts? | **0 corpus files**, 6 mutants |
| D3 | **wire/byte** | same text → same protocol JSON? | **6,489 / 6,489 byte-match, 0 diff** |
| D4 | **messages** | same rejection → same message? | 878 shared; **368 identical, 510 differ** |
| D5 | **tiers** | how far apart are lite's three dialects? | PLATFORM 3,144 → LITE 2,981 → ENGINE 2,302 |
| D6 | **pure vs engine** | where the two upstreams disagree, whom do we follow? | pure at PLATFORM, engine at ENGINE — with 2 leaks |

---

## 2. D1 — acceptance drift: 17 files, two legend-pure constructs

`LEGEND_ENGINE` accepts 2,302 corpus files; the real engine accepts 2,285. The
17-file gap has exactly two real causes plus one artefact.

| cause | files | what it is |
|---|---:|---|
| **`#TDS … #` literal** | ~6 | `tdsEquivalent.pure`, `testModelJoinSimple/Advanced/Milestoning.pure`, `dataquality_relation_helper_test.pure`, `testQueryToGraphFetch.pure` |
| **`Primitive X extends Y`** | ~6 | `unitMeasure.pure`, `shared.pure`, `simple.pure`, `milestoningModel.pure` — engine: `Unexpected token 'Primitive'. Valid alternatives: ['Class','Association','Profile','Enum','Measure','function','native','^']` |
| stray top-level `)` | 1 | `m2m2rExecutionPlanTests.pure` — §5 |
| engine NPE, not our drift | 1 | `validation_rules_test_service_tds.pure` |

Both real causes are **legend-pure constructs leaking past the platform gate**.
`Dialect`'s own javadoc names `#TDS` as `LEGEND_PLATFORM`-only; `Primitive` is a
legend-pure primitive-subtype declaration the engine grammar has no alternative
for. `refusesPlatformDialect()` is called at 26 sites and simply is not called at
these two parse sites.

Confirmed by direct tier probe — three constructs still leak into the drop-in
tier, while four others are gated correctly:

| construct | PLATFORM | ENGINE tier | real engine | |
|---|---|---|---|---|
| `#TDS … #` | OK | **OK** | no | leaks |
| `^$x(...)` | OK | **OK** | no | leaks |
| `%latest` | OK | **OK** | no | leaks |
| `native function`, generics `<T>`, function-type literal, `Relation<T+R>` | OK | no | no | gated |

---

## 3. D2 — rejection drift: the strongest result in the audit

**Zero.** Over all 3,180 corpus files there is not one case where legend-lite
rejects a file the real engine accepts. For a drop-in surface this is the
property that actually matters — a parser that refuses valid Legend is unusable,
and legend-lite never does.

Mutation fuzzing found **6** over-strict rows out of 5,622 mutants, all on
malformed input, so none affects real code:

| mutation | n | seed |
|---|---:|---|
| `delete ';'` | 3 | `measure/grammar.pure` |
| `double '-'` | 2 | `valueSpecification/primitives/grammar.pure` |
| `delete '}'` | 1 | `connection/h2Connection.pure` |

Worth fixing for exactness, but they are the least urgent finding here: being
stricter than the engine on garbage input costs nothing in practice.

---

## 4. D3 — wire parity: clean

Gate 8, regenerated from source at this HEAD:

```
oracle accepts        : 6489
  docs byte-MATCH     : 6489
  docs DIFF (BUG)     : 0
  we-refuse (BUG)     : 0
oracle rejects        : 2402 (both-reject 2368)
REJECT_MATCH          : 423     REJECT_MISS (BUG) : 0
files byte-identical  : 6480    engine JSON-asymmetry : 9
```

Every document the oracle accepts produces byte-identical protocol JSON. This
dimension needs no work and is the best-governed part of the parser.

---

## 5. D4 — message parity: one systematic divergence, one place we are better, one worse

878 files are rejected by both. Comparing the first line of each message:

| | n |
|---|---:|
| message text **identical** after stripping the position prefix | **368** |
| message text **genuinely different** | **510** |
| legend-lite emits a `[line:col]` prefix | **878 / 878** |
| the engine emits a `[line:col]` prefix | **0 / 878** |

**The prefix is a systematic, 100% divergence.** Every legend-lite parse error
opens with `[4:139] `; no engine error does. If message parity is part of the
drop-in claim, this breaks it on every single error. If it is not, the claim
should say so. `MessageParityTest` passes, so it is comparing after normalisation
— which means the prefix has never been adjudicated as a decision.

Of the 510 genuine divergences:

| n | legend-lite | engine | verdict |
|---:|---|---|---|
| 267 | `copy-with-update (^$var) is not supported` | NPE | **we are better** — engine crashes |
| 68 | `Unsupported syntax` | `Unexpected token 'X'. Valid alternatives: [23 listed]` | **we are worse** — no alternatives offered |
| 48 | `Unexpected token 'A'` | `Unexpected token 'B'` | different token/position picked |
| 36 | `Type and/or multiplicity parameters are not authorized in Legend Engine` | `Unexpected token` | we give the engine's *own* better message where the engine does not reach it |
| 15 | reports the **inner** function type | reports the **outer** function type | we truncate the signature |
| ~20 | assorted named walls | NPE | we are better |
| 8 | shape-specific | shape-specific | one-offs |

Two actionable rows: the 68 `Unsupported syntax` cases give a user nothing to act
on, and the 15 truncated type signatures name the wrong type.

---

## 6. D5 — the tier ladder, measured on real Legend code

| surface | corpus files accepted | of 3,180 |
|---|---:|---:|
| `LEGEND_PLATFORM` | 3,144 | 98.9% |
| `LEGEND_LITE` | 2,981 | 93.7% |
| `LEGEND_ENGINE` | 2,302 | 72.4% |
| **real engine** | **2,285** | **71.9%** |

Monotonic, as designed. Two observations the own-corpus census cannot show:

- **`LEGEND_LITE` accepts 679 corpus files the drop-in tier refuses.** The
  declared extensions are not a rounding error on real Legend code — they are a
  30-percentage-point surface. `OwnCorpusConformanceTest` measures this at 65
  snippets *in legend-lite's own tests*; on the real corpus the reach is 679
  files. Both numbers are true; only the second describes what a user gets.
- **`LEGEND_PLATFORM` reads 3,144 of 3,180** — legend-lite can parse 98.9% of
  legend-engine's own `.pure` sources, including the 301 the engine itself
  crashes on. That is the strongest single statement about this parser.

---

## 7. D6 — where legend-pure and legend-engine disagree

These are not lite inventions; they are two upstreams that contradict each other,
and legend-lite has to pick per tier.

| construct | legend-pure | legend-engine grammar | lite PLATFORM | lite ENGINE |
|---|---|---|---|---|
| `Primitive X extends Y` | yes | **no** | yes | **yes — leak** |
| `#TDS … #` | yes | **no** | yes | **yes — leak** |
| `^$x(...)` copy-with-update | yes | **no** (NPEs) | yes | **yes — leak** |
| `%latest` | yes | **no** | yes | **yes — leak** |
| generics `<T>` on functions | yes | **no** (*"not authorized in Legend Engine"*) | yes | no ✓ |
| function-type literals | yes | **no** | yes | no ✓ |
| `Relation<T+R>` column algebra | yes | **no** | yes | no ✓ |
| `native function` declaration | yes | **no** | yes | no ✓ |

The policy is right — follow pure at PLATFORM, the engine at ENGINE. Four
constructs implement it; four do not. That is the whole of D1 and most of D4's
"we are better" column.

---

## 8. What is actually wrong, ranked

| # | finding | evidence | size |
|---|---|---|---|
| 1 | `#TDS`, `Primitive`, `^$x(...)`, `%latest` bypass `refusesPlatformDialect()` | D1 = 17 files; tier probe | S |
| 2 | The stray-`)` skip (`ElementParser.java:532-538`) is justified by a **false premise** — the engine *rejects* the file it cites | §5 of the invention audit; re-verified | XS |
| 3 | Every error carries a `[line:col]` prefix the engine never emits — 878/878 | D4 | XS, but it is a decision, not a bug |
| 4 | 68 rejections say only `Unsupported syntax` where the engine lists valid alternatives | D4 | M |
| 5 | 15 type-signature messages report the inner type, not the outer | D4 | S |
| 6 | 6 over-strict mutants (`;` in Measure, `--`, `}` in connection) | D2 | S |
| 7 | No gate compares the ENGINE tier to the real oracle on **synthetic** input — every existing gate runs only over text that already exists in a corpus | D1/D2 method | S |

**Item 7 is the meta-finding.** `RejectionParityTest`, `CorpusSweepTest` and
`MessageParityTest` all sweep real files. Nothing generates input. Every defect
in this audit that a corpus sweep could not see was found by mutation, and the
mutation harness is ~60 lines. Landing it closes the class.

## 9. What is right, and should be said

- **D2 = 0 on 3,180 files.** legend-lite never refuses valid Legend.
- **D3 = 0 diffs on 6,489 documents.** Wire parity is exact.
- **98.9% of legend-engine's own sources parse**, including 301 files the engine
  crashes on.
- The three-tier design is sound and the ladder is monotonic; the defects are
  four unguarded parse sites, not a design problem.
- The invention-audit execution moved `parseStrict lenient` from **181 to 22**.

---

## 10. Reproducing

Harness in `docs/parser-audit-2026-08-14/probes/`. Build the classpath as in
`docs/invention-audit-2026-08-14/README.md`, then:

| class | dimension |
|---|---|
| `Dim` | D1 + D2 + D4 over every corpus file |
| `Msg` | D4 with the position prefix stripped — the honest message comparison |
| `Npe` | the 301 engine crashes and what legend-lite says instead |
| `Tier` | D5, the four-surface ladder |
| `Mut3` | corpus-seeded mutation fuzz, both directions |

`Dim`'s own first run reported "878 of 878 message divergences" because it
compared legend-lite's `[line:col]`-prefixed message against the engine's
unprefixed one. `Msg` is the corrected comparison; the real split is 368/510.
Both are kept so the error is legible.
