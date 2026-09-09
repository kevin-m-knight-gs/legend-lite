package com.legend.compiler.element.type;

/**
 * EXACT identification of the platform's distinguished types — the ONE home
 * for these checks. Suffix matching ({@code endsWith("::List")}) was a bug
 * class, not a convenience: a user class that happens to share a simple name
 * (my::domain::List) must never be mistaken for the platform carrier.
 *
 * <p>The FQN constants mirror the {@code builtin/Pure} prelude declarations;
 * {@code PlatformTypesDriftTest} pins the two against each other so neither
 * can move alone. (Constants rather than {@code Pure.X.qualifiedName()}
 * references keep this package free of a dependency on the parser-level
 * prelude classes.)
 */
public final class PlatformTypes {

    /**
     * The Typer's {@code .rows} MARKER property (identity over a relation
     * value — the K result frame's row-index/envelope disambiguator).
     * ONE constant for producer (Typer desugars) and consumers
     * (StoreResolver erasure floor, Lowerer defensive floor) — audit 23 A6.
     */
    public static final String ROWS_MARKER = "rows";
    /** The Class metaclass FQN — type VALUES at the lowering boundary
     * travel as canonical simple-name strings (task #78). */
    public static final String CLASS_METACLASS =
            "meta::pure::metamodel::type::Class";


    /**
     * The TDS null-cell SENTINEL spelling — real pure's {@code ^TDSNull()}
     * instance prints as this string (tds.pure). ONE constant for every
     * producer (Typer toString-get desugar, makeString/joinStrings NULL
     * coalesce) and parser (TDS-literal cells, harness wire compares) —
     * audit 23 C-d. Divergence note: pure DROPS empty elements from
     * ordinary collections; we print the sentinel only where TDS-row
     * semantics apply (ledgered in AUDIT_23_SPECIAL_CASING.md).
     */
    public static final String TDS_NULL_CELL = "TDSNull";

    /** The TDS null-cell CLASS (engine tds.pure:127) — {@code ^TDSNull()}
     * types as an instance of it, stamped [1] (a VALUE, never an empty). */
    public static final String TDS_NULL_FQN = "meta::pure::tds::TDSNull";

    private PlatformTypes() {
    }

    public static final String ANY = "meta::pure::metamodel::type::Any";
    public static final String NIL = "meta::pure::metamodel::type::Nil";
    public static final String VARIANT = "meta::pure::metamodel::variant::Variant";
    public static final String LIST = "meta::pure::functions::collection::List";
    public static final String PAIR = "meta::pure::functions::collection::Pair";
    public static final String FUNCTION = "meta::pure::metamodel::function::Function";
    /** The m3 function-carrier hierarchy under {@link #FUNCTION}:
     * {@code LambdaFunction<F>} and {@code ConcreteFunctionDefinition<F>}
     * extend {@code FunctionDefinition<F>} extends {@code Function<F>}
     * (m3.pure). A value-level function's CLASSIFIER is one of these
     * carriers; the structural {@code FunctionType} it wraps is the
     * signature. Lambda literals classify as LambdaFunction; references
     * to body-bearing user functions as ConcreteFunctionDefinition;
     * native-function references are NOT FunctionDefinitions. */
    public static final String FUNCTION_DEFINITION = "meta::pure::metamodel::function::FunctionDefinition";
    public static final String LAMBDA_FUNCTION = "meta::pure::metamodel::function::LambdaFunction";
    public static final String CONCRETE_FUNCTION_DEFINITION = "meta::pure::metamodel::function::ConcreteFunctionDefinition";

    /** The classifier of a lambda literal: {@code LambdaFunction<ft>}. */
    public static Type lambdaType(Type.FunctionType ft) {
        return new Type.GenericType(LAMBDA_FUNCTION, java.util.List.of(ft));
    }

    /** The classifier of a reference to a body-bearing user function:
     * {@code ConcreteFunctionDefinition<ft>}. */
    public static Type concreteFunctionDefinitionType(Type.FunctionType ft) {
        return new Type.GenericType(CONCRETE_FUNCTION_DEFINITION, java.util.List.of(ft));
    }

    /** The structural signature a function-valued type carries — the bare
     * {@code FunctionType}, or the one inside a carrier spelling
     * ({@code Function<{…}>}, {@code LambdaFunction<{…}>}, …); {@code null}
     * when {@code t} is not function-valued (including carriers whose
     * argument is nominal, e.g. {@code FunctionDefinition<Any>}). */
    public static Type.@com.legend.Nullable FunctionType functionTypeOf(Type t) {
        if (t instanceof Type.FunctionType ft) {
            return ft;
        }
        if (t instanceof Type.GenericType g && g.arguments().size() == 1
                && g.arguments().get(0) instanceof Type.FunctionType ft) {
            return ft;
        }
        return null;
    }
    /** The legacy TDS surface — ≡ the relation carrier at the value level
     * ({@code cast(@TabularDataSet)} is a type ASSERTION, never a wire
     * conversion). */
    public static final String TABULAR_DATA_SET = "meta::pure::tds::TabularDataSet";

    public static final String TDS_ROW = "meta::pure::tds::TDSRow";

    /** The {@code TDS<T>} relation class (tds.pure:17) — a relation
     * literal's own type; {@code csv: String[1]} (tds.pure:19) is its text. */
    public static final String TDS_RELATION_CLASS = "meta::pure::metamodel::relation::TDS";

    /** The TDS class's csv property name (tds.pure:19); a read of it over a
     * TDS-shaped value is the csv TEXT the engine prints for the relation. */
    public static final String TDS_CSV_PROPERTY = "csv";

    /** Whether {@code t} is TDS-SHAPED: a schema-bearing {@code Relation<..>},
     * the {@code TDS<T>} relation class, or TabularDataSet — the types a
     * cast between which is a type-level no-op (exact FQNs, never a
     * suffix match). */
    public static boolean isTdsShaped(Type t) {
        return Type.isRelation(t)
                || isTdsType(t)
                || t instanceof Type.GenericType g
                        && TDS_RELATION_CLASS.equals(g.rawFqn());
    }

    /** Whether {@code t} is the TDS carrier type (exact FQN, never a
     * suffix match). */
    public static boolean isTdsType(Type t) {
        return t instanceof Type.ClassType ct
                        && TABULAR_DATA_SET.equals(ct.fqn())
                || t instanceof Type.GenericType gt
                        && TABULAR_DATA_SET.equals(gt.rawFqn());
    }

    /**
     * The K-native JDBC boundary: raw-SQL execution over the ambient
     * connection ({@code Compiler}'s executeInDb dispatch). A FUNCTION
     * FQN, not a type — it lives here because this class is the one home
     * for exact platform-FQN identification.
     */
    public static final String EXECUTE_IN_DB = "meta::relational::metamodel::execute::executeInDb";

    /** executeInDbToTDS(sql, connectionFunction) — the engine's program is
     * executeInDb(sql, fn)->resultSetToTDS() (execute.pure:73-90): a VALUE
     * MAPPING of the result set into a TDS, which our raw-grid relation
     * already is. Platform-owned (the program never inlines); the Typer's
     * raw-grid arm binds the call ONCE to the late-bound relation exactly
     * as executeInDb over a single-query literal. Batch 82. */
    public static final String EXECUTE_IN_DB_TO_TDS =
            "meta::relational::metamodel::execute::executeInDbToTDS";

    /** JDBC DatabaseMetaData reads — HOST-evaluated against the H2
     * second target (engine-parity metadata casing), never lowered. */
    public static final String FETCH_DB_TABLES_META_DATA =
            "meta::relational::metamodel::execute::fetchDbTablesMetaData";
    public static final String FETCH_DB_COLUMNS_META_DATA =
            "meta::relational::metamodel::execute::fetchDbColumnsMetaData";
    public static final String FETCH_DB_SCHEMAS_META_DATA =
            "meta::relational::metamodel::execute::fetchDbSchemasMetaData";
    public static final String FETCH_DB_PRIMARY_KEYS_META_DATA =
            "meta::relational::metamodel::execute::fetchDbPrimaryKeysMetaData";

    public static boolean isFetchDbFn(String fqn) {
        return FETCH_DB_TABLES_META_DATA.equals(fqn)
                || FETCH_DB_COLUMNS_META_DATA.equals(fqn)
                || FETCH_DB_SCHEMAS_META_DATA.equals(fqn)
                || FETCH_DB_PRIMARY_KEYS_META_DATA.equals(fqn);
    }

    public enum FetchDbKind { SCHEMAS, TABLES, COLUMNS, PRIMARY_KEYS }

    public static FetchDbKind fetchDbKind(String fqn) {
        if (FETCH_DB_SCHEMAS_META_DATA.equals(fqn)) {
            return FetchDbKind.SCHEMAS;
        }
        if (FETCH_DB_TABLES_META_DATA.equals(fqn)) {
            return FetchDbKind.TABLES;
        }
        if (FETCH_DB_COLUMNS_META_DATA.equals(fqn)) {
            return FetchDbKind.COLUMNS;
        }
        if (FETCH_DB_PRIMARY_KEYS_META_DATA.equals(fqn)) {
            return FetchDbKind.PRIMARY_KEYS;
        }
        throw new IllegalArgumentException("not a fetchDb native: " + fqn);
    }

    /** K-native sibling of {@link #EXECUTE_IN_DB}: model-derived drop+create DDL. */
    public static final String DROP_AND_CREATE_TABLE_IN_DB =
            "meta::relational::functions::toDDL::dropAndCreateTableInDb";

    /** loadCsvToDbTable(filePath, table, connection) — the engine's native
     * (legend-pure LoadCsvToDbTable) reads a classpath CSV, drops its
     * header row and inserts the rows positionally into the table
     * (execute.pure:57-66 delegate to it). An EFFECT at the execution
     * boundary; the CSV is TEST INPUT the harness resolves
     * (exec.TestResources). Batch 85. */
    public static final String LOAD_CSV_TO_DB_TABLE =
            "meta::relational::metamodel::execute::loadCsvToDbTable";

    /** Schema (re)creation K-native (toDDL.pure:108). */
    public static final String DROP_AND_CREATE_SCHEMA_IN_DB =
            "meta::relational::functions::toDDL::dropAndCreateSchemaInDb";

    /** DDL STRING generators (toDDL.pure deprecated 1-/3-arg forms):
     * evaluate in the EXECUTOR (the engine walks its Database metamodel;
     * we render from the compiled store model — model access the lowerer
     * does not have). Engine golden spellings: testDDL.pure:42-45. */
    public static final String DROP_SCHEMA_STATEMENT =
            "meta::relational::functions::toDDL::dropSchemaStatement";
    public static final String CREATE_SCHEMA_STATEMENT =
            "meta::relational::functions::toDDL::createSchemaStatement";
    public static final String CREATE_TABLE_STATEMENT =
            "meta::relational::functions::toDDL::createTableStatement";
    public static final String DROP_TABLE_STATEMENT =
            "meta::relational::functions::toDDL::dropTableStatement";

    /** Store-metamodel NAVIGATION natives (platform_store_relational/
     * functions.pure:227/:249) — HOST-evaluated over the compiled store
     * model (the reflection leg's store domain). */
    public static final String STORE_SCHEMA_NAV =
            "meta::relational::metamodel::schema";
    public static final String STORE_TABLE_NAV =
            "meta::relational::metamodel::table";

    public static boolean isStoreNavFn(String fqn) {
        return STORE_SCHEMA_NAV.equals(fqn) || STORE_TABLE_NAV.equals(fqn);
    }

    /** One of the DDL string-generator natives. */
    public static boolean isDdlStatementFn(String fqn) {
        return DROP_SCHEMA_STATEMENT.equals(fqn)
                || CREATE_SCHEMA_STATEMENT.equals(fqn)
                || CREATE_TABLE_STATEMENT.equals(fqn)
                || DROP_TABLE_STATEMENT.equals(fqn);
    }

    /** The engine's SQL-text surface — K-dispatched: the query lambda
     * lowers through the platform's own G½->H->I against the given mapping
     * and renders with the engine-style dialect (audit 19d B3: this was a
     * name-intercepting harness arm; the corpus's own toSQLString body is
     * engine plan-generation internals, suppressed like toDDL). */
    public static final String TO_SQL_STRING =
            "meta::relational::functions::sqlstring::toSQLString";

    /** toSQLString with the engine's pretty Format — same K-dispatch
     * (sqlRemoveFormatting normalizes the whitespace difference away in
     * every golden compare; engine toSQLString.pure:35). */
    public static final String TO_SQL_STRING_PRETTY =
            "meta::relational::functions::sqlstring::toSQLStringPretty";

    /** toSQL(f, mapping, runtime, ext) — the SQLResult HANDLE of the
     * same doctrine (engine toSQLString.pure:46): the query lambda and
     * mapping ride on it and {@code SQLResult.toSQLString(dbType, tz,
     * quote, format)} (:151, the 5-argument toSQLString overload)
     * forces it through the one renderer. Batch 75. */
    public static final String TO_SQL =
            "meta::relational::functions::sqlstring::toSQL";

    /** toNonExecutableSQLString(f, mapping, dbType, ext) — toSQLString with
     * the engine's nonExecutable post-processor installed (toSQLString.pure:83-86):
     * the same K-routine, the nonExecutable IR pass applied before the
     * render. Batch 81. */
    public static final String TO_NON_EXECUTABLE_SQL_STRING =
            "meta::relational::functions::sqlstring::toNonExecutableSQLString";

    /** The engine's CSV-seed SQL generator — K-dispatched (CsvSeed). */
    public static final String SET_UP_DATA_SQLS_V2 =
            "meta::alloy::service::execution::setUpDataSQLsV2";

    /** The deprecated plain spelling — PLATFORM-OWNED (the corpus's own
     * ladder is M3-reflective and its DatabaseType wrapper cannot type
     * against createDbConfig's Any); same CsvSeed K-arm. */
    public static final String SET_UP_DATA_SQLS =
            "meta::alloy::service::execution::setUpDataSQLs";

    /** The plan surface (#47) — PLATFORM-OWNED opaque handle + K-native
     * literal plan-text rendering (toSQLString doctrine). */
    public static final String EXECUTION_PLAN =
            "meta::pure::executionPlan::executionPlan";
    /** The lineage surface (harness burn-down group E): a PLATFORM-OWNED
     * opaque handle whose relation tree is rows (LineageRows). */
    public static final String SCAN_RELATIONS =
            "meta::pure::lineage::scanRelations::scanRelations";
    /** The column-lineage chain (group I): two opaque intermediate handles
     * and the rows-bearing terminal. */
    public static final String SCAN_PROPERTIES =
            "meta::pure::lineage::scanProperties::scanProperties";
    public static final String BUILD_PROPERTY_TREE =
            "meta::pure::lineage::scanProperties::propertyTree::buildPropertyTree";
    public static final String SCAN_COLUMNS =
            "meta::pure::lineage::scanColumns::scanColumns";

    /** The metaclass a HANDLE native's rows extend as (the chain root
     * re-roots at its extent keyed by the handle's content id): the
     * native's DECLARED return class — a handle's rows ARE its result
     * (executionPlan → ExecutionPlan, scanRelations → RelationTree,
     * scanColumns → ColumnWithContext, execute → Result whose activities
     * are the execution's activity rows). Null for a non-handle native
     * or a handle whose result is not a class (preval's function value).
     * No per-FQN table: the registry labels the kind, the signature
     * names the class. */
    public static @com.legend.Nullable String handleRowClass(String fqn, Type returnType) {
        if (IMPLEMENTATION_KIND.get(fqn) != NativeImpl.HANDLE) {
            return null;
        }
        return switch (returnType) {
            case Type.ClassType c -> c.fqn();
            case Type.GenericType g -> g.rawFqn();
            default -> null;
        };
    }
    /** Plan-time constant pre-evaluation — a FUNCTION-VALUED identity
     * for plan construction (the wrapped lambda IS the query). */
    public static final String PREVAL =
            "meta::pure::router::preeval::preval";
    public static final String PLAN_TO_STRING =
            "meta::pure::executionPlan::toString::planToString";
    /** {@code planToString} minus newlines and spaces (real
     * executionPlan_print.pure:27). */
    public static final String PLAN_TO_STRING_WITHOUT_FORMATTING =
            "meta::pure::executionPlan::toString::planToStringWithoutFormatting";

    /** The engine's execution entry — K-dispatched as a RESULT FRAME
     * (audit 19d B2: {@code Result} is a typing surface plus an
     * orchestration handle, never a host object graph; reads over it
     * splice into SQL-bound typed queries in the statement executor). */
    /** THE execute entry point — real pure's meta::pure::router::execute
     * (router_entry.pure; reachable bare via m3.pure's auto-import of
     * meta::pure::router). The old meta::pure::mapping::execute alias
     * was an invented FQN (audit R8) and is deleted. */
    public static final String EXECUTE = "meta::pure::router::execute";

    /** The ROUTER'S STRING ENTRY — real engine devUtils.pure:30/:35
     * {@code meta::legend::executeLegendQuery(f, vars, [exeCtx,] ext)}:
     * the same result frame as {@link #EXECUTE} with the query lambda's
     * parameters bound from the vars pairs, read as the engine's RESULT
     * JSON string ({@code ExecuteChainAssembly.prepareLegendQuery} /
     * {@code legendQueryEnvelope}). */
    public static final String EXECUTE_LEGEND_QUERY =
            "meta::legend::executeLegendQuery";

    public static boolean isLegendQueryFqn(String fqn) {
        return EXECUTE_LEGEND_QUERY.equals(fqn);
    }

    /** The engine's PLAN-EXECUTE entry — real pure's
     * meta::pure::executionPlan::execute(plan, parametersValues,
     * extensions) (executionPlan_execution.pure:20). The platform
     * NORMALIZES it to the ordinary execute frame by peeling the plan
     * argument to its executionPlan(...) build (same positional arg
     * shape: query, mapping, runtime, extensions) — one execution
     * semantics, the one router; plan TEXT is engine-text
     * (EngineStyleH2) and never executes on the session connection. */
    public static final String EXECUTION_PLAN_EXECUTE =
            "meta::pure::executionPlan::execute";

    public static boolean isExecuteFqn(String fqn) {
        return EXECUTE.equals(fqn) || EXECUTION_PLAN_EXECUTE.equals(fqn);
    }

    /** The m3 profiles the compiler reads semantics from (legend-pure
     * m3.pure / profiles.pure). */
    public static final String EQUALITY_PROFILE = "meta::pure::profiles::equality";
    public static final String TEMPORAL_PROFILE = "meta::pure::profiles::temporal";
    public static final String PCT_PROFILE = "meta::pure::test::pct::PCT";

    /** Whether a stereotype's RESOLVED profile name is {@code profileFqn}.
     * The resolver qualifies a bare profile name through the file's
     * imports and the core-import group when the profile is declared in
     * the model; a model that does not declare it keeps the bare spelling
     * — that spelling is the same profile (the m3 profiles are the only
     * ones the compiler reads). */
    public static boolean isProfile(String resolvedName, String profileFqn) {
        return profileFqn.equals(resolvedName)
                || profileFqn.substring(profileFqn.lastIndexOf(':') + 1).equals(resolvedName);
    }


    /**
     * PLATFORM-OWNED function FQNs: legend-lite's native IS the definition
     * — user re-definitions (the real engine's toDDL.pure bodies walk the
     * Database METAMODEL, M3 reflection this platform doesn't model) are
     * suppressed at the overload merge, exactly like real pure natives
     * replacing their stub bodies. executeInDb is NOT owned: the corpus's
     * 2-arg wrapper there is legitimate pure code over the 4-arg leaf.
     */
    /** The dialect-config handle feeding toSQLString/DebugContext — the
     * corpus bodies build DbConfig by eval'ing stored dialect lambdas
     * (loadDbExtension), M3 machinery this platform K-dispatches instead.
     * Per-module compiles never carried the corpus definitions; the
     * global corpus compile always does, so ownership must be explicit. */
    public static final String CREATE_DB_CONFIG =
            "meta::relational::functions::sqlQueryToString::createDbConfig";

    /** The RENDER phase's CSV text fn (F4.2) — the corpus's own
     *  M3-reflective body never joins the overload set. */
    public static final String TO_CSV = "meta::relational::tests::csv::toCSV";

    /** toRepresentation: the platform native (Phase 4 — the pure body is
     * m3-reflective and unportable; the native is the definition). */
    public static final String TO_REPRESENTATION =
            "meta::pure::functions::string::toRepresentation";

    /** assertError: the platform native (Phase 4 — the pure /2 and /4
     * bodies delegate to a PCT.platformOnly matcher native over a
     * SourceInformation value our model does not carry; the K-orchestrated
     * catch IS the definition). */
    public static final String ASSERT_INSTANCE_OF =
            "meta::pure::functions::asserts::assertInstanceOf";

    public static final String ASSERT_ERROR =
            "meta::pure::functions::asserts::assertError";

    /** TDG lane S1: the CSV-census native — a COMPILE-TIME reflection
     * fact (model-space, no database) that FOLDS in the checker to
     * instance literals; the production TestDataGenerator IS the
     * implementation, the real pure body is the spec (verified by
     * signature, never loaded). */
    public static final String GET_RELATIONAL_CSV_DATA =
            "meta::relational::testDataGeneration::getRelationalCSVDataFromQuery";

    /** TDG lane S2: the RUNTIME data-extraction native — the checker
     * captures the call's protocol (carrier), the ORCHESTRATOR executes
     * the fetches through the database and splices the result as
     * literals. */
    public static final String GENERATE_TEST_DATA =
            "meta::relational::testDataGeneration::generateTestData";
    /** The TDG plan (testDataGeneration.pure:818/823): a plan HANDLE whose
     * planToString is the engine's MultiResultSequence text. */
    public static final String PLAN_TEST_DATA_GENERATION =
            "meta::relational::testDataGeneration::executionPlan::planTestDataGeneration";
    public static final String GENERATE_SEED_DATA_STRING =
            "meta::relational::testDataGeneration::generateSeedDataString";

    // ---- the execution context's vocabulary (ExecutionContext.Reader is
    // the only reader of these classes' fields) ----
    public static final String RUNTIME = "meta::core::runtime::Runtime";
    public static final String CONNECTION_STORE = "meta::core::runtime::ConnectionStore";
    public static final String WITH_CHAINED_MAPPINGS = "meta::pure::mapping::withChainedMappings";
    public static final String MODEL_CHAIN_CONNECTION =
            "meta::external::store::model::ModelChainConnection";
    public static final String JSON_MODEL_CONNECTION =
            "meta::external::store::model::JsonModelConnection";
    public static final String LOCAL_H2_DATASOURCE_SPECIFICATION =
            "meta::pure::alloy::connections::alloy::specification::LocalH2DatasourceSpecification";
    public static final String DATABASE_CONNECTION =
            "meta::external::store::relational::runtime::DatabaseConnection";
    public static final String RELATIONAL_DATABASE_CONNECTION =
            "meta::external::store::relational::runtime::RelationalDatabaseConnection";
    public static final String TEST_DATABASE_CONNECTION =
            "meta::external::store::relational::runtime::TestDatabaseConnection";
    /** The corpus's runtime builder — a platform-owned function (the
     * quoteIdentifiers overload carries the flag as its argument). */
    public static final String TEST_RUNTIME =
            "meta::external::store::relational::tests::testRuntime";
    public static final String IS_EMPTY = "meta::pure::functions::collection::isEmpty";
    /** The engine's relational execution OPTIONS class (executionContext.pure) — the one
     *  context reader spells its fields. */
    public static final String RELATIONAL_EXECUTION_CONTEXT =
            "meta::relational::runtime::RelationalExecutionContext";
    public static final String DURATION = "meta::pure::functions::date::Duration";
    public static final String MAP = "meta::pure::functions::collection::map";
    public static final String PLUS = "meta::pure::functions::math::plus";

    /** The three relational connection classes (exact FQN). */
    public static boolean isRelationalConnectionClass(String fqn) {
        return DATABASE_CONNECTION.equals(fqn)
                || RELATIONAL_DATABASE_CONNECTION.equals(fqn)
                || TEST_DATABASE_CONNECTION.equals(fqn);
    }

    /** The plan-text simple name of a relational connection class, given
     * its FQN or (in an UNCHECKED helper body) its bare class name; null
     * for any other class. */
    public static @com.legend.Nullable String relationalConnectionSimpleName(String nameOrFqn) {
        if (DATABASE_CONNECTION.equals(nameOrFqn) || "DatabaseConnection".equals(nameOrFqn)) {
            return "DatabaseConnection";
        }
        if (RELATIONAL_DATABASE_CONNECTION.equals(nameOrFqn)
                || "RelationalDatabaseConnection".equals(nameOrFqn)) {
            return "RelationalDatabaseConnection";
        }
        if (TEST_DATABASE_CONNECTION.equals(nameOrFqn)
                || "TestDatabaseConnection".equals(nameOrFqn)) {
            return "TestDatabaseConnection";
        }
        return null;
    }

    /** FQN or bare (unchecked helper body) spelling. */
    public static boolean isModelChainConnection(String nameOrFqn) {
        return MODEL_CHAIN_CONNECTION.equals(nameOrFqn) || "ModelChainConnection".equals(nameOrFqn);
    }

    public static boolean isJsonModelConnection(String nameOrFqn) {
        return JSON_MODEL_CONNECTION.equals(nameOrFqn) || "JsonModelConnection".equals(nameOrFqn);
    }

    public static boolean isLocalH2DatasourceSpecification(String nameOrFqn) {
        return LOCAL_H2_DATASOURCE_SPECIFICATION.equals(nameOrFqn)
                || "LocalH2DatasourceSpecification".equals(nameOrFqn);
    }

    /** The string/number {@code +} in a raw body: bare or FQN spelling. */
    public static boolean isPlus(String nameOrFqn) {
        return PLUS.equals(nameOrFqn) || "plus".equals(nameOrFqn);
    }

    /** The asserts package: every function in it is a VERDICT the
     * statement channel adjudicates (AssertVerdicts). */
    public static final String ASSERTS_PACKAGE = "meta::pure::functions::asserts::";
    public static final String ASSERT_EQUALS = ASSERTS_PACKAGE + "assertEquals";
    /** VERDICT functions declared OUTSIDE the asserts package — user
     * functions in the model whose calls the statement channel
     * adjudicates by exact FQN instead of running their Pure bodies
     * (AssertVerdicts' root arms). */
    public static final String ASSERT_SAME_SQL =
            "meta::relational::functions::asserts::assertSameSQL";
    public static final String ASSERT_SQL_EQUALS_TDG =
            "meta::relational::testDataGeneration::tests::assertSqlEquals";
    public static final String ASSERT_EQUALS_H2_COMPATIBLE =
            "meta::relational::functions::sqlQueryToString::h2::assertEqualsH2Compatible";
    public static final String ASSERT_TDS_EQUIVALENT =
            "meta::pure::functions::relation::assertTdsEquivalent";

    /** A call the statement channel ADJUDICATES as a verdict (never runs
     * as Pure): the asserts package (package membership, exact spelling)
     * and the named verdict functions. */
    public static boolean isVerdictFunction(String fqn) {
        return fqn.startsWith(ASSERTS_PACKAGE)
                || ASSERT_SAME_SQL.equals(fqn)
                || ASSERT_SQL_EQUALS_TDG.equals(fqn)
                || ASSERT_EQUALS_H2_COMPATIBLE.equals(fqn)
                || ASSERT_TDS_EQUIVALENT.equals(fqn);
    }

    /** A call only the STATEMENT channel can run — an execution, a store
     * effect or a test-data generator: it never lowers inside an
     * expression, so a user function whose own statements reach one is
     * a PROGRAM, and a call to a program splices at statement level
     * ({@link com.legend.compiler.StatementInline}). A verdict is NOT on
     * this list: a helper that only asserts β-reduces to an assert root
     * and is adjudicated as that verdict (the statement channel's
     * inlined-assert routes), never run as statements. */
    public static boolean isStatementOnly(String fqn) {
        return isEffectfulNative(fqn)
                || EXECUTE.equals(fqn)
                || EXECUTION_PLAN_EXECUTE.equals(fqn)
                || EXECUTE_LEGEND_QUERY.equals(fqn)
                || GENERATE_TEST_DATA.equals(fqn)
                || GENERATE_SEED_DATA_STRING.equals(fqn)
                // the seed-SQL form (setUpDataSQLs): a statement-channel
                // form — executed when mapped over executeInDb, compared as
                // engine text under a TDG assert; never a value expression
                || isSeedSqlForm(fqn);
    }

    /** The ASSERT FAMILY is platform-owned WHOLESALE (V7 tenet
     * correction 2026-08-28: asserts are verdicts ALWAYS —
     * AssertVerdicts/the K-arm IS the implementation; the real pure
     * bodies are the SPEC, verified by signature in the registry,
     * NEVER loaded as runtime components). Parsed twins — PCT trees,
     * any corpus/library source — suppress loudly. */
    private static final java.util.Set<String> ASSERT_FAMILY_OWNED =
            java.util.Set.of(
                    "meta::pure::functions::asserts::assert",
                    "meta::pure::functions::asserts::assertFalse",
                    "meta::pure::functions::asserts::assertEquals",
                    "meta::pure::functions::asserts::assertNotEquals",
                    "meta::pure::functions::asserts::assertSameElements",
                    "meta::pure::functions::asserts::assertSize",
                    "meta::pure::functions::asserts::assertEq",
                    "meta::pure::functions::asserts::assertEmpty",
                    "meta::pure::functions::asserts::assertNotEmpty",
                    "meta::pure::functions::asserts::assertIs",
                    "meta::pure::functions::asserts::assertContains",
                    "meta::pure::functions::asserts::assertEqWithinTolerance",
                    "meta::pure::functions::asserts::assertJsonStringsEqual");

    /**
     * DERIVED properties the platform implements NATIVELY — the function
     * half's by-name suppression rule (PRELUDE_MODULE_HOMEWORK §3, §9.14)
     * applied to a class's own qualified properties. The spec's
     * {@code TDSRow} reads its cells through the engine's row
     * representation ({@code $this.values}, {@code columnByName}, asserts);
     * on this platform a row IS a SQL row and the accessors are the natives
     * {@code meta::pure::tds::get*(row, col)}. The typer's derived-shadow
     * route and the overload set yield to the native for these; the
     * module still CARRIES the spec bodies (verbatim, T5).
     */
    private static final java.util.Set<String> TDS_ROW_OWNED_ACCESSORS = java.util.Set.of(
            "get", "isNull", "isNotNull",
            "getString", "getNullableString", "getNumber", "getInteger", "getFloat",
            "getDecimal", "getDate", "getDateTime", "getStrictDate", "getBoolean", "getEnum");

    /**
     * THE CONSTRUCTED VOCABULARY (PHASE3_DEMAND_CUT_HOMEWORK §2, T1 by USE):
     * the classes and enums whose VALUES the platform's own Java builds —
     * typed instances, enum values, checker outputs. Each entry is a
     * receipt naming the constructing site. With the native signatures and
     * the system metamodel's source this list IS the platform's demand on
     * the prelude generator; a class Java merely compares against a
     * constant, or reads out of a value a program built, is not here and
     * lives in the graph. Provenance-blind: legend-pure entries are prelude
     * anyway (T1's first clause), listed because the list is "what Java
     * constructs", nothing else.
     */
    private static final java.util.Map<String, String> CONSTRUCTED_VOCABULARY = java.util.Map.ofEntries(
            java.util.Map.entry("meta::json::JSONArray",
                    "JsonChecker builds JSON arrays (toVariant of the elements, annotated JSONArray)"),
            java.util.Map.entry("meta::json::JSONKeyValue", "JsonChecker builds key-value nodes as typed instances"),
            java.util.Map.entry("meta::relational::metamodel::data::RelationalCSVData",
                    "CsvCensusChecker: TypedNewInstance(DATA_FQN) — the platform's CSV census result"),
            java.util.Map.entry("meta::relational::metamodel::data::RelationalCSVTable",
                    "CsvCensusChecker: TypedNewInstance(TABLE_FQN) per table"),
            java.util.Map.entry("meta::relational::testDataGeneration::TestDataGenResult",
                    "GenerateTestDataChecker: the typed result of generateTestData"),
            java.util.Map.entry("meta::pure::functions::relation::JoinKind",
                    "JoinChecker / Lowerer / AssociationJoins / Pipelines: new EnumValue(JoinKind, …)"),
            java.util.Map.entry("meta::pure::metamodel::relation::Column",
                    "ColumnsChecker: TypedNewInstance(COLUMN_FQN) — columns() as Column instances"),
            java.util.Map.entry("meta::pure::functions::date::DurationUnit",
                    "RelOpTranslator builds new EnumValue(DurationUnit, …) for the relational date operations"),
            java.util.Map.entry("meta::pure::functions::hash::HashType",
                    "RelOpTranslator builds new EnumValue(HashType, …) for the digest operations"));

    /** The FQNs of {@link #CONSTRUCTED_VOCABULARY}, for the prelude generator. */
    public static java.util.Set<String> constructedVocabulary() {
        return CONSTRUCTED_VOCABULARY.keySet();
    }

    /** {@code meta::pure::functions::string::format}: its {@code %s} slots
     * print an argument by the argument's own {@code toString()} — real
     * pure's format calls toString on each value, so a CLASS-typed slot
     * (a Pair, a List, a user class) types as {@code $arg->toString()} and
     * reaches the class's own body (batch 152: the Pair/List Java arms in
     * lowering/Scalars, ports of the spec bodies, are gone). */
    public static final String FORMAT = "meta::pure::functions::string::format";

    /** A value whose text form is its class's {@code toString()}: an
     * instance of a class or a class carrier — never a primitive, an enum,
     * {@code Any}/{@code Nil} (variant-carried scalars print as
     * themselves), a variant, or a relation. */
    public static boolean printsByOwnToString(Type t) {
        return switch (t) {
            case Type.ClassType c -> !isAny(c) && !isNil(c) && !isVariant(c)
                    && Type.schemaView(c) == null;
            case Type.GenericType g -> isPairCarrier(g) || isListCarrier(g);
            default -> false;
        };
    }

    public static boolean isPlatformOwnedDerivedProperty(String ownerFqn, String name) {
        return TDS_ROW.equals(ownerFqn) && TDS_ROW_OWNED_ACCESSORS.contains(name);
    }

    public static boolean isPlatformOwnedFunction(String fqn) {
        return DROP_AND_CREATE_TABLE_IN_DB.equals(fqn)
                || TO_REPRESENTATION.equals(fqn)
                || ASSERT_ERROR.equals(fqn)
                || ASSERT_INSTANCE_OF.equals(fqn)
                || ASSERT_FAMILY_OWNED.contains(fqn)
                || TO_CSV.equals(fqn)
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn)
                || isDdlStatementFn(fqn)
                || EXECUTE_IN_DB_TO_TDS.equals(fqn)
                || LOAD_CSV_TO_DB_TABLE.equals(fqn)
                || TO_SQL_STRING.equals(fqn)
                || TO_SQL_STRING_PRETTY.equals(fqn)
                || TO_SQL.equals(fqn)
                || TO_NON_EXECUTABLE_SQL_STRING.equals(fqn)
                || SET_UP_DATA_SQLS.equals(fqn)
                || EXECUTION_PLAN.equals(fqn)
                || PLAN_TO_STRING.equals(fqn)
                || PLAN_TO_STRING_WITHOUT_FORMATTING.equals(fqn)
                || CREATE_DB_CONFIG.equals(fqn)
                || GET_RELATIONAL_CSV_DATA.equals(fqn)
                || GENERATE_TEST_DATA.equals(fqn)
                || PLAN_TEST_DATA_GENERATION.equals(fqn)
                || GENERATE_SEED_DATA_STRING.equals(fqn)
                || EXECUTE.equals(fqn)
                || EXECUTION_PLAN_EXECUTE.equals(fqn);
    }

    /** Debug output — K-dispatched as a NO-OP, arguments never evaluated. */
    public static final String PRINT = "meta::pure::functions::io::print";
    public static final String PRINTLN = "meta::pure::functions::io::println";

    /**
     * K-natives with REAL side effects (raw SQL over the connection).
     * print/println are K-DISPATCHED but effect-FREE (no-op arm) — the
     * effectful-let guard and statement-orchestration routing key on THIS,
     * not on {@link #isKNative} (audit 17: counting print as an effect
     * made harmless let bindings refuse loudly).
     */
    public static boolean isEffectfulNative(String fqn) {
        return EXECUTE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_TABLE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn)
                || LOAD_CSV_TO_DB_TABLE.equals(fqn);
    }

    /** Post-processor CONFIG property names (runtime/connection hook
     * slots): their values are plan-time SQL-rewrite config, never Pure
     * the executor evaluates — the effect scan and the inliner treat
     * them as config, not query code (ledger cluster 63). */
    public static boolean isPostProcessorConfigProperty(String name) {
        return "sqlQueryPostProcessors".equals(name)
                || "sqlQueryPostProcessorsConnectionAware".equals(name)
                || "queryPostProcessorsWithParameter".equals(name);
    }

    /** INERT diagnostics: print/println — the executor's registered arm
     * never evaluates the argument (engine parity is the statement's
     * inertness), so the resolver leaves such a statement untouched too:
     * a printed LAMBDA VALUE ({@code println($l->evaluateAndDeactivate())})
     * is data, never a query to resolve. */
    public static boolean isInertDiagnostic(String fqn) {
        return PRINT.equals(fqn) || PRINTLN.equals(fqn);
    }

    /** All K-natives: calls that EXECUTE at the K boundary and never lower. */
    public static boolean isKNative(String fqn) {
        return EXECUTE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_TABLE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn)
                || LOAD_CSV_TO_DB_TABLE.equals(fqn)
                || TO_SQL_STRING.equals(fqn)
                || TO_SQL_STRING_PRETTY.equals(fqn)
                || TO_SQL.equals(fqn)
                || TO_NON_EXECUTABLE_SQL_STRING.equals(fqn)
                || SET_UP_DATA_SQLS_V2.equals(fqn)
                || SET_UP_DATA_SQLS.equals(fqn)
                || EXECUTION_PLAN.equals(fqn)
                || PLAN_TO_STRING.equals(fqn)
                || PLAN_TO_STRING_WITHOUT_FORMATTING.equals(fqn)
                || EXECUTE.equals(fqn)
                || EXECUTION_PLAN_EXECUTE.equals(fqn)
                || PRINT.equals(fqn) || PRINTLN.equals(fqn);
    }

    /** The top type. */
    public static boolean isAny(Type t) {
        return t instanceof Type.ClassType c && c.fqn().equals(ANY);
    }

    /** The bottom type (the []-born element type). */
    public static boolean isNil(Type t) {
        return t instanceof Type.ClassType c && c.fqn().equals(NIL);
    }

    /** The semi-structured JSON carrier — the Variant class, and the
     * {@code meta::json} tree classes (real json.pure:32-70), whose values
     * RIDE the same carrier (a JSON element IS a JSON value; the classes
     * are its kinds). JSONKeyValue rides it too: a member is represented
     * by its value, the key being spelled by the access. */
    public static boolean isVariant(Type t) {
        return t instanceof Type.ClassType c
                && (c.fqn().equals(VARIANT) || JSON_FAMILY.contains(c.fqn()));
    }

    public static final String JSON_ELEMENT = "meta::json::JSONElement";
    public static final String JSON_OBJECT = "meta::json::JSONObject";
    public static final String JSON_ARRAY = "meta::json::JSONArray";
    public static final String JSON_STRING = "meta::json::JSONString";
    public static final String JSON_NUMBER = "meta::json::JSONNumber";
    public static final String JSON_BOOLEAN = "meta::json::JSONBoolean";
    public static final String JSON_NULL = "meta::json::JSONNull";
    public static final String JSON_KEY_VALUE = "meta::json::JSONKeyValue";
    /** The {@code meta::json} tree classes (json.pure:32-70). */
    public static final java.util.Set<String> JSON_FAMILY = java.util.Set.of(
            JSON_ELEMENT, JSON_OBJECT, JSON_ARRAY, JSON_STRING, JSON_NUMBER,
            JSON_BOOLEAN, JSON_NULL, JSON_KEY_VALUE);

    /** A {@code meta::json} tree class (element kinds + the key-value pair). */
    public static boolean isJsonElement(Type t) {
        return t instanceof Type.ClassType c && JSON_FAMILY.contains(c.fqn());
    }

    /** The {@code List<T>} collection carrier (parameterized form). */
    public static boolean isListCarrier(Type t) {
        return t instanceof Type.GenericType g && g.rawFqn().equals(LIST)
                && g.arguments().size() == 1;
    }

    /** The {@code Pair<U,V>} value carrier (parameterized form). */
    public static boolean isPairCarrier(Type t) {
        return t instanceof Type.GenericType g && g.rawFqn().equals(PAIR)
                && g.arguments().size() == 2;
    }

    /** The {@code Map<U,V>} collection carrier (parameterized form). */
    public static boolean isMapCarrier(Type t) {
        return t instanceof Type.GenericType g
                && g.rawFqn().equals("meta::pure::functions::collection::Map")
                && g.arguments().size() == 2;
    }

    /** The {@code Function<{…}>} value carrier (parameterized form). */
    public static boolean isFunctionCarrier(Type t) {
        return t instanceof Type.GenericType g && g.rawFqn().equals(FUNCTION);
    }

    /**
     * HOW a registered native is implemented — the catalog FACT the
     * executor dispatches by (exact FQN lookup, never statement
     * silhouettes). Absent = the default: an SQL rule (the Lowerer
     * translates; the database executes — filter, startsWith, ...).
     */
    public enum NativeImpl {
        /** The platform computes a VALUE in Java at orchestration time
         * (compiler-output surfaces: plan text, SQL text). The result
         * enters the surrounding statement as a bound literal; the
         * database still judges every comparison over it. */
        JAVA_ROUTINE,
        /** Produces an OPAQUE orchestration value consumed later
         * (execute's result frame, executionPlan's plan handle) —
         * resolution does not enter it; consumers force it. */
        HANDLE,
        /** An EFFECTFUL Java routine at the execution boundary
         * (executeInDb's raw SQL, DDL natives, seed forms, print's
         * no-op): runs via its registered arm when evaluation reaches
         * the call — NEVER staged (effects happen at execution time,
         * in statement order, against the session). */
        EFFECT,
        /** Bound ONCE at type-check: the checker replaces the call
         * with a CARRIER node that knows its implementation
         * (TypedCsvCensus folds from the model; TypedTestDataGen
         * executes through the database) — the bind-once end-state
         * form, already achieved for this family; no runtime lookup
         * ever happens. */
        CARRIER,
        /** Establishes its OWN evaluation context for its arguments
         * (assertError's catch): staging must not enter them — the
         * function's own arm evaluates them under that context (user
         * catch 2026-08-31: pre-staging a walling call inside
         * assertError's lambda would escape the catch the engine
         * applies; witness test pins the contract). */
        CONTEXT_OWNER
    }

    /** The labeled subset (catalog leg, charter §4AG): every entry here
     * must ALSO be a registered signature; the executor's dispatch table
     * must cover exactly the JAVA_ROUTINE rows (governance-pinned). The
     * remaining silhouette arms migrate here one by one — end state is
     * ZERO function-name checks in the executor (task: full ladder
     * migration). */
    /** The engine's runtime connection lookup — an orchestration value
     * our session model answers with null (was a RAW STRING LITERAL at
     * its dispatch site; ladder census §10m). */
    public static final String CONNECTION_BY_ELEMENT =
            "meta::core::runtime::connectionByElement";

    public static final java.util.Map<String, NativeImpl> IMPLEMENTATION_KIND =
            java.util.Map.ofEntries(
                    java.util.Map.entry(PLAN_TO_STRING, NativeImpl.JAVA_ROUTINE),
                    java.util.Map.entry(PLAN_TO_STRING_WITHOUT_FORMATTING, NativeImpl.JAVA_ROUTINE),
                    // ladder migration #22: each row replaces a deleted
                    // silhouette arm (statement ladder §10m; the
                    // toSQLString rows also replaced the ad-hoc
                    // envelope-splice fold)
                    java.util.Map.entry(TO_SQL_STRING, NativeImpl.JAVA_ROUTINE),
                    java.util.Map.entry(TO_SQL_STRING_PRETTY, NativeImpl.JAVA_ROUTINE),
                    java.util.Map.entry(TO_NON_EXECUTABLE_SQL_STRING, NativeImpl.JAVA_ROUTINE),
                    // batch 82: bound ONCE at type-check to the late-bound
                    // raw-grid relation (Typer.rawGridOrSelf)
                    java.util.Map.entry(EXECUTE_IN_DB_TO_TDS, NativeImpl.CARRIER),
                    // batch 75: the SQLResult handle — consumed by the
                    // 5-argument toSQLString row above (the plan handle's
                    // twin: no rows of its own, the consumer forces it)
                    java.util.Map.entry(TO_SQL, NativeImpl.HANDLE),
                    java.util.Map.entry(EXECUTION_PLAN, NativeImpl.HANDLE),
                    java.util.Map.entry(SCAN_RELATIONS, NativeImpl.HANDLE),
                    java.util.Map.entry(SCAN_PROPERTIES, NativeImpl.HANDLE),
                    java.util.Map.entry(BUILD_PROPERTY_TREE, NativeImpl.HANDLE),
                    java.util.Map.entry(SCAN_COLUMNS, NativeImpl.HANDLE),
                    java.util.Map.entry(PREVAL, NativeImpl.HANDLE),
                    java.util.Map.entry(EXECUTE, NativeImpl.HANDLE),
                    java.util.Map.entry(EXECUTE_LEGEND_QUERY, NativeImpl.HANDLE),
                    java.util.Map.entry(ASSERT_ERROR, NativeImpl.CONTEXT_OWNER),
                    java.util.Map.entry(EXECUTE_IN_DB, NativeImpl.EFFECT),
                    java.util.Map.entry(DROP_AND_CREATE_TABLE_IN_DB, NativeImpl.EFFECT),
                    java.util.Map.entry(DROP_AND_CREATE_SCHEMA_IN_DB, NativeImpl.EFFECT),
                    java.util.Map.entry(LOAD_CSV_TO_DB_TABLE, NativeImpl.EFFECT),
                    java.util.Map.entry(SET_UP_DATA_SQLS, NativeImpl.EFFECT),
                    java.util.Map.entry(SET_UP_DATA_SQLS_V2, NativeImpl.EFFECT),
                    java.util.Map.entry(PRINT, NativeImpl.EFFECT),
                    java.util.Map.entry(PRINTLN, NativeImpl.EFFECT),
                    java.util.Map.entry(CONNECTION_BY_ELEMENT, NativeImpl.EFFECT),
                    java.util.Map.entry(GET_RELATIONAL_CSV_DATA, NativeImpl.CARRIER),
                    java.util.Map.entry(GENERATE_TEST_DATA, NativeImpl.CARRIER));

    /** Which HANDLE forces EAGERLY when consumed at a statement's value
     * position: execute's frame run IS the value; plan handles stay
     * symbolic (navigated by the plan reader). A catalog FACT — the
     * executor consults it, never a name literal. */
    public static boolean handleForcesAtValuePosition(String fqn) {
        return EXECUTE.equals(fqn) || EXECUTE_LEGEND_QUERY.equals(fqn);
    }

    /** The RAW-SQL boundary fact: executeInDb statements carry
     * corpus-authored SQL whose recording rides the replay channel
     * verbatim (the transcript-fidelity contract) — a catalog fact,
     * never an executor name literal. */
    public static boolean isRawSqlBoundary(String fqn) {
        return EXECUTE_IN_DB.equals(fqn);
    }

    /** The seed-SQL form family (both spellings) — consumers routing
     * AROUND these (the TDG carrier fold must not classify their
     * arguments) read this fact, never name pairs. */
    public static boolean isSeedSqlForm(String fqn) {
        return SET_UP_DATA_SQLS.equals(fqn)
                || SET_UP_DATA_SQLS_V2.equals(fqn);
    }

}
