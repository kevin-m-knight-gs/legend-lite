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
                SqlExpr.Case, SqlExpr.Exists, SqlExpr.ScalarSubquery,
                SqlExpr.DeferredTdsString, SqlExpr.WindowCall,
                SqlExpr.Lambda, SqlExpr.Cast, SqlExpr.FoldCall, SqlExpr.JsonObject,
                SqlExpr.JsonArrayAgg, SqlExpr.PlanParam, SqlExpr.Group,
                SqlExpr.RowOrder, SqlExpr.ReduceCollection, SqlExpr.Membership,
                SqlExpr.TempTableInSplice,
                SqlAgg.Reducer {

    /**
     * The DIRECT {@link SqlExpr} children, in rebuild order — the ONE
     * structural-recursion contract every expression walker shares. The
     * switch is EXHAUSTIVE with no default arm: a new variant fails
     * compilation HERE (and in {@link #withChildren}) until it declares
     * its children, and every walker inherits correct traversal.
     * Query-carrying nodes ({@link Exists}, {@link ScalarSubquery}) own
     * their inner traversal — expression walkers do not descend into
     * subqueries through this contract.
     */
    default List<SqlExpr> children() {
        return switch (this) {
            case Column ignored -> List.of();
            case RowOrder ignored -> List.of();
            case Membership m -> List.of(m.needle(), m.collection());
            case ReduceCollection rc -> {
                java.util.List<SqlExpr> out = new java.util.ArrayList<>();
                out.add(rc.collection());
                out.addAll(rc.extras());
                yield out;
            }
            case Star ignored -> List.of();
            case StarExcept ignored -> List.of();
            case StringLit ignored -> List.of();
            case IntLit ignored -> List.of();
            case FloatLit ignored -> List.of();
            case DecimalLit ignored -> List.of();
            case BoolLit ignored -> List.of();
            case NullLit ignored -> List.of();
            case DateLit ignored -> List.of();
            case TimestampLit ignored -> List.of();
            case FormatLit ignored -> List.of();
            case PlanParam ignored -> List.of();
            case TempTableInSplice ignored -> List.of();
            case Group g -> List.of(g.inner());
            case ArrayLit a -> a.elements();
            case OrderedListAgg o -> List.of(o.value(), o.orderBy());
            case StructLit s -> s.fields().stream()
                    .map(StructLit.Field::value).toList();
            case StructGet g -> List.of(g.source());
            case Call c -> c.args();
            case Case cs -> {
                java.util.List<SqlExpr> out = new java.util.ArrayList<>();
                for (Case.When w : cs.whens()) {
                    out.add(w.condition());
                    out.add(w.then());
                }
                if (cs.otherwise() != null) {
                    out.add(cs.otherwise());
                }
                yield out;
            }
            case Exists ignored -> List.of();
            case ScalarSubquery ignored -> List.of();
            case DeferredTdsString ignored -> List.of();
            case WindowCall w -> {
                java.util.List<SqlExpr> out = new java.util.ArrayList<>();
                if (w.fn() instanceof SqlExpr fe) {
                    out.add(fe);
                }
                out.addAll(w.partitionBy());
                for (SqlSelect.SortKey k : w.orderBy()) {
                    out.add(k.expr());
                }
                yield out;
            }
            case Lambda l -> List.of(l.body());
            case Cast c -> List.of(c.value());
            case FoldCall f -> List.of(f.source(), f.lambda(), f.init());
            case JsonObject j -> j.kv();
            case JsonArrayAgg j -> {
                java.util.List<SqlExpr> out = new java.util.ArrayList<>();
                out.add(j.value());
                for (JsonArrayAgg.Key k : j.orderKeys()) {
                    out.add(k.expr());
                }
                yield out;
            }
            case SqlAgg.Reducer r -> {
                java.util.List<SqlExpr> out =
                        new java.util.ArrayList<>(r.args());
                for (SqlSelect.SortKey k : r.orderBy()) {
                    out.add(k.expr());
                }
                yield out;
            }
        };
    }

    /**
     * This node with its direct children replaced by {@code cs}
     * ({@code cs.size() == children().size()}, same order). The other
     * half of the recursion contract — see {@link #children()}.
     */
    default SqlExpr withChildren(List<SqlExpr> cs) {
        return switch (this) {
            case Column ignored -> this;
            case RowOrder ignored -> this;
            case ReduceCollection rc -> new ReduceCollection(rc.reducer(),
                    cs.get(0), cs.subList(1, cs.size()));
            case Membership m -> new Membership(cs.get(0), cs.get(1));
            case Star ignored -> this;
            case StarExcept ignored -> this;
            case StringLit ignored -> this;
            case IntLit ignored -> this;
            case FloatLit ignored -> this;
            case DecimalLit ignored -> this;
            case BoolLit ignored -> this;
            case NullLit ignored -> this;
            case DateLit ignored -> this;
            case TimestampLit ignored -> this;
            case FormatLit ignored -> this;
            case PlanParam ignored -> this;
            case TempTableInSplice ignored -> this;
            case Exists ignored -> this;
            case ScalarSubquery ignored -> this;
            case DeferredTdsString ignored -> this;
            case Group ignored -> new Group(cs.get(0));
            case ArrayLit ignored -> new ArrayLit(cs);
            case OrderedListAgg ignored ->
                    new OrderedListAgg(cs.get(0), cs.get(1));
            case StructLit s -> {
                java.util.List<StructLit.Field> fs = new java.util.ArrayList<>();
                for (int i = 0; i < s.fields().size(); i++) {
                    fs.add(new StructLit.Field(s.fields().get(i).name(),
                            cs.get(i)));
                }
                yield new StructLit(fs);
            }
            case StructGet g -> new StructGet(cs.get(0), g.field());
            case Call c -> new Call(c.fn(), cs);
            case Case old -> {
                java.util.List<Case.When> ws = new java.util.ArrayList<>();
                int i = 0;
                for (int w = 0; w < old.whens().size(); w++) {
                    ws.add(new Case.When(cs.get(i), cs.get(i + 1)));
                    i += 2;
                }
                yield new Case(ws, old.otherwise() == null ? null : cs.get(i));
            }
            case WindowCall w -> {
                int base = w.fn() instanceof SqlExpr ? 1 : 0;
                SqlAgg fn = base == 1 ? (SqlAgg) cs.get(0) : w.fn();
                int np = w.partitionBy().size();
                java.util.List<SqlSelect.SortKey> ks = new java.util.ArrayList<>();
                for (int i = 0; i < w.orderBy().size(); i++) {
                    SqlSelect.SortKey k = w.orderBy().get(i);
                    ks.add(new SqlSelect.SortKey(cs.get(base + np + i),
                            k.ascending(), k.nullOrder(), k.outputName()));
                }
                yield new WindowCall(fn, cs.subList(base, base + np), ks,
                        w.frame());
            }
            case Lambda l -> new Lambda(l.params(), cs.get(0));
            case Cast c -> new Cast(cs.get(0), c.target());
            case FoldCall f -> new FoldCall(cs.get(0), (Lambda) cs.get(1),
                    cs.get(2), f.accIsList(), f.homogeneous());
            case JsonObject ignored -> new JsonObject(cs);
            case JsonArrayAgg j -> {
                java.util.List<JsonArrayAgg.Key> ks = new java.util.ArrayList<>();
                for (int i = 0; i < j.orderKeys().size(); i++) {
                    ks.add(new JsonArrayAgg.Key(cs.get(1 + i),
                            j.orderKeys().get(i).desc()));
                }
                yield new JsonArrayAgg(cs.get(0), ks);
            }
            case SqlAgg.Reducer r -> {
                int na = r.args().size();
                java.util.List<SqlSelect.SortKey> ks = new java.util.ArrayList<>();
                for (int i = 0; i < r.orderBy().size(); i++) {
                    SqlSelect.SortKey k = r.orderBy().get(i);
                    ks.add(new SqlSelect.SortKey(cs.get(na + i), k.ascending(),
                            k.nullOrder(), k.outputName()));
                }
                yield new SqlAgg.Reducer(r.fn(), cs.subList(0, na),
                        r.distinct(), ks);
            }
        };
    }

    /**
     * Identity-preserving one-level rewrite: {@code f} over each direct
     * child, reassembled through {@link #withChildren} only when a child
     * actually changed. Walkers recurse by calling this from their own
     * default arm — a variant they do not special-case still traverses
     * correctly by construction.
     */
    default SqlExpr mapChildren(java.util.function.UnaryOperator<SqlExpr> f) {
        List<SqlExpr> cs = children();
        if (cs.isEmpty()) {
            return this;
        }
        java.util.List<SqlExpr> rw = new java.util.ArrayList<>(cs.size());
        boolean same = true;
        for (SqlExpr c : cs) {
            SqlExpr r = f.apply(c);
            same = same && r == c;
            rw.add(r);
        }
        return same ? this : withChildren(rw);
    }

    /** A column reference, optionally qualified by a source alias. */
    /** {@code table} null = unqualified reference (lambda params,
     * pivot args, post-unqualify rewrites). */
    /** The backend's PHYSICAL ROW-ORDER pseudo-column — the determinism
     * key for insertion-ordered aggregation (joinStrings parity). Spelled
     * per dialect (DuckDB {@code rowid}, H2 {@code _ROWID_}); a plain
     * Column would bake one backend's spelling into the IR. */
    record RowOrder(@com.legend.Nullable String table) implements SqlExpr {
    }

    /** SEMANTIC collection reduction (CARRIER_REDESIGN.md R1): reduce a
     * collection VALUE with the named ANSI aggregate ({@code string_agg},
     * {@code quantile_cont}, {@code var_samp}, ...); {@code extras} are
     * the aggregate's trailing arguments (separator, quantile). The
     * dialect strategy layer owns the emission — DuckDB's native
     * {@code list_aggregate}, or the portable FUSION into the collecting
     * subselect (the engine's shape). No backend spelling lives here. */
    record ReduceCollection(SqlAgg.Fn reducer, SqlExpr collection,
            java.util.List<SqlExpr> extras) implements SqlExpr {
        public ReduceCollection {
            extras = java.util.List.copyOf(extras);
        }
    }

    /** SEMANTIC collection membership (CARRIER_REDESIGN.md R2):
     * {@code needle} in the collection VALUE. NULL TRUTH TABLE (probed
     * DuckDB 1.5 list_contains, the reference semantics): NULL needle
     * -> NULL; needle absent (even with NULL elements) -> FALSE; needle
     * present -> TRUE; empty collection -> FALSE. SQL {@code IN}
     * differs ONLY when a NULL element exists and the needle is absent
     * (NULL, not FALSE) — indistinguishable in filter position; the
     * portable rule CASE-wraps in the NULL-element-literal case. The
     * collection may be a PlanParam at bind time (§2). */
    record Membership(SqlExpr needle, SqlExpr collection)
            implements SqlExpr {
    }

    record Column(@com.legend.Nullable String table, String name) implements SqlExpr {
    }

    /** {@code *} or {@code alias.*}. */
    /** {@code alias.* EXCLUDE (a, b)} — the star minus named columns (pivot key synthesis). */
    record StarExcept(@com.legend.Nullable String table, List<String> except) implements SqlExpr {
        public StarExcept {
            except = List.copyOf(except);
        }
    }

    /** {@code table} null = unqualified {@code *}. */
    record Star(@com.legend.Nullable String table) implements SqlExpr {
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
            @com.legend.Nullable String enumMapFn) implements SqlExpr {
        /** {@code RAW} splices {@code ${name}} bare — the temp-table IN
         * protocol's {@code inFilterClause_X} wrapper variable
         * (processInOperation.pure); plan-text vocabulary only. */
        public enum Kind { STRING, DATE, DATETIME, FLOAT, BOOLEAN, ENUM,
            OTHER, RAW }

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
    /** {@code otherwise} null = no ELSE branch (SQL semantics: NULL). */
    record Case(List<When> whens, @com.legend.Nullable SqlExpr otherwise) implements SqlExpr {
        public record When(SqlExpr condition, SqlExpr then) {
        }
    }

    /** {@code EXISTS (subquery)} &mdash; Boolean-composable association predicate. */
    record Exists(SqlQuery subquery) implements SqlExpr {
    }

    /** A single-value subquery in scalar position. */
    record ScalarSubquery(SqlQuery subquery) implements SqlExpr {
    }

    /** A relation-toString whose COLUMN LIST is dynamic (a pivot with
     * runtime-discovered keys): the '#TDS' text cannot compose at
     * lowering — the EXECUTION BOUNDARY resolves it (DynamicPivot's
     * two-phase discipline; the lowering layer records the typed
     * schema by {@code id}). Types-free by design — the sql package
     * never carries compiler types. A node reaching a renderer is a
     * routing bug and walls loudly there. */
    record DeferredTdsString(SqlSelect inner, String alias, int id)
            implements SqlExpr {
    }

    /**
     * The temp-table IN splice (the engine's
     * {@code generateTempTableSelectSQLQuery} — processInOperation's
     * over-threshold arm): renders as the fixed temp-select template
     * {@code select "<t>_0".ColumnForStoringInCollection as
     * ColumnForStoringInCollection from <t> as "<t>_0"} inside
     * {@code in (...)}. Plan-text vocabulary only — a loud error in any
     * executable dialect.
     */
    record TempTableInSplice(String tempTableName) implements SqlExpr {
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
    /** {@code frame} null = no explicit frame clause (dialect default). */
    record WindowCall(SqlAgg fn, List<SqlExpr> partitionBy, List<SqlSelect.SortKey> orderBy,
                      @com.legend.Nullable Frame frame) implements SqlExpr {

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
