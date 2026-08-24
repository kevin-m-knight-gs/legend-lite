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

## 8. Relation to standing programs

Debt-to-zero: this IS the lowering-layer "sane story" entry (sibling
of the JDBC story). G4 latency drill: run AFTER M1's differential
probe exists (it measures judge cost directly). The corpus SQL-verdict
migration and all bucket work queue behind M4.
