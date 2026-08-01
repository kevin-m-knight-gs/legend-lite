// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.harness;

import com.legend.exec.ExecutionResult;
import com.legend.exec.Row;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ADVISORY SECOND TARGET (#67): golden engine SQL executes on a real
 * in-memory H2 — the engine's own dialect — over the SAME raw seed
 * statements the test ran (recorded verbatim at the RawSqlBoundary,
 * which is H2-flavored BY DEFINITION), and its rows verify against the
 * rows our pipeline produced on DuckDB. Row-set equality is the
 * contract; a divergence is an honest FAIL naming both sides. H2 is a
 * TEST-SCOPED dependency of the corpus harness only — this class detects
 * the driver reflectively and reports {@link #ready()} false without it,
 * leaving golden-SQL asserts advisory exactly as before.
 *
 * <p>Connection settings mirror the engine's own H2 2.1.214 test server
 * (H2Manager: NON_KEYWORDS + MODE=LEGACY) plus DATABASE_TO_UPPER=false so
 * the corpus's unquoted mixed-case DDL matches the goldens' quoted
 * column spellings.
 */
public final class H2Verify {

    private H2Verify() {
    }

    /** Verification could not run (driver absent, seed replay failed,
     * golden text not executable) — the caller stays advisory. */
    public static final class Unverifiable extends RuntimeException {
        public Unverifiable(String msg, @com.legend.Nullable Throwable cause) {
            super(msg, cause);
        }
    }

    private static final boolean READY = detect();
    private static final java.util.concurrent.atomic.AtomicInteger COUNTER =
            new java.util.concurrent.atomic.AtomicInteger();

    private static boolean detect() {
        try {
            Class.forName("org.h2.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean ready() {
        return READY;
    }

    /** MILESTONE-1 counters (H2_BACKEND.md §12 step 5): real H2
     * execution of OUR byte-matched SQL, held to our DuckDB rows.
     * Sweep-scoped (fresh JVM per surefire run); the corpus runner
     * reports them as the h2-exec scoreboard line. */
    public static final java.util.concurrent.atomic.LongAdder M1_VERIFIED =
            new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder M1_DIVERGED =
            new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder M1_UNVERIFIABLE =
            new java.util.concurrent.atomic.LongAdder();

    /** The engine's H2 session settings (H2Manager parity) — shared with
     * the {@code -Drcorpus.backend=h2} portability sweep so the replay
     * oracle and the real backend open IDENTICAL sessions. */
    public static final String SETTINGS =
            // CASE_INSENSITIVE_IDENTIFIERS mirrors DuckDB's matching —
            // the SAME recorded statements already ran there; quoted
            // model-DDL case vs unquoted corpus-INSERT case must not
            // diverge between the two targets
            ";MODE=LEGACY;DATABASE_TO_UPPER=false"
            + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=ANY,"
            + "ASYMMETRIC,AUTHORIZATION,CAST,CURRENT_PATH,CURRENT_ROLE,"
            + "DAY,DEFAULT,ELSE,END,HOUR,KEY,MINUTE,MONTH,SECOND,"
            + "SESSION_USER,SET,SOME,SYMMETRIC,SYSTEM_USER,TO,UESCAPE,"
            + "USER,VALUE,WHEN,YEAR";

    /**
     * Replay {@code seeds} on a fresh H2, run {@code goldenSql}, compare
     * its rows with {@code ours} as ORDER-INSENSITIVE multisets of
     * normalized cells. Returns null when the row sets match, else a
     * divergence message; throws {@link Unverifiable} when H2 cannot
     * evaluate the inputs at all.
     */
    public static @com.legend.Nullable String verify(List<String> seeds, String goldenSql,
            ExecutionResult ours) {
        if (!READY) {
            throw new Unverifiable("h2 driver not on classpath", null);
        }
        // only FLAT TABULAR frames compare cell-for-cell against raw SQL
        // rows: class/graph carriers wrap rows in JSON.
        if (!(ours instanceof ExecutionResult.Tabular tab)) {
            throw new Unverifiable("non-tabular result frame", null);
        }
        // ENUM-typed frames decline because SOME frames decode enums
        // POST-SQL: the SQL (ours or golden) selects the raw source code
        // while the compared frame carries decoded names — a LAYER
        // mismatch, not a divergence (c42 witnesses: the 4 denorm/
        // multigrain tests compare [.|1] raw vs [.|CITY] decoded, plus 6
        // advisory goldens selecting raw codes). Frames whose decode IS
        // in the SQL (CASE emission — the W40 family) verified CLEAN
        // when probed (milestoning h2-exec 51/0 with this arm bypassed);
        // retiring the arm for real means replaying H2 rows through the
        // SAME post-SQL decode transform the frame ran — its own rung.
        for (com.legend.exec.Column c : tab.columns()) {
            if (c.pureType()
                    instanceof com.legend.compiler.element.type.Type.EnumType) {
                throw new Unverifiable(
                        "enum-decoded column (post-transform rows)", null);
            }
        }
        int id = COUNTER.getAndIncrement();
        try (Connection h2 = DriverManager.getConnection(
                "jdbc:h2:mem:advisory" + id + SETTINGS, "sa", "")) {
            try (Statement st = h2.createStatement()) {
                for (String seed : seeds == null ? List.<String>of()
                        : seeds) {
                    for (String one : seed.split(";\\s*\n|;\\s*$")) {
                        if (one.isBlank()) {
                            continue;
                        }
                        try {
                            st.execute(one);
                        } catch (SQLException e) {
                            throw new Unverifiable("seed replay: "
                                    + e.getMessage(), e);
                        }
                    }
                }
                List<String> theirs = new ArrayList<>();
                int[] theirsCols = {0};
                try (ResultSet rs = st.executeQuery(goldenSql)) {
                    int n = rs.getMetaData().getColumnCount();
                    theirsCols[0] = n;
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            if (i > 1) {
                                row.append('|');
                            }
                            row.append(norm(rs.getObject(i)));
                        }
                        theirs.add(row.toString());
                    }
                } catch (SQLException e) {
                    throw new Unverifiable("golden execution: "
                            + e.getMessage(), e);
                }
                if (theirsCols[0] != tab.columns().size()) {
                    // our frame carries harness-added columns (driver
                    // PKs, order keys) the golden never selects — an
                    // ARITY gap is a layer difference, not a divergence
                    throw new Unverifiable("column arity differs: golden "
                            + theirsCols[0] + " vs frame "
                            + tab.columns().size(), null);
                }
                List<String> mine = new ArrayList<>();
                for (Row r : ours.rows()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 0; i < r.values().size(); i++) {
                        if (i > 0) {
                            row.append('|');
                        }
                        row.append(norm(r.values().get(i)));
                    }
                    mine.add(row.toString());
                }
                Collections.sort(theirs);
                Collections.sort(mine);
                if (theirs.equals(mine)) {
                    return null;
                }
                return "h2-advisory divergence: golden SQL on H2 gave "
                        + theirs.size() + " row(s) " + head(theirs)
                        + ", our pipeline gave " + mine.size() + " row(s) "
                        + head(mine);
            }
        } catch (SQLException e) {
            throw new Unverifiable("h2 connection: " + e.getMessage(), e);
        }
    }

    private static String head(List<String> rows) {
        return rows.subList(0, Math.min(rows.size(), 5)).toString();
    }

    /** One normalization for BOTH sides: JDBC drivers disagree on exact
     * numeric/temporal classes; the database-level VALUE is the
     * contract. */
    private static String norm(Object v) {
        if (v == null) {
            return "<null>";
        }
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Number) {
            try {
                BigDecimal d = new BigDecimal(v.toString());
                // INTEGRAL values compare EXACTLY — the old blanket
                // MathContext(10) made two epoch-millis differing in the
                // last 3 digits compare EQUAL (H2_BACKEND.md §12 step 3:
                // a silent false PASS on BOTH sides of the oracle).
                if (d.stripTrailingZeros().scale() <= 0) {
                    return d.stripTrailingZeros().toPlainString();
                }
                // FLOATING values keep a CROSS-ENGINE tolerance of 10
                // significant digits: H2 divides in exact DECIMAL,
                // DuckDB in binary double, and the tails genuinely
                // diverge around digit 11-12 WITH rounding-boundary
                // straddles (witness: testUnionWithWtdAndPwa raw
                // ...394497 vs ...39455 rounds apart at BOTH 11 and 12).
                // Fixed-digit normalization cannot separate 1-ulp tails
                // from real sub-1e-10 differences; 10 digits is the
                // empirically-clean cross-engine floor. The REAL defect
                // (integral collapse — epoch-millis comparing equal) is
                // fixed above by the exact integral arm.
                return d.round(new java.math.MathContext(10))
                        .stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return v.toString();
            }
        }
        String s = v.toString();
        // timestamp spellings: trim trailing fractional zeros and the
        // bare '.0' second fraction ('2015-08-26 00:00:00.0' ==
        // '2015-08-26 00:00:00')
        if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")) {
            s = s.replaceAll("\\.?0+$", "");
        }
        return s;
    }
}
