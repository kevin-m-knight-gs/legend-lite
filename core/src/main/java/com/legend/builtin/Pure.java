// Ported from engine/com.gs.legend.compiler.Pure (auto-generated there from
// BuiltinRegistry.registerSignature calls). Kept verbatim except for:
//   - package + imports retargeted to core
//   - NativeFunctionDef -> NativeFunctionDefinition (core's parser record)
//   - signature(...) routes through ElementParser instead of engine's
//     hand-rolled PureNativeSignatureParser, eliminating the second parser
//     and giving us a single parse pipeline for user source AND stdlib.
//
// Naming scheme: <NAME>__<ARG1TYPE>_<ARG1MULT>__<ARG2TYPE>_<ARG2MULT>__...
// Multiplicity: [1]->1, [N]->N, [*]->MANY, [0..1]->0_1, [1..*]->1_MANY, [N..M]->N_M.
// Return type omitted (Pure overloads on args only).
//
// HAND-CURATED port of the real legend-pure/legend-engine native catalog.
// Every signature is VERBATIM to its real .pure source (verified per
// function; NO divergence categories remain as of 2026-07-08) — except the
// individually-commented INVENTED pipeline natives (tableReference, tds,
// legacyNavigate, ...), which are internal plumbing, not stdlib claims.
// To add a native: add the verbatim signature citing its .pure path,
// re-run tests (the golden catalog file shows the diff).
package com.legend.builtin;

import com.legend.parser.ElementParser;
import com.legend.model.ClassDefinition;
import com.legend.model.EnumDefinition;
import com.legend.model.NativeFunctionDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Typed identifiers for every Pure native overload &mdash; the single source
 * of truth for Pure-name strings in the system. Every consumer (type checker,
 * checkers, binding tables, lowering) should reference natives by these
 * constants, not by string lookups.
 *
 * <p>Each constant is a {@link NativeFunctionDefinition} produced by routing
 * the signature through {@link ElementParser} at class-load time. Class init
 * fails loudly if any signature stops parsing &mdash; that is the
 * parse-coverage guarantee.
 *
 * <p>Constants are populated in declaration order; {@link #all()} returns the
 * full list for downstream consumers to ingest at bootstrap.
 */
public final class Pure {
    private Pure() {}

    // ================================================================
    // Built-in type FQNs.
    //
    // Single source of truth for the names of stdlib types Pure code
    // refers to without an explicit import. Same role as the
    // {@link NativeFunctionDefinition} constants below, restricted to
    // strings until {@code NativeClassDefinition} (planned follow-up)
    // lands and these get promoted to structured records.
    //
    // Consumers (NameResolver, TypeChecker, tests, etc.) should
    // reference these constants instead of hard-coding the FQN string.
    // ================================================================

    /** {@code meta::pure::metamodel::type::} &mdash; package for core
     *  primitives and {@code Any}, {@code Type}, etc. */
    public static final String TYPE_PKG = "meta::pure::metamodel::type";

    /** {@code meta::pure::metamodel::relation::} &mdash; package for
     *  {@link #RELATION}, {@link #COL_SPEC}, etc. */
    public static final String RELATION_PKG = "meta::pure::metamodel::relation";

    /** {@code meta::pure::metamodel::function::} &mdash; package for
     *  {@link #FUNCTION}. */
    public static final String FUNCTION_PKG = "meta::pure::metamodel::function";

    /** {@code meta::pure::functions::relation::} &mdash; package for
     *  relation-algebra helpers ({@link #WINDOW}, {@link #SORT_INFO}, ...). */
    public static final String RELATION_FUNCTIONS_PKG = "meta::pure::functions::relation";

    /** {@code meta::pure::functions::date::} &mdash; package for date-related
     *  enums ({@link #DURATION_UNIT}, {@link #MONTH}, ...) and helpers. */
    public static final String DATE_FUNCTIONS_PKG = "meta::pure::functions::date";

    /** {@code meta::pure::functions::hash::} &mdash; package for hash-related
     *  enums ({@link #HASH_TYPE}). */
    public static final String HASH_FUNCTIONS_PKG = "meta::pure::functions::hash";

    /** {@code meta::pure::functions::collection::} &mdash; package for collection
     *  helper carriers ({@link #LIST}, {@link #PAIR}). */
    public static final String COLLECTION_PKG = "meta::pure::functions::collection";

    /** {@code meta::pure::functions::math::mathUtility::} &mdash; package for math
     *  helper carriers ({@link #ROW_MAPPER}). */
    public static final String MATH_UTILITY_PKG = "meta::pure::functions::math::mathUtility";

    /** {@code meta::pure::metamodel::variant::} &mdash; package for {@link #VARIANT}. */
    public static final String VARIANT_PKG = "meta::pure::metamodel::variant";

    /** {@code meta::pure::graphFetch::} &mdash; package for graph-fetch
     *  tree carriers ({@link #ROOT_GRAPH_FETCH_TREE}). */
    public static final String GRAPH_FETCH_PKG = "meta::pure::graphFetch";

    /** {@code meta::relational::metamodel::} &mdash; package for relational-store
     *  built-ins ({@link #SORT_DIRECTION}). Distinct from
     *  {@link #RELATION_FUNCTIONS_PKG} which carries Pure-level relation
     *  algebra: this one lives under {@code meta::relational::} and is owned
     *  by the relational DSL. */
    public static final String RELATIONAL_PKG = "meta::relational::metamodel";

    // ================================================================
    // Native class catalog.
    //
    // Built-in types declared as parsed {@link ClassDefinition} records
    // (with {@code isNative=true}) so consumers can treat them uniformly
    // with user classes: same record type, same access patterns, same
    // {@link com.legend.context.ModelContext} lookups. Bodies are empty
    // for now &mdash; we only carry name + type parameters + superclass
    // hierarchy. Property bodies will land in a follow-up when the
    // type-checker needs them.
    //
    // Hierarchy mirrors the engine's M3 platform Pure declarations.
    //
    // Naming: the constants below are the records themselves
    // (e.g. {@link #INTEGER} is a {@link ClassDefinition}, not a string).
    // For the FQN string, call {@code .qualifiedName()}.
    // ================================================================

    /** Native classes in declaration order. Populated by {@link #nativeClass(String)}. */
    private static final List<ClassDefinition> ALL_CLASSES = new ArrayList<>();

    /** Snapshot of every native class declared by {@link Pure}, declaration order. */
    public static List<ClassDefinition> allNativeClasses() {
        return Collections.unmodifiableList(ALL_CLASSES);
    }

    /**
     * Parse one {@code native Class ...} declaration through
     * {@link ElementParser} and stash the resulting record.
     *
     * <p>Call sites contain real Pure source verbatim &mdash; the same
     * text that would appear in an engine {@code .pure} file. This keeps
     * the catalog visually identical to engine declarations and means
     * any copy-paste from engine sources just works.
     *
     * <p>Class-load fails loudly if {@code pureSource} is malformed, parses
     * to something other than a {@link ClassDefinition}, or comes back
     * with {@code isNative=false}.
     */
    private static ClassDefinition nativeClass(String pureSource) {
        // the bootstrap payload is WRITTEN IN platform dialect
        var parsed = ElementParser.parse(pureSource,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
        if (parsed.elements().size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one element parsed from: " + pureSource
                            + " (got " + parsed.elements().size() + ")");
        }
        var el = parsed.elements().get(0);
        if (!(el instanceof ClassDefinition cls)) {
            throw new IllegalStateException(
                    "expected ClassDefinition but got " + el.getClass().getSimpleName()
                            + " from: " + pureSource);
        }
        if (!cls.isNative()) {
            throw new IllegalStateException(
                    "expected native class but parsed isNative=false from: " + pureSource);
        }
        ALL_CLASSES.add(cls);
        return cls;
    }

    // ---- Top of the hierarchy ----
    public static final ClassDefinition ANY  = nativeClass("native Class meta::pure::metamodel::type::Any {}");
    /** M3 ElementOverride: the Typer serves {@code Any.elementOverride}
     * reads as this type and folds them EMPTY (never installed here). */
    public static final ClassDefinition ELEMENT_OVERRIDE = nativeClass("native Class meta::pure::metamodel::extension::ElementOverride {}");
    // the TDS null-cell TYPE (engine tds.pure:127) — the VALUE stays the
    // one sqlNull() funnel (Typer's TDSNull arms); the class exists so
    // match arms (n:TDSNull[1] — toCSVString) TYPE against it
    public static final ClassDefinition TDS_NULL = nativeClass("native Class meta::pure::tds::TDSNull {}");
    public static final ClassDefinition NIL  = nativeClass("native Class meta::pure::metamodel::type::Nil  extends meta::pure::metamodel::type::Any {}");
    // real m3: Type extends PackageableElement extends ... ModelElement — the
    // chain contracts to the link we model (a Class value conforms to
    // ModelElement; letFn's removeDuplicates over classes needs it)
    public static final ClassDefinition TYPE = nativeClass("native Class meta::pure::metamodel::type::Type extends meta::pure::metamodel::ModelElement {}");
    /** Real M3 GenericType — {@code $x->genericType().rawType} reflection
     * (inheritance testGetAll: per-instance member class over a union). */
    public static final ClassDefinition GENERIC_TYPE_META = nativeClass("native Class meta::pure::metamodel::type::generics::GenericType { rawType: meta::pure::metamodel::type::Type[0..1]; }");
    /** Real M3's element root (meta::pure::metamodel::ModelElement) — corpus fixtures pass these around. */
    public static final ClassDefinition MODEL_ELEMENT = nativeClass("native Class meta::pure::metamodel::ModelElement extends meta::pure::metamodel::type::Any {}");

    // ---- Numeric tower ----
    public static final ClassDefinition NUMBER  = nativeClass("native Class meta::pure::metamodel::type::Number  extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition INTEGER = nativeClass("native Class meta::pure::metamodel::type::Integer extends meta::pure::metamodel::type::Number {}");
    public static final ClassDefinition FLOAT   = nativeClass("native Class meta::pure::metamodel::type::Float   extends meta::pure::metamodel::type::Number {}");
    public static final ClassDefinition DECIMAL = nativeClass("native Class meta::pure::metamodel::type::Decimal extends meta::pure::metamodel::type::Number {}");

    // ---- Other primitives ----
    public static final ClassDefinition STRING  = nativeClass("native Class meta::pure::metamodel::type::String  extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition BOOLEAN = nativeClass("native Class meta::pure::metamodel::type::Boolean extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition BYTE    = nativeClass("native Class meta::pure::metamodel::type::Byte    extends meta::pure::metamodel::type::Any {}");

    // ---- Date hierarchy ----
    public static final ClassDefinition DATE        = nativeClass("native Class meta::pure::metamodel::type::Date        extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition STRICT_DATE = nativeClass("native Class meta::pure::metamodel::type::StrictDate  extends meta::pure::metamodel::type::Date {}");
    public static final ClassDefinition DATE_TIME   = nativeClass("native Class meta::pure::metamodel::type::DateTime    extends meta::pure::metamodel::type::Date {}");
    public static final ClassDefinition LATEST_DATE = nativeClass("native Class meta::pure::metamodel::type::LatestDate  extends meta::pure::metamodel::type::Date {}");
    public static final ClassDefinition STRICT_TIME = nativeClass("native Class meta::pure::metamodel::type::StrictTime  extends meta::pure::metamodel::type::Any {}");

    // ---- Relation algebra (parameterized) ----
    public static final ClassDefinition RELATION             = nativeClass("native Class meta::pure::metamodel::relation::Relation<T>         extends meta::pure::metamodel::type::Any {}");
    // real relation.pure — the TDS refinement of Relation (cast target
    // in testEnumInRelation; taxonomy T2: absent metamodel class)
    public static final ClassDefinition TDS_RELATION         = nativeClass("native Class meta::pure::metamodel::relation::TDS<T>              extends meta::pure::metamodel::relation::Relation {}");
    public static final ClassDefinition COL_SPEC             = nativeClass("native Class meta::pure::metamodel::relation::ColSpec<T>          extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition COL_SPEC_ARRAY       = nativeClass("native Class meta::pure::metamodel::relation::ColSpecArray<T>     extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition FUNC_COL_SPEC        = nativeClass("native Class meta::pure::metamodel::relation::FuncColSpec<F, R>   extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition FUNC_COL_SPEC_ARRAY  = nativeClass("native Class meta::pure::metamodel::relation::FuncColSpecArray<F, R> extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition AGG_COL_SPEC         = nativeClass("native Class meta::pure::metamodel::relation::AggColSpec<F, U, R> extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition AGG_COL_SPEC_ARRAY   = nativeClass("native Class meta::pure::metamodel::relation::AggColSpecArray<F, U, R> extends meta::pure::metamodel::type::Any {}");

    // ===== engine RELATIONAL-RUNTIME surface (K-phase natives) =====
    // The corpus's own executeInDb WRAPPER functions
    // (relationalExtension.pure) compile against these classes and inline
    // to the native leaf below; the CONNECTION VALUE at run time is the
    // execution context's one ambient JDBC connection (the K dispatch
    // never evaluates connection expressions).
    // the real platform_dsl_store/grammar/runtime.pure trio — properties
    // as REAL pure declares them (the connection VALUES never evaluate;
    // these exist so connection-resolution chains TYPE-check)
    public static final ClassDefinition RUNTIME_CONNECTION = nativeClass("native Class meta::core::runtime::Connection {}");
    public static final ClassDefinition CONNECTION_STORE = nativeClass("native Class meta::core::runtime::ConnectionStore { connection: meta::core::runtime::Connection[1]; element: meta::pure::metamodel::type::Any[1]; }");
    public static final ClassDefinition RUNTIME = nativeClass("native Class meta::core::runtime::Runtime { connectionStores: meta::core::runtime::ConnectionStore[*]; }");
    // real runtime.pure (engine core) — the corpus instantiates
    // ^EngineRuntime(mappings=..., connectionStores=...) directly
    // (taxonomy T2: absent metamodel class)
    public static final ClassDefinition ENGINE_RUNTIME = nativeClass("native Class meta::core::runtime::EngineRuntime extends meta::core::runtime::Runtime { mappings: meta::pure::mapping::Mapping[*]; }");
    // real executionContext.pure — the corpus instantiates bare
    // ^ExecutionContext() as a defaults carrier (testDataGeneration
    // _Alloy plan calls); properties are optional knobs
    public static final ClassDefinition EXECUTION_CONTEXT = nativeClass("native Class meta::pure::runtime::ExecutionContext {}");
    // real executionPlan_generation.pure — the execution-option context
    // family (taxonomy T2)
    public static final ClassDefinition MULTI_EXECUTION_CONTEXT = nativeClass("native Class meta::pure::executionPlan::MultiExecutionContext extends meta::pure::runtime::ExecutionContext { childExecutionContext: meta::pure::runtime::ExecutionContext[*]; }");
    public static final ClassDefinition EXECUTION_OPTION = nativeClass("native Class meta::pure::executionPlan::ExecutionOption extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition EXECUTION_OPTION_CONTEXT = nativeClass("native Class meta::pure::executionPlan::ExecutionOptionContext extends meta::pure::executionPlan::MultiExecutionContext { executionOptions: meta::pure::executionPlan::ExecutionOption[*]; }");
    // real extension.pure — the plug-in registry class; corpus function
    // SIGNATURES name it (extensions:Extension[*]) even where the value
    // only ever passes through
    public static final ClassDefinition EXTENSION = nativeClass("native Class meta::pure::extension::Extension {}");
    // scalar properties as REAL relationalRuntime.pure declares them (the
    // Function-typed post-processor properties are omitted until demanded);
    // the corpus's testDatabaseConnection(...) constructs these
    /** Real pure relationalRuntime.pure:133 (abstract marker class; the corpus subclasses it — GenerationFeaturesConfig, relationalRuntimeExtension.pure:15). */
    public static final ClassDefinition RELATIONAL_QUERY_GENERATION_CONFIG = nativeClass("native Class meta::external::store::relational::runtime::RelationalQueryGenerationConfig {}");
    /** Real pure graphFetch.pure:89 — the serialize(...) CONFIG carrier.
     * TYPING-ONLY here: the resolver walls any flag that would change the
     * envelope (includeType etc.); the all-false shape is a NOP. */
    public static final ClassDefinition ALLOY_SERIALIZATION_CONFIG = nativeClass("native Class meta::pure::graphFetch::execution::AlloySerializationConfig { typeKeyName: meta::pure::metamodel::type::String[1]; includeType: meta::pure::metamodel::type::Boolean[0..1]; includeEnumType: meta::pure::metamodel::type::Boolean[0..1]; dateTimeFormat: meta::pure::metamodel::type::String[0..1]; removePropertiesWithNullValues: meta::pure::metamodel::type::Boolean[0..1]; removePropertiesWithEmptySets: meta::pure::metamodel::type::Boolean[0..1]; fullyQualifiedTypePath: meta::pure::metamodel::type::Boolean[0..1]; includeObjectReference: meta::pure::metamodel::type::Boolean[0..1]; }");
    public static final ClassDefinition DATABASE_CONNECTION = nativeClass("native Class meta::external::store::relational::runtime::DatabaseConnection extends meta::core::runtime::Connection { type: meta::relational::runtime::DatabaseType[1]; debug: meta::pure::metamodel::type::Boolean[0..1]; timeZone: meta::pure::metamodel::type::String[0..1]; quoteIdentifiers: meta::pure::metamodel::type::Boolean[0..1]; queryTimeOutInSeconds: meta::pure::metamodel::type::Integer[0..1]; queryGenerationConfigs: meta::external::store::relational::runtime::RelationalQueryGenerationConfig[*]; queryPostProcessorsWithParameter: meta::relational::runtime::PostProcessorWithParameter[*]; sqlQueryPostProcessors: meta::pure::metamodel::function::Function<{meta::relational::metamodel::relation::SelectSQLQuery[1]->meta::pure::mapping::Result<meta::relational::metamodel::relation::SelectSQLQuery|1>[1]}>[*]; sqlQueryPostProcessorsConnectionAware: meta::pure::metamodel::function::Function<{meta::relational::metamodel::relation::SelectSQLQuery[1],meta::external::store::relational::runtime::DatabaseConnection[1]->meta::pure::mapping::Result<meta::relational::metamodel::relation::SelectSQLQuery|1>[1]}>[*]; }");
    public static final ClassDefinition TEST_DATABASE_CONNECTION = nativeClass("native Class meta::external::store::relational::runtime::TestDatabaseConnection extends meta::external::store::relational::runtime::DatabaseConnection { testDataSetupCsv: meta::pure::metamodel::type::String[0..1]; testDataSetupSqls: meta::pure::metamodel::type::String[*]; }");
    // the ALLOY connection surface (real connection.pure:29 /
    // datasourceSpecification.pure:15,34 / authenticationStrategy.pure:15,48)
    // — corpus getConnection() helpers construct these literally; the
    // execution still rides the ambient test connection
    public static final ClassDefinition DATASOURCE_SPECIFICATION = nativeClass("native Class meta::pure::alloy::connections::alloy::specification::DatasourceSpecification extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition LOCAL_H2_DATASOURCE_SPECIFICATION = nativeClass("native Class meta::pure::alloy::connections::alloy::specification::LocalH2DatasourceSpecification extends meta::pure::alloy::connections::alloy::specification::DatasourceSpecification { testDataSetupCsv: meta::pure::metamodel::type::String[0..1]; testDataSetupSqls: meta::pure::metamodel::type::String[*]; disableDatabaseToUpper: meta::pure::metamodel::type::Boolean[0..1]; }");
    public static final ClassDefinition AUTHENTICATION_STRATEGY = nativeClass("native Class meta::pure::alloy::connections::alloy::authentication::AuthenticationStrategy extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition DEFAULT_H2_AUTHENTICATION_STRATEGY = nativeClass("native Class meta::pure::alloy::connections::alloy::authentication::DefaultH2AuthenticationStrategy extends meta::pure::alloy::connections::alloy::authentication::AuthenticationStrategy {}");
    public static final ClassDefinition TEST_DATABASE_AUTHENTICATION_STRATEGY = nativeClass("native Class meta::pure::alloy::connections::alloy::authentication::TestDatabaseAuthenticationStrategy extends meta::pure::alloy::connections::alloy::authentication::DefaultH2AuthenticationStrategy {}");
    public static final ClassDefinition RELATIONAL_DATABASE_CONNECTION = nativeClass("native Class meta::external::store::relational::runtime::RelationalDatabaseConnection extends meta::external::store::relational::runtime::DatabaseConnection { datasourceSpecification: meta::pure::alloy::connections::alloy::specification::DatasourceSpecification[1]; authenticationStrategy: meta::pure::alloy::connections::alloy::authentication::AuthenticationStrategy[1]; postProcessors: meta::pure::alloy::connections::PostProcessor[*]; }");
    // the store METACLASS (real: extends meta::pure::store::Store) — a
    // database REFERENCE is a value of this type (classReference), so the
    // corpus's testRuntime(db:Database[1]) overload family type-checks
    // real relational.pure: Database extends Store
    public static final ClassDefinition DATABASE_METACLASS = nativeClass("native Class meta::relational::metamodel::Database extends meta::pure::store::Store { schemas: meta::relational::metamodel::Schema[*]; }");
    // The VIEW/inference metamodel surface (real relational.pure:114-137
    // + relationalExtension.pure:120): host-evaluated over OUR
    // DatabaseDefinition — the typeInference family's whole vocabulary
    public static final ClassDefinition VIEW_METACLASS = nativeClass("native Class meta::relational::metamodel::relation::View extends meta::pure::metamodel::ModelElement { columnMappings: meta::relational::mapping::ColumnMapping[*]; }");
    public static final ClassDefinition COLUMN_MAPPING_METACLASS = nativeClass("native Class meta::relational::mapping::ColumnMapping extends meta::pure::metamodel::type::Any { columnName: meta::pure::metamodel::type::String[1]; relationalOperationElement: meta::relational::metamodel::RelationalOperationElement[1]; }");
    public static final ClassDefinition RELATIONAL_OPERATION_ELEMENT = nativeClass("native Class meta::relational::metamodel::RelationalOperationElement extends meta::pure::metamodel::type::Any {}");
    // CONSTRUCTED relational-op instances (^DynaFunction(...) in the
    // inference tests — real relational.pure metamodel)
    public static final ClassDefinition DYNA_FUNCTION_METACLASS = nativeClass("native Class meta::relational::metamodel::DynaFunction extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; parameters: meta::relational::metamodel::RelationalOperationElement[*]; }");
    public static final ClassDefinition LITERAL_METACLASS = nativeClass("native Class meta::relational::metamodel::Literal extends meta::relational::metamodel::RelationalOperationElement { value: meta::pure::metamodel::type::Any[1]; }");
    public static final ClassDefinition LITERAL_LIST_METACLASS = nativeClass("native Class meta::relational::metamodel::LiteralList extends meta::relational::metamodel::RelationalOperationElement { values: meta::relational::metamodel::Literal[*]; }");
    // The toPostgresModel STANDALONE-SQL bridge surface (real
    // relational.pure:196-383 + postgres metamodel.pure:378-386 +
    // toPostgresModel.pure:31-82)
    public static final ClassDefinition SQL_NODE = nativeClass("native Class meta::external::query::sql::metamodel::Node extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition SQL_EXPRESSION = nativeClass("native Class meta::external::query::sql::metamodel::Expression extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQL_QUALIFIED_NAME = nativeClass("native Class meta::external::query::sql::metamodel::QualifiedName extends meta::pure::metamodel::type::Any { parts: meta::pure::metamodel::type::String[*]; }");
    public static final ClassDefinition SQL_QUALIFIED_NAME_REF = nativeClass("native Class meta::external::query::sql::metamodel::QualifiedNameReference extends meta::external::query::sql::metamodel::Expression { name: meta::external::query::sql::metamodel::QualifiedName[1]; }");
    public static final ClassDefinition SQLN_STATEMENT = nativeClass("native Class meta::external::query::sql::metamodel::Statement extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQLN_RELATION = nativeClass("native Class meta::external::query::sql::metamodel::Relation extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQLN_QUERYBODY = nativeClass("native Class meta::external::query::sql::metamodel::QueryBody extends meta::external::query::sql::metamodel::Relation {}");
    public static final ClassDefinition SQLN_SELECTITEM = nativeClass("native Class meta::external::query::sql::metamodel::SelectItem extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQLN_LITERAL = nativeClass("native Class meta::external::query::sql::metamodel::Literal extends meta::external::query::sql::metamodel::Expression {}");
    public static final ClassDefinition SQLN_DATELITERAL = nativeClass("native Class meta::external::query::sql::metamodel::DateLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::StrictDate[1]; }");
    public static final ClassDefinition SQLN_TIMESTAMPLITERAL = nativeClass("native Class meta::external::query::sql::metamodel::TimestampLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::DateTime[1]; }");
    public static final ClassDefinition SQL_NULL_METACLASS = nativeClass("native Class meta::relational::metamodel::SQLNull extends meta::relational::metamodel::RelationalOperationElement {}");
    public static final ClassDefinition SQLN_STRINGLITERAL = nativeClass("native Class meta::external::query::sql::metamodel::StringLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::String[1]; quoted: meta::pure::metamodel::type::Boolean[0..1]; }");
    public static final ClassDefinition SQLN_INTEGERLITERAL = nativeClass("native Class meta::external::query::sql::metamodel::IntegerLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::Integer[1]; }");
    public static final ClassDefinition SQLN_BOOLEANLITERAL = nativeClass("native Class meta::external::query::sql::metamodel::BooleanLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::Boolean[1]; }");
    public static final ClassDefinition SQLN_DOUBLELITERAL = nativeClass("native Class meta::external::query::sql::metamodel::DoubleLiteral extends meta::external::query::sql::metamodel::Literal { value: meta::pure::metamodel::type::Float[1]; }");
    public static final ClassDefinition SQLN_NULLLITERAL = nativeClass("native Class meta::external::query::sql::metamodel::NullLiteral extends meta::external::query::sql::metamodel::Literal {}");
    public static final ClassDefinition SQLN_FUNCTIONCALL = nativeClass("native Class meta::external::query::sql::metamodel::FunctionCall extends meta::external::query::sql::metamodel::Expression { name: meta::external::query::sql::metamodel::QualifiedName[1]; distinct: meta::pure::metamodel::type::Boolean[1]; arguments: meta::external::query::sql::metamodel::Expression[*]; filter: meta::external::query::sql::metamodel::Expression[0..1]; window: meta::external::query::sql::metamodel::Window[0..1]; }");
    public static final ClassDefinition SQLN_WINDOW = nativeClass("native Class meta::external::query::sql::metamodel::Window extends meta::external::query::sql::metamodel::Statement { windowRef: meta::pure::metamodel::type::String[0..1]; partitions: meta::external::query::sql::metamodel::Expression[*]; orderBy: meta::external::query::sql::metamodel::SortItem[*]; windowFrame: meta::external::query::sql::metamodel::WindowFrame[0..1]; }");
    public static final ClassDefinition SQLN_SORTITEM = nativeClass("native Class meta::external::query::sql::metamodel::SortItem extends meta::external::query::sql::metamodel::Node { sortKey: meta::external::query::sql::metamodel::Expression[1]; ordering: meta::external::query::sql::metamodel::SortItemOrdering[1]; nullOrdering: meta::external::query::sql::metamodel::SortItemNullOrdering[1]; }");
    /** Real postgres metamodel.pure:460 (mode/start/end omitted until demanded). */
    public static final ClassDefinition SQLN_WINDOWFRAME = nativeClass("native Class meta::external::query::sql::metamodel::WindowFrame extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQLN_CAST = nativeClass("native Class meta::external::query::sql::metamodel::Cast extends meta::external::query::sql::metamodel::Expression { expression: meta::external::query::sql::metamodel::Expression[1]; type: meta::external::query::sql::metamodel::ColumnType[1]; }");
    public static final ClassDefinition SQLN_COLUMNTYPE = nativeClass("native Class meta::external::query::sql::metamodel::ColumnType extends meta::external::query::sql::metamodel::Expression { name: meta::pure::metamodel::type::String[1]; parameters: meta::pure::metamodel::type::Integer[*]; }");
    // The sql-protocol EXTENSION package (postgresSqlModel-extensions
    // metamodel_extensions.pure:18/62) — placeholder nodes the
    // toPostgresModel conversion emits for plan-time variables
    public static final ClassDefinition SQLX_TABLE_PLACEHOLDER = nativeClass("native Class meta::external::query::sql::metamodel::extension::TablePlaceholder extends meta::external::query::sql::metamodel::Relation { name: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition SQLX_INCLAUSE_VAR_PLACEHOLDER = nativeClass("native Class meta::external::query::sql::metamodel::extension::InClauseVariablePlaceholder extends meta::external::query::sql::metamodel::Expression { name: meta::pure::metamodel::type::String[1]; }");
    // The query-level sql-protocol surface (postgres metamodel.pure:30-110
    // + extensions:149 — the toPostgresModel SelectSQLQuery conversion)
    public static final ClassDefinition SQLN_QUERYSPECIFICATION = nativeClass("native Class meta::external::query::sql::metamodel::QuerySpecification extends meta::external::query::sql::metamodel::QueryBody { select: meta::external::query::sql::metamodel::Select[1]; from: meta::external::query::sql::metamodel::Relation[*]; where: meta::external::query::sql::metamodel::Expression[0..1]; groupBy: meta::external::query::sql::metamodel::Expression[*]; having: meta::external::query::sql::metamodel::Expression[0..1]; orderBy: meta::external::query::sql::metamodel::SortItem[*]; limit: meta::external::query::sql::metamodel::Expression[0..1]; offset: meta::external::query::sql::metamodel::Expression[0..1]; }");
    public static final ClassDefinition SQLX_EXTENDED_QUERY_SPECIFICATION = nativeClass("native Class meta::external::query::sql::metamodel::extension::ExtendedQuerySpecification extends meta::external::query::sql::metamodel::QuerySpecification { qualify: meta::external::query::sql::metamodel::Expression[0..1]; }");
    public static final ClassDefinition SQLN_WITHQUERY = nativeClass("native Class meta::external::query::sql::metamodel::WithQuery extends meta::external::query::sql::metamodel::Node { name: meta::pure::metamodel::type::String[1]; columns: meta::pure::metamodel::type::String[*]; query: meta::external::query::sql::metamodel::Query[1]; }");
    public static final ClassDefinition SQLN_WITH = nativeClass("native Class meta::external::query::sql::metamodel::With extends meta::external::query::sql::metamodel::Statement { withQueries: meta::external::query::sql::metamodel::WithQuery[*]; }");
    public static final ClassDefinition SQLN_QUERYWITHSCOPE = nativeClass("native Class meta::external::query::sql::metamodel::QueryWithScope extends meta::external::query::sql::metamodel::QueryBody { with: meta::external::query::sql::metamodel::With[0..1]; queryBody: meta::external::query::sql::metamodel::QueryBody[1]; }");
    /** Real: extends SetOperation extends QueryBody (single-inheritance collapse). */
    public static final ClassDefinition SQLN_UNION = nativeClass("native Class meta::external::query::sql::metamodel::Union extends meta::external::query::sql::metamodel::QueryBody { left: meta::external::query::sql::metamodel::Relation[1]; right: meta::external::query::sql::metamodel::Relation[1]; distinct: meta::pure::metamodel::type::Boolean[1]; }");
    public static final ClassDefinition SQLN_LOGICALBINARYEXPRESSION = nativeClass("native Class meta::external::query::sql::metamodel::LogicalBinaryExpression extends meta::external::query::sql::metamodel::Expression { type: meta::external::query::sql::metamodel::LogicalBinaryType[1]; left: meta::external::query::sql::metamodel::Expression[1]; right: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_ISNULLPREDICATE = nativeClass("native Class meta::external::query::sql::metamodel::IsNullPredicate extends meta::external::query::sql::metamodel::Expression { value: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_ISNOTNULLPREDICATE = nativeClass("native Class meta::external::query::sql::metamodel::IsNotNullPredicate extends meta::external::query::sql::metamodel::Expression { value: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_INLISTEXPRESSION = nativeClass("native Class meta::external::query::sql::metamodel::InListExpression extends meta::external::query::sql::metamodel::Expression { values: meta::external::query::sql::metamodel::Expression[*]; }");
    public static final ClassDefinition SQLN_INPREDICATE = nativeClass("native Class meta::external::query::sql::metamodel::InPredicate extends meta::external::query::sql::metamodel::Expression { value: meta::external::query::sql::metamodel::Expression[1]; valueList: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_COMPARISONEXPRESSION = nativeClass("native Class meta::external::query::sql::metamodel::ComparisonExpression extends meta::external::query::sql::metamodel::Expression { left: meta::external::query::sql::metamodel::Expression[1]; right: meta::external::query::sql::metamodel::Expression[1]; operator: meta::external::query::sql::metamodel::ComparisonOperator[1]; }");
    public static final ClassDefinition SQLN_ALIASEDRELATION = nativeClass("native Class meta::external::query::sql::metamodel::AliasedRelation extends meta::external::query::sql::metamodel::Relation { relation: meta::external::query::sql::metamodel::Relation[1]; alias: meta::pure::metamodel::type::String[1]; columnNames: meta::pure::metamodel::type::String[*]; }");
    public static final ClassDefinition SQLN_TABLE = nativeClass("native Class meta::external::query::sql::metamodel::Table extends meta::external::query::sql::metamodel::QueryBody { name: meta::external::query::sql::metamodel::QualifiedName[1]; }");
    public static final ClassDefinition SQLN_TABLEFUNCTION = nativeClass("native Class meta::external::query::sql::metamodel::TableFunction extends meta::external::query::sql::metamodel::QueryBody { functionCall: meta::external::query::sql::metamodel::FunctionCall[1]; }");
    public static final ClassDefinition SQLN_TABLESUBQUERY = nativeClass("native Class meta::external::query::sql::metamodel::TableSubquery extends meta::external::query::sql::metamodel::QueryBody { query: meta::external::query::sql::metamodel::Query[1]; }");
    public static final ClassDefinition SQLN_JOIN = nativeClass("native Class meta::external::query::sql::metamodel::Join extends meta::external::query::sql::metamodel::Relation { type: meta::external::query::sql::metamodel::JoinType[1]; left: meta::external::query::sql::metamodel::Relation[1]; right: meta::external::query::sql::metamodel::Relation[1]; criteria: meta::external::query::sql::metamodel::JoinCriteria[0..1]; }");
    public static final ClassDefinition SQLN_JOINCRITERIA = nativeClass("native Class meta::external::query::sql::metamodel::JoinCriteria extends meta::external::query::sql::metamodel::Node {}");
    public static final ClassDefinition SQLN_JOINON = nativeClass("native Class meta::external::query::sql::metamodel::JoinOn extends meta::external::query::sql::metamodel::JoinCriteria { expression: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_QUERY = nativeClass("native Class meta::external::query::sql::metamodel::Query extends meta::external::query::sql::metamodel::Statement { queryBody: meta::external::query::sql::metamodel::QueryBody[1]; }");
    public static final ClassDefinition SQLN_SINGLECOLUMN = nativeClass("native Class meta::external::query::sql::metamodel::SingleColumn extends meta::external::query::sql::metamodel::SelectItem { alias: meta::pure::metamodel::type::String[0..1]; expression: meta::external::query::sql::metamodel::Expression[1]; }");
    public static final ClassDefinition SQLN_ALLCOLUMNS = nativeClass("native Class meta::external::query::sql::metamodel::AllColumns extends meta::external::query::sql::metamodel::SelectItem {}");
    public static final ClassDefinition SQLN_SELECT = nativeClass("native Class meta::external::query::sql::metamodel::Select extends meta::external::query::sql::metamodel::Node { distinct: meta::pure::metamodel::type::Boolean[1]; selectItems: meta::external::query::sql::metamodel::SelectItem[*]; }");
    /** Real toPostgresModel.pure:31 (dynaFunctionConverterMap is a Map-typed reference — Any). */
    public static final ClassDefinition MODEL_CONVERSION_STATE = nativeClass("native Class meta::relational::functions::toPostgresModel::ModelConversionState extends meta::pure::metamodel::type::Any { isRootSelect: meta::pure::metamodel::type::Boolean[0..1]; processingSelect: meta::pure::metamodel::type::Boolean[0..1]; processingFilter: meta::pure::metamodel::type::Boolean[0..1]; extensions: meta::pure::extension::Extension[*]; dynaFunctionConverterMap: meta::pure::metamodel::type::Any[0..1]; }");
    public static final ClassDefinition ALIAS_METACLASS = nativeClass("native Class meta::relational::metamodel::Alias extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; relationalElement: meta::relational::metamodel::RelationalOperationElement[1]; }");
    public static final ClassDefinition TABLE_ALIAS_METACLASS = nativeClass("native Class meta::relational::metamodel::TableAlias extends meta::relational::metamodel::Alias { schema: meta::pure::metamodel::type::String[0..1]; }");
    public static final ClassDefinition COLUMN_METAMODEL = nativeClass("native Class meta::relational::metamodel::Column extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; type: meta::relational::metamodel::datatype::DataType[1]; }");
    public static final ClassDefinition COLUMN_NAME_METACLASS = nativeClass("native Class meta::relational::metamodel::ColumnName extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition TABLE_ALIAS_COLUMN_NAME_METACLASS = nativeClass("native Class meta::relational::metamodel::TableAliasColumnName extends meta::relational::metamodel::RelationalOperationElement { alias: meta::relational::metamodel::TableAlias[1]; columnName: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition TABLE_ALIAS_COLUMN_METACLASS = nativeClass("native Class meta::relational::metamodel::TableAliasColumn extends meta::relational::metamodel::RelationalOperationElement { columnName: meta::pure::metamodel::type::String[0..1]; alias: meta::relational::metamodel::TableAlias[1]; column: meta::relational::metamodel::Column[1]; }");
    public static final ClassDefinition DATA_TYPE_METACLASS = nativeClass("native Class meta::relational::metamodel::datatype::DataType extends meta::pure::metamodel::type::Any {}");
    // Window aggregation over relational ops (real platform_store_
    // relational/grammar/relational.pure:539-563; frame omitted until
    // demanded)
    public static final ClassDefinition REL_WINDOW = nativeClass("native Class meta::relational::metamodel::Window extends meta::relational::metamodel::RelationalOperationElement { partition: meta::relational::metamodel::RelationalOperationElement[*]; sortBy: meta::relational::metamodel::SortByInfo[*]; }");
    public static final ClassDefinition SORT_BY_INFO = nativeClass("native Class meta::relational::metamodel::SortByInfo extends meta::relational::metamodel::RelationalOperationElement { sortByElement: meta::relational::metamodel::RelationalOperationElement[1]; sortDirection: meta::relational::metamodel::SortDirection[0..1]; }");
    public static final ClassDefinition WINDOW_COLUMN = nativeClass("native Class meta::relational::metamodel::WindowColumn extends meta::relational::metamodel::RelationalOperationElement { columnName: meta::pure::metamodel::type::String[1]; window: meta::relational::metamodel::Window[1]; func: meta::relational::metamodel::DynaFunction[1]; }");
    // The MAPPING-side inference navigation (real functions_Mapping.pure
    // :61 + functions_PropertyMappingsImplementation.pure:74 +
    // relationalMapping.pure:46/66; the real intermediate parents
    // collapse to single inheritance — subsumption for casts only)
    // root/id/parent/superSetImplementationId ride SetImplementation in
    // real mapping.pure (they are PropertyOwnerImplementation properties,
    // mapping.pure:52-58 — lite flattens that parent; class and parent
    // are REFERENCE values, declared Any/loose per the class-reference
    // convention)
    public static final ClassDefinition SET_IMPLEMENTATION = nativeClass("native Class meta::pure::mapping::SetImplementation extends meta::pure::metamodel::type::Any { root: meta::pure::metamodel::type::Boolean[0..1]; id: meta::pure::metamodel::type::String[0..1]; parent: meta::pure::metamodel::type::Any[0..1]; superSetImplementationId: meta::pure::metamodel::type::String[0..1]; }");
    // Real mapping.pure:68 — a SIBLING of SetImplementation under
    // PropertyOwnerImplementation (InstanceSetImplementation extends
    // both); with the parent flattened into SetImplementation the chain
    // collapses to single inheritance, subsumption preserved for the
    // corpus's cast(@PropertyMappingsImplementation) sites
    public static final ClassDefinition PROPERTY_MAPPINGS_IMPLEMENTATION = nativeClass("native Class meta::pure::mapping::PropertyMappingsImplementation extends meta::pure::mapping::SetImplementation {}");
    public static final ClassDefinition INSTANCE_SET_IMPLEMENTATION = nativeClass("native Class meta::pure::mapping::InstanceSetImplementation extends meta::pure::mapping::PropertyMappingsImplementation { class: meta::pure::metamodel::type::Any[0..1]; }");
    /** Real core/pure/router/store/cluster.pure:43. */
    public static final ClassDefinition CROSS_SET_IMPLEMENTATION = nativeClass("native Class meta::pure::router::clustering::CrossSetImplementation extends meta::pure::mapping::InstanceSetImplementation { targetStore: meta::pure::store::Store[0..1]; varName: meta::pure::metamodel::type::String[1]; }");
    // Real relational.pure:105 (userDefinedPrimaryKey/filter/distinct/
    // groupBy/mainTableAlias omitted until a checker demands them) +
    // relationalMapping.pure:26/46 — RootRelational keeps the real
    // TWO-parent shape so mainTable(RelationalMappingSpecification[1])
    // accepts the corpus's cast(@RootRelationalInstanceSetImplementation)
    public static final ClassDefinition RELATIONAL_MAPPING_SPECIFICATION = nativeClass("native Class meta::relational::metamodel::RelationalMappingSpecification extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition RELATIONAL_INSTANCE_SET_IMPL = nativeClass("native Class meta::relational::mapping::RelationalInstanceSetImplementation extends meta::pure::mapping::InstanceSetImplementation { primaryKey: meta::relational::metamodel::RelationalOperationElement[*]; }");
    public static final ClassDefinition ROOT_RELATIONAL_SET_IMPL = nativeClass("native Class meta::relational::mapping::RootRelationalInstanceSetImplementation extends meta::relational::mapping::RelationalInstanceSetImplementation, meta::relational::metamodel::RelationalMappingSpecification {}");
    public static final ClassDefinition PURE_PROPERTY_MAPPING = nativeClass("native Class meta::pure::mapping::PropertyMapping extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition RELATIONAL_PROPERTY_MAPPING = nativeClass("native Class meta::relational::mapping::RelationalPropertyMapping extends meta::pure::mapping::PropertyMapping { relationalOperationElement: meta::relational::metamodel::RelationalOperationElement[1]; }");
    // task #78 step-1 declarations (each cited to the REAL source; class
    // CONSTRAINTS are never ported — constraint evaluation is a separate
    // feature track, declarations only TYPE):
    /** Real platform_dsl_store/grammar/store.pure:18 (extends PackageableElement — ModelElement is this prelude's analog, see :170). */
    public static final ClassDefinition STORE = nativeClass("native Class meta::pure::store::Store extends meta::pure::metamodel::ModelElement { includes: meta::pure::store::Store[*]; name: meta::pure::metamodel::type::String[0..1]; }");
    /** Real core/pure/mapping/modelToModel.pure:37 (toString() qualified property omitted until demanded). */
    public static final ClassDefinition MODEL_STORE = nativeClass("native Class meta::external::store::model::ModelStore extends meta::pure::store::Store {}");
    /** Real core/pure/mapping/modelToModel.pure:43 (empty marker; the connection VALUES never evaluate — declarations exist so runtime-construction chains TYPE, XStore leg slice 0). */
    public static final ClassDefinition PURE_MODEL_CONNECTION = nativeClass("native Class meta::external::store::model::PureModelConnection extends meta::core::runtime::Connection {}");
    /** Real core/pure/mapping/modelToModel.pure:58 ('class: Class&lt;Any&gt;[1]' — a class REFERENCE value; declared Any here, the reference's own type conforms). */
    public static final ClassDefinition JSON_MODEL_CONNECTION = nativeClass("native Class meta::external::store::model::JsonModelConnection extends meta::external::store::model::PureModelConnection { class: meta::pure::metamodel::type::Any[1]; url: meta::pure::metamodel::type::String[1]; }");
    /** Real core/pure/mapping/modelToModel.pure:82. */
    public static final ClassDefinition MODEL_CHAIN_CONNECTION = nativeClass("native Class meta::external::store::model::ModelChainConnection extends meta::core::runtime::Connection { mappings: meta::pure::mapping::Mapping[*]; }");
    /** Real relationalRuntimeExtension.pure:15-27 (constraints noDuplicates/knownFeatures not ported). */
    public static final ClassDefinition GENERATION_FEATURES_CONFIG = nativeClass("native Class meta::external::store::relational::runtime::GenerationFeaturesConfig extends meta::external::store::relational::runtime::RelationalQueryGenerationConfig { enabled: meta::pure::metamodel::type::String[*]; disabled: meta::pure::metamodel::type::String[*]; }");
    /** Real platform_dsl_mapping/grammar/mapping.pure:26 (extends PackageableElement, Testable — ModelElement analog). The mapping METACLASS: a mapping reference is a value of this type. */
    // name rides PackageableElement in real m3 (grammar/mapping.pure:26 —
    // Mapping extends PackageableElement); the corpus constructs the
    // empty-mapping sentinel ^Mapping(name = '') (testFrom.pure:30).
    public static final ClassDefinition MAPPING_METACLASS = nativeClass("native Class meta::pure::mapping::Mapping extends meta::pure::metamodel::ModelElement { name: meta::pure::metamodel::type::String[0..1]; }");
    /** Real platform_store_relational/grammar/relational.pure:92 (extends NamedRelation — ModelElement analog; column surface omitted until demanded). */
    // schema is a parent back-REFERENCE (never struct state — a
    // Schema-typed prop would cycle the value layout Table<->Schema);
    // declared Any per the class-reference convention
    // Real relational.pure:45/:50 — the store-relation hierarchy Table
    // sits under (Relation's real second parent SetRelation flattens per
    // the single-inheritance idiom; Relation's columns stay off — Table
    // declares its own typed columns). NamedRelation carries name.
    public static final ClassDefinition REL_RELATION_METACLASS = nativeClass("native Class meta::relational::metamodel::relation::Relation extends meta::relational::metamodel::RelationalOperationElement {}");
    public static final ClassDefinition NAMED_RELATION_METACLASS = nativeClass("native Class meta::relational::metamodel::relation::NamedRelation extends meta::relational::metamodel::relation::Relation { name: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition TABLE_METACLASS = nativeClass("native Class meta::relational::metamodel::relation::Table extends meta::relational::metamodel::relation::NamedRelation { columns: meta::relational::metamodel::Column[*]; schema: meta::pure::metamodel::type::Any[0..1]; }");
    // The generated-SQL metamodel root the POST-PROCESSOR hooks receive
    // (relationalRuntime.pure:40-42) — opaque here: legend-lite applies
    // recognized post-processors over its OWN SQL IR
    // real chain: SelectSQLQuery extends Relation extends
    // RelationalOperationElement (relational.pure:240) — the conversion
    // surface dispatches on the RelationalOperationElement parent.
    // columns rides the Relation parent in real pure (collapsed here);
    // pivot/leftSideOfFilter/saved*/preIsolation* omitted until demanded.
    public static final ClassDefinition SELECT_SQL_QUERY = nativeClass("native Class meta::relational::metamodel::relation::SelectSQLQuery extends meta::relational::metamodel::RelationalOperationElement { columns: meta::relational::metamodel::RelationalOperationElement[*]; distinct: meta::pure::metamodel::type::Boolean[0..1]; data: meta::relational::metamodel::join::RootJoinTreeNode[0..1]; filteringOperation: meta::relational::metamodel::RelationalOperationElement[*]; groupBy: meta::relational::metamodel::RelationalOperationElement[*]; havingOperation: meta::relational::metamodel::RelationalOperationElement[*]; qualifyOperation: meta::relational::metamodel::RelationalOperationElement[*]; orderBy: meta::relational::metamodel::OrderBy[*]; fromRow: meta::relational::metamodel::Literal[0..1]; toRow: meta::relational::metamodel::Literal[0..1]; commonTableExpressions: meta::relational::metamodel::relation::CommonTableExpression[*]; }");
    // The join-tree surface (relational.pure:139-156): RelationalTreeNode
    // extends TreeNode in real pure — collapsed under
    // RelationalOperationElement so RootJoinTreeNode (extends
    // RelationalTreeNode, Relation) conforms to the conversion surface.
    // childrenData is the TreeNode self-recursive child list — Any-typed
    // (reference semantics; a self-typed prop cycles the value layout).
    public static final ClassDefinition RELATIONAL_TREE_NODE = nativeClass("native Class meta::relational::metamodel::join::RelationalTreeNode extends meta::relational::metamodel::RelationalOperationElement { alias: meta::relational::metamodel::TableAlias[1]; childrenData: meta::pure::metamodel::type::Any[*]; }");
    public static final ClassDefinition ROOT_JOIN_TREE_NODE = nativeClass("native Class meta::relational::metamodel::join::RootJoinTreeNode extends meta::relational::metamodel::join::RelationalTreeNode {}");
    /** Real relational.pure:148 (database/setMappingOwner/join are references — Any per convention; joinType enum exists as join::JoinType). */
    public static final ClassDefinition JOIN_TREE_NODE = nativeClass("native Class meta::relational::metamodel::join::JoinTreeNode extends meta::relational::metamodel::join::RelationalTreeNode { setMappingOwner: meta::pure::metamodel::type::Any[0..1]; database: meta::pure::metamodel::type::Any[0..1]; joinName: meta::pure::metamodel::type::String[1]; join: meta::pure::metamodel::type::Any[0..1]; joinType: meta::relational::metamodel::join::JoinType[0..1]; lateral: meta::pure::metamodel::type::Boolean[0..1]; }");
    /** Real relational.pure:522. */
    public static final ClassDefinition ORDER_BY = nativeClass("native Class meta::relational::metamodel::OrderBy extends meta::pure::metamodel::type::Any { column: meta::relational::metamodel::RelationalOperationElement[1]; direction: meta::relational::metamodel::SortDirection[1]; }");
    /** Real relational.pure:259 (sqlQuery is SelectSQLQuery — Any breaks the CTE<->SelectSQLQuery value-layout cycle). */
    public static final ClassDefinition COMMON_TABLE_EXPRESSION = nativeClass("native Class meta::relational::metamodel::relation::CommonTableExpression extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; sqlQuery: meta::pure::metamodel::type::Any[1]; }");
    /** Real relational.pure:265 (extends Relation — collapse). */
    public static final ClassDefinition CTE_REFERENCE = nativeClass("native Class meta::relational::metamodel::relation::CommonTableExpressionReference extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; }");
    /** Real relational.pure:323 (extends Operation extends Function extends RelationalOperationElement — collapse). */
    public static final ClassDefinition JOIN_STRINGS_OP = nativeClass("native Class meta::relational::metamodel::operation::JoinStrings extends meta::relational::metamodel::RelationalOperationElement { strings: meta::relational::metamodel::RelationalOperationElement[*]; prefix: meta::relational::metamodel::RelationalOperationElement[0..1]; separator: meta::relational::metamodel::RelationalOperationElement[0..1]; suffix: meta::relational::metamodel::RelationalOperationElement[0..1]; }");
    /** Real relational.pure:386 — the join-slot property mapping's element. */
    public static final ClassDefinition ROEWJ = nativeClass("native Class meta::relational::metamodel::RelationalOperationElementWithJoin extends meta::relational::metamodel::RelationalOperationElement { relationalOperationElement: meta::relational::metamodel::RelationalOperationElement[0..1]; joinTreeNode: meta::relational::metamodel::join::JoinTreeNode[0..1]; }");
    /** Real pureToSQLQuery_union.pure:868 (currentTreeNodes/setImplementations are references — Any). */
    public static final ClassDefinition REL_UNION = nativeClass("native Class meta::relational::metamodel::relation::Union extends meta::relational::metamodel::RelationalOperationElement { currentTreeNodes: meta::pure::metamodel::type::Any[*]; setImplementations: meta::pure::metamodel::type::Any[*]; queries: meta::relational::metamodel::relation::SelectSQLQuery[*]; }");
    public static final ClassDefinition REL_UNION_ALL = nativeClass("native Class meta::relational::metamodel::relation::UnionAll extends meta::relational::metamodel::relation::Union {}");
    /** Real relational.pure:275 (also extends RelationalTds — single-inheritance collapse per this prelude's convention). */
    public static final ClassDefinition TDS_SELECT_SQL_QUERY = nativeClass("native Class meta::relational::metamodel::relation::TdsSelectSqlQuery extends meta::relational::metamodel::relation::SelectSQLQuery {}");
    /** Real relational.pure:121 (extends NamedRelation; parameters surface omitted until demanded). */
    public static final ClassDefinition TABULAR_FUNCTION = nativeClass("native Class meta::relational::metamodel::relation::TabularFunction extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; schema: meta::pure::metamodel::type::Any[0..1]; }");
    // The pureToSqlQuery PLACEHOLDER metamodel (real pureToSQLQuery/
    // metamodel.pure:6-23): plan-time variable stand-ins the dialect
    // conversion maps to sql-extension placeholder nodes. propertyPath/
    // type/multiplicity are REFERENCE values — declared Any per the
    // class-reference convention (see JSON_MODEL_CONNECTION).
    public static final ClassDefinition VAR_PLACEHOLDER = nativeClass("native Class meta::relational::functions::pureToSqlQuery::metamodel::VarPlaceHolder extends meta::relational::metamodel::RelationalOperationElement { name: meta::pure::metamodel::type::String[1]; propertyPath: meta::pure::metamodel::type::Any[*]; type: meta::pure::metamodel::type::Any[1]; multiplicity: meta::pure::metamodel::type::Any[0..1]; }");
    public static final ClassDefinition VAR_SET_PLACEHOLDER = nativeClass("native Class meta::relational::functions::pureToSqlQuery::metamodel::VarSetPlaceHolder extends meta::relational::metamodel::relation::TdsSelectSqlQuery { varName: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition VAR_CROSS_SET_PLACEHOLDER = nativeClass("native Class meta::relational::functions::pureToSqlQuery::metamodel::VarCrossSetPlaceHolder extends meta::relational::metamodel::relation::Table { varName: meta::pure::metamodel::type::String[1]; crossSetImplementation: meta::pure::router::clustering::CrossSetImplementation[1]; }");
    // The queryPostProcessorsWithParameter chain (real relationalRuntime
    // .pure:46/51-70 + cteExtractionPostProcessor.pure:26) — recognized
    // hooks apply over OUR SQL IR
    public static final ClassDefinition POST_PROCESSOR_PARAMETER = nativeClass("native Class meta::relational::runtime::PostProcessorParameter extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition RELATIONAL_POST_PROCESSOR = nativeClass("native Class meta::relational::runtime::PostProcessor extends meta::pure::metamodel::type::Any { sqlQueryPostProcessorForExecution: meta::pure::metamodel::function::Function<meta::pure::metamodel::type::Any>[0..1]; sqlQueryPostProcessorForPlan: meta::pure::metamodel::function::ConcreteFunctionDefinition<meta::pure::metamodel::type::Any>[0..1]; }");
    public static final ClassDefinition POST_PROCESSOR_WITH_PARAMETER = nativeClass("native Class meta::relational::runtime::PostProcessorWithParameter extends meta::pure::metamodel::type::Any { postProcessor: meta::pure::metamodel::function::ConcreteFunctionDefinition<{->meta::relational::runtime::PostProcessor[1]}>[1]; parameters: meta::relational::runtime::PostProcessorParameter[*]; }");
    public static final ClassDefinition ALLOY_POST_PROCESSOR = nativeClass("native Class meta::pure::alloy::connections::PostProcessor extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition EXTRACT_CTES_POST_PROCESSOR = nativeClass("native Class meta::relational::postProcessor::cteExtraction::ExtractSubQueriesAsCTEsPostProcessor extends meta::pure::alloy::connections::PostProcessor {}");
    // The relationalMapper rename surface (real corpus metamodel.pure
    // :185-208 + runtime/connection/postprocessor.pure:40-43; the
    // corpus's own defining files are demand-pull-AMBIGUOUS — protocol
    // versions share the simple names — so the classes register here
    // like the rest of the relational metamodel). RelationalMapper's
    // real parents: PackageableElement (ModelElement analog, see :170)
    // + PostProcessorParameter.
    public static final ClassDefinition DATABASE_MAPPER = nativeClass("native Class meta::relational::metamodel::DatabaseMapper extends meta::pure::metamodel::type::Any { database: meta::pure::metamodel::type::String[1]; schemas: meta::relational::metamodel::Schema[*]; }");
    public static final ClassDefinition SCHEMA_MAPPER = nativeClass("native Class meta::relational::metamodel::SchemaMapper extends meta::pure::metamodel::type::Any { from: meta::relational::metamodel::Schema[1]; to: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition TABLE_MAPPER = nativeClass("native Class meta::relational::metamodel::TableMapper extends meta::pure::metamodel::type::Any { from: meta::relational::metamodel::relation::Table[1]; to: meta::pure::metamodel::type::String[1]; }");
    public static final ClassDefinition RELATIONAL_MAPPER = nativeClass("native Class meta::relational::metamodel::RelationalMapper extends meta::pure::metamodel::ModelElement, meta::relational::runtime::PostProcessorParameter { databaseMappers: meta::relational::metamodel::DatabaseMapper[*]; schemaMappers: meta::relational::metamodel::SchemaMapper[*]; tableMappers: meta::relational::metamodel::TableMapper[*]; }");
    public static final ClassDefinition RELATIONAL_MAPPER_POST_PROCESSOR = nativeClass("native Class meta::pure::alloy::connections::RelationalMapperPostProcessor extends meta::pure::alloy::connections::PostProcessor { relationalMappers: meta::relational::metamodel::RelationalMapper[*]; }");
    /** Real core/pure/tds/tds.pure:18-23. */
    public static final ClassDefinition TABULAR_DATA_SET = nativeClass("native Class meta::pure::tds::TabularDataSet extends meta::pure::metamodel::type::Any { columns: meta::pure::tds::TDSColumn[*]; rows: meta::pure::tds::TDSRow[*]; }");
    /** Real core/pure/tds/tds.pure:25-45 (offset/name; the type surface omitted until demanded). */
    public static final ClassDefinition TDS_COLUMN = nativeClass("native Class meta::pure::tds::TDSColumn extends meta::pure::metamodel::type::Any { offset: meta::pure::metamodel::type::Integer[0..1]; name: meta::pure::metamodel::type::String[1]; }");
    /** Real core/pure/tds/tds.pure:76-80 (getString/isNull qualified properties omitted until demanded — the ResultSet Row precedent). */
    public static final ClassDefinition TDS_ROW = nativeClass("native Class meta::pure::tds::TDSRow extends meta::pure::metamodel::type::Any { parent: meta::pure::tds::TabularDataSet[0..1]; values: meta::pure::metamodel::type::Any[*]; }");
    /** Real platform_store_relational/grammar/relational.pure (Schema on Database; table lookups land on it). */
    public static final ClassDefinition SCHEMA_METACLASS = nativeClass("native Class meta::relational::metamodel::Schema extends meta::pure::metamodel::ModelElement { tables: meta::relational::metamodel::relation::Table[*]; views: meta::relational::metamodel::relation::View[*]; name: meta::pure::metamodel::type::String[0..1]; database: meta::relational::metamodel::Database[1]; }");
    /** Real core/store/aggregationAware/aggregationAware.pure:36-39. */
    public static final ClassDefinition AGGREGATION_AWARE_ACTIVITY = nativeClass("native Class meta::pure::mapping::aggregationAware::AggregationAwareActivity extends meta::pure::mapping::Activity { rewrittenQuery: meta::pure::metamodel::type::String[1]; }");
    // real platform_store_relational/functions.pure:128 — the relational
    // execution activity the corpus casts Result.activities to
    // (taxonomy T2: absent metamodel class)
    public static final ClassDefinition RELATIONAL_DATA_SOURCE = nativeClass("native Class meta::relational::runtime::DataSource extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition RELATIONAL_ACTIVITY = nativeClass("native Class meta::relational::mapping::RelationalActivity extends meta::pure::mapping::Activity { sql: meta::pure::metamodel::type::String[1]; comment: meta::pure::metamodel::type::String[0..1]; executionTimeInNanoSecond: meta::pure::metamodel::type::Integer[0..1]; sqlGenerationTimeInNanoSecond: meta::pure::metamodel::type::Integer[0..1]; connectionAcquisitionTimeInNanoSecond: meta::pure::metamodel::type::Integer[0..1]; executionPlanInformation: meta::pure::metamodel::type::String[0..1]; dataSource: meta::relational::runtime::DataSource[0..1]; }");
    // real platform_store_relational/functions.pure:50-65 (dataSource and
    // Row's value(name) qualified property omitted until demanded) — setup
    // functions INTROSPECT results (println(executeInDb(...).rows.values))
    public static final ClassDefinition RESULT_SET = nativeClass("native Class meta::relational::metamodel::execute::ResultSet extends meta::pure::metamodel::type::Any { executionTimeInNanoSecond: meta::pure::metamodel::type::Integer[1]; connectionAcquisitionTimeInNanoSecond: meta::pure::metamodel::type::Integer[1]; executionPlanInformation: meta::pure::metamodel::type::String[0..1]; columnNames: meta::pure::metamodel::type::String[*]; rows: meta::relational::metamodel::execute::Row[*]; }");
    public static final ClassDefinition RESULT_SET_ROW = nativeClass("native Class meta::relational::metamodel::execute::Row extends meta::pure::metamodel::type::Any { values: meta::pure::metamodel::type::Any[*]; parent: meta::relational::metamodel::execute::ResultSet[1]; value(name:meta::pure::metamodel::type::String[1]){$this.values->at($this.parent.columnNames->indexOf($name));}: meta::pure::metamodel::type::Any[1]; }");
    // real executionPlan.pure:60-73 (func/mapping/runtime/rootExecutionNode
    // omitted until demanded — each declared property matches the REAL
    // class member-for-member)
    public static final ClassDefinition EXECUTION_PLAN_CLASS = nativeClass("native Class meta::pure::executionPlan::ExecutionPlan extends meta::pure::metamodel::type::Any { rootExecutionNode: meta::pure::executionPlan::ExecutionNode[1]; processingTemplateFunctions: meta::pure::metamodel::type::String[*]; }");
    // the plan NODE surface (real executionPlan.pure:73-83/:178-205 +
    // relational executionPlan.pure:63-90) — declared subsets; values
    // answer through the K-side plan model (the plan-handle walks)
    public static final ClassDefinition EXECUTION_NODE = nativeClass("native Class meta::pure::executionPlan::ExecutionNode extends meta::pure::metamodel::type::Any { executionNodes: meta::pure::executionPlan::ExecutionNode[*]; }");
    public static final ClassDefinition FUNCTION_PARAMETERS_VALIDATION_NODE = nativeClass("native Class meta::pure::executionPlan::FunctionParametersValidationNode extends meta::pure::executionPlan::ExecutionNode { functionParameters: meta::pure::executionPlan::FunctionParameter[*]; }");
    // real graphFetchExecutionPlan.pure — the cross-store graph fetch
    // node pair the corpus casts plan nodes to (taxonomy T2)
    public static final ClassDefinition GLOBAL_GRAPH_FETCH_EXECUTION_NODE = nativeClass("native Class meta::pure::graphFetch::executionPlan::GlobalGraphFetchExecutionNode extends meta::pure::executionPlan::ExecutionNode {}");
    public static final ClassDefinition STORE_MAPPING_GLOBAL_GRAPH_FETCH_EXECUTION_NODE = nativeClass("native Class meta::pure::graphFetch::executionPlan::StoreMappingGlobalGraphFetchExecutionNode extends meta::pure::graphFetch::executionPlan::GlobalGraphFetchExecutionNode {}");
    public static final ClassDefinition FUNCTION_PARAMETER = nativeClass("native Class meta::pure::executionPlan::FunctionParameter extends meta::pure::metamodel::type::Any { name: meta::pure::metamodel::type::String[1]; supportsStream: meta::pure::metamodel::type::Boolean[0..1]; }");
    public static final ClassDefinition SQL_EXECUTION_NODE = nativeClass("native Class meta::relational::mapping::SQLExecutionNode extends meta::pure::executionPlan::ExecutionNode { sqlQuery: meta::pure::metamodel::type::String[1]; sqlComment: meta::pure::metamodel::type::String[0..1]; connection: meta::external::store::relational::runtime::DatabaseConnection[1]; }");
    public static final ClassDefinition RELATIONAL_INSTANTIATION_EXECUTION_NODE = nativeClass("native Class meta::relational::mapping::RelationalInstantiationExecutionNode extends meta::pure::executionPlan::ExecutionNode {}");

    // ---- Function carrier (parameterized over a function-type token) ----
    public static final ClassDefinition FUNCTION = nativeClass("native Class meta::pure::metamodel::function::Function<F> extends meta::pure::metamodel::type::Any {}");
    // The m3 definition hierarchy under it (real pure: LambdaFunction<F>
    // extends FunctionDefinition<F> extends Function<F>) — corpus code
    // annotates with these (LambdaFunction<{->TabularDataSet[1]}>), and
    // the kernel's unwrapFunction treats all carriers as wrapper
    // spellings of the bare FunctionType.
    public static final ClassDefinition FUNCTION_DEFINITION = nativeClass("native Class meta::pure::metamodel::function::FunctionDefinition<F> extends meta::pure::metamodel::function::Function<F> {}");
    public static final ClassDefinition CONCRETE_FUNCTION_DEFINITION = nativeClass("native Class meta::pure::metamodel::function::ConcreteFunctionDefinition<F> extends meta::pure::metamodel::function::FunctionDefinition<F> {}");
    public static final ClassDefinition LAMBDA_FUNCTION = nativeClass("native Class meta::pure::metamodel::function::LambdaFunction<F> extends meta::pure::metamodel::function::FunctionDefinition<F> {}");

    // ---- Metaclass ----
    // Pure exposes the metaclass as `Class<T>` (parameterized over the
    // class it describes); used by signatures like `getAll(Class<T>):T[*]`.
    public static final ClassDefinition CLASS = nativeClass("native Class meta::pure::metamodel::type::Class<T> extends meta::pure::metamodel::type::Type {}");
    // The enumeration metaclass (real m3: Class Enumeration<T> extends Type) —
    // a bare enumeration reference (STR_GeographicEntityType->toString()) is a
    // value of this type.
    public static final ClassDefinition ENUMERATION = nativeClass("native Class meta::pure::metamodel::type::Enumeration<T> extends meta::pure::metamodel::type::Type {}");

    // ---- Variant (semi-structured value carrier) ----
    public static final ClassDefinition VARIANT = nativeClass("native Class meta::pure::metamodel::variant::Variant extends meta::pure::metamodel::type::Any {}");

    // ---- Collection carriers ----
    // REAL pure declares values (legend-pure platform/pure/anonymousCollections.pure:33-35,
    // <<equality.Key>>) — property access and ^List(values=...) construction validate against it.
    public static final ClassDefinition LIST = nativeClass("native Class meta::pure::functions::collection::List<T>    extends meta::pure::metamodel::type::Any { values: T[*]; }");
    // REAL pure declares first/second (legend-pure platform/pure/anonymousCollections.pure:17-25,
    // both <<equality.Key>>) — property access and instance construction validate against THEM.
    public static final ClassDefinition PAIR = nativeClass("native Class meta::pure::functions::collection::Pair<U, V> extends meta::pure::metamodel::type::Any { first: U[1]; second: V[1]; }");
    public static final ClassDefinition MAP = nativeClass("native Class meta::pure::functions::collection::Map<U, V> extends meta::pure::metamodel::type::Any {}");

    // ---- Math helper carrier (rowwise correlation/covariance inputs) ----
    public static final ClassDefinition ROW_MAPPER = nativeClass("native Class meta::pure::functions::math::mathUtility::RowMapper<T, U> extends meta::pure::metamodel::type::Any {}");

    // ---- Graph-fetch tree carrier ----
    public static final ClassDefinition ROOT_GRAPH_FETCH_TREE =
            nativeClass("native Class meta::pure::graphFetch::RootGraphFetchTree<T> extends meta::pure::metamodel::type::Any {}");
    // real dataQuality.pure:39-44 / :20 — the checked-result surface.
    // Opaque carriers: defects/source/value are the SERIALIZER's envelope
    // (the corpus never property-reads a Checked value).
    public static final ClassDefinition CHECKED =
            nativeClass("native Class meta::pure::dataQuality::Checked<T> extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition DEFECT =
            nativeClass("native Class meta::pure::dataQuality::Defect extends meta::pure::metamodel::type::Any {}");

    // ---- Relation-functions helpers ----
    public static final ClassDefinition WINDOW    = nativeClass("native Class meta::pure::functions::relation::_Window<T>   extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition SORT_INFO = nativeClass("native Class meta::pure::functions::relation::SortInfo<T>  extends meta::pure::metamodel::type::Any {}");

    // ---- Window-frame hierarchy (mirrors engine's frame.pure / range.pure / rows.pure) ----
    public static final ClassDefinition FRAME                 = nativeClass("native Class meta::pure::functions::relation::Frame                extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition FRAME_VALUE           = nativeClass("native Class meta::pure::functions::relation::FrameValue           extends meta::pure::metamodel::type::Any {}");
    public static final ClassDefinition UNBOUNDED_FRAME_VALUE = nativeClass("native Class meta::pure::functions::relation::UnboundedFrameValue  extends meta::pure::functions::relation::FrameValue {}");
    public static final ClassDefinition _RANGE                = nativeClass("native Class meta::pure::functions::relation::_Range               extends meta::pure::functions::relation::Frame {}");
    public static final ClassDefinition _RANGE_INTERVAL       = nativeClass("native Class meta::pure::functions::relation::_RangeInterval       extends meta::pure::functions::relation::Frame {}");
    public static final ClassDefinition ROWS                  = nativeClass("native Class meta::pure::functions::relation::Rows                 extends meta::pure::functions::relation::Frame {}");

    // ================================================================
    // Native enum catalog.
    //
    // Engine declares several stdlib types as {@code Enum} rather than
    // {@code Class} (e.g. {@link #DURATION_UNIT}, {@link #JOIN_KIND}).
    // Modelled as parsed {@link EnumDefinition} records so they round-trip
    // through {@link ElementParser} the same way native classes do.
    //
    // Same naming convention as the class catalog: the constant is the
    // record itself (e.g. {@link #JOIN_KIND} is an {@link EnumDefinition}).
    // ================================================================

    /** Native enums in declaration order. Populated by {@link #nativeEnum(String)}. */
    private static final List<EnumDefinition> ALL_ENUMS = new ArrayList<>();

    /** Snapshot of every native enum declared by {@link Pure}, declaration order. */
    public static List<EnumDefinition> allNativeEnums() {
        return Collections.unmodifiableList(ALL_ENUMS);
    }

    /**
     * Parse one {@code Enum ...} declaration through {@link ElementParser}
     * and stash the resulting record.
     *
     * <p>Like {@link #nativeClass(String)}, call sites contain real Pure
     * source verbatim. Class-load fails loudly on any malformed declaration.
     */
    private static EnumDefinition nativeEnum(String pureSource) {
        // the bootstrap payload is WRITTEN IN platform dialect
        var parsed = ElementParser.parse(pureSource,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
        if (parsed.elements().size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one element parsed from: " + pureSource
                            + " (got " + parsed.elements().size() + ")");
        }
        var el = parsed.elements().get(0);
        if (!(el instanceof EnumDefinition def)) {
            throw new IllegalStateException(
                    "expected EnumDefinition but got " + el.getClass().getSimpleName()
                            + " from: " + pureSource);
        }
        ALL_ENUMS.add(def);
        return def;
    }

    // ---- Relational runtime enums ----
    // real relationalRuntime.pure:21 — the corpus's testDatabaseConnection
    // constructs ^TestDatabaseConnection(type=DatabaseType.H2, ...)
    public static final EnumDefinition SQL_JOIN_TYPE = nativeEnum("""
            Enum meta::external::query::sql::metamodel::JoinType
            { CROSS, INNER, LEFT, RIGHT, FULL }
            """);
    public static final EnumDefinition SQL_LOGICAL_BINARY_TYPE = nativeEnum("""
            Enum meta::external::query::sql::metamodel::LogicalBinaryType
            { AND, OR }
            """);
    public static final EnumDefinition SQL_COMPARISON_OPERATOR = nativeEnum("""
            Enum meta::external::query::sql::metamodel::ComparisonOperator
            { EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, IS_DISTINCT_FROM }
            """);
    public static final EnumDefinition SQL_SORT_ITEM_ORDERING = nativeEnum("""
            Enum meta::external::query::sql::metamodel::SortItemOrdering
            { ASCENDING, DESCENDING }
            """);
    public static final EnumDefinition SQL_SORT_ITEM_NULL_ORDERING = nativeEnum("""
            Enum meta::external::query::sql::metamodel::SortItemNullOrdering
            { FIRST, LAST, UNDEFINED }
            """);
    public static final EnumDefinition DATABASE_TYPE = nativeEnum("""
            Enum meta::relational::runtime::DatabaseType
            {
                DB2, H2, MemSQL, Sybase, SybaseIQ, Composite, Postgres, SqlServer,
                Hive, Snowflake, Presto, Trino, BigQuery, Redshift, Databricks,
                Spanner, Athena, Aurora, SparkSQL, DuckDB, Oracle, ClickHouse,
                DebugPrint
            }
            """);

    // ---- Date enums ----
    // executionPlan feature flags (REAL executionPlanFeature.pure:21);
    // withFeatureFlags is IDENTITY in real pure (:27 — the flag rides the
    // plan context; our enum source-value translation IS the pushdown).
    public static final EnumDefinition EXECUTION_PLAN_FEATURE = nativeEnum("""
            Enum meta::pure::executionPlan::features::Feature
            {
                PUSH_DOWN_ENUM_TRANSFORM,
                VARIANT_TYPE_AS_INPUT,
                LEGACY_SQL_NULL_UNSAFE_EQUALS
            }""");

    public static final EnumDefinition DURATION_UNIT = nativeEnum("""
            Enum meta::pure::functions::date::DurationUnit
            {
                YEARS, MONTHS, WEEKS, DAYS, HOURS, MINUTES,
                SECONDS, MILLISECONDS, MICROSECONDS, NANOSECONDS
            }
            """);

    public static final EnumDefinition MONTH = nativeEnum("""
            Enum meta::pure::functions::date::Month
            {
                January, February, March, April, May, June,
                July, August, September, October, November, December
            }
            """);

    public static final EnumDefinition QUARTER = nativeEnum("""
            Enum meta::pure::functions::date::Quarter
            {
                Q1, Q2, Q3, Q4
            }
            """);

    public static final EnumDefinition DAY_OF_WEEK = nativeEnum("""
            Enum meta::pure::functions::date::DayOfWeek
            {
                Monday, Tuesday, Wednesday, Thursday, Friday, Saturday, Sunday
            }
            """);

    // ---- Relation enums ----
    public static final EnumDefinition SORT_TYPE = nativeEnum(
            "Enum meta::pure::functions::relation::SortType { ASC, DESC }");

    // ---- String/date enums (real regexpParameter.pure / formatDate.pure) ----
    public static final EnumDefinition REGEXP_PARAMETER = nativeEnum("""
            Enum meta::pure::functions::string::RegexpParameter
            {
                CASE_SENSITIVE, CASE_INSENSITIVE, MULTILINE, NON_NEWLINE_SENSITIVE
            }
            """);
    public static final EnumDefinition STRICT_DATE_FORMAT = nativeEnum(
            "Enum meta::pure::functions::date::StrictDateFormat { ISO8601 }");
    public static final EnumDefinition DATE_TIME_FORMAT = nativeEnum(
            "Enum meta::pure::functions::date::DateTimeFormat { ISO8601_NanoSecondPrecision }");

    public static final EnumDefinition JOIN_KIND = nativeEnum("""
            Enum meta::pure::functions::relation::JoinKind
            {
                LEFT, RIGHT, FULL, INNER
            }
            """);

    // ---- Hash enum ----
    public static final EnumDefinition HASH_TYPE = nativeEnum(
            "Enum meta::pure::functions::hash::HashType { MD5, SHA1, SHA256 }");

    // ---- Relational-store enum (lives under meta::relational, not meta::pure) ----
    public static final EnumDefinition SORT_DIRECTION = nativeEnum(
            "Enum meta::relational::metamodel::SortDirection { ASC, DESC }");

    /** The legacy TDS join kind (join(tds, JoinType.INNER, ...)). */
    public static final EnumDefinition JOIN_TYPE = nativeEnum(
            "Enum meta::relational::metamodel::join::JoinType"
            + " { INNER, LEFT_OUTER, RIGHT_OUTER, FULL_OUTER }");

    // ================================================================
    // Native function catalog.
    // ================================================================

    /**
     * Definitions in declaration order &mdash; which is LOAD-BEARING (overload
     * selection keeps the FIRST best-scoring candidate on ties, so reordering
     * can change tie-breaks; the golden catalog file pins it) and NOT
     * constant name. Populated by {@link #signature(String)}.
     */
    private static final List<NativeFunctionDefinition> ALL = new ArrayList<>();

    /**
     * The lite-internal native package's vocabulary, as COMPILE-TIME
     * CONSTANTS — the only sanctioned spellings for internal producers
     * (normalizer/lowering emissions), internal consumers (structural
     * matchers), and lowering registrations. A bare name is a QUERY
     * against the user's namespace; the compiler talking to itself uses
     * exact identity, so string literals of these names at use sites
     * are banned. The governance test binds every constant to a
     * registered catalog native (a typo here cannot survive one run).
     */
    public static final class Lite {
        public static final String PKG = "meta::legend::lite::";

        // -- INTERNAL DESUGAR IR (invention audit 2026-08-14, per-name
        // verified against both upstream repos): emitted by lite's
        // normalizer/lowering, no upstream counterpart — legacy-mapping
        // semantics, declared-type shims, and arity-disambiguating
        // renames of engine dynaFns (parseDate etc. with a format
        // arg -> *Format). NOT user-reachable: bare-name resolution
        // excludes the lite package.
        public static final String CAST_AS_DECLARED = PKG + "castAsDeclared";
        public static final String TYPE_AS_DECLARED = PKG + "typeAsDeclared";
        public static final String LEGACY_NAVIGATE = PKG + "legacyNavigate";
        public static final String LEGACY_ASSOC_PREDICATE = PKG + "legacyAssocPredicate";
        public static final String LEGACY_LOCAL_PROPERTY = PKG + "legacyLocalProperty";
        public static final String OTHERWISE = PKG + "otherwise";
        public static final String PARSE_DATE_FORMAT = PKG + "parseDateFormat";
        public static final String CONVERT_DATE_FORMAT = PKG + "convertDateFormat";
        public static final String CONVERT_DATE_TIME_FORMAT = PKG + "convertDateTimeFormat";
        public static final String CONVERT_TIME_ZONE_FORMAT = PKG + "convertTimeZoneFormat";
        /** date::adjust semantics; the FQN marks the LEGACY-print channel:
         *  engine legacy H2 prints the dateadd unit UPPERCASE
         *  (extensionDefaults.pure mapToDBUnitType) while the new
         *  sqlDialectTranslation defaults print lowercase — TemporalFrame
         *  stamps this on milestoning window-condition dates so
         *  EngineStyleH2 can render the channel it is quoting. */
        public static final String ADJUST_TEMPORAL = PKG + "adjustTemporal";
        /** The #TDS literal's desugar target (SpecParser spells this
         *  FQN literally — the parser stays free of this class). */
        public static final String TDS = PKG + "tds";

        // -- ENGINE-VOCABULARY typing shims (per-name verified): the
        // NAME is legend-engine's own wire/dynaFn vocabulary
        // ('divideRound' pureToSQLQuery dynaFunction, 'notEqualAnsi'
        // relationalExtension, 'avg' legacy ~groupBy aggregate, 'sub'
        // databricks dynaFns, 'isNumeric' duckdb extension, 'hash'
        // memsql dialect); 'join' is the REAL relation join's name —
        // lite carries a same-name overload shim. Only the typing-shim
        // FQN package is ours.
        public static final String AVG = PKG + "avg";
        public static final String DIVIDE_ROUND = PKG + "divideRound";
        public static final String NOT_EQUAL_ANSI = PKG + "notEqualAnsi";
        /** Engine DynaFunc ORDERING comparisons in join/filter conditions:
         *  the engine never type-checks these operands (untyped Literal in
         *  a DynaFunc — RelationalParseTreeWalker), so the shim is
         *  Any-typed like notEqualAnsi; a Date column vs a quoted string
         *  literal must not die in pure overload resolution (ledger
         *  cluster 18). */
        public static final String LESS_THAN_ANY = PKG + "lessThan";
        public static final String LESS_THAN_EQUAL_ANY = PKG + "lessThanEqual";
        public static final String GREATER_THAN_ANY = PKG + "greaterThan";
        public static final String GREATER_THAN_EQUAL_ANY = PKG + "greaterThanEqual";
        public static final String SUB = PKG + "sub";
        public static final String IS_NUMERIC = PKG + "isNumeric";
        public static final String HASH = PKG + "hash";
        public static final String JOIN = PKG + "join";

        // -- USER-FACING lite natives (product surface): bare-name
        // resolvable. 'navigate' is the relation-navigation extension
        // the integration tests pin from user query text (it subsumed
        // the deleted traverse machinery; zero internal emitters).
        // 'sourceUrl' is the data-URI relation source, DELIBERATELY
        // user-callable (SourceUrlUserCallableTest javadoc: "not just
        // inside synthesised mapping bodies") — it also has internal
        // emitters, which spell this constant. The 08-14 census had
        // mis-filed both as internal.
        public static final String NAVIGATE = PKG + "navigate";
        public static final String SOURCE_URL = PKG + "sourceUrl";

        private Lite() {
        }
    }

    private static String liteLocalName(String fqn) {
        return fqn.substring(Lite.PKG.length());
    }

    /** Bare names of the internal-desugar IR — the governance census
     *  surface, DERIVED from the {@link Lite} constants (single point
     *  of truth). Pinned shrink-only. */
    public static final java.util.Set<String> INTERNAL_DESUGAR =
            java.util.stream.Stream.of(Lite.CAST_AS_DECLARED,
                    Lite.TYPE_AS_DECLARED, Lite.LEGACY_NAVIGATE,
                    Lite.LEGACY_ASSOC_PREDICATE, Lite.LEGACY_LOCAL_PROPERTY,
                    Lite.OTHERWISE, Lite.PARSE_DATE_FORMAT,
                    Lite.CONVERT_DATE_FORMAT, Lite.CONVERT_DATE_TIME_FORMAT,
                    Lite.CONVERT_TIME_ZONE_FORMAT, Lite.TDS,
                    Lite.ADJUST_TEMPORAL)
                    .map(Pure::liteLocalName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Bare names of the engine-vocabulary typing shims (see
     *  {@link Lite}). Pinned shrink-only. */
    public static final java.util.Set<String> ENGINE_VOCAB_SHIMS =
            java.util.stream.Stream.of(Lite.AVG, Lite.DIVIDE_ROUND,
                    Lite.NOT_EQUAL_ANSI, Lite.SUB, Lite.IS_NUMERIC,
                    Lite.HASH, Lite.JOIN, Lite.LESS_THAN_ANY,
                    Lite.LESS_THAN_EQUAL_ANY, Lite.GREATER_THAN_ANY,
                    Lite.GREATER_THAN_EQUAL_ANY)
                    .map(Pure::liteLocalName)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Bare names of the user-facing lite product natives (see
     *  {@link Lite#NAVIGATE}, {@link Lite#SOURCE_URL}): these STAY
     *  bare-name resolvable. */
    public static final java.util.Set<String> LITE_SURFACE =
            java.util.Set.of(liteLocalName(Lite.NAVIGATE),
                    liteLocalName(Lite.SOURCE_URL));

    /**
     * Translation at the engine-wire DATA BOUNDARY: a name arriving
     * from the engine's relational-operation vocabulary (protocol
     * dynaFns, legacy ~groupBy aggregates) is respelled to its exact
     * lite-internal identity the moment it enters our AST; every other
     * name passes through untouched (it is real pure vocabulary and
     * resolves in the user namespace).
     */
    public static String wireEmissionName(String wireName) {
        return INTERNAL_DESUGAR.contains(wireName)
                || ENGINE_VOCAB_SHIMS.contains(wireName)
                ? Lite.PKG + wireName : wireName;
    }


    /** Every registered native in the lite-internal package — the
     *  governance test's census surface. */
    public static java.util.List<String> liteInternalNatives() {
        return ALL.stream().map(NativeFunctionDefinition::qualifiedName)
                .filter(q -> q.startsWith(Lite.PKG))
                .distinct().sorted().toList();
    }

    /** Snapshot of every Pure native def, in (load-bearing) declaration order. */
    public static List<NativeFunctionDefinition> all() {
        return Collections.unmodifiableList(ALL);
    }

    // ====================================================================
    // Indexed lookup surface — the bootstrap catalog's query API.
    //
    // The catalog is fixed at class-load; the FQN indexes are built once,
    // lazily (the holder idiom guarantees every constant is registered
    // first). Consumers in BOTH phases — NameResolver's prelude (D) and
    // element compilation (F) — read these instead of building private
    // indexes of the same data.
    // ====================================================================

    private static final class Index {
        static final java.util.Map<String, ClassDefinition> CLASS_BY_FQN = new java.util.HashMap<>();
        static final java.util.Map<String, EnumDefinition> ENUM_BY_FQN = new java.util.HashMap<>();
        static final java.util.Map<String, List<NativeFunctionDefinition>> FN_BY_FQN = new java.util.HashMap<>();
        /** bare name -> the USER-RESOLVABLE overloads across packages
         *  (filter ∈ collection+relation, ...). A bare name is a QUERY
         *  against the user's namespace, so this index holds exactly
         *  that namespace: lite-internal defs (desugar IR + engine-vocab
         *  shims) are excluded — internal producers and consumers spell
         *  {@link Pure#lite} and resolve through FN_BY_FQN. LITE_SURFACE
         *  names (user-facing product natives that happen to live in the
         *  lite package) stay. */
        static final java.util.Map<String, List<NativeFunctionDefinition>> FN_BY_BARE = new java.util.HashMap<>();
        /** name -> overload signature keys; nativeNamed's O(1) surface (re-audit M5). */
        static final java.util.Map<String, java.util.Set<String>> KEYS_BY_NAME = new java.util.HashMap<>();

        static {
            for (ClassDefinition cd : ALL_CLASSES) {
                CLASS_BY_FQN.put(cd.qualifiedName(), cd);
            }
            for (EnumDefinition ed : ALL_ENUMS) {
                ENUM_BY_FQN.put(ed.qualifiedName(), ed);
            }
            for (NativeFunctionDefinition nfd : ALL) {
                FN_BY_FQN.computeIfAbsent(nfd.qualifiedName(), k -> new ArrayList<>()).add(nfd);
                String bare = nfd.qualifiedName().contains("::")
                        ? nfd.qualifiedName().substring(nfd.qualifiedName().lastIndexOf("::") + 2)
                        : nfd.qualifiedName();
                boolean userResolvable = !nfd.qualifiedName().startsWith(Lite.PKG)
                        || LITE_SURFACE.contains(bare);
                if (userResolvable) {
                    FN_BY_BARE.computeIfAbsent(bare, k -> new ArrayList<>()).add(nfd);
                }
                // keys index serves BOTH spellings (registration tables
                // use bare) — the bare spelling under the same partition
                // rule as FN_BY_BARE.
                KEYS_BY_NAME.computeIfAbsent(nfd.qualifiedName(), k -> new java.util.HashSet<>())
                        .add(nfd.signatureKey());
                if (userResolvable) {
                    KEYS_BY_NAME.computeIfAbsent(bare, k -> new java.util.HashSet<>())
                            .add(nfd.signatureKey());
                }
            }
        }
    }

    /** The native class registered at {@code fqn}, if any. */
    public static java.util.Optional<ClassDefinition> findNativeClass(String fqn) {
        return java.util.Optional.ofNullable(Index.CLASS_BY_FQN.get(fqn));
    }

    /** The native enumeration registered at {@code fqn}, if any. */
    public static java.util.Optional<EnumDefinition> findNativeEnum(String fqn) {
        return java.util.Optional.ofNullable(Index.ENUM_BY_FQN.get(fqn));
    }

    /** Every native overload registered at {@code fqn} (empty when none). */
    /**
     * Whether {@code signatureKey} identifies one of the native overloads
     * registered at {@code name} — the parser-node-free membership test for
     * identity-keyed consumers (AUDIT_2026_07 §1c).
     */
    /**
     * The signature KEYS of every native overload registered at {@code name}
     * — the parser-node-free registration surface for the lowering's rule
     * tables (AUDIT_2026_07 §1c: dispatch identity crosses as STRINGS).
     */
    public static List<String> nativeKeysAt(String name) {
        List<String> keys = new ArrayList<>();
        for (var f : nativeFunctionsAt(name)) {
            keys.add(f.signatureKey());
        }
        return keys;
    }

    /**
     * Signature keys of the overloads at {@code name} with exactly
     * {@code arity} parameters — for dispatch tables (the lowering's
     * pinned surface) that must select overloads without touching the
     * model type (audit 22a M5: the isDistinct GROUP marker must never
     * catch the legacy 2-arg overload).
     */
    public static List<String> nativeKeysAt(String name, int arity) {
        List<String> keys = new ArrayList<>();
        for (var f : nativeFunctionsAt(name)) {
            if (f.parameters().size() == arity) {
                keys.add(f.signatureKey());
            }
        }
        return keys;
    }

    /**
     * Signature keys of the overloads at {@code name} that take a parameter
     * whose type is the EXACT class {@code paramClassFqn} (audit 15:
     * replaces the lowering's {@code contains("_Window")} key probe —
     * identification is by full FQN, never substring).
     */
    public static List<String> nativeKeysAt(String name, String paramClassFqn) {
        List<String> keys = new ArrayList<>();
        for (var f : nativeFunctionsAt(name)) {
            for (var prm : f.parameters()) {
                String head = switch (prm.type()) {
                    case com.legend.protocol.TypeExpression.NameRef nr -> nr.name();
                    case com.legend.protocol.TypeExpression.Generic g -> g.name();
                    default -> null;
                };
                if (paramClassFqn.equals(head)) {
                    keys.add(f.signatureKey());
                    break;
                }
            }
        }
        return keys;
    }

    /**
     * Signature keys of specific overloads the lowering must single out
     * (string CONCAT-plus; IN) — parser records stay behind this wall.
     */
    public static String keyPlusString() {
        return PLUS__STRING_1__STRING_1.signatureKey();
    }

    public static String keyIn() {
        return IN__ANY_1__ANY_MANY.signatureKey();
    }

    /** The real second overload: in(value:Any[0..1], ...) — an empty needle is FALSE. */
    public static String keyInOptional() {
        return IN__ANY_0_1__ANY_MANY.signatureKey();
    }

    public static boolean nativeNamed(String name, String signatureKey) {
        return Index.KEYS_BY_NAME
                .getOrDefault(name, java.util.Set.of())
                .contains(signatureKey);
    }

    public static List<NativeFunctionDefinition> nativeFunctionsAt(String name) {
        // FQN-keyed catalog with a BARE-NAME secondary index: a qualified
        // lookup resolves its exact package; a bare lookup returns the union
        // of overloads across packages (overload resolution picks by shape).
        if (name.contains("::")) {
            return Index.FN_BY_FQN.getOrDefault(name, List.of());
        }
        return Index.FN_BY_BARE.getOrDefault(name, List.of());
    }

    /** All native class FQNs — the resolver's prelude / known-FQN universe. */
    public static java.util.Set<String> nativeClassFqns() {
        return Collections.unmodifiableSet(Index.CLASS_BY_FQN.keySet());
    }

    /** All native enumeration FQNs — the resolver's prelude / known-FQN universe. */
    public static java.util.Set<String> nativeEnumFqns() {
        return Collections.unmodifiableSet(Index.ENUM_BY_FQN.keySet());
    }

    /**
     * Parse a Pure native signature through {@link ElementParser} and stash
     * the resulting record. Class-load fails if the signature is malformed
     * or if {@code ElementParser} refuses any grammar form &mdash; that is
     * the comprehensive parse-coverage guarantee.
     */
    private static NativeFunctionDefinition signature(String pureSignature) {
        var parsed = ElementParser.parse(pureSignature,
                com.legend.parser.Dialect.LEGEND_PLATFORM);
        if (parsed.elements().size() != 1) {
            throw new IllegalStateException(
                    "expected exactly one element parsed from: " + pureSignature
                            + " (got " + parsed.elements().size() + ")");
        }
        var el = parsed.elements().get(0);
        if (!(el instanceof NativeFunctionDefinition def)) {
            throw new IllegalStateException(
                    "expected NativeFunctionDefinition but got " + el.getClass().getSimpleName()
                            + " from: " + pureSignature);
        }
        ALL.add(def);
        return def;
    }

    // real graphFetch.pure:126-171 — the alloyConfig ctor family (every
    // overload constructs an AlloySerializationConfig; the envelope reads
    // the CALL structurally by arity)
    public static final NativeFunctionDefinition ALLOY_CONFIG__4 = signature("native function meta::pure::graphFetch::execution::alloyConfig(includeType:meta::pure::metamodel::type::Boolean[1], includeEnumType:meta::pure::metamodel::type::Boolean[1], removePropertiesWithNullValues:meta::pure::metamodel::type::Boolean[1], removePropertiesWithEmptySets:meta::pure::metamodel::type::Boolean[1]):meta::pure::graphFetch::execution::AlloySerializationConfig[1];");
    public static final NativeFunctionDefinition ALLOY_CONFIG__5 = signature("native function meta::pure::graphFetch::execution::alloyConfig(includeType:meta::pure::metamodel::type::Boolean[1], includeEnumType:meta::pure::metamodel::type::Boolean[1], removePropertiesWithNullValues:meta::pure::metamodel::type::Boolean[1], removePropertiesWithEmptySets:meta::pure::metamodel::type::Boolean[1], includeObjectReference:meta::pure::metamodel::type::Boolean[1]):meta::pure::graphFetch::execution::AlloySerializationConfig[1];");
    public static final NativeFunctionDefinition ALLOY_CONFIG__6 = signature("native function meta::pure::graphFetch::execution::alloyConfig(includeType:meta::pure::metamodel::type::Boolean[1], includeEnumType:meta::pure::metamodel::type::Boolean[1], removePropertiesWithNullValues:meta::pure::metamodel::type::Boolean[1], removePropertiesWithEmptySets:meta::pure::metamodel::type::Boolean[1], typeString:meta::pure::metamodel::type::String[1], fullyQualifiedTypePath:meta::pure::metamodel::type::Boolean[1]):meta::pure::graphFetch::execution::AlloySerializationConfig[1];");
    public static final NativeFunctionDefinition ALLOY_CONFIG__7 = signature("native function meta::pure::graphFetch::execution::alloyConfig(includeType:meta::pure::metamodel::type::Boolean[1], includeEnumType:meta::pure::metamodel::type::Boolean[1], removePropertiesWithNullValues:meta::pure::metamodel::type::Boolean[1], removePropertiesWithEmptySets:meta::pure::metamodel::type::Boolean[1], typeString:meta::pure::metamodel::type::String[1], fullyQualifiedTypePath:meta::pure::metamodel::type::Boolean[1], includeObjectReference:meta::pure::metamodel::type::Boolean[1]):meta::pure::graphFetch::execution::AlloySerializationConfig[1];");
    public static final NativeFunctionDefinition ALLOY_CONFIG__8 = signature("native function meta::pure::graphFetch::execution::alloyConfig(includeType:meta::pure::metamodel::type::Boolean[1], includeEnumType:meta::pure::metamodel::type::Boolean[1], dateTimeFormat:meta::pure::metamodel::type::String[1], removePropertiesWithNullValues:meta::pure::metamodel::type::Boolean[1], removePropertiesWithEmptySets:meta::pure::metamodel::type::Boolean[1], typeString:meta::pure::metamodel::type::String[1], fullyQualifiedTypePath:meta::pure::metamodel::type::Boolean[1], includeObjectReference:meta::pure::metamodel::type::Boolean[1]):meta::pure::graphFetch::execution::AlloySerializationConfig[1];");
    public static final NativeFunctionDefinition ABS__T_1 = signature("native function meta::pure::functions::math::abs<T>(number:T[1]):T[1];");
    public static final NativeFunctionDefinition ACOS__NUMBER_1 = signature("native function meta::pure::functions::math::acos(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition ADD__T_MANY__INTEGER_1__T_1 = signature("native function meta::pure::functions::collection::add<T>(set:T[*], index:meta::pure::metamodel::type::Integer[1], val:T[1]):T[*];");
    public static final NativeFunctionDefinition ADD__T_MANY__T_1 = signature("native function meta::pure::functions::collection::add<T>(set:T[*], val:T[1]):T[*];");

    // CALENDAR-AGGREGATION natives (engine calendarFunctions.pure —
    // 32 fns, one shape; lowered as CASE-conditioned aggregates over
    // the LegendCalendarSchema calendar table, task G1):
    public static final NativeFunctionDefinition CAL_ANNUALIZED = signature("native function meta::pure::functions::date::calendar::annualized(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_CME = signature("native function meta::pure::functions::date::calendar::cme(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_CW = signature("native function meta::pure::functions::date::calendar::cw(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_CW_FM = signature("native function meta::pure::functions::date::calendar::cw_fm(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_C_Y_MINUS2 = signature("native function meta::pure::functions::date::calendar::CYMinus2(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_C_Y_MINUS3 = signature("native function meta::pure::functions::date::calendar::CYMinus3(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_MTD = signature("native function meta::pure::functions::date::calendar::mtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P12WA = signature("native function meta::pure::functions::date::calendar::p12wa(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P12WTD = signature("native function meta::pure::functions::date::calendar::p12wtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P4WA = signature("native function meta::pure::functions::date::calendar::p4wa(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P4WTD = signature("native function meta::pure::functions::date::calendar::p4wtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P52WTD = signature("native function meta::pure::functions::date::calendar::p52wtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P52WA = signature("native function meta::pure::functions::date::calendar::p52wa(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_P12MTD = signature("native function meta::pure::functions::date::calendar::p12mtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PMA = signature("native function meta::pure::functions::date::calendar::pma(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PMTD = signature("native function meta::pure::functions::date::calendar::pmtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PQTD = signature("native function meta::pure::functions::date::calendar::pqtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PRIOR_DAY = signature("native function meta::pure::functions::date::calendar::priorDay(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PRIOR_YEAR = signature("native function meta::pure::functions::date::calendar::priorYear(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PW = signature("native function meta::pure::functions::date::calendar::pw(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PW_FM = signature("native function meta::pure::functions::date::calendar::pw_fm(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PWA = signature("native function meta::pure::functions::date::calendar::pwa(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PWTD = signature("native function meta::pure::functions::date::calendar::pwtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PYMTD = signature("native function meta::pure::functions::date::calendar::pymtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PYQTD = signature("native function meta::pure::functions::date::calendar::pyqtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PYTD = signature("native function meta::pure::functions::date::calendar::pytd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PYWA = signature("native function meta::pure::functions::date::calendar::pywa(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_PYWTD = signature("native function meta::pure::functions::date::calendar::pywtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_QTD = signature("native function meta::pure::functions::date::calendar::qtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_REPORT_END_DAY = signature("native function meta::pure::functions::date::calendar::reportEndDay(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_WTD = signature("native function meta::pure::functions::date::calendar::wtd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CAL_YTD = signature("native function meta::pure::functions::date::calendar::ytd(date:meta::pure::metamodel::type::Date[0..1], calendarType:meta::pure::metamodel::type::String[1], endDate:meta::pure::metamodel::type::Date[1], value:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition ADJUST__DATE_1__INTEGER_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::adjust(d:meta::pure::metamodel::type::Date[1], amount:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Date[1];");
    // adjustTemporal: identical shape to adjust — the internal legacy-print
    // channel marker (Pure.Lite.ADJUST_TEMPORAL javadoc has the two-channel
    // engine evidence).
    public static final NativeFunctionDefinition ADJUST_TEMPORAL__DATE_1__INTEGER_1__DURATION_UNIT_1 = signature("native function meta::legend::lite::adjustTemporal(d:meta::pure::metamodel::type::Date[1], amount:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition AGGREGATE__RELATION_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::aggregate<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<R>[1];");
    public static final NativeFunctionDefinition AGGREGATE__RELATION_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::aggregate<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<R>[1];");
    public static final NativeFunctionDefinition AND__BOOLEAN_1__BOOLEAN_1 = signature("native function meta::pure::functions::boolean::and(left:meta::pure::metamodel::type::Boolean[1], right:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition AND__BOOLEAN_MANY = signature("native function meta::pure::functions::collection::and(bools:meta::pure::metamodel::type::Boolean[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition ASCENDING__COL_SPEC_1 = signature("native function meta::pure::functions::relation::ascending<T>(column:meta::pure::metamodel::relation::ColSpec<T>[1]):meta::pure::functions::relation::SortInfo<T>[1];");
    public static final NativeFunctionDefinition ASCII__STRING_1 = signature("native function meta::pure::functions::string::ascii(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition ASC__COL_SPEC_1 = signature("native function meta::pure::tds::asc<T>(column:meta::pure::metamodel::relation::ColSpec<T>[1]):meta::pure::functions::relation::SortInfo<T>[1];");
    public static final NativeFunctionDefinition ASIN__NUMBER_1 = signature("native function meta::pure::functions::math::asin(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition AS_OF_JOIN__RELATION_1__RELATION_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::asOfJoin<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], match:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    public static final NativeFunctionDefinition AS_OF_JOIN__RELATION_1__RELATION_1__FUNCTION_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::asOfJoin<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], match:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], join:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    public static final NativeFunctionDefinition AS_OF_JOIN__RELATION_1__RELATION_1__FUNCTION_1__FUNCTION_1__STRING_1 = signature("native function meta::pure::functions::relation::asOfJoin<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], match:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], join:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], prefix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    public static final NativeFunctionDefinition ATAN2__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::atan2(y:meta::pure::metamodel::type::Number[1], x:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition ATAN__NUMBER_1 = signature("native function meta::pure::functions::math::atan(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition AT__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::collection::at<T>(set:T[*], index:meta::pure::metamodel::type::Integer[1]):T[1];");
    public static final NativeFunctionDefinition AVERAGE_RANK = signature("native function meta::pure::functions::math::olap::averageRank():meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition AVERAGE__NUMBER_MANY = signature("native function meta::pure::functions::math::average(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Float[1];");
    // WINDOW FAMILY — VERIFIED per function 2026-07-08 against real checkouts;
    // now FULLY FAITHFUL: ranking and
    // slice are verbatim (core_functions_relation/relation/functions/
    // {ranking,slice}); the 4-arg colToAgg aggregates below (average,
    // stdDevPopulation — the ONLY aggregate window functions real pure has;
    // everything else windows via the agg-col spelling
    // ~c:{p,w,r|$r.col}:y|$y->sum()) are verbatim core_functions_standard/
    // math/aggregator. The old engine-lite 3-arg row-returning aggregate
    // forms were MADE UP (never in real pure, unlowerable, exercised only by
    // engine-lite-authored tests since rewritten) and are DELETED.
    // over(): verify the ⊆-constrained args + the String[*] overload in 4c.
    public static final NativeFunctionDefinition AVERAGE__RELATION_1__WINDOW_1__T_1__COL_SPEC_1 = signature("native function meta::pure::functions::math::average<T>(partition:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], row:T[1], colToAgg:meta::pure::metamodel::relation::ColSpec<(?:meta::pure::metamodel::type::Number)⊆T>[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition AVG__NUMBER_MANY = signature("native function meta::legend::lite::avg(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition BETWEEN__NUMBER = signature("native function meta::pure::functions::boolean::between(value:meta::pure::metamodel::type::Number[0..1], lower:meta::pure::metamodel::type::Number[0..1], upper:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition BETWEEN__STRING = signature("native function meta::pure::functions::boolean::between(value:meta::pure::metamodel::type::String[0..1], lower:meta::pure::metamodel::type::String[0..1], upper:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition BETWEEN__STRICT_DATE = signature("native function meta::pure::functions::boolean::between(value:meta::pure::metamodel::type::StrictDate[0..1], lower:meta::pure::metamodel::type::StrictDate[0..1], upper:meta::pure::metamodel::type::StrictDate[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition BETWEEN__DATE_TIME = signature("native function meta::pure::functions::boolean::between(value:meta::pure::metamodel::type::DateTime[0..1], lower:meta::pure::metamodel::type::DateTime[0..1], upper:meta::pure::metamodel::type::DateTime[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition BIT_AND__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::bitAnd(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition BIT_OR__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::bitOr(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition BIT_SHIFT_LEFT__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::bitShiftLeft(value:meta::pure::metamodel::type::Integer[1], bits:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition BIT_SHIFT_RIGHT__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::bitShiftRight(value:meta::pure::metamodel::type::Integer[1], bits:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition BIT_XOR__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::bitXor(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition CAST__ANY_m__T_1 = signature("native function meta::pure::functions::lang::cast<T|m>(source:meta::pure::metamodel::type::Any[m], type:T[1]):T[m];");
    public static final NativeFunctionDefinition SUB_TYPE__ANY_m__T_1 = signature("native function meta::pure::functions::lang::subType<T|m>(source:meta::pure::metamodel::type::Any[m], object:T[1]):T[m];");
    public static final NativeFunctionDefinition CBRT__NUMBER_1 = signature("native function meta::pure::functions::math::cbrt(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition CEILING__NUMBER_1 = signature("native function meta::pure::functions::math::ceiling(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition CHAR__INTEGER_1 = signature("native function meta::pure::functions::string::char(code:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    // coalesce: REAL pure is GENERIC (legend-engine core_functions_unclassified/flow/coalesce.pure)
    // — six overloads: 1-3 optional values, ifEmpty either [1] (result [1]) or [0..1] (result [0..1]).
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value:T[0..1], ifEmpty:T[1]):T[1];");
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_0_1__T_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], ifEmpty:T[1]):T[1];");
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_0_1__T_0_1__T_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], value3:T[0..1], ifEmpty:T[1]):T[1];");
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_0_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value:T[0..1], ifEmpty:T[0..1]):T[0..1];");
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_0_1__T_0_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], ifEmpty:T[0..1]):T[0..1];");
    public static final NativeFunctionDefinition COALESCE__T_0_1__T_0_1__T_0_1__T_0_1 = signature("native function meta::pure::functions::flow::coalesce<T>(value1:T[0..1], value2:T[0..1], value3:T[0..1], ifEmpty:T[0..1]):T[0..1];");
    public static final NativeFunctionDefinition COMPARE__ANY_1__ANY_1 = signature("native function meta::pure::functions::lang::compare(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition CONCATENATE__T_MANY__T_MANY = signature("native function meta::pure::functions::collection::concatenate<T>(set1:T[*], set2:T[*]):T[*];");
    public static final NativeFunctionDefinition CONCATENATE__RELATION_1__RELATION_1 = signature("native function meta::pure::functions::relation::concatenate<T>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition CONTAINS__ANY_MANY__ANY_1 = signature("native function meta::pure::functions::collection::contains(collection:meta::pure::metamodel::type::Any[*], val:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition CONTAINS__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::contains(source:meta::pure::metamodel::type::String[1], val:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition CONTAINS__T_MANY__T_1__FUNCTION_1 = signature("native function meta::pure::functions::collection::contains<T>(collection:T[*], val:T[1], comparator:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition CORR__NUMBER_MANY__NUMBER_MANY = signature("native function meta::pure::functions::math::corr(x:meta::pure::metamodel::type::Number[*], y:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition CORR__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::corr<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition COSH__NUMBER_1 = signature("native function meta::pure::functions::math::cosh(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition COS__NUMBER_1 = signature("native function meta::pure::functions::math::cos(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition COT__NUMBER_1 = signature("native function meta::pure::functions::math::cot(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition COUNT__T_MANY = signature("native function meta::pure::functions::collection::count<T>(values:T[*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition COVAR_POPULATION__NUMBER_MANY__NUMBER_MANY = signature("native function meta::pure::functions::math::covarPopulation(x:meta::pure::metamodel::type::Number[*], y:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition COVAR_POPULATION__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::covarPopulation<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition COVAR_SAMPLE__NUMBER_MANY__NUMBER_MANY = signature("native function meta::pure::functions::math::covarSample(x:meta::pure::metamodel::type::Number[*], y:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition COVAR_SAMPLE__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::covarSample<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition CUMULATIVE_DISTRIBUTION__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::cumulativeDistribution<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], w:meta::pure::functions::relation::_Window<T>[1], row:T[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition CURRENT_USER_ID = signature("native function meta::pure::functions::runtime::currentUserId():meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DATE_DIFF__DATE_1__DATE_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::dateDiff(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1], du:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition DATE_PART__DATE_1 = signature("native function meta::pure::functions::date::datePart(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1], month:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1], month:meta::pure::metamodel::type::Integer[1], day:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1__INTEGER_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1], month:meta::pure::metamodel::type::Integer[1], day:meta::pure::metamodel::type::Integer[1], hour:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1__INTEGER_1__INTEGER_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1], month:meta::pure::metamodel::type::Integer[1], day:meta::pure::metamodel::type::Integer[1], hour:meta::pure::metamodel::type::Integer[1], minute:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition DATE__INTEGER_1__INTEGER_1__INTEGER_1__INTEGER_1__INTEGER_1__NUMBER_1 = signature("native function meta::pure::functions::date::date(year:meta::pure::metamodel::type::Integer[1], month:meta::pure::metamodel::type::Integer[1], day:meta::pure::metamodel::type::Integer[1], hour:meta::pure::metamodel::type::Integer[1], minute:meta::pure::metamodel::type::Integer[1], second:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition DAY_OF_MONTH__DATE_1 = signature("native function meta::pure::functions::date::dayOfMonth(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition DAY_OF_WEEK_NUMBER__DATE_1 = signature("native function meta::pure::functions::date::dayOfWeekNumber(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    // 2-arg engine overload (dayOfWeekNumber.pure:15-24; the constraint is
    // firstDayMondayOrSundayOnly — ledger cluster 25)
    public static final NativeFunctionDefinition DAY_OF_WEEK_NUMBER__DATE_1__DAY_OF_WEEK_1 = signature("native function meta::pure::functions::date::dayOfWeekNumber(d:meta::pure::metamodel::type::Date[1], firstDay:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition DAY_OF_WEEK__DATE_1 = signature("native function meta::pure::functions::date::dayOfWeek(d:meta::pure::metamodel::type::Date[1]):meta::pure::functions::date::DayOfWeek[1];");
    // day-of-week anchored shifts (engine pureToSQLQuery dyna pairs; the
    // H2 dialect emission is the semantic source — duckdbExtension has
    // them commented out): mostRecent = latest date <= anchor on the
    // target day (same-day allowed); previous excludes the anchor day.
    public static final NativeFunctionDefinition MOST_RECENT_DAY_OF_WEEK__DAY_1 = signature("native function meta::pure::functions::date::mostRecentDayOfWeek(day:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition MOST_RECENT_DAY_OF_WEEK__DATE_1__DAY_1 = signature("native function meta::pure::functions::date::mostRecentDayOfWeek(d:meta::pure::metamodel::type::Date[1], day:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition PREVIOUS_DAY_OF_WEEK__DAY_1 = signature("native function meta::pure::functions::date::previousDayOfWeek(day:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition PREVIOUS_DAY_OF_WEEK__DATE_1__DAY_1 = signature("native function meta::pure::functions::date::previousDayOfWeek(d:meta::pure::metamodel::type::Date[1], day:meta::pure::functions::date::DayOfWeek[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition FIRST_DAY_OF_WEEK__DATE_1 = signature("native function meta::pure::functions::date::firstDayOfWeek(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition DAY_OF_YEAR__DATE_1 = signature("native function meta::pure::functions::date::dayOfYear(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition DECODE_BASE64__STRING_1 = signature("native function meta::pure::functions::string::decodeBase64(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DENSE_RANK__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::denseRank<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], w:meta::pure::functions::relation::_Window<T>[1], row:T[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition DESCENDING__COL_SPEC_1 = signature("native function meta::pure::functions::relation::descending<T>(column:meta::pure::metamodel::relation::ColSpec<T>[1]):meta::pure::functions::relation::SortInfo<T>[1];");
    public static final NativeFunctionDefinition DESC__COL_SPEC_1 = signature("native function meta::pure::tds::desc<T>(column:meta::pure::metamodel::relation::ColSpec<T>[1]):meta::pure::functions::relation::SortInfo<T>[1];");
    public static final NativeFunctionDefinition DISTINCT__RELATION_1 = signature("native function meta::pure::functions::relation::distinct<T>(rel:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition DISTINCT__RELATION_1__COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::distinct<X,T>(rel:meta::pure::metamodel::relation::Relation<T>[1], columns:meta::pure::metamodel::relation::ColSpecArray<X⊆T>[1]):meta::pure::metamodel::relation::Relation<X>[1];");
    public static final NativeFunctionDefinition IS_NUMERIC__STRING_0_1 = signature("native function meta::legend::lite::isNumeric(str:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[0..1];");
    public static final NativeFunctionDefinition CONVERT_TIME_ZONE_FORMAT__DATE_0_1__STRING_1__STRING_1 = signature("native function meta::legend::lite::convertTimeZoneFormat(d:meta::pure::metamodel::type::DateTime[0..1], tz:meta::pure::metamodel::type::String[1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[0..1];");

    /** Relational FORMAT dynafunctions (convertDate('MMMyyyy') et al) — lite natives. */
    public static final NativeFunctionDefinition CONVERT_DATE_FORMAT__STRING_0_1__STRING_1 = signature("native function meta::legend::lite::convertDateFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::StrictDate[0..1];");
    public static final NativeFunctionDefinition CONVERT_DATE_TIME_FORMAT__STRING_0_1__STRING_1 = signature("native function meta::legend::lite::convertDateTimeFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::DateTime[0..1];");
    public static final NativeFunctionDefinition PARSE_DATE_FORMAT__STRING_0_1__STRING_1 = signature("native function meta::legend::lite::parseDateFormat(str:meta::pure::metamodel::type::String[0..1], fmt:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::DateTime[0..1];");
    public static final NativeFunctionDefinition DIVIDE_ROUND__NUMBER_1__NUMBER_1__INTEGER_1 = signature("native function meta::legend::lite::divideRound(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition DIVIDE__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::divide(dividend:meta::pure::metamodel::type::Number[1], divisor:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition DIVIDE__NUMBER_1__NUMBER_1__INTEGER_1 = signature("native function meta::pure::functions::math::divide(dividend:meta::pure::metamodel::type::Number[1], divisor:meta::pure::metamodel::type::Number[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition DROP__RELATION_1__INTEGER_1 = signature("native function meta::pure::functions::relation::drop<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], size:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition DROP__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::collection::drop<T>(set:T[*], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition ENCODE_BASE64__STRING_1 = signature("native function meta::pure::functions::string::encodeBase64(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition ENDS_WITH__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::endsWith(source:meta::pure::metamodel::type::String[1], val:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    // VERIFIED vs real legend-pure grammar/functions/boolean/equality/equal.pure:
    // equal(left:Any[*], right:Any[*]):Boolean[1] — collection equality is part
    // of the contract (identity/primitive/collection/model-defined equality).
    public static final NativeFunctionDefinition EQUAL__ANY_MANY__ANY_MANY = signature("native function meta::pure::functions::boolean::equal(left:meta::pure::metamodel::type::Any[*], right:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition EQ__ANY_1__ANY_1 = signature("native function meta::pure::functions::boolean::eq(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    // VERBATIM real pure (platform/pure/essential/lang/eval/eval.pure),
    // arities 1-3 (real pure goes to 8; add on demand). Typed via the
    // kernel's FunctionType unification for function VALUES; lambda-literal
    // and colspec sources short-circuit in EvalChecker.
    public static final NativeFunctionDefinition EVAL__FUNCTION_1 = signature("native function meta::pure::functions::lang::eval<V|m>(func:meta::pure::metamodel::function::Function<{->V[m]}>[1]):V[m];");
    public static final NativeFunctionDefinition EVAL__FUNCTION_1__T_n = signature("native function meta::pure::functions::lang::eval<T,V|m,n>(func:meta::pure::metamodel::function::Function<{T[n]->V[m]}>[1], param:T[n]):V[m];");
    public static final NativeFunctionDefinition EVAL__FUNCTION_1__T_n__U_p = signature("native function meta::pure::functions::lang::eval<T,U,V|m,n,p>(func:meta::pure::metamodel::function::Function<{T[n],U[p]->V[m]}>[1], param1:T[n], param2:U[p]):V[m];");
    public static final NativeFunctionDefinition EXISTS__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::exists<T>(value:T[*], func:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition EXP__NUMBER_1 = signature("native function meta::pure::functions::math::exp(exponent:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition EXTEND__C_MANY__FUNC_COL_SPEC_1 = signature("native function meta::pure::functions::relation::extend<C,Z>(cl:C[*], f:meta::pure::metamodel::relation::FuncColSpec<{C[1]->meta::pure::metamodel::type::Any[0..1]},Z>[1]):C[*];");
    public static final NativeFunctionDefinition EXTEND__C_MANY__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<C,Z>(cl:C[*], fs:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},Z>[1]):C[*];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::extend<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__FUNC_COL_SPEC_1 = signature("native function meta::pure::functions::relation::extend<T,Z>(r:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::metamodel::relation::FuncColSpec<{T[1]->meta::pure::metamodel::type::Any[0..1]},Z>[1]):meta::pure::metamodel::relation::Relation<T+Z>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<T,Z>(r:meta::pure::metamodel::relation::Relation<T>[1], fs:meta::pure::metamodel::relation::FuncColSpecArray<{T[1]->meta::pure::metamodel::type::Any[*]},Z>[1]):meta::pure::metamodel::relation::Relation<T+Z>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__WINDOW_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::extend<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{meta::pure::metamodel::relation::Relation<T>[1],meta::pure::functions::relation::_Window<T>[1],T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__WINDOW_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<T,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{meta::pure::metamodel::relation::Relation<T>[1],meta::pure::functions::relation::_Window<T>[1],T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__WINDOW_1__FUNC_COL_SPEC_1 = signature("native function meta::pure::functions::relation::extend<T,Z,W,R>(r:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], f:meta::pure::metamodel::relation::FuncColSpec<{meta::pure::metamodel::relation::Relation<T>[1],meta::pure::functions::relation::_Window<T>[1],T[1]->meta::pure::metamodel::type::Any[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition EXTEND__RELATION_1__WINDOW_1__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::extend<T,Z,W,R>(r:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], f:meta::pure::metamodel::relation::FuncColSpecArray<{meta::pure::metamodel::relation::Relation<T>[1],meta::pure::functions::relation::_Window<T>[1],T[1]->meta::pure::metamodel::type::Any[*]},R>[1]):meta::pure::metamodel::relation::Relation<T+R>[1];");
    public static final NativeFunctionDefinition FILTER__RELATION_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::filter<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    // the TDS-era FQN spelling (real tds.pure filter over TabularDataSet;
    // the corpus's tableToTDS chains call it FULLY QUALIFIED)
    public static final NativeFunctionDefinition TDS_FILTER__RELATION_1__FUNCTION_1 = signature("native function meta::pure::tds::filter<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition FILTER__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::filter<T>(value:T[*], func:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):T[*];");
    public static final NativeFunctionDefinition FIND__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::find<T>(value:T[*], func:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):T[0..1];");
    public static final NativeFunctionDefinition FIRST_DAY_OF_MONTH__DATE_1 = signature("native function meta::pure::functions::date::firstDayOfMonth(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition FIRST_DAY_OF_QUARTER__DATE_1 = signature("native function meta::pure::functions::date::firstDayOfQuarter(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition FIRST_DAY_OF_YEAR__DATE_1 = signature("native function meta::pure::functions::date::firstDayOfYear(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Date[1];");
    /** Pure-code composition in real pure (dateExtension.pure:482 — today()->firstDayOfYear()); platform-native here, composed BY EMISSION. */
    /** Real pure platform/pure/grammar/functions/lang/enum/extractEnumValue.pure:25; the TYPER constant-folds literal calls to the enum value (special form). */
    public static final NativeFunctionDefinition EXTRACT_ENUM_VALUE = signature("native function meta::pure::functions::lang::extractEnumValue<T>(enum:meta::pure::metamodel::type::Enumeration<T>[1], value:meta::pure::metamodel::type::String[1]):T[1];");
    public static final NativeFunctionDefinition FIRST_DAY_OF_THIS_YEAR = signature("native function meta::pure::functions::date::firstDayOfThisYear():meta::pure::metamodel::type::Date[1];");
    /** Real pure dateExtension.pure:472. */
    public static final NativeFunctionDefinition FIRST_DAY_OF_THIS_MONTH = signature("native function meta::pure::functions::date::firstDayOfThisMonth():meta::pure::metamodel::type::Date[1];");
    /** Real pure dateExtension.pure:187 — StrictDate[1]. */
    public static final NativeFunctionDefinition FIRST_DAY_OF_THIS_QUARTER = signature("native function meta::pure::functions::date::firstDayOfThisQuarter():meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition FIRST_HOUR_OF_DAY__DATE_1 = signature("native function meta::pure::functions::date::firstHourOfDay(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition FIRST_MILLISECOND_OF_SECOND__DATE_1 = signature("native function meta::pure::functions::date::firstMillisecondOfSecond(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition FIRST_MINUTE_OF_HOUR__DATE_1 = signature("native function meta::pure::functions::date::firstMinuteOfHour(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition FIRST_SECOND_OF_MINUTE__DATE_1 = signature("native function meta::pure::functions::date::firstSecondOfMinute(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::DateTime[1];");
    // Param names w:Relation, f:_Window are VERBATIM real pure
    // (core_functions_relation/relation/functions/slice/first.pure) — kept
    // faithful even though they read swapped.
    public static final NativeFunctionDefinition FIRST__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::first<T>(w:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::functions::relation::_Window<T>[1], r:T[1]):T[0..1];");
    public static final NativeFunctionDefinition FIRST__T_MANY = signature("native function meta::pure::functions::collection::first<T>(set:T[*]):T[0..1];");
    public static final NativeFunctionDefinition FIRST__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::collection::first<T>(set:T[*], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition FLATTEN__T_MANY__COL_SPEC_1 = signature("native function meta::pure::functions::relation::variant::flatten<T,Z>(valueToFlatten:T[*], columnWithFlattenedValue:meta::pure::metamodel::relation::ColSpec<Z=(?:T)>[1]):meta::pure::metamodel::relation::Relation<Z>[1];");
    public static final NativeFunctionDefinition FLOOR__NUMBER_1 = signature("native function meta::pure::functions::math::floor(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition FOLD__T_MANY__FUNCTION_1__V_m = signature("native function meta::pure::functions::collection::fold<T,V|m>(source:T[*], lambda:meta::pure::metamodel::function::Function<{T[1],V[m]->V[m]}>[1], init:V[m]):V[m];");
    public static final NativeFunctionDefinition FORMAT__STRING_1__ANY_MANY = signature("native function meta::pure::functions::string::format(format:meta::pure::metamodel::type::String[1], args:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition FOR_ALL__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::forAll<T>(value:T[*], func:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition FROM_EPOCH_VALUE__INTEGER_1 = signature("native function meta::pure::functions::date::fromEpochValue(epoch:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition FROM_EPOCH_VALUE__INTEGER_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::fromEpochValue(epoch:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition FROM__RELATION_1 = signature("native function meta::pure::mapping::from<T>(source:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition FROM__RELATION_1__ANY_1 = signature("native function meta::pure::mapping::from<T>(source:meta::pure::metamodel::relation::Relation<T>[1], runtime:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    // REAL pure is multiplicity-preserving (mappingExtension.pure:297
    // from<T|m>(t:T[m], m:Mapping[1], r:PackageableRuntime[1]):T[m]) —
    // the erased T[*] form broke toString(serialize(...)->from(...))
    public static final NativeFunctionDefinition FROM__T_MANY__ANY_1__ANY_1 = signature("native function meta::pure::mapping::from<T|m>(source:T[m], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1]):T[m];");
    // engine Handlers.java:2223 withChainedMappings_T_m__Mapping_MANY__T_m_
    // — identity on the stream, tagging CHAINED mappings (the M2M2R
    // query-side chain channel; FromChecker absorbs it into
    // TypedFrom.chainMappings)
    public static final NativeFunctionDefinition WITH_CHAINED_MAPPINGS = signature("native function meta::pure::mapping::withChainedMappings<T>(source:T[*], mappings:meta::pure::mapping::Mapping[*]):T[*];");
    public static final NativeFunctionDefinition GENERATE_GUID = signature("native function meta::pure::functions::string::generation::generateGuid():meta::pure::metamodel::type::String[1];");
    // real legend-pure platform/pure/essential/meta/type/genericType.pure
    public static final NativeFunctionDefinition GENERIC_TYPE__ANY_MANY = signature("native function meta::pure::functions::meta::genericType(any:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::generics::GenericType[1];");
    // real legend-pure platform/pure/essential/meta/instance/getHiddenPayload.pure
    // (compile surface only — reachable solely behind the elementOverride
    // guard, which our execution answers empty)
    public static final NativeFunctionDefinition GET_HIDDEN_PAYLOAD__ANY_1 = signature("native function meta::pure::functions::meta::getHiddenPayload(o:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Any[1];");
    public static final NativeFunctionDefinition GET_ALL__CLASS_1 = signature("native function meta::pure::functions::collection::getAll<T>(class:meta::pure::metamodel::type::Class<T>[1]):T[*];");
    public static final NativeFunctionDefinition GET_ALL__CLASS_1__DATE_1 = signature("native function meta::pure::functions::collection::getAll<T>(class:meta::pure::metamodel::type::Class<T>[1], date:meta::pure::metamodel::type::Date[1]):T[*];");
    public static final NativeFunctionDefinition GET_ALL__CLASS_1__DATE_1__DATE_1 = signature("native function meta::pure::functions::collection::getAll<T>(class:meta::pure::metamodel::type::Class<T>[1], from:meta::pure::metamodel::type::Date[1], to:meta::pure::metamodel::type::Date[1]):T[*];");
    // engine collectionExtension.pure:230 (fail-stub upstream; the
    // relational router lowers it — the per-date extent form)
    public static final NativeFunctionDefinition GET_ALL_FOR_EACH_DATE__CLASS_1__DATE_MANY = signature("native function meta::pure::functions::collection::getAllForEachDate<T>(type:meta::pure::metamodel::type::Class<T>[1], dates:meta::pure::metamodel::type::Date[*]):T[*];");
    public static final NativeFunctionDefinition GET_ALL_VERSIONS__CLASS_1 = signature("native function meta::pure::functions::collection::getAllVersions<T>(class:meta::pure::metamodel::type::Class<T>[1]):T[*];");
    public static final NativeFunctionDefinition GET_ALL_VERSIONS_IN_RANGE__CLASS_1__DATE_1__DATE_1 = signature("native function meta::pure::functions::collection::getAllVersionsInRange<T>(class:meta::pure::metamodel::type::Class<T>[1], start:meta::pure::metamodel::type::Date[1], end:meta::pure::metamodel::type::Date[1]):T[*];");
    public static final NativeFunctionDefinition GET__VARIANT_1__ANY_1 = signature("native function meta::pure::functions::variant::navigation::get(source:meta::pure::metamodel::variant::Variant[1], key:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::variant::Variant[0..1];");
    public static final NativeFunctionDefinition GRAPH_FETCH__T_MANY__COL_SPEC_1 = signature("native function meta::pure::graphFetch::execution::graphFetch<T>(source:T[*], col:meta::pure::metamodel::relation::ColSpec<T>[1]):T[*];");
    public static final NativeFunctionDefinition GRAPH_FETCH__T_MANY__COL_SPEC_ARRAY_1 = signature("native function meta::pure::graphFetch::execution::graphFetch<T>(source:T[*], cols:meta::pure::metamodel::relation::ColSpecArray<T>[1]):T[*];");
    public static final NativeFunctionDefinition GRAPH_FETCH__T_MANY__ROOT_GRAPH_FETCH_TREE_1 = signature("native function meta::pure::graphFetch::execution::graphFetch<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1]):T[*];");
    public static final NativeFunctionDefinition GRAPH_FETCH__T_MANY__ROOT_GRAPH_FETCH_TREE_1__INTEGER_1 = signature("native function meta::pure::graphFetch::execution::graphFetch<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1], batchSize:meta::pure::metamodel::type::Integer[1]):T[*];");
    // real graphFetch.pure:32/:38 — the CHECKED projection (per-object
    // constraint defects ride the envelope)
    public static final NativeFunctionDefinition GRAPH_FETCH_CHECKED__T_MANY__ROOT_GRAPH_FETCH_TREE_1 = signature("native function meta::pure::graphFetch::execution::graphFetchChecked<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1]):meta::pure::dataQuality::Checked[*];");
    public static final NativeFunctionDefinition GRAPH_FETCH_CHECKED__T_MANY__ROOT_GRAPH_FETCH_TREE_1__INTEGER_1 = signature("native function meta::pure::graphFetch::execution::graphFetchChecked<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1], batchSize:meta::pure::metamodel::type::Integer[1]):meta::pure::dataQuality::Checked[*];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__DATE_0_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__DATE_0_1__DATE_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__DATE_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__DATE_1__DATE_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__NUMBER_0_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__NUMBER_0_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__NUMBER_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__STRING_0_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__STRING_0_1__STRING_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__STRING_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__STRING_1__STRING_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__DATE_0_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__DATE_0_1__DATE_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__DATE_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__DATE_1__DATE_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__NUMBER_0_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__NUMBER_0_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__NUMBER_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__STRING_0_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__STRING_0_1__STRING_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__STRING_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__STRING_1__STRING_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    // greatest/least: REAL pure is GENERIC (legend-engine core_functions_standard/collection/{greatest,least}.pure).
    public static final NativeFunctionDefinition GREATEST__X_MANY = signature("native function meta::pure::functions::collection::greatest<X>(values:X[*]):X[0..1];");
    public static final NativeFunctionDefinition GREATEST__X_1_MANY = signature("native function meta::pure::functions::collection::greatest<X>(values:X[1..*]):X[1];");
    // CLASS-space groupBy bridge: the agg MAP is {C[1]->K[*]} — real pure's
    // collection::agg (collectionExtension.pure:21) declares mapFn
    // {T[1]->V[*]} (to-many paths like $f.employees.age aggregate via the
    // per-PK sub-aggregation route). The RELATION-space AggColSpec below
    // stays {T[1]->K[0..1]} (real relation signature).
    public static final NativeFunctionDefinition GROUP_BY__C_MANY__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_1 = signature("native function meta::pure::tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpec<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition GROUP_BY__C_MANY__FUNC_COL_SPEC_ARRAY_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::tds::groupBy<C,Z,K,V,R>(cl:C[*], keys:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},Z>[1], aggs:meta::pure::metamodel::relation::AggColSpecArray<{C[1]->K[*]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition GROUP_BY__K_MANY__FUNCTION_MANY__ANY_MANY__STRING_MANY = signature("native function meta::pure::functions::collection::groupBy<K,V,U>(set:K[*], fns:meta::pure::metamodel::function::Function<{K[1]->meta::pure::metamodel::type::Any[*]}>[*], aggs:meta::pure::metamodel::type::Any[*], ids:meta::pure::metamodel::type::String[*]):meta::pure::metamodel::relation::Relation<K>[1];");
    public static final NativeFunctionDefinition GROUP_BY__RELATION_1__COL_SPEC_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::groupBy<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition GROUP_BY__RELATION_1__COL_SPEC_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::groupBy<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition GROUP_BY__RELATION_1__COL_SPEC_ARRAY_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::groupBy<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition GROUP_BY__RELATION_1__COL_SPEC_ARRAY_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::groupBy<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<Z+R>[1];");
    public static final NativeFunctionDefinition HASH_CODE__ANY_MANY = signature("native function meta::pure::functions::hash::hashCode(val:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Integer[1];");
    // lite convenience; REAL pure hashing is hash(text, HashType) below.
    public static final NativeFunctionDefinition HASH__STRING_1 = signature("native function meta::legend::lite::hash(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition HASH__STRING_1__HASH_TYPE_1 = signature("native function meta::pure::functions::hash::hash(str:meta::pure::metamodel::type::String[1], algorithm:meta::pure::functions::hash::HashType[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition HAS_DAY__DATE_1 = signature("native function meta::pure::functions::date::hasDay(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_HOUR__DATE_1 = signature("native function meta::pure::functions::date::hasHour(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_MINUTE__DATE_1 = signature("native function meta::pure::functions::date::hasMinute(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_MONTH__DATE_1 = signature("native function meta::pure::functions::date::hasMonth(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_SECOND__DATE_1 = signature("native function meta::pure::functions::date::hasSecond(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_SUBSECOND_WITH_AT_LEAST_PRECISION__DATE_1__INTEGER_1 = signature("native function meta::pure::functions::date::hasSubsecondWithAtLeastPrecision(d:meta::pure::metamodel::type::Date[1], precision:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HAS_SUBSECOND__DATE_1 = signature("native function meta::pure::functions::date::hasSubsecond(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition HEAD__T_MANY = signature("native function meta::pure::functions::collection::head<T>(set:T[*]):T[0..1];");
    public static final NativeFunctionDefinition HOUR__DATE_1 = signature("native function meta::pure::functions::date::hour(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    // Real legend-pure (essential/lang/flow/if.pure): if<T|m>(Boolean[1], {->T[m]}, {->T[m]}):T[m].
    // The multiplicity VARIABLE m is shared by both branches and the result, so the result multiplicity
    // is the branches' (engine-lite dropped m and returned [*]/forced [1] — the bug flagged in §4.2).
    public static final NativeFunctionDefinition IF__BOOLEAN_1__FUNCTION_1__FUNCTION_1 = signature("native function meta::pure::functions::lang::if<T|m>(test:meta::pure::metamodel::type::Boolean[1], then:meta::pure::metamodel::function::Function<{->T[m]}>[1], else:meta::pure::metamodel::function::Function<{->T[m]}>[1]):T[m];");
    public static final NativeFunctionDefinition IF__PAIR_MANY__FUNCTION_1 = signature("native function meta::pure::functions::lang::if<T|m>(condList:meta::pure::functions::collection::Pair<meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Boolean[1]}>,meta::pure::metamodel::function::Function<{->T[m]}>>[*], last:meta::pure::metamodel::function::Function<{->T[m]}>[1]):T[m];");
    public static final NativeFunctionDefinition INDEX_OF__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::indexOf(str:meta::pure::metamodel::type::String[1], toFind:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition INDEX_OF__STRING_1__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::indexOf(str:meta::pure::metamodel::type::String[1], toFind:meta::pure::metamodel::type::String[1], fromIndex:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition INDEX_OF__T_MANY__T_1 = signature("native function meta::pure::functions::collection::indexOf<T>(set:T[*], value:T[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition INIT__T_MANY = signature("native function meta::pure::functions::collection::init<T>(set:T[*]):T[*];");
    public static final NativeFunctionDefinition INSTANCE_OF__ANY_1__TYPE_1 = signature("native function meta::pure::functions::meta::instanceOf(instance:meta::pure::metamodel::type::Any[1], type:meta::pure::metamodel::type::Type[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IN__ANY_1__ANY_MANY = signature("native function meta::pure::functions::collection::in(value:meta::pure::metamodel::type::Any[1], collection:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IN__ANY_0_1__ANY_MANY = signature("native function meta::pure::functions::collection::in(value:meta::pure::metamodel::type::Any[0..1], collection:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_AFTER_DAY__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::isAfterDay(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_BEFORE_DAY__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::isBeforeDay(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    // REAL pure (collectionExtension.pure:32): isDistinct<T>(set:T[*]) —
    // relational agg position lowers to COUNT(DISTINCT x) = COUNT(x)
    // (engine testGroupByIsDistinct golden).
    // the unique value of a collection or empty (engine collectionExtension
    // .pure:155-166: distinct size 1 -> max, else the default/[]) — native
    // because the corpus consumes it as an AGGREGATE reducer
    public static final NativeFunctionDefinition UNIQUE_VALUE_ONLY__T_MANY = signature("native function meta::pure::functions::collection::uniqueValueOnly<T>(values:T[*]):T[0..1];");
    public static final NativeFunctionDefinition UNIQUE_VALUE_ONLY__T_MANY__T_01 = signature("native function meta::pure::functions::collection::uniqueValueOnly<T>(values:T[*], defaultValue:T[0..1]):T[0..1];");
    public static final NativeFunctionDefinition IS_DISTINCT__T_MANY = signature("native function meta::pure::functions::collection::isDistinct<T>(set:T[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_DISTINCT__ANY_1__ANY_1 = signature("native function meta::pure::functions::collection::isDistinct(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_EMPTY__T_MANY = signature("native function meta::pure::functions::collection::isEmpty<T>(value:T[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_NOT_EMPTY__T_MANY = signature("native function meta::pure::functions::collection::isNotEmpty<T>(value:T[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_ON_DAY__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::isOnDay(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_ON_OR_AFTER_DAY__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::isOnOrAfterDay(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition IS_ON_OR_BEFORE_DAY__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::isOnOrBeforeDay(d1:meta::pure::metamodel::type::Date[1], d2:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition JARO_WINKLER_SIMILARITY__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::jaroWinklerSimilarity(s1:meta::pure::metamodel::type::String[1], s2:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MAKE_STRING__ANY_MANY = signature("native function meta::pure::functions::string::makeString(any:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition MAKE_STRING__ANY_MANY__STRING_1 = signature("native function meta::pure::functions::string::makeString(any:meta::pure::metamodel::type::Any[*], separator:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition MAKE_STRING__ANY_MANY__STRING_1__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::makeString(any:meta::pure::metamodel::type::Any[*], prefix:meta::pure::metamodel::type::String[1], separator:meta::pure::metamodel::type::String[1], suffix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    // 1-arg joinStrings = joinStrings(s, '', '', '') — EMPTY separator
    // (stringExtension.pure:253); the agg lowering adds the explicit ''
    // (DuckDB's bare STRING_AGG defaults to a COMMA — silently wrong).
    public static final NativeFunctionDefinition JOIN_STRINGS__STRING_MANY = signature("native function meta::pure::functions::string::joinStrings(strings:meta::pure::metamodel::type::String[*]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition JOIN_STRINGS__STRING_MANY__STRING_1 = signature("native function meta::pure::functions::string::joinStrings(strings:meta::pure::metamodel::type::String[*], separator:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition JOIN_STRINGS__STRING_MANY__STRING_1__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::joinStrings(strings:meta::pure::metamodel::type::String[*], prefix:meta::pure::metamodel::type::String[1], separator:meta::pure::metamodel::type::String[1], suffix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition JOIN__RELATION_1__RELATION_1__JOIN_KIND_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::join<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], joinKind:meta::pure::functions::relation::JoinKind[1], f:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    // join (ColSpec form, Relation<S> -> Relation<S+A>): chain-join used by
    // MappingNormalizer's relational synth. The ColSpec binds a sub-row alias
    // (~firm:) to a tableReference in its function1 body; the trailing lambda
    // is the join condition over (source-row, target-row). Defaults to LEFT.
    // This is the relational, same-store widening primitive; cross-class
    // widening uses `associate` on Class[*] above.
    public static final NativeFunctionDefinition JOIN__RELATION_1__FUNC_COL_SPEC_1__FUNCTION_1 = signature("native function meta::legend::lite::join<S,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], slot:meta::pure::metamodel::relation::FuncColSpec<{->meta::pure::metamodel::relation::Relation<T>[1]},Z>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];");
    public static final NativeFunctionDefinition JOIN__RELATION_1__RELATION_1__JOIN_KIND_1__FUNCTION_1__STRING_1 = signature("native function meta::pure::functions::relation::join<T,V>(rel1:meta::pure::metamodel::relation::Relation<T>[1], rel2:meta::pure::metamodel::relation::Relation<V>[1], joinKind:meta::pure::functions::relation::JoinKind[1], f:meta::pure::metamodel::function::Function<{T[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[1], prefix:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    public static final NativeFunctionDefinition LAG__RELATION_1__T_1 = signature("native function meta::pure::functions::relation::lag<T>(w:meta::pure::metamodel::relation::Relation<T>[1], r:T[1]):T[0..1];");
    public static final NativeFunctionDefinition LAG__RELATION_1__T_1__INTEGER_1 = signature("native function meta::pure::functions::relation::lag<T>(w:meta::pure::metamodel::relation::Relation<T>[1], r:T[1], offset:meta::pure::metamodel::type::Integer[1]):T[0..1];");
    public static final NativeFunctionDefinition LAST__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::last<T>(w:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::functions::relation::_Window<T>[1], row:T[1]):T[0..1];");
    public static final NativeFunctionDefinition LAST__T_MANY = signature("native function meta::pure::functions::collection::last<T>(set:T[*]):T[0..1];");
    public static final NativeFunctionDefinition LEAD__RELATION_1__T_1 = signature("native function meta::pure::functions::relation::lead<T>(w:meta::pure::metamodel::relation::Relation<T>[1], r:T[1]):T[0..1];");
    public static final NativeFunctionDefinition LEAD__RELATION_1__T_1__INTEGER_1 = signature("native function meta::pure::functions::relation::lead<T>(w:meta::pure::metamodel::relation::Relation<T>[1], r:T[1], offset:meta::pure::metamodel::type::Integer[1]):T[0..1];");
    public static final NativeFunctionDefinition LEAST__X_MANY = signature("native function meta::pure::functions::collection::least<X>(values:X[*]):X[0..1];");
    public static final NativeFunctionDefinition LEAST__X_1_MANY = signature("native function meta::pure::functions::collection::least<X>(values:X[1..*]):X[1];");
    public static final NativeFunctionDefinition LEFT__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::left(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    // legacyNavigate (pipeline step): structurally symmetric to clean-
    // sheet `navigate`. Widens the current row scope by adding a named
    // slot bound to an instance of the target class, materialized
    // through the target's mapping. The lambda takes two ROW references
    // (source-row from the current scope, target-main-table-row of the
    // slot class); using physical-column access in the lambda is what
    // makes the call "legacy" rather than clean. Emitted exclusively by
    // MappingNormalizer for class-typed Join PMs (single-hop final hop,
    // multi-hop final hop, OtherwiseEmbedded fallback). See
    // docs/MAPPING_LEGACY_TO_FUNCTION.md §2.1.
    // navigate — THE clean-sheet graph-traversal primitive (MAPPING_CLEAN_SHEET.md §3):
    // pre-map widens a Relation with a named class-typed sub-row (row-multiplying,
    // like join; the sub-row column itself is [1] per output row, §3.4); post-map
    // fills a DECLARED class property via an instance-space predicate; inline is the
    // constructor-slot form. The target extent rides the colspec as a zero-param thunk.
    public static final NativeFunctionDefinition NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__FUNCTION_1 = signature("native function meta::legend::lite::navigate<S,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->T[*]},Z>[1], pred:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];");
    public static final NativeFunctionDefinition NAVIGATE__C_MANY__FUNC_COL_SPEC_1__FUNCTION_1 = signature("native function meta::legend::lite::navigate<C,T,Z>(cl:C[*], target:meta::pure::metamodel::relation::FuncColSpec<{->T[*]},Z>[1], pred:meta::pure::metamodel::function::Function<{C[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):C[*];");
    public static final NativeFunctionDefinition NAVIGATE__T_MANY__FUNCTION_1 = signature("native function meta::legend::lite::navigate<T>(target:T[*], pred:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):T[*];");
    public static final NativeFunctionDefinition LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1 = signature("native function meta::legend::lite::legacyNavigate<S,C,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->C[*]},Z>[1], tgtRows:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];");

    // 5-arg overload: the STRICT member-PAIRED variant of a MERGED union
    // navigate condition rides as a fifth lambda (internal plumbing — the
    // engine's relational path merges diagonal union routes while its
    // graph executor pairs strictly; TypedNavigate.pairedPredicate).
    public static final NativeFunctionDefinition LEGACY_NAVIGATE__RELATION_1__FUNC_COL_SPEC_1__RELATION_1__FUNCTION_1__FUNCTION_1 = signature("native function meta::legend::lite::legacyNavigate<S,C,T,Z>(rel:meta::pure::metamodel::relation::Relation<S>[1], target:meta::pure::metamodel::relation::FuncColSpec<{->C[*]},Z>[1], tgtRows:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1], pairedCond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::relation::Relation<S+Z>[1];");
    // legacyAssocPredicate: row-extraction adapter for AssociationMapping
    // predicate function bodies. The outer function signature is
    // (A[1], B[1]) -> Boolean[1] (matching a clean AssociationMapping
    // predicate function); the adapter extracts the underlying main-
    // table rows of $a and $b and binds them to the lambda's two Row
    // parameters so the body can speak physical-column predicates.
    // Emitted exclusively by MappingNormalizer for Relational
    // AssociationMapping bodies. See docs/MAPPING_LEGACY_TO_FUNCTION.md §2.2.
    // typeAsDeclared: the mapping-side TYPE ASSERTION (a binding read
    // types as the DECLARED property; NO SQL is emitted — engine parity
    // for e.g. an Integer property over a DOUBLE column, calendar family)
    public static final NativeFunctionDefinition TYPE_AS_DECLARED__ANY_01__T_1 = signature("native function meta::legend::lite::typeAsDeclared<T>(value:meta::pure::metamodel::type::Any[0..1], type:T[1]):T[0..1];");
    // castAsDeclared: the mapping-side WIRE coercion (a String-declared
    // property over a numeric column) — execution lowers to the SQL
    // cast (DuckDB does not wire-convert; audit 19 F7), while the
    // engine-TEXT funnel passes the value through bare: the engine's
    // plan/toSQLString goldens never spell wire coercions
    // (conformance-cast provenance seam).
    public static final NativeFunctionDefinition CAST_AS_DECLARED__ANY_01__T_1 = signature("native function meta::legend::lite::castAsDeclared<T>(value:meta::pure::metamodel::type::Any[0..1], type:T[1]):T[0..1];");
    public static final NativeFunctionDefinition ID__ANY_1 = signature("native function meta::pure::functions::meta::id(instance:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[1];");
    // Real platform_store_relational/functions.pure:227/:249 — metamodel
    // navigation (ordinary pure over the store metamodel there; typed
    // natives here, evaluated K-side when a consumer demands the values).
    public static final NativeFunctionDefinition CONVERT_ELEMENT = signature("native function meta::relational::functions::toPostgresModel::convertElement(r:meta::relational::metamodel::RelationalOperationElement[1], state:meta::relational::functions::toPostgresModel::ModelConversionState[1]):meta::external::query::sql::metamodel::Node[1];");
    public static final NativeFunctionDefinition NEW_STATE = signature("native function meta::relational::functions::toPostgresModel::newState():meta::relational::functions::toPostgresModel::ModelConversionState[1];");
    public static final NativeFunctionDefinition CONVERT_SELECT_SQL_QUERY = signature("native function meta::relational::functions::toPostgresModel::convertSelectSqlQuery(select:meta::relational::metamodel::relation::SelectSQLQuery[1], state:meta::relational::functions::toPostgresModel::ModelConversionState[1]):meta::external::query::sql::metamodel::Query[1];");
    public static final NativeFunctionDefinition ROOT_CLASS_MAPPING_BY_CLASS = signature("native function meta::pure::mapping::rootClassMappingByClass(_this:meta::pure::mapping::Mapping[1], class:meta::pure::metamodel::type::Class<meta::pure::metamodel::type::Any>[1]):meta::pure::mapping::SetImplementation[0..1];");
    // real functions_Mapping.pure:28 — platform-owned (the pure body's
    // groupBy/AggregationAware machinery is out of scope; the walk serves
    // the include-closure set collection the corpus asserts on)
    public static final NativeFunctionDefinition CLASS_MAPPING_BY_CLASS = signature("native function meta::pure::mapping::_classMappingByClass(_this:meta::pure::mapping::Mapping[1], class:meta::pure::metamodel::type::Class<meta::pure::metamodel::type::Any>[1]):meta::pure::mapping::SetImplementation[*];");
    public static final NativeFunctionDefinition PROPERTY_MAPPINGS_BY_NAME = signature("native function meta::pure::mapping::propertyMappingsByPropertyName(i:meta::pure::mapping::InstanceSetImplementation[1], propertyName:meta::pure::metamodel::type::String[1]):meta::pure::mapping::PropertyMapping[*];");
    // Extends-chain navigation over the mapping metamodel (real
    // functions_Mapping.pure:74, functions_PropertyMappingsImplementation
    // .pure:19, engine mappingExtension.pure:163, platform_store_
    // relational/functions.pure:277/:191 — ordinary pure there; typed
    // natives here, evaluated K-side over the compiled model)
    public static final NativeFunctionDefinition CLASS_MAPPING_BY_ID = signature("native function meta::pure::mapping::classMappingById(_this:meta::pure::mapping::Mapping[1], id:meta::pure::metamodel::type::String[1]):meta::pure::mapping::SetImplementation[0..1];");
    public static final NativeFunctionDefinition SUPER_MAPPING = signature("native function meta::pure::mapping::superMapping(_this:meta::pure::mapping::PropertyMappingsImplementation[1]):meta::pure::mapping::PropertyMappingsImplementation[0..1];");
    public static final NativeFunctionDefinition ALL_SUPER_SET_IMPLEMENTATIONS = signature("native function meta::pure::mapping::allSuperSetImplementations(set:meta::pure::mapping::PropertyMappingsImplementation[1], m:meta::pure::mapping::Mapping[1]):meta::pure::mapping::PropertyMappingsImplementation[*];");
    public static final NativeFunctionDefinition MAIN_TABLE = signature("native function meta::relational::metamodel::mainTable(_this:meta::relational::metamodel::RelationalMappingSpecification[1]):meta::relational::metamodel::relation::Table[1];");
    public static final NativeFunctionDefinition RESOLVE_PRIMARY_KEY = signature("native function meta::relational::mapping::resolvePrimaryKey(_this:meta::relational::mapping::RootRelationalInstanceSetImplementation[1]):meta::relational::metamodel::RelationalOperationElement[*];");
    public static final NativeFunctionDefinition VIEW__SCHEMA_1__STRING_1 = signature("native function meta::relational::metamodel::view(_this:meta::relational::metamodel::Schema[1], name:meta::pure::metamodel::type::String[1]):meta::relational::metamodel::relation::View[0..1];");
    public static final NativeFunctionDefinition INFER_RELATIONAL_TYPE = signature("native function meta::relational::functions::typeInference::inferRelationalType(rop:meta::relational::metamodel::RelationalOperationElement[1]):meta::relational::metamodel::datatype::DataType[0..1];");
    public static final NativeFunctionDefinition DATA_TYPE_TO_SQL_TEXT = signature("native function meta::relational::metamodel::datatype::dataTypeToSqlText(type:meta::relational::metamodel::datatype::DataType[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SCHEMA__DB_1__STRING_1 = signature("native function meta::relational::metamodel::schema(_this:meta::relational::metamodel::Database[1], name:meta::pure::metamodel::type::String[1]):meta::relational::metamodel::Schema[0..1];");
    public static final NativeFunctionDefinition EXTRACT_CTES = signature("native function meta::relational::postProcessor::cteExtraction::extractSubqueriesAsCTEs(select:meta::relational::metamodel::relation::SelectSQLQuery[1]):meta::relational::metamodel::relation::SelectSQLQuery[1];");
    public static final NativeFunctionDefinition EXTRACT_CTES_PP = signature("native function meta::relational::postProcessor::cteExtraction::extractSubQueriesAsCTEsPostProcessor(s:meta::relational::postProcessor::cteExtraction::ExtractSubQueriesAsCTEsPostProcessor[1]):meta::relational::runtime::PostProcessorWithParameter[1];");
    // Real runtime/connection/postprocessor.pure:50 — wraps the mapper
    // config as a PostProcessorWithParameter for the connection's
    // queryPostProcessorsWithParameter channel
    public static final NativeFunctionDefinition RELATIONAL_MAPPER_PP = signature("native function meta::pure::alloy::connections::relationalMapperPostProcessor(mapper:meta::pure::alloy::connections::RelationalMapperPostProcessor[1]):meta::relational::runtime::PostProcessorWithParameter[1];");
    public static final NativeFunctionDefinition REPLACE_TABLES = signature("native function meta::relational::postProcessor::replaceTables(selectSQLQuery:meta::relational::metamodel::relation::SelectSQLQuery[1], oldToNewPairs:meta::pure::functions::collection::Pair<meta::relational::metamodel::relation::Table,meta::relational::metamodel::relation::Table>[*]):meta::pure::mapping::Result<meta::relational::metamodel::relation::SelectSQLQuery|1>[1];");
    public static final NativeFunctionDefinition NON_EXECUTABLE_PP = signature("native function meta::relational::postProcessor::nonExecutable(selectSQLQuery:meta::relational::metamodel::relation::SelectSQLQuery[1], extensions:meta::pure::extension::Extension[*]):meta::pure::mapping::Result<meta::relational::metamodel::relation::SelectSQLQuery|1>[1];");
    public static final NativeFunctionDefinition TABLE__SCHEMA_1__STRING_1 = signature("native function meta::relational::metamodel::table(_this:meta::relational::metamodel::Schema[1], name:meta::pure::metamodel::type::String[1]):meta::relational::metamodel::relation::Table[0..1];");
    // Real essential/meta/reflect/evaluateAndDeactivate.pure:17 — a
    // reflection-level IDENTITY on values (deactivates expression wrappers
    // in real pure; values here are already values, so it is the identity;
    // task #78 step-1, the TDS-concatenate family spells it).
    public static final NativeFunctionDefinition EVALUATE_AND_DEACTIVATE__T_M = signature("native function meta::pure::functions::meta::evaluateAndDeactivate<T|m>(var:T[m]):T[m];");
    // K-phase natives: the engine's JDBC boundary (executed host-side at
    // the EXECUTE phase, never lowered to SQL). executeInDb is the 4-arg
    // leaf every corpus wrapper bottoms out at; testRuntime and
    // connectionByElement type the connection-resolution chains
    // (execution-context elements are Any[1] — the from() convention).
    public static final NativeFunctionDefinition EXECUTE_IN_DB__STRING_1__CONN_1__INTEGER_1__INTEGER_1 = signature("native function meta::relational::metamodel::execute::executeInDb(sql:meta::pure::metamodel::type::String[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], timeOutInSeconds:meta::pure::metamodel::type::Integer[1], fetchSize:meta::pure::metamodel::type::Integer[1]):meta::relational::metamodel::execute::ResultSet[1];");
    // the 2-arg overload — REAL pure's wrapper (relationalExtension.pure:31,
    // executeInDb($sql, $conn, 0, 1000)) as a platform native (Clause 2b:
    // engine pure is the spec, the platform's definition is Java): same FQN,
    // user definitions suppress; the K dispatch and the Phase 1c retype key
    // on the FQN and the sql literal, indifferent to arity
    public static final NativeFunctionDefinition EXECUTE_IN_DB__STRING_1__CONN_1 = signature("native function meta::relational::metamodel::execute::executeInDb(sql:meta::pure::metamodel::type::String[1], databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1]):meta::relational::metamodel::execute::ResultSet[1];");
    // JDBC DatabaseMetaData reads (REAL platform_store_relational/
    // functions.pure:34-41) — evaluated HOST-SIDE against the H2 second
    // target (engine-parity metadata casing), never lowered
    public static final NativeFunctionDefinition FETCH_DB_TABLES_META_DATA = signature("native function meta::relational::metamodel::execute::fetchDbTablesMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:meta::pure::metamodel::type::String[0..1], tablePattern:meta::pure::metamodel::type::String[0..1]):meta::relational::metamodel::execute::ResultSet[1];");
    public static final NativeFunctionDefinition FETCH_DB_COLUMNS_META_DATA = signature("native function meta::relational::metamodel::execute::fetchDbColumnsMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:meta::pure::metamodel::type::String[0..1], tablePattern:meta::pure::metamodel::type::String[0..1], columnPattern:meta::pure::metamodel::type::String[0..1]):meta::relational::metamodel::execute::ResultSet[1];");
    public static final NativeFunctionDefinition FETCH_DB_SCHEMAS_META_DATA = signature("native function meta::relational::metamodel::execute::fetchDbSchemasMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:meta::pure::metamodel::type::String[0..1]):meta::relational::metamodel::execute::ResultSet[1];");
    public static final NativeFunctionDefinition FETCH_DB_PRIMARY_KEYS_META_DATA = signature("native function meta::relational::metamodel::execute::fetchDbPrimaryKeysMetaData(databaseConnection:meta::external::store::relational::runtime::DatabaseConnection[1], schemaPattern:meta::pure::metamodel::type::String[0..1], tableName:meta::pure::metamodel::type::String[1]):meta::relational::metamodel::execute::ResultSet[1];");
    public static final NativeFunctionDefinition CONNECTION_BY_ELEMENT__ANY_1__ANY_1 = signature("native function meta::core::runtime::connectionByElement(runtime:meta::core::runtime::Runtime[1], store:meta::pure::metamodel::type::Any[1]):meta::core::runtime::Connection[1];");
    // B2a (docs/PHASE_B2_RESULT_VALUE.md): the execute()/Result typing
    // surface. Result is a TYPING surface + orchestration handle — reads
    // over it rewrite into SQL-bound queries (no interpreter, tenet #1);
    // the K arm lands in B2b. mapping/runtime/extensions type as Any.
    // NOTE: real pure spells Result<T|m> with values:T[m]; class-level
    // multiplicity params are the task-#50 parse gap (the corpus's OWN
    // Result<T|m> spelling darkens the postprocessor family the same
    // way). T[*] is safe meanwhile — consumers normalize multiplicity.
    public static final ClassDefinition RESULT = nativeClass("native Class meta::pure::mapping::Result<T> extends meta::pure::metamodel::type::Any { values: T[*]; activities: meta::pure::mapping::Activity[*]; }");
    // debug-context surface (REAL legend-pure platform/pure/tools.pure +
    // essential/tools/debug/noDebug.pure): the corpus's debug-arity execute
    // calls type against these. RelationalDebugContext/IsolationStrategy
    // stay CORPUS classes — pureToSQLQuery.pure parses clean since the
    // #50 walls landed and the corpus runner registers it as a LIBRARY
    // source (pulled by reference); never promoted to the prelude.
    public static final ClassDefinition DEBUG_CONTEXT = nativeClass("native Class meta::pure::tools::DebugContext extends meta::pure::metamodel::type::Any { debug: meta::pure::metamodel::type::Boolean[1]; space: meta::pure::metamodel::type::String[1]; }");
    public static final NativeFunctionDefinition NO_DEBUG = signature("native function meta::pure::tools::noDebug():meta::pure::tools::DebugContext[1];");
    public static final ClassDefinition ACTIVITY = nativeClass("native Class meta::pure::mapping::Activity extends meta::pure::metamodel::type::Any {}");
    public static final NativeFunctionDefinition EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY = signature("native function meta::pure::mapping::execute<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::mapping::Result<T>[1];");
    public static final NativeFunctionDefinition EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY__ANY_1 = signature("native function meta::pure::mapping::execute<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*], debug:meta::pure::metamodel::type::Any[1]):meta::pure::mapping::Result<T>[1];");
    // The ROUTER entry spelling (REAL pure router_entry.pure:20/:47 —
    // execute<T|y>(f:FunctionDefinition<{->T[y]}>[1], m:Mapping[1],
    // runtime:Runtime[1], extensions:Extension[*])[, debug]):Result —
    // same execution semantics as mapping::execute; the harness
    // recognizes both FQNs (PlatformTypes.isExecuteFqn).
    public static final NativeFunctionDefinition ROUTER_EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY = signature("native function meta::pure::router::execute<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::mapping::Result<T>[1];");
    public static final NativeFunctionDefinition ROUTER_EXECUTE__FN_1__ANY_1__ANY_1__ANY_MANY__ANY_1 = signature("native function meta::pure::router::execute<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*], debug:meta::pure::metamodel::type::Any[1]):meta::pure::mapping::Result<T>[1];");

    // preval: the engine's PLAN-TIME pre-evaluation pass (REAL pure
    // preeval.pure:53/:58 — preval<T>(f:FunctionDefinition<T>[1],
    // extensions:Extension[*])[, debug:DebugContext[1]]:FunctionDefinition
    // <T>[1]). IDENTITY for row semantics; f spelled with this platform's
    // execute convention (Function<{->T[*]}>) so the wrapped query
    // composes through execute with the same T. Never evaluated — the
    // harness reads through it to the query lambda.
    public static final NativeFunctionDefinition PREVAL__FN_1__ANY_MANY = signature("native function meta::pure::router::preeval::preval<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::function::Function<{->T[*]}>[1];");
    public static final NativeFunctionDefinition PREVAL__FN_1__ANY_MANY__DEBUG_1 = signature("native function meta::pure::router::preeval::preval<T>(f:meta::pure::metamodel::function::Function<{->T[*]}>[1], extensions:meta::pure::metamodel::type::Any[*], debug:meta::pure::tools::DebugContext[1]):meta::pure::metamodel::function::Function<{->T[*]}>[1];");

    // concatenateTemporalTdsQueries (REAL milestoning.pure:753 —
    // (lfs:LambdaFunction<{->TabularDataSet[1]}>[*]):LambdaFunction<...>
    // [1]): its real body folds the queries into concatenate
    // SimpleFunctionExpressions — reflection metamodel this platform
    // lacks, so the corpus copy is signature-broken and drops at
    // overload collection. This native carries the TYPE; the harness
    // splices the SAME semantics by EMISSION (TypedConcatenate fold in
    // StatementExecutor.buildFrame).
    public static final NativeFunctionDefinition CONCATENATE_TEMPORAL_TDS_QUERIES = signature("native function meta::relational::milestoning::concatenateTemporalTdsQueries<T>(lfs:meta::pure::metamodel::function::Function<{->T[*]}>[*]):meta::pure::metamodel::function::Function<{->T[*]}>[1];");

    // withFeatureFlags (REAL executionPlanFeature.pure:27): IDENTITY —
    // the flags ride the plan context; the harness reads through it.
    public static final NativeFunctionDefinition WITH_FEATURE_FLAGS__T_MANY__ANY_MANY = signature("native function meta::pure::executionPlan::featureFlag::withFeatureFlags<T>(object:T[*], e:meta::pure::metamodel::type::Any[*]):T[*];");

    // relationalExtensions(): the corpus's own definition is signature-
    // broken in this platform (the Extension metamodel class), so it never
    // enters the module — this native exists for TYPING the context
    // argument of toSQLString/execute calls; it is never evaluated.
    public static final NativeFunctionDefinition RELATIONAL_EXTENSIONS__ANY_MANY = signature("native function meta::relational::extension::relationalExtensions():meta::pure::metamodel::type::Any[*];");

    // setUpDataSQLsV2: the engine's CSV-seed SQL generator (module-
    // external to the corpus) — K-dispatched via CsvSeed; dbConfig types
    // as Any and is never evaluated (the ambient-connection doctrine).
    public static final NativeFunctionDefinition SET_UP_DATA_SQLS_V2__STRING_1__ANY_1__ANY_1 = signature("native function meta::alloy::service::execution::setUpDataSQLsV2(csv:meta::pure::metamodel::type::String[1], db:meta::pure::metamodel::type::Any[1], dbConfig:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[*];");

    // plain setUpDataSQLs (deprecated engine spelling, the
    // testDataGeneration family's assert/reload route) — PLATFORM-OWNED:
    // the corpus's own overload ladder bottoms out in M3-reflective
    // loadCsvDataToDbTable bodies this platform doesn't model, and its
    // DatabaseType wrapper cannot type against createDbConfig's Any.
    // Same CsvSeed K-arm as V2.
    // the RECORDS overload (helperFunctions.pure:193 — parsed CSV lines
    // as List<String> cells); the K-arm renders the same statement list
    public static final NativeFunctionDefinition SET_UP_DATA_SQLS__LIST_MANY__ANY_MANY__ANY_1 = signature("native function meta::alloy::service::execution::setUpDataSQLs(records:meta::pure::functions::collection::List<meta::pure::metamodel::type::String>[*], db:meta::pure::metamodel::type::Any[*], dbConfig:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition SET_UP_DATA_SQLS__STRING_1__ANY_MANY = signature("native function meta::alloy::service::execution::setUpDataSQLs(csv:meta::pure::metamodel::type::String[1], db:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition SET_UP_DATA_SQLS__STRING_1__ANY_MANY__ANY_1 = signature("native function meta::alloy::service::execution::setUpDataSQLs(csv:meta::pure::metamodel::type::String[1], db:meta::pure::metamodel::type::Any[*], dbConfig:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[*];");

    // executionPlan + planToString (#47): PLATFORM-OWNED plan surface —
    // the corpus's own definitions walk the plan METAMODEL (M3
    // reflection); here the plan handle is opaque and planToString is a
    // K-native rendering the engine's literal plan text (the toSQLString
    // doctrine: plan text compares LITERALLY).
    public static final NativeFunctionDefinition EXECUTION_PLAN__4 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__5 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], exeCtx:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    // real executionPlan_generation.pure:30 — extensions[*] THEN
    // debugContext[1] last (the noDebug() trailing form)
    public static final NativeFunctionDefinition EXECUTION_PLAN__5_DEBUG = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*], debugContext:meta::pure::metamodel::type::Any[1]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition PLAN_TO_STRING__ANY_1__ANY_MANY = signature("native function meta::pure::executionPlan::toString::planToString(plan:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    // real executionPlan_print.pure:27 — planToString minus '\n' and ' '
    public static final NativeFunctionDefinition PLAN_TO_STRING_WITHOUT_FORMATTING__ANY_1__ANY_MANY = signature("native function meta::pure::executionPlan::toString::planToStringWithoutFormatting(plan:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    // real executionPlan_execution.pure:67 — the node-tree flatten
    public static final NativeFunctionDefinition ALL_NODES__EXECUTION_NODE_1__ANY_MANY = signature("native function meta::pure::executionPlan::allNodes(node:meta::pure::executionPlan::ExecutionNode[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionNode[*];");
    // pure-only plan shapes (no store): 2/3-arg spellings type; their
    // plan text is a PureExp node — a named wall at the K-arm until built
    // parameterized query lambdas (Allocation/Sequence plans): they
    // TYPE here; the plan-text K-arm walls the multi-node envelope
    public static final NativeFunctionDefinition EXECUTION_PLAN__4_P1 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__5_P1 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], exeCtx:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__4_P2 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1],meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__5_P2 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1],meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], runtime:meta::pure::metamodel::type::Any[1], exeCtx:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__2 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__2_P1 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__2_P2 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Any[1],meta::pure::metamodel::type::Any[1]->meta::pure::metamodel::type::Any[*]}>[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");
    public static final NativeFunctionDefinition EXECUTION_PLAN__3 = signature("native function meta::pure::executionPlan::executionPlan(func:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], context:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::executionPlan::ExecutionPlan[1];");

    // createDbConfig: the corpus's own definitions return the DbConfig
    // metamodel class (unknown here, signature-broken) — typing-only.
    public static final NativeFunctionDefinition CREATE_DB_CONFIG__ANY_1 = signature("native function meta::relational::functions::sqlQueryToString::createDbConfig(dbType:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Any[1];");
    public static final NativeFunctionDefinition CREATE_DB_CONFIG__ANY_1__STRING_01 = signature("native function meta::relational::functions::sqlQueryToString::createDbConfig(dbType:meta::pure::metamodel::type::Any[1], dbTimeZone:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Any[1];");

    // toSQLString: ordinary pure in the real engine (plan-generation
    // internals) — a K-native here: the query lambda lowers through the
    // platform's own pipeline against the mapping argument and renders
    // with the engine-style dialect. mapping/databaseType/extensions type
    // as Any (the from()-convention for execution-context elements).
    public static final NativeFunctionDefinition TO_SQL_STRING__FN_1__ANY_1__ANY_1__ANY_MANY = signature("native function meta::relational::functions::sqlstring::toSQLString(f:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], databaseType:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    // toSQLStringPretty = toSQLString with the pretty Format (engine
    // toSQLString.pure:35/:40) — same K-dispatch; every golden compare
    // strips formatting (sqlRemoveFormatting), so the flat rendering is
    // compare-equal. The 3rd argument is DatabaseType OR Runtime (:40).
    public static final NativeFunctionDefinition TO_SQL_STRING_PRETTY__FN_1__ANY_1__ANY_1__ANY_MANY = signature("native function meta::relational::functions::sqlstring::toSQLStringPretty(f:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], mapping:meta::pure::metamodel::type::Any[1], databaseTypeOrRuntime:meta::pure::metamodel::type::Any[1], extensions:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::String[1];");
    // dropAndCreateTableInDb: ordinary pure in the real engine (toDDL.pure
    // walks the Database metamodel to spell DDL) — a K-native here, DDL
    // rendered from the compiled store model (com.legend.exec.Ddl). The
    // database argument types as the store METACLASS, exactly like real
    // pure (audit 17: Any[1] let string literals type-check).
    public static final NativeFunctionDefinition DDL_DROP_SCHEMA_STATEMENT__STRING_1 = signature("native function meta::relational::functions::toDDL::dropSchemaStatement(schema:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DDL_CREATE_SCHEMA_STATEMENT__STRING_1 = signature("native function meta::relational::functions::toDDL::createSchemaStatement(schema:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DDL_CREATE_TABLE_STATEMENT__DB_1__STRING_1__STRING_1 = signature("native function meta::relational::functions::toDDL::createTableStatement(database:meta::relational::metamodel::Database[1], schema:meta::pure::metamodel::type::String[1], tableName:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    // engine helperFunctions.pure:198-232 — the RENDER phase's CSV text
    // (F4.2): platform-owned lowerings, the DB constructs the text
    // engine toString.pure:19-24 — the '#TDS' relation text (F4.2c);
    // the RELATION overload also NARROWS toString(Any)'s leak for
    // relation args (overload resolution picks the specific one)
    public static final NativeFunctionDefinition TO_STRING__RELATION = signature("native function meta::pure::functions::relation::toString<T>(rel:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_STRING__RELATION_BOOL = signature("native function meta::pure::functions::relation::toString<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], typesAndMuls:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_CSV__TDS = signature("native function meta::relational::tests::csv::toCSV(t:meta::pure::tds::TabularDataSet[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_CSV__TDS_BOOL = signature("native function meta::relational::tests::csv::toCSV(t:meta::pure::tds::TabularDataSet[1], renderTdsNull:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_CSV__TDS_FMT = signature("native function meta::relational::tests::csv::toCSV(t:meta::pure::tds::TabularDataSet[1], dateTimeFormat:meta::pure::metamodel::type::String[1], dateFormat:meta::pure::metamodel::type::String[1], renderTdsNull:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::String[1];");

    // engine toDDL.pure:34-47 string-generator overloads — platform-owned
    // (the native IS the definition; the corpus bodies walk DbConfig)
    public static final NativeFunctionDefinition DDL_CREATE_TABLE_STATEMENT__DB_1__STRING_1 = signature("native function meta::relational::functions::toDDL::createTableStatement(database:meta::relational::metamodel::Database[1], tableName:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DDL_DROP_TABLE_STATEMENT__DB_1__STRING_1 = signature("native function meta::relational::functions::toDDL::dropTableStatement(database:meta::relational::metamodel::Database[1], tableName:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition DDL_DROP_TABLE_STATEMENT__DB_1__STRING_1__STRING_1 = signature("native function meta::relational::functions::toDDL::dropTableStatement(database:meta::relational::metamodel::Database[1], schema:meta::pure::metamodel::type::String[1], tableName:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");

    public static final NativeFunctionDefinition DROP_AND_CREATE_TABLE_IN_DB__ANY_1__STRING_1__CONN_1 = signature("native function meta::relational::functions::toDDL::dropAndCreateTableInDb(database:meta::relational::metamodel::Database[1], tableName:meta::pure::metamodel::type::String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition DROP_AND_CREATE_TABLE_IN_DB__ANY_1__STRING_1__STRING_1__CONN_1 = signature("native function meta::relational::functions::toDDL::dropAndCreateTableInDb(database:meta::relational::metamodel::Database[1], schema:meta::pure::metamodel::type::String[1], tableName:meta::pure::metamodel::type::String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):meta::pure::metamodel::type::Boolean[1];");
    // relationalExtension.pure's wrappers (2-arg AND the 3-arg debug
    // variant) are the corpus's OWN pure code — shared module sources in
    // the harness — and inline to the 4-arg native leaf. No natives here
    // (audit 17: a same-signature native would TIE with the corpus's own
    // function the day it compiles).
    public static final NativeFunctionDefinition DROP_AND_CREATE_SCHEMA_IN_DB__STRING_1__CONN_1 = signature("native function meta::relational::functions::toDDL::dropAndCreateSchemaInDb(schema:meta::pure::metamodel::type::String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1]):meta::pure::metamodel::type::Boolean[1];");
    // real essential/io print surface — K-dispatched as NO-OPS: debug
    // output whose ARGUMENTS are never evaluated (they may introspect
    // ResultSets, which never materialize host-side)
    public static final NativeFunctionDefinition PRINT__ANY_M__INTEGER_1 = signature("native function meta::pure::functions::io::print(param:meta::pure::metamodel::type::Any[*], max:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Nil[0];");
    public static final NativeFunctionDefinition PRINT__ANY_M = signature("native function meta::pure::functions::io::print(param:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Nil[0];");
    public static final NativeFunctionDefinition PRINTLN__ANY_M__INTEGER_1 = signature("native function meta::pure::functions::io::println(param:meta::pure::metamodel::type::Any[*], max:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Nil[0];");
    public static final NativeFunctionDefinition PRINTLN__ANY_M = signature("native function meta::pure::functions::io::println(param:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Nil[0];");
    public static final NativeFunctionDefinition DROP_AND_CREATE_SCHEMA_IN_DB__STRING_1__CONN_1__BOOLEAN_1 = signature("native function meta::relational::functions::toDDL::dropAndCreateSchemaInDb(schema:meta::pure::metamodel::type::String[1], c:meta::external::store::relational::runtime::DatabaseConnection[1], debug:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    /** THE legacyAssocPredicate FQN — producers spell the bare name (the
     * checker resolves), the resolver matches THIS constant (audit 23
     * contract consolidation). */
    public static final String LEGACY_ASSOC_PREDICATE_FQN =
            "meta::legend::lite::legacyAssocPredicate";

    public static final NativeFunctionDefinition LEGACY_ASSOC_PREDICATE__A_1__B_1__RELATION_1__RELATION_1__FUNCTION_1 = signature("native function meta::legend::lite::legacyAssocPredicate<A,B,S,T>(a:A[1], b:B[1], src:meta::pure::metamodel::relation::Relation<S>[1], tgt:meta::pure::metamodel::relation::Relation<T>[1], cond:meta::pure::metamodel::function::Function<{S[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    /** PROPERTY-SPACE overload (XStore route A): a Pure-set end has no
     * relation at normalize time, so the emission pins the two SETS by id
     * and keeps the condition in property space over the END CLASSES —
     * the resolver substitutes it through the sets' composed bindings. */
    public static final NativeFunctionDefinition LEGACY_ASSOC_PREDICATE__A_1__B_1__STRING_1__STRING_1__FUNCTION_1 = signature("native function meta::legend::lite::legacyAssocPredicate<A,B>(a:A[1], b:B[1], srcSet:meta::pure::metamodel::type::String[1], tgtSet:meta::pure::metamodel::type::String[1], cond:meta::pure::metamodel::function::Function<{A[1],B[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    /** A set-LOCAL (+prop) read inside a property-space XStore condition:
     * locals are not class properties, so {@code $row.local} cannot type —
     * the emission spells {@code legacyLocalProperty($row, 'local')} and
     * the resolver substitutes the set's binding (conform-by-emission). */
    public static final String LEGACY_LOCAL_PROPERTY_FQN =
            "meta::legend::lite::legacyLocalProperty";
    public static final NativeFunctionDefinition LEGACY_LOCAL_PROPERTY__ANY_1__STRING_1 = signature("native function meta::legend::lite::legacyLocalProperty(row:meta::pure::metamodel::type::Any[1], prop:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Any[1];");
    public static final NativeFunctionDefinition LENGTH__STRING_1 = signature("native function meta::pure::functions::string::length(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__DATE_0_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__DATE_0_1__DATE_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__DATE_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__DATE_1__DATE_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__NUMBER_0_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__NUMBER_0_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__NUMBER_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__STRING_0_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__STRING_0_1__STRING_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__STRING_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__STRING_1__STRING_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__DATE_0_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__BOOLEAN_0_1__BOOLEAN_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Boolean[0..1], right:meta::pure::metamodel::type::Boolean[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL__BOOLEAN_0_1__BOOLEAN_0_1 = signature("native function meta::pure::functions::boolean::lessThanEqual(left:meta::pure::metamodel::type::Boolean[0..1], right:meta::pure::metamodel::type::Boolean[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN__BOOLEAN_0_1__BOOLEAN_0_1 = signature("native function meta::pure::functions::boolean::greaterThan(left:meta::pure::metamodel::type::Boolean[0..1], right:meta::pure::metamodel::type::Boolean[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL__BOOLEAN_0_1__BOOLEAN_0_1 = signature("native function meta::pure::functions::boolean::greaterThanEqual(left:meta::pure::metamodel::type::Boolean[0..1], right:meta::pure::metamodel::type::Boolean[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__DATE_0_1__DATE_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Date[0..1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__DATE_1__DATE_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__DATE_1__DATE_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__NUMBER_0_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__NUMBER_0_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Number[0..1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__NUMBER_1__NUMBER_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__STRING_0_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__STRING_0_1__STRING_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::String[0..1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__STRING_1__STRING_0_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[0..1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN__STRING_1__STRING_1 = signature("native function meta::pure::functions::boolean::lessThan(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    // Real legend-pure: letFunction(String[1], T[m]):T[m] (mangled letFunction_String_1__T_m__T_m_) —
    // the multiplicity VARIABLE m is what makes a binding preserve its value's multiplicity through the
    // standard resolve→unify→resolveOutput pipeline (multi-valued let, `let xs = [1,2,3]`). engine-lite
    // flattened m→[1], which broke that and forced a bespoke checker; the mult var restores correctness.
    public static final NativeFunctionDefinition LET_FUNCTION__STRING_1__T_m = signature("native function meta::pure::functions::lang::letFunction<T|m>(name:meta::pure::metamodel::type::String[1], value:T[m]):T[m];");
    public static final NativeFunctionDefinition LEVENSHTEIN_DISTANCE__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::levenshteinDistance(s1:meta::pure::metamodel::type::String[1], s2:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition LIMIT__RELATION_1__INTEGER_1 = signature("native function meta::pure::functions::relation::limit<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], size:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition LIMIT__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::collection::limit<T>(set:T[*], size:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition LIST__T_MANY = signature("native function meta::pure::functions::collection::list<T>(values:T[*]):meta::pure::functions::collection::List<T>[1];");
    public static final NativeFunctionDefinition LOG10__NUMBER_1 = signature("native function meta::pure::functions::math::log10(value:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition LOG__NUMBER_1 = signature("native function meta::pure::functions::math::log(value:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition LPAD__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::lpad(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition LPAD__STRING_1__INTEGER_1__STRING_1 = signature("native function meta::pure::functions::string::lpad(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1], pad:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition LTRIM__STRING_1 = signature("native function meta::pure::functions::string::ltrim(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    // Real collection/iteration/map.pure:19-26 — the MULTIPLICITY-
    // PRESERVING overload (to-one body over T[m] yields V[m]; the corpus's
    // $cs.connection->cast(...)->map(x|^$x(...)) needs [1]·[1]→[1] so the
    // copy's connection:[1] property conformance holds).
    public static final NativeFunctionDefinition MAP__T_M__FUNCTION_1 = signature("native function meta::pure::functions::collection::map<T,V|m>(value:T[m], func:meta::pure::metamodel::function::Function<{T[1]->V[1]}>[1]):V[m];");
    public static final NativeFunctionDefinition MAP__T_0_1__FUNCTION_1 = signature("native function meta::pure::functions::collection::map<T,V>(value:T[0..1], func:meta::pure::metamodel::function::Function<{T[1]->V[0..1]}>[1]):V[0..1];");
    public static final NativeFunctionDefinition MAP__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::map<T,V>(value:T[*], func:meta::pure::metamodel::function::Function<{T[1]->V[*]}>[1]):V[*];");
    public static final NativeFunctionDefinition MATCHES__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::matches(str:meta::pure::metamodel::type::String[1], regex:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition REGEXP_LIKE__2 = signature("native function meta::pure::functions::string::regexpLike(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition REGEXP_LIKE__3 = signature("native function meta::pure::functions::string::regexpLike(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition REGEXP_COUNT__2 = signature("native function meta::pure::functions::string::regexpCount(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_COUNT__3 = signature("native function meta::pure::functions::string::regexpCount(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_EXTRACT__3 = signature("native function meta::pure::functions::string::regexpExtract(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], extractAll:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition REGEXP_EXTRACT__4 = signature("native function meta::pure::functions::string::regexpExtract(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], extractAll:meta::pure::metamodel::type::Boolean[1], groupNumber:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition REGEXP_EXTRACT__4P = signature("native function meta::pure::functions::string::regexpExtract(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], extractAll:meta::pure::metamodel::type::Boolean[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition REGEXP_EXTRACT__5 = signature("native function meta::pure::functions::string::regexpExtract(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], extractAll:meta::pure::metamodel::type::Boolean[1], groupNumber:meta::pure::metamodel::type::Integer[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition REGEXP_INDEX_OF__2 = signature("native function meta::pure::functions::string::regexpIndexOf(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_INDEX_OF__3 = signature("native function meta::pure::functions::string::regexpIndexOf(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], groupNumber:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_INDEX_OF__3P = signature("native function meta::pure::functions::string::regexpIndexOf(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_INDEX_OF__4 = signature("native function meta::pure::functions::string::regexpIndexOf(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], groupNumber:meta::pure::metamodel::type::Integer[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REGEXP_REPLACE__4 = signature("native function meta::pure::functions::string::regexpReplace(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], replacement:meta::pure::metamodel::type::String[1], replaceAll:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition REGEXP_REPLACE__5 = signature("native function meta::pure::functions::string::regexpReplace(string:meta::pure::metamodel::type::String[1], regexp:meta::pure::metamodel::type::String[1], replacement:meta::pure::metamodel::type::String[1], replaceAll:meta::pure::metamodel::type::Boolean[1], regexpParameters:meta::pure::functions::string::RegexpParameter[1..*]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition BIT_NOT__INTEGER_1 = signature("native function meta::pure::functions::math::bitNot(arg:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition Z_SCORE__WINDOW = signature("native function meta::pure::functions::math::zScore<T>(partition:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], row:T[1], colToZScore:meta::pure::metamodel::relation::ColSpec<(?:meta::pure::metamodel::type::Number)⊆T>[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition FORMAT_DATE__STRICT_DATE = signature("native function meta::pure::functions::date::formatDate(date:meta::pure::metamodel::type::StrictDate[1], dateFormat:meta::pure::functions::date::StrictDateFormat[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition FORMAT_DATE__DATE_TIME = signature("native function meta::pure::functions::date::formatDate(dateTime:meta::pure::metamodel::type::DateTime[1], dateTimeFormat:meta::pure::functions::date::DateTimeFormat[1]):meta::pure::metamodel::type::String[1];");
    // VERBATIM real pure (platform/pure/essential/lang/flow/match.pure):
    // Nil branch params (bottom — the kernel's FunctionType arm skips them),
    // T[m] = the branch result; MatchChecker REFINES to the statically
    // selected branch's type (sound: a subtype of the signature's T[m]).
    public static final NativeFunctionDefinition MATCH__ANY_MANY__FUNCTION_1_MANY = signature("native function meta::pure::functions::lang::match<T|m,n>(var:meta::pure::metamodel::type::Any[*], functions:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Nil[n]->T[m]}>[1..*]):T[m];");
    public static final NativeFunctionDefinition MATCH__ANY_MANY__FUNCTION_1_MANY__P_o = signature("native function meta::pure::functions::lang::match<T,P|m,n,o>(var:meta::pure::metamodel::type::Any[*], functions:meta::pure::metamodel::function::Function<{meta::pure::metamodel::type::Nil[n],P[o]->T[m]}>[1..*], with:P[o]):T[m];");
    public static final NativeFunctionDefinition MAX_BY__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::maxBy<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):T[0..1];");
    public static final NativeFunctionDefinition MAX_BY__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::math::maxBy<T>(values:T[*], key:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[1]}>[1]):T[0..1];");
    public static final NativeFunctionDefinition MAX_BY__T_MANY__FUNCTION_1__INTEGER_1 = signature("native function meta::pure::functions::math::maxBy<T>(values:T[*], key:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[1]}>[1], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition MAX_BY__T_MANY__T_MANY = signature("native function meta::pure::functions::math::maxBy<T>(values:T[*], keys:T[*]):T[0..1];");
    public static final NativeFunctionDefinition MAX_BY__T_MANY__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::math::maxBy<T>(values:T[*], keys:T[*], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition MAX__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::max(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition MAX__DATE_MANY = signature("native function meta::pure::functions::date::max(dates:meta::pure::metamodel::type::Date[*]):meta::pure::metamodel::type::Date[0..1];");
    public static final NativeFunctionDefinition MAX__DATE_TIME_1__DATE_TIME_1 = signature("native function meta::pure::functions::date::max(left:meta::pure::metamodel::type::DateTime[1], right:meta::pure::metamodel::type::DateTime[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition MAX__DATE_TIME_MANY = signature("native function meta::pure::functions::date::max(dates:meta::pure::metamodel::type::DateTime[*]):meta::pure::metamodel::type::DateTime[0..1];");
    public static final NativeFunctionDefinition MAX__FLOAT_1__FLOAT_1 = signature("native function meta::pure::functions::math::max(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MAX__FLOAT_MANY = signature("native function meta::pure::functions::math::max(values:meta::pure::metamodel::type::Float[*]):meta::pure::metamodel::type::Float[0..1];");
    public static final NativeFunctionDefinition MAX__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::max(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MAX__INTEGER_MANY = signature("native function meta::pure::functions::math::max(values:meta::pure::metamodel::type::Integer[*]):meta::pure::metamodel::type::Integer[0..1];");
    public static final NativeFunctionDefinition MAX__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::max(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition MAX__NUMBER_MANY = signature("native function meta::pure::functions::math::max(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition MAX__STRICT_DATE_1__STRICT_DATE_1 = signature("native function meta::pure::functions::date::max(left:meta::pure::metamodel::type::StrictDate[1], right:meta::pure::metamodel::type::StrictDate[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition MAX__STRICT_DATE_MANY = signature("native function meta::pure::functions::date::max(dates:meta::pure::metamodel::type::StrictDate[*]):meta::pure::metamodel::type::StrictDate[0..1];");
    public static final NativeFunctionDefinition MEAN__NUMBER_MANY = signature("native function meta::pure::functions::math::mean(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MEDIAN__NUMBER_MANY = signature("native function meta::pure::functions::math::median(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Float[1];");   // engine median.pure:17+:26 — BOTH overloads return Float[1]; Number[1] was a mis-transcription F5.3-B caught when the header overlay stopped concealing it
    public static final NativeFunctionDefinition MINUS__DECIMAL_1__DECIMAL_1 = signature("native function meta::pure::functions::math::minus(left:meta::pure::metamodel::type::Decimal[1], right:meta::pure::metamodel::type::Decimal[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition MINUS__FLOAT_1__FLOAT_1 = signature("native function meta::pure::functions::math::minus(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MINUS__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::minus(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MINUS__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::minus(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition MINUS__T_MANY = signature("native function meta::pure::functions::math::minus<T>(values:T[*]):T[1];");
    public static final NativeFunctionDefinition MINUTE__DATE_1 = signature("native function meta::pure::functions::date::minute(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MIN_BY__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::minBy<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):T[0..1];");
    public static final NativeFunctionDefinition MIN_BY__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::math::minBy<T>(values:T[*], key:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[1]}>[1]):T[0..1];");
    public static final NativeFunctionDefinition MIN_BY__T_MANY__FUNCTION_1__INTEGER_1 = signature("native function meta::pure::functions::math::minBy<T>(values:T[*], key:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[1]}>[1], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition MIN_BY__T_MANY__T_MANY = signature("native function meta::pure::functions::math::minBy<T>(values:T[*], keys:T[*]):T[0..1];");
    public static final NativeFunctionDefinition MIN_BY__T_MANY__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::math::minBy<T>(values:T[*], keys:T[*], count:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition MIN__DATE_1__DATE_1 = signature("native function meta::pure::functions::date::min(left:meta::pure::metamodel::type::Date[1], right:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition MIN__DATE_MANY = signature("native function meta::pure::functions::date::min(dates:meta::pure::metamodel::type::Date[*]):meta::pure::metamodel::type::Date[0..1];");
    public static final NativeFunctionDefinition MIN__DATE_TIME_1__DATE_TIME_1 = signature("native function meta::pure::functions::date::min(left:meta::pure::metamodel::type::DateTime[1], right:meta::pure::metamodel::type::DateTime[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition MIN__DATE_TIME_MANY = signature("native function meta::pure::functions::date::min(dates:meta::pure::metamodel::type::DateTime[*]):meta::pure::metamodel::type::DateTime[0..1];");
    public static final NativeFunctionDefinition MIN__FLOAT_1__FLOAT_1 = signature("native function meta::pure::functions::math::min(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MIN__FLOAT_MANY = signature("native function meta::pure::functions::math::min(values:meta::pure::metamodel::type::Float[*]):meta::pure::metamodel::type::Float[0..1];");
    public static final NativeFunctionDefinition MIN__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::min(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MIN__INTEGER_MANY = signature("native function meta::pure::functions::math::min(values:meta::pure::metamodel::type::Integer[*]):meta::pure::metamodel::type::Integer[0..1];");
    public static final NativeFunctionDefinition MIN__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::min(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition MIN__NUMBER_MANY = signature("native function meta::pure::functions::math::min(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition MIN__STRICT_DATE_1__STRICT_DATE_1 = signature("native function meta::pure::functions::date::min(left:meta::pure::metamodel::type::StrictDate[1], right:meta::pure::metamodel::type::StrictDate[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition MIN__STRICT_DATE_MANY = signature("native function meta::pure::functions::date::min(dates:meta::pure::metamodel::type::StrictDate[*]):meta::pure::metamodel::type::StrictDate[0..1];");
    // mode: REAL pure has CONCRETE numeric overloads, result [1] (core_functions_standard/math/aggregator/mode.pure).
    public static final NativeFunctionDefinition MODE__INTEGER_MANY = signature("native function meta::pure::functions::math::mode(numbers:meta::pure::metamodel::type::Integer[*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MODE__FLOAT_MANY = signature("native function meta::pure::functions::math::mode(numbers:meta::pure::metamodel::type::Float[*]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition MODE__NUMBER_MANY = signature("native function meta::pure::functions::math::mode(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition MOD__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::mod(dividend:meta::pure::metamodel::type::Integer[1], divisor:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MONTH_NUMBER__DATE_1 = signature("native function meta::pure::functions::date::monthNumber(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition MONTH__DATE_1 = signature("native function meta::pure::functions::date::month(d:meta::pure::metamodel::type::Date[1]):meta::pure::functions::date::Month[1];");
    public static final NativeFunctionDefinition NEW_TDS_RELATION_ACCESSOR__RELATION_1 = signature("native function meta::pure::metamodel::relation::newTDSRelationAccessor<T>(tds:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition NOT_EQUAL_ANSI__ANY_1__ANY_1 = signature("native function meta::legend::lite::notEqualAnsi(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    // Any-typed ordering shims (ledger cluster 18 — DynaFunc join/filter
    // conditions; the engine leaves these operands untyped).
    public static final NativeFunctionDefinition LESS_THAN_ANY__ANY_1__ANY_1 = signature("native function meta::legend::lite::lessThan(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition LESS_THAN_EQUAL_ANY__ANY_1__ANY_1 = signature("native function meta::legend::lite::lessThanEqual(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_ANY__ANY_1__ANY_1 = signature("native function meta::legend::lite::greaterThan(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition GREATER_THAN_EQUAL_ANY__ANY_1__ANY_1 = signature("native function meta::legend::lite::greaterThanEqual(left:meta::pure::metamodel::type::Any[1], right:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition NOT__BOOLEAN_1 = signature("native function meta::pure::functions::boolean::not(value:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition NOW = signature("native function meta::pure::functions::date::now():meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition NTH__RELATION_1__WINDOW_1__T_1__INTEGER_1 = signature("native function meta::pure::functions::relation::nth<T>(w:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::functions::relation::_Window<T>[1], r:T[1], offset:meta::pure::metamodel::type::Integer[1]):T[0..1];");
    public static final NativeFunctionDefinition NTILE__RELATION_1__T_1__INTEGER_1 = signature("native function meta::pure::functions::relation::ntile<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], row:T[1], tileCount:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition OBJECT_REFERENCE_IN__ANY_1__ANY_MANY = signature("native function meta::pure::functions::collection::objectReferenceIn(col:meta::pure::metamodel::type::Any[1], values:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition OFFSET__RELATION_1__T_1__INTEGER_1 = signature("native function meta::pure::functions::relation::offset<T>(w:meta::pure::metamodel::relation::Relation<T>[1], r:T[1], offset:meta::pure::metamodel::type::Integer[1]):T[0..1];");
    public static final NativeFunctionDefinition OR__BOOLEAN_1__BOOLEAN_1 = signature("native function meta::pure::functions::boolean::or(left:meta::pure::metamodel::type::Boolean[1], right:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition OR__BOOLEAN_MANY = signature("native function meta::pure::functions::collection::or(values:meta::pure::metamodel::type::Boolean[*]):meta::pure::metamodel::type::Boolean[1];");
    // otherwise (generic class-level structural merge): takes a partial
    // instance and a fallback instance of the same class and returns a
    // complete instance — partial's set fields win, fallback fills the
    // rest (docs/MAPPING_CLEAN_SHEET.md §4.3). Emitted by MappingNormalizer
    // for OtherwiseEmbedded PMs as otherwise(^Inner(<inline subs>),
    // $row.<slot>) where the fallback slot is a legacyNavigate'd
    // instance. partial is always constructed (T[1]); the fallback slot
    // may be optional (T[0..1]); the merge yields a complete T[1].
    public static final NativeFunctionDefinition OTHERWISE__T_1__T_0_1 = signature("native function meta::legend::lite::otherwise<T>(partial:T[1], fallback:T[0..1]):T[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_1__SORT_INFO_MANY = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[*]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_1__SORT_INFO_1__RANGE_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[1], range:meta::pure::functions::relation::_Range[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_1__SORT_INFO_MANY__ROWS_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[*], rows:meta::pure::functions::relation::Rows[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1__SORT_INFO_MANY = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[*]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__SORT_INFO_MANY = signature("native function meta::pure::functions::relation::over<T>(sortInfo:meta::pure::functions::relation::SortInfo<T>[*]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__SORT_INFO_1__RANGE_1 = signature("native function meta::pure::functions::relation::over<T>(sortInfo:meta::pure::functions::relation::SortInfo<T>[1], range:meta::pure::functions::relation::_Range[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__SORT_INFO_1__RANGE_INTERVAL_1 = signature("native function meta::pure::functions::relation::over<T>(sortInfo:meta::pure::functions::relation::SortInfo<T>[1], rangeInterval:meta::pure::functions::relation::_RangeInterval[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_1__SORT_INFO_1__RANGE_INTERVAL_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[1], rangeInterval:meta::pure::functions::relation::_RangeInterval[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition MAX_GENERIC__X_MANY = signature("native function meta::pure::functions::collection::max<X>(values:X[*]):X[0..1];");
    public static final NativeFunctionDefinition MAX_GENERIC__X_1_MANY = signature("native function meta::pure::functions::collection::max<X>(values:X[1..*]):X[1];");
    public static final NativeFunctionDefinition MIN_GENERIC__X_MANY = signature("native function meta::pure::functions::collection::min<X>(values:X[*]):X[0..1];");
    public static final NativeFunctionDefinition MIN_GENERIC__X_1_MANY = signature("native function meta::pure::functions::collection::min<X>(values:X[1..*]):X[1];");
    public static final NativeFunctionDefinition MAX_CMP__T_MANY = signature("native function meta::pure::functions::collection::max<T>(col:T[*], comp:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Integer[1]}>[1]):T[0..1];");
    public static final NativeFunctionDefinition MAX_CMP__T_1_MANY = signature("native function meta::pure::functions::collection::max<T>(col:T[1..*], comp:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Integer[1]}>[1]):T[1];");
    public static final NativeFunctionDefinition MIN_CMP__T_MANY = signature("native function meta::pure::functions::collection::min<T>(col:T[*], comp:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Integer[1]}>[1]):T[0..1];");
    public static final NativeFunctionDefinition MIN_CMP__T_1_MANY = signature("native function meta::pure::functions::collection::min<T>(col:T[1..*], comp:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Integer[1]}>[1]):T[1];");
    public static final NativeFunctionDefinition PAIR__T_1__U_1 = signature("native function meta::pure::functions::collection::pair<T,U>(first:T[1], second:U[1]):meta::pure::functions::collection::Pair<T,U>[1];");
    public static final NativeFunctionDefinition NEW_MAP__PAIRS = signature("native function meta::pure::functions::collection::newMap<U,V>(pairs:meta::pure::functions::collection::Pair<U,V>[*]):meta::pure::functions::collection::Map<U,V>[1];");
    public static final NativeFunctionDefinition MAP_GET__MAP_1__U_1 = signature("native function meta::pure::functions::collection::get<U,V>(m:meta::pure::functions::collection::Map<U,V>[1], key:U[1]):V[0..1];");
    public static final NativeFunctionDefinition MAP_PUT__MAP_1__U_1__V_1 = signature("native function meta::pure::functions::collection::put<U,V>(m:meta::pure::functions::collection::Map<U,V>[1], key:U[1], value:V[1]):meta::pure::functions::collection::Map<U,V>[1];");
    public static final NativeFunctionDefinition MAP_PUT_ALL__MAP_1__PAIRS = signature("native function meta::pure::functions::collection::putAll<U,V>(m:meta::pure::functions::collection::Map<U,V>[1], pairs:meta::pure::functions::collection::Pair<U,V>[*]):meta::pure::functions::collection::Map<U,V>[1];");
    public static final NativeFunctionDefinition MAP_PUT_ALL__MAP_1__MAP_1 = signature("native function meta::pure::functions::collection::putAll<U,V>(m:meta::pure::functions::collection::Map<U,V>[1], o:meta::pure::functions::collection::Map<U,V>[1]):meta::pure::functions::collection::Map<U,V>[1];");
    public static final NativeFunctionDefinition MAP_KEYS__MAP_1 = signature("native function meta::pure::functions::collection::keys<U,V>(m:meta::pure::functions::collection::Map<U,V>[1]):U[*];");
    public static final NativeFunctionDefinition MAP_VALUES__MAP_1 = signature("native function meta::pure::functions::collection::values<U,V>(m:meta::pure::functions::collection::Map<U,V>[1]):V[*];");
    public static final NativeFunctionDefinition PARSE_BOOLEAN__STRING_1 = signature("native function meta::pure::functions::string::parseBoolean(string:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition PARSE_DATE__STRING_1 = signature("native function meta::pure::functions::string::parseDate(string:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Date[1];");
    public static final NativeFunctionDefinition PARSE_DECIMAL__STRING_1 = signature("native function meta::pure::functions::string::parseDecimal(string:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition PARSE_DECIMAL__STRING_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::string::parseDecimal(string:meta::pure::metamodel::type::String[1], precision:meta::pure::metamodel::type::Integer[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition PARSE_FLOAT__STRING_1 = signature("native function meta::pure::functions::string::parseFloat(string:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition PARSE_INTEGER__STRING_1 = signature("native function meta::pure::functions::string::parseInteger(string:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition PERCENTILE__NUMBER_MANY__NUMBER_1 = signature("native function meta::pure::functions::math::percentile(numbers:meta::pure::metamodel::type::Number[*], p:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition PERCENTILE__NUMBER_MANY__NUMBER_1__BOOLEAN_1__BOOLEAN_1 = signature("native function meta::pure::functions::math::percentile(numbers:meta::pure::metamodel::type::Number[*], p:meta::pure::metamodel::type::Number[1], ascending:meta::pure::metamodel::type::Boolean[1], continuous:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Number[0..1];");
    public static final NativeFunctionDefinition PERCENT_RANK__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::percentRank<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], w:meta::pure::functions::relation::_Window<T>[1], row:T[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition PI = signature("native function meta::pure::functions::math::pi():meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition PIVOT__RELATION_1__COL_SPEC_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::pivot<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition PIVOT__RELATION_1__COL_SPEC_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::pivot<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition PIVOT__RELATION_1__COL_SPEC_1__ANY_1_MANY__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::pivot<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1], values:meta::pure::metamodel::type::Any[1..*], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition PIVOT__RELATION_1__COL_SPEC_ARRAY_1__AGG_COL_SPEC_1 = signature("native function meta::pure::functions::relation::pivot<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpec<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition PIVOT__RELATION_1__COL_SPEC_ARRAY_1__AGG_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::pivot<T,Z,K,V,R>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1], agg:meta::pure::metamodel::relation::AggColSpecArray<{T[1]->K[0..1]},{K[*]->V[0..1]},R>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition PLUS__DECIMAL_1__DECIMAL_1 = signature("native function meta::pure::functions::math::plus(left:meta::pure::metamodel::type::Decimal[1], right:meta::pure::metamodel::type::Decimal[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition PLUS__FLOAT_1__FLOAT_1 = signature("native function meta::pure::functions::math::plus(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition PLUS__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::plus(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition PLUS__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::plus(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition PLUS__STRING_1__STRING_1 = signature("native function meta::pure::functions::math::plus(left:meta::pure::metamodel::type::String[1], right:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition PLUS__T_MANY = signature("native function meta::pure::functions::math::plus<T>(values:T[*]):T[1];");
    public static final NativeFunctionDefinition POW__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::pow(base:meta::pure::metamodel::type::Number[1], exponent:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition PROJECT__C_MANY__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::project<C,T>(cl:C[*], x:meta::pure::metamodel::relation::FuncColSpecArray<{C[1]->meta::pure::metamodel::type::Any[*]},T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    // real tds.pure spells getString as a TDSRow qualified property
    // ({$this.get($colName)->cast(@String)}:String[1]) — registered as the
    // 2-arg native the dot-call dispatch resolves; the tdsContains
    // cross-operation rewrite substitutes it to a column/outer read
    // real platform assertEqWithinTolerance.pure:17 — numeric tolerance
    // assert; compiles inside collection lambdas (forAll), so it needs a
    // catalog entry (the harness's statement-level arm serves top-level
    // spellings only)
    // assert (REAL essential/asserts/assert.pure): TRUE or raises — in
    // VALUE position (inside a map lambda) it lowers to CASE WHEN cond
    // THEN TRUE ELSE error(...) END; top-level asserts stay the harness's
    // assertion vocabulary.
    public static final NativeFunctionDefinition ASSERT__BOOLEAN_1 = signature("native function meta::pure::functions::asserts::assert(condition:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition ASSERT__BOOLEAN_1__STRING_1 = signature("native function meta::pure::functions::asserts::assert(condition:meta::pure::metamodel::type::Boolean[1], message:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    // real essential/tests/assert.pure:17 — the message-LAMBDA form (135
    // corpus call sites)
    public static final NativeFunctionDefinition ASSERT__BOOLEAN_1__FN_1 = signature("native function meta::pure::functions::asserts::assert(condition:meta::pure::metamodel::type::Boolean[1], messageFunction:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::String[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");
    // Real essential/tests/fail.pure:17/:22 — always-throwing asserts;
    // host evaluation stays loud-unknown, SQL lowering walls (a fail in
    // a reachable branch is typed at the branch value's type — bottom
    // spirit; see IfChecker.thunkBody)
    public static final NativeFunctionDefinition FAIL = signature("native function meta::pure::functions::asserts::fail():meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition FAIL__STRING_1 = signature("native function meta::pure::functions::asserts::fail(message:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    // toRepresentation (REAL essential/string/toString/toRepresentation.pure)
    // — the pure-source spelling of a value. A PLATFORM NATIVE (Phase 4:
    // the pure body is m3-reflective — match over PackageableElement/
    // elementToPath — and cannot compile in our model; PureAsserts.repr
    // is the host owner, the Scalars rule the SQL owner). Platform-owned:
    // parsed/corpus pure definitions suppress.
    public static final NativeFunctionDefinition TO_REPRESENTATION__ANY_1 = signature("native function meta::pure::functions::string::toRepresentation(any:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition ASSERT_EQ_WITHIN_TOLERANCE__NUMBER_1__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::asserts::assertEqWithinTolerance(expected:meta::pure::metamodel::type::Number[1], actual:meta::pure::metamodel::type::Number[1], delta:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Boolean[1];");
    // assertError (REAL essential/tests/assertError.pure:21/:30 — the
    // message forms; the matcher-lambda native at :18 is PCT.platformOnly
    // and needs a SourceInformation VALUE our model does not carry, so
    // the message forms ARE the platform natives). PLATFORM natives
    // (Phase 4): run f in the database, the K-orchestrator catches the
    // database error and adjudicates message + line/column against the
    // error's embedded source-info channel — the interpreted
    // AssertError.java contract. Platform-owned: the parsed pure bodies
    // (which call the matcher native) suppress.
    public static final NativeFunctionDefinition ASSERT_ERROR__FN_1__STRING_1 = signature("native function meta::pure::functions::asserts::assertError(f:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], message:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition ASSERT_ERROR__FN_1__STRING_1__INTEGER_01__INTEGER_01 = signature("native function meta::pure::functions::asserts::assertError(f:meta::pure::metamodel::function::Function<{->meta::pure::metamodel::type::Any[*]}>[1], message:meta::pure::metamodel::type::String[1], line:meta::pure::metamodel::type::Integer[0..1], column:meta::pure::metamodel::type::Integer[0..1]):meta::pure::metamodel::type::Boolean[1];");

    // assertInstanceOf (REAL essential/tests/assertInstanceOf.pure:17/:22,
    // signatures verbatim): PLATFORM natives on the assertError pattern —
    // the parsed pure body needs elementToPath (m3 reflection, unportable),
    // so the K-arm adjudicates the RUNTIME carrier kind against the named
    // type host-side (PureAsserts.assertInstanceOf, the m3 value lattice).
    // Platform-owned: the parsed bodies suppress; a value-position use
    // walls loudly (no lowering — verdicts never compute in SQL).
    public static final NativeFunctionDefinition ASSERT_INSTANCE_OF__ANY_1__TYPE_1 = signature("native function meta::pure::functions::asserts::assertInstanceOf(instance:meta::pure::metamodel::type::Any[1], type:meta::pure::metamodel::type::Type[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition ASSERT_INSTANCE_OF__ANY_1__TYPE_1__STRING_1 = signature("native function meta::pure::functions::asserts::assertInstanceOf(instance:meta::pure::metamodel::type::Any[1], type:meta::pure::metamodel::type::Type[1], message:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");

    public static final NativeFunctionDefinition GET_STRING__TDS_ROW_1__STRING_1 = signature("native function meta::pure::tds::getString(row:meta::pure::tds::TDSRow[1], colName:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");

    // real tds.pure declares tdsContains over TabularDataSet[1]; our TDS
    // carrier is Relation (same divergence as project<K> above) — the
    // relational route rewrites the call to EXISTS over the projected
    // relation (engine pureToSQLQuery tdsContains processor)
    public static final NativeFunctionDefinition TDS_CONTAINS__T_1__FUNCTION_MANY__RELATION_1 = signature("native function meta::pure::tds::tdsContains<T,Z>(object:T[1], fns:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[0..1]}>[*], tds:meta::pure::metamodel::relation::Relation<Z>[1]):meta::pure::metamodel::type::Boolean[1];");

    public static final NativeFunctionDefinition TDS_CONTAINS__T_1__FUNCTION_MANY__STRING_MANY__RELATION_1__FUNCTION_1 = signature("native function meta::pure::tds::tdsContains<T,Z>(object:T[1], fns:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::type::Any[0..1]}>[*], ids:meta::pure::metamodel::type::String[*], tds:meta::pure::metamodel::relation::Relation<Z>[1], crossOperation:meta::pure::metamodel::function::Function<{meta::pure::tds::TDSRow[1],meta::pure::tds::TDSRow[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):meta::pure::metamodel::type::Boolean[1];");

    public static final NativeFunctionDefinition PROJECT__K_MANY__FUNCTION_MANY__STRING_MANY = signature("native function meta::pure::tds::project<K>(set:K[*], fns:meta::pure::metamodel::function::Function<{K[1]->meta::pure::metamodel::type::Any[*]}>[*], ids:meta::pure::metamodel::type::String[*]):meta::pure::metamodel::relation::Relation<K>[1];");
    public static final NativeFunctionDefinition PROJECT__RELATION_1__FUNC_COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::project<T,Z>(r:meta::pure::metamodel::relation::Relation<T>[1], fs:meta::pure::metamodel::relation::FuncColSpecArray<{T[1]->meta::pure::metamodel::type::Any[*]},Z>[1]):meta::pure::metamodel::relation::Relation<Z>[1];");
    public static final NativeFunctionDefinition QUARTER_NUMBER__DATE_1 = signature("native function meta::pure::functions::date::quarterNumber(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition QUARTER__DATE_1 = signature("native function meta::pure::functions::date::quarter(d:meta::pure::metamodel::type::Date[1]):meta::pure::functions::date::Quarter[1];");
    public static final NativeFunctionDefinition RANGE__INTEGER_1 = signature("native function meta::pure::functions::collection::range(stop:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[*];");
    public static final NativeFunctionDefinition RANGE__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::collection::range(start:meta::pure::metamodel::type::Integer[1], stop:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[*];");
    public static final NativeFunctionDefinition RANGE__INTEGER_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::collection::range(start:meta::pure::metamodel::type::Integer[1], stop:meta::pure::metamodel::type::Integer[1], step:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[*];");
    public static final NativeFunctionDefinition RANK__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::relation::rank<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], w:meta::pure::functions::relation::_Window<T>[1], row:T[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition REMOVE_ALL_OPTIMIZED__T_MANY__T_MANY = signature("native function meta::pure::functions::collection::removeAllOptimized<T>(set:T[*], other:T[*]):T[*];");
    public static final NativeFunctionDefinition REMOVE_DUPLICATES_BY__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::removeDuplicatesBy<T,V>(col:T[*], key:meta::pure::metamodel::function::Function<{T[1]->V[1]}>[1]):T[*];");
    public static final NativeFunctionDefinition REMOVE_DUPLICATES__T_MANY = signature("native function meta::pure::functions::collection::removeDuplicates<T>(col:T[*]):T[*];");
    public static final NativeFunctionDefinition DISTINCT__T_MANY = signature("native function meta::pure::functions::collection::distinct<T>(s:T[*]):T[*];");
    /** The collection overload's key, exported so LOWERING (parser-free) can rule on it. */
    public static final String DISTINCT_COLLECTION_KEY = DISTINCT__T_MANY.signatureKey();
    /** pair()'s key, exported for the STRUCT-carrier lowering rule. */
    public static final String PAIR_KEY = PAIR__T_1__U_1.signatureKey();
    /** Map get()'s key — the bare name is shared with variant get. */
    public static final String MAP_GET_KEY = MAP_GET__MAP_1__U_1.signatureKey();
    public static final NativeFunctionDefinition REMOVE_DUPLICATES__T_MANY__FUNCTION_0_1__FUNCTION_0_1 = signature("native function meta::pure::functions::collection::removeDuplicates<T,V>(col:T[*], key:meta::pure::metamodel::function::Function<{T[1]->V[1]}>[0..1], eql:meta::pure::metamodel::function::Function<{V[1],V[1]->meta::pure::metamodel::type::Boolean[1]}>[0..1]):T[*];");
    public static final NativeFunctionDefinition REMOVE_DUPLICATES__T_MANY__FUNCTION_1 = signature("native function meta::pure::functions::collection::removeDuplicates<T>(col:T[*], eql:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Boolean[1]}>[1]):T[*];");
    public static final NativeFunctionDefinition REM__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::rem(dividend:meta::pure::metamodel::type::Number[1], divisor:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition RENAME__RELATION_1__COL_SPEC_1__COL_SPEC_1 = signature("native function meta::pure::functions::relation::rename<T,Z,K,V>(r:meta::pure::metamodel::relation::Relation<T>[1], old:meta::pure::metamodel::relation::ColSpec<Z=(?:K)⊆T>[1], new:meta::pure::metamodel::relation::ColSpec<V=(?:K)>[1]):meta::pure::metamodel::relation::Relation<T-Z+V>[1];");
    public static final NativeFunctionDefinition RENAME__RELATION_1__COL_SPEC_ARRAY_1__COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::rename<T,Z,V>(r:meta::pure::metamodel::relation::Relation<T>[1], oldCols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1], newCols:meta::pure::metamodel::relation::ColSpecArray<V>[1]):meta::pure::metamodel::relation::Relation<T-Z+V>[1];");
    public static final NativeFunctionDefinition REPEAT_STRING__STRING_0_1__INTEGER_1 = signature("native function meta::pure::functions::string::repeatString(str:meta::pure::metamodel::type::String[0..1], count:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[0..1];");
    public static final NativeFunctionDefinition REPLACE__STRING_1__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::replace(str:meta::pure::metamodel::type::String[1], toFind:meta::pure::metamodel::type::String[1], replacement:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition REVERSE_STRING__STRING_1 = signature("native function meta::pure::functions::string::reverseString(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition REVERSE__T_m = signature("native function meta::pure::functions::collection::reverse<T|m>(set:T[m]):T[m];");
    public static final NativeFunctionDefinition RIGHT__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::right(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition ROUND__DECIMAL_1__INTEGER_1 = signature("native function meta::pure::functions::math::round(decimal:meta::pure::metamodel::type::Decimal[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition ROUND__FLOAT_1__INTEGER_1 = signature("native function meta::pure::functions::math::round(float:meta::pure::metamodel::type::Float[1], scale:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition ROUND__NUMBER_1 = signature("native function meta::pure::functions::math::round(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition ROWS__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::relation::rows(offsetFrom:meta::pure::metamodel::type::Integer[1], offsetTo:meta::pure::metamodel::type::Integer[1]):meta::pure::functions::relation::Rows[1];");
    // over(frame) — real over.pure line 21's (cols:String[*], sortInfo:[*],
    // frame:Frame[0..1]) admits the frame-only call through empty varargs;
    // our arity-exact matching registers the collapsed form.
    public static final NativeFunctionDefinition OVER__COL_SPEC_1__ROWS_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpec<T>[1], rows:meta::pure::functions::relation::Rows[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1__ROWS_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1], rows:meta::pure::functions::relation::Rows[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1__SORT_INFO_MANY__ROWS_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[*], rows:meta::pure::functions::relation::Rows[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1__SORT_INFO_1__RANGE_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[1], range:meta::pure::functions::relation::_Range[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition OVER__COL_SPEC_ARRAY_1__SORT_INFO_1__RANGE_INTERVAL_1 = signature("native function meta::pure::functions::relation::over<T>(cols:meta::pure::metamodel::relation::ColSpecArray<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<T>[1], rangeInterval:meta::pure::functions::relation::_RangeInterval[1]):meta::pure::functions::relation::_Window<T>[1];");
    public static final NativeFunctionDefinition REDUCE__RELATION_1__WINDOW_1__T_1__FUNCTION_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::reduce<T,V,U|m>(rel:meta::pure::metamodel::relation::Relation<T>[1], w:meta::pure::functions::relation::_Window<T>[1], row:T[1], map:meta::pure::metamodel::function::Function<{T[1]->V[*]}>[1], agg:meta::pure::metamodel::function::Function<{V[*]->U[m]}>[1]):U[m];");
    public static final NativeFunctionDefinition FROM_JSON__STRING_1 = signature("native function meta::pure::functions::variant::convert::fromJson(json:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::variant::Variant[1];");
    public static final NativeFunctionDefinition VARIANT_FLATTEN__T_MANY__COL_SPEC_1 = signature("native function meta::pure::functions::relation::variant::flatten<T>(valueToFlatten:T[*], columnWithFlattenedValue:meta::pure::metamodel::relation::ColSpec<meta::pure::metamodel::type::Any>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition LATERAL__RELATION_1__FUNCTION_1 = signature("native function meta::pure::functions::relation::lateral<T,V>(rel:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::metamodel::function::Function<{T[1]->meta::pure::metamodel::relation::Relation<V>[1]}>[1]):meta::pure::metamodel::relation::Relation<T+V>[1];");
    public static final NativeFunctionDefinition ROWS__UNBOUNDED_1__UNBOUNDED_1 = signature("native function meta::pure::functions::relation::rows(offsetFrom:meta::pure::functions::relation::UnboundedFrameValue[1], offsetTo:meta::pure::functions::relation::UnboundedFrameValue[1]):meta::pure::functions::relation::Rows[1];");
    public static final NativeFunctionDefinition ROWS__UNBOUNDED_1__INTEGER_1 = signature("native function meta::pure::functions::relation::rows(offsetFrom:meta::pure::functions::relation::UnboundedFrameValue[1], offsetTo:meta::pure::metamodel::type::Integer[1]):meta::pure::functions::relation::Rows[1];");
    public static final NativeFunctionDefinition ROWS__INTEGER_1__UNBOUNDED_1 = signature("native function meta::pure::functions::relation::rows(offsetFrom:meta::pure::metamodel::type::Integer[1], offsetTo:meta::pure::functions::relation::UnboundedFrameValue[1]):meta::pure::functions::relation::Rows[1];");
    public static final NativeFunctionDefinition ROW_MAPPER__T_0_1__U_0_1 = signature("native function meta::pure::functions::math::mathUtility::rowMapper<T,U>(value:T[0..1], key:U[0..1]):meta::pure::functions::math::mathUtility::RowMapper<T,U>[1];");
    public static final NativeFunctionDefinition ROW_NUMBER__RELATION_1__T_1 = signature("native function meta::pure::functions::relation::rowNumber<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], row:T[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition RPAD__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::rpad(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition RPAD__STRING_1__INTEGER_1__STRING_1 = signature("native function meta::pure::functions::string::rpad(str:meta::pure::metamodel::type::String[1], len:meta::pure::metamodel::type::Integer[1], pad:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition RTRIM__STRING_1 = signature("native function meta::pure::functions::string::rtrim(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SECOND__DATE_1 = signature("native function meta::pure::functions::date::second(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SELECT__RELATION_1 = signature("native function meta::pure::functions::relation::select<T>(r:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition SELECT__RELATION_1__COL_SPEC_1 = signature("native function meta::pure::functions::relation::select<T,Z>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpec<Z⊆T>[1]):meta::pure::metamodel::relation::Relation<Z>[1];");
    public static final NativeFunctionDefinition SELECT__RELATION_1__COL_SPEC_ARRAY_1 = signature("native function meta::pure::functions::relation::select<T,Z>(r:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::relation::ColSpecArray<Z⊆T>[1]):meta::pure::metamodel::relation::Relation<Z>[1];");
    public static final NativeFunctionDefinition SERIALIZE__T_MANY__ROOT_GRAPH_FETCH_TREE_1 = signature("native function meta::pure::graphFetch::execution::serialize<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SERIALIZE__T_MANY__ROOT_GRAPH_FETCH_TREE_1__ANY_1 = signature("native function meta::pure::graphFetch::execution::serialize<T>(source:T[*], tree:meta::pure::graphFetch::RootGraphFetchTree<T>[1], config:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SIGN__NUMBER_1 = signature("native function meta::pure::functions::math::sign(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SINH__NUMBER_1 = signature("native function meta::pure::functions::math::sinh(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition SIN__NUMBER_1 = signature("native function meta::pure::functions::math::sin(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition SIZE__RELATION_1 = signature("native function meta::pure::functions::relation::size<T>(rel:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SIZE__T_MANY = signature("native function meta::pure::functions::collection::size<T>(col:T[*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SLICE__RELATION_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::relation::slice<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], start:meta::pure::metamodel::type::Integer[1], stop:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition SLICE__T_MANY__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::collection::slice<T>(set:T[*], start:meta::pure::metamodel::type::Integer[1], end:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition SORT_BY_REVERSED__T_m__FUNCTION_0_1 = signature("native function meta::pure::functions::collection::sortByReversed<T,U|m>(col:T[m], key:meta::pure::metamodel::function::Function<{T[1]->U[1]}>[0..1]):T[m];");
    public static final NativeFunctionDefinition SORT_BY__T_m__FUNCTION_0_1 = signature("native function meta::pure::functions::collection::sortBy<T,U|m>(col:T[m], key:meta::pure::metamodel::function::Function<{T[1]->U[1]}>[0..1]):T[m];");
    public static final NativeFunctionDefinition SORT__RELATION_1__SORT_INFO_MANY = signature("native function meta::pure::functions::relation::sort<X,T>(rel:meta::pure::metamodel::relation::Relation<T>[1], sortInfo:meta::pure::functions::relation::SortInfo<X⊆T>[*]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition SORT__RELATION_1__STRING_1__SORT_DIRECTION_1 = signature("native function meta::pure::tds::sort<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], col:meta::pure::metamodel::type::String[1], direction:meta::relational::metamodel::SortDirection[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition SORT__RELATION_1__STRING_MANY = signature("native function meta::pure::functions::relation::sort<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], cols:meta::pure::metamodel::type::String[*]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition SORT__T_m = signature("native function meta::pure::functions::collection::sort<T|m>(col:T[m]):T[m];");
    public static final NativeFunctionDefinition SORT__T_m__FUNCTION_0_1 = signature("native function meta::pure::functions::collection::sort<T|m>(col:T[m], comp:meta::pure::metamodel::function::Function<{T[1],T[1]->meta::pure::metamodel::type::Integer[1]}>[0..1]):T[m];");
    public static final NativeFunctionDefinition SORT__T_m__FUNCTION_0_1__FUNCTION_0_1 = signature("native function meta::pure::functions::collection::sort<T,U|m>(col:T[m], key:meta::pure::metamodel::function::Function<{T[1]->U[1]}>[0..1], comp:meta::pure::metamodel::function::Function<{U[1],U[1]->meta::pure::metamodel::type::Integer[1]}>[0..1]):T[m];");
    public static final NativeFunctionDefinition SOURCE_URL__STRING_1 = signature("native function meta::legend::lite::sourceUrl(url:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition SPLIT_PART__STRING_0_1__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::splitPart(str:meta::pure::metamodel::type::String[0..1], delimiter:meta::pure::metamodel::type::String[1], index:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[0..1];");
    public static final NativeFunctionDefinition SPLIT__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::split(str:meta::pure::metamodel::type::String[1], delimiter:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[*];");
    public static final NativeFunctionDefinition SQL_FALSE = signature("native function meta::relational::functions::sqlQueryToString::sqlFalse():meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition SQL_NULL = signature("native function meta::relational::functions::sqlQueryToString::sqlNull():meta::pure::metamodel::type::Nil[0];");
    public static final NativeFunctionDefinition SQL_TRUE = signature("native function meta::relational::functions::sqlQueryToString::sqlTrue():meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition SQRT__NUMBER_1 = signature("native function meta::pure::functions::math::sqrt(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition STARTS_WITH__STRING_1__STRING_1 = signature("native function meta::pure::functions::string::startsWith(source:meta::pure::metamodel::type::String[1], val:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition STD_DEV_POPULATION__NUMBER_MANY = signature("native function meta::pure::functions::math::stdDevPopulation(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition STD_DEV_POPULATION__RELATION_1__WINDOW_1__T_1__COL_SPEC_1 = signature("native function meta::pure::functions::math::stdDevPopulation<T>(partition:meta::pure::metamodel::relation::Relation<T>[1], window:meta::pure::functions::relation::_Window<T>[1], row:T[1], colToAgg:meta::pure::metamodel::relation::ColSpec<(?:meta::pure::metamodel::type::Number)⊆T>[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition STD_DEV_SAMPLE__NUMBER_MANY = signature("native function meta::pure::functions::math::stdDevSample(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition STD_DEV__NUMBER_MANY = signature("native function meta::pure::functions::math::stdDev(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    // CORPUS-SHAPE window overload — see VARIANCE__RELATION_1__WINDOW_1__T_1.
    public static final NativeFunctionDefinition STD_DEV__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::math::stdDev<T>(w:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::functions::relation::_Window<T>[1], r:T[1]):T[0..1];");
    public static final NativeFunctionDefinition SUBSTRING__STRING_1__INTEGER_1 = signature("native function meta::pure::functions::string::substring(str:meta::pure::metamodel::type::String[1], start:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SUBSTRING__STRING_1__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::string::substring(str:meta::pure::metamodel::type::String[1], start:meta::pure::metamodel::type::Integer[1], end:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition SUB__DECIMAL_1__DECIMAL_1 = signature("native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Decimal[1], right:meta::pure::metamodel::type::Decimal[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition SUB__FLOAT_1__FLOAT_1 = signature("native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition SUB__INTEGER_1__INTEGER_1 = signature("native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SUB__NUMBER_1__NUMBER_1 = signature("native function meta::legend::lite::sub(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition SUM__FLOAT_MANY = signature("native function meta::pure::functions::math::sum(numbers:meta::pure::metamodel::type::Float[*]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition SUM__INTEGER_MANY = signature("native function meta::pure::functions::math::sum(numbers:meta::pure::metamodel::type::Integer[*]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition SUM__NUMBER_MANY = signature("native function meta::pure::functions::math::sum(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition TABLE_REFERENCE__STRING_1__STRING_1 = signature("native function meta::relational::functions::database::tableReference(db:meta::pure::metamodel::type::String[1], name:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    // tableToTDS (REAL: meta::pure::tds::tableToTDS(table:Table[1]):TableTDS[1],
    // tableToTDS.pure:22) — over OUR relation carrier the table reference IS
    // the TDS value; the checker validates and emits identity.
    public static final NativeFunctionDefinition TABLE_TO_TDS__RELATION_1 = signature("native function meta::pure::tds::tableToTDS(table:meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    // REAL engine form (storeContract.pure: tableReference_Database_1__String_1__String_1__Table_1_):
    // (db, SCHEMA, table) — the corpus calls it directly with a schema name.
    public static final NativeFunctionDefinition TABLE_REFERENCE__STRING_1__STRING_1__STRING_1 = signature("native function meta::relational::functions::database::tableReference(db:meta::pure::metamodel::type::String[1], schema:meta::pure::metamodel::type::String[1], name:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition TAIL__T_MANY = signature("native function meta::pure::functions::collection::tail<T>(set:T[*]):T[*];");
    public static final NativeFunctionDefinition TAKE__RELATION_1__INTEGER_1 = signature("native function meta::pure::functions::collection::take<T>(rel:meta::pure::metamodel::relation::Relation<T>[1], size:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::relation::Relation<T>[1];");
    public static final NativeFunctionDefinition TAKE__T_MANY__INTEGER_1 = signature("native function meta::pure::functions::collection::take<T>(set:T[*], size:meta::pure::metamodel::type::Integer[1]):T[*];");
    public static final NativeFunctionDefinition TANH__NUMBER_1 = signature("native function meta::pure::functions::math::tanh(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TAN__NUMBER_1 = signature("native function meta::pure::functions::math::tan(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TDS__STRING_1__STRING_1 = signature("native function meta::legend::lite::tds(tag:meta::pure::metamodel::type::String[1], raw:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::relation::Relation<meta::pure::metamodel::type::Any>[1];");
    public static final NativeFunctionDefinition TIMES__DECIMAL_1__DECIMAL_1 = signature("native function meta::pure::functions::math::times(left:meta::pure::metamodel::type::Decimal[1], right:meta::pure::metamodel::type::Decimal[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition TIMES__FLOAT_1__FLOAT_1 = signature("native function meta::pure::functions::math::times(left:meta::pure::metamodel::type::Float[1], right:meta::pure::metamodel::type::Float[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TIMES__INTEGER_1__INTEGER_1 = signature("native function meta::pure::functions::math::times(left:meta::pure::metamodel::type::Integer[1], right:meta::pure::metamodel::type::Integer[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition TIMES__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::math::times(left:meta::pure::metamodel::type::Number[1], right:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition TIMES__T_MANY = signature("native function meta::pure::functions::math::times<T>(values:T[*]):T[1];");
    // timeBucket: REAL pure has CONCRETE overloads (core_functions_standard/date/operation/timeBucket.pure)
    // — the abstract Date form was ours, and it broke lattice-kind recovery (midnight buckets).
    public static final NativeFunctionDefinition TIME_BUCKET__DATETIME_1__INTEGER_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::timeBucket(date:meta::pure::metamodel::type::DateTime[1], quantity:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::DateTime[1];");
    public static final NativeFunctionDefinition TIME_BUCKET__STRICTDATE_1__INTEGER_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::timeBucket(date:meta::pure::metamodel::type::StrictDate[1], quantity:meta::pure::metamodel::type::Integer[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition TODAY = signature("native function meta::pure::functions::date::today():meta::pure::metamodel::type::StrictDate[1];");
    public static final NativeFunctionDefinition TO_DECIMAL__NUMBER_1 = signature("native function meta::pure::functions::math::toDecimal(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Decimal[1];");
    public static final NativeFunctionDefinition TO_DEGREES__NUMBER_1 = signature("native function meta::pure::functions::math::toDegrees(radians:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TO_EPOCH_VALUE__DATE_1 = signature("native function meta::pure::functions::date::toEpochValue(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition TO_EPOCH_VALUE__DATE_1__DURATION_UNIT_1 = signature("native function meta::pure::functions::date::toEpochValue(d:meta::pure::metamodel::type::Date[1], unit:meta::pure::functions::date::DurationUnit[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition TO_FLOAT__NUMBER_1 = signature("native function meta::pure::functions::math::toFloat(number:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TO_LOWER_FIRST_CHARACTER__STRING_1 = signature("native function meta::pure::functions::string::toLowerFirstCharacter(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_LOWER__STRING_1 = signature("native function meta::pure::functions::string::toLower(source:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_MANY__T_0_1__V_0_1 = signature("native function meta::pure::functions::variant::convert::toMany<T,V>(source:T[0..1], type:V[0..1]):V[*];");
    public static final NativeFunctionDefinition TO_ONE_MANY__T_MANY = signature("native function meta::pure::functions::multiplicity::toOneMany<T>(values:T[*]):T[1..*];");
    public static final NativeFunctionDefinition TO_ONE_MANY__T_MANY__STRING_1 = signature("native function meta::pure::functions::multiplicity::toOneMany<T>(values:T[*], message:meta::pure::metamodel::type::String[1]):T[1..*];");
    public static final NativeFunctionDefinition TO_ONE__T_MANY = signature("native function meta::pure::functions::multiplicity::toOne<T>(values:T[*]):T[1];");
    public static final NativeFunctionDefinition TO_ONE__T_MANY__STRING_1 = signature("native function meta::pure::functions::multiplicity::toOne<T>(values:T[*], message:meta::pure::metamodel::type::String[1]):T[1];");
    public static final NativeFunctionDefinition TO_RADIANS__NUMBER_1 = signature("native function meta::pure::functions::math::toRadians(degrees:meta::pure::metamodel::type::Number[1]):meta::pure::metamodel::type::Float[1];");
    public static final NativeFunctionDefinition TO_STRING__ANY_1 = signature("native function meta::pure::functions::string::toString(any:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_UPPER_FIRST_CHARACTER__STRING_1 = signature("native function meta::pure::functions::string::toUpperFirstCharacter(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_UPPER__STRING_1 = signature("native function meta::pure::functions::string::toUpper(source:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TO_VARIANT__ANY_MANY = signature("native function meta::pure::functions::variant::convert::toVariant(source:meta::pure::metamodel::type::Any[*]):meta::pure::metamodel::variant::Variant[1];");
    public static final NativeFunctionDefinition TO__T_0_1__V_0_1 = signature("native function meta::pure::functions::variant::convert::to<T,V>(source:T[0..1], type:V[0..1]):V[0..1];");
    public static final NativeFunctionDefinition TRIM__STRING_1 = signature("native function meta::pure::functions::string::trim(str:meta::pure::metamodel::type::String[1]):meta::pure::metamodel::type::String[1];");
    public static final NativeFunctionDefinition TYPE__ANY_1 = signature("native function meta::pure::functions::meta::type(any:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Type[1];");
    public static final NativeFunctionDefinition UNBOUNDED = signature("native function meta::pure::functions::relation::unbounded():meta::pure::functions::relation::UnboundedFrameValue[1];");
    public static final NativeFunctionDefinition VARIANCE_POPULATION__NUMBER_MANY = signature("native function meta::pure::functions::math::variancePopulation(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition VARIANCE_SAMPLE__NUMBER_MANY = signature("native function meta::pure::functions::math::varianceSample(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    public static final NativeFunctionDefinition VARIANCE__NUMBER_MANY = signature("native function meta::pure::functions::math::variance(numbers:meta::pure::metamodel::type::Number[*]):meta::pure::metamodel::type::Number[1];");
    // CORPUS-SHAPE window overload (no real-pure counterpart): the
    // first/last 3-arg spelling with the column named by the wrapping
    // property access — conform-by-emission, VARIANCE(col) OVER (...).
    public static final NativeFunctionDefinition VARIANCE__RELATION_1__WINDOW_1__T_1 = signature("native function meta::pure::functions::math::variance<T>(w:meta::pure::metamodel::relation::Relation<T>[1], f:meta::pure::functions::relation::_Window<T>[1], r:T[1]):T[0..1];");
    public static final NativeFunctionDefinition VARIANCE__NUMBER_MANY__BOOLEAN_1 = signature("native function meta::pure::functions::math::variance(numbers:meta::pure::metamodel::type::Number[*], isSample:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Number[1];");
    // lite spelling of REAL pure meta::pure::functions::variant::convert::to/toMany.
    public static final NativeFunctionDefinition WAVG__ROW_MAPPER_MANY = signature("native function meta::pure::functions::math::wavg<T,U>(values:meta::pure::functions::math::mathUtility::RowMapper<T,U>[*]):meta::pure::metamodel::type::Float[1];");
    // wavgRowMapper (REAL math/wavgUtility spelling, handlers:464 —
    // wavgRowMapper(Number[0..1], Number[0..1]):WavgRowMapper[1]): the
    // corpus's receiver-style pair builder ($x.quantity->wavgRowMapper(
    // $x.weight)); carried as the SAME RowMapper family (the aggregate
    // decompose reads (value, weight) positionally either way).
    public static final NativeFunctionDefinition WAVG_ROW_MAPPER__NUMBER_0_1__NUMBER_0_1 = signature("native function meta::pure::functions::math::wavgUtility::wavgRowMapper(value:meta::pure::metamodel::type::Number[0..1], weight:meta::pure::metamodel::type::Number[0..1]):meta::pure::functions::math::mathUtility::RowMapper<meta::pure::metamodel::type::Number, meta::pure::metamodel::type::Number>[1];");
    public static final NativeFunctionDefinition WEEK_OF_YEAR__DATE_1 = signature("native function meta::pure::functions::date::weekOfYear(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition WRITE__RELATION_1 = signature("native function meta::pure::functions::relation::write<T>(source:meta::pure::metamodel::relation::Relation<T>[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition WRITE__RELATION_1__ANY_1 = signature("native function meta::pure::functions::relation::write<T>(source:meta::pure::metamodel::relation::Relation<T>[1], target:meta::pure::metamodel::type::Any[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition XOR__BOOLEAN_1__BOOLEAN_1 = signature("native function meta::pure::functions::boolean::xor(left:meta::pure::metamodel::type::Boolean[1], right:meta::pure::metamodel::type::Boolean[1]):meta::pure::metamodel::type::Boolean[1];");
    public static final NativeFunctionDefinition YEAR__DATE_1 = signature("native function meta::pure::functions::date::year(d:meta::pure::metamodel::type::Date[1]):meta::pure::metamodel::type::Integer[1];");
    public static final NativeFunctionDefinition ZIP__T_MANY__U_MANY = signature("native function meta::pure::functions::collection::zip<T,U>(set1:T[*], set2:U[*]):meta::pure::functions::collection::Pair<T,U>[*];");
    public static final NativeFunctionDefinition _RANGE__NUMBER_1__NUMBER_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::metamodel::type::Number[1], offsetTo:meta::pure::metamodel::type::Number[1]):meta::pure::functions::relation::_Range[1];");
    public static final NativeFunctionDefinition _RANGE__UNBOUNDED_1__NUMBER_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::functions::relation::UnboundedFrameValue[1], offsetTo:meta::pure::metamodel::type::Number[1]):meta::pure::functions::relation::_Range[1];");
    public static final NativeFunctionDefinition _RANGE__NUMBER_1__UNBOUNDED_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::metamodel::type::Number[1], offsetTo:meta::pure::functions::relation::UnboundedFrameValue[1]):meta::pure::functions::relation::_Range[1];");
    public static final NativeFunctionDefinition _RANGE__INT_1__DU_1__INT_1__DU_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::metamodel::type::Integer[1], offsetFromDurationUnit:meta::pure::functions::date::DurationUnit[1], offsetTo:meta::pure::metamodel::type::Integer[1], offsetToDurationUnit:meta::pure::functions::date::DurationUnit[1]):meta::pure::functions::relation::_RangeInterval[1];");
    public static final NativeFunctionDefinition _RANGE__UNBOUNDED_1__INT_1__DU_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::functions::relation::UnboundedFrameValue[1], offsetTo:meta::pure::metamodel::type::Integer[1], offsetToDurationUnit:meta::pure::functions::date::DurationUnit[1]):meta::pure::functions::relation::_RangeInterval[1];");
    public static final NativeFunctionDefinition _RANGE__INT_1__DU_1__UNBOUNDED_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::metamodel::type::Integer[1], offsetFromDurationUnit:meta::pure::functions::date::DurationUnit[1], offsetTo:meta::pure::functions::relation::UnboundedFrameValue[1]):meta::pure::functions::relation::_RangeInterval[1];");
    public static final NativeFunctionDefinition _RANGE__UNBOUNDED_1__UNBOUNDED_1 = signature("native function meta::pure::functions::relation::_range(offsetFrom:meta::pure::functions::relation::UnboundedFrameValue[1], offsetTo:meta::pure::functions::relation::UnboundedFrameValue[1]):meta::pure::functions::relation::_Range[1];");
}
