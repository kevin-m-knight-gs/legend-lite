// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE SETTINGS VECTOR an {@code #IQ{...}#} island is read under (docs/IQ_SQL_STUDY_2026_10_13.md §9.17; IQ island,
 * 2026-10-14, the options framework): a value for every setting of the registry ({@link IqOption}), built the way IQ
 * builds a connection's -- the engine base's defaults (IQ 16's or SQL Anywhere 17's), then the database's level (its
 * creation properties, its PUBLIC options), then the client's login (a TDS login's {@code sp_tsql_environment}, IQ's
 * ODBC driver's date and time formats), then the connection's own. Every rule of the translator that depends on a
 * setting reads it here, never a profile's name.
 *
 * <p>A setting's value is IQ's spelling of it, as {@code CONNECTION_PROPERTY} and {@code DB_PROPERTY} read it back:
 * {@code On} and {@code Off}, an integer's digits, {@code YMD}, a format as written.
 */
public final class IqSettings {

    /** The engine base whose defaults the vector starts from: an IQ 16 database's (whose queries over tables IQ's engine
     *  runs) or a SQL Anywhere 17 database's (whose every query SQL Anywhere runs). */
    public enum Base { IQ16, SA17 }

    /** The client a connection is made by: one at the database's defaults (embedded SQL, dbcapi), IQ's ODBC driver (its
     *  date and time formats), or a TDS login (Open Client, jConnect: {@code CommProtocol} {@code TDS}). */
    public enum Client { DEFAULT, ODBC, TDS }

    private final String spelling;
    private final Base base;
    private final Client client;
    /** The database's level: its creation properties and PUBLIC values, by setting. */
    private final Map<IqOption, String> database;
    /** The connection's own values (a login's overrides, the island's), by setting. */
    private final Map<IqOption, String> connection;

    private IqSettings(String spelling, Base base, Client client, Map<IqOption, String> database,
            Map<IqOption, String> connection) {
        this.spelling = spelling;
        this.base = base;
        this.client = client;
        this.database = Collections.unmodifiableMap(database);
        this.connection = Collections.unmodifiableMap(connection);
    }

    /** The settings of a connection by {@code client} to a database at {@code base}'s defaults, named {@code spelling}
     *  (a profile's name): the login's overrides are the connection's values. */
    static IqSettings preset(String spelling, Base base, Client client) {
        Map<IqOption, String> connection = new LinkedHashMap<>();
        for (IqOption o : IqOption.values()) {
            String login = client == Client.TDS ? o.tdsLogin() : client == Client.ODBC ? o.odbcLogin() : null;
            if (login != null && o.defaultFor(base) != null) {
                connection.put(o, login);
            }
        }
        return new IqSettings(spelling, base, client, new LinkedHashMap<>(), connection);
    }

    /** The value of {@code option}: the connection's, else the database's, else the base's default; null where the base
     *  has no such setting. */
    public @com.legend.base.Nullable String value(IqOption option) {
        String v = connection.get(option);
        if (v == null) {
            v = database.get(option);
        }
        return v != null ? v : option.defaultFor(base);
    }

    private boolean on(IqOption option) {
        return "On".equalsIgnoreCase(value(option));
    }

    private String text(IqOption option) {
        String v = value(option);
        if (v == null) {
            throw new IllegalStateException(option.spelling() + " has no value under " + spelling);
        }
        return v;
    }

    private int integer(IqOption option) {
        return Integer.parseInt(text(option));
    }

    /** How the island names these settings in its messages: the profile's name. */
    public String spelling() {
        return spelling;
    }

    public Base base() {
        return base;
    }

    public Client client() {
        return client;
    }

    /** Whether the database is IQ's ({@link Base#IQ16}): only there does a query over tables follow the IQ engine's
     *  rules; a SQL Anywhere 17 database's every query its front end runs. */
    public boolean iqDatabase() {
        return base == Base.IQ16;
    }

    /** {@code CaseSensitive}: the database compares strings case-sensitively (IQ's CASE RESPECT; SQL Anywhere's
     *  default ignores case). */
    public boolean caseSensitive() {
        return on(IqOption.CASE_SENSITIVE);
    }

    /** {@code BlankPadding}: the database ignores trailing blanks in comparisons. */
    public boolean blankPadding() {
        return on(IqOption.BLANK_PADDING);
    }

    /** {@code CharSet}: the database's CHAR charset is ISO-8859-1 (IQ's), not UTF-8. */
    public boolean latin1() {
        return text(IqOption.CHAR_SET).equalsIgnoreCase("ISO_8859-1:1987");
    }

    /** {@code ansinull}: On, a comparison with NULL is UNKNOWN; Off (a TDS login), {@code = NULL} means IS NULL. */
    public boolean ansinull() {
        return on(IqOption.ANSINULL);
    }

    /** {@code tsql_variables}: On (a TDS login, as its login procedure sets it), {@code @name} is a placeholder whose
     *  value the client binds (IQ island, 2026-10-14, review 6's M1, measured on SQL Anywhere 17: it reads the bound
     *  value even where a column of its name is in scope); Off, a connection's variable, read after such a column. */
    public boolean tsqlVariables() {
        return on(IqOption.TSQL_VARIABLES);
    }

    /** {@code quoted_identifier}: On, {@code "x"} is an identifier; Off (a TDS login), a string. */
    public boolean quotedIdentifier() {
        return on(IqOption.QUOTED_IDENTIFIER);
    }

    /** {@code escape_character}: On, {@code \n}, {@code \\} and {@code \xHH} are escapes in a string; Off (a TDS login),
     *  a backslash is a character. */
    public boolean escapeCharacter() {
        return on(IqOption.ESCAPE_CHARACTER);
    }

    /** {@code date_order}: how a date string is read. */
    public String dateOrder() {
        return text(IqOption.DATE_ORDER);
    }

    /** {@code date_format}: how a date is written as text. */
    public String dateFormat() {
        return text(IqOption.DATE_FORMAT);
    }

    /** {@code time_format}. */
    public String timeFormat() {
        return text(IqOption.TIME_FORMAT);
    }

    /** {@code timestamp_format}. */
    public String timestampFormat() {
        return text(IqOption.TIMESTAMP_FORMAT);
    }

    /** {@code precision}: the widest NUMERIC. */
    public int precision() {
        return integer(IqOption.PRECISION);
    }

    /** {@code scale}: the scale of an unqualified NUMERIC's computations. */
    public int scale() {
        return integer(IqOption.SCALE);
    }

    @Override
    public String toString() {
        return spelling;
    }
}
