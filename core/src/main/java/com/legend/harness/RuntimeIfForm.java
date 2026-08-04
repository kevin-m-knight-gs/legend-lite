// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.element.ModelContext;
import com.legend.model.ImportScope;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.ValueSpecification;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RUNTIME-conditional statement form — the engine test idiom guarding
 * engine-only payload asserts:
 * {@code if($r->at(0).elementOverride->isNotEmpty(), |assert…, |true);}
 * (inheritance testGetAll's KeyInformation branch). The CONDITION
 * evaluates through the pipeline; the chosen branch's statements re-enter
 * the ordinary statement loop, so asserts inside stay harness-verified.
 * Literal-condition ifs fold earlier ({@code foldLiteralIf}); this form
 * exists for conditions only execution can answer.
 */
final class RuntimeIfForm {

    private RuntimeIfForm() {
    }

    /** Splice the chosen branch's statements onto {@code work}; false
     * when {@code stmt} is not this form / not one boolean. */
    static boolean splice(ValueSpecification stmt,
            Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, Set<String> execVars,
            Map<String, ValueSpecification> execChains, ModelContext ctx,
            ImportScope imports, String runtimeFqn, Connection conn,
            java.util.Deque<ValueSpecification> work)
            throws java.sql.SQLException {
        List<ValueSpecification> br = branch(stmt, lets, execStmts, execVars,
                execChains, ctx, imports, runtimeFqn, conn);
        if (br == null) {
            return false;
        }
        for (int i = br.size() - 1; i >= 0; i--) {
            work.addFirst(br.get(i));
        }
        return true;
    }

    /** The chosen branch's statements, or null when {@code stmt} is not
     * this form / the condition is not one boolean. */
    static @com.legend.Nullable List<ValueSpecification> branch(
            ValueSpecification stmt, Map<String, ValueSpecification> lets,
            List<ValueSpecification> execStmts, Set<String> execVars,
            Map<String, ValueSpecification> execChains,
            ModelContext ctx, ImportScope imports, String runtimeFqn,
            Connection conn) throws java.sql.SQLException {
        if (!(stmt instanceof AppliedFunction iff)
                || !TestBody.simpleName(iff.function()).equals("if")
                || iff.parameters().size() != 3
                || !(iff.parameters().get(1) instanceof LambdaFunction tb)
                || !tb.parameters().isEmpty()
                || !(iff.parameters().get(2) instanceof LambdaFunction eb)
                || !eb.parameters().isEmpty()) {
            return null;
        }
        TestBody.Eval cv = TestBody.eval(iff.parameters().get(0), lets,
                execStmts, execVars, execChains, ctx, imports, runtimeFqn,
                conn);
        Boolean b = cv.result() instanceof
                com.legend.exec.ExecutionResult.Scalar sc
                && sc.value() instanceof Boolean bb ? bb : null;
        return b == null ? null : (b ? tb : eb).body();
    }
}
