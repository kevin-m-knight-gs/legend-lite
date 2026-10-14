// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import java.util.ArrayList;
import java.util.List;

/**
 * The connection profiles an {@code #IQ{...}#} island is written for (docs/IQ_SQL_STUDY_2026_10_13.md §4, I1 and I2):
 * a closed set, {@code #IQ:<profile>{...}#} naming one, {@code iq16-default} when none is named. A profile is the
 * database's creation settings and the options a client of that kind has after connecting: the research's Tier 1
 * option vector (its OPTIONS §9), the values the oracle's profiles set (tools/iq-oracle/README.md, "Profiles").
 *
 * <p>The {@code iq16-*} profiles are an IQ database (case-sensitive, blank-padded, ISO-8859-1 with a binary
 * collation); the {@code sqla17-*} ones a SQL Anywhere 17 database at its defaults (case-insensitive, not
 * blank-padded, UTF-8). {@code *-tds} is a TDS login (Open Client, jConnect), {@code iq16-odbc} the IQ ODBC driver.
 * Which rules a statement follows besides (IQ's engine or SQL Anywhere's front end) is the statement's own:
 * {@link IqDialect.Engine}.
 */
public enum IqProfile {

    IQ16_DEFAULT("iq16-default", true, true, true, true, true, true, "YMD", "YYYY-MM-DD", "HH:NN:SS.SSS",
            "YYYY-MM-DD HH:NN:SS.SSS", 126, 38),
    IQ16_ODBC("iq16-odbc", true, true, true, true, true, true, "YMD", "yyyy-mm-dd", "hh:nn:ss",
            "yyyy-mm-dd hh:nn:ss.ssssss", 126, 38),
    IQ16_TDS("iq16-tds", true, true, true, false, false, false, "MDY", "YYYY-MM-DD", "HH:NN:SS.SSS",
            "YYYY-MM-DD HH:NN:SS.SSS", 126, 38),
    SQLA17_DEFAULT("sqla17-default", false, false, false, true, true, true, "YMD", "YYYY-MM-DD", "HH:NN:SS.SSS",
            "YYYY-MM-DD HH:NN:SS.SSS", 30, 6),
    SQLA17_TDS("sqla17-tds", false, false, false, false, false, false, "MDY", "YYYY-MM-DD", "HH:NN:SS.SSS",
            "YYYY-MM-DD HH:NN:SS.SSS", 30, 6);

    /** The profile of an island that names none. */
    public static final IqProfile DEFAULT = IQ16_DEFAULT;

    private final String spelling;
    private final boolean caseSensitive;
    private final boolean blankPadding;
    private final boolean latin1;
    private final boolean ansinull;
    private final boolean quotedIdentifier;
    private final boolean escapeCharacter;
    private final String dateOrder;
    private final String dateFormat;
    private final String timeFormat;
    private final String timestampFormat;
    private final int precision;
    private final int scale;

    IqProfile(String spelling, boolean caseSensitive, boolean blankPadding, boolean latin1, boolean ansinull,
            boolean quotedIdentifier, boolean escapeCharacter, String dateOrder, String dateFormat, String timeFormat,
            String timestampFormat, int precision, int scale) {
        this.spelling = spelling;
        this.caseSensitive = caseSensitive;
        this.blankPadding = blankPadding;
        this.latin1 = latin1;
        this.ansinull = ansinull;
        this.quotedIdentifier = quotedIdentifier;
        this.escapeCharacter = escapeCharacter;
        this.dateOrder = dateOrder;
        this.dateFormat = dateFormat;
        this.timeFormat = timeFormat;
        this.timestampFormat = timestampFormat;
        this.precision = precision;
        this.scale = scale;
    }

    /** The profile {@code name} spells, exactly; null when it names none of the closed set. */
    public static @com.legend.base.Nullable IqProfile named(String name) {
        for (IqProfile p : values()) {
            if (p.spelling.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The closed set's names, for a refusal's message. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (IqProfile p : values()) {
            names.add(p.spelling);
        }
        return names;
    }

    /** The refusal of a profile name outside the closed set, in the words both the parser and the wire reader use. */
    public static String unknown(String name) {
        return "unknown #IQ{...}# profile '" + name + "': a profile is one of " + String.join(", ", names());
    }

    /** As an island names it: {@code iq16-default}, ... */
    public String spelling() {
        return spelling;
    }

    /** Whether the profile is an IQ database's ({@code iq16-*}): only there does a query over tables follow the IQ
     *  engine's rules; a {@code sqla17-*} profile is a SQL Anywhere database, whose every query its front end runs. */
    public boolean iqDatabase() {
        return spelling.startsWith("iq16");
    }

    /** The database compares strings case-sensitively (IQ's CASE RESPECT; SQL Anywhere's default ignores case). */
    public boolean caseSensitive() {
        return caseSensitive;
    }

    /** The database ignores trailing blanks in comparisons (BLANK PADDING ON). */
    public boolean blankPadding() {
        return blankPadding;
    }

    /** The database's CHAR charset is ISO-8859-1 (IQ's), not UTF-8. */
    public boolean latin1() {
        return latin1;
    }

    /** {@code ansinull}: On, a comparison with NULL is UNKNOWN; Off (a TDS login), {@code = NULL} means IS NULL. */
    public boolean ansinull() {
        return ansinull;
    }

    /** {@code tsql_variables}: On (a TDS login, as its login procedure sets it), {@code @name} is a placeholder whose
     *  value the client binds (IQ island, 2026-10-14, review 6's M1, measured on SQL Anywhere 17: it reads the bound
     *  value even where a column of its name is in scope); Off, a connection's variable, read after such a column. */
    public boolean tsqlVariables() {
        return this == IQ16_TDS || this == SQLA17_TDS;
    }

    /** {@code quoted_identifier}: On, {@code "x"} is an identifier; Off (a TDS login), a string. */
    public boolean quotedIdentifier() {
        return quotedIdentifier;
    }

    /** {@code escape_character}: On, {@code \n}, {@code \\} and {@code \xHH} are escapes in a string; Off (a TDS login),
     *  a backslash is a character. */
    public boolean escapeCharacter() {
        return escapeCharacter;
    }

    /** {@code date_order}: how a date string is read. */
    public String dateOrder() {
        return dateOrder;
    }

    /** {@code date_format}: how a date is written as text. */
    public String dateFormat() {
        return dateFormat;
    }

    /** {@code time_format}. */
    public String timeFormat() {
        return timeFormat;
    }

    /** {@code timestamp_format}. */
    public String timestampFormat() {
        return timestampFormat;
    }

    /** {@code precision}: the widest NUMERIC. */
    public int precision() {
        return precision;
    }

    /** {@code scale}: the scale of an unqualified NUMERIC's computations. */
    public int scale() {
        return scale;
    }
}
