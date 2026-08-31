# FULL BURN-DOWN HANDOFF — 2026-08-30

The one goal, in the user's words: **sql-exec declines to zero, declined
to zero, mismatch to zero.** This doc is the complete state + work map
as of commit b3fd0901 (main, pushed, ALLGATES GREEN). Every number below
is from the last green G4 sweep (gates-neema.g4.out of that chain), and
every lane pin quoted here is asserted in
`core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java` —
the pins ARE the ground truth; re-derive from them, not from this prose,
if they ever disagree.

## The scoreboard (last green sweep)

```
[v7] dual-channel agree=3381 disagree=9
     sql-text: exec-passing=1495  text-only=44  UNABLE-TO-EXEC=50
     test-data-csv=0 (ZERO-FROZEN)   declined=323
corpus: 2356/2575 PASS (1419 clean + 937 carrying softness;
        sqldiff 24, advisory 26, 0-asserts 27, text-rescued 896)
h2 lane: advisory floor 1347, walls 983
2 pre-existing family ERRORs (inheritance multi-hop join;
        union prune) — owned by their families, not this program
```

Reproduce the decline breakdown from any green sweep log:
`grep -E "^\[v7\] declined " <g4 log>` — the runner prints every
(assert-form :: reason = count) row. The buckets below are that grep,
aggregated.

## What landed this session (all pushed, each on its own green chain)

- Enum/fact-placement/reach-back arc CLOSED (4e6da393, e70cf26c,
  0d2a4096, f615354c, ee972acd): sealed ClassBinding/RelationalSource,
  Undeclared eradicated, LegacyReachbackCensusTest guard.
- TDG lane 117→0 COMPLETE (charter docs/TDG_LANE_CHARTER.md; S1
  29bff101, S2 19f27072, S3 07374d08+789d9cf4, S4 26a53c14): carrier
  pattern (checker captures protocol → TypedCsvCensus/TypedTestDataGen →
  StatementExecutor folds via TestDataGenerationNatives → compiler-minted instance
  literals), harness compensation machinery deleted, csv bucket
  ZERO-FROZEN, zero disagreements throughout.
- TDG 49er replay LANDED (6799cd91 + scoreboard 9b6f82a9): 20 of 49
  unable rows verified by execution-equivalence
  (H2Verify.tdgSqlReplay — golden on H2 mirror / ours on DuckDB via
  duplicate()+USE, name-normalized multiset compare). unable 70→50,
  exec-passing 1475→1495, rescue ratchet 881→896.
- TDG charter §S5 written (b3fd0901) — the next TDG leg, design only,
  AWAITS EXECUTION (below).

## LANE 1 — mismatch (disagree) = 9, EXACT-pinned

Runner pin `assertEquals(9, …)` (~line 753): the 9 are a DESIGNED
referee split, receipts R1–R8 in the verdict-burndown record
(includes testConcatenateWithJoin phantom-class). **Closing this lane
below 9 is a USER RULING, not code** — the charter says any movement
is a semantic change needing adjudication. Do not "fix" these without
the user re-ruling; the pin exists to make movement loud.

## LANE 2 — declined = 323 (the big burn; buckets measured, sum exact)

| bucket | count | witness / cause |
|---|---|---|
| getAll unresolved | 76 | `wall: store resolution left getAll(<Class>) unresolved — the query shape around it is not supported by the resolver yet`. Spread: simple::Person 11+5, enumeration Employee 11, Order 8, Firm 5, m2m2r::Entitlement 5, Product 4, tdsJoin 3, Address 3, m2m shared 3+2+1, plans 2+2, milestoning 1, datePeriods 1, misc. |
| no-scalar-lowering (reflection cluster) | 72 | `no scalar lowering registered for resolved overload '<fqn>'`: classMappingById 21, toPostgresModel::newState 18, rootClassMappingByClass 13, relationalExtensions 8, metamodel::view 6, inferRelationalType 5, _classMappingByClass 1 |
| TypedMap H2-vocab wall | 65 | `wall: class query under TypedMap is not resolvable yet (H2 vocabulary)` — h2-lane vocabulary, NOT a resolver bug |
| pkOfFunc | 43 | `FunctionDefinition has no property 'expressionSequence'` (all in mapping::relation::pkOfFunc) — function-BODY reflection |
| host-unsupported | 26 | the register carries per-row causes |
| ONE-STAMP invariant | 17 | `MULTIPLICITY-STAMP INVARIANT VIOLATED` — a real stamp-program bug class, see docs/STAMP_* directive it names |
| bare-lambda non-let statement | 10 | `a non-let intermediate statement in a bare lambda literal is not supported` |
| routerExtensions unknown fn | 5 | metamodel::execute tests |
| tail | 9 | InstanceValue type 2, toPrettyJSONString 2, trailing-JSON 2, extend/project unresolvable-name 1, filter-pred unresolvable 1, SQLNull no-canonical-layout 1 |

### Recommended attack order (tests-per-design ranking, user doctrine)

1. **no-scalar reflection cluster (72)** — NEXT. These are
   compile-time questions about the AUTHORED artifact (mapping
   reflection: classMappingById, rootClassMappingByClass, view,
   mainTable…), not DB queries. First witness read:
   `testExtendsForMainTable.pure:79` —
   `B1Mapping->classMappingById('b1')->cast(@RootRelationalInstanceSetImplementation)->map(x|$x->mainTable())`.
   DESIGN FRAME (ratified this session, LegacyReachbackCensusTest
   record): reflection APIs spec'd on the authored artifact are a
   distinct READ-ONLY class (clang AST-for-tooling analogy) — they do
   NOT violate "database executes" because they ask about the model,
   not about data. TEMPLATE = the TDG carrier: a checker validates the
   native against the registered signature and emits a typed carrier;
   the orchestrator (StatementExecutor fold, TestDataGenerationNatives precedent)
   answers from the compiled model and splices INSTANCE LITERALS;
   downstream asserts then execute in-DB as normal. Compiler-minted
   factories only (ArchitectureTest invariant 7). Prelude classes for
   engine metamodel types (SetImplementation etc.) go in Pure.java
   with FULL-FQN property types + signatures verified against real
   legend-pure/engine .pure sources. Milestone the fold on
   fixpoint-following user-function chains (the stamper precedent —
   mappings reached through user fns must bottom out).
   CAUTION: newState (18) is toPostgresModel state-threading — read
   its .pure first; it may be a different shape than mapping
   reflection and belong with a store-model census, not this leg.
2. **pkOfFunc (43)** — FunctionDefinition.expressionSequence =
   reflection over a FUNCTION BODY. Same carrier frame, but the
   answer is a metamodel walk of the typed lambda we already hold
   (TypedLambda IS the expressionSequence). One test family; check
   whether all 43 rows are the single pkOfFunc helper — if so, one
   checker closes them.
3. **getAll census (76)** — NOT one bug: "the query shape AROUND
   getAll" varies. Census-first: for each of the ~20 distinct tests,
   print the resolver's walk context (the wall already names the path
   `[at root > TypedNativeCall > …]`). Expect a handful of shape
   families (getAll under native calls the resolver doesn't traverse).
   Rank by family size before building.
4. **ONE-STAMP (17)** — a bug leg: the invariant fires, meaning some
   construction site double-stamps or skips. Follow the docs/STAMP_*
   directive the message names; treat as correctness work (loud
   invariant = working guard, do not soften).
5. **TypedMap wall (65)** — belongs to the h2-lane (H2 renderer list
   vocabulary), sequenced with the h2-session convergence leg
   (memory: h2-session-convergence-leg; batch C blocked on the
   h2-lane 108-test diff). Not a resolver leg — do not attack from
   the decline side.
6. **host-unsupported (26) + tail (24ish)** — row-by-row after the
   big buckets; several tail rows (toPrettyJSONString, InstanceValue)
   are small ports.

## LANE 3 — sql-text unable-to-exec = 50 (pin ~line 824)

29 TDG (26 chained-fetch + 2 projection-demand + 1 no-generator) +
21 prior named (predicate-diverged 6, both-ours 5, forced-isolation 2,
column-arity 2, no-generator/H2C 2, adjudicated rest — the register
carries per-row attribution). AUDIT CORRECTION: the 6
predicate-diverged rows are NOT by-design — they are the queued
**emission-anatomy leg** (6 predicate-diverged + 1 skew, from the
pre-49er census): our emitted predicate spelling diverges from the
engine's in a way the replay can't referee; burn by aligning the
emission, not by adjudicating.

**The TDG 26+2 burn = charter §S5 (docs/TDG_LANE_CHARTER.md,
DESIGN COMPLETE, awaits execution).** Everything known is in that
section with receipts; the short form:

- Chained fetch = the engine's OWN anatomy: each hop materializes into
  a temp table the next hop joins; the temp name is IN the golden text
  (`testDataGen_Temp_<RootTable>`, no index, chained arm —
  testDataGeneration.pure:439; `_<id>` variants are the nested-VIEW
  path :391). Ours: `tdg_<counter>_<table>` (TestDataGenerator.java:656).
- REFUTED (measured, do not re-derive): renaming temps does NOT
  byte-match — our recorded texts diverge wholesale (we hand-concat
  single-line SQL, quoted cols, `limit 20`, `main`/table-name aliases;
  engine records `sqlQueryToStringPretty` layout: leading \n,
  `select top 20 \n\t`, bare cols, `"bicycle_0"`-style aliases).
- THE FIX: build the generator's FOUR recorded fetch shapes
  (sqls.add sites :278 root, :346 idSql, :372 chained join, :398 view)
  as SqlSelect IR; EXECUTE the DuckDb rendering, RECORD the
  EngineStyleH2 rendering — the exact existing golden-SQL doctrine.
  NEW WORK: a PRETTY formatting mode on EngineStyleH2 (none today;
  ~45 goldens to byte-converge), engine alias minting for these shapes
  (root=`"root"`, child=lowercase(table)+`_0`,
  parent-temp=lowercase(temp)+`_0`), executed-temp naming/lifetime
  aligned to engine (recorded name == executed name, one artifact).
  End state: byte-equal ⇒ assertSqlEquals passes natively in-DB —
  rows leave the lane; tdgSqlReplay stays as referee for residue.
- The 2 projection rows: our fetchCols demands `(firstName,id)` where
  engine demands `(id,legalName)` on concatenate shapes — align with
  engine `generateRelationColumnMap` (read it first; it is the spec).

## LANE 4 — sql-text text-only = 44 (pin ~line 781)

Mostly BY DESIGN: 17 alloy plan-literal + 3+3 plan-let (the plan-TEXT
lane owns these — plan-flavor deferred at TDG sign-off), 7+2+2
no-generator, 5+2 no-root-exec-variable, 2 match-noreplay, small rest.
Burn path: the plan-text lane charter (not yet written) for the ~23
plan rows; the rest re-examine after S5 (some causes vanish when the
generator records engine spelling).

## THE 49er REPLAY MACHINERY (already landed — how it works, for reuse)

`H2Verify.tdgSqlReplay(seeds, goldenSql, duckConn, ourSql)`:
- gates: READY, no ORDER BY (ordered fetch ⇒ Unverifiable — the
  multiset compare is only sound orderless; this gate is WHY the new
  sort sites are registered in HarnessDisciplineTest), no `tdg_`
  temp reference on either side (chained ⇒ Unverifiable, burns at S5).
- golden side: live H2 mirror via applyPendingSeeds (factored out of
  verify()), or for mirror-suspended PRIVATE sessions a fresh
  `jdbc:h2:mem:tdgreplay` + H2ExtensionFunctions.aliases + full seed
  replay from RawSqlBoundary.recording().
- our side: `duck.unwrap(DuckDBConnection.class).duplicate()` then
  `USE <duck.getCatalog()>` — TRAP: a plain duplicate lands in the
  wrong `__ws_N` workspace catalog and the real "Table X does not
  exist" hides behind "Attempting to execute an unsuccessful or
  closed pending query result".
- compare: rows rendered `col=val|…` NAME-SORTED + a header row of
  sorted column names; projection mismatch ⇒ Unverifiable
  ("projection differs"), else sorted-multiset equality.
- caller `EngineTestExecutor.tdgSqlTextVerify`: byte match → replay;
  rows==null → exec-pass (+M1_RESCUED/verdict("rescued") if text
  diverged); rows text → exec-diverged (REAL failure); Unverifiable →
  match-/diff-noreplay :: cause. GENERATE_TEST_DATA is in
  SQL_PRODUCER_FQNS and the producer scan SUBSTITUTES lets — without
  that, rescued rows dual-eval and show up as disagreements.

### Units caution (audit-corrected 2026-08-30)

`exec-passing` counts ASSERT rows; the rescue counters (`softRescued`,
`M1_RESCUED`-derived test flags) count passing TESTS with
`rescued() > 0`. The two do NOT subtract: "20 exec-passing, ratchet
+15" does NOT mean 5 asserts byte-matched — the byte-match count was
never measured, and any byte matches that do exist may be
SELF-matches (H2Compatible pairs / goldens folded from our own
generated text), not proof that any ENGINE golden matches our
spelling. The S5 charter's "texts diverge wholesale" measurement
stands.

## GOVERNANCE ROUNDS WHEN ADDING NATIVES/CARRIERS
(the recommended leg WILL add natives — every TDG slice hit all of
these; budget a governance round per chain)

- **Native catalog golden** `src/test/resources/native-catalog.txt`:
  regen by writing a temporary test that calls
  `NativeFunctionTest.renderCanonical` to dump the catalog, copy it
  over the golden, DELETE the temp test. Class-count pin in
  NativeFunctionTest (209 at handoff), DATA_SURFACE_PROPERTIES, and
  the package whitelist all move when Pure.java gains classes.
- **Pure.java signatures**: verify against the REAL
  legend-pure/legend-engine .pure sources in the reference checkouts
  — and sweep for dropped DEFAULTS on class properties (a real past
  bug). Property types are FULL-FQN.
- **Checkers** validate against the REGISTERED native signature via
  checkGeneric; new CoreFn entries + Typer.applyCore arms +
  PlatformTypes membership (isPlatformOwnedFunction).
- **Typed carriers**: TypedSpec permits clause + children()/
  withChildren + TypedSpecChildrenTest dummy rules (LambdaFunction /
  ValueSpecification dummies — `new CString("d", null, false)` idiom).
  Carriers are minted ONLY by compiler factories (ArchitectureTest
  invariant 7).
- **StatementExecutor** has a LINE BUDGET (2520) — fold hooks must
  compress to one line delegating to the orchestrator class.
- **JavaEvalLedgerTest** (file line pins + funnel registers),
  **CarrierPurityRatchetTest** (SqlFn.LIST_ pre-dialect count; pin
  moves use the semantic-node wire-shape-rule precedent),
  **JdbcSurfaceCensusTest**, **ErrorShapeGuardrailTest** (no broad
  catches — name NotImplementedException|TypeInferenceException),
  **HarnessDisciplineTest** (sort-site register per file).
- **Shared machinery to reuse, never re-implement**:
  `SourceSubst.substitute` = THE β-substitution (SubstitutionParityTest
  pins harness parity); `Pipelines.instanceLiteralProp` = THE
  literal-prop rule; `TestDataGenerationNatives.classifyArg` = the ONE arg
  classifier. Walkers enter lambdas through `lambda.body()`, never
  the lambda node.

## OTHER NAMED RESIDUE (owners on record)

- SourceSubst variable-arg call-site relocation — BLOCKED on
  harness-splice deletion; the checker walls name it.
- h2 list-vocab walls (~28, from TDG S1 sortBy rows reaching the
  renderer without list-lambda vocabulary) — h2-lane leg, honest
  advisory walls today.
- alloy plan-let 6 — plan-text lane (charter not yet written).
- Emission-anatomy leg — the 6 predicate-diverged + 1 skew above.
- 2 pre-existing family ERRORs (inheritance multi-hop, union prune).

## PINS THAT MOVE TOGETHER (the trap that cost two chain runs)

Converting unable rows to verified moves THREE pins in one commit:
1. `assert-sql-text-unable-to-exec` count (now 50, ~line 824);
2. `assert-sql-text-with-exec-passing` (now 1495, ~line 768);
3. the text-rescued shrink-ratchet (now ≤896, ~line 1150) — bump ONLY
   with the established comment idiom "JUSTIFIED by exec-passing +N +
   unable −N in the same commit … verification gained, not text
   decayed", counting how many of the N carry the rescue (byte-matched
   rows don't).
Plus, if harness code adds sorts: the HarnessDisciplineTest per-file
sort-site register (H2Verify.java now 7). And the scoreboard doc
`docs/RELATIONAL_CORPUS.md` regenerates during the sweep — stage it
with the landing (it carries sqldiff/advisory/rescued columns).

## STANDING PROCESS (verbatim rules that bit us this window)

- Chain: `LEGEND_ENGINE_ROOT=/Users/neemsandv/legend/legend-engine
  LEGEND_PURE_ROOT=/Users/neemsandv/legend/legend-pure caffeinate
  -dims tools/allgates.sh` in background; ZERO repo writes while it
  runs; >12 min = stop and fix the chain first.
- Scoped probe: `mvn -pl core test -Dtest=RelationalCorpusRunner
  -Drcorpus.only=<family> -Drcorpus.test=<substring>
  -Dlegend.engine.root=… -Dlegend.pure.root=…` (no comma lists).
  TDG family probe expected line:
  `agree=68 disagree=0 | exec-passing=20 text-only=6 UNABLE-TO-EXEC=29
  | test-data-csv=0`.
- Grep the FULL outcome vocabulary (PASS/FAIL/ERROR) — "FAIL"-only
  greps produced a false claim once already.
- Stage NAMED files only; commit trailer per repo standard; push
  after every gates-green batch.
- Measure before claiming: this window alone killed two plausible
  theories ("rename the temps" and "5 byte-matched" needed the
  ratchet's own arithmetic as the receipt).

## SUGGESTED NEXT-SESSION OPENING

1. Read this doc + docs/TDG_LANE_CHARTER.md §S5.
2. Pick: (a) no-scalar reflection cluster 72 (decline lane, TDG
   carrier template, biggest tests-per-design) or (b) S5 text-parity
   (finishes the TDG story; pretty-printer byte-convergence is
   iterative). Recommendation on record: (a) first — S5's pretty
   printer is a grind best done fresh, and (a) reuses the pattern
   just built while its precedent (TestDataGenerationNatives/CsvCensusChecker) is
   the newest code in the tree.
3. Census-first on whichever: print the witness rows, read the
   engine .pure spec for the natives involved, write the slice plan
   into the relevant charter BEFORE coding (the reverted-S1 lesson).
