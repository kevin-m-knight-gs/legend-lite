// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.element.TypedFunction;
import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.List;

/**
 * NUMERIC CHARTER Rule 2a — the ONE owner of a Number-DECLARED native
 * result's kind (docs/NUMERIC_CHARTER_2026_09_17.md).
 *
 * <p>Every Number-declared ARITHMETIC native in the reference ends in the same
 * call ({@code NumericUtilities.toPureNumberValueExpression(result,
 * anyOperandIsDecimal)}: Rem, Abs, the Number overloads of plus / minus /
 * times / sum): the kind is the JOIN of the operand
 * kinds — any Decimal gives Decimal, all Integer gives Integer, otherwise
 * Float. This is a refinement of the registered signature's output from
 * the call's own facts, at the seam where the Decimal carrier and
 * parseDate already refine (the Decimal half was accepted before:
 * InferenceKernel's PrecisionDecimal-under-Number rule). The root envelope
 * is its one consumer. An operand whose kind is itself an unrefined
 * Number leaves the result Number.
 */
final class NumberKinds {

    private NumberKinds() {
    }

    /** The Number-declared natives that COMPUTE a number and so take the
     * operand join (the reference's {@code NumericUtilities.toPureNumber…}
     * with the Decimal flag: Plus, Minus, Times, Rem, Abs, Sum). A SELECTOR
     * — max, min, greatest, least — returns the chosen element with its OWN
     * kind ({@code max(1.23, 2)} is the Integer 2; Channel B 2026-09-17), so
     * it stays Number; {@code pow} is Float for any double operand and
     * Integer for two integers (Power.java) and stays Number too. */
    private static final java.util.Set<String> ARITHMETIC = java.util.Set.of(
            "meta::pure::functions::math::plus",
            "meta::pure::functions::math::minus",
            "meta::pure::functions::math::times",
            "meta::pure::functions::math::rem",
            "meta::pure::functions::math::abs",
            "meta::pure::functions::math::sum");

    static ExprType refine(TypedFunction chosen, List<TypedSpec> args, ExprType out) {
        // the SIGNATURE declares Number (rem, abs, pow, plus(Number[*]) …) —
        // never a generic native whose type variable merely resolved to
        // Number (sort(T[*]) over a mixed list keeps every element's own kind)
        if (out.type() != Type.Primitive.NUMBER || chosen.returnType() != Type.Primitive.NUMBER
                || !chosen.isNative() || args.isEmpty()
                || !ARITHMETIC.contains(chosen.qualifiedName())) {
            return out;
        }
        boolean anyFloat = false;
        boolean anyDecimal = false;
        for (TypedSpec a : args) {
            // a LITERAL collection argument (plus([1, 2.5]), sum([...])) joins
            // over its elements — its own type is the unrefined Number
            List<TypedSpec> operands = a instanceof TypedCollection tc ? tc.elements() : List.of(a);
            for (TypedSpec o : operands) {
                Type t = o.info().type();
                if (t == Type.Primitive.DECIMAL || t instanceof Type.PrecisionDecimal) {
                    anyDecimal = true;
                } else if (t == Type.Primitive.FLOAT) {
                    anyFloat = true;
                } else if (t != Type.Primitive.INTEGER) {
                    return out;   // an unrefined Number (or a non-numeric) operand
                }
            }
        }
        // all-Integer operands stay NUMBER: Pure's static type, and the
        // natives whose result is a Float whatever the operands (stdDev,
        // variance, pow(2, 2) = 4.0) are declared Number too — only the
        // Float and Decimal halves of the join are the boundary's business
        if (anyDecimal) {
            return new ExprType(Type.Primitive.DECIMAL, out.multiplicity());
        }
        return anyFloat ? new ExprType(Type.Primitive.FLOAT, out.multiplicity()) : out;
    }
}
