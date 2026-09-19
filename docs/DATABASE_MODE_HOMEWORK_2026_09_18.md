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
asserts as ONE ROW EACH of one statement per body (user question 2026-09-18, decided: rows,
not columns — a fixed schema `assert_ix, family, verdict, expected, actual, unjudged`, a
`UNION ALL` of one SELECT per assert over the shared CTEs, keyed by the assert's position in
the body, the same key the differential gate uses; every failing assert reported, not the
first; leg 3.1a's per-assert SELECT is exactly one of those rows), the split rung as the
diagnostic fallback on statement error. Witnesses: the nine spike tests first (they have hand-written
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

**The un-attempted bucket, explained (same day, `not-attempted <family> <route>` rows at
the three routes that have no byte channel).** DuckDB: assertEquals `sql-text` 991 ·
`rendered-text` 253 · unaccounted 35; assertSameElements `rendered-text` 19 (all of its 19).
H2: 929 · 246 · unaccounted 103; 13. So the "never attempted" mass is the SQL-TEXT lane
(a `toSQLString`/plan producer in an argument: the verdict is ROWS through the replay oracle
— OUT of step 3 by D8, exactly as the V7 charter partitioned it) plus the RENDERED-TEXT arm
(exactly one side is a database-rendered grid text — toCSV / toString / join spellings — and
the peer a string literal: the database already computed the text; the compare is a string
equality, a one-line SQL predicate — leg 3.1's easiest 272 rows). The `grid-pair` route
fired 0 times on both lanes (relation-stamped pairs reach the flat-cells verdict instead).
The 35 / 103 unaccounted are the routes that raise before any channel (a side that errors)
or the class-value / string-entry inlined roots — small, to be named when 3.1 reaches them.

**Evaluate-once, measured (same day, 20 runs each, a CTE holding `RAND()`/`random()` cells
referenced twice in one statement).** H2 2.4.240: a plain CTE is re-evaluated PER REFERENCE
— 20 of 20 runs saw different values on the two references, for a scalar CTE, a 3-row VALUES
CTE and a CTE over a table alike; H2 has no MATERIALIZED. DuckDB 1.4.4: 20 of 20 the SAME on
all three shapes, with or without MATERIALIZED (so P-11's one-run result was right for DuckDB;
MATERIALIZED stays the emitted keyword because it is the documented guarantee, not because the
probe needed it). Consequence for leg 3.4 on H2: a frame read twice inside one statement is
two executions; a verdict over a nondeterministically ordered frame must reference it ONCE (one
CTE read, the sides derived from that read) or the H2 lane materializes the frame as a
temporary table before the verdict statement. Named now, decided at 3.4.

**Round trips, measured (a counter at the executor's two JDBC entries — prepared queries and
raw statements — printed by the lanes):** DuckDB lane 142,580 statements for 2,613 tests;
H2 lane 138,734. About 54 statements per test, most of them the session's seeds replayed per
package plus every assert side and frame re-execution. Leg 3.1 (one statement per assert
instead of one per side) and 3.4 (one per test body, no frame re-execution) each get their
before/after from this line.

**The census RECONCILES (final, after four corrections on the same day — each a real
counting fault, recorded so the next census does not repeat them).** The faults: (1) a
decline is an EVENT, and a grid pair records one per side — `declined-asserts <family>`
counts an assert once, the reason rows stay event counts (DuckDB equals: 174 events, 165
asserts; H2: 319 events, 256 asserts — the H2 `tds-side` 63 were exactly the double
records); (2) an assert can leave through ANY exception, not only AssertFailed/DataError —
counted in a `finally` (`raised`), never caught; (3) a nested adjudication's raise passes
through two entries — one raise; (4) a raise BEFORE the family arm is reached (the lineage,
quantified, if-branch and SQL-text root arms run first) is its own row, `(pre-arm) raised`,
not the previous assert's family. With those, for the three families the SQL canon serves,
claimed + declined-asserts + not-attempted = adjudicated EXACTLY on both lanes:

| family | lane | adjudicated | claimed | declined | not attempted (sql-text · rendered-text · raised) |
|---|---|---|---|---|---|
| assertEquals | DuckDB | 2,950 | 1,497 | 165 | 1,288 (991 · 253 · 44) |
| assertEquals | H2 | 2,761 | 1,164 | 256 | 1,341 (929 · 246 · 166) |
| assertSameElements | DuckDB | 755 | 686 | 50 | 19 (0 · 19 · 0) |
| assertSameElements | H2 | 730 | 627 | 60 | 43 (0 · 13 · 30) |
| assertEq | DuckDB / H2 | 6 / 6 | 6 / 2 | 0 / 3 | 0 / 1 |

Every other family's remainder IS the family-level gap (no SQL arm at all): assertSize
684 / 675, assert 362 / 362 (15 / 18 raised), assertJsonStringsEqual 177 / 172 (4 / 28
raised), assertEmpty 20, assertFalse 20, assertContains 17, assertNotEmpty 13,
assertEqWithinTolerance 11, assertIs 4, assertTdsEquivalent 2, assertInstanceOf 1. Pre-arm
raises: 5 on DuckDB, 55 on H2 (the H2 walls before an arm is reached). The `raised`
rows are the assert sides that hit a wall or errored — 44 equals on DuckDB, 166 on H2 — a
list database mode inherits unchanged (a side that cannot execute is unjudged in every
mode) and leg 3.2 names by reason.

**Every item leg 3.0 owed is answered above** (census, Decimal pairs, the H2 rows, round
trips, the H2 evaluate-once probe, the multiset spelling); item 6 (CSV-literal and stress
`###Data` fixture sizes) stays parked WITH its reason — it matters only if the self-contained
statement form is adopted, and D9 keeps the corpus lane's seeds at the session. (a probe: does H2 evaluate a
plain CTE once when referenced twice? If not, the H2 fusion form uses a temporary view or
runs the frame CTE as a subquery per reference and the differential gate catches drift).

## 4b. Leg 3.1 — the plan (written 2026-09-18, before any edit)

**What exists that the verdict statement composes from (read, not assumed).** A side is
executed by `StatementExecutor.evalValue(arg, …, rider)`; `executePlan` wraps the side's
lowered `SqlQuery` with `CanonicalRenderSql.wrapWithCanon` (scalar / collection: `SELECT
value, __canon0[, __canon1, …] FROM (plan) side [ORDER BY canon]`; one canon column per
candidate kind, the literal channel last) or `wrapTdsCanon` (grid: the per-row canon as the
last column); `Executor` runs it and harvests the canon columns into the `CanonRider`;
`AssertVerdicts.frame` then writes the spec's separators around the harvested texts in Java
(`'[]'` for empty, the bare text for one, `'[a, b]'` for many) and `finish` compares two
strings. The SQL IR already has `SqlWith`/`Cte`, `OrderedListAgg(value, orderBy)` (rendered
`list(v ORDER BY k)` on DuckDB — H2 needs its own rendering, `ARRAY_AGG(v ORDER BY k)`),
`SqlFn.TYPEOF`, and the dialects.

**The statement, one per assert (database mode):**

```sql
WITH e AS (<wrapped expected plan>), a AS (<wrapped actual plan>)
SELECT
  (<frame(e)> IS NOT DISTINCT FROM <frame(a)>) AS verdict,      -- never NULL
  <frame(e)> AS expected, <frame(a)> AS actual                    -- the evidence = the message
```

where `frame(s)` is the spec's framing IN SQL: for a scalar side `COALESCE((SELECT __canon
FROM s), '[]')`; for a collection side `CASE count WHEN 0 THEN '[]' WHEN 1 THEN the one text
ELSE '[' || array_to_string(list(__canon ORDER BY k), ', ') || ']' END`; `k` is the side's
own order (the plan's sort keys for a SORTED side; the canon text itself for sameElements
and for INCIDENTAL-order sides, which the host judge already treats as multisets); a NULL
canon cell inside a collection is UNJUDGED (the host's `null-canon-cell`). The same statement
shape serves `assertSameElements` (both sides ordered by canon text), `assertEq` over
primitives (= `assertEquals`), `assertSize` (`count(*) = n`), `assertEmpty` / `NotEmpty`,
`assertContains` (`EXISTS`), `assertEqWithinTolerance` (`abs(e - a) <= tol` over the value
columns), and the grid: the per-row canons from `wrapTdsCanon` framed the same way (row
canons are already `U+001F`-joined cells; ordered vs multiset by the same rule; the
TDSNull sentinel already spelled on the expected side at compile time). The 2-ULP leniency:
`verdict OR (both Double, finite, abs(e - a) <= 2 * ulp(max(|e|,|a|)))` as a second
column `leniency`, counted by the harness exactly as `Equality.ulpFirings` is today.

**Unrefined-Number sides** (a wrap with more than one candidate: today the verdict layer
picks the column by the FETCHED value's kind) become, in SQL, a `CASE typeof(value)` over the
candidates on DuckDB; on H2 (no `typeof`) they are UNJUDGED until the typer refines them
(NumberKinds already refines the arithmetic natives; the census says `unrefined-number`
declines are 0 today, so the residual population is the multi-candidate CLAIMED pairs —
counted first, in 3.1a).

**Sub-legs, each judged by the DuckDB corpus lane in database mode against host mode's
roster (identical pass/fail per test, or the difference is on the unjudged list by
reason), then the H2 lane:**
- **3.1a — plumbing + the scalar/collection equals family.** `JudgeMode.DATABASE`
  selectable; a `VerdictSql` builder in `lowering` composing the two wrapped plans into the
  statement above; `AssertVerdicts` in database mode: for `assertEquals` / `assertSameElements`
  / `assertEq` scalar and collection sides it executes the ONE statement and reads the verdict
  row (no side values fetched); a side the wrap declines, a multi-candidate side on H2, a
  null canon cell, a shape without a statement → `Unjudged(<reason>)`, which FAILS the test
  with the reason (the per-mode unjudged census row). Host mode untouched. Also 3.1a: the
  multi-candidate-claimed count printed.
- **3.1b — the grid verdict** (`tdsRowValuesVerdict` / `SameElements`): the two named H2
  canon bugs first (the boolean canon `FALSE`; the wire-kind rule for a non-numeric
  declaration — the canon spells by the COLUMN's SQL kind when the Pure declaration is not
  numeric, the same rule as `Equality.effectiveKind`), then the statement.
- **3.1c — the one-line families:** size, empty/notEmpty, contains, eq(primitive), assert /
  assertFalse over a boolean side, the tolerance assert.
- **3.1d — the rendered-text arm** (272 asserts: a database-rendered text vs a string
  literal): the statement is `SELECT text IS NOT DISTINCT FROM 'literal'`.
- **3.1e — the census + the switch's ceilings:** `sql-census` gains `judged-in-database
  <family>` and `unjudged <family> <reason>` rows; the register pins the database-mode
  unjudged ceilings per lane, shrink-only.

**Not in 3.1 (named):** JSON asserts (D6, leg 3.2), keyed/identity instances (3.2), the
SQL-text lane (D8, never), fusion (3.4), any deletion (3.5).

**Traps for 3.1 specifically:** `OrderedListAgg` renders `list(…)` on the ANSI base — H2
must render `ARRAY_AGG(… ORDER BY …)` and `array_to_string` its own way (a dialect item,
found before the H2 lane runs, not during); the verdict column must be built with
`IS NOT DISTINCT FROM` / `COALESCE` end to end (P-19); the statement carries both side plans
— a side that ERRORS makes the whole statement error, which is UNJUDGED with the error as
the reason (no bare re-run rescue: that was the mixed verdict's habit); framing a huge
collection into one string is the same cost as today's Java framing, just in the database.

## 4c. Leg 3.1a — LANDED 2026-09-18 (the verdict statement for scalar and collection sides)

**What exists now.** `-Dlegend.judge.mode=database` is selectable. For `assertEquals`,
`assertNotEquals`, `assertSameElements` and `assertEq` over scalar and collection sides,
`AssertVerdicts.databaseVerdict` PLANS both sides (`StatementExecutor.planValue` — the
executor's typed pipeline split into a shared prelude, the canon wrap and the run, so a side
is planned exactly as it would execute), composes them with `lowering.VerdictSql.equality`
into ONE statement (two CTEs of canon texts in arrival order, the spec's framing in SQL,
`IS NOT DISTINCT FROM`, the evidence columns, an `__unjudged` column) and reads the verdict
row. A side the pipeline answers as a HOST CONSTANT (a generated seed-data string, a
rendered DDL text, a folded literal) is bound as a VALUES relation and canon-wrapped like any
side — database-ADJUDICATED (TWO_DESIGN_LEGS blocker 1's rule); a store-free side runs on the
store-reading side's database (the system database for a metamodel read). Everything the
statement cannot decide FAILS the assert with its reason and is counted
(`sql-census unjudged <family> <reason>`); errors inside the statement are counted then
surface as themselves — never a bare re-run, never a host rescue. Host mode is untouched:
the chain's rosters did not move (DuckDB 108, H2 428, stress 4,700, PCT, Channel B).

**Judged (the DuckDB corpus lane in database mode, against host mode's roster):**

| | count |
|---|---|
| asserts judged in the database — assertEquals / assertSameElements | 1,259 / 691 |
| tests LOST against the host roster / GAINED | 34 / 0 |
| unjudged: null canon cell inside a collection (3.1b, the grid family) | 14 |
| unjudged: non-primitive kind gate (a class / generic stamp over equal instances — 3.2) | 10 |
| unjudged: keyless instance side (3.2) | 4 |
| unjudged: enum on the literal channel (3.2) | 4 |
| unjudged: statement error (a canon over a JSON-carried plain string — `'ROOT'`) | 1 |
| strength differential | 1,546 (host floor 1,543) |

Every lost test is one of those rows; none is a wrong verdict. Grid sides
(`tdsRowValuesVerdict`) still take the host path in database mode — 3.1b. The other
families (size, empty, contains, boolean asserts, tolerance, JSON, rendered text) — 3.1c/d.

**H2 in database mode (measured, not yet worked):** 1,633 asserts judged, 104 tests lost:
60 are ONE dialect gap — the literal-channel canon spells through JSON navigation and the
H2 dialect raises `DialectCapability` ("variant navigation reached a dialect without JSON
support"; host mode's canon-exec tunnel used to swallow this and re-run bare, which is why
it never showed) — counted as `dialect-capability`; 15 are `REGEXP_EXTRACT` in a canon
expression (same tunnel); the rest are the DuckDB rows. The H2 lane's database mode is a
dialect leg: the canon must DECLINE on H2 where it cannot spell, not raise at render.

**Corrections the lane forced on the builder (each a real defect, all before the chain):**
the wrapped side's own `ORDER BY` must be dropped for a canon-ordered side — inlined into a
CTE over a literal side DuckDB rejects "ORDER BY a literal" (the aggregate re-orders by the
canon text anyway); an Integer canon is the bare number until cast — the framing casts every
canon to VARCHAR; a static kind gate is the engine's FALSE only for PRIMITIVE kind classes (a
class-typed side's static class is a declaration, not the value's kind); a constant side is
bound on the counterpart's database; errors are counted then rethrown (the error-shape
guardrail: a caught failure that returns a value must be a designed sentinel — it is not,
the failure is the failure).

**Registers moved, with reasons:** AssertVerdicts 1842 → 1991 and StatementExecutor
2125 → 2216 (the database path's plumbing — no value compared in Java; leg 3.5 deletes the
host machinery); the parked-work ledger's WITH-construction anchor names `VerdictSql` beside
`SqlRewriter` (PARK-2 stays parked); the harness prints the database-mode differential and
strength instead of pinning them (the differential gate, 3.3, pins).

**Next (3.1b):** the grid verdict — `tdsRowValuesVerdict` / `SameElements` through the
statement with the row canons from `wrapTdsCanon`, the TDSNull sentinel, the H2 boolean
canon and the wire-kind rule for a non-numeric declaration (the two named H2 canon bugs).

## 4d. Leg 3.1b, part 1 — LANDED 2026-09-18 (the two H2 canon bugs, the prelude fold)

- **The H2 boolean canon.** `H2.variantAwareCast` spelled a boolean-SHAPED expression as
  `true`/`false` but a boolean-TYPED value (a column) as H2's `TRUE`/`FALSE`. It now honours
  the value's type fact too. This is a PRODUCT fix with a corpus witness beyond the canon:
  `query::view::testAllWithJoinToView` (`[$o.id, $o.zeroPnl]->makeString(',')` — `'1,FALSE'`)
  now passes on H2 (roster 428 → 427; the order-lenient register gains it, as on DuckDB).
- **The wire kind under a String declaration.** `wrapTdsCanon` spelled a cell by the
  DECLARED Pure type; the plan's output label is stamp-derived, so a `String[1]` property over
  an INT column was spelled quoted (`'11'`). It now spells a String-declared cell by the
  WIRE kind read from the projection expression's own type fact (a table column ref carries
  its DDL type; only when the projection and output lists align — a star projection does
  not, and indexing them positionally threw on three tds tests before the guard). On DuckDB
  this canon used to ERROR (`replace` over an INT) and the old tunnel declined it silently —
  DuckDB declines 224 → 216, ULP-policy claims 7 → 16.
- **Prelude fold.** `evalValue` and `planValue` share `sideBody` (the audit's one real
  duplication).
- **Judged:** host mode both lanes EXACT (DuckDB 108, H2 427); byte-vs-host disagreements
  H2 6 → 1, DuckDB 0 → 1 — the SAME row on both: `filter::in::testInWithDynaFunction`,
  expected `'4'` (a String literal), actual the INT column `interactionTable.ID` under
  `Interaction.id : String[1]`, host says EQUAL (the executor decodes the cell by its label,
  VARCHAR), the canon now spells the wire's bare `4`.

**OPEN RULING (recorded, not decided): which engine test is right?** Two engine tests
assert opposite things for the same shape — a `String[1]` property mapped to an INT column:
`mapping::tree` asserts the INTEGER `11` (`Account.number` over `accountTable.id`), the
dyna-function test asserts the STRING `'4'` (`Interaction.id` over `interactionTable.ID`).
The engine reads a cell by `ResultSetMetaData.getColumnType` (`RelationalResult.java:551`)
and its `dataTypeTransformer` is the identity for a String declaration, which favours the
Integer; but the Pure-side TDS is parsed from the engine's JSON result, and how that parse
treats a numeric JSON value under a String column is the fact not yet read. Until it is:
the wrap's wire-kind rule stays (both rosters exact, 4 of 5 disagreements gone), the one
remaining disagreement is named, and the differential gate will hold it up as the first
host-vs-database difference to rule on. A third H2 host-mode item surfaced beside it: an
expected `^TDSNull()` cell decodes as the STRING `'null'` on H2 (`expected: [11, 'null']`,
the five `mapping::tree` rows in the H2 fail roster) — an H2 lane item, named here.

## 4e. Leg 3.1b, part 2 — LANDED 2026-09-18 (the grid verdict statement)

**What exists now.** `wrapTdsCanon` appends one canon per CELL (`__cell<i>`) before the
row canon, so the cell pool needs no string splitting on any dialect (the executor's decode
reads the first width columns and harvests the row canon at 2·width + 1). `VerdictSql`
gained the grid forms: `gridRows` (the grid's row canons against the peer's cells chunked by
the grid's width — cells in ARRIVAL order grouped by `(rn−1) − ((rn−1) MOD width)` and
joined by the cell separator, the same framing `TdsCompare.peerRowCanons` writes; ordered
or as a row multiset), `gridCells` (the loose cell pool for `assertSameElements`, a
`UNION ALL` of the per-cell canons), `gridPair` (two grids' row canons). An expected
`^TDSNull()` element is rewritten to the string `'TDSNull'` at plan time and the peer rule
spells it bare on the expected side (direction-aware, as in Java). A canon carrying the
JSON-tree marker is UNJUDGED ("unclaimable tree cell"). A collection side drops its NULL
VALUE rows as the executor's value decode does. An unrefined NUMBER grid cell spells by its
wire kind like a String-declared one.

**A 3.1a hole this closed.** The equals arm skipped the database branch whenever EITHER
side was a grid (`gridPair` meant "any side tabular"), so grid asserts were silently
host-judged in database mode — the census showed them as `claimed` (the byte channel) and
nothing flagged it. Every equals / sameElements assert now routes to the statement in
database mode; the sorted flat-cells idiom routes as a cell pool.

**Defects the lane forced out of the builder (each a real one):** the sides were planned
with the canon-text sort, which scrambled a peer's cells across rows (riders plan unsorted;
the statement orders); the expected `^TDSNull()` rode the JSON carrier as a tree; the value
decode and the canon harvest had to move with the appended cell columns.

**Judged (DuckDB lane, database mode):**

| | count |
|---|---|
| asserts judged in the database — assertEquals / assertSameElements | 1,569 / 723 |
| tests LOST against the host roster (from 34 before the grids routed; 150 when they first did) | 63 |
| unjudged: null canon cell 14 · non-primitive kind gate 10 · enum (cell or literal) 12 · no literal channel on the peer 8 (date literals) · keyless instance 4 · tree cell 3 · statement error 1 · unrefined Number 1 | 53 |
| real differences: the 2-ULP float pairs (sqlFunction acos/asin/…: host mode's declared leniency, not yet a SQL predicate — part 3) | 7 |
| real differences: `'4'`/`'7'`/`'1'` expected STRINGS over INT columns (the open ruling of §4d — now three witnesses: the dyna-function test, `testSimpleDistinct`, `testSimpleDistinctWithFilter`) | 3 |

**Next (part 3):** the leniency predicate in SQL (`abs(e−a) ≤ 2·ulp(max)` over the value
columns for finite doubles, positional, counted); then the null-canon-cell 14 read one by
one; then 3.1c.

## 4f. Leg 3.1b, part 3 — LANDED 2026-09-18 (the 2-ULP leniency as a SQL predicate)

Every row source of the verdict statement carries a third column, `__v`: the cell's DOUBLE
value when the cell is DECLARED Float (the side's canon kind, or the grid column's schema
type — the projection's type fact is not DOUBLE for a Float grid column, so the declaration
is the switch), NULL otherwise. Two CELL sequences ride beside the framed sides — for a grid
the cells row-major (`(row − 1)·width + i + 1`), for a peer or a collection its elements —
and ONE predicate over them is the leniency: same count, and every position either
canon-equal or a finite Double pair within `2 · ulp(max(|x|, |y|))`, ulp spelled
`2^(floor(ln(max)/ln(2)) − 52)` with `max = 0 → 0`. The verdict is `exact OR lenient`; a
fifth column `__lenient` (`NOT exact AND lenient`) is counted through the same
`sqlUlpPolicy` census host mode's firings use. The leniency is positional on arrival order
in EVERY form, as host mode's is (`Equality.ordered` runs first even for incidental sides) —
the first cut attached it to ordered forms only and never fired.

**Judged (DuckDB, database mode):** ulp firings 7 = host mode's 7; the seven sqlFunction
rows (acos / asin / atan2 / log / tan testProject / testFilter) pass; lost 63 → 56 = 53
named unjudged + the 3 String-over-INT rows of the open ruling. No other change.

**Named caveat:** the ulp through `ln` can differ from `Math.ulp` by one binade at an
exact power of two; the differential gate (3.3) is where that would show, and it has a
witness set of seven.

**Follow-up, same day — the 14 "null canon cell" rows, read:** every one was an EMPTY
side (`assertEquals([], …)`, `[TDSNull, TDSNull]->firstNotNull()`): the empty `[]` lowers
to one row whose value is NULL; the frame already spelled it `'[]'` and the verdict was
true, but the null-cell check counted that row. Pure has no null VALUE — the executor reads
a NULL scalar as the empty collection and drops NULL rows of a value collection — so the
canon side now drops a NULL-value row on EVERY side (not only collection sides); a NULL
canon over a non-null value stays unjudged. Lost 56 → 42: 39 named unjudged (kind-gate
non-primitive 10, enum cells / literals 12, date literals without a literal channel 8,
keyless instances 4, tree cells 3, one statement error, one multi-candidate Number) — all
leg 3.2 — plus the 3 open-ruling rows. Database mode: 2,322 asserts judged in the database
on DuckDB. Leg 3.1b is closed for the equals / sameElements domain.

## 4g. Leg 3.1c — LANDED 2026-09-18 (the one-line families)

`assertSize`, `assertEmpty` / `assertNotEmpty`, `assertContains`, `assert` / `assertFalse`
(a boolean condition, and the `forAll-contains` subset idiom), and `assertEqWithinTolerance`
are one predicate statement each (`VerdictSql.size / empty / contains / condition / subset /
tolerance`), returned in the same verdict row (no unjudged, no leniency). Each side is planned
by `planSide` — the same road as an equality side — and:
- **counting needs no canon**: a collection of instances the canon declines still has a row
  count (`countRows` over the bare plan, NULL values dropped) — the first cut refused 197
  size / emptiness asserts for that;
- **a class collection is ONE JSON document** (a GRAPH-shaped plan): its size is the array's
  length, or 1 for a bare object, 0 for NULL — the host rule — through a new
  `SqlFn.JSON_ARRAY_LENGTH` (`json_array_length`; H2 has no JSON, a dialect wall there);
- **the size rule's envelope**: a relation-rooted execute's `.values` holds ONE TDS
  (`envelopeValuesRead` → the count is 1), as the host's `envelopeCarriers`;
- **one canon channel per pair** for `contains` / the subset (both sides literal when either
  is literal-only, else the bare channel) — the first cut chose per side and compared a
  quoted string against a bare one;
- a predicate statement with no CTEs is its bare body (the WITH node refuses an empty list —
  15 emptiness asserts over graph sides).

**Judged (DuckDB, database mode):** assertSize 679 · assert 354 · assertFalse 19 ·
assertContains 17 · assertEmpty 20 · assertNotEmpty 13 · assertEqWithinTolerance 10 —
every assert of those families; with assertEquals 1,598 and assertSameElements 724 that is
**3,434 asserts decided in the database**; lost 42 = the same 39 named unjudged (leg 3.2) +
the 3 open-ruling rows; ulp 7. Still host-judged in database mode: assertJsonStringsEqual
(177, D6 / 3.2), assertIs 4 and assertInstanceOf 1 (identity / type — 3.2 or walled),
assertTdsEquivalent 2, the rendered-text arm (272, 3.1d), the SQL-text lane (D8).

## 4h. The reset (2026-09-18, after 3.1c) — what was not measured, the baselines, the plan

**What happened.** Legs 3.1b part 3, its follow-up and 3.1c were committed with only the
DuckDB database lane measured. The H2 database lane was last measured before 3.1b part 3
(lost 104) and not again. The host gate (both lanes) was green on every commit, and the
DuckDB differential improved on every commit, and that was read as enough. It was not: the
H2 database differential went from 104 lost to 332 lost across those three commits. The 3.1d
edit (the rendered-text arm as byte equality) was built on top, measured, found to take the
DuckDB differential from 42 to 76, and is **stashed, not committed** (`git stash`:
"3.1d rendered-text arm (unlanded, DuckDB lost 42->76)"). The tree is exactly 214f32056.

**The baselines at HEAD (214f32056), both lanes, measured 16:21–16:23:**

| lane | database-mode lost vs the host roster | gained | judged in the database |
|---|---|---|---|
| DuckDB | 42 (39 named unjudged + 3 open-ruling) | 0 | 3,434 asserts |
| H2 | 332 | 38 | assertEquals 1,379 · assertSameElements 565 · one-line families (see the census) |

**The H2 332, by the failure text (every row read from the lane output):**

| rows | cause | introduced by | fix (named below) |
|---|---|---|---|
| 193 | `Function "JSON_TYPE" not found` — the graph-shaped size rule (`VerdictSql.graphCount`) reads the JSON document's type and length | 3.1c | A |
| 60 | `variant navigation reached a dialect without JSON support` — the literal channel's JSON navigation | pre-existing, named at 3.1a | C (3.2, dialect leg) |
| 49 | `Function "REGEXP_EXTRACT" not found` — the float canon's exponent unfold (`LiteralSpelling.exponentUnfold`); 3 of them print with the message head hidden behind the cell separator byte and were first counted as a "peer form" bucket — reproduced outside the harness (`tmp/H2Run.java` over the dumped statement): the same error | 3.1b part 3 | B |
| 26 | the named unjudged rows (kind-gate 8, side-a/side-e 9, tds-peer 3, enum 3, tree cells 3) — the same rows as DuckDB's 39 where they exist on H2 | 3.1a–3.1c, by design | 3.2 |
| 1 | `Function "EPOCH_MS" not found` | — | D |
| 3 | rows not attributed above (the histogram's tail) | — | read at the leg |

**The 38 gained on H2 are correct verdicts, not wrong ones.** Probed (`routing::testSimpleEval`):
in host mode on H2 an expected `^TDSNull()` cell decodes as the STRING `'null'` and the actual
as an empty list, so the host fails the test for its own decode bug (the item named in §4d);
the database statement spells both as `TDSNull` and judges it true. The host reasons for all
38 (read from the last host H2 lane output) are that family: `expected: [..., 'null', ...]`.
That is an **H2 host-lane fix** (38 roster rows), named here, not part of this leg.

**Probes done for the plan (no guessing):**
- H2 2.1.214 accepts the peer-chunking form as written (`STRING_AGG(… ORDER BY …)` + `MIN`
  over a `GROUP BY` expression), with the `` cell separator in `LOCATE`, `STRING_AGG`
  and `||` — under the product's connection settings too (`tmp/H2Forms.java`, `tmp/H2Sep.java`).
- H2 has `REGEXP_SUBSTR(s, pattern, position, occurrence, flags, group)`; group 1 returns the
  capture (`'-1.3421e-08'` → `1.3421`; `'e([+-]?[0-9]+)$'` → `-08`).
- **H2 spells a DOUBLE as Java does:** `1.3421E-8`, `1.23456789E7`, `1.0E7`, `0.001` —
  UPPERCASE `E`, exponent form from 1e7 up. The unfold looks for a lowercase `e`, so on H2 it
  never fires and any double ≥ 1e7 spells as `1.23456789E7` in the canon. A correctness gap
  on H2 the spelling fix alone would not close.
- The graph fold is one projection at the top of the graph plan (`Lowerer.java:992`,
  `JsonArrayAgg(obj ORDER BY keys)` over the root rows' source; a to-one root is a bare
  `JsonObject`).

**The plan, ordered by rows per design, each with its named rows and its measurement:**

- **P0 — the ceilings register FIRST (was 3.1e, moved to the front).** The database-mode lane
  pins `lost` per lane (DuckDB 42, H2 332) shrink-only, and `gained` per lane as a NAMED list
  (H2: the 38, each a host decode row). A leg that raises either number is red before it is
  committed. The leg script measures FOUR lanes before any commit: DuckDB host, H2 host, DuckDB
  database, H2 database. Turns green: nothing; prevents: this section.
- **A — the graph size as a root-row count (H2 193; DuckDB the same asserts, cheaper).**
  `graphCount` stops reading the JSON document. The size of a graph-shaped side is
  `COUNT(*)` over the fold's own source (the top select with the `JsonArrayAgg` projection
  replaced by a count; a bare-object root counts its non-NULL rows) — no JSON function on
  any dialect, no document built to be measured. `SqlFn.JSON_TYPE` / `JSON_ARRAY_LENGTH`
  leave the verdict path (3.1c's addition, reverted by this). Turns green on H2: the 193;
  DuckDB: unchanged count, fewer bytes.
- **B — the float canon on H2 (49 statement errors + the ≥ 1e7 correctness gap).** The
  H2 dialect spells `REGEXP_EXTRACT(s, p, g)` as `REGEXP_SUBSTR(s, p, 1, 1, NULL, g)`; the
  unfold matches `[eE]` and the H2 dialect's double-text shape (`1.0E7` from 1e7 up) is a
  dialect fact the canon reads through the dialect, not a Java branch. Turns green on H2: the
  49; the correctness rows appear in the differential when the unfold fires (unknown count —
  measured, not guessed).
- **E — the rendered-text arm judged as a GRID (DuckDB 35 of 272; H2 15).** Replaces the
  stashed byte-equality form. `renderForm` already identifies the rendered relation (the
  `toCSV` / `toString` / join argument); that relation is planned as a `GridSide` and judged
  by the EXISTING grid forms (`gridRows` ordered or multiset by the chain's order view, the
  2-ULP predicate by declared Float column). The golden string is a compile-time constant: the
  compiler layer (`VerdictQueries`, beside `tdsNullSentinel`) splits it by the form's grammar
  (CSV: header + data lines + trailing ''; TDS: `#TDS`, header, rows, `#`; `CSVJOIN:sep`) and
  spells every cell by the grid's DECLARED column kind — String quoted, numbers bare, Float as
  the canon spelling plus its DOUBLE for the leniency, Boolean `true`/`false`, the empty cell
  `TDSNull` — bound as the peer's VALUES plan. The header is judged in the compiler (both
  sides static). Turns green: the 35 DuckDB rows (line order 11, one float digit 23, the date
  witness 1 stays an accepted divergence and needs the unjudged message to carry expected /
  actual so its witness matches) and the 15 H2 rows; all 272 judged in the database, none
  host-judged. Named risk: embedded commas / quotes in golden cells — the compiler splits
  CSV properly (the host splits on ',' symmetrically); a probe counts goldens with `"` before
  the leg.
- **D — `EPOCH_MS` on H2 (1).** A dialect spelling. With B.
- **C — JSON navigation on H2 (60).** Stays 3.2's dialect leg. Named, not this leg.
- **F — the H2 host 'null' decode (38 roster rows).** An H2 host-lane item, separate.

**Not changed by this section:** no verdict, no commit. The stash is reapplied only as the
starting point of E, then rewritten.

## 4i. Leg 3.1d + P0 — LANDED 2026-09-18 (the rendered-text arm as byte equality; the per-lane differential registers)

**USER rulings (2026-09-18):** "land it then we keep burning down" — the byte form lands with
its 35 rows named, the grid form (E) clears them next; "keep landing, ledger comes down at
3.5" — every arm carries a host branch beside its database branch until the differential gate
(3.3) makes the host branch deletable; the pinned evaluator grows by dispatch lines only
(3.1d: AssertVerdicts 2212 → 2222; the judging is `VerdictSql.renderedText`, SQL); and a
byte-equal string short circuit is an honest verdict — the 35 rows that pass only through the
host's line-multiset / cell-tolerance policy are the ones that must move to the grid road.

**3.1d.** `renderedArm` in database mode plans both sides (`planSide`, no canon) and runs
`VerdictSql.renderedText`: `text IS NOT DISTINCT FROM 'literal'`; a differing pair returns
`__unjudged = "rendered-text: not byte-equal (host policy: line multiset, cell tolerance)"`.
DuckDB: 237 of the 272 rendered-text asserts decided in the database (assertEquals 1,817 and
assertSameElements 740 judged in total); 35 unjudged, named (11 line-order over unsorted
chains, 23 calendarAggregations one-float-digit cells, 1 accepted-divergence date witness).
Lost 42 → 76 = the 35 + 39 named unjudged + 3 open-ruling rows (minus overlap: two tests
carry two of the rows). H2: 15 rendered-text rows unjudged; lost 332 → 342 (the extra rows =
rendered sides whose float canon hits the missing `REGEXP_EXTRACT` spelling — step B).

**P0.** `MinimalCorpusTest.pinDifferential`: in database mode the FAIL differential per lane
and direction is a NAMED register — `rcorpus/<lane>-database-lost-register.txt` (DuckDB 76,
H2 342; each row `name ||| the failure text`) and `rcorpus/<lane>-database-gained-register.txt`
(DuckDB 0, H2 38: the host H2 'null' decode rows). A lost or gained test not in the register
is red (NEW); a register row that no longer differs is red (STALE — delete it, reason in
GATES.md). Scoped runs compare against the rows that ran. The registers can only shrink from
here; the differential gate (3.3) refines them to per-assert rows.

**Measured before the commit, all four lanes:** DuckDB host 108 fail (roster exact) · H2 host
427 (roster exact) · DuckDB database lost 76 / gained 0 (register exact) · H2 database lost
342 / gained 38 (register exact); core registers green. This is the leg protocol from now on.

## 4j. Leg E, part 1 — LANDED 2026-09-19 (the general fixes; the pieces road built, measured and REMOVED)

**The 35 rendered-text rows read (every verdict row dumped, `tmp/rendered-35.txt`):** 22
calendarAggregations rows where a float cell differs from the 12th digit on (the engine's
golden was computed in H2's DECIMAL arithmetic, ours in DOUBLE as Pure specifies; the host
passes them only through `TdsCompare.cellEquals`' kept 1e-11 relative tolerance); 9 rows
where a joined collection's pieces arrive in another order; 1 accepted-divergence date row
whose witness the unjudged message did not carry.

**USER rulings (2026-09-19):** the bag compare is honest ONLY when the query has no top-level
ORDER BY; computing in decimal to match the calendar goldens would emulate one store's
arithmetic against the charter (Float IS a double) — the 22 (21 + testPywaDateRange, which
is a tds-peer row) are ACCEPTED with the cause written; "keep landing, ledger comes down at
3.5"; and — after the pieces road was built — "a lot of special casing … is this faithful to
the typed signature of map?": a carrier LABEL beside the Pure type is a side-car type system;
the type-faithful form is **a collection IS an array; relation space is an optimization by
algebraic law** (§4k homework, before any edit on that path).

**Landed (general, no shape-specific arm):**
- **Chained sorts COMPOSE** (`Sorts.carried`): `sort(name)->sort(address)` is the engine's
  `ORDER BY address, name` (testDoubleSortAsc1Chain's own golden SQL) — Pure's sort is stable,
  so the earlier keys are tie-breakers; the source is isolated, the carried keys re-addressed
  by OUTPUT name (a sortBy expression key cannot be carried). A product bug the host's line
  multiset had been hiding: ours sorted by the last key only.
- **The order view reads THROUGH the envelope splice** (`orderView(spec, lets, hook)`): a
  read of an execute frame (`$result.values.rows…`) resolved to INCIDENTAL before — every
  frame-bound sorted chain got the bag compare. Now `VerdictQueries.valuesRead` through the
  hook yields the frame's chain and its sort is seen.
- **Grouping / joins / unions / pivots leave no order** (`TypedGroupBy`, `TypedAggregate`,
  `TypedJoin`, `TypedAsOfJoin`, `TypedConcatenate`, `TypedPivot`, and the `ORDER_DESTROYING`
  natives) → INCIDENTAL; an extend descends to its source.
- **The unjudged message carries the evidence columns** (`excerpt`, 600 chars): the accepted
  witness matches again; a row is diagnosable without a re-run.
- **`rcorpus/<lane>-database-accepted-register.txt`** (read in database mode only, merged into
  the accepted register): the 21 calendar rows, class `engine-store-arithmetic:h2-decimal-
  average`, witness = the golden text. Host mode does not read it (there the host's tolerance
  passes them, and an accepted row that passes is red).

**Built, measured, REMOVED (recorded so it is not rebuilt):** the "pieces road" —
`makeString(c, sep)` judged as `c` against the golden cut at `sep`. First cut compared raw
elements to String pieces (enum / Float / null-cell collections mis-kinded: 5 new lost rows);
second cut minted `c->map(x|$x->toString())` — the faithful definition — and the lowering
refused it over rows-values and enum collections (`class query under TypedMap is not
resolvable`, **88 tests raised**); third cut fenced it to String collections + a text sniff for
`TDSNull` — the special casing the user called. Removed entirely. The 9 order rows stay in the
lost register, named `rendered-text`, until §4k.

**Measured before the commit (four lanes, registers exact):** DuckDB host 108 / H2 host 427;
DuckDB database lost **76 → 51** (25 out: 21 accepted, the date witness, testUnionViewJoins
and the two double-sort tests now byte-equal on the composed ORDER BY) / gained 0; H2
database 342 / 38 unchanged. Ledger with reason: AssertVerdicts 2222 → 2260 (order-view
navigation + the evidence message; nothing evaluated).

## 4k. Collections: the fact-based analysis (2026-09-19; USER: "step back … as a legend and database expert … real homework")

**Facts (each read or probed):**
1. The ENGINE never lowers post-result collection operators: only the lambda inside `execute()`
   becomes SQL; `$result.values.rows->map(...)->makeString(',')` runs in the Pure runtime on
   materialized rows. We lower them by tenet #1 (the database executes value evaluation) — our
   problem by choice; no engine precedent.
2. Size (all 589 engine test files, 3,490 test functions, full grep — not a sample):
   `makeString` over a query result 391 · `.rows->map` 528 · `toCSV` 108 · `toString` 252 ·
   `.rows->size()` 67 · `removeDuplicates` 11 · `contains` 15; makeString/joinStrings over a
   row map (the shape that failed) 162.
3. The lowering ALREADY collects a query collection in value position into a list —
   `ValueCollections.collectAsList`: `SELECT LIST(value) FROM (projection)` — with NO ORDER BY
   (arrival order: the audit's named debt). The typed node keeps Pure's `T[*]`; the resolver's
   guard `case TypedMap m when anchored(m.source()) && Type.relationValued(m.source().info())`
   is what refuses a second map ("class query under TypedMap is not resolvable yet") — a guard,
   not a missing capability.
4. The ratified designs: CARRIER_REDESIGN (exit met 2026-08-01) made the array vocabulary
   (ArrayLit, LIST_*, UNNEST, Reducer(LIST)) the semantic IR — each entry an ANSI spelling, a
   CarrierStrategies rule per dialect, or a typed DialectCapability wall
   (SpellingsTest.everySqlFnClassified); F10 (ratified 2026-08-23) made the kind-faithful carrier
   a JSON array of literal spellings. "Collections are arrays; dialects rewrite" is the landed
   architecture, not a new idea.
5. Databases: DuckDB has the full list algebra with lambdas but its binder refuses a subquery
   inside a lambda (the documented reason distinct/sort moved to relation space —
   ValueCollectionOps). H2 2.1.214, probed (`tmp/h2arr.sql`): `ARRAY_AGG(x ORDER BY x)`,
   `ARRAY_AGG(DISTINCT … ORDER BY)`, `CARDINALITY`, `ARRAY_CONTAINS`, ordered array `=`,
   `UNNEST … WITH ORDINALITY`, `LISTAGG` all work; no per-element lambdas.
6. Semantics: a Pure `T[m]` is an ordered sequence with duplicates — an array matches exactly;
   a relation matches only with an explicit ordinal.

**Options:**
- **A. Relation space as the meaning** (the habit we drifted into): planner-friendly, ANSI;
  contradicts the type, order carried by hand everywhere, every operator needs a row twin — the
  source of the special cases.
- **B. Arrays only:** type-faithful, order free; but a query collection is gathered then
  re-split per operation, the planner sees nothing, large collections are one cell, every
  per-element operation walls on H2.
- **C. Arrays as the meaning, relation space by rewrite LAW:** the value of a `T[m]` is
  `ARRAY_AGG(x ORDER BY <the query's sort keys, else its row number>)`; each operator an array
  operator; local laws rewrite "array operator over array_agg" to the row form (transform →
  projection, filter → where, distinct → ARRAY_AGG(DISTINCT), sort → ORDER BY, makeString →
  STRING_AGG, size → COUNT). Faithful; order in ONE place; the planner gets rows wherever a law
  fires; H2 works where a law fires and walls by name where none does; it is where the landed
  architecture points. Cost: the laws must be written and pinned; the early "projection when
  relation-valued by type" becomes one of them instead of a guard.
- **D. Evaluate post-result operators in Java (the engine's truth):** breaks tenet #1 and the
  verdict-in-database program. Listed for honesty, not recommended.

**Recommendation: C, as three measured steps** (ruling awaited before any edit):
1. ORDER in the array construction — `collectAsList` gains `ORDER BY` the chain's sort keys,
   else the row number; retires the arrival-order reliance for every consumer at once.
2. The resolver guard becomes a law — "structural when the source is relation-valued BY TYPE"
   → "structural when the source RESOLVES structurally"; map over map is not a case. Rows: the
   88 raised tests, the 162 makeString-over-row-map asserts, the 9 named order rows.
3. The rewrite laws pinned as laws — the row forms that exist for distinct / sort / string_agg /
   count written over array_agg, each with a both-dialect equivalence test.
Then the judge has no branch: a golden's pieces are an array literal; ordered equality = array
equality; bag equality = sorted-array equality (both dialects have both).

## 4l. Bucket 1 — enums (2026-09-19; USER: "fix all the buckets we can first … come back later")

**The bucket order ratified (easiest and biggest first, one commit each, four lanes and the
registers exact before every commit):** enums 12 → date literals 8 → JSON 177 asserts → instances
/ keyless / tree cells 17 → identity + type checks 7 → the String-over-INT ruling 3 → two
singletons → H2 quick wins (graph size 193, float spelling 49, epoch 1) → the glued-text order
rows (the collections design, §4k) LAST.

**The enum rule (one owner, the literal grammar).** The host judge has no enum arm: an enum
literal lowers to its NAME string and a mapped cell decodes to its name, so host mode compares
NAMES (enumeration ignored). The grammar gains pure's own enum literal, `Enumeration.NAME` —
the seventh spelling, disjoint from the six (unquoted, carries `::`) — on both halves:
`LiteralSpelling.literal` (SQL) and `LiteralText.parse` (host; decodes to the NAME the host
holds an enum as; before the Decimal arm — a name may end in D; `LiteralTextTest` pins every
form). The three enum declines went: the grid canon, the mixed-literal encoder
(`MixedEncoding.spellByKind`), the two verdict gates. The database verdict is now
ENUMERATION-SCOPED (pure's rule, stricter than the host: a false pass is impossible, only a
named lost row). Two shapes stay named: an enum against an UNTYPED wire (`$row.values->at(0)`,
`toDomainValue` — both typed Any: the Any carrier holds the name as a JSON string, no
enumeration) → `enum against an untyped (Any) wire`; the ABSTRACT Enum declaration
(`meta::pure::metamodel::type::Enum`, an EnumValueMapping's `.enum`) has no spelling (a spelled
`Enum.NAME` would fabricate inequality) → `no literal channel`. Both are one fix: the metamodel
row carrying the enumeration (named, not this leg).

**A first cut broke host mode (8 rows, both lanes): the encoder learned the spelling before the
decoder did** — the mixed literal `['Firm A', …, GeographicEntityType.CITY]` moved to the
LITERAL lane and the host parse threw NumberFormatException on `…GeographicEntityType.CITY`. The
grammar's own rule ("a form added on either side MUST land on both") caught by the four-lane
protocol before any commit.

**A determinism finding (the order bucket grows, honestly).** `testFilteredProjectWithPost
TdsOperations` flipped between runs (`5,2|6,2` vs `6,2|5,2`): a rendered text over a GROUPING
with no sort after it has no defined line order — the assert-boundary determinism
(`ScanOrder.stabilize`) is BY DESIGN scan-roots only, never aggregates, and DuckDB's hash
operators arrive differently run to run. A register row must not flip, so the rendered-text arm
is UNJUDGED deterministically over a hash-ordered chain (`hashOrdered`: grouping / join / union
/ pivot with no later sort — typed-tree navigation). That names 58 DuckDB tests (calendarAggregations 41,
embedded groupBy 9, validation 4, cteExtraction 2, union 2) that had been passing database mode
by a stable-in-practice arrival order, and 48 on H2. They join the order bucket (§4k's grid
road turns them). Ledger with reason: AssertVerdicts 2260 → 2314.

**Measured (four lanes, registers exact):** DuckDB host 108 / H2 host 427; DuckDB database
lost 51 → 101 (−8 enum, +58 order named) / gained 0; H2 database lost 342 → 382 (−8, +48) /
gained 38. Judged in the database: the 8 enum grid tests (enum cells spelled
`Enumeration.NAME` on both sides).

## 4m. Bucket 2 — the "tds-peer" rows (2026-09-19)

**Not date literals after all.** The 8 rows named `tds-peer: no literal channel` were two
causes, found by making the decline carry the peer's own state (kinds / decline reason):
- **4 DateTime literal collections** (`[%2016-06-23T13:00:00.000000000+0000, …]` vs
  `rows.values`): the literal lowers on the precision-faithful `TEMPORAL_TEXT` carrier, which
  the wire-kind map (`kindOfSqlType`) did not list — so the literal channel was never built.
  `TEMPORAL_TEXT` → the temporal kind (and `TIMESTAMPTZ` → DateTime, the +0000 form).
- **4 empty-literal peers** (`assertEquals([], $result.values.rows)` and the
  `rows->filter(…'Unknown')` shape): the Nil branch of the canon wrap claimed the side with a
  NULL canon but never set it as the literal channel; and the peer-cells CTE did not drop the
  NULL-value row every other side drops (the empty `[]` is one NULL row). Both fixed: an
  empty peer is ZERO cells, and a grid against it judges "no rows" in the database.

**Measured (four lanes, registers exact after removing the 8 / 3 rows):** DuckDB host 108 /
H2 host 427; DuckDB database lost 101 → 93 / gained 0; H2 database lost 382 → 379 / gained
38. Ledger with reason: AssertVerdicts 2314 → 2317 (the decline message carries the peer's
state).

## 4n. Bucket 3 — JSON (2026-09-19; D6 WITHDRAWN and replaced by measurement)

**USER: "we literally create the json objects in the database for graph fetch — let's look at a
real example end to end."** The flat chain test: the database builds the whole document
(`to_json(list(json_object('firmName', …, 'employeeCount', …) ORDER BY t0.ID))`) and the golden
is the same text; the parse-both-sides Java judge bought nothing there. So the bucket was
MEASURED before any design: every JSON assert in database mode compared BYTES (each side read
as its own text column), the differing pairs classified with the host's parse (a dump-only
classifier, removed afterwards):

| result (177 asserts, DuckDB) | tests |
|---|---|
| byte-equal as built | 81 |
| key order only | 69 |
| golden a bare object, our document a one-element array (the engine's single-result print) | 15 |
| pretty-printed golden, whitespace only | 9 |
| root array order, unsorted chain | 1 |
| number spelling / escaping / real value | 0 |

**Key order is not a contract.** The engine's serializer does not keep the tree's order (the
cross-store test's tree lists `tradeId` then `product`; the golden has `product` first), nor
the class's declared order (the subtype test), nor alphabetical; it is an artifact of its
execution (a cross-store or subtype pass emits its properties first) — which is why the
engine's own asserts compare structurally. One fixed order on both sides lets bytes decide.

**What was built (no second serializer, no number canon, no JSON functions in SQL):**
- `Json.canonical` — the golden's canonical text: compact, keys sorted, numbers as parsed;
  `VerdictQueries.foldedStringLiteral` folds the corpus's `'[' + '{…},' + ']'` literal chains
  (a constant fold, no data); `canonicalJsonGolden` parses, canonicalizes, and wraps `[…]`
  when the query root is many-valued and the golden is a bare object — the engine's
  `[x] ≡ x` rule decided at compile time (`serializedRootMany` reads the typed chain through
  the frame splice to its serialize node).
- `JsonKeyOrder` — one IR pass (`SqlRewriter`, post-children) over the VERDICT plan only:
  every `json_object` with literal keys written with the keys sorted; the merge-patch
  composition (single-key pieces, the removeNull serializer form) sorts its pieces. The
  product's own output keeps the tree's order. Switched by `CanonRider.withCanonicalJsonKeys`
  (the JSON arm's actual side), applied in `StatementExecutor.planValue`.
- The envelope exemption: executeLegendQuery's result (`{"builder":…,"values":…}`) is always
  ONE object and already applies the single-result print inside `values` in SQL
  (`CASE WHEN COUNT(*) = 1 THEN MIN(json_object(…))`); read off the planned side
  (`planIsEnvelope`: the root is a builder-keyed object or a concat whose first piece is the
  `{"builder":` literal) — three misses on the way (a chain-level check the frame splice
  cannot see; a plain-concat check where the spelling is CONCAT_JOIN), each found by a dump.
- `VerdictSql.jsonText` — `document IS NOT DISTINCT FROM 'golden'`, evidence in the row.

**Result:** 174 of 177 JSON asserts decided in the database. Named residue (3): two goldens
that pass through `parseJSON()->toPrettyJSONString()` on both sides (`json golden is not a
literal`) and `union::propertyLevel::test6`, whose nested `employees` list has no order key
over a union (arrival luck in both modes — the collections leg, §4k). One accepted-divergence
row (`testCheckedWithCircularConstraints`) gained its database-mode witness. DuckDB database
lost 93 → 96 (the 3 named). H2 database: 379 → 383 — `test6` as on DuckDB, and three graph
tests whose documents H2 builds with its own number spelling (`{"pnl":1E2}` for `100.0`;
`3.5E2`) — the uppercase-E gap already named at §4h B (the H2 float spelling quick win).
Ledger with reason: AssertVerdicts 2317 → 2427; StatementExecutor 2209 → 2212 (the
JsonKeyOrder hook). Two guardrails moved the shape on the way: the rider's flag is FINAL
(constructor, not a setter — CodeShapeGuardrail) and the golden parse catches the parser's
own refusal only (ErrorShapeGuardrail).

## 4o. Bucket 4 — type values, element references, untyped row cells (2026-09-19)

**The 17 rows were three shapes, none an "instance" in the sense the kind gate meant:**
- **Type values (10):** `assertEquals([String, Integer], $result.values.columns.type)`,
  `assertSameElements([Car, …, Bicycle], $r->genericType().rawType)`,
  `assertEquals(String, 'VARCHAR'->explodeReturnType())`. A type written as a value is stamped
  by the typer as its PROTOTYPE (`String` : String[1] — Typer.typeRef, the convention cast/to
  bind through) and a class reference as `Class<Car>`; on the wire both travel as their bare
  simple names (the Lowerer's long-standing convention). The rule, on both grammar halves: **a
  type / element value spells as its bare simple name** — the eighth form, unquoted, disjoint
  from a string (`LiteralSpelling` through `MixedEncoding.elementLiteral`, `LiteralText.parse`
  reads a bare identifier back as the name). The pair's kind is read off the NODE
  (`kindKey`: TypedTypeRef / a `Class<X>` packageable ref → "type"; a metamodel type
  classifier — `PlatformTypes.isTypeClassifier` — → "type"; a tracked element class →
  "element:<fqn>"); the canon wrap claims a name-valued side (`nameValued`, the callers pass
  the model's `tracksClassifier`) and a type-valued GRID column spells bare. Two typer-side
  facts had to become honest: `ColumnsMetaFold` folds `columns.type` to TYPE VALUES (TDSColumn.
  type : Type), not name strings (a `TypedTypeRef` gained its scalar lowering — the name); and
  `GenericTypeReflection.rawTypeProjection` declares its column as the classifier `Type`, not
  String — the cell was always the class's name.
- **Element references (4):** `assertEquals($superMappingMainTable, $mainTable)` — Mapping
  refs; the same bare-name rule (the model's own `tracksClassifier` names the element
  classes).
- **Untyped row cells (3):** `rows.get('col')` reads through the variant carrier; a NULL cell
  arrived as JSON `null`, which the Any-cell canon marked as a tree. A JSON null cell IS the
  TDSNull slot: it spells the quoted sentinel `'TDSNull'`, the same equivalence the host applies.

**Guardrails on the way:** Lowerer's size limits (the type-value arms compacted to one), a
dead helper deleted, the ledger re-pinned with its reason.

**Measured (four lanes, registers exact):** DuckDB host 108 / H2 host 427; DuckDB database lost
96 → 79 / gained 0; H2 database lost 383 → 371 / gained 38 → 39 (`tds::groupBy::simpleGroupCount`:
the host H2 lane fails it for its own reason; the database judges the column types by name).
All 17 named rows decided in the database.

## 4p. Bucket 5 — identity and type asserts (2026-09-19)

- **assertIs (4, all `assertIs(db, mapping->resolveStore(db))`):** a tracked element's
  identity is its row's key — the resolver's own `identityCondition` (the same equality the
  chain normalizer rewrites `is` to), judged as the condition statement. An enum pair goes
  through the equality statement (bucket 1's spelling); a statically identified pair stays a
  compile-time verdict (counted like the static kind gate). Anything else is named.
- **assertInstanceOf (1):** the model's subtype relation IS `instanceOf` — minted as the native
  call in the compiler layer (`VerdictQueries.instanceOfCondition`, lowered by the existing
  `Scalars.instanceOfFold`) and judged as a condition.
- **assertTdsEquivalent (2):** `VerdictSql.gridTolerance` — both grids' cells row-major, aligned
  by position; a numeric pair within `delta`, a temporal pair within `timeDelta` seconds (the
  cells arrive decoded as text after the fetch conformance, so a temporal cell casts back to a
  timestamp for its epoch), any other pair canon-equal; the cell counts must match; the column
  names are checked statically from the two schemas. One of the two tests is an ACCEPTED
  divergence in host mode (`engine-golden-defect:h2-literal-coercion`: the engine's H2 coerces
  a nine-digit literal to millis and returns a second row); its database-mode witness is the
  statement's own evidence (`expected: 2 | actual: 1`).

**Measured (four lanes, registers exact):** DuckDB host 108 / H2 host 427; DuckDB database
lost 79 → 79 / gained 0 (the accepted row); H2 database lost 371 → 373 / gained 39 — the two
tolerance tests on H2 hit the missing `EPOCH` spelling (H2 spells it `EXTRACT(EPOCH FROM …)`)
and the temporal-text regex spelling: the §4h B quick-win bucket. All 7 identity / type asserts
decided in the database on DuckDB. Ledger with reason: AssertVerdicts 2457 → 2527.

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
