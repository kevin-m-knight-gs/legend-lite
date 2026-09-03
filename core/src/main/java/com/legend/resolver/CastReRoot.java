// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.resolver;

import com.legend.compiler.element.type.ExprType;
import com.legend.compiler.element.type.Type;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.typed.TypedJoin;
import com.legend.compiler.spec.typed.TypedLambda;
import com.legend.compiler.spec.typed.TypedNativeCall;
import com.legend.compiler.spec.typed.TypedPropertyAccess;
import com.legend.compiler.spec.typed.TypedSpec;
import com.legend.compiler.spec.typed.TypedVariable;
import com.legend.error.NotImplementedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * {@code chain->cast(@Sub)} BELOW a flatten hop (harness burn-down group
 * Q, 2026-09-03): the composed row (a member-union row of the subtype's
 * parent) RE-ROOTS at the subtype's OWN extent, joined on the shared
 * primary key. Extracted from StoreResolver (file-size guardrail).
 */
final class CastReRoot {

    private CastReRoot() {
    }

    /**
     * {@code chain->cast(@Sub)} below a flatten hop: the composed row (a
     * member-union row of the subtype's parent) RE-ROOTS at the subtype's
     * OWN extent, joined on the shared primary key — the same physical
     * row, now read through the member set's bindings (its own routes:
     * {@code FunctionParametersValidationNode.functionParameters}). The
     * gate filter (a non-member RAISES — pure's cast exception) ran in
     * the segment below. The subtype's key columns pair with the composed
     * row's shared-key threads ({@code <col>__pk_<table>}, the union's
     * merge) or its plain column.
     */
    /** The below-ops application hook (StoreResolver.belowOpsApplied). */
    interface Below {
        TypedSpec apply(ClassSource src, List<TypedSpec> ops,
                StoreResolver.Context context);
    }

    static ClassSource reRoot(ModelContext ctx, ClassSources sources,
            Callees callees, IntSupplier fresh, Below below,
            ClassSource src, String subFqn, StoreResolver.Context context,
            List<TypedSpec> belowOps) {
        TypedSpec left = belowOps.isEmpty() ? src.pipeline()
                : below.apply(src, belowOps, context);
        ClassSource t = sources.get(src.mappingFqn(), subFqn, src.scope());
        // the subtype's extent MATERIALIZED without its navigate slots (a
        // hop above the cast splices the class's own step onto the composed
        // row — NavProvenance.spliceOwnStep — exactly as any composed
        // source serves a slot its composition did not carry)
        TypedSpec right = Pipelines.materialize(t.pipeline(), java.util.Set.of(),
                subFqn).pipeline();
        Type.RelationType leftRow = Type.requireRelationSchema(left.info().type());
        Type.RelationType rightRow = Type.requireRelationSchema(right.info().type());
        List<String> pk = List.of();
        var md = ctx.findMapping(src.mappingFqn()).orElse(null);
        if (md != null) {
            for (var cb : md.classBindingsWithIncludes(ctx::findMapping)) {
                if (cb.classFqn().equals(subFqn)) {
                    pk = cb.primaryKeyColumns();
                    break;
                }
            }
        }
        if (pk.isEmpty()) {
            throw new NotImplementedException("->cast(@" + subFqn + ") below a"
                    + " flatten hop: the subtype's set declares no ~primaryKey"
                    + " — the re-root has no key to join on");
        }
        var one = com.legend.compiler.element.type.Multiplicity.Bounded.ONE;
        ExprType boolOne = new ExprType(Type.Primitive.BOOLEAN, one);
        String lv = "_cl" + fresh.getAsInt();
        String rv = "_cr" + fresh.getAsInt();
        TypedSpec cond = null;
        for (String c : pk) {
            Type.Column lc = leftRow.columns().stream()
                    .filter(x -> x.name().startsWith(src.composedPrefix() + c + "__pk"))
                    .findFirst()
                    .or(() -> leftRow.columns().stream()
                            .filter(x -> x.name().equals(src.composedPrefix() + c))
                            .findFirst())
                    .orElseThrow(() -> new NotImplementedException("->cast(@"
                            + subFqn + ") below a flatten hop: the composed row"
                            + " carries no key column '" + c + "' under prefix '"
                            + src.composedPrefix() + "'"));
            Type.Column rc = rightRow.columns().stream()
                    .filter(x -> x.name().equals(c)).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "resolver bug: subtype row lacks its own key column " + c));
            TypedSpec eq = new TypedNativeCall(java.util.Objects.requireNonNull(
                    callees.equal(), "resolver bug: no equal registration"),
                    List.of(new TypedPropertyAccess(
                                    new TypedVariable(lv, ExprType.one(leftRow)),
                                    lc.name(), new ExprType(lc.type(), lc.multiplicity())),
                            new TypedPropertyAccess(
                                    new TypedVariable(rv, ExprType.one(rightRow)),
                                    rc.name(), new ExprType(rc.type(), rc.multiplicity()))),
                    boolOne);
            cond = cond == null ? eq
                    : new TypedNativeCall(callees.bool("and"), List.of(cond, eq), boolOne);
        }
        TypedLambda condLam = new TypedLambda(List.of(lv, rv),
                List.of(java.util.Objects.requireNonNull(cond)),
                new ExprType(new Type.FunctionType(
                        List.of(new Type.Param(leftRow, one), new Type.Param(rightRow, one)),
                        new Type.Param(Type.Primitive.BOOLEAN, one)), one));
        String prefix = "as_" + subFqn.substring(subFqn.lastIndexOf(':') + 1)
                .toLowerCase(java.util.Locale.ROOT) + "_";
        List<Type.Column> cols = new ArrayList<>(leftRow.columns());
        for (Type.Column c : rightRow.columns()) {
            cols.add(new Type.Column(prefix + c.name(), c.type(), c.multiplicity()));
        }
        Type.RelationType row = new Type.RelationType(cols);
        ExprType rowInfo = new ExprType(row, one);
        TypedSpec joined = new TypedJoin(left, right,
                AssociationJoins.leftKind(), condLam, Optional.of(prefix),
                ViewFrames.frameNameOf(ctx, t),
                new ExprType(Type.relation(row), one), false /* resolver-synth */);
        Map<String, TypedSpec> bindings = new LinkedHashMap<>();
        for (var e : t.bindings().entrySet()) {
            bindings.put(e.getKey(), FlattenOps.prefixBinding(e.getValue(),
                    t.rowVar(), prefix, src.rowVar(), rowInfo));
        }
        return new ClassSource(src.mappingFqn(), subFqn, t.setId(), joined,
                src.rowVar(), bindings, row)
                .withComposedPrefix(prefix).withScope(src.scope());
    }
}
