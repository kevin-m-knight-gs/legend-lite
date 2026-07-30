// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedFold;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedNewInstance;
import com.legend.compiler.spec.typed.TypedCopyInstance;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;
import com.legend.model.DatabaseDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ORCHESTRATION-VALUE evaluation channel: expressions over
 * {@code meta::relational::metamodel::execute} values (ResultSet/Row —
 * JDBC metadata grids, raw-SQL results) evaluate HOST-SIDE, never through
 * SQL lowering. The "DB executes" tenet governs QUERY values; these are
 * driver-plumbing values the engine also evaluates host-side (the
 * legend-pure interpreted natives). Small recursive evaluator; every
 * unhandled shape is LOUD and names itself — arms grow as corpus walls
 * demand them.
 */
public final class HostEval {

    private HostEval() {
    }

    /** The engine's SQLNull cell marker — positional null. */
    public static final Object SQL_NULL = new Object() {
        @Override
        public String toString() {
            return "SQLNull";
        }
    };

    /** One metadata row in the host channel. */
    public record HostRow(DbMetaData.HostResultSet parent,
            List<Object> values) {
        @Override
        public String toString() {
            return String.valueOf(values);
        }
    }

    /** Does this expression READ a fetchDb/executeInDb grid? fetchDb
     * anywhere in the tree routes (its only corpus shapes are grid
     * reads); executeInDb routes ONLY when the expression's PRIMARY
     * SOURCE CHAIN bottoms out at the call — ordinary setups carry
     * executeInDb deep inside spliced trees that the SQL pipeline owns
     * (routing on containment collapsed modelJoin/testDataGeneration:
     * this predicate is the fix's pin). The ROOT-position executeInDb
     * SETUP arm dispatches first in executeTyped. */
    public static boolean wantsHostEval(TypedSpec root) {
        if (chainBottom(root) instanceof TypedNativeCall b
                && (PlatformTypes.EXECUTE_IN_DB
                        .equals(b.callee().qualifiedName())
                        || PlatformTypes.isStoreNavFn(
                                b.callee().qualifiedName()))) {
            return true;
        }
        return containsFetchDb(root);
    }

    private static boolean containsFetchDb(TypedSpec root) {
        if (root instanceof TypedNativeCall nc
                && PlatformTypes.isFetchDbFn(nc.callee().qualifiedName())) {
            return true;
        }
        for (TypedSpec c : root.children()) {
            if (containsFetchDb(c)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> READ_CHAIN_FNS =
            java.util.Set.of(
                    "meta::pure::functions::collection::fold",
                    "meta::pure::functions::collection::map",
                    "meta::pure::functions::collection::concatenate",
                    "meta::pure::functions::collection::at",
                    "meta::pure::functions::collection::first",
                    "meta::pure::functions::collection::size",
                    "meta::pure::functions::collection::indexOf",
                    "meta::pure::functions::multiplicity::toOne",
                    "meta::pure::functions::string::toString");

    /** Walk the primary source chain (property access sources, fold/map
     * sources, READ-shaped collection-native first args) to the
     * expression's root. */
    private static TypedSpec chainBottom(TypedSpec n) {
        while (true) {
            switch (n) {
                case TypedPropertyAccess pa -> n = pa.source();
                case TypedFold f -> n = f.source();
                case TypedMap m -> n = m.source();
                case TypedNativeCall nc -> {
                    String fqn = nc.callee().qualifiedName();
                    if (PlatformTypes.EXECUTE_IN_DB.equals(fqn)
                            || PlatformTypes.isFetchDbFn(fqn)
                            || PlatformTypes.isStoreNavFn(fqn)) {
                        return nc;
                    }
                    // walk ONLY through the READ-shaped natives this
                    // evaluator implements — an arbitrary call's first
                    // argument is not a source chain (println(executeInDb)
                    // is a SETUP statement the print arm owns; stealing
                    // it rerouted the effect off the ambient connection)
                    if (nc.args().isEmpty() || !READ_CHAIN_FNS.contains(fqn)) {
                        return nc;
                    }
                    n = nc.args().get(0);
                }
                default -> {
                    return n;
                }
            }
        }
    }

    /** One metamodel schema in the host channel: the include-closure
     * MERGED table set (functions.pure:227-235). */
    public record HostSchema(String name, List<DatabaseDefinition.TableDefinition> tables) {
    }

    /** One metamodel table in the host channel. */
    public record HostTable(DatabaseDefinition.TableDefinition def) {
    }

    /** A CONSTRUCTED pure instance in the host channel (^Class(...)):
     * class tag + property values in declaration order. */
    public record HostInstance(String classFqn,
            java.util.LinkedHashMap<String, Object> properties) {
        @Override
        public String toString() {
            return "^" + classFqn.substring(classFqn.lastIndexOf(':') + 1)
                    + properties;
        }
    }

    /** STRUCTURAL equality over host values — the pure instance-graph
     * assertEquals semantics (debugPrint goldens compare trees). */
    public static boolean hostEquals(Object a, Object b) {
        if (a instanceof HostInstance x && b instanceof HostInstance y) {
            if (!x.classFqn().equals(y.classFqn())
                    || !x.properties().keySet()
                            .equals(y.properties().keySet())) {
                return false;
            }
            for (String k : x.properties().keySet()) {
                if (!hostEquals(x.properties().get(k),
                        y.properties().get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!hostEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number na && b instanceof Number nb
                && !(a instanceof Double || b instanceof Double)) {
            return na.longValue() == nb.longValue();
        }
        return java.util.Objects.equals(a, b);
    }

    /** The runtime class tag of a host value (exact-FQN world). */
    private static String hostTypeFqn(Object v) {
        return switch (v) {
            case HostInstance hi -> hi.classFqn();
            case HostRow ignored ->
                    "meta::relational::metamodel::execute::Row";
            case DbMetaData.HostResultSet ignored ->
                    "meta::relational::metamodel::execute::ResultSet";
            case HostSchema ignored -> "meta::relational::metamodel::Schema";
            case HostTable ignored ->
                    "meta::relational::metamodel::relation::Table";
            case String ignored -> "meta::pure::metamodel::type::String";
            case Boolean ignored -> "meta::pure::metamodel::type::Boolean";
            case Double ignored -> "meta::pure::metamodel::type::Float";
            case Number ignored -> "meta::pure::metamodel::type::Integer";
            default -> throw new NotImplementedException(
                    "host-eval: no runtime type tag for "
                            + (v == null ? "null" : v.getClass().getSimpleName()));
        };
    }

    /** Runtime conformance: exact, declared-supertype (via the model's
     * class hierarchy incl. native classes), or Any. */
    private static boolean hostConforms(Object v, String typeFqn) {
        if ("meta::pure::metamodel::type::Any".equals(typeFqn)) {
            return true;
        }
        String actual = hostTypeFqn(v);
        if (actual.equals(typeFqn)) {
            return true;
        }
        com.legend.compiler.element.ModelContext ctx = CTX.get();
        return ctx != null && ctx.isSubtype(actual, typeFqn);
    }

    private static final ThreadLocal<com.legend.compiler.element.ModelContext>
            CTX = new ThreadLocal<>();

    /** Whole-expression entry: host value wrapped as an ExecutionResult. */
    public static ExecutionResult evalToResult(TypedSpec root,
            com.legend.compiler.element.ModelContext ctx)
            throws java.sql.SQLException {
        CTX.set(ctx);
        try {
            return evalToResult(root);
        } finally {
            CTX.remove();
        }
    }

    /** Whole-expression entry: host value wrapped as an ExecutionResult. */
    public static ExecutionResult evalToResult(TypedSpec root)
            throws java.sql.SQLException {
        Object hv = eval(root, new LinkedHashMap<>());
        if (hv instanceof List<?> hl) {
            return new ExecutionResult.Collection(
                    new ArrayList<>(hl), root.info().type());
        }
        return new ExecutionResult.Scalar(hv, root.info().type());
    }

    private static Object eval(TypedSpec node, Map<String, Object> scope)
            throws java.sql.SQLException {
        switch (node) {
            case TypedNativeCall nc -> {
                String fqn = nc.callee().qualifiedName();
                if (PlatformTypes.isFetchDbFn(fqn)) {
                    return fetch(nc, scope);
                }
                if (PlatformTypes.STORE_SCHEMA_NAV.equals(fqn)) {
                    return schemaNav(nc, scope);
                }
                if (PlatformTypes.STORE_TABLE_NAV.equals(fqn)) {
                    List<Object> sv = asList(eval(nc.args().get(0), scope));
                    Object nm = asList(eval(nc.args().get(1), scope)).get(0);
                    if (sv.isEmpty()) {
                        return List.of();
                    }
                    if (!(sv.get(0) instanceof HostSchema hs)) {
                        throw new NotImplementedException(
                                "host-eval: table() over "
                                        + sv.get(0).getClass().getSimpleName());
                    }
                    for (DatabaseDefinition.TableDefinition t : hs.tables()) {
                        if (t.name().equals(nm)) {
                            return new HostTable(t);
                        }
                    }
                    return List.of();
                }
                if (PlatformTypes.EXECUTE_IN_DB.equals(fqn)) {
                    // the READ path: run the query over the replayed H2
                    // second target (engine-parity column naming)
                    Object sqlv = eval(nc.args().get(0), scope);
                    return DbMetaData.query(String.valueOf(
                            asList(sqlv).get(0)), replayStream());
                }
                switch (fqn) {
                    case "meta::pure::functions::collection::fold" -> {
                        List<Object> src = asList(eval(nc.args().get(0), scope));
                        TypedLambda fn = (TypedLambda) nc.args().get(1);
                        Object acc = eval(nc.args().get(2), scope);
                        for (Object x : src) {
                            Map<String, Object> s2 = new LinkedHashMap<>(scope);
                            s2.put(fn.parameters().get(0), x);
                            s2.put(fn.parameters().get(1), acc);
                            acc = eval(fn.body().get(fn.body().size() - 1), s2);
                        }
                        return acc;
                    }
                    case "meta::pure::functions::collection::map" -> {
                        List<Object> src = asList(eval(nc.args().get(0), scope));
                        TypedLambda fn = (TypedLambda) nc.args().get(1);
                        List<Object> out = new ArrayList<>(src.size());
                        for (Object x : src) {
                            Map<String, Object> s2 = new LinkedHashMap<>(scope);
                            s2.put(fn.parameters().get(0), x);
                            Object v = eval(fn.body().get(fn.body().size() - 1), s2);
                            out.addAll(asList(v));   // pure map flattens
                        }
                        return out;
                    }
                    case "meta::pure::functions::collection::concatenate" -> {
                        List<Object> out = new ArrayList<>();
                        out.addAll(asList(eval(nc.args().get(0), scope)));
                        out.addAll(asList(eval(nc.args().get(1), scope)));
                        return out;
                    }
                    case "meta::pure::functions::collection::at" -> {
                        List<Object> src = asList(eval(nc.args().get(0), scope));
                        int i = ((Number) eval(nc.args().get(1), scope)).intValue();
                        return src.get(i);
                    }
                    case "meta::pure::functions::collection::first" -> {
                        List<Object> src = asList(eval(nc.args().get(0), scope));
                        return src.isEmpty() ? List.of() : src.get(0);
                    }
                    case "meta::pure::functions::collection::size" -> {
                        return (long) asList(eval(nc.args().get(0), scope)).size();
                    }
                    case "meta::pure::functions::collection::indexOf" -> {
                        List<Object> src = asList(eval(nc.args().get(0), scope));
                        Object v = eval(nc.args().get(1), scope);
                        for (int i = 0; i < src.size(); i++) {
                            if (java.util.Objects.equals(src.get(i), v)) {
                                return (long) i;
                            }
                        }
                        return -1L;
                    }
                    case "meta::pure::functions::meta::instanceOf" -> {
                        Object v = asList(eval(nc.args().get(0), scope)).get(0);
                        String typeFqn = typeRefFqn(nc.args().get(1));
                        return hostConforms(v, typeFqn);
                    }
                    case "meta::pure::functions::string::toString" -> {
                        Object v = eval(nc.args().get(0), scope);
                        return String.valueOf(v);
                    }
                    case "meta::pure::functions::multiplicity::toOne" -> {
                        Object v = eval(nc.args().get(0), scope);
                        List<Object> l = asList(v);
                        if (l.size() != 1) {
                            throw new IllegalStateException(
                                    "toOne over " + l.size() + " values");
                        }
                        return l.get(0);
                    }
                    default -> throw new NotImplementedException(
                            "host-eval: native '" + fqn + "' has no host arm");
                }
            }
            case TypedMap m -> {
                List<Object> src = asList(eval(m.source(), scope));
                TypedLambda fn = m.mapper();
                List<Object> out = new ArrayList<>(src.size());
                for (Object x : src) {
                    Map<String, Object> s2 = new LinkedHashMap<>(scope);
                    s2.put(fn.parameters().get(0), x);
                    out.addAll(asList(
                            eval(fn.body().get(fn.body().size() - 1), s2)));
                }
                return out;
            }
            case TypedMatchRuntime mr -> {
                Object in = eval(mr.input(), scope);
                Object inOne = asList(in).size() == 1 ? asList(in).get(0) : in;
                for (TypedMatchRuntime.Arm arm : mr.arms()) {
                    if (hostConforms(inOne, arm.typeFqn())) {
                        Map<String, Object> s2 = new LinkedHashMap<>(scope);
                        s2.put(arm.param(), inOne);
                        if (mr.extraParam().isPresent()) {
                            s2.put(mr.extraParam().get(),
                                    eval(mr.extra().get(), scope));
                        }
                        return eval(arm.body(), s2);
                    }
                }
                throw new IllegalStateException("host-eval: match — no arm"
                        + " accepts runtime type " + hostTypeFqn(inOne));
            }
            case TypedNewInstance ni -> {
                java.util.LinkedHashMap<String, Object> props =
                        new java.util.LinkedHashMap<>();
                for (Map.Entry<String, TypedSpec> e
                        : ni.properties().entrySet()) {
                    props.put(e.getKey(), eval(e.getValue(), scope));
                }
                return new HostInstance(ni.classFqn(), props);
            }
            case TypedCopyInstance ci -> {
                Object src = eval(ci.source(), scope);
                if (!(asList(src).get(0) instanceof HostInstance hi)) {
                    throw new NotImplementedException(
                            "host-eval: ^$copy over non-instance "
                                    + hostTypeFqn(asList(src).get(0)));
                }
                java.util.LinkedHashMap<String, Object> props =
                        new java.util.LinkedHashMap<>(hi.properties());
                for (Map.Entry<String, TypedSpec> e
                        : ci.overrides().entrySet()) {
                    props.put(e.getKey(), eval(e.getValue(), scope));
                }
                return new HostInstance(hi.classFqn(), props);
            }
            case TypedCast tc -> {
                // a HOST cast is an assertion, not a conversion
                return eval(tc.source(), scope);
            }
            case TypedFold f -> {
                List<Object> src = asList(eval(f.source(), scope));
                TypedLambda fn = f.reducer();
                Object acc = eval(f.init(), scope);
                for (Object x : src) {
                    Map<String, Object> s2 = new LinkedHashMap<>(scope);
                    s2.put(fn.parameters().get(0), x);
                    s2.put(fn.parameters().get(1), acc);
                    acc = eval(fn.body().get(fn.body().size() - 1), s2);
                }
                return acc;
            }
            case TypedPropertyAccess pa -> {
                Object src = eval(pa.source(), scope);
                return property(src, pa.property());
            }
            case TypedVariable v -> {
                if (!scope.containsKey(v.name())) {
                    throw new NotImplementedException(
                            "host-eval: unbound variable '$" + v.name() + "'");
                }
                return scope.get(v.name());
            }
            case TypedCString s -> {
                return s.value();
            }
            case TypedCInteger i -> {
                return i.value();
            }
            case TypedCollection c -> {
                List<Object> out = new ArrayList<>(c.elements().size());
                for (TypedSpec e : c.elements()) {
                    out.addAll(asList(eval(e, scope)));
                }
                return out;
            }
            default -> throw new NotImplementedException(
                    "host-eval: node " + node.getClass().getSimpleName()
                            + " has no host arm");
        }
    }

    private static Object property(Object src, String prop) {
        // pure property access maps over collections
        if (src instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) {
                Object v = property(x, prop);
                out.addAll(asList(v));
            }
            return out;
        }
        if (src instanceof DbMetaData.HostResultSet rs) {
            return switch (prop) {
                case "rows" -> {
                    List<Object> rows = new ArrayList<>(rs.rows().size());
                    for (List<Object> r : rs.rows()) {
                        rows.add(new HostRow(rs, r));
                    }
                    yield rows;
                }
                case "columnNames" -> new ArrayList<Object>(rs.columnNames());
                default -> throw new NotImplementedException(
                        "host-eval: ResultSet property '" + prop + "'");
            };
        }
        if (src instanceof HostRow row) {
            return switch (prop) {
                case "values" -> {
                    // NULL cells stay POSITIONAL as SQLNull (the engine
                    // ResultSet convention — at(N) indexing depends on it)
                    List<Object> out = new ArrayList<>(row.values().size());
                    for (Object v : row.values()) {
                        out.add(v == null ? SQL_NULL : v);
                    }
                    yield out;
                }
                case "parent" -> row.parent();
                default -> throw new NotImplementedException(
                        "host-eval: Row property '" + prop + "'");
            };
        }
        throw new NotImplementedException("host-eval: property '" + prop
                + "' over " + (src == null ? "null"
                        : src.getClass().getSimpleName()));
    }

    private static DbMetaData.HostResultSet fetch(TypedNativeCall nc,
            Map<String, Object> scope) throws java.sql.SQLException {
        String fqn = nc.callee().qualifiedName();
        // arg 0 is the connection — an orchestration handle, never
        // evaluated (the H2 second target IS the metadata connection)
        String a1 = patternArg(nc, 1, scope);
        String a2 = nc.args().size() > 2 ? patternArg(nc, 2, scope) : null;
        String a3 = nc.args().size() > 3 ? patternArg(nc, 3, scope) : null;
        // replay order: schema creates (prerequisites for the main
        // stream's schema-qualified DDL), then the corpus's own
        // statements, then constraint post-fixes (PK alters)
        List<String> replay = replayStream();
        return switch (PlatformTypes.fetchDbKind(fqn)) {
            case SCHEMAS -> DbMetaData.fetch(fqn, a1, null, null, replay);
            case TABLES, PRIMARY_KEYS -> DbMetaData.fetch(fqn, a1, a2, null,
                    replay);
            case COLUMNS -> DbMetaData.fetch(fqn, a1, a2, a3, replay);
        };
    }

    /** schema(db, name): the include-closure schema lookup with MERGED
     * tables (functions.pure:227-235) over the compiled store model;
     * top-level tables are the 'default' schema. */
    private static Object schemaNav(TypedNativeCall nc,
            Map<String, Object> scope) throws java.sql.SQLException {
        if (!(nc.args().get(0) instanceof
                com.legend.compiler.spec.typed.TypedPackageableRef db)) {
            throw new NotImplementedException(
                    "host-eval: schema() requires a database reference, got "
                            + nc.args().get(0).getClass().getSimpleName());
        }
        Object nm = eval(nc.args().get(1), scope);
        String name = String.valueOf(asList(nm).get(0));
        com.legend.compiler.element.ModelContext ctx = CTX.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "host-eval: no ModelContext bound for store navigation");
        }
        List<DatabaseDefinition.TableDefinition> tables = new ArrayList<>();
        boolean[] found = {false};
        collectSchema(ctx, db.fullPath(), name, tables, found,
                new java.util.LinkedHashSet<>());
        return found[0] ? new HostSchema(name, tables) : List.of();
    }

    private static void collectSchema(
            com.legend.compiler.element.ModelContext ctx, String dbFqn,
            String schemaName, List<DatabaseDefinition.TableDefinition> out,
            boolean[] found, java.util.Set<String> seen) {
        if (!seen.add(dbFqn)) {
            return;
        }
        var dbo = ctx.findDatabase(dbFqn);
        if (dbo.isEmpty()) {
            return;
        }
        DatabaseDefinition db = dbo.get();
        for (String inc : db.includes()) {
            collectSchema(ctx, inc, schemaName, out, found, seen);
        }
        if ("default".equals(schemaName)) {
            found[0] = true;
            out.addAll(db.tables());
            return;
        }
        for (DatabaseDefinition.SchemaDefinition sd : db.schemas()) {
            if (sd.name().equals(schemaName)) {
                found[0] = true;
                out.addAll(sd.tables());
            }
        }
    }

    /** The H2 second target's replay stream — schema creates
     * (prerequisites for the main stream's schema-qualified DDL), then
     * the corpus's own statements, then constraint post-fixes. */
    private static List<String> replayStream() {
        List<String> replay = new ArrayList<>();
        List<String> meta = RawSqlBoundary.metaRecording() == null
                ? List.of() : RawSqlBoundary.metaRecording();
        for (String m : meta) {
            if (m.regionMatches(true, 0, "create schema", 0, 13)) {
                replay.add(m);
            }
        }
        if (RawSqlBoundary.recording() != null) {
            replay.addAll(RawSqlBoundary.recording());
        }
        for (String m : meta) {
            if (!m.regionMatches(true, 0, "create schema", 0, 13)) {
                replay.add(m);
            }
        }
        return replay;
    }

    /** The class FQN named by a TYPE-reference argument (instanceOf's
     * second parameter). */
    private static String typeRefFqn(TypedSpec t) {
        if (t instanceof com.legend.compiler.spec.typed.TypedPackageableRef pr) {
            return pr.fullPath();
        }
        if (t.info().type() instanceof
                com.legend.compiler.element.type.Type.ClassType ct) {
            return ct.fqn();
        }
        throw new NotImplementedException(
                "host-eval: instanceOf type argument "
                        + t.getClass().getSimpleName());
    }

    /** A String[0..1] pattern argument: literal, empty collection (null =
     * match all), or an in-scope binding. */
    private static String patternArg(TypedNativeCall nc, int i,
            Map<String, Object> scope) throws java.sql.SQLException {
        Object v = eval(nc.args().get(i), scope);
        List<Object> l = asList(v);
        if (l.isEmpty()) {
            return null;
        }
        if (l.size() == 1 && l.get(0) instanceof String s) {
            return s;
        }
        throw new IllegalStateException("fetchDb pattern argument " + i
                + " is not a String[0..1]: " + v);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        return v == null ? List.of() : List.of(v);
    }
}
