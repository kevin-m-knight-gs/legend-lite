// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.lowering;

import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedCollection;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.sql.SqlExpr;
import com.legend.sql.SqlFn;

import java.util.List;

/**
 * REGEXP helper spine (real regex/*.pure): RegexpParameter enums
 * translate to RE2 option chars — CASE_SENSITIVE 'c', CASE_INSENSITIVE
 * 'i', MULTILINE 'm', NON_NEWLINE_SENSITIVE 's' (POSIX '.' matches
 * newline). Extracted whole from {@link Scalars}; the registrations
 * there consume these.
 */
final class RegexpRules {

    private RegexpRules() {
    }

    /** The enum VALUE of a literal enum argument; loud on anything else. */
    /**
     * RegexpParameter enum values (single or list) as RE2 INLINE flag chars —
     * prepended to the pattern as {@code (?ims)}; DuckDB's option-argument
     * chars have different semantics, inline flags are the portable spelling.
     */
    static String regexpFlags(TypedSpec arg) {
        List<TypedSpec> params =
                arg instanceof TypedCollection c
                        ? c.elements() : List.of(arg);
        StringBuilder flags = new StringBuilder();
        for (var pm : params) {
            flags.append(switch (Scalars.enumName(pm)) {
                case "CASE_SENSITIVE" -> "";   // the default
                case "CASE_INSENSITIVE" -> "i";
                case "MULTILINE" -> "m";
                case "NON_NEWLINE_SENSITIVE" -> "s";
                default -> throw new IllegalStateException(
                        "unknown RegexpParameter " + Scalars.enumName(pm));
            });
        }
        return flags.toString();
    }

    /** {@code pattern} -> {@code '(?<flags>)' || pattern}; identity when no flags. */
    static SqlExpr inlineFlags(SqlExpr pattern, String flags) {
        if (flags.isEmpty()) {
            return pattern;
        }
        String prefix = "(?" + flags + ")";
        return pattern instanceof SqlExpr.StringLit lit
                ? new SqlExpr.StringLit(prefix + lit.value())
                : SqlExpr.Call.of(SqlFn.CONCAT, new SqlExpr.StringLit(prefix), pattern);
    }

    /**
     * {@code regexp_extract_all(subject, pattern, group, flags)} for a regexp
     * call whose OPTIONAL group/params begin at {@code tailStart} in the
     * lowered args (group defaults 0; flags default '').
     */
    static SqlExpr regexpAll(TypedNativeCall n,
                                     List<SqlExpr> args, int tailStart) {
        SqlExpr group = new SqlExpr.IntLit(0);
        String flags = "";
        for (int i = tailStart; i < n.args().size(); i++) {
            if (args.get(i) instanceof SqlExpr.IntLit g) {
                group = g;
            } else {
                flags = regexpFlags(n.args().get(i));
            }
        }
        return new SqlExpr.Call(SqlFn.REGEXP_EXTRACT_ALL, List.of(
                args.get(0), inlineFlags(args.get(1), flags), group));
    }

    /** Char index of the {@code k}-th CAPTURING paren in a literal pattern. */
    static int capturingParen(String pattern, int k) {
        int count = 0;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            // a '(' inside a character class ([...]) is a literal, not a group
            if (c == '[' && !inClass) {
                inClass = true;
                continue;
            }
            if (c == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) {
                continue;
            }
            if (c == '(' && (i + 1 >= pattern.length() || pattern.charAt(i + 1) != '?')) {
                count++;
                if (count == k) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("pattern '" + pattern
                + "' has no capturing group " + k);
    }

}
