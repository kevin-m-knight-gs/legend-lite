// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.Compiler;
import com.legend.compiler.NameResolver;
import com.legend.compiler.spec.SpecCompiler;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.parser.SpecParser;
import com.legend.resolver.StoreResolver;
import com.legend.sql.SqlQuery;
import com.legend.sql.dialect.DuckDb;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANY-VALUED primitive projection column = ROW EXPLOSION (engine union
 * subselect, row-major; our emission: select-list UNNEST — same
 * semantics on DuckDB). Scalar sibling columns REPEAT per exploded row
 * (corpus testConcatenateFlatWithOtherProperty).
 */
class ExplodeProbeTest {

    private static final String MODEL = """
            Class x::T { id: Integer[1]; }
            Database x::DB ( Table TT (ID INTEGER PRIMARY KEY) )
            Mapping x::M ( *x::T : Relational { ~mainTable [x::DB] TT id: TT.ID } )
            Runtime x::RT { mappings: [x::M]; }
            """;

    @Test
    @DisplayName("many-valued column explodes rows; scalar sibling repeats")
    void twoColumnExplosion() throws SQLException {
        var ctx = Compiler.compileModel(MODEL);
        SpecCompiler specs = new SpecCompiler(ctx);
        List<TypedSpec> body = specs.typeQueryBody(
                NameResolver.resolveQuery(SpecParser.parse(
                        "x::T.all()->project([t|$t.id, t|$t.id->concatenate($t.id+18)],"
                        + " ['simple','Concatenated'])->from(x::M, x::RT)")));
        List<TypedSpec> resolved = new StoreResolver(ctx, specs).resolve(body, null);
        SqlQuery plan = new Lowerer().lower(resolved);
        String sql = new DuckDb().render(plan);
        System.out.println("[explode-sql] " + sql);
        assertTrue(sql.contains("UNNEST"), sql);
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE TT (ID INTEGER)");
                st.execute("INSERT INTO TT VALUES (1), (2)");
            }
            List<String> rows = new ArrayList<>();
            try (Statement st = conn.createStatement();
                    ResultSet rs = st.executeQuery(sql + " ORDER BY 1, 2")) {
                while (rs.next()) {
                    rows.add(rs.getInt(1) + "|" + rs.getInt(2));
                }
            }
            assertEquals(List.of("1|1", "1|19", "2|2", "2|20"), rows, sql);
        }
    }
}
