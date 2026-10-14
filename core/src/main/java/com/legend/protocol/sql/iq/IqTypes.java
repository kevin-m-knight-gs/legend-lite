// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql.iq;

import com.legend.protocol.sql.SqlAst;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * IQ's types of constants and of integer arithmetic (the research's TYPING §2, §3 and §8, measured on SQL Anywhere 17,
 * whose front end types every IQ expression; docs/IQ_SQL_STUDY_2026_10_13.md §1.4). Model-space rules: each reads
 * types, and a constant's written value, never a value the database computes.
 * <ul>
 *   <li><b>Constants.</b> An integer is a SMALLINT up to 32767, an INTEGER up to 2147483647, a NUMERIC of its digits
 *       beyond (never a TINYINT or a BIGINT). A minus before an integer constant gives a constant typed by its value:
 *       {@code -32768} is a SMALLINT, {@code -2147483648} a NUMERIC (2147483648 is one). A number with a point or an
 *       exponent is a NUMERIC of its digits without trailing zeros; a string a CHAR of its length.</li>
 *   <li><b>Integer arithmetic</b> over BIT, SMALLINT, INTEGER and BIGINT: {@code + - /} give the larger type,
 *       {@code *} too except SMALLINT * SMALLINT, an INTEGER; {@code %} keeps the larger of SMALLINT and INTEGER and
 *       gives a NUMERIC for any other pair; a BIT with a SMALLINT gives an INTEGER, with an INTEGER a BIGINT, with a
 *       BIGINT a NUMERIC; BIT - BIT is a SMALLINT and the other BIT pairs an unsigned SMALLINT. A NULL constant takes
 *       the other operand's type ({@code NULL op T} is {@code T op T}); two give a SMALLINT ({@code *}: an INTEGER).
 *       A result outside its type's range is IQ's error -158, never a wider type: the range of each result is worked
 *       out from its operands' ranges, so only a result that can leave its type's range is checked.</li>
 *   <li><b>Unification</b> of integers (CASE, IF, ISNULL, COALESCE): the smallest of the types that holds them all
 *       (BIT with any other gives the other).</li>
 * </ul>
 */
final class IqTypes {

    private IqTypes() {
    }

    /** A non-negative integer constant's type, exactly its value. */
    static IqType ofIntegerConstant(BigInteger v) {
        if (v.compareTo(IqType.SMALLINT_MAX) <= 0) {
            return IqType.integer("SMALLINT", v, v, false);
        }
        if (v.compareTo(IqType.INTEGER_MAX) <= 0) {
            return IqType.integer("INTEGER", v, v, false);
        }
        return new IqType("NUMERIC(" + v.toString().length() + ",0)");
    }

    /** A minus before a non-negative integer constant: a constant typed by its value, a SMALLINT from -32768 and an
     *  INTEGER from -2147483647 (measured); beyond, the constant's NUMERIC. */
    static IqType ofNegatedConstant(BigInteger magnitude) {
        BigInteger v = magnitude.negate();
        if (v.compareTo(IqType.SMALLINT_MIN) >= 0) {
            return IqType.integer("SMALLINT", v, v, false);
        }
        if (magnitude.compareTo(IqType.INTEGER_MAX) <= 0) {
            return IqType.integer("INTEGER", v, v, false);
        }
        return new IqType("NUMERIC(" + magnitude.toString().length() + ",0)");
    }

    /** A constant with a point or an exponent: a NUMERIC of its digits, trailing zeros dropped (1.50 is (2,1)). */
    static IqType ofNumericConstant(BigDecimal v) {
        BigDecimal d = v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros();
        int scale = Math.max(d.scale(), 0);
        int precision = Math.max(d.precision() - d.scale() + scale, scale);
        return new IqType("NUMERIC(" + Math.max(precision, 1) + "," + scale + ")");
    }

    /** A constant with a point or an exponent, at the scale of its type ({@link #ofNumericConstant}): its digits
     *  without trailing zeros, never fewer than none after the point. */
    static BigDecimal atScale(BigDecimal v) {
        BigDecimal d = v.signum() == 0 ? BigDecimal.ZERO : v.stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0) : d;
    }

    /** A string constant: a CHAR of its length. */
    static IqType ofString(String v) {
        return new IqType("CHAR(" + v.length() + ")");
    }

    /** The integer type IQ gives {@code l op r} ({@code + - * / %}) for two of the island's integer types; null when
     *  IQ's result is no integer type the island computes with (a NUMERIC, an unsigned type). */
    static @com.legend.base.Nullable String arithmetic(SqlAst.BinaryOp op, String l, String r) {
        boolean lBit = l.equals("BIT");
        boolean rBit = r.equals("BIT");
        if (lBit && rBit) {
            return op == SqlAst.BinaryOp.MINUS ? "SMALLINT" : null;
        }
        if (lBit || rBit) {
            String other = lBit ? r : l;
            if (op == SqlAst.BinaryOp.MODULO) {
                return null;
            }
            return other.equals("SMALLINT") ? "INTEGER" : other.equals("INTEGER") ? "BIGINT" : null;
        }
        String larger = rank(l) >= rank(r) ? l : r;
        return switch (op) {
            case MODULO -> larger.equals("BIGINT") ? null : larger;
            case TIMES -> l.equals("SMALLINT") && r.equals("SMALLINT") ? "INTEGER" : larger;
            case PLUS, MINUS, DIVIDE -> larger;
            default -> null;
        };
    }

    /** A minus before a value of an integer type: a BIT's is a SMALLINT, any other keeps its type. */
    static String negated(String family) {
        return family.equals("BIT") ? "SMALLINT" : family;
    }

    /** The integer type holding both: the larger (BIT with another gives the other). */
    static String unified(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String family) {
        return new IqType(family).rank();
    }

    /** The range {@code l op r} may lie in, from its operands' ranges ({@code / %} by a nonzero divisor: zero is IQ's
     *  error -628 either way). */
    static BigInteger[] range(SqlAst.BinaryOp op, IqType l, IqType r) {
        BigInteger a = l.lo();
        BigInteger b = l.hi();
        BigInteger c = r.lo();
        BigInteger d = r.hi();
        return switch (op) {
            case PLUS -> new BigInteger[] {a.add(c), b.add(d)};
            case MINUS -> new BigInteger[] {a.subtract(d), b.subtract(c)};
            case TIMES -> {
                BigInteger[] p = {a.multiply(c), a.multiply(d), b.multiply(c), b.multiply(d)};
                BigInteger lo = p[0];
                BigInteger hi = p[0];
                for (BigInteger x : p) {
                    lo = lo.min(x);
                    hi = hi.max(x);
                }
                yield new BigInteger[] {lo, hi};
            }
            case DIVIDE -> {
                BigInteger dividend = a.abs().max(b.abs());
                // the smallest nonzero |divisor|: one, unless the divisor's range keeps away from zero
                BigInteger divisor = c.signum() > 0 ? c : d.signum() < 0 ? d.negate() : BigInteger.ONE;
                BigInteger q = dividend.divide(divisor);
                yield new BigInteger[] {q.negate(), q};
            }
            case MODULO -> {
                BigInteger k = a.abs().max(b.abs()).min(c.abs().max(d.abs()).subtract(BigInteger.ONE)
                        .max(BigInteger.ZERO));
                yield new BigInteger[] {k.negate(), k};
            }
            default -> throw new IllegalArgumentException("not integer arithmetic: " + op);
        };
    }
}
