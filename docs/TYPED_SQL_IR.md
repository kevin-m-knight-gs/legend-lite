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

- **Compositions COMPUTE, memoized**: `Cast` → its target; `Call` →
  the function's typing rule over children's `type()` (the judge's
  switch MOVES here — redistributed, not rewritten); `Case` → the
  branch family's shared type, `NullLit`/`error()` branches admissible
  (bottom); `Group`/`CompactList` → inner. Computed once per node.
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

## 6. Relation to standing programs

Debt-to-zero: this IS the lowering-layer "sane story" entry (sibling
of the JDBC story). G4 latency drill: run AFTER M1's differential
probe exists (it measures judge cost directly). The corpus SQL-verdict
migration and all bucket work queue behind M4.
