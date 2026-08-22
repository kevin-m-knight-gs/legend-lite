// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.element.type.Type;
import com.legend.exec.CanonicalForm;
import com.legend.sql.OutputCol;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;
import com.legend.sql.SqlType;
import com.legend.sql.dialect.DuckDb;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE DUAL-RENDER CONFORMANCE BATTERY (V10c close): the SQL canonical
 * render and the host reference render are ONE spec
 * (CANONICAL_FORM_SPEC §2) implemented twice — this pin holds them
 * byte-identical over a value battery that includes the magnitudes the
 * old DECIMAL(38,18) unfold silently zeroed (1e-30, 1e300). The DB
 * computes its text; the host computes its text; they must agree.
 */
class SqlCanonConformanceTest {

    @Test
    void floatCanonAgreesWithHostAcrossMagnitudes() throws Exception {
        double[] battery = {
                3.14, 17.000, 1.3421e8, 134.21e-10, .01, 0.0, -0.0,
                -5.59, 5.59, 1.5e-30, 1e300, -2.5e-15, 123456.789,
                9007199254740993.0, 1e16,
        };
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (double d : battery) {
                assertEquals(hostText(d), dbCanon(c, d, Type.Primitive.FLOAT),
                        "float canon diverged for " + d);
            }
        }
    }

    @Test
    void otherKindsAgree() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            assertEquals("42", dbCanon(c, new SqlExpr.IntLit(42),
                    Type.Primitive.INTEGER));
            assertEquals("true", dbCanon(c, new SqlExpr.BoolLit(true),
                    Type.Primitive.BOOLEAN));
            assertEquals("8", dbCanon(c,
                    new SqlExpr.Cast(new SqlExpr.StringLit("8.00"),
                            new SqlType.Decimal(10, 2)),
                    Type.Primitive.DECIMAL));
            assertEquals("3.8", dbCanon(c,
                    new SqlExpr.Cast(new SqlExpr.StringLit("3.80"),
                            new SqlType.Decimal(10, 2)),
                    Type.Primitive.DECIMAL));
        }
    }

    private static String hostText(double d) {
        return switch (CanonicalForm.render(d)) {
            case CanonicalForm.Result.Text t -> t.value();
            case CanonicalForm.Result.Residue r -> "residue:" + r.reason();
        };
    }

    private static String dbCanon(Connection c, double d, Type t)
            throws Exception {
        return dbCanon(c, new SqlExpr.FloatLit(d), t);
    }

    private static String dbCanon(Connection c, SqlExpr v, Type t)
            throws Exception {
        SqlExpr canon = CanonicalRenderSql.scalarCanon(v, t);
        SqlSelect q = new SqlSelect(
                List.of(new SqlSelect.Projection(
                        java.util.Objects.requireNonNull(canon), "canon")),
                false, new SqlSource.Dual(), null, List.of(), null, null,
                List.of(), null, null,
                List.of(new OutputCol("canon", SqlType.Scalar.VARCHAR, true)));
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(new DuckDb().render(q))) {
            rs.next();
            return rs.getString(1);
        }
    }
}
