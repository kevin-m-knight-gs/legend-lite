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

    public static boolean isExecuteFqn(String fqn) {
        return EXECUTE.equals(fqn);
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
    public static final String GENERATE_SEED_DATA_STRING =
            "meta::relational::testDataGeneration::generateSeedDataString";

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

    public static boolean isPlatformOwnedFunction(String fqn) {
        return DROP_AND_CREATE_TABLE_IN_DB.equals(fqn)
                || TO_REPRESENTATION.equals(fqn)
                || ASSERT_ERROR.equals(fqn)
                || ASSERT_INSTANCE_OF.equals(fqn)
                || ASSERT_FAMILY_OWNED.contains(fqn)
                || TO_CSV.equals(fqn)
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn)
                || isDdlStatementFn(fqn)
                || TO_SQL_STRING.equals(fqn)
                || TO_SQL_STRING_PRETTY.equals(fqn)
                || SET_UP_DATA_SQLS.equals(fqn)
                || EXECUTION_PLAN.equals(fqn)
                || PLAN_TO_STRING.equals(fqn)
                || PLAN_TO_STRING_WITHOUT_FORMATTING.equals(fqn)
                || CREATE_DB_CONFIG.equals(fqn)
                || GET_RELATIONAL_CSV_DATA.equals(fqn)
                || GENERATE_TEST_DATA.equals(fqn)
                || GENERATE_SEED_DATA_STRING.equals(fqn)
                || EXECUTE.equals(fqn);
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
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn);
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

    /** All K-natives: calls that EXECUTE at the K boundary and never lower. */
    public static boolean isKNative(String fqn) {
        return EXECUTE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_TABLE_IN_DB.equals(fqn)
                || DROP_AND_CREATE_SCHEMA_IN_DB.equals(fqn)
                || TO_SQL_STRING.equals(fqn)
                || TO_SQL_STRING_PRETTY.equals(fqn)
                || SET_UP_DATA_SQLS_V2.equals(fqn)
                || SET_UP_DATA_SQLS.equals(fqn)
                || EXECUTION_PLAN.equals(fqn)
                || PLAN_TO_STRING.equals(fqn)
                || PLAN_TO_STRING_WITHOUT_FORMATTING.equals(fqn)
                || EXECUTE.equals(fqn)
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

    /** The semi-structured JSON carrier. */
    public static boolean isVariant(Type t) {
        return t instanceof Type.ClassType c && c.fqn().equals(VARIANT);
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
                    java.util.Map.entry(EXECUTION_PLAN, NativeImpl.HANDLE),
                    java.util.Map.entry(PREVAL, NativeImpl.HANDLE),
                    java.util.Map.entry(EXECUTE, NativeImpl.HANDLE),
                    java.util.Map.entry(ASSERT_ERROR, NativeImpl.CONTEXT_OWNER),
                    java.util.Map.entry(EXECUTE_IN_DB, NativeImpl.EFFECT),
                    java.util.Map.entry(DROP_AND_CREATE_TABLE_IN_DB, NativeImpl.EFFECT),
                    java.util.Map.entry(DROP_AND_CREATE_SCHEMA_IN_DB, NativeImpl.EFFECT),
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
        return EXECUTE.equals(fqn);
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
