# Own-Corpus Decision Ledger — the 75 pinned leniency rows

**State (2026-08-11, post census batches 1–3):** 949 snippets extracted
from our own test sources; 758 oracle-accepted; 114 both-refuse; **75
rows where the 4.138.2 oracle refuses and lite's lenient surface
accepts** — every one classified, pinned in
`OwnCorpusConformanceTest`, ZERO unclassified. This document lists every
row for an explicit keep/conform decision. Regenerate the row list any
time: run `OwnCorpusConformanceTest` and read
`parser-equivalence/target/own-corpus-conformance.txt`.

**How to read a row id:** `file#N` is the file's N-th extracted
string-literal run (extractor order), not a line number. The construct
column carries a grep-able cue.

**Decision key:** KEEP = deliberate, stays, class stays pinned.
CONFORM = rewrite the test/feature to oracle shape. Every DECISION
cell below is `DECIDED:` (2026-08-11 review — all classes confirmed).

---

## 0. Why the engine reads legend-pure without barfing (context)

The engine has **two entirely separate Pure parsers**, and the oracle
is only one of them:

1. **The user-model grammar** (`PureGrammarParser`, ANTLR
   `CorePureGrammarParser` + section grammars) — parses `###`-sectioned
   Legend text: what Studio/SDLC users write. This is the corpus
   oracle. It is deliberately a RESTRICTED dialect: no `native
   function`, no type/multiplicity parameters, no function-type
   signatures, star-only imports. Refusals like "Type and/or
   multiplicity parameters are not authorized in Legend Engine" are
   the engine NAMING its own subset.
2. **The legend-pure m3/m4 parsers** (`M3Parser.g4` and the m2 store
   grammars in the legend-pure repo) — parse full-dialect `.pure`
   files: the stdlib, the relation contracts, PCT sources.

The engine never feeds `.pure` files through parser #1. At **build
time**, legend-pure sources are compiled by parser #2 into
`legend-engine-pure-code-compiled-core` (verified in the 4.138.2 jar:
30,820 generated `Root_meta_*` Java classes + a 23.7&nbsp;MB
`pure-core.par` serialized graph archive + the `.pure` sources riding
along as inert resources). At **runtime** the engine loads that
precompiled graph — no `.pure` text is ever parsed by the user
grammar. That is why harvesting engine-embedded `.pure` files into the
corpus and feeding them to the oracle produces the ORACLE-DEFECT
crash class: those files target parser #2, and the oracle was never
meant to read them.

**Consequence for lite:** lite has ONE parser that speaks the union of
both dialects (with the strict/`legendStrict` surface reproducing
parser #1's refusals verbatim). Every "pure-dialect" row below is a
construct parser #2 accepts and parser #1 refuses — being ahead of the
oracle there is matching legend-pure, not inventing.

## 0b. `^Class()` in queries — probe verdict (ZCaretQueryProbe)

End-to-end against the real 4.138.2 engine (compile + relational plan
generation on the classpath):

| lambda | engine compile | engine plan |
|---|---|---|
| `\|Person.all()->project(...)` (control) | OK | OK — `RelationalTdsInstantiationExecutionNode` (SQL) |
| `\|^Person(name='x')` | OK | OK — but `PureExpressionPlatformExecutionNode` (in-memory platform eval, never SQL) |
| `^Person(...)` inside `filter` | OK | **REFUSED**: "No SQL translation exists for the PURE function 'new_Class…'" |
| `^Person(...)` inside `project` | OK | **REFUSED**: same |

So the engine does NOT ban `^X()` in queries grammatically or at
compile; its RELATIONAL LOWERING cannot translate instantiation to
SQL. Lite lowers `^X()` to SQL (STRUCT values / typed carriers —
corpus 2447 design). This is a **capability superset at the lowering
level**, not a grammar invention — and it is load-bearing for
mappings-as-functions (clean-sheet bodies are
`...->map(r|^Person(...))`).

- **DECISION:** DECIDED: **KEEP** (2026-08-11 review — required by
  mappings-as-functions; engine's own surface accepts the text and only
  its SQL backend falls short).

---

## 1. DIALECT-function-types — 13 rows

Function-type signatures (`Function<{Integer[1]->Integer[1]}>[1]`,
lambda-typed parameters). Engine message: *"The type {…->…} is not
supported yet"* — the engine names its own gap ("yet"). legend-pure
parses these (function types are core m3). Lite needs them for
higher-order user functions (the `eval`/HigherOrder suites execute
against DuckDB today).

| # | row | construct |
|---|---|---|
| 1 | compiler/TdsLambdaProbeTest#0 | `{->TabularDataSet[1]}` zero-arg fn type |
| 2 | compiler/spec/CompileFunctionTest#5 | `{Integer[1]->Integer[1]}` param |
| 3 | compiler/spec/CompileFunctionTest#7 | same, let-bound lambda |
| 4 | compiler/spec/TypeCheckerTest#294 | `{Integer[1]->Boolean[1]}` predicate param |
| 5 | compiler/spec/UserCallInlinerTest#0 | fn-typed param through the inliner |
| 6–11 | integration/UserFunctionIntegrationTest#68/75/87/92/97/132 | higher-order user fns executed on DuckDB |
| 12–13 | parser/ElementParserTest#51/56 | `{T[1]->U[1]}` generic fn types (parse pins) |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (pure-dialect; engine says "yet";
  executable feature).

## 2. DIALECT-generics — 7 rows

Type/multiplicity parameters on functions/classes
(`function f<T>(…)`, `<T\|m>`). Engine: *"Type and/or multiplicity
parameters are not authorized in Legend Engine"* — again the engine
naming its own restriction; legend-pure is fully generic (the entire
relation-function surface depends on it).

| # | row | construct |
|---|---|---|
| 14–19 | parser/ElementParserTest#72/81/262/266/271/310 | generic fn/class decls (parse pins) |
| 20 | rcorpus/RelationalCorpusRunner#6 | `meta::pure::tds::extensions::firstNotNull<T>` — a REAL engine-checkout helper the corpus harness registers |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP**.

## 3. DIALECT-milestoning-range — 1 row

| # | row | construct |
|---|---|---|
| 21 | compiler/spec/UserCallInlinerTest#34 | `.allVersionsInRange($s,$e)` — engine: "is not supported"; legend-pure milestoning has it |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP**.

## 4. ENGINE-TEST-SCOPED-section — 1 row

| # | row | construct |
|---|---|---|
| 22 | parser/SectionGrammarRegistryTest#14 | `###Toy` — a fixture section for the registry SPI; oracle: "'Toy' is not a known section parser" (same refusal a production engine gives) |

- **DECISION:** DECIDED: **KEEP** (2026-08-11 review). Strict already
  fails engine-verbatim; the lenient skip is corpus-load-bearing
  ("refusing cost the relational corpus its whole library layer").

## 5. LENIENT-TIER-fixture — 1 row

| # | row | construct |
|---|---|---|
| 23 | parser/ElementParserTest#440 | `Service` without `pattern:` — pins lite's lenient default (`pattern -> "/"`). The STRICT surface refuses engine-verbatim ("Field 'pattern' is required") since census batch 1. |

- **DECISION:** DECIDED: **CONFORM** (2026-08-11 review) — the lenient
  `pattern -> "/"` default is REMOVED; `pattern` and `documentation`
  are grammar-required on BOTH surfaces (engine-verbatim messages); the
  fixture now pins the refusal. This row leaves the ledger.

## 6. LITE-DESIGN-inline-association — 2 rows

Clean-sheet inline association predicate:
`Assoc: AssociationMapping { {p, f \| $p.x == $f.y} }`.

| # | row |
|---|---|
| 24 | normalizer/MappingNormalizerTest#51 |
| 25 | parser/ElementParserTest#565 |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (part of the mappings-as-functions
  design family — M5 in MAPPING_CLEAN_SHEET).

## 7. LITE-DESIGN-json-column-get — 11 rows

`COLUMN->get('key'[, @Type])` in relational property mappings — the
lite construct binding a JSON/variant column into a class property.
The engine's semistructured mapping spelling is different
(`meta::pure::mapping::modelToModel`-side / semi-structured
flatten+extract with Binding). **This is the one class you never
explicitly blessed** (it was a Leg-3 hunt target).

| # | row |
|---|---|
| 26–33 | integration/JsonMappingIntegrationTest#36/49/61/69/80/96/113/126 |
| 34 | integration/VariantIntegrationTest#57 |
| 35–36 | protocol/RelationalTypeRefEmissionTest#0/7 |

- **DECISION:** DECIDED: **KEEP for now** (2026-08-11 review). The
  engine-parity path is the BINDING TRANSFORMER
  (`prop: Binding my::B : [db] T.COL` — RelationalParserGrammar
  `transformer: enumTransformer | bindingTransformer`), which requires
  external-format Binding machinery: a named FUTURE leg. See the
  Binding parity gap below.

## 8. LITE-DESIGN-mapping-as-function — 20 rows

The standing design exception: mapping bodies as FUNCTIONS —
`X: Relational { acme::funcs::personMapping }` (function ref),
`Pure { RawPerson.all()->map(src\|^Person(...)) }` (inline pipeline),
`#>{db.T}#->map(r\|^Person(...))` (relation-source pipeline), and the
fn-ref service query (`query: my::funcs::peopleQuery;`).

| # | row | shape |
|---|---|---|
| 37–39 | compiler/element/PureModelContextTest#53/58/69 | fn-ref bodies (incl. negative fixtures: missing fn, not-a-class) |
| 40–43 | normalizer/LegacyCleanSheetConvergenceTest#14/20/26/32 | inline pipelines, `#>{}#` sources, assoc predicate |
| 44–46 | normalizer/MappingNormalizerTest#34/41/63 | `Pure { fnRef }`, `Relational { Class.all() }` |
| 47 | normalizer/ModelNormalizerTest#101 | service `query:` as fn ref |
| 48–53 | parser/CleanSheetProtocolShapeTest#2/5/8/11/14/18 | the protocol-shape pins for every clean-sheet form |
| 54–56 | parser/ElementParserTest#548/559/563 | parse pins |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (your named exception).

## 9. LITE-DESIGN-sqlite-backend — 2 rows

| # | row | construct |
|---|---|---|
| 57–58 | integration/SQLiteIntegrationTest#46/58 | `type: SQLite` connections — a lite backend the engine's DatabaseType enum lacks (BACKEND_PORTABILITY) |

- **DECISION:** DECIDED: **KEEP**, shape-aligned (2026-08-11 review):
  the spec island is now `specification: SQLite { };` — spelled EXACTLY
  like the engine's DuckDB extension pattern (`SQLite { (path:'...')* }`,
  `PSQLiteSpec`), so a future engine SQLite extension finds our text
  conformant. The emitter refuses it loudly (no engine wire shape).

## 10. PURE-DIALECT-diagram — 1 row

| # | row | construct |
|---|---|---|
| 59 | lexer/LexerTest#40 | `Diagram my::D(width=1.0) { TypeView v(color=#FFFFCC) }` — legend-pure m2 diagram spelling (geometry attrs; `DiagramAntlrParser.g4` widthFirst/heightFirst). A LEXER fixture: the `#FFFFCC` is deliberately unlexable content proving raw-section skipping. |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (fixture needs foreign content; the
  spelling is real pure anyway).

## 11. PURE-DIALECT-signatures — 16 rows

The relation-contract signature dialect, ported VERBATIM from
legend-pure `.pure` sources (see
`verify-signatures-against-real-legend-pure`): column-set algebra
(`Relation<T+R>`, `T-Z+V`), subset constraints (`⊆`), spec forms
(`ColSpec<Z=(?:K)⊆T>`, `AggColSpec<{…}>`, `FuncColSpec<{…}>`,
`_Window<T>`), plus `native function` declarations (pure-only by
definition; strict refuses them engine-verbatim: "Unsupported
syntax").

| # | row | construct |
|---|---|---|
| 60–64 | parser/ElementParserTest#273/283/287/292/298 | `Relation<T+Z>` / rename `T-Z+V` / `SortInfo<X⊆T>` fn decls |
| 65 | #314 | `native function <<PCT.function>> …cast<T\|m>` |
| 66 | #319 | relation signature |
| 67 | #324 | native fn (strict: Unsupported syntax) |
| 68–72 | #334/344/352/368/376 | extend/sort/rename/filterAdults/write native+relation sigs |
| 73–75 | #383/388/391 | native fns (strict: Unsupported syntax) |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (these ARE legend-pure; deleting
  them deletes the ported relation spec).

## 12. PURE-DIALECT-xstore-tolerance — 1 row

| # | row | construct |
|---|---|---|
| (75a) | parser/ElementParserTest#705 | XStore missing-comma completion — CORPUS-PROVEN (`testMappingCrossStore.pure:238`): legend-pure's compiler completes the rule and drops the rest; a refusing parser cannot read the corpus |

- **DECISION:** DECIDED: **KEEP** (2026-08-11, confirmed) — was proposed: **KEEP** (corpus-required).

## 13. TEST-MACHINERY-fixture — 1 row

| # | row | construct |
|---|---|---|
| (75b) | parser/SectionGrammarRegistryTest#10 | `Whatever my::D;` — a deliberately unknown element kind proving overlay/foreign-section routing |

- **DECISION:** DECIDED: **KEEP** (2026-08-11 review — `Whatever
  my::D;` is opaque bytes inside a skipped `###QueryPostProcessor`
  section, never parsed as an element; strict refuses the file
  engine-verbatim).

---

## THE BINDING PARITY GAP (found during this review)

Probed 2026-08-11 (`ZVariantTrialProbe`): the oracle **ACCEPTS**

```
Mapping m::M ( m::P: Relational { ~mainTable [db::D] T
    manager: Binding m::B: [db::D] T.F } )
```

and lite **REFUSES** it (`Missing table or alias for column 'Binding'`).
The binding transformer is real 4.138.2 engine grammar
(`relationalPropertyMapping: COLON (transformer)? operation`). No
in-scope corpus row exercises the spelling, so gate-8 parity is green —
this is a LATENT accept-parity gap. Closing it is a proper wire batch
(protocol record + PMCD byte-parity for the `bindingTransformer`
serialization + a compile-time wall until Binding semantics exist), and
it is the engine-parity migration path for `->get()` (row class 7).

---

## Open gaps this ledger does NOT cover

- **Resource `.pure` fixtures** (`core/src/test/resources/**`) are
  outside the literal-extraction census (bazel_smoke was sectioned by
  hand in batch 2; `stress/*.pure` and others have not been swept).
- **Assembled-model seams**: runtime string concatenation can still
  produce non-conformant documents the census can't see; the
  lenient→strict endpoint flip is the mechanical net that will catch
  every remaining one.
- **Lenient-tier tolerances not exercised by any test** (e.g.
  optional final `;` in multi-statement bodies is still ACCEPTED by
  the lenient parser — tests were conformed, the tolerance itself was
  not removed). The strict flip adjudicates the tier wholesale.
