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

## AGG-STRIP + ENV FRAME BIT (2026-08-20, census 92 → 64)

**toOne agg-strip LANDED** (ListShapes.aggStrip, the C2 key insight):
a toOne whose operand SQL is the EXACT plain `(SELECT LIST(col) FROM
…)` single-projection non-distinct no-groupBy shape strips the collect
— SQL's native scalar-subquery semantics IS pure's checked toOne (>1
raises, 0→NULL engine-noOp flow). First full-corpus measurement
regressed milestoning −4 ("More than one row returned") — NOT fake-
conformance toOnes but the TYPED-getter cousins of C2c: `let data =
$result.values.rows; $data.getInteger('id')` — a LET-BOUND relation
variable, which rowRooted's variable arm misread as a row frame.

**Env frame bit**: Env now records HOW a name was bound — withRow
(lambda parameters: the per-element/per-row frame; Typer both lambda
scopes + FoldChecker elem/acc + MatchChecker case vars + EvalChecker)
vs with (lets, function params: value bindings). rowRooted consults it:
a variable root is a row frame ONLY when lambda-bound. rowCellRead's
collection-frame arm now decides SEMANTICALLY (relation-typed receiver
+ !rowRooted → auto-map the typed getter per row), replacing the
syntactic .rows-marker spelling test. This bit is the frame-awareness
the invariant flip needs — built early, at the binding site.

Census 92→64: dataType 18 + groupBy 9 + qualifier 2 CLOSED by the
strip; milestoning/union/dataType families at zero. Remainder 64 =
resultSourcing 54 (synthesized conformance ride-throughs — the
conformToOne partition leg, sized in C2's plan: TWO synthesis layers +
89 recognizer sites to audit, a full slice) + tdsRestrictDistinct 8
(relation-VALUE carrier, frame-designed — flip adjudicates) +
boolean C5 1 + groupBy 1. PCT C3 dissection: TypedDistinct/Sort 24 =
the same relation-value carrier; List<T> instance carrier 7 + struct
ctor 10 + empty sort/reverse 4 = designed carriers for the flip's
(stamp, carrier) table, NOT code lies.

## CENSUS ZERO + INVARIANT FLIPPED (2026-08-20 — the program's goal state)

The resultSourcing 54 dissected with a one-shot funnel stack trace:
NOT synthesized conformance — USER-written `Firm.all()->toOne()` in
query LETS (the AlloyOnly resultSourcing family), lowered at the
query-let seam. C2 class (a): the CHECKED EXTRACT
(ListShapes.checkedExtract — len>1 raises pure's "Cannot cast a
collection of size N to multiplicity [1]", 1 extracts, 0/NULL flows
NULL) — the agg-strip's exact semantics for definite LIST-PRODUCING
CALL operands. UNLIKE the reverted blanket unwrap this landed
corpus-green ledger-byte-identical: the synthesized operands the
blanket broke were healed FIRST (C2c + frames). The conformToOne
partition leg is thereby RETIRED UNBUILT — after the producer fixes,
zero synthesized ride-throughs remained countable. Carrier ratchet
LIST_ 137→139 (justified in-file; the checked-narrowing semantic node
re-absorbs both sites when built).

Last code lie: sort/reverse over <=1-stamped operands minted list
carriers ("stamps are unreliable here" — the rule's own comment);
stamp-read identity arms replace the sniff ([0..0] included: the
isToOne helper is upper==1 EXACTLY and missed empties).

**DESIGNED (stamp, carrier) TABLE** encoded in the instrument (each
row an adjudication, not a suppression): (1) RELATION-typed nodes —
the scalar stamp describes the relation VALUE, the collect SQL its
row-collection carrier; (2) TypedNewInstance + ArrayLit — the
struct/canonical-layout carrier; (3) platform List<T> — one OBJECT
carried as the SQL array; (4) many-stamped property reads with scalar
SQL — the per-row frame (C5). With the table: corpus census 0, PCT
census 0.

**FLIPPED**: StampCensus.check now THROWS on any provable lie —
always on, no env gate (LL_STAMP_COUNT=1 downgrades to the print
census for measurement sweeps). Full gates chain GREEN with the
throwing invariant live (core 4166 exercises lowerings no corpus test
reaches). The type system can no longer lie silently.

**NEXT (the deletion leg)**: the compensation the invariant obsoletes —
ListShapes' runtime shape-sniffing arms, the ~29 isToOne consumer
guards, the ArrayLit-escape hatches in the reduction rules (fix the
minus runtime-size-1 residue at rebuild), each deletion gated by the
live invariant + full chain. Then canonical-render byte-compare
verdicts (parked-until-zero: UNPARKED), then the burn-downs (corpus
243 rows, PCT ~25 reducible).

## DELETION LEG slices 1–4 (2026-08-20, invariant-gated)

- Slice 1 (4a627f81): the reduction rules' !(ArrayLit) escapes —
  and/or/plus/times/minus dead by type argument (to-one Number/Boolean
  carries no designed ArrayLit; anything else throws at the funnel);
  sort/reverse escapes deleted for the STRONGER reason: for the one
  carrier that can arrive (a to-one List<T> OBJECT), the identity arm
  IS pure semantics and the escape picked the wrong arm.
- Slice 2 (f6305c7a): 15 more identity-arm guards family-wide —
  sum/average/median/mode, first/head/last/at/list/removeDuplicates/
  tail/init, corr/covar side-wraps. Scalars ArrayLit sites 24→8; every
  survivor is a STRUCTURAL literal pattern-match, not stamp
  compensation.
- Slice 3 (bd1e9ab1): listArg/concatSide/asList wrap BY STAMP — the
  ArrayLit passthrough flattened designed to-one carriers into
  enclosing lists (wrong pure semantics), NULL-as-empty stays.
- Slice 4: makeString wrap-by-stamp RE-MEASURED under the live
  invariant — the h2 floor caught it (320→301) and the run was
  reverted with a "dialect capability" diagnosis. **THAT DIAGNOSIS WAS
  WRONG** (corrected slice 6, user push: "burn the whole thing to
  zero"): the per-test verdict roster (target/h2-verdicts.txt, built
  for exactly this) attributed the drop to the rowCells makeString arm
  DOUBLE-ENCODING (it pre-built an ArrayLit and delegated to the
  generic rule — two owners for one encoding), plus isRowCells
  over-matching hand-written cell lists once the honest getter desugar
  stopped toOne-wrapping [1..1] cells (the wraps had been an
  ACCIDENTAL provenance marker; testHashFunctions witness — the
  engine's append-form spelling lives in EngineStyleH2.joinStringsFlat
  keyed on the STRING_AGG shape).

**ListShapes end state ruled**: the file DISSOLVES — its founding
thesis is dead (header rewritten). The shape provers
(listShaped/definitelyScalar/listValuedSubquery) become StampCensus's
private evidence procedures; aggStrip/checkedExtract fold into the
checked-narrowing semantic node when built; the stamp-read wrap
helpers move to their consumers. THREE surviving frame dispatches
(ScalarStats reducer-vs-list, sort's per-row-frame wrap, the IN
variant harmonization) are designed-pair-#4 residue — they delete when
lowering carries explicit frame context, not before.

## SLICE 6 — BURN-TO-ZERO (user: "no way to abuse"): the wrap-by-proof holdouts FALL

- **Per-test h2 verdict roster** (H2Verify.VERDICT_ROSTER →
  target/h2-verdicts.txt, unconditional every sweep — the
  query-histogram idiom; sort site listed in the HarnessDiscipline
  charter): floor moves are now attributable by diffing two files. A
  first attempt at this instrument used an env-gated print placed in
  TEST sources because the observability guardrail scans only main —
  the user caught it; the getenv hack was reverted and the lesson
  recorded ([[guardrail-intent-binds-not-letter]]).
- **makeString rowCells arm rebuilt**: statically-enumerated cells
  join as a STATIC CONCAT interleave (ValueCollections.rowCellsJoin) —
  no list machinery, no delegation, ONE owner. isRowCells now requires
  the FULL row-column roster in order (the $r.values synthesis
  signature) — hand-written cell lists take the engine joinStrings
  channels (the old toOne wraps had carried that distinction by
  accident).
- **makeString wrap-by-stamp LANDED** (the 129-test-collapse claim and
  the slice-4 dialect claim both retired by measurement): h2 floor
  exact at 320/632, verdict roster BYTE-IDENTICAL to baseline.
- **IN harmonization stamp-read LANDED**: !isToOne(RHS) replaces
  !definitelyScalar(RHS).
- **definitelyScalar has ZERO production consumers**; listShaped has
  TWO (ScalarStats, sort per-row fallback — the genuine frame
  dispatches, pair #4). Remaining abuse surface = pair #4's
  property-read skip in the invariant + those two dispatches; both die
  together when the map-binder/aggregation channels model the per-row
  frame explicitly (the rows.get auto-map precedent).

Slice 5 (8625eeab): minus NEGATES a runtime size-1 collection
(LIST_LENGTH=1 guard → 0−l[1]; both reference runtimes; the last
recorded C1 residue). Deletion leg's UNBLOCKED portion complete —
remainder gated on the h2 list-encodings and frame-context legs by
design.

## NEXT LEG CHARTER: canonical-render verdicts (HOST_LOGIC_AUDIT fix queue 3–4, UNPARKED)

The ratified design (*compiler knows, database computes AND
serializes, harness compares bytes*) now stands on enforced-true
stamps. Slice order:
1. **Compile-through equality** (queue #3, the headline): the K-arm
   lowers `equal(e,a)`/`eq` to ONE SQL boolean when both operands are
   DB-expressible; static cross-kind folds to constant FALSE at
   compile time; float emission gains the `NOT isnan` guard; failure
   messages fetch DB-rendered reprs ON FAILURE ONLY. PureAsserts
   equal/sorted/repr arms shrink to the definitional residue
   (identity/compile-time facts). The verdict-channel ledger rows
   SHRINK — that shrinkage is the leg's own census.
2. **Grid compile-through** (queue #4): GridCompare.grids ordered →
   row_number zip; multiset → `(a EXCEPT ALL b) UNION ALL
   (b EXCEPT ALL a)` empty; tdsEquivalent deltas → per-cell
   `abs(x−y) <= delta`; ONE boolean fetch each.
3. **Tolerance census re-read** (queue #5) after 1–2 land.
Prereqs all in place: stamps enforced (082f8692), guard-emission
probes green both backends, NullSemantics/verbatim channels honest.
Measure every slice by the verdict-channel register + full chain; the
canonical-serialization spelling table is COMPILER metadata (the
audit's isVerdict ruling), never host formatting.


## SLICE 8 — ListShapes DELETED; the carrier table becomes EXPECTATIONS

Pair #4 fell first (slice 7, a596dc85): its ONE producer was
scalarMapAsProject copying the collection multiplicity onto the
synthetic u_map__ COLUMN while each row holds one cell — the column now
declares the per-cell mult, the invariant's property-read skip is
deleted, ScalarStats and sort's fallback dispatch on STAMPS, and
group-agg lambdas were verified to route around the Scalars rules
entirely (Aggregates.reducerFor owns them).

Then the dissolution:
- **ListShapes.java DELETED.** Its founding thesis ("stamps are
  unreliable, decide by shape") died at the flip; now the file follows.
- LIST_PRODUCERS → SqlFn.producesList() (function METADATA on the
  enum, outside the pre-dialect ban zone); listShaped +
  listValuedSubquery → StampCensus private (the invariant's own
  evidence); aggStrip/checkedExtract/concatSide → Scalars private;
  asList/thunkBody → PureSql; definitelyScalar + listArg were DEAD
  (the invariant carries its own stricter prover) and are gone.
- **The three designed-carrier rows converted from SKIPS to ONE-ARM
  EXPECTATIONS** (user: "why does it have exemptions?"): each row now
  states how ONE value of a composite type is represented (relation →
  row-collect or cell collapse; ctor → canonical-layout ArrayLit;
  List<T> → the SQL array) and satisfies ONLY the scalar-stamp/
  list-shape arm — a MANY-stamped composite with provably-scalar SQL
  fires like any other lie. No node class is ever unexamined.
- Carrier ratchet pins TIGHTENED same-commit per its charter:
  ArrayLit 38→34, SqlFn.LIST_ 141→129.

END STATE REACHED: zero shape-decision consumers in production, zero
invariant skips, one positive carrier table the enforcer CHECKS, and
no mechanism left to abuse. Shape classification exists only as the
throwing invariant's private evidence.

## AUDIT BURN (user: "burn them all down") — D1/D4 LANDED, D2/D3 CHARTERED BY MEASUREMENT

The deep-audit debts, attacked in risk order:
- **D4 LANDED**: ONE stamp authority (lowering/Stamps — toOne preserved
  VERBATIM for reduction identity arms vs atMostOne for collection
  ops/frames; the refactor caught its own near-bug: blanket-widening
  would have broken reduction EMPTY-IDENTITIES). DISCOVERED FORK
  recorded in Stamps: runtime-empty [0..1] reductions already yield
  NULL where pure defines an identity (and([])=true, joinStrings([])=
  '') — pinned nowhere, owned by the PCT lane. C1 collapse predicate
  stated once (ValueCollections.c1Singleton).
- **D1 LANDED**: SqlExpr.CheckedOne — checked narrowing as ONE semantic
  node, spelled by the DIALECT layer (execution = pure's size guard;
  engine-TEXT = verbatim processNoOp view); Scalars.checkedExtract
  deleted, the ratchet's re-absorption promise fulfilled (129→127).
  The subquery-collect strip's DB-native message stays: engine-verbatim,
  corpus-pinned — spec, not debt.
- **D2/D3 MEASURED, then CHARTERED (not half-landed)**: the explicit
  auto-map probe (accessProperty emitting map(recv, v|$v.prop) for
  many class receivers, mirroring the derived arm) costs 11/4166 core
  and **~65 corpus regressions across 16 families** (milestoning,
  association, inheritance, graphFetch, sqlDialectTranslation,
  executionPlan…) — the resolver's ~115 chain-match sites are
  load-bearing across the drop-in surface. Same evidence shape that
  rightly killed the blanket toOne unwrap: this is a per-walker
  MIGRATION ARC, corpus-refereed per slice. GROUNDWORK KEPT:
  ValueCollections.autoMapHop is THE canonical single-hop link reader,
  and pathOf is the first migrated walker (it now also reads
  user-written map hops as path elements — strictly more correct
  today). The migration's definition of done: every chain walker
  consumes hops through autoMapHop, the Typer flips to explicit
  emission, rowRooted's TypeVar heuristic DELETES (the frame becomes
  syntax), and Row-vs-Relation conflation resolves with it.

## D3-CLASS CLOSED AS NOT-A-DEBT (measured re-derivation, user session "lets do it")

The class-side explicit-auto-map migration was EXECUTED far enough to
learn the truth, then reverted on the evidence:
- Flip + pathOf reader: 11 core failures → the resolver-ingress
  chain adapter (map→chain at the front door) healed ALL core (4166/0)
  and cut the corpus delta 65→38.
- The surviving 38 live in channels OUTSIDE the resolver's front door
  (assert-verdict machinery, plan-text, the qualifier inliner) —
  finishing means teaching every typed-tree consumer the new spelling
  OR normalizing the maps away post-typing, which makes the flip a
  no-op with extra steps.
- DECISIVE: the witness dumps show class-side stamps were ALREADY
  honest pre-flip — accessProperty's compose rule IS pure's dot-rule
  (source × member multiplicity), C2c made it frame-aware, and the
  invariant verifies its output. The flip added ZERO invariant payoff.

RULING: the class-side representation duality costs nothing observable
— compose is the auto-map, stamps are true, the enforcer checks them.
Purity with no payoff priced at 38-test regression risk is refused,
the same way the blanket unwrap was. KEPT (correct regardless):
TypedMap.singleHopProperty — the canonical single-hop reader, now a
public fact on the node — and pathOf reading user-written map hops as
path elements. The REAL successor arc is unchanged and now precisely
scoped: Row-vs-Relation typing (one RelationType currently means both
"a row" and "a table" at the same multiplicity — the ONLY remaining
place a stamp is genuinely ambiguous, and the detective's actual home).

## SUCCESSOR ARC CHARTER: the Row-vs-Relation split (read-only sizing, 2026-08-20)

THE last genuine ambiguity: one Type.RelationType means BOTH "a row"
and "a whole table" — [1..1] cannot distinguish one row from one
table, rowRooted-the-detective exists to guess it, and its TypeVar
blind spot ([tds1,tds2]->first().col misread as a row frame) is
unfixable inside the current model.

KEY ALIGNMENT FACT: the pure signatures we ALREADY PORT write the
distinction — lead<T>(w:Relation<T>[1], r:T[1]):T[0..1] — container
(Relation<T>) vs element (T). Our kernel ERASES it: T and Relation<T>
resolve to the same RelationType. The split RESTORES what the
signatures declare; rowRooted's heuristic then becomes exact by
declaration and DELETES.

Measured radius: 121 `instanceof Type.RelationType` sites across ~30
files, concentrated: InferenceKernel 24 (the generic binding machinery
— the heart of the change: T binds RowType, Relation<T> the
container), Lowerer 23 (row/relation arms), Typer 11 (row-var scopes,
accessProperty frames), StoreResolver 7, + ResultShape/Executor
carrier decisions. Each site answers one question: row, table, or
legitimately both.

Probe-first like everything else: introduce Type.RowType, teach the
kernel to bind Relation<T> signatures container/element, let sealed-
switch exhaustiveness + the full suites enumerate the fallout, slice
by the evidence. DEFINITION OF DONE: rowRooted deleted (both the env
frame-bit consultation for relation receivers and the TypeVar
heuristic), the per-row/collection frame read off the TYPE, and the
Env frame bit retained only for genuinely lexical facts (lambda-vs-let
binding kind).

ADDENDUM (user challenge, accepted in part): the D3-class ruling holds
for STAMPS (not-a-debt) but UNDERCOUNTED one real payoff — the
resolver handles user-written maps and sugar chains through SEPARATE
arms (autoMapRead exists to convert one to the other): duplicated
logic, divergence risk. The correct fix is a PATH-VIEW API (matchers
consume the hop sequence as an abstraction both spellings satisfy;
autoMapRead and the dual arms DELETE) — not the rewriting adapter.
Sequenced AFTER the Row split (map-over-relation lambdas become
Row-typed by declaration first; the sites barely overlap, bundling
would entangle two deep migrations). Tracked as its own arc:
PATH-VIEW UNIFICATION.

## ROW-VS-RELATION SPLIT — KERNEL LANDING (user: "do it")

Type.RowType EXISTS: one row, distinct from the table. The kernel
stopped erasing the distinction the pure signatures always declared —
T in Relation<T> binds the ROW (RowType), Relation<T> resolves back to
the TABLE (bare RelationType over the row's schema). G-alpha now
round-trips instead of erasing.

The migration ran in EIGHT principled rings, 1,323 -> 0 core failures
plus the corpus's getter ring, each ring ONE systematic rule:
1. accessProperty RowType arm — per-cell stamps BY TYPE (the
   detective's replacement for kernel-typed rows).
2. ⊆ subset RHS accepts a row's schema.
3. Transitional binding coherence: RowType(cols) and RelationType(cols)
   are one binding (ROW form wins; deleted when all producers speak Row).
4. Relation<T> resolves to the TABLE view of the bound row (the
   round-trip — healed 460 in one line).
5. Schema-algebra operands contribute row SCHEMAS (left AND right —
   the right-side silent drop was the join 4-vs-2 witness).
6. ResultShape/Executor: a ROW root is a one-row TABLE at the boundary.
7. relationRow presents a row's schema (bare row-struct spellings in
   declared signatures accept row actuals).
8. The TDS getter surface (tdsReceiver): getters serve relations AND
   rows — the ROW case stated by type (h2 floor back to exactly
   320/632; groupBy agg-lambda getters were the witnesses).
Two kernel tests updated from pinning the ERASURE to pinning the SPLIT.

Landed green: core 4166/0, corpus zero regressions ledger-byte-
identical, h2 floor exact, PCT all suites, full gates chain.

NEXT SLICE (the detective's actual deletion): resolver-built navigate
slots still type bare RelationType where they mean rows — move those
producers to RowType, then rowRooted DELETES (its env consultation for
relation receivers and the TypeVar heuristic both), the frame read off
the type everywhere.

## MODEL B — THE REFERENCE-FAITHFUL RE-ORIENTATION (user: "are you
## sure the shortcut you took is the right thing? ... do it")

The RowType split was audited before landing anything on top of it and
REVERSED IN ORIENTATION. Unbiased finding, user-ratified: real pure has
no RowType — the bare struct (metamodel RelationType) IS the row/schema
(the T of Relation<T>), and a TABLE is ALWAYS the wrapped
GenericType(Relation, [schema]). My split had kept the inverted meaning
(bare = table) because 121 sites assumed it and had invented RowType
for rows — two distinct types (conflation gone) but BACKWARDS relative
to the reference: every future type-rendering leg (canonical-render
verdicts prints engine text), every signature port, and every reader
would pay a permanent translation layer, and two transitional shims
(binding coherence, the late-bound row guard whose absence stack-
overflowed) existed only because of the inversion.

MODEL B, landed: bare Type.RelationType = the SCHEMA STRUCT; as a
value, ONE ROW (pure's pun — schema IS row type). A table =
Type.relation(schema) = GenericType(Relation, [schema]), preserved
through resolution — THE G-ALPHA ERASURE IS DELETED, NOT INVERTED.
Type.RowType DELETED. Both transitional shims DELETED (RowType.of
guard, kernel coherence). Env's row-param bit DELETED — binding kind
carries nothing the type doesn't. NavigateChecker's unify-rebind hack
replaced by the explicit special-form b.bindType (JoinChecker's own Z
idiom). .rows typed as the ROW COLLECTION (engine TDSRow[*]: bare
struct, many stamp) — at(0) over rows is a row BY TYPE, the getter
auto-map/per-cell frames dispatch on Type.relationValued (wrapped, or
bare+many) with ZERO tree walking.

ONE OWNER for the representation: Type.relation / Type.isRelation /
Type.relationSchema (wrapped-only reader) / Type.schemaView (wrapped-
or-bare) / Type.requireRelationSchema (loud pipeline read) /
Type.relationValued (relation-rooted value BY TYPE+STAMP).

Migration mechanics: ~200 sites over 33 files classified table-vs-row
(mints wrap; schema/row sites stay bare), suites as referee in 8 rings
(297 -> 90 -> 37 -> 13 -> 0). TRAP RECORDED: silent-fallback keying
(`if (!(x instanceof RelationType)) return default;`) DEGRADES instead
of failing when the spelling flips — RenameChecker's position-
preserving rebuild silently fell back to append-order; Compiler.wire-
Schema fell back to the value-column; ExecutionResult.envelopeCarriers
misclassified TDS as splat. Every such site now reads through the
owner helpers. Second trap: FQ-spelled `instanceof com.….Type
.RelationType` hid ~40 sites from the short-spelling grep — census
both spellings, and multiline `instanceof\n Type` needs an awk pass.
Ledger pins bumped with justification (type-spelling growth, zero new
evaluation); Lowerer split Sorts.java out whole (real split, the
Pivots collaborator pattern) to return under the 3500 cap.

Core 4166/0. Corpus/PCT/gates: recorded below at commit.

### Model-B corpus endgame (rings 9-14) and the stale-oracle rediscovery

The full-corpus referee ran through SIX more rings after the unit
suite went green (297→90→37→13→0 unit rings; then 70→23→1→0 corpus
deltas vs a same-day HEAD baseline A/B):
- StoreResolver's anchored dispatch: per-cell reads over ROW-typed
  picks and maps over the rows view resolve STRUCTURALLY
  (schemaView/relationValued, not wrapped-only).
- .values over a PICK-rooted row is IDENTITY (wire flatten keeps
  TDSNull; the cells channel is for lambda ROW VARIABLES only — a
  lexical binding fact; the collection channel's null-drop plus the
  lower-bound honesty guard caught the difference).
- lower()'s ROOT dispatch: any schemaView-carrying root (table, rows,
  one row) lowers through the relation pipeline (matches ResultShape).
- NavMaterializer: a FOURTH spelling escape — casts written
  `(com.legend...Type.RelationType)` over a LINE BREAK evaded every
  census pattern; three pipe casts → requireRelationSchema, three
  TypedJoin infos wrapped.
- zipPairProject's ExprType.one(row) — the ExprType.one(...) mint
  spelling was a FIFTH census gap.
- Scalars.isClassish counted ANY GenericType as an instance kind — the
  wrapped relation made containment statically FALSE (exists family,
  8 tests). Relation types are excluded by name.
- ValueCollectionOps recognizers + DistinctChecker + Args.outputColumns
  accept the bare rows view (schemaView/relationValued).

THE BLOCKING SCARE THAT WASN'T: the full corpus first showed 19
regressions PLUS an h2-floor drop — and a HEAD checkout showed the
IDENTICAL failure set, as did the very commit that wrote the ledger.
Controlled A/B (same oracle both sides) kept the migration honest
while the "drift" was chased — and it was the RECORDED trap:
$HOME/legend/legend-engine is the STALE checkout; the ledger's
baselines come from /Users/neemsandv/legend. Against the correct
roots: G4 zero regressions, scoreboard byte-stable, ALL EIGHT GATES
GREEN (G1 4166/0, G4 DuckDB corpus, G5 h2, G6/G7 PCT, G8 parser).

## MULTIPLICITY AUDIT ADOPTED — RETRACTION AND THE NEW PROGRAM

docs/MULTIPLICITY_AUDIT_2026_08_20.md (independent deep audit,
evidence rule: code and execution only) is ADOPTED in full. Its
verdict stands: the invariant is real and was never weakened, but it
is a CONSISTENCY check, not soundness — lowering picks SQL shape FROM
the stamp, so a wrong-but-propagated stamp fires nothing — and the
stamps are wrong upstream on the most common shape in real Legend
code. RETRACTED accordingly: every end-state sentence in this document
claiming "the type system can no longer lie silently" or reading
census-zero as a guarantee. The honest claim is the hedge: absence is
not proof of health, but firing IS proof of a lie.

Every audit finding was spot-verified against the post-model-B tree
before adoption: unifyMult has no lower-bound comparison (its "engine
convention" comment is FALSE — real pure MultiplicityMatch.java:273
rejects [0..1]->[1]; that is why toOne exists); MatchChecker hardcoded
[0..1] beside its own unused widen(); Stamps.exactlyOne had zero
callers; CheckedOne guards 16/167 functions and navigate-toOne is
DELETED; []->toOne() yields NULL; the empty-identity fork ships wrong
answers and leaks the TDSNull sentinel; 4 endsWith("::toOne") suffix
matches; CI's corpus step assume-skips (vacuous green) and pct never
runs in CI.

THE PROGRAM (audit §8 order, path-view unification re-sequenced after
the foundation): slice 1 = the multiplicity algebra owner (below);
slice 2 = the strict lower-bound flip, probe-first, conform-by-
emission; slice 3 = toOne honest (CheckedOne everywhere, both bounds,
negative fixtures, egress lower bound); slice 4 = the empty-identity
fork; slice 5 = exact-FQN recognizers + honest CI; then path-view.

### SLICE 1 LANDED — Multiplicity.union / Multiplicity.product

ONE owner for the arithmetic (audit §1d: four ad-hoc copies, four
DIFFERENT Var fallbacks — position-dependent answers). Routed: the
kernel's covariant mult-var accumulation, IfChecker (its copy
deleted), MatchChecker (widen() deleted; the §1c HARDCODED [0..1]
fixed — differing arms now UNION, [2]/[1] = [1..2], pinned by
matchRuntimeDispatchUnionsArmMultiplicities), Typer.compose (§1e: the
Var-drop dies — [1] is the product identity, [n].[1] stays [n]; any
other Var meeting is LOUD). Product annihilation edge caught by the
slice's own new pin: [0..0].[*] = [0..0], zero beats unbounded
absorption. MultiplicityAlgebraTest pins all of it (before this slice,
ZERO tests pinned any copy). Prose corrections landed: StampCensus
header states the consistency-not-soundness scope, the false
"production code never consults shape" claim corrected, the Stamps
fork's fictional "PCT lane" owner repointed to slice 4.

### SLICE 2 LANDED — the strict lower-bound flip (audit §1 root cause)

unifyMult now enforces FULL covariant containment — real pure's
MultiplicityMatch ([a..b] into [c..d] iff c<=a and b<=d): [0..1] into a
[1] slot REJECTS, exactly like real pure (that is why toOne exists).
The false "engine convention rejects only [*]->[1]" comment is deleted
with a citation of MultiplicityMatch.java:273-279. Scoring mirrors the
containment (the kernel-halves agreement — and it is HOW real pure
disambiguates [0..1]-vs-[1] overload pairs). Carve-outs each carry
their doctrine: relation sources (§3.2), contravariant function-value
params, the Variant carrier, and — NEW, evidence-based — FUNCTION-VALUE
RESULT slots are lenient on the LOWER bound only (unifyMultResult /
resultMultScore, one owner): the reference's own corpus compiles
sortBy over optional association paths ({T[1]->String[0..1]} against
declared {T[1]->U[1]}). Audit §1b rides along: declared-return-vs-body
now strict (f(a:String[0..1]):String[1]{$a} and f():String[3]{['a','b']}
both REJECT, pinned).

PROBE-FIRST, measured: 68 core failures -> 0 in 8 rings; corpus 356
non-pass -> ZERO regressions, scoreboard byte-stable but for one
standing row's MESSAGE text. The fallout decomposed exactly as
conform-by-emission predicted:
- FALSE REGISTRATIONS exposed: variant navigation get was registered
  (Variant[1], Any[1]) — the REAL get.pure is (Variant[0..1],
  String[1]) + (Variant[0..1], Integer[1]), [0..1] BY DESIGN (nested
  get chains compose). Real [0..1] overload families registered for
  startsWith/endsWith (stringExtension.pure:26/31), the date family
  (year/monthNumber/weekOfYear/datePart/dateDiff — dateExtension), and
  the legacy TDS optional-size limit (tds.pure:394). The lite dyna
  comparisons widened to Any[0..1] (the SQL lane's own nullability,
  mirroring real pure's [0..1] inequality families).
- SQL-LANE EMISSION made uniform: RelOpTranslator's 'position' toOne
  idiom now covers add/sub/concat/hash/adjust/dayOfWeekNumber/splitPart
  AND the generic dyna fallback (COLLECTION operands exempt — the
  stamp invariant itself caught the in-list over-wrap: toOne over an
  ArrayLit fired ONE-STAMP/LIST-SHAPE, working exactly as designed).
  MappingNormalizer's parse-coercion (parseInteger/Float/Decimal over
  VARCHAR columns) wraps its read; the derived-property β-inline
  spells toOne on non-[1] receivers (the engine's no-guard qualifier
  doctrine, ledger cluster 48 — UserCallInliner's local patch is now
  the sanctioned spelling at the source).
- OUR non-compliant test spellings fixed to engine-true pure
  (->toOne() before to-one natives) — the sortBy-directive precedent.
MultiplicityStrictnessTest pins the rejections end-to-end (the audit
counted ZERO such fixtures). All eight gates green, correct oracle
roots.

Slice 3 note: the trust-toOnes this slice emitted at SYNTH sites
(translator, normalizer coercion, qualifier β-inline) are exactly the
provenance-split population — user-toOne becomes CHECKED there while
these stay SQL-lane erasures (the C2 provenance note in Scalars).

### SLICE 3 LANDED — toOne honest: the C2 provenance split + checked bounds

THE SPLIT: user toOne is CHECKED; synthesized conformance spells
meta::legend::lite::trustOne (internal-only; IDENTITY lowering — the
engine's processNoOp / no-guard behavior, finally NAMED). All 22 synth
emission sites converted (translator, normalizer coercions and ctor
wraps, union shims, qualifier β-inlines, getter desugars, json frames);
~63 scattered raw-FQN toOne readers routed through ONE owner
(Pure.isToOneCall — exact FQNs, which also killed three of the
endsWith("::toOne") suffix-matches from audit §7); exec keeps inline
FQNs (invariant 6d).

CHECKED SEMANTICS, with the referee-drawn lane boundary: VALUE-LANE
lists (ArrayLit literals, producesList calls) raise in the DATABASE
with pure's own message — [1,2]->toOne() is now a USER error "Cannot
cast a collection of size 2 to multiplicity [1]" (the audit's crash
case), a runtime-emptied list raises size 0 (BOTH bounds — the old
guard tested only >1), statically-empty [] raises at emission, and
toOneMany is at-least-one checked with a re-carrier for to-one
operands (it was an unconditional no-op). ROW-LANE reads — [0..1]
scalar carriers AND many-stamped correlated collections — FLOW,
ADJUDICATED against audit §3 with the ENGINE as the reference: its
relational compilation of toOne is processNoOp, SQL cannot tell a
NULL cell from an empty, and the milestoned-qualifier corpus row
(testIsolationOfMilestoningFiltersReferencedInAllPartsOfIfStmt, an
ENGINE-authored toOne over an 11-row correlated navigation) is the
witness that raising there diverges from the reference. The same
ruling covers egress: TABULAR keeps the engine's TDSNull-under-[1]
convention; the audit's §3 egress items are ADJUDICATED engine-parity,
not skipped. The [1..*]-lower-bound wall moved INTO the SQL (tenet #1:
the database raises; AuditTier1 pin updated).

Guardrails paid consciously: INTERNAL_DESUGAR 12→13 (trustOne),
ArrayLit ratchet 34→35 (the toOneMany re-carrier), ledger pins
MetamodelSteps/StoreNav (recognition lines), catalog golden +trustOne.
Legacy↔clean-sheet convergence fixtures spell the trust form — the
clean-sheet equivalent of legacy conformance IS trustOne. Core 4181/0;
corpus zero regressions (ledger delta = one wall row's message
spelling); ALL EIGHT GATES GREEN.

### SLICE 4 LANDED — the empty-identity fork is closed

The reduction identity arms split Stamps.exactlyOne (identity — the
value is always present) from [0..1] (COALESCE to pure's empty
identity): and([])=true, or()=false, joinStrings''=  '', makeString=''
— the four audit-§4 wrong answers now give pure's answers, pinned
end-to-end (emptyIdentityForkIsClosed). The TDSNull sentinel no longer
leaks as user data: it remains ONLY the TDS-cell convention of
[1..1]-stamped (trust-wrapped) reads and the many-element arm. The two
adjacent binder bugs die with it: add() carriers its to-one first
operand (the missing asList), and collection::distinct gets the same
to-one guard its synonym removeDuplicates always had. Stamps.exactlyOne
finally has callers. Ratchet ArrayLit 35→36 (the distinct re-carrier,
justified). Core 4182/0; corpus ledger BYTE-IDENTICAL; all eight gates
green.

### SLICE 5 LANDED — exact-FQN recognizers + honest CI

The endsWith("::toOne") suffix-matches were already killed in slice 3
(every reader routes through Pure.isToOneCall — exact FQNs; a user
function named my::customToOne can no longer be hijacked). CI honesty
(audit §6): the workflow's third step invoked the corpus runner
knowing it would assume-skip on a bare runner — a vacuously green
step named "corpus sweep (self-checks vs committed scoreboard)" that
never ran a sweep. The workflow now states its honest scope (core
suite only), and the third step is an explicit ::warning that the
corpus and PCT run ONLY in the local tools/allgates.sh gate against
the baselined oracle roots. The two harness swallow sites the audit
flagged (§6) are recorded as follow-ups on the harness-platformization
program, not silently kept.

## PATH-VIEW UNIFICATION — CLOSED BY MEASUREMENT

The charter assumed a large dual-arm surface (matchers pattern-matching
the sugar chain and the map spelling separately, with autoMapRead as a
rewriting adapter between them). The census says otherwise:
- Substitution.pathOf ALREADY IS the path view — one reader satisfied
  by both spellings (map-lambda flattening, toOne/trustOne
  look-through, milestoned property steps), consumed at 43 sites
  across six resolver files, with the "ONE funnel: scan and
  substitution must not drift" contract pinned in its own body.
- autoMapRead is 30 lines with ONE caller, and it is not an adapter
  between duplicate matchers — it is pure's OWN dot-rule desugaring
  (map.pure grammarDoc) applied once at the resolution boundary so the
  resolution machinery has one canonical form. Deleting it would mean
  re-implementing chain resolution beside map resolution — MORE
  duplication, not less.
- The remaining hand-rolled walkers (~8) have DIFFERENT contracts
  (root-only reads, unwrap-tracking leaf peels, prefix walks) —
  forcing them through one API would contort them for purity without
  payoff, the exact shape of the reverted D3-class adapter.
- The fragility the corpus rings actually exposed lived in the
  TYPE-DISPATCH layer (bare-vs-wrapped relation tests), not the path
  extractors — and model B's owner helpers already fixed it at source.

LANDED: pathOf promoted to the NAMED owner with the full contract
javadoc; autoMapRead's doctrine written on it. RULING: like D3-class,
the feared disease was already structurally cured; the honest close is
the ruling and the naming, not a migration. Any FUTURE matcher that
pattern-matches a navigation spelling instead of asking pathOf is a
review defect against this section.

## ENGINE-PARITY RUNTIME CHECKS (user: "Let's try these two")

The two lanes where the engine was still ahead (its finish-line
resultSizeRange row-count check; its FunctionParametersValidation of
provided parameter values), assessed and executed as slices.

### EGRESS SLICE A LANDED — the finish-line lower bound

The engine's Java executor checks the FINISHED result's row count
against the declared multiplicity (resultSizeRange) — the one
enforcement its in-expression processNoOp lane never performs. Ours
now does the same, at three seams, all row-count-honest:

- **Root toOne over a MANY operand** (`Lowerer#requiredOneEgress`):
  the mid-expression lane strips to the bare scalar subquery (empty →
  NULL, the ADJUDICATED row-lane flow — untouched), but at the
  STATEMENT ROOT the row count is still visible in the LIST carrier,
  so the collapse keeps the list and wraps `CheckedOne`: 0 rows raises
  pure's size-0 cast message, 1 row holding NULL extracts NULL (the
  engine counts rows, not values — the NULL-cell case must FLOW), N
  rows raises size-N with pure's message instead of DuckDB's bare
  more-than-one-row subquery error. Recognizer = `Scalars.aggStrip`'s
  exact LIST-collect shape (now package-private); trustOne
  (synthesized conformance) and [0..1]-stamped operands are excluded
  by design — for the latter a NULL cell and an empty are genuinely
  indistinguishable post-collapse and the engine flows the NULL cell,
  so guarding would FALSELY raise.
- **SCALAR egress** (`Executor` SCALAR arm): zero JDBC rows under a
  declared lower ≥ 1 raises pure's message (distinct from
  one-row-holding-NULL, so the TDSNull convention is untouched).
- **COLLECTION egress** (`Executor` COLLECTION arm): zero JDBC ROWS
  under a declared lower ≥ 1 raises pure's message as a USER error;
  the pre-existing values-below-bound guard (NULL cells dropped past
  the contract) stays as the DEFECT arm behind it. Row count, not
  value count — a row holding NULL passes the bound, matching the
  engine.

Ring recorded: the first attempt put the check ONLY in the SCALAR arm
and the pin didn't fire — the root collapse lowers to
`SELECT (scalar subquery)`, which ALWAYS returns one JDBC row with
emptiness encoded as a NULL cell. The row count had to be preserved in
the SQL (the LIST carrier), not inspected after the collapse erased
it. Second ring: `Pure.nativeKeysAt` returns signature-qualified KEYS,
not bare FQNs — the recognizer is `Pure.isToOneCall` minus its
trustOne member (exact FQNs, never contains/suffix).

Pins: MultiplicityStrictnessTest egress trio (zero-rows scalar raise +
satisfied-promise control + size-2-at-root pure message;
zero-rows [1..*] collection raise + satisfied control).

### EGRESS SLICE B — RULED VACUOUS ON THIS SURFACE (no code)

The engine's FunctionParametersParametersValidation
(missing-mandatory + per-value type validation + stream-size
processing) exists because the engine has an EXTERNAL ingress: HTTP
JSON parameter values that arrive untyped at runtime. legend-lite has
no such seam — `Compiler.execute` takes no parameter map, every
function argument is a statically-typed Pure expression, and the
inliner's β-substitution splices KERNEL-CHECKED typed specs, not raw
values. The pre-adoption framing ("the splice is blind") was WRONG:
after the strict-kernel slice, splice-time size violations would
require a stamp to lie, which is the whole program's invariant. Our
compile-time strict kernel IS FunctionParametersValidation, run
earlier — the "better than engine" lane the user asked about.
`FunctionParametersValidationNode` exists in this tree only as plan
TEXT (PlanText/PlanNode/Pure class def), which the corpus pins
byte-exactly; executing it with no ingress would be host logic with no
call site — a necessity-proof failure. RULING: the validation
semantics land WITH the future ingress that creates the
Java-holds-value moment — the prepared-statements program (JDBC
setObject binding), which is chartered separately and owns injection
safety, statement caching, and wire type fidelity as its payoffs.

## SPLICE-OWNERSHIP LEG (user: "Need to fix the compiler first for sure")

The user's question "Statement Exec does compiler work? (Splice?)"
named a real debt. Census (measured, not guessed): exactly FOUR files
outside the compiler layers construct typed-HIR nodes —
StatementExecutor, AssertVerdicts, exec.RawGridSchema,
validation.DriverPkAppend.

### INVARIANT 7 LANDED — typed nodes are minted only by compiler layers

New ArchitectureTest rule: constructors of
`com.legend.compiler.spec.typed.*` are callable only from
compiler/resolver/normalizer/lowering; READING (pattern-matching,
dispatch) is free everywhere — the executor consumes the tree, it must
not grow it. The four census files are pinned exceptions with written
justifications; the list only SHRINKS, additions need a necessity
proof (a runtime fact that cannot exist at compile time). The rule was
PROBED before trust: removing a pin surfaced the exact 19-site mint
roster with line numbers — which became slice 1's work list.

### SLICE 1 LANDED — the splice rules move home

- `compiler.spec.ResultEnvelopeSplice`: spliceHook's rewrite rules
  (row-count COUNT(1) projection, `.rows` marker erasure,
  columns.documentation fold, envelope size-is-ONE, values-read
  collapse, activities wall, aggAware rewrittenQuery) moved VERBATIM
  behind a `Frames` SPI (`frame(name)` / `inlineExecute(ec, eager)` /
  `aggAwareRewrittenQuery(chain)`). The executor's adapter supplies
  the execution-bound half ONLY: frame lookup, JDBC frame builds, the
  derived print. The compiler owns WHAT a splice means; the executor
  owns WHEN a frame's value exists.
- `UserCallInliner.bindStringParam` + `referencesVar`: the effectful-
  map β-bind folds into the one β-engine's file — the SECOND partial
  β-implementation dies as a species (deliberately still narrow: the
  deep-read wall documents untested positions).
- foldPairProjection's `endsWith("::pair")` fixed to the exact FQN
  (exact-FQN doctrine — read-side, stays executor).
- JavaEvalLedger E4 pin PAID 42→40 (activityEnvelopeRead left the
  file; the Java-side derivation stays on the ledger's radar).

REMAINING in the pin (slice 2): buildFrame's chain ASSEMBLY
(β-expansion of map-built lambda collections, the
concatenateTemporalTdsQueries fold-by-emission, from-attachment — 6
mints) + two staging TypedLets (executeCallStatement,
withQueryLetPrefix — which also instantiates a Lowerer in the
executor). AssertVerdicts (K-arm verdict-query builder),
RawGridSchema (Phase-1c late-bound schema, runtime probe — genuine
boundary), DriverPkAppend (pure tree→tree pass — just MOVE it under
resolver/) each carry their route out in the rule javadoc.

Referee: suite 4185/0, corpus scoreboard byte-identical.

### SLICE 2 LANDED — buildFrame assembly + the last executor mints; pins 4→2

User directive: ZERO exceptions, and ultimately no exception mechanism
at all (the `doNotBelongToAnyOf` clause dies with the last pin).

- `compiler.spec.ExecuteChainAssembly`: buildFrame's compiler half —
  `prepare` (query peel: letBound, β-inline of lambda-building calls,
  preval/withFeatureFlags read-through, concatenateTemporalTdsQueries
  fold-by-emission; lambda + mapping-ref validation) and `chain`
  (body inline, ->from() attachment with XStore chain mappings/JSON
  sources, relation-rootedness). The executor interleaves its
  execution-bound steps BETWEEN the two calls, order-preserved:
  runtime-arg effects, tableReplace recording, the eager run.
- `UserCallInliner.callArgumentFrame`: the staged call frame
  (arguments β-inline and bind as TypedLets; effectful arguments
  refuse loudly — audit 17) joins the β-engine's file.
- `lowering.SeedableLets`: the seedability TRIAL-LOWERING probe (was
  withQueryLetPrefix, which instantiated a Lowerer inside the
  executor — the second bonus find). Guardrails PAID with real
  splits: the Lowerer file-size limit forced the probe into its own
  file (not a limit bump), and its broad catch is reviewed+documented
  at the site with an ErrorShapeGuardrail pin row.
- `DriverPkAppend` MOVED verbatim to `resolver/` — it was a pure
  tree→tree pass misfiled in validation/.
- StatementExecutor now mints ZERO typed nodes — dropped from the
  Invariant 7 pin list; letBound/containsTypedFrom deleted from the
  executor (they moved with the assembly).

Remaining pins (2): AssertVerdicts (dies with the canonical-render
verdicts leg — synthesis becomes compiler-owned emission, the
surviving host half is a byte compare that mints nothing) and
RawGridSchema (route out = staged compilation: a compiler-owned
resolve-with-schema phase taking the PROBED column roster as input;
the executor keeps only the JDBC probe).

Referee: suite 4185/0, corpus scoreboard byte-identical.

### SLICES 3+4 LANDED — ZERO PINS; the exception mechanism is DELETED

Invariant 7 now reads "typed nodes are minted only by compiler layers"
with NO doNotBelongToAnyOf clause — nothing left to abuse (user
directive). The last two evictions:

- **RawGridSchema → resolver/ (staged compilation)**: the rewrite pass
  is parameterized by `SchemaOracle` (`sql -> columnNames`); the
  runtime-discovered roster is an INPUT to a compiler phase run at a
  later stage. `exec.GridProbe` (the LIMIT-0 probe, the one chartered
  RawSql ctor) is the executor's oracle. RING worth keeping: the first
  design kept `throws SQLException` on the oracle — F1.3's BYTECODE
  wall rejected it (java.sql funnels to exec; even the exception TYPE
  may not appear in the resolver), forcing the honest unchecked design
  (executor wraps, the slice-1 Frames idiom). A guardrail refusing the
  half-measure and producing the better architecture.
- **AssertVerdicts → compiler.spec.VerdictQueries**: the quantified-
  assert predicate-vector synthesis (its only 2 mint sites) is now
  compiler emission; AssertVerdicts fetches and judges, minting
  nothing. VerdictQueries is the SEED of the canonical-render verdicts
  leg (that leg's target is the remaining THIRD-IMPL equality debt —
  PureAsserts/GridCompare — which mints nothing and no ArchRule sees).

Registrations paid: RawSqlLedger + JavaEvalLedger + JdbcSurfaceCensus
rows follow the probe to GridProbe.java; the stale RawGridSchema exec
rows deleted per the ledgers' own instructions; executeTyped method-
size limit paid with a REAL split (gridOracle helper). Guard census
END STATE: mints outside compiler layers = ZERO, structurally frozen.

Referee: suite 4185/0, corpus scoreboard byte-identical.

## CANONICAL-RENDER VERDICTS LEG (user: "do it"; ordering ruling: render before JDBC gateway)

Ordering rationale (ratified in-session): render DELETES JDBC surface
the gateway would otherwise migrate (PureAsserts/GridCompare value-kind
rows); render is the correctness leg and its blocker (stamp census
zero) is cleared; the gateway's unique payoffs (validation, injection)
only become real WITH the ingress prepared statements creates.

### FALSE START RECORDED (2026-08-21, user-caught): the adapter hedge

The first R1 attempt built compile-through of `equal()` to one SQL
boolean with ASYMMETRIC TRUST (SQL true short-circuits, SQL false
re-runs the whole host path) — the audit doc's fix-queue row 3. The
user challenged it ("every time we try the adapter strategy it just
turns out worse") and the challenge was CORRECT; reverted uncommitted.
The tells, recorded so the shape is recognized next time:
- It bumped the eval ledger UP (398→438) with an "it'll shrink later"
  justification — on the ledger whose purpose is to shrink. A
  transitional bridge pin-bump is the adapter disease's signature
  (MODEL B: "the shims existed only because of the inversion";
  D3-class adapter reverted; C2: producers+consumers move TOGETHER).
- Two implementations stayed live with a conditional preference —
  divergences get silently ABSORBED by the fallback. The repo's method
  is the opposite: surface the divergence table loudly, adjudicate
  each class, cut over hard. Measurement belongs in the REFEREE, not
  as a permanent second path in production.
- It drifted from the RATIFIED design (byte-compare of canonical
  renders — equality semantics in ONE owner, the serialization
  definition) back to an older idea (boolean equal() compile-through)
  that then needed hedges for NaN/tolerance/sentinel — which under the
  byte design are spec ROWS, not patches.

### THE PLAN — homework, then spec, then hard cutovers

**Homework (fact tables; every R0 row traceable to a source, a PCT
pin, or a corpus witness — no guessing/sampling):**
- H1 pure's normative print spec: toString/toRepresentation per
  primitive from the REAL legend-pure checkout + PCT toString pins
  (exact spellings: Float vs Integer forms, Decimal suffix, quoting,
  +0000 temporals).
- H2 engine's grid canonical form: the TDS print conventions (distinct
  from scalar toString) from engine sources + wire notes.
- H3 our emission census: what Render/dateLiteralPrint/STRFTIME-CONCAT
  /PctTdsWrap already emit (corpus-pinned spellings); diff vs H1/H2 —
  each mismatch is an R0 decision row.
- H4 host-arm policy inventory: PureAsserts (kind lattice + repr +
  temporal bridge + sorted/typeRank), GridCompare (sig-digit tolerance
  heuristic), JsonCompare leaf rules, TDSNull sentinel scope — R0
  absorbs or explicitly retires EACH.
- H5 corpus assert distribution: assert-family × operand-kind counts;
  and the structural fact that corpus expected literals ARE
  engine-rendered canonical text — the strongest ground truth.
- H6 edge witnesses: NaN/±Inf/-0.0/Decimal-scale/empty-in-collection
  hunted in PCT+corpus so the edge catalog has witnesses.

**R0** — the canonical-form spec doc, written FROM the tables: per-kind
canonical render; where byte-equality ⟺ pure-equality holds; named
exceptions (NaN; the 2-ULP tolerance as a declared numeric policy
outside the byte channel until its census retires it).

**R1** — the render owner (Render's SQL print forms grown into THE
canonical serializer) + a HARNESS-side divergence instrument: across
the full corpus, render(e)==render(a) computed NEXT TO
PureAsserts.equal(e,a); publish the disagreement table. No production
path changes.

**R2** — per-family HARD cutover (assertEquals scalars → collections →
grids): each family moves to byte-compare AND its host arms delete in
the SAME slice — ledger pins go DOWN in every commit; disagreements
fail loudly as rings.

**R3/R4** — tolerance census re-read; World-2 paired probes guard only
what SURVIVES by design.

### SHORTCUT-AUDIT BLOCKER 1 LANDED (2026-08-21): null-drop into the lowerer

COMPILER_SHORTCUT_AUDIT §5, ratified work order item 2a. The pure rule
"a collection holds no empties" is now COMPILED, not an egress mask —
placed at the carriers SQL does NOT null-skip for us:

- **SQL aggregates need nothing**: COUNT/listagg/SUM/min/max skip NULL
  inputs natively — that IS pure's drop on those consumptions, and the
  engine's own SQL relies on it. (A first draft filtered at the
  projection seam instead; the corpus caught it perturbing the
  un-ORDER-BY'd row order under `listagg` —
  testSubAggregationMultiLevelJoinString — and it was withdrawn.)
- **LIST carriers compact**: the NEW `SqlExpr.CompactList` SEMANTIC
  node (dialect renderer owns the list-filter spelling — CheckedOne/D1
  precedent, carrier-ratchet pins untouched) wraps every optional-cell
  LIST() collect (Lowerer columnList site + collectAsList) and every
  value-lane COLLECTION-root explode. Order-preserving by construction.
  toOne's agg-strip recognizes through the wrapper and the CheckedOne
  guard counts the COMPACTED list (pure's null-free size).
- **Row-wise egress filters**: COLLECTION-shape roots (single synthetic
  map column, optional cell, many stamp) filter at the root select
  (`Fold.collectionRootEgress` / `cellPresentFiltered`) — the Executor
  reads rows directly there.
- **Executor cutover**: the one-line `if (v != null)` mask is GONE — a
  NULL reaching non-variant COLLECTION egress now WALLS as a lowering
  defect. The variant/Any lane keeps its drop (JSON-null variant-decay
  is a semantic rule of that lane, not a mask).

TRAP RECORDED (cost one PCT + one corpus regression before the referee
caught it): SqlRewriter's CheckedOne arm called the `expr()` HOOK
instead of the recursive `rewriteExpr()` — a SHALLOW visit; copying the
idiom for CompactList shielded whole subtrees from every dialect pass
(SubstringClamp never reached a nested substr). Both arms now recurse,
and the rebuild preserves CheckedOne's flags (the 1-arg ctor was
silently erasing scalarCarrier/atLeastOnly on any rewrite).

Pins: `OptionalCollectionNullDropTest` (e2e over DuckDB, JDBC-census
registered) — size()/at()/indexOf()/toOne() all see the same pure
collection; toOne over `[NULL,'Al','Cee']` raises size **2**, not 3.

ENGINE GROUND (user-requested verification): the reference performs the
SAME drop CLIENT-side — `relationalMappingExecution.pure:480`, the
PrimitiveType result arm: `if(is($val->type(), SQLNull), |[], |$val)`
inside `->map` (empties flatten out); the TDS arm (:539/:571) maps
`SQLNull -> $tdsNull` instead — the two-channel split our lanes mirror
(grids keep null cells, value collections drop). The engine never
lowers at/indexOf/toOne INTO SQL, so its SQL never needs the filter;
ours does (tenet #1), which is the whole divergence.

ADJUDICATION (user: "option 1 bump"): the compiled drop adds one WHERE
clause to value-collection egress SQL that engine goldens structurally
cannot carry -> +10 advisory golden-SQL diffs on row-verified tests
(functions/tests 8, mapping/join 1, aggregationAware/NOP 1; witness
testAssociationToManyAutoMap). Ceilings moved WITH justification in the
same commit: runner advisory 299->309, soft-ceiling sqldiff 247->257,
adv-pass 293->303. Pass baseline unchanged (2332); PCT unchanged
(1109/36 pinned). The WHERE folds INLINE per the fold policy
(`Fold.cellPresentFiltered`) — the subselect wrap survives only where
a WHERE is not clause-equivalent (grouping/window/LIMIT).

### SHORTCUT-AUDIT BLOCKER 2 LANDED (2026-08-21): toOne lane from the TYPED node

Audit §1a, ratified work order item 2b. `toOne`/`toOneMany` pick their
checked lane from the OPERAND'S TYPED PROVENANCE (`CollectionLanes.
valueLane` — literals/if-branches/native-transforms over value
collections = VALUE lane, pure raising semantics; store/relation-rooted
reads and unknown binders = ROW lane, the engine's processNoOp flow,
default-conservative). The SQL-shape sniff (`instanceof ArrayLit ||
producesList()`) is DELETED from the rules — it shared its evidence
procedure with `StampCensus.listShaped`, so the "always-on" invariant
fired zero times on the very shapes the rule missed (the audit's
tautology, concrete form).

Audit table now pins in `ToOneLaneTest` (e2e DuckDB, census-registered):
slice's Case wrapper, lowered ifs, `range()`, descending sort and
`reverse()` all raise pure's size error IN THE DATABASE; size-1 shapes
extract. Census strengthened in the same slice (the ratified stopgap,
now defense-in-depth rather than the decision procedure):
`producesList` += RANGE_FN + LIST_SORT_DESC (the asc/desc guarantee
asymmetry), `listShaped` += Case-of-list-branches + CompactList.

ROW-lane note: shapes the old sniff accidentally checked (a row-lane
`->sort()->toOne()` emitting LIST_SORT) now FLOW — that IS the
engine's relational lane; the corpus referees the parity.

THREE RULES THE REFEREE TAUGHT (one milestoning witness,
testIsolationOfMilestoningFiltersReferencedInAllPartsOfIfStmt, three
consecutive gate rounds — each grounded in the engine golden or pure
semantics, NOT in making the test pass; flagged for the R1
paired-probe pass):
1. The value-lane guard counts the COMPACTED carrier
   (CheckedOne(CompactList(x))) — pure's size is over PRESENT
   elements; SQL list slots include NULLs for empty [0..1] reads.
2. A collection LITERAL carries its ELEMENTS' lane — [$p.a, $p.b] is
   the engine's relational lane (flow), [1,2] is the value lane
   (raise); §4 per-lane ruling, engine-relational is the target.
3. if() arrives as a NATIVE with zero-param thunks (thunk bodies carry
   the lane; parameterized lambdas stay excluded), and an if whose
   branches are ALL to-one-stamped lowers on the SCALAR carrier
   (bare CASE, loose [*] stamp notwithstanding) — no list exists to
   count; toOne FLOWS it, exactly the engine's compilation
   (CollectionLanes.scalarCarriedIf — typed facts only).

### SHORTCUT-AUDIT BLOCKER 3 LANDED (2026-08-21): referee integrity #9–13

The guards now cover the code they claim to govern:
- **#9** HarnessDisciplineTest walks `src/main/com/legend/exec` (the
  comparison policy MOVED there and out of scope — the audit's central
  relocation finding) and its regex knows the `pool.remove(` loop
  spelling. The widened net immediately CAUGHT a 4th uninstrumented
  pool loop in EngineTestExecutor (assertSameElements' value multiset —
  pure-spec order-insensitivity, registered + tagged).
- **#10** all five `[ord]` sites emit DISTINCT tags (row-multiset /
  row-tuple / text-line-multiset / sameElements-values / h2-oracle) —
  the 358-count census can now separate loose-cell from row-tuple.
- **#11** SkipCensusTest walks sibling modules (parser-equivalence +
  pct; coverage floor 270) — 8 previously invisible assumption-skipping
  files registered with the oracle-absent justification.
- **#12** every ChannelB suite ASSERTS its wall count shrink-only
  (essential 20 / relation 23 / standard 20 / unclassified 27), the
  relation suite pins the two denominator-blocking walls BY NAME
  (over.pure, pctQualifiers.pure) so burning either forces the 287
  discovery pin toward the 348 REFERENCE scope, and the 287 assert's
  message states the honest denominator. Essential's garbled floor
  message fixed (said "< 290" while checking >= 295).
- **#13** the four soft-pass ceilings bind LIVE in the corpus runner
  against the sweep's own outcomes (sqldiff 257 / adv 303 / 0-asserts
  27 / rescued 613, next to the advisory ceiling);
  CorpusSoftCeilingTest — which read the committed markdown and could
  never go red in CI — is DELETED.

### R1 LANDED (2026-08-21): the World-2 paired-probe guard

`VerdictWorld2ConsistencyTest` — chartered in HOST_LOGIC_AUDIT ("the
guard that keeps it fixed") and unbuilt until the shortcut audit called
it out; ratified item d, WIDENED to the egress arms. The pairwise
equality lattice stays with its OWNER (`EqualityWorldsConformanceTest`,
the Clause-2c two-worlds fixture — R1 extended it with the
integral×Decimal agree row and the 2-ULP declared-divergence row
rather than duplicating). The new test owns: total order (host sort
canonicalization vs compiled sort), the §6 decodeAny egress probe
(Decimal-through-Any precision loss made VISIBLE, pinned to flip when
the carrier heals), and the §5 cross-notion seal
(collection/size/at/indexOf/toOne must tell ONE story over a carrier
holding empties).

**The instrument caught two live inconsistencies on its first run**:
size()=2 and indexOf mis-based over `[[]->first(),'a']` while at(0)
extracted correctly — the §5 rule missing at the counting/positional
consumers. Fixed via `CollectionLanes.compactIfValueLane` at the
size/at/indexOf rules (identity on definite lists, engine-TEXT
verbatim — zero golden movement). Divergence rows are two-sided: AGREE
rows fail on divergence, KNOWN rows fail when they START agreeing.

### BURN LANE LANDED (2026-08-21): §3a + §3b + cast — ratified item e

- **§3a exists-dedupe**: the DISTINCT-key projection dedupes by
  TABLE+NAME identity (bare-name dedupe collapsed T_PERSON.NAME and
  T_DEPT.NAME onto one projection and the ON compared the outer against
  the WRONG side — 0 rows where 1 was right). Colliding names get
  disambiguated output aliases; single-table stays golden-stable.
- **§3b joinStrings ordering**: the CONCATENATE order obligation sees
  through wrapping Calls (`Fold.orderUnionAggregateExpr`) — the 3-arg
  joinStrings' CONCAT(prefix, string_agg, suffix) now mints u_ord like
  the bare reducer; one extra argument no longer drops pure's order.
- **cast**: impossible CROSS-KIND casts raise pure's "Cast exception"
  in the database (`CastPolicy.crossKindRaise`). ADJUDICATED SCOPE the
  referee set: string<->numeric/temporal conversions are the standing
  PRODUCT contract — `TypeConversionCheckerTest$StringToInteger` IS the
  String->Integer pin and the view family pins Integer->String, so the
  audit's `1->cast(@String)` row lands in the conversion contract, and
  the raise covers the uncontested classes (boolean<->anything,
  temporal<->numeric). Mapping WIRE coercions exempt. The whole cast
  arm moved to CastPolicy (ONE cast owner; Lowerer keeps a 3-line
  delegator).

Pins: `BurnLaneTest` (§3a and §3b at the IR seam, cast e2e over
DuckDB, census-registered).

### AUDIT OF BLOCKER 3 + R1 + BURN LANE (2026-08-21, user-directed)

Same method as the Blockers-1+2 audit: probes + sweep, code and
execution only. NUL-byte sweep of every session-touched file: clean
(the one ExistsJoinForm incident was already fixed).

FOUND + FIXED (four):
1. **Type-checker stamp bug** (the biggest catch): collection-literal
   multiplicity was the ELEMENT COUNT ([2..2] for `[[]->first(),'a']`),
   not the SUM of element bounds ([1..2], pure). The §5 egress wall had
   been correctly firing on it (reverse/sort walling on a correct
   one-element result). Typer#collection now sums bounds.
2. **Compaction moved to the literal's CONSTRUCTION**: a 12-probe
   battery showed consumer-site compaction was whack-a-mole — head/
   first returned null, tail/drop/take were off-by-slot, makeString
   printed TDSNull. A value-lane literal with any lower-0 element now
   compacts ONCE at the ArrayLit build; all 12 probes pure-correct,
   pinned as VerdictWorld2ConsistencyTest#section5FullBattery.
3. **The pool-loop regex had its own blind spot**: `pool\.remove\(`
   missed rowTupleMultiset's `ap.remove(hit)` — the guard widened in
   Blocker 3 was still spelling-bound. Regex now `\.remove\(hit\)`
   (the idiom's true token); GridCompare register 3→4.
4. **ChannelBGrammarTest was the one suite #12 missed** — walls now
   captured, printed, and pinned (20) like its four siblings.

RECORDED (no action): §3b orderUnionAggregateExpr orders the FIRST
nested reducer only (one union source per agg projection — the real
shape; a second unordered reducer in one wrapped expr has no witness);
cast BYTE-family (BINARY) cross-casts now raise (pure-right, no corpus
witness either way); the live soft ceilings bind in the hand-run gate
only (the corpus never runs in CI — same boundary as every corpus
guard, now honest rather than vacuous).

CROSS-CHECKED against the PARALLEL DEEP_AUDIT_2026_08_21 (branch
worktree-e2e-deep-diagnosis, user-directed read): its two claimed
regressions from our blockers were CONFIRMED BY PROBE and are FIXED in
this slice —
- **R1**: `take(1)->toOne()` aborted the compile — `CollectionLanes`'
  whitelist had no `TypedLimit` arm (`default -> false` re-created the
  blind-spot class the whitelist replaced, exactly as the deep audit
  diagnosed). Fixed with the DURABLE form both audits converge on: the
  switch is now EXHAUSTIVE over the sealed TypedSpec hierarchy — javac
  refuses a new node type until it is classified. Pins:
  take/limit rows in ToOneLaneTest.
- **R2**: `map(p|$p.nick->toOne())` hit the §5 egress WALL — the
  flow-adjudicated toOne wrap stamps [1] but its SQL cell CAN be NULL
  (§7b witness: the engine emits no guard and drops client-side).
  `ScalarValueReads` now descends through toOne-family wraps when
  computing the u_map__ COLUMN mult, so the carrier's truth ([0..1])
  drives the egress filter — result [Al, Cee], the engine's
  SQLNull->[] equivalence. Pin: flowedToOneCellDrops.
The deep audit's remaining findings were RATIFIED as the next work
order (user: "do it"; queue D1–D6). D1 ADJUDICATED NO-CHANGE during
recon: filter-grows-the-set is the ENGINE'S PINNED semantics — its own
testInNegated golden asserts the duplicated-parent count
(assertSize 10) AND the `or ... is null` admission arm, and
testConsistencyWithNullsInJoin pins the isEmpty()||-equivalence plus
the eq/notEq partition law. Our AUDIT-9 comment
(StoreResolver:2304-2308) had already recorded exactly this; the deep
audit's §2 lands in the same retraction class as its own §1 rows.

REFEREE LESSON from this slice (corpus witness
testSelfJoinPropertyMapping): construction-site compaction must be
VALUE-LANE-scoped — a ROW-cells literal ($r.values / hand-written
property reads) keeps its NULL cells, because TDSNull is DATA on the
grid convention (the engine's tdsNull channel). The first draft
compacted any literal with a lower-0 element and ate a grid cell.

### D6a LANDED: isDistinct + the graph-leaf discipline (DEEP_AUDIT §5k, §1)

- **isDistinct**: the blanket family() had routed the 1-ARG collection
  form into the binary IS DISTINCT FROM — AIOOBE on any input. Split:
  2-arg = SQL IS DISTINCT FROM; 1-arg = the NEW `SqlFn.ALL_DISTINCT`
  semantic entry (carrier-ratchet honest: the lowering emits MEANING,
  DuckDb expands len(list_distinct(x))=len(x), the base renderer walls
  loudly, SpellingsTest CODED row). A to-one operand is trivially true;
  c1 literals box.
- **Graph-leaf toOne**: `GraphEmission.scalarLeafSubquery`'s LIMIT 1
  (comment claimed "pure toOne semantics" — wrong on any lane) is now
  the ENGINE's discipline (witness graphFetchCommon.pure:163:
  distinct=true + hard-fail; no row-cap vocabulary exists): DEDUP via
  TypedDistinct, then the backend's own more-than-one-row subquery
  error fires — the LIMIT had been suppressing that free check while
  silently picking a winner. A [0..1]-STAMPED single-column distinct
  reads as the scalar subquery in the value-position arm (the
  [1]-stamped TDS distinct/restrict SPLICE stays a value collection).
  This closes §7b Q2 (previously OPEN pending exactly this witness).
- concatSide moved to ListEncodings (file-length guard).

Pins: BurnLaneTest isDistinct rows. The nested-nav TypedLimit at
GraphEmission:2714 is the SAME family — recorded for the next touch.

### D4 LANDED: function-slot variance + the subtype cycle guard (DEEP_AUDIT §5b)

- **Contravariant parameter positions** (reference TypeMatch matches
  params with `!covariant`): the InferenceKernel FunctionType arm SWAPS
  the unify direction for parameter types — the actual's param must be
  a SUPERTYPE of the formal's — killing the wrong-accept (an
  Integer[1] lambda receiving 1.5 → silent 2.5) and the wrong-reject
  (a Number-taking function refused an Integer-taking slot, the useful
  case) in one edit. The multiplicity check FLIPS instead of skipping
  (formal range ⊆ actual range) — still admits the recorded
  equal(Any[*],Any[*]) comparator doctrine ([1] ⊆ [*]), finally
  rejects genuinely-wrong widths. UNBOUND formal type/mult VARIABLES
  keep the binding order (variance is moot for a fresh var; the swap
  would move it to the non-binding side).
- **ModelContext.isSubtype gains a visited set**: an inheritance cycle
  (which the frontend still accepts — D6's leniency batch owns the
  reject) was a StackOverflowError on the first miss; the guard
  existed in ancestorsOf but not in the primitive everything calls.

Pins: VarianceD4Test (both directions e2e through eval + the finite
cycle). Suite 4213/0 with the flip — nothing depended on the inverted
direction.

### D3 LANDED: gate integrity (DEEP_AUDIT §11c) — two user rulings

- **Tripwire**: PX.1 now `exit 1`s (it printed FAILED then exit 0 — the
  one branch detecting a poisoned certification reported success) and
  runs UNCONDITIONALLY (it only ran on all-green chains, so a
  failed-gate chain never checked tree mutation).
- **Gate 5 asserts**: the H2 sweep gains its first assertions — pass
  floor 1361 (ratchets UP), failed-seeds ≤ 6, capability walls ≤ 945
  (measured 2026-08-21). "1362 could become 1 without moving the
  verdict" is dead.
- **Gate 8 roster** (USER RULING: surgical, not restructure): the
  allowlist + rename-goes-red discipline stays; the 7 previously
  invisible classes append to BOTH lists (27 total — the module was
  RED at HEAD while the gate was green).
- **No skipping class on a gate roster** (USER RULING): the strict
  skipped() detector STAYS (any fully-skipped class marks the gate);
  PmcdReachabilityCensusTest became SELF-SUFFICIENT instead — it
  materializes the protocol roster itself
  (ProtocolRosterCensusTest.materializeRoster) rather than skipping on
  class order; its SkipCensus registration removed (census SHRANK).
- **manifest.regen** now produces a loud SKIP, never a fake PASS
  (it reported a genuine PASS having asserted nothing).

### D2 LANDED: the c1-singleton binder leaks (DEEP_AUDIT §3)

The six hard crashes fixed — a C1-COLLAPSED LITERAL ([7]) reaching a
collection op as a bare scalar re-boxes (PureSql.asList): take/drop/
slice at the Lowerer's Typed arms, exists/forAll at their
registrations, contains and zip at their rules. zip's pair emission
moved to ListEncodings (file-length guard).

SECOND REFEREE LESSON (corpus witness testContainsEscapePercentage):
the box is scoped to `CollectionLanes.c1Literal` — a to-one-stamped
COLLECTION LITERAL, exactly the deep audit's own "Bounded: literal
collections only". A to-one PROPERTY READ must NOT box: the corpus
pins its null-guarded scalar arms (`comments->contains('%')` over
String[0..1] = `IS NOT NULL AND strpos(...)`, never list_contains).
The first draft boxed by bare to-one stamp and broke that arm.

ADJUDICATED pure-faithful (the audit's "want" column was wrong — its
own §1 retraction class): `['ACTIVE']->contains('TIV')` is TRUE in
real pure — [x] == x is pure's own law, the checker resolves
string::contains for a String[1] operand, and substring semantics IS
pure's answer. The contains rule dispatches by the RESOLVED CALLEE's
param mult (boxes only the collection overload). Same ruling covers
`['abc']->indexOf('b')` (string overload + the adjudicated engine
1-based lane). Pins: BurnLaneTest#singletonCollectionOps.

### §7b ADJUDICATION (2026-08-21, ratified item f — witnesses, no reflex fix)

**Question 1 — TRUST_ONE wrapping user `[1]` declarations / the
"audit R3" pin (`ResolveNavigationTest:215`, user `->toOne()` over a
zero-row association yielding "null"): ADJUDICATED ENGINE-FAITHFUL,
pin STANDS.** Engine witness (decisive, golden SQL in the reference's
own test): `testTwoAssociationsToOneBranchWithQuery`
(projection/testAssociationToOne.pure:40-43) compiles a USER-written
`$p.firm->toOne().legalName` to a bare unguarded
`left outer join firmTable` — no row-count guard exists anywhere in
the engine's relational compilation of user toOne (processNoOp, the
same lane our flow convention mirrors). A missing association row
produces a NULL cell in the engine's own TDS, exactly the pin's
"null". PROVENANCE CORRECTION owed: the behavior is engine-relational
parity, not "pure toOne semantics" as GraphEmission's comment claims —
reword at next touch. The audit's demand ("a user's [1] declaration is
trusted, never verified") describes the ENGINE's relational lane too;
enforcement would be a deliberate DIVERGENCE, adjudicated NO for now.

**Question 2 — GraphEmission's `TypedLimit(proj, 1)` on to-one nav
leaves suppressing the backend's own more-than-one-row error: OPEN.**
No multiplicity enforcement found in the engine's relational
graph-fetch executor plugin (RelationalExecutionNodeExecutor /
RelationalGraphFetchUtils — no check/validate/error arms on child
cardinality); the decisive witness lives in the engine's GENERATED
graph-node classes' to-one setters (last-wins vs throw), not yet
decoded. Until then the LIMIT 1 stays AS PINNED — but its comment's
"pure toOne semantics" claim is wrong on any reading (pure raises on
2); reword with the honest OPEN status at next touch. Follow-up
witness location recorded: legend-engine-executionPlan-execution
generated GraphFetch class setters.

### AUDIT OF BLOCKERS 1+2 (2026-08-21, user-directed post-landing pass)

Method: executable probes + walker sweep, evidence = code and
execution only. Nine e2e probes (zip, let-bound, eval'd thunk,
empty-cast toOneMany, scalar-if, optional-element literal, fold-inner
toOne) ALL pure-correct — the classifier's conservative defaults
(TypedLet/Variable/Eval → ROW) never bite because the inliner reduces
them before the rules run.

FOUND + FIXED: `FoldToListReduce.unwrapElemRefs` claimed "EXHAUSTIVE"
but its CheckedOne/CompactList arms were SHALLOW (the same class as
SqlRewriter's arm fixed in b0a163af) — an elem ref under a value-lane
guard inside a fold body stayed un-rewritten on the H2
list-accumulator path. Both arms now recurse; zip + optional-element
rows pinned in ToOneLaneTest.

FOUND + RECORDED (no action): (a) Exists/ScalarSubquery/WindowCall
arms in the same walker share the shallow hole PRE-EXISTING — the fold
recognizer does not currently produce the combination; noted in-source.
(b) h2-replay unverifiable 143→145 across Blocker 1: compacted
carriers reaching H2 decline LOUDLY (no lambda list encoding there) —
honest wall, not silent wrong rows; a CarrierStrategies CompactList
strategy for H2 is a future leg. (c) CarrierStrategies' recognizers
no-match CompactList-wrapped collects (conservative no-op; corpus
green bounds the impact).

VERIFIED CLEAN: every engine-TEXT view (DB2/Composite extend
EngineStyleH2) renders CompactList verbatim consistently; H2 EXECUTION
extends AnsiSqlRenderer and gets the real list_filter spelling (walls
where unsupported); no volatile SqlFns exist for the whereSafe
double-evaluation to bite; the Executor wall's variant/Any boundary
mirrors the engine's SQLNull→[] vs tdsNull two-channel split.

### PROVENANCE CORRECTION (2026-08-21, owed to COMPILER_SHORTCUT_AUDIT)

Egress slice A (d1c968e0) cited "engine resultSizeRange parity". The
audit verified the reference and the citation is WRONG: the engine
reads resultSizeRange only via isUpperBoundEqualTo(1) to choose
realize-vs-stream (ExecutionNodeResultHelper.java:32-41) — there is no
lower-bound row-count check in the reference executor. The FIX stands
on the PURE-SPEC ground (size-0 into a required bound raises pure's
cast error, PCT-witnessed), not on engine parity. The behavior is
unchanged; the claim is corrected.

### D6b LANDED: the frontend-leniency batch (DEEP_AUDIT §10)

Seven probed leniency holes — models the engine rejects at compile that
we ACCEPTED (probe: ScratchLeniencyTest, results recorded here; probe
deleted, pins live in LeniencyD6Test):

- **Inheritance cycles** (was: accepted, StackOverflow at first
  demand): ModelIntegrity.checkInheritanceAcyclic — resolved-supers
  DFS with a shared acyclic memo; message names the cycle path.
- **Duplicate stored properties** (was: silent first-wins): engine
  spelling "Found duplicated property 'x' in class 'A'". Derived-name
  dups stay accepted — qualified properties legally overload; an
  identical-signature derived dup is a recorded follow-up.
- **Duplicate element FQNs** (was: silent LAST-wins at
  ModelBuilder.putAtId): ONE shared packageable-element namespace
  (classes/enums/associations/profiles/databases — cross-KIND dups
  reject too), recorded at phase-2 ingest (`internElement`), THROWN by
  ModelIntegrity so poison-not-drop/wallSink applies uniformly.
  Engine spelling "Duplicated element 'x'". The compile-once corpus
  global model was the feared casualty — scoreboard diff EMPTY.
- **Duplicate enum values**: "Found duplicated value 'A' in
  enumeration 'E'".
- **Inverted bounds `[2..1]`** (was: lazy bare IllegalArgumentException
  from the Bounded ctor at class demand): eager requireValidBounds on
  stored/derived/parameter multiplicities, attributed to the property
  site.
- **Ghost store cross-refs** (was: accepted; SQL failed or misbehaved
  only in the DB): every ColumnRef in join/filter/multigrain
  conditions must resolve table (include-closure, views,
  milestoning-declared temporal columns, quote-bearing identity per
  StoreCompiler cluster 7) and column. CONSERVATIVE: {target} refs and
  explicit foreign-db refs are SKIPPED, never false-positived.
- **Out-of-range dates `%2020-99-99`** (was: DuckDB "Conversion
  Error" at run time): SpecParser validates components for
  LEGEND_PLATFORM + LEGEND_LITE; LEGEND_ENGINE stays deferred — the
  oracle's parser accepts these byte-for-byte (gate 8 parity) and the
  engine validates in ITS compiler, which is not our parity surface.

ADJUDICATED KEPT: the ElementParser stray-paren skip is bounded ("skip
exactly this token") and corpus-witnessed engine tolerance — not a hole.

Referee: core suite 4215/0; full DuckDB corpus scoreboard diff EMPTY
(the early scoped run that the dup-FQN risk demanded); pins
LeniencyD6Test (JDBC-census registered: the valid-neighbor control and
the moved-to-parser bad-date pin execute e2e).

### D5 LANDED: ConnectionResolver content-keyed + the field-level Invariant 3 (DEEP_AUDIT §11b)

The server's connection cache had every defect Invariant 3 exists to
prevent, invisible to the CLASS-NAME funnel because it was a FIELD
(`static Map CACHE`):

- **Check-then-act race**: two threads could both open; the losing
  connection leaked UNCLOSED. Now `HandleStore.getOrOpen` — atomic
  compute, no loser exists. Race pinned by an 8-thread latch test.
- **FQN-keyed identity** (engine planCache scar): an edited connection
  definition kept resolving onto the stale database. Now content-keyed:
  Hash(definition record content + FQN) — same definition keeps its
  database across requests (tables persist, the feature); an edited
  definition gets a fresh one.
- **Shared `jdbc:h2:mem:testdb`** in the default H2 arm (the same A19
  disease the EmbeddedH2 arm was already cured of): now named per
  definition content.
- **Dead-handle replacement**: a closed cached connection is replaced
  in the same atomic step; a failed open never poisons the key.

`HandleStore<T>` lives in com.legend.cache (Invariant 3 funnel) and is
GENERIC on purpose: java.sql stays at the chartered seams (F1.3) — the
first design (ConnectionStore holding Connection in cache) tripped that
rule, and the fix was genericizing, not widening the funnel. Its two
broad catches are the checked-exception carrier, reviewed + pinned.

**Invariant 3, field level** (the ArchUnit charter item): a
declared-type rule is BLIND here — every field spells `Map<...>`
whether the value is Map.of() or a ConcurrentHashMap — so the new
guard REFLECTS ON RUNTIME VALUES: every static collection field
outside com.legend.cache must hold an immutable collection or appear
in a justified register (21 adjudicated rows: write-once init tables
= burn-down candidates; TimingLedger + SUPPRESSED_ONCE = genuinely
mutable, justified). The guard found and adjudicated 9 sites the
grep census missed. Old CACHE deleted same-slice (no adapter hedges).

Pins: HandleStoreTest (5, JDBC-free — the census stays clean by
DESIGN: the pin uses IOException). Referee: core suite 4221/0 + full
chain green.

## THE RATIFIED ARC (2026-08-21, user: "ratified, go")

Order: (1) verdict-in-DB — DB verdict of record, HOST VERDICT KEPT
PERMANENTLY as a parallel REFEREE (disagreement = pinned shrink-only
census, NEVER a rescue; ArchUnit pins it unreachable from the product
path); (2) decoupled-PCT burn to COMPLETE — finish ONE of the three
harnesses (PCT / relational corpus / ###Data corpus) entirely before
opening a new front; (3) ###Data execution (census first) →
test-corpus branch; (4) relational corpus burndown resume (2347/2575).
Prepared statements deliberately LATE: they perturb the golden-SQL
TEXT lane; land after row/verdict refereeing dominates.

**Finished-harness definition (PCT)**: every test passes through the
DECOUPLED channel (platform wire, zero harness compensations);
reference-vs-decoupled diff census 0 with compensation code DELETED
(the reference channel itself SURVIVES as the permanent cross-lane
referee — engine-derived spec, not our second impl); exclusions 0 or
declared feature-leg rows; DB verdict of record.

### Decoupled-PCT census (read-only slice, measured 2026-08-21)

Channel-B state (discovered/AGREE-PASS/AGREE-FAIL/WIRE-BUG/B-FIXES-A):
Essential 327/291/21/11/4 · Grammar 137/126/6/1/4 · Relation
287/286/0/0/1 · Standard 204/204/0/0/0 · Unclassified 95/95/0/0/0.
Total discovered 1050 vs reference 1115.

**TRUE-WIRE-BUG = 0 across all five suites.** All 12 WIRE-BUG rows are
ENGINE-FRONTIER: the engine's OWN relational-DuckDB executor fails
them too. The decoupled channel has no true wire debt today — the
completion delta is walls + exclusions, not wire bugs.

Completion delta, bucketed (design legs, NOT a onesie tail):

1. **Platform-source walls** (~50 files, hiding the 65 undiscovered
   tests incl. Relation's 287→348 gap):
   a. REFLECTION meta-types family (~14 files): Multiplicity /
      ValueSpecification / Package / PackageableElement / FunctionType
      not modeled (isToOne/isToMany/bounds, elementToPath,
      functionType, reactivate, routing.pure, support.pure).
   b. Grammar-gaps family: m3.pure top-level ^Instance(...), new.pure
      brace form, pct_core.pure generics, addColumns '@' column types,
      unit files (newUnit/getUnitValue), over.pure '?' wildcard.
2. **Reference exclusions 36** (Essential 25, Grammar 10, Relation 1),
   clustered: instance-universe-in-PCT ~13 (fold/find/filter/getAll/
   map-relationship/head-complex/match over Person-class instances);
   date ERROR-parity 5 + adjust-big-number 4; indexOf/substring base
   3 (the §4 PER-LANE adjudication rows — engine witnesses required);
   sort-with-key/function-vars 3; primitive-extension eq/equal 2;
   misc 3 (toString complex, at error, variant joinStrings).
3. **ENGINE-FRONTIER 12**: adjudication rows — engine parity says
   these stay declared (we match the engine's relational executor,
   including its frontier), pinned so they burn if the engine moves.

### Verdict-in-DB scoping (task #16, read-only slice)

Homework H1–H6 COMPLETE (docs/CANONICAL_RENDER_HOMEWORK.md). R0 next =
the canonical-form spec doc (decisions already ruled in the homework
verdict). R1 = Render grown into THE canonical serializer (scalar
channel: no-exponent float format expr + scale-normalized Decimal;
grid channel ALREADY IS engine toCSV) + the corpus-wide divergence
instrument render(e)==render(a) NEXT TO PureAsserts. R2 = per-family
hard cutover (assertEquals scalars → collections → grids →
assertSameElements = 1,803 of 2,039 sites), eval-ledger pins DOWN
every commit. RATIFIED AMENDMENT to R2's "host arms delete":
PureAsserts' equality lattice SURVIVES as the permanent parallel-host
referee (the ratified dual-verdict design) — what deletes is every
harness-compensation arm and every verdict-AFFECTING host path;
PureAsserts moves to the referee, disagreement census pinned at 0.

### R1a LANDED: the scalar-channel divergence instrument (first measurement)

CanonicalForm (R0 §2 host reference render) + CanonicalDivergence (the
census ledger, TimingLedger idiom — Invariant-3 registered, bounded
samples) + probes at the K-arm choke point (AssertVerdicts
assertEquals/assertSameElements/assertEq — measurement only, cannot
affect a verdict) + publication in the corpus runner and ChannelBDiff.

**FIRST MEASUREMENT (ChannelB, all five suites): agree=1555
disagree=0 residue=27.** The ⟺ claim (equal(e,a) iff byte-equal
canonical renders) holds on EVERY in-domain platform-executed assert.
Residues, all named: 23 LinkedHashMap + 3 ArrayList (wire-tree struct
cells — JsonCompare's domain by design, H4 "clean, keep") and 1
negative-zero (an assertEq -0.0 witness the H6 tree-scan missed — the
§4 residue policy works as specified).

SEAM FINDING: the corpus lane's grid verdicts do NOT flow through
AssertVerdicts — they ride the harness compare arms (GridCompare +
EngineTestExecutor equalScalar sites), the very arms R2 deletes. The
corpus [canon] line honestly printed 0. **R1b** = the GRID-channel
host reference render (H2 toCSV cell rules; Render.java is already the
SQL-side owner) + the instrument at the corpus row-verify seam. R2
cutovers stay gated on R1b's corpus-wide table.

Temporal wire limit recorded in-code: LocalDateTime cannot carry
WRITTEN subsecond precision (.000 vs .0) — the render emits minimal
precision; the census decides if a precision-carrying wire type is
ever needed (zero hits so far).

### PUREDATE WIRE CUTOVER LANDED (user: "sql types never escape the fetch; pure types on the wire")

**PureDateLiteral is THE wire temporal carrier** — the type half the
system already used (TypedCDate.value() was always PureDateLiteral);
this slice unified the DB egress with it. ONE conversion hop at the
fetch seam (Executor.unwrap tail: sql.Date→StrictDate,
Timestamp/LocalDateTime→DateWith* at DB precision, and the
precision-faithful VARCHAR convention now PARSES — written precision
finally survives onto the wire). java.time appears only as the
driver's extraction vehicle inside fetch() (BC-safe re-fetch).

Boxing principle settled (user ratified): box ONLY lossy carriers.
Temporals box (precision is per-value data no stamp supplies);
BigDecimal already carries scale (= PureDecimal, no box);
Int/Bool/String/Float are value-complete; struct identity lives in the
column schema. A per-value type tag elsewhere = a SECOND OWNER of a
stamp-owned fact — the desync disease.

Downstream, the comparison layer keeps NO java temporal arms:
PureAsserts (equality = record equals = engine's PRECISION-SENSITIVE
PureDate.equals; typeRank/withinRank via toInstantFloor; repr =
%+toEngineString), GridCompare (instant floor), CanonicalForm (exact
render incl. written precision — the wire-limit caveat DELETED).
toString on time-bearing variants = pure's print (+0000, H1);
toEngineString stays the parse-round-trip literal body.

**JDBC census SHRANK by 2** (PureAsserts + GridCompare rows retired) —
the D-arc dividend. Byte-channel byteEqual is now KIND-QUALIFIED
((kindClass, text) — render is not injective across kinds; spec §3
amendment; numeric tower = one class).

THE STRING-CARRIER BRIDGE IS DEAD in production. Its one real consumer
surfaced as 13 corpus regressions ("execution activities are not
recorded" — a misleading DOWNSTREAM symptom): the HARNESS's expected
side is decoded from engine golden TEXT, so temporal expectations are
strings; when its equalScalar compares flipped false the harness
declined its arms and asserts fell onto the platform wall. Fix at the
HARNESS's own seam: goldenEqualScalar decodes a string beside a wire
temporal via PureDateLiteral.parse (space→T), then the ONE production
lattice judges; non-parsing strings fail like pure. These arms delete
wholesale at R2. (Bisect lesson: -Drcorpus.only=aggregationAware fails
AT HEAD — scoped-seeding artifact — so scoped runs cannot referee this
family; full sweeps only.)

Also: H2Verify.norm PureDateLiteral arm (replay lane compares
instants, precision-blind BY CONTRACT); TestDataGenerator converts at
ITS registered read; five print pins updated to the H1 +0000 spec form
their old spellings predated. Referee: corpus 320+632/0 diverged,
scoreboard byte-identical; core suite 4228/0.

**PCT half of the cutover** (gate-6 referee round): (1) the EXPECTED
side of PCT temporal asserts lost written precision through the SQL
TIMESTAMP round-trip (engine spec pins NINE-ZERO subseconds:
timeBucket.pure:53 %…34.000000000) — fixed at the EGRESS ROOT:
Lowerer.scalarRoot projects a fragile-precision literal
(hour/minute/subsecond) as its WRITTEN spelling under the
TIMESTAMP-stamped output (the same VARCHAR convention the date() ctor
rides); MatchFold.dateLit's padded timestamp stays for computation
sites. (2) ExecuteLegendLiteQuery gains the ONE PureDateLiteral→
PureDate arm (engine's own parsePureDate reconstructs the
precision-exact class) and the five dead java temporal arms DELETE
(cut-over-hard; eval ledger enforced eviction-over-bump — the file
SHRANK). DIVIDEND: essential WIRE-BUG 11→10 — one engine-frontier row
now passes through our platform. STALE-JAR TRAP hit again
([[corpus-runs-need-fresh-core-install]]): pct referees the INSTALLED
core jar — install before every pct run.

**Temporal-decode ADJUDICATION (2026-08-22, closes the cutover's last
referee round).** The engine has TWO subsecond spellings that one
decode cannot match: relational reads are NINE-DIGIT
(DateFunctions.fromSQLTimestamp %09d — why its test authors assert
%…00.000000000 against DB cells) while computed/interpreted values
carry DERIVED precision (adjust → .338001, parseDate → .231). Ruling:
the wire carries the canonical-MINIMAL decode (seconds; subseconds
only when nonzero, trailing-stripped); the CORPUS harness compares
temporal goldens BY INSTANT (goldenEqualScalar — precision-blind by
contract, the engine's own cross-lane leniency, same rule as
H2Verify.norm); written precision rides the VARCHAR convention +
scalarRoot literal swap. Referee: corpus 320+632/0 baseline-exact AND
PCT 1115/0 AND core 4228/0 — first time all three agreed this round.
Diagnosis dividend: the aggregationAware "activities not recorded"
cluster was ALWAYS a downstream symptom of one harness temporal
compare declining — three different root causes produced the identical
13-test set (bridge deletion, literal-precision loss, golden-digit
mismatch); the set is a canary, not a family. Bisect lesson repeated:
-Drcorpus.only cannot referee aggregationAware (fails at HEAD,
scoped-seeding artifact) — full sweeps only.

### R1b LANDED: the grid-channel census (measurement decisive for R2)

The grid channel's render of record ALREADY IS the platform (H3
headline confirmed at the seam: toCSV verdicts compare engine expected
text against Render's SQL-side output — "the platform emits the header
now"). R1b instruments GridCompare.renderedText: byte answer = plain
string equality NEXT TO the lenient verdict.

**MEASURED (full corpus): 290 grid-text verdicts — 263 byte-exact
(90.7%), 27 pass ONLY via the kept leniencies, 0 residue.** The 27
classify into exactly two families:
- 6 row-order-only → R2's canonical ORDER BY burns them.
- 21 cross-engine float ARITHMETIC (H2 divides in exact decimal,
  DuckDB in binary double: golden 5.59 vs our 5.590000000000001) —
  VALUE differences no render can fix; the R0 §4 declared numeric
  policy rows, pinned for the R3 tolerance census.

Census PINNED in the runner (disagree ≤ 27, shrink-only, same-commit
justification to move). R2 grid scope is therefore: canonical ORDER BY
(6) + policy adjudication (21) + GridCompare arm deletion — no
emission work.

### R2a LANDED: the byte verdict of record (assertEquals scalars)

THE FLIP (ratified dual-verdict design): for scalar-kind assertEquals
sides, the DATABASE computes both canonical renders
(lowering/CanonicalRenderSql — Integer/Boolean/String/Float no-exponent
unfold/Decimal scale-normalized/temporal +0000 pipeline, every rule
mirroring the host reference CanonicalForm) and Java's whole remaining
act is comparing two DB-computed byte strings. The host lattice is now
the PERMANENT PARALLEL REFEREE: disagreement = census row, never a
rescue; declines (collection sides, unclaimed kinds, lowering
refusals) are COUNTED and fall back to the host verdict.

Wire shape: evalCanon (StatementExecutor) lowers each side like
evalValue and wraps the plan in the canon projection — one extra
SCALAR query per side, no cross-side SQL composition (sides can live
on different runtimes), all executeTyped channels untouched. Kind gate
on STAMPS (numeric tower = one class; cross-kind incl. empty-vs-empty
declines to the host — never a static guess).

**FIRST MEASUREMENT (ChannelB, all suites): 983 verdicts decided by
the DB byte compare, sql-verdict disagree = 0, declined = 228.** The
one divergence family the flip caught on round 1 — the
variant-identity channel's temporal texts already carry +0000 and the
canon double-suffixed (5 least/greatest/max Date tests) — fixed by
suffix normalization in temporalCanon; round 2 clean. Eval-ledger
AssertVerdicts 398→455 with written justification (verdict ROUTING
toward the database — the ledger's own direction). AssertVerdicts'
raw-array side decode leak (Timestamp→LocalDateTime) fixed to
PureDateLiteral while there.

R2b queue: collection sides (list canon in SQL), assertSameElements
(canonical ORDER BY), the 27-row grid ledger, tolerance census (R3).

### R2b LANDED: collection sides join the byte verdict

evalCanon's many-stamped sides render CanonicalForm.renderSide in SQL:
one element = the scalar form, N = '[a, b, c]' via STRING_AGG (the
input-order contract Render's corpus-proven grid text stands on),
empty = '[]' — so empty==empty and [3]==3-as-collection hold exactly
as pure rules them. MEASURED: sql-verdict 983→1083 agree, disagree
STILL 0, declined 228→128 (remaining: unclaimed kinds — Any-stamped,
class instances, wire trees, enums — the shrinking R2c ledger).

### V1–V5 LANDED: the register, the alarm, and two more families

docs/OPEN_REGISTER.md is now THE one list of every open item (rows
move to CLOSED in the closing commit — maintenance is part of every
slice's definition of done). Hygiene: the DUAL-VERDICT ALARM is pinned
(sqlDisagreeCount == 0 asserted in all five ChannelB suites AND the
corpus runner — a silent host/DB disagreement is now impossible);
evalCanon's broad catch adjudicated into the error-shape register (the
decline tunnel, counted); ArchUnit V3 pins host-verdict classes
reachable ONLY from the verdict/referee seam (one legit dependent
found and registered: JsonCompare, the one wire-tree walker).

V4: assertSameElements joined the byte verdict — both sides aggregate
ORDER BY canon text in the DATABASE (any consistent total order proves
multiset equality). V5: assertEq joined (identity rule still walls
loud first). THE ALARM EARNED ITS KEEP ON ROUND ONE: it caught
parseFloat('-000.000') → -0.0, where pure grants 0.0 == -0.0 but the
renders differed — adjudicated as ZEROS UNIFY (spec §2/§4 amendment:
equality binds the canonical form; negative zero moved INTO the
claimed domain with its first witness, H6's zero-witness claim
retired).

CENSUS: sql-verdict agree 1083→1376, disagree 0, declined 207;
R1a residue 27→26 (the negative zero became agreement). Families
DB-decided: assertEquals, assertNotEquals, assertSameElements,
assertEq. Next: the 207 declines burn (V6), then the decoupled-PCT
completion burn on a finished verdict system.

### V6 ROUND 1: the decline burn and the pair rules

Declines 207→91 across two rounds, disagreement HELD AT 0.

ROUND 2 (user-caught: "are we sure these are not OUR bugs?" — they
partly were): the plan-type refinement was CIRCULAR — OutputCol.type()
derives from the pure stamp (sqlTypeOf(NUMBER) = blanket DOUBLE), so
reading it back never carried information; rem's "DOUBLE" and times's
"FLOAT" were the stamp mapping, not reality. DELETED, replaced by
RUNTIME-KIND refinement from the fetched values — pure's own
Number-equality dispatch (the interpreted runtime decides by runtime
kind), so the "refinement can lie" special rule DIED and static
int×float re-tightened. Also: the wire's D-suffixed Decimal
representation (variant/identity VARCHAR channel: 2D, 1.0D)
normalizes in decimalCanon like the temporal +0000.

THE STANDING RULES: (1) numericValueCanon = full value normalization
of any numeric text; (2) PAIR RULES — pure's numeric equality is
NON-TRANSITIVE (8==8D, 8D==8.0, 8≠8.0): runtime/stamp-certain
int×float statically FALSE, Decimal-involved pairs by VALUE spelling;
(3) MIXED-KIND-COLLECTION gate — reclassified from declared-residue to
a CLAIMABLE canon-path limitation: the MAIN wire preserves element
kinds (mixed-identity carrier) but the canon side-query re-lowers into
a promoted column; the proper burn is variant-aware canon rendering by
runtime kind (OPEN_REGISTER F10). PrecisionDecimal IS Decimal; enum
values claimed. Census: sql-verdict agree 1492, disagree 0,
declined 91.

### V10 CLOSED: every remaining hack replaced by its derivation

(user: "lets fix all the hacks we did by doing the right fix instead")

- **V10a**: goldenEqualScalar's instant-blind compare RETIRED for the
  ENGINE CONVENTION — both sides normalize time-bearing precision to
  fromSQLTimestamp's nine digits, then EXACT record equality
  (AbstractPureDate.equals). Strictly closer to engine semantics: a
  date-only value no longer equals a midnight datetime.
  H2Verify.norm's instant funnel re-justified as DERIVED (both sides
  of that seam are DB reads = nine-digit by convention).
- **V10c-order**: STRING_AGG collection canon rides DuckDB's
  DOCUMENTED preserve_insertion_order default — a contract, not luck.
- **V10c-determinism**: double execution of assert sides is sound BY
  THE UPSTREAM EFFECT GATE (containsEffect routes effectful statements
  away before the K-arm) — derived, not assumed.
- **V10c-envelope**: the DECIMAL(38,18) float unfold REPLACED by a
  complete TEXTUAL EXPONENT SHIFT — any finite double prints
  fixed-point exactly (1e-30, 1e300 included). NEW dual-render
  conformance battery (SqlCanonConformanceTest) pins DB canon text ==
  host canon text across the magnitude battery — the two
  implementations of the one spec can no longer drift silently.

Remaining non-derived surface: F10 (variant-aware canon — the carrier
strips + mixed-collection decline) and the R3 tolerance census — both
registered, neither silent.

### X1–X4 LANDED: the engine-exact lattice — every grant deleted

(user: "fix all the hacks by doing the right fix instead")

The host lattice now IS EqualityUtilities.eq: NO cross-primitive-kind
equality (X1 integral×Decimal grant deleted — its witness was
mis-cited; X3 Float×Decimal fp-grant deleted; X4 the numeric-tower
kind class replaced by per-kind classes, cross-kind pairs decline to
the host's engine-FALSE); X2 Decimal equality tightened to
scale-sensitive equals, and the canonical Decimal render REVERSED to
scale-preserving (the old normalization followed the deleted grant).

THE TIGHTENING NAMED SIX REAL WIRE BUGS, each fixed at its seam
(the method working exactly as designed — every break was a
compensated defect, not a needed leniency):
1. round(Decimal, scale) lost the Decimal kind (SQL round → DOUBLE) —
   constant scales restore DECIMAL-at-that-scale (DecimalKindRules).
2. divide(,,scale) — same drift, same fix.
3. toDecimal fabricated scale 18 — input-stamp-driven (INTEGER → 0).
4. Scale≤0 Decimal LITERALS spelled dotless type INTEGER in SQL —
   fixed at the EGRESS ROOT only (RootLiterals; a blanket renderer
   cast measurably truncated VALUES columns and re-kinded divide).
5. Host integral equality OVERFLOWED BigInteger via longValue() — a
   pre-existing bug the deleted X1 grant had been MASKING
   (testLargePlus exposed it the moment the grant died).
6. INTEGER-declared cells decoding as scale-0 BigDecimal (DuckDB types
   beyond-int64 literals DECIMAL) — the declared-type-drives-codec
   decode guard (the H2 DOUBLE-arm doctrine).

Dual-verdict attribution added (ChannelB names the test that grows the
disagreement census). File guard honored by REAL extractions
(RootLiterals, DecimalKindRules). CENSUS: sql-verdict agree 1492,
disagree 0, declined 91 — full coverage retained with ZERO grants.

## X-SLICE CORPUS ADJUDICATION — the 2-ULP policy survives, EXPLICIT (2026-08-22)

The corpus sweep caught what the PCT lane could not: seven
tests/mapping/sqlFunction tests (acos/asin/atan2/log/tan) failed under
the tightened seam. Root cause was NOT the DECIMAL casts (the h2-exec
text floor recovered to 320 the moment the tests passed — the earlier
316 was a symptom, and the floor was reverted untouched): the new
goldenEqualScalar numeric BY-VALUE arm returned false on value
difference instead of falling through, which SILENTLY RETIRED the
declared 2-ULP dialect-arithmetic policy at that seam.

The diff is irreducible cross-libm drift: the corpus expected literals
were minted on Java/H2's math library; DuckDB's libm computes the same
transcendentals 1–2 ULP apart, and IEEE 754 does not require correctly
rounded transcendentals. No emission, type, or canon fix exists.

USER-RATIFIED RESOLUTION: the 2-ULP policy stays a DECLARED, COUNTED
leniency — never implicit-in-a-decline again:
- goldenEqualScalar: value-equal → true; value-differ → FALL THROUGH
  to the lattice (engine-exact kinds + the declared policy).
- sqlByteVerdict: byte-differ + all-finite-Double pairs within 2 ULP →
  policy verdict, counted in its own census row (sqlUlpPolicy in the
  [canon] summary) — the runtime-kind refinement must not silently
  retire what the unrefined-NUMBER decline used to route to policy.
The honest retirement design (same-arithmetic H2 referee: byte-differ
Double pairs re-compare EXACTLY on the goldens' own libm) is recorded
on V8/R3; user chose the counted policy for now.

V11 REGISTERED (user, twice): collapse the canon render INTO the side
query — SELECT value, canon(value), one execution, like the m2m
in-query JSON return; the containsEffect double-execution gate deletes.

## V11 LANDED — the single-query canon (2026-08-22)

The user's question ("why a second query? collapse it like the m2m
JSON return") became the slice: the canonical render now rides the
side query itself. wrapWithCanon (lowering-owned, exec-pure per
Invariant 6h — returns a CanonWrap the driver records on the
CanonRider) wraps the lowered plan as `SELECT value, canon(value)
FROM (plan) side`; the Executor harvests the appended VARCHAR columns
row-aligned with the value decode; the verdict layer frames and
compares. DELETED: prepCanon, runCanon, their records, and the V10c
double-execution soundness obligation (nothing runs twice).
StatementExecutor banked DOWN 2728→2326 stripped lines; the whole
verdict system shrank 355.

Derivation points settled en route:
- Candidate columns for unrefined Number stamps (OutputCol types are
  stamp-derived — the V6 circularity — so the plan cannot name the
  member): the DB computes every candidate render; the runtime value
  kinds SELECT the column, by the same refinement that gates the
  equality rule. Selection, never evaluation.
- LiteralFold YIELDS to a canon-riding side: the fold is a value-fetch
  optimization, but a requested canon is computed by the database.
  Same execution count as the deleted runCanon round trip. The fold
  survives as the last-resort value source for literals SQL cannot
  spell (NUL-bearing strings — testEmptyChar) with a counted decline.
  [OUTDATED 2026-08-28, user catch: the unspellable class no longer
  exists — AnsiSqlRenderer.stringLit splices chr(0) between quoted
  segments (landed 2026-08-22, user-verified against the database in
  the code comment itself). Every string literal is SQL-spellable;
  LiteralFold is purely a round-trip optimization on unfused rungs,
  with no last-resort semantic role.]
- The canon-exec decline tunnel (the V11 form of prepCanon's catch):
  a wrapped-query failure re-executes the BARE plan and declines the
  canon — a canon column can never poison the value fetch. Witness:
  the mixed-identity VARCHAR carrier ('7.345D') under candidate casts,
  wrap-time-undetectable (stamp-derived OutputCol) → F10's newest row.
- Guardrails honored the hard way: Invariant 6h dependency inversion,
  CanonRider registered in the exec closed register with its tenet
  argument, JDBC accessor ratchet 12→13 (one getString of DB-composed
  text bought a deleted execution), executeTyped split at the seam.

TRAP RECORDED TWICE OVER: ChannelB suites read -Dlegend.pure.root /
-Dlegend.engine.root SYSTEM PROPERTIES (like rcorpus), not env vars —
env-only runs silently referee the STALE $HOME checkout and fake a
7-test relation discovery regression (280 != 287). The X-slice pushed
with those pins unvalidated because ChannelB is not in allgates.

## X5 LANDED — equality.Key, DB-first (2026-08-22)

User directive: push the DB as far as it goes before any flip decision
— and the user's two design calls shaped the slice: (1) "doesn't the
key tell you the columns to compare?" — YES: the key list is model
metadata known at compile time, so keyed-instance equality COMPILES
(my Tier-C placement had conflated rule-origin with execution-place);
(2) "make it a JSON of the keys, with the class fqn" — adopted: JSON
is the FRAMING (escaping kills the concat-collision class), our canon
strings are the VALUES (to_json would be a second spelling authority),
'_type' rides the bytes, kind-tagged leaves keep the engine's
same-kind rule one level down, and the format converges with F10's
carrier direction instead of inventing a disposable one.

Ground truth (EqualityUtilities + _Class.getEqualityKeyProperties):
keys = stereotyped simple properties in generalization order; each
key's VALUES compare as collections under the ordered list rule;
classifier must match; keyless = FALSE (identity is upstream).

MEASUREMENT-DRIVEN CLAIMS (the decline histogram, not the plan,
picked the buckets): the predicted Pair mountain was actually ~9 rows —
the REAL mountains were Nil/empty sides (38) and GenericType stamps
missed by a ClassType-only arm (18). Landed: Nil claim + '[]' empty
unification, GenericType instance kinds, Pair struct canon, List
bare-array carrier canon, host key restriction. Declines 97→35,
agree +62, disagree 0 throughout, ceilings banked.

TRAPS: the driver resolved keys for ClassType only while every Pair
stamp is GenericType (fqnOf owns the mapping now); List is NOT a
struct at the SQL boundary (PureSql: bare array — the array IS
values); OutputCol layouts ARE trustworthy for carriers (PureSql
builds them from concrete generic args) unlike scalar stamps.
