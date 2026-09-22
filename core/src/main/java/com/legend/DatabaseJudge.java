// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend;
import com.legend.builtin.NativeFn;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedUserCall;
import com.legend.exec.ExecutionResult;
import com.legend.exec.PureAsserts;
import java.util.ArrayList;
import java.util.List;

/**
 * THE DATABASE JUDGE (task #27, 2026-09-21 — split out of AssertVerdicts): both sides
 * of an assert are PLANNED, composed by {@link com.legend.lowering.VerdictSql} into one
 * verdict statement (deferred into the body's batch), and the database returns the
 * verdict row. No value is compared in Java here; an undecidable pair is UNJUDGED,
 * named. The router ({@link AssertVerdicts}) classifies the assert and hands the sides
 * over; the host judge ({@link HostJudge}) is the other arm of the same router.
 */

final class DatabaseJudge {
    private DatabaseJudge() {
    }

    static com.legend.lowering.VerdictSql.GridSide gridSide(com.legend.sql.SqlQuery plan,
            com.legend.compiler.element.type.Type.RelationType schema, boolean csvStrings) {
        List<Boolean> floatCols = new ArrayList<>();
        List<Boolean> emptyIsNull = new ArrayList<>();
        for (var colT : schema.columns()) {
            floatCols.add(colT.type() == com.legend.compiler.element.type.Type.Primitive.FLOAT);
            emptyIsNull.add(csvStrings
                    && colT.type() == com.legend.compiler.element.type.Type.Primitive.STRING);
        }
        return new com.legend.lowering.VerdictSql.GridSide(plan, schema.columns().size(), floatCols,
                emptyIsNull);
    }

    static ExecutionResult databaseVerdict(String name, boolean wantEqual,
            TypedSpec eSpec, TypedSpec aSpec, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook, boolean canonicalOrder,
            boolean cellPool) {
        return databaseVerdict(name, wantEqual, eSpec, aSpec, letPrefix, specs, env, hook,
                canonicalOrder, cellPool, false);
    }

    /** {@code csvStrings}: the pair is judged under toCSV's own equivalence
     * (a String column's empty string and NULL print alike — bucket 8). */
    static ExecutionResult databaseVerdict(String name, boolean wantEqual,
            TypedSpec eSpec, TypedSpec aSpec, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook, boolean canonicalOrder,
            boolean cellPool, boolean csvStrings) {
        KindClass ke = AssertVerdicts.kindKey(eSpec, letPrefix, env);
        KindClass ka = AssertVerdicts.kindKey(aSpec, letPrefix, env);
        boolean anyNil = com.legend.compiler.element.type.PlatformTypes.isNil(eSpec.info().type())
                || com.legend.compiler.element.type.PlatformTypes.isNil(aSpec.info().type());
        boolean anyAny = AssertVerdicts.isAnyStamped(eSpec) || AssertVerdicts.isAnyStamped(aSpec);
        boolean bothGrids = com.legend.compiler.element.type.Type.isRelation(eSpec.info().type())
                && com.legend.compiler.element.type.Type.isRelation(aSpec.info().type());
        if (ke != null && ka != null && !anyNil && !anyAny && !bothGrids && !ke.equals(ka)) {
            if (ke.primitive() && ka.primitive()) {
                // X4: the engine has no cross-kind PRIMITIVE equality — a
                // VERDICT (false), decided statically, the one comparison SQL
                // never sees
                com.legend.exec.CanonicalDivergence.sqlJudgedInDatabase(name);
                return wantEqual ? AssertVerdicts.fail(name + ": kinds differ (" + AssertVerdicts.typeName(eSpec)
                        + " vs " + AssertVerdicts.typeName(aSpec) + ")") : AssertVerdicts.ok();
            }
            // a class / generic side's static class is a declaration, not
            // the value's kind (a supertype stamp over equal instances):
            // leg 3.2's instance canon decides; unjudged until then
            return unjudged(name, "kind-gate: non-primitive", " ("
                    + AssertVerdicts.typeName(eSpec) + " vs " + AssertVerdicts.typeName(aSpec) + ")");
        }
        // the riders plan WITHOUT the canon-text sort: the statement orders
        // (string_agg ORDER BY __c for a multiset form) and a grid's value
        // peer is chunked into rows in its ARRIVAL order — a wrap-time sort
        // would scramble the cells across rows (3.1b lane witness)
        // an enum against an UNTYPED wire ($row.values->at(0), toDomainValue):
        // the carrier holds the NAME and no enumeration — the pair's ONE
        // declared enumeration frames the untyped AssertVerdicts.side (Rule 2: at the
        // boundary the declared kind is assigned; the host compares the names)
        String frameE = null;
        String frameA = null;
        if (!anyNil && anyAny && ke instanceof KindClass.Enum ^ ka instanceof KindClass.Enum) {
            if (ke instanceof KindClass.Enum en) {
                frameA = en.fqn();
            } else if (ka instanceof KindClass.Enum en) {
                frameE = en.fqn();
            }
        }
        var re = new com.legend.exec.CanonRider(false, false, frameE);
        var ra = new com.legend.exec.CanonRider(false, false, frameA);
        // the golden's ^TDSNull() cells spell the bare sentinel (direction-
        // aware: the EXPECTED side only, as TdsCompare.peerElementCanons) —
        // rewritten at plan time so the cell rides the literal channel as
        // the string 'TDSNull' the peer rule maps, never a JSON tree
        eSpec = com.legend.compiler.spec.VerdictQueries.tdsNullSentinel(eSpec);
        StatementExecutor.PlannedValue pe = StatementExecutor.planValue(eSpec, letPrefix, specs, env, re, hook);
        StatementExecutor.PlannedValue pa = StatementExecutor.planValue(aSpec, letPrefix, specs, env, ra, hook);
        // a HOST-CONSTANT side is bound on the OTHER side's database (a
        // literal evaluates anywhere; a planned side reads where its tables
        // are — the system database for a metamodel read)
        java.sql.Connection on = pe.side() != null && !pe.side().storeFree()
                ? pe.side().connection()
                : pa.side() != null && !pa.side().storeFree() ? pa.side().connection()
                : env.connection();
        StatementExecutor.WrappedSide we = pe.side() != null ? pe.side()
                : constantSide(pe.answered(), eSpec, re, false, env, on);
        StatementExecutor.WrappedSide wa = pa.side() != null ? pa.side()
                : constantSide(pa.answered(), aSpec, ra, false, env, on);
        String why = we == null ? "host-value AssertVerdicts.side (expected): " + describe(pe.answered())
                : wa == null ? "host-value AssertVerdicts.side (actual): " + describe(pa.answered())
                : re.declined() != null ? "side-e: " + re.declined()
                : ra.declined() != null ? "side-a: " + ra.declined()
                : we.connection() != wa.connection() && !we.storeFree() && !wa.storeFree()
                        ? "sides on different databases"
                : null;
        // leg 3.1b — a GRID AssertVerdicts.side (the tabular wrap): the statement compares
        // its row canons against the peer's cells chunked by the grid's
        // width (assertEquals) or the loose cell pool (assertSameElements)
        boolean gridE = why == null && re.tdsWrapped();
        boolean gridA = why == null && ra.tdsWrapped();
        if (gridE || gridA) {
            if (gridE && gridA) {
                // two grids: their row canons against each other, the cells
                // walked for the declared-Float leniency when the schemas agree
                var se = AssertVerdicts.effectiveSchema(java.util.Objects.requireNonNull(we));
                var sa = AssertVerdicts.effectiveSchema(java.util.Objects.requireNonNull(wa));
                com.legend.sql.SqlQuery pq;
                if (se != null && sa != null && se.columns().size() == sa.columns().size()) {
                    pq = com.legend.lowering.VerdictSql.gridPair(gridSide(we.plan(), se, csvStrings),
                            gridSide(wa.plan(), sa, csvStrings), canonicalOrder);
                } else {
                    pq = com.legend.lowering.VerdictSql.gridPair(we.plan(), wa.plan(), canonicalOrder);
                }
                return runVerdict(name, wantEqual, pq,
                        we.storeFree() ? wa.connection() : we.connection(), env);
            } else {
                StatementExecutor.WrappedSide gw = gridE ? we : wa;
                com.legend.exec.CanonRider pr = gridE ? ra : re;
                StatementExecutor.WrappedSide pw = gridE ? wa : we;
                var schema = AssertVerdicts.effectiveSchema(java.util.Objects.requireNonNull(gw));
                int width = schema == null ? 0 : schema.columns().size();
                if (width <= 0) {
                    why = "grid side without a schema view";
                } else if (!pr.wrapped() || pr.literalIndex() < 0) {
                    // the peer's own state rides the reason: WHY it has no
                    // literal channel is the bucket's diagnosis
                    why = "tds-peer: no literal channel (peer "
                            + (!pr.wrapped() ? "unwrapped: " + pr.declined()
                                    : "kinds " + pr.kinds() + ", no literal candidate")
                            + ")";
                } else {
                    var grid = gridSide(gw.plan(), java.util.Objects.requireNonNull(schema), csvStrings);
                    boolean peerFloat = pr.kinds().size() == 1
                            ? pr.kinds().get(0) == com.legend.compiler.element.type.Type.Primitive.FLOAT
                            : pr.literalIndex() >= 0 && pr.kinds().get(pr.literalIndex())
                                    == com.legend.compiler.element.type.Type.Primitive.FLOAT;
                    var peer = new com.legend.lowering.VerdictSql.PeerSide(
                            java.util.Objects.requireNonNull(pw).plan(),
                            "__canon" + pr.literalIndex(), !gridE, peerFloat);
                    com.legend.sql.SqlQuery gq = cellPool
                            ? com.legend.lowering.VerdictSql.gridCells(grid, peer, gridE)
                            : com.legend.lowering.VerdictSql.gridRows(grid, peer, gridE,
                                    canonicalOrder);
                    return runVerdict(name, wantEqual, gq,
                            gw.storeFree() ? pw.connection() : gw.connection(), env);
                }
            }
        }
        // a store-free side rides the store-reading side's database
        java.sql.Connection runOn = we != null && wa != null
                ? (we.storeFree() ? wa.connection() : we.connection()) : env.connection();
        int ie = -1;
        int ia = -1;
        if (why == null) {
            boolean literal = !anyNil && (anyAny || re.literalOnly() || ra.literalOnly());
            ie = literal ? re.literalIndex() : 0;
            ia = literal ? ra.literalIndex() : 0;
            int bareE = re.kinds().size() - (re.literalIndex() >= 0 ? 1 : 0);
            int bareA = ra.kinds().size() - (ra.literalIndex() >= 0 ? 1 : 0);
            if (literal && (ie < 0 || ia < 0)) {
                why = "no literal channel";
            } else if (!literal && (bareE > 1 || bareA > 1)) {
                why = "unrefined-number: multi-candidate side";
            }
        }
        if (why != null) {
            return unjudged(name, why);
        }
        com.legend.sql.SqlQuery vq = com.legend.lowering.VerdictSql.equality(
                new com.legend.lowering.VerdictSql.Side(
                        java.util.Objects.requireNonNull(we).plan(), "__canon" + ie,
                        re.many(), canonicalOrder,
                        re.kinds().get(ie) == com.legend.compiler.element.type.Type.Primitive.FLOAT),
                new com.legend.lowering.VerdictSql.Side(
                        java.util.Objects.requireNonNull(wa).plan(), "__canon" + ia,
                        ra.many(), canonicalOrder,
                        ra.kinds().get(ia) == com.legend.compiler.element.type.Type.Primitive.FLOAT));
        return runVerdict(name, wantEqual, vq, runOn, env);
    }

    /** Execute a verdict statement on {@code on} and read its one row:
     * unjudged (counted, the assert fails with the reason), or the verdict
     * (never NULL) with the two framed canons as the message. */
    static ExecutionResult runVerdict(String name, boolean wantEqual,
            com.legend.sql.SqlQuery vq, java.sql.Connection runOn,
            StatementExecutor.ExecEnv env) {
        com.legend.exec.VerdictBatch batch = env.verdictBatch();
        if (batch != null && batch.active()) {
            batch.defer(name, wantEqual, vq, runOn);   // leg 3.4: judged at the flush
            return AssertVerdicts.ok();
        }
        return verdictOf(name, wantEqual, com.legend.exec.VerdictBatch.executeOne(
                name, batch == null ? vq : com.legend.sql.FrameCtes.attach(vq, batch.frames()),
                AssertVerdicts.ONE_ROW, runOn, env.dialect(), env.trace()));
    }

    /** The verdict of one row ({@code verdict, expected, actual, unjudged,
     * lenient}): unjudged (counted, the assert fails with the reason), or
     * the verdict with the two framed canons as the message. */
    static ExecutionResult verdictOf(String name, boolean wantEqual, List<Object> row) {
        if (System.getenv("LEGEND_LITE_DUMP_SQL") != null) {
            // the SQL dump's companion: the verdict row the statement returned
            System.err.println("[verdict] " + name + " verdict=" + row.get(0)
                    + " expected=" + row.get(1) + " actual=" + row.get(2)
                    + " unjudged=" + row.get(3));
        }
        Object unjudged = row.get(3);
        if (unjudged != null) {
            // the evidence columns ride the message (bounded): an accepted
            // divergence matches its witness here, and a row is diagnosable
            // without a re-run
            return unjudged(name, String.valueOf(unjudged),
                    "\nexpected: " + AssertVerdicts.excerpt(row.get(1))
                    + "\nactual:   " + AssertVerdicts.excerpt(row.get(2)));
        }
        if (!(row.get(0) instanceof Boolean held)) {
            throw new IllegalStateException(name + ": the verdict column is not a boolean: " + row.get(0));
        }
        com.legend.exec.CanonicalDivergence.sqlJudgedInDatabase(name);
        if (row.size() > 4 && Boolean.TRUE.equals(row.get(4))) {
            // the declared 2-ULP Float leniency decided it — counted as
            // host mode counts its own firings
            com.legend.exec.CanonicalDivergence.sqlUlpPolicy("database " + name);
        }
        if (held == wantEqual) {
            return AssertVerdicts.ok();
        }
        return AssertVerdicts.fail(wantEqual
                ? name + "\nexpected: " + row.get(1) + "\nactual:   " + row.get(2)
                : "assertNotEquals: both sides are equal");
    }

    static SideRows planSide(TypedSpec spec, boolean expected, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env, @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        return planSide(spec, expected, true, letPrefix, specs, env, hook);
    }

    /** {@code needCanon} false = the family only COUNTS (size / emptiness):
     * a declined canon is not a reason, the plan's rows are. */
    static SideRows planSide(TypedSpec spec, boolean expected, boolean needCanon,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        return planSide(spec, expected, needCanon, false, letPrefix, specs, env, hook);
    }

    /** {@code canonicalJson} = the side's JSON objects are written with
     * their keys sorted (the JSON verdict's plan). */
    static SideRows planSide(TypedSpec spec, boolean expected, boolean needCanon,
            boolean canonicalJson, List<TypedSpec> letPrefix, SpecCompiler specs,
            StatementExecutor.ExecEnv env, @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        return planSide(spec, expected, needCanon, canonicalJson, null, letPrefix, specs, env, hook);
    }

    /** {@code enumFrame} = the pair's declared enumeration framing an untyped
     * or abstract-Enum AssertVerdicts.side (CanonRider.enumFrame). */
    static SideRows planSide(TypedSpec spec, boolean expected, boolean needCanon,
            boolean canonicalJson, @com.legend.Nullable String enumFrame, List<TypedSpec> letPrefix,
            SpecCompiler specs, StatementExecutor.ExecEnv env, @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        var rider = new com.legend.exec.CanonRider(false, canonicalJson, enumFrame);
        TypedSpec s = expected ? com.legend.compiler.spec.VerdictQueries.tdsNullSentinel(spec) : spec;
        StatementExecutor.PlannedValue pv = StatementExecutor.planValue(s, letPrefix, specs, env, rider, hook);
        StatementExecutor.WrappedSide w = pv.side() != null ? pv.side()
                : constantSide(pv.answered(), s, rider, false, env, env.connection());
        if (w == null) {
            return new SideRows(null, rider, "host-value side: " + describe(pv.answered()));
        }
        if (needCanon && rider.declined() != null) {
            return new SideRows(w, rider, "side: " + rider.declined());
        }
        if (needCanon && w.shape() == com.legend.exec.ResultShape.GRAPH) {
            return new SideRows(w, rider, "graph AssertVerdicts.side (leg 3.2)");
        }
        return new SideRows(w, rider, null);
    }

    /** THE ONE UNJUDGED OUTCOME (leg 3.3, audit §4y): the database judge
     * declined the shape — counted, then raised as a verdict failure that
     * CARRIES its reason (the runner's ledger reads the type, never the
     * text). {@code detail} rides the message only. */
    static ExecutionResult unjudged(String name, String why) {
        return unjudged(name, why, "");
    }

    static ExecutionResult unjudged(String name, String why, String detail) {
        com.legend.exec.CanonicalDivergence.sqlUnjudged(name, why);
        throw com.legend.error.AssertFailed.unjudged(why,
                name + ": UNJUDGED in database mode — " + why + detail);
    }

    static ExecutionResult databaseSize(String name, List<TypedSpec> args,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        SideRows coll = planSide(args.get(0), false, false, letPrefix, specs, env, hook);
        if (coll.why() != null) {
            return unjudged(name, coll.why());
        }
        SideRows n = planSide(args.get(1), false, letPrefix, specs, env, hook);
        if (n.why() != null) {
            return unjudged(name, "size arg: " + n.why());
        }
        boolean envelope = java.util.Objects.requireNonNull(coll.rider()).tdsWrapped()
                && AssertVerdicts.envelopeValuesRead(args.get(0), letPrefix);
        var cw = java.util.Objects.requireNonNull(coll.side());
        com.legend.sql.SqlQuery vq = cw.shape() == com.legend.exec.ResultShape.GRAPH
                ? com.legend.lowering.VerdictSql.sizeOfGraph(cw.plan(), n.scalarRow(false))
                : com.legend.lowering.VerdictSql.size(coll.countRows(), n.scalarRow(false), envelope);
        return runVerdict(name, true, vq, coll.connection(env), env);
    }

    static ExecutionResult databaseEmpty(String name, TypedSpec arg, boolean wantEmpty,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        SideRows side = planSide(arg, false, false, letPrefix, specs, env, hook);
        if (side.why() != null) {
            return unjudged(name, side.why());
        }
        var sw = java.util.Objects.requireNonNull(side.side());
        com.legend.sql.SqlQuery vq = sw.shape() == com.legend.exec.ResultShape.GRAPH
                ? com.legend.lowering.VerdictSql.emptyOfGraph(sw.plan(), wantEmpty)
                : com.legend.lowering.VerdictSql.empty(side.countRows(), wantEmpty);
        return runVerdict(name, true, vq, side.connection(env), env);
    }

    static ExecutionResult databaseContains(String name, TypedSpec coll, TypedSpec val,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        SideRows c = planSide(coll, false, letPrefix, specs, env, hook);
        if (c.why() != null) {
            return unjudged(name, c.why());
        }
        SideRows v = planSide(val, false, letPrefix, specs, env, hook);
        if (v.why() != null) {
            return unjudged(name, "value: " + v.why());
        }
        var cr = java.util.Objects.requireNonNull(c.rider());
        var vr = java.util.Objects.requireNonNull(v.rider());
        boolean literal = cr.literalOnly() || vr.literalOnly();
        if (literal && (cr.literalIndex() < 0 || vr.literalIndex() < 0)) {
            return unjudged(name, "no literal channel");
        }
        return runVerdict(name, true, com.legend.lowering.VerdictSql.contains(c.rows(literal), v.scalarRow(literal)),
                c.connection(env), env);
    }

    /** {@code assertTdsEquivalent} in database mode (bucket 5): both grids
     * planned, the column names checked statically (the schemas), the cells
     * aligned by position in one statement with the numeric / temporal
     * tolerances (VerdictSql.gridTolerance). */
    static ExecutionResult databaseTdsEquivalent(String name, List<TypedSpec> targs,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        SideRows one = planSide(targs.get(0), true, letPrefix, specs, env, hook);
        SideRows two = planSide(targs.get(1), false, letPrefix, specs, env, hook);
        if (one.why() != null || two.why() != null) {
            return unjudged(name, "tdsEquivalent side: " + (one.why() != null ? one.why() : two.why()));
        }
        var r1 = java.util.Objects.requireNonNull(one.rider());
        var r2 = java.util.Objects.requireNonNull(two.rider());
        var w1 = java.util.Objects.requireNonNull(one.side());
        var w2 = java.util.Objects.requireNonNull(two.side());
        var s1 = com.legend.compiler.element.type.Type.schemaView(w1.shapeInfo().type());
        var s2 = com.legend.compiler.element.type.Type.schemaView(w2.shapeInfo().type());
        if (!r1.tdsWrapped() || !r2.tdsWrapped() || s1 == null || s2 == null) {
            return unjudged(name, "tdsEquivalent: a side is not a grid");
        }
        List<String> n1 = s1.columns().stream().map(c -> c.name()).toList();
        List<String> n2 = s2.columns().stream().map(c -> c.name()).toList();
        if (!n1.equals(n2)) {
            com.legend.exec.CanonicalDivergence.sqlJudgedInDatabase(name);
            return AssertVerdicts.fail(name + ": columns differ " + n1 + " vs " + n2);   // a static verdict
        }
        List<com.legend.compiler.element.type.Type> kinds = new ArrayList<>();
        List<Boolean> floats = new ArrayList<>();
        for (var c : s1.columns()) {
            kinds.add(c.type());
            floats.add(c.type() == com.legend.compiler.element.type.Type.Primitive.FLOAT);
        }
        var g1 = new com.legend.lowering.VerdictSql.GridSide(w1.plan(), n1.size(), floats);
        var g2 = new com.legend.lowering.VerdictSql.GridSide(w2.plan(), n2.size(), floats);
        SideRows delta = planSide(targs.get(2), false, letPrefix, specs, env, hook);
        SideRows timeDelta = planSide(targs.size() == 4 ? targs.get(3)
                : com.legend.compiler.spec.VerdictQueries.zeroLiteral(), false, letPrefix, specs, env, hook);
        if (delta.why() != null || timeDelta.why() != null) {
            return unjudged(name, "tdsEquivalent delta: "
                    + (delta.why() != null ? delta.why() : timeDelta.why()));
        }
        return runVerdict(name, true, com.legend.lowering.VerdictSql.gridTolerance(g1, g2, kinds,
                        delta.rows(false), timeDelta.rows(false)),
                w1.storeFree() ? w2.connection() : w1.connection(), env);
    }

    static ExecutionResult databaseCondition(String name, TypedSpec cond, boolean wantTrue,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        TypedSpec[] fc = AssertVerdicts.forAllContains(cond);
        if (fc != null) {
            SideRows need = planSide(fc[0], false, letPrefix, specs, env, hook);
            SideRows have = planSide(fc[1], false, letPrefix, specs, env, hook);
            if (need.why() != null || have.why() != null) {
                return unjudged(name, "forAll-contains: " + (need.why() != null ? need.why() : have.why()));
            }
            var nr = java.util.Objects.requireNonNull(need.rider());
            var hr = java.util.Objects.requireNonNull(have.rider());
            boolean literal = nr.literalOnly() || hr.literalOnly();
            if (literal && (nr.literalIndex() < 0 || hr.literalIndex() < 0)) {
                return unjudged(name, "forAll-contains: no literal channel");
            }
            return runVerdict(name, true, com.legend.lowering.VerdictSql.subset(need.rows(literal), have.rows(literal), wantTrue),
                    need.connection(env), env);
        }
        SideRows side = planSide(cond, false, letPrefix, specs, env, hook);
        if (side.why() != null) {
            return unjudged(name, side.why());
        }
        return runVerdict(name, true, com.legend.lowering.VerdictSql.condition(side.scalarRow(false), wantTrue),
                side.connection(env), env);
    }

    static ExecutionResult databaseTolerance(String name, List<TypedSpec> args,
            List<TypedSpec> letPrefix, SpecCompiler specs, StatementExecutor.ExecEnv env,
            @com.legend.Nullable AssertVerdicts.SpliceHook hook) {
        SideRows e = planSide(args.get(0), false, letPrefix, specs, env, hook);
        SideRows a = planSide(args.get(1), false, letPrefix, specs, env, hook);
        SideRows t = planSide(args.get(2), false, letPrefix, specs, env, hook);
        String why = e.why() != null ? e.why() : a.why() != null ? a.why() : t.why();
        if (why != null) {
            return unjudged(name, why);
        }
        return runVerdict(name, true, com.legend.lowering.VerdictSql.tolerance(e.scalarRow(false), a.scalarRow(false), t.scalarRow(false)),
                a.connection(env), env);
    }

    /** A side the pipeline ANSWERED as a host constant (a TDG seed string, a
     * rendered DDL text, a folded literal): bound as a VALUES relation and
     * canon-wrapped like any side, so the database still compares
     * (database-ADJUDICATED). Null = no literal spelling for the value's
     * kind (the caller reports it unjudged by kind). */
    static StatementExecutor.@com.legend.Nullable WrappedSide constantSide(
            @com.legend.Nullable ExecutionResult answered, TypedSpec spec,
            com.legend.exec.CanonRider rider, boolean canonicalOrder,
            StatementExecutor.ExecEnv env, java.sql.Connection on) {
        if (answered == null) {
            return null;
        }
        List<Object> values = AssertVerdicts.decodeSideValues(answered);
        com.legend.sql.SqlQuery plan = com.legend.lowering.VerdictSql.constantPlan(values);
        if (plan == null) {
            return null;
        }
        var w = com.legend.lowering.CanonicalRenderSql.wrapWithCanon(plan, spec.info(),
                canonicalOrder, com.legend.compiler.element.EqualityKeys
                        .resolve(env.ctx(), spec.info().type()),
                true, com.legend.lowering.CanonicalRenderSql.nameValued(
                        spec.info().type(), env.ctx()::tracksClassifier),
                rider.enumFrame());
        if (w.declineReason() != null) {
            rider.decline(w.declineReason());
            return new StatementExecutor.WrappedSide(plan, spec.info(),
                    com.legend.exec.ResultShape.COLLECTION, on, true);
        }
        rider.wrap(w.kinds(), w.many(), w.literalIndex());
        return new StatementExecutor.WrappedSide(w.plan(), spec.info(),
                com.legend.exec.ResultShape.COLLECTION, on, true);
    }

    static String describe(@com.legend.Nullable ExecutionResult r) {
        return r == null ? "no plan" : r.getClass().getSimpleName();
    }


    // ── leg 3.1c: the one-line families in database mode. Each side is
    // planned like an equality side (planSide); the predicate statement
    // returns the same verdict row; unjudged fails by name.

    /** A planned side for the predicate forms, or a reason it could not be. */
    record SideRows(StatementExecutor.@com.legend.Nullable WrappedSide side,
            com.legend.exec.@com.legend.Nullable CanonRider rider,
            @com.legend.Nullable String why) {
        /** The side's canon rows; {@code literal} = the pair compares in the
         * literal channel (decided for BOTH sides — one channel per pair). */
        com.legend.sql.SqlQuery rows(boolean literal) {
            var r = java.util.Objects.requireNonNull(rider);
            var w = java.util.Objects.requireNonNull(side);
            int idx = literal && r.literalIndex() >= 0 ? r.literalIndex() : 0;
            return com.legend.lowering.VerdictSql.sideRows(w.plan(), r.tdsWrapped(),
                    "__canon" + idx, r.many() || r.tdsWrapped());
        }
        /** The side's own TEXT column as its rows (a database-built JSON
         * document, a golden bound as VALUES): the plan's first output,
         * read raw — no canon needed, none may exist (a graph plan's
         * canon declines by kind while its document is one VARCHAR). */
        com.legend.sql.SqlQuery textRows() {
            var w = java.util.Objects.requireNonNull(side);
            return com.legend.lowering.VerdictSql.sideRows(w.plan(), false,
                    w.plan().outputs().get(0).name(), false);
        }
        /** A literal collection's TEXTS as rows (one per element), raw. */
        com.legend.sql.SqlQuery textRowsMany() {
            var w = java.util.Objects.requireNonNull(side);
            return com.legend.lowering.VerdictSql.sideRows(w.plan(), false,
                    w.plan().outputs().get(0).name(), true);
        }
        /** The side's rows for counting: the canon may have declined. */
        /** A side DECLARED exactly one as a one-row relation {@code (__c,
         * value)} straight over its plan — no rows CTE, no limited read
         * (lean ladder rung 4). */
        com.legend.sql.SqlQuery scalarRow(boolean literal) {
            var r = java.util.Objects.requireNonNull(rider);
            var w = java.util.Objects.requireNonNull(side);
            int idx = literal && r.literalIndex() >= 0 ? r.literalIndex() : 0;
            return com.legend.lowering.VerdictSql.scalarRow(w.plan(), "__canon" + idx);
        }
        com.legend.sql.SqlQuery countRows() {
            var r = java.util.Objects.requireNonNull(rider);
            var w = java.util.Objects.requireNonNull(side);
            return r.tdsWrapped() ? rows(false)
                    : com.legend.lowering.VerdictSql.countRows(w.plan());
        }
        java.sql.Connection connection(StatementExecutor.ExecEnv env) {
            return side != null && !side.storeFree() ? side.connection() : env.connection();
        }
    }
}
