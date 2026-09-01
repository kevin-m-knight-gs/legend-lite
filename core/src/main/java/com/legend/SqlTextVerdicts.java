// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedEnumValue;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPackageableRef;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.exec.ExecutionResult;
import com.legend.exec.SqlReplayOracle;
import com.legend.exec.SqlTextEmission;

import java.util.List;

/**
 * THE SQL-TEXT VERDICT ARM (SQLTEXT_ROW_VERDICT_CHARTER §3-§4, slice
 * 3a): a statement-root {@code assertEquals(golden, toSQLString({|q},
 * mapping, dialect, ext))} judges on ROWS, never on text — golden text
 * is H2-flavored, we execute DuckDB, so identical text proves the
 * emitter's spelling, not the answer (§0). Detection is typed-node +
 * exact callee FQN navigation of the assert's argument trees (§3.4;
 * never text sniffing); the producer node's CHILDREN are the
 * structured inputs. Four artifacts (§3.5): OUR TEXT and GOLDEN TEXT
 * by ordinary evaluation of the two sides; OUR ROWS by executing the
 * producer's own query lambda through the ONE router
 * ({@code evalValue} — the lambda wraps in {@code from(mapping)} with
 * the env's runtime, Appendix A); GOLDEN ROWS via the
 * {@link SqlReplayOracle} SPI on ExecEnv. Text match/diff is a CENSUS
 * number ({@link SqlTextEmission}); a row divergence FAILS whatever
 * the text said; an oracle decline (foreign dialects — no reference
 * database) keeps TEXT as the contract, hard pass/fail, counted (§4).
 * Production registers no oracle and this arm WALLS loudly (§2).
 *
 * <p>Eval-ledger tenet argument: NO evaluation here — both texts, our
 * rows, and the golden replay all compute in the database through
 * existing routes; this class navigates typed trees, sequences the
 * four derivations, and judges outcomes (Clause 2c judgment, the
 * AssertVerdicts discipline). Shapes outside the exact cohort return
 * null and keep their current path — never a guess.
 */
final class SqlTextVerdicts {

    private SqlTextVerdicts() {
    }

    /** Null = not this arm's shape (the caller's generic path
     * continues); otherwise the verdict. */
    static @com.legend.Nullable ExecutionResult tryArm(String name,
            boolean wantEqual, List<TypedSpec> args,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook) {
        if (!wantEqual || args.size() < 2) {
            return null;
        }
        TypedNativeCall p0 = findProducer(args.get(0), letPrefix);
        TypedNativeCall p1 = findProducer(args.get(1), letPrefix);
        if ((p0 == null) == (p1 == null)) {
            if (p0 == null) {
                // no toSQLString producer anywhere: the exec-sql-read
                // spelling (§8.3c) is the remaining owned shape
                return tryArmExecRead(name, args, letPrefix, specs, env,
                        hook);
            }
            // two producers: not the golden-vs-render shape
            return null;
        }
        TypedSpec producerSide = p0 != null ? args.get(0) : args.get(1);
        TypedSpec goldenSide = p0 != null ? args.get(1) : args.get(0);
        TypedNativeCall producer = p0 != null ? p0 : p1;
        // the producer's CHILDREN are the structured inputs (§3.4):
        // query lambda, mapping ref, dialect. Anything else is a shape
        // this arm does not own yet.
        if (producer.args().size() < 3
                || !(producer.args().get(0) instanceof TypedLambda lam)
                || lam.body().size() != 1
                || !(producer.args().get(1)
                        instanceof TypedPackageableRef mapping)
                || !(producer.args().get(2) instanceof TypedEnumValue db)) {
            return null;
        }
        // OUR TEXT + GOLDEN TEXT: ordinary evaluation, the one router
        String golden = scalarString(StatementExecutor.evalValue(
                goldenSide, letPrefix, specs, env, null, false, hook));
        String ours = scalarString(StatementExecutor.evalValue(
                producerSide, letPrefix, specs, env, null, false, hook));
        if (golden == null || ours == null) {
            return null;
        }
        // from here every path is THIS arm's verdict — the marker lets
        // the dual-channel probe bucket walk-vs-arm outcomes as the
        // DESIGNED text-vs-rows divergence, never pinned disagreement
        SqlTextEmission.armFired();
        boolean textEqual = golden.equals(ours);
        SqlReplayOracle oracle = env.replayOracle();
        if (oracle == null) {
            // §2: no oracle registered = no goldens exist here — WALL
            throw new com.legend.error.NotImplementedException(
                    "sql-text assert verdict needs a replay oracle and"
                            + " none is registered on this env (correct"
                            + " outside tests: there are no goldens)");
        }
        if (!"H2".equals(db.value())) {
            // §4 FOREIGN-DIALECT residue: no oracle database for this
            // dialect — text stays the contract, counted forever
            SqlTextEmission.textVerdict("foreign-dialect " + db.value());
            return textEqual ? ok()
                    : fail(name + " (sql-text, " + db.value()
                            + " — text is the contract): expected "
                            + golden + ", got " + ours);
        }
        // OUR ROWS (§3.5c): the referee executes the producer's own
        // query — mapping from the producer, runtime from the env
        return rowsLegAndVerdict(name, golden, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries.fromWrapped(
                        lam.body().get(0), mapping),
                mapping.fullPath(), rootClassFqn(lam), letPrefix, specs,
                env, hook);
    }

    /** SQLTEXT charter §8.3b — the ROOT arm for
     * {@code assertSameSQL(golden, $result)} (the ~750-test
     * assert-form cohort): the statement root reaches the verdict
     * layer PRE-inline, so the arm owns the whole shape. OUR TEXT =
     * the minted {@code sqlRemoveFormatting($result)} (the envelope
     * splice folds it to the frame's EXECUTED SQL); OUR ROWS = the
     * minted {@code $result.values} (the splice swaps in the frame's
     * typed chain); golden rows + §6/§7 compare via the oracle SPI.
     * Same verdict policy as the toSQLString arm. Null = not the
     * simple shape (the String-overload spelling, extra args) — the
     * current path keeps it. */
    static @com.legend.Nullable ExecutionResult tryArmSameSql(
            com.legend.compiler.spec.typed.TypedUserCall root,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook) {
        if (root.args().size() != 2) {
            return null;
        }
        TypedSpec goldenSide = root.args().get(0);
        TypedSpec resultArg = root.args().get(1);
        if (resultArg.info().type()
                == com.legend.compiler.element.type.Type.Primitive.STRING) {
            // the assertSameSQL(String, String) overload is ordinary
            // string comparison — not this arm's shape
            return null;
        }
        TypedSpec strip = com.legend.compiler.spec.VerdictQueries
                .sqlStripRead(resultArg, env.ctx());
        if (strip == null) {
            return null;
        }
        String golden = scalarString(StatementExecutor.evalValue(
                goldenSide, letPrefix, specs, env, null, false, hook));
        String ours = scalarString(StatementExecutor.evalValue(
                strip, letPrefix, specs, env, null, false, hook));
        if (golden == null || ours == null) {
            return null;
        }
        SqlTextEmission.armFired();
        boolean textEqual = golden.equals(ours);
        SqlReplayOracle oracle = env.replayOracle();
        if (oracle == null) {
            throw new com.legend.error.NotImplementedException(
                    "sql-text assert verdict needs a replay oracle and"
                            + " none is registered on this env (correct"
                            + " outside tests: there are no goldens)");
        }
        return rowsLegAndVerdict("assertSameSQL", golden, ours, textEqual,
                oracle, com.legend.compiler.spec.VerdictQueries
                        .valuesRead(resultArg),
                null, null, letPrefix, specs, env, hook);
    }

    /** SQLTEXT charter §8.3c — the EXEC-SQL-READ arm (the ~700-test
     * cohort): {@code assertEquals(golden, sqlRemoveFormatting($res))}
     * where the test's OWN code reads the SQL out of an executed
     * Result. Detection is the same typed-node + exact-FQN discipline
     * (§3.4): a let-aware walk finds the {@code sql}/{@code
     * sqlRemoveFormatting} USER call whose first argument is
     * Result-typed (the String overload is ordinary string code and
     * never matches). ONLY the first-statement forms are owned —
     * {@code sql($res, n)} with n&gt;0 names the n-th activity's SQL,
     * and pairing that golden against the frame's RESULT rows would
     * judge the wrong statement, so those shapes return null and stay
     * counted on their current path. OUR TEXT = the whole actual side
     * evaluated as written (any wrapping string code runs in the DB);
     * OUR ROWS = the frame's values (the splice's typed chain); golden
     * rows + verdict via the SHARED tail. */
    private static @com.legend.Nullable ExecutionResult tryArmExecRead(
            String name, List<TypedSpec> args, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook) {
        com.legend.compiler.spec.typed.TypedUserCall r0 =
                findSqlRead(args.get(0), letPrefix);
        com.legend.compiler.spec.typed.TypedUserCall r1 =
                findSqlRead(args.get(1), letPrefix);
        if ((r0 == null) == (r1 == null)) {
            return null;
        }
        TypedSpec producerSide = r0 != null ? args.get(0) : args.get(1);
        TypedSpec goldenSide = r0 != null ? args.get(1) : args.get(0);
        com.legend.compiler.spec.typed.TypedUserCall read =
                r0 != null ? r0 : r1;
        TypedSpec resultArg = read.args().get(0);
        String golden = scalarString(StatementExecutor.evalValue(
                goldenSide, letPrefix, specs, env, null, false, hook));
        String ours = scalarString(StatementExecutor.evalValue(
                producerSide, letPrefix, specs, env, null, false, hook));
        if (golden == null || ours == null) {
            return null;
        }
        SqlTextEmission.armFired();
        boolean textEqual = golden.equals(ours);
        SqlReplayOracle oracle = env.replayOracle();
        if (oracle == null) {
            throw new com.legend.error.NotImplementedException(
                    "sql-text assert verdict needs a replay oracle and"
                            + " none is registered on this env (correct"
                            + " outside tests: there are no goldens)");
        }
        return rowsLegAndVerdict(name, golden, ours, textEqual, oracle,
                com.legend.compiler.spec.VerdictQueries
                        .valuesRead(resultArg),
                null, null, letPrefix, specs, env, hook);
    }

    /** The exec-sql-read producer node: a {@code sql($res)} /
     * {@code sqlRemoveFormatting($res)} USER call (exact splice FQNs)
     * over a Result-typed receiver, first-statement form only
     * (1 argument, or 2 with a literal 0). LET-AWARE like
     * {@link #findProducer}. */
    private static @com.legend.Nullable
            com.legend.compiler.spec.typed.TypedUserCall findSqlRead(
            TypedSpec t, List<TypedSpec> letPrefix) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(t);
        java.util.Set<String> seenVars = new java.util.HashSet<>();
        while (!work.isEmpty()) {
            TypedSpec cur = work.poll();
            if (cur instanceof com.legend.compiler.spec.typed
                    .TypedUserCall uc) {
                String fqn = uc.callee().qualifiedName();
                if ((fqn.equals(com.legend.compiler.spec
                                .ResultEnvelopeSplice.SQL_FQN)
                        || fqn.equals(com.legend.compiler.spec
                                .ResultEnvelopeSplice
                                .SQL_REMOVE_FORMATTING_FQN))
                        && !uc.args().isEmpty()
                        && uc.args().get(0).info().type()
                                != com.legend.compiler.element.type.Type
                                        .Primitive.STRING
                        && (uc.args().size() == 1
                                || uc.args().size() == 2
                                        && uc.args().get(1) instanceof
                                        com.legend.compiler.spec.typed
                                        .TypedCInteger k
                                        && k.value().longValue() == 0)) {
                    return uc;
                }
            }
            if (cur instanceof com.legend.compiler.spec.typed
                    .TypedVariable tv && seenVars.add(tv.name())) {
                for (TypedSpec p : letPrefix) {
                    if (p instanceof com.legend.compiler.spec.typed
                            .TypedLet tl && tl.name().equals(tv.name())) {
                        work.add(tl.value());
                    }
                }
            }
            work.addAll(cur.children());
        }
        return null;
    }

    /** The SHARED rows leg + verdict policy (§3.5c-§3.7): evaluate
     * {@code rowsRead} through the one router (REFEREE-CLASS
     * execution — wire-census suspended, save/restore), replay the
     * golden via the oracle, judge. Rows match → pass (text →
     * emission census); rows diverge → FAIL whatever the text said;
     * rows leg underivable or oracle declined → TEXT is the contract,
     * counted. */
    private static ExecutionResult rowsLegAndVerdict(String name,
            String golden, String ours, boolean textEqual,
            SqlReplayOracle oracle, TypedSpec rowsRead,
            @com.legend.Nullable String mappingFqn,
            @com.legend.Nullable String classFqn,
            List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env,
            AssertVerdicts.@com.legend.Nullable SpliceHook hook) {
        ExecutionResult rows;
        boolean priorSuspend = com.legend.exec.SqlTypeCensus
                .probeSuspended();
        try {
            com.legend.exec.SqlTypeCensus.probeSuspend(true);
            rows = StatementExecutor.evalValue(rowsRead,
                    letPrefix, specs, env, null, false, hook);
        } catch (RuntimeException e) {
            // the rows leg is underivable — counted, text stays the
            // contract (§3.7: a counted decline, visible, never silent;
            // DataError joined RuntimeException at the seam)
            SqlTextEmission.textVerdict("our-rows-underivable: "
                    + String.valueOf(e.getMessage()).replace('\n', ' '));
            return textEqual ? ok()
                    : fail(name + " (sql-text, rows underivable):"
                            + " expected " + golden + ", got " + ours);
        } finally {
            com.legend.exec.SqlTypeCensus.probeSuspend(priorSuspend);
        }
        if (rows == null) {
            SqlTextEmission.textVerdict("our-rows-underivable: null result");
            return textEqual ? ok()
                    : fail(name + " (sql-text, rows underivable):"
                            + " expected " + golden + ", got " + ours);
        }
        SqlReplayOracle.RowVerdict rv = oracle.verify(env.connection(),
                golden, rows, mappingFqn, classFqn, env.ctx());
        return switch (rv.outcome()) {
            case MATCH -> {
                // rows are the verdict (§0); text is a census number
                if (textEqual) {
                    SqlTextEmission.textMatched();
                } else {
                    SqlTextEmission.textDiverged();
                }
                yield ok();
            }
            case DIVERGED -> fail(name + " (sql-text ROW verdict —"
                    + " golden rows vs ours diverged, whatever the text"
                    + " said): " + rv.detail());
            case DECLINED -> {
                // oracle could not answer: text is the contract,
                // decline counted (§3.7)
                SqlTextEmission.textVerdict("oracle-declined: "
                        + rv.detail());
                yield textEqual ? ok()
                        : fail(name + " (sql-text, oracle declined: "
                                + rv.detail() + "): expected " + golden
                                + ", got " + ours);
            }
        };
    }

    /** The producer node in an argument tree, by EXACT callee FQN
     * (§3.4 — never names, never text). LET-AWARE (§3.2): a variable
     * reference chases its {@code letPrefix} binding — the platform
     * keeps lets as lets, so {@code let sql = toSQLString(...);
     * assertEquals(golden, $sql)} carries the producer BEHIND the
     * variable. Null when absent. */
    private static @com.legend.Nullable TypedNativeCall findProducer(
            TypedSpec t, List<TypedSpec> letPrefix) {
        java.util.ArrayDeque<TypedSpec> work = new java.util.ArrayDeque<>();
        work.add(t);
        java.util.Set<String> seenVars = new java.util.HashSet<>();
        while (!work.isEmpty()) {
            TypedSpec cur = work.poll();
            if (cur instanceof TypedNativeCall nc) {
                String fqn = nc.callee().qualifiedName();
                if (fqn.equals(com.legend.compiler.element.type
                                .PlatformTypes.TO_SQL_STRING)
                        || fqn.equals(com.legend.compiler.element.type
                                .PlatformTypes.TO_SQL_STRING_PRETTY)) {
                    return nc;
                }
            }
            if (cur instanceof com.legend.compiler.spec.typed
                    .TypedVariable tv && seenVars.add(tv.name())) {
                for (TypedSpec p : letPrefix) {
                    if (p instanceof com.legend.compiler.spec.typed
                            .TypedLet tl && tl.name().equals(tv.name())) {
                        work.add(tl.value());
                    }
                }
            }
            work.addAll(cur.children());
        }
        return null;
    }

    /** The query's root class when it returns instances (drives the
     * oracle's per-property enum decode); null for relation-shaped
     * results. */
    private static @com.legend.Nullable String rootClassFqn(
            TypedLambda lam) {
        return lam.body().get(lam.body().size() - 1).info().type()
                instanceof com.legend.compiler.element.type.Type
                        .ClassType ct
                ? ct.fqn() : null;
    }

    private static @com.legend.Nullable String scalarString(
            @com.legend.Nullable ExecutionResult r) {
        return r instanceof ExecutionResult.Scalar s
                && s.value() instanceof String str ? str : null;
    }

    private static ExecutionResult ok() {
        return new ExecutionResult.Scalar(Boolean.TRUE,
                com.legend.compiler.element.type.Type.Primitive.BOOLEAN);
    }

    private static ExecutionResult fail(String message) {
        // the seam: verdicts speak the platform vocabulary
        throw new com.legend.error.AssertFailed(message);
    }
}
