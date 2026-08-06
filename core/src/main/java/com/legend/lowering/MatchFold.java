// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedMatchRuntime;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;
import com.legend.sql.SqlExpr;
import com.legend.values.PureDateLiteral;

/**
 * STATIC-DISPATCH fold for a runtime match in scalar position: the
 * input's compile-time type picks the FIRST conforming arm (Pure match
 * order), whose body inlines with the parameter bound to the input —
 * the milestoning date-projection helpers spell StrictDate/DateTime
 * arms over a typed date column (the getAllForEachDate family). A
 * genuinely polymorphic input (class hierarchies) stays a loud wall.
 */
final class MatchFold {

    private MatchFold() {
    }

    static TypedSpec fold(TypedMatchRuntime mr) {
        for (TypedMatchRuntime.Arm arm : mr.arms()) {
            if (staticConforms(mr.input().info().type(), arm.typeFqn())) {
                return inlineParam(arm.body(), arm.param(), mr.input());
            }
        }
        throw new NotImplementedException("scalar match: no arm statically"
                + " accepts input type "
                + mr.input().info().type().typeName());
    }

    /** Exact primitive FQN, the temporal ladder under Date, the numeric
     * ladder under Number, and Any; class inputs answer false. */
    private static boolean staticConforms(Type t, String armFqn) {
        if ("meta::pure::metamodel::type::Any".equals(armFqn)) {
            return true;
        }
        if (!(t instanceof Type.Primitive p)) {
            return false;
        }
        if (p.qualifiedName().equals(armFqn)) {
            return true;
        }
        if ("meta::pure::metamodel::type::Date".equals(armFqn)) {
            return p.isTemporal() && p != Type.Primitive.STRICT_TIME;
        }
        return "meta::pure::metamodel::type::Number".equals(armFqn)
                && p.isNumeric();
    }

    /** β-inline the arm parameter (generic mapChildren walk; lambda
     * bodies shadow-checked by name). */
    private static TypedSpec inlineParam(TypedSpec n, String param,
            TypedSpec input) {
        if (n instanceof TypedVariable v && v.name().equals(param)) {
            return input;
        }
        if (n instanceof TypedLambda tl && tl.parameters().contains(param)) {
            return n;
        }
        return n.mapChildren(k -> inlineParam(k, param, input));
    }

    /** Date literals: full dates/timestamps render typed; PARTIAL dates
     * (year / year-month) compare as STRINGS (master's pinned
     * semantics); HOUR/MINUTE-precision timestamps PAD to the full
     * shape SQL demands. Exhaustive — a new precision variant demands a
     * decision here. */
    static SqlExpr dateLit(PureDateLiteral d) {
        return switch (d) {
            case PureDateLiteral.StrictDate sd ->
                    new SqlExpr.DateLit(sd.toEngineString());
            case PureDateLiteral.Year y ->
                    new SqlExpr.StringLit(y.toEngineString());
            case PureDateLiteral.YearMonth ym ->
                    new SqlExpr.StringLit(ym.toEngineString());
                // Every time-bearing precision is a TIMESTAMP — exhaustive,
                // so a new precision variant demands a decision here.
                // HOUR/MINUTE-precision literals PAD to the full timestamp
                // shape SQL demands (%2015-04-15T17 is 17:00:00); second-level
                // precision is already full.
            case PureDateLiteral.DateWithHour h ->
                    new SqlExpr.TimestampLit(h.toEngineString() + ":00:00");
            case PureDateLiteral.DateWithMinute mi ->
                    new SqlExpr.TimestampLit(mi.toEngineString() + ":00");
            case PureDateLiteral.DateWithSecond se ->
                    new SqlExpr.TimestampLit(se.toEngineString());
            case PureDateLiteral.DateWithSubsecond su ->
                    new SqlExpr.TimestampLit(su.toEngineString());
        };
    }
}
