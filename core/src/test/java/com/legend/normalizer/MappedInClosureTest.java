// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0
package com.legend.normalizer;

import com.legend.compiler.ModelBuilder;
import com.legend.compiler.NameResolver;
import com.legend.model.MappingDefinition;
import com.legend.model.NormalizedModel;
import com.legend.model.ParsedModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3.3 witness: whether a class is MAPPED is a question about ONE mapping's
 * closure (engine R1), never about the graph. A class another mapping maps
 * is not navigable here; an implicit Operation set of this mapping counts;
 * and the compiled mappings do not depend on the element order.
 */
class MappedInClosureTest {

    private static Map<String, ResolvedMapping> resolve(String src) {
        ParsedModel resolved = NameResolver.resolve(com.legend.testing.Own.model(src));
        ModelBuilder index = ModelBuilder.from(resolved);
        return MappingPrePass.run(resolved, index, null);
    }

    private static Set<String> mappedFor(Map<String, ResolvedMapping> pre, String mapping) {
        return MappingLedger.mappedInClosure(pre.get(mapping), pre);
    }

    /** Vehicle is UNMAPPED; the association end targets it while the
     * routed set maps the strict subclass Car — the construction appends an
     * implicit Inheritance set for Vehicle (ImplicitInheritance case a). */
    private static final String IMPLYING = """
            ###Mapping
            Mapping w::Implying (
              *w::Owner : Relational { ~mainTable [w::DB] OWNER name: OWNER.NAME }
              *w::Car[car] : Relational { ~mainTable [w::DB] CAR plate: CAR.PLATE }
              w::OwnerVehicle : Relational { AssociationMapping (
                vehicles[w_Owner, car]: [w::DB] @OwnerCar,
                owner[car, w_Owner]: [w::DB] @OwnerCar ) }
            )
            """;
    private static final String UNRELATED = """
            ###Mapping
            Mapping w::Unrelated (
              *w::Garage : Relational { ~mainTable [w::DB] GARAGE name: GARAGE.NAME }
            )
            """;
    private static final String INCLUDER = """
            ###Mapping
            Mapping w::Includer (
              include w::Unrelated
            )
            """;
    private static final String CLASSES = """
            Class w::Owner { name: String[1]; }
            Class w::Vehicle { plate: String[1]; }
            Class w::Car extends w::Vehicle { doors: Integer[0..1]; }
            Class w::Garage { name: String[1]; }
            Association w::OwnerVehicle { vehicles: w::Vehicle[*]; owner: w::Owner[0..1]; }
            ###Relational
            Database w::DB (
              Table OWNER (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
              Table CAR (ID INTEGER PRIMARY KEY, PLATE VARCHAR(20), OWNER_ID INTEGER)
              Table GARAGE (ID INTEGER PRIMARY KEY, NAME VARCHAR(100))
              Join OwnerCar (OWNER.ID = CAR.OWNER_ID)
            )
            """;

    @Test
    @DisplayName("mapped is per closure: own sets, included sets, this mapping's implicit sets; nothing from elsewhere")
    void mappedIsClosureLocal() {
        Map<String, ResolvedMapping> pre = resolve(CLASSES + IMPLYING + UNRELATED + INCLUDER);
        Set<String> implying = mappedFor(pre, "w::Implying");
        assertTrue(implying.contains("w::Vehicle"), "the implied Inheritance set for Vehicle");
        assertTrue(implying.contains("w::Car"));
        assertTrue(implying.contains("w::Owner"));
        assertFalse(implying.contains("w::Garage"), "mapped by an unrelated mapping only");
        Set<String> unrelated = mappedFor(pre, "w::Unrelated");
        assertEquals(Set.of("w::Garage"), unrelated);
        Set<String> includer = mappedFor(pre, "w::Includer");
        assertEquals(Set.of("w::Garage"), includer, "an include brings its sets; nothing else does");
        assertFalse(includer.contains("w::TotallyUnknown"));
    }

    private static MappingDefinition mapping(NormalizedModel m, String fqn) {
        return m.elements().stream()
                .filter(e -> e instanceof MappingDefinition d && d.qualifiedName().equals(fqn))
                .map(e -> (MappingDefinition) e).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the compiled mappings are the same in either element order (no order-dependent synthesis)")
    void compiledMappingsIgnoreElementOrder() {
        NormalizedModel a = com.legend.testing.Phases.normalize(
                NameResolver.resolve(com.legend.testing.Own.model(CLASSES + IMPLYING + UNRELATED)));
        NormalizedModel b = com.legend.testing.Phases.normalize(
                NameResolver.resolve(com.legend.testing.Own.model(CLASSES + UNRELATED + IMPLYING)));
        for (String fqn : new String[] {"w::Implying", "w::Unrelated"}) {
            MappingDefinition ma = mapping(a, fqn);
            MappingDefinition mb = mapping(b, fqn);
            assertEquals(ma.classBindings().stream().map(cb -> cb.classFqn()).sorted().toList(),
                    mb.classBindings().stream().map(cb -> cb.classFqn()).sorted().toList(), fqn);
            assertEquals(ma.facts(), mb.facts(), fqn);
        }
        // the implied set IS a binding of the implying mapping
        assertTrue(mapping(a, "w::Implying").classBindings().stream()
                .anyMatch(cb -> cb.classFqn().equals("w::Vehicle")),
                mapping(a, "w::Implying").classBindings().toString());
    }
}
