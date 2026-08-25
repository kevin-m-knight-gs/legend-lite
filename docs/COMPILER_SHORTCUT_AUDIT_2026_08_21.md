# Where we hack IN the compiler, and where we hack AROUND it

Re-audit at `origin/main` = `cfe2b876`. Two questions, kept separate:

- **IN** — shortcuts taken inside the compiler: a decision made on a proxy
  instead of the fact, a user's declaration dropped, a stamp minted that
  the code cannot back.
- **AROUND** — machinery outside the compiler doing work the compiler
  should do: Java computing Pure semantics, harnesses normalising away
  the difference, gates measuring a denominator the compiler chose.

**Evidence rule: code and execution only.** Comments, javadoc and `docs/`
were read to extract *claims*, never as proof. Every behavioural line
below was produced by building this tree and running the query. Baseline:
`mvn -o -pl core test` → **4185 tests, 0 failures, 16 skipped**.

> **ADOPTION NOTE (2026-08-21, ratified).** This audit is ADOPTED and its
> recommended order governs the work queue (blockers 1–3 = §5, §1a,
> recs #9–13; then rec #1 as the render leg's R1). One standing caveat:
> **§4's findings are lane-conflated** — its oracle probes ran on the
> pure-interpreted lane while our conformance target is the
> engine-relational corpus lane, and the two reference lanes do not agree
> on all of these behaviors. Therefore `1 == 1.0` (kind-strict equality),
> `indexOf` base, and `substring` base get **per-lane adjudication with
> engine witnesses** before any shared-emission change. `cast()` being
> unchecked and `isDistinct()` crashing are real bugs on any lane and
> need no adjudication. §7b (TRUST_ONE over user declarations,
> GraphEmission's LIMIT 1) is likewise an adjudication item requiring
> engine witnesses — a test pins current behavior
> (ResolveNavigationTest "audit R3"); do not reflex-fix.

---

## 0. The previous audit's fixes: verified, and mostly real

`docs/MULTIPLICITY_AUDIT_2026_08_20.md` was adopted. Re-running its exact
probes:

| finding | status |
|---|---|
| `[1,2]->toOne()` crashed with an internal assertion | **FIXED** — raises Pure's real message |
| `[]->toOne()` returned null (lower bound unenforced) | **FIXED** — raises size 0 |
| empty-identity fork (`and`/`or`/`joinStrings`) | **FIXED** on the value lane |
| the `'TDSNull'` sentinel leaking as user data | **FIXED** on the `[0..1]` arm (see D6) |
| `unifyMult` accepted `[0..1]` into `[1]` | **FIXED** — rejects, matching `MultiplicityMatch` |
| declared return vs body not range-checked | **FIXED** — `[2]` body under `[3]` now fails |
| `match` arms hardcoded to `[0..1]` | **FIXED** — `[2]`/`[1]` now unions to `[1..2]` |
| `endsWith("::toOne")` name dispatch | **FIXED** — exact `signatureKey()`, no bare-name fallback |
| `Stamps.exactlyOne` had zero callers | **FIXED** — four callers |
| ~10 `AppliedFunction("toOne")` shims in `UnionSynthesis` | **FIXED** — zero remain; all 22 became `trustOne` |
| four divergent copies of bound arithmetic | **FIXED** — whole-tree grep finds combining arithmetic only in `Multiplicity.java` |
| derived-property bodies unchecked | **FIXED** by `bc5b5d92` |
| the CI corpus gate was vacuously green | **FIXED** (`c4386547`) |

**Two corrections I owe from my own last audit.** I reported the
relational `toOne` no-op as a defect. It is **reference-faithful**:
`legend-engine/.../pureToSQLQuery.pure:10251` maps `toOne` to
`processNoOp`, and `:10252` does the same for `toOneMany`. The relational
`joinStrings`-over-empty NULL is *also* reference-faithful
(`pureToSQLQuery.pure:8260-8275` → `group_concat`, no COALESCE; golden at
`testFunctionVariables.pure:135`). The team was right and I was wrong.

One provenance claim of theirs does not hold: `d1c968e0` cites "engine
`resultSizeRange` parity", but in the reference `resultSizeRange` is only
ever read via `isUpperBoundEqualTo(1)` to choose realize-vs-stream
(`ExecutionNodeResultHelper.java:32-41`). There is no lower-bound check in
the reference. The fix is defensible; the citation is not.

---

# PART I — hacking IN the compiler

## 1. THE ROOT CAUSE: the compiler does not trust its own type information

`Lowerer.java:3164-3167`, in its own words:

> *"the scalar channel carries collections as LISTs … row-var `.values`
> is an ArrayLit **even at bounded-1 multiplicity**"*

— and the code then branches on `value instanceof SqlExpr.ArrayLit`.

That sentence explains almost every wrong answer in this report. The
lowerer cannot rely on the stamp, so it re-derives intent by
**pattern-matching the SQL it just emitted**. Every defect below is a
place where that pattern-match did not fire.

Scale of the workaround: **32 behaviour-deciding `instanceof ArrayLit`**
in production, 4 `Cast`→`Array` sniffs, 11 `ScalarSubquery` sniffs, and
~210 SqlExpr-subtype `instanceof` in `CarrierStrategies` +
`EngineStyleH2` alone. `CarrierStrategies.java:743-762` uses a 13-clause
conjunction to reverse-engineer a tree that `Lowerer.java:2784-2794`
constructed three files earlier.

`StampCensus.java:126-130` has already retracted the "production code
never consults shape" claim in its own source. Good. The debt is real and
now honestly labelled.

### 1a. The invariant is blind for the same reason the rule is wrong

`toOne` picks its *checked* lane by sniffing the emitted SQL
(`Scalars.java:455-458`: `instanceof ArrayLit || Call.producesList()`).
`StampCensus.listShaped` (`:131-138`) uses the **same predicate set** and
also has no `Case` arm. So:

```
[1,2]->toOne()                  => ERROR "collection of size 2"      ✓  ArrayLit
[1,2]->filter(x|true)->toOne()  => ERROR "collection of size 2"      ✓  Call
[1,2]->slice(0,2)->toOne()      => SCALAR(value=[1,2], type=INTEGER) ✗  Case
if(true,|[1,2],|[3,4])->toOne() => SCALAR(value=[1,2], type=INTEGER) ✗  Case
range(1,5)->toOne()             => SCALAR(value=[1,2,3,4])          ✗  RANGE_FN not whitelisted
[1,2]->zip([3,4])->toOne()      => SCALAR(value=[Pair,Pair])        ✗
[3,1,2]->sort()->toOne()        => ERROR "collection of size 3"      ✓  LIST_SORT whitelisted
```

A two-element list handed back as `Integer[1]`. The "always-on,
build-breaking" invariant fires **zero** times, because the checker and
the rule it polices share their evidence procedure. This is the concrete
form of the tautology the last audit argued for in the abstract.

`SqlFn.producesList()` is a hand-maintained 16-entry switch: `LIST_SORT`
is in it, `LIST_SORT_DESC` and `RANGE_FN` are not — so ascending and
descending sort get different safety guarantees for the same query.

## 2. Stamps the compiler cannot back, and the join rule that breaks them

One defect at three layers.

**The artifact** — one query, one row, two contradictory assertions:

```
project([p|$p.id, p|$p.dept->isEmpty(), p|$p.dept.dname])
   => [P3,  empty=true,  dname: String[1..1] = null]
```

- **`TypedJoinSlot` cannot represent a join kind.** The record has no
  such field; its `info` javadoc is literally
  `Relation<S + (alias:TargetRow[1])>` — the target row is stamped `[1]`
  unconditionally. `resolver/Pipelines.java:428,563` hardcode `"LEFT"`.
- **A user's `(INNER)` on a property chain is silently discarded.**
  `MappingNormalizer.java:2823-2825` *does* read `joinType()` and throws
  `NotImplemented` for an explicit join type on a `~filter` — so the same
  annotation is a loud refusal in one position and a silent drop in the
  other. Both mappings produce byte-identical SQL.
- **~15 sites copy a right-side `[1]` across an always-LEFT join**
  (`StoreResolver.java:757,828-843`, `NavMaterializer.java:571,645,739`,
  `CorrelatedSubselects.java:233,315,1201,1494`, …).

`StampCensus` cannot see any of it: it compares stamp against SQL
*shape*, never *nullability*, and exempts `NullLit` outright
(`:60-63`). Its javadoc says so.

## 3. Confirmed wrong answers in lowering

| # | query / shape | result | correct |
|---|---|---|---|
| a | `exists` with two correlation keys sharing a column name | **0 rows** | 1 row |
| b | `joinStrings('[',',',']')` after a `concatenate` | **wrong order** | union order |
| c | H2 `FULL OUTER` + `GROUP BY` + `LIMIT 5` | **up to 10 rows** | 5 |
| d | `[1,2]->filter(x\|false)->head()->sum()` | `null` | `0` |
| e | `Dept.all()->project([d\|$d.members.name->makeString()])` | `TDSNull` | `''` |

(a) `ExistsJoinForm.java:99-106` dedupes the DISTINCT key projection by
**bare column name** while `:114-118` builds the ON over all pairs — with
`T_PERSON.NAME` and `T_DEPT.NAME` present, the emitted SQL compares
`DEPT_NAME` against a *person's* name. Reachable on every main path.

(b) `Lowerer.java:911` gates the union-ordering obligation on
`instanceof SqlAgg.Reducer`; the 3-arg form emits a `SqlFn.CONCAT` Call,
so `u_ord` is never minted. One extra argument silently drops ordering.

(c) `CarrierStrategies.java:59-87` gates on the FROM only, then copies
`groupBy`/`limit` into both UNION branches. The explode arm 30 lines
later correctly requires every clause empty.

(d) The empty-identity fork was closed for `and`/`or`/`joinStrings`/
`makeString` and **not** for the arithmetic reductions
(`Scalars.java:1214,1224,1237,1247,1262,…`). Same file, same fork, half
fixed.

(e) `Scalars.java:940-951` spared only the `[0..1]` arm; a many stamp
still substitutes the sentinel.

Two more, silent and data-dependent:

- **`relation->size()`** emits `COUNT(<col>)` for single-column
  projections (`RelationPredicates.java:59-67`), so it undercounts NULLs
  — 2 or 3 for the same data depending on how many columns you projected.
  Cited engine parity, but amplified by the always-LEFT rule that
  manufactures the NULLs.
- **A view's ungrouped column becomes `ANY_VALUE`**
  (`ViewRelation.java:203-217`) — an ill-formed model gets a
  nondeterministic answer instead of a compile error.

## 4. Type-system shortcuts

Oracle: `/Users/neemsandv/legend/legend-pure`.

```
1 == 1.0                            => true    Pure: false (kind-strict)
true == 1                           => true    Pure: false
'1' == 1                            => true    Pure: false
%2014-01-01 == %2014-01-01T00:00:00 => true    Pure: false
1->cast(@String)                    => 1 typed STRING      Pure: Cast exception
1->cast(@Boolean)                   => true                Pure: Cast exception
[true,false]->plus()                => 1 typed BOOLEAN     Pure: no such overload
'abcdef'->indexOf('cd')             => 3       Pure: 2
'abcdef'->indexOf('zz')             => 0       Pure: -1
'abcdef'->substring(2)              => bcdef   Pure: cdef
[2,3,4]->times()                    => 24.0    Pure: 24 (Integer)
[1,2,3]->isDistinct()               => ArrayIndexOutOfBoundsException
```

- **`cast()` performs no check.** `CastChecker.java:40` mints the node;
  the three consumers are pass-throughs. `grep "Cast exception"` over
  main → nothing. The reference raises
  (`.../natives/essentials/lang/cast/Cast.java:124-134`). The standard
  Legend idiom `$p->cast(@Child).childProp` becomes a silent wrong answer.
- **`indexOf` absent → `0`, not `-1`.** The idiomatic
  `->indexOf(x) != -1` guard is therefore *always true*. Note the internal
  inconsistency: *collection* `indexOf` is correctly 0-based.
- **Overload specificity is a 3-bucket score**
  (`InferenceKernel.java:1055`), not generalization distance, so any two
  overloads that are both ancestors of the argument tie and the call is
  rejected as ambiguous. The reference orders by `typeDistance`
  (`TypeMatch.java:418-433`). Any 3-level hierarchy with overloads fails.
- **`match` ignores generic type arguments** —
  `^List<Integer>(...)->match([l:List<String>[1]|…, l:List<Integer>[1]|…])`
  takes the **String** branch.
- **Extended primitives are erased** and their constraint blocks dropped
  at `ElementParser.java:1264-1267`.
- **`Any` is not the top of the generalization graph**
  (`ModelContext.java:223-244` has the `Nil` bottom arm and no top arm);
  `InferenceKernel` patches around it, the other seven `isSubtype` call
  sites do not.

**Correct, verified, not padding:** subtyping *is* the real
generalization graph; arithmetic result types come from reference-faithful
overload signatures, not a promotion table; unbound type variables throw
rather than defaulting to `Any`; `new`/lambda/argument checking is strict;
banker's rounding is exact (17.5→18, 16.5→16); `mod` on negatives is
**correct** (−5 mod 2 = 1, −12 mod 5 = 3 — a subagent claim that did not
reproduce); `splitPart`, `rem` sign, `median`, and the whole
`slice/at/drop/take/init/tail/reverse/sort/fold/range/add` family match.

---

# PART II — hacking AROUND the compiler

## 5. The null-drop mask: one Java line hiding a lowering rule

The sharpest instance. Model `nick: String[0..1]`, rows
`[NULL, 'Al', 'Cee']`; Pure's collection is `['Al','Cee']`.

```
P.all().nick                 => COLLECTION[Al, Cee]     ✓
P.all().nick->size()         => 2                       ✓
P.all().nick->at(0)          => null                    ✗ 'Al'
P.all().nick->at(1)          => Al                      ✗ 'Cee'
P.all().nick->indexOf('Cee') => 2                       ✗ 1
P.all().nick->toOne()        => "collection of size 3"  ✗ size 2
```

`size()` says 2 and `toOne()` says 3, in the same query.

The Pure rule *"a collection holds no empties"* is implemented in **one
Java line at egress** — `Executor.java:298-300`, `if (v != null)
values.add(v)`. The lowerer never learned it: the SQL is a bare
`SELECT t0.NICK FROM T t0`, no `IS NOT NULL`. So the database's collection
has 3 elements, the egress collection has 2, and every operation the
compiler lowers *into* SQL sees the 3-element list. Three notions of one
collection: `LIST()` keeps nulls, `COUNT()` drops them, Java drops them
again.

The mask sits at exactly the place a human checks, which is why it
survived. **Owner: the lowerer** (emit `IS NOT NULL` at the projection
site, then delete the Java drop).

## 6. Semantics computed in Java

- **`decodeAny` silently downgrades Decimal to Double.** — **HEALED
  (M4 re-land, 2026-08-25)**: the LITERAL carrier preserves Decimal
  through mixed-`Any` BY GRAMMAR (the D-suffix spelling — exactly the
  kind json erased); LITERAL-labeled cells never reach `decodeAny`
  (the label IS the decode instruction). The
  `VerdictWorld2ConsistencyTest.decodeAnyPrecision` probe — built to
  detect this healing — flipped to assert exact BigDecimal equality
  in the landing commit; the `PERMANENT-ALLOWED` shelter no longer
  covers a live loss (genuine Variant values keep raw JSON by
  contract).
- **`size()` answers differently inside a user function.**
  `NormalizeFolds.java:66-78` folds `size(x)` to the stamp's lower bound;
  `Scalars.java:911-914` lowers it to `CASE WHEN IS_NULL(x) THEN 0 ELSE 1`.
  For a `[1]`-stamped property that is NULL, the direct form gives **0**
  and the same expression inside an inlined user function gives **1**.
- **`MetamodelSteps.java:49-54`** implements `toOne`/`toOneMany`/`cast` as
  `return recv;` with no cardinality check, and `at` as a raw `List.get`.
  Pure multiplicity semantics are simply absent in the metamodel lane.
- **`StatementExecutor.java:1124-1686`** is a ~560-line untyped
  interpreter (`planWalk` → `walkProp(Object, String)` → …), dispatching
  property reads by name string on `Object` with `default -> null` at
  every arm. It **is** registered and shrink-only in `JavaEvalLedgerTest`
  — credit — but `ArchitectureTest.theInterpreterPerformsNoJdbc:543`
  cannot cover it, because it lives in the same class as the JDBC seam.

## 7. The guards are enforced at spellings; the shortcuts moved off-spelling

Mechanically verified with `javap`:

```
EngineStyleH2.java:171   -> com.legend.compiler.element.type.PlatformTypes.TDS_NULL_CELL
   javap | grep -c com/legend/compiler   =>  0     (but: ldc "TDSNull")

SnapshotEnvelope.java:133,139 -> com.legend.resolver.AsorRef.SEG_LEN_WIDTH / .MARKER
   javap | grep -c com/legend/resolver   =>  0
```

Both are real cross-layer source dependencies. Because the targets are
`static final` constants, javac inlines the literal and emits **no
bytecode edge**, so `ArchitectureTest.sqlLayerIsFullyStandalone` and
`packageDependenciesAreAcyclic` are green on live violations of
themselves. The acyclicity rule's own javadoc says it exists because
"two cycles shipped invisibly because one direction used fully-qualified
names no import-based review sees" — and it is now green on a cycle of
exactly that shape.

The same pattern in two more forms:

- **11 `ThreadLocal` ambient channels** cross layer walls with zero
  imports (`NullSemantics` ×5, `RawSqlBoundary` ×4, `DriverPkOption`,
  `TextGoldens`, `RelationReads`, `StampCensus`, `EngineTextBoundary`,
  `PostProcessBoundary`, `PctRenderOption`). Two are set from **test**
  code into production. All pass `noStaticMutableState`, whose regex is
  `static (?!final )`. `Lowerer.java:3143` branches on one of them and
  emits different MIR for the same query — the "compile-side layers are
  dialect-blind" rule does not fire only because the flag was placed
  inside `com.legend.lowering`.
- **`Object`/`String` erasure** — `DateShifts.java:65-79` mints DuckDB
  function names (`to_years`, `to_microseconds`) inside lowering and
  splices them raw into SQL, after which three dialects un-map them. This
  is the `String mapXxxName(String)` shape AGENTS.md calls "the smoking
  gun"; there are two, and nothing tests for them.

**Diagnosis:** every rule here is enforced at a spelling — an import, a
bytecode edge, a regex — and the shortcuts have migrated, without anyone
deciding to, into the three constructs that carry meaning without a
spelling: compile-time constants, `ThreadLocal` ambient state, and
`Object`/`String` erasure. None of the 32 ArchUnit rules can see any of
them.

## 7b. `TRUST_ONE` is applied to user declarations, not just synthesis

The "provenance split" (`40961299`) deserves the credit I gave it for
*naming* the synthesized population — but the name is applied wider than
"synthesized". `MappingNormalizer.java:3366-3373`, in the mapping's
new-instance construction path, wraps **every `[1]`-declared property
value** (bar four exempt function shapes) in `Pure.Lite.TRUST_ONE`:

```java
wrapped.put(name, toOneDeclared && !exempt
        ? new KeyExpression(new AppliedFunction(Pure.Lite.TRUST_ONE, List.of(v)), …)
```

`TRUST_ONE` lowers to identity with no guard (`Scalars.java:474-476`) and
is explicitly excluded from the egress guard (`Lowerer.java:330-331`), so
the wrap silences the compile-time checker *and* emits nothing. A user's
`[1]` declaration is therefore trusted, never verified.

**The suite pins the resulting wrong answer.** `ResolveNavigationTest.java:215-222`:

```java
@DisplayName("toOne()-wrapped navigation is transparent to the path (audit R3)")
… project(~[legal: p|$p.employer->toOne().legal]) …
assertEquals(List.of("ACME", "ACME", "null"), …);
```

An explicit **user** `->toOne()` over a `[1]` association with zero
matching rows yields `"null"`, and the test asserts that is correct. Real
Pure raises size 0.

Companion: `GraphEmission.java:2781` wraps a to-one nav leaf in
`TypedLimit(proj, 1)`, commented *"LIMIT 1 (pure toOne semantics)"*. That
is backwards — Pure `toOne` raises on 2 rows — and the `LIMIT 1`
additionally **suppresses the backend's own** "more than one row
returned by a subquery" error, which would otherwise have caught it for
free.

## 8. The evidence base

`docs/RELATIONAL_CORPUS.md`'s "2332 passing of 2575" is **exact — zero
drift**, independently re-derived from a fresh 269 s run (and the runner
rewrote the file byte-identically — real determinism). That is honest
reporting and deserves saying.

Three numbers, all measured live at HEAD with the suite's own
instruments, none taken from a doc:

- **393 of 2434 corpus tests (16.1%) pass only because row order is
  discarded.** Measured with `LL_ORD_COUNT=1`: 484 leniency sites (358 in
  `GridCompare:209`, 126 in `H2Verify:490`) across 393 distinct tests. The
  gate (`EngineTestExecutor.java:2925`) is `ordered && actual.sortedChain()`
  — defensible, since an unsorted Pure chain has no defined SQL row order,
  but `sortedChain()` is a fact about the Pure chain rather than the
  emitted SQL, and the reference's own expectations *are* order-sensitive.
  Blinds: wrong/dropped `ORDER BY`, join-order-dependent sequence, window
  frame ordering.
- **925 of 2332 passes (39.7%) carry a softness flag**, and **299 of 299**
  advisory-SQL diffs — the ceiling has **zero slack**, having walked
  246→299.
- **0 tests assert that violating relational data is detected**, while
  ~25 assert that it is not (§7b).

Numeric tolerance, by contrast, is **small and well-guarded — credit**:
38 sites / 28 tests (1.2%), of which 7 are irreducible transcendental ULP
divergence and 31 are a float-to-text rendering gap already scheduled for
shrink. The cross-kind collapse (`'007'=='7'`) is deleted; integral values
compare exactly, after an audit caught a blanket `MathContext(10)` making
two epoch-millis compare equal.

Two structural weaknesses in the gates themselves:

- **`CorpusSoftCeilingTest` is vacuous in CI.** It runs inside
  `mvn -pl core test`, but it reads `docs/RELATIONAL_CORPUS.md` — a
  *committed markdown file* — and regexes it against four constants. The
  corpus does not run in CI, so this can never go red on a real
  regression; it binds only through the human commit loop.
- **`ChannelB` silently drops 20 platform source files.**
  `ChannelB.java:88-135` drops any file that fails the model build and
  recompiles, up to 200 rounds. Live: `walls=20`, of which **eight are
  legend-lite parse failures on the reference's own Pure source**
  (`grammar/m3.pure`, `essential/lang/cast/cast.pure`, …). Three
  `essential` PCT tests vanish with them (330 on disk vs 327 discovered),
  and `walls.size()` is never asserted.

**CI honesty (`c4386547`) is real but achieved by deletion, not repair.**
The `Assumptions.assumeTrue(Corpus.available(), …)` at
`RelationalCorpusRunner.java:55` is untouched and would still skip
silently; what makes CI honest is that the class is named `*Runner`, so
surefire never matches it. `gate.yml` now runs `-pl core` only and prints
an explicit `::warning::` that corpus and PCT are not run. The standing
gate is the hand-run `tools/allgates.sh`, whose gate 7 tolerates up to
**22 errors** (`G7_MAX_ERR=22`).

Of the 243 non-passing: **173 are honest refusals** (named, loud), 40 of
the 70 FAILs are text-only golden-SQL diffs with no wrong data, and the
true silent-wrong-data surface is **~23–27 of 2575, about 1%**.

The softness is in the PASS column:

- **2332 PASS = 1407 clean + 925 carrying softness** (247 sqldiff, 293
  advisory, 27 zero-assertion, 613 text-rescued).
- Only **320 of 952 (33.6%)** checkable golden-SQL assertions produced
  byte-identical SQL to the reference. The other 632 passed by replaying
  *our own* SQL on H2 and matching rows — self-consistency, not reference
  conformance.
- **293 tests carry golden-SQL assertions never checked at all.**

And a denominator chosen by the compiler: **`ChannelBRelationTest`
reports "287/287 = 100%"; the reference scope is 348.** The missing 61
vanish because `over.pure` fails legend-lite's *model build*
(`Unknown type: '?'`) and `pctQualifiers.pure` fails to parse, and test
discovery walks successfully-built model elements. Then
`ChannelBRelationTest.java:65` pins `assertTrue(out.size() == 287)`,
making the loss permanent instead of visible. Channel A runs all 348.

Native capability, by synthesising a call per declared signature:
**374 of 501 probed work (74.7%), 21% refuse loudly, 0.8% crash.**
`boolean 62/63`, `math 96/110`, `date 60/109`, `collection 54/83`;
`relation 0/99`, `graphFetch 0/13`, `tds 0/11`, `mapping 0/12` (mostly
unprobeable rather than broken).

---

# PART III — the pattern, and what to do

## The mechanism: relocation outruns the guards

Both halves of this audit converge on one mechanism, and it is not
sloppiness.

Every rule in this repo is enforced at a **spelling** — an import, a
bytecode edge, a regex, a file path. Work that moves keeps its meaning
but loses its spelling, and the guard silently stops covering it:

- The **comparison policy** moved from `harness/` — where two *exact-pin*
  discipline tests police every sort site with written justifications —
  into `core/src/main/java/com/legend/exec/GridCompare.java`, which
  neither test scans, and whose hand-rolled pool-matching loops contain no
  `.sorted(` spelling for the regex to find. `GridCompare`'s own header
  says it: *"moved from the harness … the policies survive, the private
  copies die."* The repo's two best guards no longer cover the code that
  decides equality.
- The **multiplicity guard** moved the other way — from a real
  `CheckedOne` SQL error into a `TRUST_ONE` identity wrap that silences
  the checker and emits nothing (§7b).
- **Cross-layer dependencies** moved into `static final` constants, which
  javac inlines out of the bytecode ArchUnit reads (§7).
- **Mode flags** moved into `ThreadLocal`s, which cross every layer wall
  with no import and pass `noStaticMutableState` because its regex is
  `static (?!final )` (§7).

Nobody decided any of this. Each individual move was locally reasonable.
The aggregate is that the enforcement and the thing enforced drifted
apart.

## The recurring failure mode is not hacking

The code is unusually disciplined. `@SuppressWarnings` appears **4 times
in 522 files**. `deadPrivateMethodsOnlyShrink` is pinned at **0**.
`GuardCoverage.assertFloor` makes every scope-walking guard assert how
many files it scanned — a second-order fix for guards whose *scope* rots,
which is rare. `JavaEvalLedgerTest` is shrink-only on two axes
(comment-stripped LOC *and* exact name counts) specifically so deleted
comments cannot fund new Java evaluation. Invariant 7 has real teeth with
zero exceptions. The lowering wall-to-silence ratio is about **7:1**,
with zero `catch { fallback }` in `Lowerer`/`Render`/`Fold`.

The failure mode is that **the guard which would stop the shortcut coming
back gets chartered in prose and then not built**, while the prose reads
as though it were done:

| chartered | status |
|---|---|
| `VerdictWorld2ConsistencyTest` — "the guard that keeps it fixed" (`HOST_LOGIC_AUDIT_2026_08_20.md:173-180`) | **does not exist** |
| compile-through equality (fix-queue item 3) — would fix `1 == 1.0` | not built |
| grid compile-through (item 4) | not built |
| tolerance/order-leniency census (item 5) | not published |
| `conformToOne` partition leg | "RETIRED UNBUILT" |
| union arm-factory leg | never built |
| H2 `RAISE_ERROR` mechanism the docs describe | no such source |
| "89 recognizer sites need auditing" | cancelled |

That is why docs and code drift, and why "census zero" kept meaning less
than it sounded like. Findings §5 and §6 would both have been caught on
day one by the one test that was specified and never written.

## Recommended order

1. **Build `VerdictWorld2ConsistencyTest`, and widen its scope from the
   verdict arms to the egress arms.** Every surviving host-side semantic
   arm gets a paired probe that runs the same computation through SQL and
   asserts agreement. §5 and §6 fall out immediately.
2. **Stop choosing the `toOne` lane by SQL shape.** Decide from the typed
   node. As a stopgap, add `SqlExpr.Case` to *both* `Scalars.java:455-458`
   and `StampCensus.listShaped` — but the stopgap re-creates the coupling,
   so prefer the real fix.
3. **Move the null-drop into the lowerer** (§5) and delete the Java line.
4. **Give `TypedJoinSlot` a join kind**, honour `(INNER)`, and stop
   stamping LEFT-joined target rows `[1]` (§2).
5. **Make `cast()` check** (§4). It is the largest silent-wrong-answer
   surface outside §5.
6. **Fix string `indexOf`/`substring` base**, or refuse loudly as the
   reference's relational target does. Returning `0` for "absent" is worse
   than either.
7. **Un-pin the ChannelB relation denominator** — assert against the
   reference scope (348) and let the parser gap show as a failure.
8. **Teach the guards to see off-spelling dependencies** (§7): ban
   `static final` cross-layer constant reads, and put the `ThreadLocal`
   channels on a register.

Cheapest-first, from the evidence side:

9. **Point `HarnessDisciplineTest`'s walk at
   `core/src/main/java/com/legend/exec`** and teach its `SITE` regex the
   pool-loop spelling. One line; restores coverage of the code that
   decides equality.
10. **Give the three `[ord]` sites distinct messages** so loose-cell
    matching can be separated from row-tuple matching in the 358.
11. **Extend `SkipCensusTest`'s walk to sibling modules** — it would catch
    9 uncensused `assumeTrue(!sources.isEmpty())` sites in
    `parser-equivalence` today, the exact vacuous-green pattern
    `c4386547` was written to kill.
12. **Assert `walls.size()` in `ChannelB`** and un-pin the relation
    denominator (§8) so a parser gap shows as a failure rather than a
    smaller 100%.
13. **Make `CorpusSoftCeilingTest` assert against a live run, or stop
    running it in CI** — today it does neither honestly.

---

## Appendix — claims checked and REFUTED

Recorded so they are not re-litigated:

- The relational `toOne` no-op and the relational `joinStrings`-over-empty
  NULL are **reference-faithful**; my 2026-08-20 audit was wrong to call
  them defects.
- **`mod` on negative operands is correct** (−5 mod 2 = 1, −12 mod 5 = 3).
- **Derived-property bodies are strictly checked** at HEAD.
- **`UserCallInliner` breaks no structural invariant** across nested
  calls, α-capture, lets-in-lambdas or contravariant params — a
  four-invariant checker found zero violations.
- **Milestoning is genuinely implemented**, not approximated; the
  unsupported forms are loud.
- **Name-based rule dispatch is clean** — `signatureKey()`, no bare-name
  fallback; three hijack routes traced and all fail to reach a builtin.
- **`AUDIT_23_SPECIAL_CASING.md` items C-a/C-b/C-c are all fixed** and now
  loud.
- **AGENTS.md understates its own coverage**: it claims the two
  lazy-loading guards "died with the engine module" and that core has
  none. Both live in `core/src/test/java/com/legend/architecture/`.
