// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;

import java.util.Properties;

/** Databricks's sessions: a real account's SQL warehouse ({@link CloudBackend}): its {@code hostname} and {@code httpPath} declared. */
final class DatabricksBackend extends CloudBackend {

    static final DatabricksBackend INSTANCE = new DatabricksBackend();

    private DatabricksBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Databricks;
    }

    @Override
    public String name() {
        return "databricks";
    }

    @Override
    String specificationAndAuth(Properties p) {
        return "specification: Databricks { hostname: " + quoted(p, "hostname") + "; port: '443'; protocol:"
                + " 'https'; httpPath: " + quoted(p, "httpPath") + "; };\n  auth: ApiToken { apiToken:"
                + " 'databricks.token'; }";
    }
}
