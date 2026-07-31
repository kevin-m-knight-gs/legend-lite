# Null Gate — independent verification

> **What this is:** an audit of *delivered work*, not of the codebase. It verifies the ~20 commits
> from `a814ffa9..aa5df4f7` that implemented `AUDIT_PROGRAM.md` §3 (the null gate) plus Phases 0A,
> 0B, 2a, 2b and 2c. Companion to `AUDIT_PROGRAM.md`, which specified the work.
>
> **Two questions asked:** did we get it right, and were we aggressive enough.

**Method.** The gate was proven live by *injection*, not by reading config: three deliberate
violations were compiled into `com.legend.sql` and all three failed the build as errors. Strictness
headroom was measured by **rebuilding `core` under six configurations** and counting unique
diagnostics, not by estimation. Claim verification read the 13,135-line main-source diff (164 files,
612 substantive hunks) rather than commit messages.

---

## 1. Verdict

**Right: yes, with one capitulation.** The implementation matches the §3.1 decision exactly — our own
`com.legend.Nullable`/`NonNull`, NullAway in `annotationProcessorPaths` only, `-XepDisableAllChecks`,
the `--add-exports` block present and commented as load-bearing. Zero new production dependencies;
`core`'s deps remain two JDBC drivers. **The code was fixed, not merely made to compile** (§3).

**Aggressive enough: on main, nearly maxed. On the system around it, no.** Measured remaining
headroom on main sources is **32 findings** — about one per twelve files. But the gate has **no CI and
silently no-ops on a warm `target/`** (§4), which is a larger problem than any flag.

### 1.1 A correction to the first reading of this work

An initial pass reported *"15 `@Nullable`, all in one file"* and called it exceptional. **That was a
grep artifact and it was wrong by roughly 70×.** The codebase writes the annotation fully qualified
in type-use position, `@com.legend.Nullable`, which a `@Nullable` pattern does not match.

| | Count |
|---|---|
| `@Nullable` (unqualified — `SqlSelect.java` only) | 15 |
| `@com.legend.Nullable` | **1,044** |
| **Total** | **1,059 across ~150 of 379 files** |
| `@SuppressWarnings("NullAway")` | **0** |

Nulls were overwhelmingly **marked**, not erased. That is still a mature adoption — a clean baseline
at 1,059 declared exceptions with zero suppressions is not a token one — but it is a different claim
from "the tree is null-free," and the doc records the accurate one.

---

## 2. What verified

- **The gate fires.** Injected sentinel-return, null-deref and null-argument violations each failed
  the build: `returning @Nullable expression from method with @NonNull return type`, `dereferenced
  expression t is @Nullable`, `passing @Nullable parameter 'null' where @NonNull is required`.
- **Whole-tree adoption, beyond what §3.5 proposed.** The plan was incremental with
  `exec`/`harness` last or never. Delivered: `AnnotatedPackages=com.legend`, all 379 main files,
  earned package by package with real counts — resolver 344→0, lowering 144→0, model 129→0, exec
  123→0, harness 117→0, spec 101→0, normalizer 81→0.
- **All four claimed "latent silent-nulls" are genuinely fixed**, and one produced a *type-level*
  strengthening rather than an annotation: after the zip-arm fix, `StoreResolver`'s
  `resolveNode`/`objectNode`/`anchoredNode` **dropped their `@Nullable` returns entirely**. Another
  became `throw new NotImplementedException("class-typed if with a non-static condition is not
  supported yet")`.
- **`SqlSource.Dual` is a real sealed variant, not an EMPTY sentinel.** Every `s.from() != null`
  became `!(s.from() instanceof Dual)` with byte-identical output, `AnsiSqlRenderer.source()` throws
  on it as a caller bug — and javac exhaustiveness then **found four stray null-FROM constructions
  the grep had missed**. The mechanism working as designed.
- **Dummy defaults were deleted, not added.** `ModelBuilder.from` dropped
  `elementImports() == null ? Map.of() : …`; `Pipelines` dropped three dead `d.columns() != null &&`
  guards. No sentinel objects introduced (the three pre-existing `NONE` singletons predate this
  work). No widened or erased types. Nothing pushed into unchecked territory.
- **`requireNonNull` 314 → 579 (88 → 125 files), +259 / −0, and the large clusters are honest.**
  Each traced to an invariant NullAway structurally cannot see: alias keys drawn from the map's own
  `keySet()`, values correlated through two ternaries, an `orElseThrow` three lines above.
- **The `try` denominator is exact.** An independent count found **134** (113 plain + 21
  try-with-resources), matching the Phase 2b claim precisely.
- **Phase 2c's deletions are all safe** — the load-bearing question. Ten of eleven are protected by
  **record compact constructors**, which run for *every* caller including un-gated
  `core/src/test`, `engine/` and `pct/` (`null → Map.of()`, `null → List.of()`, `List.copyOf`). The
  eleventh has exactly two construction sites, both inside gated code.
- **`MappingNormalizer:759` fixed** — the dead `groupBy` null-check became
  `!child.groupBy().isEmpty() ? …`, matching its `primaryKey` sibling. `tests/mapping/extends`
  21/23 → 23/23; corpus 2,129 → 2,131.
- **Four strictness flags measured at exactly zero**, correctly left off:
  `AcknowledgeRestrictiveAnnotations`, `CheckContracts`, `AssertsEnabled`, `ExhaustiveOverride`.
  Zero `@Contract`, zero `assert` in main, and core main imports no third-party types.
- **Nothing is escaping through unset exclusions.** All 379 main files are `com.legend`; zero
  `@Generated`, zero `@Inject`/`@Mock`, no generated-source dirs, no `UnannotatedSubPackages`.
- **Module isolation holds.** `engine` is `com.gs.legend`; `pct`/`nlq` are `org.finos.legend`. None
  declare `com.legend.*`, so nothing is silently treated as annotated-but-unchecked.

---

## 3. G0 — The gate is not enforced by anything

**This outranks every flag in this document.**

- **There is no CI.** No `.github/`, no workflow files, no YAML anywhere in the repo. The gate
  protocol is prose in `docs/ENGINEERING_LOG.md:40-46`, executed by hand.
- **The protocol has no `clean`, and a warm `target/` skips the gate entirely.** Verified: Maven
  prints `Nothing to compile - all classes are up to date` and **NullAway does not run**. A developer
  who has already built will not be checked.

So the gate's current enforcement strength is developer discipline — the same thing that let
`ARCHITECTURE` T3.1 be applied to two of three sites (§7 G5).

**G0.1 — add `clean` to the core steps of the gate protocol.** One line, and it is the difference
between a gate and a suggestion.
**G0.2 — add CI** that runs the protocol. Until then, every other item here is advisory.

*Correction to an earlier concern:* offline mode is **not** a bypass. Only the `engine` (×2) and
`pct` (×1) steps use `-o`; both core steps run online, and the gate lives only in core's
`default-compile`. The bypass is the missing `clean`.

---

## 4. G1 — A live regression introduced by Phase 2a

**`SqlExpr.children()` drops a subtree, and five walkers now trust it.**

`SqlExpr.java:66-73` returns only `partitionBy` + `orderBy` for `WindowCall` — **`w.fn()` is
omitted** — and `withChildren` at `:150` carries it through untouched. Since
`SqlAgg.Reducer implements SqlExpr` and `Windows.windowize` routinely builds
`new SqlExpr.WindowCall(reducer, …)`, the consequence is concrete:

> `SqlPostProcessors.expr:238` does not apply plan-wide table renames inside `sum(t.x) OVER (…)`.

`Fold.walkColumns:142`, `PlanEnumForm.collectColumns:234`, `Scalars.undoubled:2740` and
`substituteRef:3130` have the same hole, because all five replaced explicit switches with
`default -> e.mapChildren(...)`.

This is the **identical bug class the commit celebrates fixing** for `Pivot`/`Values` — and it is
worse than the prior state, because an explicit switch made the omission visible while "correct
traversal by construction" hides it.

**G1.1 — add `w.fn()` to `WindowCall`'s children and round-trip it through `withChildren`.**
**G1.2 — extend `TypedSpecChildrenTest`** (`core/src/test/java/com/legend/compiler/spec/TypedSpecChildrenTest.java:58-67`)
to the three new contracts. The reflective test that would have caught this already exists in pattern
form and none of the three Phase 2a commits extended it.

**What the contract does and does not buy.** A `sealed` node interface gains `children()`,
`withChildren(List)` and `mapChildren(op)` as pattern switches with **no `default` arm**, so javac
refuses to compile until a newly-permitted variant declares its children. That is real compiler
enforcement — **for variant *addition* only.** Nothing enforces *child completeness* (G1.1 is the
proof), and nothing forces a new walker to delegate. `SqlSource` received no contract at all.

---

## 5. G2 — The one capitulation

`resolver/NavMaterializer.java:96` turned `chainPrefix.contains(".")` into
`chainPrefix == null ? "" : chainPrefix.contains(".")…`.

That value is the **set-id dispatch key** into `sources.getForNav(mappingFqn, targetClassFqn, key)`.
`""` never matches a real set id, so `routedTargetSetOf(…, "")` returns empty and **the class-level
root set is silently chosen over the set-discriminated binding**. The prior behavior was an NPE.

An exception converted into a wrong answer is the trade tenet #3 explicitly forbids, and this is the
only site in the entire body of work where the gate was satisfied by making a wrong answer possible.

**Fix:** `requireNonNull(chainPrefix, "…")`, or make the parameter non-null and have the top-level
entry pass an explicit, documented key.

### 5.1 Three smaller defects in the same family

| Site | Defect |
|---|---|
| `NavMaterializer.java:694` vs `:297-306` | **Same nullable, two opposite policies in one file.** `:297` was made loud — `requireNonNull(…, () -> "sub-navigation '"+alias+"' has no materializable pipeline")` — while `:694` silently `continue`s |
| `Lowerer.java:3307` | `if (!params.contains(var) \|\| var == null)` — the null test comes **after** `contains(var)`, which throws first on an immutable list. It cannot protect at runtime; it exists only to satisfy the analyzer. Swap the operands |
| `Substitution.java:1271-1278` | `requireNonNull(target.isNotEmptyCallee())` where `Registries.NONE` and six construction sites pass null callees. Self-declared as debt in `0e04b9fd`; the stated fix is threading callees into nested `Registries` |

### 5.2 Systemic — 166 anonymous NPEs

**166 of the 259 added `requireNonNull` calls carry no message (64%).** Tenet #3 requires *named*
errors; an argument-less `requireNonNull` produces an anonymous NPE with no phase, element, or
reason. Several sites already have a domain exception available for the exact condition —
`Substitution:2000` sits in a file whose `:1541` throws
`MappingResolutionException("property 'X' of embedded 'Y' … is not mapped")`.

---

## 6. G3 — Measured strictness headroom

All figures from rebuilding `core` under each configuration.

| Gap | Newly catches | Findings | Call |
|---|---|---|---|
| **`JSpecifyMode=true`** | Nullness in **generic type arguments** | **19** | **Adopt now** |
| **NullAway 0.12.10 → 0.13.6** | `switch` selector nullness | **4** | **Adopt now** — free, same flags |
| **`CheckOptionalEmptiness=true`** | `Optional.get()` without `isPresent()` | **9** | **Adopt now** |
| Test sources | 1,546 tests, zero coverage | 284 raw / **~26 real** | Defer deliberately — §6.2 |
| `AcknowledgeRestrictiveAnnotations`, `CheckContracts`, `AssertsEnabled`, `ExhaustiveOverride` | — | **0 each** | Skip — measured, not assumed |
| `engine/` + `pct/` gating | Un-gated → gated null flow | **1 site** | Skip |

### 6.1 JSpecify is the flag this codebase specifically needs

This is a compiler built on resolver lambdas — **144 `Function<` uses across 25 files** — and a
`Function<A,B>` hides its null sentinel inside a *type argument*, where the current configuration is
blind. `InnerDemand.java:478,496,510`:

```java
Function<TypedSpec, TypedSpec> resolver = chain -> { … return rel0 == null ? null : …; … return null; }
```

A documented sentinel return, invisible to the gate that exists to kill exactly that pattern. Same
shape at `GraphEmission.java:424,465,2552,2577` (`toRow.apply(null)` — a literal null through a
functional interface) and nine sites in `TemporalFrame`. All 19 concentrate in five files.

> **Trap — do not skip this.** NullAway **0.13.6 hard-crashes** in JSpecify mode on JDK 21:
> `Running NullAway in JSpecify mode requires either JDK 22+ or passing the flag
> -XDaddTypeAnnotationsToSymbol=true`. **0.12.10 does not enforce this and runs anyway**, producing
> unverified results. Add `-XDaddTypeAnnotationsToSymbol=true` as its own `compilerArg`.

### 6.2 Test gating: defer, but fix what it hides first

Of 284 measured errors only **~26 are real null flow** (25 null-into-non-null-param, 6 null returns);
**124 are `ex.getMessage()`** JDK-model noise and ~129 are `Map.get()` deref hygiene. Deferring is
defensible — but main-only checking is **structurally incapable** of ever finding this:

```java
/** {@code [lower..upper]}; pass {@code null} for unbounded upper. */
static Multiplicity range(int lower, Integer upper)        // Multiplicity.java:136-138
```

The Javadoc documents null as legal, the parameter is unannotated (→ non-null), and `range()` has
**zero callers in main** — only tests. The record it delegates to is correctly annotated at `:63`.
`PureDateLiteral.DateWithSubsecond.subsecond` and `Compiler.compileModel`'s `model` are the same
shape. **G3.1 — fix these three now**; minutes of work, and no amount of main-only checking will
surface them.

The single cross-module null flow is `Runner.java:1512` → `Ddl.createTable(td, null)`, where `schema`
is declared non-null yet `qualify()` branches on `schema == null`. Annotate the parameter.

### 6.3 Dead config

`-XepOpt:NullAway:CustomNonnullAnnotations=com.legend.NonNull` has **zero usages** in main — expected,
since non-null is already the default in an annotated package. Harmless; keep it for the rare explicit
override, or drop it.

Versions are behind: error_prone_core 2.41.0 (latest 2.50.0), nullaway 0.12.10 (latest 0.13.8).

---

## 7. G4 — Where claims outran the work

Recorded because `CORRECTNESS_REMEDIATION` §1 and `TENET_REMEDIATION` §1.1 both found that this
project's self-descriptions drift flatteringly. The pattern recurred here.

| Claim | Reality |
|---|---|
| Phase 2b: *"full try review (134 sites)"* | The denominator is right, but **no written census exists** — not in `docs/`, not in the commit, which touched **one file and zero source files**. The message accounts for 65 of 134. Two counts are wrong: `finally` blocks are **29** not 31; truly-broad catches are **15** not 14 |
| Phase 0B: *"the three swallows become visible/loud"* | **One is loud.** `InnerDemand:502` genuinely narrowed to `catch (NotImplementedException \| LegendCompileException)`. `DbMetaData:113` and `TestBody:1077` still **swallow** — they now `System.err.println` unconditionally. **A failed H2 replay still returns `ADVISORY_MARKER` and still scores as a pass** |
| C0.3 *(surface `scoreAssert`'s reason)* | 29 of 34 SHAPE rows name their wall; **~30 other `UNSUPPORTED_MARKER` returns set no reason**, including the sibling plan wall at `TestBody:1589` |
| C0.4 *(instrument order exposure)* | 2 of 4 comparators. **The accept criterion — an exact count of order-unverified passes — was never met**; no `[ord]` count exists in any doc or commit |
| C0.1 *(raise truncation)* | Cap raised to 4,000 for FAIL rows — but **4 of 86 FAIL rows sit at 4,050–4,085 chars**, still truncated |

**And two things got worse:**

- **Two *production* classes now write to `System.err` unconditionally** — `DbMetaData:113`
  (`com.legend.exec`) and `TestBody:1077` (`com.legend.harness`), both shipped in `core`. That is
  ~416 stderr lines per sweep, **355 of them by-design non-tabular declines**, emitted on every
  embedding and unit-test run. Signal-to-noise fell while the defect stayed.
  *(Phase 2d `e7d7f6c3` froze `System.err.println` at 40 sites, shrink-only — growth is now locked,
  but the volume and the unclosed swallows behind it are unchanged.)*
- **`ErrorShapeGuardrailTest`'s allowlist is a ratchet pointed the wrong way.** Eleven files —
  including `TestBody`, `Typer`, `Compiler`, `StatementExecutor` — are exempt at **file granularity
  with no count pinned**, so broad catches can accumulate there indefinitely while green. Its own
  miscount (14 vs the true 15) is the first instance: `TestBody:1069`'s
  `catch (java.sql.SQLException | RuntimeException e)` is invisible because the rule's pattern
  requires the broad type to come **first**.

  **The correct pattern already exists in-tree, one commit later.** Phase 2d's
  `ObservabilityGuardrailTest` (`e7d7f6c3`) pins three accretion patterns at **exact measured counts**
  — 17 debug env flags as a closed vocabulary, 40 `System.err.println` sites, 110 string-compared
  function-name dispatches — each shrink-only. That is precisely the shape
  `ErrorShapeGuardrailTest`'s allowlist should have. **G4.1 — retrofit per-file counts onto the
  broad-catch allowlist**, so it can only shrink. Same author, same week, right answer already
  written down.

Other guardrail gaps: `noReturnOrThrowInsideFinally` matches only `} finally {` on one line and does
not skip string literals; `noControlFlowOnExceptionMessageText` scans **core main only**, so the one
instance `AUDIT_PROGRAM` §4.2 actually names — `Runner.unknownTypePull` regexing `[Uu]nknown
(?:type|class|function)` — is out of scope and unchanged; `noUndocumentedEmptyCatch` accepts any
comment, so a bare `;` passes. **Three rules `AUDIT_PROGRAM` §6.5 assigned to a guardrail were not
implemented at all**: a `catch` that returns a value (§4.2 calls this *"the one that silently changes
an answer"*), `default ->` returning a value where a sealed switch should throw, and `endsWith` on
FQN strings.

---

## 8. G5 — Still 0 of 4 on previously-flagged defects

None of the four defects named in `TENET_REMEDIATION` and `CORRECTNESS_REMEDIATION` were fixed by
these 20 commits.

| Defect | State | Note |
|---|---|---|
| `MappingNormalizer.java:1542-1543` silent `"String"` | **Present** | The correct pattern — `throw NotImplementedException` — is **26 lines above** at `:1521` |
| `CsvSeed.java:126` silent `VARCHAR` | **Present** | Now the **only** such fallthrough left in `core` + `engine` main; `Ddl.java:126` already has the exhaustive-throw fix |
| `Scalars.java:2677` blanket `Cast(x, VARCHAR)` | **Present** | Reproduced empirically against freshly-built classes |
| `Pure.java:1844` `toString(any: Any[1])` accepts `RelationType` | **Present** | `Pure.java` has **zero diff across all 20 commits**. `TENET_REMEDIATION` §10 lists this as item #1, un-started |

**One got actively worse.** Phase 1h (`54f260e3`) added `@com.legend.Nullable` to
`nullOfDeclaredType`'s `owner` parameter (`MappingNormalizer.java:1538`) — so the null input now looks
**sanctioned** while the next line still fabricates a column type. The gate documented the hole
instead of closing it. This is the failure mode §3.3 of `AUDIT_PROGRAM` named as the *sentinel* class:
annotating is not deciding.

---

## 9. Preserve these

- **The pom's own commentary.** The `THE NULL GATE` block states why `annotationProcessorPaths` is not
  a dependency, names the `--add-exports` block as load-bearing on JDK upgrades, and declares the
  `AnnotatedPackages` list as a **ratchet that never shrinks**. That is the right way to ship build
  config.
- **`@com.legend.Nullable`'s javadoc** — *"a `@Nullable` on a field/return/parameter is an HONEST
  declared sentinel, not a licence."* Exactly the distinction G5 shows is easy to lose.
- **Zero `@SuppressWarnings("NullAway")`.** The escape hatch exists and was never used.
- **The `ArchitectureTest` relaxation is acceptable and should be recorded, not reverted.** Invariants
  6a/6g/6h/6j were widened with `.or(NULLNESS_ANNOTATIONS)`, so `com.legend.sql` no longer *"depends
  only on itself and the JDK."* Annotation-only, `CLASS` retention, no runtime coupling — but it is
  the sole guardrail relaxed in the range and belongs in the ledger.

---

## 10. Sequencing

1. **G0.1 `clean` in the protocol, G0.2 CI.** Everything below is advisory until this lands.
2. **G1.1 + G1.2 — `WindowCall`'s missing child, and the test that catches the class.** A live
   wrong-answer introduced by this work.
3. **G3 — `JSpecifyMode` + `-XDaddTypeAnnotationsToSymbol=true`, bump NullAway, enable
   `CheckOptionalEmptiness`.** 32 findings for a handful of config lines.
4. **G2 — `NavMaterializer:96`**, then §5.1's three.
5. **G3.1 — the three `Multiplicity`-class signatures** that main-only checking can never see.
6. **G5's four defects.** `Pure.java:1844` first: it is a latent wrong answer with no test covering it.
7. **G4 — close the guardrail gaps**, especially the catch-that-returns rule, and pin per-file counts
   on the broad-catch allowlist so it can only shrink.
8. **§5.2 — messages on the 166 anonymous `requireNonNull`s.**
9. **Test-source gating**, once §6.2's three signatures are fixed and the `getMessage()` noise has a
   plan.
