# Bucket 3 — Engine metamodel surface

3 tests from the ledger; **3 still non-passing** at `9d1f2cd0`.

Each entry was produced by an agent that read the test's `.pure` body, grepped the failure message back to the emitting legend-lite code, read that method's control flow, and checked semantics against real legend-engine / legend-pure sources. Citations were mechanically verified to resolve.

Verdicts: MISSING FEATURE 2, REAL DEFECT 1

---

## `testUnionWithJoinToOneTable`

| | |
|---|---|
| family | `lineage/scanRelations` |
| sweep status | SHAPE |
| **verdict** | **REAL DEFECT** |
| effort | XS |
| confidence | high |
| **adversarial review** | **CONFIRMED** |

**Root cause**

ScanRelations.walk treats "no property mapping for this property in THIS set" as an unimplemented-surface wall, but in the engine it is a normal, silent outcome. In unionToUnionMapping3 the Firm union has two arms: firm_set1 maps employees (join @PersonSet1FirmSet1), firm_set2 maps only legalName. buildRoots (ScanRelations.java:778-815) walks EVERY root set with EVERY collected chain, so the chain [employees, name] is walked against firm_set2 too. In walk, pmsFor returns empty (ScanRelations.java:1211-1212); derivedChains returns null because 'employees' is a stored/association property, not a derived one (ScanRelations.java:1796-1834 only scans cd.derivedProperties()); the name is not a generated milestoning member; so control reaches the throw at ScanRelations.java:1252-1254 and the whole scan dies. The engine has no such wall: in scanRelations.pure the per-property propMappings collection (line 152-161) is simply empty for that set and `$propMappings->map(pm | ...)` (line 164) yields nothing, which is exactly the golden — FirmSet2 prints with no child.

**Fix**

In core/src/main/java/com/legend/lineage/ScanRelations.java, method walk: replace the throw at :1252-1254 with `return;` (keeping the milestoning early-return above it, which then becomes redundant but harmless). Comment it with the engine citation: scanRelations.pure:152-164, where propMappings can be empty and `->map` contributes nothing — a set that does not map a property contributes no relation and no columns. Nothing else changes: buildRoots already created the FirmSet2 root node with its PK+legalName columns, so the golden's childless `FirmSet2 [ID, name]` line falls out. Do NOT gate the skip on 'the class is union-mapped' or on runtimeScan — the engine's skip is unconditional and the same rule feeds the tdg walk.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/legend-engine-xts-relationalStore/legend-engine-xt-relationalStore-generation/legend-engine-xt-relationalStore-pure/legend-engine-xt-relationalStore-core-pure/src/main/resources/core_relational/relational/lineage/scanRelations/scanRelations.pure:152-164 — `let propMappings = if(... , | $source->toOne()->_propertyMappingsByPropertyName($prop.name->toOne()), | []); ... $propMappings->map(pm | ...)`. An unmapped property yields an EMPTY collection and the map contributes nothing; there is no assert/fail on that path (the only assert, at :171, fires on a non-relational TARGET set, which is a different condition). The same silent-empty shape is repeated in scanRelationColumns (:260-270).

<details><summary>Adversarial review notes (CONFIRMED)</summary>

Every legend-lite citation resolves exactly as claimed and the mechanism is on the path. ScanRelations.java:1252-1254 is verbatim the throw whose message matches the recorded sweep failure (docs/RELATIONAL_CORPUS_ALL.md:1297: "property 'employees' has no property mapping in set 'meta::relational::tests::model::simple::Firm'"). :1211-1212 opens the empty-pms branch; the only escapes are derivedChains (:1217) and the three generated milestoning names (:1245-1249). derivedChains (:1796-1834) only iterates cd.derivedProperties(), and 'employees' is an association/stored property in the simple model, so it returns null -> the throw is reached. buildRoots (:778-816) does walk every collected chain against every root set (:809-813), and rootClassMappings returns both firm_set1 and firm_set2 for the union. unionToUnionMapping3 (testUnion.pure:1818-1841) confirms firm_set2 maps only legalName. The golden (scanRelationsTests.pure:290-301) is exactly the childless FirmSet2. The PK demand claim also holds: :805-807 adds pkCols when rootCms.size()>1 and extentRoots (true for the 4-arg runtime variant, LineageRelationsForm.java:126 passes size()==4). Fix is genuinely one line and cannot turn a current green red (a green never reaches the throw); the worst case is converting an Outcome.Unsupported into a visible golden mismatch, which is the honest direction. Confidence in the resulting tree is high because the sibling testUnionToUnion/testUnion lineage tests are not in the failure list and their goldens pin the identical PersonSet1 [FirmID, firstName_s1, lastName_s1] subtree, so the derived-name + join-column machinery already reproduces it.

</details>

**Citation issues found in review** — Engine-overload mismatch: the diagnosis justifies the skip with scanRelations.pure:152-164, but that is the 3-arg STATIC overload. This test calls scanRelations($query,$mapping,testRuntime(),extensions) — scanRelations.pure:341-364 -> generatRelationalTrees (:397-434), which builds the tree from the generated SQL (generateRelationTreeFromRelationalOperationElement), never touching propMappings. The conclusion still holds (the union arm that maps nothing contributes no join in the SQL either, and the static path is silently empty), but the quoted lines are not the engine code that produces THIS golden. Minor: testUnion.pure:669-680 — FirmSet1 is (ID INT PRIMARY KEY, name VARCHAR(200), nickName VARCHAR(200)), not just ID/name as stated; harmless since nickName is undemanded.

**Risk** — The throw is currently also the safety net for legend-lite's OWN lookup gaps in pmsFor (ScanRelations.java:1953-1982 searches only cm.propertyMappings() plus association-mapping ends matched by source set id; an inherited property on an `extends` set, if the legacy surface consumed here is not extends-flattened, would now be dropped silently instead of walling). Consequence is a missing subtree in lineage output and in testDataGeneration's relation tree, i.e. a wrong golden rather than wrong DB rows. Tenet-2 trap to avoid: do not 'fix' this in the harness (LineageRelationsForm / Runner) by catching the exception or by special-casing union mappings — the skip is platform semantics and belongs in walk. If you want a net, log at debug level (LL_LINEAGE_DEBUG) rather than throw.

**Also unblocks** — Any other scanRelations / testDataGeneration test whose mapping has a union arm (or a set) that does not map a property the query navigates — e.g. queries over unionWithSinglePropertyMapping-style mappings. Not verified against the sweep; treat as a hypothesis.

**Falsifier** — Apply the one-line skip and print the tree for this query/mapping. If FirmSet2 still acquires a child, or FirmSet1/PersonSet1 column sets differ from the golden, then the wall was not the only defect and the union/derived column demand is also wrong. Cheapest non-build observation: with LL_LINEAGE_DEBUG set, confirm `paths` contains exactly [[legalName],[employees,name]] and that the throw is reached only on the firm_set2 iteration.

<details><summary>Evidence read (7 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:1252 — `throw new NotImplementedException("scanRelations: property '" + prop.name() + "' has no property mapping in set '" + cm.className() + "'")`, the exact sweep message.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:1211 — `List<PropertyMapping> pms = pmsFor(ctx, md, cm, prop.name()); if (pms.isEmpty()) {` opens the branch that ends in that throw; the only escapes are derivedChains (:1217) and the businessDate/processingDate/snapshotDate names (:1244).
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:1796-1810 — derivedChains only iterates `cd.derivedProperties()`, so a stored/association property such as 'employees' returns null.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/lineage/ScanRelations.java:795-815 — buildRoots creates one root Node per root class mapping and then `for (List<Seg> p : paths) for (int i = 0; i < rootCms.size(); i++) walk(...)`: every chain is walked against every union arm, including the arm that lacks the property.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnion.pure:1837-1840 — `Firm[firm_set2] : Relational { legalName : [myDB]FirmSet2.name }`: no employees mapping in the second arm (firm_set1 has it at :1834).
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/lineage/scanRelations/scanRelationsTests.pure:290-299 — the test's golden is `root / FirmSet1 [ID, name] / PersonSet1 [FirmID, firstName_s1, lastName_s1] / FirmSet2 [ID, name]`: FirmSet2 present, childless.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/union/testUnion.pure:669-680 — FirmSet1/FirmSet2 both `ID INT PRIMARY KEY, name VARCHAR(200)`, matching the golden's [ID, name] (PK demand added by ScanRelations.java:806 for a multi-set root).

</details>

---

## `testExtractDBsWithSubstituition`

| | |
|---|---|
| family | `tests` |
| sweep status | SHAPE |
| **verdict** | **MISSING FEATURE** |
| effort | L |
| confidence | high |

**Root cause**

The test has no execute() call, so Runner.run0 routes it to tryRunNoExecute (Runner.java:1307), which compiles the body through the platform. The body calls the corpus's own Pure function meta::relational::runtime::extractDBs (helperFunctions.pure:80-86), whose first expression is `$m.includes.included->map(...)`. legend-lite's native prelude declares the Mapping metaclass with a single property: Pure.java:406 `native Class meta::pure::mapping::Mapping extends ModelElement { name: String[0..1]; }`. Typer's ClassType property branch finds no 'includes', no generated milestoning member, and no elementOverride, so it throws TypeInferenceException at Typer.java:2566, which SpecCompiler.compile re-wraps with the enclosing function name at SpecCompiler.java:70 — producing verbatim the sweep text. The surface is genuinely absent: there is no MappingInclude/SubstituteStore class, no Mapping.classMappings, no mainTableAlias on RelationalMappingSpecification, and no meta::pure::mapping::resolveStore native anywhere in Pure.java, and MetamodelWalk.prop has no arm for an Mm (mapping) handle at all. The wall is honest.

**Fix**

Platform work in two layers. (1) DECLARATIONS in core/src/main/java/com/legend/builtin/Pure.java: extend MAPPING_METACLASS (:406) to `{ name: String[0..1]; includes: meta::pure::mapping::MappingInclude[*]; classMappings: meta::pure::mapping::SetImplementation[*]; }`; add `native Class meta::pure::mapping::MappingInclude { owner: Mapping[1]; included: Mapping[1]; storeSubstitutions: meta::pure::mapping::SubstituteStore[*]; }` and `native Class meta::pure::mapping::SubstituteStore { original: meta::pure::store::Store[1]; substitute: meta::pure::store::Store[1]; }` (mapping.pure:26-31/143-155); add `mainTableAlias: meta::relational::metamodel::TableAlias[1]` to RELATIONAL_MAPPING_SPECIFICATION (:382, the TODO at :378 already names it) and `database: meta::relational::metamodel::Database[0..1]` to TABLE_ALIAS_METACLASS (:346) (relational.pure:111/209); declare `native function meta::pure::mapping::resolveStore(_this:Mapping[1], store:Store[1]):Store[1]` next to the other mapping natives (~:1478-1493). (2) EVALUATION in core/src/main/java/com/legend/exec/MetamodelWalk.java: add a `MiH(ModelContext ctx, LegacyMappingDefinition owner, MappingInclude inc)` handle and a `TaH(ModelContext ctx, String dbFqn, String table)` handle; in prop() (:1074) add arms — Mm+"includes" -> MiH list from mapping().includes(); MiH+"included" -> mapping(ctx, inc.mappingPath()); MiH+"storeSubstitutions" -> substitution handles; Mm+"classMappings" -> `new Cm(ctx, mapping, r)` for each ClassMapping.Relational; Cm+"mainTableAlias" -> TaH built from cm.mainTable() (reuse the this/extends/inferred precedence already in mainTable(), :906-925); TaH+"database" -> database(ctx, dbFqn) i.e. the WRITTEN db, not the one that physically owns the table (this is what the test's assert pins); and a static `resolveStore(Object mm, Object store)` that folds mapping().includes()'s StoreSubstitution pairs (originalStore -> replacementStore) and returns the substitute or the store unchanged (functions_Mapping.pure:105-114). (3) Host-eval plumbing in StatementExecutor: route the `resolveStore` native like the existing mappingNav cases (:1397-1401), make `filter(r|$r->instanceOf(RootRelationalInstanceSetImplementation))` work over Cm handles (walkFilter at :1857-1875 currently only matches PlanNode kinds — extend it to test the handle's metaclass), make `cast(@...)` a passthrough over handles, and make `==`/removeDuplicates over Db handles compare by database FQN so `$dbs->at(0) == SubstitutionTestDB1` (a TypedPackageableRef on the right) is true. If any one of these is not worth building now, the honest alternative is to ledger the test as MISSING_FEATURE and keep the wall — but do NOT special-case extractDBs in Runner/EngineTestExecutor.

**How legend-engine does it** — /Users/neemsandv/legend/legend-engine/.../core_relational/relational/helperFunctions/helperFunctions.pure:80-86 — `meta::relational::runtime::extractDBs(m:Mapping[1], topMapping:Mapping[1])` = `$m.includes.included->map(i|$i->extractDBs($topMapping))` concatenated with `$m.classMappings->filter(r|$r->instanceOf(RootRelationalInstanceSetImplementation))->cast(@RootRelationalInstanceSetImplementation).mainTableAlias.database->map(n|$topMapping->resolveStore($n)->cast(@Database))`, ->removeDuplicates(). Supporting declarations: /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-mapping/legend-pure-m2-dsl-mapping-pure/src/main/resources/platform_dsl_mapping/grammar/mapping.pure:26-31 (Mapping { includes : MappingInclude[*]; classMappings : SetImplementation[*]; ... }) and :143-155 (MappingInclude { owner, included, storeSubstitutions } / SubstituteStore { owner, original, substitute }); .../functions_Mapping.pure:105-114 (findSubstituteStore folds the includes, resolveStore returns the substitute or the store itself); /Users/neemsandv/legend/legend-pure/legend-pure-store/legend-pure-store-relational/legend-pure-m2-store-relational-pure/src/main/resources/platform_store_relational/grammar/relational.pure:111 (RelationalMappingSpecification.mainTableAlias : TableAlias[1]) and :209 (TableAlias.database : Database[0..1]).

**Risk** — Adding `classMappings`/`includes` to the Mapping metaclass changes typing for any corpus expression that mentions those names — check it does not shadow or conflict with the existing natives `_classMappingByClass`/`classMappingById`/`rootClassMappingByClass` (Pure.java:1478-1491), which stay the preferred lookups. `TableAlias.database` must return the AS-WRITTEN database of the class mapping (SubstitutionTestDB1), not the include-resolved owner of the table (SubstitutionTestDB1_Inc) — returning the physical owner silently inverts the very thing this test asserts. Tenet-2 trap: implementing extractDBs as a Runner/EngineTestExecutor intercept, or teaching the harness to recognize the assert shape, would be textbook harness compensation — the Mapping metamodel projection is platform Knowledge.

**Also unblocks** — Every other corpus site that navigates the Mapping metamodel the same way — helperFunctions.pure's setUpData/extractDBs overloads and executionPlan/tests/executionPlanExecutionTest.pure:158 (`$mapping->extractDBs($runtime)`). Any test walling on 'Mapping has no property includes/classMappings' shares this fix. Count not verified against the sweep.

**Falsifier** — Grep the platform prelude for any declaration of `MappingInclude` or of an `includes` property on meta::pure::mapping::Mapping. If one exists (i.e. the property is declared somewhere I did not look, e.g. a second nativeClass registry), then the wall is not an absent surface and the diagnosis is wrong — the failure would then be a module/context problem instead. I ran this grep over core/src/main and found only Pure.java:406 with `name` alone.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:406 — MAPPING_METACLASS declares only `name`; no includes, no classMappings.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2565-2566 — `throw new TypeInferenceException("class " + ct.fqn() + " has no property '" + ap.property() + "'")`, the message tail in the sweep.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/SpecCompiler.java:70 — `"in function '" + fn.qualifiedName() + "': " + e.getMessage()`, the message head.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/test/java/com/legend/rcorpus/Runner.java:1300-1315 — the no-execute path calls tryRunNoExecute and, on a non-PASS/FAIL, reports `no execute(|...) call [calls <ns>] — wall: <detail>`; matches the sweep line exactly.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/exec/MetamodelWalk.java:1074-1160 — prop() has arms for Cm(id/root), TacH, Db(schemas), Sch, Tbl, ColH, Vw, Vcm, Pm, RoewjH — none for the Mm mapping handle, so even with a declaration there is no evaluation.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/LegacyMappingDefinition.java:53-59 — the model ALREADY carries `List<MappingInclude> includes` and `List<ClassMapping> classMappings`, so the data exists; only the metamodel projection is missing.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/model/MappingInclude.java:22-33 — `record MappingInclude(String mappingPath, List<StoreSubstitution> substitutions)` with `StoreSubstitution(originalStore, replacementStore)` — the substitution data resolveStore needs is present.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/testRelationalExtension.pure:383-388 — the test: `let dbs = SubstitutionTestMappingLevel1->extractDBs(); assertSize($dbs,1); assert($dbs->at(0) == SubstitutionTestDB1);` (the mapping writes [SubstitutionTestDB1]testTable1.prop1 while the table physically lives in the INCLUDED database SubstitutionTestDB1_Inc, :363-372 — the assert pins that the WRITTEN db survives).

</details>

---

## `testEnumInRelation`

| | |
|---|---|
| family | `tests/mapping/enumeration` |
| sweep status | ERROR |
| **verdict** | **MISSING FEATURE** |
| effort | M |
| confidence | medium |

**Root cause**

The assert argument is `$result.values->cast(@meta::pure::metamodel::relation::TDS<Any>).csv`. EngineTestExecutor.eval finds no shape it can strip (its serialization-tail strip at EngineTestExecutor.java:2604-2614 matches an AppliedFunction named toCSV/toString, NOT a property read named 'csv'), so the whole expression goes to evalSpliced (:2666-2679) and is compiled by the platform. legend-lite declares the TDS metaclass with an EMPTY body — Pure.java:211 `native Class meta::pure::metamodel::relation::TDS<T> extends meta::pure::metamodel::relation::Relation {}` — so Typer's property step throws 'class meta::pure::metamodel::relation::TDS has no property csv' (Typer.java:2566 for the ClassType receiver / :2579 for the parameterized TDS<Any> receiver). Real Pure declares `csv : String[1]` on TDS, and the engine reads it (toPureGrammar.pure:179 does `$t.csv->replace(' ','')`). So the surface — the declaration AND the relation-result-to-CSV rendering behind it — is genuinely unimplemented; the wall is honest.

**Fix**

Two platform changes. (1) Pure.java:211 — declare the property: `native Class meta::pure::metamodel::relation::TDS<T> extends meta::pure::metamodel::relation::Relation { csv: meta::pure::metamodel::type::String[1]; }`, citing tds.pure:19. (2) Evaluation: add a terminal serialization arm in com.legend.StatementExecutor beside spliceValuesRead (:2687-2706) — a TypedPropertyAccess with property "csv" whose source, after unwrapping TypedCast and the `.values` splice, resolves to an execute frame: evaluate that chain to an ExecutionResult.Tabular and return the rendered String. Put the renderer itself in a shared platform helper (e.g. com.legend.exec) with the TDS.csv convention, distinct from toCSV: header = column names joined with ', '; each row = cells joined with ','; null cell = 'TDSNull'; dates as ISO yyyy-MM-dd; enum cells as the enum VALUE NAME; lines joined with '\n' and NO trailing newline. Then have EngineTestExecutor's toCSV strip and this new renderer share the cell-rendering helper rather than duplicating it. Compare the result as an ordinary String against the literal, subject to the existing row-order policy.

**How legend-engine does it** — /Users/neemsandv/legend/legend-pure/legend-pure-dsl/legend-pure-dsl-tds/legend-pure-m2-dsl-tds-pure/src/main/resources/platform_dsl_tds/tds.pure:17-20 — `Class meta::pure::metamodel::relation::TDS<T> extends Relation<T> { csv : String[1]; }`. Independent engine use of the same property: /Users/neemsandv/legend/legend-engine/legend-engine-core/legend-engine-core-pure/legend-engine-pure-code-compiled-core/src/main/resources/core/pure/serialization/toPureGrammar.pure:179 — `t:meta::pure::metamodel::relation::TDS<Any>[1]|'#TDS{\n'+$t.csv->replace(' ','')+'}#'`. Note the corpus helper meta::relational::tests::csv::toCSV (helperFunctions.pure:198-211) is a DIFFERENT rendering (header joined ',', trailing '\n') — do not conflate the two.

**Risk** — Row ORDER: the golden is Alice,Bob,Curtis with no ORDER BY, so a strict string compare will be order-dependent; reuse the existing header-pinned/multiset order policy (EngineTestExecutor.csvEquals :3160-3208) rather than inventing a second one, or the test becomes flaky against DuckDB's row order. Adding `csv` to TDS also makes `.csv` typeable anywhere — make sure a `.csv` read whose receiver is NOT an execute-backed tabular still walls loudly instead of returning ''. Tenet-2 trap: the tempting one-line fix is to extend the harness's toCSV strip at EngineTestExecutor.java:2604 to also match an AppliedProperty named 'csv'. That is harness compensation — TDS.csv is a declared platform metamodel property (tds.pure:19) that any user function may read, so the declaration and the rendering both belong in the platform.

**Also unblocks** — Nothing else in this corpus — `.csv` on a TDS appears exactly once (grep of the whole core_relational/relational tree returned only testEnumerationMapping.pure:142).

**Falsifier** — The csv gap may not be the only blocker: this query is a RELATION projection (~[name:..., type:..., firm:..., role:...]) over enumeration-mapped columns including a case() transform and a constant 'A'. Cheapest check: confirm the sibling tests meta::relational::tests::projection::enumeration::testProjectionWithEnum / testProjectionWithEnumUsingLambda (testEnumerationMapping.pure:194-212) currently PASS — they pin CONTRACT,FULL_TIME,CONTRACT through the classic project. If they pass, the enum→name rendering is already right and csv really is the only gap; if they fail, the enum surface is the deeper cause and csv is only the first wall.

<details><summary>Evidence read (8 citations)</summary>

- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/builtin/Pure.java:211 — TDS_RELATION declared with `{}`; its own comment says 'cast target in testEnumInRelation; taxonomy T2: absent metamodel class'.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/compiler/spec/Typer.java:2566 and :2579 — both the ClassType and the GenericType arm throw `class <fqn> has no property '<name>'`; :2579 prints g.rawFqn(), which is why the message names TDS without its type argument.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2603-2614 — the existing serialization-tail strip requires `simpleName(tail.function()).equals("toCSV") || ..."toString"` on an AppliedFunction; a `.csv` AppliedProperty never matches, so nothing intercepts before the platform compile.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:2666-2679 — evalSpliced wraps the expression in a LambdaFunction and hands it to Compiler.executeResolved, i.e. straight into the G-phase where the Typer wall fires.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/harness/EngineTestExecutor.java:3136-3158 — csvText() renders the toCSV convention: header joined with ',' and EVERY line newline-terminated. That is a different format from TDS.csv (header ', ' separated, no trailing newline), so the existing renderer cannot simply be reused as-is.
- /Users/neemsandv/legend/legend-lite/.claude/worktrees/e2e-deep-diagnosis/core/src/main/java/com/legend/StatementExecutor.java:2687-2706 — spliceValuesRead is the platform seam that already turns a `.values` read (over a frame variable or an inline execute) into the underlying query chain; a `.csv` read sits directly on top of it.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMapping.pure:133-144 — the test body and its golden string: header 'name, dateOfHire, type, active, firm, role' (', ' separated), rows ',' separated, null cell rendered 'TDSNull', NO trailing newline.
- /Users/neemsandv/legend/legend-engine/.../core_relational/relational/tests/mapping/enumeration/testEnumerationMappingDomain.pure:219-255 — employeeTestMapping: type/active/role/firm all go through EnumerationMappings (role via case(...), firm via the constant 'A'), which is why the golden cells are CONTRACT/YES/FIRM_A/JUNIOR.

</details>

---
