# PCT audit — where we compensate, and what it takes to run PCT ourselves

> **Scope.** `pct/` (the adapter + wiring), the PCT-motivated surface in `core/`/`engine/`, and
> the feasibility of a second, self-hosted PCT runner. Measured on `main` at `d229b694` on an
> idle machine, 2026-08-03, against upstream checkouts at `legend-pure 5.89.2` /
> `legend-engine 4.135.0`.
>
> **Every compensation in §3 was tested by deletion** (§4) — 14 controlled runs of the full
> suite. Numbers here are measured, not derived.
>
> **Companions:** `docs/TENET_REMEDIATION.md` (the rubric and the corpus-side findings —
> **it never covered `pct/`**), `docs/PCT_BURNDOWN.md` (status, stale since 2026-07-12),
> `docs/PCT_EXPECTED_FAILURES.md` (the ledger), `docs/PCT_NATIVE_PLAN.md` +
> `docs/PCT_CORPUS.md` (**on branch `pct-native` only**).

---

## 1. Verdict

**Half the compensations are cargo.** Of twelve tested by deletion, **six cost zero tests** —
including `remapErrorMessage`, which substitutes the answer key for the database's own error
message and which I had ranked the purest breach in the module. They can be deleted today.

**The load is concentrated in two sites, and one of them is largely self-inflicted.** The
answer-key TDS header (C15) is load-bearing for **148** tests — but 87 of those 148 exist only
because the adapter re-derives column types from *SQL type names* while the compiler's own Pure
type is sitting unread in the same record. Feed the header the compiler's type and the number
drops to **61**. That 61 is the honest size of the "PCT verifies values only, never core's
relation typing" debt — not 148, and not zero.

**Our ledger is clean and we have been underselling it badly.** All **36** `expectedFailures`
are *also* excluded by upstream's own DuckDB adapter, which ledgers **266**. Zero legend-lite
divergences (§2.1). DuckDB is upstream's *best* relational target — H2 512, Postgres 519,
Trino 686, Spanner 900.

**`pct/` is the largest surface never audited under the tenet.** The eight-agent
tenet-conformance audit behind `TENET_REMEDIATION.md` covered `core/`, `engine/`, `harness/`.
`ExecuteLegendLiteQuery` appears nowhere in it.

**The adapter cannot be deleted, but it can lose two-thirds of itself** (§6). Roughly 400 of its
1,126 lines are an irreducible value→`CoreInstance` bijection; the rest is transport repair and
rendering that belongs in the platform. And the size is not the story — upstream's DuckDB adapter
plus its shared body is 1,172 lines against our 1,465. **We are not oversized; we are doing the
wrong kind of work in the lines** — decisions where upstream has plumbing.

**Running PCT ourselves via upstream's contract is a LARGE project, not "two natives."**
Upstream's new *surveyor* runner is a portable Pure program (§5.1) — but it is a pure
*model-space* program, and legend-lite has no execution mode for that. `Profile`, `Stereotype`,
`Package`, `PackageableElement` and `SourceInformation` have **no Pure-level existence** here;
five of surveyor's helpers are self-recursive, which `UserCallInliner` rejects by design; and
`println` is a deliberate no-op. The realistic path is a legend-lite-native runner (§5.3), which
`pct-native` already proved at 1039/1199.

**And `mvn -pl pct test` is green but is not in CI.** `gate.yml` runs `core` tests, a `core`
install, and `RelationalCorpusRunner`. PCT is never invoked, so the ledger's ratchet — which
fails the build on an unexpected *pass* too — is unenforced.

### 1.1 Measured today, on an idle machine

```
mvn -o test -pl pct   →   Tests run: 1109, Failures: 0, Errors: 0, Skipped: 0
                          BUILD SUCCESS, ~82s wall
```

| | count | note |
|---|---:|---|
| Tests executed | **1109** | Essential 327, Relation 348, Standard 204, Grammar 136, Unclassified 94 |
| Passing outright | **1073** | |
| Ledgered `expectedFailures` | **36** | executed, asserted-to-fail with a pinned message — not skipped |
| Scopes not wired at all | **94** | Variant **88**, ScenarioQuant **6** — absent from `pct/pom.xml` |
| Upstream corpus, 7 collections | **~1214** | Essential 329, Relation 348, Standard 205, Grammar 142, Unclassified 96, Variant 88, ScenarioQuant 6 |

"1109/1109" means **1073 real / 1109 run / ~1214 available**. Three contradictory exclusion
counts live in the tree — source **36**, `PCT_BURNDOWN.md` **29**, `ENGINEERING_LOG.md:69`
**33**. The source is right.

---

## 2. The ledger is not the problem

### 2.1 The verification

Upstream declares expected failures as **machine-readable JSON manifests** —
`<module>/src/main/resources/pct-manifests/<adapter-key>/<Scope>_manifest.json`, 99 files across
15 adapter keys. Intersecting the DuckDB set against our 36 pins:

| | count |
|---|---:|
| Upstream DuckDB exclusions, all 7 scopes | **266** |
| Upstream DuckDB exclusions, the 5 scopes we run | **249** |
| legend-lite `expectedFailures` | **36** |
| …of which upstream also excludes | **36** |
| **legend-lite-only divergences** | **0** |

```bash
python3 - <<'EOF'
import json,glob,re
up={e["functionName"] for f in glob.glob(
      "/Users/neemsandv/legend/legend-engine/**/pct-manifests/relational-duckdb/*.json",
      recursive=True) for e in json.load(open(f))["exclusions"]}
ours={m.group(1) for f in glob.glob(
      "/Users/neemsandv/legend/legend-lite/pct/src/test/java/**/Test_LegendLite_*_PCT.java",
      recursive=True) for m in re.finditer(r'one\("([^"]+)"', open(f).read())}
print(len(up), len(ours), len(ours-up))   # 266 36 0
EOF
```

We run 1109 of the 1120 tests in those five scopes and fail none outside the 36, so of the 213
tests upstream's DuckDB adapter ledgers and we do not, we pass essentially all.

### 2.2 Where we sit among upstream's adapters

Exclusion counts from all 99 manifests — lower is better, against ~1,214 tests:

| adapter | excl. | | adapter | excl. |
|---|---:|---|---|---:|
| core-compiled | 3 | | relational-databricks | 582 |
| core-interpreted | 7 | | java platform binding | 624 |
| **legend-lite (5 of 7 scopes)** | **36** | | relational-clickhouse | 653 |
| relational-duckdb | 266 | | relational-trino | 686 |
| relational-snowflake | 285 | | relational-oracle | 692 |
| relational-h2 | 512 | | relational-memsql | 776 |
| relational-postgres | 519 | | relational-sqlserver | 846 |
| | | | relational-spanner | 900 |
| | | | deephaven | 989 |

Honest caveat: our 36 covers five scopes, not seven, and our adapter is allowed to compensate
where upstream's is not (§2.4).

### 2.3 What the 36 actually are

| category | n | fixable? |
|---|---:|---|
| Instance identity / metamodel reflection (`find`, `head`, `first`, `eq` on instances, `deactivate`) | 14 | **No.** Reference identity cannot cross a value-serializing wire. Permanent. |
| substring / indexOf 0-vs-1-based, plus the two sort tests that depend on them | 7 | **Deliberate.** We match the engine's 1-based relational pushdown (`Scalars.java:1256` cites the golden and the reference adapter's identical ledger). Correct under "the database executes". |
| Error source-**column** precision — all six pin actual column 23 | 6 | Yes, cheaply. The adapter reports a constant column. |
| PCT-harness serialization loss (`fold` ×3, `map` one-to-one) | 4 | Only by changing the transport (§4.3). `^$x(prop = expr)` prints as `copy('', prop)`. |
| Timestamp carrier domain (`adjustBy*BigNumber`) | 4 | No. Years 1.4M–800M exceed DuckDB `TIMESTAMP`. |
| Empty-string cell cannot round-trip the TDS text wire | 1 | Yes — §4.4. |

Two pin-strength defects: two pins are the bare string `"Assert failed"`, four are a bare
`instanceOf …CO_Person` fragment. Both swallow regressions. Upstream guards against exactly this
— `isGenericExecutionErrorMessage` **rejects** a generic wrapper message as an `expectedError`.

### 2.4 The honest caveat

Our exclusion count is 7× lower than the reference adapter's partly because we are better and
partly because **the adapter is allowed to compensate**. Upstream's DuckDB adapter drives every
test through a full relational plan and even suppresses constant pre-evaluation on purpose
(*"preeval will evaluate constant values that PCT aims to push to DB"*). §3 is part of the price
of §2.1. Both numbers are true and belong in the same sentence.

---

## 3. The 22 compensations

Classified with `TENET_REMEDIATION.md` §6 (**VIOLATION > COMPENSATION > BOUNDARY-with-defect >
BOUNDARY > ORCHESTRATION**). The **cost** column is measured — see §4.

### 3.1 Java — `pct/src/test/java/.../ExecuteLegendLiteQuery.java`

| # | Site | What it does | Verdict | cost |
|---|---|---|---|---:|
| **C1** | `:82-113` | `PURE_MODEL` hardcodes **five PCT corpus helper functions verbatim** because the text wire cannot carry a callee's body | **COMPENSATION** (Q8). Corpus source pasted into the harness. | **4** |
| **C2/C3** | `:115-121`, `:862-1019` | Five regexes scrape the printed text to find types; classes/enums are rebuilt from the **interpreter's** M3 graph, printed as Pure grammar, concatenated, re-parsed | **COMPENSATION** + correctness hazard (substring name discovery — the family `SIMPLE_NAME_AUDIT.md` exists to stamp out) | **45** |
| **C4** | `:1104-1117` | `inlineFunctionLiterals` — a regex rewrites `fqn(a:T[1]):R[1]{body}` into `{a:T[1] \| body}` | **COMPENSATION** (parser gap) | **2** |
| **C5** | `:1057-1095` | `reEscapeStringLiterals` guesses at a mixed escape convention | **COMPENSATION.** The burndown admits it *"guesses … and will corrupt backslash-bearing literals in other suites"* | **0** |
| **C6** | `:1032-1050` | `remapErrorMessage` rewrites DuckDB's shift errors into **the literal string the assertion expects** | **COMPENSATION** — Java substituting the answer key | **0** |
| **C7** | `:637-645` | `formatAsTds` scans every row for nulls to choose `[1]` vs `[0..1]` | **COMPENSATION** (Q6). Deriving a *type* from *data*. | **0** |
| **C8** | `:666-685` | `pureTypeName` re-derives Pure types from **SQL type names**, `default -> "String"`, DATE/TIMESTAMP → String | Violates **AGENTS.md 4 (NO FALLBACKS)** — and is **needless**: the compiler's Pure type is already on the record (§4.2) | see §4.2 |
| **C9** | `:687-716` | `formatValue` hardcodes Pure's print forms — `…HH:mm:ss.SSS` + `+0000`, bespoke float `.0` | **COMPENSATION** — the V1.5 gap on a second surface. `SSS` **truncates**: `.499999` prints `.499`. | untested |
| **C10** | `:505-511` | A `Timestamp` declared `StrictDate` is narrowed to `toLocalDate()` | Compensates `PureSql.java:64` mapping both `DATE_TIME` and `DATE` to `TIMESTAMP` — **V1.10** | **1** |
| **C11** | `:550-559` | A `String` matching a date-shaped regex is reinterpreted as a date | Fails Q5(a) — decides a **kind** from a value's **text shape**; same as **V1.4** | **0** |
| **C12** | `:569-583` | Multi-element `List` in scalar position: *"shouldn't happen … but return first as fallback"* | **VIOLATION** of AGENTS.md 4. Silently drops elements. | **0** |
| **C13** | `:735-747` | Unrecognized struct type defaults to `…collection::Pair` — **twice** | Silent default | **0** |
| **C14** | `:622-626` | `__\|__` pivot-column names get a hand-built escaped quote pair | Overfit to Pure's pivot identity spelling | untested |

**Not a compensation but found alongside them: two owners for one behaviour.**
`structToInstance:296` and `createClassInstance:735` both build a Pure instance from a struct
`Map`, with different semantics; `toCoreInstance` routes between them on whether
`classFqnOf(type)` is null (`:455-458` vs `:563-567`). The second defaults twice to a hardcoded
`Pair`, and E4 proved those arms are never taken. See §6.3.

### 3.2 Pure — `pct/src/main/resources/core_legend_lite_pct/pct_adapter.pure`

| # | Site | What it does | Verdict | cost |
|---|---|---|---|---:|
| **C15** | `buildTypedHeader` + `:271-276` | Rebuilds the TDS header from **`$f->functionReturnType()` — the test's own declared answer — and replaces the compiler's header with it** | **The largest compensation in the wiring.** The burndown: *"PCT verifies VALUES ONLY, never core's relation typing (a core typing every column [0..1] would still go green)"* | **148**, of which **87** are C8's fault (§4.2) |
| **C16/C17** | `wrapPctMap`, `wrapPctList` | Hardcoded type tables — exactly `(String,Integer)`, `(String,String)`, `(Integer,String)`; a 5-element list enumeration | Overfit to the tests that exist | untested |
| **C18** | `:294-300` | `Integer`→`Float`, `Decimal`→`Float` widening on a declared `Float` | Compensates DuckDB folding `AVG(1)` integral. `pct-native` fixed this properly in core (`8bd4aa98`); never landed on main | **2** |
| **C19** | `:288-290` | `String` → `parseDate()` on a declared `Date` | Consequence of C8 | untested |
| **C20** | `:291-293` | `String` → enum value by name | Legitimate **BOUNDARY** | — |
| **C21** | `substituteOpenVariables` / `substituteInExpression` | Hand-written β-substitution over the M3 AST — **not capture-safe** (no α-freshening) | Exists only because the transport is text. Same shape as **V3.2** | untested |
| **C22** | `getSimpleTypeName` | Collapses every enumeration and unrecognized type to `'String'` | Silent default | untested |

### 3.3 Where PCT touched core — mostly clean

**44 of 47** PCT mentions in `core/`/`engine/` main sources are benign comments citing which test
witnessed a rule that is itself general and unconditional. There is **no test-mode env var, no
`legend.pipeline` test gate, no "running under test" branch** outside `com.legend.harness`.
Three need judgment:

- **`compiler/spec/TdsChecker.java:196-207`** — JSON-shaped TDS cells infer `Variant` where real
  Pure says `String`. Labelled *"DELIBERATE, LEDGERED"*, but justified by **the PCT wire dropping
  the fixtures' `Variant` annotations**. Divergent platform semantics caused by a harness
  limitation; fixing the transport retires the justification.
- **`compiler/spec/Typer.java:1922-1937`** — demangles engine signature tails. Attributed to PCT;
  actually a general legend-engine convention. Reword the comment, keep the code.
- **`lowering/Scalars.java:2930`** — "the PCT **fixture** spelling". General ISO-8601
  normalization; only the word is a smell.

`TENET_REMEDIATION.md` **V3.1** still holds: `com.legend.harness` (5,288 lines) ships in the
production jar with zero production consumers, and `ArchitectureTest.java:129-142` **blesses**
the placement with a carve-out.

---

## 4. The deletion test — measured

`TENET_REMEDIATION.md` §6 prescribes the tie-breaker: *"Delete the site and let the platform
answer. Same result → it was compensation all along. A loud error → the platform has a real gap
and the site is a stopgap that must be named as one."*

Method: apply one removal, run the full suite, count failures from the surefire XML, revert via
`git checkout -- pct/`. Baseline 1109/1109 confirmed twice before starting; working tree verified
clean after every run.

> **Methodology note, recorded because it nearly produced a false finding.** My first counter
> read failures from surefire XML and reported "0" when a run produced *no XML at all* — a
> compile error scored as a perfect pass. One experiment (E13) was reported to myself as a
> spectacular zero before the guard caught it: `BUILD FAILURE`, 0 suites run. The harness now
> refuses to score any run whose log lacks a `Running org.finos…` line. **A green that comes from
> an absence of evidence is the most dangerous number in a test harness** — the same shape as
> `PCT_BURNDOWN.md`'s audit round 6, where three independent holes inflated the runner's PASS
> column.

### 4.1 Results

| exp | compensation removed | tests lost | reading |
|---|---|---:|---|
| E2 | **C6** error-message remap | **0** | pure cargo — delete |
| E3 | **C12** silent list fallback → throw | **0** | never hit; make it throw |
| E4 | **C13** silent `Pair` default → throw | **0** | never hit; make it throw |
| E5 | **C11** date-shaped-string reparse | **0** | pure cargo — delete |
| E7 | **C7** null-scan multiplicity | **0** | dead while C15 fires |
| E9 | **C5** escape re-escaping | **0** | pure cargo — and the burndown warned it corrupts literals |
| E6 | **C10** `Timestamp`→`StrictDate` | **1** | real; root cause `PureSql.java:64` |
| E12 | **C18** `Integer`→`Float` widening | **2** | real; `pct-native` fixed it in core already |
| E8 | **C4** regex fn-literal inlining | **2** | real parser gap |
| E1 | **C1** five hardcoded corpus functions | **4** | real wire gap — **and one of the five is cargo** |
| E10 | **C2/C3** regex-driven model injection | **45** | real wire gap; fails **loudly** (`unknown class '…'`) |
| E11 | **C15** answer-key header overlay | **148** | see §4.2 |
| **E13b** | **C15 removed *and* the header fed the compiler's own Pure type** | **61** | **the honest number** |

**Six of twelve cost nothing.** Everything that failed, failed loudly and by name — no silent
wrongness was uncovered, which is a real credit to the module.

### 4.2 The C15 result, which is the most useful number here

`formatAsTds` builds the TDS header with `pureTypeName(col.sqlType())` — re-deriving a Pure type
from a **SQL type name**, with `default -> "String"` and DATE/TIMESTAMP deliberately flattened to
String (C8). Then `buildTypedHeader` on the Pure side throws that away and substitutes types
derived from the test's own declared return type (C15).

But `com.legend.exec.Column` is `record Column(String name, String sqlType, Type pureType)` —
*"the PURE type — the OBJECT, not a name string: consumers (PCT, serializers, the QueryService
bridge) convert values by this type and never sniff SQL types."* And `CoreBridge.column:68-70`
carries it across to the engine-shaped record. **The compiler's own Pure type is already there
and the adapter sniffs the SQL type instead** — exactly what `Column`'s javadoc forbids.

| header source | C15 overlay | failures |
|---|---|---:|
| `pureTypeName(col.sqlType())` | on | **0** (baseline) |
| `pureTypeName(col.sqlType())` | off | **148** |
| **the compiler's Pure type** | off | **61** |

So of the 148: **87 were the adapter's own lossiness**, recoverable by reading a field that is
already populated. **61 remain** — 55 of them failing at the `stringToTDS()->cast(@X)` step, 6 on
pivot-column parsing.

**Do not over-read the 61.** This experiment does not separate "core's relation typing is wrong"
from "the multiplicity is wrong" — `Column` carries no multiplicity, so the `[1]`/`[0..1]`
annotation still comes from C7's data-driven null-scan even in E13b. Splitting that is the next
experiment, and it needs the compiler's multiplicity plumbed to the wire.

What is safe to say: **the "values only, never typing" debt is real, is worth 61 tests not 148,
and 87 tests' worth of it is a bug in the adapter rather than a gap in core.**

### 4.3 Gap A — the transport must stop being text

**C1, C2/C3, C4, C5, C21 — 51 tests measured.** A typed M3 function is printed to a string,
shipped, and re-parsed; everything a string cannot carry is rebuilt by regex on the far side.
Fix by handing off the `FunctionDefinition` structurally and building HIR directly — or by
skipping the interpreter entirely (§5), which is strictly better.

### 4.4 Gap B — the platform needs a result-serialization surface

**C7, C8, C9, C10, C11, C15, C19, C22.** This is `TENET_REMEDIATION.md` §3.1 verbatim on a new
surface: *"the platform has no result-serialization surface, and lies about it."*
`ExecutionResult` has no renderer; `#TDS` is emitted exactly once in all of `main/`, inside the
harness. So the corpus harness and the PCT adapter each author Pure's print-forms themselves, in
Java, differently.

In order: (1) **use `Column.pureType()` in the header** — 87 tests, ~15 lines, do this first;
(2) narrow `toString(Any[1])` so a `RelationType` cannot silently match the scalar cast — a
latent wrong-answer independent of all of this; (3) plumb multiplicity to the wire and delete
C7/C15, then measure the residual 61 honestly; (4) fix `PureSql.java:64` so `DATE` and
`DATE_TIME` do not both become `TIMESTAMP` — C10 and V1.10 fall out together; (5) give the
platform a real print-form owner so C9 stops truncating microseconds.

### 4.5 The residue

C6, C11, C5 (delete outright — zero cost), C12/C13 (make them throw — zero cost), C14, C16/C17
(replace hardcoded tables with the declared type, loud on anything else).

---

## 5. Running PCT ourselves

### 5.1 Upstream's surveyor contract — real, but LARGE for us

Upstream re-architected PCT between 5.88 (what we pin) and 5.89: discovery *and* execution now
live in `surveyor.pure`, exclusions live in portable JSON manifests, and Java supplies three
natives. `PCTManifest.java` states the intent — *"a single portable, language-agnostic file that
can be consumed by Java (interpreted and compiled), **Rust**, and any future Pure executor."*

**I previously wrote that our M3 metamodel was "largely present" and the reflection surface
"partial". That was wrong.** Verified against `core/src/main`:

| requirement | our state |
|---|---|
| `Package`, `PackageableElement`, `Profile`, `Stereotype`, `TaggedValue`, `SourceInformation` as Pure values | **absent** — zero hits in `core/src/main`. `ConcreteFunctionDefinition` is declared with an empty body (`Pure.java:515`), so `.stereotypes`/`.package` cannot type-check |
| `Package.children` navigation | **absent** — `ModelContext` is flat FQN-keyed maps; no package tree exists |
| `elementToPath` / `pathToElement` / `sourceInformation` | **absent** from the native catalog entirely |
| Stereotypes reaching the compiled layer | **dropped by design** — `ModelContext.java:42-46`: *"`TypedClass` deliberately drops annotations"*. `StereotypeApplication` is two bare strings; `ModelBuilder.findProfile` exists with **zero callers** |
| Recursive user functions | **walled by design** — `UserCallInliner.java:174-179` throws *"recursive functions cannot lower to SQL"*. Five surveyor helpers self-recurse |
| A model-space execution mode | **absent** — `HostEval`'s construction whitelist is 5 FQNs; everything else lowers to SQL and needs a JDBC connection |
| `println` | **a deliberate no-op** at three sites |
| A distinguishable assertion-failure exception | **absent** — none of core's 5 exception types is one, so PASS/FAIL vs ERROR cannot be told apart |

Items genuinely worth building regardless of surveyor: stereotypes on compiled elements, a
package tree, `Profile`/`Stereotype` as values, source-id retention, `elementToPath`/
`pathToElement`, `Map` runtime semantics, the Pure assert family. Items that exist *only* to
satisfy surveyor's verbatim shape: a general model-space interpreter, recursion, element-as-value
beyond class refs, the root-`::` literal, `Profile <<stereo>>` grammar, and a real `println`.

**Verdict: adopt the manifest format now; treat surveyor itself as a later option, not the plan.**

### 5.2 What main can do today — measured

Our own parser, pointed at the raw upstream `.pure` corpus with no preprocessing:

```
parse OK   : 217 files, 1030 PCT tests   (84%)
parse WALL :  21 files,  196 PCT tests
```

Seven distinct grammar gaps; three cover 165:

| wall | files | tests | fix |
|---|---:|---:|---|
| `SortInfo<(?:?)⊆T>` — the wildcard relation-type slot | 1 (`over.pure`) | **68** | already solved on `pct-native` (`1076b86a`) |
| `doc.doc='…' + '…'` — string concatenation in a tagged value | 6 | **63** | constant-fold, or accept a string expression |
| leading-dot float literal (`cosh(-.7654321)`) | 4 | **34** | lexer |
| `%` in `adjust.pure` | 1 | 17 | lexer |
| `\0` escape in `char.pure` | 1 | 6 | lexer |
| scenario-quant `PAREN_OPEN` after a type | 6 | 6 | grammar |
| `LESS_THAN` after a type name (`pct_core`, `pctQualifiers`) | 2 | 2 | grammar |

### 5.3 It has already been done once — on a stale branch

`pct-native` (18 commits, 2026-07-18/19) reached **1039 / 1199 discovered tests** with **zero
upstream dependencies**: `pct-corpus/` depends on `legend-lite-core` and JUnit only, reads the
`.pure` corpus in place, discovers tests with our own `ElementParser`, and runs each body through
`core`'s parse → type → inline → lower → DuckDB. The **only** synthetic AST node is the adapter
binding `{g | $g->eval()}` — pct_core.pure's own reference adapter as AST, β-reduced by the
inliner. No substitution walker, no text. Six adversarial audits found two HIGH wrong-row
channels, both closed and re-verified with committed pins.

Note it sidesteps every "surveyor-only" item in §5.1 by doing discovery in Java over
`ParsedModel.elements()` — which `rcorpus/Runner.discoverTests:433-451` already does.

**Do not merge it.** Merge base 2026-07-18; `main` has **695 commits** since; `git merge-tree`
reports 12 content conflicts in the hot files; main's `TestBody.java` is at **3,499 lines against
a 3,500-line guardrail**. Evidence artifact, not a merge candidate. Re-implementing is also the
chance to fix what it left open (sub-ms truncation, `fromJson` duplicate keys, the `compactJson`
subset canonicalizer, the `average` guard keyed on the wrong layer).

### 5.4 Both modes, and what each is for

| | **Mode A — self-hosted** (`pct-corpus/`) | **Mode B — through legend-pure** (`pct/`) |
|---|---|---|
| Runs | our parser, typer, lowering, DuckDB | upstream interpreter drives the body; only `$f->eval` crosses |
| Denominator | **every `<<PCT.test>>` on disk** — ~1214 | what the `ReportScope` exposes — 1109 |
| Dependencies | `legend-lite-core` + JUnit | 18 upstream artifacts at `legend-pure 5.88` / `legend-engine 4.133`, plus a PAR plugin |
| Catches | parser gaps, typer gaps, our own assert semantics | divergence from the reference implementation |
| Blind to | our assert vocabulary being wrong twice the same way | anything the text wire drops (§4.3) |
| Compensations | ~0 by construction | 22 (§3) |

Mode B is the **referee** and must stay — a self-hosted runner that grades its own homework will
drift. Mode A is the **engine of progress**. The `pct-native` plan proposed deleting `pct/` at
A5; **that is the one part I would not adopt.**

### 5.5 Upstream's answer to "this cannot be expressed in SQL"

It validates our posture. When a Pure function has no SQL translation the relational adapter
**fails hard** — `fail('No SQL translation exists for the PURE function …')` — and the exception
is matched against the manifest's `expectedError` by substring. **There is no in-memory
fallback.** Routing partitions some expressions to a Java platform cluster *before* SQL
generation, but once an expression is claimed by the relational store and SQL generation fails,
there is no recovery. Upstream even suppresses constant pre-evaluation on purpose.

All 6,717 exclusions across the 11 relational adapters, by error text:

| count | category |
|---:|---|
| 3,070 | `[unsupported-api] … not supported` — dialect capability gap |
| 1,891 | other (JDBC type errors, cast failures, column resolution) |
| 671 | `No SQL translation exists for the PURE function 'X'` |
| 516 | assertion mismatch — SQL ran and produced the **wrong answer** |
| 515 | DB/SQL runtime error |
| 54 | `"X is not managed yet!"` |

Our 339 `NotImplementedException` sites are the same doctrine. `TENET_REMEDIATION.md` §9's
framing was right, and it applies to the reference adapter too.

### 5.6 Two things upstream gives us that we are not taking

- **The manifest format and its ratchet.** We hand-maintain 36 `one(...)` calls and assert parity
  *in prose*. Emitting a manifest in upstream's shape makes §2.1 a build-time check and makes our
  numbers comparable to all 15 published adapters. Upstream also hard-errors when a manifest names
  a nonexistent test — an anti-rot guard we lack.
- **Two whole scopes.** `VariantFunctions` (88) and `ScenarioQuantFunctions` (6). Upstream's
  DuckDB adapter ledgers 17 exclusions in Variant and **0** in ScenarioQuant.

**One place to beat upstream:** they lost the `AdapterQualifier` classification
(`unsupportedFeature` / `assertErrorMismatch` / `needsInvestigation` / `needsImplementation`) in
the JSON migration — which is why 3,070 of their exclusions are only distinguishable by
string-sniffing. Put the qualifier *in* our manifest schema from day one.

---

## 6. Can the adapter be deleted?

`pct_adapter.pure` (339 lines) and `ExecuteLegendLiteQuery.java` (1,126 lines) **are** Mode B.
Three different questions hide behind "delete them", with three different answers.

### 6.1 Delete outright, with nothing in their place — no

- **PCT's framework contract requires a `<<PCT.adapter>>`-stereotyped Pure function.** That is
  how the corpus finds an executor at all (`pct_core.pure:26-33`); all 13 upstream adapters have
  one. Deleting `pct_adapter.pure` removes us from the framework.
- **The interpreter consumes `CoreInstance`s.** Something in Java has to build them from our
  `ExecutionResult`. That crossing is a genuine **BOUNDARY** under the §6 rubric, not a
  compensation, and it cannot be deleted while Mode B exists.

### 6.2 Delete by retiring Mode B — possible, and I argue against it

This is `pct-native`'s A5: *"delete `pct/` (module, upstream deps, bridge, shim) and fold the PCT
gate onto `pct-corpus`."* Two objections, one practical and one structural:

- **Mode A does not exist on main** (§5.3). Nothing to fold onto yet.
- **§5.4's argument stands.** Mode B is the only thing anchoring us to a real Pure
  implementation. A self-hosted runner that grades its own homework can drift, and by
  construction it cannot see itself drift. The referee is worth its cost.

### 6.3 Shrink to plumbing — yes, and here is the budget

| `ExecuteLegendLiteQuery.java` — 1,126 lines | lines | fate | measured cost to remove |
|---|---:|---|---:|
| Regex model injection — `extractClassMetadata`, `extractEnumDefinitions`, `extractClassRecursive` (C2/C3) | ~210 | goes with the transport fix (§4.3) | 45 |
| TDS rendering — `formatAsTds`, `pureTypeName`, `formatValue`, `createTDSResult` (C7/C8/C9) | ~119 | goes when the platform owns print-form (§4.4) | 148 → **61** with `Column.pureType()` |
| Text utilities — `remapErrorMessage`, `reEscapeStringLiterals`, `inlineFunctionLiterals` (C4/C5/C6) | ~96 | C5 + C6 deletable **today** | 0, 0, and 2 for C4 |
| `createClassInstance` + `indexOf` | ~79 | **duplicate** — consolidate into `structToInstance` | fallback arms proven dead (E4 = 0) |
| `PURE_MODEL`'s five hardcoded corpus functions (C1) | ~32 | goes with the transport fix | 4 (one of the five is already cargo) |
| **Value → `CoreInstance` bijection** — `handleScalar`/`handleCollection`/`handleTabular`, `toCoreInstance`, `structToInstance`, `genericTypeOf`, the date builders | **~400** | **irreducible** while Mode B exists | — |

`pct_adapter.pure`'s floor is much lower: **~30 lines**. Upstream's DuckDB and H2 adapters are
**27 lines each**. Everything above that — `substituteOpenVariables` (C21), `buildTypedHeader`
(C15), `wrapPctList`/`wrapPctMap` (C16/C17), `getSimpleTypeName` (C22), the scalar coercion
ladder (C18/C19/C20) — is compensation or transport repair.

**The duplicate is worth calling out on its own.** `structToInstance:296` and
`createClassInstance:735` are two implementations of one behaviour — struct `Map` → Pure
instance — with different semantics. `toCoreInstance:455-458` routes to the first when
`classFqnOf(type) != null`, and `:563-567` routes to the second otherwise, where it defaults
twice to a hardcoded `Pair`. E4 proved those default arms are never taken. Two owners for one
behaviour is the AGENTS.md violation; the fallback is the AGENTS.md-4 violation. Both fall out of
one consolidation.

### 6.4 The size comparison that reframes the whole question

| | adapter | shared body | total |
|---|---:|---:|---:|
| upstream DuckDB | 27 | 1,145 (`pct_relational.pure`) | **1,172** |
| upstream H2 | 27 | 1,145 (same file, reused) | **1,172** |
| **legend-lite** | 339 | 1,126 (`ExecuteLegendLiteQuery`) | **1,465** |

**We are not oversized — we are doing the wrong *kind* of work in the lines.** Upstream's 1,172
lines are plumbing: reprocess the function, build an execution plan, seed a database, bind to a
platform, materialize results. Ours make *decisions*: regex type discovery, value-shape sniffing,
answer-key typing, escape guessing. That distinction is the finding; the line count is not.

### 6.5 The target

**`ExecuteLegendLiteQuery` → ~400 lines of decision-free bijection. `pct_adapter.pure` → ~30
lines.** Zero compensations, both files still present, Mode B still refereeing.

That is precisely `pct-native`'s **"Mode B — de-compensated delegation"**: *"the Java bridge
shrinks to a decision-free bijection under THE BRIDGE RULE."* It was specified in
`PCT_NATIVE_PLAN.md` and never started — the branch went all-in on Mode A instead. It is the
cheaper half of that plan and it is still on the table.

**Ordering constraint:** the ~400-line floor is only reachable *after* Gap A (§4.3) and Gap B
(§4.4). But §6.3's third and fourth rows — ~175 lines covering C5, C6 and the duplicate builder —
are reachable today at zero measured cost, and they do not depend on anything.

---

## 7. Sequencing

**Free wins — zero measured cost, do them in one commit:**

1. **Delete C6, C11, C5** (0, 0, 0). Make **C12** and **C13** throw (0, 0). Five compensations
   gone, including the two silent-wrongness hazards and the escape mangler the burndown warned
   about.
2. **Use `Column.pureType()` for the TDS header** instead of sniffing `sqlType()` — recovers
   **87** of C15's 148 and honours `Column`'s own documented contract. ~15 lines.
3. **Consolidate `createClassInstance` into `structToInstance`** (§6.3) — one owner for one
   behaviour, and the hardcoded `Pair` defaults go with it. ~79 lines out.

Together those are ~175 lines of the adapter deleted with zero measured cost and no architectural
dependency.

**Cheap and high-leverage:**

4. **Put PCT in CI.** `gate.yml` does not run it. *(PCT also consumes the installed
   `legend-lite-engine` jar from `~/.m2`, not the working tree — a stale jar has produced a fossil
   baseline before.)*
5. **Emit an expected-failure manifest** in upstream's JSON shape plus a qualifier field, and
   assert `ours ⊆ upstream-duckdb`. Fix the six weak pins; adopt the nonexistent-test hard error.
6. **Correct the three doc numbers** — `PCT_BURNDOWN.md` (29), `ENGINEERING_LOG.md:69` (33), and
   the "1109/1109" framing.

**Real work, in dependency order:**

7. **Plumb multiplicity to the wire**, delete C7 and C15, and measure the residual 61 honestly.
   This is the "values only, never typing" debt and it is now sized.
8. **Narrow `toString(Any[1])`** against `RelationType` — latent wrong-answer, independent. **Do
   not batch with the rendering redesign.**
9. **Burn the parse walls** (§5.2) — 196 tests, seven fixes, ~165 in the first three. Pays in
   both modes; precondition for Mode A.
10. **Land `pct-corpus/` on main** — re-implement rather than merge. Extract the runner kit that
    `PctCorpusRunner` and `rcorpus/Runner` duplicate.
11. **Fix `PureSql.java:64`** (C10 + V1.10), then a platform print-form owner (C9's microsecond
    truncation).
12. **Gap A** — structured hand-off, or let Mode A moot it. 51 tests. Takes the adapter to its
    §6.5 target of ~400 + ~30 lines.
13. **Wire Variant + ScenarioQuant** (94 tests).
14. **`com.legend.harness` out of `core/src/main`** — V3.1. Deletes three ArchUnit carve-outs.

## 8. Do not do these

- **Do not merge `pct-native`.** 695 commits of divergence, 12 conflicts, one line of guardrail
  headroom (§5.3).
- **Do not delete `pct/`.** Mode B is the referee (§5.4). Shrink the adapter to a decision-free
  bijection instead (§6.5) — the deletion you want is of *decisions*, not of files.
- **Do not treat the adapter's line count as the defect.** Upstream's equivalent is 1,172 lines
  against our 1,465 (§6.4). What differs is that theirs is plumbing and ours makes decisions.
- **Do not plan on running `surveyor.pure` verbatim** as the near-term route (§5.1). Take the
  manifest; build the native runner.
- **Do not "fix" the 36 pins.** 14 permanent, 7 deliberate and correct, all 36 ledgered upstream.
- **Do not add an in-memory fallback for un-lowerable functions.** Upstream has none either.
- **Do not read `PCT_BURNDOWN.md` for numbers.** Read it for the debt admissions, which remain
  accurate — and note §4.2 now *quantifies* its largest one.
- **Do not trust a green from a harness that could have produced no evidence.** See §4's
  methodology note.
