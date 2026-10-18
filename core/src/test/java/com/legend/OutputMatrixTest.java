// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend;

import com.legend.executionplan.ExecutionPlan;
import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testcases.PlanCases;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE OUTPUT MATRIX (docs/OUTPUT_LAYER_DESIGN_2026_10_10.md §5 step 1; the plan path program's step 2,
 * docs/PLAN_PATH_PROGRAM_2026_10_17.md): for each kind of value -- a whole, a fractional and an extreme Float, a Float
 * after arithmetic, a Decimal literal and after arithmetic, a DateTime with no, three and nine fraction digits, a
 * StrictDate, each as a value root and as a column -- and each of lite's own outputs on the plan path (CSV, the JSON
 * wire, a stream of JSON rows, Pure's TDS text) on DuckDB and H2, the text lite writes beside interpreted Pure's
 * (the reference: {@code docs/execution-plan-boundary-2026-10-05/probes/engine-reference/pure-interpreted-results.txt},
 * PureEval.java, 2026-10-10). Every cell is judged: a cell equal to Pure's text passes; a cell that differs is
 * either PINNED here with the text it writes today -- a deviation named, shrink-only, each later landing of the
 * output layer removing the pins it fixes -- or a failure. A pinned cell that starts writing Pure's text fails too,
 * so a fix is recorded by deleting its pin. The whole table is printed on every run.
 */
class OutputMatrixTest {
    private static final AtomicInteger H2_NAMES = new AtomicInteger();

    /** A table of one row, {@code ID = 2}, for the column forms. */
    private static final String MODEL = """
            ###Relational
            Database s::DB ( Table T ( ID INTEGER PRIMARY KEY ) )
            ###Connection
            RelationalDatabaseConnection s::Conn
            {
                store: s::DB;
                type: %s;
                specification: LocalH2 { testDataSetupCSV: 'default\\nT\\nID\\n2\\n'; };
                auth: DefaultH2;
            }
            ###Runtime
            Runtime s::RT { mappings: []; connections: [ s::DB: [ c1: s::Conn ] ]; }
            """;

    /** A kind: its name, the expression as a value root, the same value as a column, and interpreted Pure's text. */
    private record Kind(String name, String value, String column, String pure) {
    }

    private static final String T = "#>{s::DB.T}#->extend(~v: r|";

    private static final List<Kind> KINDS = List.of(
            new Kind("float_whole", "|2 * 1.5", "|" + T + "$r.ID * 1.5)->select(~[v])", "3.0"),
            new Kind("float_fraction", "|1.1", "|" + T + "$r.ID * 0.55)->select(~[v])", "1.1"),
            new Kind("float_arithmetic", "|3 * 1.1", "|" + T + "$r.ID * 1.65)->select(~[v])", "3.3"),
            new Kind("float_big", "|1.5e15", "|" + T + "$r.ID * 7.5e14)->select(~[v])", "1500000000000000.0"),
            new Kind("float_small", "|2.5e-7", "|" + T + "$r.ID * 1.25e-7)->select(~[v])", "0.00000025"),
            new Kind("decimal_literal", "|2.50D", "|" + T + "$r.ID * 1.25D)->select(~[v])", "2.50"),
            new Kind("decimal_arithmetic", "|2 * 2.50D", "|" + T + "$r.ID * 2.50D)->select(~[v])", "5.00"),
            new Kind("decimal_times_decimal", "|1.50D * 2.50D", "|" + T + "1.50D * 2.50D)->select(~[v])",
                    "3.7500"),
            new Kind("datetime_whole", "|%2024-01-02T10:30:00", "|" + T + "%2024-01-02T10:30:00)->select(~[v])",
                    "2024-01-02T10:30:00+0000"),
            new Kind("datetime_millis", "|%2024-01-02T10:30:00.123",
                    "|" + T + "%2024-01-02T10:30:00.123)->select(~[v])", "2024-01-02T10:30:00.123+0000"),
            new Kind("datetime_nanos", "|%2024-01-02T10:30:00.123456789",
                    "|" + T + "%2024-01-02T10:30:00.123456789)->select(~[v])",
                    "2024-01-02T10:30:00.123456789+0000"),
            new Kind("date", "|%2024-01-02", "|" + T + "%2024-01-02)->select(~[v])", "2024-01-02"));

    private static final List<TypedQuery.Output> OUTPUTS = List.of(TypedQuery.Output.CSV, TypedQuery.Output.JSON,
            TypedQuery.Output.STREAMED_JSON, TypedQuery.Output.TDS_TEXT);

    /**
     * Today's deviations from Pure's text, by {@code kind/form/output/database}, each the text lite writes
     * (measured 2026-10-18 at the matrix's first run; docs/OUTPUT_LAYER_DESIGN_2026_10_10.md §3 names the four gaps).
     * Shrink-only: a landing of the output layer deletes the pins it fixes, and a pin that no longer holds fails.
     */
    private static final Map<String, String> PINNED = Map.ofEntries(
            Map.entry("float_small/value/JSON/DuckDB", "2.5e-7"),
            Map.entry("float_small/value/STREAMED_JSON/DuckDB", "2.5e-7"),
            Map.entry("float_small/column/JSON/DuckDB", "2.5e-7"),
            Map.entry("float_small/column/STREAMED_JSON/DuckDB", "2.5e-7"),
            Map.entry("decimal_literal/value/JSON/DuckDB", "2.5"),
            Map.entry("decimal_literal/value/STREAMED_JSON/DuckDB", "2.5"),
            Map.entry("decimal_literal/column/JSON/DuckDB", "2.5"),
            Map.entry("decimal_literal/column/STREAMED_JSON/DuckDB", "2.5"),
            Map.entry("decimal_arithmetic/value/JSON/DuckDB", "5.0"),
            Map.entry("decimal_arithmetic/value/STREAMED_JSON/DuckDB", "5.0"),
            Map.entry("decimal_arithmetic/column/JSON/DuckDB", "5.0"),
            Map.entry("decimal_arithmetic/column/STREAMED_JSON/DuckDB", "5.0"),
            Map.entry("decimal_times_decimal/value/JSON/DuckDB", "3.75"),
            Map.entry("decimal_times_decimal/value/STREAMED_JSON/DuckDB", "3.75"),
            Map.entry("decimal_times_decimal/column/JSON/DuckDB", "3.75"),
            Map.entry("decimal_times_decimal/column/STREAMED_JSON/DuckDB", "3.75"),
            Map.entry("datetime_whole/value/CSV/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/value/JSON/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/value/STREAMED_JSON/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/column/CSV/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/column/JSON/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/column/STREAMED_JSON/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/column/TDS_TEXT/DuckDB", "2024-01-02T10:30:00.000+0000"),
            Map.entry("datetime_millis/value/CSV/DuckDB", "ERROR DataError: Binder Error: Could not choose a best candidate function for the function call \"strftime(VARCHAR, STRING_LITERAL)\". In order to select one, please add explicit type casts."),
            Map.entry("datetime_millis/value/JSON/DuckDB", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/value/STREAMED_JSON/DuckDB", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/value/TDS_TEXT/DuckDB", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/column/CSV/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_millis/column/JSON/DuckDB", "2024-01-02 10:30:00.123"),
            Map.entry("datetime_millis/column/STREAMED_JSON/DuckDB", "2024-01-02 10:30:00.123"),
            Map.entry("datetime_nanos/value/CSV/DuckDB", "ERROR DataError: Binder Error: Could not choose a best candidate function for the function call \"strftime(VARCHAR, STRING_LITERAL)\". In order to select one, please add explicit type casts."),
            Map.entry("datetime_nanos/value/JSON/DuckDB", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/value/STREAMED_JSON/DuckDB", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/value/TDS_TEXT/DuckDB", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/column/CSV/DuckDB", "2024-01-02 10:30:00"),
            Map.entry("datetime_nanos/column/JSON/DuckDB", "2024-01-02 10:30:00.123456789"),
            Map.entry("datetime_nanos/column/STREAMED_JSON/DuckDB", "2024-01-02 10:30:00.123456789"),
            Map.entry("datetime_nanos/column/TDS_TEXT/DuckDB", "2024-01-02T10:30:00.123+0000"),
            Map.entry("float_big/value/CSV/H2", "1.5E15"),
            Map.entry("float_big/value/JSON/H2", "1.5E15"),
            Map.entry("float_big/value/STREAMED_JSON/H2", "1.5E15"),
            Map.entry("float_big/value/TDS_TEXT/H2", "1.5E15"),
            Map.entry("float_big/column/CSV/H2", "1.5E15"),
            Map.entry("float_big/column/JSON/H2", "1.5E15"),
            Map.entry("float_big/column/STREAMED_JSON/H2", "1.5E15"),
            Map.entry("float_big/column/TDS_TEXT/H2", "1.5E15"),
            Map.entry("float_small/value/CSV/H2", "2.5E-7"),
            Map.entry("float_small/value/JSON/H2", "2.5E-7"),
            Map.entry("float_small/value/STREAMED_JSON/H2", "2.5E-7"),
            Map.entry("float_small/value/TDS_TEXT/H2", "2.5E-7"),
            Map.entry("float_small/column/CSV/H2", "2.5E-7"),
            Map.entry("float_small/column/JSON/H2", "2.5E-7"),
            Map.entry("float_small/column/STREAMED_JSON/H2", "2.5E-7"),
            Map.entry("float_small/column/TDS_TEXT/H2", "2.5E-7"),
            Map.entry("datetime_whole/value/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/value/JSON/H2", "2024-01-02T10:30:00"),
            Map.entry("datetime_whole/value/STREAMED_JSON/H2", "2024-01-02T10:30:00"),
            Map.entry("datetime_whole/column/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_whole/column/JSON/H2", "2024-01-02T10:30:00"),
            Map.entry("datetime_whole/column/STREAMED_JSON/H2", "2024-01-02T10:30:00"),
            Map.entry("datetime_whole/column/TDS_TEXT/H2", "2024-01-02T10:30:00.000+0000"),
            Map.entry("datetime_millis/value/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_millis/value/JSON/H2", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/value/STREAMED_JSON/H2", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/value/TDS_TEXT/H2", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/column/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_millis/column/JSON/H2", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_millis/column/STREAMED_JSON/H2", "2024-01-02T10:30:00.123"),
            Map.entry("datetime_nanos/value/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_nanos/value/JSON/H2", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/value/STREAMED_JSON/H2", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/value/TDS_TEXT/H2", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/column/CSV/H2", "2024-01-02 10:30:00"),
            Map.entry("datetime_nanos/column/JSON/H2", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/column/STREAMED_JSON/H2", "2024-01-02T10:30:00.123456789"),
            Map.entry("datetime_nanos/column/TDS_TEXT/H2", "2024-01-02T10:30:00.123+0000"));

    @Test
    void everyKindOnEveryOutputWritesPuresText_orIsPinnedByName() throws Exception {
        List<String> table = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, String> pins = new LinkedHashMap<>(PINNED);
        for (DatabaseType type : List.of(DatabaseType.DuckDB, DatabaseType.H2)) {
            var ctx = Compiler.compileModel(MODEL.formatted(type.name()));
            for (Kind kind : KINDS) {
                for (String form : List.of("value", "column")) {
                    String query = form.equals("value") ? kind.value() : kind.column();
                    for (TypedQuery.Output output : OUTPUTS) {
                        String key = kind.name() + "/" + form + "/" + output + "/" + type;
                        String cell;
                        try (Connection c = fresh(type)) {
                            ExecutionPlan plan = Compiler.query(ctx, query).executionPlan("s::RT", output);
                            cell = cellOf(PlanCases.run(plan, c), output);
                        } catch (RuntimeException e) {
                            cell = "ERROR " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
                        }
                        String pinned = pins.remove(key);
                        String verdict;
                        if (cell.equals(kind.pure())) {
                            verdict = pinned == null ? "pure" : "PIN STALE";
                            if (pinned != null) {
                                failures.add(key + " writes Pure's text now: delete its pin");
                            }
                        } else if (pinned != null && pinned.equals(cell)) {
                            verdict = "pinned";
                        } else if (pinned != null) {
                            verdict = "PIN MOVED";
                            failures.add(key + " writes '" + cell + "', pinned as '" + pinned + "'");
                        } else {
                            verdict = "DEVIATES";
                            failures.add(key + " writes '" + cell + "' where Pure writes '" + kind.pure() + "'");
                        }
                        table.add(key + "\t" + kind.pure() + "\t" + cell + "\t" + verdict);
                    }
                }
            }
        }
        for (String stale : pins.keySet()) {
            failures.add(stale + " is pinned but not in the matrix");
        }
        System.out.println("[output matrix] key\tpure\tlite\tverdict");
        table.forEach(System.out::println);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** The one cell of a one-row, one-column answer in {@code output}'s text, as the value's own text. */
    private static String cellOf(String text, TypedQuery.Output output) {
        return switch (output) {
            case CSV, TDS_TEXT -> {
                String[] lines = text.split("\n");
                yield lines.length > 1 ? lines[1].trim() : "<no row: " + text.replace("\n", "\\n") + ">";
            }
            case JSON, STREAMED_JSON -> {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(?:v|value)\":(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]]*)")
                        .matcher(text);
                if (!m.find()) {
                    yield "<no cell: " + text.replace("\n", "\\n") + ">";
                }
                String token = m.group(1);
                yield token.startsWith("\"") ? token.substring(1, token.length() - 1) : token;
            }
        };
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.split("\n")[0];
    }

    private static Connection fresh(DatabaseType type) throws SQLException {
        return switch (type) {
            case DuckDB -> DriverManager.getConnection("jdbc:duckdb:");
            case H2 -> DriverManager.getConnection("jdbc:h2:mem:outputmatrix" + H2_NAMES.incrementAndGet()
                    + com.legend.exec.H2Settings.SETTINGS);
            default -> throw new IllegalArgumentException(type.name());
        };
    }
}
