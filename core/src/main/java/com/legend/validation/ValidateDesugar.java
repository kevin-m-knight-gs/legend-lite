// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.validation;

import com.legend.compiler.element.ModelContext;
import com.legend.error.NotImplementedException;
import com.legend.model.ClassDefinition;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.CString;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PackageableElementPtr;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * RAW-SPACE desugar of {@code meta::relational::validation::validate}
 * (feature track #45) — the engine's own synthesis (validation.pure
 * {@code generateValidationQuery} + functions.pure
 * {@code generateConstraintNegatedProjectQuery/FilteredQuery}) rebuilt
 * over the PARSED AST: per constraint,
 * <pre>
 *   userQuery-&gt;filter({this | !body})
 *            -&gt;project([|'name', |'Error', |''],
 *                      [CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE])
 * </pre>
 * folded with {@code concatenate}, handed to the ORDINARY execute path —
 * the pipeline types, resolves and lowers the synthesized query like any
 * user query (constraint bodies with exists/aggregates compile like any
 * predicate; the feature is THIN GLUE, per the feature map §14.1).
 *
 * <p>Runs BEFORE name resolution (TestBody entry), so the call matches
 * by simple name + validation-import scope; the constraint bodies are
 * the parser's own {@code ValueSpecification}s ($this references become
 * the filter lambda's parameter).
 */
public final class ValidateDesugar {

    private ValidateDesugar() {
    }

    private static final String PKG = "meta::relational::validation";

    /** {@code stmt} with every {@code validate(...)} call rewritten to
     * the synthesized {@code execute(...)}; unchanged when none. */
    public static ValueSpecification rewrite(ValueSpecification stmt,
            ModelContext ctx, List<String> imports) {
        return switch (stmt) {
            case AppliedFunction af -> {
                List<ValueSpecification> ps = new ArrayList<>();
                boolean changed = false;
                for (ValueSpecification p : af.parameters()) {
                    ValueSpecification r = rewrite(p, ctx, imports);
                    ps.add(r);
                    changed |= r != p;
                }
                AppliedFunction cur = changed
                        ? new AppliedFunction(af.function(), ps,
                                af.candidateFqns())
                        : af;
                yield isValidate(cur, imports) ? desugar(cur, ctx, imports)
                        : cur;
            }
            case LambdaFunction lf -> {
                List<ValueSpecification> body = new ArrayList<>();
                boolean changed = false;
                for (ValueSpecification b : lf.body()) {
                    ValueSpecification r = rewrite(b, ctx, imports);
                    body.add(r);
                    changed |= r != b;
                }
                yield changed ? new LambdaFunction(lf.parameters(), body) : lf;
            }
            case PureCollection pc -> {
                List<ValueSpecification> vs = new ArrayList<>();
                boolean changed = false;
                for (ValueSpecification v : pc.values()) {
                    ValueSpecification r = rewrite(v, ctx, imports);
                    vs.add(r);
                    changed |= r != v;
                }
                yield changed ? new PureCollection(vs) : pc;
            }
            default -> stmt;
        };
    }

    private static boolean isValidate(AppliedFunction af,
            List<String> imports) {
        if (af.parameters().size() < 4
                || !(af.parameters().get(0) instanceof LambdaFunction)) {
            return false;
        }
        String f = af.function();
        if (f.contains("::")) {
            return (PKG + "::validate").equals(f);
        }
        return "validate".equals(f) && imports.contains(PKG);
    }

    private static ValueSpecification desugar(AppliedFunction af,
            ModelContext ctx, List<String> imports) {
        LambdaFunction query = (LambdaFunction) af.parameters().get(0);
        ValueSpecification mapping = af.parameters().get(1);
        ValueSpecification runtime = af.parameters().get(2);
        // optional tail: [exeCtx] [constraintIds] [constraintInformation]
        // extensions — classified by shape, exactly the real overload set
        // (validation.pure:34-72). ConstraintContextInformation stays a
        // loud wall until a corpus member needs it.
        List<String> ids = new ArrayList<>();
        ValueSpecification extensions = af.parameters()
                .get(af.parameters().size() - 1);
        for (int i = 3; i < af.parameters().size() - 1; i++) {
            ValueSpecification a = af.parameters().get(i);
            if (a instanceof PureCollection pc
                    && pc.values().stream().allMatch(v -> v instanceof CString)) {
                for (ValueSpecification v : pc.values()) {
                    ids.add(((CString) v).value());
                }
            } else if (a instanceof CString cs) {
                ids.add(cs.value());
            } else if (a instanceof com.legend.model.spec.NewInstance
                    || (a instanceof AppliedFunction nw
                        && "new".equals(nw.function()))) {
                continue;   // ^RelationalExecutionContext() — PK append is
                            // the pipeline's own root-form concern
            } else {
                throw new NotImplementedException("validate(...) argument #"
                        + i + " (" + a.getClass().getSimpleName()
                        + ") is not supported yet");
            }
        }
        String classFqn = rootClassFqn(query, ctx, imports);
        ClassDefinition cd = ctx.findClassDefinition(classFqn).orElseThrow(
                () -> new NotImplementedException("validate: class '"
                        + classFqn + "' has no parsed definition"));
        List<ClassDefinition.ConstraintDefinition> constraints =
                constraintsInHierarchy(cd, ctx);
        if (!ids.isEmpty()) {
            List<ClassDefinition.ConstraintDefinition> picked =
                    new ArrayList<>();
            for (String id : ids) {
                constraints.stream().filter(c -> c.name().equals(id))
                        .findFirst().ifPresentOrElse(picked::add, () -> {
                            throw new NotImplementedException(
                                    "validate: cannot find constraint '" + id
                                    + "' in hierarchy of class '" + classFqn
                                    + "'");
                        });
            }
            constraints = picked;
        }
        if (constraints.isEmpty()) {
            throw new NotImplementedException("validate: class '" + classFqn
                    + "' has no constraints in the hierarchy to validate");
        }
        ValueSpecification queryChain = query.body()
                .get(query.body().size() - 1);
        ValueSpecification tds = null;
        for (ClassDefinition.ConstraintDefinition c : constraints) {
            ValueSpecification one = constraintProject(queryChain, c);
            tds = tds == null ? one
                    : new AppliedFunction("concatenate", List.of(tds, one));
        }
        return new AppliedFunction("execute", List.of(
                new LambdaFunction(List.of(), List.of(tds)),
                mapping, runtime, extensions));
    }

    /** Own constraints first, then supertypes' (the engine's
     * allConstraintsInHierarchy walk). */
    private static List<ClassDefinition.ConstraintDefinition>
            constraintsInHierarchy(ClassDefinition cd, ModelContext ctx) {
        List<ClassDefinition.ConstraintDefinition> out =
                new ArrayList<>(cd.constraints());
        ctx.findClass(cd.qualifiedName()).ifPresent(tc -> {
            for (String sup : tc.superClassFqns()) {
                ctx.findClassDefinition(sup).ifPresent(sd ->
                        out.addAll(constraintsInHierarchy(sd, ctx)));
            }
        });
        return out;
    }

    /** {@code chain->filter({this|!body})->project([|'n',|'Error',|''],
     *  [CONSTRAINT_ID, ENFORCEMENT_LEVEL, MESSAGE])}. */
    private static ValueSpecification constraintProject(
            ValueSpecification chain,
            ClassDefinition.ConstraintDefinition c) {
        ValueSpecification body = c.expression();
        // engine negatedFunctionExpression: not(not(x)) collapses
        ValueSpecification negated = body instanceof AppliedFunction nf
                && ("not".equals(nf.function())
                        || "meta::pure::functions::boolean::not"
                                .equals(nf.function()))
                && nf.parameters().size() == 1
                ? nf.parameters().get(0)
                : new AppliedFunction("not", List.of(body));
        ValueSpecification filtered = new AppliedFunction("filter", List.of(
                chain,
                new LambdaFunction(
                        List.of(new Variable("this", null, null)),
                        List.of(negated))));
        return new AppliedFunction("project", List.of(
                filtered,
                new PureCollection(List.of(
                        constLambda(c.name()),
                        constLambda("Error"),
                        constLambda(""))),
                new PureCollection(List.of(
                        new CString("CONSTRAINT_ID"),
                        new CString("ENFORCEMENT_LEVEL"),
                        new CString("MESSAGE")))));
    }

    private static LambdaFunction constLambda(String value) {
        return new LambdaFunction(
                List.of(new Variable("x", null, null)),
                List.of(new CString(value)));
    }

    /** The query lambda's root class ({@code X.all()} — the deepest
     * {@code getAll} target), import-qualified against the model. */
    private static String rootClassFqn(ValueSpecification n, ModelContext ctx,
            List<String> imports) {
        if (n instanceof AppliedFunction af) {
            if ("getAll".equals(af.function()) && !af.parameters().isEmpty()
                    && af.parameters().get(0)
                            instanceof PackageableElementPtr ptr) {
                String name = ptr.fullPath();
                if (ctx.findClassDefinition(name).isPresent()) {
                    return name;
                }
                for (String imp : imports) {
                    String q = imp + "::" + name;
                    if (ctx.findClassDefinition(q).isPresent()) {
                        return q;
                    }
                }
                throw new NotImplementedException("validate: cannot qualify"
                        + " root class '" + name + "'");
            }
            for (ValueSpecification p : af.parameters()) {
                try {
                    return rootClassFqn(p, ctx, imports);
                } catch (NotImplementedException e) {
                    throw e;
                } catch (RuntimeException ignore) {
                    // keep scanning siblings
                }
            }
        }
        if (n instanceof LambdaFunction lf) {
            for (ValueSpecification b : lf.body()) {
                try {
                    return rootClassFqn(b, ctx, imports);
                } catch (RuntimeException ignore) {
                    // keep scanning
                }
            }
        }
        throw new IllegalStateException("no getAll root in validate query");
    }
}
