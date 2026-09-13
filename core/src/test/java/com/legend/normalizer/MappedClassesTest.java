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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 step 2, verified item 1: the mapped-class fact is computed ONCE
 * from every mapping's pre-passed class mappings (explicit and implicit),
 * so a mapping's answer never depends on which mappings normalized
 * before it. The corpus probe of 2026-09-13 found the old index write
 * order-dependent across unrelated mappings (FunctionScope, the
 * milestoned Vehicle).
 */
class MappedClassesTest {

    private static MappedClasses mapped(String src) {
        ParsedModel resolved = NameResolver.resolve(com.legend.testing.Own.model(src));
        ModelBuilder index = ModelBuilder.from(resolved);
        Map<String, MappingPrePass.PrePassed> pre = MappingPrePass.run(resolved, index, null);
        return MappedClasses.of(pre.values().stream().map(MappingPrePass.PrePassed::md).toList(),
                resolved.elements());
    }

    @Test
    @DisplayName("explicit class mappings, across mappings; unmapped and unknown classes are not mapped")
    void explicitClassMappings() {
        MappedClasses m = mapped("""
                Class model::Person { name: String[1]; }
                Class model::Other { x: String[1]; }
                ###Mapping
                Mapping pkg::M1 ( *model::Person: Pure { ~src model::Person name: $src.name } )
                Mapping pkg::M2 ( *model::Person: Pure { ~src model::Person name: $src.name } )
                """);
        assertTrue(m.contains("model::Person"));
        assertFalse(m.contains("model::Other"));
        assertFalse(m.contains("model::TotallyUnknown"));
    }

    /** Vehicle is UNMAPPED; the association end targets it while the
     * routed set maps the strict subclass Car — the pre-pass appends an
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
    @DisplayName("an implicit Operation set counts, and every mapping sees the same fact whatever the element order")
    void implicitSetsAreMappedOrderIndependently() {
        MappedClasses first = mapped(CLASSES + IMPLYING + UNRELATED);
        MappedClasses second = mapped(CLASSES + UNRELATED + IMPLYING);
        for (MappedClasses m : new MappedClasses[] {first, second}) {
            assertTrue(m.contains("w::Vehicle"), "the implied Inheritance set for Vehicle");
            assertTrue(m.contains("w::Car"));
            assertTrue(m.contains("w::Garage"));
        }
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

    private static MappingDefinition mapping(NormalizedModel n, String fqn) {
        return n.elements().stream()
                .filter(e -> e instanceof MappingDefinition md && md.qualifiedName().equals(fqn))
                .map(MappingDefinition.class::cast).findFirst().orElseThrow();
    }
}
