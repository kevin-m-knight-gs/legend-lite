package com.legend.compiler.spec;

import com.legend.compiler.spec.typed.TypedAggCol;
import com.legend.compiler.spec.typed.TypedEval;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedMap;
import com.legend.compiler.spec.typed.TypedMatch;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSelect;
import com.legend.compiler.spec.typed.TypedSerializeGraph;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase G&frac12; &mdash; whole-program &beta;-reduction of user-defined
 * function calls (the monomorphization family: SQL has no call frame, so
 * every {@link TypedUserCall} must be spliced into its caller BEFORE the
 * store resolver and the lowerer see the tree). After this pass no
 * {@code TypedUserCall} exists, and H's demand analysis sees THROUGH
 * function boundaries by construction (a callee navigating
 * {@code $p.address.street} contributes join demand like inline code).
 *
 * <p>Rules:
 * <ul>
 *   <li><b>&beta;</b> &mdash; parameter occurrences are replaced by the
 *       (already-rewritten) argument expressions; every node keeps its
 *       G-computed {@link com.legend.compiler.element.type.ExprType}
 *       (arguments conform by overload resolution; narrower replacements
 *       are conservative &mdash; the no-restamp discipline).</li>
 *   <li><b>lets reduce</b> &mdash; a callee's intermediate
 *       {@code let x = e;} statements substitute forward (Pure lets are
 *       single-assignment), so a call becomes ONE expression. Query-level
 *       lets are untouched (the lowerer owns those).</li>
 *   <li><b>&alpha;-hygiene</b> &mdash; INSIDE an inlined body every binder
 *       (lambda parameter, let name, match parameter) is renamed to a fresh
 *       {@code _i&lt;N&gt;}, unconditionally: an argument's free variables can
 *       never be captured. (Occurrences keep their own infos.)</li>
 *   <li><b>recursion is loud</b> &mdash; a call cycle throws naming the
 *       path ({@code f/1 -> g/2 -> f/1}); SQL cannot express it.</li>
 *   <li><b>eval of a literal lambda</b> &mdash; after substitution a
 *       higher-order parameter becomes a lambda literal;
 *       {@code $f-&gt;eval($x)} then &beta;-reduces the same way. Eval of
 *       anything else passes through (its own wall downstream).</li>
 * </ul>
 */
public final class UserCallInliner {

    private final SpecCompiler specs;
    private final java.util.function.@com.legend.Nullable BiFunction<TypedSpec, java.util.Set<String>, TypedSpec> hook;
    private final ArrayDeque<String> stack = new ArrayDeque<>();
    private final ArrayDeque<String> names = new ArrayDeque<>();
    /** Lambda binders in scope at the CURRENT walk position (name → nesting
     * count) — passed to the hook so a query-level splice never captures a
     * lambda-bound variable spelled like an exec-let ({@code let r =
     * execute(...)} vs {@code ->map(r|$r.values...)}). */
    private final Map<String, Integer> bound = new LinkedHashMap<>();
    /** Query-level lets consumed by {@link #inlineBody} — graph-tree args
     * keep their source spelling, so the resolver resolves variable dates
     * through these (engine inScopeVars). */
    private final Map<String, TypedSpec> queryLets = new LinkedHashMap<>();

    public Map<String, TypedSpec> queryLets() {
        return queryLets;
    }
    /** Inside a postprocessor-CONFIG property: user calls STAND
     * (extraction reads them structurally); variables still substitute. */
    private boolean configMode;
    private int fresh;

    public UserCallInliner(SpecCompiler specs) {
        this(specs, null);
    }

    /**
     * {@code hook}: an OPTIONAL per-node pre-rewrite (the statement
     * executor's result-frame splice rides this walker instead of
     * duplicating the vocabulary switch). Fired before the standard
     * rewrite at every node; returning a DIFFERENT node replaces it and
     * the rewrite recurses into the replacement — the hook must return
     * the argument itself (same reference) when it does not apply.
     */
    public UserCallInliner(SpecCompiler specs,
            java.util.function.@com.legend.Nullable BiFunction<TypedSpec, java.util.Set<String>, TypedSpec> hook) {
        this.specs = Objects.requireNonNull(specs, "specs");
        this.hook = hook;
    }

    /** Inline every user call in a query body (statements = lets + result). */
    public List<TypedSpec> inlineBody(List<TypedSpec> body) {
        // The fresh namespace must clear every user-written _i<N> — a query
        // variable literally named _i0 would otherwise be CAPTURED by an
        // α-renamed callee binder (audit). Callee bodies are closed, so the
        // query body is the only collision source.
        for (TypedSpec stmt : body) {
            reserveFreshNames(stmt);
        }
        // QUERY-level lets β-reduce exactly like callee lets — binders die
        // in the one substitution pass. A relation-typed let ($t = #TDS…#)
        // splices its pipeline into every use; downstream phases never see
        // a let. A TRAILING let IS its value (real pure: the let statement
        // yields it). KNOWN TRADE (audit): a let used twice EVALUATES twice
        // in SQL — for a non-deterministic row set (limit with no total
        // order) the two splices may disagree where real pure's
        // single-evaluation binding could not; CTE sharing is the future fix.
        Map<String, TypedSpec> scope = new LinkedHashMap<>();
        for (int i = 0; i < body.size() - 1; i++) {
            if (!(body.get(i) instanceof TypedLet let)) {
                throw new NotImplementedException(
                        "only let statements may precede the query expression");
            }
            scope.put(let.name(), rewrite(let.value(), scope));
        }
        // graph-tree args are NOT β-reduced (source spelling is the
        // serialize key) — the resolver reads consumed lets through this
        // (engine inScopeVars)
        queryLets.putAll(scope);
        TypedSpec last = body.get(body.size() - 1);
        TypedSpec root = last instanceof TypedLet let
                ? rewrite(let.value(), scope)
                : rewrite(last, scope);
        return List.of(root);
    }

    private void reserveFreshNames(TypedSpec n) {
        switch (n) {
            case TypedVariable v -> bumpPast(v.name());
            case TypedLet let -> bumpPast(let.name());
            case TypedLambda l -> l.parameters().forEach(this::bumpPast);
            default -> { }
        }
        for (TypedSpec c : n.children()) {
            reserveFreshNames(c);
        }
    }

    private void bumpPast(String name) {
        if (name.startsWith("_i")) {
            try {
                fresh = Math.max(fresh, Integer.parseInt(name.substring(2)) + 1);
            } catch (NumberFormatException ignored) {
                // _iFoo is outside the fresh namespace
            }
        }
    }

    // =====================================================================
    // The call frame
    // =====================================================================

    private TypedSpec inlineCall(TypedUserCall call, Map<String, TypedSpec> env) {
        List<TypedSpec> args = new ArrayList<>(call.args().size());
        for (TypedSpec a : call.args()) {
            args.add(rewrite(a, env));
        }
        if (configMode) {
            return new TypedUserCall(call.callee(), args, call.info());
        }
        // signatureKey identifies the OVERLOAD — name/arity conflated two
        // same-arity overloads into a false recursion (audit).
        String key = call.callee().signatureKey();
        String shown = call.callee().qualifiedName() + "/"
                + call.callee().parameters().size();
        if (stack.contains(key)) {
            List<String> path = new ArrayList<>(names);
            java.util.Collections.reverse(path);
            throw new NotImplementedException("recursion cycle involving " + shown
                    + " (" + String.join(" -> ", path) + " -> " + shown
                    + ") — recursive functions cannot lower to SQL");
        }
        stack.push(key);
        names.push(shown);
        try {
            List<TypedSpec> body = specs.compile(call.callee()).body();
            Map<String, TypedSpec> callEnv = new LinkedHashMap<>();
            // A relation param accepts a SUPERSET schema (covariant call);
            // the spliced body then carries the caller's extra columns in
            // SQL while the call site is typed by the DECLARED return —
            // conform by EMISSION with a select down to the declared columns.
            boolean widened = false;
            for (int i = 0; i < call.callee().parameters().size(); i++) {
                callEnv.put(call.callee().parameters().get(i).name(), args.get(i));
                if (rowType(call.callee().parameters().get(i).type()) instanceof
                            com.legend.compiler.element.type.Type.RelationType dp
                        && rowType(args.get(i).info().type()) instanceof
                            com.legend.compiler.element.type.Type.RelationType ap
                        && ap.columns().size() > dp.columns().size()) {
                    widened = true;
                }
            }
            TypedSpec reduced = reduceStatements(body, callEnv);
            if (widened && call.info().type()
                    instanceof com.legend.compiler.element.type.Type.RelationType rt) {
                reduced = new com.legend.compiler.spec.typed.TypedSelect(reduced,
                        rt.columns().stream()
                                .map(com.legend.compiler.element.type.Type.Column::name)
                                .toList(),
                        call.info());
            }
            return reduced;
        } catch (NotImplementedException e) {
            // The body cannot β-reduce (a recursion cycle unwinding one
            // level, a non-let intermediate statement) — the CALL STANDS
            // with rewritten args. Channels that can run calls consume it
            // (host call frames; the plan seam reads postprocessor config
            // structurally); SQL lowering keeps its loud TypedUserCall
            // frontier wall.
            return new TypedUserCall(call.callee(), args, call.info());
        } finally {
            stack.pop();
            names.pop();
        }
    }

    /** DIRECT self-recursion in the callee's (resolved) definition body —
     * the reason {@link #inlineCall} let the call stand, recovered at the
     * resolver's TypedUserCall wall so its message names the cycle.
     * Indirect cycles keep the generic did-not-&beta;-reduce line (naming
     * them needs the whole call graph). Lives HERE, not at the wall: the
     * resolver never touches the untyped value-spec AST (invariant 6c). */
    public static boolean selfRecursive(
            com.legend.compiler.element.TypedFunction callee) {
        if (!(callee.definition()
                instanceof com.legend.model.FunctionDefinition fd)) {
            return false;
        }
        java.util.ArrayDeque<com.legend.protocol.spec.ValueSpecification> work =
                new java.util.ArrayDeque<>(fd.body());
        while (!work.isEmpty()) {
            var vs = work.poll();
            if (vs instanceof com.legend.protocol.spec.AppliedFunction af
                    && af.function().equals(callee.qualifiedName())) {
                return true;
            }
            work.addAll(vs.children());
        }
        return false;
    }

    /** The row type of a relation-valued type: bare RelationType, or Relation<(...)>. */
    private static com.legend.compiler.element.type.Type rowType(
            com.legend.compiler.element.type.Type t) {
        if (t instanceof com.legend.compiler.element.type.Type.GenericType g
                && g.rawFqn().equals("meta::pure::metamodel::relation::Relation")
                && g.arguments().size() == 1) {
            return g.arguments().get(0);
        }
        return t;
    }

    /**
     * A statement list under an environment: intermediate lets substitute
     * FORWARD (their values see the bindings so far); the last statement is
     * the value. One expression comes out.
     */
    private TypedSpec reduceStatements(List<TypedSpec> body, Map<String, TypedSpec> env) {
        Map<String, TypedSpec> scope = new LinkedHashMap<>(env);
        for (int i = 0; i < body.size() - 1; i++) {
            if (!(body.get(i) instanceof TypedLet let)) {
                throw new NotImplementedException("a non-let intermediate statement ("
                        + body.get(i).getClass().getSimpleName()
                        + ") in an inlined function body is not supported");
            }
            scope.put(let.name(), rewrite(let.value(), scope));
        }
        // A TRAILING let IS its value (real pure: the let statement yields
        // it) — `{ let r = $x + 100 }` returns the sum, and no let node
        // survives into H/I.
        TypedSpec last = body.get(body.size() - 1);
        return last instanceof TypedLet let
                ? rewrite(let.value(), scope)
                : rewrite(last, scope);
    }

    /** &beta;-reduce {@code eval(<literal lambda>, args)}. */
    private TypedSpec reduceEval(TypedEval ev, List<TypedSpec> args, TypedLambda lam) {
        if (lam.parameters().size() != args.size()) {
            throw new IllegalStateException("eval arity mismatch after inlining: "
                    + lam.parameters().size() + " parameter(s), " + args.size()
                    + " argument(s) — G should have rejected this");
        }
        Map<String, TypedSpec> env = new LinkedHashMap<>();
        for (int i = 0; i < args.size(); i++) {
            env.put(lam.parameters().get(i), args.get(i));
        }
        return reduceStatements(lam.body(), env);
    }

    // =====================================================================
    // The rewriter — exhaustive over the sealed vocabulary (javac-enforced)
    // =====================================================================

    private TypedSpec rewrite(TypedSpec n, Map<String, TypedSpec> env) {
        if (hook != null) {
            TypedSpec h = hook.apply(n, bound.keySet());
            if (h != n) {
                return rewrite(h, env);
            }
        }
        return switch (n) {
            case TypedUserCall uc -> inlineCall(uc, env);

            case TypedEval ev -> {
                TypedSpec fn = rewrite(ev.fn(), env);
                List<TypedSpec> args = list(ev.args(), env);
                yield fn instanceof TypedLambda lam
                        ? reduceEval(ev, args, lam)
                        : new TypedEval(fn, args, ev.info());
            }

            case TypedVariable v -> {
                TypedSpec r = env.get(v.name());
                if (r == null) {
                    yield v;
                }
                // An α-rename preserves the OCCURRENCE's own info; a real
                // substitution splices the argument/let expression verbatim.
                yield r instanceof TypedVariable rv
                        ? new TypedVariable(rv.name(), v.info())
                        : r;
            }

            // Postprocessor CONFIG is consumed STRUCTURALLY at the plan
            // seam (mapper extraction reads schema()/getTable() call
            // shapes) — inside the property, VARIABLES still substitute
            // (the frame's bindings must reach the extraction) but USER
            // CALLS STAND, so the corpus's recursive getSchema/getTable
            // helpers never hit the recursion wall (the execute()-runtime
            // orchestration-position rule, one property deeper).
            case com.legend.compiler.spec.typed.TypedNewInstance ni
                    when ni.properties().containsKey(
                            "queryPostProcessorsWithParameter")
                    && !configMode -> {
                var props = new LinkedHashMap<String, TypedSpec>();
                for (var pe : ni.properties().entrySet()) {
                    if ("queryPostProcessorsWithParameter"
                            .equals(pe.getKey())) {
                        configMode = true;
                        try {
                            props.put(pe.getKey(),
                                    rewrite(pe.getValue(), env));
                        } finally {
                            configMode = false;
                        }
                    } else {
                        props.put(pe.getKey(), rewrite(pe.getValue(), env));
                    }
                }
                yield new com.legend.compiler.spec.typed.TypedNewInstance(
                        ni.classFqn(), props, ni.info());
            }

            // BINDERS — α-fresh inside inlined bodies (env non-empty),
            // untouched at the query's own level.
            case TypedLambda l -> lambda(l, env);
            // match is STATICALLY DISPATCHED (the checker picked the branch)
            // — the node IS a β-redex: substitute the input (and the extra
            // argument) into the chosen body and the match disappears; the
            // lowerer never needs a match arm.
            case TypedMatch m -> {
                TypedSpec input = rewrite(m.input(), env);
                Optional<TypedSpec> extra = m.extra().map(e -> rewrite(e, env));
                Map<String, TypedSpec> inner = new LinkedHashMap<>(env);
                inner.put(m.param(), input);
                if (m.extraParam().isPresent()) {
                    inner.put(m.extraParam().get(), extra.orElse(input));
                }
                yield rewrite(m.body(), inner);
            }
            case TypedLet let -> {
                // Reached only for QUERY-LEVEL lets (callee lets reduce in
                // reduceStatements) and lets inside lambda bodies, which
                // lambda() handles statement-wise. env must not know it.
                if (env.containsKey(let.name())) {
                    throw new IllegalStateException("resolver bug: a let name '"
                            + let.name() + "' collided with an inlining binding");
                }
                yield new TypedLet(let.name(), rewrite(let.value(), env), let.info());
            }

            case TypedNativeCall c -> {
                // execute()'s RUNTIME argument is ORCHESTRATION position
                // (engine: the router evaluates connections outside the
                // planner) — user calls inside it (the corpus's
                // createDbAndGetConnection) stay UNINLINED; buildFrame
                // runs their effects once and treats the value as an
                // opaque handle. Inlining them hits the non-let
                // intermediate-statement wall on their effect bodies.
                if (com.legend.compiler.element.type.PlatformTypes
                        .isExecuteFqn(c.callee().qualifiedName())
                        && c.args().size() >= 3) {
                    List<TypedSpec> keepRt = new ArrayList<>(c.args().size());
                    for (int i = 0; i < c.args().size(); i++) {
                        keepRt.add(i == 2 ? c.args().get(i)
                                : rewrite(c.args().get(i), env));
                    }
                    yield new TypedNativeCall(c.callee(), keepRt, c.info());
                }
                List<TypedSpec> args = list(c.args(), env);
                // HIGHER-ORDER map: substitution revealed a literal lambda
                // where the checker saw a function-valued variable
                // ($f->map($func) — MapChecker emitted the plain call).
                // ONLY an exactly-[1] source β-reduces (map(v[1], f) ≡
                // f(v), pure semantics). A [0..1] source must NOT (audit
                // 22 self-catch): map over EMPTY is EMPTY, but a lambda
                // body NON-STRICT in its param (a constant, if with a
                // constant branch, isEmpty itself) would manufacture a
                // value after β-reduction — silent wrong value. [0..1]
                // and to-many sources rebuild the TypedMap construct node
                // the checker would have emitted.
                if ("meta::pure::functions::collection::map"
                        .equals(c.callee().qualifiedName())
                        && args.size() == 2
                        && !(c.args().get(1) instanceof TypedLambda)
                        && args.get(1) instanceof TypedLambda lam
                        && lam.parameters().size() == 1) {
                    // audit 22a H1: the guard reads the POST-substitution
                    // multiplicity — a [1]-DECLARED param fed an
                    // effectively-[0..1] actual (the engine-convention
                    // acceptance) must NOT β-reduce either; the declared
                    // mult lied about emptiness.
                    if (args.get(0).info().multiplicity()
                            instanceof com.legend.compiler.element.type
                                    .Multiplicity.Bounded mb
                            && mb.lower() == 1 && mb.upper() != null
                            && mb.upper() == 1) {
                        Map<String, TypedSpec> inner = new LinkedHashMap<>();
                        inner.put(lam.parameters().get(0), args.get(0));
                        yield reduceStatements(lam.body(), inner);
                    }
                    yield new TypedMap(args.get(0), lam, c.info());
                }
                yield new TypedNativeCall(c.callee(), args, c.info());
            }
            // Resolver OUTPUT vocabulary — never present pre-H; fails loud
            // here on a pipeline reordering rather than silently rebuilding.
            case TypedSerializeGraph sg -> throw new IllegalStateException(
                    "TypedSerializeGraph reached the inliner — it runs BEFORE the store resolver");
            // EVERY other variant is a pure structural rebuild: rewrite the
            // children (a lambda child re-enters through the TypedLambda arm,
            // so α-hygiene stays uniform) and reassemble through the variant's
            // own withChildren inverse — field preservation is the VARIANT's
            // contract, not this walker's. The hand-written arms this replaces
            // dropped TypedAggCol.orderKey and skipped MapReduce strategy
            // lambdas (remediation T2.1). Untouched subtrees keep identity.
            default -> n.mapChildren(k -> rewrite(k, env));
        };
    }

    // =====================================================================
    // Binders and carriers
    // =====================================================================

    /**
     * A lambda under {@code env}: inside an inlined body (non-empty env)
     * every parameter α-renames to a fresh name and body LETS bind
     * statement-wise (renamed too); at the query's own level parameters and
     * let names stay.
     */
    private TypedLambda lambda(TypedLambda l, Map<String, TypedSpec> env) {
        if (env.isEmpty()) {
            l.parameters().forEach(p -> bound.merge(p, 1, Integer::sum));
            try {
                List<TypedSpec> body = new ArrayList<>(l.body().size());
                for (TypedSpec stmt : l.body()) {
                    body.add(rewrite(stmt, env));
                }
                return new TypedLambda(l.parameters(), body, l.info());
            } finally {
                l.parameters().forEach(p -> bound.compute(p,
                        (k, c) -> c == null || c <= 1 ? null : c - 1));
            }
        }
        Map<String, TypedSpec> inner = new LinkedHashMap<>(env);
        var fnType = (com.legend.compiler.element.type.Type.FunctionType) l.info().type();
        List<String> params = new ArrayList<>(l.parameters().size());
        for (int i = 0; i < l.parameters().size(); i++) {
            var p = fnType.params().get(i);
            params.add(bind(l.parameters().get(i), inner,
                    new com.legend.compiler.element.type.ExprType(
                            p.type(), p.multiplicity())));
        }
        List<TypedSpec> body = new ArrayList<>(l.body().size());
        for (TypedSpec stmt : l.body()) {
            if (stmt instanceof TypedLet let) {
                TypedSpec value = rewrite(let.value(), inner);
                String renamed = bind(let.name(), inner, let.value().info());
                body.add(new TypedLet(renamed, value, let.info()));
                continue;
            }
            body.add(rewrite(stmt, inner));
        }
        return new TypedLambda(params, body, l.info());
    }

    /** Fresh-bind {@code name} into {@code scope}; returns the fresh name. */
    private String bind(String name, Map<String, TypedSpec> scope,
            com.legend.compiler.element.type.ExprType info) {
        String renamed = "_i" + fresh++;
        scope.put(name, new TypedVariable(renamed, info));
        return renamed;
    }

    private List<TypedSpec> list(List<TypedSpec> ns, Map<String, TypedSpec> env) {
        List<TypedSpec> out = new ArrayList<>(ns.size());
        for (TypedSpec n : ns) {
            out.add(rewrite(n, env));
        }
        return out;
    }


}
