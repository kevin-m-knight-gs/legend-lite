package com.legend.compiler.spec;

import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.ColSpecArray;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure's {@code isDistinct}. The 1-arg collection form types generically
 * (its lowering is the group reducer COUNT(DISTINCT x) = COUNT(x)). The
 * 2-arg BY-TREE form {@code isDistinct(collection, #{T{a, b}}#)} — "no
 * duplicates comparing the elements by the tree's leaf properties" (engine
 * collectionExtension.pure declares it; its Pure body is
 * {@code fail('Not implemented!')} and only the generated-Java plan binding
 * implements it, IsDistinctFetchTreeCoder: an equality method over the
 * tree) — desugars to the 1-arg form over the row of those leaves:
 * {@code collection->map(e | tuple([$e.a, $e.b]))->isDistinct()}, with
 * {@code tuple} the internal struct value ({@link
 * com.legend.builtin.Pure.Lite#TUPLE}). A nested sub-tree is not a leaf and
 * walls loudly (the corpus spells leaves only: modelWithConstraints.pure
 * duplicateEmployee).
 */
final class IsDistinctChecker {

    private IsDistinctChecker() {
    }

    static TypedSpec check(Typer t, AppliedFunction af, Env env) {
        if (af.parameters().size() != 2) {
            return t.applyGeneric(af, env);
        }
        ValueSpecification bound = env.resolveAlias(af.parameters().get(1));
        ValueSpecification tree = GraphFetchChecker.unwrapCompiledTree(bound);
        if (!(tree instanceof ColSpecArray leaves)) {
            return t.applyGeneric(af, env);
        }
        Variable e = new Variable("e");
        List<ValueSpecification> reads = new ArrayList<>();
        for (ColSpec cs : leaves.colSpecs()) {
            if (GraphFetchChecker.nestedTree(cs) != null) {
                throw new com.legend.error.NotImplementedException(
                        "isDistinct by a tree with a nested sub-tree (" + cs.name()
                                + " {…}) — leaves only");
            }
            reads.add(new AppliedProperty(e, cs.name()));
        }
        if (reads.size() < 2 || reads.size() > 6) {
            throw new com.legend.error.NotImplementedException(
                    "isDistinct by a tree of " + reads.size() + " leaves — 2 to 6 supported"
                            + " (one leaf: isDistinct over the property itself)");
        }
        // one tuple ARGUMENT per leaf (explicit arities): a collection literal
        // of mixed leaf types would lower as a text-cell list, not a row
        AppliedFunction mapped = new AppliedFunction("map", List.of(
                af.parameters().get(0),
                new LambdaFunction(List.of(e), List.of(new AppliedFunction(
                        com.legend.builtin.Pure.Lite.TUPLE, reads)))));
        return t.synth(new AppliedFunction("isDistinct", List.of(mapped)), env);
    }
}
