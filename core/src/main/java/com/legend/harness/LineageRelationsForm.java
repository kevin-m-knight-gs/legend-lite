// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.compiler.NameResolver;
import com.legend.compiler.element.ModelContext;
import com.legend.lineage.ScanRelations;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's scanRelations TEST FORM (#44): {@code let tree =
 * scanRelations($query, $mapping[, runtime], ext)->toOne();
 * assertEquals(expected, $tree->relationTreeAsString())}. BOTH variants
 * VERIFY against {@link ScanRelations}; a tree the scanner cannot
 * reproduce is a loud wall. (The former runtime-variant advisory arms —
 * skip-by-grepping-the-expected-string, NotImplementedException
 * laundering — manufactured ~half the family's greens; deleted per
 * study §8.3 so the plan-derived tree starts honest.)
 */
final class LineageRelationsForm {

    private LineageRelationsForm() {
    }

    static TestBody.@com.legend.Nullable Outcome tryRun(ModelContext ctx,
            List<ValueSpecification> statements, ImportScope imports,
            String runtimeFqn) {
        Map<String, ValueSpecification> lets = new LinkedHashMap<>();
        // let name -> STATIC scanRelations call (null value = runtime
        // variant let; absent = not a scanRelations let)
        Map<String, AppliedFunction> staticCalls = new LinkedHashMap<>();
        Map<String, Boolean> isScan = new LinkedHashMap<>();
        List<String[]> asserts = new ArrayList<>();   // [letName, expected]
        for (ValueSpecification stmt : statements) {
            if (stmt instanceof AppliedFunction lf
                    && "letFunction".equals(lf.function())
                    && lf.parameters().size() == 2
                    && lf.parameters().get(0) instanceof CString ln) {
                lets.put(ln.value(), lf.parameters().get(1));
                AppliedFunction scan = findCall(lf.parameters().get(1),
                        "scanRelations");
                if (scan != null) {
                    isScan.put(ln.value(), Boolean.TRUE);
                    if (scan.parameters().size() == 3
                            || scan.parameters().size() == 4) {
                        staticCalls.put(ln.value(), scan);
                    }
                }
                continue;
            }
            AppliedFunction ae = stmt instanceof AppliedFunction f
                    && "assertEquals".equals(simple(f.function()))
                    && f.parameters().size() == 2 ? f : null;
            if (ae == null) {
                continue;
            }
            AppliedFunction tas = findCall(ae.parameters().get(1),
                    "relationTreeAsString");
            if (tas == null || tas.parameters().isEmpty()) {
                continue;
            }
            String var = receiverVar(tas.parameters().get(0));
            String expected = foldString(ae.parameters().get(0), lets);
            if (var != null && expected != null) {
                asserts.add(new String[]{var, expected});
            }
        }
        if (asserts.isEmpty() || isScan.isEmpty()) {
            return null;
        }
        int verified = 0;
        int advisory = 0;
        List<String> failures = new ArrayList<>();
        for (String[] a : asserts) {
            if (!Boolean.TRUE.equals(isScan.get(a[0]))) {
                return null;   // a tree assert over something else — not
                               // this form; process normally
            }
            AppliedFunction scan = staticCalls.get(a[0]);
            if (scan == null) {
                advisory++;    // unrecognized scan shape
                continue;
            }
            ValueSpecification q = deref(scan.parameters().get(0), lets);
            ValueSpecification m = deref(scan.parameters().get(1), lets);
            if (!(q instanceof LambdaFunction ql)
                    || !(m instanceof PackageableElementPtr mp)) {
                return null;
            }
            // BOTH variants verify against ScanRelations, and a tree the
            // scanner cannot reproduce is a LOUD wall for both (study §8.3:
            // the runtime-variant advisory arms decided whether to check
            // the answer by GREPPING the answer, and laundered
            // NotImplementedExceptions into advisory — ~half the family's
            // greens were manufactured; deleted 2026-08-06, BEFORE the
            // plan-derived tree lands, so its build starts honest).
            try {
                ValueSpecification resolved = NameResolver.resolveQuery(
                        ql, imports, ctx.elementFqns());
                String got = ScanRelations.treeString(ctx,
                        (LambdaFunction) resolved,
                        java.util.Objects.requireNonNull(
                                qualifyMapping(mp.fullPath(), ctx,
                                        imports),
                                "unresolvable mapping reference"),
                        scan.parameters().size() == 4);
                verified++;
                if (!stripAliasBreadcrumbs(a[1])
                        .equals(stripAliasBreadcrumbs(got))) {
                    failures.add("scanRelations: expected\n" + a[1]
                            + "got\n" + got);
                }
            } catch (com.legend.error.NotImplementedException e) {
                return new TestBody.Outcome.Unsupported("scanRelations: "
                        + String.valueOf(e.getMessage()).split("\\n")[0]);
            }
        }
        return new TestBody.Outcome.Ran(verified, advisory, 0, failures);
    }

    /** Strip the engine's alias BREADCRUMBS from tree labels before
     * compare (handoff: §12 settled) — the `_d#N`/`_dyN`/`_mN` runs and
     * their `_l`/`_r`/`_f`/`_md`/`_N`/`#N` continuations are
     * pureToSqlQuery's internal processing-step counters, not
     * reproducible from our IR. A run must START with a `_d…`/`_mN`
     * token, so literal constants (`…fk_8`) and ordinary identifiers
     * survive. Applied to BOTH sides: our labels carry no breadcrumbs,
     * so the semantic content (function names, tables, columns,
     * constants, the root spelling) stays exactly verified. */
    static String stripAliasBreadcrumbs(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("_d(?:#\\d+|y\\d+)?(?![A-Za-z])|_m\\d+")
                .matcher(s);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (m.find(pos)) {
            out.append(s, pos, m.start());
            int end = m.end();
            // consume the continuation run
            while (true) {
                java.util.regex.Matcher c = java.util.regex.Pattern
                        .compile("\\G(?:_d(?:#\\d+|y\\d+)?(?![A-Za-z])"
                                + "|_m\\d+|_md|_l|_r|_f|_\\d+|#\\d+)")
                        .matcher(s);
                if (!c.find(end)) {
                    break;
                }
                end = c.end();
            }
            pos = end;
        }
        out.append(s, pos, s.length());
        return out.toString();
    }

    private static @com.legend.Nullable String qualifyMapping(String name, ModelContext ctx,
            ImportScope imports) {
        if (ctx.findMapping(name).isPresent()) {
            return name;
        }
        for (String imp : imports.wildcards()) {
            String q = imp + "::" + name;
            if (ctx.findMapping(q).isPresent()) {
                return q;
            }
        }
        return name;
    }

    private static @com.legend.Nullable ValueSpecification deref(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        return v instanceof Variable var && lets.containsKey(var.name())
                ? lets.get(var.name()) : v;
    }

    /** {@code $x->relationTreeAsString()}'s receiver let name, looking
     * through toOne(). */
    private static @com.legend.Nullable String receiverVar(ValueSpecification v) {
        if (v instanceof Variable var) {
            return var.name();
        }
        if (v instanceof AppliedFunction af && af.parameters().size() == 1
                && "toOne".equals(simple(af.function()))) {
            return receiverVar(af.parameters().get(0));
        }
        return null;
    }

    /** Fold {@code 'a' + 'b' + ...} chains (and let-bound strings) to one
     * literal; null when any part is not a string. */
    private static @com.legend.Nullable String foldString(ValueSpecification v,
            Map<String, ValueSpecification> lets) {
        v = deref(v, lets);
        if (v instanceof CString cs) {
            return cs.value();
        }
        if (v instanceof AppliedFunction af
                && "plus".equals(simple(af.function()))) {
            StringBuilder sb = new StringBuilder();
            for (ValueSpecification p : af.parameters()) {
                if (p instanceof PureCollection pc) {
                    for (ValueSpecification e : pc.values()) {
                        String s = foldString(e, lets);
                        if (s == null) {
                            return null;
                        }
                        sb.append(s);
                    }
                    continue;
                }
                String s = foldString(p, lets);
                if (s == null) {
                    return null;
                }
                sb.append(s);
            }
            return sb.toString();
        }
        return null;
    }

    private static @com.legend.Nullable AppliedFunction findCall(ValueSpecification n,
            String name) {
        if (n instanceof AppliedFunction af) {
            if (name.equals(simple(af.function()))) {
                return af;
            }
            for (ValueSpecification p : af.parameters()) {
                AppliedFunction r = findCall(p, name);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof AppliedProperty ap) {
            return findCall(ap.receiver(), name);
        } else if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                AppliedFunction r = findCall(b, name);
                if (r != null) {
                    return r;
                }
            }
        } else if (n instanceof PureCollection pc) {
            for (ValueSpecification e : pc.values()) {
                AppliedFunction r = findCall(e, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static String simple(String f) {
        return f.substring(f.lastIndexOf(':') + 1);
    }
}
