// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.testdatagen;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.spec.typed.TypedCsvCensus;
import com.legend.compiler.spec.typed.TypedSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The orchestration-time FOLD for {@link TypedCsvCensus} (TDG lane S1):
 * the carrier arrives from the checker holding the protocol query and
 * mapping FQN; this fold runs the production census (Java ORCHESTRATES
 * — the census is model-space, no execution) and replaces the node with
 * instance literals, so every downstream navigation lowers through the
 * ordinary pipeline. Lives in testdatagen because the census
 * implementation does (compiler must not depend upward — the carrier
 * pattern exists exactly for this layering).
 */
public final class TdgNatives {

    private static final String DATA_FQN =
            "meta::relational::metamodel::data::RelationalCSVData";
    private static final String TABLE_FQN =
            "meta::relational::metamodel::data::RelationalCSVTable";

    private TdgNatives() {
    }

    /** Replace every {@link TypedCsvCensus} under {@code stmt} with its
     * folded instance-literal result. */
    public static TypedSpec foldCensus(TypedSpec stmt, ModelContext ctx) {
        if (stmt instanceof TypedCsvCensus cc) {
            return literal(cc, ctx);
        }
        List<TypedSpec> kids = stmt.children();
        if (kids.isEmpty()) {
            return stmt;
        }
        List<TypedSpec> out = new ArrayList<>(kids.size());
        boolean changed = false;
        for (TypedSpec k : kids) {
            TypedSpec r = foldCensus(k, ctx);
            changed |= r != k;
            out.add(r);
        }
        return changed ? stmt.withChildren(out) : stmt;
    }

    private static TypedSpec literal(TypedCsvCensus cc, ModelContext ctx) {
        // the census computes HERE (its implementation lives in this
        // layer); the typed literals are COMPILER-minted (invariant 7)
        // by the checker's own factory — a downward call
        return com.legend.compiler.spec.CsvCensusChecker.literal(
                TestDataGenerator.necessaryColumns(
                        ctx, cc.query(), cc.mappingFqn()),
                cc.info());
    }
}
