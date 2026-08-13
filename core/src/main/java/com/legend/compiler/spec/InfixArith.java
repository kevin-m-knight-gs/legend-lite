package com.legend.compiler.spec;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;

import java.util.List;

/**
 * The compiler-side view of the parser's ENGINE-SHAPED n-ary arithmetic.
 *
 * <p>Operator-built {@code a + b + c} parses the engine's way &mdash; ONE
 * collection parameter holding the whole same-op run
 * ({@code plus[Collection[a,b,c]]}, DomainParseTreeWalker's
 * {@code buildArithmeticOp}) &mdash; so the model and the wire agree. The
 * compiler's internal convention (and {@code Pure.java}'s registered
 * signatures) is pairwise; this helper converts the carrier to its binary
 * LEFT FOLD, which is the operators' associativity. Shallow on purpose:
 * nested carriers re-enter through the same seams
 * ({@code Typer.applyFunction} desugars every application it types).
 */
final class InfixArith {

    private InfixArith() {
    }

    /** True when the node is an OPERATOR-BUILT n-ary carrier:
     *  {@code fn[Collection[a,b,...]]} with the parser's {@code infix}
     *  marker. The marker (not a name set — deep-audit item 11: the old
     *  name set here and the emitter's had already drifted apart)
     *  distinguishes {@code a + b + c} from a user's literal
     *  {@code plus([1,2,3])}, which stays a plain collection call.
     *  {@code divide} and the comparisons are always pairwise on the
     *  wire and never carry a collection. */
    static boolean isNaryCarrier(AppliedFunction af) {
        return af.infix()
                && af.parameters().size() == 1
                && af.parameters().get(0) instanceof PureCollection run
                && run.values().size() >= 2;
    }

    /** {@code fn[Collection[a,b,c]]} &rarr; {@code fn(fn(a,b),c)}; any other
     *  node is returned unchanged. Top level only. */
    static ValueSpecification binarize(AppliedFunction af) {
        if (!isNaryCarrier(af)) {
            return af;
        }
        List<ValueSpecification> run = ((PureCollection) af.parameters().get(0)).values();
        ValueSpecification folded = run.get(0);
        for (int i = 1; i < run.size(); i++) {
            folded = new AppliedFunction(af.function(), List.of(folded, run.get(i)),
                    af.candidateFqns(), af.pos(), false, false, true);
        }
        return folded;
    }
}
