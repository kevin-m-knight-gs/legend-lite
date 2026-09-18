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
5. Ratification of §3 D1–D8.
