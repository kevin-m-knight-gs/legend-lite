// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;
import com.legend.protocol.sql.SqlSyntaxException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

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
                {"SELECT id FROM t, u", "comma join"},
                {"SELECT id FROM t JOIN u ON a = b", "joins"},
                {"SELECT id FROM t GROUP BY id", "GROUP BY"},
                {"SELECT id FROM t UNION SELECT id FROM u", "UNION"},
                {"SELECT count(*) FROM t", "aggregate"},
                {"SELECT sum(id) FROM t", "aggregate"},
                {"SELECT dba.f(id) FROM t", "owner-qualified"},
                {"SELECT id FROM t WHERE vc LIKE 'a%'", "LIKE"},
                {"SELECT id FROM t WHERE id IN (1, 2)", "IN"},
                {"SELECT id FROM t WHERE (id = 1) IS TRUE", "is not supported"},
                {"SELECT id & 1 FROM t", "bitwise"},
                {"SELECT ? FROM t", "parameters"},
                {"SELECT @@version", "global variables"},
                {"SELECT (SELECT 1) FROM t", "subqueries"},
                {"WITH q AS (SELECT 1) SELECT * FROM q", "WITH"},
                {"SELECT id INTO x FROM t", "INTO"},
                {"SELECT id FROM t;", "separator"},
                {"SELECT TOP 10 PERCENT id FROM t", "PERCENT"},
                {"SELECT TOP @n id FROM t", "variable"},
                {"SELECT @v = id FROM t", "assignment"},
                {"SELECT id '' FROM t", "empty"},
                {"SELECT CAST(id AS INT) FROM t", "special form"},
                {"SELECT 0.000000000000000000000000000000000000001234 FROM t", "38"},
                {"SELECT 1e-50 FROM t", "38"},
                {"SELECT id FROM t ORDER BY +(1)", "position"},
        };
        for (String[] r : refused) {
            SqlSyntaxException e = error(r[0]);
            assertTrue(e.unsupported(), r[0] + ": " + e.reason());
            assertTrue(e.reason().contains(r[1]), r[0] + ": " + e.reason());
        }
    }

    // ------------------------------------------------------------------ M0's rest: row limits, aliases, IF, CASE

    @Test
    void topFirstAndStartAtAreTheLimitAndAnOffsetOfOneLess() {
        SqlAst.Select s = select("SELECT TOP 2 START AT 3 id FROM t ORDER BY id");
        assertEquals(BigInteger.TWO, ((SqlAst.IntegerLit) s.limit()).value());
        SqlAst.Binary offset = (SqlAst.Binary) s.offset();
        assertEquals(SqlAst.BinaryOp.MINUS, offset.op());
        assertEquals(BigInteger.valueOf(3), ((SqlAst.IntegerLit) offset.left()).value());
        assertEquals(BigInteger.ONE, ((SqlAst.IntegerLit) offset.right()).value());
        assertEquals(BigInteger.ONE, ((SqlAst.IntegerLit) select("SELECT FIRST id FROM t").limit()).value());
        SqlAst.Select all = select("SELECT DISTINCT TOP ALL START AT 2 id FROM t");
        assertTrue(all.distinct());
        assertNull(all.limit());
        // a parenthesized count is + - * over integers, * binding tighter
        SqlAst.Binary sum = (SqlAst.Binary) select("SELECT TOP (1+2*3) id FROM t").limit();
        assertEquals(SqlAst.BinaryOp.PLUS, sum.op());
        assertEquals(SqlAst.BinaryOp.TIMES, ((SqlAst.Binary) sum.right()).op());
        // TOP 2+1 x is TOP 2 then the item +1 named x (the research's GRAMMAR S5 parse trap)
        SqlAst.Select trap = select("SELECT TOP 2+1 x FROM t");
        assertEquals(BigInteger.TWO, ((SqlAst.IntegerLit) trap.limit()).value());
        assertEquals("x", ((SqlAst.SelectExpr) trap.items().get(0)).alias().text());
        // a literal TOP 0 parses: IQ's error -674 is the dialect's, at translation
        assertEquals(BigInteger.ZERO, ((SqlAst.IntegerLit) select("SELECT TOP 0 id FROM t").limit()).value());
    }

    @Test
    void rowLimitsIqRejects() {
        for (String sql : new String[] {"SELECT TOP 2147483648 id FROM t", "SELECT TOP 1.5 id FROM t",
                "SELECT TOP -1 id FROM t", "SELECT TOP (2 - -1) id FROM t", "SELECT TOP (4/2) id FROM t",
                "SELECT TOP 2 DISTINCT id FROM t", "SELECT TOP 1 TOP 2 id FROM t", "SELECT FIRST START AT 2 id FROM t",
                "SELECT START AT 2 id FROM t", "SELECT TOP 2 WITH TIES id FROM t ORDER BY id"}) {
            SqlSyntaxException e = error(sql);
            assertFalse(e.unsupported(), sql + ": " + e.reason());
            assertTrue(e.reason().contains("SQLCODE -131"), e.reason());
        }
    }

    @Test
    void tsqlAndStringAliasesNameAnItem() {
        SqlAst.Select s = select("SELECT n = id + 1, 'm' = vc, id 'k', vc AS 'a b', \"q\" = 1 FROM t");
        assertEquals(List.of("n", "m", "k", "a b", "q"), s.items().stream()
                .map(i -> ((SqlAst.SelectExpr) i).alias().text()).toList());
        assertInstanceOf(SqlAst.Binary.class, ((SqlAst.SelectExpr) s.items().get(0)).expr());
        // under a TDS login "q" is a string: still an alias
        SqlAst.Select tds = (SqlAst.Select) IqParser.parse("SELECT \"q\" = id, id \"r\" FROM t", IqProfile.IQ16_TDS);
        assertEquals("q", ((SqlAst.SelectExpr) tds.items().get(0)).alias().text());
        assertEquals("r", ((SqlAst.SelectExpr) tds.items().get(1)).alias().text());
        for (String sql : new String[] {"SELECT n = id = 1 FROM t", "SELECT n = id AS m FROM t",
                "SELECT t.n = id FROM t", "SELECT id 'a[b' FROM t", "SELECT id 'a\\x09' FROM t",
                "SELECT id + 1 = 2 FROM t"}) {
            SqlSyntaxException e = error(sql);
            assertFalse(e.unsupported(), sql + ": " + e.reason());
        }
    }

    @Test
    void ifIsTheCaseOfItsConditionAndItsNegation() {
        SqlAst.Case c = (SqlAst.Case) ((SqlAst.SelectExpr) select("SELECT IF a > 0 THEN 1 ELSE 2 ENDIF FROM t")
                .items().get(0)).expr();
        assertEquals(2, c.whens().size());
        assertNull(c.otherwise());
        SqlAst.Unary not = (SqlAst.Unary) c.whens().get(1).condition();
        assertEquals(SqlAst.UnaryOp.NOT, not.op());
        assertTrue(not.operand() == c.whens().get(0).condition());
        assertEquals(1, ((SqlAst.Case) ((SqlAst.SelectExpr) select("SELECT IF a > 0 THEN 1 END IF FROM t").items()
                .get(0)).expr()).whens().size());
        for (String sql : new String[] {"SELECT IF a > 0 THEN 1 END FROM t", "SELECT IF a THEN 1 ENDIF FROM t",
                "SELECT IF a > 0 THEN 1 ELSEIF a > 1 THEN 2 ENDIF FROM t", "SELECT IF a > 0 1 ENDIF FROM t"}) {
            assertFalse(error(sql).unsupported(), sql);
        }
    }

    @Test
    void aSimpleCaseIsTheSearchedCaseOfItsComparisons() {
        SqlAst.Case c = (SqlAst.Case) ((SqlAst.SelectExpr) select("SELECT CASE id WHEN 1 THEN 'a' WHEN 2 THEN 'b'"
                + " ELSE 'c' END CASE FROM t").items().get(0)).expr();
        assertNull(c.operand());
        SqlAst.Binary eq = (SqlAst.Binary) c.whens().get(1).condition();
        assertEquals(SqlAst.BinaryOp.EQ, eq.op());
        assertEquals(BigInteger.TWO, ((SqlAst.IntegerLit) eq.right()).value());
        assertInstanceOf(SqlAst.StringLit.class, c.otherwise());
        assertFalse(error("SELECT CASE WHEN id THEN 1 END FROM t").unsupported());
    }

    @Test
    void callsAndSignsAreValues() {
        SqlAst.FunctionCall f = (SqlAst.FunctionCall) ((SqlAst.SelectExpr) select("SELECT ISNULL(a, -b) FROM t")
                .items().get(0)).expr();
        assertEquals("ISNULL", f.name().get(0).text());
        assertInstanceOf(SqlAst.Unary.class, f.args().get(1));
        SqlAst.Unary twice = (SqlAst.Unary) ((SqlAst.SelectExpr) select("SELECT - -a FROM t").items().get(0)).expr();
        assertInstanceOf(SqlAst.Unary.class, twice.operand());
    }

    @Test
    void orderByASignedIntegerIsAPosition_aParenthesizedOneAConstant() {
        // measured on SQL Anywhere 17: +1 sorts by the first column, (1) by nothing
        assertEquals(BigInteger.ONE, ((SqlAst.IntegerLit) select("SELECT id FROM t ORDER BY +1 DESC").orderBy().get(0)
                .expr()).value());
        SqlAst.Select constant = select("SELECT sm, id FROM t ORDER BY (1) DESC, 2");
        assertEquals(1, constant.orderBy().size());
        assertEquals(BigInteger.TWO, ((SqlAst.IntegerLit) constant.orderBy().get(0).expr()).value());
    }

    @Test
    void anErrorKnowsItsPlace() {
        SqlSyntaxException e = error("SELECT id\nFROM t WHERE NOT NOT id = 1");
        assertEquals(2, e.line());
        assertEquals(18, e.column());
    }
}
