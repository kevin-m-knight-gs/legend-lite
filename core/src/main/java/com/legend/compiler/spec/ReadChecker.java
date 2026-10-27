package com.legend.compiler.spec;

import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ValueSpecification;

import java.util.List;

/**
 * {@code read(row, ~name)} (lite surface, docs/SEMANTICS_REGISTER.md S114) &mdash; a row's column through a column spec,
 * which IS the property read {@code row.name}: typed as that read, so its type, its multiplicity and its refusal of a
 * column the row has not are the read's, and lowering sees a column read. A function generic over relations (S112) is
 * expanded with its parameters substituted, so its {@code $r->read($value)} is {@code $r->read(~close)} by the time it
 * is typed; a column spec that is not written at the call (a variable, a computed one) names no column, and is refused.
 */
final class ReadChecker {

    private ReadChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        List<ValueSpecification> ps = af.parameters();
        if (ps.size() != 2) {
            throw new TypeInferenceException("read takes a row and a column spec, got " + ps.size() + " argument(s)");
        }
        if (!(ps.get(1) instanceof ColSpec cs) || cs.function1() != null || cs.function2() != null) {
            throw new TypeInferenceException("read takes a column spec written at the call (~name), got "
                    + ps.get(1).getClass().getSimpleName());
        }
        return t.synth(new AppliedProperty(ps.get(0), cs.name()), env);
    }
}
