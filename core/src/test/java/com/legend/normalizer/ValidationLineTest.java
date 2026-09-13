// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.Compiler;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 steps 5–6 witness — §6's line: a USER-model error the engine
 * rejects at compile time is rejected by a STRICT build and deferred by a
 * MODULE build (the set poisoned, the reason raised at use); a ROADMAP
 * gap defers in both.
 */
class ValidationLineTest {

    private static final String BAD_PM_NAME = """
            Class w::Person { name: String[1]; }
            ###Relational
            Database w::DB ( Table PERSON (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), AGE INTEGER) )
            ###Mapping
            Mapping w::M (
              *w::Person : Relational { ~mainTable [w::DB] PERSON name: PERSON.NAME, age: PERSON.AGE }
            )
            """;

    private static final String MISSING_JOIN = """
            Class w::Person { name: String[1]; firm: w::Firm[0..1]; }
            Class w::Firm { legalName: String[1]; }
            ###Relational
            Database w::DB (
              Table PERSON (ID INTEGER PRIMARY KEY, NAME VARCHAR(100), FIRM_ID INTEGER)
              Table FIRM (ID INTEGER PRIMARY KEY, LEGAL_NAME VARCHAR(100))
            )
            ###Mapping
            Mapping w::M (
              *w::Person : Relational { ~mainTable [w::DB] PERSON name: PERSON.NAME, firm: [w::DB] @Nonexistent }
              *w::Firm : Relational { ~mainTable [w::DB] FIRM legalName: FIRM.LEGAL_NAME }
            )
            """;

    @Test
    @DisplayName("validation before synthesis: a property mapping naming no property — strict rejects, module poisons the set")
    void badPropertyMappingName() {
        ModelException strict = assertThrows(ModelException.class,
                () -> Compiler.compileModel(BAD_PM_NAME));
        assertEquals(LegendCompileException.Phase.MODEL, strict.phase());
        assertTrue(strict.getMessage().contains("PropertyMapping 'age'"), strict.getMessage());

        Compiler.BuiltModule module = Compiler.buildModule(Compiler.parseSources(List.of(
                new Compiler.ModelSource("m.pure", BAD_PM_NAME))).model());
        assertTrue(module.context().findMapping("w::M").isPresent(), "the mapping still loads");
        String poison = module.context().mappingPoison("w::M", "w::Person").orElseThrow();
        assertTrue(poison.contains("PropertyMapping 'age'"), poison);
    }

    @Test
    @DisplayName("a user-model error inside synthesis: strict rejects, module poisons")
    void missingJoinInsideSynthesis() {
        ModelException strict = assertThrows(ModelException.class,
                () -> Compiler.compileModel(MISSING_JOIN));
        assertTrue(strict.getMessage().contains("Nonexistent"), strict.getMessage());
        Compiler.BuiltModule module = Compiler.buildModule(Compiler.parseSources(List.of(
                new Compiler.ModelSource("m.pure", MISSING_JOIN))).model());
        String poison = module.context().mappingPoison("w::M", "w::Person").orElseThrow();
        assertTrue(poison.contains("Nonexistent"), poison);
        // the healthy class of the same mapping stays bound
        assertTrue(module.context().mappingPoison("w::M", "w::Firm").isEmpty());
    }
}
