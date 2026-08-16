# Cluster registry — every non-passing core_relational test, grouped by the change that fixes it

209 clusters over the 271 distinct non-passing tests at `9d1f2cd0`. A cluster groups tests that **one code change fixes**; 160 are singletons, which is the honest shape of this corpus.

Ordered within each section by tests-unblocked per unit of effort (XS=1, S=2, M=5, L=12, XL=30).

Per-test detail — evidence, risk, falsifier, adversarial review — is in the `bucket-*.md` dossiers.

---

## Actionable — 181 clusters, 232 tests

These are real work items.

### 1. DuckDB LEFT-JOIN build/probe swap reorders ORDER-BY-less results

**3 tests** · effort **XS** · confidence medium · bucket 6 (wrong rows) · verdicts: EXECUTION_TARGET_ARTIFACT 3

Tests: `testFilterMappingWithProjectionOverlappForcedCorrelated`, `testFilterMappingWithProjectionOverlappForcedOnClause`, `testFilterMappingWithProjectionOverlapp`

**Mechanism** — All three run the same helper: an `Org.all()->project([name, parent/name, parent/parent/name])` chain of two LEFT joins with no ORDER BY, then assert row CONTENT by POSITION via `rows->at(0..5).values`. Row counts and multisets are right — the observed at(0) tuple [Federation, Firm X, ROOT] is verbatim the engine's expected row 5. DuckDB's `build_side_probe_side` optimizer swaps build/probe for the LEFT joins, so output follows hash-bucket chain order of the inner relation rather than the driver-table scan; H2's order-preserving nested loops give the insertion order every engine expectation encodes. legend-lite never reorders: `Executor.tabular` appends in ResultSet order and `at(k)` lowers to LIMIT 1 OFFSET k. Existing `SET threads=1`/`TimeZone` pins do not cover join side selection.

**Owning code** — core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java:82 (and class javadoc :59-62); evidence: core/src/main/java/com/legend/exec/Executor.java:511-521

**Fix** — One line in the workspace bootstrap: after `st.execute("SET threads=1")` at DuckWorkspaces.java:82 add `st.execute("SET disabled_optimizers='build_side_probe_side'")`, keeping build=RHS/probe=LHS so output follows driver-table scan order and unmatched rows stay in place. Update the javadoc settings list to name the third pin and why. Validate with a FULL-corpus sweep, not a single-test run — tests currently passing on the swapped order by luck may flip. If the sweep shows the setting is insufficient or net-negative, the tenet-clean fallback is a docs/NOT_IMPLEMENTABLE.md entry classifying these as order-dependent-vacuous. Explicitly rejected: pooling the six `rows->at(k)` asserts into a multiset, loosening the comparator, or injecting a synthetic ORDER BY — all would let six different expected tuples match one actual row.

**Leverage** — One line clears three tests plus testChainedTwoHops. Cheap, but the two diagnoses disagree on disposition (fix vs ledger) and the setting is corpus-wide, so the sweep is the real cost, not the edit.

**Shares code with** — DuckWorkspaces.java is the shared corpus execution-target pin — any bucket whose failures are 'right multiset, wrong row index over an unsorted join' rides this same one-line change and should be swept together, not fixed independently.

---

### 2. StaticFold cannot fold isEmpty, so a dead if-branch is type-checked

**2 tests** · effort **XS** · confidence high · bucket 09 (?) · verdicts: REAL_DEFECT 2

Tests: `iqrClassifyTest`, `zScoreTest`

**Mechanism** — meta::pure::tds::extensions::joinWithOptionalColumns has body if($cols->isEmpty(), |join(…,{x,y|true}), |join(…,$cols->toOneMany())). Both tests β-substitute $cols to the literal []. StaticFold.evalCall has no isEmpty arm (StaticFold.java:243-436, default -> null), so foldCall's if-fold (StaticFold.java:144-149) does not fire and BOTH branches survive into the Typer. The dead else-branch join(q1,q2,JoinKind.LEFT,[]->toOneMany()) has a non-lambda 4th arg, so it takes plain resolveOverload against three same-arity 'join' candidates (native relation::join at Pure.java:1409 plus two meta::pure::tds::join overloads pulled in as a function-only parent source, RelationalCorpusRunner.java:705-721); all score -1 and InferenceKernel.java:810-821 throws. The failure text is byte-identical across the two tests.

**Owning code** — core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 (evalCall switch); the consumer is StaticFold.java:144-149 (foldCall's if arm)

**Fix** — Add three arms to StaticFold.evalCall: isEmpty, isNotEmpty, size — each evalList(ps.get(0)) and return null when the argument is not statically evaluable, so a non-static condition still leaves the if un-folded and loud. [] already evals to an empty List (StaticFold.java:164-174), so the literal window folds immediately. This mirrors legend-engine's preeval if-handler (preeval.pure:169-183), which pre-evaluates the condition and replaces the whole if with only the taken branch precisely to avoid type-checking un-evaluable dead branches.

**Leverage** — Excellent — three switch arms, two tests, and it is the correct general semantics (engine parity with preeval), not a point fix. Caveat that lowers the payoff: both tests then land on a constant-TRUE LEFT join (cross join) against an empty-key groupBy, which is unverified; expect an advance, not necessarily a green. Secondary cost: more if-conditions become foldable across every NormalizeRequired body, so walls elsewhere in tds/ and functions/ will move — budget a re-sweep, not just a two-test run.

**Shares code with** — StaticFold.java is shared with the digest/TDSColumn cluster below and with any bucket reporting 'no overload of … join structurally matches' or a NormalizeRequired body whose plan branches on isEmpty/isNotEmpty/size. If another bucket blames StaticFold.evalCall's missing vocabulary, merge — the evalCall switch is one edit surface.

---

### 3. DuckDB build_side_probe_side row-order artifact on index-addressed self-join asserts

**2 tests** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: EXECUTION_TARGET_ARTIFACT 2

Tests: `testSelfJoinPropertyMappingOverlap`, `testSelfJoinPropertyMappingWithDynaFunction`

**Mechanism** — Both tests assert `rows->at(k).values` against a golden whose SQL (selfJoin.pure:62) has no ORDER BY. Column names, row count (9), join semantics and the four-hop dyna boolean are all already correct: the tuple returned at index 0 is verbatim the engine's expected row 5 (resp. row 8), and the order-insensitive sibling testSelfJoinPropertyMapping passes on the same 9 rows via assertSameElements. Only the permutation differs, caused by DuckDB's build_side_probe_side optimizer swapping join sides across the chained LEFT joins.

**Owning code** — core/src/test/java/com/legend/rcorpus/DuckWorkspaces.java (after line 82)

**Fix** — One line: `st.execute("SET disabled_optimizers='build_side_probe_side'")` after DuckWorkspaces.java:82. No platform change is warranted. Explicitly do NOT emit an ORDER BY to stabilise the order — the golden SQL asserted at selfJoin.pure:62 has none and adding one breaks the SQL contract to satisfy an incidental one. Do NOT relax the comparator for `rows->at(k)`. The setting needs a full-corpus sweep: currently-passing tests that happen to pass on the swapped order will flip. Honesty note: only one of the nine rows has been observed for the dyna test, so a residual per-row defect (rows 6/7) cannot be excluded — if it still fails on a different index afterwards, re-diagnose from the new failure text rather than assuming order.

**Leverage** — Highest ratio in the bucket: one line, and the same line also covers tests 1 and 2 in this unit (four tests total). Cost is the full-corpus sweep, not the edit.

**Shares code with** — DuckWorkspaces.java is the shared execution-target config — any other bucket proposing a DuckDB SET should be folded into this single line.

---

### 4. ScanRelations.walk throws instead of silently skipping an unmapped property

**1 test** · effort **XS** · confidence high · bucket 03 (?) · verdicts: REAL_DEFECT 1

Tests: `testUnionWithJoinToOneTable`

**Mechanism** — buildRoots walks every collected property chain against every root class mapping, including union arms that do not map the navigated property. In walk, pmsFor returns empty, derivedChains returns null for a stored/association property, and the name is not a milestoning member, so control falls to a NotImplementedException. legend-engine's scanRelations.pure treats an empty propMappings collection as a normal outcome: `$propMappings->map(...)` contributes nothing and the set simply prints childless.

**Owning code** — core/src/main/java/com/legend/lineage/ScanRelations.java:1252-1254 (the throw); reached from :1211-1212 (empty pmsFor branch), :1796-1834 (derivedChains only scans cd.derivedProperties()), driven by :795-815 (buildRoots cross-product of paths x root class mappings)

**Fix** — Replace the throw at ScanRelations.java:1252-1254 with an unconditional `return;` — a set that does not map a property contributes no relation and no columns. Do not gate the skip on union-mapping-ness or on runtimeScan; the engine's skip is unconditional and the same rule feeds the testDataGeneration walk. If a safety net is wanted for legend-lite's own pmsFor lookup gaps (:1953-1982), log under LL_LINEAGE_DEBUG rather than throw.

**Leverage** — Highest leverage in the bucket by a wide margin: a one-line deletion in a single method, high confidence, and the diagnosis flags it as plausibly unblocking any other scanRelations/testDataGeneration test whose mapping has an arm not mapping a navigated property (unverified hypothesis). The output is a lineage tree golden, not DB rows, so blast radius is low. Do this first regardless of what else in the bucket gets built.

**Shares code with** — Mechanism is confined to core/src/main/java/com/legend/lineage/ScanRelations.java — walk() at :1211-1254 and its callers buildRoots :795-815. Any other bucket whose failure message is `scanRelations: property '<p>' has no property mapping in set '<c>'`, or that blames ScanRelations.walk / pmsFor / derivedChains, is the same one-line change and should merge here. Also relevant to testDataGeneration relation-tree failures, which consume the same walk.

---

### 5. generateObjectReferences host-fold recognizer rejects a collection RHS

**1 test** · effort **XS** · confidence medium · bucket 09 (?) · verdicts: REAL_DEFECT 1

Tests: `testObjectReferneceInWithMilestonedRootClass`

**Mechanism** — ObjectRefs.build opens with `if (!(rhs instanceof AppliedFunction af)) return null;` (ObjectRefs.java:36-44). This test binds `let productObjectRef = [generateObjectReferences(…), generateObjectReferences(…)]` — a PureCollection — so the recognizer declines, ConnEquality.letFold returns null (ConnEquality.java:66-78), and EngineTestExecutor falls through to lets.put(name, purifiedSetup(rhs, ctx)) at :461, binding the RAW calls. The un-folded generateObjectReferences then reaches the typer, which has no such native, and Typer.java:1443-1452 throws 'unknown function'. The passing sibling testObjectReferenceInWithMilestonedProperty uses the same function, mapping, set and two-key pkMap with a single unbracketed call — the collection wrapper is the sole discriminator.

**Owning code** — core/src/main/java/com/legend/harness/ObjectRefs.java:36-44 (the shape guard) and :63-76 (the CString JSON-array output); consumer at core/src/main/java/com/legend/resolver/Substitution.java:1477-1501

**Fix** — Split ObjectRefs.build: extract the current body into refsOf(rhs, ctx) returning the ASOR string list for a single AppliedFunction, and make build accept `rhs instanceof PureCollection pc ? pc.values() : List.of(rhs)`, concatenating into ONE merged JSON array — exactly the single-CString shape Substitution.objectReferenceInRewrite already expects. Keep the all-or-nothing rule: if any element is not a generateObjectReferences call, return null so the unknown-function wall stays loud.

**Leverage** — Genuinely cheap and well-isolated — a producer/consumer pair both owned by legend-lite, with a passing sibling proving every other layer works. Honest ceiling: this is the milestoned-ROOT graphFetch path while the passing sibling only exercises a milestoned PROPERTY, so a FAIL-on-rows downstream is plausible and would be a separate defect. Not a platform gap (Substitution's objectReferenceIn -> pk predicate rewrite is fully implemented), so it is not tenet-2 harness compensation.

**Shares code with** — Touches resolver/Substitution.java:1477-1531 (the objectReferenceIn -> pk predicate rewrite) only as a consumer — if another bucket blames Substitution's ASOR decode or the OR-of-ANDs pk path (Substitution.java:1560-1580), check whether the merged-array shape from this fix interacts before clustering separately. Also touches harness/EngineTestExecutor.java:416-461's let-fold chain.

---

### 6. enumDriverLoop never dereferences let-bound sources (diverged from driverPairLoop)

**1 test** · effort **XS** · confidence high · bucket 10 (harness SHAPE) · verdicts: HARNESS_GAP 1

Tests: `testAdjustDateTranslationInMappingAndQuery`

**Mechanism** — `$toAssertDbTypes->map({db | let s1 = toSQLString(...); let s2 = toSQLString(...); assert($s1 == $s2); })` is host orchestration carrying an assert — vocabulary the platform can never type — and the harness already HAS the unroller for it (enumDriverLoop). It misses only because it never dereferences lets: its source check is `esrc instanceof PureCollection ? pc0.values() : List.of(esrc)` (EngineTestExecutor.java:2206-2247), and here esrc is the Variable bound by the preceding `let toAssertDbTypes = [DatabaseType.DB2];`. A Variable is neither an EnumValue nor a dotted read off a PackageableElementPtr, so the strict element check fails and the helper returns null. driverPairLoop, immediately below at lines 2262-2265, does exactly this dereference correctly — the two helpers simply diverged. The statement then falls through to the K-natives arc and dies at StoreResolver's TypedMap default.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:2206-2247 (enumDriverLoop, no lets parameter), :2262-2265 (driverPairLoop, the correct deref to copy), :762-771 (spliceForms, must thread lets), :374-379 (spliceForms call site)

**Fix** — Give enumDriverLoop the dereference driverPairLoop already has: change its signature to take `Map<String, ValueSpecification> lets`, thread lets through spliceForms (line 762) and its call site (line 374), and before the element check do `if (esrc instanceof Variable v) { var bound = lets.get(v.name()); if (bound != null) esrc = bound; }`. Keep the strict literal-enum element predicate exactly as it is — its comment records the testComplexOrExistsToManyProperty misfire it prevents — and do NOT subst the whole statement before spliceForms, because resultVarLoop deliberately pattern-matches a PureCollection of VARIABLES and a blanket subst would destroy that match.

**Leverage** — Best effort-to-wall-removed ratio in the bucket — a few lines to bring two sibling helpers back into agreement, and it is a genuine divergence bug, not a feature. But be honest about the payoff: removing the wall does not guarantee a green. Behind it the test still has to clear assertEquals on the returned rows and assertSameSQL over `dateadd(day, -7, "root".dateTime)`, i.e. the adjust()/DurationUnit.DAYS lowering must actually be correct on DuckDB. Expect SHAPE -> PASS-or-FAIL, and treat a FAIL as progress. Zero further unblocks: `$var->map({...})` appears in exactly one test body in the corpus.

**Shares code with** — Shares StoreResolver.java:466-510 (the same TypedMap/default wall) with the assert-loop cluster above, and shares spliceForms/the statement router region of EngineTestExecutor with it — the `lets` threading through spliceForms is plumbing both would benefit from, so land this one first and let the other build on the threaded signature. Any bucket whose failure text is `class query under TypedMap is not resolvable yet (H2 vocabulary)` should be checked against this mechanism before anyone touches the resolver.

---

### 7. viewSchema compares a bare column name against a quote-bearing column reference

**1 test** · effort **XS** · confidence high · bucket 10 (harness SHAPE) · verdicts: REAL_DEFECT 1

Tests: `testRelationStoreAccessorOnView`

**Mechanism** — An asymmetric unquote between the protocol->model conversion of a column DECLARATION and of a column REFERENCE. DatabaseProtocolParser.parseIdentifier deliberately keeps a QUOTED_STRING's quotes as the wire name (DatabaseProtocolParser.java:90-99, 282-294). FromProtocol.table() stores the ColumnDefinition name BARE plus a quoted flag (FromProtocol.java:206-208). RelOpFromProtocol.columnRef() unquotes the schema and table but passes the column through verbatim (RelOpFromProtocol.java:71-79), so a ColumnRef keeps its quotes. StoreCompiler.viewSchema then compares `c.name().equals(cr.column())` — bare `FIRST NAME` against quoted `"FIRST NAME"` — finds nothing, returns Optional.empty(), and TableReferenceChecker reports `unknown table 'personView'`. StoreCompiler.tableSchema RE-ADDS the quotes when building a Type.Column (line 167-170, the project's stated quote-bearing RelationType convention); viewSchema simply is not following the convention its sibling establishes.

**Owning code** — core/src/main/java/com/legend/compiler/element/StoreCompiler.java:96-100 (the bare comparison) and :167-170 (the quote-bearing identity it should reuse); core/src/main/java/com/legend/model/RelOpFromProtocol.java:71-79 (the asymmetric unquote — do not change here); core/src/main/java/com/legend/compiler/spec/TableReferenceChecker.java:70-73 (the misleading message)

**Fix** — In StoreCompiler.viewSchema, compare against the SAME quote-bearing identity tableSchema builds: replace `.filter(c -> c.name().equals(cr.column()))` with a comparison on `c.quoted() ? "\"" + c.name() + "\"" : c.name()`, and factor that expression into a private `static String columnIdentity(ColumnDefinition c)` used by BOTH tableSchema and viewSchema so the two cannot drift again. Do NOT instead unquote in RelOpFromProtocol.columnRef — ColumnRef.column() is consumed by the renderers and by ScanRelations/MappingNormalizer/ViewRelation, which rely on the as-written spelling to emit quoted SQL, so unquoting there would silently unquote generated SQL. Add a regression check that a quoted column still renders quoted. Worth also splitting TableReferenceChecker's message so 'database absent' and 'view schema unresolved' stop sharing one string.

**Leverage** — The highest-value single-test entry here, and it should be done regardless of the test. XS effort, high confidence, and it is a genuine correctness defect with unbounded blast radius: EVERY view that projects a double-quoted column is currently unresolvable and reports the misleading 'unknown table'. Be honest about the test itself though — it will NOT go green from this fix. Its asserts pin the engine's exact plan-JSON envelope, while ElqSplice binds executeLegendQuery's result to toString(<query>) (ElqSplice.java:88-104), so the test moves SHAPE -> FAIL with a GOLDEN_TEXT_ONLY residual. Land it for the defect, count the green elsewhere.

**Shares code with** — Quoted-identifier identity spans StoreCompiler, FromProtocol, RelOpFromProtocol, DatabaseProtocolParser and the SQL renderers — any bucket with a 'column not found' / 'unknown table' failure on a model that declares a quoted column is very likely this same asymmetry and should be checked against columnIdentity before a separate fix is written. Separately, the plan-JSON-envelope residual is an ElqSplice.java:88-104 concern shared with any bucket whose asserts compare executeLegendQuery output against engine plan JSON.

---

### 8. Join-navigation database scope lost in metamodel type inference

**1 test** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testDynaComplexInference2`

**Mechanism** — `MetamodelWalk.inferOp`'s JoinNavigation arm recurses into the nav's terminal with the SAME `Rop` env, discarding the nav's own `[DB]` qualifier. For a property-mapping Expression the Rop is built with a null database, and RelOpFromProtocol deliberately strips the terminal ColumnRef's db ('as-written': the terminal is relative to the nav's own db). With both opDb and env.db() null, `columnType` returns null, and the concat arm silently adds 0 for an unknown-typed argument. So `concat(FULLNAME[VARCHAR(200)], @join|FULLNAME_PART2[unknown])` infers VARCHAR(200+0), and substring passes arg0's size through — the test sees VARCHAR(200) instead of VARCHAR(400).

**Owning code** — core/src/main/java/com/legend/exec/MetamodelWalk.java:1315-1317 (JoinNavigation arm), :1279-1289 (concat arm), :1504-1506 (columnType null), :1147-1148 (null-db Rop); context core/src/main/java/com/legend/protocol/RelOpFromProtocol.java:70-71,111-121

**Fix** — Rebind the environment database before recursing: in the JoinNavigation arm, take `j.databaseName()`, falling back to `j.chain().get(0).databaseName()` (the nav's db is legitimately null when rooted in the enclosing db; JoinChainElement always populates the hop), resolve it via `env.ctx().findDatabase(...)`, and recurse with `new Rop(resolvedDb, env.ctx(), j.terminal())`. Second, match the engine's guard: make the `concat`/`group_concat` arm return null (no inferred type) when ANY argument's type is unresolved rather than contributing 0 — silently shrinking a VARCHAR is exactly the wrong-value failure caught here. Land both together; the guard alone would turn passing-by-luck inference into empty results. Optionally thread the class mapping's store into the `Pm` handle as the default db (general cure for unqualified ColumnRefs in mapping expressions, not required here).

**Leverage** — Small, contained, and a genuine wrong-value fix. Blast radius is only the typeInference surface, so it is cheap to land — but it clears one test plus any corpus inferRelationalType assertion over a join-navigating dyna function.

**Shares code with** — MetamodelWalk.inferOp is reached from StatementExecutor.java:1410 (the inferRelationalType dispatch) — buckets blaming StatementExecutor for type-inference results are pointing at this, not at the executor.

---

### 9. Zero-row toCSV render misses joinStrings' unconditional suffix

**1 test** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: GOLDEN_TEXT_ONLY 1

Tests: `testIsEmpty`

**Mechanism** — The rows are right — both engine and legend-lite return zero rows (no seed has a null FIRM_LEGALNAME). Only the CSV rendering of a ZERO-row TDS diverges. The engine's corpus helper is `header + '\n' + rows->map(...)->joinStrings('', '\n', '\n')`, and joinStrings emits its prefix and SUFFIX even for an empty collection, so zero rows render as `name,firm\n\n`. legend-lite's re-implementation appends '\n' per line and so produces `name,firm\n` for zero lines. The exact compare fails, and the multiset fallback also fails: splitting the expected on '\n' gives ["name,firm", "", ""] and the trailing-empty skip forgives only the LAST element, so the interior "" is treated as a data line.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:3160-3181 (csvEquals render), :3196 (trailing-empty skip)

**Fix** — In `EngineTestExecutor.csvEquals`, reproduce joinStrings' suffix-on-empty: render as `header + "\n" + (lines.isEmpty() ? "\n" : lines.stream().map(l -> l + "\n").reduce("", String::concat))`. For N>=1 rows this is byte-identical to today (separator between lines plus terminating suffix equals one '\n' per line), so only the zero-row case changes. Do NOT loosen the multiset fallback's trailing-empty rule at :3196 to swallow interior blank lines — that would make a genuine empty data row compare equal to nothing. Apply the same suffix rule to `csvJoinedEquals` if it shares the renderer. Note the sibling `testIsEmptyType` expects `name,firm\n` for a zero-row result, contradicting the helper; it is `<<test.ToFix>>` in the corpus, so do not try to satisfy both.

**Leverage** — Trivial edit, correct in principle: csvEquals models a corpus helper function, and this makes the model match the helper's published definition rather than compensating for a platform shape. Low reach — one confirmed test.

---

### 10. average/mean over a to-one argument lowers to identity (Integer, not Float)

**1 test** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testSubAggregationMultiLevel`

**Mechanism** — Scalars.java's average/mean rule returns the identity when the argument is to-one, so the emitted column is the bare AGE column and the result keeps INTEGER type. `Firm.all().employees->map(e|$e.age->average())` navigates no to-many head, so CorrelatedSubselects.aggScan never raises an agg demand and the call falls through to that scalar rule. DuckDB's normalize is the interface default identity, so java.lang.Integers reach the comparator; wireEquals deliberately refuses int-vs-float equality, so [23,22,12,…] never matches [12.0,22.0,…]. The values are correct; only the kind is wrong.

**Owning code** — core/src/main/java/com/legend/lowering/Scalars.java:1173-1178

**Fix** — Return a Float-producing expression on the to-one arm instead of the identity: `isToOne(arg) ? SqlExpr.Call.of(SqlFn.TIMES, new SqlExpr.FloatLit(1.0), args.get(0)) : SqlExpr.Call.of(SqlFn.LIST_AVG, numList(args.get(0)))`. TIMES is preferred over an explicit DOUBLE cast because it reproduces the engine's own `1.0 * x` text. Do NOT touch the median rule two lines below (Scalars.java:1180-1185) — Pure's median returns Number, so its to-one identity is correct. No typer or outputs change is needed; the node's Pure type is already Float. Do not loosen wireEquals to bridge int-vs-float.

**Leverage** — Best ratio in the bucket: one-line typing fix for a real result-kind defect. Single test only (no other avg/mean failures in the sweep), but near-zero risk.

**Shares code with** — lowering/Scalars.java numeric rules — any bucket reporting Integer-vs-Float wire mismatches should check this same to-one-identity pattern across other aggregate rules.

---

### 11. meta::pure::mapping::withMapping is unregistered and unrecognized by FromChecker

**2 tests** · effort **S** · confidence high · bucket 09 (?) · verdicts: MISSING_FEATURE 2

Tests: `testFromWithMapping`, `testFromWithMappingAndIntermediateFuncCall`

**Mechanism** — withMapping has no NativeFunctionDefinition (Pure.java registers only the sibling WITH_CHAINED_MAPPINGS at :1293) and no CoreFn entry, so Typer.checkGeneric finds zero candidates and throws the 'unknown function' wall at Typer.java:1446-1452. Two consequences chain: FromChecker's query-side channel (FromChecker.java:92-104) has an arm for withChainedMappings but none for withMapping, so even once registered the mapping would never reach TypedFrom.mapping() (the input to JsonSourceFrame.fromContext at :158-163 and StatementExecutor.firstFromMapping at :803-816); and Runner.executeMappingRefs's fromShape predicate (Runner.java:900-902) does not recognize withMapping, so mappingRefs is empty, run0 takes tryRunNoExecute (Runner.java:1287-1315) and the typer wall surfaces as the SHAPE ' — wall: …' suffix. The second test differs only by an intervening ->cast(@TabularDataSet), which CastChecker.java:33-41 normally treats as emission-identity.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1293 (+ core/src/test/resources/native-catalog.txt:180); core/src/main/java/com/legend/compiler/spec/FromChecker.java:92-104; core/src/test/java/com/legend/rcorpus/Runner.java:900-902

**Fix** — Three coordinated edits mirroring the existing withChainedMappings channel: (1) register WITH_MAPPING with the engine's verbatim multiplicity-preserving signature withMapping<T|m>(source:T[m], mapping:Mapping[1]):T[m] (mappingExtension.pure:386) plus the golden-catalog line; (2) replace FromChecker's single direct-source peek with a strict SPINE walk that absorbs withMapping's mapping FQN into the TypedFrom mapping Optional, stripping only a TypedCast whose target is the TDS nominal (CastChecker's own identity condition) and leaving any narrowing cast in place; (3) widen Runner's fromShape predicate to accept withMapping so the test takes the SEEDED execute path (tryRunNoExecute does not replay seeds). The cast tolerance in (2) is the only test-2-specific requirement — one fix, both tests.

**Leverage** — Good: two tests, three small edits, all patterned on code that already exists one line away. The engine's routing semantics are unambiguous (routing.pure:724-737 pairs withMapping and withChainedMappings in one rule), so there is little design risk. The one hard constraint: the spine walk must never become a subtree scan — concatenate(A->withMapping(M1)->from(rt), B->withMapping(M2)->from(rt)) is the engine's own known-broken case (testMultipleFromWithMapping is <<test.ToFix>> and excluded at Runner.java:470), and a subtree scan would silently fabricate an answer there.

**Shares code with** — Touches builtin/Pure.java (native catalog + golden native-catalog.txt) and the from()/mapping plumbing that JsonSourceFrame.java:158-163 and StatementExecutor.firstFromMapping (StatementExecutor.java:803-816) consume. Any bucket whose tests wall on 'unknown function' for a mapping-routing native, or whose from() loses its mapping context, should be checked against FromChecker.java:92-104 before being clustered separately.

---

### 12. Enumeration half of the mapping-metamodel navigation family is unported

**2 tests** · effort **S** · confidence medium · bucket 09 (?) · verdicts: MISSING_FEATURE 2

Tests: `testEnumMappings`, `testEnumMappingsWithInclude`

**Mechanism** — meta::pure::mapping::enumerationMappingByName and meta::pure::mapping::toDomainValue are legend-pure platform functions. legend-lite ported the CLASS half of that family as natives + a MetamodelWalk arm (Pure.java:1478-1491: rootClassMappingByClass, classMappingByClass, classMappingById, superMapping, allSuperSetImplementations) but never the ENUMERATION half, so Typer.checkGeneric finds zero candidates and throws at Typer.java:1444-1451. The data is already modelled — LegacyMappingDefinition.java:58 carries enumerationMappings, and EnumerationMapping.java:35-65 carries EnumValueMapping/SourceValue — only the walk arms are missing. The second test adds one requirement: tradeMapping3 declares no enumeration mappings of its own (two include hops), so the lookup must be include-closure-recursive.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1489 (beside CLASS_MAPPING_BY_ID); core/src/main/java/com/legend/exec/MetamodelWalk.java:845-866 (classMappingByIdIn, the recursion to mirror) and :771-782 (Cm/Pm records); core/src/main/java/com/legend/StatementExecutor.java:1298-1415 (the planWalk native switch)

**Fix** — One change covering both tests: register the two natives, add an Em walk handle beside Cm/Pm, implement enumerationMappingByName as an exact copy of classMappingByIdIn's include-first recursion (functions_Mapping.pure:21 puts includes' hits BEFORE own hits, then removeDuplicates+toOne — do not short-circuit on the local mapping), implement toDomainValue as a sourceValues scan asserting exactly one match, and add the two planWalk switch arms. toDomainValue must return the bare value NAME string to stay consistent with StatementExecutor.java:1668's TypedEnumValue convention, or the assertEquals(TradeType.BUY, …) compare fails instead of erroring.

**Leverage** — Clean two-for-one with a precedent to copy line-for-line, and it partially unblocks testEnumTheSame (which additionally needs an enumerationMappings PROPERTY arm in MetamodelWalk.prop — today that mis-parses as an enum-value access at Typer.java:2664). Ordering matters more than it looks: getting includes-first backwards would pass both these tests and be silently wrong for any shadowing mapping.

**Shares code with** — Adds arms to StatementExecutor's planWalk native switch (StatementExecutor.java:1298-1415) and to MetamodelWalk — the same surfaces any other bucket's metamodel-navigation walls (schema/table/classMappingById/propertyMappingsByPropertyName shapes) would touch. Also a builtin/Pure.java catalog addition, mergeable with the other catalog-only clusters into one Pure.java pass.

---

### 13. %latest generated-date column emitted for classes mapped to non-milestoned tables

**2 tests** · effort **S** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 2

Tests: `testLatestIgnoredForNonMilestonedMappedClassesAllQuery`, `testLatestIgnoredForNonMilestonedMappedBiTemporalClassesAllQuery`

**Mechanism** — GraphEmission.synthesizeScalarTree adds a businessDate (and, for BITEMPORAL, a processingDate) envelope node for any class whose temporal strategy is non-null, with no reference to the mapped relation. The leaf returns the root context date verbatim, %latest lowers to TimestampLit('9999-12-31 00:00:00.0000'), and RelationalRootForm renames it to k_businessDate/k_processingDate. The engine's getTemporalDateAlias returns [] for a %latest date on a relation that cannot support the strategy, so TemporalMilestoningContext.columns() contributes no Alias and the column simply does not exist. The suppression is %latest-specific — a concrete date on a non-milestoned table still projects the alias, which is what the earlier audit-14 removal was right about.

**Owning code** — core/src/main/java/com/legend/resolver/GraphEmission.java:149-159 (both nodes added whenever strat != null) and :174-182 (generatedDateLeaf returns ctxDate); core/src/main/java/com/legend/lowering/Lowerer.java:2214-2215; core/src/main/java/com/legend/resolver/RelationalRootForm.java:107-110

**Fix** — One change: add a package-private TemporalFrame.latestAliasLiteral(pipe, dim) helper next to stampWithBlock (TemporalFrame.java:1359-1398) that resolves the mapped root relation's milestoning block for that dimension and returns its INFINITY_DATE in Pure toString() form, or null when the relation cannot support the strategy. Then in GraphEmission.synthesizeScalarTree skip each generated-date node when rootContextDate(dim) is a TypedCLatestDate AND latestAliasLiteral(cs.pipeline(), dim) == null — decided independently per axis, so a bitemporal class over a business-only table keeps k_businessDate and drops k_processingDate. Do not gate on the relation for non-latest dates. Fall through to emitting for union/partial-milestoning sources rather than silently dropping.

**Leverage** — Best effort-to-tests ratio in the bucket: S effort, two tests, one change, both diagnosed high-confidence with the same code site and the same engine rule. The helper it introduces (latestAliasLiteral) is also the prerequisite for the %latest k_ literal half of the milestoning join-order cluster, so building it here makes that cluster cheaper. Do this one first.

**Shares code with** — resolver/GraphEmission.java:149-182 and the new TemporalFrame.latestAliasLiteral. Any bucket whose got carries an unexpected k_businessDate / k_processingDate column, or whose k_ column value is TIMESTAMP'9999-12-31 00:00:00.0000' where the golden says a string, shares this code and this helper — merge.

---

### 14. EngineStyleH2 renders aggregate reducers by lowercasing the ANSI spelling instead of using the engine's formats

**2 tests** · effort **S** · confidence high · bucket 04 (?) · verdicts: GOLDEN_TEXT_ONLY 2

Tests: `testIsDistinctSQLGeneration`, `testToSQLStringJoinStrings`

**Mechanism** — EngineStyleH2.reducer post-processes super.reducer(r) by lowercasing only the substring before the first '(', so AnsiSqlRenderer's ANSI spellings leak through: COUNT(DISTINCT x) becomes count(DISTINCT x) where the engine spells DISTINCT as a nested call count(distinct(x)), and the SqlAgg.Fn enum name becomes string_agg(...) where the corpus H2/DB2 contract is listagg(col, sep). One method, two wrong spellings. joinStrings carries an independent rider: Lowerer injects a DuckDB-only ORDER BY <alias>.rowid determinism key into any unordered STRING_AGG and does not gate it on EngineTextBoundary, so a DuckDB pseudo-column leaks into the engine-text channel.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1205-1214 (reducer) with core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:705-706; riders at core/src/main/java/com/legend/lowering/Lowerer.java:1106-1116 (unguarded rowid injection) and EngineStyleH2.java:939-942 (rowOrder), with the existing precedent for the channel split at Lowerer.java:3158

**Fix** — Rewrite EngineStyleH2.reducer to build the string locally rather than post-processing super: extract the ORDER BY suffix into a protected helper on AnsiSqlRenderer that both renderers share, then emit `fn.toLowerCase() + "(" + (distinct ? "distinct(" + args + ")" : args) + order + ")"`, and add a STRING_AGG name mapping to listagg(<value>, <sep>). EngineStyleDB2/Composite inherit correctly (both take distinct(%s) from extensionDefaults; DB2's own listagg format has no space after the comma — override the separator spacing in EngineStyleDB2 if a DB2 golden pins it, do not fork the reducer). Rider: add `&& !EngineTextBoundary.active()` to the Lowerer STRING_AGG rowid-injection condition so execution keeps deterministic ordering and the engine-text/plan channel stops emitting rowid.

**Leverage** — Neither test goes green — both are alias-gated — so judge this purely on the corpus-wide effect, which is substantial for S effort: the diagnoses count 41 listagg goldens across the relationalStore module and five-plus count(distinct( goldens in testGroupBy.pure/testModelGroupBy.pure that currently pass on rows while carrying silent advisory sql diffs. It also removes a genuine correctness hazard — the engine-style text is EXECUTED on the real H2 second target in the M1 byte-match path, and `"persontable_0".rowid` is not valid H2. Verify count(distinct(x)) parses on H2 2.1.214 and re-run the row-verified joinStrings tests before landing.

**Shares code with** — sql/dialect/EngineStyleH2.java:1205-1214 reducer and lowering/Lowerer.java:1106-1116. Any bucket with an advisory or hard diff on count(DISTINCT vs count(distinct(, on string_agg vs listagg, or on a stray ORDER BY ... rowid ASC inside an aggregate is this same method — merge them all into one reducer change.

---

### 15. H2 dialect chimera: adjust() unit case vs legacy plan-param spelling

**2 tests** · effort **S** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 2

Tests: `testTemporalDateVariableInFunctionExpression`, `testTemporalDateVariableInFunctionExpressionWithPropagation`

**Mechanism** — legend-lite mixes the engine's two H2 dialects in one string. EngineStyleH2.expr renders a non-dotted DATE PlanParam in the LEGACY freemarker form '${bd}' (golden #1's spelling), while the ADD_INTERVAL arm spells the interval unit through dbUnitOf, which returns lowercase ("day") — the h2-new spelling (golden #2). Result `dateadd(day, 1, '${bd}')` matches neither golden, and planTextAssert compares strictly against golden[0] then golden[1] with no row fallback. SqlFn.ADD_INTERVAL_TEMPORAL already renders uppercase but is only ever emitted by DateShifts.dayOfWeekShift. The propagation test is the same query with the shifted date appearing four times, all from the same rule.

**Owning code** — sql/dialect/EngineStyleH2.java:1427-1435 (ADD_INTERVAL arm), :1356-1370 (dbUnitOf), :1001-1005 (PlanParam); sql/SqlFn.java:62-65; lowering/Scalars.java:497-502 (unchanged)

**Fix** — Merge the ADD_INTERVAL / ADD_INTERVAL_TEMPORAL arms at EngineStyleH2.java:1427-1435 into one that picks the unit case from the OPERAND's dialect, not the opcode: uppercase the dbUnitOf result when the fn is ADD_INTERVAL_TEMPORAL or when the date operand contains a plan-template parameter. Add a private containsPlanParam(SqlExpr) helper next to dbUnitOf walking PlanParam/Call/Cast/Group. Nothing in Scalars, TemporalFrame or the harness changes. Do NOT flip dbUnitOf globally to uppercase and do NOT route the milestoning adjust to ADD_INTERVAL_TEMPORAL in Scalars/TemporalFrame — literal-date milestoning goldens render DATE'...' and already byte-match golden #2; a global flip breaks those plus a dozen single-golden lowercase toSQLString goldens. Do not make planTextAssert case-insensitive.

**Leverage** — Highest ratio in this bucket: one merged switch arm plus a tree-walk helper fixes two goldens. Sibling testTemporalDateVariableInPropertySequence already passes, pinning everything but the unit token.

**Shares code with** — EngineStyleH2 dialect-token spelling; any bucket whose diff is a dateadd/interval unit-case or a ${...} plan-param rendering shares this arm. Scalars.java:497-502 is read but must not change.

---

### 16. UserCallInliner lambda binders not recorded when env is non-empty (exec-frame variable capture)

**2 tests** · effort **S** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 2

Tests: `columnValueDifferenceTest`, `columnValueDifferenceWithoutPrevalTest`

**Mechanism** — StatementExecutor.spliceHook has a shadow guard that drops exec-frame names shadowed by lambda binders, driven by the `boundVars` set UserCallInliner supplies. UserCallInliner.lambda records binders in `bound` only in the `env.isEmpty()` branch. Both tests have a plain (non-execute) `let` in letPrefix, so inlineBody passes a non-empty scope, the α-renaming branch runs, `bound` stays empty, and the guard is inert. spliceHook's bare-frame-variable arm then replaces the map lambda's own `$r` with the exec frame's TypedFrom query chain. The duplicated class query sits inside a row-map lambda, matches no resolver arm, and StoreResolver.assertNoStoreOnlyEscapees walls.

**Owning code** — core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:468 (lambda), ~493 (lambda-local TypedLet loop)

**Fix** — Hoist the `bound` binder bookkeeping out of the `env.isEmpty()` branch so it wraps the whole method body: increment for each of `l.parameters()` up front, add the fresh α-renamed names to `bound` as `bind(...)` mints them, and decrement all of them in a `finally`. spliceHook fires on nodes both before and after env substitution, so both spellings must be shadow-protected. Apply the same treatment to the lambda-local `TypedLet` binder loop in the α-renaming branch. One change fixes both tests. Expect them to advance past the escapee wall but not necessarily to green: the trailing `assertEquals($relationalResult->toCSV(), $inMemoryResult->toCSV())` still needs an in-memory columnValueDifference (outer TDS join + concatenate) over two execute() results — verify that next.

**Leverage** — Best in the bucket: one small, high-confidence change clears two tests and any corpus test that reuses an exec-frame name as a lambda binder. Residual in-memory TDS work may follow.

**Shares code with** — Touches UserCallInliner (compiler/spec) and the StatementExecutor.spliceHook contract — any bucket blaming StatementExecutor frame splicing or lambda shadowing shares this fix.

---

### 17. Predicate scope carries no association materials (parked filter preds)

**2 tests** · effort **S** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 2

Tests: `testSubFilter`, `testQualifiedPropertyInQuery`

**Mechanism** — A filter predicate parked on an association head (SyntheticHeads.parkFiltered) is applied via the 5-arg CorrelatedSubselects.predFilteredPipe, whose subNavs is Map.of(). The predicate's Substitution.Target is therefore built with Registries.NONE and nested=true, so any association navigation inside it ($e.address.city) reaches Substitution.assocLeaf with a==null and throws "nested navigation 'address.city' inside an exists/isEmpty predicate is not supported yet". The material already exists: scanCondTargetReads/collectNestedAssocReads already joined Address into the target pipe under prefix 'address_' and recorded it in nestedPrefixByProp — it is simply never handed to the predicate scope. testQualifiedPropertyInQuery reaches the identical throw after employeesInCity() inlines to the same filter shape, so the qualified-property inline itself is not implicated.

**Owning code** — core/src/main/java/com/legend/resolver/AssociationJoins.java:1129-1131 (call site), :1052-1071 (widening loop), :950-960 (synthPreds); core/src/main/java/com/legend/resolver/Substitution.java:2028-2038 (the wall)

**Fix** — In AssociationJoins.associationJoin: (1) after synthPreds and after nestedAssocReads is populated by scanCondTargetReads (:1026), also run collectNestedAssocReads over each parked pred body against targetClass, so a pred navigating an association the condition does not read still gets joined in — this is what makes the fix general rather than accidental for this mapping. (2) In the widening loop at :1052-1071 retain each AssocJoin aj2 and build Substitution.SubNav(pfx, aj2.target().rowVar(), aj2.target().bindings()) into a nestedSubNavs map; switch :1129-1131 to the 6-arg predFilteredPipe(p, target, tMat.slotPrefixes(), nestedSubNavs, pred, cs.mappingFqn()). predFilteredPipe already converts SubNavs to AssocSubs keyed by property. Also confirm parkFiltered's alpha-canonical dedup (SyntheticHeads.java:1200-1216) does not merge qualified-property calls with different literal arguments.

**Leverage** — Two tests, one small resolver change, and it unblocks the general "filter on an associated property" shape that any corpus mapping can hit. Best value-per-line in this bucket.

**Shares code with** — The wall is resolver/Substitution.java:2028-2038 (assocLeaf, Registries.NONE / nested=true contract at :122-125) — any other bucket blaming 'nested navigation inside an exists/isEmpty predicate' shares this exact site; fix should be coordinated.

---

### 18. Relational join/filter comparisons are subjected to Pure overload resolution (Date column vs String literal)

**4 tests** · effort **M** · confidence high · bucket 09 (?) · verdicts: REAL_DEFECT 4

Tests: `testMultipleJoinsInPropertyMappingWithDateInJoin`, `testBiTemporalUnionAsJoinTarget_correlatedSubqueryQuoting`, `testBiTemporalUnionJoin_milestoningColumnInOnClause`, `testBiTemporalUnionWithSelfJoin_duplicateColumnRegression`

**Mechanism** — RelOpTranslator's Comparison arm translates an engine DynaFunc join/filter condition into a real-Pure AppliedFunction (comparisonFn returns 'greaterThanEqual'/'lessThanEqual', RelOpTranslator.java:580-589) with the SQL string literal mapped unconditionally to a CString (RelOpTranslator.java:570-578), while the column reads as STRICT_DATE (StoreCompiler.java:191). Pure's ordering overloads are correctly same-family only (Pure.java:1318-1329, 1695-1706), so every candidate scores -1 (InferenceKernel.java:1046) and resolveOverload throws (InferenceKernel.java:819-821), wrapped by SpecCompiler.java:69 as "in function '<mapping>$class$…'". The engine never type-checks these operands at all (RelationalParseTreeWalker.java:823 — untyped Literal in a DynaFunc). The synth function is built over ALL property mappings of the set, so one bad join condition sinks a whole class mapping even for queries that never navigate it.

**Owning code** — core/src/main/java/com/legend/normalizer/RelOpTranslator.java:493-514 and :580-589; core/src/main/java/com/legend/builtin/Pure.java:788-794 (Pure.Lite block), :831-836 (ENGINE_VOCAB_SHIMS), :1819 (NOT_EQUAL_ANSI precedent)

**Fix** — One change: give DynaFunc-sourced ordering comparisons a loosely-typed vocabulary exactly as notEqualAnsi already has. Add meta::legend::lite::{lessThan,lessThanEqual,greaterThan,greaterThanEqual}(left:Any[1], right:Any[1]):Boolean[1] to Pure.Lite + ENGINE_VOCAB_SHIMS + the native catalog, and have RelOpTranslator.comparisonFn return the Lite fqn for LT/LTE/GT/GTE (EQ/NEQ already route to Any-typed equal). Lowering needs no change: Scalars.java:121-123 and Substitution.java:1089-1096 key on the BARE name index. Explicitly REJECT the competing fix floated in the testMultipleJoins… entry (coerce the String literal to CDate at the comparison site): it renders DATE'…' and breaks the engine goldens (testUnionBiTemporalSelfJoinDuplicateColumn.pure:34 shows a bare quoted string), and it needs a new column-type lookup on RelOpTranslator.PipelineView. The Lite shim covers both operand orders and the TIMESTAMP case in advancedRelationalSetUp.pure:52 for free.

**Leverage** — Highest ratio in the bucket: one localized vocabulary change clears the G-phase wall for 4 tests and, per the diagnoses, for every relational join/filter/view ordering comparison in the corpus. But be honest about the ceiling — 3 of the 4 are expected to re-fail one layer down (bi-temporal union golden SQL: suffixed/quoted lake_from_0/lake_thru_0, unionalias nesting, per-arm null padding). Only testMultipleJoinsInPropertyMappingWithDateInJoin has a realistic shot at green from this change alone (its goldens are advisory and its content is three row asserts). Still worth doing first: it converts four opaque typer walls into four honest, separately-diagnosable SQL-shape failures.

**Shares code with** — Touches InferenceKernel.resolveOverload's winners-empty throw (InferenceKernel.java:809-821) and the Pure.java native catalog — any other bucket whose wall text is "no overload of 'greaterThanEqual'/'lessThanEqual' structurally matches the argument types" inside a `$class$`/`$set$` synth function is the SAME cluster and should be merged in. Also touches Pure.Lite/ENGINE_VOCAB_SHIMS and Pure.wireEmissionName (Pure.java:853), which other buckets may blame for emission-name rewrites.

---

### 19. StaticFold's static vocabulary cannot carry a TDSColumn (Col) or a lambda across the fold boundary

**3 tests** · effort **M** · confidence medium · bucket 09 (?) · verdicts: REAL_DEFECT 3

Tests: `testExtendDigest_Relational`, `testExtendDigest_InMemory`, `testJoinWithExtendWithDigestOnColumnsOnBothQueries`

**Mechanism** — extendWithDigestOnColumns is NormalizeRequired, so Typer.inlineNormalized (Typer.java:1274-1305) alpha-renames, β-substitutes and folds its body. Three vocabulary holes break the fold. (i) evalCall has no toOneMany arm, so $input.columns.name->toOneMany() reifies as an AST call; the downstream `in` arm (StaticFold.java:278-285) then nulls, `filter` (:334-354) nulls, and the map UNROLL gate (:129-141) fails, leaving the raw $input.columns->filter(c|$c.name->in(…)) chain to reach the Typer, where bare .columns types as a String collection (Typer.java:2465-2467 -> columnsMeta :2334-2345) and $c.name throws at Typer.java:2597. (ii) When the unroll DOES fire, it binds the loop variable only in a private Java scope and drops the lambda binder; reify (StaticFold.java:519-544) has no Col or TypeToken arm, so a whole-Col occurrence (toStringForColAccessor($col)) is emitted as a free Variable and Typer.java:164-165 throws "unbound variable '$_nr2'". (iii) eval has no LambdaFunction arm, so toStringForColAccessor's pair(Type,{r|…}) dispatch table and its terminal toOne('Unsupported column type: '…) cannot evaluate.

**Owning code** — core/src/main/java/com/legend/compiler/spec/StaticFold.java:243-436 (evalCall: toOneMany, toOne-with-message, toString), :157-180 (eval: LambdaFunction), :519-544 (reify: Col/TypeToken/Quote), :129-142 (map UNROLL), :94-99 (the fold shortcut that must skip LambdaFunction); consumer walls at core/src/main/java/com/legend/compiler/spec/Typer.java:164-165 and :2597

**Fix** — One coordinated completion of StaticFold's static vocabulary: (1) evalCall gains toOneMany (identity on the evaluated list), a 2-arg toOne(v,msg) spelling, and toString; (2) eval/reify gain a quoted-lambda value (fold the body BEFORE quoting) with the mandatory guard that fold() skips its eval/reify shortcut for LambdaFunction, or existing col({r|…}) folding silently regresses; (3) reify gains a TypeToken arm and evalProperty's List arm auto-maps .first/.second over a List of Pair; (4) a Col bound by the unroll must survive β-substitution into a schema-erased helper — preferred route is calling the already-public typer.rawSchemaErasedExpansion (Typer.java:1348) from foldCall, gated on a non-reifiable capture, and folding the expansion in the SAME scope. Fallback route named in the diagnoses: register extendWithDigestOnColumns (both arities) as a native + checker desugar (InferenceKernel.java:836-850 already implements the native-beats-corpus-body tie-break) — cheaper, but it fixes only this helper and generalizes to nothing.

**Leverage** — Three tests for one file's worth of edits, and it removes a whole class of NormalizeRequired inline failures — but this is the cluster most likely to consume effort without producing greens. Two independent blockers sit behind it: MD5 hash() must exist on the DuckDB path, and the reconstructed goldens show the engine emitted concat(A,B,'|') (separator APPENDED once) while legend-lite's joinStrings interleaves (Scalars.java:813-857) — that alone would make the digest tests fail on rows. The lambda-as-static-value change is also the single most regression-prone edit in the bucket. Recommend landing (1) alone first (toOneMany + toOne-with-message) as a cheap probe, then deciding.

**Shares code with** — Shares StaticFold.evalCall with the isEmpty cluster (land them together — same switch), and shares Typer.java:2465-2467/:2334-2345 (bare .columns types as column-NAME Strings, not TDSColumn) with the rowValueDifferenceTest cluster below. Any bucket whose wall is "cannot access 'name' on String" or "unbound variable '$_nrN'" belongs here. Also touches Typer.rawSchemaErasedExpansion (Typer.java:1348) and EvalChecker.java:50-57.

---

### 20. VarSetPlaceholder result-column typing on cross-store TDS joins

**3 tests** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 3

Tests: `tdsJoinTwoDBExtend`, `tdsJoinTwoDBWithColumnMappedViaJoins`, `testCrossDbPlanGenerationWithFromWithoutExternalMapping`

**Mechanism** — crossDbTdsPlan splices the leftmost subselect into a `(${tdsVar})` placeholder for SQL TEXT ONLY and deliberately hands PlanText.single the pre-splice plan (StatementExecutor.java:648-650, 745-752). resultColumns therefore takes the star-top branch and resolves firstName physically to personTable.FIRSTNAME → VARCHAR(200). The engine has the placeholder relation in the query when resultColumns is computed, and every VarSetPlaceHolder column is hard-typed `^Integer()` (pureToSQLQuery.pure:583), so tdsVar-sourced columns print INT. The `type = TDS[...]` line rides paths/sourceDataType and stays VARCHAR(200) in both engines — hence the golden's apparent contradiction. Single divergence in all three tests: ("firstName", INT) vs ("firstName", VARCHAR(200)).

**Owning code** — core/src/main/java/com/legend/plan/PlanText.java:495-512,528,540-562,675,718; core/src/main/java/com/legend/StatementExecutor.java:726,737-739,745-753

**Fix** — (1) Split PlanText.spliceLeftVar into `spliceLeftVarQuery(plan, var)` returning the spliced SqlSelect; keep the rendering wrapper. (2) Add a `colsPlan` parameter to PlanText.single used ONLY by the resultColumns call at :76; typeBlock/tdsTuples keep the original plan (existing overloads delegate with colsPlan = typePlan). (3) In resolveStarColumn (:675) and resolvePhysical (:718) add a `SqlSource.VarSetPlaceholder` arm returning a private sentinel when a vp.outputs() name matches; resultColumns (:528) spells INT on the sentinel instead of doing findTableDefinition. Keep the sentinel private so tdsTuples can never reach it. (4) In crossDbTdsPlan, compute the spliced IR and pass it as colsPlan for the terminal (:751-753) and each per-Allocation splice (:726/:737-739). Do not touch the typeBlock arguments.

**Leverage** — High: three tests, one localized change, and the diff is a single column spelling. Caveat — it reproduces a known engine bug (VARCHAR printed as INT); matching the corpus is a deliberate policy call.

**Shares code with** — Touches plan/PlanText.java (resolveStarColumn/resolvePhysical/resultColumns/single signature) and StatementExecutor.java crossDbTdsPlan — both blamed by other buckets. The new single(...) overload and the placeholder arm will collide with any other PlanText edit; land them together.

---

### 21. M2M eager binding substitution — defer the unmapped-property wall to read time

**3 tests** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 3

Tests: `testModelConnectionDeepFunction`, `testModelConnectionAgg`, `testModelConnectionMultipleAgg`

**Mechanism** — ClassSources.composeModelToModel substitutes every key of the M2M constructor eagerly (loop at ClassSources.java:897-907). simpleModelMapping's Person class mapping binds four properties over ~src _S_Person, but the upstream relationalMapping maps only fullName, so `type : $src._type` has no inner binding and substituteSourceReads throws the H5b wall at ClassSources.java:951. All three queries demand only firstName and lastName; the failing binding is never needed. Eager Work where the tenets require lazy.

**Owning code** — core/src/main/java/com/legend/resolver/ClassSources.java:897-907, :951, :932; resolver/ClassSource.java:38; InnerDemand.java:81,:230; StoreResolver.java:697,:852,:1296,:1336,:1646,:2150,:808,:765,:787,:1526,:2501,:3430; GraphEmission.java:251,:125; CastNav.java:98

**Fix** — Replace the eager loop with per-property try/catch: on NotImplementedException record `prop -> message` in a new `deferredWalls` component of the ClassSource record (default Map.of() in the convenience ctor). Add `TypedSpec binding(String prop)` that rethrows the recorded wall for deferred props and route all scalar READ sites through it, so a query that really reads `type` still fails identically. Every WHOLE-MAP iteration site that builds an output row (CastNav.java:98, StoreResolver.java:808, GraphEmission.java:125) must raise when deferredWalls is non-empty — dropping a column there is wrong rows, not a wall. Propagate deferredWalls through the ClassSource rebuilds that copy sub.bindings(). Beyond the wall each test needs downstream spellings (group-by-alias, size()->count(), M2M VARCHAR(8192)) that may surface as separate divergences.

**Leverage** — Best ratio in the bucket: one lazy-binding change unblocks three tests and matches a core tenet. Risk is the silent-omission variant, which the loud iteration sites prevent.

**Shares code with** — Touches resolver/ClassSources.java + ClassSource record and read sites across StoreResolver/GraphEmission/CastNav — any other bucket blaming ClassSources composition or M2M binding resolution should merge here.

---

### 22. n-ary TDS concatenate: collection right-hand side never folded into a TypedConcatenate chain

**1 test** · effort **S** · confidence high · bucket 08 (?) · verdicts: MISSING_FEATURE 1

Tests: `testMultiConcatenate`

**Mechanism** — `p1->concatenate([p2, p3, p4])` has no matching catalog signature — Pure.java:1167-1168 has only `collection::concatenate<T>(T[*],T[*])` and `relation::concatenate<T>(Relation<T>[1],Relation<T>[1])`, no `meta::pure::tds::concatenate(TabularDataSet[1], TabularDataSet[*])`. The 3-element collection therefore binds the COLLECTION overload with T = the TDS relation type, so ConcatenateChecker's guard `if (!(a.out().type() instanceof Type.RelationType)) return emitCall(...)` does not fire and it builds `new TypedConcatenate(arg0, arg1, out)` with a TypedCollection of three relations as the RIGHT child (ConcatenateChecker.java:17-26). Lowerer.union/collectBranches recurses only on TypedConcatenate and otherwise calls relation(spec) (Lowerer.java:533-547), which hits the frontier default at Lowerer.java:524 and throws 'lowering not yet implemented for TypedCollection'.

**Owning code** — core/src/main/java/com/legend/compiler/spec/ConcatenateChecker.java:17-26; supporting: core/src/main/java/com/legend/lowering/Lowerer.java:524 and :533-547; catalog gap at core/src/main/java/com/legend/builtin/Pure.java:1167-1168; existing fold precedent at core/src/main/java/com/legend/StatementExecutor.java:2164-2170

**Fix** — In ConcatenateChecker.check, fold a relation-typed TypedCollection right-hand side into a LEFT-ASSOCIATIVE TypedConcatenate chain, mirroring the fold already used at StatementExecutor.java:2164-2170: when `a.args().get(1)` is a non-empty TypedCollection whose elements are ALL RelationType, start from `a.args().get(0)` and wrap each element in turn, stamping the folded result with multiplicity ONE (`ExprType.one(a.out().type())`) because the engine's n-ary TDS concatenate returns TabularDataSet[1] (tds.pure:480-496), so `$result.values->toOne()` behaves as in the passing testSimpleConcatenate. Guard on ALL elements being relation-typed so a genuine scalar-collection concatenate (Scalars.java:1630-1637) is never captured. Lowerer.collectBranches then flattens the nested chain into the single 4-branch SqlUnion the golden expects. Prefer this over adding `case TypedCollection tc -> tc.elements().forEach(...)` to collectBranches: the checker fix keeps the typed tree well-formed (both TypedConcatenate children are relations) for the resolver passes in Pipelines that also walk TypedConcatenate (Pipelines.java:602, 953, 988).

**Leverage** — Highest leverage in the bucket: S effort, self-contained, one file, an exact-semantics fold with an in-repo precedent, and it closes a real catalog/shape gap rather than asserting engine internals. Only one test in this bucket, but the n-ary TDS concatenate form is common in tds/tests so other buckets may carry siblings. Do this one first.

**Shares code with** — lowering/Lowerer.java:524 is the generic frontier default — its message text ('lowering not yet implemented for <ClassName>') is shared by many buckets, so match on the CLASS NAME in the message, not the line: a TypedCollection here vs a TypedNativeCall elsewhere are different clusters. lowering/Lowerer.java:533-547 (union/collectBranches) and builtin/Pure.java:1167-1168 (concatenate catalog) are the specific surfaces; any bucket reporting 'lowering not yet implemented for TypedCollection' in relation position should merge here.

---

### 23. Post-processor hook channel: recognizer arm for cteExtraction + rename-map→rewrite-list generalization

**1 test** · effort **S** · confidence high · bucket R (newly-honest walls) · verdicts: MISSING_FEATURE 1

Tests: `testNoSubQueries`

**Mechanism** — SqlPostProcessors.readHook only has one arm — a terminal 2-arg meta::relational::postProcessor::replaceTables call — and throws NotImplementedException for anything else (SqlPostProcessors.java:89-100). Since 6ddae338 removed the catch-and-skip, the plain sqlQueryPostProcessors slot calls readHook unguarded (SqlPostProcessors.java:61-76), and StatementExecutor invokes tableReplaceMap for every execute() with >=3 args (StatementExecutor.java:2214-2221), so all 7 cteExtraction tests die at setup before any lowering runs. The channel itself is the blocker: it can only carry a Map<String,String> table-rename, so a hook that is a rewrite rather than a rename has nowhere to live even once recognized. For testNoSubQueries specifically the extraction is provably a no-op (engine short-circuits at subQueryTotalCount == 0, cteExtractionPostProcessor.pure:52-53; golden at .pure:150 has no WITH clause), so recognition alone is the entire fix. Separately, the throw's message hardcodes the slot name sqlQueryPostProcessorsConnectionAware (SqlPostProcessors.java:55, :74) while all 7 hooks come from the PLAIN slot — a wrong-name diagnostic that actively misdirected this investigation.

**Owning code** — core/src/main/java/com/legend/lowering/SqlPostProcessors.java:89-100 (readHook, the refusing recognizer); :43 (tableReplaceMap(TypedSpec) signature); :55 and :74 (the two call sites that must pass the slot name); :61-76 (unguarded plain-slot path); core/src/main/java/com/legend/StatementExecutor.java:58 (ExecEnv Map<String,String> tableReplace), :2214-2221 (setup call), :3206-3208 (exec application site), :390-392 (toSQLString application site); core/src/main/java/com/legend/lowering/PostProcessBoundary.java:27 (record)

**Fix** — Generalize the hook channel and add one recognizer arm. (1) tableReplaceMap(TypedSpec) at SqlPostProcessors.java:43 becomes List<UnaryOperator<SqlQuery>> hooks(TypedSpec); arm A yields q -> apply(q, map) from the existing replaceTables pattern; arm B recognizes body = TypedNewInstance of meta::pure::mapping::Result whose sole property `values` is a TypedNativeCall to meta::relational::postProcessor::cteExtraction::extractSubqueriesAsCTEs applied to the lambda's own parameter, and yields CteExtraction::extract. (2) ExecEnv.tableReplace (StatementExecutor.java:58) and PostProcessBoundary.record (PostProcessBoundary.java:27) carry the list; the two application sites (StatementExecutor.java:3206-3208 and :390-392) fold it over the plan in declaration order — mirroring the engine's generic fold at postProcessor.pure:71-72. (3) KEEP the throw for unrecognized shapes but thread the actual slot name in from SqlPostProcessors.java:55 and :74. With arm B present and CteExtraction.extract returning its argument unchanged when no SqlSource.Subselect is in the FROM tree, testNoSubQueries passes byte-exact with zero IR change.

**Leverage** — Highest leverage item in the bucket. S effort, and it is a hard prerequisite for all 6 tests in the other cluster — until the channel carries a rewrite instead of a rename map, no amount of CTE work is reachable. It also fixes a diagnostic that names the wrong slot for every hook in this corpus, which cost this investigation real time, and it makes every future hook shape an additive arm rather than a channel redesign. Do this first and independently of the pass. Only caveat: if the falsifier fires (identity rewrite yields text that does not byte-match cteExtractionPostProcessor.pure:150), testNoSubQueries is masking a second unrelated text divergence and stops being S — but the channel work still stands.

**Shares code with** — Touches StatementExecutor.java in four places (:58 ExecEnv field, :2214-2221 setup, :3206-3208 exec, :390-392 toSQLString) plus PostProcessBoundary.java:27 — any other bucket blaming StatementExecutor's post-processor plumbing, ExecEnv shape, or the toSQLString-vs-exec divergence should merge here, since this cluster changes the type carried through all four sites. Also owns the NotImplementedException text emitted from SqlPostProcessors.readHook, so any bucket whose failure message is 'sqlQueryPostProcessorsConnectionAware ...' is really this cluster's wrong-slot-name bug and may be misfiled.

---

### 24. groupByWithWindowSubset has no desugar

**1 test** · effort **S** · confidence medium · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testGroupByWithWindowSubset`

**Mechanism** — groupByWithWindowSubset is defined in legend-engine-core's tds.pure (outside the corpus root) and additionally intercepted by a dedicated relational processor. legend-lite has neither: no CoreFn entry (CoreFn.java:73 owns only 'groupBy') and no native. The call carries lambdas so it takes checkWithDeferred, functionCandidates returns empty, and Typer.java:1511-1518 emits the '(no candidates at all)' branch — proving zero registration rather than a shape mismatch. Everything the desugar needs already exists: GroupByChecker.legacyToModern (GroupByChecker.java:63-104) already handles the arity-4 legacy form, and the sibling contractmoneyscenario tests using the same qualifier lambdas and mapping already pass.

**Owning code** — core/src/main/java/com/legend/compiler/spec/CoreFn.java:73 (add the alias); core/src/main/java/com/legend/compiler/spec/GroupByChecker.java:44-51 (new arity-6 branch) and :63-104 (the existing desugar target)

**Fix** — A pure G-phase desugar mirroring the RELATIONAL processObjectGroupByWithWindowSubSet — no native, no lowering change: alias groupByWithWindowSubset onto CoreFn.GROUP_BY, branch on arity 6 at the top of GroupByChecker.check, and rewrite to the arity-4 legacy form via index-mapping (newFunctions = subSelectIds mapped through allIds.indexOf; newAggs = subAggIds mapped through allIds.indexOf minus functions.size(); new ids = subSelectIds ++ subAggIds), enforcing the engine's three preconditions with its own message text. Follow pureToSQLQuery.pure:879, NOT tds.pure:867 — the tds.pure body re-sorts subset functions into ORIGINAL order while labelling them in subSelectIds order, which would mislabel results3's Rate/Amount columns; the relational processor's index-mapped order is what the goldens assert.

**Leverage** — Single test, and explicitly the only relational-corpus user of the function — no downstream unblocks. Value is that it is genuinely small and low-risk (the desugar target already exists and its siblings pass), so it is a clean win if the bucket is being burned down for count. Two cautions: aliasing onto CoreFn.GROUP_BY makes GroupByChecker own the name for ALL arities, so the 6-arity branch must be explicit and everything else must fall through to checkGeneric; and the H2-specific date rendering in the later asserts may leave a GOLDEN_TEXT_ONLY residue that must be reclassified, not smoothed over in the text comparator.

**Shares code with** — CoreFn.java + GroupByChecker.java are shared with any bucket reporting legacy-TDS groupBy desugar issues (including the mangled-groupBy-id cluster below, which exists precisely BECAUSE legend-lite carries no standalone TDS groupBy overload). If another bucket proposes registering a standalone meta::pure::tds::groupBy native, that would collide with this desugar-at-call-site design — reconcile before either lands.

---

### 25. dayOfWeekNumber 2-arg overload missing from the Pure surface (lowering already exists)

**1 test** · effort **S** · confidence high · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testDayOfWeekNumberFunction`

**Mechanism** — Only the 1-arg meta::pure::functions::date::dayOfWeekNumber(Date[1]) is registered (Pure.java:1193). The test calls the 2-arg form with DayOfWeek.Monday/Sunday, which in the engine is a separate Pure-defined overload with a constraint (dayOfWeekNumber.pure:15-24). With no arity-2 candidate the arity filter empties and InferenceKernel.java:786-790 throws 'accepts 2 argument(s)'. Notably the LOWERING half is already written and correct: RelOpTranslator.java:231-261 handles the 2-arg dyna spelling and desugars Monday to isodow and Sunday to mod(isodow,7)+1, and Scalars.java:400-408 lowers the 1-arg native to EXTRACT('isodow', …).

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1193 (+ the DayOfWeek enum already at :692); core/src/main/java/com/legend/lowering/Scalars.java:400-408, with the arity-selective registration helper at Pure.java:965-973 and the precedent at Scalars.java:483-491

**Fix** — Register the 2-arg signature, then split the Scalars registration by arity: narrow the existing EXTRACT-isodow rule to nativeKeysAt("dayOfWeekNumber", 1) and add an arity-2 loop that reads enumName(args[1]) and emits EXTRACT('isodow', …) for Monday, plus(mod(EXTRACT('isodow', …),7),1) for Sunday, and a NotImplementedException naming the engine's own firstDayMondayOrSundayOnly constraint otherwise. Factor the desugar into one helper shared with RelOpTranslator.java:240-261 rather than duplicating byte-identical logic.

**Leverage** — Small and self-contained, one test, no other corpus user — but unusually likely to actually go green because the semantic half is already written and verified against the golden CSV row values (mod(isodow,7)+1 gives Monday->2, Wednesday->4, Thursday->5, Friday->6). Golden SQL will differ from H2's extract(dow from …), which is advisory. One real hazard to check, not paper over: the test's filter compares an Integer to the STRING '2', so verify legend-lite's equal lowering coerces the way DuckDB does or the filter drops the wrong rows — that would be a separate defect to file.

**Shares code with** — builtin/Pure.java signature plus lowering/Scalars.java rule registration — mergeable with any other bucket's 'missing overload arity + arity-blind Scalars rule' pair (the mostRecentDayOfWeek/previousDayOfWeek pattern at Scalars.java:483-491 is the shared template). If another bucket blames Scalars.java for date-extract rendering, coordinate so the arity split happens once.

---

### 26. Typer.classReference's mangled-signature handling is unreachable when zero candidates match

**1 test** · effort **S** · confidence high · bucket 09 (?) · verdicts: REAL_DEFECT 1

Tests: `testToSQLStringWithAggregation`

**Mechanism** — classReference gates ALL mangled-id handling behind `if (fns.size() > 1)` (Typer.java:2246-2270), so the deliberate zero-candidate fallback inside that block is dead code. For the id meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_, functionCandidates demangles to base groupBy, arity 3, return 'TabularDataSet' and then requires the candidate's returnType().typeName() to end with 'TabularDataSet' (Typer.java:2143-2162) — but legend-lite's two meta::pure::tds::groupBy overloads return Relation<Z+R> (Pure.java:1350-1351, rendered 'Relation<…>' by Type.java:299-315). So fns is EMPTY, empty is not > 1, the block is skipped, and the method falls through to the ResolutionException. The code's own comment at Typer.java:2260-2266 describes exactly this case — the intent is right, the guard is wrong. legend-lite has no standalone TDS-era groupBy because GroupByChecker desugars that spelling at call sites, which is a legitimate design choice; the reference is metadata and is never invoked.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2246-2270 (the fns.size() > 1 guard) and :2143-2162 (the return-name filter); second wall at core/src/main/java/com/legend/StatementExecutor.java:379-388

**Fix** — Hoist the mangled handling out of the size>1 guard: compute tailArity unconditionally, enter the block when arity >= 0 && fns.size() != 1, keep the existing byArity narrowing, and return the opaque Function<Any> reference ONLY when ctx.findFunction(stripTail(fullPath)) is non-empty. That base-exists guard is the entire safety property — a genuinely misspelled or absent function id still fails the base lookup and still throws loudly; do not drop it.

**Leverage** — Modest. It advances two tests' walls (testToSQLStringWithAbs fails on the identical message) but makes neither pass: runTestCaseById reads $testCase.query/$testCase.mapping off a class instance, while StatementExecutor.toSqlString requires a TypedLambda literal and a TypedPackageableRef (StatementExecutor.java:379-388), so a much larger constant-folding job (instance construction + filter + toOne) stands behind it. Worth doing only as a correctness fix in its own right — the dead fallback is a latent bug for any mangled id naming a real function this platform spells differently. Small honest cost: a mangled id with a WRONG signature tail becomes silent at the reference site (still loud at any invocation site).

**Shares code with** — Typer.classReference (Typer.java:2246-2296) also emits the generic "'X' is not a known class, mapping, runtime, connection, or database" wall that the tds/relation engine-internals tests hit — any bucket with that message should be checked here first, since a mangled-id case is a real bug while a bare-unknown-name case is corpus scope. Also depends on GroupByChecker's desugar-at-call-site design (see the groupByWithWindowSubset cluster) — if any bucket proposes a standalone TDS groupBy native, this fix becomes unnecessary.

---

### 27. ScanRelations: no branch for a join condition that references no columns (cross join)

**1 test** · effort **S** · confidence high · bucket 10 (harness SHAPE) · verdicts: REAL_DEFECT 1

Tests: `testTableToTdsWithCrossJoin`

**Mechanism** — attachTdsJoin's first act requires the condition lambda body to be an `equal` AppliedFunction with two parameters; a `{a,b| true}` body is a CBoolean, so ScanRelations.java:337-343 throws `NotImplementedException("scanRelations: tableToTDS join condition beyond a single equality pending")`. The arity guard above it (lines 306-311) passes, because the lambda IS 2-param with a non-empty body. Everything else the test needs already works — keepAll on the bare tableToTDS source, the project narrowing on the right side, and the early return at line 245 that keeps the stray string literals from shrinking personTable — so this single missing branch is the whole failure.

**Owning code** — core/src/main/java/com/legend/lineage/ScanRelations.java:337-343 (the throw), :296-315 (parseTdsJoinChain, must return its left Node), :386-387 (child key), :845-857 (print renders labelOverride verbatim)

**Fix** — Thread the current LEFT node out of parseTdsJoinChain (change it from void to Node-returning; the only caller at line 242 ignores the result today, so nothing else moves), then replace the unconditional throw at 337-343 with a cross-join branch taken only when the condition contains NO tds column read: set right.labelOverride = "tdsJoin", attach right under left, leave right.cond null, return. Gate on a real recursive `containsTdsColRead(body)` helper built on the existing tdsColRead predicate (line 391) — NOT on `body instanceof CBoolean`, or `{a,b| 1 == 1}` still walls while a genuinely unsupported column condition could slip through as a silent cross join (a plausible-looking lie is worse than a wall). Keep the throw for a condition that DOES read columns but is not a single equality (multi-key and(eq,eq) joins).

**Leverage** — High for its size. One S-effort branch, one green, and the golden it satisfies is a real tree shape. The 'tdsJoin' label it emits is directly warranted by the engine (scanRelations.pure:625-632 takes the empty-updatedAliases branch and never renames the join, leaving pureToSQLQuery's literal 'tdsJoin' from pureToSQLQuery.pure:6755/6768), so this is faithful, not curve-fitting. Deliberately kept OUT of the union cluster above: that one changes parseTdsSource/alias plumbing, this one changes the condition parser — neither change fixes the other test.

**Shares code with** — Same file as the union cluster and as the XL mapping-driven cluster; all three are edits to com.legend.lineage.ScanRelations and should be sequenced together (this one first — it is the smallest and touches attachTdsJoin, which the union cluster also rewrites, so landing them independently guarantees a merge conflict in attachTdsJoin).

---

### 28. Harness has no statement-position arm for an assert loop over already-materialised values

**1 test** · effort **S** · confidence high · bucket 10 (harness SHAPE) · verdicts: HARNESS_GAP 1

Tests: `testComplexOrExistsToManyProperty`

**Mechanism** — `$result.values.legalName->map(f | assert([...]->contains($f)))` is an ASSERTION LOOP over values execute() already materialised, not a query — but no router arm claims it. enumDriverLoop deliberately refuses it (its comment names this exact test as the misfire it was narrowed to exclude, EngineTestExecutor.java:2226-2235), resultVarLoop needs a PureCollection of Variables (line 2190-2192), alloyFallback does not apply. So it falls into the K-natives arc (lines 519-535), gets wrapped with the exec statements because it references $result, and is pushed through the whole compile pipeline; StoreResolver's TypedMap arm only fires for a RelationType source (StoreResolver.java:466-471), so control reaches the named default throw at StoreResolver.java:505-510 and EngineTestExecutor reports `statement 'map' failed through the pipeline: ...` (line 553-558). The wall is honest; the routing decision that reached it is the defect.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:519 (insertion point, immediately before the K-natives arc), :2226-2235 (enumDriverLoop's deliberate exclusion — do NOT widen), :553-558 (the reporting site); core/src/main/java/com/legend/resolver/StoreResolver.java:505-510 (the wall actually hit)

**Fix** — Add an ASSERT-LOOP arm to EngineTestExecutor's main statement loop just before the K-natives arc: match an AppliedFunction named `map` with 2 parameters whose second is a 1-param LambdaFunction and whose body statements are ALL harness assert calls (harnessVocabName(f) && simpleName(f).startsWith("assert")). On match, evaluate the map SOURCE host-side with the existing eval(subst(src, lets), ...) call that assertSameElements already uses, lift each Eval value to a literal ValueSpecification (CString/CInteger/CFloat/CBoolean), and push the substituted assert statements onto the front of the worklist so they score through the normal checkAssert/scoreAssert path. If any element is not liftable, return null and fall through to today's loud wall — never skip an element. The all-statements-are-asserts gate is the discriminator that keeps a genuinely query-shaped map out; do not widen enumDriverLoop instead.

**Leverage** — Modest but clean: one green for an S-effort arm, plus a small regression-guard benefit (testSubtypeMapping.pure:56 is the only other test-level use of this idiom in the whole core_relational corpus and is not currently failing). The value is really in the honesty property — the arm must wall on a non-liftable element rather than skip it, otherwise it converts a real failure into a vacuous pass, which is the failure mode this router has already suffered once.

**Shares code with** — Two things here are shared surface. (1) StoreResolver.java:505-510's `class query under <X> is not resolvable yet (H2 vocabulary)` is a catch-all default — any other bucket reporting that exact message is reporting a ROUTING decision upstream, not a StoreResolver gap, and should be triaged the same way rather than by adding StoreResolver arms. (2) The edit lands in the same ~40 lines of EngineTestExecutor's statement router as the enumDriverLoop let-deref cluster below; schedule the two together to avoid conflicting edits, even though neither fix resolves the other's test.

---

### 29. Engine-style alias plan computed over the post-processed tree instead of the pre-post-processor IR

**1 test** · effort **S** · confidence high · bucket 04 (?) · verdicts: GOLDEN_TEXT_ONLY 1

Tests: `testReplaceTablePostProcessorWithExists`

**Mechanism** — SqlPostProcessors.source() rewrites the physical table NAME and preserves the IR alias, but SqlSource.Table carries no alias-group identity. EngineStyleH2.render then runs planQuery/planSource over the already-renamed tree, and planSource's Table arm derives the group from t.name(), so the group becomes differentpersontable instead of persontable (and firstInnerTable keys the exists-subselect the same way). The engine computes the alias map in reAlias::replaceAliasName as part of sqlQueryDefaultPostProcessors, which is folded BEFORE the connection-aware processors — hence the golden keeps persontable_0/_1 over a differentPersonTable source. Everything else in the text is byte-identical.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:219-231 (render), :326-346 (planSource Table arm), :415-428 (firstInnerTable); core/src/main/java/com/legend/lowering/SqlPostProcessors.java:220-227; core/src/main/java/com/legend/sql/SqlSource.java:44; callers StatementExecutor.java:388-395 and :470-478

**Fix** — Give EngineStyleH2 a two-tree entry point render(SqlQuery toRender, SqlQuery aliasPlanSource) that applies wrapTdsJoinTop to both, plans on aliasPlanSource and renders toRender, with the one-arg render(q) delegating render(q, q); then change the two callers that rename before rendering (StatementExecutor.java:388-395 and the engineSql path at :470-478) to capture the pre-SqlPostProcessors plan and pass it as the alias source. Sound because source() preserves t.alias()/sub.alias() verbatim, so an alias-keyed plan built on the pre-rename tree applies unchanged. Alternative if the property should live on the data: add a nullable aliasGroup to SqlSource.Table carried through the rename and read by planSource/firstInnerTable.

**Leverage** — Good: S effort, and the diagnosis names three further beneficiaries (testReplaceTablesPostProcessor's SQL-text half, testToSqlStringReplaceTablesPostProcessor once its harness gate opens, the alias half of testReplaceTablePostProcessorWithView). It is also the only alias-related cluster in this bucket that is genuinely cheap — do not confuse it with the raw-alias ledger, which is a different mechanism entirely (this one is about WHEN the reAlias plan runs, not whether it runs).

**Shares code with** — sql/dialect/EngineStyleH2.java render/planSource/firstInnerTable and lowering/SqlPostProcessors.java:220-247. Any bucket where a table-replace post-processor is in play and only the alias GROUP NAME differs (renamed-table-derived alias vs original-table-derived alias) is this cluster — merge. Distinct from the raw-alias ledger.

---

### 30. Zero-candidate mangled function reference throws instead of typing as an opaque Function

**1 test** · effort **S** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testToSQLStringWithAbs`

**Mechanism** — Typer.classReference's escape hatch for engine signature-mangled function references sits inside `if (fns.size() > 1)`. For [meta::pure::tds::groupBy_TabularDataSet_1__String_MANY__AggregateValue_MANY__TabularDataSet_1_], functionCandidates demangles to base name + arity 3 + return-name 'TabularDataSet' and filters on returnType().typeName().endsWith(ret); legend-lite's two groupBy natives return Relation<Z+R>, so the filter empties the list, the escape hatch is unreachable, and control falls to the ResolutionException. The reference is pure documentation metadata (generateUsageFor) that is never evaluated.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2148-2160 (functionCandidates demangle + return-name filter), :2248-2270 (the fns.size() > 1 escape hatch), :2293-2296 (the throw); core/src/main/java/com/legend/compiler/spec/SignatureMangle.java:44-58; next wall at core/src/main/java/com/legend/StatementExecutor.java:380-383

**Fix** — Before the fns.size() > 1 branch add a zero-candidate arm: if fns.isEmpty() AND the id has a mangled tail AND ctx.findFunction(stripped base name) is non-empty, return the same opaque TypedPackageableRef typed Function<Any>[1] the existing branch returns. Keep functionCandidates strict so eta-expansion stays exact; refactor both branches to share one opaque-ref helper. The base-name-known guard is what keeps this from masking typos — an unresolvable id whose base name does not exist still throws, and an opaque ref that is actually invoked still walls at its call site.

**Leverage** — S effort and it removes a hard ERROR, but it does NOT green this test: the very next statement calls toSQLString with $testCase.query, a property read off a ^TestCase, which StatementExecutor rejects because it is not a lambda literal. Closing that needs host-side folding of the ^TestCase collection -> filter -> toOne -> property read down to the literal lambda, a separate larger item. Real value is that the identical reference kills testToSQLStringWithAggregation in bucket 9 from the same ^TestCase list, and any other corpus element carrying generateUsageFor-style mangled metadata.

**Shares code with** — compiler/spec/Typer.java:2148-2296 classReference/functionCandidates. testToSQLStringWithAggregation (bucket 9) fails with the identical message from the identical reference — merge those two into one cluster across buckets. Typer.java is also touched by the enumValues cluster, different method.

---

### 31. Golden side of an SQL-text assert is never evaluated when it is not a literal

**1 test** · effort **S** · confidence high · bucket 04 (?) · verdicts: HARNESS_GAP 1

Tests: `testBuildFilterWithValueThatCanBeNullPlanSql`

**Mechanism** — The golden side of the assert is a call to a corpus-private String function, not a literal. EngineTestExecutor.sqlTextVerify extracts the golden with TestDataGenForm.foldString, which folds only CString and plus-of-foldables and returns null for anything else. With golden == null the no-golden branch finds no execute/toSQLString terminal in the helper call and returns null too, so control reaches h2Upgrade, which early-outs with 'no foldable golden string' and returns the advisory marker. verified stays 0 and Runner scores SHAPE. Nothing in the platform is wrong — the harness simply never evaluates the golden expression.

**Owning code** — core/src/main/java/com/legend/harness/TestDataGenForm.java:477-494 (foldString); core/src/main/java/com/legend/harness/EngineTestExecutor.java:958-966 (the golden/actual split loop), the identical loop inside h2Upgrade at ~:1053-1061, and the decline at :1062-1068; core/src/test/java/com/legend/rcorpus/Runner.java:1456-1465

**Fix** — Factor one goldenStringOf(a, ...) helper called from BOTH split loops (so they stay in step) that first tries foldString and, if that is null AND !containsSqlText(a), falls back to the platform evaluator already in scope: evalScalar(a, lets, execStmts, execVars, execChains, ctx, imports, runtimeFqn, conn) instanceof String s ? s : null. The !containsSqlText guard is load-bearing — it keeps the $result->sqlRemoveFormatting() side out of the golden slot. Keep the call inside the existing try/catch that declines with 'replay/verify failed' so a non-evaluable golden degrades to today's advisory rather than a spurious ERROR. Do NOT teach foldString to inline user function bodies — that would re-implement the platform's inliner inside the harness.

**Leverage** — One test, S effort, and it is a clean win: the harness IS the Pure test-body evaluator and here it is delegating to the platform's own evaluator rather than hand-rolling a value, so it is not compensation. After the change the test resolves honestly in either direction (identical text -> H2 row check; divergent text -> row verification, and only if unverifiable a real sql-text diff). The diagnosis is explicit that the other four advisory-golden tests in the sweep have DIFFERENT causes, so do not expect fan-out — testViewChainsWithBusinessDate declines for a different reason (no root exec variable).

**Shares code with** — harness/EngineTestExecutor.java:958-966 / :1053-1068 and harness/TestDataGenForm.java:477-494. Any bucket reporting SHAPE 'sql-only: N advisory golden-SQL assert(s), no row verification' should be checked against this specific decline reason ('no foldable golden string') before being merged — the same wall text is emitted for at least four distinct upstream causes across this bucket alone.

---

### 32. Plan model has no sqlComment channel

**1 test** · effort **S** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testSQLCommentsInPlan`

**Mechanism** — The typer no longer walls (commit 787c391b added sqlComment: String[0..1] to native SQLExecutionNode), so the current failure is `expected -- "executionTraceID" : "${execID}", got []`. PlanNode is a record of (kind, children, sqlQuery, functionParameters) with no sqlComment component, and StatementExecutor.walkProp's PlanNode arm answers only rootExecutionNode/executionNodes/sqlQuery/functionParameters with `default -> null`. Because the receiver is a LIST (cast over filter), the list arm drops the nulls and yields an empty collection. In the engine the value is a CONSTANT: the default post-processor stamps every SelectSQLQuery with that comment and generateSQLExecutionNode copies it.

**Owning code** — plan/PlanNode.java:20-21; StatementExecutor.java:1834-1845 (walkProp), :1919-1927 (walkResult), :2012 (SQL node construction)

**Fix** — Three edits. (1) plan/PlanNode.java — add a @Nullable String sqlComment component after sqlQuery (compact constructor unchanged); hoist the literal to PlanNode.EXEC_TRACE_COMMENT and cite defaultPostProcessor.pure:71 for why it is constant. (2) StatementExecutor.java:2012 — construct the SQL node with that constant. (3) StatementExecutor.walkProp:1835-1844 — add `case "sqlComment" -> pn.sqlComment();`. Leave PlanText untouched: the engine printer does not print sqlComment and PlanText.single emits type/resultColumns/sql/connection only, so every existing plan-text golden stays byte-identical. Do not answer sqlComment inside PlanAsserts — the plan model owns the value. Do not gate the constant on a flag.

**Leverage** — Cheap and genuinely correct: the plan value model is missing a field the engine always sets. Single test, but zero golden-regression surface.

**Shares code with** — StatementExecutor.walkProp/walkResult (:1834-1927) is the shared plan-property reader; any bucket whose failure is `got []` from a plan walk lands in the same switch.

---

### 33. Cell-vs-row index over `$tds.rows.values` (whole-relation receiver)

**1 test** · effort **S** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testFilterOnEnum`

**Mechanism** — `$result.values.rows.values->at(1)` must read CELL 1 in row-major order (TDSRow.values is Any[*] in column order; property access over a collection auto-flattens). legend-lite types `.rows` as an identity marker and `.values` over that marker as identity (cells are only enumerated when the receiver is a TypedVariable row binder), so the whole chain types as the relation and `at(rel,1)` reaches Lowerer.relation(), which rewrites it as TypedSlice(1,2) = one ROW. Eval flattens that row to [New York, CITY]. The existing tdsRowCellIndexRead machinery is gated on a single-row PICK receiver (ROW_PICK_FQNS), so it returns null here.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:958-990 (and 2429-2463 `.values` identity arm); lowering/Lowerer.java:292-310 (at→TypedSlice)

**Fix** — Add a second arm to Typer.tdsRowCellIndexRead for the whole-relation receiver: when synth of the receiver yields a RelationType and the receiver is the `.rows` marker (TypedPropertyAccess) rather than a row pick, compute C = columns().size(), r = k/C, c = k%C and rewrite `at(k)` to `toOne(at(<receiver>, r).<columns[c].name()>)` — the exact route commit 9d1f2cd0 already proved for the sibling spelling. Bound only c; let a bad r yield empty. Return null for `size()` on this receiver (rows*cols is not statically known) rather than lying. Do not touch the bare `.values` flatten — whole-grid list compares and EngineTestExecutor.isFlatCellsRead depend on the identity typing.

**Leverage** — High: wrong-value correctness bug in core TDS semantics, small edit reusing a proven rewrite. Sibling spelling already landed upstream, so this closes the family.

**Shares code with** — compiler/spec/Typer.java `.values`/`.rows` marker typing and lowering/Lowerer.java at→slice rewrite — any bucket blaming TDS cell indexing or the relation `.values` identity arm should merge here.

---

### 34. assertSize over the peeled Result envelope misses the `->at(0)` spelling

**1 test** · effort **S** · confidence high · bucket 6 (wrong rows) · verdicts: HARNESS_GAP 1

Tests: `testTwoQualifiersUsingSameJoinWithNoUserParams`

**Mechanism** — `assertSize($result.values->at(0), 1)` asserts the envelope holds one TDS carrier — a TDS is one object, not its rows. The platform deliberately erases that envelope (the splice hook collapses `$r.values->at(0)` on a relation-rooted frame to the relation), so the arity can only be answered at the assert boundary. The harness has that rule (carrierSizeCheck) but gates it on the BARE `$result.values` spelling (AppliedProperty over an exec-frame Variable). The `->at(0)` form is an AppliedFunction, misses the gate, falls to the generic Eval path, and Eval.size() over a Tabular returns rows().size() = 4. Nothing about the query, SQL, or rows is wrong.

**Owning code** — EngineTestExecutor.java:1944 (assertSize arm); peel walk already at :186-197; platform parallel at StatementExecutor.java:2536-2546

**Fix** — In the assertSize arm, before the generic Eval path, peel `at(_,0)` / `toOne()` / `first()` wrappers off args.get(0) using the same walk recordExecChain does, and if what remains is the bare `$<execVar>.values` on an exec-frame variable AND no `.rows` property was traversed, route to carrierSizeCheck with the ORIGINAL arg. Gate strictly on 'no .rows traversed' so `$result.values.rows->at(0)` — a real row pick — is never folded to 1. Do not make Eval.size() over a Tabular return 1 in general, and do not weaken the platform's envelope erasure. Note the test then proceeds to a second assert over `$result.values->toOne().rows.values`; verify it rather than assume.

**Leverage** — Harness gap, one-spelling extension of an existing documented adapter. Low value beyond unblocking this test, but cheap and the sibling passes only by luck (1-row TDS).

**Shares code with** — EngineTestExecutor assert-arm spelling gates: other buckets reporting 'adapter exists but misses a spelling' can share the peel helper at :186-197.

---

### 35. IN over a collection-valued expression collapses to '=' (wrong rows)

**1 test** · effort **S** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testFilterInWithResultSorcedFromAnExpression`

**Mechanism** — lowering builds Call(SqlFn.IN, [needle, collectionExpr]) for `$x->in($collectionExpr)`, and EngineStyleH2 collapses a 2-arg IN to equality on ARITY ALONE (the rule is meant for a singleton literal list). With `$y->split(',')` as the right side the emitted predicate is `where "root".LEGALNAME = string_split(...)` — a predicate that can never be true. The collection-vs-scalar distinction is lost at lowering, so the renderer has nothing to test.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1156-1161; core/src/main/java/com/legend/lowering/Scalars.java:2273-2287; core/src/main/java/com/legend/sql/SqlExpr.java:249-256 (existing Membership node)

**Fix** — Stop losing the distinction at lowering: in Scalars.java:2273-2287 emit SqlExpr.Membership (which already means exactly this) when arg 1 is a collection-valued expression rather than an ArrayLit, and have the renderer spell `x in (<expr>)`. As a narrower stopgap, replace the arity test at EngineStyleH2.java:1156-1161 with a shape test — collapse to `=` only when arg 1 is a literal/column/scalar param — never simply delete the collapse, since goldens legitimately spell `x = v` for a singleton literal list.

**Leverage** — Highest-value change in this bucket: a silent wrong-rows correctness defect in execution, not plan text. Shares its test with the FilterIn cluster but is independently worth landing.

**Shares code with** — lowering/Scalars.java IN construction and its dialect rendering — merge with any bucket blaming IN/membership SQL text or wrong filter rows.

---

### 36. sequencePlan envelope: trailing let never becomes Allocation, Sequence wrapped unconditionally

**1 test** · effort **S** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testExecutionPLanGenerationForFromInAllocation`

**Mechanism** — For a body that is one `let`, sequencePlan's allocation loop runs `for i < body.size() - 1` and is therefore empty, so the let falls into the TERMINAL arm and prints as a bare Relational; the result is then wrapped in PlanText.sequence unconditionally. The engine instead processes a let cluster into an AllocationExecutionNode and emits a Sequence only when clusters != 1. Got is Sequence(Relational(...)) where the golden is Allocation(name=var, value=(Relational(...))). The inner Relational type block, resultColumns and SQL are already byte-identical.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:825-898 (loop :865-888, wrap :891-897); core/src/main/java/com/legend/plan/PlanText.java:138-150

**Fix** — (1) Treat a TRAILING TypedLet as an Allocation: widen the loop bound to cover every statement and, when the last statement is a TypedLet, append allocationNode(...) instead of the PlanText.single terminal — the envelope type block then comes from that node's own result type. (2) Drop the Sequence envelope for a single child (return the child directly before the PlanText.sequence call). Faithful because a parameterized lambda always contributes a FunctionParametersValidationNode as a second child and still gets its Sequence, and cross-store plans build their own Sequence in crossDbTdsPlan. No PlanText change needed; allocation() and typeBlock() already emit the golden bytes.

**Leverage** — Best cost/benefit in the bucket: two small edits in one method, generalizes to every plan golden whose body is a single let.

**Shares code with** — Same StatementExecutor.sequencePlan (:825-898) and PlanText.sequence (:138-150) the processInOperation cluster must also modify — sequence the two so the single-child unwrap and the RelationalBlock arm are written once.

---

### 37. ORDER BY null placement not pinned in the IR

**1 test** · effort **S** · confidence medium · bucket 2 (execution-plan) · verdicts: EXECUTION_TARGET_ARTIFACT 1

Tests: `testSortByLambdaAndGraphFetchDeep`

**Mechanism** — Fold.sortNulls returns null unconditionally, so every top-level sort key lowers with nullOrder == null and AnsiSqlRenderer emits no NULLS clause. DuckDB defaults ASC to NULLS LAST; H2 defaults to NULLS FIRST. Goldens were produced on H2, so a null key sorts first there and last on DuckDB — the observed `$[0].address expected null` diff. This is a half-reverted design: EngineStyleH2.sortKey still computes h2Default and suppresses a nullOrder that merely restates it, dead code today, and H2.java compensates in the wrong direction by appending NULLS LAST whenever nullOrder is null, forcing H2 to imitate DuckDB.

**Owning code** — Fold.java:344-346; AnsiSqlRenderer.java:198-205; EngineStyleH2.java:1229-1241; H2.java:275-281; Lowerer.java:1596/1617/1622/1633

**Fix** — Restore the C1.2 IR pin: Fold.sortNulls returns ascending ? NULLS_FIRST : NULLS_LAST. DuckDB then renders the clause explicitly and matches H2; EngineStyleH2's existing suppression keeps plan/SQL goldens byte-identical; H2.java's nullOrder==null branch stops firing so both targets agree. Before landing, key the suppression off a per-dialect defaultNullOrder(ascending) hook instead of the hard-coded h2Default, since EngineStyleDB2 and EngineStyleComposite inherit it and a differing DB2 default would add a spurious `nulls first` token. Leave aggregate-internal ORDER BY alone (AnsiSqlRenderer.reducer, H2.java:362-375 joinStrings pin) and leave window keys alone (already explicit). Do NOT set default_null_order in the DuckDB session in DuckWorkspaces — that is harness compensation for a dialect decision.

**Leverage** — High per unit of effort: a one-line IR change that fixes real wrong-row ordering, with the absorbing code already written. Plausibly fixes other nullable-ORDER-BY row-order diffs.

**Shares code with** — Fold/Lowerer sort lowering plus the H2 and DuckDB dialect renderers; any bucket blaming row order on a nullable sort key shares this mechanism.

---

### 38. executionPlan let bindings are never evaluated (no executed count)

**1 test** · effort **S** · confidence high · bucket 2 (execution-plan) · verdicts: HARNESS_GAP 1

Tests: `testPersonToFirmUsingProject`

**Mechanism** — The test body has no live assertion — all assertEquals are commented out and it ends in `true;`. Its only substance is that a 2-arg `executionPlan($query, relationalExtensions())` binding generates a plan. Runner.score PASSes on verified==0 && executed>0 and falls to SHAPE "no verifying assertions" only when both are zero, so the failure means executed never incremented. EngineTestExecutor.tdgLetArm gates its executionPlan arm on parameters().size() >= 3, so the 2-arg spelling is skipped and returns consumed=false; the eager-forward branch requires containsExecute/referencesAny, and containsExecute matches only a call literally named `execute` with >=2 params. The binding lands in `lets` and nothing downstream reads it.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:444 (eager-forward rule), :1430-1441, :1480; Runner.java:1466-1475

**Fix** — Extend the eager-forward rule at EngineTestExecutor.java:444 so a substituted let RHS that is an `executionPlan(...)` call of any arity (reuse the simpleName+harnessVocabName test already at :1430-1432) is evaluated once through evalSpliced/eval with executed++, then still recorded in `lets` so later plan asserts substitute unchanged. Any wall from that evaluation must propagate as the test's outcome, never be swallowed. This is the same engine-parity eagerness rule already applied to execute() bindings; plan generation still runs through StatementExecutor.planModel. Do NOT relax Runner.score to treat 0-asserts/0-executed as PASS. Land the XStorePureEnds fix first — alone this change only converts the row to a SHAPE naming the XStoreTradesMapping poison.

**Leverage** — Cheap and honest, but it does not pass without the XStorePureEnds fix, and it will surface previously-silent plan walls in other executionPlan/testDataGeneration tests.

**Shares code with** — Harness-owned scoring rule; any bucket whose tests bind a plan they never read (executionPlan, testDataGeneration families) changes classification when this lands.

---

### 39. TdsEquivalence over-asserts vs the Pure assertTdsEquivalent it implements

**1 test** · effort **S** · confidence high · bucket 6 (wrong rows) · verdicts: HARNESS_GAP 1

Tests: `testDateTimeInclusiveRangeQuery`

**Mechanism** — Both engines return ONE row here — legend-lite's rows are correct. The engine's Pure `assertTdsEquivalent` is a chain of `&&`; a row-count mismatch short-circuits to `false` BEFORE `fail(...)` runs, and the engine's test runners only fail on a thrown exception, so a `<<test.Test>>` returning false PASSES upstream. The golden's second row is dead text copied from a millisecond-precision fixture. legend-lite's TdsEquivalence.compare instead returns "expected N cells, got M" on size mismatch — strictly stronger than the assert the corpus actually makes.

**Owning code** — core/src/main/java/com/legend/harness/TdsEquivalence.java (compare)

**Fix** — Restructure `compare` to take the two TDS shapes (column names + rows) rather than a flattened cell list. Return NO failure (null) when column count, column NAMES, or ROW COUNT differ — that is the engine's non-throwing `false` branch. Only when all three agree does the per-column cell comparison run (existing cell logic is correct). Emit a non-fatal `PASS(assert-vacuous: N vs M rows)` annotation so the divergence stays visible on the scoreboard. Do NOT change DateTime literal rendering to millisecond precision — legend-lite already agrees with engine SQL. Do NOT generalise the relaxation to assertEquals/assertSameElements/assertSize, which do throw.

**Leverage** — Low leverage inside core_relational (assertTdsEquivalent appears twice). Legitimate fidelity fix, but reads like harness compensation — keep the vacuous-assert note or skip it.

**Shares code with** — Harness-only (TdsEquivalence); no overlap with the shared compiler/lowering files, but other buckets citing TdsEquivalence.compare cell logic should not conflict.

---

### 40. TDS column annotated `Date` types as STRICT_DATE, truncating time

**1 test** · effort **S** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testDateTimeRetrieveWithTimeZone`

**Mechanism** — TdsChecker.annotatedType maps `case "Date", "StrictDate" -> STRICT_DATE`. In Pure, `Date` is the abstract supertype and a Date-typed column legitimately holds date-times. So the header `settlementDateTime:Date[1]` with cell `2016-02-05T21:00:00.123+0000` lowers via Scalars.tdsCell's STRICT_DATE arm to `DATE '...'`; DuckDB's lenient parse drops the time, and the expected cell becomes 2016-02-05T00:00 vs actual 21:00:00.123. epochSeconds differ by 75600s against a 1s tolerance. The INFERRED path already returns Type.Primitive.DATE for zone-suffixed timestamps, so annotated directly contradicts inferred.

**Owning code** — core/src/main/java/com/legend/compiler/spec/TdsChecker.java:148 (annotatedType), :208-209 (inferredType, correct); core/src/main/java/com/legend/lowering/Scalars.java:2942-2955 (tdsCell DATE arm)

**Fix** — Split the case at TdsChecker.java:148 into `case "StrictDate" -> STRICT_DATE;` and `case "Date" -> DATE;` (leave `"DateTime" -> DATE_TIME`). Companion change in Scalars.tdsCell: make the Type.Primitive.DATE arm value-polymorphic the way Pure's Date is — if the %-stripped cell matches `\d{4}-\d{2}-\d{2}` with no time component emit SqlExpr.DateLit, else the existing TimestampLit path (+0000/Z stripped, sub-second truncated to 6 digits). Without the companion guard a `col:Date[1]` column holding date-only cells would start rendering `2014-12-04 00:00:00` in toString comparisons. No harness change needed — TdsEquivalence.epochSeconds already handles LocalDateTime.

**Leverage** — Genuine type-mapping defect with a two-line fix and a bounded blast radius (only two `:Date[` headers in core_relational). Good ratio; helps PCT fixtures using the same shape.

**Shares code with** — Companion edit lands in lowering/Scalars.tdsCell, which the union null-vs-empty cluster also touches (different arm: STRING vs DATE) — sequence them to avoid conflicting rewrites of tdsCell.

---

### 41. String[1] property over an INT column is SQL-cast, changing the TDS cell kind

**1 test** · effort **S** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testJoinIsolationDeeper_LeftOuterLeftOuterThenInner`

**Mechanism** — `Account.number` is declared String[1] (tree.pure:260) but mapped to accountTable.id, an INT. MappingNormalizer.coerceColumnToDeclared sees declared="String" vs colKind="Integer" and takes the `"String".equals(declared) || "Boolean".equals(declared)` branch, wrapping the read in castAsDeclared(read, String) — a real SQL cast — so the cell arrives as Java String "11". legend-engine does no such conversion: SetImplTransformers.buildTransformer returns a transformer only for Boolean and StrictDate/DateTime/Date; everything else falls through to Functions.identity(), so raw JDBC Integer reaches the TDS. Hence the corpus asserts Integer 11 and the harness reports 'renders equal, comparison differs: expected types=[Long, String]; got types=[String, String]'.

**Owning code** — core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2448-2452 (CAST_AS_DECLARED branch), block comment :2444-2449

**Fix** — Drop `"String".equals(declared)` from the CAST_AS_DECLARED branch, leaving `if ("Boolean".equals(declared)) return castAsDeclared(read, Boolean);`. A String-declared property over a non-String physical column takes the same treatment the numeric-mismatch case already uses two lines below: emit `Pure.Lite.TYPE_AS_DECLARED` — a type-only assertion, no SQL cast — mirroring engine's Functions.identity(). Restructure to: Boolean -> castAsDeclared; String -> typeAsDeclared; DateTime-over-StrictDate -> cast; numeric<->numeric -> typeAsDeclared; numeric-over-VARCHAR -> parseInteger/parseFloat/parseDecimal (unchanged); everything else uncast. Update the block comment at :2444-2449, which currently asserts the opposite of SetImplTransformers. Do NOT teach the harness's wireEquals to compare Long 11 with String "11".

**Leverage** — Small edit, mapping-wide effect: every String[1] property over a non-VARCHAR column changes cell kind. Likely nets other tree/multigrain tests — and likely flips some that pass on the stringified value.

**Shares code with** — MappingNormalizer.coerceColumnToDeclared is the mapping-wide wire-coercion rule; any bucket blaming TDS cell kind, TYPE_AS_DECLARED, or builtin/Pure.Lite casts is fixed or broken by this same edit. Also a prerequisite for the NavMaterializer cluster below.

---

### 42. Blank #TDS String cell: NULL vs empty string — needs an engine probe

**1 test** · effort **S** · confidence medium · bucket 6 (wrong rows) · verdicts: NEEDS_PROBE 1

Tests: `testUnionTwoRelationMappings_ManyColumnProject`

**Mechanism** — The only difference is the firstName representation: the `#TDS` literal side yields NULL, the DuckDB side yields ''. Both are individually faithful to legend-engine — legend-pure's TDS inline DSL reads the body with a CSV reader configured nullValueLiterals("", "null") so a blank cell IS null, and Scalars.tdsCell reproduces that with SqlExpr.NullLit(); meanwhile the fixture genuinely inserts literal '' into PersonSet1/2.firstName_s*, so '' is the correct actual value. What could not be established statically is how the engine's own assertion passes: relation toString renders an empty cell as the text `null` and '' as nothing, so under this reading the engine's two strings differ too.

**Owning code** — core/src/main/java/com/legend/lowering/Scalars.java:2915-2926 (tdsCell empty-cell disjunct) — only if the probe says (b)

**Fix** — Change nothing until the probe settles it; a blind edit is a coin flip between two engine-faithful behaviours. PROBE: in a real legend-engine/legend-pure runtime evaluate `assertEquals('#TDS\n a,b\n x,\n#', #TDS a,b\n x,\n#->toString())`. (a) Blank renders as the text `null` -> both legend-lite sides are right and the corpus test cannot pass as written; ledger it as upstream-inconsistent. (b) Blank renders as nothing -> the runtime treats a blank String cell as '' despite the CSV config, and the fix is in Scalars.tdsCell: for a STRING-typed column an EMPTY-but-present cell lowers to SqlExpr.StringLit(""), with SQL NULL reserved for explicit `null`/`TDSNull`; the existing cell.isEmpty() disjunct moves under a non-String type guard. Either way do NOT make gridEquals/wireEquals treat null and "" as equal.

**Leverage** — Covers a byte-identical sibling test too, but option (b) flips every #TDS fixture with a blank String cell globally. Do the probe; do not spend fix effort on inference.

**Shares code with** — Option (b) edits lowering/Scalars.tdsCell — the same method the annotated-`Date` cluster changes (DATE arm) and a global TDS-literal semantics change any bucket with #TDS fixtures will feel.

---

### 43. Runtime subtype not accepted by from() — ^EngineRuntime rejected by exact-FQN check

**1 test** · effort **S** · confidence high · bucket 11 (unclassified) · verdicts: REAL_DEFECT 1

Tests: `testSpecialUnion_m2m2r`

**Mechanism** — FromChecker's instance-runtime arm recognises a non-ref argument as a runtime only by EXACT FQN equality against meta::core::runtime::Runtime. `^EngineRuntime(mappings=…, connectionStores=…)` types as ClassType("…::EngineRuntime"), a declared SUBCLASS in legend-lite's own prelude, so the equality fails, the argument falls to the loud throw, and its ModelChainConnection mappings / CSV setup are never harvested. Everything downstream (chainMappings, jsonSources, sqlSetups) already exists inside that arm — this is purely a missing subtype check.

**Owning code** — core/src/main/java/com/legend/compiler/spec/FromChecker.java:53-56; mirror sites core/src/main/java/com/legend/compiler/spec/StatementExecutor.java:3054 and :3080

**Fix** — Replace the exact-FQN test with a subtype-aware one: accept when ct.fqn().equals("meta::core::runtime::Runtime") || t.model().isSubtype(ct.fqn(), "meta::core::runtime::Runtime") — t.model() is the ModelContext already reachable from Typer (same accessor GraphFetchChecker.java:117 uses). Apply the identical widening at the two orchestration-handle arms in StatementExecutor.java:3054 and :3080, which carry the same equality and would otherwise push an ^EngineRuntime value through the SQL pipeline. Keep the effect guard at StatementExecutor.java:3057-3063 intact so a ctor argument carrying an executeInDb effect still walls loudly. Nothing else changes.

**Leverage** — High per unit of work: three-line subtype check, unblocks every corpus test that builds ^EngineRuntime inline (crossStoreGraphFetch, modelJoins, executionPlan). This test additionally needs special_union to work, so it may not go green alone.

**Shares code with** — Widens StatementExecutor.java:3054/:3080 orchestration-handle arms — any bucket blaming StatementExecutor handle dispatch should fold in here.

---

### 44. Singleton-collection sort key rejected — multiplicity rule encoded as AST shape

**1 test** · effort **S** · confidence high · bucket 11 (unclassified) · verdicts: REAL_DEFECT 1

Tests: `testTableToTDSWithQuotes`

**Mechanism** — The test spells `->sort([desc(['FIRST NAME'])])`. In Pure a one-element collection collapses to String[1] and binds meta::pure::tds::desc(String[1]). legend-lite registers only the ColSpec overload and compensates for the string form with a SYNTACTIC desugar in SortChecker.sortInfo that fires only when the sole parameter is literally a CString node. A PureCollection wrapping one CString is not a CString, so the desugar is skipped, the call is checked against the ColSpec signature, and InferenceKernel:1318 reports "expected ColSpec<T>, got String". The defect is encoding a MULTIPLICITY rule as an AST-shape rule.

**Owning code** — core/src/main/java/com/legend/compiler/spec/SortChecker.java:106 (sortInfo), :120 (carriesColSpec)

**Fix** — In sortInfo (:106), before the CString test, peel a singleton collection: take the sole parameter, and if it is a PureCollection of size 1 replace it with its single element; then apply the existing CString→ColSpec rewrite, plus an else-arm passing a bare ColSpec through. In carriesColSpec (:120), give the AppliedFunction arm the same peel so `sort([desc(['X'])])` is classified as a relation sort and rides checkGeneric+TypedSort rather than applyGeneric. No lowering change — after the peel the node is the ordinary TypedSortInfo(colName, ascending=false) the renderer already emits as `order by "FIRST NAME" desc`. Keep the peel strictly at size()==1, and do NOT peel in the SORT arm's legacyStringSortToModern (:69-76), whose ['A','B'] semantics differ.

**Leverage** — High. Smallest fix in the bucket, clears any corpus test spelling asc/desc with a bracketed single column name. Low blast radius as long as the peel stays size-1.

---

### 45. Subtype cast onto an EMBEDDED property reads a plain stc_ column union synthesis only publishes flattened

**1 test** · effort **S** · confidence high · bucket 11 (unclassified) · verdicts: REAL_DEFECT 1

Tests: `testPartialUnionMappingOfSubTypePrimitiveProperties_EmbeddedMapping`

**Mechanism** — UnionSynthesis classifies the embedded `ext1Address` as non-scalar, takes the embedded branch, and publishes only the FLAT per-leaf column stc_…PersonExt1___ext1Address__name (addStcEmbeddedLeaf); there is no plain stc_…___ext1Address by construction, and ClassSources exposes bindings keyed by column name. On the read side subTypeNavCastCanon uses pa.property() verbatim, minting the SCALAR spelling stc_…___ext1Address and leaving `.name` outside as a second hop. Substitution's filteredInstanceRead reduces that to a 1-element path, rewriteHeadProp finds no binding, and it throws at Substitution.java:1421-1427. Sibling tests pass because they cast to scalar props, for which the plain column does exist.

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1834-1846 (subTypeNavCastCanon tail), insert above :1775/:1808; refs UnionSynthesis.java:932-963, ClassSources.java:688-700, Substitution.java:1421-1427, optional mirror Substitution.java:1931

**Fix** — One place: add a new arm at the TOP of subTypeNavCastCanon. Because liftFilteredHeads applies the canonicalizer top-down, the two-level node (subType($v,@Sub).prop).leaf is visited before its child, so the arm can fold the trailing hop into the column name: match outer/mid TypedPropertyAccess over a subType TypedNativeCall, compute plain = subTypeColumn(sub, mid.property()) and flat = subTypeColumn(sub, mid.property()+"__"+outer.property()), and only when the target does NOT bind plain and DOES bind flat, return TypedPropertyAccess(witness-filtered nav, flat). Both guards are load-bearing: !containsKey(plain) preserves the existing route for genuinely class-typed stc navigations, containsKey(flat) fires only where union synthesis flattened. Keep the witness branch for partial-membership CASE reads. Do NOT make UnionSynthesis emit a plain class-typed stc column, and do not flatten in the static Substitution.pathOf.

**Leverage** — High. Single-test but a genuine correctness unblock with a tightly guarded arm — behavior changes only for a set that is today 100% failure. The row assert (toCSV) is a real check, so it proves rows, not just SQL text.

**Shares code with** — Optional mirror edit lands in resolver/Substitution.java:1931 (rewritePath, before assocLeaf) — coordinate with buckets touching Substitution path resolution.

---

### 46. Derived [0..1] property: relational path must inline, not apply Pure empty semantics

**1 test** · effort **S** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testQualifierWithInThroughJoin`

**Mechanism** — In Typer.applyProperty's derived-read arm, a [0..1] receiver β-inlines the qualifier body only when derivedBodyStrictInThis proves it null-strict; `if` is in EMPTY_MANUFACTURING_FNS, so an `if(...)`-bodied qualifier scores non-strict and the Typer throws. The gate preserves PURE's in-memory auto-map semantics, but legend-engine's RELATIONAL compiler has none: processQualifiedProperty inlines the body into SQL with no emptiness test, and emptiness surfaces only as SQL NULL propagation. The golden proves it — the account-less trade gets 'B' via `case when name in (...) then 'A' else 'B' end`, not empty. The roadmap's advertised 'presence-guarded emission' would emit TDSNull and STILL fail.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2489-2497, 2505-2511, 2514

**Fix** — In Typer.applyProperty's derived-read arm, delete the strictness gate at Typer.java:2505-2511 so a [0..1] receiver falls through to the same applyGeneric inline the exactly-[1] receiver already takes at line 2514. Leave the isMany() auto-map arm at 2489-2497 untouched — that one is real Pure and the engine agrees. If some corpus shape genuinely needs Pure empty semantics for a [0..1] derived read, discriminate on the EXECUTION TARGET (compiled-to-SQL -> inline, matching processQualifiedProperty) rather than on body shape, keeping derivedBodyStrictInThis/EMPTY_MANUFACTURING_FNS for the host-eval path only. Explicitly do NOT implement the presence-guarded emission the wall text advertises: it produces TDSNull for the account-less row and this test would still fail.

**Leverage** — Best ratio in the bucket: an S deletion in Typer that unblocks every parameterless derived property whose body uses if/match/isEmpty over a [0..1] receiver.

**Shares code with** — compiler/spec/Typer.java derived-read arm — any other bucket walled on 'derived body not null-strict' is the same one-line gate.

---

### 47. in-collection peel is not recursive and misses TypedLimit

**1 test** · effort **S** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `relationalResultSourcingOfDateList`

**Mechanism** — InnerDemand's inQueryReads resolver lambda peels a trailing `distinct` and a trailing `take`/`limit` off the collection argument of `in`, but matches both as TypedNativeCall. `distinct` over a collection genuinely is a native call (DistinctChecker delegates the non-relation overload to Typer.emitCall); `take`/`limit` never are — CoreFn.LIMIT/TAKE own both overloads and SlicingChecker emits TypedLimit unconditionally. So the take arm is dead code, the chain falls to rawResolver, which returns a Date[*]-typed node, collectInQuery's RelationType check rejects it, no InQueryRead is registered, and the rewrite descends into object-space TypedLimit, which Substitution has no arm for. The distinct arm also calls rawResolver rather than itself, so peels never compose.

**Owning code** — core/src/main/java/com/legend/resolver/InnerDemand.java:506-551 (dead arm :526-539); wall at core/src/main/java/com/legend/resolver/Substitution.java:1893-1900

**Fix** — Replace the lambda with a named self-recursive function (or private static method taking rawResolver): (i) 1-arg TypedNativeCall `distinct` -> recurse on arg, require RelationType, rebuild TypedDistinct; (ii) NEW arm: TypedLimit tl -> recurse on tl.source(), require RelationType, rebuild TypedLimit with tl.count(); (iii) else rawResolver.apply(chain). Keep the existing try/catch of NotImplementedException|LegendCompileException around the whole thing. Delete the dead TypedNativeCall take/limit arm at :526-539 or leave a comment that CoreFn.TAKE/LIMIT always emit TypedLimit. Recursion (not rawResolver) is what makes ->distinct()->take(n) and ->take(n)->distinct() both work. Do not instead make SlicingChecker emit a native call for the collection overload — Anchors.objectSpine:99 and the Lowerer switch on TypedLimit.

**Leverage** — Small, contained, high value: fixes a whole idiom family (any in/contains argument ending in take/limit). Sibling without ->take(2) already passes, so the blast radius is proven narrow.

**Shares code with** — Touches Substitution.java only at its default-throw wall; the real edit is InnerDemand. Buckets blaming Substitution's :1893 default throw may share this symptom but not this fix.

---

### 48. Typer refuses to inline a derived body over a [0..1] receiver

**1 test** · effort **S** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testDenormMappingWithQualifierWithIfAndEquals`

**Mechanism** — Typer.synth's zero-arg-derived arm refuses to beta-inline a derived body over a [0..1] receiver unless the body passes a hand-written "null-strict" whitelist. `Person.firm` is [0..1] and the qualifier body starts with `if`, a member of EMPTY_MANUFACTURING_FNS, so strictScan sets the non-strict bit, derivedBodyStrictInThis returns false, and a TypeInferenceException is thrown. The whitelist encodes a Pure-semantics belief (an empty receiver auto-maps to empty, so a value-manufacturing body must not inline) that the relational store does not implement: engine's processQualifiedProperty processes the qualifier's expressionSequence against the current cursor with no presence guard and no regard for receiver multiplicity. The corpus confirms engine's behaviour is the manufactured value — testQualifierWithInThroughJoin asserts cat='B' for a trade whose LEFT-JOINed account row is absent.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2495-2513 (block to delete), 2514 (fallthrough), dead helpers 2715-2722, 2724-2732, 2735-2771

**Fix** — Delete the `if (!exactlyOne) { … }` block at Typer.java:2495-2513. A [0..1] receiver then falls through to the same `return applyGeneric(new AppliedFunction(d.bodyFunctionFqn(), List.of(ap.receiver())), env);` at :2514 that a [1] receiver already takes — exactly processQualifiedProperty's behaviour. Keep the isMany() auto-map branch at :2487-2494 untouched: that IS Pure's auto-map and is correct. Then delete the now-dead private helpers derivedBodyStrictInThis (:2724-2732), strictScan (:2735-2771), and the constant EMPTY_MANUFACTURING_FNS (:2715-2722), and rewrite the comments at :2476-2481 and :2706-2714 to record the engine rule: the relational store inlines a qualifier body regardless of receiver multiplicity; an absent [0..1] receiver yields the body evaluated over NULL columns, not empty. Nothing downstream changes — the resulting `$p.firm.legalName` chain is a shape already resolved today.

**Leverage** — Highest ratio in this bucket: a pure deletion that removes a policy contradicting ground truth, unblocks a named second test (testQualifierWithInThroughJoin, U40), and deletes ~70 lines of dead whitelist.

**Shares code with** — Deletes a block in compiler/spec/Typer.java's AppliedProperty synth path — any other bucket blaming Typer for qualifier/derived-property TypeInferenceExceptions is likely the same deletion; check before writing a second fix.

---

### 49. embedded-exists gate and substitution both hard-limit predicate paths to length 1

**1 test** · effort **S** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testExists`

**Mechanism** — Substitution.rewriteCallArms has the correct arm for `exists` over a same-row embedded head (predicate applied directly over the parent row, no EXISTS subquery — the engine's rule), but its admission gate predLeavesIn only accepts predicate paths of LENGTH 1. Here the predicate path is [address, name] (size 2, because `address` is a nested ctor inside the ^Firm partial), so the arm declines, the walk descends into the exists call's arg0 `$p.firm`, and rewriteHeadProp throws graph-output NotImplementedException on a binding whose look-through is a TypedNewInstance. Two compounding defects: collectParamPaths also adds PREFIX paths (no return after out.add), so a generalized gate would see a spurious [address] resolving to a ctor; and substEmbeddedReads has the mirror-image size-1 restriction.

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:2410-2414 (predLeavesIn), 2418-2430 (collectParamPaths), 2453-2457 (substEmbeddedReads), 2482-2486 (loud wall), 3097-3120 (ctorTailLeaf)

**Fix** — Three coordinated edits in Substitution.java. (1) Add `@Nullable TypedSpec partialLeaf(TypedNewInstance partial, List<String> path)` starting at `partial.properties().get(path.get(0))` and running the identical descent as ctorTailLeaf (:3097-3120): toOne look-through, otherwiseOf look-through, descend while `ni.properties().containsKey(path.get(h))`, else null; unwrap a trailing toOne and return null if the result is still a TypedNewInstance (a class-typed value is not a leaf and must stay loud). Refactor ctorTailLeaf to delegate so there is one descent. (2) collectParamPaths (:2418-2430): add `return;` right after `out.add(p);` so only MAXIMAL paths are collected. (3) predLeavesIn (:2410-2414): `for (List<String> path : paths) if (partialLeaf(partial, path) == null) return false;` and in substEmbeddedReads (:2453-2457) replace the `p.size() == 1` case with a partialLeaf lookup returning `renameRowVar(lf)`, falling through to the existing loud wall at :2482-2486.

**Leverage** — Contained, and it makes the gate and the substitution share one resolver so they cannot drift — the class of bug that produces silent wrong rows. Unblocks any nested-embedded exists predicate.

**Shares code with** — All three edits are in resolver/Substitution.java's embedded-partial helpers (predLeavesIn / substEmbeddedReads / ctorTailLeaf) — disjoint from the filtered-nav arms other clusters touch, but same file.

---

### 50. OTHERWISE per-leaf dispatch skips join demand for class-typed partial members

**1 test** · effort **S** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testProjectionOtherwiseNonPrimitive`

**Mechanism** — StoreResolver's OTHERWISE per-leaf dispatch treats membership in the embedded partial as proof that the next hop is a same-row column and short-circuits the navigate-slot demand with `continue`. For [bondDetails, bondClassification, type] the partial does contain `bondClassification` — but as a CLASS-TYPED navigate-slot read (JoinChainEmission mints the slot for the Otherwise partial's structural Join sub-PMs). So no join is demanded and no AssocSub is registered under the dotted key. At rewrite time rewriteMultiHop fails every arm — subNavs has only `holder`, the chainKey misses, and both ctor drills stop because the slot read is not a TypedNewInstance — producing the reported multi-hop wall. The drill machinery for exactly this shape sits two lines below the `continue`.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:1345-1353 (the `continue`), drill 1362-1382, dotted registration 1502-1532; Substitution.java:1256-1259, 1263-1286

**Fix** — Replace StoreResolver.java:1345-1353 with a KIND-aware dispatch: take `var ow = Substitution.otherwiseOf(headBinding)`; if non-null, get the partial's property under `SyntheticHeads.realHead(path.get(1))`. If that binding exists AND `InnerDemand.navSlotAlias(pb, cs.rowVar(), navSteps.keySet()) == null` (a genuine same-row column) keep the `continue`; if it exists but IS a navigate slot, set `navRead = partial` so control falls into the existing drill; if it is absent, set `navRead = ow.args().get(1)` as today. Nothing else changes: the drill at :1362-1382 descends on path.get(1), lands mid=2 on the slot read, headKey becomes "bondDetails.bondClassification", navTails picks up ["type"], the loop at :1502-1532 registers the dotted AssocSub, and Substitution.rewriteMultiHop's chainKey branch resolves the leaf via assocLeaf. The sibling [bondDetails, holder, name] path still takes the else branch; the two coexist because navHeadByAlias is keyed by alias.

**Leverage** — Small edit reusing machinery already present two lines away, and it encodes the engine's actual rule (partial's own mapping wins over the otherwise target's), matching the golden's join off "root".

**Shares code with** — Fix is entirely in StoreResolver's otherwise dispatch; it only unblocks resolver/Substitution.java:1256-1259's existing chainKey branch, so no Substitution edit is needed and no conflict with the Substitution clusters.

---

### 51. Derived (qualified) property has no arm in ModelJoin condition rewriter

**1 test** · effort **S** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testDerivedPropertyInCondition`

**Mechanism** — A ModelJoin condition reads a zero-arg derived property ($employees.fullName). RelationReads.rewrite matches it on the plain-property arm, scans rf.columns() for a Col with that property name and then for an expression-bodied Col, finds neither, and throws "has no column binding on the Relation mapping of 'Person'". The rewriter has no arm for derived/qualified properties at all — it never consults ClassDefinition.derivedProperties(). MappingNormalizer catches the throw per-association, records it as a poison keyed <mapping>::<assocFqn> and drops the AssociationBinding; the query later fails in AssociationJoins.predicateMaterial with "association 'Person_Firm' is not mapped in mapping ..." plus the poison in parentheses.

**Owning code** — core/src/main/java/com/legend/normalizer/RelationReads.java:90-96, :117-127 (the throw), :150-170 (super-walk shape), :50-57 (6-arg overload passing model=null); MappingNormalizer.java:1249-1252, :419-435; AssociationJoins.java:1166-1177

**Fix** — Add a DERIVED-PROPERTY arm to RelationReads.rewrite immediately before the throw at :123. When model != null, resolve rf.className() via model.findClass and walk its derivedProperties() plus superclasses' (reusing the super-walk shape at :150-170) for a DerivedPropertyDefinition matching ap.property() with empty parameters and a Realization.Inline of exactly one expression. Beta-inline it: substitute the free variable 'this' with the ORIGINAL receiver Variable using the name-keyed traversal already at :129-147, then recursively call rewrite(...) so the resulting $employees.firstName / $employees.lastName take the plain-column arm. Guard the recursion with a depth counter so a self-referential derived property stays loud. Also thread model through ModelJoinNesting.java:120-124 (currently the 6-arg overload, model=null) so nested ModelJoin conditions inline too, and keep the [1] toOne wrapping at :98-108 on the inlined leaves.

**Leverage** — One test in this corpus (fullName appears in exactly one mapping condition), but it closes a whole missing category — any derived property inside a ModelJoin/XStore condition.

**Shares code with** — Shares the ModelJoinNesting.java:120-124 model=null call site with the ModelJoinNesting recursion cluster — sequence the two so both land the model-threading change once.

---

### 52. Inline execute() in a non-.values read position has no splice arm

**1 test** · effort **S** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testWithParameterToClassNestedSelect`

**Mechanism** — assertSize's arg is evaluated on its own, so the statement's preRoot is a size TypedNativeCall wrapping an INLINE execute call. The execute-in-result-position arm does not fire, and StatementExecutor.spliceHook has no arm for this shape: it handles only $r->size() where the argument is a TypedVariable already bound in execFrames, and an inline execute(...).values. The raw size(execute(lambda,...)) tree therefore reaches StoreResolver.resolve; Anchors.spaceOf marks both native calls ANCHORED, structural() recurses into the execute call's children, and the query lambda hits the 'a bare lambda VALUE is DATA — its consumer owns resolution' arm. No consumer owns it, so the getAll survives to assertNoStoreOnlyEscapees with the reported ancestry root > size > execute > TypedLambda > TypedFilter. The class query itself is fine — its ISIN twin passes with the same golden SQL.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:2534-2543 (insertion point), :2705-2723 (spliceValuesRead precedent), :329-334; EngineTestExecutor.java:1938-1957, :2665-2677; StoreResolver.java:476-478, :504, :220-240 (the throw)

**Fix** — Add one arm to StatementExecutor.spliceHook immediately after the existing $r->size() arm: if n is a TypedNativeCall in SIZE_FQNS with one arg, unwrap TypedFrom wrappers, and if the argument is an inline execute call, call buildFrame(ec2, letPrefix, true, specs, env) and return TypedCInteger(1L) — the Result envelope is Result<T|m>[1], never the row count, and Pure is strict so the query must still run. Three constraints ride with it: eager MUST be true (nothing downstream consumes the chain, so eager=false would silently skip the query and pass for the wrong reason); do NOT gate on relationRooted(), since this query is class-rooted and chain.info().type() is not a RelationType; wrap the checked SQLException exactly as spliceValuesRead does. Leave every other inline-execute read position as the existing loud resolver wall — no blanket unwrap. Nothing in the resolver changes.

**Leverage** — Low. execute(...)->size() occurs exactly once in the whole core_relational corpus, and the fix is a narrow harness/executor accommodation, not a query-engine capability.

**Shares code with** — Lives in StatementExecutor.java spliceHook, which other buckets also blame. Note the OTHER 'store resolution left getAll' failures in the sweep (projection testVariableReferenceIn*, tds columnValueDifference*, testParseDate) have different ancestry and are NOT this cluster.

---

### 53. `ScanRelations.scanRoots` only splits `concatenate` when it is the OUTERMOST expression of the query body (ScanRelations.java:126-137 calls `splitConc

**1 test** · effort **S** · confidence high · bucket 10 (harness SHAPE) · verdicts: REAL_DEFECT 1

Tests: `testTableToTdsWithJoinAndUnion`

**Mechanism** — `ScanRelations.scanRoots` only splits `concatenate` when it is the OUTERMOST expression of the query body (ScanRelations.java:126-137 calls `splitConcatenate(body, branches)` on the top-level body only). In this test the body is the outer `join(...)` and the concatenate sits UNDER it as parameter 0, so `branches` has size 1 and the whole `join(concatenate(A,B), addressTable, INNER, lambda)` is handed to `tableToTdsRoots`. `containsCall(n,"join")` (line 222) is true, so `parseTdsJoinChain` (line 296) recurses on `af.parameters().get(0)` — the `concatenate(...)` — which is not a `join`, so it falls to `parseTdsSource(ctx, concatenate(...))` (line 315). `parseTdsSource` peels only `project` wrappers (lines 413-418), so `cur` is still the concatenate; `collectTableToTds` (line 506) recurses through the concatenate's parameters and returns BOTH personTable and firmTable; `found.size() != 1` at line 421 throws `NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` at line 422-423. `LineageRelationsForm.tryRun` catches it (line 134) and returns `Outcome.Unsupported("scanRelations: " + msg)` — exactly the observed detail (the doubled `scanRelations:` prefix is the harness prefix plus the message's own prefix).

**Owning code** — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:129 — `splitConcatenate(body, branches);` is applied only to the top-level body, so a concatenate nested under a join is never split; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:146-158 — `splitConcatenate` flattens only a literal `concatenate(a,b)` spine; it has no rule to push an enclosing operator into the branches; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:315 — `TdsSrc base = parseTdsSource(ctx, v, aliases, byTable);` is the base case reached with v = the concatenate

**Fix** — In `/Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java`, teach `splitConcatenate` (lines 146-158) to DISTRIBUTE a `join` over a concatenate on its left operand — the AST mirror of the engine's "one base tree per UnionAll query, each getting a copy of the join child". Insert before the existing `out.add(v)`: ```java if (v instanceof AppliedFunction jf && "join".equals(jf.function().substring(jf.function().lastIndexOf(':') + 1)) && jf.parameters().size() >= 3) { List<ValueSpecification> lefts = new ArrayList<>(); splitConcatenate(jf.parameters().get(0), lefts); if (lefts.size() > 1) { for (ValueSpecification l : lefts) { List<ValueSpecification> ps = new ArrayList<>(jf.parameters()); ps.set(0, l); splitConcatenate(jf.withParameters(ps), out); // recurse: nested joins } return; } } ``` (`AppliedFunction.withParameters` already exists — it is used at JoinChecker.java:112.) Gate it on `join` only; do NOT distribute generically over any wrapper, because the engine only splits where the union alias is a join operand. Nothing else changes: `scanRoots` then sees `branches.size() == 2`, calls `tableToTdsRoots` once per branch (each with its OWN fresh `aliases`/`byTable` maps and its own freshly-built addressTable `Node`, because `collectTableToTds` constructs new Nodes per call at line 519), and the existing `if (branches.size() > 1) out.sort(comparing(nd -> nd.table))` at line 138-141 yields firmTable then personTable — exactly the golden order. Column demand also falls out correctly: `parseTdsSource` narrows personTable/firmTable to {ID} via the project alias map (lines 459-468), and addressTable is a bare source so `keepAll = true` (line 431) keeps all five columns. Labels are suppressed anyway (`relationTreeAsString(false)` -> `showLabels=false`, LineageRelationsForm.java:81-85).

**Leverage** — Singleton — folded in from its own diagnosis (clustering agent omitted it).

---

### 54. ScanRelations models a `tableToTDS(...)->join(...)` spine as a chain of SINGLE-table sides.

**1 test** · effort **S** · confidence high · bucket 10 (harness SHAPE) · verdicts: REAL_DEFECT 1

Tests: `testTableToTdsWithJoinAndUnion`

**Mechanism** — ScanRelations models a `tableToTDS(...)->join(...)` spine as a chain of SINGLE-table sides. parseTdsJoinChain recurses on the join's left parameter and hands each side to parseTdsSource, which strips only `->project(...)` wrappers (ScanRelations.java:404-410) and then requires `collectTableToTds` to find exactly one node: `if (found.size() != 1) throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` (ScanRelations.java:418-424). In this test the join's LEFT side is `project(tableToTDS(personTable)) ->concatenate( project(tableToTDS(firmTable)) )` — a UNION, so collectTableToTds returns two nodes and the wall fires. The concatenate splitter that does exist (scanRoots, ScanRelations.java:123-142) only splits at the TOP of the query body; here concatenate is nested underneath the join, and the tdg consumer relTree bypasses scanRoots entirely, calling tableToTdsRoots directly (ScanRelations.java:180-190). A second, consequential gap is behind the first: even with the left side split, attachTdsJoin resolves the join column through a single flat alias map (`aliases`, populated with putIfAbsent so the left-most branch wins) and attaches the right node to ONE parent via `byTable.get(l[0])` (ScanRelations.java:370-386) — but the engine's expected tree has addressTable as a child of BOTH personTable and firmTable. NOTE ON ATTRIBUTION: the brief's `source` line (lineage/scanRelations/scanRelationsTests.pure:920) conflicts with its `family` (testDataGeneration/tests). Three functions share this short name. The failure text is a BARE NotImplementedException message with no prefix, which is the exact shape tdgLetArm produces (`new Outcome.Unsupported(e.getMessage().split("\\n")[0])`, EngineTestExecutor.java:1418-1426), so the failing test is almost certainly `meta::relational::testDataGeneration::tests::testTableToTdsWithJoinAndUnion` (testDataGeneration.pure:1326). The same fix covers the lineage twin regardless.

**Owning code** — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:421 — `if (found.size() != 1) { throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source"); }` inside parseTdsSource; the `while` above it strips only `project`.; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:294 — parseTdsJoinChain: `parseTdsJoinChain(ctx, af.parameters().get(0), ...)` for the left, `parseTdsSource(ctx, af.parameters().get(1), ...)` for the right, then `attachTdsJoin(cl, right, aliases, byTable)`; the base case is `TdsSrc base = parseTdsSource(ctx, v, ...); roots.add(base.node());` — no concatenate handling anywhere on this path.; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:372 — `Node parent = Objects.requireNonNull(byTable.get(l[0]), ...); parent.children.put(right.table + "(tds_join_" + parent.children.size() + ")", right);` — a single parent, a single shared right Node.

**Fix** — In /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java: (1) Make a tds side able to be a UNION. Change `TdsSrc` (line ~320) from `record TdsSrc(Node node, Map<String,String[]> own)` to `record TdsSrc(List<Node> nodes, Map<String,String[]> own)` (or add a parallel `unionNodes` list). In parseTdsSource, before the `found.size() != 1` check, split the (project-stripped) expression with the existing `splitConcatenate` helper (line 147) and parse each branch independently, merging their alias maps; keep the wall for the genuinely unrecognised case (found.size()==0, or >1 tableToTDS in a single non-concatenate branch). (2) Make the alias map branch-aware: `aliases` must map alias -> LIST of [table, physCol] rather than the first binding only (today `own.forEach(aliases::putIfAbsent)` at the end of parseTdsSource silently drops the second branch's 'eID'). (3) In attachTdsJoin (line 327): iterate every owner the left alias resolves to; for each, attach its OWN copy of the right node (deep-copy Node: table/db/schema/cols/keepAll, and a per-parent `cond` built from that parent's own column) rather than sharing one instance. Preserve the existing `labelOverride` construction per copy. (4) In parseTdsJoinChain (line 294): `roots.addAll(base.nodes())` instead of `roots.add(base.node())`, and sort multi-branch roots by table name to match the engine (reuse the comparator already at line 140) so firmTable precedes personTable. No change is needed in TestDataGenerator — once relTree returns the two-root/one-child-each tree, the existing per-relation fetch generation produces the four goldens.

**Leverage** — Singleton — folded in from its own diagnosis (clustering agent omitted it).

**Shares code with** — The two same-named twins: `meta::pure::lineage::scanRelations::test::testTableToTdsWithJoinAndUnion` (scanRelationsTests.pure:920) and `meta::relational::testDataGeneration::tests::alloy::testTableToTdsWithJoinAndUnion` (testDataGeneration.pure:2964) — whichever of the three is not the one currently reported.

---

### 55. CTE extraction: SqlWith/CteRef IR nodes + transcription of extractSubqueriesAsCTEsRecursively

**6 tests** · effort **L** · confidence medium · bucket R (newly-honest walls) · verdicts: MISSING_FEATURE 6

Tests: `testSingleSubQueryFromView`, `testSingleSubQueryFromOperations`, `testDeepSubQueries`, `testMultipleSubQueries`, `testComplexSubQueries`, `testCorrelatedSubQueryIsolationStrategy`

**Mechanism** — legend-lite's SQL IR has no CTE node — `sealed interface SqlQuery permits SqlSelect, SqlUnion` (SqlQuery.java:11) — so there is nothing to hoist a derived table into, and no pass that does the hoisting. Every one of these 6 goldens requires the same single transformation: walk the FROM/join tree of the root select, and for each SqlSource.Subselect (SqlSource.java:77-97) recurse into its inner query FIRST, name it subquery_cte_<level>_<n>, append the child's CTEs before the node's own, and replace the node with a CTE reference that PRESERVES the original alias. The three ordering/naming invariants that look like separate bugs are one fold: deepest-first append (cteExtractionPostProcessor.pure:93, visible as 2_1 before 1_1 at .pure:186-188), per-level counter threaded left-to-right across siblings rather than reset per subtree (.pure:94, visible as 2_1/2_2 at .pure:207-210), and level 1 meaning the root select's direct subselects. The cases differ only in what produced the Subselect — view frame, limit()-isolation frame, correlated-isolation frame — which the pass must ignore entirely: it keys on the node type SqlSource.Subselect, never on frameName (including null and SqlSource.Subselect.EXISTS_KEYS_FRAME at SqlSource.java:87), exactly as the engine's post-processor is producer-agnostic (postProcessor.pure:66-83).

**Owning code** — core/src/main/java/com/legend/sql/SqlQuery.java:11 (permits clause — add SqlWith); core/src/main/java/com/legend/sql/SqlSource.java:77-97 (Subselect, the hoisted node; add CteRef alongside); new core/src/main/java/com/legend/lowering/CteExtraction.java (transcription of cteExtractionPostProcessor.pure:47-99); core/src/main/java/com/legend/lowering/SqlPostProcessors.java:192-251 (apply/source switches — source() is default-less/total, so the compiler enumerates every remaining site); core/src/main/java/com/legend/lowering/SubselectPrune.java; UnionSerialOrder; ScanColumns; SqlRewriter; AnsiSqlRenderer render at :82 and union arm at :115; EngineStyleH2 render at :219-230, union arm at :311, and planQuery at EngineStyleH2.java:226 (alias planning order)

**Fix** — One change in three mechanical parts, landing on top of the recognizer cluster. (a) IR: add `record SqlWith(List<Cte> ctes, SqlQuery body) implements SqlQuery` with `record Cte(String name, SqlQuery query)`, extend SqlQuery.java:11's permits clause, and add `record CteRef(String name, String alias, List<OutputCol> outputs) implements SqlSource` so a table-rename can never rewrite a CTE reference — mirroring the engine's explicit CommonTableExpressionReference passthrough arm in fixTables (postProcessor.pure:351). Add arms in the seven switch sites listed in owningCode. (b) Pass: CteExtraction.extract(SqlQuery) — non-SqlSelect or no Subselect in the FROM tree returns the argument unchanged; otherwise fold over the join tree (left before right) with a single accumulator carrying currentSelect + extractedCTEs + levelIndexMap together, recursing before naming, appending child CTEs before the parent's, replacing each Subselect with CteRef(name, node.alias(), node.outputs()). (c) Render: `with <name> as (<query>)[, <name> as (<query>)] <body>` — lowercase, comma-space separated, one space before the body — with each CTE body rendered through the existing dialect `query(sb, ...)` recursion so H2's `top 10` spelling survives inside a CTE. Plus one ordering decision that is part of the same change: EngineStyleH2.planQuery (EngineStyleH2.java:226) must plan group aliases over the PRE-extraction tree, because the goldens show pre-extraction numbering (the tradetable_1→tradetable_3 gap at cteExtractionPostProcessor.pure:209, tradetable_4 at :231).

**Leverage** — 6 tests off one pass is the best ratio available here, and the pass is a straight transcription of ~50 lines of Pure with goldens that pin every ordering rule — so it is tractable despite the L. But be honest about what it buys: this is byte-exact text conformance for one post-processor hook, with no correctness payoff anywhere else in the engine; nothing outside this corpus consumes SqlWith. Two real hazards. First, false green: if alias numbering drifts because planQuery runs post-extraction, sqlTextVerify falls through to H2 row-replay (EngineTestExecutor.java:1007-1013 -> :1076) and these tests pass on rows that are invariant by construction — re-creating exactly the false green 6ddae338 was written to kill. Accept only on byte-exact text. Second, two members carry unresolved falsifiers that could each add work outside this change: testSingleSubQueryFromOperations needs SubselectPrune not to have already flattened the limit-isolation subselect (else a prune exclusion is required), and testCorrelatedSubQueryIsolationStrategy needs lite's correlated isolation to lower as a plain SqlSource.Subselect rather than a lateral join or SqlExpr.Exists (else it needs an isolation-lowering fix first and is not covered here). Run both falsifiers before committing to the L — if the correlated one fires, drop it to 5 tests and ledger it.

**Shares code with** — Adding a SqlQuery variant forces arms in AnsiSqlRenderer (:82, :115) and EngineStyleH2 (:219-230, :311) — any other bucket blaming SQL text rendering or dialect spelling will be editing the same switch statements, so renderer-arm work should be merged. EngineStyleH2.planQuery:226 alias planning is shared surface: this cluster wants aliases planned on the pre-post-processing tree, which changes alias numbering globally — any bucket with a `<table>_<n>` alias-numbering mismatch is touching the same decision and must be co-designed. SubselectPrune.java is also shared: this cluster may need it to stop collapsing limit-isolation subselects, which will change output for any bucket relying on that collapse. Cluster is otherwise self-contained; the CteExtraction pass itself is new code no other bucket blames.

---

### 56. Metamodel-walk map-lambda whitelist (walkMapBody) blocks the relationalOperationElement type-inference chain

**2 tests** · effort **M** · confidence high · bucket 08 (?) · verdicts: MISSING_FEATURE 2

Tests: `testJoinStringsTypeInference`, `testSQLNullWithinCaseTypeInference1`

**Mechanism** — Both tests walk `mapping->rootClassMappingByClass(X)->cast(...)->map(x|$x->propertyMappingsByPropertyName('...')).relationalOperationElement->toOne()`. `->map` with a literal lambda becomes a TypedMap, so planWalk dispatches to walkMapOver -> walkMapBody, whose switch is a hand-maintained three-entry whitelist (`view`, `mainTable`, `resolvePrimaryKey`) with `default -> null` (StatementExecutor.java:1741-1764). `propertyMappingsByPropertyName` IS handled in the MAIN planWalk switch (StatementExecutor.java:1402-1408) but not in the map-lambda copy. walkMapOver drops null mapper results (StatementExecutor.java:1770-1789), so the map yields an EMPTY list instead of failing; the chain silently degrades (cast passes through, `.relationalOperationElement` and `toOne()` stay empty), argOp then can't unwrap a size-1 list to a Rop (StatementExecutor.java:1725-1735), the DynaFunction falls to the mixed-args DynH channel, inferRelationalType returns null, planWalk returns null, and the statement falls back to the SQL pipeline where Scalars.lower walls on `rootClassMappingByClass` (Scalars.java:2384). Everything else each test needs already exists (MetamodelWalk.propertyMappingsByName:1054, Pm.relationalOperationElement:1144, joinStrings->Varchar(4000):1275).

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:1741-1764 (walkMapBody switch), :1770-1789 (walkMapOver null-drop + no list flatten), :1298-1416 (the main planWalk native switch to share); secondary for test 2 only: core/src/main/java/com/legend/exec/MetamodelWalk.java:1231-1313 (inferOp FunctionCall switch) and :1441-1481 (safe(a,b))

**Fix** — ONE structural change plus one small lattice change. (1) Stop hand-maintaining walkMapBody: extract the body of the main planWalk native switch (StatementExecutor.java:1298-1416) into a shared `metamodelStep(String simple, Object recv, TypedNativeCall c, ...)` helper and have BOTH planWalk and walkMapBody call it, so the entire recv-dispatched metamodel vocabulary (propertyMappingsByPropertyName, _classMappingByClass, classMappingById, superMapping, inferRelationalType, dataTypeToSqlText, schema, table) works inside a map lambda. While there, make walkMapOver FLATTEN list-valued mapper results and return NULL (not an empty list) when a body step is unrecognised, so an unsupported step produces an honest walk-failure fallback instead of a silently empty chain. (2) testSQLNullWithinCaseTypeInference1 needs two further MetamodelWalk edits behind that wall, both of which are wrong-answer (not wall) defects: add `case "sqltrue", "sqlfalse" -> new RelationalDataType.Bit();` to inferOp, and add the engine's `Other`-absorption rule to safe(a,b) (getSafeType(Other, X) = X, with an Other operand contributing size 0 to the Char/Varchar max-size rule). Without (2) the test returns OTHER instead of BIT; note that the currently-passing testSQLNullWithinCaseTypeInference2 passes only by accident of argument order and the safe() rule makes it correct for a principled reason — re-verify it yields VARCHAR(3)/VARCHAR(4) after the change.

**Leverage** — Best leverage in the bucket alongside the concatenate cluster. The shared-helper version removes a whole CLASS of gap rather than two tests: every metamodel-walk test whose chain happens to put a known native inside a map lambda is currently degrading to an empty list and falling back to the SQL pipeline, where it walls on an unlowerable metamodel native. Expect this to move tests in other buckets that blame Scalars.lower on `rootClassMappingByClass`/`classMappingById`. The MetamodelWalk.inferOp/safe half is genuinely valuable independent of tests — it is a silent wrong-type defect today.

**Shares code with** — Touches StatementExecutor.java (walkMapBody/walkMapOver/planWalk native switch, lines 1298-1416 and 1725-1789) and exec/MetamodelWalk.java (inferOp switch 1231-1313, safe() 1441-1481). Any other bucket whose failure message is a Scalars.lower wall naming a METAMODEL native (`rootClassMappingByClass`, `classMappingById`, `propertyMappingsByPropertyName`, `superMapping`, `resolvePrimaryKey`) is very likely the same walk-degradation mechanism reaching the SQL pipeline and should merge here rather than register scalar lowering rules. Also touches lowering/Scalars.java:2384 only as the observed wall, not as the fix site.

---

### 57. Union arms over-project undemanded columns — SubselectPrune never prunes set-operation inners

**2 tests** · effort **M** · confidence high · bucket 05 (?) · verdicts: REAL_DEFECT 2

Tests: `testChainedJoinsWithUnionsAndIsolationWithProjectionQueryTableFilter`, `testProjectAndFilterSamePropertySameJoinInUnion`

**Mechanism** — UnionSynthesis builds the synthetic union extent's column set from `common` = every member set's scalar properties, demand-blind (UnionSynthesis.java:1036-1048), and emits a ColSpec for every one of them into every arm (UnionSynthesis.java:1104-1136). SubselectPrune, the SQL-exit pass that would delete the undemanded projections, bails whenever the subselect's inner is a SqlUnion ('set-operation inner: branches are positional — never pruned themselves', SubselectPrune.java:275-286). So the emitted union base selects columns the query never reads — `t5.name AS legalName` for Firm[FirmSet1], `t0.firstName` for Person[set1/set2] — and those particular columns are DECLARED in the store but do not exist in the physical table the corpus setUp actually created (FirmSet1 clobbered to (ID,LegalName) by merge::setUp; PersonMaster created as (ID,lastName,FirmID)). DuckDB then errors: honestly as 'Table "t0" does not have a column named "firstName"' in one case, and as the bogus 'Referenced table "t5" not found! Candidate tables: "t4"' in the nested-union case (known DuckDB 1.5.0 misreport, UPSTREAM_DEFECTS.md:51 / U18). legend-engine survives the same shared clobbered H2 database only because its relational plan projects per demand and never selects those columns.

**Owning code** — core/src/main/java/com/legend/lowering/SubselectPrune.java:275-286 (the SqlSource.Subselect arm of rewriteSource that skips SqlUnion inners); guards to mirror at SubselectPrune.java:303-317; the over-projection origin is core/src/main/java/com/legend/normalizer/UnionSynthesis.java:1036-1048 and :1104-1136

**Fix** — Add a union branch to SubselectPrune's Subselect arm: when `inner instanceof SqlUnion u` and every branch is a SqlSelect with equal, non-empty projection counts and the existing guards hold (`!r.starred().contains("*")`, `!r.starred().contains(sub.alias())`), compute kept POSITIONS from the union's output names (position i kept iff `u.outputs().get(i).name()` is in `r.cols().get(sub.alias())` or `r.unqualified()`), rebuild every branch with only those positions via `s.withProjections(keptPs, keptOutputs)`, and rebuild the SqlUnion with the narrowed `outputs()`. Drop positionally regardless of expression kind (an arm slot may be `NULL AS ID_1`) — do NOT reuse pruneProjections' plain-Column-only rule, it would desynchronise the arms. Keep at least one position; bail if any branch is itself a SqlUnion or carries distinct/groupBy/having/qualify; leave the root query untouched. One edit fixes both tests — no second change needed. Do NOT compensate in the harness by reordering/isolating the setup stream: legend-engine shares one test DB and runs the same clobbering setUp order.

**Leverage** — Best in the bucket: one localized pass gets 2 tests, moves advisory golden SQL toward the engine (which also omits these columns), and structurally immunises every union query against store-declared-but-physically-absent columns — likely more of the tests/mapping/union family than the two named here. Both entries independently converged on the same single edit, and the second one is high-confidence with the honest DuckDB message, which de-risks the first one's U18-obscured message. Residual: testProjectAndFilterSamePropertySameJoinInUnion may then fail on row/column ORDER — a new, separate finding.

**Shares code with** — Touches lowering/SubselectPrune.java (SQL-exit projection pruning) and reads normalizer/UnionSynthesis.java. Any other bucket blaming 'column does not exist / referenced table tN not found' inside a union subquery, or blaming UnionSynthesis over-projection, merges here. Note the pass is a general demand-narrowing pass — clusters elsewhere that propose demand-driven union extents in the resolver should be redirected to this SQL-exit site instead.

---

### 58. ScanRelations: a tds-join side cannot be a UNION (concatenate under join)

**2 tests** · effort **M** · confidence high · bucket 10 (harness SHAPE) · verdicts: 

Tests: `testTableToTdsWithJoinAndUnion (lineage/scanRelations)`, `testTableToTdsWithJoinAndUnion (testDataGeneration/tests)`

**Mechanism** — parseTdsSource peels only ->project wrappers and then demands exactly one tableToTDS source: `if (found.size() != 1) throw new NotImplementedException("scanRelations: tableToTDS join side is not a single table source")` (ScanRelations.java:419-424). When the join's LEFT operand is `project(tableToTDS(A))->concatenate(project(tableToTDS(B)))`, collectTableToTds returns TWO nodes and the wall fires. Both tests are the identical throw at the identical line, reached through the identical parseTdsJoinChain->parseTdsSource recursion; only the consumer differs (LineageRelationsForm wraps the message with its own 'scanRelations: ' prefix, tdgLetArm reports it bare). The existing top-of-body concatenate splitter (scanRoots, ScanRelations.java:123-142) is unreachable for both: for the lineage twin the concatenate is nested under the join, and for the tdg twin relTree calls tableToTdsRoots directly (ScanRelations.java:181), bypassing scanRoots entirely.

**Owning code** — core/src/main/java/com/legend/lineage/ScanRelations.java:419-424 (the wall), :294-320 (parseTdsJoinChain/TdsSrc), :370-386 (attachTdsJoin single-parent attach), :129 and :138-142 (scanRoots split + root sort, reused not changed), :181 (relTree entry that bypasses scanRoots)

**Fix** — Make a tds join SIDE union-capable inside parseTdsSource, which is the one point both consumers pass through. (a) Widen TdsSrc from `record TdsSrc(Node node, Map<String,String[]> own)` to carry a List<Node>; in parseTdsSource, before the found.size()!=1 check, split the project-stripped expression with the existing splitConcatenate helper (line 147) and parse each branch independently, merging alias maps — keep the wall for found.size()==0 or >1 tableToTDS inside a single non-concatenate branch. (b) Make `aliases` multi-valued (alias -> LIST of [table, physCol]); today `own.forEach(aliases::putIfAbsent)` silently drops the second branch's 'eID'. (c) In attachTdsJoin, iterate every owner the left alias resolves to and attach a DEEP COPY of the right Node per parent (table/db/schema/cols/keepAll plus a per-parent cond) — sharing one instance would print correctly here but corrupt cond for the self-join and multi-join goldens. (d) parseTdsJoinChain base case does roots.addAll(base.nodes()), and multi-branch roots reuse the existing by-table comparator at line 140 so firmTable precedes personTable. Do NOT take the cheaper alternative floated in the lineage entry (distributing `join` over concatenate inside splitConcatenate at ScanRelations.java:146-158) as the whole fix: it lives on the scanRoots path and therefore fixes only the lineage twin, leaving the tdg twin walled.

**Leverage** — Good. Two tests for one change at one throw site, and the change is the load-bearing prerequisite for the XL testTdsJoinConcatenateAndJoin cluster (step 4 of that entry), so it is bought once and reused. Both goldens are real tree-shape assertions (the tdg twin pins four generated fetch SQLs), not engine-internals cosmetics, so the greens are worth having. The risk is contained but real: attachTdsJoin's byTable is documented as a LEFT-owner registry so a same-table right side does not shadow the accumulated left, and making it multi-valued must not disturb testTableToTdsWithJoinToSameTable / testTableToTdsWithMultipleJoin, which currently pass.

**Shares code with** — Self-contained to com.legend.lineage.ScanRelations, but ScanRelations is also the sole tree source for TestDataGenerator (TestDataGenerator.java:80 calls ScanRelations.relTree) and for LineageRelationsForm — any other bucket blaming testDataGeneration fetch-SQL goldens or scanRelations trees is blaming this same file, and clusters there should be merged with this one before either is scheduled.

---

### 59. Connection post-processor rename map is an execute-scoped thread-local, not carried by the plan

**2 tests** · effort **M** · confidence medium · bucket 04 (?) · verdicts: HARNESS_GAP 1, REAL_DEFECT 1

Tests: `testReplaceTablesPostProcessor`, `testToSqlStringReplaceTablesPostProcessor`

**Mechanism** — The rename map is computed in buildFrame, used to rebind a LOCAL env for the eager run, and then dropped: ExecFrame carries only (chain, relationRooted, result). The harness's row verification evaluates $result.values, which splices the BARE chain and re-plans it under the ambient ExecEnv whose tableReplace defaults to Map.of(), so the re-read queries the ORIGINAL tables and returns 7 seeded rows where the golden (correctly renamed) hits the empty differentPersonTable/otherFirmTable and returns 0. The same hole on the generation side: toSqlString uses its runtime argument only for databaseTypeOf and takes its renames from PostProcessBoundary, a ThreadLocal written only by execute() and never cleared per test — so a test with no execute() renders unrenamed, or worse inherits the previous test's map.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:2214-2229 (buildFrame computes tr into a local env), :2054 (ExecFrame record), :2261-2281 (eager run drops it), :2700-2703 (spliceValuesRead returns the bare chain), :3205-3209 (apply reads the ambient env), :52-69 and :40-44 (ExecEnv default Map.of()), :359-395 (toSqlString reads PostProcessBoundary); core/src/main/java/com/legend/exec/PostProcessBoundary.java:24-33

**Fix** — Make the runtime argument the single source of truth for renames and carry it on the plan. Factor the extract-hooks-from-a-runtime-argument step out of buildFrame (including the UserCallInliner step, which also fixes databaseTypeOf silently defaulting to H2 for a helper-call runtime) and call it from both buildFrame and toSqlString. Widen ExecFrame to carry tableReplace, and in executeStatements union the maps of every frame the statement references (throwing on conflicting keys rather than picking one) into a per-statement ExecEnv so the apply site at :3205-3209 sees it; feed spliceValuesRead's inline-execute frame into the same union. Then narrow or retire PostProcessBoundary rather than keeping a second source of truth. Do not have the harness reuse ExecFrame.result() instead of re-planning — the re-plan is the architecture.

**Leverage** — One clear win and one partial. testReplaceTablesPostProcessor is a genuine wrong-rows bug (7 rows of pre-rename data where 0 is correct) and should go green on this change alone, since a text-divergent golden is upgraded to a pass by row verification. testToSqlStringReplaceTablesPostProcessor needs two more things to even attempt its assert (the ExecCallFinder replace-through below) and then remains alias-gated, so do not count it. The stale-thread-local leak is worth closing regardless — it silently cross-contaminates tests. Also very likely fixes testReplaceTablesPostProcessorJoinIsolation, and generally every test whose runtime carries a connection post-processor and whose asserts read $result.values.

**Shares code with** — StatementExecutor.java buildFrame/ExecFrame/spliceValuesRead/toSqlString and exec/PostProcessBoundary.java. Any bucket where the first execution is correct but a $result.values re-read returns pre-transform data — or where a test with no execute() renders with a previous test's connection hooks — is this same missing threading. The harness half (ExecCallFinder.sideSqlText must collect literal replace(x, <CString>, <CString>) frames and APPLY them rather than refusing the chain, ExecCallFinder.java:78-87 and :114-121) is a separate, small change that belongs with the golden-side-evaluator harness cluster if that is also being done.

---

### 60. SQLExecutionNode.connection: native declarations, plan-model connection handle, include-aware DDL

**2 tests** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `testDatabaseConnectionSQLPopulation`, `testDatabaseConnectionSQLPopulationLegacy`

**Mechanism** — Two layers. Typing: legend-lite's SQLExecutionNode declares only sqlQuery/sqlComment (Pure.java:516), so `->cast(@SQLExecutionNode).connection` throws "has no property 'connection'" at Typer.java:2565; assertSize is not a plan-text assert so it escapes as ERROR. The legacy twin then needs one more declaration — it reads TestDatabaseConnection.testDataSetupSqls, which Pure.java:258 does not declare. Value: PlanNode is a 4-field record with no connection slot (PlanNode.java:20-21), planModel builds the node from es.sql() alone (StatementExecutor.java:2012-2014), and walkProp has no connection arm (:1834-1842). The asserted 58 is storeContract's processRuntimeTestConnections DDL expansion; Ddl.setUpDataSqlsText (Ddl.java:112-135) ignores db.includes() and would yield 36.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:258,516; core/src/main/java/com/legend/plan/PlanNode.java:20-21; core/src/main/java/com/legend/StatementExecutor.java:1114,1834-1842,2012-2014; core/src/main/java/com/legend/exec/Ddl.java:112-135

**Fix** — (1) Pure.java:516 — add `connection: DatabaseConnection[1]` to SQL_EXECUTION_NODE; Pure.java:258 — add `testDataSetupSqls: String[*]` to TEST_DATABASE_CONNECTION. (2) PlanNode — add a nullable connection component (or a ConnH record carrying kind, DatabaseType, datasourceSpecification, testDataSetupSqls) threaded through the canonical constructor. (3) planModel — reuse connectionInstanceOf (:1114), locate the enclosing ^ConnectionStore and read its element to get the store FQN, then emulate processRuntimeTestConnections: TestDatabaseConnection with non-empty csv and empty sqls gets setUpDataSqlsText; LocalH2DatasourceSpecification concatenates onto declared sqls. Note '' is NOT empty in Pure, so the empty-string CSV takes the setUpDataSQLs branch. Add walkProp arms for connection/datasourceSpecification/testDataSetupSqls/testDataSetupCsv/type. (4) Ddl.setUpDataSqlsText — make schema/table enumeration include-closure aware, grouping schemas by name and de-duplicating tables.

**Leverage** — Good: two tests plus testPlanWithLocalH2ConnectionWithSQL and a testCrossStoreGraphFetch assert, and it fixes a genuine DDL correctness bug (includes ignored). Legacy is the twin's fix plus one property line.

**Shares code with** — Adds properties to builtin/Pure.java native class declarations (:258, :516) and a walkProp arm in StatementExecutor.java — both files other buckets edit. Ddl.setUpDataSqlsText signature change hits SeedSqlForms.java:46,58 and TestDataGenerator.

---

### 61. Legacy shared-key TDS join desugars through a __jk_ rename

**2 tests** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: GOLDEN_TEXT_ONLY 2

Tests: `testTwoMappingsOneRuntime`, `testTwoMappingsOneRuntimeWithoutExternalMapping`

**Mechanism** — JoinChecker.sharedKeyLegacyJoin desugars join(tds2, JoinType, ['legalName']) into rename(right, legalName -> __jk_legalName) + modern join + a drop-synthetic TypedSelect. Lowerer.rename materialises the rename as its own derived table, so the synthetic name leaks into the ON clause and adds an extra alias; the fused drop-select gives the top select non-empty projections, which disqualifies EngineStyleH2.wrapTdsJoinTop (guard s.projections().isEmpty()), so the engine's outer tdsJoined isolation wrapper is never emitted. The engine joins unrenamed and omits the right's duplicate-named columns. Rows are identical in both tests; only plan text differs, and the sweep's got string is byte-identical between them.

**Owning code** — compiler/spec/JoinChecker.java:250-292 (+242-244); compiler/spec/typed/TypedJoin.java; lowering/Lowerer.java:1708-1761, :1692; sql/dialect/EngineStyleH2.java:240-247

**Fix** — Make the legacy shared-key join a first-class node. In JoinChecker.sharedKeyLegacyJoin:250-292 replace the rename/select desugar with a directly-constructed TypedJoin whose condition is $a.k == $b.k over ORIGINAL names and whose info is the deduped schema (the existing `kept` list at :286-288). Add a List<String> sharedKeys component to TypedJoin (empty for the modern overload) and update withChildren plus Lowerer.java:1692. In Lowerer.join:1708-1761, when sharedKeys is non-empty, build SqlSource.Join over unrenamed sides, resolve the ON against each alias with the original column name, emit an explicit projection (left columns ++ right minus shared keys), and wrap in SqlSource.Subselect(join, nextAlias(), "tdsJoined"). Do not route through check(t, modern, env) — T+V rightly rejects the name collision. Do not normalise __jk_ in PlanAsserts/TdsEquivalence.

**Leverage** — Two tests, no wrong rows — pure text parity. Worth doing because the desugar is a real modelling error that will keep bending alias numbering for any future legacy-overload golden.

**Shares code with** — Touches lowering/Lowerer.java (join/rename lowering, plus the synthetic TypedJoin construction at :1692) — any other bucket blaming Lowerer join or alias numbering can merge here.

---

### 62. EngineStyleH2 has no version axis: float literal and StrictDate/Date placeholder spellings

**2 tests** · effort **M** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 2

Tests: `testGroupByWithOpenVariableInAgg`, `testGroupByWithTwoOpenVariablesInAggAndFilter`

**Mechanism** — The dialect has no declared H2 version; each construct's spelling was chosen ad hoc, so output is a hybrid matching neither golden arm. Date literals are emitted in 2.1.214 form (DATE'...') while float literals are emitted in 1.4.200 form (bare 0.0 instead of cast(0.0 as float)). Second instance: Fold.planKindOf collapses DATE and STRICT_DATE into one PlanParam.Kind.DATE, and EngineStyleH2 tries to recover the distinction with a name heuristic (dotted name => TIMESTAMP'...'), which the corpus disproves — the engine's rule is purely by Pure type, StrictDate => DATE'...', Date/DateTime => TIMESTAMP'...'.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:968-970 (FloatLit), :989-1014 and :1003-1005 (PlanParam / dotted-name heuristic); core/src/main/java/com/legend/lowering/Fold.java:32-36; core/src/main/java/com/legend/sql/SqlExpr.java (PlanParam.Kind); core/src/main/java/com/legend/plan/PlanEnumForm.java:155-190

**Fix** — Commit EngineStyleH2 to one declared H2 version and derive every literal/placeholder spelling from one type-keyed LiteralFormat table (FLOAT -> cast(.. as float), STRICT_DATE -> DATE'..', DATE/DATETIME -> TIMESTAMP'..'), routing BOTH the FloatLit arm and the PlanParam arm through it, as the engine routes both through literalProcessor. Use lowercase cast(...) — the goldens have 26 lowercase, zero uppercase. Split PlanParam.Kind.STRICT_DATE out of Kind.DATE in Fold.java:32-36, delete the indexOf('.') heuristic, and audit every Kind.DATE consumer (EngineStyleDB2, EngineStyleComposite, PlanEnumForm) for exhaustiveness. Land the float and date changes together and also fix the upgraded arithmetic parenthesization, or m2m2r testProp3/testProp4 regress. Never widen PlanAsserts to accept both texts.

**Leverage** — Two tests here plus the recorded H2-version-mixing cluster, but it migrates every legacy-arm golden at once — measure the net corpus delta before landing; if negative, ledger the whole dialect-versioning item.

**Shares code with** — EngineStyleH2 literal/placeholder spelling and PlanParam.Kind touch PlanEnumForm.java:155-190, EngineStyleDB2 and EngineStyleComposite — merge with any bucket blaming date/float SQL text or plan-param rendering.

---

### 63. Post-processor hook body is type-checked as query code (transformNonCached / ViewSelectSQLQuery wall)

**2 tests** · effort **M** · confidence high · bucket 11 (unclassified) · verdicts: MISSING_FEATURE 1, REAL_DEFECT 1

Tests: `testExistsWithEmbeddedWithPostProcessor`, `testRestrictWithPostProcessor`

**Mechanism** — Both tests install `sqlQueryPostProcessors = [{query|$query->...::postprocess({rel|$rel})}]`. That value is CONFIG legend-lite never evaluates as Pure, but it still gets whole-graph compiled: StatementExecutor.containsEffect is not a syntactic scan — for every TypedUserCall it calls specs.compile(callee) and recurses, so it compiles the corpus's postprocess and then transformNonCached, whose match arms name relational-metamodel classes (ViewSelectSQLQuery, Operation, …) absent from the hand-authored prelude. Typer.namedType/ctx.findType misses and G throws `unknown type 'ViewSelectSQLQuery'` — identical message, identical trigger, in both tests.

**Owning code** — core/src/main/java/com/legend/compiler/spec/StatementExecutor.java:2911 (containsEffect); core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:340-361; SqlPostProcessors.collectConnections/readHook (path not pinned in dossier); alternative route core/src/main/java/com/legend/builtin/Pure.java:426,451

**Fix** — Take the config-skip route, not the prelude-growth route. (1) Add one predicate for post-processor config property names {sqlQueryPostProcessors, sqlQueryPostProcessorsConnectionAware, queryPostProcessorsWithParameter}. (2) containsEffect (:2911): before the generic children() recursion, when the node is a TypedNewInstance/TypedCopyInstance, skip values of those properties — a plan-time SQL rewrite can carry no DDL/executeInDb effect. That alone clears the reported error for both. (3) UserCallInliner.rewrite (:340-361): widen its single-name guard to the predicate, enter configMode per matching property, and add the mirror TypedCopyInstance/overrides() arm. (4) To make them actually pass: SqlPostProcessors.collectConnections must read TypedCopyInstance.overrides() too, and readHook needs an arm for the IDENTITY postprocess shape. Reject the Pure.java route — it drags in 8+ metamodel classes and still ends at the readHook wall.

**Leverage** — Medium. Unblocks the whole post-processor family and removes a real effect-scan overreach, but the installed hook is identity — no SQL changes. Edit (4) may flip silently-green copy-instance hooks to loud errors; land with a sweep.

**Shares code with** — Touches StatementExecutor.java (effect scan) and offers a Pure.java prelude-growth alternative other buckets may also be blaming — coordinate so nobody adds ViewSelectSQLQuery/Operation classes to Pure.java in parallel.

---

### 64. ScanRelations: tableToTDS join side cannot be a UNION

**2 tests** · effort **M** · confidence medium · bucket split-misc (?) · verdicts: 

Tests: `lineage/scanRelations · testTableToTdsWithJoinAndUnion`, `testDataGeneration/tests · testTableToTdsWithJoinAndUnion`

**Mechanism** — Both die at the same wall: ScanRelations.parseTdsSource peels only ->project wrappers, then demands exactly one tableToTDS node; when the join's left side is project(tableToTDS(person))->concatenate(project(tableToTDS(firm))), collectTableToTds returns two and NotImplementedException("scanRelations: tableToTDS join side is not a single table source") fires. The only concatenate splitter (scanRoots) runs on the OUTERMOST body only, so a concatenate nested under join is never split; the tdg consumer bypasses scanRoots entirely and calls tableToTdsRoots directly. Behind that sits a second gap: the flat putIfAbsent `aliases` map keeps only the left-most branch's join column and attachTdsJoin attaches the right node to a single parent, whereas the engine's tree has addressTable as a child of BOTH branch roots.

**Owning code** — core/src/main/java/com/legend/lineage/ScanRelations.java:126-158 (scanRoots/splitConcatenate), :294-315 (parseTdsJoinChain), :320 (TdsSrc), :327-386 (attachTdsJoin), :404-424 (parseTdsSource + the throw)

**Fix** — Make a tds join side union-capable in ScanRelations.java: widen TdsSrc from a single Node to List<Node>; in parseTdsSource, after stripping project wrappers, run the existing splitConcatenate helper and parse each branch independently, merging alias maps — keep the wall only for found.size()==0 or >1 tableToTDS inside a single non-concatenate branch. Make `aliases` map alias -> list of [table, physCol] instead of first-binding-only. In attachTdsJoin, iterate every owner the left alias resolves to and attach a per-parent deep copy of the right Node (own cols/keepAll/cond/labelOverride). In parseTdsJoinChain, roots.addAll(base.nodes()) and sort multi-root output by table name with the comparator at :140 so firmTable precedes personTable. TestDataGenerator needs no change. This subsumes the narrower alternative of distributing join over concatenate inside splitConcatenate.

**Leverage** — Real defect, self-contained in one file, unblocks both twins plus the third same-named alloy variant. Medium value: lineage/tdg only, no query-result correctness impact.

**Shares code with** — Self-contained in lineage/ScanRelations.java; no overlap with Typer/Lowerer/PlanText code other buckets blame.

---

### 65. Bare lambda blocks resolution of self-contained sub-queries (assert/forAll idiom)

**2 tests** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 2

Tests: `testVariableReferenceInFilterWithSameNameAsThatInParentProject`, `testVariableReferenceInMapWithSameNameAsThatInParentProject`

**Mechanism** — EngineTestExecutor.checkAssert's assert arm evaluates only args.get(0); evalSpliced compiles `$expected->forAll(e|$results->contains($e))` with $results β-reduced and $tds spliced to the ExecFrame's deliberately UNRESOLVED chain. Anchors.spaceOf classifies the forAll as ANCHORED, so anchoredNode takes the structural TypedNativeCall arm and maps children through resolveNode — but the child carrying the query is the predicate TypedLambda, and anchoredNode's `case TypedLambda l -> l;` returns it VERBATIM ('a bare lambda VALUE is DATA'). forAll's structural arm is not a consumer that resolves lambda bodies, so the embedded TypedFrom chain is never resolved and assertNoStoreOnlyEscapees fires. The queries themselves are fine — the eager execute() run succeeded; traversal simply never reaches them.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:225-228, 297-307, 504; core/src/main/java/com/legend/resolver/SubQueryLift.java:97-120

**Fix** — Replace the arm at StoreResolver.java:504 (`case TypedLambda l -> l;`) with one that maps children through a new private `resolveClosedQueries(node, boundVars, context)`: it accumulates lambda parameters into the bound set as it descends, and when it meets a TypedFrom that is closed — uncorrelated and reading none of the bound variables — calls resolveNode on it; otherwise it recurses via mapChildren, which returns the same node when nothing changed, so ordinary predicates/mappers still pass through verbatim. Use the shadow-aware closedness test that already exists, SubQueryLift.uncorrelated (SubQueryLift.java:97-120), promoted to package-visible, passing the accumulated bound set plus letBindings.keySet() as its own caller does. A TypedFrom carries its own mapping+runtime so resolveNode already handles it (297-307). Also make assertNoStoreOnlyEscapees (225-228) name the enclosing lambda instead of claiming the shape is unsupported.

**Leverage** — One localized arm change fixes two tests and the whole assert/forAll/contains driver idiom across the corpus — likely the highest test-per-line return in this bucket.

**Shares code with** — StoreResolver.anchoredNode plus StatementExecutor's unresolved ExecFrame contract — any bucket whose failure is 'store-only escapee' under a lambda ancestry is this same arm.

---

### 66. Union routing and collision guard blind to embedded property blocks

**2 tests** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: 

Tests: `testAdvancedEmbeddedInMappingQuery (union::unionMappingWithEmbeddedProperty2)`, `testAdvancedEmbeddedInMappingQuery (union::extend::unionMappingWithEmbeddedProperty2)`

**Mechanism** — An embedded block declares two class-typed Join sub-PMs for the SAME property routed to two union members (bridge(employees[set1], employees[set2])). Two defects combine. (1) The first sub-PM mints nav slot alias 'employees'; the second trips JoinChainEmission's name-only collision guard on the slot its own sibling just created — top-level same-named routed siblings never hit this because they take the plain Join arm and dedup harmlessly, which is why unionToUnionMapping passes. (2) UnionSynthesis.classifyUnionRoutes only scans rcm.propertyMappings() and never descends into Embedded/Otherwise/Inline bodies, so unionRoutes has no 'employees' entry and even without the guard the navigate would carry one member's join. The throw is caught by per-class fault isolation, recorded as a mapping poison, and resurfaces as 'class Firm is not mapped'. The extends variant re-emits the same inherited block into the same guard.

**Owning code** — core/src/main/java/com/legend/normalizer/JoinChainEmission.java:119-144 (Embedded guard), :103-113, :171-180, :549-571 (mintNavSlotAlias), :415, :294-299 (dedup); core/src/main/java/com/legend/normalizer/UnionSynthesis.java:197-301

**Fix** — (A) Replace classifyUnionRoutes' flat scan with a recursive walk that threads the OWNER CLASS: collect (ownerClassFqn, Join with targetSetId != null); for Embedded recurse with owner = findPropertyTypeDeep(owner, propertyName, model); for OtherwiseEmbedded recurse into embedded(); for InlineEmbedded use the referenced set's className(); for LocalProperty recurse into body(). Group by property name as today but resolve targetClass via findPropertyTypeDeep on the PAIR's owner, not rcm.className(). (B) Make the collision guard owner-aware: add Pipeline.navSlotOwner (property -> ownerClassFqn) recorded in mintNavSlotAlias, and fire all three guards only when the recorded owner differs from the current level's owner class. Same-owner same-name siblings then fall through to emitJoinChain's existing dedup, emitting one routed navigate carrying the OR over unionRoutes. Use the CLASS fqn as the owner key, not the set id, so inherited and parent blocks agree. No change to materializeEmbedded.

**Leverage** — Two tests directly, plus byte-identical poison on lineage/scanRelations testUnionToUnion and a candidate in testDataGeneration. Fixing a whole mapping's poison, so payoff scales with how many queries bind it.

**Shares code with** — Failure surfaces as a mappingPoison rethrown from ClassSources.java:622-625 ('class X is not mapped in mapping Y (<poison>)'). Any bucket whose test dies with that wrapper on unionMappingWithEmbeddedProperty2 is this cluster, not a query-side bug.

---

### 67. ClassSource pipeline emits navigate steps before DataType-column join slots, plus the %latest k_ literal spelling

**3 tests** · effort **L** · confidence high · bucket 04 (?) · verdicts: GOLDEN_TEXT_ONLY 3

Tests: `testMilestoningQueryWithMilestoneFilterAndDifferentDatesOnTypeWithLatestDateOnProperty`, `testLatestMilestoneDatePropogationFromTypeQueryDoesNotOverrideThatSpecifiedAsArgToMilestonedQpInFilter`, `testLatestMilestoneDateMappedTableDateDoesNotOverrideLatestDateFromChildPropertyInPropogation`

**Mechanism** — legend-lite renders the ClassSource pipeline in mapping-declaration order and EngineStyleH2.planSource numbers aliases by left-to-right traversal, so a mapping that declares class-typed navigations before its join-slot columns (milestoningmap Product: classification at slot 4, stockProductName/classificationType at 9/10) emits the filter's joins first. The engine seeds the join tree from the class's DataType property columns at getAll (processRelationalMappingSpecification, pureToSQLQuery.pure:4766-4790) and appends every predicate join afterwards (processFilter's left side first, :5062). The result is an exact permutation with consistent alias renaming — no row can differ. Two of the three additionally diverge on the k_businessDate literal, where the engine projects the mapped table's INFINITY_DATE as a STRING ('9999-12-31T00:00:00.0000+0000') and legend-lite emits the hard-coded TimestampLit sentinel.

**Owning code** — core/src/main/java/com/legend/resolver/Pipelines.java:67-113 (sinkNavSteps chain-rebuild machinery) and :341-434 (materialize/walkJoinSlot preserve structural order); core/src/main/java/com/legend/normalizer/MappingNormalizer.java:96-101 (documents the join(...)* then legacyNavigate(...)* order the emission does not honour); core/src/main/java/com/legend/resolver/StoreResolver.java:1761-1764; core/src/main/java/com/legend/lowering/Lowerer.java:2214-2215 and core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:909-911 for the literal half

**Fix** — Two changes, landed separately, never bundled. (b) Ordering: enforce at construction in MappingNormalizer the pipeline shape it already documents, or apply a stable partition to cs.pipeline() at StoreResolver.materializeRoot reusing the Pipelines.sinkNavSteps chain-rebuild loop with the predicate `step instanceof TypedJoinSlot`, so all join slots precede all navigate steps. (a) Literal: in GraphEmission.generatedDateLeaf, when the context date is TypedCLatestDate and TemporalFrame.latestAliasLiteral is non-null, return that literal (keep it DATE-typed as a TypedCDate over the parsed INFINITY_DATE) and add a TimestampLit arm to EngineStyleH2.projection so it spells plainly with the +0000 suffix. (b) alone closes the first test; (a)+(b) are both required for the other two.

**Leverage** — Three tests here, but (b) permutes alias numbering across the ENTIRE corpus and will move a large number of currently-advisory sql diffs in both directions, including which alias group a correlated/EXISTS subquery consumes. Only worth doing if the sql-only bucket is being deliberately burned down, and only as its own change with a full before/after sweep. Change (a) is small, independently correct (it also fixes a latent wrong-value case for tables declaring INFINITY_DATE=%9999-12-31), and shares the latestAliasLiteral helper with the non-milestoned-k_-column cluster — land (a) with that cluster and treat (b) as a standalone corpus-wide decision.

**Shares code with** — resolver/Pipelines.java materialize/sinkNavSteps, normalizer/MappingNormalizer.java:96-101, and lowering/Lowerer.java:2214 + EngineStyleH2.java:909. Any bucket whose diff is 'same joins, same predicates, different order/alias numbering' is change (b) — those must all be counted before (b) is attempted. Any bucket showing TIMESTAMP'9999-12-31 00:00:00.0000' where a golden says '9999-12-31T00:00:00.0000+0000' is change (a).

---

### 68. TDS.csv undeclared and no relation-result CSV renderer

**1 test** · effort **M** · confidence medium · bucket 03 (?) · verdicts: MISSING_FEATURE 1

Tests: `testEnumInRelation`

**Mechanism** — The assert reads `.csv` off a TDS cast. The harness's serialization-tail strip only matches an AppliedFunction named toCSV/toString, so a `.csv` property read is never intercepted and the whole expression is compiled by the platform. The TDS metaclass is declared with an empty body, so the Typer property step throws 'class meta::pure::metamodel::relation::TDS has no property csv'. Both the declaration and the rendering behind it are absent; real Pure declares csv: String[1] on TDS and the engine reads it in toPureGrammar.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:211 (TDS declared `{}`); core/src/main/java/com/legend/StatementExecutor.java:2687-2706 (spliceValuesRead, the seam the .csv arm sits on); wall emitted at compiler/spec/Typer.java:2566 and :2579; harness context core/src/main/java/com/legend/harness/EngineTestExecutor.java:2603-2614 (toCSV-only strip), :2666-2679 (evalSpliced), :3136-3158 (csvText, a different format), :3160-3208 (csvEquals order policy)

**Fix** — Declare `csv: String[1]` on the TDS metaclass at Pure.java:211, and add a terminal serialization arm in StatementExecutor beside spliceValuesRead (:2687-2706): a TypedPropertyAccess with property 'csv' whose source, after unwrapping TypedCast and the .values splice, resolves to an execute frame evaluates the chain to ExecutionResult.Tabular and returns the rendered String. Put the renderer in a shared com.legend.exec helper implementing the TDS.csv convention (header joined ', ', rows joined ',', null cell 'TDSNull', ISO dates, enum cells as VALUE NAME, lines joined '\n' with no trailing newline) and have EngineTestExecutor's toCSV path share the cell-rendering helper rather than duplicating it. Reuse csvEquals' existing order policy for the compare; keep a loud wall for a .csv read whose receiver is not execute-backed. Do not extend the harness's toCSV strip to match an AppliedProperty.

**Leverage** — Worst leverage in the bucket: M effort, medium confidence, and the diagnosis explicitly verified that `.csv` on a TDS appears exactly once in the whole core_relational/relational tree (testEnumerationMapping.pure:142) — literally zero follow-on tests. Confidence is only medium because the query is a relation projection over enumeration-mapped columns with a case() transform and a constant, so csv may be the first wall rather than the only one. Ledger this as out-of-scope unless the shared cell renderer is being built anyway for another reason.

**Shares code with** — builtin/Pure.java:211 is a distinct metaclass from cluster 2's :406/:382/:346 — the two Pure.java clusters are NOT one change; merge only on the specific declaration. StatementExecutor.java:2687-2706 spliceValuesRead is the generic execute-frame seam: any other bucket adding a terminal read/serialization over a `.values` splice extends the same dispatch and should merge. The shared cell-rendering helper (null -> TDSNull, ISO dates, enum value names) is the real reusable piece — buckets failing on toCSV/toString output formatting of tabular results touch the same code.

---

### 69. Multi-column TDS cell flatten loses its marker at the Typer, so `rows.values->sort()` has no cross-kind ordering path

**1 test** · effort **M** · confidence medium · bucket 08 (?) · verdicts: REAL_DEFECT 1

Tests: `testProject`

**Mechanism** — NOTE: the brief's `source` field is wrong — tests/injection/testInjection.pure:50 contains no `sort`; the failing test is meta::relational::tests::mapping::dates::strictdate::testProject at tests/mapping/dates.pure:57 (the only multi-column `rows.values->sort()` in the corpus). The assert is `assertEquals([1..15, %2014-12-01 ...], $result.values.rows.values->sort())` over a 2-column project. In Pure, TDSRow.values : Any[*] (tds.pure:79), so `$tds.rows.values` auto-maps to a row-major heterogeneous cell list and `sort()` orders it with the TYPE-CLASS-MAJOR default comparator (numbers, then dates, then booleans, then strings — legend-pure Compare.java:51,75-110), which is exactly why the expected list is 1..15 then the dates. legend-lite loses the flatten in the Typer: the `.values`-over-RelationType arm synthesizes per-column cells for a ROW VARIABLE receiver but falls through to `return source;` (pure identity) for a RELATION VALUE receiver (Typer.java:2429-2463), deferring the flatten to the wire (EngineTestExecutor.Eval.values() concatenates r.values() per row). The `->sort()` therefore sits ABOVE that identity in RELATION space, where Lowerer.java:514 routes 1-arg sort only through ValueCollectionOps.isBareSingleColumnSort, which requires columns().size()==1 (ValueCollectionOps.java:33-41). With 2 columns the guard is false and control lands on the frontier default at Lowerer.java:524. The wall is honest: legend-lite has the flatten carrier (ValueCollections.rowMajorCellList:52-68) and the Any-LUB variant literal carrier (Lowerer.java:2225-2233) but NO cross-kind ordering anywhere — Scalars.mixedElems bails unless the LUB is NUMBER or DATE (Scalars.java:2501-2512).

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2463 (`.values` over RelationType, ends `return source;`); core/src/main/java/com/legend/lowering/Lowerer.java:514 (only relation-position sort arm), :524 (frontier default), :494-500 (ROWS_MARKER erasure mirror site); core/src/main/java/com/legend/resolver/StoreResolver.java:320-326 (other ROWS_MARKER erasure); core/src/main/java/com/legend/lowering/ValueCollectionOps.java:29-41 (isBareSingleColumnSort + stale javadoc); new file core/src/main/java/com/legend/lowering/CellOrder.java

**Fix** — Three coordinated edits, nothing in the harness. (1) Stop erasing the flatten at the Typer: replace `return source;` at Typer.java:2463 with an identity-TYPED marker mirroring the `.rows` marker three lines above (Typer.java:2416-2422) — `new TypedPropertyAccess(source, PlatformTypes.CELLS_MARKER, source.info())`, adding CELLS_MARKER beside ROWS_MARKER at PlatformTypes.java:23; keeping source.info() means no downstream type changes. (2) In the SAME change, erase the new marker everywhere ROWS_MARKER is erased — StoreResolver.java:321-326 and the defensive floor in Lowerer.relation() at Lowerer.java:494-500 — so every currently-green shape stays bit-identical. (3) Add a relation-position rule immediately BEFORE the isBareSingleColumnSort arm at Lowerer.java:514: `case TypedNativeCall nc when CellOrder.isCellSort(nc) -> cellSort(nc);` where isCellSort is a 1-arg meta::pure::functions::collection::sort whose single arg is the CELLS marker over a RelationType with >1 column. cellSort must NOT reuse rowMajorCellList + LIST_SORT (TO_VARIANT renders to_json, DuckDb.java:262, and DuckDB JSON is text-backed, so list_sort orders "2014-12-01" before 1 and 10 before 2 — wrong rows, not a wall). Emit a one-column VARIANT-carrier relation from a per-column UNION ALL carrying Pure's comparator as explicit sort keys (_k kind rank, then typed _n/_d/_b/_s columns, ORDER BY _k,_n,_d,_b,_s), with pureKindRank transcribing legend-pure Compare.PRIMITIVE_TYPE_COMPARISON_ORDER (numbers 0, dates 1, boolean 2, string 3) in ONE place (new com.legend.lowering.CellOrder) for reuse. Also correct the now-stale ValueCollectionOps.isBareSingleColumnSort javadoc (:29-32): the flattened CELL sequence of a multi-column relation DOES have a Pure-defined order; it is multi-column ROWS that have none.

**Leverage** — Low, and the diagnosis says so explicitly: the agent grepped every `rows.values->sort()` in the corpus (dates.pure:104, testAssociationMappingInheritance.pure:73, testInheritanceRelational.pure:64, testInheritanceRelationalUnion.pure:65, testQueryStructure.pure:315, and seven in testTDSRestrictDistinct.pure) and ALL of them are single-column projections already served by the existing arm. dates.pure:57 is the only multi-column one, so this M-effort fix is worth exactly one test. It also carries an UNVERIFIED half — nobody confirmed a Tabular column of to_json values decodes back to TYPED Java values at the wire, so the fix may land red on a decode issue rather than an ordering one — and edit (1) has a real regression surface (`.values` over a relation is a very common shape; any consumer matching on node identity rather than info().type() now sees a wrapper). Recommend ledgering: the current wall is honest and strictly better than the cheap alternatives. Confidence is marked medium for the FIX (the mechanism itself is high confidence) because of that unverified wire-decode half.

**Shares code with** — compiler/spec/Typer.java:2429-2463 (`.values` over a Type.RelationType receiver) and the ROWS_MARKER/PlatformTypes marker-erasure protocol spanning StoreResolver.java:320-326 and Lowerer.java:494-500 — any bucket touching TDS `.rows`/`.values` shapes shares that protocol and edit (1) must be coordinated with them. lowering/Lowerer.java:524 is the shared frontier default (match on the class name + callee in the message, not the line). lowering/Scalars.java:2501-2512 (mixedElems bails unless LUB is NUMBER or DATE) is the generic 'no cross-kind ordering exists anywhere' gap — any other bucket asserting a sorted heterogeneous Any[*] collection shares it and the proposed CellOrder.pureKindRank should be the single home for that comparator. Harness note for cross-bucket triage: EngineTestExecutor.java:3409-3430 (endsInSort) and :2841 (ordered && sortedChain) are the tenet-2 trap — do not let any bucket 'fix' an ordering gap by relaxing them.

---

### 70. Root-position `->distinct()` over a relation-derived collection lowers as a list dedup lambda

**1 test** · effort **M** · confidence high · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testCollectionDistinctFunction`

**Mechanism** — DistinctChecker forks at G-phase on whether arg0 is a RelationType (DistinctChecker.java:34-37). For `Trade.all()->filter(...)->map(t|$t.product.name)->distinct()` the source is still a CLASS stream at G (String[*]), so it correctly emits the COLLECTION overload as a plain TypedNativeCall — and nothing re-decides after Phase H turns the stream into a relation. `Lowerer.lower(TypedSpec)` (Lowerer.java:204-256) has arms for TypedFrom / TypedSerializeGraph / TypedConcatenate / RelationType-typed / TypedMap-over-relation, none of which match a distinct native call, so it falls to `scalarRoot(spec)` (Lowerer.java:262-278) and routes to `Scalars.RULES.get(Pure.DISTINCT_COLLECTION_KEY)` = `orderedDedup` (Scalars.java:1383, 2428-2436), which builds `list_filter(<list>, (_ddx,_ddi) -> list_position(<list>,_ddx) = _ddi)`. `<list>` is the scalar lowering of the inner TypedMap-over-relation = `SqlExpr.ScalarSubquery` (Lowerer.java:2695-2730), so a subquery lands inside a SQL lambda body and DuckDB's binder rejects it. legend-lite already knows this failure mode (ValueCollectionOps.java:19-21) but its relation-space escape hatch is registered only for removeDuplicates/sort and only in scalar position (ValueCollectionOps.java:63-83), and InnerDemand already does the right thing for INNER chains (InnerDemand.java:503-521) — only the ROOT lacks it.

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:204-256 (add a root arm before the TypedMap arm at :230); gate helper alongside; leaves core/src/main/java/com/legend/lowering/Scalars.java:1383 intact for genuinely list-valued args

**Fix** — Add a root arm to `Lowerer.lower(TypedSpec)` after the TypedFrom/TypedSerializeGraph/TypedConcatenate unwraps and before the TypedMap arm: if the spec is a 1-arg `distinct`/`removeDuplicates` TypedNativeCall whose argument is relation-rooted (arg is RelationType-typed, or a TypedMap whose source is RelationType-typed), lower the argument and return `Fold.distinctFolds(s) ? s.withDistinct() : isolate(s).withDistinct()` for a SqlSelect, or a non-distinct SqlUnion rebuild for a SqlUnion (UNION already dedups). All referenced machinery exists (Fold.java:371, SqlSelect.java:87, Lowerer.isolate/union, Pure.nativeNamed). Keep the gate strictly relation-rooted — do not widen to 'any many-multiplicity arg', which would hijack literal-list/split dedups away from orderedDedup and silently change first-occurrence ordering.

**Leverage** — Single test, but genuinely worth it: the fix is additive (a new arm, no existing rule weakened), matches legend-engine's processDistinct exactly (pureToSQLQuery.pure:8171-8180, 9974), and closes the structural gap where the ROOT is the only position lacking the relation-level distinct that InnerDemand already applies to inner chains. Plausibly also unblocks the `Firm.all()->filter(...).legalName->distinct()` shapes in tests/advanced/testRelationalResultSourcing.pure — unverified, not claimed.

**Shares code with** — Touches lowering/Lowerer.java's root `lower(TypedSpec)` switch and reads lowering/Scalars.java + lowering/ValueCollectionOps.java. Any bucket whose failure is 'subqueries in lambda expressions are not supported' from a list_filter/list_transform/list_position idiom shares the ROOT MECHANISM (list-space rule over a relation-derived collection) even if the function differs — sort/removeDuplicates/isEmpty siblings would extend the SAME new arm or the same ValueCollectionOps registration, so merge on the relation-space-rewrite decision rather than on the function name.

---

### 71. Declared-type wire cast is emitted into SQL in operand position (should be host-side / result-position only)

**1 test** · effort **M** · confidence high · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testInWithDynaFunction`

**Mechanism** — MappingNormalizer.coerceToDeclaredNumeric wraps any expression property mapping whose declared kind is in {Float,Integer,Decimal,Number,DateTime,StrictDate,Boolean} in `Pure.Lite.CAST_AS_DECLARED` (MappingNormalizer.java:2325-2338). Typer turns that into a wire-flagged TypedCast (Typer.java:1141-1146), and Lowerer.cast erases it only inside the engine-text funnel: `if (c.wire() && EngineTextBoundary.active()) return value;` (Lowerer.java:3158-3164). Under EXECUTION the boundary is never active, so a real SQL cast is always emitted — including when the property read is an OPERAND. For `Interaction.active` (declared Boolean[1], mapped to a VARCHAR dynafunction, relationalSetUp.pure:881 / simpleTestModel.pure:258) this yields `CAST(CASE WHEN t0.active='Y' THEN 'true' ELSE 'false' END AS BOOLEAN) IN ('false','something')`; DuckDB coerces the IN-list to BOOLEAN and dies on 'something'. The gating axis is simply the wrong one: it is 'engine-text vs execution' when it should be 'result position vs operand position'. legend-engine never casts in SQL — the coercion is a host-side result-set transformer (SetImplTransformers.java:61-77, wired at RelationalResult.java:290).

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:3157-3164 (the `c.wire() && EngineTextBoundary.active()` early return); origin at core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2325-2338 and core/src/main/java/com/legend/compiler/spec/Typer.java:1141; policy note at core/src/main/java/com/legend/lowering/EngineTextBoundary.java:1-38

**Fix** — Make the wire coercion positional rather than funnel-scoped. Preferred: stop emitting it in SQL entirely — strip the wire cast unconditionally in `Lowerer.cast` (which also makes the EngineTextBoundary special case redundant) and record the declared kind on the output column so exec/ converts on decode using the engine's own rule (String -> Boolean.parseBoolean; Number -> !=0), matching SetImplTransformers.toBoolean; that simultaneously retires the documented divergence at MappingNormalizer.java:2318-2323. Minimum correct variant if host-side decode is too large: in Lowerer.cast return the bare value for `c.wire()` in every operand position (comparison, IN/membership, arithmetic, join condition, filter predicate) and keep the cast only for select-list/result columns. Do NOT drop Boolean from the coercion set at MappingNormalizer.java:2329 — the projected column would come back as the string 'false' and break the row assert.

**Leverage** — Single test in this bucket, but high value and cheap in its minimal form (one branch in one lowering rule). It is a correctness bug, not an internals assertion: any comparison/IN against a declared-vs-column mismatched property is currently either erroring or silently type-coerced. The full host-side-decode variant touches every declared-vs-column mismatch in the corpus (String-declared over numeric, DateTime over StrictDate), so its blast radius is much wider than one test — stage it behind the castAsDeclared node so the surface stays one lowering rule.

**Shares code with** — Touches lowering/Lowerer.java:3157 (cast), compiler/spec/Typer.java:1141 (the wire flag), and normalizer/MappingNormalizer.java:2325 (the coercion set). Any bucket whose failure is a SQL type mismatch on a mapped property whose DECLARED type differs from its column/dynafunction type — wrong-typed IN lists, BOOLEAN/VARCHAR comparisons, DateTime-vs-StrictDate comparisons, or 'engine-text golden has no cast but we emit one' golden drift — is the same mechanism and merges here. Also merge any bucket proposing host-side result decode in exec/.

---

### 72. Class-collection `concatenate` at the query root is not recognised as the union set-op

**1 test** · effort **M** · confidence high · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testAll`

**Mechanism** — `StoreResolver.anchoredNode` has exactly one arm recognising a class-collection concatenate, and it requires a TypedProject sitting above it (`case TypedProject p when classConcatOf(p.source()) != null ->`, StoreResolver.java:355). A BARE root concatenate (`Product.all()->concatenate(Product.all())`) has no project above it, so it falls through to the catch-all `case TypedNativeCall nc -> structural(Pipelines.classEmptinessRewrite(...))` (StoreResolver.java:477-478), which only resolves the arguments and leaves concatenate as an ordinary Pure native call. Lowering then applies the SCALAR collection rule (Scalars.java:1635-1657, `SqlExpr.Call(SqlFn.LIST_CONCAT, args)`) over two class-typed extents whose scalar encoding is a JSON-object scalar subquery — hence `list_concat(JSON, JSON)`, which DuckDB's binder rejects since it only has `list_concat([ANY[]...])`. Scalars.java:1630-1631 states the invariant being violated verbatim: 'the relation overload is the TypedConcatenate set-op and never reaches scalar lowering'.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:477 (new arm inserted above the catch-all TypedNativeCall arm), mirroring StoreResolver.java:355; gate helper classConcatOf at StoreResolver.java:1196; consumer Lowerer.union(TypedConcatenate) at core/src/main/java/com/legend/lowering/Lowerer.java:534

**Fix** — Add an arm to `StoreResolver.anchoredNode` ABOVE the generic TypedNativeCall arm at :477: `case TypedNativeCall nc when classConcatOf(nc) != null && nc.info().type() instanceof Type.ClassType ->` yielding `new TypedConcatenate(resolveNode(arg0), resolveNode(arg1), nc.info())`. `Lowerer.union(TypedConcatenate)` (Lowerer.java:534) already turns that into UNION ALL. Two things must travel with it: (a) column-align the arms before the union (engine does this in alignJoinAndPkColumnsForUnion, pureToSQLQuery.pure:2740) — align by name in Lowerer.union if legend-lite's class envelope is not already positionally identical; (b) make the class-result decode path accept a union-rooted class envelope so `$result.values` materialises 8 Product instances rather than one. Keep classConcatOf's `anchored()` gate and the ClassType gate so scalar concatenates keep the Scalars LIST_CONCAT path and TDS concatenates are untouched.

**Leverage** — Single test, medium effort, and the effort is understated by the arm itself — the arm is ~6 lines but the union column-alignment and the union-rooted class-envelope DECODE are the real work. Worth doing because it closes a structural hole (the union set-op only exists under a project) and may pick up the other bare-class concatenate shapes in functions/tests (testConcatenateInQualifierWithComplexReturnType, testConcatenateFlatWithOtherProperty) — but those are filter/qualifier-position and explicitly unproven, so do not budget on them.

**Shares code with** — Touches resolver/StoreResolver.java's anchoredNode switch (the arm-ordering discipline: narrow arms must precede the catch-all TypedNativeCall arm at :477) and lowering/Lowerer.java:534 union + the class-result decode path in exec/. Any bucket blaming a class-typed operation that silently degraded to a scalar/JSON encoding because anchoredNode fell through to the catch-all arm is the same mechanism. Also flags Lowerer.union column alignment, which any other union-shaped cluster would touch.

---

### 73. Flat-cells `.rows.values` read of an executed TDS lowers as a bare scalar subquery, not a value collection

**1 test** · effort **M** · confidence medium · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testProject`

**Mechanism** — The assert is `[123.456,100.001]->zip($result.values->cast(@TabularDataSet)->at(0).rows.values)->forAll(...)`, and the harness compiles the whole statement through the platform (EngineTestExecutor.evalSpliced -> Compiler.executeResolved, EngineTestExecutor.java:2666-2679), so `zip` becomes SQL: `least(coalesce(len(a),0), coalesce(len(b),0))` (Scalars.java:1146-1171) with LIST_LENGTH spelled `len` (Spellings.java:83). Side a is an array literal (fine). Side b is the flat-cells read, which lowers to a BARE SCALAR SUBQUERY of type DOUBLE — hence `len(DOUBLE)` and no matching function. Why: the Typer erases `.rows` to an identity marker carrying the SOURCE's multiplicity (Typer.java:2418-2427) and returns the source unchanged for `.values` on a relation (Typer.java:2429/2463), and StatementExecutor's splice collapses `$r.values->at(0)` to the exec frame's chain with the chain's own stamp (StatementExecutor.java:2549-2571). A relation VALUE is stamped [1] (ProjectChecker.java:67), so in Lowerer.scalar's single-column relation-in-scalar arm `toMany` is false (Multiplicity.isToOne() is upper==1, Multiplicity.java:104-106) and it yields `ScalarSubquery(relation(rel))` instead of the list-aggregating ValueCollections.columnList route. The multi-column arm directly above already refuses to trust that stamp ('a [1] stamp on a relation VALUE is the value's mult, not the row count'); the single-column arm still trusts it. Even if len bound, the bare subquery would raise 'more than one row' on this 2-row TDS.

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:2758-2795 (single-column relation-in-scalar arm; the toMany computation at :2779-2785 and the ScalarSubquery at :2786); marker plumbing at core/src/main/java/com/legend/compiler/spec/Typer.java:2429-2463 (and the .rows marker at :2418-2427), with erasures at core/src/main/java/com/legend/resolver/StoreResolver.java:322-327 and core/src/main/java/com/legend/StatementExecutor.java:2490-2495

**Fix** — Make the flat-cells read honest rather than patching zip. Preferred: mirror the existing `.rows` marker discipline for `.values` on a relation — return `new TypedPropertyAccess(source, "values", new ExprType(rt2, Multiplicity.Bounded.ZERO_MANY))` from Typer.java:2429-2463 instead of the bare source; erase that marker beside the ROWS_MARKER erasures (StoreResolver.java:322-327, StatementExecutor.java:2490-2495); add two Lowerer arms — in relation() erase it to `relation(pa.source())` (beside Lowerer.java:495-499), and in scalar() lower it as a VALUE COLLECTION (`ValueCollections.rowMajorCellList` for >1 column, `ValueCollections.columnList` for exactly one), never the correlated-scalar route. Minimal alternative: at Lowerer.java:2779-2785 force `toMany` for a relation whose stamp is exactly [1] (lower==1 && upper==1), leaving the correlated-scalar route to the [0..1] nav encodings the comment names — one line, but it changes every exactly-[1] single-column relation in scalar position and needs a corpus sweep. Do NOT wrap zip's arg in an ArrayLit the way the covar rules do (Scalars.java:1785-1790): that yields a 1-element list holding a 2-row subquery — a runtime error and, worse, a silently truncated zip.

**Leverage** — Nearly a singleton by the diagnosis's own admission — the multi-column `.rows.values` spelling already works, so only ONE-column TDS reads inside a scalar function (zip/contains/in/makeString) are affected. It is also partly an assertion-plumbing fix (making corpus asserts that re-execute through the platform behave), not a query-correctness win. Cheap-looking but unfinished: even after this, the rest of the expression (list_transform over range + StructGet at Lowerer.java:3014-3029, forAll -> LIST_FOR_ALL at Scalars.java:334) is 'plausible but unverified'. Rank it below the union prune and the root-distinct arm.

**Shares code with** — Touches lowering/Lowerer.java:2779-2787 — THE SAME BRANCH as the filtered-navigation cluster above, and the minimal variant here (force toMany when the stamp is exactly [1]) must be checked against that cluster's requirement that [0..1] navs KEEP the correlated-scalar route; the two are compatible only if the discriminator is exactly-[1] vs [0..1]. Also touches compiler/spec/Typer.java:2418-2463 (.rows/.values markers) and StatementExecutor.java:2549-2571 (the $r.values splice). Any bucket blaming the $result.values / TDS re-execution splice, or a [1]-stamped relation consumed as a scalar, merges here.

---

### 74. sqlstring surface: native meta::relational::metamodel::SQLQuery + platform-owned toSQL/sqlQueryToString

**1 test** · effort **M** · confidence high · bucket 01 (?) · verdicts: MISSING_FEATURE 1

Tests: `testPushFiltersDownToJoinsPostProcessorToSQL`

**Mechanism** — Every `sqlQueryToString` overload and the `SQLResult.sqlQueries` property are typed over `meta::relational::metamodel::SQLQuery`, a legend-pure platform class (relational.pure:200) that legend-lite's native catalogue does not carry. TypeClassifier.classify throws `Unknown type: 'SQLQuery'`, FunctionCompiler.compileAll drops every overload and rethrows, and the whole `toSQL(...).sqlQueries->at(0)->sqlQueryToString(...)` chain never compiles. The SQL TEXT this test asserts is already produced byte-identically by legend-lite's EngineStyleH2 renderer (the 909-char golden on testPostProcessor.pure:401 is shared with two passing tests), so only the API surface is missing.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:426 (SELECT_SQL_QUERY parented off RelationalOperationElement, no SQLQuery base); core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 (the throw); core/src/main/java/com/legend/compiler/element/FunctionCompiler.java:119-121 (drop-all-overloads rethrow); core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:218-230 (isPlatformOwnedFunction); core/src/main/java/com/legend/StatementExecutor.java:359-396 (the toSQLString K-arm to clone)

**Fix** — One additive change in three files: (1) Pure.java — add `native Class meta::relational::metamodel::SQLQuery extends ...RelationalOperationElement { comment: String[0..1]; }` and re-parent SELECT_SQL_QUERY (:426) onto it; add native signatures for `sqlstring::toSQL` and `sqlQueryToString::sqlQueryToString`. Leave SQLResult as the corpus class. (2) PlatformTypes.java — add TO_SQL / SQL_QUERY_TO_STRING to isPlatformOwnedFunction so the corpus Pure bodies are suppressed exactly as TO_SQL_STRING's are. (3) StatementExecutor.java — two K-native arms beside toSqlString: `toSQL` runs the same engineSql(...) pipeline but DEFERS rendering, returning an opaque handle over the lowered com.legend.sql.SqlQuery list (`.sqlQueries` yields the list, `cast(@SelectSQLQuery)` is an identity peel); `sqlQueryToString` picks the renderer with the identical dbType switch at :369-378 and renders. No harness change.

**Leverage** — Highest leverage in the bucket. One M-effort platform change greens this test outright (the SQL content is already verified-correct), and the SQLQuery class alone is the hard prerequisite for 5 other clusters in this bucket (testDb2ColumnRename, testPostProcessTransformJoinOp, testTempTableSqlStatementsForH2, and the two pureToSQLQuery ledger clusters). Land this first — it also converts several other rows from a lying wall to an honest one, which the sweep should be measured on rather than assumed positive (adding SQLQuery un-walls many corpus signatures, so some tests will fail DEEPER, not better).

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:426 and core/src/main/java/com/legend/StatementExecutor.java:359-396/369-378. Any bucket whose failures cite `Unknown type: 'SQLQuery'` from TypeClassifier.java:91-92 — explicitly named: sqlQueryToString/*, DDL/testDDL.pure, postprocessor/*, pureToSQLQuery/* — shares this exact change and must be merged here.

---

### 75. dataTypeToSqlText over Pure-constructed datatype instances (translateCoreTypeToDbSpecificType)

**1 test** · effort **M** · confidence medium · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testTranslateDbType`

**Mechanism** — `^meta::relational::metamodel::datatype::Varchar(size = 100)` is fully qualified, so name resolution is not involved — NewChecker.check simply finds no such class in the native catalogue or the model and throws. Behind that, dataTypeToSqlText is implemented over legend-lite's OWN Java datatype record: MetamodelWalk.sqlText returns null unless the receiver is a `Dt`, and the only producer of Dt is MetamodelWalk.infer — so a Pure-constructed ^DbSpecificDataType would yield null, not 'STRING(100)'. And translateCoreTypeToDbSpecificType stores its translator in a Function-typed property, ->evals it and constructs ^DbSpecificDataType, none of which is in HostEval's scoped channel.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:351 (datatype prelude), :1496 (dataTypeToSqlText native); core/src/main/java/com/legend/compiler/spec/NewChecker.java:68-69 (the throw); core/src/main/java/com/legend/exec/MetamodelWalk.java:1546-1551 (`if (!(r instanceof Dt d)) return null;`) and :1219-1227 (the only Dt producer); core/src/main/java/com/legend/StatementExecutor.java:1413-1415 (the K-dispatch); core/src/main/java/com/legend/compiler/NameResolver.java:532-593 (primitive-first tier); core/src/main/java/com/legend/exec/HostEval.java:36-44 (scoped channel)

**Fix** — On top of the shared datatype-prelude + NameResolver primitive-tier sub-change (which must include CoreDataType and DbSpecificDataType{coreDataType, dbSpecificSql}), make `dataTypeToSqlText` TOTAL over Pure-constructed instances: add an arm to MetamodelWalk.sqlText (:1546) that accepts an instance value whose class FQN is under `meta::relational::metamodel::datatype::` and spells it per legend-pure functions.pure:68-96, in particular `DbSpecificDataType -> $d.dbSpecificSql`. Only then does the residual gap show: host-evaluating a lambda-valued property + ->eval + ^DbSpecificDataType construction inside HostEval.

**Leverage** — Marginal-cost-only if the datatype prelude lands anyway; the tail (sqlText totality) is small and genuinely useful, but the diagnosis is explicit that step 4 (HostEval evaluating a Function-typed property) may be out of scope and is what actually decides whether the row goes green. Treat as: land the sqlText totality because it makes the surface honest, and accept the row may still wall at HostEval. Note the diagnosis's own note that a latent mis-resolution already exists today in protocols/pure/v1_33_0/models/metamodel_relational.pure:207/223 that the primitive-first tier fixes independently of this test — that is the real payoff of step (2).

**Shares code with** — Pure.java:351 + NameResolver.java:532-593 shared with the temp-table and testTempTableSqlStatementsForH2 clusters. MetamodelWalk.java:1546-1551 and StatementExecutor.java:1413-1415 are the dataTypeToSqlText path — merge with any bucket whose failures mention dataTypeToSqlText returning null/empty.

---

### 76. REAL DEFECT: generalization type arguments discarded at class compile (inherited generic property types)

**1 test** · effort **M** · confidence high · bucket 01 (?) · verdicts: REAL_DEFECT 1

Tests: `testMergeOldAliasToNewAlias`

**Mechanism** — ClassCompiler flattens every superclass through TypeClassifier.headFqn into a bare FQN string, so `extends Pair<String, TableAlias>` loses `<String, TableAlias>` at that instant and TypedClass has nowhere to keep it. PureModelContext.findProperty then recurses into the super FQN and returns Pair's own `Property.Stored("second", TypeVar("V"), [1])` verbatim — there is no substitution step — so `$p.second` types as TypeVar(V) and `.name` on it falls to the Typer's default arm: `cannot access 'name' on V`. The asymmetry that hid it: the Typer ALREADY does correct positional instantiation when the RECEIVER is generic (Typer.java:2574-2589); the identical instantiation in the INHERITANCE direction is simply absent. Construction stays silent because NewChecker unifies with a FRESH Bindings, so TypeVar(V) accepts anything.

**Owning code** — core/src/main/java/com/legend/compiler/element/ClassCompiler.java:38-41 (`superFqns.add(TypeClassifier.headFqn(sup))` — the data loss); core/src/main/java/com/legend/compiler/element/TypedClass.java:30 (`List<String> superClassFqns`); core/src/main/java/com/legend/compiler/element/PureModelContext.java:196-201 (unsubstituted inherited property return); core/src/main/java/com/legend/compiler/spec/Typer.java:2543-2568 (ClassType arm) and :2597-2598 (the emitting throw); core/src/main/java/com/legend/compiler/spec/InferenceKernel.java:626-632 (why resolve() cannot be reused)

**Fix** — Carry the generalization's type arguments through Phase F and substitute at property lookup: (1) TypedClass — replace `List<String> superClassFqns` with `List<Type> superTypes` and keep superClassFqns() as a derived accessor mapping each entry to its head FQN, so all 8 existing call sites compile untouched; (2) ClassCompiler.java:38-41 — `superTypes.add(classifier.classify(sup, typeParams))` instead of headFqn; (3) PureModelContext.java:196-201 — when the super entry is a Type.GenericType, zip the super class's typeParameters against its arguments and run the returned property's type (and, for Property.Derived, its parameter types) through a NEW LENIENT structural `Type substitute(Type, Map<String,Type>)` placed next to Type (do NOT reuse InferenceKernel.resolve — it throws on unbound vars and unwraps Relation<row>), walling loudly on arity mismatch. Add withType(...) to both Property arms. No change needed in NewChecker or Typer — both go through ModelContext.findProperty.

**Leverage** — The single highest-value CORRECTNESS item in the bucket even though it is one test: this is a compiler data-loss bug, not a missing surface, and it silently mistypes every corpus class that extends a parameterized class. But it is leverage with a bill attached — it TIGHTENS types that previously unified with anything, so new walls can surface anywhere (the sharpest exposure named is PureFunctionToRelationalFunctionPair at pureToSQLQuery.pure:78, whose `.second` becomes a full FunctionType that every ->eval must conform to). Gate on a full sweep, not on this test. Honest limit: the fix removes the WALL, not necessarily the FAIL — OldAliasToNewAlias is not in HostEval.HOST_CONSTRUCTION_CLASSES (HostEval.java:105-111) so evaluation takes the lowering path and a second, distinct wall may appear.

**Shares code with** — Touches core/src/main/java/com/legend/compiler/element/ClassCompiler.java:38-41, TypedClass.java:30, PureModelContext.java:196-201 and is observed via compiler/spec/Typer.java:2597-2598. ANY bucket with a failure message of the form `cannot access '<prop>' on <single-uppercase-letter>` (a TypeVar receiver: `on V`, `on U`, `on T`) is this same defect and must be merged here — the diagnosis names milestoning.pure:123/180 and testDataGeneration.pure:461/547/979/1076 as candidate sites.

---

### 77. TableAlias/Table/View prelude fidelity (derived property relation(), typed schema, real View shape)

**1 test** · effort **M** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testFindAliasMappingBySchemaName`

**Mechanism** — legend-pure's TableAlias carries a QUALIFIED (derived) property `relation(){$this.relationalElement->cast(@Relation)}:Relation[1]`; legend-lite's native port declares only the data property `schema` — and in fact NO native class in Pure.java declares any qualified property. So `$tableAlias.relation()` is not recognised as a property call, falls through to function dispatch, functionCandidates comes back empty and Typer throws the catalog-miss. Two further fidelity gaps sit behind it: Table.schema is typed `Any[0..1]` (so `->cast(@Table).schema.name` cannot navigate) and View extends ModelElement with only columnMappings (no name/schema/primaryKey/mainTableAlias, and the wrong supertype for the `v:View[1]` match arm).

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:346 (TABLE_ALIAS_METACLASS, no relation()), :417 (TABLE_METACLASS, `schema: Any[0..1]`), :277 (VIEW_METACLASS, wrong supertype + missing properties), :489 (SCHEMA_METACLASS already holds tables: Table[*] — the cycle risk), :146-167 (nativeClass runs the real ElementParser, so a derived property is expressible in the prelude string); core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1452 (the emitting throw); core/src/main/java/com/legend/model/ClassDefinition.java:48-65 (model side already supports derivedProperties)

**Fix** — Three prelude edits in Pure.java, all or none (each alone only moves the wall one step): (1) add the qualified property to TABLE_ALIAS_METACLASS verbatim from relational.pure:211, fully qualified since the prelude string has no imports; (2) retype TABLE_METACLASS.schema from Any[0..1] to `meta::relational::metamodel::Schema[0..1]` — but FIRST verify the instance-value layout builder tolerates the Table<->Schema cycle, since the prelude already works around exactly this for CTE<->SelectSQLQuery at Pure.java:440; if it does not, ledger the test instead; (3) widen VIEW_METACLASS to the real shape (schema, primaryKey, columnMappings, userDefinedPrimaryKey, mainTableAlias) and re-parent it from ModelElement to NamedRelation.

**Leverage** — Modest and risk-loaded. The one encouraging signal is that this is a prelude-fidelity gap rather than an absent subsystem, and the file's sibling white-box tests are said to already pass — so legend-lite genuinely can host engine Pure helpers over this metamodel once the surface is complete. Against that: part (3) (View ModelElement -> NamedRelation) has the largest blast radius in the bucket (the typeInference family consumes the current View shape host-side over DatabaseDefinition), and part (2) may be structurally blocked by the value-layout cycle. Also note this test navigates `.second.relation()` off OldAliasToNewAlias, so it ALSO needs the generic-inheritance defect fixed — sequence that cluster first, then re-probe with only part (1) and read the wall before committing to (2)/(3).

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:277/346/417/489 — the View re-parent specifically is called out as changing subsumption for the typeInference family, so any bucket with typeInference or `match`/`instanceOf`-over-View failures must be merged before landing. Also depends on ClassCompiler.java:38-41 / PureModelContext.java:196-201 (the generic-inheritance cluster).

---

### 78. Typer types a NormalizeRequired call's arguments before deciding to inline it

**1 test** · effort **M** · confidence high · bucket 09 (?) · verdicts: REAL_DEFECT 1

Tests: `rowValueDifferenceTest`

**Mechanism** — Typer.applyGeneric (Typer.java:1208-1214) calls checkGeneric first — which synths EVERY argument (Typer.java:1437-1440) — and only then asks requiresNormalization(a.chosen()). Since inlineNormalized (Typer.java:1289-1301) discards those typed args and β-substitutes the RAW AST, the standalone typing is pure wasted work that can wall on expressions the inline would never have typed. Here the argument $tds1.columns->filter(c|$c.name->in($columnsToCheck))->sortBy(…) passed to extendMatchColumns cannot be constant-folded (StaticFold.reify has no Col arm, StaticFold.java:519-544), so it is typed standalone, bare .columns comes back as a String collection (Typer.java:2465-2467/:2334-2345) and $c.name throws at Typer.java:2597. The sibling columnValueDifferenceTest has the identical chain but consumes it with a trailing ->map(col|…) that StaticFold UNROLLS (StaticFold.java:126-142), so .columns never reaches the Typer and that test clears typing entirely — that shape difference is the only discriminator.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1208-1214 (applyGeneric ordering), with the gate pattern to copy at Typer.java:1352-1373 (rawSchemaErasedExpansion)

**Fix** — Decide normalization BEFORE typing arguments: add soleNormalizeRequiredCandidate(af) — functionCandidates filtered to matching arity, null if ANY candidate isNative(), then the single requiresNormalization candidate or null — and call inlineNormalized directly when it returns non-null, keeping the existing post-checkGeneric branch as a backstop. inlineNormalized already takes (af, chosen, env) and reads only raw parameters, so nothing else moves. The gate must decline when several same-arity NormalizeRequired overloads exist (meta::pure::tds::join has two) so no overload can be silently mis-picked.

**Leverage** — Single test, and the diagnosis is explicit that it will NOT go green — the sibling columnValueDifferenceTest, same pipeline, dies at 'store resolution left getAll(Trade) unresolved', which is where this one lands next. Value is architectural rather than per-test: it removes spurious walls and wasted typing at every NormalizeRequired call site, so it will move messages across tds/ and functions/ (budget a re-sweep). Important adjacency: the diagnosis's SECONDARY fix (teach StaticFold.reify to emit a Col marker so a Col survives β-substitution) is the same change as step (4) of the digest cluster — if that lands there with a slightly widened gate (fire on a non-reifiable ARGUMENT, not only a non-reifiable bound variable), this test falls out for free and the M drops to near zero. Sequence the digest cluster first and re-check before spending M here.

**Shares code with** — Typer.applyGeneric/inlineNormalized (Typer.java:1208-1305) is the entry point for EVERY NormalizeRequired corpus helper — any bucket blaming inlineNormalized, requiresNormalization, or reporting a wall inside a schema-erased helper's argument shares this owning code. Also shares the '.columns is Strings, not TDSColumn' defect (Typer.java:2465-2467) with the digest cluster; do not fix that half twice, and do not change columnsMeta's String typing without auditing every assertEquals([...names...], $x.columns) consumer.

---

### 79. loadCsvToDbTable platform native and its resource-root seam are entirely absent

**1 test** · effort **M** · confidence medium · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testLoadCsv`

**Mechanism** — The corpus's own 3-arg convenience overload (relationalExtension.pure:57-59) bottoms out at the 4-arg legend-pure platform native loadCsvToDbTable(String[1],Table[1],DatabaseConnection[1],Integer[0..1]):Nil[0], which legend-lite does not register anywhere. InferenceKernel.resolveOverload sees only the two 3-arg corpus overloads and throws 'no overload of … accepts 4 argument(s)' (InferenceKernel.java:787-790), prefixed by SpecCompiler.java:70. Behind that there is no CSV bulk-load surface at all and no code-storage/resource-root seam through which a Pure repository path can be resolved.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1517 (beside EXECUTE_IN_DB); core/src/main/java/com/legend/StatementExecutor.java:3273-3300 (the executeInDb K-phase arm the new arm sits beside); a new pureResourceRoots option on the execution configuration

**Fix** — Build the surface in three parts: register the 4-arg native; add a configured resource-root list (set by the corpus runner to the resources dir, falling back to getResourceAsStream, walling loudly with the attempted path) so the Pure repository path resolves as configuration rather than a hardcode; and add a loadCsvToDbTable arm to StatementExecutor beside executeInDb that reads the table handle's columns and declared SQL types, drops the header row, coerces per LoadCsvToDbTable.java:96-118 (Integer/SmallInt/TinyInt -> int, BigInt -> long, Double/Float/Numeric -> double, empty -> NULL, dates and everything else left as strings), honours numberOfRows as a LIMIT, and issues a batched parameterised INSERT through the same RawSqlBoundary recording channel executeInDb uses.

**Leverage** — Low leverage, high tenet risk. One test, a new I/O seam, and a sharp tenet-2 trap: the Runner already treats loadCsvToDbTable as an effectful-setup marker (Runner.java:2169) and already walks the corpus tree, so pre-seeding personCsvTable from employees.csv would be nearly free and completely wrong. If this bucket is being triaged for value, defer it — but if it IS taken, the resource-root seam is the reusable part; the CSV read, coercion and INSERT are single-purpose. Do not 'helpfully' parse date columns during load; the engine deliberately keeps them strings.

**Shares code with** — Adds a K-phase native arm next to StatementExecutor.executeInDb (StatementExecutor.java:3273) and a builtin/Pure.java registration. Any bucket blaming StatementExecutor's K-phase JDBC arms or needing a resource/code-storage root (external format tests, CSV/flat-file surfaces) shares the seam introduced here — flag it before two buckets invent two different root configs.

---

### 80. relationalExtensions() returns Any[*] instead of Extension[*], and the nonExecutable post-processor hook shape is unrecognized

**1 test** · effort **M** · confidence high · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testReplaceTablePostProcessorWithSubQueries`

**Mechanism** — Two stacked causes. (a) Pure.java:1582 declares relationalExtensions():Any[*] while both the corpus function and legend-lite's own mirror (Pure.java:1505) declare the parameter as Extension[*]. InferenceKernel.paramTypeScore's zero-shortcut fires only when the FORMAL is Any (InferenceKernel.java:960-966), so formal=Extension/actual=Any scores -1; both arity-2 candidates lose, winners is empty and resolveOverload throws (InferenceKernel.java:809-822). There are two candidates only because the corpus's function-only parent file is pulled into the family model (RelationalCorpusRunner.java:704-717). (b) Behind that, SqlPostProcessors.readHook accepts exactly one hook body — a terminal replaceTables call — and throws NotImplementedException for anything else (SqlPostProcessors.java:60-98), so fixing (a) only advances the wall to (b).

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1582 (return type) and :247 (the Extension class already exists); core/src/main/java/com/legend/lowering/SqlPostProcessors.java:63-75 and :85-98 (readHook) plus its apply path; core/src/main/java/com/legend/StatementExecutor.java:2211-2222 (where tableReplaceMap is threaded)

**Fix** — (1) XS: change relationalExtensions's return type to meta::pure::extension::Extension[*] (the engine's own declaration, extension.pure:62), rename the constant and update the golden native-catalog. Call sites passing it into Any[*] parameters are unaffected. (2) M: teach readHook a second recognized shape — a terminal nonExecutable($query, extensions) call — carried as a flag on the same channel as tableReplaceMap (widen the record to a small Cfg), and add an IR pass in SqlPostProcessors.apply that ANDs the constant predicate 1 = 2 into the WHERE of EVERY SqlSelect node (root and nested/derived), leaving plain table sources untouched — exactly nonExecutablePostProcessor.pure:36-46, and exactly what the golden SQL shows. The recognizer must stay LOUD for any third hook shape; SqlPostProcessors.java:63-75 records a user ruling that catch-and-skip produced false greens.

**Leverage** — Mixed, and worth splitting. Part (1) is XS and may unblock other tests that pass relationalExtensions() into corpus functions declaring Extension[*] — but it also changes overload SCORING wherever both an Extension[*] and an Any[*] overload exist for a name, so it demands a full-corpus re-sweep rather than a family run. Part (2) is real feature work whose only in-corpus beneficiary is this one test (its Snowflake/SybaseIQ mirrors are outside). Land (1) alone first and read the new wall — the diagnosis gives an exact expected string that confirms or refutes the two-layer model for free.

**Shares code with** — Part (1) edits builtin/Pure.java and shifts InferenceKernel overload scoring corpus-wide — if any other bucket has tests failing on Extension[*] vs Any[*] parameter binding (the pureToSqlQuery::andFilters family is named), they are the SAME one-line change and should merge. Part (2) lives in lowering/SqlPostProcessors.java, adjacent to any bucket blaming lowering/Lowerer.java for post-processor or table-rename behaviour.

---

### 81. Generic user functions are compiled once against declared types, so TypeVars reach SQL lowering

**1 test** · effort **M** · confidence medium · bucket 09 (?) · verdicts: REAL_DEFECT 1

Tests: `testFirstNotNull`

**Mechanism** — SpecCompiler.check types a function body in an Env binding each parameter to its DECLARED type (SpecCompiler.java:136-139) and compile memoizes that one compilation per TypedFunction (SpecCompiler.java:57-74) — there is no per-call-site variant. UserCallInliner.inlineCall splices exactly that generic body (UserCallInliner.java:184) and reduceStatements substitutes argument NODES for parameter names without re-typing, so for firstNotNull<T>(set:T[*]):T[0..1] the spliced filter node still carries ExprType(T,[*]) and the first node ExprType(T,[0..1]). PureSql.type then hits `case Type.TypeVar v -> throw new IllegalStateException("unresolved type variable " + v.typeName() + " reached the lowering boundary")` (PureSql.java:98-99) with v = T. This is a general monomorphization gap, not a firstNotNull quirk.

**Owning code** — core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:57-74 and :136-139; core/src/main/java/com/legend/compiler/spec/UserCallInliner.java:184; wall at core/src/main/java/com/legend/lowering/PureSql.java:98-99

**Fix** — Monomorphize at the call site: add SpecCompiler.compileAt(fn, argTypes) that runs the same check logic but seeds the scope position-wise from the actual argument ExprTypes and memoizes on (fn, argTypes); keep the existing generic compile(fn) for whole-graph validation (the declared-return conformance check at SpecCompiler.java:151-158 must still run generically). In UserCallInliner.inlineCall gate on call.callee().typeParameters().isEmpty() (TypedFunction.java:51-99 already carries the list) so non-generic callees keep the memoized path and there is no compile-count regression. This matches the engine, which resolves T from the argument at the call site (Handlers.java:1771).

**Leverage** — Single test in this bucket but architecturally the highest-value entry here: it is a general gap that will wall EVERY generic user function whose body types flow into SQL. Before sizing, grep 'unresolved type variable … reached the lowering boundary' across the corpus docs — the real leverage is however many tests carry that text, which this bucket cannot see. The absolute prohibition: do not make PureSql.type map an unresolved TypeVar to VARCHAR or JSON — that converts a compiler gap into wrong rows. Honest ceiling for THIS test: after the fix it still has to compare an Any/JSON-carried heterogeneous element against the integer literal 1.

**Shares code with** — Owns SpecCompiler's memoization and UserCallInliner's body splice — any bucket whose wall is 'unresolved type variable … reached the lowering boundary' (lowering/PureSql.java:98-99) is this cluster regardless of which test surfaced it, and should be merged in wholesale. Also note the risk that a generic body which previously compiled once may now fail declared-return conformance for a specific argument shape, turning currently-passing tests loud in other buckets.

---

### 82. meta::json::toJSON is unported

**1 test** · effort **M** · confidence high · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testSimpleTypeMappingProjectNulls`

**Mechanism** — The name toJSON does not exist anywhere in legend-lite (grep of core/src/main/java/com/legend returns zero hits). The test imports meta::json::* and its final assert calls $tds->toJSON(); functionCandidates comes back empty and Typer.java:1443-1451 throws the 'unported platform function' wall. The whole meta::json serializer family (toJSON/toJSONElement/toJSONStringStream, Config, JSONState, extraSerializers) is absent. The test's actual semantic content — TDSNull rows from a projection over nullable columns — is unrelated to the wall.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java (new native signature); a new host-side arm in com/legend/harness beside the toCSV/sqlRemoveFormatting folds; wall at core/src/main/java/com/legend/compiler/spec/Typer.java:1443-1451

**Fix** — Port a deliberately SCOPED meta::json::toJSON: register only the arities the corpus actually spells, and implement exactly ONE arm host-side — a TabularDataSet argument emitting the engine's optimizedTdsJSONStringStream shape {"columns":[{"name":…,"type":…,"metaType":"PrimitiveType"|"Enumeration"|"InvalidType"|""}],"rows":[{"values":[…]}]} with a TDSNull cell as bare null (toJSON.pure:193-206, :263). Every other argument shape throws NotImplementedException naming toJSON. Do not attempt a generic object serializer, and do not weaken the golden compare — the golden pins column type names and metaType classification.

**Leverage** — Low: one test, the only toJSON wall in the corpus, and the serializer is not otherwise needed. The scope discipline is the whole point — meta::json in the engine is a full extensible serializer (Config, extraSerializers, graph-fetch trees, cipher), and porting more than the TDS arm buys nothing here. Reasonable to ledger unless a broader external-format story is planned.

**Shares code with** — builtin/Pure.java catalog addition plus a harness-side value-producing native fold — if another bucket needs meta::json (external format / serialization families), merge so the TDS arm and any object arm are designed together rather than bolted on.

---

### 83. Alloy test-data leg is never inlined, and TestDataGenForm has no alloy entry-point recogniser

**1 test** · effort **M** · confidence high · bucket 10 (harness SHAPE) · verdicts: HARNESS_GAP 1

Tests: `testAlloyTestDatGenForNestedViews`

**Mechanism** — Two independent gaps. (1) EngineTestExecutor's mayExecute* arm inlines the parameterized leg only when its body references none of its own parameters (`if (lfA.body().stream().noneMatch(st -> referencesAny(st, ps)))`, EngineTestExecutor.java:341). This body references $clientVersion/$serverVersion/$host/$port, so inner stays null, the `{|true}` fallback wins (line 349), the sole spliced statement is a CBoolean that is skipped without counting, and Runner scores `no verifying assertions` (Runner.java:1474). (2) Even inlined, TestDataGenForm recognises only generateTestData / planTestDataGeneration / generateSeedDataString / getRelationalCSVDataFromQuery (TestDataGenForm.java:46,54,57,86,148) — not the alloy `pathToElement(...)->cast(...)->evaluate([...])` entry point — so $result never binds and assertTestData/3 returns UNSUPPORTED_MARKER.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:332-351 (the mayExecute leg selection), :1504-1520 (assertTestData); core/src/main/java/com/legend/harness/TestDataGenForm.java:44-46 (hasGenerate, the only recogniser); core/src/test/java/com/legend/rcorpus/Runner.java:1474 (the scoring outcome)

**Fix** — (1) In the mayExecute arm, when the parameterized leg references ONLY the four decorative transport parameters (clientVersion, serverVersion, host, port), inline it with those bound as literals in `lets`; a leg referencing any other parameter keeps falling through to the zero-arg leg, so the existing unreferenced-params case is untouched. (2) Add an alloy recogniser beside hasGenerate matching `pathToElement(<foldable string ending in alloyGenerateTestDataWith{Seed,DefaultSeed}{Interactive,SemiInteractive}_...>)->cast(...)->evaluate([list(a0), list(a1), ...])`, unwrap the list(...) wrappers, route positions 0-5 (query, mapping, runtime, executionContext, tableRowIdentifiers, hashStrings) into the SAME TestDataGenForm.run path generateTestData uses, drop host/port/version/extensions, and teach TestDataGenForm.read to treat a bare cast/toOne chain over an alloy binding as kind dataCsvString so assertTestData verifies by ROWS through TestDataGenerator.compareCsv. Land both together, and never have the harness compute or hard-code the CSV — it must call the platform's TestDataGenerator.

**Leverage** — Negative on the scoreboard, strongly positive on honesty — the most important entry in this bucket to read correctly. Change (1) will START RUNNING the alloy leg in roughly twenty sibling *_Alloy tests that today score a hollow PASS purely because Runner's `verified==0 && executed>0` rule sees their bare `createTablesAndFillDb();` statement. This one test is the only member of the family whose setup is a LET, which is why it alone surfaces as SHAPE. Expect several currently-green tests to flip to SHAPE/FAIL. Schedule it when a scoreboard dip is affordable and land (2) with it so the tests that CAN verify do. Cheap falsifier first: instrument (do not fix) the arm to log which leg it inlines for testSimpleSingleTable_Alloy.

**Shares code with** — Touches Runner.score's PASS-on-executed rule indirectly (Runner.java:1474 region) and EngineTestExecutor's mayExecute inlining — both are global harness scoring surfaces. If any other bucket contains a test that passes with '0 asserts — N statement(s) executed', it is passing under the same hollow rule this cluster exposes, and that bucket's counts should be re-read before this lands.

---

### 84. Element-metamodel property access on a Mapping receiver, and the missing EnumerationMapping surface

**1 test** · effort **M** · confidence high · bucket 10 (harness SHAPE) · verdicts: MISSING_FEATURE 1

Tests: `testEnumTheSame`

**Mechanism** — SpecParser.parseDotPostfix turns `<PackageableElementPtr>.<name>` into an EnumValue unconditionally (SpecParser.java:1350-1359), and Typer.enumValue has exactly ONE disambiguation arm for 'this dotted form is really element-metamodel property access' — receiver is a Database (Typer.java:2652-2661). A Mapping receiver has no arm, so `employeeTestMapping.enumerationMappings` falls to findEnum().orElseThrow and reports `unknown enumeration 'meta::relational::tests::mapping::enumeration::model::mapping::employeeTestMapping'` (Typer.java:2665) — a misattributed message hiding the real gap. Behind it the entire enumeration-mapping metamodel is absent: Pure.MAPPING_METACLASS declares only `name` (Pure.java:406), there is no EnumerationMapping metaclass, and neither toDomainValue nor enumerationMappingByName exists in core/src/main. The compiled model already carries the data (MappingDefinition.java:39, List<EnumerationMapping>) — it is simply not exposed.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:2643-2665 (enumValue, Database-only arm and the misleading throw); core/src/main/java/com/legend/builtin/Pure.java:406 (MAPPING_METACLASS); core/src/main/java/com/legend/exec/MetamodelWalk (host-side backing); core/src/main/java/com/legend/model/MappingDefinition.java:39 (the data already present)

**Fix** — (A) Generalise Typer.enumValue's Database special case into an element-kind dispatch: when findEnum is empty, look the FQN up as a packageable element and emit TypedPropertyAccess over a TypedPackageableRef typed with the right metaclass — meta::relational::metamodel::Database for the existing arm, meta::pure::mapping::Mapping when ctx.findMapping is present, extensible from there; keep the enum lookup FIRST so real enum values are unaffected, keep the throw when the FQN names no element, and fix the element-exists-but-property-missing message to name the element's KIND instead of saying 'unknown enumeration'. (B) Build the metamodel surface: extend MAPPING_METACLASS with enumerationMappings; add meta::pure::mapping::EnumerationMapping<T> (name, enumeration, enumValueMappings) and EnumValueMapping (enum, sourceValues); add the natives toDomainValue and enumerationMappingByName; back them in MetamodelWalk by projecting MappingDefinition.enumerationMappings() into walk nodes. toDomainValue must reproduce legend-pure's semantics exactly including the 'exactly one match' assert. Do not add a toDomainValue arm to EngineTestExecutor — this is model surface the platform owns.

**Leverage** — Better than one-test-for-M suggests. Part (B) alone is what testEnumMappings and testEnumMappingsWithInclude (same file, lines 165-185) need — they use the arrow form tradeMapping->enumerationMappingByName('X')->toDomainValue(...) and so need no part (A) — so the metamodel half has at least two further consumers even though they are not in today's failing set, i.e. it is regression-guard value rather than immediate green count. Part (A) also converts a genuinely misleading diagnostic ('unknown enumeration' for a Mapping element) into an accurate one, which pays back in every future triage of this shape.

**Shares code with** — Two shared files. Typer.java:2643-2665 enumValue — any bucket reporting `unknown enumeration '<an FQN that is obviously not an enumeration>'` is hitting this exact missing dispatch and belongs in this cluster. builtin/Pure.java:406 MAPPING_METACLASS is extended here AND by the resolveStore cluster below; both also add host-side folds in com.legend.exec.MetamodelWalk. Those two clusters are not one change but they are one work area and should be sequenced together.

---

### 85. Mapping.resolveStore is unexposed and checkAssert has no assertIs arm

**1 test** · effort **M** · confidence high · bucket 10 (harness SHAPE) · verdicts: MISSING_FEATURE 1

Tests: `testStoreSubstitution`

**Mechanism** — Two independent absences, the assert form surfacing first. checkAssert's switch has arms for fourteen assert forms but none for assertIs, so it falls to `default -> UNSUPPORTED_MARKER` (EngineTestExecutor.java:2043) and scoreAssert stamps 'assert form assertIs/2 is not supported yet'. Behind that, the operand `simpleRelationalMappingInc->resolveStore(dbInc)` cannot be evaluated at all: meta::pure::mapping::resolveStore and its helper findSubstituteStore exist nowhere in legend-lite. The DATA is present (MappingDefinition.includes() -> MappingInclude.substitutions() -> StoreSubstitution), but legend-lite consumes it EAGERLY as a rewrite in normalizer/StoreSubstitutionRewrite rather than exposing the query 'which store does this mapping resolve X to?'.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:1782-2045 (checkAssert switch, default at :2043); core/src/main/java/com/legend/builtin/Pure.java (resolveStore native); core/src/main/java/com/legend/exec/MetamodelWalk (host-side fold); core/src/main/java/com/legend/normalizer/StoreSubstitutionRewrite.java:32-62 (the eager consumer); core/src/main/java/com/legend/StatementExecutor.java:3036-3070 (Scalar(null) orchestration handles — the hollow-pass trap)

**Fix** — (A) FIRST, platform: add meta::pure::mapping::resolveStore(Mapping[1], Store[1]):Store[1] as a native backed by a MetamodelWalk fold mirroring legend-pure exactly — for each MappingInclude, recurse into the included mapping first, then map the result (or the argument store if none) through this include's substitutions, first non-empty wins, unchanged argument otherwise — and represent the result as the store's FQN in the walk's element-handle node, NOT as Scalar(null). (B) THEN, harness: add assertIs/assertIsNot arms next to assertEquals accepting 2-4 args, comparing by ELEMENT IDENTITY (walk-node FQN equality) and returning UNSUPPORTED_MARKER — never a pass — when either side evaluates to null or an opaque handle. Order matters absolutely: (B) alone would compare two Scalar(null) handles and hollow-pass all four asserts.

**Leverage** — Split value. The assertIs arm is small, reusable, and pays off for any corpus test using this common pure assert. The resolveStore native is narrow — specific to the mapping/include family. What makes this cluster worth calling out is the trap rather than the payoff: the tempting half (the assert arm) is precisely the half that manufactures a WRONG PASS, since both operands currently evaluate to Scalar(null) and null == null. If effort has to be cut, cut the assert arm and keep the native, never the reverse. Cheap falsifier that could shrink the work: check whether a Database element in value position already yields its FQN rather than Scalar(null).

**Shares code with** — builtin/Pure.java plus com.legend.exec.MetamodelWalk — the same pair the enumeration-mapping cluster extends; merge the two into one 'expose the mapping metamodel host-side' work item if either is scheduled. The assertIs arm lands in EngineTestExecutor.checkAssert's switch (EngineTestExecutor.java:1782-2045), which is the single most contended harness surface across buckets — any other bucket adding an assert arm edits the same switch, and every such addition carries the same hollow-pass hazard when its operands evaluate to opaque handles.

---

### 86. Correlated isolation subselect keyed on the association key instead of the parent PK

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testVariableReferenceWithNestedFilterMultiple`

**Mechanism** — CorrelatedSubselects.explodingSubselect derives the isolation key from the ASSOCIATION condition (parentEquiKeys), so for Person.firm (firmTable.ID = personTable.FIRMID) the parent-side key is FIRMID, which is not unique on Person. The join-back becomes outer.FIRMID = sub._pk0, so every outer Person row matches every parent-copy row of the same firm that satisfies the predicate: 4 employees x 3 matching + 3 null rows = the 15 rows observed where 7 are correct. The engine copies the ROOT tree and re-joins on the root's own PK (golden: root.ID = persontable_1.ID at both isolation levels).

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:285-286 (keyCols = parentEquiKeys), :330-349 (_pk<i> projection), :361-363 (pkEqualityCond join-back), :395-405 (parentEquiKeys); same reuse in corrAggSubSource ~:2043-2055

**Fix** — Stop deriving the isolation key from the association condition. Resolve the parent ClassSource's primary-key columns (mapping ~primaryKey, falling back to the main table's declared PRIMARY KEY, via the same mapping-closure walk ViewFrames.frameNameOf uses at ViewFrames.java:34-48) and use those as keyCols in both explodingSubselect and the filter-position parent-copy branch of corrAggSubSource. The association condition stays inside the subselect. Throw a loud NotImplementedException naming the class if no PK resolves. Do NOT add DISTINCT to the outer projection.

**Leverage** — Single test in this bucket but the highest-value item in it: a wrong-rows correctness bug (15 rows returned where 7 are right, with duplicate objects) that today silently passes row verification elsewhere whenever the association key happens to equal the parent PK. The diagnosis names two further candidates with the same over-explosion signature (testTwoQualifiersUsingSameJoinWithNoUserParams: assertSize expected 1 got 4; testIsolatioWhereNoConstaintsAndInnerJoin). Worth doing on correctness grounds alone.

**Shares code with** — resolver/CorrelatedSubselects.java and its caller StoreResolver.java:1946-1953 (the corrSubPred path). Any bucket reporting duplicate/over-counted rows from a nested filter over a to-one navigation, or an assertSize N-vs-kN mismatch, is likely this same keying bug — merge.

---

### 87. dayOfWeek shift formula hard-coded in the IR, never dialect-adapted

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testMostRecentDayOfWeek`

**Mechanism** — DateShifts.dayOfWeekShift expands mostRecent/previousDayOfWeek into a CASE over EXTRACT('isodow', anchor) with ISO numbering (Monday=1) at lowering time. EngineStyleH2 special-cases EXTRACT only for 'doy', so everything else falls through to the generic spelling date_part — which is not an H2 function. The engine's H2 model is date_part('dow', ...) with mapToDBDayOfWeekNumber (Monday=2 ... Sunday=1), printed as extract(dow from ...). Both formulas are correct for their own target, so DuckDB rows are fine and only the pure-text toSQLString surface exposes it.

**Owning code** — core/src/main/java/com/legend/lowering/DateShifts.java:23-34 (isoDayNumber) and :40-60 (the expansion); core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1491-1495 (EXTRACT only handles 'doy'); core/src/main/java/com/legend/sql/dialect/Spellings.java:70 (EXTRACT -> date_part); registration at core/src/main/java/com/legend/lowering/Scalars.java:483-491

**Fix** — Keep the shift SEMANTIC in the IR and expand per dialect, exactly as DATE_TRUNC already is (EngineStyleH2.java:1498-1509). Add SqlFn.DOW_ANCHORED_SHIFT(dayName, strict, anchor); DateShifts.dayOfWeekShift returns that single call; DuckDb/AnsiSqlRenderer expand it to today's isodow form byte-identically so rows are unchanged; EngineStyleH2 expands it to the engine's form (Monday=2...Sunday=1 table, part name dow, printed extract(dow from ...), operator > for mostRecent and >= for previous, with the parenthesisation matched literally in the WHEN and THEN but not the ELSE).

**Leverage** — One test green, plus it removes the standing advisory sql diffs on testPreviousDayOfWeek and the two ...WithDate variants that pass today only by row verification. Modest but self-contained and low-risk provided the DuckDB arm is kept byte-identical (DuckDB dow is Sunday=0, not the engine's Sunday=1 — changing the DuckDB arm would produce wrong rows).

**Shares code with** — sql/dialect/EngineStyleH2.java call() EXTRACT arm and sql/dialect/Spellings.java:70. Any other bucket whose got text emits date_part(...) where an engine golden says extract(... from ...) is the same missing per-dialect expansion seam.

---

### 88. Mid-table milestoning context ignores the hop's explicit date

**1 test** · effort **M** · confidence medium · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testDateFunctionInMilestonedPropertyWithMilestonedEntity`

**Mechanism** — TemporalFrame.applyJoinTemporalFilters computes the mid-table context with a ternary guarded on specDim and a single-date spec. Two gates independently force the root fallback: (1) specDim comes from midPrefixToDim, filled from the nav step's TARGET class (System, non-temporal, so null) even though the comment three lines above says the target class never governs the mid table; (2) midChain for a drilled EMBEDDED head is the DOTTED chain (classification.system) while the explicit date registers under the bare chain (classification), so the lookup misses. Result: the mid table ProductClassificationSystemTable is filtered with the root fetch date 2015-10-16 instead of the classification's explicit 2015-01-01. Different rows, not different text.

**Owning code** — core/src/main/java/com/legend/resolver/TemporalFrame.java:1643 (the midCtx ternary), :1625 (specDim from midPrefixToDim), :2024-2026 and :2119 (collectTemporalNodes / TemporalSpec record); core/src/main/java/com/legend/resolver/StoreResolver.java:1829 (dimension taken from tg2.classFqn()) with the contradicting comment at :1806

**Fix** — Carry the dimension ON the spec instead of the slot: add a MilestoningStrategy dim field to TemporalFrame.TemporalSpec set from the class the milestoned qualified property RETURNS. Add midContextFor(String midChain) that walks the dotted chain longest-prefix-first and, for the first prefix with a spec, yields NONE for a sweep spec, the range construction for a 2-date spec, and TemporalContext.single(spec.dim(), date) for a single-date spec; only when no prefix has a spec return root. Call it at TemporalFrame.java:1643 and delete midPrefixToDim with its producer (StoreResolver.java:1829) and parameter threading. Narrow, do not delete, the NotImplementedException at TemporalFrame.java:1631.

**Leverage** — Wrong dates mean wrong rows, so this is worth fixing on correctness grounds — but it buys zero green tests here: this test's golden is a raw-alias toSQLString golden, so it stays red behind the raw-alias ledger even after the fix. Justify it by the three same-family candidates the diagnosis names (testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR, testLatestMilestoneDatePropogation..., testLatestMilestoneDateMappedTableDate...), all unverified. Do not count this cluster as a pass-rate item.

**Shares code with** — resolver/TemporalFrame.java applyJoinTemporalFilters (1599-1750) and resolver/StoreResolver.java:1792-1838. Any bucket whose failure is a milestoning predicate carrying the ROOT fetch date on an intermediate join where an ancestor hop supplied an explicit date is this same mid-table arm.

---

### 89. ImplicitInheritance silently merges ancestor property mappings into the bare-root envelope

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testQueryOfMilestonedTypeUsingLatestWithFilterInMapping`

**Mechanism** — ImplicitInheritance.apply rewrites any Relational class mapping whose class extends an ancestor with a single Relational mapping over the SAME main table, merging the ancestor's unqualified property mappings into the child's list. StockProduct therefore silently gains Product's stockProductName/classificationType/referenceSystem/biTemporalClassification; the datatype ones become bindings and synthesizeScalarTree emits one leaf per scalar binding, adding extra select columns and three left-outer joins. legend-engine inherits property mappings ONLY through an explicit `extends [setId]` (superSetImplementationId) — there is no class-hierarchy walk in allPropertyMappings. The same test also carries the %latest k_ literal divergence.

**Owning code** — core/src/main/java/com/legend/normalizer/ImplicitInheritance.java:78-99 (the merge, applied unconditionally from MappingNormalizer.java:249); core/src/main/java/com/legend/resolver/GraphEmission.java:123-144 (one leaf per scalar binding); core/src/main/java/com/legend/resolver/RelationalRootForm.java:100-112 for the literal half

**Fix** — Make the merge PROVENANCED rather than deleting it (it is load-bearing for property RESOLUTION — ProductWithConstraint2's constraint resolves through an inherited PM, and the engine gets that via routing's generalization walk, not via the SELECT column list). Collect the appended PM names in ImplicitInheritance.apply, thread them through MappingDefinition.ClassBinding into ClassSource as inheritedBindings(), and have GraphEmission.synthesizeScalarTree skip those keys exactly as it already skips isSubTypeColumn pseudo-bindings. Explicit navigation/projection still resolves; only the implicit bare-root envelope loses them. Separately substitute the %latest string literal in RelationalRootForm.apply (NOT in Lowerer.scalar, which would change the executed envelope's businessDate value type and break testPopulationOfLatestMilestonedDateInQuery).

**Leverage** — One test, but the mechanism is a mapping-model divergence that changes the object shape for every subclass class mapping sharing a main table with its parent (milestoningmap StockProduct, isolationFocusedMapping, the ProductWithConstraint family). Extra columns and extra joins on every bare .all() of such a class is the kind of defect that quietly inflates plans corpus-wide, so the value is broader than the one test — but expect it to MOVE other tests, since any corpus test reading an inherited property off a bare .all() object of such a subclass will start failing and must be re-read, not patched around.

**Shares code with** — normalizer/ImplicitInheritance.java + resolver/GraphEmission.java:123-144. Any bucket whose got has extra select columns or extra left-outer joins for properties the class mapping does not declare is this merge. The %latest literal half is shared with the milestoning join-order cluster and the non-milestoned-k_ cluster — land the literal change once, in one place.

---

### 90. meta::relational::functions::sqlstring::toSQL and its SQLResult toSQLString overload are unimplemented

**1 test** · effort **M** · confidence medium · bucket 04 (?) · verdicts: MISSING_FEATURE 1

Tests: `testViewChainsWithBusinessDate`

**Mechanism** — The test builds its SQL as toSQL(f, mapping, runtime, ext).toSQLString(conn.type, conn.timeZone, conn.quoteIdentifiers, ^Format(...)). legend-lite registers exactly one toSQLString native (the 4-arg Function/mapping/DatabaseType form) and no toSQL at all, so no overload takes a SQLResult in position 0. ExecCallFinder.findTerminal correctly stops at the outer toSQLString, evalScalar throws on the unresolvable signature, sideSqlText swallows it to null, sqlTextVerify falls to h2Upgrade which declines, and Runner scores SHAPE 'sql-only: 1 advisory golden-SQL assert'.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1639 (the only toSQLString native); core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:155 and :162 (TO_SQL_STRING / TO_SQL_STRING_PRETTY are the only registered sqlstring surfaces); core/src/main/java/com/legend/StatementExecutor.java:205-213 (the K-dispatch arm to extend)

**Fix** — Implement the surface in platform code — the harness already reaches it. Add PlatformTypes.TO_SQL next to TO_SQL_STRING; register in Pure.java a toSQL(f, mapping, runtime, extensions) native returning an opaque SQLResult handle (the pattern the plan surface already uses) plus the rendering overload toSQLString(sqlResult, databaseType, dbTimeZone, quoteIdentifiers, format); extend the StatementExecutor arm at :205-213 to recognise the composition and route it into the existing toSqlString/engineSql pipeline, taking the DatabaseType from the runtime's connection and honouring ^Format(newLine='', indent='') as the unformatted spelling. Keep dispatch keyed on the first argument's KIND, not arity, so the existing 4-arg call sites are undisturbed. Do not special-case toSQL inside ExecCallFinder.

**Leverage** — Unusually good for a single-test cluster because this is the ONE toSQLString-family golden in the bucket that is actually reachable: toSQL applies sqlQueryDefaultPostProcessors including replaceAliasName, so its golden spells producttableview_0 / intermediate_0 / intertwoview_0 — legend-lite's own alias convention. It is not behind the raw-alias ledger. Budget a second pass though: after the wall is removed the test may fail on content, since the golden also exercises a milestoned businessDate constant projected inside chained view sub-selects. Also unblocks the runtime-taking toSQLStringPretty(f, mapping, runtime, extensions) overload generally.

**Shares code with** — builtin/Pure.java:1639, compiler/element/type/PlatformTypes.java:155-162, StatementExecutor.java:205-213. Any bucket walling on toSQL(...) or on a runtime-taking toSQLStringPretty is the same native — merge. Shares the StatementExecutor sqlstring dispatch arm with the toNonExecutableSQLString cluster and the SQL-text-native-dispatch cluster; land them in one pass over that method if all three are being done.

---

### 91. SQL-text K-natives dispatch only at statement root, and 1-arg parseDate is spelled as a bare cast

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testParseDate`

**Mechanism** — StatementExecutor unwraps TypedLet/TypedFrom/foldPairProjection and then requires preRoot ITSELF to be the TO_SQL_STRING / TO_SQL_STRING_PRETTY native. The harness binds lets syntactically, so what reaches the platform is contains(toSQLStringPretty(...), 'parsedatetime') — preRoot is the contains native, the dispatch misses, and the whole expression falls through to StoreResolver, which never descends into a toSQLStringPretty query lambda and throws on the unresolved TypedGetAll. The precedent that this is root-only-by-construction is the hand-written one-off arm for replace(planToString(...), a, b). Behind that wall, the assert would still fail: legend-lite lowers 1-arg parseDate to a bare Cast, never emitting the parsedatetime token the engine's toTimestamp dynafunction produces on H2.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:196-212 (the root-only guard), :223-245 and :248-260 (the two hand-written nested arms), :334 (the fall-through to StoreResolver), :490-512 (hostChannel's hostLets); core/src/main/java/com/legend/resolver/StoreResolver.java:223-229 (the throw); core/src/main/java/com/legend/lowering/Scalars.java:2061-2084 (bare Cast); core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1514 and :1534-1560

**Fix** — Generalize the dispatch from root-only to a subtree fold: add foldSqlTextNatives(n, specs, env) that maps children recursively and replaces any TO_SQL_STRING / TO_SQL_STRING_PRETTY / PLAN_TO_STRING node with a TypedCString of its rendered text; run it after foldPairProjection and, when it changes the tree, evaluate the now-literal expression through the existing HostEval path (hoist hostLets out of hostChannel to reuse it). Delete the two hand-written arms at :223-260 in the same change so there is one rule, not three; a fold that throws must propagate the same NotImplementedException, never swallow to null. Separately spell 1-arg parseDate the engine way in the ENGINE-STYLE dialect only: emit cast(parsedatetime(<arg>, 'yyyy-MM-dd HH:mm:ss') as timestamp) from EngineStyleH2 while DuckDb/H2 execution dialects keep today's bare cast and the literal padding.

**Leverage** — Fix A is the valuable half and is broader than this test: it unblocks every corpus test that consumes toSQLString/toSQLStringPretty/planToString text through any host string function other than the two hand-enumerated ones, and it lets two special-case arms be deleted. Fix B additionally targets the two named testSqlFunctionsInMapping parseDate goldens. Together they close this test outright — one of the few in this bucket that actually goes green.

**Shares code with** — StatementExecutor.java:196-260 (the same dispatch arm the toSQL and toNonExecutableSQLString clusters extend — do all three in one pass over this method if all are being done) and lowering/Scalars.java:2061-2084. Any bucket walling with 'store resolution left getAll(...) unresolved' where the ancestry path runs through a string function wrapping a SQL-text native is Fix A.

---

### 92. toNonExecutableSQLString and its nonExecutable post-processor are absent

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: MISSING_FEATURE 1

Tests: `testNonExecutableSQLString`

**Mechanism** — Neither the toNonExecutableSQLString surface nor any behaviour behind the nonExecutable post-processor exists — nonExecutable is only an unimplemented native SIGNATURE. The reported wall is a compound of two honest-but-misattributed gates: Runner's executeShape list does not name toNonExecutableSQLString so mappingRefs is empty and the test takes the no-execute branch, and ExecCallFinder.findTerminal's stops set is {execute, toSQLString, toSQLStringPretty} so the side is unverifiable and sqlTextVerify falls to an advisory h2Upgrade. Nothing in the platform was ever asked to render this query.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1505 (NON_EXECUTABLE_PP is signature-only); core/src/main/java/com/legend/harness/ExecCallFinder.java:114-118; core/src/test/java/com/legend/rcorpus/Runner.java:879-900 and :1311-1315; core/src/main/java/com/legend/StatementExecutor.java:359-397; core/src/main/java/com/legend/lowering/SqlPostProcessors.java:23-31

**Fix** — Platform first, harness registration second — doing the registration alone would be compensation. (1) Add a nonExecutable IR pass beside SqlPostProcessors: a recursive SqlQuery rewrite that, for every SqlSelect reachable through union branches, subselect sources, join sides and projection sub-selects, PREPENDS EQUAL(IntLit(1), IntLit(2)) into where() (matching the engine's andFilters order, which puts `and 1 = 2` last in the rendered text). (2) Add the toNonExecutableSQLString(f, mapping, dbType, extensions) K-native next to toSqlString, running the same engineSql pipeline and applying the pass before render. (3) Then add the FQN to Runner's executeShape list and ExecCallFinder's stops. Independently, thread a reason out of sideSqlText so an unknown terminal and a thrown generation stop being indistinguishable nulls.

**Leverage** — Low as a pass target: the golden also carries _d# aliases, so even a correct nonExecutable pass moves this test from SHAPE to an honest sql-text diff, not to PASS. Its real worth is that it converts a misattributed wall ('no execute(|...) call') into a named missing feature, and the sideSqlText attribution fix it carries improves diagnosis quality for every other advisory in this bucket. Do it for the attribution, not for the test.

**Shares code with** — StatementExecutor.java:359-397 (shared with the toSQL and SQL-text-dispatch clusters), harness/ExecCallFinder.java:114-157 (shared with the golden-side-evaluator and rename-threading clusters — the swallow-to-null at :151-157 is blamed by three separate diagnoses in this bucket alone), Runner.java:879-900 executeShape list.

---

### 93. Null-safe equality gated on filter position instead of on operand multiplicity

**1 test** · effort **M** · confidence high · bucket 04 (?) · verdicts: REAL_DEFECT 1

Tests: `testNullSafeEqualityForOptionalProperties`

**Mechanism** — NullSemantics.equalNullArms gates the null-safe-equal arm on a FILTER_POS ThreadLocal entered only from Lowerer.filter, so an equality in PROJECTION position falls through to a bare EQUAL. The engine has no position condition: processEqual calls nullSafeEqualsOperation unconditionally and its decisive arm is purely multiplicity-based (both params lower bound 0 -> nullSafeEqual). The callingFromFilter flag legend-lite modelled is a DIFFERENT secondary string-layer mechanism (isEqualsFromFilter) that upgrades store-authored filter equalities; legend-lite implemented the secondary rule and omitted the primary one. Not cosmetic: NULL = NULL yields SQL NULL while `is not distinct from` yields TRUE, so the projected column VALUE differs. != is unaffected because notEqualNullArms has no position gate at all — that asymmetry is what exposes the bug.

**Owning code** — core/src/main/java/com/legend/lowering/NullSemantics.java:100-112 (FILTER_POS + enterFilter), :114-126 (equalNullArms), :125 (the bare EQUAL fall-through); core/src/main/java/com/legend/lowering/Lowerer.java:1208 (the only enterFilter site) and :1904 (sideCondition, the join-ON funnel); core/src/main/java/com/legend/resolver/StoreResolver.java:290-296 (withFeatureFlags discarded); core/src/main/java/com/legend/builtin/Pure.java:660-666

**Fix** — Delete FILTER_POS, the Scope interface and enterFilter, and drop the FILTER_POS conjunct from equalNullArms — the remaining predicate (two operands, both [0..1]) IS the engine's rule. Add the engine's two missing degenerate arms (an empty-multiplicity param yields IS_NULL of the other side; both empty yields constant true). Replace the deleted marker with its INVERSE, a NullSemantics.enterJoinCondition() suppression scope opened in Lowerer.sideCondition (the single funnel for every join ON), inside which equalNullArms returns the bare EQUAL — the honest structural analog, since in the engine a Database @join arrives at the SQL layer as an already-built DynaFunction and never passes through processEqual. Then delete the enterFilter wrapper at Lowerer.java:1208 and update the three stale comment blocks that describe the filter-gate model. REQUIRED companion: honour Feature.LEGACY_SQL_NULL_UNSAFE_EQUALS by threading the flag StoreResolver.resolveNode currently discards into the Lowerer so equalNullArms returns bare EQUAL when set, otherwise testLegacyFlagProjectionEmitsPlainEquals flips from accidentally-passing to failing.

**Leverage** — This test stays red (alias-gated) but the cluster is still worth doing: it is a wrong-VALUES defect, not a text defect, and it is the only entry in this bucket that changes what a projected column evaluates to. It closes four named tests in tests/query/testLegacyNullUnsafeEquals.pure (testDefaultProjectionIsNullSafe, testDefaultOptionalParamIsNullSafe via steps 1-3; testLegacyFlagProjectionEmitsPlainEquals, testLegacyFlagRestoresOptionalParamFreeMarkerSelector via step 4), all on the plan channel with reAliased aliases so none are alias-blocked. The join-ON suppression scope is not optional — without it, join keys start null-matching and rows change silently.

**Shares code with** — lowering/NullSemantics.java:100-126 and Lowerer.java:1208/1904, plus resolver/StoreResolver.java:290-296 (withFeatureFlags is discarded outright — any bucket blaming an unhonoured Feature flag shares that line). Any bucket whose projection emits a bare `=` where a golden says `is not distinct from`, or whose join ON emits `is not distinct from` where the golden says `=`, is this cluster — the two halves must land together.

---

### 94. meta::pure::functions::date::Duration class and date::add(Date, Duration) overloads are unported

**1 test** · effort **M** · confidence medium · bucket 04 (?) · verdicts: MISSING_FEATURE 1

Tests: `testToSQLStringWithCodeBlock`

**Mechanism** — legend-lite carries the DurationUnit ENUM but no Duration CLASS and no add(Date[1], Duration[1]) overload, so the code block's first statement (let endDate = %2015-01-01->add(^Duration(number=1, unit=DurationUnit.MONTHS))) cannot be typed. ExecCallFinder.sideSqlText catches the RuntimeException and returns null, so sqlTextVerify has a golden but no actual, falls to h2Upgrade, declines, and the test scores SHAPE 'sql-only: 1 advisory' — a message that hides which feature is missing.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:667-672 (DURATION_UNIT exists, Duration does not; ADJUST at :1114 is the existing host-fold template); core/src/main/java/com/legend/harness/ExecCallFinder.java:151-157 (the swallow); core/src/main/java/com/legend/harness/EngineTestExecutor.java:1015-1017 and :900-905

**Fix** — Add the missing date surface to builtin/Pure.java: a Duration class with number:Integer[1] and unit:DurationUnit[1], and the three date::add(Date|StrictDate|DateTime[1], Duration[1]) overloads, folded HOST-SIDE the way the existing adjust native already shifts Date+n+DurationUnit so %2015-01-01->add(^Duration(1, MONTHS)) folds to the literal 2015-02-01 the golden needs. Ensure the new overloads do not shadow the collection add(T[*],T[1]) — they differ in the second parameter's type but the overload scorer must actually discriminate on it. Separately, stop ExecCallFinder.sideSqlText erasing the cause: return the exception text through a side channel so a generation failure reports as a named platform wall.

**Leverage** — Weak and speculative as a pass target. Duration is only the FIRST wall — the test also passes a path literal as a Function<{T[1]->Date[0..1]}>[1] parameter and applies it via $x->map($path) inside a generic helper, which the diagnosis flags as the next candidates and did not verify. Confidence in 'Duration is the blocking cause' is medium and the falsifier is a one-line env-var re-run. Do the cheap falsifier BEFORE building the Duration surface. The attribution half (stop swallowing the exception) is worth doing unconditionally and is shared with three other clusters here.

**Shares code with** — builtin/Pure.java date natives and harness/ExecCallFinder.java:151-157. The sideSqlText swallow-to-null is independently blamed by this cluster, the toNonExecutableSQLString cluster and the toSQL cluster — one attribution fix serves all three and should be unbundled from any of them. Any bucket constructing ^Duration or calling date::add with a Duration merges here.

---

### 95. isEmpty over a to-many plan parameter emits SQL list length instead of the collectionSize template

**1 test** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testIsEmptyOnCollection`

**Mechanism** — $input->isEmpty() where input is a to-many PLAN PARAMETER must render as the engine freemarker template `(${collectionSize(input![])}) = 0`. Scalars' isEmpty rule dispatches purely on multiplicity (listValued = info().multiplicity().isMany()) and emits EQUAL(COALESCE(LIST_LENGTH(arg), 0), 0), treating the parameter as a SQL list value. In the engine-text channel the argument is a STRING-kind SqlExpr.PlanParam rendered as '${input?replace(...)}' and LIST_LENGTH spells `len` (DuckDB spellings), so the WHERE reads coalesce(len('${input...}'),0)=0 — a template that computes the string length of the rendered parameter. 'collectionSize' exists in main only as a support-function constant, never emitted into SQL. Plan envelope matches byte-for-byte up to the WHERE.

**Owning code** — lowering/Scalars.java:292-308, :2419-2421; sql/dialect/EngineStyleH2.java:990-993, :1136-1155; sql/Spellings.java:83; plan/PlanSupportFunctions.java:36-39

**Fix** — Add the plan-parameter arm at IR level so execution and text agree. In Scalars.java:292-308, when the isEmpty/isNotEmpty argument lowers to a SqlExpr.PlanParam of many multiplicity, emit a new one-arg SqlFn.VAR_COLLECTION_SIZE: EQUAL(VAR_COLLECTION_SIZE(param), 0) / GREATER(..., 0) instead of COALESCE(LIST_LENGTH(...)). In EngineStyleH2.expr, next to the IN/renderCollection arm at :1136-1155, render VAR_COLLECTION_SIZE as `(${collectionSize(<name>![])})`; the enclosing EQUAL supplies ` = 0` through the existing comparison path. DuckDb keeps a list_length spelling for the opcode since at execution time the parameter binds to a real collection. Scope strictly to SqlExpr.PlanParam arguments so carrier-list LIST_LENGTH shapes (CarrierStrategies.java:501-507) are untouched. Do not normalise the golden or relax PlanAsserts.

**Leverage** — Single test but the current output is a semantically wrong template (string length of a parameter), not just a text delta. High value per unit of risk.

**Shares code with** — Adds an SqlFn opcode and an EngineStyleH2.expr arm; buckets touching plan-parameter rendering (EngineStyleH2.java:990-1005) or Scalars collection predicates overlap here.

---

### 96. Multi-hop plan-variable path never mints a PlanParam placeholder

**1 test** · effort **M** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testPlanGenerationForMultipleExpressionsWithPropertyPath`

**Mechanism** — Dotted template parameters like ${endDateCalendar.fiscalYear.value} are minted by exactly one Lowerer arm, which folds a SINGLE hop off a bare let-bound variable (Lowerer.java:2285-2295). The query is `$endDateCalendar->toOne().fiscalYear.value` — two hops with a multiplicity-erasing toOne() between the variable and the first hop — so the arm never fires and the accesses fall through to the generic StructGet arm (:2371-2373). EngineStyleH2 then walks the nested StructGets to a base that is not a PlanParam and throws (:1038-1055). Even on the PlanParam path the StructGet fallback hard-codes a QUOTED '${...}' spelling, while the golden wants bare — the placeholder KIND must come from the LEAF property type.

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:2285-2295, :2371-2373; sql/dialect/EngineStyleH2.java:1038-1055; lowering/Fold.java:27-55

**Fix** — Replace the one-hop arm with a PATH-collecting arm placed before the generic struct arm: walk the TypedPropertyAccess spine upward collecting property names, peeling multiplicity-erasing wrappers on the way down (TypedNativeCall whose callee key is in Pure.nativeKeysAt("toOne")/"toOneMany"/"first", and TypedCast). If the spine bottoms at a TypedVariable whose letBindings value is a SqlExpr.PlanParam, yield a new PlanParam named `pp.name() + "." + join(".", path)` with kind Fold.planKindOf(outermost access type); otherwise fall through to StructGet. Leave the EngineStyleH2 StructGet wall standing as the honest wall for genuine struct extraction and do not relax its quoting — quoting is decided by PlanParam.Kind, which the new arm now supplies. Keep StructGet for StructLit-bound instance values.

**Leverage** — Small, well-localized change that fixes a printer correctness bug and generalizes to the datePeriods/reportEndDate plan goldens. Good value for M effort.

**Shares code with** — Owns lowering/Lowerer.java plan-variable arm plus the EngineStyleH2 StructGet renderer — any bucket blaming ${...} placeholder spelling or 'cannot render StructGet' belongs here.

---

### 97. Connection timeZone never reaches date-literal rendering

**1 test** · effort **M** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testInExecutionWithTempTableForDateTimesWithTz`

**Mechanism** — MatchFold.dateLit converts a TypedCDate straight to SqlExpr.DateLit/TimestampLit using the literal's own engine string, verbatim; no zone parameter exists on that path. The only timeZone plumbing (StatementExecutor.timeZoneOf) matches an inline `^...(timeZone='...')` literal and feeds plan TEXT only, never execution — and this test's zone arrives through two user functions plus an `if(isEmpty,|'GMT',|tz)` fold, so even that reader misses it. In-list literals render as UTC (2014-12-03 04:00:00 …) while the seeded table holds US/Arizona-shifted values (2014-12-02 21:00:00 …), so zero rows match instead of five.

**Owning code** — core/src/main/java/com/legend/lowering/MatchFold.java:75-96; StatementExecutor.java:1080-1097, 2006

**Fix** — Thread the connection zone (default 'GMT') to literal rendering, where the engine puts it (LiteralProcessor). (1) Extend StatementExecutor.timeZoneOf to survive user-call inlining and the constant-folded `if(isEmpty(x),|'GMT',|x)` binding. (2) Carry the resolved zone on the exec context alongside dbType/quoteIdentifiers into Phase I/J and hand it to the dialect. (3) At render, treat the Pure literal as a UTC instant and print it in the zone (`ZonedDateTime.of(lit, UTC).withZoneSameInstant(ZoneId.of(tz))`) with the precision-appropriate format; keep GMT as byte-identical identity. (4) Put the read-side inverse in the dialect's TIMESTAMP normalize, not the harness, gated on a non-GMT zone.

**Leverage** — Real wrong-rows bug and the only zone channel in the platform; likely also unblocks testDateTimeRetrieveWithTimeZone. Blast radius is every date literal, so the GMT-identity default is load-bearing.

**Shares code with** — StatementExecutor.java exec-context plumbing plus dialect render/normalize — overlaps any bucket blaming StatementExecutor argument reading or timestamp round-trips.

---

### 98. Join-terminal column resolved by spelled table name instead of the chain's sub-row

**1 test** · effort **M** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testIsolatioWhereNoConstaintsAndInnerJoin`

**Mechanism** — For `@J1 > @J2 | TABLE.COL`, the JoinNavigation arm builds innerScope = tableScope + {terminalTable -> subRow} and then resolves the terminal ColumnRef by plain name lookup. When the spelled table (firmTable) differs from the chain's terminal table (personTable) but is still in scope as the class mapping's root, the read silently binds to root.ADDRESSID instead of persontable.ADDRESSID. Two consequences: the case expression is always 'Europe', and because nothing reads through the join slot, Pipelines.walkJoinSlot cancels the join, collapsing 7 rows to 4.

**Owning code** — core/src/main/java/com/legend/normalizer/RelOpTranslator.java:539-562; MappingNormalizer.java:2618-2626 (and check 2844-2845)

**Fix** — When `jn.terminal()` is a plain ColumnRef, bypass name-scoped translate and yield `new AppliedProperty(subRow, cr.column())` directly, ignoring cr.table() — the engine's reprocessAliases rule. Guard it: if the terminal table's column set lacks cr.column(), throw a loud ModelException naming chain and column; the silent outer-scope fallback IS the bug. Leave non-ColumnRef terminals (FunctionCall etc.) on the current innerScope path, matching the engine's DynaFunction branch. Make the identical change at the MappingNormalizer JoinTerminalColumn arm; best shape is one shared `joinTerminalRead(terminal, subRow, terminalTable, outerScope, …)` helper called from both (and audit the third site at 2844-2845).

**Leverage** — High: silent wrong-rows AND wrong-row-count from a mapping resolution rule that every `@J | T.COL` read goes through. Likely leads in testFilters.pure and advancedRelationalSetUp.pure.

**Shares code with** — normalizer/RelOpTranslator.java + MappingNormalizer.java join-terminal scope construction; residual row differences may expose the missing JoinType threading in emitJoinChain (NavMaterializer.java:184-188), which other join buckets also blame.

---

### 99. Inline ^MapperPostProcessor table renames ignored, and renames never reach execution

**1 test** · effort **M** · confidence high · bucket 6 (wrong rows) · verdicts: MISSING_FEATURE 1

Tests: `testGraphFetchWithTableMapperPostProcessor`

**Mechanism** — RelationalMapperRenames recognises exactly one shape — a TypedNewInstance carrying `queryPostProcessorsWithParameter` whose entry is a call to relationalMapperPostProcessor. The test's runtime instead carries `postProcessors = ^MapperPostProcessor(mappers = ^TableNameMapper(...))`, so extract returns identity with no diagnostic. Worse, even the recognised channel is wired only into plan-text generation: the engineSql overload the execution path uses hardcodes UnaryOperator.identity() for tableRenames. So the executed SQL reads personTable and returns real employees where the test expects none (differentPersonTable exists but is never populated).

**Owning code** — core/src/main/java/com/legend/plan/RelationalMapperRenames.java (walk/readPostProcessor); StatementExecutor.java:405-413 (vs 2006)

**Fix** — (1) Make RelationalMapperRenames.walk also fire on a TypedNewInstance carrying a `postProcessors` property, and add a readMapperPostProcessor sibling recognising `^MapperPostProcessor(mappers=[…])` with `^TableNameMapper(schema=^SchemaNameMapper(from,to), from, to)` and bare `^SchemaNameMapper` entries, feeding the existing Cfg.tableTo/Cfg.schemaTo maps so rename() and SqlPostProcessors.apply are reused unchanged; resolve store identity the way readDatabaseMapper/readSchemaMapper does so same-named schemas stay distinct. (2) In StatementExecutor.engineSql(TypedLambda, String, SpecCompiler, ExecEnv, EngineStyleH2), extract renames from the runtime argument as the plan path already does at :2006 instead of passing identity. Until (1) lands, make an unrecognised MapperPostProcessor entry throw NotImplementedException rather than skip silently.

**Leverage** — Missing feature, not a subtle bug, but the silent-skip is the exact wrong-rows-instead-of-a-wall pattern the tenets forbid. Loud-failing first will dip the pass count before it rises.

**Shares code with** — StatementExecutor.java engineSql overload divergence — plan-text path and execution path read runtime config differently; any bucket where a connection option works in plan text but not in execution shares this root.

---

### 100. Enum push-down suppressed unconditionally; multi-source decode spelled as OR not IN

**1 test** · effort **M** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testExecutionPlanGenerationForLambdaFromWithEnumMapping`

**Mechanism** — engineSql calls PlanEnumForm.apply on EVERY relation-terminal plan, and apply rewrites any enum-typed projection whose expression is a decode CASE back to its raw store column — so the golden's push-down CASE never appears. That reduction is only correct for the executionPlan overloads taking a mapping/runtime argument; the 2-arg overload with ->from inside the query turns push-down ON. Independently, MappingNormalizer spells a multi-source-value branch as an OR of equalities, so even with the reduction suppressed the text would be `type = 'FTC' or type = 'FTO'` instead of the golden's `in ('FTC','FTO')`; nothing folds OR-of-equalities back into SQL IN.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:463-468 and :546-556; core/src/main/java/com/legend/plan/PlanEnumForm.java:32-57; core/src/main/java/com/legend/normalizer/MappingNormalizer.java:3053-3068; core/src/main/java/com/legend/sql/DecodeShapes.java:68-85

**Fix** — (1) Thread a pushDownEnum boolean through the plan path: set it true in planToString exactly on the :546-556 branch where args[1] is NOT a TypedPackageableRef, pass through engineSql, and skip PlanEnumForm.apply's PROJECTION arm when set. Split PlanEnumForm.apply into projection arm (suppressed under push-down) and where arm (always applied) — the engine gates push-down on !inFilter, so the filter-side selector rewrite must stay. (2) In MappingNormalizer:3053-3068 emit AppliedFunction("in", [sourceRead, PureCollection(lits)]) when sourceValues().size() > 1 (single value keeps equal); companion edit in DecodeShapes.conditionSource (:68-85) to accept a SqlFn.IN condition and return its first argument. EngineStyleH2 already renders that as the bare `a in (b, c)`. Must not be implemented in com/legend/harness.

**Leverage** — Change (2) also unblocks the testEnumerationMapping goldens at :386/:405/:443 and is a prerequisite for the external-format test — worth doing, but wide SQL-text blast radius.

**Shares code with** — plan/PlanEnumForm.java and StatementExecutor.java:463-468 are the enum decode surface; MappingNormalizer:3053-3068 changes execution SQL too — merge with any bucket blaming enum decode CASE text.

---

### 101. Deferred-lambda gate rejects the top type Any

**1 test** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testBusinessDatePropagationInColFunction_asQueryParam`

**Mechanism** — `{...}->cast(@FunctionDefinition<Any>)` fails because cast is declared with source `Any[m]`, i.e. parameter 0 is a ClassType(Any). The receiver is a LambdaFunction, which Typer.deferredArg classifies as deferred, so checkGeneric routes to checkWithDeferred, whose deferredShapesMatch LambdaFunction arm accepts ONLY a function-typed parameter or a bare TypeVar. Any is neither, the single cast/2 candidate is filtered out, and the arity.isEmpty() branch throws the observed message. The gate is wrong — in real Pure a lambda IS an Any. Two sibling guards encode the same wrong rule and would reject the candidate even with the first gate opened: lambdaAritiesFit and bindDeferredAndBuild's standalone-synthesis path, which is keyed to TypeVar only.

**Owning code** — compiler/spec/Typer.java:1434-1436, 1511-1518, 1573-1590, 1652-1653, 1691-1693, 1843-1848, 1902-1907; Pure.java:1153 (cast decl)

**Fix** — Three coordinated edits in Typer.java expressing one rule — a SELF-TYPABLE lambda also matches a parameter typed as the top type Any, and types there by standalone synthesis. (1) Add `isTopAny(Type)` using whatever constant Pure exposes for meta::pure::metamodel::type::Any (do not hard-code the string twice). (2) deferredShapesMatch LambdaFunction arm becomes `isFunctionTyped(t) || ((t instanceof Type.TypeVar || isTopAny(t)) && selfTypable(lf))`. (3) lambdaAritiesFit skips arity constraints for an Any param as it does for TypeVar. (4) bindDeferredAndBuild's standalone-synthesis guard accepts isTopAny alongside TypeVar so the lambda is synth'd and unified against Any rather than pushed through typeLambda. Do NOT widen to every non-function type — a lambda against String[1] must stay loud. Keep the selfTypable conjunct as the mitigation.

**Leverage** — Real typing defect with a principled one-rule fix, but opening the gate only clears the FIRST wall — the plan path must then see through TypedCast to the lambda. Expect a second wall, not a pass.

**Shares code with** — compiler/spec/Typer.java overload selection and deferred-argument prefiltering — widening it enlarges candidate sets for every Any-typed parameter (assertEquals, toString, print), so any bucket blaming overload resolution shares this surface.

---

### 102. Join order in the ClassSource pipeline follows declaration, not processing

**1 test** · effort **M** · confidence medium · bucket 2 (execution-plan) · verdicts: GOLDEN_TEXT_ONLY 1

Tests: `testExecutionPlanForQueryWithVariableRundateWithinLambda`

**Mechanism** — Everything in the plan is byte-identical to the golden except join ORDER in the from-tree, which shifts alias numbers (expected productclassificationtable_0/_1, emitted swapped). Aliases are not chosen at build time: EngineStyleH2.planSource walks the from-tree left-to-right and takes the next index in the lowercased-table-name group, so the alias diff is purely a consequence of order. That order comes from pipeline node order — Pipelines.materialize preserves it, cancelling only undemanded slots, and materializeRoot materializes navigate steps and join slots off that one pipeline. In milestoningmap, Product declares `classification` (a navigation, position 4) before stockProductName and classificationType, so the filter's navigation join lands ahead of the column-producing slot joins. The engine orders by processing: select-column joins first, filter joins appended after.

**Owning code** — resolver/StoreResolver.java:1755-1788, 2811-2812; Pipelines.java:341-378, 404-408; EngineStyleH2.java:281-347

**Fix** — One ordering rule in the resolver, not a per-test patch: when building the ClassSource pipeline for a class root whose result is the class itself, emit the join slots that produce SELECT columns ahead of the navigate steps demanded only by the query's filter/sort. Concretely, re-sequence the pipeline handed to Pipelines.materialize in StoreResolver.materializeRoot so demanded TypedJoinSlot nodes reached from cs.bindings() precede demanded TypedNavigate steps reached only from filterPaths; equivalently split the demand at StoreResolver.java:2811-2812 into projection-first order (paths = projectionPaths then filterPaths) and make pipeline construction honour that order rather than mapping-declaration order. Land only behind a full-sweep diff. Do not normalise alias numbers in the harness — EngineStyleH2 models the engine's alias plan deliberately.

**Leverage** — Low urgency, high risk. Rows are identical either way (same joins, same predicates); only alias numbering moves, and alias numbering is asserted by a large fraction of relational goldens.

**Shares code with** — StoreResolver pipeline ordering plus EngineStyleH2 alias assignment — any bucket whose only diff is alias numbers in SQL or plan text shares this mechanism and could be merged in.

---

### 103. Runtime.preprocessFunction missing and mangled function pointers unresolvable

**1 test** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testModelJoinForNonRelationalConcepts`

**Mechanism** — Two stacked defects, both hit type-checking getNoStoreRuntime(). (1) legend-lite's native Runtime declares only connectionStores (ENGINE_RUNTIME adds mappings), while real pure declares preprocessFunction, connectionStores and connectionByElement — so `^EngineRuntime(preprocessFunction = ...)` fails NewChecker's findProperty(...).orElseThrow. That lookup runs before the value is typed, hiding (2): the value is a bare function pointer carrying the engine's signature mangle. NameResolver.resolveNameMulti tests every tier against knownFqns, which holds only unmangled element FQNs, and has no demangling tier, so the name survives bare; Typer.classReference then demangles to `preprocessQueryDummy` and findFunction misses because FunctionCompiler.functionsAt extends bare-name lookup only to meta::pure packages.

**Owning code** — builtin/Pure.java:230, 234; compiler/spec/NewChecker.java:83-94; compiler/NameResolver.java:284-292, 532-592; compiler/spec/Typer.java:2148-2160, 2248, 2253-2259, 2294; FunctionCompiler.java:34-47

**Fix** — A. Extend the native Runtime declaration at Pure.java:230 to match legend-pure runtime.pure:17-22, adding `preprocessFunction: Function<{FunctionDefinition<Any>[1], Runtime[1] -> FunctionDefinition<Any>[1]}>[0..1]`. Function-typed properties with spelled signatures already parse in this file (DATABASE_CONNECTION.sqlQueryPostProcessors). Put it on Runtime, not ENGINE_RUNTIME — routing.pure reads it off a plain Runtime. B. In NameResolver.resolveNameMulti add a LAST tier after the prelude tier: if SignatureMangle.stripTail(name) is non-null, re-run the wildcard and own-package membership tests against pkg::base, and on a hit return pkg::name KEEPING the mangled tail — Typer already demangles an FQN and uses the tail's arity and return-type name to pick the overload, so returning the stripped form would discard the disambiguator. No planModel change: the expected SQL already matches the passing sibling tests.

**Leverage** — Good. Two contained edits with an already-correct downstream path (SQL is byte-identical to passing siblings), and edit B unblocks any body holding a bare mangled user-function pointer.

**Shares code with** — builtin/Pure.java native metamodel plus NameResolver's global name resolution — edit B changes resolution for every bare mangled identifier corpus-wide (autogeneration, calendarAggregation), so other buckets blaming unresolved mangled names merge here.

---

### 104. XStore end column view drops expression-bodied local properties

**1 test** · effort **M** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testPersonToFirmUsingFromProject`

**Mechanism** — XStorePureEnds.xstoreEndOf builds a relational end's column view from the class mapping's property mappings, but emits a RelationFunction.Col only when a LocalProperty's body is a plain PropertyMapping.Column; expression bodies land only in the `locals` name set. XStoreTradesMapping declares +entityIdFk/+entityNameFk with case(...) expression bodies, and both ends are relational, so MappingNormalizer takes the column-space route and RelationReads.xstore finds neither a column() nor an expr() Col for entityIdFk, throwing NotImplementedException. MappingNormalizer records that as a per-association poison, surfaced at query time by AssociationJoins.predicateMaterial as "association … is not mapped in mapping …". The Col.expr channel already exists end-to-end; the only missing producer is this conversion.

**Owning code** — core/src/main/java/com/legend/normalizer/XStorePureEnds.java:97-113 (drop sites :103-111); consumers RelationReads.java:117-127, MappingNormalizer.java:420-436,1116-1122

**Fix** — In the Relational-end arm of XStorePureEnds.xstoreEndOf, for a LocalProperty whose body is PropertyMapping.Expression (and symmetrically a top-level PropertyMapping.Expression), emit a Col carrying a $src-rooted ValueSpecification via RelOpTranslator.translate with the set's ~mainTable (MappingNormalizer.inferMainTableQuiet when unspelled) bound to Variable("src") — $src is required because RelationReads inlines through Col.bindSrc. Keep locals.add unchanged. Keep the wall loud for shapes flat translation cannot express: if collectJoinNavigations yields navigations or translate throws, let it propagate to the existing per-association poison so the message still names the property. Do not add a database-identity check (the corpus legitimately spells a cross-database table that canonicalizes to the same row).

**Leverage** — Real defect and a genuine unblock, but it converts poisoned associations into real SQL across the whole modelJoin/xStore family — expect SHAPE→FAIL churn before any pass.

**Shares code with** — Feeds both MappingNormalizer's XStore route (:1114) and ModelJoin route (:1215), so every modelJoin/mft-xStore bucket blaming "association not mapped" poisons shares this producer. Also exercises RelOpTranslator.

---

### 105. testDataGen no-seed root throws instead of emitting an Error plan node

**1 test** · effort **M** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testErrorDueToNoSeedForRoot`

**Mechanism** — The engine's contract for "caller supplied no row identifiers for a root table" is to emit an ErrorExecutionNode INTO the plan wrapping a probe query over the table's primary key — not to fail. legend-lite's planRootSql scans rowIds for a matching table+schema and, finding none, throws NotImplementedException("testDataGen plan: no row identifiers for root …"). The test deliberately passes `let tableRowIdentifiers = [];` and asserts the resulting Error-node plan text, so legend-lite walls exactly where the engine produces its expected output. Two small surfaces are absent: an Error node builder in PlanText, and the distinct no-seed probe SQL form.

**Owning code** — core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:1489-1492 (throw), :1441/:1448 (resultColumns), :1458-1460 (Allocation wrap); core/src/main/java/com/legend/plan/PlanText.java:165 (beside allocation)

**Fix** — Two edits. (1) Add an `error(message, inner)` node builder to PlanText beside `allocation`, emitting `Error( type = …::Any / message = … / ( inner ) )`; the message's trailing newline must be emitted ESCAPED as backslash-n, matching the engine's planToString. (2) Replace the throw with the engine's no-seed branch: build probe SQL as `select top 5 "<lowercased table>_0".<pk> as "<pk>" from <qualified table> as "<lowercased table>_0"` over PRIMARY KEY columns only (top 20 for a view root), keeping planMilestone's filter; set resultColumns to the PK columns only; wrap the Relational in the new Error node with message `Row Identifers should be provided for the root table: …`. Note the alias is `person_0`, not `root`. Restructure planNode so the no-seed case returns the Error node in place of the Allocation and does NOT recurse into children.

**Leverage** — Small, well-scoped, and the golden is fully specified — a likely clean pass. Control-flow care needed: allocating or recursing would leave a dangling ${res_c0}.

**Shares code with** — plan/PlanText.java gains a new node builder (additive, beside `allocation`) and shares TestDataGenerator.planNode with the view-slice cluster.

---

### 106. Post-execute relation sort must use host nulls-high comparator

**1 test** · effort **M** · confidence medium · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testChainedTwoHops`

**Mechanism** — A sort applied AFTER execute() to a materialised TDS is evaluated in memory by the engine: TestTDS.sortOneLevel sorts with safeNullsHigh then REVERSES for DESC, so DESC is nulls-FIRST. legend-lite splices that post-execute sort back onto the query chain and lowers it to ORDER BY with no nulls clause (Fold.sortNulls returns null by policy), so DuckDB's default_null_order gives NULLS LAST in both directions. ASC agrees, DESC diverges: the Apple-group null lands last instead of first. Row multisets are identical; only null placement differs.

**Owning code** — core/src/main/java/com/legend/compiler/spec/typed/TypedSort.java; StatementExecutor.java:2431-2613 (spliceHook), :2698-2724 (spliceValuesRead); lowering/Lowerer.java:1596 (sort), :1633 (sortOnto)

**Fix** — Add `boolean hostNullOrder` to TypedSort (threaded through withChildren, default false). In StatementExecutor.spliceHook, when the rewritten node's source resolves through an ExecFrame `.values` splice (same condition spliceValuesRead already detects), rebuild any enclosing TypedSort with hostNullOrder=true. In Lowerer.sort/sortOnto use `s.hostNullOrder() ? (asc ? NULLS_LAST : NULLS_FIRST) : Fold.sortNulls(asc)`. Leave Fold.sortNulls returning null for all in-query sorts — that policy was arrived at empirically and a global pin is known-bad (H2.java:267-273, Fold.java:334-343). Do not key the scope on asc/descending spelling; CoreFn.ASC/DESC collapse it.

**Leverage** — Single test, narrow flag. Worth doing only because a global pin is provably regressive — this is the one safe scoping. Low payoff, contained risk.

**Shares code with** — Touches lowering/Lowerer.java sort emission and StatementExecutor splice path — both blamed elsewhere; coordinate with any other TypedSort/spliceHook change.

---

### 107. Missing mapping-seam isolation barrier over windowed ~func pipelines

**1 test** · effort **M** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testMappingWithWindowColumn`

**Mechanism** — The mapping's `~func` pipeline (join then extend(over(...), RANK)) folds into ONE SqlSelect because Fold.windowFolds(src) is true. The query's own `filter(x|$x.age > 25)` then composes onto that SAME select: the predicate reads AGE not RANK, so windowRef is false and Fold.filterSlot picks WHERE. SQL evaluates WHERE strictly before window functions in the same SELECT, so RANK() is computed over the AGE>25 rows — Peter drops before ranking and John becomes rank 1 instead of 2. Fold.containsWindow's doc comment already states the correct doctrine (isolation is the RESOLVER's decision at the mapping seam); nothing in resolver/ implements it.

**Owning code** — core/src/main/java/com/legend/compiler/spec/typed/TypedSpec.java:17-83 (permits); resolver/ClassSources.java:807-814 (seam); lowering/Lowerer.java:3466 (existing isolate); Fold.java:243-252 (doctrine comment)

**Fix** — Add a unary marker node TypedIsolate(source, info) under compiler/spec/typed/ and to TypedSpec's permits list. In ClassSources.java:807-814, immediately before `return new ClassSource(...)`, walk the resolved pipeline's relation spine and, if it contains a TypedExtendWindow/TypedExtendAgg not already below a materialising boundary (TypedLimit/TypedDrop/TypedSlice/TypedDistinct/TypedGroupBy), wrap `pipeline = new TypedIsolate(pipeline, pipeline.info())`. Add `case TypedIsolate i -> isolate(relation(i.source()))` to Lowerer's relation dispatch, reusing Lowerer.java:3466. Add pass-through arms in StoreResolver.resolveNode (~:430-452) and Substitution's relation arm. Do NOT touch Fold.filterSlot — the FoldTest pin and PCT testExtendFilterOutNull require the current WHERE fold (this is the reverted 63a68804 shape).

**Leverage** — Real wrong-value correctness bug (rank computed over the wrong population). Single test today, but it implements a doctrine the codebase already wrote down — durable infrastructure.

**Shares code with** — Adds a node to TypedSpec's sealed permits and requires pass-through arms in resolver/Substitution.java and lowering/Lowerer.java — every bucket adding a Typed* node or switching on relation nodes must be merged with this.

---

### 108. Extra sub-identities in NavMaterializer skip the composite-chain treatment

**1 test** · effort **M** · confidence low · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testJoinIsolationDeeperTwoIsolations_LeftOuterLeftOuterThenInner`

**Mechanism** — Two differently-filtered navigations off the SAME `orgs` join-chain slot are materialized asymmetrically. `midByAlias.putIfAbsent` makes the FIRST head (team) the primary identity; the rest go to extraSubHeads/extraSubTails. The primary gets the composite treatment — compositeChainTarget pulls the sibling orgTreeOptimizationTable joinslot into the sub-target and rewriteNavPredicate replaces the condition with hop-1's oriented condition on pipelineForMat. foldExtraSubIdentities does none of that: it reads tNavSteps built from the ORIGINAL pipeline, joins the plain Org target, and uses step.predicate() verbatim. That predicate reads a sibling joinslot deliberately not demanded at parent level, so it is stripped, the LEFT join never matches, and `bu` is NULL on every row.

**Owning code** — core/src/main/java/com/legend/resolver/NavMaterializer.java:599-660 (foldExtraSubIdentities), :196-232, :237-283 and :264-266 (primary path), :186-193 (demand comment)

**Fix** — Give extra identities the primary's treatment. In foldExtraSubIdentities, after building `xPipe` and before constructing the TypedJoin, call `CorrelatedSubselects.CompositeChain xcc = corrSubs.compositeChainTarget(t, step.predicate(), xPipe);`. When xcc != null, use xcc.pipeline() as the join target (recompute xRow/xCols from `xcc.pipeline().info().type()`) and xcc.orientedCond() as the TypedJoin condition instead of step.predicate(); when null, keep today's flat form. Same call the primary path already makes at :264-266 — no new machinery, only per-identity invocation. The composite MUST be rebuilt per identity (not reused from compositeByAlias) so each filter lives inside its own isolated subselect. This test also needs the MappingNormalizer String-cast fix to go green.

**Leverage** — Wrong-rows correctness (a whole column silently NULL) and reuses existing machinery — but low confidence and it will surface NotImplementedException walls on shapes that currently degrade quietly.

**Shares code with** — Depends on the MappingNormalizer String-cast cluster (same test carries both defects). compositeChainTarget throws on several shapes (CorrelatedSubselects.java:1330-1370, 1394-1422) and chained-union tests are documented at :1379-1387 as relying on the flat degradation — coordinate with any union/join bucket.

---

### 109. Aggregate over a to-many hop behind a to-one head has no aggScan arm (STUDY #12 wall)

**1 test** · effort **M** · confidence medium · bucket 11 (unclassified) · verdicts: MISSING_FEATURE 1

Tests: `testFilterTimesWithManyOperands`

**Mechanism** — CorrelatedSubselects.aggScan dispatches aggregated navigations by path SHAPE and has no arm for [to-one hop, to-many hop, leaf]. The qualifier sumEmployeesAge() inlines to $p.firm->toOne().employees.age->sum(), so pathOf yields [firm, employees, age]. Every arm misses: the 2-element to-many-head arms need size 2; the CHAIN arm whose shape matches is gated on isCountFamily and path.size()==2; the DEEP/loud arms require a to-many head. Execution falls to the STUDY #12 wall, which fires honestly — a bare demand for firm.employees.age would explode the join and Scalars' to-one identity elision would silently eat the sum. Only the count-family sibling of this exact shape is implemented.

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:2069-2103 (CHAIN arm), :2141-2153 (STUDY #12 wall); reuses :100-121 buildAggMaterials, :141-190 foldChainMid, :902-916 aggColFor

**Fix** — Generalize the CHAIN arm from 'count over a 2-element to-one-headed path' to 'any aggregate whose to-many hop is at index 1 behind a to-one head'. Add a prefixNodeAt(arg, userVar, len) helper that peels toOne/property wrappers to recover the prefix expression node at a given path length (toManyHead cannot be used past index 0 — aggScan has no ClassSource for the mid target), plus an isToMany(node) multiplicity test. Then add an arm after the count arm gated on path.size()==3, !toManyHead(path[0]), bareHead(path[0]), !isToMany(prefix@1) && isToMany(prefix@2); it registers AggDemand under the dotted key path[0]+"."+path[1] with leaf path[2], recurses on remaining args, and returns. Leave the STUDY #12 wall untouched — it simply stops firing for this shape. No downstream change: buildAggMaterials/foldChainMid/aggColFor already route dotted keys.

**Leverage** — Decent. Reuses machinery already proven by testQualifiedPropertyUsingColumnProtocol, and study #12 appears exactly once in the sweep so no ripple. Expect SQL-text divergence from the engine's parent-copy subselect — rows match; don't chase the text.

**Shares code with** — Edits CorrelatedSubselects.java in a different region than the embedded-cast cluster (aggScan vs subTypeNavCastCanon) — same file, independent arms; sequence the edits to avoid conflicts.

---

### 110. Non-isolated emptiness input: hoist mapping ~filter out of the join target

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testInputNotIsolatedWhenPropertyPathIsToOne`

**Mechanism** — legend-engine's processSubEmpty isolates an isEmpty input only when the argument does NOT return at-least-to-one; with explicit ->toOne() calls the mapping ~filter stays in the OUTER WHERE, so `firmtable.LEGALNAME='Firm X' AND addresstable.NAME is null` yields 0 rows. legend-lite implements only the isolated emission (filter carried inside the join target pipeline), detects the dangerous shape, and walls: rewriteCallArms checks isEmptinessFamily && piercesToOne and throws when the head's AssocSub was built from a filter-bearing pipeline (filteredTarget).

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:689, 884-894; core/src/main/java/com/legend/resolver/StoreResolver.java:1525-1531, 2893

**Fix** — Model the engine's decision instead of walling. Complete the predicate: rename/extend piercesToOne (Substitution.java:689) into `returnsAtLeastToOneDataType`, adding the second half — any step whose multiplicity lower bound is >= 1 — so parity with pureToSQLQuery.pure:4227-4238 is auditable. When TRUE, do NOT isolate: build the emptiness input as an ordinary navigation chain, and at the AssocSub construction site (StoreResolver.java:1525-1531) strip the TypedFilter node(s) that containsFilter detects from target.pipeline() before materialising the AssocJoin, re-emitting the stripped predicate as a root-level filter over the joined row via the foldAssociationJoins mechanism (StoreResolver.java:2893). filteredTarget becomes the hoist trigger rather than the wall trigger. Delete the wall at Substitution.java:884-894 only after the hoist works.

**Leverage** — Correctness-shaped: today the isolated-only emission would return wrong row counts for this shape if the wall were merely removed. Single test, but the wall removal is unsafe without it.

**Shares code with** — Substitution.java emptiness-family arms and StoreResolver AssocSub construction — buckets blaming Substitution.rewriteCallArms may collide here.

---

### 111. Dotted-exists registration missing in nested predicate scopes

**1 test** · effort **M** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testNestedExistsWithExistsInAbstractProperty`

**Mechanism** — An exists whose head is a DOTTED path (['firm','employees']) relative to an INNER binder needs an ExistsSub registered under that dotted key. registerDottedExistsSubs exists and is consumed by rewriteCallArms, but has exactly one call site — the OUTER chain resolution. Nested scopes built by nestedScope -> scopeMaterials call only registerExistsSubs with HEADS-ONLY paths, so no dotted key is registered; the outer scope cannot cover because collectEmptinessChainPaths runs against the outer param and pathOf returns null. The exists arm then does not match, the generic rewriter descends, and assocLeaf('firm','employees') silently prefixes a class-typed join binding into a bare column read that the Lowerer cannot fold.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:1548, 1567-1574, 1583-1590, 1617-1623, 2889, 3287, 3336-3372, 3354, 3291; core/src/main/java/com/legend/resolver/Substitution.java:866-872, 2104

**Fix** — In StoreResolver.scopeMaterials (3336), after nestedAssocs/pipe are built (3350) and before the NestedScope is returned (3366), call registerDottedExistsSubs with innerOps, the nested assoc map and the nested scope. innerOps are exactly the TypedFilter nodes (3291) that registerDottedExistsSubs already scans at 1567-1574, so paths are collected relative to the INNER binder. Add a boolean to skip the terminal-lambda block at 1583-1590 (graph terminals do not exist in a nested predicate scope) rather than passing a fake tree. For this shape midToMany (1617-1623) is false, so the ordinary branch applies: chain = nestedAssocs.get("firm") and the leaf registers the correlated EXISTS on employees. Also add the class-typed loudness guard to Substitution.assocBindingRead (2104) so a future registration miss is a named wall, not a dangling column.

**Leverage** — Wiring a fix already written into a scope that never calls it — good value per line, and it converts a downstream Lowerer mystery into a resolver-level guarantee.

**Shares code with** — Shares the Substitution.assocBindingRead class-typed guard with the concatenate cluster; buckets blaming Lowerer for dangling assoc columns likely trace back here.

---

### 112. Nav-slot (join-mapped, non-association) property as a MID hop of a filter-position path

**1 test** · effort **M** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testChainedFiltersQuery`

**Mechanism** — A to-many crossing in FILTER position deliberately takes the association-JOIN route, so registerAssociationJoins walks the consumed path hop by hop. Only HOP 0 is gated on a ClassSource binding; every later hop calls associationJoin unconditionally, whose first act is findAssociationOf(...).orElseThrow. `locations` is not an Association end — it is a plain class property mapped by a Join PM, normalized into a legacyNavigate slot — so a mid-hop nav slot lands in an association-only path and throws a misleading 'not mapped in mapping'. AssociationJoins.isAssocOrNavHead already declares nav-slot and association heads to be the same family; associationJoin does not honour that contract. Sibling get/project tests work because collectOpChain re-roots per hop and never enters this walk.

**Owning code** — core/src/main/java/com/legend/resolver/AssociationJoins.java:232-254, 925-937, 944, 962, 963-966; core/src/main/java/com/legend/resolver/StoreResolver.java:1646-1685, 2337-2341, 2388

**Fix** — In AssociationJoins.associationJoin (line 944), insert a NAV-SLOT branch immediately before line 962, mirroring StoreResolver.java:1646-1685 in structure: look up cs.bindings().get(real), unwrap toOne, resolve the nav slot alias via InnerDemand.navSlotAlias over Pipelines.navSteps(cs.pipeline()), and when the step's target is a TypedGetAll bound in the mapping, build the AssocJoin from the navigate step (target = sources.get(mappingFqn, classFqn), condition = nav.predicate()) instead of from the association end. Everything after condition/target is end-agnostic — factor materialisation, slot demand, prefixes, temporal stamping and parked-pred application into a private helper taking (target ClassSource, condition TypedLambda) and call it from both branches; do not fork the method. Fix the orElseThrow message at 963-966. Do NOT skip nav-slot hops at StoreResolver.java:2388 — skipping drops the join and produces WRONG ROWS.

**Leverage** — High value: the tempting one-line alternative silently yields wrong rows. Unblocks any filter-position path crossing a join-mapped class property as a non-leading hop.

**Shares code with** — AssociationJoins/StoreResolver join construction — buckets reporting 'property X is not mapped in mapping' for a join-PM property should be checked against this branch.

---

### 113. XStore column-space route drops non-Column local properties on a Relational end

**1 test** · effort **M** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testCrossMappingWithRelOpWithJoinKeys`

**Mechanism** — The mapping declares a mapping-local `+ceoId` whose body is a PropertyMapping.JoinTerminalColumn (join chain + terminal column), not a plain column. XStorePureEnds.xstoreEndOf adds a Col only when `lp.body() instanceof PropertyMapping.Column`, so the JoinTerminalColumn is dropped from `cols` (it survives in `locals`, which the column-space route never consults). With both ends Relational, synthesizeXStoreMapping takes the COLUMN-space route; RelationReads.xstore finds no Col for `$that.ceoId` and throws 'has no column binding on the Relation mapping of Employee', recorded as association poison and resurfaced by AssociationJoins.predicateMaterial as 'association ... is not mapped'. The class-level path already handles this via translatePmToField's LocalProperty→JoinTerminalColumn arm.

**Owning code** — core/src/main/java/com/legend/normalizer/XStorePureEnds.java:92-121 (xstoreEndOf Relational branch); MappingNormalizer.java:1122 (synthesizeXStoreMapping); core/src/main/java/com/legend/resolver/AssociationJoins.java:1246-1300

**Fix** — In xstoreEndOf's Relational branch, when the set carries any LocalProperty whose body is not a PropertyMapping.Column (JoinTerminalColumn, Expression, Embedded), return the XEnd with `colsView = null` and a distinct `columnSpace=false` flag (rather than overloading `pure()`), keeping `localProps` fully populated. synthesizeXStoreMapping then routes to XStorePureEnds.synthesize, which emits `legacyLocalProperty($row,'ceoId')`; AssociationJoins.propertyCondToColumns substitutes each side through its set's composed bindings, where `ceoId` is already bound to the joined-slot read from translatePmToField. Verify `new CString(endA.setId())` is non-null for a Relational set (MappingNormalizer.setIdOf); pin the set id before emitting if it can be absent. Do not widen ClassMapping.RelationFunction.Col.expr() to carry a join — joins need FROM-clause participation.

**Leverage** — Contained, well-localized routing fix; unblocks any XStore/ModelJoin whose condition reads a `+local` bound by a join chain or expression on a Relational end.

**Shares code with** — Touches resolver/AssociationJoins condition substitution — shared with buckets blaming association predicate materialization.

---

### 114. filteredNavLeafRead peels class hops and toOne in the wrong order

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testQualifierWithIsolationXX`

**Mechanism** — Substitution.filteredNavLeafRead runs its class-hop peel and its toOne/first peel as two sequential loops. For `$f.employeeByLastName('Smith').firm->toOne().legalName` the outer leaf sees a toOne call as source, the hop loop collects nothing, and the method returns null. rewrite() then falls to rebuildWithInstanceFold, which rewrites the inner `.firm` alone; there the wrapper peel reaches the filter and the CLASS-typed `.firm` is treated as the leaf, projected as TypedFuncCol("firm") with no data-type guard (the two slot guards only test JoinSlot aliases, and a class-typed @Join binding is a NAVIGATE step — a disjoint set). The lowerer cannot resolve that column name. InnerDemand.collectChains has the same ordering bug, so [firm, legalName] is never demanded and no SubNav is built.

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:2588-2630, 2775, 2789-2792; core/src/main/java/com/legend/resolver/InnerDemand.java:264-297; StoreResolver.java:2176-2189

**Fix** — Three edits that must land together. (1) Substitution.filteredNavLeafRead :2588-2630 — collapse the two sequential peel loops into ONE interleaved loop: repeat { class-typed TypedPropertyAccess -> hops.add(0, prop), descend; 1-arg toOne/first/head -> descend, unwrapped=false, firstRow=true for first/head; else break }. Keep the post-loop multiplicity check on `unwrapped`; the existing SubNav dispatch at :2721-2740 then resolves legalName as a composed-prefix column. (2) InnerDemand.collectChains :264-297 — mirror the same interleaved peel when building `chain` so leafChains yields [firm, legalName] and navStepDemand publishes SubNav['firm']; without this (1) only converts the crash into the "nav step is not materialized yet" wall. (3) Guard at Substitution.java:2775: if the leaf type is a ClassType, throw the :1367-1372 wording rather than projecting a class-typed column.

**Leverage** — Real correctness fix plus a guard that converts a misleading lowerer error into an honest wall for a whole qualifier family. Edit (3) also improves the diagnosis of the sibling isolation tests.

**Shares code with** — Edits resolver/Substitution.java (filteredNavLeafRead and its class-typed-leaf guard) — the same file other buckets blame for default-throw and multi-hop walls; coordinate to avoid conflicting rewrites of the peel loops.

---

### 115. filtered-navigation LEAF is never demanded, so its join slot stays unmaterialized

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testChainedInnerJoinsWithQualifierInGroupBy`

**Mechanism** — For `$f.employees->filter(e|$e.lastName == $f.legalName)->toOne().extraInformation` the head is depth-1, so filteredNavLeafRead matches and builds the correlated scalar subquery — then walls because the LEAF binding reads an unmaterialized join slot. The exists target's slot demand comes from `InnerDemand.leaves(ops, head)`, which only collects path heads read by the LAMBDAS attached to the head; InnerDemand.collect adds the filter PREDICATE lambdas only, never the property read OFF the filter result. leafChains, the other demand route, requires chain.size() >= 2, so a single-segment leaf like `extraInformation` is dropped there too. Since that binding is a JOIN-SLOT binding, referencesAliasOn is true and Substitution throws.

**Owning code** — core/src/main/java/com/legend/resolver/InnerDemand.java:434-458, 162-170, 244-256; StoreResolver.java:2143-2149, 2266-2267; wall at Substitution.java:2760-2765

**Fix** — Demand the filtered-navigation LEAF, not just its predicates. Add to InnerDemand a collector that mirrors the existing recognizer so the two cannot drift — e.g. `static Set<String> filteredNavLeaves(List<TypedSpec> ops, String head)` built from the same walk as collect's PropertyAccess arm (:434-458) but recording `pna.property()` when the unwrapped source is a filter chain over `head`. Union its result into the `demandedLeaves` argument at StoreResolver.java:2266 (association route) and into `innerLeaves` at StoreResolver.java:2143-2149 (navigate route). AssociationJoins.java:993-1001 then collects the alias reads of the leaf's binding, materializes the MiddleTable/personExtensionTable slot into the exists target, and `aj.targetSlotPrefixes()` carries the prefix so Substitution.java:2766-2775's CONVERTED branch rewrites the read instead of the wall at :2760 firing.

**Leverage** — Closes a genuine demand-scan hole rather than widening a guard: any filtered-nav value read whose leaf is a join-slot binding currently walls. Substitution needs no edit, only the demand union.

**Shares code with** — Adds a demand collector consumed by StoreResolver; it only makes resolver/Substitution.java:2766-2775's existing CONVERTED branch reachable. Buckets seeing "leaf binding reads an unmaterialized join slot" at Substitution:2760 likely share this root cause.

---

### 116. Hoisted below-ops' non-hop reads dropped from flatten source demand

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testUnionToUnionJoinSequenceWithMultipleChildrenInUnionSourceTree`

**Mechanism** — Demand/materialize mismatch in the class-flatten hop. Person[set1] carries both a routed nav slot 'firm' and a JoinTerminalColumn 'extraInformation' backed by a hoisted physical join slot. FlattenOps.splitBelowOps sees the filter consume {[firm,legalName],[extraInformation]}; because [firm,...] passes through the hop head the WHOLE op is classified hoisted and its non-hop path [extraInformation] is discarded (spliceFull is only populated in the non-colliding arm). flattenNavSlot then materializes the source with an EMPTY join-slot demand, so walkJoinSlot strips 'PersonSet1PersonAdditional'. The hoisted filter is rewritten afterwards against a target carrying m.stripped(), renameRowVar hits the stripped-slot branch, and the 'STRIPPED join slot' message is thrown. The demand scan ignores reads the rewrite still performs.

**Owning code** — core/src/main/java/com/legend/resolver/FlattenOps.java:33-36 (BelowSplit), :64-85 (collides arm at :80); core/src/main/java/com/legend/resolver/StoreResolver.java:724-726 (empty demand), :796-801, :3433; Pipelines.java:405-406, :1208-1212 (throw)

**Fix** — (1) Extend FlattenOps.BelowSplit with Set<List<String>> hoistedFull and populate it in the if(collides) arm exactly as spliceFull is populated in the else arm. (2) In StoreResolver.flattenNavSlot, before the materialize call at :724, compute the source-side slot demand from bsp.hoistedFull(): for each path, look up src.bindings().get(SyntheticHeads.realHead(head)) and run CorrelatedSubselects.collectAliasReads against Pipelines.slotAliases(spliced), then Pipelines.closeOverConditions; pass that in place of Set.of(). slotAliases collects only TypedJoinSlot aliases, so the nav head 'firm' cannot leak into join-slot demand. Once demanded, walkJoinSlot promotes the slot to a real prefixed join and rewriteRowReads' two-level arm emits the prefixed flat column. Optionally harden the companion site StoreResolver.belowScope:3318-3319, which materializes with Set.of() while its rewriter carries the stripped set.

**Leverage** — Only test in the sweep with the 'STRIPPED join slot' message, so no co-beneficiaries — but it is a wrong-demand correctness bug in the flatten path, and the belowScope companion site is the same latent defect.

**Shares code with** — Rewrite side goes through StoreResolver.substitution/renameRowVar into resolver/Substitution.java:3155-3159 and Pipelines.rewriteRowReads — other buckets reporting stripped-slot or missing-prefixed-column errors after a map/flatten hop likely share this demand gap.

---

### 117. ModelJoin nesting is only one level deep

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testNestedModelJoinCompoundInnerCondition`

**Mechanism** — ModelJoinNesting.compose is not recursive. Person_Address synthesises fine standalone: compose collects the (person, profile) hop, composes the Person_Profile join, and returns nestedCols {person:{profile:{rank}}} so RelationReads' nested arm resolves $person.profile.rank. Person_Firm then fails: compose collects the (employees, address) hop, finds Person_Address's ModelJoin, and re-rewrites its RAW lambda body passing Map.of() as nestedCols and the overload that leaves model null. With empty nestedCols the nested arm cannot fire, $person.profile falls to the plain-column arm and throws 'no column binding'. The throw is recorded as a poison keyed <mapping>::Person_Firm, which is why the surfaced error blames Person_Firm while the parenthetical names Person_Address.

**Owning code** — core/src/main/java/com/legend/normalizer/ModelJoinNesting.java:106-136 (hop loop), :120-124 (non-recursive re-rewrite), :179-200 (collectNestedHops); RelationReads.java:67-89, :90-92; MappingNormalizer.java:419-435, :431 (poison key)

**Fix** — Make ModelJoinNesting.compose recursive. Inside the hop loop, before building nCond, call compose(...) on the nested ModelJoin with the nested pair/row-frames and pipelines to obtain a Composed inner. Then (a) rewrite the nested condition with RelationReads.rewrite(..., inner.nestedCols(), model) — passing both the nestedCols and the previously-dropped model; (b) use inner.pipeA() as the end-side pipeline the ColSpec join is applied to, so the 'profile' join lands on the person pipeline BEFORE the address join attaches, and inner.pipeB() as the ColSpec body in place of the bare nEnd.pipeline(). Thread a Set<String> visitingAssocFqns (or depth counter) through compose and throw on re-entry so mutually-navigating ModelJoins stay loud. Keep behaviour identical when inner.nestedCols() is empty so no passing ModelJoin golden changes shape. Separately, key the poison at MappingNormalizer.java:431 by the association that actually threw and prefix the outer association's name.

**Leverage** — Single test, no co-beneficiaries in the corpus (the $person.profile.… shape appears once). Worth doing only for the structural limit it removes; the poison-attribution half is cheap and improves every future ModelJoin diagnosis.

**Shares code with** — Same ModelJoinNesting.java:120-124 model=null site as the derived-property cluster; the RelationReads.rewrite signature change should be landed once for both.

---

### 118. Mapping `union::unionMappingWithEmbeddedProperty2` maps `Firm[firm_set1]` with an EMBEDDED block `bridge ( employees[set1]:[myDB]@PersonSet1FirmSet1, 

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testAdvancedEmbeddedInMappingQuery`

**Mechanism** — Mapping `union::unionMappingWithEmbeddedProperty2` maps `Firm[firm_set1]` with an EMBEDDED block `bridge ( employees[set1]:[myDB]@PersonSet1FirmSet1, employees[set2]:[myDB]@PersonSet2FirmSet1 )` — two class-typed Join sub-PMs for the SAME property `employees`, routed to the two members of Person's union (testUnion.pure:1340-1392). Two normalizer defects combine. (1) MIS-SCOPED COLLISION GUARD: JoinChainEmission.emitHopsForStructuralPm case `PropertyMapping.Embedded` (JoinChainEmission.java:119-144) throws when `p.aliasToTargetTable.containsKey(j.propertyName())` and the sub-property is class-typed to a mapped class. The FIRST `employees` sub-PM mints nav slot alias 'employees' (mintNavSlotAlias, JoinChainEmission.java:549-571) and registers it at JoinChainEmission.java:415; the SECOND sub-PM then trips the guard on the slot its own SIBLING just created. Same-named routed siblings at TOP level never hit this — they go through the plain `case PropertyMapping.Join` arm and dedup harmlessly at JoinChainEmission.java:294-299 — which is why the structurally identical `unionToUnionMapping` (Firm[firm_set1] { employees[set1], employees[set2] } at top level) passes. (2) MISSING ROUTE CLASSIFICATION: UnionSynthesis.classifyUnionRoutes (UnionSynthesis.java:197-205) only iterates `rcm.propertyMappings()`, never descending into Embedded/Otherwise/Inline bodies, so `p.unionRoutes` has no entry for 'employees' and even with the guard removed the emitted navigate would carry ONE member's join instead of the OR over both. The thrown NotImplementedException is caught by the per-class fault isolation in MappingNormalizer.java:326-340, recorded on `model.mappingPoisons` under `<mapping>::Firm`, and re-surfaces at query time as the observed 'class Firm is not mapped in mapping ... (<poison>)' from ClassSources.java:622-625. Note the same guard also poisons Person in this mapping (Person[set1] carries `bridge(firm[firm_set1], firm[firm_set2])`).

**Owning code** — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:128 — `if (sub instanceof PropertyMapping.Join j && p.aliasToTargetTable.containsKey(j.propertyName()) && classTypedTargetIfMapped(nr.name(), ...) != null)` then throw 'Embedded sub-PM ... collides with an existing pipeline slot of the same name' (message literal at :133-138, exactly the observed text); /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:415 — `p.aliasToTargetTable.put(slotAlias, targetTable)`: the first sibling's emission is what populates the map the guard then reads; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:294 — the nav arm's own dedup (`if (p.aliasToTargetTable.containsKey(navAlias)) { ...; continue; }`), i.e. a second same-named routed PM is ALREADY handled correctly at top level

**Fix** — Two coordinated changes in core/src/main/java/com/legend/normalizer. (A) UnionSynthesis.classifyUnionRoutes (UnionSynthesis.java:197-301): replace the flat scan of `rcm.propertyMappings()` with a recursive walk that threads the OWNER CLASS. Collect pairs (ownerClassFqn, PropertyMapping.Join with targetSetId != null): for `Embedded emb`, recurse into emb.propertyMappings() with owner = MappingNormalizer.findPropertyTypeDeep(owner, emb.propertyName(), model) as NameRef.name(); for `OtherwiseEmbedded oe`, recurse into oe.embedded() the same way; for `InlineEmbedded ie`, recurse into the referenced set's PMs with owner = referenced.className() (mirroring the owner derivations already used in JoinChainEmission.java:119-185); for `LocalProperty lp`, recurse into lp.body(). Then group by property name as today, but resolve `targetClass` via findPropertyTypeDeep on the PAIR's owner class, not rcm.className(). Everything downstream (memberOrdinalOf, routes list, p.unionRoutes.put) is unchanged. (B) JoinChainEmission: make the collision guard owner-aware instead of name-only. Add to Pipeline a `final Map<String,String> navSlotOwner = new LinkedHashMap<>();` recorded in mintNavSlotAlias (JoinChainEmission.java:549-571) alongside navSlotByProp — key = property name, value = the ownerClassFqn the slot was minted under (pass ownerClassFqn into mintNavSlotAlias; emitJoinChain already has it). Then in all three guards (Embedded at :128-139, OtherwiseEmbedded at :103-113, InlineEmbedded at :171-180) fire ONLY when the recorded owner differs from the current level's owner class, i.e. `String prior = p.navSlotOwner.get(j.propertyName()); if (prior != null && !prior.equals(<this level's owner fqn>)) throw ...`. Same-owner same-name siblings (the routed union members) then fall through to emitJoinChain, whose existing dedup at :294-299 emits ONE routed navigate carrying the OR over all p.unionRoutes entries — the same code path the passing top-level `unionToUnionMapping` uses. No change is needed in materializeEmbedded (MappingNormalizer.java:2668-2717): both same-named sub-PMs produce the identical ctor field `$row.employees`, and the LinkedHashMap put is idempotent.

**Leverage** — Singleton — folded in from its own diagnosis (clustering agent omitted it).

**Shares code with** — lineage/scanRelations/scanRelationsTests.pure:303 testUnionToUnion — its failure detail in the sweep is byte-identical (same poison on unionMappingWithEmbeddedProperty2). testDataGeneration/tests/testDataGeneration.pure:471 also binds this mapping and is a candidate.

---

### 119. Identical mechanism to the entry above, one mapping level up.

**1 test** · effort **M** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testAdvancedEmbeddedInMappingQuery`

**Mechanism** — Identical mechanism to the entry above, one mapping level up. `union::extend::unionMappingWithEmbeddedProperty2` (testUnionWithExtends.pure:588-616) `include`s `union::unionMappingWithEmbeddedProperty2` and re-declares the union over extends-sets: `Firm[my_firm_set1] extends [firm_set1]` with an EMPTY body, `*Firm : Operation union(my_firm_set1, my_firm_set2)`. The inherited `bridge ( employees[set1], employees[set2] )` embedded block is re-emitted under this mapping's synthesis, and the SAME guard at JoinChainEmission.java:128-139 fires on the second `employees` sibling — the observed poison text is byte-identical apart from the class/mapping FQNs (`union::extend::Firm`), which is itself the proof that the inherited PMs reached the guard. Route classification here additionally relies on UnionSynthesis.memberOrdinalOf's extends-lineage walk (UnionSynthesis.java:158-177) to map targetSetId `set1`/`set2` onto members `mySet1`/`mySet2`; that path already exists and is exercised by the passing extend::testSimpleQueryUnionToUnion.

**Owning code** — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/normalizer/JoinChainEmission.java:133 — the message literal 'Embedded sub-PM ... collides with an existing pipeline slot of the same name; distinct same-named class-typed joins across embedded levels are a roadmap feature' matches both sweep details verbatim; /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnionWithExtends.pure:588 — `Mapping meta::relational::tests::mapping::union::extend::unionMappingWithEmbeddedProperty2 ( include meta::relational::tests::mapping::union::unionMappingWithEmbeddedProperty2 ... Firm[my_firm_set1] extends [firm_set1] : Relational { } ...)`; /Users/neemsandv/legend/legend-engine/.../tests/mapping/union/testUnionWithExtends.pure:184 — the extend-package test body, same query `Firm.all()->filter(f|$f.bridge.employees->exists(e|$e.lastName == 'Wright'))`

**Fix** — No separate fix. Changes (A) and (B) described for the sibling test resolve this one too, because the failing code path is the same guard on the same inherited embedded block. Verify only that the owner-class key used in (B) is the CLASS fqn (e.g. `union::extend::Bridge` / the inherited Bridge), not the set id, so an inherited block and its parent block agree on provenance rather than colliding.

**Leverage** — Singleton — folded in from its own diagnosis (clustering agent omitted it).

---

### 120. Deep sub-aggregation under an inner map parameter is silently demoted to a flat join (aggScan / pathOf rooted on the outer var)

**2 tests** · effort **L** · confidence high · bucket 08 (?) · verdicts: MISSING_FEATURE 2

Tests: `testSubAggregationWithDeepAndOverlap`, `testSubAggregationWithDeepAndOverlap_WithColVar`

**Mechanism** — For `col(f:Firm[1]|$f.employees->map(e|2 + $e.locations.place->count()),'c')`, every aggregate arm in CorrelatedSubselects.aggScan is guarded by `Substitution.pathOf(nc.args().get(0), userVar) != null` where userVar is the OUTER project-lambda param `f`. The count's argument is rooted at the INNER map param `e`, so pathOf bottoms out on the wrong TypedVariable and returns null (Substitution.java:709-740); every arm is skipped and even the `path == null && containsToManyCrossing(..., userVar, ...)` loud guard does not fire because the crossing is under `e`. The aggregate demand is DROPPED. Control falls to the VALUE-POSITION fan-out arm (CorrelatedSubselects.java:2166-2181), which harvests StoreResolver.consumedPaths on the mapper body — and consumedPaths does not exclude aggregate arguments (StoreResolver.java:3048-3062) — so ["employees","locations","place"] is registered as a BARE demand: a FLAT left join through locationTable instead of a grouped subselect. With no AggDemand, the `count(...)` survives as an ordinary scalar native and Scalars.lower throws (Scalars.java:2384) because count is registered only as an aggregate reducer (Aggregates.java:33). The wall is incidental: the real defect is that the silent demotion is a wrong-rows hazard for any query that does NOT happen to trip the scalar-count wall. _WithColVar is the identical query behind one extra shape gap: the harness inlines `let cols = [...]->cast(@BasicColumnSpecification<Firm>)`, Typer.rawSchemaErasedExpansion returns null because `cast` has a native overload (Typer.java:1348-1360), ProjectChecker.normalizeLegacyForms falls through all three arms, and withMappedColumns throws (ProjectChecker.java:264).

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:1965-2158 (aggScan aggregate arms + path==null guard), :2166-2181 (value-position fan-out arm), :100-122 (buildAggMaterials dotted-key branch), :141-186 (foldChainMid private-mid minting); core/src/main/java/com/legend/resolver/Substitution.java:709-740 (pathOf); core/src/main/java/com/legend/resolver/StoreResolver.java:3048-3062 (consumedPaths); core/src/main/java/com/legend/compiler/spec/ProjectChecker.java:44 and :104-165 (cast peel, _WithColVar only)

**Fix** — One resolver change in three coordinated edits, plus one independent S-sized compiler peel that only _WithColVar needs. Resolver: (1) in the value-position fan-out arm (CorrelatedSubselects.java:2166), BEFORE harvesting consumedPaths as bare demands, recursively aggScan the mapper body with the mapper's OWN parameter as userVar against the ClassSource of the map's element class (obtained the way buildAggMaterials obtains midAj.target()), register the found demands under the DOTTED chain key `sp.get(0) + "." + innerHead` (here "employees.locations"), and EXCLUDE the paths consumed inside those aggregate arguments from bareOut — double-registering leaves the flat locationTable join and explodes rows. (2) buildAggMaterials (:100-122): add the case where the dotted mid head is ALREADY a demanded to-many slot on the pipe and reuse that slot's prefix instead of calling assocMaterial.aggJoinMaterial for the mid. (3) foldChainMid (:141-186): when the mid slot is shared (the OVERLAP in the test name — column 'b' already explodes employees into persontable_0), skip the extra TypedJoin and re-point the final hop's parent-side condition onto the EXISTING prefix; keep chain-private mid as the DEFAULT so the passing `count($p.firm.employees)` family is untouched. Compiler (for _WithColVar only): in ProjectChecker.check, right after FQN canonicalization at :44, peel a type-ascription wrapper from the columns position — `cast`/`to`/`toMany` with a 2nd param that is a TypeExpression whose typeName endsWith "ColumnSpecification" — replacing param 1 with the wrapped collection; keep the guard narrow to the ColumnSpecification family so an unrelated cast still walls. Do NOT register `count` in Scalars.RULES to silence the wall (a scalar COUNT over an exploded flat join returns a silently wrong number), and do NOT special-case either column shape in the harness. If the full resolver work is too large for this slice, the honest interim is to make aggScan WALL loudly when an aggregate is found under an inner map parameter over a to-many head instead of demoting it to a bare join.

**Leverage** — Two tests for L effort, so mediocre on a per-test basis — but this is the one cluster in the bucket where the underlying defect is a SILENT WRONG-ROWS hazard, not just a missing surface: any deep sub-aggregation that does not happen to contain a scalar-unlowerable `count` today produces a flat join and wrong counts with no wall at all. Even if the full three-edit fix is deferred, the interim loud-wall change is cheap and strictly improves correctness posture. The _WithColVar cast peel is S, self-contained, and independently useful, but it unblocks nothing on its own — sequencing it first only converts one error message into another.

**Shares code with** — Heavy overlap with files other buckets blame. resolver/Substitution.java:709-740 (pathOf returns null for anything not rooted at the named userVar) is the generic mechanism behind any 'demand silently dropped inside a nested lambda' failure. resolver/StoreResolver.java:3048-3062 (consumedPaths has no aggregate-argument exclusion) will be blamed by any bucket seeing over-broad flat joins. lowering/Scalars.java:2384 is only the OBSERVED wall here — buckets reporting 'no scalar lowering registered for count/sum/max' should be checked against this resolver mechanism before anyone adds a Scalars.RULES entry. compiler/spec/Typer.java:1348-1360 (rawSchemaErasedExpansion returns null whenever an arity-matching candidate isNative, which `cast` is) plus compiler/spec/ProjectChecker.java:104-165 is a reusable cluster seed for any other test that wraps a columns/relation argument in cast/to/toMany.

---

### 121. Relational temp-table DDL vocabulary: datatype prelude classes + Column construction + createTempTable/dropTempTable natives

**2 tests** · effort **L** · confidence high · bucket 01 (?) · verdicts: MISSING_FEATURE 2

Tests: `testCreateTempTableStatement`, `dropAndCreateTempTable`

**Mechanism** — Both bodies construct `^Column(name='col', type=^meta::relational::metamodel::datatype::Integer())`. legend-lite's prelude declares exactly ONE class under that package (`datatype::DataType`), so NewChecker.check throws `unknown class 'meta::relational::metamodel::datatype::Integer'`, and Runner.unknownTypePull cannot rescue it because the datatype classes live in legend-pure's platform tree, not the corpus. Behind that shared wall: StatementExecutor.constructNode has no `case "Column"` arm (so a constructed ^Column has no host value), `createTempTableStatement` is neither native nor evaluated from the corpus body, and — for dropAndCreateTempTable only — the natives `execute::createTempTable`/`dropTempTable` do not exist at all (zero grep hits), while the sibling DDL natives createTableStatement/dropTableStatement do.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:351 (only datatype::DataType registered), :347 (Column.type demands a DataType value that cannot be spelled), :1652-1657 (existing toDDL natives, the pattern to copy); core/src/main/java/com/legend/compiler/spec/NewChecker.java:67-70 (the throw); core/src/main/java/com/legend/StatementExecutor.java:1517-1610 (constructNode, no Column arm), :3009-3014/:3273 (the executeInDb K-native to route through); core/src/main/java/com/legend/exec/MetamodelWalk.java:59 / :1126 / :1546-1573 (ColH, property nav, sqlText already maps Integer_ -> INT); core/src/main/java/com/legend/compiler/NameResolver.java:243-256 (PRELUDE_TYPES/PRELUDE_COLLISIONS)

**Fix** — One work item, superset-ordered: (a) Pure.java — register the legend-pure datatype hierarchy verbatim beside :351 (CoreDataType + ~22 concrete leaves; Varchar/Char/Varbinary/Binary carry size, Decimal/Numeric carry precision+scale), mirroring relational.pure:392-470; (b) MANDATORY companion — add a primitive-first tier at the head of NameResolver.resolveNameMulti (NameResolver.java:532-593) mirroring legend-pure ImportStub.java:183-186, so bare `Integer`/`Boolean`/`Date`/`Float` still resolve to primitives, and add every colliding simple name to PRELUDE_COLLISIONS (:256); (c) StatementExecutor.constructNode — add a `case "Column"` arm carrying name+datatype and a datatype arm mapping a constructed `datatype::X` NewInstance to MetamodelWalk.Dt, plus $c.type/$c.name navigation on the new value; (d) route `toDDL::createTempTableStatement` through the corpus body (the file is demand-pullable) and eval the returned lambda; (e) declare `execute::createTempTable` (both overloads) and `dropTempTable` in Pure.java copying legend-pure functions.pure:44-48, and add K-native arms in StatementExecutor beside executeInDb that EVALUATE the sql lambda (as CreateTempTable.java:73 does) and route the produced statement through the same executeInDb path. (a)-(d) alone green testCreateTempTableStatement; (a)-(e) green both.

**Leverage** — Good: 2 tests for one contiguous change, and step (a)+(b) is the shared foundation that 3 other clusters in this bucket (testTranslateDbType, testTempTableSqlStatementsForH2, testProcessIdentifierWithQuoteChar) also need — land the datatype block + primitive tier ONCE, here, and the other clusters shrink to their tails. Caveat on dropAndCreateTempTable: the new effectful native must be registered in StatementExecutor's executeInDb-family effect guards (:3061/:3086/:3131) or it will double-execute, and the H2 `Create LOCAL TEMPORARY TABLE` text may not run on DuckDB — that residue would be a separate EXECUTION_TARGET_ARTIFACT, not a reason to bend the DDL text.

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:351 (datatype prelude block) and core/src/main/java/com/legend/compiler/NameResolver.java:532-593 (primitive-first tier) — both are cited as the fix or the honest-wall change in 4 clusters here and are named as affecting postprocessor/tests/testPostProcessor.pure, tests/testRelationalExtension.pure and milestoning's ^datatype::SemiStructured(). Also touches StatementExecutor.java constructNode (:1517-1610) and the executeInDb K-native (:3009-3014, :3273) — merge with any other bucket blaming those.

---

### 122. View-backed class extent is dissolved instead of kept as a named frame on the left of a join

**2 tests** · effort **L** · confidence medium · bucket 04 (?) · verdicts: REAL_DEFECT 2

Tests: `testViewSimpleExists`, `testReplaceTablePostProcessorWithView`

**Mechanism** — The PersonFirmView frame is built correctly by the normalizer, but its SQL body (three plain Column projections over a personTable-join-firmTable source, no clauses) satisfies isRenameOnlySelect, so when the exists-correlation join arrives with a prefix Lowerer.join takes the hosting branch: the frame's join tree becomes the LEFT side and its renames are carried away. The emitted subquery therefore reads from personTable ... left outer join firmTable, the predicate reads firmtable_0.LEGALNAME instead of personfirmview_0.firm_name, and PersonFirmView.PERSON_ID degrades to persontable_1.ID — which also reverses the drive side so the mapping's (INNER) hop is lost. Frame identity is only ever attached to the RIGHT side of a join; the LEFT has a special case for union frames but none for view frames, and asLeftJoinSide hard-codes frameName null. Neighbouring view tests pass only incidentally (one view is ~distinct, one has the view on the right, one has a Subselect source).

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:1718 (hosting branch), :1842-1858 (isRenameOnlySelect), :1734 and :1763-1766 (unionFramed, the existing precedent), :1739-1741 (frameName applied to the right only), :1867-1868 (asLeftJoinSide passes null); core/src/main/java/com/legend/resolver/ViewFrames.java:26-97 with its only call sites StoreResolver.java:888, :1967, :2006

**Fix** — Preserve view-frame identity on the LEFT side of a join, mirroring the union-frame treatment. Carry the frame name onto the extent itself — when ViewFrames.frameNameOf(ctx, cs) is non-null have the extent lower as SqlSelect.starOf(new SqlSource.Subselect(body, nextAlias(), viewName)) — and add a viewFramed(leftSel) guard alongside unionFramed in Lowerer.join so the isRenameOnlySelect hosting branch is skipped for a view-framed left, passing the frame name into the wrap instead of the null at :1868. Narrow the guard to view frames specifically. Keeping the frame also stops the SalesPerson_PersonView condition degrading to personTable.ID, which should restore the engine's drive order.

**Leverage** — Two tests, one change, and the WithView entry explicitly ledgers itself behind testViewSimpleExists — fix and verify on the latter, which has no post-processor confound. Real value beyond text: the flattening changes join cardinality and column visibility and turns a mapped (INNER) hop into a LEFT OUTER, which is only row-equivalent by luck here. Risk is real though — the rename-only hosting branch is what keeps prefix chains in one flat SELECT, so a too-wide guard grows spurious subselects across many passing flat-join goldens. testReplaceTablePostProcessorWithView additionally needs the alias-plan-timing fix from the post-processor cluster before it goes green.

**Shares code with** — lowering/Lowerer.java:1708-1741 and :1842-1868, resolver/ViewFrames.java. Any bucket whose got flattens a view into the enclosing FROM where the golden shows a named `... as "<viewname>_0"` derived table is this cluster — the diagnosis names testViewPropertyFilterWithPrimaryKey as a shape-alike. Also touches Lowerer.join, which the isolation-strategy cluster's Star-rename rider does not, but the same method is where a MoveFilterInOnClause emission would land.

---

### 123. Filtered-navigation join lift (scalar-consumed qualified properties)

**2 tests** · effort **L** · confidence medium · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 2

Tests: `testRelationalMapperWithJoin`, `testRelationalMapperTwoDBs`

**Mechanism** — `toOne(filter($x.coll, pred)).leaf` is excluded from the synthetic join lift by the `scalar read (upper==1) && directlyOnVar(f.source())` guard in SyntheticHeads. It falls to `Substitution.filteredNavLeafRead`, which builds a correlated [0..1] single-column relation that the lowerer renders as a scalar subquery (`select (select ...`). The engine instead lifts a `coll#fN` synthetic head and parks the closed predicate in the association join's ON clause, reading the leaf off the shared alias. TwoDBs hits the same loss twice: projection position (scalar subselect instead of `"synonymtable_0".NAME`) and filter position, where `$p.cusip=='CUSIP1'` takes the explicit EXISTS fold instead of an outer-WHERE comparison against that same lifted alias.

**Owning code** — core/src/main/java/com/legend/resolver/SyntheticHeads.java:367-371; core/src/main/java/com/legend/resolver/Substitution.java:2587-2790; core/src/main/java/com/legend/resolver/Substitution.java:1005-1046; core/src/main/java/com/legend/resolver/StoreResolver.java:557-593

**Fix** — Drop the `&& directlyOnVar(f.source())` exclusion at SyntheticHeads.java:371 so depth-1 scalar filtered reads mint a `#fN` head exactly as the [*] spelling already does; `augmentNavPredicates`/`AssociationJoins.andCorrelatedIntoCondition` then compose the predicate into the ON clause. Narrow `Substitution.filteredNavLeafRead` so it claims only shapes no join route can serve. For TwoDBs additionally narrow the EXISTS fold at Substitution.java:1007-1046 so equality on a qualified property becomes an outer-WHERE comparison against the SAME lifted alias (parkFiltered already reuses one identity for equal predicates, which is what makes `synonymtable_0` appear once), and confirm the mapping-level filter lands in that ON clause. Accept engine semantics: `->toOne()` imposes no one-row constraint; no-match yields NULL through the LEFT JOIN. Stage on projection position first if a smaller landing is wanted.

**Leverage** — Highest-leverage platform fix here. Retires a whole qualified-property class (U30's runtime 'more than one row' error, U34/U42 qualifier tests) and the two mapper tests. Large blast radius: every scalar-subselect golden moves.

**Shares code with** — Owns `resolver/Substitution.java` (filteredNavLeafRead + the EXISTS fold) — any other bucket blaming Substitution for scalar subselects or EXISTS in qualified-property SQL is the same change and should merge here. Also touches StoreResolver join-condition composition.

---

### 124. processInOperation post-processor absent (temp-table / inFilterClause IN rewrite)

**2 tests** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `testExecutionPlanGenerationForInWithVarAndConstantInputs`, `testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs`

**Mechanism** — legend-lite has no equivalent of the engine's first default plan post-processor. The engine rewrites IN right-sides either into a temp-table subselect (literal list over threshold: 50 for test connections, 32767 for DB2) or into a ${inFilterClause_<var>} placeholder backed by Allocation + FreeMarkerConditional + CreateAndPopulateTempTable, and wraps the plan in RelationalBlockExecutionNode (FPVN prepended inside it) rather than Sequence. legend-lite's sequencePlan unconditionally emits Sequence(FPVN, lets, Relational) and the dialect renders the IN list inline via renderCollection — which is exactly the text the engine relegates to the falseBlock Constant. No collectionThreshold / tempTableForIn / inFilterClause / CreateAndPopulateTempTable anywhere in core/src/main/java.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:825-895 (sequencePlan); new com/legend/plan/InOperationPostProcessor; com/legend/plan/PlanText.java (relationalBlock + CreateAndPopulateTempTable + FreeMarkerConditional printers); com/legend/sql/dialect/EngineStyleH2.java:1155-1163 (renderCollection, must be shared not duplicated); com/legend/sql/dialect/EngineStyleDB2.java:61-66 (separate top-level-AND paren defect)

**Fix** — Add a plan-channel InOperationPostProcessor: walk the lowered IR for IN nodes whose right side is a literal list or ZeroMany PlanParam; threshold 50 for TestDatabaseConnection / LocalH2, else the dialect limit. Literal-list-over-threshold branch emits Allocation(tempVarForIn_<id>, Constant(values)) + CreateAndPopulateTempTable and a temp-table subselect. Placeholder branch emits Allocation(inFilterClause_<var>, FreeMarkerConditional(condition, trueBlock=Sequence(CreateAndPopulateTempTable, Constant), falseBlock=Constant(shared renderCollection text))). When a CreateAndPopulateTempTable exists, sequencePlan must emit RelationalBlockExecutionNode with FPVN as first child. The engine's temp-table id counter (tempTableForIn_4) must be reproduced from transform-memo size; if it can't be, wall rather than emit a wrong id. DB2 SESSION. prefix and two- vs three-disjunct conditions follow the connection type. Fix EngineStyleDB2.whereSql parens as its own change.

**Leverage** — Two tests here plus testRelationalResultSourcing and other collection-param goldens, but it is a large faithful port of engine plan post-processing and the id counter is the hard part.

**Shares code with** — Touches StatementExecutor.sequencePlan (:825-895) and plan/PlanText.java node vocabulary — merge with any other bucket blaming plan envelope shape or missing plan node kinds.

---

### 125. Projection-position correlated aggregate uses target-grouped shape instead of engine parent-copy aggCol

**2 tests** · effort **L** · confidence medium · bucket 2 (execution-plan) · verdicts: GOLDEN_TEXT_ONLY 2

Tests: `testMapWithOpenVariable`, `testMapWithOpenVariableOutsideBlock`

**Mechanism** — CorrelatedSubselects takes an early-return shortcut for projection-position aggregate demands with plain equi keys, returning the target pipeline grouped by the TARGET key (agg_0 under a target alias). The engine always uses parent-copy isolation: re-root on the PARENT extent left-outer-joined to the target, group by the parent key, name the column aggCol under a parent-derived alias. Secondary text delta: legend-lite wraps count-family aggregate reads in `case when col is not null then col else 0 end` (zeroWhenEmpty); the engine emits no guard. Rows are not asserted here, and legend-lite's rows are arguably the pure-correct ones.

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:205-214 (shortcut) and :219-266 (parentCopy path); core/src/main/java/com/legend/resolver/StoreResolver.java:2046-2052, :2069-2071, :2092-2093, :1984-1987; core/src/main/java/com/legend/resolver/Substitution.java:1658-1676

**Fix** — Remove the !filterPosition escape at CorrelatedSubselects.java:205-214 so projection-position aggregate demands also fall through to the parentCopy path. Three companions are required for text parity: (a) name the aggregate column aggCol rather than agg_<n> when the group carries exactly one aggregate demand (StoreResolver:2046-2052); (b) derive the outer join alias from the PARENT table rather than the target (StoreResolver:2069-2071); (c) drop the zeroWhenEmpty count guard on this path (StoreResolver:2092-2093 / Substitution:1663-1674). Honest alternative: ledger. These tests assert only plan text, and (c) is a real semantic regression — count over a firm with zero employees becomes 1 under the engine's left-outer-join count(*) where pure (and legend-lite today) says 0.

**Leverage** — Two tests plus many aggCol goldens, but the price is a knowingly wrong count semantic and a change to every to-many aggregate's SQL. Ledger is defensible.

**Shares code with** — resolver/Substitution.java:1658-1676 (zeroWhenEmpty rewrite) and StoreResolver aggregate naming — merge with any bucket blaming aggCol naming, subselect aliasing, or count coalescing.

---

### 126. withFeatureFlags discards its flag argument; null-safe equality is filter-position-only

**2 tests** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 2

Tests: `testLegacyFlagRestoresOptionalParamFreeMarkerSelector`, `testDefaultProjectionIsNullSafe`

**Mechanism** — One mechanism: legend-lite has no query-scoped feature-flag state. meta::pure::executionPlan::featureFlag::withFeatureFlags is peeled as pure identity in StoreResolver.resolveNode and StatementExecutor.buildFrame, throwing away arg 1 — but real routing reifies that flag onto the ExecutionContext, where defaultState sets State.legacyNullUnsafeEquals. Two symptoms follow. EngineStyleH2.optionalParamEquality gates the legacy FreeMarker selector on the parameter's SQL Kind (DATE/DATETIME) instead of the flag, so an Integer optional param falls through to `is not distinct from`. And NullSemantics.equalNullArms emits NULL_SAFE_EQUAL only under FILTER_POS, modelling the render-layer callingFromFilter but never the position-independent router-layer nullSafeEqualsOperation — so a projection `==` renders plain `=`. Neither symptom can be fixed without the flag; each regresses the other's test if landed alone.

**Owning code** — core/src/main/java/com/legend/lowering/NullSemantics.java:117-125 (new QueryFeatureFlags.java beside EngineTextBoundary); StoreResolver.java:291-295; StatementExecutor.java:457,1157-1169,2091-2093; EngineStyleH2.java:534,555,566-568,947-951

**Fix** — One coordinated change. (1) Add core/src/main/java/com/legend/lowering/QueryFeatureFlags.java — a thread-scoped Set<String> holder modelled exactly on EngineTextBoundary, exposing legacyNullUnsafeEquals(). (2) Capture the flags where they are discarded: a featureFlagsIn(body) static scan (next to StatementExecutor.firstFromMapping) collecting arg-1 enum value names from withFeatureFlags calls; wrap StatementExecutor.engineSql's try-with-resources at :457 with QueryFeatureFlags.enter(...) alongside EngineTextBoundary.enter(). Leave both identity peels as-is. (3) Thread a legacyNullUnsafeEquals constructor param through StatementExecutor.planDialect into EngineStyleH2/DB2 and replace the Kind gates at :534 and :555 with the flag gate — the selector spellings at :536-543/:561-563 already match the engine. (4) Add the router-layer arm to NullSemantics.equalNullArms AFTER the FILTER_POS arm, gated strictly on EngineTextBoundary.active() && !legacyNullUnsafeEquals(), firing when both operands are optional or either is an optional plan param.

**Leverage** — High: two real defects, one shared plumbing, and neither test can land alone without regressing the other or testLegacyFlagProjectionEmitsPlainEquals. Also removes one diff from testNullSafeEqualityForOptionalProperties.

**Shares code with** — Touches StatementExecutor.engineSql (:457) and lowering/NullSemantics — any bucket blaming `=` vs `is not distinct from` in plan text, or optional-parameter FreeMarker selectors, merges here. Must NOT change DuckDB execution SQL (EngineTextBoundary gate) or rows shift corpus-wide.

---

### 127. Cross-receiver concatenate union: multi-chain synthetic head

**2 tests** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1, REAL_DEFECT 1

Tests: `testConcatenateInQualifierWithComplexReturnType`, `testQualifierConcatenateTwoSimilarJoins`

**Mechanism** — `SyntheticHeads.liftConcatStreams` models a concatenate stream as a ONE-HEAD union: it requires every branch to share the same head property AND the same nav node, refusing at SyntheticHeads.java:712 when branches differ. Both tests concatenate two DIFFERENT receiver chains ($p.address vs $p.firm.address; $t.subAccount.oe vs $t.otherAccount.oe), so the lift returns null (test 1) or the arm never fires at all because the concatenate is wrapped in an outer toOne() and the receiver is [0..1] (test 2, gates at SyntheticHeads.java:441-453). With no synthetic union head, the generic rewriter descends into the concatenate arms and a class-typed join binding is either walled by rewriteHeadProp or silently turned into a dangling column by Substitution.assocBindingRead.

**Owning code** — core/src/main/java/com/legend/resolver/SyntheticHeads.java:441-453, 672-757, 171-191; core/src/main/java/com/legend/resolver/StoreResolver.java:2441, 2500, 2196; core/src/main/java/com/legend/resolver/Substitution.java:2104-2137

**Fix** — Generalise liftConcatStreams from one-head to MULTI-CHAIN: replace the single prop/headNode accumulator and the refusal at SyntheticHeads.java:712 with per-branch descriptors (navPath, pred); keep requiring one bottom lambda var and one shared leaf property, allow differing head properties and receiver chains; park a synth->List<navPath> map beside branchPreds. Widen the arm gates at 441-453: unwrap an OUTER toOne/first around the concatenate (reuse the loop at 685-690) and drop the not-to-one exclusion. In StoreResolver.registerAssociationJoins (2441, 2500) build ONE AssocJoin whose target is the UNION ALL of per-branch materialised chains and whose condition is the OR of per-branch root conditions, reusing applyToPipe (generalised so each branch supplies its own base pipe) and the OR-of-branches logic in Substitution.java:830-861. Extend Pipelines.widenConcatenateForKeys to NULL-pad missing key columns per branch. Add the class-typed guard in assocBindingRead first so misses are loud.

**Leverage** — Real feature with two tests plus several ToFix siblings, but L effort across three subsystems. The small loudness guard inside it is cheap and pays off independently.

**Shares code with** — Touches Substitution.java (assocBindingRead class-typed guard, mergedConcatExists) and StoreResolver association-join registration — other buckets blaming those files may share the guard, not the union feature.

---

### 128. filtered-nav head deepened by map inline has no dotted-key ExistsSub

**2 tests** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 2

Tests: `testProjectThroughAssociation`, `testProjectThroughAssociationAutoMap`

**Mechanism** — A qualified property whose body filters `$this.products` is depth-1 on the mapper's own param at canonicalization time, so SyntheticHeads' deep-head lift arm is deliberately suppressed and the mapper is walked with lifting disabled. Substitution's TypedMap arm then beta-inlines the mapper param with the map SOURCE, DEEPENING the filtered head to `$b.trades.products`. filteredNavLeafRead only matches a head whose source is the instance variable, and ExistsSubs are registered only for size-1 paths, so it returns null; the raw object-space TypedFilter matches no arm (the only TypedFilter arm needs a RelationType source) and hits the default wall. The auto-map spelling desugars to the identical TypedMap node — the failure detail is byte-identical.

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:2641-2657, 1815-1831, 1848-1850, 1893-1899; SyntheticHeads.java:353-366, 504-508; StoreResolver.java:1640-1738, 2121-2124

**Fix** — Give the deep (multi-hop) filtered head a scalar arm mirroring the depth-1 one. (a) Substitution.filteredNavLeafRead :2641-2657 — replace the "head is a property access directly on the instance var" match with `List<String> hp = pathOf(f.source(), target.userVar()); if (hp == null) return null;` and look up `target.existsSubs().get(String.join(".", hp))`; a 1-element path reproduces today's key exactly, so depth-1 behaviour is unchanged. (b) StoreResolver — factor the emptiness-family block at :1640-1738 into a helper `chainedExistsSub(cs, path, ops, context, assocs)` and call it from a new loop over consumed paths of size >= 2 that are the SOURCE of a filtered-nav value read, using InnerDemand.collect's PropertyAccess recognizer (:434-458) so demand and rewrite cannot drift. Keep the `existsSubs.containsKey(dotted) continue;` guard. (c) No extra work for the correlated predicate — filteredNavLeafRead already re-rewrites the inner body through the enclosing scope (:2712-2714).

**Leverage** — Two tests here plus named neighbours in tests/projection (testVariableReferenceInMapWithNestedFilter). Good value: it closes the structural gap the lift arm's own comment admits, rather than special-casing.

**Shares code with** — Re-keys resolver/Substitution.java's ExistsSub lookup and adds a StoreResolver registration loop — overlaps the qualifier-isolation segment-keying cluster; both change how existsSubs keys are formed, so land one keying scheme, not two.

---

### 129. Mapping metamodel projection absent (includes / classMappings / mainTableAlias / resolveStore)

**1 test** · effort **L** · confidence high · bucket 03 (?) · verdicts: MISSING_FEATURE 1

Tests: `testExtractDBsWithSubstituition`

**Mechanism** — The corpus function meta::relational::runtime::extractDBs navigates `$m.includes.included` and `$m.classMappings...mainTableAlias.database`, then folds store substitutions via resolveStore. legend-lite's prelude declares Mapping with only `name`, has no MappingInclude/SubstituteStore class, no mainTableAlias on RelationalMappingSpecification, no TableAlias.database, and no resolveStore native; MetamodelWalk.prop has no arm for the Mm handle at all. Typer therefore throws 'class ... has no property includes', re-wrapped by SpecCompiler with the enclosing function name. The underlying data already exists in the model (LegacyMappingDefinition carries includes and classMappings; MappingInclude carries the substitution pairs) — only the metamodel projection and its evaluation are missing.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:406 (MAPPING_METACLASS = name only), :382 (RELATIONAL_MAPPING_SPECIFICATION, TODO at :378 names mainTableAlias), :346 (TABLE_ALIAS_METACLASS), :1478-1493 (mapping natives, where resolveStore belongs); core/src/main/java/com/legend/exec/MetamodelWalk.java:1074-1160 (prop() has no Mm arm), :906-925 (mainTable precedence to reuse); core/src/main/java/com/legend/StatementExecutor.java:1397-1401 (native routing), :1857-1875 (walkFilter matches only PlanNode kinds); wall emitted at compiler/spec/Typer.java:2565-2566 and compiler/spec/SpecCompiler.java:70

**Fix** — Build the Mapping metamodel projection end to end: (a) declare in Pure.java — Mapping.includes: MappingInclude[*] and Mapping.classMappings: SetImplementation[*]; new native classes MappingInclude{owner,included,storeSubstitutions} and SubstituteStore{original,substitute}; RelationalMappingSpecification.mainTableAlias: TableAlias[1]; TableAlias.database: Database[0..1]; native function resolveStore(Mapping[1], Store[1]):Store[1]; (b) evaluate in MetamodelWalk — new MiH and TaH handles plus prop() arms for Mm.includes, MiH.included/storeSubstitutions, Mm.classMappings, Cm.mainTableAlias, TaH.database, with TaH.database returning the AS-WRITTEN database of the class mapping, and a static resolveStore folding MappingInclude's substitution pairs; (c) plumb in StatementExecutor — route the resolveStore native, extend walkFilter to test a handle's metaclass so instanceOf/filter works over Cm handles, make cast(@...) a passthrough over handles, and make ==/removeDuplicates over Db handles compare by database FQN. Do not intercept extractDBs in Runner/EngineTestExecutor.

**Leverage** — Poor leverage for this bucket alone: L effort across four files to turn on exactly one test, and the thing asserted is engine-internal metadata (which Database object survives store substitution) rather than query correctness or DB rows. It becomes worth doing only if the sweep shows a meaningful population of other 'Mapping has no property includes/classMappings' walls — the diagnosis names helperFunctions.pure setUpData/extractDBs overloads and executionPlanExecutionTest.pure:158 as candidates but did not count them. Absent that count, ledger it as MISSING FEATURE and keep the wall.

**Shares code with** — Touches four commonly-blamed files. builtin/Pure.java: MAPPING_METACLASS:406, RELATIONAL_MAPPING_SPECIFICATION:382, TABLE_ALIAS_METACLASS:346, mapping natives ~:1478-1493 — merge with any bucket adding metaclass properties or natives in those same blocks (watch for conflict with _classMappingByClass/classMappingById/rootClassMappingByClass). compiler/spec/Typer.java:2565-2566 is only the message emitter, NOT the fix — do not merge on that citation alone; other buckets citing Typer.java:2566 are almost certainly a different missing declaration. StatementExecutor.java:1857-1875 walkFilter (instanceOf/filter over metamodel handles) and cast-over-handles are generic gaps other metamodel-navigation buckets likely also hit; exec/MetamodelWalk.java prop():1074-1160 is the shared arm table.

---

### 130. Positional length-truncating zip has no relational shape (zipSide only accepts same-source scalar projections)

**1 test** · effort **L** · confidence high · bucket 08 (?) · verdicts: MISSING_FEATURE 1

Tests: `testSortByLambdaDeepOptional`

**Mechanism** — The failure is in the test's SECOND assert, not the query: `zip($result.values.address.name, zip($result.values.firstName, $result.values.lastName))->map(pair | ...)`. The harness inlines $result, so the resolver sees a relational zip->map; StoreResolver.resolveNode:308-319 (the only zip dispatch site in the resolver) routes it to CorrelatedSubselects.zipPairMap, the only zip implementation in the codebase. zipPairProject demands that both arguments are scalar projections of the SAME class chain and value-equal in source (zipSide, CorrelatedSubselects.java:549-583), rewriting the pair into a two-column TypedProject. The inner zip is a TypedNativeCall that zipSide does not recognise, so zipPairProject returns null and zipPairMap throws (CorrelatedSubselects.java:513-521). Critically, widening zipSide would be WRONG rather than merely incomplete: `$result.values.address.name` has 11 elements (one of 12 persons has no address) while the inner zip has 12 pairs, and pure's zip truncates to the shorter input, whereas a 2-/3-column same-row project over Person yields 12 rows with a NULL name. The two-column-project trick can only model zip when both sides are [1] reads off the SAME row; this is a genuinely positional, length-truncating host zip.

**Owning code** — core/src/main/java/com/legend/resolver/CorrelatedSubselects.java:513-521 (zipPairMap throw), :524-547 (zipPairProject same-source requirement), :549-583 (zipSide accept set); core/src/main/java/com/legend/resolver/StoreResolver.java:308-319 (sole zip dispatch); catalog-only entry at core/src/main/java/com/legend/builtin/Pure.java:2083

**Fix** — Give zip a shape that preserves pure's positional-with-truncation semantics instead of the same-row projection trick. Recommended relational route: generalise CorrelatedSubselects.zipPairProject into a ROW_NUMBER positional INNER join — resolve each zip argument independently into its own relation (single-column projection for a scalar chain; recursively into a 2-column relation for a nested zip), wrap each in `SELECT *, ROW_NUMBER() OVER () AS rn`, and INNER JOIN on rn. The INNER join reproduces truncate-to-shorter exactly and ROW_NUMBER over the already-ordered subquery preserves position; name the nested zip's columns so `$pair.second.first` resolves (flatten to `first`, `second$first`, `second$second` and teach the pair-field reader to walk dotted names). SqlAgg.Fn.ROW_NUMBER exists (core/src/main/java/com/legend/sql/SqlAgg.java:27) and the window machinery is in place (core/src/main/java/com/legend/lowering/Windows.java:41). Keep the existing same-source fast path and use the ROW_NUMBER form ONLY when the sources differ or a side is itself a zip, so currently-passing flat zip asserts keep their SQL. Keep the throw at CorrelatedSubselects.java:517 as the floor. Explicitly rejected: widening zipSide to accept the nested zip and emitting a 3-column same-row project — that silently returns 12 mis-paired rows. Also rejected: a host-side zip over materialised Result values, which contradicts the no-interpreter tenet stated in the RESULT class comment in core/src/main/java/com/legend/builtin/Pure.java.

**Leverage** — Poor. L effort for exactly ONE test, and the thing being fixed is an assertion-tail idiom (host list zip) rather than a query capability — the query and the sortBy under test already work. Worse, the recommended route perturbs the emitted SQL for every currently-passing zip assert unless the same-source fast path is carefully preserved, so it carries regression risk disproportionate to its yield. The current throw is a correct loud wall. Recommend ledgering unless another bucket also blames zipPairMap/zipSide, in which case the ROW_NUMBER generalisation amortises.

**Shares code with** — resolver/StoreResolver.java:308-319 is the single zip dispatch point and resolver/CorrelatedSubselects.java:513-583 the single implementation — any bucket whose failure text is 'zip over inputs that are not two scalar projections of the SAME class chain has no relational shape' is the SAME cluster and should be merged here, since one generalisation serves all of them. Also touches sql/SqlAgg.java:27 (ROW_NUMBER) and lowering/Windows.java:41; buckets doing window-function work may share that surface. builtin/Pure.java:2083 is catalog-only (zip has no host implementation anywhere).

---

### 131. Two-hop `exists` head with a TO-ONE first hop registers no exists material, degrading to a DuckDB list lambda

**1 test** · effort **L** · confidence medium · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testAssociationWithProjectionHandlingDups`

**Mechanism** — Correlated-EXISTS material is registered per-path in StoreResolver.registerExistsSubs, whose loop hard-skips any path longer than one hop unless it is a filterTwoHop (`boolean filterTwoHop = path.size()==2 && filterPaths.contains(path); if ((path.size()!=1 && !filterTwoHop) || existsSubs.containsKey(head)) continue;`, StoreResolver.java:2120-2123). The separate dotted two-hop registration (StoreResolver.java:1595-1642) only fires its ChainedExists.explodedTwoHop arm when `midToMany` is true, i.e. the FIRST hop is to-many (StoreResolver.java:1615-1621). For `$p.firm.employees` the first hop Person.firm is [1], so midToMany is false, control falls to the chain-based arm, and `if (chain == null) continue;` (StoreResolver.java:1633) silently bails. With no ExistsSub under the dotted key, `Substitution.rewriteCallArms` finds nothing at either lookup (Substitution.java:815-819 for one-hop, :866-872 for dotted) and leaves the exists as an ordinary Pure collection call. Generic lowering hands it to DuckDb.listExists -> listPredicate -> `coalesce(list_bool_or(list_transform(coll, e -> pred)), false)` (DuckDb.java:190-202, lambda rendering at :158-162), and the predicate body is a correlated scalar subquery — 'Binder Error: subqueries in lambda expressions are not supported'. The one-hop siblings (testAssociationHandlingDups, testAssociationThreeLevelDeep) pass, isolating hop count + mid-hop multiplicity as the discriminator.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:1595-1642 (the dotted-path loop; specifically the midToMany gate at :1615 and the silent `continue` at :1633) and StoreResolver.java:2120-2123 (the path-length skip); lookup side core/src/main/java/com/legend/resolver/Substitution.java:866; guard site core/src/main/java/com/legend/sql/dialect/DuckDb.java:190-202

**Fix** — Two parts. PRIMARY: in the dotted-path loop at StoreResolver.java:1595-1642, stop letting `midToMany == false` fall through to the `continue` at :1633 — a to-one mid hop is exactly the engine's shape where the exists subselect attaches to the mid hop's join target. When `chain = assocs.get(path[0..n-1])` is null for a to-one mid hop, MATERIALISE it (register the `firm` association join first, then build the ExistsSub on `employees` against that target) and register it under the dotted key 'firm.employees' so Substitution.java:866 resolves it. Target emission is the golden at functions/tests/testExists.pure:376 (left outer join of a `select distinct FIRMID` semi-join, then `where ... FIRMID is not null`). GUARD (do regardless): make Lowerer/DuckDb.listExists refuse to emit a list lambda whose body contains a SqlExpr.ScalarSubquery/SqlExpr.Exists — throw NotImplementedException naming the unrewritten exists head, so every future instance of this miss is attributable instead of surfacing as an unprovenanced DuckDB binder error.

**Leverage** — One test here plus a named likely sibling (testNestedExistsWithExistsInAbstractProperty, same firm->employees head, different downstream symptom: "exists/forAll predicate references column 'firm_employees', unresolvable even after isolation"). Effort is L and the confidence is medium because the fix is inside the exists-registration machinery, whose blast radius is join emission for every to-one-mid-hop exists in the corpus (golden-SQL drift risk across the exists family). The GUARD half is XS, independently valuable, and converts a whole class of silent misses into attributable errors — do the guard even if the primary is deferred.

**Shares code with** — Touches resolver/StoreResolver.java (exists-sub registration, both the :1595 dotted loop and the :2120 path-length skip), resolver/Substitution.java:815-872 (the exists lookup sites), and sql/dialect/DuckDb.java:190-202 (listExists/listPredicate). Any bucket whose failure is either 'subqueries in lambda expressions are not supported' from list_bool_or/list_transform, OR "exists/forAll predicate references column '<a>_<b>', unresolvable even after isolation", is very likely the same missing multi-hop exists material and merges here — the two symptoms are the same defect seen from opposite ends.

---

### 132. Filtered navigation in value position with a chained hop is encoded as a correlated scalar subquery instead of a join

**1 test** · effort **L** · confidence low · bucket 05 (?) · verdicts: REAL_DEFECT 1

Tests: `testIsolationOfFiltersWithoutAlias`

**Mechanism** — Under MappingWithLiteral, Person.lastName maps to the bare literal 'Smith' with no table alias (advancedRelationalSetUp.pure:401-403), so `employeeByLastName('Smith')` = `$this.employees->filter(e|$e.lastName==$lastName)->toOne()` (simpleTestModel.pure:56) substitutes to a filtered navigation whose predicate is the alias-free constant `'Smith' = 'Smith'`. legend-engine isolates such a filter INTO the join ON clause and lets the to-many join fan out (testFilters.pure:99 golden: `left outer join personTable as "persontable_0" on ("root".ID = "persontable_0".FIRMID and 'Smith' = 'Smith')`, 7 rows). legend-lite instead took the 'single-column RELATION consumed in SCALAR position' arm of Lowerer.scalar: `toMany` computes false because employeeByLastName returns Person[0..1] and isToOne() holds (Lowerer.java:2779), so it yields `new SqlExpr.ScalarSubquery(relation(rel))` (Lowerer.java:2786-2787) and DuckDB raises 'More than one row returned by a subquery used as an expression'. The policy encoded in that branch's own comment (Lowerer.java:2748-2751) — enforce Pure toOne() semantics in SQL — is wrong for a MAPPED relational navigation: the engine erases toOne() in SQL and joins. The chain never took the normal synthetic-head lift (SyntheticHeads.java:37-46, `head#fN` with the predicate parked on the join target). The discriminator against the PASSING sibling testIsolationOfFiltersWithoutAliasWithChainedJoins is that here the filtered nav is followed by a SECOND association hop (.address.name) and the same head is consumed twice with different leaves.

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:2779-2787 (the `!toMany -> ScalarSubquery` branch; comment at :2748-2751); the correct route is the filtered-nav lift in core/src/main/java/com/legend/resolver/SyntheticHeads.java:37-46 and its caller in StoreResolver

**Fix** — Fix it in the LIFT, not the lowering default. Make a filtered head whose predicate contains no reference to the target row's columns (here the closed constant `'Smith' = 'Smith'` after literal-mapping substitution) still eligible for the `#fN` lift, and still mint a DISTINCT identity per distinct predicate (result3 proves the engine mints two person aliases, one per filter constant): a closed/constant predicate must be PARKED in the join ON, not read as 'no correlation, therefore subquery'. Then the chained `address` hop joins off that head's target. Only as a secondary hardening, narrow Lowerer.java:2779-2787's ScalarSubquery branch to genuinely non-navigational relation values, or make it resolve through the join whenever the resolver already has a join identity for the frame — do not simply weaken it, the branch is load-bearing for genuine [0..1] nav encodings. Never set scalar_subquery_error_on_multiple_rows=false (returns a random row, silently wrong), and do not de-duplicate in the harness to make the 7-row assert line up.

**Leverage** — Low leverage as scoped: one test, L effort, LOW confidence — the diagnosis itself says a single SQL dump would move it from low to high, so the cheapest next action is the falsifier (dump the SQL under MappingWithLiteral and again under simpleRelationalMapping), not the fix. Value is real if confirmed (wrong-shape SQL that would otherwise return wrong/failing rows on any filtered-nav-then-association-hop), and it may also carry testIsolatioWhereNoConstaintsAndInnerJoin (4 rows instead of 7 — same missing fan-out), but that link is unproven. Do the probe before budgeting the L.

**Shares code with** — Touches lowering/Lowerer.java:2779-2787 — THE SAME BRANCH the 'TDS .values flat-cells read' cluster in this bucket touches, from the opposite direction (that one wants a [1]-stamped relation forced to toMany; this one wants [0..1] mapped navigations routed to a join). Any cross-bucket cluster proposing to change that branch must be reconciled against both: agree the branch's contract is 'correlated-scalar ONLY for genuine [0..1] nav encodings' and fix everything else upstream. Also touches resolver/SyntheticHeads.java (the #fN filtered-head lift) — merge any bucket blaming missing join-ON filter isolation or missing per-predicate head identity.

---

### 133. DB2 trimColumnName post-processor (alias-limit rename pass)

**1 test** · effort **L** · confidence medium · bucket 01 (?) · verdicts: MISSING_FEATURE 1

Tests: `testDb2ColumnRename`

**Mechanism** — Reported wall is the shared SQLQuery/toSQL absence, but behind it the DB2 alias-limit post-processor `meta::relational::postProcessor::reAliasColumnName::trimColumnName` is entirely absent from legend-lite (grep for aliasLimit/trimColumn/reAliasColumnName over core/src/main returns nothing). The engine rule is a dialect fact: names >= aliasLimit (DB2 = 128) are truncated to limit-10 = 118 and grouped by truncated prefix into `<prefix>_<index>`.

**Owning code** — core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:156 and :218-230 (the platform-owned idiom to join); core/src/main/java/com/legend/builtin/Pure.java (new native signature); core/src/main/java/com/legend/lowering/SqlPostProcessors.java:181-273 (the apply/applySelect/source/expr walk to mirror); core/src/main/java/com/legend/sql/dialect/EngineStyleDB2.java:19 and core/src/main/java/com/legend/StatementExecutor.java:371 (DB2 renderer selection already exists)

**Fix** — After the sqlstring-surface cluster lands: declare `trimColumnName` as a platform-owned native (PlatformTypes + Pure.java signature, both the Runtime and ConnectionStore overloads) and add one new IR pass, core/src/main/java/com/legend/lowering/TrimColumnNames.java, shaped like SqlPostProcessors' walk but rewriting projection/alias NAMES: collect every output name whose length >= the dialect's alias limit (put aliasLimit on SqlDialect/DialectCapability, never hardcoded per-dialect in the pass), strip quotes, truncate to limit-10, group by prefix in first-seen order, assign `<prefix>_<index>`, rewrite the alias and every reference. Wire the native to run the pass over the deferred toSQL handle and wrap the result as a Result value.

**Leverage** — Low — do the falsifier before spending the effort. Nothing else in the corpus depends on trimColumnName, and if legend-lite's UNION-ALL/"unionBase" text for unionMappingWithLongPropertyMapping does not already match the engine's PRE-trim string, adding this pass just produces a different wrong string for L effort and zero scoreboard movement. Probe toSQLString on that mapping in DB2 first; if the skeleton or the DB2 concat spelling diverges, ledger the row instead of building the pass.

**Shares code with** — Depends on the SQLQuery/toSQL change in Pure.java:426 + PlatformTypes.java:218-230 (see the sqlstring cluster). The pass itself is self-contained in lowering/, but adding aliasLimit to SqlDialect/DialectCapability touches shared dialect config that other DB2/dialect buckets may also blame.

---

### 134. H2 temp-table DDL statement generation (ddlSqlQueryToString + createDbConfig handle)

**1 test** · effort **L** · confidence high · bucket 01 (?) · verdicts: MISSING_FEATURE 1

Tests: `testTempTableSqlStatementsForH2`

**Mechanism** — The body demand-pulls metamodel/metamodel.pure to construct ^CreateTableSQL/^LoadTableSQL/^DropTableSQL, and every one of those corpus classes `extends SQLQuery` — the legend-pure class legend-lite does not register — so TypeClassifier throws `Unknown type: 'SQLQuery'`. Behind it two real gaps: `createDbConfig` is declared platform-owned so the corpus body is suppressed, yet no execution arm exists anywhere (grep for DbConfig outside PlatformTypes/Pure/InferenceKernel is empty), and `ddlSqlQueryToString` is not in isDdlStatementFn while Ddl.java has no CREATE LOCAL TEMPORARY TABLE or CSVREAD form.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:426 (SQLQuery absent / SELECT_SQL_QUERY parenting), :351 (datatype classes); core/src/main/java/com/legend/compiler/element/type/PlatformTypes.java:144-149 (isDdlStatementFn) and :215-216/:228 (createDbConfig owned, no arm); core/src/main/java/com/legend/exec/Ddl.java:292-303 (drop/create parity only); core/src/main/java/com/legend/StatementExecutor.java:1427-1440 (constructOp, the instance->host bridge shape to copy); core/src/main/java/com/legend/compiler/NameResolver.java:532-593 (primitive-first tier)

**Fix** — Consumes two shared sub-changes (native SQLQuery + re-parent SELECT_SQL_QUERY; datatype classes + NameResolver primitive-first tier), then adds its own tail: own the DDL text surface the way toDDL already is — add `sqlQueryToString::ddlSqlQueryToString` to PlatformTypes.isDdlStatementFn/isPlatformOwnedFunction, declare its native signature, add a K-arm that reads the constructed ^CreateTableSQL/^LoadTableSQL/^DropTableSQL instance (same bridge shape as StatementExecutor.constructOp) and renders through exec/Ddl.java, which gains two forms: `CREATE LOCAL TEMPORARY TABLE <t>(<col TYPE,...>);` and `INSERT INTO <t> SELECT * FROM CSVREAD('${var}');`. `createDbConfig` must additionally return a real handle carrying dbType/quoteIdentifiers/timeZone.

**Leverage** — Medium and back-loaded: the two prerequisites are already being paid for by other clusters, so the marginal cost here is the ddlSqlQueryToString arm + two Ddl forms + a real createDbConfig handle. Payoff extends past this row — the diagnosis states the rest of sqlQueryToString/testSuite and DDL/testDDL.pure all die on the same `Unknown type: 'SQLQuery'`. Verify with the falsifier (register SQLQuery alone, expect the wall to MOVE to createDbConfig) before committing to the tail.

**Shares code with** — Shares Pure.java:426 (SQLQuery) with the sqlstring cluster and Pure.java:351 + NameResolver.java:532-593 with the temp-table datatype cluster — land those once. Its own tail touches exec/Ddl.java:292-303 and PlatformTypes.java:144-149, which any other DDL-family bucket would also blame.

---

### 135. Class-typed projection column in the lineage lowering (join-forcing, no-output column)

**1 test** · effort **L** · confidence medium · bucket 01 (?) · verdicts: MISSING_FEATURE 1

Tests: `testNonDataTypeProperty`

**Mechanism** — legend-lite answers scanColumns by LOWERING the query to SQL and scanning the lowered plan, rather than by engine's property-tree/property-mapping walk. The query projects `p|$p.address`, a class-typed property bound to a join-only property mapping (`address : [dbInc]@Address_Person`, no columns), so Substitution.rewriteHeadProp throws `class-typed property '$p.address' used as a whole value is graph output (Phase H4)` and LineageForm turns it into `scanColumns query: ...`. The throw is CORRECT for lowering — there is no scalar SQL expression for that column — but the lowering has no way to express "this projection column forces a join and produces no output", which is the only shape yielding the expected 4-element answer.

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:1355-1372 (the unconditional throw at ~:1365/:1372); core/src/main/java/com/legend/harness/LineageForm.java:96-117 (lower-then-scan strategy and the Unsupported wrapper); core/src/main/java/com/legend/lineage/ScanColumns.java:19-52 (the plan-based lineage walk); com.legend.sql.SqlSelect + lowering/Lowerer (need a no-output column slot / join-without-projection arm)

**Fix** — Context-scope the Substitution throw: when the class-typed head is the TERMINAL value of a projection column, rewrite it to a join-forcing marker instead of throwing, so the join enters the plan's FROM/ON and ScanColumns tags personTable.ADDRESSID / addressTable.ID as <JoinTreeNode> while the column contributes no select expression. That needs a matching no-output column slot in com.legend.sql.SqlSelect and a Lowerer arm emitting the join with no projection entry. Keep the throw for every other context. The alternative — and the one matching engine's design — is a second ScanColumns entry point that walks the class's property mappings from ModelContext directly (mirroring scanColumns.pure:30-53), which removes the lowerability dependency but duplicates mapping traversal ScanRelations already partly does.

**Leverage** — Single test, and the honest read is that it is NOT high-value on its own: this is a lineage-surface completeness gap, not a wrong-rows correctness bug, and the alternative design (ScanColumns over property mappings) is arguably the better long-term answer at similar cost. The reason not to skip it is the architectural signal — legend-lite's scanColumns is only as complete as its lowering, so any join-only property in any lineage query hits this. Explicitly do NOT relax the Substitution guard globally (genuine graph-output queries would lower to silently wrong SQL) and do NOT special-case class-typed project columns in LineageForm.

**Shares code with** — Touches core/src/main/java/com/legend/resolver/Substitution.java:1352-1372 — a single throw site reachable from ANY query reading a class-valued property as a whole value, so other buckets citing `class-typed property ... is graph output (Phase H4)` share this code. Merge only the LINEAGE/projection-context instances: for genuine graph-fetch tests the same wall is correct and must stay. Also touches lowering/Lowerer + com.legend.sql.SqlSelect.

---

### 136. execute() has no ExecutionContext overload, and importDataFlow is unimplemented

**1 test** · effort **L** · confidence high · bucket 09 (?) · verdicts: MISSING_FEATURE 1

Tests: `testPksWithImportDataFlow`

**Mechanism** — Two layers. (a) legend-lite registers exactly two 5-arg execute shapes, both with (extensions:Any[*], debug:Any[1]) in slots 4-5 (Pure.java:1545, 1552). Real Pure has a second 5-arg shape with ExecutionContext in slot 4 (router_entry.pure:25). The corpus call execute(|…, unionMapping, testRuntime(), $execCtx, relationalExtensions()) therefore binds Extension[*] to debug:Any[1]; because the call carries a deferred lambda, resolution runs through Typer.bindDeferredAndBuild whose kernel.unifyMult throws 'expected at most one value, got many ([*])' (InferenceKernel.java:601) and Typer.java:1549 rethrows it RAW — which is why the message carries neither the "in call to" nor the "in function" prefix. (b) importDataFlow itself is unimplemented: legend-lite has no typed exeCtx anywhere; the one supported option rides a ThreadLocal whose own comment (DriverPkOption.java:9-14) says to replace it with the typed exeCtx argument.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:1544-1552 (execute overloads; :238 already registers ExecutionContext); core/src/main/java/com/legend/validation/DriverPkOption.java:9-14 (the ThreadLocal to replace); the union lowering that names per-arm PK aliases

**Fix** — Two steps, in order. (1) XS: register the exeCtx execute overloads for BOTH fqns (meta::pure::mapping and meta::pure::router), typing slot 4 as ExecutionContext[1] rather than Any[1] so it stays unambiguous against the existing (Any[*], Any[1]) debug form in both directions; StatementExecutor.buildFrame indexes only args 0-2 so no arity change is needed. (2) L: thread the typed exeCtx into ExecEnv (retiring the DriverPkOption ThreadLocal) and, in the union lowering, rename per-arm PK aliases from pk_<pkIndex>_<setIndex> to <ColumnName>_<setIndex> and project them (plus FKs when importDataFlowAddFks) into the result TDS, per pureToSQLQuery_union.pure:662-680. If only (1) lands, (2) MUST throw a NotImplementedException naming importDataFlow when the exeCtx carries it.

**Leverage** — Poor per-unit: one test in this bucket for L-sized work. Step (1) alone is XS and has real spillover — it unblocks the typer for ANY corpus test passing an ExecutionContext to execute — but it is dangerous alone: silently ignoring importDataFlow yields a syntactically valid query whose SQL diverges from the engine's, i.e. green-looking wrong rows. That makes the explicit wall non-optional, not a nicety. Recommend landing (1)+the wall as a cheap correctness/diagnosability improvement and ledgering (2) until the union PK-naming work is scheduled on its own merits.

**Shares code with** — Step (1) is a builtin/Pure.java execute-overload change that affects every execute call site corpus-wide — any bucket with a test failing on 'expected at most one value, got many ([*])' at an execute call is the same fix. Step (2) lands in the union lowering (lowering/Lowerer.java neighbourhood) alongside the bi-temporal union golden-SQL work that the Date-vs-String cluster will expose next; sequence them together so union PK aliasing is designed once.

---

### 137. executeInDb returns an opaque handle, and Typer misclassifies TDS-returning helpers as normalize-required

**1 test** · effort **L** · confidence high · bucket 10 (harness SHAPE) · verdicts: MISSING_FEATURE 1

Tests: `testExecuteInDbToTDS`

**Mechanism** — Layered. Typer.requiresNormalization treats a function as NormalizeRequired if it is stereotyped OR any parameter is schema-erased OR its RETURN type is schema-erased (Typer.java:1236). meta::relational::metamodel::execute::resultSetToTDS carries no stereotype and takes a ResultSet, but returns TabularDataSet, so the return-only clause fires; inlineNormalized then calls SourceSubst.inlineLets, which fails because the body has a non-let intermediate statement (`$tds.rows->map(r|mutateAdd($r,'parent',$tds));`), and throws at Typer.java:1281-1288. legend-engine marks normalization EXPLICITLY with <<functionType.NormalizeRequiredFunction>> (tdsExtension.pure:22,96,...) and never infers it from the return type — resultSetToTDS is unstereotyped there (relationalExtension.pure:73). Underneath: StatementExecutor.executeInDb returns `new ExecutionResult.Scalar(null, call.info().type())` (StatementExecutor.java:3273-3301) — an opaque handle with no columnNames/rows that resultSetToTDS's body cannot read — and toCSV has no implementation anywhere in core/src/main.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1228-1237 (requiresNormalization) and :1281-1288 (inlineNormalized throw); core/src/main/java/com/legend/StatementExecutor.java:3273-3301 (executeInDb Scalar(null)); core/src/main/java/com/legend/builtin/Pure.java (ResultSet/Row metaclasses, toCSV native)

**Fix** — Three coordinated changes in order. (1) Make the return-only clause conditional on inlinability: keep the stereotype and erased-PARAMETER clauses unconditional and loud, but when the classification came from the return type alone and SourceSubst.inlineLets returns null, fall back to emitCall(chosen, args, out) instead of throwing. (2) Have executeInDb materialize the last statement's JDBC ResultSet into a real value — an ExecutionResult carrying columnNames and rows typed as meta::relational::metamodel::execute::ResultSet — and register that metaclass in builtin/Pure.java (columnNames: String[*], rows: Row[*], Row.values: Any[*]) so resultSetToTDS's body types and evaluates. (3) Implement meta::pure::tds::toCSV(TabularDataSet[1]):String[1] (header line plus one line per row). Do NOT special-case executeInDbToTDS in the harness to return a fabricated TDS — the ResultSet value is platform surface that StatementExecutor owns.

**Leverage** — One test for L effort, but change (1) is a genuine semantic correction with reach well beyond it: legend-lite is inferring a contract the engine states explicitly, and that misinference will misfire on any unstereotyped TDS-returning helper with a non-let body statement. Change (2) removes a documented placeholder that the code comment itself says will 'surface loudly here when that day comes' — that day is now. Worth doing for the platform correctness even though the test count moves by one. Watch the stated regression vector: (1) widens what types as an ordinary call, so any test that today depends on a non-stereotyped TDS-returning helper being INLINED could move from 'inlined and typed' to 'called and untyped'.

**Shares code with** — High cross-bucket value — this touches all three of the usual suspects at once. Typer.java:1228-1237/1281 (requiresNormalization + inlineNormalized) is the normalization gate for EVERY TDS helper, so any bucket whose failure reads 'NormalizeRequired function ... has non-let intermediate statements — cannot inline' is the same mechanism and must merge here. StatementExecutor.java:3273 executeInDb is the read boundary shared with the H2Test routing cluster below (that one wants executeInDb READS moved off the H2 replay target). builtin/Pure.java gains new metaclasses, shared with the enumeration-mapping and resolveStore clusters.

---

### 138. meta::pure::functions::meta::enumValues is unported, and the enum-driven per-driver loop is unrecognized

**1 test** · effort **L** · confidence high · bucket 04 (?) · verdicts: MISSING_FEATURE 1

Tests: `testSortQuotes`

**Mechanism** — enumValues does not exist anywhere in legend-lite (zero grep hits in core/src, absent from the native catalog). The test's first statement is DatabaseType->enumValues()->filter(...)->forAll(type | ...), which is not a shape the harness intercepts — the only multi-driver loop form recognized is $pairs->map(p|...)->distinct() over pair() literals — so it compiles and Typer.checkGeneric throws 'unknown function' with zero candidates.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1447-1451 (the throw) with the fold template at :1056-1077 (extractEnumValueFold) and the dispatch site at :418; core/src/main/java/com/legend/builtin/Pure.java (no ENUM_VALUES signature); core/src/main/java/com/legend/harness/EngineTestExecutor.java:2249-2281 (driverPairLoop) and :2110-2174 (runPerDriverLoop, already gated on H2/DB2/Composite)

**Fix** — Three pieces. (1) Port enumValues: add the native signature to Pure.java and a compile-time enumValuesFold in Typer modelled line-for-line on extractEnumValueFold — require the argument to be Enumeration<E>, resolve ctx.findEnum(fqn) and emit a TypedCollection of TypedEnumValue in declaration order, returning null (loud generic path) for any non-Enumeration argument. Then ->filter(e|$e->in([...])) folds over a literal collection with nothing further needed. (2) Add a recognizer beside driverPairLoop for a statement-position forAll over a collection of enum values (and the filter(enumValues(E), pred) source) and reuse runPerDriverLoop by generalizing its per-iteration substitution to a single-value bind; keep its unrecognized-statement rejection strict. (3) The goldens spell addressTable_d#3_1_d#3_m2 — raw-alias ledger.

**Leverage** — This test will move from ERROR to a golden-text FAIL, not to PASS — it is gated on the raw-alias ledger. Piece (1) is nonetheless near-zero-risk, additive, and independently valuable for any corpus test reflecting over an Enumeration's values, so it is worth landing on its own. Piece (2) is harness ORCHESTRATION the harness legitimately owns (asserts inside a loop lambda), not compensation. Do not implement enumValues by hardcoding the DatabaseType list in the harness.

**Shares code with** — compiler/spec/Typer.java:418 + :1056-1077 (the enum-fold dispatch) and builtin/Pure.java's meta natives. Any bucket walling on 'unknown function enumValues' merges here. Typer.java is also owned by the mangled-function-reference cluster below — different methods, same file.

---

### 139. execute() result is re-planned, not materialized — join cancellation shrinks the extent

**1 test** · effort **L** · confidence high · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testMultipleJoinsInPropertyMappingWithDatesInClass`

**Mechanism** — In real Pure, `Result.values` holds already-built objects and `$result.values.tableProperty` is an in-memory collect over them. legend-lite instead splices the frame's chain back into a fresh query (`spliceValuesRead` returns `f.chain()`), so the statement is re-planned as a property access with only `tableProperty` demanded. `Pipelines.walkJoinSlot` then CANCELS every join slot no column reads, stripping the row-multiplying (un-date-filtered) TypeTableB join, and the projection collapses from 6 rows to 3. Cardinality of an already-observed object extent is being recomputed from a narrower demand set. Proof: the preceding `assertSize($result.values, 6)` PASSES because the class-rooted chain is GRAPH-shaped and demands all mapped properties; the very next `.tableProperty` read returns 3.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:2699-2704 (spliceValuesRead), :2600-2612 (spliceHook), :2053-2055 + :2278 (ExecFrame.result); core/src/main/java/com/legend/exec/Pipelines.java:405-407 (join cancellation)

**Fix** — (a) Preferred: answer downstream reads of `$result.values` from the MATERIALIZED eager result instead of re-planning. `ExecFrame` already carries `result()`; extend `spliceValuesRead` so a property/collect read over a frame whose eager result is an `ExecutionResult.Graph` is answered HOST-SIDE from that JSON (one value per object, object order, empties dropped per Pure), via a small `GraphValuesRead` beside `com.legend.exec.HostEval`, with the `.values`-read recognizer in `spliceHook` routing to it. (b) Cheaper scoped alternative: flag the spliced `TypedFrom` as 'extent cardinality pinned' and have `Pipelines.materialize` seed `demanded` with the root class mapping's FULL mapped-property slot set, so cancellation at :405-407 cannot change the row count. Do NOT disable join cancellation generally — pruning is correct for genuine `project()` queries and is what keeps golden SQL matching elsewhere.

**Leverage** — Single test here, but option (a) retires the entire 'execute() is fused, not materialized' family (five mechanisms in CORPUS_STUDY_2026_08 §2) and subsumes the harness-side null-drop. Genuine wrong-cardinality defect — highest correctness value in this bucket.

**Shares code with** — Owns `StatementExecutor.java` splice path and `exec/Pipelines.java` demand pruning — any bucket whose tests read `$result.values.<prop>` and get too few/too many rows is this same change; merge rather than fix locally.

---

### 140. Three-DB spine: unmessaged cross-database column lookups plus root/var-naming defects

**1 test** · effort **L** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `tdsTwoJoinThreeDB`

**Mechanism** — `No value present` is a supplier-less Optional.orElseThrow in PlanText's column-type lookups (:298/:301, :313/:316, :328/:332, :555/:559), all of which resolve a physical [table,column] against the SINGLE dbFqn = impl[2] of the BFS-chosen root class. This query spans dbInc/database2/database3, so a projected column's table is absent from dbFqn. resolvePhysical, unlike resolveStarColumn (:675-716), never checks the table belongs to dbFqn. NoSuchElementException is not in PlanAsserts' catch list (PlanAsserts.java:188-190) so it escapes as raw ERROR. Behind it: rootGetAllClass is a BFS (StatementExecutor.java:2028-2038) returning the wrong branch's root, and allocation vars are named innermost-first (StatementExecutor.java:715-716), inverted vs the golden.

**Owning code** — core/src/main/java/com/legend/plan/PlanText.java:298,301,313,316,328,332,555,559; core/src/main/java/com/legend/StatementExecutor.java:715-716,2028-2038

**Fix** — (1) Give every findTableDefinition/column orElseThrow in PlanText a NotImplementedException supplier as :202-207 and :582-590 already do, converting the raw ERROR into a named wall. (2) Adopt the placeholder-typing cluster's fix: a projection sourced from a spliced TDS var must not be looked up physically — spell INT for resultColumns, keep the source type for the TDS tuple line. (3) Make rootGetAllClass take the LEFT-SPINE root (leftmost TypedGetAll of the terminal's left spine) rather than a BFS, so the node's dbFqn is the leading store. (4) Invert allocation var naming: outermost spine allocation is `tdsVar`, inner ones `tdsVar_0`, `tdsVar_1`.

**Leverage** — Moderate. Item (1) alone is cheap and turns a raw ERROR into an honest wall — worth doing regardless. The pass needs the placeholder cluster plus two riskier changes; rootGetAllClass is shared by ~60 passing plan tests.

**Shares code with** — Depends on the same PlanText placeholder arm as the cross-store cluster, and changes rootGetAllClass in StatementExecutor.java, which the single-node and sequence plan paths also call — coordinate with any bucket touching StatementExecutor plan routing.

---

### 141. Operation/inheritance set implementations in plan-text identity

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `inheritance`

**Mechanism** — RoadVehicle is mapped by an Operation (inheritance) set. ScanRelations.rootImplOrNull scans only allClassMappings(m) (Relational sets) and then only ClassMapping.Pure sets for the ~src chase; ClassMapping.Inheritance — which legend-lite does model (ClassMapping.java:352-366) and consults elsewhere (ScanRelations.java:1541-1553) — is never consulted, so rootImplOrNull returns null and rootImpl throws (ScanRelations.java:588-596). SQL lowering already succeeded (engineSql at StatementExecutor.java:629 precedes PlanText.single at :633), so the failure is purely the plan-text identity layer. Behind that wall: PlanText.typeBlock can print only ONE impl pair (:120-126) while the engine prints a comma-joined list, and resultColumns cannot type through a UNION sub-select.

**Owning code** — core/src/main/java/com/legend/relational/ScanRelations.java:588-596,1541-1553; core/src/main/java/com/legend/plan/PlanText.java:57-59,113-126,718

**Fix** — Three coordinated changes. (1) ScanRelations: add a plural `rootImpls(ctx, mappingFqn, classFqn, chainMappings) -> List<String[]>`; when the class's set is a ClassMapping.Inheritance, resolve members the engine's way — walk specializations to LEAF classes having a root class mapping in the mapping (getMappedLeafTypes recursion), in specialization-declaration order, returning one [definingMappingName, setId, dbFqn, mainTable] per leaf. Keep singular rootImpl as rootImpls(...).get(0) so no caller breaks. (2) PlanText.typeBlock (:113-126): accept List<String[]> impls plus parallel leaf class FQNs and print `Class[impls=` + comma-joined entries + `]` then `as <rootClassFqn>`, per executionPlan_print.pure:119. (3) PlanText.resolvePhysical (:718): add an arm that resolves a column positionally in the FIRST branch of a union inner, or the wall just moves.

**Leverage** — One test, but it opens the whole Operation/union-set plan-text family and the union column-typing path. Order is specialization order, not declaration or alphabetical — Bicycle-before-Car happens to be both here, a trap.

**Shares code with** — Changes ScanRelations.rootImpl, called from StatementExecutor.java:731,754,889,939 and PlanText.java:57, and adds a union arm to PlanText.resolvePhysical — the same method the cross-store placeholder cluster edits. Route only typeBlock through the plural form to protect ~200 passing plan tests.

---

### 142. Store-rooted (tableToTDS) plans with no class root

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `relationalTDSTypeForColumnsAndQuoting`

**Mechanism** — The query root is tableToTDS(tableReference(db,'default','tableWithQuotedColumns')) — a direct STORE relation with EmptyMapping and no class anywhere. StatementExecutor.planToString reaches the single-node arm and calls rootGetAllClass (StatementExecutor.java:2028-2038), which BFSes for a TypedGetAll, finds none, returns null, and :611-616 throws "planToString: no getAll root". The class root is load-bearing only incidentally: PlanText.single calls ScanRelations.rootImpl purely to obtain impl[2], the database FQN used to type every column. For a tableToTDS root that database is named directly by the tableReference argument, and the `type = TDS[...]` block needs no class identity (typeBlock's RelationType branch never uses impl[0]/impl[1]).

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:611-616,2028-2038; core/src/main/java/com/legend/plan/PlanText.java:52-83; core/src/main/java/com/legend/relational/ScanRelations.java:506-528

**Fix** — In StatementExecutor.planToString, before the rootClass == null wall (:611), add a STORE-ROOT arm: walk the typed body for the tableReference call (a TypedNativeCall with callee CoreFn.TABLE_REFERENCE and arg0 a TypedPackageableRef naming the Database) and take that FQN as the plan's dbFqn. Then add PlanText.singleTds(ctx, dbFqn, plan, sql, body, connName) — the body of single() (:52-83) minus the ScanRelations.rootImpl call, passing dbFqn where impl[2] was used and emitting typeBlock's TDS branch directly (no impls line, no resultSizeRange). Mirror ScanRelations.collectTableToTds (:506-528) for the extraction so the two readers cannot drift. Do not thread a fake class through rootImpl — the honest shape is 'this plan has a store, not a set implementation'.

**Leverage** — Low blast radius — a new arm reached only where today's code always throws — and it likely unblocks testViewToTDS and the other tableToTDS-rooted goldens. The golden also exercises quoted-identifier restrict, so a second wall may follow.

**Shares code with** — Adds an arm to StatementExecutor.planToString beside the mapping-less-route cluster's wall at :576-581, and a new entry point in plan/PlanText.java parallel to single(). Both files are blamed by other buckets.

---

### 143. planToString requires a Class.all() root — table accessors wall

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testFilterLimitInSequenceForTableAccessor`

**Mechanism** — StatementExecutor.planToString demands a getAll root: rootGetAllClass only matches TypedGetAll, and this query's root is a TypedTableReference (#>{dbInc.personTable}#), so rootClass is null and the 'planToString: no getAll root' wall fires before any SQL is generated. Operator order (filter→limit vs limit→filter) is the only difference from the sibling and only affects where top 1 and the WHERE land inside the subselect. A second, separable item: both expected SQLs carry the engine's optional-operand guard `"…".AGE is not null and "…".AGE > 25`, which legend-lite must also emit for relation-column comparisons (a relation column is typed [0..1]) or the text still differs once the wall is gone.

**Owning code** — StatementExecutor.java:2028-2038 (rootGetAllClass), :612-616 (wall); lowering/Lowerer.java:1200-1246 (null-semantics filter path, unverified)

**Fix** — Teach planToString to accept a relation-rooted body: generalise rootGetAllClass (StatementExecutor.java:2028-2038) so a TypedTableReference root yields the store/table context the plan envelope needs, and remove the unconditional wall at :612-616 for that shape. Then verify the optional-operand comparison guard: the `is not null and` conjunct must be emitted from the IR based on the operand's multiplicity (Lowerer.java:1200-1246 null-semantics path), not patched into the engine-text channel, so executed rows and plan text agree. Treat the guard as a second work item to confirm once the wall is removed. One change unblocks the sibling testLimitFilterInSequenceForTableAccessor, which lives outside this bucket.

**Leverage** — Only one test in this bucket, but the same change clears its sibling elsewhere and opens the table-accessor plan surface generally. The null-guard sub-item is a genuine semantic gap.

**Shares code with** — StatementExecutor.java planToString root handling (:603-640, :2028-2038) — shared with the withPlatform cluster and with testLimitFilterInSequenceForTableAccessor in another bucket.

---

### 144. No platform (PureExp) plan node — non-clusterable terminals get pushed into SQL

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `withPlatform`

**Mechanism** — planToString has no platform/store split: it lowers the whole query into ONE Relational node. Person.all()->filter(...).lastName->makeString(', ') has no parameters and one body statement, so it takes the single-Relational path and lowers makeString into SQL. Scalars lowers it to COALESCE(ReduceCollection(STRING_AGG, LIST_TRANSFORM(...), [sep]), ''), and AnsiSqlRenderer.reduceCollection throws DialectCapability("collection reduction ... reached a dialect without a list encoding") — the observed message. The engine instead wraps makeString in a PureExpressionPlatformExecutionNode printed as PureExp over a nested Relational. The failure classifies ERROR not SHAPE only because DialectCapability extends IllegalStateException while planTextAssert catches NotImplementedException/LegendCompileException/UnsupportedOperationException.

**Owning code** — StatementExecutor.java:603-640; plan/PlanText.java:18-25, :63-76; lowering/Scalars.java:791-810; sql/dialect/AnsiSqlRenderer.java:360-363; assert/PlanAsserts.java:188-190

**Fix** — Stage 1 (S): in StatementExecutor.java:603-640, before calling engineSql, test whether the body's terminal is store-clusterable; if it is a TypedNativeCall whose callee is outside the relational-clusterable set applied to a relational sub-expression, throw NotImplementedException("planToString: platform (PureExp) node pending — '<callee>' is not store-clusterable"). Seed the predicate from a DENY-list (makeString and friends), not an allow-list, so currently-passing terminal aggregates do not regress. Stage 2 (L): add a store-clusterability predicate over TypedSpec mirroring storeContract.pure:206-212; split the terminal into the maximal clusterable sub-expression plus platform remainder; add PlanText.pureExp(...) matching executionPlan_print.pure:47's layout; add a minimal Pure-expression printer spelling the child as [Node Index:0]; give PlanText.single a typed resultColumns variant for the platform-child case. Never add DialectCapability to PlanAsserts' catch list. Do not change Scalars' makeString rule — SQL push-down is correct for the DuckDB execution channel.

**Leverage** — One test now, but Stage 2 is the shared prerequisite for every PureExp-rooted golden (Pure.java:1615-1617 notes the 2/3-arg pure-only executionPlan spellings are walled for the same reason). Stage 1 alone is cheap and honest.

**Shares code with** — Touches StatementExecutor.java:603-640 (shared with the table-accessor root cluster) and plan/PlanText.java's node vocabulary; any bucket needing a non-Relational plan node depends on the Stage-2 envelope.

---

### 145. viewToTDS / viewReference query-source entry point is unmodelled

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testViewToTDS`

**Mechanism** — There is no viewReference/viewToTDS native, so the call falls through to the corpus's own Pure definition of meta::pure::tds::viewToTDS, whose body constructs ^TableTDS(store = ...). `store` is declared on supertype meta::pure::mapping::TabularDataSetImplementation, which lives in engine's platform model and is absent from legend-lite's native class catalog, so NewChecker throws "class 'meta::relational::mapping::TableTDS' has no property 'store'" — a leaked type error from compiling a platform-owned corpus body, caught by planTextAssert as a plan wall. The TABLE half is fully built (CoreFn.TABLE_REFERENCE/TABLE_TO_TDS, the Pure natives, the Typer dispatch, TableReferenceChecker, ScanRelations.collectTableToTds); only the VIEW mirror is missing. The underlying view machinery (ViewDefinition, ScanRelations.findView, viewDef, view inlining) already exists.

**Owning code** — compiler/spec/NewChecker.java:83-94; builtin/Pure.java:2016-2023; compiler/spec/CoreFn.java:38-41; compiler/spec/Typer.java:1179; compiler/spec/TableReferenceChecker.java:33-79 (:48-62); lineage/ScanRelations.java:506-527, :1141-1156, :662

**Fix** — Mirror the table half in five edits. (1) Pure.java beside :2016-2023 — add VIEW_REFERENCE__STRING_1__STRING_1__STRING_1 and VIEW_TO_TDS__RELATION_1 signatures with the engine-form comment. (2) CoreFn.java — add VIEW_REFERENCE("viewReference") and VIEW_TO_TDS("viewToTDS"). (3) Typer.java:1179 — dispatch VIEW_TO_TDS to TableReferenceChecker.checkTableToTds and route VIEW_REFERENCE to check. (4) TableReferenceChecker — parameterise check on kind; for VIEW resolve the relation type from ScanRelations.viewDef (or add ModelContext.findView beside findTable:157), keep the "must be a reference, not a derived relation" guard, and apply the SAME strict top-level-only 'default' schema path as :48-62 or a default-schema view resolves against another schema. (5) ScanRelations.java:506-527 — accept viewToTDS over viewReference, resolving columns via findView/viewDef. Then confirm the existing mapped-view inlining (:696-710, :1007-1035) is reachable from a bare relation source; if not, emit a NAMED wall in TableReferenceChecker and ledger. Registering the natives shadows the corpus Pure definitions — confirm the shadow is total. Never special-case viewToTDS in PlanAsserts.

**Leverage** — Single corpus test for a rarely used store-contract entry point; current outcome is SHAPE, not a wrong answer. Legitimate to ledger — but the leaked NewChecker message should at minimum become a named wall.

**Shares code with** — Touches builtin/Pure.java native signatures and compiler/spec/Typer.java:1179 dispatch — merge with any bucket adding relational natives through the same Typer switch.

---

### 146. ModelChainConnection dispatch dropped by navigation-target class-source lookups

**1 test** · effort **L** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testModelConnectionJoin`

**Mechanism** — The chain dispatch closure is threaded only into the ROOT getAll resolution (StoreResolver.java:2717-2720). Every downstream navigation target uses the 2-arg `sources.get(mappingFqn, targetClass)` overload, which hard-codes `upstreamMapping = null, contextKey = ""` (ClassSources.java:114-116). A null upstream short-circuits the chain at ClassSources.java:868-874, so the request lands on the M2M mapping itself, finds no binding for src::_Person, and throws at :622-625. Behind that, the M2M `employees : $src.employees` binding composes to a source-nav marker still typed with the SOURCE class (:941-950), so downstream `.lastName` reasons about src::_Person, not the routed dest::Person.

**Owning code** — core/src/main/java/com/legend/resolver/ClassSources.java:114-116, :868-874, :941-950, :622-625, :70-74, :1267-1286; StoreResolver.java:2717-2720, :680, :942, :2465, :2519; AssociationJoins.java:987; CorrelatedSubselects.java:456,:465,:1707; CastNav.java:49

**Fix** — (1) Make the chain dispatch ambient on ClassSources, mirroring the existing setJsonSources seam (:70-74): add fields for the upstream-dispatch BiFunction and the context key, set once per from()-scope by StoreResolver where fctx is built, and have the 2-arg get pass them instead of null/"". All the AssociationJoins/StoreResolver/CorrelatedSubselects/CastNav sites then dispatch through the ModelChainConnection with no call-site edits; the memo key already includes contextKey. (2) Retarget the source-nav marker at :941-950 so a dest-class-typed binding keeps the src marker for join derivation but stamps the declared dest property type on the node used for downstream property resolution. Verify dispatch's guard order still gives 'explicit mapping wins when it binds'.

**Leverage** — Single test, medium confidence, and part (2) can move graph-fetch results. Worth doing only if M2M-over-relational chains are a funded surface; otherwise ledger.

**Shares code with** — Same file as the eager-binding cluster (resolver/ClassSources.java) but a disjoint mechanism and disjoint lines — do not merge on filename alone. Changing the 2-arg get affects every M2M composition, so buckets touching m2m/graphFetch resolution should re-run after it.

---

### 147. PlanVarPlaceHolder unregistered; no ExecutionOption -> plan-parameter feature

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testPlanForExecutionOption`

**Mechanism** — The corpus declares a DummyExecutionOption whose qualified property returns ^PlanVarPlaceHolder (executionPlanTest.pure:1448-1451). TypeClassifier.findType resolves a bare NameRef only via model.findClass || Pure.findNativeClass (TypeClassifier.java:52-54); meta::pure::executionPlan::PlanVarPlaceHolder is a real platform class (engine executionPlan_generation.pure:121) but is absent from legend-lite's native catalog, so classification throws at TypeClassifier.java:91-92. That is only the first hop: the assert needs the whole ExecutionOption->plan-parameter feature — a FeatureExtension whose extractVariablesFromExecutionOption lambda the plan generator evaluates, plus isExecutionOptionPresent / validateAndReturnExecutionOptionOfType — since the golden's functionParameters come from the OPTION, not the zero-arg query lambda.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:243, :515; compiler/spec/TypeClassifier.java:52-54, :91-92; exec/StatementExecutor.java:836-863

**Fix** — (1) XS, lifts the type wall: register PLAN_VAR_PLACE_HOLDER next to EXECUTION_OPTION_CONTEXT in Pure.java as a native class extending FunctionParameter with genericType[0..1] and multiplicity[0..1] (name/supportsStream inherited). (2) L, what the assert needs: register natives for isExecutionOptionPresent and validateAndReturnExecutionOptionOfType with host implementations filtering ctx.executionOptions by instanceOf; then in StatementExecutor.sequencePlan/planToString, when the 5-arg executionPlan overload carries an ExecutionOptionContext argument, evaluate each extension's extractVariablesFromExecutionOption lambda over the context's options and prepend the resulting name:Type[mult] entries to the FunctionParametersValidationNode spelling at :836-863. Only add entries when a context argument is actually present. No harness special-casing.

**Leverage** — Step (1) is XS and makes both corpus ExecutionOption classes compile, moving other executionPlanTest walls deeper (louder, not wronger). Step (2) is one-test-only feature work.

**Shares code with** — Touches builtin/Pure.java native catalog and the FunctionParametersValidationNode spelling in StatementExecutor.java shared by every parameterized plan golden.

---

### 148. GraphFetch plan-node vocabulary missing from PlanText

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testQuoteIdentifiersFlagWithGraphFetch`

**Mechanism** — The quoteIdentifiers flag works — the emitted SQL is character-identical to the engine's. What is missing is the plan NODE vocabulary for a graph-fetch query. StatementExecutor.planToString funnels every single-node plan into PlanText.single (:629-639), and PlanText knows only the relational forms (single, typeBlock, sequence, functionParametersNode, allocation, scalarTypeBlock, scalarRelational, constant). It has no PureExp, StoreMappingGlobalGraphFetch, RelationalGraphFetch, bare SQL node, nor any notion of localTreeIndices / dependencyIndices / nodeIndex / PartialClass[...propertiesWithParameters=[...]]. So Product.all()->graphFetch($g)->serialize($g) renders as an ordinary relational read.

**Owning code** — core/src/main/java/com/legend/plan/PlanText.java:31-233, :133 (indent helper); exec/StatementExecutor.java:629-639

**Fix** — Add a graph-fetch branch to PlanText plus a dispatch in planToString. (a) Before the PlanText.single call at :629-639, detect a terminal whose typed node is TypedSerialize over a graph tree and route to a new PlanText.graphFetch. (b) Add three forms mirroring the engine printers: pureExp(typeBlock, expressionText, childBlock); storeMappingGlobalGraphFetch(typeBlock, storeFqn, localNodeBlock, children, localTreeIndices, dependencyIndices); relationalGraphFetch(typeBlock, nodeIndex, sqlNodeBlock, children) whose inner node is a bare SQL(...) rather than the class-envelope Relational block. (c) Add a PartialClass type-block spelling derivable from ScanRelations.rootImpl output plus TypedGraphTree property names; the serialize expression text is already carried by TypedSerialize. Build blocks with the shared indent() helper. Key routing on a graph tree so TDS serialize is not captured.

**Leverage** — High for a single-test cluster: this vocabulary unblocks every graphFetch plan-text golden in the executionPlan and graphFetch families that currently renders as a bare Relational node.

**Shares code with** — Owns plan/PlanText.java and the planToString dispatch — merge any other bucket whose graphFetch plan golden shows a Relational node where a StoreMappingGlobalGraphFetch/PureExp block is expected. PureExp is also demanded by the parameterized-lambda cluster.

---

### 149. Many-valued projection column explodes host-side, not in SQL

**1 test** · effort **L** · confidence medium · bucket 6 (wrong rows) · verdicts: REAL_DEFECT 1

Tests: `testConcatenateFlatWithOtherProperty`

**Mechanism** — A [*]-valued scalar projection column is lowered as a plain list expression (e.g. list_concat) and the one-row-per-element explosion happens only at result shaping in Executor.shapeRow. The explosion is therefore a property of the RESULT, not the RELATION: any re-plan with a different column set (here `$result.values.rows.getInteger('simple')`, which projects only 'simple') drops the list column, no cell is a List, and shapeRow returns one row per DB row — 'simple' comes back [1,2] instead of [1,1,2,2]. ProjectChecker.clampTdsCells already clamps the schema to [0..1] on the assumption the rows really were exploded.

**Owning code** — core/src/main/java/com/legend/lowering/Lowerer.java:1178-1198; Executor.java:572-619 (host fallback); ProjectChecker.java:70-94 (leave as-is)

**Fix** — In Lowerer.tryComputedColumns, detect a column whose lambda result multiplicity isMany (read from the column function type, since the relation schema was already clamped) and emit `LEFT JOIN LATERAL UNNEST(<listExpr>) AS <alias>(<col>)` on the base select, projecting `<alias>.<col>` instead of the list expression. LEFT so an empty stream keeps one NULL row, matching shapeRow today and the instance-literal path at Lowerer.java:451-457 — reuse that construct. Keep shapeRow as a defensive fallback. Move the existing 'one many-valued column per row' wall (Executor.java:590-597) to lowering time so it fires earlier.

**Leverage** — Real wrong-row-count correctness bug, and it moves explosion from result shaping into the relation where it belongs. Single test here, but the shape is generic to any many-valued projection.

**Shares code with** — Touches lowering/Lowerer.java projection path and Executor row shaping; any bucket blaming Lowerer computed columns or list-cell results overlaps.

---

### 150. Relation-rooted plan text (table accessor has no class root)

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testLimitFilterInSequenceForTableAccessor`

**Mechanism** — planToString derives every plan fact from a Pure class root: rootGetAllClass returns non-null only for TypedGetAll, and PlanText.single immediately calls ScanRelations.rootImpl keyed by class FQN. A `#>{db.table}#` root compiles to TypedTableReference, which carries db/table identity directly and has no class or mapping, so the root lookup returns null and the K-arm throws NotImplementedException. Execution already works (Lowerer lowers TypedTableReference to a star select); only the plan-TEXT surface is missing. Two further gaps sit behind it: the type block for a relation root must spell precisePrimitives plus the dialect DEFAULT relational type, not the physical column, and engine alias conventions render the leftmost table as `root` only for class extents.

**Owning code** — StatementExecutor.java:611-616, 877-881, 2028-2038; PlanText.java:52-84, 106-107, 125-128; EngineStyleH2.java:330-347; Lowerer.java:3466-3468

**Fix** — Add rootTableRef(body) BFS alongside rootGetAllClass. When the class root is null and a TypedTableReference root exists, take a relation-root branch that skips ScanRelations.rootImpl and builds impl = {"","",tref.store(),tref.table()} directly; add PlanText.singleRelationRoot(...) reusing single's body with the prebuilt impl (only the TDS arm is reachable). Mirror at the sequencePlan twin wall. Give PlanText.tdsTuples a flag so a relation root spells the Pure precisePrimitives column type and the dialect default relational type while resultColumns keeps the physical SQL type. Finally give SqlSource.Subselect a frameName at Lowerer.isolate and special-case it in EngineStyleH2.planSource: consume nextInGroup but render the literal `subselect`, and reserve `root` for class extents. No harness change — planTextAssert is correct.

**Leverage** — Medium. Unblocks both table-accessor plan tests plus any executionPlan over a relation root. Text-only surface; execution already correct. Part (3) reprices aliases corpus-wide, so it needs a full-sweep diff.

**Shares code with** — Touches StatementExecutor.planToString and PlanText.single — the same two entry points as the graph-fetch plan-text wall and any other bucket blaming plan text. Also touches Lowerer.isolate and EngineStyleH2 alias numbering, shared with alias-order failures elsewhere.

---

### 151. Extended primitive types erased to their base primitive

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testFilterAfterJoinInRelationWithExtendedPrimitives`

**Mechanism** — A `Primitive X extends String` declaration is stored only as fqn -> baseTypeName; findPrimitiveExtension chases the chain to a Type.Primitive constant and TypeClassifier.findType returns that base for the extension FQN. Type is a sealed interface with no variant carrying an extension's own FQN, so the declared name is unrecoverable downstream. ExtendedString therefore types as Type.Primitive.STRING, the projected TDS column carries STRING, and PlanText spells "String" where the golden requires meta::relational::tests::model::simple::ExtendedString. The sweep's got string is byte-identical to expected except those two type names, and the non-extended sibling test passes — pinning the defect to exactly this erasure.

**Owning code** — compiler/element/type/Type.java (sealed permits); compiler/element/TypeClassifier.java:39-42; plan/PlanText.java:452, 482-484

**Fix** — Add `record PreciseType(String fqn, Primitive base) implements Type` to the sealed permits clause; have TypeClassifier.findType return it for an extension FQN instead of the bare base; add a first arm in PlanText's pureName returning pt.fqn(), mirroring the existing EnumType arm. The load-bearing part: introduce one normalizing accessor `static Type erased(Type t)` and route every compare-by-kind path through it — identity compares against Type.Primitive constants, family/isNumeric/isTemporal dispatch, SQL dialect type mapping, InferenceKernel coercion, ModelContext subtyping — keeping the precise form only on Type.Column, property types and plan spelling. Do not take the narrower alternative of carrying the name only on Type.Column; that is a second parallel truth about types and will drift.

**Leverage** — Low-to-medium here (this test's divergence is text-only; SQL is byte-identical), but it removes a real type-fidelity hole that any precise-primitive golden will keep hitting.

**Shares code with** — Adds a variant to the sealed Type used by Typer, InferenceKernel, ModelContext and the dialect type mapping; PlanText.java:452 is shared with every bucket asserting plan type blocks.

---

### 152. Checked graph fetch: constraints only at the root, defect path hard-coded empty

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testCheckedWithCircularConstraints`

**Mechanism** — The graphFetchChecked envelope evaluates class constraints only on the ROOT graph node: StoreResolver passes checkedEnvelope exactly once, to the root buildGraphNode, and every child goes through graphChild into the 9-arg overload which delegates with checked = false, so checkedConstraints() is non-null only at the root. Even at the root, CheckedEnvelope.wrap hard-codes the defect path to an empty ArrayLit, so the engine's path=[{propertyName:firm,index:null}] shape is unrepresentable. Rows come out with defects: [] and the first diff is at $[2].defects. The golden additionally pins a documented engine bug (the test's own toFix comment) where a constraint that throws while reading unmapped data becomes an 'Unable to evaluate constraint' defect.

**Owning code** — resolver/StoreResolver.java:2584, 2958; GraphEmission.java:219-220, 461, 1392-1394, 397-401; CheckedEnvelope.java:51-53

**Fix** — (a) Thread `checked` through GraphEmission.graphChild into the child buildGraphNode call; give TypedSerializeGraph.CheckedConstraint a `path` field built from tree position (root = [], child of p = parent path + {propertyName:p, index}); render it from CheckedEnvelope.wrap instead of the empty ArrayLit; hoist child defects into the root object's flat defects array. Filter constraints per node the way the engine does by porting canEvaluateForTree against the node's tree plus mapping bindings, so a constraint reading unfetched data is EXCLUDED rather than silently true. (b) Only to match this golden, emit an 'Unable to evaluate constraint [id]: data not available' defect when the predicate cannot be lowered — scope this strictly to checked-envelope predicates and keep the wall text inside the message, or ledger the test. A blanket rule would mask resolver walls as data defects.

**Leverage** — Part (a) is genuine correctness — nested defects are currently invisible corpus-wide. Part (b) exists only to match an engine bug the test itself flags as toFix; low value, real danger.

**Shares code with** — StoreResolver graph-node construction and GraphEmission are shared with every graphFetch bucket; (a) changes the defects array of all nested-class checked tests.

---

### 153. Multi-column olapGroupBy partition desugars to a collection instead of ColSpecArray

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testExecutionPlanGeneration`

**Mechanism** — Typer.olapGroupByDesugar rewrites legacy TDS olapGroupBy into windowed extend(src, over(...), ~col:...). It collects one ColSpec per partition name and wraps them in a plain PureCollection. Typer.collection types a collection literal with multiplicity Bounded(n,n), so two partition columns yield ColSpec<...> at multiplicity [2]. Every registered `over` overload declares its column parameter as ColSpec<T>[1] or ColSpecArray<T>[1], so no candidate scores >= 0 and InferenceKernel.resolveOverload throws "no overload of …::over structurally matches". A single-column olapGroupBy accidentally works (multiplicity [1]), which is why only the two-column corpus test walls. The sibling `restrict` desugar already builds the correct ColSpecArray node.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:651,654,674 (olapGroupByDesugar) and :833 (windowColsProjectDesugar); Pure.java:1837-1845; InferenceKernel.java:808-822

**Fix** — In Typer.olapGroupByDesugar, declare partSpecs as List<ColSpec> and at line 674 emit `new ColSpecArray(partSpecs)` instead of `new PureCollection(partSpecs)`. Leave line 677 (sortKeys as PureCollection) alone — SortInfo<T>[*] accepts a many-valued collection. Apply the identical change at Typer.java:833 in windowColsProjectDesugar, which has the same latent bug for a col(window(p1,p2), …) project column. No change needed in OverChecker or Pure's registrations. Re-check the single-column olapGroupBy tests (testTDSWindowColumn.pure:31/43/56/73/93, testTDSFilter.pure:218) for identical emitted SQL, since they shift from ColSpec[1] to ColSpecArray[1]. Do not teach the harness or PlanAsserts to tolerate the wall.

**Leverage** — The typer fix is near-trivial and removes a real desugar bug, but this test then faces a fused group-by/window H2 golden with inlined user functions — expect a text diff, not a pass.

**Shares code with** — compiler/spec/Typer.java desugar path — any bucket blaming Typer's relation desugars or `over` overload resolution shares this file; the :833 companion pre-empts the same failure for window-col projects.

---

### 154. testDataGen has no view slice (VIEW-backed relations wall in locate)

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testAlloyTestDatGenWithQuotedColumnsForViews`

**Mechanism** — TestDataGenerator.locate searches each database's schemas and top-level table lists for a matching TABLE; when it finds a VIEW of that name it throws NotImplementedException("testDataGen: view-backed relation … view slice pending") at both the schema-owned and top-level sites. The query projects Party.identifier.identifier through MappingWithJoinToSchemaInAnotherView, so ScanRelations yields AltID_View as a relation in the tree and the first locate call walls. The wall is honest: nothing in TestDataGenerator emits the engine's two-node view slice, and relationType hard-codes type=TABLE.

**Owning code** — core/src/main/java/com/legend/testdatagen/TestDataGenerator.java:916-966 (locate), :1431 (planNode), :1468-1477 (relationType)

**Fix** — Implement the view slice mirroring the engine's planTestDataGenerationForNestedViewTree. (a) Make locate return the view definition instead of throwing — add a nullable ViewDefinition to Located. (b) In planNode, when the located relation is a view emit TWO allocations: a `<res>_v` node for the view's main table planned like a normal table node (select top 20, seeded from the parent ${placeholder} or rowIdentifiers at the root), then a `<res>` node whose type is Relation[…, type=VIEW, schema=…, database=…] and whose sql re-roots the view's own column expressions onto `(select * from (${<res>_v}) as "root") as "root"`. (c) relationType must spell type=VIEW. (d) Keep the view's column aliases QUOTED — that is the test's point. Recurse for children off the VIEW node's varName.

**Leverage** — Genuinely missing platform feature with a single test behind it; worth doing only if testDataGeneration view parity matters, and the two-node emission shifts varName sequences in other goldens.

**Shares code with** — Shares TestDataGenerator.planNode with the no-seed-root cluster — both restructure the same node-emission function, so sequence them together to avoid conflicting rewrites.

---

### 155. PlanText physical-column resolvers have no SqlUnion arm

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testEnumFilterWithUnionMappingPlanGeneration`

**Mechanism** — A union-mapped class produces a TypedConcatenate, which Lowerer lowers to a Subselect whose inner is a SqlUnion, aliased "t2" from nextAlias(). PlanText must spell the plan's `type = TDS[…]` and `resultColumns = […]` by resolving each top-level projection's alias.column back to a physical store column via resolvePhysical, whose Subselect arm is guarded on `sub.inner() instanceof SqlSelect`. For a union subselect the alias matches but the inner is a SqlUnion, so the guard fails, control falls out of the switch and it throws "plan: alias 't2' not resolvable to a table (Subselect)". resolveStarColumn has the identical SqlSelect-only gap. This is a missing arm, not an honest wall — all branches of a mapping-generated union share output shape, so the column IS resolvable.

**Owning code** — core/src/main/java/com/legend/plan/PlanText.java:733-735 and :756-758 (resolvePhysical), :692-709 (resolveStarColumn); Lowerer.java:461-462,283-285

**Fix** — Add a UNION arm to both resolvers in PlanText. In resolvePhysical, after the existing SqlSelect branch, handle `sub.alias().equals(alias) && sub.inner() instanceof SqlUnion u && !u.branches().isEmpty()`: resolve through the FIRST branch — if branches.get(0) is a SqlSelect, find the projection whose outputName strips to `col` with a SqlExpr.Column expr, then recurse resolvePhysical(bs.from(), c2.table(), strip(c2.name())). A non-column projection (the 'city' literal discriminator) stays loud. Make the same addition to resolveStarColumn's Subselect arm. First-branch resolution is sound because mapping-generated union branches share output names and store types; pin that assumption with a comment. Do not soften PlanAsserts' literal plan-text compare.

**Leverage** — Additive — fires only where the code throws today — and unblocks every plan-text golden over a union- or inheritance-mapped class. But it buys a golden DIFF, not a pass: union alias/column naming parity is separate.

**Shares code with** — plan/PlanText.java resolvePhysical/resolveStarColumn are shared by every executionPlan/planToString test in any bucket; the same missing arm explains other "alias not resolvable to a table" walls.

---

### 156. meta::json library unported and executeLegendQuery binds no result envelope

**1 test** · effort **L** · confidence medium · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testPushDownProjectWithParameter`

**Mechanism** — The whole meta::json platform library is absent. The test's final statement navigates `parseJSON()->cast(@JSONObject).keyValuePairs->filter(kv|…).value->toOne()->toCompactJSONString()`. JsonAssertCanon.isPlumbing recognises only parseJSON/toPrettyJSONString/toOne/cast chains bottoming at a Variable, so this chain (an AppliedProperty plus a filter) is not plumbing; ConnEquality.letFold falls through to eager evaluation, Typer.checkGeneric finds zero catalog candidates for meta::json::parseJSON and throws. Grep finds no parseJSON native, no JSONElement/JSONObject/JSONKeyValue hierarchy, no toCompactJSONString. Behind it: ElqSplice.splice binds the executeLegendQuery result as toString(<final expr>) and wraps a JSON envelope only when the chain contains `serialize`, so a project()->from() terminal never yields the engine's `result` envelope.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java (native declarations); ElqSplice.java:87-105; JsonAssertCanon.java:95-114; Typer.java:1448-1452; ProtocolEmitter.java:531 / TailEmitter.java:163

**Fix** — Two pieces, platform-side. (A) Port meta::json: add JSONElement/JSONBoolean/JSONString/JSONNumber/JSONNull/JSONArray/JSONObject/JSONKeyValue to the builtin model in Pure.java, copied verbatim from core_functions_json/json.pure:32-70, declaration-only (Tier-1) so nothing forces a transitive load; declare parseJSON and toCompactJSONString as host natives, implementing parseJSON over the existing core/src/main/java/com/legend/sql/Json.java so it materialises class instances rather than raw Maps. cast/.keyValuePairs/filter/toOne then work through the ordinary object pipeline. (B) Replace ElqSplice's serialize-only special case with a result-shape switch: relation/TDS terminals bind the engine tdsBuilder envelope with `result:{columns:[…],rows:[{values:[…]}]}`, serialize keeps its json form, scalars keep bare toString. Put the envelope next to the existing TDS JSON writer and have ElqSplice call it. Do NOT add a parseJSON-chain recogniser to JsonAssertCanon.

**Leverage** — Large port for one test; the wall is loud and correctly attributed, so ledgering is defensible. Piece (B) alone risks breaking other executeLegendQuery tests that assert the raw $result string.

**Shares code with** — builtin/Pure.java native declarations; piece (B) changes $result for every executeLegendQuery test (tests/mapping/relation, platformOperations, modelToModelToRelational/milestoned), so those buckets merge on the envelope half.

---

### 157. Set-routed Join property mappings ignore targetSetId (prop[map2]/prop[map3])

**1 test** · effort **L** · confidence high · bucket 11 (unclassified) · verdicts: MISSING_FEATURE 1

Tests: `testExistsAsNullWithSubType`

**Mechanism** — mappingForMultipleSubTypes maps `fnScope` twice with subtype-set routes (fnScope[map2]→Private, fnScope[map3]→Public). JoinChainEmission.classTypedTargetIfMapped decides whether a class-typed Join PM becomes a class NAVIGATE purely from the DECLARED property type: it reads FunctionScope and asks model.isMappedClass("FunctionScope"). FunctionScope is an abstract supertype mapped nowhere (only its subclasses are), so it returns null, no legacyNavigate is emitted, and MappingNormalizer's ctor field falls to the physical-slot arm `$row.<joinSlot>` — a table sub-row. NewChecker then fails to unify that row against FunctionScope[1]. The PM's targetSetId is never consulted, and the name-keyed ctor field map would overwrite one route with the other anyway.

**Owning code** — core/src/main/java/com/legend/normalizer/JoinChainEmission.java:173, :272, :291-299; core/src/main/java/com/legend/normalizer/MappingNormalizer.java:2608-2617, :2609

**Fix** — Both changes in the normalizer, shipped together. (1) classTypedTargetIfMapped takes the PropertyMapping (or a @Nullable targetSetId) and, when targetSetId != null, resolves the target class from the named set implementation in the enclosing LegacyMappingDefinition instead of the declared property type; assert the set's className is a subtype of the declared type and fall back to the declared type when null. Update the three call sites (JoinChainEmission.java:173, :272, MappingNormalizer.java:2609). (2) Key the nav slot by (propertyName, targetSetId) instead of propName (mintNavSlotAlias / navSlotByProp, dedup :291-299), and emit ONE ctor field per route in MappingNormalizer:2608-2617, binding each route as stc_<Private|Public>___fnScope alongside the plain name so `->subType(@Public)` picks map3. Do not ship (1) without (2) — the surviving-route binding is wrong rows.

**Leverage** — Moderate. Correctness-shaped (silent wrong rows if half-landed) and likely clears the whole prop[subTypeSetId] shape, but classTypedTargetIfMapped is called from the Inline-embedded collision check and union-route code, so re-routing risk is real.

---

### 158. Subtype-cast dispatch over an inheritance union omits JOIN-navigated (class-typed) properties

**1 test** · effort **L** · confidence high · bucket 11 (unclassified) · verdicts: MISSING_FEATURE 1

Tests: `testForcedSubTypeProjectDirect`

**Mechanism** — RoadVehicle is an Operation inheritance union of Car[map1] and Bicycle[map2]. The query forces r->subType(@Bicycle).person.name, which the resolver rewrites into a read of stc_<Bicycle>___person. UnionSynthesis.subTypeDispatchProps only mints stc columns for SCALAR properties (t is a NameRef with no class) or for flattenable EMBEDDED ctor leaves. `person` on Bicycle[map2] is a class-typed Join PM — a nav SLOT, neither scalar nor ctor — so no stc column is emitted in any thread, and Substitution finds no such binding and walls. Cast dispatch over an inheritance union simply has no arm for join-navigated properties.

**Owning code** — core/src/main/java/com/legend/normalizer/UnionSynthesis.java:798-830 (subTypeDispatchProps), :877-929 (addSubTypeDispatchCols), :934-964 (addStcEmbeddedLeaf), :833-845 (MEMBER_WITNESS); core/src/main/java/com/legend/resolver/ClassSources.java:711-798, gate at :726-730

**Fix** — Transplant the owning member's navigation instead of projecting a scalar. In subTypeDispatchProps add a third branch for visibleOnTarget && !scalar && the member's field value is a nav-slot read, recording the prop as a NAV cast target. In addSubTypeDispatchCols (or a new addStcNavTransplant sibling of addStcEmbeddedLeaf) emit, for the OWNING thread only, the member's join step re-anchored under ClassMapping.subTypeColumn(target, prop), and for every other thread project the join KEY columns as typed NULL so the transplanted left join yields no row there. Then generalise ClassSources' stcNavTransplants block (:711-798) past its sameRootTable gate (:726-730) so union members on different tables are picked up; the pseudo-binding must be a slot read so ordinary nav/demand machinery resolves `.name`. Reuse MEMBER_WITNESS so Car rows read TDSNull. Keep the loud wall if deferred.

**Leverage** — Moderate. Correct-rows-critical (a scalar fallback would return David Scott for Cars instead of TDSNull) and likely clears the inheritance-subType family, but it reshapes every union query's thread alignment — expensive and risky.

**Shares code with** — Shares the stc_ column contract with the embedded-cast cluster and with ClassSources.java:688-700 binding exposure; any bucket touching stc column naming or union thread alignment overlaps here.

---

### 159. Correlated filtered navigation as a chained association hop (nested filter inside a class-result map)

**1 test** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testVariableReferenceInMapWithNestedFilter`

**Mechanism** — Two stacked gaps. RECOGNITION: SyntheticHeads.liftFilteredHeads normalizes ->map only when the mapper body is a bare property read, or when the map SOURCE is already the TypedFilter; here the filter sits INSIDE the mapper body over a further hop, so no synthetic #fN head is parked. SUBSTITUTION: the leaf then reaches Substitution.rewrite as a property access over a TypedMap; filteredNavLeafRead peels class-typed hops and toOne wrappers but not a TypedMap, returns null, and the generic rebuild β-inlines the map into a CLASS-typed-source TypedFilter that matches no arm and hits the default wall. Even normalized, the head is two hops off the instance var and the leaf is [*], so the real blocker is the named correlated-mid-hop wall.

**Owning code** — core/src/main/java/com/legend/resolver/SyntheticHeads.java:296, 303-315, 401-424; core/src/main/java/com/legend/resolver/Substitution.java:1767, 1830, 1848, 1893-1899, 2587, 2621-2655; core/src/main/java/com/legend/resolver/StoreResolver.java:2376-2386

**Fix** — (a) In SyntheticHeads.liftFilteredHeads (line 296) add a NORMALIZATION arm ahead of the existing lifts: an object-space TypedMap whose 1-param mapper body is a navigation chain rooted at that param (property hops and/or a filter over them) β-inlines the param with the map source and re-enters the walk, making the arm at 303-315 the degenerate case. Reuse the same inlineParam substitution Substitution.java:1830 uses so both paths agree. (b) The real work: extend the hop-0 parent-copy reroute in CorrelatedSubselects to chained MID hops, lifting the wall at StoreResolver.java:2376-2386 — the sub-select re-roots at the query's own class, carries the chain's joins and the outer-correlated conjunct in its WHERE, projects pk plus leaf, and LEFT JOINs back on the pk. Do NOT relax filteredNavLeafRead's head/multiplicity gates (Substitution.java:2621-2655): that route builds a SCALAR correlated subquery and would impose toOne semantics on a [*] leaf, raising or dropping rows.

**Leverage** — L effort for one currently-failing test; part (a) alone is cheap and may unblock uncorrelated post-normalization shapes. Defer (b) unless other buckets also hit the mid-hop correlated wall.

**Shares code with** — Substitution's TypedFilter default wall and StoreResolver.java:2376-2386 correlated-mid-hop wall — merge with any bucket reporting that same named wall.

---

### 160. M2M positional explosion (`prop*`) unimplemented in synthM2M

**1 test** · effort **L** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testCrossMappingJsonToDBWithExplosion`

**Mechanism** — crossMapping5 uses explosion bindings (`tradeId* : $src.s_trades.s_tradeId`, etc.). The `*` marker parses and survives (ClassMapping.Pure.PropertyBinding.explode, MappingFromProtocol passes pm.explodeProperty()), but MappingNormalizer.synthM2M hits `if (pb.explode())` and throws a ModelException, which lands on the mapping-poison ledger; ClassSources.classSource later reports 'class T_Trade is not mapped in mapping ...' with the poison appended. legend-lite's M2M terminal is a single `map(src | ^Class(...))` — one target per source instance, no positional fan-out. The refusal is at synthesis, not parse.

**Owning code** — core/src/main/java/com/legend/normalizer/MappingNormalizer.java:~1355 (synthM2M explode throw)

**Fix** — Implement positional fan-out in synthM2M. Partition the PropertyBinding list into exploded and non-exploded as inMemory.pure does. When the exploded set is non-empty: (a) assert all exploded bindings share one source collection root (engine's 'Explosion on target properties from more than one class is not supported'); (b) replace the terminal `map(src | ^Class(fields))` with an index-aligned zip — `map(src | range(0,<len>)->map(i | ^Class(nonExploded..., e_k = <expr_k>->at($i))))` where `<len>` is the exploded expressions' common size; (c) evaluate non-exploded fields once per src. On the relational/JSON side this lowers to UNNEST-with-ordinality over the JSON array frame (JsonSourceFrame.classSource realizes the S_TradesWrapper extent). Keep the existing throw as the fallback for multi-root explosion. Do not special-case the test.

**Leverage** — Genuine missing surface; also unblocks the mft/testExplosion families named in the throw's own comment. Worth doing if those families matter, otherwise a big lift for one test.

**Shares code with** — MappingNormalizer.synthM2M plus JsonSourceFrame lowering — overlaps any bucket blaming M2M binding synthesis or JSON array extents.

---

### 161. Temporal collector has no identity-cursor arm for filter/map over the instance variable

**1 test** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testBusinessDateInjectionFromVarReferenceInProjectUsingExternalFunction`

**Mechanism** — `filterOrders($o)` inlines to `$o->filter(o2|...)->map(x|$x.id)`. TemporalFrame.collectTemporalNodes composes an inner cursor only when the filtered/mapped source is a NAVIGATION path off the instance var (`Substitution.pathOf(tf.source(), userVar)` non-null). Here the source is the bare instance VARIABLE, and pathOf returns null for anything that is not a TypedPropertyAccess. The arm never fires, the walk descends into the predicate with the outer userVar still bound, reaches `$o2.product(...)` whose root is the inner param, pathOf returns null again, and the guard throws 'milestoned property access on a NESTED navigation is not supported yet'. A filter over the instance is a zero-length cursor move the code has no arm for.

**Owning code** — core/src/main/java/com/legend/normalizer/TemporalFrame.java:2018-2022 (wall), 2061-2072 (TypedFilter), 2077 (TypedProject), 2096 (TypedMap); resolver/Substitution.java:772 (pathOf), 1738-1745 (filteredInstanceRead)

**Fix** — Add an IDENTITY-CURSOR arm ahead of the existing pathOf tests in each of the TypedFilter/TypedMap/TypedProject cases: when the source is `TypedVariable(userVar)` and the lambda has one parameter, the cursor does not move — β-inline the lambda param with the source (`Substitution.inlineParam`) and recurse with the SAME userVar and prefix. That registers the spec under chain key 'product' with dates `$o.orderDate->toOne()`, byte-identical to the passing sibling at testBusinessDateMilestoning.pure:561. This is necessary but not sufficient: Substitution's filteredInstanceRead arm emits CASE WHEN pred THEN id ELSE NULL, while the engine golden emits root.id for every order via a LEFT OUTER JOIN over a filtered subselect. Green also requires the project-of-instance-filter emission to adopt the engine's parent-copy left-join subselect shape; otherwise ledger the residual as a row divergence.

**Leverage** — Two-part: the collector arm is small, but the SQL-shape change (CASE-WHEN vs left-join subselect) is a real wrong-rows correctness divergence worth fixing on its own merit.

**Shares code with** — Second half lives in resolver/Substitution.java filteredInstanceRead emission — any bucket blaming filtered-instance projection shape shares it.

---

### 162. Subtype-cast hop (stc_) is not a first-class navigation hop on class-typed properties

**1 test** · effort **L** · confidence low · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testRoutingWithSubtypePropagation`

**Mechanism** — `$x.employees->subType(@PersonExtension).manager->subType(@PersonExtension).name` canonicalizes to [employees, stc_<PE>___manager, stc_<PE>___firstName]. `employees` is a Join PM registering via the NAV-slot route, so its AssocSub comes from `navMats.get(alias).subNavs()`. The MID hop `stc_<PE>___manager` yields no SubNav (`head subNavs=[]`), so rewriteMultiHop has no walk: the chained-assoc key is absent, the embedded-ctor drill fails (head binding is a nav read, not a ctor), the head-join+embedded-tail arm fails likewise, the flat stc column lookup misses, and the wall fires. For a SubNav to exist the Person ClassSource must carry `stc_<PE>___manager` both as a pseudo-binding and as a transplanted TypedNavigate; the class-typed subtype transplant never fires here.

**Owning code** — core/src/main/java/com/legend/normalizer/ClassSources.java:719-731, 735-780, 793-798, 1122-1149; resolver/AssociationJoins.java:884-892, 906-919, 921-936; resolver/Substitution.java:742-771, 1206-1247, 1338-1345; resolver/NavMaterializer.java:133-134, 305-318, 444-495

**Fix** — Make the cast hop a real navigation hop. (1) In ClassSources, ensure the class-typed arm of the same-source subtype transplant fires for this mapping — audit the two silent skips (the swallowed subclass build at 719-723 and the root-table gate at 724-731 / sameRootTableUnderSubstitution) across `include simpleRelationalMappingInc[dbInc->db]`; a subclass that cannot be built must record WHY, so a cast read walls honestly instead of surfacing as empty subNavs. (2) Where a hop's target class is resolved by NAME (AssociationJoins.hopTargetClass, toOneClassProp), decode stc heads: when `ClassMapping.isSubTypeColumn(prop)`, split with `ClassMapping.classOfWitnessPrefix` (model-driven, never string surgery) and look the tail up on the SUBCLASS; this also unblocks chainNavTails. (3) With (1) done, NavMaterializer/Substitution need no change — demandSlotSubTail and subTree register the SubNav and the leaf resolves.

**Leverage** — Low confidence and multi-site, but the same missing stc pseudo-binding is blamed by testDataGeneration and mapping/inheritance failures — check whether those merge before budgeting the L.

**Shares code with** — Spans normalizer/ClassSources, resolver/AssociationJoins and resolver/Substitution subtype arms; any bucket reporting 'property stc_...___X has no binding' or 'head subNavs=[]' likely merges here.

---

### 163. Union-thread synthesis emits no stc leaves for join-backed class-typed subtype properties

**1 test** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testInheritanceMultipleLevel`

**Mechanism** — The third projection is `$f.vehicles->subType(@Bicycle).person.name`. Person.vehicles routes to map1/map2 under a Vehicle inheritance operation, so subtype casts read thread-local `stc_<Sub>___<prop>` columns. UnionSynthesis.subTypeDispatchProps emits an stc column only when the subtype-only property is SCALAR, or when it is class-typed with an EMBEDDED NewInstance ctor field (flattened by addStcEmbeddedLeaf). `Bicycle[map2].person : [myDB]@PersonBicycle` is class-typed and its ctor field is a pipeline SLOT read of a legacyNavigate hop, so `ctorOf` returns null and nothing is emitted. The union relation carries no `stc_..._Bicycle___person__name`, so Substitution's multi-hop walk exhausts all four arms and walls. `stc_..._Car___engineType` exists (scalar), which is why only column 3 fails.

**Owning code** — core/src/main/java/com/legend/normalizer/UnionSynthesis.java:795 (subTypeDispatchProps), :936 (addStcEmbeddedLeaf); resolver/Substitution.java:1317

**Fix** — Add a third arm to subTypeDispatchProps alongside the scalar and embedded-ctor arms: when `visibleOnTarget` and the ctor field value is a pipeline SLOT read (the legacyNavigate hop minted by JoinChainEmission for a class-typed PropertyMapping.Join), enumerate the scalar leaves of the join TARGET set's mapping and register `prop + "__" + leaf` for each. Extend addStcEmbeddedLeaf to emit `$row.<slot>.<targetCol>` for the owning thread and `nullOfDeclaredType` for the others, keeping the existing String-cast and toOne shaping so threads type-align; the member thread's pipeline already carries the join hop, so no new joins are needed. Evaluate the cheaper alternative first: make Substitution's subtype arm materialize the hop on demand inside the owning member arm, avoiding a pre-flattened column at the cost of a larger resolver change. Note the test also asserts four generated SQL texts and a CSV.

**Leverage** — Unblocks any `subType(@Sub).<classTypedProp>.<leaf>` over an inheritance/union mapping, but this test additionally asserts generated SQL and CSV, so navigation alone will not turn it green.

**Shares code with** — UnionSynthesis stc-column emission and Substitution.java:1317's subtype arm — shares the resolver site with the stc-hop cluster above; shares the file (different function) with testUnionToUnion.

---

### 164. classifyUnionRoutes does not descend into embedded property mappings

**1 test** · effort **L** · confidence medium · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testUnionToUnion`

**Mechanism** — unionMappingWithEmbeddedProperty2 places routed union joins inside an embedded block (`bridge ( employees[set1]:@PersonSet1FirmSet1, employees[set2]:@PersonSet2FirmSet1 )`). UnionSynthesis.classifyUnionRoutes, which collects same-named routed joins into p.unionRoutes so one navigate dispatches per member, iterates only the TOP-LEVEL propertyMappings of the Relational class mapping and never descends into PropertyMapping.Embedded. The two `employees[...]` sub-PMs therefore reach JoinChainEmission.emitHopsForStructuralPm's Embedded arm as independent class-typed Joins; the first mints pipeline slot 'employees', the second finds the slot already taken with a mapped class-typed target and throws the 'Embedded sub-PM collides with an existing pipeline slot' NotImplementedException, poisoning Firm so classSource reports 'Firm is not mapped'.

**Owning code** — core/src/main/java/com/legend/normalizer/UnionSynthesis.java:197 (classifyUnionRoutes); JoinChainEmission.java:76 (top-level dropped-route arm), :121-128 (Embedded arm); MappingNormalizer.materializeEmbedded

**Fix** — Generalize classifyUnionRoutes to walk the full PM tree: for each PropertyMapping.Embedded / InlineEmbedded / OtherwiseEmbedded, recurse into its sub-PMs with the EMBEDDED class as owner (resolved via MappingNormalizer.findPropertyTypeDeep, as JoinChainEmission.java:122-128 already does), and key both the collected routes and the dropped-route ledger by an embedded-qualified path ('bridge.employees') rather than the bare name. Teach emitHopsForStructuralPm's Embedded arm to consult that ledger before minting a slot — a route-classified sub-PM must not emit its own hop, mirroring the top-level `droppedRoutedProps` arm. Then make MappingNormalizer.materializeEmbedded build the embedded ctor's `employees` field from the union-route navigate (the per-member OR entry set) rather than a single slot. Keep the collision throw as the fallback for unclassifiable embedded routes.

**Leverage** — Also unblocks the testUnion.pure:209-212 and testUnionWithExtends.pure:186-189 filter-over-bridge.employees tests and any routed same-named class-typed joins inside an embedded block.

**Shares code with** — Same file as testInheritanceMultipleLevel (UnionSynthesis) but a different function — do not merge; also touches JoinChainEmission and MappingNormalizer.materializeEmbedded, shared with embedded-mapping buckets.

---

### 165. filtered navigation has no per-segment descriptor (qualifier -> class hop -> qualifier)

**1 test** · effort **L** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `testQualifierWithIsolation`

**Mechanism** — The visible ERROR is the class-typed-leaf mis-projection, but the real blocker is the SECOND qualifier after a class hop: `$f.employeeByLastName('Smith2').firm->toOne().employeeByLastName('Smith3').age` inlines to a filter whose source head sits on the HOP TARGET, not on the user variable. filteredNavLeafRead requires the filter source to be a property access directly on the instance variable, so it returns null. No other arm registers a filtered-navigation segment keyed by a composed chain: existsSubs are keyed by a bare head property and assocs by headKey. A qualifier -> class hop -> qualifier chain therefore has no route at all, and StoreResolver walls it.

**Owning code** — core/src/main/java/com/legend/resolver/Substitution.java:2639-2648; StoreResolver.java:1502-1531, 2113-2245 (keys at :2243, :1525), wall at StoreResolver.java:2377-2387

**Fix** — Do not attempt this with a filteredNavLeafRead patch. (a) Interim: land the class-typed-leaf guard at Substitution.java:2775 (shared with the XX cluster) so this fails with an honest wall instead of a lowerer column error. (b) The real rung: give filtered navigation a per-SEGMENT descriptor. In StoreResolver's head registration (nav-slot route :1502-1531 and registerExistsSubs :2113-2245) key ExistsSub/AssocSub by the COMPOSED chain (`employees#f0`, `employees#f0.firm`, `employees#f0.firm.employees#f1`) instead of by a bare head property, and teach Substitution's filtered-nav recogniser to accept a head whose receiver resolves to a previously-registered segment: look up its descriptor and continue. Each segment emits its own filtered-subselect join; the continuation re-keys on the joined alias, matching the engine golden's four chained LEFT JOINs.

**Leverage** — Expensive but it is the only route for the whole qualifier->hop->qualifier family (Forced/Forced2 named at WALL_DEPTH.txt:362-363). Low leverage per test until the segment keying lands; sequence it after the XX guard.

**Shares code with** — Re-keys StoreResolver's ExistsSub/AssocSub registries, which resolver/Substitution.java's recognisers read — any bucket touching Substitution's filtered-nav or multi-hop arms must be sequenced against this.

---

### 166. Embedded-ctor drill missing in sub-navigation materializer

**1 test** · effort **L** · confidence high · bucket 7 (resolver (H)) · verdicts: REAL_DEFECT 1

Tests: `testToManyWithQualifierWithFilterOnJoin`

**Mechanism** — On path [account, incomeFunctionSplits#f0, incomeFunction, Classification, name], the SubNav for the filtered head exists but has no 'incomeFunction' child: on AccountIncomeFunctionSplit, incomeFunction is an EMBEDDED property mapping (a TypedNewInstance binding), and NavMaterializer only demands a sub-step when the binding is itself a nav-slot read. The join to the embedded's own Classification slot is never materialized. At rewrite time ctorTailLeaf drills incomeFunction -> Classification, then stops because it only follows nested ctors and Classification's value is a class-typed slot read; with 'name' unconsumed it returns null and the wall fires. The root-level demand scan has exactly this embedded-then-slot drill; the sub-level one does not.

**Owning code** — core/src/main/java/com/legend/resolver/NavMaterializer.java:129-174, :444-462; core/src/main/java/com/legend/resolver/Substitution.java:1180-1205, :3093-3122, wall at :1338-1345; reference implementation StoreResolver.java:1359-1378

**Fix** — Port the root-level embedded-ctor drill into the sub-navigation materializer and give the rewrite a matching dispatch. (1) In NavMaterializer.navTargetMaterialized's tail loop and demandSlotSubTail: before giving up on tail.get(0), unwrap toOne/otherwise and, while the binding is a TypedNewInstance containing the next tail segment, drill into it exactly as StoreResolver.java:1359-1378 does; when the drill lands on a class-typed slot read, demand that nav step and record the SubNav under the composed DOTTED key ('incomeFunction.Classification') with remaining segments as its own sub-tail. (2) In Substitution.rewriteMultiHop, when ctorTailLeaf's drill ends on a class-typed slot read with segments left, look up the composed dotted key in sub.children() and resolve the leaf through that child's bindings/prefix, reusing the emission at :1206-1247. Keep the loud wall when neither a ctor property nor a materialized child is found.

**Leverage** — Single test here, but the same embedded-head-then-slot shape is confirmed in tests/mapping/embedded (bondDetails.bondClassification.type), so real cross-bucket reach for a structural gap.

**Shares code with** — Touches resolver/Substitution.java rewriteMultiHop (:1180-1247) and its wall at :1338-1345 — buckets reporting 'head subNavs=[...]; head binding=TypedNewInstance/TypedNativeCall' failures blame the same code and should merge here.

---

### 167. compileLegendGrammar drops every non-FunctionDefinition element (no dynamic-element channel)

**2 tests** · effort **XL** · confidence high · bucket 10 (harness SHAPE) · verdicts: MISSING_FEATURE 2

Tests: `testFlatten_ViaNoArgMapping`, `testFlatten_ViaNoArgMapping_ViaAssociation`

**Mechanism** — Both tests execute against a mapping BUILT AT RUNTIME: `compileLegendGrammar('###Mapping ...')->at(0)->cast(@Mapping)` then `^$mapping(includes = ^MappingInclude(included = milestoningMapSmall, owner = $mapping))`. EngineTestExecutor.clgArm collects only `el instanceof FunctionDefinition` (EngineTestExecutor.java:826) and returns the ORIGINAL rhs untouched when the ->at(i) index is out of range (line 834), so a ###Mapping payload yields nothing and the let stays bound to the raw helper call. Two gaps sit behind it: Runner.expandHelperCalls will not beta-expand a let-bound helper unless it matches pairIdiom/singleExecute/executeTerminal/planChain (Runner.java:696-701), so clgArm never even sees the call; and there is no channel to register a dynamically parsed element into the per-test ModelContext, nor support for copy-with-includes. The reported wall is downstream: $mapping types as a TypedUserCall, FromChecker accepts only a TypedPackageableRef (line 35) or a ClassType meta::core::runtime::Runtime (lines 53-56), and throws at FromChecker.java:74. The two tests differ only in which property the grammar payload transforms (synonymsMilestoned vs synonymsMilestonedViaAssociation) and their sweep messages are byte-identical.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:796-838 (clgArm, FunctionDefinition-only filter at :826, silent passthrough at :834); core/src/test/java/com/legend/rcorpus/Runner.java:647-701 (helper-expansion gate); core/src/main/java/com/legend/compiler/element/ModelContext.java (needs a per-test overlay registration API); core/src/main/java/com/legend/compiler/spec/FromChecker.java:74 (the surfacing throw)

**Fix** — One dynamic-element channel, built once, serves both tests: (1) give ModelContext a per-test overlay registration API for elements parsed at test time — the parse already exists (com.legend.parser.ElementParser.parse(src, Dialect.LEGEND_ENGINE), used at EngineTestExecutor.java:828) — registering ALL PackageableElement kinds under their declared FQNs; (2) stop clgArm filtering to FunctionDefinition: index ->at(i) over the parsed elements in declaration order, keep today's behaviour for a FunctionDefinition, otherwise register the element and return `new PackageableElementPtr(<declared fqn>)`, which alone makes from() see a TypedPackageableRef; (3) add a narrowly-gated clgIdiom arm to Runner's let-bound helper gate (the helper's body contains a call whose simple name is compileLegendGrammar) so the helper beta-expands at all; (4) recognise `^$var(includes = ^MappingInclude(included = <ref>, owner = $var))` over a registered dynamic Mapping and re-register with the include appended. Only then does the real semantic surface become reachable — a NO-ARG milestoned property on a business-temporal source inside an M2M transform, whose date must come from the root .all($bdate) — and the association leg is a distinct resolver path from the owned-property leg, so one green does not imply the other.

**Leverage** — Two tests for one channel, but the channel is XL and steps 1-4 only get you to the START of the real work (step 5, the milestoned-property resolver surface, is unbounded and the corpus itself flags the feature as engine-only). Treat this as a platform capability decision, not a test fix: build it if a dynamic-element channel is wanted for its own sake, ledger it otherwise. The sibling testFlatten_ViaAllVersionsMapping is byte-identical except that it binds a static mapping element and it PASSES, which cleanly isolates the delta to the runtime-built mapping — that is the single most useful fact here and it argues the graphFetch/serialize/from machinery is already fine.

**Shares code with** — Step 3 edits Runner.expandHelperCalls (Runner.java:647-701), whose gate the code comment at :641-646 records as having cost 53 tests when previously widened — ANY other bucket proposing to widen let-expansion is proposing the same edit and must be merged with this cluster and gated together, not landed twice. Step 2 edits EngineTestExecutor.clgArm, shared with the graphFetch milestoning tests that pass FunctionDefinition payloads (testGraphFetchMilestoning.pure:645,684,723) — those currently work and must not regress.

---

### 168. No IsolationStrategy model: nested-filter isolation and forcedIsolation are both absent

**2 tests** · effort **XL** · confidence medium · bucket 04 (?) · verdicts: MISSING_FEATURE 1, REAL_DEFECT 1

Tests: `testIsolationOfMilestoningFiltersUsedOnIntermediateJoinInOR`, `testForcedIsolationFilterOnTop`

**Mechanism** — legend-engine picks an IsolationStrategy per filtered navigation (shouldIsolateNestedFilter -> manageIsolation, pureToSQLQuery.pure:5170-5176; forcedIsolation override at :7565-7567; MoveFilterOnTop at :7584-7588). legend-lite has exactly one hard-coded emission and no strategy concept at all: it never wraps a chained-join filter branch in a derived sub-select (so the milestoning test's two derived tables never appear), and it silently ignores the ^RelationalDebugContext(forcedIsolation=...) argument entirely — the 5th execute arg is typed Any, nothing reads forcedIsolation, and the harness drops it when rewriting execute into toSQLString.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:1755-1873 (materializeRoot, the seam where isolation emission belongs); core/src/main/java/com/legend/builtin/Pure.java:1537-1543 (debug arg typed Any); core/src/main/java/com/legend/harness/ExecCallFinder.java:133-144 (debug context discarded); riders: core/src/main/java/com/legend/resolver/Pipelines.java:428-431 (join kind hardcoded LEFT) and core/src/main/java/com/legend/sql/dialect/AnsiSqlRenderer.java:298-301 (Star not renamed)

**Fix** — Introduce the strategy at the resolver/lowering seam next to Pipelines.materialize / StoreResolver.materializeRoot: give execute a typed 5th parameter, read forcedIsolation, thread it into StoreResolver as an override, and implement manageIsolation (wrap a chained-join branch under a filter at chain depth <= 1 in a derived sub-select projecting the branch's read columns plus the milestoning columns, LEFT-OUTER-joined with the outer milestoning predicate re-applied on the exposed from_z/thru_z) alongside moveFiltersOnTop (keep the tree flat, hoist the saved filtering operation into the outer WHERE), reproducing the engine's degradation of MoveFilterOnTop to BuildCorrelatedSubQuery when the root has an inner join (pureToSQLQuery.pure:7581). Throw loudly for any strategy not implemented. Two cheap riders worth landing separately on their own merits: thread the mapping's per-hop JoinType through TypedJoinSlot (built at JoinChecker.java:376, populated from MappingFromProtocol.java:518-521) so Pipelines.java:428-431 stops emitting LEFT where the mapping says (INNER); and give AnsiSqlRenderer a sourceAlias() hook for the Star/StarExcept arms overridden in EngineStyleH2 to rename(a), which fixes the dangling `select t1.*` in the engine-style text.

**Leverage** — Poor as a pass target, honest recommendation is LEDGER both. Both tests assert SQL text only, and the milestoning one additionally needs the join-order change and would still be alias-adjacent. The two riders are the real value and should be unbundled: the INNER-hop threading is a documented row-count correctness gap (NavMaterializer.java:181-188 records 'JoinIsolationDeeper expected 4, got 11') and the Star rename is a small unambiguous renderer bug. Land the riders, ledger the strategy surface.

**Shares code with** — resolver/StoreResolver.java materializeRoot, resolver/Pipelines.java:428-431, sql/dialect/AnsiSqlRenderer.java:298. The Pipelines LEFT-vs-INNER hardcode will show up in any bucket with an over-permissive row count on a mapping chain declaring `> (INNER) @join` — merge those. The Star rename affects any engine-text golden projecting <alias>.*, named candidate testLiteralConditionsForcedIsolation.

---

### 169. Service DSL root unregistered + plan-handle execution and node vocabulary absent

**2 tests** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `testPureExecutionStrategyForRelationalInstantiationExecutionNode`, `testPureExecutionStrategyForCreateAndPopulateTempTableExecutionNode`

**Mechanism** — Both construct ^Service(...) from meta::legend::service::metamodel and call ->executeServiceTests(n). NewChecker.check throws `unknown class 'Service'` because Corpus assembles only the relational tree and the M2M test model, not core_service. Behind that shared wall both need a plan tree with real node counts, plan-handle execute with parameters, setUpData seeding, and reflective assert evaluation — none of which exist. The temp-table test is strictly harder: its 53- and 51-element IN lists require the engine's large-IN split into CreateAndPopulateTempTableExecutionNode plus siblings to reach 6 nodes, and PlanNode is a flat record with an ad-hoc kind string that never mints temp-table nodes.

**Owning code** — core/src/test/java/com/legend/rcorpus/Corpus.java (SERVICE_DSL root beside M2M_TESTS); compiler/spec/NewChecker.java; plan/PlanNode.java; plan/PlanText.java; exec/StatementExecutor.java plan builder; SeedSqlForms

**Fix** — Step 1 (legitimate model assembly, not harness compensation): add a SERVICE_DSL source root to Corpus.java pointing at legend-engine-xts-service/legend-engine-language-pure-dsl-service-pure/src/main/resources/core_service/service, included exactly as M2M_TESTS is. That converts the wall into the real one. Step 2: emit a RelationalInstantiationExecutionNode wrapper above SQLExecutionNode for a class-typed relational root so allNodes()->size() is 2, and add a $plan->execute(parameterValues, extensions) surface returning a Result. Step 3: wire setUpData through the existing SeedSqlForms/setUpDataSQLs reader plus executeInDb. The temp-table test additionally needs the large-IN strategy (processInOperation's large-IN branch). If Steps 2-3 are unfunded, ledger both — a root added alone just moves the wall one line and costs a build.

**Leverage** — Low. Two tests, but the shared change alone fixes neither, and a new source root widens the assembled model for every corpus test. Ledger; keep `unknown class 'Service'` loud.

**Shares code with** — The temp-table node vocabulary overlaps the CreateAndPopulateTempTable/FreeMarkerConditional forms also needed by the parameterized-lambda cluster (plan/PlanText.java + StatementExecutor plan builder) — build that vocabulary once.

---

### 170. compileLegendGrammar payload idiom: filter selector ignored, grammar elements discarded

**2 tests** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyConstraint`, `testCrossStoreGraphFetchWithRelationalDatePropagationForMilestonedPropertyZeroToOne`

**Mechanism** — Both bodies are `let elements = compileLegendGrammar($grammar); let function = $elements->filter(e|$e->instanceOf(FunctionDefinition))->at(1)->cast(...)`. EngineTestExecutor.clgArm's unwrap loop peels only cast/toOne/at and never sees the filter step, so idx stays 0 and clgArm returns the body of the FIRST grammar function — a helper declaring `collection: FirmOutput[*]` — wrapped as a ZERO-ARG lambda that discards the parameter. Typer then throws `unbound variable '$collection'`. Behind that: clgArm registers no grammar element into the ModelContext, ElqSplice.varPairs accepts only pair(...) not ^Pair(...), and the queries need getRuntimeWithModelQueryConnection / mergeRuntimes / ModelChainConnection XStore resolution, none of which exist anywhere in core/src.

**Owning code** — EngineTestExecutor.java:390, 796-838 (unwrap loop 800-812, wrap 836); ElqSplice.java:162-186; Typer.java:165

**Fix** — Staged. (1) XS, do anyway: make clgArm loud — when it reaches a compileLegendGrammar call, refuse to zero-arg-wrap a FunctionDefinition that declares parameters; return an unsupported("compileLegendGrammar: the grammar payload surface binds only zero-arg bodies") marker so the message names the real gap instead of 'unbound variable $collection'. (2) S: teach the unwrap loop the corpus idiom — accept filter(<src>, e|instanceOf(e, FunctionDefinition)) as identity over the FunctionDefinition list clgArm already builds, so ->at(1) reaches idx. (3) L: register all parsed elements into a SCOPED overlay ModelContext (not a global merge — payload names like test::model::Firm shadow) and keep the selection as a function handle with parameters, not a lambda. (4) M: extend ElqSplice.varPairs to accept ^Pair NewInstance. (5) XL: ledger the runtime-merge/date-propagation stack.

**Leverage** — Steps 1-4 are cheap and corpus-wide — the filter-selector idiom appears across modelToModelToRelational/milestoned payload tests. Step 5 gates actual passes and is an XL feature stack; expect a ledger, not a pass.

**Shares code with** — Steps 1-2 are harness-side (EngineTestExecutor.clgArm) but step 3 is a platform ModelContext overlay; any bucket whose tests use compileLegendGrammar shares this wall.

---

### 171. ScanRelations recognizes only tableToTDS sources — mapping-driven tds joins are unmodelled

**1 test** · effort **XL** · confidence medium · bucket 10 (harness SHAPE) · verdicts: MISSING_FEATURE 1

Tests: `testTdsJoinConcatenateAndJoin`

**Mechanism** — tableToTdsRoots routes on `containsCall(n, "join")` alone (ScanRelations.java:222), so a query with NO tableToTDS anywhere is still handed to the tableToTDS-only recognizer. Every source here is `testJoinTDS_Person.all()->project([col({p|$p.firstName},'First_1'), ...])`; collectTableToTds (lines 506-528) matches only a literal tableToTDS(tableReference(...)) and returns ZERO nodes, so the same found.size()!=1 wall fires — same message literal as the union cluster, different cause. Four further walls sit behind it and must all land together: the string-pair join form `join(right, LEFT_OUTER, 'First_1', 'First_2')` fails the condition-lambda guard at 306-311; a mid-spine `->extend(^BasicColumnSpecification...)` is unreachable because the wrapper peel at 232-239 runs once at the top and never inside the recursion; the concatenate-under-join gap (prerequisite from the union cluster); and the golden's [AGE, FIRSTNAME] demand is MAPPING-derived, so it needs buildRoots' mapping walk to expose a projectedAlias -> (table, physicalColumn) map that ScanRelations does not currently produce.

**Owning code** — core/src/main/java/com/legend/lineage/ScanRelations.java:222 (unconditional join routing), :408-472 (tableToTDS-only parseTdsSource base case), :506-528 (collectTableToTds), :306-311 (condition-lambda guard), :232-239 (top-only wrapper peel), :386-387 (insertion-ordinal child key); core/src/main/java/com/legend/harness/LineageRelationsForm.java:153,163 (stripAliasBreadcrumbs regex)

**Fix** — A real generalization, ledgered as its own work item: replace parseTdsSource's tableToTDS-only base with a kind-dispatching scanTdsSource that peels result-shaping wrappers (project/filter/extend/restrict/sort/groupBy/olapGroupBy/distinct/take) and dispatches — tableToTDS keeps today's path, `Class.all()` delegates to buildRoots with the mapping FQN, anything else walls loudly naming the base function; have it also return the projectedAlias -> [table, physicalColumn] map (buildRoots must optionally record, per leaf property, the (table,column) it landed on — walk/dispatchPms already computes this when it does node.cols.add). Accept the 5-parameter string-pair join form by reusing JoinChecker.columnNames' shape (JoinChecker.java:216-292) to synthesize the same alias pair the lambda form produces. Replace the insertion-ordinal child key with the engine's sort key table + '->' + cols + '->' + joinLabel (scanRelations.pure:562-564). Widen LineageRelationsForm.stripAliasBreadcrumbs from `_d(?:#\d+|y\d+)?` to `_d(?:#\d+|y\d+|\d+)?` so `_d0`/`_d1` node ids are fully consumed — worthless alone, must land with the tree work.

**Leverage** — Poor — the worst leverage in the bucket. XL effort, medium confidence, ONE test, and it carries the bucket's only genuine regression hazard: the sibling-ordering change (step 5) reorders children for EVERY tds-join tree, including four currently-passing goldens (testTableToTdsWithJoin, testTableToTdsWithMultipleJoin, testTableToTdsWithJoinToSameTable, testTableToTdsWithOLAPGroupBy), and testTableToTdsWithMultipleJoin's golden explicitly pins firmTable-before-locationTable. Recommend ledgering behind the other two ScanRelations clusters. If it is ever picked up, run the entry's falsifier first: rewrite the query's Class.all()->project sources as equivalent tableToTDS sources in a scratch .pure — if the existing recognizer then produces the right shape, only the class-mapped source and the string-pair join form are load-bearing and the estimate drops sharply.

**Shares code with** — Touches ScanRelations (shared with the two clusters above) AND the buildRoots mapping walk plus com.legend.harness.LineageRelationsForm.stripAliasBreadcrumbs. LineageRelationsForm's class comment (lines 28-32) records that treating unrecognized tds-join shapes as 'advisory' previously manufactured ~half this family's greens — if any other bucket proposes re-softening LineageRelationsForm, that proposal collides head-on with this cluster and must be rejected in favour of it.

---

### 172. Function-carrier blind spot in lambda arity filtering / deferred binding

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `executeProjectWithNestedDerivedProperty`

**Mechanism** — The function-CARRIER family (FunctionDefinition<F>/LambdaFunction<F>/Function<F>) is recognised in the shape prefilter (fixed by 787c391b at Typer.java:1727-1738) but not further in. HEAD's wall — a bare "no overload ... matches the argument types" — can only come from selectRankedByPresentArgs (:1773-1778) with a null arityRejection, i.e. every candidate dropped by lambdaAritiesFit (:1830-1864): extractFunctionType(FunctionDefinition<Any>) throws because the carrier's type argument is `Any`, not a FunctionType (:2027-2036), and the catch does `if (raw.get(i) instanceof LambdaFunction) return false;`. lambdaArityMismatch continues in the same catch and returns null (:1876-1882), hence the bare message. The same blind spot then recurs in typeLambda.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1830-1864,1876-1882,1567-1590,1722-1725,2027-2036

**Fix** — Both edits in Typer.java. (1) lambdaAritiesFit (:1830): in the catch(TypeInferenceException) block, before rejecting, exempt the carrier family — if isFunctionTyped(pt) (rawFqn in InferenceKernel.FUNCTION_CARRIER_FQNS) the lambda's arity is unconstrained by the signature, so `continue` instead of `return false`. (2) bindDeferredAndBuild (:1567-1590): widen the TypeVar self-typable arm (:1573-1583) to 'TypeVar OR function carrier with a non-FunctionType argument' — synth the lambda standalone and unify the carrier param against the synthesised LambdaFunction<{->T[m]}> rather than calling typeLambda, which re-enters extractFunctionType and throws. Guard with the existing selfTypable predicate (:1722-1725) so a bare `x|...` against a carrier still fails loudly. This unblocks TYPING only; the next wall will be the absent meta::pure::executionPlan::execute and meta::json::tdsToJSONKeyValueObjectString surfaces.

**Leverage** — The typer half is S and unblocks every corpus helper taking FunctionDefinition<Any>[1] called with a lambda. The test itself still needs an absent plan-execute surface, so it will not pass — fix the typer, ledger the rest.

**Shares code with** — Lives in compiler/spec/Typer.java overload resolution (lambdaAritiesFit, bindDeferredAndBuild) and needs new natives in builtin/Pure.java (meta::pure::executionPlan::execute, tdsToJSONKeyValueObjectString) — both files other buckets blame. Widening the arity filter risks the P1/P2 executionPlan overload discrimination noted at Typer.java:1875-1880; sweep required.

---

### 173. Mapping-less relation-plan route (runtime-only ->from)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testCrossDbPlanGenerationWithRelationFromWithOnlyRuntimes`

**Mechanism** — The query joins two relation-store accessors, each with `->from($runtimeN)` carrying a RUNTIME only and no mapping, under the two-arg executionPlan(f, extensions) overload. StatementExecutor.planToString requires a mapping FQN before it can do anything: args[1] is the relationalExtensions() call rather than a TypedPackageableRef, firstFromMapping is null (fr.mapping() is empty on a runtime-only from), firstFromChainMappings is empty, and it throws at :576-581. The real engine's two-arg entry routes with NO mapping and only stuffs a dummy ^Mapping(name='dummy') and empty ^Runtime() into the ExecutionPlan record for bookkeeping. This is an honest wall over a route that genuinely does not exist.

**Owning code** — core/src/main/java/com/legend/StatementExecutor.java:576-581,651-765,2829-2847; core/src/main/java/com/legend/plan/PlanText.java (pureTypeName)

**Fix** — Make the plan path mapping-optional, mirroring the engine's two-arg entry. In planToString: when no mapping is found AND the roots are relation-store accessors (#>{db.T}# / tableReference), carry mappingFqn = null, derive each cluster's store from its accessor and each cluster's CONNECTION from its own ->from($runtimeN) argument — connectionNameOf must be read PER from(), not once for the plan, since this golden prints RelationalDatabaseConnection(type = "H2") rather than the default TestDatabaseConnection. Route through the crossDbTdsPlan splitter (:651-765) but keyed on DIFFERENT RUNTIMES rather than stores: streamStoreOf (:2829-2847) reports the same store for both sides here, so it needs runtime-identity comparison. Finally, the TDS tuple line must spell precise primitives (meta::pure::precisePrimitives::Varchar), which PlanText.pureTypeName does not do today.

**Leverage** — Low. A new capability spanning routing, per-from connection resolution, runtime-keyed splitting, and precise-primitive spelling — for one test. Keep the wall loud until it lands.

**Shares code with** — Rewrites StatementExecutor.planToString's mapping precondition and generalizes crossDbTdsPlan — the same splitter the VarSetPlaceholder cluster edits — plus PlanText.pureTypeName. Sequence it after the placeholder cluster to avoid rework.

---

### 174. External-format subsystem absent (binding corpus root, externalize, ExternalizeTDS node)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testRelationalProjectionWithExternalFormat`

**Mechanism** — The test's first statement calls meta::external::format::shared::transformation::tests::exampleExternalFormatExtension(), defined in engine's core/pure/binding — a source root Corpus.java never loads (it exposes only core_relational/relational and core/store/m2m/tests). With no catalog entry and no shim, Typer.checkGeneric finds zero candidates and throws 'unknown function ... unported platform function'; planTextAssert stamps it as a plan wall. The wall is honest and structural: even with that function resolvable the test needs the whole external-format surface — externalize(TabularDataSet[1], String[1]), the ExternalFormatContract/Binding metamodel, and the ExternalFormat_ExternalizeTDS plan node and printer. A grep of legend-lite's whole main source finds zero occurrences of externalize, ExternalizeTDS, or external::format as a Legend concept.

**Owning code** — compiler/spec/Typer.java:1448-1451; Corpus.java (source roots); assert/PlanAsserts.java:198; builtin/Pure.java (no externalize native); plan/PlanText.java (no ExternalizeTDS form)

**Fix** — Do not chase now — this is a subsystem, not a bug. KEEP the wall; it is correctly loud and correctly attributed. If ever scheduled: (a) add core/pure/binding as a third Corpus source root next to M2M_TESTS so ExternalFormatContract/Binding/exampleExternalFormatExtension compile as user code, gated and swept separately since a large new element set will surface new compile walls in unrelated families; (b) port meta::external::format::shared::functions::externalize(TabularDataSet[1], String[1]):String[1] as a plan-time-only function (engine marks the graph-fetch variants NotImplementedFunction); (c) teach the plan channel to wrap the relational node in an ExternalFormat_ExternalizeTDS node carrying contentType and add its printer form to com.legend.plan.PlanText. Do NOT stub exampleExternalFormatExtension in the harness — the plan would be generated with a fake extension list and the golden would be wrong for a second reason.

**Leverage** — Low: a whole unbuilt subsystem for two corpus tests (this and testEnumPushDownWithExternalFormat). Ledger it; the wall already communicates the right thing.

**Shares code with** — Corpus.java source roots and plan/PlanText.java node vocabulary; the corpus-root change would need a joint sweep with any bucket blaming unported platform functions in Typer.checkGeneric.

---

### 175. Parameterized multi-statement lambda: let folded away, no PureExp/Allocation plan surface, IN collapsed to =

**1 test** · effort **XL** · confidence medium · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testMultiExpressionWithPlatformAndFromFunction`

**Mechanism** — Typer.typeLambda β-inlines the let of a parameterized multi-statement lambda (Typer.java:1908-1919), so StatementExecutor's Allocation loop has nothing to emit. The golden requires the let value to stay on the platform as a PureExp node, which legend-lite has no representation for — allocationNode can print only a Constant, a Class-envelope Relational or a scalar Relational, and a platform-only let value hits the wall at StatementExecutor.java:930-934. Independently, the inlined `in($names->map(toUpper))` lowers to a 2-arity Call(SqlFn.IN) and EngineStyleH2's arity-only singleton collapse rewrites it to `=` — a wrong-rows defect on its own.

**Owning code** — compiler/spec/Typer.java:1908-1919; exec/StatementExecutor.java:865-876, :905-975, :930-934; sql/dialect/EngineStyleH2.java:1156-1161; lowering/Scalars.java:2273-2287; sql/SqlExpr.java:249-256; plan/PlanText.java; builtin/Pure.java:1616

**Fix** — (1) Preserve the multi-statement body of parameterized lambdas for the plan surface instead of β-inlining. (2) Stop collapsing a 2-arity IN whose second operand is collection-valued to `=`; distinguish scalar-singleton from collection at lowering, using the meaning SqlExpr.Membership already carries. (3) Add the missing plan vocabulary (PureExp with `requires=[var(Type[mult])]` free-variable analysis and the `[Routed Func: ...]` routed-lambda source spelling, plus RelationalBlock / FreeMarkerConditional / CreateAndPopulateTempTable) and a fourth Allocation value form for a platform-only let. (4) Add the processInOperation-equivalent post-pass. If (3)/(4) are unfunded, make planToString throw a NAMED wall for this shape rather than silently printing a folded single-node plan.

**Leverage** — Part (2) is a standalone wrong-rows correctness bug and is cheap — do it regardless. Part (3) builds a printer surface for essentially one test; ledger behind a loud wall.

**Shares code with** — Shares causes (1),(2),(4) with testFilterInWithResultSorcedFromAnExpression and testExecutionPlanGenerationForMultipleInWithTwoCollectionInputs (executionPlanTest.pure:2350) in other buckets — merge those in. Touches compiler/spec/Typer.java, StatementExecutor.java and plan/PlanText.java.

---

### 176. Parameterized-lambda let inlining destroys plan Allocations (FilterIn plan shape)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: REAL_DEFECT 1

Tests: `testFilterInWithResultSorcedFromAnExpression`

**Mechanism** — Typer.typeLambda beta-inlines lets via SourceSubst.inlineLets for parameterized lambdas and hands a single-expression lambda to the plan printer, so StatementExecutor.planToString never takes the sequencePlan branch and no Allocation(name=z) is ever emitted — the got plan is only FPVN + Relational. On top of that the golden's PureExp / RelationalBlockExecutionNode / FreeMarkerConditional / CreateAndPopulateTempTable node vocabulary is entirely absent (PureExp exists only as a comment calling it a named wall).

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1908-1919; core/src/main/java/com/legend/StatementExecutor.java:603-610 and :905-975 (allocationNode); core/src/main/java/com/legend/plan/PlanText.java; core/src/main/java/com/legend/builtin/Pure.java:1616

**Fix** — Keep the folded lambda for the typing pass but preserve the original multi-statement TypedLambda on the produced spec (extra field, or type the statements as TypedLets the way the zero-parameter path does) so planToString still reaches sequencePlan. Extend allocationNode with a PureExp form: a let whose value has no getAll root and no literal form becomes PureExp(type, resultSizeRange, requires=[free vars with types], expression=<pure source>). Then land the IN post-processor cluster and the IN-collapse fix; this test needs all three. If the node vocabulary is not built, emit a loud wall ("IN over a collection plan variable needs the temp-table post-processor") instead of today's silent wrong SQL.

**Leverage** — Low on its own — one test, and it only passes once the IN post-processor and IN-collapse fixes also land. The inlineLets change is risky and load-bearing elsewhere.

**Shares code with** — Typer.java:1908-1919 SourceSubst.inlineLets is depended on by Typer:1281/1374, StaticFold:445, IfChecker:145 — any bucket blaming let inlining or folded lambda shape shares this code. Also depends on the processInOperation cluster.

---

### 177. from() rejects Runtime subtypes (exact-FQN compare)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testCrossStoreWithCSVDataSource`

**Mechanism** — Two stacked blockers. The reported one is a plain subtype bug: NewChecker types `^$var(...)` with the receiver's class type (EngineRuntime), and FromChecker's instance-runtime arm admits an argument only when ct.fqn() EXACTLY equals meta::core::runtime::Runtime — yet legend-lite itself declares EngineRuntime extends Runtime. So any ^EngineRuntime(...) or copy is rejected with 'from() argument 2 must be a mapping or runtime reference'. Behind it, TypedFrom.collectSqlSetups harvests only literal testDataSetupSqls and never converts testDataSetupCsv despite Ddl.setUpDataSqlsText already porting the generator; and the test's first assert white-box-reads the engine's ExecutionPlan object graph (StoreMappingGlobalGraphFetchExecutionNode / datasourceSpecification) to count DDL statements.

**Owning code** — FromChecker.java:53-58, 74-76; NewChecker.java:32-37, 63; TypedFrom.java:350-358; Ddl.java:106-165; StatementExecutor.java:1836-1843

**Fix** — (a) One line, worth doing independently: in FromChecker.check replace `ct.fqn().equals("meta::core::runtime::Runtime")` with `t.model().isSubtype(ct.fqn(), "meta::core::runtime::Runtime")` (Typer.model() at Typer.java:102, ModelContext.isSubtype at ModelContext.java:223). The rest of the arm already walks the instance generically. (b) Optional and independently valuable: give TypedFrom.collectSqlSetups a testDataSetupCsv arm that resolves the store Database off the enclosing ^ConnectionStore(element=...) and calls Ddl.setUpDataSqlsText — and do NOT seed when the element cannot be resolved to a Database. (c) Do not build: materialising the engine's plan protocol as a property-navigable object graph purely so a DDL statement count can be read off it. Ledger that assert.

**Leverage** — The XS part (a) is high value beyond this test — it unblocks testSpecialUnion_m2m2r and getNoStoreRuntime, which hit the identical wall. This test itself stays blocked on (c) and should be ledgered.

**Shares code with** — FromChecker/NewChecker typing of runtime arguments and StatementExecutor's plan-handle property walk; other buckets passing ^EngineRuntime into from() blame the same line.

---

### 178. Graph-fetch planToString renders a fake Relational node instead of walling

**1 test** · effort **XL** · confidence medium · bucket 2 (execution-plan) · verdicts: GOLDEN_TEXT_ONLY 1

Tests: `testMilestonedProperty`

**Mechanism** — The rows assert passes; the failure is the plan assert. planToString has no graph-fetch vocabulary at all — it unwraps the lambda, finds a getAll root, and falls through to PlanText.single, which emits a single `Relational(type=..., resultColumns=..., sql=...)` block. Nothing in com/legend/plan or StatementExecutor mentions graphFetch, serialize, PureExp, StoreMappingGlobalGraphFetch, RelationalGraphFetch, localTreeIndices or temp_table_node, and Pure.java states outright that plan text is meant to be a named wall at the K-arm. The defect is that the wall is NOT taken: a serialize/graphFetch-rooted query silently renders as a Relational node, turning an honest SHAPE into a FAIL on text legend-lite never claimed to emit.

**Owning code** — StatementExecutor.java:626-638; PlanText.java:78-81; StoreResolver.java:2581-2585 (graph-terminal test); PlanAsserts.java:186-189; Pure.java:1616-1617

**Fix** — (a) XS now: in planToString, before the PlanText.single return, detect a GRAPH terminal using the same predicate StoreResolver uses at StoreResolver.java:2581-2585 (terminal typed node is a serialize whose source is a TypedGraphFetch) and throw NotImplementedException("planToString: graph-fetch plans (PureExp/StoreMappingGlobalGraphFetch/RelationalGraphFetch nodes) are not rendered yet"). PlanAsserts.planTextAssert already catches NotImplementedException and scores SHAPE. Make the predicate exact — wall only when the terminal really is a graph serialize, not when a projection merely mentions serialize — and first check no currently-passing plan golden is a graph query matching the single Relational text. (b) Later, XL: the full engine node vocabulary (PureExp envelope, PartialClass impls, per-node SQL with ${temp_table_node_N} splices, localTreeIndices). Do not skip the assert in the harness.

**Leverage** — The XS part does not make anything pass — it reclassifies FAIL to SHAPE and stops misleading Relational text leaking into every graph-fetch plan golden. Honesty value, not score value.

**Shares code with** — Same planToString K-arm and PlanText.single entry point as the relation-root plan-text cluster; both edits land in StatementExecutor.java:611-638.

---

### 179. External-format binding corpus absent (enum push-down golden behind it)

**1 test** · effort **XL** · confidence high · bucket split-misc (?) · verdicts: 

Tests: `executionPlan/tests · testEnumPushDownWithExternalFormat`

**Mechanism** — The test's $extensions let calls meta::external::format::shared::transformation::tests::exampleExternalFormatExtension(), defined only in legend-engine-core (core/pure/binding/transformation/tests/externalFormatContract.pure:112), outside the two roots Corpus.java loads. Typer.checkGeneric finds zero candidates and throws, and PlanAsserts.planTextAssert converts that into the 'plan wall' SHAPE stamp. A second independent requirement sits behind it: the golden embeds the enum push-down CASE expression (case when "root".TYPE = 'CUSIP' ... else null end as "name") inside the externalized relational node, so even a complete external-format port leaves this failing unless PUSH_DOWN_ENUM_TRANSFORM is correct — the sibling testExecutionPlanGenerationForLambdaFromWithEnumMapping fails on exactly that CASE text with no external format involved.

**Owning code** — compiler/spec/Typer.java:1448 (checkGeneric zero-candidate throw); PlanAsserts.java:198 (plan wall SHAPE stamp); missing corpus root core/pure/binding/transformation/tests/externalFormatContract.pure:112; golden at executionPlanTest.pure (enum-mapping sibling at :2856)

**Fix** — Keep the wall. The external-format subsystem is genuinely absent, not broken. If it is ever scheduled, port the binding/transformation corpus root and the externalize plan nodes as one XL unit shared with testRelationalProjectionWithExternalFormat (executionPlanTest.pure:2647), and sequence it AFTER the enum push-down defect tracked by testExecutionPlanGenerationForLambdaFromWithEnumMapping — otherwise this test fails a second time on the pushed-down CASE expression. Do not attempt in isolation.

**Leverage** — Low. Two stacked prerequisites for one golden; the enum push-down half is worth fixing on its own via its non-external-format sibling, the external-format port is not.

**Shares code with** — Blames compiler/spec/Typer.java:1448 (unresolved-function throw) and the PlanAsserts wall — same code path other buckets hit for any missing-corpus symbol; fixing corpus roots there would flip several such stamps at once.

---

### 180. Correlated filtered sub-hop is left undemanded at sub-nav depth (no parent-copy subselect below depth 0)

**1 test** · effort **XL** · confidence high · bucket 7 (resolver (H)) · verdicts: MISSING_FEATURE 1

Tests: `isolationTest`

**Mechanism** — The column is `col(x|$x.employees.group.children->filter(c|$c.coveredProduct.name == $x.employees.product.name).name->toOne(),...)`. The filter reads the OUTER $x, so SyntheticHeads.parkFiltered puts it in the correlated pool and mints head `children#f0`, yielding path employees.group.children#f0.name. `employees` is a nav-slot head, so its SubNav tree comes from NavMaterializer's NavMat (which recurses, so depth is not the limit). The limit is demandSlotSubTail: when the tail's first segment has a correlated parked pred it returns EARLY and leaves the step undemanded, deliberately. The Organization NavMat's subNavs is therefore empty, SubNav('group').children() is empty, rewriteMultiHop cannot advance past hop 2, and the wall prints 'assocs=[employees]; head subNavs=[product, group]'.

**Owning code** — core/src/main/java/com/legend/resolver/NavMaterializer.java:454-461 (early return), :308-330 (subMats/subTree assembly), :316-321; resolver/StoreResolver.java:2517-2545 (depth-0 corr route), :2377-2387 (chained-hop wall); resolver/Substitution.java:1182-1186, :1342-1348

**Fix** — Route a correlated sub-hop the way depth-0 correlated heads already are. In demandSlotSubTail, replace the blanket `return` with a corrSub branch: record the sub-alias in a corrSubHeads map (alias -> head) alongside tNavs, and during materialisation build that step through CorrelatedSubselects — the parent-copy subselect that re-joins the parent extent with the navs the pred demands, applies the pred over the joined row, and joins back on parent-key equality (exactly StoreResolver.java:2517-2545's AssocJoin corrSubPred). Publish the resulting SubNav with the composed prefix into subTree so rewriteMultiHop's descent finds 'children#f0'. The outer correlation ($x.employees.product.name) must resolve against the PARENT copy inside the subselect — the engine's self-join-on-PK form. Fallback if parent-copy machinery cannot be reused at sub depth: keep the wall but move it, throwing at demandSlotSubTail naming 'correlated filtered navigation at sub-nav depth'.

**Leverage** — XL for one test; the cheap win is the fallback (move the wall so the message names the real site). Speculatively shared with testMultipleIsolationWithDifferentProp — unverified, do not bank on it.

**Shares code with** — NavMaterializer / StoreResolver correlated-subselect routing and the Substitution.java:1342-1348 multi-hop wall — any bucket reporting that wall text should be checked against this before opening a new cluster.

---

### 181. Identical mechanism to testRelationalProjectionWithExternalFormat and identical first wall: the `$extensions` let calls meta::external::format::shared

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testEnumPushDownWithExternalFormat`

**Mechanism** — Identical mechanism to testRelationalProjectionWithExternalFormat and identical first wall: the `$extensions` let calls meta::external::format::shared::transformation::tests::exampleExternalFormatExtension(), which is defined only in legend-engine-core (core/pure/binding/transformation/tests/externalFormatContract.pure:112), outside the two roots Corpus.java loads; Typer.checkGeneric finds zero candidates and throws (Typer.java:1448), and PlanAsserts.planTextAssert converts it to the 'plan wall' SHAPE stamp (PlanAsserts.java:198). Beyond that shared wall this test adds a SECOND, independent requirement: its golden asserts the enum push-down CASE expression `case when "root".TYPE = 'CUSIP' then 'CUSIP' ... else null end as "name"` inside the externalized relational node — i.e. the PUSH_DOWN_ENUM_TRANSFORM behaviour, not just the externalize envelope. So even a complete external-format port would not make this pass unless enum push-down is right (the sibling test testExecutionPlanGenerationForLambdaFromWithEnumMapping, executionPlanTest.pure:2856, fails on exactly that CASE text with no external format involved, which is evidence the push-down path is separately broken).

**Owning code** — /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Corpus.java:49 and :57 — the only two corpus source roots; core/pure/binding is not among them.; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:1448 — the zero-candidate throw producing the observed message.; /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/PlanAsserts.java:198 — plan-wall stamping for planToString goldens.

**Fix** — Same as testRelationalProjectionWithExternalFormat — keep the wall; the external-format subsystem is genuinely absent. If it is ever scheduled, sequence it AFTER the enum push-down defect (tracked by testExecutionPlanGenerationForLambdaFromWithEnumMapping), because this test's golden embeds the pushed-down CASE expression and would fail a second time otherwise. Do not fix in isolation.

**Leverage** — Singleton — folded in from its own diagnosis (clustering agent omitted it).

**Shares code with** — Shares the external-format prerequisite with testRelationalProjectionWithExternalFormat (executionPlanTest.pure:2647).

---

## Recommended to ledger — 28 clusters, 50 tests

The diagnosis concluded these should come off the denominator rather than onto a backlog.

### 1. Raw un-post-processed toSQLString alias breadcrumbs not reproduced

**9 tests** · effort **XL** · confidence medium · bucket 04 (?) · verdicts: GOLDEN_TEXT_ONLY 8, TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testGroupByWithJoinDB2`, `testDateFunctionInMilestonedProperty`, `testRestrictDistinct_NoOptimization_WindowColumns`, `testEqualityInFilterOnOptionalProperties`, `testEqualityInFilterOnOptionalPropertiesLegacy`, `testNotEqualityForOptionalProperties`, `testNotEqualityInFilterOnOptionalProperties`, `testNotEqualityInFilterOnOptionalPropertiesLegacy`, `testSqlGenerationDivide_AllDBs`

**Mechanism** — The 4-arg toSQLString(f, mapping, DatabaseType, extensions) overload passes an EMPTY post-processor list (toSQLString.pure:63/65), so replaceAliasName never runs and the golden keeps pureToSqlQuery's RAW alias = table name + router nodeId (createJoinTableAlias pureToSQLQuery.pure:8998-9003, nodeId scheme buildNodeId :3871-3896, e.g. personTable_d#6_d#3_m1_d_m2). legend-lite has no pre-reAlias alias layer: Lowerer mints t0/t1 and EngineStyleH2.render unconditionally applies its replaceAliasName-parity plan, emitting the POST-processed <lowercased table>_<n>. In these tests every operator/shape token already matches byte-for-byte (DB2 nullSafeEqual OR-expansion, 'is distinct from', 'is not distinct from', ((1.0 * %s) / %s), the retained window-restrict join); the alias is the whole diff.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:219-231 (render always calls planQuery), :326-346 and :394-397 (nextInGroup = lowercased table + '_' + i); core/src/main/java/com/legend/lowering/Lowerer.java:283 (nextAlias = t0/t1); core/src/main/java/com/legend/StatementExecutor.java:359-378 (nothing distinguishes the DatabaseType overload from the runtime one)

**Fix** — ONE ledger decision for the whole set: 'raw toSQLString aliases not reproduced' (113 golden literals across 14-15 corpus files per the diagnoses). If it is ever closed it must be closed as one change, never per test: (1) thread a boolean reAlias flag from StatementExecutor.toSqlString (= false when arg2 is a DatabaseType, true when it is a Runtime) into the renderer; (2) EngineStyleH2.render skips planQuery when false; (3) the H-phase stamps every join source with an engine-shaped nodeId reproducing buildNodeId's _d accumulation, #N run-length compression and _mN merge indices. Step (3) is the entire cost and it is a transcript of legend-engine's Pure recursion, not a semantic property. Never normalise aliases in the harness comparator.

**Leverage** — Large but worthless as a pass target. 9 of the 35 tests in this bucket, and none of them can be closed by anything cheaper. All 9 assert engine-internal alias spelling with zero row assertions; the rows are provably identical. Two members (testDateFunctionInMilestonedProperty, testSqlGenerationDivide_AllDBs) additionally carry a second text divergence (derived-table-vs-ON-clause milestoning predicate; unpruned root join tree for a scalar map root) so they would not go green on the alias channel alone — they belong here because the disposition, ledger-and-never-fix, is identical. The real value of this cluster is negative work avoided: it is the reason ~6 other clusters in this bucket cannot go green even after their real defect is fixed, so those clusters must be justified on their cross-corpus effect, not on this bucket's counts.

**Shares code with** — EngineStyleH2.java render/planQuery/nextInGroup and StatementExecutor.java:359-378 toSqlString. Any other bucket blaming a `_d#N`/`_mN` golden alias is the same ledger row — merge on sight. The diagnoses enumerate the affected corpus files: testSort.pure, testTDSRestrictDistinct.pure, testPureToSql.pure, testMergeRules.pure, testMilestoningContextPropagation.pure, testBusinessDateMilestoning.pure, testWithFunction.pure, testModelGroupBy.pure, testConcatenate.pure, testExists.pure, testFunctionVariables.pure, testQualifier.pure, testToSQLString.pure, scanRelationsTests*.pure.

---

### 2. LEDGER: M3 ValueSpecification reflection + engine's Pure SQL compiler (toSQLQuery / TdsSelectSqlQuery / Multiplicity singletons)

**5 tests** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 5

Tests: `simpleFunctionExpressionTranslationNow`, `simpleFunctionExpressionTranslationAdjust`, `testImportDataFlow`, `addDriverTablePkForProject`, `testFindFunctionSequenceMultiplicity`

**Mechanism** — All five call into legend-engine's Pure-implemented compiler and observe its output as a Pure metamodel object. Two absences stack for every one of them: `sqlQueryToString` drops on `SQLQuery[1]` and `pureToSqlQuery::toSQLQuery` drops independently on `ValueSpecification[1]` (recorded global drop, docs/RELATIONAL_CORPUS.md:705) — legend-lite has no M3 valuespecification metamodel, no reflective expression tree behind deactivate(), no Multiplicity as a packageable INSTANCE (ZeroMany/PureOne are m3 bootstrap graph values, and Typer.classReference knows only class/enum/database/mapping/runtime/function), and no RelationalTds/paths on TdsSelectSqlQuery. Two of them additionally need `meta::pure::router::routeFunction`, from a tree RelationalCorpusRunner never loads. Notably the SEMANTICS two of these tests are about already work in legend-lite: addDriverTablePkForProject is implemented (validation/DriverPkAppend.java, wired at StatementExecutor.java:342) and the adjust->dateadd dialect mapping lives in sql/dialect — only the Pure API through which the tests observe them is missing.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:426/451 (no metamodel::SQLQuery base, TdsSelectSqlQuery has no RelationalTds/paths), :528 (LambdaFunction empty, no expressionSequence), :1509 (only evaluateAndDeactivate), :1565-1573 (the platform's own note: 'SimpleFunctionExpressions — reflection metamodel this platform lacks'); core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 and FunctionCompiler.java:119-121 (the drop-and-rethrow); core/src/main/java/com/legend/compiler/spec/Typer.java:2203-2294 (classReference, the ZeroMany miss); core/src/test/java/com/legend/rcorpus/RelationalCorpusRunner.java:129-177 (core/pure/router not loaded); core/src/main/java/com/legend/validation/DriverPkAppend.java:23-31 (the feature that DOES work)

**Fix** — DO NOT FIX — one ledger entry: 'engine self-metamodel: reifying M3 ValueSpecification + the relational SQL metamodel so legend-engine's Pure compiler can be run and its intermediate objects asserted'. Record for each row WHY it can never be a feature gap: the assert is over the engine's node structure, its alias generator's spelling, or its printer. SPLIT OUT one genuine sub-entry: `importDataFlow`/`importDataFlowAddFks`/`importDataFlowFksByTable` is a real unimplemented execution-context option (zero grep hits in core/src/main/java) whose natural home is beside validation/DriverPkAppend.java as an ImportDataFlowOption over the same TypedProject node — but it must be asserted by an ordinary execute(|...)/golden-SQL corpus test, never through toSQLQuery, and must not be built speculatively off this test's engine-alias-spelled golden.

**Leverage** — Zero as tests; non-zero as ledger information. Do not let the SQLQuery half of the wall make these look adjacent to the cheap sqlstring-surface cluster — landing native SQLQuery moves these rows' wall to ValueSpecification/routeFunction and they stay red, so they must NOT be counted as beneficiaries of that change. Two specific traps: adding a bare `paths` property to TdsSelectSqlQuery with nothing populating it would make assertEquals(2, ...->size()) fail misleadingly or pass by coincidence; and teaching Typer.classReference to return an Any[1] sentinel for unresolved bare names (to make `pair('employees', ZeroMany)` type) would turn every genuine unported-element error in the whole corpus into a silent meaningless comparison.

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:426/451/528 and compiler/spec/Typer.java:2294 (classReference) — any bucket proposing to weaken Typer.java:2294 or to add empty native classes just to unblock signatures must be merged here and refused. The `Unknown type: 'ValueSpecification'` global drop (docs/RELATIONAL_CORPUS.md:705) is the shared marker; the importDataFlow sub-entry belongs beside com/legend/validation/DriverPkAppend.java in whatever bucket owns execution-context options.

---

### 3. LEDGER: engine's Pure router + extension registry (routeFunction, StoreContract, router printer)

**4 tests** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 4

Tests: `testPlatformExpressionDependencyOnAFromExpression`, `testPlatformExpressionDependencyOnAFromExpression2`, `testRoutingOfSimpleQualifiedProperty`, `testRoutingContextBuilderFunctions`

**Mechanism** — All four drive legend-engine's Pure-implemented router. Three call `meta::pure::router::routeFunction` (4-arg router_main.pure:35 and 6-arg deprecated.pure:24), which is not in legend-lite's catalogue at all — Typer.functionCandidates is empty and the throw carries the `(no candidates at all)` suffix; two of them then assert EXACT STRING EQUALITY against `meta::pure::router::printer::asString()` of the routed tree (`{Platform> [strategy_wrapper ...]`, `[Routed Func:...]`, set-implementation ids), and the third downcasts through ClusteredValueSpecification / StoreMappingRoutedValueSpecification. The fourth walls one layer earlier — `relationalExtensions()` is deliberately typed `Any[*]` as a never-evaluated typing placeholder and `Extension` is declared property-free — but its subject is the same plug-in mechanism: it copy-updates a StoreContract with a custom routeFunctionExpressions pair and asserts the golden planToString produced by routing through it.

**Owning code** — core/src/main/java/com/legend/compiler/spec/Typer.java:1512 (the `no overload ... (no candidates at all)` throw for routeFunction) and :2566 (the `has no property 'type'` throw for $e.type); core/src/main/java/com/legend/builtin/Pure.java:247 (EXTENSION declared property-free) and :1578-1582 (RELATIONAL_EXTENSIONS returns Any[*], documented as never evaluated); core/src/main/java/com/legend/harness/PlanAsserts.java:188-198 and EngineTestExecutor.java:878-894 (the SHAPE/unsupported scoring wrapper); core/src/main/java/com/legend/resolver/ (26 Java files, no routed-ValueSpecification metamodel); docs/NOT_IMPLEMENTABLE.md:81 (the existing routeFunction entry to extend)

**Fix** — DO NOT FIX. One ledger entry covering all four, extending docs/NOT_IMPLEMENTABLE.md:81: 'engine Pure router + extension registry — tests assert the router's own metamodel print format / node taxonomy / custom StoreContract dispatch; no observable result exists in legend-lite, which routes in Java (resolver/, phase H)'. Explicitly register NOTHING: do not add a routeFunction signature to Pure.java, do not add ClusteredValueSpecification/StoreMappingRoutedValueSpecification/byPassRouterInfo as classes to satisfy casts, do not widen relationalExtensions()'s Any[*] return or add properties to Extension piecemeal, and do not teach Runner/EngineTestExecutor/PlanAsserts to recognise these call shapes.

**Leverage** — Zero — this is the archetype of the large-but-worthless cluster. Four tests, and every one asserts the shape of legend-engine's internal routing nodes or their debug print, not any observable query behaviour. The traps are the point of the entry: registering a no-op routeFunction native would convert an honest ERROR into a wrong-answer FAIL and shadow the signal for every other test reaching the router; widening relationalExtensions()'s return type would break the corpus-wide typing of every execute(..., relationalExtensions()) call, which currently RELIES on the loose Any[*]. Note testRoutingOfSimpleQualifiedProperty is labelled SHAPE only because its body has no execute/from mapping ref — that label is correct behaviour and must not be 'fixed' by making the try-run path swallow the wall.

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:247 and :1578-1582 (Extension / relationalExtensions typing) — any bucket proposing to widen those must be merged here, because the Any[*] placeholder is load-bearing for the whole corpus's execute/toSQLString typing. Also Typer.java:1512: any other bucket whose failures read `no overload of 'routeFunction'` belongs in this ledger entry, not in its own.

---

### 4. Enumeration projection trio — unsorted Product/Product_Synonym scan order (ledger, no code change)

**3 tests** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: EXECUTION_TARGET_ARTIFACT 3

Tests: `testProjectWithIfWhereBothSidesUseTheSameEnumMapping`, `testProjectWithIfWhereOneSideIsEnumLiteral`, `testProjectionWithEnumThroughAssociation`

**Mechanism** — All three project over the same `Product LEFT OUTER JOIN Product_Synonym` with the same seeds and no ORDER BY, then assert `rows->at(1)` by position. legend-lite's SQL byte-matches legend-engine's own plan golden (asserted by the passing `tdsWithEnumReturn`). H2 returns the three rows product-major (11,13,12); DuckDB returns them synonym-major (11,12,13), so index 1 differs while the multiset is identical on both. Typer, resolver, lowering, enum decode and enum-literal encoding are all correct — the `==` encodes CUSIP to source value 'CUS' via the EnumerationMapping, and the CASE/if literal branch fires correctly. The `disabled_optimizers` pin was probed here and yields a THIRD order, so it is not even a workaround.

**Owning code** — docs/NOT_IMPLEMENTABLE.md:32-33 (existing 'Enumeration projection trio' entry); docs/burndown-2026-08-14/master-classification.csv:193 (misbucketed as WRONG ROWS)

**Fix** — No platform, harness, or SQL change. Keep the existing docs/NOT_IMPLEMENTABLE.md 'Enumeration projection trio' entry and strengthen its evidence line with the reproducible duckdb probe command instead of the prose claim. Correct docs/burndown-2026-08-14/master-classification.csv:193 (and the CORPUS_BURNDOWN_INDEX note claiming 'the enum mapping is applied from the wrong side') from bucket 6 WRONG ROWS to the order-artifact class — CUSIP vs GS_NUMBER is a different ROW, not a different decode. Explicitly rejected: injecting an ORDER BY (fabricates semantics and breaks the passing byte-exact plan golden), DuckDB session tuning (probed, produces a third order), name-keyed FAIL→known-artifact downgrades in the runner. The only honest route to green is running this family under `-Drcorpus.backend=h2` and reporting it as a portability result.

**Leverage** — Zero engineering value — three tests that can never be green on DuckDB. Worth doing only to stop the misbucketed 'wrong enum decode' note luring someone into inverting a correct decode.

**Shares code with** — Touches only docs/NOT_IMPLEMENTABLE.md and the master-classification CSV; if other buckets ledger order artifacts, merge the CSV corrections into one pass.

---

### 5. legend-lite-only acos/asin/sqrt domain guards (test rows unreachable on DuckDB regardless)

**2 tests** · effort **XS** · confidence high · bucket 05 (?) · verdicts: EXECUTION_TARGET_ARTIFACT 2

Tests: `testFilterUsingArcCosFunction`, `testFilterUsingArcSinFunction`

**Mechanism** — Two stacked causes. OUTER: legend-lite does not lower acos/asin bare — Scalars.java:1578-1590 loops over both names and wraps each in a Pure-runtime domain guard `CASE WHEN abs(cast(x as double)) > 1 THEN error('Unable to compute <fn> of ' || floatRepr(x)) ELSE <fn>(x) END` (guarded at Scalars.java:2862; SqlFn.ERROR spells DuckDB `error` at Spellings.java:68, which prints with the 'Invalid Input Error: ' prefix). Trade id 11 gives 1.1, the guard fires, the statement aborts. That guard was added deliberately for PCT error parity (PCT_BURNDOWN.md:470, Slice 13) and its message is copied from the Pure IN-MEMORY runtime (CoreHelper.java:131/146) — a path legend-engine's relational push-down never takes. INNER: removing the guard does NOT make the tests pass. The asserts (`assertSameElements([9,10], ...)` / `[1,2,3,4]`) require trade 11 to be SILENTLY EXCLUDED, which depends on H2/Java Math.acos returning NaN (NaN < 0.5 is false). DuckDB 1.5.0.0 raises instead — the literals 'ACOS is undefined outside [-1,1]' / 'ASIN is undefined outside [-1,1]' are present in the shipped native library. So deleting the guard only swaps one error for another.

**Owning code** — core/src/main/java/com/legend/lowering/Scalars.java:1569-1590 (the sqrt guard at :1569-1577 and the acos/asin loop at :1578-1590, both built by `guarded` at Scalars.java:2862); renderer at core/src/main/java/com/legend/sql/dialect/Spellings.java:68; PCT pin at docs/PCT_BURNDOWN.md:470

**Fix** — Do not chase these tests green — ledger both as execution-target artifacts. Separately and on its own merits (NOT for these tests), delete the acos/asin/sqrt domain guards at Scalars.java:1569-1590 so they lower to bare `SqlExpr.Call.of(SqlFn.ACOS/ASIN/SQRT, args.get(0))`, matching legend-engine's `acos(%s)`/`asin(%s)` (extensionDefaults.pure:187/190, duckDBSqlDialect.pure:434-435) and the corpus golden text `acos((1.0 * "root".ID) / 10)`. In the SAME commit, re-pin testArcCosineError/testArcSineError/testSquareRootError as expected PCT failures mirroring relational-duckdb/EssentialFunctions_manifest.json, or the 1109/1109 gate reads the 3-test drop as a regression. Do NOT rewrite the guard to yield NaN (`CASE WHEN abs(x)>1 THEN 'nan'::DOUBLE ELSE acos(x) END`) — it would turn both tests green but invents a DuckDB emulation of H2's libm that legend-engine has nowhere; if the project ever decides its row reference is H2 rather than the live target, that belongs in DuckDb.java as an explicit documented IEEE-domain shim, never as a lowering rule.

**Leverage** — ZERO tests unblocked — this is the clearest 'large-looking but worthless' cluster in the bucket. Two tests, one XS edit, and the edit provably does not make either pass (the inner DuckDB wall was verified against the shipped native library). The edit is still worth making for engine parity and golden-text alignment, but it must be justified on parity grounds and it COSTS 3 PCT tests plus a gate re-pin, so its net ledger effect is negative in test count. Budget it as cleanup, not burndown; the two tests belong in the out-of-scope ledger.

**Shares code with** — Touches lowering/Scalars.java:1569-1590 + :2862 (the `guarded` CASE/ERROR idiom) and sql/dialect/Spellings.java:68. If another bucket has a test failing with 'Invalid Input Error: Unable to compute <fn> of <x>', it is this same guard family and merges here — including the sqrt guard, which currently bites nothing in this unit. Separately: if any bucket proposes a DuckDb.java dialect-level IEEE/NaN or H2-semantics shim, that is the decision point this cluster defers, and the two should be decided together.

---

### 6. Asserts on legend-engine's TDS→Relation protocol-AST JSON (no legend-lite counterpart)

**2 tests** · effort **XS** · confidence high · bucket 09 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 2

Tests: `testJoinUsing`, `testJoinFunc`

**Mechanism** — Neither test executes a query. meta::pure::tds::toRelation::test serializes both lambdas to legend-engine's vX_X_X protocol JSON and compares the STRINGS (assertLambdaJSONEquals = assertEquals($expected->functionJSON(), $actual->meta::json::toJSON(100))), rewriting the actual side through the TdsToRelationExtension_V_X_X registry. That registry, the transformer, the test helper and the TestClass model element all live in legend-engine-core, outside the corpus root (Corpus.java:49). So Runner finds no execute call, falls to tryRunNoExecute (Runner.java:1307), and the first thing the typer touches — the bare name TestClass — throws at Typer.java:2294. The TestClass wall is a corpus-scope symptom; the assert underneath is over engine compiler internals.

**Owning code** — No legend-lite code should change. Ledger at docs/RELATIONAL_CORPUS_ALL.md:1374 with the reason 'asserts engine protocol-AST JSON'. Emitting sites for the observed text: core/src/main/java/com/legend/compiler/spec/Typer.java:2294-2297 and core/src/test/java/com/legend/rcorpus/Runner.java:1307-1312.

**Fix** — Do not fix — record the exclusion for both tests in one ledger entry. Making them green would require porting legend-engine's vX_X_X protocol value-specification metamodel, transformLambda (Pure-graph -> protocol AST), meta::json::toJSON and the TdsToRelationExtension_V_X_X transfer registry. If the SEMANTIC content is ever wanted (that TDS join-using means the same as Relation join-with-lambda), write it as a rows-level test on real data, not protocol-JSON string equality.

**Leverage** — Zero engineering value, positive ledger value: two tests off the non-passing list for the cost of one documentation entry. The trap to name explicitly is that the M2M_TESTS precedent (Corpus.java:57) makes it LOOK sanctioned to load engine-core's core/pure/tds/relation/*.pure — doing so would only move the wall from TestClass to transform/transformLambda and add a foreign family to every module assembly.

**Shares code with** — Shares the emitting site Typer.java:2294 ("'X' is not a known class, mapping, runtime, connection, or database") with the mangled-id cluster and with any bucket's corpus-scope walls. Do NOT merge on that message alone — here it is a scope symptom to ledger, there it is a real resolution bug to fix. Any bucket proposing to widen Corpus.java's roots should be reconciled against this exclusion.

---

### 7. Graph-fetch execution-node vocabulary (resolver arm + plan printer)

**2 tests** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `planGraphFetchWithDerivedProperty`, `planGraphFetchWithNestedDerivedProperty`

**Mechanism** — Byte-identical tests (same query and golden, m2m2rExecutionPlanTests.pure:37 and :167). Two stacked absences. Resolver: the terminal is a BARE graphFetch with no ->serialize; StoreResolver.resolveNode has a TypedSerialize arm (:408) but none for TypedGraphFetch, so it hits the named default at :508 ("class query under TypedGraphFetch is not resolvable yet"); collectOpChain only unwraps a TypedGraphFetch under a TypedSerialize (:2581-2586). Printer, the real wall: com/legend/plan has no graph-fetch node vocabulary at all — StoreMappingGlobalGraphFetch exists only as a native declaration for reflective asserts (builtin/Pure.java:514). The golden needs a model-store StoreMappingGlobalGraphFetch wrapping InMemoryRootGraphFetch wrapping a relational StoreMappingGlobalGraphFetch with RelationalGraphFetch/SQL leaves.

**Owning code** — core/src/main/java/com/legend/resolver/StoreResolver.java:408,508,2581-2586; core/src/main/java/com/legend/plan/ (no graph-fetch node types); core/src/main/java/com/legend/builtin/Pure.java:514

**Fix** — Ledger, do not fix now. If picked up: (1) small resolver addition — `case TypedGraphFetch gf when anchored(gf.source()) -> resolveChain(gf, context)` beside StoreResolver.java:408, and extend collectOpChain's terminal detection at :2581 to unwrap a bare TypedGraphFetch (tree, checked envelope, source) as its own graph terminal without the PureExp/serialize envelope. (2) The large part — a new plan/GraphFetchPlanText emitting StoreMappingGlobalGraphFetch / InMemoryRootGraphFetch / RelationalGraphFetch / SQL nodes with PartialClass[impls=..., propertiesWithParameters=[...]] type spelling, graphFetchTree rendering, and nodeIndex/batchSize/checked/localTreeIndices/dependencyIndices, driven by the store split of the graph tree. Do (1) alone only to move the wall to where the work is.

**Leverage** — Low near-term: a subsystem, not a patch. The resolver half is XS but converts a loud wall into a risk of a silently wrong single-node Relational plan. Value is the four other graph-fetch goldens it would unlock.

**Shares code with** — Adds a printer alongside plan/PlanText.java and touches resolver/StoreResolver.resolveNode/collectOpChain, which other buckets blame. Any bucket adding resolveNode arms should land its case in the same switch.

---

### 8. Relational graph-fetch plan node family absent (H2 temp-table strategy)

**2 tests** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 2

Tests: `testGraphFetchH2TempTableStrategy`, `testGraphFetchH2TempTableStrategyWithQuoteIdentifiers`

**Mechanism** — Both the typed surface and the generator are missing. GlobalGraphFetchExecutionNode and StoreMappingGlobalGraphFetchExecutionNode are registered as EMPTY native classes, so the typer walls at `.localGraphFetchExecutionNode`; and planModel only ever builds Sequence / FPVN / RelationalInstantiation / SQLExecutionNode — no GraphFetch anywhere in com/legend/plan. The test additionally needs RelationalRootQueryTempTableGraphFetchExecutionNode with processedTempTableName and a LoadFromTempFileTempTableStrategy whose three child Sequences carry CREATE / INSERT-CSVREAD / DROP DDL. The quoteIdentifiers sibling differs only by routing the temp-table and column names through the connection's identifier processor.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:513-514; core/src/main/java/com/legend/compiler/spec/Typer.java:2564-2566 (wall); core/src/main/java/com/legend/StatementExecutor.java:1941-2026 (planModel) and :1971-1976 / :1000-1026 (quote flag); com/legend/plan/PlanNode.java

**Fix** — Ledger as one feature covering both tests. If built: give the graph-fetch classes their real members (localGraphFetchExecutionNode, children, graphFetchTree, localTreeIndices, dependencyIndices) and add natives for RelationalTempTable / ClassQueryTempTable / RootQueryTempTable graph-fetch nodes and TempTableStrategy; extend PlanNode with kinds beyond the current four plus NAMED child slots rather than one positional children list; add a planModel arm that for graphFetch(tree)->serialize(tree) over a single relational store emits Global -> StoreMapping -> RelationalRootQueryTempTable with processedTempTableName = tempTableName(0) run through the identifier processor (the existing quote flag already reaches the plan dialect — that is the whole quoteIdentifiers delta) and a LoadFromTempFile strategy wrapping the CREATE/INSERT/DROP DDL. Do NOT declare the node properties without the generator: that turns a clear typer wall into a silent empty read.

**Leverage** — Two tests for one feature, but it is a whole absent subsystem — ledger unless graph-fetch is otherwise on the roadmap. The quoteIdentifiers sibling is free once built.

**Shares code with** — builtin/Pure.java:513-514 native class registry and Typer.java:2564-2566 property wall — merge with any other bucket blaming empty native graph-fetch/serialize classes.

---

### 9. XStore self-association filter-in-project goldens encode an engine JoinTreeNode merge defect (WONTFIX)

**2 tests** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 2

Tests: `testSimpleMappingQueryWithFilterInProject`, `testMixedMappingWithFilterInProject`

**Mechanism** — Identical query and byte-identical golden across two different mappings (Relation+Relation vs Relation+Relational), so the divergence rides on the shared XStore Person_Firm self-association, not the class-mapping kind. The goldens are reproducible ONLY by binding the inner `age < 35` predicate to the OUTER root person — i.e. the filter leaks into the employees LEFT JOIN's ON clause. legend-lite's rows are the semantically correct isolated-filter result. legend-engine names the mechanism itself in testMergeRules.pure:52-53 (unisolated JoinTreeNodes keep their JoinName and merge, leaking filter criteria), on a function carrying `test.ToFix`; testClassMappingFilterWithInnerJoin.pure:82-85 shows the intended isolated SQL.

**Owning code** — No legend-lite code should change. Evidence: testMergeRules.pure:52-53; testClassMappingFilterWithInnerJoin.pure:82-85 (upstream corpus)

**Fix** — Do not fix. Ledger both as one scoreboard exception. Matching the golden would require replicating legend-engine's JoinTreeNode merge alias collision in the resolver's association-navigation emission — deliberately failing to isolate a filtered to-many navigation when both association ends resolve to the same mapped relation, and rebinding the predicate's row variable to the outer extent. That is an invented defect and must be rejected under the tenets. Equally rejected: loosening the harness row comparison. Leave the wall loud so the divergence stays visible.

**Leverage** — Two tests closed for zero code. Value is purely scoreboard hygiene — the tests assert legend-engine's own acknowledged bug, so 'fixing' them would be a net regression.

**Shares code with** — None — self-contained ledger decision, no shared code touched.

---

### 10. LEDGER: arbitrary Pure post-processor transform over a reified SQL metamodel

**1 test** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testPostProcessTransformJoinOp`

**Mechanism** — The runtime installs `sqlQueryPostProcessors = [{query | $query->postprocess({rel | $rel->match([t:TableAliasColumn[1] | if($t.column.type->instanceOf(Integer), |^Literal(value=2), |$t), ...])})}]` — an arbitrary user lambda pattern-matching over legend-engine's internal SQL metamodel node graph, applied recursively by engine's Pure transform. legend-lite deliberately does not reify its SQL IR as Pure metamodel instances: SqlPostProcessors recognizes exactly ONE hook shape (replaceTables) and throws loudly otherwise. The reported SQLQuery wall is a decoy fired while typing the LAST statement; the asserted golden `on (2 = 2 and ...)` is a statement about the engine's node structure (that a join condition is a BinaryOperation over two TableAliasColumns), not about observable query behaviour.

**Owning code** — core/src/main/java/com/legend/lowering/SqlPostProcessors.java:23-31 (the architectural boundary stated in code), :61 (plain sqlQueryPostProcessors routes to the same recognizer), :64-73 (the loudness that caught the false-green cteExtraction cluster), :96 (the honest wall this test SHOULD hit); core/src/main/java/com/legend/builtin/Pure.java:350/283 (TableAliasColumn and Literal ARE native, which is why the decoy names SQLQuery); docs/RELATIONAL_CORPUS.md:1265-1271 (the 7 siblings already at that wall)

**Fix** — DO NOT FIX. Ledger it in the SAME bucket as the 7 postprocessor rows already sitting at SqlPostProcessors.readHook ('arbitrary Pure post-processor transform over the reified SQL metamodel — not modelled'), noting this one is a different hook shape from their cteExtraction. The only action is diagnostic honesty: once the sqlstring surface lands, this row stops walling on `Unknown type: SQLQuery` and starts walling at SqlPostProcessors.java:96 with 'hook shape is not a replaceTables lambda' — the TRUE reason. If it is ever built it belongs in a new lowering/MetamodelTransform pass reached from readHook, never in the harness.

**Leverage** — Zero, but the entry earns its place by protecting the recognizer. The tempting shortcut — special-casing THIS lambda (integer TableAliasColumn -> literal 2) the way replaceTables is recognized — would be pattern-matching a single test body and would destroy the property that already caught a false-green cluster. Consolidation value: this row should be ledgered together with the 7 existing readHook ERROR rows as one bucket, which is the only 'leverage' available here.

**Shares code with** — Touches core/src/main/java/com/legend/lowering/SqlPostProcessors.java:61/96 — the 7 named sibling tests (testComplexSubQueries, testCorrelatedSubQueryIsolationStrategy, testDeepSubQueries, testMultipleSubQueries, testNoSubQueries, testSingleSubQueryFromOperations, testSingleSubQueryFromView) live in other rows/buckets at the identical readHook wall and should be merged into one ledger bucket with this. Also depends on Pure.java:426 (SQLQuery) only to make its wall honest.

---

### 11. LEDGER: engine's Pure join-tree merge/re-alias algorithm (+ honest-wall fix: register join::Join)

**1 test** · effort **XS** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testReAliasMergedJoinOperations`

**Mechanism** — Two layers. Shallow and LYING: `meta::relational::metamodel::join::Join` is absent from legend-lite's catalogue (its siblings RelationalTreeNode/RootJoinTreeNode/JoinTreeNode/TableAlias are all ported, and JOIN_TREE_NODE.join is degraded to Any[0..1] — the visible scar), so NameResolver's wildcard tier finds no candidate, falls through to PRELUDE_TYPES, and bare `Join` silently binds to the unrelated `meta::external::query::sql::metamodel::Join`; NewChecker then reports 'class ... has no property name' about the WRONG class. Because exactly one prelude class owns the simple name, the PRELUDE_COLLISIONS tie-break never fires. Deep and terminal: the test white-boxes engine's Pure SQL compiler (buildAndTransformJoinMetaData / reAliasMergedJoinOperations) and asserts on JoinTreeMetaData.joinAliases/jtnAliases/missingJoinAliases — internal bookkeeping of the engine's join-tree MERGE, requiring metamodel::children, gatherAllOperations, reprocessJoin, findNode, reprocessAliases, copy-with-update over native classes and higher-order eval, none of which legend-lite has.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:436 (JOIN_TREE_NODE.join typed Any[0..1]), :433-434 (the ported siblings), :336 (SQLN_JOIN, the wrong winner); core/src/main/java/com/legend/compiler/NameResolver.java:532-591 (resolveNameMulti tiers), :268-281 (preludeCollisions removeIf size<2), :283-291 (knownFqns); core/src/main/java/com/legend/compiler/spec/NewChecker.java:83-94 (the emitting throw); core/src/test/java/com/legend/rcorpus/Corpus.java:52 (corpus root excludes legend-pure)

**Fix** — Do NOT try to pass it — ledger as engine-internals (legend-lite merges and re-aliases join trees in Java; reimplementing the Pure version to assert its intermediate bookkeeping means a second parallel compiler with no user-visible behaviour behind it). DO make the wall honest with one edit: register `native Class meta::relational::metamodel::join::Join` (name/database:Any/target:TableAlias/aliases:Pair<TableAlias,TableAlias>/operation:RelationalOperationElement) after JOIN_TREE_NODE in Pure.java, so the FQN enters knownFqns, tier-1 wildcards claim bare `Join`, and `Join` becomes a two-entry PRELUDE_COLLISIONS key. Sweep for bare `Join` under a sql-metamodel import before landing. Optionally retype JOIN_TREE_NODE.join to Join[1]. Then the test walls honestly on `children`/`gatherAllOperations`/eval-over-Any.

**Leverage** — The test is worthless; the XS edit is not. This is a live silent cross-namespace name capture — every corpus file naming bare `Join` under `import meta::relational::metamodel::join::*` currently mis-binds to the SQL-AST Join, and the diagnosis names testDataGeneration.pure:461/547/979/1076 (which construct ^Join(name=..., operation=...)) plus pureToSQLQuery.pure and milestoning.pure as candidates. Worth landing purely as a correctness/diagnosis-honesty fix, counted as zero tests. Do NOT then chase the deep layer by stubbing children()/gatherAllOperations as Java natives — that is building the engine's Pure compiler a piece at a time with a test as the spec.

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:436 and the NameResolver prelude-tier machinery at NameResolver.java:532-591/268-281 — the SAME resolver tier that the datatype clusters need a primitive-first arm in. Land both NameResolver changes together. Any bucket with a mysterious 'class ... has no property ...' on a name that also exists under meta::external::query::sql::metamodel:: is likely this capture.

---

### 12. LEDGER: engine's Pure milestoning rewriter over the SQL metamodel (operation:: classes)

**1 test** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testMilestoningFilterApplicationOnSemiStructuredRelationalOperationElements`

**Mechanism** — The module pulls milestoning/milestoning.pure, whose applyMilestoningFilters signature names `Operation[0..1]`; ModelIntegrity.checkFunction classifies every parameter type eagerly at module construction, NameResolver passes the unresolvable bare `Operation` through unchanged (documented 0-matches rule), and TypeClassifier throws. The prelude declares exactly one class under metamodel::operation:: (JoinStrings, and even that is reparented off Operation). But the wall is only the first of many: the test's real subject is a ~55-arm match over engine's SQL metamodel that rebuilds SelectSQLQuery/JoinTreeNode/DynaFunction trees with ^$x(...) copies and dispatches through RelationalExtension.milestoning_applyFilterHandlers. legend-lite applies milestoning filters in Java inside its own resolver/lowering. The test carries NO assert — it passes iff evaluating that engine function six times does not throw.

**Owning code** — core/src/main/java/com/legend/compiler/element/TypeClassifier.java:85-92 (the throw) and :52-54 (isClassFqn); core/src/main/java/com/legend/compiler/NameResolver.java:106-108 (the 0-matches pass-through rule); core/src/main/java/com/legend/builtin/Pure.java:444 (JoinStrings, the only operation:: class, reparented off Operation); core/src/main/java/com/legend/compiler/element/ModelIntegrity.java:130-134 (eager whole-model parameter classification)

**Fix** — Do not fix; ledger. Declaring the metamodel::operation::{Function, Operation, BinaryOperation, UnaryOperation, VariableArityOperation, ArithmeticOperation, VariableArithmeticOperation, SemiStructuredObjectNavigation, SemiStructuredPropertyAccess, SemiStructuredArrayElementAccess} and relation::{SemiStructuredArrayFlatten, SemiStructuredArrayFlattenOutput} classes mirroring relational.pure:186-357 is cheap and correct and WILL move the wall — but if done, record this test as still-walled at the applyMilestoningFilters BODY rather than counting the declaration as progress. Do NOT reparent JoinStrings from RelationalOperationElement to Operation as a drive-by (GENERIC_RELATIONAL_KINDS at StatementExecutor.java:1614-1626 lists it as a generic node and every cast/instanceOf site relies on the flat chain).

**Leverage** — Zero for this test, and NEGATIVE for the sweep if done carelessly: adding the operation:: classes makes SemiStructuredPropertyAccess etc. newly resolve as bare names in every corpus file importing metamodel::operation::* (postProcessor.pure, trimColumnNamePostProcessor.pure, pushFiltersDownToJoin.pure), pulling those files past MODEL integrity into deeper noisier walls — the SHAPE denominator shifts with no test passing. The missing Operation name is also stated to poison pureToSQLQuery.pure:6786, pureToSQLQuery_deprecated.pure:621 and ~16 sites in milestoning.pure, so if the classes are added it should be a deliberate, separately-measured manifest change, not a side effect of chasing this row.

**Shares code with** — Touches core/src/main/java/com/legend/builtin/Pure.java:444 (operation:: block) and compiler/element/ModelIntegrity.java:130-134. Any bucket whose failures read `Unknown type: 'Operation'` or name SemiStructured* classes shares this manifest gap and must be merged before anyone lands the class block, because the sweep-delta is the whole decision.

---

### 13. LEDGER: vX_X_X protocol metamodel + relational-to-Pure autogeneration

**1 test** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testClassesAssociationsAndMappingFromDatabase`

**Mechanism** — The body constructs `meta::protocols::pure::vX_X_X::metamodel::PureModelContextData` and NewChecker throws unknown class. legend-lite has a JAVA-side protocol model (Protocol.PureModelContextData, emitted by ProtocolEmitter) but carries no Pure-level vX_X_X metamodel and no fromPureGraph transformers, and cannot demand-pull them: the class lives outside the relational corpus tree in legend-engine-core, while the Runner registers only core_relational/relational plus core/store/m2m/tests, so the FQN never enters elementSource. The test's subject is itself an engine Pure code generator that builds protocol elements and serialises them via alloyToJSON, with the assert a JSON string equality against engine's own protocol serialisation.

**Owning code** — core/src/main/java/com/legend/compiler/spec/NewChecker.java:68 (the throw); core/src/main/java/com/legend/protocol/Protocol.java:34 and ProtocolEmitter.java:44 (legend-lite's Java-side protocol model); core/src/test/java/com/legend/rcorpus/Corpus.java:48-60 (registered source roots) and Runner.java:1191-1193 (demand-pull requires elementSource membership); core/src/main/java/com/legend/builtin/Pure.java (no meta::protocols:: declaration anywhere)

**Fix** — Do not fix; ledger. Passing requires porting engine's entire vX_X_X protocol metamodel (hundreds of Pure classes under core/pure/protocol/vX_X_X/models/* plus metamodel_relational.pure), the fromPureGraph transformers for Class/Association/Mapping, the alloyToJSON serialiser and the whole meta::relational::transform::autogen::* generator, then matching engine's JSON byte-for-byte. Record that if a relational-to-Pure autogeneration surface is ever wanted it belongs as a JAVA generator over com.legend.model.DatabaseDefinition emitting Protocol.PureModelContextData through the existing ProtocolEmitter — and that this corpus test still would not exercise it, because the test's EXPECTED side is itself built from engine Pure transformers.

**Leverage** — Zero, and unusually clean-cut: this is a parallel implementation of engine's protocol layer, not a defect in legend-lite's query pipeline. The only thing worth carrying forward from the row is the design note about where a Java autogeneration surface would live if the capability is ever wanted.

---

### 14. LEDGER: engine's Pure SQL generator record (DbExtension/DbConfig interpretation)

**1 test** · effort **XL** · confidence high · bucket 01 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testProcessIdentifierWithQuoteChar`

**Mechanism** — Typing `^DbConfig(..., dbExtension=createDbExtensionForH2())` forces compilation of corpus class DbExtension, whose property names bare `CoreDataType` under `import meta::relational::metamodel::datatype::*`; that class is in neither the corpus nor the prelude, so NameResolver returns the name unchanged and TypeClassifier throws `Unknown type: 'CoreDataType'`. That is only proximate. The test's subject is engine's Pure identifier processor reached through a fully-evaluated ^DbExtension record built from 20 function-pointer fields plus a Map-backed dyna-function dispatcher — legend-lite has no interpreter for that (HostEval is explicitly scoped to orchestration-value evaluation), and its own identifier quoting is a plain boolean flag with no reserved-word/space/FreeMarker handling.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:351 (only datatype::DataType); core/src/main/java/com/legend/compiler/NameResolver.java:571-592 (prelude tier falls through to List.of(name)) and :532-593 (where the primitive-first tier goes); core/src/main/java/com/legend/compiler/element/TypeClassifier.java:91-92 (the throw); core/src/main/java/com/legend/exec/HostEval.java:36-44 (scoped channel); core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:211-213 (phys(): quote-or-not, no reserved-word rule)

**Fix** — Do NOT try to pass it — ledger. Passing means interpreting createDbExtensionForH2 end to end (20 function-pointer fields, a Map-backed dyna dispatcher, ~1300 lines of dbExtension.pure plus 860 of extensionDefaults.pure), an interpreter legend-lite deliberately does not have. DO make the wall honest as part of the shared datatype work: register CoreDataType + DbSpecificDataType{coreDataType, dbSpecificSql} + the concrete subclasses in Pure.java beside :351, WITH the mandatory NameResolver primitive-first tier ahead of the wildcard and own-package tiers. After that the wall moves to the DbExtension record's evaluation — the honest message.

**Leverage** — Zero as a test; its only value is that its honest-wall change is the SAME datatype-prelude + NameResolver primitive-tier sub-change three other clusters need — so it costs nothing extra once that lands, and it stops 'Unknown type: CoreDataType' from reading as a two-line manifest gap it is not. The trap to record: registering colliding datatype names WITHOUT the primitive tier re-points every bare Integer[0..1]/Boolean[1] in dbExtension.pure at the wrong class, and do NOT harness-intercept processIdentifierWithBackTicks/WithDoubleQuotes — identifier quoting is platform-owned in sql/dialect and PlatformTypes.java:151-155 records such an arm having been removed once already.

**Shares code with** — Shares core/src/main/java/com/legend/builtin/Pure.java:351 (datatype block) and core/src/main/java/com/legend/compiler/NameResolver.java:532-593 (primitive-first tier) with the temp-table, testTempTableSqlStatementsForH2 and testTranslateDbType clusters — one change, four clusters, land it once. The primitive tier independently fixes latent mis-resolution in protocols/pure/v1_*/models/metamodel_relational.pure:207/223, which other buckets may be seeing as unexplained mistyping.

---

### 15. Router IR printer assert — stale wall, then engine-internals

**1 test** · effort **XS** · confidence medium · bucket 09 (?) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testCompositionInMultiStatementPureExpressions`

**Mechanism** — The recorded wall is STALE: the isFunctionTyped rejection of FunctionDefinition<Any> was fixed on 2026-08-15 by commit 787c391b, which added the FUNCTION_CARRIER_FQNS clause (Typer.java:1727-1738, InferenceKernel.java:1180-1185), so the call now type-checks. What the test actually asserts is legend-engine's ROUTER printer output: routeInternal wraps meta::pure::router::routeFunction and the assertion compares $routingResult->map(f|$f->asString()) against the literal '{Platform> [strategy_wrapper /let y = …]}'. Neither routeFunction nor meta::pure::router::printer::asString exists in legend-lite; the sibling router tests already fail with 'no overload of routeFunction … (no candidates at all)', which is the real wall this test will show once re-swept.

**Owning code** — No fix. Re-sweep to refresh the stale wall, then ledger alongside testPlatformExpressionDependencyOnAFromExpression, testPlatformExpressionDependencyOnAFromExpression2 and testRoutingOfSimpleQualifiedProperty. Reference points: core/src/main/java/com/legend/compiler/spec/Typer.java:1727-1738 (the landed fix) and docs/RELATIONAL_CORPUS_ALL.md:1363-1367 (siblings on routeFunction).

**Fix** — Do not fix; adjudicate BLOCKED-ON-ENGINE-INTERNALS. Passing it would require reproducing legend-engine's router IR node-for-node AND its printer's exact text — a white-box assertion on a Pure-implemented compiler stage legend-lite does not have. If a router surface is ever wanted, routeFunction and printer::asString would go in builtin/Pure.java plus a new router package, but that is a platform decision, not a bug fix.

**Leverage** — No engineering value, but ONE actionable point that matters beyond this test: the landed 787c391b fix means the recorded walls for this test — and possibly for other tests classified off the 2026-08-14 burndown CSV — are stale. Re-sweep before anyone sizes work against those messages; some adjacent clusters may already be cheaper or already fixed. That is the real payoff of this entry.

**Shares code with** — Touches Typer.isFunctionTyped / InferenceKernel.FUNCTION_CARRIER_FQNS (Typer.java:1727-1738, InferenceKernel.java:1180-1185) — ALREADY FIXED at HEAD. Any bucket still citing 'deferredShapesMatch filtered out the only candidate' or 'matches N argument(s) of these shapes' for a FunctionDefinition<Any> parameter is quoting a stale wall from the 2026-08-14 sweep and must be re-swept before clustering.

---

### 16. H2Test asserts legend-engine's forked H2 jar is on the classpath

**1 test** · effort **L** · confidence high · bucket 10 (harness SHAPE) · verdicts: EXECUTION_TARGET_ARTIFACT 1

Tests: `H2Test`

**Mechanism** — Not a semantics test — the corpus comment says so outright ('this is testing that legend-h2 is actually being used rather than vanilla h2', testIn.pure:181). It runs raw SQL `SELECT case when false = 'false' then 'Ok' else 'Error' END` and expects 'Ok'. legend-engine ships a PATCHED H2 2.1.214 in which org.h2.value.TypeInfo.areComparable was edited to allow BOOLEAN<->VARCHAR comparison (TypeInfo.java:1011, @legend-fix; the fork's own unit test TestBooleanComparison.java:26 asserts exactly this query). legend-lite routes the statement to STOCK org.h2: HostEval's executeInDb READ path calls DbMetaData.query(sql, replayStream()) (HostEval.java:376-381), which opens a fresh jdbc:h2:mem:execquery<n> connection (DbMetaData.java:92-93) against Class.forName("org.h2.Driver") (H2Verify.java:52-58), raising 90110. MODE=LEGACY does not help — the fork's change is in areComparable, which is mode-independent.

**Owning code** — core/src/main/java/com/legend/exec/HostEval.java:376-381 (the READ route to the H2 replay target); core/src/main/java/com/legend/exec/DbMetaData.java:92-93 (fresh stock-H2 connection); core/src/main/java/com/legend/harness/H2Verify.java:52-58,152-160 (stock driver, engine-mirrored settings)

**Fix** — RECOMMENDED: do not fix — ledger it as an execution-target artifact with the TypeInfo.java:1011 citation. legend-lite's primary target is DuckDB and its second is stock H2; neither is legend-h2, and vendoring a patched org.h2 into a clean-room rewrite buys exactly this one test. IF it must go green, the only defensible change is to stop routing executeInDb READS to a different database than the writes went to: at HostEval.java:376-381, execute the raw statement on the SESSION's own connection (the DuckDB workspace the setup already populated) and keep the H2 replay only as the fallback for metadata natives that genuinely need H2's column naming. Never rewrite the SQL text at the boundary and never special-case this statement.

**Leverage** — Zero to negative. The alternative fix is broad and very likely a net regression: the H2 read route exists specifically for engine-parity column naming, so moving executeInDb reads to DuckDB changes result-set column names for every other executeInDb-reading test across the mutation, metadata and sqlQueryToString families. One test gained, an unknown number lost. Run the one-line falsifier before spending anything — execute the CASE expression directly on DuckDB; if DuckDB also rejects the BOOLEAN/VARCHAR comparison, even the alternative fix fails and 'do not fix' is the only answer.

**Shares code with** — HostEval.java:376-381 / StatementExecutor's executeInDb boundary is shared with the testExecuteInDbToTDS cluster, which wants executeInDb to return a materialised ResultSet rather than Scalar(null). If that cluster is scheduled, revisit this one at the same time — a single coherent decision about which target executeInDb reads from, and what it returns, covers both. Do not make the two decisions independently.

---

### 17. resolveSchemaTest requires an in-memory Pure TDS evaluator and a static schema algebra

**1 test** · effort **XL** · confidence high · bucket 10 (harness SHAPE) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `resolveSchemaTest`

**Mechanism** — The corpus helper beta-expands on the try-run path into meta::pure::tds::schema::tests::assertSchemaRoundTripEquality/2. The statement loop routes any AppliedFunction whose simple name startsWith('assert') and whose FQN startsWith('meta::') into checkAssert (EngineTestExecutor.java:499-501, harnessVocabName at :1798-1799), which has no arm for it, so `default -> UNSUPPORTED_MARKER` fires and the row reads as a harness gap. That message is a misattribution: the assert's real contract is `$query->eval().columns == meta::pure::tds::schema::resolveSchema($query, $extensions)` — it compares the Pure INTERPRETER's runtime TDS columns against legend-engine's Pure-implemented static schema inference over the SchemaState algebra (tdsSchema.pure:17-71). legend-lite implements neither side: no in-memory Pure evaluator for Class.all()->project/join with no mapping or store, and no resolveSchema surface.

**Owning code** — core/src/main/java/com/legend/harness/EngineTestExecutor.java:2043-2045 (checkAssert default), :1798-1799 (harnessVocabName hijacking any meta:: assert*), :892-895 (the message literal)

**Fix** — DO NOT FIX the feature — ledger it. Make only the MESSAGE honest: before the `default -> UNSUPPORTED_MARKER` arm in checkAssert, detect a meta::-qualified assert* that is not in the harness's known-assert set but IS resolvable via ctx.findFunctionDefinition, and report `corpus assert helper '<fqn>' has no harness arm; it needs <named pure surface>` so the row stops reading as a harness gap. Any change here must improve the message and NEVER the verdict — a generic assert* fallback folding unknown asserts to PASS would hollow-pass this and every other corpus assert helper. If the feature were ever wanted it is two independent subsystems: an in-memory Pure TDS evaluator over object-graph instances with no store (legend-lite has no object-graph instances at all), and a static schema resolver equivalent to resolveSchema.

**Leverage** — None as a green — this is the bucket's clearest 'asserts engine internals' case, and the honest action is a ledger entry plus a better message (XS of the XL). The one thing worth harvesting is that better message: it applies to ANY corpus test whose assert helper lives under meta:: with no harness arm, so it improves triage accuracy for future sweeps at near-zero cost. Falsifier worth thirty seconds before ledgering: grep core/src/main for any evaluator that materialises Class instances without a relational mapping — if one exists, the blocking half is not blocking and the verdict weakens toward MISSING FEATURE.

**Shares code with** — harnessVocabName (EngineTestExecutor.java:1798-1799) returns true for ANY meta::-qualified name, so every corpus helper named assert* anywhere in the corpus is hijacked into checkAssert. If other buckets show 'assert form X/N is not supported yet' for helpers that are really corpus functions rather than platform asserts, they share this misrouting and should adopt the same message fix rather than each adding a checkAssert arm.

---

### 18. No graph-tree VALUE type — computed trees (calculateSourceTree) cannot be consumed

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testSupportStreamFlagWithGraphFetchAndFrom`

**Mechanism** — A #{Class{...}}# tree is only ever a SYNTACTIC argument in legend-lite. GraphFetchChecker.checkTree requires the second parameter, after unwrapping the literal carrier/cast/quoted-tree forms, to be a ColSpecArray and otherwise throws 'graphFetch expects (classCollection, #{Class{…}}#)'. This test passes ->graphFetch($sourceGraph) where $sourceGraph = meta::pure::graphFetch::calculateSourceTree(...) — a COMPUTED tree. Two gaps: calculateSourceTree is not implemented anywhere (zero grep hits over core/src/main), and even a stub could not be consumed because the checker's contract is syntactic. The wall fires at the first hop. The test additionally needs cross-store M2M chaining through getRuntimeWithModelConnection and the 2-arg executionPlan plan walk.

**Owning code** — compiler/spec/GraphFetchChecker.java:82-87, :233-252 (unwrapCompiledTree)

**Fix** — Ledger behind a real feature; do not point-patch. The prerequisite is a graph-tree VALUE type in the compiler: reify TypedGraphTree/TypedGraphFetch's tree as a value a variable can hold (a new TypedSpec carrier plus an ExprType for RootGraphFetchTree<C>); teach GraphFetchChecker.checkTree to accept, in addition to a syntactic ColSpecArray, an expression evaluating to such a carrier, CONSTANT-FOLDED at compile time since the tree must be statically known to drive lowering; only then implement meta::pure::graphFetch::calculateSourceTree as tree algebra over the M2M mapping (root ~src class, per-property source-tree enrichment, subtree merge + sort). Acceptance must be gated on successful folding — letting a non-static tree reach lowering would trade a loud wall for wrong output, since the serialize key and fetch plan both assume a known tree. Do not use this test as the driving case for the tree-value work.

**Leverage** — Low for this test alone (it also needs 2-arg executionPlan and cross-store M2M). The tree-value work is real but should be driven by a simpler case; testObjectReferenceIn.pure:379 uses the same idiom.

**Shares code with** — GraphFetchChecker.checkTree is the single gate for every computed-tree failure; also depends on the 2-arg executionPlan argument reader in StatementExecutor (see the testSupportStreamFlagFromSimple entry, already fixed upstream).

---

### 19. SQLExecutionNode.connection absent from catalog and unthreaded; protocol transformPlan surface missing

**1 test** · effort **L** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testPlanWithLocalH2ConnectionWithSQL`

**Mechanism** — Two independent gaps. (i) `->cast(@SQLExecutionNode).connection` walls in Typer.java:2564-2566 because the native catalog entry declares only sqlQuery + sqlComment, and even with the property declared there is no value channel: PlanNode carries no connection, planModel never threads one in, and walkProp has no arm for it nor for reading properties off a constructed HostInstance. (ii) The second assert calls the vX_X_X protocol transformPlan — a 370-line Pure transformation outside the loaded corpus root that dispatches through per-extension serializer contributions. legend-lite has no protocol-transformation surface at all, so a perfect fix for (i) still leaves the test walled. That wall is honest.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:516; compiler/spec/Typer.java:2564-2566; plan/PlanNode.java:20-21; exec/StatementExecutor.java:1941-2026, :2012, :1118, :1817-1853; exec/HostEval.java:207-213, :548-556

**Fix** — Recommendation: LEDGER as a named missing surface (protocol plan transformation). If moving the wall honestly to the second assert is wanted, the bounded part is: (1) add `connection: DatabaseConnection[1]` to the SQLExecutionNode catalog entry, citing executionPlan.pure:69 per the catalog citation contract; (2) add a nullable connection component to PlanNode holding a HostEval.HostInstance; (3) in planModel, after computing rtArg2, evaluate connectionInstanceOf(rtArg2) through HostEval and thread it into the SQLExecutionNode at :2012; (4) add `case "connection"` to walkProp's PlanNode arm plus a HostInstance arm so .datasourceSpecification/.testDataSetupSqls read through. Do not let the harness synthesize a connection or short-circuit the second assert.

**Leverage** — Low. Step (1) is cheap but changes typing globally: testDatabaseConnectionSQLPopulation(Legacy) move from ERROR to FAIL, not PASS. The test cannot pass without a protocol metamodel.

**Shares code with** — Touches builtin/Pure.java native catalog and StatementExecutor.walkProp; the transformPlan gap is the same 'protocol / external roots' surface other buckets ledger.

---

### 20. M3 function-metamodel surgery on a hand-built FunctionDefinition

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testPreprocessFunctionOnRuntime`

**Mechanism** — The test reads $lambda.expressionSequence, builds a ^SimpleFunctionExpression, and constructs ^FunctionDefinition<{->Any[1]}>(expressionSequence = ...) before handing that to executionPlan. legend-lite registers FunctionDefinition as an OPAQUE native carrier with no declared properties (Pure.java:526), so the constructor key fails property lookup and NewChecker.check throws (NewChecker.java:94 — the quoted-class-name format, not the Typer's). Underneath, the feature the test is really about — EngineRuntime.preprocessFunction evaluated during plan generation, the corpus's addLimit rewriting a select SFE — does not exist at all: preprocessFunction/addLimit have zero hits in core/src/main/java.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:522-528, :526, :1567; compiler/spec/NewChecker.java:94

**Fix** — Do not fix; ledger. Passing requires an M3 ValueSpecification metamodel (ValueSpecification / SimpleFunctionExpression / InstanceValue / FunctionDefinition.expressionSequence / GenericType with construction, match-dispatch and property reads) plus the ability to re-enter the compiler on a reflectively constructed function body — the exact surface Pure.java:522-528 and :1567 already declare out of scope. If the preprocess hook is ever wanted independently, the honest slice is registering `preprocessFunction: Function<Any>[0..1]` on the EngineRuntime native class and applying a recognized preprocess shape structurally to the typed body before lowering — but that would not make THIS test pass, since its input is a hand-built FunctionDefinition. Never add expressionSequence to the opaque carrier to silence the message.

**Leverage** — Zero — the test asserts legend-engine's own M3 internals. The property-free carrier is load-bearing; keep the loud wall and ledger.

**Shares code with** — Shares the opaque-M3-carrier wall class with other M3-surgery tests (concatenateTemporalTdsQueries-style bodies, testRoutingContextBuilderFunctions) — merge those into one ledger entry at builtin/Pure.java:522-528.

---

### 21. Positional row assert over an unordered TDS query (H2 scan order encoded)

**1 test** · effort **XS** · confidence medium · bucket 6 (wrong rows) · verdicts: EXECUTION_TARGET_ARTIFACT 1

Tests: `testSequenceMapWithConfusingSetImplementation`

**Mechanism** — The query has no ORDER BY, yet the test asserts rows positionally at indices 0..5. The expectation encodes H2's incidental scan order (orgTable insertion order). Our row content is byte-identical to expected rows — the six rows are a permutation, and the row-count assert passes. ROOT is the one row whose parentId (-1) has no join partner, and DuckDB's hash join flushes NULL-extended unmatched probe rows last, so it lands at the end instead of the front. The harness's documented multiset leniency for unsorted chains cannot apply because `rows->at(i)` names one specific row.

**Owning code** — No owning code — expectation is corpus-side; harness order policy at EngineTestExecutor.java:2836-2848 is deliberately not extended

**Fix** — Do not fix; ledger as 'positional row assert over an unordered query, H2-order-encoded'. There is no honest platform change: Pure's relational execute makes no row-order promise, and adding a synthetic ORDER BY would invent a clause the plan does not contain and diverge from the engine golden SQL this test also pins. The tenet-2 trap is the tempting harness fix — sorting or multiset-matching a `rows->at(i)` assert — which would silently grant order-insensitivity to every positional assert in the corpus, including the ones where a sort IS in the chain and order is contractual. Revisit only if a deterministic-order execution mode (single-threaded / preserve_insertion_order / merge join) is adopted wholesale. Run the falsifier before ledgering, so a genuine wrong-rows bug is not misfiled.

**Leverage** — Zero engineering value; the value is the classification. Misfiling it as a defect would burn L-effort chasing H2's join flush order.

**Shares code with** — Any bucket proposing a blanket harness order policy touches EngineTestExecutor.compare's order policy — this cluster is the argument against widening it.

---

### 22. graphFetch union member order (three-leg special_union)

**1 test** · effort **S** · confidence low · bucket 6 (wrong rows) · verdicts: EXECUTION_TARGET_ARTIFACT 1

Tests: `test6`

**Mechanism** — Only the ORDER of the top-level firm array differs; per-firm contents match element-for-element. Mapping6 declares special_union(firm_set1, firm_set2, firm_set3) = Firm X, Firm A, Firm B, and legend-lite deliberately serializes in branch declaration order (UnionSerialOrder injects a negated per-branch ordinal into the UNION ALL). The corpus expects Firm B, Firm X, Firm A — derivable from nothing in the model: not declaration order, not firm ids, not legalName, and not any ORDER BY over the union pk columns. The engine's own router returns members in declaration order, so B,X,A is an assembly/H2 accident captured into the golden. The file's two-leg tests expect declaration order and already pass.

**Owning code** — UnionSerialOrder (do not change); optional narrow policy at EngineTestExecutor.compare graphFetch root

**Fix** — Do not change UnionSerialOrder — declaration order is the principled contract, matches the engine's router, and is what this file's own two-leg tests pin. Ledger as an execution-target ordering artifact. If a green result is genuinely required, the only honest lever is a harness order policy scoped to graphFetch ROOTS over a union with no sort in the chain: compare the top-level JSON array as a multiset of objects while every nested array stays order-sensitive — parallel to the existing documented TDS order policy. It must not extend to nested arrays or to non-union graphFetch roots, or a blanket multiset comparison would hide real graphFetch ordering defects across the family.

**Leverage** — No correctness value. Confidence is low that B,X,A is truly underivable — worth one more look before ledgering, since a real routing-order rule would be a family-wide finding.

**Shares code with** — Shares the EngineTestExecutor order-policy surface with the unordered-TDS cluster, but the levers are disjoint (JSON graphFetch root vs TDS positional row assert) — do not merge them into one policy change.

---

### 23. assertRoundTrip tests legend-engine's own preeval + protocol serializer

**1 test** · effort **XS** · confidence high · bucket 6 (wrong rows) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testPrerouting42`

**Mechanism** — assertRoundTrip is not an assert about query results. Its 5-arg body runs `$input->preval(...)` to get a rewritten FunctionDefinition, then compares `transformFunctionBody($extensions)->toJSON(50000)->parseJSON()->toPrettyJSONString()` for both the prevalled result and the expected lambda — i.e. legend-engine's preeval AST rewrite serialized through legend-engine's vX_X_X protocol serializer, byte-for-byte. legend-lite models preval as identity for row semantics, has no preeval AST pass and no protocol-JSON round trip, so there is nothing to compare. The harness dispatch has no assertRoundTrip case and scoreAssert emits the bare Unsupported marker — an honest, correctly attributed wall.

**Owning code** — EngineTestExecutor.java scoreAssert / assert dispatch (classification only); no platform code should change

**Fix** — DO NOT FIX — ledger it. An assertRoundTrip arm would require reimplementing meta::pure::router::preeval::preval as a faithful AST→AST rewrite AND meta::protocols::pure::vX_X_X::transformation::fromPureGraph + toJSON so the serialized text matches byte-for-byte. Both are engine compiler internals with no observable row semantics, and a partial implementation would produce a passing test that proves nothing. Record it in the engine-internals ledger so it stops counting as a fixable SHAPE. The only defensible change is classification: have scoreAssert recognise a small set of known engine-internals assert names and emit a distinct outcome ('engine-internals assert') so the burndown does not read this as an unimplemented harness surface.

**Leverage** — Worthless to fix, valuable to classify. Siblings in the file are <<test.ToFix>> and other call sites live outside the relational corpus, so no other test is unblocked.

**Shares code with** — The scoreAssert outcome taxonomy is shared: any other bucket holding white-box engine-internals asserts should land in the same 'engine-internals assert' outcome rather than each inventing a harness arm.

---

### 24. TDS blank-cell lowers to NULL instead of empty string

**1 test** · effort **XS** · confidence medium · bucket 6 (wrong rows) · verdicts: NEEDS_PROBE 1

Tests: `testUnionTwoRelationMappings_ManyColumnProjectGeneratesSingleUnion`

**Mechanism** — The `#TDS` literal in the test body contains blank cells for the String-typed `firstName_s1`/`firstName_s2` columns. `Scalars.tdsCell` lowers a blank cell to SQL NULL, faithful to legend-engine's TDSExtension `nullValueLiterals("", "null")`. But the union fixture rows genuinely hold `''` in those columns, so the expected-vs-actual row comparison diverges on NULL vs empty string. Nothing about unions is actually wrong; the union plan is only the vehicle that surfaces the literal-lowering divergence. Note the test's stated intent (exactly one `union all` fragment, no per-column set-list growth) is never asserted in the .pure body — it only compares rows — so making it pass proves nothing about union-fragment count.

**Owning code** — core/src/main/java/com/legend/lowering/Scalars.java:2915-2926

**Fix** — Probe first: confirm the divergence is exactly NULL-vs-`''` on the two String columns rather than a real union row difference. Then pick one of two platform-level resolutions, applied once for both this test and its twin: (a) ledger both as upstream-inconsistent, accepting engine-faithful `nullValueLiterals` semantics and marking the expectations wrong; or (b) change `Scalars.tdsCell` so an empty-but-present cell on a String-typed column lowers to `SqlExpr.StringLit("")` rather than NULL, reserving NULL for genuinely absent cells. Do not patch the union path or the test body. Separately, if the single-`union all` guarantee matters, add a real plan-text assertion — the current body cannot check it.

**Leverage** — Low direct value — the test never asserts what it claims to guard. But the `tdsCell` decision is a one-line platform choice affecting every `#TDS` literal, so settle it once.

**Shares code with** — Touches lowering/Scalars.java, sibling to lowering/Lowerer.java; any bucket blaming TDS literal lowering shares this fix. Directly co-fixes testUnionTwoRelationMappings_ManyColumnProject, which lives in another bucket.

---

### 25. M3 function reflection surface absent (ledger, do not fix)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: TEST_ASSERTS_ENGINE_INTERNALS 1

Tests: `testGraphFetch`

**Mechanism** — The test calls legend-engine's extractDomainTypeClassFromFunction, a Pure function that reflects over the M3 syntax of the caller's own lambda and rewrites it: evaluateAndDeactivate on $func.expressionSequence, instanceOf(FunctionExpression), .parametersValues->at(1)->cast(@InstanceValue), then synthesizes new LambdaFunction values via newLambdaFunction/reprocessVariables. legend-lite walls on the first hop because Pure.FUNCTION_DEFINITION is declared with an empty property list, so Typer's property-access arm finds no expressionSequence. Declaring it would only move the wall: there is no ValueSpecification/FunctionExpression/InstanceValue value model, no evaluateAndDeactivate/reactivate, no newLambdaFunction — no self-reflective compiler surface at all.

**Owning code** — builtin/Pure.java:526 (FUNCTION_DEFINITION); compiler/spec/Typer.java property-access arm; compiler/spec/NewChecker.java:44

**Fix** — Do not fix — ledger as an unported surface: "M3 function reflection: FunctionDefinition.expressionSequence and the ValueSpecification value model". Specifically do NOT add `expressionSequence: ValueSpecification[1..*]` to Pure.FUNCTION_DEFINITION as a cosmetic unblock: the property would type but nothing could evaluate it, the wall would move to evaluateAndDeactivate / instanceOf(FunctionExpression) / newLambdaFunction with a less informative message, and it would push testPreprocessFunctionOnRuntime past its construction check into a deeper, more confusing failure. That is message laundering. If ever prioritised, the scope is an M3 ValueSpecification value model (FunctionExpression / InstanceValue / VariableExpression) reified from the parsed com.legend.protocol.spec AST plus the reflection natives — a subsystem, not a patch. Do not special-case the function in the harness.

**Leverage** — Zero-to-negative: this asserts legend-engine's own Pure-implemented reflection internals. Correct action is to record the wall, not build the subsystem.

**Shares code with** — Blames builtin/Pure.java's native metamodel classes and Typer's property-access arm — shared with other buckets that hit missing native class members.

---

### 26. Truncated builtin TDSColumn class (and absent toJSONStringStream)

**1 test** · effort **XL** · confidence high · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `testResultToJsonStream`

**Mechanism** — Two independent gaps, only the first visible. (a) legend-lite's builtin meta::pure::tds::TDSColumn declares only `offset` and `name`; the test constructs ^TDSColumn(name='id', type=String), so NewChecker.check's findProperty returns empty and it throws "class 'meta::pure::tds::TDSColumn' has no property 'type'" — the reported wall. Real Pure declares seven properties. (b) The test's actual point is `$result->toJSONStringStream([],true)->makeString()`, and toJSONStringStream exists nowhere in legend-lite. Runner classifies SHAPE via the no-execute path and attaches the first wall hit, which is merely the class-property gap.

**Owning code** — core/src/main/java/com/legend/builtin/Pure.java:485 (TDSColumn declaration); NewChecker.java:94; Typer.java:2240-2296 (packageableRef)

**Fix** — Fix (1) only, and ledger (2). In Pure.java:485 complete the native class to match tds.pure:25-40 — add type: …::type::Type[0..1], enumMappingId, enumMapping, documentation, sourceDataType, fully qualifying each type as neighbouring registrations do. This requires that a bare element reference such as `String` or `GeographicEntityType` synthesizes to a value conforming to …::type::Type, so add a PrimitiveType/Enumeration-as-value arm to Typer.packageableRef, which today resolves classes/mappings/runtimes/connections/databases and otherwise throws ResolutionException. Ledger toJSONStringStream: the engine's Result/TDS JSON envelope is a serializer subsystem, not a patch, and the resulting wall is honest. Do NOT special-case toJSONStringStream in EngineTestExecutor to return a canned string.

**Leverage** — The test is not winnable this cycle, but the truncated builtin class is a real correctness defect that mis-walls the whole TDS-metadata family — fix the class, ledger the serializer.

**Shares code with** — builtin/Pure.java class declarations and compiler/spec/Typer.java packageableRef — any bucket blaming a missing builtin property or an unresolvable bare type reference shares both.

---

### 27. Many-valued scalar allocation reaches an engine-H2 renderer with no UNNEST

**1 test** · effort **XL** · confidence medium · bucket 2 (execution-plan) · verdicts: MISSING_FEATURE 1

Tests: `relationalResultSourcingOfListExecutionPlan`

**Mechanism** — The `let nameList = …legalName->distinct()` binding becomes an Allocation via StatementExecutor.allocationNode, lowered through engineSql and rendered by the engine-style H2 dialect. Its type is String[*] — a value collection, not a RelationType — so Lowerer.lower falls past its RelationType and root-TypedMap arms into scalarRoot, which builds a FROM-less select and wraps the many-valued expression in SqlFn.UNNEST. unnestProjection is overridden only by DuckDb; the engine-style renderer inherits the base and throws DialectCapability, which extends IllegalStateException and so escapes PlanAsserts' catch list as [ERROR] rather than [SHAPE]. Underneath, the golden needs RelationalBlockExecutionNode, FreeMarkerConditionalExecutionNode, CreateAndPopulateTempTable and inFilterClause templating — none of which exist in legend-lite.

**Owning code** — core/src/main/java/com/legend/sql/dialect/EngineStyleH2.java:1054 (convention site), AnsiSqlRenderer.java:553,614-616; Lowerer.java:262-281; ValueCollectionOps.java:63-68; StatementExecutor.java:900-972

**Fix** — Apply (A), ledger (B). (A) XS: in EngineStyleH2 override unnestProjection — and for consistency listCall/listExists/listForAll/foldCall — to throw UnsupportedOperationException("plan: <construct> has no engine-H2 spelling"), exactly matching the convention this class already sets at line 1054. That reclassifies the failure ERROR→SHAPE with an honest message and stops a dialect-capability exception escaping the plan channel. Do NOT change DialectCapability's supertype and do NOT add it to PlanAsserts' catch list — that would move a platform classification decision into the harness. (B) Ledger: relation-space lowering for many-valued scalar roots (extend ValueCollectionOps.relationSpaceRewrite to accept removeDuplicates(TypedMap(rel,λ))) plus the whole Allocation-block plan vocabulary. Note removeDuplicates preserves first-occurrence order; SELECT DISTINCT does not.

**Leverage** — Do the XS reclassification only. The real feature is deep engine plan machinery and the (B) rewrite risks silently reordering currently-passing DuckDB deduped-collection tests.

**Shares code with** — lowering/Lowerer.java scalarRoot and StatementExecutor.allocationNode; change (A) reclassifies ERROR→SHAPE for every plan-channel test in any bucket that trips a dialect capability gap.

---

### 28. Cross-head concatenate of two navigations has no union rung

**1 test** · effort **L** · confidence high · bucket 11 (unclassified) · verdicts: MISSING_FEATURE 1

Tests: `testQualifierConcatenateTwoSimilarJoinsEmbedded`

**Mechanism** — The qualifier concatenates two navigations with DIFFERENT head properties (subAccount.oe, otherAccount.oe) landing on one class. legend-lite models class-concatenate only as 'one head property with parked branch predicates' (#cN synthetic heads): SyntheticHeads.liftConcatStreams requires every branch to be the SAME whole navigation node and returns null otherwise. The lift refuses, branches fall to per-hop resolution, and under testEmbeddedMapping branch 1's leaf `oe` binds to a TypedNewInstance (embedded ctor). Substitution.assocBindingRead has no arm for a class-valued embedded leaf and throws. Two stacked gaps: the missing cross-head union (primary) and class-valued-embedded-leaf materialization.

**Owning code** — core/src/main/java/com/legend/resolver/SyntheticHeads.java:672 (liftConcatStreams), :171-191 (applyToPipe); core/src/main/java/com/legend/resolver/Substitution.java:2104 (assocBindingRead)

**Fix** — Ledger as a rung; do not spot-fix. (1) Give liftConcatStreams a NEW head kind rather than loosening its nav.equals(headNode) check: mint a UNION head carrying the ordered list of whole branch navigations, and have applyToPipe resolve each branch to its own target pipeline and UNION-ALL them with column alignment — output column named by joining each branch's unique column name with '_' (engine processConcatenate:2790), the union joined back to the driver on the OR of the branches' own join conditions; this also yields the null-padded FK columns the expected SQL shows. (2) Add an EMBEDDED arm to Substitution.assocBindingRead: when leafInner is a TypedNewInstance and the read is a branch materialization, the ctor's mapped property expressions become that branch's projected columns, renamed exactly as the scalar arm does. Do (1) first; keep the current refusal as fallback for unhandled branch shapes.

**Leverage** — Moderate. Real missing surface, also unblocks testQualifierConcatenateTwoSimilarJoins, but expensive and sits squarely on the merge-by-identity wrong-rows trap (audit 16, Merge golden 7-vs-13 rows). Only worth it with its own head kind.

**Shares code with** — Adds an arm to resolver/Substitution.java assocBindingRead (:2104) — coordinate with any bucket editing Substitution's leaf-read dispatch.

---
