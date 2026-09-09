# The prelude as a module — tenets, homework, census, plan (2026-09-08)

Status: HOMEWORK, written after batch 150 and one aborted afternoon of implementation (see §7). READ THIS FIRST before touching
`Prelude.java`, `PreludeGeneratorTest`, or the boot layer. Companion: `docs/SYSTEM_PRELUDE_DESIGN_2026_09_08.md` §10 (the decision),
`docs/HAND_SHAPE_DIVERGENCE_2026_09_08.md` (the 84 hand shapes), `docs/PRELUDE_MODULE_CENSUS_2026_09_08.tsv` (one row per declaration).

## 0. The road here (2026-09-08, in order — each link is where the detail lives)

1. **Batch 148** (29d1e9789): the system-prelude DESIGN (`SYSTEM_PRELUDE_DESIGN` §1–9: the prelude is generated system Pure; native = signature +
   lowering or a named wall; function/derived = a program) and the first CENSUS of every Pure body in legend-pure's platform packages —
   481 typed / 643 failed; one kernel rule (a function's own type parameters rigid inside its body) explained 605 → fixed: 950 / 174.
2. **Batch 149** (9a72c7089): 174 → 36 — fifteen hand shapes and 36 natives spec-exact, supertype instantiation in the kernel
   (`asSuper`), rigid type-parameter frames. Two lane-caught regressions (class LAYOUT picking up `Class.properties`; a system view's
   carrier). A ten-minute HANG once the engine's post-processor programs typed: USER walled that machinery by name pending a design
   session; the inliner got an unroll budget (20,000) and an ancestor index. `SPEC_BODY_CENSUS` §8, `LEDGER_GRANULAR` §22.
3. **Batch 150** (1555b13ce): 36 → 3 — units whole, packages as values, the PCT harness, the receiver-owned `_this` routing (narrowed
   twice after it hijacked `tableToTDS` and `cast`: 66 tests lost then recovered; a stash bisect against HEAD then per-file), eval's
   run-time multiplicity, no-branch match as the raise. `SPEC_BODY_CENSUS` §9, `LEDGER_GRANULAR` §23.
4. **The last 3 rows** are derived properties of generated shapes → USER: "why two ways to do derived properties?" → this document.
5. **The hand-shape sweep** (338baeb98, `HAND_SHAPE_DIVERGENCE`): 84 hand shapes in `Pure.java`, 48 exact, 30 real divergences by
   kind and cause; the plan that makes them dissolve on migration.
6. **The module attempt** (branch `wip/prelude-module`): five probe cycles before this homework — stopped by the clean-sheet rule.

## 0a. Questions asked on the way and the answers (the user's words in quotes)

- "What is the 17 vs the 174?" — two units: 174 was BODIES that fail to type (one row each); 17 was distinct DEFINITIONS (missing
  properties) behind one bucket — fixing one definition clears several rows. The census's rows are bodies; its work list groups them by
  the definition to add.
- "Why do we have two different ways to do derived properties?" — §1. User classes: the normalizer lifts them. Catalog classes: an
  on-demand lift in `FunctionCompiler`, because the catalog bypasses the pipeline. A seam, not a design.
- "Why do we have things in Pure.java that are more than native signatures?" — 84 hand shapes, all spec shapes typed in by witness
  before the generator existed; only the bootstrap handful Java constructs before a model exists has a reason to stay
  (`HAND_SHAPE_DIVERGENCE` §3–4).
- "Why would anything diverge from the real spec?" — three causes: written by witness (not decisions), a platform limitation of the
  day (`Any`'s layout, `Column<T,X|z>` before multiplicity parameters — both gone or one rule away), and the system store's row shape
  (the only platform reason; two store legs). Every divergence carries a receipt and a leg; none is permanent.
- "What is the tenet that pulls something into the prelude vs reads from the graph?" — §2 T1–T5.
- "What does unknown or graph-owned mean?" — §10 glossary.
- "Why would the corpus also define the function?" — §3.
- "Am I making a mistake forcing the prelude through this?" — §0b: the alternative (a printer) was considered and rejected.
- "What does mechanism-only as its own batch mean?" — one unit of work ending with the chain green and a commit that changes HOW the
  prelude is loaded and nothing about WHAT is in it — so if the lanes move, the cause is the container, not a shape (the day's
  receiver-routing bisect is the lesson: never two changes in one bisect).

## 0b. The alternative considered and rejected: a protocol-to-Pure printer

Keep the catalog; write a printer over the ~30 protocol record kinds so the generator can emit derived bodies FQN-qualified. Lands the
last 3 rows in a day. Rejected because it leaves two lift paths, leaves 84 hand shapes in a container that bypasses the pipeline, and
the next divergence gets fixed by editing a Java string again — a local patch on a structural seam. The module removes a mechanism;
the printer adds one. USER: "I want to do the right architectural thing — I don't mind it taking longer to get to the right place."

## 0c. END STATE (what "done" looks like)

- `Pure.java` = native function signatures + `Pure.Lite` + the bootstrap handful (what Java constructs before a model exists), each
  hand shape with a receipt naming the Java that needs it. Nothing else declared by hand.
- `prelude.pure` = legend-pure's platform packages + the platform's Java vocabulary + closure, copied VERBATIM from the spec with
  imports, generated and pinned; compiled through the user pipeline as the boot layer. Constraints, derived properties, stereotypes:
  all present, all lifted like a user class's.
- The engine's classes live in the graph, admitted by file under rule 8; prelude and graph name sets are disjoint (T4 list at zero).
- `FunctionCompiler`'s on-demand derived lift deleted; `TypeClassifier.classDef`'s catalog-first order gone with it.
- The census types every spec body (0 failures) and every derived/constraint body the prelude carries; `tools/shape_sweep.py` and the
  declaration census are shrink-only pins; every divergence from the spec is a receipt with a leg, never a silent hand edit.

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

**T1 sharpened (USER 2026-09-08, after batch 152): platform vocabulary is decided by USE, never by provenance.** A class is
prelude material when the platform's own Java constructs it, reads it, or names it in a native signature — whichever
checkout declares it. Fifty-nine engine-declared classes (the TDS result, the JSON tree, connections and runtime, plan
nodes, the platform's own output records) are the platform's interface types and belong in the prelude with a receipt
naming that Java site; an engine class only programs use stays in the graph even when every corpus test names it. The
test is the design's question 4, not the file's repository. A Java MENTION (a comment, a dispatch string) is not use:
phase 3 tightens the generator's demand to construct / read / signature.

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
1. **Mechanism — LANDED 2026-09-08 (batch 151; §9 carries every decision and the four findings).** Pass counts unchanged
   (DuckDB 2442/108/14/11, H2 1990/565/14/6, channel B 314/13, 355, 137, 95, 204; G1 4377); census 1128/3 → 1226/22 (§9.4).
   Branch `wip/prelude-module` is superseded by this landing (never merged; delete at leisure). The Any-to-text leg (§9.15)
   LANDED as batch 152: `Pair`/`List` `toString` run as bodies, Scalars' two Java arms are gone, the census is pinned.
   NEXT: phase 2 (below), then phase 3.
   As planned: `prelude.pure` written by the generator (verbatim declarations, per-file sections + imports), a small hand-written
   `Prelude.java` reader, the boot layer merging it beside the system metamodel (one hash, one cache), the resolver's bare-name
   fallback knowing its names, T4's drop rule, the pins widened (`headlineNativeClassesAreAllPresent`,
   `everyTypePositionFqnInNativeSignaturesResolvesToCatalog` — the platform universe is catalog ∪ module). Demand UNCHANGED (today's
   Java + corpus) so the only variable is the container. Expected: pass counts unchanged; census 3 → 0 for the derived rows, plus
   whatever the 136 derived / 16 constraint bodies surface.
2. **Migrate the 84 hand shapes** out of `Pure.java` a family at a time (HAND_SHAPE_DIVERGENCE §4).
3. **Re-scope demand to T1/T2 — LANDED as batches 154 + 155 (2026-09-08, `PHASE3_DEMAND_CUT_HOMEWORK`).** Demand = T1 alone;
   legend-pure's platform packages whole (+41); the 253 corpus-only engine classes left for `Corpus.SHAPE_FILES` (declarations
   only, D1); prelude 569 → 357. T4's receipt list does NOT burn to zero (D2): it is the stable list of vocabulary classes the
   graph's own files also declare (119), pinned shrink-only by parity. Pass counts unchanged; census 22 → 19.
4. **The bootstrap handful** and the store-shaped divergences (HAND_SHAPE_DIVERGENCE §4 steps 3–4).
5. `tools/shape_sweep.py` and the census as pins; `FunctionCompiler`'s on-demand lift deleted.

## 6a. RATIFIED 2026-09-08 (after batch 152) — the order and the open decisions, USER: "let's ratify all and go"

1. **Phase 3 before phase 2.** The demand cut (T1-by-use, §2) settles what the prelude IS — removes the 253 corpus-only engine
   classes, most of the 48 collisions, 18 of the 22 census rows — before hand shapes migrate into it.
2. **The bare-name leg is its own batch, FIRST (batch 153) — LANDED, one lite test moved, nothing else.** A bare name resolves through the section's imports, the core imports
   and its own package, or fails with the engine's message; the resolver's fallback tier (`PRELUDE_TYPES`/`PRELUDE_COLLISIONS`)
   is deleted; lite tests that leaned on it get their imports. Done before phase 3 so phase 3's lane movement is demand alone.
3. **Vocabulary by the spec's marking:** `native function` → Pure.java signature + one lowering or a named wall; a Pure-bodied
   `function` → generated body. `mutateAdd` is a wall (mutation, no SQL meaning).
4. **What stays by hand after phase 2:** a shape survives only with a receipt naming the Java line that constructs it before a
   model exists (the primitives, Any/Nil, Class<T>, Relation<T>, the ColSpec family the typer builds; the six store-coupled shapes
   until their store legs). "Java dispatches on its FQN" is not a receipt — the module serves that.
5. **Platform vocabulary by use, never provenance** (T1 sharpened, §2).
6. **Text natives beyond `format`** (`makeString`, `joinStrings` over class values): the same rule as `format` when a witness asks.
7. **Closure = option B** (§9a).

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

## 9. Open checks — DECIDED 2026-09-08 (phase 1), each answer with its receipt

1. **`native` or not → NOT native.** The module copies the spec's `Class …` verbatim; a prelude class is an ordinary class of the
   boot layer. Receipt (grep of `isNative()` on classes, 2026-09-08): `FromProtocol:373`, `NameResolver:706`, `ModelNormalizer:199`
   copy the flag through; `ClassCompiler:101` stores it on `TypedClass.isNative`, which NO code reads (its javadoc already says
   "bootstrap native classes from builtin/Pure"); `Pure.nativeClass` (Pure.java:161) demands it — the catalog's door, which the module
   no longer passes through. The four `NativeFunctionTest` pins that iterate `Pure.allNativeClasses()` and skip
   `Prelude.classFqns()` are catalog pins by construction once the module leaves the catalog: their skip lines are dead and are
   removed; the hand count is simply the catalog's size.
2. **Stereotypes and profiles → verbatim, no filter.** `ClassCompiler:55-59` reads the equality key as
   `PlatformTypes.isProfile(profile, EQUALITY_PROFILE) && "Key".equals(name)` — profile + name, never the printed spelling. The
   resolver qualifies stereotype and tagged-value profile names through `resolveName` (`NameResolver:1570/1581`) and leaves an
   unresolvable one as written (no wall) — the census already parses and resolves these files through the same path. The Key-only
   filter of `printClass` dies with the printer.
3. **Dialect → one boot `ParsedModel`, two dialects, fine.** `NameResolver.resolve` scopes each element by
   `model.elementImports().get(fqn)` and falls back to `model.imports()` (NameResolver:249-255). The boot model is built from the
   prelude's parsed model (its `elementOffsets/elementImports/elementSources`) plus the system metamodel's elements, which have no
   per-element entry and therefore resolve in the empty scope exactly as today. Nothing text-level is merged.
4. **The 136 derived + 16 constraint bodies TYPE — MEASURED.** `SpecBodyCensusTest` after phase 1: **1226 typed / 22 failed**
   (was 1128 / 3; load walls 6 unchanged). The 3 derived rows (`TableAlias.relation` ×2, `GraphFetchTree.propertyTrees`) are
   GONE. The 22 new rows are all boot-layer bodies the module now carries, honest and listed
   (`docs/SPEC_BODY_CENSUS_2026_09_08.md` §10): 18 unknown functions in engine-internal derived/constraint bodies
   (`removeAll` ×4, `createSchemaState` ×2, `checkSuperType` ×2, `sqlQueryToString`, `processOperation`, `mutateAdd`, `indent`,
   `getLiteralProcessorForType`, `forgivingPathToElement`, `containsAll`, `collectionMultiExecutionContexts` — the
   `SchemaState`, `DbConfig`, `Extension`, `ExternalFormat*Descriptor` families), 3 unknown types (`PureMultiExecution`,
   `DynaFunctionRegistry` — classes outside today's demand), 1 normalize (`SchemaState.extend`). Nothing excluded.
5. **`SetImplementation`/`Mapping` stay HAND shapes.** A prelude declaration naming them resolves to the catalog because the boot
   resolution's known set is `bootFqns()` ∪ the resolver's `knownFqns` (which already carries `Pure.nativeClassFqns()`), and
   `TypeClassifier.classDef` still asks the catalog first. Phase 2 migrates them.
6. **Performance → MEASURED.** Method: `Compiler.compileModel` of a one-class model in a fresh JVM, jshell over the core
   classpath, HEAD (a scratch worktree) vs the module. First compile (boot layer compiled on that call): HEAD 339–354 ms, module
   369–381 ms — **about +25 ms once per process** for the ~570 extra boot elements; second compile 32–37 ms both. The chain:
   6m03s (G1 50, G2 8, G4 61, G5 39, G6 85, G7 27, G9 19, G8 74) against batch 150's 6m00s — inside the wobble.

Decided while implementing (2026-09-08):

7. **Verbatim = PARSER-DELIMITED, never brace-matched.** The declaration's text is `source[tokens.start(i) .. tokens.end(j))` where
   `i` is the element's first token (the parser's own `elementOffsets`) and `j` is where `ElementParser.at(tokens, i, LEGEND_PLATFORM)
   .parseClassDefinition(false)` / `.parseEnumDefinition()` leaves the cursor. A header tagged-value block `{doc.doc = '…'}`, a
   constraint block, a string literal holding a brace — all handled by the parser that will read the module, not by a regex. The
   branch's `declarationText` (first `{` after the offset, brace count) was wrong for any class with a tagged value in its header.
8. **Enums verbatim too** (§8 said "as today"): one rule for every declaration; an enum's `<<doc.doc>>` rides with it.
9. **Closure = the relaxed rule of the branch, with no OMITTED list.** A type a wanted declaration names (supertype, stored or
   derived property type, derived parameter type) is admitted when the spec declares it and it is not a DECIDED exclusion
   (`EXCLUDED_CLASSES`, the versioned protocol packages, m3 paths); the spec-test-package rule applies to DEMAND only. That is what
   makes the module CLOSED (T5) and the census's "references outside prelude ∪ catalog = 0" true; a reference that still escapes is
   a generator ERROR (the dangling check), never a silently omitted class. A corpus-tree class pulled this way is listed at the foot
   of `prelude.pure` — the T4 receipt list phase 3 burns.
9a. **CLOSURE — USER RULING 2026-09-08 (option B): declarations closed, bodies resolved where they run.** The closure follows a
    class's DECLARATION (supertypes, property types, derived-property parameter and result types) and never its BODIES (derived
    properties, constraints). What a body names resolves when the body is used, against everything loaded then — prelude, catalog,
    system metamodel, the program's own graph — as a real Pure qualified property resolves against the whole loaded world. Why not
    follow bodies: engine-class bodies name the engine's world (the SQL printer's whole vocabulary would ride in through the back
    door, against T1/T2), and bodies call FUNCTIONS, which the prelude never carries, so following them would not make them type.
    The guarantee "the prelude's bodies are closed" is therefore a MEASURED fact — the census pin (`SpecBodyCensusTest`, shrink-only)
    — not a generator rule. Consequence accepted: the 18 engine-class body rows of SPEC_BODY_CENSUS §10.2 stay red until phase 3
    moves those classes to the graph where their helpers live; what remains after that is real vocabulary.
10. **Section headers name the spec file RELATIVE to its checkout root** (`legend-pure/…`, `legend-engine/…`): the module is a
    committed resource and must not carry a machine's absolute paths.
11. **Two architecture allowlists widen by one receipt each**: `PlatformSurfaceGuardrailTest` (Prelude.java names
    `Dialect.LEGEND_PLATFORM` — the same bootstrap-loader regime as Pure.java/SystemMetamodel.java) and
    `ParserBoundaryArchTest.DIALECT_CLASSES` (Prelude.java parses fixed Pure source once at class load).
12. **FOUND: the bare-name fallback's collision winner was HashMap luck — now a rule.** The resolver's fallback tier
    (`NameResolver.PRELUDE_TYPES`, simple name → FQN, consulted only when imports, wildcards, own package and the core
    imports claim nothing) was built by `put` over the catalog's `HashMap` key sets: 48 simple names collide across catalog ∪
    module (`Relation` ×3, `JoinType`, `Table`, `Window` ×3, `Column`, `Frame`, `SortDirection`, …) and the winner was whichever
    FQN the hash order visited last — bare `Boolean` fell to `meta::relational::metamodel::datatype::Boolean`, bare
    `PackageableElement`/`Property`/`Multiplicity`/`Constraint`/`ValueSpecification` to the protocol-template copies (masked
    only because the core imports claim those first). The first gate-1 run of the module flipped `Relation` and `JoinType` to
    the sql-protocol copies (13 failures: relation-typed parameters, a let-bound join kind). RULE (phase 1): the universe is
    read IN ORDER — the catalog's hand shapes and enums in declaration order, then the module in module order (legend-pure's
    sections before legend-engine's, each by spec path, source order within; `Prelude.classFqns()/enumFqns()` are ordered) —
    and the FIRST claimant of a simple name wins. Receipt: `NameResolver.platformTypeFqns/preludeTypes`, the generator's
    section order, `Prelude`'s ordered sets. The rule is a phase-1 container decision, not the end state: real Pure has no
    fallback tier (a bare name is its imports or an error), and phase 3's demand cut removes most of the 48 collisions from
    the module; whether an ambiguous bare name should then be an error is a leg of its own.
    **USER RULING 2026-09-08 (after batch 152): "Bare names must fail like pure/engine."** The fallback tier goes: a bare
    name resolves through the section's imports, the core imports and its own package, or it is an error — as in the
    engine. The ordered first-claimant rule above is transitional only until that leg lands (phase 3 removes most of the
    48 collisions first; lite tests that leaned on the fallback get their imports).
    **LANDED (batch 153, 2026-09-08).** `NameResolver`'s fallback index (`PRELUDE_TYPES`, `PRELUDE_COLLISIONS`) is deleted: a
    bare name resolves through its section's wildcards, its own package, then the core import group, or stays unresolved and
    fails downstream (`Unknown type`, the engine's `Can't find type`). The core group is m3.pure's `coreImport` PLUS the three
    packages the engine adds (`CompileContext.META_IMPORTS`: `meta::pure::metamodel::relation`, `::variant`,
    `meta::pure::precisePrimitives`) — the corpus is engine code and spells `Relation<(…)>` bare on their strength. MEASURED:
    the fallback was nearly dead — the whole chain moved by ONE lite test (`testLegacyTdsJoinWithLetBoundJoinType`, a
    sectionless query spelling `JoinType.LEFT_OUTER` bare; now qualified, as the engine would demand). Lanes EXACT, PCT
    1110/0, channel B at its floor, census 22. Phase 3 no longer has a collision problem to solve: two prelude classes with
    one simple name are now simply two classes, reachable by import.
13. **`Compiler.compileAllBodies` is the MODULE's eager pass, not the boot layer's.** The boot layer's functions (the system
    metamodel's, the prelude's lifted derived properties and constraints) are compiled once per process and typed by
    `SpecBodyCensusTest`, whose loop walks every function in the context — the 136 + 16 bodies' failures are that census's
    rows (§9.4). A user module's eager pass skips the boot FQNs (`CompilerModuleTest.eagerCompileAllBodies` pins one wall,
    the module's own).
14. **FOUND: a derived property the platform implements natively — the function half's rule, extended.** The first lane run
    lost 8 (DuckDB) / 3 (H2) tests, all `tdsContains`/`tdsJoin`, all one message: `TDSRow$prop$get has non-let intermediate
    statements — cannot inline`. The catalog DROPPED derived properties, so `$row.get('c')` never saw the spec's
    `TDSRow.get(colName)` body (`$this.values`, `columnByName`, two asserts — the engine's row representation); the module
    carries it verbatim, and the typer's derived-shadow route (`Typer.derivedShadow`: the receiver's own qualified property
    beats an Any-first native) redirected the call into it. On this platform a row IS a SQL row and the accessors are the
    natives `meta::pure::tds::get*(row, col)` (Pure.java: "real tds.pure spells getString as a TDSRow qualified property").
    §3 already states the rule for FUNCTIONS (the engine writes in Pure what a platform implements natively; the by-name
    suppression picks the native); a class's derived property is the same case. RULE:
    `PlatformTypes.isPlatformOwnedDerivedProperty(owner, name)` — exact owner FQN (`meta::pure::tds::TDSRow`) and the
    fourteen accessor names. ONE owner: `ClassCompiler` leaves such a property out of the TYPED class (the catalog never
    presented it, so every property route — the ordinary derived route, the derived-shadow route — reaches the native as
    before); `FunctionCompiler.compileAll` suppresses the lifted body's overload as it does a platform-owned function. A
    first cut guarded only the shadow route and the ordinary route then routed `$row.getString('c')` into the suppressed
    body ("unknown function TDSRow$prop$getString") — the second cycle; the class-compile site is the third and last. The
    module still carries the bodies (T5); the census may list them. Any further name on this list needs the same receipt:
    a native that IS the platform's meaning.
15. **FOUND: `Pair.toString` / `List.toString` as bodies expose the Any-to-text rendering gap — parked on the same rule,
    transitionally.** With the module, `->toString()` on a Pair routes through the derived-shadow rule into the spec body
    (`'<' + $this.first->toString() + ', ' + $this.second->toString() + '>'`); channel B's essential suite lost exactly one
    test, `testPairCollectionToString` (`<a, "b">` — an Any-carried string renders JSON-quoted), the witness
    `SYSTEM_PRELUDE_DESIGN` §8 already names for this day. Phase 1 is mechanism-only, so the two Java arms in
    `lowering/Scalars` (`isPairCarrier`, `isListCarrier`) keep rendering: `Pair.toString` and `List.toString` are on the
    platform-owned derived list with a TRANSITIONAL receipt. NEXT LEG (first after phase 1): fix the Any-to-text rendering
    (the variant carrier's `toString` of a scalar renders the scalar's text, never its JSON), delete the two arms and the
    two list entries, and let the bodies run — the design's original batch-148 item 3.
    **LANDED (batch 152, 2026-09-08).** The cause was not the Any arm (it already strips JSON quoting) but MONOMORPHIZATION:
    the module's `Pair<U,V>.toString()` body is typed once, generically, and `UserCallInliner` β-reduced it keeping every
    node's stamp — `$this.second : V` reached the lowering as a type variable and `toString`'s fall-through cast printed
    the JSON. Three rules, all at the inlining seam, each the design's "monomorphize at the application" (batch 147 row
    15) made complete: (a) `TypedSpec.withInfo` (every node record) and `UserCallInliner.instantiate` — the application
    binds the callee's type parameters by unifying declared parameter types against the argument types and every
    type-variable stamp in the inlined body resolves under them (the old rule re-stamped the ROOT only); (b) RE-DISPATCH
    — the derived-shadow rule applied once a receiver that was a type variable is concrete: an Any-first native call
    whose receiver now has its own same-named derived property becomes that body, inlined in turn (`<dog, <cat, mouse>>`);
    (c) the shadow keys on the function's SIMPLE name — the PCT printer spells `->meta::pure::functions::string::toString()`
    and real pure routes the class's own toString all the same. And `format`'s `%s` slots print a class-typed argument
    through its own `toString()` at TYPING (`CallShapes.formatSlotsByToString`, `PlatformTypes.printsByOwnToString`) —
    real pure's format calls toString per value. Scalars' two Java arms and the format pre-print are DELETED; the two
    transitional list entries are gone; `SpecBodyCensusTest` is PINNED shrink-only (22 rows, 6 load walls) and runs in
    gate 1 (its root defaults to the reference checkout like the generator). Pass counts unchanged on every gate.

## 10. Glossary (terms that confused in discussion)

- **catalog** — `Pure.java`'s static index of native signatures and hand-declared classes, built at Java class-load; never resolved
  or normalized; today also holds the generated prelude via `Prelude.load()`.
- **boot layer** — the system Pure compiled ONCE per process through the user pipeline and joined into every graph
  (`Compiler.bootLayer`); today `SystemMetamodel.source()`, after phase 1 also `prelude.pure`.
- **graph** — one compile's own elements: a corpus test's model (the engine tree's non-test elements + the test), a user's model.
- **graph-owned / corpus-defined** — a class the loaded engine tree declares itself (`corpusDefined` in the generator); each test
  compiles it as part of its own model.
- **unknown** — a name no spec file declares as a class or enum anywhere in the indexed roots; nothing to generate.
- **demand** — why a shape is wanted: `java` (a `src/main/java` file names its FQN), `corpus` (a corpus/library file names it),
  `closure` (a wanted declaration names it).
- **T4 receipt list** — the prelude classes a graph also declares; the prelude wins transitionally; the list burns to zero in phase 3.
