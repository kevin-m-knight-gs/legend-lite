// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend;

import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedLet;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.exec.ExecutionResult;
import com.legend.exec.VerdictBatch;
import java.util.List;
import java.util.Map;

/**
 * THE BLOCK COMPILER, stage 1 (2026-09-21; docs/BLOCK_COMPILER_HOMEWORK_2026_09_21.md
 * §13). A PURE test body — non-effect lets and assert-family statement roots, the
 * last an assert — is compiled to its ARTIFACT before anything of it runs: the
 * frames (CTE definitions) and the verdict rows on the batch, one fused statement
 * per connection, the appeals attached. {@link #run} only sends it. The walk is the
 * executor's own arms in the executor's own order, so the artifact is byte-identical
 * to what the statement-by-statement loop produced (the ladder pins it); what moves
 * is the seam — nothing is planned once running has begun.
 *
 * <p>Provisioning precedes planning: a statement's execution contexts are
 * established (the seeding boundary: a runtime's declared setups, a from()'s inline
 * data) before its frame is planned, because a frame's reported wire types are read
 * from the seeded tables. That is seeding, not evaluation.
 *
 * <p>Measured before this class existed: the arms claim EVERY assert-family root in
 * the corpus (a verdict, a deferred row, or a raise — never a fall-through to host
 * evaluation; 0 unclaimed on both lanes), so {@link #compile} has no host arm and
 * walls loudly if one were ever needed.
 */
final class BodyCompiler {
    private BodyCompiler() {
    }

    /** The artifact of a pure body: its frames and verdict rows on the batch, and the
     * body's VALUE — the last assert's held verdict. */
    record Artifact(VerdictBatch batch, ExecutionResult value) {
    }

    /** A PURE body: every statement but the last is a let without effects (a frame,
     * an alias, a handle, a value binding), the last and every other statement is an
     * assert-family root without effects; no test-data generator anywhere; no
     * assertError (a context owner: it runs its body under a catch). */
    static boolean accepts(List<TypedSpec> stmts, SpecCompiler specs,
            Map<String, Boolean> effectMemo) {
        if (stmts.isEmpty()) {
            return false;
        }
        for (int i = 0; i < stmts.size(); i++) {
            TypedSpec s = stmts.get(i);
            if (StatementExecutor.containsEffect(s, specs, effectMemo)
                    || Compiler.containsTdgGenerator(s)) {
                return false;
            }
            if (s instanceof TypedLet) {
                if (i == stmts.size() - 1) {
                    return false;   // a trailing let IS the body's value: the value position
                }
                continue;
            }
            String fqn = rootCallee(s);
            if (fqn == null
                    || !com.legend.compiler.element.type.PlatformTypes.isVerdictFunction(fqn)
                    || com.legend.builtin.NativeFn.ContextOwner.of(fqn).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private static @com.legend.Nullable String rootCallee(TypedSpec s) {
        if (s instanceof TypedUserCall u) {
            return u.callee().qualifiedName();
        }
        if (s instanceof TypedNativeCall n) {
            return n.callee().qualifiedName();
        }
        return null;
    }

    /** Compile: walk once, plan everything, run nothing. */
    static Artifact compile(List<TypedSpec> stmts, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env0) {
        VerdictBatch batch = StatementExecutor.newVerdictBatch();
        StatementExecutor.ExecEnv env = env0.withVerdictBatch(batch);
        Map<String, StatementExecutor.ExecFrame> execFrames = new java.util.LinkedHashMap<>();
        ExecutionResult value = null;
        for (int i = 0; i < stmts.size(); i++) {
            // TDG lane S1: the checker's census CARRIER folds to instance literals
            // before the statement is planned (orchestration owns testdatagen)
            TypedSpec stmt = com.legend.testdatagen.TestDataGenerationNatives.foldCensus(
                    stmts.get(i), env.ctx(), env.connection(), letPrefix, StatementExecutor.ENGINE_TEXT);
            StatementExecutor.establishContexts(stmt, env);
            if (stmt instanceof TypedLet let) {
                StatementExecutor.ExecFrame alias = StatementExecutor.aliasFrame(let.value(), execFrames);
                if (alias != null) {
                    execFrames.put(let.name(), alias);
                    continue;
                }
                TypedSpec rhs = let.value();
                while (rhs instanceof com.legend.compiler.spec.typed.TypedFrom rf) {
                    rhs = rf.source();
                }
                if (rhs instanceof TypedNativeCall ec
                        && (com.legend.builtin.NativeFn.Handle.isExecute(ec.callee().qualifiedName())
                            || com.legend.builtin.NativeFn.Handle.of(ec.callee().qualifiedName()).orElse(null)
                                    == com.legend.builtin.NativeFn.Handle.EXECUTE_LEGEND_QUERY)) {
                    // a FRAME: planned here, its CTE defined on the batch when a reader
                    // splices it (rung 12: a plain class frame as its root rows)
                    execFrames.put(let.name(),
                            StatementExecutor.buildFrame(ec, letPrefix, true, specs, env));
                    continue;
                }
                // a HANDLE or a value binding: rows under its scope, the let rides the prefix
                PlanAllocations.registerHandlesIn(let.name(), rhs, letPrefix, specs, env);
                letPrefix.add(let);
                continue;
            }
            ExecutionResult verdict = AssertVerdicts.tryAdjudicate(stmt, letPrefix, specs,
                    StatementExecutor.frameReplaceEnv(stmt, execFrames, env, letPrefix, specs),
                    StatementExecutor.spliceHook(execFrames, letPrefix, specs, env));
            if (verdict == null) {
                throw new IllegalStateException("block compiler: an assert root the arms did not"
                        + " claim (measured 0 in the corpus): " + rootCallee(stmt));
            }
            value = verdict;
        }
        return new Artifact(batch, java.util.Objects.requireNonNull(value, "a pure body ends in an assert"));
    }

    /** Run: send the artifact (one fused statement per connection; appeals on failed
     * rows; the first failure raises), return the body's value. */
    static ExecutionResult run(Artifact artifact, StatementExecutor.ExecEnv env0) {
        AssertVerdicts.flush(artifact.batch(), env0.withVerdictBatch(artifact.batch()));
        return artifact.value();
    }
}
