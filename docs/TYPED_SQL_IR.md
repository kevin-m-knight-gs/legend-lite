# TYPED SQL IR — the label-transport contract (2026-08-24)

**User ruling:** fix correctly — no carry-helpers, no counted guards
("a pin that can only be counted is debt by definition"). This doc is
the CONTRACT, written before code, per the structural-acceptance
doctrine this program's failure ratified: *a slice that builds a
channel is done when the channel's contract is total — produced,
transported, consumed, enumerated — never merely when today's
witnesses pass.*

## 1. The problem (why the judge existed)

Pure types are correct but coarse (`Any` names no carrier); SQL runtime
metadata is correct but speaks SQL's vocabulary (VARCHAR cannot say
"spelling convention"). The CARRIER is a convention fact known only to
the code that builds an expression — the F10 label channel carries it
(LITERAL marks, OutputCol types). That channel was built with producers
and consumers but **no transport**: a mark on a collection does not
ride into `LIST_GET(collection, 2)`. `SqlTyping.judge` filled the gap
by RE-DERIVING types at consumption — the isRowCells disease at the
SQL layer: honest, deterministic, and structurally guaranteed to have
blind spots (three patched in one day: error() branches, subselect
boundaries, lambda parameters — each a place construction knowledge
was discarded and the reconstruction had a hole).

## 2. The design: the type is a node fact

Every `SqlExpr` answers `type()` — its `SqlType` or `UNKNOWN`.

- **Compositions STORE their type as a record component, computed
  ONCE at construction** — inside the canonical constructor or the
  existing factory (`Call.of` etc.), so composition construction
  sites change ZERO times; prior constructor arities remain as thin
  delegates that compute-and-delegate. `Cast` → its target; `Call` →
  the function's typing rule over children's stored types (the
  judge's switch MOVES into the rule table the factories call);
  `Case` → the branch family's shared type, `NullLit`/`error()`
  branches admissible (bottom); `Group`/`CompactList` → inner.
  NO lazy memoization and NO side-cache — a memo map is knowledge
  carried BESIDE the tree (mutable global state, lifecycle, an
  allowlist row): the type is a property OF the tree, stored on the
  node, immutable with it. (User catch 2026-08-24 — the lazy variant
  was touch-aversion violating the design's own defining sentence.)
- **Leaves are SUPPLIED at construction**: `Column` and `Lambda`
  parameters take their `SqlType` from the builder — which always has
  it in hand (the projection it just built, the DDL, the source list's
  element type). Census on the parked branch: 98 Column sites (60 in
  Render/Scalars/Fold/Comparators/Lowerer), 37 Lambda sites, 46 node
  kinds.
- **UNKNOWN is explicit and mortal**: a leaf whose type is genuinely
  unknowable at construction (correlated outer reference) declares
  UNKNOWN. Label consumers treat UNKNOWN as "not this carrier" —
  exactly today's judge-null semantics, no behavior change. UNKNOWN
  sites are greppable; their count RATCHETS DOWN and the ratchet
  hardens into a constructor invariant at zero (pin→invariant
  lifecycle, debt-to-zero program).

## 3. The contract, enumerated

**Producers (type knowledge enters):** the 6 mark-construction sites
(the claim's collection, MixedElems.select/markLiteral, the sort
recipe, mixedNumericArray, rowMajorCellList) — under this design they
simply build nodes whose `type()` is LITERAL/Array(LITERAL); the Cast
marks REMAIN as the physical VARCHAR identity but stop being the only
knowledge channel. Leaf typing: every Column/Lambda construction.

**Transport:** the nodes themselves. There is no second mechanism.
Building an expression from typed parts yields a typed expression; a
derived value CANNOT lose its type because the type is not carried
beside the tree — it IS a property of the tree.

**Consumers (type knowledge read — all become `.type()` reads):**
egress OutputCol stamping (Lowerer scalarRoot), the canon's literal
short-circuit (CanonicalRenderSql), the print arm (Scalars
pureToString), equality emission (MixedEncoding), membership needle
arms, Dedup's mark preservation (becomes automatic), CarrierStrategies
(dialect), Executor unwrap (unchanged — reads OutputCol).

## 4. Structural acceptance — the deletions ARE the proof

1. `SqlTyping` consumption-side judging DELETES (a residual
   `SqlTyping` may survive only as the shared typing-rule TABLE the
   nodes call — one owner of per-function rules; zero call sites that
   walk a finished tree to rediscover its type).
2. `LambdaWire` DELETES ENTIRELY (ThreadLocal scope, per-argument
   judge loop, comparator FQN registry — a typed Lambda node carries
   its parameter types).
3. OutputCol label re-derivation at scalarRoot DELETES (reads the
   root's `type()`).
4. The parked claim (`wip/slice3-claim-on-untyped-ir`) re-lands
   needing ZERO of its four compensations — the final examination.
5. Every behavior pin holds: corpus numbers, PCT censuses,
   byte-verdicts, healed probes. Node-type vs judge divergences are
   judge blind spots surfacing — each examined, none waved through.

## 5. Migration order (on main from 2ae83855)

M1: `type()` skeleton on the 46 node kinds + the moved rule table;
    leaves default UNKNOWN (compiles, changes nothing, judge still
    live — the two channels COEXIST and a differential probe compares
    them across the corpus: every disagreement is a pre-logged blind
    spot).
M2: leaf typing — the 98 Column + 37 Lambda sites, file by file
    (Render → Scalars → Fold → Comparators → Lowerer → tail), UNKNOWN
    ratchet shrinking per commit.
M3: consumers flip to `.type()`; deletions land (acceptance §4);
    full chain.
M4: the claim re-lands from the wip branch; slice 3 closes for real;
    full chain; push.

## 6. THE HOMEWORK (2026-08-24, user-ordered: "stop guessing") —
## every §2 claim re-graded with receipts

**DISCOVERY: this program already existed.** SqlTyping's own header:
"TYPED-IR Slice 1 ... instrument → census → flip. In Slice 1 it only
measures — OutputCol labels stay stamp-derived and the census compares
the two." The instrument (SqlTypeCensus, 529 lines, admissibility
relation user-audited as T3 on 2026-08-23) SHIPPED and has run on
every executed plan since. F10 then consumed the measuring instrument
as a live authority — the flip never ran as a designed step. This doc
IS the flip's charter, now with the instrument's data.

**VERIFIED — the judge's rules move freely.** Full read of
SqlTyping: `scope` is consulted ONLY for Column resolution and
LIST_TRANSFORM param binding — every other rule is a function of
children's types. The switch redistributes into nodes mechanically.

**VERIFIED — the leaves are in-hand.** Per-site reading of the top 60
Column constructions (Render 19: all read names off OutputCols that
carry .type(); Fold 10: sources in-hand with schemas — one
typedColumn(source,name) helper; Scalars 15 + Comparators 9: lambda
param refs — typed via the Lambda node's declared params; Lowerer 7:
RelationType.Column carries .type()). ZERO genuinely-unknown leaves
found; the UNKNOWN ratchet starts near zero.

**MEASURED — the label-lie census (full corpus, 24,508 plans):**

    cols: agree=44417 admissible=4265 mismatch=250 untyped=3692
          bottom-ok=91 bottom-mult-backlog=6472
    wire: agree=24271 delivered=7100 adopt-pending=130 diverge=179

- **92% of label-comparable columns already honest** (agree +
  admissible). The flip is a correction, not an upheaval.
- **The lie payload = 250 mismatches, enumerated with witnesses**
  (97x declared VARCHAR vs computed BIGINT; 48x DOUBLE vs
  Decimal(18,6); ...) — each adjudicates to a label fix or a new
  admissibility row during M3.
- **The untyped tail = 3,692, concentrated in FIVE rule families**:
  Reducer 2,508 (aggregate numeric promotion — the header's own
  deferred rule), ScalarSubquery 340, COALESCE 247, Case 185,
  TIMES/MINUS/UNNEST tails (mostly cascades of the same). M1 writes
  the reducer-promotion rules; the cascades collapse with them.
- **bottom-mult-backlog 6,472 = the T4 nullability-lie program,
  quantified** (5,707 BIGINT + 598 VARCHAR + 161 BOOLEAN
  null-under-required-multiplicity) — T4 starts from this number.
- wire adopt-pending 130 = the HUGEINT builder-leg register entry,
  confirmed live.

**DOCTRINE ADDENDUM (why F10 missed all this):** the numbers above
were printed at the end of every fully-green corpus sweep and read by
no one. An instrument without a consumer is a receipt without an
audit. The census summary becomes part of gate output review, and any
slice consuming a measured channel must cite the instrument's current
numbers in its charter.

## 7. SCOPE HONESTY (user question 2026-08-24: "does this fix our
## problems?") — the two layers, and the census caveat

**Where Any lives in this vocabulary:** pure's Any never appears by
name — Any IS the pure type that maps to a CARRIER choice, and it
shows up as JSON (variant) and LITERAL (spelling) rows. The 3,584
admissible VARCHAR<-JSON rows are largely Any-lane traffic.

**CENSUS CAVEAT:** §6's numbers were measured on MAIN, where the
claim is reverted — the LITERAL carrier barely runs, so the
Any-carrier surface (this week's actual disease site) is
UNDERREPRESENTED in them. The Any-lane's own census becomes
measurable only when the claim is live on a green sweep — it is
therefore part of M4's ACCEPTANCE: the re-landed claim's first green
sweep must show the LITERAL census rows, reviewed, with every new
mismatch class adjudicated.

**The two layers this arc's bugs decompose into:**
- TRANSPORT (labels dying at boundaries — double-decode, judge blind
  spots): fixed STRUCTURALLY by this arc.
- CARRIER POLICY (which representation an Any position rides; how two
  sides of an equality agree; json's kind limits): decided by the
  carrier rule + conform-by-emission doctrine, IMPLEMENTED by the
  parked claim; this arc makes the policies reliable, it does not
  produce them.

**Explicitly NOT fixed by this arc** (bounded completion — the
overclaim F10 made is not repeated here): T4 nullability lies (6,472,
quantified in §6), cross-kind sort order (bucket item), G4 corpus
latency, corpus host-side->SQL-verdict migration, HUGEINT
adopt-pending (130). All queued elsewhere in PROGRAM_MAP.

## M1 RECEIPTS (2026-08-24, executed)

**Shape as landed.** The stored fact is the three-valued
`SqlTyping.Verdict` (Typed/Bottom/Unknown — §2's "SqlType or UNKNOWN",
with NullLit/all-null compositions as bottom), a trailing record
component on all 36 SqlExpr kinds. Compositions compute it in the
COMPACT canonical constructor (the component structurally cannot lie —
a caller-passed verdict is overwritten by the rule); prior arities are
compute-and-delegate; construction sites changed ZERO times. Column and
Lambda are supplied-leaf doors (M1 default UNKNOWN; Lambda's
`withChildren` PRESERVES the supplied verdict like Column's identity
arm — no rule can recompute builder knowledge).

**The judge is now a leaf-binding REBUILDER** (`SqlTyping.rebind`):
it re-types Column leaves from scope (+ LIST_TRANSFORM's parameter
binding) by rebuilding, and reads the rebuilt root's stored verdict —
so the rules run ONCE, in the node constructors, for both channels;
the Slice-1 switch is DELETED, not copied. Verdict-level corners
extend deliberately: bottom transports through element-preserving ops
(LIST_GET/StructGet/CheckedOne of the NULL value = the NULL value),
all-bottom COALESCE/Case = bottom. Zero census movement resulted
(bottom buckets byte-identical to baseline).

**Reducer promotion rules — empirical, DuckDB 1.5.0** (probe
2026-08-24, fresh statements per query, ResultSetMetaData ground
truth): SUM int-family/BOOLEAN→HUGEINT, DOUBLE→DOUBLE,
Decimal(p,s)→Decimal(38,s); COUNT→BIGINT (arg-free); AVG
numerics→DOUBLE, temporals→TIMESTAMP; moment family
(STDDEV/VAR/CORR/COVAR)→DOUBLE; MIN/MAX/ANY_VALUE/MODE/QUANTILE_DISC/
ARG_MAX/ARG_MIN identity; MEDIAN/QUANTILE_CONT ints→DOUBLE, Decimal
stays, temporals→TIMESTAMP, MEDIAN alone identity on VARCHAR/BOOLEAN
(LITERAL deliberately UNKNOWN — interpolation breaks the spelling
grammar); STRING_AGG→VARCHAR; LIST→Array(t); BOOL_AND/OR→BOOLEAN.
Window-wrapped Reducers keep their promotion (probed);
ReduceCollection reads the SAME rule through the collection's element
(probed: list_aggregate matches). Markers and unprobed inputs stay
UNKNOWN.

**Census A/B (full corpus, 24,508 plans, scoreboard byte-identical,
wire row identical — stash-bisected same-day):**

    main     : agree=44417 admissible=4265 mismatch=250 untyped=3692
    M1       : agree=46684 admissible=4272 mismatch=517 untyped=1151
    node diff: agree=30880 pending-leaf=28307 diverge=0

- untyped −2541: the Reducer hole 2508→71; COALESCE 247→188, Case
  185→175 (cascade collapse); the residual tail is ScalarSubquery 340,
  COALESCE 188, Case 175, TIMES 91, UNNEST 87, Reducer 71, ABS/
  INT_DIVIDE/ROUND/WindowCall singles.
- mismatch +267, FULLY ACCOUNTED: 231x NEW `BIGINT <> HUGEINT` (SUM
  widening — the wire adopt-pending register entry surfacing at the
  column layer) + 33x NEW `DOUBLE <> HUGEINT` (integer sums under
  Number-erasure labels — same fact) + 3x growth of the PRE-EXISTING
  `Decimal(38,18) <> DOUBLE` 19→22 (avg(Decimal)→DOUBLE, previously
  untyped). All three adjudicate with the HUGEINT builder-leg /
  label-fix table at M3. The 2x `LITERAL <> VARCHAR` rows are
  PRE-EXISTING (the conformance-cast typed-seam pair, registered).
- **diverge=0**: the node channel and the judge NEVER disagree where
  both know — every gap is the enumerated M2 leaf backlog
  (pending-leaf 28,307 = Column 26,790 + Reducer-over-columns 1,300 +
  Case 153 + tail), pre-logged by shape.
- Gate-output census display deepened 20→60 classes (the top-20 cut
  hid the mismatch tail — the doctrine addendum applied to itself).

## 8. Relation to standing programs

Debt-to-zero: this IS the lowering-layer "sane story" entry (sibling
of the JDBC story). G4 latency drill: run AFTER M1's differential
probe exists (it measures judge cost directly). The corpus SQL-verdict
migration and all bucket work queue behind M4.

## M2 RECEIPTS (2026-08-24, executed same day — the backlog had ONE
## hot door)

**pending-leaf 28,307 → 0 in one slice; diverge stays 0; both PINNED
(equality, not ceilings) in the corpus runner.** The census-visible
backlog was not spread across the 105 Column sites — it flowed through
`Fold.sourceColumn` (the ONE by-name resolver, which always held the
claiming source's OutputCol) plus a handful of schema-loop sites
(Render's OutputCol reads, Lowerer's union/join projection loops, the
lateral-elem projection, ValueCollections, CanonicalRenderSql's
valueCol). Stamping doors added on the node: `Column.of(table,
OutputCol)`, `Column.of(table, name, SqlType)`, and the lookup form
`Column.of(table, List<OutputCol>, name)` (stamps when claimed, plain
otherwise). Derived references TRANSPORT (CarrierStrategies.remapAlias
and UnqualifyPivotArgs carry `c.type()` — a rebuilt reference never
drops leaf knowledge).

**Deliberately NOT stamped** (each with a reason, none silent):
late-bound arms (raw grids, pivots — schema genuinely absent at
construction, the charter's mortal-UNKNOWN case); lambda-parameter
placeholders in Scalars/Comparators/Dedup and the row-scope
lambdaResolver (no mechanical schema in hand; zero census-visible
effect — the differential proves the label channel never reads them
today; their mechanism is decided at M3 with LambdaWire's deletion).
FoldTest's `col()` helper now builds stamped expectations from the
fixture's own declared outputs — the two "failures" during the slice
were the stamp ARRIVING, pinned as the new truth.

**Remaining M3 pre-work visible from here:** the untyped tail (1,151)
is now rule coverage, not leaf debt — ScalarSubquery 340, COALESCE
188, Case 175, TIMES 91, UNNEST 87, Reducer 71, ABS/INT_DIVIDE/ROUND/
WindowCall singles. The 517 mismatches remain M3's adjudication table.

## ARCHITECTURE REVIEW (2026-08-24, user question "is the
## architecture right" — four standing tensions, recorded not absorbed)

1. **Reference stamps duplicate source knowledge.** A stamped Column
   carries a copy of what its source's OutputCol declares — the
   chartered types-on-nodes choice over a typing-environment. Failure
   mode: a rewrite re-points a reference across sources without
   restamping (stale stamp). Mitigations: rewriters TRANSPORT
   (remapAlias/unqualify), the two equality pins, and M3's deletion of
   the competing channel. Structural prevention is impossible at the
   Column (it does not know its source) — the pin carries invariant
   duty here, permanently.
2. **THE M3 FLIP PRECONDITION (slice 0): LIST_TRANSFORM param
   binding** is the ONE knowledge the judge still has that the tree
   does not (rebind binds params; node-channel lambda bodies are
   unstamped). The two production judge sites (Scalars literalWire,
   Lowerer boxing arm) judge SUBEXPRESSIONS — the root-level
   differential does NOT certify them. Before flipping either site:
   a site-local differential (judge-vs-node at those exact call
   sites, corpus + PCT) proving zero divergence, OR stamp the
   param references in the LIST_TRANSFORM builders.

   **EXECUTED same day — and it FIRED.** The site differential
   (SqlTyping.judgeSite, transitional) measured corpus 27,559/0 but
   ChannelB-standard 1,580 agree / **3 diverge** (StructGet
   node=UNKNOWN judge=BIGINT x2, Call node=UNKNOWN
   judge=Array(BIGINT)) — the sort/topBy pipelines' param reads, on
   exactly the Any/LITERAL lane §7's census caveat called
   underrepresented. A blind flip would NOT have been neutral (the
   boxing arm would have stopped boxing those roots). Fix: the
   MECHANICAL param door `Column.param(name, collection)` — element
   of the collection's STORED type, UNKNOWN otherwise, never
   hand-reasoned — applied to makeStrings/x, sort/_st_i+_st_e,
   topBy/_by_i+_by_e, contains/_nv, variant-conform/_cv,
   toString/_ts (range params ride the same rule: RANGE_FN types
   Array(BIGINT)). Deliberately NOT param-stamped: fold accumulators
   (acc type is not the element's), zipper-subselect column refs.
   Re-measured: chB-std site 1,583/0, pending-leaf 0. The judgeSite
   probe stays until the flip lands, then deletes with the judge.

   **Round 2 (same day): the full five-suite ChannelB sequence found 4
   MORE** (`LIST_TRANSFORM(LIST_FILTER, λ)` judged Array(VARCHAR) —
   the shape only composes in the shared-JVM full run; standalone
   suites were clean — measure the lane WHOLE, not per-suite). Their
   builder is the general map lane: the user lambda lowers with
   unstamped param refs BEFORE the rule attaches it to a collection.
   Fix: `Lambda.bind(lam, collection)` — the ATTACHMENT-SITE door
   (rebuild-substitute single-param refs stamped as the collection's
   element; shadow-stopped, subquery-bounded — the judge's rebind
   moved to construction) — applied at ListEncodings.map,
   removeDuplicatesBy's key transform, and the class-property map
   lane. ALSO closed: 9 pending-leaf pivot GROUP-column roots
   (sourceColumn's Pivot arm claimed every name late-bound; the
   group columns its outputs DO declare now stamp via the lookup
   door — dynamic pivot columns stay mortal-UNKNOWN). End state:
   all five ChannelB lanes at node pending-leaf=0 diverge=0, site
   diverge=0 — and all THREE pinned as equalities in the five
   ChannelB suites (the corpus-runner pins do not cover those JVMs;
   the site pin freezes the flip precondition until the flip lands).

   **Recorded coverage gap (slice-0 audit):** G6/G7's PCT JVMs
   MEASURE the invariants (the census probes every executed plan)
   but ASSERT nothing — the generated PCT classes carry no census
   pins; divergence there stays silent until the shape recurs on a
   pinned lane. Closing it needs an assert-hook in the PCT harness —
   its own small change, queued, not smuggled into a slice. Also
   recorded: `rebindParam` substitutes by NAME (unqualified + param
   name) — safe today because only lambdaResolver mints unqualified
   Columns pre-dialect (substituteRef's long-standing convention),
   but it is a convention, not a structural guarantee.

   **Deletion-order correction (slice-0 audit):** the judge cannot
   fully delete at the site flip — the census differential is its
   LAST consumer (it exists to compare the channels). Order: flip
   the two sites → judgeSite probe deletes → label flip with
   adjudication → judge/rebind delete WITH the differential's judge
   side (census collapses to node-vs-declared).

   **THE FLIP EXECUTED (2026-08-24, same day):** both production
   consumers read `e.type()` / `x.type()` — the tree is the label
   authority at those sites. The site probe (judgeSite, counters,
   witnesses, ChannelB site pins, census summary segment,
   ArchitectureTest registration) DELETED in the same slice — no
   adapter hedge, the measurement's verdict lives here and in the
   slice-0 receipts. The judge's remaining callers: the census
   differential (its scope channel) and SqlTypingTest — both die at
   the label flip.
3. **Promotion rules commit the IR to reference-backend (DuckDB)
   semantics** pre-dialect. Fine while H2 is advisory (wire census
   absorbs it); if H2 ever graduates to a verdict backend, type()
   needs a dialect story. Single-compiler tenet holds today.
4. **`Verdict` is judge vocabulary in the permanent tree.** After the
   M3 deletions there is no judge — rename the stored fact (and
   consider folding Bottom/Unknown into the type lattice) in the
   deletion slice, per the standalone-SQL-library vision.

Endgame beyond the charter: once labels flip to type()-derived (+
admissibility) at M3, OutputCol stops being an independent copy and
tension 1's duplication collapses with it.

## THE LABEL FLIP — EXECUTED (2026-08-24; mismatch = 0, pinned)

**Mechanism:** the admissibility relation MOVED from the census into
SqlTyping (one owner; the census reads the same relation), and
`SqlSelect`'s canonical constructor RECONCILES labels against the
projections' stored types — the compact-ctor idiom again: a label is
a property of the select, computed once; equal or registered keeps
the pure-contract erasure, anything else was a lie and adopts the
wire. No fixer pass exists.

**The 517, adjudicated (witness table above §6):** 264 ADOPTED
(BIGINT/DOUBLE labels under integer-sum wires widen to HUGEINT — the
adopt-pending register entry executed: corpus wire adopt-pending
130→13); ~249 REGISTERED with justifications in the relation (the
Float/Number-erasure numeric carriage, the String-slot coercion, the
pure-Decimal erasure slot incl. its float coercion, the collection
carrier under the element label); 2 FIXED at the emitter
(mixedNumericArray now returns the construction-site Array(LITERAL)
mark — the sort arm's idiom; the tree carries what the label knew).

**Receipts:** corpus mismatch 517→0 and chB all-lanes mismatch→0,
BOTH pinned as equalities (the label-lie program instrument→census→
flip COMPLETE); scoreboard + h2-exec byte-identical; corpus wire
diverge 179→114 and adopt-pending 130→13 (ceilings RATCHETED to
measured; chB adopt-pending 110→103). chB-std wire diverge 73→74,
fully accounted: −4 healed (percentile Float-carriage delivered;
letFn's JSON label adopted its VARCHAR wire and now AGREES) / +5
reclassified honest (testLargePlus/Times literals carry true
Decimal(p,0) labels vs HUGEINT arithmetic wires — closes when the
PLUS/TIMES promotion rules land, never via a false delivery row:
HUGEINT is not a value-subset of Decimal(19,0)).

**Judge status after the flip:** its last callers are the census
differential's scope channel and SqlTypingTest. The judge-free
permanent tripwires now stand: mismatch==0 (labels cannot lie),
node-diverge/pending-leaf==0 (the tree knows its leaves), and the
WIRE census (ground truth per execution). Next slice: delete
judge/rebind + the census's judge side, then the Verdict rename.

## CONFORM-BY-EMISSION — USER RULING, REFEREE VERDICT, AND T4 LEG 1
## (2026-08-24, after the label flip)

**User ruling:** decode-by-label coercion (an int column under a
String property stringified by the JDBC driver) is HOST-SIDE VALUE
EVALUATION — tenet-#1 territory. The conversion lowers to SQL. The
"engine doesn't cast" defense was the named anti-pattern (engine =
semantic spec, never architectural spec), and text divergence is the
established rescue lane (632 of 952 h2-verified tests already
diverge, row-verified).

**Experiment and referee verdict (same day):** a type-pair CONFORM
verdict at the SqlSelect seam (CAST(expr AS declared) for the
String-slot and Float-erasure pairs) went RED on exactly the case
the T3 honesty note predicted: castErasure 42 -> 42.0 — the DOUBLE
label is ALSO the abstract-Number IDENTITY carrier, and a type-pair
cannot distinguish it from concrete Float (VARCHAR likewise:
quarter-extraction broke). CastPolicy already spells the doctrine:
"a WIDENING cast is a type ASSERTION — converting corrupts."
REVERTED at that seam; the two pairs stay ADMITTED with the verdict
recorded on the rows.

**What survived the referee — pure-Decimal erasure ADOPTION:** the
Decimal(38,18) erasure rows now ADOPT the wire's own precision
(strictly more truthful, decode-identical). Corpus: admissible
4,523->4,443, wire agree +82, wire diverge 114->56 (RATCHETED),
mismatch stays 0, scoreboard byte-identical.

**T4 LEG 1 (the ruling's faithful home, next):** STAMP-GUARDED
conformance at the mapping-read seam in the LOWERING — where the
pure stamp distinguishes concrete Float/String (conversion correct:
emit the cast) from abstract Number/identity (assertion: no cast).
CastPolicy is the owner; the witnessed families are the scalar-map
u_map path, the plain project path, and the groupBy aggregate
projections. As concrete-stamp sites conform by emission, the two
ADMITTED coercion arms drain to agree and delete. Expected fallout:
text-matched -> rescued reclassifications with rescue-ratchet moves,
each recorded. VARCHAR<-JSON's emission stays behind its own
golden-text gate.

## THE JUDGE IS DEAD (2026-08-24) — §4 acceptance status

**Deleted:** `SqlTyping.judge`/`of`/`rebind` (the consumption-side
judging and its transitional leaf-binding rebuilder), the census's
judge-vs-node differential and scope resolver, and the node
pending/diverge pins (retired WITH what they guarded — parity was
pinned zero on every lane first, and the census read the TREE's own
types thereafter: numbers byte-identical). The tripwire handover:
mismatch==0 (labels cannot lie, every lane), the WIRE census (ground
truth per execution, ceilings ratcheted), and a NEW untyped ceiling
(1,116, corpus) — untyped is now both rule-coverage debt AND the
leaf-regression signal, ratcheting down as rules land.

**Renamed:** `SqlTyping.Verdict` &rarr; top-level
`com.legend.sql.TypeFact` (Typed/Bottom/Unknown) — the fact was never
a judgment; judge vocabulary is out of the permanent tree. SqlTyping
survives as exactly what §4.1 licensed: the rule table (+ the
admissibility relation and label reconciliation the flip added).

**§4 acceptance:** (1) consumption-side judging deleted ✓ (rule
table survives, zero call sites walk a finished tree); (2) LambdaWire
— exists only on the parked branch; its deletion IS M4's re-land;
(3) scalarRoot label re-derivation — NOT yet deleted: its LITERAL
arms still choose the LABEL SPELLING (scalar LITERAL vs the tree's
Array(LITERAL)) and a blind delete would change decode dispatch; it
retires as its own examined slice with the carrier work; (4) claim
re-land = M4; (5) every pin held through every slice ✓.

## ARITHMETIC PROMOTION RULES (2026-08-24, probed then written)

DuckDB 1.5.0 matrix probed (full receipts in the session ledger; the
probe script pattern is the reducer probe's): any DOUBLE operand
wins; all-integer promotes to the widest member (INTEGER/BIGINT/
HUGEINT); DIVIDE was already DOUBLE-total; DECIMAL arithmetic follows
version-specific precision formulas — deliberately UNKNOWN, counted.
Rules written: PLUS/MINUS (incl. the probed date arms: DATE ± int →
DATE, DATE−DATE → BIGINT), TIMES/MOD/REM, NEGATE/ABS (domain
identity), INT_DIVIDE (width-keeping), ROUND (int identity /
DOUBLE), CEILING/FLOOR (→ DOUBLE; Decimal(p,s) → Decimal(p,0)); the
NULL value propagates (arithmetic is strict).

Corpus: untyped 1,116 → 737 (ceiling RATCHETED); **wire adopt-pending
13 → 0, HARDENED to an equality pin** — every integer-aggregate and
arithmetic label now speaks its wire (the testLargePlus family
closed: the promoted types adopt at reconciliation). Remaining
untyped tail: ScalarSubquery 340, COALESCE 174, Case 101, UNNEST 68,
LIST_CONCAT 18, DATE_TRUNC_DAY 14, PLUS-with-decimal 12, WindowCall
7 — next rules slices (COALESCE/Case need branch-family promotion:
probe CASE/COALESCE mixed-member results first).

## T4 LEG 1, ATTEMPT 1 — THREE REFEREE VERDICTS, APPLICATION PARKED
## (2026-08-24; the ruling stands, the seam was wrong)

The mapping-read conformance was applied at the PROJECTION boundary
by OUTPUT-NAME schema lookup and the corpus rejected it three ways:
1. **The flat class form pins the engine's WIRE-typed plan metadata**
   (resultColumns=INT + castless SQL, hard-asserted by 12 plan tests).
   FIX KEPT: `TypedProject.wireForm` — RelationalRootForm carries the
   lane fact; the class lane's contract is the engine's decode-side
   coercion, by observable plan pins.
2. **Deep-join schemas collide output names** — the by-name lookup
   conformed an Integer column to String off a same-named column
   (testJoinIsolationDeeper). Name lookup across schemas is the exact
   re-derivation smell this program exists to kill.
3. **A conform cast on ONE union branch breaks branch-projection
   identity** and reorders the merged rows (union propertyLevel
   test6, sqlQueryMerging). Emission at a shared boundary perturbs
   structural identities downstream consumers key on.

**KEPT (sound, behavior-neutral):** `Cast.conform` provenance (the
typed-level seam the cast-provenance register demanded) + rebuild
transports (Scalars/SqlRewriter/UnqualifyPivotArgs/DecodeShapes) +
engine-TEXT elision (EngineStyleH2/DB2 — the wire-coercion
precedent) + `TypedProject.wireForm`. **REMOVED:** conformRead/
conformProjections and their call sites; all four execution-text
ratchet bumps reverted (state byte-identical to the arithmetic
slice).

**THE SOUND SEAM (attempt 2, queued):** the PROPERTY-READ PAIRING —
where a mapped property meets its physical column uniquely, no name
lookup, before any shared boundary — emitting `Cast(..., conform)`
per read. The kept plumbing is that attempt's infrastructure. The
159 rulebook rows + 48 wire rows it will drain remain counted.

## G4 LATENCY DRILL — VERDICT (2026-08-24, post-M1, measured first)

**The 389s does not reproduce.** Six caffeinated G4 runs same day,
correct roots (`-Dlegend.engine.root`/`-Dlegend.pure.root` at the
neemsandv checkouts): baseline main 136.1s; M1 135.5 / 135.5 / 137.8 /
139 (in-chain) / 141.7s — stable ±5%, all UNDER the ~185s reference.
The 389s trigger figure matches the two failure modes GATES.md already
documents (slept/preempted run — the 722s→34s precedent — or the
stale-root tell, "~320s instead of ~90s"); treat it as a measurement
artifact, not a regression. Same-day corollary: G8 read ~250s against
its ~63s GATES.md pin in BOTH full chains (246s and 250s — consistent,
so NOT a slept-run artifact; the gate likely genuinely grew under
commits since the 2026-08-15 re-pin). Needs its own isolated
decomposition per the GATES.md budget rule — recorded, not absorbed;
not caused by this arc (same reading on the M1-only chain).

**Judge/differential cost ≈ 0.** The M1 differential probe (judge over
every executed plan, now via rebind) plus eager node typing costs
nothing visible: baseline-vs-M1 wall delta is inside the machine's
wobble. Bounded above by ~3s on a 136s run.

**Where the wall actually is** (timing-ledger.txt, M1 run, jvm.wall
139.2s / test.wall 138.97s over 2,575 tests): query.exec 6.84s
(24,508 stmts; GRAPH 2.06 + TABULAR 2.59 + SCALAR 1.41 + COLLECTION
0.79), ctx.overlay 1.37s, session.open 1.34s — ~129s is UNBUCKETED
middle-end (parse/compile/lower/harness/verdict). Any future speed leg
starts by bucketing that, not by touching the SQL layer. Ledger trap
recorded: gate 8's `-am clean` wipes core/target — read the ledger
straight after G4, or re-run G4 standalone.

## E3 — THE NULLABILITY DIMENSION (designed 2026-08-26; user ruling:
## E3 before F10 slice 4, "keep the order")

**The problem (E2E audit findings 2+3, all receipts above and in
TYPE_E2E_AUDIT_2026_08.md):** `OutputCol.nullable` exists but its
AUTHORITY is the pure multiplicity echo ("nullable = multiplicity, one
authority" — seam C), and `TypeFact` has no nullability dimension, so
the expression channel cannot transport slot truth: a Column re-read
over a nullable child slot re-derives `[1] → nullable=false`. Measured:
925 corpus / 49 pct wire-NULLs under always-present labels
(ceiling-pinned), plus the 520-pairing static census (fc2fe6bd) of
class-mapped `[1]` properties over DDL-nullable columns.

### The decision: (a) with (c) as its composition arm

Nullability becomes a component of `TypeFact.Typed` —
`Typed(SqlType type, boolean nullable, boolean tolerated)` — **assigned
at construction**, exactly the M1 playbook replayed for a second
dimension. The rejected options, by the program's own doctrine:

- **(b) reconcileLabels consulting the from-tree at consumption** is
  the judge disease by definition — re-deriving at consumption what
  construction knew. This program's entire arc (M1→judge deletion)
  exists because that architecture regenerates blind-spot patches.
- **(c) alone (per-SqlFn NULL-propagation rules with no stored
  dimension)** has no transport: the Column-re-read-over-child-slot
  failure is exactly a missing stored fact. The rules are needed — but
  they are the COMPOSITION arm of (a), living in the compact canonical
  ctors like every type rule since M1, running once.

Fact semantics: `Bottom` IS the null value (nullable by definition —
it is what the dimension means); `Raises` never yields (vacuously
non-null); `Unknown` stays structurally impossible (EQ-0 pins).

### Authorities (where nullable is KNOWN at construction)

1. **Base table columns: the DDL rule** — `notNull || primaryKey →
   non-null`, else nullable (TableReferenceChecker already implements
   it for the relation paradigm; engine receipt
   RelationalCompilerExtension.java:940 `lowerBound = nullable ? 0 : 1`,
   unknown defaults SAFE). The class-mapped lane's base frames adopt
   THIS rule — the property echo dies as an authority. The 520 census
   becomes a pure model-debt register (property promises [1], label
   honestly says nullable): the dialect-split warning's firing list.
2. **Join provenance** — a pad-side frame (LEFT/RIGHT/FULL/ASOF) flips
   its columns nullable AT THE JOIN CONSTRUCTOR regardless of DDL
   (engine parity: the values really are NULL-padded; milestoning
   ON-clause channel untouched).
3. **Literals** — non-null; `NullLit` is Bottom.
4. **Composition rules (the (c) arm, in ctors):** default scalar Call =
   any-operand-nullable (SQL propagation); exceptions table probed on
   the 1.5.0 reference jar (the date_trunc version-skew lesson —
   EMISSION not builtin): `IS_NULL/IS_NOT_NULL/EXISTS/COUNT` never;
   `COALESCE` = all-operands-nullable; `CASE` = any-branch-nullable ∨
   Bottom-branch ∨ missing-else; `Cast` transports. **Reducers**
   (SUM/MIN/MAX/AVG…) are nullable AT THE NODE (empty input → NULL) and
   REFINED at the `SqlSelect` compact ctor: a reducer projection under
   a non-empty-group proof (GROUP BY present — SQL groups are non-empty
   by construction) drops the empty-group nullability, keeping
   operand-derived nullability. The ctor-refines idiom is the landed
   SqlSelect/SqlUnion reconciliation precedent (§4bZ-V D).

### The flip and the consumers

The first consumer is the converse tripwire INVERTED: labels read the
fact (`Lowerer.outputsOf` + the value frame switch authority;
reconcileLabels adopts fact nullability alongside kind), and the
925/49 breach ceilings become **EQUALITY-0 pins** — a wire NULL under a
non-null label is then a compiler bug, always loud. Decode/verdict
lanes unchanged (decode is label-KIND-driven; the engine carries no
nullability on its results — zero wire-behavior change, this is label
honesty only). Pure-level multiplicity is untouched: the ExprType [1]
stays the PURE contract; the SQL layer stops echoing it as physics.

### Milestones (each its own gated chain, M1 discipline)

- **M-N1 the dimension**: Typed gains `nullable`; ctor compositions +
  doors stamp (Column.of from OutputCol.nullable; audit the
  3-arg Column.of callers for the authority in hand); ZERO consumers;
  a differential instrument prints fact-nullable vs label-nullable
  disagreement (the flip payload, census-first). All existing censuses
  byte-stable (facts only). TRAP WATCH: a new Typed component changes
  fact equality — G9/Comparators structural pattern-matching is the
  recorded stamp-equality trip (min/max revert lesson); run the full
  chain, watch G9 first.
- **M-N2 frame authority**: base frames from DDL + join-pad provenance;
  SqlSelect reducer refinement; label side still property-echo (no
  flip); differential shrinks to the genuine-inference residue.
- **M-N3 THE FLIP**: labels adopt facts; breach censuses → EQUALITY-0
  both lanes; 520 census re-roled (model-debt register only); the
  property-echo authority DELETED same slice (no adapter hedge).
- **M-N4 tails**: pct 49 empty-group family closes via the reducer
  rule (this also fixes triage D27's aggregate-lane identity gap);
  bottomMult backlog re-adjudicated; D94's struct-field note updated;
  E2E seam table refreshed.

**Acceptance:** breach EQUALITY-0 both lanes + the property-echo
authority deleted + zero exec-text movement (nullability renders
nothing) + full chain green per milestone.

### M-N1 LANDING RECORD (2026-08-26)

**Landed: the dimension, zero consumers.** `TypeFact.Typed` is
`Typed(type, nullable, tolerated)`; the old two-arg ctor is DELETED so
every construction site was visited. Semantics pinned on the record:
`nullable=false` is a PROOF CLAIM (at the M-N3 flip a wire NULL under
it is a compiler bug, loud); uncertainty defaults TRUE (the engine's
DDL doctrine). Bottom IS null; Raises vacuously non-null; ride-alongs
(Lambda, FormatLit) and definite composites (ArrayLit/StructLit)
contribute nothing as operands (`SqlTyping.mayBeNull`).

**The rule funnel:** `callType = callNullability(callKind(...))` —
default scalar composition any-operand-nullable; the default arm never
LOWERS a rule-computed nullability. PROBED EMISSION exceptions (1.5.0
reference jar battery, 2026-08-26; spellings from OUR renderers — the
date_trunc/CEILING lesson):

- `concat()/concat_ws` SKIP NULL args (`concat(NULL,NULL)` -> `''`) —
  CONCAT/CONCAT_JOIN never null (AnsiSqlRenderer's engine-parity
  comment was already the receipt);
- greatest/least IGNORE NULL members — the all-operands rule, with
  COALESCE;
- `x // 0` and `mod(x, 0)` -> NULL, not an error — INT_DIVIDE/REM/MOD
  nullable always (DIVIDE emits `(1.0*a)/b`; `/0` -> Infinity, stays
  any-operand);
- `list_extract` OOB / `list_position` missing -> NULL — always;
- every list reduction over the EMPTY list -> NULL except count -> 0;
  a NULL collection -> NULL even for count;
- `list_concat`/`map_concat` treat NULL as the empty collection,
  `list_append(NULL, x)` -> `[x]` — never null;
- `hash(NULL)` yields a value (our signed reinterpretation preserves
  it); `typeof(NULL)` -> `'NULL'` — never null;
- json_extract missing path -> NULL — VARIANT_GET always;
- math domain edges (sqrt(-1), ln(0), asin(2)) RAISE, they don't NULL;
  string edges return EMPTIES not NULLs — any-operand stands for both.

**Reducers nullable AT THE NODE except COUNT** (probed: sum/min/avg/
median/string_agg/bool_and/list over zero rows -> NULL, count -> 0;
windowed twins identical; lag/lead frame edges NULL -> ValueFn wraps).
FOR M-N2: the GROUP-BY refinement must KEEP the moment family's
nullability — stddev_samp needs n>=2 (NULL on one row, probed);
var_pop(1 row) -> 0.0.

**Node rules:** CASE = any-branch ∨ Bottom-branch ∨ missing-else
(conditions never contribute); Cast TRANSPORTS; ScalarSubquery always
(zero rows -> NULL; one-row proofs = queued refinement); CheckedOne
exactly-one always (the 0/NULL flow contract); FoldCall = source ∨
init ∨ body (`list_reduce(NULL)` -> NULL probed; foldType now takes
the source); StructGet always (Struct.Field carries no nullability
dimension — absent-optional IS a NULL field; per-field authority
queued); Membership = needle ∨ collection (its probed truth table);
OrderedListAgg always; JsonArrayAgg stays non-null (its own coalesce
contract); Exists/rankings/literals/JsonObject/DeferredTdsString
non-null.

**Doors (every one visited, no defaults):** `Column.of(OutputCol)`
transports the frame's nullable (M-N1 leaf = the echo; M-N2 swaps the
authority behind the same door). The bare-name door is now
`of(table, name, SqlType, boolean)` — the caller states nullability
with the same authority as the type. Audit: Render's line/row re-reads
= false (the row frames DECLARE false beside them; concat lines never
null); Render's hoist-key re-read transports `src.nullable()`;
LEFT_LATERAL `elem` reads (Lowerer, InstanceProjection) = true (pad
slots); Fold's Using.type fallback = true (aggregate re-read, fact
not in hand); Comparators' `_cx.x` = true. ELEMENT DOCTRINE: presence
is NOT provable from Array(T) — no element-nullability dimension, and
carriers physically hold SQL NULLs until compaction (CompactList's
reason to exist) — `Column.param`, LambdaBinding.elementOf/
structFieldRead/mapElemResolver all stamp may-be-null.

**The differential instrument (the M-N3 flip payload):**
`SqlTypeCensus.nulDifferential` rides the existing walk's Typed arm —
SEPARATE NUL_* counters/class/sample maps, so every pre-§E3 census
print stayed byte-stable (verified against the standing pins on the
first sweep). Buckets: agree-required / agree-nullable /
UNDER-DECLARED (fact may-null, label promises present — the adoption
set) / OVER-DECLARED (label nullable, fact proves never-null — the
flip TIGHTENS these; adjudicate against the breach ledger before
M-N3). Bottom facts stay owned by bottom-ok/bottom-mult. Console:
`[rcorpus] nullable-diff:` + class lines (the target/ dump dies at
gate 8's clean — TimingLedger lesson); full report also written to
`target/nullable-differential.txt` for hand-run diffing. No pin this
slice (measure-first, the converse-tripwire precedent).

**FIRST SWEEP NUMBERS (G4 corpus, 24,529 plans):**
`agree-required=29,689 agree-nullable=7,373 under-declared=14,420
over-declared=1,177`. The 925 wire breaches are the wire-visible tip
of the 14,420; the 1,177 over-declared rows are M-N2/M-N3 homework
(each is a label the flip would TIGHTEN — the echo says nullable
where the fact proves presence; most should dissolve when M-N2 makes
leaves DDL-true).

TOP CLASSES (hand-run G4 decomposition, preserved witnesses): under-
declared = 6,063 Column VARCHAR + 3,214 Column BIGINT (the E2E
audit's re-read family — the fact TRANSPORTS the child slot's
nullable while the outer label re-echoes the [1]; the transport is
doing its job) + 929 UNNEST VARCHAR (CompactList carrier reads) +
732 Reducer DOUBLE + 675/339 ScalarSubquery (empty-group aggregates
and zero-row envelopes — the breach census's own families); over-
declared = 611 Column BIGINT + 390 Column VARCHAR (echo says
nullable, transported fact proves present — the DDL-truth swap at
M-N2 adjudicates these).

**Byte-stability note:** the D1/converse watch witness printed the raw
record toString (`fact=Typed[...]`) — pinned to the pre-§E3 spelling
via `factWitness` (type + tolerance); M-N3 re-roles those witnesses
and retires the formatter.

**Trips (1):** HarnessDisciplineTest's sort-site pin — the
differential report's largest-first ordering is a second display-only
sort in SqlTypeCensus; pin 1 -> 2 with the reason. G9/Comparators did
NOT trip (min/max stay outside the stamp roster; expr-keyed maps are
IdentityHashMaps; recognizer equality untouched). Test updates: 2
element-stamp expectations (DedupBindingTest, SqlTypingTest
checked-extract) now honestly may-null; three new §E3 test blocks pin
the composition default, every probed exception, and reducer node
nullability.
