// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition.DatabaseType;

import java.util.List;

/** The connectors on the class path ({@link DatabaseConnector}, by {@link java.util.ServiceLoader}, as the bulk
 *  loaders are found), each for one database type; two for one type is a packaging mistake, said at once. */
final class Connectors {

    private Connectors() {
    }

    private static final List<DatabaseConnector> CONNECTORS = java.util.ServiceLoader.load(DatabaseConnector.class)
            .stream().map(java.util.ServiceLoader.Provider::get).toList();

    static @com.legend.base.Nullable DatabaseConnector of(DatabaseType type) {
        DatabaseConnector found = null;
        for (DatabaseConnector c : CONNECTORS) {
            if (c.type() == type) {
                if (found != null) {
                    throw new IllegalStateException("two connectors for " + type + ": " + found.getClass().getName()
                            + " and " + c.getClass().getName());
                }
                found = c;
            }
        }
        return found;
    }
}
