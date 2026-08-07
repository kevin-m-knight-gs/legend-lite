> ## ⚠ SUPERSEDED — 2026-08-06
>
> This document describes work that was **not built, was abandoned, or has been
> superseded**. It targets `engine/com.gs.legend` (frozen since 2026-07-18) or a
> plan that never landed. It is kept as a record of what was considered.
>
> **Do not act on it.** For the live architecture see `AGENTS.md` and
> `core/README.md`; for current work see `docs/GATES.md` and
> `docs/CORPUS_BURNDOWN_HANDOFF.md`.

# Burn-down roadmap — remaining 442 fails (priced 2026-07-29, corpus 2096/2538)

Method: three parallel corpus-body readings (feature-track sampling, mapping/M3
slice sizing, interpreter sizing) over the try-run census + scoreboard. Effort:
S = hours, M = ~a day, L = multi-day, XL = week+.

## Headline corrections to prior assumptions

1. **"Reflection feeds the feature tracks (140+)" is FALSE.** Sampled bodies
   show lineage/test-data-gen/fromPure assertions are outputs of their own
   walkers/generators/compilers — reflection supplies operands only.
2. **The feature tracks are not greenfield here.** legend-lite already passes
   lineage 43/55, test-data-gen 60/68, fromPure 30/50 — the walkers and
   generators exist; the fails are SPECIFIC GAPS, not missing features.
3. **transform/fromPure is mislabeled as "grammar printer"** — it is
   pure→SQL generation (toSQLString), i.e. our core pipeline; its 20 fails
   are mostly per-dialect spelling gaps (engine-source-grounded work).
4. **The reflection slices are heterogeneous**: one shared prerequisite (the
   pure-object interpreter CORE: match/instanceOf/cast-to-subtype,
   ^Class(...) construction, ^$x(...) copy, structural instance equality)
   unlocks several slices cheaply; M3 AST reflection (deactivate) is XL and
   serves only ~3-14 tests — do it last or never.

## Ranked clusters

| # | Cluster | Tests | Effort | Depends on | Notes |
|---|---------|-------|--------|------------|-------|
| 1 | **Interpreter core** (HostEval: match/instanceOf/cast, ^construction, ^copy, structural equality) | ~15-19 direct | **M** | — | Unlocks #2 (6-10) + #3 debugPrint (9); shared prereq for most metamodel work. Highest tests-per-effort. |
| 2 | **Mapping-metamodel navigation** (classMappingById/allSuperSetImplementations/superMapping/resolvePrimaryKey/enumerationMappingByName/mainTable + SetImplementation surface) | 6-10 | **M** (after #1) | #1 | testPrimaryKeyForB's super-chain precedence table is the bulk; execute()-based siblings may follow free since our execute channel exists. |
| 3 | **debugPrint instance-graph tests** | 9 | **S-M** (after #1) | #1 | Only wrapH2Boolean path: 6 metamodel classes; assertEquals over instance TREES (needs structural equality). ~5 more test files reuse the machinery. |
| 4 | **relationalMapper** (DatabaseMapper/SchemaMapper/TableMapper) | ~10 | **M** | — | Do NOT run the real executionPlan pipeline (XL); typed-tree mapper extraction + render-time schema/table rename, like existing connection-field extraction. |
| 5 | **fromPure per-dialect gaps** (trim/pad/abs/splitPart/leftRight/DB2 spellings…) | 20 | **M-L** | — | Engine-source-grounded dialect function spellings (h2/db2 extension .pure files); NOT invented rules. Batchable. |
| 6 | **executionPlan family** (11 plan-golden diffs + multi-node/Allocation envelopes + m2m overloads) | up to 60 | **L** (multiple slices) | — | Mixed bag; probe-first per sub-bucket. Includes the 5 M2M-resolution walls (H5c-adjacent). |
| 7 | **Milestoning long tail** (#32: non-literal dates 7, two-dates-per-head 4, bitemp generated 8, semi-structured single) | ~22 | **M-L** | — | Known sub-buckets from earlier recon; calculus leg #81 partially open. |
| 8 | **Mapping-family fail tails** (enumeration 10, inheritance 9, embedded 8, extends 8, association 6, sqlFunction 7) | ~48 | **M each** | — | Family-specific; several enumeration fails are dialect/if-enum spellings; inheritance is subType dispatch (#71 open). |
| 9 | **Task #80 Leg-1 isolation strategies** (sql-only 12 + forced milestoning/filter 6) | ~18 | **L** | — | BuildCorrelatedSubQuery/MoveFilterInOnClause/MoveFilterOnTop chooser — design already mapped in RELATIONAL_FEATURE_MAP. |
| 10 | **Lineage specific gaps** (scanRelations union/join shapes 9, scanColumns 3) | 12 | **M-L** | — | Walkers exist; gaps are union+join relation-tree shapes. scanRelations-with-runtime rides our router (exists). |
| 11 | **Test-data-gen gaps** | 8 | **M** | — | Generator exists (60/68); fails: inheritance-multi-level, union-to-union seeds, alloy-server pair (2 likely unreachable — live server). |
| 12 | **H5c M2M cast-binding** (m2m2r 12 + graphFetch m2m milestoned) | ~12-19 | **L-XL** | — | Recursive-substitution design (memory sketch exists). Real design leg. |
| 13 | **Shared-key join desugar** (testTwoMappings pair + RIGHT_OUTER provenance) | 2-4 | **M** | — | Checker-level merge-by-join-name; high correctness value (kills the __jk_ wrapper class of diffs). |
| 14 | **Validation/constraints** (#45: complexValidation 2-10 + milestoned agg) | ~11-23 | **L** | possibly #1 | Not yet sampled — price before starting. |
| 15 | **M3 AST reflection** (deactivate/FunctionExpression/InstanceValue surface) | 3 (+11 plan walls maybe) | **XL** | #1 | The 3 pureToSqlQuery tests are trivial AFTER the foundation; foundation is a week+. LAST. |
| 16 | **FAIL-diff long tail** (~88 real diffs across families) | ~88 | opportunistic | — | Engine-source-grounded rules ONLY (reAliasQuery precedent); never invented per-test spellings. |

## Recommended order (next ~5 legs)

1. **Interpreter core + its two dependents** (#1 → #2 → #3): one arc,
   ~25-35 tests for ~2-3 days, and the core compounds (same primitives the
   metamodel work keeps hitting).
2. **relationalMapper** (#4): M, ~10 tests, self-contained.
3. **fromPure dialect gaps** (#5): 20 tests, engine-grounded batch work.
4. **Milestoning tail** (#7) or **mapping-family tails** (#8) by probe results.
5. Interleave **#13 shared-key desugar** (correctness) and opportunistic #16.

Defer: #15 (M3 AST) until something bigger needs it; #12 (H5c) as its own
priced design leg; #14 price first.