// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.exec;

import com.legend.model.ConnectionDefinition;

/**
 * A database's connector (docs/MONGODB_PLAN_2026_10_16.md §5.2, D12; the dialect program's Phase 5 shape): how a
 * session on a declared connection of its database type is opened, beside the JDBC drivers ({@code //core:drivers})
 * and found as they are, by {@link java.util.ServiceLoader} -- so core compiles against no client library, and a
 * running core is given the connectors with its drivers. The plan runner's sessions ({@link PlanSessions}) ask the
 * connectors first: a type with one is opened by it, and never by a JDBC URL.
 */
public interface DatabaseConnector {

    /** The one database type this connector opens. */
    ConnectionDefinition.DatabaseType type();

    /**
     * A session on {@code connection}, owned by the caller (closing it releases it), its authentication resolved here
     * -- a vault reference read from where it points -- and never carried further. A specification or an
     * authentication the connector does not open is refused by name.
     */
    Sessions.Session open(ConnectionDefinition connection);
}
