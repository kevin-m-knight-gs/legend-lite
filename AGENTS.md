# Legend Lite — Architectural Invariants

> **This file is read by AI coding assistants. Follow these rules strictly.**
>
> **North-star tenet:** *Total Knowledge, Demand-Driven Work* — be eager and
> total about Knowledge (parse/index/manifest), lazy about Work (type-check
> bodies, lower, SQL, execute). See `docs/TENETS.md` before any "lazy vs
> eager?" decision.
>
> **Authoritative spec:** `core/README.md` — folder layout, per-package
> contracts, open decisions. This file is the short form.
>
> Layer design rationale: `docs/pipeline-architecture.md` (backend, HIR → MIR
> → SQL) and `docs/frontend-architecture.md` (frontend, text → HIR). **Both
> were written against the frozen `engine/` tree** — their reasoning governs,
> their type names do not. Each carries a name-mapping banner. Take names from
> here and from `core/README.md`.

## Read this first: there are two trees, and you almost certainly want `core/`

| | `core/src/main/java/com/legend/` | `engine/src/main/java/com/gs/legend/` |
|---|---|---|
| status | **LIVE.** All compiler work happens here. | **LEGACY**, frozen — last substantive change 2026-07-18. |
| size | 418 files / ~120,716 LOC | 341 files / ~47,949 LOC |
| back half | the whole pipeline | switched off (`System.getProperty("legend.pipeline","core")`) |

`com.gs.legend`'s parser, compiler, model, plan, sql and sqlgen packages all
have live `com.legend` counterparts. **Core is authoritative — do not read,
copy, or "fix" the legacy versions.** The wall is enforced: nothing under
`com.legend.*` may import `com.gs.legend.*` (`ArchitectureTest:50`).

**Legitimate reasons to open `engine/`** — capabilities core genuinely does
not have:

- **HTTP server + routing** — `server/LegendHttpServer` (core has no wire layer).
- **JSON *writing*** — `util/Json`. Core's `sql/Json` is **parse-only**; there
  is no general JSON emitter in core.
- **Result serialization** — `serial/{JsonSerializer,CsvSerializer,...}`.
- **LSP** — `server/PureLspServer`. Core's `ide/` is dormant parsing infra by
  its own `package-info`; no JSON-RPC, no diagnostics.
- **Diagrams** — `server/DiagramService`. Core lexes `###Diagram` and discards it.
- **The relational corpus scoreboard** — `engine/src/test/.../rcorpus/`. This
  lives under `engine/` but **measures core**; it calls `com.legend.sql.dialect.DuckDb`
  and `com.legend.exec.Executor` directly.

The legacy→core bridge is exactly three files: `QueryService.java:82`,
`PlanGenerator.java:101`, `CoreBridge.java`.

## Pipeline — 11 steps, one driver

`com.legend.Compiler` owns step ordering. Every step is the same method its
own unit tests exercise; there is no orchestrator-only code path.

```
text                                                             [FRONTEND]
  A  lexer/            Lexer.tokenize                 text → TokenStream
  B  parser/           ElementParser.parse            tokens → ParsedModel
  C  parser/           SpecParser.parse               tokens → ValueSpecification
  D  compiler/         NameResolver.resolve           simple name → FQN
  E  normalizer/       ModelNormalizer.normalize      ParsedModel → NormalizedModel
  F  compiler/element/ PureModelContext.from          → TypedElement + ModelContext
  G  compiler/spec/    SpecCompiler                   spec + model → TypedSpec
  G½ compiler/spec/    UserCallInliner.inlineBody     TypedSpec → β-inlined TypedSpec
                                                                 [MIDEND]
  H  resolver/         StoreResolver.resolve          logical → physical TypedSpec
  I  lowering/         Lowerer.lower                  TypedSpec → sql.SqlQuery
                                                                 [BACKEND]
  J  sql/dialect/      SqlDialect.render              SqlQuery → SQL string
                                                                 [RUNTIME]
  K  exec/             Executor.execute               SQL + JDBC → ExecutionResult
```

**G½ runs on every execution path** — do not omit it when reasoning about the
pipeline.

**A second, user-facing phase vocabulary exists** and does not use letters:
`error/LegendCompileException.Phase` = `PARSE, RESOLVE, NORMALIZE, MODEL,
TYPE, MAPPING, LOWER, EXECUTE`. Note there is **no `RENDER`**. Every
user-visible error carries one of these eight.

### Entry points

| Method | Does | On error |
|---|---|---|
| `Compiler.compileModel(String)` | A→F | **STRICT** — first error aborts |
| `Compiler.buildModule(ParsedModel)` | A→F | **TOLERANT** — poison-don't-drop, returns a wall map |
| `Compiler.compileAllBodies(ctx)` | eager G over all bodies | **never throws**, returns walls |
| `Compiler.compileQuery(model, query)` | frontend + G | STRICT |
| `Compiler.plan(...)` | frontend → J | STRICT — **the production seam** |
| `Compiler.execute(...)` | A→K | STRICT — **the production seam** |
| `Compiler.executeResolved(...)` | G½→K, the one back-half sequence | STRICT |

`compileModel` and `buildModule` differ by **one argument**:
`NameResolver.resolve(parsed)` vs `resolve(parsed, walls)`. If you want every
error rather than the first, you want `buildModule`.

`StatementExecutor` is **package-private by design** — reachable only through
`Compiler.executeResolved`. The driver never re-implements a step; the
executor never decides pipeline order.

## Layer ownership (the contract)

| Layer | Owns | Forbidden |
|---|---|---|
| **Lexer** | text → tokens | anything else; it is JDK-only and stays that way |
| **ElementParser / SpecParser** | tokens → `model.PackageableElement`, `protocol.spec.ValueSpecification` | type info, semantic decisions |
| **NameResolver** | simple name → FQN over the parsed model | consulting the compiled model; type checking |
| **ModelNormalizer** | legacy mapping DSL → synthesized `FunctionDefinition`s | mutating the model; running after the typer |
| **PureModelContext** (phase F) | definitions → `TypedElement` in a `ModelContext` | type-checking bodies (that is G, on demand) |
| **SpecCompiler / Typer** | `ValueSpecification` + model → `TypedSpec`; resolves overloads; computes types and multiplicities | mutating `ModelContext`; emitting MIR |
| **StoreResolver** | logical `TypedSpec` → physical | re-running type checks |
| **Lowerer** | `TypedSpec` → `SqlQuery` | naming SQL functions; SQL syntax; importing a dialect; any `String` field encoding a SQL operation |
| **SqlDialect** | `SqlQuery` → SQL string | inferring types; rewriting HIR; consulting the model |
| **Executor** | SQL + JDBC → `ExecutionResult` | everything above |

## Invariants

> **These numbers are load-bearing.** 31 javadoc sites under
> `core/src/main/java` cite them *by number* — "AGENTS.md invariant 4" appears
> 15 times alone. **Do not renumber.** Append new rules at the end.
>
> **Enforcement is marked honestly.** `[ENFORCED]` means a test fails if you
> break it. `[CONVENTION]` means the rule is real and expected but **nothing
> checks it** — breaking it will not turn anything red, so it is on you.

### 1. The frontend does ALL AST walking and typing `[CONVENTION]`

Two cooperating compilers:

- **Element compiler** (phase F — `compiler/element/PureModelContext.from`,
  plus `ModelBuilder` and `element/{ClassCompiler,FunctionCompiler,StoreCompiler}`):
  builds the typed model from parsed definitions.
- **Expression compiler** (phase G — `compiler/spec/SpecCompiler`, `Typer`,
  `InferenceKernel` and the per-construct checkers): type-checks
  `ValueSpecification` against the model and produces typed HIR.

Together they are the **single source of truth for types**:

- Every expression and every call MUST get a type.
- Every overload MUST resolve to a concrete signature.
- If a type is missing downstream, **the frontend has a bug — fix the
  frontend**, usually `Typer` or the relevant checker.

### 2. The Lowerer does no type inference `[CONVENTION]`

The Lowerer reads **annotated HIR** — structure from typed nodes, types from
the annotations.

It MUST NOT: infer or resolve types (no model lookups, no compatibility
checks); validate correctness; inspect HIR for **type** dispatch
(`instanceof CInteger` to pick a MIR shape); parse function names to guess
types; contain SQL syntax or SQL function names; import a dialect.

It MAY: read HIR structure (function names, parameters, lambda bodies, ColSpec
names, nesting); read type annotations; pattern-match typed HIR for
**structural** dispatch; use binding tables to map a resolved signature to a
typed MIR variant.

The Lowerer emits typed MIR records — never raw SQL, never SQL function names.

### 3. The dialect owns ALL SQL rendering `[CONVENTION]`

Type-name mapping, keyword spellings, function names, syntactic quirks, and
dialect-specific decompositions all live **here**, not in lowering.

**Core has exactly ONE render entry point:**

```java
// core/src/main/java/com/legend/sql/dialect/SqlDialect.java:14
String render(SqlQuery query);
```

plus three defaults on the same interface: `normalize(Object, SqlType)`,
`needsStaticPivot()`, `rawH2IsNative()`. Base impl `AnsiSqlRenderer`.

> **If you have read otherwise:** `SQLDialect`, `SqlAggregate`, `SqlRelation`
> and the three-render-method contract are **engine-only**. They do not exist
> in core. `WindowAggregate` exists in neither tree.

Dialects: `AnsiSqlRenderer` → `DuckDb`; `H2` → `H2Modern`; `EngineStyleH2` →
`EngineStyleDB2` → `EngineStyleComposite`. SQLite is not a class — it is
`Lexicon.SQLITE` passed to `AnsiSqlRenderer`.

Render methods are switch **expressions** with **no `default ->` arm**, so
javac enforces exhaustiveness. When a dialect genuinely cannot express a
variant, the arm **throws** `UnsupportedOperationException` — that is still an
arm. `default ->` is not an acceptable substitute.

### 3a. The MIR is closed and pure data `[CONVENTION — dependency half ENFORCED]`

Sealed roots in `com.legend.sql` (15 files, ~1,743 LOC):

| Root | Variants |
|---|---|
| `SqlQuery` | `permits SqlSelect, SqlUnion` |
| `SqlSource` | 8 — `Pivot, SourceUrl, Table, VarSetPlaceholder, Dual, Subselect, Values, Join` |
| `SqlExpr` | 32 — incl. `Lambda` (**not** `LambdaExpr`), `Call`, `Cast`, `WindowCall`, `SqlAgg.Reducer` |
| `SqlAgg` | carries `enum Fn` (~35) + `Reducer` |
| `SqlType` | `Scalar` enum, `Decimal(p,s)`, `Array`, `Map` |
| `DateFmt` | — |

- **No method on a MIR type returns SQL.** No `toSql()`, no `render()`.
- **No MIR type references a dialect.**
- **No MIR record has a `String` field encoding a SQL operation.** The single
  carve-out is `SqlExpr.Cast(expr, pureTypeName)` — a *Pure* type name mapped
  by the dialect. Pure type names are not SQL.
- **No `FunctionCall(String name, args)` catch-all in MIR.** Every operation is
  its own typed record. New native = new MIR variant + new render arm.
- **Lambdas live in MIR as data** — `SqlExpr.Lambda`. MIR never holds a Pure
  AST node.
- **New dialect = one class implementing `render(SqlQuery)`.** MIR does not change.

`ArchitectureTest:80` and `:215` enforce that `sql/` depends on nothing else.
The "no `toSql()` / no SQL-encoding `String` field" half has **no test**.

> **A record named `FunctionCall` does exist** at
> `model/RelationalOperation.java:189` — the parsed `###Relational` dynaFunc
> node. That is not MIR and is fine.

**Stop signs** — if you are writing one of these, re-read this section:

- `record Foo(...) implements SqlExpr { String toSql(...) {...} }`
- a `FunctionCall("someFunc", args)` in a lowering
- `private static String mapXxxName(String pureName)` in a lowering
- `default ->` in a render method (add a real arm; throw if unsupported)
- `sealed interface ...` with no `permits` clause

### 4. NO FALLBACKS. NO DEFAULTING. `[CONVENTION]`

*The most-cited invariant in the codebase — 15 javadoc sites.*

- The **whole point** of the compiler is to catch mistakes early.
- If a type is unknown, **fail**. Do not guess, default, or fall back.
- If a binding is missing for a resolved overload, **throw** — never fall
  through to a stringly-typed catch-all.
- Every defaulting branch is a bug hiding behind a safety net.

### 5. Lazy loading of user packageable elements `[CONVENTION in core]`

Cross-project dependencies must not force-load the transitive graph.

- **Platform types** (everything in `builtin/Pure.java`) are always loaded and
  safe to classify eagerly.
- **User types** are referenced by FQN. `Type.ClassType("my::app::Person")` is
  an FQN in a typed wrapper; it does **not** imply the class is loaded.
- Structural access (`findProperty`, `isSubtype`, superclass walks) MUST go
  through `ModelContext.findClass` / `findEnum` / `findFunction` — the sole
  layer that owns load triggering.
- Long-lived fields hold **FQN strings**, never resolved element objects.
  `TypedClass.superClassFqns: List<String>` is the canonical example.
- Never walk a dependency graph by field access; go through `ModelContext`
  every step.

> **The two automated guards — `NoEagerTypeReferencesTest` and
> `NoEagerUserClassLoadsTest` — live in `engine/src/test` and scan
> `com.gs.legend` only.** Core has **no lazy-loading enforcement at all**. If
> you break this in core, nothing turns red.

### 6. F must not trigger G `[CONVENTION]`

Function bodies stay as `ValueSpecification` inside `TypedFunction` and are
type-checked on demand. Compiling elements must not compile specs.

### 7. Store-only nodes must not escape their phase `[ENFORCED — runtime]`

`TypedGetAll` and `TypedUserCall` MUST NOT survive phase H.
`StoreResolver.assertNoStoreOnlyEscapees:220` walks every resolved statement
and throws, naming the construct; `StoreResolverTest` pins it. Likewise no
store-only node reaches the Lowerer — those are "resolver bug" walls.

### 8. The wall `[ENFORCED — ArchitectureTest:50]`

No `com.legend..` → `com.gs.legend..` dependency, ever. Also: no `util/`
package anywhere (`ArchitectureTest:66`).

`ArchitectureTest` is 23 dependency-direction rules and **nothing else** — it
asserts no sealedness, record-ness, or exhaustiveness. It uses its own
numbering ("6g", "7a-c") that does **not** map to this list or to
`core/README.md`'s. Do not merge the numbering schemes.

> `core/README.md` has its **own** 12-invariant list, also cited by number from
> code (`TypedElement.java:11`, `Type.java:16` cite "core/README invariant 11").
> The two lists are separate. Do not merge or renumber either.

## Standing documents

The live process layer. None of it is reachable from any always-loaded file
otherwise, which is how it drifted:

| Doc | What it is |
|---|---|
| `docs/GATES.md` | The gate chain. **Read before claiming anything is green.** |
| `docs/ENGINEERING_LOG.md` | Standing tenets + active queue |
| `docs/TENETS.md` | Eager-Knowledge / lazy-Work, the north star |
| `docs/AUDITS.md` | Audit index and reading order |
| `docs/CORPUS_BURNDOWN_HANDOFF.md` | Corpus burn-down entry point |
| `docs/RELATIONAL_CORPUS.md` | The corpus scoreboard (a **gate artifact** — regenerated, not hand-edited) |
| `docs/OUTSTANDING.md` | Generated non-passing ledger |

## Common mistakes (don't repeat)

1. **`(int) longValue`** — boxed `Long` cannot raw-cast to `int`. Use `.intValue()`.
2. **Hardcoding SQL in lowering** — emit a typed MIR variant; the dialect renders it.
3. **Naming SQL functions in lowering** — no `FunctionCall("name", args)`.
4. **`String mapXxxName(String pureName)` helpers in lowering** — the smoking
   gun of stringly-typed dispatch leaking SQL into the wrong layer.
5. **Adding a normalization instead of fixing the root cause.**
6. **Adding fallbacks or defaults** — fail loudly; fix the compiler.
7. **"Fixing" a fallback by changing what it defaults to** — if a default
   branch is being hit: (a) make it throw, (b) find why the upstream layer
   produced nothing, (c) fix that layer. The fallback existing is the bug.
8. **`default ->` in a render method** — add the missing arm.
9. **Type inference in the Lowerer** — it reads types, never infers them.
10. **Making the compiler lenient on missing model elements** — it MUST throw
    if a referenced class, property or type is absent. If a test fails because
    a class is not found, **fix the test's model setup**. Never degrade silently.
11. **Running a downstream gate without rebuilding core.** `mvn -pl <module>
    test` resolves `legend-lite-core` from `~/.m2`, **not** the reactor — so it
    silently tests the previously installed jar. Use `-am`, or `mvn -pl core
    install -DskipTests` first. This has already produced a phantom
    regression report.
