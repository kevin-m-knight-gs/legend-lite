// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.typed.TypedSpec;

/**
 * RUNTIME-ARGUMENT connection-flag readers (extracted from
 * {@link StatementExecutor} at the file guardrail): the plan surface's
 * structural reads over an executionPlan/toSQLString call's runtime
 * argument — quoteIdentifiers, timeZone, the connection's plan spelling,
 * and its DatabaseType. Bounded constant-folds only; never guesses.
 */
final class ConnectionFlags {

    private ConnectionFlags() {
    }

    /** The engine connection's quoteIdentifiers flag, read off the
     * executionPlan call's RUNTIME argument (a Runtime instance literal
     * carrying a TestDatabaseConnection(quoteIdentifiers=true)). */
    static boolean quoteIdentifiersOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedNewInstance ni
                    && ni.properties().get("quoteIdentifiers")
                            instanceof TypedSpec qv) {
                Boolean b2 = staticBool(qv);
                if (b2 != null) {
                    return b2;
                }
            }
            // the PLATFORM-NATIVE testRuntime(quoteIdentifiers:Boolean[1])
            // overload (relationalSetUp.pure:1223 is the corpus contract;
            // the user body is platform-suppressed, so the flag rides the
            // call's own argument)
            if (t instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                    && nc.callee().qualifiedName().equals(
                            "meta::external::store::relational::tests::testRuntime")
                    && nc.args().size() == 1
                    && nc.args().get(0) instanceof
                            com.legend.compiler.spec.typed.TypedCBoolean fb) {
                return fb.value();
            }
            work.addAll(t.children());
        }
        return false;
    }

    /** Bounded constant-fold of the corpus connection-builder idiom
     * ({@code if($q->isEmpty(), |false, |$q->toOne())} over an INLINED
     * literal — relationalSetUp.pure testDatabaseConnection). Null =
     * not statically known; never guesses. */
    static @com.legend.Nullable Boolean staticBool(TypedSpec t) {
        return switch (t) {
            case com.legend.compiler.spec.typed.TypedCBoolean b -> b.value();
            case com.legend.compiler.spec.typed.TypedNativeCall nc
                    when nc.callee().qualifiedName().equals(
                            "meta::pure::functions::multiplicity::toOne")
                    && nc.args().size() >= 1 -> staticBool(nc.args().get(0));
            case com.legend.compiler.spec.typed.TypedIf i -> {
                Boolean empt = staticIsEmpty(i.condition());
                if (empt == null) {
                    yield null;
                }
                TypedSpec branch = empt ? i.thenBranch()
                        : i.elseBranch().orElse(null);
                if (branch instanceof
                        com.legend.compiler.spec.typed.TypedLambda l
                        && !l.body().isEmpty()) {
                    branch = l.body().get(l.body().size() - 1);
                }
                yield branch == null ? null : staticBool(branch);
            }
            default -> null;
        };
    }

    /** {@code isEmpty(x)} over a statically known operand; null else. */
    static @com.legend.Nullable Boolean staticIsEmpty(TypedSpec cond) {
        if (!(cond instanceof com.legend.compiler.spec.typed.TypedNativeCall nc
                && nc.callee().qualifiedName().equals(
                "meta::pure::functions::collection::isEmpty")
                && nc.args().size() == 1)) {
            return null;
        }
        TypedSpec x = nc.args().get(0);
        if (x instanceof com.legend.compiler.spec.typed.TypedCollection c) {
            return c.elements().isEmpty();
        }
        // any scalar LITERAL is a one-element collection: not empty
        if (x instanceof com.legend.compiler.spec.typed.TypedCBoolean
                || x instanceof com.legend.compiler.spec.typed.TypedCString
                || x instanceof com.legend.compiler.spec.typed.TypedCInteger) {
            return false;
        }
        return null;
    }

    /** The engine connection's timeZone, read off the RUNTIME argument
     * (an inline DatabaseConnection(timeZone='US/Arizona')). Null when
     * absent — the default-zone connection. */
    static @com.legend.Nullable String timeZoneOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed.TypedNewInstance ni
                    && ni.properties().get("timeZone")
                            instanceof com.legend.compiler.spec.typed
                                    .TypedCString tzs) {
                return tzs.value();
            }
            work.addAll(t.children());
        }
        return null;
    }

    /** The runtime connection's plan spelling — the instance's own CLASS
     * simple name (exact-FQN dispatch) with its declared DatabaseType
     * ({@code DatabaseConnection(type = "DB2")}). */
    static @com.legend.Nullable String connectionNameOf(TypedSpec runtimeArg) {
        var ni = connectionInstanceOf(runtimeArg);
        if (ni == null) {
            return "TestDatabaseConnection(type = \"H2\")";
        }
        String simple = switch (ni.classFqn()) {
            case "meta::external::store::relational::runtime"
                    + "::DatabaseConnection" -> "DatabaseConnection";
            case "meta::external::store::relational::runtime"
                    + "::RelationalDatabaseConnection" ->
                    "RelationalDatabaseConnection";
            default -> "TestDatabaseConnection";
        };
        return simple + "(type = \"" + dbTypeOf(ni) + "\")";
    }

    /** The FIRST connection instance under {@code runtimeArg} (exact-FQN
     * dispatch), or null. */
    static com.legend.compiler.spec.typed.@com.legend.Nullable TypedNewInstance
            connectionInstanceOf(TypedSpec runtimeArg) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(runtimeArg);
        while (!work.isEmpty()) {
            TypedSpec t = work.poll();
            if (t instanceof com.legend.compiler.spec.typed
                            .TypedNewInstance ni
                    && ("meta::external::store::relational::runtime::DatabaseConnection"
                                    .equals(ni.classFqn())
                        || "meta::external::store::relational::runtime::TestDatabaseConnection"
                                    .equals(ni.classFqn())
                        || "meta::external::store::relational::runtime::RelationalDatabaseConnection"
                                    .equals(ni.classFqn()))) {
                return ni;
            }
            work.addAll(t.children());
        }
        return null;
    }

    static @com.legend.Nullable String dbTypeOf(
            com.legend.compiler.spec.typed.TypedNewInstance ni) {
        return ni.properties().get("type") instanceof
                com.legend.compiler.spec.typed.TypedEnumValue ev
                ? String.valueOf(ev.value()) : "H2";
    }

    /** The runtime connection's DatabaseType name ("H2" when absent). */
    static @com.legend.Nullable String databaseTypeOf(TypedSpec runtimeArg) {
        var ni = connectionInstanceOf(runtimeArg);
        return ni == null ? "H2" : dbTypeOf(ni);
    }
}
