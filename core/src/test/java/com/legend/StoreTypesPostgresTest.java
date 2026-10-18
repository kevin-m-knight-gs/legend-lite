// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StoreTypesTest}'s case that is Postgres's own (moved here from it by the dialect program's Phase 6,
 * 2026-10-16): a nested value is read as JSONB.
 */
class StoreTypesPostgresTest {

    @Test
    void aNestedValueIsReadAsJsonb() {
        String pg = StoreTypesTest.sql(StoreTypesTest.on(com.legend.model.ConnectionDefinition.DatabaseType.Postgres),
                "#>{s::DB.HOSTS}#->select(~[META])");
        // Postgres folds the store's bare META to lower case (Postgres.physicalName)
        assertTrue(java.util.regex.Pattern.compile("CAST\\(\"\\w+\"\\.meta AS JSONB\\)").matcher(pg).find(), pg);
    }
}
