# Ambient state — the surviving twelve, and the sweep

**Verdict: the plan's §0 claim "Execution-option thread-locals in main | ZERO" is false, and
the plan contradicts itself twelve lines later.** §1a (`:65`) opens *"Modes that change what
the compiler emits (4 fields) — FIRST, verdict-relevant"* and names `FILTER_POS`,
`VERBATIM_EQ`, `EngineTextBoundary.ACTIVE`, `TextGoldens.ACTIVE`. Both sentences cannot be
true. The §0 row should read: **2 remaining (`VERBATIM_EQ`, `EngineTextBoundary.ACTIVE`);
4 deleted (batches 118, 120, 121, 122)**.

Twelve `ThreadLocal` fields survive across nine files, plus one non-final static and ~40
`static final` counters/maps mutated at runtime and read by tests across a module boundary.

---

## 1. The inventory

| # | Field | file:line | Carries | **Written by** | Read by | Prod behaviour? | Save/restore? | `remove()`? | Sev |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `StampCensus.CONTEXT` | `lowering/StampCensus:48` | PCT test FQN | **TEST ONLY** — `pct/…/ChannelB:191` | prod `:60,101,108` (message text) | No — the fire/throw decision never consults it | blind-set | never | LOW |
| 2 | `NullSemantics.FILTER_POS` | `lowering/NullSemantics:100` | "lowering a filter predicate" | prod `Lowerer:1470,1926` | **NOBODY** | **No — provably write-only** | save/restore | never | MED (a documented lie) |
| 3 | `NullSemantics.VERBATIM_EQ` | `lowering/NullSemantics:114` | "this equality is a resolver-synthesized join condition" | prod `Lowerer:1472,1932` | prod `:141` | **YES — `NULL_SAFE_EQUAL` vs `EQUAL`**, i.e. which rows a join matches | save/restore | never | **HIGH** |
| 4 | `EngineTextBoundary.ACTIVE` | `lowering/EngineTextBoundary:21` | engine-TEXT lowering mode | prod `StatementExecutor:619` wrapping `lw.lower(body)` at `:621` | prod `CastPolicy:50` | **YES — deletes a cast from the MIR** | **blind-set** (`:34-37`) | never | **HIGH** |
| 5 | `TextGoldens.ACTIVE` | `sql/dialect/TextGoldens:17` | engine-text rendering mode | prod `StatementExecutor:620,640` | prod `EngineStyleH2:1031` | **YES** — changes rendered text | **blind-set** (`:30-33`) | never | MED-HIGH |
| 6 | `SqlTypeCensus.CONTEXT` | `exec/SqlTypeCensus:89` (`public`) | PCT test FQN | **TEST ONLY** — `ChannelB:192` | prod `:157,826` (witness text) | No | blind-set | never | LOW |
| 7 | `SqlTypeCensus.WIRE_WATCH` | `exec/SqlTypeCensus:396` | per-statement wire-nullability watch | prod `watch():433` ← `Executor:246` | prod, census only | No | n/a | **YES** — `:474` in `Executor:252` `finally`. The only disciplined lifecycle | LOW-MED |
| 8 | `ExecutionTrace.LAST` | `exec/ExecutionTrace:19` | `-- "executionTraceID"` comment | prod `:26` ← `Executor:238` | prod `StatementExecutor:1484,1532` | **YES — user-visible activity row**, and it is "last on this thread", not "of this execution" | blind-set | never | MED |
| 9 | `TestResources.RESOLVER` | `exec/TestResources:18` | classpath-path → text resolver | **TEST ONLY** — `rcorpus/MinimalCorpus:443` | **PROD** — `CsvLoad:43`, throws at `:35` when unset | **YES — whether `loadCsvToDbTable` works at all** | blind-set | `register(null)` → `remove()`; no caller passes null | **CRITICAL** |
| 10 | `RelationReads.DERIVED_DEPTH` | `normalizer/RelationReads:175` | derived-property inline recursion depth | prod, itself `:136` | prod, itself `:130` (`< 16`) | **YES** — at 16 it stops inlining and throws at `:148` | inc/dec in `finally` | never | MED |
| 11 | `RawSqlBoundary.RECORDER` | `sql/dialect/RawSqlBoundary:46` | executed raw statements | installed by **TEST** `MinimalCorpus:442`; appended by **PROD** `StatementExecutor:1948,2563,2593,2643-4`; truncated by **TEST** `ReplayOracle:239` | **TEST** `ReplayOracle:198,859,897,923,933` | No (append-only) | n/a | `record(null)`; no caller | MED |
| 12 | `RawSqlBoundary.META_RECORDER` | `:115` | metadata-only DDL | same as #11 | **TEST** `metaRecording():125` — **zero call sites** | No | n/a | `:52` | MED |

### Mutable statics

**Exactly one non-final static field in all of `core/src/main`:**

```java
// exec/CanonicalDivergence.java:434-435
public static volatile java.util.function.Supplier<String>
        CONTEXT_SOURCE = () -> "<unattributed>";
```

Written by **nobody, anywhere in the repo**; read at `:270, 329, 451, 479`. A dead injection
seam — its javadoc says *"the HARNESS wires its per-test context holder here"*; no harness
does, so every divergence witness is stamped `<unattributed>`. It slips both guards because
the declaration spans two lines and the regex is per-line.

| Group | file:line | Writers | Readers | Finding | Sev |
|---|---|---|---|---|---|
| `SqlTypeCensus` — 26 `LongAdder` + 6 maps | `exec/SqlTypeCensus:44-202` | prod `probe()`, `settleWire()` | **TESTS ACROSS MODULES**: `pct/PctCensusGate:183-201`, `ChannelBStandardTest:139-151`, `ChannelBUnclassifiedTest:122-134` | process-global accumulators a verdict reads — exactly what §6f item 3 forbids | **HIGH** |
| `SqlTypeCensus.PROBE_SUSPENDED` (`AtomicBoolean`) | `:660-661` | **PRODUCTION** — `SqlTextVerdicts:1256`, restored `:1267` | prod `:672,678` | **not thread-local at all** — a process-global boolean production code flips mid-execution. Its javadoc admits it: *"Toggled by the **single-threaded** harness"* | **HIGH** |
| `CanonicalDivergence` counters/samples | `:35-43,156-160,237-245,387-428,561` | prod verdict path | `AssertVerdictsTest:129-144`, `LiteralChannelTest:63-102`, `InstanceIdentityTest:156-258`, `ChannelB:200-205` | same violation; **worse** — `reset()` is called by `CanonicalFormTest:119,138` and `V7DualChannelCensusTest:21,64,70,80` while `AssertVerdictsTest:129` takes an absolute snapshot → **test-order-coupled in one JVM** | **HIGH** |
| `CanonicalDivergence.MUTED`, `R1_SUSPENDED` | `:245`, `:238` | `muteAll()`/`r1Suspend()` **called from nowhere** | 14 read sites | dead global switches, permanently false, costing a volatile read per census event | LOW |
| `RawSqlBoundary.XLATE_NANOS` (`public AtomicLong`) | `:179-180` | `:198` | **never read** | dead; costs two `System.nanoTime()` per raw statement on a path the file says runs "per seeded statement, per test, across 2,019 corpus tests" | LOW |
| `SqlTyping.PAD_READ_FLIPPED` (`public LongAdder`) | `:41-42` | `SqlExpr:382` | **never read** | dead | LOW |
| `FunctionCompiler.SUPPRESSED_ONCE` | `:134` | prod `:74,107` | itself | gates only a stderr line; makes stderr order-dependent across a JVM | LOW |
| `SystemDatabase.IDS` | `:43` → `:112` | prod | prod | legitimate id minter — stays | — |
| ~20 write-once `<clinit>` tables | `Lexer.KEYWORDS`, `Pure.ALL*`, `Scalars.RULES`, … | `<clinit>` | prod | not runtime state | — |

**The guard cannot see any of it.** `ArchitectureTest.staticCollectionStateIsImmutableOrRegistered`
inspects only fields whose *declared type* is `Map` or `Collection` (`:936-943`). Every
`LongAdder`, `AtomicLong`, `AtomicBoolean`, `ThreadLocal`, and the non-final `Supplier` is
invisible. The plan's §386 description — "static-sink registry (every static accumulator
named)" — is false: the registry names the *collections* only. All 26 `SqlTypeCensus`
LongAdders the PCT gate pins on are unregistered.

---

## 2. Classification and remediation shape

| Class | Fields | Remediation |
|---|---|---|
| **Execution option** (changes emitted SQL) | `VERBATIM_EQ`, `EngineTextBoundary.ACTIVE` | a **parameter**, not a flag |
| **Rendering mode** | `TextGoldens.ACTIVE` | a constructor argument on the renderer — `new EngineStyleH2(TextMode.GOLDEN)`; `:1031` reads `this.textMode` |
| **Recursion depth** | `RelationReads.DERIVED_DEPTH` | a method argument — `rewrite` already threads 7 params; add `int derivedDepth` |
| **Census counter** | both `CONTEXT`s, `WIRE_WATCH`, all adders/maps, `CONTEXT_SOURCE` | the per-run fact ledger (§4). `CONTEXT_SOURCE`, `MUTED`, `R1_SUSPENDED`, `XLATE_NANOS`, `PAD_READ_FLIPPED` are **deleted outright** |
| **Test-resource SPI** | `TestResources.RESOLVER`, both `RawSqlBoundary` recorders | the SPI stays but **moves onto the request** — fields on `ExecuteOptions` |
| **Debug trace / identity** | `ExecutionTrace.LAST` | not a trace — an *identity of one execution*; returned with the result |
| **Write-only / dead** | `NullSemantics.FILTER_POS` | **delete** |

---

## 3. Two verified sub-findings

**`FILTER_POS` is provably write-only, and two documents claim otherwise.** Every occurrence
in the repo: `NullSemantics:100` (declaration), `:109` (`boolean prev = FILTER_POS.get()` —
the save), `:110` (`set(TRUE)`), `:111` (`set(prev)`), `:139` (a comment). No decision read.
Written from two production sites at real cost — `Lowerer:1470-1473` opens a two-resource
try-with-resources around the whole filter body — for nothing.

- `NullSemantics:138-140` claims *"The FILTER_POS scope still gates OTHER arms."* There are none.
- `END_TO_END_PLAN:341` (Appendix A, headed "measured 2026-09-08") lists *"Read by
  `NullSemantics` (the null-arm choice of comparisons)"*. False. That row was not measured,
  which undercuts the header on the whole table.

**The `Lowerer`'s builder methods are *mutating* withers,** so the plan's §1a suggestion
("the Lowerer already takes builder options") must not be applied to `VERBATIM_EQ`:

```java
// Lowerer.java:183-186
public Lowerer withDbTimeZone(@Nullable String zone) { this.dbTimeZone = zone; return this; }
```

A `withVerbatimEquality()` in this style would be *exactly as ambient* as the ThreadLocal,
merely instance-scoped, and additionally **sticky** (no restore on scope exit) where the
ThreadLocal at least restores `prev`. `VERBATIM_EQ` must become a parameter.
`EngineTextBoundary`, being a whole-lowering constant, is safe as a constructor argument.

**`Lowerer` instance state that never clears:** `aliasCounter` (`:104`), `deferredTds`
(`:112-114`), `tdsCounter` (`:121`), `enclosing` (`:175`), `dbTimeZone` (`:180`),
**`letBindings` (`:188` — 4 put sites, 3 read sites, zero `clear()`)**,
`engineExistsJoinForm` (`:195`), `streamingGraphRoot` (`:204`), `instanceIdOf` (`:214`),
`instanceKeysOf` (`:223`), `whereZones` (`:1516`). A second top-level `lower()` on the same
instance would see the first's state. **It does not fire today** — every main-scope
construction site allocates a fresh `Lowerer` immediately before its single `lower()`
(`StatementExecutor:611-621, 2010-2024`; `Compiler:430-439, 934`; `SeedableLets:38-40`). The
contract "one `Lowerer` = one top-level lowering" is real, currently honoured, and **entirely
unenforced** — no assertion, no guard, and the class is `public` with a public no-arg ctor.

---

## 4. The per-run fact ledger, designed

Goal: `PctCensusGate` and `ChannelB*Test` stop reading `SqlTypeCensus.mismatchCount()` and
friends, and no static in `main` accumulates a fact a verdict reads.

**Shape** — a mutable collector handed *in* with the request, not a value returned out. This
avoids touching the sealed `ExecutionResult` hierarchy and mirrors the shape
`RawSqlBoundary.record(sink)` already has, minus the static slot.

```java
// core/src/main/java/com/legend/exec/FactLedger.java
public final class FactLedger {
    public record Fact(Kind kind, String cls, String witness, String context) {}
    public enum Kind { LABEL_LIE, WIRE_DIVERGE, WIRE_ADOPT_PENDING, WIRE_UNKNOWN,
                       WIRE_INT_OR_NULL_EMPTY, NULL_BREACH, UNTYPED, BOTTOM_MULT,
                       STAMP_LIE, VERDICT_DISAGREE, VERDICT_DECLINE, SLACK_ROW }

    private final ConcurrentLinkedQueue<Fact> facts = new ConcurrentLinkedQueue<>();
    private final EnumMap<Kind, LongAdder> counts = ...;   // instance, not static
    private volatile String context = "<unattributed>";     // replaces both CONTEXTs

    public void context(String testFqn);
    public void add(Kind k, String cls, String witness);    // stamps context
    public long count(Kind k);
    public List<Fact> facts(Kind k);
    public String summary();                                // replaces SqlTypeCensus.summary()
    public void merge(FactLedger other);                    // suite roll-up
    public static final FactLedger DISCARD = ...;           // no-op sink; the default
}
```

**Who writes it.** Exactly the production sites that today call
`SqlTypeCensus.classify/sample/increment` and `CanonicalDivergence.record/v7Verdict/v7Declined`
— they take the ledger as a parameter. `Executor.probeWire/settleWire` (`:246,252`) already
have `plan`, `dialect` and the connection in scope; `StampCensus.check(spec, e)`
(`Lowerer:2387`) takes a third argument; `SqlTypeCensus.probe(plan)` (`Executor:83`) a second.

**Where it lives.** On `ExecuteOptions` — the record that already exists for exactly this,
and whose javadoc states the doctrine (`ExecuteOptions.java:5-9`): *"The execute OPTIONS a
caller passes with one execution — they ride the request and the result, never a static slot
(the PctRenderOption thread-local died here, batch 122)."*

```java
public record ExecuteOptions(
        boolean pctRender,
        FactLedger facts,                                  // was: the two censuses
        @Nullable RawSqlLedger rawSql,                     // was: RECORDER/META_RECORDER
        @Nullable Function<String, String> resources) {    // was: TestResources.RESOLVER
    public static final ExecuteOptions NONE =
            new ExecuteOptions(false, FactLedger.DISCARD, null, null);
}
```

The threading is already built: `ExecEnv.withOptions(...)` exists at `StatementExecutor:122`,
and `ExecuteOptions` already reaches `Compiler.execute` (`:745,884`), `StatementExecutor`
(`:66,104`), `QueryService` (`:67`) and `PctExecuteNative:157`. What must be *extended* is
downward into `Executor` and `Lowerer`.

**How a test asserts without a static accumulator:**

```java
private final FactLedger suite = new FactLedger();      // TEST scope — allowed by §6f

void runOne(FunctionDefinition fd) {
    FactLedger run = new FactLedger();
    run.context(fd.qualifiedName());                     // replaces ChannelB:191-192
    Compiler.execute(model, query, …, ExecuteOptions.NONE.withFacts(run));
    suite.merge(run);
}

@AfterAll void ceilings() {
    assertEquals(0, suite.count(Kind.LABEL_LIE), suite.summary());
    assertTrue(suite.count(Kind.WIRE_DIVERGE) <= MAX_WIRE_DIVERGE, suite.summary());
}
```

The accumulator still exists — a ceiling is a sum — but it lives in the test that owns the
ceiling, which is the whole point of "*in main*". `ChannelB:200-205`'s before/after
`sqlDisagreeCount()` diffing becomes `run.count(VERDICT_DISAGREE)` on that run's own ledger:
strictly more precise, and it stops being wrong if anything else executes in between.

**Parallel-safe by construction**, not by hope. Each execution and each test gets its own
ledger; only the suite roll-up is shared and `merge` over `LongAdder`/`ConcurrentLinkedQueue`
is safe. **Nesting is fixed for free**: an inner execution receiving `ExecuteOptions.NONE`
writes to `DISCARD` and cannot pollute the outer run — which is what `PROBE_SUSPENDED`
simulates with a global flag, so `probeSuspend`/`probeSuspended` and the
`SqlTextVerdicts:1253-1267` save/restore dance **delete along with it**.

**Not a file** (needs a path convention, cleanup, and would not survive a crash) and **not a
returned value** on `ExecutionResult` (sealed; ~8 arms would grow an unused field; the
aggregate is per-*suite*, not per-result).

---

## 5. Parallelism and reentrancy

**Surefire is NOT parallel today.** Root `pom.xml:95-115` sets only
`<argLine>-Duser.timezone=GMT</argLine>`; `core/pom.xml:96-100` only `<excludedGroups>`;
`pct/pom.xml:229-235` only `<useSystemClassLoader>`. No `<parallel>`, no `<threadCount>`, no
`forkCount` override, and **no `junit-platform.properties` anywhere**. Tests are sequential in
one reused JVM per module. Nothing thread-confined is corrupted right now.

| Scenario | What breaks | Evidence |
|---|---|---|
| One thread, two queries in sequence | `ExecutionTrace.LAST` is stale-but-readable. `StatementExecutor:1484` guards with `lqRun == null ? null : lastComment()` — "did *a* run happen", not "did *this* run stamp" — so an execution whose SQL was served without a `stamp()` inherits the prior statement's trace id into its activity row | `ExecutionTrace:19,26,33`; `StatementExecutor:1484,1532` |
| A second `lower()` on one `Lowerer` | `letBindings`/`whereZones`/`deferredTds` leak; a stale `letBindings` entry silently substitutes the wrong `SqlExpr` at `:2554` | `Lowerer:188, 240-260, 2552-2554, 1516` |
| Nested `engineSql` / nested lowering | the text flags **blind-restore to FALSE**: the inner close clears the outer's mode and the rest of the outer lowering emits execution-shaped casts into an engine-text plan, no error. Latent (one enter site each) but `engineSql` has 10+ production callers | `EngineTextBoundary:36`, `TextGoldens:32`; `StatementExecutor:619-620,640` |
| Nested `execute()` during row reading | `settleWire()` does a blind `WIRE_WATCH.remove()` (`:474`); an inner statement settling inside an outer's `runShape` loop drops the outer's watch, whose `finally` then finds `null` and silently loses its wire census — **under-counting a pinned ceiling reads as healthy**. Nested execution provably occurs (`SqlTextVerdicts:1250-1268`); whether inside an open `ResultSet` loop is **unverified** | `SqlTypeCensus:469-474`; `Executor:246-252` |
| Long-lived LSP/HTTP session | `TestResources.RESOLVER` and `RawSqlBoundary.RECORDER` are never `remove()`d by any caller, so a server thread that once served a corpus-style request retains the resolver and an unbounded recorder buffer **forever**; `ExecutionTrace.LAST` retains a UUID per pooled thread indefinitely | `TestResources:22-29`; `RawSqlBoundary:49-57` |
| `-T`/parallel surefire | immediately unsafe — **not** because of the ThreadLocals but because of the process-global flags. `PROBE_SUSPENDED` is flipped by production at `SqlTextVerdicts:1256`; with two threads in that region, A's restore un-suspends B's probe mid-flight. Likewise `CanonicalDivergence.reset()` would zero counters `AssertVerdictsTest:129-144` snapshots | `SqlTypeCensus:657-673`; `CanonicalDivergence:237-255` |

Nothing is broken today because the build is single-threaded and each field happens to have
one writer. Every one of those is a coincidence of the current call graph, not an invariant,
and none is asserted.

---

## 6. The sweep plan

Ordered by risk-removed ÷ effort. Batches 1–4 are near-free and remove two real hazards.

1. **Delete `NullSemantics.FILTER_POS`.** Field (`:100`), `enterFilter()` (`:108-112`), the
   misleading comment (`:138-140`); `Lowerer:1470-1473`'s two-resource try collapses to one,
   `:1926-1928`'s disappears. Minutes. **Also correct** Appendix A row 1 and `END_TO_END_PLAN:42`.
2. **Delete the dead statics** — `CONTEXT_SOURCE` (`:434`), `MUTED`+`muteAll`,
   `R1_SUSPENDED`+`r1Suspend`, `XLATE_NANOS` (also deletes two `System.nanoTime()` from the
   per-statement seed path), `PAD_READ_FLIPPED` (deletes the increment at `SqlExpr:382`).
   Verify against `JavaEvalLedgerTest:888-940` and `HarnessDisciplineTest:133,149`, whose
   numbers will need restating.
3. **`RelationReads.DERIVED_DEPTH` → a parameter.** Add `int derivedDepth` to `rewrite(...)`
   (`:59-64`), default 0 from the two entry overloads, `+1` at `:138`, test at `:130`, delete
   the field and the inc/dec. Self-contained; `rewrite` is package-private.
4. **`TestResources.RESOLVER` → `ExecuteOptions.resources`.** *The CRITICAL one.* One
   registrant (`MinimalCorpus:443`), one reader (`CsvLoad:43`). Blocker: confirm `CsvLoad.load`
   has `ExecEnv`/options in scope, or thread it one level. ~1h.
5. **`ExecutionTrace.LAST` → returned with the execution.** `stamp(sql)` returns the stamped
   SQL *and* the comment; `Executor.executePrepared` (`:236-238`) carries it out;
   `StatementExecutor:1484,1532` reads it from the frame. Pass it through `ExecFrame`,
   constructed right there. **This also fixes the stale-trace-id bug** — a correctness fix.
6. **`TextGoldens.ACTIVE` → a renderer constructor argument.** The text surface at
   `StatementExecutor:640` constructs the renderer, so it can construct it in golden mode.
   First delete the `:620` entry (around `lw.lower`, which renders nothing) as its own step
   and confirm the roster is unchanged — that isolates the question.
7. **`EngineTextBoundary.ACTIVE` → a `Lowerer` constructor argument.**
   `CastPolicy.lower(TypedCast, SqlExpr, TextMode)` gains the parameter from its caller in
   `Scalars`. Medium; needs the roster-diff protocol. **Not a mutating wither** (§3).
8. **`VERBATIM_EQ` → a parameter of the equality lowering.** The information is already at the
   call sites: `f.stamp() == CORRELATION` (`Lowerer:1471`) and `j.userCondition()` (`:1926`).
   Thread an `EqualityPolicy` through `tryPredicate`/`sideCondition` → `Scalars:156` →
   `NullSemantics.equalNullArms(n, ops, policy)`. **Deepest thread in the sweep** — it
   traverses `Scalars`' rule table — so it goes last despite being the highest-severity
   field. Guard with both lane rosters exact.
9. **The fact ledger** (§4). Splits into (9a) introduce and dual-write alongside the statics,
   proving the numbers identical; (9b) move `PctCensusGate`/`ChannelB*Test`/`AssertVerdictsTest`/
   `LiteralChannelTest`/`InstanceIdentityTest`; (9c) delete the statics, `probeSuspend`, and the
   `SqlTextVerdicts:1253-1267` dance. **Blocker: bigger than Appendix A implies** — three
   *core* tests also read `CanonicalDivergence`, not just the PCT pins.
10. **Both `RawSqlBoundary` recorders → `ExecuteOptions.rawSql`.** `Raw`, `LedgerMark`,
    `mark`/`truncateTo` move onto a `RawSqlLedger` instance. `MinimalCorpus:287` already keeps
    a `seedLedger` field. Touches the referee, so its own roster check.

**Not in the plan, and needed for the standard to be checkable:**

11. **Widen `ArchitectureTest.staticCollectionStateIsImmutableOrRegistered`:**

```java
boolean container = Map.class.isAssignableFrom(f.getType())
        || Collection.class.isAssignableFrom(f.getType());
boolean mutableCell = ThreadLocal.class.isAssignableFrom(f.getType())
        || AtomicLong.class.isAssignableFrom(f.getType())
        || AtomicInteger.class.isAssignableFrom(f.getType())
        || AtomicBoolean.class.isAssignableFrom(f.getType())
        || AtomicReference.class.isAssignableFrom(f.getType())
        || LongAdder.class.isAssignableFrom(f.getType())
        || StringBuilder.class.isAssignableFrom(f.getType())
        || f.getType().isArray();
if (!Modifier.isStatic(f.getModifiers()) || !(container || mutableCell)) { continue; }
if (mutableCell) {                       // a mutable CELL can never be "known immutable"
    if (!register.contains(id)) { violations.add(id + " is static mutable CELL state"); }
    continue;
}
// ... existing isKnownImmutable() path for containers
```

Plus check `!Modifier.isFinal(...)` as its own violation class — that is how `CONTEXT_SOURCE`
passes. Expect ~51 new register rows. Also fix `catch (Throwable t) { continue; }` at `:932`,
which silently drops any class that fails to load, uncounted.

12. **Enforce `Lowerer` single-use** — a `private boolean lowered` with a loud wall on a
    second top-level call, or move `letBindings`/`whereZones`/`deferredTds` into a per-call
    `LoweringState`.

---

## 7. What was done right

The four deletions are the correct fix, and the code states the pattern better than the plan:

- `ExecuteOptions.java:5-9` — *"they ride the request and the result, never a static slot."*
- `ExecutionContext.java:18-24` — *"Bound ONCE by the special form's rule … read everywhere
  else as fields — no consumer walks a runtime expression for a shape."*

That is the whole cure: one binding point, value semantics, field reads at every consumer.
Copy it literally for `resources`, `rawSql`, `facts`.

Two things to preserve rather than rewrite:

- **`WIRE_WATCH` is the only ambient field with a correct lifecycle** — acquired lazily at
  `:433`, released at `:474`, release in a `finally` at the acquiring layer. Keep that
  acquire/settle/release rhythm when it becomes a ledger field; it is the right shape on the
  wrong storage.
- **`NullSemantics.enterFilter`/`enterVerbatimEquality` save-and-restore `prev`**
  (`:109-111`, `:122-124`) — exactly what `EngineTextBoundary:36` and `TextGoldens:32` get
  wrong. The fix for the text flags is not new thinking; it is applying care that already
  exists 40 lines away.

And the precedent for closing `FILTER_POS` is already in the tree: `Lowerer:169-175` records
deleting `relationDepth` because *"the slice-1-3 audit found it WRITE-ONLY — no reader ever
landed … dead state carried by a stale justification."*

---

## 8. What DONE means for ambient state

Each item is a command or a named test.

1. `grep -rn "ThreadLocal" core/src/main/java` → **zero matches** (not zero fields — zero
   matches; the comments at `LambdaBinding:24` and `Lowerer:1467,3000` describe a mechanism
   that will no longer exist).
2. `grep -rnP 'static\s+(?!final\b)' core/src/main` finds **no field declarations**.
   Today: one (`CanonicalDivergence:434`).
3. `staticCollectionStateIsImmutableOrRegistered` inspects `ThreadLocal`, all
   `java.util.concurrent.atomic` types, `LongAdder`, and every non-final static — and its
   register contains **no entry justified as "measurement only"**.
4. No test in any module references `SqlTypeCensus` or `CanonicalDivergence` by name.
   Today: `PctCensusGate:183-201`, `ChannelBStandardTest:139-151`,
   `ChannelBUnclassifiedTest:122-134`, `AssertVerdictsTest:129-144`, `LiteralChannelTest:63-102`,
   `InstanceIdentityTest:156-258`.
5. Every ceiling asserts on a `FactLedger` the test allocated. **No assertion anywhere takes a
   "before" snapshot of a global and diffs it.**
6. `probeSuspend`/`probeSuspended` and the `SqlTextVerdicts:1253-1267` save/restore no longer
   exist — nesting is handled by passing a discard sink.
7. `EngineStyleH2` renders golden text because it was **constructed** in golden mode;
   `CastPolicy.lower` elides a wire cast because it was **passed** a text mode.
   `grep -n "active()" core/src/main` returns nothing.
8. `NullSemantics.equalNullArms` takes an explicit equality policy; both
   `try (var ignored = NullSemantics.enter…)` blocks are gone.
9. `RelationReads.rewrite` carries `int derivedDepth`; no recursion guard in `main` uses
   thread storage.
10. **The behavioural acceptance test:** adding `<parallel>classes</parallel>` and
    `<threadCount>4</threadCount>` to surefire produces the **same roster on both lanes**. The
    other nine items are structural; this one proves the property. It need not stay enabled;
    it must pass once.
11. A single JVM can lower two queries on one `Lowerer` and get the same SQL as two fresh
    ones, **or the second call walls loudly**. Either is fine; silently reusing `letBindings`
    is not.
12. `END_TO_END_PLAN:42` states a number matching item 1, and Appendix A's "Read by" column
    has been re-measured.
