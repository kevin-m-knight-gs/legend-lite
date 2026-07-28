package com.legend.sql.dialect;

import com.legend.sql.SqlFn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remediation T3.2 &mdash; the classification ledger that replaces the
 * switch's compile-time exhaustiveness after the pure spellings moved to
 * DATA: every {@code SqlFn} is either a {@link Spellings} row or a CODED
 * rendering rule. A new SqlFn fails here until classified &mdash; never a
 * silent fall-through (the writer's default is a loud wall).
 */
class SpellingsTest {

    /** Fns rendered by CODE (infix, shape logic, composites, idiom points). */
    private static final Set<SqlFn> CODED = Set.of(
            SqlFn.ADD_INTERVAL,
            SqlFn.AND,
            SqlFn.BIT_AND,
            SqlFn.BIT_NOT,
            SqlFn.BIT_OR,
            SqlFn.BIT_SHIFT_LEFT,
            SqlFn.BIT_SHIFT_RIGHT,
            SqlFn.BIT_XOR,
            SqlFn.CEILING,
            SqlFn.CONCAT,
            SqlFn.CURRENT_USER_FN,
            SqlFn.DATE_TRUNC_DAY,
            SqlFn.DECODE_BASE64,
            SqlFn.DIVIDE,
            SqlFn.ENCODE_BASE64,
            SqlFn.EQUAL,
            SqlFn.FLOOR,
            SqlFn.FROM_EPOCH_MS,
            SqlFn.GREATER,
            SqlFn.GREATER_EQUAL,
            SqlFn.GUID,
            SqlFn.IN,
            SqlFn.INT_DIVIDE,
            SqlFn.IS_DISTINCT,
            SqlFn.IS_NOT_NULL,
            SqlFn.IS_NULL,
            SqlFn.JSON_MERGE_PATCH,
            SqlFn.LC_FIRST,
            SqlFn.LESS,
            SqlFn.LESS_EQUAL,
            SqlFn.LIST_AGG,
            SqlFn.LIST_APPEND,
            SqlFn.LIST_AVG,
            SqlFn.LIST_BOOL_AND,
            SqlFn.LIST_BOOL_OR,
            SqlFn.LIST_CONCAT,
            SqlFn.LIST_CONTAINS,
            SqlFn.LIST_DISTINCT,
            SqlFn.LIST_EXISTS,
            SqlFn.LIST_FILTER,
            SqlFn.LIST_FOR_ALL,
            SqlFn.LIST_GET,
            SqlFn.LIST_INIT,
            SqlFn.LIST_MAX,
            SqlFn.LIST_MEDIAN,
            SqlFn.LIST_MIN,
            SqlFn.LIST_MODE,
            SqlFn.LIST_POSITION,
            SqlFn.LIST_PRODUCT,
            SqlFn.LIST_REDUCE,
            SqlFn.LIST_REVERSE,
            SqlFn.LIST_SLICE,
            SqlFn.LIST_SORT,
            SqlFn.LIST_SORT_DESC,
            SqlFn.LIST_SUM,
            SqlFn.LIST_TAIL,
            SqlFn.LIST_TRANSFORM,
            SqlFn.LIST_ZIP,
            SqlFn.LPAD,
            SqlFn.MAKE_TIMESTAMP,
            SqlFn.MAP_EMPTY,
            SqlFn.MINUS,
            SqlFn.MOD,
            SqlFn.NEGATE,
            SqlFn.NOT,
            SqlFn.NOT_EQUAL,
            SqlFn.NOW,
            SqlFn.OR,
            SqlFn.PI,
            SqlFn.PLUS,
            SqlFn.RANGE_FN,
            SqlFn.REM,
            SqlFn.ROUND,
            SqlFn.ROUND_HALF_UP,
            SqlFn.RPAD,
            SqlFn.SIGN,
            SqlFn.TIMES,
            SqlFn.TIME_BUCKET,
            SqlFn.TODAY,
            SqlFn.TO_VARIANT,
            SqlFn.TYPEOF,
            SqlFn.UC_FIRST,
            SqlFn.UNNEST,
            SqlFn.VARIANT_ELEMENTS,
            SqlFn.VARIANT_GET,
            SqlFn.XOR);

    @Test
    @DisplayName("every SqlFn is classified: spelling DATA or coded rule")
    void everySqlFnClassified() {
        for (SqlFn f : SqlFn.values()) {
            assertTrue(Spellings.DUCKDB.fnNames().containsKey(f)
                            || CODED.contains(f),
                    f + " is unclassified — add a Spellings row or a coded"
                    + " rule (and list it in CODED)");
            assertFalse(Spellings.DUCKDB.fnNames().containsKey(f)
                            && CODED.contains(f),
                    f + " is BOTH data and coded — pick one");
        }
    }
}
