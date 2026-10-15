// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

import com.legend.sql.SqlExpr;

import java.util.List;

/**
 * DuckDB's spellings of the functions only DuckDB runs, written once for the two dialects that name them: {@link DuckDb}
 * and the engine-H2 text ({@link EngineStyleH2}), which has carried them since they were the base renderer's. The engine
 * text keeps them because the corpus reads that text on DuckDB too: refusing them broke 14 passing corpus tests (the
 * dialect program's decision D1, measured 2026-10-13: 13 through {@code error()}, one through {@code to_base64}).
 * legend-engine's own H2 text spells none of them; that is the engine-text work's question, not D1's.
 */
final class DuckDbSpellings {

    private DuckDbSpellings() {
    }

    /** {@code ~x} as {@code xor(x, -1)}: no negation overflow at MIN_LONG. */
    static SqlWriter bitNot(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("xor(").expr(a.get(0), 0).append(", -1)");
    }

    static SqlWriter mapEmpty(SqlWriter writer) {
        return writer.append("MAP {}");
    }

    static SqlWriter jsonMergePatch(SqlWriter writer, List<SqlExpr> a) {
        return writer.function("json_merge_patch", a);
    }

    static SqlWriter encodeBase64(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("to_base64(CAST(").expr(a.get(0), 0).append(" AS BLOB))");
    }

    /** decode(blob): a CAST of the blob to VARCHAR ESCAPES quotes and non-printables (\x22), never the text itself
     *  (batch 72b). */
    static SqlWriter decodeBase64(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("decode(from_base64(").expr(a.get(0), 0).append("))");
    }

    /** make_timestamp wants DOUBLE seconds; (part, value) otherwise. */
    static SqlWriter makeTimestamp(SqlWriter writer, List<SqlExpr> a) {
        return a.size() == 6
                ? writer.append("make_timestamp(").list(a.subList(0, 5)).append(", CAST(").expr(a.get(5), 0)
                        .append(" AS DOUBLE))")
                : writer.function("make_timestamp", a);
    }

    /** Week buckets align to the Monday ON/BEFORE the epoch (1969-12-29 — real pure's origin, PCT-pinned); every
     *  other unit aligns to the 1970 epoch. */
    static SqlWriter timeBucket(SqlWriter writer, List<SqlExpr> a) {
        String unit = ((SqlExpr.StringLit) a.get(0)).value();
        return writer.append("time_bucket(").append(unit).append("(").expr(a.get(1), 0).append("), ")
                .expr(a.get(2), 0)
                .append("to_weeks".equals(unit) ? ", TIMESTAMP '1969-12-29 00:00:00'"
                        : ", TIMESTAMP '1970-01-01 00:00:00'")
                .append(")");
    }

    static SqlWriter fromEpochMs(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("epoch_ms(CAST(").expr(a.get(0), 0).append(" AS BIGINT))");
    }

    static SqlWriter intDivide(SqlWriter writer, List<SqlExpr> a) {
        return writer.append("(").expr(a.get(0), 6).append(" // ").expr(a.get(1), 6).append(")");
    }
}
