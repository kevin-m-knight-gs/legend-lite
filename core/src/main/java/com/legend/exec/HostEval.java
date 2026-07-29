// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.typed.TypedCInteger;
import com.legend.compiler.spec.typed.TypedCString;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedFold;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;

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

    /** Does this expression tree read a fetchDb* metadata native? */
    public static boolean wantsHostEval(TypedSpec root) {
        if (root instanceof TypedNativeCall nc
                && PlatformTypes.isFetchDbFn(nc.callee().qualifiedName())) {
            return true;
        }
        for (TypedSpec c : root.children()) {
            if (wantsHostEval(c)) {
                return true;
            }
        }
        return false;
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
        return switch (PlatformTypes.fetchDbKind(fqn)) {
            case SCHEMAS -> DbMetaData.fetch(fqn, a1, null, null, replay);
            case TABLES, PRIMARY_KEYS -> DbMetaData.fetch(fqn, a1, a2, null,
                    replay);
            case COLUMNS -> DbMetaData.fetch(fqn, a1, a2, a3, replay);
        };
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
