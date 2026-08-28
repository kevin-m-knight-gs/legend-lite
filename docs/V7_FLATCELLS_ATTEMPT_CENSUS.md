# V7 flat-cells ATTEMPT census (2026-08-28) — the REVERTED leg's measured output

Companion to charter §8 leg 1 (attempted, measured, reverted at
ec9f6fe8; retry recipe in §8). This is the FULL [v7]/[canon] output
of the attempt's sweep — the diagnosis payload for the 28-row
unexplained class (byte-verdict failure message vs sql-verdict
disagree=0). Regenerating it requires re-applying the leg, so it
is preserved here.

```
[canon] agree=263 disagree=27 residue=0 | sql-verdict agree=1203 disagree=0 declined=818 ulp-policy=0
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,annualized,Campus,9.812850678733032,Lateral,9.26769…> a<hireType,annualized,Campus,9.812850678733032,Lateral,9.26769…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,cme,Campus,0.5131221719457013,Lateral,0.56443438914…> a<hireType,cme,Campus,0.5131221719457014,Lateral,0.56443438914…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p12mtd,Campus,6.84,Lateral,7.24,> a<hireType,p12mtd,Campus,6.840000000000002,Lateral,7.239999999…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p12wa,Campus,0.383333333333,Lateral,0.391666666667,> a<hireType,p12wa,Campus,0.38333333333333336,Lateral,0.39166666…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p12wtd,Campus,4.6,Lateral,4.7,> a<hireType,p12wtd,Campus,4.6,Lateral,4.700000000000001,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p4wa,Campus,0.8575,Lateral,0.98,> a<hireType,p4wa,Campus,0.8574999999999999,Lateral,0.98,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p4wtd,Campus,3.43,Lateral,3.92,> a<hireType,p4wtd,Campus,3.4299999999999997,Lateral,3.92,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p52wa,Campus,0.131538461538,Lateral,0.134230769231,> a<hireType,p52wa,Campus,0.13153846153846155,Lateral,0.13423076…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,p52wtd,Campus,6.84,Lateral,6.98,> a<hireType,p52wtd,Campus,6.840000000000002,Lateral,6.979999999…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pma,Campus,0.259,Lateral,0.222,> a<hireType,pma,Campus,0.259,Lateral,0.22200000000000003,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pma,Campus,0.236666666667,Lateral,0.255,> a<hireType,pma,Campus,0.23666666666666666,Lateral,0.255,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,priorYear,Campus,2.84,Lateral,3.06,> a<hireType,priorYear,Campus,2.84,Lateral,3.0600000000000005,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pwa,Campus,0.103440366973,Lateral,0.0940366972485,> a<hireType,pwa,Campus,0.10344036697247706,Lateral,0.0940366972…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pwa,Campus,0.05657370518,Lateral,0.0609561752985,> a<hireType,pwa,Campus,0.05657370517928287,Lateral,0.0609561752…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pymtd,Campus,0.69,Lateral,0.46,> a<hireType,pymtd,Campus,0.69,Lateral,0.45999999999999996,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pyqtd,Campus,1.4,Lateral,1.36,> a<hireType,pyqtd,Campus,1.4,Lateral,1.3599999999999999,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pytd,Campus,1.59,Lateral,1.54,> a<hireType,pytd,Campus,1.5899999999999999,Lateral,1.54,>
[canon] sqlDecline flat-cells side
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,pywa,Campus,0.05657370518,Lateral,0.0609561752985,> a<hireType,pywa,Campus,0.05657370517928287,Lateral,0.060956175…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,qtd,Campus,4.23,Lateral,4.32,> a<hireType,qtd,Campus,4.2299999999999995,Lateral,4.32,>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,wtd,pwa,Campus,2.16,0.206880733946,Lateral,3.24,0.1…> a<hireType,wtd,pwa,Campus,2.16,0.20688073394495413,Lateral,3.2…>
[canon] gridText lattice=true byte=false:cell-diff@line0 e<hireType,ytd,Campus,5.59,Lateral,5.72,> a<hireType,ytd,Campus,5.590000000000001,Lateral,5.719999999999…>
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-e: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline flat-cells side
[canon] sqlDecline any-pair: enum kind has no literal channel: enum:meta::relational::tests::model::simple::GeographicEntityType
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-e: canon-exec: Binder Error: ORDER BY non-integer literal has no effect.
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline side-a: canonical-order over an unrefined Number side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] gridText lattice=true byte=false:row-order-only@line2
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: canonical-order over an unrefined Number side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline flat-cells side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline decoded-grid-or-graph side
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: non-sql-arm
[canon] sqlDecline side-a: non-sql-arm
[canon] sqlDecline side-a: non-sql-arm
[canon] sqlDecline side-a: non-sql-arm
[canon] sqlDecline side-a: non-sql-arm
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline flat-cells side
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[canon] sqlDecline side-a: keyless-instance: meta::pure::metamodel::relation::Relation
[v7] dual-channel agree=2959 disagree=52 declined=2230 | side-rows 0:56 1:3878 2-3:1278 4-7:887 8-15:189 16-31:38 32-63:11 64-127:1 256-511:1 2048-4095:1
[v7] form assert/1 agree=16 disagree=0
[v7] form assert/2 agree=3 disagree=0
[v7] form assertContains/2 agree=17 disagree=0
[v7] form assertEmpty/1 agree=7 disagree=0
[v7] form assertEq/2 agree=5 disagree=0
[v7] form assertEqWithinTolerance/3 agree=8 disagree=0
[v7] form assertEquals/2 agree=1567 disagree=51
[v7] form assertFalse/1 agree=6 disagree=0
[v7] form assertJsonStringsEqual/2 agree=13 disagree=0
[v7] form assertNotEmpty/1 agree=10 disagree=0
[v7] form assertNotEmpty/2 agree=1 disagree=0
[v7] form assertSameElements/2 agree=656 disagree=1
[v7] form assertSize/2 agree=648 disagree=0
[v7] form assertTdsEquivalent/4 agree=2 disagree=0
[v7] declined assert/1 :: TypeInferenceException: a non-let intermediate statement in a bare lambda literal is not supported = 3
[v7] declined assert/1 :: TypeInferenceException: in function 'meta::relational::metamodel::execute::tests::runRelationalRouterExtensionConnectionEquality': unknown function 'routerExtensions' — no function of this name in the… = 5
[v7] declined assert/1 :: TypeInferenceException: unknown type 'InstanceValue' in @InstanceValue = 1
[v7] declined assert/1 :: host-partition-sqltext = 10
[v7] declined assert/1 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Order) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCa… = 5
[v7] declined assert/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::relational::extension::relationalExtensions' with 0 parameter(s) = 1
[v7] declined assert/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeC… = 5
[v7] declined assertEqWithinTolerance/3 :: TypeInferenceException: in call to 'meta::pure::functions::asserts::assertEqWithinTolerance', argument 2: multiplicity [*] is not compatible with [1] = 1
[v7] declined assertEquals/2 :: IllegalStateException: MULTIPLICITY-STAMP INVARIANT VIOLATED (stamp program, docs/STAMP_DISCIPLINE_PROGRAM.md): ONE-STAMP/LIST-SHAPE mult=[1..1] sql=Call node=TypedNativeCall callee=meta::pure::functi… = 17
[v7] declined assertEquals/2 :: IllegalStateException: extend/project columns [name, prop4] reference names unresolvable even after isolation [col='prop4' ref='<whole variable>'] = 1
[v7] declined assertEquals/2 :: IllegalStateException: filter predicate references column '<whole variable>', unresolvable even after isolation [param=_r0; pred=TypedNativeCall[callee=TypedFunction[qualifiedName=meta::pure::function… = 1
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::mapping::classMappingById' with 2 parameter(s) = 18
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::mapping::rootClassMappingByClass' with 2 parameter(s) = 13
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::relational::extension::relationalExtensions' with 0 parameter(s) = 7
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::relational::functions::toPostgresModel::newState' with 0 parameter(s) = 18
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::relational::functions::typeInference::inferRelationalType' with 1 parameter(s) = 5
[v7] declined assertEquals/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::relational::metamodel::view' with 2 parameter(s) = 6
[v7] declined assertEquals/2 :: TypeInferenceException: in call to 'meta::relational::tests::mapping::relation::pkOfFunc', argument 1: expected FunctionDefinition<meta::pure::metamodel::type::Any>, got Function<meta::pure::metamodel… = 36
[v7] declined assertEquals/2 :: host-partition-sqltext = 196
[v7] declined assertEquals/2 :: host-partition-tdg = 13
[v7] declined assertEquals/2 :: host-unsupported = 25
[v7] declined assertEquals/2 :: wall: class query under TypedMap is not resolvable yet (H2 vocabulary) = 69
[v7] declined assertEquals/2 :: wall: class query under TypedUserCall is not resolvable yet (H2 vocabulary) = 513
[v7] declined assertEquals/2 :: wall: relation has no column 'activities' in scalar read = 14
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::pure::executionPlan::m2m2r::tests::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNative… = 2
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::pure::executionPlan::m2m2r::tests::PersonPeterSmith) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > T… = 2
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::pure::mapping::modelToModel::test::shared::dest::Firm) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall >… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::pure::mapping::modelToModel::test::shared::dest::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall… = 2
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::pure::mapping::modelToModel::test::shared::src::_Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall… = 3
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::groupBy::datePeriods::domain::FiscalCalendarDate) unresolved — the query shape around it is not supported by the resolver yet [at root > Typ… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::m2m2r::Entitlement) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCall… = 5
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::mapping::enumeration::model::domain::Employee) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedN… = 11
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::mapping::enumeration::model::domain::Product) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNa… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::mapping::propertyfunc::model::domain::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNa… = 3
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::mapping::sqlFunction::model::domain::SqlFunctionDemo) unresolved — the query shape around it is not supported by the resolver yet [at root >… = 14
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::milestoning::Order) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCall… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Address) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Address) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNative… = 3
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Firm) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda > … = 2
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Firm) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCal… = 5
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Interaction) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNa… = 3
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Location) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambd… = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Order) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCa… = 8
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda … = 32
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda] = 1
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeC… = 12
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Product) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedNative… = 4
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Trade) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda >… = 17
[v7] declined assertEquals/2 :: wall: store resolution left getAll(meta::relational::tests::tds::tdsJoin::testJoinTDS_Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > T… = 3
[v7] declined assertEquals/2 :: wall: store resolution left user call 'meta::relational::mapping::sql' uninlined — the call shape is not supported by the resolver yet [at root > TypedNativeCall > TypedNativeCall] = 4
[v7] declined assertEquals/4 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Person) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda … = 8
[v7] declined assertEquals/4 :: wall: store resolution left getAll(meta::relational::tests::model::simple::Trade) unresolved — the query shape around it is not supported by the resolver yet [at root > TypedNativeCall > TypedLambda >… = 15
[v7] declined assertEqualsH2Compatible/3 :: host-partition-sqltext = 322
[v7] declined assertEqualsH2Compatible/3 :: host-partition-tdg = 4
[v7] declined assertFalse/1 :: TypeInferenceException: a non-let intermediate statement in a bare lambda literal is not supported = 7
[v7] declined assertFalse/1 :: TypeInferenceException: unknown type 'InstanceValue' in @InstanceValue = 1
[v7] declined assertFalse/1 :: host-partition-sqltext = 6
[v7] declined assertInstanceOf/2 :: IllegalStateException: class value ^meta::relational::metamodel::SQLNull(…) has no canonical layout — the class declares no stored properties (or no model rides this lowering) = 1
[v7] declined assertIs/2 :: host-unsupported = 1
[v7] declined assertJsonStringsEqual/2 :: TypeInferenceException: in call to 'meta::pure::functions::asserts::assertJsonStringsEqual', argument 2: multiplicity [*] is not compatible with [1] = 160
[v7] declined assertJsonStringsEqual/2 :: TypeInferenceException: unknown function 'parseJSON' — no function of this name in the native or user catalog (unported platform function, or a misspelling) = 2
[v7] declined assertRoundTrip/3 :: host-unsupported = 1
[v7] declined assertSameElements/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::mapping::classMappingById' with 2 parameter(s) = 3
[v7] declined assertSameElements/2 :: TypeInferenceException: in call to 'meta::relational::tests::mapping::relation::pkOfFunc', argument 1: expected FunctionDefinition<meta::pure::metamodel::type::Any>, got Function<meta::pure::metamodel… = 7
[v7] declined assertSameElements/2 :: wall: class query under TypedMap is not resolvable yet (H2 vocabulary) = 30
[v7] declined assertSameSQL/2 :: host-partition-sqltext = 427
[v7] declined assertSchemaRoundTripEquality/2 :: host-unsupported = 1
[v7] declined assertSize/2 :: IllegalStateException: no scalar lowering registered for resolved overload 'meta::pure::mapping::_classMappingByClass' with 2 parameter(s) = 1
[v7] declined assertSize/2 :: host-partition-tdg = 26
[v7] declined assertSize/2 :: wall: class query under TypedMap is not resolvable yet (H2 vocabulary) = 2
[v7] declined assertSqlEquals/2 :: host-partition-tdg = 45
[v7] declined assertTestData/3 :: host-partition-tdg = 35
[v7] disagree-witness assertSameElements/2 host=pass prod=fail expected: ['A', 'Account 2', 'B', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [%2014-12-05T21:00:00.000000, 5]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'Anthony', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'Anthony', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['New', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'New', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['New', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'New', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['1,TDSNull,TDSNull', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4', '2,2,STOCK DESC-V4']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Oliver', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Oliver', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Elena', 'David', 'No address', 'TDSNull', 'Peter', 'John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Oliver', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Oliver', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Peter', 'John', 'John', 'Anthony', 'New', 'Don', 'Fabrice', 'Oliver', 'Elena', 'David', 'No address', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: 'Allen|Anthony,Allen|Anthony,Harris|David,Harris|David,Hill|John,Hill|John,Hill|John,Hill|John,Hill|Oliver,Hill|Oliver,Hill|Oliver,Hill|Oliver,Johnson|John,Johnson|John,Roberts|Fabrice,Roberts|Fabrice,Smith|Peter,Smith|Peter'
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [%2003-07-19T00:00:00.000000000]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [1.234D]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [1.23456D]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [%2003-07-19T00:00:00.000000000]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [1.234D]
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [1.23456D]
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [11, 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: [22, 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['David', 'Firm X', 'Ma', 'OrgName5', 'OrgName6', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail expected: ['Andrews', 'Julie', 'TDSNull', 'TDSNull', 'TDSNull', 'TDSNull']
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
[v7] disagree-witness assertEquals/2 host=pass prod=fail byte-verdict: canonical renders differ (host lattice agreed — dual-verdict divergence, see [canon] census)
```
