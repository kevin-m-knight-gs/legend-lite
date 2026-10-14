// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.PureComposer;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.sql.SqlToPure;
import com.legend.protocol.sql.SqlTranslationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IQ's SQL through the one translator ({@link SqlToPure} reading {@link IqDialect}): the Pure each query becomes,
 * printed as a person would write it, IQ's name, NULL, blank-padding and CHAR rules, the engine a statement runs in,
 * and each refusal's kind. The FROM source's columns, with their store types, come from a stand-in for the typer;
 * the iq-parity lanes (IqParityTest, IqParityPostgresTest) run the same pipeline end to end.
 */
class IqToPureTest {

    /** A source column, as the typer reports it: name, Pure type, nullable, store type. */
    private record Col(String name, String type, boolean nullable, String store) {
    }

    /** The source table: id INTEGER not null, sm SMALLINT, vc VARCHAR(20), dt DATE. */
    private static final List<Col> COLUMNS = List.of(
            new Col("id", "Integer", false, "INTEGER"),
            new Col("sm", "Integer", true, "SMALLINT"),
            new Col("vc", "String", true, "VARCHAR(20)"),
            new Col("dt", "StrictDate", true, "DATE"));

    /** A table of every integer type: i INTEGER not null, sm SMALLINT, bg BIGINT, bt BIT (the store's Boolean), and
     *  nm NUMERIC(10,2), d DOUBLE. */
    private static final List<Col> NUMBERS = List.of(
            new Col("i", "Integer", false, "INTEGER"),
            new Col("sm", "Integer", true, "SMALLINT"),
            new Col("bg", "Integer", true, "BIGINT"),
            new Col("bt", "Boolean", true, "BIT"),
            new Col("nm", "Decimal", true, "NUMERIC(10,2)"),
            new Col("d", "Float", true, "DOUBLE"));

    /** A table with a CHAR(5) column. */
    private static final List<Col> WITH_CHAR = List.of(
            new Col("id", "Integer", false, "INTEGER"),
            new Col("ch", "String", true, "CHAR(5)"),
            new Col("vc", "String", true, "VARCHAR(20)"));

    private static final String T = "tb('s::DB.T')";

    private static String pure(String sql) {
        return pure(sql, IqProfile.IQ16_DEFAULT, COLUMNS);
    }

    private static String pure(String sql, IqProfile profile) {
        return pure(sql, profile, COLUMNS);
    }

    private static String pure(String sql, IqProfile profile, List<Col> columns) {
        ValueSpecification vs = SqlToPure.translate(sql, (source, column) -> columns.forEach(
                c -> column.column(c.name(), c.type(), c.nullable(), c.store())), IqDialect.of(profile));
        return PureComposer.valueSpecification(vs, PureComposer.Style.STANDARD);
    }

    private static SqlTranslationException refusal(String sql) {
        return refusal(sql, IqProfile.IQ16_DEFAULT, COLUMNS);
    }

    private static SqlTranslationException refusal(String sql, IqProfile profile, List<Col> columns) {
        return assertThrows(SqlTranslationException.class, () -> pure(sql, profile, columns));
    }

    // ------------------------------------------------------------------ shapes and names

    @Test
    void aPlainQueryIsFilterSortSelect_withNullTheLowestValue() {
        assertEquals("#>{s::DB.T}#->filter(r|$r.id > 2)"
                        + "->sort([~sm->descending(meta::pure::functions::relation::NullOrder.LAST),"
                        + " ~id->ascending(meta::pure::functions::relation::NullOrder.FIRST)])->select(~[id, sm])",
                pure("SELECT id, sm FROM " + T + " WHERE id > 2 ORDER BY sm DESC, id"));
    }

    @Test
    void namesMatchWithoutCase_quotedOrNot_andAColumnShowsAsWritten() {
        assertEquals("#>{s::DB.T}#->extend(~[ID:r|$r.id, SM:r|$r.sm])->select(~[ID, SM])",
                pure("SELECT ID, [SM] FROM " + T));
        assertEquals("#>{s::DB.T}#->filter(r|$r.id == 1)->select(~[id])", pure("SELECT `id` FROM " + T + " a WHERE"
                + " A.ID = 1"));
        // two names a reference matches without regard to case are an ambiguity, quoted or not
        List<Col> twins = List.of(new Col("id", "Integer", false, "INTEGER"), new Col("ID", "Integer", false,
                "INTEGER"));
        SqlTranslationException e = refusal("SELECT [id] FROM " + T, IqProfile.IQ16_DEFAULT, twins);
        assertEquals(SqlTranslationException.Kind.INVALID, e.kind());
    }

    @Test
    void anUnaliasedConstantIsNamedByIqsRenderingOfIt() {
        // a numeric constant is written at IQ's scale for it: 1.50 is NUMERIC(2,1), 1.5
        assertEquals("#>{s::DB.T}#->extend(~['1.5':r|1.5D, '-1':r|-1, '\\'a\\'':r|'a', null:r|[]->cast(@Integer)])"
                        + "->select(~['1.5', '-1', '\\'a\\'', null])",
                pure("SELECT 1.50, -1, 'a', NULL FROM " + T));
        // a whole numeric between an INT's range and eleven digits: its name is not known yet
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT 4000000000. FROM " + T).kind());
    }

    @Test
    void aSelectListAliasReadAnywhereButAsABareOrderByKeyIsRefused() {
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT id AS x FROM " + T
                + " WHERE x = 1").kind());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT vc AS id FROM " + T
                + " WHERE id = 1").kind());
        // inside an ORDER BY expression IQ reads the alias too (the second review's B1), in any case and through any
        // sign or function
        for (String key : new String[] {"x + 0", "-x", "+x", "ISNULL(x, 0)", "CASE WHEN X > 0 THEN 0 ELSE 1 END",
                "IF x > 0 THEN 0 ELSE 1 ENDIF"}) {
            SqlTranslationException e = refusal("SELECT id, sm AS x FROM " + T + " ORDER BY " + key);
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind(), key);
            assertTrue(e.getMessage().contains("inside an ORDER BY expression"), e.getMessage());
        }
        SqlTranslationException shadow = refusal("SELECT id AS sm FROM " + T + " ORDER BY -sm");
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, shadow.kind());
        // a bare alias, a position and a qualified column are read as before
        String bare = pure("SELECT id, sm AS x FROM " + T + " ORDER BY x");
        assertTrue(bare.contains("->sort([~x->ascending("), bare);
        pure("SELECT id, sm AS x FROM " + T + " ORDER BY 2");
        // an alias of the column of its own name reads the same
        assertEquals("#>{s::DB.T}#->filter(r|$r.id == 1)->extend(~[ID:r|$r.id])->select(~[ID])",
                pure("SELECT id AS ID FROM " + T + " WHERE id = 1"));
    }

    // ------------------------------------------------------------------ NULL

    @Test
    void equalsNullIsUnknown_orIsNullUnderATdsLogin() {
        assertEquals("#>{s::DB.T}#->filter(r|false)->select(~[id])", pure("SELECT id FROM " + T + " WHERE sm = NULL"));
        assertEquals("#>{s::DB.T}#->filter(r|$r.sm->isEmpty())->select(~[id])",
                pure("SELECT id FROM " + T + " WHERE sm = NULL", IqProfile.IQ16_TDS));
        assertEquals("#>{s::DB.T}#->filter(r|$r.sm->isNotEmpty())->select(~[id])",
                pure("SELECT id FROM " + T + " WHERE NULL != sm", IqProfile.IQ16_TDS));
        // only = and != are rewritten
        assertEquals("#>{s::DB.T}#->filter(r|false)->select(~[id])",
                pure("SELECT id FROM " + T + " WHERE sm < NULL", IqProfile.IQ16_TDS));
    }

    // ------------------------------------------------------------------ strings

    @Test
    void stringsAreEqualIgnoringTrailingBlanks() {
        assertEquals("#>{s::DB.T}#->filter(r|$r.vc->isNotEmpty() && (($r.vc->toOne()->startsWith('x') ||"
                        + " 'x'->startsWith($r.vc->toOne())) && ($r.vc->toOne()->replace(' ', '') =="
                        + " 'x'->replace(' ', ''))))->select(~[id])",
                pure("SELECT id FROM " + T + " WHERE vc = 'x'"));
    }

    @Test
    void aCharColumnIsPaddedToItsLengthUnderTheIqEngine() {
        assertEquals("#>{s::DB.T}#->extend(~[_sql_1:r|if($r.ch->isEmpty(), |[], |$r.ch->toOne()->rpad(5, ' '))])"
                        + "->select(~[id, _sql_1, vc])->rename(~_sql_1, ~ch)->select(~[ch])",
                pure("SELECT ch FROM " + T, IqProfile.IQ16_DEFAULT, WITH_CHAR));
    }

    @Test
    void orderingStringsAndCaseInsensitiveComparisonsWaitForTheirNatives() {
        assertTrue(refusal("SELECT id FROM " + T + " WHERE vc < 'b'").getMessage().contains("right trim"));
        assertTrue(refusal("SELECT id FROM " + T + " ORDER BY vc").getMessage().contains("ORDER BY over strings"));
        assertTrue(refusal("SELECT DISTINCT vc FROM " + T).getMessage().contains("DISTINCT over strings"));
        SqlTranslationException e = refusal("SELECT id FROM " + T + " WHERE vc = 'x'", IqProfile.SQLA17_DEFAULT,
                COLUMNS);
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind());
        assertTrue(e.getMessage().contains("case fold"), e.getMessage());
    }

    // ------------------------------------------------------------------ kinds and refusals

    @Test
    void conversionsBetweenKindsAreLaterSlices() {
        for (String where : new String[] {"vc = 1", "dt = '2020-01-01'", "vc + 1 = 'b'", "dt + 1 = dt"}) {
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED,
                    refusal("SELECT id FROM " + T + " WHERE " + where).kind(), where);
        }
    }

    // ------------------------------------------------------------------ M0's rest

    private static String numbers(String sql) {
        return pure(sql, IqProfile.IQ16_DEFAULT, NUMBERS);
    }

    private static SqlTranslationException numbersRefusal(String sql) {
        return refusal(sql, IqProfile.IQ16_DEFAULT, NUMBERS);
    }

    @Test
    void aBitIsReadAsTheInteger0Or1_andComparesWithIntegers() {
        String p = numbers("SELECT i FROM " + T + " WHERE bt = 1");
        assertTrue(p.contains("if($r.bt->isNotEmpty() && $r.bt->toOne(), |1, |if($r.bt->isNotEmpty() &&"
                + " !$r.bt->toOne(), |0, |[]))"), p);
    }

    @Test
    void integerArithmeticIsTypedAndCheckedAsIqs() {
        // smallint + smallint constant: a SMALLINT, checked against its range, the column widened to 64 bits first
        String plus = numbers("SELECT sm + 1 AS n FROM " + T);
        assertTrue(plus.contains("$r.sm->toOne()->floor() + 1"), plus);
        assertTrue(plus.contains("< -32768") && plus.contains("> 32767"), plus);
        // two constants that cannot leave their type: no check, no widening
        assertTrue(numbers("SELECT 2 + 3 * 4 AS n FROM " + T).contains("n:r|2 + (3 * 4)"));
        // a constant result out of its type's range is IQ's error now (-158)
        SqlTranslationException over = numbersRefusal("SELECT 32767 + 1 AS n FROM " + T);
        assertEquals(SqlTranslationException.Kind.INVALID, over.kind());
        assertTrue(over.getMessage().contains("SQLCODE -158"), over.getMessage());
        // a result that cannot leave its type is not checked: a BIT and a SMALLINT give an INTEGER
        assertFalse(numbers("SELECT bt + sm AS n FROM " + T).contains("2147483647"));
        // a BIGINT result is never checked: the database computes it in 64 bits and raises its own overflow first
        String wide = numbers("SELECT i + bg AS n FROM " + T);
        assertFalse(wide.contains("9223372036854775807") || wide.contains("cast(@Integer)->toOne()"), wide);
        // / truncates toward zero (the shared exact quotient); % is rem
        assertTrue(numbers("SELECT i / 2 AS q FROM " + T).contains("->rem("));
        assertTrue(numbers("SELECT i % 2 AS r FROM " + T).contains("->rem(2)->cast(@Integer)"));
        // a minus before a column is checked in its type; before a constant it is a constant typed by its value
        assertTrue(numbers("SELECT -sm AS n FROM " + T).contains("> 32767"));
        assertEquals(SqlTranslationException.Kind.INVALID, numbersRefusal("SELECT -(-32768) AS n FROM " + T).kind());
    }

    @Test
    void aConstantThatMayBeNullIsCheckedWhereItIsComputed() {
        // the second review's M3: IQ answers NULL where the IF is unknown, and raises only where it computes 32768
        for (String sql : new String[] {"SELECT IF i = 1 THEN 32767 ENDIF + 1 AS n FROM ",
                "SELECT CASE WHEN i = 1 THEN 2147483647 END + 1 AS n FROM ",
                "SELECT NULLIF(32767, sm) + 1 AS n FROM "}) {
            String p = numbers(sql + T);
            assertTrue(p.contains("cast(@Integer)->toOne()"), p);
        }
        assertTrue(pure("SELECT IF 1 = 2 THEN 32767 ENDIF + 1 AS n").contains("cast(@Integer)->toOne()"));
        // constants IQ folds still raise at once, as IQ does
        assertEquals(SqlTranslationException.Kind.INVALID, numbersRefusal("SELECT (40000 - 39999) + 2147483647 AS n"
                + " FROM " + T).kind());
        assertEquals(SqlTranslationException.Kind.INVALID, numbersRefusal("SELECT NULLIF(sm, 100000) AS n FROM " + T)
                .kind());
    }

    @Test
    void checkedArithmeticPastItsSizeIsRefusedQuickly() {
        // the second review's M1: each checked operation repeats its operands, fourfold per level
        String twelve = String.join(" + ", java.util.Collections.nCopies(12, "sm"));
        SqlTranslationException e = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> numbersRefusal("SELECT " + twelve + " AS n FROM " + T));
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind());
        assertTrue(e.getMessage().contains("nests too many operations"), e.getMessage());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, numbersRefusal("SELECT i / 2 / 2 / 2 AS n FROM " + T)
                .kind());
        // what is translated stays small
        String six = numbers("SELECT " + String.join(" + ", java.util.Collections.nCopies(6, "sm")) + " AS n FROM "
                + T);
        assertTrue(six.length() < 100_000, "" + six.length());
        String bigints = numbers("SELECT " + String.join(" + ", java.util.Collections.nCopies(10, "bg")) + " AS n FROM "
                + T);
        assertTrue(bigints.length() < 100_000, "" + bigints.length());
    }

    @Test
    void aRowLimitOverASelectListThatCanRaiseIsRefused() {
        // the second review's M2: IQ computes the select list only for the rows it keeps
        for (String sql : new String[] {"SELECT TOP 1 i, sm + 1 AS n FROM " + T + " ORDER BY i DESC",
                "SELECT TOP 1 i, 100 / sm AS n FROM " + T + " ORDER BY i DESC",
                "SELECT FIRST i, i * 2 AS n FROM " + T + " ORDER BY i",
                "SELECT TOP 1 START AT 2 i, -sm AS n FROM " + T + " ORDER BY i",
                "SELECT TOP 1 i FROM " + T + " ORDER BY bg + 1",
                "SELECT TOP 1 i, NULLIF(sm, bg) AS n FROM " + T + " ORDER BY i"}) {
            SqlTranslationException e = numbersRefusal(sql);
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind(), sql);
            assertTrue(e.getMessage().contains("TOP, FIRST or START AT"), e.getMessage());
        }
        // what cannot raise is translated, and so is the same arithmetic without a row limit or in WHERE
        numbers("SELECT TOP 2 i, sm + 0 AS n FROM " + T + " ORDER BY i DESC");
        numbers("SELECT i, sm + 1 AS n FROM " + T + " ORDER BY i DESC");
        numbers("SELECT TOP 2 i FROM " + T + " WHERE sm + 1 > 0 ORDER BY i DESC");
    }

    @Test
    void arithmeticIqTypesAsNumericOrUnsignedIsRefused() {
        for (String sql : new String[] {"SELECT nm + 1 AS n", "SELECT d * 2 AS n", "SELECT bg % 2 AS n",
                "SELECT bt + bt AS n", "SELECT bt + bg AS n", "SELECT 2147483648 + 1 AS n", "SELECT 1.5 * 2 AS n"}) {
            SqlTranslationException e = numbersRefusal(sql + " FROM " + T);
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind(), sql);
        }
        assertTrue(numbersRefusal("SELECT nm + 1 AS n FROM " + T).getMessage().contains("D-N1"));
    }

    @Test
    void concatenationReadsASingleNullAsEmpty() {
        String p = pure("SELECT vc || '!' AS s FROM " + T);
        assertTrue(p.contains("$r.vc->coalesce('') + '!'"), p);
        String both = pure("SELECT vc + vc AS s FROM " + T);
        assertTrue(both.contains("if($r.vc->isEmpty() && $r.vc->isEmpty(), |[]"), both);
        assertTrue(pure("SELECT id || 'x' AS s FROM " + T).contains("$r.id->toString() + 'x'"));
        // + of a string and anything else is IQ's NUMERIC addition
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT vc + 1 AS s FROM " + T).kind());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT 'a' + NULL AS s FROM " + T).kind());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT dt || 'x' AS s FROM " + T).kind());
    }

    @Test
    void ifAndCaseTakeIqsUnifiedType() {
        String p = numbers("SELECT IF sm > 0 THEN sm ELSE bg ENDIF AS n FROM " + T);
        assertTrue(p.contains("if("), p);
        // CHAR results of different lengths: whether the IQ engine pads them is open (D-S1)
        SqlTranslationException e = refusal("SELECT IF id > 0 THEN 'pos' ELSE 'nonpos' ENDIF AS s FROM " + T);
        assertTrue(e.getMessage().contains("D-S1"), e.getMessage());
        // SQL Anywhere's front end (no FROM) does not pad: answered
        assertTrue(pure("SELECT IF 1 > 0 THEN 'pos' ELSE 'nonpos' ENDIF AS s").contains("'nonpos'"));
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED,
                refusal("SELECT CASE WHEN id = 1 THEN 'a' ELSE 1 END AS s FROM " + T).kind());
    }

    @Test
    void equalsNullInACaseConditionIsRewrittenByTheIqEngineOnly() {
        String iq = pure("SELECT IF sm = NULL THEN 1 ELSE 0 ENDIF AS s FROM " + T, IqProfile.IQ16_TDS);
        assertTrue(iq.contains("$r.sm->isEmpty()"), iq);
        String sa = pure("SELECT IF NULL = NULL THEN 1 ELSE 0 ENDIF AS s", IqProfile.IQ16_TDS);
        assertFalse(sa.contains("isEmpty"), sa);
    }

    @Test
    void theCatalogsFunctions() {
        assertTrue(numbers("SELECT ISNULL(sm, i, 0) AS n FROM " + T).contains("coalesce("));
        String ifnull = numbers("SELECT IFNULL(sm, 99) AS n FROM " + T);
        assertTrue(ifnull.contains("if($r.sm->isEmpty(), |99, |[]"), ifnull);
        String nullif = numbers("SELECT NULLIF(sm, bg) AS n FROM " + T);
        assertTrue(nullif.contains("> 32767"), nullif);
        SqlTranslationException arity = numbersRefusal("SELECT ISNULL(sm) AS n FROM " + T);
        assertEquals(SqlTranslationException.Kind.INVALID, arity.kind());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, numbersRefusal("SELECT upper(i) AS n FROM " + T)
                .kind());
    }

    @Test
    void rowLimitsAreConstantsThePlannerFolds() {
        String p = pure("SELECT TOP (1+1) START AT 3 id FROM " + T + " ORDER BY id");
        assertTrue(p.endsWith("->drop(3 - 1)->limit(1 + 1)"), p);
        for (String sql : new String[] {"SELECT TOP 0 id FROM ", "SELECT TOP (1-2) id FROM ",
                "SELECT TOP 1 START AT 0 id FROM ", "SELECT TOP (65536*65536) id FROM "}) {
            assertEquals(SqlTranslationException.Kind.INVALID, refusal(sql + T).kind(), sql);
        }
        assertTrue(pure("SELECT TOP (1-1) id FROM " + T + " ORDER BY id").endsWith("->limit(1 - 1)"));
        // without ORDER BY the rows kept are IQ's plan's choice (the second review's m4): refused, after IQ's own
        // errors; without FROM there is one row
        for (String sql : new String[] {"SELECT TOP 2 id FROM ", "SELECT FIRST id FROM ",
                "SELECT TOP ALL START AT 2 id FROM "}) {
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal(sql + T).kind(), sql);
        }
        assertTrue(pure("SELECT TOP 1 1 AS x").endsWith("->limit(1)"));
    }

    @Test
    void theReviewsRefusals() {
        // B3: a FROM function other than the table source
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT * FROM generate_series(1, 3)").kind());
        // B4: two aliases IQ cannot tell apart (-830)
        SqlTranslationException dup = refusal("SELECT sm AS k, id AS K FROM " + T);
        assertEquals(SqlTranslationException.Kind.INVALID, dup.kind());
        assertTrue(dup.getMessage().contains("-830"), dup.getMessage());
        // B6: more digits than a sqla17 database keeps
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT 1234567890123456789012345678901 AS v"
                + " FROM " + T, IqProfile.SQLA17_DEFAULT, COLUMNS).kind());
        // B1: a REAL column refuses its table
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT id FROM " + T, IqProfile.IQ16_DEFAULT,
                List.of(new Col("id", "Integer", false, "INTEGER"), new Col("r", "Float", true, "REAL"))).kind());
        // m4: a CHAR of no length read is refused by name, never a crash
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT c FROM " + T, IqProfile.IQ16_DEFAULT,
                List.of(new Col("c", "String", true, "CHAR"))).kind());
        // M4: a sqla17 profile has no IQ engine: no CHAR padding
        assertEquals("#>{s::DB.T}#->select(~[ch])", pure("SELECT ch FROM " + T, IqProfile.SQLA17_DEFAULT,
                WITH_CHAR));
    }

    @Test
    void aStoreTypeTheIslandDoesNotReadRefusesItsSource() {
        List<Col> binary = List.of(new Col("id", "Integer", false, "INTEGER"), new Col("b", "String", true,
                "BINARY(16)"));
        SqlTranslationException e = refusal("SELECT id FROM " + T, IqProfile.IQ16_DEFAULT, binary);
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind());
        assertTrue(e.getMessage().contains("BINARY(16)"), e.getMessage());
    }

    @Test
    void anUnknownColumnIsIqsErrorWithItsCode() {
        SqlTranslationException e = refusal("SELECT nope FROM " + T);
        assertEquals(SqlTranslationException.Kind.INVALID, e.kind());
        assertTrue(e.getMessage().contains("SQLCODE -143"), e.getMessage());
    }

    @Test
    void theEngineIsTheStatements_iqOverATable_saWithoutFrom() {
        assertEquals(IqDialect.Engine.IQ, IqDialect.engineOf(IqParser.parse("SELECT id FROM " + T,
                IqProfile.IQ16_DEFAULT), IqProfile.IQ16_DEFAULT));
        assertEquals(IqDialect.Engine.SA, IqDialect.engineOf(IqParser.parse("SELECT 1", IqProfile.IQ16_DEFAULT),
                IqProfile.IQ16_DEFAULT));
        // a CHAR read under SQL Anywhere's rules is not padded
        assertEquals("#>{s::DB.T}#->select(~[ch])", PureComposer.valueSpecification(SqlToPure.translate(
                "SELECT ch FROM " + T, (source, column) -> WITH_CHAR.forEach(c -> column.column(c.name(), c.type(),
                        c.nullable(), c.store())), IqDialect.of(IqProfile.IQ16_DEFAULT, IqDialect.Engine.SA)),
                PureComposer.Style.STANDARD));
    }

    @Test
    void withoutFromIsOneRow() {
        assertTrue(pure("SELECT 1 AS one WHERE 1 = 1").contains("->filter(r|1 == 1)"));
    }
}
