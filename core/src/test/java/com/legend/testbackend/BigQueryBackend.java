// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;

import java.util.Properties;

/** BigQuery's sessions: a real account's project ({@link CloudBackend}): its {@code projectId} and {@code defaultDataset} declared; the system connection's {@code EnableSession}, without which it makes no temporary table. */
final class BigQueryBackend extends CloudBackend {

    static final BigQueryBackend INSTANCE = new BigQueryBackend();

    private BigQueryBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.BigQuery;
    }

    @Override
    public String name() {
        return "bigquery";
    }

    @Override
    String specificationAndAuth(Properties p) {
        return "specification: BigQuery { projectId: " + quoted(p, "projectId") + "; defaultDataset: "
                + quoted(p, "defaultDataset") + "; };\n  auth: GCPApplicationDefaultCredentials";
    }
}
