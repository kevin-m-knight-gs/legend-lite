// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.setup;

import com.legend.testbackend.TestBackend;
import com.legend.testcases.ReservedNames;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A table or schema named by a reserved word seeds and answers a query (PARK-16, docs/PARKED_WORK_LEDGER.md): the
 * statements that create, drop, fill and empty a table spell its names as a query references them
 * ({@code SqlDialect.physicalName}), so the seed and the query name one table. Before, a default-schema table
 * {@code order} was seeded with {@code Drop table if exists order;}, which DuckDB and H2 refuse. On each in-process
 * database (the dialect program's Phase 6, 2026-10-16: until then DuckDB and H2, each by name); Postgres's case is
 * {@code PostgresArmTest}'s (it needs the embedded Postgres).
 */
class ReservedNamesSeedTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.legend.testbackend.TestBackends#inProcess")
    void seedsAndAnswers(TestBackend backend) throws SQLException {
        try (Connection c = backend.open()) {
            ReservedNames.seedsAndAnswers(ReservedNames.model(backend.connectionBody()), backend.dialect(), c);
        }
    }
}
