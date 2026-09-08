# The prelude as a module — tenets, homework, census, plan (2026-09-08)

Status: HOMEWORK, written after batch 150 and one aborted afternoon of implementation (see §7). READ THIS FIRST before touching
`Prelude.java`, `PreludeGeneratorTest`, or the boot layer. Companion: `docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md` §10 (the decision),
`docs/HAND_SHAPE_DIVERGENCE_2026_09_08.md` (the 84 hand shapes), `docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv` (one row per declaration).

## 1. The question that started it

USER: "Why do we have two different ways to do derived properties? Why doesn't everything go through the user pipeline?"
Because the generated prelude lives in `Pure.java`'s static catalog — one `Pure.nativeClass("…")` per declaration, parsed alone, no
imports, no resolver, no normalizer, no integrity check — while user classes go parse → resolve → normalize → build. So a prelude
class's derived property needed a second lift path (`FunctionCompiler`'s on-demand lift) and its body would have needed a printer,
and nothing ever validated a prelude declaration's references. `SystemMetamodel` already IS system Pure through the user pipeline
(the boot layer). The prelude should be too.

## 2. The tenets (USER-ratified in discussion 2026-09-08; the words are the user's questions answered)

**T1 — The prelude is what exists before any program is written.** The language's own definitions (legend-pure's platform
packages: m3, the functions' shapes, the dsl and store metamodels) plus the platform's JAVA VOCABULARY (the shapes our Java
constructs, types against, or dispatches on), plus the closure of what those declarations name. Nothing else.

**T2 — The graph is everything a program declares or brings in as a library, by file.** Engine modules are programs
(WORLD_MAP rule 8); their classes are graph material admitted through `Corpus.LIBRARY_FILES`. "A corpus test names it" is NOT a
reason to be in the prelude — that is a library-admission decision on the graph side.

**T3 — A prelude declaration may only name prelude or catalog types.** If it names a graph class, that class is platform
vocabulary too (Java's reach goes through it) and moves to the prelude; the graph's copy yields. Example: `SQLExecutionNode` (plan
lowering constructs it) names `SQLResultColumn` → both prelude.

**T4 — A name declared on both sides is a modeling error, not a contest.** Transitionally the prelude wins with a receipt list
that burns to zero (the catalog-first lookup did this silently for two months). End state: the two sets are disjoint by construction.

**T5 — The module is a closed library the boot layer checks.** Every type a declaration names resolves inside prelude ∪ catalog;
every constraint, derived property, stereotype, tagged value and default the spec declares is carried; the normalizer lifts derived
properties and constraints exactly as for a user class.

## 3. Why the corpus declares the same things (the user's "dumb question", which is the crux)

The corpus is not a folder of tests. It is the engine's source module `core_relational/relational/…`: test functions, the engine's
library programs those tests call (the SQL printer, post-processors, `toPostgresModel`), and the class declarations those programs
use — one tree. A test compiles the tree's non-test elements as its model. Two overlaps follow: CLASSES, because the generator copied
shapes out of that same tree by demand (one declaration, two containers; catalog-first papered it over); FUNCTIONS, because the
engine writes in Pure what a platform may implement natively (`pathToElement`, `average`, `toSQL`) — the spec marks them
`<<PCT.function>>` for exactly that reason, and the by-name suppression rule picks the native. T1/T2 dissolve the class half.
The function half stays a rule.

## 4. The census (tools: `PreludeGeneratorTest -Dprelude.census=1` → `target/prelude-census.tsv`; snapshot in this folder)

Over the 569 declarations the generator wants TODAY (demand = Java names + corpus names, closure over references):

| | count |
|---|---|
| declarations | 569 (537 classes, 32 enums) |
| from legend-pure | 186 (Java-demanded 84, corpus-demanded 93, closure 9) |
| from legend-engine | 383 (Java-demanded 59, corpus-demanded 253, closure 71) |
| declared by the corpus tree too (T4 duplicates) | 61 |
| classes with constraints | 13 (16 constraints) |
| classes with derived properties | 38 (136 properties) |
| classes with stereotypes / tagged values | 35 / 3 |
| classes with property defaults | 13 |
| references outside prelude ∪ catalog after the relaxed closure | 0 |

Under T1/T2 the prelude's demand becomes: legend-pure's platform packages (the census's nine roots, ~363 classes) + the 59+84
Java-demanded shapes + closure. The 253 corpus-demanded engine classes LEAVE the prelude for library admission — a phase of its own
(§6 phase 3) because it can move pass counts.

## 5. What the boot layer checks that the catalog never did

Running a declaration through the user pipeline means: (a) every type it names must resolve (`TypeClassifier`: "Unknown type: … is
not a known primitive, class, or enum" — the failure that ended the first implementation attempt, `SqlFunctionTest.setupData :
tests::utils::TestSetupData[*]`); (b) association ends and injected properties are validated (`ModelNormalizer`); (c) duplicates are
refused (`ModelBuilder`, D6b) — hence T4's explicit drop; (d) derived properties and constraints are LIFTED to functions and typed on
demand — so the census's typing list will grow by whatever those 136 + 16 bodies cannot type (the truth, wanted).

## 6. The decision on emission, and the phases

**Emission = the spec's declaration text VERBATIM, under the spec file's imports.** The generator re-prints stored properties with
fully qualified names only because the catalog had no imports. A module has `###Pure` sections with imports, so the whole class
declaration — constraints, stereotypes, tagged values, derived properties, defaults — is copied as written and the resolver
qualifies it like user code. Half the slicing code written on 2026-09-08 (derived-text, constraint-text, tagged-value regexes)
exists only because emission was still re-printing. Delete it; copy the declaration.

Phases, one batch each, lanes exact between them:
1. **Mechanism.** `prelude.pure` written by the generator (verbatim declarations, per-file sections + imports), a small hand-written
   `Prelude.java` reader, the boot layer merging it beside the system metamodel (one hash, one cache), the resolver's bare-name
   fallback knowing its names, T4's drop rule, the pins widened (`headlineNativeClassesAreAllPresent`,
   `everyTypePositionFqnInNativeSignaturesResolvesToCatalog` — the platform universe is catalog ∪ module). Demand UNCHANGED (today's
   Java + corpus) so the only variable is the container. Expected: pass counts unchanged; census 3 → 0 for the derived rows, plus
   whatever the 136 derived / 16 constraint bodies surface.
2. **Migrate the 84 hand shapes** out of `Pure.java` a family at a time (HAND_SHAPE_DIVERGENCE §4).
3. **Re-scope demand to T1/T2**: drop "corpus names it"; the 253 engine classes become `LIBRARY_FILES` admissions by file under
   rule 8; T4's receipt list burns to zero. The lanes decide the pace.
4. **The bootstrap handful** and the store-shaped divergences (HAND_SHAPE_DIVERGENCE §4 steps 3–4).
5. `tools/shape_sweep.py` and the census as pins; `FunctionCompiler`'s on-demand lift deleted.

## 7. What happened on 2026-09-08 and what survives (branch `wip/prelude-module`)

The mechanism was started before this homework existed and went five probe cycles deep (derived-text slicing, tagged-value regexes,
escaping references, corpus-tree pulls, constraint blocks, header braces) — the pattern the "clean sheet after three cycles" ruling
names. Stopped; this document is the design. The WIP is parked on `wip/prelude-module` (never merged as is). What survives from it:
`Prelude.java` as a reader of `prelude.pure`; `Compiler.bootLayer`/`bootFqns`/`withoutPreludeShadows`; the resolver's
`platformTypeFqns()`; the removal of `Prelude.load()` from `Pure.java`; the two widened pins; the census mode. What does NOT: every
re-printing/slicing helper (`derivedText`, `constraintsText`, `escapingReference`, the omitted list) — replaced by verbatim copy.

## 8. Wiring map for phase 1 (file → what changes; the branch shows a first cut of each)

| where | today | phase 1 |
|---|---|---|
| `PreludeGeneratorTest.generate()` | prints `Pure.nativeClass("…")` Java lines; `OUT = src/main/java/…/Prelude.java`; `printClass` re-prints stored properties FQN-qualified and DROPS derived properties, constraints, non-Key stereotypes | writes `OUT = src/main/resources/com/legend/builtin/prelude.pure`: header comment, one `###Pure` section per spec file (the file's `import …;` lines, from `importsOf(text)`), then each wanted declaration's text VERBATIM (`declarationText(source, offset)` already slices it; keep the whole thing), enums as today. Round-trip: the whole module parses (`ElementParser.parse(text, LEGEND_PLATFORM).elements().size() == classes + enums`). Parity test compares to the resource; `-Dprelude.generate=1` writes it. Keep `-Dprelude.census=1`. |
| `Prelude.java` | generated Java with `CLASSES`/`ENUMS` lists registered into the catalog via `Prelude.load()` | HAND-WRITTEN reader: `source()` (resource), `parsedModel()` (parsed once, LEGEND_PLATFORM), `elements()`, `elementFqns()`, `classFqns()`, `enumFqns()`, `cls(fqn)`, `enumOf(fqn)` (the last two keep `NativeFunctionTest`'s existing calls working) |
| `Pure.java` static block `Prelude.load()` | registers the prelude into the catalog index | deleted; comment points at §10 |
| `Compiler.bootLayer()` | normalizes `SystemMetamodel.elements()` once (content-addressed by its source hash) | merges `SystemMetamodel.elements()` + `Prelude.parsedModel().elements()` into ONE `ParsedModel` carrying the prelude's `elementOffsets/elementImports/elementSources` (the section imports resolve the derived bodies); hash = both sources |
| `Compiler` lines 231 and 336 (`resolveAlongside(parsed, SystemMetamodel.elementFqns(), …)`) | the graph resolves against the system metamodel's names | against `bootFqns()` = system ∪ prelude |
| `Compiler.normalizeWithSystem` | `SystemMetamodel.withoutSystemShadows(resolved)` | also `withoutPreludeShadows` first: a graph CLASS or ENUM whose FQN is a prelude class/enum is dropped (T4, prelude wins; the census's spec files and the corpus tree's 61 copies) |
| `NameResolver` `preludeTypes()`, `preludeCollisions()`, `knownFqns()`, `resolveQuery()`, `querycope()` | built from `Pure.nativeClassFqns()` + `nativeEnumFqns()` | from `platformTypeFqns()` = catalog ∪ `Prelude.classFqns()` ∪ `Prelude.enumFqns()` |
| `TypeClassifier.classDef` (catalog first, then model) and the other catalog askers (`UnionSynthesis` ×3, `MappingNormalizer.classDef`, `RequiredNullableCensus`, `PureModelContext` classifier instances, `FunctionCompiler`'s derived lift) | find prelude classes in the catalog | unchanged code: prelude classes are now MODEL elements (boot layer), so the model branch finds them; `Pure.findNativeClass` returns only hand shapes |
| `NativeFunctionTest.headlineNativeClassesAreAllPresent`, `everyTypePositionFqnInNativeSignaturesResolvesToCatalog` | universe = `Pure.allNativeClasses()/Enums()` | universe = catalog ∪ `Prelude.classFqns()/enumFqns()` |
| `PreludeGeneratorTest.excluded()` / the closure loop | the `.*::tests?::.*` rule keeps spec test packages out; the closure skips `excluded(ref)` and `corpusDefined` | keep TODAY'S demand for phase 1; the closure admits a referenced type when the spec declares it and it is not a DECIDED exclusion (`SqlFunction.tests : SqlFunctionTest[*]` names a tests:: class — part of the shape); a corpus-tree class the closure needs is admitted and listed (T4 receipt) |

Commands (always with the literal roots):
```
mvn -q -o test -pl core -Dtest=PreludeGeneratorTest -Dsurefire.excludedGroups= -Dprelude.generate=1 -Dlegend.engine.root=/Users/neemsandv/legend/legend-engine -Dlegend.pure.root=/Users/neemsandv/legend/legend-pure
mvn -q -o test -pl core -Dtest=PreludeGeneratorTest -Dsurefire.excludedGroups= -Dprelude.census=1   (same roots)  → target/prelude-census.tsv
mvn -q -o test -pl core -Dtest=SpecBodyCensusTest -Dsurefire.excludedGroups= (same roots)              → target/spec-body-census.txt
lanes / guards / chain: docs/GATES.md and memory (harness-iteration-speed); LEGEND_LITE_PROGRESS=1 names a hanging corpus test
```

## 9. Open checks (found while probing; not decided — decide in phase 1, write the answer here)

1. **`native` or not.** Spec declarations are `Class …`; copied verbatim they are ordinary classes in the boot layer, not `native Class`.
   Who reads `isNative()` on a CLASS: `FromProtocol`, `ClassCompiler`, `NameResolver`, `ModelNormalizer` (pass-through) and
   `Pure.nativeClass` (catalog only). Expected: nothing depends on it for prelude classes — verify by grep before deciding; if the
   pins (`everyNativeClassIsMarkedNativeAndHasEmptyBodyOutsideTheDocumentedSurface`) reach module classes, they are catalog pins and
   must scope to the catalog.
2. **Stereotypes and profiles in verbatim text.** Spec classes carry `<<doc.doc>>`, `{doc.doc = '…'}`, `<<equality.Key>>`,
   `<<meta::pure::profiles::…>>`. The census parses these files, so the parser accepts them; the equality-key filter in `printClass`
   (Key only) goes away with re-printing — check `ClassLayouts`/equality consumers read the stereotype by profile+name, not by the
   printed spelling.
3. **Dialect.** `SystemMetamodel` parses as LEGEND_LITE; the prelude as LEGEND_PLATFORM (legend-pure grammar: `|m` parameters,
   `Function<{…}>`). One boot `ParsedModel` from two dialects is fine (elements, not text, are merged) — confirm the resolver's
   per-element import scopes survive the merge (`elementImports` keyed by FQN).
4. **The 136 derived + 16 constraint bodies now TYPE.** The census's typing list will show whatever they cannot type; those are new,
   honest rows (§5). Do not hide them behind an exclusion.
5. **`SetImplementation`/`Mapping` are HAND shapes with system-store rows; prelude classes referencing them resolve to the catalog** —
   fine in phase 1; phase 2 migrates them.
6. **Performance.** ~450 more boot elements normalized once per process; per graph, indexing only. Measure the first compile in the
   lane log (the 2026-09-02 budget entry method) and record it.
