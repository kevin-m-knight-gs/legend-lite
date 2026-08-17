# JAVA EVICTION — the tenet's completion program

**Charter (2026-08-17, user-directed):** the foundations pause made the referee honest;
this program makes tenet #1 ("Java orchestrates, the DATABASE executes") **TRUE AND
MECHANICAL**. The deep audit's finding stands: the tenet never had a ratchet, so it was
negotiable under pressure — F4.4 reverted, HostEval reframed, A13/JsonSourceFrame filed.
This program builds the ratchet FIRST, then evicts in leverage order.

## 0. Rules (inherited from the foundations program — they worked)

- One leg per commit batch; full `tools/allgates.sh` chain per batch; tree frozen mid-chain.
- The corpus sweep is the REFEREE. Zero undeclared family deltas; declared deltas get
  verdicts in `docs/BURNDOWN_EXPLANATIONS.md` in the same commit.
- Revert, don't patch forward. Probe before building. Measure before claiming.
- The ledger (`JavaEvalLedgerTest`) is SHRINK-ONLY from the day it lands: a new
  Java-evaluation site fails the build; an evicted one forces the ledger row to shrink.

## 1. The boundary — what counts as "Java in the exec path"

**EVICT (must reach zero):** any Java code that COMPUTES a value or COMPOSES text that a
test assertion (or product consumer) observes as the result of executing a Pure
expression — evaluation, rendering, row shaping, realization of source data.

**PERMANENT-ALLOWED (registered, each with a written justification):**
- **Egress decode** (`Executor.fetch/unwrap/latticeKind/decodeAny`): decoding a carrier the
  DATABASE produced, by declared type/carrier contract. No computation.
- **`LiteralFold`**: bare String/Boolean literal unwrap — the engine's own
  ConstantExecutionNode, differential-pinned (`ConstantPlanParityTest`).
- **Comparison layer** (wireEquals/hostEquals/TdsEquivalence/renderedTextEquals):
  VERIFICATION is the harness's job; it consumes two sides, never produces a result.
- **`JsonAssertCanon.sortByKey`**: re-creates the TEST'S OWN canonicalization idiom over a
  metamodel that never executes through SQL (revisit only if the JSON metamodel
  re-platforms).

## 2. Phase E0 — the ratchet (BUILT WITH THIS CHARTER)

`core/src/test/java/com/legend/JavaEvalLedgerTest.java`: every EVICT surface is a row
(file → explicit evaluator-method regex → pinned count, exact-match, shrink-only), and the
PERMANENT-ALLOWED register is spelled in the same file so the boundary is one artifact.
Definition of done for the program: **every EVICT row reaches zero and is deleted.**

Measured EVICT surface at charter time (~5.4k lines of Java evaluation):

| Row | Surface | Size |
|---|---|---|
| E4 | `exec/HostEval.java` — the interpreter | 928 lines |
| E4 | `exec/MetamodelWalk.java` — walk handles/nav | 1,603 lines |
| E4 | `MetamodelSteps.java` — walk vocabulary | 234 lines |
| E4 | `StatementExecutor` walk family (planWalk/walkProp/walkFilter/planModel/…) | ~25 methods |
| E4 | `plan/PlanText.java` + `AggAwareActivities.java` — metamodel TEXT composed in Java | 888 + 265 lines |
| E4 | `exec/Ddl.java` engine-TEXT generators (`*StatementText`, `setUpDataSqlsText*`) | 5 methods |
| E4 | `exec/DbMetaData.java` — metadata from a shadow replay | 161 lines |
| E2 | `Executor.shapeRow` many-valued row explosion (A13) | 1 branch |
| E3 | `resolver/JsonSourceFrame.java` — JSON parse → VALUES realization | 2 methods |
| E1 | PCT `ExecuteLegendLiteQuery.formatAsTds/formatValue/formatDate` — wire text in Java | ~500 lines |

## 3. The legs, in order

### E1 — PCT renderer → Lowerer ROOT MODE (F4.4 done right) · effort L

The recorded design from the reverted attempt: post-hoc plan rewriting is impossible
(~10 shape classes recorded in FOUNDATIONS_PLAN F4.4); the Lowerer grows a ROOT MODE
(`withStreamingGraphRoot` precedent) in which the PCT wire print (fixed-3-millis+0000
DateTimes, TDS text, cell forms — all measured and recorded) is EMITTED BY THE PLAN.
Blocked findings to honor: abstract-Date slots need `typeof()` reflection (OutputCol slot
claims measured unreliable); minimal-subsec forms demote deephaven columns to STRING.
**Acceptance:** `formatAsTds`/`formatValue` DELETED; PCT 1109/1109; PCT is
orchestration-only (its ledger rows reach zero).

### E2 — TDS-to-many slot + A13 row explosion → SQL · effort M

One design, both halves: a to-many project lambda emits the engine's
union-subselect/LEFT-join row explosion IN SQL (the engine rule is already documented at
the `shapeRow` branch), and OutputCol reconciles with the emitted slot.
**Acceptance:** the `shapeRow` explosion branch DELETED; `testConcatenateWithFilter`
flips green (+1, the one CSV-render residue); corpus zero-delta otherwise.

### E3 — JsonSourceFrame → `SqlSource.SourceUrl` · effort M

The DB path exists end-to-end (`unnest(CAST(… AS JSON[]))`); the missing piece is the
`FRAME_ORDINAL` row-identity channel in the sourceUrl spelling, then per-column typed
variant extraction (`variant::navigation::get` + `variant::convert::to`, both registered).
**Acceptance:** `classSource` builds a projection over SourceUrl, no Java JSON parse of
payloads; the F7.3 walls (null-string collision, structured-under-scalar) DISSOLVE
(the carrier limitation dies with the carrier); XStore tests green.

### E4 — HostEval re-platform · effort XL (its own phased arc)

The mountain: ~4.1k lines across the interpreter, walk handles, metamodel text, and the
metadata shadow. Phased by the F0.3 census families, one family per batch, each with an
instrument-first firing count:

- **E4.a store navigation** (`schema`/`table`/`view` handles) → the platform's OWN
  `information_schema` surface (the H2-backend vision doc already names it).
- **E4.b metadata natives** (`fetchDb*`, `DbMetaData`) → same information_schema surface;
  the shadow-H2 replay dies.
- **E4.c metamodel instances & walks** (constructNode/constructOp, plan walks) → struct
  values / grid→relation (the STRUCT design is landed platform vocabulary).
- **E4.d metamodel TEXT** (`PlanText`, `Ddl` *Text generators, `AggAwareActivities`) →
  either relation-valued reads the DB composes, or registered PERMANENT rows with
  engine-parity justification (the engine renders plan text host-side too — this
  sub-family may legitimately end as ALLOWED; the census decides, not the prose).
- **E4.e the interpreter core** (`eval`/`property`/fold/map/filter arms) — shrinks as
  a–d remove its demand; whatever remains at the end is either deleted dead or moved
  behind the harness-installed seam (the F0.3 consequence note).

**Acceptance per sub-leg:** the family's ledger rows shrink; corpus/PCT referee zero-delta
or §0.4-declared.

## 4. Definition of done

1. Every EVICT ledger row at zero (row deleted from the test).
2. The PERMANENT-ALLOWED register is the complete residue, each row justified.
3. Corpus + PCT referees green through every leg; declared deltas verdicted.
4. Tenet #1 re-worded in AGENTS.md/TENET_CHARTER from aspiration to INVARIANT, citing the
   ledger as its enforcement.

Dependency order: **E0 → E1 → E2 → E3 → E4.a → E4.b → E4.c → E4.d → E4.e.**
E1 first because it retires a whole harness (PCT) to orchestration-only and builds the
root-mode machinery E2 reuses; E4 last because a–d's vocabulary (information_schema,
struct values, root-mode rendering) is exactly what its arms need.
