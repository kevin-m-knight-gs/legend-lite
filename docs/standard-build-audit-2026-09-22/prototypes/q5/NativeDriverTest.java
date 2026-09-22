package com.legend;

import java.sql.*;

/** Q5: do the NATIVE-extracting JDBC drivers work inside Bazel's sandbox? */
public final class NativeDriverTest {

    public static void main(String[] args) throws Exception {
        System.out.println("TEST_TMPDIR      = " + System.getenv("TEST_TMPDIR"));
        System.out.println("java.io.tmpdir   = " + System.getProperty("java.io.tmpdir"));
        System.out.println("user.home        = " + System.getProperty("user.home"));
        System.out.println("os.arch          = " + System.getProperty("os.arch"));
        System.out.println("PWD              = " + System.getProperty("user.dir"));

        one("DuckDB", "org.duckdb.DuckDBDriver", "jdbc:duckdb:",
            "SELECT 40 + 2 AS answer, version() AS v");
        one("SQLite", "org.sqlite.JDBC", "jdbc:sqlite::memory:",
            "SELECT 40 + 2 AS answer, sqlite_version() AS v");
        one("H2",     "org.h2.Driver",    "jdbc:h2:mem:q5;DB_CLOSE_DELAY=-1",
            "SELECT 40 + 2 AS answer, H2VERSION() AS v");
        System.out.println("ALL THREE OK");
    }

    private static void one(String label, String driver, String url, String sql) throws Exception {
        Class.forName(driver);
        long t0 = System.nanoTime();
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new IllegalStateException(label + ": no rows");
            System.out.printf("%-7s answer=%d version=%s  (%d ms)%n",
                label, r.getInt("answer"), r.getString("v"),
                (System.nanoTime() - t0) / 1_000_000);
        }
    }
}
