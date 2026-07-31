package com.legend.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * FROM-clause sources. A {@link Join} tree renders FLAT
 * ({@code a JOIN b ON ... LEFT OUTER JOIN c ON ...}) &mdash; joins are inlined,
 * never wrapped, per the lean-SQL tenet; {@link Subselect} is the ONLY
 * nesting construct and exists solely where the fold policy demands isolation.
 */
public sealed interface SqlSource {

    /**
     * The source's binding alias — satisfied by each record's {@code alias}
     * component. A nested {@link Join} has no single alias (its sides
     * resolve individually); asking is a caller bug.
     */
    String alias();

    List<OutputCol> outputs();

    /**
     * DuckDB {@code PIVOT <source> ON <col> [IN (v…)] USING <agg> AS <alias>}
     * — a structural source; output columns are DYNAMIC (one per pivot
     * value), so {@code outputs} carries only what Phase G could type
     * statically. A non-empty {@code in} pins the pivoted columns to exactly
     * those values (the static {@code pivot(~col, [v…], ~agg)} form).
     */
    record Pivot(SqlSource source, List<SqlExpr> on, List<SqlExpr> in, List<Using> usings,
                 String alias, List<OutputCol> outputs) implements SqlSource {
        public record Using(SqlAgg.Reducer agg, String alias) {
        }
    }

    /**
     * An external semi-structured source ({@code sourceUrl('data:...')}) —
     * ONE {@code data} column of JSON rows; the DIALECT renders the URL
     * into a complete subquery (scheme-dispatched: {@code data:} inlines,
     * {@code file:} reads).
     */
    record SourceUrl(String url, String alias, List<OutputCol> outputs) implements SqlSource {
    }

    record Table(String name, String alias, List<OutputCol> outputs) implements SqlSource {
    }

    /** The FROM-less scalar select's source ({@code SELECT <expr>} — the
     * executeInDb value channel). A REAL variant rather than a null
     * {@code from}: an absent source is a different KIND of source, and
     * dialects that need a dummy table (DB2 {@code SYSIBM.SYSDUMMY1},
     * Oracle {@code DUAL}) get a render hook instead of an omission
     * convention. Current dialects all render it as clause omission. */
    record Dual() implements SqlSource {
        @Override
        public String alias() {
            throw new IllegalStateException(
                    "a Dual (FROM-less) source has no alias — caller bug");
        }

        @Override
        public List<OutputCol> outputs() {
            return List.of();
        }
    }

    /** {@code frameName}: the derived table's MODEL identity (a view's
     * own name) — null for anonymous isolation subselects. Dialects that
     * re-alias by table group name view frames by it. */
    record Subselect(SqlQuery inner, String alias,
            @com.legend.Nullable String frameName)
            implements SqlSource {

        /** SYNTHETIC frame marker (not a model identity): the engine's
         * join-distinct exists key subselect (ExistsJoinForm) — dialects
         * group-number its interior and spell its DISTINCT keys bare. */
        public static final String EXISTS_KEYS_FRAME = "existsKeys";

        // NO short overload: a defaulted frameName silently anonymized a
        // view frame at rebuild sites (remediation T2.2); every
        // construction names every field.

        @Override
        public List<OutputCol> outputs() {
            return inner.outputs();
        }
    }

    /** {@code (VALUES (...), (...)) AS alias(col, ...)} &mdash; TDS / instance literals. */
    record Values(List<List<SqlExpr>> rows, List<String> columns, String alias,
                  List<OutputCol> outputs) implements SqlSource {
    }

    /** {@code on} is KIND-COUPLED, enforced at construction: the CROSS
     * family takes no ON clause; every other kind REQUIRES one — a null
     * {@code on} on an INNER/LEFT join would render {@code JOIN t}
     * (invalid SQL, or an accidental natural join). LEFT_LATERAL spells
     * its always-true condition explicitly ({@code ON true}). */
    record Join(SqlSource left, SqlSource right, Kind kind,
            @com.legend.Nullable SqlExpr on) implements SqlSource {

        public Join {
            boolean onless = kind == Kind.CROSS || kind == Kind.CROSS_LATERAL;
            if (onless && on != null) {
                throw new IllegalArgumentException(
                        kind + " takes no ON clause");
            }
            if (!onless && on == null) {
                throw new IllegalArgumentException(
                        kind + " requires an ON condition");
            }
        }
        @Override
        public String alias() {
            throw new IllegalStateException(
                    "a nested join has no single alias — resolve per side");
        }


        public enum Kind {
            INNER("JOIN"),
            LEFT("LEFT OUTER JOIN"),
            RIGHT("RIGHT OUTER JOIN"),
            FULL("FULL OUTER JOIN"),
            CROSS("CROSS JOIN"),
            // Per-row correlated right side (real relation lateral.pure);
            // the right subselect references the left alias.
            CROSS_LATERAL("CROSS JOIN LATERAL"),
            ASOF_LEFT("ASOF LEFT JOIN"),
            /** Correlated derived table preserving left rows ({@code ... ON TRUE}) — array explosion. */
            LEFT_LATERAL("LEFT JOIN LATERAL");

            public final String sql;

            Kind(String sql) {
                this.sql = sql;
            }
        }

        @Override
        public List<OutputCol> outputs() {
            List<OutputCol> all = new ArrayList<>(left.outputs());
            all.addAll(right.outputs());
            return List.copyOf(all);
        }
    }
}
