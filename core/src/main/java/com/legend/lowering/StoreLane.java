package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCast;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;

/**
 * WHERE a possibly-empty operand comes from — the fact that decides an
 * operator run's empty semantics (docs/MULTIPLICITY_AUDIT_2026_08_20.md §4,
 * the 2026-09-11 note).
 *
 * <p>A STORE read — a column of a relation row, which is what the resolver
 * turns a mapped class property into and what a relation column already is —
 * carries SQL's semantics by definition: the engine compiles {@code col + 1}
 * verbatim and NULL propagates; every corpus golden agrees. A plain PURE value
 * that may be empty (a let, a literal, a {@code [0..1]} call result) keeps
 * pure's rule: the empty element drops out of the run. The mark is the
 * resolver's OUTPUT read by its checked TYPE (the operand's source is a
 * relation row), never a name and never the SQL text.
 */
final class StoreLane {

    private StoreLane() {
    }

    /** An operand SQL arithmetic may take verbatim: always present, or a
     *  store column read (through the cast / toOne / trust wrappers the
     *  resolver leaves on one). */
    static boolean sqlLane(TypedSpec operand) {
        if (Stamps.exactlyOne(operand)) {
            return true;
        }
        TypedSpec cur = operand;
        while (true) {
            if (cur instanceof TypedCast c) {
                cur = c.source();
            } else if (cur instanceof TypedNativeCall nc && nc.args().size() == 1
                    && com.legend.builtin.Pure.isToOneCall(nc.callee().qualifiedName())) {
                cur = nc.args().get(0);
            } else {
                break;
            }
        }
        return cur instanceof TypedPropertyAccess pa
                && Type.schemaView(pa.source().info().type()) instanceof Type.RelationType;
    }

    /** Every operand of the run is SQL-lane. */
    static boolean sqlLane(java.util.List<TypedSpec> operands) {
        for (TypedSpec o : operands) {
            if (!sqlLane(o)) {
                return false;
            }
        }
        return true;
    }
}
