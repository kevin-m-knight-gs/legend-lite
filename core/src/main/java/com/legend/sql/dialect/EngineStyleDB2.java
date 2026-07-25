// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlType;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The engine's DB2 SQL text (toSQLString parity) — shares the
 * engine-style select/alias plan with {@link EngineStyleH2} and respells
 * only the dialect-owned function forms the DB2 goldens pin:
 * infix {@code concat}, bare {@code trim}, {@code varchar(16000)} string
 * casts, {@code reverse}. Every unpinned form inherits the H2 spelling —
 * an unmatched golden stays an HONEST text FAIL, never a guessed
 * respell.
 */
public class EngineStyleDB2 extends EngineStyleH2 {

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        List<SqlExpr> a = c.args();
        return switch (c.fn()) {
            // DB2 string concatenation is the infix operator, the whole
            // chain parenthesized once: (a concat b concat c)
            case CONCAT -> "(" + flattenConcat(a).stream()
                    .map(x -> expr(x, 0))
                    .collect(Collectors.joining(" concat ")) + ")";
            case TRIM -> a.size() == 1
                    ? "trim(" + expr(a.get(0), 0) + ")"
                    : super.call(c, parentPrec);
            case REVERSE_STRING -> "reverse(" + expr(a.get(0), 0) + ")";
            default -> super.call(c, parentPrec);
        };
    }

    @Override
    protected String variantAwareCast(SqlExpr.Cast c) {
        // DB2 string casts carry the engine's explicit width
        if (c.target() instanceof SqlType.Scalar s
                && s == SqlType.Scalar.VARCHAR) {
            return "cast(" + expr(c.value(), 0) + " as varchar(16000))";
        }
        return super.variantAwareCast(c);
    }

}
