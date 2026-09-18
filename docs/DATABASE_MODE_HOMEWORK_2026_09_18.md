# Database mode (judging step 3) — the homework before any edit (2026-09-18)

Written after host mode became the only verdict of record (JUDGING_TWO_MODES step 2,
host-only 2026-09-18). Everything below is either MEASURED (a number with its method), READ
(a source or a design doc, cited), or a DECISION PROPOSED for ratification. Nothing here is
a guess; where a number is still owed it is listed under §7 as leg 3.0's work.

## 0. What step 3 is, in one paragraph

Today every assertion in a corpus test is judged in Java: each side of the assert runs as its
own SQL statement, the rows come back to the host, and `Equality` decides. Database mode
means the DATABASE decides: each assert becomes a SQL predicate over the canonical spelling
of its two sides, the verdict comes back as a row, and no Java compares values. The two
modes then run side by side on every lane and must agree per assertion (the differential
gate). Host mode is the reference because it is small and mirrors the engine; database mode
is the product because tenet #1 says the database executes.

## 1. What already exists (read, not assumed)

**The execution model today** (`StatementExecutor.executeStatements`, read 2026-09-18): a
test body runs statement by statement. A `let r = execute(...)` runs EAGERLY as a frame,
one round trip at the let; every later read of `$r.values` RE-EXECUTES the frame's query
(the "rows leg re-executes the frame's values exactly as the frame ran"). Ordinary lets
substitute forward into the statements after them (`SourceSubst.inlineLets`, β-substitution;
lets are non-recursive value bindings). A statement-root assert reaches `AssertVerdicts`
BEFORE inlining, so the assert library's Pure bodies never lower to SQL. Each assert side is
lowered and executed as its own statement, with the canon text appended as a column of the
SAME query (`CanonicalRenderSql.wrapWithCanon`, V11: "SELECT value, canon(value) FROM (plan)");
the wire values are fetched and `Equality` judges; the byte compare of the two canon texts is
a CENSUS since host-only.

**Round trips per assert today:** two side executions (plus the frame re-execution when a
side reads an execute handle). V7 charter §1 measured 24,529 queries / 6.5 s per full sweep
(0.26 ms each) — performance is not the motive; architecture is.

**The SQL canon that exists** (`CanonicalRenderSql`, 836 lines; leaves in `LiteralSpelling`):
per-kind canonical text (docs/CANONICAL_FORM_SPEC.md §2); scalar/collection sides through
`wrapWithCanon` (declines any plan with more than one output column); grids through
`wrapTdsCanon` (per-cell leaf joined by U+001F into a row canon); an unrefined Number root
projects one candidate column per fine kind and the verdict layer selects by the runtime
value kind; `canonicalOrder` sorts rows by canon text in the database for sameElements.

**What the byte channel DECLINES today** (`AssertVerdicts.sqlByteVerdict`, the reasons as
written in the code): `mixed-kind-collection` (computed mixed numeric collections; ceiling
pinned 0), `kind-gate` (the two sides' static kind classes differ — the engine's answer is
FALSE, not unjudged), `side-e`/`side-a` (the wrap declined: non-scalar plan shape, unclaimed
kind), `keyless-ctor-in-lambda`, `identityless-instance-wire` (F13 identity), `any-pair: enum
kind has no literal channel`, `any-pair: no literal channel`, `unrefined-number`,
`cross-kind-numeric`, `render-e`/`render-a`, `any-wire-tree` (a JSON tree in an Any cell),
`canon-exec` (the canon column errored at execution; the side re-ran bare). The corpus lane
COUNTS these (`CanonicalDivergence.sqlDeclined(reason)`, sampled rows) but PRINTS no
per-reason histogram — leg 3.0's first deliverable.

**The prior design work, and what each decided:**
- OPEN_REGISTER V12 (user design 2026-08-22): one round trip per assert — side-tagged UNION
  ALL, per-side typed value columns NULL-padded, literals inline, tunnel rung
  fused→split→bare→fold.
- OPEN_REGISTER V13 (user insight 2026-08-22): whole-function fusion — "let IS WITH"
  (materialized CTE = evaluate-once), asserts as the verdict overlay, a verdict table out,
  typed `list()` evidence columns. Hazard: eager CTE vs first-failure sequencing.
- docs/V12_FUSION_SPIKE_2026_08_28.md: nine REAL corpus tests hand-fused into one statement
  each on python-duckdb, all verdicts and polarities correct. Findings that bind step 3:
  F2 grid canon is a small extension (landed since); F3 the nine-digit temporal canon
  (`strftime … %n` over the ns carrier) is the engine's spelling — PCT pins today's; F4 JSON
  rides the byte channel by canonical EMISSION (sorted keys at compile time on the actual
  side, the golden canonicalized once at inlining) — no runtime JSON canonicalization; F5 the
  first-failure hazard is ADJUDICATED DOWN (user): one statement per body, no CASE nesting, a
  statement error re-runs that one test assert-by-assert as a DIAGNOSTIC fallback; R2-1 an
  explicit `->sort()` needs pure's TOTAL ORDER as the SQL key (kind-rank, typed value), never
  the canon text; R2-3 NULL cells = `COALESCE(cell canon, 'TDSNull')` with the golden's
  sentinel emitted verbatim; R2-4 `now()` typing needs a two-step cast in DuckDB. MATERIALIZED
  is load-bearing (DuckDB's explicit evaluate-once; a plain CTE may inline per reference).
- docs/CANONICAL_FORM_SPEC.md: the canon per kind, the claimed domain (Integer, Boolean,
  String, finite Float, Decimal, Date/DateTime all precisions, enums, lists, grids) and the
  three residue tiers (§4b: A no native type; B erased by SQL's type system but encodable —
  claimable; C not data — model equality, object identity, handles, the metamodel). **One
  row is now WRONG against the reference adopted in step 2a**: §2 says Decimal renders
  SCALE-NORMALIZED because "pure Decimal equality is numeric/scale-blind". The compiled
  engine's assert seam is scale-SENSITIVE (`getValue().equals`; equality-worlds World 1:
  `3.0D ≠ 3.00D`), and `Equality` decides so. A scale-normalized canon would make database
  mode disagree with host mode on every such pair. Amendment owed in leg 3.0 with the pair
  count measured (§7).
- docs/V7_ASSERT_VERDICT_CHARTER.md: the scope partition (data asserts ~1,880 sites
  migrate; `assertSameSQL` + `assertEquals(... sqlRemoveFormatting())` plan-text compares,
  the TDG arms and the golden-SQL replay channel STAY with the SQL-text lane); D1 one owner
  (`AssertVerdicts` constructs verdict queries; the harness only sequences); D3 explicit
  order keys; D4 `assertJsonStringsEqual` lands inside the leg.
- docs/SQLTEXT_ROW_VERDICT_CHARTER.md: a SQL-text assert's verdict is ROWS (the golden text
  replayed on the H2 mirror through the oracle SPI); its ten-digit cell canon (`H2Verify.norm`)
  is a cross-engine tolerance between two databases — NOT a host-vs-golden judge and NOT
  step 3's (JUDGING_TWO_MODES step 2 plan said so; it stands).
- docs/JUDGE_INVENTORY_2026_09_17.md: every judge site (A1–A7, B1, C1, D1–D3, E1). After
  steps 2a/2b/host-only: A3/A4/A5 are `Equality`; A1/A2 (the byte canons) are census; A6
  (rendered text) and A7 (tolerance) unchanged; C1 is `Equality.serviceJson`.

**The engine's own assert definitions** (legend-pure `platform/pure/essential/tests/*.pure`,
read 2026-09-18): `assertEquals(e, a, msg)` is `assert(equal($e, $a), $msg)`;
`assertSameElements` is `assertEquals($e->sort(), $a->sort())`; `assertEq` is
`assert(eq($e, $a))`; `assertSize`, `assertEmpty`, `assertContains`, `assertNotEquals`,
`assertIs`, `assertInstanceOf` are one-line predicates over `size`/`isEmpty`/`contains`/
`is`/`instanceOf`; `assertEqWithinTolerance` is arithmetic; `assertError` is
`<<PCT.platformOnly>>` (a control-flow native, already its own arm: `AssertErrorNative`);
`assertJsonStringsEqual` is engine-core `corefunctions/testExtension.pure:38`. So every data
assert IS `assert(<boolean native over the two sides>)` — the SQL natives to write are
`equal`, `eq`, `sort`, `size`, `contains`, `isEmpty`, `is`, `instanceOf`, the tolerance
arithmetic, and the JSON equality — over the canonical spellings, plus the engine's failure
message (`\nexpected: %r\nactual: %r` is `toRepresentation`, which the canon already spells).

## 2. The corpus, measured (2026-09-18; method: a Python scan of every `<<test.Test>>`
## function body under the corpus root `core_relational/relational`, 552 files)

| what | count |
|---|---|
| test functions in the root (the lane discovers 2,613 after its filters) | 2,767 |
| with at least one `let` / without | 2,687 / 80 |
| lets per test: 0 · 1 · 2 · 3 · 4 · 5 · 6+ | 80 · 1,391 · 567 · 206 · 127 · 170 · 226 |
| tests calling `execute` | 2,103 |
| tests that re-bind a let name | 5 |
| tests with `if(` in the body | 67 |
| tests with loop-shaped asserts (`forAll`/`fold`/`map` over an assert) | 13 |

Let right-hand sides (7,085 lets): `execute(...)` 2,339 · plain function call 1,014 · other
729 · literal 598 · lambda 513 · `$var` chain 508 · `^instance` 228 · `[list]` 145 ·
`Class.all()->` 11.

Assert families (call sites): assertEquals 3,191 · assertSameElements 744 · assertSize 727 ·
assertSameSQL 487 · assertEqualsH2Compatible 380 · assertJsonStringsEqual 188 · assert 75 ·
assertTestData 61 · assertSqlEquals 45 · assertConversion 36 · assertFalse 25 ·
assertEqWithinTolerance 22 · assertEmpty 22 · assertContains 17 · assertNotEmpty 16 ·
assertSchemaRoundTripEquality 8 · assertEq 6 · assertIs 4 · assertTdsEquivalent 2 ·
assertInstanceOf 2 · assertRoundTrip 1.

**What the numbers say about "let as CTE".** The phrase suggests 7,085 CTEs. It is not
that. A lambda let (513) is a function value and inlines; a literal let (598), an
instance let (228), a list let (145) and most `$var` chains (508) are values that
β-substitute today and keep doing so — there is nothing to materialize. The CTE population
is the RELATION-valued lets: the 2,339 `execute(...)` frames (today already materialized as a
frame and re-executed per read) plus the relation-shaped part of the 1,014 function-call
lets and the 11 `Class.all()` chains. So "let IS WITH" is: the execute frame becomes a
`WITH r AS MATERIALIZED (...)` and each `$r.values` read becomes a reference to `r` instead
of a re-execution. That is a real leg, not a huge one, and it comes AFTER the per-assert
verdict statement exists (§4), because fusion is the composition of statements that already
work.

The 67 `if(` tests and 13 loop-shaped asserts are the bodies a single statement cannot
express directly; they are named here so they fail as UNJUDGED in database mode by design
until a leg claims them, never silently host-judged.

## 2b. The single-shot homework that already exists (found 2026-09-18, in order)

The question "did we do our own homework on single-shot asserts" has a long answer: yes,
four times, and the rounds disagree on one point that this section settles by measurement.

1. **docs/SESSION_HANDOFF_2026_09_02.md §0 Phase 1b (user design, 2026-09-06):** "a test =
   WITH seeds (relation values for the table refs, bound in the IR) + one verdict SELECT
   (asserts as boolean columns); retires the splice, the frame route and per-assert verdict
   queries; real DDL goldens, executeInDb result reads and mid-body reseeds are the exceptions
   (extra shots)." This is the SEEDS-IN-THE-SHOT design: every table reference becomes a VALUES
   relation inside the statement, no seeded session.
2. **docs/TWO_DESIGN_LEGS_2026_09_07.md §2 (research, no execution):** 77.6% of tests
   (1,998 / 2,575) are already one statement after inlining; the setup wrappers are 28 names in
   7 shapes, 6 of which unroll; lets: zero rebinds, 60% CTE-able, 31% erased by substitution,
   262 lets in ~300 tests are host objects (plan handles, engine text); blockers ranked
   (engine TEXT 159 and plan handles 156 are INHERENT — database-ADJUDICATED not
   database-COMPUTED, the ledger must say which); the ~700-test pilot; 30 probes (P-01…P-30);
   risks ranked with the NULL verdict first. It argued the SEEDING BOUNDARY AT THE SESSION
   from statement counts (26,425 setup statements, "seeds amortize 25:1") and from "DuckDB has
   no data-modifying CTE" — the latter is about INSERT-as-CTE, which nobody proposed; the
   former counted statements, not data. Neither was a timing.
3. **docs/REFEREE_IN_DATABASE_DESIGN_2026_09_07.md + docs/parked/InDbVerdict.java:** the
   SQL-text lane's golden rows transferred into the session as a typed table and judged by a
   two-way `EXCEPT ALL` count — the referee leg, a different thing from the assert verdict
   (TWO_DESIGN_LEGS §6 records the conflation). Its H2 finding stands: H2 has no `EXCEPT ALL`.
4. **docs/V12_FUSION_SPIKE_2026_08_28.md** (§1 above): nine real tests fused by hand, all
   verdicts correct; MATERIALIZED; the split rung; JSON by emission.
5. **docs/END_TO_END_PLAN_2026_09_08.md §6/6b/6f:** the order (referee-in-database, then
   single-shot), "single-shot's acceptance is host judged ZERO", and the definition of done.

**The fixtures, measured (2026-09-18, the same scanner over the corpus root):** 85 functions
insert data; 1,632 INSERT statements carry **1,638 rows in total** across 277 distinct tables;
the largest fixture is `milestoning::initDatabase` at 179 rows, then 126, 123, 103, 95; 51 of
the 85 fixtures are ≤10 rows, 26 are ≤50, 8 are ≤200, none larger. (CSV-literal fixtures —
`setupTestData`/`loadAndTestExecution`, 78 tests — are not in this count; they are small
too but unmeasured.) So the whole corpus's seed DATA is about the size of one modest table.

**The timing, measured (python-duckdb 1.4.4, same engine version as the JDBC pin; 1,000
statements each, warm; a two-table join + filter + aggregate):**

| fixture in the shot | seeded tables | every table as an inline VALUES CTE | extra per shot |
|---|---|---|---|
| 1 table × 10 rows (0.3 KB) | 0.134 ms | 0.378 ms | +0.24 ms |
| 3 × 30 rows (2.5 KB) | 0.258 ms | 1.666 ms | +1.4 ms |
| 5 × 60 rows (7.9 KB) | 0.266 ms | 4.775 ms | +4.5 ms |
| 5 × 180 rows (23.8 KB) | 0.276 ms | 13.965 ms | +13.7 ms |
| 10 × 180 rows (47.5 KB) | 0.275 ms | 29.137 ms | +28.9 ms |
| 277 tables × 6 rows, whole corpus fixture inline (53 KB), query touches 2 | — | 206 ms | +206 ms |

The cost is parse + bind of the VALUES text, about 0.6 ms per KB, and it is paid on EVERY
statement; the seeded query is flat at ~0.27 ms whatever the fixture size. What it means at
corpus scale (2,613 tests, DuckDB lane ≈ 57 s standalone / ≈ 100 s in the chain today):
- seeds-in-the-shot with ONLY the tables the test touches (typically 1–5 tables, ≤60 rows):
  +1 to +5 ms per test ≈ +3–13 s per lane — noticeable, not fatal;
- seeds-in-the-shot with the package's whole fixture regardless of use: +14–29 ms per test
  ≈ +40–75 s per lane — that doubles the lane;
- the whole corpus fixture in every shot: +206 ms per test ≈ +9 minutes — that kills it.

**Decision proposed, D9 — the seeding boundary stays at the SESSION for the corpus lane;
seeds-in-the-shot is a property of the STATEMENT the product can emit, not of the harness's
run.** The database-mode verdict statement must reference tables by name exactly as the
product's query does; nothing in the verdict design depends on where the rows came from. A
test that NEEDS its own rows (the ~10 DDL-in-body tests, the mid-body reseeds) is an extra
shot, as Phase 1b already said. The self-contained "one statement carries its own data" form
is kept as a DIAGNOSTIC and PORTABLE artifact (a failing test reproduced as one pasteable
statement) and as the natural form for the stress corpus's `###Data` fixtures (per-suite data
that is already per-test by construction and small) — measured separately before adoption
there. Rejected: seeds-in-the-shot as the corpus lane's default (the numbers above);
rejected: the 25:1 statement-count argument as the reason (it was not a measurement of the
thing that costs, which is text size per statement).

**Three probes settled on the way (TWO_DESIGN_LEGS §4):** P-19 — `(1=1) AND (NULL=1)` returns
NULL and the driver delivers `None`, not false: every verdict column MUST be written with
`IS NOT DISTINCT FROM` / `COALESCE(…, false)` so a verdict is never three-valued (the
document's highest-ranked risk, confirmed). P-01 — DuckDB `EXCEPT ALL` keeps multiplicity.
P-08 — a data-modifying CTE is rejected: "Not implemented Error: A CTE needs a SELECT" (the
exact text for the capability wall). P-11 — a plain CTE referenced twice returned the same
`random()` value in one probe; NOT proof of evaluate-once (one call, one engine build) —
MATERIALIZED stays the rule.

## 3. The decisions proposed (each with the alternative rejected and why)

**D1 — the unit of a database-mode verdict is ONE STATEMENT PER ASSERT first, one per
test body second.** Stage A keeps today's statement sequencing and replaces the two side
executions plus the Java compare with one SELECT that computes both sides and the verdict
(`SELECT verdict, evidence…`). Stage B (V13) composes those into one statement per body
with the relation lets as MATERIALIZED CTEs. Rejected: starting at V13 — it changes the
executor and the verdict at once, so a red row could not be attributed to either.

**D2 — the verdict is a SQL predicate over CANONICAL SPELLINGS, not over raw values.**
The canon already exists per kind and carries kind in its bytes (six disjoint spellings); a
raw-value compare would re-import SQL's type promotion (1 = 1.0 is TRUE in SQL and FALSE in
pure). Per family: `equal` over scalars = `canon(e) IS NOT DISTINCT FROM canon(a)`; over
collections = `list(canon ORDER BY key) = list(...)` with an explicit row-number key for
ordered asserts and the canon text as the key for sameElements (R2-1: an explicit `->sort()`
uses pure's total order); grids = row canons; `size`/`isEmpty`/`contains` are their SQL
counterparts over the canon column; `eq` over primitives = `equal`, over instances =
UNJUDGED (Tier C); the tolerance assert = `abs(e - a) <= tol` in the database (it is
arithmetic; A7's "own arithmetic" moves into SQL). Rejected: a Java predicate over fetched
rows — that is host mode.

**D3 — UNJUDGED is a failure with a name, in every mode.** A shape the canon cannot spell
(a JSON tree in an Any cell, an identityless instance, a metamodel value, an `if(` body)
FAILS with the shape named; the per-mode unjudged lists are ceilings in the register that
only shrink. Rejected: falling back to host mode for that assert — that is the mixed verdict
step 2 just deleted.

**D4 — the 2-ULP leniency is a SQL predicate, counted.** `abs(e - a) <= 2 * ulp(max(|e|,
|a|))` with ulp computed as `2^(floor(log2(x)) - 52)` for finite doubles; fires only for
Double×Double; the count reported per run as today (`Equality.ulpFirings` in host mode).
Rejected: retiring the leniency first — its witness set is the libm rows (F-AE), CI-proven.

**D5 — Decimal canon becomes scale-PRESERVING** (CANONICAL_FORM_SPEC §2 amendment), the
compiled reference's rule that host mode already applies. The Integer×Decimal row
(`8` vs `8.0D`) follows the same reference: different kinds, not equal.

**D6 — JSON asserts ride the canon by emission** (spike F4, charter D4): the actual side's
serializer emits a second, key-sorted build at compile time; the expected literal is
canonicalized once at inlining; the compare is bytes. `serviceJson` (EqualToJson, the stress
runner) is the same native with the engine's null≡missing and unordered-root rules —
sequenced last (its own leg; the nested-document multiset is the hard part).

**D7 — the differential gate records per-assertion verdicts keyed by test FQN and
statement index** (the `AssertListener` already observes every statement-root verdict), one
file per mode per lane, compared by a gate that pins 0 disagreements and per-mode unjudged
ceilings. Rejected: comparing pass/fail per TEST — a test with two asserts can agree on the
outcome while the modes disagree on which assert failed.

**D8 — the SQL-text lane is out of step 3** (charter §2, restated): assertSameSQL 487 +
assertEqualsH2Compatible 380 + assertSqlEquals 45 ≈ 900 sites judge ROWS through the replay
oracle already; plan-text likewise. Step 3 claims the ~1,880 DATA-assert sites.

## 4. The legs, in order, each named by what it turns and what it may not change

**Leg 3.0 — measure, amend, no verdict change.** (a) The corpus lane prints the byte
channel's CLAIM/DECLINE census per assert family and per decline reason, both backends
(`CanonicalDivergence` already samples the rows; the print is missing). (b) The count of
Decimal pairs whose scale differs (the D5 amendment's witness set). (c) The H2 +2 rows
(`mapping::boolean::testProject`, `filter::in::testInWithDynaFunction`): what the H2 grid
canon text says versus DuckDB's for equal values — the first database-mode bug, named
before the mode exists. (d) The spec amendment (D5) and this doc's §3 ratified.

**Leg 3.1 — the verdict statement for the claimed domain.** `assertEquals`,
`assertSameElements`, `assertEq` (primitives), `assertSize`, `assertEmpty`/`NotEmpty`,
`assertContains`, `assertNotEquals` over scalars, collections and grids of the claimed kinds:
ONE statement per assert, verdict row out; `-Dlegend.judge.mode=database` selectable; a
decline is UNJUDGED (fails). Judge: the DuckDB corpus lane in database mode; the rows it
turns are exactly leg 3.0's "claimed" set; every other row is on the unjudged list, by
reason. Host mode unchanged (its rosters are the chain's).

**Leg 3.2 — claim the declines by tier.** In this order, each a census-named batch:
cross-kind pairs (the engine's FALSE — a verdict, not a decline); unrefined Number and enum
sides through the literal channel; the tolerance assert; keyed instances by key tree (F13's
canon); the JSON natives (D6, 188 sites); the `if(`/loop bodies last or walled by ruling.

**Leg 3.3 — the differential gate as a chain lane.** Both modes on both corpus lanes and
the stress lane, per-assertion agreement pinned 0, unjudged ceilings per mode; cost measured
against the 4-minute parallel budget (an extra DuckDB corpus run ≈ 100 s in its own stream).

**Leg 3.4 — whole-function fusion (V13).** Relation lets as `WITH … AS MATERIALIZED`, the
asserts as verdict columns of one statement per body, the split rung as the diagnostic
fallback on statement error. Witnesses: the nine spike tests first (they have hand-written
goldens), then the lane. This leg deletes the frame re-execution.

**Leg 3.5 — deletions.** The `sql*` census counters and the byte-verdict plumbing that
database mode replaces; the `finish()` census probe; whatever `CanonicalDivergence` rows
the differential gate makes redundant. (The ten-digit replay canon stays: it is the SQL-text
lane's.)

## 4a. Leg 3.0 — the measurements (2026-09-18; no verdict changed)

**The claim/decline census** (`[corpus2] sql-census …`, printed by both corpus lanes from
`CanonicalDivergence`; `adjudicated` = asserts that reached `AssertVerdicts`, `claimed` = a
byte verdict was produced, `declined` = the canon refused with a reason; the remainder never
attempted a byte verdict — the family has no SQL arm, or the shape routed to an arm without
one):

| family | DuckDB adjudicated / claimed / declined / not attempted | H2 adjudicated / claimed / declined / not attempted |
|---|---|---|
| assertEquals | 2,950 / 1,497 / 174 / **1,279** | 2,761 / 1,164 / 319 / 1,278 |
| assertSameElements | 755 / 686 / 50 / 19 | 730 / 627 / 65 / 38 |
| assertEq | 6 / 6 / 0 / 0 | 6 / 2 / 3 / 1 |
| assertSize | 684 / 0 / 0 / 684 | 675 / — / — / 675 |
| assert (boolean) | 362 / 0 / 0 / 362 | 362 |
| assertJsonStringsEqual | 177 / 0 / 0 / 177 | 172 |
| assertEmpty / NotEmpty / Contains / False / Is / InstanceOf / EqWithinTolerance / TdsEquivalent | 20 / 13 / 17 / 20 / 4 / 1 / 11 / 2 — none attempted | similar |

Decline reasons, DuckDB: assertEquals `tds-peer` 90 · `tds-side` 26 · `side-e` 24 ·
`any-pair` 14 · `kind-gate` 11 · `side-a` 5 · `any-wire-tree` 4; assertSameElements
`side-e` 39 · `tds-peer` 4 · `tds-side` 3 · `side-a` 2 · `any-wire-tree` 2. H2 adds
`side-a` 111 and `tds-side` 63 on assertEquals (the H2 canon wrap declines more shapes) and
`side-e` 51 on assertSameElements.

**What the numbers say.** The SQL canon already produces a verdict for 2,189 of the 3,711
assertEquals/SameElements/Eq asserts on DuckDB (59%), declines 224 with a named reason, and
NEVER TRIES 1,298 — the biggest bucket, and it has no reason column yet because nothing
recorded one. Every family other than those three has no SQL arm at all (assertSize 684,
assert 362, JSON 177, the small tails). So leg 3.1's real work list, in order of size: the
1,298 un-attempted equals/sameElements shapes (a reason census first — the next print), the
1,223 family-level gaps (size/assert/JSON/…: each a one-line predicate, JSON the exception),
then the 224 named declines by tier (§3 D3). Policies: the 2-ULP leniency fired 7 times on
DuckDB, 0 on H2; the TDSNull policy 0; **Decimal scale-only pairs 0 on both lanes** (the
D5 amendment has no corpus witness; it is now in the spec, docs/CANONICAL_FORM_SPEC.md §2/§3).

**The disagreements = database mode's first bug list** (host is the verdict of record; a
byte-vs-host disagreement is a wrong SQL canon). DuckDB: 0. H2: 6, all in the grid canon,
two causes: (1) H2 spells a Boolean cell `FALSE` where the canon expects `false` — the H2
dialect's boolean canon is unnormalized (2 rows: `mapping::boolean::testProject` and
`filter::in::testInWithDynaFunction`, the two the H2 roster lost when host became the
verdict); (2) a grid cell under a String-DECLARED column over an INT wire is spelled
QUOTED (`'11'`) while the expected literal `11` spells bare — the SQL grid canon follows the
Pure declaration where the engine (and `Equality.effectiveKind`) follows the wire kind for a
non-numeric declaration (4 rows, the `mapping::tree` family, `Account.number : String[1]`
over `accountTable.id INT`). Both are leg 3.1 items with their rows named here.

**The multiset formulation — settled for BOTH dialects with ONE spelling.** H2 2.4.240
rejects `EXCEPT ALL` (probe P-02: "Syntax error … except [*]all"), rejects `AS MATERIALIZED`,
and rejects a data-modifying CTE (P-09). The signed-counts form needs none of them:

```sql
SELECT count(*) = 0 FROM (
  SELECT v FROM (SELECT v, count(*) n FROM e GROUP BY v
                 UNION ALL SELECT v, -count(*) FROM a GROUP BY v) u
  GROUP BY v HAVING sum(n) <> 0) d
```

Probed on both engines over multisets with duplicates and NULLs (GROUP BY treats NULL as
one value, so the form is NULL-safe without IS NOT DISTINCT FROM): equal → 0, one element
moved → 2, one side empty → 3, identical answers on H2 and DuckDB; DuckDB's two-way
`EXCEPT ALL` agrees. So the sameElements verdict is one SQL shape, not one per dialect —
the risk TWO_DESIGN_LEGS §5 ranked fourth is retired. Ordering (P-22): on both engines an
`ORDER BY` inside a CTE does survive into `list()`/`ARRAY_AGG` in the probe, but the
verdict must not rely on it — `list(v ORDER BY key)` is explicit on both and costs nothing.
P-12 (a CTE reading an earlier CTE) holds on both. P-11 stands as recorded in §2b.

**Owed from this leg (small, before 3.1's first edit):** the reason census for the
un-attempted 1,298 (a `not-attempted <family> <route>` row at each host-only route), the
round-trip count per lane, and the H2 MATERIALIZED substitute (a probe: does H2 evaluate a
plain CTE once when referenced twice? If not, the H2 fusion form uses a temporary view or
runs the frame CTE as a subquery per reference and the differential gate catches drift).

## 5. Traps recorded now (so they are not rediscovered)

- MATERIALIZED is load-bearing; a plain CTE can inline per reference and two asserts could
  see different rows of a nondeterministic result (spike, rung choice).
- The H2 dialect has no `MATERIALIZED`; the H2 lane's fusion needs its own evaluate-once
  rule (a probe, not an assumption).
- A canon column that ERRORS at execution poisons the side (the `canon-exec` decline
  tunnel exists for this) — in database mode there is no bare re-run to fall back to; the
  canon must not error, so every claimed kind needs the wrap proven on the H2 mirror too.
- `now()` and temporal precision: the nine-digit spelling is the engine's; PCT pins today's.
  Moving the convention is one slice across host canon, SQL canon and the PCT pins together.
- `assertSameElements` over an unrefined-Number side has no single ordering key (multi-
  candidate) — the literal channel's kind-tagged spelling is the key.
- Frames re-execute today; a verdict statement that references the frame twice (both sides
  read `$r`) must reference ONE evaluation (a CTE), or a nondeterministic order breaks
  positional asserts.

## 6. What a strong Legend developer would ask, answered

*Why not lower the assert library's Pure bodies and let the ordinary pipeline do it?*
Because `assert(equal(e, a))` lowers `equal` as SQL `=`, which is SQL equality (promotion,
NULL semantics), not pure equality; the natives must be written against the canon
(Clause 2c: "pre-inline so the assert library's pure bodies never β-inline into SQL").

*Why keep host mode at all once database mode is complete?* Because the differential gate
needs an independent reference that is small and readable against the engine's Java; a
single judge cannot check itself.

*Why is fusion last?* Because it is composition: once each assert is one statement over
canon spellings, the body is those statements joined under shared CTEs. Doing it first would
mean designing the verdict inside the fusion.

## 7. Owed before leg 3.1 (leg 3.0's outputs)

1. Claim/decline census per family and reason, both backends (the unjudged list of record).
2. Decimal scale-differing pair count (D5 witness).
3. The H2 +2 grid-canon text difference, read.
4. Per-assert round trips today from the trace (`ExecutionTrace`), to set the leg 3.1 and
   3.4 before/after numbers.
5. Ratification of §3 D1–D8 and §2b D9 (the seeding boundary).
6. The CSV-literal fixtures' row count (the 78 `setupTestData`/`loadAndTestExecution`
   tests) and the stress corpus's `###Data` sizes, before the self-contained form is
   adopted anywhere.
