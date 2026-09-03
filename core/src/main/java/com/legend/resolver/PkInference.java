// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.typed.TypedAsOfJoin;
import com.legend.compiler.spec.typed.TypedDistinct;
import com.legend.compiler.spec.typed.TypedDrop;
import com.legend.compiler.spec.typed.TypedExtend;
import com.legend.compiler.spec.typed.TypedExtendAgg;
import com.legend.compiler.spec.typed.TypedExtendWindow;
import com.legend.compiler.spec.typed.TypedFilter;
import com.legend.compiler.spec.typed.TypedFrom;
import com.legend.compiler.spec.typed.TypedGroupBy;
import com.legend.compiler.spec.typed.TypedJoin;
import com.legend.compiler.spec.typed.TypedLimit;
import com.legend.compiler.spec.typed.TypedRename;
import com.legend.compiler.spec.typed.TypedSelect;
import com.legend.compiler.spec.typed.TypedSlice;
import com.legend.compiler.spec.typed.TypedSort;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedTableReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Primary-key inference over a relation expression tree — the engine's
 * {@code meta::pure::mapping::relation::inferPrimaryKeyColumnNames}
 * (relationFunctionMapping.pure) as a COMPILE-TIME stamp on the typed
 * tree (the rules, not the engine source): a bare table accessor's key is
 * the table's declared primary key; row-preserving operators keep it;
 * select keeps it when it projects every key column; rename maps it;
 * groupBy / distinct(cols) key on their columns; INNER / LEFT joins and
 * asOf joins union both sides; aggregate / pivot / concatenate / other
 * joins have no key. The rows carry the answer (FunctionBodyRows); the
 * database reads it.
 */
final class PkInference {

    private PkInference() {
    }

    static List<String> infer(TypedSpec n, ModelContext ctx) {
        return switch (n) {
            case TypedTableReference tr -> {
                List<String> pk = new ArrayList<>();
                ctx.findTableDefinition(tr.store(), tr.table()).ifPresent(td -> {
                    for (var c : td.columns()) {
                        if (c.primaryKey()) {
                            pk.add(c.name());
                        }
                    }
                });
                yield pk;
            }
            case TypedFilter f -> infer(f.source(), ctx);
            case TypedLimit l -> infer(l.source(), ctx);
            case TypedDrop d -> infer(d.source(), ctx);
            case TypedSlice s -> infer(s.source(), ctx);
            case TypedSort s -> infer(s.source(), ctx);
            case TypedExtend e -> infer(e.source(), ctx);
            case TypedExtendAgg e -> infer(e.source(), ctx);
            case TypedExtendWindow e -> infer(e.source(), ctx);
            case TypedFrom fr -> infer(fr.source(), ctx);
            case TypedSelect s -> {
                List<String> left = infer(s.source(), ctx);
                yield s.columns().isEmpty() || s.columns().containsAll(left)
                        ? left : List.of();
            }
            // distinct() — the checker spells the no-argument form over EVERY
            // column of the source; that is the row-preserving identity for
            // the key (the engine's distinct_Relation_1__Relation_1_ arm)
            case TypedDistinct d -> d.columns().isEmpty()
                    || (com.legend.compiler.element.type.Type.relationSchema(
                            d.source().info().type())
                            instanceof com.legend.compiler.element.type.Type.RelationType rt
                        && d.columns().size() == rt.columns().size()
                        && rt.columns().stream().allMatch(c -> d.columns().contains(c.name())))
                    ? infer(d.source(), ctx) : d.columns();
            case TypedRename r -> {
                List<String> out = new ArrayList<>();
                for (String c : infer(r.source(), ctx)) {
                    String mapped = c;
                    for (var rn : r.renames()) {
                        if (rn.from().equals(c)) {
                            mapped = rn.to();
                        }
                    }
                    out.add(mapped);
                }
                yield out;
            }
            case TypedGroupBy g -> g.keys().stream().map(k -> k.column()).toList();
            case TypedJoin j -> {
                String kind = j.kind().value();
                if (!kind.equals("INNER") && !kind.equals("LEFT")) {
                    yield List.of();
                }
                yield union(infer(j.left(), ctx), infer(j.right(), ctx));
            }
            case TypedAsOfJoin aj -> union(infer(aj.left(), ctx), infer(aj.right(), ctx));
            default -> List.of();
        };
    }

    private static List<String> union(List<String> a, List<String> b) {
        LinkedHashSet<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return new ArrayList<>(out);
    }
}
