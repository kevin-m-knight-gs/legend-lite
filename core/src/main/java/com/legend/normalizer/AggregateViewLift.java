// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.model.ClassMapping;
import com.legend.model.FunctionDefinition;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.MappingDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The AggregationAware node's aggregate views at normalization: each view
 * compiles as a SET like any set (its own lifted function, a non-root
 * binding under its own id); the main set's binding carries the views'
 * specification FACTS for the router ({@code AggregationAwareRouting}).
 */
final class AggregateViewLift {

    private AggregateViewLift() {
    }

    /** The AggregationAware node's views compile as SETS like any set
     * (non-root, their own ids); the main binding carries their
     * specification facts for the router. */
    static void lift(LegacyMappingDefinition md,
            ClassMapping.Relational aggMain, ModelBuilder model,
            List<FunctionDefinition> lifted,
            List<MappingDefinition.ClassBinding> classBindings,
            Map<String, MappingDefinition.ClassBinding.DeclaredKeys> declaredKeys) {
        for (ClassMapping.AggregateView view : java.util.Objects
                .requireNonNull(aggMain.aggregation()).views()) {
            FunctionDefinition viewFn = MappingNormalizer.synthesizeClassMapping(md, view.set(), model, true);
            lifted.add(viewFn);
            classBindings.add(new MappingDefinition.ClassBinding.Relational(
                    view.set().className(), view.set().setId(),
                    view.set().extendsSetId(), /*root*/ false,
                    viewFn.qualifiedName(),
                    MappingNormalizer.declaredPrimaryKeyColumns(view.set()),
                    declaredKeys.getOrDefault(SetKeyFacts.setKey(view.set()),
                            MappingDefinition.ClassBinding.DeclaredKeys.NONE),
                    MappingNormalizer.relationalSourceOf(view.set()),
                    List.of()));
        }
    }


    /** The view facts an AggregationAware main binding carries. */
    static List<MappingDefinition.ClassBinding.AggregateViewFacts> facts(
            ClassMapping.Relational main) {
        if (main.aggregation() == null) {
            return List.of();
        }
        List<MappingDefinition.ClassBinding.AggregateViewFacts> out = new ArrayList<>();
        for (ClassMapping.AggregateView v : main.aggregation().views()) {
            out.add(new MappingDefinition.ClassBinding.AggregateViewFacts(
                    java.util.Objects.requireNonNull(v.set().setId(), "view set id"),
                    v.canAggregate(), v.groupByFunctions(), v.aggregateValues()));
        }
        return out;
    }

}
