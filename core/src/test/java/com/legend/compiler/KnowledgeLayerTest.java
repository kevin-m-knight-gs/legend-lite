// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler;

import com.legend.Compiler;
import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.Property;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.normalizer.ModelNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4.1 step 1 witness: association QUALIFIED properties adopt into their
 * owning class in the knowledge layer (F1), before Phase E. Modeled on the
 * corpus's {@code meta::relational::tests::model::simple::ProdSynonym}
 * (distinct ends, a parameterized qualifier, both return multiplicities),
 * plus the self-association and the no-unique-end error.
 */
class KnowledgeLayerTest {

    private static final String PROD_SYNONYM = """
            Class w::Product { name: String[1]; }
            Class w::Synonym { name: String[1]; type: String[1]; }
            Association w::ProdSynonym
            {
              synonyms: w::Synonym[*];
              product: w::Product[1];
              synonymByType(type: String[1]) { $this.synonyms->filter(s | $s.type == $type)->toOne() }: w::Synonym[1];
              synonymsByTypes(types: String[*]) { $this.synonyms->filter(s | $s.type->in($types)) }: w::Synonym[*];
            }
            """;

    private static Property.Derived derived(ModelContext ctx, String cls, String name) {
        return ctx.findClass(cls).orElseThrow().properties().stream()
                .filter(p -> p instanceof Property.Derived d && d.name().equals(name))
                .map(p -> (Property.Derived) p)
                .findFirst().orElseThrow(() -> new AssertionError(
                        cls + " does not carry qualified property " + name));
    }

    private static boolean carries(ModelContext ctx, String cls, String name) {
        return ctx.findClass(cls).orElseThrow().properties().stream()
                .anyMatch(p -> p instanceof Property.Derived d && d.name().equals(name));
    }

    @Test
    @DisplayName("distinct ends: the OTHER end's class owns the qualified property (both multiplicities)")
    void distinctEndsOtherEndOwns() {
        ModelContext ctx = Compiler.compileModel(PROD_SYNONYM);
        // both return Synonym, so Product (the other end) owns both
        derived(ctx, "w::Product", "synonymByType");
        derived(ctx, "w::Product", "synonymsByTypes");
        assertFalse(carries(ctx, "w::Synonym", "synonymByType"));
        assertFalse(carries(ctx, "w::Synonym", "synonymsByTypes"));
        // the adopted property lifts through the ONE class-derived funnel
        assertEquals(SynthFqn.prop("w::Product", "synonymByType"),
                derived(ctx, "w::Product", "synonymByType").bodyFunctionFqn());
    }

    @Test
    @DisplayName("parameterized qualifier: the parameter rides the adopted property")
    void parameterizedQualifierKeepsItsParameter() {
        ModelContext ctx = Compiler.compileModel(PROD_SYNONYM);
        Property.Derived byType = derived(ctx, "w::Product", "synonymByType");
        assertEquals(1, byType.parameters().size(), byType.parameters().toString());
        assertEquals("type", byType.parameters().get(0).name());
        Property.Derived byTypes = derived(ctx, "w::Product", "synonymsByTypes");
        assertEquals("types", byTypes.parameters().get(0).name());
    }

    @Test
    @DisplayName("self-association: the one class owns its qualified property")
    void selfAssociationOwnsItself() {
        ModelContext ctx = Compiler.compileModel("""
                Class w::Person { name: String[1]; }
                Association w::Friends
                {
                  a: w::Person[*];
                  b: w::Person[*];
                  friendsNamed(n: String[1]) { $this.a->filter(p | $p.name == $n) }: w::Person[*];
                }
                """);
        derived(ctx, "w::Person", "friendsNamed");
    }

    private static final String NO_UNIQUE_END = """
            Class w::Product { name: String[1]; }
            Class w::Synonym { name: String[1]; }
            Class w::Other { name: String[1]; }
            Association w::ProdSynonym
            {
              synonyms: w::Synonym[*];
              product: w::Product[1];
              stray() { $this.synonyms->map(s | ^w::Other(name = $s.name)) }: w::Other[*];
            }
            """;

    @Test
    @DisplayName("no unique owning end: a MODEL error — strict throws, a module build walls the association")
    void noUniqueOwningEnd() {
        ModelException strict = assertThrows(ModelException.class,
                () -> Compiler.compileModel(NO_UNIQUE_END));
        assertEquals(LegendCompileException.Phase.MODEL, strict.phase());
        assertTrue(strict.getMessage().contains(
                "qualified property 'stray' returns 'w::Other', which does not identify"
                        + " a unique owning end"), strict.getMessage());
        Compiler.BuiltModule module = Compiler.buildModule(Compiler.parseSources(List.of(
                new Compiler.ModelSource("m.pure", NO_UNIQUE_END))).model());
        // same message; the strict path additionally carries the element's
        // [line:col] because the error now names the association
        String wall = module.walls().get("w::ProdSynonym");
        assertTrue(wall != null && strict.getMessage().endsWith(wall),
                strict.getMessage() + " vs " + module.walls());
        // the rest of the module stands; nothing adopted the stray property
        assertFalse(carries(module.context(), "w::Product", "stray"));
        assertFalse(carries(module.context(), "w::Synonym", "stray"));
    }

    @Test
    @DisplayName("the normalizer refuses a model the knowledge layer has not adopted")
    void normalizerRequiresAdoption() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> com.legend.testing.Phases.normalize(
                        NameResolver.resolve(com.legend.testing.Own.model(PROD_SYNONYM))));
        assertTrue(ex.getMessage().contains("w::ProdSynonym"), ex.getMessage());
        assertTrue(ex.getMessage().contains("synonymByType"), ex.getMessage());
    }
}
