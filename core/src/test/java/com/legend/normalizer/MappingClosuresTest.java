// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.NameResolver;
import com.legend.model.ClassMapping;
import com.legend.model.LegacyMappingDefinition;
import com.legend.model.ParsedModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 step 4a witness: the include-order facts of a mapping are ONE
 * memoized computation over the mappings' surfaces, and each accessor
 * keeps the rule of the walker it replaced.
 */
class MappingClosuresTest {

    private static final String MODEL = """
            Class w::Person { name: String[1]; }
            Class w::Firm { legalName: String[1]; }
            Class w::Doc { title: String[1]; }
            Enum w::Kind { A, B }
            ###Relational
            Database w::DB (
              Table PERSON (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
              Table FIRM (ID INTEGER PRIMARY KEY, LEGAL_NAME VARCHAR(100))
            )
            Database w::DB2 ( include w::DB )
            ###Mapping
            Mapping w::Deep (
              *w::Firm[deepFirm] : Relational { ~mainTable [w::DB] FIRM legalName: FIRM.LEGAL_NAME }
              w::Kind : EnumerationMapping { A: ['a'], B: ['b'] }
            )
            Mapping w::First ( include w::Deep
              *w::Person[firstPerson] : Relational { ~mainTable [w::DB] PERSON name: PERSON.NAME }
            )
            Mapping w::Second (
              *w::Person[firstPerson] : Relational { ~mainTable [w::DB] PERSON name: PERSON.NAME }
            )
            Mapping w::Top ( include w::First include w::Second[w::DB->w::DB2] )
            ###Runtime
            Runtime w::RT
            {
              mappings: [ w::Deep ];
              connections:
              [
                ModelStore:
                [
                  json: #{
                    JsonModelConnection
                    {
                      class: w::Doc;
                      url: 'data:application/json,{}';
                    }
                  }#
                ]
              ];
            }
            """;

    private static MappingClosures closures() {
        ParsedModel resolved = NameResolver.resolve(com.legend.testing.Own.model(MODEL));
        ModelBuilder index = ModelBuilder.from(resolved);
        return MappingClosures.of(index);
    }

    @Test
    @DisplayName("the closure is one memoized fact per mapping, over surfaces that carry the JSON identity sets")
    void memoizedOverSurfaces() {
        MappingClosures c = closures();
        assertSame(c.closure("w::Top"), c.closure("w::Top"));
        // Deep's surface carries the identity set Doc the runtime binds through it,
        // and an INCLUDER sees it (the engine's cross-bake; visible before step 2)
        assertTrue(c.closure("w::First").sets().values().stream()
                .anyMatch(cm -> cm.className().equals("w::Doc") && cm instanceof ClassMapping.Relational r
                        && r.sourceUrl() != null));
        assertEquals(List.of("w::First", "w::Deep", "w::Second"),
                c.closure("w::Top").mappings().stream().map(LegacyMappingDefinition::qualifiedName).toList());
    }

    @Test
    @DisplayName("visible sets: a later include overrides an earlier one, and its store substitution applies")
    void laterIncludeWinsAndSubstitutes() {
        MappingClosures c = closures();
        ClassMapping person = c.closure("w::Top").sets().get("firstPerson");
        assertNotNull(person);
        // Second's set (the later include), re-rooted on DB2 by the include's substitution
        assertEquals("w::DB2", ((ClassMapping.Relational) person).mainTable().database());
        assertNotNull(c.closure("w::Top").sets().get("deepFirm"), "a grandparent's set is visible");
    }

    @Test
    @DisplayName("roots and enumeration mappings compose through the includes")
    void rootsAndEnums() {
        MappingClosures c = closures();
        assertEquals("deepFirm", c.closure("w::Top").roots().get("w::Firm").setId());
        assertEquals(1, c.closure("w::Top").enumerationMappings().size());
        assertTrue(c.closure("w::Second").enumerationMappings().isEmpty());
    }
}
