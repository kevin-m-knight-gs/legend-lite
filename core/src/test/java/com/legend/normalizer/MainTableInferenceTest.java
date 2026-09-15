// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.model.MappingDefinition;
import com.legend.model.NormalizedModel;
import com.legend.model.ParsedModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main-table inference without {@code ~mainTable}, on the engine's rule
 * (docs/TRANSLATOR_AUDIT_2026_09_15.md T1 / F3): the relational compiler
 * collects every DIRECT column reference the class mapping's property
 * mappings make — inside a computed expression too — and processes a
 * join's terminal with a fresh alias map, so joined tables never count
 * ({@code HelperRelationalBuilder.java:1172} and {@code :1182}); an
 * otherwise-embedded block's own property mappings read the owner's row
 * like a plain embedded block.
 */
class MainTableInferenceTest {

    private static String sourceTableOf(String modelText, String classFqn) {
        ParsedModel parsed = com.legend.testing.Own.model(modelText);
        NormalizedModel normalized = com.legend.testing.Phases.normalize(parsed);
        for (var el : normalized.elements()) {
            if (el instanceof MappingDefinition md) {
                assertTrue(md.facts().poisons().isEmpty(),
                        () -> "poisoned: " + md.facts().poisons());
                for (var cb : md.classBindings()) {
                    if (cb.classFqn().equals(classFqn)
                            && cb instanceof MappingDefinition.ClassBinding.Relational rb
                            && rb.source() instanceof MappingDefinition.RelationalSource.Table t) {
                        return t.table();
                    }
                }
            }
        }
        throw new AssertionError("no relational binding for " + classFqn);
    }

    @Test
    @DisplayName("F3a: a computed column that also navigates a join still names its direct table")
    void expressionWithJoinNavigationContributesItsDirectColumns() {
        // the only property mapping: concat(T_PERSON.FN, @PF | T_FIRM.NAME)
        // — T_PERSON.FN is a direct reference (counts), the join's terminal
        // T_FIRM.NAME is not (fresh map); the main table is T_PERSON
        assertEquals("T_PERSON", sourceTableOf(
                "Class m::P { name: String[1]; }"
                        + "\n###Relational\nDatabase db::DB ("
                        + "  Table T_PERSON (ID INTEGER, FN VARCHAR(10), FIRM_ID INTEGER)"
                        + "  Table T_FIRM (ID INTEGER, NAME VARCHAR(10))"
                        + "  Join PF (T_PERSON.FIRM_ID = T_FIRM.ID)"
                        + ")"
                        + "\n###Mapping\nMapping m::M ("
                        + "  *m::P: Relational {"
                        + "    name: concat([db::DB]T_PERSON.FN, [db::DB]@PF | T_FIRM.NAME)"
                        + "  }"
                        + ")", "m::P"));
    }

    @Test
    @DisplayName("F3b: an otherwise-embedded block's own column references name the main table")
    void otherwiseEmbeddedContributesItsDirectColumns() {
        assertEquals("T_PERSON", sourceTableOf(
                "Class m::P { firm: m::F[0..1]; } Class m::F { legalName: String[1]; }"
                        + "\n###Relational\nDatabase db::DB ("
                        + "  Table T_PERSON (ID INTEGER, FIRM_NAME VARCHAR(10), FIRM_ID INTEGER)"
                        + "  Table T_FIRM (ID INTEGER, NAME VARCHAR(10))"
                        + "  Join PF (T_PERSON.FIRM_ID = T_FIRM.ID)"
                        + ")"
                        + "\n###Mapping\nMapping m::M ("
                        + "  *m::P: Relational {"
                        + "    firm ("
                        + "      legalName: [db::DB]T_PERSON.FIRM_NAME"
                        + "    ) Otherwise ([f]: [db::DB] @PF)"
                        + "  }"
                        + "  m::F[f]: Relational { legalName: [db::DB]T_FIRM.NAME }"
                        + ")", "m::P"));
    }
}
