# Compiler-stage audit — stage 2 (2026-08-06)

**The question:** stage 1 (parse) has a live differential harness reporting 19,273/19,273. Stage 2
is `PureModelContextData -> PureModel`. What measures it, and what is it getting wrong?

**Anchors.** legend-lite working tree at `266fe1d5`+ (not a git repo; no history to consult).
legend-engine checkout `4.137.0-36-g943d38b3dc2`. **Executed oracles:** legend-engine
`4.133.0` compiler and legend-pure `5.88.0` interpreted runtime — both stood up from `~/.m2`
with no network. Probes under `/tmp`; **no file in legend-lite was modified.**

**Method.** Six agents — name/import resolution, the oracle question, type system and inference,
element compilation and the model graph, the two-implementation question, diagnostics and
totality — plus a seventh that adjudicated the type findings against both live oracles. Each was
told to execute rather than infer and to report what it could not determine. Several refuted
premises in their own briefs; those refutations are recorded in §6 and are among the most
valuable results.

> **Companions:** [`PARSER_IMPLEMENTATION_AUDIT_2026_08.md`](PARSER_IMPLEMENTATION_AUDIT_2026_08.md)
> is stage 1. [`GRAMMAR_COMPATIBILITY_2026_08.md`](GRAMMAR_COMPATIBILITY_2026_08.md) is section
> coverage. [`ARCHITECTURE_AUDIT_2026_08.md`](ARCHITECTURE_AUDIT_2026_08.md) swept 12 feature
> areas; §6 records which of its findings are now stale.

---

## §0 — The verdict

**Stage 2 has no oracle, and the things it gets wrong are the things an oracle would have
caught on day one.**

That is the whole finding. Every defect in §1 is silent, most produce a wrong *answer* rather
than an error, and none is visible to any gate in the repo. Three facts frame it:

1. **A stage-2 differential harness is buildable today, offline, with zero new downloads** —
   three Maven coordinates, ~8 ms per compile, and a byte-deterministic comparable artifact the
   engine already ships (§2). This was assumed impossible; it is not.
2. **Of 9,792 assertions judging the compiler, 47 — 0.5% — come from upstream** (§3.2).
3. **The CI gate is green because its only cross-checked step does not run** (§3.1).

Two secondary findings are structural rather than semantic and are worth as much:

- **A legacy compiler still gates every HTTP request**, rejecting ~14% of the relational corpus
  that the live compiler handles (§5). This is a shipped defect, not a tidiness problem.
- **Every always-loaded document describes the wrong module** (§6.3).

And one finding is not a defect at all but a decision the project has to make: **legend-pure and
legend-engine disagree with each other about overload resolution**, so "correct" is
under-specified until someone chooses (§7).

---

## §1 — Findings, severity-ranked

Severity is consequence if it fires. Corpus frequency is stated separately, because "zero
occurrences today" is a property of the corpus.

### Tier 1 — wrong answers, adjudicated against a live oracle

**1.1 An optional silently becomes required, and values are manufactured from nothing.**

`InferenceKernel.unifyMult:587-613` rejects `[*] -> [1]` and a statically-empty `[0] -> [1]`.
**`[0..1] -> [1]` falls through.** With `boss: test::P[0..1]` unset:

| expression | inferred | value |
|---|---|---|
| `$p.boss.name` | `String[0..1]` | — |
| `$p.boss.name->toUpper()` | **`String[1]`** | **`null`** |
| `needsOne($p.boss.name)` | **`String[1]`** | **`"!"`** |

**Both oracles reject at compile time:**

```
legend-pure:   The system can't find a match for the function: test::needsOne(_:String[0..1])
legend-engine: Can't find a match for function 'test::needsOne(String[0..1])'.
               Functions that can match if parameter types or multiplicities are changed:
                    test::needsOne(String[1]):String[1]
```

The javadoc at `InferenceKernel.java:549-551` — *"Following engine convention, only the
`[*] -> [1]` case is rejected"* — **is factually false.** The engine has a dedicated error naming
multiplicity. There is no oracle anywhere for the lenient behaviour.

This is the highest-severity item in the audit: it is reachable from any optional property, it
is invisible, it converts a compile error into a wrong row, and **every downstream consumer that
trusts `[1]` — null-guard elision, inner-vs-outer join choice, `NOT NULL` assumptions — is
reasoning from a false premise.**

**1.2 `cast` is an unchecked coercion where every oracle makes it a checked assertion.**

`CastChecker.java:22-40` performs no relatedness check and lowers to a SQL `CAST`.

| expression | legend-lite | legend-pure (executed) | legend-engine runtime primitive (executed) |
|---|---|---|---|
| `1->cast(@String)` | `String[1]`, value `1` | `Cast exception: Integer cannot be cast to String` | same |
| `1.5->cast(@Integer)` | `Integer[1]`, **silently truncates** | `Cast exception: Float cannot be cast to Integer` | `Float cannot be cast to Long` |
| `^A()->cast(@test::B)` | DuckDB `Could not find key "bn" in struct` | `Cast exception` | — |

`1.5->cast(@Integer)` is decisive: a Pure program using `cast` as a guard now returns a
different number. Both compilers *accept* the cast and type it as the target — so legend-lite's
static typing is right and its runtime is wrong.

**1.3 `###Pure` is not a section boundary; imports leak forward and bind the wrong class.**

`Lexer.java:278` consumes the section header and emits no token, so `ElementParser:293-320`
infers sections from the heuristic *"an `import` appeared after an element."* A section with no
import block inherits the previous section's:

```
###Pure / import a::*; / Class a::Foo{...} / Class s1::Uses{f:Foo[1];}
###Pure /               Class p::Foo{...} / Class p::Uses{f:Foo[1];}
```

Engine: `Can't find type 'Foo'`. legend-lite: binds `p::Uses.f` to **`a::Foo`** — a different
class, from a different section, silently, and compiles clean. It leaks into `###Mapping` too.

This **refutes `Compiler.java:113-117`'s javadoc**: *"Imports never leak across units: each
element resolves against its own section's scope, and the merged model's GLOBAL scope is empty."*

**1.4 Swapping two lines inside one `AssociationMapping` block changes the generated SQL.**

```
( b[a0,b0]: @J1, a[b0,a0]: @J2 )  -> ... LEFT OUTER JOIN bT t1 ON t0.fk1 = t1.pk
( a[b0,a0]: @J2, b[a0,b0]: @J1 )  -> ... LEFT OUTER JOIN bT t1 ON t0.fk2 = t1.pk
```

`AssociationSynthesis.java:417` takes `propertyMappings().get(0)` as "primary" and **both**
navigation directions use it. Different join column, **different rows**. The engine keeps one
property mapping per direction and uses the right one.

A related first-wins at `:431-433` (`if (firstJoin.joins().size() >= 2) return null;`) drops an
association binding entirely when the first entry is multi-hop. Across all 24 orderings of a
four-entry block: 12 produce two bindings, 12 produce one — with `walls={}`, no poison, no
diagnostic. The comment at `:434-441` acknowledges first-wins for predicate *content* but not
for binding *existence*.

**1.5 A generic supertype argument is dropped, so construction is unchecked and the inferred
type is a lie.**

```
^test::Box<String>(v=1).v            type-checks as String[1], evaluates to Integer 1
^test::Box<String>(v=1).v->toUpper() emits  SELECT upper(1)
```

`Class SBox extends Box<String>` yields a raw `TypeVar` as a *value type*, and `NewChecker`
unifies against the bare variable, so `unify` **binds** it rather than checking it. Type-check
says String, wire says Integer, the SQL is well-formed and wrong. This is the exact analogue of
the parser audit's divergent-tree-patched-over-by-the-wire finding.

**1.6 Resolution precedence is inverted for primitives.**

The engine checks `SPECIAL_TYPES` **first — before imports and before the `::` test**
(`CompileContext.java:239-242`), so a bare `String` is always the primitive. `NameResolver.java:469-521`
puts the prelude **last**, below wildcards and own-package. With `Class a::String` in scope:

```
engine:      The property 'n' can't be accessed on primitive types. Inferred primitive type is String
legend-lite: binds a::String, compiles clean
```

**1.7 `isSubtype` returns `true` for classes that do not exist.**

```
findClass("test::A").superClassFqns() = [test::NopeA]
isSubtype("test::A", "test::NopeA")   = true
findClass("test::NopeA").isPresent()  = false
```

`ModelIntegrity.checkClass:81-104` classifies property types, derived-property types, their
parameter types and constraint realizers — and **never touches `cd.superTypes()`**. The
extraction helper already exists (`TypeClassifier.headFqn:123`); the call is simply absent. This
corrupts the type lattice rather than merely deferring a diagnosis.

**1.8 `Relation<T>` is erased to its row type `T`.**

`InferenceKernel.resolve:621-624` unwraps `Relation<row>` to the bare row, so a relation and one
of its rows are interchangeable at every argument position: `takesRel(#>{DB.T}#->first())` is
accepted where Pure raises a type error. Corroborates the earlier relation/TDS finding; the
erasure is in `resolve`, not in the lowering.

### Tier 2 — silent structural loss

**2.1 Milestoning exists in the query surface and not in the graph.** All seven milestoned query
forms type correctly, but `findProperty(C, "businessDate")` is **empty** on a business-temporal
class, `findProperty(A, "bAllVersions")` is empty, and `findClass(".../BusinessDateMilestoning")`
is empty. The engine materializes all of these as real stereotyped properties
(`Milestoning.java:110-121, 139-149`). Anything that *enumerates* properties — graph-fetch
defaults, `ClassLayouts`, test-data generation, IDE completion — sees none of it.

**2.2 Union set-implementation identity collapses.** 433 of 1,644 declared `(class, setId)` pairs
do not survive normalization:

```
EmployeeMappingUnion  declared: [Employee#null/Union, Employee#emp1/Relational, Employee#emp2/Relational]
                     canonical: [Employee#null/RELATIONAL]
```

The engine keeps three addressable `SetImplementation`s; we keep one, tagged `RELATIONAL` — so a
union mapping and a plain relational mapping become **structurally indistinguishable**.

**2.3 Per-property mapping structure is erased.** `MappingDefinition` carries only
`(classFqn, kind, setId, extendsSetId, root, Realization.Ref)`. "Which column does property X map
to" is no longer answerable from the compiled model — proven by the static lineage analyzer
having to read the *archived legacy surface* (`ModelContext.findLegacyMapping`) because the
canonical form cannot answer it.

**2.4 Measure/Unit and SectionIndex are not model elements at all.** `Measure` is a parse-only
protocol shape; `ElementParser.parseSingleElement:377-405` has no `Measure` arm, so
`Measure t::Mass` is `unsupported top-level keyword`. `###Pure` sections produce no
`SectionIndex`.

**2.5 The auto-import set is wrong in both directions** — 46 packages against the engine's 32,
with 13 overlapping. `Class p::Uses { t: Table[1]; }` → engine `Can't find type 'Table'`;
legend-lite binds `meta::relational::metamodel::relation::Table`. And we invented an **implicit
same-package import** neither engine has; the in-code justification at `NameResolver:490-496`
cites `testUnionPartial.pure` as proving it, but that file has no import-less section — it
imports its own package *by hand* at lines 15, 304 and 332. It is evidence against the rule it
is cited for.

### Tier 3 — under-diagnosis (the engine rejects; we accept silently)

Fifteen shapes, each adjudicated against the engine's compiler:

| input | engine |
|---|---|
| `Class A extends NopeA` (unknown supertype) | `Can't find type 'test::NopeA'` |
| `Class extends` an Enum / a primitive | `Invalid supertype: 'X' cannot extend 'Y' as it is not a class.` |
| association end at an Enum / primitive | `Can't find class 'x'` |
| association with 1 or 3 ends | validated (`AssociationCompilerExtension:72-75`); **1 end → raw `IndexOutOfBoundsException`** here |
| class-hierarchy cycle | `Cycle detected in class supertype hierarchy: A -> B -> A` |
| mapping include cycle (incl. self-include) | `Cycle detected in mapping include hierarchy` |
| duplicate element FQN | `Duplicated element 'x'` — we take **last wins** |
| unknown profile / unknown stereotype | `Can't find the profile 'X'` / `Can't find stereotype ...` |
| constraint on an unknown property | rejected at thirdPass |
| derived-property type/multiplicity vs body | `Error in derived property 'A.prop' - Multiplicity error…` |
| property mapping for a nonexistent property/column | `Can't find property 'x' in [...]` |
| property/column type + multiplicity mismatch | `- Type error: …` / `- Multiplicity error: [1] doesn't subsume [2]` |
| `Runtime` naming an unknown mapping | `Can't find mapping 'test::NoSuchMapping'` |
| `Join`/`Filter` on an unknown table/column | `Can't find table 'tNOPE'` / `Can't find column 'nosuchcol'` |
| duplicate enum values | `EnumerationValidator` |

Root cause is narrow and fixable: **`ModelIntegrity.check:47-58` visits only classes, functions,
associations and mappings.** Enums, databases, runtimes, connections, services and profiles are
never integrity-checked.

The cycle cases are worse than leniency. Three superclass walks recurse with **no visited set**
(`PureModelContext.findProperty:196-201`, `ClassLayouts.collect:66-68`,
`ModelContext.isSubtype:231-236`), so `Class A extends B / Class B extends A` plus a typo'd
property name in an ordinary query throws **`StackOverflowError`** — an `Error`, on a server
thread. The guard is known: `Temporal.strategyOf:110-131` carries a `seen` set. It is just not
applied uniformly.

### Tier 4 — diagnostics, determinism, tenets

**4.1 Cross-file function overloads report the wrong file and line.**
`Compiler.parseSources:160-185` computes a dedup key that *includes the parameter shape*
(deliberately — overloads across files are legal), then writes both side indexes keyed on the
**plain FQN** with last-wins `put` (`:179`, `:185`). An error in `a.pure:1` is reported as
`b.pure [13:1]`. Classes are unaffected.

**4.2 Phase G has no positions at all.** 5,427 corpus diagnostics with zero line numbers.
`SpecCompiler.java:64-70` re-wraps with `"in function '<fqn>': "` and calls itself a "Positions
stopgap" — and it catches only `TypeInferenceException`, so the 1,786 `ResolutionException`s
escape it with **no function name and no position**.

**4.3 The documented entry point is first-error-fatal.** On a real 689-file module:

```
STRICT   Compiler.compileModel(List<ModelSource>)  ->     1 error, whole build aborted
TOLERANT Compiler.buildModule(ParsedModel)         -> 1,052 element walls in one pass
         Compiler.compileAllBodies(ctx)            -> 5,287 body walls in one pass
```

Multi-error reporting exists and is good — `wallSink` threading plus a POISON-NOT-DROP policy
(`Compiler.java:218-232`) that stops walls cascading. It just isn't on the entry point the
javadoc calls *"the single orchestration point."*

**4.4 A whole legal model is refused over one unsupported association kind.**
`Association mapping kind ModelJoin not supported` (6 files) and `Cross` (5 files) fire at Phase
E and, on the strict path, sink every class, store and mapping in the file. Per-association
isolation exists at `MappingNormalizer.java:418-437` but is gated `if (!tolerant) throw e;`.

**4.5 Tenet violations in the compile path.** `ModelBuilder`'s javadoc (`:51-55`) claims
"immutable after construction … safe to share across threads … no lazy caches"; in fact
`mappingPoisons` (`:323`) and `mixedUnions` (`:327`) are public mutable maps written after
construction, `registerMappedClass`/`retainLegacySurface` are post-hoc mutators, and
`associationEndsByOwner:647-658` is a **non-volatile lazily-built cache** — an unsynchronized
race under exactly the sharing the javadoc promises. `FunctionCompiler.SUPPRESSED_ONCE:89-90` is
process-global static mutable state, so compiling the same model twice in one JVM produces
different diagnostics the second time. And `cache/` — advertised as "the one sanctioned cache in
core/" — has **zero main-source callers**.

**4.6 Two silent-loss sites that lose rows rather than errors.**
`GraphEmission.java:1437-1447` (`catch (RuntimeException unresolvable) { return null; }`) prunes
a property's whole graph subtree, making a genuine internal bug indistinguishable from "not
mapped" — fewer columns fetched, wrong rows, no error. `FunctionCompiler.java:64-72` drops user
definitions of platform-owned FQNs and reports it **only via `System.err.println`** (10 FQNs / 53
definitions on one module); a programmatic caller cannot observe it at all.

---

## §2 — The oracle question, answered

**There is no compiler-side differential harness.** Confirmed, not inferred: `core/` and
`engine/` contain **zero** references to `org.finos.legend`, and neither pom declares an upstream
dependency. The only upstream contact is `parser-equivalence/pom.xml` (three *grammar* jars),
`pct/pom.xml`, and `nlq/`.

**But one is buildable today, offline.** Three coordinates —
`legend-engine-language-pure-compiler:4.133.0`, `legend-engine-language-pure-grammar:4.133.0`,
and `legend-engine-xt-relationalStore-grammar:4.133.0` (already a stage-1 dependency) — resolve
144 jars with **no network**. Cost: **1.3 s one-time M3 bootstrap, then ~8 ms per compile**. A
corpus sweep is minutes.

Day-one adjudication surface covers **every element kind legend-lite implements**: Class,
Enumeration, Association, Profile, Measure, Function, Mapping, **Relational**,
PackageableConnection, PackageableRuntime, DataElement, SectionIndex, ExternalFormat. Missing:
service, serviceStore, persistence, dataquality, data-space, diagram.

**And the comparable artifact already exists.** The engine's own compiler tests assert almost
nothing structural — of 815 `@Test` / 1,181 `test(...)` calls, ~44% assert a diagnostic string
and ~56% assert only "did not throw." But `TestRoundTripWithPureTransformation.java:69-77` walks
compiled elements back out of the graph:

```
PureModel -> transformPackageableElement(...) -> alloyToJSON(...) -> protocol JSON
```

All three classes ship in `legend-engine-pure-code-compiled-core-4.133.0.jar`, already on the
offline classpath. It is a genuine **post-compilation** artifact: it drops all
`sourceInformation` and **adds `fControl` — the resolved overload signature** — plus inferred
supertypes and resolved full paths. Byte-deterministic across three JVM runs. The family is
complete (`transformMapping`, `transformStore`, `transformRuntime`,
`buildPureModelContextTextFromMapping`).

**So stage 2 is the same shape as stage 1 — JSON bytes — and the surface is borrowed, not
designed.**

It would catch what stage 1 structurally cannot. Both parser Tier-1 findings were confirmed to
survive into the *compiled graph*: `1 + 2 * 3 + 4` compiles to nested 2-element collections in
the engine against our flat 3-element `plus`, and `1 < 2 + 3 * 4` compiles to
`lessThan(1, times[plus[2,3], 4])` — so the engine's mis-association is in the graph the plan is
built from, not an emission artifact.

**What must be written on our side:** `ProtocolEmitter.emit` takes `PureModelContextData` — it is
parse-side only. legend-lite has **no emitter from its compiled model**. The information is there
(`compiler/spec` records `callee().signatureKey()` per call site, the `fControl` analogue), but
the projection is new code. For scale: the entire stage-1 harness is 2,294 LOC with a 244-LOC
comparator.

**Corpus choice matters.** Not the raw `.pure` tree — measured, it is 64.2% parse but only
**19.1% compile** file-at-a-time, because those files are legend-*pure* platform source, not
engine-grammar models. The right corpus is upstream's own compiler-test strings (1,181 of them),
which `InlineSnippets.java` **already mines from exactly those Java files** for stage 1 — it just
discards the paired expected-error literal.

---

## §3 — The instruments

### 3.1 The CI gate is green because its cross-checked step does not run

`.github/workflows/gate.yml` does `actions/checkout@v4` and nothing else — no legend-engine
checkout. `Corpus.available()` (`Corpus.java:65-66`) tests `Files.isDirectory` on
`~/legend/legend-engine/...`, which does not exist on a GitHub runner, so
`RelationalCorpusRunner.java:55`'s `Assumptions.assumeTrue` **skips the test and JUnit reports
success**. The step named *"corpus sweep (self-checks vs committed scoreboard)"* is a no-op.

`parser-equivalence` and `pct` are **not in the workflow at all** — so the 19,273/19,273 and the
PCT suites never run in CI either. What CI actually enforces is the null gate and the core suite.

This is the cheapest high-value fix in the document.

### 3.2 Oracle quality: 0.5%

9,792 assertions in `core` + `engine`, attributed per file:

| category | assertions | share |
|---|---:|---:|
| (a) hand-written by the implementer — **no oracle** | 3,545 | 36.2% |
| (b) from legend-engine / legend-pure — **real oracle** | **47** | **0.5%** |
| (c) executed against a real DB | 5,745 | 58.7% |
| (d) self-consistency / structural | 455 | 4.6% |

Two corrections matter more than the split. 704 of the (c) assertions are hand-written SQL-text
pins *inside* DB tests (reattributing → a+d ≈ 43.5%, c ≈ 51.5%). And (c) is weaker than it looks:
seed data **and** expected rows were both authored here, so it proves executability, not Pure
semantics. All 47 category-(b) assertions are **frozen 2026-08-04 captures**, not live
comparisons.

**PCT is a genuine external oracle** — Maven-Central jars, nothing vendored, 1,109 run / 0 fail,
982 of 1,112 asserting a computed value. But it is a **pipeline** oracle: it serializes to
grammar text and calls `QueryService.execute` (parse → compile → plan → DuckDB), never touching a
PMCD, so **it cannot localize a fault to the compiler**. None of §1 would be caught by it. Two
real weaknesses: 88 upstream variant-function PCT tests are on the classpath but unwired, and
`ExecuteLegendLiteQuery.java:1032-1050` **rewrites legend-lite's error strings before the 22
`assertError` tests judge them**.

### 3.3 The corpus ledger, measured

```
default:  2575 run -> 2298 pass / 89 fail / 93 error / 95 shape  -> 277 non-passing
100%:     2798 run -> 2398 pass / 133 fail / 153 error / 114 shape -> 400 non-passing
```

Real and reproducible — but **not a like-for-like 484 → 400 burn-down**: the denominator moved
(2,793/2,309 then, 2,798/2,398 now). A pass asserts result rows from upstream's own
`assertEquals` literals, which *is* a genuine external oracle. Upstream's golden SQL is
explicitly discarded as advisory (`Runner.java:22-23`).

**The `ARCHITECTURE_AUDIT` §3 pattern survives.** The specific lineage arm was deleted
2026-08-06 (verified), but instrumenting `Runner.score` measured **317 passing tests carrying ≥1
unchecked assert, 55 of them invisible anywhere in the scoreboard**; 408 advisory vs 4,869
verified asserts inside passes; **29 passes assert nothing at all**. The same shape recurs at
`ExecCallFinder.java:151-156`, where *whether we check the answer depends on whether producing
our answer threw.*

### 3.4 The ratchets

**12 of 16 core guardrail values and 6 of 9 parser-equivalence ratchets sit exactly ON their
bound** — the signature of numbers re-pinned to whatever was measured.

- The corpus per-family PASS floor **reads `docs/RELATIONAL_CORPUS.md` at `:294` and overwrites
  that same file at `:381`.** Auto-advancing; zero slack is definitional, not evidence. And
  `readBaseline:509-545` **silently disarms** — unreadable file → "gate SKIPPED" → green.
- **One-directional gates that go RED on improvement:** `tools/allgates.sh:53` greps for the
  literal `"Tests run: 348, Failures: 1, Errors: 22"`. Fixing any one of those 22 errors turns
  the gate red.
- `M1_VERIFIED >= 296` — actual 319, stale since 2026-08-01, blind to a 23-test regression.
- `MIN_ELEMENTS_COMPARED` is mis-named: it asserts `all.size()`, which counts WALL and PARSE_FAIL
  as progress.
- Adding one `one(fqn, substring)` line to a PCT `expectedFailures` list turns a red test green
  with no fix, no approval, and **no ratchet on list size**.

---

## §4 — What is right

Stated because it changes the remediation, and because two axes came back healthy.

- **Diagnostics and totality are genuinely healthy — the parser's disease does not transfer.**
  ~96,000 fuzz mutants plus 3,455 real files produced **zero** raw JDK escapes from
  `compiler/`, `resolver/`, `normalizer/`, `validation/`. (Two escapes on the clean corpus are
  both in the *parser* layer and already recorded in the stage-1 audit.) 85.6% of throws route
  through the taxonomy; **82% of throw sites name the offending runtime value** against the
  parser's 11 of 122; candidate enumeration is real — `Typer.java:1427-1433` lists every overload
  with its arity, `ClassSources.java:1325-1328` names every competing mapping *and why each was
  disqualified*.
- **Determinism is demonstrated three ways**: 50 same-JVM compiles → 1 distinct message;
  full-corpus Phase G re-sweep in a fresh JVM → 5,427 rows **byte-identical**; Phase H → 1,117
  rows byte-identical. Wall maps are `LinkedHashMap`, `compileAllBodies` iterates a `TreeSet`,
  and `Compiler.dialectOf:376-379` sorts explicitly with a comment recording that first-match-wins
  was previously nondeterministic.
- **Element compile order is derived from the model, not from iteration order.** 41 permutations
  of a realistic model → 1 distinct output.
- **The schema algebra is the strongest thing in the compiler.** One 45-line evaluator
  (`InferenceKernel.resolveSchemaAlgebra:697-737`) plus a `⊆` constraint solver gives
  `select`/`rename`/`extend`/`groupBy`/`over`/`pivot` their column types, multiplicities **and**
  collision errors with zero per-operator code — verified by execution.
- **The primitive lattice is correct and could not be broken.** `Type.java:70-73` deliberately
  does not re-encode it; it walks the declared `extends` chain. `1 + 1.5 -> Number` is correct,
  not a widening bug.
- **The normalizer's poison ledger works.** Of 1,644 declared class mappings, 60 lose their
  binding — 26 walled, 34 with an explicit poison reason, **0 silent**.
- **No tightenings at element-compile scope.** Where legend-lite is stricter it is stricter
  correctly (`ModelIntegrity.checkDuplicateSignatures:112-127` matches real Pure).
- **No `ThreadLocal` anywhere** under `compiler/`, `normalizer/`, `model/` — the earlier
  lowering-layer finding does not extend here.

---

## §5 — The shipped defect: a legacy compiler gates every request

`engine/src/main/java/com/gs/legend/` is **not** a peer implementation. It is a legacy
predecessor whose back half is switched off and whose **front half is still on the live request
path**. `CoreBridge.java:18` says so: *"This class dies with the engine module; nothing gets
ported."*

Traced `POST /engine/execute` under `-verbose:class`: 297 `com.legend.lowering`, 283
`com.legend.compiler`, 92 `com.legend.resolver` … and **zero** `com.gs.legend.compiler`,
`plan.lowering`, `sqlgen` or `sql`. Only 63 of 341 engine classes load at all.

But step 3 of the request — resolving a JDBC connection (`QueryService.java:104-105`) — parses
the **whole model** through `com.gs.legend.parser.PureModelParser` first. A/B over 1,138 real
`.pure` files:

```
BOTH accept = 965      CORE only = 163 (14.3%)      ENGINE only = 1      NEITHER = 9
```

Reproduced against the running server:

```
HTTP modern: {"success":true,"data":"[{\"firstName\":\"John\"}]","rowCount":1}
HTTP legacy: {"success":false,"error":"line 4:10 Expected identifier but found BRACKET_OPEN ('[')"}
CORE direct on legacy model: OK -> Tabular[... rows=[Row[values=[John]]] ...]
```

**~14% of the relational corpus's model grammar is unreachable through `/engine/execute`** —
legacy `scope(...)` forms, `###Data`, generics — and every model is parsed twice per request.

Two further live surfaces are 100% legacy: `POST /lsp` (Studio Lite's diagnostics and
completions) and `POST /engine/diagram`. And the core/legacy switch is **per-overload, not
per-class**: `QueryService.execute(..., OutputStream, OutputFormat)`, `QueryService.stream(...)`
and `PlanGenerator.generate(PureModelBuilder,...)` run legacy, ungated. No HTTP route reaches
them today — but the next person who wires `/engine/execute` to `stream()` for large results
silently swaps compilers.

**Which implementation each gate measures — the answer is the inverse of what was suspected.**
*Every* gate measures core, including the relational corpus runner that lives in
`engine/src/test` (227 `com.legend.*` references, zero `com.gs.legend.*` outside its own package
line — it is there for a test-scope H2 dependency). The 19,273/19,273 is real and it is core's.
**What is uncertified is the engine parser that gates the request path; nothing measures it at
all.**

Recommended: port `ConnectionResolver` (184 LOC) onto the `ConnectionDefinition` core already
has, add `Compiler.resolveConnection`, and delete the detour — ~300 LOC. That removes the legacy
parser from the request path, closes the 14.3% gap, and stops double-parsing.

---

## §6 — Corrections

### 6.1 To code and its documentation

| site | claim | measured |
|---|---|---|
| `InferenceKernel.java:549-551` | `[0..1] -> [1]` leniency follows "engine convention" | **False.** Both oracles reject, with a dedicated multiplicity error |
| `Compiler.java:113-117` | "imports never leak across units" | **False** (§1.3) |
| `NameResolver.java:490-496` | `testUnionPartial.pure` proves same-package resolution | That file imports its own package **by hand**; it is evidence against |
| `ModelBuilder.java:51-55` | "immutable after construction … safe to share across threads … no lazy caches" | three counts false (§4.5) |
| `NameResolver.java:97` | classification into `LClass` | `LClass` does not exist in `core/` |
| `AsOfJoinChecker.java:19-24` | every right-side column is prefixed | `:81-82` prefixes only colliding columns — the doc describes the behaviour it says it refuses |
| `PARSER_DROP_IN_PLAN.md` §8 | `ProtocolToModel` is stage 2's input adapter | no such class; it is `com.legend.model.FromProtocol` |
| `cache/` javadoc | "the one sanctioned cache in core/" | zero main-source callers |

### 6.2 To `ARCHITECTURE_AUDIT_2026_08.md`

- **"Overload resolution has no rollback"** — **stale.** A retry loop landed in `9fc1693a`. It is
  not Pure's algorithm (no re-rank-to-confirm), but the flat statement is wrong.
- **"`GenericTypeReflection` returns the base class where the engine returns concrete leaves"** —
  **stale.** Fixed in `68d06358`; `:78-100` now emits concrete leaves. The `_`-in-package-name
  decode bug at `:86-88` survives.
- **"Overload resolution cannot be patched into correctness"** — **too pessimistic.** The scoring
  is a bounded ~120-line rewrite (§7). What cannot be retrofitted cheaply is the rollback loop.
- **The §3 half-verified-test pattern** — **survives**, in a different place (§3.3).

### 6.3 To the always-loaded documents

`AGENTS.md`, `README.md`, `FAQ.md`, `docs/frontend-architecture.md` and
`docs/pipeline-architecture.md` are all stamped `Jul 18 16:44` — **the same instant the engine
module's newest source froze.** Core has been moving since (newest source `Aug 6 17:43`).

All five describe `com.gs.legend`. `AGENTS.md`'s pipeline diagram (`:19-35`), layer-ownership
table (`:44-60`) and invariants 1/2/5 name `PureModelBuilder [model/]`, `MappingNormalizer
[compiler/]`, `TypeChecker`, `checkers/`, `MappingResolver`, `plan/lowering/`, `sqlgen/`,
`executor/`, `CompiledClass`, `PureClass`, `LClass`, `BuiltinClassRegistry`, `PropertyBuilder` —
**none of which exists in `core/src/main/java`**. `AGENTS.md` never says `core/`, `normalizer`,
`resolver`, `PureModelContext` or `ModelBuilder`. `docs/frontend-architecture.md` — which
`AGENTS.md:12` orders every session to read *"before any work touching that layer"* — has 11 hits
for legacy type names and **0** for `com.legend`.

`README.md` additionally claims "~35K lines, 3 modules" (actual: 168,665 LOC, 5 modules), an
`antlr/` package that does not exist, and an ANTLR dependency that is in no pom.

**This is the highest-leverage fix in the document after §3.1.** Five always-loaded documents
send every session — including the briefs that produced this audit — to a module nobody has
touched in three weeks.

### 6.4 To this audit's own briefs

Recorded because the refutations were among the most valuable results:

- I claimed `TEXT_SURGERY_AUDIT_2026_08.md` **exempted** import resolution. It contains zero
  occurrences of `NameResolver` or `ImportScope` — it does not exempt that path, it **does not
  cover it**.
- I relayed that `legend-engine-xt-relationalStore-compiler` is absent from `~/.m2`. **No such
  artifact exists anywhere** — the relational compiler extension lives *inside* the grammar jar,
  which is present.
- I framed prelude-collision winners as a run-to-run reproducibility defect. `String.hashCode` is
  specified; iteration order is stable across runs. The real defect is **edit-fragility**: adding
  one unrelated class flips `JoinType`'s winner.
- I framed overload tie-break-by-declaration-order as unambiguously a defect. It is
  **engine-correct** (§7).
- An agent's own first measurement showed 570 silent binding losses in normalization; it caught
  that it had compared pre-resolution short names against resolved FQNs, redid it, got 60 with 0
  silent, and flagged the error specifically because the corrected number is a *credit*.
- Another agent's first dead-code pass used class-load tracing and found zero unreachable
  classes; it then found `NoEagerTypeReferencesTest:105` does `Class.forName` over every compiled
  class, making the technique useless here, and **discarded its own result**.

---

## §7 — The decision the project must make

**legend-pure and legend-engine disagree with each other about overload resolution.** This is not
a legend-lite defect; it is an under-specified target.

`MultiHandlerFunctionExpressionBuilder.buildFunctionExpressionGraph:106` is

```java
handlers.stream().filter(h -> h.getDispatch().shouldSelect(parameters)).findFirst()
```

`findFirst()`, not `min(comparator)`. **The engine performs no ranking at all** — it takes the
first registered handler whose dispatch predicate accepts. Decisive experiment, `sel(Any[1])` vs
`sel(String[1])` applied to `'x'`:

| declaration order | legend-pure | legend-engine |
|---|---|---|
| `sel(Any)` first | `sel(String)` — most specific | **`sel(Any)`** — first declared, strictly worse |
| `sel(String)` first | `sel(String)` | `sel(String)` |

legend-pure implements a strict lexicographic comparison (`FunctionMatch.compareTo:69-102`): all
*type* matches in parameter order first, then all *multiplicity* matches, with C3-linearization
distance as the type score.

Where that leaves legend-lite:

| behaviour | vs legend-pure | vs legend-engine |
|---|---|---|
| summed score (`typeScore*20 + multScore`, `InferenceKernel:886-901`) | wrong | wrong (differently) |
| tie-break by `declIdx` (`Typer:1687-1690`) | wrong | **right** |
| `ambiguous overload` throw on `sel(A)`/`sel(B)` for a `C` | wrong | wrong |

**If drop-in engine compatibility governs**, overload resolution should be "first declared that
accepts" — inherently order-sensitive, hence not stably specifiable, which is presumably why the
engine treats user function overloading as barely supported and **forbids generic user functions
outright** (`Type and/or multiplicity parameters are not authorized in Legend Engine`).

**If Pure correctness governs**, implement `FunctionMatch.compareTo`: replace the scalar with a
comparable `(int[] typeDistances, MultDist[])`, compute distances by walking the generalization
chain instead of 3 buckets, score deferred slots as `NON_CONCRETE` instead of skipping them
(`InferenceKernel:871-873`), and make ties an error in **both** paths. ~120 lines.

You cannot have both, and the two answers differ on real programs. **Findings 1.1 and 1.2 are
unaffected** — both oracles agree there, so those remediations are unconditional.

---

## §8 — Recommended sequence

**Phase A — make the gates real. Cheapest, and everything else is judged by them.**

1. **Fix `.github/workflows/gate.yml`** — check out legend-engine (or fail loudly when the corpus
   is absent instead of `assumeTrue`-skipping), and add `parser-equivalence` and `pct`. Today CI
   green means "the null gate passed."
2. **Make `readBaseline` fail-closed** instead of skipping on an unreadable file.
3. **Delete the one-directional gate** at `tools/allgates.sh:53` — it goes red on improvement.
4. **Land the two oracle harnesses** (`/tmp/oracle/`) in the repo as a module. They are the
   instrument this stage has never had, and they already run.

**Phase B — the unconditional correctness fixes.**

5. **Reject `[0..1] -> [1]`** (`InferenceKernel:587-613`) and correct the false javadoc. Expect
   fallout: this currently type-checks throughout the corpus. Land it alone.
6. **Make `cast` checked** (`CastChecker:22-40`) — relatedness check at compile time, runtime
   failure on mismatch.
7. **Add the supertype check to `ModelIntegrity.checkClass`** — `TypeClassifier.headFqn` already
   exists; this fixes an unsound `isSubtype`.
8. **Add visited sets** to the three unguarded superclass walks. `Temporal.strategyOf` shows the
   pattern.

**Phase C — the shipped defect.**

9. **Port `ConnectionResolver` onto `ConnectionDefinition`** and delete the `PureModelBuilder`
   detour (§5). ~300 LOC, removes the legacy parser from the request path.
10. **Close the ungated seam** — route the `OutputStream`/`stream` overloads through core or make
    them throw.

**Phase D — build the stage-2 harness.**

11. Write the compiled-model projection (`fControl` analogue exists as
    `callee().signatureKey()`), compare against `transformPackageableElement -> alloyToJSON`,
    over upstream's 1,181 compiler-test strings. Reuse `InlineSnippets`, which already mines
    them.

**Phase E — the rest of §1, now measurable.** Section-import leakage (1.3), association
first-wins (1.4), generic supertype arguments (1.5), resolution precedence (1.6), the auto-import
set (2.5), the Tier-3 under-diagnosis list.

**Phase F — the decision in §7**, then whichever overload implementation follows from it.

**And separately, before any of it: rewrite `AGENTS.md` and `README.md` against `com.legend`**
(§6.3). Half a day, and it changes the correctness of every future session's starting map.

---

## §9 — Honest gaps

- **No end-to-end legend-engine execution.** `legend-engine-executionPlan-execution*` is not in
  `~/.m2` and `mvn -o` will not fetch it. For `cast` the adjudicator invoked the exact
  compiled-runtime primitive a generated plan calls; for the overload claims the question is
  entirely compile-time. But no full engine pipeline was run.
- **Version skew.** Executed oracles are legend-pure **5.88.0** and legend-engine **4.133.0** (the
  versions `pct` pins). The `~/legend/legend-pure` checkout is `5.92.1-SNAPSHOT`; the two quoted
  source sites are from the checkout and are consistent with executed 5.88.0 behaviour, but the
  revisions were not diffed.
- **Claim 3's exact 3-parameter model is not expressible in the engine** —
  `Function<{Integer[1]->String[1]}>[1]` is rejected by its grammar. Reduced to 2 parameters,
  which isolates the same question.
- **Multi-file semantics of the lite-vs-engine cross-tab.** Files were compiled individually
  while the engine bootstraps the full platform graph. Of 169 files where lite rejects and the
  engine accepts, **168 are `Unknown type` for a name defined elsewhere** — a prelude coverage
  gap, not a totality wall. Only 1 is a true over-refusal.
- **`GraphEmission.java:1445`/`1578` reachability.** They swallow silently by construction, so no
  sweep can observe them without editing source. Their *shape* is the finding; their *frequency*
  is unmeasured.
- **The 67 `IllegalStateException` sites never fired** across ~96k mutants and 3,455 files, so
  "internal-only" is consistent but not empirically confirmed.
- **Phase H is under-exercised** — each typed body was resolved against `null` and the model's
  first runtime; bodies needing a specific runtime or an `->from()` context were not driven.
- **Whether the union set-id collapse (2.2) breaks anything at execution.** Model fidelity is
  established; the scoreboard reports `calendarAggregation 92/92`, so it appears execution-neutral
  today.
- **Whether `Measure`/`Unit`/`SectionIndex` are deliberately out of scope** — no roadmap entry
  either way, and the tree is not a git repo, so there is no history to consult.
- **The "579 corpus occurrences" of polymorphic function pointers** from the earlier sweep could
  not be reproduced; `pct-corpus/` contains only `target/` and is not a module.
