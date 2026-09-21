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
 * THE BLOCK COMPILER, stages 1–2 (2026-09-21; docs/BLOCK_COMPILER_HOMEWORK_2026_09_21.md
 * §13, §18). A test body WITHOUT EFFECTS — lets, assert-family roots (a verdict call,
 * a quantified map / forAll, an if over asserts), helper calls and value statements
 * — is compiled to its ARTIFACT before anything of it runs: the frames (CTE
 * definitions) and the verdict rows on the batch, one fused statement per
 * connection, the appeals attached; the value statements PREPARED (helper inlining,
 * native staging, store resolution — the executor's own compile phases, shared:
 * {@link StatementExecutor#prepareValue}); the FRAGMENT MAP naming the let / assert
 * every frame and verdict branch came from. {@link #run} only sends it. The walk is
 * the executor's own arms in the executor's own order, so the artifact is
 * byte-identical to what the statement-by-statement loop produced (the ladder pins
 * it); what moves is the seam — nothing is planned once running has begun.
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
public final class BodyCompiler {
    private BodyCompiler() {
    }

    /** The artifact of a body: its frames and verdict rows on the batch (with the
     * fragment map), its value statements PREPARED in body order, and the body's
     * VALUE — the last statement's: a verdict, or (when {@code lastIsValue}) the
     * last value statement's run result. */
    record Artifact(VerdictBatch batch, @com.legend.Nullable ExecutionResult verdict,
            List<StatementExecutor.PreparedValue> values, boolean lastIsValue,
            Map<String, String> fragments) {
    }

    /** A PURE body: every statement but the last is a let without effects (a frame,
     * an alias, a handle, a value binding), the last and every other statement is an
     * assert-family root without effects; no test-data generator anywhere; no
     * assertError (a context owner: it runs its body under a catch). */
    static boolean accepts(List<TypedSpec> stmts, SpecCompiler specs,
            Map<String, Boolean> effectMemo) {
        String why = refusal(stmts, specs, effectMemo);
        (why == null ? ACCEPTED : REFUSALS.computeIfAbsent(why,
                k -> new java.util.concurrent.atomic.LongAdder())).increment();
        return why == null;
    }

    /** CENSUS (stage 2 measurement, 2026-09-21): bodies compiled as one artifact, and
     * the bodies the compiler REFUSED by reason — read by the corpus lanes only. */
    static final java.util.concurrent.atomic.LongAdder ACCEPTED =
            new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.LongAdder> REFUSALS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static long acceptedCount() {
        return ACCEPTED.sum();
    }

    public static Map<String, Long> refusals() {
        Map<String, Long> out = new java.util.TreeMap<>();
        REFUSALS.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    /** Why the body is not compiled as one artifact yet, or null. Stage 2 accepts
     * every statement without effects: lets (a trailing let is the body's value),
     * assert-family roots, helper calls (inlined at compile time), value statements
     * (prepared at compile time, run at the artifact's run). Refused: a test-data
     * generator, an effect (stage 3: scripts), a context owner (assertError runs its
     * body under an arm's catch — a run inside the walk), a frame forced at value
     * position (execute as a statement: its eager run IS the value). */
    static @com.legend.Nullable String refusal(List<TypedSpec> stmts, SpecCompiler specs,
            Map<String, Boolean> effectMemo) {
        if (stmts.isEmpty()) {
            return "empty";
        }
        for (TypedSpec s : stmts) {
            // a let's EFFECT is its value's (the loop reads it the same way: an
            // executeInDb binding runs at once, never rides the prefix)
            TypedSpec v = s instanceof TypedLet l ? l.value() : s;
            if (Compiler.containsTdgGenerator(s) || Compiler.containsTdgGenerator(v)) {
                return "tdg";
            }
            if (StatementExecutor.containsEffect(s, specs, effectMemo)
                    || StatementExecutor.containsEffect(v, specs, effectMemo)) {
                return s instanceof TypedLet ? "effect-let" : "effect";
            }
            String fqn = rootCallee(v);
            if (fqn != null && com.legend.builtin.NativeFn.ContextOwner.of(fqn).isPresent()) {
                return "context-owner";
            }
            if (!(s instanceof TypedLet) && com.legend.builtin.NativeFn.Handle.forcesAtValuePosition(fqn)) {
                return "value-frame";
            }
            // an UNPORTED native at a statement root (typed from the prelude, in no
            // family, no core function — createTempTable): it has no body here and
            // the loop is loud at its evaluation; the compiler must not plan past it
            // (a later raw-grid read probes the state it would have changed)
            if (fqn != null && v instanceof TypedNativeCall n && !implemented(n)) {
                return "unported-native:" + fqn;
            }
        }
        return null;
    }

    /** THE IMPLEMENTED SURFACE, the claim registry's own question (Claims: a family
     * member, a scalar rule / reducer / window function by signature key, a core
     * function by bare name); a walled native is refused by decision. */
    private static boolean implemented(TypedNativeCall n) {
        String fqn = n.callee().qualifiedName();
        String key = n.callee().signatureKey();
        String bare = fqn.substring(fqn.lastIndexOf(':') + 1);
        if (com.legend.builtin.Pure.walledNativeFqns().contains(fqn)) {
            return false;
        }
        return com.legend.compiler.element.type.PlatformTypes.isVerdictFunction(fqn)
                || com.legend.builtin.NativeFn.claims(fqn)
                || com.legend.lowering.RegistryKeys.scalarRules().contains(key)
                || com.legend.lowering.RegistryKeys.reducers().contains(key)
                || com.legend.lowering.RegistryKeys.windowFunctions().contains(key)
                || com.legend.lowering.RegistryKeys.windowAggregates().contains(key)
                || com.legend.compiler.spec.CoreFn.parseNames().containsKey(bare);
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
        Map<String, String> fragments = new java.util.LinkedHashMap<>();
        List<StatementExecutor.PreparedValue> values = new java.util.ArrayList<>();
        ExecutionResult verdict = null;
        boolean lastIsValue = false;
        for (int i = 0; i < stmts.size(); i++) {
            // TDG lane S1: the checker's census CARRIER folds to instance literals
            // before the statement is planned (orchestration owns testdatagen)
            TypedSpec stmt = com.legend.testdatagen.TestDataGenerationNatives.foldCensus(
                    stmts.get(i), env.ctx(), env.connection(), letPrefix, StatementExecutor.ENGINE_TEXT);
            StatementExecutor.establishContexts(stmt, env);
            boolean last = i == stmts.size() - 1;
            if (stmt instanceof TypedLet let && !last) {
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
                    fragments.put("frame_" + let.name(), "let " + let.name() + " (statement " + (i + 1) + ")");
                    continue;
                }
                // a HANDLE or a value binding: rows under its scope, the let rides the prefix
                PlanAllocations.registerHandlesIn(let.name(), rhs, letPrefix, specs, env);
                letPrefix.add(let);
                continue;
            }
            // a statement root (a trailing let IS its value): the arms claim an
            // assert-family root — a verdict call, a quantified map / forAll, an if
            // over asserts — into deferred rows …
            TypedSpec bare = stmt instanceof TypedLet l ? l.value() : stmt;
            int rowsBefore = batch.pendingCount();
            ExecutionResult v = AssertVerdicts.tryAdjudicate(bare, letPrefix, specs,
                    StatementExecutor.frameReplaceEnv(stmt, execFrames, env, letPrefix, specs),
                    StatementExecutor.spliceHook(execFrames, letPrefix, specs, env));
            if (v != null) {
                nameRows(fragments, batch, rowsBefore, rootCallee(bare), i + 1);
                verdict = v;
                lastIsValue = false;
                continue;
            }
            // … everything else is a VALUE statement, prepared now (a helper call
            // inlines here; an inlined assert root is adjudicated by the preparation)
            StatementExecutor.PreparedValue pv = StatementExecutor.prepareValue(
                    stmt, bare, letPrefix, execFrames, specs, env);
            if (pv.contextOwner() != null) {
                throw new IllegalStateException("block compiler: a context owner reached the"
                        + " compile walk (refused by construction): " + rootCallee(bare));
            }
            if (pv.verdict() != null) {
                nameRows(fragments, batch, rowsBefore, rootCallee(bare), i + 1);
            }
            values.add(pv);
            lastIsValue = true;
        }
        batch.fragments(fragments);
        return new Artifact(batch, verdict, values, lastIsValue, fragments);
    }

    /** The verdict rows an assert root deferred, named in the fragment map. */
    private static void nameRows(Map<String, String> fragments, VerdictBatch batch, int from,
            @com.legend.Nullable String callee, int ordinal) {
        String name = callee == null ? "assert" : callee.substring(callee.lastIndexOf(':') + 1);
        for (int ix = from; ix < batch.pendingCount(); ix++) {
            fragments.put(com.legend.lowering.VerdictSql.INDEX + "=" + ix,
                    name + " (statement " + ordinal + ")");
        }
    }

    /** Run: the value statements in body order, then the artifact's fused statement
     * (one per connection; appeals on failed rows; the first failure raises);
     * return the body's value. */
    static @com.legend.Nullable ExecutionResult run(Artifact artifact, SpecCompiler specs,
            StatementExecutor.ExecEnv env0) {
        ExecutionResult last = null;
        for (StatementExecutor.PreparedValue pv : artifact.values()) {
            last = StatementExecutor.runValue(pv, specs, new java.util.ArrayDeque<>());
        }
        AssertVerdicts.flush(artifact.batch(), env0.withVerdictBatch(artifact.batch()));
        return artifact.lastIsValue() ? last : artifact.verdict();
    }
}
