# §4AD SLICE 1 — NAVIGATION-CONSUMPTION ROUTING DESIGN (2026-08-29)

Status: **DESIGN, awaiting user sign-off before implementation.**
Parent charter: V7_ASSERT_VERDICT_CHARTER.md §4AD (ratified row-algebra
rule + decisions 1-3). Census: NAV_ARM_CENSUS_4AD.md (blast radius:
1,017 tests / 4 named correlated-or-dedup arms; witness dump
nav-arm-census-4AD.txt). This document is self-sufficient for a fresh
session: it carries the context, the measured failure evidence, the
ratified design, the deletion list, and the implementation plan.

---

## 0. SESSION CONTEXT (state at time of writing)

- **Committed & pushed, all gates green** through `a618c5d2`:
  - SQL-IR slice 2 complete (outputs-from-projections + finish +
    pair-native; `2dc682fd`, `e9599fe3`, `a8f804e2`).
  - §4AD census executed (`1146086e`) + measured witnesses recorded
    (`abe60f6e`).
  - §4AD slice-1 **batch 1** (`a618c5d2`): scalar filtered-nav reads
    in value position lift to the `#fN` fan-out join; infix-plus
    blanket arity gate; position scope (filter predicates untouched);
    **pred-count scope gate — acknowledged WEAK** (user: "why did you
    hack a fix"). Pins at that commit: exec-passing 1,387; M1 matched
    457 / rescued 880; h2 floor 1372 / walls 946; rescued-passes 816.
- **Uncommitted working tree (the "hack wreckage")**: several
  successive replacement gates (mapping-topology, mid-slot, per-head
  plus skips, a threaded `valueLifted` set — interrupted mid-edit by
  the user). One test red under every post-`a618c5d2` configuration:
  `meta::relational::tests::mapping::join::testChainedInnerJoinsWithQualifierInGroupBy`.
  Measured-but-unlanded gains from the topology round: exec-passing
  would be 1,390 (+3 over the landed 1,387), h2 floor 1374, walls 947
  (UNNEST 903→904, the justified pattern), rescued-passes 817,
  tests/advanced 64 (incl. `testQualifierWithIsolation`, a baseline
  ERROR that flips to PASS).
- **On implementation start: revert the working tree to `a618c5d2`**
  (`git checkout -- core/ docs/` for the touched files, or stash) and
  build fresh against THIS design. Do not try to salvage the gates —
  the design makes all of them unnecessary.
- Standing rules that bind (from the session handoff + memory):
  sweeps/probes need
  `-Dlegend.engine.root=/Users/neemsandv/legend/legend-engine
  -Dlegend.pure.root=/Users/neemsandv/legend/legend-pure`; the chain is
  `LEGEND_ENGINE_ROOT=… LEGEND_PURE_ROOT=… caffeinate -dims
  tools/allgates.sh` (~7.5 min, 12-min budget); zero repo writes while
  any run is going; push only on exact `ALLGATES_DONE — GREEN`, pins +
  charter in the same commit; **no dedup / filter-position work
  without a user-reviewed plan** (slice 2); scoped corpus probes:
  `-Drcorpus.only=<family-substring> -Drcorpus.test=<one-substring>`
  (single substring, not a comma list); `LEGEND_LITE_DUMP_SQL=1`
  dumps our SQL per statement.

## 1. THE PROBLEM

A navigation consumption such as

```
Firm.all()->map(f | $f.employeesByCityOrManagerAndLastName('Smith','Hoboken','Bla').firstName + 'Test')
```

can compile through three routes today:

1. **Correlated scalar subquery** per root row
   (`Substitution.filteredNavLeafRead`, ~line 2601) — §4AD bans it;
   it also miscomputes rows (CONCAT's null-skip mints phantom `'Test'`
   rows for non-matching firms: ours 4 rows, engine 1 —
   witness `testQualifierWithOperation`, measured 2026-08-29).
2. **Grouped sub-aggregation** — the demand scan
   (`CorrelatedSubselects.aggScan`, entry ~line 2053) classifies the
   surrounding expression as an aggregate because pure registers
   `plus` as a reducer (`lowering/Aggregates` REDUCERS,
   `family(SUM, "plus")`), and emits a grouped-subselect join.
3. **Join fan-out** — the engine's row algebra (charter §4AD): LEFT
   JOIN the (filtered) navigation, conditions in join/WHERE, one
   result row per surviving joined row.

**The disease is that no single place chooses.** The route *emerges*
from three independent re-classifiers that share no fact:

- `resolver/SyntheticHeads.liftFilteredHeads` — a pre-pass tree
  rewrite that lifts filtered navigations to `#fN` synthetic join
  identities (predicate parked on the join target). Multiple arms:
  the `[*]` bare-collection lift (pre-existing), the map-normalization
  arm (`->map(e|$e.leaf)` → path spelling), the computed-mapper arm
  (#69), and (batch 1) the scalar `[0..1]` arm.
- `resolver/CorrelatedSubselects.aggScan` — the demand scan, which
  RE-classifies the consumption (aggregate vs bare) by pattern
  matching the (post-lift) tree, including treating n-ary `plus` as a
  reduction.
- `Substitution.filteredNavLeafRead` — the substitution-time fallback
  to the correlated form for scalar reads the lift did not rewrite.

Every post-batch-1 attempt to make one of the three §4AD-correct while
coordinating the others with predicates stranded some shape — denied
the new route by one decider, diverted off its old route by another,
landing on a loud wall.

## 2. THE MEASURED HACK CENSUS (evidence; all from 2026-08-29 sweeps)

| # | attempt | decision point patched | discriminator | what broke it (witness) |
|---|---|---|---|---|
| 1 | pred-count prescan (`scalarLiftHeads`) | scalar-lift permission | distinct preds per head | proxy for topology; excluded the winnable `testQualifierWithIsolation`; user-flagged as a hack. LANDED at `a618c5d2`, to be deleted |
| 2 | nav-step membership (`navSteps().containsKey`) | scalar-lift permission | "head is a navigate slot" | rejected EVERYTHING — navigate slots are the normal representation of class-typed properties in these mappings, not the disease |
| 3 | mid-slot references (nav predicate reads other slot aliases) | scalar-lift permission | head-level chain detection | right for the lift itself (+3 verified rows vs #1), but chain-ness can live at the LEAF (`extraInformation` reads a join slot), invisible at the head |
| 4 | blanket plus arity gate (`argc > 1` never a demand reducer) | demand-scan classification | arity only | closed the aggregation route for shapes the lift denied — stranded `testChainedInnerJoinsWithQualifierInGroupBy` (its every other route walls) |
| 5 | per-head plus skip (`isFiltered(head)`) | demand-scan classification | head is `#fN` | diverted `[*]`-map-normalized aggregation shapes (same witness) into the fnlr wall — `#fN`-ness ≠ "scalar-fanned" |
| 6 | #5 + `singleJoinNavHead` | demand-scan classification | head-level join shape | same wall — the chain is leaf-level; head-level topology cannot see it |
| 7 | `valueLifted` walk-recorded set threaded into aggScan (interrupted) | a coordination side-channel | shared mutable set | the confession: three deciders needing a side-channel = the ownership is wrong. Never finished |

Uniform failure taxonomy: **consumption-side re-classification of a
decision the pre-pass could own**, plus **route totality being
nobody's job** (denying a route never restored the alternative).
This is the same disease the SQL-IR outputs-from-projections slice
deleted at the SQL layer (three positional/reconciliation mechanisms
vs one construction-time fact).

Key routing facts established by bisection (worth not re-deriving):

- `testChainedInnerJoinsWithQualifierInGroupBy` PASSES at pre-slice
  baseline via the AGGREGATION route: its qualifier consumption is
  lifted by the pre-existing `[*]`/map-normalization arms (head is
  `#fN` **without** the scalar gate being involved), and its `plus`
  is claimed by aggScan. Its leaf (`extraInformation`) reads a join
  slot, so the correlated arm WALLS for it
  ("slot-demanding leaves under value-position filters") — it MUST
  keep an aggregation or fan-out route.
- `testQualifierWithOperation`'s SUM garbage (`SUM(FIRSTNAME,'Test')`)
  arises in aggScan's size-2 leaf arm: it claims n-ary `plus` over a
  post-lift plain chain and emits the reducer with the extra scalar
  args appended — an emission that is invalid BY CONSTRUCTION for
  n-ary plus (SUM is 1-arg). Pre-lift this arm never saw such shapes
  (pathOf over `pa(filter(...))` is null), which is why the bug was
  latent until the scalar lift created plain chains.
- The engine compiles the value-position witness as
  `SELECT concat(p.FIRSTNAME,'Test') FROM firmTable root LEFT JOIN
  personTable p ON root.ID=p.FIRMID LEFT JOIN … WHERE p.LASTNAME='Smith'
  AND (…)` — flat fan-out, qualifier predicate in the top WHERE,
  non-matching roots vanish because the WHERE fails on NULL (this is
  charter §4AD's "empty propagation falls out of the shape").
- The engine compiles explicit reduction
  (`$f.employees.age->sum()`-style, the subAggregation golden) as
  `LEFT JOIN (SELECT key, SUM(x) … GROUP BY key)` — STILL a join.

## 3. THE DESIGN (user-ratified direction, this session)

**There is no route choice.** Both legal forms are row algebra, and
the choice between them is decided by **expression syntax alone**:

> **The lift pre-pass rewrites every navigation consumption into
> exactly one of two canonical forms:**
>
> - **FANNED JOIN READ** — the consumption is NOT an explicit
>   reducer call: the read becomes a plain chain over the (possibly
>   filtered `#fN`) head; the surrounding computation is row-wise by
>   construction (`concat` per fanned row). One result row per
>   surviving joined row. The filter predicate lands where the engine
>   puts it (top WHERE for the value position — which also delivers
>   empty propagation with no synthesized IS-NOT-NULL).
> - **GROUPED JOIN READ** — the consumption IS an explicit reducer
>   call (`->sum()`, `->max()`, `->count()`, `->joinStrings()`, …,
>   including pure's 1-arg collection `->plus()` which IS sum): the
>   read becomes a grouped-subselect join by key (the engine's
>   subAggregation shape). One result row per root.
>
> N-ary infix `plus` (`a + b`) is a SCALAR operator — always the
> fanned form. 1-arg collection `plus` is a reducer — always the
> grouped form. Syntax-directed, no head/topology/mapping lookup.
>
> **Downstream passes read structure; they classify nothing.** The
> demand scan's plus-reinterpretation and the correlated fallback for
> these shapes are DELETED, not gated.

**There is no architectural residue.** The former "residue classes"
are implementation debt with named bugs, each failing LOUD until
fixed (burn-down list, not routing branches):

- **Milestoned (dated) heads** — the lift materialization emits an
  unbound alias (`t1.*` inside a frame renamed `"root"`); witness
  `testTemporalDateVariableInFunctionExpressionWithPropagation`
  (fails identically at baseline — pre-existing). Fix the dated-head
  materialization; until then the shape must WALL loudly, not
  silently take a deleted correlated arm.
- **Chained (mid-hop) heads** — the engine bundles mid-table + target
  INSIDE each filtered occurrence's join subselect (see
  `testProjectMerge`'s golden: two self-contained
  `(tree ⋈ org WHERE type=X)` subselects keyed on `node`); our
  materializer builds the mid join once, shared, which cross-fans
  (measured 3→10 rows). Build per-occurrence mid-hop bundling.
- **Filter position** — slice 2 (dedup removal), deliberately
  sequenced behind a user-reviewed plan. Until then filter-position
  consumptions keep today's behavior UNTOUCHED (not routed through
  anything new).

End state: zero correlated scalar subqueries for navigations (the
census's `correlated-count-reducer` 234 and `correlated-agg-reducer` 2
arms retire with slice legs too), `filteredNavLeafRead` deleted, the
aggScan plus arms deleted.

## 4. WHAT GETS DELETED (the acceptance test, per repo doctrine)

1. `Substitution.filteredNavLeafRead` — value-position matching
   (the correlated arm for these shapes). The slot-demanding-leaf
   wall inside it disappears WITH it (the fanned/grouped forms read
   materialized slots through the join material).
2. `CorrelatedSubselects.aggScan`'s implicit-plus classification —
   the size-2 leaf arm's ability to claim n-ary `plus` (and any other
   n-ary scalar op registered as a reducer family). Explicit reducer
   FQNs remain the ONLY aggregation triggers.
3. Batch 1's pred-count gate (`scalarLiftHeads` prescan) and the
   blanket arity gate — subsumed by the syntax-directed rewrite.
4. Every coordination predicate from the wreckage (nothing from
   hacks #2-#7 survives).

## 5. MEASUREMENT BEFORE IMPLEMENTATION (no more theory-first)

One instrumented sweep (census idiom — `NavArmCensus.fire`-style,
per-test attribution) answering, for every witness in
`nav-arm-census-4AD.txt` and specifically the five named tests below:
**which aggScan arm or substitution arm claims each navigation
consumption today** (size-2 leaf / computed-mapper / chain-agg /
sortBy / bare / fnlr). The rewrite rules in §3 are then checked
against that table, not against guesses — hacks #5/#6 both died from
guessing arm attribution.

ADDENDUM (self-audit, 2026-08-29): the census must ALSO (a) count
`filteredNavLeafRead` firings per test — the correlated family round
1 missed; its blast radius bounds what the batch-5 deletion must
cover — and (b) locate an engine witness with TWO differently-
filtered VALUE-position reads in one query: the top-WHERE placement
rule is measured from a single-occurrence witness, and a shared WHERE
would make the two predicates interact (a row dying when EITHER
qualifier misses may not be engine semantics). Placement for the
multi-occurrence case is decided by that witness's golden, not
asserted.

Named witnesses (all shapes must be green post-implementation):
- `advanced::structure::testQualifierWithOperation` (fanned, map `+`)
- `advanced::forced::structure::testQualifierQueryWithOr`
  (filter position — must stay UNTOUCHED until slice 2)
- `mapping::join::testChainedInnerJoinsWithQualifierInGroupBy`
  (grouped route today; leaf reads a join slot — the route-totality
  canary)
- `mapping::tree::testProjectMerge` (chained mid-hop, two distinct
  preds — the cross-fan canary)
- `projection::qualifier::testQualifierWithVariableArg` (byte-match
  upgrade — must not regress)
- plus the temporal witness above (loud wall until its bug is fixed).

## 6. IMPLEMENTATION PLAN (gated batches, each: witness probe →
## full sweep → allgates → push)

**ORDERING PRINCIPLE (self-audit fix, 2026-08-29): capability FIRST,
routing flip SECOND.** An earlier draft deleted the correlated arm in
the router batch with chained/milestoned shapes "walling until
fixed" — that would convert an UNMEASURED number of currently-green
tests (the fnlr-riding shapes the census never counted) into walls,
contradicting the zero-regressions criterion below. Walls-not-
fallbacks is right for NEW capability; applied to working shapes it
is regression with good posture. So the materializer bugs burn
before the router flips, and the deletion lands only when it is
total AND safe.

1. **Revert working tree to `a618c5d2`.**
2. **Batch 2 — arm-attribution census** (design §5, incl. fnlr
   firings per test — the family round 1 missed) + the standalone
   size-2 emission bug fix if separable (n-ary plus can never emit a
   valid AggDemand). Measurement batch; behavior change only if the
   emission fix is provably inert on the sweep.
3. **Batch 3 — capability: per-occurrence mid-hop bundling** (the
   engine's own shape — mid ⋈ target inside each filtered
   occurrence's subselect). Pure materializer capability; existing
   routing untouched; zero behavior change expected on green tests.
4. **Batch 4 — capability: dated-head materialization alias fix**
   (the unbound `t1.*`-vs-"root" bug; its witness fails at baseline,
   so this one can only improve).
5. **Batch 5 — THE ROUTER FLIP**: `liftFilteredHeads` classifies
   each filtered-nav consumption by syntax and rewrites to the
   canonical form; batch-1's pred-count + blanket-arity gates,
   aggScan's implicit-plus arms, and `filteredNavLeafRead`'s
   value-position matching are DELETED in this one batch (the lift
   permission and the plus rule flip together — the #4 lesson).
   Pred placement for MULTI-OCCURRENCE value position follows the
   engine witness batch 2 must have located (see §5 addendum) — a
   single shared top WHERE is NOT assumed. Pins from the topology
   round (exec-passing ≥1,390, h2 ≥1,374, walls 947, rescued 817,
   advanced ≥64) are floors to re-measure, not targets.
6. **Batch 6 — retire the RelationPredicates correlated reducers**
   (`correlated-count-reducer` 234 + `correlated-agg-reducer` 2 from
   the census): count/size/sum-style consumptions of navigation
   relations move onto the GROUPED JOIN form. Named here so it
   cannot linger as an unscheduled clause — it is the same
   syntax-directed rule applied to the relation-argument spellings.
7. **Batch 7 — FILTER POSITION (charter slice 2, THE DEDUP LEG)** —
   the same syntax-directed rule extended to predicate consumptions;
   plan below (§8) so nothing in §4AD is left unplanned. Lands only
   after batches 2-6 are green (it builds on the same materializer
   capability and the same one-owner router).
8. **Batch 8 — ORACLE-LANE UNPARK (charter slice 3)**: the parked
   Collection/Scalar verify lane (H2Verify "non-tabular result
   frame", 54 declines at the last census) turns on — the
   string-plus empty-vs-'' semantic gap that parked it is RESOLVED
   by batches 5+7 (our rows become the engine's rows); the
   "row-cardinality skew (distinct rows agree)" decline reason
   RETIRES (its reason for existing is gone — charter's own words).
   Expected: unable-to-exec 97 → ~50 (charter), both known semantic
   divergences become verified agreements.

Acceptance per batch (charter §4AD): zero DuckDB-lane pass
regressions (zero means zero, not net-zero — check individual flips,
not just family counts), oracle conversions grow-only, pins + charter
in the same commit, ALLGATES green before push.

## 7. WHY THIS DESIGN AND NOT THE GATES (for the record)

The gates all tried to answer "which route does this HEAD take?" —
but the head was never the deciding entity; the CONSUMPTION is. The
engine's own compiler is syntax-directed the same way (reducer call →
grouped join; anything else → fanned join). Once stated that way,
route totality is structural: every consumption matches exactly one
of two syntactic categories, both compile to joins, and unbuildable
material walls loudly instead of falling back. One owner, one fact,
decided where the whole tree is visible, recorded in the tree itself.

## 8. THE DEDUP LEG IN FULL (charter slice 2 — the plan the user
## asked to see before any dedup work; review happens with THIS
## design, one sign-off for the whole program)

**What changes, observably (charter decision 2, USER-RATIFIED in the
charter with "flagged and accepted"):** a filter over a fanned-out
navigation keeps duplicates. Witness `testQualifierQueryWithOr`:
engine 7 rows, ours today 1. Our weak value-asserts often pass both
ways; the ORACLE (golden-SQL row compare) is the referee that sees
the difference — today it counts these as "row-cardinality skew
(distinct rows agree)" declines.

**The mechanism (same one-owner rule, predicate position):** a
navigation consumed inside a class-lane FILTER predicate compiles to
a READ OF THE JOINED ROW in the WHERE clause — the engine's own
shape (witness `testQualifierQueryWithOr` golden: two filtered
subselects LEFT JOINed, `WHERE "p0".FIRSTNAME = 'Peter' OR
"p3".FIRSTNAME = 'John'` — reads of joined columns, no EXISTS, no
DISTINCT, duplicates preserved). Concretely:

- The router (batch 5's same pre-pass) rewrites predicate-position
  filtered-nav reads exactly like value-position ones: plain chain
  reads over `#fN` heads. The demand scan's "FILTER position →
  implicit EXISTS per boolean leaf" rule (StoreResolver's
  position-aware demand, the comment at ~line 2775) is DELETED for
  these shapes — filter paths join projection paths in the ONE
  explosion channel.
- DELETED with it: `ExistsJoinForm.rewrite`'s DISTINCT-key
  row-count-preserving join (the 109-test `exists-join-form-dedup`
  census arm — its entire purpose is preserving root cardinality,
  which decision 2 removes), and the EXISTS-material registration for
  plain filter predicates (`registerExistsSubs` and the ExistsSub
  machinery SHRINK to the shapes that remain genuinely
  boolean-emptiness — `isEmpty`/`isNotEmpty`/`exists` calls, which
  ARE row-count-preserving semantics by definition and stay
  semi-joins in the engine too).
- The batch-1 slot-prefix collision (duplicate `employees_ID` when a
  synthetic head's material met a plain head's) is IN SCOPE here and
  must be fixed structurally (per-identity slot prefixes), not
  avoided — it was the measured wall that position-scoped batch 1.

**Blast radius, measured (NAV_ARM_CENSUS_4AD.md):** exists-material
946 tests / exists-join-form-dedup 109. NOT all of exists-material
changes behavior: the `isEmpty`-family stays semi-join. Batch 7's
FIRST step is splitting that census arm by consumption kind
(predicate-read vs emptiness-call) so the observable-change set is a
NAMED list before any rewrite — the same census-first discipline as
every other leg.

**Sequencing inside batch 7:** (a) census split → (b) predicate-read
rewrite + deletions, gated exactly like batch 5 → (c) oracle
reconciliation: tests whose OWN asserts encode the deduped
cardinality (if any exist beyond weak asserts — the census split
names them) are adjudicated against the charter's ratified decision
with the oracle as referee, each row named in the charter, never
silently re-pinned.

## 9. DEFERRAL LEDGER (kept honest)

After this document, NOTHING in §4AD is deferred-without-a-plan:
- Batches 2-8 cover value position, capability bugs, the router,
  the correlated reducers, filter position/dedup, and the oracle
  unpark — the complete charter §4AD program to 100%.
- The ONLY item on the user is ONE sign-off of this document.
- Anything discovered mid-implementation that would create a new
  deferral gets its own named batch in this ledger, or it does not
  land ("finish-the-last-percent" rule: burn disclosed residuals in
  the same arc; a documented residual is still an open seam).
