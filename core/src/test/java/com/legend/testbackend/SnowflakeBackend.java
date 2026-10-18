// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;

import java.util.Properties;

/** Snowflake's sessions: a real account's warehouse ({@link CloudBackend}): its {@code account}, {@code warehouse},
 *  {@code region} and {@code database} declared; the system connection's {@code schema}, where its temporary tables
 *  live. */
final class SnowflakeBackend extends CloudBackend {

    static final SnowflakeBackend INSTANCE = new SnowflakeBackend();

    private SnowflakeBackend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.Snowflake;
    }

    @Override
    public String name() {
        return "snowflake";
    }

    @Override
    String specificationAndAuth(Properties p) {
        return "specification: Snowflake { name: " + quoted(p, "database") + "; account: "
                + quoted(p, "account") + "; warehouse: " + quoted(p, "warehouse") + "; region: "
                + quoted(p, "region") + "; };\n  auth: SnowflakePublic { publicUserName: 'snowflake.user';"
                + " privateKeyVaultReference: 'snowflake.privateKey'; passPhraseVaultReference:"
                + " 'snowflake.passPhrase'; }";
    }
}
