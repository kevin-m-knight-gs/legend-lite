// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * THE H2 session — the engine's own, VERBATIM (convergence batch C,
 * user-ratified "converge directly", landed 2026-08-29): H2Defaults
 * from legend-engine-xt-relationalStore-h2-execution-2.1.214 —
 * case-SENSITIVE identifiers, no DATABASE_TO_UPPER override, the
 * engine's NON_KEYWORDS list (incl OVER), MODE=LEGACY. ONE definition
 * shared by every H2 session opener so all targets open IDENTICAL
 * sessions.
 *
 * <p>HISTORY: this constant used to add CASE_INSENSITIVE_IDENTIFIERS
 * + DATABASE_TO_UPPER=false — session-level compensation for OUR OWN
 * spelling skew (create full-quoted vs insert bare; renderer quoting
 * what DDL spelled bare; bare aliases uppercasing in labels). The
 * cure was conform-by-emission end to end: per-target DDL/insert
 * spelling, declared-quote preservation, and the ORIGIN-driven
 * renderer (a column reference knows whether its name is PHYSICAL —
 * DDL-owned, bare-unless-special — or DERIVED — query-invented,
 * quoted like the engine spells every alias). Receipts: all four
 * consumers probed green on THIS session (oracle sweep
 * census-byte-identical; h2 lane 1369; PCT DuckDB + PCT h2modern).
 */
public final class H2Settings {

    private H2Settings() {
    }

    /** JDBC-URL suffix, {@code ;KEY=VALUE} form. */
    public static final String SETTINGS =
            ";NON_KEYWORDS=ANY,ASYMMETRIC,AUTHORIZATION,CAST,"
            + "CURRENT_PATH,CURRENT_ROLE,DAY,DEFAULT,ELSE,END,HOUR,KEY,"
            + "MINUTE,MONTH,SECOND,SESSION_USER,SET,SOME,SYMMETRIC,"
            + "SYSTEM_USER,TO,UESCAPE,USER,VALUE,WHEN,YEAR,OVER"
            + ";MODE=LEGACY";
}
