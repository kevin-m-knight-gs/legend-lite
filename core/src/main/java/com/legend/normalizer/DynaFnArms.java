package com.legend.normalizer;

import com.legend.builtin.DynaFn;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The mapping translator's DECLARED set of engine dynafunctions it has a
 * rewriting arm for (the {@code case … when call.name().equals(…)} arms in
 * {@code RelOpTranslator}) — public so the registry's test can hold the two
 * descriptions together: every {@code DynaFn.Resolution.TRANSLATED} name is in
 * here, and every name in here that is not TRANSLATED is PURE (the arm rewrites
 * the engine's EXTRA shape — {@code and(a, b, c)}, {@code parseDate(s, fmt)} —
 * and pure's own shape passes through to the catalog).
 */
public final class DynaFnArms {

    private DynaFnArms() {
    }

    /** Every engine dynafunction with a translator arm. */
    public static final Set<DynaFn> ARMS = Collections.unmodifiableSet(EnumSet.of(
            DynaFn.ADD, DynaFn.ADJUST, DynaFn.AND,
            DynaFn.CONCAT, DynaFn.CONVERT_DATE,
            DynaFn.CONVERT_DATE_TIME, DynaFn.CONVERT_TIME_ZONE,
            DynaFn.CONVERT_VARCHAR128, DynaFn.DAY_OF_WEEK,
            DynaFn.DAY_OF_WEEK_NUMBER, DynaFn.EXTRACT_FROM_SEMI_STRUCTURED,
            DynaFn.GROUP, DynaFn.IF, DynaFn.INDEX_OF,
            DynaFn.IS_NOT_NULL, DynaFn.IS_NULL,
            DynaFn.MD5, DynaFn.OR,
            DynaFn.PARSE_DATE, DynaFn.POSITION,
            DynaFn.SHA1, DynaFn.SHA256, DynaFn.SPLIT_PART,
            DynaFn.SUBSTRING, DynaFn.TO_TIMESTAMP));

}
