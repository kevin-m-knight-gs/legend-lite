package com.legend.model;

import com.legend.model.ClassDefinition;
import com.legend.protocol.Protocol;
import com.legend.protocol.Protocol.PClass;
import com.legend.protocol.Protocol.PProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol &rarr; {@code com.legend.model}: the boundary that keeps upstream's wire shape out of
 * legend-lite's compiler.
 *
 * <p>The parser has exactly one output — {@link Protocol} records. Everything legend-lite compiles
 * comes through here, and the compiler below is unchanged. This is deliberately the mirror of
 * {@link ProtocolEmitter}: one converts our records to their bytes, the other converts our records
 * to our compiler's model, and <b>no third party learns the wire shape</b>.
 *
 * <p>It is also stage 2's input adapter. When legend-lite's compiler replaces legend-engine's it
 * must consume {@code PureModelContextData} anyway, so this boundary is being built now and
 * exercised by legend-lite's entire test suite on every build.
 *
 * <p><b>Positions are dropped here, on purpose.</b> {@code com.legend.model} records are value
 * types whose structural equality the compiler and 111 hand-built test assertions rely on; a
 * position component would change {@code equals}. Positions live on the protocol side, which is
 * where the wire needs them.
 */
public final class FromProtocol {

    private FromProtocol() {
    }

    /** Protocol stereotypes to the model's, dropping positions the compiler does not want. */
    public static List<com.legend.model.StereotypeApplication> stereotypes(
            List<Protocol.PStereotype> ss) {
        List<com.legend.model.StereotypeApplication> out = new ArrayList<>(ss.size());
        for (Protocol.PStereotype s : ss) {
            out.add(new com.legend.model.StereotypeApplication(s.profile(), s.value()));
        }
        return out;
    }

    /** Protocol tagged values to the model's. */
    public static List<com.legend.model.TaggedValue> taggedValues(List<Protocol.PTaggedValue> ts) {
        List<com.legend.model.TaggedValue> out = new ArrayList<>(ts.size());
        for (Protocol.PTaggedValue t : ts) {
            out.add(new com.legend.model.TaggedValue(t.tag().profile(), t.tag().value(), t.value()));
        }
        return out;
    }

    /** Protocol function parameters to the model's nested shape. */
    public static java.util.List<FunctionDefinition.ParameterDefinition> toFunctionParams(
            java.util.List<com.legend.protocol.ParameterDefinition> ps) {
        java.util.List<FunctionDefinition.ParameterDefinition> out = new ArrayList<>(ps.size());
        for (com.legend.protocol.ParameterDefinition p : ps) {
            out.add(new FunctionDefinition.ParameterDefinition(p.name(), p.type(), p.multiplicity()));
        }
        return out;
    }

    /** Protocol function to the model's — UNmangled FQN; the wire name is the emitter's. */
    public static FunctionDefinition toFunctionDefinition(Protocol.PFunction f) {
        return new FunctionDefinition(
                f.qualifiedName(), f.typeParams(), f.multParams(),
                toFunctionParams(f.parameters()), f.returnType(), f.returnMultiplicity(),
                f.body(), stereotypes(f.stereotypes()), taggedValues(f.taggedValues()));
    }

    /** Protocol association to the model's — two ends plus qualified properties. */
    public static AssociationDefinition toAssociationDefinition(Protocol.PAssociation a) {
        java.util.List<AssociationDefinition.AssociationEndDefinition> ends =
                new ArrayList<>(a.properties().size());
        for (PProperty p : a.properties()) {
            ends.add(new AssociationDefinition.AssociationEndDefinition(
                    p.name(), p.type(), p.multiplicity()));
        }
        return new AssociationDefinition(a.qualifiedName(), ends.get(0), ends.get(1),
                a.derivedProperties());
    }

    /** Protocol profile to the model's — bare names; spans stay protocol-side. */
    public static ProfileDefinition toProfileDefinition(Protocol.PProfile p) {
        java.util.List<String> ss = new ArrayList<>(p.stereotypes().size());
        for (Protocol.PProfileEntry e : p.stereotypes()) {
            ss.add(e.value());
        }
        java.util.List<String> ts = new ArrayList<>(p.tags().size());
        for (Protocol.PProfileEntry e : p.tags()) {
            ts.add(e.value());
        }
        return new ProfileDefinition(p.qualifiedName(), ss, ts);
    }

    /** Protocol enumeration to the model's — value NAMES only; the compiler does not
     *  consume enum annotations. */
    public static EnumDefinition toEnumDefinition(Protocol.PEnumeration e) {
        java.util.List<String> names = new ArrayList<>(e.values().size());
        for (Protocol.PEnumValue v : e.values()) {
            names.add(v.value());
        }
        return new EnumDefinition(e.qualifiedName(), names);
    }

    /**
     * {@code PDatabase} &rarr; {@link DatabaseDefinition} — R1 of the
     * protocol-first migration (PARSER_COMPLETENESS_PLAN.md §1). Every
     * expression rides {@link RelOpFromProtocol}; this method is only the
     * structural half.
     *
     * <p>Correctness is established by {@code MigrationEquivalenceTest}, which
     * requires this to agree with the legacy parser on every database in the
     * corpus before that parser may be deleted.
     */
    public static DatabaseDefinition toDatabaseDefinition(
            com.legend.protocol.Protocol.PDatabase db) {
        List<String> includes = new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PPointer p : db.includedStores()) {
            includes.add(p.path());
        }
        List<DatabaseDefinition.SchemaDefinition> schemas = new java.util.ArrayList<>();
        List<DatabaseDefinition.TableDefinition> flatTables = new java.util.ArrayList<>();
        List<DatabaseDefinition.ViewDefinition> flatViews = new java.util.ArrayList<>();
        // The flat lists are the BARE-NAME lookup mirror, and a bare name
        // means the DEFAULT schema — `schemaB.personTable` and
        // `personTable` are different tables that share a short name. The
        // wire orders schemas the way the engine's walker appends them
        // (named first, synthetic "default" last), which is required for
        // byte parity and WRONG for lookup: it shadowed the 7-column
        // default personTable with a 3-column schemaB one and cost the
        // mapping/join family 19 corpus tests. Default schema first here;
        // `schemas` below keeps the wire's order.
        List<com.legend.protocol.Protocol.PDbSchema> lookupOrder =
                new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PDbSchema s : db.schemas()) {
            if ("default".equals(s.name())) {
                lookupOrder.add(s);
            }
        }
        for (com.legend.protocol.Protocol.PDbSchema s : db.schemas()) {
            if (!"default".equals(s.name())) {
                lookupOrder.add(s);
            }
        }
        for (com.legend.protocol.Protocol.PDbSchema s : lookupOrder) {
            List<DatabaseDefinition.TableDefinition> st = new java.util.ArrayList<>();
            List<DatabaseDefinition.ViewDefinition> sv = new java.util.ArrayList<>();
            for (com.legend.protocol.Protocol.PDbTable tb : s.tables()) {
                DatabaseDefinition.TableDefinition d = table(tb);
                st.add(d);
                flatTables.add(d);
            }
            for (com.legend.protocol.Protocol.PDbView vw : s.views()) {
                DatabaseDefinition.ViewDefinition d = view(vw, db.qualifiedName());
                sv.add(d);
                flatViews.add(d);
            }
            // EXPERIMENT (R2): the protocol wraps top-level tables in a
            // synthetic "default" schema, exactly as the engine does; the
            // legacy model records a SchemaDefinition only when the source
            // WROTE one. The protocol cannot tell the two apart.
            if (!"default".equals(s.name())) {
                schemas.add(new DatabaseDefinition.SchemaDefinition(s.name(), st, sv));
            }
        }
        List<DatabaseDefinition.JoinDefinition> joins = new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PDbJoin j : db.joins()) {
            joins.add(new DatabaseDefinition.JoinDefinition(j.name(),
                    RelOpFromProtocol.op(j.operation(), db.qualifiedName())));
        }
        // one protocol list, split by the filterType discriminator the wire
        // carries; the model keeps them in separate fields
        List<DatabaseDefinition.FilterDefinition> filters = new java.util.ArrayList<>();
        List<DatabaseDefinition.FilterDefinition> multiGrain = new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PDbFilter f : db.filters()) {
            DatabaseDefinition.FilterDefinition d =
                    new DatabaseDefinition.FilterDefinition(f.name(),
                            RelOpFromProtocol.op(f.operation(), db.qualifiedName()));
            // the wire spelling is all-lowercase "multigrain"
            // (RelationalParseTreeWalker.java:662 sets filter._type)
            if ("multigrain".equals(f.filterType())) {
                multiGrain.add(d);
            } else {
                filters.add(d);
            }
        }
        return new DatabaseDefinition(db.qualifiedName(), includes, schemas,
                flatTables, flatViews, joins, filters, multiGrain);
    }

    private static DatabaseDefinition.TableDefinition table(
            com.legend.protocol.Protocol.PDbTable t) {
        List<DatabaseDefinition.ColumnDefinition> cols = new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PDbColumn c : t.columns()) {
            cols.add(new DatabaseDefinition.ColumnDefinition(unquote(c.name()),
                    dataType(c.type()), t.primaryKey().contains(c.name()),
                    !c.nullable(), isQuoted(c.name())));
        }
        return new DatabaseDefinition.TableDefinition(unquote(t.name()), cols,
                milestoning(t.milestoning()));
    }

    /** The wire carries a LIST of milestoning entries, one per dimension;
     *  the model carries one record with a business and a processing slot. */
    private static DatabaseDefinition.TableDefinition.@com.legend.Nullable Milestoning
            milestoning(List<com.legend.protocol.Protocol.PMilestoning> ms) {
        if (ms == null || ms.isEmpty()) {
            return null;
        }
        DatabaseDefinition.TableDefinition.Milestoning.Business business = null;
        DatabaseDefinition.TableDefinition.Milestoning.Processing processing = null;
        for (com.legend.protocol.Protocol.PMilestoning m : ms) {
            switch (m) {
                case com.legend.protocol.Protocol.PBusinessMilestoning b ->
                        business = new DatabaseDefinition.TableDefinition
                                .Milestoning.Business(b.from(), b.thru(),
                                b.thruIsInclusive(), null,
                                b.infinityDate() == null ? null
                                        : b.infinityDate().value());
                case com.legend.protocol.Protocol.PBusinessSnapshotMilestoning b ->
                        business = new DatabaseDefinition.TableDefinition
                                .Milestoning.Business(null, null, false,
                                b.snapshotDate(), null);
                case com.legend.protocol.Protocol.PProcessingMilestoning pm ->
                        processing = new DatabaseDefinition.TableDefinition
                                .Milestoning.Processing(pm.in(), pm.out(),
                                pm.outIsInclusive(), null,
                                pm.infinityDate() == null ? null
                                        : pm.infinityDate().value());
                case com.legend.protocol.Protocol.PProcessingSnapshotMilestoning pm ->
                        processing = new DatabaseDefinition.TableDefinition
                                .Milestoning.Processing(null, null, false,
                                pm.snapshotDate(), null);
            }
        }
        return new DatabaseDefinition.TableDefinition.Milestoning(business,
                processing);
    }

    private static DatabaseDefinition.ViewDefinition view(
            com.legend.protocol.Protocol.PDbView v,
            @com.legend.Nullable String enclosingDb) {
        List<DatabaseDefinition.ViewDefinition.ViewColumnMapping> cms =
                new java.util.ArrayList<>();
        for (com.legend.protocol.Protocol.PViewColumnMapping cm : v.columnMappings()) {
            cms.add(new DatabaseDefinition.ViewDefinition.ViewColumnMapping(
                    cm.name(), null, RelOpFromProtocol.op(cm.operation(), enclosingDb),
                    v.primaryKey().contains(cm.name())));
        }
        List<RelationalOperation> groupBy = new java.util.ArrayList<>();
        if (v.groupBy() != null) {
            for (com.legend.protocol.Protocol.PRelOp g : v.groupBy()) {
                groupBy.add(RelOpFromProtocol.op(g, enclosingDb));
            }
        }
        return new DatabaseDefinition.ViewDefinition(v.name(), filterMapping(v.filter()),
                groupBy, v.distinct(), cms);
    }

    private static @com.legend.Nullable FilterMapping filterMapping(
            com.legend.protocol.Protocol.@com.legend.Nullable PViewFilter f) {
        if (f == null) {
            return null;
        }
        // Local vs Cross is exactly "did the source name a database?", which
        // is what R0 taught the protocol record to remember
        FilterPointer ptr = f.db() == null
                ? new FilterPointer.Local(f.name())
                : new FilterPointer.Cross(f.db(), f.name());
        if (f.joins().isEmpty()) {
            return new FilterMapping.Direct(ptr);
        }
        List<JoinChainElement> chain = new java.util.ArrayList<>();
        String sourceDb = null;
        for (com.legend.protocol.Protocol.PJoinPtr jp : f.joins()) {
            if (sourceDb == null) {
                sourceDb = jp.db();
            }
            chain.add(new JoinChainElement(jp.name(),
                    jp.joinType() == null ? null : JoinType.fromIdentifier(jp.joinType()),
                    jp.db(), false));
        }
        return new FilterMapping.JoinMediated(sourceDb, chain, ptr, null);
    }

    /** The wire keeps a quoted identifier's quotes; the model keeps the bare
     *  name and remembers that it was quoted. */
    private static String unquote(String name) {
        return isQuoted(name) ? name.substring(1, name.length() - 1) : name;
    }

    private static boolean isQuoted(String name) {
        return name.length() >= 2 && name.charAt(0) == '"'
                && name.charAt(name.length() - 1) == '"';
    }

    private static RelationalDataType dataType(
            com.legend.protocol.Protocol.PDbType t) {
        int size = t.size() == null ? 0 : t.size().intValue();
        return switch (t.kind()) {
            case "Boolean" -> new RelationalDataType.Bool();
            case "BigInt" -> new RelationalDataType.BigInt();
            case "SmallInt" -> new RelationalDataType.SmallInt();
            case "TinyInt" -> new RelationalDataType.TinyInt();
            case "Integer" -> new RelationalDataType.Integer_();
            case "Float" -> new RelationalDataType.Float_();
            case "Double" -> new RelationalDataType.Double_();
            case "Real" -> new RelationalDataType.Real();
            case "Bit" -> new RelationalDataType.Bit();
            case "Timestamp" -> new RelationalDataType.Timestamp();
            case "Date" -> new RelationalDataType.Date_();
            case "Distinct" -> new RelationalDataType.Distinct();
            case "Other" -> new RelationalDataType.Other();
            case "SemiStructured" -> new RelationalDataType.SemiStructured();
            // KNOWN MODEL CONFLATION, carried over unchanged from the legacy
            // parser (RelationalDataType.java:140): engine's WIRE has a
            // distinct Json type (RelationalParseTreeWalker.java:492) and our
            // MODEL does not, so JSON columns land as SemiStructured. The
            // protocol keeps them apart, so byte parity is unaffected; the
            // model-side split is a separate leg (PARSER_COMPLETENESS_PLAN
            // §3.1) and needs arms in all five RelationalDataType switches.
            case "Json" -> new RelationalDataType.SemiStructured();
            // a BARE VARCHAR is unbounded, not zero-width — the model's
            // own convention (RelationalDataType.fromName). Engine requires
            // the size (walker:296), so this only covers legend-lite's
            // documented superset; PlanText renders the size verbatim.
            case "Varchar" -> new RelationalDataType.Varchar(
                    t.size() == null ? java.lang.Integer.MAX_VALUE : size);
            case "Char" -> new RelationalDataType.Char_(size);
            case "Binary" -> new RelationalDataType.Binary(size);
            case "Varbinary" -> new RelationalDataType.Varbinary(size);
            case "Decimal" -> new RelationalDataType.Decimal(
                    t.precision() == null ? 0 : t.precision().intValue(),
                    t.scale() == null ? 0 : t.scale().intValue());
            // Numeric was reachable from the protocol parser and had no
            // arm here — a NUMERIC column crashed the transform. Same
            // class of hole as BOOLEAN, same cause: nothing built the
            // model from protocol, so nothing ever asked.
            case "Numeric" -> new RelationalDataType.Numeric(
                    t.precision() == null ? 0 : t.precision().intValue(),
                    t.scale() == null ? 0 : t.scale().intValue());
            default -> throw new UnsupportedOperationException(
                    "no model data type for protocol kind '" + t.kind() + "'");
        };
    }

    public static ClassDefinition toClassDefinition(PClass c) {
        List<ClassDefinition.PropertyDefinition> props = new ArrayList<>(c.properties().size());
        for (PProperty p : c.properties()) {
            props.add(new ClassDefinition.PropertyDefinition(
                    p.name(), p.type(), p.multiplicity(),
                    stereotypes(p.stereotypes()), taggedValues(p.taggedValues())));
        }
        List<com.legend.protocol.TypeExpression> supers = new ArrayList<>(c.superTypes().size());
        for (Protocol.PSuperType st : c.superTypes()) {
            supers.add(st.type());
        }
        return new ClassDefinition(c.qualifiedName(), c.typeParams(), supers, props,
                c.derivedProperties(), c.constraints(),
                stereotypes(c.stereotypes()), taggedValues(c.taggedValues()),
                c.isNative());
    }

    /** A parsed connection whose shape the model cannot represent yet (e.g.
     *  an un-censused database type). The parser converts these into
     *  positioned {@code ParseException}s at the element site. */
    public static final class UnsupportedConnectionShape extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String reason;

        public UnsupportedConnectionShape(String message) {
            super(message);
            this.reason = message;
        }

        public String reason() {
            return reason;
        }
    }

    /** {@code ###Connection} element to its model form — one of the three
     *  packageable connection kinds. Positions drop here like everywhere
     *  else in this class. */
    public static PackageableElement toConnectionElement(Protocol.PConnection c) {
        return switch (c.value()) {
            case Protocol.PJsonModelConnection j -> new ModelConnectionDefinition(
                    c.qualifiedName(), ModelConnectionDefinition.Kind.JSON,
                    j.className(), j.url());
            case Protocol.PXmlModelConnection x -> new ModelConnectionDefinition(
                    c.qualifiedName(), ModelConnectionDefinition.Kind.XML,
                    x.className(), x.url());
            case Protocol.PModelChainConnection m ->
                    new ModelChainConnectionDefinition(c.qualifiedName(),
                            m.mappings());
            case Protocol.PRelationalDatabaseConnection r ->
                    toRelationalConnection(c.qualifiedName(), r);
            case Protocol.PConnectionPointer p -> throw new IllegalStateException(
                    "a connection pointer cannot be a standalone element: "
                            + c.qualifiedName());
        };
    }

    /**
     * {@code Runtime | SingleConnectionRuntime} to its model form. Pointer
     * bindings keep declaration order and allow SEVERAL connections per
     * store (engine semantics — the old model twin's {@code Map} refused
     * duplicates). Embedded islands split three ways: Json islands feed the
     * cross-bake list (execution parity with the twin); every other flavor
     * hoists to an anonymous element named with the reserved {@code $}
     * sigil, registered by {@code ModelBuilder.ingestRuntime} and BOUND to
     * its store, so dialect selection sees an inline relational
     * connection's database type.
     */
    public static RuntimeDefinition toRuntimeElement(Protocol.PRuntime r) {
        String qn = r.qualifiedName();
        java.util.List<String> mappings = new ArrayList<>(r.mappings().size());
        for (Protocol.PPointer p : r.mappings()) {
            mappings.add(p.path());
        }
        java.util.Map<String, java.util.List<String>> bindings =
                new java.util.LinkedHashMap<>();
        java.util.List<JsonModelConnection> jsonConnections = new ArrayList<>();
        java.util.List<PackageableElement> inline = new ArrayList<>();
        for (Protocol.PStoreConnections sc : r.connections()) {
            String store = sc.store().path();
            for (Protocol.PIdentifiedConnection ic : sc.storeConnections()) {
                switch (ic.connection()) {
                    case Protocol.PConnectionPointer p -> bindings
                            .computeIfAbsent(store, k -> new ArrayList<>())
                            .add(p.connection());
                    case Protocol.PJsonModelConnection j -> jsonConnections
                            .add(new JsonModelConnection(j.className(), j.url()));
                    case Protocol.PXmlModelConnection x -> {
                        String synth = qn + "$" + store + "$" + ic.id();
                        inline.add(new ModelConnectionDefinition(synth,
                                ModelConnectionDefinition.Kind.XML,
                                x.className(), x.url()));
                        bindings.computeIfAbsent(store, k -> new ArrayList<>())
                                .add(synth);
                    }
                    case Protocol.PModelChainConnection m -> {
                        String synth = qn + "$" + store + "$" + ic.id();
                        inline.add(new ModelChainConnectionDefinition(synth,
                                m.mappings()));
                        bindings.computeIfAbsent(store, k -> new ArrayList<>())
                                .add(synth);
                    }
                    case Protocol.PRelationalDatabaseConnection rc -> {
                        String synth = qn + "$" + store + "$" + ic.id();
                        ConnectionDefinition cd =
                                toRelationalConnection(synth, rc);
                        if (cd.storeName() == null) {
                            // an inline island may omit store: — the OUTER
                            // binding names it
                            cd = new ConnectionDefinition(synth, store,
                                    cd.databaseType(), cd.specification(),
                                    cd.authentication());
                        }
                        inline.add(cd);
                        bindings.computeIfAbsent(store, k -> new ArrayList<>())
                                .add(synth);
                    }
                }
            }
        }
        // connectionStores: [conn: [stores...]] — the REVERSE direction; a
        // SingleConnectionRuntime's storeless entry binds nothing
        for (Protocol.PConnectionStores cs : r.connectionStores()) {
            if (!(cs.connectionPointer()
                    instanceof Protocol.PConnectionPointer p)) {
                throw new IllegalStateException(
                        "connectionStores entries are pointers by grammar: "
                                + qn);
            }
            for (Protocol.PStorePointer sp : cs.storePointers()) {
                bindings.computeIfAbsent(sp.path(), k -> new ArrayList<>())
                        .add(p.connection());
            }
        }
        return new RuntimeDefinition(qn, mappings, bindings, jsonConnections,
                inline);
    }

    /** A {@code ###Service} section element (Service or
     *  ExecutionEnvironment) to its model form. Envelope decorations ride
     *  the protocol record only — the model's service record predates them
     *  and nothing compiles them yet. */
    public static PackageableElement toServiceSectionElement(Protocol.Element e) {
        return switch (e) {
            case Protocol.PService s -> toServiceDefinition(s);
            case Protocol.PExecutionEnvironment ee ->
                    new ExecutionEnvironmentDefinition(ee.qualifiedName(),
                            keyedExecutions(ee.executions()));
            default -> throw new IllegalStateException(
                    "not a service-section element: " + e.getClass());
        };
    }

    private static ServiceDefinition toServiceDefinition(Protocol.PService s) {
        String pattern = s.pattern() != null ? s.pattern() : "/";
        return switch (s.execution()) {
            case Protocol.PSingleExecution single -> new ServiceDefinition(
                    s.qualifiedName(), pattern, single.query(),
                    s.documentation(), single.mapping(), single.runtime(),
                    s.testSuitesSource(), s.owners(),
                    s.autoActivateUpdates(), null, s.testSource());
            case Protocol.PMultiExecution multi -> new ServiceDefinition(
                    s.qualifiedName(), pattern, multi.query(),
                    s.documentation(), null, null, s.testSuitesSource(),
                    s.owners(), s.autoActivateUpdates(),
                    new ServiceDefinition.MultiExecution(multi.executionKey(),
                            keyedExecutions(multi.executions())),
                    s.testSource());
        };
    }

    private static java.util.List<ServiceDefinition.KeyedExecution>
            keyedExecutions(java.util.List<Protocol.PKeyedExecution> ks) {
        java.util.List<ServiceDefinition.KeyedExecution> out =
                new ArrayList<>(ks.size());
        for (Protocol.PKeyedExecution k : ks) {
            out.add(new ServiceDefinition.KeyedExecution(k.keyValue(),
                    k.mapping(), k.runtime()));
        }
        return out;
    }

    private static ConnectionDefinition toRelationalConnection(String qualifiedName,
            Protocol.PRelationalDatabaseConnection r) {
        ConnectionDefinition.DatabaseType type;
        try {
            type = ConnectionDefinition.DatabaseType.valueOf(r.databaseType());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedConnectionShape("unknown database type '"
                    + r.databaseType() + "' (expected one of "
                    + java.util.Arrays.toString(
                            ConnectionDefinition.DatabaseType.values()) + ")");
        }
        ConnectionSpecification spec = switch (r.datasourceSpecification()) {
            case Protocol.PH2Local h -> new ConnectionSpecification.LocalH2(
                    h.url(), h.testDataSetupCsv(), h.testDataSetupSqls());
            // engine's name: IS the database name (wire field databaseName)
            case Protocol.PStaticSpec s -> new ConnectionSpecification
                    .StaticDatasource(s.host(), (int) s.port(), s.databaseName());
            case Protocol.PInMemory im -> new ConnectionSpecification.InMemory();
            case Protocol.PLocalFile lf ->
                    new ConnectionSpecification.LocalFile(lf.path());
            case Protocol.PSnowflakeSpec s -> new ConnectionSpecification
                    .Snowflake(s.databaseName(), s.accountName(),
                            s.warehouseName(), s.region(), s.accountType(),
                            s.cloudType(), s.enableQueryTags(),
                            s.organization(), s.role());
            case Protocol.PSpannerSpec s -> new ConnectionSpecification
                    .Spanner(s.projectId(), s.instanceId(), s.databaseId());
            case Protocol.PDatabricksSpec s -> new ConnectionSpecification
                    .Databricks(s.hostname(), s.port(), s.protocol(),
                            s.httpPath());
            case Protocol.PBigQuerySpec s -> new ConnectionSpecification
                    .BigQuery(s.projectId(), s.defaultDataset());
        };
        AuthenticationSpec auth = switch (r.authenticationStrategy()) {
            case Protocol.PH2Default d -> new AuthenticationSpec.DefaultH2();
            case Protocol.PTestAuth t -> new AuthenticationSpec.TestAuth();
            case Protocol.PDelegatedKerberos k ->
                    new AuthenticationSpec.DelegatedKerberos(k.serverPrincipal());
            case Protocol.PUserNamePassword u ->
                    new AuthenticationSpec.VaultUserNamePassword(
                            u.baseVaultReference(), u.userNameVaultReference(),
                            u.passwordVaultReference());
            case Protocol.PNoAuth n -> new AuthenticationSpec.NoAuth();
            case Protocol.PPlainUserPassword p ->
                    new AuthenticationSpec.UsernamePassword(p.username(),
                            p.passwordVaultRef());
            case Protocol.PSnowflakePublic s ->
                    new AuthenticationSpec.SnowflakePublic(s.publicUserName(),
                            s.privateKeyVaultReference(),
                            s.passPhraseVaultReference());
            case Protocol.PGCPApplicationDefaultCredentials g ->
                    new AuthenticationSpec.GCPApplicationDefaultCredentials();
            case Protocol.PApiToken a ->
                    new AuthenticationSpec.ApiToken(a.apiToken());
            case Protocol.PMiddleTierUserNamePassword m ->
                    new AuthenticationSpec.MiddleTierUserNamePassword(
                            m.vaultReference());
        };
        // quoteIdentifiers rides the PROTOCOL record only (wire parity);
        // lite's SQL renderers decide quoting themselves, so the model
        // deliberately does not carry it
        return new ConnectionDefinition(qualifiedName, r.element(), type, spec,
                auth);
    }

}
