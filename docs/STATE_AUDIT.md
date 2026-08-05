# State audit — void methods, mutable state, ambient state, determinism

> Three exhaustive enumerations over `core/src/main/java`, per `AUDIT_PROGRAM.md` §1: countable
> denominators, taxonomy fixed before the sweep, `N found / N classified / K flagged` reported.
>
> **Companions:** `AUDIT_PROGRAM.md` (method), `CORPUS_TAXONOMY.md` (the burndown),
> `TENET_REMEDIATION.md`, `NULL_GATE_VERIFICATION.md`, `H2_BACKEND.md`.

**Read at `VERIFIED@0a7e2108`.** Claims marked ✅ were re-verified against source by hand after the
sweep. Several were produced by executing code — running the flapper's SQL 200×, running the golden
under three locales, running the corpus under two DuckDB thread counts.

---

## 1. Verdict

**The codebase is materially cleaner on state than assumed, and the findings sit in a class the
taxonomy did not have a name for.**

| | |
|---|---|
| **Zero** static non-final fields | …because `noStaticMutableState` already gates it (§2) |
| **Zero** clock reads, filesystem access, `Locale`/`Charset`/`TimeZone.getDefault`, `Random`, parallelism | in `core/src/main` |
| `Compiler` and `SpecCompiler` | contain **no `void` token at all** |
| Cross-phase mutation | **16 hits repo-wide, none inside a `void` method** |
| Typed IR | write-protected at *runtime* — 30 of 76 files under `compiler/spec/typed/` copy in compact constructors |
| `IdentityHashMap` sites | **21 of 21 lookup-only** — identity hash never reaches output |
| Bare-hash declarations | 140, of which **8 iterated, 2 observable** (against 294 `LinkedHashMap` + 215 `LinkedHashSet`) |
| Scoreboard | **byte-identical** across two full sweeps + the committed file |

**What is wrong divides into exactly three things:** ambient state that steers generated SQL (§4);
one guardrail blindness that hides most of it (§3); and a harness that masks two production-only
failure modes (§7).

---

## 2. The denominators — and why the taxonomy needed correcting first

### Void methods — 306 found / 306 classified / 84 flagged

| Class | N | |
|---|---:|---|
| call-scoped fill | **160** | 129 self-recursive walkers; every sink verified allocated by the immediate caller |
| validation | 37 | |
| registration/init | 15 | |
| IO/boundary | 10 | |
| **denominator** | **222** | 72.5% |
| field mutation | 49 | but see the split below |
| argument mutation | 27 | |
| side-channel | 6 | all write a `static final ThreadLocal` |
| swallow | 2 | |
| **findings** | **84** | 27.5% |

Reporting "49 field mutations" as one number would mislead. **38 are one-shot cursor/builder objects
never published** (`Lexer` ×22, parser cursors ×8; 14 already in `MUTABLE_FIELD_ALLOWLIST` with
reasons). Only **11 are real** — 6 in `EngineStyleH2`, 5 genuine service state.

Second denominator: **180 void-bodied lambda/method-ref sites**. 178 delegate into an
already-classified walker and inherit its class; **2 are findings in their own right** — the
`() -> ACTIVE.set(...)` / `() -> FILTER_POS.set(prev)` scope closers.

### Mutable state

| | |
|---|---:|
| Type declarations | 757 — **476 records**, 192 classes, 55 interfaces, 34 enums |
| **`static` non-final fields** | **0** |
| `static final` holding a *mutable object* | **22** — 8–10 ThreadLocals, 2 `AtomicInteger`, 10 lookup tables |
| Instance fields (non-record) | 188 — 32 non-final, **57 final-but-mutable**, 99 clean |
| Records with ≥1 collection/array component | **174 of 476** (315 components) |
| …defensively copied | **159 (50%)** — 93 fully defended, 2 partial, **79 with zero defense** |
| Phase classes | 23 — **14 hold post-construction mutable state** |
| Transaction-control calls repo-wide | **0** |

### The taxonomy correction, made before the sweep

**"Static mutable" is an empty class — not because it was never a problem, but because a gate solved
it.** `noStaticMutableState` exists and its failure message reads *"no allowlist for this one; make it
final or design it away."* The population it was written for migrated into two classes nobody had
named:

- **final-but-mutable** — 57 instance + 12 static fields where `final` guarantees only the reference
- **ambient** — the ThreadLocals of §4

> This is the strongest available evidence for `AUDIT_PROGRAM.md` §6.5's *prefer a gate to a finding*.
> A gate converted a finding class into a solved problem — **and the finding relocated to a class no
> gate covers.** Expect that. Name the new class when it appears.

---

## 3. S0 — the instruments (do first; ~½ day)

### S0.1 — every field pattern is blind to wrapped declarations ✅

`CodeShapeGuardrailTest`'s field regexes require the terminating `;` on the **same physical line**, and
the scanner never joins lines. That single hole hides:

- **9 of the 10 ThreadLocals** in the codebase
- **exactly one non-final instance field** — `SyntheticHeads.canon`, whose `=` ends one line and `;`
  lands on the next. Verified by running the production regex over the tree: one field in the hole,
  and it is a live finding (§5.4).

`noStaticMutableState` reads **zero violations** today and is trusted; it would not catch a regression
written across two lines. `ErrorShapeGuardrailTest` already uses `readString` + `lineOf(src, offset)` —
port that idiom. **~10 lines, and it is the highest-value change in this document.**

### S0.2 — `SIG` is anchored at `^    `

`CorrelatedSubselects.scanLambda` sits at column 0 and is therefore invisible to both the method-length
rule and dead-code detection. Anchor at any indent.

### S0.3 — two documents give false guidance about the flapper ✅

`ENGINEERING_LOG.md:56` and **`AUDIT_PROGRAM.md:446`** both state that `testConcatenateClassAgg`
"flips between PASS/FAIL across sweeps; not a signal." **That was fixed by `d21548f2` on 2026-07-28**
(§7.1) and verified at 40/40 fresh-JVM runs. The guidance now trains reviewers to discard a genuine
regression in the project's only equivalence gate. Delete both lines. *(The second is this audit
program's own — it propagated the claim without checking.)*

### S0.4 — settle the ThreadLocal count

Two independent slices returned **8** and **10**. Both enumerated explicitly; the delta is scoping.
Resolve before freezing a census (§8).

---

## 4. S1 — ambient state, and the plan to retire all of it

**Ten sites, and two of them steer generated SQL.** This is the class the original taxonomy lacked.

### 4.1 — S1.1 `NullSemantics.FILTER_POS` scope hole: a silent wrong answer ✅

`Lowerer.java` closes the `enterFilter()` scope, and *then* the `Fold.FilterSlot.ISOLATE` branch calls
`predicateOrThrow(src, f.predicate(), "filter")` a **second** time — outside the gate. Confirmed by
reading the source: the two calls are at `:1200` (inside) and `:1207` (outside).

`NullSemantics.equalNullArms` emits `a = b or (a is null and b is null)` inside filter position and
bare `a = b` outside it. So for two `[0..1]` column operands:

```
Person.all()->filter(p|$p.k == $p.k)              -- one row set
Person.all()->distinct()->filter(p|$p.k == $p.k)  -- a DIFFERENT row set
```

Production path, no diagnostic, no golden diff unless a corpus test happens to combine `distinct` with
a two-optional-column equality. A second hole: `whereLambda` (`:3440-3450`) never enters filter
position and silently inherits whatever is ambient.

**Fix:** move the `Fold.filterSlot`/ISOLATE block inside the `try (var ignored = …enterFilter())`, and
open a scope in `whereLambda`. **The mechanism is the best-disciplined of the ten** — true
save/restore, reentrancy-correct, and recursion through `ScalarSubquery` is real. **The defect is the
scope boundary, not the ThreadLocal.**

### 4.2 — S1.2 `EngineTextBoundary.enter()` blind reset

```java
public static Scope enter() {
    ACTIVE.set(Boolean.TRUE);
    return () -> ACTIVE.set(Boolean.FALSE);   // blind — not a restore
}
```

An inner scope's exit switches the boundary off for the remainder of an **outer** lowering, and
`Lowerer.java:3155` starts re-emitting `CAST(... AS VARCHAR)` mid-plan. Not reachable today — one
`enter()` call site — but **the class javadoc already claims "the toSQLString/planToString funnels set
it," plural, and only one does.** The doc describes the state that makes this live.

`NullSemantics.java:120-123` is the correct implementation **in the same package**, and it explicitly
cites *"the EngineTextBoundary precedent"* while silently correcting it. The correction was never
back-ported. **One line** — or retire the flag entirely, §4.4.

### 4.3 — S1.3 the four with no clear at all

| Site | Writer | Reader | Failure |
|---|---|---|---|
| `PostProcessBoundary.TABLE_REPLACE:24` | `StatementExecutor:2041`, guarded by `args.size() >= 3` | `:381-383`, production | The javadoc claims *"Reset per execute"*; the guard means a 2-arg `execute` never clears it. On a pooled thread a later `toSQLString` renders SQL carrying the **previous request's** table renames |
| `DriverPkOption.ACTIVE:21` | `TestBody:545` (test) | `StatementExecutor:41` (**production**) | Once a `validate(...)` test latches it true, every later `Compiler.execute` on that thread gains phantom result columns or throws a bogus `NotImplementedException` |
| `RawSqlBoundary.RECORDER:37` / `META_RECORDER:60` | `Runner:1087` (**test only**) | `HostEval.replayStream()`, production — the sole source for `fetchDb*MetaData` | Without the harness, `recording()` is null and the metadata grid returns **empty with no error**. `recording()` also hands out the live `ArrayList`, which `H2Verify:108` iterates while `:90` can append → `CME` → caught → `ADVISORY_MARKER` → **scores as a pass** |

### 4.4 — Are we deleting all ten? Yes. They do not share a cost.

| Site | Cost | Route |
|---|---|---|
| `PostProcessBoundary.TABLE_REPLACE` | **free** | `ExecEnv.tableReplace` already exists, is already threaded at `:2043`, and is already read at `:2795` |
| `DriverPkOption.ACTIVE` | **free** | already an `ExecEnv` component — pass it to `Compiler.execute` |
| `RawSqlBoundary.RECORDER` / `META_RECORDER` | small | onto `ExecEnv` beside `rawSqlFailureSink`, returning `List.copyOf` |
| `HostEval.CTX` / `SPECS` / `LETS` | one change | they exist because the evaluator takes no env. An interpreter's signature is `eval(node, Env)`; `LETS` is let-binding scope, per-call by definition |
| `TestBody.UNSUPPORTED_REASON` | ~27 sites | return `(marker, reason)` instead of a bare `String`. Also fixes its misattribution bug |
| `EngineTextBoundary.ACTIVE` | small | a `Lowerer` constructor argument — `Lowerer` already carries `engineExistsJoinForm` as exactly this kind of builder flag |
| **`NullSemantics.FILTER_POS`** | **expensive** | read at `Scalars:117` **through the static rule table** — ~90 rule lambdas take bare args with no slot for it |

**FILTER_POS is expensive, and should still go — because four independent findings all want the same
missing object.** A `LowerCtx` would house: the engine-text mode (§4.2), filter position (§4.1), the
connection timezone (§7.2, a live wrong answer), and `H2_BACKEND.md` §8's per-dialect capability
budget. **Build it once for the second backend; two ThreadLocals and a wrong answer fall out.** Nobody
should build it *for* the ThreadLocals.

`rawSqlFailureSink` is the model for all of this — a `Consumer<String>` record component on `ExecEnv`.
*(Correction to `TENET_REMEDIATION.md` §1.2, which cites it as a static ledger. It is not.)*

---

## 5. S2 — mutable state

### 5.1 — the pipeline contract holds for signatures, not for objects

Two slices appear to disagree and do not. **No `void` method mutates an object a prior phase
produced** — 16 mutation-through-accessor hits repo-wide, none in a void method, and the typed IR is
copy-protected at runtime. **But 14 of 23 phase classes hold post-construction mutable state.**

`StoreResolver` holds no `Connection` — and 6 direct mutable fields plus 3 transitive. Its own javadoc
concedes the design: *"THE per-resolution temporal frame … nested sibling resolutions overwrite at
their own entry."*

**`ClassSources.java:796-807` is the proof, and its comment is evidence rather than a symptom.** The
nested `~func` resolution allocates `new StoreResolver(ctx, specs)` because re-entering the caller's
instance would hit `temporal = TemporalFrame.rootFrame(...)`, which unconditionally overwrites — **the
save/restore guard exists only in `resolveChain`, not in the public `resolve()` entry** — and would
trip the in-flight cycle check.

So the contract holds **observationally**: the driver allocates fresh at all 7 `new StoreResolver(` and
5 `new Lowerer(` sites, and no singleton or cache exists anywhere. **Reusing one across two queries
corrupts results even single-threaded.**

### 5.2 — S2.1 `PureModelContext`'s read-path caches: the concurrency blocker ✅

`classCache`, `enumCache`, `functionCache` are plain `HashMap`s **written from the read methods** —
`findClass` reads at `:106` and puts at `:112`. `Compiler.executeResolved` takes a caller-supplied
`ModelContext`, so "compile once, serve queries concurrently" — decision D2's product shape — races
these three maps. Same at `ModelBuilder.associationEndsByOwner:116`, lazily assigned inside a public
read method.

`ConcurrentHashMap` + `computeIfAbsent`. **This single change converts the thread-safety verdict.**

### 5.3 — S2.2 `ModelBuilder` falsifies its own header

The javadoc claims *"One-shot, immutable after construction… safe to share across threads… No mutation
after build."* Three violations:

- `retainLegacySurface:816` — the only public mutator; interns an FQN and appends nine lines after
  `from()` returned
- `public final Map mappingPoisons:323` — written by **8 external sites across two packages**
- `associationEndsByOwner:116` — an unsynchronized lazy cache inside the public `findAssociationEnd`

`public final Map` is the whole mechanism: `final` protects the reference, not the contents.

**The value channel already exists and is already used.** `NormalizedModel` carries `mappingPoisons` as
a `Map.copyOf` record component (`:35, :67-68`), drained at `ModelNormalizer:130,142` and re-hydrated
at `PureModelContext:89`. Only the *accumulation* uses the mutable public field. **The cleanest fix in
the audit.**

### 5.4 — S2.3 the smaller service-state leaks

- **`SyntheticHeads.setCanonicalizer:292`** — writes non-final `canon`, **never reset to identity**, so
  a later `liftFilteredHeads` canonicalizes under the last installed lambda, which closes over a
  specific `Context`. Pass it as a parameter; nothing needs it to persist. *(This is the one field
  S0.1 unblocks.)*
- **`ClassSources.setJsonSources:72`** — monotonic. A second `from()` scope with no JSON sources skips
  the guard and silently inherits the previous scope's map.
- **`Lowerer.withEngineExistsJoinForm():159`** — **mutates in place and returns `this`**, while
  `Compiler.java:282` writes `planLw = planLw.withEngineExistsJoinForm();`, which reads like a copy.
  Return a copy, or rename it and make the mutation honest.
- **`EngineStyleH2`** — 6 of 9 void methods write instance fields. `render()` clears three at entry,
  which makes sequential reuse safe and **reentrancy fatal**: a nested `render()` would `clear()` the
  alias plan mid-render. Safe today only because every instantiation site allocates fresh.

### 5.5 — S2.4 records: an honest negative, and a live inverse

**156 of 315 collection components hand back the caller's live reference — and it is not a live bug.**
Every method body was scanned for the dataflow *build list → hand to undefended record → mutate list*:
**one hit, and it is a false positive.** Latent hazard.

The discipline stops at a layer boundary, sharply:

| Layer | records w/ collection | defended |
|---|---:|---:|
| `model`, `model/spec`, `compiler/element/type`, `sql/dialect`, `plan` | 56 | **56** |
| `compiler/spec/typed` | 32 | 30 |
| `resolver` | 22 | **1** |
| `sql` | 20 | **5** |
| `exec` · `normalizer` · `lowering` · `lineage` · `testdatagen` · `harness` | 28 | **0** |

*(The "normalise but don't copy" class is empty — the compact constructors that do `null → List.of()`
per `NULL_GATE_VERIFICATION.md` also `List.copyOf`.)*

**The inverse is live: four records are deliberately used as mutable accumulators** —
`StatementExecutor.ExecEnv.queryLets()`, `SubselectPrune.Refs`, `MetamodelWalk.JtnH.children()`,
`TestDataGenerator.Fetched.temps()`. Each is call-scoped and never escapes, so it is benign by the
taxonomy — but **a record whose value changes after construction is a type-level lie.** Make them
classes.

**Two records are *partially* defended, which is the most misleading state of all:**
`TypedSerializeGraph.SubTypePatch` copies `children` but not `leaves`; `:40` copies four components but
not `checkedConstraints`. Fix these before the 79 undefended ones.

---

## 6. S3 — void methods: the smaller intervention

**Do not rewrite the 160 call-scoped walkers.** Java punishes the functional style here — a recursive
walker returning `List<X>` allocates per frame and needs tree-flatMap composition the language
expresses badly — and those files are already queued for T4.1, the collection-carrier redesign, and the
H2 dialect work.

**But the acceptance is not free, and the reason is mechanical:** `void f(node, List<X> out)` with a
fresh sink and one with a sink from three frames up are **textually identical**. Deciding between them
needs call-graph reachability from every allocation site to every write — which is what four agents
just did by hand. **The 160 benign fills are what make the 27 harmful argument mutations
undetectable.**

**S3.1 — make sink ownership visible in the type.** Either a `record Sink<X>(List<X> out)` that only
the allocating frame can construct, or a `collect*`-named / sink-last convention with a guardrail.
Turns the undetectable 27 into a mechanical check **without touching the 160**.

Also worth noting the standard is achievable: **`Ddl.java` has zero void methods** — all seven return
`String` and the caller executes the text. That is the model shape.

**S3.2 — the residue:**
- **`ScanRelations.Node`** — 8 void mutators over a shared mutable tree; `attachTdsJoin`'s `parent` is
  not an argument at all, it is looked up from a map belonging to an *earlier recursion level*. **The
  honest answer is no fix without a persistent-tree rewrite.** Confined to `lineage/`. Document it.
- **`ModelIntegrity.withElement`** — deliberate and documented (dropping instead of poisoning once cost
  182 corpus tests), but **it discards the cause chain entirely**; only `getMessage().split("\n")[0]`
  survives, so a nested `TypeInferenceException` root cause is unrecoverable.
- **`TestDataGenerator.dropTemps`** — empty `catch (SQLException ignored)` invoked from two `finally`
  blocks, so it can also mask an exception that would otherwise surface.
- **Two dead void methods** — `CorrelatedSubselects.scanLambda`, `UnionSynthesis.collectInboundRouteKeys(5-arg)`.

**Registration/init is upstream of §2's finding.** The 15 registration voids are what produce the ten
`static final` fields holding mutable containers. A factory returning `Map.ofEntries(...)` makes the
table immutable by construction rather than by convention-after-init.

---

## 7. S4 — determinism, and the harness masking production

### 7.1 — the flapper: root-caused, already fixed, doc is stale ✅

`joinStrings` over `concatenate` lowers to `STRING_AGG` fed by a `UNION ALL` with no `ORDER BY`, so
DuckDB returns either arm order. Measured on the real seed rows: **101/200 one way, 99/200 the other —
a ~50/50 coin flip per execution**, not a cross-sweep artifact, and **not thread parallelism** (it
flips at `threads=1` too). Only one outcome matches the expected string, so the test was ~50% pass **by
construction**.

`d21548f2` added `Fold.orderUnionAggregate:57-105`, which stamps a branch ordinal into the union and
orders by it. Verified live, and 40/40 fresh-JVM runs pass. **The live defect is documentation** —
see S0.3.

**Residual obligation gap, scoped honestly.** Two determinism guards exist and neither is general:
`Lowerer:1085-1095` injects `ORDER BY <alias>.rowid` only when `aliasIsBaseTable`, which returns
`false` for any `Subselect`; `Fold.orderUnionAggregate` fires only when a UNION source is found. Across
a full sweep: **317 `STRING_AGG` occurrences, 4 shapes still carry no `ORDER BY`** — but one was run
200× and is stable at corpus data scale. **Latent, not live.**

### 7.2 — S4.1 the harness pins settings production does not have

`Runner.java:986,1168` pin **`SET threads=1`** and **`SET TimeZone='UTC'`**. **Nothing in
`core/src/main` does either.**

Proven: an unordered `string_agg` over 300k rows returns `n0,n3,n6…` at one thread and
`n245760,n245763…` at eight — each stable, **different answers**. So every corpus test passing
*because of* that pin is unverified in production, and **the gate structurally cannot see this class
of bug.**

The timezone half is a *live* wrong answer already filed: `Compiler.dialectOf` returns a bare
`new DuckDb()` while the plan path threads `timeZone` through `planDialect(dbType, quote, timeZone)`.
`CORPUS_TAXONOMY.md` §6 records the cost — `testInExecutionWithTempTableForDateTimesWithTz`, 5 rows → 0.

**Fix:** pin session settings in the product connection path (or ban reliance on scan order), and
thread `timeZone` into `dialectOf` — the `LowerCtx` of §4.4 is its natural home.

### 7.3 — S4.2 three locale bugs, all proven by execution

| Site | Proof |
|---|---|
| `PureDateLiteral:151,162,174,187,201,219` + `PureTimeLiteral:54,65,77` — `String.format` with no `Locale` | under `hi-IN-u-nu-deva` a date renders `२०१४-०१-०५`; flows via `toEngineString()` → `SqlExpr.DateLit` → `AnsiSqlRenderer:298` → **emitted SQL** |
| `RelationalDataType:125`, `JoinType:30`, `Executor:481` — `switch (x.toUpperCase())` | `"int".toUpperCase()` → `İNT` (U+0130) in `tr-TR`; a DDL type `int` misses its arm, and `Executor:481` mis-branches JDBC type decoding |
| `AnsiSqlRenderer:780`, `CorrelatedSubselects:1606,1615` — `.toLowerCase()` | `"INT".toLowerCase()` → `ınt` (U+0131); misses the reserved-word set and **the identifier-quoting decision flips** |

All three: add `Locale.ROOT`. 19 sites total.

### 7.4 — S4.3 hash-order reaching output: two sites, not 140

- **`ImplicitInheritance:40,47`** — a `HashMap bySetId` iterated into `byClass` lists; list order
  decides which mapping `nearestMappedAncestor` returns, hence which property mappings merge into the
  child, hence emitted SQL. Requires ≥2 relational mappings for one class on one main table. Stable
  run-to-run (String keys), fragile to renames. → `LinkedHashMap`.
- **`NameResolver:238-243`** over `Pure.nativeClassFqns()` — decides which FQN wins a colliding prelude
  simple name. **The code documents this** (*"kept one ARBITRARILY"*) and mitigates via
  `PRELUDE_COLLISIONS`. Diagnostic-adjacent; shifts if a native class is added.

Everything else was checked and cleared — see §9.

### 7.5 — S4.4 corpus input ordering and the hardcoded path

The dedup **is** deterministic (`Compiler.parseSources` walks the list in order, `putIfAbsent` gives
first-wins). **The list is not.** `RelationalCorpusRunner` sorts every walk except two — `:115-121`
`Files.walk(...).forEach(...)` and `:132-134` `Files.list(p).toList()` — and both feed `putIfAbsent`
chains including `fnIndex:227`, which decides **which body a helper call expands to**. Stable on APFS
while the tree is untouched; silently changes when the checkout is modified. **Add `.sorted()`.**

> **RESOLVED 2026-08-05** — both halves, in three commits.
>
> **Engine root** (`9f9c0240`): `Corpus.ENGINE_ROOT` now defaults under `user.home`, matching
> `parser-equivalence`'s `Corpus.engineRoot()`.
>
> **Ordering** (`42277dfa`, `<this commit>`): `.sorted()` on the two `addBeforePackages` feeds named
> below was necessary but **not** sufficient — three green sweeps still gave three checksums. The
> actual culprit was `NameResolver.resolveKeyExpressionMap` returning **`Map.copyOf(out)`**, which
> discards the `LinkedHashMap` order built two lines above it. `Map.copyOf`'s iteration order is
> randomized per JVM run by `java.util.ImmutableCollections.SALT` (seeded from `nanoTime`), and
> `^Class(...)` validation reports the *first* failing property — so an ill-typed instantiation's
> wall text changed between runs. Proven with a standalone probe on the exact `^TableTDS(...)`
> property names (`store,table,columns` → a different order on nearly every JVM start), then fixed
> by returning `Collections.unmodifiableMap(out)`.
>
> **Verified:** three consecutive full sweeps now produce a byte-identical scoreboard
> (`md5 0e6b1773…`), counts unchanged at 2567 / 2253 / 104 / 97 / 113. Two rows changed once and
> stayed: they now report their *true* first failure, which the randomization had been masking.
> See `CORPUS_STUDY_2026_08.md` § 0a.

**And `Corpus.java:32` defaults `legend.engine.root` to `/Users/neema/legend/legend-engine` — another
user account's home directory** — with no pom, script, or doc setting the property. Both checkouts
exist on this machine and **differ**: 540 vs 541 `.pure` files, ~20 with differing content, files
unique to each side. **3,807 committed baseline lines encode that path.** Anyone running the sweep on a
normal checkout gets thousands of spurious deltas before evaluating a single behavioural change. Make
it mandatory and fail fast; store corpus-relative paths in the baseline.

---

## 8. Gates

**Write the invariant as "ambient state may never influence output," not "zero ThreadLocals."** A count
freeze at 8 invites the reading that one more is within budget. Freeze the count as a cheap interim
ratchet; the checkable rule is the one that lasts.

| # | Rule | Violations today | Cost |
|---|---|---:|---|
| **G0** | **Join brace-depth-1 statements before matching field patterns** (S0.1) | 1 — `SyntheticHeads.canon` | ~10 lines. Fixes a rule that reads complete and is not |
| **G1** | `final`-but-mutable instance fields — pin per-file counts, shrink-only | **57** | ~15 lines. Largest ungated population |
| **G2** | Extend `noStaticMutableState` to `static final` **mutable containers** | 12 | ~15 lines. Its own message says "no allowlist for this one" |
| **G3** | ThreadLocal census, shrink-only | freeze at today's count | ~8 lines. Gates the entire side-channel class |
| **G4** | **No `void` on a phase class assigns `this.<field>`** | **0** — the property already holds | ~12 lines. A ratchet on a true invariant is the cheapest kind |
| **G5** | Ban unlocalized `toUpperCase`/`toLowerCase`/`String.format` | 19 | Fixes §7.3 permanently |
| **G6** | Emitted SQL: no `STRING_AGG(`/`LIST_AGG(` without an inner `ORDER BY` | 4 shapes | **Would have caught the flapper without knowing about unions.** Costs 1 corpus FAIL, recoverable by gating the `rowid` guard on `EngineTextBoundary.active()` |
| **G7** | Require `.sorted()` on `Files.walk`/`Files.list` in `rcorpus` | 2 of 14 | The convention exists; it just is not enforced |
| **G8** | ArchUnit: `ModelBuilder` exposes no public non-final field, no public `void` | 3 | Land with the §5.3 fix |

**Not mechanizable: argument mutation (27 of 84).** Textually identical to the 160 benign fills;
needs call-graph reachability. That is the argument for spending agent time there rather than on more
gates — and the argument for S3.1, which makes it mechanical.

---

## 9. Checked and cleared — do not re-audit

`core/src/main/java` has **zero** clock reads, filesystem or classpath access,
`Locale`/`Charset`/`TimeZone.getDefault`, `Random`/`UUID`, and parallelism (the one
`ConcurrentHashMap.newKeySet` at `FunctionCompiler:90` is a memo). **Zero** statement or `ResultSet`
leaks — all 3 connection sites and every `createStatement`/`executeQuery` are try-with-resources. All
10 static lookup tables are static-init-only and safely published.

**All 21 `IdentityHashMap`/identity-set sites are lookup-only** — verified none calls
`.values()/.keySet()/.entrySet()/.forEach/.stream`; the two that escape are consumed only via `.get(n)`.
**Identity hash does not reach output.**

Of 140 bare `HashMap`/`HashSet` declarations, **8 are iterated and 2 are observable** (§7.4). The 8
inline `Map.of(...).entrySet()` registration loops are keyed by distinct function names — order cannot
change the result.

**The scoreboard is deterministic** — two back-to-back sweeps and the committed file share one md5.
That question is closed.

---

## 10. Sequencing

**S0 first** (~½ day) — the guardrail blindness, the `SIG` anchor, the two stale doc lines, the
ThreadLocal count. Every estimate below is computed from data these distort, and S0.1 is what makes
G1–G3 possible at all.

1. **S1.1** — the `FILTER_POS` scope hole. A verified silent wrong answer.
2. **S4.2** — the 19 locale sites, plus **G5**. Mechanical, and two of the three reach emitted SQL.
3. **S2.1** — `PureModelContext` → `ConcurrentHashMap`. One change converts the thread-safety verdict.
4. **S1.2** + the three free ThreadLocal deletions (`PostProcessBoundary`, `DriverPkOption`,
   `RawSqlBoundary`) — each has an explicit twin already in `ExecEnv`.
5. **S2.2** — `ModelBuilder` through the existing value channel, then land **G8**.
6. **S2.3** — `SyntheticHeads`, `ClassSources`, `withEngineExistsJoinForm`; the 2 partially-defended
   records; the 4 record-accumulators.
7. **S4.4** — `.sorted()` and mandatory `legend.engine.root`. Cheap, and it makes the gate portable.
8. **G6** — the emitted-SQL assertion. The outcome check the structural guards only approximate.
9. **S3.1** — sink ownership in the type; then the 27 become mechanical.
10. **S4.1** — decide the session-settings question. It is a product decision, not a code fix, and
    until it is made the gate cannot see a whole class of bug.
11. **`LowerCtx`** — build it for the second backend (`H2_BACKEND.md` §8). `FILTER_POS`,
    `EngineTextBoundary`, and the timezone bug fall out.

Roughly **two weeks**, with S0–S2 about a third of it and carrying most of the value.
