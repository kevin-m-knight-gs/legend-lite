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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** The source table: id INTEGER not null, sm SMALLINT, vc VARCHAR(20), dt DATE, bt BIT. */
    private static final List<Col> COLUMNS = List.of(
            new Col("id", "Integer", false, "INTEGER"),
            new Col("sm", "Integer", true, "SMALLINT"),
            new Col("vc", "String", true, "VARCHAR(20)"),
            new Col("dt", "StrictDate", true, "DATE"),
            new Col("bt", "Boolean", true, "BIT"));

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
        assertEquals("#>{s::DB.T}#->extend(~['1.5':r|1.50D, '-1':r|-1, '\\'a\\'':r|'a', null:r|[]->cast(@Integer)])"
                        + "->select(~['1.5', '-1', '\\'a\\'', null])",
                pure("SELECT 1.50, -1, 'a', NULL FROM " + T));
        // a whole numeric between an INT's range and eleven digits: its name is not known yet
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT 4000000000. FROM " + T).kind());
    }

    @Test
    void aSelectListAliasReadOutsideOrderByIsRefused() {
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT id AS x FROM " + T
                + " WHERE x = 1").kind());
        assertEquals(SqlTranslationException.Kind.UNSUPPORTED, refusal("SELECT vc AS id FROM " + T
                + " WHERE id = 1").kind());
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
    void conversionsBetweenKindsAndArithmeticAreLaterSlices() {
        for (String where : new String[] {"vc = 1", "dt = '2020-01-01'", "bt = 1", "id + 1 = 2", "vc || 'a' = 'b'"}) {
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED,
                    refusal("SELECT id FROM " + T + " WHERE " + where).kind(), where);
        }
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
                IqProfile.IQ16_DEFAULT)));
        assertEquals(IqDialect.Engine.SA, IqDialect.engineOf(IqParser.parse("SELECT 1", IqProfile.IQ16_DEFAULT)));
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
