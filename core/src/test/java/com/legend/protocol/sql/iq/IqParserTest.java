// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IQ's SELECT read into the shared tree ({@link IqParser}): the shapes of M0's first slice, IQ's precedence and its
 * split of values from conditions, the syntax errors IQ makes too, and every form refused by name.
 */
class IqParserTest {

    private static SqlAst.Select select(String sql) {
        return (SqlAst.Select) IqParser.parse(sql, IqProfile.IQ16_DEFAULT);
    }

    private static SqlSyntaxException error(String sql) {
        return assertThrows(SqlSyntaxException.class, () -> IqParser.parse(sql, IqProfile.IQ16_DEFAULT));
    }

    @Test
    void aSelectHasItsItemsFromWhereAndOrder() {
        SqlAst.Select s = select("SELECT DISTINCT id, [vc] AS \"v\", nm n FROM tb('db::D.t') a WHERE id > 1 ORDER BY 2"
                + " DESC, id");
        assertTrue(s.distinct());
        assertEquals(3, s.items().size());
        SqlAst.SelectExpr v = (SqlAst.SelectExpr) s.items().get(1);
        assertEquals("v", v.alias().text());
        assertTrue(v.alias().quoted());
        assertEquals("[vc]", v.text());
        assertEquals("n", ((SqlAst.SelectExpr) s.items().get(2)).alias().text());
        SqlAst.TableFunction from = (SqlAst.TableFunction) s.from().get(0);
        assertEquals("tb", from.name().get(0).text());
        assertEquals("a", from.alias().text());
        assertInstanceOf(SqlAst.Binary.class, s.where());
        assertTrue(s.orderBy().get(0).descending());
        assertNull(s.orderBy().get(0).nullsFirst());
        assertFalse(s.orderBy().get(1).descending());
    }

    @Test
    void anItemKeepsItsTextAndAStarMayBeQualified() {
        SqlAst.Select s = select("SELECT t.*, *, 'it''s' , - 1 FROM t");
        assertEquals("t", ((SqlAst.AllColumns) s.items().get(0)).qualifier().text());
        assertNull(((SqlAst.AllColumns) s.items().get(1)).qualifier());
        assertEquals("'it''s'", ((SqlAst.SelectExpr) s.items().get(2)).text());
        SqlAst.SelectExpr minus = (SqlAst.SelectExpr) s.items().get(3);
        assertEquals("- 1", minus.text());
        SqlAst.Unary u = (SqlAst.Unary) minus.expr();
        assertEquals(SqlAst.UnaryOp.MINUS, u.op());
        assertEquals(BigInteger.ONE, ((SqlAst.IntegerLit) u.operand()).value());
    }

    @Test
    void numbersAreIntegersOrNumerics() {
        SqlAst.Select s = select("SELECT 12, 1.50, 1e3, 99999999999999999999 FROM t");
        assertEquals(BigInteger.valueOf(12), ((SqlAst.IntegerLit) ((SqlAst.SelectExpr) s.items().get(0)).expr()).value());
        assertEquals(new BigDecimal("1.50"), ((SqlAst.NumericLit) ((SqlAst.SelectExpr) s.items().get(1)).expr()).value());
        assertEquals(new BigDecimal("1000"), ((SqlAst.NumericLit) ((SqlAst.SelectExpr) s.items().get(2)).expr()).value());
        assertEquals(new BigInteger("99999999999999999999"),
                ((SqlAst.IntegerLit) ((SqlAst.SelectExpr) s.items().get(3)).expr()).value());
    }

    @Test
    void precedenceIsIqs_notOverAndOverOr_comparisonsBelowTheValueOperators() {
        SqlAst.Binary or = (SqlAst.Binary) select("SELECT a FROM t WHERE NOT a = 1 AND b = 2 OR c IS NOT NULL").where();
        assertEquals(SqlAst.BinaryOp.OR, or.op());
        SqlAst.Binary and = (SqlAst.Binary) or.left();
        assertEquals(SqlAst.BinaryOp.AND, and.op());
        assertInstanceOf(SqlAst.Unary.class, and.left());
        assertTrue(((SqlAst.IsNull) or.right()).negated());
        SqlAst.Binary cmp = (SqlAst.Binary) select("SELECT a FROM t WHERE a || b = c + d * e").where();
        assertEquals(SqlAst.BinaryOp.EQ, cmp.op());
        assertEquals(SqlAst.BinaryOp.CONCAT, ((SqlAst.Binary) cmp.left()).op());
        SqlAst.Binary plus = (SqlAst.Binary) cmp.right();
        assertEquals(SqlAst.BinaryOp.PLUS, plus.op());
        assertEquals(SqlAst.BinaryOp.TIMES, ((SqlAst.Binary) plus.right()).op());
    }

    @Test
    void iqsComparisonSpellings() {
        assertEquals(SqlAst.BinaryOp.GE, ((SqlAst.Binary) select("SELECT a FROM t WHERE a !< 1").where()).op());
        assertEquals(SqlAst.BinaryOp.LE, ((SqlAst.Binary) select("SELECT a FROM t WHERE a !> 1").where()).op());
        assertEquals(SqlAst.BinaryOp.NE, ((SqlAst.Binary) select("SELECT a FROM t WHERE a != 1").where()).op());
    }

    @Test
    void valuesAndConditionsStayApart_asInIq() {
        // syntax errors in IQ too (SQLCODE -131), measured on SQL Anywhere 17
        for (String sql : new String[] {"SELECT id FROM t WHERE id", "SELECT (id = 1) FROM t",
                "SELECT id FROM t WHERE id = 1 = 1", "SELECT id FROM t WHERE NOT NOT id = 1",
                "SELECT id FROM t ORDER BY id NULLS FIRST", "SELECT id AS from FROM t", "SELECT t.from FROM t"}) {
            SqlSyntaxException e = error(sql);
            assertFalse(e.unsupported(), sql + ": " + e.reason());
            assertTrue(e.reason().contains("SQLCODE -131"), e.reason());
        }
        assertInstanceOf(SqlAst.Unary.class, select("SELECT id FROM t WHERE NOT (NOT id = 1)").where());
    }

    @Test
    void formsNotReadYetAreRefusedByName() {
        String[][] refused = {
                {"SELECT TOP 2 id FROM t", "TOP"},
                {"SELECT id FROM t, u", "comma join"},
                {"SELECT id FROM t JOIN u ON a = b", "joins"},
                {"SELECT id FROM t GROUP BY id", "GROUP BY"},
                {"SELECT id FROM t UNION SELECT id FROM u", "UNION"},
                {"SELECT n = id FROM t", "T-SQL"},
                {"SELECT id 'x' FROM t", "string as an alias"},
                {"SELECT upper(vc) FROM t", "function calls"},
                {"SELECT CASE WHEN a = 1 THEN 1 END FROM t", "CASE"},
                {"SELECT id FROM t WHERE vc LIKE 'a%'", "LIKE"},
                {"SELECT id FROM t WHERE id IN (1, 2)", "IN"},
                {"SELECT id FROM t WHERE (id = 1) IS TRUE", "is not supported"},
                {"SELECT -id FROM t", "sign"},
                {"SELECT id & 1 FROM t", "bitwise"},
                {"SELECT ? FROM t", "parameters"},
                {"SELECT @@version", "global variables"},
                {"SELECT (SELECT 1) FROM t", "subqueries"},
                {"WITH q AS (SELECT 1) SELECT * FROM q", "WITH"},
                {"SELECT id INTO x FROM t", "INTO"},
                {"SELECT id FROM t;", "separator"},
        };
        for (String[] r : refused) {
            SqlSyntaxException e = error(r[0]);
            assertTrue(e.unsupported(), r[0] + ": " + e.reason());
            assertTrue(e.reason().contains(r[1]), r[0] + ": " + e.reason());
        }
    }

    @Test
    void anErrorKnowsItsPlace() {
        SqlSyntaxException e = error("SELECT id\nFROM t WHERE NOT NOT id = 1");
        assertEquals(2, e.line());
        assertEquals(18, e.column());
    }
}
