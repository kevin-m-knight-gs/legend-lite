# Declarations homework — register or load on demand? (2026-09-04)

Question (user): to run Pure programs, must the platform register every class
the program names? Why these and not the thousand others a program might name
one day? Is registering maybe the right thing? Receipts, not guesses.

## 1. What the prelude holds today (`core/src/main/java/com/legend/builtin/Pure.java`)

| kind | count | note |
|---|---|---|
| `native Class` declarations | **257** | the two numbers quoted earlier (330, 110) were two different greps; this is the count of `nativeClass("native Class …")` calls |
| `native Enum` declarations | 21 | text-block form (`nativeEnum("""Enum …""")`) |
| native function signatures | 787 | functions with a Java SQL lowering rule — these MUST be registered (we own their meaning) |

The 257 classes by package: relational metamodel 65, SQL nodes (`meta::external::query::sql`) 47, m3 (`meta::pure::metamodel`) 43, pure functions carriers 12, mapping 10, store 9, relational mapping 8, execution plan 8, alloy 7, lineage 5, and 43 more across 11 packages. Only a handful (Pair, List, Map, TDS, TDSNull, Variant, …) are platform carriers; the rest are copies of engine/pure library shapes.

## 2. How the compiler treats them

* `TypeClassifier.findType`: **native class first, then the user model** — a loaded declaration that duplicates a native is ignored (the native wins). Loading a declaration file that overlaps the prelude is therefore safe.
* `ModelIntegrity.check` runs **eagerly over the whole model at context construction**: every type a declaration names (property types, supertypes, function parameter and return types) must exist, or the model never becomes queryable. Function **bodies** are lazy (Phase G) — that is why `toPostgresModel.pure` loads today with `DynaFunctionRegistry` unresolved inside a body, and why the corpus loader walls families whose declaration files dangle (`MODEL-WALL Unknown type: 'Package'` in channel B).
* Consequence for any loader: pulling one declaration means pulling the transitive closure of the types it names, at load time.

## 3. What the corpus actually references (`core_relational`, 543 files)

| measure | count |
|---|---|
| distinct engine/pure LIBRARY types referenced in type positions (`@X`, `^X(`, `:X[`, `instanceOf(X)`) | **887** |
| of those NOT in the prelude | **713** |
| of those, protocol-version classes (`meta::protocols::pure::v1_2x_0::…` — nine versions of the same eight mapping classes, from tests that build protocol payloads) | 505 |
| non-protocol | 208, defined in **52 files** |

The 208 by defining file: legend-pure `relational.pure` 38, `mapping.pure` 19, the SQL node `metamodel.pure` 13 + `metamodel_extensions.pure` 14, `mft.pure` 13, `executionPlan.pure` 12, `router/store/metamodel.pure` 9, `tds.pure` 9, `relationalMapping.pure` 7, `milestoning.pure` 5, 42 other files ≤ 4 each.

So "a thousand other shapes" is not hypothetical: hand-registration has 713 to go **for this corpus alone**, 74 of them for the one program (toPostgresModel) in front of us.

## 4. The universe those types live in

| module | class + enum declarations | files |
|---|---|---|
| legend-pure core / dsl / store | 215 + 84 + 124 | 61 |
| legend-engine core pure | 4,768 | 335 |
| legend-engine relational store pure | 2,376 | 358 |
| postgres SQL model | 127 | 3 |

≈ 7,700 declarations. The engine's own module for `toPostgresModel.pure` declares Maven dependencies on `legend-engine-pure-code-compiled-core` and `legend-engine-pure-platform-store-relational-java` (relational.pure) among others: in the engine, the shapes arrive through the module graph, never through registration.

## 5. Feasibility receipt — our parser already reads the declaration files

Parsed in the platform dialect by `ElementParser.parse` (this session, jshell over the installed core jar):

| file | elements | classes | enums | functions |
|---|---|---|---|---|
| legend-pure `platform_store_relational/grammar/relational.pure` (617 lines) | 103 | 99 | 4 | 0 |
| postgres `core_external_store_relational_postgres_sql_model/metamodel.pure` (603 lines) | 93 | 79 | 14 | 0 |
| legend-pure `platform_dsl_mapping/grammar/mapping.pure` | 36 | 36 | 0 | 0 |
| engine `core/pure/executionPlan/executionPlan.pure` | 31 | 30 | 0 | 0 |

Besides shapes the two main files carry: 2 qualified properties (relational.pure), 0 constraints, 0 functions. Parse cost is negligible (the parity gate parses 5,259 sources in ≈ 40 s).

## 6. What the world map says, and what it does not

`docs/WORLD_MAP.md` rules on **who executes behavior** (natives = Java SQL rule; platform semantics = Java from spec; programs = compiler input) and says the platform "ships a prelude of declarations only". It does NOT say whether that prelude is Java strings or files, nor whether it is eager or on demand. Both answers satisfy every tenet; the choice is delivery, not principle.

The mental model that decides: **behavior is curated, shapes are data.** If the platform must IMPLEMENT something (a native's SQL meaning), it is registered. If the platform must only KNOW a shape so a program can name it, there is nothing to implement — it is data, and curating data by hand puts labor between a program and a fact that already exists in a file.

Steelman for registering: a closed, reviewed type surface — every addition is a diff someone read. It held at 257. It stops holding when the goal is running programs, because the review would only be checking that a copy matches a file, 713 times.

## 7. Recommendation

1. **A declarations-only dependency loader.** Reads `Class`/`Enum`/`Association`/`Profile` from a set of dependency roots, resolving an unknown type by FQN through an index (the corpus runner already has `classIndex()` over `core_relational`; extend it to the dependency roots), pulling the transitive closure of the types each declaration names (ModelIntegrity is eager), never loading function bodies. Qualified-property bodies come with their class and inline only if a program reaches them (a program, kind 3); constraints are dropped (validation semantics we do not execute).
2. **Two sources, one loader.** For the corpus harness the legend-pure and legend-engine checkouts are the dependency roots — they feed the thing UNDER TEST, which the reference-checkout tenet permits (fixture), and `LibraryPlatformNamespaceGuardTest` still refuses `meta::pure::functions::` bodies. For a shipped legend-lite the same declaration set is VENDORED as data (generated from the spec, parity-tested), because production has no checkout and real users name `meta::relational::metamodel::*` too.
3. **`Pure.java` keeps natives (787 signatures) and carriers.** No new library class declarations are added by hand; the 200-odd library-shape copies migrate out when the loader is proven (cleanup, not a blocker).
4. **World map amendment (rule 2):** "shapes are data, generated and loaded on demand; natives are registered; constraints are not loaded."

Witness for the loader: `toPostgresModel::tests::testConvertLiteral` (74 declarations, 0 bodies).

## 8. Step 3 homework — does "load on demand" fit the model context? (user challenge)

Receipts from the construction path:

* `PureModelContext` is **one-shot and immutable after construction** ("modulo
  the memo caches; the backing ModelBuilder is read-only"). `ModelIntegrity.
  check` runs eagerly at construction. Execution overlays are VIEWS resolving
  exactly two fqns (runtime, connection). → **Lazy class loading at QUERY
  time is not how this context works** and would be a redesign. The
  skepticism is right for that reading.
* `Compiler.buildModule` is the TOLERANT build: "POISON, DON'T DROP — every
  element stays; the walls map records each broken element's first failure;
  the failure fires at USE time" (the alternative, dropping, once killed 182
  corpus tests in a blast radius). → an incomplete type closure degrades PER
  ELEMENT (`global <fqn> => Unknown type …` walls), never per model.
* The corpus runner assembles ONE global module (compile-once): family
  sources + test files + `libraryRaw` (an existing LIBRARY SOURCE channel,
  deduplicated by text) → `parseSources` (platform dialect) → `buildModule`,
  once. Duplicate FQNs across sources throw before the build.
* The boot layer (`SystemMetamodel`) is a second Pure layer, normalized
  separately and concatenated into the model; `withoutSystemShadows` refuses a
  graph element that redefines a system element.

Conclusion: "on demand" means **demand-driven at MODEL BUILD time**, not at
query time — and that form needs no change to `PureModelContext`:

1. Before `parseSources`, scan the assembled sources for type references
   (the runner's `classIndex` already scans declaration headers this way),
   map them through the prelude manifest to files, take the file closure.
2. Parse those prelude files, keep only class/enum/association/profile
   elements, DROP any element whose fqn a native, a system element, or a
   corpus source already defines (the duplicate/shadow rules), and append the
   rest to the parsed model — exactly the `new ParsedModel(elements, imports)`
   shape the boot layer uses. The exact copy stays on disk; the filtering is
   load-time code, pinned by a guard test.
3. Build once. Any remaining `Unknown type` wall that the manifest CAN resolve
   is a second pass (rare — the reference scan over-approximates); one that it
   cannot is a genuine wall, reported as today.

Cost: the global module already parses ~543 corpus files; the 52 prelude
files the corpus needs are ≈ 10% more parsing, once per run.
