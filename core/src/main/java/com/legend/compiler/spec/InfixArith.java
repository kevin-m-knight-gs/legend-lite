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

    /** The n-ary arithmetic carriers, by EXACT name (simple + canonical FQN —
     *  no suffix matching). {@code divide} and the comparisons are always
     *  pairwise on the wire and never carry a collection. */
    static final java.util.Set<String> NARY_ARITH_CARRIERS = java.util.Set.of(
            "plus", "minus", "times",
            "meta::pure::functions::math::plus",
            "meta::pure::functions::math::minus",
            "meta::pure::functions::math::times",
            "meta::pure::functions::string::plus");

    /** True when the node is an n-ary carrier: {@code fn[Collection[a,b,...]]}. */
    static boolean isNaryCarrier(AppliedFunction af) {
        return NARY_ARITH_CARRIERS.contains(af.function())
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
            folded = new AppliedFunction(af.function(),
                    List.of(folded, run.get(i)),
                    af.candidateFqns(), af.pos(), false, false, true);
        }
        return folded;
    }
}
