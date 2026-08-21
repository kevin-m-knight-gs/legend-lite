# Deep audit — "multiplicity is always correct" (2026-08-20)

Scope: the whole multiplicity program — the stamp-discipline arc
(`0386d20c` … `fc7592eb`), the flipped `StampCensus` invariant, the
deletion/burn legs. Audited at `origin/main` = `fc7592eb`.

**Evidence rule for this document: code and execution only.** Comments,
javadoc, and `docs/` were read to extract *claims*, never as proof. Every
behavioural finding below was produced by compiling this tree and running
real Pure queries end-to-end against DuckDB (and H2 where relevant).
Baseline: `mvn -o -pl core test` → **4166 tests, 0 failures, 16 skipped**
(all 16 skips are grammar/mapping gaps, none multiplicity).

---

## VERDICT

The program made the multiplicity stamp **enforceable where its shape is
provable**, and then wrote its end-state prose as if provable meant total.

Three things are true at once:

1. **The invariant is real.** It is always-on, it throws, and git
   archaeology shows it was never weakened to make a test pass. The skip
   list only ever shrank.
2. **The invariant cannot detect a wrong stamp.** It compares the
   compiler's stamp against SQL whose shape the compiler *chose from that
   same stamp*. A stamp that is wrong but consistently propagated fires
   nothing. "Census zero" measures corpus coverage, not soundness.
3. **The stamps are, in fact, wrong on the single most common shape in
   real Legend code** — and that defect is upstream of everything the
   program worked on. It is in the type checker, not the lowerer.

So: not a hack, but also not the guarantee the prose claims. There is no
end-to-end multiplicity guarantee today.

---

## 1. ROOT CAUSE — `[0..1]` is accepted into a `[1]` slot and stamped `[1]`

`InferenceKernel.unifyMult` (`compiler/spec/InferenceKernel.java:613-639`)
rejects exactly two things: a to-many actual in a to-one slot, and a
statically-empty `[0..0]` actual in a required slot. **It never compares
lower bounds.**

Reproduced (`Compiler.compileModel` + `SpecCompiler`, model
`m::Person.middleName: String[0..1]`):

| expression | stamp produced | correct |
|---|---|---|
| `$p.middleName->toUpper()` | `String[1]` | `String[0..1]` |
| `$a->toUpper()`, `a: String[0..1]` | `String[1]` | `String[0..1]` |
| `$a + 1`, `a: Integer[0..1]` | `Integer[1]` | `Integer[0..1]` |

Control: `$p.nicks->toUpper()` (`[*]` into `[1]`) **is** correctly
rejected. So the guard exists — it only covers the many case.

**The justifying comment is factually false.**
`InferenceKernel.java:575-578` says *"Following engine convention, only the
`[*] -> [1]` case is rejected."* Primary source, verified on this machine at
`legend-pure/.../m3/navigation/multiplicity/MultiplicityMatch.java:273-279`:

```java
int lowerBoundDistance = smallLowerBound - largeLowerBound;
if (lowerBoundDistance < 0) { return null; }   // NO MATCH
```

For a covariant match of value `[0..1]` into target `[1]`:
`0 - 1 = -1 < 0` → **no match**. Real Pure rejects it. That is precisely
why `toOne()` exists as a function.

This one rule manufactures a false `[1]` on *optional property → any
to-one native*, which is the most common expression shape in Legend. Every
downstream layer then trusts that `[1]`: `Stamps.toOne`, the reduction
identity arms, `ResultShape`, the column metadata. **Most of the
user-visible defects in §3 are this rule's shadow.**

The codebase already knows. `compiler/spec/UserCallInliner.java:477-481`:
*"a `[1]`-DECLARED param fed an effectively-`[0..1]` actual (the
engine-convention acceptance) must NOT β-reduce either; the declared mult
lied about emptiness."* That is a local patch at one site, not a fix.

### 1b. No range check on a declared return vs the body

Same `unifyMult`, via `Typer.requireConforms` (`Typer.java:3064-3071`).
Both of these **compile**:

```
function f(a: String[0..1]): String[1] { $a }   // body [0..1], declared [1]
function f(): String[3] { ['a','b'] }           // body [2],     declared [3]
```

Callers of the first see `[1]` on a value that can be empty.

### 1c. `match` invents `[0..1]` instead of unioning the arms

`compiler/spec/MatchChecker.java:269-272` — when the arms' multiplicities
differ, the result is a hardcoded `Bounded.ZERO_ONE`. The correct helper,
`widen()`, is defined 74 lines above in the same file and is not called.

Reproduced: arms `[2]` and `[1]` → stamped **`[0..1]`**. Correct is
`[1..2]`. This loses the upper bound *and* falsely asserts emptiness — a
tightening, the dangerous direction.

### 1d. No combination helper; four divergent copies of the arithmetic

`Multiplicity` exposes only `isMany`, `requireBounded`, `from`, `text`.
There is no `join`/`union`/`product`. The arithmetic is re-derived at
`InferenceKernel.java:607-610`, `IfChecker.java:124-131`,
`MatchChecker.java:195-201`, `Typer.java:2844-2850` — three byte-identical
union copies with four *different* `Var` fallbacks. This is the same
"five divergent copies of `isMany`" problem the `Multiplicity` javadoc
congratulates itself for having fixed, reproduced one level up. It is the
mechanism that will regenerate 1a–1c.

### 1e. Stamps *shrink* across inlining

`Typer.compose` (`Typer.java:2844-2851`) returns `inner` when the source's
multiplicity is a `Var`, silently dropping the source's cardinality. Its
comment ("multiplicity variables do not occur on object-graph paths") is
false. With `firstNameOf<n>(p: m::Person[n]): String[n] { $p.name }` and
query `|m::firstNameOf(m::Person.all())`, `UserCallInliner` produces:

```
TypedPropertyAccess   String[1]     <- WRONG
  TypedGetAll         m::Person[*]
```

A node reading `name` off an N-row extent, stamped **exactly one**, with
its own `[*]` child directly beneath it. `Lowerer.java:3314` will answer
`isMany() == false`; `ResultShape.of` will shape it as a scalar.

---

## 2. The invariant: real, never weakened, and structurally blind

### What checks out (stated plainly, this is the strong part)

- `StampCensus.fire()` genuinely throws; it is always-on. **No
  multiplicity check was ever softened in git history** — the enforcer
  only got stronger, the skip list only shrank, and the `scalar()` funnel
  was never bypassed (recursion goes through it, so sub-nodes are checked).
- `LL_STAMP_COUNT` is set **nowhere** in the build, CI, scripts, or
  harness. That hatch is clean.
- **No multiplicity exemption allowlist exists anywhere** in the guardrail
  suite. `CodeShapeGuardrailTest.METHOD_ALLOWLIST` is literally `Map.of()`.
  The claim that guardrail limits were "paid with REAL splits, not
  allowlist rows" is true. This is the cleanest part of the program.
- Exactly **one** real deferral marker exists in the entire main tree
  (`Stamps.java:32-33`). There is no hidden TODO/FIXME layer.
- Census zero on the corpus is real; I reproduced it (0 `[stamp]` lines).
- `ListShapes` is genuinely deleted; `definitelyScalar`/`listShaped` have
  zero production consumers.

### Why it cannot prove what the prose claims

**It is tautological wherever shape is stamp-derived.** Lowering *picks*
the SQL shape from the stamp at 21+ sites — `Lowerer.java:3173-3195`
(`TypedCast`: `isMany` → array vs scalar), `PureSql.java:146-151`
(`asList(e, many)`), `Scalars.java:2478-2480`, `Scalars.java:3295`
("the STAMP decides"). On those nodes neither arm can ever fire.

**The MANY-STAMP arm is near-vacuous.** `definitelyScalar`
(`StampCensus.java:150-162`) recognises 8 of 34 `SqlExpr` forms — seven
literal records plus scalar `Cast`, and scalar `Cast` is emitted *only* on
the `!isMany` branch. So it cannot catch a `[*]` stamp sitting on
`EXISTS`, `CheckedOne`, any aggregate reducer, a window call, a column
read, or any of ~151 scalar function calls.

**The ONE-STAMP arm's biggest population is exempted by type.**
`designedListCarrier`'s first disjunct is `type instanceof RelationType`.
The scalar-chain arm that produces almost all list-shaped scalars
(`Lowerer.java:2827-2864`) is guarded by the *identical* predicate and
emits `SELECT LIST(col)` subqueries — fully exempt. The `NullLit` early
return additionally exempts every `[1..1]`-stamped node lowering to SQL
NULL, which is a genuine lie.

**~10 deliberate lies are in the tree that it cannot see.**
`normalizer/UnionSynthesis.java` still mints `toOne` type-alignment shims
at `:950, :984, :1021, :1194, :1213, :1359, :1491`. The program doc
concedes it (`:348-350`, "the shims' `[1..1]` alignment lies remain
(invisible to the census)") and the arm-factory leg was never built. So
*"the type system can no longer lie silently"* is false as written.

**"Production code never consults shape" (`StampCensus.java:126`) is
false.** Six live pre-dialect shape-sniffs still decide multiplicity
behaviour: `Scalars.java:2288-2331` (the `in` rule — four `instanceof`
tests, slice 6 converted only one), `Scalars.java:426-429` +
`aggStrip:3277-3293`, `PureSql.java:146-151`, `Scalars.java:3300-3306`,
`ListEncodings.java:38-40`, `Lowerer.java:3167` (whose own comment admits
`.values` is an `ArrayLit` "even at bounded-1 multiplicity").

### It crashes on legal user code

```
{| [1,2]->toOne() }
→ IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED
  (stamp program, docs/STAMP_DISCIPLINE_PROGRAM.md): ONE-STAMP/LIST-SHAPE
  mult=[1..1] sql=ArrayLit ... callee=...::toOne
```

Every literal variant, including through a `let`. Real Pure raises a
*user* error ("Cannot cast a collection of size 2 to multiplicity [1]");
we hand the user a developer assertion citing an internal design doc.

Run with `LL_STAMP_COUNT=1`, the underlying defect appears:

```
{| [1,2]->toOne() }           => SCALAR([1, 2])
{| [1,2]->toOne()->toString()} => SCALAR([1, 2])
```

**A scalar-shaped result whose value is the whole two-element list.** The
invariant converted a silent wrong answer into a crash; it did not fix it.
`Scalars.java:403-412` says so outright: *"the `[1]` stamp is the lie, not
the shape … the C2 fix is provenance-split … not a blanket emission."*
No test covers `literal->toOne()`, which is why the suite is green and
why census zero did not catch it.

---

## 3. Runtime: the cardinality guarantees are partial and route-dependent

### `toOne` erases on the path that matters

`lowering/Scalars.java:413-433` has three arms; **the default arm deletes
the call** (`return args.get(0)`). The real guard (`CheckedOne`, arm B)
fires only when the operand is a `Call` whose `SqlFn.producesList()` is
true — **16 of 167 functions**. No property navigation, no column read, no
subquery, no variable ever matches. `toOneMany` is an unconditional no-op.

Reproduced against DuckDB with a real relational model (Dept D1 has two
members):

```
Dept.all()->filter(d|$d.did=='D1')->project([d|$d.members->toOne().name],['m'])
  => two rows: [Ann], [Bob]        -- no error; column stamped [1..1]
```

### The lower bound is never enforced, anywhere

`dialect/AnsiSqlRenderer.java:294-311` emits
`CASE WHEN len(x) > 1 THEN error(...) ELSE list_extract(x,1) END`.
**Only `>1` is tested**; `len = 0` falls to the ELSE and yields NULL.
Reproduced:

```
{| []->toOne() }                            => SCALAR(null)
{| [1,2,3]->filter(x|$x>10)->toOne() }      => SCALAR(null)
{| [1,2,3]->filter(x|$x>10)->toOne() + 1 }  => SCALAR(null)   -- propagates
```

Structural cause: `lowering/Stamps.exactlyOne` — the only lower-bound-aware
predicate in the lowering layer — **has zero callers**. Every other stamp
read (`atMostOne`, `toOne`, `many`) reads the upper bound only.

At egress the enforcement is inconsistent by result shape
(`exec/Executor.java`): the COLLECTION arm checks the lower bound
(`:288-297`), the SCALAR arm checks only for a second row (`:253-268`),
and the TABULAR arm checks neither. Hence a self-contradicting result:

```
Person.all()->project([p|$p.name],['n'])     -- name: String[1], column NULL
  => Column[multiplicity=Bounded[lower=1, upper=1]] holding null
```

Same for a `Dept[1]` association over a dangling FK.

### The guard is dialect-dependent

Tested directly. On DuckDB the `>1` guard fires. On H2 the whole family is
a hard `DialectCapability` refusal ("LIST_FILTER reached a dialect without
a list encoding") — loud, therefore honest, but the guard is demonstrably
functional on exactly one backend. And `dialect/EngineStyleH2.java:81-84`
**strips `CheckedOne` to the verbatim inner value** — that is the renderer
behind `PlanEnvelope`, so the execution plan shipped for a real engine has
the guard removed.

`docs/STAMP_DISCIPLINE_PROGRAM.md:107-110` claims the H2 must-fire path is
`CREATE ALIAS RAISE_ERROR`. **`RAISE_ERROR` does not exist anywhere in the
source.** The doc describes a mechanism that was not built.

### There is no runtime parameter binding at all

`setObject`/`setString`: **zero hits** repo-wide. Every value is inlined
into SQL text before `prepareStatement`. So a declared parameter
multiplicity has no site at which it *could* be checked.
`FunctionParametersValidationNode` exists only as plan *text*
(`PlanText.java:236-241`); it is never executed.

---

## 4. The empty-identity fork — a documented hole that is live and unowned

`lowering/Stamps.java:25-33` admits it in prose. It is real, it is
user-visible, and the identical runtime value gives different answers
depending only on the *static* stamp:

| expression | operand stamp | our answer | Pure |
|---|---|---|---|
| `[true,false]->filter(x\|false)->and()` | `[*]` | `true` ✅ | `true` |
| `…->filter(x\|false)->head()->and()` | `[0..1]` | **`null`** ❌ | `true` |
| `…->or()` | `[*]` | `false` ✅ | `false` |
| `…->head()->or()` | `[0..1]` | **`null`** ❌ | `false` |
| `…->joinStrings('-')` | `[*]` | `''` ✅ | `''` |
| `…->head()->joinStrings('-')` | `[0..1]` | **`null`** ❌ | `''` |
| `…->makeString()` | `[*]` | `''` ✅ | `''` |
| `…->head()->makeString()` | `[0..1]` | **`'TDSNull'`** ❌❌ | `''` |

The last leaks an internal sentinel string as a **user-visible data
value**.

**It reproduces on real relational data**, not just literals — a Dept with
zero members gives `$d.members.name->joinStrings('-')` → `null` in a column
stamped `[1..1]`.

Mechanism: ~30 identity arms in `Scalars.java` gate on `Stamps.toOne`
(`upper == 1` exactly, so `[0..1]` is included) and return the bare SQL
NULL; the empty-identity `COALESCE` lives only in the *list* arm. Sites
include `Scalars.java:185` (`and`), `:194` (`or`), `:905` (`joinStrings`),
`:860` (`makeString`). `size()` (`:845`) **does** null-guard its identity
arm — so the family is inconsistent, not uniformly designed.

**The stated owner is fictional.** `Stamps.java:33` assigns it to "the PCT
lane". Grepping `docs/` and `pct/` for this fork returns **zero** ledger
rows. It is named as owned debt and is in fact unowned and unpinned.

Two adjacent binder errors from missed stamp-wraps, same root:
`$x.street->add('z')` → `Binder Error: list_concat(VARCHAR, VARCHAR[])`
(`Scalars.java:1414-1418` — no `asList` wrap, unlike its neighbours), and
`->distinct()` on a `[0..1]` → `Invalid LIST argument during lambda
function binding` (`Scalars.java:1477-1478` — no guard, while its own
synonym `removeDuplicates` at `:1447` does guard).

---

## 5. Egress: honest at one exit, dropped at the rest

**Credit:** the `Scalar`-vs-`Collection` split is genuinely stamp-driven —
`exec/ResultShape.java:23-71` is a closed switch with no value sniffing,
and `StatementExecutor.java:3091-3112`'s override reads the *declared*
stamp. `[1]` → `Scalar`, `[*]`-of-size-1 → `Collection(1)`. Correct.

Past that point the stamp is discarded:

- `ExecutionResult` has **no `multiplicity()` accessor**. `[1]` and
  `[0..1]` are both `Scalar`; `[*]` and `[1..*]` are both `Collection`;
  exact bounds are unrecoverable.
- Three of four egress channels build columns with the 2-arg constructor
  that leaves multiplicity `null`, and `exec/Column.multiplicity()` has
  **zero readers in `main/`**.
- On the JSON/CSV product wire, `Compiler.java:397-403` routes SCALAR and
  COLLECTION through **one arm**, and `lowering/WireRender.java` never
  reads the schema's multiplicity (zero hits). **A `[1]` and a
  `[*]`-of-size-1 produce byte-identical JSON and CSV.**
- `exec/MetamodelWalk.java:124-137` unwraps any singleton list by a pure
  runtime **shape sniff**, erasing the distinction in the host metamodel.

**Protocol round-trips that don't round-trip.** There is no typed→protocol
conversion at all (`Multiplicity.from` is one-way). An undeclared relation
column defaults to `[1]` in the parser (`TokenStreamCursor.java:1249`, and
`TypeExpression.java:201-203` asserts this matches the engine) while the
emitter hardcodes `{"lowerBound":0,"upperBound":1}`
(`ProtocolEmitter.java:1774-1780`). Worse, `NameResolver.java:486-491`
silently drops the `multiplicityDeclared` flag whenever a column's *type*
needed resolving — flipping the emitted multiplicity. `Column.equals`
excludes that flag, so no AST-equality test can observe it.

**Plan text has two disagreeing printers.** `[1..*]` prints as `*` from
`PlanText.java:122-128` (lower bound dropped) and `1..*` from
`StatementExecutor.sizeRange:994-1008`. **Zero in-repo goldens pin
`resultSizeRange`**, and the external corpus contains no `1..*` row at all
— so the divergence is unfalsifiable by any evidence that exists.

---

## 6. The evidence base is thinner than the claims

- **Zero tests exercise a runtime cardinality violation end-to-end.**
  Searches for `"Cannot cast a collection"`, `"more than one row"`,
  `SIGNAL`, `45000`, and `assertThrows` × multiplicity terms all return
  **0** across `core/src/test`. Of 161 `toOne` lines in tests, the 50 that
  execute all apply it to a source that cannot yield 2+ rows. No negative
  fixture exists.
- **The corpus gate is vacuously green in CI.**
  `RelationalCorpusRunner.java:55` is
  `Assumptions.assumeTrue(Corpus.available(), …)`, where `available()`
  tests for `~/legend/legend-engine` (`Corpus.java:48-53`).
  `.github/workflows/gate.yml:28` runs it on stock `ubuntu-latest` with a
  single checkout of this repo. **The step whose stated purpose is the
  corpus regression gate is skipped, not run.**
- **PCT never runs in CI.** `gate.yml` builds `-pl core` only, so the
  "census zero on all five PCT suites" evidence is unenforced.
- Two real swallow sites: `StatementExecutor.java:2106-2116` wraps a full
  `Lowerer.lower` in `catch (RuntimeException) { }` (a stamp violation
  becomes "not seedable"), and `pct/.../ChannelB.java:209-212` downgrades
  it to a census row.
- The `ENGINE-FRONTIER` bucket in `ChannelBEssentialTest` classifies by
  test **name** against a snapshot of the reference engine's own DuckDB
  exclusions. Matching that set is a legitimate parity policy — but a lite
  failure lands in the bucket regardless of *why* it failed, so the
  `[1,2]->toOne()` internal crash would be absorbed there and never count
  against `trueWireBug == 0`.

---

## 7. Program-admitted debt, re-verified

| admission | verdict today |
|---|---|
| `Stamps.java:25-33` — `[0..1]` empty at runtime → NULL where Pure has an empty identity; "owned by the PCT lane" | **OPEN; owner fictional** (§4) |
| `STAMP_DISCIPLINE_PROGRAM.md:118-123` — "DISCOVERED FORK: `toOne` over an EMPTY scalar carrier — Pure throws, we let NULL flow" | **OPEN, never adjudicated** (§3) |
| `:348-350` — UnionSynthesis shim `[1..1]` lies remain, arm-factory leg owns them | **OPEN, leg never built** (§2) |
| `:131-133` — message parity vs Pure's "Cannot cast…" wording | **OPEN** — agg-strip surfaces DuckDB's own message |
| `:84-86` / `:397` — "89 recognizer sites need the audit" → "RETIRED UNBUILT" | **Never done; 101 sites today**, 6 using `endsWith("::toOne")` (`Scalars.java:901`, `SqlPostProcessors.java:197-199`, `RelationalMapperRenames.java:262-264`, `Substitution.java:1463`) — a user function named `my::customToOne` is hijacked |
| `:302-305` — `minus` LIST arm wrong for a runtime size-1 many-stamped list | **CLOSED** (`Scalars.java:302-311`) |
| `:466-468` — three surviving frame dispatches | **2 of 3 closed**; `in` harmonization: 1 of 4 shape tests converted |
| `326e1463` — `LL_STAMP_COUNT` + raised STDERR pin "retire when the census flips" | **NOT retired** (`ObservabilityGuardrailTest.java:45`, `:59`) |

Stale/false comments confirmed: `StampCensus.java:126` ("production code
never consults shape"); the class's own opening paragraph still calls
itself a measurement instrument; `Lowerer.java:2221` still says
"measurement only"; `STAMP_DISCIPLINE_PROGRAM.md:15` contradicts `:417`.

---

## 8. Recommended order of work

1. **Fix `unifyMult` to compare lower bounds** (`InferenceKernel.java:613-639`),
   matching `MultiplicityMatch.java:273-279`. Delete the false
   "engine convention" comment. Expect significant fallout — that fallout
   is the real size of the debt, and everything else here is downstream.
2. **Give `Multiplicity` a real `union`/`product`** and route all four
   ad-hoc copies through it. Fix `MatchChecker:269-272` and
   `Typer.compose:2850` as part of that.
3. **Make `toOne` honest**: emit `CheckedOne` on every arm (including
   navigation), and check *both* bounds — `len <> 1` raises, with Pure's
   message. Add the missing negative fixtures; there are currently none.
4. **Enforce the lower bound at egress** for the SCALAR and TABULAR arms,
   as the COLLECTION arm already does. Give `Stamps.exactlyOne` its callers.
5. **Fix the empty-identity fork** (§4) — it is four wrong answers and one
   leaked sentinel, on a documented, unowned hole.
6. **Make the gates real**: fail (not skip) when the corpus is absent in
   CI, and run `pct` in CI, or stop citing PCT census zero as evidence.
7. **Correct the prose.** The invariant's honest claim is its own hedge —
   *"absence is not proof of health, but firing IS proof of a lie."* The
   end-state sentences that go beyond it should be retracted.
