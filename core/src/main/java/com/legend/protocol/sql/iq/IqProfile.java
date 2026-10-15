// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import java.util.ArrayList;
import java.util.List;

/**
 * The profiles an {@code #IQ{...}#} island is written for (docs/IQ_SQL_STUDY_2026_10_13.md §4, I1 and I2): a closed set
 * of presets of the settings vector ({@link IqSettings}), {@code #IQ:<profile>{...}#} naming one, {@code iq16-default}
 * when none is named. A preset is an engine base and a client: the base's database at its defaults, and the options a
 * client of that kind has after connecting (the research's OPTIONS §9; the values the oracle's profiles set,
 * tools/iq-oracle/README.md, "Profiles").
 *
 * <p>The {@code iq16-*} presets are an IQ database (case-sensitive, blank-padded, ISO-8859-1 with a binary collation);
 * the {@code sqla17-*} ones a SQL Anywhere 17 database at its defaults (case-insensitive, not blank-padded, UTF-8).
 * {@code *-tds} is a TDS login (Open Client, jConnect), {@code *-odbc} the IQ ODBC driver. Which rules a statement
 * follows besides (IQ's engine or SQL Anywhere's front end) is the statement's own: {@link IqDialect.Engine}.
 *
 * <p>IQ island, landing 4 (WP1), 2026-10-14: the {@code iq17-*} presets are the IQ base with the database properties of
 * the real SAP IQ 17.1 the island is judged against (the user's HANA Cloud data lake, tools/iq-oracle's live backend):
 * case-sensitive, blank-padded, collation UTF8BIN, charset UTF-8, PUBLIC precision 126 and scale 38 (and, IQ island,
 * 2026-10-15, PUBLIC tsql_outer_joins On, measured). The {@code iq16-*}
 * presets stay as they were, judged by SQL Anywhere 17, and are not verified on a real IQ 16.
 */
public enum IqProfile {

    IQ16_DEFAULT("iq16-default", IqSettings.Base.IQ16, IqSettings.Client.DEFAULT),
    IQ16_ODBC("iq16-odbc", IqSettings.Base.IQ16, IqSettings.Client.ODBC),
    IQ16_TDS("iq16-tds", IqSettings.Base.IQ16, IqSettings.Client.TDS),
    SQLA17_DEFAULT("sqla17-default", IqSettings.Base.SA17, IqSettings.Client.DEFAULT),
    SQLA17_TDS("sqla17-tds", IqSettings.Base.SA17, IqSettings.Client.TDS),
    IQ17_DEFAULT("iq17-default", IqSettings.Base.IQ16, IqSettings.Client.DEFAULT, IqSettings.UTF8BIN),
    IQ17_ODBC("iq17-odbc", IqSettings.Base.IQ16, IqSettings.Client.ODBC, IqSettings.UTF8BIN),
    IQ17_TDS("iq17-tds", IqSettings.Base.IQ16, IqSettings.Client.TDS, IqSettings.UTF8BIN);

    /** The profile of an island that names none. */
    public static final IqProfile DEFAULT = IQ16_DEFAULT;

    private final String spelling;
    private final IqSettings settings;

    IqProfile(String spelling, IqSettings.Base base, IqSettings.Client client) {
        this(spelling, base, client, java.util.Map.of());
    }

    /** A preset whose database's level is {@code database} (its creation properties) over the base's defaults. */
    IqProfile(String spelling, IqSettings.Base base, IqSettings.Client client, java.util.Map<IqOption, String> database) {
        this.spelling = spelling;
        this.settings = IqSettings.preset(spelling, base, client, database);
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

    /** The settings vector the preset is. */
    public IqSettings settings() {
        return settings;
    }
}
