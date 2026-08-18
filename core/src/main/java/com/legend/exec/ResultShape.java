package com.legend.exec;

import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.element.type.ExprType;

/**
 * THE result-shape classification (PHASE_HIJ_LOWERING.md, "a first-class
 * axis"): decided ONCE at the query root from its Pure type — a closed
 * switch, never a heuristic — and drives the SQL envelope and the
 * {@link ExecutionResult} variant. Per-op lowering never branches on it.
 */
public enum ResultShape {
    TABULAR, GRAPH, COLLECTION, SCALAR;

    /**
     * Node-aware classification (post-H roots): GRAPH is the RESOLVER's
     * envelope — a class-typed root WITHOUT one is an instance VALUE (a
     * struct: {@code find} over instance literals, a constructed pair) and
     * takes the value shapes. The type alone cannot distinguish the two.
     */
    public static ResultShape of(com.legend.compiler.spec.typed.TypedSpec root) {
        if (root instanceof com.legend.compiler.spec.typed.TypedFrom f) {
            return of(f.source());
        }
        if (root instanceof com.legend.compiler.spec.typed.TypedSerializeGraph) {
            return GRAPH;
        }
        if (root.info().type() instanceof Type.ClassType ct
                && !PlatformTypes.isVariant(ct) && !PlatformTypes.isNil(ct)) {
            return isMany(root.info().multiplicity()) ? COLLECTION : SCALAR;
        }
        return of(root.info());
    }

    public static ResultShape of(ExprType root) {
        if (root.type() instanceof Type.RelationType rt) {
            // F6.2 (audit A2): the map-binder channel (single synthetic
            // u_map__ column) IS a VALUE COLLECTION — pure collections
            // hold no empties, and the COLLECTION shaping's existing
            // null-drop is the platform-owned convention (the harness
            // used to repair arity with a post-ResultSet strip; SQL-level
            // exclusion corrupted correlated getters — referee-proven)
            if (rt.columns().size() == 1
                    && rt.columns().get(0).name().startsWith(
                            com.legend.sql.SqlSelect.SYNTH_MAP_COL)) {
                return isMany(root.multiplicity()) ? COLLECTION : SCALAR;
            }
            return TABULAR;
        }
        if (root.type() instanceof Type.ClassType ct) {
            // Variant is a SCALAR JSON value, not an object graph — the
            // lowering types it as JSON (audit L8); every other class value
            // is a graph fetch.
            if (PlatformTypes.isVariant(ct)) {
                return isMany(root.multiplicity()) ? COLLECTION : SCALAR;
            }
            // Nil is the []-born BOTTOM type — an always-empty VALUE
            // ([]->head() is Nil[0..1] = null), never an object graph.
            if (PlatformTypes.isNil(ct)) {
                return isMany(root.multiplicity()) ? COLLECTION : SCALAR;
            }
            return GRAPH;
        }
        return isMany(root.multiplicity()) ? COLLECTION : SCALAR;
    }

    private static boolean isMany(Multiplicity m) {
        return m.requireBounded("ResultShape").isMany();
    }
}
