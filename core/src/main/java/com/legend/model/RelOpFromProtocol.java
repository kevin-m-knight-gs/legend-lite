// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.model;

import com.legend.protocol.Protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code PRelOp} &rarr; {@link RelationalOperation}: the operation half of
 * finishing the protocol-first migration (PARSER_COMPLETENESS_PLAN.md §1).
 *
 * <p>The two trees are shaped for different jobs. The PROTOCOL tree is the
 * engine's wire shape and is deliberately flat — every operator is a
 * {@code dynaFunc} carrying a name, because that is what serialises. The MODEL
 * tree is what the compiler reasons over, so comparisons, boolean connectives
 * and null tests are distinct typed variants. Transforming one into the other
 * therefore means RE-DERIVING semantics from a closed vocabulary of function
 * names, which is exactly where a quiet SQL-semantics bug could hide.
 *
 * <p>That vocabulary is small and fixed — the twelve names below are every
 * operator {@code DatabaseProtocolParser} and {@code OperatorParts} can mint —
 * and anything outside it stays a {@link RelationalOperation.FunctionCall},
 * which is what the legacy parser did with an unrecognised function too.
 *
 * <p>Correctness is not argued, it is DIFFERENTIALLY TESTED: {@code
 * MigrationEquivalenceTest} parses every corpus database both ways and requires
 * the resulting models to be equal. The legacy parser is not deleted until that
 * is green over the whole corpus.
 */
public final class RelOpFromProtocol {

    private RelOpFromProtocol() {
    }

    public static RelationalOperation op(Protocol.PRelOp p) {
        return switch (p) {
            case Protocol.PColumnRef c -> columnRef(c);
            case Protocol.PRelLiteral l -> new RelationalOperation.Literal(l.value());
            case Protocol.PRelLiteralList l -> new RelationalOperation.ArrayLiteral(
                    l.values().stream().map(RelOpFromProtocol::op).toList());
            case Protocol.PElemtWithJoins j -> joinNavigation(j);
            case Protocol.PDynaFunc f -> dynaFunc(f);
        };
    }

    /** {@code {target}.COL} arrives with the sentinel table alias the engine
     *  uses for a self-join's far side; everything else is a plain column. */
    private static RelationalOperation columnRef(Protocol.PColumnRef c) {
        if ("target".equals(c.tableAlias())) {
            return new RelationalOperation.TargetColumnRef(c.column());
        }
        Protocol.PTablePtr t = c.table();
        return new RelationalOperation.ColumnRef(t.database(), t.table(),
                c.column());
    }

    private static RelationalOperation joinNavigation(Protocol.PElemtWithJoins j) {
        List<JoinChainElement> chain = new ArrayList<>();
        String db = null;
        for (Protocol.PJoinPtr ptr : j.joins()) {
            if (db == null) {
                db = ptr.db();
            }
            // the joinType rides the NEXT pointer on the wire (relational leg)
            // — the model hangs it on the element it qualifies
            chain.add(new JoinChainElement(ptr.name(),
                    ptr.joinType() == null ? null
                            : JoinType.fromIdentifier(ptr.joinType()),
                    ptr.db(), false));
        }
        return new RelationalOperation.JoinNavigation(db, chain,
                j.relationalElement() == null ? null : op(j.relationalElement()));
    }

    /**
     * The closed operator vocabulary. Anything not named here is a genuine
     * function call — the same fallback the legacy parser applied.
     */
    private static RelationalOperation dynaFunc(Protocol.PDynaFunc f) {
        List<RelationalOperation> args = f.parameters().stream()
                .map(RelOpFromProtocol::op).toList();
        ComparisonOp cmp = comparison(f.funcName());
        if (cmp != null && args.size() == 2) {
            return new RelationalOperation.Comparison(args.get(0), cmp, args.get(1));
        }
        switch (f.funcName()) {
            case "and", "or" -> {
                if (args.size() >= 2) {
                    LogicalOp lop = "and".equals(f.funcName())
                            ? LogicalOp.AND : LogicalOp.OR;
                    // the wire flattens a same-operator run into ONE n-ary
                    // dynaFunc; the model is binary, so fold left to rebuild
                    // the shape the legacy parser produced
                    RelationalOperation acc = args.get(0);
                    for (int i = 1; i < args.size(); i++) {
                        acc = new RelationalOperation.BooleanOp(acc, lop, args.get(i));
                    }
                    return acc;
                }
            }
            case "isNull" -> {
                if (args.size() == 1) {
                    return new RelationalOperation.IsNull(args.get(0));
                }
            }
            case "isNotNull" -> {
                if (args.size() == 1) {
                    return new RelationalOperation.IsNotNull(args.get(0));
                }
            }
            case "group" -> {
                if (args.size() == 1) {
                    return new RelationalOperation.Group(args.get(0));
                }
            }
            default -> {
                // fall through to FunctionCall
            }
        }
        return new RelationalOperation.FunctionCall(f.funcName(), args);
    }

    private static @com.legend.Nullable ComparisonOp comparison(String name) {
        return switch (name) {
            case "equal" -> ComparisonOp.EQ;
            // the engine spells inequality two ways: '!=' mints notEqual and
            // '<>' mints notEqualAnsi; both are NEQ to the compiler
            case "notEqual", "notEqualAnsi" -> ComparisonOp.NEQ;
            case "lessThan" -> ComparisonOp.LT;
            case "lessThanEqual" -> ComparisonOp.LTE;
            case "greaterThan" -> ComparisonOp.GT;
            case "greaterThanEqual" -> ComparisonOp.GTE;
            default -> null;
        };
    }
}
