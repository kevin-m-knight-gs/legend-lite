// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.cypher;

import com.legend.protocol.sql.SqlAst.Pos;
import com.legend.protocol.sql.SqlTranslationException;

/** The Cypher reader's and translator's refusals, in the three kinds the island seam reports: a syntax error, a
 *  construct not read yet (refused by name), and a query Neo4j rejects too. */
final class CypherErrors {

    private CypherErrors() {
    }

    static SqlTranslationException syntax(String message, Pos pos) {
        return new SqlTranslationException(SqlTranslationException.Kind.SYNTAX, message, pos.line(), pos.column());
    }

    static SqlTranslationException unsupported(String message, Pos pos) {
        return SqlTranslationException.unsupported(message, pos);
    }

    static SqlTranslationException invalid(String message, Pos pos) {
        return SqlTranslationException.invalid(message, pos);
    }
}
