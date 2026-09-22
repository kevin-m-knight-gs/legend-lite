# Untangling `core` — the homework

The audit's §1 established that `core` is one 25-package / 667-file cycle and
that two files carry 68% of the fix. This is the rest of it: **exactly which
classes move, where, in what order, what each step buys, and how to check it.**

Endpoint, simulated and verified: **39 packages, 0 cycles, 42 classes moved,
~1,036 files touched** (the moved class plus every referrer's import line).

The algorithm is three phases, and the third is the one that halves the work:

1. split every package by dependency depth — acyclic by construction, 166 packages;
2. merge bands back while acyclicity holds — 40 packages, 82 moves;
3. **for every relocated class, try putting it back**; keep it home if the graph
   is still acyclic — **40 of the 82 moves were unnecessary**, leaving 42.

Phase 3 is the refined rule made executable. It is what keeps `TypedConstraint`
with the `Typed*` family, and it also keeps `WindowFrame`, `RawSqlBoundary` and
`ExecuteOptions` exactly where they are — all depth-0 or depth-1, none of them
reached from below, none of them needing to move. Run `final.py` to reproduce.

Tools, all re-runnable against any tree:

| | |
| --- | --- |
| `package-graph.py` | package graph, cycles, rebuild blast radius; `--move` prices a refactor |
| `classgraph.py` | class-level SCCs — the irreducible co-location constraints |
| `layers.py` | prints a package's classes by dependency depth (the "topic vs layer" view) |
| `plan-moves.py` | computes the full move set; writes `move-manifest.json` |
| `sequence.py` | applies the groups one at a time and measures each |
| `untangle-plan.txt` | the final 40-package layout, every class listed |

---

## 0. Validated against bytecode

Everything below started as regex over source text. That is a hypothesis, so it
was checked against the compiler's own answer: `jdeps -v` over
`core/target/classes`, which reads bytecode.

**The regex graph was 90.3% recall, 93.3% precision** — it missed 722 real edges
and invented 484. Enough to be worth redoing, so the analysis was rebuilt on a
**union** graph, and the reason it must be a union is itself a finding:

> Neither tool alone is correct for a *build* dependency graph. `jdeps` misses
> everything the compiler erases — decisively `@Nullable`, which is
> `RetentionPolicy.CLASS` + `TYPE_USE` and therefore lives in
> `RuntimeInvisibleTypeAnnotations`, which jdeps does not walk. **jdeps reports
> 4 referrers of `Nullable`; the source has 355.** A `java_library` whose
> sources write `@com.legend.Nullable` still needs it on the compile classpath,
> so the source edge is real even though the bytecode edge is not there.
> Conversely regex misses inherited members, erased generics and implicit
> references — the 722.

On the validated union graph every structural finding holds: **one package
cycle of 25 packages / 654 classes**, 18 class-level SCCs of which only **two
span packages** (38 classes `protocol`/`protocol.spec`, 26
`parser`/`parser.section`), largest class SCC 85. The plan holds too — the same
ten move groups to the same destinations, **43 moves rather than 42** (one more
class into `resolver`), 39 packages, zero cycles, edit surface ~1,264 files.

Reproduce with `union-graph.py` and `validate-plan.py`; `jdeps-raw.txt` is the
bytecode extract they were checked against.

---

## 1. The method, and the mistake that proves it

Depth is the whole game. A class's **depth** is its longest path down the
dependency graph; depth 0 means it depends on nothing else in `core`. Every
class edge points strictly downward in depth, so:

> Split every package so that its classes occupy one depth band, and the
> package graph is acyclic **by construction**. Then merge bands back together
> while acyclicity holds, to recover readable names.

That yields 166 packages, then 40. Zero cycles, provably.

**The mistake worth recording.** The first attempt gave those 40 packages nicer
semantic names by merging across depth bands — grouping `TypedConstraint`
(depth 0) with `ModelContext` (depth 17) under one tidy `compiler.model`. The
result was 24 packages and a **231-file cycle**, because that merge
*reintroduced a straddle*: `base → compiler.model` appeared, carried by a
single class, `ExecuteOptions`.

The lesson is the rule:

> **A package name may never span a depth band.** Naming is allowed to be
> semantic, but it is not allowed to be *only* semantic. "These belong together
> conceptually" is exactly the instinct that produced the 667-file cycle.

**But the rule is sufficient, not necessary — and the difference matters.**
The depth-split is a *construction* that guarantees acyclicity; it is not a
statement that every depth-0 class must leave its package. A leaf only has to
move when something **below its package's band** depends on it, because that is
what forces an edge upward and closes a cycle.

`TypedConstraint` is the worked example. It is a pure record — `String`,
`Optional<String>`, an enum, no `com.legend` dependency at all — so it is
depth 0 while its `Typed*` siblings are depth 14-17. The mechanical split
therefore wants it in `com.legend.base`, next to `Nullable`, which is
semantically absurd: it is compiled-class metadata, not base vocabulary. And it
is unnecessary. Its only referrers are `ClassCompiler` (d22), `TypedClass`
(d16) and `resolver.GraphEmission` — all *above* it. Nothing low needs it, so
it creates no upward edge and it stays in group F with its family. Verified:
the endpoint is the same 39 packages and zero cycles either way.

The classes that genuinely must move are the ones with low-level referrers —
`Nullable` (354 referrers spanning every layer), `SourceInfo` (59),
`Multiplicity` (33), `Feature` and `RawSqlBoundary` (both reached by
`ExecuteOptions` at depth 1). **Check the referrers, not just the depth.** Run
the mechanical split to find the candidates, then keep every class whose
referrers are all above it where its meaning says it belongs.

---

## 2. The move groups

Eleven groups, each independently landable. `refs` is how many files reference
the class today — the import-edit cost.

### A — base vocabulary → `com.legend.base` *(4 classes, 366 edits)*

Only classes that low-level code actually reaches. `TypedConstraint` is depth 0
too and deliberately **not** here (§1).

| class | depth | refs | lines |
| --- | ---: | ---: | ---: |
| `Nullable` | 0 | **354** | 24 |
| `ExecuteOptions` | 1 | 6 | 61 |
| `ProgramFacts` | 0 | 2 | 40 |
| `NonNull` | 0 | 0 | 18 |

**Do this one first and alone.** It is 4 files, it is mechanical, and it takes
the largest unit from 654 files to 429. `Nullable`'s 354 referrers are the
entire edit cost of the group and every one is a one-line import change.

### B — protocol primitives → `com.legend.protocol.base` *(4, 105 edits)*
`SourceInfo` (d0, 59 refs) · `Multiplicity` (d1, 33) · `Escapes` (d0, 5) · `SpanOrigin` (d1, 4)

### C — merge `protocol` into its SCC → `com.legend.protocol.spec` *(6, 148 edits)*
`TypeExpression` (51 refs) · `Protocol` (43, **3,195 lines**) · `Realization` (14) ·
`DerivedPropertyDefinition` (13) · `ParameterDefinition` (12) · `ConstraintDefinition` (9)

Not a choice: these six plus the 32 in `protocol.spec` are one genuine 38-class
cycle. They must share a package.

### D — name resolution → `com.legend.compiler.names` *(7, 34 edits)*
`ResolvedNames` (11 refs) · `SynthFqn` (8) · `NameResolver` (3, 2,021 lines) ·
`RelationalKinds` (2) · `LiteralMapUnroll` · `DerivedProps` · `SymbolTable`

Cheapest group in the plan, and it is the one that unblocks the compiler
cluster: these are the classes `compiler.element` and `compiler.spec` reach *up*
to today.

### E — compile environment → `com.legend.compiler.env` *(11, 162 edits)*
`TypeInferenceException` (49 refs, 17 lines) · `Env` (49) · `SourceSubst` (16) ·
`CoreFn` (15) · `Bindings` (11) · `SchemaInvariantException` (3) · `Expected` ·
`SignatureMangle` · `WalledBodies` · `AlphaRename` · `TdsNullForms`

### F — typed metamodel → `com.legend.compiler.model` *(11, 183 edits)*
`ModelContext` (73 refs) · `TypedFunction` (45) · `Property` (15) ·
`TypedParameter` (10) · `TypedClass` (8) · `MilestoningStrategy` (8) ·
`TypedEnum` (4) · `TypedNominal` (3) · `StoreCompiler` (2) · `TypedElement` (2) ·
`TypedConstraint` (2)

`TypedConstraint` is depth 0 while the rest are depth 14-17, so the mechanical
split wants it in `com.legend.base`. **It stays here.** All three of its
referrers are above it, so it forces no upward edge — see §1. Compiled-class
metadata belongs with the compiled model, not with `Nullable`.

### G — relational layout facts → `com.legend.compiler.layout` *(5, 40 edits)*
`ClassLayouts` (11) · `EqualityKeys` (9) · `Temporal` (9) · `RelationalOpRows` (4) ·
`RelationalTypeInference` (2)

### H — typed leaf types *(2, 14 edits)* → `com.legend.base`
`Feature` (d0, 8 refs) · `WindowFrame` (d0, 4)

### I — resolver vocabulary → `com.legend.resolver.model` *(21, 93 edits)*
`ClassSource` (23 refs) · `TemporalContext` (8) · `RelationalRootForm` (7) ·
`Callees` (5) · `AsorRef` (4) · `PipelineWalks` (3) · `ChainNormalizer` (3) ·
+14 more, each 1-2 refs

Twenty-one classes for 93 edits — the cheapest per class in the plan, and it
separates the 72-file `lowering` target from the 58-file `resolver` target.

### J — merge `parser` into its SCC → `com.legend.parser.section` *(9, 38 edits)*
`ElementParser` (2,861 lines) · `SpecParser` (3,487) · `MappingProtocolParser` (3,497) ·
`SectionGrammarRegistry` · `PmcdParser` · `RelationIslands` · `ServiceStubDataParser` ·
`QuotedSpecParser` · `ServiceLegacyMappingParser`

Not a choice either: 25 classes, one genuine cycle. Note the file sizes — this
group is 9 moves but the three big parsers are 9,845 lines between them, so it
is the group most likely to collide with other work. Land it when the parser is
quiet.

### K — sql leaf types → `com.legend.sql.base` *(2, 10 edits)*
`DialectCapability` (5 refs) · `RawSqlBoundary` (3)

---

## 3. What each step buys

Measured cumulatively by `sequence.py` — `biggest` is the largest single
compile unit, which is what bounds incremental build time.

| step | units | cycles | biggest | units <100 files | files edited |
| --- | ---: | ---: | ---: | ---: | ---: |
| today | 7 | 1 | **654** | 4/7 | — |
| A base vocabulary | 15 | 2 | **429** | 10/15 | 366 |
| B protocol primitives | 15 | 2 | 429 | 10/15 | 105 |
| C merge protocol SCC | 15 | 2 | 429 | 10/15 | 148 |
| D name resolution | 15 | 2 | 429 | 10/15 | 34 |
| E compile environment | 15 | 2 | 429 | 10/15 | 162 |
| F typed metamodel | 16 | 3 | **344** | 10/16 | 183 |
| G layout facts | 19 | 2 | 344 | 12/19 | 40 |
| H typed leaf types | 20 | 2 | **257** | 12/20 | 14 |
| I resolver vocabulary | 22 | 1 | 257 | 15/22 | 93 |
| J merge parser SCC | 22 | 1 | 257 | 15/22 | 38 |
| K sql leaf types | 39 | **0** | **none** | 16/39 | 10 |

**A–K reaches zero.** 39 packages, no cycles, nothing left to untangle.

Two things the table says that the endpoint does not. **The value is
front-loaded and lumpy** — A alone takes the biggest unit from 654 to 429, a
third of the whole reduction, for four files. And **B, C, D and E move the
cycle count without moving `biggest` at all.** They are prerequisites for F, H
and K rather than wins in themselves; anyone judging them by the `biggest`
column will conclude they failed and stop four steps before the payoff. The
last group, K, is two classes and ten edits, and it is the one that closes the
final cycle — 229 files to zero.

*An earlier revision of this table reported A–K stopping at a 231-file cycle.
That was a bug in the group definitions, not a property of the plan: two
depth-0 classes (`TypedConstraint`, and `Feature`/`WindowFrame`) had been
filed into depth-14+ destinations, which is precisely the straddle §1 warns
about. The rule caught its author. Both now go to `com.legend.base`.
`TypedConstraint` was swept into that same correction and should not have been
— it is depth 0 but has no low-level referrer, so it stays with the `Typed*`
family. Same endpoint, 39 packages and zero cycles, either way.*

---

## 4. How to check each step

A move is correct when all four hold. Steps are independent, so a failure
reverts one group, not the plan.

1. **The graph improved.** `python3 package-graph.py core/src/main/java` —
   `units` must not fall and `biggest` must not rise. Both numbers are in the
   table above; a step that misses its row did something other than what was
   planned.
2. **The build is green.** `mvn -o -pl core clean test` — `clean` is load-bearing
   (NullAway binds to `default-compile`). Expect ~4,467 tests, 67 s.
3. **Nothing but imports changed.** `git diff -U0 | grep '^[+-]' | grep -v '^[+-]\(import\|package\)'`
   should be empty for every group except C and J, where the merged packages
   lose now-redundant imports.
4. **The gates still pass** for any group touching parser (J), protocol (B, C)
   or the compiler (D-H): `GATES=1,8 tools/allgates.sh`, because those are the
   byte-exact parity gates most likely to notice an accidental behaviour change.

## 5. Sequencing against the Bazel work

Group **A belongs in Bazel phase 1, before the first BUILD file** — without it
`core` is one 667-file `java_library` and no BUILD layout means anything.

Groups **B-K belong after Bazel lands, not before.** The reason is the point of
the whole exercise: these 24 packages drifted because nothing ever said no.
Under Maven, filing `Json` in `server` costs nothing at build time. Under Bazel
a package is a target with declared `deps`, so the same mistake is a cycle and
**the build refuses to load**. Doing the re-layering first fixes today's graph
and leaves tomorrow's unguarded; doing it after means each group lands against a
build that cannot regress.
