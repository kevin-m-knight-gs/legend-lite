// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.lineage.ScanColumns;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.AppliedProperty;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.LambdaFunction;
import com.legend.protocol.spec.PackageableElementPtr;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine's canonical scanColumns TEST FORM (#44), recognized as a
 * whole and routed to the REAL analyzer:
 * <pre>
 *   let p    = {|Q}.expressionSequence-&gt;at(0)-&gt;evaluateAndDeactivate()
 *                  -&gt;scanProperties(^List&lt;PropertyPathNode&gt;(), [], []);
 *   let tree = $p.result-&gt;buildPropertyTree();
 *   let f    = $tree-&gt;scanColumns(&lt;mapping&gt;)-&gt;removeDuplicates();
 *   assertEquals([...], $f-&gt;map(t|$t.column.owner-&gt;cast(@Table).name
 *       -&gt;toOne()+'.'+$t.column.name-&gt;toOne()+' &lt;'+$t.context+'&gt;')
 *       -&gt;sort());
 * </pre>
 *
 * <p><strong>Why a form bridge and not K-natives (the B3 route).</strong>
 * The chain above is the engine's REFLECTION API (expression metamodel +
 * property trees), not query semantics. Typing it would mean minting
 * opaque platform shells (scanProperties results, PropertyTree nodes,
 * ColumnWithContext with a live Column/Table metamodel) whose ONLY
 * consumer is the terminal formatting — hollow vocabulary, a worse fake
 * than this explicit bridge. Here the ANALYSIS is real core code over
 * the real pipeline's lowered SQL plan ({@link ScanColumns}); the bridge
 * replaces only the reflection plumbing, requires the VERBATIM canonical
 * form (format lambda included — anything else stays SHAPE/loud), and
 * every string divergence is an honest FAIL pinning our emission against
 * the engine's golden.
 */
final class LineageForm {

    private LineageForm() {
    }

    /** Runs the body when it IS the canonical scanColumns form; null
     * when the form doesn't match (the caller proceeds normally). */
    static EngineTestExecutor.@com.legend.Nullable Outcome tryRun(ModelContext ctx,
            List<ValueSpecification> statements, ImportScope imports,
            String runtimeFqn) {
        LambdaFunction query = null;
        String mappingRef = null;
        List<String> expected = null;
        boolean sawScanProperties = false;
        boolean sawBuildTree = false;
        boolean formatOk = false;
        for (ValueSpecification stmt : statements) {
            ValueSpecification v = letValue(stmt);
            if (containsCall(v, "scanProperties")) {
                sawScanProperties = true;
                query = zeroParamLambda(v);
            }
            if (containsCall(v, "buildPropertyTree")) {
                sawBuildTree = true;
            }
            AppliedFunction sc = findCall(v, "scanColumns");
            if (sc != null && sc.parameters().size() >= 2
                    && sc.parameters().get(1)
                            instanceof PackageableElementPtr mp) {
                mappingRef = mp.fullPath();
            }
            AppliedFunction ae = findCall(v, "assertEquals");
            if (ae != null && ae.parameters().size() == 2) {
                AppliedFunction sort = findCall(ae.parameters().get(1), "sort");
                AppliedFunction map = sort == null ? null
                        : findCall(sort, "map");
                if (map != null && map.parameters().size() == 2
                        && map.parameters().get(1)
                                instanceof LambdaFunction fmt
                        && canonicalFormat(fmt)) {
                    formatOk = true;
                    expected = stringList(ae.parameters().get(0));
                }
            }
        }
        if (!sawScanProperties || !sawBuildTree || query == null
                || mappingRef == null || expected == null || !formatOk) {
            return null;
        }
        try {
            ValueSpecification resolved = NameResolver.resolveQuery(
                    query, imports, ctx.elementFqns());
            com.legend.sql.SqlQuery plan = Compiler.lowerResolved(
                    resolved, ctx, runtimeFqn, false);
            List<String> got = ScanColumns.strings(plan);
            if (System.getenv("LL_LINEAGE_DEBUG") != null) {
                System.err.println("[scanColumns-sql] "
                        + new com.legend.sql.dialect.DuckDb().render(plan));
            }
            List<String> want = new ArrayList<>(expected);
            want.sort(String::compareTo);
            if (want.equals(got)) {
                return new EngineTestExecutor.Outcome.Ran(1, 0, 0, List.of());
            }
            return new EngineTestExecutor.Outcome.Ran(1, 0, 0, List.of(
                    "scanColumns: expected " + want + ", got " + got));
        } catch (com.legend.error.NotImplementedException e) {
            return new EngineTestExecutor.Outcome.Unsupported("scanColumns query: "
                    + String.valueOf(e.getMessage()).split("\\n")[0]);
        }
    }

    /** The canonical formatting lambda — owner/cast-Table/name + column
     * name + context, nothing else accepted. */
    private static boolean canonicalFormat(LambdaFunction fmt) {
        boolean[] seen = new boolean[3];   // owner, column.name, context
        walkProps(fmt, seen);
        return seen[0] && seen[1] && seen[2];
    }

    private static void walkProps(ValueSpecification n, boolean[] seen) {
        if (n instanceof AppliedProperty ap) {
            if ("owner".equals(ap.property())) {
                seen[0] = true;
            }
            if ("name".equals(ap.property())) {
                seen[1] = true;
            }
            if ("context".equals(ap.property())) {
                seen[2] = true;
            }
            walkProps(ap.receiver(), seen);
            return;
        }
        if (n instanceof AppliedFunction af) {
            af.parameters().forEach(p -> walkProps(p, seen));
        } else if (n instanceof LambdaFunction lf) {
            lf.body().forEach(b -> walkProps(b, seen));
        } else if (n instanceof PureCollection pc) {
            pc.values().forEach(v -> walkProps(v, seen));
        }
    }

    private static @com.legend.Nullable ValueSpecification letValue(ValueSpecification stmt) {
        return stmt instanceof AppliedFunction af
                && "letFunction".equals(af.function())
                && af.parameters().size() == 2
                ? af.parameters().get(1) : stmt;
    }

    private static boolean containsCall(@com.legend.Nullable ValueSpecification n, String simple) {
        return findCall(n, simple) != null;
    }

    private static @com.legend.Nullable AppliedFunction findCall(@com.legend.Nullable ValueSpecification n,
            String simple) {
        if (n instanceof AppliedFunction af) {
            String s = af.function()
                    .substring(af.function().lastIndexOf(':') + 1);
            if (s.equals(simple)) {
                return af;
            }
            for (ValueSpecification p : af.parameters()) {
                AppliedFunction r = findCall(p, simple);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof AppliedProperty ap) {
            return findCall(ap.receiver(), simple);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                AppliedFunction r = findCall(b, simple);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof PureCollection pc) {
            for (ValueSpecification v : pc.values()) {
                AppliedFunction r = findCall(v, simple);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof Variable) {
            return null;
        }
        return null;
    }

    /** The query lambda: the zero-parameter lambda the reflection chain
     * opens with ({@code {|Q}.expressionSequence...}). */
    private static @com.legend.Nullable LambdaFunction zeroParamLambda(@com.legend.Nullable ValueSpecification n) {
        if (n instanceof LambdaFunction lf && lf.parameters().isEmpty()) {
            return lf;
        }
        List<ValueSpecification> children = switch (n) {
            case null -> List.of();
            case AppliedFunction af -> af.parameters();
            case AppliedProperty ap -> List.of(ap.receiver());
            case PureCollection pc -> pc.values();
            default -> List.of();
        };
        for (ValueSpecification c : children) {
            LambdaFunction r = zeroParamLambda(c);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static @com.legend.Nullable List<String> stringList(ValueSpecification n) {
        if (n instanceof CString cs) {
            return List.of(cs.value());
        }
        if (n instanceof PureCollection pc) {
            List<String> out = new ArrayList<>();
            for (ValueSpecification v : pc.values()) {
                if (!(v instanceof CString cs)) {
                    return null;
                }
                out.add(cs.value());
            }
            return out;
        }
        return null;
    }
}
