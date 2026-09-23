package com.legend.exec;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * DuckDB's Appender, keeping the typing in the database: the Appender takes
 * only values of the column's own type (a string into an INTEGER column is
 * refused), so every cell is appended as TEXT into the staging table and the
 * staging copy casts it into the target &mdash; the cast DuckDB applies to the
 * text path's string literal. Every statement is the dialect's, handed in;
 * this class spells none. ~20x faster than the multi-row insert at 10K-100K
 * rows, the rows identical.
 */
public final class DuckDbAppenderLoad implements BulkLoad {

    /** Where DuckDB keeps a temporary table: catalog {@code temp}, schema {@code main}. */
    private static final String TEMP_CATALOG = "temp";
    private static final String TEMP_SCHEMA = "main";

    @Override
    public boolean accepts(Connection connection) throws SQLException {
        return connection.isWrapperFor(DuckDBConnection.class);
    }

    @Override
    public void load(Connection connection, RowLoad load, Staging staging) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(staging.create());
            try {
                try (DuckDBAppender appender = connection.unwrap(DuckDBConnection.class)
                        .createAppender(TEMP_CATALOG, TEMP_SCHEMA, staging.table())) {
                    for (List<String> row : load.rows()) {
                        appender.beginRow();
                        for (String cell : row) {
                            if (cell == null) {
                                appender.appendNull();
                            } else {
                                appender.append(cell);
                            }
                        }
                        appender.endRow();
                    }
                }
                st.execute(staging.copy());
            } finally {
                st.execute(staging.drop());
            }
        }
    }
}
