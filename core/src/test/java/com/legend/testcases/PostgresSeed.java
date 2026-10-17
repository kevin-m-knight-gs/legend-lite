// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testcases;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The parity lanes' tables on Postgres, the database upstream snapped its answers on, made as upstream's
 * {@code SchemaManager} makes them there ({@code TestPostgresParity}): its setup (the digest functions, the sequence the
 * sequence functions' cases use, fresh), each table's declared types verbatim (a {@code JSON} column stays {@code JSON}
 * and a {@code JSONB} one {@code JSONB}: the snapped answers read them so), a table's dependents dropped with it, and a
 * JSON value bound as pgjdbc's {@code PGobject}. The other lanes' tables are the product's, rendered by their dialect
 * ({@link SqlParity#seed}). Postgres's own (the dialect program's Phase 6, 2026-10-17; this was {@code SqlParity.seed}'s
 * {@code postgres} branch).
 */
public final class PostgresSeed {

    private PostgresSeed() {
    }

    /** Creates and fills {@code tables} on {@code c}, a session on Postgres, as upstream does. */
    public static void seed(Connection c, List<SqlParity.Table> tables) throws SQLException {
        try (Statement s = c.createStatement()) {
            // upstream's Postgres setup: the digest functions, and the sequence the sequence functions' cases use,
            // fresh (the cases run in corpus order, so their answers are fixed)
            s.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            s.execute("DROP SEQUENCE IF EXISTS test_seq");
            s.execute("CREATE SEQUENCE test_seq START 1");
        }
        for (SqlParity.Table t : tables) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE " + t.name() + " (");
            for (int i = 0; i < t.columns().size(); i++) {
                SqlParity.Column col = t.columns().get(i);
                ddl.append(i > 0 ? ", " : "").append(col.name()).append(' ').append(col.type());
            }
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS " + t.name() + " CASCADE");
                s.execute(ddl.append(')').toString());
            }
            SqlParity.fill(c, t, PostgresSeed::bindJson);
        }
    }

    /** A JSON value as upstream binds it: a {@code PGobject} of the column's type, made by reflection (core's tests do
     *  not compile against pgjdbc). */
    private static void bindJson(java.sql.PreparedStatement ps, int i, String type, String json) throws SQLException {
        try {
            Object value = Class.forName("org.postgresql.util.PGobject").getConstructor().newInstance();
            value.getClass().getMethod("setType", String.class).invoke(value, type.contains("JSONB") ? "jsonb" : "json");
            value.getClass().getMethod("setValue", String.class).invoke(value, json);
            ps.setObject(i, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("pgjdbc's PGobject", e);
        }
    }
}
