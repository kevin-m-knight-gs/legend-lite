// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

/**
 * THE H2 session — the engine's own, verbatim (convergence batch C,
 * user-ratified "converge directly", 2026-08-29): H2Defaults from
 * legend-engine-xt-relationalStore-h2-execution-2.1.214 —
 * case-SENSITIVE identifiers, no DATABASE_TO_UPPER override, the
 * engine's NON_KEYWORDS list (incl OVER), MODE=LEGACY. ONE definition
 * shared by every H2 session opener (the harness replay oracle, the
 * {@code -Drcorpus.backend=h2} portability sweep) so all targets open
 * IDENTICAL sessions.
 *
 * <p>HISTORY: this constant used to add CASE_INSENSITIVE_IDENTIFIERS
 * + DATABASE_TO_UPPER=false — session-level compensation for OUR OWN
 * emitters disagreeing on identifier casing (create full-quoted vs
 * insert bare; renderer quoting schema parts the DDL spelled bare).
 * The emitters now conform by emission (Ddl per-target spelling +
 * declared-quote preservation, H2.tableName engine rule, TDG
 * quoted-uppercase) and the flags are GONE — receipts: DuckDB-lane
 * census byte-identical and h2 lane 1370 >= the 1369 lenient
 * baseline, both measured on this exact session.
 */
public final class H2Settings {

    private H2Settings() {
    }

    /** JDBC-URL suffix, {@code ;KEY=VALUE} form. BATCH C PARKED
     * (2026-08-29): the cutover to {@link #ENGINE_CASED} verbatim is
     * blocked ONLY on the PCT label seam — bare projection aliases
     * uppercase in result-set labels on a case-sensitive session, and
     * reference-spelling must match definition-spelling per column
     * origin (physical vs derived), which the flat SQL IR does not tag
     * yet. Receipts already in hand: the replay ORACLE and the h2
     * BACKEND lane are both green on ENGINE_CASED (census
     * byte-identical / 1370 >= 1369). */
    public static final String SETTINGS =
            ";MODE=LEGACY;DATABASE_TO_UPPER=false"
            + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=ANY,"
            + "ASYMMETRIC,AUTHORIZATION,CAST,CURRENT_PATH,CURRENT_ROLE,"
            + "DAY,DEFAULT,ELSE,END,HOUR,KEY,MINUTE,MONTH,SECOND,"
            + "SESSION_USER,SET,SOME,SYMMETRIC,SYSTEM_USER,TO,UESCAPE,"
            + "USER,VALUE,WHEN,YEAR";

    /** The engine's session EXACTLY as H2Defaults spells it
     * (legend-engine-xt-relationalStore-h2-execution-2.1.214):
     * case-SENSITIVE identifiers, no DATABASE_TO_UPPER override, the
     * engine's NON_KEYWORDS list (incl OVER). The replay oracle
     * retries case-collision goldens on this session; becomes THE
     * session when batch C lands. */
    public static final String ENGINE_CASED =
            ";NON_KEYWORDS=ANY,ASYMMETRIC,AUTHORIZATION,CAST,"
            + "CURRENT_PATH,CURRENT_ROLE,DAY,DEFAULT,ELSE,END,HOUR,KEY,"
            + "MINUTE,MONTH,SECOND,SESSION_USER,SET,SOME,SYMMETRIC,"
            + "SYSTEM_USER,TO,UESCAPE,USER,VALUE,WHEN,YEAR,OVER"
            + ";MODE=LEGACY";
}
