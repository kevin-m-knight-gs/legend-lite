# The One-Platform Plan

*2026-08-18. The master plan for finishing the foundations: one implementation of Pure
semantics — ours — with the database doing the work, Java doing the orchestration, and
every helper that grew up beside the platform either absorbed into it or reduced to a
stopwatch and a clipboard.*

---

## The summary, in five sentences

We are building a Legend platform whose only two materials are **Java and SQL**: user
queries compile to SQL and the database executes them; platform functions are Java code
that lowers to SQL wherever the work is data-shaped. Along the way we grew a test harness
that quietly became a **third implementation** of Pure semantics (the product compiler is
the first, legend-pure's reference interpreter is the second) — it evaluates asserts,
equality, result navigation, and envelope rules in its own private Java, and every audit
we have run found drift there, because two implementations of one semantics always drift.
The plan is to **migrate the harness's semantics onto the platform, piece by piece**, with
the full 2,575-test corpus green at every step, until the harness only starts tests and
records outcomes. At the end, PCT conformance runs through **two channels** — the
reference implementation (the oracle) and our own fully decoupled platform — and the diff
between their reports *is* the wire-bug census. legend-pure remains our **spec** for what
behavior must be, and is **never** our architecture for how to build it.

---

## The two tenets

**Tenet #1 — Java orchestrates, the database executes.**
Any value derived from stored data reaches its consumer through SQL. Java never computes
an answer out of rows; it moves compiled queries in and finished results out.
*(Standing since the eviction program; enforced by the ledger, the funnel rules, and the
census guards.)*

**Tenet #2 — The platform is Java + SQL; no platform code is ever written in Pure.**
*(Ratified 2026-08-18.)* legend-pure's own architecture is a small native core plus a
mountain of platform functionality written in `.pure` files and interpreted — their
`assertSameElements` is Pure calling Pure. We learn the *behavior* from those files and
implement it as **Java code**, DB-lowered where the work is data-shaped, host-native where
it is not. Pure source in our runtime is **user model text only**. Declared *signatures*
as Pure text are fine (they are the surface, not behavior); Pure *bodies* are banned.

Where they meet — **Charter Clause 2b**: a Pure semantic that makes no sense in SQL
(an unordered check's message formatting, a metamodel operation, an execution-time field)
may be a native Java platform function, under three conditions: one owner on the compiled
surface (never a harness-private copy), the engine/legend-pure source as its verified
spec, and a ledger registration naming it a platform native.

**How clean are we today?** Verified 2026-08-18: `core/src/main` contains **zero** `.pure`
files. The platform surface is 699 native signatures in `Pure.java`; every body is Java.
The corpus runner already suppresses user redefinitions of platform-owned functions
("native is the definition"). Tenet #2 is *true of the tree* — this plan writes it down
and guards it.

---

## The insight this plan is built on

The corpus tests are Pure source: `let result = execute(...)`,
`assertSize($result.values, 1)`, `$result.values->at(0)->map(r|$r.name)`. Today the
**query** inside `execute` runs through our platform — but the **rest of the sentence**
is evaluated by the harness's own Java: its own `let` handling, its own chain navigation,
its own fourteen assert implementations, its own equality (`wireEquals`), its own
temporal decoding, its own `Result.values` arity rule.

Every one of those is a Pure platform construct with an owner elsewhere. The cost is not
hypothetical — it is our own history:

- The **one-carrier timestamp fix** (2026-08-18) had to be applied in *five places*
  (`Executor.fetch`, `unwrap`'s leaf, `carrierList`, `coerceTemporal`, `norm`) because the
  harness owns parallel copies of value decoding. The referee caught an 11-test transient
  from exactly this.
- Multiple audit rounds found **comparator drift**: cross-kind numeric collapses, order
  policies, tolerance leaks — all in the harness's private equality.
- The adversarial audit's **harness-compensation census** (B1–B7) is the same disease
  catalogued.

One implementation of a semantics cannot drift from itself. That is the whole plan.

---

## The destination, as a picture

```
                     ┌──────────────────────────────────────────┐
   user model text → │  THE PLATFORM (Java + SQL, one owner)    │ → answers
   (the ONLY Pure    │  parser → compiler → SQL → database      │
    in our runtime)  │  + platform natives (asserts, metamodel) │
                     └──────────────────────────────────────────┘
                            ▲                        ▲
              ┌─────────────┘                        └──────────────┐
   CORPUS HARNESS (stopwatch + clipboard):              PCT, TWO CHANNELS:
   parses test bodies, hands them to the                A: reference interpreter
   platform, records pass/fail, keeps the                  (the oracle, THEIR code)
   scoreboard and the oracle replay.                    B: our platform, decoupled
   Evaluates NOTHING itself.                            diff(A,B) = wire-bug census
```

---

## The phases

Every phase obeys the same two rules: **the full corpus (2,575) and PCT (1,110) are green
at every landing**, and **migrated harness code is deleted, never kept "just in case"** —
its ledger rows die with it.

### Phase 1 — Typed `executeInDb` results (was "Batch B")

**What, plainly:** Today, when a test navigates a raw-SQL result —
`executeInDb(...).rows->at(0).value('NAME')` — a pattern-recognizer called GridReads
matches known chain shapes at the execution seam and hand-builds SQL strings for them.
It works, but it is a closed vocabulary, a second SQL producer, and the audit's "sixth
JDBC class."

**The fix, grounded in the spec:** the engine's own metamodel (verified in
`platform_store_relational/functions.pure`) says `executeInDb` returns a **class** —
`ResultSet { columnNames: String[*], rows: Row[*], ... }`, where `Row.value(name)` is
*itself defined as* `at(values, indexOf(columnNames, name))`. So we model `ResultSet` and
`Row` as platform classes in the typer, back them with the one executed statement, and
let **the ordinary compiler** lower the navigation — the same machinery that already
handles far harder expressions. The chains stop being special.

- The host-fact fields (`executionTimeInNanoSecond`, connection info) are Clause-2b
  natives — Java facts, never SQL.
- Schema discovery for arbitrary SQL keeps the existing LIMIT-0 probe, moved from the
  exec seam into compilation, where it belongs.

**What dies:** `GridReads` (~465 lines), `HostEval` (the last routing shim),
`DbMetaData.HostResultSet` (the carrier that slid past the bytecode guard), five of the
shadow-SQL register's sites. Ledger rows deleted, not bumped.

**Done when:** every corpus test that passes today still passes; the four files are gone;
the JDBC census and SQL-text registers shrink accordingly. Size: **M** (a few days —
real typer design, bounded surface).

### Phase 2 — Asserts become platform natives

**What, plainly:** the harness dispatches fourteen assert forms in its own Java. The
census says where the weight is (engine relational corpus, occurrences):

| assert | uses | | assert | uses |
|---|---:|---|---|---:|
| `assertEquals` | 3,278 | | `assertEq` | 51 |
| `assertSameElements` | 763 | | `assertSqlEquals` | 46 |
| `assertSize` | 759 | | `assertEmpty` | 36 |
| `assertSameSQL` | 504 | | `assertNotEmpty` | 34 |
| `assert` | 341 | | `assertFalse` | 31 |
| `assertJsonStringsEqual` | 192 | | `assertEqWithinTolerance` | 22 |

**The fix:** implement the assert family as **Java platform natives** (tenet #2 — we read
legend-pure's 19 small assert files as the *spec*, e.g. `assertSameElements(e, a)` ≡
`assertEquals(sort(e), sort(a))`, and write Java). Pushdown follows the data:

- **Data-shaped** compares lower to SQL: two relations compare by symmetric `EXCEPT`
  (we already do exactly this in `assertTestData` — it is the house pattern);
  `assertSize` on a relation is a `COUNT`.
- **Host-shaped** work stays Java under Clause 2b: comparing two already-produced
  scalars, message formatting via the platform's `toRepresentation` (one owner — the
  testdatagen port generalizes), the multiset rule.
- **Equality has ONE owner.** The platform's equality replaces `wireEquals`. The
  adjudicated policies (integral exactness, the 2-ULP dialect-arithmetic leniency, the
  TDSNull sentinel) move with it — *policies survive, the private copy dies*.

**Order:** `assertEquals` + `assertSize` + `assertSameElements` first (≈83% of all
uses), then the tail. Each native lands → the corresponding harness arm is deleted →
full referee run. `assertSameSQL`/`assertSqlEquals` (text-of-SQL asserts) migrate last —
they compare compiler *output*, not data, and stay host-native.

**Done when:** the harness contains zero assert implementations; `wireEquals` is deleted;
the eval ledger's harness rows shrink to orchestration. Size: **L** (the biggest phase —
but perfectly incremental, one assert at a time).

*Phase 2 LANDED (2026-08-19):* **`wireEquals` is DELETED** (no shim survives — all 11
call sites repoint). The comparison layer has ONE production owner pair:
`exec/PureAsserts` (spec core read from `essential/tests/*.pure` — `equal()`,
`toRepresentation` [the testdatagen port generalized; `pureRepr` delegates],
`assertEquals`/`assertSameElements`/`assertSize`/`assertEqWithinTolerance` with the
spec's EXACT failure messages, pinned by the spec's own test cases incl.
`sort([1,3,'2'])` number-before-string; PLUS the adjudicated wire policies clearly
layered: TDSNull sentinel, 2-ULP, temporal Any-carrier bridge — each direction-aware)
and `exec/GridCompare` (2b: header pins, row-cohesion tuple multiset, rendered-text
CSV/TDS policy, `assertTdsEquivalent` cell tolerance, the `LL_ORD_COUNT`/`LL_TOL_COUNT`
instruments riding their policies). Two latent bugs found by the spec pins and fixed:
NaN through `BigDecimal` threw in the old `wireEquals`; `java.sql.Date` epoch used the
default TZ where the `LocalDate` arm used UTC (mixed-kind wobble — now consistent).
Charter C2.4 caught and killed a `default->` numeric bucket in the sort rank. NO new
pipeline natives: real pure defines the family as FUNCTIONS over `assert`+`equal`
(both already native) — corpus/PCT sources compile them; registering natives would
have FOUGHT the spec. What remains harness-side, by adjudication: routing + Eval
plumbing (Phase 5's kill list), the ORDER POLICY (retires via the divergence census,
not migration), assertSameSQL/assertSqlEquals (compiler-output text — the plan's own
"stays host-native"), and the JSON canon cluster (ledger-adjudicated exception).
`assertEq`'s `eq()`-vs-`equal()` conflation in the harness noted (pre-existing;
primitives only in corpus — Phase 4's diff will surface any real divergence). Perf
gate: +2.7% referee wall-clock (limit 10%). Suite 4134/0; referee byte-identical;
PCT channel A green.

*PHASE 2 DEEP AUDIT (2026-08-19, user-directed; the post-landing audit the phase
lacked):* verdict SOUND-with-findings, all remediated in one batch. Verified: deletions
real (zero wireEquals code refs), one owner with delegation, two-layer separation,
spec-verbatim messages, all five deferrals recorded, perf gate honored. Findings fixed:
**P2-1** tolerance now EXACT arithmetic for exact kinds (the double round-trip silently
widened it); **P2-2** temporals sort BY INSTANT (contract said instant, code said text);
**P2-3** no-op replace deleted; **P2-4/P2-6** ONE structural tree walker
(`exec/JsonCompare`: wire-value trees with `equalScalar` leaves, parsed-document trees
with strict content leaves) — the harness's private `jsonDeepEquals` DIED (its claimed
ledger exception had never been registered), and the latent nested-list-in-struct gap
(raw Java equals, no numeric normalization) is fixed and pinned; **P2-5** `assertEq` now
means `eq` — primitives compare, non-primitives REFUSE LOUDLY (identity is unobservable
on a value wire; the conflation risked silent structural true). **P2-7** ratified as
Charter Clause 2c — TWO WORLDS, ONE SPEC: verdicts are the host layer's job, in-query
computation is the compiler's, neither reimplements the other; the
`EqualityWorldsConformanceTest` fixture pins both worlds' verdicts INCLUDING the two
declared divergences (SQL numeric coercion 1==1.0, SQL NULL vs pure true). Follow-up
noted, not yet done: a census guard over the 15 `@Disabled` GAP rows + 1
assumption-gated skip (they predate this arc, 2026-08-16, each reason named — but no
guard keeps the set tamper-evident).

### Phase 3 — The Result envelope becomes a platform model

**What, plainly:** `execute(...)` returns the engine's `Result` envelope; tests read
`$result.values`. Today the harness hardcodes the envelope's arity in a Java branch
(`tds ? 1 : size`) — correct (we pinned it with tests), but the fact belongs in a model.

**The fix:** `Result` becomes a platform-modeled class like `ResultSet` in Phase 1;
`.values` navigation is compiled; the arity falls out of the model instead of a branch.
The K pin retires into it. Size: **S** (rides Phase 1's machinery).

*Phase 3 LANDED (2026-08-19):* the B2a/B2b substrate was already in (Result<T>/Activity/
execute natives, platform-owned; the eager RESULT FRAME with its typed splice), and the
grid half (3a) landed with Phase 1c's endgame. The remaining K pin — the harness's
`tds ? 1 : size` arity branch — RETIRED into the model:
`ExecutionResult.envelopeCarriers` owns the rule (a relation-rooted result is ONE
TabularDataSet carrier; class/scalar roots ARE the collection), and the harness's
`envelopeSizeCheck` keeps only recognition + eval (Phase 5's kill list). Honest
residuals recorded: `Result<T>` collapses real pure's `Result<T|m>` per the
single-type-param convention (same as `project<K>`); the DEMAND-DRIVEN STAMPING
follow-up is DECIDED AGAINST for now — the LIMIT-0 probe is planning-only and uniform,
and resolver demand-analysis would trade real complexity for microseconds (revisit only
if the perf histogram ever names it). Referee byte-identical; suite 4134/0.

*PHASE-3 FINDINGS BATCH (2026-08-19, the systematic-audit loop — REVERSES the
demand-driven-stamping deferral above, measured this time):* three parked findings
landed as one gated batch. **P3-1**: `RawGridSchema`'s static at-pick DELETED — a
resolver-side duplicate of the lowering's collection-at rule (one owner; referee
identical in isolation; ledger 248→237). **P3-2 SINGLE-QUERY**: the LIMIT-0 probe now
runs ONLY when a `columnNames`/`values` read statically demands the schema
(`RawGridSchema`'s demand gate); an undemanded grid executes as ONE query — the
lowering emits the zero-output star-select, `Executor.resolveColumns`' late-bound arm
(gated on `schema.isLateBound()`, the TYPE, never an outputs-emptiness proxy) adopts
the result-set headers as trusted columns, the wire KIND drives cell decode
(`sqlTypeOf` threads the flag), and `Fold`'s claim rule grants a late-bound grid frame
the trust-name claim (pivot's claim-any precedent; empty outputs anywhere ELSE keep
the loud construction wall — the full suite caught exactly that seam). PINNED BY
OBSERVATION: `ExecuteInDbProbeCountTest` counts real JDBC traffic through a proxy —
0 probes for bare `.rows`/named reads, exactly 1 under `columnNames` demand (ledger
237→261: demand ANALYSIS, not evaluation — it deletes a query from the common path).
**P3-5**: `SkipCensusTest` — every `@Disabled` must name its gap (`GAP: ...`, pinned
15 shrink-only with the stale-row rule) and the `Assumptions.assume*` FILE SET is
pinned exactly (2) — the suite can no longer go quiet silently. Suite 4161/0, referee
byte-identical, ALLGATES GREEN.

### Phase 4 — The decoupled PCT channel

**What, plainly:** PCT is the conformance suite — 1,110 tests defined in legend-pure.
Today they run one way: the *reference interpreter* executes the test, calling our
platform through an adapter (`ExecuteLegendLiteQuery`) that marshals values into the
reference's object world. That channel is the **oracle** and it stays. But it means our
platform has never run PCT *by itself*.

**The fix:** a second runner — **channel B** — where *our* parser compiles the same
`PCT.test` functions, *our* platform natives run the asserts (Phase 2 makes this
possible), and no reference object ever appears. Feasibility is already measured: the
parity harness parses the reference sources today (1,856 of 2,110 files byte-clean).

**The payoff is the diff.** Channel A and channel B should agree *modulo real wire
bugs* — so every disagreement is a finding with a name. The adapter's compensations stop
hiding things: `remapErrorMessage` (which rewrites our error text to match reference
expectations) retires because channel B compares errors raw and differences become
visible rows in the diff, adjudicated one by one.

**Also in this phase — the adapter gets honest.** `ExecuteLegendLiteQuery` (~850 code
lines) splits into: legitimate channel-A marshalling (≈40%, the reference-interpreter
plumbing that channel B never touches), and residue that either moves to the platform or
surfaces in the diff — the regex model-scanner (`extractClassMetadata`, a shadow parser
by another name), the error remapper, the embedded classifiers. Size: **M–L**, phased
suite by suite (Essential's 327 first).

*Phase 4 CHANNEL B FIRST MILESTONE (2026-08-19):* the runner EXISTS and the Essential
census is pinned. `pct/channelb/ChannelB`: our parser compiles the PCT sources at
LEGEND_PLATFORM (iterative model-wall collection over the platform tree; parsed native
declarations drop — the registry is the definition), discovers `<<PCT.test>>` functions,
β-reduces the IDENTITY adapter (`$f->eval(|expr)` → `expr` — our platform IS the
executor; non-identity shapes DECLINE with their spelling), and executes each body in a
fresh DuckDB session — asserts lower to `CASE WHEN … error(msg)` and run IN THE
DATABASE. **Essential: 327 discovered (exactly channel A's count) — 189 PASS / 107
FAIL / 2 DECLINED / 29 ERROR**, pinned (discovery exact, PASS grows-only). Three
platform fixes fell out and referee-gated clean: the asserts package joined the
implicit-import list (spec fact: PCT sources call `assertFalse` bare); the
message-LAMBDA assert overload unwraps to its body in SQL (CASE ELSE is already lazy —
`error()` was receiving a DuckDB lambda); `toRepresentation` became a platform native
(the pure body is m3-reflective and unportable; `lowering/Repr` is the SQL owner,
`PureAsserts.repr` the host owner, the `%r` directive rides the same emission with the
dead-branch VARCHAR cast). Burn list for the next batches: 92× DuckDB list-lambda
binding (`put`/Map territory), 12× assertError (needs the error-catching assert), 6×
eval-with-args adapter shapes, primitive-extension declines, then the THREE-BUCKET DIFF
against channel A's expected-failure ledger and the adapter split.

*THE THREE-BUCKET DIFF IS LIVE (2026-08-19):* after the inlined-body constant folds
(189→248 PASS; the provenance rule engine-verified both directions), the diff against
channel A's own ledger reads **AGREE-PASS=247, AGREE-FAIL=24, WIRE-BUG=53, B-FIXES-A=1,
DECLINED=2** — 271/327 corroborated by two fully independent channels, the 53 wire-bug
rows are the named census the phase exists to produce (24 real assert divergences +
list-lambda/cast/temporal-range gaps), and one B-FIXES-A finding (our platform passes
where the reference adapter could not). Pinned: AGREE-PASS grows-only, WIRE-BUG
shrinks-only. Remaining phase work: the wire-bug burn (feeds Phase 6's census), the
remaining suites (Grammar/Relation/Standard/Unclassified ride the same runner), and the
adapter split.

*BURN SLICE 1 — assertError (2026-08-19):* the platform native landed (the /2 and /4
message forms; the matcher native is m3-reflective, platform-owned doctrine) — the
K-orchestrator runs f's body IN the database, catches the database error, and
adjudicates with assertError.pure:24-26's exact spellings. It brought the SOURCE-INFO
channel with it: TypedNativeCall now carries the parser's name-token span (semantic
equality excludes it — two referee regressions pinned that the day it landed), guards
embed it behind U+001E (Scalars.withSrc), and the catcher verifies line/column against
real coordinates. The date ctor's day guard became month-aware
(DateFunctions.validateDay: 'Invalid day: 2016-12-32'). All 11 assertError rows burned:
census **PASS=259**, diff **AGREE-PASS=252 AGREE-FAIL=18 WIRE-BUG=48 B-FIXES-A=7** —
six of the eleven are tests the reference adapter CANNOT pass (its re-spelled sources
break the source-info expectations); channel B, compiling the real .pure files, passes
them. The adapter's remapErrorMessage strips the span channel (its coordinates describe
adapter text, not the file).

*BURN SLICE 2 — map wire-shape + rigid-lattice conformance (2026-08-19):* two general
rules burned 21 more rows. (1) ListEncodings.map: WIRE SHAPE FOLLOWS THE TYPE — a
to-one/[0..1] map source wraps as its singleton list (list_transform over a bare scalar
is a DuckDB Binder error even in a dead CASE arm), a to-one/[0..1] RESULT unwraps back
to the scalar wire, [0..1] null-guards both ways, and an already-list-shaped source
(one-element collection literal) passes through — the whole 12-row list-lambda Binder
family died. (2) InferenceKernel: a RIGID type-variable binding whose declared type is
an abstract value head (Number, Date) accepts actuals up the m3 lattice and KEEPS the
declared type — eval({a:Number[1]|...}, 1.0) is the spec (the 6-row testNumberExp/Log/
Pow family). Census **PASS=274**, diff **AGREE-PASS=264 AGREE-FAIL=15 WIRE-BUG=36
B-FIXES-A=10**. Extractions at the guardrails: ListEncodings (map/slice encodings out
of Lowerer), ConnectionFlags (runtime-argument readers out of StatementExecutor),
DateCtorRule (out of Scalars). Remaining wire-bug anatomy: 26 real assert-value
divergences (adjudicate per row), ~5 eval/match inference edges, 11 conversion edges
(big-number dates, fold-empty casts, match [0..1]), 2 resolution, 2 declines.

*BURN SLICE 3 — collection equality (2026-08-19):* pure equality is COLLECTION
equality — [x] IS x. The one-element-collection-vs-scalar reconciliation landed in its
chartered owner, CastPolicy.comparisonWireOperand (a match-arm [1] against the expected
1), narrow to a literal scalar other side (a non-literal to-one may itself ride the
list wire — take(1) — and list = list stays untouched). A first attempt as a
NormalizeFolds [x]→x rewrite regressed 4 rows (collection ops keep the LIST wire;
folding the literal broke list = list) and was reverted — the wire-convention
unification remains a future design leg. Census **PASS=277**, diff **AGREE-PASS=267
AGREE-FAIL=15 WIRE-BUG=33 B-FIXES-A=10**.

*BURN SLICE 4 — the FRONTIER ORACLE + empty equality (2026-08-19):* the census gained
its THIRD channel: the engine's OWN relational-DuckDB PCT manifest (pinned snapshot,
legend-engine 943d38b3/2026-08-06) names the tests the reference RELATIONAL executor
itself cannot pass — indexOf/substring 0-vs-1-base, partial-precision dates,
mixed-type Any. A wire-bug row the engine also excludes is the RELATIONAL FRONTIER,
corroborated — not ours to fix toward interpreted semantics. The split:
**ENGINE-FRONTIER=21, TRUE-WIRE-BUG=3** (testValues/testKeys Map-get,
testComplexPow decimal scale) — pinned shrink-only. And the equal rule became TOTAL
over empties: a statically empty side ([] literal, or Nil-typed [0..0]) makes it the
other side's emptiness test — [] == [] is TRUE, never SQL NULL — scoped to VALUE
kinds (Primitive/Enum/Nil/Any); the [0..0]-multiplicity criterion alone and the
unscoped arm each regressed gate-4 mapping families (subtype navigations the checker
types [0..0] still read real columns) and were narrowed, gate-verified. Census
**PASS=286**, diff **AGREE-PASS=276 AGREE-FAIL=15 WIRE-BUG=24 B-FIXES-A=10**. The
detail channel flattens multi-line assert messages (first() clipped them at the
first newline — the census could not speak).

*BURN SLICE 5 — TRUE WIRE BUGS = ZERO (2026-08-19):* the last three burned. (1) The
equality DUAL wrap (CastPolicy.equalityWireOperand, EQUAL-rule exclusive — the IN rule
shares the base seam for its needle where wrapping breaks membership): a to-one scalar
literal against a many-typed VALUE side compares as its singleton list; property
navigations keep the bare compare. (2) MAP_KEYS/MAP_VALUES joined ListShapes'
LIST_PRODUCERS (values()->sort() had wrapped a nested list). (3) A PROMOTED decimal
literal (no D suffix) beyond the DECIMAL(38) carrier ROUNDS to the carrier's edge —
5e-39 error, ~10^21x tighter than the double it was promoted from; explicit D-suffixed
decimals keep the loud reject. Census **PASS=290/327**, diff **AGREE-PASS=280
AGREE-FAIL=15 WIRE-BUG=20 B-FIXES-A=10 DECLINED=2**, frontier **ENGINE-FRONTIER=20,
TRUE-WIRE-BUG=0 — pinned at zero**: every remaining essential failure is corroborated
by a reference channel (channel A's interpreted ledger, or the engine's own
relational-DuckDB manifest). The essential-suite burn is COMPLETE; remaining Phase-4
work: the remaining suites (Grammar/Relation/Standard/Unclassified) and the adapter
split.

*CLAUSE-2c REDESIGN — the burn slices re-judged and the seam arms DELETED
(2026-08-19, docs/PHASE4_REDESIGN_PROPOSAL.md EXECUTED):* the Phase-2 deep audit
found the five slices had taught the SQL world verdict tricks (Clause 2c's named
violation); the homework retired "position decides" — legend-engine has NO SQL
translation for ANY assert, so **asserts are verdicts, always**. The replacement is
the K-arm: `AssertVerdicts` intercepts statement-root assert-family calls PRE-INLINE
in `StatementExecutor` (or the spec bodies would compile to SQL), executes the
ARGUMENTS in the database (`evalValue`), and adjudicates in World 1 (`PureAsserts`);
`map(f|assert(pred, msg))` is the QUANTIFIED verdict — the predicate vectorizes in
SQL, the boolean vector is judged host-side. DELETED wholesale: the Scalars SQL
assert rule, the U+001E sentinel span channel end-to-end (assertError line/col now
refuses loudly — position is unobservable from database errors), and the equality
seam arms (`equalityWireOperand` dual-wrap, static-empty, `[1]==1` unwrap —
CastPolicy/Scalars reverted to their pre-Phase-4 shape). World-1 completeness the
honest crossing exposed (all spec-witnessed): array-cell flattening, OffsetDateTime
repr, integral×Decimal equality is NUMERIC (testIntToDecimal), the temporal
string-carrier bridge is SYMMETRIC (the parse is the typing-bug catch, not the
direction; PureAsserts ledger 250→264 justified). Census **PASS=290/327**, diff
**AGREE-PASS=286 AGREE-FAIL=21 WIRE-BUG=14 B-FIXES-A=4 DECLINED=2**, frontier
**ENGINE-FRONTIER=14, TRUE-WIRE-BUG=0** — better than the seam-arm era
(280/15/20/10) on every honest axis; the 6 line/col rows reclassified
B-FIXES-A→AGREE-FAIL (source-info vanity, not conformance). Referee byte-identical,
core 4156 green, ALLGATES GREEN.

*GRAMMAR SUITE LIVE (2026-08-19, the second channel-B scope):* the same runner over
`platform/pure/grammar/functions` (137 tests, discovery pinned exact), diffed by the
extracted per-suite `ChannelBDiff` (channel-A ledger + engine frontier manifest).
Fresh measurement under the redesign found TWO true wire bugs; both burned as
engine-source-grounded platform fixes: (1) the **empty-equality ladder** — the
engine's own `nullSafeEqualsOperation` (pureToSQLQuery.pure) verbatim: `[] == []`
is statically TRUE, a one-side-empty literal is `isNull(other)` — scoped to LITERAL
empty collections (the engine's [0..0] criterion is honest post-routing; ours types
some subtype navigations [0..0] that read real columns, the pinned gate-4 lesson);
witness testEqualEmpty. (2) the **mixed-numeric aggregate unwrap** — collection
sum/product now apply the chartered `numList` variant-carrier unwrap (sum(JSON) was
a Binder error); witness testPlusNumber. A third row fell out with the burns:
census **PASS=123/137**, diff **AGREE-PASS=120 AGREE-FAIL=7 WIRE-BUG=5 B-FIXES-A=3
DECLINED=2**, frontier **ENGINE-FRONTIER=5, TRUE-WIRE-BUG=0 — pinned at zero like
essential**. Bonus catch: the grammar discovery pin (== 137) caught G6 running
channel B against the STALE ~/legend/legend-pure checkout — `allgates.sh` now
threads `-Dlegend.pure.root` into G6/G7. Essential re-verified untouched
(290/286/14/0); referee byte-identical; ALLGATES GREEN. Remaining suites:
Standard/Relation/Unclassified.

*STANDARD SUITE LIVE (2026-08-19, the first MULTI-ROOT scope):* the standard
functions live in legend-ENGINE (`core_functions_standard`, importing the
legend-pure platform tree) — `ChannelB.run` grew a multi-root form (source names
prefixed by root index; scopes resolve against their containing root). Discovery
pinned exact at 204 (the tree carries 205 `PCT.test` spellings, one commented out
in the engine's own source). All FIVE true wire bugs burned as GENERAL platform
fixes: (1) the **PCT.function suppression rule** — the engine's own stereotype
marks "the platform function under conformance"; where the registry owns a native
at that FQN the NATIVE is the definition and the reference body never joins the
overload set (fired 51x; witness testOr, whose inlined fold body produced broken
SQL); a PCT.function with NO native keeps its body — the model IS the
implementation. (2) **assertError catches deferred-body guards** — a lowering-time
ModelException (timeBucket's unit validation) IS the eval-time error for the
deferred lambda (witnesses testTimeBucketSeconds/Minutes/Hours); other exception
kinds stay loud. (3) the **assertInstanceOf platform native** (the assertError
pattern — the spec body needs elementToPath, unportable m3 reflection) + K-arm
dispatch + the World-1 adjudicator judging the RUNTIME carrier kind up the m3
value lattice (witness testHashCode). En route, two register catches: the
error-shape guard flagged endsWith("::PCT") → exact profile FQNs; the bare-name
resolution path bypassed the platform-owned gate → gated. Census **PASS=133/204**,
diff **AGREE-PASS=133 AGREE-FAIL=0 WIRE-BUG=7 DECLINED=64** (the 64 = window/
non-identity adapter shapes, the named future adapter arm), frontier
**ENGINE-FRONTIER=7, TRUE-WIRE-BUG=0 — all three live suites pinned at zero**.
Essential/grammar unchanged (one essential row's failure MODE improved: compile
error → adjudicated verdict). Golden catalog +2 rows; PureAsserts 264→298 and
AssertVerdicts 221→246 justified. Referee byte-identical; ALLGATES GREEN.
Remaining scopes: Relation, Unclassified.

*ALL FIVE SUITES LIVE (2026-08-19 — channel B covers the whole PCT surface):*
**Unclassified is PERFECT out of the box** (95/95, zero declines, zero wire bugs,
TRUE=0 pinned). **Relation** rides three additions: the LET-INDIRECTION adapter arm
(`let e = {|expr}; $f->eval($e)` — the same identity application with the lambda
named; 283 declines became measurable — declines hide, measurements name), the
**assertTdsEquivalent GRID VERDICT** (Clause 2c's chartered GridCompare route,
finally witnessed by 79 rows: native signatures verbatim from tdsEquivalent.pure,
the K-arm executes BOTH relations in the database and cell-zips host-side with the
spec's numeric-delta + temporal-seconds policies), and **Variant.toString as its
compact JSON text** (the carrier's own VARCHAR form; 27 rows). Census: Relation
**PASS=203/287** (TRUE≤33 shrink-only; 51 deeper non-identity declines now carry
their SHAPE), Standard re-measured **PASS=180/204** (TRUE≤16 shrink-only — the
un-declined window tail is the RECORDED burn queue: window frame semantics, pivot
column orders, chunk, temporal-precision equality). Totals across channel B:
**891 of 1,050 discovered tests pass through our platform alone**, every failure
oracle-corroborated or on a pinned shrink-only queue. The discovery pins caught
BOTH stale-root axes in G6 (grammar 136≠137 pure-root, relation 280≠287
engine-root) — `allgates.sh` threads `$R1` AND `$R2` into G6/G7. Post-landing
audit fixes folded in: the PCT.function suppression rule has a BEHAVIOR pin
(`PctFunctionSuppressionTest` — native-owned suppresses, unowned keeps its body),
the K-arm's new arms have core spec pins, decline messages carry the offending
spelling. AssertVerdicts 246→300 justified (the grid-verdict arm). Referee
byte-identical; core 4166; ALLGATES GREEN. Remaining Phase-4 work: the burn
queue (49 TRUE rows) and the adapter split.

*BURN QUEUE SLICE 1 (2026-08-19):* three general fixes. (1) The EXTREME family's
identity arm (min/max/least/greatest, 6 sites) now carries the minus rule's
singleton-list-literal guard — the reduction of [x] is x, never [x] (witness
testLeast/Greatest_Single, where the whole DuckDB array came back as the value).
(2) The K-arm's side-flatten normalizes raw JDBC array elements to the java.time
ONE-CARRIER convention — the INVISIBLE-DIFF bug: a java.sql.Timestamp reprs
identically to the LocalDateTime it never equals, so expected and actual printed
the same text while failing. (3) `chunk` is a platform native (signature verbatim
from core_functions_unclassified chunk.pure; the SQL owner is the regexp sweep
'.{1,n}' with the pattern composed IN SQL so computed sizes work), and
REGEXP_EXTRACT_ALL joins ListShapes' LIST_PRODUCERS (chunk()->sort() wrapped a
nested list without it — the map-reader precedent). Census: Relation
**PASS=208/287 (TRUE 33→28)**, Standard **PASS=184/204 (WIRE 24→20)**; pins
tightened to the new floors. AssertVerdicts 300→304 justified (carrier decode).
Referee byte-identical; core 4166; ALLGATES GREEN. Queue remainder: window frame
semantics, pivot column orders, columns(), temporal-precision rows,
INTEGER[]→DOUBLE casts, the 51 shaped declines.

*BURN QUEUE SLICE 2 (2026-08-19):* the singleton-list-literal bug was a CLASS, not
an instance — the systematic sweep of all 29 `isToOne` identity arms found five
more reduction rules without the guard (sum, mean/average, mode, median, and
`list`, which RE-wrapped an already-list-shaped value). All now carry the ArrayLit
guard with the class rule documented at the family head (witness
testAverage_Integers: `average([1])` emitted `CAST([1] AS DOUBLE)`). Standard
**PASS=187/204, WIRE 20→17, ENGINE-FRONTIER 4→1**. Referee byte-identical;
core 4166; ALLGATES GREEN.

*BURN QUEUE SLICE 3 (2026-08-19):* the "window semantics" bucket was a SPELLING
bug — a Number/Float cell on a FRACTION-KIND wire (the DECIMAL an aggregate
collapses to) printed the DECIMAL cast's fixed scale (81.180 where pure prints
81.18). One slot-directed arm in `tdsCell` (the pure Float spelling via DOUBLE;
integer slots and the VARCHAR mixed-identity carrier keep their own forms)
unmasked TWELVE correct window tests. Standard **PASS=199/204, WIRE 17→5,
TRUE 16→4** — the whole remaining standard tail is four named rows: date
min/max extend, dayOfWeek, columns() ×2. Referee byte-identical; core 4166;
ALLGATES GREEN.

*BURN QUEUE SLICE 4 (2026-08-19):* CANONICAL Variant text — the engine prints
compact JSON with leaf quoting preserved ({"a":1}, '"hello"'), never the source's
whitespace. The spelling is `to_json` over the JSON-cast value; the first attempt
('$'-extract) stripped leaf-string quotes and the census caught the regression
(208→204) before it could land — the pins adjudicated the canonicalizer choice.
Relation **PASS=212/287, TRUE 28→24**. Channel-B totals after four slices:
**919/1,050**, TRUE census 49→28, every row named. Referee byte-identical;
core 4166; ALLGATES GREEN.

*BURN QUEUE SLICE 5 (2026-08-19, user-prompted architecture check):* the user asked
whether the formatting fixes were adapter-compensation (the ExecuteLegendLiteQuery
disease) or platform. Adjudication: they are W2 — toString's #TDS text is spec'd by
engine toString.pure and COMPOSED BY THE DATABASE; every product caller gets it. But
the question caught a real forming smell: tdsCell and pctCell were DRIFTING TWINS
with duplicated per-type spelling arms (the drift is exactly why tdsCell lacked the
DateTime arm pctCell carried). Fixed structurally: ONE print-form owner
(dateTimeText/strictDateText/dateOnly helpers); each renderer keeps only its
channel-specific quoting. The restored DateTime arm burned the date min/max extend,
dayOfWeek, and asOf-temporal rows in one stroke. Standard **PASS=202/204, TRUE 4→1
(only the columns() reflection pair remains)**, Relation **PASS=214/287, TRUE 22**.
Totals: **924/1,050**, TRUE census 49→23. Referee byte-identical; core 4166;
ALLGATES GREEN.

*BURN QUEUE SLICE 6 (2026-08-19):* **deferred relation-toString** — a dynamic
pivot's '#TDS' column list only exists at the execution boundary, so the
composition DEFERS: a types-free `SqlExpr.DeferredTdsString` IR node (sealed-
interface exhaustiveness extended all six walkers; a renderer reaching it walls),
the Lowerer's side registry carries the typed schema, and post-staticize the
LOWERING-owned pass rebuilds the composition from the LIMIT-0 probe (the
dynamic-pivot two-phase discipline's second consumer). THREE architecture guards
shaped the design in-flight: the 250-line shape guard split executeTyped
(`lowerAndPrepare`); **invariant 6d rejected the first cut** (an exec-layer
resolver calling Render) — the final design has the lowering own the pass with
the orchestrator bridging via probe+decode FUNCTIONS (the PctTdsWrap pattern);
the pivot-column matching rule moved to its one owner (`Type.RelationType`,
nullable — each caller owns its fallback, and the suite pins caught the
too-strict wall on downstream-rebuilt schemas before it landed). Relation
**PASS=217/287, TRUE 22→19**. StatementExecutor 2695→2724 justified (probe
plumbing, not evaluation). Referee byte-identical; core 4166; ALLGATES GREEN.

*BURN QUEUE SLICE 7 (2026-08-19 — the three-front slice):* (1) the window-frame
guard moved TYPE→LOWERING (the timeBucket precedent: both reference channels
observe the invalid-boundary error LAZILY at eval; "never bad SQL" holds since
lowering precedes rendering) — the frame cluster extracted to Windows when the
shape guard flagged Lowerer at 3,521 lines; frame pair burned. (2) the
MULTI-STATEMENT lambda hoist ({| let t1=...; expr}): the identity application
hoists the leading statements into the enclosing body and splices the last —
ALL 51 relation declines eliminated (46 passed immediately; declines hide,
measurements name). (3) the ENGINE-VERBATIM pivot-name presentation rule
(pureToSQLQuery.pure:2985 mayQuotePivotColNames): a separator-bearing pivot
name carries its literal quotes AS PART OF THE NAME — applied at the egress,
the deferred-TDS resolver, and the TDS-literal header parse (Fold.pivotIdentity
was already the reference-side inverse); five unit pins consciously re-pinned
to the engine convention. Relation **PASS=278/287 (TRUE 9)**. Channel-B totals:
**988/1,050, TRUE census 49→10, ZERO relation declines**. A mid-flight lesson
recorded: a killed gates SCRIPT leaves its mvn child running — one concurrent-
G4 artifact read as an 18-test regression until the scoped re-run proved
23/23 (never pkill broad patterns; stop tasks by id). Referee byte-identical;
core 4166; ALLGATES GREEN.

### Phase 5 — Walk-family end-state and the last harness semantics

**What, plainly:** `MetamodelWalk`/`MetamodelSteps` (~1,500 lines) re-implement engine
metamodel navigation for structure-inspecting tests (execution plans, model shapes). It
is mechanically safe (it cannot reach a connection) but it is the same third-implementation
pattern. As Phases 1–3 land, its consumers shrink; what remains migrates to compiled
metamodel navigation or is declined with verdicts, family by family. The harness's
remaining `eval` machinery (lets, chain peeling) dies here too — test bodies compile
whole. Size: **M**, and it is *last* on purpose: by then most of it is dead code.

### Phase 6 — Resume burn-to-zero

The standing goal (243 non-passing → floor) resumes on the clean substrate. Prediction
worth writing down now: **some of the 243 will simply fall out of Phases 1–5** — walls
like "host channel: this chain would need interpreted engine code" (9 tests) exist
precisely because the platform could not yet run those constructs.

---

## What stays, forever, and why that is fine

**Java platform natives (Clause 2b, registered):** assert scaffolding over produced
values, `toRepresentation`-style spelling, metamodel operations, execution-time facts,
catalog/DDL text (in the shrink-only SQL register). *Reason: not data-shaped — pushing
them into SQL would be ritual, not engineering.*

**The harness keeps (the stopwatch and clipboard):** parsing test bodies and handing
them over; the scoreboard and gate-before-write; the decline/rescue censuses; the
H2-mirror oracle replay (comparing our rows against golden SQL is *oracle work*, its
whole point is being outside the thing it checks); seed orchestration. *Reason: this is
instrumentation about our platform, not Pure semantics.*

**The reference implementation keeps:** being the spec and the channel-A oracle.
*It never becomes our runtime, and never our architecture.*

---

## The bucket rule: what we implement, what we build differently, what we refuse

This program will implement many Pure functions in Java. Not every function the engine
has deserves implementing — some of what looks like "the platform" is really the engine's
own plumbing showing through. Every function we meet gets sorted into one of three
buckets, and the sort is recorded:

**Bucket 1 — Platform surface: implement it.**
Semantics a user's model can legitimately depend on: the assert family, dates, relation
operations, `executeInDb`, the `meta::pure` functions users call. These are the language.
Java natives, engine source as the verified spec.

**Bucket 2 — Engine mechanism: never implement it as-shaped; build the capability
natively where the capability is real.**
Functions like `wrapH2Boolean` (an internal helper of the engine's H2 emitter) or the
plan/SQL-metamodel transformation passes are the engine's *implementation*, not the
language. We already own those capabilities in our own shape — dialect rewrite rules,
the one compiler. Tests that assert the engine's internal mechanism (the nine
`debugPrint` tree-transformation tests) are **declined with a verdict naming where our
native equivalent lives** — that is the standing precedent, and it stands. Implementing
the engine's mechanism to pass its unit tests would be building a museum replica inside
a working factory.

**Bucket 3 — Mechanism-as-API: the hard middle. Support the enumerated declarative
uses; refuse the general hook.**
Sometimes the engine exposed its internals as an extension surface — connection
post-processors are the canonical case (`cteReplacePostProcessor`, the
`sqlQueryPostProcessors` hooks). Two things are true at once: the *capabilities* users
actually reach for (replace tables for test isolation, schema qualification, CTE
injection) are legitimate product features — and we implement those natively and
declaratively (`testReplaceTablesPostProcessor` already works this way). But the
*general mechanism* — running arbitrary user Pure lambdas over the engine's SQL
metamodel — we refuse: it would freeze an internal IR into a public API, and executing
user Pure over metamodel objects is the interpreter we deleted, wearing a hat. Each
refused hook-shape test carries a verdict (the seven "hook shape is not a replaceTables
lambda" walls are this bucket, recorded).

The discipline that makes the buckets honest: **no silent gaps.** A bucket-2 or
bucket-3 refusal is always a written verdict in the burn-down explanations — "not the
way we build this, here is our native equivalent (or the declarative form we support
instead)" — never a quiet exclusion. And the sort itself is falsifiable: if a real
user model (not an engine unit test) turns out to depend on something we bucketed as
mechanism, that is evidence it belongs in bucket 1 or a declarative bucket-3 feature,
and we re-sort it.

---

## Guardrails while we travel

1. **The referee rules.** Full corpus + full PCT green at every landing; the scoreboard's
   gate-before-write catches silent family drift; scoped runs verify each step cheaply
   first. (This caught the 11-test carrier transient within the hour.)
2. **Deletion is the definition of done.** A migrated semantics ends with the harness
   copy *deleted* and its ledger/register rows removed — the audit taught us that guarded
   residue rots, deleted residue cannot.
3. **New guard for tenet #2:** no `.pure` file under any `*/src/main` (one registered
   exception: the channel-A adapter, which runs inside the *reference* interpreter, not
   our runtime). One test, exact register, added with Phase 1.
4. **Existing guards ride along:** the JDBC census, the SQL-text ratchet, the closed
   class registers, coverage floors — each shrinks as phases delete their subjects.

---

## Why we can feel confident

- **The spec is small and known.** The assert family is 19 short files bottoming out in
  equality + sort + representation — all things the platform already owns or has ported.
- **The hard machinery exists.** The compiler already lowers harder expressions than
  anything in the harness sublanguage; `Row.value` is *literally defined in the spec* as
  `at`/`indexOf` — functions we compile today.
- **The pattern is proven.** We have done this migration four times already — the shadow
  parser died, the interpreter died, the CSV/JSON renderers died, the PCT wire formatter
  died — each time by the same move: platform absorbs, referee confirms, copy deleted.
- **The risk is bounded by increments.** No phase is a big bang; every step is one
  construct, one referee run, one deletion.
- **The scoreboard cannot lie to us** — after this month's audit work, the guards that
  watch it were themselves adversarially probed (9/9 firing).

## Sizes and order, honestly

| Phase | Size | Depends on |
|---|---|---|
| 1. Typed executeInDb results | M | — |
| 2. Platform asserts | L | — (helped by 1) |
| 3. Result envelope model | S | 1 |
| 4. Decoupled PCT | M–L | 2 |
| 5. Walk end-state + last semantics | M | 1–3 |
| 6. Burn-to-zero resumes | (standing) | substrate |

Phases 1 and 2 can interleave (different files, same referee). Nothing here blocks
urgent product work; every landing leaves the tree strictly better and fully green.

---

## Plan audit (pre-flight, 2026-08-18)

*The plan re-read adversarially before starting — five corrections and three verified
comforts, so we begin with the weaknesses named rather than discovered.*

**Corrections the audit forced:**

1. **Phase 1 must preserve the side-effect path.** `executeInDb` has TWO uses in the
   corpus: navigated query results (the chains) AND multi-statement DDL/DML blobs whose
   result is an opaque handle nobody reads. The typed-ResultSet design covers the first;
   the second keeps its current execute-and-discard path. Phase 1's acceptance list now
   includes: the DDL-blob tests (e.g. `ExecuteInDbTest`'s keyword-column blob) unchanged.
2. **Phase 1 is "modeling plus one new mechanism", not "mostly modeling".** Navigating a
   result whose columns are only known at run time needs a plan-time schema resolution
   point (the LIMIT-0 probe moving into compilation). That is genuinely new compiler
   machinery — bounded, but it is the phase's real work and its real risk. Named
   honestly.
3. **Phase 2's default is host-native platform equality, not SQL round-trips.** Most
   asserts compare values the query already produced; re-lowering each into SQL would
   add thousands of round-trips to a sweep this machine runs sequentially. The rule:
   ONE platform equality (Java, Clause 2b) over produced values by default; `EXCEPT`/
   `COUNT` pushdown only where both sides are still unfetched relations. Perf gate:
   the sweep's wall-clock must not grow by more than ~10% at any phase-2 landing.
4. **Phase 2 splits into 2a/2b.** 2a: platform equality + scalar/collection
   assertEquals/assertSize/assertSameElements (the bulk). 2b: the TDS-grid compare
   policies (row cohesion, multiset fallback, header pins) migrate as their own step —
   measured lower bound ~152 grid-literal compares, but the policies also serve
   grid-shaped values beyond literals, so 2b is sized as its own landing.
5. **The tenet-#2 guard register has THREE entries, not one:** `pct_adapter.pure`,
   `pct_types.pure`, `pct_native.pure` — all channel-A resources that execute inside the
   REFERENCE interpreter, never our runtime. The guard names all three exactly.
6. **Phase 4's acceptance is a three-bucket diff, not "nearly identical".** Channel A vs
   channel B rows land as: AGREE / WIRE-BUG (the census we want) / CHANNEL-B-DECLINED
   with a reason (PCT tests exercising reference-only reflection — `deactivate`,
   `genericType` inspection — are expected declines, not failures). Promising
   near-identity without the third bucket would recreate the "100%" mistake.
7. **Phase 4 entry gate:** before building the runner, a parse census over the PCT test
   files specifically (the 1,856/2,110 figure is all reference sources; the test files'
   own rate is the one that matters).

**Verified comforts (checked, not assumed):**

- The `Row.value` navigation really is spec'd as `at`/`indexOf` — Phase 1 compiles
  functions we already own.
- Zero `.pure` anywhere under any module's `src/main` except the three channel-A files —
  tenet #2 needs a guard, not a cleanup.
- The TDS-grid share of assertEquals is small (~5% by literal count) — phase 2a covers
  the great bulk of the 3,278 with scalar/collection equality.

**Phase 1 audit (2026-08-18, the first phase gate): verdict PARTIAL, gaps scheduled.**

*Delivered and verified:* GridReads, HostEval, and the HostResultSet carrier are DELETED;
grid chains execute as MIR over the quarantined RawSql seam through the one Executor
(carriers, shaping, egress — one leaf path); the DDL-blob side-effect path is unchanged
(ExecuteInDbTest); bare `.rows` returns REAL Row objects with real cells — including
NULL-first-column rows, the shape both prior stand-ins broke on (user-forced honesty,
pinned in Phase1AuditTest); the dispatch predicate lives with its owners
(ResultNav.owns / StoreNav.owns, HostChannelPredicateTest re-pinned); TWO new invariants
armed and already earning (RawSql ctor quarantine at source+bytecode; compiler
dialect-blindness — which found two pre-existing breaches: PlanText's renderer parameter
FIXED to the pass-a-function convention, and Scalars' SUBSTRING TextGoldens branch MOVED
to DuckDb's own SubstringClamp rewrite pass, retiring the rule's only carve-out).
Referee byte-match held throughout; two real behavior gaps it caught mid-flip (GRAPH
mis-shaping, the NULL-column emptiness break) were fixed, not worked around.

*The honest gap (the phase's own acceptance text says "the chains stop being special" —
NOT yet true):* ResultNav remains a closed-vocabulary recognizer. `.rows->size()` — a
perfectly well-typed expression — walls today, and that behavior is PINNED as a
tripwire (Phase1AuditTest) that must flip green when the fix lands.

*The fix, user-ratified (2026-08-18), and SIMPLER than first sketched:* we support
exactly TWO result worlds — Relation and Class — and this design uses BOTH, each where
it fits. `ResultSet` is an honest **platform Class** (the engine signature holds:
`executeInDb` returns `ResultSet[1]`) whose **`.rows` property is typed Relation** —
the envelope is a Class (`columnNames` a schema-fact property; the timing/connection
fields Clause-2b host facts), the data is a Relation with late-bound columns. The
late-columns problem is the **dynamic-pivot precedent, already built**: probe on the
same connection at the staticize point, pin into `RelationType.dynamicColumns` (which
exists because pivot needed it), ordinary pipeline after. ONE declared divergence,
recorded: the engine types `rows: Row[*]` — its interpreter architecture in the
signature (bucket 2). The `.rows` type is an ORDERED MATERIALIZED relation kind, not
bare `Relation` — because `at(k)` is only SOUND where an order exists (a result
sequence has one; an unordered relation does not), and `value('name')` exists because
columns are late-bound. Preference order (the phase's opening design question,
user-proposed): (1) REUSE the platform's existing TDS/materialized kind if it fits
without TDS wire/header baggage — zero invention, `$tds.rows->at(0)` already works;
(2) a minimal `Rows <: Relation` nominal carrying only `{at(k), value(name)}`;
(3) never order-assuming operations on bare Relation. Once rows is a relation, `->size()`, `->filter()`, and
everything not yet written work for free; ResultNav's recognition arms DELETE. This is
**Phase 3a**, merged with Phase 3 (the Result envelope rides the same Class modeling).
Phase 2 (platform asserts) does not depend on it and proceeds first.

*Phase 1c progress (slices 1–3, 2026-08-18):* `.rows->size()`, the spec-accessor
`filter`, positional cells (`rows->at(k).value('N')`), `.columnNames`, and fetchDb catalog
grids all execute through the ordinary pipeline — the database does the work, the surface
stays spec-exact. **What GridSplice IS, honestly:** the migration bridge — a shape
translator running in the compiler's inliner pass that rewrites `ResultSet` navigation
spellings into ordinary relation IR (relation source, filter, slice+project, constants),
holding no connection and computing no values. It is real progress (recognized shapes
now COMPOSE with everything; unrecognized ones fall through to the pipeline instead of
walling) but it is still a list of shapes, one layer earlier than the ResultNav arms it
replaces. It dissolves entirely when the typer knows the ordered-relation kind for
`.rows` natively (the reuse-TDS-kind-or-minimal-subtype design) — then `.rows` types as
a relation from the start and `value('N')` is a declared operation, no rewrite needed.
The arm-deletion audit (measured by bypassing the arms under the referee) names exactly
what the pipeline still lacks: the fold column-collect idiom and `executeInDb` in scalar
position — slice 4. Arms stay until the referee proves them dead.

*Phase 1c LANDED (2026-08-18, the playbook restart):* **GridSplice is DELETED** — the
typer knows the grid kind natively. The design, exactly the dynamic-pivot model:
`executeInDb('literal single READ')` and `fetchDb*(literal patterns)` TYPE as
`TypedRawSqlRelation` with **late-bound columns** (`Type.RelationType.lateBound()` — a
wildcard template in pivot's own `dynamicColumns` field); by-name reads **trust the
name** (`Type.RelationType.trustedColumn`, pivot's claim-any rule at typing);
`Row.value` is TDSRow's getter twin (one word in the getter set; `.rows.value('N')`
auto-maps per pure's dot rule); the execution boundary pins the real schema by a FIRST
query (`exec/RawGridSchema` — the `DynamicPivot.staticize` model; tested alternative:
relaxing the SQL layer's stamped-outputs invariant instead erodes a deliberate loud
guarantee — rejected). Late-bound cells are PHYSICAL, never the Any-JSON carrier
(`TypedRawSqlRelation.lateBoundCellRead`). Statement/DDL blobs keep the opaque
execute-once path (`RawSql.isSingleQuery` gates); a query-shaped `let` READS BACK.
`DbMetaData` moved to `compiler/spec/CatalogGrids` (pure catalog-SQL composition —
Invariant 6e made the compiler-visible home mandatory). ResultNav keeps its marker-chain
arms (`.columnNames`, row-major `.values`) until Phase 3 and learned the typed leaf.
Gates: full suite 4117/0; functions referee byte-identical (239/146, fail+shape
name-sets).

*GRID ENDGAME (same day, user-directed "don't wait for Phase 3"):* **ResultNav is
DELETED WHOLESALE.** The boundary stamp grew into the boundary RESOLVER
(`RawGridSchema`): late-bound reads substitute against the FIRST-query stamped schema
— `.columnNames` → the string collection; grid `.values` → the per-row cells map (the
collection-mapper flatten channel); binder `.values` → the TDS row-var cells rule
applied late (scope-tracked, shadow-safe — audit T1.1); `at(cells, k)` picks
statically. The fold column-collect idiom lowers as the per-row MAP it is
(`TypedFold.columnCollectBody` + `Fold.columnCollectAsMap`; prepend order noted). The
2-arg `executeInDb` became a platform native (real pure's wrapper,
relationalExtension.pure:31 — Clause 2b). `chainBottom` moved to StoreNav (its last
consumer), the LIMIT-0 probe to RawGridSchema (the chartered RawSql site). The seam is
StoreNav's model-fact channel alone. Kill-switch bypass audit named 5 live consumers;
all 5 came over to the pipeline; ALLGATES GREEN across every family with the seam dead.

*Adversarial audit (2026-08-18, this feature):* T1.1 binder-shadowing hole FIXED +
pinned; T1.2 stale prose fixed; Tier-2 loud divergences recorded (parenthesized
queries and trailing-comment texts classify as effects; `.rows->at(k).values`
transposition walls; empty-PK catalog grids wall; duplicate probe names wall —
all corpus-silent, all loud). **Follow-up (user-raised, ratified direction):
DEMAND-DRIVEN STAMPING** — probe only when the resolver actually consumes schema
(columnNames/values/positional reads); bare and by-name chains ride the single
query with headers read from the result (the DuckDB native-pivot one-call model).
Phase 3 refinement.

*Process defect recorded:* one batch briefly landed on the remote as two commits (a
silently failed `git add` — stderr was suppressed; remote was unbuildable for ~1
minute). Rule adopted: never silence stderr on staging commands; verify `git status`
before commit.

**Standing risks accepted, with mitigations:**

- Phases 1+2 interleaving touches `Executor` from both sides → land in alternating
  batches, full referee between.
- The harness's compare policies encode adjudications (2-ULP, TDSNull, order rules) —
  migration must move each policy WITH its written justification, and the referee's
  byte-match is the proof nothing was silently dropped.
