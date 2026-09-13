// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.element.MilestoningStrategy;
import com.legend.model.ClassDefinition;

/**
 * A class's TEMPORAL stereotype as a fold over its lineage (the kernel's
 * walk, T4.1 step 3c): a class is temporal when it or any ancestor
 * carries a milestoning stereotype. No hierarchy walk of its own. (The
 * bitemporal twin the normalizer carried had no caller and is gone.)
 */
final class MilestoningFacts {

    private MilestoningFacts() {}

    static boolean isTemporal(String classFqn, ModelBuilder model) {
        for (ClassDefinition cd : model.knowledge().lineage(classFqn)) {
            for (var st : cd.stereotypes()) {
                if (MilestoningStrategy.ofStereotypeOrNull(st.profileName(), st.stereotypeName()) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
