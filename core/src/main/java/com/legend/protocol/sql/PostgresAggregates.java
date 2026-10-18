// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import static com.legend.protocol.sql.SqlToPure.*;
import static com.legend.protocol.sql.PostgresOperators.*;

import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.CBoolean;
import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Postgres's aggregates as the {@code #SQL{...}#} island reads them: count, sum, avg, min, max, the statistics,
 * string_agg, the boolean and ordered-set aggregates, and those of pairs and regressions
 * ({@link PostgresDialect#aggregate}; the translator's own reading until the dialect program's D11, 2026-10-18, moved it
 * out of {@link SqlToPure}).
 */
final class PostgresAggregates {

    private PostgresAggregates() {
    }

    /** Postgres's linear regressions of y on x: double precision, over the pairs that hold both values. */
    private static final Set<String> REGRESSIONS = Set.of("regr_count", "regr_avgx", "regr_avgy", "regr_sxx",
            "regr_syy", "regr_sxy", "regr_slope", "regr_intercept", "regr_r2");

    /** Postgres's ordered-set aggregates that Pure's percentile computes. */
    private static final Set<String> PERCENTILES = Set.of("percentile_cont", "percentile_disc");

    /** An aggregate column of the grouped relation: its map and reduce (with any it is computed from), its kind, and
     *  the column computed from them after the grouping when it is not one of them. */
    private record Aggregate(List<ColSpec> specs, Kind kind, @com.legend.base.Nullable ColSpec finish) {

        Aggregate(ColSpec spec, Kind kind) {
            this(List.of(spec), kind, null);
        }
    }

    /** Whether {@code name} (a function's name as the translator reads one, {@link SqlToPure#functionName}) is one of
     *  Postgres's aggregates ({@link PostgresDialect#isAggregate}). */
    static boolean postgresAggregate(String name) {
        return AGGREGATES.contains(name);
    }

    /** An aggregate as the translator's own rules read it ({@link InputDialect#aggregate}: Postgres's), named
     *  {@code name} among the grouped relation's {@code names}. */
    static Aggregation ownAggregate(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in, String name,
            Set<String> names) {
        String fn = functionName(f.name().get(0));
        if (fn.equals("string_agg") || PERCENTILES.contains(fn) || BIVARIATE.containsKey(fn)
                || REGRESSIONS.contains(fn)) {
            Aggregate aggregate = fn.equals("string_agg") ? new Aggregate(stringAgg(translator, f, in, name),
                    Kind.STRING)
                    : PERCENTILES.contains(fn) ? percentile(translator, f, in, name)
                    : REGRESSIONS.contains(fn) ? regression(translator, f, in, name, names) : bivariate(translator, f,
                    in, name, names);
            return new Aggregation(aggregate.specs(), aggregate.finish(), aggregate.kind(), true, null);
        }
        Tx arg;
        if (f.star()) {
            if (!fn.equals("count")) {
                throw SqlTranslationException.invalid(fn + "(*) is not a function; only count(*) is", f.pos());
            }
            arg = Tx.value(new CInteger(1L), Kind.INTEGER, false);
        } else {
            if (f.args().size() != 1) {
                throw SqlTranslationException.invalid(fn + " takes one argument here", f.pos());
            }
            arg = translator.tx(f.args().get(0), in, translator.row);
        }
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; "
                    + fn + " is not one", f.pos());
        }
        orderedKeys(translator, f, in);
        if (f.distinct()) {
            // the distinct values of the group, each once (Postgres's DISTINCT inside an aggregate)
            notJsonKey(arg.kind(), fn + "(DISTINCT ...)", f.pos());
        }
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (translator.containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER",
                        filter.pos());
            }
            // the rows the condition holds for only: another row gives no value, as Postgres skips it
            Tx value = arg;
            ValueSpecification test = translator.condition(filter, in, "FILTER");
            ValueSpecification kept = call("if", test, thunk(valueOf(value)), thunk(empty()));
            arg = value.kind() == Kind.BOOLEAN ? booleanValue(kept, true) : Tx.value(kept, value.kind(), true);
        }
        ValueSpecification values = new Variable(translator.group);
        if (f.distinct()) {
            values = call("distinct", values);
        }
        Kind kind;
        ValueSpecification reduce;
        if (BOOLEAN_AGGREGATES.contains(fn)) {
            // Postgres's every is bool_and; NULLs are skipped, and no values is NULL
            if (arg.kind() != Kind.BOOLEAN) {
                throw arg.kind() == Kind.NULL || arg.kind() == Kind.OTHER
                        ? SqlTranslationException.unsupported(fn + " of this value is not supported yet", f.pos())
                        : SqlTranslationException.invalid("function " + fn + "(" + postgresName(arg.kind())
                                + ") does not exist", f.pos());
            }
            kind = Kind.BOOLEAN;
            reduce = call(fn.equals("bool_or") ? "or" : "and", values);
        } else {
            switch (fn) {
                case "count" -> {
                    kind = Kind.INTEGER;
                    reduce = call("count", values);
                }
                case "sum" -> {
                    requireNumeric(arg, f);
                    kind = sumKind(arg.kind());
                    reduce = call("sum", values);
                }
                case "avg" -> {
                    requireNumeric(arg, f);
                    kind = statisticKind(arg.kind());
                    reduce = call("average", values);
                }
                case "min" -> {
                    minMaxable(arg.kind(), fn, f.pos());
                    kind = arg.kind();
                    reduce = call("min", values);
                }
                case "max" -> {
                    minMaxable(arg.kind(), fn, f.pos());
                    kind = arg.kind();
                    reduce = call("max", values);
                }
                default -> {
                    // stddev, variance and their sample and population forms
                    requireNumeric(arg, f);
                    kind = statisticKind(arg.kind());
                    reduce = call(java.util.Objects.requireNonNull(STATISTICS.get(fn), fn), values);
                }
            }
        }
        // count is never NULL; every other aggregate is NULL over no values (Postgres)
        return new Aggregation(List.of(new ColSpec(name, lambda(translator.row, valueOf(arg)),
                lambda(translator.group, reduce), null)),
                null, kind, !fn.equals("count"), null);
        
    }

    /**
     * {@code percentile_cont(p)} or {@code percentile_disc(p) WITHIN GROUP (ORDER BY x [DESC]) [FILTER (WHERE ...)]}:
     * Pure's percentile over the group, NULLs skipped as Postgres skips them, {@code p} a constant fraction. The
     * continuous one interpolates between doubles, as Postgres does (each value read as one: DuckDB would interpolate
     * a decimal at the decimal's scale). The discrete one picks a value, which the lowering gives as a double: an
     * integer's is read back as its integer (Postgres's type, whose division truncates), a numeric's is inexact.
     */
    private static Aggregate percentile(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in, String name) {
        String fn = functionName(f.name().get(0));
        boolean continuous = fn.equals("percentile_cont");
        if (f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregate " + fn,
                    f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.invalid("cannot use DISTINCT with WITHIN GROUP", f.pos());
        }
        if (f.star() || f.args().size() != 1 || f.withinGroup().size() != 1 || !f.orderBy().isEmpty()) {
            throw SqlTranslationException.unsupported(fn + " of other than one fraction and one value is not"
                    + " supported yet", f.pos());
        }
        SqlAst.SortItem key = f.withinGroup().get(0);
        for (SqlAst.Expr a : List.of(f.args().get(0), key.expr())) {
            if (translator.containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
        }
        SqlAst.Expr fraction = f.args().get(0);
        BigDecimal p = fraction instanceof SqlAst.NumericLit n ? n.value()
                : fraction instanceof SqlAst.IntegerLit n ? new BigDecimal(n.value()) : null;
        if (p == null) {
            throw SqlTranslationException.unsupported(fn + " of a fraction other than a constant is not supported"
                    + " yet", fraction.pos());
        }
        if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) {
            throw SqlTranslationException.invalid("percentile value " + p.toPlainString() + " is not between 0 and 1",
                    fraction.pos());
        }
        Tx x = translator.tx(key.expr(), in, translator.row);
        if (!x.kind().numeric()) {
            throw x.kind() == Kind.NULL || x.kind() == Kind.OTHER || !continuous
                    ? SqlTranslationException.unsupported(fn + " of " + postgresName(x.kind()) + " values is not"
                            + " supported yet", key.expr().pos())
                    : SqlTranslationException.invalid("function " + fn + "(numeric, " + postgresName(x.kind())
                            + ") does not exist", f.pos());
        }
        Tx value = continuous && x.kind() != Kind.FLOAT ? strict(Kind.FLOAT, List.of(x), v -> call("toFloat",
                v.get(0))) : x;
        ValueSpecification each = valueOf(value);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (translator.containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            each = call("if", translator.condition(filter, in, "FILTER"), thunk(each), thunk(empty()));
        }
        ValueSpecification reduce = call("percentile", new Variable(translator.group),
                new com.legend.protocol.spec.CFloat(p.doubleValue()), new CBoolean(!key.descending()),
                new CBoolean(continuous));
        if (!continuous && (x.kind() == Kind.INTEGER || x.kind() == Kind.WHOLE)) {
            reduce = call("cast", reduce, typeArg("Integer"));
        }
        Kind kind = continuous ? (x.kind() == Kind.INEXACT ? Kind.INEXACT : Kind.FLOAT)
                : x.kind() == Kind.DECIMAL || x.kind() == Kind.NUMBER ? Kind.INEXACT : x.kind();
        return new Aggregate(new ColSpec(name, lambda(translator.row, each), lambda(translator.group, reduce), null),
                kind);
    }

    /** {@code corr(y, x)}, {@code covar_pop(y, x)}, {@code covar_samp(y, x)}: Pure's over the group's pairs
     *  ({@code rowMapper(y, x)}), a pair with a NULL skipped as Postgres skips it; double precision. */
    private static Aggregate bivariate(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in, String name,
            Set<String> names) {
        String fn = functionName(f.name().get(0));
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; " + fn
                    + " is not one", f.pos());
        }
        if (f.distinct() || f.star() || f.args().size() != 2) {
            throw SqlTranslationException.unsupported(fn + " other than of two values is not supported yet",
                    f.pos());
        }
        orderedKeys(translator, f, in);
        List<Tx> pair = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            if (translator.containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
            Tx t = translator.tx(a, in, translator.row);
            if (!t.kind().numeric()) {
                throw SqlTranslationException.unsupported(fn + " of " + postgresName(t.kind()) + " values is not"
                        + " supported yet", a.pos());
            }
            pair.add(t);
        }
        ValueSpecification y = valueOf(pair.get(0));
        ValueSpecification x = valueOf(pair.get(1));
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (translator.containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            ValueSpecification test = translator.condition(filter, in, "FILTER");
            y = call("if", test, thunk(y), thunk(empty()));
            x = call("if", test, thunk(x), thunk(empty()));
        }
        ValueSpecification reduce = call(java.util.Objects.requireNonNull(BIVARIATE.get(fn)),
                new Variable(translator.group));
        if (!fn.equals("corr")) {
            return new Aggregate(new ColSpec(name, lambda(translator.row, call("rowMapper", y, x)),
                    lambda(translator.group, reduce), null),
                    Kind.FLOAT);
        }
        // Postgres's corr is NULL where either value's variance over the pairs is 0 (one pair among them); a
        // database's quotient is NaN there (DuckDB's). The pairs' variances -- a value's covariance with itself, over
        // the pairs that hold both -- say where; the column is the correlation elsewhere.
        String raw = translator.fresh(names);
        String vy = translator.fresh(names);
        String vx = translator.fresh(names);
        ValueSpecification yPaired = call("if", call("isEmpty", x), thunk(empty()), thunk(y));
        ValueSpecification xPaired = call("if", call("isEmpty", y), thunk(empty()), thunk(x));
        ValueSpecification flat = call("or", knownZero(translator, vy), knownZero(translator, vx));
        return new Aggregate(List.of(
                new ColSpec(raw, lambda(translator.row, call("rowMapper", y, x)), lambda(translator.group, reduce),
                null),
                new ColSpec(vy, lambda(translator.row, call("rowMapper", y, yPaired)), lambda(translator.group,
                        call("covarPopulation", new Variable(translator.group))), null),
                new ColSpec(vx, lambda(translator.row, call("rowMapper", x, xPaired)), lambda(translator.group,
                        call("covarPopulation", new Variable(translator.group))), null)),
                Kind.FLOAT, new ColSpec(name, lambda(translator.row, call("if", flat, thunk(empty()),
                thunk(property(translator.row, raw)))),
                        null, null));
    }

    /** An aggregate's {@code ORDER BY}, for one whose answer no order changes (all but string_agg): Postgres orders
     *  the values it reads, and reads the same answer from them in any order. Its keys are still checked as Postgres
     *  checks them; under DISTINCT each must be the argument. */
    private static void orderedKeys(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in) {
        for (SqlAst.SortItem o : f.orderBy()) {
            if (translator.containsAggregate(o.expr())) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", o.expr().pos());
            }
            if (f.distinct() && (f.args().size() != 1 || !canon(o.expr(), in).equals(canon(f.args().get(0), in)))) {
                throw SqlTranslationException.invalid("in an aggregate with DISTINCT, ORDER BY expressions must"
                        + " appear in argument list", o.expr().pos());
            }
            translator.tx(o.expr(), in, translator.row);
        }
    }

    /**
     * Postgres's regressions of y on x ({@code regr_slope(y, x)} and the others), over the pairs that hold both
     * values, each value read as a double as Postgres reads it. They are computed from the pairs' count and averages
     * and their population variances and covariance (Pure's covarPopulation over rowMapper pairs), the column itself
     * after the grouping: Sxx is N times x's variance, Syy and Sxy alike; the slope Sxy / Sxx and the intercept
     * avg(y) - slope * avg(x), NULL where Sxx is 0; r2 Sxy^2 / (Sxx * Syy), 1 where only Syy is 0. As for corr, a
     * variance Postgres's running sums leave near 0 for equal values is exactly 0 here.
     */
    private static Aggregate regression(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in, String name,
            Set<String> names) {
        String fn = functionName(f.name().get(0));
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only; " + fn
                    + " is not one", f.pos());
        }
        if (f.star() || f.args().size() != 2) {
            throw SqlTranslationException.invalid("function " + fn + " takes two arguments", f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.unsupported(fn + "(DISTINCT ...) is not supported yet", f.pos());
        }
        orderedKeys(translator, f, in);
        List<ValueSpecification> pair = new ArrayList<>();
        List<Boolean> nullable = new ArrayList<>();
        for (SqlAst.Expr a : f.args()) {
            if (translator.containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
            Tx t = translator.tx(a, in, translator.row);
            if (!t.kind().numeric()) {
                throw SqlTranslationException.unsupported(fn + " of " + postgresName(t.kind()) + " values is not"
                        + " supported yet", a.pos());
            }
            ValueSpecification v = valueOf(t);
            // Postgres reads each value as a double precision
            pair.add(t.kind() == Kind.FLOAT || t.kind() == Kind.INEXACT ? v : t.nullable() ? call("if",
                    call("isEmpty", v), thunk(empty()), thunk(call("toFloat", call("toOne", v)))) : call("toFloat", v));
            nullable.add(t.nullable());
        }
        ValueSpecification y = pair.get(0);
        ValueSpecification x = pair.get(1);
        boolean maybeNull = nullable.get(0) || nullable.get(1);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (translator.containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            ValueSpecification test = translator.condition(filter, in, "FILTER");
            y = call("if", test, thunk(y), thunk(empty()));
            x = call("if", test, thunk(x), thunk(empty()));
            maybeNull = true;
        }
        // each value where its pair holds both
        ValueSpecification yPaired = maybeNull ? call("if", call("isEmpty", x), thunk(empty()), thunk(y)) : y;
        ValueSpecification xPaired = maybeNull ? call("if", call("isEmpty", y), thunk(empty()), thunk(x)) : x;
        Variable g = new Variable(translator.group);
        switch (fn) {
            case "regr_count" -> {
                return new Aggregate(new ColSpec(name, lambda(translator.row, xPaired), lambda(translator.group,
                        call("count", g)), null),
                        Kind.INTEGER);
            }
            case "regr_avgx", "regr_avgy" -> {
                return new Aggregate(new ColSpec(name, lambda(translator.row, fn.equals("regr_avgx") ? xPaired
                        : yPaired),
                        lambda(translator.group, call("average", g)), null), Kind.FLOAT);
            }
            default -> {
            }
        }
        String n = translator.fresh(names);
        String vx = translator.fresh(names);
        String vy = translator.fresh(names);
        String cxy = translator.fresh(names);
        String ax = translator.fresh(names);
        String ay = translator.fresh(names);
        List<ColSpec> parts = List.of(
                new ColSpec(n, lambda(translator.row, xPaired), lambda(translator.group, call("count", g)), null),
                new ColSpec(vx, lambda(translator.row, call("rowMapper", x, xPaired)), lambda(translator.group,
                call("covarPopulation", g)),
                        null),
                new ColSpec(vy, lambda(translator.row, call("rowMapper", y, yPaired)), lambda(translator.group,
                call("covarPopulation", g)),
                        null),
                new ColSpec(cxy, lambda(translator.row, call("rowMapper", y, x)), lambda(translator.group,
                call("covarPopulation", g)),
                        null),
                new ColSpec(ax, lambda(translator.row, xPaired), lambda(translator.group, call("average", g)), null),
                new ColSpec(ay, lambda(translator.row, yPaired), lambda(translator.group, call("average", g)), null));
        ValueSpecification count = call("toFloat", property(translator.row, n));
        ValueSpecification sxx = one(translator, vx);
        ValueSpecification slope = call("divide", one(translator, cxy), sxx);
        ValueSpecification flat = call("or", call("isEmpty", property(translator.row, vx)), call("equal", sxx,
                new CInteger(0L)));
        ValueSpecification value = switch (fn) {
            case "regr_sxx" -> call("if", call("isEmpty", property(translator.row, vx)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, sxx))));
            case "regr_syy" -> call("if", call("isEmpty", property(translator.row, vy)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, one(translator, vy)))));
            case "regr_sxy" -> call("if", call("isEmpty", property(translator.row, cxy)), thunk(empty()), thunk(
                    AppliedFunction.infixRun("times", List.of(count, one(translator, cxy)))));
            case "regr_slope" -> call("if", flat, thunk(empty()), thunk(slope));
            case "regr_intercept" -> call("if", flat, thunk(empty()), thunk(AppliedFunction.infixRun("minus",
                    List.of(one(translator, ay), AppliedFunction.infixRun("times", List.of(slope, one(translator,
                    ax)))))));
            default -> call("if", flat, thunk(empty()), thunk(call("if", call("equal", one(translator, vy),
                    new CInteger(0L)),
                    thunk(call("toFloat", new CInteger(1L))), thunk(call("divide", AppliedFunction.infixRun("times",
                            List.of(one(translator, cxy), one(translator, cxy))), AppliedFunction.infixRun("times",
                            List.of(sxx,
                            one(translator, vy))))))));
        };
        return new Aggregate(parts, Kind.FLOAT, new ColSpec(name, lambda(translator.row, value), null, null));
    }

    /** A grouped column's one value (where it holds one). */
    private static ValueSpecification one(SqlToPure translator, String column) {
        return call("toOne", property(translator.row, column));
    }

    /** Whether a grouped column holds 0: known and equal to 0. */
    private static ValueSpecification knownZero(SqlToPure translator, String column) {
        ValueSpecification v = property(translator.row, column);
        return call("and", call("isNotEmpty", v), call("equal", call("toOne", v), new CInteger(0L)));
    }

    /** {@code string_agg(text, 'sep' [ORDER BY col [DESC], ...]) [FILTER (WHERE ...)]}: Pure's joinStrings over the
     *  group, NULLs skipped as Postgres skips them; ordered, the group-lambda form
     *  ({@code g | $g->joinStrings(r | value, sep, ~k->ascending())}: string_agg(x, sep ORDER BY k)). A key is a
     *  column, in Postgres's default NULL placement (last ascending, first descending). */
    private static ColSpec stringAgg(SqlToPure translator, SqlAst.FunctionCall f, SqlToPure.Input in, String name) {
        if (f.star() || f.args().size() != 2) {
            throw SqlTranslationException.invalid("function string_agg takes two arguments", f.pos());
        }
        if (f.distinct()) {
            throw SqlTranslationException.unsupported("string_agg(DISTINCT ...) is not supported yet", f.pos());
        }
        if (!f.withinGroup().isEmpty()) {
            throw SqlTranslationException.invalid("WITHIN GROUP is required for ordered-set aggregates only;"
                    + " string_agg is not one", f.pos());
        }
        for (SqlAst.Expr a : f.args()) {
            if (translator.containsAggregate(a)) {
                throw SqlTranslationException.invalid("aggregate function calls cannot be nested", a.pos());
            }
        }
        Tx value = translator.tx(f.args().get(0), in, translator.row);
        if (value.kind() != Kind.STRING) {
            throw value.kind() == Kind.OTHER || value.kind() == Kind.NULL
                    ? SqlTranslationException.unsupported("string_agg of this type is not supported yet", f.pos())
                    : SqlTranslationException.invalid("function string_agg(" + postgresName(value.kind())
                            + ", unknown) does not exist", f.pos());
        }
        if (!(f.args().get(1) instanceof SqlAst.StringLit sep)) {
            throw SqlTranslationException.unsupported("a string_agg delimiter other than a constant is not supported"
                    + " yet", f.args().get(1).pos());
        }
        ValueSpecification each = valueOf(value);
        SqlAst.Expr filter = f.filter();
        if (filter != null) {
            if (translator.containsAggregate(filter)) {
                throw SqlTranslationException.invalid("aggregate functions are not allowed in FILTER", filter.pos());
            }
            each = call("if", translator.condition(filter, in, "FILTER"), thunk(each), thunk(empty()));
        }
        if (f.orderBy().isEmpty()) {
            return new ColSpec(name, lambda(translator.row, each), lambda(translator.group, call("joinStrings",
                    new Variable(translator.group),
                    new CString(sep.value()))), null);
        }
        List<ValueSpecification> keys = new ArrayList<>();
        for (SqlAst.SortItem o : f.orderBy()) {
            Col c = o.expr() instanceof SqlAst.ColumnRef ref ? in.columnOrNull(ref) : null;
            if (c == null || c.var() != null) {
                throw SqlTranslationException.unsupported("a string_agg ORDER BY key other than a column is not"
                        + " supported yet", o.expr().pos());
            }
            Boolean nullsFirst = o.nullsFirst();
            if (nullsFirst != null && nullsFirst != o.descending()) {
                throw SqlTranslationException.unsupported("a string_agg ORDER BY key with its NULLs placed otherwise"
                        + " than Postgres's default is not supported yet", o.pos());
            }
            keys.add(call(o.descending() ? "descending" : "ascending", colSpec(c.name())));
        }
        return new ColSpec(name, lambda(translator.group, call("joinStrings", new Variable(translator.group),
                lambda(translator.row, each),
                new CString(sep.value()), keys.size() == 1 ? keys.get(0) : new PureCollection(keys))), null, null);
    }

    private static void requireNumeric(Tx arg, SqlAst.FunctionCall f) {
        if (!arg.kind().numeric()) {
            throw SqlTranslationException.invalid("function " + functionName(f.name().get(0)) + "(" + arg.kind()
                    .name().toLowerCase(Locale.ROOT) + ") does not exist", f.pos());
        }
    }
}
