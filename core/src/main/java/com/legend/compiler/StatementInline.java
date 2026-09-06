// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.PlatformTypes;
import com.legend.compiler.spec.SourceSubst;
import com.legend.model.FunctionDefinition;
import com.legend.model.ImportScope;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * STATEMENT-level &beta;-reduction of user function calls (the front-door
 * sibling of {@link com.legend.compiler.spec.UserCallInliner}): a call whose
 * callee is a PROGRAM &mdash; its body reaches a statement-only call (an
 * execution, a store effect, a verdict) &mdash; cannot become one
 * expression the SQL lowering runs, so the expression inliner leaves it
 * standing. Pure's semantics for the call are the callee's
 * statements evaluated in order under the parameter bindings; this pass
 * spells exactly that into the caller's statement list:
 * <pre>
 *   helper($m, 3);                 &lt;helper's statements with $m and 3
 *   let r = helper2($m);      →     substituted, lets renamed&gt;
 *                                  ... let r = &lt;helper2's last statement&gt;;
 * </pre>
 * Rules: (1) only a statement-root call or a let-bound call expands
 * (calls nested in expressions are the expression inliner's); (2) only a
 * PROGRAM expands &mdash; a callee whose body reaches a statement-only
 * call ({@link PlatformTypes#isStatementOnly}: an execution, a store
 * effect, a test-data generator, a verdict), directly or through another
 * program; a value function stays with the expression inliner, generics
 * and all, and a platform-owned verdict ({@link
 * PlatformTypes#isVerdictFunction}) is never spliced &mdash; the statement
 * channel adjudicates its call, its Pure body never runs; (3) parameters substitute (&beta;, the expression inliner's rule) and
 * every let the callee introduces is renamed to a fresh
 * {@code _s&lt;N&gt;_&lt;name&gt;} through the body, so the caller's
 * single-assignment scope never collides;
 * (4) spliced statements expand recursively; a call cycle leaves the
 * inner call standing (the expression inliner's loud wall names it).
 * Callee bodies come from the module (already name-resolved under their
 * own imports), so splicing under the caller's scope resolves the same
 * referents. The callee is identified by exact FQN (a bare name resolves
 * through the caller's wildcard imports, first match wins only when it is
 * unique) and arity.
 */
public final class StatementInline {

    private StatementInline() {
    }

    public static List<ValueSpecification> rewrite(List<ValueSpecification> statements,
            ImportScope imports, ModelContext ctx) {
        return new StatementInline.Pass(imports, ctx).expand(statements, new ArrayDeque<>());
    }

    private static final class Pass {
        private final ImportScope imports;
        private final ModelContext ctx;
        /** Every binder minted so far, in order: the fresh-name ledger (its
         * size is the next index; names never repeat within a rewrite). */
        private final List<String> minted = new ArrayList<>();

        Pass(ImportScope imports, ModelContext ctx) {
            this.imports = imports;
            this.ctx = ctx;
        }

        List<ValueSpecification> expand(List<ValueSpecification> statements,
                Deque<String> stack) {
            List<ValueSpecification> out = new ArrayList<>(statements.size());
            for (ValueSpecification st : statements) {
                CString letName = SourceSubst.letName(st);
                ValueSpecification callSite = letName == null ? st
                        : ((AppliedFunction) st).parameters().get(1);
                FunctionDefinition callee = callSite instanceof AppliedFunction af
                        && SourceSubst.letName(af) == null ? sequenceCallee(af) : null;
                if (callee == null || stack.contains(callee.qualifiedName())) {
                    out.add(st);
                    continue;
                }
                AppliedFunction call = (AppliedFunction) callSite;
                Map<String, ValueSpecification> env = new LinkedHashMap<>();
                // β: parameter occurrences become the argument expressions
                // (the expression inliner's rule — a lambda literal types
                // only in a call position, and a substituted argument lets
                // static folding see the call site's shape)
                for (int i = 0; i < callee.parameters().size(); i++) {
                    env.put(callee.parameters().get(i).name(), call.parameters().get(i));
                }
                List<ValueSpecification> body = new ArrayList<>(callee.body().size());
                for (ValueSpecification s : callee.body()) {
                    CString ln = SourceSubst.letName(s);
                    if (ln == null) {
                        body.add(SourceSubst.substitute(s, env));
                        continue;
                    }
                    AppliedFunction let = (AppliedFunction) s;
                    String renamed = freshName(ln.value());
                    ValueSpecification value = SourceSubst.substitute(
                            let.parameters().get(1), env);
                    body.add(let.withParameters(List.of(new CString(renamed, ln.pos()), value)));
                    env.put(ln.value(), new Variable(renamed, null, null, ln.pos()));
                }
                stack.push(callee.qualifiedName());
                List<ValueSpecification> spliced = expand(body, stack);
                stack.pop();
                if (letName == null) {
                    out.addAll(spliced);
                    continue;
                }
                // a let-bound call: the callee's value is its last statement
                // (a trailing let IS its value, real pure)
                ValueSpecification last = spliced.remove(spliced.size() - 1);
                out.addAll(spliced);
                CString lastLet = SourceSubst.letName(last);
                if (lastLet != null) {
                    out.add(last);
                    last = new Variable(lastLet.value(), null, null, lastLet.pos());
                }
                out.add(((AppliedFunction) st).withParameters(List.of(letName, last)));
            }
            return out;
        }

        private String freshName(String name) {
            minted.add(name);
            return "_s" + minted.size() + "_" + name;
        }

        /** The callee when {@code af} calls a user function that is a
         * PROGRAM (its body reaches a statement-only call) and not a
         * platform-owned verdict, else null. */
        private @com.legend.Nullable FunctionDefinition sequenceCallee(AppliedFunction af) {
            FunctionDefinition fd;
            if (af.function().contains("::")) {
                fd = definition(af.function(), af.parameters().size());
            } else {
                fd = null;
                for (String pkg : imports.wildcards()) {
                    String fqn = pkg + "::" + af.function();
                    if (ctx.findFunction(fqn).isEmpty()
                            && ctx.findFunctionDefinitions(fqn).isEmpty()) {
                        continue;
                    }
                    if (fd != null) {
                        return null; // ambiguous: name resolution's wall
                    }
                    fd = definition(fqn, af.parameters().size());
                    if (fd == null) {
                        return null; // platform-owned, or no such overload
                    }
                }
            }
            return fd == null || fd.body().isEmpty()
                    || PlatformTypes.isVerdictFunction(fd.qualifiedName())
                    || !isProgram(fd) ? null : fd;
        }

        /** The ONE parsed definition of {@code fqn} at {@code arity}; null
         * when the platform owns the FQN (a native is registered under it
         * &mdash; the model's Pure bodies for it are documentation), when
         * no overload has that arity, or when several do. */
        private @com.legend.Nullable FunctionDefinition definition(String fqn, int arity) {
            if (ctx.findFunction(fqn).stream().anyMatch(
                    com.legend.compiler.element.TypedFunction::isNative)) {
                return null;
            }
            FunctionDefinition found = null;
            for (FunctionDefinition fd : ctx.findFunctionDefinitions(fqn)) {
                if (fd.parameters().size() != arity) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = fd;
            }
            return found;
        }

        /** A function is a program when its OWN statements reach a
         * statement-only call &mdash; directly, never through another user
         * function: a value function that merely calls a program somewhere
         * below is still a value function to its caller (its standing call
         * takes the executor's call-frame route), and a library value
         * function whose rarely-taken arm executes must never be opened
         * statement by statement on a caller's behalf. */
        private boolean isProgram(FunctionDefinition fd) {
            return fd.body().stream().anyMatch(this::reachesStatementOnly);
        }

        /** The catalog FQNs a call name denotes: an exact FQN, or for a
         * bare name the natives the catalog's bare-name index holds at
         * the call's arity (the typer's own resolution of a bare native). */
        private static List<String> referents(AppliedFunction af) {
            if (af.function().contains("::")) {
                return List.of(af.function());
            }
            return com.legend.builtin.Pure.nativeFunctionsAt(af.function()).stream()
                    .filter(n -> n.parameters().size() == af.parameters().size())
                    .map(com.legend.model.NativeFunctionDefinition::qualifiedName)
                    .toList();
        }

        private boolean reachesStatementOnly(ValueSpecification v) {
            if (v instanceof AppliedFunction af) {
                if (referents(af).stream().anyMatch(PlatformTypes::isStatementOnly)) {
                    return true;
                }
            }
            return v.children().stream().anyMatch(this::reachesStatementOnly);
        }
    }
}
