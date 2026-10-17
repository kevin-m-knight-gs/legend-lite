// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testbackend;

import com.legend.model.ConnectionDefinition.DatabaseType;

/** H2's sessions: a private in-memory instance each, with the engine's session settings ({@code H2Connector.SETTINGS}). */
final class H2Backend extends InProcessBackend {

    static final H2Backend INSTANCE = new H2Backend();

    private H2Backend() {
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.H2;
    }

    @Override
    public String name() {
        return "h2";
    }
}
