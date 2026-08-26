package com.legend.sql;

/**
 * The SQL layer's OWN type vocabulary (LEGEND_SQL_VISION.md prerequisite #1):
 * logical SQL types, independent of any frontend's type system. Frontends map
 * their types to these at the lowering boundary (Pure Integer → BIGINT is the
 * FRONTEND's 64-bit decision); dialects map these to spellings.
 */
public sealed interface SqlType {

    enum Scalar implements SqlType {
        BOOLEAN, INTEGER, BIGINT, HUGEINT, DOUBLE, VARCHAR, DATE, TIMESTAMP,
        TIMESTAMPTZ, JSON,
        /** The KIND-FAITHFUL CARRIER (F10 proper, docs/F10_CARRIER_
         * DESIGN.md): the cell holds a value's PURE-LITERAL SPELLING as
         * text — the six mutually disjoint source forms (bare int,
         * pointed float, D-suffix decimal, quoted string, bare bool,
         * %-temporal), so the value carries its own kind. Physical form
         * is VARCHAR on every backend; the LABEL records the contract
         * (the spelling grammar), which plain VARCHAR cannot promise.
         * Encoder: {@code lowering/LiteralSpelling}. Decoder:
         * {@code values/LiteralText}. Canon: the cell IS canonical. */
        LITERAL,
        /** The PRECISION-FAITHFUL TEMPORAL-TEXT CARRIER (§4bZ-V B3,
         * the LITERAL pattern): a temporal value whose pure precision
         * a SQL temporal cannot hold — partials (%2015, %2015-04),
         * padded HOUR/MINUTE forms, written subsecond digit counts —
         * carried as its engine print-form text. Physical form is
         * VARCHAR on every backend; the LABEL records the contract (a
         * temporal value in text carriage), which plain VARCHAR cannot
         * promise. Constructed ONLY by the temporal emitters (a
         * marker {@code Cast} that never renders — a label device);
         * decode is pure-type-driven date parsing, unchanged. */
        TEMPORAL_TEXT
    }

    record Decimal(int precision, int scale) implements SqlType {
    }

    record Array(SqlType element) implements SqlType {
    }

    /** {@code MAP(K, V)} — the Map<U,V> collection carrier. */
    record Map(SqlType key, SqlType value) implements SqlType {
    }

    /**
     * A named-field composite (DuckDB/BigQuery {@code STRUCT}, Postgres
     * {@code ROW}). Field order is load-bearing — it is the layout the
     * emitting frontend declared, never inferred from data.
     */
    record Struct(java.util.List<Field> fields) implements SqlType {
        public Struct {
            fields = java.util.List.copyOf(fields);
        }

        public record Field(String name, SqlType type) {
        }
    }
}
