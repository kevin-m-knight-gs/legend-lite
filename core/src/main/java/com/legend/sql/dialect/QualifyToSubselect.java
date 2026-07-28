package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;
import com.legend.sql.SqlQuery;
import com.legend.sql.SqlRewriter;
import com.legend.sql.SqlSelect;
import com.legend.sql.SqlSource;

import java.util.List;

/**
 * Structural capability pass (remediation T3.2 step 3): a backend without
 * a QUALIFY clause filters window results by WRAPPING the select &mdash;
 * {@code SELECT * FROM (inner) AS qualify_src WHERE <qualify>}. Formerly
 * the renderer's {@code select} method rewrote this on the fly; now it is
 * MIR&rarr;MIR, and the writer never sees a QUALIFY it cannot spell.
 */
final class QualifyToSubselect extends SqlRewriter {

    @Override
    protected SqlQuery select(SqlSelect s) {
        if (s.qualify() == null) {
            return s;
        }
        SqlSelect inner = s.withQualify(null);
        return new SqlSelect(
                List.of(new SqlSelect.Projection(new SqlExpr.Star(null), null)),
                false,
                new SqlSource.Subselect(inner, "qualify_src", null),
                s.qualify(), List.of(), null, null,
                List.of(), null, null, s.outputs());
    }
}
