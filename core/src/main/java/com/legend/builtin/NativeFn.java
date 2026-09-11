// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import com.legend.model.NativeFunctionDefinition;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE NATIVE FUNCTION FAMILIES — the catalog natives ({@link Pure}) grouped
 * by the code that IMPLEMENTS them, each family a CLOSED TYPE: an enum whose
 * constants carry their catalog overloads and whose owning switch is a
 * switch EXPRESSION with no default, so a new member does not COMPILE until
 * it is handled. The sibling of {@link com.legend.compiler.spec.CoreFn}
 * (language forms with their own checker): {@code CoreFn} = a form, {@code
 * NativeFn} = a native with an implementer. The registry
 * ({@code com.legend.claims.Claims}) reads {@link #families()}: the enum is
 * the set and the dispatch key, so nothing beside the code can drift, and no
 * compiler code dispatches on a function-name STRING (USER 2026-09-10:
 * "we should not dispatch on strings and only on typed things that are
 * registered"). One file, next to the catalog it groups.
 *
 * <p>Batch 4b of the upstream boundary program folds the batch-3 enums
 * ({@code CalendarFn}, {@code AssertFn}, {@code RowGetter}) in here and adds
 * a family per remaining implementer until the ledger's UNCLAIMED count is 0.
 */
public final class NativeFn {

    private NativeFn() {
    }

    /** One member of a family: the FQN it implements and every catalog overload of it. */
    public interface Member {
        String fqn();

        List<NativeFunctionDefinition> overloads();

        /** The bare function name ({@code assertEquals}) — for messages. */
        default String bareName() {
            String f = fqn();
            return f.substring(f.lastIndexOf(':') + 1);
        }
    }

    /** FQN -> member; immutable (ArchitectureTest invariant 3). */
    static <E extends Enum<E> & Member> Map<String, E> index(E[] values) {
        Map<String, E> m = new HashMap<>();
        for (E e : values) {
            m.put(e.fqn(), e);
        }
        return Map.copyOf(m);
    }

    /** Every family, by name — THE registration the claim registry reads. A
     *  new enum in this file that is not listed here is unclaimed, and the
     *  ledger says so. */
    public static Map<String, List<? extends Member>> families() {
        Map<String, List<? extends Member>> out = new LinkedHashMap<>();
        out.put("Calendar", List.of(Calendar.values()));
        out.put("Verdict", List.of(Verdict.values()));
        out.put("RowGetter", List.of(RowGetter.values()));
        out.put("Frame", List.of(Frame.values()));
        out.put("LowererForm", List.of(LowererForm.values()));
        return out;
    }

    /** the 32 calendar natives CalendarAgg lowers to SQL calendar aggregation. */
    public enum Calendar implements Member {
        CY_MINUS2("meta::pure::functions::date::calendar::CYMinus2",
                Pure.CAL_C_Y_MINUS2),
        CY_MINUS3("meta::pure::functions::date::calendar::CYMinus3",
                Pure.CAL_C_Y_MINUS3),
        ANNUALIZED("meta::pure::functions::date::calendar::annualized",
                Pure.CAL_ANNUALIZED),
        CME("meta::pure::functions::date::calendar::cme",
                Pure.CAL_CME),
        CW("meta::pure::functions::date::calendar::cw",
                Pure.CAL_CW),
        CW_FM("meta::pure::functions::date::calendar::cw_fm",
                Pure.CAL_CW_FM),
        MTD("meta::pure::functions::date::calendar::mtd",
                Pure.CAL_MTD),
        P12MTD("meta::pure::functions::date::calendar::p12mtd",
                Pure.CAL_P12MTD),
        P12WA("meta::pure::functions::date::calendar::p12wa",
                Pure.CAL_P12WA),
        P12WTD("meta::pure::functions::date::calendar::p12wtd",
                Pure.CAL_P12WTD),
        P4WA("meta::pure::functions::date::calendar::p4wa",
                Pure.CAL_P4WA),
        P4WTD("meta::pure::functions::date::calendar::p4wtd",
                Pure.CAL_P4WTD),
        P52WA("meta::pure::functions::date::calendar::p52wa",
                Pure.CAL_P52WA),
        P52WTD("meta::pure::functions::date::calendar::p52wtd",
                Pure.CAL_P52WTD),
        PMA("meta::pure::functions::date::calendar::pma",
                Pure.CAL_PMA),
        PMTD("meta::pure::functions::date::calendar::pmtd",
                Pure.CAL_PMTD),
        PQTD("meta::pure::functions::date::calendar::pqtd",
                Pure.CAL_PQTD),
        PRIOR_DAY("meta::pure::functions::date::calendar::priorDay",
                Pure.CAL_PRIOR_DAY),
        PRIOR_YEAR("meta::pure::functions::date::calendar::priorYear",
                Pure.CAL_PRIOR_YEAR),
        PW("meta::pure::functions::date::calendar::pw",
                Pure.CAL_PW),
        PW_FM("meta::pure::functions::date::calendar::pw_fm",
                Pure.CAL_PW_FM),
        PWA("meta::pure::functions::date::calendar::pwa",
                Pure.CAL_PWA),
        PWTD("meta::pure::functions::date::calendar::pwtd",
                Pure.CAL_PWTD),
        PYMTD("meta::pure::functions::date::calendar::pymtd",
                Pure.CAL_PYMTD),
        PYQTD("meta::pure::functions::date::calendar::pyqtd",
                Pure.CAL_PYQTD),
        PYTD("meta::pure::functions::date::calendar::pytd",
                Pure.CAL_PYTD),
        PYWA("meta::pure::functions::date::calendar::pywa",
                Pure.CAL_PYWA),
        PYWTD("meta::pure::functions::date::calendar::pywtd",
                Pure.CAL_PYWTD),
        QTD("meta::pure::functions::date::calendar::qtd",
                Pure.CAL_QTD),
        REPORT_END_DAY("meta::pure::functions::date::calendar::reportEndDay",
                Pure.CAL_REPORT_END_DAY),
        WTD("meta::pure::functions::date::calendar::wtd",
                Pure.CAL_WTD),
        YTD("meta::pure::functions::date::calendar::ytd",
                Pure.CAL_YTD);

        private final String fqn;
        private final List<NativeFunctionDefinition> overloads;

        Calendar(String fqn, NativeFunctionDefinition... overloads) {
            this.fqn = fqn;
            this.overloads = List.of(overloads);
        }

        @Override
        public String fqn() {
            return fqn;
        }

        @Override
        public List<NativeFunctionDefinition> overloads() {
            return overloads;
        }

        private static final Map<String, Calendar> BY_FQN = index(values());

        /** The member a callee FQN resolves to — empty when the callee is not
         *  in this family (a normal fall-through, never an error). */
        public static Optional<Calendar> of(String calleeFqn) {
            return Optional.ofNullable(BY_FQN.get(calleeFqn));
        }
    }

    /** the asserts AssertVerdicts adjudicates as row verdicts, plus the two relation verdict forms it owns. */
    public enum Verdict implements Member {
        ASSERT_EQUALS("meta::pure::functions::asserts::assertEquals",
                Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_EQUALS__ANY_MANY__ANY_MANY__FN_1),
        ASSERT_NOT_EQUALS("meta::pure::functions::asserts::assertNotEquals",
                Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_NOT_EQUALS__ANY_MANY__ANY_MANY__FN_1),
        ASSERT_SAME_ELEMENTS("meta::pure::functions::asserts::assertSameElements",
                Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__STRING_1, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_SAME_ELEMENTS__ANY_MANY__ANY_MANY__FN_1),
        ASSERT_SIZE("meta::pure::functions::asserts::assertSize",
                Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__STRING_1, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__STRING_1__ANY_MANY, Pure.ASSERT_SIZE__ANY_MANY__INTEGER_1__FN_1),
        ASSERT_JSON_STRINGS_EQUAL("meta::pure::functions::asserts::assertJsonStringsEqual",
                Pure.ASSERT_JSON_STRINGS_EQUAL__STRING_1__STRING_1),
        ASSERT_CONTAINS("meta::pure::functions::asserts::assertContains",
                Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__STRING_1, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_CONTAINS__ANY_MANY__ANY_1__FN_1),
        ASSERT_EQ("meta::pure::functions::asserts::assertEq",
                Pure.ASSERT_EQ__ANY_1__ANY_1, Pure.ASSERT_EQ__ANY_1__ANY_1__STRING_1, Pure.ASSERT_EQ__ANY_1__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_EQ__ANY_1__ANY_1__FN_1),
        ASSERT_EQ_WITHIN_TOLERANCE("meta::pure::functions::asserts::assertEqWithinTolerance",
                Pure.ASSERT_EQ_WITHIN_TOLERANCE__NUMBER_1__NUMBER_1__NUMBER_1, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__STRING_1, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__STRING_1__ANY_MANY, Pure.ASSERT_EQ_WITHIN_TOLERANCE__N_1__N_1__N_1__FN_1),
        ASSERT("meta::pure::functions::asserts::assert",
                Pure.ASSERT__BOOLEAN_1, Pure.ASSERT__BOOLEAN_1__STRING_1, Pure.ASSERT__BOOLEAN_1__FN_1, Pure.ASSERT__BOOLEAN_1__STRING_1__ANY_MANY),
        ASSERT_FALSE("meta::pure::functions::asserts::assertFalse",
                Pure.ASSERT_FALSE__BOOLEAN_1, Pure.ASSERT_FALSE__BOOLEAN_1__STRING_1, Pure.ASSERT_FALSE__BOOLEAN_1__STRING_1__ANY_MANY, Pure.ASSERT_FALSE__BOOLEAN_1__FN_1),
        ASSERT_INSTANCE_OF("meta::pure::functions::asserts::assertInstanceOf",
                Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__STRING_1, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__STRING_1__ANY_MANY, Pure.ASSERT_INSTANCE_OF__ANY_1__TYPE_1__FN_1),
        ASSERT_IS("meta::pure::functions::asserts::assertIs",
                Pure.ASSERT_IS__ANY_1__ANY_1, Pure.ASSERT_IS__ANY_1__ANY_1__STRING_1, Pure.ASSERT_IS__ANY_1__ANY_1__STRING_1__ANY_MANY, Pure.ASSERT_IS__ANY_1__ANY_1__FN_1),
        ASSERT_EMPTY("meta::pure::functions::asserts::assertEmpty",
                Pure.ASSERT_EMPTY__ANY_MANY, Pure.ASSERT_EMPTY__ANY_MANY__STRING_1, Pure.ASSERT_EMPTY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_EMPTY__ANY_MANY__FN_1),
        ASSERT_NOT_EMPTY("meta::pure::functions::asserts::assertNotEmpty",
                Pure.ASSERT_NOT_EMPTY__ANY_MANY, Pure.ASSERT_NOT_EMPTY__ANY_MANY__STRING_1, Pure.ASSERT_NOT_EMPTY__ANY_MANY__STRING_1__ANY_MANY, Pure.ASSERT_NOT_EMPTY__ANY_MANY__FN_1),
        ASSERT_TDS_EQUIVALENT("meta::pure::functions::relation::assertTdsEquivalent",
                Pure.ASSERT_TDS_EQUIVALENT__REL_1__REL_1__NUMBER_1, Pure.ASSERT_TDS_EQUIVALENT__REL_1__REL_1__NUMBER_1__NUMBER_1),
        TO_CSV("meta::relational::tests::csv::toCSV",
                Pure.TO_CSV__TDS, Pure.TO_CSV__TDS_BOOL, Pure.TO_CSV__TDS_FMT);

        private final String fqn;
        private final List<NativeFunctionDefinition> overloads;

        Verdict(String fqn, NativeFunctionDefinition... overloads) {
            this.fqn = fqn;
            this.overloads = List.of(overloads);
        }

        @Override
        public String fqn() {
            return fqn;
        }

        @Override
        public List<NativeFunctionDefinition> overloads() {
            return overloads;
        }

        private static final Map<String, Verdict> BY_FQN = index(values());

        /** The member a callee FQN resolves to — empty when the callee is not
         *  in this family (a normal fall-through, never an error). */
        public static Optional<Verdict> of(String calleeFqn) {
            return Optional.ofNullable(BY_FQN.get(calleeFqn));
        }
    }

    /** the TDSRow getters RowGetters lowers to column reads. */
    public enum RowGetter implements Member {
        GET_BOOLEAN("meta::pure::tds::getBoolean",
                Pure.GET_BOOLEAN__TDS_ROW_1__STRING_1),
        GET_DATE("meta::pure::tds::getDate",
                Pure.GET_DATE__TDS_ROW_1__STRING_1),
        GET_DATE_TIME("meta::pure::tds::getDateTime",
                Pure.GET_DATE_TIME__TDS_ROW_1__STRING_1),
        GET_DECIMAL("meta::pure::tds::getDecimal",
                Pure.GET_DECIMAL__TDS_ROW_1__STRING_1),
        GET_FLOAT("meta::pure::tds::getFloat",
                Pure.GET_FLOAT__TDS_ROW_1__STRING_1),
        GET_INTEGER("meta::pure::tds::getInteger",
                Pure.GET_INTEGER__TDS_ROW_1__STRING_1),
        GET_NUMBER("meta::pure::tds::getNumber",
                Pure.GET_NUMBER__TDS_ROW_1__STRING_1),
        GET_STRICT_DATE("meta::pure::tds::getStrictDate",
                Pure.GET_STRICT_DATE__TDS_ROW_1__STRING_1),
        GET_STRING("meta::pure::tds::getString",
                Pure.GET_STRING__TDS_ROW_1__STRING_1);

        private final String fqn;
        private final List<NativeFunctionDefinition> overloads;

        RowGetter(String fqn, NativeFunctionDefinition... overloads) {
            this.fqn = fqn;
            this.overloads = List.of(overloads);
        }

        @Override
        public String fqn() {
            return fqn;
        }

        @Override
        public List<NativeFunctionDefinition> overloads() {
            return overloads;
        }

        private static final Map<String, RowGetter> BY_FQN = index(values());

        /** The member a callee FQN resolves to — empty when the callee is not
         *  in this family (a normal fall-through, never an error). */
        public static Optional<RowGetter> of(String calleeFqn) {
            return Optional.ofNullable(BY_FQN.get(calleeFqn));
        }
    }

    /** the window-frame keywords Frames classifies and the over() checker consumes by type. */
    public enum Frame implements Member {
        ROWS("meta::pure::functions::relation::rows",
                Pure.ROWS__INTEGER_1__INTEGER_1, Pure.ROWS__UNBOUNDED_1__UNBOUNDED_1, Pure.ROWS__UNBOUNDED_1__INTEGER_1, Pure.ROWS__INTEGER_1__UNBOUNDED_1),
        RANGE("meta::pure::functions::relation::_range",
                Pure._RANGE__NUMBER_1__NUMBER_1, Pure._RANGE__UNBOUNDED_1__NUMBER_1, Pure._RANGE__NUMBER_1__UNBOUNDED_1, Pure._RANGE__INT_1__DU_1__INT_1__DU_1, Pure._RANGE__UNBOUNDED_1__INT_1__DU_1, Pure._RANGE__INT_1__DU_1__UNBOUNDED_1, Pure._RANGE__UNBOUNDED_1__UNBOUNDED_1),
        UNBOUNDED("meta::pure::functions::relation::unbounded",
                Pure.UNBOUNDED);

        private final String fqn;
        private final List<NativeFunctionDefinition> overloads;

        Frame(String fqn, NativeFunctionDefinition... overloads) {
            this.fqn = fqn;
            this.overloads = List.of(overloads);
        }

        @Override
        public String fqn() {
            return fqn;
        }

        @Override
        public List<NativeFunctionDefinition> overloads() {
            return overloads;
        }

        private static final Map<String, Frame> BY_FQN = index(values());

        /** The member a callee FQN resolves to — empty when the callee is not
         *  in this family (a normal fall-through, never an error). */
        public static Optional<Frame> of(String calleeFqn) {
            return Optional.ofNullable(BY_FQN.get(calleeFqn));
        }
    }

    /** relation forms the Lowerer lowers structurally (lateral joins, windowed map+reduce, composed window expressions, bi-variate aggregate maps). */
    public enum LowererForm implements Member {
        LATERAL("meta::pure::functions::relation::lateral",
                Pure.LATERAL__RELATION_1__FUNCTION_1),
        REDUCE("meta::pure::functions::relation::reduce",
                Pure.REDUCE__RELATION_1__WINDOW_1__T_1__FUNCTION_1__FUNCTION_1),
        Z_SCORE("meta::pure::functions::math::zScore",
                Pure.Z_SCORE__WINDOW),
        ROW_MAPPER("meta::pure::functions::math::mathUtility::rowMapper",
                Pure.ROW_MAPPER__T_0_1__U_0_1),
        WAVG_ROW_MAPPER("meta::pure::functions::math::wavgUtility::wavgRowMapper",
                Pure.WAVG_ROW_MAPPER__NUMBER_0_1__NUMBER_0_1);

        private final String fqn;
        private final List<NativeFunctionDefinition> overloads;

        LowererForm(String fqn, NativeFunctionDefinition... overloads) {
            this.fqn = fqn;
            this.overloads = List.of(overloads);
        }

        @Override
        public String fqn() {
            return fqn;
        }

        @Override
        public List<NativeFunctionDefinition> overloads() {
            return overloads;
        }

        private static final Map<String, LowererForm> BY_FQN = index(values());

        /** rowMapper / wavgRowMapper — the bi-variate aggregate map bodies. */
        public static boolean isBivariateMap(String calleeFqn) {
            LowererForm f = BY_FQN.get(calleeFqn);
            return f == ROW_MAPPER || f == WAVG_ROW_MAPPER;
        }

        /** The member a callee FQN resolves to — empty when the callee is not
         *  in this family (a normal fall-through, never an error). */
        public static Optional<LowererForm> of(String calleeFqn) {
            return Optional.ofNullable(BY_FQN.get(calleeFqn));
        }
    }
}
