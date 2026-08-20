# MULTIPLICITY-STAMP DISCIPLINE PROGRAM

Commissioned 2026-08-20 (host-logic audit follow-on; user: "go - build the
stamp instrument and census first"). Root defect, in ListShapes' own words:
*"pure multiplicity stamps are unreliable after substitution — many-stamped
reads stay scalar; values readers return lists from to-one-stamped
subqueries"* — which forced runtime SHAPE-SNIFFING (ListShapes, numList,
~29 isToOne arms) instead of trusting static types. The program: make the
stamp always true, make count-changes explicit emissions, enforce with a
post-lowering invariant, then delete the sniffing.

## The instrument

`lowering/StampCensus` — hooked at the ONE scalar funnel
(`Lowerer.scalar`), env-gated `LL_STAMP_COUNT=1`, measurement only.
Reports only PROVABLE mismatches:
- `ONE-STAMP/LIST-SHAPE` — scalar-stamped (`upper <= 1`) expression whose
  SQL is definitely a list (ArrayLit / list-producing call / list-valued
  subquery / array cast).
- `MANY-STAMP/SCALAR-SHAPE` — many-stamped expression whose SQL is a
  definite scalar (literal or scalar cast).
- `VAR-STAMP-AT-LOWERING` — a multiplicity VARIABLE surviving to lowering
  (lowering should only ever see concrete bounds).

`NullLit` is excluded BY DESIGN: SQL NULL is the designed carrier for
pure `[]` in scalar AND list positions — the first census run counted the
convention itself 62,460 times before the exclusion (instrument
false-positive, fixed same day; the lesson is recorded here so the
exclusion is never "simplified" away).

Absence of a line is NOT proof of health (unknowable shapes — column
reads, opaque calls — are never reported); presence IS proof of a lie.

## Census (2026-08-20, full corpus @ real roots + all five PCT suites)

1,021 corpus + 304 PCT provable events; both runs GREEN (instrument is
behavior-neutral). Five cause-classes:

| # | Class | Events | Witness rows |
|---|---|---|---|
| C1 | Collection literal stamped `[1]`/`[0..1]` lowers to ArrayLit (TypedCollection 861, TypedCast 200, TypedNewInstance 5) | ~1,066 | the slice-2 "singleton-list-literal" class residue |
| C2 | `toOne()` stamped `[1]` rides a definite list through (ScalarSubquery 127, Call 54) | 181 | the documented "to-one-stamped subqueries return lists" |
| C3 | value-collection `sort`/`distinct`/`reverse`/`list` one-stamped but list-valued (TypedSort 12, TypedDistinct 20, list/reverse calls 7) | 39 | same family, different constructors |
| C4 | multiplicity VARIABLES at lowering (`y`, `acc`, `_i1/_i2/_i5`) | 37 (PCT only) | generic instantiation fails to bind multiplicity vars |
| C5 | many-stamped property read provably scalar (`u_map__active`) | 1 | the documented "many-stamped reads stay scalar" |

Unique patterns: ~13 corpus + ~13 PCT — the worklist is FINITE and small.

## Fix order (each slice: fix cause-class → census shrinks → gates → pin)

1. **C4 — CLOSED (2026-08-20, one line)**: the lambda-parameter seam
   resolved the TYPE through the kernel binding but took the
   MULTIPLICITY raw from the signature (Typer lambda-scope build) —
   `Var("m")` flowed into scope for sort comparators and fold
   accumulators. Fix: resolve through the binding when bound (a var
   still OPEN there stays, and the census keeps counting it). Census
   37 → 0; core 4166/0; all five PCT suites unchanged. PCT census
   304 → 267 (all remaining = C1/C3).
2. **C2 — MEASURED NEGATIVE RESULT (2026-08-20), redesigned**: the
   blanket toOne unwrap (`list[1]` when the operand is definitely
   list-shaped) was built and run against the FULL corpus: functions
   referee byte-identical, but milestoning −16 / union −23. Diagnosis:
   the ScalarSubquery witnesses are values-reader lists whose FULL list
   downstream consumes — the `[1]` stamp is the lie, not the shape, and
   many of those `toOne`s are resolver-SYNTHESIZED conformance markers
   (the conform-by-emission family), not user value-ops. REVERTED; the
   correct C2 decomposes by PROVENANCE (the TypedJoin.userCondition
   pattern):
   - (a) USER-written toOne over a genuinely-single sloppy shape →
     explicit unwrap at the seam;
   - (b) SYNTHESIZED conformance toOne → ride-through BY DESIGN, marked,
     and the census stops counting it as a lie;
   - (c) values-reader subquery stamps → fixed at their PRODUCER
     (the stamp becomes `[*]`), which is the real C2 residue.
   Attribution instrument upgraded first (census lines carry
   `test=<fqn>` via StampCensus.CONTEXT); witness verified: the biggest
   C2 test (testSimpleTypeMappingProject, 18 events) has NO user toOne —
   all synthesized. SIZED (2026-08-20): the provenance mechanism is a
   `Pure.Lite.conformToOne` internal native (provenance = FQN, the
   internal-natives partition governs it; zero field churn) — but
   synthesized toOnes come from TWO layers (17 protocol-level
   `AppliedFunction("toOne", ...)` sites in the normalizer's
   UnionSynthesis + the typed-level GraphEmission/JsonSourceFrame
   family) and 89 recognizer sites reference the toOne name/FQN and
   need the audit for whether they must also match conformToOne
   (peelers yes, others per-site). A full slice with full-corpus
   verification — NOT a quick tail (the blanket-unwrap regression is
   the caution).
3. **C3** (same seam family): value-collection `sort`/`distinct`
   one-stamped lowerings — adjudicate with the same provenance split.
4. **C1** (biggest, mostly mechanical): scalar-stamped collection
   literals lower to their element (singleton) or the designed empty —
   the 29 consumer-side isToOne guards then start deleting.
5. **C5**: the one many-stamped scalar read — fix at its resolver site.
6. **Flip the instrument to an INVARIANT**: census == 0 on full sweep →
   `LL_STAMP_COUNT` check becomes a failing gate; then DELETE the
   shape-sniffing (ListShapes' runtime arms) as consumers stop needing it.

Everything downstream (the canonical-render/byte-compare verdict design,
HOST_LOGIC_AUDIT fix queue items 3-4) builds on stamps being
enforced-true; it stays parked until the census is zero.

## Guard-emission probe (2026-08-20, both backends — CLEARS the checked toOne emission)

P1 filter-before-projection, P2 dead-branch constant-fold, P3 CASE
per-row laziness, P4 AND short-circuit, P5 subquery under pushdown: NO
spurious guard firings on DuckDB or H2. Must-fire: DuckDB `error(...)`
raises with the message under the standard prefix; H2 via
`CREATE ALIAS RAISE_ERROR` (source-code alias; enabled by default per
H2_BACKEND.md) raises with the message embedded and as the cause.
Emission design: ONE semantic node (checked narrowing) with per-dialect
spellings; engine-text channel renders the inner value (engine-verbatim
noOp view, the NULLS-suppression precedent). ORDERING (the blanket-
unwrap lesson): the guard lands ONLY AFTER synthesized conformance
toOnes are replaced and producer stamps honest — guarding a fake toOne
whose list downstream consumes regresses identically to the revert.

DISCOVERED FORK to rule on separately (out of C2's census scope): toOne
over an EMPTY scalar carrier ([0..1] → SQL NULL). Pure throws ("size
0"); engine's processNoOp lets NULL flow, and the corpus (drop-in
surface) pins NULL-flow behavior broadly. The census never counted this
(NullLit is the designed empty carrier). Needs its own adjudication —
upstream interpreted-vs-relational family, same as index-base.

## C2 emitter map (2026-08-20, fingerprinted via arg0 digests + SQL dumps)

- dataType/projection cell reads: `toOne(prop)` over `SELECT LIST(col)
  FROM (filtered scan)` — single by DATA (filter on a unique key), never
  provable. KEY INSIGHT: dropping the LIST agg yields SQL's NATIVE
  scalar-subquery semantics — >1 row raises, 1 row yields the value,
  0 rows NULL — i.e. the DB-native form IS pure's checked toOne for
  subquery operands, no error() emission needed (message parity vs
  pure's "Cannot cast..." wording is the only residue).
- union `toOne(x_pk/y_pk/...)`: per-member PK collects whose LIST the
  merge genuinely consumes — the agg-strip would raise at runtime here;
  the union synthesis must become an honest [*] flow + explicit merge
  BEFORE the strip can be unconditional. ONE LEG, sequenced:
  synthesis-fix then agg-strip.
- Typer TDS-getter desugars (5 sites): STAMPS ARE HONEST (toOne sits
  inside an isEmpty-guarded branch); their shape rides the same
  LIST-collect and heals with the strip.
- GraphEmission.toOneJoinEquals: a TYPE-LIE used as a lowering
  side-channel (make [0..1]==[0..1] verbatim) — replace with the honest
  mechanism that already exists (NullSemantics.enterVerbatimEquality /
  the TypedJoin.userCondition route).
- C2-i landed meanwhile: provably-single sources (LIMIT≤1 chains, Dual)
  lower cell reads as plain scalar subqueries (Fold.provablySingleRow).

## C2-i LANDED (2026-08-20 night, third attempt — fix train green)

- Fold.provablySingleRow (LIMIT<=1 chains + Dual, recursion through
  subselects; joins unprovable) + the map-collect arm's scalar route:
  provably-single cell reads lower as PLAIN scalar subqueries.
- makeString's latent hole fixed shape-true: the operand wraps as a
  singleton ONLY when PROVABLY scalar (ListShapes.definitelyScalar —
  literals, scalar casts, non-list-valued plain scalar subqueries);
  unknowable shapes stay unwrapped. The intermediate unconditional
  asList wrap collapsed 129 tests out of the h2 compare (measured,
  bisected, replaced) — wrap-by-proof, never wrap-by-doubt.
- Full corpus GREEN incl. testOptionalLimit_WithValue; h2-exec
  text-matched 309→320 (floor ratcheted); PCT all suites unchanged;
  core 4166/0. Census unchanged (1021/267 — the counted events are
  data-single filters, not provably-single; they wait on the
  synthesis/producer legs), but the SHAPES the provable rule touches
  are now honest and 11 more SQL texts byte-match engine goldens.

## toOneJoinEquals DELETED (2026-08-20 night — the type-lie side-channel replaced by honest provenance)

The fake-[1] toOne wraps on correlation-equality operands (8 call sites,
not 3 — the first grep truncated) existed only to defeat the null-safe
equality arm. Replaced wholesale:
- The correlation mints stamp their filters `TypedFilter.Stamp.CORRELATION`
  (the enum already existed for WHERE-conjunct order — provenance and
  ordering now share one honest channel).
- `Lowerer.filter` enters the verbatim-equality scope on that stamp; the
  ThreadLocal covers nested lambdas, so the two-join EXISTS construct
  inherits it without restructuring the exists-chooser.
- The two-join midRel MIXED user + correlation conjuncts in one filter —
  split into LAYERED filters (user layer NONE under a CORRELATION layer;
  the Substitution membership idiom), preserving the per-conjunct
  precision the old wraps had.
- toOneJoinEquals, isOptional, toOneFn deleted. (One deletion mishap —
  a slice swallowed two adjacent methods — was caught by the compiler
  and restored from git surgically.)

Full corpus exit 0 first measurement; h2 320/632/0-diverged identical;
core 4166/0; PCT five suites unchanged. Census unchanged (the wraps'
operands were column reads — unknowable, never counted); the win is
architectural: one type-lie helper gone, the C2 provenance channel now
EXISTS and is exercised, and Substitution's pre-existing CORRELATION
mints now get verbatim equality (engine @join semantics) uniformly.

## C5 ADJUDICATED (2026-08-21): instrument frame-conflation, not a code lie

The one MANY-STAMP/SCALAR-SHAPE event ($result.values.active →
u_map__active, testGet): the [0..*] stamp describes the pure COLLECTION
value; the scalar Cast is the PER-ROW lowering frame of the same read
inside a projection. Both are correct in their own frame — the census
fires because it compares across frames. Refinement queued: the
invariant flip must carry frame awareness (per-row lambda contexts
compare the ELEMENT stamp, not the collection stamp) before census-zero
is meaningful. No code change.

## Union leg decomposition (2026-08-21, from reading the 7 UnionSynthesis sites)

The protocol-level toOnes are NOT carry-and-merge: their own comments
say "toOne types both threads identically (real read vs NULL cast);
lowering is erasure" — they are TYPE-ALIGNMENT shims making union-arm
projection columns unify at [1]. The honest form is [0..1] alignment via
multiplicity SUBSUMPTION ([1] ⊆ [0..1] — the checker's LUB), deleting
the shims; separately, the C2 census's union events (toOne(x_pk) over
list-collecting reads) live in the RESOLVER's union navigation reads,
where the honest form is [*]-stamped reads + the explicit merge-by-join-
name operation. Both pieces = the union arm-factory redesign leg
(RELATIONAL_FEATURE_MAP), now census-guided. Remaining program order:
arm-factory (C2-union + C3 ride along) → C1 literals → frame-aware
invariant flip → sniffer deletion.

## C1 flip — DIAGNOSIS CORRECTED, parked as stash 'C1-flip-v2' (2026-08-21)

Census 1021→193 (−81%), h2 at the 320 floor, four consumer fixes inside
(flatten cell re-box / IN variant proof-gate / root skip-UNNEST /
REDUCER trial-signal: a reducer registry-miss throws UnfoldableRef —
the Resolution.attempt contract).

**The earlier subAggregation diagnosis was WRONG — both subAgg tests are
PRE-EXISTING committed-ledger failure rows** (verified: pure HEAD fails
them identically; my family-delta compared a run file instead of the
LEDGER — always diff against docs/RELATIONAL_CORPUS.md's own rows).
Hours of resolver-hunting chased a phantom.

**The ONE real C1 regression**: projection::filter::in::H2Test —
`expected Ok, got "Ok" [String vs JsonNode]`. The raw-grid values-reader
synthesizes `[to_json(cell)]` singleton collections (the Any-cell
variant-in-list carrier, itself C1-class-shaped); under flip+re-box the
SQL VALUES stay mathematically identical (flatten([[j]])=[j]) but the
at(0) egress DECODE (JsonNode→String, Executor.decodeAny) stops firing —
LOCALIZED WITH GROUND TRUTH (same night, egress trace):
on HEAD the value exits via Executor.cell() with anyRoot=true (sqlType
JSON) and decodes; under the flip NO cell() call fires — the at(0)
evaluation egresses TABULAR instead of Scalar, and the tabular/flatten
route has no JsonNode decode arm. TWO candidate fixes, pick by reading
the result-shape decision fresh: (i) the SHAPE — a [0..1]-stamped at(0)
root must egress Scalar (find why the skip-path plan flips the frame
kind); (ii) the chartered decode — a JsonNode ELEMENT under an
Any-element context decodes at the ONE-CARRIER flatten (the same
argument audit 22 accepted for cell()'s arm). (i) is the honest
default; (ii) only if the tabular shape is itself correct. Parked as
stash C1-flip-v3.

MASKING TRAP #2 (cost hours): never compute family regressions by
diffing two RUN FILES — one may be from a mid-experiment jar. The
committed docs/RELATIONAL_CORPUS.md rows are the only baseline.

## C1 LANDED (2026-08-20, fix (i) — emission made stamp-honest)

The in::H2Test defect, dissected fresh with LL_DUMP_RESOLVED +
LL_TMP_SQL: the raw-grid values-reader's cell is a TypedCollection
(one Any [0..1] property read) STAMPED [1..1], but the heterogeneous
Any-LUB collection arm (Lowerer.scalar, fires BEFORE the generic C1
singleton rule) kept the `[to_json(cell)]` list box unconditionally —
so the two stamp-TRUSTING guards the flip added (root skip-UNNEST,
values-reader re-box) read a list where the stamp promised a scalar,
and at(0)'s list_extract returned `[json]` instead of the json scalar
(the "String vs JsonNode" symptom; the earlier "egresses TABULAR"
framing was the same desync seen from the egress side). Fix: the
Any-LUB arm honors the C1 singleton rule too — a scalar-stamped
singleton lowers to `to_json(element)` (the VARIANT carrier stays:
an Any-position scalar is self-describing JSON, cell()'s anyRoot
contract; only the box goes).

CONSUMER AUDIT (gate 1, same session — the corpus alone was NOT the
audit; the core suite caught 4 semantic consumers + 2 shape guards):
- minus: real pure NEGATES a size-1 collection (interpreted Minus.java
  case-1 seeds 0; compiled CompiledSupport.minus case-1 → unary; PCT
  testSingleMinus pins minus(1) == -1 and auto-collection makes
  minus(1) ≡ minus([1])). AuditRound3Test.singletonListReductions had
  pinned a first-element-seed 1.5 NO reference runtime implements —
  test corrected, rule untouched (the flip made it right).
- TypedFilter/TypedFold value arms: scalar-stamped sources now conform
  to the list contract BY EMISSION (ListShapes.asList reads the stamp).
- indexOf: dispatch moved from the type+ArrayLit sniff to the RESOLVED
  CALLEE's declared param mult ([*] set → list search, [1] str →
  substring). Fallout ruled on: ['a'] is String[1], so resolution picks
  string::indexOf (real pure same), whose platform convention is the
  engine's 1-BASED locate() (ledgered divergence) — the old test's 0
  rode the sniff (same callee: 0 for ['a'], 1 for 'a'); corrected to 1.
- CodeShapeGuardrail file/method limits: paid with REAL splits, not
  allowlist rows — MixedEncoding.java extracted from Scalars (the
  MixedElems two-channel family, ~200 lines, zero external callers);
  scalarRelationalArms split at an arm boundary into
  scalarValueTailArms (the documented chain pattern).

Landed state: full corpus GREEN (scoreboard rewritten byte-identical
except the subAgg ERROR row's message text — the REDUCER trial-signal
wording; status unchanged, pre-existing failure); census 1,021 → 191
(190 ONE-STAMP/LIST-SHAPE + the 1 frame-conflation C5 event, which
waits on the frame-aware flip); all five PCT suites unchanged; full
gates chain green. Remaining program order (unchanged): union
arm-factory (C2-union + C3) → frame-aware invariant flip
(LL_STAMP_COUNT becomes a failing gate) → sniffer deletion.
KNOWN RESIDUE (pre-existing, out of C1 scope): the minus LIST arm
(LIST_REDUCE, first-element seed) is wrong for a RUNTIME size-1
many-stamped list — real pure would negate; needs a len()-guarded
CASE when the sniffer-deletion leg rebuilds these arms.

## FRAME-HONEST COLUMN STAMPS (2026-08-20, C2c producer fix — census 191 → 92)

The census's biggest surviving class was NOT resolver union synthesis:
dissected with LL_DUMP_RESOLVED on the union witness, the toOne(x_pk)
LIST-collect events are the ASSERT-side rows.get('COL') desugar — the
Typer minted toOne([1..1]) around a column read that lowers as the
whole-column LIST collect. Root cause one level down: the property-
access rule composed a standalone relation's mult like a scalar
object's ([1..1] relation ∘ [0..1] cell = [0..1]) — the frame
conflation baked into the NODE. Three changes, all producer-side:

1. **Frame-honest column stamp** (Typer.accessProperty): a column read
   whose receiver ROOTS at a row variable (walk through navigate slots
   + from()) or at a call whose RESOLVED CALLEE returns a naked type
   variable (real pure's own signature line — lead/lag/first/nth/at
   return T = a ROW; filter/project return Relation<T>) keeps the
   per-cell mult; a STANDALONE relation receiver stamps the read
   [0..*] — the auto-mapped cell collection it lowers as. First cut
   (instanceof TypedVariable) broke mapping navigate slots
   (calendarAggregations) and window offset chains (lead($r).id, 21
   core tests) — the root-walk + TypeVar-return rule is the honest
   line, and the core suite was again the audit the corpus alone
   could not be.
2. **TDS getter desugar** (rows.get('COL') over a standalone
   relation): the engine getter is TDSNull-TOTAL and COUNT-PRESERVING
   (sqlQueryMerging pins ['8',^TDSNull(),'8',^TDSNull()]; a declared-
   [1] property still yields NULL cells through union threads), and
   TDS cells carry the STORE's kind, not the model's (p3:String[1]
   over an INT column asserts 2222). Desugar = explicit map whose
   per-row body is if(isEmpty(cell), 'TDSNull', toOne(cell)->cast(
   @Any)) — every frame honest, toOne inside the guard, the Any
   upcast puts the whole if on the variant carrier.
3. **Any-LUB if discipline** (MixedEncoding.lubCase): an Any-LUB if
   with differing branch kinds rides TO_VARIANT on both branches (a
   raw CASE cannot even type: 'TDSNull' vs INT32); NULL stays bare.

Guardrail splits paid en route: Typer.tdsGetterDesugars +
Typer.tdsColumnsMetaRead extracted (both parents were over 250);
MixedEncoding grew lubCase. Corpus green ledger-byte-identical, core
4166/0. Census 92 = resultSourcing 54 + dataType 18 + groupBy 10 +
tdsRestrictDistinct 8 (C3) + tails — the union-family events are GONE
without touching UnionSynthesis: the shims' [1..1] alignment lies
remain (invisible to the census; the arm-factory leg still owns them),
but the C2c producer class is closed for union/milestoning families.

