package com.legend.sql;

import java.math.BigDecimal;
import java.util.List;

/**
 * Scalar SQL expressions &mdash; sealed, immutable, data-only. Function calls
 * ({@link Call}) carry SEMANTIC names; the dialect owns the SQL spelling
 * (an unknown semantic name is a loud rendering error, never a fallback).
 */
public sealed interface SqlExpr
        permits SqlExpr.Column, SqlExpr.Star, SqlExpr.StarExcept, SqlExpr.StringLit, SqlExpr.IntLit,
                SqlExpr.FloatLit, SqlExpr.DecimalLit, SqlExpr.BoolLit, SqlExpr.NullLit,
                SqlExpr.DateLit, SqlExpr.TimestampLit, SqlExpr.FormatLit, SqlExpr.ArrayLit,
                SqlExpr.OrderedListAgg,
                SqlExpr.StructLit, SqlExpr.StructGet, SqlExpr.Call,
                SqlExpr.Case, SqlExpr.Exists, SqlExpr.ScalarSubquery, SqlExpr.WindowCall,
                SqlExpr.Lambda, SqlExpr.Cast, SqlExpr.FoldCall, SqlExpr.JsonObject,
                SqlExpr.JsonArrayAgg, SqlExpr.PlanParam, SqlExpr.Group, SqlAgg.Reducer {

    /** A column reference, optionally qualified by a source alias. */
    record Column(String table, String name) implements SqlExpr {
    }

    /** {@code *} or {@code alias.*}. */
    /** {@code alias.* EXCLUDE (a, b)} — the star minus named columns (pivot key synthesis). */
    record StarExcept(String table, List<String> except) implements SqlExpr {
        public StarExcept {
            except = List.copyOf(except);
        }
    }

    record Star(String table) implements SqlExpr {
    }

    record StringLit(String value) implements SqlExpr {
    }

    record IntLit(long value) implements SqlExpr {
    }

    record FloatLit(double value) implements SqlExpr {
    }

    record DecimalLit(BigDecimal value) implements SqlExpr {
    }

    record BoolLit(boolean value) implements SqlExpr {
    }

    record NullLit() implements SqlExpr {
    }

    /** ISO {@code yyyy-MM-dd}; renders as a typed DATE literal. */
    record DateLit(String iso) implements SqlExpr {
    }

    /** ISO timestamp; renders as a typed TIMESTAMP literal. */
    /** An EXPLICIT parenthesization group — the engine's {@code group}
     * dynafunction (extensionDefaults.pure:224, format '(%s)'). Parens
     * are STRUCTURAL, never derived from operator arity: the engine
     * emits group when an and/or nests under the OPPOSITE operator and
     * when a predicate merges with its null-guards under or/not
     * (pureToSQLQuery newAndOrDynaFunctionRelaxedBrackets:5376,
     * moveExtraFilterToFilter:4610). */
    record Group(SqlExpr inner) implements SqlExpr {
    }

    /** An execution-plan TEMPLATE parameter ({@code ${name}} — the
     * engine's freemarker placeholder for a function parameter or an
     * Allocation-bound variable). Plan-text vocabulary only: it renders
     * through the engine-style dialect and is a loud error in any
     * executable dialect. */
    record PlanParam(String name, Kind kind, boolean optional,
            String enumMapFn) implements SqlExpr {
        public enum Kind { STRING, DATE, DATETIME, FLOAT, BOOLEAN, ENUM,
            OTHER }

        public PlanParam(String name, Kind kind, boolean optional) {
            this(name, kind, optional, null);
        }

        public PlanParam(String name, Kind kind) {
            this(name, kind, false);
        }

        public PlanParam(String name, boolean stringTyped) {
            this(name, stringTyped ? Kind.STRING : Kind.OTHER, false);
        }
    }

    /** A TYPED date format — a list of {@link DateFmt} parts, never a
     * C-format string a renderer must re-parse (remediation T3.2). Rides
     * as the format argument of STRFTIME/STRPTIME. */
    record FormatLit(List<DateFmt> parts) implements SqlExpr {
        public FormatLit {
            parts = List.copyOf(parts);
        }
    }

    record TimestampLit(String iso) implements SqlExpr {
    }

    /** A list literal, {@code [a, b, c]} in DuckDB. */
    /** {@code list(value ORDER BY key)} — identity-preserving ordered aggregation. */
    record OrderedListAgg(SqlExpr value, SqlExpr orderBy) implements SqlExpr {
    }

    record ArrayLit(List<SqlExpr> elements) implements SqlExpr {
    }

    /**
     * A named-field composite literal ({@code {'f': v, …}} in DuckDB). Field
     * ORDER is the emitting frontend's declared layout — load-bearing, never
     * inferred from the value set.
     */
    record StructLit(List<Field> fields) implements SqlExpr {
        public StructLit {
            fields = List.copyOf(fields);
        }

        public record Field(String name, SqlExpr value) {
        }
    }

    /** Field extraction from a composite value ({@code struct_extract(x, 'f')} in DuckDB). */
    record StructGet(SqlExpr source, String field) implements SqlExpr {
    }

    /** A function application by SEMANTIC vocabulary entry (see {@link SqlFn}). */
    record Call(SqlFn fn, List<SqlExpr> args) implements SqlExpr {
        public static Call of(SqlFn fn, SqlExpr... args) {
            return new Call(fn, List.of(args));
        }
    }

    /** {@code CASE WHEN ... THEN ... [WHEN ...] ELSE ... END}. */
    record Case(List<When> whens, SqlExpr otherwise) implements SqlExpr {
        public record When(SqlExpr condition, SqlExpr then) {
        }
    }

    /** {@code EXISTS (subquery)} &mdash; Boolean-composable association predicate. */
    record Exists(SqlQuery subquery) implements SqlExpr {
    }

    /** A single-value subquery in scalar position. */
    record ScalarSubquery(SqlQuery subquery) implements SqlExpr {
    }

    /**
     * {@code json_object(k1, v1, k2, v2, ...)} &mdash; the graph-serialize
     * envelope's per-row object. {@code kv} alternates string-literal keys
     * with value expressions.
     */
    record JsonObject(List<SqlExpr> kv) implements SqlExpr {
    }

    /**
     * {@code coalesce(json_group_array(x), '[]')} &mdash; the SNAPSHOT
     * aggregation of an envelope: all rows into one JSON-array value; an
     * empty rowset is the EMPTY ARRAY, never SQL NULL.
     */
    record JsonArrayAgg(SqlExpr value, List<Key> orderKeys) implements SqlExpr {
        public JsonArrayAgg {
            orderKeys = orderKeys == null ? List.of() : List.copyOf(orderKeys);
        }

        /** Unordered aggregation (scan order — the pre-determinism shape). */
        public JsonArrayAgg(SqlExpr value) {
            this(value, List.of());
        }

        /** One ordered-agg key: union WITNESS keys render DESC (the
         * TRUE-first contract), pk determinism keys ASC. */
        public record Key(SqlExpr expr, boolean desc) {
        }
    }

    /**
     * {@code fn(...) OVER (PARTITION BY ... ORDER BY ... frame)}. Any
     * {@link SqlAgg} kind is legal here &mdash; this is the ONLY position that
     * admits the window-only kinds.
     */
    record WindowCall(SqlAgg fn, List<SqlExpr> partitionBy, List<SqlSelect.SortKey> orderBy,
                      Frame frame) implements SqlExpr {

        /** {@code ROWS|RANGE BETWEEN <from> AND <to>}. */
        public record Frame(Kind kind, Bound from, Bound to) {
            public enum Kind { ROWS, RANGE }

            public sealed interface Bound {
                record UnboundedPreceding() implements Bound {
                }

                record Preceding(Number n) implements Bound {
                }

                record CurrentRow() implements Bound {
                }

                record Following(Number n) implements Bound {
                }

                record UnboundedFollowing() implements Bound {
                }

                /** {@code INTERVAL n UNIT PRECEDING} — the _RangeInterval frame side. */
                record IntervalPreceding(long n, String unit) implements Bound {
                }

                record IntervalFollowing(long n, String unit) implements Bound {
                }
            }
        }
    }

    /** A lambda for DuckDB list functions: {@code x -> body} / {@code (a, x) -> body}. */
    record Lambda(List<String> params, SqlExpr body) implements SqlExpr {
    }

    /**
     * {@code CAST(value AS <type>[])} — the target rides as a PURE type; the
     * SQL type name is the dialect's business. {@code array} casts to a list
     * of the target ({@code toMany}). A dialect may render a variant-access
     * value through its text-extraction idiom (DuckDB {@code ->>}) — that
     * swap is RENDERING knowledge, not IR content.
     */
    record Cast(SqlExpr value, SqlType target) implements SqlExpr {
    }

    /**
     * A FOLD over a collection value, in PURE conventions: the lambda's
     * parameters are {@code (element, accumulator)} — Pure's order — and the
     * dialect owns the encoding (DuckDB: {@code list_reduce} with swapped
     * params, single-item-list wrap/unwrap when {@code accIsList}; a
     * lambda-less backend: recursive CTE or a loud error).
     */
    record FoldCall(SqlExpr source, Lambda lambda, SqlExpr init, boolean accIsList,
                    boolean homogeneous) implements SqlExpr {
    }
}
