// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.Compiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Remediation T1.8 — a mapping {@code ~groupBy} using {@code avg(COL)}
 * type-checked (the meta::legend::lite::avg native) and then died at
 * lowering: the reducer catalog carried {@code average}/{@code mean}
 * but not the DSL's {@code avg}. The pair now registers together.
 */
class GroupByAvgMappingTest {

    private static final String MODEL = """
            Class g::Acct { k: String[1]; avgQty: Float[1]; }
            Database g::DB ( Table T (K VARCHAR(10), QTY INTEGER) )
            Mapping g::M (
              *g::Acct : Relational {
                ~groupBy([g::DB] T.K)
                ~mainTable [g::DB] T
                k: T.K,
                avgQty: avg(T.QTY)
              }
            )
            Runtime g::RT { mappings: [g::M]; }
            """;

    @Test
    @DisplayName("~groupBy avg(COL) lowers and executes")
    void avgLowersAndExecutes() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE T (K VARCHAR, QTY INTEGER)");
                st.execute("INSERT INTO T VALUES ('a', 10), ('a', 20), ('b', 5)");
            }
            var r = Compiler.execute(MODEL,
                    "|g::Acct.all()->project(~[k: x|$x.k, q: x|$x.avgQty])"
                            + "->sort(~k->ascending())", "g::RT", c);
            assertEquals(List.of("a|15.0", "b|5.0"),
                    r.rows().stream().map(row -> row.get(0) + "|" + row.get(1))
                            .toList());
        }
    }
}
