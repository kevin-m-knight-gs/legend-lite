# Invention audit — what legend-lite supports that legend-engine does not

> **Method: code only.** No doc, comment, TSV or design note was taken as
> evidence. Every claim below is either (a) a mechanical diff of legend-lite's
> declared surface against the *defining sources* in
> `~/legend/legend-engine` @ `943d38b3dc2` and `~/legend/legend-pure`, or
> (b) a live probe compiled against `core/target/classes`.
> Run at legend-lite `e0a907a9`; **every probe re-run at `8a206fc8`** after nine
> commits landed upstream touching 19 parser files. Gate 8 re-run to regenerate
> every equivalence report from source rather than reading the committed TSVs.
>
> **Three of my own intermediate findings were false positives and are recorded
> as such in §5**, and **one real finding was fixed upstream mid-audit** (§4.1).
> A census method that never produces either is not being checked.

---

## 0. Verdict

Two independent gaps, one of which I initially missed.

**(A) The name catalog is ungoverned.** 26 natives sit in `meta::legend::lite::*`,
a package that cannot exist upstream. **20 name functions that exist nowhere in
legend-engine or legend-pure and are written by no corpus source — and user query
text can call them.** Probe-verified.

**(B) The dialect gate is applied inconsistently.** Legend-lite has a sound
three-tier design (`Dialect.LEGEND_PLATFORM` / `LEGEND_LITE` / `LEGEND_ENGINE`),
where `LEGEND_ENGINE` is meant to be the exact drop-in surface. It is not:
**three constructs the real engine rejects are accepted at the `LEGEND_ENGINE`
tier**, and a differential fuzz found **44 further mutants** it accepts and the
engine refuses — 38 of them from a single unguarded compensation.

| axis | surface | invented / leaking | governed by a gate? |
|---|---:|---:|---|
| native functions | 421 FQNs | **20** | **no** |
| native classes | 200 | **1** | no |
| declared grammar extensions | 65 own-corpus rows | 65, all named | **yes — ratcheted** |
| **dialect-gate enforcement** | every construct | **3 leaks + 44 fuzz rows** | **no — spot-checked only** |

> **Correction to my own first pass.** I initially reported the grammar axis as
> "already governed, needs no new work", on the strength of
> `OwnCorpusConformanceTest`'s classification. That was the same error the method
> forbids, one level up: I trusted a *test's* classification instead of probing
> the parser. That test only sees snippets that **happen to appear in
> legend-lite's own tests** — it cannot see what the parser would accept that
> nobody wrote. §4.1 is what direct probing found.

---

## 1. The finding: an ungoverned parallel function catalog

`core/src/main/java/com/legend/builtin/Pure.java` declares 697 native signatures
over 421 distinct FQNs. Diffed against **20,805** function FQNs defined across
both upstream repos, 28 are unmatched; 26 of those sit in `meta::legend::lite::*`.

Filtering to names that (a) exist nowhere upstream under any package and (b) are
never written by any upstream `.pure` source — with string literals and comments
stripped, because SQL goldens contain `avg(` and produce false hits — leaves
**20 invented names**:

```
avg  castAsDeclared  convertDateTimeFormat  convertTimeZoneFormat  divideRound
legacyAssocPredicate  legacyLocalProperty  legacyNavigate  maxDate  minDate
navigate  notEqualAnsi  otherwise  parseDateFormat  percentileCont
percentileDisc  sourceUrl  tds  typeAsDeclared  variantTo
```

### They are reachable from user source

`Pure.Index` (`Pure.java:763-791`) builds `FN_BY_FQN` **and `FN_BY_BARE`** —
"bare name → ALL overloads across packages" — over the single `ALL` list. There
is no internal/external partition. A probe compiled against `core/target/classes`,
calling `Compiler.compileQuery` on a trivial model:

```
ACCEPTED  avg(...)                          ACCEPTED  otherwise
ACCEPTED  maxDate/minDate                   ACCEPTED  notEqualAnsi
ACCEPTED  divideRound                       ACCEPTED  percentileCont
ACCEPTED  isNumeric                         ACCEPTED  sub(decimal)
ACCEPTED  FQN meta::legend::lite::avg(...)
rejected  navigate        :: navigate requires a relation or class-collection source
rejected  typeAsDeclared  :: typeAsDeclared expects (value, @Type)
CONTROL   average()  ACCEPTED     (real upstream function — resolution works)
CONTROL   bogusFnThatCannotExist(1)  rejected: unknown function
```

The control pair is the point: the rejection path works, and these names get past
it. `navigate` and `typeAsDeclared` were refused on **argument shape**, not
"unknown function" — they resolve too, my probe args were simply wrong.

So a user can today write `demo::P.all()->map(p|$p.age)->avg()` in legend-lite.
That query is not Legend. It will not run on legend-engine, and nothing tells
the author.

### Real upstream spellings exist for the user-facing ones

| invented | real Legend spelling |
|---|---|
| `avg` | `meta::pure::functions::math::average` |
| `percentileCont` / `percentileDisc` | `meta::pure::functions::math::percentile` |
| `sub` | `meta::pure::functions::math::minus` |
| `divideRound` | `meta::pure::functions::math::divide` + `round` |
| `notEqualAnsi` | `!=` / `meta::pure::functions::boolean::notEqual` |
| `maxDate` / `minDate` | `max` / `min` |
| `isNumeric` | no general Legend function — only `sqlDialectTranslation::defaults::isNumeric`, a dialect helper |

---

## 2. Keep / gate / delete

The decisive split is **who consumes the name inside legend-lite**. A name emitted
by the normalizer or lowering is real internal IR; a name nothing emits is dead
surface.

### DELETE — dead surface, 3 names

Zero upstream definition, zero corpus usage, **zero references anywhere in
legend-lite outside the catalog declaration itself**. They exist only to widen
the accepted language.

| name | evidence |
|---|---|
| `maxDate` | no consumer; the only `maxDate` strings in the repo are a column alias in `DuckDBIntegrationTest:3581` |
| `minDate` | same, `DuckDBIntegrationTest:3575` |
| `variantTo` | no consumer; `LowerRelationTest:693` is a test *method name*, not a call |

Delete the three `NativeFunctionDefinition` constants. Nothing can break: nothing
reads them.

### KEEP but GATE — 17 names, real internal desugar vocabulary

Each is emitted by legend-lite's own normalizer/lowering, mostly to desugar the
**legacy relational mapping DSL** into typed calls:

| name | emitted by |
|---|---|
| `avg` | `normalizer/MappingNormalizer`, `lowering/Aggregates`, `exec/MetamodelWalk` |
| `legacyNavigate` | `normalizer/GroupBySynthesis`, `JoinChainEmission`, `compiler/spec/CoreFn` (5 sites) |
| `legacyAssocPredicate` | `normalizer/AssociationSynthesis`, `MappingNormalizer`, `XStorePureEnds` |
| `legacyLocalProperty` | `normalizer/MappingNormalizer`, `XStorePureEnds` |
| `castAsDeclared` / `typeAsDeclared` | `compiler/spec/Typer`, `CoreFn`, `MappingNormalizer` |
| `otherwise` | `normalizer/MappingNormalizer`, `resolver/Substitution` |
| `notEqualAnsi` | `lowering/Scalars`, `model/RelOpFromProtocol`, `parser/DatabaseProtocolParser` |
| `parseDateFormat`, `convertDateTimeFormat`, `convertTimeZoneFormat` | `lowering/Scalars`, `normalizer/RelOpTranslator` |
| `percentileCont`, `percentileDisc` | `lowering/Aggregates`, `lowering/Scalars` |
| `divideRound` | `lowering/Scalars` |
| `navigate`, `sourceUrl` | `compiler/spec/CoreFn`, `normalizer/MappingNormalizer` |
| `tds` | `compiler/spec/CoreFn`, `normalizer/MappingNormalizer` |

**These should not be deleted — they are load-bearing.** The defect is narrower
and cheaper to fix: *internal IR vocabulary is registered in the same index that
resolves user calls.*

The fix is a partition, not a rewrite. Mark them (a boolean on
`NativeFunctionDefinition`, or a second list beside `ALL`) and have the
user-facing resolution path consult an index that excludes them, while the
normalizer and lowering keep emitting them. `Pure.java:763-791` is the only place
the indexes are built, so this is one constructor-side change plus a filter.

Note the names themselves argue for this: `legacyAssocPredicate`,
`legacyLocalProperty`, `legacyNavigate`, `castAsDeclared`, `typeAsDeclared` were
plainly never meant to be user vocabulary.

---

## 3. Native classes — one invention

200 declared via `nativeClass(...)`; 28 unmatched against 11,763 upstream
`Class`/`Association` FQNs. 27 are false positives (§5). One is real:

**`meta::pure::functions::relation::_Traversal`** — zero occurrences in either
upstream repo. The leading underscore marks it as internal. Same treatment as
§2's gate bucket: keep if consumed, but do not expose it as a resolvable type
name.

---

## 4. Grammar

### 4.1 The dialect gate leaks — four constructs the real engine rejects

`Dialect` (`core/src/main/java/com/legend/parser/Dialect.java`) defines three
tiers, and its javadoc states `LEGEND_ENGINE` is *"user-facing EXACT
legend-engine … the drop-in surface … Refuses BOTH the platform dialect and the
lite extensions"*.

Probing each construct at all three tiers **and against the real engine ANTLR
parser** (`PureGrammarParser.newInstance().parseModel`) — not legend-lite's model
of it:

| construct | platform | ENGINE tier | **real engine** | verdict |
|---|---|---|---|---|
| ~~`#{P{name}}->serialize()` — missing `#`~~ | no | no | no | **FIXED mid-audit** — see note below |
| `#TDS … #` literal | OK | **OK** | **no** | leaks |
| `^$x(...)` copy-instance | OK | **OK** | **no** | leaks |
| `%latest` | OK | **OK** | **no** | leaks |
| `native function` decl | OK | no | no | gated correctly |
| generics `<T>` | OK | no | no | gated correctly |
| function-type literal `{Integer[1]->Integer[1]}` | OK | no | no | gated correctly |
| `Relation<T+R>` column algebra | OK | no | no | gated correctly |

All three are named by `Dialect`'s own javadoc as platform-only
(`#TDS`, `^$x(...)`) or are plainly non-engine (`%latest`). The gate exists —
`refusesPlatformDialect()` is called at 26 sites — it is simply **not applied at
these constructs' parse sites**. Enforcement is per-construct and was never
checked for completeness.

**One finding was fixed while this audit was running.** `#{P{name}}->serialize()`
— a graph-fetch tree missing its `#` terminator — was accepted at every tier when
first probed at `e0a907a9`, and parsed to a clean `FunctionDefinition` while the
engine reported `Unexpected token '<EOF>'. Valid alternatives: [',', ')']`. Re-run
at `8a206fc8` it is **correctly refused at all three tiers**. One of the nine
intervening commits closed it. Recorded because it was real, and because it is the
evidence that this class of bug is live and worth a standing gate.

**Why nothing catches these.** `RejectionParityTest` and `CorpusSweepTest` do
compare legend-lite against the real oracle — but only over **text that exists**
in the corpus and fixtures. Malformed input appears in no corpus, so no gate has
an opinion. The three-tier design is sound; its enforcement is spot-checked rather than swept.

**Recommended gate.** The probe harness used here is ~30 lines against
`parser-equivalence`'s existing classpath: for a fixed adversarial corpus of
constructs and mutations, assert `LEGEND_ENGINE tier accepts ⟺ real engine
accepts`. That is a true drop-in-surface gate, and it is what would have caught
all four.

### 4.1b Differential mutation fuzz — ~1,490 mutants, 5 more families

Hand-written probes do not scale, so: take grammar seeds the **real engine
accepts**, mutate them systematically, keep only mutants the **real engine
rejects**, and flag any that `LEGEND_ENGINE` still accepts.

| round | mutation | mutants | engine-rejected | drift |
|---|---|---:|---:|---:|
| 1 | delete / duplicate each special char (`#;,()[]{}\|:~%@^><*.=!`) over 32 seeds | 1,010 | 983 | **20** |
| 2 | trailing junk × 15 forms, token deletion, keyword lower-casing over 15 seeds | 404 | 397 | **24** |
| 3 | island-terminator mutations (`#{…}#`, `#/…/#`, `#>{…}#`, `#TDS…#`, `#SQL{…}#`, `#GQL{…}#`) | 16 | — | **0** |

**Useful negatives** (these bound the problem): token deletion produced **zero**
drift. Keyword lower-casing produced **zero**. Trailing `}`, `]`, `;`, `,`,
`garbage`, `###Nope`, `|`, `->`, `#`, `@`, `~`, `*` all produced **zero**. Of 16
island-terminator mutations only one drifted. Legend-lite is strict almost
everywhere; the leniency is concentrated.

**Family 1 — a stray `)` is swallowed at top level. 38 of the 44 drift rows.**

Root cause found, `core/src/main/java/com/legend/parser/ElementParser.java:532-538`:

```java
// a STRAY top-level closer: the corpus's own
// m2m2rExecutionPlanTests.pure carries an unbalanced extra )
// (opens=4, closes=5) that the engine tolerates; skip exactly
// this token, never other junk
if (peek() == TokenType.PAREN_CLOSE) {
    advance();
    return true;
}
```

Three things are wrong with it.

1. **Its stated premise is false.** I fed the real engine that exact file
   (`core_relational/relational/executionPlan/tests/m2m2rExecutionPlanTests.pure`,
   174 opens / 175 closes) — **the engine rejects it**: `Unexpected token`. The
   file is a legend-*pure* source; legend-pure's compiler tolerates the
   unbalanced paren, the engine's ANTLR grammar does not. The comment conflated
   "legend-pure tolerates" with "the engine tolerates".
2. **It is not gated by dialect**, so it degrades `LEGEND_ENGINE` — the tier
   whose entire purpose is to refuse what the engine refuses.
3. **"skip exactly this token, never other junk" understates the reach.** It
   fires after *every* element type: `Class`, `Enum`, `Profile`, `Association`,
   `function`, `Database`, `Mapping`, `Runtime`, `Service` — 12 of 15 seeds — and
   repeats, so `)))` is accepted too.

This is the project's own cardinal sin in miniature: a compensation for one
corpus file, generalised into a blanket leniency, justified by a belief nobody
re-tested.

**Fix:** delete it, or gate it to `LEGEND_PLATFORM` where legend-pure fidelity is
the contract. That one file is a legend-pure source the engine itself cannot
parse; walling it is the honest outcome.

**Family 2 — `::` leniency inside path islands. 4 rows.**

```
#/my:P/name#        accepted (single colon)
#/my::::P/name#     accepted (quadruple colon)
```
Both with and without the `!alias` form. The qualified-name scanner inside
`#/…/#` does not require exactly `::`.

**Family 3 — missing comma between column definitions. 1 row.**
`Table t (id INT PRIMARY KEY c VARCHAR(200))` — no comma, accepted.

**Family 4 — missing `;` between Runtime body entries. 2 rows.**
`Runtime my::r { mappings: [my::m] connections: [...]; }` — accepted, and the
symmetric case on the last entry.

All four families are *accept-junk* leniencies: they widen what parses, but none
was observed to change the meaning of well-formed input. The one finding that did
change meaning — `#{…}->` — was fixed upstream mid-audit (§4.1).

### 4.2 The declared extensions — correctly gated

Recording these because they are the ones an audit is tempted to flag and
shouldn't: every declared lite extension probes clean —
`liteENGINE=no, realEngine=no`.

| extension | ENGINE tier | real engine |
|---|---|---|
| `BOOLEAN` / `BOOL` column type | refused | refused |
| SQLite connection type | refused | refused |
| generics, function-types | refused | refused |

`BOOLEAN`/`BOOL` is worth naming since it looks like drift and is not:
`DatabaseProtocolParser.java:365-373` gates it explicitly, with the engine's own
message (`Unsupported column data type 'BOOLEAN'`). The engine spells booleans
`BIT`. The comment's rationale — *"lite's own model has had Bool since the start
and keeps it"* — is a weak reason to carry a second spelling, so it belongs on
the keep/delete docket even though it is not a leak. Same for the SQLite
connection type: additive, gated, and a genuine backend feature.

### 4.3 The declared-extension ledger

This part *is* well governed; recording it so the audit is complete.

`OwnCorpusConformanceTest` parses every snippet in legend-lite's own tests at
three surfaces (platform / `LEGEND_LITE` / `LEGEND_ENGINE`) and classifies every
divergence. Fresh run: **971 platform-accepted, 946 LEGEND_LITE, 892
LEGEND_ENGINE** — a 54-snippet extension surface, of which 65 rows are
classified:

| class | n | judgement |
|---|---:|---|
| `LITE-DESIGN-mapping-as-function` | 20 | deliberate (clean-sheet mapping form); engine grammar has no arm for it |
| `PURE-DIALECT-signatures` | 16 | **not an invention** — legend-pure relation-contract syntax (`⊆`, `Relation<T+R>`, `AggColSpec<{…}>`, `native function`) |
| `DIALECT-function-types` | 13 | engine refuses `{Integer[1]->Integer[1]}`; legend-pure allows it |
| `DIALECT-generics` | 7 | engine: *"Type and/or multiplicity parameters are not authorized in Legend Engine"*; legend-pure uses them throughout |
| `LITE-DESIGN-inline-association` | 2 | deliberate |
| `LITE-DESIGN-sqlite-backend` | 2 | deliberate; additive connection type |
| `PURE-DIALECT-diagram`, `-xstore-tolerance` | 2 | legend-pure fidelity |
| `TEST-MACHINERY-fixture`, `ENGINE-TEST-SCOPED-section` | 2 | harness |

Every class is pinned in `OwnCorpusConformanceTest:227-238` and the assert fails
on growth, so this surface can only shrink. `unclassified` is empty — there is no
unnamed grammar invention.

**One labelling question, not a defect.** `DIALECT-function-types` (13) and
`DIALECT-generics` (7) are labelled as legend-lite dialect, but both are ordinary
**legend-pure** — `meta::pure::functions::collection::add<T>` is upstream. The
engine's Domain grammar forbids them; legend-pure does not. Under this project's
own rule that legend-pure is ground truth, those 20 rows arguably belong in
`PURE-DIALECT-*`. Worth re-labelling so the "lite invention" count reads true.

The 181 `parseStrict lenient` rows are not lite drift either: nearly all are the
**engine's own ANTLR parser throwing** (`Cannot invoke …QualifiedNameContext.packagePath()
because the return value is null`) on legend-engine's *own* platform `.pure`
sources. Legend-lite parsing files the engine grammar cannot is fidelity to
legend-pure, not invention.

---

## 5. The governance gap, and why this was invisible

`NativeFunctionTest.catalogMatchesTheGoldenFile` (`core/src/test/.../builtin/NativeFunctionTest.java:52`)
pins the catalog against **a golden file inside legend-lite**. It asserts the
catalog does not *change*; it never asks whether a name exists upstream. Adding a
21st invented native passes every one of the 8 gates as long as the golden is
regenerated.

Contrast the grammar axis, which is checked against a live engine oracle on every
gate-8 run. **The asymmetry is the root cause**: syntax was treated as the
compatibility surface, and vocabulary was not.

**Recommended gate.** The census in this audit is ~40 lines of Python and runs in
seconds against the two checkouts. Promote it: for every declared native FQN,
assert it resolves upstream *or* appears on an explicit `INTERNAL_DESUGAR`
allowlist. Ratchet the allowlist down-only, exactly as
`OwnCorpusConformanceTest` does for grammar.

### False positives — recorded so the method can be judged

1. **`meta::pure::mapping::execute` and `meta::pure::tds::getString`** first
   appeared as inventions. They are not: my index scanned only `.pure`
   definitions, and these are Java-registered natives. Both are written by real
   corpus sources (`execute(` in 186 corpus files; `$a.getString('addressName')`
   at `lineageTests.pure:79`).
2. **27 of 28 "missing" native classes** are M3 bootstrap primitives — `String`,
   `Integer`, `Any`, `Nil`, `Class`, `Type`, `Function`, `LambdaFunction`,
   `GenericType`, `Relation` — plus `DateLiteral`/`TimestampLiteral`, which do
   exist upstream (`duckDBSqlDialect.pure:650-661`). My `^Class` regex cannot see
   bootstrap or protocol-generated declarations.
3. **`avg` initially looked corpus-legitimate** with 11 hits. Every one was
   inside a SQL golden string — `avg(1.0 * "root".AGE) OVER (…)` — not Pure code.
   Stripping string literals dropped it to 0. Any census over `.pure` that does
   not strip quoted text will over-count exactly this way.

---

## 6. Actions

| # | action | size | risk |
|---|---|---|---|
| 0 | **Delete or `LEGEND_PLATFORM`-gate the stray-`)` skip** (`ElementParser.java:532-538`). Its justification is empirically false and it is the single largest leniency found | XS | low — one corpus file walls; it is a legend-pure source the engine rejects too |
| 2 | Apply `refusesPlatformDialect()` at the `#TDS`, `^$x(...)`, `%latest` parse sites | S | low — LEGEND_LITE and PLATFORM unaffected; only the drop-in tier tightens |
| 2b | Tighten path-island `::`, Database column commas, Runtime `;` separators | S | low |
| 3 | **Add the adversarial drop-in gate**: run the mutation fuzzer in CI — assert `LEGEND_ENGINE accepts ⟺ real engine accepts` over the seed × mutation grid | S | none — new gate; the harness already exists in `parser-equivalence` |
| 4 | Delete `maxDate`, `minDate`, `variantTo` from `Pure.java` | XS | none — no consumers |
| 5 | Partition the catalog: `INTERNAL_DESUGAR` flag, excluded from the user-facing index built at `Pure.java:763-791` | S | low — normalizer/lowering emit by constant, unaffected |
| 6 | Add a native-catalog parity gate (upstream-resolves ∨ allowlisted), ratcheted | S | none — new gate |
| 7 | Decide `_Traversal`: gate or rename without the underscore | XS | low |
| 8 | Re-label `DIALECT-function-types` / `DIALECT-generics` as `PURE-DIALECT-*` | XS | none — labels only |
| 9 | Decide `BOOLEAN`/`BOOL`: keep the second spelling or conform to `BIT` | XS | low — gated either way |

Items 1–3 make `LEGEND_ENGINE` actually mean drop-in. Items 4–6 remove a 20-name
parallel language from the user surface and stop a 20th appearing. Both gaps have
the same shape: **a compatibility claim enforced by spot-checks rather than by a
sweep**, and in both cases the sweep is cheap.


---

## Appendix A — every invented native, with its `Pure.java` constant

The working list for actions 4 and 5. One row per **declared constant**, so a
name with several overloads appears several times — all of them need the same
treatment. "Internal consumers" counts files under `core/src/main/java` that
reference the bare name, excluding `Pure.java` itself.

| bare name | Java constant in `Pure.java` | internal consumers | verdict |
|---|---|---:|---|
| `avg` | `AVG__NUMBER_MANY` | 3 | KEEP, gate out of user resolution |
| `castAsDeclared` | `CAST_AS_DECLARED__ANY_01__T_1` | 3 | KEEP, gate out of user resolution |
| `convertDateTimeFormat` | `CONVERT_DATE_TIME_FORMAT__STRING_0_1__STRING_1` | 2 | KEEP, gate out of user resolution |
| `convertTimeZoneFormat` | `CONVERT_TIME_ZONE_FORMAT__DATE_0_1__STRING_1__STRING_1` | 2 | KEEP, gate out of user resolution |
| `divideRound` | `DIVIDE_ROUND__NUMBER_1__NUMBER_1__INTEGER_1` | 1 | KEEP, gate out of user resolution |
| `legacyAssocPredicate` | `LEGACY_ASSOC_PREDICATE__A_1__B_1__RELATION_1__RELATION_1__FUNCTION_1` | 3 | KEEP, gate out of user resolution |
| `legacyAssocPredicate` | `LEGACY_ASSOC_PREDICATE__A_1__B_1__STRING_1__STRING_1__FUNCTION_1` | 3 | KEEP, gate out of user resolution |
| `legacyLocalProperty` | `LEGACY_LOCAL_PROPERTY__ANY_1__STRING_1` | 2 | KEEP, gate out of user resolution |
| `legacyNavigate` | `LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1` | 5 | KEEP, gate out of user resolution |
| `legacyNavigate` | `LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1__FUNCTION_1` | 5 | KEEP, gate out of user resolution |
| `maxDate` | `MAX_DATE__DATE_1__DATE_1` | 0 | **DELETE** — dead |
| `minDate` | `MIN_DATE__DATE_1__DATE_1` | 0 | **DELETE** — dead |
| `navigate` | `NAVIGATE__C_MANY__FUNC_COL_SPEC_1__FUNCTION_1` | 2 | KEEP, gate out of user resolution |
| `navigate` | `NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__FUNCTION_1` | 2 | KEEP, gate out of user resolution |
| `navigate` | `NAVIGATE__T_MANY__FUNCTION_1` | 2 | KEEP, gate out of user resolution |
| `notEqualAnsi` | `NOT_EQUAL_ANSI__ANY_1__ANY_1` | 3 | KEEP, gate out of user resolution |
| `otherwise` | `OTHERWISE__T_1__T_0_1` | 2 | KEEP, gate out of user resolution |
| `parseDateFormat` | `PARSE_DATE_FORMAT__STRING_0_1__STRING_1` | 2 | KEEP, gate out of user resolution |
| `percentileCont` | `PERCENTILE_CONT__NUMBER_MANY__NUMBER_1` | 2 | KEEP, gate out of user resolution |
| `percentileDisc` | `PERCENTILE_DISC__NUMBER_MANY__NUMBER_1` | 2 | KEEP, gate out of user resolution |
| `sourceUrl` | `SOURCE_URL__STRING_1` | 2 | KEEP, gate out of user resolution |
| `tds` | `TDS__STRING_1__STRING_1` | 2 | KEEP, gate out of user resolution |
| `typeAsDeclared` | `TYPE_AS_DECLARED__ANY_01__T_1` | 2 | KEEP, gate out of user resolution |
| `variantTo` | `VARIANT_TO__ANY_1__T_1` | 0 | **DELETE** — dead |

Also in `meta::legend::lite::*` but NOT counted as inventions (the bare name exists upstream, or the corpus really calls it):
  `convertDateFormat`, `hash`, `isNumeric`, `join`, `sub`, `traverse`

**Correction, found while assembling this appendix.** The body of this document
first reported **19** invented names. It is **20**: `tds` was wrongly excluded
because the corpus appeared to call it. Those hits are `^$tds(rows = …)` —
copy-instance expressions on a *variable* named `$tds` — and my call-detection
regex's negative lookbehind omitted `$`. `probes/usage3.py` carries the corrected
pattern (`(?<![A-Za-z0-9_:$^.])`). This is the fourth false positive the method
produced and the second caused by matching text that only looks like a call; the
first three are in §5.
