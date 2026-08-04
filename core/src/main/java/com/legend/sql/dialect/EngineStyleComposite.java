// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

/**
 * The engine's COMPOSITE dialect text: the DEFAULT spellings — native
 * trim/pad/cbrt like DB2's 'common' goldens, but WITHOUT DB2's own
 * quirks ({@code CHARACTER_LENGTH(x,CODEUNITS32)} stays plain
 * {@code char_length}). Divergent goldens fail honestly.
 */
public final class EngineStyleComposite extends EngineStyleDB2 {

    @Override
    protected String call(SqlExpr.Call c, int parentPrec) {
        if (c.fn() == SqlFn.LENGTH) {
            return "char_length(" + expr(c.args().get(0), 0) + ")";
        }
        if (c.fn() == SqlFn.SUBSTRING) {
            // Composite keeps the FULL substring keyword (DB2 shortens)
            return "substring(" + c.args().stream()
                    .map(x -> expr(x, 0))
                    .collect(java.util.stream.Collectors.joining(", "))
                    + ")";
        }
        return super.call(c, parentPrec);
    }
}
