# Architecture Remediation Plan

> **Status:** active work plan. Derived from the 2026-07-28 three-round architecture audit

## Status ledger (updated 2026-07-28, session 01Qd…Yvwi)

| item | status | note |
|---|---|---|
| **T0.1** pct gate | ✅ `8cb07093` | `testFailureIgnore` deleted; hid 4 reds (ONE root cause: relational substring divergence, incl. 2 key-sorts whose keys are substrings) — ledgered as reference-parity pins (`docs/PCT_EXPECTED_FAILURES.md`); PCT 1109/1109 gating. |
| **T0.2** corpus assert | ✅ `8cb07093` | Per-family pass baseline asserted against the committed scoreboard; verified red on a forced regression. Viable because the flapper arc (`d21548f2`) made sweeps byte-identical. |
| **T0.3** honest numbers | ✅ | ENGINEERING_LOG states 2004/2538 = 79.0% as pass/total (now 2005). |
| **T1.1** versionSweep | ✅ `878394fe` | Fixed + pinned; lossy `TypedGetAll` ctor DELETED (T2.2 discipline). |
| **T1.2** date truncation | ✅ `878394fe` | Structural `PureDateLiteral.strictDatePart()`; substring surgery gone. |
| **T1.3** frame clobber | ✅ `878394fe` | TWO leaks (audit's + a hop-window leak the pin caught live: `p_from <= inner-date`); killed as a class at `resolveChain` save/restore. Residual: IdentityHashMap iteration-order sensitivity → T3.1. |
| **T1.4** equal guard | ✅ verdict, no change | The guard inlines pure's `[0..1]` ORDERING overload bodies; `equal` is a total native (`[] == []` true — guard would break it). Both-NULL divergence = reference relational behavior (bare `=`, no `IS NOT DISTINCT FROM` in any golden). Documented at the rule site. |
| **T1.5** pureKindOf | ✅ `5477a068` | Exhaustive over all 22 variants; extracted to `RelationalKinds` (T3.1 one-reader seed); reflective pin. |
| **T1.6** precedence | ✅ `5477a068` | Four composite arms parenthesized + render pins. |
| **T1.7** AGG_FQNS | ✅ `5477a068` | Hand list DELETED; membership = the reducer catalog (`Aggregates.isReducer`). |
| **T1.8** avg | ✅ `5477a068` | Catalog pair registered; end-to-end grouped-mapping pin. |
| **T1.9** Phase-H skip | ✅ fix / ⚠ residual `5477a068`+`8e1f79f4` | Effect-let path resolves before the back-half. Residual: the inliner substitutes lets forward, so a class chain can sit INLINE in a K-native string arg — a scalar-root shape the resolver has no arm for; pinned as the LOUD wall (flips to a row assert when the arm lands). |
| **T1.10** assoc synthesis | ✅ `8e1f79f4` | Pick-and-translate aligned on the resolved tree — and the SAME bug one level deeper fixed (`resolveViewRefsInJoin` anySide: reverse-end view joins resolve; frame refs verbatim). Pinned both directions. |
| **T2.1** withChildren | ✅ `41fe8523`+`e4fc1671`+(step 3) | Mandatory `withChildren` on all 68 variants + round-trip/arity pins; `mapChildren` default on `TypedSpec`; generic arms of `UserCallInliner` (one delegating default), `StoreResolver.resolveNode` (22 wrapper arms), and `Substitution` (rewrite + inlineParam) collapsed onto it. Three live dropped-field bugs died with the hand rebuilds: inliner `aggCol()` nulled `TypedAggCol.orderKey` (pinned fails-before), the inliner's `TypedFold` arm skipped MapReduce strategy lambdas, and `resolveNode`'s `TypedJoin` arm nulled `frameName` via the 6-arg compat ctor. |
| **T2.2** lossy ctors | ✅ (this session) | All seven short overloads DELETED — `SqlAgg.Reducer` 3-arg (orderBy), `TypedAggCol` 3-arg (orderKey/orderAsc), `KeyExpression` 1+2-arg (isLocal), `ClassMapping.Relational` 11-arg (propertyTargetSets), `SqlSelect.SortKey` 3-arg (outputName), `TypedCast` 3-arg (wire), `TypedJoin` 6-arg (frameName), `SqlSource.Subselect` 2-arg (frameName); ~115 construction sites name every field. Rebuild walkers now thread state: DuckDb unqualify + pivot USING keep orderBy; SqlPostProcessors keeps outputName; SyntheticHeads/StoreResolver agg walkers rewrite orderKey like map; TemporalFrame join rebuild keeps frameName; NameResolver uses `KeyExpression.withValue`. `Bindings.copy()` preserves rigid+contravariantDepth (fails-before pin `BindingsTest`). Copiers added: `Reducer.withArgs`, `KeyExpression.withValue`. |
| **T2.3** strategy enum | ✅ (this session) | `MilestoningStrategy` enum (BUSINESS/PROCESSING/BITEMPORAL + Dimension + `has()`); `ofStereotypeOrNull` is the ONE stereotype funnel; `Temporal.strategyOf`, `TemporalFrame.temporalStrategy`, `TemporalContext` (incl. `rangeDim`), `midPrefixToDim`, GraphEmission, StoreResolver, Typer all typed. Acceptance holds: zero strategy literals outside the funnel. Deferred: `MilestoneBlock` sealed unification of the Business/Processing block records — lands with Leg 2 (milestoning calculus port), which reworks those exact decoders; an unconsumed interface now would be dead code. |
| **T3.1** do-alongside | ✅ (this session) | All four smaller same-pattern items: ONE shadowing-aware `VarUse.reads` (5 copies → 1; three were shadow-blind); `PureDateLiteral.Precision` (the two incompatible integer scales in Scalars both derive from it; Typer.dateType reads it); `RelationalDataType` switches exhaustive (TestDataGenerator's silent `default -> VARCHAR` gone; Ddl/PlanText default-throws are explicit walls); `WindowFrame` classified at OverChecker (`Frames.classify`, boundary validation at Phase.TYPE like the engine's ExtendChecker; Lowerer maps 1:1 — the 110-line literal-sign re-derivation deleted). |
| **T3.1** Space core + determinism | ✅ (this session) | B1: `Anchors` owns all three reaches (memoized `anchored`, static `containsGetAll`, navigate-exempt `anchoredInFlow` — the silent disagreement is now two NAMED semantics). B2a: `Space` (OBJECT/ANCHORED/INERT) decided once per node — one classifier over checked types, memoized; `isObjectSpace` deleted. B2b: `resolveNode` is a TWO-LEVEL dispatch (space-independent normalizations → exhaustive space switch → variant arms); the silent default pass-through is the INERT level; ANCHORED's only default is the named wall; guard order became structure. O(n) resolution. B3: determinism census found the audit's `inQueryReads` attribution WRONG (identity maps are looked up, never iterated; no identity-keyed structure is iterated anywhere in core; `Map.of` salted iterations are registration-only); the historical bistability no longer reproduces after the T2.1/T3.1 arc — 12/12 fresh-JVM runs; the T1.9 pin is tightened to ROWS-ONLY and ResolveNestedTemporalFrameTest held single-outcome. Deliberate deltas from the audit's text: stamp-from-`Application.chosen()` rejected (layering/drift — classifier over checked types instead); bottom-up anchor-driven inversion an explicit NON-GOAL (chain-as-rewrite-unit is the better architecture — user-ratified). |
| T3.2 / T4 | pending | |

> (34 agent reports; stage-by-stage reading of all 68,263 lines of `core/src/main/java`).
> **Scope:** `core/` only. `engine/` is on death row and is addressed once, in T4.4.
> **How to use this:** tiers are ordered by what to do first. Within a tier, items are
> independent unless a dependency is stated. Every item has an acceptance criterion.

---

## 0. The one defect

Thirty-four reports converge on a single mechanism:

> **The pipeline computes a fact, drops it at a phase boundary, and re-derives it
> downstream from weaker inputs — and the copies diverge.**

The important refinement: **this is inconsistency, not ignorance.** The codebase already
applies the correct pattern in six places — `TypedFold.strategy`, `TypedNavigate.form`,
`PureSql.type`, `CoreFn`, `SqlAgg` position-typing, and `Fold`, whose javadoc states the
principle outright:

> *"Master plangen re-derived this per operator and drifted; this class is the one place
> the knowledge lives."*

It was applied to `fold` and `navigate` and **never** to `space`, `frame`, `precision`, or
`column type`. Most of this plan is finishing that pattern, not inventing one.

**Measured cost:** ~2,130 lines of pure recomputation (3.1% of core), 3,000–4,000 once
shape is included. Plus a complexity result: `StoreResolver.resolveNode` is **O(42·n²)** —
42 ordered guards, each a full recursive subtree walk, re-evaluated per node — to recover
one bit the Typer knew by construction.

---

## 1. Decisions taken

These were open questions in the audit. They are now settled and the plan assumes them.

| # | Decision | Consequence |
|---|---|---|
| D1 | **N backends is a hard requirement.** It is what legend-engine *is*. | The dialect layer must be rebuilt (T3.2). Not optional, not deferrable. |
| D2 | **Execution is a product, not a test harness.** | Session/transaction/limits/error translation is a missing subsystem to build (T4.2), not a cleanup. |
| D3 | **Split F into F1-Knowledge / F2-Work.** | T4.1. Two auditors reached this independently. |
| D4 | **`AGENTS.md` describes `engine/`, not `core/`.** The shipped design is *better* than the one the doc prescribes. | Fix the doc, not the code (T4.5). |

---

## 2. What NOT to do

Three hypotheses the audit **refuted**. Do not spend effort here.

- **Milestoning does not leak downstream.** Verified by exhaustive grep: `lowering/` 10
  lines, `sql/dialect/` **zero**, `exec/` **zero**. 77% sits in the resolver where its
  knowledge belongs and it exits as ordinary comparison predicates. *This is the reference
  example of a correctly-placed expensive feature.*
- **The Element/Expression compiler recursion is not a cycle.**
  `grep "com.legend.compiler.spec" compiler/element/` returns nothing. F-never-triggers-G
  holds without exception.
- **Relation / TDS / Class-space are not parallel pipelines.** TDS desugars to Relation
  before typing; zero duplicated operator semantics. It is a deliberate improvement over
  real Legend, which carries two disjoint libraries and an incomplete bridge.

Also preserve, do not "clean up": the **stage-3 mapping wall** (15 sealed mapping variants
appear in **19 lines out of 30,000** downstream), **M2M**'s collapse-at-one-seam design,
and both guardrail tests with their **empty allowlists**.

---

## TIER 0 — Restore the gates

**~10 lines. Do this before anything else — every item below is unverifiable without it.**

Two of the four gates in the documented protocol cannot fail.

### T0.1 — `pct` build ignores test failures
`pct/pom.xml:225` sets `<testFailureIgnore>true</testFailureIgnore>`. The documented gate
`cd ../pct && mvn -o test # 1109/1109` prints BUILD SUCCESS regardless of outcome.

**Do:** delete the line. Fix or ledger whatever fails.
**Accept:** `mvn -o test` in `pct/` returns non-zero when a PCT test fails.

### T0.2 — The corpus sweep has no assertions
`engine/src/test/java/com/gs/legend/rcorpus/RelationalCorpusRunner.java` is 241 lines and
contains **zero** assertions. `mvn -o test -Dtest='RelationalCorpusRunner'` — step 3 of the
gate protocol, described in `ENGINEERING_LOG.md` as *"the only trusted gate"* — exits green
whether the sweep scores 1,258 or 12.

**Do:** assert a baseline pass count in `scoreboard()`; fail on regression.
**Accept:** artificially breaking one family makes the build red.

### T0.3 — Correct the bootstrap numbers
`ENGINEERING_LOG.md` reports *"1085 pass / 53 fail of 2538"* — quoting pass and fail while
omitting 592 errors and 625 shape. Read naturally that says 95% done. Actual: **1258/2538 =
49.6%, with 1,280 non-passing.** The generated table is honest; the prose summary is not,
and it is the file every new session bootstraps from.

**Do:** state the rate as pass/total. Refresh the stale counts.

---

## TIER 1 — Silent wrong answers shipping today

Independent of every refactor below. Ranked by how quietly each fails.

| # | Bug | Site | Failure mode |
|---|---|---|---|
| T1.1 | `versionSweep` dropped when rebuilding a dated fetch | `UserCallInliner.java:379` | `allVersionsInRange` on a bitemporal class silently becomes a **point fetch**. The sibling `TypedMilestonedAccess` arm 6 lines below threads its flag correctly. *Verified.* |
| T1.2 | Date truncation via `substring(0,10)` | `TemporalFrame.java:285` | `toEngineString()` formats the year with `%d` (not zero-padded), so `%12024-03-15T10` → `"12024-03-1"` → re-parses as `StrictDate(12024,3,**1**)`. **Day 15 becomes day 1.** |
| T1.3 | Nested resolution clobbers the temporal frame | `StoreResolver` — `inQueryReadsFor` → `collectOpChain` | `this.temporal` is reassigned with the inner fetch's context, no save/restore; the outer chain's specs then attach to it. Reachable whenever a temporal root carries `->in(Other.all()…)`. |
| T1.4 | `equal` excluded from the `[0..1]` null guard | `Scalars.java:76-102` | Ordering comparisons, `startsWith`, `endsWith`, `contains` all get `optionalOperandGuards`; `==` does not. Composed with the reader's null-drop rule this changes result **cardinality**, not just values. |
| T1.5 | `pureKindOf` returns `null` for 7 variants | `MappingNormalizer.java:2552` | `null` is indistinguishable from "column not found", so the declared-type coercion is **silently skipped**. The two column-type switches disagree on **9 of 22** variants. |
| T1.6 | Precedence ignored in string-returning arms | `AnsiSqlRenderer.java` — `XOR`, `ADD_INTERVAL`, `UC_FIRST`, `LC_FIRST` | `a AND xor(b,c)` renders unparenthesised and misparses. Root cause: `expr()` returns `String`, so each node commits to parenthesisation before it can see its context. *Verified.* |
| T1.7 | `AGG_FQNS` missing `stdDev`, `variance` + 13 others | `CorrelatedSubselects.java:951` | Skips the wall whose own comment reads *"max() > 30 becoming any-match"*. `->stdDevSample()` hits it; `->stdDev()` does not. |
| T1.8 | `avg` has no lowering | `MappingNormalizer:136` vs `Aggregates`/`Scalars` | Five owners disagree. A `~groupBy` using `avg(COL)` type-checks and dies at lowering with an internal error. The test suite **pins the gap**. |
| T1.9 | Two back-half sequences skip Phase H | `StatementExecutor:122`, `evalStringArg` | A `let x = executeInDb($sql)` whose `$sql` came from a class query reaches the Lowerer with `TypedGetAll` intact. |
| T1.10 | `AssociationSynthesis` picks target from one tree, translates another | `AssociationSynthesis.java:325-348` | Computes view-resolved `cond2`, uses it to pick the target table, then translates the **raw** `jd.operation()`. |

**Accept (each):** a regression test that fails before the fix and passes after. Note that
8 of 8 spot-checked recent fix commits added **zero** regression tests — that pattern stops
here.

---

## TIER 2 — Mechanical deletions

**1–2 weeks. Low risk, compiler-verified, large deletions. All three finish a pattern the
codebase already applies correctly.**

### T2.1 — `TypedSpec.withChildren()`
`TypedSpec` mandates `children()` and offers no way to rebuild. So every *rewriter*
re-enumerates all 67 variants by hand: **~450 lines of boilerplate across three walkers**,
**~390 `case Typed*` arms across eight files**, and five partial rebuild walkers in the
resolver with three different failure modes (throw / pass-through / silently miss).

**Found independently by four auditors** — the most corroborated finding in the audit.

**Do:** add `TypedSpec withChildren(List<TypedSpec>)` as a mandatory member, mirroring
`children()`. Collapse the generic arms of `UserCallInliner`, `StoreResolver.resolveNode`,
and `Substitution` to one delegating default.
**Accept:** adding a `TypedSpec` variant produces a compile error in every rewriter.
**Bonus:** enables deleting `resolveNode`'s `default` arm in T3.1.

### T2.2 — Delete the lossy convenience constructors
Six instances of the same shape — a shorter overload delegating with a default, and real
call sites silently discarding state:

| Record | Silently dropped |
|---|---|
| `TypedGetAll(fqn, dates, info)` | `versionSweep` — **causes T1.1** |
| `SqlAgg.Reducer(fn, args, distinct)` | `orderBy` |
| `TypedAggCol(name, map, reduce)` | `orderKey`, `orderAsc` |
| `KeyExpression(value, isAdd)` | `isLocal` |
| `ClassMapping` 11-arg | `propertyTargetSets` |
| `Bindings.copy()` | `rigid`, `contravariantDepth` — **already broken** |

**Do:** delete the short overloads; add `with*` copiers so state can only be *transformed*,
never re-founded.
**Accept:** every construction site names every field; `Bindings.copy()` preserves all four.

### T2.3 — Milestoning strategy becomes an enum
The strategy is a nullable raw `String` compared at **56 literal sites across 7 files**
with **zero compiler enforcement**. Adding a strategy compiles clean everywhere; a forgotten
site produces a **silently unfiltered temporal extent** — more rows, no error.

The codebase already fixed this at exactly one of 56 sites (`TemporalContext.single`, tagged
"audit 23") and left the rest.

**Do:**
```java
enum MilestoningStrategy { NONE, BUSINESS, PROCESSING, BITEMPORAL;
    static MilestoningStrategy ofStereotype(String profile, String name); // throws on unknown
    boolean has(Dimension d);
}
enum Dimension { BUSINESS, PROCESSING }
```
Plus `sealed interface MilestoneBlock` so `Business`/`Processing` stop being two
structurally-identical records with different field names (that asymmetry forces four
separate strategy→block decoders in `TemporalFrame` alone).
**Accept:** zero string literals matching the three stereotype names outside
`ofStereotype`; adding a fourth strategy is a compile error at every dispatch.

---

## TIER 3 — The two big levers

### T3.1 — Stamp `Space` on construct nodes  *(2–4 weeks)*

**The highest-leverage change in the plan.**

The Typer knows whether every expression is class-space or relation-space — it *constructs*
`TypedGetAll` and `TypedTableReference`, and `Application.chosen()` holds the resolved
overload at the moment of emission. **20 of 32 checkers throw that away.**

The resolver then recomputes it: `containsGetAll` (full recursive subtree scan, 31 sites)
and `isObjectSpace` (spine walk, 28 sites) guard ~30 of the 44 arms of `resolveNode`. Java
evaluates guards in source order and `resolveNode` recurses ⇒ **O(42·n²)**.

There are also **two `containsGetAll` implementations that disagree** (`ClassSources:260`
descends `TypedNavigate.source()`; `StoreResolver:1203` descends everything).

**Do:** add a `Space` field (`OBJECT | RELATION | COLLECTION`) set by the checker from
`Application.chosen()`. Collapse the 42-guard switch to a two-level dispatch. Delete both
predicates and all 59 call sites.
**Accept:** `containsGetAll` and `isObjectSpace` do not exist; `resolveNode` has no
`default` arm; resolution is O(n) in tree size.

**Same pattern, smaller, do alongside:**
- `TypedOver.frame: Optional<TypedSpec>` → `Optional<Frame>` with a sealed `Bound`.
  `OverChecker.isFrame` classifies by exact class FQN at `:47` and discards it; the Lowerer
  spends **108 lines** re-deriving it — including PRECEDING/FOLLOWING **from the sign of a
  numeric literal**. The destination type `SqlExpr.WindowCall.Frame` already exists, fully
  structured.
- One `DatePrecision` enum on `PureDateLiteral`. Four mutually-incompatible ladders exist
  today (two on *different integer scales* in the same file); ~590 lines re-derive precision
  against ~190 that define it. **Kills T1.2.**
- One `RelationalDataType` reader. `StoreCompiler.columnType` is exhaustive with no default;
  `pureKindOf` has 15 arms and `default -> null`. **Kills T1.5.**
- One shadowing-aware `VarUse.reads()`. Five implementations exist; **only one** stops at a
  shadowing lambda.

### T3.2 — Rewrite-then-render for N backends  *(4–8 weeks)*

**Promoted to Tier 3 by decision D1.** The current design has already failed its only test.

Override counts against the ~47 protected hooks:

| Dialect | Overrides | What |
|---|---|---|
| `DuckDb` | 21 | leaf hooks — the base was written for it |
| `EngineStyleH2` | 7 | **`select`, `source`, `projection`, `expr`, `call`** — the template itself |
| `Sqlite` | 1 | `reservedWords`. It is a keyword list, not a backend — and it is instantiated in production at `Compiler.java:333`. |

> A template method whose only genuinely-different subclass overrides the template is not a
> template method — it is a base class with helper functions.

Three consequences, all measured:

1. **152 lines of MIR→MIR rewriting hide inside render methods** (`DuckDb.unqualify`,
   `unwrapElemRefs`, `foldCall` *synthesising fresh IR*, the qualify self-wrap) — because
   there is no pass slot.
2. **`AnsiSqlRenderer` is a DuckDB renderer with an ANSI name** — 81 of 122 arms hardcode a
   spelling with no override hook (`time_bucket`, `epoch_ms`, `//`, `printf`, `MAP {}`,
   `HUGEINT`, `regexp_full_match`).
3. **The MIR is DuckDB's syntax tree, not a relational algebra.** `qualify` is a *slot in
   the shared IR*; so are `Pivot`, `ASOF_LEFT`, `StarExcept`. Interchange encodings are
   DuckDB's too — `STRFTIME` carries C-format strings, so `EngineStyleH2` must re-parse them.

**The target shape** — and the codebase already contains a working instance of it,
`SubselectPrune` (333 lines, dialect-agnostic MIR→MIR):

```
lowering ──▶ SqlPlan { query, schema }     (relational algebra: no clause slots,
                 │                          no QUALIFY, no PIVOT, no Star)
                 ▼
        MIR → MIR pass pipeline
          common:      FlattenConcat, SubselectPrune, Parenthesize
          per-dialect: QualifyToSubselect, PivotToCaseAggregation,
                       FoldToListReduce, LateralToCorrelated, AssignAliases
                 ▼
        SqlWriter — ONE class, total, no throws, no subclasses
          parameterised by Lexicon + Spellings + TypeNames
                 ▼
              String

Dialect = record { passes, lexicon, spellings, typeNames, capabilities }  // DATA
```

Adding Postgres becomes: a `Dialect` record, ~4 pass entries, a spelling diff, a `Lexicon`.
Nobody edits `SqlWriter` — and **cannot break DuckDB by doing so**, which is the property
the current design most lacks.

**Migration, cheapest first (each independently shippable):**
1. Extract `Lexicon` + `TypeNames` from the ~20 never-overridden hooks. Pure refactor.
2. Make precedence a property of the walk; add a `Parenthesize` pass. **Kills T1.6.**
3. Move the five in-renderer rewrites out to named passes. Largest win per line.
4. Split `SqlPlan` from `SqlQuery`; lift `normalize` to a `ValueCodec`.
   (`SqlDialect.normalize` currently has **zero overrides** while all DuckDB-specific
   decoding sits hardcoded in the backend-*agnostic* `Executor`.)
5. De-syntax the MIR (`qualify`, `Pivot`, `Star`). The expensive one; touches the lowering.

**Accept:** a second *executing* backend passes a dialect-parameterised conformance suite.
None exists today — every render assertion instantiates `new DuckDb()`.

---

## TIER 4 — Structural

### T4.1 — Split F into F1-Knowledge / F2-Work  *(multi-week)*

**Decision D3.** Phase F does two unrelated kinds of work, and Phase E runs before both.

- **Knowledge** — kind manifest, class property types and multiplicities, supertypes, store
  table schemas, temporal strategies. Cheap, factual, no compilation.
- **Work** — compiling the bodies E lifts; mapping-binding integrity.

Because E runs first, it cannot ask, so it **hand-rolled ~445 lines of shadow type system**
(`findPropertyTypeDeep`, `findPropertyDefDeep`, `isSubclassOf`, `pureKindOf`,
`declaredPlatformKind`, `columnPureKind`) that returns `String` where the real API returns
`Type`, skips derived properties, and defaults to `"String"` when a lookup fails. Because
it works on an unvalidated model, it grew the **poison mechanism** — deferred failure
through a public mutable map — plus ~40 null-tolerance guards.

**Target order:**
```
parse → name-resolve → F1 (Knowledge) → validate → E (normalize) → F2 (Work) → G (type-check)
```

**Why it's safe:** normalization is *append-only* — `ModelNormalizer:118-122` states stored
properties, supertypes and joins are untouched — so F1 computed before E and after E would
agree. `ModelIntegrity` **already splits along this line**: only `checkMapping` needs E's
output.

**Two known blockers, both bounded:**
- `ModelBuilder.from` mutates the model (`ingestRuntime`'s JSON cross-bake), which is why
  `MappingNormalizer:172` must "re-fetch the latest legacy surface" — a consumer documented
  as unable to trust its own input. Move the rewrite into E.
- `PureModelContext.from` receives a `NormalizedModel` and converts it *back* to a
  `ParsedModel` to dodge a package cycle — laundering its own phase gate.

**Accept:** the shadow type system is deleted; E consumes `ModelContext`; validation runs
before E; poison carries only genuine "not built yet" walls.

### T4.2 — Build the execution subsystem  *(multi-week)*

**Decision D2.** Execution is currently shaped as a corpus-test driver.

- **No ceiling.** Zero occurrences of `setFetchSize`, `setMaxRows`, `setQueryTimeout` in all
  of `core/src/main/java`. Everything drains into `ArrayList`s of boxed `Object`s.
  **Graph fetch returns the entire object graph as one Java `String`.** Nothing in the API
  signals the limit.
- **No session layer.** No `DriverManager`, `getConnection`, `setAutoCommit`, `commit`, or
  `rollback` anywhere in core. Connections arrive as method arguments; every caller is a
  test or the harness. Zero transaction control means `executeInDb`'s per-statement failure
  sink tolerates partial failure *because it cannot roll back*.
- **No error vocabulary.** Raw `SQLException` reaches the public API; the error package has
  no execution member.
- **`StatementExecutor` is a Pure interpreter, not an executor** — 1,023 lines with no JDBC
  beyond four calls out, and **~230 lines of typed-HIR rewriting running at execution time**
  (`spliceHook`, `buildFrame`), untestable as a phase.
- **The single-statement plan is already outgrown in four places**, each hand-rolled —
  including `buildFrame` executing a query eagerly at the `let` and **re-executing the same
  chain later** (doubled work, invisible, unbounded).

**Do:** `Session` (connection + transaction), `ExecPolicy(fetchSize, maxRows, timeout)`,
`QueryPlan(List<Step>, shape, RowDescriptor)`, an `ExecutionException` boundary, and a
streaming `Tabular`. Move the interpreter and the splice to a real phase upstream.
**Accept:** a 10M-row query completes or fails politely; a mid-sequence failure rolls back;
no vendor error text reaches a user unlabelled.

### T4.3 — Give the front end positions  *(1–2 weeks, unblocks everything diagnostic)*

**0 of ~66 AST record types carry a span**, while `TokenStream` holds exact `starts[]`/
`ends[]` for every token and forwards none of it. Every downstream error is decorated with
the enclosing element's *first character*; after `ModelNormalizer` even that is gone
(`NormalizedModel` drops `source`, `elementOffsets`, `elementImports`, `elementSources`).
Already filed as `[H][arch]` in `AUDIT_2026_07.md`.

**Do:** `int start, int end` on every `model/` and `model/spec/` record (~100 parser sites);
carry the source and span table on `NormalizedModel`.
**Prerequisite for T4.6.** Also: `SpecParser.parseFrom(stream, pos) → (node, endPos)` —
~40 lines that delete ~200 and most of the **21 hand-rolled brace matchers** (21 different
terminator policies today).

### T4.4 — Port the tests, then delete `engine/`
**67% of all test code (62.5K lines) lives in the module slated for deletion**, and
**74.5% of it routes to `core`** through the gated seam — provably: `AbstractDatabaseTest`
catches `com.legend.error.LegendCompileException` and cannot compile unless core runs.
Deleting `engine/` without porting destroys the real safety net.

Good news: **`rcorpus/` is already pure core code** — all 1,662 lines have zero
`com.gs.legend` references beyond their package declaration. That step is a *file move*.

**Order:** move `rcorpus/` → close the 5 un-gated `QueryService`/`PlanGenerator` overloads
(including the entire **streaming API**, which runs the legacy pipeline with no core
coverage) → kill the live double-parse → port the ~46.5K lines → retarget `pct`/`nlq` →
delete.

### T4.5 — Fix the governing documents
- **`AGENTS.md`** — its layer table names `PureModelBuilder`, `TypeChecker`,
  `MappingResolver`, `SQLDialect`, `NormalizedMapping`; **8 of 10 have zero occurrences in
  `core/`** and `NativeBindingTable` exists in neither module. Rewrite against `core/`.
  Per D4, the two-compiler recursion section prescribes a *worse* design than the one that
  shipped — correct the doc.
- **`README.md`** documents `engine/`'s package structure; `core/` is not mentioned once.
- **`FAQ.md:107`** teaches `new SqlExpr.FunctionCall("listTransform", …)` — the exact
  expression `AGENTS.md:153` lists as a stop sign.
- **`pipeline-architecture.md` / `frontend-architecture.md`** are marked `status: active`
  with verification greps hardcoded to `engine/` paths — they **pass vacuously**.
- Link `docs/PIPELINE.md` and `core/README.md`, which are accurate and referenced by nothing.

### T4.6 — Two guardrail tests
`CodeShapeGuardrailTest` already walks the tree for file size, method length, and static
state. Add:
- **Duplication:** normalized-token-hash of any ≥10-statement window appears at most once.
  Detector already written (~120 lines) in the audit scratchpad.
- **Exhaustiveness ratchet:** per-package share of type-pattern switches with no `default`
  may not decrease. Baseline today: `compiler/` **85%**, `sql/dialect/` 72%, **`resolver/`
  0%** (23 of 23 defaulted), `exec/` 0%, `harness/` 0%.

---

## 5. Sequencing

```
T0 (gates)  ──▶ everything else                 [do first, ~10 lines]
T1 (bugs)   ──▶ independent, parallelisable
T2.1 (withChildren) ──▶ T3.1 (Space)            [T2.1 makes T3.1's default-deletion possible]
T2.3 (strategy enum) ──▶ T4.1 (Split F)         [enum first; F1 then serves it]
T4.3 (positions) ──▶ T4.6 diagnostics
T3.2 (dialect) ──▶ independent of T3.1/T4.1     [can run in parallel with a second team]
T4.4 (port tests) ──▶ before any engine/ deletion
```

**If only one thing gets done:** T0. Two of your four gates are theatre, and every item
above is unverified without them.

**If only one *architectural* thing gets done:** T3.1. It deletes the most code, removes an
O(n²), and fixes a divergence class — and T2.1 makes it cheaper.

---

## 6. Preserve these

Named so a refactor does not destroy them:

- **`Fold`** — the one-owner decision oracle, and the pattern this whole plan generalises.
- **The stage-3 mapping wall** — 19 leaked lines out of 30,000 downstream.
- **`M2M`** — collapses at one seam; `ClassSource` *structurally cannot* carry the kind, so
  nothing downstream can re-derive it. Grep for `isM2M` returns zero.
- **`SqlFn`** — 162 constants, zero `default` arms, javac-enforced across every dialect.
- **`SqlAgg` position typing** — `LAG` in a GROUP BY projection is a compile error.
- **`Hash` / `ContentStore`** — a key type that cannot be built from a name.
  *(Note: currently **zero production callers**. Wire it up — `Compiler.executeResolved`
  recompiles the entire model on every query.)*
- **`ArchitectureTest` + `CodeShapeGuardrailTest`** — ~20 rules, **empty allowlists**.
- **`PureSql.type`** — exhaustive Pure→SQL boundary with a throwing arm per unsupported kind.
- **The comments.** Several audit findings came from the code admitting them. Keep that
  habit through any restructure.
