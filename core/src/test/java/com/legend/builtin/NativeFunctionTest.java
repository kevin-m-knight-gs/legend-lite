package com.legend.builtin;

import com.legend.model.ClassDefinition;
import com.legend.model.EnumDefinition;
import com.legend.model.FunctionDefinition.ParameterDefinition;
import com.legend.protocol.Multiplicity;
import com.legend.model.NativeFunctionDefinition;
import com.legend.protocol.TypeExpression;
import com.legend.protocol.TypeExpression.Column;
import com.legend.protocol.TypeExpression.FunctionType;
import com.legend.protocol.TypeExpression.Generic;
import com.legend.protocol.TypeExpression.NameRef;
import com.legend.protocol.TypeExpression.Op;
import com.legend.protocol.TypeExpression.RelationType;
import com.legend.protocol.TypeExpression.SchemaAlgebra;
import com.legend.protocol.TypeExpression.TypedParameter;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.legend.model.TypeExpressionFixtures.col;
import static com.legend.model.TypeExpressionFixtures.nr;
import static com.legend.model.TypeExpressionFixtures.rel;
import static com.legend.model.TypeExpressionFixtures.sa;
import static com.legend.model.TypeExpressionFixtures.tg;
import static com.legend.model.TypeExpressionFixtures.tp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog-level tests for {@link Pure} &mdash; the ported Pure stdlib of
 * native function declarations.
 *
 * <p>The class-load contract is the headline guarantee: if {@link Pure}
 * loads at all, every declared signature parsed through {@code ElementParser}.
 * The tests below pin <em>shape</em> for representative natives covering
 * every non-trivial grammar form the parser must support, so a regression
 * that changes the parsed AST (rather than rejecting the source outright)
 * fails loudly.
 */
class NativeFunctionTest {

    // ---------------------------------------------------------------
    // Catalog size + uniqueness
    // ---------------------------------------------------------------

    @Test
    void catalogMatchesTheGoldenFile() throws Exception {
        // THE golden catalog: every signature, canonically rendered, in
        // (load-bearing) declaration order. Replaces the count-pin + comment
        // changelog: any add/remove/edit/REORDER is a reviewable line diff.
        // To update deliberately: fix the code, regenerate the resource with
        // the renderer below, and review the diff in the commit.
        List<String> expected = java.nio.file.Files.readAllLines(
                        java.nio.file.Path.of("src/test/resources/native-catalog.txt"))
                .stream().filter(l -> !l.startsWith("#")).toList();
        List<String> actual = Pure.all().stream()
                .map(NativeFunctionTest::renderCanonical).toList();
        assertEquals(expected, actual,
                "the native catalog diverged from the golden file — review the diff;"
                        + " regenerate the resource only for DELIBERATE catalog changes");
    }

    /** Canonical signature rendering — the golden file's line format. */
    static String renderCanonical(NativeFunctionDefinition d) {
        StringBuilder s = new StringBuilder(d.qualifiedName());
        if (!d.typeParameters().isEmpty() || !d.multiplicityParameters().isEmpty()) {
            s.append('<').append(String.join(",", d.typeParameters()));
            if (!d.multiplicityParameters().isEmpty()) {
                s.append('|').append(String.join(",", d.multiplicityParameters()));
            }
            s.append('>');
        }
        s.append('(');
        for (int i = 0; i < d.parameters().size(); i++) {
            var p = d.parameters().get(i);
            if (i > 0) {
                s.append(", ");
            }
            s.append(p.name()).append(':').append(renderType(p.type())).append(p.multiplicity());
        }
        return s.append("):").append(renderType(d.returnType()))
                .append(d.returnMultiplicity()).toString();
    }

    private static String renderType(com.legend.protocol.TypeExpression t) {
        return switch (t) {
            case com.legend.protocol.TypeExpression.NameRef n -> n.name();
            case com.legend.protocol.TypeExpression.Generic g -> g.name() + "<"
                    + String.join(",", g.arguments().stream()
                            .map(NativeFunctionTest::renderType).toList()) + ">";
            case com.legend.protocol.TypeExpression.FunctionType f -> "{"
                    + String.join(",", f.parameters().stream()
                            .map(pp -> renderType(pp.type()) + pp.multiplicity()).toList())
                    + "->" + renderType(f.result().type()) + f.result().multiplicity() + "}";
            case com.legend.protocol.TypeExpression.RelationType r -> "("
                    + String.join(",", r.columns().stream()
                            .map(c -> c.name() + ":" + renderType(c.type())).toList()) + ")";
            case com.legend.protocol.TypeExpression.SchemaAlgebra a ->
                    renderType(a.left()) + a.op() + renderType(a.right());
        };
    }

    @Test
    void noTwoOverloadsCollapseToSameSignatureKey() {
        // Pure overloads on (qualifiedName, parameter-type+multiplicity tuple).
        // Two constants with the same key would mean either a duplicate
        // signature in the catalog or the parser flattening two distinct
        // declarations into the same record.
        Set<String> seen = new HashSet<>();
        for (NativeFunctionDefinition def : Pure.all()) {
            String key = signatureKey(def);
            assertTrue(seen.add(key),
                    () -> "duplicate overload key in Pure catalog: " + key);
        }
    }

    // ---------------------------------------------------------------
    // Full structural pins on representative natives.
    //
    // Each test below compares against a hand-built expected
    // NativeFunctionDefinition record, which exercises record-equality
    // over every field: qualifiedName, type/multiplicity params, every
    // parameter (name+type+multiplicity), returnType, returnMultiplicity,
    // stereotypes, taggedValues. Any drift fails loudly.
    // ---------------------------------------------------------------

    @Test
    void filterRelation_pinShape() {
        // filter<T>(Relation<T>[1], Function<{T[1]->Boolean[1]}>[1]):Relation<T>[1]
        TypeExpression relationOfT = tg(Pure.RELATION, nr("T"));
        TypeExpression filterFn = tg(Pure.FUNCTION, new FunctionType(
                List.of(tp(nr("T"), Multiplicity.exactly(1))),
                tp(nr(Pure.BOOLEAN), Multiplicity.exactly(1))));
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::relation::filter",
                List.of("T"),
                List.of(),
                List.of(
                        new ParameterDefinition("rel", relationOfT, Multiplicity.exactly(1)),
                        new ParameterDefinition("f", filterFn, Multiplicity.exactly(1))),
                relationOfT,
                Multiplicity.exactly(1),
                List.of(),
                List.of());
        assertEquals(expected, Pure.FILTER__RELATION_1__FUNCTION_1);
    }

    @Test
    void castWithMultiplicityParameter_pinShape() {
        // cast<T|m>(Any[m], T[1]): T[m]
        // The motivating case for Multiplicity.Parameter capture.
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::lang::cast",
                List.of("T"),
                List.of("m"),
                List.of(
                        new ParameterDefinition("source", nr(Pure.ANY),
                                new Multiplicity.Parameter("m")),
                        new ParameterDefinition("type", nr("T"), Multiplicity.exactly(1))),
                nr("T"),
                new Multiplicity.Parameter("m"),
                List.of(),
                List.of());
        assertEquals(expected, Pure.CAST__ANY_m__T_1);
    }

    @Test
    void renameWithSchemaAlgebraAndSubsetEquality_pinShape() {
        // rename<T,Z,K,V>(
        //   Relation<T>[1],
        //   ColSpec<Z=(?:K) \u2286 T>[1],
        //   ColSpec<V=(?:K)>[1]
        // ): Relation<T-Z+V>[1]
        // Wildcard column (?:K) parses as RelationType([Column("?",NameRef("K"),[1])]).
        TypeExpression wildcardOfK = rel(col("?", nr("K"), Multiplicity.exactly(1)));
        // Z=(?:K)⊆T parses left-associatively: SUBSET wraps the EQUAL
        // node, matching engine's parseTypeWithOperation precedence.
        TypeExpression zEqQK_subT = sa(
                sa(nr("Z"), Op.EQUAL, wildcardOfK),
                Op.SUBSET, nr("T"));
        // V=(?:K) has no SUBSET tail — just the EQUAL.
        TypeExpression vEqQK = sa(nr("V"), Op.EQUAL, wildcardOfK);
        // T-Z+V is left-leaning: (T-Z)+V.
        TypeExpression tMinusZPlusV = sa(
                sa(nr("T"), Op.DIFFERENCE, nr("Z")),
                Op.UNION, nr("V"));
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::relation::rename",
                List.of("T", "Z", "K", "V"),
                List.of(),
                List.of(
                        new ParameterDefinition("r",
                                tg(Pure.RELATION, nr("T")),
                                Multiplicity.exactly(1)),
                        new ParameterDefinition("old",
                                tg(Pure.COL_SPEC, zEqQK_subT),
                                Multiplicity.exactly(1)),
                        new ParameterDefinition("new",
                                tg(Pure.COL_SPEC, vEqQK),
                                Multiplicity.exactly(1))),
                tg(Pure.RELATION, tMinusZPlusV),
                Multiplicity.exactly(1),
                List.of(),
                List.of());
        assertEquals(expected, Pure.RENAME__RELATION_1__COL_SPEC_1__COL_SPEC_1);
    }

    @Test
    void ifWithEmptyArgFunctionType_pinShape() {
        // Real legend-pure: if<T|m>(Boolean[1], Function<{->T[m]}>[1], Function<{->T[m]}>[1]): T[m]
        // Exercises the empty-parameter-list function-type grammar `{->T[m]}` with a multiplicity var.
        TypeExpression thunkOfT = tg(Pure.FUNCTION, new FunctionType(
                List.of(),
                tp(nr("T"), Multiplicity.parameter("m"))));
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::lang::if",
                List.of("T"),
                List.of("m"),
                List.of(
                        new ParameterDefinition("test", nr(Pure.BOOLEAN), Multiplicity.exactly(1)),
                        new ParameterDefinition("then", thunkOfT, Multiplicity.exactly(1)),
                        new ParameterDefinition("else", thunkOfT, Multiplicity.exactly(1))),
                nr("T"),
                Multiplicity.parameter("m"),
                List.of(),
                List.of());
        assertEquals(expected, Pure.IF__BOOLEAN_1__FUNCTION_1__FUNCTION_1);
    }

    @Test
    void extendWithWindowAndNestedFunctionType_pinShape() {
        // extend<T,Z,W,R>(
        //   Relation<T>[1],
        //   _Window<T>[1],
        //   FuncColSpec<{Relation<T>[1], _Window<T>[1], T[1] -> Any[0..1]}, R>[1]
        // ): Relation<T+R>[1]
        //
        // Exercises:
        //   - underscore-prefixed type _Window
        //   - multi-arg function type with three inputs
        //   - FuncColSpec generic carrying two type arguments
        //   - schema algebra return type T+R
        NativeFunctionDefinition def =
                Pure.EXTEND__RELATION_1__WINDOW_1__FUNC_COL_SPEC_1;
        assertEquals("meta::pure::functions::relation::extend", def.qualifiedName());
        assertEquals(List.of("T", "Z", "W", "R"), def.typeParameters());
        assertEquals(List.of(), def.multiplicityParameters());
        assertEquals(3, def.parameters().size());
        assertEquals(tg(Pure.WINDOW, nr("T")), def.parameters().get(1).type());
        // Structural pin of the nested function type with three input arrows.
        TypeExpression innerFn = new FunctionType(
                List.of(
                        tp(tg(Pure.RELATION, nr("T")), Multiplicity.exactly(1)),
                        tp(tg(Pure.WINDOW, nr("T")), Multiplicity.exactly(1)),
                        tp(nr("T"), Multiplicity.exactly(1))),
                tp(nr(Pure.ANY), Multiplicity.range(0, 1)));
        assertEquals(tg(Pure.FUNC_COL_SPEC, innerFn, nr("R")),
                def.parameters().get(2).type());
        // Return type Relation<T+R> = Generic(Relation, [SchemaAlgebra(T, UNION, R)]).
        assertEquals(tg(Pure.RELATION, sa(nr("T"), Op.UNION, nr("R"))),
                def.returnType());
        assertEquals(Multiplicity.exactly(1), def.returnMultiplicity());
    }

    @Test
    void sortWithSubsetConstraintMultiplicityMany_pinShape() {
        // sort<X,T>(Relation<T>[1], SortInfo<X \u2286 T>[*]): Relation<T>[1]
        TypeExpression relationOfT = tg(Pure.RELATION, nr("T"));
        TypeExpression sortInfoXsubT = tg(Pure.SORT_INFO,
                sa(nr("X"), Op.SUBSET, nr("T")));
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::relation::sort",
                List.of("X", "T"),
                List.of(),
                List.of(
                        new ParameterDefinition("rel", relationOfT, Multiplicity.exactly(1)),
                        new ParameterDefinition("sortInfo", sortInfoXsubT, Multiplicity.zeroMany())),
                relationOfT,
                Multiplicity.exactly(1),
                List.of(),
                List.of());
        assertEquals(expected, Pure.SORT__RELATION_1__SORT_INFO_MANY);
    }

    @Test
    void zeroArityNative_pinShape() {
        // generateGuid(): String[1]
        var expected = new NativeFunctionDefinition(
                "meta::pure::functions::string::generation::generateGuid",
                List.of(),
                List.of(),
                List.of(),
                nr(Pure.STRING),
                Multiplicity.exactly(1),
                List.of(),
                List.of());
        assertEquals(expected, Pure.GENERATE_GUID);
    }

    // ---------------------------------------------------------------
    // Multiplicity-parameter sweep: every native that declares a |m
    // parameter must capture it as Multiplicity.Parameter, NOT silently
    // coerce to a concrete multiplicity. This was the original bug that
    // motivated the sealed Multiplicity type.
    // ---------------------------------------------------------------

    @Test
    void everyMultiplicityParameterIsCapturedStructurally() {
        for (NativeFunctionDefinition def : Pure.all()) {
            if (def.multiplicityParameters().isEmpty()) continue;
            // Find at least one Parameter multiplicity somewhere in the
            // signature; otherwise the |m declaration was orphaned and the
            // signature is dishonest.
            boolean usesParam =
                    def.returnMultiplicity() instanceof Multiplicity.Parameter
                            || def.parameters().stream()
                                    .anyMatch(p -> p.multiplicity() instanceof Multiplicity.Parameter);
            assertTrue(usesParam,
                    () -> "native '" + def.qualifiedName()
                            + "' declares multiplicityParameters="
                            + def.multiplicityParameters()
                            + " but no parameter or return references them: "
                            + def);
            // And every Parameter multiplicity name must be declared in the
            // signature's |m,n list — the parser must not invent names.
            checkParameterNamesDeclared(def);
        }
    }

    private static void checkParameterNamesDeclared(NativeFunctionDefinition def) {
        Set<String> declared = Set.copyOf(def.multiplicityParameters());
        if (def.returnMultiplicity() instanceof Multiplicity.Parameter p) {
            assertTrue(declared.contains(p.name()),
                    () -> "return multiplicity references undeclared parameter '"
                            + p.name() + "' in " + def.qualifiedName());
        }
        for (var param : def.parameters()) {
            if (param.multiplicity() instanceof Multiplicity.Parameter p) {
                assertTrue(declared.contains(p.name()),
                        () -> "parameter '" + param.name()
                                + "' multiplicity references undeclared parameter '"
                                + p.name() + "' in " + def.qualifiedName());
            }
        }
    }

    // ---------------------------------------------------------------
    // Coverage spot-checks: the headline natives we expect downstream
    // consumers (TypeChecker, lowering, checkers) to reach for. If any
    // of these go missing from the catalog, the build fails loudly here
    // rather than at first use much later.
    // ---------------------------------------------------------------

    @Test
    void headlineNativesAreAllPresent() {
        Set<String> simpleNames = Pure.all().stream()
                .map(d -> simpleName(d.qualifiedName()))
                .collect(Collectors.toSet());
        for (String required : List.of(
                "filter", "sort", "distinct", "select", "rename", "extend",
                "concatenate", "size", "groupBy", "join", "project",
                "map", "fold", "exists", "forAll", "at", "first", "last",
                "if", "match", "eval", "cast", "instanceOf",
                "getAll", "type", "letFunction",
                "plus", "minus", "times", "divide", "equal", "lessThan",
                "and", "or", "not",
                "graphFetch", "serialize", "generateGuid")) {
            assertTrue(simpleNames.contains(required),
                    () -> "required native '" + required
                            + "' missing from Pure catalog");
        }
    }

    // ===============================================================
    // Native class catalog ({@link Pure#allNativeClasses}).
    //
    // Same shape contract as the function catalog: class-load fails
    // loudly if any declaration stops parsing, and the tests below pin
    // structural invariants (hierarchy edges, type-parameter arity,
    // headline presence, the isNative flag).
    // ===============================================================

    @Test
    void nativeClassCatalogSizeIsPinned() {
        // Update this deliberately when adding or removing native classes.
        // 37: +StrictTime (real legend-pure meta::pure::metamodel::type::StrictTime).
        // 44: +DatabaseConnection/TestDatabaseConnection/ResultSet (the
        // engine's relational-runtime surface — K-phase executeInDb natives).
        // 47: +Runtime/ConnectionStore/Connection (the real runtime.pure trio).
        // 48: +Database (the store metaclass — database refs in value position).
        // 49: +Row (ResultSet introspection in setup functions).
        // 52: +DebugContext (B2b: the debug-arity execute calls type
        //     against the REAL legend-pure platform tools surface).
        // 62: task #78 step-1 declarations, all cited in Pure.java —
        //     +Store (platform_dsl_store/grammar/store.pure:18),
        //     +ModelStore (modelToModel.pure:37),
        //     +GenerationFeaturesConfig (relationalRuntimeExtension.pure:15),
        //     +Mapping metaclass (platform_dsl_mapping/grammar/mapping.pure:26),
        //     +Table metaclass (platform_store_relational/grammar/relational.pure:92),
        //     +TabularDataSet/TDSColumn/TDSRow (core/pure/tds/tds.pure:18/25/76),
        //     +AggregationAwareActivity (aggregationAware.pure:36).
        // 63: +Schema metaclass (platform_store_relational relational.pure;
        //     the schema()/table() metamodel navigation pair).
        // 64: +AlloySerializationConfig (graphFetch.pure:89 — serialize's
        //     config carrier; all-false = NOP, flags wall in the resolver).
        // 67: +FunctionDefinition/ConcreteFunctionDefinition/LambdaFunction
        //     (the m3 function-carrier hierarchy under Function<F> — corpus
        //     annotations like LambdaFunction<{->TabularDataSet[1]}>).
        // 70: +PureModelConnection/JsonModelConnection/ModelChainConnection
        //     (XStore leg slice 0 — real modelToModel.pure:43/:58/:82).
        // 72: +ExecutionContext/Extension (#46 _Alloy subfamily — bare
        //     defaults carriers named by corpus signatures).
        // 73: +ExecutionPlan (real executionPlan.pure:60-73 — the plan
        //     surface's processingTemplateFunctions property).
        // 75: +Checked/Defect (real dataQuality.pure:39/:20 — the
        //     graphFetchChecked envelope carriers, opaque).
        // 80: +ExecutionNode/FunctionParametersValidationNode/
        //     FunctionParameter/SQLExecutionNode/
        //     RelationalInstantiationExecutionNode (the plan NODE surface
        //     — real executionPlan.pure:73-205 + relational :63-90).
        // 81: +SelectSQLQuery (relationalRuntime.pure post-processor hook
        //     parameter type — opaque; recognized pps apply over OUR IR).
        // 86: +PostProcessorParameter/PostProcessor/PostProcessorWith-
        //     Parameter (relationalRuntime.pure:46-70) + alloy
        //     PostProcessor + ExtractSubQueriesAsCTEsPostProcessor
        //     (cteExtractionPostProcessor.pure:26).
        // 90: +View/ColumnMapping/RelationalOperationElement/DataType
        //     (relational.pure:114-137 — the typeInference metamodel
        //     surface, host-evaluated over our DatabaseDefinition).
        // 95: +SetImplementation/InstanceSetImplementation/
        //     RootRelationalInstanceSetImplementation/PropertyMapping/
        //     RelationalPropertyMapping (mapping-side inference nav).
        // 98: +DynaFunction/Literal/LiteralList (constructed relational
        //     ops in the inference tests).
        // 108: +Node/Expression/QualifiedName/QualifiedNameReference/
        //     ModelConversionState/Alias/TableAlias/Column/ColumnName/
        //     TableAliasColumn (the toPostgresModel bridge surface).
        // 109: +TableAliasColumnName (pureToSQLQuery/metamodel.pure:66).
        // 138: +the postgres SQL-protocol node surface (metamodel.pure —
        //     Statement/Relation/QueryBody/SelectItem/Literal family/
        //     FunctionCall/Logical/Comparison/predicates/Join/Query/
        //     Select/columns — bridge batch 2).
        // 141: +DateLiteral/TimestampLiteral/SQLNull (literal tests).
        // 156: +SortItem/WindowFrame/Cast/ColumnType + extension
        //     placeholders (TablePlaceholder/InClauseVariablePlaceholder)
        //     + relational Window/SortByInfo/WindowColumn +
        //     TdsSelectSqlQuery/TabularFunction + pureToSqlQuery
        //     VarPlaceHolder/VarSetPlaceHolder/VarCrossSetPlaceHolder +
        //     CrossSetImplementation (bridge batch 4).
        // 172: +QuerySpecification/ExtendedQuerySpecification/WithQuery/
        //     With/QueryWithScope/sql-Union + relational
        //     RelationalTreeNode/RootJoinTreeNode/JoinTreeNode/OrderBy/
        //     CommonTableExpression(+Reference)/JoinStrings/Union/
        //     UnionAll/RelationalOperationElementWithJoin (the
        //     query-level dialect-conversion surface, bridge batch 5).
        // 177: +RelationalDatabaseConnection + DatasourceSpecification/
        //     LocalH2DatasourceSpecification + AuthenticationStrategy/
        //     DefaultH2AuthenticationStrategy + TestDatabaseAuthentication-
        //     Strategy (the alloy connection forms corpus getConnection()/
        //     m2m2r runtime() helpers construct — connection.pure:29)
        // 181: +PropertyMappingsImplementation (mapping.pure:68) +
        //     RelationalMappingSpecification (relational.pure:105) +
        //     RelationalInstanceSetImplementation (relationalMapping
        //     .pure:26) — the extends-chain navigation hierarchy;
        //     RootRelational keeps the real two-parent shape.
        // 186: +DatabaseMapper/SchemaMapper/TableMapper/RelationalMapper/
        //     RelationalMapperPostProcessor (relationalMapper rename
        //     surface — corpus metamodel.pure:185-208 + postprocessor
        //     .pure:40-43).
        // 188: +relation::Relation/NamedRelation (relational.pure:45/:50
        //     — the store-relation chain Table sits under; TableTDS'
        //     defining file compiles in 42 corpus modules again).
        // 197: taxonomy T2 — +EngineRuntime, MultiExecutionContext,
        //     ExecutionOption, ExecutionOptionContext, relation::TDS,
        //     relational DataSource, RelationalActivity,
        //     GlobalGraphFetchExecutionNode,
        //     StoreMappingGlobalGraphFetchExecutionNode (all grounded in
        //     engine/legend-pure declarations; see PLAN_SURFACE map).
        // 200: +GenericType (genericType().rawType reflection) +
        // TDSNull (the null-cell TYPE for match arms; value stays sqlNull) +
        //     ElementOverride (M3 Any.elementOverride, folded empty).
        // 199: -_Traversal (2026-08-14 — the old engine-lite traverse
        // machinery deleted; navigate subsumed it)
        assertEquals(199, Pure.allNativeClasses().size(),
                "Pure.allNativeClasses() size pin: review the catalog if this changes");
    }

    private static final java.util.Map<String, List<String>> RUNTIME_SURFACE_PROPERTIES =
            java.util.Map.ofEntries(
                    java.util.Map.entry(
                    "meta::core::runtime::ConnectionStore", List.of("connection", "element")),
                    java.util.Map.entry(
                    "meta::external::store::relational::runtime::RelationalDatabaseConnection",
                    // real connection.pure:29-34 (the alloy connection form
                    // corpus getConnection() helpers construct)
                    List.of("datasourceSpecification", "authenticationStrategy",
                            "postProcessors")),
                    java.util.Map.entry(
                    "meta::pure::alloy::connections::alloy::specification::LocalH2DatasourceSpecification",
                    // real datasourceSpecification.pure:34-39
                    List.of("testDataSetupCsv", "testDataSetupSqls",
                            "disableDatabaseToUpper")),
                    java.util.Map.entry(
                    "meta::pure::graphFetch::execution::AlloySerializationConfig",
                    // real graphFetch.pure:89-111 (serialize config carrier)
                    List.of("typeKeyName", "includeType", "includeEnumType",
                            "dateTimeFormat", "removePropertiesWithNullValues",
                            "removePropertiesWithEmptySets",
                            "fullyQualifiedTypePath", "includeObjectReference")),
                    java.util.Map.entry(
                    "meta::core::runtime::Runtime", List.of("connectionStores")),
                    java.util.Map.entry(
                    "meta::external::store::relational::runtime::DatabaseConnection",
                    // real relationalRuntime.pure:26-48 (queryGenerationConfigs
                    // :48 — the removeUnionOrJoins feature-toggle surface)
                    // + the post-processor hooks (relationalRuntime.pure
                    // :40-42): recognized shapes apply over OUR SQL IR
                    List.of("type", "debug", "timeZone", "quoteIdentifiers",
                            "queryTimeOutInSeconds", "queryGenerationConfigs",
                            "queryPostProcessorsWithParameter",
                            "sqlQueryPostProcessors",
                            "sqlQueryPostProcessorsConnectionAware")),
                    java.util.Map.entry(
                    "meta::external::store::relational::runtime::TestDatabaseConnection",
                    List.of("testDataSetupCsv")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::View",
                    List.of("columnMappings")),
                    java.util.Map.entry(
                    "meta::relational::mapping::ColumnMapping",
                    List.of("columnName", "relationalOperationElement")),
                    java.util.Map.entry(
                    "meta::relational::mapping::RelationalPropertyMapping",
                    List.of("relationalOperationElement")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::DateLiteral",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::TimestampLiteral",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::StringLiteral",
                    List.of("value", "quoted")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::IntegerLiteral",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::BooleanLiteral",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::DoubleLiteral",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::FunctionCall",
                    List.of("name", "distinct", "arguments", "filter", "window")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::LogicalBinaryExpression",
                    List.of("type", "left", "right")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::IsNullPredicate",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::IsNotNullPredicate",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::InListExpression",
                    List.of("values")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::InPredicate",
                    List.of("value", "valueList")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::ComparisonExpression",
                    List.of("left", "right", "operator")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::AliasedRelation",
                    List.of("relation", "alias", "columnNames")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Table",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::TableFunction",
                    List.of("functionCall")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::TableSubquery",
                    List.of("query")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Join",
                    List.of("type", "left", "right", "criteria")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::JoinOn",
                    List.of("expression")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Query",
                    List.of("queryBody")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::SingleColumn",
                    List.of("alias", "expression")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Select",
                    List.of("distinct", "selectItems")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::QualifiedName",
                    List.of("parts")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::QualifiedNameReference",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::NamedRelation",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::Table",
                    List.of("columns", "schema")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Schema",
                    List.of("tables", "views", "name", "database")),
                    java.util.Map.entry(
                    "meta::pure::store::Store",
                    List.of("includes", "name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::DatabaseMapper",
                    List.of("database", "schemas")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::SchemaMapper",
                    List.of("from", "to")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::TableMapper",
                    List.of("from", "to")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::RelationalMapper",
                    List.of("databaseMappers", "schemaMappers",
                            "tableMappers")),
                    java.util.Map.entry(
                    "meta::pure::alloy::connections::RelationalMapperPostProcessor",
                    List.of("relationalMappers")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Alias",
                    List.of("name", "relationalElement")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::TableAlias",
                    List.of("schema")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Column",
                    List.of("name", "type")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::ColumnName",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::TableAliasColumn",
                    List.of("columnName", "alias", "column")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::TableAliasColumnName",
                    List.of("alias", "columnName")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::DynaFunction",
                    List.of("name", "parameters")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Literal",
                    List.of("value")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::LiteralList",
                    List.of("values")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Database",
                    List.of("schemas")),
                    java.util.Map.entry(
                    "meta::relational::runtime::PostProcessor",
                    // relationalRuntime.pure:63-70 (stored props only —
                    // quals are engine-side conveniences)
                    List.of("sqlQueryPostProcessorForExecution",
                            "sqlQueryPostProcessorForPlan")),
                    java.util.Map.entry(
                    "meta::relational::runtime::PostProcessorWithParameter",
                    List.of("postProcessor", "parameters")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::execute::ResultSet",
                    List.of("executionTimeInNanoSecond",
                            "connectionAcquisitionTimeInNanoSecond",
                            "executionPlanInformation", "columnNames", "rows")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::execute::Row",
                    List.of("values", "parent")),
                    java.util.Map.entry(
                    // B2a (real pure: platform_dsl_mapping/result.pure)
                    "meta::pure::mapping::Result",
                    List.of("values", "activities")),
                    java.util.Map.entry(
                    // B2b (real pure: platform/pure/tools.pure)
                    "meta::pure::tools::DebugContext",
                    List.of("debug", "space")),
                    java.util.Map.entry(
                    // task #78 step-1 (cites in Pure.java):
                    // relationalRuntimeExtension.pure:23-26
                    "meta::external::store::relational::runtime::GenerationFeaturesConfig",
                    List.of("enabled", "disabled")),
                    // bridge batch 4 (cites in Pure.java)
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Window",
                    List.of("windowRef", "partitions", "orderBy",
                            "windowFrame")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::SortItem",
                    List.of("sortKey", "ordering", "nullOrdering")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Cast",
                    List.of("expression", "type")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::ColumnType",
                    List.of("name", "parameters")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::extension::TablePlaceholder",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::extension::InClauseVariablePlaceholder",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::Window",
                    List.of("partition", "sortBy")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::SortByInfo",
                    List.of("sortByElement", "sortDirection")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::WindowColumn",
                    List.of("columnName", "window", "func")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::TabularFunction",
                    List.of("name", "schema")),
                    java.util.Map.entry(
                    "meta::relational::functions::pureToSqlQuery::metamodel::VarPlaceHolder",
                    List.of("name", "propertyPath", "type", "multiplicity")),
                    java.util.Map.entry(
                    "meta::relational::functions::pureToSqlQuery::metamodel::VarSetPlaceHolder",
                    List.of("varName")),
                    java.util.Map.entry(
                    "meta::relational::functions::pureToSqlQuery::metamodel::VarCrossSetPlaceHolder",
                    List.of("varName", "crossSetImplementation")),
                    java.util.Map.entry(
                    "meta::pure::router::clustering::CrossSetImplementation",
                    List.of("targetStore", "varName")),
                    java.util.Map.entry(
                    "meta::pure::mapping::SetImplementation",
                    List.of("root", "id", "parent",
                            "superSetImplementationId")),
                    java.util.Map.entry(
                    "meta::pure::mapping::InstanceSetImplementation",
                    List.of("class")),
                    java.util.Map.entry(
                    "meta::relational::mapping"
                            + "::RelationalInstanceSetImplementation",
                    List.of("primaryKey")),
                    // bridge batch 5: the query-level conversion surface
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::QuerySpecification",
                    List.of("select", "from", "where", "groupBy", "having",
                            "orderBy", "limit", "offset")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::extension::ExtendedQuerySpecification",
                    List.of("qualify")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::WithQuery",
                    List.of("name", "columns", "query")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::With",
                    List.of("withQueries")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::QueryWithScope",
                    List.of("with", "queryBody")),
                    java.util.Map.entry(
                    "meta::external::query::sql::metamodel::Union",
                    List.of("left", "right", "distinct")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::SelectSQLQuery",
                    List.of("columns", "distinct", "data",
                            "filteringOperation", "groupBy",
                            "havingOperation", "qualifyOperation",
                            "orderBy", "fromRow", "toRow",
                            "commonTableExpressions")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::join::RelationalTreeNode",
                    List.of("alias", "childrenData")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::join::JoinTreeNode",
                    List.of("setMappingOwner", "database", "joinName",
                            "join", "joinType", "lateral")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::OrderBy",
                    List.of("column", "direction")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::CommonTableExpression",
                    List.of("name", "sqlQuery")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::CommonTableExpressionReference",
                    List.of("name")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::operation::JoinStrings",
                    List.of("strings", "prefix", "separator", "suffix")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::relation::Union",
                    List.of("currentTreeNodes", "setImplementations",
                            "queries")),
                    java.util.Map.entry(
                    "meta::relational::metamodel::RelationalOperationElementWithJoin",
                    List.of("relationalOperationElement", "joinTreeNode")),
                    java.util.Map.entry(
                    "meta::relational::functions::toPostgresModel::ModelConversionState",
                    List.of("isRootSelect", "processingSelect",
                            "processingFilter", "extensions",
                            "dynaFunctionConverterMap")));

    /** The plan surface (real executionPlan.pure:60-205 + relational
     * executionPlan.pure:63-90 — declared subsets). */
    private static final java.util.Map<String, List<String>> PLAN_SURFACE_PROPERTIES =
            java.util.Map.of(
                    "meta::pure::executionPlan::ExecutionPlan",
                    List.of("rootExecutionNode", "processingTemplateFunctions"),
                    "meta::pure::executionPlan::ExecutionNode",
                    List.of("executionNodes"),
                    "meta::pure::executionPlan::FunctionParametersValidationNode",
                    List.of("functionParameters"),
                    "meta::pure::executionPlan::FunctionParameter",
                    List.of("name", "supportsStream"),
                    "meta::relational::mapping::SQLExecutionNode",
                    List.of("sqlQuery"),
                    // taxonomy T2 additions — real engine sources:
                    // runtime.pure (EngineRuntime.mappings),
                    // executionPlan_generation.pure (MultiExecutionContext,
                    // ExecutionOptionContext),
                    // platform_store_relational/functions.pure:128
                    // (RelationalActivity)
                    "meta::core::runtime::EngineRuntime",
                    List.of("mappings"),
                    "meta::pure::executionPlan::MultiExecutionContext",
                    List.of("childExecutionContext"),
                    "meta::pure::executionPlan::ExecutionOptionContext",
                    List.of("executionOptions"),
                    "meta::relational::mapping::RelationalActivity",
                    List.of("sql", "comment", "executionTimeInNanoSecond",
                            "sqlGenerationTimeInNanoSecond",
                            "connectionAcquisitionTimeInNanoSecond",
                            "executionPlanInformation", "dataSource"));

    /** XStore leg slice 0 (real core/pure/mapping/modelToModel.pure:58/:82). */
    private static final java.util.Map<String, List<String>> STORE_MODEL_SURFACE_PROPERTIES =
            java.util.Map.of(
                    "meta::external::store::model::JsonModelConnection",
                    List.of("class", "url"),
                    "meta::external::store::model::ModelChainConnection",
                    List.of("mappings"),
                    // real m3 generics.pure: rawType is the reflection
                    // surface ($x->genericType().rawType — testGetAll)
                    "meta::pure::metamodel::type::generics::GenericType",
                    List.of("rawType"));

    /** task #78 step-1 TDS + aggregationAware surfaces (Map.of caps at 10
     * pairs — second map, same contract). */
    private static final java.util.Map<String, List<String>> TDS_SURFACE_PROPERTIES =
            java.util.Map.of(
                    // real m3: name rides PackageableElement (the corpus
                    // constructs the empty sentinel ^Mapping(name = ''))
                    "meta::pure::mapping::Mapping", List.of("name"),
                    // core/pure/tds/tds.pure:18-23
                    "meta::pure::tds::TabularDataSet", List.of("columns", "rows"),
                    // tds.pure:25-45
                    "meta::pure::tds::TDSColumn", List.of("offset", "name"),
                    // tds.pure:76-80
                    "meta::pure::tds::TDSRow", List.of("parent", "values"),
                    // aggregationAware.pure:36-39
                    "meta::pure::mapping::aggregationAware::AggregationAwareActivity",
                    List.of("rewrittenQuery"));

    @Test
    void everyNativeClassIsMarkedNativeAndHasEmptyBodyOutsideTheDocumentedSurface() {
        // Opaque carriers — EXCEPT where real legend-pure declares properties:
        // Pair has first/second (anonymousCollections.pure:17-25), which property
        // access and ^Pair(...) construction validate against.
        for (ClassDefinition c : Pure.allNativeClasses()) {
            assertTrue(c.isNative(),
                    () -> "native class '" + c.qualifiedName() + "' has isNative=false");
            if (c.qualifiedName().equals("meta::pure::functions::collection::Pair")) {
                assertEquals(List.of("first", "second"),
                        c.properties().stream().map(p -> p.name()).toList(),
                        "Pair declares exactly first/second (real pure)");
            } else if (c.qualifiedName().equals("meta::pure::functions::collection::List")) {
                assertEquals(List.of("values"),
                        c.properties().stream().map(p -> p.name()).toList(),
                        "List declares exactly values (real pure anonymousCollections.pure:33-35)");
            } else if (TDS_SURFACE_PROPERTIES.containsKey(c.qualifiedName())) {
                assertEquals(TDS_SURFACE_PROPERTIES.get(c.qualifiedName()),
                        c.properties().stream().map(p -> p.name()).toList(),
                        () -> c.qualifiedName() + " must match real legend-pure");
            } else if (RUNTIME_SURFACE_PROPERTIES.containsKey(c.qualifiedName())) {
                // the relational-runtime surface (K-natives arc): properties
                // as REAL legend-pure declares them — runtime.pure:17-32,
                // relationalRuntime.pure:26-105, functions.pure:50-65
                assertEquals(RUNTIME_SURFACE_PROPERTIES.get(c.qualifiedName()),
                        c.properties().stream().map(p -> p.name()).toList(),
                        () -> c.qualifiedName() + " must match real legend-pure");
            } else if (STORE_MODEL_SURFACE_PROPERTIES.containsKey(c.qualifiedName())) {
                assertEquals(STORE_MODEL_SURFACE_PROPERTIES.get(c.qualifiedName()),
                        c.properties().stream().map(p -> p.name()).toList(),
                        () -> c.qualifiedName() + " must match real legend-pure");
            } else if (PLAN_SURFACE_PROPERTIES.containsKey(c.qualifiedName())) {
                assertEquals(PLAN_SURFACE_PROPERTIES.get(c.qualifiedName()),
                        c.properties().stream().map(p -> p.name()).toList(),
                        () -> c.qualifiedName() + " must match real legend-pure");
            } else {
                assertTrue(c.properties().isEmpty(),
                        () -> "native class '" + c.qualifiedName()
                                + "' must have empty properties for now (got "
                                + c.properties() + ")");
            }
            if (c.qualifiedName().equals(
                    "meta::relational::metamodel::execute::Row")) {
                // real platform_store_relational/functions.pure:65 —
                // value(name) lifts on demand (FunctionCompiler's
                // native-catalog derived-property arm)
                assertEquals(List.of("value"),
                        c.derivedProperties().stream()
                                .map(dp -> dp.name()).toList(),
                        "Row declares exactly value(name) (real pure)");
            } else {
                assertTrue(c.derivedProperties().isEmpty(),
                        () -> "native class '" + c.qualifiedName()
                                + "' must have empty derived properties");
            }
            assertTrue(c.constraints().isEmpty(),
                    () -> "native class '" + c.qualifiedName()
                            + "' must have empty constraints");
        }
    }

    @Test
    void everyNativeClassHasUniqueFqn() {
        Set<String> seen = new HashSet<>();
        for (ClassDefinition c : Pure.allNativeClasses()) {
            assertTrue(seen.add(c.qualifiedName()),
                    () -> "duplicate native class FQN: " + c.qualifiedName());
        }
    }

    @Test
    void numericTowerHierarchyIsCorrect() {
        // Integer/Float/Decimal extend Number; Number extends Any.
        assertEquals(List.of(nr(Pure.ANY)), Pure.NUMBER.superClasses());
        assertEquals(List.of(nr(Pure.NUMBER)), Pure.INTEGER.superClasses());
        assertEquals(List.of(nr(Pure.NUMBER)), Pure.FLOAT.superClasses());
        assertEquals(List.of(nr(Pure.NUMBER)), Pure.DECIMAL.superClasses());
    }

    @Test
    void dateHierarchyIsCorrect() {
        // Date extends Any; StrictDate/DateTime/LatestDate extend Date.
        assertEquals(List.of(nr(Pure.ANY)),  Pure.DATE.superClasses());
        assertEquals(List.of(nr(Pure.DATE)), Pure.STRICT_DATE.superClasses());
        assertEquals(List.of(nr(Pure.DATE)), Pure.DATE_TIME.superClasses());
        assertEquals(List.of(nr(Pure.DATE)), Pure.LATEST_DATE.superClasses());
    }

    @Test
    void anyHasNoSuperclass() {
        // Top of the hierarchy: Any must have no supers.
        assertTrue(Pure.ANY.superClasses().isEmpty(),
                () -> "Any must have no superclasses, got " + Pure.ANY.superClasses());
    }

    @Test
    void parameterizedNativeClassesCarryTypeParameters() {
        // Single-parameter generics.
        assertEquals(List.of("T"), Pure.RELATION.typeParams());
        assertEquals(List.of("T"), Pure.COL_SPEC.typeParams());
        assertEquals(List.of("T"), Pure.COL_SPEC_ARRAY.typeParams());
        assertEquals(List.of("T"), Pure.WINDOW.typeParams());
        assertEquals(List.of("T"), Pure.SORT_INFO.typeParams());
        assertEquals(List.of("F"), Pure.FUNCTION.typeParams());
        // Two-parameter generics.
        assertEquals(List.of("F", "R"), Pure.FUNC_COL_SPEC.typeParams());
        assertEquals(List.of("F", "R"), Pure.FUNC_COL_SPEC_ARRAY.typeParams());
        // Three-parameter generics.
        assertEquals(List.of("F", "U", "R"), Pure.AGG_COL_SPEC.typeParams());
        assertEquals(List.of("F", "U", "R"), Pure.AGG_COL_SPEC_ARRAY.typeParams());
    }

    @Test
    void nonParameterizedNativeClassesHaveNoTypeParameters() {
        // The primitives, Any/Type/Nil, and _Traversal carry no type
        // parameters. Pinning this catches accidental drift where a
        // declaration grows an unintended <T>.
        for (ClassDefinition c : List.of(
                Pure.ANY, Pure.TYPE, Pure.NIL,
                Pure.NUMBER, Pure.INTEGER, Pure.FLOAT, Pure.DECIMAL,
                Pure.STRING, Pure.BOOLEAN, Pure.BYTE,
                Pure.DATE, Pure.STRICT_DATE, Pure.DATE_TIME,
                Pure.LATEST_DATE)) {
            assertTrue(c.typeParams().isEmpty(),
                    () -> "expected no type params on " + c.qualifiedName()
                            + ", got " + c.typeParams());
        }
    }

    @Test
    void headlineNativeClassesAreAllPresent() {
        Set<String> simpleNames = Pure.allNativeClasses().stream()
                .map(c -> simpleName(c.qualifiedName()))
                .collect(Collectors.toSet());
        for (String required : List.of(
                "Any", "Nil", "Type",
                "Number", "Integer", "Float", "Decimal",
                "String", "Boolean", "Byte",
                "Date", "StrictDate", "DateTime", "LatestDate",
                "Relation", "ColSpec", "FuncColSpec", "AggColSpec",
                "Function",
                "_Window", "SortInfo")) {
            assertTrue(simpleNames.contains(required),
                    () -> "required native class '" + required
                            + "' missing from Pure.allNativeClasses()");
        }
    }

    @Test
    void nativeClassFqnsAreInExpectedPackages() {
        // Every native FQN must live under one of the documented packages;
        // a typo or stray declaration that leaks elsewhere is a bug.
        List<String> expected = List.of(
                Pure.TYPE_PKG, Pure.RELATION_PKG, Pure.FUNCTION_PKG,
                Pure.RELATION_FUNCTIONS_PKG, Pure.COLLECTION_PKG,
                Pure.MATH_UTILITY_PKG, Pure.VARIANT_PKG, Pure.GRAPH_FETCH_PKG,
                // ModelElement lives directly under meta::pure::metamodel
                // (real M3's package tree root element).
                "meta::pure::metamodel",
                // the engine's relational-runtime surface (K-phase natives)
                "meta::external::store::relational::runtime",
                "meta::relational::metamodel::execute",
                // B2a: the execute()/Result typing surface
                "meta::pure::mapping",
                // B2b: the debug-context surface (platform tools.pure)
                "meta::pure::tools",
                "meta::core::runtime",
                // the store metaclass lives directly under meta::relational::metamodel
                "meta::relational::metamodel",
                // task #78 step-1 surfaces
                "meta::pure::store",
                "meta::external::store::model",
                // #46: bare defaults carriers (ExecutionContext/Extension)
                "meta::pure::runtime",
                "meta::pure::extension",
                "meta::pure::tds",
                "meta::relational::metamodel::relation",
                // the plan surface (#47: ExecutionPlan)
                "meta::pure::executionPlan",
                // the checked-result surface (graphFetchChecked)
                "meta::pure::dataQuality",
                // the relational plan-node surface (SQLExecutionNode)
                "meta::relational::mapping",
                // the connection post-processor chain (relationalRuntime
                // .pure:46-70 + cteExtractionPostProcessor.pure:26)
                "meta::relational::runtime",
                "meta::pure::alloy::connections",
                "meta::relational::postProcessor::cteExtraction",
                // the inference DataType surface (relational.pure datatype)
                "meta::relational::metamodel::datatype",
                // the standalone-SQL bridge surfaces
                "meta::external::query::sql::metamodel",
                "meta::relational::functions::toPostgresModel",
                // bridge batch 4: extension placeholders + pureToSqlQuery
                // plan-time metamodel + cross-store set impls
                "meta::external::query::sql::metamodel::extension",
                "meta::relational::functions::pureToSqlQuery::metamodel",
                "meta::pure::router::clustering");
        for (ClassDefinition c : Pure.allNativeClasses()) {
            String fqn = c.qualifiedName();
            boolean ok = expected.stream().anyMatch(p -> fqn.startsWith(p + "::"));
            assertTrue(ok, () -> "native class FQN outside expected packages: " + fqn);
        }
    }

    // ===============================================================
    // Native enum catalog ({@link Pure#allNativeEnums}).
    //
    // Engine declares several stdlib types as {@code Enum} rather than
    // {@code Class} (DurationUnit, JoinKind, ...). They round-trip
    // through {@link ElementParser} the same way native classes do.
    // ===============================================================

    @Test
    void nativeEnumCatalogSizeIsPinned() {
        // Update this deliberately when adding or removing native enums.
        // 13: +DatabaseType (relationalRuntime.pure:21).
        // 14: +executionPlan features::Feature (executionPlanFeature.pure:21
        //     — withFeatureFlags is identity; the flag enum types the call).
        // 17: +JoinType/LogicalBinaryType/ComparisonOperator (postgres
        //     metamodel.pure — the bridge node enums).
        // 19: +SortItemOrdering/SortItemNullOrdering (postgres
        //     metamodel.pure:511/517).
        assertEquals(19, Pure.allNativeEnums().size(),
                "Pure.allNativeEnums() size pin: review the catalog if this changes");
    }

    @Test
    void everyNativeEnumHasAtLeastOneValueAndUniqueValues() {
        for (EnumDefinition e : Pure.allNativeEnums()) {
            assertFalse(e.values().isEmpty(),
                    () -> "native enum '" + e.qualifiedName() + "' has no values");
            Set<String> seen = new HashSet<>(e.values());
            assertEquals(e.values().size(), seen.size(),
                    () -> "native enum '" + e.qualifiedName()
                            + "' has duplicate values: " + e.values());
        }
    }

    @Test
    void everyNativeEnumHasUniqueFqn() {
        Set<String> seen = new HashSet<>();
        for (EnumDefinition e : Pure.allNativeEnums()) {
            assertTrue(seen.add(e.qualifiedName()),
                    () -> "duplicate native enum FQN: " + e.qualifiedName());
        }
    }

    @Test
    void headlineNativeEnumValuesArePinned() {
        // Spot-check the enums most consumers reach for. If engine extends
        // any of these we'll catch it here before downstream code breaks.
        assertEquals(List.of("ASC", "DESC"), Pure.SORT_TYPE.values());
        assertEquals(List.of("LEFT", "RIGHT", "FULL", "INNER"), Pure.JOIN_KIND.values());
        assertEquals(List.of("MD5", "SHA1", "SHA256"), Pure.HASH_TYPE.values());
        assertEquals(List.of("Q1", "Q2", "Q3", "Q4"), Pure.QUARTER.values());
        assertEquals(10, Pure.DURATION_UNIT.values().size(),
                "DurationUnit has YEARS..NANOSECONDS = 10 values");
        assertEquals(12, Pure.MONTH.values().size(),
                "Month has January..December = 12 values");
        assertEquals(7, Pure.DAY_OF_WEEK.values().size(),
                "DayOfWeek has Monday..Sunday = 7 values");
    }

    // ===============================================================
    // Coverage: every FQN referenced in a native function signature
    // (parameter types, return type, generic arguments) MUST resolve
    // to a record in the class or enum catalog. This is the single
    // tenet that keeps natives and types consistent: nothing in the
    // signatures dangles unresolved.
    //
    // If this fails: either declare the missing type in {@link Pure}
    // or remove the offending native.
    // ===============================================================

    @Test
    void everyTypePositionFqnInNativeSignaturesResolvesToCatalog() {
        Set<String> catalogFqns = new HashSet<>();
        Pure.allNativeClasses().forEach(c -> catalogFqns.add(c.qualifiedName()));
        Pure.allNativeEnums().forEach(e -> catalogFqns.add(e.qualifiedName()));

        java.util.SortedSet<String> missing = new java.util.TreeSet<>();
        for (NativeFunctionDefinition def : Pure.all()) {
            Set<String> typeParams = Set.copyOf(def.typeParameters());
            for (var p : def.parameters()) {
                collectTypePositionFqns(p.type(), typeParams, catalogFqns, missing);
            }
            collectTypePositionFqns(def.returnType(), typeParams, catalogFqns, missing);
        }
        assertTrue(missing.isEmpty(),
                () -> "FQNs referenced in native signatures but missing from "
                        + "Pure.allNativeClasses() / Pure.allNativeEnums():\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * Walks a {@link TypeExpression} and records every FQN (anything with
     * {@code ::}) that appears in a type position. Bare names (no {@code ::})
     * are skipped &mdash; they're type-parameter binders. Recurses into
     * generic arguments, function-type parameter/return types, relation-type
     * columns, and schema-algebra operands.
     */
    private static void collectTypePositionFqns(
            TypeExpression t,
            Set<String> typeParams,
            Set<String> catalog,
            Set<String> missing) {
        switch (t) {
            case NameRef nr -> recordIfFqn(nr.name(), typeParams, catalog, missing);
            case Generic g -> {
                recordIfFqn(g.name(), typeParams, catalog, missing);
                for (TypeExpression arg : g.arguments()) {
                    collectTypePositionFqns(arg, typeParams, catalog, missing);
                }
            }
            case FunctionType ft -> {
                for (TypedParameter p : ft.parameters()) {
                    collectTypePositionFqns(p.type(), typeParams, catalog, missing);
                }
                collectTypePositionFqns(ft.result().type(), typeParams, catalog, missing);
            }
            case RelationType rt -> {
                for (Column c : rt.columns()) {
                    collectTypePositionFqns(c.type(), typeParams, catalog, missing);
                }
            }
            case SchemaAlgebra sa -> {
                collectTypePositionFqns(sa.left(), typeParams, catalog, missing);
                collectTypePositionFqns(sa.right(), typeParams, catalog, missing);
            }
            default -> {
                // Other variants (if any) carry no FQN-shaped references.
            }
        }
    }

    private static void recordIfFqn(
            String name,
            Set<String> typeParams,
            Set<String> catalog,
            Set<String> missing) {
        // FQNs always contain '::'. Bare names like 'T', 'K', 'm' are type
        // parameters; they have no FQN to resolve.
        if (!name.contains("::")) {
            return;
        }
        if (!catalog.contains(name)) {
            missing.add(name);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static String simpleName(String fqn) {
        int idx = fqn.lastIndexOf("::");
        return idx < 0 ? fqn : fqn.substring(idx + 2);
    }

    private static String signatureKey(NativeFunctionDefinition def) {
        StringBuilder key = new StringBuilder(def.qualifiedName()).append('(');
        for (var p : def.parameters()) {
            key.append(p.type()).append(':').append(p.multiplicity()).append(',');
        }
        key.append(')');
        return key.toString();
    }

}
