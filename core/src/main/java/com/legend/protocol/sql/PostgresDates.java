// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import static com.legend.protocol.sql.SqlToPure.*;
import static com.legend.protocol.sql.PostgresCasts.*;
import static com.legend.protocol.sql.PostgresOperators.*;
import static com.legend.protocol.sql.PostgresFunctions.*;

import com.legend.protocol.spec.CInteger;
import com.legend.protocol.spec.CString;
import com.legend.protocol.spec.EnumValue;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Postgres's intervals and date_trunc as the {@code #SQL{...}#} island reads them ({@link PostgresDialect}'s, moved
 * out of {@link SqlToPure} with the dialect program's D11, 2026-10-18).
 */
final class PostgresDates {

    private PostgresDates() {
    }

    /** An interval constant's months, days and seconds, which Postgres adds in that order. */
    private record Interval(long months, long days, long seconds) {
    }

    /** The units an interval constant's text may name (Postgres's abbreviations too): their months, days, seconds. */
    private static final Map<String, long[]> INTERVAL_UNITS = intervalUnits();

    private static Map<String, long[]> intervalUnits() {
        // one array per field, shared by its spellings: a field named twice is Postgres's error
        Map<String, long[]> m = new LinkedHashMap<>();
        long[] year = {12, 0, 0};
        for (String y : List.of("year", "years", "yr", "yrs", "y")) {
            m.put(y, year);
        }
        long[] month = {1, 0, 0};
        for (String mo : List.of("month", "months", "mon", "mons")) {
            m.put(mo, month);
        }
        long[] week = {0, 7, 0};
        for (String w : List.of("week", "weeks", "w")) {
            m.put(w, week);
        }
        long[] day = {0, 1, 0};
        for (String d : List.of("day", "days", "d")) {
            m.put(d, day);
        }
        long[] hour = {0, 0, 3600};
        for (String h : List.of("hour", "hours", "hr", "hrs", "h")) {
            m.put(h, hour);
        }
        long[] minute = {0, 0, 60};
        for (String mi : List.of("minute", "minutes", "min", "mins", "m")) {
            m.put(mi, minute);
        }
        long[] second = {0, 0, 1};
        for (String sec : List.of("second", "seconds", "sec", "secs", "s")) {
            m.put(sec, second);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** An interval constant's text in Postgres's plain form: signed whole amounts, each with its unit. Null for
     *  another form (a fraction, {@code HH:MM:SS}, {@code ago}, a bare number): refused. */
    private static @com.legend.base.Nullable Interval interval(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (char c : (text.toLowerCase(Locale.ROOT) + " ").toCharArray()) {
            if (c != ' ') {
                word.append(c);
            } else if (!word.isEmpty()) {
                words.add(word.toString());
                word.setLength(0);
            }
        }
        if (words.isEmpty() || words.size() % 2 != 0) {
            return null;
        }
        long months = 0;
        long days = 0;
        long seconds = 0;
        List<long[]> fields = new ArrayList<>();
        for (int i = 0; i < words.size(); i += 2) {
            String amount = plainNumber(words.get(i), false);
            long[] unit = INTERVAL_UNITS.get(words.get(i + 1));
            if (amount == null || unit == null || amount.length() > 9 || fields.contains(unit)) {
                return null;
            }
            fields.add(unit);
            long n = Long.parseLong(amount);
            months += n * unit[0];
            days += n * unit[1];
            seconds += n * unit[2];
        }
        return new Interval(months, days, seconds);
    }

    /** An interval constant ({@code INTERVAL '1 day'}, {@code '1 day'::interval}); null for another expression. */
    static @com.legend.base.Nullable Interval intervalConstant(SqlAst.Expr e) {
        String text = e instanceof SqlAst.TypedLit t && t.type().name().equals("interval") ? t.value()
                : e instanceof SqlAst.Cast c && c.type().name().equals("interval") && c.type().modifiers().isEmpty()
                        && c.operand() instanceof SqlAst.StringLit s ? s.value() : null;
        if (text == null) {
            return null;
        }
        Interval i = interval(text);
        if (i == null) {
            throw SqlTranslationException.unsupported("the interval '" + text + "' is not supported yet: whole years,"
                    + " months, weeks, days, hours, minutes and seconds are", e.pos());
        }
        return i;
    }

    /**
     * A date or timestamp plus or minus an interval constant: a timestamp, its months added first (a day past the
     * month's end is its last day), then its days, then its seconds, as Postgres adds them. A date is a timestamp at
     * midnight here, as in Postgres.
     */
    static Tx intervalArithmetic(SqlToPure translator, SqlAst.Binary b, Scope scope, String row) {
        boolean minus = b.op() == SqlAst.BinaryOp.MINUS;
        Interval li = intervalConstant(b.left());
        Interval ri = intervalConstant(b.right());
        if (li != null && (ri != null || minus)) {
            throw SqlTranslationException.unsupported("interval arithmetic other than a date or timestamp plus or"
                    + " minus an interval is not supported yet", b.pos());
        }
        Interval in = java.util.Objects.requireNonNull(li != null ? li : ri);
        Tx d = translator.tx(li != null ? b.right() : b.left(), scope, row);
        if (d.kind() == Kind.NULL || isNullConstant(d)) {
            return nullOf(Kind.DATETIME);
        }
        if (!isDate(d.kind())) {
            throw SqlTranslationException.unsupported("an interval with a " + postgresName(d.kind()) + " value is not"
                    + " supported yet", b.pos());
        }
        long sign = minus ? -1 : 1;
        return strict(Kind.DATETIME, List.of(d), v -> {
            ValueSpecification x = v.get(0);
            if (d.kind() == Kind.DATE) {
                x = atMidnight(x);
            }
            if (in.months() != 0) {
                x = call("adjust", x, new CInteger(sign * in.months()), new EnumValue(DURATION_UNIT, "MONTHS"));
            }
            if (in.days() != 0) {
                x = call("adjust", x, new CInteger(sign * in.days()), new EnumValue(DURATION_UNIT, "DAYS"));
            }
            if (in.seconds() != 0) {
                x = call("adjust", x, new CInteger(sign * in.seconds()), new EnumValue(DURATION_UNIT, "SECONDS"));
            }
            return x;
        });
    }

    /** Postgres's date_trunc fields and Pure's: the first moment of the year, quarter, month, week (Monday), day, hour,
     *  minute or second. */
    private static final Map<String, String> TRUNCATIONS = Map.of("year", "firstDayOfYear", "quarter",
            "firstDayOfQuarter", "month", "firstDayOfMonth", "week", "firstDayOfWeek", "day", "firstHourOfDay", "hour",
            "firstMinuteOfHour", "minute", "firstSecondOfMinute", "second", "firstMillisecondOfSecond");

    /** {@code date_trunc('field', timestamp)}: a timestamp. Of a date Postgres truncates a timestamp with time zone:
     *  refused. */
    static Tx dateTrunc(SqlAst.FunctionCall f, List<Tx> args) {
        arity(f, args, 2);
        String pure = args.get(0).vs() instanceof CString c ? TRUNCATIONS.get(c.value().toLowerCase(Locale.ROOT))
                : null;
        if (pure == null) {
            throw SqlTranslationException.unsupported("date_trunc to a field other than a constant year, quarter,"
                    + " month, week, day, hour, minute or second is not supported yet", f.args().get(0).pos());
        }
        Tx x = args.get(1);
        if (x.kind() != Kind.DATETIME) {
            throw SqlTranslationException.unsupported("date_trunc of a " + postgresName(x.kind()) + " value is not"
                    + " supported yet", f.args().get(1).pos());
        }
        // Pure's first day of a year, quarter, month or week is a date: Postgres's is a timestamp at its midnight
        boolean day = pure.startsWith("firstDayOf");
        return strict(Kind.DATETIME, List.of(x), v -> day ? atMidnight(call(pure, v.get(0))) : call(pure, v.get(0)));
    }

    /** A date as the timestamp at its midnight (no time added: Pure's adjust of a date by 0 hours). */
    private static ValueSpecification atMidnight(ValueSpecification date) {
        return call("adjust", date, new CInteger(0L), new EnumValue(DURATION_UNIT, "HOURS"));
    }

    /** {@code +} and {@code -} with a date or a timestamp on a side: a date plus or minus an integer is a date, so many
     *  days on; a date minus a date the days between them. Refused: a timestamp minus a timestamp (an interval) and
     *  the rest. */
    static Tx dateArithmetic(SqlAst.Binary b, Tx l, Tx r) {
        boolean minus = b.op() == SqlAst.BinaryOp.MINUS;
        if (l.kind() == Kind.DATE && r.kind() == Kind.DATE && minus) {
            // the days from the right date to the left one
            return strict(Kind.INTEGER, List.of(l, r), v -> call("dateDiff", v.get(1), v.get(0),
                    new EnumValue(DURATION_UNIT, "DAYS")));
        }
        Tx date = l.kind() == Kind.DATE ? l : r;
        Tx days = date == l ? r : l;
        if (date.kind() == Kind.DATE && days.kind() == Kind.INTEGER && !(minus && date == r)) {
            return strict(Kind.DATE, List.of(date, days), v -> call("adjust", v.get(0), minus ? call("minus", v.get(1))
                    : v.get(1), new EnumValue(DURATION_UNIT, "DAYS")));
        }
        throw SqlTranslationException.unsupported("this date and timestamp arithmetic is not supported yet (a date"
                + " plus or minus an integer, a date minus a date, and a date or timestamp plus or minus an interval"
                + " constant are)", b.pos());
    }
}
