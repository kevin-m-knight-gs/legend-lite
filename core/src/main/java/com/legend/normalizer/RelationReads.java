// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.error.NotImplementedException;
import com.legend.model.ClassDefinition;
import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.Multiplicity;
import com.legend.model.TypeExpression;
import com.legend.model.spec.AppliedFunction;
import com.legend.model.spec.AppliedProperty;
import com.legend.model.spec.LambdaFunction;
import com.legend.model.spec.PureCollection;
import com.legend.model.spec.ValueSpecification;
import com.legend.model.spec.Variable;

import java.util.List;
import java.util.Map;

/**
 * XStore/ModelJoin condition rewriting: {@code $this.p}/{@code $that.p}
 * property reads become COLUMN reads on the two relation rows (relocated
 * from {@link MappingNormalizer} at the file guardrail). A [1]-DECLARED
 * property reads through {@code toOne} — the column view types [0..1]
 * (physical nullability) and would summon comparison guards the engine
 * never spells (the multiplicity-masking trap, 4th sighting).
 */
final class RelationReads {

    private RelationReads() {
    }

    /** {@code $this.p}/{@code $that.p} → column reads on the two relation rows. */
    static ValueSpecification xstore(ValueSpecification v,
            Variable thisRow, ClassMapping.@com.legend.Nullable RelationFunction thisRf,
            Variable thatRow, ClassMapping.@com.legend.Nullable RelationFunction thatRf,
            String assocName, LegacyMappingDefinition md, ModelBuilder model) {
        return rewrite(v,
                Map.of("this", thisRow, "that", thatRow),
                Map.of("this", thisRf, "that", thatRf), assocName, md,
                Map.of(), model);
    }

    /** {@code $var.prop} → the var's Relation mapping's COLUMN read. */


    static ValueSpecification rewrite(ValueSpecification v,
            Map<String, Variable> rowByVar,
            Map<String, ClassMapping.RelationFunction> rfByVar,
            String assocName, LegacyMappingDefinition md,
            @com.legend.Nullable Map<String, Map<String, Map<String, String>>> nestedCols) {
        return rewrite(v, rowByVar, rfByVar, assocName, md,
                nestedCols, null);
    }

    static ValueSpecification rewrite(ValueSpecification v,
            Map<String, Variable> rowByVar,
            Map<String, ClassMapping.RelationFunction> rfByVar,
            String assocName, LegacyMappingDefinition md,
            @com.legend.Nullable Map<String, Map<String, Map<String, String>>> nestedCols,
            @com.legend.Nullable ModelBuilder model) {
        // NESTED hop read: $end.assocProp.leaf resolves to the nested
        // target's column on the END's composite row
        if (v instanceof AppliedProperty ap0
                && ap0.receiver() instanceof AppliedProperty mid0
                && mid0.receiver() instanceof Variable var0
                && nestedCols != null
                && nestedCols.getOrDefault(var0.name(), Map.of())
                        .containsKey(mid0.property())) {
            Map<String, String> leafCols = java.util.Objects.requireNonNull(
                    java.util.Objects.requireNonNull(nestedCols.get(var0.name()))
                            .get(mid0.property()));
            String col = leafCols.get(ap0.property());
            if (col == null) {
                throw new NotImplementedException(
                        "association '" + assocName + "': $" + var0.name()
                        + "." + mid0.property() + "." + ap0.property()
                        + " has no column binding on the nested Relation"
                        + " mapping (mapping=" + md.qualifiedName() + ")");
            }
            // the SLOT-READ spelling ($row.<navSlot>.<COL>) — typed and
            // demanded by the stock navigate-step machinery
            return new AppliedProperty(new AppliedProperty(
                    rowByVar.get(var0.name()), mid0.property()), col);
        }
        if (v instanceof AppliedProperty ap
                && ap.receiver() instanceof Variable var
                && rowByVar.containsKey(var.name())) {
            ClassMapping.RelationFunction rf = java.util.Objects.requireNonNull(rfByVar.get(var.name()));
            for (ClassMapping.RelationFunction.Col c : rf.columns()) {
                if (c.property().equals(ap.property()) && c.column() != null) {
                    ValueSpecification read = new AppliedProperty(
                            rowByVar.get(var.name()), c.column());
                    // conform-by-emission: a [1]-DECLARED property reads
                    // through toOne — the column view types [0..1]
                    // (physical nullability) and would summon comparison
                    // guards the engine never spells (masking trap #4)
                    ClassDefinition rcd = model == null ? null
                            : model.findClass(rf.className()).orElse(null);
                    if (model != null && rcd != null
                            && Multiplicity.Concrete.PURE_ONE.equals(
                            findPropertyDeclared(rcd, ap.property(), model))) {
                        read = new AppliedFunction("toOne", List.of(read));
                    }
                    return read;
                }
            }
            throw new NotImplementedException(
                    "association '" + assocName + "': $" + var.name() + "."
                    + ap.property() + " has no column binding on the Relation"
                    + " mapping of '" + rf.className() + "' (mapping="
                    + md.qualifiedName() + ")");
        }
        return switch (v) {
            case AppliedFunction af -> new AppliedFunction(af.function(),
                    af.parameters().stream().map(x -> rewrite(x,
                            rowByVar, rfByVar, assocName, md, nestedCols,
                            model)).toList());
            case AppliedProperty ap2 -> new AppliedProperty(
                    rewrite(ap2.receiver(), rowByVar, rfByVar,
                            assocName, md, nestedCols, model), ap2.property());
            case PureCollection pc -> new PureCollection(
                    pc.values().stream().map(x -> rewrite(x,
                            rowByVar, rfByVar, assocName, md, nestedCols,
                            model)).toList());
            case LambdaFunction lf2 -> new LambdaFunction(lf2.parameters(),
                    lf2.body().stream().map(x -> rewrite(x,
                            rowByVar, rfByVar, assocName, md, nestedCols,
                            model)).toList());
            default -> v;
        };
    }

    static @com.legend.Nullable Multiplicity findPropertyDeclared(
            ClassDefinition owner, String prop, ModelBuilder model) {
        for (ClassDefinition.PropertyDefinition pd
                : owner.properties()) {
            if (pd.name().equals(prop)) {
                return pd.multiplicity();
            }
        }
        for (TypeExpression sup : owner.superClasses()) {
            if (sup instanceof TypeExpression.NameRef nr) {
                ClassDefinition sc = model.findClass(nr.name()).orElse(null);
                if (sc != null) {
                    Multiplicity m = findPropertyDeclared(sc, prop, model);
                    if (m != null) {
                        return m;
                    }
                }
            }
        }
        return null;
    }
}
