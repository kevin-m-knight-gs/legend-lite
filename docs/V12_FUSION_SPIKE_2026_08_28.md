# V12/V13 fusion spike — real corpus tests, hand-fused SQL (2026-08-28)

**Question.** Is the recorded assert end state (OPEN_REGISTER rows
V12/V13, user design 2026-08-22) achievable on real corpus tests —
the assert running **inline as a wrapper around the real execution**
(the graphFetch→serialize species), one statement per test body, a
verdict row out? And: which parts of the current V7 migration work are
durable vs. throwaway under that end state?

**Method.** Four real engine corpus tests, their fixture DDL/inserts
lifted verbatim from the engine checkout (spec input, never runtime),
inner SQL taken from the engine's own `assertSameSQL` golden where one
exists, hand-fused per the register design (let IS WITH materialized
CTE; side-tagged UNION ALL evidence; per-side canon columns with
`ORDER BY side+canon` realized as `list(canon ORDER BY canon)`
equality; expected literals inline). Canon spellings hand-mirrored
from `LiteralSpelling` (the platform's SQL canon owner). Executed on
python-duckdb 1.4.4 (platform runs JDBC 1.5.0 — a landing slice must
re-verify through the platform's own emission). Script:
[`tools/spikes/fusion_spike_2026_08_28.py`](../tools/spikes/fusion_spike_2026_08_28.py).

| test (real corpus fn) | shape | fused verdicts | polarity |
|---|---|---|---|
| `mapping::dates::datetime::testQuery` | sorted temporal assertEquals + assertSize, one CTE | size ✓; equals ✗ under today's canon, **✓ under nine-digit canon** | — |
| `query::association::toMany::testAssociationToManyWithBoolean` | assertSameElements multiset + assertSize; **engine SQL verbatim** | both ✓ | broken golden fails ✓ |
| `mapping::boolean::testProject` | flat cells (`rows.values`), width-2 grid, incidental order | both ✓ | **cross-row shuffle fails ✓** |
| `graphFetch::simple::testSimpleGraphFetchWithPrimitivesOnly` | serialize → JSON golden | byte ✓ (same key order) | reordered-key golden fails (see F4) |

## Findings

**F1 — the verdict-overlay end state works on real tests.** Three of
the four tests fused into ONE statement each — the query and every
assert on it — with correct verdicts and correct failure polarity.
The register's shapes all executed as designed; nothing about the
overlay is hypothetical for scalar, multiset, and grid-cell asserts.

**F2 — grid canon is a small extension, not a wall.** The flat-cells
blocker today is mechanical: `CanonicalRenderSql.wrapWithCanon`
declines any plan with more than one output column. The spike's grid
canon — per-cell leaf spelling joined by a unit separator (chr 31)
into a row canon, row-tuple multiset = sorted-list equality over row
canons — adjudicated the real 14×2 grid correctly and **failed the
cross-row shuffle** (audit 9's requirement). This retires the
"permanently exempt raw cells from the byte channel" framing: the
exemption was a gap, and the gap is closable.

**F3 — the nine-digit temporal disagreement (§4N row 2) has a proven
in-database fix.** Today's canon (CAST → minimal subseconds)
reproduces the named wire-fidelity disagreement byte-for-byte; canon
via `strftime(ts, '%Y-%m-%dT%H:%M:%S.%n')` over the ns carrier
(DuckDB accepts the fixture's `TIMESTAMP(9)` DDL) emits the engine's
nine-digit spelling — `.000000000` on whole seconds too — and the
fused verdict passes byte-exact. Adjudication still required: the
canon convention change must move host `CanonicalForm` and the SQL
canon together (PCT lanes pin today's spelling).

**F4 (AMENDED 2026-08-28, user catch) — JSON rides the byte channel
like every other kind, via canonical EMISSION, no SQL-side recursion
needed.** The original finding ("DuckDB JSON equality is key-order
sensitive → host-judged") tested the wrong mechanism — the `=`
operator over arbitrary documents — instead of applying the canon
doctrine (render canonically, compare bytes). The two sides don't
need runtime canonicalization at all: the ACTUAL side's JSON is built
by OUR serializer, whose key set per object is STATIC (the fetch
tree), so the canon channel emits a second build with keys pre-sorted
at compile time (tree-order emission stays the product output); the
EXPECTED side is a compile-time literal, canonicalized once at
inlining (parse, sort keys, minify). Probed: sorted-key DB emission
byte-equals the host-canonicalized golden, including int/double
number spellings. Named residual seams for the landing slice: float
edge spellings in JSON leaves (exponent/zero forms — the floatCanon
class), string escapes/unicode, and key order after
`json_merge_patch` (the removeNullKeys shape). Arrays keep order on
both sides (engine rule) naturally. Host JsonCompare remains the
parallel referee, not the judge.

**F5 — the eager-evaluation hazard is real, and mitigable.** A later
assert whose evaluation ERRORS kills the whole fused statement
(ConversionException) even when an earlier verdict already failed —
pure semantics stop at the first failure. Proven mitigation: `CASE
WHEN <earlier verdicts> THEN <later side> END` defers evaluation
inside the same statement; the register's fused→split→bare→fold
ladder remains the fallback for bodies that can't nest.

**F6 — fixtures are workspace state, not per-file state.** Two live
demonstrations in one spike: the association test's golden requires
`testSimple.pure`'s cross-family person rows (ids 8–12, incl. the
Smith at Firm C), and the graphFetch test requires its family's OWN
7-row personTable override. Fusion must compile against the harness's
actual workspace state (DuckWorkspaces stays the fixture owner);
goldens pin mechanism∘fixture.

**F7 — timing.** Median per test body at this fixture scale: fused
0.95 ms vs split 1.30 ms (~25%). Queries this small make wall-clock a
secondary motive; the V12 GO/NO-GO (TimingLedger query.exec share)
still governs. The primary value demonstrated is architectural: one
execution, the verdict riding it.

**F8 — kind fidelity held without promotion.** Int and boolean cells
stayed distinct through the canon-text channel (disjoint spellings);
the register's NULL-padded typed value columns remain the design for
the EVIDENCE layout, but the verdict itself never needed a promoting
union.

## Rung choice: per-assert statements over whole-body single-shot
## (2026-08-28, user question → adopted framing)

The spike demonstrated the TOP rung (whole test body = one
statement). The right DEFAULT landing rung is the register's own
"split": **the shared `$result` materializes ONCE (the let-IS-WITH
materialization — evaluate-once semantics; two asserts must see the
SAME rows of a nondeterministically-ordered result), then EACH assert
is its own small verdict statement against it.** What that buys, for
free: pure's stop-at-first-failure (assert 2's statement never runs
if assert 1 fails — the F5 eager-evaluation hazard vanishes without
CASE nesting), and exact error attribution (a statement error IS that
assert's error). Cost: 1+N round trips instead of 1 — at 0.26 ms per
query, noise. Whole-body single-shot remains the top rung for bodies
proven hazard-free, gated by the V12 TimingLedger measurement. The
one forbidden shape: re-executing the shared query per assert
(violates evaluate-once; nondeterministic order/sequences would let
asserts disagree about the same result).

## Roadmap implications (PROPOSED — for ratification)

1. **Leg 1 (flat cells) lands as the grid-canon extension** of
   `wrapWithCanon` (multi-column: per-cell leaf + row assembly +
   row-tuple order key), with the host cell compare as the parallel
   referee — NOT as a host-only arm with a permanent byte decline.
2. **Leg 5's temporal fix** uses F3's mechanism; adjudicate the
   nine-digit canonical-spec change (host + SQL canon + PCT pins move
   in one slice).
3. **Every remaining burn slice** is built as "comparison policy
   chosen at compile time from static types; executed host-side
   today; emittable as the fused overlay tomorrow" — the spike shows
   the emission target concretely for scalar/multiset/grid shapes.
   Runtime shape-sniffing arms are the one layer fusion discards;
   stop growing that layer.
4. **JSON rides the byte channel** via canonical emission (F4 as
   amended): sorted-key canon build on the actual side, compile-time
   canonicalized golden on the expected side; JsonCompare = referee.
5. **Per-assert verdict statements over a once-materialized result**
   are the default rung (see the rung-choice section); whole-body
   single-shot is the measured top rung.
6. **V12/V13 sequencing after V7 cutover is unchanged** (the
   dual-referee and pinned semantics are what make the flip safe).

**Limitations.** Inner SQL hand-written for three tests (engine SQL
verbatim only for the association test); no milestoning/union/window
shapes attempted; python-duckdb 1.4.4 vs platform JDBC 1.5.0; canon
spellings mirrored by hand, not emitted by the platform.
